package com.liule.ticket.service;

/**
 * 库存释放与回流分配。
 * <p>
 * 取消、超时、退款都要把订单占用的库存还回去。以前这段逻辑散在
 * {@code OrderServiceImpl.invalidateOrder} 里直接改库存，E11-6 接入候补后，
 * 「这张票给候补队首还是回公开池」必须和释放动作在同一个临界区完成 ——
 * 否则先回公开池再拉回候补，中间窗口会被普通用户抢走。
 * <p>
 * 因此把「库存去哪儿」收敛到这一个入口，调用方只负责作废订单本身。
 */
public interface IReflowService {

    /**
     * 归还一笔订单占用的库存，并决定票的去向。
     * <p>
     * E11-6 起不再直接回公开池：
     * <ol>
     *   <li>候补队列非空 → 按 ZSet 顺序取队首，生成 {@code source=1} 订单，
     *       给 10 分钟专属支付窗口；</li>
     *   <li>队首数量大于本次可分配量 → 不跳过队首，剩余回公开池（v1 拍板）；</li>
     *   <li>队列空 / 票档或场次不可售 → 全部回公开池。</li>
     * </ol>
     * 整个分配过程由票档级 Redisson 锁串行化，锁持有到调用方事务提交之后。
     *
     * @param tierId    票档 id，库存单元，也是回流锁的粒度
     * @param sessionId 场次 id，候补行校验和限购校验用
     * @param quantity  归还数量
     */
    void release(Long tierId, Long sessionId, Integer quantity);
}
