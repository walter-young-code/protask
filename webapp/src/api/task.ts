import { del, get, post, put } from './request'
import type { DashboardVO, TaskRequest, TaskVO } from '@/types'

/** 跨项目仪表盘：当前用户可见项目的任务统计与分类列表 */
export const getDashboard = () => get<DashboardVO>('/v1/dashboard')

/** 查询项目下的任务列表 */
export const listTasks = (projectId: number) => get<TaskVO[]>(`/v1/projects/${projectId}/tasks`)

/** 全局搜索当前用户可见项目内的任务 */
export const searchTasks = (keyword: string) =>
  get<TaskVO[]>('/v1/tasks/search', { keyword })

/** 创建任务 */
export const createTask = (projectId: number, data: TaskRequest) =>
  post<TaskVO>(`/v1/projects/${projectId}/tasks`, data)

/** 复制任务（同项目内，标题自动追加副本后缀） */
export const copyTask = (id: number) => post<TaskVO>(`/v1/tasks/${id}/copy`)

/** 更新任务 */
export const updateTask = (id: number, data: TaskRequest) => put<TaskVO>(`/v1/tasks/${id}`, data)

/** 删除任务 */
export const deleteTask = (id: number) => del<void>(`/v1/tasks/${id}`)
