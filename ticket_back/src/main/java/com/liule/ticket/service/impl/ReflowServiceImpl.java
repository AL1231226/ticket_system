package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.entity.Notification;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.entity.Waitlist;
import com.liule.ticket.mapper.NotificationMapper;
import com.liule.ticket.mapper.UserPurchaseMapper;
import com.liule.ticket.service.IOrderService;
import com.liule.ticket.service.IReflowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.service.IWaitlistService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.RedisIdWorker;
import com.liule.ticket.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 回流票池：退票 / 取消 / 超时时，优先把票补给候补队首，队列空了才回公开池。
 * <p>
 * 整个「查队列 → 校验 → 建单 → 出队」的临界区由票档级 Redisson 锁
 * {@code ticker:lock:reflow:{tierId}} 串行化。锁持有到外层事务提交之后，
 * 否则另一个线程会在前一个事务未提交时读到旧库存/旧候补行。
 */
@Slf4j
@Service
public class ReflowServiceImpl implements IReflowService {

    private static final long LOCK_WAIT_SECONDS = 5;

    @Resource
    private ITicketTierService ticketTierService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private IWaitlistService waitlistService;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private UserPurchaseMapper userPurchaseMapper;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * {@code @Lazy}：OrderService 也依赖本服务（取消/退款调 release），
     * 不加会形成构造期循环依赖。
     */
    @Lazy
    @Resource
    private IOrderService orderService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long tierId, Long sessionId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return;
        }
        //获取回流分配锁
        String lockKey = RedisConstants.REFLOW_LOCK_KEY + tierId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 强锁失败，直接将这笔订单回流放回公开池
        if (!locked) {
            // 抢不到锁说明同一票档有另一笔回流正在分配。此时把票直接放回
            // 公开池，宁可少一次候补优先，也不能阻塞用户的退票/取消。
            log.error("票档 {} 回流锁获取失败，{} 张票直接回公开池（候补本轮不分配）",
                    tierId, quantity);
            returnAllToPublic(tierId, quantity);
            return;
        }

        try {
            allocate(tierId, sessionId, quantity);
        } finally {
            // 锁必须持有到事务提交/回滚之后。release 通常运行在
            // invalidateOrder / refundOrder 的事务里，方法返回时事务还没提交。
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                try {
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    safeUnlock(lock);
                                }
                            });
                } catch (Exception e) {
                    safeUnlock(lock);
                }
            } else {
                safeUnlock(lock);
            }
        }
    }

    /**
     * 在锁内完成分配。调用前事务已开启，DB 改动和建单会一起提交/回滚。
     */
    private void allocate(Long tierId, Long sessionId, Integer quantity) {
        // 1. 先把整批票从 lock_stock 还回 stock。
        //    Redis 库存先不动 —— 只有真正回公开池的部分才加，给候补的票不能出现在公开池。
        moveLockToStock(tierId, quantity);

        TicketTier tier = ticketTierService.getById(tierId);
        ShowSession session = showSessionService.getById(sessionId);
        if (tier == null || session == null || !sellable(tier, session)) {
            // 票档/场次整体不可售：不分配，队列保留，整批回公开池
            publishPublicStock(tierId, quantity);
            return;
        }

        String queueKey = RedisConstants.WAITLIST_QUEUE_KEY + tierId;
        List<RemovedMember> allocated = new ArrayList<>();
        registerRollbackCompensation(queueKey, allocated);

        int remaining = quantity;

        while (remaining > 0) {
            ZSetOperations.TypedTuple<String> head = headOf(queueKey);
            if (head == null) {
                break;
            }
            String member = head.getValue();
            Double score = head.getScore();
            Long userId = parseUserId(member);
            if (userId == null) {
                stringRedisTemplate.opsForZSet().remove(queueKey, member);
                continue;
            }

            Waitlist row = waitlistService.getOne(new LambdaQueryWrapper<Waitlist>()
                    .eq(Waitlist::getUserId, userId)
                    .eq(Waitlist::getTierId, tierId));
            if (row == null || !Integer.valueOf(0).equals(row.getStatus())
                    || !sessionId.equals(row.getSessionId())) {
                // ZSet 脏数据 / 用户已退出或已分配：清掉这条，继续看下一位
                stringRedisTemplate.opsForZSet().remove(queueKey, member);
                continue;
            }

            int want = row.getQuantity();
            if (want <= 0 || want > tier.getMaxPerOrder()) {
                // 需求数量已不合法，永远补不上：出队并置 3 已过期，不能堵住队首
                stringRedisTemplate.opsForZSet().remove(queueKey, member);
                waitlistService.markStatusById(row.getId(), 0, 3);
                continue;
            }

            if (want > remaining) {
                // v1 拍板：队首数量不够就停止等待，不跳过队首；剩余回公开池
                break;
            }

            // 限购再校验：DB 是权威值（Redis 计数可能被清空）
            Integer bought = userPurchaseMapper.getQuantity(userId, sessionId);
            int already = bought == null ? 0 : bought;
            int limit = session.getPurchaseLimit() == null
                    ? Integer.MAX_VALUE : session.getPurchaseLimit();
            if (already + want > limit) {
                // 这个用户在本场次已经买满，永远补不上：出队、置 3，继续下一位
                stringRedisTemplate.opsForZSet().remove(queueKey, member);
                waitlistService.markStatusById(row.getId(), 0, 3);
                continue;
            }

            // 先占位候补行：0→1。条件更新防并发退出/重复分配。
            Long orderId = redisIdWorker.create_ID("order");
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireTime = now.plusMinutes(SystemConstants.WAITLIST_PAY_TIMEOUT_MINUTES);
            if (!waitlistService.markNotified(row.getId(), orderId, now, expireTime)) {
                // 用户刚好退出，或已被其他流程改状态
                stringRedisTemplate.opsForZSet().remove(queueKey, member);
                continue;
            }

            // 同步建单（source=1 / 10min）。失败会抛异常，整个事务回滚；
            // 已出队的成员由 registerRollbackCompensation 补回。
            orderService.createWaitlistOrder(orderId, userId, tierId, sessionId, want, expireTime);

            notificationMapper.insert(new Notification()
                    .setUserId(userId)
                    .setType(1)
                    .setTitle("候补补位成功")
                    .setContent("您候补的「" + tier.getName()
                            + "」已补位成功，请在 10 分钟内完成支付")
                    .setBizId(orderId)
                    .setIsRead(0));

            // 出队并记下原 score：事务回滚时按原位置补回
            stringRedisTemplate.opsForZSet().remove(queueKey, member);
            allocated.add(new RemovedMember(member, score == null
                    ? System.currentTimeMillis() : score));
            remaining -= want;
        }

        if (remaining > 0) {
            // 只有真正回公开池的部分才加 Redis 库存
            publishPublicStock(tierId, remaining);
        }
    }

    /**
     * 把被订单占用的票从 lock_stock 还回 stock（只改 DB，不动 Redis）。
     */
    private void moveLockToStock(Long tierId, Integer quantity) {
        boolean returned = ticketTierService.update()
                .setSql("stock = stock + " + quantity
                        + ", lock_stock = lock_stock - " + quantity)
                .eq("id", tierId)
                .ge("lock_stock", quantity)
                .update();
        if (!returned) {
            // 正常情况锁定量一定够（下单时刚加过）。走到这说明数据被动过，
            // 抛出去让调用方事务回滚，避免「订单作废了、库存却没还回去」。
            log.error("库存释放失败：tierId={}, quantity={}，lock_stock 不足",
                    tierId, quantity);
            throw new IllegalStateException("票档 " + tierId + " 回补库存失败：lock_stock 不足");
        }
    }

    /**
     * 抢锁失败时的兜底：整批票回公开池，候补队列不动。
     */
    private void returnAllToPublic(Long tierId, Integer quantity) {
        moveLockToStock(tierId, quantity);
        publishPublicStock(tierId, quantity);
    }

    /**
     * Redis 公开库存 +n。放在事务提交后执行：DB 回滚时 Redis 不能先加。
     */
    private void publishPublicStock(Long tierId, int quantity) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + tierId;
        Runnable increment = () -> {
            try {
                // key 不存在时不要 INCRBY —— 那会凭空造出库存，DB 才是权威值
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                    stringRedisTemplate.opsForValue().increment(stockKey, quantity);
                }
            } catch (Exception e) {
                log.error("票档 {} Redis 库存 +{} 失败，等待对账", tierId, quantity, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            increment.run();
                        }
                    });
        } else {
            increment.run();
        }
    }

    /**
     * 事务回滚时把已出队的候补用户按原 score 补回队列。
     * <p>
     * DB 回滚不会回滚 Redis ZSet；不补的话，这个用户会凭空失去排队位置。
     */
    private void registerRollbackCompensation(String queueKey, List<RemovedMember> allocated) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_COMMITTED) {
                            return;
                        }
                        for (RemovedMember member : allocated) {
                            try {
                                stringRedisTemplate.opsForZSet()
                                        .add(queueKey, member.member(), member.score());
                            } catch (Exception e) {
                                log.error("候补成员 {} 回滚补偿失败，需人工处理",
                                        member.member(), e);
                            }
                        }
                        if (!allocated.isEmpty()) {
                            log.warn("回流分配回滚，已把 {} 位候补用户补回队列",
                                    allocated.size());
                        }
                    }
                });
    }

    private ZSetOperations.TypedTuple<String> headOf(String queueKey) {
        Set<ZSetOperations.TypedTuple<String>> head =
                stringRedisTemplate.opsForZSet().rangeWithScores(queueKey, 0, 0);
        if (head == null || head.isEmpty()) {
            return null;
        }
        return head.iterator().next();
    }

    private Long parseUserId(String member) {
        if (member == null) {
            return null;
        }
        try {
            return Long.valueOf(member);
        } catch (NumberFormatException e) {
            log.warn("候补队列里出现非用户 ID 成员：{}，已跳过", member);
            return null;
        }
    }

    /**
     * 票档和场次当前是否还能卖。已售罄（status=3）也要能分配回流票。
     */
    private boolean sellable(TicketTier tier, ShowSession session) {
        if (!Integer.valueOf(1).equals(tier.getStatus())) {
            return false;
        }
        if (!Integer.valueOf(1).equals(session.getStatus())
                && !Integer.valueOf(2).equals(session.getStatus())
                && !Integer.valueOf(3).equals(session.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getOnSaleTime() != null && session.getOnSaleTime().isAfter(now)) {
            return false;
        }
        return session.getSessionTime() == null || session.getSessionTime().isAfter(now);
    }

    private void safeUnlock(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放回流锁失败：{}", lock.getName(), e);
        }
    }

    private record RemovedMember(String member, double score) {
    }
}
