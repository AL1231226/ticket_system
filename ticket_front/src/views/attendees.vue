<script setup>
import { onMounted, ref } from 'vue'

import { deleteAttendee, getAttendees } from '@/api/attendee'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import AttendeeForm from '@/components/AttendeeForm.vue'
import StateView from '@/components/StateView.vue'

const attendees = ref([])
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const showForm = ref(false)
const deleteTarget = ref(null)
const deleting = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const list = await getAttendees()
    attendees.value = Array.isArray(list) ? list : []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function handleSaved() {
  await load()
}

function askDelete(attendee) {
  actionError.value = ''
  deleteTarget.value = attendee
}

async function confirmDelete() {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  actionError.value = ''
  try {
    await deleteAttendee(deleteTarget.value.id)
    deleteTarget.value = null
    await load()
  } catch (e) {
    actionError.value = e.message
  } finally {
    deleting.value = false
  }
}

function initial(name) {
  return (name || '观').slice(0, 1)
}
</script>

<template>
  <div class="attendees page">
    <AppBar title="观演人管理">
      <template #right>
        <button class="attendees__add-icon" type="button" aria-label="添加观演人" @click="showForm = true">
          <AppIcon name="plus" :size="18" />
        </button>
      </template>
    </AppBar>

    <main class="attendees__body">
      <p class="attendees__intro">
        一票一人，同一场次同一证件只能持有一张票。支付前需要为每张票选择观演人。
      </p>

      <div v-if="loading" class="attendees__skeletons">
        <div v-for="i in 3" :key="i" class="attendees__skeleton" />
      </div>

      <StateView
        v-else-if="error"
        title="观演人加载失败"
        :description="error"
        action-text="重试"
        @action="load"
      />

      <StateView
        v-else-if="!attendees.length"
        icon="users"
        title="还没有观演人"
        description="添加实名观演人后，抢到票就能直接选择。"
        action-text="添加观演人"
        @action="showForm = true"
      />

      <div v-else class="attendees__list">
        <article v-for="attendee in attendees" :key="attendee.id" class="attendee">
          <span class="attendee__avatar">{{ initial(attendee.realName) }}</span>

          <div class="attendee__info">
            <div class="attendee__name">
              <b>{{ attendee.realName }}</b>
              <span v-if="attendee.verifyStatus === 1" class="attendee__verified">已实名</span>
              <span v-else class="attendee__unverified">未认证</span>
            </div>
            <p>{{ attendee.idCard }}</p>
          </div>

          <button class="attendee__delete" type="button" @click="askDelete(attendee)">删除</button>
        </article>

        <p class="attendees__hint">
          被待支付或已支付订单占用的观演人不能删除，需要先处理订单。
        </p>
      </div>

      <p v-if="actionError" class="attendees__error">{{ actionError }}</p>
    </main>

    <footer class="attendees__action">
      <button type="button" @click="showForm = true">
        <AppIcon name="plus" :size="16" />
        添加观演人
      </button>
    </footer>

    <AttendeeForm v-model:show="showForm" @saved="handleSaved" />

    <Teleport to="body">
      <div v-if="deleteTarget" class="delete-mask" @click.self="deleteTarget = null">
        <div class="delete-card">
          <h3>删除观演人？</h3>
          <p>将删除「{{ deleteTarget.realName }}」的实名信息，之后需要重新添加。</p>
          <div class="delete-card__actions">
            <button type="button" class="ghost" @click="deleteTarget = null">再想想</button>
            <button type="button" class="danger" :disabled="deleting" @click="confirmDelete">
              {{ deleting ? '删除中…' : '删除' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.attendees {
  padding-bottom: calc(78px + env(safe-area-inset-bottom));
}

.attendees__add-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--bg-card);
  color: var(--text-2);
  cursor: pointer;
}

.attendees__body {
  padding: 14px 16px 0;
}

.attendees__intro {
  margin: 0 2px 14px;
  color: var(--text-3);
  font-size: 9.5px;
  line-height: 1.7;
}

.attendees__list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.attendee {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 13px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: var(--bg-card);
}

.attendee__avatar {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #7b61ff, #ff3d71);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.attendee__info {
  min-width: 0;
  flex: 1;

  p {
    margin: 5px 0 0;
    color: var(--text-3);
    font-size: 9.5px;
    font-variant-numeric: tabular-nums;
  }
}

.attendee__name {
  display: flex;
  align-items: center;
  gap: 7px;

  b {
    overflow: hidden;
    color: var(--text-1);
    font-size: 12.5px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.attendee__verified,
.attendee__unverified {
  flex: 0 0 auto;
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 8px;
}

.attendee__verified {
  background: rgba(46, 213, 115, 0.1);
  color: var(--success);
}

.attendee__unverified {
  background: rgba(255, 203, 71, 0.1);
  color: var(--accent);
}

.attendee__delete {
  flex: 0 0 auto;
  padding: 5px 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-3);
  font-size: 9px;
  cursor: pointer;

  &:active {
    background: rgba(255, 71, 87, 0.08);
    color: #ff8d96;
  }
}

.attendees__hint {
  margin: 7px 4px 0;
  color: var(--text-3);
  font-size: 8.5px;
  line-height: 1.6;
}

.attendees__error {
  margin: 12px 0 0;
  padding: 9px 11px;
  border: 1px solid rgba(255, 71, 87, 0.25);
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.08);
  color: #ff9aa3;
  font-size: 9.5px;
  line-height: 1.5;
}

.attendees__action {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  width: 100%;
  max-width: 480px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  background: rgba(20, 20, 28, 0.97);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(18px);

  button {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
    width: 100%;
    height: 44px;
    border: 0;
    border-radius: 11px;
    background: linear-gradient(96deg, #ff3d71, #ff6b35);
    color: #fff;
    font-size: 12.5px;
    font-weight: 700;
    cursor: pointer;
  }
}

.attendees__skeletons {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.attendees__skeleton {
  height: 66px;
  border-radius: 13px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

.delete-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.62);
  backdrop-filter: blur(4px);
}

.delete-card {
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

.delete-card__actions {
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

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
