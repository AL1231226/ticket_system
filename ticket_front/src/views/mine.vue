<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getSignCount, getUserInfo, sign } from '@/api/user'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'
import UnreadBadge from '@/components/UnreadBadge.vue'
import { useUnreadPolling } from '@/composables/useUnreadPolling'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const { unread } = useUnreadPolling()

const info = ref(null)
const signDays = ref(0)
const signing = ref(false)
const signedToday = ref(false)
const loading = ref(true)
const error = ref('')
const signError = ref('')
const avatarFailed = ref(false)

const user = computed(() => userStore.userInfo)
const nickname = computed(() => user.value?.nickname || '乐迷')
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())
const level = computed(() => info.value?.level || 1)
const city = computed(() => info.value?.city || '')

const stats = computed(() => [
  { label: '观演场次', value: info.value?.showCount || 0, hot: true },
  { label: '关注', value: info.value?.followee || 0 },
  { label: '粉丝', value: info.value?.fans || 0 },
  { label: '积分', value: info.value?.credits || 0 },
])

const menu = computed(() => [
  { icon: 'receipt', label: '我的订单', hint: '查看全部订单', to: '/orders' },
  { icon: 'hourglass', label: '我的候补', hint: '排队位置与补位提醒', to: '/waitlists' },
  { icon: 'users', label: '观演人管理', hint: '实名信息与一证一票', to: '/attendees' },
  { icon: 'ticket', label: '我的票夹', hint: '电子票与动态二维码', to: '/wallet' },
  {
    icon: 'bell',
    label: '消息',
    hint: unread.value > 0 ? `${unread.value} 条未读` : '候补补位与订单通知',
    to: '/notifications',
    badge: unread.value,
  },
])

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (!userStore.userInfo) {
      await userStore.fetchUserInfo()
    }
    const userId = userStore.userInfo?.id
    if (userId) {
      const [userInfo, days] = await Promise.all([getUserInfo(userId), getSignCount()])
      info.value = userInfo || null
      signDays.value = Number(days || 0)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function signIn() {
  if (signing.value) return
  signing.value = true
  signError.value = ''
  try {
    await sign()
    const days = await getSignCount()
    signDays.value = Number(days || 0)
    signedToday.value = true
  } catch (e) {
    signError.value = e.message
  } finally {
    signing.value = false
  }
}

function goMenu(item) {
  router.push(item.to)
}

async function logout() {
  await userStore.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="mine page">
    <div v-if="loading" class="mine__loading">
      <StateView title="正在加载个人主页" description="马上就好" />
    </div>

    <StateView
      v-else-if="error && !user"
      title="个人主页加载失败"
      :description="error"
      action-text="重试"
      @action="load"
    />

    <template v-else>
      <header class="mine__head">
        <div class="mine__avatar">
          <img
            v-if="user?.icon && !avatarFailed"
            :src="user.icon"
            :alt="nickname"
            @error="avatarFailed = true"
          />
          <span v-else>{{ initial }}</span>
        </div>

        <div class="mine__who">
          <h1>{{ nickname }}</h1>
          <div class="mine__badges">
            <span class="mine__level">Lv.{{ level }} 乐迷</span>
            <span v-if="city" class="mine__city">{{ city }}</span>
          </div>
        </div>

        <UnreadBadge :unread="unread" class="mine__bell" />
      </header>

      <section class="mine__stats">
        <div v-for="item in stats" :key="item.label" class="mine__stat">
          <b :class="{ 'is-hot': item.hot }" class="num">{{ item.value }}</b>
          <span>{{ item.label }}</span>
        </div>
      </section>

      <section class="sign-card">
        <div class="sign-card__main">
          <div class="sign-card__count">
            <b class="num">{{ signDays }}</b>
            <span>天</span>
          </div>
          <div>
            <h2>连续签到</h2>
            <p>{{ signDays ? '保持记录，别断在今天。' : '今天签到，开启连续记录。' }}</p>
          </div>
        </div>

        <button
          class="sign-card__button"
          type="button"
          :disabled="signing || signedToday"
          @click="signIn"
        >
          {{ signing ? '签到中…' : signedToday ? '已签到' : '今日签到' }}
        </button>

        <p v-if="signError" class="sign-card__error">{{ signError }}</p>
      </section>

      <section class="mine__menu">
        <button
          v-for="item in menu"
          :key="item.label"
          type="button"
          class="mine__item"
          @click="goMenu(item)"
        >
          <span class="mine__item-icon">
            <AppIcon :name="item.icon" :size="17" />
          </span>
          <span class="mine__item-text">
            <b>{{ item.label }}</b>
            <small>{{ item.hint }}</small>
          </span>
          <span v-if="item.badge > 0" class="mine__item-badge">
            {{ item.badge > 99 ? '99+' : item.badge }}
          </span>
          <AppIcon name="chevron-right" :size="16" class="mine__arrow" />
        </button>
      </section>

      <button class="mine__logout" type="button" @click="logout">
        <AppIcon name="logout" :size="16" />
        退出登录
      </button>

      <p v-if="error" class="mine__error">{{ error }}</p>
    </template>
  </div>
</template>

<style scoped lang="scss">
.mine {
  padding-bottom: 30px;
}

.mine__loading {
  min-height: 70vh;
}

.mine__head {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: calc(24px + env(safe-area-inset-top)) 18px 26px;
  background: linear-gradient(165deg, #241338, #1a1020 58%, var(--bg-base));
}

.mine__avatar {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #7b61ff, #ff3d71);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 22px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.mine__who {
  min-width: 0;

  h1 {
    overflow: hidden;
    margin: 0;
    font-size: 18px;
    font-weight: 800;
    letter-spacing: -0.3px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.mine__badges {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 7px;
}

.mine__level {
  padding: 2px 7px;
  border-radius: 6px;
  background: linear-gradient(92deg, #ffcb47, #ffa726);
  color: #1a1020;
  font-size: 8px;
  font-weight: 800;
}

.mine__city {
  color: var(--text-3);
  font-size: 9px;
}

.mine__bell {
  margin-left: auto;
}

.mine__stats {
  display: flex;
  margin: -14px 16px 0;
  padding: 15px 0;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
  position: relative;
  z-index: 2;
}

.mine__stat {
  position: relative;
  flex: 1;
  text-align: center;

  &:not(:last-child)::after {
    content: '';
    position: absolute;
    top: 4px;
    right: 0;
    bottom: 4px;
    width: 1px;
    background: var(--line);
  }

  b {
    display: block;
    font-size: 18px;
    font-weight: 800;
    letter-spacing: -0.3px;

    &.is-hot {
      color: var(--brand);
    }
  }

  span {
    display: block;
    margin-top: 5px;
    color: var(--text-3);
    font-size: 9px;
  }
}

.sign-card {
  margin: 15px 16px 0;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
}

.sign-card__main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sign-card__count {
  display: flex;
  align-items: baseline;
  gap: 3px;
  min-width: 52px;
  color: var(--accent);

  b {
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -1px;
    line-height: 1;
  }

  span {
    font-size: 9px;
  }
}

.sign-card h2 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 700;
}

.sign-card p {
  margin: 5px 0 0;
  color: var(--text-3);
  font-size: 9px;
}

.sign-card__button {
  width: 100%;
  height: 38px;
  margin-top: 13px;
  border: 1px solid rgba(255, 203, 71, 0.35);
  border-radius: 9px;
  background: var(--gold-soft);
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;

  &:disabled {
    border-color: var(--line);
    background: var(--bg-elevated);
    color: var(--text-3);
    cursor: default;
  }
}

.sign-card__error {
  color: #ff9aa3 !important;
  font-size: 9px !important;
}

.mine__menu {
  margin: 15px 16px 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
}

.mine__item {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 13px 14px;
  border: 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.55);
  background: transparent;
  text-align: left;
  cursor: pointer;

  &:last-child {
    border-bottom: 0;
  }

  &:active {
    background: rgba(255, 255, 255, 0.025);
  }
}

.mine__item-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: rgba(123, 97, 255, 0.13);
  color: #b7a8ff;
}

.mine__item-text {
  flex: 1;

  b {
    display: block;
    color: var(--text-1);
    font-size: 12.5px;
    font-weight: 600;
  }

  small {
    display: block;
    margin-top: 3px;
    color: var(--text-3);
    font-size: 8.5px;
  }
}

.mine__item-badge {
  min-width: 16px;
  height: 16px;
  margin-right: 7px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--brand);
  color: #fff;
  font-size: 8px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  text-align: center;
}

.mine__arrow {
  color: var(--text-3);
}

.mine__logout {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: calc(100% - 32px);
  height: 42px;
  margin: 18px 16px 0;
  border: 1px solid rgba(255, 71, 87, 0.2);
  border-radius: 11px;
  background: transparent;
  color: #ff8d96;
  font-size: 11.5px;
  cursor: pointer;
}

.mine__error {
  margin: 14px 16px 0;
  color: #ff9aa3;
  font-size: 9.5px;
  text-align: center;
}
</style>
