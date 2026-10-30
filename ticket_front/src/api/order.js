import request from '@/utils/request'

export function seckill({ tierId, quantity }) {
  return request.post('/order/seckill', { tierId, quantity })
}

export function getSeckillResult(orderId) {
  return request.get(`/order/result/${orderId}`)
}

export function getOrder(orderId) {
  return request.get(`/order/${orderId}`)
}

export function getOrders(status) {
  return request.get('/order/list', { params: { status: status ?? undefined } })
}

export function bindAttendees(orderId, attendeeIds) {
  return request.post(`/order/${orderId}/attendees`, { attendeeIds })
}

export function payOrder(orderId) {
  return request.put('/order/finish', null, { params: { orderId } })
}

export function cancelOrder(orderId) {
  return request.put('/order/cancle', null, { params: { orderId } })
}

/** 整单退款：仅已支付且票全未核销。 */
export function refundOrder(orderId) {
  return request.put('/order/refund', null, { params: { orderId } })
}
