import { get, post, put } from './request'
import type { SiteSettingsRequest, SiteSettingsVO } from '@/types'

/** 站点公开设置（应用名称/LOGO，无需登录） */
export const getSiteSettings = () => get<SiteSettingsVO>('/v1/site/settings')

/** 更新站点设置（仅系统管理员） */
export const updateSiteSettings = (data: SiteSettingsRequest) =>
  put<SiteSettingsVO>('/v1/site/settings', data)

/** 上传站点 LOGO 图片，返回公开访问 URL */
export const uploadSiteLogo = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return post<{ url: string }>('/v1/upload/image', formData)
}
