<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import { useNotificationStore } from '@/stores/notification'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const store = useNotificationStore()

const TABS = [
  { label: '全部', value: null },
  { label: '未读', value: 0 },
]

const active = ref(null)
const busy = ref(false)
const notice = ref('')
const actionError = ref('')

const activeLabel = computed(
  () => TABS.find((tab) => tab.value === active.value)?.label || '全部',
)
const emptyTitle = computed(() =>
  active.value === 0 ? '没有未读消息' : '还没有消息',
)

onMounted(() => {
  store.refreshUnread()
  load()
})

async function load() {
  try {
    await store.loadList(active.value)
  } catch {
    // store.error 已经带上原因
  }
}

function selectTab(value) {
  if (active.value === value) return
  active.value = value
  notice.value = ''
  actionError.value = ''
  load()
}

function typeMeta(type) {
  const n = Number(type)
  if (n === 1) return { label: '候补补位', icon: 'hourglass', className: 'is-waitlist' }
  if (n === 2) return { label: '开票提醒', icon: 'calendar', className: 'is-remind' }
  if (n === 3) return { label: '订单状态', icon: 'receipt', className: 'is-order' }
  return { label: '系统', icon: 'bell', className: 'is-system' }
}

async function openItem(item) {
  actionError.value = ''
  try {
    if (Number(item.isRead) === 0) {
      await store.markRead(item.id)
    }
  } catch (e) {
    actionError.value = e.message
  }

  if (Number(item.type) === 1 && item.bizId && String(item.bizId) !== '0') {
    router.push(`/order/${item.bizId}`)
  }
}

async function markAll() {
  if (busy.value || store.unread === 0) return
  busy.value = true
  notice.value = ''
  actionError.value = ''
  try {
    const updated = await store.markAllRead()
    notice.value = updated > 0 ? `已标记 ${updated} 条为已读` : '没有未读消息'
    await load()
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="notice page">
    <AppBar title="消息" />

    <div class="notice__tabs">
      <button
        v-for="tab in TABS"
        :key="String(tab.value)"
        type="button"
        class="notice__tab"
        :class="{ 'is-active': active === tab.value }"
        @click="selectTab(tab.value)"
      >
        {{ tab.label }}
      </button>
      <div class="notice__actions">
        <span v-if="store.unread > 0" class="notice__unread">{{ store.unread }} 条未读</span>
        <button
          class="notice__read-all"
          type="button"
          :disabled="busy || store.unread === 0"
          @click="markAll"
        >
          {{ busy ? '处理中…' : '全部已读' }}
        </button>
      </div>
    </div>

    <p v-if="notice" class="notice__notice">{{ notice }}</p>
    <p v-if="actionError" class="notice__error">{{ actionError }}</p>

    <div v-if="store.loading && !store.items.length" class="notice__skeletons">
      <div v-for="i in 3" :key="i" class="notice__skeleton" />
    </div>

    <StateView
      v-else-if="store.error && !store.items.length"
      icon="bell"
      title="消息加载失败"
      :description="store.error"
      action-text="重试"
      @action="load"
    />

    <StateView
      v-else-if="!store.items.length"
      icon="bell"
      :title="emptyTitle"
      :description="active === 0 ? '候补补位和订单变化会出现在这里。' : '暂时没有新的通知。'"
      action-text="刷新"
      @action="load"
    />

    <main v-else class="notice__list">
      <button
        v-for="item in store.items"
        :key="item.id"
        type="button"
        class="notice-item"
        :class="{ 'is-unread': Number(item.isRead) === 0 }"
        @click="openItem(item)"
      >
        <span class="notice-item__icon" :class="typeMeta(item.type).className">
          <AppIcon :name="typeMeta(item.type).icon" :size="16" />
        </span>
        <span class="notice-item__body">
          <span class="notice-item__head">
            <b>{{ item.title || typeMeta(item.type).label }}</b>
            <i v-if="Number(item.isRead) === 0" class="notice-item__dot" />
          </span>
          <span class="notice-item__content">{{ item.content }}</span>
          <span class="notice-item__meta">
            {{ typeMeta(item.type).label }} · {{ formatDateTime(item.createTime) }}
          </span>
        </span>
        <AppIcon name="chevron-right" :size="15" class="notice-item__arrow" />
      </button>
      <p v-if="active === null" class="notice__end">消息按时间倒序，最多展示全部历史。</p>
    </main>
  </div>
</template>

<style scoped lang="scss">
.notice {
  padding-bottom: 26px;
}

.notice__read-all {
  padding: 0;
  border: 0;
  background: transparent;
  color: #b7a8ff;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;

  &:disabled {
    color: var(--text-3);
    cursor: default;
  }
}

.notice__tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px 0;
}

.notice__tab {
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--text-2);
  font-size: 10.5px;
  cursor: pointer;

  &.is-active {
    border-color: rgba(123, 97, 255, 0.45);
    background: var(--brand-alt-soft);
    color: #c8bcff;
    font-weight: 700;
  }
}

.notice__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.notice__unread {
  color: var(--text-3);
  font-size: 9px;
}

.notice__notice,
.notice__error {
  margin: 10px 16px 0;
  font-size: 9.5px;
  text-align: center;
}

.notice__notice {
  color: var(--success);
}

.notice__error {
  color: #ff9aa3;
}

.notice__list {
  padding: 10px 16px 0;
}

.notice-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 13px 2px;
  border: 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.65);
  background: transparent;
  text-align: left;
  cursor: pointer;

  &:active {
    background: rgba(255, 255, 255, 0.025);
  }

  &:last-of-type {
    border-bottom: 0;
  }
}

.notice-item__icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-3);

  &.is-waitlist {
    background: var(--brand-alt-soft);
    color: #b7a8ff;
  }

  &.is-remind {
    background: var(--gold-soft);
    color: var(--accent);
  }

  &.is-order {
    background: var(--brand-soft);
    color: var(--brand);
  }
}

.notice-item__body {
  min-width: 0;
  flex: 1;
}

.notice-item__head {
  display: flex;
  align-items: center;
  gap: 6px;

  b {
    overflow: hidden;
    color: var(--text-1);
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.notice-item.is-unread .notice-item__head b {
  font-weight: 800;
}

.notice-item__dot {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--brand);
}

.notice-item__content {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 5px;
  color: var(--text-2);
  font-size: 10px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.notice-item__meta {
  display: block;
  margin-top: 6px;
  color: var(--text-3);
  font-size: 8.5px;
}

.notice-item__arrow {
  margin-top: 8px;
  flex: 0 0 auto;
  color: var(--text-3);
}

.notice__end {
  margin: 18px 0 0;
  color: var(--text-3);
  font-size: 8.5px;
  text-align: center;
}

.notice__skeletons {
  padding: 10px 16px 0;
}

.notice__skeleton {
  height: 72px;
  margin-top: 8px;
  border-radius: 12px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
