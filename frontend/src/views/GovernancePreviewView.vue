<script setup lang="ts">
import { ArrowLeft, Check, Refresh, Select } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import * as governanceApi from '@/api/governance'
import type {
  Category,
  GovernancePreviewItemResponse,
  GovernancePreviewResponse,
  UpdateGovernancePreviewItemRequest,
} from '@/api/types'
import FileTile from '@/components/FileTile.vue'
import { formatBytes, toDisplayTime } from '@/utils/format'

type Draft = {
  fileName: string
  category: Category
  summary: string
  tags: string
}

const CATEGORY_OPTIONS: { value: Category; label: string }[] = [
  { value: 'CONTRACT', label: '合同' },
  { value: 'INVOICE', label: '发票' },
  { value: 'REPORT', label: '报告' },
  { value: 'ID', label: '证件' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'OTHER', label: '其他' },
]

const route = useRoute()
const router = useRouter()
const previewId = computed(() => String(route.params.previewId ?? ''))

const preview = ref<GovernancePreviewResponse | null>(null)
const loading = ref(false)
const actionId = ref<number | null>(null)
const confirming = ref(false)
const cancelling = ref(false)
const retryingOperation = ref(false)
const drafts = reactive<Record<number, Draft>>({})
const selectedIds = ref<Set<number>>(new Set())

const items = computed(() => preview.value?.items ?? [])
const latestOperation = computed(() => preview.value?.latestOperation ?? null)
const confirmableItems = computed(() => items.value.filter(isConfirmable))
const selectedCount = computed(() => selectedIds.value.size)
const canCancel = computed(() => {
  const status = preview.value?.status
  return status !== 'CANCELLED' && status !== 'EXPIRED' && status !== 'CONFIRMED'
    && status !== 'PARTIALLY_CONFIRMED'
})

function isConfirmable(item: GovernancePreviewItemResponse) {
  return item.status === 'READY' || item.status === 'EDITED'
}

function isEditable(item: GovernancePreviewItemResponse) {
  return item.status === 'READY' || item.status === 'EDITED' || item.status === 'CONFLICTED'
}

function isSkippable(item: GovernancePreviewItemResponse) {
  return !['CONFIRMED', 'SKIPPED', 'EXPIRED', 'CANCELLED'].includes(item.status)
}

function statusLabel(status: GovernancePreviewItemResponse['status']) {
  const labels: Record<GovernancePreviewItemResponse['status'], string> = {
    PENDING: '等待处理',
    READY: '待确认',
    EDITED: '已修改',
    CONFLICTED: '存在冲突',
    CONFIRMED: '已确认',
    SKIPPED: '已跳过',
    FAILED: '分析失败',
    EXPIRED: '已过期',
    CANCELLED: '已取消',
  }
  return labels[status]
}

function categoryLabel(value?: string | null) {
  return CATEGORY_OPTIONS.find((item) => item.value === value)?.label ?? value ?? '其他'
}

function operationStatusLabel(status?: string | null) {
  const labels: Record<string, string> = {
    PENDING: '等待执行',
    RUNNING: '执行中',
    SUCCEEDED: '已完成',
    PARTIAL_FAILED: '部分失败',
    FAILED: '执行失败',
    CANCELLED: '已取消',
  }
  return labels[status ?? ''] ?? status ?? '未知状态'
}

function operationItemStatusLabel(status?: string | null) {
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
  return labels[status ?? ''] ?? status ?? '未知'
}

function draftOf(item: GovernancePreviewItemResponse): Draft {
  return drafts[item.id] ?? {
    fileName: item.suggestedFileName || item.sourceFileName,
    category: (item.suggestedCategory || 'OTHER') as Category,
    summary: item.suggestedSummary || '',
    tags: (item.suggestedTags ?? []).join('，'),
  }
}

function syncDrafts(nextItems: GovernancePreviewItemResponse[]) {
  const activeIds = new Set(nextItems.map((item) => item.id))
  for (const key of Object.keys(drafts)) {
    if (!activeIds.has(Number(key))) delete drafts[Number(key)]
  }
  for (const item of nextItems) {
    drafts[item.id] = draftOf(item)
  }
}

function syncSelection(nextItems: GovernancePreviewItemResponse[]) {
  const allowed = new Set(nextItems.filter(isConfirmable).map((item) => item.id))
  selectedIds.value = new Set([...selectedIds.value].filter((id) => allowed.has(id)))
}

async function load() {
  if (!previewId.value) return
  loading.value = true
  try {
    const { data } = await governanceApi.getGovernancePreview(previewId.value)
    preview.value = data.data
    syncDrafts(data.data?.items ?? [])
    syncSelection(data.data?.items ?? [])
  } catch {
    preview.value = null
  } finally {
    loading.value = false
  }
}

function toggleSelected(item: GovernancePreviewItemResponse) {
  if (!isConfirmable(item)) return
  const next = new Set(selectedIds.value)
  if (next.has(item.id)) next.delete(item.id)
  else next.add(item.id)
  selectedIds.value = next
}

function selectAll() {
  selectedIds.value = new Set(confirmableItems.value.map((item) => item.id))
}

function clearSelection() {
  selectedIds.value = new Set()
}

function buildItemUpdate(item: GovernancePreviewItemResponse): UpdateGovernancePreviewItemRequest | null {
  const draft = drafts[item.id]
  if (!draft) return null
  const tags = draft.tags
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean)
  if (!draft.fileName.trim()) {
    ElMessage.warning(`${item.sourceFileName}：建议文件名不能为空`)
    return null
  }
  if (!tags.length) {
    ElMessage.warning(`${item.sourceFileName}：至少保留一个建议标签`)
    return null
  }
  return {
    suggestedFileName: draft.fileName.trim(),
    suggestedCategory: draft.category,
    suggestedSummary: draft.summary.trim(),
    suggestedTags: tags,
  }
}

function hasDraftChanges(item: GovernancePreviewItemResponse, update: UpdateGovernancePreviewItemRequest) {
  const currentTags = item.suggestedTags ?? []
  const nextTags = update.suggestedTags ?? []
  return update.suggestedFileName !== (item.suggestedFileName || item.sourceFileName)
    || update.suggestedCategory !== (item.suggestedCategory || 'OTHER')
    || update.suggestedSummary !== (item.suggestedSummary || '')
    || currentTags.length !== nextTags.length
    || currentTags.some((tag, index) => tag !== nextTags[index])
}

async function saveItem(item: GovernancePreviewItemResponse) {
  const update = buildItemUpdate(item)
  if (!update) return
  actionId.value = item.id
  try {
    const { data } = await governanceApi.updateGovernancePreviewItem(previewId.value, item.id, update)
    preview.value = data.data
    syncDrafts(data.data?.items ?? [])
    syncSelection(data.data?.items ?? [])
    ElMessage.success('建议已保存，正式文件尚未改变')
  } finally {
    actionId.value = null
  }
}

async function skipItem(item: GovernancePreviewItemResponse) {
  if (!isSkippable(item)) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('可选：填写跳过原因，便于后续追踪。', '跳过预览项', {
      confirmButtonText: '跳过',
      cancelButtonText: '返回',
      inputPlaceholder: '例如：暂不整理、内容敏感',
      inputPattern: /^.{0,255}$/,
      inputErrorMessage: '原因不能超过 255 个字符',
    })
    reason = result.value?.trim() || ''
  } catch {
    return
  }
  actionId.value = item.id
  try {
    const { data } = await governanceApi.skipGovernancePreviewItem(
      previewId.value,
      item.id,
      { reason: reason || undefined },
    )
    preview.value = data.data
    syncDrafts(data.data?.items ?? [])
    syncSelection(data.data?.items ?? [])
    ElMessage.success('已跳过该预览项')
  } finally {
    actionId.value = null
  }
}

async function confirmItems(confirmAll: boolean) {
  const ids = confirmAll
    ? confirmableItems.value.map((item) => item.id)
    : [...selectedIds.value]
  if (!ids.length) return ElMessage.warning('请先选择至少一个待确认项')

  const selectedItems = items.value.filter((item) => ids.includes(item.id))
  const pendingUpdates: Array<{
    item: GovernancePreviewItemResponse
    update: UpdateGovernancePreviewItemRequest
  }> = []
  for (const item of selectedItems) {
    const update = buildItemUpdate(item)
    if (!update) return
    if (hasDraftChanges(item, update)) pendingUpdates.push({ item, update })
  }

  confirming.value = true
  try {
    // Confirmation must use what is currently visible in the editor. Persist dirty
    // drafts first so the asynchronous archive operation reads the user's tags,
    // rather than the original AI suggestions stored when the preview was created.
    for (const pending of pendingUpdates) {
      const { data } = await governanceApi.updateGovernancePreviewItem(
        previewId.value,
        pending.item.id,
        pending.update,
      )
      preview.value = data.data
    }
    const { data } = await governanceApi.confirmGovernancePreview(previewId.value, {
      itemIds: ids,
      confirmAll,
    })
    preview.value = data.data
    syncDrafts(data.data?.items ?? [])
    clearSelection()
    ElMessage.success(pendingUpdates.length
      ? '最新修改已保存，归档任务已提交执行'
      : '已确认，归档任务已提交执行')
  } finally {
    confirming.value = false
  }
}

async function retryOperation() {
  const batchId = latestOperation.value?.batchId
  if (!batchId || retryingOperation.value) return
  retryingOperation.value = true
  try {
    await governanceApi.retryArchiveOperation(batchId)
    await load()
    ElMessage.success('失败项已提交重试')
  } finally {
    retryingOperation.value = false
  }
}

async function cancelPreview() {
  if (!canCancel.value) return
  try {
    await ElMessageBox.confirm(
      '取消后本批次不会进入归档执行，已生成的建议也不能继续确认。',
      '取消预览批次',
      { confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    const { data } = await governanceApi.cancelGovernancePreview(previewId.value)
    preview.value = data.data
    clearSelection()
    ElMessage.success('预览批次已取消')
  } finally {
    cancelling.value = false
  }
}

function goBack() {
  router.push('/files')
}

watch(previewId, load)
onMounted(load)
</script>

<template>
  <div class="pg preview-page">
    <header class="pg-head">
      <div class="head-copy">
        <button class="back-btn" @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回文件
        </button>
        <h1 class="pg-title">整理预览</h1>
        <p class="pg-sub">先查看和修改建议，确认后才会进入归档执行。</p>
      </div>
      <div class="head-actions">
        <button class="ghost-btn" :disabled="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          刷新
        </button>
        <button class="danger-btn" :disabled="!canCancel || cancelling" @click="cancelPreview">
          {{ cancelling ? '取消中…' : '取消预览' }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="preview-panel loading-panel">正在加载整理建议…</div>

    <template v-else-if="preview">
      <section class="summary-panel">
        <div>
          <p class="eyebrow">DRY-RUN PREVIEW</p>
          <h2 class="summary-title">{{ preview.totalCount }} 个文件的整理建议</h2>
          <p class="summary-meta">
            批次 {{ preview.previewId }} · 创建于 {{ toDisplayTime(preview.createdAt) }} ·
            {{ preview.expiresAt ? `有效至 ${toDisplayTime(preview.expiresAt)}` : '未设置过期时间' }}
          </p>
        </div>
        <div class="summary-stats">
          <span class="status-pill" :class="`is-${preview.status.toLowerCase()}`">
            {{ preview.status === 'PARTIAL_READY' ? '部分可确认' : preview.status === 'PARTIALLY_CONFIRMED' ? '部分已确认' : preview.status === 'READY' ? '待确认' : preview.status === 'CONFIRMED' ? '已确认' : preview.status === 'CANCELLED' ? '已取消' : preview.status === 'EXPIRED' ? '已过期' : preview.status }}
          </span>
          <span>可处理 {{ preview.readyCount }}</span>
          <span>失败 {{ preview.failedCount }}</span>
        </div>
      </section>

      <section v-if="latestOperation" class="summary-panel operation-summary">
        <div class="operation-copy">
          <p class="eyebrow">ARCHIVE OPERATION</p>
          <h2 class="summary-title">确认后的归档执行</h2>
          <p class="summary-meta">
            批次 {{ latestOperation.batchId }} ·
            {{ latestOperation.startedAt ? `开始于 ${toDisplayTime(latestOperation.startedAt)}` : '等待执行' }}
          </p>
          <p v-if="latestOperation.failureSummary" class="operation-error">
            {{ latestOperation.failureSummary }}
          </p>
        </div>
        <div class="operation-result">
          <div class="summary-stats">
            <span class="status-pill" :class="`is-${(latestOperation.status ?? '').toLowerCase()}`">
              {{ operationStatusLabel(latestOperation.status) }}
            </span>
            <span>成功 {{ latestOperation.successCount }}</span>
            <span>失败 {{ latestOperation.failedCount }}</span>
            <span>冲突 {{ latestOperation.conflictedCount }}</span>
          </div>
          <button
            v-if="latestOperation.failedCount > 0 && (latestOperation.status === 'FAILED' || latestOperation.status === 'PARTIAL_FAILED')"
            class="ghost-btn"
            :disabled="retryingOperation"
            @click="retryOperation"
          >
            {{ retryingOperation ? '重试中…' : '重试失败项' }}
          </button>
        </div>
        <div v-if="latestOperation.items?.length" class="operation-items">
          <div v-for="operationItem in latestOperation.items" :key="operationItem.id" class="operation-item">
            <span class="operation-item-file">{{ operationItem.sourceFileName }}</span>
            <span class="operation-item-target">→ {{ operationItem.targetPath }}</span>
            <span class="item-status" :class="`is-${(operationItem.executionStatus ?? '').toLowerCase()}`">
              {{ operationItemStatusLabel(operationItem.executionStatus) }}
            </span>
          </div>
        </div>
      </section>

      <section class="preview-panel preview-body">
        <div class="bulk-toolbar">
          <div>
            <h2 class="panel-title">逐项检查</h2>
            <p class="panel-sub">编辑内容会在确认时自动保存，并作为该文件最终的标签集合。</p>
          </div>
          <div class="bulk-actions">
            <button class="ghost-btn" :disabled="!confirmableItems.length" @click="selectAll">
              <el-icon><Select /></el-icon>
              全选可确认项
            </button>
            <button class="ghost-btn" :disabled="!selectedCount" @click="clearSelection">
              清除选择
            </button>
            <button class="primary-btn" :disabled="!selectedCount || confirming" @click="confirmItems(false)">
              <el-icon><Check /></el-icon>
              {{ confirming ? '确认中…' : `确认选中（${selectedCount}）` }}
            </button>
            <button class="primary-btn is-strong" :disabled="!confirmableItems.length || confirming" @click="confirmItems(true)">
              全量确认
            </button>
          </div>
        </div>

        <div class="preview-list">
          <article
            v-for="item in items"
            :key="item.id"
            class="preview-item"
            :class="[`is-${item.status.toLowerCase()}`, { 'is-selected': selectedIds.has(item.id) }]"
          >
            <div class="item-select">
              <input
                type="checkbox"
                :checked="selectedIds.has(item.id)"
                :disabled="!isConfirmable(item)"
                :aria-label="`选择 ${item.sourceFileName}`"
                @change="toggleSelected(item)"
              />
            </div>

            <div class="item-content">
              <header class="item-head">
                <div class="item-file">
                  <FileTile :file-type="item.sourceFileName?.split('.').pop()" size="sm" />
                  <div>
                    <h3>{{ item.sourceFileName }}</h3>
                    <p>{{ formatBytes(item.sourceSize ?? 0) }} · 原路径 {{ item.sourcePath }}</p>
                  </div>
                </div>
                <span class="item-status" :class="`is-${item.status.toLowerCase()}`">
                  {{ statusLabel(item.status) }}
                </span>
              </header>

              <div v-if="item.errorMessage" class="item-error">
                {{ item.errorCode ? `[${item.errorCode}] ` : '' }}{{ item.errorMessage }}
              </div>
              <div v-if="item.skipReason" class="item-skip">跳过原因：{{ item.skipReason }}</div>

              <div class="suggestion-grid">
                <label>
                  <span>建议文件名</span>
                  <input v-model="drafts[item.id].fileName" :disabled="!isEditable(item)" />
                </label>
                <label>
                  <span>建议分类（决定归档目录）</span>
                  <select v-model="drafts[item.id].category" :disabled="!isEditable(item)">
                    <option v-for="option in CATEGORY_OPTIONS" :key="option.value" :value="option.value">
                      {{ option.label }}
                    </option>
                  </select>
                </label>
                <label class="wide-field">
                  <span>建议标签（用逗号分隔）</span>
                  <input v-model="drafts[item.id].tags" :disabled="!isEditable(item)" />
                </label>
                <label class="wide-field">
                  <span>建议摘要</span>
                  <textarea v-model="drafts[item.id].summary" rows="2" :disabled="!isEditable(item)"></textarea>
                </label>
              </div>

              <p class="classification-help">
                分类决定确认后的归档目录和预计路径；标签用于搜索、筛选和描述文件内容，两者互不替代。
              </p>

              <div class="item-foot">
                <p class="target-path">
                  预计路径：<code>{{ item.suggestedPath || '暂无' }}</code>
                  <span v-if="item.suggestedCategory">· {{ categoryLabel(item.suggestedCategory) }}</span>
                </p>
                <div class="item-actions">
                  <button class="item-btn" :disabled="!isEditable(item) || actionId === item.id" @click="saveItem(item)">
                    {{ actionId === item.id ? '保存中…' : '保存建议' }}
                  </button>
                  <button class="item-btn is-skip" :disabled="!isSkippable(item) || actionId === item.id" @click="skipItem(item)">
                    跳过
                  </button>
                </div>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>

    <div v-else class="preview-panel empty-panel">
      <h2>预览批次不存在或已无法加载</h2>
      <button class="primary-btn" @click="goBack">返回文件列表</button>
    </div>
  </div>
</template>

<style scoped>
.preview-page {
  overflow: auto;
  padding-right: 4px;
}
.head-copy {
  min-width: 0;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  padding: 0;
  background: transparent;
  color: var(--accent);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  margin-bottom: 6px;
}
.head-actions,
.bulk-actions,
.item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ghost-btn,
.primary-btn,
.danger-btn,
.item-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-inner);
  padding: 8px 12px;
  background: var(--panel-3);
  color: var(--text-1);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.ghost-btn:hover:not(:disabled),
.item-btn:hover:not(:disabled) {
  border-color: var(--accent);
}
.primary-btn {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
.primary-btn.is-strong {
  background: var(--ink-teal);
  border-color: var(--ink-teal);
}
.danger-btn,
.item-btn.is-skip {
  color: var(--danger);
}
button:disabled,
input:disabled,
select:disabled,
textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.summary-panel,
.preview-panel {
  flex: none;
  background: var(--panel);
  border-radius: var(--radius-card);
  padding: var(--pad);
}
.summary-panel {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}
.operation-summary {
  flex-wrap: wrap;
  margin-top: 12px;
}
.operation-copy {
  min-width: 0;
  flex: 1 1 360px;
}
.operation-result {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 10px;
}
.operation-error {
  margin: 10px 0 0;
  color: var(--danger);
  font-size: 12px;
}
.operation-items {
  display: flex;
  flex: 1 1 100%;
  flex-direction: column;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.operation-item {
  display: grid;
  grid-template-columns: minmax(120px, 0.8fr) minmax(160px, 1.5fr) auto;
  align-items: center;
  gap: 10px;
  color: var(--text-2);
  font-size: 11px;
}
.operation-item-file,
.operation-item-target {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.operation-item-file {
  color: var(--text-1);
}
.eyebrow {
  margin: 0 0 5px;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}
.summary-title,
.panel-title {
  margin: 0;
  font-family: var(--font-display);
  color: var(--text-1);
}
.summary-title {
  font-size: 20px;
}
.summary-meta,
.panel-sub {
  margin: 5px 0 0;
  color: var(--text-2);
  font-size: 12px;
}
.summary-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-2);
  font-size: 12px;
  white-space: nowrap;
}
.status-pill,
.item-status {
  border-radius: var(--radius-pill);
  padding: 4px 10px;
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 11px;
}
.status-pill.is-ready,
.status-pill.is-partial_ready,
.item-status.is-ready,
.item-status.is-edited {
  background: var(--warn-tint);
  color: var(--warn);
}
.status-pill.is-confirmed,
.item-status.is-confirmed {
  background: rgb(var(--ok-rgb) / 0.13);
  color: var(--ok);
}
.status-pill.is-cancelled,
.status-pill.is-expired,
.item-status.is-skipped,
.item-status.is-cancelled,
.item-status.is-expired {
  color: var(--text-3);
}
.item-status.is-conflicted,
.item-status.is-failed {
  background: rgb(var(--danger-rgb) / 0.12);
  color: var(--danger);
}
.item-status.is-succeeded {
  background: rgb(var(--ok-rgb) / 0.13);
  color: var(--ok);
}
.preview-body {
  min-height: 0;
}
.bulk-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.panel-title {
  font-size: 16px;
}
.preview-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;
}
.preview-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: var(--panel-2);
}
.preview-item.is-selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgb(var(--accent-rgb) / 0.1);
}
.item-select {
  padding-top: 5px;
}
.item-select input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
}
.item-head,
.item-file,
.item-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.item-file {
  justify-content: flex-start;
  min-width: 0;
}
.item-file h3 {
  overflow: hidden;
  margin: 0;
  color: var(--text-1);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-file p {
  overflow: hidden;
  max-width: 70vw;
  margin: 2px 0 0;
  color: var(--text-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-error,
.item-skip {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: var(--radius-inner);
  background: rgb(var(--danger-rgb) / 0.1);
  color: var(--danger);
  font-size: 12px;
}
.item-skip {
  background: var(--tag-bg);
  color: var(--text-2);
}
.suggestion-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) 160px minmax(0, 1fr);
  gap: 12px;
  margin-top: 16px;
}
.suggestion-grid label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.suggestion-grid label span {
  color: var(--text-2);
  font-size: 11px;
}
.suggestion-grid input,
.suggestion-grid select,
.suggestion-grid textarea {
  width: 100%;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-inner);
  padding: 8px 10px;
  outline: none;
  background: var(--panel-3);
  color: var(--text-1);
  font: inherit;
  font-size: 12px;
  resize: vertical;
}
.suggestion-grid input:focus,
.suggestion-grid select:focus,
.suggestion-grid textarea:focus {
  border-color: var(--accent);
}
.wide-field {
  grid-column: span 1;
}
.classification-help {
  margin: 8px 0 0;
  color: var(--text-2);
  font-size: 11px;
  line-height: 1.6;
}
.item-foot {
  align-items: flex-end;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}
.target-path {
  overflow: hidden;
  min-width: 0;
  margin: 0;
  color: var(--text-2);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.target-path code {
  color: var(--accent);
  font-family: var(--font-mono);
}
.loading-panel,
.empty-panel {
  display: grid;
  place-items: center;
  min-height: 260px;
  color: var(--text-2);
}
.empty-panel {
  gap: 12px;
}
.empty-panel h2 {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-display);
  font-size: 18px;
}
@media (max-width: 1000px) {
  .summary-panel,
  .bulk-toolbar,
  .item-foot {
    align-items: flex-start;
    flex-direction: column;
  }
  .summary-stats {
    white-space: normal;
  }
  .suggestion-grid {
    grid-template-columns: 1fr 1fr;
  }
  .operation-result {
    align-items: flex-start;
  }
  .operation-item {
    grid-template-columns: minmax(100px, 1fr) auto;
  }
  .operation-item-target {
    display: none;
  }
  .wide-field {
    grid-column: span 2;
  }
}
@media (max-width: 620px) {
  .head-actions,
  .bulk-actions {
    width: 100%;
  }
  .head-actions > button,
  .bulk-actions > button {
    flex: 1;
  }
  .suggestion-grid {
    grid-template-columns: 1fr;
  }
  .operation-item {
    grid-template-columns: 1fr;
  }
  .operation-item .item-status {
    justify-self: start;
  }
  .wide-field {
    grid-column: span 1;
  }
}
</style>
