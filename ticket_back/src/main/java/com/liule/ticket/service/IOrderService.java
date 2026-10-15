package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.OrderDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.entity.TicketOrder;

public interface IOrderService extends IService<TicketOrder> {

    Result createOrder(OrderDTO orderDTO);

    Result cancleOrder(String orderId);

    Result finishOrder(String orderId);

    Result seckill(SeckillFormDTO form);
}
