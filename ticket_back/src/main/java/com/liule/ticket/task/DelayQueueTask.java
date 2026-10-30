package com.liule.ticket.task;

import com.liule.ticket.service.IDelayQueueService;
import com.liule.ticket.service.IOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ZSet 延迟队列的到期扫描（主路径）。
 * <p>
 * 每 10 秒取一次 score <= now 的订单，逐个走 {@code invalidateOrder}，
 * 处理完从队列删除。多实例同时扫到同一单也没关系：
 * {@code invalidateOrder} 的 {@code WHERE status = 0} 只会成功一次。
 * <p>
 * 处理抛异常时故意不删队列记录，10 秒后重试；{@link OrderTimeoutTask}
 * 每 30 秒扫表还会再兜一层。
 */
@Slf4j
@Component
public class DelayQueueTask {

    private static final int BATCH_SIZE = 200;

    @Resource
    private IDelayQueueService delayQueueService;

    @Resource
    private IOrderService orderService;

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void processExpiredOrders() {
        List<Long> dueOrderIds = delayQueueService.findDue(System.currentTimeMillis(), BATCH_SIZE);
        if (dueOrderIds.isEmpty()) {
            return;
        }

        int released = 0;
        for (Long orderId : dueOrderIds) {
            try {
                if (orderService.invalidateOrder(orderId, 4)) {
                    released++;
                }
                // 订单不存在或已经被支付/取消处理过，也要从队列移除。
                delayQueueService.remove(orderId);
            } catch (Exception e) {
                // 不删，留给 10 秒后的下一轮；30 秒扫表也会兜底。
                log.error("订单 {} 延迟队列处理失败，下轮重试", orderId, e);
            }
        }
        log.info("延迟队列处理：到期 {} 条，成功 {} 条", dueOrderIds.size(), released);
    }
}
