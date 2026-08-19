import { del, get, post, put } from './request'
import type {
  CalendarSubscriptionVO,
  CardProperty,
  ProjectCopyRequest,
  ProjectMemberRequest,
  ProjectMemberVO,
  ProjectRequest,
  ProjectTemplateRequest,
  ProjectTemplateVO,
  ProjectToTemplateRequest,
  ProjectVO,
  ShareVO,
} from '@/types'

/** 查询我参与的项目列表 */
export const listMyProjects = () => get<ProjectVO[]>('/v1/projects')

/** 创建项目 */
export const createProject = (data: ProjectRequest) => post<ProjectVO>('/v1/projects', data)

/** 查询项目模板库 */
export const listTemplates = () => get<ProjectTemplateVO[]>('/v1/templates')

/** 从模板创建项目 */
export const createProjectFromTemplate = (data: ProjectTemplateRequest) =>
  post<ProjectVO>('/v1/projects/from-template', data)

/** 将现有项目转为模板（复制项目结构到模板库，原项目不受影响） */
export const convertProjectToTemplate = (id: number, data?: ProjectToTemplateRequest) =>
  post<ProjectTemplateVO>(`/v1/projects/${id}/to-template`, data ?? {})

/** 复制项目（克隆内容到新项目，复制者成为新项目负责人） */
export const copyProject = (id: number, data?: ProjectCopyRequest) =>
  post<ProjectVO>(`/v1/projects/${id}/copy`, data ?? {})

/** 查询项目详情 */
export const getProject = (id: number) => get<ProjectVO>(`/v1/projects/${id}`)

/** 更新项目 */
export const updateProject = (id: number, data: ProjectRequest) =>
  put<ProjectVO>(`/v1/projects/${id}`, data)

/** 设置项目默认视图（viewId 为 null 时清除默认视图） */
export const setDefaultView = (id: number, viewId: number | null) =>
  put<ProjectVO>(`/v1/projects/${id}/default-view`, { viewId })

/** 删除项目（软删除） */
export const deleteProject = (id: number) => del<void>(`/v1/projects/${id}`)

/** 归档项目（归档后从侧边栏移除，仅管理后台可恢复/删除） */
export const archiveProject = (id: number) => post<ProjectVO>(`/v1/projects/${id}/archive`)

/** 更新项目卡片动态属性模板（自定义属性整体替换，内置属性由后端保留） */
export const updateCardProperties = (id: number, properties: CardProperty[]) =>
  put<ProjectVO>(`/v1/projects/${id}/card-properties`, { properties })

/** 查询项目成员列表 */
export const listMembers = (id: number) => get<ProjectMemberVO[]>(`/v1/projects/${id}/members`)

/** 添加项目成员 */
export const addMember = (id: number, data: ProjectMemberRequest) =>
  post<ProjectMemberVO>(`/v1/projects/${id}/members`, data)

/** 变更项目成员角色 */
export const updateMemberRole = (id: number, data: ProjectMemberRequest) =>
  put<ProjectMemberVO>(`/v1/projects/${id}/members`, data)

/** 移除项目成员 */
export const removeMember = (id: number, userId: number) =>
  del<void>(`/v1/projects/${id}/members/${userId}`)

/** 开启项目分享（仅公开项目，需项目 admin） */
export const createShare = (id: number) => post<ShareVO>(`/v1/projects/${id}/share`)

/** 关闭项目分享（旧链接立即失效，需项目 admin） */
export const disableShare = (id: number) => del<ShareVO>(`/v1/projects/${id}/share`)

/** 查询项目当前分享状态（需项目 admin） */
export const getShare = (id: number) => get<ShareVO>(`/v1/projects/${id}/share`)

/** 通过分享链接解析项目（shareId 本身即访问凭证） */
export const resolveShare = (shareId: string) => get<ProjectVO>(`/v1/projects/share/${shareId}`)

/** 开启日历订阅：生成 ICS 订阅地址（重复开启复用现有凭证，需项目 admin） */
export const enableCalendarSubscription = (id: number) =>
  post<CalendarSubscriptionVO>(`/v1/projects/${id}/calendar-subscription`)

/** 重新生成日历订阅凭证（旧地址立即失效，需项目 admin） */
export const regenerateCalendarSubscription = (id: number) =>
  post<CalendarSubscriptionVO>(`/v1/projects/${id}/calendar-subscription/regenerate`)

/** 关闭日历订阅（旧地址立即失效，需项目 admin） */
export const disableCalendarSubscription = (id: number) =>
  del<CalendarSubscriptionVO>(`/v1/projects/${id}/calendar-subscription`)

/** 查询日历订阅状态（需项目 admin） */
export const getCalendarSubscription = (id: number) =>
  get<CalendarSubscriptionVO>(`/v1/projects/${id}/calendar-subscription`)
