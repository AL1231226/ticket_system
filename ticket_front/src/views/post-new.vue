<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { publishPost, uploadCommunityImage } from '@/api/community'
import { getShowDetail } from '@/api/show'
import AppBar from '@/components/AppBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import { formatDate, formatSessionDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const title = ref('')
const content = ref('')
const fileList = ref([])
const submitting = ref(false)
const error = ref('')

const linkedShow = ref(null)
const linkedSession = ref(null)
const showLoading = ref(false)
const showError = ref('')
const posterFailed = ref(false)

const images = computed(() => fileList.value.map((file) => file.url).filter(Boolean))
const uploading = computed(() => fileList.value.some((file) => file.status === 'uploading'))
const failedUpload = computed(() => fileList.value.some((file) => file.status === 'failed'))
const canSubmit = computed(
  () =>
    !submitting.value &&
    !uploading.value &&
    !failedUpload.value &&
    Boolean(content.value.trim() || images.value.length),
)

const posterStyle = computed(() => {
  const palettes = [
    ['#ff3d71', '#5b2a86'],
    ['#00d2ff', '#1b2a6b'],
    ['#ffcb47', '#7a3b12'],
    ['#2ed573', '#0b3d2c'],
    ['#e056fd', '#2b1550'],
  ]
  const seed = Number(linkedShow.value?.id || 0) % palettes.length
  const p = palettes[seed]
  return { background: `linear-gradient(145deg, ${p[0]}, ${p[1]})` }
})

onMounted(loadLinkedShow)

async function loadLinkedShow() {
  const showId = route.query.showId
  if (!showId) return

  showLoading.value = true
  showError.value = ''
  try {
    const detail = await getShowDetail(showId)
    linkedShow.value = detail?.show || null
    const sessionId = route.query.sessionId
    linkedSession.value =
      detail?.sessions?.find((session) => String(session.id) === String(sessionId)) || null
  } catch (e) {
    showError.value = e.message
  } finally {
    showLoading.value = false
  }
}

function clearShow() {
  linkedShow.value = null
  linkedSession.value = null
  showError.value = ''
}

async function afterRead(item) {
  const files = Array.isArray(item) ? item : [item]
  for (const file of files) {
    file.status = 'uploading'
    file.message = '上传中…'
    try {
      const res = await uploadCommunityImage(file.file)
      file.url = res.url
      file.status = 'done'
      file.message = ''
    } catch (e) {
      file.status = 'failed'
      file.message = e.message || '上传失败'
      error.value = e.message || '图片上传失败'
    }
  }
}

function onOversize() {
  error.value = '图片不能超过 5MB'
}

async function submit() {
  if (submitting.value) return
  error.value = ''

  const safeTitle = title.value.trim()
  const safeContent = content.value.trim()

  if (!safeContent && !images.value.length) {
    error.value = '正文和图片至少填一项'
    return
  }
  if (safeTitle.length > 128) {
    error.value = '标题不能超过 128 字'
    return
  }
  if (safeContent.length > 2000) {
    error.value = '正文不能超过 2000 字'
    return
  }
  if (uploading.value) {
    error.value = '图片还在上传中，请稍候'
    return
  }
  if (failedUpload.value) {
    error.value = '有图片上传失败，请先删除失败的图片'
    return
  }

  submitting.value = true
  try {
    const payload = {
      title: safeTitle || undefined,
      content: safeContent || undefined,
      images: images.value,
    }
    if (linkedShow.value?.id) {
      payload.showId = linkedShow.value.id
    }
    if (linkedSession.value?.id) {
      payload.sessionId = linkedSession.value.id
    }

    const id = await publishPost(payload)
    router.replace(`/post/${id}`)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="post-new page">
    <AppBar title="发布动态" />

    <div class="post-new__intro">
      <span class="post-new__mark">
        <AppIcon name="community" :size="18" />
      </span>
      <div>
        <h1>记录今晚的现场</h1>
        <p>一张图、一句话，都可以发。</p>
      </div>
    </div>

    <section class="post-new__card">
      <input
        v-model="title"
        class="post-new__title"
        type="text"
        maxlength="128"
        placeholder="标题（可选）"
      />

      <textarea
        v-model="content"
        class="post-new__content"
        rows="5"
        maxlength="2000"
        placeholder="这场的灯光、歌单、散场后的心情…"
      />

      <div class="post-new__counters">
        <span>{{ title.length }}/128</span>
        <span>{{ content.length }}/2000</span>
      </div>

      <div class="post-new__upload">
        <div class="post-new__upload-head">
          <span>图片</span>
          <span>{{ images.length }}/9</span>
        </div>
        <van-uploader
          v-model="fileList"
          accept="image/*"
          :max-count="9"
          :max-size="5 * 1024 * 1024"
          :after-read="afterRead"
          @oversize="onOversize"
        />
      </div>

      <div v-if="showLoading" class="post-new__show post-new__show--loading">关联演出加载中…</div>

      <div v-else-if="showError" class="post-new__show-error">
        {{ showError }}
        <button type="button" @click="loadLinkedShow">重试</button>
        <button type="button" @click="clearShow">移除</button>
      </div>

      <div v-else-if="linkedShow" class="post-new__show">
        <div class="post-new__show-poster" :style="posterStyle">
          <img
            v-if="linkedShow.poster && !posterFailed"
            :src="linkedShow.poster"
            :alt="linkedShow.name"
            @error="posterFailed = true"
          />
          <span v-else>{{ (linkedShow.name || '演').slice(0, 1) }}</span>
        </div>
        <div class="post-new__show-body">
          <p class="post-new__show-name">{{ linkedShow.name }}</p>
          <p class="post-new__show-meta">
            {{ linkedShow.city }} · {{ formatDate(linkedShow.startDate) }}
          </p>
          <p v-if="linkedSession" class="post-new__show-session">
            {{ linkedSession.sessionName }} · {{ formatSessionDate(linkedSession.sessionTime) }}
          </p>
        </div>
        <button type="button" class="post-new__show-clear" @click="clearShow">取消关联</button>
      </div>

      <p v-if="error" class="post-new__error">{{ error }}</p>
    </section>

    <div class="post-new__action">
      <button type="button" :disabled="!canSubmit" @click="submit">
        {{ submitting ? '发布中…' : '发布' }}
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.post-new {
  padding-bottom: calc(78px + env(safe-area-inset-bottom));
}

.post-new__intro {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 18px 16px 4px;

  h1 {
    margin: 0;
    font-size: 17px;
    font-weight: 800;
    letter-spacing: -0.2px;
  }

  p {
    margin: 4px 0 0;
    color: var(--text-3);
    font-size: 10px;
  }
}

.post-new__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand), #ff6b35);
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.3);
  color: #fff;
}

.post-new__card {
  margin: 12px 16px 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(36, 36, 46, 0.66), var(--bg-card));
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.18);
}

.post-new__title {
  width: 100%;
  height: 40px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid var(--line);
  outline: none;
  background: transparent;
  color: var(--text-1);
  font-size: 15px;
  font-weight: 700;

  &::placeholder {
    color: var(--text-3);
    font-weight: 500;
  }
}

.post-new__content {
  width: 100%;
  min-height: 120px;
  margin-top: 12px;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.75;
  resize: none;

  &::placeholder {
    color: var(--text-3);
  }
}

.post-new__counters {
  display: flex;
  justify-content: space-between;
  padding-top: 6px;
  border-top: 1px solid rgba(42, 42, 54, 0.6);
  color: var(--text-3);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
}

.post-new__upload {
  margin-top: 16px;
}

.post-new__upload-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-2);
  font-size: 11px;
  font-weight: 700;

  span:last-child {
    color: var(--text-3);
    font-size: 9.5px;
    font-weight: 500;
    font-variant-numeric: tabular-nums;
  }
}

.post-new__show {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 10px;
  border: 1px solid rgba(255, 61, 113, 0.16);
  border-radius: 12px;
  background: rgba(255, 61, 113, 0.06);
}

.post-new__show--loading {
  color: var(--text-3);
  font-size: 10px;
}

.post-new__show-poster {
  display: grid;
  place-items: center;
  width: 46px;
  height: 58px;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 16px;
  font-weight: 800;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.post-new__show-body {
  min-width: 0;
  flex: 1;
}

.post-new__show-name {
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-new__show-meta,
.post-new__show-session {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-new__show-clear {
  padding: 5px 0 5px 6px;
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: var(--text-3);
  font-size: 9.5px;
  cursor: pointer;
}

.post-new__show-error {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 71, 87, 0.08);
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

.post-new__error {
  margin: 14px 0 0;
  padding: 9px 11px;
  border: 1px solid rgba(255, 71, 87, 0.25);
  border-radius: 8px;
  background: rgba(255, 71, 87, 0.08);
  color: #ff9aa3;
  font-size: 10px;
  line-height: 1.5;
}

.post-new__action {
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
    width: 100%;
    height: 44px;
    border: 0;
    border-radius: 11px;
    background: linear-gradient(96deg, #ff3d71, #ff6b35);
    color: #fff;
    font-size: 12.5px;
    font-weight: 700;
    cursor: pointer;

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
  }
}

:deep(.van-uploader__wrapper) {
  gap: 8px;
}

:deep(.van-uploader__upload) {
  width: 82px;
  height: 82px;
  margin: 0;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-3);
}

:deep(.van-uploader__preview) {
  width: 82px;
  height: 82px;
  margin: 0;
}

:deep(.van-uploader__preview-image) {
  overflow: hidden;
  border-radius: 10px;
}

:deep(.van-uploader__preview-delete) {
  border-radius: 0 10px 0 10px;
  background: rgba(0, 0, 0, 0.58);
}

:deep(.van-uploader__mask) {
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.6);
  font-size: 9px;
}
</style>
