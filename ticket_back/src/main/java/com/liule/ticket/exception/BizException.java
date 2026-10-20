package com.liule.ticket.exception;

/**
 * 业务异常：表达"这件事注定失败"的原因，区别于系统故障。
 * <p>
 * 秒杀落库时用它区分两类失败，这个区分决定了消息怎么处理：
 * <ul>
 *   <li>{@code BizException}（库存不足、超出限购）—— 重试多少次结果都一样，
 *       补偿掉 Redis 占用后就可以把消息 ACK 掉</li>
 *   <li>其他异常（DB 连接抖了、Redis 超时）—— 暂时性，不能 ACK，
 *       要留在 pending 里等重投</li>
 * </ul>
 * 全局异常处理器也会单独接住它并原样返回 message —— 不接的话会被
 * {@code handleRuntimeException} 统一吞成"服务器异常"。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
