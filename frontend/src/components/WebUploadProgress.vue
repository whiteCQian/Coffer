<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useWebUploadsStore, type UploadPhase } from '@/stores/webUploads'
import { useWorkspaceStore } from '@/stores/workspace'
import { confirmTag } from '@/api/fileTags'
import { useAuthStore } from '@/stores/auth'
const props = defineProps<{ showEmpty?: boolean; expanded?: boolean }>()
const emit = defineEmits<{ (e: 'open-file', id: number): void; (e: 'changed'): void; (e: 'toggle-expand'): void }>()
const uploads = useWebUploadsStore(), workspace = useWorkspaceStore(), auth = useAuthStore(), busy = ref<string | null>(null)
const labels: Record<UploadPhase, string> = { UPLOADING: '正在上传', PENDING: '已上传，等待解析', PROCESSING: '正在解析', CONFIRM: '请确认标签', COMPLETED: '已完成', FAILED: '分析未完成', SAVED: '已保存，稍后分析', UPLOAD_FAILED: '上传未完成', REMOVED: '文件已移除' }
const others = computed(() => {
  const taskIds = new Set(uploads.entries.map(e => e.taskId)), fileIds = new Set(uploads.entries.map(e => e.fileId))
  return [
    ...(workspace.overview?.processing ?? []).filter(t => !taskIds.has(t.taskId)).map(t => ({ key: t.taskId, name: t.fileName, text: '正在解析', fileId: null })),
    ...(workspace.overview?.pendingConfirm ?? []).filter(f => !fileIds.has(f.fileId)).map(f => ({ key: `pending-${f.fileId}`, name: f.fileName, text: '待确认标签', fileId: f.fileId })),
    ...(workspace.overview?.failed ?? []).filter(f => !taskIds.has(f.taskId) && !fileIds.has(f.fileId ?? undefined)).map(f => ({ key: f.taskId, name: f.fileName, text: '分析未完成，文件已保存', fileId: f.fileId })),
  ]
})
const visible = computed(() => uploads.entries.length || others.value.length || props.showEmpty || workspace.unavailable)
let timer: ReturnType<typeof setInterval> | undefined
async function refresh(force = false) { await Promise.all([uploads.refresh(force), workspace.refresh()]) }
onMounted(() => {
  void refresh(true)
  timer = setInterval(() => {
    if (!document.hidden && (uploads.entries.some(e => ['PENDING', 'PROCESSING', 'CONFIRM'].includes(e.phase)) || others.value.length || workspace.unavailable)) void refresh()
  }, 2500)
})
onUnmounted(() => clearInterval(timer))
async function accept(key: string) {
  const row = uploads.entries.find(e => e.key === key)
  if (!row?.fileId || !row.detail || busy.value) return
  const generation = auth.generation
  busy.value = key
  try {
    for (const tag of row.detail.pendingTags ?? []) {
      if (generation !== auth.generation) return
      await confirmTag({ fileId: row.fileId, tagId: tag.tagId })
    }
    if (generation !== auth.generation) return
    ElMessage.success('标签已确认，文件可通过这些标签查找'); emit('changed')
  } catch { /* The request layer reports the error; refresh preserves any remaining tags for retry. */
  } finally { await refresh(true); busy.value = null }
}
</script>
<template>
  <section v-if="visible" id="web-task-progress" class="upload-progress" aria-label="本次上传与处理" aria-live="polite">
    <header><h2>上传与处理</h2><button v-if="uploads.hasFinished" class="text-button" @click="uploads.clearFinished">收起已完成</button><button class="text-button" @click="refresh(true)">刷新状态</button><button type="button" class="task-expand-button" :aria-expanded="!!props.expanded" aria-controls="web-task-receipts" :aria-label="props.expanded ? '收起上传与处理' : '展开上传与处理'" @click="emit('toggle-expand')">{{ props.expanded ? '收起' : '展开' }}</button></header>
    <p v-if="workspace.unavailable" role="status">任务列表暂时无法读取，已上传的文件仍会保留。可刷新状态重试。</p>
    <ol id="web-task-receipts" class="upload-receipts">
      <li v-for="entry in uploads.entries" :key="entry.key" class="upload-receipt" :data-phase="entry.phase">
        <div class="receipt-heading"><strong :title="entry.name">{{ entry.name }}</strong><span>{{ labels[entry.phase] }}</span></div>
        <progress v-if="['UPLOADING', 'PROCESSING'].includes(entry.phase)" :value="entry.progress" max="100" :aria-label="`${entry.name}的${labels[entry.phase]}进度`" />
        <p v-if="entry.pollError" role="status">暂时无法读取最新状态，请刷新重试。文件不会因此丢失。</p>
        <p v-else-if="entry.phase === 'UPLOADING'">{{ entry.progress }}% · 上传完成后会在这里继续显示解析结果。</p>
        <p v-else-if="entry.phase === 'PENDING'">文件已上传，无需重复提交，正在等待 AI 处理。</p>
        <p v-else-if="entry.phase === 'PROCESSING'">AI 正在提取摘要和标签，完成后会自动更新。</p>
        <p v-else-if="entry.error">{{ entry.error }}</p>
        <p v-else-if="entry.phase === 'SAVED'">文件已安全保存，可以查看和下载，之后再进行 AI 分析。</p>
        <p v-else-if="entry.phase === 'COMPLETED'">文件已保存{{ entry.detail?.confirmedTags?.length ? '，标签已确认' : '' }}，可在文件库查找。</p>
        <p v-if="entry.phase === 'CONFIRM'">文件已保存，采用建议标签后更容易查找。</p>
        <div v-if="entry.phase === 'CONFIRM'" class="suggested-tags"><span v-for="tag in entry.detail?.pendingTags" :key="tag.tagId">{{ tag.tagName }}</span></div>
        <div class="receipt-actions"><button v-if="entry.phase === 'CONFIRM'" class="primary" :disabled="busy !== null" @click="accept(entry.key)">{{ busy === entry.key ? '正在确认…' : '采用建议标签' }}</button><button v-if="entry.fileId" @click="emit('open-file', entry.fileId)">{{ entry.phase === 'CONFIRM' ? '查看详情／调整标签' : '查看文件' }}</button></div>
      </li>
      <li v-for="row in others" :key="row.key" class="upload-receipt"><div class="receipt-heading"><strong>{{ row.name }}</strong><span>{{ row.text }}</span></div><button v-if="row.fileId" @click="emit('open-file', row.fileId)">查看并处理</button></li>
    </ol>
    <p v-if="!uploads.entries.length && !others.length && !workspace.unavailable">当前没有待处理的文件。</p>
  </section>
</template>
<style scoped>
.upload-progress{min-height:0;max-height:380px;overflow-y:auto;border:1px solid var(--line);border-radius:10px;background:var(--panel);padding:16px 20px;color:var(--text-1);scroll-margin:18px}header,.receipt-heading,.receipt-actions,.suggested-tags{display:flex;align-items:center;gap:8px}header{flex-wrap:wrap}h2{font-size:14px;margin:0 auto 0 0}.upload-receipts{list-style:none;padding:0;margin:6px 0 0}.upload-receipt{padding:13px 0;border-top:1px solid var(--line);margin-top:10px}.receipt-heading{align-items:flex-start;justify-content:space-between}.receipt-heading strong{font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.receipt-heading>span{font-size:11px;color:var(--accent);flex-shrink:0}p{font-size:11px;line-height:1.7;color:var(--text-2);overflow-wrap:anywhere;margin:7px 0}progress{width:100%;height:6px;accent-color:var(--accent);margin-top:10px}.receipt-actions,.suggested-tags{flex-wrap:wrap;margin-top:8px}button{font:inherit;font-size:11px;border:1px solid var(--line-strong);border-radius:6px;padding:5px 9px;color:var(--text-1);background:var(--panel-2);cursor:pointer}.primary{background:var(--accent);color:white;border-color:var(--accent)}.text-button{font-size:10px;border:0;background:transparent;color:var(--text-3);padding:3px}button:disabled{opacity:.55;cursor:default}.suggested-tags>span{font-size:11px;border-radius:12px;padding:3px 8px;background:var(--panel-3);color:var(--text-2)}[data-phase=FAILED] .receipt-heading>span,[data-phase=UPLOAD_FAILED] .receipt-heading>span{color:var(--danger,#d98e7c)}
</style>
