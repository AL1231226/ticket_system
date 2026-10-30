package com.liule.ticket.dto;

import com.liule.ticket.entity.Waitlist;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 候补展示对象：候补行 + 演出/场次/票档的展示字段 + 实时排队位置。
 * <p>
 * 位置不落库、不缓存，查询时用 Redis {@code ZRANK + 1}；
 * {@code total} 是当前该票档队列总人数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistVO {

    private Waitlist waitlist;

    private String showName;

    private String poster;

    private String sessionName;

    private LocalDateTime sessionTime;

    private String tierName;

    private String seatArea;

    private BigDecimal price;

    /**
     * 排队位置，从 1 开始；只有 {@code status=0 排队中} 才有值。
     */
    private Integer position;

    /**
     * 当前队列总人数；只有 {@code status=0} 才有值。
     */
    private Integer total;
}
