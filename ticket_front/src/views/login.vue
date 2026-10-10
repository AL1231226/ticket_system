<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { sendCode } from '@/api/user'
import { useUserStore } from '@/stores/user'

// 前端只做"格式对不对"的粗筛，手机号是否合法以后端 RegexUtils 为准
// （后端那条正则比这个严，比如 144 开头的号段前端放行、后端会拒）
const PHONE_REGEX = /^1[3-9]\d{9}$/
const CODE_REGEX = /^\d{6}$/
const COUNTDOWN_SECONDS = 60

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const phone = ref('')
const code = ref('')
const errorMsg = ref('')
const notice = ref('')
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)

let countdownTimer = null

const codeButtonText = computed(() =>
  countdown.value > 0 ? `${countdown.value}s 后重发` : '发送验证码',
)

function startCountdown() {
  countdown.value = COUNTDOWN_SECONDS
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
  countdown.value = 0
}

// 组件卸载时必须清掉，否则离开页面后定时器还在跑
onBeforeUnmount(stopCountdown)

async function handleSendCode() {
  if (countdown.value > 0 || sending.value) {
    return
  }
  errorMsg.value = ''
  notice.value = ''

  if (!PHONE_REGEX.test(phone.value)) {
    errorMsg.value = '请输入正确的手机号'
    return
  }

  sending.value = true
  try {
    await sendCode(phone.value)
    notice.value = '验证码已发送，请查看后端控制台日志'
    startCountdown()
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    sending.value = false
  }
}

async function handleLogin() {
  if (loading.value) {
    return
  }
  errorMsg.value = ''

  if (!PHONE_REGEX.test(phone.value)) {
    errorMsg.value = '请输入正确的手机号'
    return
  }
  if (!CODE_REGEX.test(code.value)) {
    errorMsg.value = '请输入 6 位数字验证码'
    return
  }

  loading.value = true
  try {
    // 后端在手机号未注册时会自动注册，所以「注册」和「登录」是同一个动作
    await userStore.login({ phone: phone.value, code: code.value })
    // 被守卫弹过来时带着 redirect，登录后回到原来想去的页面
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
    router.replace(redirect)
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__glow" aria-hidden="true" />

    <div class="login__content">
      <div class="login__hero">
        <div class="login__mark">♪</div>
        <h1 class="login__title">演出票务</h1>
        <p class="login__subtitle">抢到你想去的每一场</p>
      </div>

      <div class="login__form">
        <div class="login__row">
          <span class="login__prefix">+86</span>
          <van-field
            v-model="phone"
            class="login__input"
            type="tel"
            maxlength="11"
            placeholder="请输入手机号"
            :border="false"
            @keyup.enter="handleLogin"
          />
        </div>

        <div class="login__hairline" />

        <div class="login__row">
          <van-field
            v-model="code"
            class="login__input"
            type="digit"
            maxlength="6"
            placeholder="请输入验证码"
            :border="false"
            @keyup.enter="handleLogin"
          />
          <button
            class="login__code"
            type="button"
            :disabled="countdown > 0 || sending"
            @click="handleSendCode"
          >
            {{ codeButtonText }}
          </button>
        </div>
      </div>

      <p v-if="errorMsg" class="login__msg login__msg--error">{{ errorMsg }}</p>
      <p v-else-if="notice" class="login__msg login__msg--notice">{{ notice }}</p>

      <van-button
        class="login__submit"
        type="primary"
        block
        round
        :loading="loading"
        loading-text="登录中…"
        @click="handleLogin"
      >
        登 录
      </van-button>

      <p class="login__tip">未注册的手机号将自动创建账号</p>
      <p class="login__tip login__tip--dim">开发环境不接短信，验证码见后端控制台日志</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
  overflow: hidden;
  background: var(--bg-base);
}

// 顶部一片粉紫辉光，制造「舞台灯光」的感觉
.login__glow {
  position: absolute;
  top: -180px;
  left: 50%;
  width: 520px;
  height: 520px;
  transform: translateX(-50%);
  background: radial-gradient(
    circle,
    rgba(255, 61, 113, 0.28) 0%,
    rgba(123, 97, 255, 0.12) 45%,
    transparent 70%
  );
  pointer-events: none;
}

.login__content {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 380px;
}

.login__hero {
  margin-bottom: 32px;
  text-align: center;
}

.login__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  margin-bottom: 16px;
  border-radius: 18px;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  box-shadow: 0 10px 30px rgba(255, 61, 113, 0.35);
  color: #fff;
  font-size: 28px;
  line-height: 1;
}

.login__title {
  margin: 0 0 6px;
  color: var(--text-1);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 2px;
}

.login__subtitle {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  letter-spacing: 1px;
}

.login__form {
  // 只覆盖 Vant 的 CSS 变量，不改组件源码；
  // 用变量而不是 .van-xxx 选择器，避免和 Vant 的样式表比优先级
  --van-cell-background: transparent;
  --van-cell-vertical-padding: 0;
  --van-cell-horizontal-padding: 0;
  --van-field-input-text-color: var(--text-1);
  --van-field-placeholder-text-color: var(--text-3);

  padding: 4px 16px;
  border: 1px solid #2a2a36;
  border-radius: 16px;
  background: var(--bg-card);
}

.login__row {
  display: flex;
  align-items: center;
  min-height: 52px;
}

.login__prefix {
  flex: none;
  margin-right: 8px;
  color: var(--text-2);
  font-size: 15px;
}

.login__input {
  flex: 1;
}

.login__hairline {
  height: 1px;
  background: #2a2a36;
}

.login__code {
  flex: none;
  padding: 6px 0 6px 12px;
  border: none;
  background: none;
  color: var(--brand);
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;

  &:disabled {
    color: var(--text-3);
    cursor: default;
  }
}

.login__msg {
  margin: 12px 2px 0;
  font-size: 13px;
  line-height: 1.4;

  &--error {
    color: var(--danger);
  }

  &--notice {
    color: var(--success);
  }
}

.login__submit {
  margin-top: 20px;
  height: 46px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 6px;
}

.login__tip {
  margin: 14px 0 0;
  color: var(--text-2);
  font-size: 12px;
  text-align: center;

  &--dim {
    margin-top: 6px;
    color: var(--text-3);
  }
}
</style>
