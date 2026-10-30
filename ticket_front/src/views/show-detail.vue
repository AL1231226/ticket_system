<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getShowDetail, getTierStock } from '@/api/show'
import AppIcon from '@/components/AppIcon.vue'
import Countdown from '@/components/Countdown.vue'
import StateView from '@/components/StateView.vue'
import { useCatalogStore } from '@/stores/catalog'
import { useWaitlistStore } from '@/stores/waitlist'
import { formatDate, formatMoney, formatSessionDate, formatWant, splitImages } from '@/utils/format'
import { TOKEN_KEY } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const catalogStore = useCatalogStore()
const waitlistStore = useWaitlistStore()

const show = ref(null)
const sessions = ref([])
const selectedSessionId = ref(null)
const selectedTierId = ref(null)
const stockMap = ref({})
const loading = ref(true)
const error = ref('')
const heroFailed = ref(false)
const descExpanded = ref(false)

let stockTimer = null
let stockSeq = 0

const selectedSession = computed(
  () => sessions.value.find((session) => session.id === selectedSessionId.value) || null,
)
const tiers = computed(() => selectedSession.value?.tiers || [])
const selectedTier = computed(
  () => tiers.value.find((tier) => tier.id === selectedTierId.value) || null,
)
const categoryName = computed(() => catalogStore.categoryName(show.value?.categoryId))
const gallery = computed(() => splitImages(show.value?.images))
const lowestStock = computed(() => {
  const list = tiers.value.map((tier) => stockOf(tier))
  return list.length ? Math.min(...list) : 0
})
const allTiersSoldOut = computed(
  () => tiers.value.length > 0 && tiers.value.every((tier) => stockOf(tier) <= 0),
)
const hasFutureOnSale = computed(() => {
  const session = selectedSession.value
  return (
    session?.status === 0 &&
    Boolean(session.onSaleTime) &&
    new Date(session.onSaleTime).getTime() > Date.now()
  )
})
const canBuy = computed(() => {
  if (!selectedTier.value || !selectedSession.value) return false
  if (!isOnSale(selectedSession.value)) return false
  return stockOf(selectedTier.value) > 0
})

/** 候补行：详情页按当前选中的票档取状态。 */
const waitlistEntry = computed(() => waitlistStore.byTier(selectedTierId.value))
const waitlistStatus = computed(() =>
  waitlistEntry.value ? Number(waitlistEntry.value.waitlist?.status) : null,
)

/** 已售罄但还能候补的场次：1/2 已开票，3 已售罄。 */
function isWaitlistSession(session) {
  if (!session) return false
  const status = Number(session.status)
  if (status !== 1 && status !== 2 && status !== 3) return false
  if (session.onSaleTime && new Date(session.onSaleTime).getTime() > Date.now()) return false
  return !session.sessionTime || new Date(session.sessionTime).getTime() > Date.now()
}

const waitlistAction = computed(() => {
  if (!selectedTier.value || !selectedSession.value) return null
  if (!isWaitlistSession(selectedSession.value)) return null
  if (stockOf(selectedTier.value) > 0) return null

  const orderId = waitlistEntry.value?.waitlist?.orderId
  if (waitlistStatus.value === 0) {
    const pos = waitlistEntry.value?.position
    return {
      kind: 'waitlist-state',
      label: pos ? `已在候补中 · 第 ${pos} 位` : '已在候补中 · 查看位置',
      to: '/waitlists',
    }
  }
  if (waitlistStatus.value === 1) {
    return {
      kind: 'waitlist-state',
      label: '已获得补位 · 去支付',
      to: orderId ? `/order/${orderId}` : '/waitlists',
    }
  }
  if (waitlistStatus.value === 2) {
    return {
      kind: 'waitlist-state',
      label: '已购票 · 查看订单',
      to: orderId ? `/order/${orderId}` : '/waitlists',
    }
  }
  return { kind: 'waitlist-join', label: '加入候补', to: '' }
})

const primaryAction = computed(() => {
  if (canBuy.value) return { kind: 'seckill', label: '立即抢票', to: '' }
  if (waitlistAction.value) return waitlistAction.value
  if (!selectedSession.value) return { kind: 'disabled', label: '请选择场次', to: '' }
  if (!isWaitlistSession(selectedSession.value)) {
    return {
      kind: 'disabled',
      label: selectedSession.value.status === 0 ? '未到开票时间' : '本场暂不可抢票',
      to: '',
    }
  }
  if (!selectedTier.value) return { kind: 'disabled', label: '请选择票档', to: '' }
  return { kind: 'disabled', label: '已售罄', to: '' }
})

const heroFallback = computed(() => {
  const palettes = [
    ['#ff3d71', '#3a164d'],
    ['#00d2ff', '#14235c'],
    ['#ffcb47', '#5c2c0b'],
    ['#2ed573', '#0a3325'],
    ['#e056fd', '#241044'],
  ]
  const id = Number(show.value?.id || 0)
  const p = palettes[id % palettes.length]
  return { background: `linear-gradient(145deg, ${p[0]}, ${p[1]})` }
})

onMounted(async () => {
  catalogStore.ensureCategories().catch(() => {})
  try {
    const detail = await getShowDetail(route.params.id)
    show.value = detail.show
    sessions.value = detail.sessions || []

    // 已登录才需要候补状态；每次进详情都强制刷新，避免补位后状态滞后
    if (localStorage.getItem(TOKEN_KEY)) {
      waitlistStore.loadMine(true).catch(() => {})
    }

    const preferred =
      sessions.value.find((session) => session.status === 1 || session.status === 2) ||
      sessions.value.find((session) => session.status !== 4 && session.status !== 5)
    if (preferred) {
      selectSession(preferred.id)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})

watch(selectedSessionId, () => {
  initStock()
  restartStockPolling()
})

onBeforeUnmount(() => {
  stockSeq += 1
  stopStockPolling()
})

function isOnSale(session) {
  return session?.status === 1 || session?.status === 2
}

function stockOf(tier) {
  const value = stockMap.value[tier.id]
  return typeof value === 'number' ? value : Number(tier.stock || 0)
}

function stockText(tier) {
  const stock = stockOf(tier)
  if (stock <= 0) {
    return isWaitlistSession(selectedSession.value) ? '已售罄 · 可候补' : '已售罄'
  }
  if (stock <= 20) return `仅剩 ${stock} 张`
  return '余票充足'
}

function stockClass(tier) {
  const stock = stockOf(tier)
  if (stock <= 0) return 'is-out'
  if (stock <= 20) return 'is-low'
  return 'is-ok'
}

function selectSession(id) {
  selectedSessionId.value = id
  selectedTierId.value = null
}

function selectTier(tier) {
  // 售罄票档也要能选中：选中后底部会出现「加入候补」
  if (!isWaitlistSession(selectedSession.value)) return
  selectedTierId.value = tier.id
}

function initStock() {
  const next = {}
  for (const tier of tiers.value) {
    next[tier.id] = Number(tier.stock || 0)
  }
  stockMap.value = next
}

function restartStockPolling() {
  stopStockPolling()
  if (!tiers.value.length) return
  refreshStock()
  stockTimer = setInterval(refreshStock, 5000)
}

function stopStockPolling() {
  if (stockTimer) {
    clearInterval(stockTimer)
    stockTimer = null
  }
}

async function refreshStock() {
  const session = selectedSession.value
  if (!session?.tiers?.length || document.hidden) return
  const seq = ++stockSeq
  try {
    const values = await Promise.all(session.tiers.map((tier) => getTierStock(tier.id)))
    if (seq !== stockSeq) return
    const next = { ...stockMap.value }
    session.tiers.forEach((tier, index) => {
      const value = Number(values[index])
      if (Number.isFinite(value)) {
        next[tier.id] = value
      }
    })
    stockMap.value = next
  } catch {
    // 余票轮询是锦上添花，失败时保留上一次的值，不打断用户浏览
  }
}

function goBuy() {
  if (!canBuy.value) return
  const target = `/seckill/${selectedTier.value.id}?showId=${show.value.id}&sessionId=${selectedSession.value.id}`
  if (!localStorage.getItem(TOKEN_KEY)) {
    router.push({ name: 'login', query: { redirect: target } })
    return
  }
  router.push(target)
}

function openWaitlist() {
  if (!selectedTier.value || !selectedSession.value) return
  const target = `/waitlist/${selectedTier.value.id}?showId=${show.value.id}&sessionId=${selectedSession.value.id}`
  if (!localStorage.getItem(TOKEN_KEY)) {
    router.push({ name: 'login', query: { redirect: target } })
    return
  }
  router.push(target)
}

function onPrimaryAction() {
  const action = primaryAction.value
  if (!action || action.kind === 'disabled') return
  if (action.kind === 'seckill') {
    goBuy()
    return
  }
  if (action.kind === 'waitlist-join') {
    openWaitlist()
    return
  }
  if (action.to) router.push(action.to)
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/home')
  }
}

function onGalleryError(event) {
  event.target.style.display = 'none'
}
</script>

<template>
  <div class="detail page">
    <button class="detail__back" type="button" aria-label="返回" @click="goBack">
      <AppIcon name="arrow-left" :size="20" />
    </button>

    <div v-if="loading" class="detail__loading">
      <div class="detail__hero-skeleton" />
      <StateView title="正在加载演出" description="马上就好" />
    </div>

    <StateView
      v-else-if="error"
      title="演出加载失败"
      :description="error"
      action-text="重新加载"
      @action="$router.go(0)"
    />

    <template v-else-if="show">
      <header class="detail__hero" :style="heroFallback">
        <img
          v-if="show.poster && !heroFailed"
          :src="show.poster"
          :alt="show.name"
          @error="heroFailed = true"
        />
        <span v-if="heroFailed || !show.poster" class="detail__hero-initial">
          {{ (show.name || '演').slice(0, 1) }}
        </span>
        <div class="detail__hero-shade" />
        <div class="detail__hero-content">
          <div class="detail__tags">
            <span v-if="categoryName">{{ categoryName }}</span>
            <span>{{ show.city }}</span>
            <span>{{ sessions.length }} 场</span>
          </div>
          <h1>{{ show.name }}</h1>
          <p>{{ show.artist || '现场演出' }}</p>
        </div>
      </header>

      <main class="detail__body">
        <section v-if="sessions.length" class="detail__sessions">
          <div class="detail__section-head">
            <h2>选择场次</h2>
            <span>{{ formatWant(show.wantCount) }}</span>
          </div>
          <div class="session-row">
            <button
              v-for="session in sessions"
              :key="session.id"
              type="button"
              class="session-pill"
              :class="{
                'is-active': selectedSessionId === session.id,
                'is-disabled': session.status === 4 || session.status === 5,
              }"
              :disabled="session.status === 4 || session.status === 5"
              @click="selectSession(session.id)"
            >
              <span>{{ session.sessionName }}</span>
              <small>{{ formatDate(session.sessionTime) }}</small>
              <em v-if="session.status === 0">未开票</em>
              <em v-else-if="session.status === 2">已开票</em>
            </button>
          </div>
        </section>

        <section v-if="selectedSession" class="detail__status">
          <template v-if="!isOnSale(selectedSession)">
            <div v-if="isWaitlistSession(selectedSession)" class="status-card status-card--waitlist">
              <div>
                <span class="status-card__label">本场已售罄</span>
                <p class="status-card__note">
                  候补通道开放：有人退票时按排队顺序补位，补位后 10 分钟内支付。
                </p>
              </div>
              <span class="status-card__waitlist">候补中</span>
            </div>

            <div v-else class="status-card">
              <div class="status-card__main">
                <span class="status-card__label">
                  {{ selectedSession.status === 0 ? '距离开票' : '本场次当前不可抢票' }}
                </span>
                <Countdown
                  v-if="hasFutureOnSale"
                  :target="selectedSession.onSaleTime"
                  label=""
                  compact
                />
                <p v-else class="status-card__note">
                  {{ selectedSession.status === 0 ? '开票时间已过，等待场次状态更新。' : '本场次已结束或已取消。' }}
                </p>
              </div>
              <span class="status-card__date">
                {{ formatSessionDate(selectedSession.onSaleTime) }}
              </span>
            </div>
          </template>

          <div v-else class="status-card status-card--live">
            <div>
              <span class="status-card__label">本场正在抢票</span>
              <p class="status-card__note">
                <template v-if="allTiersSoldOut">本场票档已售罄，可选择票档加入候补。</template>
                <template v-else-if="lowestStock <= 0">
                  部分票档已售罄，可选择其他票档或加入候补。
                </template>
                <template v-else>
                  当前最低余票 {{ lowestStock }} 张 · 每账号本场限购 {{ selectedSession.purchaseLimit }} 张
                </template>
              </p>
            </div>
            <span class="status-card__live">
              <i /> 热抢中
            </span>
          </div>
        </section>

        <section v-if="selectedSession" class="detail__tiers">
          <div class="detail__section-head">
            <h2>选择票档</h2>
            <span>余票每 5 秒更新</span>
          </div>

          <div v-if="!tiers.length" class="detail__empty-tiers">本场暂无可售票档</div>

          <button
            v-for="tier in tiers"
            :key="tier.id"
            type="button"
            class="tier-row"
            :class="{
              'is-active': selectedTierId === tier.id,
              'is-sold-out': stockOf(tier) <= 0,
              'is-waitlist-able': stockOf(tier) <= 0 && isWaitlistSession(selectedSession),
            }"
            :disabled="!isWaitlistSession(selectedSession)"
            @click="selectTier(tier)"
          >
            <div class="tier-row__info">
              <h3>{{ tier.name }}</h3>
              <p>{{ tier.seatArea }} · {{ tier.seatType === 0 ? '对号入座' : '站票/通票' }}</p>
              <span>每单限 {{ tier.maxPerOrder }} 张</span>
            </div>
            <div class="tier-row__right">
              <strong>{{ formatMoney(tier.price) }}</strong>
              <span :class="stockClass(tier)">{{ stockText(tier) }}</span>
            </div>
            <span v-if="selectedTierId === tier.id" class="tier-row__check">
              <AppIcon name="check" :size="14" />
            </span>
          </button>
        </section>

        <section class="detail__about">
          <div class="detail__section-head">
            <h2>关于演出</h2>
          </div>
          <p class="detail__desc" :class="{ 'is-expanded': descExpanded }">
            {{ show.description || '暂无演出介绍。' }}
          </p>
          <button
            v-if="(show.description || '').length > 90"
            class="detail__expand"
            type="button"
            @click="descExpanded = !descExpanded"
          >
            {{ descExpanded ? '收起' : '展开全部' }}
          </button>
        </section>

        <section v-if="gallery.length" class="detail__gallery">
          <div class="detail__section-head">
            <h2>现场图</h2>
          </div>
          <div class="detail__gallery-track">
            <img
              v-for="(image, index) in gallery"
              :key="index"
              :src="image"
              alt=""
              loading="lazy"
              @error="onGalleryError"
            />
          </div>
        </section>
      </main>

      <div class="detail__action">
        <div class="detail__want">
          <AppIcon name="heart" :size="18" />
          <span>{{ formatWant(show.wantCount) }}</span>
        </div>
        <button
          class="detail__buy"
          :class="{
            'is-waitlist':
              primaryAction.kind === 'waitlist-join' || primaryAction.kind === 'waitlist-state',
          }"
          type="button"
          :disabled="primaryAction.kind === 'disabled'"
          @click="onPrimaryAction"
        >
          {{ primaryAction.label }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.detail {
  position: relative;
  padding-bottom: calc(78px + env(safe-area-inset-bottom));
}

.detail__back {
  position: fixed;
  top: calc(12px + env(safe-area-inset-top));
  left: 50%;
  z-index: 30;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-left: -224px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 50%;
  background: rgba(7, 7, 10, 0.46);
  color: #fff;
  backdrop-filter: blur(10px);
  cursor: pointer;

  @media (max-width: 480px) {
    // 窄屏时按钮贴左边，不能再用 left:50% + margin 偏移，否则会跑到屏幕中间
    left: 14px;
    margin-left: 0;
  }
}

.detail__hero {
  position: relative;
  height: 262px;
  overflow: hidden;
  background: var(--bg-card);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 30%;
  }
}

.detail__hero-initial {
  position: absolute;
  right: 22px;
  bottom: 48px;
  color: rgba(255, 255, 255, 0.14);
  font-size: 130px;
  font-weight: 800;
  letter-spacing: -10px;
}

.detail__hero-shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(7, 7, 10, 0.36), transparent 32%),
    linear-gradient(0deg, rgba(15, 15, 20, 0.96) 0%, rgba(15, 15, 20, 0.28) 48%, transparent 72%);
}

.detail__hero-content {
  position: absolute;
  right: 18px;
  bottom: 20px;
  left: 18px;

  h1 {
    max-width: 340px;
    margin: 10px 0 0;
    color: #fff;
    font-size: 23px;
    font-weight: 800;
    letter-spacing: -0.5px;
    line-height: 1.22;
    text-wrap: balance;
  }

  p {
    margin: 8px 0 0;
    color: rgba(255, 255, 255, 0.58);
    font-size: 11px;
  }
}

.detail__tags {
  display: flex;
  gap: 6px;

  span {
    padding: 3px 8px;
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: 999px;
    background: rgba(7, 7, 10, 0.28);
    color: rgba(255, 255, 255, 0.76);
    font-size: 9px;
    backdrop-filter: blur(6px);
  }
}

.detail__body {
  padding: 0 16px;
}

.detail__section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 22px 0 10px;

  h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
    letter-spacing: -0.2px;
  }

  span {
    color: var(--text-3);
    font-size: 10px;
  }
}

.session-row {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.session-pill {
  position: relative;
  min-width: 96px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: var(--bg-elevated);
  color: var(--text-2);
  text-align: left;
  cursor: pointer;

  span {
    display: block;
    color: var(--text-1);
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;
  }

  small {
    display: block;
    margin-top: 4px;
    color: var(--text-3);
    font-size: 9px;
  }

  em {
    display: inline-block;
    margin-top: 6px;
    padding: 1px 5px;
    border-radius: 4px;
    background: rgba(255, 203, 71, 0.12);
    color: var(--accent);
    font-size: 8px;
    font-style: normal;
  }

  &.is-active {
    border-color: rgba(255, 61, 113, 0.55);
    background: var(--brand-soft);

    small,
    em {
      color: #ff9ab4;
    }
  }

  &.is-disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.status-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 15px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--bg-card);
}

.status-card__main {
  min-width: 0;
}

.status-card__label {
  display: block;
  color: var(--text-3);
  font-size: 9px;
  letter-spacing: 0.5px;
}

.status-card__note {
  margin: 5px 0 0;
  color: var(--text-2);
  font-size: 10px;
  line-height: 1.6;
}

.status-card__date {
  flex: 0 0 auto;
  color: var(--text-3);
  font-size: 9px;
  text-align: right;
}

.status-card--live {
  border-color: rgba(46, 213, 115, 0.22);
  background: rgba(46, 213, 115, 0.06);
}

.status-card--waitlist {
  border-color: rgba(123, 97, 255, 0.28);
  background: var(--brand-alt-soft);
}

.status-card__waitlist {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(123, 97, 255, 0.18);
  color: #c8bcff;
  font-size: 9px;
  font-weight: 700;
}

.status-card__live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  color: var(--success);
  font-size: 10px;
  font-weight: 700;

  i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--success);
    box-shadow: 0 0 0 4px rgba(46, 213, 115, 0.1);
    animation: pulse 1.6s ease-in-out infinite;
  }
}

.tier-row {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 14px 0;
  border: 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.65);
  background: transparent;
  text-align: left;
  cursor: pointer;

  &.is-active {
    &::before {
      content: '';
      position: absolute;
      top: 8px;
      bottom: 8px;
      left: -16px;
      width: 2px;
      border-radius: 2px;
      background: var(--brand);
    }
  }

  &.is-sold-out {
    opacity: 0.55;
  }

  &.is-waitlist-able {
    opacity: 0.9;
    cursor: pointer;

    .tier-row__right span {
      color: #b7a8ff;
    }
  }
}

.tier-row__info {
  min-width: 0;

  h3 {
    margin: 0;
    color: var(--text-1);
    font-size: 13px;
    font-weight: 700;
  }

  p {
    margin: 5px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
  }

  > span {
    display: block;
    margin-top: 5px;
    color: var(--text-3);
    font-size: 9px;
  }
}

.tier-row__right {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 5px;
  flex: 0 0 auto;

  strong {
    color: var(--brand);
    font-size: 15px;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }

  span {
    font-size: 9px;

    &.is-ok {
      color: var(--accent);
    }

    &.is-low {
      color: var(--brand);
    }

    &.is-out {
      color: var(--text-3);
    }
  }
}

.tier-row__check {
  position: absolute;
  top: 50%;
  right: -4px;
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  margin-top: -9px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  box-shadow: 0 4px 10px rgba(255, 61, 113, 0.4);
}

.detail__empty-tiers {
  padding: 18px 0;
  color: var(--text-3);
  font-size: 11px;
}

.detail__desc {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.85;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;

  &.is-expanded {
    display: block;
    -webkit-line-clamp: unset;
  }
}

.detail__expand {
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand);
  font-size: 10px;
  cursor: pointer;
}

.detail__gallery-track {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  img {
    width: 210px;
    height: 132px;
    flex: 0 0 auto;
    border-radius: 11px;
    object-fit: cover;
  }
}

.detail__action {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  max-width: 480px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.detail__want {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 3px;
  width: 54px;
  flex: 0 0 auto;
  color: var(--text-2);

  span {
    font-size: 8px;
    white-space: nowrap;
  }
}

.detail__buy {
  flex: 1;
  height: 46px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.3);
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.4px;
  cursor: pointer;

  &:disabled {
    background: var(--bg-elevated);
    box-shadow: none;
    color: var(--text-3);
    cursor: not-allowed;
  }

  &.is-waitlist:not(:disabled) {
    background: linear-gradient(96deg, #7b61ff, #a78bfa);
    box-shadow: 0 8px 22px rgba(123, 97, 255, 0.3);
  }

  &:active:not(:disabled) {
    transform: scale(0.99);
  }
}

.detail__loading {
  min-height: 100vh;
}

.detail__hero-skeleton {
  height: 262px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}
</style>
