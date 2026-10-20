package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单观演人（一票一人）。
 * <p>
 * 一个订单买 N 张票就有 N 行，{@code seq} 是第几张（从 1 开始）。
 * 出票时按 {@code seq} 逐张展开到 {@code tb_ticket}。
 * <p>
 * <b>两条唯一索引</b>：
 * <ul>
 *   <li>{@code (order_id, seq)} —— 一张订单里位置不能重复</li>
 *   <li>{@code (session_id, id_card)} —— <b>一证一票</b>：同一场次、同一个证件
 *       只能出现一次。并发插入会直接撞唯一索引失败，不需要分布式锁</li>
 * </ul>
 * <p>
 * {@code session_id} / {@code real_name} / {@code id_card} 都是冗余列，有意为之：
 * 唯一索引只能引用本表列；出票时也直接取，少一次 join。
 * <p>
 * <b>取消/超时释放时必须删除对应的行</b>，否则这个证件会被永久占住，
 * 用户再也买不了同一场次。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_order_attendee")
public class OrderAttendee implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 订单号（tb_ticket_order.id）
     */
    private Long orderId;

    /**
     * 冗余场次ID。"一证一票"的唯一约束靠它划作用域。
     */
    private Long sessionId;

    /**
     * 关联的观演人（tb_user_attendee.id）。
     * 0 表示没有使用观演人库（直接填的姓名/证件）。
     */
    private Long attendeeId;

    /**
     * 第几张票，从 1 开始。
     */
    private Integer seq;

    /**
     * 观演人姓名（冗余，出票时直接取）。
     */
    private String realName;

    /**
     * 证件号（冗余，唯一索引要用它）。
     */
    private String idCard;

    private LocalDateTime createTime;

}
