package com.liule.ticket.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付超时延迟队列（ZSet）。
 * <p>
 * member = orderId，score = 到期时间戳（毫秒）。只保存待支付订单：
 * 支付/取消后从队列移除，超时处理后也移除。队列只是「定时器」，
 * 不区分普通订单还是候补补位订单 —— 两者只是 score 不同。
 * <p>
 * 定位是加速器：DB 里还有 30 秒扫表对账，Redis 抖动不能让下单/支付失败。
 */
public interface IDelayQueueService {

    /**
     * 订单落库后加入延迟队列。失败只记日志，由扫表兜底。
     */
    void add(Long orderId, LocalDateTime expireTime);

    /**
     * 支付或取消后移除。member 不存在时是空操作。
     */
    void remove(Long orderId);

    /**
     * 取出 score 已到期的订单号，按 score 升序，最多 {@code limit} 条。
     * 只读不删；调用方处理完再 {@link #remove(Long)}。
     */
    List<Long> findDue(long nowMillis, int limit);
}
