/**
 * 展示层格式化。只做纯函数，方便用 node --test 直接测。
 */

export function pad2(n) {
  return String(n).padStart(2, '0')
}

export function formatMoney(value) {
  const n = Number(value || 0)
  return `¥${n.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  })}`
}

export function formatWant(count) {
  const n = Number(count || 0)
  if (n >= 10000) {
    // 12840 → 1.2 万，采用向下取整，避免把 1.2 写成 1.3
    return `${Math.floor(n / 1000) / 10} 万人想看`
  }
  return `${n.toLocaleString('zh-CN')} 人想看`
}

export function formatDate(value) {
  if (!value) return ''
  const d = new Date(value)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

export function formatSessionDate(value) {
  if (!value) return ''
  const d = new Date(value)
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日 ${week} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function formatDateTime(value) {
  if (!value) return ''
  const d = new Date(value)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function formatCountdown(target, now = Date.now()) {
  if (!target) {
    return { expired: true, days: 0, hours: 0, minutes: 0, seconds: 0 }
  }
  const end = target instanceof Date ? target.getTime() : new Date(target).getTime()
  if (Number.isNaN(end)) {
    return { expired: true, days: 0, hours: 0, minutes: 0, seconds: 0 }
  }
  const diff = Math.max(0, end - now)
  const total = Math.floor(diff / 1000)
  return {
    expired: end <= now,
    days: Math.floor(total / 86400),
    hours: Math.floor((total % 86400) / 3600),
    minutes: Math.floor((total % 3600) / 60),
    seconds: total % 60,
  }
}

export function orderStatusText(status) {
  return ['待支付', '已支付', '已取消', '已退款', '已失效'][status] || '未知'
}

export function ticketStatusText(status) {
  return ['未使用', '已核验', '已退票'][status] || '未知'
}

export function splitImages(images) {
  return String(images || '')
    .split(',')
    .map((url) => url.trim())
    .filter(Boolean)
}
