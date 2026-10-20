<script setup>
import { ref, watch } from 'vue'
import QRCode from 'qrcode'

const props = defineProps({
  value: { type: String, default: '' },
  size: { type: Number, default: 200 },
  margin: { type: Number, default: 1 },
})

const dataUrl = ref('')
const failed = ref(false)

watch(
  () => [props.value, props.size, props.margin],
  async () => {
    if (!props.value) {
      dataUrl.value = ''
      return
    }
    try {
      dataUrl.value = await QRCode.toDataURL(props.value, {
        width: props.size,
        margin: props.margin,
        errorCorrectionLevel: 'M',
        color: {
          dark: '#0f0f14',
          light: '#ffffff',
        },
      })
      failed.value = false
    } catch {
      failed.value = true
      dataUrl.value = ''
    }
  },
  { immediate: true },
)
</script>

<template>
  <img
    v-if="dataUrl"
    class="qrcode"
    :src="dataUrl"
    :width="size"
    :height="size"
    alt="电子票二维码"
  />
  <div v-else class="qrcode qrcode--placeholder" :style="{ width: `${size}px`, height: `${size}px` }">
    {{ failed ? '二维码生成失败' : '生成中…' }}
  </div>
</template>

<style scoped lang="scss">
.qrcode {
  display: block;
  border-radius: 6px;
  background: #fff;
}

.qrcode--placeholder {
  display: grid;
  place-items: center;
  color: var(--text-3);
  font-size: 10px;
}
</style>
