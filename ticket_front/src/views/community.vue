<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { getCommunityFeed } from '@/api/community'
import AppIcon from '@/components/AppIcon.vue'
import PostCard from '@/components/PostCard.vue'
import StateView from '@/components/StateView.vue'
import { useUserStore } from '@/stores/user'

const MODES = [
  { value: 'follow', label: '关注' },
  { value: 'latest', label: '最新' },
]

const router = useRouter()
const userStore = useUserStore()
const mode = ref(userStore.token ? 'follow' : 'latest')
const posts = ref([])
const cursor = ref(null)
const loading = ref(false)
const refreshing = ref(false)
const finished = ref(false)
const loadedOnce = ref(false)
const error = ref('')
const fallbackGlobal = ref(false)

let seq = 0

const initialLoading = computed(() => loading.value && !posts.value.length)
const showFallbackHint = computed(
  () =>
    mode.value === 'follow' &&
    posts.value.length > 0 &&
    (!userStore.token || fallbackGlobal.value),
)
const emptyTitle = computed(() => {
  if (mode.value !== 'follow') return '社区还没有动态'
  return userStore.token ? '关注后这里会出现动态' : '登录后查看关注动态'
})
const emptyDesc = computed(() => {
  if (mode.value !== 'follow') return '第一条现场记录，等你来写。'
  return userStore.token
    ? '先看看全站最新，关注感兴趣的人后会变成你的专属时间线。'
    : '关注的人发动态会出现在这里，去登录后开启你的关注流。'
})

onMounted(() => loadPage(true))

async function loadPage(reset = false) {
  const mySeq = ++seq
  if (reset) {
    posts.value = []
    cursor.value = null
    finished.value = false
    loadedOnce.value = false
    error.value = ''
    fallbackGlobal.value = false
  }

  loading.value = true
  try {
    const res = await getCommunityFeed({
      mode: mode.value,
      lastId: reset ? undefined : cursor.value?.lastId,
      offset: reset ? 0 : cursor.value?.offset || 0,
      count: 10,
    })
    if (mySeq !== seq) return

    const list = Array.isArray(res?.posts) ? res.posts : []
    posts.value = reset ? list : [...posts.value, ...list]
    cursor.value = res?.nextCursor || null
    finished.value = !res?.hasMore
    fallbackGlobal.value = Boolean(res?.fallbackGlobal)
    loadedOnce.value = true
  } catch (e) {
    if (mySeq !== seq) return
    error.value = e.message
    finished.value = true
    loadedOnce.value = true
  } finally {
    if (mySeq === seq) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function onLoad() {
  loadPage(false)
}

function onRefresh() {
  refreshing.value = true
  loadPage(true)
}

function switchMode(next) {
  if (mode.value === next) return
  mode.value = next
  loadPage(true)
}

function retry() {
  loadPage(true)
}

function openPost(id) {
  router.push(`/post/${id}`)
}

function goPublish() {
  router.push('/post/new')
}
</script>

<template>
  <div class="community page">
    <header class="community__head">
      <div class="community__brand">
        <span class="community__mark">
          <AppIcon name="community" :size="19" />
        </span>
        <div>
          <h1>社区</h1>
          <p>今晚的现场，大家都在聊</p>
        </div>
      </div>

      <div class="community__modes" role="tablist" aria-label="社区流">
        <button
          v-for="item in MODES"
          :key="item.value"
          type="button"
          role="tab"
          :aria-selected="mode === item.value"
          :class="{ 'is-active': mode === item.value }"
          @click="switchMode(item.value)"
        >
          {{ item.label }}
        </button>
      </div>
    </header>

    <p v-if="showFallbackHint" class="community__hint">
      <AppIcon name="community" :size="14" />
      {{ userStore.token ? '关注后这里会出现动态，先看看全站最新。' : '登录后这里会变成你的关注时间线。' }}
    </p>

    <van-pull-refresh v-model="refreshing" class="community__refresh" @refresh="onRefresh">
      <div v-if="initialLoading" class="community__skeletons">
        <div v-for="i in 2" :key="i" class="community-skeleton">
          <div class="community-skeleton__head">
            <i class="community-skeleton__avatar" />
            <div class="community-skeleton__who">
              <i />
              <i />
            </div>
          </div>
          <i class="community-skeleton__line" />
          <i class="community-skeleton__line community-skeleton__line--short" />
          <div class="community-skeleton__image" />
        </div>
      </div>

      <StateView
        v-else-if="error && !posts.length"
        icon="community"
        title="社区加载失败"
        :description="error"
        action-text="重试"
        @action="retry"
      />

      <StateView
        v-else-if="loadedOnce && !posts.length"
        icon="community"
        :title="emptyTitle"
        :description="emptyDesc"
        action-text="刷新"
        @action="retry"
      />

      <template v-else>
        <p v-if="error && posts.length" class="community__error">
          {{ error }}
          <button type="button" @click="retry">重试</button>
        </p>

        <van-list
          v-model:loading="loading"
          class="community__list"
          :finished="finished"
          finished-text="已经到底了"
          @load="onLoad"
        >
          <PostCard v-for="post in posts" :key="post.id" :post="post" @open="openPost(post.id)" />
        </van-list>
      </template>
    </van-pull-refresh>

    <div class="community__fab-wrap">
      <button type="button" class="community__fab" @click="goPublish">
        <AppIcon name="plus" :size="17" />
        <span>发布</span>
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.community {
  padding-bottom: 84px;
}

.community__head {
  position: sticky;
  top: 0;
  z-index: 12;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: calc(16px + env(safe-area-inset-top)) 16px 12px;
  background: linear-gradient(180deg, rgba(26, 16, 32, 0.98), rgba(15, 15, 20, 0.92));
  border-bottom: 1px solid rgba(42, 42, 54, 0.62);
  backdrop-filter: blur(14px);
}

.community__brand {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
}

.community__brand > div {
  min-width: 0;
}

.community__mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand), #ff6b35);
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.3);
  color: #fff;
}

.community__brand h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 1px;
}

.community__brand p {
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
  letter-spacing: 0.3px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.community__modes {
  display: flex;
  flex: 0 0 auto;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(26, 26, 34, 0.85);
}

.community__modes button {
  min-width: 48px;
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--text-3);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.18s ease,
    color 0.18s ease;

  &.is-active {
    background: var(--brand-soft);
    color: var(--brand);
  }
}

.community__hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 12px 16px 0;
  padding: 9px 12px;
  border: 1px solid rgba(255, 61, 113, 0.18);
  border-radius: 10px;
  background: var(--brand-soft);
  color: #ff8baa;
  font-size: 10px;
  line-height: 1.5;
}

.community__refresh {
  min-height: calc(100vh - 150px);
}

.community__list {
  padding-top: 12px;
}

.community__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 12px 16px 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.12);
  color: #ff8baa;
  font-size: 10px;

  button {
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--brand);
    font-size: 10px;
    font-weight: 700;
    cursor: pointer;
  }
}

.community__skeletons {
  padding-top: 12px;
}

.community-skeleton {
  margin: 0 16px 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.community-skeleton__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.community-skeleton__avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.community-skeleton__who {
  flex: 1;

  i {
    display: block;
    width: 44%;
    height: 9px;
    margin-bottom: 6px;
    border-radius: 4px;

    &:last-child {
      width: 28%;
      margin-bottom: 0;
    }
  }
}

.community-skeleton__line {
  display: block;
  width: 88%;
  height: 11px;
  margin-top: 14px;
  border-radius: 5px;

  &--short {
    width: 62%;
    margin-top: 8px;
  }
}

.community-skeleton__image {
  height: 118px;
  margin-top: 12px;
  border-radius: 10px;
}

.community-skeleton__avatar,
.community-skeleton__who i,
.community-skeleton__line,
.community-skeleton__image {
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: community-shimmer 1.6s infinite linear;
}

:deep(.van-pull-refresh__head) {
  color: var(--text-3);
  font-size: 11px;
}

:deep(.van-list__finished-text),
:deep(.van-list__loading),
:deep(.van-list__error-text) {
  color: var(--text-3);
  font-size: 10.5px;
  line-height: 44px;
}

@keyframes community-shimmer {
  to {
    background-position: -200% 0;
  }
}

.community__fab-wrap {
  position: fixed;
  bottom: calc(74px + env(safe-area-inset-bottom));
  left: 50%;
  z-index: 25;
  display: flex;
  justify-content: flex-end;
  width: 100%;
  max-width: 480px;
  padding: 0 16px;
  transform: translateX(-50%);
  pointer-events: none;
}

.community__fab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 42px;
  padding: 0 18px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  box-shadow: 0 12px 28px rgba(255, 61, 113, 0.38);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  pointer-events: auto;
  transition: transform 0.15s ease;

  &:active {
    transform: scale(0.96);
  }
}
</style>
