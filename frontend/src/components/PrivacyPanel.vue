<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import type { Result } from '@/api/types'
import { currentBoundary } from '@/api/sessionBoundary'
const status = ref<Record<string, number>>({})
const exporting = ref(false)
async function refresh() { status.value = (await http.get<Result<Record<string, number>>>('/privacy')).data.data }
async function exportData() {
  exporting.value = true
  try {
    const boundary = currentBoundary().epoch
    const { data } = await http.get<Blob>('/privacy/export', { responseType: 'blob', timeout: 0 })
    if (boundary !== currentBoundary().epoch) return
    const url = URL.createObjectURL(data)
    const link = document.createElement('a'); link.href = url; link.download = 'coffer-private-export.zip'
    link.click(); URL.revokeObjectURL(url)
  } finally { exporting.value = false }
}
async function eraseConversations() {
  await ElMessageBox.confirm('删除当前账号的全部对话、引用历史和会话记忆？此操作无法撤销。正在进行的操作完成后才能删除。', '删除对话与记忆', { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消' })
  await http.delete('/privacy/conversations', { data: { confirmation: 'DELETE_MY_CONVERSATIONS' } })
  await refresh()
  window.dispatchEvent(new Event('coffer:conversations-deleted'))
  ElMessage.success('对话已删除，残留记忆已进入后台清理队列')
}
onMounted(refresh)
</script>
<template>
  <section class="pg-panel privacy-panel">
    <h2>我的私有数据</h2>
    <p>当前有 {{ status.files ?? 0 }} 个文件、{{ status.conversations ?? 0 }} 个会话。</p>
    <h3>导出</h3>
    <p>下载 ZIP，包含你的文件原件及文件、标签、对话、任务、整理记录清单。文件以 ID 命名，原名在 manifest.json 中。不包含登录凭据或模型 API 密钥。请妥善保管下载的私有数据。</p>
    <el-button :loading="exporting" @click="exportData">导出我的数据</el-button>
    <h3>删除</h3>
    <p>文件删除会移除对应文件记录、标签关联和上传任务，并排队清理对象与向量。可在文件页逐项选择确认。</p>
    <router-link to="/files">前往文件页选择删除</router-link>
    <p>删除对话后，旧会话与引用立即失效；Redis 暂时不可用时，后台继续重试清理记忆。</p>
    <el-button type="danger" plain @click="eraseConversations">删除全部对话与记忆</el-button>
    <p>待物理清理：{{ status.pendingObjects ?? 0 }} 个对象、{{ status.pendingVectors ?? 0 }} 个文件索引、{{ status.pendingMemories ?? 0 }} 个会话记忆。停用账号的清理在重新启用后继续。</p>
    <el-button @click="refresh">刷新清理进度</el-button>
  </section>
</template>
<style scoped>.privacy-panel { flex: none; padding: 24px; } p { line-height: 1.7; } h3 { margin-top: 24px; }</style>
