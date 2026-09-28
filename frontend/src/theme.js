import { reactive } from 'vue'

// 明暗主题：跟随系统首次进入，之后以本地记忆为准。
const STORAGE_KEY = 'campusclaw-theme'

function initialMode() {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'dark' || stored === 'light') return stored
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const themeState = reactive({ mode: initialMode() })

export function applyTheme() {
  document.documentElement.dataset.theme = themeState.mode
}

export function toggleTheme() {
  themeState.mode = themeState.mode === 'dark' ? 'light' : 'dark'
  localStorage.setItem(STORAGE_KEY, themeState.mode)
  applyTheme()
}

applyTheme()
