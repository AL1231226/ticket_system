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
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_order")
public class TicketOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单号。由 RedisIdWorker 生成，不是数据库自增，所以是 IdType.INPUT
     */
    @TableId(value = "id", type = IdType.INPUT)
    // 64 位订单号超出 JS Number 的安全整数范围，必须按字符串返回，否则前端会丢精度
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 下单用户
     */
    private Long userId;

    /**
     * 冗余演出ID，便于不 join 就能按演出查订单
     */
    private Long showId;

    /**
     * 场次ID。限购是按场次算的，所以这张表上必须有它
     */
    private Long sessionId;

    /**
     * 票档ID
     */
    private Long tierId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 0待支付 1已支付 2已取消 3已退款 4已失效(超时)
     */
    private Integer status;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 0正常抢票 1候补补位
     */
    private Integer source;

    private LocalDateTime createTime;

    /**
     * 支付截止时间（创建 + 15min）
     */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;

}
