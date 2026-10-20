package com.liule.ticket.controller;

import com.liule.ticket.dto.Result;
import com.liule.ticket.service.ITicketService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的票夹。
 * <p>
 * 这个路径不在拦截器白名单里，需要登录 —— 走 {@code authorization} 头。
 */
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    /**
     * 我的票夹：当前用户的所有票，带演出/场次/票档展示字段。
     * <pre>
     * GET /api/ticket/list
     * </pre>
     */
    @GetMapping("/list")
    public Result list() {
        return ticketService.listMine();
    }

    /**
     * 刷新动态二维码：每次调用都换新值，旧码随即作废。
     * <pre>
     * GET /api/ticket/{ticketId}/qr
     * </pre>
     * 前端建议每 60 秒调一次；只能刷新自己的、未使用的票。
     */
    @GetMapping("/{ticketId}/qr")
    public Result refreshQr(@PathVariable("ticketId") Long ticketId) {
        return ticketService.refreshQr(ticketId);
    }
}
