<script setup lang="ts">
import { FolderOpened, Download, CircleCheck, List, Search, Setting, Moon, Sunny } from '@element-plus/icons-vue'
import CofferBrand from '@/components/CofferBrand.vue'
import SidebarTags from '@/components/SidebarTags.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'
import { getTheme, toggleTheme } from '@/theme'

const route = useRoute(), workspace = useWorkspaceStore()
const theme = ref(getTheme())
const nav = [
  { label: '全部文件', path: '/', icon: FolderOpened, view: '' },
  { label: '收件箱', path: '/', icon: Download, view: 'inbox' },
  { label: '待确认', path: '/', icon: CircleCheck, view: 'pending' },
  { label: '最近操作', path: '/operations', icon: List },
  { label: 'AI 搜索', path: '/search', icon: Search },
]
const selectedTag = computed(() => typeof route.query.tag === 'string' ? route.query.tag : '')
function active(item: typeof nav[number]) {
  if (item.path === '/') return (route.path === '/' || route.path === '/files') && !selectedTag.value && String(route.query.view ?? '') === item.view
  return route.path.startsWith(item.path)
}
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { void workspace.refresh(); timer = setInterval(() => void workspace.refresh(), 10000) })
onUnmounted(() => { clearInterval(timer); workspace.clear() })
</script>

<template>
  <aside class="desktop-sidebar">
    <RouterLink to="/" class="desktop-brand" aria-label="Coffer 文件库">
      <CofferBrand subtitle="你的智能文件管家"/>
    </RouterLink>
    <div class="sidebar-section-label">个人文件库</div>
    <nav class="desktop-nav" aria-label="主导航">
      <RouterLink v-for="item in nav" :key="item.label" :to="{ path: item.path, query: item.view ? { view: item.view } : {} }"
        class="desktop-nav-item" :class="{ active: active(item) }" :aria-current="active(item) ? 'page' : undefined">
        <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        <span v-if="item.view === 'pending' && workspace.overview?.pendingConfirmCount" class="nav-count">{{ workspace.overview.pendingConfirmCount }}</span>
      </RouterLink>
    </nav>
    <SidebarTags class="sidebar-tags" file-path="/" />
    <div class="sidebar-bottom">
      <div class="library-local"><span class="local-dot"></span><span>本机个人文件库<small>文件保存在你的设备上</small></span></div>
      <div class="sidebar-preferences">
        <RouterLink to="/settings" class="desktop-nav-item" :class="{ active: route.path === '/settings' }"><el-icon><Setting /></el-icon><span>设置</span></RouterLink>
        <button class="theme-toggle" :title="theme === 'dark' ? '切换到亮色主题' : '切换到深色主题'" :aria-label="theme === 'dark' ? '切换到亮色主题' : '切换到深色主题'" @click="theme = toggleTheme()"><el-icon><component :is="theme === 'dark' ? Sunny : Moon" /></el-icon></button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.desktop-sidebar { display: flex; flex-direction: column; min-height: 0; padding: 28px 16px 16px; background: var(--rail); border-right: 1px solid var(--line); }
.desktop-brand { display: flex; gap: 12px; align-items: center; padding: 0 8px 30px; text-decoration: none; color: var(--text-1); }
.sidebar-section-label { display: flex; justify-content: space-between; padding: 0 12px; margin: 10px 0; font-size: 11px; letter-spacing: .06em; color: var(--text-3); }
.desktop-nav { display: flex; flex-direction: column; gap: 4px; }
.desktop-nav-item { display: flex; align-items: center; gap: 11px; padding: 10px 12px; text-decoration: none; border-radius: 7px; color: var(--text-2); font-size: 13px; min-width: 0; }
.desktop-nav-item .el-icon { font-size: 17px; flex-shrink: 0; }
.desktop-nav-item:hover { background: var(--hover-bg); color: var(--text-1); }
.desktop-nav-item.active { background: rgb(var(--accent-rgb) / .1); color: var(--accent); font-weight: 600; }
.nav-count { margin-left: auto; font-size: 11px; color: var(--text-3); font-variant-numeric: tabular-nums; }
.active .nav-count { color: var(--accent); }
.sidebar-tags { margin-top: 25px; min-height: 0; overflow-y: auto; }
.sidebar-bottom { margin-top: auto; padding-top: 24px; }
.library-local { display: flex; align-items: flex-start; gap: 9px; padding: 14px 12px; font-size: 12px; color: var(--text-2); border-top: 1px solid var(--line); }
.library-local small { display: block; color: var(--text-3); font-size: 10px; margin-top: 3px; }
.local-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); margin-top: 7px; }
.sidebar-preferences { display: flex; align-items: center; }
.sidebar-preferences a { flex: 1; }
.theme-toggle { display: grid; place-items: center; width: 34px; height: 34px; background: transparent; border: 0; color: var(--text-2); cursor: pointer; border-radius: 7px; }
.theme-toggle:hover { background: var(--hover-bg); }
@media (max-width: 1100px) { .desktop-sidebar { padding-inline: 10px; } }
</style>
