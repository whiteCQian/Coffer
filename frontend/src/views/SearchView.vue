<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ref } from 'vue'

import { searchFiles } from '@/api/files'
import type { FileListResponse } from '@/api/types'
import FileDetailDrawer from '@/components/FileDetailDrawer.vue'
import FileTile from '@/components/FileTile.vue'
import { formatBytes, shortTime, toDisplayTime } from '@/utils/format'

const keyword = ref('')
const tag = ref('')
const ran = ref(false)
const searching = ref(false)
const drawerId = ref<number | null>(null)
const results = ref<FileListResponse[]>([])
const resultTotal = ref(0)

function hasQuery() {
  return !!(keyword.value.trim() || tag.value.trim())
}

async function doSearch() {
  if (!hasQuery() || searching.value) return
  searching.value = true
  try {
    const { data } = await searchFiles({
      keyword: keyword.value.trim() || undefined,
      tag: tag.value.trim() || undefined,
      size: 50,
    })
    results.value = data.data?.content ?? []
    resultTotal.value = data.data?.totalElements ?? 0
    ran.value = true
  } catch {
    /* 拦截器已弹错：清空结果，避免残留旧结果误导 */
    results.value = []
    resultTotal.value = 0
    ran.value = true
  } finally {
    searching.value = false
  }
}
function reset() {
  keyword.value = ''
  tag.value = ''
  results.value = []
  resultTotal.value = 0
  ran.value = false
}

/** 行副文本：失败/处理中/排队 → AI 摘要 → 已确认标签名 */
function rowDesc(f: FileListResponse) {
  if (f.status === 'FAILED') return '解析失败'
  if (f.status === 'PROCESSING') return 'AI 解析中…'
  if (f.status === 'PENDING') return '排队等待解析'
  if (f.summary) return f.summary
  const names = (f.tags ?? [])
    .filter((t) => t.confirmationStatus === 'CONFIRMED')
    .map((t) => t.tagName)
  return names.join(' · ') || '暂无摘要'
}
const STATUS_LABEL: Record<string, string> = {
  COMPLETED: '完成',
  PROCESSING: '处理中',
  FAILED: '失败',
  PENDING: '排队',
}

/** 文件上传时间展示 */
function uploadTimeOf(f: FileListResponse) {
  return shortTime(toDisplayTime(f.uploadTime))
}
</script>

<template>
  <div class="pg search">
    <!-- 页头 -->
    <header class="pg-head">
      <div>
        <h1 class="pg-title">语义搜索</h1>
        <p class="pg-sub">按文件名或 AI 标签检索文件（标签含已确认与待确认，排除已拒绝）</p>
      </div>
    </header>

    <!-- 搜索面板 -->
    <div class="pg-panel search-panel">
      <div class="search-controls">
        <label class="field">
          <span class="field-label">文件名 / AI 摘要</span>
          <input
            v-model="keyword"
            class="field-input"
            type="text"
            placeholder="例如：采购合同、发布会照片、周报…"
            @keyup.enter="doSearch"
          />
        </label>
        <label class="field">
          <span class="field-label">AI 标签</span>
          <input
            v-model="tag"
            class="field-input"
            type="text"
            placeholder="例如：发票、报销、Q3…"
            @keyup.enter="doSearch"
          />
        </label>
        <button class="do-search" :disabled="!hasQuery() || searching" @click="doSearch">
          <el-icon class="do-ic"><Search /></el-icon>
          {{ searching ? '搜索中…' : '搜索' }}
        </button>
      </div>

      <!-- 结果 -->
      <template v-if="ran">
        <div v-if="results.length" class="result-head">
          <span>找到 {{ resultTotal }} 个文件</span>
          <button class="again-btn" @click="reset">重新搜索</button>
        </div>

        <ul v-if="results.length" class="result-list">
          <li v-for="f in results" :key="f.id" class="result-row" @click="drawerId = f.id">
            <FileTile :file-type="f.fileType" size="md" />
            <div class="row-main">
              <div class="row-name-line">
                <h3 class="row-name">{{ f.fileName }}</h3>
              </div>
              <p class="row-desc" :class="{ 'is-error': f.status === 'FAILED' }">{{ rowDesc(f) }}</p>
              <div
                v-if="f.tags && f.tags.length"
                class="row-tags"
              >
                <span
                  v-for="t in f.tags.slice(0, 5)"
                  :key="t.tagId"
                  class="mini-tag"
                  :class="{ 'is-pending': t.confirmationStatus === 'PENDING_CONFIRMATION' }"
                >
                  {{ t.tagName }}
                </span>
              </div>
            </div>
            <div class="row-side">
              <span class="row-state" :class="`is-${String(f.status).toLowerCase()}`">
                {{ STATUS_LABEL[f.status ?? ''] ?? '—' }}
              </span>
              <span class="row-meta">
                {{ formatBytes(f.fileSize) }}<br />{{ uploadTimeOf(f) }}
              </span>
              <span class="row-open">›</span>
            </div>
          </li>
        </ul>

        <div v-else-if="!searching" class="no-result">
          <p class="nr-title">没有找到匹配的文件</p>
          <p class="nr-sub">试试放宽关键词，或仅用一个标签搜索。</p>
          <button class="empty-btn" @click="reset">清空搜索</button>
        </div>
      </template>

      <!-- 初始引导 -->
      <div v-else class="guide">
        <p class="guide-title">像聊天一样找文件</p>
        <p class="guide-sub">
          输入文件名关键词或 AI 生成的标签，系统按文件名 / 摘要 / 标签实时检索真实文件。
        </p>
        <div class="guide-chips">
          <button
            v-for="q in ['实习', '发票', '安全教育', '生产实习']"
            :key="q"
            class="guide-chip"
            @click="tag = q; doSearch()"
          >
            {{ q }}
          </button>
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <FileDetailDrawer v-model="drawerId" @changed="doSearch" />
  </div>
</template>

<style scoped>
.search {
  gap: 14px;
}
.search-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.search-controls {
  flex: none;
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 12px;
  align-items: end;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.field-label {
  font-size: 12px;
  color: var(--text-3);
}
.field-input {
  width: 100%;
  height: 42px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  color: var(--text-1);
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
}
.field-input:focus {
  border-color: var(--accent);
}
.field-input::placeholder {
  color: var(--text-3);
}

.do-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 24px;
  border: none;
  border-radius: var(--radius-inner);
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}
.do-search:disabled {
  opacity: 0.45;
  cursor: default;
}
.do-search:hover:not(:disabled) {
  background: var(--accent-hover);
}

.result-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text-3);
}
.again-btn {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.result-list {
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
.result-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  cursor: pointer;
  transition: background-color 0.15s ease;
}
.result-row:hover {
  background: var(--card-hover-bg);
}

.row-main {
  flex: 1;
  min-width: 0;
}
.row-name-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.row-name {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-desc.is-error {
  color: var(--danger);
}
.row-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.mini-tag {
  padding: 2px 11px;
  border-radius: var(--radius-pill);
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 11.5px;
}
.mini-tag.is-pending {
  background: var(--warn-tint);
  color: var(--warn);
}

.row-side {
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
}
.row-state {
  font-size: 12px;
  color: var(--text-3);
}
.row-state.is-completed {
  color: var(--ok);
}
.row-state.is-processing {
  color: var(--warn);
}
.row-state.is-failed {
  color: var(--danger);
}
.row-meta {
  text-align: right;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-3);
}
.row-open {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text-2);
  font-size: 16px;
}

/* 无结果 / 引导 */
.no-result {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.nr-title {
  margin: 0;
  font-size: 15px;
  color: var(--text-1);
}
.nr-sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.empty-btn {
  margin-top: 10px;
  padding: 7px 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-1);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.empty-btn:hover {
  background: var(--hover-bg);
}

.guide {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 6px;
}
.guide-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 17px;
  color: var(--text-1);
}
.guide-sub {
  max-width: 460px;
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
  line-height: 1.7;
}
.guide-chips {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
.guide-chip {
  padding: 7px 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.guide-chip:hover {
  color: var(--hover-ink);
  border-color: var(--accent);
}
</style>
