<script setup>
import { useRouter } from 'vue-router'

import AppIcon from './AppIcon.vue'

const props = defineProps({
  title: { type: String, default: '' },
  back: { type: Boolean, default: true },
})

const router = useRouter()

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/home')
  }
}
</script>

<template>
  <header class="appbar">
    <button v-if="props.back" class="appbar__back" type="button" aria-label="返回" @click="goBack">
      <AppIcon name="arrow-left" :size="20" />
    </button>
    <div v-else class="appbar__placeholder" />
    <h1 class="appbar__title">{{ props.title }}</h1>
    <div class="appbar__right">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped lang="scss">
.appbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  align-items: center;
  height: 52px;
  padding-top: env(safe-area-inset-top);
  background: rgba(15, 15, 20, 0.82);
  border-bottom: 1px solid rgba(42, 42, 54, 0.65);
  backdrop-filter: blur(16px);
}

.appbar__back,
.appbar__placeholder {
  width: 44px;
  height: 44px;
}

.appbar__back {
  display: grid;
  place-items: center;
  border: 0;
  background: transparent;
  color: var(--text-1);
  cursor: pointer;
  transition: opacity 0.15s ease;

  &:active {
    opacity: 0.6;
  }
}

.appbar__title {
  margin: 0;
  overflow: hidden;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 700;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.appbar__right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding-right: 12px;
}
</style>
