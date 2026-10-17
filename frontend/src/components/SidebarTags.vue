<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { PriceTag } from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useRecentFileTagsStore } from '@/stores/recentFileTags'

const props = defineProps<{ filePath: string }>()
const emit = defineEmits<{ (e: 'navigate'): void }>()
const workspace = useWorkspaceStore(), route = useRoute(), recent = useRecentFileTagsStore()
const commonTags = computed(() => recent.names.flatMap(name => {
  const tag = workspace.tags.find(item => item.name === name)
  return tag ? [tag] : []
}))
const allTagsOpen = ref(false), query = ref('')
const filteredTags = computed(() => {
  const text = query.value.trim().toLocaleLowerCase()
  return text ? workspace.tags.filter(tag => tag.name.toLocaleLowerCase().includes(text)) : workspace.tags
})
function active(name: string) {
  return (route.path === props.filePath || route.path === '/files') && route.query.tag === name
}
function openAllTags() { query.value = ''; allTagsOpen.value = true }
function chooseTag() { allTagsOpen.value = false; emit('navigate') }
</script>

<template>
  <section v-if="workspace.tags.length || commonTags.length" class="sidebar-tag-list">
    <div class="tag-list-heading">常用标签 <span>{{ commonTags.length }}</span></div>
    <nav class="common-tag-links" aria-label="按标签浏览">
      <RouterLink v-for="tag in commonTags" :key="tag.name" class="tag-link" :class="{ active: active(tag.name) }"
        :to="{ path: props.filePath, query: { tag: tag.name } }" :aria-current="active(tag.name) ? 'page' : undefined"
        :title="tag.name" @click="emit('navigate')">
        <el-icon><PriceTag /></el-icon><span class="tag-name">{{ tag.name }}</span><span class="tag-count">{{ tag.fileCount }}</span>
      </RouterLink>
    </nav>
    <p v-if="!commonTags.length" class="no-recent-tags">打开文件后，其标签会显示在这里</p>
    <button v-if="workspace.tags.length" class="all-tags-button" aria-haspopup="dialog" @click="openAllTags">
      查看全部标签 <span>{{ workspace.tags.length }}</span>
    </button>
    <el-dialog v-model="allTagsOpen" class="all-tags-dialog" title="全部标签" width="min(480px, calc(100vw - 32px))" append-to-body destroy-on-close>
      <el-input v-model="query" placeholder="搜索标签" aria-label="搜索全部标签" clearable />
      <nav v-if="filteredTags.length" class="all-tag-links" aria-label="全部标签">
        <RouterLink v-for="tag in filteredTags" :key="tag.name" class="tag-link" :class="{ active: active(tag.name) }"
          :to="{ path: props.filePath, query: { tag: tag.name } }" :aria-current="active(tag.name) ? 'page' : undefined"
          :title="tag.name" @click="chooseTag">
          <el-icon><PriceTag /></el-icon><span class="tag-name">{{ tag.name }}</span><span class="tag-count">{{ tag.fileCount }}</span>
        </RouterLink>
      </nav>
      <p v-else class="no-matching-tags" role="status">没有匹配的标签</p>
    </el-dialog>
  </section>
</template>

<style scoped>
.tag-list-heading { display: flex; justify-content: space-between; padding: 0 12px; margin: 10px 0; color: var(--text-3); font-size: 11px; }
.common-tag-links { display: flex; flex-direction: column; gap: 2px; }
.tag-link { display: flex; align-items: center; gap: 11px; padding: 8px 12px; min-width: 0; border-radius: 7px; color: var(--text-2); font-size: 13px; text-decoration: none; }
.tag-link .el-icon { font-size: 16px; flex-shrink: 0; }
.tag-link:hover, .all-tags-button:hover { background: var(--hover-bg); color: var(--text-1); }
.tag-link.active { background: rgb(var(--accent-rgb) / .1); color: var(--accent); font-weight: 600; }
.tag-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag-count { flex-shrink: 0; margin-left: auto; color: var(--text-3); font-size: 11px; font-variant-numeric: tabular-nums; }
.active .tag-count { color: var(--accent); }
.all-tags-button { display: flex; justify-content: space-between; width: 100%; margin-top: 4px; padding: 8px 12px; border: 0; border-radius: 7px; background: transparent; color: var(--accent); font-size: 12px; font-family: inherit; cursor: pointer; }
.all-tags-button span { color: var(--text-3); }
.all-tag-links { max-height: 50vh; margin-top: 12px; overflow-y: auto; }
.no-matching-tags { color: var(--text-3); text-align: center; margin: 24px 0; }
.no-recent-tags { padding: 0 12px; color: var(--text-3); font-size: 11px; line-height: 1.6; }
:global(.all-tags-dialog) { --el-bg-color: var(--panel); --el-fill-color-blank: var(--panel-3); --el-text-color-primary: var(--text-1); --el-text-color-regular: var(--text-2); --el-text-color-placeholder: var(--text-3); --el-border-color: var(--line-strong); }
</style>
