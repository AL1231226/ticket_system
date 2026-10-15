package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_tier")
public class TicketTier implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 场次ID
     */
    private Long sessionId;

    /**
     * 冗余演出ID，便于查询
     */
    private Long showId;

    /**
     * 票档名：内场VIP / 看台A
     */
    private String name;

    /**
     * 票价
     */
    private BigDecimal price;

    /**
     * 座位区域描述
     */
    private String seatArea;

    /**
     * 0对号入座 1站票/通票；站票出票时不生成座位号
     */
    private Integer seatType;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 可售库存（Redis 秒杀扣的就是这一列）
     */
    private Integer stock;

    /**
     * 被待支付订单占用的数量。
     * <p>
     * 下单时 stock → lock_stock；支付成功后不再归还（已成交）；
     * 取消或超时失效时 lock_stock → stock。
     * <p>
     * 不存"已成交"列：tb_ticket_order 才是完整账本，
     * 需要销量时从订单表聚合，或算 totalStock - stock - lockStock。
     */
    private Integer lockStock;

    /**
     * 每账号限购数量
     */
    private Integer purchaseLimit;

    /**
     * 0下架 1上架
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
