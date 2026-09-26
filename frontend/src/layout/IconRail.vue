<script setup lang="ts">
import { FolderOpened, HomeFilled, List, Moon, Search, Setting, Sunny } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getTheme, toggleTheme } from '@/theme'
import { useAuthStore } from '@/stores/auth'
import { useFileViewStore } from '@/stores/fileView'

/** 导航表：点击 push，激活态按 path 前缀匹配 */
const NAV = [
  { path: '/', label: '首页', icon: HomeFilled, exact: true },
  { path: '/files', label: '全部文件', icon: FolderOpened },
  { path: '/operations', label: '操作台账', icon: List },
  { path: '/search', label: '搜索', icon: Search },
  { path: '/settings', label: '设置', icon: Setting },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const visibleNav = computed(() => auth.isAdmin ? [{ path: '/admin', label: '管理控制台', icon: Setting, exact: true }] : NAV)
const fileView = useFileViewStore()

const current = computed(() => route.path)
function isActive(nav: (typeof NAV)[number]) {
  return nav.exact ? current.value === nav.path : current.value.startsWith(nav.path)
}

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
  <nav class="icon-rail">
    <div class="rail-top">
      <div class="rail-logo" title="Coffer">智</div>
      <button
        v-for="n in visibleNav"
        :key="n.path"
        class="rail-icon"
        :class="{ 'is-active': isActive(n) }"
        :title="n.label"
        @click="router.push(n.path)"
      >
        <el-icon><component :is="n.icon" /></el-icon>
      </button>
    </div>
    <div class="rail-bottom">
      <button
        class="rail-theme"
        :title="isDark ? '切换到亮色主题' : '切换到深色主题'"
        @click="onToggleTheme"
      >
        <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
      </button>
      <button class="rail-avatar" :title="`${auth.user?.username || '用户'} · 退出登录`" @click="signOut">
        {{ auth.user?.username?.slice(0, 1).toUpperCase() || 'Q' }}
      </button>
    </div>
  </nav>
</template>

<style scoped>
.icon-rail {
  width: 64px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}
.rail-top {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.rail-bottom {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

/* Logo：主题强调色圆形印章 */
.rail-logo {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  margin-bottom: 10px;
  background: var(--accent);
  color: #fff;
  font-family: var(--font-display);
  font-size: 19px;
  border-radius: 50%;
  box-shadow: 0 2px 10px rgb(var(--accent-rgb) / 0.35);
}

/* 图标按钮：默认中性灰，悬停变深 + 弱化圆底，激活强调色 + 半透明底 */
.rail-icon {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--icon);
  font-size: 18px;
  cursor: pointer;
}
.rail-icon:hover {
  color: var(--text-1);
  background: var(--hover-bg);
}
.rail-icon.is-active {
  color: var(--accent);
  background: rgb(var(--accent-rgb) / 0.12);
}

/* 主题切换：头像上方 */
.rail-theme {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--icon);
  font-size: 18px;
  cursor: pointer;
}
.rail-theme:hover {
  color: var(--text-1);
  background: var(--hover-bg);
}

/* 底部头像 */
.rail-avatar {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--rail);
  border: 1px solid var(--line-strong);
  color: var(--text-2);
  font-size: 15px;
  cursor: pointer;
  font: inherit;
}
</style>
