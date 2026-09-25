<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { listFiles } from '@/api/files'
import type { FileListResponse } from '@/api/types'
import FileTile from '@/components/FileTile.vue'
import { useFileViewStore } from '@/stores/fileView'

const view = useFileViewStore()

/** 每屏展示条数 = 「最近上传」队列长度：默认（无标签）状态展示最近 8 个上传，先进先出窗口 */
const SHOW = 8

/** 真实文件列表（默认按上传时间倒序取最近 SHOW 条；选中标签时取含该标签的文件） */
const files = ref<FileListResponse[]>([])
const total = ref(0)
const loading = ref(false)

const shown = computed(() => files.value.slice(0, SHOW))
const hasMore = computed(() => total.value > shown.value.length)

/** 标题：标签筛选 → 「最近上传」 */
const title = computed(() => (view.activeTag ? `含「${view.activeTag}」` : '最近上传'))

/** 头部计数：标签筛选显示命中总数；“最近”默认视图注明「最近 N（≤8）· 共 M」 */
const countLabel = computed(() => {
  if (loading.value) return '加载中…'
  if (view.activeTag) return `${total.value} 个文件`
  return total.value > shown.value.length
    ? `最近 ${shown.value.length} 个 · 共 ${total.value} 个`
    : `${total.value} 个文件`
})

/** 空态文案随筛选类型变化 */
const emptyText = computed(() =>
  view.activeTag
    ? `暂无包含「${view.activeTag}」的文件。`
    : '还没有文件，上传后 AI 会自动归类到这里。',
)

/** 行副文本：失败 → 解析中 → 排队 → AI 摘要 → 已确认标签名 */
function descOf(f: FileListResponse) {
  if (f.status === 'FAILED') return '解析失败'
  if (f.status === 'PROCESSING') return 'AI 正在解析…'
  if (f.status === 'PENDING') return '排队等待解析'
  if (f.summary) return f.summary
  const confirmed = (f.tags ?? [])
    .filter((t) => t.confirmationStatus === 'CONFIRMED')
    .map((t) => t.tagName)
  return confirmed.join(' · ') || '暂无摘要'
}

/** 行右侧状态文案 */
function stateOf(f: FileListResponse) {
  if (f.status === 'COMPLETED') return '完成'
  if (f.status === 'PROCESSING') return '解析中'
  if (f.status === 'FAILED') return '失败'
  return '排队'
}

async function load() {
  loading.value = true
  try {
    const { data } = await listFiles({
      sort: 'new',
      size: SHOW,
      tag: view.activeTag || undefined,
    })
    files.value = data.data?.content ?? []
    total.value = data.data?.totalElements ?? 0
  } catch {
    files.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => view.activeTag, load)
</script>

<template>
  <section class="recent-card">
    <div class="card-head">
      <h2 class="card-title">{{ title }}</h2>
      <span class="card-count">{{ countLabel }}</span>
    </div>

    <ul v-if="shown.length" class="recent-list">
      <li v-for="f in shown" :key="f.id" class="recent-item">
        <FileTile :file-type="f.fileType" size="sm" />
        <div class="file-info">
          <span class="file-name">{{ f.fileName }}</span>
          <span class="file-desc" :class="{ 'is-error': f.status === 'FAILED' }">
            {{ descOf(f) }}
          </span>
        </div>
        <span class="file-state" :class="`is-${String(f.status).toLowerCase()}`">
          {{ stateOf(f) }}
        </span>
      </li>
    </ul>

    <!-- 空态 -->
    <div v-else-if="!loading" class="recent-empty">
      {{ emptyText }}
    </div>

    <!-- 超出展示上限时给出入口 -->
    <button v-if="hasMore" class="recent-more" @click="$router.push('/files')">
      查看全部文件 →
    </button>
  </section>
</template>

<style scoped>
.recent-card {
  background: var(--olive);
  border-radius: var(--radius-card);
  padding: var(--pad);
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.card-title {
  color: var(--ink-amber);
}
.card-count {
  color: rgb(var(--ink-amber-rgb) / 0.75);
}

.recent-list {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.recent-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-inner);
  background: rgb(255 255 255 / 0.5);
}
.recent-item:hover {
  background: rgb(255 255 255 / 0.7);
}

.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.file-name {
  color: var(--ink-amber);
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-desc {
  margin-top: 2px;
  font-size: 11.5px;
  color: rgb(var(--ink-amber-rgb) / 0.72);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-desc.is-error {
  color: #b33a2f;
}

.file-state {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-amber);
}
.file-state.is-completed {
  color: #2f7d3f;
}
.file-state.is-processing {
  color: #8a6a1f;
}
.file-state.is-failed {
  color: #b33a2f;
}
.file-state.is-pending {
  color: rgb(var(--ink-amber-rgb) / 0.6);
}

.recent-more {
  flex: none;
  align-self: flex-end;
  margin-top: 8px;
  padding: 4px 10px;
  border: none;
  border-radius: var(--radius-pill);
  background: rgb(var(--ink-amber-rgb) / 0.1);
  color: var(--ink-amber);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.recent-more:hover {
  background: rgb(var(--ink-amber-rgb) / 0.18);
}

.recent-empty {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: rgb(var(--ink-amber-rgb) / 0.65);
}
</style>
