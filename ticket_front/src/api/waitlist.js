import request from '@/utils/request'

/** 加入候补：{tierId, quantity}，返回 {position, total, joinTime}。 */
export function joinWaitlist({ tierId, quantity }) {
  return request.post('/waitlist/join', { tierId, quantity })
}

/** 我的候补列表，排队中的行带 position/total。 */
export function getMyWaitlists() {
  return request.get('/waitlist/my')
}

/** 我在指定票档的排队位置。 */
export function getWaitlistPosition(tierId) {
  return request.get(`/waitlist/${tierId}/position`)
}

/** 退出候补。 */
export function quitWaitlist(tierId) {
  return request.delete(`/waitlist/${tierId}`)
}
