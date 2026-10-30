package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.entity.TicketOrder;

import java.time.LocalDateTime;
import java.util.List;

public interface IOrderService extends IService<TicketOrder> {

    Result cancleOrder(String orderId);

    Result finishOrder(String orderId);

    /**
     * 整单退款：仅限本人的【已支付】订单，且订单下所有票都未核销。
     * <p>
     * 退款成功：订单 → 3 已退款并写 {@code refund_time}，票 → 2 已退；
     * 库存走 {@link IReflowService#release} 统一回补（E11-6 起候补优先），
     * 同时归还限购额度、删除订单观演人行释放证件。
     * <p>
     * 任意一张票已核销就整单拒绝 —— 本期不做部分退款（Plan 3 拍板 #9）。
     */
    Result refundOrder(String orderId);

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
     * 其中库存（DB + Redis）统一走 {@link IReflowService#release}，为 Plan 3 候补回流留出入口。
     *
     * @param targetStatus 2=已取消，4=已失效
     * @return true=本次确实作废了；false=订单不存在或已不是待支付（别人处理过）
     */
    boolean invalidateOrder(Long orderId, int targetStatus);

    /**
     * 抢票。入口先做风控（用户/IP 频率、设备多账号），再走业务校验和 Lua。
     *
     * @param form     票档与数量
     * @param ip       客户端 IP，为空时跳过 IP 限流
     * @param deviceId 设备指纹（X-Device-Id），为空时跳过设备记录
     */
    Result seckill(SeckillFormDTO form, String ip, String deviceId);

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
     * 候补补位建单：由 {@link IReflowService#release} 在回流分配锁内调用，
     * 和 release 共用同一个事务。
     * <p>
     * 与秒杀建单共用内部实现（扣 DB 库存、累计限购、插订单、进延迟队列）。
     * 区别：
     * <ul>
     *   <li>{@code source=1 候补补位}，支付窗口 10 分钟；</li>
     *   <li>秒杀的 Redis 限购计数由 Lua 原子加，候补没有 Lua，
     *       由本方法在事务提交后补加。</li>
     * </ul>
     *
     * @param expireTime 补位支付截止时间（now + 10min）
     */
    void createWaitlistOrder(Long orderId, Long userId, Long tierId, Long sessionId,
                             Integer quantity, LocalDateTime expireTime);

    /**
     * 落库失败时，把秒杀阶段占用的 Redis 资源还回去。
     * <p>
     * 只用于补偿：此时 DB 事务已回滚，DB 库存没有扣成功，只能还 Redis，
     * 不能走 {@link IReflowService#release}（那会把 DB 库存也加回去）。
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
