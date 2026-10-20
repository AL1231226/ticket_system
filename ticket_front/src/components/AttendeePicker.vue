<script setup>
import { computed, ref } from 'vue'

import AppIcon from './AppIcon.vue'

const props = defineProps({
  attendees: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] },
  max: { type: Number, default: 1 },
})

const emit = defineEmits(['update:modelValue', 'add'])

const hint = ref('')

const selectedSet = computed(() => new Set(props.modelValue))
const verifiedCount = computed(
  () => props.attendees.filter((item) => item.verifyStatus === 1).length,
)

function toggle(attendee) {
  if (attendee.verifyStatus !== 1) {
    hint.value = '这位观演人还未完成实名认证'
    return
  }

  const ids = [...props.modelValue]
  const index = ids.indexOf(attendee.id)
  if (index >= 0) {
    ids.splice(index, 1)
    hint.value = ''
  } else if (ids.length >= props.max) {
    hint.value = `本单最多选择 ${props.max} 位观演人`
    return
  } else {
    ids.push(attendee.id)
    hint.value = ''
  }
  emit('update:modelValue', ids)
}

function initial(name) {
  return (name || '观').slice(0, 1)
}
</script>

<template>
  <section class="picker">
    <div class="picker__head">
      <div>
        <h3>观演人</h3>
        <p>一票一人，请选择 {{ max }} 位</p>
      </div>
      <span class="picker__count" :class="{ 'is-full': modelValue.length === max }">
        {{ modelValue.length }} / {{ max }}
      </span>
    </div>

    <div v-if="attendees.length" class="picker__list">
      <button
        v-for="attendee in attendees"
        :key="attendee.id"
        type="button"
        class="attendee-card"
        :class="{
          'is-selected': selectedSet.has(attendee.id),
          'is-unverified': attendee.verifyStatus !== 1,
        }"
        @click="toggle(attendee)"
      >
        <span class="attendee-card__avatar">{{ initial(attendee.realName) }}</span>
        <span class="attendee-card__info">
          <b>{{ attendee.realName }}</b>
          <small>{{ attendee.idCard }}</small>
        </span>
        <span v-if="attendee.verifyStatus === 1" class="attendee-card__verified">已实名</span>
        <span v-else class="attendee-card__unverified">未认证</span>
        <span class="attendee-card__check" :class="{ 'is-on': selectedSet.has(attendee.id) }">
          <AppIcon v-if="selectedSet.has(attendee.id)" name="check" :size="13" />
        </span>
      </button>
    </div>

    <div v-else class="picker__empty">
      还没有观演人。强实名场次需要先添加观演人，才能提交订单。
    </div>

    <button class="picker__add" type="button" @click="emit('add')">
      <AppIcon name="plus" :size="15" />
      添加观演人
    </button>

    <p v-if="hint" class="picker__hint">{{ hint }}</p>
    <p v-else-if="attendees.length && verifiedCount < attendees.length" class="picker__hint">
      未实名认证的观演人暂不能选择。
    </p>
  </section>
</template>

<style scoped lang="scss">
.picker {
  margin-top: 18px;
}

.picker__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
    letter-spacing: -0.2px;
  }

  p {
    margin: 4px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
  }
}

.picker__count {
  color: var(--text-3);
  font-size: 10px;
  font-variant-numeric: tabular-nums;

  &.is-full {
    color: var(--success);
  }
}

.picker__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attendee-card {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg-card);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;

  &.is-selected {
    border-color: rgba(255, 61, 113, 0.5);
    background: var(--brand-soft);
  }

  &.is-unverified {
    opacity: 0.55;
  }
}

.attendee-card__avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #7b61ff, #ff3d71);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.attendee-card__info {
  min-width: 0;
  flex: 1;

  b {
    display: block;
    color: var(--text-1);
    font-size: 12.5px;
    font-weight: 700;
  }

  small {
    display: block;
    margin-top: 4px;
    color: var(--text-3);
    font-size: 9.5px;
    font-variant-numeric: tabular-nums;
  }
}

.attendee-card__verified,
.attendee-card__unverified {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 8px;
}

.attendee-card__verified {
  background: rgba(46, 213, 115, 0.1);
  color: var(--success);
}

.attendee-card__unverified {
  background: rgba(255, 203, 71, 0.1);
  color: var(--accent);
}

.attendee-card__check {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: #fff;

  &.is-on {
    border-color: var(--brand);
    background: var(--brand);
  }
}

.picker__empty {
  padding: 14px;
  border: 1px dashed var(--line);
  border-radius: 11px;
  color: var(--text-3);
  font-size: 10px;
  line-height: 1.7;
}

.picker__add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  height: 38px;
  margin-top: 9px;
  border: 1px dashed var(--line);
  border-radius: 9px;
  background: transparent;
  color: var(--text-2);
  font-size: 10.5px;
  cursor: pointer;

  &:active {
    background: rgba(255, 255, 255, 0.03);
  }
}

.picker__hint {
  margin: 8px 0 0;
  color: var(--accent);
  font-size: 9.5px;
  line-height: 1.5;
}
</style>
