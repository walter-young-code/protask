import { del, get, getBlob, patch, post } from './request'
import type { DependencyPatchRequest, DependencyRequest, DependencyVO } from '@/types'

/** 项目依赖列表（非 WBS 项目返回空列表） */
export const listDependencies = (projectId: number) =>
  get<DependencyVO[]>(`/v1/projects/${projectId}/dependencies`)

/** 创建依赖（FS/SS/FF/SF，后端拦截自依赖/重复/环，中英错误消息） */
export const createDependency = (projectId: number, data: DependencyRequest) =>
  post<DependencyVO>(`/v1/projects/${projectId}/dependencies`, data)

/** 修改依赖类型/lag */
export const updateDependency = (id: number, data: DependencyPatchRequest) =>
  patch<DependencyVO>(`/v1/dependencies/${id}`, data)

/** 删除依赖 */
export const deleteDependency = (id: number) => del<void>(`/v1/dependencies/${id}`)

/**
 * 导出 MS Project MSPDI(.xml)：含 OutlineLevel/WBS/Summary/Milestone/四类型链接+lag/负责人资源/进度。
 * 鉴权走请求头（getBlob），门禁拒绝时后端以 JSON 返回错误并由 getBlob 还原提示。
 */
export const exportProjectXml = (projectId: number) =>
  getBlob(`/v1/projects/${projectId}/export/project.xml`)