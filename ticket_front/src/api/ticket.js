import request from '@/utils/request'

export function getMyTickets() {
  return request.get('/ticket/list')
}

/** 刷新动态二维码；返回带新 qrCode 的 TicketVO。 */
export function refreshTicketQr(ticketId) {
  return request.get(`/ticket/${ticketId}/qr`)
}
