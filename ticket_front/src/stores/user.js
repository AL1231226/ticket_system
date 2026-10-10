import { ref } from 'vue'
import { defineStore } from 'pinia'

import { TOKEN_KEY } from '@/utils/request'
import { getMe, login as loginApi, logout as logoutApi } from '@/api/user'

/**
 * 登录态。
 * token 同时存在 Pinia（响应式，供模板用）和 localStorage（刷新后不丢）里，
 * 两者只在 setToken 这一处同步，避免出现"一半有一半没有"的中间状态。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const userInfo = ref(null)

  function setToken(value) {
    token.value = value || ''
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  /** 登录并记住 token */
  async function login(form) {
    const newToken = await loginApi(form)
    setToken(newToken)
    return newToken
  }

  /** 拉取当前用户信息，用于首页显示昵称 / 校验 token 是否还有效 */
  async function fetchUserInfo() {
    userInfo.value = await getMe()
    return userInfo.value
  }

  /** 登出。即使后端调用失败也要清本地登录态，否则会卡在"登不出去" */
  async function logout() {
    try {
      await logoutApi()
    } finally {
      setToken('')
      userInfo.value = null
    }
  }

  return { token, userInfo, setToken, login, fetchUserInfo, logout }
})
