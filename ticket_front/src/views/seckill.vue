<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getShowDetail } from '@/api/show'
import { seckill } from '@/api/order'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import { formatMoney, formatSessionDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const show = ref(null)
const session = ref(null)
const tier = ref(null)
const quantity = ref(1)
const loading = ref(true)
const error = ref('')
const submitting = ref(false)
const posterFailed = ref(false)

const maxQuantity = computed(() => Math.max(1, Number(tier.value?.maxPerOrder || 1)))
const total = computed(() => Number(tier.value?.price || 0) * quantity.value)
const onSale = computed(() => session.value?.status === 1 || session.value?.status === 2)
const soldOut = computed(() => Number(tier.value?.stock || 0) <= 0)
const canSubmit = computed(() => Boolean(tier.value) && onSale.value && !soldOut.value && !submitting.value)

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
    const tierId = Number(route.params.tierId)
    const sessionId = Number(route.query.sessionId)
    const sessions = detail.sessions || []
    const matchedSession =
      sessions.find((item) => item.id === sessionId) ||
      sessions.find((item) => (item.tiers || []).some((itemTier) => itemTier.id === tierId))
    const matchedTier = matchedSession?.tiers?.find((item) => item.id === tierId)

    if (!matchedSession || !matchedTier) {
      error.value = '票档不存在或已下架，请返回详情页重新选择'
      return
    }

    session.value = matchedSession
    tier.value = matchedTier
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
    const orderId = await seckill({
      tierId: tier.value.id,
      quantity: quantity.value,
    })
    router.replace({ name: 'queue', params: { orderId } })
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="seckill page">
    <AppBar title="确认订单" />

    <div v-if="loading" class="seckill__loading">
      <StateView title="正在准备票档" description="马上就好" />
    </div>

    <StateView
      v-else-if="!tier"
      title="无法确认订单"
      :description="error || '票档信息不完整'"
      action-text="返回详情"
      @action="$router.back()"
    />

    <template v-else>
      <main class="seckill__body">
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
              <span class="order-card__label">购买数量</span>
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

        <div class="seckill__limit">
          <AppIcon name="users" :size="15" />
          <span>本场每账号限购 {{ session.purchaseLimit }} 张，支付前需选择观演人，一证一票。</span>
        </div>

        <div v-if="!onSale" class="seckill__warning">本场当前不可抢票，请返回详情页查看开票时间。</div>
        <div v-else-if="soldOut" class="seckill__warning">这个票档已经售罄，可以返回选择其他票档。</div>
        <div v-else-if="error" class="seckill__error">{{ error }}</div>
      </main>

      <footer class="seckill__action">
        <div class="seckill__total">
          <span>合计 {{ quantity }} 张</span>
          <strong class="num">{{ formatMoney(total) }}</strong>
        </div>
        <button class="seckill__submit" type="button" :disabled="!canSubmit" @click="submit">
          {{ submitting ? '正在锁定…' : onSale ? (soldOut ? '已售罄' : '立即抢票') : '未开票' }}
        </button>
      </footer>
    </template>
  </div>
</template>

<style scoped lang="scss">
.seckill {
  padding-bottom: calc(84px + env(safe-area-inset-bottom));
}

.seckill__loading {
  min-height: 70vh;
}

.seckill__body {
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
  background: linear-gradient(145deg, #ff3d71, #3a164d);
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

  &--quantity {
    align-items: center;
  }
}

.order-card__label {
  color: var(--text-2);
}

.order-card__price {
  color: var(--brand);
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
    height: 28px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--text-1);
    font-size: 12.5px;
  }
}

.seckill__limit {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 11px 13px;
  border: 1px solid rgba(123, 97, 255, 0.2);
  border-radius: 11px;
  background: rgba(123, 97, 255, 0.07);
  color: #b7a8ff;
  font-size: 9.5px;
  line-height: 1.6;

  svg {
    flex: 0 0 auto;
    margin-top: 1px;
  }
}

.seckill__warning,
.seckill__error {
  margin-top: 12px;
  padding: 10px 13px;
  border-radius: 10px;
  font-size: 10.5px;
  line-height: 1.6;
}

.seckill__warning {
  border: 1px solid rgba(255, 203, 71, 0.22);
  background: var(--gold-soft);
  color: #ffdf9b;
}

.seckill__error {
  border: 1px solid rgba(255, 71, 87, 0.25);
  background: rgba(255, 71, 87, 0.08);
  color: #ff9aa3;
}

.seckill__action {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  max-width: 480px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.seckill__total {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 96px;

  span {
    color: var(--text-3);
    font-size: 9.5px;
  }

  strong {
    color: var(--brand);
    font-size: 20px;
    font-weight: 800;
    line-height: 1;
  }
}

.seckill__submit {
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
}
</style>
