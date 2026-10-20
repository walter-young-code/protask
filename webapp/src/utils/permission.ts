import type { CardProperty, ProjectRole, ProjectVO } from '@/types'

/**
 * 项目角色权限判定（与后端 PermissionService 的角色-权限矩阵保持一致）。
 * 角色等级：viewer < commenter < editor < admin
 */
const ROLE_LEVEL: Record<ProjectRole, number> = {
  viewer: 1,
  commenter: 2,
  editor: 3,
  admin: 4,
}

const levelOf = (role?: string): number =>
  role && role in ROLE_LEVEL ? ROLE_LEVEL[role as ProjectRole] : 0

/** 当前角色是否不低于指定角色 */
export const hasRole = (role: string | undefined, min: ProjectRole): boolean =>
  levelOf(role) >= ROLE_LEVEL[min]

/** 可评论任务卡片 */
export const canComment = (project?: ProjectVO): boolean => hasRole(project?.myRole, 'commenter')

/** 可增删改任务/内容块 */
export const canManageCards = (project?: ProjectVO): boolean => hasRole(project?.myRole, 'editor')

/** 可管理项目卡片动态属性定义（与后端 MANAGE_PROJECT_PROPERTIES 矩阵一致） */
export const canManageProperties = (project?: ProjectVO): boolean =>
  hasRole(project?.myRole, 'editor')

/** 可管理项目成员与角色 */
export const canManageRoles = (project?: ProjectVO): boolean => hasRole(project?.myRole, 'admin')

/** 可删除项目 / 修改项目信息 */
export const isAdmin = (project?: ProjectVO): boolean => hasRole(project?.myRole, 'admin')

/** 解析项目卡片动态属性模板（后端以 JSON 字符串下发） */
export const parseCardProperties = (project?: ProjectVO): CardProperty[] => {
  if (!project?.cardProperties) return []
  try {
    const parsed = JSON.parse(project.cardProperties)
    return Array.isArray(parsed) ? (parsed as CardProperty[]) : []
  } catch {
    return []
  }
}
