<script setup>
import { computed, ref } from 'vue'

import { formatDate, formatMoney, formatWant } from '@/utils/format'

const props = defineProps({
  show: { type: Object, required: true },
})

defineEmits(['click'])

const failed = ref(false)

const fallbackStyle = computed(() => {
  const palettes = [
    ['#ff3d71', '#5b2a86'],
    ['#00d2ff', '#1b2a6b'],
    ['#ffcb47', '#7a3b12'],
    ['#2ed573', '#0b3d2c'],
    ['#e056fd', '#2b1550'],
  ]
  const p = palettes[Number(props.show.id || 0) % palettes.length]
  return { background: `linear-gradient(145deg, ${p[0]}, ${p[1]})` }
})

const initial = computed(() => (props.show.name || '演').slice(0, 1))
</script>

<template>
  <article class="show-card" @click="$emit('click', show)">
    <div class="show-card__poster" :style="fallbackStyle">
      <img
        v-if="show.poster && !failed"
        :src="show.poster"
        :alt="show.name"
        loading="lazy"
        @error="failed = true"
      />
      <span v-if="failed || !show.poster" class="show-card__initial">{{ initial }}</span>
      <span class="show-card__date">{{ formatDate(show.startDate) }}</span>
    </div>

    <div class="show-card__body">
      <h3 class="show-card__name">{{ show.name }}</h3>
      <p class="show-card__artist">{{ show.artist || '演出' }}</p>
      <p class="show-card__meta">
        <span>{{ show.city }}</span>
        <i />
        <span>{{ formatDate(show.startDate) }}</span>
      </p>
      <div class="show-card__bottom">
        <span v-if="show.minPrice" class="show-card__price">
          {{ formatMoney(show.minPrice) }}<small>起</small>
        </span>
        <span v-else class="show-card__price show-card__price--muted">价格待定</span>
        <span class="show-card__want">{{ formatWant(show.wantCount) }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.show-card {
  display: flex;
  gap: 12px;
  padding: 10px 16px;
  cursor: pointer;
  transition: background 0.18s ease;

  &:active {
    background: rgba(255, 255, 255, 0.025);
  }
}

.show-card__poster {
  position: relative;
  display: grid;
  place-items: center;
  width: 84px;
  height: 108px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 11px;
  background: var(--bg-elevated);

  &::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, transparent 55%, rgba(7, 7, 10, 0.6));
    pointer-events: none;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.show-card__initial {
  color: rgba(255, 255, 255, 0.28);
  font-size: 40px;
  font-weight: 800;
  letter-spacing: -2px;
}

.show-card__date {
  position: absolute;
  right: 6px;
  bottom: 6px;
  z-index: 2;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgba(7, 7, 10, 0.62);
  color: rgba(255, 255, 255, 0.78);
  font-size: 8px;
  letter-spacing: 0.2px;
  backdrop-filter: blur(4px);
}

.show-card__body {
  min-width: 0;
  flex: 1;
  padding: 3px 0;
}

.show-card__name {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: -0.15px;
  line-height: 1.4;
  text-wrap: balance;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.show-card__artist {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--text-2);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.show-card__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 7px 0 0;
  color: var(--text-3);
  font-size: 10px;

  i {
    width: 2px;
    height: 2px;
    border-radius: 50%;
    background: var(--text-3);
  }
}

.show-card__bottom {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}

.show-card__price {
  color: var(--brand);
  font-size: 14px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;

  small {
    margin-left: 2px;
    color: var(--text-3);
    font-size: 9px;
    font-weight: 500;
  }

  &--muted {
    color: var(--text-3);
    font-size: 11px;
    font-weight: 500;
  }
}

.show-card__want {
  overflow: hidden;
  color: var(--text-3);
  font-size: 9.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
