import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getFileDetail } from '@/api/files'
import { confirmSavedFile, getArchiveOperation, retryArchiveOperation } from '@/api/governance'
import { useAuthStore } from '@/stores/auth'

type Job = { phase: 'running' | 'waiting' | 'failed' | 'done'; batchId?: string }
const jobs = ref(new Map<string, Job>())
const completedRevision = ref(0)
export function useArchiveFile() {
  const auth = useAuthStore()
  const key = (id: number) => `${auth.generation}:${auth.user?.id}:${id}`
  const job = (id: number) => jobs.value.get(key(id))
  async function archive(id: number, changed: () => void) {
    const identity = key(id), current = () => identity === key(id)
    const previous = jobs.value.get(identity)
    if (previous?.phase === 'running') return
    const state: Job = { phase: 'running', batchId: previous?.batchId }
    jobs.value.set(identity, state)
    try {
      const { data } = await getFileDetail(id)
      if (!current()) return
      const detail = data.data
      if (detail.archived) { state.phase = 'done'; jobs.value.set(identity, { ...state }); completedRevision.value++; changed(); return }
      if (detail.status === 'PENDING' || detail.status === 'PROCESSING') {
        jobs.value.delete(identity); ElMessage.info('文件正在解析，完成后即可归档'); changed(); return
      }
      let batch
      if (state.batchId) {
        batch = (await getArchiveOperation(state.batchId)).data.data
        if (!current()) return
        if (['FAILED', 'PARTIAL_FAILED'].includes(batch.status)) {
          batch = (await retryArchiveOperation(state.batchId)).data.data
        }
      } else {
        const pending = detail.pendingTags ?? []
        if (pending.length) {
          try {
            await ElMessageBox.confirm(`归档时将采用这些建议标签：${pending.map(t => t.tagName).join('、')}。文件名称和摘要保持当前内容。`, '确认标签并归档', { confirmButtonText: '采用并归档', cancelButtonText: '暂不归档', type: 'info' })
          } catch { jobs.value.delete(identity); return }
        }
        if (!current()) return
        batch = (await confirmSavedFile(id, { revision: detail.revision })).data.data
        state.batchId = batch.batchId
      }
      if (!current()) return
      jobs.value.set(identity, { ...state }); changed()
      // Success means the durable operation finished, not merely that POST was accepted.
      for (let attempt = 0; attempt < 30; attempt++) {
        if (!current()) return
        if (batch.status === 'SUCCEEDED') {
          const updated = (await getFileDetail(id)).data.data
          if (!current()) return
          if (!updated.archived) throw new Error('归档结果尚未同步，请刷新后查看')
          state.phase = 'done'; jobs.value.set(identity, { ...state }); completedRevision.value++; changed(); ElMessage.success('文件已归档'); return
        }
        if (['FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(batch.status)) throw new Error('归档未完成，文件仍已保存；可重试或到操作记录查看原因')
        await new Promise(resolve => setTimeout(resolve, 1000))
        if (!current()) return
        batch = (await getArchiveOperation(state.batchId!)).data.data
      }
      state.phase = 'waiting'; jobs.value.set(identity, { ...state }); changed()
      ElMessage.info('归档仍在进行，可点击“查看归档进度”继续查看，或前往操作记录')
    } catch (error) {
      if (!current()) return
      state.phase = 'failed'; jobs.value.set(identity, { ...state }); changed()
      ElMessage.error((error as Error).message || '归档未完成，文件仍已保存')
    }
  }
  return { archive, job, completedRevision }
}
