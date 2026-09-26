<script setup lang="ts">
import { Search, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import * as fileApi from '@/api/files'
import { listTagCandidates, suggestTags } from '@/api/fileTags'
import * as governanceApi from '@/api/governance'
import type { FileListResponse, FileStatus, TagCandidate } from '@/api/types'
import FileDetailDrawer from '@/components/FileDetailDrawer.vue'
import FileTile from '@/components/FileTile.vue'
import { formatBytes, shortTime, toDisplayTime } from '@/utils/format'

/**
 * 全部文件：标签为主要组织维度，服务端分页/排序/关键词检索。
 * 固定分类（合同/发票/证件…）只作内部归档目录，不在此展示，也不按分类筛选。
 */
const PAGE_SIZE = 12

const keyword = ref('')
/** 点选标签芯片后的精确筛选标签名（空 = 不限） */
const activeTag = ref('')
const sortMode = ref<'new' | 'old' | 'size' | 'name'>('new')
const page = ref(1)
const drawerId = ref<number | null>(null)
const selectedIds = ref<Set<number>>(new Set())
const router = useRouter()

/** 常用标签：库中真实、覆盖文件数靠前的标签（常驻导航，替代原分类 tab） */
const tagCandidates = ref<TagCandidate[]>([])
/** 输入联想：AI/字面从真实标签中挑出的候选 */
const suggestions = ref<TagCandidate[]>([])
const suggesting = ref(false)
async function suggestWithModel() {
  const q = keyword.value.trim()
  if (!q) return
  const seq = ++sugSeq
  suggesting.value = true
  try {
    const { data } = await suggestTags(q, true)
    if (seq === sugSeq && keyword.value.trim() === q) suggestions.value = data.data ?? []
  } catch { /* 请求拦截器处理错误和取消。 */ }
  finally { if (seq === sugSeq) suggesting.value = false }
}

const SORT_OPTIONS = [
  { value: 'new', label: '最新上传' },
  { value: 'old', label: '最早上传' },
  { value: 'size', label: '按大小' },
  { value: 'name', label: '按名称' },
]

/** 卡片行：由后端列表项映射为模板可直接渲染的形状（无分类展示字段） */
type ListItem = {
  id: number
  fileName: string
  fileType: string | null
  fileSize: number
  /** 展示用 'YYYY-MM-DD HH:mm'（由后端 ISO 归一化） */
  uploadTime: string
  summary: string | null
  status: FileStatus | null
  tags: { id: number; name: string; status: string }[]
}

function mapRow(it: FileListResponse): ListItem {
  return {
    id: it.id,
    fileName: it.fileName,
    fileType: it.fileType,
    fileSize: it.fileSize,
    uploadTime: toDisplayTime(it.uploadTime),
    summary: it.summary,
    status: it.status,
    tags: (it.tags ?? []).map((t) => ({
      id: t.tagId,
      name: t.tagName,
      status: t.confirmationStatus,
    })),
  }
}

/** 服务端分页结果 */
const items = ref<ListItem[]>([])
const total = ref(0)
const serverTotalPages = ref(0)
const loading = ref(false)

const totalPages = computed(() => Math.max(1, serverTotalPages.value))
const selectedCount = computed(() => selectedIds.value.size)
const gridRows = computed(() =>
  items.value.length > 6 ? 'repeat(2, minmax(0, 1fr))' : 'auto',
)

/** 关键词防抖：输入即搜 + 同步做标签联想；空词清空联想 */
let kwTimer: ReturnType<typeof setTimeout> | undefined
let sugTimer: ReturnType<typeof setTimeout> | undefined
let sugSeq = 0
onUnmounted(() => { clearTimeout(kwTimer); clearTimeout(sugTimer); sugSeq += 1 })
watch(keyword, (v) => {
  const q = v.trim()
  // (a) 输入联想：AI/字面映射到真实标签（不打断列表搜索）
  if (sugTimer) clearTimeout(sugTimer)
  if (!q) {
    suggestions.value = []
    suggesting.value = false
  } else {
    sugTimer = setTimeout(async () => {
      const seq = ++sugSeq
      suggesting.value = true
      try {
        const { data } = await suggestTags(q)
        if (seq === sugSeq) suggestions.value = data.data ?? []
      } catch {
        /* 联想失败静默降级：不影响普通关键词搜索 */
        if (seq === sugSeq) suggestions.value = []
      } finally {
        if (seq === sugSeq) suggesting.value = false
      }
    }, 300)
  }
  // (b) 列表关键词搜索（含已选标签收窄）
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(() => {
    page.value = 1
    loadList()
  }, 260)
})
/** 标签芯片选中/取消：翻回第一页刷新 */
watch(activeTag, () => {
  page.value = 1
  loadList()
})
watch(sortMode, () => {
  page.value = 1
  loadList()
})
watch(page, () => loadList())

async function loadList() {
  loading.value = true
  try {
    const { data } = await fileApi.listFiles({
      keyword: keyword.value.trim() || undefined,
      tag: activeTag.value || undefined,
      sort: sortMode.value,
      page: page.value - 1,
      size: PAGE_SIZE,
    })
    items.value = (data.data?.content ?? []).map(mapRow)
    total.value = data.data?.totalElements ?? 0
    serverTotalPages.value = data.data?.totalPages ?? 0
    // 页码越界（如删除末页最后一条）回落
    if (page.value > 1 && !items.value.length && serverTotalPages.value > 0) {
      page.value = Math.min(page.value, serverTotalPages.value)
    }
  } catch {
    /* 拦截器已弹错 */
  } finally {
    loading.value = false
  }
}

/** 列表刷新（抽屉内写操作成功后调用；标签芯片数据另由 loadCommonTags 更新） */
async function reload() {
  await loadList()
  loadCommonTags()
}

/** 清空一切筛选（关键词 + 已选标签） */
function resetFilters() {
  keyword.value = ''
  activeTag.value = ''
}

/** 常用标签：取库中真实、被采用次数 Top 12，作常驻导航 */
async function loadCommonTags() {
  try {
    const { data } = await listTagCandidates()
    tagCandidates.value = (data.data ?? []).slice(0, 12)
  } catch {
    tagCandidates.value = []
  }
}

/** 切换标签筛选：点同一芯片取消 */
function toggleTag(name: string) {
  activeTag.value = activeTag.value === name ? '' : name
}

/** 联想芯片：选中即清空关键词、按该标签精确检索 */
function chooseSuggestion(name: string) {
  keyword.value = ''
  toggleTag(name)
}

function clearActiveTag() {
  activeTag.value = ''
}

function toggleSelection(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function clearSelection() {
  selectedIds.value = new Set()
}

async function createPreview() {
  const fileIds = [...selectedIds.value]
  if (!fileIds.length) return ElMessage.warning('请先选择至少一个文件')
  try {
    const requestId = globalThis.crypto?.randomUUID?.()
      ?? `preview-${Date.now()}-${Math.random().toString(36).slice(2)}`
    const { data } = await governanceApi.createGovernancePreview({
      fileIds,
      requestId,
      source: 'UPLOAD',
      mode: 'API',
    })
    clearSelection()
    const previewId = data.data?.previewId
    if (previewId) {
      await router.push({ name: 'governance-preview', params: { previewId } })
    }
  } catch {
    /* 拦截器已弹错 */
  }
}

async function reanalyzeSelected() {
  const fileIds = [...selectedIds.value]
  if (!fileIds.length) return ElMessage.warning('请先选择至少一个文件')
  try {
    await ElMessageBox.confirm(
      `将为选中的 ${fileIds.length} 个文件生成新的分析预览，当前分类、标签和摘要不会被覆盖。是否继续？`,
      '重新分析',
      { confirmButtonText: '开始分析', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const requestId = globalThis.crypto?.randomUUID?.()
      ?? `reanalyze-${Date.now()}-${Math.random().toString(36).slice(2)}`
    const { data } = await governanceApi.reanalyzeGovernanceFiles({
      fileIds,
      requestId,
      mode: 'API',
    })
    clearSelection()
    const previewId = data.data?.previewId
    if (previewId) await router.push({ name: 'governance-preview', params: { previewId } })
  } catch {
    /* 拦截器已弹错 */
  }
}

function goToReanalysis(previewId: string) {
  router.push({ name: 'governance-preview', params: { previewId } })
}

function descOf(f: ListItem) {
  if (f.status === 'FAILED') return '解析失败'
  if (f.status === 'PROCESSING') return 'AI 解析中…'
  if (f.status === 'PENDING') return '排队等待解析'
  const hasTag = (f.tags ?? []).some((t) => t.status === 'CONFIRMED')
  return f.summary || (hasTag ? '已打标，暂无摘要' : '暂无摘要')
}

function stateMini(f: ListItem) {
  if (f.status === 'COMPLETED') return { label: '完成', cls: 'is-ok' }
  if (f.status === 'PROCESSING') return { label: '处理中', cls: 'is-run' }
  if (f.status === 'FAILED') return { label: '失败', cls: 'is-fail' }
  return { label: '排队', cls: 'is-wait' }
}

const uploadInput = ref<HTMLInputElement>()
function onUploadClick() {
  uploadInput.value?.click()
}
async function onUploadPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = input.files?.[0]
  input.value = ''
  if (!picked) return
  const fd = new FormData()
  fd.append('file', picked)
  try {
    await fileApi.uploadFile(fd)
    ElMessage.success(`「${picked.name}」已上传，AI 开始解析…`)
    reload()
  } catch {
    /* 拦截器已弹错 */
  }
}

onMounted(() => {
  reload()
  loadCommonTags()
})
</script>

<template>
  <div class="pg files">
    <!-- 页头 -->
    <header class="pg-head">
      <div>
        <h1 class="pg-title">全部文件</h1>
        <p class="pg-sub">共 {{ total }} 个文件 · AI 自动打标与摘要</p>
      </div>
      <div class="file-head-actions">
        <button class="upload-btn" @click="onUploadClick">
          <el-icon class="upload-ic"><UploadFilled /></el-icon>
          上传文件
        </button>
        <button class="preview-btn" :disabled="!selectedCount" @click="createPreview">
          生成整理预览<span v-if="selectedCount">（{{ selectedCount }}）</span>
        </button>
        <button class="reanalyze-btn" :disabled="!selectedCount" @click="reanalyzeSelected">
          重新分析<span v-if="selectedCount">（{{ selectedCount }}）</span>
        </button>
      </div>
      <input ref="uploadInput" type="file" hidden @change="onUploadPicked" />
    </header>

    <!-- 浏览面板 -->
    <div class="pg-panel browse">
      <!-- 工具栏：搜索 + 排序 -->
      <div class="toolbar">
        <div class="search-box">
          <el-icon class="search-ic"><Search /></el-icon>
          <input
            v-model="keyword"
            class="search-input"
            type="text"
            placeholder="搜索文件名 / 摘要，或描述你想找的（如「上次出差的发票」）…"
          />
          <button v-if="keyword" class="search-clear" title="清空" @click="keyword = ''">✕</button>
        </div>
        <select v-model="sortMode" class="sort-select" title="排序">
          <option v-for="o in SORT_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <el-button :disabled="!keyword.trim() || suggesting" @click="suggestWithModel">AI 标签联想</el-button>
      </div>

      <!-- 标签智能条：输入联想 / 常用标签 / 正在查看的标签（标签为主要组织维度） -->
      <div class="tagline">
        <template
          v-if="keyword.trim() && !activeTag && (suggesting || suggestions.length)"
        >
          <span class="tagline-label">{{ suggesting ? '正在查找标签…' : '相关标签：' }}</span>
          <button
            v-for="s in suggestions"
            :key="s.name"
            class="tag-chip is-sug"
            title="按此标签筛选"
            @click="chooseSuggestion(s.name)"
          >
            {{ s.name }}<span class="chip-count">{{ s.fileCount }}</span>
          </button>
        </template>

        <template
          v-else-if="!keyword.trim() && !activeTag && tagCandidates.length"
        >
          <span class="tagline-label">常用标签：</span>
          <button
            v-for="c in tagCandidates"
            :key="c.name"
            class="tag-chip"
            title="按此标签筛选（再点取消）"
            @click="toggleTag(c.name)"
          >
            {{ c.name }}<span class="chip-count">{{ c.fileCount }}</span>
          </button>
        </template>

        <template v-else-if="activeTag">
          <span class="tagline-label">正在查看标签：</span>
          <span class="tag-pill">
            标签：{{ activeTag }}
            <button class="tag-pill-x" title="取消该标签筛选" @click="clearActiveTag">✕</button>
          </span>
        </template>
      </div>

      <!-- 文件卡片网格（标签为主要分类维度，无固定分类 tab/徽标） -->
      <div v-if="items.length" class="grid" :style="{ gridTemplateRows: gridRows }">
        <article
          v-for="f in items"
          :key="f.id"
          class="fcard"
          :class="{ 'is-selected': selectedIds.has(f.id) }"
          tabindex="0"
          @click="drawerId = f.id"
          @keyup.enter="drawerId = f.id"
        >
          <header class="fcard-head">
            <div class="fcard-select">
              <input
                type="checkbox"
                :checked="selectedIds.has(f.id)"
                :aria-label="`选择 ${f.fileName}`"
                @click.stop
                @change="toggleSelection(f.id)"
              />
              <FileTile :file-type="f.fileType" size="sm" />
            </div>
            <span class="fcard-state" :class="stateMini(f).cls">{{ stateMini(f).label }}</span>
          </header>

          <h3 class="fcard-name">{{ f.fileName }}</h3>
          <p class="fcard-desc" :class="{ 'is-error': f.status === 'FAILED' }">{{ descOf(f) }}</p>

          <div v-if="f.tags.length" class="fcard-tags">
            <span
              v-for="t in f.tags.filter((x) => x.status !== 'REJECTED').slice(0, 3)"
              :key="t.id"
              class="mini-tag"
            >
              {{ t.name }}
            </span>
          </div>
          <p v-else class="fcard-no-tags">
            {{ f.status === 'PROCESSING' ? '等待 AI 打标…' : '未打标' }}
          </p>

          <footer class="fcard-foot">
            <span class="fcard-time">{{ shortTime(f.uploadTime) }} · {{ formatBytes(f.fileSize) }}</span>
          </footer>
        </article>
      </div>

      <!-- 空态 -->
      <div v-else class="empty">
        <p class="empty-title">没有找到匹配的文件</p>
        <p class="empty-sub">
          {{
            keyword
              ? '换个关键词试试。'
              : activeTag
                ? `标签「${activeTag}」下还没有文件，试试其他标签。`
                : '上传文件后，AI 会自动打标归档到这里。'
          }}
        </p>
        <button v-if="keyword" class="empty-btn" @click="resetFilters">
          清空搜索
        </button>
      </div>

      <!-- 分页 -->
      <footer v-if="total > 0" class="pager">
        <button class="pg-arrow" :disabled="page <= 1" @click="page--">‹</button>
        <button
          v-for="p in totalPages"
          :key="p"
          class="pg-num"
          :class="{ 'is-active': p === page }"
          @click="page = p"
        >
          {{ p }}
        </button>
        <button class="pg-arrow" :disabled="page >= totalPages" @click="page++">›</button>
        <span class="pg-info">第 {{ page }} / {{ totalPages }} 页</span>
      </footer>
    </div>

    <!-- 详情抽屉：恒走真实接口，写操作成功后回调 reload 刷新列表 -->
    <FileDetailDrawer v-model="drawerId" @changed="reload" @reanalyzed="goToReanalysis" />
  </div>
</template>

<style scoped>
.files {
  gap: 14px;
}
.upload-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border: none;
  border-radius: var(--radius-pill);
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 4px 14px rgb(var(--accent-rgb) / 0.25);
}
.file-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.preview-btn {
  display: inline-flex;
  align-items: center;
  padding: 10px 16px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--accent);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.preview-btn:hover:not(:disabled) {
  background: rgb(var(--accent-rgb) / 0.1);
}
.preview-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.reanalyze-btn {
  display: inline-flex;
  align-items: center;
  padding: 10px 16px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--accent);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.reanalyze-btn:hover:not(:disabled) {
  background: rgb(var(--accent-rgb) / 0.1);
}
.reanalyze-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.upload-btn:hover {
  background: var(--accent-hover);
}
.upload-ic {
  font-size: 15px;
}

/* —— 面板 —— */
.browse {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: hidden;
}

.toolbar {
  flex: none;
  display: flex;
  gap: 12px;
  align-items: center;
}
.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 12px 0 14px;
  border-radius: var(--radius-pill);
  background: var(--panel-3);
}
.search-ic {
  flex: none;
  color: var(--text-3);
}
.search-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--text-1);
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
}
.search-input::placeholder {
  color: var(--text-3);
}
.search-clear {
  flex: none;
  border: none;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  font-size: 12px;
}
.search-clear:hover {
  color: var(--text-1);
}

.sort-select {
  flex: none;
  height: 40px;
  padding: 0 12px;
  border: none;
  border-radius: var(--radius-pill);
  background: var(--panel-3);
  color: var(--text-1);
  font-size: 13px;
  font-family: inherit;
  outline: none;
  cursor: pointer;
}
.sort-select option {
  background: var(--panel-2);
}

/* 卡片网格：每页 12 个，固定 6 列；行高由 gridRows 动态给出 */
.grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
  padding: 2px;
  margin: -2px;
}
.fcard {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  border: 1px solid transparent;
  cursor: pointer;
  min-width: 0;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.fcard.is-selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgb(var(--accent-rgb) / 0.1);
}
.fcard:hover,
.fcard:focus-visible {
  border-color: var(--line-hover);
  background: var(--card-hover-bg);
  outline: none;
}

.fcard-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fcard-select {
  display: flex;
  align-items: center;
  gap: 9px;
}
.fcard-select input {
  width: 15px;
  height: 15px;
  accent-color: var(--accent);
  cursor: pointer;
}
.fcard-state {
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 600;
}
.fcard-state.is-ok {
  color: var(--ok);
}
.fcard-state.is-run {
  color: var(--warn);
}
.fcard-state.is-fail {
  color: var(--danger);
}
.fcard-state.is-wait {
  color: var(--text-3);
}

.fcard-name {
  margin: 4px 0 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.9em;
}
.fcard-desc {
  margin: 0;
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 3em;
}
.fcard-desc.is-error {
  color: var(--danger);
}

.fcard-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.mini-tag {
  padding: 3px 12px;
  border-radius: var(--radius-pill);
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 12px;
}
.fcard-no-tags {
  margin: 0;
  font-size: 12px;
  color: var(--text-3);
}

.fcard-foot {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--line);
}
.fcard-time {
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}

/* 空态 */
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.empty-title {
  margin: 0;
  font-size: 15px;
  color: var(--text-1);
}
.empty-sub {
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

/* 分页 */
.pager {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.pg-arrow,
.pg-num {
  min-width: 30px;
  height: 30px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.pg-arrow:hover:not(:disabled),
.pg-num:hover {
  color: var(--text-1);
  border-color: var(--line-hover-strong);
}
.pg-arrow:disabled {
  opacity: 0.35;
  cursor: default;
}
.pg-num.is-active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.pg-info {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-3);
}

/* —— 标签智能条 —— */
.tagline {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  padding: 2px 0 0;
  overflow-x: auto;
}
.tagline-label {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
}
.tag-chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
}
.tag-chip:hover {
  color: var(--hover-ink);
  border-color: rgb(var(--accent-rgb) / 0.6);
  background: rgb(var(--accent-rgb) / 0.08);
}
.tag-chip.is-sug {
  border-color: rgb(var(--accent-rgb) / 0.45);
  color: var(--accent-soft-text);
}
.chip-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.tag-chip:hover .chip-count {
  color: rgb(var(--accent-soft-rgb) / 0.7);
}
.tag-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 14px;
  border-radius: var(--radius-pill);
  background: rgb(var(--accent-rgb) / 0.14);
  border: 1px solid rgb(var(--accent-rgb) / 0.4);
  color: var(--accent-soft-text);
  font-size: 12.5px;
  white-space: nowrap;
}
.tag-pill-x {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--accent-soft-text);
  font-size: 11px;
  cursor: pointer;
}
.tag-pill-x:hover {
  background: rgb(var(--accent-rgb) / 0.25);
  color: var(--accent-soft-text);
}
</style>
