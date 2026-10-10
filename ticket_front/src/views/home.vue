<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

// 首页暂时是空的：只放一个占位和登出入口，
// 用来验证「登录 → 拿到 token → 带上 token 拿回用户信息 → 登出」这条链路真的通了。
const router = useRouter()
const userStore = useUserStore()

onMounted(() => {
  userStore.fetchUserInfo().catch(() => {})
})

async function handleLogout() {
  await userStore.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="home">
    <header class="home__bar">
      <span class="home__nickname">
        {{ userStore.userInfo ? userStore.userInfo.nickname : '未登录' }}
      </span>
      <button class="home__logout" type="button" @click="handleLogout">退出登录</button>
    </header>

    <main class="home__body">
      <p class="home__placeholder">首页</p>
    </main>
  </div>
</template>

<style scoped lang="scss">
.home {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  background: var(--bg-base);
}

.home__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  // 顶部安全区，避免刘海屏遮挡
  padding-top: calc(14px + env(safe-area-inset-top));
  background: var(--bg-card);
}

.home__nickname {
  font-size: 15px;
  color: var(--text-1);
}

.home__logout {
  padding: 6px 12px;
  border: 1px solid var(--text-3);
  border-radius: 999px;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;

  &:active {
    background: var(--bg-elevated);
  }
}

.home__body {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
}

.home__placeholder {
  color: var(--text-3);
  font-size: 15px;
  letter-spacing: 4px;
}
</style>
