<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { retryFile } from '@/api/files'
import { getInboxImportProgress, getOverview } from '@/api/tasks'
import type { InboxImportProgressResponse, TaskOverviewResponse } from '@/api/types'
import FileTile from '@/components/FileTile.vue'

const router = useRouter()

/** 统一的三类任务条目（处理中 → 待确认 → 失败） */
interface OverviewRow {
  key: string
  kind: 'processing' | 'pending' | 'failed'
  name: string
  fileType: string | null
  progress?: number
  fileId?: number | null
  error?: string | null
}

const POLL_MS = 5000

const overview = ref<TaskOverviewResponse | null>(null)
const inbox = ref<InboxImportProgressResponse | null>(null)
const loading = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const inboxCompletedCount = computed(() => {
  const i = inbox.value
  return i ? i.importedCount + i.duplicateCount : 0
})

const inboxProgressPercent = computed(() => {
  const i = inbox.value
  if (!i || i.totalCount === 0) return 0
  return Math.min(100, Math.round((inboxCompletedCount.value / i.totalCount) * 100))
})

const rows = computed<OverviewRow[]>(() => {
  const o = overview.value
  if (!o) return []
  const list: OverviewRow[] = []
  for (const t of o.processing ?? []) {
    list.push({
      key: `p-${t.taskId}`,
      kind: 'processing',
      name: t.fileName,
      fileType: extOf(t.fileName),
      progress: t.progress,
    })
  }
  for (const f of o.pendingConfirm ?? []) {
    list.push({
      key: `pending-${f.fileId}`,
      kind: 'pending',
      name: f.fileName,
      fileType: extOf(f.fileName),
      fileId: f.fileId,
    })
  }
  for (const f of o.failed ?? []) {
    list.push({
      key: `f-${f.taskId}`,
      kind: 'failed',
      name: f.fileName,
      fileType: extOf(f.fileName),
      fileId: f.fileId,
      error: f.error,
    })
  }
  return list
})

/** 从文件名提取扩展名（无则返回 null），驱动 FileTile 图章 */
function extOf(name: string): string | null {
  const m = /\.([A-Za-z0-9]+)$/.exec(name)
  return m ? m[1].toLowerCase() : null
}

async function load() {
  loading.value = true
  try {
    const { data } = await getOverview()
    overview.value = data.data
  } catch {
    /* 拦截器已弹错：保留上次数据，避免整卡闪烁 */
  }
  try {
    const { data } = await getInboxImportProgress()
    inbox.value = data.data
  } catch {
    /* C04 接口不可用时不影响既有 AI 任务面板 */
  } finally {
    loading.value = false
  }
}

/** 失败重试（fileId 为空说明文件已被删除，不展示重试按钮） */
async function doRetry(row: OverviewRow) {
  if (!row.fileId) return
  const { data } = await retryFile(row.fileId)
  void data
  ElMessage.success('已重新开始解析')
  await load()
}

onMounted(() => {
  load()
  timer = setInterval(load, POLL_MS)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <section class="progress-card">
    <div class="card-head">
      <h2 class="card-title">AI 解析进度</h2>
      <span class="card-count">
        处理中 {{ overview?.processingCount ?? 0 }} · 待确认
        {{ overview?.pendingConfirmCount ?? 0 }} · 失败 {{ overview?.failedCount ?? 0 }}
        <template v-if="inbox?.enabled"> · 批量导入 {{ inboxCompletedCount }}/{{ inbox.totalCount }}</template>
      </span>
    </div>

    <div v-if="inbox?.enabled" class="inbox-summary">
      <div class="inbox-summary-head">
        <span>收件箱批量导入</span>
        <span>{{ inboxProgressPercent }}%</span>
      </div>
      <div class="inbox-bar-track">
        <div class="inbox-bar-fill" :style="{ width: inboxProgressPercent + '%' }"></div>
      </div>
      <p class="inbox-summary-detail">
        已导入 {{ inbox.importedCount }} · 重复 {{ inbox.duplicateCount }} ·
        待稳定 {{ inbox.discoveredCount + inbox.stableCount }} · 失败 {{ inbox.failedCount }}
      </p>
    </div>

    <div v-if="loading && !rows.length" class="progress-empty">
      <p>加载中…</p>
    </div>

    <ul v-else-if="rows.length" class="task-list">
      <li v-for="t in rows" :key="t.key" class="task-item">
        <div class="task-top">
          <FileTile :file-type="t.fileType" size="sm" />
          <span class="task-name">{{ t.name }}</span>
          <span class="task-tag" :class="`is-${t.kind}`">
            {{ t.kind === 'processing' ? '解析中' : t.kind === 'pending' ? '待确认' : '失败' }}
          </span>
        </div>

        <p v-if="t.error" class="task-err">{{ t.error }}</p>

        <!-- 处理中：进度条 -->
        <div v-if="t.kind === 'processing'" class="task-bar">
          <div class="bar-track">
            <div class="bar-fill" :style="{ width: (t.progress ?? 0) + '%' }"></div>
          </div>
          <span class="bar-percent">{{ t.progress }}%</span>
        </div>

        <!-- 待确认：去标签确认（跳转全部文件页处理） -->
        <button
          v-else-if="t.kind === 'pending'"
          class="task-btn is-select"
          @click="router.push('/files')"
        >
          去确认标签
        </button>

        <!-- 失败：重试（文件已删则仅展示） -->
        <button v-else-if="t.fileId" class="task-btn is-retry" @click="doRetry(t)">重试</button>
      </li>
    </ul>

    <!-- 空态 -->
    <div v-else class="progress-empty">
      <p>当前没有待处理的任务</p>
      <p class="progress-empty-sub">上传文件后，AI 会自动开始解析与分类。</p>
    </div>
  </section>
</template>

<style scoped>
.progress-card {
  background: var(--mint);
  border-radius: var(--radius-card);
  padding: var(--pad);
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.card-title,
.card-count {
  color: var(--ink-mint);
}
.card-count {
  opacity: 0.85;
}

.task-list {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.inbox-summary {
  flex: none;
  margin: 10px 0;
  padding: 10px 14px;
  border-radius: var(--radius-inner);
  background: var(--card-item-bg);
}
.inbox-summary-head {
  display: flex;
  justify-content: space-between;
  color: var(--ink-mint);
  font-size: 12.5px;
  font-weight: 600;
}
.inbox-bar-track {
  height: 6px;
  margin-top: 8px;
  border-radius: var(--radius-pill);
  background: rgb(var(--ink-mint-rgb) / 0.2);
  overflow: hidden;
}
.inbox-bar-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--ink-mint);
  transition: width 0.25s ease;
}
.inbox-summary-detail {
  margin: 7px 0 0;
  color: rgb(var(--ink-mint-rgb) / 0.7);
  font-size: 11.5px;
}

.task-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px;
  border-radius: var(--radius-inner);
  background: var(--card-item-bg);
}

.task-top {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.task-name {
  flex: 1;
  min-width: 0;
  color: var(--ink-mint);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-tag {
  flex: none;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: 11.5px;
  background: var(--card-chip-bg);
  color: var(--ink-mint);
}
.task-tag.is-processing {
  background: #fff;
  color: var(--ink-mint-strong);
}
.task-tag.is-pending {
  background: var(--warn);
  color: var(--on-warn);
}
.task-tag.is-failed {
  background: var(--danger);
  color: #fff;
}

.task-err {
  margin: 0;
  font-size: 11.5px;
  color: rgb(var(--ink-mint-rgb) / 0.7);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-track {
  flex: 1;
  height: 6px;
  border-radius: var(--radius-pill);
  background: rgb(var(--ink-mint-rgb) / 0.2);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--ink-mint);
}
.bar-percent {
  flex: none;
  font-size: 11.5px;
  font-family: var(--font-mono);
  color: var(--ink-mint);
  opacity: 0.9;
}

.task-btn {
  align-self: flex-start;
  padding: 6px 16px;
  border: none;
  border-radius: var(--radius-inner);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}
.task-btn.is-select {
  background: #fff;
  color: var(--ink-mint-strong);
  font-weight: 600;
}
.task-btn.is-retry {
  background: var(--danger);
  color: #fff;
}

.progress-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  text-align: center;
  color: rgb(var(--ink-mint-rgb) / 0.85);
  font-size: 13.5px;
}
.progress-empty-sub {
  margin: 0;
  font-size: 12px;
  color: rgb(var(--ink-mint-rgb) / 0.6);
}
</style>
