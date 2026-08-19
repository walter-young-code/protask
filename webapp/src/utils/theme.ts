import { ref } from 'vue'

const STORAGE_KEY = 'protask-theme'

/** 全局暗色模式状态（响应式），与 <html class="dark"> 及 localStorage 保持同步 */
export const isDark = ref(document.documentElement.classList.contains('dark'))

function apply(dark: boolean) {
  document.documentElement.classList.toggle('dark', dark)
  localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light')
}

/** 应用初始化时调用：读取持久化偏好并同步状态（index.html 内联脚本已先行应用，避免首屏闪烁） */
export function initTheme() {
  const dark = localStorage.getItem(STORAGE_KEY) === 'dark'
  isDark.value = dark
  apply(dark)
}

/** 切换明暗主题并持久化 */
export function toggleTheme() {
  isDark.value = !isDark.value
  apply(isDark.value)
}
