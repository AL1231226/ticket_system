package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.entity.TicketOrder;

import java.util.List;

public interface IOrderService extends IService<TicketOrder> {

    Result cancleOrder(String orderId);

    Result finishOrder(String orderId);

    /**
     * 订单确认：给订单绑定观演人（一票一人）。
     * <p>
     * 数量必须等于订单的 {@code quantity}；列表顺序就是票的顺序（seq 从 1 开始）。
     * 覆盖式写入 —— 可以反复提交替换，删和插在同一个事务里。
     * <p>
     * 一证一票由 {@code tb_order_attendee.uk_session_idcard} 兜底，
     * 冲突时抛 {@code BizException}，整个事务回滚。
     */
    Result bindAttendees(Long orderId, List<Long> attendeeIds);

    /**
     * 把一张【待支付】订单作废，并把它占用的资源全部还回去。
     * <p>
     * 取消（2）和超时失效（4）本质是同一件事，所以共用这一个方法 ——
     * 用户主动取消和定时任务扫出来的超时单走完全相同的回补逻辑。
     * <p>
     * 释放四处：DB 的 stock/lock_stock、Redis 库存、Redis 限购计数、DB 限购兜底表。
     *
     * @param targetStatus 2=已取消，4=已失效
     * @return true=本次确实作废了；false=订单不存在或已不是待支付（别人处理过）
     */
    boolean invalidateOrder(Long orderId, int targetStatus);

    Result seckill(SeckillFormDTO form);

    /**
     * 把秒杀消息落库。由消费线程调用，不在请求线程里跑。
     * <p>
     * 消息里的 orderId 已经由 seckill 生成好了，这里只负责落成订单。
     * <p>
     * 幂等：同一条消息重复消费时靠 orderId 已存在直接跳过。
     *
     * @throws com.liule.ticket.exception.BizException 库存不足 / 超出限购 / 票档或场次不存在
     */
    void createOrder(Long orderId, Long userId, Long tierId, Long sessionId, Integer quantity);

    /**
     * 落库失败时，把秒杀阶段占用的 Redis 资源还回去。
     * <p>
     * 不还就是漏票：Lua 已经扣了库存、加了限购计数，消息一丢，这张票就从
     * 可售池里消失 —— 用户没订单、DB 里库存还在，两边永久分叉。
     */
    void releaseReservation(Long tierId, Long sessionId, Long userId, Integer quantity);

    /**
     * 抢票结果轮询。前端拿 seckill 返回的 orderId 反复调它。
     * <p>
     * 三种结果都要能区分：订单已生成 / 还在生成中 / 已经失败。
     */
    Result getSeckillResult(Long orderId);

    /**
     * 查当前登录用户的一笔订单详情，附带演出/场次/票档名称。
     * 查别人的订单一律拒绝。
     */
    Result queryOrderById(Long orderId);

    /**
     * 我的订单列表，按创建时间倒序。
     *
     * @param status 0待支付 1已支付 2已取消 3已退款 4已失效；null=全部
     */
    Result listMyOrders(Integer status);
}
