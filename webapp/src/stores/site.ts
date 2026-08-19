import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as siteApi from '@/api/site'
import type { SiteSettingsRequest } from '@/types'

/** 站点品牌全局状态：应用名称、LOGO（未配置时为空，组件回退默认值） */
export const useSiteStore = defineStore('site', () => {
  const name = ref('')
  const logo = ref('')

  /** 拉取站点公开设置（应用启动时调用，失败静默保持默认品牌） */
  async function fetchSettings() {
    try {
      const result = await siteApi.getSiteSettings()
      name.value = result.name
      logo.value = result.logo || ''
    } catch {
      // 后端不可达时保持默认品牌
    }
  }

  /** 保存站点设置（仅系统管理员），成功后同步全局状态 */
  async function updateSettings(data: SiteSettingsRequest) {
    const result = await siteApi.updateSiteSettings(data)
    name.value = result.name
    logo.value = result.logo || ''
  }

  return { name, logo, fetchSettings, updateSettings }
})
