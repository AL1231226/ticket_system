<script setup>
import { computed } from 'vue'

import { useCountdown } from '@/composables/useCountdown'
import { pad2 } from '@/utils/format'

const props = defineProps({
  target: { type: [String, Number, Date], default: '' },
  label: { type: String, default: '距开票' },
  compact: { type: Boolean, default: false },
  // brand = 粉金渐变（开票/支付）；waitlist = 紫色渐变（候补补位）
  tone: { type: String, default: 'brand' },
})

const targetRef = computed(() => props.target)
const { remaining } = useCountdown(targetRef)
</script>

<template>
  <div
    class="countdown"
    :class="{
      'is-expired': remaining.expired,
      'is-compact': compact,
      'is-tone-waitlist': tone === 'waitlist',
    }"
  >
    <span class="countdown__label">{{ label }}</span>
    <div class="countdown__time num">
      <template v-if="remaining.days > 0">
        <b>{{ remaining.days }}</b><i>天</i>
      </template>
      <b>{{ pad2(remaining.hours) }}</b><i>:</i>
      <b>{{ pad2(remaining.minutes) }}</b><i>:</i>
      <b>{{ pad2(remaining.seconds) }}</b>
    </div>
    <span v-if="!compact" class="countdown__units">时<span>分</span><span>秒</span></span>
  </div>
</template>

<style scoped lang="scss">
.countdown {
  display: inline-flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 5px;
}

.countdown__label {
  color: rgba(255, 255, 255, 0.62);
  font-size: 9px;
  letter-spacing: 0.8px;
}

.countdown__time {
  display: flex;
  align-items: baseline;
  color: transparent;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 1px;
  line-height: 1;
  background: linear-gradient(92deg, #ff3d71, #ffcb47);
  background-clip: text;
  -webkit-background-clip: text;

  b {
    font-weight: 800;
  }

  i {
    margin: 0 1px;
    font-size: 11px;
    font-style: normal;
    font-weight: 600;
    -webkit-text-fill-color: rgba(255, 255, 255, 0.5);
  }
}

.countdown__units {
  display: flex;
  gap: 17px;
  margin-left: 2px;
  color: rgba(255, 255, 255, 0.32);
  font-size: 8px;
  letter-spacing: 1px;

  span {
    margin-left: 0;
  }
}

.countdown.is-compact {
  .countdown__time {
    font-size: 15px;
    letter-spacing: 0.6px;
  }
}

.countdown.is-tone-waitlist .countdown__time {
  background: linear-gradient(92deg, #7b61ff, #a78bfa);
  background-clip: text;
  -webkit-background-clip: text;
}

.countdown.is-expired {
  .countdown__time {
    color: var(--text-2);
    background: none;
    -webkit-text-fill-color: var(--text-2);
  }
}
</style>
