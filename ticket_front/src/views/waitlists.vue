<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import WaitlistCard from '@/components/WaitlistCard.vue'
import { useWaitlistStore } from '@/stores/waitlist'

const router = useRouter()
const store = useWaitlistStore()

const dialog = ref(null)
const dialogBusy = ref(false)
const actionError = ref('')

let pollTimer = null

const entries = computed(() => store.entries)
const queuedCount = computed(
  () => entries.value.filter((item) => item.waitlist?.status === 0).length,
)
const notifiedCount = computed(
  () => entries.value.filter((item) => item.waitlist?.status === 1).length,
)

onMounted(async () => {
  await refresh()
  pollTimer = setInterval(tick, 10000)
  document.addEventListener('visibilitychange', onVisible)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  document.removeEventListener('visibilitychange', onVisible)
})

async function refresh() {
  try {
    await store.loadMine(true)
  } catch {
    // store.error 已经带上原因，页面用 StateView 展示
  }
}

function tick() {
  if (!document.hidden) refresh()
}

function onVisible() {
  if (!document.hidden) refresh()
}

function askQuit(entry) {
  actionError.value = ''
  dialog.value = { type: 'quit', entry }
}

function askRejoin(entry) {
  actionError.value = ''
  dialog.value = { type: 'rejoin', entry }
}

async function confirmDialog() {
  if (!dialog.value || dialogBusy.value) return
  const { type, entry } = dialog.value
  dialogBusy.value = true
  actionError.value = ''
  try {
    if (type === 'quit') {
      await store.quit(entry.waitlist.tierId)
    } else {
      const quantity = Number(entry.waitlist.quantity || 1)
      await store.join({ tierId: entry.waitlist.tierId, quantity })
    }
    dialog.value = null
  } catch (e) {
    actionError.value = e.message
    dialog.value = null
  } finally {
    dialogBusy.value = false
  }
}

function goPay(entry) {
  router.push(`/order/${entry.waitlist.orderId}`)
}

function goOrder(entry) {
  router.push(`/order/${entry.waitlist.orderId}`)
}

function goHome() {
  router.push('/home')
}
</script>

<template>
  <div class="wls page">
    <AppBar title="我的候补">
      <template #right>
        <button class="wls__refresh" type="button" aria-label="刷新" @click="refresh">
          <AppIcon name="refresh" :size="17" />
        </button>
      </template>
    </AppBar>

    <header class="wls__head">
      <div>
        <h1>候补队列</h1>
        <p>
          共 {{ entries.length }} 条 · {{ queuedCount }} 条排队中
          <template v-if="notifiedCount">· {{ notifiedCount }} 条待支付</template>
        </p>
      </div>
    </header>

    <div v-if="store.loading && !entries.length" class="wls__skeletons">
      <div v-for="i in 2" :key="i" class="wls__skeleton" />
    </div>

    <StateView
      v-else-if="store.error && !entries.length"
      icon="hourglass"
      title="候补加载失败"
      :description="store.error"
      action-text="重试"
      @action="refresh"
    />

    <StateView
      v-else-if="!entries.length"
      icon="hourglass"
      title="还没有候补"
      description="看中的演出售罄后，可以加入候补等回流票。"
      action-text="去看演出"
      @action="goHome"
    />

    <main v-else class="wls__list">
      <WaitlistCard
        v-for="entry in entries"
        :key="entry.waitlist.id"
        :entry="entry"
        @quit="askQuit(entry)"
        @pay="goPay(entry)"
        @order="goOrder(entry)"
        @rejoin="askRejoin(entry)"
      />
      <p class="wls__tip">有人退票时会按排队顺序补位；补位后请在 10 分钟内支付。</p>
      <p v-if="actionError" class="wls__error">{{ actionError }}</p>
    </main>

    <Teleport to="body">
      <div v-if="dialog" class="confirm-mask" @click.self="dialog = null">
        <div class="confirm-card">
          <template v-if="dialog.type === 'quit'">
            <h3>退出候补？</h3>
            <p>退出后会失去当前排队位置，之后只能重新排到队尾。</p>
            <div class="confirm-card__actions">
              <button type="button" class="ghost" @click="dialog = null">再想想</button>
              <button type="button" class="danger" :disabled="dialogBusy" @click="confirmDialog">
                {{ dialogBusy ? '退出中…' : '退出候补' }}
              </button>
            </div>
          </template>
          <template v-else>
            <h3>重新候补？</h3>
            <p>
              将以 {{ dialog.entry.waitlist.quantity }} 张重新排到队尾，原来的位置不会保留。
            </p>
            <div class="confirm-card__actions">
              <button type="button" class="ghost" @click="dialog = null">再想想</button>
              <button type="button" class="primary" :disabled="dialogBusy" @click="confirmDialog">
                {{ dialogBusy ? '提交中…' : '重新候补' }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.wls {
  padding-bottom: 26px;
}

.wls__refresh {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 0;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
}

.wls__head {
  padding: 14px 16px 0;

  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 800;
    letter-spacing: -0.4px;
  }

  p {
    margin: 6px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
  }
}

.wls__list {
  padding: 0 16px;
}

.wls__title {
  color: var(--text-1);
}

.wls__tip {
  margin: 20px 4px 0;
  color: var(--text-3);
  font-size: 9px;
  line-height: 1.7;
  text-align: center;
}

.wls__error {
  margin: 12px 4px 0;
  color: #ff9aa3;
  font-size: 9.5px;
  text-align: center;
}

.wls__skeletons {
  padding: 0 16px;
}

.wls__skeleton {
  height: 190px;
  margin-top: 12px;
  border-radius: 15px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.62);
  backdrop-filter: blur(4px);
}

.confirm-card {
  width: 100%;
  max-width: 300px;
  padding: 22px 20px 18px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: var(--bg-card);
  text-align: center;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
  }

  p {
    margin: 9px 0 0;
    color: var(--text-3);
    font-size: 10.5px;
    line-height: 1.6;
  }
}

.confirm-card__actions {
  display: flex;
  gap: 9px;
  margin-top: 20px;

  button {
    flex: 1;
    height: 40px;
    border-radius: 10px;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;

    &:disabled {
      opacity: 0.6;
    }
  }

  .ghost {
    border: 1px solid var(--line);
    background: transparent;
    color: var(--text-2);
  }

  .danger {
    border: 0;
    background: var(--danger);
    color: #fff;
  }

  .primary {
    border: 0;
    background: linear-gradient(96deg, #7b61ff, #a78bfa);
    color: #fff;
  }
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
