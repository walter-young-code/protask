import { createI18n } from 'vue-i18n'
import zhCN from '@/locales/zh-CN'
import enUS from '@/locales/en-US'

export type AppLocale = 'zh-CN' | 'en-US'

const LOCALE_KEY = 'protask_locale'

/** 优先读取本地保存的语言，否则按浏览器语言自动探测 */
function detectLocale(): AppLocale {
  const saved = localStorage.getItem(LOCALE_KEY)
  if (saved === 'zh-CN' || saved === 'en-US') {
    return saved
  }
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'en-US',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

/** 切换语言并持久化 */
export function setLocale(locale: AppLocale) {
  i18n.global.locale.value = locale
  localStorage.setItem(LOCALE_KEY, locale)
  document.documentElement.lang = locale
}

// 初始化 html lang 属性
document.documentElement.lang = i18n.global.locale.value

export default i18n
