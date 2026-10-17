<script setup lang="ts">
import { taskStateLabel } from '@/utils/status'
import { Search, UploadFilled, Grid, List, Refresh, ChatDotRound, Fold, Expand, FolderOpened, ArrowRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import * as fileApi from '@/api/files'
import { suggestTags } from '@/api/fileTags'
import * as governanceApi from '@/api/governance'
import type { FileListResponse, FileStatus, TagCandidate } from '@/api/types'
import FileDetailDrawer from '@/components/FileDetailDrawer.vue'
import FileTile from '@/components/FileTile.vue'
import DesktopImportPanel from '@/components/DesktopImportPanel.vue'
import StorageQuotaPanel from '@/components/StorageQuotaPanel.vue'
import PaneResizeHandle from '@/components/PaneResizeHandle.vue'
import { usePaneWidth, useWindowWidth } from '@/composables/usePaneWidth'
import { useAuthStore } from '@/stores/auth'
import { desktopBridge } from '@/api/desktop'
import ChatDock from '@/layout/ChatDock.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useOpenFile } from '@/composables/useOpenFile'
import { formatBytes, shortTime, toDisplayTime } from '@/utils/format'

/**
 * 全部文件：标签为主要组织维度，服务端分页/排序/关键词检索。
 * 固定分类（合同/发票/证件…）只作内部归档目录，不在此展示，也不按分类筛选。
 */
const PAGE_SIZE = 12
const auth = useAuthStore()
const { openFile, isOpening } = useOpenFile()
let inspectTimer: ReturnType<typeof setTimeout> | undefined
function inspectClickedFile(id: number, event: MouseEvent) {
  clearTimeout(inspectTimer)
  if (event.detail > 1) return
  // Let a double-click finish before the small-window inspector can cover its second click.
  if (desktopBridge && windowWidth.value <= 960) inspectTimer = setTimeout(() => inspect(id), 500)
  else inspect(id)
}
onUnmounted(() => clearTimeout(inspectTimer))
function openListedFile(id: number, event?: MouseEvent) {
  clearTimeout(inspectTimer)
  if (event?.target instanceof Element && event.target.closest('input,button,a,label')) return
  const file = items.value.find(item => item.id === id)
  contextMenu.value = null
  void openFile(id, undefined, file?.fileType)
}
const windowWidth=useWindowWidth(),contentElement=ref<HTMLElement>(),contentWidth=ref(0)
const sidePanelMax=computed(()=>Math.max(280,Math.min(600,contentWidth.value-328)))
const {width:sidePanelWidth,resize:resizeSidePanel,commit:saveSidePanel,reset:resetSidePanel}=usePaneWidth('coffer:side-panel-width',340,280,sidePanelMax)
const canResizePanels=computed(()=>auth.isPersonal && windowWidth.value>960)
let contentObserver:ResizeObserver|undefined
onMounted(()=>{contentObserver=new ResizeObserver(entries=>{contentWidth.value=entries[0]?.contentRect.width || 0});if(contentElement.value)contentObserver.observe(contentElement.value)})
onUnmounted(()=>contentObserver?.disconnect())

const keyword = ref('')
/** 点选标签芯片后的精确筛选标签名（空 = 不限） */
const activeTag = ref('')
const sortMode = ref<'new' | 'old' | 'size' | 'name'>('new')
const page = ref(1)
const drawerId = ref<number | null>(null)
const selectedIds = ref<Set<number>>(new Set())
const router = useRouter()
const route = useRoute(), workspace = useWorkspaceStore()
const aiOpen = ref(false), aiMounted = ref(false), showInspector = ref(true)
const searchInput = ref<HTMLInputElement>()
const viewMode = ref<'list' | 'grid'>((() => { try { return localStorage.getItem('coffer:file-view') === 'grid' ? 'grid' : 'list' } catch { return 'list' } })())
watch(viewMode, value => { try { localStorage.setItem('coffer:file-view', value) } catch { /* Session-only preference. */ } })
const contextMenu = ref<{ id: number; x: number; y: number } | null>(null)
const allPageSelected = computed(() => items.value.length > 0 && items.value.every(file => selectedIds.value.has(file.id)))
function togglePageSelection() {
  const next = new Set(selectedIds.value)
  for (const file of items.value) { if (allPageSelected.value) next.delete(file.id); else next.add(file.id) }
  selectedIds.value = next
}
function inspect(id: number) { drawerId.value = id; showInspector.value = true; aiOpen.value = false; contextMenu.value = null }
function toggleAi() { aiMounted.value = true; aiOpen.value = !aiOpen.value }
function toggleInspector() {
  if (aiOpen.value) { aiOpen.value = false; showInspector.value = true }
  else showInspector.value = !showInspector.value
}
const unavailableImages = ref(new Set<number>())
function imagePreview(file: ListItem) {
  return ['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(file.fileType?.toLowerCase() ?? '') && !unavailableImages.value.has(file.id)
}
function markImageUnavailable(id: number) { unavailableImages.value = new Set(unavailableImages.value).add(id) }
function openContext(event: MouseEvent, id: number) {
  event.preventDefault()
  contextMenu.value = { id, x: Math.max(8, Math.min(event.clientX, window.innerWidth - 190)), y: Math.max(8, Math.min(event.clientY, window.innerHeight - 210)) }
}
function dismissContext() { contextMenu.value = null }
function onKey(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); searchInput.value?.focus() }
  if (event.key === 'Escape') { dismissContext(); aiOpen.value = false }
}
function setRouteTag() {
  activeTag.value = typeof route.query.tag === 'string' ? route.query.tag : ''
}
watch(() => route.query.tag, setRouteTag, { immediate: true })
watch(() => route.query.keyword, value => { keyword.value = typeof value === 'string' ? value : '' }, { immediate: true })
watch(() => route.query.file, value => {
  const id = Number(value)
  if (Number.isSafeInteger(id) && id > 0) inspect(id)
}, { immediate: true })

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
  taskState?: string
  archived: boolean
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
    taskState: it.taskState,
    archived: it.archived,
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
watch(activeTag, value => {
  page.value = 1
  if (value !== String(route.query.tag ?? '')) {
    void router.replace({ query: { ...route.query, tag: value || undefined } })
  }
  loadList()
})
watch(sortMode, () => {
  page.value = 1
  loadList()
})
watch(page, () => loadList())

let listRequest = 0
const loadFailed = ref(false)
async function loadList() {
  const request = ++listRequest
  loading.value = true
  try {
    const { data } = await fileApi.listFiles({
      keyword: keyword.value.trim() || undefined,
      tag: activeTag.value || undefined,
      sort: sortMode.value,
      page: page.value - 1,
      size: PAGE_SIZE,
    })
    if (request !== listRequest) return
    loadFailed.value = false
    items.value = (data.data?.content ?? []).map(mapRow)
    total.value = data.data?.totalElements ?? 0
    serverTotalPages.value = data.data?.totalPages ?? 0
    // 页码越界（如删除末页最后一条）回落
    if (page.value > 1 && !items.value.length && serverTotalPages.value > 0) {
      page.value = Math.min(page.value, serverTotalPages.value)
    }
  } catch {
    if (request === listRequest) { loadFailed.value = true; items.value = []; total.value = 0; serverTotalPages.value = 0 }
    /* 拦截器已弹错 */
  } finally {
    if (request === listRequest) loading.value = false
  }
}

/** 写操作后刷新文件列表、导航标签与任务计数。 */
async function reload() {
  await loadList()
  void workspace.refresh()
}

/** 清空一切筛选（关键词 + 已选标签） */
function resetFilters() {
  keyword.value = ''
  activeTag.value = ''
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
const taskDestination = computed(() => auth.isPersonal ? { path: '/', query: { view: 'inbox' } } : { path: '/', hash: '#web-task-progress' })
const pendingDestination = computed(() => auth.isPersonal ? { path: '/', query: { view: 'pending' } } : { path: '/', hash: '#web-task-progress' })

function descOf(f: ListItem) {
  if (f.archived) return f.summary || '已归档，可以查找和取回'
  if (f.status === 'FAILED') return '文件已保存；可先归档或重试 AI 分析'
  if (f.status === 'PROCESSING') return 'AI 解析中…'
  if (f.status === 'PENDING') return '排队等待解析'
  const hasTag = (f.tags ?? []).some((t) => t.status === 'CONFIRMED')
  return f.summary || (hasTag ? '已打标，暂无摘要' : '暂无摘要')
}

function stateMini(f: ListItem) {
  return { label: taskStateLabel(f), cls: f.archived ? 'is-ok' : f.taskState === 'ARCHIVING' ? 'is-run' : f.status === 'FAILED' || f.taskState === 'ARCHIVE_FAILED' ? 'is-fail' : 'is-wait' }
}
const archiving = ref(false), archiveResult = ref(''), aiQuestion = ref('')
const eligibleSelected = computed(() => items.value.filter(f => selectedIds.value.has(f.id) && !f.archived && !['PENDING','PROCESSING'].includes(f.status ?? '') && !['ARCHIVING','ARCHIVE_BLOCKED','ROLLING_BACK'].includes(f.taskState ?? '')))
async function archiveSelected() {
  if (archiving.value) return
  const selected = eligibleSelected.value.slice(); if (!selected.length) return
  archiving.value = true; archiveResult.value = `正在提交 ${selected.length} 个文件…`
  let accepted = 0
  for (const file of selected) {
    try {
      if (file.taskState === 'ARCHIVE_FAILED') {
        const { data } = await governanceApi.searchArchiveOperationItems({ fileId:file.id, size:1 })
        if (!data.data.content[0]) throw new Error('未找到归档任务')
        await governanceApi.retryArchiveOperation(data.data.content[0].batchId)
      } else {
        const { data } = await fileApi.getFileDetail(file.id)
        await governanceApi.confirmSavedFile(file.id, { revision:data.data.revision })
      }
      accepted++; selectedIds.value.delete(file.id)
    } catch { /* Keep failed selections for a targeted retry. */ }
  }
  archiveResult.value = `已受理 ${accepted} 个，${selected.length - accepted} 个未受理；文件已保存，未受理项可重试。`
  archiving.value = false; await reload()
}
function findWithAi() { aiQuestion.value = keyword.value; aiMounted.value = true; aiOpen.value = true }
const listTimer = setInterval(() => { if (!loading.value && !archiving.value) void loadList() }, 5000)
onUnmounted(() => clearInterval(listTimer))

const uploadInput = ref<HTMLInputElement>()
const nativeImport = ref<InstanceType<typeof DesktopImportPanel>>()
const lastUpload = ref<{ signature: string; requestId: string } | null>(null)
function onUploadClick() {
  if (desktopBridge) { nativeImport.value?.pick(); return }
  uploadInput.value?.click()
}
async function onUploadPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = input.files?.[0]
  input.value = ''
  if (!picked) return
  const fd = new FormData()
  fd.append('file', picked)
  const signature = `${picked.name}\0${picked.size}\0${picked.lastModified}`
  if (lastUpload.value?.signature !== signature) {
    lastUpload.value = { signature, requestId: crypto.randomUUID() }
  }
  try {
    try { await fileApi.uploadFile(fd, lastUpload.value.requestId) }
    catch {
      await ElMessageBox.confirm('AI 暂不可用或尚未授权。可以先安全保存文件，稍后整理；普通查找和取回仍可用。', '仅保存文件', { confirmButtonText:'仅保存', cancelButtonText:'返回' })
      await fileApi.saveFileOnly(fd, lastUpload.value.requestId)
    }
    lastUpload.value = null
    ElMessage.success(`「${picked.name}」已保存，可在详情查看整理状态`)
    reload()
  } catch {
    /* 拦截器已弹错 */
  }
}

let pollTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  reload()
  pollTimer = setInterval(() => { if (!loading.value && !document.hidden) void loadList() }, 10000)
  window.addEventListener('keydown', onKey)
  window.addEventListener('click', dismissContext)
})
onUnmounted(() => { listRequest++; clearInterval(pollTimer); window.removeEventListener('keydown', onKey); window.removeEventListener('click', dismissContext) })
</script>

<template>
  <div class="desktop-files" :class="{ 'web-files': !auth.isPersonal }">
    <header class="workbench-header">
      <div><div class="workbench-breadcrumb">{{ auth.isPersonal ? '个人文件库' : '我的文件空间' }} <el-icon><ArrowRight /></el-icon> {{ activeTag ? '标签' : '文件浏览' }}</div><h1>{{ activeTag || '全部文件' }}<span class="file-total">{{ total }} 个文件</span></h1><p>让每一份文件，都有迹可循。</p></div>
      <div class="header-actions"><button class="desk-button" :class="{ 'is-active': aiOpen }" :aria-expanded="aiOpen" @click="toggleAi"><el-icon><ChatDotRound /></el-icon>AI 助手</button><button class="desk-button is-primary" @click="onUploadClick"><el-icon><UploadFilled /></el-icon>{{ auth.isPersonal ? '导入文件' : '上传文件' }}</button></div>
      <input ref="uploadInput" type="file" hidden @change="onUploadPicked" />
    </header>
    <div class="workbench-toolbar">
      <label class="desk-search"><el-icon><Search /></el-icon><input ref="searchInput" v-model="keyword" type="search" placeholder="输入文件名、记得的内容，或描述要找的文件" aria-label="搜索文件名、摘要或标签" /><kbd>Ctrl K</kbd></label>
      <button class="desk-button ai-search-link" @click="aiMounted = true; aiOpen = true"><el-icon><ChatDotRound /></el-icon>用 AI 找文件</button>
      <select v-model="sortMode" class="desk-sort" aria-label="文件排序"><option v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      <div class="view-switch" aria-label="文件展示方式"><button :class="{ active: viewMode === 'list' }" title="列表视图" aria-label="列表视图" :aria-pressed="viewMode === 'list'" @click="viewMode = 'list'"><el-icon><List /></el-icon></button><button :class="{ active: viewMode === 'grid' }" title="网格视图" aria-label="网格视图" :aria-pressed="viewMode === 'grid'" @click="viewMode = 'grid'"><el-icon><Grid /></el-icon></button></div>
      <button class="desk-icon-button" :disabled="loading" title="刷新文件库" aria-label="刷新文件库" @click="reload"><el-icon><Refresh /></el-icon></button>
      <button class="desk-icon-button" :title="showInspector && !aiOpen ? '收起详情面板' : '展开详情面板'" :aria-label="showInspector && !aiOpen ? '收起详情面板' : '展开详情面板'" :aria-expanded="showInspector && !aiOpen" @click="toggleInspector"><el-icon><Fold v-if="showInspector && !aiOpen" /><Expand v-else /></el-icon></button>
    </div>
    <StorageQuotaPanel v-if="!auth.isPersonal && auth.status.storageQuotaEnabled" />
    <DesktopImportPanel ref="nativeImport" compact @queued="reload" />
    <div v-if="activeTag || (keyword.trim() && (suggestions.length || suggesting))" class="desk-filter-bar">
      <span v-if="activeTag" class="desk-tag">{{ activeTag }}<button aria-label="取消标签筛选" @click="clearActiveTag">×</button></span>
      <template v-else><span>{{ suggesting ? '正在匹配标签…' : '相关标签' }}</span><button v-for="suggestion in suggestions" :key="suggestion.name" class="desk-tag" @click="chooseSuggestion(suggestion.name)">{{ suggestion.name }} · {{ suggestion.fileCount }}</button></template>
      <button v-if="keyword.trim()" class="desk-text-button" :disabled="suggesting" @click="suggestWithModel">AI 标签联想</button>
    </div>
    <div ref="contentElement" class="workbench-content" :style="auth.isPersonal?{'--workbench-side-width':`${sidePanelWidth}px`}:undefined">
      <section class="file-browser" aria-label="文件浏览区" :aria-busy="loading">
        <div v-if="selectedCount" class="selection-toolbar"><strong>已选择 {{ selectedCount }} 个文件</strong><button class="desk-button is-primary" :disabled="archiving || !eligibleSelected.length" @click="archiveSelected">{{ archiving ? '正在提交…' : `确认并归档这 ${eligibleSelected.length} 个文件` }}</button><details><summary>高级整理</summary><button class="desk-button" @click="createPreview">历史整理预览</button></details><button class="desk-button" @click="reanalyzeSelected">重新分析</button><button class="desk-text-button" @click="clearSelection">取消选择</button></div>
        <p v-if="archiveResult" role="status">{{ archiveResult }}</p><div class="file-scroll">
          <table v-if="items.length && viewMode === 'list'" class="file-table">
            <thead><tr><th class="check-column"><input type="checkbox" :checked="allPageSelected" :indeterminate="selectedCount > 0 && !allPageSelected" aria-label="选择本页全部文件" @change="togglePageSelection" /></th><th>名称</th><th class="tags-column">标签</th><th class="date-column">导入时间</th><th class="size-column">大小</th><th class="state-column">状态</th><th class="download-column">操作</th></tr></thead>
            <tbody><tr v-for="file in items" :key="file.id" :class="{ inspected: drawerId === file.id, selected: selectedIds.has(file.id) }" tabindex="0" @click="inspectClickedFile(file.id, $event)" @dblclick="openListedFile(file.id, $event)" @keydown.enter.prevent="openListedFile(file.id)" @keydown.space.prevent="toggleSelection(file.id)" @contextmenu="openContext($event, file.id)">
              <td class="check-column"><input type="checkbox" :checked="selectedIds.has(file.id)" :aria-label="`选择 ${file.fileName}`" @click.stop @keydown.stop @change="toggleSelection(file.id)" /></td>
              <td><div class="desk-file-name"><FileTile :file-type="file.fileType" size="sm" /><div><span :title="file.fileName">{{ file.fileName }}</span><small>{{ descOf(file) }}</small></div></div></td>
              <td class="tags-column"><div class="desk-row-tags"><span v-for="tag in file.tags.filter(t => t.status !== 'REJECTED').slice(0, 2)" :key="tag.id" class="desk-tag" :class="{ pending: tag.status === 'PENDING_CONFIRMATION' }">{{ tag.name }}</span><span v-if="!file.tags.length" class="muted">—</span></div></td>
              <td class="date-column">{{ shortTime(file.uploadTime) }}</td><td class="size-column">{{ formatBytes(file.fileSize) }}</td><td class="state-column"><span class="desk-state" :class="stateMini(file).cls">{{ stateMini(file).label }}</span></td>
              <td class="download-column"><a class="file-download" :href="fileApi.fileDownloadUrl(file.id)" :download="file.fileName" :aria-label="`下载 ${file.fileName}`" @click.stop @keydown.stop>下载</a></td>
            </tr></tbody>
          </table>
          <div v-else-if="items.length" class="desk-file-grid"><article v-for="file in items" :key="file.id" class="desk-file-card" :class="{ inspected: drawerId === file.id, selected: selectedIds.has(file.id) }" tabindex="0" @click="inspectClickedFile(file.id, $event)" @dblclick="openListedFile(file.id, $event)" @keydown.enter.prevent="openListedFile(file.id)" @contextmenu="openContext($event, file.id)"><div class="desk-card-top"><input type="checkbox" :checked="selectedIds.has(file.id)" :aria-label="`选择 ${file.fileName}`" @click.stop @keydown.stop @change="toggleSelection(file.id)" /><span class="desk-state" :class="stateMini(file).cls">{{ stateMini(file).label }}</span></div><div class="desk-card-preview"><img v-if="imagePreview(file)" :src="`/api/files/${file.id}/content`" :alt="file.fileName" loading="lazy" @error="markImageUnavailable(file.id)" /><FileTile v-else :file-type="file.fileType" size="lg" /></div><h3 :title="file.fileName">{{ file.fileName }}</h3><p>{{ descOf(file) }}</p><div class="desk-row-tags"><span v-for="tag in file.tags.filter(t => t.status !== 'REJECTED').slice(0, 2)" :key="tag.id" class="desk-tag" :class="{ pending: tag.status === 'PENDING_CONFIRMATION' }">{{ tag.name }}</span></div><footer>{{ shortTime(file.uploadTime) }}<span>{{ formatBytes(file.fileSize) }}</span></footer></article></div>
          <div v-else class="desk-empty"><el-icon><FolderOpened /></el-icon><h2>{{ loading ? '正在读取文件库…' : loadFailed ? '文件库暂时无法加载' : keyword || activeTag ? '没有找到匹配的文件' : '你的文件库，从这里开始' }}</h2><p>{{ loadFailed ? (auth.isPersonal ? '请检查本地后台状态，再刷新重试。' : '请检查服务连接，再刷新重试。') : keyword || activeTag ? '试试其他关键词，或清除当前筛选条件。' : '导入文档、图片和资料，在这里浏览、查找与整理。' }}</p><button v-if="keyword.trim() && !loading && !loadFailed" class="desk-button is-primary" @click="findWithAi">用 AI 查找“{{ keyword }}”</button><button class="desk-button" @click="loadFailed ? reload() : keyword || activeTag ? resetFilters() : onUploadClick()">{{ loadFailed ? '重新加载' : keyword || activeTag ? '清除筛选' : '导入第一个文件' }}</button></div>
        </div>
        <footer class="browser-footer"><span>{{ selectedCount ? `已选择 ${selectedCount} 个文件` : '单击查看详情 · 双击打开文件 · 右键查看更多操作' }}</span><el-pagination v-if="totalPages > 1" v-model:current-page="page" :page-size="PAGE_SIZE" :total="total" layout="prev, pager, next" :pager-count="5" small /></footer>
      </section>
      <PaneResizeHandle v-if="canResizePanels && (showInspector || aiOpen)" label="调整主区域右边界" :size="sidePanelWidth" :min="280" :max="sidePanelMax" reverse @resize="resizeSidePanel" @commit="saveSidePanel" @reset="resetSidePanel"/>
      <FileDetailDrawer v-show="showInspector && !aiOpen" v-model="drawerId" inline @changed="reload" @reanalyzed="goToReanalysis" />
      <aside v-if="aiMounted" v-show="aiOpen" class="workbench-ai"><div class="ai-panel-heading"><span>AI 文件助手</span><button class="desk-icon-button" aria-label="收起 AI 助手" @click="aiOpen = false">×</button></div><ChatDock :question="aiQuestion" /></aside>
    </div>
    <footer class="workbench-status"><span class="status-indicator"></span><span>{{ workspace.unavailable ? '任务状态暂时不可用' : !workspace.overview ? '正在读取任务状态…' : workspace.overview.processingCount ? `正在解析 ${workspace.overview.processingCount} 个文件` : '当前没有正在解析的文件' }}</span><RouterLink v-if="workspace.overview?.pendingConfirmCount" :to="pendingDestination">{{ workspace.overview.pendingConfirmCount }} 个文件待确认标签</RouterLink><RouterLink v-if="workspace.overview?.failedCount" :to="taskDestination" class="status-error">{{ workspace.overview.failedCount }} 个任务失败</RouterLink><RouterLink :to="taskDestination" class="status-tasks">查看任务 <el-icon><ArrowRight /></el-icon></RouterLink></footer>
    <div v-if="contextMenu" class="file-context-menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @click.stop><button :disabled="isOpening(contextMenu.id)" @click="openListedFile(contextMenu.id)">打开文件</button><button @click="inspect(contextMenu.id)">查看文件详情</button><a :href="fileApi.fileDownloadUrl(contextMenu.id)" :download="items.find(file => file.id === contextMenu?.id)?.fileName" @click="dismissContext">下载到本地</a><button @click="selectedIds = new Set([contextMenu.id]); contextMenu = null; createPreview()">生成整理预览</button><button @click="toggleSelection(contextMenu.id); contextMenu = null">{{ selectedIds.has(contextMenu.id) ? '取消选择' : '选择文件' }}</button></div>
  </div>
</template>

<style scoped>
.desktop-files { height: 100%; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: var(--panel); }
.workbench-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 27px 28px 22px; flex: none; }
.workbench-breadcrumb { display: flex; align-items: center; gap: 7px; font-size: 11px; color: var(--text-3); margin-bottom: 9px; }
.workbench-breadcrumb .el-icon { font-size: 9px; }
.workbench-header h1 { font-size: 24px; letter-spacing: -.5px; margin: 0; line-height: 1.4; display: flex; align-items: center; gap: 12px; }
.file-total { font-size: 11px; font-weight: 400; color: var(--text-3); border: 1px solid var(--line); padding: 2px 8px; border-radius: 5px; letter-spacing: 0; }
.workbench-header p { margin: 6px 0 0; font-size: 12px; color: var(--text-3); }
.header-actions { display: flex; gap: 10px; align-items: center; }
.desk-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--line-strong); border-radius: 7px; background: var(--panel); color: var(--text-2); font-size: 12px; padding: 7px 12px; white-space: nowrap; cursor: pointer; }
.desk-button:hover { background: var(--hover-bg); color: var(--text-1); }
.desk-button.is-primary { background: var(--accent); border-color: var(--accent); color: #fff; padding-inline: 16px; }
.desk-button.is-primary:hover { background: var(--accent-hover); }
.desk-button.is-active { color: var(--accent); background: rgb(var(--accent-rgb) / .08); border-color: rgb(var(--accent-rgb) / .3); }
.desk-button .el-icon { font-size: 15px; }
.workbench-toolbar { display: flex; align-items: center; gap: 10px; padding: 0 28px 18px; border-bottom: 1px solid var(--line); flex: none; }
.desk-search { display: flex; align-items: center; gap: 10px; min-width: 160px; flex: 1; max-width: 560px; padding: 0 12px; height: 37px; background: var(--panel-3); border: 1px solid var(--line); border-radius: 7px; color: var(--text-3); }
.desk-search:focus-within { border-color: var(--accent); }
.desk-search input { flex: 1; min-width: 0; background: transparent; border: 0; outline: none !important; font-size: 12px; color: var(--text-1); height: 100%; }
.desk-search input::placeholder { color: var(--text-3); }
.desk-search kbd { font-family: inherit; font-size: 10px; border: 1px solid var(--line-strong); border-radius: 4px; padding: 1px 5px; white-space: nowrap; }
.ai-search-link { color: var(--accent); border-color: transparent; background: transparent; font-size: 11px; padding-inline: 6px; }
.desk-sort { margin-left: auto; font-size: 11px; color: var(--text-2); border: 0; background: var(--panel); padding: 7px 5px; max-width: 100px; }
.view-switch { display: flex; border: 1px solid var(--line); border-radius: 7px; padding: 3px; gap: 2px; }
.view-switch button, .desk-icon-button { width: 28px; height: 28px; display: grid; place-items: center; background: transparent; border: 0; border-radius: 5px; font-size: 15px; color: var(--text-3); cursor: pointer; }
.view-switch button.active { background: rgb(var(--accent-rgb) / .1); color: var(--accent); }
.view-switch button:hover, .desk-icon-button:hover { background: var(--hover-bg); }
.desk-icon-button:disabled { opacity: .4; cursor: wait; }
.desk-filter-bar { padding: 10px 28px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; border-bottom: 1px solid var(--line); font-size: 11px; color: var(--text-3); }
.desk-tag { display: inline-flex; align-items: center; gap: 4px; color: var(--text-2); background: var(--tag-bg); border: 0; border-radius: 4px; padding: 2px 6px; font-size: 10px; max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
button.desk-tag { cursor: pointer; }
.desk-tag button { background: transparent; border: 0; padding: 0 2px; color: inherit; cursor: pointer; }
.desk-tag.pending { color: var(--warn); background: var(--warn-tint); }
.desk-text-button { border: 0; background: transparent; color: var(--accent); font-size: 11px; cursor: pointer; padding: 4px 7px; }
.workbench-content { display: flex; min-height: 0; min-width: 0; flex: 1; overflow: hidden; }
.file-browser { display: flex; flex-direction: column; min-width: 0; min-height: 0; flex: 1; }
.selection-toolbar { display: flex; align-items: center; gap: 8px; padding: 10px 22px; background: rgb(var(--accent-rgb) / .06); border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.selection-toolbar strong { color: var(--accent); font-size: 11px; margin-right: auto; font-weight: 500; }
.selection-toolbar .desk-button { font-size: 11px; padding: 4px 8px; }
.file-scroll { flex: 1; min-height: 0; overflow: auto; }
.file-table { border-collapse: separate; border-spacing: 0; width: 100%; table-layout: fixed; font-size: 11px; }
.file-table th { position: sticky; top: 0; z-index: 1; text-align: left; font-weight: 400; background: var(--bg); color: var(--text-3); padding: 12px 8px; border-bottom: 1px solid var(--line); }
.file-table td { border-bottom: 1px solid var(--line); padding: 16px 8px; color: var(--text-2); }
.file-table th:last-child, .file-table td:last-child { padding-right: 16px; }
.file-table tbody tr { cursor: pointer; }
.file-table tbody tr:hover { background: var(--card-hover-bg); }
.file-table tbody tr.inspected, .file-table tbody tr.selected { background: rgb(var(--accent-rgb) / .06); }
.file-table tbody tr.inspected td:nth-child(2) { color: var(--accent); }
.check-column { width: 38px; text-align: center !important; }
.check-column input { cursor: pointer; width: 12px; height: 12px; }
.tags-column { width: 120px; }
.date-column { width: 90px; font-variant-numeric: tabular-nums; }
.size-column { width: 68px; font-variant-numeric: tabular-nums; }
.download-column { width: 52px; }
.file-download { color: var(--accent); text-decoration: none; font-size: 12px; }
.file-download:hover { text-decoration: underline; }
.state-column { width: 76px; }
.desk-file-name { display: flex; align-items: center; gap: 10px; }
.desk-file-name > div { min-width: 0; }
.desk-file-name > div > span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-1); font-size: 12px; font-weight: 500; }
.desk-file-name small { display: block; color: var(--text-3); font-size: 10px; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.desk-row-tags { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; min-width: 0; }
.desk-state { font-size: 10px; padding: 3px 6px; border-radius: 4px; white-space: nowrap; background: var(--tag-bg); color: var(--text-3); }
.desk-state.is-ok { color: var(--ok); background: rgb(var(--ok-rgb) / .09); }
.desk-state.is-wait { color: var(--warn); background: var(--warn-tint); }
.desk-state.is-run { color: var(--accent); background: rgb(var(--accent-rgb) / .1); }
.desk-state.is-fail { color: var(--danger); background: rgb(var(--danger-rgb) / .1); }
.muted { color: var(--text-3); }
.browser-footer { display: flex; align-items: center; justify-content: space-between; padding: 12px 22px; font-size: 10px; color: var(--text-3); border-top: 1px solid var(--line); gap: 8px; flex-wrap: wrap; min-height: 44px; }
.desk-file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 14px; padding: 20px; }
.desk-file-card { border: 1px solid var(--line); border-radius: 9px; padding: 14px; min-width: 0; cursor: pointer; }
.desk-file-card:hover { border-color: var(--line-strong); background: var(--card-hover-bg); }
.desk-file-card.inspected, .desk-file-card.selected { border-color: var(--accent); background: rgb(var(--accent-rgb) / .04); }
.desk-card-top { display: flex; align-items: center; justify-content: space-between; }
.desk-card-preview { background: var(--panel-3); display: grid; place-items: center; height: 104px; border-radius: 6px; margin: 12px 0; }
.desk-card-preview img { width: 100%; height: 104px; object-fit: contain; border-radius: 6px; }
.desk-file-card h3 { margin: 0; font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.desk-file-card p { margin: 4px 0 10px; color: var(--text-3); font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.desk-file-card footer { display: flex; justify-content: space-between; color: var(--text-3); font-size: 10px; border-top: 1px solid var(--line); padding-top: 10px; margin-top: 14px; }
.desk-empty { min-height: 300px; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; gap: 8px; padding: 28px; }
.desk-empty > .el-icon { font-size: 40px; color: var(--accent); background: rgb(var(--accent-rgb) / .06); border-radius: 14px; padding: 20px; box-sizing: content-box; }
.desk-empty h2 { font-size: 16px; font-weight: 500; margin: 12px 0 0; }
.desk-empty p { color: var(--text-3); font-size: 12px; margin: 0 0 12px; }
.workbench-ai { flex: 0 0 var(--workbench-side-width,360px); display: flex; flex-direction: column; min-height: 0; border-left: 1px solid var(--line); }
.ai-panel-heading { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px 0; font-size: 13px; font-weight: 600; }
.workbench-ai :deep(.chat-card) { flex: 1; min-height: 0; border-radius: 0; }
.workbench-status { display: flex; align-items: center; gap: 12px; flex: none; padding: 9px 24px; min-height: 36px; background: var(--bg); border-top: 1px solid var(--line); color: var(--text-3); font-size: 11px; }
.status-indicator { width: 5px; height: 5px; border-radius: 50%; background: var(--accent); }
.workbench-status a { text-decoration: none; color: var(--accent); }
.workbench-status .status-error { color: var(--danger); }
.status-tasks { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.file-context-menu { position: fixed; z-index: 110; min-width: 180px; padding: 5px; border: 1px solid var(--line-strong); border-radius: 8px; background: var(--panel); box-shadow: 0 6px 20px #0001; }
.file-context-menu a { display: block; padding: 8px 12px; color: var(--text-1); font-size: 12px; text-decoration: none; border-radius: 4px; }
.file-context-menu a:hover { background: var(--hover-bg); }
.file-context-menu button { display: block; width: 100%; border: 0; background: transparent; color: var(--text-1); text-align: left; padding: 8px 10px; font-size: 12px; border-radius: 5px; cursor: pointer; }
.file-context-menu button:hover { background: var(--hover-bg); }
@media (max-width: 1350px) { .date-column { display: none; } .tags-column { width: 104px; } }
@media (max-width: 1200px) { .workbench-ai { flex-basis: var(--workbench-side-width,320px); } .size-column { display: none; } .ai-search-link { display: none; } .workbench-header, .workbench-toolbar { padding-inline: 20px; } }
@media (max-width: 960px) { .workbench-ai { position: fixed; top: 0; bottom: 36px; right: 0; width: min(360px, calc(100vw - 210px)); z-index: 50; background: var(--panel); box-shadow: -8px 0 24px #0002; } .header-actions { gap: 6px; } .desk-search kbd { display: none; } }
</style>
