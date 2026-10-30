package com.liule.ticket.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.service.IOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待支付订单的超时释放。
 * <p>
 * 下单时写了 {@code expire_time}（默认 15 分钟），但在此之前没有任何人在盯着它：
 * 用户抢到票既不付也不取消，这张单就会永久占着 stock 和 lock_stock ——
 * 库存被一张永远不会成交的订单吃死。
 * <p>
 * 这个任务定期把过期单捞出来作废，回补四处资源（见
 * {@link IOrderService#invalidateOrder}）。
 * <p>
 * <b>主路径已经是 {@link DelayQueueTask}</b>（ZSet，10 秒扫描）；这里保留为
 * 30 秒对账兜底，原因有两个：
 * <ul>
 *   <li>{@code plan2-e2e.sh} 直接 {@code UPDATE expire_time}，ZSet 里的 score
 *       不会跟着变，只靠延迟队列会让现有超时用例失败；</li>
 *   <li>Redis 抖动或延迟队列写失败时，过期的待支付单仍然要最终被释放。</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    /** 每轮最多处理多少单，避免一次捞出几万条把内存和事务拖垮 */
    private static final int BATCH_SIZE = 200;

    @Resource
    private IOrderService orderService;

    /**
     * 每 30 秒扫一次。
     * <p>
     * 多实例部署时每个实例都会扫，但这不会造成重复释放 ——
     * {@code invalidateOrder} 里的条件更新（{@code WHERE status = 0}）保证同一单只会成功一次。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void releaseExpiredOrders() {
        List<TicketOrder> expired = orderService.list(new LambdaQueryWrapper<TicketOrder>()
                .select(TicketOrder::getId)
                .eq(TicketOrder::getStatus, 0)
                .lt(TicketOrder::getExpireTime, LocalDateTime.now())
                .last("LIMIT " + BATCH_SIZE));

        if (expired.isEmpty()) {
            return;
        }

        int released = 0;
        for (TicketOrder order : expired) {
            try {
                if (orderService.invalidateOrder(order.getId(), 4)) {
                    released++;
                }
            } catch (Exception e) {
                // 单条失败不影响后面的，下一轮还会扫到它
                log.error("订单 {} 超时释放失败，下轮重试", order.getId(), e);
            }
        }
        log.info("超时订单释放：扫描 {} 条，成功 {} 条", expired.size(), released);
    }
}
