package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_session")
public class ShowSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 演出ID
     */
    private Long showId;

    /**
     * 场次名，如"周六 19:30"
     */
    private String sessionName;

    /**
     * 开演时间
     */
    private LocalDateTime sessionTime;

    /**
     * 本场开票时间
     */
    private LocalDateTime onSaleTime;

    /**
     * 每账号在本场次的【累计】限购上限 —— 总共能买几张。
     * <p>
     * 和票档表的 maxPerOrder 是两层互相独立的约束，别混：
     * 这里由秒杀 Lua 按「账号 + 场次」累计判定（计数在 seckill:ordered:{sessionId}），
     * 那里只管一笔订单最多几张，在 Java 侧的前置校验里判。
     */
    private Integer purchaseLimit;

    /**
     * 0未开票 1预售中 2已开票 3已售罄 4已结束 5已取消
     */
    private Integer status;

    /**
     * 本场的票档列表，非表字段。
     * 装配演出详情时按 session_id 分组填进来。
     */
    @TableField(exist = false)
    private List<TicketTier> tiers;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
