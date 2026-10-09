<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/workCopies'
import type { WorkCopyView } from '@/api/workCopies'
import { formatBytes } from '@/utils/format'
import { desktopBridge } from '@/api/desktop'
const props = defineProps<{ fileId?: number }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const enabled = ref(false), busy = ref(false), page = ref(0), hasMore = ref(false)
const rows = ref<WorkCopyView[]>([])
const visibleRows = computed(() => rows.value.filter(row => !props.fileId || row.fileId === props.fileId))
const closing = ref<WorkCopyView | null>(null), choice = ref('KEEP')
let timer: ReturnType<typeof setInterval> | undefined
const labels: Record<string, string> = { PREPARING: '正在创建', READY: '副本已准备', OPENED: '外部编辑中，关闭需确认', SAVING: '保存中',
  SAVING_AS: '另存中', SAVED: '新版本已保存', SAVED_AS: '已另存独立文件', CONFLICTED: '原件冲突，副本保留',
  INTERRUPTED: '中断待确认', KEPT: '已保留，下次可继续', NEEDS_DECISION: '未保存修改，等待选择',
  CLOSING: '关闭清理中', CLOSED: '已关闭', DISCARDING: '放弃清理中', DISCARDED: '已放弃副本' }
const errors: Record<string, string> = { COPY_FAILED: '创建中断，原件保留；请检查副本是否完整。', APP_OPEN_FAILED: '默认应用打开失败，请配置文件关联后重试。',
  COPY_BUSY_OR_CHANGED: '副本被占用或读取时变化。请先在 Office 中保存并关闭文档，再刷新核对。',
  FORMAL_FILE_CHANGED: '原件的版本、正文、大小、修改时间或文件身份已变化。两份文件均保留，可另存或放弃副本。',
  BACKEND_EXIT_UNCONFIRMED: '后端上次退出时未确认编辑结束。已保留磁盘副本，请先检查外部应用。',
  APP_EXIT_UNCONFIRMED: '已记录应用异常或未保存退出；只能恢复写到磁盘的内容，未保存到磁盘的修改请在 Office 恢复面板查找。',
  UNSAVED_CHANGES: '副本含未提交修改，请选择保存、另存、保留或放弃。', UNSAVED_COPY_RETAINED: '副本保留，可重新打开继续编辑。',
  COPY_CONFIRMATION_CHANGED: '确认时副本再次变化，请刷新后重新核对。', COPY_CLEANUP_FAILED: '清理失败，副本仍保留，请关闭外部应用后重试。', SAVE_AS_FAILED: '另存未完成，副本保留；请查看保存台账并重试。' }
errors.COPY_CHANGED_AFTER_COMMIT = '此副本已提交过一个版本，之后磁盘内容再次变化。可另存当前副本；继续修改正式文件请从新版本创建副本。'
const active = (row: WorkCopyView) => !['CLOSED', 'DISCARDED'].includes(row.status)
async function load(reset = true) {
  const next = reset ? 0 : page.value + 1
  const { data } = await api.list(next)
  const incoming = data.data ?? []
  rows.value = reset ? incoming : [...rows.value, ...incoming]
  page.value = next; hasMore.value = incoming.length === 100
}
async function run(action: () => Promise<{ data: { data: WorkCopyView } }>) {
  if (busy.value) return
  busy.value = true
  try {
    const { data } = await action(), row = data.data
    rows.value = [row, ...rows.value.filter(item => item.id !== row.id)]
    if (['SAVED', 'SAVED_AS'].includes(row.status)) { emit('saved'); ElMessage.success(labels[row.status]) }
  } catch (error) { if (error !== 'cancel' && error !== 'close') await load() }
  finally { busy.value = false }
}
async function open(row: WorkCopyView) {
  const confirmed = await ElMessageBox.confirm(`仅打开受控副本：\n${row.workPath}\n\n请在外部应用中保存到这个副本并关闭文档，再回到这里提交新版本。应用启动返回并不表示文档已关闭。`, '打开工作副本', { confirmButtonText: '打开外部应用', type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  await run(() => desktopBridge ? desktopBridge.openWorkCopy(row.id).then(data => ({ data: { data } })) : api.open(row.id))
}
async function saveAs(row: WorkCopyView) {
  const confirmed = await ElMessageBox.confirm('将磁盘副本登记为独立文件，原件保留。另存后请在文件列表确认并重新分析。', '另存副本', { confirmButtonText: '另存独立文件' }).then(() => true).catch(() => false)
  if (!confirmed) return
  await run(() => api.saveAs(row.id))
}
async function finishClose() {
  const row = closing.value
  if (!row) return
  if (choice.value === 'SAVE') {
    await run(() => api.save(row.id))
    const saved = rows.value.find(item => item.id === row.id)
    if (saved?.status === 'SAVED') await run(() => api.close(row.id, 'CLOSE', saved.sha256))
  } else if (choice.value === 'SAVE_AS') await saveAs(row)
  else await run(() => api.close(row.id, choice.value, row.sha256))
  closing.value = null
}
function exitPrompt(event: BeforeUnloadEvent) {
  if (rows.value.some(row => active(row) && row.status === 'OPENED')) { event.preventDefault(); event.returnValue = '' }
}
onMounted(async () => {
  enabled.value = await api.capabilities().then(response => response.data.data.workCopies).catch(() => false)
  if (enabled.value) {
    await load()
    timer = setInterval(() => { if (!busy.value && !closing.value && page.value === 0) void load().catch(() => {}) }, 5000)
    window.addEventListener('beforeunload', exitPrompt)
  }
})
watch(() => props.fileId, () => { if (enabled.value) void load() })
onBeforeUnmount(() => { clearInterval(timer); window.removeEventListener('beforeunload', exitPrompt) })
</script>

<template>
  <section v-if="enabled" class="work-copy-panel">
    <strong>本地工作副本</strong>
    <p>外部应用编辑副本，正式文件在提交新版本后更新。请先在 Office 中保存并关闭文档。</p>
    <div class="work-copy-actions">
      <button v-if="fileId" :disabled="busy" @click="run(() => api.create(fileId!))">创建工作副本</button>
      <button :disabled="busy" @click="load()">刷新副本状态</button>
    </div>
    <p v-if="!visibleRows.length">暂无工作副本</p>
    <article v-for="row in visibleRows" :key="row.id" class="work-copy-row">
      <b>{{ row.fileName }} · {{ labels[row.status] ?? row.status }}</b>
      <p class="work-copy-path">{{ row.workPath }}</p>
      <p v-if="active(row)">{{ row.busy ? '占用或不可读，请关闭外部应用后刷新' : `${row.modified ? '含未提交修改' : '磁盘副本已核对'} · ${formatBytes(row.size ?? 0)}` }}</p>
      <p v-if="row.errorCode" role="status">{{ errors[row.errorCode] ?? row.errorCode }}</p>
      <p v-if="row.recoveredFileId">独立文件 #{{ row.recoveredFileId }}，等待手动重新分析</p>
      <div v-if="active(row)" class="work-copy-actions">
        <template v-if="row.status !== 'SAVED_AS'">
          <button :disabled="busy || row.busy || row.status === 'SAVED'" @click="open(row)">打开副本</button>
          <button :disabled="busy || row.busy || ['CONFLICTED', 'SAVED'].includes(row.status)" @click="run(() => api.save(row.id))">保存新版本</button>
          <button :disabled="busy || row.busy" @click="saveAs(row)">另存独立文件</button>
          <button :disabled="busy" @click="run(() => api.close(row.id, 'REPORT_EXIT'))">记录应用异常 / 未保存退出</button>
        </template>
        <button :disabled="busy" @click="closing = row; choice = 'KEEP'">关闭 / 放弃副本</button>
      </div>
    </article>
    <button v-if="hasMore" :disabled="busy" @click="load(false)">加载更早副本</button>
    <el-dialog :model-value="!!closing" title="确认外部编辑已结束" width="520px" @close="closing = null">
      <p>请先关闭外部文档。未写到磁盘的修改无法由 Coffer 恢复；副本被占用时不会清理或覆盖原件。</p>
      <el-radio-group v-model="choice" class="work-copy-choices">
        <el-radio value="KEEP">保留副本，下次继续</el-radio>
        <el-radio value="SAVE" :disabled="closing?.status === 'SAVED_AS'">保存新版本并关闭</el-radio>
        <el-radio value="SAVE_AS" :disabled="closing?.status === 'SAVED_AS'">另存独立文件</el-radio>
        <el-radio value="CLOSE">核对无未提交修改后关闭</el-radio>
        <el-radio value="DISCARD">放弃磁盘副本（原件保留）</el-radio>
      </el-radio-group>
      <template #footer><el-button @click="closing = null">取消</el-button><el-button type="primary" :disabled="busy" @click="finishClose">确认选择</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.work-copy-panel { padding: 16px; border: 1px solid var(--border-color, #ddd); border-radius: 12px; margin: 16px 0; font-size: 13px; }
.work-copy-row { padding: 12px 0; border-top: 1px solid #ddd; }
.work-copy-path { overflow-wrap: anywhere; }
.work-copy-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.work-copy-actions button { padding: 6px 10px; border: 1px solid #ccc; border-radius: 6px; background: transparent; cursor: pointer; }
.work-copy-actions button:disabled { opacity: .45; cursor: default; }
.work-copy-choices { display: flex; flex-direction: column; align-items: start; }
</style>
