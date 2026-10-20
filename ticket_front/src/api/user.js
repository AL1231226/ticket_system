import request from '@/utils/request'

/**
 * 发送手机验证码。
 * 后端不接短信通道，验证码只打在后端控制台日志里（log.debug）。
 */
export function sendCode(phone) {
  return request.post('/user/code', null, { params: { phone } })
}

/**
 * 验证码登录，手机号未注册时后端会自动注册。
 * @returns {Promise<string>} token（32 位十六进制）
 */
export function login({ phone, code }) {
  return request.post('/user/login', { phone, code })
}

/** 登出：清掉 Redis 里的 token */
export function logout() {
  return request.post('/user/logout')
}

/** 当前登录用户：{id, nickname, icon} */
export function getMe() {
  return request.get('/user/me')
}

/** 用户详情：等级、积分、粉丝、观演场次等。 */
export function getUserInfo(userId) {
  return request.get(`/user/info/${userId}`)
}

export function sign() {
  return request.post('/user/sign')
}

/** 本月连续签到天数。 */
export function getSignCount() {
  return request.get('/user/sign/count')
}
