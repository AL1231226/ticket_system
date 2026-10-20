package com.liule.ticket.dto;

import com.liule.ticket.entity.Ticket;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 票夹展示对象：票本体 + 演出/场次/票档的展示字段。
 * <p>
 * 和 {@link OrderVO} 一样，票表只存各类 id，票夹要显示名称，
 * 统一在服务层批量装配，避免前端逐条查询，也避免后端 N+1。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketVO {

    private Ticket ticket;

    private String showName;

    private String poster;

    private String sessionName;

    private LocalDateTime sessionTime;

    private String tierName;

    private String seatArea;
}
