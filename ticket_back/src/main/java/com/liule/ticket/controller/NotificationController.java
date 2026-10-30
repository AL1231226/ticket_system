package com.liule.ticket.controller;

import com.liule.ticket.dto.Result;
import com.liule.ticket.service.INotificationService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信。
 * <p>
 * 这个路径不在拦截器白名单里，需要登录 —— 走 {@code authorization} 头。
 */
@RestController
@RequestMapping("/notification")
public class NotificationController {

    @Resource
    private INotificationService notificationService;

    /**
     * 我的消息列表，按时间倒序。
     * <pre>
     * GET /api/notification/list
     * GET /api/notification/list?isRead=0
     * </pre>
     * isRead 可空：空=全部；0=未读；1=已读。只返回当前用户的消息。
     */
    @GetMapping("/list")
    public Result list(@RequestParam(value = "isRead", required = false) Integer isRead) {
        return notificationService.listMine(isRead);
    }

    /**
     * 未读数量，前端轮询红点用。
     * <pre>
     * GET /api/notification/unread
     * </pre>
     */
    @GetMapping("/unread")
    public Result unread() {
        return notificationService.unreadCount();
    }

    /**
     * 单条标记已读；已读再点一次也成功。
     * <pre>
     * PUT /api/notification/{id}/read
     * </pre>
     */
    @PutMapping("/{id}/read")
    public Result read(@PathVariable("id") Long id) {
        return notificationService.markRead(id);
    }

    /**
     * 全部标记已读。
     * <pre>
     * PUT /api/notification/read-all
     * </pre>
     *
     * @return 本次实际更新的条数
     */
    @PutMapping("/read-all")
    public Result readAll() {
        return notificationService.markAllRead();
    }
}
