// 主题切换：亮色（默认）/ 深色，通过 <html data-theme="dark"> 覆盖 CSS token。
// 网页保存在 localStorage；桌面额外保存在固定的 shell 配置中。

import { desktopBridge } from '@/api/desktop'

export type Theme = 'light' | 'dark'

const THEME_KEY = 'coffer-theme'

export function getTheme(): Theme {
  try {
    return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

export function applyTheme(theme: Theme, persist = true): void {
  document.documentElement.setAttribute('data-theme', theme)
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    /* 存储不可用时静默降级为会话内切换 */
  }
  if(persist)void desktopBridge?.saveTheme?.(theme).catch(()=>{})
}

export async function initializeTheme(): Promise<void> {
  const local = getTheme()
  applyTheme(local, false)
  try {
    const saved = await desktopBridge?.getTheme?.()
    applyTheme(saved === 'dark' || saved === 'light' ? saved : local)
  } catch { /* 保留本地主题 */ }
}

export function toggleTheme(): Theme {
  const next: Theme = getTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}
