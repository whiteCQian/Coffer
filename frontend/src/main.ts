import { createPinia } from 'pinia'
import { createApp } from 'vue'

import ElementPlus, { ElMessageBox, ElMessage, ElNotification } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { useFileViewStore } from './stores/fileView'
import { initializeTheme } from './theme'
import './styles/global.css'
import './styles/tokens.css'
import './styles/desktop.css'
import './styles/web.css'
import './styles/web-content-layout.css'
import './styles/web-home-viewport.css'
import './styles/web-archive-actions.css'
import './styles/web-pane-layout.css'
import './styles/chat-expand-controls.css'
import { installRecentUploadScrollbar } from './utils/recentUploadScrollbar'

installRecentUploadScrollbar()

// 首屏渲染前应用主题，避免刷新时闪烁
const themeReady = initializeTheme()

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

void themeReady.then(() => app.mount('#app'))

window.addEventListener('coffer:auth-expired', () => {
  useAuthStore(pinia).clear()
  useFileViewStore(pinia).clearTag()
  void router.replace({ name: 'login', query: { reason: 'expired' } })
})
window.addEventListener('coffer:identity-cleared', () => {
  useFileViewStore(pinia).clearTag()
  ElMessageBox.close(); ElMessage.closeAll(); ElNotification.closeAll()
})
window.addEventListener('storage', (event) => {
  if (event.key === 'coffer:conversations-change') useAuthStore(pinia).resetWorkspace()
  if (event.key === 'coffer:identity-change') {
    useAuthStore(pinia).clear()
    void router.replace({ name: 'login', query: { reason: 'changed' } })
  }
})
window.addEventListener('coffer:conversations-deleted', () => {
  useAuthStore(pinia).resetWorkspace()
  localStorage.setItem('coffer:conversations-change', String(Date.now()))
})
