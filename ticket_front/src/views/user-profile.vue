<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getCommonFollows, getUserPosts, toggleFollow } from '@/api/community'
import { getUserProfile } from '@/api/user'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import PostCard from '@/components/PostCard.vue'
import StateView from '@/components/StateView.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const profile = ref(null)
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const followBusy = ref(false)
const avatarFailed = ref(false)

const posts = ref([])
const postCursor = ref(null)
const postHasMore = ref(false)
const postsLoading = ref(false)
const postsLoaded = ref(false)
const postsError = ref('')

const commonFollows = ref([])
const commonLoaded = ref(false)
const failedAvatars = ref(new Set())

let profileSeq = 0
let postsSeq = 0
let commonSeq = 0

const userId = computed(() => route.params.id)
const isSelf = computed(() => {
  const uid = userStore.userInfo?.id
  return Boolean(uid && profile.value?.userId && String(uid) === String(profile.value.userId))
})
const nickname = computed(() => profile.value?.nickname || '乐迷')
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())
const introduce = computed(() => profile.value?.introduce || '这位乐迷还没有填写简介。')
const stats = computed(() => [
  { key: 'posts', label: '动态', value: Number(profile.value?.posts || 0) },
  { key: 'followees', label: '关注', value: Number(profile.value?.followee || 0), type: 'followees' },
  { key: 'fans', label: '粉丝', value: Number(profile.value?.fans || 0), type: 'fans' },
])

onMounted(init)
watch(
  () => route.params.id,
  () => {
    if (route.name === 'user-profile') init()
  },
)

async function init() {
  resetState()
  await ensureUser()
  const loaded = await loadProfile()
  if (loaded) {
    loadPosts(true)
    loadCommon()
  }
}

function resetState() {
  profile.value = null
  loading.value = true
  error.value = ''
  actionError.value = ''
  posts.value = []
  postCursor.value = null
  postHasMore.value = false
  postsLoaded.value = false
  postsError.value = ''
  commonFollows.value = []
  commonLoaded.value = false
  avatarFailed.value = false
  failedAvatars.value = new Set()
}

async function ensureUser() {
  if (!userStore.token || userStore.userInfo) return
  try {
    await userStore.fetchUserInfo()
  } catch {
    // 登录失效由拦截器统一处理；公开主页不依赖登录态
  }
}

async function loadProfile() {
  const mySeq = ++profileSeq
  loading.value = true
  error.value = ''
  try {
    const data = await getUserProfile(userId.value)
    if (mySeq !== profileSeq) return false
    profile.value = data
    return true
  } catch (e) {
    if (mySeq !== profileSeq) return false
    error.value = e.message
    return false
  } finally {
    if (mySeq === profileSeq) {
      loading.value = false
    }
  }
}

async function loadPosts(reset = false) {
  if (!reset && (postsLoading.value || !postHasMore.value)) return

  const mySeq = ++postsSeq
  postsLoading.value = true
  postsError.value = ''
  try {
    const res = await getUserPosts(userId.value, {
      lastId: reset ? undefined : postCursor.value?.lastId,
      offset: reset ? 0 : postCursor.value?.offset || 0,
      count: 10,
    })
    if (mySeq !== postsSeq) return

    const list = Array.isArray(res?.posts) ? res.posts : []
    posts.value = reset ? list : [...posts.value, ...list]
    postCursor.value = res?.nextCursor || null
    postHasMore.value = Boolean(res?.hasMore)
    postsLoaded.value = true
  } catch (e) {
    if (mySeq !== postsSeq) return
    postsError.value = e.message
    postsLoaded.value = true
  } finally {
    if (mySeq === postsSeq) {
      postsLoading.value = false
    }
  }
}

async function loadCommon() {
  if (!userStore.token || isSelf.value || !profile.value) return
  const mySeq = ++commonSeq
  try {
    const list = await getCommonFollows(profile.value.userId)
    if (mySeq !== commonSeq) return
    commonFollows.value = Array.isArray(list) ? list : []
  } catch {
    if (mySeq !== commonSeq) return
    commonFollows.value = []
  } finally {
    if (mySeq === commonSeq) {
      commonLoaded.value = true
    }
  }
}

function requireLogin() {
  if (userStore.token) return true
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

async function toggleFollowProfile() {
  if (!requireLogin() || followBusy.value || !profile.value) return
  followBusy.value = true
  actionError.value = ''
  try {
    const res = await toggleFollow(profile.value.userId, !profile.value.isFollowing)
    profile.value.isFollowing = Boolean(res?.following)
    if (typeof res?.fans === 'number') {
      profile.value.fans = res.fans
    }
  } catch (e) {
    actionError.value = e.message
  } finally {
    followBusy.value = false
  }
}

function goFollows(type) {
  router.push(`/user/${userId.value}/follows?type=${type}`)
}

function goUser(id) {
  router.push(`/user/${id}`)
}

function openPost(id) {
  router.push(`/post/${id}`)
}

function markAvatarFailed(id) {
  const next = new Set(failedAvatars.value)
  next.add(String(id))
  failedAvatars.value = next
}
</script>

<template>
  <div class="profile page">
    <AppBar title="个人主页" />

    <div v-if="loading" class="profile__skeleton">
      <div class="profile__skeleton-head">
        <i class="profile__skeleton-avatar" />
        <div>
          <i />
          <i />
        </div>
      </div>
      <i class="profile__skeleton-line" />
      <i class="profile__skeleton-line profile__skeleton-line--short" />
    </div>

    <StateView
      v-else-if="error"
      icon="user"
      title="个人主页加载失败"
      :description="error"
      action-text="重试"
      @action="init"
    />

    <template v-else-if="profile">
      <header class="profile__head">
        <div class="profile__avatar">
          <img
            v-if="profile.icon && !avatarFailed"
            :src="profile.icon"
            :alt="nickname"
            @error="avatarFailed = true"
          />
          <span v-else>{{ initial }}</span>
        </div>

        <div class="profile__who">
          <h1>{{ nickname }}</h1>
          <div class="profile__badges">
            <span class="profile__level">Lv.{{ profile.level || 1 }}</span>
            <span v-if="profile.city" class="profile__city">
              <AppIcon name="location" :size="11" />
              {{ profile.city }}
            </span>
          </div>
        </div>

        <button
          v-if="!isSelf"
          type="button"
          class="profile__follow"
          :class="{ 'is-following': profile.isFollowing }"
          :disabled="followBusy"
          @click="toggleFollowProfile"
        >
          <AppIcon :name="profile.isFollowing ? 'check' : 'plus'" :size="12" />
          {{ profile.isFollowing ? '已关注' : '关注' }}
        </button>
      </header>

      <p class="profile__intro">{{ introduce }}</p>

      <p v-if="actionError" class="profile__action-error">{{ actionError }}</p>

      <section class="profile__stats">
        <button
          v-for="item in stats"
          :key="item.key"
          type="button"
          class="profile__stat"
          :disabled="!item.type"
          @click="item.type && goFollows(item.type)"
        >
          <b>{{ item.value }}</b>
          <span>{{ item.label }}</span>
        </button>
      </section>

      <section v-if="commonLoaded && commonFollows.length" class="profile__common">
        <div class="profile__section-head">
          <h2>共同关注</h2>
          <span>{{ commonFollows.length }}</span>
        </div>
        <div class="profile__common-row">
          <button
            v-for="user in commonFollows.slice(0, 8)"
            :key="user.userId"
            type="button"
            class="profile__common-user"
            @click="goUser(user.userId)"
          >
            <span class="profile__common-avatar">
              <img
                v-if="user.icon && !failedAvatars.has(String(user.userId))"
                :src="user.icon"
                :alt="user.nickname"
                loading="lazy"
                @error="markAvatarFailed(user.userId)"
              />
              <span v-else>{{ (user.nickname || '乐').slice(0, 1) }}</span>
            </span>
            <span class="profile__common-name">{{ user.nickname || '乐迷' }}</span>
          </button>
        </div>
      </section>

      <section class="profile__posts">
        <div class="profile__section-head">
          <h2>{{ isSelf ? '我的动态' : 'TA 的动态' }}</h2>
          <span>{{ Number(profile.posts || 0) }}</span>
        </div>

        <p v-if="postsError" class="profile__posts-error">
          {{ postsError }}
          <button type="button" @click="loadPosts(true)">重试</button>
        </p>

        <template v-else>
          <PostCard v-for="post in posts" :key="post.id" :post="post" @open="openPost(post.id)" />

          <p v-if="postsLoaded && !posts.length" class="profile__empty">
            {{ isSelf ? '你还没有发布动态，去社区记录一场现场吧。' : 'TA 还没有发布动态。' }}
          </p>

          <button
            v-if="postHasMore"
            type="button"
            class="profile__more"
            :disabled="postsLoading"
            @click="loadPosts(false)"
          >
            {{ postsLoading ? '加载中…' : '加载更多动态' }}
          </button>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped lang="scss">
.profile {
  padding-bottom: 28px;
}

.profile__head {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 22px 18px 14px;
  background: linear-gradient(165deg, #241338, #1a1020 58%, var(--bg-base));
}

.profile__avatar {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #7b61ff, #ff3d71);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 23px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.profile__who {
  min-width: 0;
  flex: 1;

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

.profile__badges {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 7px;
}

.profile__level {
  padding: 2px 7px;
  border-radius: 6px;
  background: linear-gradient(92deg, #ffcb47, #ffa726);
  color: #1a1020;
  font-size: 8px;
  font-weight: 800;
}

.profile__city {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--text-3);
  font-size: 9px;
}

.profile__follow {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 31px;
  padding: 0 13px;
  flex: 0 0 auto;
  border: 1px solid rgba(255, 61, 113, 0.4);
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 10.5px;
  font-weight: 700;
  cursor: pointer;

  &.is-following {
    border-color: var(--line);
    background: transparent;
    color: var(--text-3);
  }

  &:disabled {
    opacity: 0.6;
  }
}

.profile__intro {
  margin: 0;
  padding: 0 18px 16px;
  background: linear-gradient(180deg, #1a1020, var(--bg-base));
  color: var(--text-2);
  font-size: 11px;
  line-height: 1.65;
  word-break: break-word;
}

.profile__action-error {
  margin: 0 16px 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.1);
  color: #ff9aa3;
  font-size: 10px;
}

.profile__stats {
  display: flex;
  margin: 0 16px;
  padding: 14px 0;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
}

.profile__stat {
  position: relative;
  flex: 1;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: center;
  cursor: pointer;

  &:disabled {
    cursor: default;
  }

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
    color: var(--text-1);
    font-size: 17px;
    font-weight: 800;
    letter-spacing: -0.3px;
    font-variant-numeric: tabular-nums;
  }

  span {
    display: block;
    margin-top: 5px;
    color: var(--text-3);
    font-size: 9.5px;
  }

  &:active:not(:disabled) {
    opacity: 0.7;
  }
}

.profile__section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 20px 16px 10px;

  h2 {
    margin: 0;
    font-size: 14px;
    font-weight: 800;
    letter-spacing: -0.2px;
  }

  span {
    color: var(--text-3);
    font-size: 10px;
    font-variant-numeric: tabular-nums;
  }
}

.profile__common-row {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding: 0 16px 4px;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.profile__common-user {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px 5px 5px;
  flex: 0 0 auto;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-card);
  color: var(--text-2);
  font-size: 10px;
  cursor: pointer;

  &:active {
    opacity: 0.7;
  }
}

.profile__common-avatar {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  overflow: hidden;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  color: #fff;
  font-size: 9px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.profile__common-name {
  max-width: 76px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile__posts {
  margin-top: 4px;
}

.profile__posts-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 0 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.1);
  color: #ff9aa3;
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

.profile__empty {
  margin: 18px 16px;
  padding: 26px 16px;
  border: 1px dashed var(--line);
  border-radius: 14px;
  color: var(--text-3);
  font-size: 10.5px;
  text-align: center;
}

.profile__more {
  display: block;
  width: calc(100% - 32px);
  margin: 2px 16px 0;
  padding: 11px 0;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: transparent;
  color: var(--text-2);
  font-size: 10.5px;
  cursor: pointer;

  &:disabled {
    opacity: 0.6;
  }
}

.profile__skeleton {
  margin: 16px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.profile__skeleton-head {
  display: flex;
  align-items: center;
  gap: 12px;

  > div {
    flex: 1;
  }

  i {
    display: block;
    height: 10px;
    border-radius: 4px;

    &:first-child {
      width: 42%;
      margin-bottom: 7px;
    }

    &:last-child {
      width: 26%;
      height: 8px;
    }
  }
}

.profile__skeleton-avatar {
  width: 62px;
  height: 62px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.profile__skeleton-line {
  display: block;
  width: 90%;
  height: 10px;
  margin-top: 18px;
  border-radius: 5px;

  &--short {
    width: 56%;
    margin-top: 9px;
  }
}

.profile__skeleton-avatar,
.profile__skeleton-head i,
.profile__skeleton-line {
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: profile-shimmer 1.6s infinite linear;
}

@keyframes profile-shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
