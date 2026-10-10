import axios from 'axios'

/** token 在 localStorage 里的键名。store 与路由守卫都从这里取，避免各写各的字面量。 */
export const TOKEN_KEY = 'ticket_token'

const request = axios.create({
  // 对应 vite.config.js 里的 proxy，最终落到后端 8081
  baseURL: '/api',
  timeout: 10000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    // 后端拦截器读的就是 authorization 这个头
    config.headers.authorization = token
  }
  return config
})

request.interceptors.response.use(
  // 后端统一响应体是 {success, errorMsg, data, total}。
  // 这里把外壳剥掉，业务代码直接拿 data；失败则抛出带后端原文的 Error，
  // 由调用方决定怎么展示（登录页是行内提示，不是全局 toast）。
  (response) => {
    const { success, errorMsg, data } = response.data
    if (!success) {
      return Promise.reject(new Error(errorMsg || '请求失败'))
    }
    return data
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      // 不 import router：那会形成 api → router → view → api 的循环依赖。
      // 登录态失效属于罕见路径，整页跳转反而是更干净的做法。
      if (!window.location.pathname.startsWith('/login')) {
        window.location.replace('/login')
      }
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    return Promise.reject(new Error(error.response?.data?.errorMsg || '网络异常，请稍后重试'))
  },
)

export default request
