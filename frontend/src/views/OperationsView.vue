<script setup lang="ts">
import { Download, Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import * as governanceApi from '@/api/governance'
import type {
  ArchiveOperationBatchResponse,
  ArchiveOperationBatchSummaryResponse,
  ArchiveOperationItemResponse,
  GovernanceCompensationTaskResponse,
} from '@/api/types'
import { toDisplayTime } from '@/utils/format'

type BatchStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED'
type ItemStatus = 'PENDING' | 'VALIDATING' | 'COPYING' | 'DB_COMMITTING' | 'CLEANUP_PENDING' | 'SUCCEEDED' | 'FAILED' | 'CONFLICTED' | 'SKIPPED'

const STATUS_OPTIONS: { value: BatchStatus | ''; label: string }[] = [
  { value: '', label: '全部批次状态' },
  { value: 'PENDING', label: '等待执行' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'PARTIAL_FAILED', label: '部分失败' },
  { value: 'FAILED', label: '执行失败' },
  { value: 'CANCELLED', label: '已取消' },
]

const batchId = ref('')
const fileId = ref('')
const status = ref<BatchStatus | ''>('')
const loading = ref(false)
const exporting = ref(false)
const batches = ref<ArchiveOperationBatchSummaryResponse[]>([])
const totalElements = ref(0)
const page = ref(0)
const pageSize = 12
const selectedBatchId = ref<string | null>(null)
const selectedBatch = ref<ArchiveOperationBatchResponse | null>(null)
const detailLoading = ref(false)
const rollbackLoading = ref(false)
const compensations = ref<GovernanceCompensationTaskResponse[]>([])
const compensationLoading = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(totalElements.value / pageSize)))
const canPrevious = computed(() => page.value > 0)
const canNext = computed(() => page.value + 1 < totalPages.value)

function normalizedFileId() {
  const value = fileId.value.trim()
  if (!value) return undefined
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    ElMessage.warning('文件 ID 必须是正整数')
    return null
  }
  return parsed
}

function queryParams() {
  const parsedFileId = normalizedFileId()
  if (parsedFileId === null) return null
  return {
    batchId: batchId.value.trim() || undefined,
    fileId: parsedFileId,
    status: status.value || undefined,
    page: page.value,
    size: pageSize,
  }
}

async function load() {
  const params = queryParams()
  if (!params || loading.value) return
  loading.value = true
  try {
    const { data } = await governanceApi.searchArchiveOperationBatches(params)
    const result = data.data
    batches.value = result?.content ?? []
    totalElements.value = result?.totalElements ?? 0
    if (selectedBatchId.value && !batches.value.some((item) => item.batchId === selectedBatchId.value)) {
      selectedBatchId.value = null
      selectedBatch.value = null
    }
  } catch {
    batches.value = []
    totalElements.value = 0
  } finally {
    loading.value = false
  }
}

async function openBatch(batch: ArchiveOperationBatchSummaryResponse) {
  selectedBatchId.value = batch.batchId
  selectedBatch.value = null
  detailLoading.value = true
  try {
    const [operation, compensation] = await Promise.all([
      governanceApi.getArchiveOperation(batch.batchId),
      governanceApi.getGovernanceCompensations(batch.batchId),
    ])
    selectedBatch.value = operation.data.data
    compensations.value = compensation.data.data ?? []
  } finally {
    detailLoading.value = false
  }
}

async function retryCompensations() {
  if (!selectedBatch.value || compensationLoading.value) return
  compensationLoading.value = true
  try {
    const { data } = await governanceApi.retryGovernanceCompensations(selectedBatch.value.batchId)
    compensations.value = data.data ?? []
    ElMessage.success('补偿任务已重新入队')
  } finally { compensationLoading.value = false }
}

function compensationLabel(value?: string | null) {
  const labels: Record<string, string> = {
    RESUME_ARCHIVE: '恢复归档执行', DELETE_ARCHIVE_SOURCE: '清理归档前对象',
    RESUME_ROLLBACK: '恢复撤销执行', DELETE_ROLLBACK_TARGET: '清理撤销前对象',
  }
  return labels[value ?? ''] ?? value ?? '未知补偿'
}

function resetFilters() {
  batchId.value = ''
  fileId.value = ''
  status.value = ''
  page.value = 0
  selectedBatchId.value = null
  selectedBatch.value = null
  load()
}

function search() {
  page.value = 0
  selectedBatchId.value = null
  selectedBatch.value = null
  load()
}

function previousPage() {
  if (!canPrevious.value) return
  page.value -= 1
  load()
}

function nextPage() {
  if (!canNext.value) return
  page.value += 1
  load()
}

async function exportLedger(format: 'json' | 'csv') {
  const parsedFileId = normalizedFileId()
  if (parsedFileId === null) return
  exporting.value = true
  try {
    const { data } = await governanceApi.exportArchiveOperations({
      batchId: batchId.value.trim() || undefined,
      fileId: parsedFileId,
      status: undefined,
      format,
    })
    const blob = data instanceof Blob ? data : new Blob([data])
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `archive-operations.${format}`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${format.toUpperCase()} 台账`)
  } finally {
    exporting.value = false
  }
}

function batchStatusLabel(value?: string | null) {
  return STATUS_OPTIONS.find((item) => item.value === value)?.label ?? value ?? '未知状态'
}

function itemStatusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    PENDING: '等待执行',
    VALIDATING: '校验中',
    COPYING: '复制中',
    DB_COMMITTING: '提交元数据',
    CLEANUP_PENDING: '待清理旧对象',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    CONFLICTED: '版本冲突',
    SKIPPED: '已跳过',
  }
  return labels[value ?? ''] ?? value ?? '未知'
}

function sourceLabel(value?: string | null) {
  const labels: Record<string, string> = {
    PREVIEW_CONFIRMATION: '预览确认',
    MANUAL_RETRY: '手动重试',
    ROLLBACK_RETRY: '撤销重试',
  }
  return labels[value ?? ''] ?? value ?? '未知来源'
}

function itemStatusClass(value?: string | null) {
  return `is-${String(value ?? '').toLowerCase()}`
}

function countText(batch: ArchiveOperationBatchSummaryResponse) {
  return `成功 ${batch.successCount} · 失败 ${batch.failedCount} · 冲突 ${batch.conflictedCount}`
}

function itemFailure(item: ArchiveOperationItemResponse) {
  return item.failureCode ? `[${item.failureCode}] ${item.failureMessage ?? ''}` : item.failureMessage
}

function rollbackLabel(value?: string | null) {
  const labels: Record<string, string> = {
    NOT_REQUESTED: '未撤销', PENDING: '等待撤销', RUNNING: '撤销中', VALIDATING: '校验中',
    COPYING: '恢复对象', DB_COMMITTING: '恢复元数据', CLEANUP_PENDING: '清理归档对象',
    SUCCEEDED: '已撤销', PARTIAL_FAILED: '部分失败', FAILED: '撤销失败',
    CONFLICTED: '撤销冲突', NOT_REVERSIBLE: '不可撤销',
  }
  return labels[value ?? ''] ?? value ?? '未知'
}

function canRollbackItem(item: ArchiveOperationItemResponse) {
  return item.executionStatus === 'SUCCEEDED'
    && ['NOT_REQUESTED', 'FAILED', 'CONFLICTED'].includes(item.rollbackStatus)
}

async function rollbackBatch() {
  if (!selectedBatch.value || rollbackLoading.value) return
  await ElMessageBox.confirm('将按台账恢复本批次可撤销文件；发生冲突时不会覆盖现有文件。是否继续？', '批量撤销', { type: 'warning' })
  rollbackLoading.value = true
  try {
    const { data } = await governanceApi.rollbackArchiveOperation(selectedBatch.value.batchId)
    selectedBatch.value = data.data
    ElMessage.success('批量撤销已提交')
    await load()
  } finally { rollbackLoading.value = false }
}

async function rollbackItem(item: ArchiveOperationItemResponse) {
  if (!selectedBatch.value || rollbackLoading.value) return
  await ElMessageBox.confirm(`确认将“${item.targetFileName || item.sourceFileName}”恢复到原路径？`, '单文件撤销', { type: 'warning' })
  rollbackLoading.value = true
  try {
    const { data } = await governanceApi.rollbackArchiveOperationItem(selectedBatch.value.batchId, item.id)
    selectedBatch.value = data.data
    ElMessage.success('单文件撤销已提交')
    await load()
  } finally { rollbackLoading.value = false }
}

onMounted(load)
</script>

<template>
  <div class="pg operations-page">
    <header class="pg-head">
      <div>
        <h1 class="pg-title">操作台账</h1>
        <p class="pg-sub">追踪确认后的归档批次、原路径与新路径，以及每个文件的执行结果。</p>
      </div>
      <div class="head-actions">
        <button class="ghost-btn" :disabled="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          刷新
        </button>
        <button class="ghost-btn" :disabled="exporting" @click="exportLedger('json')">
          <el-icon><Download /></el-icon>
          JSON
        </button>
        <button class="primary-btn" :disabled="exporting" @click="exportLedger('csv')">
          <el-icon><Download /></el-icon>
          CSV
        </button>
      </div>
    </header>

    <section class="pg-panel filter-panel">
      <div class="filter-grid">
        <label class="field">
          <span class="field-label">批次 ID</span>
          <input v-model="batchId" class="field-input" placeholder="支持输入完整或部分批次 ID" @keyup.enter="search" />
        </label>
        <label class="field">
          <span class="field-label">文件 ID</span>
          <input v-model="fileId" class="field-input" inputmode="numeric" placeholder="按文件查看历史操作" @keyup.enter="search" />
        </label>
        <label class="field">
          <span class="field-label">批次状态</span>
          <select v-model="status" class="field-input">
            <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <div class="filter-actions">
          <button class="ghost-btn" @click="resetFilters">重置</button>
          <button class="primary-btn" @click="search">
            <el-icon><Search /></el-icon>
            查询台账
          </button>
        </div>
      </div>
    </section>

    <div class="ledger-layout">
      <section class="pg-panel batch-panel">
        <div class="panel-heading">
          <div>
            <h2 class="panel-title">操作批次</h2>
            <p class="panel-note">共 {{ totalElements }} 个批次，按创建时间倒序</p>
          </div>
          <span v-if="loading" class="panel-note">读取中…</span>
        </div>

        <div v-if="batches.length" class="batch-list">
          <button
            v-for="batch in batches"
            :key="batch.batchId"
            class="batch-row"
            :class="{ 'is-active': selectedBatchId === batch.batchId }"
            @click="openBatch(batch)"
          >
            <div class="batch-row-head">
              <code>{{ batch.batchId }}</code>
              <span class="status-pill" :class="itemStatusClass(batch.status)">{{ batchStatusLabel(batch.status) }}</span>
            </div>
            <div class="batch-row-meta">
              <span>{{ sourceLabel(batch.source) }}</span>
              <span>{{ batch.createdAt ? toDisplayTime(batch.createdAt) : '—' }}</span>
            </div>
            <div class="batch-counts">{{ countText(batch) }}</div>
            <p v-if="batch.failureSummary" class="batch-failure">{{ batch.failureSummary }}</p>
          </button>
        </div>
        <div v-else-if="!loading" class="empty-panel">
          <p>没有匹配的操作批次</p>
          <span>确认预览后，归档执行记录会显示在这里。</span>
        </div>

        <div v-if="totalElements" class="pager">
          <button class="pager-btn" :disabled="!canPrevious || loading" @click="previousPage">上一页</button>
          <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
          <button class="pager-btn" :disabled="!canNext || loading" @click="nextPage">下一页</button>
        </div>
      </section>

      <section class="pg-panel detail-panel">
        <div v-if="detailLoading" class="empty-panel"><p>正在加载明细…</p></div>
        <template v-else-if="selectedBatch">
          <div class="detail-head">
            <div>
              <p class="eyebrow">OPERATION DETAIL</p>
              <h2 class="panel-title">{{ selectedBatch.batchId }}</h2>
              <p class="panel-note">
                来源 {{ sourceLabel(selectedBatch.source) }} · 预览 {{ selectedBatch.previewId || '—' }}
              </p>
            </div>
            <div class="head-actions">
              <span class="status-pill" :class="itemStatusClass(selectedBatch.rollbackStatus)">撤销：{{ rollbackLabel(selectedBatch.rollbackStatus) }}</span>
              <button class="ghost-btn" :disabled="rollbackLoading || selectedBatch.rollbackStatus === 'SUCCEEDED'" @click="rollbackBatch">批量撤销</button>
            </div>
          </div>

          <div class="detail-stats">
            <span>总计 {{ selectedBatch.totalCount }}</span>
            <span class="is-ok">成功 {{ selectedBatch.successCount }}</span>
            <span class="is-danger">失败 {{ selectedBatch.failedCount }}</span>
            <span class="is-warn">冲突 {{ selectedBatch.conflictedCount }}</span>
            <span>跳过 {{ selectedBatch.skippedCount }}</span>
          </div>

          <div v-if="compensations.length" class="compensation-panel">
            <div class="panel-heading">
              <strong>异常补偿</strong>
              <button class="ghost-btn compact-btn" :disabled="compensationLoading" @click="retryCompensations">重试未完成任务</button>
            </div>
            <div v-for="task in compensations" :key="task.id" class="compensation-row">
              <span>{{ compensationLabel(task.action) }} · 文件 {{ task.itemId }}</span>
              <span class="item-status" :class="itemStatusClass(task.status)">{{ task.status }}</span>
              <span>尝试 {{ task.attempts }} 次</span>
              <span v-if="task.lastError" class="detail-failure">{{ task.lastError }}</span>
            </div>
          </div>

          <div class="detail-list">
            <article v-for="item in selectedBatch.items" :key="item.id" class="detail-item">
              <div class="detail-item-head">
                <div>
                  <strong>{{ item.sourceFileName || `文件 #${item.fileId}` }}</strong>
                  <span class="file-id">文件 ID {{ item.fileId }}</span>
                </div>
                <div class="head-actions">
                  <span class="item-status" :class="itemStatusClass(item.rollbackStatus)">撤销：{{ rollbackLabel(item.rollbackStatus) }}</span>
                  <button v-if="canRollbackItem(item)" class="ghost-btn compact-btn" :disabled="rollbackLoading" @click="rollbackItem(item)">撤销</button>
                  <span class="item-status" :class="itemStatusClass(item.executionStatus)">{{ itemStatusLabel(item.executionStatus) }}</span>
                </div>
              </div>
              <div class="path-grid">
                <div><span>原路径</span><code>{{ item.sourcePath || '—' }}</code></div>
                <div><span>新路径</span><code>{{ item.targetPath || '—' }}</code></div>
              </div>
              <div class="detail-item-meta">
                <span>原分类 {{ item.sourceCategory || '—' }} → {{ item.targetCategory || '—' }}</span>
                <span>执行 {{ item.startedAt ? toDisplayTime(item.startedAt) : '—' }}</span>
                <span>尝试 {{ item.attempts }} 次</span>
              </div>
              <p v-if="itemFailure(item)" class="detail-failure">{{ itemFailure(item) }}</p>
            </article>
          </div>
        </template>
        <div v-else class="empty-panel">
          <el-icon class="empty-icon"><View /></el-icon>
          <p>选择一个操作批次查看明细</p>
          <span>这里会展示原路径、新路径、来源、时间和失败原因。</span>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.operations-page {
  overflow: auto;
  padding-right: 4px;
}
.head-actions,
.filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ghost-btn,
.primary-btn,
.pager-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  padding: 7px 12px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  color: var(--text-1);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.primary-btn {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.ghost-btn:hover:not(:disabled),
.pager-btn:hover:not(:disabled) {
  border-color: var(--accent);
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.filter-panel {
  flex: none;
}
.filter-grid {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) minmax(140px, 0.8fr) minmax(160px, 0.9fr) auto;
  align-items: end;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.field-label,
.panel-note {
  color: var(--text-3);
  font-size: 11.5px;
}
.field-input {
  width: 100%;
  height: 36px;
  box-sizing: border-box;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-inner);
  padding: 0 10px;
  outline: none;
  background: var(--panel-3);
  color: var(--text-1);
  font: inherit;
  font-size: 12px;
}
.field-input:focus {
  border-color: var(--accent);
}
.ledger-layout {
  display: grid;
  grid-template-columns: minmax(300px, 0.85fr) minmax(0, 1.5fr);
  gap: var(--gap);
  min-height: 0;
  flex: 1;
}
.batch-panel,
.detail-panel {
  min-height: 0;
  overflow: hidden;
}
.panel-heading,
.detail-head,
.batch-row-head,
.batch-row-meta,
.detail-item-head,
.detail-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.panel-title {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-display);
  font-size: 16px;
}
.panel-note {
  margin: 4px 0 0;
}
.batch-list,
.detail-list {
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 14px;
}
.batch-list {
  flex: 1;
}
.batch-row {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  padding: 12px;
  background: var(--panel-2);
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.batch-row:hover,
.batch-row.is-active {
  border-color: var(--accent);
  background: var(--panel-3);
}
.batch-row code,
.path-grid code {
  overflow: hidden;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-pill,
.item-status {
  flex: none;
  border-radius: var(--radius-pill);
  padding: 4px 9px;
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 10.5px;
}
.status-pill.is-succeeded,
.item-status.is-succeeded {
  background: rgb(var(--ok-rgb) / 0.13);
  color: var(--ok);
}
.status-pill.is-pending,
.status-pill.is-running,
.item-status.is-pending,
.item-status.is-validating,
.item-status.is-copying,
.item-status.is-db_committing,
.item-status.is-cleanup_pending {
  background: var(--warn-tint);
  color: var(--warn);
}
.status-pill.is-failed,
.status-pill.is-partial_failed,
.item-status.is-failed,
.item-status.is-conflicted {
  background: rgb(var(--danger-rgb) / 0.12);
  color: var(--danger);
}
.batch-row-meta,
.batch-counts,
.detail-item-meta,
.file-id {
  color: var(--text-3);
  font-size: 11px;
}
.batch-row-meta {
  margin-top: 8px;
}
.batch-counts {
  margin-top: 7px;
}
.batch-failure,
.detail-failure {
  margin: 8px 0 0;
  color: var(--danger);
  font-size: 11px;
  line-height: 1.5;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 12px;
  color: var(--text-3);
  font-size: 11px;
}
.detail-panel {
  display: flex;
  flex-direction: column;
}
.detail-head {
  align-items: flex-start;
  flex: none;
}
.eyebrow {
  margin: 0 0 5px;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}
.detail-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 16px;
  padding: 11px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  color: var(--text-2);
  font-size: 11.5px;
}
.compensation-panel { margin-top: 12px; padding: 11px; border: 1px solid var(--warn); border-radius: var(--radius-inner); }
.compensation-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 8px; color: var(--text-3); font-size: 11px; }
.detail-stats .is-ok {
  color: var(--ok);
}
.detail-stats .is-danger {
  color: var(--danger);
}
.detail-stats .is-warn {
  color: var(--warn);
}
.detail-list {
  flex: 1;
}
.detail-item {
  padding: 13px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: var(--panel-2);
}
.detail-item-head {
  align-items: flex-start;
}
.detail-item-head strong {
  display: block;
  overflow: hidden;
  color: var(--text-1);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.compact-btn { min-height: 26px; padding: 3px 8px; }
.file-id {
  display: block;
  margin-top: 4px;
}
.path-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 12px;
}
.path-grid > div {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.path-grid span {
  color: var(--text-3);
  font-size: 10.5px;
}
.detail-item-meta {
  justify-content: flex-start;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}
.empty-panel {
  display: grid;
  place-content: center;
  gap: 5px;
  min-height: 220px;
  color: var(--text-3);
  text-align: center;
  font-size: 12px;
}
.empty-panel p {
  margin: 0;
  color: var(--text-1);
  font-size: 14px;
}
.empty-panel span {
  font-size: 11px;
}
.empty-icon {
  justify-self: center;
  margin-bottom: 4px;
  color: var(--accent);
  font-size: 22px;
}
@media (max-width: 1050px) {
  .filter-grid {
    grid-template-columns: 1fr 1fr;
  }
  .filter-actions {
    justify-content: flex-end;
  }
  .ledger-layout {
    grid-template-columns: 1fr;
    overflow: visible;
  }
  .batch-panel,
  .detail-panel {
    min-height: 380px;
  }
}
@media (max-width: 620px) {
  .head-actions {
    width: 100%;
  }
  .head-actions > button {
    flex: 1;
  }
  .filter-grid {
    grid-template-columns: 1fr;
  }
  .filter-actions > button {
    flex: 1;
  }
  .path-grid {
    grid-template-columns: 1fr;
  }
}
</style>
