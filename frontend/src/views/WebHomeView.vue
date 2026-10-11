<script setup lang="ts">
import { ArrowRight, Search } from '@element-plus/icons-vue'
import UploadZone from '@/layout/UploadZone.vue'
import RecentUploads from '@/layout/RecentUploads.vue'
import SuggestTags from '@/layout/SuggestTags.vue'
import ChatDock from '@/layout/ChatDock.vue'
import AiProgress from '@/layout/AiProgress.vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { nextTick, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
const auth = useAuthStore(), workspace = useWorkspaceStore()
const route = useRoute()
async function focusTasks() {
  if (route.hash === '#web-task-progress') { await nextTick(); document.getElementById('web-task-progress')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }) }
}
onMounted(focusTasks)
watch(() => route.hash, focusTasks)
</script>
<template>
  <div class="web-home">
    <header class="web-home-head"><div><p class="web-greeting">你好，{{ auth.user?.username }}</p><h1>工作台</h1><p class="web-home-description">上传、查找、整理，让每一份文件都有迹可循。</p></div><RouterLink to="/files" class="web-home-browse">浏览文件库 <el-icon><ArrowRight /></el-icon></RouterLink></header>
    <div class="web-overview" aria-label="文件任务概览"><span><i class="overview-dot"></i>正在解析 <strong>{{ workspace.overview?.processingCount ?? '—' }}</strong></span><span>待确认标签 <strong>{{ workspace.overview?.pendingConfirmCount ?? '—' }}</strong></span><span>失败任务 <strong>{{ workspace.overview?.failedCount ?? '—' }}</strong></span><span v-if="workspace.unavailable" role="status">任务状态暂时无法读取</span><RouterLink to="/search"><el-icon><Search /></el-icon>用自然语言找文件</RouterLink></div>
    <div class="web-home-workspace">
      <div class="web-home-library"><UploadZone compact /><RecentUploads /><SuggestTags compact /></div>
      <div class="web-home-side"><ChatDock /><AiProgress id="web-task-progress" /></div>
    </div>
  </div>
</template>
<style scoped>
.web-home { display: flex; flex-direction: column; min-width: 0; min-height: 100%; height: 100%; padding: 26px 30px 24px; gap: 16px; }
.web-home-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; }.web-home-head h1 { margin: 0; font-size: 25px; letter-spacing: -.5px; }.web-greeting { font-size: 11px; color: var(--text-3); margin: 0 0 4px; }.web-home-description { margin: 5px 0 0; color: var(--text-2); font-size: 12px; }.web-home-browse { display: inline-flex; align-items: center; gap: 7px; color: var(--accent); font-size: 12px; text-decoration: none; border: 1px solid var(--line-strong); border-radius: 7px; padding: 8px 12px; white-space: nowrap; }
.web-overview { display: flex; gap: 24px; align-items: center; padding: 10px 0 16px; border-bottom: 1px solid var(--line); font-size: 11px; color: var(--text-3); }.web-overview > span { display: flex; gap: 8px; align-items: center; }.web-overview strong { font-size: 13px; font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; }.overview-dot { display: block; width: 5px; height: 5px; background: var(--accent); border-radius: 50%; }.web-overview a { margin-left: auto; display: flex; align-items: center; gap: 6px; color: var(--accent); text-decoration: none; }
.web-home-workspace { flex: 1; min-height: 550px; display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(320px, 1fr); gap: 20px; }
.web-home-library { display: flex; flex-direction: column; min-width: 0; min-height: 0; gap: 16px; }.web-home-library :deep(.recent-card) { flex: 1; min-height: 250px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }.web-home-library :deep(.recent-item) { border-bottom: 1px solid var(--line); background: transparent; padding: 14px 0; cursor: pointer; border-radius: 0; }.web-home-library :deep(.recent-item:hover) { background: var(--card-hover-bg); }.web-home-library :deep(.file-name) { font-size: 13px; }.web-home-library :deep(.file-desc) { font-size: 11px; }.web-home-library :deep(.file-state) { font-size: 10px; background: var(--panel-3); border-radius: 5px; padding: 3px 7px; }.web-home-library :deep(.card-title) { color: var(--text-1); }.web-home-library :deep(.card-count) { color: var(--text-3); }.web-home-library :deep(.recent-empty) { color: var(--text-3); }
.web-home-side { display: flex; flex-direction: column; gap: 16px; min-width: 0; min-height: 0; }.web-home-side :deep(.chat-card) { flex: 1; min-height: 300px; border: 1px solid var(--line); }.web-home-side :deep(.progress-card) { flex: 0 0 240px; overflow: auto; border: 1px solid var(--line); background: var(--panel); border-radius: 10px; }.web-home-side :deep(.progress-card .card-head) { flex-wrap: wrap; gap: 4px; margin-bottom: 10px; }.web-home-side :deep(.progress-card .card-title) { font-size: 12px; }.web-home-side :deep(.progress-card .card-count) { font-size: 10px; }.web-home-side :deep(.task-btn) { font-size: 11px; padding: 4px 9px; }.web-home-side :deep(.task-name) { font-size: 12px; }
@media (max-width: 1200px) { .web-home { padding: 22px; }.web-home-workspace { gap: 16px; grid-template-columns: minmax(0, 1.3fr) minmax(300px, 1fr); }.web-overview { gap: 16px; }.web-overview a { font-size: 10px; } }
@media (max-width: 1050px) { .web-home { height: auto; }.web-home-workspace { grid-template-columns: minmax(0, 1fr); }.web-home-library :deep(.recent-card) { min-height: 320px; }.web-home-side { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }.web-home-side :deep(.chat-card) { min-height: 370px; }.web-home-side :deep(.progress-card) { max-height: 370px; } }
@media (max-width: 620px) { .web-home { padding: 20px 16px; }.web-home-head { align-items: flex-start; }.web-home-head h1 { font-size: 22px; }.web-home-description { max-width: 230px; font-size: 11px; }.web-home-browse { font-size: 10px; padding: 6px 9px; margin-top: 22px; }.web-overview { gap: 14px; flex-wrap: wrap; padding-bottom: 14px; }.web-overview a { margin-left: 0; width: 100%; }.web-home-side { display: flex; }.web-home-side :deep(.progress-card) { flex-basis: 270px; }.web-home-library :deep(.card-head) { flex-wrap: wrap; } }
</style>
