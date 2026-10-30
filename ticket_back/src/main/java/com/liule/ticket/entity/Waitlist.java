package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 候补队列（Plan 3）。
 * <p>
 * 排队顺序存在 Redis ZSet {@code ticker:waitlist:queue:{tierId}}，
 * 这张表只保存业务状态和补位订单关联。
 * <p>
 * {@code (user_id, tier_id)} 唯一：同一用户同一票档只能排一次。
 * 过期/取消后重新加入时更新原行（status→0、join_time 重置），不要 insert。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_waitlist")
public class Waitlist implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 候补用户
     */
    private Long userId;

    /**
     * 候补的票档
     */
    private Long tierId;

    private Long sessionId;

    /**
     * 想候补几张
     */
    private Integer quantity;

    /**
     * 0排队中 1已通知待支付 2已购票 3已过期 4已取消
     */
    private Integer status;

    /**
     * 补位生成的订单号；0=尚未补位。
     * <p>
     * 64 位订单号超出 JS Number 安全范围，按字符串返回。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /**
     * 被通知补位的时间
     */
    private LocalDateTime notifyTime;

    /**
     * 补位支付截止时间
     */
    private LocalDateTime expireTime;

    /**
     * 入队时间
     */
    private LocalDateTime joinTime;

}
