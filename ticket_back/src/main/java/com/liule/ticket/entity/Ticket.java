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

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket")
public class Ticket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 票号。和订单号一样由 RedisIdWorker 生成，不是自增
     */
    @TableId(value = "id", type = IdType.INPUT)
    // 票号和订单号都是 RedisIdWorker 生成的 64 位 ID，按字符串返回避免 JS 精度丢失
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long tierId;

    /**
     * 座位号。站票/通票（票档 seat_type=1）留空，对号入座存占位号
     */
    private String seatNo;

    /**
     * 实际观演人，不一定是下单人
     */
    private String realName;

    private String idCard;

    /**
     * 核验二维码。每次刷新都换一个新值，让旧截图作废
     */
    private String qrCode;

    /**
     * 0未使用 1已核销 2已退
     */
    private Integer status;

    private LocalDateTime checkTime;

    private LocalDateTime createTime;

}
