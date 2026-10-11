<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import type { NativeReceipt, NativeSelection } from '@/api/desktop'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import { useRouter } from 'vue-router'
const router = useRouter()
const props = defineProps<{ compact?: boolean }>()
const expanded = ref(false)
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
    if (results.value.some(item => item.ok)) { ElMessage.success('已暂存到收件箱；确认正式入库后将删除源文件'); emit('queued') }
    selected.value = []
  } catch (error) { explain(error) } finally { busy.value = false }
}
watch(() => auth.generation, () => { selected.value = []; results.value = [] })
defineExpose({ pick })
</script>
<template>
  <section v-if="desktopBridge" class="desktop-import" :class="{ compact: props.compact, 'has-items': selected.length || results.length }" @dragover.prevent @drop.prevent="drop">
    <div class="native-header"><strong>{{ props.compact ? '拖入文件以导入收件箱' : '本地文件导入' }}</strong><span v-if="props.compact" class="import-caution">正式入库确认后会删除源文件</span><button :disabled="busy" @click="pick">{{ busy ? '核对中…' : '选择本地文件' }}</button><button v-if="props.compact" class="import-explain" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起说明' : '导入说明' }}</button></div>
    <p v-if="!props.compact || expanded || selected.length">拖入本机文件，或选择文件。确认接管后先暂存到收件箱；正式入库并校验成功后删除原路径文件。入库失败或来源变化时保留源文件。AI 分析仍需确认。</p>
    <article v-for="item in selected" :key="item.id"><b>{{ item.name }} · {{ formatBytes(item.size) }}</b><p class="native-target">{{ item.targetPath }}</p></article>
    <div v-if="selected.length" class="native-actions"><button :disabled="busy" @click="confirm">确认接管（入库后删除源文件）</button><button :disabled="busy" @click="selected = []">取消</button></div>
    <p v-for="(item, index) in results" :key="index" role="status">{{ item.name }}：{{ item.ok ? '收件箱暂存已就绪；完成正式路径确认后删除源文件' : item.error }}</p>
    <button v-if="results.some(item => item.ok)" @click="router.push({ name: 'home', query: auth.isPersonal ? { view: 'inbox' } : {} })">确认入库路径并查看接管结果</button>
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
