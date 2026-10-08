<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { runtimeStatus, rotationStatus, rotateMasterKey, alertHistory, runtimeLabels, runtimeActions, type RuntimeSnapshot, type KeyRotation } from '@/api/runtime'
const status = ref<RuntimeSnapshot | null>(null)
const key = ref<KeyRotation | null>(null)
const events = ref<{ code: string; severity: string; firstSeen: string; resolvedAt: string | null }[]>([])
const unavailable = ref(false)
const rotating = ref(false)
let loading = false
let disposed = false
let timer: ReturnType<typeof setInterval> | undefined
async function load() {
  if (loading) return
  loading = true
  try {
    const [snapshot, rotation, history] = await Promise.all([runtimeStatus(true), rotationStatus(), alertHistory()])
    if (!disposed) { status.value = snapshot.data.data; key.value = rotation.data.data; events.value = history.data.data; unavailable.value = false }
  } catch { if (!disposed) { unavailable.value = true; status.value = null; key.value = null; events.value = [] } }
  finally { loading = false }
}
async function rotate() {
  try { await ElMessageBox.confirm('确认已在服务环境中配置新的主密钥和上一把主密钥。轮换将校验并重新加密所有账号的凭据；失败将整体回滚。', '轮换主密钥') }
  catch { return }
  rotating.value = true
  try { const result = await rotateMasterKey(); if (!disposed) { key.value = result.data.data; ElMessage.success(`已校验 ${key.value.verified} 条，加密更新 ${key.value.rewritten} 条`) } }
  catch { key.value = null; await load() }
  finally { rotating.value = false }
}
onMounted(() => { void load(); timer = setInterval(() => void load(), 30000) })
onUnmounted(() => { disposed = true; if (timer) clearInterval(timer) })
</script>
<template>
  <section class="pg-panel runtime-panel">
    <h2>运行状态与告警</h2><button @click="load">刷新</button>
    <p v-if="!status && !unavailable">正在核实运行状态…</p>
    <p v-if="unavailable" role="alert">运行状态暂时无法核实。检查数据库、后台服务和网络连接后重试。</p>
    <template v-if="status">
      <p>就绪状态：{{ runtimeLabels[status.readiness] ?? status.readiness }} · 最近采样：{{ new Date(status.checkedAt).toLocaleString() }}（每 30 秒采样）</p>
      <div class="components"><article v-for="component in status.components" :key="component.name">
        <strong>{{ runtimeLabels[component.name] }} · {{ runtimeLabels[component.status] }}</strong>
        <p>{{ runtimeLabels[component.reason] ?? '状态未核实' }}</p>
        <p v-if="component.totalBytes !== null">可用 {{ ((component.freeBytes ?? 0) / 1024 ** 3).toFixed(1) }} / 总计 {{ (component.totalBytes / 1024 ** 3).toFixed(1) }} GiB</p>
        <p v-if="component.action !== 'NONE'">{{ runtimeActions[component.action] }}</p>
      </article></div>
      <h3>当前告警</h3><p v-if="!status.alerts.length">当前无告警</p>
      <p v-for="alert in status.alerts" :key="alert.code" role="alert">{{ alert.severity === 'CRITICAL' ? '故障' : '需处理' }} · {{ alert.code }}：{{ runtimeActions[alert.action] }}</p>
      <h3>后台积压</h3><p v-if="Object.keys(status.queues).length">排队 {{ status.queues.pendingTasks }} · 处理中 {{ status.queues.processingTasks }} · 失败 {{ status.queues.failedTasks }} · 补偿 {{ status.queues.pendingCompensations }} · 清理 {{ status.queues.pendingDeletions }} · 人工处理 {{ status.queues.manualReview }} · 写入 {{ status.queues.pendingWrites }} · 重命名 {{ status.queues.pendingRenames }} · 工作副本 {{ status.queues.pendingWorkSaves }} · 向量清理 {{ status.queues.pendingVectorCleanups }} · 重建失败 {{ status.queues.failedVectorReindexes }} · 最旧待处理 {{ status.queues.oldestPendingMinutes }} 分钟</p><p v-else>积压数量未核实</p>
      <h3>数据库连接池</h3><p v-if="Object.keys(status.connections ?? {}).length">使用 {{ status.connections.active }} · 空闲 {{ status.connections.idle }} · 等待 {{ status.connections.waiting }} · 上限 {{ status.connections.limit }}</p><p v-else>连接池统计未核实</p>
    </template>
    <h3>主密钥轮换</h3>
    <template v-if="key"><p>当前密钥标识 {{ key.keyId }} · 待重包 {{ key.remaining }} 条 · 上一把密钥{{ key.previousKeyConfigured ? '已配置' : '未配置' }}</p>
      <p>先配置 COFFER_SECRET_KEY 与 COFFER_SECRET_PREVIOUS_KEY 并重启，再执行轮换。完成核对并备份新密钥后，移除上一把密钥。</p>
      <button :disabled="rotating || !key.previousKeyConfigured" @click="rotate">{{ rotating ? '校验与轮换中…' : '校验并轮换全库凭据' }}</button>
    </template>
    <h3>最近告警记录</h3><p v-for="(event, index) in events" :key="index">{{ event.code }} · {{ new Date(event.firstSeen).toLocaleString() }} · {{ event.resolvedAt ? '已恢复' : '处理中' }}</p>
  </section>
</template>
<style scoped>.runtime-panel { padding: 20px; margin: 20px 0; } .components { display: grid; grid-template-columns: repeat(auto-fit,minmax(190px,1fr)); gap: 16px; } article { border: 1px solid var(--line); padding: 12px; border-radius: 10px; } p { font-size: 13px; line-height: 1.6; } button { cursor: pointer; }</style>
