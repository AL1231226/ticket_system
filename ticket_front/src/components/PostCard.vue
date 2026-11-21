<script setup>
import { computed, ref } from 'vue'

import AppIcon from './AppIcon.vue'
import { formatRelativeTime } from '@/utils/format'

const props = defineProps({
  post: { type: Object, required: true },
})

const emit = defineEmits(['open'])

const authorFailed = ref(false)
const failedImages = ref(new Set())

const nickname = computed(() => props.post.nickname || '乐迷')
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())
const images = computed(() =>
  Array.isArray(props.post.images) ? props.post.images.slice(0, 9) : [],
)
const imageLayout = computed(() => {
  if (images.value.length === 1) return 'post-card__images--single'
  if (images.value.length === 2) return 'post-card__images--double'
  return 'post-card__images--grid'
})

function markImageFailed(index) {
  const next = new Set(failedImages.value)
  next.add(index)
  failedImages.value = next
}
</script>

<template>
  <article
    class="post-card"
    role="link"
    tabindex="0"
    @click="emit('open', post)"
    @keydown.enter="emit('open', post)"
  >
    <header class="post-card__head">
      <div class="post-card__avatar">
        <img
          v-if="post.icon && !authorFailed"
          :src="post.icon"
          :alt="nickname"
          loading="lazy"
          @error="authorFailed = true"
        />
        <span v-else>{{ initial }}</span>
      </div>

      <div class="post-card__who">
        <p class="post-card__name">{{ nickname }}</p>
        <p class="post-card__time">{{ formatRelativeTime(post.createTime) }}</p>
      </div>

      <span v-if="post.showName" class="post-card__show">
        <AppIcon name="ticket" :size="12" />
        <span class="post-card__show-name">{{ post.showName }}</span>
      </span>
    </header>

    <h2 v-if="post.title" class="post-card__title">{{ post.title }}</h2>
    <p v-if="post.content" class="post-card__content">{{ post.content }}</p>

    <div v-if="images.length" class="post-card__images" :class="imageLayout">
      <div v-for="(url, index) in images" :key="`${url}-${index}`" class="post-card__image">
        <img
          v-if="!failedImages.has(index)"
          :src="url"
          alt=""
          loading="lazy"
          @error="markImageFailed(index)"
        />
        <span v-else class="post-card__image-fallback">
          <AppIcon name="ticket" :size="18" />
        </span>
      </div>
    </div>

    <footer class="post-card__foot">
      <span class="post-card__stat">
        <AppIcon name="heart" :size="15" />
        {{ Number(post.liked || 0) }}
      </span>
      <span class="post-card__stat">
        <AppIcon name="comment" :size="15" />
        {{ Number(post.comments || 0) }}
      </span>
    </footer>
  </article>
</template>

<style scoped lang="scss">
.post-card {
  margin: 0 16px 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(36, 36, 46, 0.66), var(--bg-card));
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.18);
  cursor: pointer;
  animation: card-rise 0.28s ease both;
  transition:
    border-color 0.18s ease,
    transform 0.18s ease;

  &:active {
    border-color: rgba(255, 61, 113, 0.36);
    transform: scale(0.995);
  }
}

.post-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.post-card__avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  color: #fff;
  font-size: 13px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-card__who {
  min-width: 0;
  flex: 1;
}

.post-card__name {
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 12.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-card__time {
  margin: 3px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
}

.post-card__show {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  max-width: 118px;
  min-width: 0;
  padding: 3px 7px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 999px;
  background: var(--brand-soft);
  color: #ff8baa;
  font-size: 9px;
}

.post-card__show-name {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-card__title {
  margin: 12px 0 0;
  color: var(--text-1);
  font-size: 14.5px;
  font-weight: 800;
  letter-spacing: -0.2px;
  line-height: 1.45;
}

.post-card__content {
  display: -webkit-box;
  overflow: hidden;
  margin: 8px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.72;
  white-space: pre-line;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.post-card__images {
  display: grid;
  gap: 5px;
  margin-top: 10px;
  overflow: hidden;
  border-radius: 10px;

  &--single {
    grid-template-columns: 1fr;

    .post-card__image {
      aspect-ratio: 16 / 10;
      max-height: 210px;
    }
  }

  &--double {
    grid-template-columns: repeat(2, 1fr);

    .post-card__image {
      aspect-ratio: 1;
    }
  }

  &--grid {
    grid-template-columns: repeat(3, 1fr);

    .post-card__image {
      aspect-ratio: 1;
    }
  }
}

.post-card__image {
  position: relative;
  overflow: hidden;
  background: var(--bg-elevated);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-card__image-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--text-3);
}

.post-card__foot {
  display: flex;
  gap: 22px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.post-card__stat {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-3);
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
}

@keyframes card-rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
