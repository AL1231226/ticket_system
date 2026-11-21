import test from 'node:test'
import assert from 'node:assert/strict'

import { formatCountdown, formatMoney, formatRelativeTime, formatWant } from './format.js'

test('formatMoney adds currency and separators', () => {
  assert.equal(formatMoney(1280), '¥1,280')
  assert.equal(formatMoney(0), '¥0')
})

test('formatWant uses 万 for large numbers', () => {
  assert.equal(formatWant(12840), '1.2 万人想看')
  assert.equal(formatWant(4213), '4,213 人想看')
})

test('formatCountdown splits days/hours/minutes/seconds', () => {
  const now = new Date('2026-10-20T12:00:00').getTime()
  const result = formatCountdown('2026-10-21T14:03:04', now)
  assert.deepEqual(result, {
    expired: false,
    days: 1,
    hours: 2,
    minutes: 3,
    seconds: 4,
  })
})

test('formatCountdown marks past targets expired', () => {
  const now = new Date('2026-10-20T12:00:00').getTime()
  assert.equal(formatCountdown('2026-10-20T11:00:00', now).expired, true)
})

test('formatRelativeTime returns 刚刚 for recent and future times', () => {
  const now = new Date('2026-10-30T12:00:00').getTime()
  assert.equal(formatRelativeTime(new Date(now - 30_000), now), '刚刚')
  assert.equal(formatRelativeTime(new Date(now + 10_000), now), '刚刚')
})

test('formatRelativeTime returns minutes and hours', () => {
  const now = new Date('2026-10-30T12:00:00').getTime()
  assert.equal(formatRelativeTime(new Date(now - 5 * 60_000), now), '5 分钟前')
  assert.equal(formatRelativeTime(new Date(now - 3 * 3_600_000), now), '3 小时前')
})

test('formatRelativeTime returns days under a week', () => {
  const now = new Date('2026-10-30T12:00:00').getTime()
  assert.equal(formatRelativeTime(new Date(now - 2 * 86_400_000), now), '2 天前')
  assert.equal(formatRelativeTime(new Date(now - 6 * 86_400_000 - 60_000), now), '6 天前')
})

test('formatRelativeTime falls back to month/day in the same year', () => {
  const now = new Date('2026-10-30T12:00:00').getTime()
  assert.equal(formatRelativeTime('2026-10-20T08:00:00', now), '10月20日')
})

test('formatRelativeTime falls back to year and blanks invalid input', () => {
  const now = new Date('2026-10-30T12:00:00').getTime()
  assert.equal(formatRelativeTime('2025-12-31T08:00:00', now), '2025年12月31日')
  assert.equal(formatRelativeTime(''), '')
  assert.equal(formatRelativeTime('not-a-date', now), '')
})
