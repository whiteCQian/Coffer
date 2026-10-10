import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { resetBoundary } from '@/api/sessionBoundary'

import {
  getAuthStatus,
  getCsrfToken,
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  setupInitialAdmin,
  type AuthStatus,
  type AuthUser,
} from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const status = ref<AuthStatus>({ setupRequired: true, setupAvailable: false })
  const initialized = ref(false)
  const generation = ref(0)
  function boundary() { resetBoundary(); generation.value += 1 }
  function announce() { localStorage.setItem('coffer:identity-change', String(Date.now()) + Math.random()) }
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const passwordHint = computed(() => status.value.strongPasswordRequired ? '12–16 个字符，包含大小写字母和数字，避开常见弱口令' : '6–16 个字符')
  function validNewPassword(value: string) {
    return value.length >= (status.value.passwordMinLength ?? 6) && value.length <= 16
      && (!status.value.strongPasswordRequired || /[A-Z]/.test(value) && /[a-z]/.test(value) && /[0-9]/.test(value) && !/(password|qwerty|123456|letmein|coffer|admin)/i.test(value))
  }

  async function bootstrap(force = false) {
    if (initialized.value && !force) return
    await getCsrfToken()
    const response = await getAuthStatus()
    status.value = response.data.data
    try {
      const me = await getCurrentUser()
      if (user.value?.id !== me.data.data.id) boundary()
      user.value = me.data.data
    } catch (error) {
      if ((error as { response?: { status?: number } }).response?.status !== 401) throw error
      user.value = null
    }
    initialized.value = true
  }

  async function signIn(username: string, password: string) {
    const response = await loginRequest(username, password)
    boundary()
    user.value = response.data.data
    status.value = { ...status.value, setupRequired: false, setupAvailable: false }
    initialized.value = true
    announce()
  }

  async function createInitialAdmin(token: string, username: string, password: string) {
    const response = await setupInitialAdmin(token, username, password)
    boundary()
    user.value = response.data.data
    status.value = { ...status.value, setupRequired: false, setupAvailable: false }
    initialized.value = true
    announce()
  }

  async function signOut() {
    clear()
    try { await logoutRequest() } finally { announce() }
  }

  function clear() {
    boundary()
    user.value = null
    initialized.value = false
  }

  return { user, status, initialized, generation, isAdmin, passwordHint, validNewPassword, bootstrap, signIn, createInitialAdmin, signOut, clear, resetWorkspace: boundary }
})
