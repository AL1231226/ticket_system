<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getMyTickets, refreshTicketQr } from '@/api/ticket'
import AppIcon from '@/components/AppIcon.vue'
import QrCode from '@/components/QrCode.vue'
import StateView from '@/components/StateView.vue'
import TicketStub from '@/components/TicketStub.vue'
import { formatSessionDate } from '@/utils/format'

const router = useRouter()

const tickets = ref([])
const loading = ref(true)
const error = ref('')
const activeTicket = ref(null)
const qrValue = ref('')
const qrSeconds = ref(60)
const qrLoading = ref(false)
const qrError = ref('')

let qrTimer = null

const totalCount = computed(() => tickets.value.length)
const validCount = computed(
  () => tickets.value.filter((item) => item.ticket?.status === 0).length,
)
const progress = computed(() => {
  const value = ((60 - qrSeconds.value) / 60) * 100
  return Math.min(100, Math.max(0, value))
})
const activeSeat = computed(() => activeTicket.value?.ticket?.seatNo || '站票/通票')

onMounted(loadTickets)
onBeforeUnmount(closeQr)

async function loadTickets() {
  loading.value = true
  error.value = ''
  try {
    const list = await getMyTickets()
    tickets.value = Array.isArray(list) ? list : []
  } catch (e) {
    error.value = e.message
    tickets.value = []
  } finally {
    loading.value = false
  }
}

function openQr(ticketVO) {
  if (ticketVO?.ticket?.status !== 0) return
  activeTicket.value = ticketVO
  qrValue.value = ticketVO.ticket.qrCode || ''
  qrSeconds.value = 60
  qrError.value = ''
  startQrTimer()
  refreshQr()
}

function closeQr() {
  if (qrTimer) {
    clearInterval(qrTimer)
    qrTimer = null
  }
  activeTicket.value = null
  qrValue.value = ''
  qrError.value = ''
  qrSeconds.value = 60
}

function startQrTimer() {
  if (qrTimer) clearInterval(qrTimer)
  qrTimer = setInterval(() => {
    if (qrLoading.value) return
    if (qrSeconds.value > 0) {
      qrSeconds.value -= 1
    }
    if (qrSeconds.value === 0) {
      refreshQr()
    }
  }, 1000)
}

async function refreshQr() {
  if (!activeTicket.value || qrLoading.value) return
  qrLoading.value = true
  qrError.value = ''
  try {
    const updated = await refreshTicketQr(activeTicket.value.ticket.id)
    activeTicket.value = updated
    qrValue.value = updated.ticket.qrCode
    qrSeconds.value = 60

    const index = tickets.value.findIndex(
      (item) => String(item.ticket.id) === String(updated.ticket.id),
    )
    if (index >= 0) {
      tickets.value[index] = updated
    }
  } catch (e) {
    qrError.value = e.message
    qrSeconds.value = 10
  } finally {
    qrLoading.value = false
  }
}

function goHome() {
  router.push('/home')
}
</script>

<template>
  <div class="wallet page">
    <header class="wallet__head">
      <div>
        <h1>我的票夹</h1>
        <p>共 {{ totalCount }} 张票 · {{ validCount }} 张待使用</p>
      </div>
      <button class="wallet__refresh" type="button" aria-label="刷新票夹" @click="loadTickets">
        <AppIcon name="refresh" :size="17" />
      </button>
    </header>

    <div v-if="loading" class="wallet__skeletons">
      <div v-for="i in 2" :key="i" class="wallet__skeleton" />
    </div>

    <StateView
      v-else-if="error"
      title="票夹加载失败"
      :description="error"
      action-text="重试"
      @action="loadTickets"
    />

    <StateView
      v-else-if="!tickets.length"
      icon="ticket"
      title="还没有票"
      description="看中一场演出，抢到票后会出现在这里。"
      action-text="去看演出"
      @action="goHome"
    />

    <div v-else class="wallet__list">
      <TicketStub
        v-for="item in tickets"
        :key="item.ticket.id"
        :ticketVO="item"
        @show-qr="openQr"
      />
      <p class="wallet__end">电子票每 60 秒更新一次二维码，入场前请打开最新二维码。</p>
    </div>

    <Teleport to="body">
      <div v-if="activeTicket" class="qr-mask" @click.self="closeQr">
        <div class="qr-sheet">
          <header class="qr-sheet__head">
            <span>电子票</span>
            <button type="button" aria-label="关闭" @click="closeQr">
              <AppIcon name="x" :size="18" />
            </button>
          </header>

          <h2>{{ activeTicket.showName }}</h2>
          <p class="qr-sheet__meta">
            {{ formatSessionDate(activeTicket.sessionTime) }}
          </p>

          <div class="qr-sheet__code">
            <QrCode :value="qrValue" :size="216" />
            <span v-if="qrLoading" class="qr-sheet__loading">刷新中…</span>
          </div>

          <div class="qr-sheet__seat">
            <span>{{ activeTicket.tierName }} · {{ activeSeat }}</span>
            <b>{{ activeTicket.ticket.realName || '观演人' }}</b>
          </div>

          <div class="qr-sheet__refresh">
            <div class="qr-sheet__bar">
              <i :style="{ width: `${progress}%` }" />
            </div>
            <div class="qr-sheet__refresh-row">
              <span>{{ qrLoading ? '正在刷新…' : `${qrSeconds} 秒后自动刷新` }}</span>
              <button type="button" :disabled="qrLoading" @click="refreshQr">
                <AppIcon name="refresh" :size="13" />
                手动刷新
              </button>
            </div>
          </div>

          <p v-if="qrError" class="qr-sheet__error">{{ qrError }}</p>
          <p class="qr-sheet__hint">二维码每 60 秒更新，请勿截图转发。</p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.wallet {
  padding-bottom: 26px;
}

.wallet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: calc(20px + env(safe-area-inset-top)) 16px 6px;

  h1 {
    margin: 0;
    font-size: 21px;
    font-weight: 800;
    letter-spacing: -0.4px;
  }

  p {
    margin: 6px 0 0;
    color: var(--text-3);
    font-size: 10px;
  }
}

.wallet__refresh {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--bg-card);
  color: var(--text-3);
  cursor: pointer;
}

.wallet__list {
  padding: 0 16px;
}

.wallet__end {
  margin: 22px 4px 0;
  color: var(--text-3);
  font-size: 9px;
  line-height: 1.7;
  text-align: center;
}

.wallet__skeletons {
  padding: 0 16px;
}

.wallet__skeleton {
  height: 188px;
  margin-top: 14px;
  border-radius: 15px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

.qr-mask {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(6px);
}

.qr-sheet {
  width: 100%;
  max-width: 328px;
  padding: 18px 18px 20px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--bg-card);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.6);
  animation: qr-in 0.22s ease both;

  h2 {
    overflow: hidden;
    margin: 14px 0 0;
    font-size: 14px;
    font-weight: 800;
    letter-spacing: -0.2px;
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.qr-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;

  span {
    color: var(--text-3);
    font-size: 9px;
    letter-spacing: 0.8px;
  }

  button {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border: 0;
    border-radius: 50%;
    background: var(--bg-elevated);
    color: var(--text-2);
    cursor: pointer;
  }
}

.qr-sheet__meta {
  overflow: hidden;
  margin: 6px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qr-sheet__code {
  position: relative;
  display: grid;
  place-items: center;
  width: 232px;
  height: 232px;
  margin: 16px auto 0;
  padding: 8px;
  border-radius: 12px;
  background: #fff;
}

.qr-sheet__loading {
  position: absolute;
  bottom: 10px;
  left: 50%;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(15, 15, 20, 0.82);
  color: #fff;
  font-size: 8px;
  transform: translateX(-50%);
}

.qr-sheet__seat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 15px;
  padding: 0 2px;

  span {
    color: var(--text-2);
    font-size: 10px;
  }

  b {
    color: var(--text-1);
    font-size: 11px;
    font-weight: 700;
  }
}

.qr-sheet__refresh {
  margin-top: 12px;
}

.qr-sheet__bar {
  height: 3px;
  overflow: hidden;
  border-radius: 3px;
  background: var(--bg-elevated);

  i {
    display: block;
    height: 100%;
    border-radius: 3px;
    background: linear-gradient(90deg, var(--brand), var(--accent));
    transition: width 0.3s linear;
  }
}

.qr-sheet__refresh-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 7px;

  span {
    color: var(--text-3);
    font-size: 8.5px;
    font-variant-numeric: tabular-nums;
  }

  button {
    display: flex;
    align-items: center;
    gap: 3px;
    padding: 0;
    border: 0;
    background: none;
    color: var(--brand);
    font-size: 9px;
    cursor: pointer;

    &:disabled {
      color: var(--text-3);
    }
  }
}

.qr-sheet__error {
  margin: 10px 0 0;
  color: #ff9aa3;
  font-size: 9px;
  text-align: center;
}

.qr-sheet__hint {
  margin: 13px 0 0;
  color: var(--text-3);
  font-size: 8.5px;
  text-align: center;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}

@keyframes qr-in {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(6px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
</style>
