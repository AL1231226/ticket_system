import { ref } from 'vue'
import { defineStore } from 'pinia'

import { getMyWaitlists, joinWaitlist, quitWaitlist } from '@/api/waitlist'

/**
 * 我的候补。
 * 详情页和我的候补页共用同一份数据：加入/退出后统一 loadMine(true)，
 * 避免两个页面各拿一份、状态对不上。
 */
export const useWaitlistStore = defineStore('waitlist', () => {
  const entries = ref([])
  const loading = ref(false)
  const error = ref('')

  let loaded = false
  let pending = null

  async function loadMine(force = false) {
    if (loading.value && pending) return pending
    if (loaded && !force) return entries.value

    loading.value = true
    error.value = ''
    pending = getMyWaitlists()
      .then((list) => {
        entries.value = Array.isArray(list) ? list : []
        loaded = true
        return entries.value
      })
      .catch((e) => {
        error.value = e.message
        throw e
      })
      .finally(() => {
        loading.value = false
        pending = null
      })
    return pending
  }

  /** 详情页按票档取当前状态；没有候补行时返回 null。 */
  function byTier(tierId) {
    return (
      entries.value.find(
        (item) => String(item.waitlist?.tierId) === String(tierId),
      ) || null
    )
  }

  async function join(form) {
    await joinWaitlist(form)
    await loadMine(true)
  }

  async function quit(tierId) {
    await quitWaitlist(tierId)
    await loadMine(true)
  }

  return { entries, loading, error, loadMine, byTier, join, quit }
})
