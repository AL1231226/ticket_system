import request from '@/utils/request'

/** 消息列表；isRead 为空时返回全部，0 未读 / 1 已读。 */
export function getNotifications(isRead) {
  return request.get('/notification/list', {
    params: { isRead: isRead ?? undefined },
  })
}

/** 未读数量，返回 {unread}。 */
export function getUnreadCount() {
  return request.get('/notification/unread')
}

/** 单条标记已读（幂等）。 */
export function readNotification(id) {
  return request.put(`/notification/${id}/read`)
}

/** 全部标记已读，返回实际更新条数。 */
export function readAllNotifications() {
  return request.put('/notification/read-all')
}
