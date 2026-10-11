<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import AiProgress from '@/layout/AiProgress.vue'
import DesktopImportPanel from '@/components/DesktopImportPanel.vue'
import FileDetailDrawer from '@/components/FileDetailDrawer.vue'
import { useWorkspaceStore } from '@/stores/workspace'
const route = useRoute(), workspace = useWorkspaceStore()
const pending = computed(() => route.query.view === 'pending')
const fileId = ref<number | null>(null)
const progress = ref<InstanceType<typeof AiProgress>>()
function refresh() { void workspace.refresh(); void progress.value?.refresh() }
</script>
<template>
  <div class="desktop-queue pg">
    <header class="pg-head"><div><p class="queue-eyebrow">个人文件库 / {{ pending ? '标签确认' : '本地导入' }}</p><h1 class="pg-title">{{ pending ? '待确认' : '收件箱' }}</h1><p class="pg-sub">{{ pending ? '核对 AI 建议的标签，让你的文件更容易找到。' : '核对导入目标，确认正式入库，然后查看解析与接管结果。' }}</p></div><RouterLink to="/" class="queue-back">返回文件库</RouterLink></header>
    <DesktopImportPanel v-if="!pending" @queued="refresh" />
    <div class="queue-body"><AiProgress :key="String(pending)" ref="progress" :pending-only="pending" @open-file="fileId = $event" /><FileDetailDrawer v-model="fileId" inline @changed="refresh" /></div>
  </div>
</template>
<style scoped>
.desktop-queue { padding: 28px 30px 20px; gap: 20px; }
.queue-eyebrow { color: var(--text-3); font-size: 11px; margin: 0 0 8px; }
.queue-back { text-decoration: none; color: var(--accent); font-size: 12px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 7px; }
.queue-body { display: flex; flex: 1; min-height: 0; overflow: hidden; gap: 20px; }
.queue-body :deep(.progress-card) { flex: 1; border: 1px solid var(--line); background: var(--panel); }
.queue-body :deep(.card-head) { flex-wrap: wrap; }
.desktop-queue :deep(.desktop-import) { flex: none; max-height: 32vh; overflow-y: auto; margin: 0; }
@media (max-width: 1100px) { .desktop-queue { padding: 20px; } }
</style>
