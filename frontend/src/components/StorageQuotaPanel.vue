<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import http from '@/api/http'
import type { Result } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
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
  <aside class="quota-panel" aria-live="polite">
    <p v-if="usage">存储已用 {{ formatBytes(usage.usedBytes) }} / {{ formatBytes(usage.limitBytes) }} · 文件及保留副本 {{ usage.objectCount }} / {{ usage.limitObjects }}</p>
    <p v-else>{{ unavailable ? '暂时无法读取存储配额，请稍后刷新。' : '正在读取存储配额…' }}</p>
    <small>保留副本和待清理文件占用额度，物理清理完成后释放。<template v-if="usage">最多 {{ usage.pendingTaskLimit }} 个待处理任务，{{ usage.concurrentTaskLimit }} 个处理名额。</template></small>
    <button type="button" class="action-button" @click="refresh">刷新配额</button>
  </aside>
</template>
<style scoped>
.quota-panel { margin: 12px 0; padding: 12px 16px; border: 1px solid var(--line, #dedbd4); border-radius: 10px; }
.quota-panel p { margin: 0 0 6px; font-size: 13px; }
.quota-panel small { display: block; margin-bottom: 8px; }
</style>
