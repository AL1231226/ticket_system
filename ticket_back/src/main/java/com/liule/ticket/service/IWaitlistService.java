package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.WaitlistJoinDTO;
import com.liule.ticket.entity.Waitlist;

import java.time.LocalDateTime;

/**
 * 候补排队。E11-5 只负责「排队」，不负责补位分配（E11-6）。
 * <p>
 * 排队顺序在 Redis ZSet {@code ticker:waitlist:queue:{tierId}}：
 * member=userId，score=入队时间戳（毫秒），先到先得。
 * {@code tb_waitlist} 只保存业务状态和补位订单关联。
 */
public interface IWaitlistService extends IService<Waitlist> {

    /**
     * 加入候补，排到队尾。
     * <p>
     * 同一用户同一票档只有一行（{@code uk_user_tier}）：
     * 正在排队或已获得补位时拒绝；过期（3）/取消（4）后重新加入时
     * <b>复用原行</b>并重置入队时间，排到队尾，不保留旧位置。
     *
     * @return 新位置（从 1 开始）和队列总人数
     */
    Result join(WaitlistJoinDTO form);

    /**
     * 我的候补列表，按入队时间倒序；排队中的行带实时位置和队列总人数。
     */
    Result listMine();

    /**
     * 我在指定票档的排队位置。只有 {@code status=0 排队中} 才可查询。
     *
     * @return position（从 1 开始）、total、quantity、joinTime
     */
    Result position(Long tierId);

    /**
     * 退出候补：状态置 4 已取消，并从 ZSet 移除。
     * <p>
     * 只有排队中（0）能退出；已获得补位（1）要先取消补位订单。
     */
    Result quit(Long tierId);

    // ==================== E11-6：补位分配用 ====================

    /**
     * 补位分配时把候补行从「排队中」置为「已通知待支付」，并绑定补位订单。
     * <p>
     * 条件更新 {@code status=0}：用户刚好退出、或已被别的流程处理时返回 false，
     * 调用方应跳过这个人。
     */
    boolean markNotified(Long waitlistId, Long orderId,
                         LocalDateTime notifyTime, LocalDateTime expireTime);

    /**
     * 按行 id 做条件状态迁移。分配时发现队首已不具备资格（超过限购、
     * 数量不再合法），用它把行置为 3 已过期，避免永久堵住队首。
     */
    boolean markStatusById(Long waitlistId, int expectStatus, int targetStatus);

    /**
     * 按补位订单号同步候补行状态：
     * 支付 1→2、超时 1→3、取消 1→4、退款 2→4。
     *
     * @return 实际更新行数；普通订单返回 0
     */
    int syncByOrderId(Long orderId, int expectStatus, int targetStatus);
}
