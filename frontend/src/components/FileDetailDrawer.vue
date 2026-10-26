<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, nextTick, ref, watch, onUnmounted } from 'vue'
import ArchiveConfirmationCard from '@/components/ArchiveConfirmationCard.vue'
import { taskStateLabel } from '@/utils/status'

import * as fileApi from '@/api/files'
import * as governanceApi from '@/api/governance'
import { confirmTag, rejectTag } from '@/api/fileTags'
import type { ChatCitation, FileDetailResponse, FileStatus } from '@/api/types'
import FileTile from '@/components/FileTile.vue'
import WorkCopyPanel from '@/components/WorkCopyPanel.vue'
import { desktopBridge } from '@/api/desktop'
import { useOpenFile } from '@/composables/useOpenFile'
import { useArchiveFile } from '@/composables/useArchiveFile'
import { useDetailPresentation } from '@/composables/useDetailPresentation'
import '@/styles/detail-presentation.css'
import { formatBytes, shortTime, toDisplayTime } from '@/utils/format'

/**
 * 文件详情抽屉：/files 与 /search 共用，按 id 走真实接口拉详情并执行写操作
 * （重命名 / 删除 / 标签确认 / 拒绝可填修正），成功后 emit changed 供宿主刷新列表。
 * 标签是主要组织维度；固定分类只作内部归档目录，抽屉不展示也不提供改分类入口。
 */

/** 抽屉统一的展示形状（真实详情映射到它，模板只依赖该形状） */
type DrawerFile = {
  id: number
  fileName: string
  fileType: string | null
  fileSize: number
  /** 展示用 'YYYY-MM-DD HH:mm' */
  uploadTime: string
  summary: string | null
  status: FileStatus
  taskState?: string
  archived: boolean
  tags: { id: number; name: string; status: 'CONFIRMED' | 'PENDING_CONFIRMATION' | 'REJECTED' }[]
  previewUrl?: string
}

const props = defineProps<{
  modelValue: number | null
  revision?: number
  source?: ChatCitation
  /** 在对话放大态打开时提升抽屉层级，避免被对话遮罩覆盖。 */
  elevated?: boolean
  /** Workbench inspector; retains the same real actions and confirmation dialogs. */
  inline?: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: number | null): void
  /** 任一写操作成功后触发，宿主据此刷新列表/计数 */
  (e: 'changed', id: number): void
  (e: 'reanalyzed', previewId: string): void
  /** 引用打开失败或文件已删除时通知引用卡片显示失效提示。 */
  (e: 'unavailable', id: number): void
}>()

const open = computed(() => props.modelValue !== null)
const { expanded, panel, toggleButton, setExpanded, onKeydown: presentationKeydown } = useDetailPresentation(
  () => `${props.modelValue}:${props.revision ?? ''}`,
)
const loading = ref(false)
/** 当前打开文件拉取的真实详情（切换/关闭时置空，未加载完成为 null） */
const detail = ref<FileDetailResponse | null>(null)
const reanalyzing = ref(false)
const { openFile, isOpening, canOpenFile } = useOpenFile()
const { completedRevision } = useArchiveFile()
watch(completedRevision, () => { if (props.modelValue != null) { void loadDetail(props.modelValue); emit('changed', props.modelValue) } })
const editorPanel = ref<InstanceType<typeof WorkCopyPanel>>()
const editorExpanded = ref(false), editing = ref(false)
async function editFile() {
  if (editing.value) return
  editing.value = true; editorExpanded.value = true
  try { await nextTick(); await editorPanel.value?.startEditing() }
  finally { editing.value = false }
}

function fromDetail(d: FileDetailResponse): DrawerFile {
  const tags: DrawerFile['tags'] = [
    ...(d.confirmedTags ?? []).map((t) => ({
      id: t.tagId,
      name: t.tagName,
      status: 'CONFIRMED' as const,
    })),
    ...(d.pendingTags ?? []).map((t) => ({
      id: t.tagId,
      name: t.tagName,
      status: 'PENDING_CONFIRMATION' as const,
    })),
  ]
  return {
    id: d.id,
    fileName: d.fileName,
    fileType: d.fileType,
    fileSize: d.fileSize,
    uploadTime: toDisplayTime(d.uploadTime),
    summary: d.summary,
    status: d.status ?? 'PENDING',
    archived: d.archived,
    taskState: d.taskState,
    tags,
    previewUrl: d.previewUrl || undefined,
  }
}

/** 当前展示的文件：仅真实详情 */
const file = computed<DrawerFile | undefined>(() => {
  const id = props.modelValue
  if (id === null) return undefined
  return detail.value ? fromDetail(detail.value) : undefined
})

let detailRequest = 0
async function loadDetail(id: number) {
  const request = ++detailRequest
  loading.value = true
  try {
    const { data } = await fileApi.getFileDetail(id, props.revision)
    if (request === detailRequest && props.modelValue === id) detail.value = data.data
  } catch {
    if (request !== detailRequest || props.modelValue !== id) return
    /* 拦截器已弹错：加载失败直接收起抽屉 */
    emit('unavailable', id)
    emit('update:modelValue', null)
  } finally {
    if (request === detailRequest) loading.value = false
  }
}

/* —— 顶栏动作模态 —— */
type Action = 'rename' | 'delete' | null
const action = ref<Action>(null)
const renameValue = ref('')

function close() {
  emit('update:modelValue', null)
}

function openAction(a: Exclude<Action, null>) {
  if (a === 'rename') renameValue.value = file.value?.fileName ?? ''
  action.value = a
}

/** 重命名 */
async function submitRename() {
  const id = props.modelValue
  const v = renameValue.value.trim()
  if (!id) return
  if (!v) return ElMessage.warning('文件名为空')
  const { data } = await fileApi.renameFile(id, { fileName: v })
  detail.value = data.data
  emit('changed', id)
  action.value = null
  ElMessage.success('已重命名')
}

/** 删除 */
async function submitDelete() {
  const id = props.modelValue
  if (!id) return
  await fileApi.deleteFile(id)
  emit('unavailable', id)
  emit('changed', id)
  action.value = null
  close()
  ElMessage.success('已删除文件及其归档记录')
}

async function reanalyze() {
  const id = props.modelValue
  if (!id || reanalyzing.value) return
  reanalyzing.value = true
  try {
    const requestId = globalThis.crypto?.randomUUID?.()
      ?? `reanalyze-${id}-${Date.now()}-${Math.random().toString(36).slice(2)}`
    const { data } = await governanceApi.reanalyzeGovernanceFile(id, {
      requestId,
      mode: 'API',
    })
    const previewId = data.data?.previewId
    if (previewId) {
      emit('reanalyzed', previewId)
      ElMessage.success('新的分析预览已生成')
    }
  } finally {
    reanalyzing.value = false
  }
}

/* —— 标签确认 / 拒绝 —— */
const editingTag = ref<number | null>(null)
const correction = ref('')
const tagBusy = ref(false)
const tagEditName = computed(() => file.value?.tags.find(t => t.id === editingTag.value)?.name ?? '')

async function doConfirm(tagId: number) {
  const id = props.modelValue
  if (!id || tagBusy.value) return
  tagBusy.value = true
  try {
    await confirmTag({ fileId: id, tagId })
    if (props.modelValue !== id) return
    await loadDetail(id)
    emit('changed', id)
    ElMessage.success('标签已确认')
  } catch { /* The request layer displays failures; retain the tag for retry. */ }
  finally { tagBusy.value = false }
}

function beginReject(tagId: number) {
  if (tagBusy.value) return
  editingTag.value = tagId
  correction.value = ''
}
function cancelReject() {
  editingTag.value = null
}
async function submitReject() {
  const id = props.modelValue
  const tagId = editingTag.value
  if (!id || tagId === null || tagBusy.value) return
  const newName = correction.value.trim()
  tagBusy.value = true
  try {
    await rejectTag(newName ? { fileId: id, tagId, newTagName: newName } : { fileId: id, tagId })
    if (props.modelValue !== id) return
    await loadDetail(id)
    emit('changed', id)
    editingTag.value = null
    ElMessage.success(newName ? `已移除原标签，并记录为「${newName}」` : '已移除该文件的标签')
  } catch { /* Keep the editor open so a failed request can be retried. */ }
  finally { tagBusy.value = false }
}

/* —— 预览 —— */
const isImage = computed(() => ['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(file.value?.fileType?.toLowerCase() ?? ''))
const sourceLabel = computed(() => {
  const source = props.source
  if (!source) return ''
  if (source.sourceKind === 'ORIGINAL_IMAGE') return '原图'
  if (!source.sourceStart) return ''
  if (source.sourceKind === 'PAGE') return `第 ${source.sourceStart} 页`
  if (source.sourceKind === 'LINE') return `第 ${source.sourceStart} 行`
  if (source.sourceKind === 'PARAGRAPH') return `第 ${source.sourceStart} 段`
  return '原文'
})
const previewLink = computed(() => {
  const url = file.value?.previewUrl
  if (!url) return undefined
  const page = props.source?.sourceKind === 'PAGE' ? props.source.sourceStart : undefined
  return page && file.value?.fileType?.toLowerCase() === 'pdf' ? `${url}#page=${page}` : url
})
const downloadLink = computed(() => file.value
  ? fileApi.fileDownloadUrl(file.value.id, detail.value?.revision ?? props.revision)
  : undefined)

const confirmedTags = computed(() => file.value?.tags.filter((t) => t.status === 'CONFIRMED') ?? [])
const pendingTags = computed(
  () => file.value?.tags.filter((t) => t.status === 'PENDING_CONFIRMATION') ?? [],
)
const rejectedTags = computed(() => file.value?.tags.filter((t) => t.status === 'REJECTED') ?? [])

/* —— 关闭 / 文件被删时复位 —— */
watch(open, (v) => {
  if (!v) {
    detailRequest++
    action.value = null
    editingTag.value = null
    detail.value = null
  }
})
watch(
  () => [props.modelValue, props.revision] as const,
  async ([id]) => {
    editingTag.value = null
    correction.value = ''
    detail.value = null
    if (id !== null) {
      await loadDetail(id)
    } else {
      await nextTick()
    }
  },
  { immediate: true },
)
const refreshTimer = setInterval(() => {
  if (props.modelValue && !props.revision) void loadDetail(props.modelValue)
}, 3000)
onUnmounted(() => clearInterval(refreshTimer))
</script>

<template>
  <div
    class="drawer-wrap"
    :class="{ 'is-open': open, 'is-elevated': props.elevated, 'is-inline': props.inline, 'is-expanded': expanded }"
    :aria-hidden="!open && !props.inline"
    @keydown.capture="presentationKeydown"
  >
    <!-- 遮罩 -->
    <div v-if="!props.inline || expanded" class="drawer-mask" @click="expanded ? setExpanded(false) : close()"></div>

    <!-- 抽屉面板 -->
    <aside v-if="file" ref="panel" class="drawer-panel" :role="expanded ? 'dialog' : undefined" :aria-modal="expanded || undefined" :aria-label="expanded ? '文件详情' : undefined" tabindex="-1">
      <div v-if="props.inline" class="inspector-heading">文件详情 <span>预览与 AI 信息</span></div>
      <!-- 顶栏：图章 + 文件名 + 操作 -->
      <div class="drawer-head">
        <div class="drawer-title-row">
          <FileTile :file-type="file.fileType" size="md" />
          <div class="drawer-title">
            <h2 class="drawer-name">{{ file.fileName }}</h2>
            <div class="drawer-meta-line">
              <span class="meta-time">上传于 {{ shortTime(file.uploadTime) }}</span>
            </div>
          </div>
          <button ref="toggleButton" class="head-expand" :aria-label="expanded ? '收起文件详情' : '放大文件详情'" :aria-expanded="expanded" @click="setExpanded(!expanded)"><span aria-hidden="true">{{ expanded ? '↙' : '↗' }}</span>{{ expanded ? '收起' : '放大' }}</button>
          <button class="head-x" title="关闭" @click="close">✕</button>
        </div>
        <div class="drawer-actions">
          <button v-if="canOpenFile(file.fileType)" class="act-btn is-download" :disabled="isOpening(file.id)" @click="openFile(file.id, detail?.revision ?? props.revision, file.fileType)">{{ isOpening(file.id) ? '正在打开…' : '打开文件' }}</button>
          <button v-if="desktopBridge && props.revision === undefined" class="act-btn" :disabled="editing" @click="editFile">{{ editing ? '正在准备…' : '编辑文件' }}</button>
          <a v-if="downloadLink" class="act-btn is-download" :href="downloadLink" :download="file.fileName">下载到本地</a>
          <button
            v-if="file.status === 'COMPLETED' || file.status === 'FAILED'"
            class="act-btn is-reanalyze"
            :disabled="reanalyzing"
            @click="reanalyze"
          >
            {{ reanalyzing ? '分析中…' : '重新分析' }}
          </button>
          <button class="act-btn" @click="openAction('rename')">重命名</button>
          <button class="act-btn is-danger" @click="openAction('delete')">删除</button>
        </div>
      </div>

      <!-- 预览区 -->
      <div class="drawer-body">
        <div class="preview" :class="{ 'is-image': isImage }">
          <template v-if="isImage">
            <img v-if="file.previewUrl" :src="file.previewUrl" class="preview-photo" alt="文件预览" />
            <FileTile v-else :file-type="file.fileType" size="lg" />
            <p v-if="!file.previewUrl" class="preview-note">暂无预览图片（对象存储中该文件可能已被清理）</p>
          </template>
          <template v-else>
            <FileTile :file-type="file.fileType" size="lg" />
            <p class="preview-note">
              {{ desktopBridge ? '点击“打开文件”查看；需要修改时选择“编辑文件”。查看副本的改动不会写回文件库。' : canOpenFile(file.fileType) ? '可打开文件在线阅读。' : '该类型暂不支持在线阅读，请下载到本地查看。' }}<template v-if="previewLink && !desktopBridge && canOpenFile(file.fileType)">
                ，可
                <a class="preview-link" :href="previewLink" target="_blank" rel="noreferrer">打开文件预览</a>
              </template>
            </p>
          </template>
          <span class="preview-size">{{ formatBytes(file.fileSize) }}</span>
        </div>

        <section v-if="source && sourceLabel" class="block">
          <h3 class="block-title">引用位置 · {{ sourceLabel }}</h3>
          <p v-if="source.snippet" class="summary">{{ source.snippet }}</p>
        </section>

        <!-- AI 摘要 -->
        <section class="block">
          <h3 class="block-title">AI 摘要</h3>
          <p class="summary" :class="{ 'is-empty': !file.summary }">
            {{ file.summary || (file.status === 'PROCESSING' ? 'AI 正在理解文件内容…' : '暂无摘要') }}
          </p>
        </section>

        <ArchiveConfirmationCard v-if="detail && !file.archived && props.revision === undefined" :file="detail" @changed="loadDetail(file.id); emit('changed', file.id)" @later="close()" />
        <!-- 标签 -->
        <section v-if="file.archived" class="block">
          <div class="block-title-row">
            <h3 class="block-title">标签</h3>
            <span class="block-count">
              {{ confirmedTags.length + pendingTags.length }} 个 · {{ pendingTags.length }} 个待确认
            </span>
          </div>

          <!-- 已确认标签 -->
          <div v-if="confirmedTags.length" class="tag-row">
            <span v-for="t in confirmedTags" :key="t.id" class="tag is-confirmed">
              <span class="tag-check">✓</span><span class="tag-name">{{ t.name }}</span>
              <button type="button" class="op-btn is-no" :disabled="tagBusy" :aria-label="`移除标签 ${t.name}`" title="移除标签" @click="beginReject(t.id)">✕</button>
            </span>
          </div>

          <!-- 待确认标签：确认 / 拒绝（可填修正） -->
          <div v-if="pendingTags.length" class="pending-block">
            <p class="pending-hint">AI 建议的标签，等待你确认：</p>
            <div v-for="t in pendingTags" :key="t.id" class="tag is-pending">
              <span class="tag-name">{{ t.name }}</span>
              <span class="pending-ops">
                <button type="button" class="op-btn is-ok" :disabled="tagBusy" :aria-label="`确认标签 ${t.name}`" title="确认标签" @click="doConfirm(t.id)">✓</button>
                <button type="button" class="op-btn is-no" :disabled="tagBusy" :aria-label="`移除标签 ${t.name}`" title="拒绝" @click="beginReject(t.id)">✕</button>
              </span>
            </div>
          </div>
            <!-- Both pending and confirmed tags can be removed or corrected. -->
            <div v-if="editingTag !== null" class="correction" :aria-busy="tagBusy">
              <p class="pending-hint">移除标签：{{ tagEditName }}。留空直接移除，也可填写替代标签。</p>
              <input
                v-model="correction"
                :disabled="tagBusy"
                class="correction-input"
                placeholder="如标签有误，可输入更贴切的名字"
                @keyup.enter="submitReject"
              />
              <div class="correction-ops">
                <button class="mini-btn" :disabled="tagBusy" @click="cancelReject">取消</button>
                <button class="mini-btn is-primary" :disabled="tagBusy" @click="submitReject">
                  {{ tagBusy ? '正在保存…' : correction.trim() ? '以修正标签替代' : '移除标签' }}
                </button>
              </div>
            </div>

          <!-- 已拒绝标签（留档；后端详情不返回已拒绝标签，恒空） -->
          <div v-if="rejectedTags.length" class="rejected-line">
            <span v-for="t in rejectedTags" :key="t.id" class="tag is-rejected">{{ t.name }}</span>
          </div>

          <p v-if="!confirmedTags.length && !pendingTags.length && !rejectedTags.length" class="no-tag">
            暂无标签{{ file.status === 'PROCESSING' ? '，解析完成后 AI 会自动给出建议' : '，可点击「AI 重新打标」或查看归档记录' }}
          </p>
        </section>

        <!-- 档案信息 -->
        <section class="block">
          <h3 class="block-title">档案信息</h3>
          <dl class="meta-list">
            <div class="meta-item">
              <dt>大小</dt>
              <dd class="meta-value">{{ formatBytes(file.fileSize) }}</dd>
            </div>
            <div class="meta-item">
              <dt>状态</dt>
              <dd class="meta-value">
                <span class="state-chip" :class="`is-${file.status.toLowerCase()}`">
                  {{ taskStateLabel(file) }}
                </span>
              </dd>
            </div>
            <div class="meta-item">
              <dt>归档</dt>
              <dd class="meta-value">
                <span :class="file.archived ? 'arch is-on' : 'arch'">
                  {{ file.archived ? '已归档' : '未归档' }}
                </span>
              </dd>
            </div>
            <div class="meta-item">
              <dt>上传时间</dt>
              <dd class="meta-value">{{ file.uploadTime }}</dd>
            </div>
          </dl>
        </section>

        <details v-if="desktopBridge && props.revision === undefined" :open="editorExpanded" class="file-editor-details" @toggle="editorExpanded = ($event.target as HTMLDetailsElement).open">
          <summary>编辑与版本保存</summary>
          <WorkCopyPanel :key="file.id" ref="editorPanel" :file-id="file.id" @saved="emit('changed', file.id); loadDetail(file.id)" />
        </details>
      </div>
    </aside>

    <!-- 打开瞬间的加载 / 加载失败兜底 -->
    <aside v-else-if="open" class="drawer-panel drawer-panel--load">
      <button class="head-x" title="关闭详情" @click="close">✕</button>
      <p class="drawer-loading-text">{{ loading ? '正在加载文件详情…' : '无法加载该文件（可能已被删除）' }}</p>
    </aside>
    <aside v-else-if="props.inline" class="drawer-panel inspector-empty">
      <span class="empty-file-icon">▤</span><h2>文件详情</h2><p>选择一个文件，查看预览、AI 摘要和标签。</p><small>双击文件可直接打开</small>
    </aside>

    <!-- 动作模态：仅在有文件上下文时展示 -->
    <div v-if="action && file" class="modal-mask">
      <div class="modal">
        <template v-if="action === 'rename'">
          <h3 class="modal-title">重命名文件</h3>
          <p class="modal-sub">仅修改展示名与档案记录，对象存储中的原文件保持不变。</p>
          <input
            v-model="renameValue"
            class="modal-input"
            autofocus
            @keyup.enter="submitRename"
          />
          <div class="modal-ops">
            <button class="modal-btn" @click="action = null">取消</button>
            <button class="modal-btn is-primary" @click="submitRename">确定重命名</button>
          </div>
        </template>

        <template v-else>
          <h3 class="modal-title">删除文件</h3>
          <p class="modal-sub">
            将永久删除该文件及其 AI 摘要、标签与归档记录{{ file.archived ? '（含已归档副本）' : '' }}，此操作不可撤销。
          </p>
          <div class="modal-ops">
            <button class="modal-btn" @click="action = null">取消</button>
            <button class="modal-btn is-danger" @click="submitDelete">确认删除</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.drawer-wrap {
  position: fixed;
  inset: 0;
  z-index: 100;
  pointer-events: none;
}
.drawer-wrap.is-elevated {
  z-index: 300;
}
.drawer-wrap.is-open {
  pointer-events: auto;
}

.drawer-mask {
  position: absolute;
  inset: 0;
  background: rgb(5 5 8 / 0.55);
  opacity: 0;
  transition: opacity 0.2s ease;
}
.is-open .drawer-mask {
  opacity: 1;
}

.drawer-panel {
  position: absolute;
  top: var(--gap);
  right: var(--gap);
  bottom: var(--gap);
  width: min(480px, calc(100vw - 120px));
  display: flex;
  flex-direction: column;
  background: var(--panel-2);
  border: 1px solid var(--border-panel);
  border-radius: var(--radius-card);
  padding: var(--pad);
  transform: translateX(calc(100% + var(--gap)));
  transition: transform 0.22s ease;
  box-shadow: 0 12px 40px rgb(0 0 0 / 0.45);
}
.is-open .drawer-panel {
  transform: translateX(0);
}

/* 打开瞬间的加载 / 兜底面板 */
.drawer-panel--load {
  align-items: center;
  justify-content: center;
  color: var(--text-3);
}
.drawer-loading-text {
  margin: 0;
  font-size: 13px;
}

/* —— 顶栏 —— */
.drawer-head {
  flex: none;
  border-bottom: 1px solid var(--line);
  padding-bottom: 14px;
}
.drawer-title-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.drawer-title {
  flex: 1;
  min-width: 0;
}
.drawer-name {
  margin: 2px 0 6px;
  font-size: 15.5px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.4;
  word-break: break-all;
}
.drawer-meta-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.meta-time {
  font-size: 11.5px;
  color: var(--text-3);
}
.head-x {
  flex: none;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
}
.head-x:hover {
  background: var(--hover-bg-strong);
  color: var(--text-1);
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}
.act-btn {
  padding: 6px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-1);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}
.act-btn:hover {
  background: var(--hover-bg);
}
.act-btn.is-download {
  display: inline-flex;
  align-items: center;
  text-decoration: none;
  border-color: var(--accent);
  color: var(--accent);
}
.act-btn.is-danger {
  margin-left: auto;
  color: var(--danger);
}
.act-btn.is-danger:hover {
  background: rgb(var(--danger-rgb) / 0.12);
}
.act-btn.is-reanalyze {
  border-color: var(--accent);
  color: var(--accent);
}
.act-btn.is-reanalyze:hover:not(:disabled) {
  background: var(--accent);
  color: #fff;
}

/* —— 主体滚动 —— */
.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 2px 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* 预览区 */
.preview {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 168px;
  padding: 20px;
  border-radius: var(--radius-inner);
  background: var(--panel-3);
}
.preview-note {
  margin: 0;
  max-width: 320px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}
.preview-size {
  position: absolute;
  top: 10px;
  right: 12px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-3);
}
.preview-photo {
  max-width: 100%;
  max-height: 220px;
  border-radius: var(--radius-inner);
  object-fit: contain;
}
.preview-link {
  color: var(--accent);
}

/* 区块 */
.block-title-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.block-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.block-count {
  font-size: 11.5px;
  color: var(--text-3);
}
.summary {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-1);
}
.summary.is-empty {
  color: var(--text-3);
}

/* 标签 */
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  font-size: 12.5px;
  max-width: 100%;
}
.tag-name { min-width: 0; overflow-wrap: anywhere; }
.tag.is-confirmed {
  background: var(--tag-bg);
  color: var(--text-1);
}
.tag-check {
  font-size: 11px;
  color: var(--text-2);
}
.tag.is-pending {
  background: var(--tag-bg);
  color: var(--text-1);
  padding-right: 6px;
}
.pending-ops {
  display: inline-flex;
  flex-shrink: 0;
  gap: 4px;
  margin-left: 4px;
}
.op-btn {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 50%;
  font-size: 11px;
  cursor: pointer;
  display: grid;
  place-items: center;
}
.op-btn.is-ok {
  background: rgb(var(--ok-rgb) / 0.25);
  color: var(--ok);
}
.op-btn.is-no {
  background: rgb(var(--danger-rgb) / 0.18);
  color: var(--danger);
}
.op-btn.is-ok:hover {
  background: var(--ok);
  color: #fff;
}
.op-btn.is-no:hover {
  background: var(--danger);
  color: #fff;
}
.op-btn:disabled { opacity: .5; cursor: default; }

.pending-block {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pending-hint {
  width: 100%;
  margin: 0 0 -2px;
  font-size: 12px;
  color: var(--text-3);
}

.correction {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
  padding: 10px;
  border-radius: var(--radius-inner);
  background: var(--panel-3);
}
.correction-input {
  width: 100%;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: var(--panel-2);
  color: var(--text-1);
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.correction-input:focus {
  border-color: var(--accent);
}
.correction-ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.mini-btn {
  padding: 4px 12px;
  border: none;
  border-radius: var(--radius-pill);
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.mini-btn.is-primary {
  background: var(--accent);
  color: #fff;
}

.rejected-line {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag.is-rejected {
  padding: 3px 10px;
  background: transparent;
  border: 1px dashed var(--line-dashed);
  color: var(--text-3);
  text-decoration: line-through;
}
.no-tag {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}

/* 档案 */
.meta-list {
  margin: 0;
}
.meta-item {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--line);
}
.meta-item:last-child {
  border-bottom: none;
}
.meta-item dt {
  flex: none;
  width: 64px;
  font-size: 12.5px;
  color: var(--text-3);
}
.meta-value {
  font-size: 13px;
  color: var(--text-1);
}
.state-chip {
  padding: 1px 10px;
  border-radius: var(--radius-pill);
  font-size: 11.5px;
}
.state-chip.is-completed {
  background: rgb(var(--ok-rgb) / 0.16);
  color: var(--ok);
}
.state-chip.is-processing {
  background: var(--warn-tint);
  color: var(--warn);
}
.state-chip.is-failed {
  background: rgb(var(--danger-rgb) / 0.16);
  color: var(--danger);
}
.arch.is-on {
  color: var(--ok);
}
.arch {
  color: var(--text-2);
}

/* —— 动作模态 —— */
.modal-mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgb(5 5 8 / 0.5);
}
.modal {
  width: min(360px, calc(100vw - 60px));
  padding: 22px;
  border-radius: var(--radius-card);
  background: var(--panel-2);
  border: 1px solid var(--border-panel);
  box-shadow: 0 12px 40px rgb(0 0 0 / 0.5);
}
.modal-title {
  margin: 0 0 6px;
  font-size: 16px;
  color: var(--text-1);
}
.modal-sub {
  margin: 0 0 14px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.6;
}
.modal-input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-inner);
  background: var(--panel-3);
  color: var(--text-1);
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.modal-input:focus {
  border-color: var(--accent);
}
.modal-ops {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}
.modal-btn {
  padding: 7px 16px;
  border: none;
  border-radius: var(--radius-inner);
  background: var(--tag-bg);
  color: var(--text-2);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.modal-btn.is-primary {
  background: var(--accent);
  color: #fff;
}
.modal-btn.is-danger {
  background: var(--danger);
  color: #fff;
}
</style>

<style scoped>
.drawer-wrap.is-inline { position: relative; inset: auto; flex: 0 0 var(--workbench-side-width,340px); width: var(--workbench-side-width,340px); min-width: 0; height: 100%; z-index: 1; pointer-events: auto; border-left: 1px solid var(--line); }
.is-inline .drawer-panel { position: relative; inset: auto; height: 100%; width: 100%; border: 0; border-radius: 0; padding: 20px; transform: none; transition: none; box-shadow: none; background: var(--panel); }
.inspector-heading { display: flex; justify-content: space-between; font-weight: 600; font-size: 13px; margin-bottom: 20px; }
.inspector-heading span { color: var(--text-3); font-size: 11px; font-weight: 400; }
.is-inline .drawer-actions { flex-wrap: wrap; gap: 6px; }
.is-inline .act-btn { border-radius: 6px; padding: 5px 8px; font-size: 11px; }
.is-inline .drawer-body { padding-top: 18px; }
.is-inline .summary { background: var(--panel-3); font-size: 12px; line-height: 1.8; }
.is-inline .tag.is-pending { background: var(--warn-tint); color: var(--warn); }
.is-inline .tag { padding-inline: 8px; font-size: 11px; border-radius: 5px; }
.is-inline .meta-value { margin-left: 0; font-size: 12px; }
.is-inline .meta-item dt { font-size: 12px; }
.is-inline .drawer-panel--load .head-x { position: absolute; top: 14px; right: 14px; }
.inspector-empty { align-items: center; justify-content: center; text-align: center; color: var(--text-3); }
.inspector-empty h2 { color: var(--text-2); font-size: 14px; margin: 16px 0 8px; }
.inspector-empty p { max-width: 230px; font-size: 12px; margin: 0; }
.inspector-empty small { font-size: 11px; margin-top: 14px; }
.empty-file-icon { font-size: 32px; }
.is-inline .modal-mask { position: fixed; z-index: 150; }
@media (max-width: 1200px) { .drawer-wrap.is-inline { flex-basis: var(--workbench-side-width,300px); width: var(--workbench-side-width,300px); } .is-inline .drawer-panel { padding: 16px; } }
@media (max-width: 960px) { .drawer-wrap.is-inline.is-open { position: fixed; inset: 0 0 0 auto; width: min(380px, calc(100vw - 210px)); z-index: 100; box-shadow: -8px 0 24px #0002; } .drawer-wrap.is-inline:not(.is-open) { display: none; } }
@media (pointer: coarse), (max-width: 600px) {
  .op-btn { width: 44px; height: 44px; font-size: 15px; touch-action: manipulation; }
  .tag.is-pending { display: flex; width: 100%; justify-content: space-between; }
  .tag-name { flex: 1; }
  .correction-input { height: 44px; font-size: 16px; }
  .mini-btn { min-height: 44px; font-size: 14px; touch-action: manipulation; }
}
</style>
