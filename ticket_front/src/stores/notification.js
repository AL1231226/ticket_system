import { ref } from 'vue'
import { defineStore } from 'pinia'

import {
  getNotifications,
  getUnreadCount,
  readAllNotifications,
  readNotification,
} from '@/api/notification'

/**
 * 站内信。
 * 未读数被我的/票夹/消息页共用；列表只在消息页维护。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  const items = ref([])
  const loading = ref(false)
  const error = ref('')

  let listFilter = null

  /** 红点轮询只关心数量，失败时保留上一次的值，不打断页面。 */
  async function refreshUnread() {
    try {
      const data = await getUnreadCount()
      unread.value = Number(data?.unread || 0)
    } catch {
      // 忽略：未读数是锦上添花，不能因为轮询失败让页面报错
    }
  }

  async function loadList(isRead = null) {
    listFilter = isRead
    loading.value = true
    error.value = ''
    try {
      const list = await getNotifications(isRead)
      items.value = Array.isArray(list) ? list : []
    } catch (e) {
      error.value = e.message
      items.value = []
      throw e
    } finally {
      loading.value = false
    }
  }

  async function reload() {
    return loadList(listFilter)
  }

  async function markRead(id) {
    await readNotification(id)
    const item = items.value.find((row) => String(row.id) === String(id))
    if (item && item.isRead === 0) {
      item.isRead = 1
      unread.value = Math.max(0, unread.value - 1)
    }
    refreshUnread()
  }

  async function markAllRead() {
    const updated = await readAllNotifications()
    items.value = items.value.map((row) => ({ ...row, isRead: 1 }))
    unread.value = 0
    return Number(updated || 0)
  }

  return {
    unread,
    items,
    loading,
    error,
    refreshUnread,
    loadList,
    reload,
    markRead,
    markAllRead,
  }
})
