<script setup lang="ts">
import { Menu, Search, ArrowRight } from '@element-plus/icons-vue'
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import '@/styles/home-task-expansion.css'
const props = defineProps<{ menuOpen: boolean }>()
const emit = defineEmits<{ (e: 'toggle-menu'): void; (e: 'close-menu'): void }>()
const auth = useAuthStore(), route = useRoute(), router = useRouter()
const query = ref(''), input = ref<HTMLInputElement>()
function search() { if (query.value.trim()) { emit('close-menu'); void router.push({ path: '/files', query: { keyword: query.value.trim() } }) } }
function shortcut(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close-menu')
  if (!auth.isAdmin && route.path !== '/files' && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); input.value?.focus() }
}
onMounted(() => window.addEventListener('keydown', shortcut))
onUnmounted(() => window.removeEventListener('keydown', shortcut))
</script>
<template>
  <header class="web-topbar">
    <button class="web-menu-toggle" :aria-expanded="props.menuOpen" aria-controls="web-navigation" :aria-label="props.menuOpen ? '关闭导航' : '打开导航'" @click="emit('toggle-menu')"><el-icon><Menu /></el-icon></button>
    <div class="web-breadcrumb"><span>{{ auth.isAdmin ? '管理空间' : '我的工作空间' }}</span><el-icon><ArrowRight /></el-icon><strong>{{ route.path === '/' ? '工作台' : route.path === '/search' ? 'AI 搜索' : route.path === '/operations' ? '最近操作' : route.meta.title }}</strong></div>
    <form v-if="!auth.isAdmin" class="web-global-search" role="search" @submit.prevent="search"><el-icon><Search /></el-icon><input ref="input" v-model="query" type="search" placeholder="搜索我的文件" aria-label="搜索我的文件" /><kbd>Ctrl K</kbd></form>
  </header>
</template>
<style scoped>
.web-topbar { display: flex; align-items: center; gap: 18px; height: 64px; padding: 0 30px; background: var(--panel); border-bottom: 1px solid var(--line); flex-shrink: 0; min-width: 0; }
.web-breadcrumb { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--text-3); white-space: nowrap; }.web-breadcrumb .el-icon { font-size: 9px; }.web-breadcrumb strong { color: var(--text-2); font-weight: 500; }
.web-global-search { margin-left: auto; display: flex; align-items: center; gap: 9px; max-width: 320px; width: 30%; border: 1px solid var(--line); background: var(--bg); border-radius: 7px; color: var(--text-3); padding: 6px 10px; }.web-global-search:focus-within { border-color: var(--accent); }
.web-global-search input { width: 100%; min-width: 0; border: 0; background: transparent; outline: none; color: var(--text-1); font-size: 11px; }.web-global-search input::placeholder { color: var(--text-3); }.web-global-search kbd { flex-shrink: 0; font-size: 9px; font-family: inherit; color: var(--text-3); padding: 1px 4px; border: 1px solid var(--line-strong); border-radius: 4px; }
.web-menu-toggle { display: none; background: transparent; color: var(--text-2); border: 0; width: 30px; height: 30px; font-size: 19px; }
@media (max-width: 850px) { .web-menu-toggle { display: grid; place-items: center; }.web-topbar { padding-inline: 16px; gap: 10px; }.web-global-search { width: 40%; }.web-breadcrumb > span, .web-breadcrumb .el-icon { display: none; } }
@media (max-width: 540px) { .web-topbar { height: 56px; }.web-global-search { flex: 1; width: auto; }.web-global-search kbd { display: none; } }
</style>
