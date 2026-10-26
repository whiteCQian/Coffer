<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import http from '@/api/http'
import type { Result } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import '@/styles/detail-presentation.css'
type Usage = { usedBytes: number; objectCount: number; limitBytes: number; limitObjects: number; pendingTaskLimit: number; concurrentTaskLimit: number }
const auth = useAuthStore()
const usage = ref<Usage | null>(null)
const unavailable = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
async function refresh() {
  const generation = auth.generation
  try {
    const response = await http.get<Result<Usage>>('/storage/usage')
    if (generation === auth.generation) { usage.value = response.data.data; unavailable.value = false }
  } catch { if (generation === auth.generation) unavailable.value = true }
}
watch(() => auth.generation, () => { usage.value = null; void refresh() })
onMounted(() => { void refresh(); timer = setInterval(() => void refresh(), 30000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>
<template>
  <details class="quota-panel is-collapsible" aria-live="polite">
    <summary>存储用量</summary>
    <div class="quota-details">
    <p v-if="usage">存储已用 {{ formatBytes(usage.usedBytes) }} / {{ formatBytes(usage.limitBytes) }} · 文件及保留副本 {{ usage.objectCount }} / {{ usage.limitObjects }}</p>
    <p v-else>{{ unavailable ? '暂时无法读取存储配额，请稍后刷新。' : '正在读取存储配额…' }}</p>
    <small>保留副本和待清理文件占用额度，物理清理完成后释放。<template v-if="usage">最多 {{ usage.pendingTaskLimit }} 个待处理任务，{{ usage.concurrentTaskLimit }} 个处理名额。</template></small>
    <button type="button" class="quota-refresh" @click="refresh"><el-icon><Refresh /></el-icon><span>刷新配额</span></button>
    </div>
  </details>
</template>
<style scoped>
.quota-panel { margin: 12px 0; padding: 0; border: 1px solid var(--line, #dedbd4); border-radius: 10px; }
.quota-panel p { margin: 0 0 6px; font-size: 13px; }
.quota-panel small { display: block; margin-bottom: 8px; }
.quota-refresh { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 32px; padding: 7px 12px; border: 1px solid var(--line-strong); border-radius: var(--radius-inner); background: var(--panel); color: var(--text-2); font: inherit; font-size: 12px; line-height: 1.4; white-space: nowrap; cursor: pointer; }
.quota-refresh:hover { background: var(--hover-bg); color: var(--text-1); }
.quota-refresh:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.quota-refresh .el-icon { font-size: 15px; }
</style>
