import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getTaskFile, getTaskProgress } from '@/api/tasks'
import type { FileDetailResponse, FileUploadResponse } from '@/api/types'
export type UploadPhase = 'UPLOADING' | 'PENDING' | 'PROCESSING' | 'CONFIRM' | 'COMPLETED' | 'FAILED' | 'SAVED' | 'UPLOAD_FAILED' | 'REMOVED'
export interface WebUploadEntry {
  key: string; name: string; phase: UploadPhase; progress: number; taskId?: string; fileId?: number; savedOnly?: boolean
  detail?: FileDetailResponse; error?: string; pollError?: boolean
}
export const useWebUploadsStore = defineStore('web-uploads', () => {
  const auth = useAuthStore(), entries = ref<WebUploadEntry[]>([])
  let epoch = 0, refreshing: Promise<void> | undefined
  const scope = () => `coffer:upload-tasks:${auth.user?.id}`
  function persist() {
    if (auth.user?.id == null) return
    try { sessionStorage.setItem(scope(), JSON.stringify(entries.value.filter(e => e.taskId).map(({ key, name, taskId, savedOnly }) => ({ key, name, taskId, savedOnly })))) } catch { /* Optional tab history. */ }
  }
  watch(() => [auth.user?.id, auth.generation] as const, () => {
    epoch++; refreshing = undefined; entries.value = []
    if (auth.user?.id == null) return
    try {
      const saved: unknown = JSON.parse(sessionStorage.getItem(scope()) || '[]')
      if (Array.isArray(saved)) entries.value = saved.filter(e => typeof e?.key === 'string' && typeof e?.name === 'string' && typeof e?.taskId === 'string').slice(0, 10)
        .map(e => ({ key: e.key, name: e.name, taskId: e.taskId, savedOnly: e.savedOnly === true, phase: 'PENDING', progress: 0 }))
    } catch { /* Ignore malformed preferences. */ }
  }, { immediate: true, flush: 'sync' })
  function begin(key: string, name: string) { entries.value = [{ key, name, phase: 'UPLOADING' as const, progress: 0 }, ...entries.value.filter(e => e.key !== key)].slice(0, 10) }
  function progress(key: string, percent: number) { const row = entries.value.find(e => e.key === key); if (row?.phase === 'UPLOADING') row.progress = Math.max(0, Math.min(100, percent)) }
  function accepted(key: string, receipt: FileUploadResponse, savedOnly: boolean) {
    const row = entries.value.find(e => e.key === key)
    if (!row) return
    row.taskId = receipt.taskId; row.savedOnly = savedOnly; row.phase = savedOnly ? 'SAVED' : 'PENDING'; row.progress = 0; persist(); void refresh(true)
  }
  function failed(key: string) { const row = entries.value.find(e => e.key === key); if (row) { row.phase = 'UPLOAD_FAILED'; row.error = '尚未确认保存成功。请检查连接，重新选择此文件重试。' } }
  async function refresh(force = false) {
    if (refreshing) return refreshing
    const current = epoch
    refreshing = (async () => {
      await Promise.all(entries.value.filter(e => e.taskId && (force || ['PENDING', 'PROCESSING', 'CONFIRM'].includes(e.phase))).map(async row => {
        try {
          const [task, file] = await Promise.all([getTaskProgress(row.taskId!, true), getTaskFile(row.taskId!)])
          if (epoch !== current || !entries.value.includes(row)) return
          row.detail = file.data.data; row.fileId = row.detail.id; row.pollError = false
          row.progress = Math.max(0, Math.min(100, task.data.data.progress || 0))
          row.error = !row.savedOnly && task.data.data.status === 'FAILED' ? task.data.data.result || 'AI 分析未完成，文件已保存。' : undefined
          row.phase = row.savedOnly ? 'SAVED' : task.data.data.status === 'FAILED' ? 'FAILED'
            : task.data.data.status === 'COMPLETED' ? row.detail.pendingTags?.length ? 'CONFIRM' : 'COMPLETED'
            : task.data.data.status === 'PROCESSING' ? 'PROCESSING' : 'PENDING'
        } catch (error) {
          if (epoch !== current || !entries.value.includes(row)) return
          if ((error as { response?: { status?: number } }).response?.status === 404) { row.phase = 'REMOVED'; row.error = '此任务对应的文件已移除，或已开始新的分析任务。' }
          else row.pollError = true
        }
      }))
    })().finally(() => { if (current === epoch) refreshing = undefined })
    return refreshing
  }
  const hasFinished = computed(() => entries.value.some(e => ['COMPLETED', 'REMOVED'].includes(e.phase)))
  function clearFinished() { entries.value = entries.value.filter(e => !['COMPLETED', 'REMOVED'].includes(e.phase)); persist() }
  return { entries, hasFinished, begin, progress, accepted, failed, refresh, clearFinished }
})
