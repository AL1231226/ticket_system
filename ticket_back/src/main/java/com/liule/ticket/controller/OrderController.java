package com.liule.ticket.controller;

import com.liule.ticket.dto.OrderDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.SeckillFormDTO;
import com.liule.ticket.service.IOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {
    @Resource
    private IOrderService orderService;
    @PostMapping("/create")
    public Result createOrder(@RequestBody OrderDTO orderDTO) {
        return orderService.createOrder(orderDTO);

    }
    @PutMapping("/cancle")
    public Result cancleOrder(@RequestParam String orderId) {
        return orderService.cancleOrder(orderId);
    }
    @PutMapping("finish")
    public Result finishOrder(@RequestParam String orderId) {
        return orderService.finishOrder(orderId);
    }

    @PostMapping("/seckill")
    public Result seckill(@RequestBody SeckillFormDTO form) {
        return orderService.seckill(form);
    }

}
