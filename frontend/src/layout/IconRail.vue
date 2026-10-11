<script setup lang="ts">
import { FolderOpened, HomeFilled, List, Moon, Search, Setting, Sunny, SwitchButton, PriceTag } from '@element-plus/icons-vue'
import CofferBrand from '@/components/CofferBrand.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getTheme, toggleTheme } from '@/theme'
import { useAuthStore } from '@/stores/auth'
import { useFileViewStore } from '@/stores/fileView'
import { useWorkspaceStore } from '@/stores/workspace'
const props = defineProps<{ mobileOpen?: boolean }>()
const emit = defineEmits<{ (e: 'navigate'): void }>()
const workspace = useWorkspaceStore()

/** 导航表：点击 push，激活态按 path 前缀匹配 */
const NAV = [
  { path: '/', label: '工作台', icon: HomeFilled, exact: true },
  { path: '/files', label: '全部文件', icon: FolderOpened },
  { path: '/operations', label: '操作记录', icon: List },
  { path: '/search', label: 'AI 搜索', icon: Search },
  { path: '/settings', label: '设置', icon: Setting },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const visibleNav = computed(() => auth.isAdmin ? [{ path: '/admin', label: '管理控制台', icon: Setting, exact: true }] : NAV)
const fileView = useFileViewStore()

const current = computed(() => route.path)
function isActive(nav: (typeof NAV)[number]) {
  if (route.query.tag && nav.path === '/files') return false
  return nav.exact ? current.value === nav.path : current.value.startsWith(nav.path)
}
function navigate(path: string, tag?: string) { emit('navigate'); void router.push({ path, query: tag ? { tag } : {} }) }
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { if (!auth.isAdmin) { void workspace.refresh(); timer = setInterval(() => void workspace.refresh(), 15000) } })
onUnmounted(() => { clearInterval(timer); workspace.clear() })

/** 主题：亮色（默认）/ 深色，头像上方一键切换 */
const theme = ref<'light' | 'dark'>(getTheme())
const isDark = computed(() => theme.value === 'dark')
function onToggleTheme() {
  theme.value = toggleTheme()
}

async function signOut() {
  try {
    await auth.signOut()
  } finally {
    fileView.clearTag()
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <aside id="web-navigation" class="icon-rail" :class="{ 'mobile-open': props.mobileOpen }">
    <RouterLink :to="auth.isAdmin ? '/admin' : '/'" class="web-brand" @click="emit('navigate')"><CofferBrand subtitle="智能文件工作空间"/></RouterLink>
    <div class="web-nav-label">{{ auth.isAdmin ? '管理空间' : '我的工作空间' }}</div>
    <nav aria-label="Web 主导航" class="web-nav">
      <button v-for="item in visibleNav" :key="item.path" class="rail-icon" :class="{ 'is-active': isActive(item) }" :title="item.label" :aria-current="isActive(item) ? 'page' : undefined" @click="navigate(item.path)"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></button>
    </nav>
    <section v-if="!auth.isAdmin && workspace.tags.length" class="web-tags">
      <div class="web-nav-label">常用标签 <span>{{ workspace.tags.length }}</span></div>
      <button v-for="tag in workspace.tags" :key="tag.name" class="rail-icon" :class="{ 'is-active': route.path === '/files' && route.query.tag === tag.name }" @click="navigate('/files', tag.name)"><el-icon><PriceTag /></el-icon><span>{{ tag.name }}</span><small>{{ tag.fileCount }}</small></button>
    </section>
    <div class="web-sidebar-bottom">
      <button class="rail-theme" :title="isDark ? '切换到亮色主题' : '切换到深色主题'" :aria-label="isDark ? '切换到亮色主题' : '切换到深色主题'" @click="onToggleTheme"><el-icon><Sunny v-if="isDark" /><Moon v-else /></el-icon><span>{{ isDark ? '亮色主题' : '深色主题' }}</span></button>
      <div class="web-account"><span class="web-avatar">{{ auth.user?.username?.slice(0, 1).toUpperCase() || 'U' }}</span><span class="web-account-name"><strong>{{ auth.user?.username || '用户' }}</strong><small>{{ auth.isAdmin ? '管理员账号' : '独立文件空间' }}</small></span><button class="rail-avatar" :title="`${auth.user?.username || '用户'} · 退出登录`" aria-label="退出登录" @click="signOut"><el-icon><SwitchButton /></el-icon></button></div>
    </div>
  </aside>
</template>

<style scoped>
.icon-rail { display: flex; flex-direction: column; padding: 27px 16px 16px; min-height: 0; background: var(--rail); border-right: 1px solid var(--line); }
.web-brand { display: flex; align-items: center; gap: 11px; padding: 0 10px 27px; color: var(--text-1); text-decoration: none; }
.web-brand strong { display: block; font-size: 22px; line-height: 1.3; letter-spacing: -.5px; }.web-brand small { font-size: 10px; color: var(--text-2); }
.web-nav-label { display: flex; justify-content: space-between; padding: 0 12px; font-size: 11px; color: var(--text-3); margin: 12px 0 10px; }
.web-nav { display: flex; flex-direction: column; gap: 4px; }
.rail-icon, .rail-theme { width: 100%; display: flex; align-items: center; gap: 11px; padding: 10px 12px; border: 0; background: transparent; color: var(--text-2); border-radius: 7px; font-size: 13px; font-family: inherit; text-align: left; cursor: pointer; }
.rail-icon .el-icon, .rail-theme .el-icon { flex-shrink: 0; font-size: 17px; }.rail-icon > span:not(.el-icon) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-icon:hover, .rail-theme:hover { background: var(--hover-bg); color: var(--text-1); }.rail-icon.is-active { background: rgb(var(--accent-rgb) / .1); color: var(--accent); font-weight: 600; }
.rail-icon small { margin-left: auto; font-size: 11px; color: var(--text-3); }
.web-tags { margin-top: 20px; min-height: 0; overflow-y: auto; }.web-tags .rail-icon { padding-block: 8px; }
.web-sidebar-bottom { margin-top: auto; padding-top: 20px; }.rail-theme { font-size: 12px; }
.web-account { display: flex; align-items: center; gap: 9px; padding: 16px 6px 0; margin-top: 12px; border-top: 1px solid var(--line); }
.web-avatar { width: 32px; height: 32px; display: grid; place-items: center; font-size: 12px; font-weight: 600; color: var(--accent); border-radius: 9px; background: rgb(var(--accent-rgb) / .1); }
.web-account-name { flex: 1; min-width: 0; }.web-account-name strong { display: block; font-size: 12px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; }.web-account-name small { display: block; font-size: 10px; color: var(--text-3); margin-top: 1px; }
.rail-avatar { border: 0; background: transparent; color: var(--text-3); width: 28px; height: 28px; display: grid; place-items: center; cursor: pointer; border-radius: 6px; }.rail-avatar:hover { background: var(--hover-bg); color: var(--danger); }
@media (max-width: 850px) { .icon-rail { position: fixed; width: 224px; inset: 0 auto 0 0; z-index: 100; transform: translateX(-100%); visibility: hidden; transition: transform .2s ease, visibility .2s ease; }.icon-rail.mobile-open { transform: none; visibility: visible; } }
</style>
