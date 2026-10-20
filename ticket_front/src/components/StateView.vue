<script setup>
import AppIcon from './AppIcon.vue'

const props = defineProps({
  icon: { type: String, default: 'ticket' },
  title: { type: String, default: '这里还是空的' },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' },
})

const emit = defineEmits(['action'])
</script>

<template>
  <div class="state">
    <div class="state__icon">
      <AppIcon :name="props.icon" :size="26" :stroke-width="1.5" />
    </div>
    <p class="state__title">{{ props.title }}</p>
    <p v-if="props.description" class="state__desc">{{ props.description }}</p>
    <button v-if="props.actionText" class="state__action" type="button" @click="emit('action')">
      {{ props.actionText }}
    </button>
  </div>
</template>

<style scoped lang="scss">
.state {
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  padding: 56px 32px;
  text-align: center;
}

.state__icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 18px;
  border: 1px solid rgba(255, 61, 113, 0.24);
  border-radius: 18px;
  background: var(--brand-soft);
  color: var(--brand);
}

.state__title {
  margin: 0;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 700;
}

.state__desc {
  max-width: 240px;
  margin: 8px 0 0;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.7;
}

.state__action {
  margin-top: 20px;
  padding: 9px 18px;
  border: 1px solid rgba(255, 61, 113, 0.4);
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}
</style>
