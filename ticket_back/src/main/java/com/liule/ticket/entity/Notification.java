package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * 站内信（Plan 3）。
 * <p>
 * 候补补位成功时写一条；前端轮询未读数拿红点，不做 WebSocket。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_notification")
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收者
     */
    private Long userId;

    /**
     * 0系统 1候补补位成功 2开票提醒 3订单状态变更
     */
    private Integer type;

    private String title;

    private String content;

    /**
     * 关联业务ID（如候补ID/订单ID）；64 位，按字符串返回。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bizId;

    /**
     * 0未读 1已读
     */
    @TableField("is_read")
    private Integer isRead;

    private LocalDateTime createTime;

}
