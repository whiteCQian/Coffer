<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import type { NativeReceipt, NativeSelection } from '@/api/desktop'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
const auth = useAuthStore(), busy = ref(false), selected = ref<NativeSelection[]>([]), results = ref<NativeReceipt[]>([])
const emit = defineEmits<{ (e: 'queued'): void }>()
function explain(error: unknown) { ElMessage.error(String((error as Error)?.message || '本地文件选择失败').replace(/^Error invoking remote method .*?: Error: /, '')) }
async function pick() {
  if (!desktopBridge || busy.value) return
  busy.value = true
  try { selected.value = await desktopBridge.selectFiles(); results.value = [] } catch (error) { explain(error) } finally { busy.value = false }
}
async function drop(event: DragEvent) {
  if (!desktopBridge || busy.value || !event.dataTransfer?.files.length) return
  busy.value = true
  try { selected.value = await desktopBridge.previewDroppedFiles(Array.from(event.dataTransfer.files)); results.value = [] } catch (error) { explain(error) } finally { busy.value = false }
}
async function confirm() {
  if (!desktopBridge || busy.value || !selected.value.length) return
  busy.value = true
  try {
    results.value = await desktopBridge.commitInbox(selected.value.map(item => ({ id: item.id, targetPath: item.targetPath })))
    if (results.value.some(item => item.ok)) { ElMessage.success('已复制到收件箱，来源保留；请继续核对正式入库路径'); emit('queued') }
    selected.value = []
  } catch (error) { explain(error) } finally { busy.value = false }
}
watch(() => auth.generation, () => { selected.value = []; results.value = [] })
defineExpose({ pick })
</script>
<template>
  <section v-if="desktopBridge" class="desktop-import" @dragover.prevent @drop.prevent="drop">
    <strong>本地文件导入</strong>
    <p>拖入本机文件，或选择文件。先核对收件箱路径；复制保留来源，正式入库与 AI 分析仍需确认。</p>
    <button :disabled="busy" @click="pick">{{ busy ? '核对中…' : '选择本地文件' }}</button>
    <article v-for="item in selected" :key="item.id"><b>{{ item.name }} · {{ formatBytes(item.size) }}</b><p class="native-target">{{ item.targetPath }}</p></article>
    <div v-if="selected.length" class="native-actions"><button :disabled="busy" @click="confirm">确认这些路径并复制</button><button :disabled="busy" @click="selected = []">取消</button></div>
    <p v-for="(item, index) in results" :key="index" role="status">{{ item.name }}：{{ item.ok ? '收件箱副本已就绪，等待扫描和正式路径确认' : item.error }}；来源文件保留</p>
  </section>
</template>
<style scoped>
.desktop-import { padding: 18px; border: 1px dashed var(--line-strong, #bbb); border-radius: 12px; margin: 14px 0; font-size: 13px; background: var(--panel-2, #f5f0e6); }
.desktop-import p { line-height: 1.6; }
.desktop-import button { padding: 8px 12px; background: var(--accent, #3f8290); color: white; border: 0; border-radius: 7px; cursor: pointer; }
.desktop-import button:disabled { opacity: .5; }
.native-target { overflow-wrap: anywhere; }
.native-actions { display: flex; gap: 10px; }
article { padding: 12px 0; }
</style>
