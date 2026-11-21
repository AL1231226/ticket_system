<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getFollowList } from '@/api/community'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import StateView from '@/components/StateView.vue'

const TYPES = [
  { value: 'followees', label: '关注' },
  { value: 'fans', label: '粉丝' },
]

const route = useRoute()
const router = useRouter()

const type = ref(normalizeType(route.query.type))
const users = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const failedAvatars = ref(new Set())

let seq = 0

const userId = computed(() => route.params.id)
const emptyText = computed(() =>
  type.value === 'fans' ? '还没有人关注 TA。' : 'TA 还没有关注任何人。',
)

onMounted(() => load(true))

watch(
  () => route.params.id,
  () => {
    if (route.name === 'user-follows') {
      reset()
      load(true)
    }
  },
)

watch(
  () => route.query.type,
  (value) => {
    if (route.name !== 'user-follows') return
    const next = normalizeType(value)
    if (next !== type.value) {
      type.value = next
      reset()
      load(true)
    }
  },
)

function normalizeType(value) {
  return value === 'fans' ? 'fans' : 'followees'
}

function reset() {
  users.value = []
  cursor.value = null
  hasMore.value = false
  loaded.value = false
  error.value = ''
  failedAvatars.value = new Set()
}

function switchType(next) {
  if (type.value === next) return
  type.value = next
  router.replace({ path: `/user/${userId.value}/follows`, query: { type: next } })
  reset()
  load(true)
}

async function load(resetList = false) {
  if (!resetList && (loading.value || !hasMore.value)) return

  const mySeq = ++seq
  loading.value = true
  error.value = ''
  try {
    const res = await getFollowList(userId.value, {
      type: type.value,
      lastId: resetList ? undefined : cursor.value?.lastId,
      count: 20,
    })
    if (mySeq !== seq) return

    const list = Array.isArray(res?.users) ? res.users : []
    users.value = resetList ? list : [...users.value, ...list]
    cursor.value = res?.nextCursor || null
    hasMore.value = Boolean(res?.hasMore)
    loaded.value = true
  } catch (e) {
    if (mySeq !== seq) return
    error.value = e.message
    loaded.value = true
  } finally {
    if (mySeq === seq) {
      loading.value = false
    }
  }
}

function goUser(id) {
  router.push(`/user/${id}`)
}

function markAvatarFailed(id) {
  const next = new Set(failedAvatars.value)
  next.add(String(id))
  failedAvatars.value = next
}
</script>

<template>
  <div class="follows page">
    <AppBar title="关注与粉丝" />

    <div class="follows__tabs" role="tablist" aria-label="关注与粉丝">
      <button
        v-for="item in TYPES"
        :key="item.value"
        type="button"
        role="tab"
        :aria-selected="type === item.value"
        :class="{ 'is-active': type === item.value }"
        @click="switchType(item.value)"
      >
        {{ item.label }}
      </button>
    </div>

    <StateView
      v-if="error && !users.length"
      icon="users"
      title="列表加载失败"
      :description="error"
      action-text="重试"
      @action="load(true)"
    />

    <template v-else>
      <p v-if="error && users.length" class="follows__error">
        {{ error }}
        <button type="button" @click="load(true)">重试</button>
      </p>

      <div v-if="!loaded && loading" class="follows__skeletons">
        <div v-for="i in 3" :key="i" class="follows-skeleton">
          <i class="follows-skeleton__avatar" />
          <div>
            <i />
            <i />
          </div>
        </div>
      </div>

      <div v-else class="follows__list">
        <button
          v-for="user in users"
          :key="user.userId"
          type="button"
          class="follows__item"
          @click="goUser(user.userId)"
        >
          <span class="follows__avatar">
            <img
              v-if="user.icon && !failedAvatars.has(String(user.userId))"
              :src="user.icon"
              :alt="user.nickname"
              loading="lazy"
              @error="markAvatarFailed(user.userId)"
            />
            <span v-else>{{ (user.nickname || '乐').slice(0, 1) }}</span>
          </span>
          <span class="follows__body">
            <b>{{ user.nickname || '乐迷' }}</b>
            <small>{{ user.introduce || '还没有填写简介' }}</small>
          </span>
          <AppIcon name="chevron-right" :size="16" class="follows__arrow" />
        </button>

        <p v-if="loaded && !users.length" class="follows__empty">{{ emptyText }}</p>

        <button
          v-if="hasMore"
          type="button"
          class="follows__more"
          :disabled="loading"
          @click="load(false)"
        >
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.follows {
  padding-bottom: 24px;
}

.follows__tabs {
  display: flex;
  gap: 3px;
  margin: 12px 16px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(26, 26, 34, 0.85);

  button {
    flex: 1;
    height: 32px;
    border: 0;
    border-radius: 999px;
    background: transparent;
    color: var(--text-3);
    font-size: 11.5px;
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
}

.follows__list {
  margin: 0 16px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
}

.follows__item {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.6);
  background: transparent;
  text-align: left;
  cursor: pointer;

  &:last-of-type {
    border-bottom: 0;
  }

  &:active {
    background: rgba(255, 255, 255, 0.025);
  }
}

.follows__avatar {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
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

.follows__body {
  min-width: 0;
  flex: 1;

  b {
    display: block;
    overflow: hidden;
    color: var(--text-1);
    font-size: 12.5px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    display: block;
    overflow: hidden;
    margin-top: 4px;
    color: var(--text-3);
    font-size: 9.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.follows__arrow {
  flex: 0 0 auto;
  color: var(--text-3);
}

.follows__empty {
  margin: 0;
  padding: 30px 16px;
  color: var(--text-3);
  font-size: 10.5px;
  text-align: center;
}

.follows__more {
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px;
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

.follows__error {
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

.follows__skeletons {
  margin: 0 16px;
  padding: 4px 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
}

.follows-skeleton {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 0;

  > div {
    flex: 1;
  }

  i {
    display: block;
    height: 9px;
    border-radius: 4px;

    &:first-child {
      width: 38%;
      margin-bottom: 7px;
    }

    &:last-child {
      width: 66%;
      height: 8px;
    }
  }
}

.follows-skeleton__avatar {
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.follows-skeleton__avatar,
.follows-skeleton i {
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: follows-shimmer 1.6s infinite linear;
}

@keyframes follows-shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
