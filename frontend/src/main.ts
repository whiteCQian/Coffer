import { createPinia } from 'pinia'
import { createApp } from 'vue'

import ElementPlus, { ElMessageBox, ElMessage, ElNotification } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { useFileViewStore } from './stores/fileView'
import { applyTheme, getTheme } from './theme'
import './styles/global.css'
import './styles/tokens.css'

// 首屏渲染前应用主题，避免刷新时闪烁
applyTheme(getTheme())

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')

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
