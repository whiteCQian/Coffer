<script setup lang="ts">
// 应用外壳：左侧 64px 图标导航 + 右侧页面视口（路由出口）
import IconRail from './IconRail.vue'
import DesktopSidebar from './DesktopSidebar.vue'
import { useAuthStore } from '@/stores/auth'
import WebTopbar from './WebTopbar.vue'
import PaneResizeHandle from '@/components/PaneResizeHandle.vue'
import { usePaneWidth, useWindowWidth } from '@/composables/usePaneWidth'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
const auth = useAuthStore()
const mobileMenu = ref(false), route = useRoute()
const windowWidth=useWindowWidth()
const sidebarMax=computed(()=>Math.max(180,Math.min(360,windowWidth.value-640)))
const {width:sidebarWidth,resize:resizeSidebar,commit:saveSidebar,reset:resetSidebar}=usePaneWidth('coffer:sidebar-width',220,180,sidebarMax)
watch(() => route.fullPath, () => { mobileMenu.value = false })
</script>

<template>
  <div class="app-shell" :class="auth.isPersonal ? 'desktop-shell' : 'web-shell'" :style="auth.isPersonal?{'--desktop-sidebar-width':`${sidebarWidth}px`}:undefined">
    <DesktopSidebar v-if="auth.isPersonal" />
    <IconRail v-else :mobile-open="mobileMenu" @navigate="mobileMenu = false" />
    <PaneResizeHandle v-if="auth.isPersonal" label="调整主区域左边界" :size="sidebarWidth" :min="180" :max="sidebarMax" @resize="resizeSidebar" @commit="saveSidebar" @reset="resetSidebar"/>
    <div class="main-workspace"><WebTopbar v-if="!auth.isPersonal" :menu-open="mobileMenu" @toggle-menu="mobileMenu = !mobileMenu" @close-menu="mobileMenu = false" /><main class="page-viewport"><router-view /></main></div>
    <button v-if="!auth.isPersonal && mobileMenu" class="web-menu-backdrop" aria-label="关闭导航" @click="mobileMenu = false"></button>
  </div>
</template>

<style scoped>
.app-shell {
  height: 100vh;
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: var(--gap);
  padding: var(--gap);
  background: var(--bg);
  overflow: hidden;
}

/* 页面视口：交给各页面自行组织布局与滚动 */
.page-viewport {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.desktop-shell { grid-template-columns: var(--desktop-sidebar-width,220px) 8px minmax(0, 1fr); gap: 0; padding: 0; }
.main-workspace { display: flex; flex-direction: column; min-width: 0; min-height: 0; }.main-workspace .page-viewport { flex: 1; }
.web-shell { grid-template-columns: 224px minmax(0, 1fr); gap: 0; padding: 0; }
.web-shell .page-viewport { overflow-y: auto; }
.web-menu-backdrop { display: none; }
@media (max-width: 850px) { .web-shell { grid-template-columns: minmax(0, 1fr); }.web-menu-backdrop { display: block; position: fixed; inset: 0; z-index: 90; background: #14293666; border: 0; } }
</style>
