package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.OrderVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.OrderAttendee;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.entity.UserAttendee;
import com.liule.ticket.exception.BizException;
import com.liule.ticket.mapper.OrderAttendeeMapper;
import com.liule.ticket.mapper.TicketOrderMapper;
import com.liule.ticket.mapper.UserPurchaseMapper;
import com.liule.ticket.service.IOrderService;
import com.liule.ticket.service.IShowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.service.IUserAttendeeService;
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

        int quantity = order.getQuantity();

        // 1. DB 库存：可售 +n、占用 -n。两个列一条 UPDATE，保持 total = stock + lock 不变。
        boolean returned = ticketTierService.update()
                .setSql("stock = stock + " + quantity
                        + ", lock_stock = lock_stock - " + quantity)
                .eq("id", order.getTierId())
                .ge("lock_stock", quantity)
                .update();
        if (!returned) {
            // 正常情况锁定量一定够（下单时刚加过）。走到这说明数据被动过，
            // 抛出去让整个事务回滚，避免"订单作废了、库存却没还回去"。
            throw new IllegalStateException(
                    "订单 " + orderId + " 回补库存失败：lock_stock 不足");
        }

        // 2. DB 限购兜底表：不减的话，用户取消后想重买会被自己的旧计数挡住。
        userPurchaseMapper.decrease(order.getUserId(), order.getSessionId(), quantity);

        // 3. Redis：库存 +n、限购计数 -n。
        //    秒杀侧是按 Redis 判的，不还就会出现假售罄 / 用户被自己挡住。
        releaseReservation(order.getTierId(), order.getSessionId(), order.getUserId(), quantity);

        // 4. 释放观演人：删掉本订单绑定的观演人行。
        //    不删的话，uk_session_idcard 会把这几个证件永久占住 ——
        //    用户取消/超时后再也买不了同一场次。
        orderAttendeeMapper.delete(new LambdaQueryWrapper<OrderAttendee>()
                .eq(OrderAttendee::getOrderId, orderId));

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
    public Result seckill(SeckillFormDTO form) {
        // 1. 获取用户信息
        UserDTO userDTO = UserHolder.getUser();
        Long userId = userDTO.getId();
        //2.校验抢票信息
        Result result = checkSeckillInformation(form);
        if (!result.getSuccess()) {
            return Result.fail(result.getErrorMsg());
        }
        TicketTier ticketTier = (TicketTier) result.getData();
        //3.查对应票档在redis里有库存
        Long stock = ticketTierService.queryStock(form.getTierId());
        if (stock <= 0) {
            return Result.fail("票档库存不足");
        }
        //4.将订单信息加入异步队列
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
                .setSource(0)   // 0正常抢票
                .setExpireTime(LocalDateTime.now()
                        .plusMinutes(SystemConstants.ORDER_PAY_TIMEOUT_MINUTES));
        save(order);

        log.info("订单 {} 落库成功", orderId);
    }

    @Override
    public void releaseReservation(Long tierId, Long sessionId, Long userId, Integer quantity) {
        // 先判 key 存在 —— INCRBY 会把不存在的 key 当 0 再加，凭空造出 quantity 张票。
        // DB 才是库存的权威值，Redis 这边少一个 key 就说明它本来就没扣过。
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + tierId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().increment(stockKey, quantity);
        }

        // 限购计数同理：不退的话，用户下次抢会被自己这条"不存在的订单"挡住
        String orderedKey = RedisConstants.SECKILL_ORDERED_KEY + sessionId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(orderedKey))) {
            Long left = stringRedisTemplate.opsForHash()
                    .increment(orderedKey, userId.toString(), -quantity);
            if (left != null && left <= 0) {
                // 退到 0 就把 field 删掉，别在 Hash 里留一堆 0
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
