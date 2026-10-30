package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.OrderVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.OrderAttendee;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.Ticket;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.entity.UserAttendee;
import com.liule.ticket.exception.BizException;
import com.liule.ticket.mapper.OrderAttendeeMapper;
import com.liule.ticket.mapper.TicketOrderMapper;
import com.liule.ticket.mapper.UserPurchaseMapper;
import com.liule.ticket.service.IDelayQueueService;
import com.liule.ticket.service.IOrderService;
import com.liule.ticket.service.IReflowService;
import com.liule.ticket.service.IRiskControlService;
import com.liule.ticket.service.IShowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.service.IUserAttendeeService;
import com.liule.ticket.service.IWaitlistService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.RedisIdWorker;
import com.liule.ticket.utils.StreamConsumer;
import com.liule.ticket.utils.SystemConstants;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder>
        implements IOrderService {
    @Resource
    private ITicketTierService ticketTierService;
    @Resource
    private IShowSessionService showSessionService;
    @Resource
    private IShowService showService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserPurchaseMapper userPurchaseMapper;

    @Resource
    private OrderAttendeeMapper orderAttendeeMapper;

    @Resource
    private IUserAttendeeService userAttendeeService;

    @Resource
    private ITicketService ticketService;

    @Resource
    private IReflowService reflowService;

    @Resource
    private IDelayQueueService delayQueueService;

    @Resource
    private IWaitlistService waitlistService;

    @Resource
    private IRiskControlService riskControlService;

    /**
     * 注入自己，唯一目的是拿到 Spring 代理。
     * <p>
     * 下面的消费线程是内部类，它直接写 {@code createOrder(...)} 属于 self-invocation，
     * <b>不走代理，@Transactional 会静默失效</b> —— 扣库存、写限购兜底表、插订单
     * 这三步就不会一起回滚，扣了库存没订单。必须通过代理调才生效。
     * <p>
     * {@code @Lazy} 是为了避开"自己注入自己"在启动期被判成循环依赖。
     */
    @Lazy
    @Resource
    private IOrderService self;

    @Override
    @Transactional
    public Result cancleOrder(String orderId) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != 0) {
            return Result.fail("订单状态不允许取消");
        }
        Long userId = UserHolder.getUser().getId();
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权限取消该订单");
        }

        // 真正的"作废 + 回补"都在 invalidateOrder 里，取消和超时失效共用一套。
        // 走 self 是为了经过代理（它标了 @Transactional），和现有事务合并。
        boolean ok = self.invalidateOrder(Long.valueOf(orderId), 2);
        if (!ok) {
            return Result.fail("订单状态不允许取消");
        }
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean invalidateOrder(Long orderId, int targetStatus) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            return false;
        }

        // 状态守卫：只有待支付能作废。条件更新 + 影响行数 = 并发/重复调用时只会成功一次。
        boolean updated = update()
                .set("status", targetStatus)
                .set("cancel_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", 0)
                .update();
        if (!updated) {
            return false;
        }

        // 候补补位订单作废时同步候补行：主动取消 → 4 已取消，超时 → 3 已过期。
        // 普通订单这里更新 0 行，不影响。
        if (Integer.valueOf(1).equals(order.getSource())) {
            int waitlistStatus = targetStatus == 2 ? 4 : 3;
            int rows = waitlistService.syncByOrderId(orderId, 1, waitlistStatus);
            if (rows == 0) {
                log.warn("补位订单 {} 作废，但候补行状态未同步", orderId);
            }
        }

        int quantity = order.getQuantity();

        // 1. 库存去向统一交给 ReflowService：候补优先，队列空才回公开池。
        //    这里不再自己决定 stock / lock_stock 怎么改。
        reflowService.release(order.getTierId(), order.getSessionId(), quantity);

        // 2. DB 限购兜底表：不减的话，用户取消后想重买会被自己的旧计数挡住。
        userPurchaseMapper.decrease(order.getUserId(), order.getSessionId(), quantity);

        // 3. Redis 限购计数 -n。秒杀侧按 Redis 判，不退用户会被自己的旧计数挡住。
        //    库存的 Redis 回补已经在 release 里做了，这里只处理限购。
        decreaseRedisOrderedCount(order.getSessionId(), order.getUserId(), quantity);

        // 4. 释放观演人：删掉本订单绑定的观演人行。
        //    不删的话，uk_session_idcard 会把这几个证件永久占住 ——
        //    用户取消/超时后再也买不了同一场次。
        orderAttendeeMapper.delete(new LambdaQueryWrapper<OrderAttendee>()
                .eq(OrderAttendee::getOrderId, orderId));

        // 5. 已作废的订单不该再被支付超时定时器扫到。
        delayQueueService.remove(orderId);

        return true;
    }

    @Override
    @Transactional
    public Result finishOrder(String orderId) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getExpireTime().isBefore(LocalDateTime.now())) {
            return Result.fail("订单已超时失效，请重新抢票");
        }

        if (order.getStatus() != 0) {
            return Result.fail("订单状态不允许完成");
        }

        Long userId = UserHolder.getUser().getId();
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权限完成该订单");
        }

        // 支付前必须把每张票的观演人都填满 —— 强实名是一票一人。
        // 没填满就付款，等于卖出一张不知道给谁的票，出票时也没办法写票面。
        Long filled = orderAttendeeMapper.selectCount(
                new LambdaQueryWrapper<OrderAttendee>()
                        .eq(OrderAttendee::getOrderId, orderId));
        if (filled == null || filled.longValue() != order.getQuantity()) {
            return Result.fail("请先为每张票选择观演人");
        }

        boolean updated = update().set("status", 1).set("pay_time", LocalDateTime.now()).eq("status", 0).eq("id", orderId).update();
        if (!updated) {
            return Result.fail("完成订单失败");
        }

        // 状态已置为已支付，立刻出票。在同一个事务里：
        // 出票失败会连状态一起回滚，不会出现"付了钱却没有票"。
        ticketService.issueTickets(order);

        // 候补补位订单支付成功 → 候补行 1→2 已购票；普通订单更新 0 行。
        if (Integer.valueOf(1).equals(order.getSource())) {
            int rows = waitlistService.syncByOrderId(Long.valueOf(orderId), 1, 2);
            if (rows == 0) {
                log.warn("补位订单 {} 支付成功，但候补行状态未同步", orderId);
            }
        }

        // 已支付订单不再需要支付超时定时器。
        delayQueueService.remove(Long.valueOf(orderId));

        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result refundOrder(String orderId) {
        Long id = Long.valueOf(orderId);
        TicketOrder order = getById(id);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null || !user.getId().equals(order.getUserId())) {
            return Result.fail("无权限退款该订单");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            return Result.fail("仅已支付订单可退款");
        }

        // 整单退：先确认每张票都还没核销。任意一张已核销/已退就拒绝，不做部分退款。
        Long unused = ticketService.count(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getOrderId, id)
                .eq(Ticket::getStatus, 0));
        if (unused == null || unused.longValue() != order.getQuantity().longValue()) {
            return Result.fail("订单中有票已核销或已退，整单不可退款");
        }

        // 条件更新：并发重复退款时只有一次能成功。
        boolean updated = update()
                .set("status", 3)
                .set("refund_time", LocalDateTime.now())
                .eq("id", id)
                .eq("status", 1)
                .update();
        if (!updated) {
            return Result.fail("订单状态不允许退款");
        }

        // 票 → 已退。仍然带 status=0 条件并校验行数：
        // 预检查之后、这一行之前如果正好发生了核销，这里会少更新一张，直接回滚整单。
        int refundedTickets = ticketService.getBaseMapper().update(
                new LambdaUpdateWrapper<Ticket>()
                        .set(Ticket::getStatus, 2)
                        .eq(Ticket::getOrderId, id)
                        .eq(Ticket::getStatus, 0));
        if (refundedTickets != order.getQuantity()) {
            throw new BizException("订单中有票已核销，整单不可退款");
        }

        // 候补补位订单退款 → 候补行 2→4 已取消（失去位置，可重新排队）。
        if (Integer.valueOf(1).equals(order.getSource())) {
            int rows = waitlistService.syncByOrderId(id, 2, 4);
            if (rows == 0) {
                log.warn("补位订单 {} 退款，但候补行状态未同步", id);
            }
        }

        // 库存去向统一交给 ReflowService：候补优先，队列空才回公开池。
        reflowService.release(order.getTierId(), order.getSessionId(), order.getQuantity());

        // 退款后归还限购额度，否则用户退完票也再买不了。
        userPurchaseMapper.decrease(order.getUserId(), order.getSessionId(), order.getQuantity());
        decreaseRedisOrderedCount(order.getSessionId(), order.getUserId(), order.getQuantity());

        // 释放观演人证件：不删的话 uk_session_idcard 会把证件永久占住。
        orderAttendeeMapper.delete(new LambdaQueryWrapper<OrderAttendee>()
                .eq(OrderAttendee::getOrderId, id));

        // 已支付订单支付时已出队；这里防御性再删一次，重复退款也不会留下定时器。
        delayQueueService.remove(id);

        log.info("订单 {} 退款成功，回补 {} 张票", id, order.getQuantity());
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result bindAttendees(Long orderId, List<Long> attendeeIds) {
        Long userId = UserHolder.getUser().getId();

        TicketOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (!Integer.valueOf(0).equals(order.getStatus())) {
            return Result.fail("订单当前状态不可选择观演人");
        }
        if (order.getExpireTime().isBefore(LocalDateTime.now())) {
            return Result.fail("订单已超时失效，请重新抢票");
        }

        if (attendeeIds == null || attendeeIds.isEmpty()) {
            return Result.fail("请选择观演人");
        }
        if (attendeeIds.size() != order.getQuantity()) {
            return Result.fail("需要选择 " + order.getQuantity() + " 位观演人");
        }
        if (new HashSet<>(attendeeIds).size() != attendeeIds.size()) {
            return Result.fail("观演人不能重复选择");
        }

        // 只能选自己名下、且已实名认证的观演人
        List<UserAttendee> attendees = userAttendeeService.listByIds(attendeeIds);
        if (attendees.size() != attendeeIds.size()) {
            return Result.fail("观演人不存在");
        }
        for (UserAttendee attendee : attendees) {
            if (!userId.equals(attendee.getUserId())) {
                return Result.fail("观演人不存在");
            }
            if (!Integer.valueOf(1).equals(attendee.getVerifyStatus())) {
                return Result.fail("观演人「" + attendee.getRealName() + "」未完成实名认证");
            }
        }

        // 覆盖式写入：先删本订单旧的行，再按请求顺序重建 seq 1..N。
        // 这样可以反复提交、替换观演人；删和插在同一个事务里，
        // 后面插入失败会连同删除一起回滚，不会把上一次的选择弄丢。
        orderAttendeeMapper.delete(new LambdaQueryWrapper<OrderAttendee>()
                .eq(OrderAttendee::getOrderId, orderId));

        Map<Long, UserAttendee> byId = attendees.stream()
                .collect(Collectors.toMap(UserAttendee::getId, a -> a));

        int seq = 1;
        for (Long attendeeId : attendeeIds) {
            UserAttendee attendee = byId.get(attendeeId);
            OrderAttendee row = new OrderAttendee()
                    .setOrderId(orderId)
                    .setSessionId(order.getSessionId())
                    .setAttendeeId(attendee.getId())
                    .setSeq(seq++)
                    .setRealName(attendee.getRealName())
                    .setIdCard(attendee.getIdCard());
            try {
                orderAttendeeMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // uk_session_idcard：同一场次同一个证件只能有一张票。
                // 抛 BizException 让事务整体回滚，并把原因带回给前端。
                throw new BizException("观演人「" + attendee.getRealName() + "」已购买本场次门票");
            }
        }

        log.info("订单 {} 绑定 {} 位观演人", orderId, attendeeIds.size());
        return Result.ok();
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckill(SeckillFormDTO form, String ip, String deviceId) {
        // 1. 获取用户信息
        UserDTO userDTO = UserHolder.getUser();
        Long userId = userDTO.getId();

        // 2. 风控：用户/IP 频率超限直接拒绝；设备多账号只记录不拦截。
        //    放在业务校验之前，失败的抢票请求也计数 —— 防脚本试探。
        if (!riskControlService.check(userId, ip, deviceId)) {
            return Result.fail("操作过于频繁，请稍后再试");
        }

        // 3. 校验抢票信息
        Result result = checkSeckillInformation(form);
        if (!result.getSuccess()) {
            return Result.fail(result.getErrorMsg());
        }
        TicketTier ticketTier = (TicketTier) result.getData();
        // 4. 查对应票档在 redis 里有库存
        Long stock = ticketTierService.queryStock(form.getTierId());
        if (stock <= 0) {
            return Result.fail("票档库存不足");
        }
        // 5. 将订单信息加入异步队列
        //   累计限购的上限取自【场次】，不是票档 —— 两个旋钮互相独立：
        //     场次的 purchase_limit  管"这个账号在本场次总共能买几张"，由 Lua 按账号累计判；
        //     票档的 max_per_order   管"一笔订单最多几张"，已在 checkSeckillInformation 里判过。
        //   这里再查一次 session 是为了拿它的限购值，主键查询，代价可忽略。
        ShowSession session = showSessionService.getById(ticketTier.getSessionId());

        Long orderId = redisIdWorker.create_ID("order");

        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + ticketTier.getId(),
                        RedisConstants.SECKILL_ORDERED_KEY + ticketTier.getSessionId(),
                        RedisConstants.ORDER_STREAM_KEY),
                userId.toString(),
                orderId.toString(),
                form.getQuantity().toString(),
                session.getPurchaseLimit().toString(),
                ticketTier.getId().toString(),
                ticketTier.getSessionId().toString());

        int code = r == null ? -1 : r.intValue();
        if (code == 1) {
            return Result.fail("已售罄");
        }
        if (code == 2) {
            return Result.fail("超出限购数量");
        }
        if (code != 0) {
            return Result.fail("系统繁忙，请重试");
        }
        // 此刻只是"抢到了名额"，订单还没落库。前端拿 orderId 去 /order/result/{orderId} 轮询。
        // 64 位订单号超出 JS Number 安全范围，按字符串返回，避免前端拿到被四舍五入的 ID。
        return Result.ok(orderId.toString());
    }

    // ==================================================================
    // 异步落库：消费 stream.orders，把抢到名额的订单写进 DB
    // ==================================================================

    /**
     * Stream 那套机制（建组、阻塞读、ACK、pending 重投、异常退避、启停）都在
     * {@link StreamConsumer} 里，这个类只留订单业务。
     */
    private StreamConsumer orderConsumer;

    @PostConstruct
    public void startConsumer() {
        orderConsumer = new StreamConsumer(
                stringRedisTemplate,
                RedisConstants.ORDER_STREAM_KEY,
                RedisConstants.ORDER_STREAM_GROUP,
                RedisConstants.ORDER_STREAM_CONSUMER,
                this::handleOrderMessage);
        orderConsumer.start();
    }

    @PreDestroy
    public void stopConsumer() {
        if (orderConsumer != null) {
            orderConsumer.stop();
        }
    }

    /**
     * 处理一条下单消息。
     * <p>
     * 和 {@link StreamConsumer} 的约定是：<b>正常返回 = ACK，抛异常 = 留 pending 重投</b>。
     * 所以这里要分清两类失败：
     * <ul>
     *   <li>{@link BizException} —— 业务上注定失败（库存不足、超出限购），重试多少次都一样。
     *       补偿掉 Redis 占用后<b>正常返回</b>，让这条彻底过去。</li>
     *   <li>其他异常 —— DB/Redis 抖了，是暂时的。<b>故意不 catch</b>，让它冒出去留 pending。
     *       这类消息绝不能丢，丢了就是用户抢到票却没有订单。</li>
     * </ul>
     */
    private void handleOrderMessage(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Long orderId = Long.valueOf(String.valueOf(value.get("orderId")));
        Long userId = Long.valueOf(String.valueOf(value.get("userId")));
        Long tierId = Long.valueOf(String.valueOf(value.get("tierId")));
        Long sessionId = Long.valueOf(String.valueOf(value.get("sessionId")));
        Integer quantity = Integer.valueOf(String.valueOf(value.get("quantity")));

        try {
            // ⚠ 必须走代理调（self），不能直接写 createOrder(...)：
            //   这个方法是 OrderServiceImpl 自己的，直接调属于 self-invocation，
            //   不走 Spring 代理，@Transactional 会静默失效 ——
            //   扣库存、写兜底表、插订单就不会一起回滚。
            self.createOrder(orderId, userId, tierId, sessionId, quantity);
        } catch (BizException e) {
            log.warn("订单 {} 落库失败：{}", orderId, e.getMessage());
            compensate(orderId, tierId, sessionId, userId, quantity, e.getMessage());
        }
    }

    /**
     * 落库失败后的收尾：把 Redis 上占用的还回去，并留下一个可查询的失败标记。
     */
    private void compensate(Long orderId, Long tierId, Long sessionId, Long userId,
                            Integer quantity, String reason) {
        try {
            self.releaseReservation(tierId, sessionId, userId, quantity);
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SECKILL_FAILED_KEY + orderId, reason,
                    RedisConstants.SECKILL_FAILED_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 补偿再失败就只能靠日志 + 对账了。绝不能抛出去 ——
            // 抛出去会被 StreamConsumer 当成"可以重试"，但 Redis 那边可能已经动过一半了。
            log.error("订单 {} 补偿失败，需人工介入", orderId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Long orderId, Long userId, Long tierId, Long sessionId, Integer quantity) {
        if (getById(orderId) != null) {
            log.info("订单 {} 已存在，跳过重复消费", orderId);
            return;
        }
        createOrderInternal(orderId, userId, tierId, sessionId, quantity, 0,
                LocalDateTime.now().plusMinutes(SystemConstants.ORDER_PAY_TIMEOUT_MINUTES));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createWaitlistOrder(Long orderId, Long userId, Long tierId, Long sessionId,
                                    Integer quantity, LocalDateTime expireTime) {
        if (getById(orderId) != null) {
            // 补位订单号是分配时现生成的，正常不可能撞号；走到这里说明数据异常
            log.warn("补位订单 {} 已存在，跳过重复建单", orderId);
            return;
        }

        createOrderInternal(orderId, userId, tierId, sessionId, quantity, 1, expireTime);

        // 秒杀路径的 Redis 限购计数由 Lua 原子加；候补路径没有 Lua，必须自己补。
        // 放到事务提交后执行：事务回滚时不能留下一条不存在的购买记录。
        incrementOrderedAfterCommit(sessionId, userId, quantity);
        log.info("候补补位订单 {} 落库成功", orderId);
    }

    /**
     * 秒杀与候补共用的建单逻辑。调用方保证 orderId 不存在。
     *
     * @param source 0 正常抢票 / 1 候补补位
     */
    private void createOrderInternal(Long orderId, Long userId, Long tierId, Long sessionId,
                                     Integer quantity, int source, LocalDateTime expireTime) {
        TicketTier tier = ticketTierService.getById(tierId);
        if (tier == null) {
            throw new BizException("票档不存在");
        }
        ShowSession session = showSessionService.getById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }

        // 1. 扣 DB 库存，同时记进 lock_stock（被已下单未取消的订单占用）。
        //    ge("stock", quantity) 让"库存不够"表现为影响 0 行，而不是扣成负数 ——
        //    这是 Redis 那层闸门漏过来时的最后一道防线。
        //    两个列必须在同一条 UPDATE 里改，否则中间那一刻 total_stock ≠ stock + lock_stock。
        boolean reserved = ticketTierService.update()
                .setSql("stock = stock - " + quantity
                        + ", lock_stock = lock_stock + " + quantity)
                .eq("id", tierId)
                .ge("stock", quantity)
                .update();
        if (!reserved) {
            throw new BizException("库存不足，订单创建失败");
        }

        // 2. 限购兜底。Redis 是限购的主判据（快、原子），但它可能被清空或重启丢数据，
        //    所以这里在同一事务里再累加一次并校验 —— 唯一索引 + 原子累加，
        //    即使 Redis 全丢了也不会超卖。
        //    上限取【场次】的 purchase_limit（累计），不是票档的 max_per_order（单笔）。
        userPurchaseMapper.increase(userId, sessionId, quantity);
        Integer total = userPurchaseMapper.getQuantity(userId, sessionId);
        if (total != null && total > session.getPurchaseLimit()) {
            throw new BizException("超出限购，事务回滚");
        }

        // 3. 落订单
        TicketOrder order = new TicketOrder()
                .setId(orderId)
                .setUserId(userId)
                .setShowId(tier.getShowId())
                .setSessionId(sessionId)
                .setTierId(tierId)
                .setQuantity(quantity)
                .setAmount(tier.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .setStatus(0)   // 0待支付
                .setSource(source)
                .setExpireTime(expireTime);
        save(order);

        // 落库成功后加入支付超时延迟队列。这里在事务提交前写 Redis：
        // 万一事务回滚，队列里会残留一条记录，到期时 invalidateOrder
        // 找不到订单会直接返回 false，任务随后把它删掉，不会重复回补。
        delayQueueService.add(order.getId(), order.getExpireTime());

        log.info("订单 {} 落库成功（source={}）", orderId, source);
    }

    /**
     * 候补建单的 Redis 限购计数补偿。
     * <p>
     * 必须在事务提交后执行：DB 回滚了，Redis 不能留下一个凭空的已购数量。
     */
    private void incrementOrderedAfterCommit(Long sessionId, Long userId, Integer quantity) {
        Runnable increment = () -> {
            try {
                stringRedisTemplate.opsForHash().increment(
                        RedisConstants.SECKILL_ORDERED_KEY + sessionId,
                        userId.toString(), quantity);
            } catch (Exception e) {
                // Redis 只是限购的快判据；漏加最多让用户多抢一次，DB 兜底表仍会拦住
                log.error("候补订单 Redis 限购计数 +{} 失败：sessionId={}, userId={}",
                        quantity, sessionId, userId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    increment.run();
                }
            });
        } else {
            increment.run();
        }
    }

    @Override
    public void releaseReservation(Long tierId, Long sessionId, Long userId, Integer quantity) {
        // 只服务落库失败补偿：此时 DB 事务已回滚，库存只存在于 Redis，不能走 ReflowService
        // （那会把 DB 库存也加回去）。先判 key 存在 —— INCRBY 会把不存在的 key 当 0 再加，
        // 凭空造出 quantity 张票。
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + tierId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().increment(stockKey, quantity);
        }

        // 限购计数同理：不退的话，用户下次抢会被自己这条"不存在的订单"挡住
        decreaseRedisOrderedCount(sessionId, userId, quantity);
    }

    /**
     * 减少用户在场次的 Redis 累计限购计数。退到 0 时删掉 field，不留下 0。
     */
    private void decreaseRedisOrderedCount(Long sessionId, Long userId, Integer quantity) {
        String orderedKey = RedisConstants.SECKILL_ORDERED_KEY + sessionId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(orderedKey))) {
            Long left = stringRedisTemplate.opsForHash()
                    .increment(orderedKey, userId.toString(), -quantity);
            if (left != null && left <= 0) {
                stringRedisTemplate.opsForHash().delete(orderedKey, userId.toString());
            }
        }
    }

    @Override
    // 根据订单id获取秒杀结果
    public Result getSeckillResult(Long orderId) {
        TicketOrder order = getById(orderId);

        Map<String, Object> data = new HashMap<>();
        if (order != null) {
            // 只允许查自己的订单
            UserDTO user = UserHolder.getUser();
            if (user == null || !user.getId().equals(order.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            data.put("created", true);
            data.put("status", order.getStatus());
            data.put("amount", order.getAmount());
            data.put("quantity", order.getQuantity());
            data.put("expireTime", order.getExpireTime());
            return Result.ok(data);
        }

        // 订单还不存在：可能还在队列里等着落库，也可能已经失败了。
        // 光看订单表区分不出来，失败标记就是为这个存在的。
        String reason = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_FAILED_KEY + orderId);
        data.put("created", false);
        if (reason != null) {
            data.put("failed", true);
            data.put("reason", reason);
        } else {
            // 没有失败标记 = 消息还在路上，前端继续轮询
            data.put("failed", false);
        }
        return Result.ok(data);
    }

    public Result checkSeckillInformation(SeckillFormDTO form) {
        if (form == null) {
            return Result.fail("订单信息为空");
        }

        TicketTier ticketTier = ticketTierService.getById(form.getTierId());
        Integer quantity = form.getQuantity();
        if (ticketTier == null || quantity == null || quantity <= 0) {
            return Result.fail("票档不存在或信息不正确");
        }
        if (!Integer.valueOf(1).equals(ticketTier.getStatus())) {
            return Result.fail("票档已下架");
        }
        if (quantity > ticketTier.getMaxPerOrder()) {

            return Result.fail("每单限购 " + ticketTier.getMaxPerOrder() + " 张");
        }
        ShowSession session = showSessionService.getById(ticketTier.getSessionId());
        if (session == null) return Result.fail("场次不存在");
        if (session.getOnSaleTime().isAfter(LocalDateTime.now())) {
            return Result.fail("还未到开票时间");
        }
        if (!Integer.valueOf(1).equals(session.getStatus())
                && !Integer.valueOf(2).equals(session.getStatus())) {
            return Result.fail("本场次当前不可抢票");
        }


        return Result.ok(ticketTier);
    }

    @Override
    public Result queryOrderById(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        TicketOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!user.getId().equals(order.getUserId())) {
            return Result.fail("无权查看该订单");
        }
        List<OrderVO> vos = toOrderVOList(List.of(order));
        return Result.ok(vos.get(0));
    }

    @Override
    public Result listMyOrders(Integer status) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        LambdaQueryWrapper<TicketOrder> wrapper = new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, user.getId())
                .eq(status != null, TicketOrder::getStatus, status)
                .orderByDesc(TicketOrder::getCreateTime);
        List<TicketOrder> orders = list(wrapper);
        return Result.ok(toOrderVOList(orders));
    }

    /**
     * 批量装配订单展示字段：一次查齐演出、场次、票档，避免逐单回源。
     */
    private List<OrderVO> toOrderVOList(List<TicketOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> showIds = orders.stream()
                .map(TicketOrder::getShowId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> sessionIds = orders.stream()
                .map(TicketOrder::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> tierIds = orders.stream()
                .map(TicketOrder::getTierId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Show> showMap = showIds.isEmpty() ? new HashMap<>()
                : showService.listByIds(showIds).stream()
                        .collect(Collectors.toMap(Show::getId, Function.identity()));
        Map<Long, ShowSession> sessionMap = sessionIds.isEmpty() ? new HashMap<>()
                : showSessionService.listByIds(sessionIds).stream()
                        .collect(Collectors.toMap(ShowSession::getId, Function.identity()));
        Map<Long, TicketTier> tierMap = tierIds.isEmpty() ? new HashMap<>()
                : ticketTierService.listByIds(tierIds).stream()
                        .collect(Collectors.toMap(TicketTier::getId, Function.identity()));

        List<OrderVO> result = new ArrayList<>(orders.size());
        for (TicketOrder order : orders) {
            OrderVO vo = new OrderVO();
            vo.setOrder(order);

            Show show = showMap.get(order.getShowId());
            if (show != null) {
                vo.setShowName(show.getName());
                vo.setPoster(show.getPoster());
            }

            ShowSession session = sessionMap.get(order.getSessionId());
            if (session != null) {
                vo.setSessionName(session.getSessionName());
                vo.setSessionTime(session.getSessionTime());
            }

            TicketTier tier = tierMap.get(order.getTierId());
            if (tier != null) {
                vo.setTierName(tier.getName());
                vo.setSeatArea(tier.getSeatArea());
            }

            result.add(vo);
        }
        return result;
    }

}
