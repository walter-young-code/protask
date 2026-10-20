import { del, get, getBlob, post } from './request'
import type { PageResult } from '@/types'

/** 附件列表分页大小 */
export const ATTACHMENT_PAGE_SIZE = 20

/** 附件视图（与后端 AttachmentVO 对应） */
export interface AttachmentVO {
  id: number
  taskId: number
  originalName: string
  /** 可访问地址（图片为公开静态资源，非图片为鉴权下载接口） */
  url: string
  contentType: string
  size: number
  createdBy: number
  createdByName?: string
  createdAt: string
}

/**
 * 下载附件：通过 Authorization 请求头鉴权后以 Blob 保存，
 * 避免把 JWT 拼进 URL（浏览器历史/网关日志/Referer 泄露）。
 */
export async function downloadAttachment(url: string, filename: string): Promise<void> {
  const blob = await getBlob(url)
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(objectUrl)
}

/** 新标签页预览附件（PDF）：请求头鉴权后使用 object URL 打开 */
export async function previewAttachment(url: string): Promise<void> {
  const blob = await getBlob(url)
  const objectUrl = URL.createObjectURL(blob)
  window.open(objectUrl, '_blank', 'noopener')
  // 延迟释放，避免新标签页加载完成前 URL 失效
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
}

/** 分页查询任务附件列表（按上传时间倒序） */
export const listAttachments = (taskId: number, params?: { page?: number; size?: number }) =>
  get<PageResult<AttachmentVO>>(`/v1/tasks/${taskId}/attachments`, params as Record<string, unknown>)

/** 上传附件到任务 */
export const uploadAttachment = (taskId: number, file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return post<AttachmentVO>(`/v1/tasks/${taskId}/attachments`, formData)
}

/** 删除附件（后端同步移除任务描述中的 Markdown 引用） */
export const deleteAttachment = (taskId: number, id: number) =>
  del<void>(`/v1/tasks/${taskId}/attachments/${id}`)
