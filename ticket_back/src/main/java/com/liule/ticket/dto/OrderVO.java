package com.liule.ticket.dto;

import com.liule.ticket.entity.TicketOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单展示对象：订单本体 + 演出/场次/票档的展示字段。
 * <p>
 * 订单表只存各类 id，订单列表要显示名称，所以统一在服务层批量装配，
 * 避免前端逐条查询，也避免后端 N+1。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO {

    private TicketOrder order;

    private String showName;

    private String poster;

    private String sessionName;

    private LocalDateTime sessionTime;

    private String tierName;

    private String seatArea;
}
