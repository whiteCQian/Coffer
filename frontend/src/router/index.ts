import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layout/MainLayout.vue'
import FilesView from '@/views/FilesView.vue'
import GovernancePreviewView from '@/views/GovernancePreviewView.vue'
import HomeView from '@/views/HomeView.vue'
import OperationsView from '@/views/OperationsView.vue'
import SearchView from '@/views/SearchView.vue'
import SettingsView from '@/views/SettingsView.vue'
import AuthView from '@/views/AuthView.vue'
import AdminView from '@/views/AdminView.vue'
import ForbiddenView from '@/views/ForbiddenView.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: AuthView, meta: { public: true, title: '登录' } },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: 'admin', component: AdminView, meta: { admin: true, title: '管理控制台' } },
        { path: 'forbidden', component: ForbiddenView, meta: { shared: true, title: '无权访问' } },
        { path: '', name: 'home', component: HomeView, meta: { title: '首页' } },
        { path: 'files', name: 'files', component: FilesView, meta: { title: '全部文件' } },
        {
          path: 'governance/previews/:previewId',
          name: 'governance-preview',
          component: GovernancePreviewView,
          meta: { title: '整理预览' },
        },
        { path: 'operations', name: 'operations', component: OperationsView, meta: { title: '操作台账' } },
        { path: 'search', name: 'search', component: SearchView, meta: { title: '搜索' } },
        { path: 'settings', name: 'settings', component: SettingsView, meta: { title: '设置' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  try {
    await auth.bootstrap()
  } catch {
    if (to.path !== '/login') return { name: 'login', query: { redirect: to.fullPath } }
    return true
  }

  if (to.path === '/login') {
    return auth.user ? (auth.isAdmin ? '/admin' : '/') : true
  }
  if (!auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return '/forbidden'
  if (auth.isAdmin && !to.meta.admin && !to.meta.shared) return to.path === '/' ? '/admin' : '/forbidden'
  return true
})

export default router
