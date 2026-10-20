<script setup>
import { computed, ref, watch } from 'vue'

import { addAttendee } from '@/api/attendee'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  show: { type: Boolean, default: false },
})

const emit = defineEmits(['update:show', 'saved'])

const CARD_TYPES = [
  { value: 1, label: '身份证' },
  { value: 2, label: '港澳通行证' },
  { value: 3, label: '台湾通行证' },
  { value: 4, label: '护照' },
]

const ID_CARD_REGEX =
  /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/

const realName = ref('')
const idCard = ref('')
const idCardType = ref(1)
const submitting = ref(false)
const error = ref('')

const title = computed(
  () => CARD_TYPES.find((item) => item.value === idCardType.value)?.label || '证件',
)

watch(
  () => props.show,
  (open) => {
    if (open) {
      realName.value = ''
      idCard.value = ''
      idCardType.value = 1
      error.value = ''
    }
  },
)

function close() {
  if (submitting.value) return
  emit('update:show', false)
}

async function submit() {
  if (submitting.value) return
  error.value = ''

  const name = realName.value.trim()
  const card = idCard.value.trim().toUpperCase()

  if (!name) {
    error.value = '请填写观演人姓名'
    return
  }
  if (name.length > 32) {
    error.value = '姓名过长'
    return
  }
  if (!card) {
    error.value = '请填写证件号'
    return
  }
  if (idCardType.value === 1 && !ID_CARD_REGEX.test(card)) {
    error.value = '身份证号格式不正确'
    return
  }
  if (idCardType.value !== 1 && card.length < 4) {
    error.value = '证件号格式不正确'
    return
  }

  submitting.value = true
  try {
    const attendeeId = await addAttendee({
      realName: name,
      idCard: card,
      idCardType: idCardType.value,
    })
    emit('saved', attendeeId)
    emit('update:show', false)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="attendee-mask" @click.self="close">
      <div class="attendee-sheet">
        <header class="attendee-sheet__head">
          <div>
            <h3>添加观演人</h3>
            <p>一票一人，证件信息用于入场核验。</p>
          </div>
          <button type="button" aria-label="关闭" @click="close">
            <AppIcon name="x" :size="18" />
          </button>
        </header>

        <label class="attendee-field">
          <span>观演人姓名</span>
          <input
            v-model="realName"
            type="text"
            maxlength="32"
            autocomplete="off"
            placeholder="与证件保持一致"
          />
        </label>

        <div class="attendee-field">
          <span>证件类型</span>
          <div class="attendee-types">
            <button
              v-for="type in CARD_TYPES"
              :key="type.value"
              type="button"
              :class="{ 'is-active': idCardType === type.value }"
              @click="idCardType = type.value"
            >
              {{ type.label }}
            </button>
          </div>
        </div>

        <label class="attendee-field">
          <span>{{ title }}号码</span>
          <input
            v-model="idCard"
            type="text"
            autocomplete="off"
            :placeholder="idCardType === 1 ? '18 位身份证号' : '请输入证件号'"
          />
        </label>

        <p v-if="error" class="attendee-sheet__error">{{ error }}</p>

        <button class="attendee-sheet__submit" type="button" :disabled="submitting" @click="submit">
          {{ submitting ? '保存中…' : '保存观演人' }}
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.attendee-mask {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
}

.attendee-sheet {
  width: 100%;
  max-width: 480px;
  padding: 20px 18px calc(22px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: var(--bg-card);
  box-shadow: 0 -20px 50px rgba(0, 0, 0, 0.5);
  animation: sheet-up 0.22s ease both;
}

.attendee-sheet__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;

  h3 {
    margin: 0;
    font-size: 16px;
    font-weight: 800;
    letter-spacing: -0.2px;
  }

  p {
    margin: 5px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
  }

  button {
    display: grid;
    place-items: center;
    width: 32px;
    height: 32px;
    flex: 0 0 auto;
    border: 0;
    border-radius: 50%;
    background: var(--bg-elevated);
    color: var(--text-2);
    cursor: pointer;
  }
}

.attendee-field {
  display: block;
  margin-bottom: 15px;

  > span {
    display: block;
    margin-bottom: 7px;
    color: var(--text-3);
    font-size: 10px;
  }

  input {
    width: 100%;
    height: 44px;
    padding: 0 13px;
    border: 1px solid var(--line);
    border-radius: 10px;
    outline: none;
    background: var(--bg-elevated);
    color: var(--text-1);
    font-size: 13px;
    transition: border-color 0.16s ease;

    &::placeholder {
      color: var(--text-3);
      font-size: 11px;
    }

    &:focus {
      border-color: rgba(255, 61, 113, 0.5);
    }
  }
}

.attendee-types {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  button {
    flex: 0 0 auto;
    height: 34px;
    padding: 0 12px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg-elevated);
    color: var(--text-2);
    font-size: 10.5px;
    white-space: nowrap;
    cursor: pointer;

    &.is-active {
      border-color: rgba(255, 61, 113, 0.5);
      background: var(--brand-soft);
      color: var(--brand);
      font-weight: 700;
    }
  }
}

.attendee-sheet__error {
  margin: 0 0 12px;
  padding: 9px 11px;
  border: 1px solid rgba(255, 71, 87, 0.25);
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.08);
  color: #ff9aa3;
  font-size: 10px;
  line-height: 1.5;
}

.attendee-sheet__submit {
  width: 100%;
  height: 46px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(96deg, #ff3d71, #ff6b35);
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  cursor: pointer;

  &:disabled {
    background: var(--bg-elevated);
    color: var(--text-3);
    cursor: not-allowed;
  }
}

@keyframes sheet-up {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}
</style>
