package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.OrderDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.mapper.TicketOrderMapper;
import com.liule.ticket.service.IOrderService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.RedisIdWorker;
import com.liule.ticket.utils.SystemConstants;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class OrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder>
        implements IOrderService {
    @Resource
    private ITicketTierService ticketTierService;
    @Resource
    private IShowSessionService showSessionService;


    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result createOrder(OrderDTO orderDTO) {
        UserDTO userDTO = UserHolder.getUser();

        Result result = checkInformation(orderDTO);
        Boolean checkResult = result.getSuccess();

        if (!checkResult) {
            return Result.fail(result.getErrorMsg());
        }



        // 扣减库存
        Long tierId = orderDTO.getTicketId();
        int quantity = orderDTO.getQuantity();
        boolean reserved = ticketTierService.update()
                .setSql("stock = stock - " + quantity
                        + ", lock_stock = lock_stock + " + quantity)
                .eq("id", tierId)
                .ge("stock", quantity)
                .update();
        if (!reserved) {
            return Result.fail("扣减库存失败");
        }

        //生成订单
        // 1. 生成订单号
        Long orderId = redisIdWorker.create_ID("order");
        TicketTier tier = (TicketTier) result.getData();

        TicketOrder order = new TicketOrder()
                .setId(orderId)
                .setUserId(userDTO.getId())
                .setShowId(tier.getShowId())
                .setSessionId(tier.getSessionId())
                .setTierId(tierId)
                .setQuantity(quantity)
                .setAmount(tier.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .setStatus(0)   // 0待支付
                .setSource(0)   // 0正常抢票
                // expire_time 是 NOT NULL 且没有默认值，必须显式给
                .setExpireTime(LocalDateTime.now()
                        .plusMinutes(SystemConstants.ORDER_PAY_TIMEOUT_MINUTES));
        save(order);

        return Result.ok(order);
    }

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
        //取消订单(状态和是时间）
        boolean updated = update().set("status", 2).set("cancel_time", LocalDateTime.now()).eq("status", 0).eq("id", orderId).update();
        if (!updated) {
            return Result.fail("取消订单失败");
        }
        Long quantity = order.getQuantity().longValue();

        boolean updated1 = ticketTierService.update()
                .setSql("stock = stock + " + quantity
                        + ", lock_stock = lock_stock - " + quantity)
                .eq("id", order.getTierId())
                .ge("lock_stock", quantity)     // ← 不够减就 0 行，防止重复归还
                .update();
        if (!updated1) {
            return Result.fail("归还库存失败");
        }

        return Result.ok();
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

        boolean updated = update().set("status", 1).set("pay_time", LocalDateTime.now()).eq("status", 0).eq("id", orderId).update();
        if (!updated) {
            return Result.fail("完成订单失败");
        }
        return Result.ok();
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
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
        Long orderId = redisIdWorker.create_ID("order");

        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + ticketTier.getId(),
                        RedisConstants.SECKILL_ORDERED_KEY + ticketTier.getSessionId(),
                        RedisConstants.ORDER_STREAM_KEY),
                userId.toString(),
                orderId.toString(),
                form.getQuantity().toString(),
                ticketTier.getPurchaseLimit().toString(),
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
        return Result.ok(orderId);
    }



    public Result checkInformation(OrderDTO orderDTO) {
        if (orderDTO == null ) {
            return Result.fail("订单信息为空");
        }

        TicketTier ticketTier = ticketTierService.getById(orderDTO.getTicketId());
        Integer quantity = orderDTO.getQuantity();
        if (ticketTier == null || quantity == null || quantity <= 0) {
            return Result.fail("票档不存在或信息不正确");
        }
        if ( !Integer.valueOf(1).equals(ticketTier.getStatus())) {
            return Result.fail("票档已下架");
        }
        if (quantity > ticketTier.getPurchaseLimit()) {

            return Result.fail("每单限购 " + ticketTier.getPurchaseLimit() + " 张");
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
    public Result checkSeckillInformation(SeckillFormDTO form) {
        if (form == null ) {
            return Result.fail("订单信息为空");
        }

        TicketTier ticketTier = ticketTierService.getById(form.getTierId());
        Integer quantity = form.getQuantity();
        if (ticketTier == null || quantity == null || quantity <= 0) {
            return Result.fail("票档不存在或信息不正确");
        }
        if ( !Integer.valueOf(1).equals(ticketTier.getStatus())) {
            return Result.fail("票档已下架");
        }
        if (quantity > ticketTier.getPurchaseLimit()) {

            return Result.fail("每单限购 " + ticketTier.getPurchaseLimit() + " 张");
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


}
