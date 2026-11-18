import type { CardProperty, CustomPropertyType, TaskVO } from '@/types'

/** 可新建的属性类型（文案由 i18n 提供：propertyType.*） */
export const CUSTOM_PROPERTY_TYPES: CustomPropertyType[] = [
  'text',
  'select',
  'date',
  'number',
  'multiSelect',
  'checkbox',
  'member',
]

/** 生成属性/选项的随机 ID */
export function createPropertyId(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4)
}

/** 内置推荐属性：选项已预配，按需一键添加（不随新建项目自带） */
export const BUILTIN_PROPERTIES: CardProperty[] = [
  {
    id: 'priority',
    name: '优先级',
    type: 'select',
    options: [
      { id: 'p0', value: 'P0', color: '#ffa9a9' },
      { id: 'p1', value: 'P1', color: '#ffd3c1' },
      { id: 'p2', value: 'P2', color: '#f7f0b6' },
      { id: 'p3', value: 'P3', color: '#f7ddc3' },
    ],
  },
  {
    id: 'status',
    name: '状态',
    type: 'select',
    options: [
      { id: 'todo', value: '待开始', color: '#ededed' },
      { id: 'in_progress', value: '进行中', color: '#b1d1f6' },
      { id: 'done', value: '已完成', color: '#c7eac3' },
    ],
  },
  {
    id: 'dueDate',
    name: '截止日期',
    type: 'date',
  },
  {
    id: 'wbs',
    name: 'WBS编码',
    type: 'text',
  },
  {
    id: 'startDate',
    name: '开始日期',
    type: 'date',
  },
]

/** 内置 WBS 编码属性 id（门禁开关：项目属性模板含此 id 即启用层级/甘特/依赖/导出） */
export const WBS_PROPERTY_ID = 'wbs'

/** 内置开始日期属性 id（仅 WBS 项目菜单提供，与截止日期绑定排期） */
export const START_DATE_PROPERTY_ID = 'startDate'

/** 内置截止日期属性 id（全项目通用） */
export const DUE_DATE_PROPERTY_ID = 'dueDate'

/** 项目是否添加了 WBS 编码属性（层级/甘特/依赖/导出的总门禁） */
export function hasWbsProperty(cardProperties: CardProperty[]): boolean {
  return cardProperties.some((p) => p.id === WBS_PROPERTY_ID)
}

/** 甘特必要字段：启用（含 WBS）后锁定不可删除 */
export const GANTT_REQUIRED_PROPERTY_IDS: string[] = [
  WBS_PROPERTY_ID,
  START_DATE_PROPERTY_ID,
  DUE_DATE_PROPERTY_ID,
]

/** 属性 id 是否为甘特必要字段 */
export function isGanttRequiredProperty(id: string): boolean {
  return GANTT_REQUIRED_PROPERTY_IDS.includes(id)
}

/** 模板是否已绑定排期字段（开始日期 + 截止日期均在） */
export function hasScheduleProperties(cardProperties: CardProperty[]): boolean {
  return (
    cardProperties.some((p) => p.id === START_DATE_PROPERTY_ID) &&
    cardProperties.some((p) => p.id === DUE_DATE_PROPERTY_ID)
  )
}

/** 选项可选颜色（对齐 Focalboard propColor* 10 色系，浅色标签底，直接存 hex） */
export const OPTION_COLORS = [
  '#ffffff', // 默认（白）
  '#ededed', // 灰
  '#f7ddc3', // 棕
  '#ffd3c1', // 橙
  '#f7f0b6', // 黄
  '#c7eac3', // 绿
  '#b1d1f6', // 蓝
  '#e6d0ff', // 紫
  '#ffd6e9', // 粉
  '#ffa9a9', // 红
]

/**
 * 动态属性在各视图中的只读展示文本：
 * select/multiSelect 按选项解析（孤儿 id 即选项已删除，不展示），checkbox 显示勾选符，
 * member 按成员映射解析（成员已移除即孤儿，不展示），date 仅展示日期部分（兼容存量带时间的值），
 * 其余原样转字符串。
 * @param memberMap 项目成员映射（userId -> 昵称），供 member 类型解析
 */
export function propertyDisplayText(
  task: TaskVO,
  prop: CardProperty,
  memberMap?: Record<number, string>,
): string {
  // WBS 编码为后端树序自动编号，值取自 task.wbs（只读，不存于 properties）
  if (prop.id === WBS_PROPERTY_ID) {
    return task.wbs || '—'
  }
  const value = task.properties?.[prop.id]
  if (value == null || value === '') return '—'
  if (prop.type === 'select') {
    const option = prop.options?.find((o) => o.id === value || o.value === value)
    return option?.value ?? '—'
  }
  if (prop.type === 'multiSelect') {
    if (!Array.isArray(value)) return '—'
    const texts = (value as unknown[])
      .map((id) => prop.options?.find((o) => o.id === id)?.value)
      .filter((text): text is string => Boolean(text))
    return texts.join(', ') || '—'
  }
  if (prop.type === 'checkbox') {
    return value ? '✓' : '—'
  }
  if (prop.type === 'member') {
    return memberMap?.[Number(value)] ?? '—'
  }
  if (prop.type === 'date') {
    // 仅取日期部分，兼容历史遗留的 YYYY-MM-DD HH:mm:ss 值
    return String(value).slice(0, 10)
  }
  return String(value)
}
