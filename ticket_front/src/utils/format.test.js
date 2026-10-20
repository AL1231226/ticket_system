import test from 'node:test'
import assert from 'node:assert/strict'

import { formatCountdown, formatMoney, formatWant } from './format.js'

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
