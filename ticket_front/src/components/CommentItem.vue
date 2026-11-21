<script setup>
import { computed, ref } from 'vue'

import { formatRelativeTime } from '@/utils/format'

const props = defineProps({
  comment: { type: Object, required: true },
  canDelete: { type: Boolean, default: false },
})

defineEmits(['delete'])

const avatarFailed = ref(false)

const nickname = computed(() => props.comment.nickname || '乐迷')
const initial = computed(() => nickname.value.slice(0, 1).toUpperCase())
</script>

<template>
  <article class="comment-item">
    <div class="comment-item__avatar">
      <img
        v-if="comment.icon && !avatarFailed"
        :src="comment.icon"
        :alt="nickname"
        loading="lazy"
        @error="avatarFailed = true"
      />
      <span v-else>{{ initial }}</span>
    </div>

    <div class="comment-item__body">
      <header class="comment-item__head">
        <p class="comment-item__name">{{ nickname }}</p>
        <button
          v-if="canDelete"
          type="button"
          class="comment-item__delete"
          @click="$emit('delete', comment)"
        >
          删除
        </button>
      </header>
      <p class="comment-item__content">{{ comment.content }}</p>
      <p class="comment-item__time">{{ formatRelativeTime(comment.createTime) }}</p>
    </div>
  </article>
</template>

<style scoped lang="scss">
.comment-item {
  display: flex;
  gap: 10px;
  padding: 13px 0;
  border-bottom: 1px solid rgba(42, 42, 54, 0.6);

  &:last-child {
    border-bottom: 0;
  }
}

.comment-item__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  color: #fff;
  font-size: 11px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.comment-item__body {
  min-width: 0;
  flex: 1;
}

.comment-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.comment-item__name {
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 11.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.comment-item__delete {
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-3);
  font-size: 9.5px;
  cursor: pointer;

  &:active {
    color: var(--danger);
  }
}

.comment-item__content {
  margin: 5px 0 0;
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-line;
  word-break: break-word;
}

.comment-item__time {
  margin: 5px 0 0;
  color: var(--text-3);
  font-size: 9px;
}
</style>
