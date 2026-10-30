<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getAttendees } from '@/api/attendee'
import { bindAttendees, cancelOrder, getOrder, payOrder, refundOrder } from '@/api/order'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import AttendeeForm from '@/components/AttendeeForm.vue'
import AttendeePicker from '@/components/AttendeePicker.vue'
import StateView from '@/components/StateView.vue'
import { useCountdown } from '@/composables/useCountdown'
import { formatDateTime, formatMoney, formatSessionDate, orderStatusText } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const orderId = computed(() => String(route.params.orderId))
const orderVO = ref(null)
const loading = ref(true)
const error = ref('')
const attendees = ref([])
const attendeeError = ref('')
const selectedIds = ref([])
const showAttendeeForm = ref(false)
const paying = ref(false)
const canceling = ref(false)
const refunding = ref(false)
const showCancelDialog = ref(false)
const showRefundDialog = ref(false)
const actionError = ref('')
const posterFailed = ref(false)
const copied = ref(false)

let copyTimer = null

const order = computed(() => orderVO.value?.order || null)
const status = computed(() => Number(order.value?.status ?? -1))
const isPending = computed(() => status.value === 0)
const required = computed(() => Number(order.value?.quantity || 1))
const selectedCount = computed(() => selectedIds.value.length)
const expireTarget = computed(() => order.value?.expireTime || '')
const { remaining, text: countdownText } = useCountdown(expireTarget)
const isExpired = computed(() => isPending.value && remaining.value.expired)
const canPay = computed(
  () => isPending.value && !isExpired.value && selectedCount.value === required.value && !paying.value,
)
const payLabel = computed(() => {
  if (paying.value) return '正在支付…'
  if (isExpired.value) return '订单已超时'
  if (selectedCount.value < required.value) {
    return `还需选择 ${required.value - selectedCount.value} 位观演人`
  }
  return '提交并支付'
})
const statusLabel = computed(() => orderStatusText(status.value))
const showName = computed(() => orderVO.value?.showName || '演出')
const sessionTime = computed(() => orderVO.value?.sessionTime || '')
const tierName = computed(() => orderVO.value?.tierName || '')
const seatArea = computed(() => orderVO.value?.seatArea || '')
const poster = computed(() => orderVO.value?.poster || '')

onMounted(load)
onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await getOrder(orderId.value)
    orderVO.value = data
    if (data.order?.status === 0) {
      await loadAttendees()
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function reloadOrder() {
  const data = await getOrder(orderId.value)
  orderVO.value = data
  if (data.order?.status === 0) {
    await loadAttendees()
  }
}

async function loadAttendees() {
  attendeeError.value = ''
  try {
    const list = await getAttendees()
    attendees.value = Array.isArray(list) ? list : []
    const valid = new Set(attendees.value.map((item) => item.id))
    selectedIds.value = selectedIds.value.filter((id) => valid.has(id))
  } catch (e) {
    attendeeError.value = e.message
  }
}

async function handleAttendeeSaved(attendeeId) {
  await loadAttendees()
  const numericId = Number(attendeeId)
  if (
    Number.isFinite(numericId) &&
    selectedIds.value.length < required.value &&
    !selectedIds.value.includes(numericId)
  ) {
    selectedIds.value = [...selectedIds.value, numericId]
  }
}

async function submitAndPay() {
  if (!canPay.value) return
  paying.value = true
  actionError.value = ''
  try {
    await bindAttendees(orderId.value, selectedIds.value)
    await payOrder(orderId.value)
    await reloadOrder()
  } catch (e) {
    actionError.value = e.message
  } finally {
    paying.value = false
  }
}

function askCancel() {
  actionError.value = ''
  showCancelDialog.value = true
}

async function doCancel() {
  if (canceling.value) return
  canceling.value = true
  actionError.value = ''
  try {
    await cancelOrder(orderId.value)
    showCancelDialog.value = false
    await reloadOrder()
  } catch (e) {
    actionError.value = e.message
  } finally {
    canceling.value = false
  }
}

function askRefund() {
  actionError.value = ''
  showRefundDialog.value = true
}

async function doRefund() {
  if (refunding.value) return
  refunding.value = true
  actionError.value = ''
  try {
    await refundOrder(orderId.value)
    showRefundDialog.value = false
    await reloadOrder()
  } catch (e) {
    actionError.value = e.message
    showRefundDialog.value = false
  } finally {
    refunding.value = false
  }
}

function goWallet() {
  router.push('/wallet')
}

function goHome() {
  router.push('/home')
}

function goOrders() {
  router.push('/orders')
}

function goShow() {
  if (order.value?.showId) {
    router.push(`/show/${order.value.showId}`)
  } else {
    router.push('/home')
  }
}

async function copyOrderId() {
  try {
    await navigator.clipboard?.writeText(orderId.value)
    copied.value = true
    copyTimer = setTimeout(() => {
      copied.value = false
    }, 1600)
  } catch {
    // 剪贴板不可用时静默
  }
}

function closedDescription(value) {
  if (value === 2) return '订单已取消，锁定的库存已经释放。'
  if (value === 3) return '退款已处理，这张票已经失效。'
  if (value === 4) return '超过支付时间，订单已失效，库存已释放。'
  return '订单当前不可支付。'
}
</script>

<template>
  <div class="order page">
    <AppBar :title="status === 1 ? '支付成功' : status === 0 ? '确认订单' : '订单详情'" />

    <div v-if="loading" class="order__loading">
      <StateView title="正在加载订单" description="马上就好" />
    </div>

    <StateView
      v-else-if="!order"
      title="订单不存在"
      :description="error || '订单可能仍在生成，请稍后再试。'"
      action-text="重试"
      @action="load"
    />

    <template v-else>
      <main v-if="status === 1" class="success">
        <div class="success__icon">
          <AppIcon name="check" :size="28" />
        </div>
        <h1>支付成功</h1>
        <p>电子票已经放进票夹，入场时出示动态二维码。</p>

        <div class="success__card">
          <div class="success__row">
            <span>演出</span>
            <b>{{ showName }}</b>
          </div>
          <div class="success__row">
            <span>场次</span>
            <b>{{ formatSessionDate(sessionTime) }}</b>
          </div>
          <div class="success__row">
            <span>票档</span>
            <b>{{ tierName }} · {{ seatArea }}</b>
          </div>
          <div class="success__row">
            <span>数量</span>
            <b>{{ order.quantity }} 张</b>
          </div>
          <div class="success__row success__row--total">
            <span>实付</span>
            <b class="num">{{ formatMoney(order.amount) }}</b>
          </div>
        </div>

        <button class="success__primary" type="button" @click="goWallet">查看电子票</button>
        <button class="success__ghost success__refund" type="button" @click="askRefund">
          申请退款
        </button>
        <button class="success__ghost" type="button" @click="goHome">继续逛演出</button>
        <p v-if="actionError" class="inline-error">{{ actionError }}</p>
      </main>

      <main v-else-if="status !== 0" class="closed">
        <div class="closed__icon">
          <AppIcon :name="status === 3 ? 'refresh' : 'x'" :size="26" />
        </div>
        <h1>{{ statusLabel }}</h1>
        <p>{{ closedDescription(status) }}</p>

        <div class="success__card">
          <div class="success__row">
            <span>演出</span>
            <b>{{ showName }}</b>
          </div>
          <div class="success__row">
            <span>场次</span>
            <b>{{ formatSessionDate(sessionTime) }}</b>
          </div>
          <div class="success__row">
            <span>票档</span>
            <b>{{ tierName }} · {{ seatArea }}</b>
          </div>
          <div class="success__row success__row--total">
            <span>订单金额</span>
            <b class="num">{{ formatMoney(order.amount) }}</b>
          </div>
        </div>

        <button class="success__primary" type="button" @click="goShow">重新选票</button>
        <button class="success__ghost" type="button" @click="goOrders">查看我的订单</button>
      </main>

      <template v-else>
        <main class="pending">
          <section class="order-show">
            <div class="order-show__poster" :class="{ 'is-fallback': posterFailed || !poster }">
              <img
                v-if="poster && !posterFailed"
                :src="poster"
                :alt="showName"
                @error="posterFailed = true"
              />
              <span v-else>{{ showName.slice(0, 1) }}</span>
            </div>

            <div class="order-show__info">
              <h1>{{ showName }}</h1>
              <p>{{ formatSessionDate(sessionTime) }}</p>
              <strong>{{ tierName }}<small>{{ seatArea }}</small></strong>
            </div>
          </section>

          <div class="order-no">
            <span>订单号 {{ orderId }}</span>
            <button type="button" @click="copyOrderId">{{ copied ? '已复制' : '复制' }}</button>
          </div>

          <div class="pay-clock" :class="{ 'is-expired': isExpired }">
            <AppIcon name="clock" :size="18" />
            <div>
              <span>{{ isExpired ? '订单已超时' : '支付剩余' }}</span>
              <b class="num">{{ isExpired ? '00:00:00' : countdownText }}</b>
            </div>
            <em v-if="!isExpired">超时后库存自动释放</em>
            <em v-else>请重新选票</em>
          </div>

          <section class="summary">
            <div class="summary__row">
              <span>购买数量</span>
              <b>{{ order.quantity }} 张</b>
            </div>
            <div class="summary__row">
              <span>单价</span>
              <b class="num">{{ formatMoney(Number(order.amount) / Number(order.quantity || 1)) }}</b>
            </div>
            <div class="summary__row">
              <span>下单时间</span>
              <b>{{ formatDateTime(order.createTime) }}</b>
            </div>
            <div class="summary__row summary__row--total">
              <span>合计</span>
              <b class="num">{{ formatMoney(order.amount) }}</b>
            </div>
          </section>

          <div v-if="attendeeError" class="inline-error">
            {{ attendeeError }}
            <button type="button" @click="loadAttendees">重试</button>
          </div>

          <AttendeePicker
            v-model="selectedIds"
            :attendees="attendees"
            :max="required"
            @add="showAttendeeForm = true"
          />

          <div v-if="actionError" class="inline-error">{{ actionError }}</div>
        </main>

        <footer class="order-action">
          <button class="order-action__cancel" type="button" :disabled="canceling" @click="askCancel">
            {{ canceling ? '取消中…' : '取消订单' }}
          </button>
          <button
            class="order-action__pay"
            type="button"
            :disabled="!canPay"
            @click="submitAndPay"
          >
            {{ payLabel }}
          </button>
        </footer>
      </template>
    </template>

    <AttendeeForm v-model:show="showAttendeeForm" @saved="handleAttendeeSaved" />

    <Teleport to="body">
      <div v-if="showCancelDialog" class="confirm-mask" @click.self="showCancelDialog = false">
        <div class="confirm-card">
          <h3>取消订单？</h3>
          <p>取消后锁定的票会立即释放，订单不可恢复。</p>
          <div class="confirm-card__actions">
            <button type="button" class="ghost" @click="showCancelDialog = false">再想想</button>
            <button type="button" class="danger" :disabled="canceling" @click="doCancel">
              {{ canceling ? '取消中…' : '取消订单' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="showRefundDialog" class="confirm-mask" @click.self="showRefundDialog = false">
        <div class="confirm-card">
          <h3>申请退款？</h3>
          <p>整单退款后电子票立即失效，订单不可恢复。任意一张票已核验时无法退款。</p>
          <div class="confirm-card__actions">
            <button type="button" class="ghost" @click="showRefundDialog = false">再想想</button>
            <button type="button" class="danger" :disabled="refunding" @click="doRefund">
              {{ refunding ? '退款中…' : '确认退款' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.order {
  padding-bottom: calc(82px + env(safe-area-inset-bottom));
}

.order__loading {
  min-height: 70vh;
}

.pending {
  padding: 14px 16px 0;
}

.order-show {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.order-show__poster {
  display: grid;
  place-items: center;
  width: 62px;
  height: 82px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 9px;
  background: linear-gradient(145deg, #ff3d71, #3a164d);
  color: rgba(255, 255, 255, 0.22);
  font-size: 30px;
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

.order-show__info {
  min-width: 0;
  flex: 1;

  h1 {
    display: -webkit-box;
    overflow: hidden;
    margin: 1px 0 0;
    font-size: 14px;
    font-weight: 700;
    letter-spacing: -0.2px;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  p {
    margin: 7px 0 0;
    color: var(--text-2);
    font-size: 10px;
  }

  strong {
    display: block;
    margin-top: 9px;
    color: var(--brand);
    font-size: 12px;
    font-weight: 700;

    small {
      margin-left: 6px;
      color: var(--text-3);
      font-size: 9px;
      font-weight: 400;
    }
  }
}

.order-no {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 9px;
  padding: 0 2px;
  color: var(--text-3);
  font-size: 9px;
  font-variant-numeric: tabular-nums;

  button {
    padding: 2px 6px;
    border: 1px solid var(--line);
    border-radius: 4px;
    background: transparent;
    color: var(--text-3);
    font-size: 8px;
    cursor: pointer;
  }
}

.pay-clock {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid rgba(255, 203, 71, 0.22);
  border-radius: 12px;
  background: var(--gold-soft);
  color: var(--accent);

  > div {
    min-width: 0;
    flex: 1;
  }

  span {
    display: block;
    color: rgba(255, 203, 71, 0.72);
    font-size: 9px;
  }

  b {
    display: block;
    margin-top: 3px;
    color: var(--accent);
    font-size: 17px;
    font-weight: 800;
    letter-spacing: 0.5px;
    line-height: 1;
  }

  em {
    color: rgba(255, 203, 71, 0.55);
    font-size: 8.5px;
    font-style: normal;
  }

  &.is-expired {
    border-color: rgba(255, 71, 87, 0.25);
    background: rgba(255, 71, 87, 0.08);
    color: var(--danger);

    span,
    b {
      color: #ff9aa3;
    }
  }
}

.summary {
  margin-top: 14px;
  padding: 2px 14px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: var(--bg-card);
}

.summary__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.62);
  font-size: 11px;

  &:last-child {
    border-bottom: 0;
  }

  span {
    color: var(--text-3);
  }

  b {
    color: var(--text-1);
    font-weight: 600;
  }

  &--total b {
    color: var(--brand);
    font-size: 16px;
    font-weight: 800;
  }
}

.inline-error {
  margin-top: 12px;
  padding: 9px 11px;
  border: 1px solid rgba(255, 71, 87, 0.25);
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.08);
  color: #ff9aa3;
  font-size: 9.5px;
  line-height: 1.5;

  button {
    margin-left: 6px;
    padding: 0;
    border: 0;
    background: none;
    color: var(--brand);
    font-size: 9.5px;
    text-decoration: underline;
    cursor: pointer;
  }
}

.order-action {
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

.order-action__cancel {
  flex: 0 0 auto;
  padding: 0 4px;
  border: 0;
  background: none;
  color: var(--text-3);
  font-size: 11px;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
  }
}

.order-action__pay {
  flex: 1;
  height: 46px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.3);
  color: #fff;
  font-size: 13.5px;
  font-weight: 800;
  cursor: pointer;

  &:disabled {
    background: var(--bg-elevated);
    box-shadow: none;
    color: var(--text-3);
    cursor: not-allowed;
  }
}

.success,
.closed {
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 44px 24px 30px;
  text-align: center;

  h1 {
    margin: 18px 0 0;
    font-size: 21px;
    font-weight: 800;
    letter-spacing: -0.3px;
  }

  > p {
    max-width: 260px;
    margin: 9px 0 0;
    color: var(--text-3);
    font-size: 10.5px;
    line-height: 1.7;
  }
}

.success__icon,
.closed__icon {
  display: grid;
  place-items: center;
  width: 66px;
  height: 66px;
  border-radius: 50%;
}

.success__icon {
  border: 1px solid rgba(46, 213, 115, 0.28);
  background: rgba(46, 213, 115, 0.1);
  color: var(--success);
}

.closed__icon {
  border: 1px solid var(--line);
  background: var(--bg-card);
  color: var(--text-2);
}

.success__card {
  width: 100%;
  margin-top: 26px;
  padding: 2px 15px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
  text-align: left;
}

.success__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.62);
  font-size: 10.5px;

  &:last-child {
    border-bottom: 0;
  }

  span {
    flex: 0 0 auto;
    color: var(--text-3);
  }

  b {
    color: var(--text-1);
    font-weight: 600;
    text-align: right;
  }

  &--total b {
    color: var(--brand);
    font-size: 16px;
    font-weight: 800;
  }
}

.success__primary,
.success__ghost {
  width: 100%;
  height: 46px;
  margin-top: 20px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
}

.success__primary {
  border: 0;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.28);
  color: #fff;
}

.success__ghost {
  margin-top: 10px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--text-2);
}

.success__refund {
  border-color: rgba(255, 71, 87, 0.25);
  color: #ff8d96;
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

    &:disabled {
      opacity: 0.6;
    }
  }
}
</style>
