import { onBeforeUnmount, onMounted } from 'vue'
import { storeToRefs } from 'pinia'

import { useNotificationStore } from '@/stores/notification'

/**
 * 未读数轮询：进入页面立即拉一次，之后每 30 秒一次。
 * 页面隐藏时跳过请求；从后台切回来立即补一次。
 */
export function useUnreadPolling(intervalMs = 30000) {
  const store = useNotificationStore()
  // 注意：直接取 store.unread 拿到的是当前数字快照，不是响应式引用。
  // 页面要跟着未读数变，必须用 storeToRefs 保留 ref。
  const { unread } = storeToRefs(store)
  let timer = null

  function tick() {
    if (!document.hidden) {
      store.refreshUnread()
    }
  }

  function onVisible() {
    if (!document.hidden) {
      store.refreshUnread()
    }
  }

  function start() {
    store.refreshUnread()
    timer = setInterval(tick, intervalMs)
    document.addEventListener('visibilitychange', onVisible)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    document.removeEventListener('visibilitychange', onVisible)
  }

  onMounted(start)
  onBeforeUnmount(stop)

  return {
    unread,
    refresh: () => store.refreshUnread(),
  }
}
