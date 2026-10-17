<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { retryFile } from '@/api/files'
import { ElMessage, ElMessageBox } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import type { NativeReceipt, NativeSelection } from '@/api/desktop'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import { useRouter } from 'vue-router'
const router = useRouter()
const props = defineProps<{ compact?: boolean }>()
const expanded = ref(false), moveSource = ref(false)
const auth = useAuthStore(), busy = ref(false), selected = ref<NativeSelection[]>([]), results = ref<NativeReceipt[]>([])
const batchProgress = ref<{ total: number; processed: number } | null>(null)
const batchPercent = computed(() => batchProgress.value
  ? Math.round(batchProgress.value.processed / batchProgress.value.total * 100) : 0)
const emit = defineEmits<{ (e: 'queued'): void }>()
function errorMessage(error: unknown, fallback: string) { return String((error as Error)?.message || fallback).replace(/^Error invoking remote method .*?: Error: /, '') }
function explain(error: unknown) { ElMessage.error(errorMessage(error, '本地文件选择失败')) }
async function pick() {
  if (!desktopBridge || busy.value) return
  busy.value = true
  moveSource.value = false
  try { selected.value = await desktopBridge.selectFiles(); results.value = [] } catch (error) { explain(error) } finally { busy.value = false }
}
async function drop(event: DragEvent) {
  if (!desktopBridge || busy.value || !event.dataTransfer?.files.length) return
  busy.value = true
  moveSource.value = false
  try { selected.value = await desktopBridge.previewDroppedFiles(Array.from(event.dataTransfer.files)); results.value = [] } catch (error) { explain(error) } finally { busy.value = false }
}
async function confirm() {
  if (!desktopBridge || busy.value || !selected.value.length) return
  if (moveSource.value) {
    try { await ElMessageBox.confirm(`将这 ${selected.value.length} 个文件移动到 Coffer：${selected.value.map(f => f.name).join('、')}。保存并校验文件库副本后，原位置文件将被移除。`, '移动到文件库', {confirmButtonText:'确认移动',cancelButtonText:'返回保留原文件',type:'warning'}) }
    catch { moveSource.value = false; return }
  }
  busy.value = true
  const generation = auth.generation
  const items = selected.value.slice()
  const shouldMoveSource = moveSource.value
  batchProgress.value = items.length > 1 ? { total: items.length, processed: 0 } : null
  results.value = []
  try {
    for (const item of items) {
      if (auth.generation !== generation) return
      let receipts: NativeReceipt[]
      try {
        receipts = await desktopBridge.commitInbox([{ id: item.id, targetPath: item.targetPath, moveSource: shouldMoveSource }])
      } catch (error) {
        receipts = [{ name: item.name, targetPath: item.targetPath, ok: false, error: errorMessage(error, '文件导入失败，请重试') }]
      }
      if (auth.generation !== generation) return
      results.value.push(...receipts)
      if (batchProgress.value) batchProgress.value.processed++
    }
    batchProgress.value = null
    if (results.value.some(item => item.ok)) { ElMessage.success(shouldMoveSource ? '已保存；来源处理结果可在收件箱查看' : '已保存到文件库，原文件保留'); emit('queued') }
    selected.value = []
    // Saving precedes optional model authorization; declining cannot undo the saved copy.
    for (const receipt of results.value.filter(r => r.ok && r.fileId)) {
      if (auth.generation !== generation) return
      try { await retryFile(receipt.fileId!); if (auth.generation === generation) emit('queued') } catch { /* File remains safely saved. */ }
    }
  } catch (error) { explain(error) } finally { batchProgress.value = null; busy.value = false }
}
watch(() => auth.generation, () => { selected.value = []; results.value = []; batchProgress.value = null })
defineExpose({ pick })
</script>
<template>
  <section v-if="desktopBridge" class="desktop-import" :class="{ compact: props.compact, 'has-items': selected.length || results.length }" @dragover.prevent @drop.prevent="drop">
    <div class="native-header"><strong>{{ props.compact ? '拖入文件以导入收件箱' : '本地文件导入' }}</strong><span v-if="props.compact" class="import-caution">默认保留原文件</span><button :disabled="busy" @click="pick">{{ busy ? '核对中…' : '选择本地文件' }}</button><button v-if="props.compact" class="import-explain" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起说明' : '导入说明' }}</button></div>
    <div v-if="batchProgress" class="import-batch" role="progressbar" aria-label="本次批量导入" :aria-valuenow="batchProgress.processed" :aria-valuemax="batchProgress.total" aria-valuemin="0">
      <div class="import-batch-head"><strong>本次批量导入</strong><span>{{ batchPercent }}%</span></div>
      <div class="import-batch-track"><div class="import-batch-fill" :style="{ width: batchPercent + '%' }"></div></div>
      <p>已处理 {{ batchProgress.processed }} / {{ batchProgress.total }} 个文件 · 整理状态可在文件库查看</p>
    </div>
    <p v-if="!props.compact || expanded || selected.length">保存一份到 Coffer，默认保留原位置文件。选择移动时会另行确认；保存失败或来源变化时保留原文件。AI 分析仍需有效授权。</p>
    <article v-for="item in selected" :key="item.id"><b>{{ item.name }} · {{ formatBytes(item.size) }}</b></article>
    <label v-if="selected.length"><input v-model="moveSource" type="checkbox" :disabled="busy" />移动到 Coffer（成功后移除原位置文件）</label><div v-if="selected.length" class="native-actions"><button :disabled="busy" @click="confirm">{{ moveSource ? '移动到文件库' : '保存一份到 Coffer' }}</button><button :disabled="busy" @click="selected = []">取消</button></div>
    <p v-for="(item, index) in results" :key="index" role="status">{{ item.name }}：{{ item.ok ? '文件已保存；可在文件库找到和取回' : item.error }}</p>
    <button v-if="results.some(item => item.ok)" @click="router.push({ path: '/files' })">查看文件库</button>
  </section>
</template>
<style scoped>
.desktop-import { padding: 18px; border: 1px dashed var(--line-strong, #bbb); border-radius: 12px; margin: 14px 0; font-size: 13px; background: var(--panel-2, #f5f0e6); }
.desktop-import p { line-height: 1.6; }
.desktop-import button { padding: 8px 12px; background: var(--accent, #3f8290); color: white; border: 0; border-radius: 7px; cursor: pointer; }
.desktop-import button:disabled { opacity: .5; }
.native-target { overflow-wrap: anywhere; }
.native-actions { display: flex; gap: 10px; }
.import-batch { position: sticky; top: 0; z-index: 1; padding: 12px 0; background: var(--panel); }
.import-batch-head { display: flex; justify-content: space-between; gap: 12px; }
.import-batch-track { height: 6px; margin-top: 8px; border-radius: 6px; overflow: hidden; background: var(--line); }
.import-batch-fill { height: 100%; background: var(--accent); transition: width .2s ease; }
.import-batch p { margin: 7px 0 0; color: var(--text-2); font-size: 12px; }
article { padding: 12px 0; }
.native-header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.native-header strong { margin-right: auto; }
.compact { margin: 0; border-width: 0 0 1px; border-style: solid; border-color: var(--line); border-radius: 0; padding: 10px 28px; flex: none; max-height: 32vh; overflow-y: auto; font-size: 11px; background: var(--bg); }
.compact .native-header { flex-wrap: wrap; gap: 10px; }
.compact .native-header strong { font-weight: 400; color: var(--text-2); margin: 0; }
.import-caution { color: var(--text-3); }
.compact .native-header button { padding: 3px 8px; font-size: 11px; background: transparent; color: var(--accent); }
.compact .native-header .import-explain { margin-left: auto; color: var(--text-2); }
.compact.has-items { background: var(--panel); }
</style>
