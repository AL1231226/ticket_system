<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getSeckillResult } from '@/api/order'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'

const route = useRoute()
const router = useRouter()

const orderId = computed(() => route.params.orderId)
const status = ref('polling')
const reason = ref('')
const elapsed = ref(0)
const attempts = ref(0)
const copied = ref(false)

let pollTimer = null
let elapsedTimer = null
let copyTimer = null
let destroyed = false

const MAX_ATTEMPTS = 40

onMounted(() => {
  destroyed = false
  start()
})
onBeforeUnmount(() => {
  destroyed = true
  cleanup()
})

function start() {
  cleanup()
  status.value = 'polling'
  reason.value = ''
  attempts.value = 0
  elapsed.value = 0
  elapsedTimer = setInterval(() => {
    elapsed.value += 1
  }, 1000)
  poll()
}

async function poll() {
  if (status.value !== 'polling') return

  try {
    const data = await getSeckillResult(orderId.value)
    if (destroyed || status.value !== 'polling') return

    if (data?.created) {
      cleanup()
      router.replace({ name: 'order-detail', params: { orderId: orderId.value } })
      return
    }
    if (data?.failed) {
      status.value = 'failed'
      reason.value = data.reason || '抢票失败，请重新选择'
      cleanup()
      return
    }
  } catch {
    // 网络抖动时继续轮询；401 已由 request.js 统一处理
  }

  if (destroyed) return
  attempts.value += 1
  if (attempts.value >= MAX_ATTEMPTS) {
    status.value = 'timeout'
    cleanup()
    return
  }
  pollTimer = setTimeout(poll, 500)
}

function cleanup() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

function continueWaiting() {
  status.value = 'polling'
  attempts.value = 0
  elapsed.value = 0
  elapsedTimer = setInterval(() => {
    elapsed.value += 1
  }, 1000)
  poll()
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/home')
  }
}

function goOrders() {
  router.push('/orders')
}

async function copyOrderId() {
  try {
    await navigator.clipboard?.writeText(String(orderId.value))
    copied.value = true
    copyTimer = setTimeout(() => {
      copied.value = false
    }, 1600)
  } catch {
    // 剪贴板不可用时静默，不影响主流程
  }
}

onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
})
</script>

<template>
  <div class="queue page">
    <AppBar title="抢票中" />

    <main class="queue__body">
      <template v-if="status === 'polling'">
        <div class="queue-orbit">
          <div class="queue-orbit__core">
            <AppIcon name="ticket" :size="24" :stroke-width="1.5" />
          </div>
        </div>

        <h1>正在为你锁定座位</h1>
        <p class="queue__sub">
          请求已进入队列，系统正在确认库存并生成订单。<br />
          通常几秒内完成，请不要关闭页面。
        </p>

        <div class="queue__order">
          <span>订单号</span>
          <b class="num">{{ orderId }}</b>
          <button type="button" @click="copyOrderId">{{ copied ? '已复制' : '复制' }}</button>
        </div>

        <div class="queue__progress">
          <div class="queue__progress-bar" />
        </div>
        <p class="queue__elapsed">已等待 {{ elapsed }} 秒</p>

        <p class="queue__hint">结果出来会自动跳转，不需要手动刷新。</p>
      </template>

      <template v-else-if="status === 'failed'">
        <div class="queue__icon queue__icon--failed">
          <AppIcon name="x" :size="26" />
        </div>
        <h1>这次没抢到</h1>
        <p class="queue__sub">{{ reason }}</p>

        <div class="queue__actions">
          <button class="queue__btn queue__btn--ghost" type="button" @click="goBack">重新选票</button>
          <button class="queue__btn" type="button" @click="goOrders">查看订单</button>
        </div>
      </template>

      <template v-else>
        <div class="queue__icon queue__icon--waiting">
          <AppIcon name="clock" :size="26" />
        </div>
        <h1>还在排队</h1>
        <p class="queue__sub">
          订单可能已经生成，也可能还在路上。<br />
          可以继续等一下，或者去订单列表看看。
        </p>

        <div class="queue__order">
          <span>订单号</span>
          <b class="num">{{ orderId }}</b>
          <button type="button" @click="copyOrderId">{{ copied ? '已复制' : '复制' }}</button>
        </div>

        <div class="queue__actions">
          <button class="queue__btn queue__btn--ghost" type="button" @click="continueWaiting">
            继续等待
          </button>
          <button class="queue__btn" type="button" @click="goOrders">查看订单</button>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped lang="scss">
.queue {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  background:
    radial-gradient(circle at 50% 18%, rgba(255, 61, 113, 0.1), transparent 34%),
    var(--bg-base);
}

.queue__body {
  display: flex;
  align-items: center;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  padding: 24px 28px calc(34px + env(safe-area-inset-bottom));
  text-align: center;

  h1 {
    margin: 26px 0 0;
    font-size: 19px;
    font-weight: 800;
    letter-spacing: -0.4px;
  }
}

.queue-orbit {
  position: relative;
  display: grid;
  place-items: center;
  width: 108px;
  height: 108px;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: 50%;
    background: conic-gradient(from 90deg, transparent 0 22%, #ff3d71 48%, #ffcb47 62%, transparent 78%);
    -webkit-mask: radial-gradient(farthest-side, transparent calc(100% - 2px), #000 calc(100% - 1px));
    mask: radial-gradient(farthest-side, transparent calc(100% - 2px), #000 calc(100% - 1px));
    animation: spin 2.4s linear infinite;
  }

  &::after {
    content: '';
    position: absolute;
    inset: 14px;
    border: 1px dashed rgba(255, 255, 255, 0.14);
    border-radius: 50%;
    animation: spin 8s linear infinite reverse;
  }
}

.queue-orbit__core {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border: 1px solid rgba(255, 61, 113, 0.3);
  border-radius: 18px;
  background: var(--brand-soft);
  box-shadow: 0 0 30px rgba(255, 61, 113, 0.22);
  color: var(--brand);
  animation: breathe 1.8s ease-in-out infinite;
}

.queue__sub {
  margin: 10px 0 0;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.8;
}

.queue__order {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  margin-top: 26px;
  padding: 8px 10px 8px 13px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: rgba(26, 26, 34, 0.8);

  span {
    flex: 0 0 auto;
    color: var(--text-3);
    font-size: 9px;
  }

  b {
    overflow: hidden;
    color: var(--text-2);
    font-size: 9.5px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  button {
    flex: 0 0 auto;
    padding: 3px 7px;
    border: 1px solid var(--line);
    border-radius: 5px;
    background: var(--bg-elevated);
    color: var(--text-2);
    font-size: 8.5px;
    cursor: pointer;
  }
}

.queue__progress {
  width: 168px;
  height: 2px;
  margin-top: 28px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--bg-elevated);
}

.queue__progress-bar {
  width: 46%;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, transparent, var(--brand), var(--accent));
  animation: slide 1.4s ease-in-out infinite;
}

.queue__elapsed {
  margin: 9px 0 0;
  color: var(--text-3);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.queue__hint {
  margin: 34px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
}

.queue__icon {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border-radius: 20px;

  &--failed {
    border: 1px solid rgba(255, 71, 87, 0.25);
    background: rgba(255, 71, 87, 0.08);
    color: var(--danger);
  }

  &--waiting {
    border: 1px solid rgba(255, 203, 71, 0.24);
    background: var(--gold-soft);
    color: var(--accent);
  }
}

.queue__actions {
  display: flex;
  gap: 10px;
  width: 100%;
  max-width: 300px;
  margin-top: 30px;
}

.queue__btn {
  flex: 1;
  height: 44px;
  border: 0;
  border-radius: 11px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  color: #fff;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;

  &--ghost {
    border: 1px solid var(--line);
    background: var(--bg-card);
    color: var(--text-2);
  }

  &:active {
    transform: scale(0.99);
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(0.94);
  }
}

@keyframes slide {
  0% {
    transform: translateX(-120%);
  }
  100% {
    transform: translateX(260%);
  }
}
</style>
