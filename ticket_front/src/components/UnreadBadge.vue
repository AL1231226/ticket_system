<script setup>
import AppIcon from './AppIcon.vue'

const props = defineProps({
  unread: { type: Number, default: 0 },
  to: { type: String, default: '/notifications' },
})
</script>

<template>
  <RouterLink :to="props.to" class="unread-badge" aria-label="消息">
    <AppIcon name="bell" :size="18" />
    <span v-if="props.unread > 0" class="unread-badge__count">
      {{ props.unread > 99 ? '99+' : props.unread }}
    </span>
  </RouterLink>
</template>

<style scoped lang="scss">
.unread-badge {
  position: relative;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--bg-card);
  color: var(--text-2);
  cursor: pointer;

  &:active {
    opacity: 0.7;
  }
}

.unread-badge__count {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border: 1px solid var(--bg-base);
  border-radius: 999px;
  background: var(--brand);
  color: #fff;
  font-size: 8px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 14px;
  text-align: center;
}
</style>
