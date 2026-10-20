<script setup>
import { computed } from 'vue'

import AppIcon from './AppIcon.vue'
import QrCode from './QrCode.vue'
import { formatSessionDate } from '@/utils/format'

const props = defineProps({
  ticketVO: { type: Object, required: true },
})

const emit = defineEmits(['show-qr'])

const ticket = computed(() => props.ticketVO?.ticket || {})
const unused = computed(() => ticket.value.status === 0)
const checked = computed(() => ticket.value.status === 1)
const refunded = computed(() => ticket.value.status === 2)
const seatText = computed(() => ticket.value.seatNo || '站票/通票')
const statusText = computed(() => {
  if (checked.value) return '已核验'
  if (refunded.value) return '已退票'
  return '待使用'
})

function openQr() {
  if (!unused.value) return
  emit('show-qr', props.ticketVO)
}
</script>

<template>
  <article
    class="stub"
    :class="{ 'is-used': checked, 'is-refunded': refunded }"
    @click="openQr"
  >
    <div class="stub__top">
      <div class="stub__poster" :class="{ 'is-fallback': !ticketVO.poster }">
        <img v-if="ticketVO.poster" :src="ticketVO.poster" :alt="ticketVO.showName" loading="lazy" />
        <span v-else>{{ (ticketVO.showName || '演').slice(0, 1) }}</span>
      </div>

      <div class="stub__info">
        <h3>{{ ticketVO.showName || '演出' }}</h3>
        <p>{{ formatSessionDate(ticketVO.sessionTime) }}</p>
        <span>{{ ticketVO.tierName }}<i v-if="ticketVO.seatArea"> · {{ ticketVO.seatArea }}</i></span>
      </div>

      <span class="stub__status" :class="{ 'is-checked': checked, 'is-refunded': refunded }">
        {{ statusText }}
      </span>
    </div>

    <div class="stub__perf">
      <i class="stub__notch stub__notch--left" />
      <span class="stub__dash" />
      <i class="stub__notch stub__notch--right" />
    </div>

    <div class="stub__bottom">
      <div class="stub__seat">
        <span>座位</span>
        <b>{{ checked ? '已核验入场' : seatText }}</b>
        <p>观演人 {{ ticket.realName || '—' }} · 电子票</p>
      </div>

      <div v-if="unused" class="stub__qr">
        <QrCode :value="ticket.qrCode" :size="56" :margin="0" />
        <em>查看二维码</em>
      </div>
      <div v-else class="stub__stamp">
        <AppIcon :name="checked ? 'check' : 'x'" :size="16" />
        <span>{{ checked ? '已核验' : '已退票' }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.stub {
  position: relative;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--bg-card);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.38);
  cursor: pointer;
}

.stub__top {
  position: relative;
  display: flex;
  gap: 11px;
  padding: 14px 14px 13px;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(135deg, rgba(255, 61, 113, 0.13), transparent 58%);
    pointer-events: none;
  }
}

.stub__poster {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 50px;
  height: 66px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 8px;
  background: linear-gradient(145deg, #ff3d71, #3a164d);
  color: rgba(255, 255, 255, 0.24);
  font-size: 24px;
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

.stub__info {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;

  h3 {
    display: -webkit-box;
    overflow: hidden;
    margin: 1px 0 0;
    font-size: 12.5px;
    font-weight: 800;
    letter-spacing: -0.15px;
    line-height: 1.35;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  p {
    overflow: hidden;
    margin: 6px 0 0;
    color: var(--text-3);
    font-size: 9px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    display: block;
    margin-top: 4px;
    color: var(--text-2);
    font-size: 9px;

    i {
      font-style: normal;
    }
  }
}

.stub__status {
  position: relative;
  z-index: 1;
  align-self: flex-start;
  flex: 0 0 auto;
  height: 19px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 8px;
  font-weight: 700;
  line-height: 19px;

  &.is-checked {
    background: rgba(46, 213, 115, 0.1);
    color: var(--success);
  }

  &.is-refunded {
    background: var(--bg-elevated);
    color: var(--text-3);
  }
}

.stub__perf {
  position: relative;
  height: 20px;
}

.stub__dash {
  position: absolute;
  top: 50%;
  right: 14px;
  left: 14px;
  border-top: 1.5px dashed rgba(90, 90, 110, 0.65);
}

.stub__notch {
  position: absolute;
  top: 50%;
  z-index: 2;
  width: 20px;
  height: 20px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--bg-base);
  transform: translateY(-50%);

  &--left {
    left: -10px;
  }

  &--right {
    right: -10px;
  }
}

.stub__bottom {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 14px 16px;
}

.stub__seat {
  min-width: 0;

  span {
    display: block;
    color: var(--text-3);
    font-size: 8.5px;
    letter-spacing: 0.5px;
  }

  b {
    display: block;
    margin-top: 5px;
    color: var(--text-1);
    font-size: 23px;
    font-weight: 800;
    letter-spacing: -0.4px;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }

  p {
    margin: 7px 0 0;
    color: var(--text-3);
    font-size: 9px;
  }
}

.stub__qr {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 4px;
  flex: 0 0 auto;

  em {
    color: var(--text-3);
    font-size: 8px;
    font-style: normal;
  }
}

.stub__stamp {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 7px;
  color: var(--text-3);
  font-size: 9px;
}

.stub.is-used {
  opacity: 0.55;

  .stub__bottom b {
    color: var(--success);
    font-size: 18px;
  }
}

.stub.is-refunded {
  opacity: 0.48;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
