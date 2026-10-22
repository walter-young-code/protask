/** 统一 API 响应结构，与后端 Result<T> 对应 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 用户视图 */
export interface UserVO {
  id: number
  username: string
  nickname: string
  /** 名（First Name） */
  firstName?: string
  /** 姓（Last Name） */
  lastName?: string
  /** 全名：First + Last 拼接，未填写时为空 */
  fullName?: string
  email?: string
  avatar?: string
  /** 所属团队名称，未分配时为空 */
  teamName?: string
  role: string
}

/** 登录响应 */
export interface LoginResponse {
  token: string
  user: UserVO
}

/** 注册邀请视图：token 为邀请凭证，inviteUrl 为完整注册链接 */
export interface InviteVO {
  /** 邀请凭证（注册链接携带） */
  token: string
  /** 完整邀请链接 */
  inviteUrl: string
  /** 过期时间 */
  expiresAt: string
}

/** 管理后台用户视图（含状态与时间信息） */
export interface AdminUserVO {
  id: number
  username: string
  nickname: string
  /** 名（First Name） */
  firstName?: string
  /** 姓（Last Name） */
  lastName?: string
  /** 全名：First + Last 拼接，未填写时为空 */
  fullName?: string
  email?: string
  avatar?: string
  role: string
  status: number
  teamId?: number
  teamName?: string
  createdAt: string
  updatedAt?: string
}

/** 管理后台团队视图（含成员数） */
export interface TeamVO {
  id: number
  name: string
  memberCount: number
  createdAt: string
  updatedAt?: string
}

/** 管理后台创建/更新团队请求 */
export interface TeamRequest {
  name: string
}

/** 通用分页结果 */
export interface PageResult<T> {
  total: number
  records: T[]
}

/** 管理后台用户分页查询参数 */
export interface AdminUserQuery {
  keyword?: string
  status?: number
  page?: number
  size?: number
}

/** 管理后台创建用户请求 */
export interface AdminUserCreateRequest {
  username: string
  password: string
  nickname?: string
  /** 名（First Name） */
  firstName?: string
  /** 姓（Last Name） */
  lastName?: string
  email?: string
  role?: string
  status?: number
  teamId?: number
}

/** 管理后台更新用户请求 */
export interface AdminUserUpdateRequest {
  nickname?: string
  /** 名（First Name） */
  firstName?: string
  /** 姓（Last Name） */
  lastName?: string
  email?: string
  role?: string
  status?: number
  teamId?: number
}

/** 管理后台重置密码请求 */
export interface ResetPasswordRequest {
  password: string
}

/** 项目内成员角色（对齐 Focalboard BoardRole） */
export type ProjectRole = 'viewer' | 'commenter' | 'editor' | 'admin'

/** 卡片动态属性选项 */
export interface CardPropertyOption {
  id: string
  value: string
  color?: string
}

/** 卡片动态属性类型 */
export type PropertyType =
  | 'text'
  | 'select'
  | 'date'
  | 'number'
  | 'multiSelect'
  | 'checkbox'
  | 'member'

/** 可新建的自定义属性类型（与 PropertyType 同义，全部类型均可新建） */
export type CustomPropertyType = PropertyType

/** 卡片动态属性定义（项目级模板） */
export interface CardProperty {
  id: string
  name: string
  type: PropertyType
  options?: CardPropertyOption[]
}

/** 项目分类视图 */
export interface CategoryVO {
  id: number
  name: string
  /** 创建人(用户ID) */
  createdBy: number
  createdAt: string
}

/** 新建项目分类请求 */
export interface CategoryRequest {
  name: string
}

/** 项目日历订阅视图：calendarToken 为订阅凭证，subscribeUrl 为 ICS 订阅地址 */
export interface CalendarSubscriptionVO {
  calendarToken?: string | null
  subscribeUrl?: string | null
}

/** 项目分享视图：shareId 为分享凭证，shareUrl 为完整分享链接 */
export interface ShareVO {
  /** 分享凭证(UUID，null=未开启分享) */
  shareId?: string | null
  /** 分享链接（null=未开启分享） */
  shareUrl?: string | null
}

/** 项目视图 */
export interface ProjectVO {
  id: number
  name: string
  description?: string
  /** 项目类型: O 公开, P 私有 */
  type?: 'O' | 'P'
  icon?: string
  /** 项目分类ID（null=未分类） */
  categoryId?: number | null
  /** 分类名称 */
  categoryName?: string
  /** 卡片动态属性定义模板(JSON 数组字符串) */
  cardProperties?: string
  /** 项目扩展属性(JSON 对象字符串，如默认视图配置 defaultViewId) */
  properties?: string
  ownerId: number
  ownerName?: string
  status: string
  /** 当前用户在项目中的生效角色: viewer/commenter/editor/admin */
  myRole?: string
  createdAt: string
  updatedAt: string
}

/** 已删除项目物理清理统计（管理后台按日期清理） */
export interface ProjectCleanupVO {
  /** 清理的项目数 */
  projects: number
  /** 物理删除的块数（任务/视图/评论） */
  blocks: number
  /** 物理删除的附件记录数 */
  attachments: number
  /** 删除的附件磁盘文件数 */
  attachmentFiles: number
  /** 删除的项目成员关系数 */
  members: number
  /** 删除的站内通知数 */
  notifications: number
}

/** 存储桶用量 */
export interface StorageBucketVO {
  fileCount: number
  totalBytes: number
}

/** 存储用量与孤儿图片统计（管理后台） */
export interface StorageStatsVO {
  publicImages: StorageBucketVO
  privateAttachments: StorageBucketVO
  attachmentRecords: number
  uploadImageRecords: number
  /** 无引用且超过保留期的孤儿图片数（未启用检测时恒为 0） */
  orphanImages: number
  orphanImageBytes: number
  /** 是否启用孤儿图片检测与清理，关闭时隐藏清理卡片 */
  orphanCheckEnabled: boolean
  /** 孤儿图片保留天数 */
  orphanRetentionDays: number
  warnFileCount: number
  warnTotalBytes: number
  /** 是否已超过告警阈值 */
  warning: boolean
}

/** 孤儿图片清理结果 */
export interface StorageCleanupVO {
  deletedImages: number
  deletedFiles: number
  freedBytes: number
}

/** 项目成员视图 */
export interface ProjectMemberVO {
  userId: number
  username?: string
  nickname?: string
  /** 姓名：First + Last 拼接，未填写时为空 */
  fullName?: string
  avatar?: string
  role: ProjectRole
  joinedAt: string
}

/** 添加/变更项目成员请求 */
export interface ProjectMemberRequest {
  userId: number
  role: ProjectRole
}

/** 块视图（统一块模型 card/view/text/comment） */
export interface BlockVO {
  id: number
  projectId: number
  parentId?: number
  schemaVer: number
  type: 'card' | 'view' | 'text' | 'comment'
  title: string
  fields?: Record<string, unknown>
  createdBy: number
  createdByName?: string
  modifiedBy: number
  modifiedByName?: string
  createAt: string
  updateAt: string
}

/** 创建块请求 */
export interface BlockRequest {
  type: BlockVO['type']
  parentId?: number | null
  title?: string
  fields?: Record<string, unknown>
  /** 评论中被 @提及的成员用户 ID（仅 comment 类型生效） */
  mentionUserIds?: number[]
}

/** 站内通知视图（当前仅评论 @提及） */
export interface NotificationVO {
  id: number
  type: 'mention'
  projectId: number
  taskId: number
  commentId: number
  actorId: number
  /** 操作人昵称 */
  actorName?: string
  /** 关联任务标题 */
  taskTitle?: string
  /** 评论摘要 */
  snippet?: string
  isRead: boolean
  createAt: string
}

/** 块局部更新请求 */
export interface BlockPatchRequest {
  parentId?: number
  title?: string
  updatedFields?: Record<string, unknown>
  deletedFields?: string[]
}

/** 任务视图 */
export interface TaskVO {
  id: number
  projectId: number
  /** 所属项目名称（仅全局搜索场景返回） */
  projectName?: string
  title: string
  /** 任务图标（emoji） */
  icon?: string
  description?: string
  creatorId: number
  creatorName?: string
  createdAt: string
  updatedAt: string
  /** 卡片全部动态属性 */
  properties?: Record<string, unknown>
}

/** 创建/更新任务请求（properties 整体替换卡片属性值） */
export interface TaskRequest {
  title: string
  /** 任务图标（emoji），空串表示移除 */
  icon?: string
  description?: string
  /** 动态属性(按项目 cardProperties 模板定义) */
  properties?: Record<string, unknown>
}

/** 仪表盘任务列表项（跨项目轻量字段） */
export interface DashboardTaskItemVO {
  id: number
  projectId: number
  /** 所属项目名称 */
  projectName?: string
  title: string
  /** 任务图标（emoji） */
  icon?: string
  /** 截止日期（YYYY-MM-DD，未设置为空） */
  dueDate?: string | null
  creatorName?: string
  updatedAt: string
}

/** 仪表盘聚合视图：统计数字 + 分类任务列表 */
export interface DashboardVO {
  totalTasks: number
  /** 我负责的任务数（当前用户为负责人属性值且未完成） */
  myTasks: number
  /** 紧急任务数（内置优先级 P0） */
  urgentTasks: number
  /** 逾期任务数 */
  overdueTasks: number
  /** 即将到期任务数（7 天内截止） */
  dueSoonTasks: number
  /** 已完成任务数 */
  doneTasks: number
  myTaskList: DashboardTaskItemVO[]
  urgentList: DashboardTaskItemVO[]
  overdueList: DashboardTaskItemVO[]
  dueSoonList: DashboardTaskItemVO[]
}

/** 创建/更新项目请求 */
export interface ProjectRequest {
  name: string
  description?: string
  type?: 'O' | 'P'
  icon?: string
  /** 分类ID：仅创建时生效（null/0=未分类，>0=设置分类）；更新归类请调用 assignProjectCategory */
  categoryId?: number | null
}

/** 复制项目请求（字段可选，缺省沿用原项目值；名称缺省为「原名称 + 副本后缀」） */
export interface ProjectCopyRequest {
  name?: string
  description?: string
  type?: 'O' | 'P'
}

/** 项目模板示例卡片（模板预览用） */
export interface ProjectTemplateSampleCard {
  title: string
  /** 卡片图标（emoji） */
  icon?: string
  /** 示例属性值 */
  properties?: Record<string, unknown>
}

/** 项目模板视图（模板库只读数据） */
export interface ProjectTemplateVO {
  id: number
  name: string
  description?: string
  /** 项目图标 */
  icon?: string
  /** 卡片动态属性定义模板(JSON 数组字符串) */
  cardProperties?: string
  /** 模板创建时间 */
  createdAt?: string
  /** 示例卡片（用于模板预览） */
  sampleCards?: ProjectTemplateSampleCard[]
}

/** 管理后台更新项目模板请求 */
export interface ProjectTemplateUpdateRequest {
  name: string
  description?: string
  /** 项目图标 */
  icon?: string
}

/** 从模板创建项目请求 */
export interface ProjectTemplateRequest extends ProjectRequest {
  /** 模板 ID */
  templateId: number
}

/** 将现有项目转为模板请求（字段可选，缺省沿用原项目值） */
export interface ProjectToTemplateRequest {
  name?: string
  description?: string
}

/** 视图类型（对齐 Focalboard ViewType） */
export type ViewType = 'table' | 'board' | 'gallery' | 'calendar'

/** 可筛选的字段：动态属性 id */
export type FilterField = string

/** 筛选运算符：includes 包含任一值；empty 为空/未设置 */
export type FilterOperator = 'includes' | 'empty'

/** 视图筛选条件 */
export interface ViewFilter {
  field: FilterField
  operator: FilterOperator
  values: string[]
}

/** 可排序的字段：动态属性 id 或 title/createdAt */
export type SortField = string

/** 视图排序规则 */
export interface ViewSort {
  field: SortField
  direction: 'asc' | 'desc'
}

/** 视图配置（持久化于 view 块的 fields） */
export interface ViewConfig {
  viewType: ViewType
  filters: ViewFilter[]
  sorts: ViewSort[]
  /** 看板分组属性 id（select 类型属性） */
  groupById?: string
  /** 日历日期属性 id（date 类型属性） */
  dateId?: string
  /** 看板卡片显示的属性 id 列表（undefined=默认前 3 个有值属性） */
  cardPropertyIds?: string[]
}

/** 视图 Tab 的展示结构（view 块 + 解析后的配置） */
export interface ViewTab {
  id: number
  title: string
  config: ViewConfig
}

/** 站点公开设置：应用名称 + LOGO（未配置时 logo 为 null） */
export interface SiteSettingsVO {
  name: string
  logo: string | null
  /** 单文件上传上限（字节），后端按 multipart 配置下发 */
  maxUploadBytes: number
}

/** 更新站点设置请求（仅系统管理员） */
export interface SiteSettingsRequest {
  name: string
  logo: string | null
}
