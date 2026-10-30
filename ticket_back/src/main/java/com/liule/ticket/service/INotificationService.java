package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.Notification;

/**
 * 站内信。E11-6 负责写（候补补位成功），E11-7 只负责读和已读状态。
 * <p>
 * 不做 WebSocket：前端在「我的」页轮询未读数拿红点，进入消息页再拉列表。
 */
public interface INotificationService extends IService<Notification> {

    /**
     * 我的消息列表，按创建时间倒序。
     *
     * @param isRead 0 未读 / 1 已读；null=全部
     */
    Result listMine(Integer isRead);

    /**
     * 未读数量，前端轮询红点用。返回 {@code {"unread": n}}。
     */
    Result unreadCount();

    /**
     * 单条标记已读。
     * <p>
     * 只允许操作自己的消息；已读再点一次也返回成功（幂等）。
     */
    Result markRead(Long id);

    /**
     * 全部标记已读，返回本次实际更新的条数。
     */
    Result markAllRead();
}
