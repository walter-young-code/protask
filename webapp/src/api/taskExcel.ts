import { getBlob, post } from './request'
import type { TaskImportResultVO } from '@/types'

/**
 * 导出项目任务为 Excel(.xlsx)：固定列（标题 + 内置字段），
 * WBS 项目另含 WBS编码/里程碑/前置任务列。鉴权走请求头（getBlob）。
 */
export const exportTasksXlsx = (projectId: number) =>
  getBlob(`/v1/projects/${projectId}/export/tasks.xlsx`)

/** 下载导入模板（固定表头 + 示例行） */
export const downloadImportTemplate = (projectId: number) =>
  getBlob(`/v1/projects/${projectId}/import/template.xlsx`)

/** 导入 Excel 仅新建任务：errors 非空表示校验失败、未写入任何任务 */
export const importTasksXlsx = (projectId: number, file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return post<TaskImportResultVO>(`/v1/projects/${projectId}/import/tasks.xlsx`, formData)
}