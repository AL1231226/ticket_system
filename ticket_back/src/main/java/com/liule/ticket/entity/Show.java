package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show")
public class Show implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 演出名
     */
    private String name;

    /**
     * 艺人/演出团体
     */
    private String artist;

    /**
     * 场馆ID
     */
    private Long venueId;

    /**
     * 冗余城市，便于筛选
     */
    private String city;

    /**
     * 分类ID
     */
    private Long categoryId;

    /**
     * 海报（列表大图）
     */
    private String poster;

    /**
     * 详情图，逗号分隔
     */
    private String images;

    /**
     * 巡演开始日期
     */
    private LocalDate startDate;

    /**
     * 巡演结束日期
     */
    private LocalDate endDate;

    /**
     * "想看"人数
     */
    private Integer wantCount;

    /**
     * 0下架 1上架
     */
    private Integer status;

    /**
     * 演出介绍
     */
    private String description;

    /**
     * 最低票价，非表字段。
     * 装配详情时从 tb_ticket_tier 算出来填上，用于显示"¥xxx 起"。
     */
    @TableField(exist = false)
    private BigDecimal minPrice;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
