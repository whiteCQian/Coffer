// 主题切换：亮色（默认）/ 深色，通过 <html data-theme="dark"> 覆盖 CSS token。
// 选择持久化到 localStorage，下次启动沿用。

export type Theme = 'light' | 'dark'

const THEME_KEY = 'coffer-theme'

export function getTheme(): Theme {
  try {
    return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme)
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    /* 存储不可用时静默降级为会话内切换 */
  }
}

export function toggleTheme(): Theme {
  const next: Theme = getTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}
