import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { formatCountdown, pad2 } from '@/utils/format'

/**
 * 倒计时。target 是 ref/computed，可以随数据变化。
 * 返回 remaining（天/时/分/秒/expired）和 text（HH:MM:SS，超过一天带天数）。
 */
export function useCountdown(target) {
  const now = ref(Date.now())
  let timer = null

  const remaining = computed(() => formatCountdown(target.value, now.value))

  const text = computed(() => {
    const value = remaining.value
    if (value.expired) return '00:00:00'
    const clock = `${pad2(value.hours)}:${pad2(value.minutes)}:${pad2(value.seconds)}`
    return value.days > 0 ? `${value.days}天 ${clock}` : clock
  })

  onMounted(() => {
    timer = setInterval(() => {
      now.value = Date.now()
    }, 1000)
  })

  onBeforeUnmount(() => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  })

  return { remaining, text }
}
