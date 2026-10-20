import { del, get, post, put } from './request'
import type {
  AdminUserCreateRequest,
  AdminUserQuery,
  AdminUserUpdateRequest,
  AdminUserVO,
  PageResult,
  ProjectCleanupVO,
  ProjectTemplateUpdateRequest,
  ProjectTemplateVO,
  ProjectVO,
  ResetPasswordRequest,
  StorageCleanupVO,
  StorageStatsVO,
  TeamRequest,
  TeamVO,
} from '@/types'

/** 分页查询用户（关键字 + 状态过滤，仅系统管理员） */
export const listAdminUsers = (params: AdminUserQuery) =>
  get<PageResult<AdminUserVO>>('/v1/admin/users', params as Record<string, unknown>)

/** 创建用户 */
export const createAdminUser = (data: AdminUserCreateRequest) =>
  post<AdminUserVO>('/v1/admin/users', data)

/** 更新用户（昵称/邮箱/角色/状态） */
export const updateAdminUser = (id: number, data: AdminUserUpdateRequest) =>
  put<AdminUserVO>(`/v1/admin/users/${id}`, data)

/** 重置密码 */
export const resetAdminUserPassword = (id: number, data: ResetPasswordRequest) =>
  put<void>(`/v1/admin/users/${id}/password`, data)

/** 注销用户（不可逆）：可指定项目接管人，历史数据保留 */
export const deleteAdminUser = (id: number, data?: { takeoverUserId?: number }) =>
  del<void>(`/v1/admin/users/${id}`, data ?? {})

/** 分页查询团队（名称模糊匹配 + 成员数，仅系统管理员） */
export const listAdminTeams = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<TeamVO>>('/v1/admin/teams', params as Record<string, unknown>)

/** 全量团队列表（含成员数），供用户管理下拉选择 */
export const listAllTeams = () => get<TeamVO[]>('/v1/admin/teams/all')

/** 创建团队 */
export const createAdminTeam = (data: TeamRequest) => post<TeamVO>('/v1/admin/teams', data)

/** 重命名团队 */
export const updateAdminTeam = (id: number, data: TeamRequest) =>
  put<TeamVO>(`/v1/admin/teams/${id}`, data)

/** 删除团队（团队下用户 team_id 置空） */
export const deleteAdminTeam = (id: number) => del<void>(`/v1/admin/teams/${id}`)

/** 分页查询模板（名称模糊匹配，仅系统管理员） */
export const listAdminTemplates = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<ProjectTemplateVO>>('/v1/admin/templates', params as Record<string, unknown>)

/** 更新模板（名称/描述/图标） */
export const updateAdminTemplate = (id: number, data: ProjectTemplateUpdateRequest) =>
  put<ProjectTemplateVO>(`/v1/admin/templates/${id}`, data)

/** 删除模板 */
export const deleteAdminTemplate = (id: number) => del<void>(`/v1/admin/templates/${id}`)

/** 分页查询归档项目（名称模糊匹配，仅系统管理员） */
export const listArchivedProjects = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<ProjectVO>>('/v1/admin/projects/archived', params as Record<string, unknown>)

/** 恢复归档项目（恢复正常状态，重新出现在成员侧边栏） */
export const restoreArchivedProject = (id: number) => post<ProjectVO>(`/v1/admin/projects/${id}/restore`)

/** 删除归档项目（软删除，不可恢复） */
export const deleteArchivedProject = (id: number) => del<void>(`/v1/admin/projects/${id}`)

/** 统计指定日期前（含当天）已软删的项目及关联数据量（仅系统管理员，不执行删除） */
export const statDeletedProjects = (before: string) =>
  get<ProjectCleanupVO>('/v1/admin/projects/deleted/stat', { before })

/** 物理清理指定日期前（含当天）已软删的项目：级联删除块/附件/成员/通知（仅系统管理员） */
export const cleanupDeletedProjects = (before: string) =>
  del<ProjectCleanupVO>(`/v1/admin/projects/deleted?before=${encodeURIComponent(before)}`)

/** 存储用量与孤儿图片统计（仅系统管理员） */
export const getStorageStats = () => get<StorageStatsVO>('/v1/admin/storage')

/** 清理无引用且超过保留期的孤儿图片（仅系统管理员） */
export const cleanupOrphanImages = () => del<StorageCleanupVO>('/v1/admin/storage/orphans')
