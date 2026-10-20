<script setup>
import TabBar from '@/components/TabBar.vue'
</script>

<template>
  <div class="tab-layout">
    <main class="tab-layout__main">
      <router-view />
    </main>
    <TabBar />
  </div>
</template>

<style scoped lang="scss">
.tab-layout {
  min-height: 100%;
  background: var(--bg-base);
}

.tab-layout__main {
  min-height: 100vh;
  padding-bottom: calc(62px + env(safe-area-inset-bottom));
}
</style>
