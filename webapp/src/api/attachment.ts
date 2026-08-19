import { del, get, post } from './request'

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
 * 为鉴权下载 URL 附加 token 参数：浏览器新标签页/下载链接无法携带 Authorization 头，
 * 后端 sa-token 支持从同名请求参数读取 token；公开静态地址原样返回。
 */
export function withAuthToken(url: string): string {
  if (!url.includes('/download')) return url
  const token = localStorage.getItem('protask_token')
  return token ? `${url}?Authorization=${encodeURIComponent(`Bearer ${token}`)}` : url
}

/** 查询任务附件列表 */
export const listAttachments = (taskId: number) => get<AttachmentVO[]>(`/v1/tasks/${taskId}/attachments`)

/** 上传附件到任务 */
export const uploadAttachment = (taskId: number, file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return post<AttachmentVO>(`/v1/tasks/${taskId}/attachments`, formData)
}

/** 删除附件（后端同步移除任务描述中的 Markdown 引用） */
export const deleteAttachment = (taskId: number, id: number) =>
  del<void>(`/v1/tasks/${taskId}/attachments/${id}`)
