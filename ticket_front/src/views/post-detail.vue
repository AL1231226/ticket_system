<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  createComment,
  deleteComment,
  deletePost,
  getPostComments,
  getPostDetail,
  toggleFollow,
  togglePostLike,
} from '@/api/community'
import { getUserProfile } from '@/api/user'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import CommentItem from '@/components/CommentItem.vue'
import StateView from '@/components/StateView.vue'
import { useUserStore } from '@/stores/user'
import { formatRelativeTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const post = ref(null)
const loading = ref(true)
const error = ref('')
const actionError = ref('')

const followBusy = ref(false)
const authorFollowing = ref(false)

const likeBusy = ref(false)

const comments = ref([])
const commentCursor = ref(null)
const commentHasMore = ref(false)
const commentsLoading = ref(false)
const commentsLoaded = ref(false)
const commentsError = ref('')
const commentText = ref('')
const commentSending = ref(false)

const authorFailed = ref(false)
const failedImages = ref(new Set())
const previewVisible = ref(false)
const previewIndex = ref(0)

const confirmState = ref(null)
const deleting = ref(false)

const postId = computed(() => route.params.id)
const images = computed(() => (Array.isArray(post.value?.images) ? post.value.images : []))
const imageLayout = computed(() => {
  if (images.value.length === 1) return 'post-detail__images--single'
  if (images.value.length === 2) return 'post-detail__images--double'
  return 'post-detail__images--grid'
})
const nickname = computed(() => post.value?.nickname || '乐迷')
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())
const isMine = computed(() => {
  const uid = userStore.userInfo?.id
  return Boolean(uid && post.value?.userId && String(uid) === String(post.value.userId))
})
const commentCount = computed(() => Number(post.value?.comments || 0))

const confirmTitle = computed(() =>
  confirmState.value?.kind === 'post' ? '删除这条动态？' : '删除这条评论？',
)
const confirmMessage = computed(() =>
  confirmState.value?.kind === 'post'
    ? '删除后将从社区消失，且不可恢复。'
    : '删除后这条评论将不再展示。',
)

onMounted(async () => {
  await ensureUser()
  const loaded = await loadPost()
  if (loaded) {
    loadComments(true)
  }
})

async function ensureUser() {
  if (!userStore.token || userStore.userInfo) return
  try {
    await userStore.fetchUserInfo()
  } catch {
    // 登录态失效由拦截器统一跳登录；这里不阻断公开详情的展示
  }
}

async function loadPost() {
  loading.value = true
  error.value = ''
  try {
    const detail = await getPostDetail(postId.value)
    post.value = detail
    if (detail?.userId && !isMine.value) {
      getAuthorProfile(detail.userId)
    }
    return true
  } catch (e) {
    error.value = e.message
    return false
  } finally {
    loading.value = false
  }
}

async function getAuthorProfile(userId) {
  try {
    const profile = await getUserProfile(userId)
    authorFollowing.value = Boolean(profile?.isFollowing)
  } catch {
    // 关注状态拿不到不影响正文阅读，保留默认「关注」
  }
}

function retryAll() {
  loadPost().then((loaded) => {
    if (loaded) {
      loadComments(true)
    }
  })
}

function requireLogin() {
  if (userStore.token) return true
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

function goLogin() {
  requireLogin()
}

async function toggleLike() {
  if (!requireLogin() || likeBusy.value || !post.value) return
  likeBusy.value = true
  actionError.value = ''
  try {
    const res = await togglePostLike(post.value.id)
    post.value.likedByMe = Boolean(res?.liked)
    post.value.liked = Number(res?.likedCount || 0)
  } catch (e) {
    actionError.value = e.message
  } finally {
    likeBusy.value = false
  }
}

async function toggleFollowAuthor() {
  if (!requireLogin() || followBusy.value || !post.value) return
  followBusy.value = true
  actionError.value = ''
  try {
    const res = await toggleFollow(post.value.userId, !authorFollowing.value)
    authorFollowing.value = Boolean(res?.following)
  } catch (e) {
    actionError.value = e.message
  } finally {
    followBusy.value = false
  }
}

async function loadComments(reset = false) {
  if (commentsLoading.value) return
  if (!reset && !commentHasMore.value) return

  commentsLoading.value = true
  commentsError.value = ''
  try {
    const res = await getPostComments(postId.value, {
      lastId: reset ? undefined : commentCursor.value?.lastId,
      offset: reset ? 0 : commentCursor.value?.offset || 0,
      count: 10,
    })
    const list = Array.isArray(res?.comments) ? res.comments : []
    comments.value = reset ? list : [...comments.value, ...list]
    commentCursor.value = res?.nextCursor || null
    commentHasMore.value = Boolean(res?.hasMore)
    commentsLoaded.value = true
  } catch (e) {
    commentsError.value = e.message
    commentsLoaded.value = true
  } finally {
    commentsLoading.value = false
  }
}

async function sendComment() {
  if (!requireLogin() || commentSending.value || !post.value) return
  const content = commentText.value.trim()
  if (!content) return
  if (content.length > 512) {
    actionError.value = '评论不能超过 512 字'
    return
  }

  commentSending.value = true
  actionError.value = ''
  try {
    const created = await createComment({ postId: post.value.id, content })
    comments.value = [created, ...comments.value]
    post.value.comments = commentCount.value + 1
    commentText.value = ''
  } catch (e) {
    actionError.value = e.message
  } finally {
    commentSending.value = false
  }
}

function isCommentMine(comment) {
  const uid = userStore.userInfo?.id
  return Boolean(uid && comment?.userId && String(uid) === String(comment.userId))
}

function askDeletePost() {
  confirmState.value = { kind: 'post' }
}

function askDeleteComment(comment) {
  confirmState.value = { kind: 'comment', comment }
}

function closeConfirm() {
  if (deleting.value) return
  confirmState.value = null
}

async function runConfirm() {
  if (!confirmState.value || deleting.value) return
  deleting.value = true
  actionError.value = ''
  try {
    if (confirmState.value.kind === 'post') {
      await deletePost(post.value.id)
      confirmState.value = null
      router.replace('/community')
      return
    }
    const target = confirmState.value.comment
    await deleteComment(target.id)
    comments.value = comments.value.filter((item) => item.id !== target.id)
    post.value.comments = Math.max(0, commentCount.value - 1)
    confirmState.value = null
  } catch (e) {
    actionError.value = e.message
    confirmState.value = null
  } finally {
    deleting.value = false
  }
}

function markImageFailed(index) {
  const next = new Set(failedImages.value)
  next.add(index)
  failedImages.value = next
}

function openPreview(index) {
  previewIndex.value = index
  previewVisible.value = true
}

function goShow() {
  if (post.value?.showId) {
    router.push(`/show/${post.value.showId}`)
  }
}
</script>

<template>
  <div class="post-detail page">
    <AppBar title="动态详情">
      <template #right>
        <button
          v-if="isMine && post"
          type="button"
          class="post-detail__delete-post"
          @click="askDeletePost"
        >
          删除
        </button>
      </template>
    </AppBar>

    <div v-if="loading" class="post-detail__skeleton">
      <div class="post-detail__skeleton-head">
        <i class="post-detail__skeleton-avatar" />
        <div>
          <i />
          <i />
        </div>
      </div>
      <i class="post-detail__skeleton-line" />
      <i class="post-detail__skeleton-line post-detail__skeleton-line--short" />
      <i class="post-detail__skeleton-image" />
    </div>

    <StateView
      v-else-if="error"
      icon="community"
      title="动态加载失败"
      :description="error"
      action-text="重试"
      @action="retryAll"
    />

    <template v-else-if="post">
      <article class="post-detail__main">
        <header class="post-detail__head">
          <div class="post-detail__avatar">
            <img
              v-if="post.icon && !authorFailed"
              :src="post.icon"
              :alt="nickname"
              @error="authorFailed = true"
            />
            <span v-else>{{ initial }}</span>
          </div>

          <div class="post-detail__who">
            <p class="post-detail__name">{{ nickname }}</p>
            <p class="post-detail__time">{{ formatRelativeTime(post.createTime) }}</p>
          </div>

          <button
            v-if="!isMine"
            type="button"
            class="post-detail__follow"
            :class="{ 'is-following': authorFollowing }"
            :disabled="followBusy"
            @click="toggleFollowAuthor"
          >
            <AppIcon :name="authorFollowing ? 'check' : 'plus'" :size="12" />
            {{ authorFollowing ? '已关注' : '关注' }}
          </button>
        </header>

        <button
          v-if="post.showName && post.showId"
          type="button"
          class="post-detail__show"
          @click="goShow"
        >
          <AppIcon name="ticket" :size="13" />
          <span>{{ post.showName }}</span>
          <AppIcon name="chevron-right" :size="12" />
        </button>

        <h1 v-if="post.title" class="post-detail__title">{{ post.title }}</h1>
        <p v-if="post.content" class="post-detail__content">{{ post.content }}</p>

        <div v-if="images.length" class="post-detail__images" :class="imageLayout">
          <button
            v-for="(url, index) in images"
            :key="`${url}-${index}`"
            type="button"
            class="post-detail__image"
            @click="openPreview(index)"
          >
            <img
              v-if="!failedImages.has(index)"
              :src="url"
              alt=""
              loading="lazy"
              @error="markImageFailed(index)"
            />
            <span v-else class="post-detail__image-fallback">
              <AppIcon name="ticket" :size="20" />
            </span>
          </button>
        </div>

        <footer class="post-detail__stats">
          <button
            type="button"
            class="post-detail__like"
            :class="{ 'is-liked': post.likedByMe }"
            :disabled="likeBusy"
            @click="toggleLike"
          >
            <AppIcon name="heart" :size="17" />
            <span>{{ Number(post.liked || 0) }}</span>
          </button>
          <span class="post-detail__stat">
            <AppIcon name="comment" :size="16" />
            {{ commentCount }}
          </span>
        </footer>

        <p v-if="actionError" class="post-detail__action-error">{{ actionError }}</p>
      </article>

      <section class="post-detail__comments">
        <header class="post-detail__comments-head">
          <h2>评论</h2>
          <span>{{ commentCount }}</span>
        </header>

        <p v-if="commentsError" class="post-detail__comments-error">
          {{ commentsError }}
          <button type="button" @click="loadComments(true)">重试</button>
        </p>

        <template v-else>
          <CommentItem
            v-for="item in comments"
            :key="item.id"
            :comment="item"
            :can-delete="isCommentMine(item)"
            @delete="askDeleteComment"
          />

          <p v-if="commentsLoaded && !comments.length" class="post-detail__comments-empty">
            还没有评论，来抢沙发。
          </p>

          <button
            v-if="commentHasMore"
            type="button"
            class="post-detail__more"
            :disabled="commentsLoading"
            @click="loadComments(false)"
          >
            {{ commentsLoading ? '加载中…' : '加载更多评论' }}
          </button>
        </template>
      </section>

      <div class="post-detail__bar">
        <template v-if="userStore.token">
          <input
            v-model="commentText"
            class="post-detail__input"
            type="text"
            maxlength="512"
            placeholder="说点什么…"
            @keyup.enter="sendComment"
          />
          <button
            type="button"
            class="post-detail__send"
            :disabled="commentSending || !commentText.trim()"
            @click="sendComment"
          >
            {{ commentSending ? '发送中' : '发送' }}
          </button>
        </template>
        <button v-else type="button" class="post-detail__bar-login" @click="goLogin">
          登录后参与评论
        </button>
      </div>
    </template>

    <div v-if="confirmState" class="confirm-mask" @click.self="closeConfirm">
      <div class="confirm-card">
        <h3>{{ confirmTitle }}</h3>
        <p>{{ confirmMessage }}</p>
        <div class="confirm-card__actions">
          <button type="button" class="ghost" :disabled="deleting" @click="closeConfirm">
            再想想
          </button>
          <button type="button" class="danger" :disabled="deleting" @click="runConfirm">
            {{ deleting ? '删除中…' : '删除' }}
          </button>
        </div>
      </div>
    </div>

    <van-image-preview
      v-model:show="previewVisible"
      :images="images"
      :start-position="previewIndex"
    />
  </div>
</template>

<style scoped lang="scss">
.post-detail {
  padding-bottom: calc(78px + env(safe-area-inset-bottom));
}

.post-detail__delete-post {
  padding: 6px 2px;
  border: 0;
  background: transparent;
  color: #ff8baa;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
}

.post-detail__main {
  margin: 12px 16px 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(36, 36, 46, 0.66), var(--bg-card));
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.18);
}

.post-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.post-detail__avatar {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  color: #fff;
  font-size: 14px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-detail__who {
  min-width: 0;
  flex: 1;
}

.post-detail__name {
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-detail__time {
  margin: 3px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
}

.post-detail__follow {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 29px;
  padding: 0 12px;
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

.post-detail__show {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  margin-top: 12px;
  padding: 5px 9px;
  overflow: hidden;
  border: 1px solid rgba(255, 61, 113, 0.16);
  border-radius: 999px;
  background: var(--brand-soft);
  color: #ff8baa;
  font-size: 9.5px;
  cursor: pointer;

  span {
    overflow: hidden;
    min-width: 0;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.post-detail__title {
  margin: 14px 0 0;
  color: var(--text-1);
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.2px;
  line-height: 1.45;
}

.post-detail__content {
  margin: 10px 0 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-line;
  word-break: break-word;
}

.post-detail__images {
  display: grid;
  gap: 6px;
  margin-top: 12px;
  overflow: hidden;
  border-radius: 10px;

  &--single {
    grid-template-columns: 1fr;

    .post-detail__image {
      aspect-ratio: 16 / 10;
      max-height: 320px;
    }
  }

  &--double {
    grid-template-columns: repeat(2, 1fr);

    .post-detail__image {
      aspect-ratio: 1;
    }
  }

  &--grid {
    grid-template-columns: repeat(3, 1fr);

    .post-detail__image {
      aspect-ratio: 1;
    }
  }
}

.post-detail__image {
  position: relative;
  display: block;
  overflow: hidden;
  padding: 0;
  border: 0;
  background: var(--bg-elevated);
  cursor: zoom-in;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-detail__image-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--text-3);
}

.post-detail__stats {
  display: flex;
  align-items: center;
  gap: 22px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.post-detail__like,
.post-detail__stat {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-3);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.post-detail__like {
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;

  &.is-liked {
    color: var(--brand);

    svg {
      fill: rgba(255, 61, 113, 0.18);
    }
  }

  &:disabled {
    opacity: 0.6;
  }
}

.post-detail__action-error {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.1);
  color: #ff9aa3;
  font-size: 10px;
  line-height: 1.5;
}

.post-detail__comments {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.post-detail__comments-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;

  h2 {
    margin: 0;
    color: var(--text-1);
    font-size: 13px;
    font-weight: 800;
  }

  span {
    color: var(--text-3);
    font-size: 10px;
    font-variant-numeric: tabular-nums;
  }
}

.post-detail__comments-empty {
  margin: 16px 0;
  color: var(--text-3);
  font-size: 10.5px;
  text-align: center;
}

.post-detail__comments-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 12px 0 4px;
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

.post-detail__more {
  display: block;
  width: 100%;
  margin-top: 12px;
  padding: 10px 0;
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

.post-detail__bar {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  max-width: 480px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.post-detail__input {
  height: 38px;
  min-width: 0;
  padding: 0 13px;
  flex: 1;
  border: 1px solid var(--line);
  border-radius: 999px;
  outline: none;
  background: var(--bg-elevated);
  color: var(--text-1);
  font-size: 12px;

  &::placeholder {
    color: var(--text-3);
  }

  &:focus {
    border-color: rgba(255, 61, 113, 0.45);
  }
}

.post-detail__send {
  height: 38px;
  padding: 0 16px;
  flex: 0 0 auto;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  color: #fff;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
  }
}

.post-detail__bar-login {
  width: 100%;
  height: 38px;
  border: 1px solid rgba(255, 61, 113, 0.34);
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
}

.post-detail__skeleton {
  margin: 12px 16px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}

.post-detail__skeleton-head {
  display: flex;
  align-items: center;
  gap: 10px;

  > div {
    flex: 1;
  }

  i {
    display: block;
    height: 9px;
    border-radius: 4px;

    &:first-child {
      width: 42%;
      margin-bottom: 6px;
    }

    &:last-child {
      width: 26%;
      height: 8px;
    }
  }
}

.post-detail__skeleton-avatar {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.post-detail__skeleton-line {
  display: block;
  width: 90%;
  height: 11px;
  margin-top: 16px;
  border-radius: 5px;

  &--short {
    width: 58%;
    margin-top: 8px;
  }
}

.post-detail__skeleton-image {
  display: block;
  height: 150px;
  margin-top: 12px;
  border-radius: 10px;
}

.post-detail__skeleton-avatar,
.post-detail__skeleton-head i,
.post-detail__skeleton-line,
.post-detail__skeleton-image {
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: post-detail-shimmer 1.6s infinite linear;
}

@keyframes post-detail-shimmer {
  to {
    background-position: -200% 0;
  }
}

.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.62);
  backdrop-filter: blur(4px);
}

.confirm-card {
  width: 100%;
  max-width: 300px;
  padding: 22px 20px 18px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: var(--bg-card);
  text-align: center;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
  }

  p {
    margin: 9px 0 0;
    color: var(--text-3);
    font-size: 10.5px;
    line-height: 1.6;
  }
}

.confirm-card__actions {
  display: flex;
  gap: 9px;
  margin-top: 20px;

  button {
    flex: 1;
    height: 40px;
    border-radius: 10px;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;
  }

  .ghost {
    border: 1px solid var(--line);
    background: transparent;
    color: var(--text-2);
  }

  .danger {
    border: 0;
    background: var(--danger);
    color: #fff;

    &:disabled {
      opacity: 0.6;
    }
  }
}

:deep(.van-image-preview__index) {
  font-size: 12px;
}
</style>
