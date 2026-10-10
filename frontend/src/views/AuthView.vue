<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import { desktopBridge } from '@/api/desktop'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const setupToken = ref('')
const busy = ref(false)
const checkingStatus = ref(!auth.initialized)
const serviceUnavailable = ref(false)
const verifiedStatus = computed(() => auth.initialized && !checkingStatus.value && !serviceUnavailable.value)
const setupMode = computed(() => verifiedStatus.value && auth.status.setupRequired && auth.status.setupAvailable)
const setupUnavailable = computed(() => verifiedStatus.value && auth.status.setupRequired && !auth.status.setupAvailable)

async function checkService() {
  checkingStatus.value = true
  try {
    await auth.bootstrap(true)
    if (auth.status.setupRequired && auth.status.setupAvailable && desktopBridge) setupToken.value = await desktopBridge.setupToken()
    serviceUnavailable.value = false
    if (auth.user) await router.replace('/')
  } catch {
    serviceUnavailable.value = true
  } finally { checkingStatus.value = false }
}
onMounted(() => void checkService())

async function submit() {
  if (!verifiedStatus.value) return
  if (setupMode.value && !auth.validNewPassword(password.value)) {
    ElMessage.warning('新密码须为 ' + auth.passwordHint)
    return
  }
  if (username.value.trim().length < 3 || password.value.length < 6 || password.value.length > 16) {
    ElMessage.warning('账号至少 3 个字符，密码须为 6–16 个字符')
    return
  }
  busy.value = true
  try {
    if (setupMode.value) {
      await auth.createInitialAdmin(setupToken.value, username.value.trim(), password.value)
      ElMessage.success('管理员账号已初始化')
    } else {
      await auth.signIn(username.value.trim(), password.value)
    }
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/'
    password.value = ''; setupToken.value = ''
    await router.replace(auth.isAdmin ? '/admin' : redirect)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <form class="auth-card" @submit.prevent="submit">
      <div class="brand-mark">智</div>
      <p class="eyebrow">COFFER · PRIVATE WORKSPACE</p>
      <h1>{{ setupMode ? '初始化管理员' : '登录 Coffer' }}</h1>
      <p class="intro">{{ setupMode ? '首次启动时创建管理员账号。' : '使用管理员为你创建的账号登录。' }}</p>
      <p v-if="route.query.reason === 'expired'" role="alert">会话已失效，请重新登录。</p>
      <p v-if="route.query.reason === 'changed'" role="alert">其他窗口的登录身份已变化，当前页面状态已清空。</p>
      <p v-if="checkingStatus" role="status">正在核实服务与账号状态…</p>
      <div v-else-if="serviceUnavailable" role="alert">
        <p>暂时无法核实服务和账号状态。请检查网络及后台服务，恢复后重新检查。</p>
        <button type="button" @click="checkService">重新检查服务</button>
      </div>

      <div v-if="setupUnavailable" class="setup-note">
        尚未创建管理员。请由部署者配置 COFFER_ADMIN_SETUP_TOKEN 后重启服务，再完成首次初始化。
      </div>

      <template v-if="verifiedStatus && !setupUnavailable">
        <label v-if="setupMode && !desktopBridge" class="field">
          <span>初始化凭据</span>
          <input v-model="setupToken" type="password" autocomplete="off" required />
        </label>
        <label class="field">
          <span>账号</span>
          <input v-model="username" type="text" autocomplete="username" minlength="3" maxlength="64" required />
        </label>
        <label class="field">
          <span>密码</span>
          <small v-if="setupMode">{{ auth.passwordHint }}</small>
          <input v-model="password" type="password" :autocomplete="setupMode ? 'new-password' : 'current-password'" minlength="6" maxlength="16" required />
        </label>
        <button class="submit" type="submit" :disabled="busy">
          {{ busy ? '处理中…' : setupMode ? '创建管理员' : '登录' }}
        </button>
      </template>
    </form>
  </main>
</template>

<style scoped>
.auth-shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: var(--bg); }
.auth-card { width: min(100%, 390px); display: grid; gap: 16px; padding: 30px; border: 1px solid var(--line); border-radius: 20px; background: var(--panel); box-shadow: var(--shadow-card); }
.brand-mark { width: 48px; height: 48px; display: grid; place-items: center; border-radius: 50%; color: white; background: var(--accent); font: 22px var(--font-display); }
.eyebrow { margin: 2px 0 -12px; color: var(--accent); font-size: 10px; font-weight: 700; letter-spacing: .12em; }
h1 { margin: 0; color: var(--text-1); font: 24px var(--font-display); }
.intro { margin: -10px 0 0; color: var(--text-3); font-size: 13px; }
.field { display: grid; gap: 6px; color: var(--text-2); font-size: 12px; }
.field input { padding: 11px 12px; border: 1px solid var(--line-strong); border-radius: 9px; color: var(--text-1); background: var(--panel-2); font: inherit; }
.submit { border: 0; border-radius: 9px; padding: 11px 14px; color: white; background: var(--accent); font: inherit; cursor: pointer; }
.submit:disabled { opacity: .6; cursor: wait; }
.setup-note { padding: 12px; border-radius: 9px; color: var(--text-2); background: var(--panel-2); font-size: 12px; line-height: 1.7; }
</style>
