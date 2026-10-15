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
