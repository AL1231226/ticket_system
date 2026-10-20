<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getOrders } from '@/api/order'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import {
  formatMoney,
  formatSessionDate,
  orderStatusText,
} from '@/utils/format'

const router = useRouter()

const TABS = [
  { label: '全部', value: null },
  { label: '待支付', value: 0 },
  { label: '已支付', value: 1 },
  { label: '已取消', value: 2 },
  { label: '已失效', value: 4 },
]

const active = ref(null)
const orders = ref([])
const loading = ref(true)
const error = ref('')
let requestSeq = 0

const activeLabel = computed(() => TABS.find((tab) => tab.value === active.value)?.label || '全部')

onMounted(fetchOrders)

async function fetchOrders() {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const list = await getOrders(active.value)
    if (seq !== requestSeq) return
    orders.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message
    orders.value = []
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function selectTab(value) {
  if (active.value === value) return
  active.value = value
  fetchOrders()
}

function openOrder(order) {
  router.push(`/order/${order.id}`)
}

function goShow(order) {
  if (order.showId) {
    router.push(`/show/${order.showId}`)
  } else {
    router.push('/home')
  }
}

function statusClass(status) {
  if (status === 0) return 'is-pending'
  if (status === 1) return 'is-paid'
  if (status === 3) return 'is-refunded'
  return 'is-closed'
}

function emptyTitle() {
  if (active.value === 0) return '没有待支付的订单'
  if (active.value === 1) return '还没有已支付的订单'
  if (active.value === 2) return '没有已取消的订单'
  if (active.value === 4) return '没有已失效的订单'
  return '还没有订单'
}
</script>

<template>
  <div class="orders page">
    <AppBar title="我的订单">
      <template #right>
        <button class="orders__refresh" type="button" aria-label="刷新" @click="fetchOrders">
          <AppIcon name="refresh" :size="17" />
        </button>
      </template>
    </AppBar>

    <div class="orders__tabs">
      <button
        v-for="tab in TABS"
        :key="String(tab.value)"
        type="button"
        class="orders__tab"
        :class="{ 'is-active': active === tab.value }"
        @click="selectTab(tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="orders__skeletons">
      <div v-for="i in 3" :key="i" class="orders__skeleton" />
    </div>

    <StateView
      v-else-if="error"
      title="订单加载失败"
      :description="error"
      action-text="重试"
      @action="fetchOrders"
    />

    <StateView
      v-else-if="!orders.length"
      title="还没有订单"
      :description="`${activeLabel}里暂时没有订单。`"
      action-text="去看演出"
      @action="$router.push('/home')"
    />

    <div v-else class="orders__list">
      <article v-for="item in orders" :key="item.order.id" class="order-item">
        <div class="order-item__head" @click="openOrder(item.order)">
          <div class="order-item__poster" :class="{ 'is-fallback': !item.poster }">
            <img v-if="item.poster" :src="item.poster" :alt="item.showName" loading="lazy" />
            <span v-else>{{ (item.showName || '演').slice(0, 1) }}</span>
          </div>

          <div class="order-item__info">
            <h3>{{ item.showName || '演出' }}</h3>
            <p>{{ formatSessionDate(item.sessionTime) }}</p>
            <span>{{ item.tierName }}<i v-if="item.seatArea"> · {{ item.seatArea }}</i></span>
          </div>

          <span class="order-item__status" :class="statusClass(item.order.status)">
            {{ orderStatusText(item.order.status) }}
          </span>
        </div>

        <div class="order-item__foot">
          <div class="order-item__amount">
            <span>{{ item.order.quantity }} 张</span>
            <b class="num">{{ formatMoney(item.order.amount) }}</b>
          </div>

          <div class="order-item__actions">
            <button
              v-if="item.order.status === 0 || item.order.status === 1 || item.order.status === 3"
              class="order-item__action order-item__action--primary"
              type="button"
              @click="openOrder(item.order)"
            >
              {{ item.order.status === 0 ? '去支付' : '查看' }}
            </button>
            <button
              v-else
              class="order-item__action"
              type="button"
              @click="goShow(item.order)"
            >
              重新选票
            </button>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped lang="scss">
.orders {
  padding-bottom: 28px;
}

.orders__refresh {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;

  &:active {
    color: var(--text-1);
  }
}

.orders__tabs {
  display: flex;
  gap: 22px;
  overflow-x: auto;
  padding: 14px 16px 9px;
  border-bottom: 1px solid rgba(42, 42, 54, 0.55);
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.orders__tab {
  position: relative;
  flex: 0 0 auto;
  padding: 0 0 8px;
  border: 0;
  background: none;
  color: var(--text-3);
  font-size: 12px;
  cursor: pointer;

  &.is-active {
    color: var(--text-1);
    font-weight: 700;

    &::after {
      content: '';
      position: absolute;
      bottom: 0;
      left: 50%;
      width: 14px;
      height: 2px;
      border-radius: 2px;
      background: var(--brand);
      transform: translateX(-50%);
    }
  }
}

.orders__list {
  padding: 4px 16px 0;
}

.order-item {
  margin-top: 12px;
  padding: 13px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--bg-card);
}

.order-item__head {
  display: flex;
  gap: 11px;
  cursor: pointer;
}

.order-item__poster {
  display: grid;
  place-items: center;
  width: 60px;
  height: 80px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 8px;
  background: linear-gradient(145deg, #ff3d71, #3a164d);
  color: rgba(255, 255, 255, 0.22);
  font-size: 28px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &.is-fallback {
    color: rgba(255, 255, 255, 0.65);
  }
}

.order-item__info {
  min-width: 0;
  flex: 1;

  h3 {
    display: -webkit-box;
    overflow: hidden;
    margin: 1px 0 0;
    font-size: 13px;
    font-weight: 700;
    letter-spacing: -0.15px;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  p {
    overflow: hidden;
    margin: 6px 0 0;
    color: var(--text-2);
    font-size: 9.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    display: block;
    margin-top: 7px;
    color: var(--text-3);
    font-size: 9px;

    i {
      font-style: normal;
    }
  }
}

.order-item__status {
  flex: 0 0 auto;
  height: 20px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 8.5px;
  font-weight: 700;
  line-height: 20px;

  &.is-pending {
    background: var(--brand-soft);
    color: var(--brand);
  }

  &.is-paid {
    background: rgba(46, 213, 115, 0.1);
    color: var(--success);
  }

  &.is-refunded {
    background: rgba(123, 97, 255, 0.12);
    color: #b7a8ff;
  }

  &.is-closed {
    background: var(--bg-elevated);
    color: var(--text-3);
  }
}

.order-item__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px solid rgba(42, 42, 54, 0.62);
}

.order-item__amount {
  display: flex;
  align-items: baseline;
  gap: 7px;

  span {
    color: var(--text-3);
    font-size: 9px;
  }

  b {
    color: var(--text-1);
    font-size: 14px;
    font-weight: 800;
  }
}

.order-item__actions {
  display: flex;
  gap: 8px;
}

.order-item__action {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  color: var(--text-2);
  font-size: 9.5px;
  font-weight: 600;
  cursor: pointer;

  &--primary {
    border-color: rgba(255, 61, 113, 0.4);
    background: var(--brand-soft);
    color: var(--brand);
  }
}

.orders__skeletons {
  padding: 4px 16px 0;
}

.orders__skeleton {
  height: 132px;
  margin-top: 12px;
  border-radius: 15px;
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
