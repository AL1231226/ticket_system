<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getShowDetail } from '@/api/show'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import { useWaitlistStore } from '@/stores/waitlist'
import { formatMoney, formatSessionDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const waitlistStore = useWaitlistStore()

const show = ref(null)
const session = ref(null)
const tier = ref(null)
const quantity = ref(1)
const loading = ref(true)
const error = ref('')
const submitting = ref(false)
const posterFailed = ref(false)

const tierId = computed(() => Number(route.params.tierId))
const maxQuantity = computed(() => Math.max(1, Number(tier.value?.maxPerOrder || 1)))
const total = computed(() => Number(tier.value?.price || 0) * quantity.value)
const entry = computed(() => waitlistStore.byTier(tierId.value))
const entryStatus = computed(() =>
  entry.value ? Number(entry.value.waitlist?.status) : null,
)
const entryBlocks = computed(() =>
  entryStatus.value === 0 || entryStatus.value === 1 || entryStatus.value === 2,
)
const canJoin = computed(() => {
  if (!session.value) return false
  const status = Number(session.value.status)
  if (status !== 1 && status !== 2 && status !== 3) return false
  if (session.value.onSaleTime && new Date(session.value.onSaleTime).getTime() > Date.now()) {
    return false
  }
  return !session.value.sessionTime || new Date(session.value.sessionTime).getTime() > Date.now()
})
const canSubmit = computed(
  () => Boolean(tier.value) && canJoin.value && !entryBlocks.value && !submitting.value,
)
const entryAction = computed(() => {
  if (entryStatus.value === 0) return { text: '去我的候补看位置', to: '/waitlists' }
  if (entryStatus.value === 1) return { text: '去支付补位订单', to: `/order/${entry.value.waitlist.orderId}` }
  return { text: '查看订单', to: `/order/${entry.value?.waitlist?.orderId || ''}` }
})

onMounted(async () => {
  const showId = route.query.showId
  if (!showId) {
    error.value = '缺少演出信息，请返回详情页重新选择'
    loading.value = false
    return
  }

  try {
    const detail = await getShowDetail(showId)
    show.value = detail.show
    const sessionId = Number(route.query.sessionId)
    const sessions = detail.sessions || []
    const matchedSession =
      sessions.find((item) => item.id === sessionId) ||
      sessions.find((item) => (item.tiers || []).some((itemTier) => itemTier.id === tierId.value))
    const matchedTier = matchedSession?.tiers?.find((item) => item.id === tierId.value)

    if (!matchedSession || !matchedTier) {
      error.value = '票档不存在或已下架，请返回详情页重新选择'
      return
    }

    session.value = matchedSession
    tier.value = matchedTier
    await waitlistStore.loadMine(true).catch(() => {})
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})

function changeQuantity(delta) {
  const next = quantity.value + delta
  if (next < 1 || next > maxQuantity.value) return
  quantity.value = next
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    await waitlistStore.join({ tierId: tierId.value, quantity: quantity.value })
    router.replace('/waitlists')
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="wlj page">
    <AppBar title="加入候补" />

    <div v-if="loading" class="wlj__loading">
      <StateView title="正在准备候补信息" description="马上就好" />
    </div>

    <StateView
      v-else-if="!tier"
      title="无法加入候补"
      :description="error || '票档信息不完整'"
      action-text="返回详情"
      @action="$router.back()"
    />

    <template v-else>
      <main class="wlj__body">
        <section class="ticket-card">
          <div class="ticket-card__poster" :class="{ 'is-fallback': posterFailed || !show?.poster }">
            <img
              v-if="show?.poster && !posterFailed"
              :src="show.poster"
              :alt="show.name"
              @error="posterFailed = true"
            />
            <span v-else>{{ (show?.name || '演').slice(0, 1) }}</span>
          </div>

          <div class="ticket-card__info">
            <h1>{{ show?.name }}</h1>
            <p>{{ show?.artist || '现场演出' }} · {{ show?.city }}</p>
            <span>{{ formatSessionDate(session.sessionTime) }}</span>
          </div>
        </section>

        <section class="order-card">
          <div class="order-card__row">
            <span class="order-card__label">票档</span>
            <strong>{{ tier.name }}</strong>
          </div>
          <div class="order-card__row">
            <span class="order-card__label">座位区</span>
            <span>{{ tier.seatArea }} · {{ tier.seatType === 0 ? '对号入座' : '站票/通票' }}</span>
          </div>
          <div class="order-card__row">
            <span class="order-card__label">单价</span>
            <span class="order-card__price">{{ formatMoney(tier.price) }}</span>
          </div>
          <div class="order-card__row order-card__row--quantity">
            <div>
              <span class="order-card__label">候补数量</span>
              <small>每单限 {{ maxQuantity }} 张</small>
            </div>
            <div class="stepper">
              <button
                type="button"
                aria-label="减少数量"
                :disabled="quantity <= 1 || submitting"
                @click="changeQuantity(-1)"
              >
                <AppIcon name="minus" :size="14" />
              </button>
              <b class="num">{{ quantity }}</b>
              <button
                type="button"
                aria-label="增加数量"
                :disabled="quantity >= maxQuantity || submitting"
                @click="changeQuantity(1)"
              >
                <AppIcon name="plus" :size="14" />
              </button>
            </div>
          </div>
        </section>

        <section class="wlj__tip">
          <AppIcon name="hourglass" :size="16" />
          <div>
            <h2>候补说明</h2>
            <p>有人退票时按排队顺序补位。补位成功后会生成一笔待支付订单，请在 10 分钟内完成支付；超时未付，票会顺延给下一位。</p>
          </div>
        </section>

        <div v-if="entryBlocks" class="wlj__existing">
          <p>
            <template v-if="entryStatus === 0">
              你已在候补中，当前第 {{ entry.position ?? '—' }} 位。
            </template>
            <template v-else-if="entryStatus === 1">你已经获得补位，请尽快支付。</template>
            <template v-else>你已经购买过该票档。</template>
          </p>
          <button type="button" @click="$router.push(entryAction.to)">
            {{ entryAction.text }}
          </button>
        </div>

        <div v-else-if="!canJoin" class="wlj__warning">本场次当前不可候补，请返回详情页查看开票时间。</div>
        <div v-else-if="error" class="wlj__error">{{ error }}</div>
      </main>

      <footer class="wlj__action">
        <div class="wlj__total">
          <span>候补 {{ quantity }} 张</span>
          <strong class="num">{{ formatMoney(total) }}</strong>
        </div>
        <button class="wlj__submit" type="button" :disabled="!canSubmit" @click="submit">
          {{ submitting ? '提交中…' : entryBlocks ? '已在候补' : '确认加入候补' }}
        </button>
      </footer>
    </template>
  </div>
</template>

<style scoped lang="scss">
.wlj {
  padding-bottom: calc(84px + env(safe-area-inset-bottom));
}

.wlj__loading {
  min-height: 70vh;
}

.wlj__body {
  padding: 14px 16px 0;
}

.ticket-card {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.ticket-card__poster {
  display: grid;
  place-items: center;
  width: 58px;
  height: 76px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 9px;
  background: linear-gradient(145deg, #7b61ff, #2a1d55);
  color: rgba(255, 255, 255, 0.24);
  font-size: 28px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &.is-fallback {
    color: rgba(255, 255, 255, 0.7);
  }
}

.ticket-card__info {
  min-width: 0;

  h1 {
    display: -webkit-box;
    overflow: hidden;
    margin: 1px 0 0;
    font-size: 13.5px;
    font-weight: 700;
    letter-spacing: -0.2px;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  p {
    overflow: hidden;
    margin: 6px 0 0;
    color: var(--text-2);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    display: block;
    margin-top: 7px;
    color: var(--text-3);
    font-size: 9.5px;
  }
}

.order-card {
  margin-top: 14px;
  padding: 2px 15px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--bg-card);
}

.order-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.65);
  color: var(--text-1);
  font-size: 12px;

  &:last-child {
    border-bottom: 0;
  }

  strong {
    font-size: 12.5px;
    font-weight: 700;
  }

  small {
    display: block;
    margin-top: 4px;
    color: var(--text-3);
    font-size: 9px;
  }
}

.order-card__label {
  color: var(--text-2);
}

.order-card__price {
  color: #b7a8ff;
  font-size: 14px;
  font-weight: 800;
}

.stepper {
  display: flex;
  align-items: center;
  overflow: hidden;
  border-radius: 8px;
  background: var(--bg-elevated);

  button {
    display: grid;
    place-items: center;
    width: 30px;
    height: 28px;
    border: 0;
    background: transparent;
    color: var(--text-2);
    cursor: pointer;

    &:disabled {
      color: var(--text-3);
      cursor: not-allowed;
    }
  }

  b {
    display: grid;
    place-items: center;
    width: 34px;
    color: var(--text-1);
    font-size: 12px;
    font-weight: 700;
  }
}

.wlj__tip {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  padding: 13px 14px;
  border: 1px solid rgba(123, 97, 255, 0.22);
  border-radius: 13px;
  background: var(--brand-alt-soft);
  color: #b7a8ff;

  h2 {
    margin: 0;
    color: #d4caff;
    font-size: 11px;
    font-weight: 700;
  }

  p {
    margin: 5px 0 0;
    color: var(--text-2);
    font-size: 9.5px;
    line-height: 1.7;
  }
}

.wlj__existing {
  margin-top: 14px;
  padding: 13px 14px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: var(--bg-card);

  p {
    margin: 0;
    color: var(--text-2);
    font-size: 10.5px;
    line-height: 1.6;
  }

  button {
    margin-top: 9px;
    padding: 7px 12px;
    border: 1px solid rgba(123, 97, 255, 0.35);
    border-radius: 8px;
    background: transparent;
    color: #b7a8ff;
    font-size: 10px;
    font-weight: 700;
    cursor: pointer;
  }
}

.wlj__warning,
.wlj__error {
  margin-top: 14px;
  font-size: 10px;
  line-height: 1.6;
}

.wlj__warning {
  color: var(--text-3);
}

.wlj__error {
  color: #ff9aa3;
}

.wlj__action {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 480px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.wlj__total {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 0 0 auto;

  span {
    color: var(--text-3);
    font-size: 8.5px;
  }

  strong {
    color: #b7a8ff;
    font-size: 17px;
    font-weight: 800;
  }
}

.wlj__submit {
  flex: 1;
  height: 46px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(96deg, #7b61ff, #a78bfa);
  box-shadow: 0 8px 22px rgba(123, 97, 255, 0.3);
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

  &:active:not(:disabled) {
    transform: scale(0.99);
  }
}
</style>
