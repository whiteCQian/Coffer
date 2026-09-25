<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { listTagCandidates } from '@/api/fileTags'
import type { TagCandidate } from '@/api/types'
import { useFileViewStore } from '@/stores/fileView'

/**
 * 「猜你需要的标签」：展示库中真实存在、被文件确认采用过的标签（含覆盖文件数），
 * 数据来自 GET /api/files/tags/candidates。第 1 格恒为「最近」，点它回到最近上传视图
 * （右侧卡片展示最近 8 个上传文件，先进先出）；其余按覆盖数展示真实标签，
 * 点真实标签联动「最近上传」区按该标签过滤。
 */
const view = useFileViewStore()

const candidates = ref<TagCandidate[]>([])
const loading = ref(false)

/**
 * 固定 7 行 × 2 列 = 14 格：第 1 格「最近」+ 最多 13 个真实标签。
 * 不足 14 个时空位留空（位置固定不跳动，也不拉伸按钮去填满）。
 */
const ROWS = 7
const COLS = 2
const MAX_TAGS = ROWS * COLS - 1

const shown = computed(() => candidates.value.slice(0, MAX_TAGS))

onMounted(async () => {
  loading.value = true
  try {
    const { data } = await listTagCandidates()
    candidates.value = data.data ?? []
  } catch {
    candidates.value = []
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="suggest-card">
    <div class="card-head">
      <h2 class="card-title">猜你需要的标签</h2>
      <span class="head-side">
        <span class="card-sub">{{ loading ? '加载中…' : `${shown.length} 个候选标签` }}</span>
      </span>
    </div>

    <!-- 最近 + 真实标签格：第 1 格固定「最近」，选中态 = 无标签（右侧展示最近 8 个上传） -->
    <div class="tag-grid">
      <button
        class="tag-cell"
        :class="{ 'is-active': view.activeTag === null }"
        title="查看最近上传的 8 个文件"
        @click="view.clearTag()"
      >
        最近
      </button>
      <button
        v-for="c in shown"
        :key="c.name"
        class="tag-cell"
        :class="{ 'is-active': view.activeTag === c.name }"
        :title="'看看含「' + c.name + '」的文件'"
        @click="view.toggleTag(c.name)"
      >
        <span class="tag-name">{{ c.name }}</span>
        <span v-if="c.fileCount > 0" class="tag-count">{{ c.fileCount }}</span>
      </button>
    </div>

    <!-- 空态：无候选标签 -->
    <div v-if="!loading && !candidates.length" class="suggest-empty">
      暂无标签，上传文件后 AI 会自动打标。
    </div>
  </section>
</template>

<style scoped>
.suggest-card {
  background: var(--ice);
  border-radius: var(--radius-card);
  padding: var(--pad);
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.card-title {
  color: var(--ink-peach);
}
.head-side {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.card-sub {
  color: rgb(var(--ink-peach-rgb) / 0.7);
}

/* 标签格：固定 7 行 × 2 列 = 14 个槽位（含恒在首格的「最近」）。
   槽位位置固定不跳动：标签不足时空槽留白，标签多余 13 个时只展示前 13 个；
   按钮用统一尺寸（垂直居中、不随行高拉伸变大），七行均分卡片高度上下铺开 */
.tag-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-template-rows: repeat(7, minmax(0, 1fr));
  gap: 8px 10px;
}
.tag-cell {
  min-width: 0;
  min-height: 0;
  align-self: center;
  width: 100%;
  height: 46px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  border-radius: var(--radius-inner);
  background: rgb(255 255 255 / 0.7);
  color: var(--ink-peach);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}
.tag-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-cell:hover {
  background: #fff;
}
/* 选中（正按该标签过滤）：深杏底白字 */
.tag-cell.is-active {
  background: var(--ink-peach);
  color: #fff;
}
.tag-count {
  flex: none;
  font-size: 11px;
  font-family: var(--font-mono);
  opacity: 0.75;
}
.suggest-empty {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 16px;
  text-align: center;
  font-size: 12.5px;
  color: rgb(var(--ink-peach-rgb) / 0.6);
}
</style>
