<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  changePassword,
  createUser,
  disableUser,
  enableUser,
  getAccountAudit,
  listUsers,
  resetUserPassword,
  type AccountAudit,
  type AuthUser,
} from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const currentPassword = ref('')
const newPassword = ref('')
const username = ref('')
const initialPassword = ref('')
const busy = ref(false)
const users = ref<AuthUser[]>([])
const audit = ref<AccountAudit[]>([])

onMounted(() => {
  if (auth.isAdmin) void loadAdminData()
})

async function loadAdminData() {
  const [userResponse, auditResponse] = await Promise.all([listUsers(), getAccountAudit()])
  users.value = userResponse.data.data
  audit.value = auditResponse.data.data
}

async function updatePassword() {
  if (newPassword.value.length < 12) {
    ElMessage.warning('新密码至少 12 个字符')
    return
  }
  busy.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    ElMessage.success('密码已更新，其他会话已退出')
  } finally {
    busy.value = false
  }
}

async function addUser() {
  if (!/^[A-Za-z0-9._-]{3,64}$/.test(username.value)) {
    ElMessage.warning('账号只能使用 3–64 位字母、数字、点、下划线或短横线')
    return
  }
  if (initialPassword.value.length < 12) {
    ElMessage.warning('初始密码至少 12 个字符')
    return
  }
  busy.value = true
  try {
    await createUser(username.value, initialPassword.value)
    username.value = ''
    initialPassword.value = ''
    await loadAdminData()
    ElMessage.success('账号已创建')
  } finally {
    busy.value = false
  }
}

async function resetPassword(user: AuthUser) {
  const { value } = await ElMessageBox.prompt(`为 ${user.username} 设置新密码（至少 12 个字符）`, '重置账号密码', {
    inputType: 'password',
    inputPattern: /^.{12,72}$/,
    inputErrorMessage: '密码长度须为 12–72 个字符',
    confirmButtonText: '重置',
    cancelButtonText: '取消',
  })
  await resetUserPassword(user.id, value)
  ElMessage.success('密码已重置，原会话已撤销')
  await loadAdminData()
}

async function disable(user: AuthUser) {
  await ElMessageBox.confirm(`停用 ${user.username} 后，其现有会话会立即失效。`, '停用账号', {
    confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning',
  })
  await disableUser(user.id)
  await loadAdminData()
  ElMessage.success('账号已停用')
}

async function enable(user: AuthUser) {
  await enableUser(user.id)
  await loadAdminData()
  ElMessage.success('账号已启用')
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="pg-panel account-panel">
    <h2 class="panel-title">账号与安全</h2>
    <p class="note">当前账号：{{ auth.user?.username }}（{{ auth.user?.role === 'ADMIN' ? '管理员' : '用户' }}）</p>

    <form class="form-grid" @submit.prevent="updatePassword">
      <label class="field"><span>当前密码</span><input v-model="currentPassword" type="password" autocomplete="current-password" required /></label>
      <label class="field"><span>新密码（至少 12 个字符）</span><input v-model="newPassword" type="password" autocomplete="new-password" minlength="12" maxlength="72" required /></label>
      <button class="action-button primary" type="submit" :disabled="busy">更新密码</button>
    </form>

    <template v-if="auth.isAdmin">
      <div class="divider"></div>
      <h3>用户账号</h3>
      <p class="note">管理员只管理账号状态与凭据。用户文件、标签、对话和任务按账号隔离。</p>
      <form class="form-grid create-grid" @submit.prevent="addUser">
        <label class="field"><span>新账号</span><input v-model="username" type="text" autocomplete="off" minlength="3" maxlength="64" required /></label>
        <label class="field"><span>初始密码</span><input v-model="initialPassword" type="password" autocomplete="new-password" minlength="12" maxlength="72" required /></label>
        <button class="action-button primary" type="submit" :disabled="busy">创建用户</button>
      </form>
      <div class="user-list">
        <article v-for="user in users" :key="user.id" class="user-row">
          <div><strong>{{ user.username }}</strong><span>{{ user.role === 'ADMIN' ? '管理员' : '用户' }} · {{ user.enabled ? '启用' : '已停用' }}</span></div>
          <div v-if="user.role === 'USER'" class="row-actions">
            <button v-if="user.enabled" class="action-button" @click="resetPassword(user)">重置密码</button>
            <button v-if="user.enabled" class="action-button danger" @click="disable(user)">停用</button>
            <button v-else class="action-button" @click="enable(user)">启用</button>
          </div>
        </article>
      </div>
      <div class="audit-list">
        <h3>账号操作记录</h3>
        <p v-for="event in audit" :key="event.id" class="audit-row">
          {{ formatDate(event.createdAt) }} · {{ event.actorUsername }} · {{ event.action }} · 账号 #{{ event.targetUserId ?? '—' }}
        </p>
        <p v-if="!audit.length" class="note">暂无记录</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.account-panel { flex: none; }
.panel-title, h3 { margin: 0; color: var(--text-1); font-size: 15px; }
.note { margin: 5px 0 0; color: var(--text-3); font-size: 11.5px; line-height: 1.6; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)) auto; align-items: end; gap: 10px; margin-top: 15px; }
.field { display: grid; gap: 5px; color: var(--text-3); font-size: 11px; }
.field input { min-width: 0; padding: 9px 10px; border: 1px solid var(--line-strong); border-radius: var(--radius-inner); color: var(--text-1); background: var(--panel-2); font: inherit; }
.action-button { border: 1px solid var(--line-strong); border-radius: var(--radius-inner); padding: 8px 11px; color: var(--text-1); background: var(--panel-2); cursor: pointer; font: inherit; font-size: 11px; white-space: nowrap; }
.action-button.primary { border-color: var(--accent); color: white; background: var(--accent); }
.action-button.danger { color: var(--danger); }
.action-button:disabled { opacity: .6; cursor: wait; }
.divider { margin: 20px 0 16px; border-top: 1px dashed var(--line); }
.create-grid { margin-bottom: 12px; }
.user-list { display: grid; border-top: 1px solid var(--line); }
.user-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.user-row strong, .user-row span { display: block; }
.user-row strong { color: var(--text-1); font-size: 12px; }
.user-row span { margin-top: 2px; color: var(--text-3); font-size: 10.5px; }
.row-actions { display: flex; gap: 7px; }
.audit-list { margin-top: 18px; }
.audit-row { margin: 6px 0 0; color: var(--text-3); font-size: 10.5px; }
@media (max-width: 760px) { .form-grid { grid-template-columns: 1fr; }.user-row { align-items: flex-start; flex-direction: column; } }
</style>
