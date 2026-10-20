package com.liule.ticket.controller;

import com.liule.ticket.dto.BindAttendeeDTO;
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

    /**
     * 抢票结果轮询。seckill 返回的 orderId 只是"抢到名额"的凭证，订单此刻还没落库，
     * 前端拿它反复调这里，直到 created=true（可以付款）或 failed=true（抢票失败）。
     */
    @GetMapping("/result/{orderId}")
    public Result result(@PathVariable("orderId") Long orderId) {
        return orderService.getSeckillResult(orderId);
    }

    /**
     * 我的订单列表，按创建时间倒序。
     * <pre>
     * GET /api/order/list?status=0
     * </pre>
     * status 可空：空=全部；0待支付 1已支付 2已取消 3已退款 4已失效。
     */
    @GetMapping("/list")
    public Result list(@RequestParam(value = "status", required = false) Integer status) {
        return orderService.listMyOrders(status);
    }

    /**
     * 订单详情：订单本体 + 演出名/海报/场次名/开演时间/票档名/座位区。
     * <pre>
     * GET /api/order/{orderId}
     * </pre>
     * 只允许查自己的订单。
     */
    @GetMapping("/{orderId}")
    public Result detail(@PathVariable("orderId") Long orderId) {
        return orderService.queryOrderById(orderId);
    }

    /**
     * 订单确认：给订单绑定观演人（一票一人）。
     * <pre>
     * POST /api/order/{orderId}/attendees
     * {"attendeeIds": [11, 12]}
     * </pre>
     * 数量必须等于订单的 quantity；列表顺序就是票的顺序。
     */
    @PostMapping("/{orderId}/attendees")
    public Result bindAttendees(@PathVariable("orderId") Long orderId,
                                @RequestBody BindAttendeeDTO form) {
        return orderService.bindAttendees(orderId, form.getAttendeeIds());
    }

}
