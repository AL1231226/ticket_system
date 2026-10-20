<script setup>
import { useRoute } from 'vue-router'

import AppIcon from './AppIcon.vue'

const route = useRoute()

const tabs = [
  { name: 'home', label: '首页', icon: 'home', to: '/home' },
  { name: 'wallet', label: '票夹', icon: 'ticket', to: '/wallet' },
  { name: 'mine', label: '我的', icon: 'user', to: '/mine' },
]
</script>

<template>
  <nav class="tabbar" aria-label="主导航">
    <RouterLink
      v-for="tab in tabs"
      :key="tab.name"
      :to="tab.to"
      class="tabbar__item"
      :class="{ 'is-active': route.name === tab.name }"
    >
      <AppIcon :name="tab.icon" :size="21" />
      <span>{{ tab.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped lang="scss">
.tabbar {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  width: 100%;
  max-width: 480px;
  height: calc(62px + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.tabbar__item {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.4px;
  transition: color 0.18s ease;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    left: 50%;
    width: 18px;
    height: 2px;
    border-radius: 0 0 2px 2px;
    background: transparent;
    transform: translateX(-50%);
    transition: background 0.18s ease;
  }

  &.is-active {
    color: var(--brand);

    &::before {
      background: var(--brand);
    }
  }

  &:active {
    opacity: 0.7;
  }
}
</style>
