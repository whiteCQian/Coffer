import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layout/MainLayout.vue'
import FilesView from '@/views/FilesView.vue'
import GovernancePreviewView from '@/views/GovernancePreviewView.vue'
import HomeView from '@/views/HomeView.vue'
import OperationsView from '@/views/OperationsView.vue'
import SearchView from '@/views/SearchView.vue'
import SettingsView from '@/views/SettingsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: MainLayout,
      children: [
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

export default router
