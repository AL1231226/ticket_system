<script setup>
import { computed } from 'vue'

import AppIcon from './AppIcon.vue'
import Countdown from './Countdown.vue'
import { formatDateTime, formatMoney, formatSessionDate } from '@/utils/format'

const props = defineProps({
  entry: { type: Object, required: true },
})

defineEmits(['quit', 'pay', 'order', 'rejoin'])

const row = computed(() => props.entry?.waitlist || {})
const status = computed(() => Number(row.value.status ?? 0))
const position = computed(() =>
  Number.isFinite(Number(props.entry?.position)) ? Number(props.entry.position) : null,
)
const ahead = computed(() => {
  if (position.value === null) return null
  return Math.max(0, position.value - 1)
})
const price = computed(() => props.entry?.price ?? 0)
const orderId = computed(() => row.value.orderId)

const statusText = computed(() => {
  return ['排队中', '已获得补位', '已购票', '已过期', '已取消'][status.value] || '未知状态'
})

const statusClass = computed(() => {
  return ['is-queued', 'is-notified', 'is-bought', 'is-expired', 'is-cancelled'][status.value] || ''
})

const joinText = computed(() => formatDateTime(row.value.joinTime))
</script>

<template>
  <article class="wl-card" :class="statusClass">
    <div class="wl-card__head">
      <div class="wl-card__poster" :class="{ 'is-fallback': !entry.poster }">
        <img v-if="entry.poster" :src="entry.poster" :alt="entry.showName" loading="lazy" />
        <span v-else>{{ (entry.showName || '演').slice(0, 1) }}</span>
      </div>

      <div class="wl-card__info">
        <div class="wl-card__title-row">
          <h3>{{ entry.showName || '演出' }}</h3>
          <span class="wl-card__status">{{ statusText }}</span>
        </div>
        <p>{{ formatSessionDate(entry.sessionTime) }}</p>
        <p class="wl-card__tier">{{ entry.tierName }} · {{ entry.seatArea }}</p>
        <div class="wl-card__meta">
          <span>{{ formatMoney(price) }}</span>
          <span>{{ row.quantity }} 张</span>
        </div>
      </div>
    </div>

    <div v-if="status === 0" class="wl-card__state">
      <div class="wl-card__position">
        <b class="num">{{ position === null ? '—' : position }}</b>
        <span>当前位次</span>
      </div>
      <div class="wl-card__state-text">
        <p>
          <template v-if="ahead === null">正在排队，位置会随队列变化。</template>
          <template v-else-if="ahead === 0">你排在队首，有人退票会优先补位。</template>
          <template v-else>前面还有 {{ ahead }} 人，请耐心等待。</template>
        </p>
        <small>{{ joinText }} 加入 · 共 {{ entry.total ?? '—' }} 人排队</small>
      </div>
    </div>

    <div v-else-if="status === 1" class="wl-card__state is-notified">
      <div class="wl-card__state-text">
        <p>已为你留票，请在倒计时内完成支付。</p>
        <Countdown :target="row.expireTime" label="支付剩余" tone="waitlist" compact />
      </div>
    </div>

    <div v-else class="wl-card__state is-text-only">
      <p v-if="status === 2">已成功购票，可在订单或票夹中查看。</p>
      <p v-else-if="status === 3">补位后 10 分钟内未支付，位置已释放。</p>
      <p v-else>你已退出本次候补，可重新排队。</p>
    </div>

    <div class="wl-card__actions">
      <button v-if="status === 0" type="button" class="is-ghost" @click="$emit('quit')">
        <AppIcon name="x" :size="13" />
        退出候补
      </button>
      <button
        v-else-if="status === 1"
        type="button"
        class="is-primary"
        :disabled="!orderId || String(orderId) === '0'"
        @click="$emit('pay')"
      >
        去支付
      </button>
      <button v-else-if="status === 2" type="button" class="is-ghost" @click="$emit('order')">
        查看订单
      </button>
      <button v-else type="button" class="is-primary" @click="$emit('rejoin')">
        重新候补
      </button>
    </div>
  </article>
</template>

<style scoped lang="scss">
.wl-card {
  margin-top: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--bg-card);
}

.wl-card__head {
  display: flex;
  gap: 12px;
}

.wl-card__poster {
  display: grid;
  place-items: center;
  width: 64px;
  height: 86px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 10px;
  background: linear-gradient(145deg, #7b61ff, #2a1d55);
  color: rgba(255, 255, 255, 0.7);
  font-size: 26px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.wl-card__info {
  min-width: 0;
  flex: 1;

  p {
    overflow: hidden;
    margin: 5px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .wl-card__tier {
    color: var(--text-2);
  }
}

.wl-card__title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  h3 {
    overflow: hidden;
    margin: 0;
    color: var(--text-1);
    font-size: 13.5px;
    font-weight: 700;
    line-height: 1.35;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.wl-card__status {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--brand-alt-soft);
  color: #b7a8ff;
  font-size: 8px;
  font-weight: 700;
}

.wl-card.is-notified .wl-card__status {
  background: var(--gold-soft);
  color: var(--accent);
}

.wl-card.is-bought .wl-card__status {
  background: rgba(46, 213, 115, 0.12);
  color: var(--success);
}

.wl-card.is-expired .wl-card__status,
.wl-card.is-cancelled .wl-card__status {
  background: var(--bg-elevated);
  color: var(--text-3);
}

.wl-card__meta {
  display: flex;
  gap: 10px;
  margin-top: 7px;

  span {
    color: var(--brand);
    font-size: 11px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    &:last-child {
      color: var(--text-3);
      font-weight: 500;
    }
  }
}

.wl-card__state {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 13px;
  padding: 12px;
  border-radius: 11px;
  background: var(--brand-alt-soft);

  &.is-notified {
    background: var(--gold-soft);
  }

  &.is-text-only {
    align-items: flex-start;
    background: var(--bg-elevated);

    p {
      margin: 0;
      color: var(--text-2);
      font-size: 10px;
      line-height: 1.6;
    }
  }
}

.wl-card__position {
  display: flex;
  align-items: center;
  flex-direction: column;
  min-width: 56px;

  b {
    color: #b7a8ff;
    font-size: 28px;
    font-weight: 800;
    letter-spacing: -1px;
    line-height: 1;
  }

  span {
    margin-top: 4px;
    color: var(--text-3);
    font-size: 8px;
  }
}

.wl-card__state-text {
  min-width: 0;

  p {
    margin: 0;
    color: var(--text-2);
    font-size: 10px;
    line-height: 1.6;
  }

  small {
    display: block;
    margin-top: 5px;
    color: var(--text-3);
    font-size: 8.5px;
  }

  :deep(.countdown) {
    margin-top: 7px;
  }
}

.wl-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;

  button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 34px;
    padding: 0 14px;
    border-radius: 9px;
    font-size: 10.5px;
    font-weight: 700;
    cursor: pointer;

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
  }

  .is-primary {
    border: 0;
    background: linear-gradient(96deg, #7b61ff, #a78bfa);
    color: #fff;
  }

  .is-ghost {
    border: 1px solid rgba(123, 97, 255, 0.35);
    background: transparent;
    color: #b7a8ff;
  }
}
</style>
