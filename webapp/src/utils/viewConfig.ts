import type { CardProperty, TaskVO, ViewConfig, ViewFilter, ViewSort, ViewType } from '@/types'

/** 默认视图配置（表格视图、无筛选、无排序） */
export const DEFAULT_VIEW_CONFIG: ViewConfig = { viewType: 'table', filters: [], sorts: [] }

const VALID_VIEW_TYPES: ViewType[] = ['table', 'board', 'gallery', 'calendar']

/**
 * 从 view 块的 fields 解析视图配置（容错处理，非法值回退默认）。
 */
export function parseViewConfig(fields?: Record<string, unknown>): ViewConfig {
  const config: ViewConfig = {
    viewType: VALID_VIEW_TYPES.includes(fields?.viewType as ViewType)
      ? (fields?.viewType as ViewType)
      : 'table',
    filters: Array.isArray(fields?.filters) ? (fields?.filters as ViewFilter[]) : [],
    sorts: Array.isArray(fields?.sorts) ? (fields?.sorts as ViewSort[]) : [],
    groupById: typeof fields?.groupById === 'string' ? (fields.groupById as string) : undefined,
    dateId: typeof fields?.dateId === 'string' ? (fields.dateId as string) : undefined,
    cardPropertyIds: Array.isArray(fields?.cardPropertyIds)
      ? (fields.cardPropertyIds as unknown[]).filter((v): v is string => typeof v === 'string')
      : undefined,
  }
  config.filters = config.filters.filter(
    (f) => f && typeof f.field === 'string' && Array.isArray(f.values),
  )
  config.sorts = config.sorts.filter(
    (s) => s && typeof s.field === 'string' && (s.direction === 'asc' || s.direction === 'desc'),
  )
  return config
}

/**
 * 将视图配置序列化为 view 块的 fields。
 */
export function toViewFields(config: ViewConfig): Record<string, unknown> {
  return {
    viewType: config.viewType,
    filters: config.filters,
    sorts: config.sorts,
    groupById: config.groupById,
    dateId: config.dateId,
    cardPropertyIds: config.cardPropertyIds,
  }
}

/** 属性值是否为空（未设置/空串/空数组） */
function isPropertyValueEmpty(value: unknown): boolean {
  return value == null || value === '' || (Array.isArray(value) && value.length === 0)
}

/**
 * 按动态属性匹配筛选条件：
 * select/multiSelect 支持按选项 id 或选项值 includes，全部类型支持 empty。
 */
function matchPropertyFilter(task: TaskVO, prop: CardProperty | undefined, filter: ViewFilter): boolean {
  const value = task.properties?.[filter.field]
  if (filter.operator === 'empty') {
    return isPropertyValueEmpty(value)
  }
  if (isPropertyValueEmpty(value)) {
    return false
  }
  if (prop && (prop.type === 'select' || prop.type === 'multiSelect')) {
    const ids = Array.isArray(value) ? (value as unknown[]) : [value]
    return ids.some((id) =>
      filter.values.some(
        (v) =>
          v === String(id) ||
          prop.options?.some((o) => o.id === id && (o.value === v || o.id === v)),
      ),
    )
  }
  return filter.values.includes(String(value))
}

/**
 * 按筛选条件过滤任务（多条件 AND，field 为属性 id）。
 */
export function applyFilters(
  tasks: TaskVO[],
  filters: ViewFilter[],
  cardProperties: CardProperty[],
): TaskVO[] {
  if (!filters.length) return tasks
  const propMap = new Map(cardProperties.map((p) => [p.id, p]))
  return tasks.filter((task) =>
    filters.every((filter) => matchPropertyFilter(task, propMap.get(filter.field), filter)),
  )
}

/**
 * 按关键词模糊匹配任务（标题或描述，忽略大小写）。
 */
export function applyKeyword(tasks: TaskVO[], keyword: string): TaskVO[] {
  const kw = keyword.trim().toLowerCase()
  if (!kw) return tasks
  return tasks.filter(
    (task) =>
      task.title.toLowerCase().includes(kw) ||
      (task.description ?? '').toLowerCase().includes(kw),
  )
}

/** 计算排序比较值：title/createdAt 固定字段 + 属性（select 按选项序、其余按值） */
function sortValue(
  task: TaskVO,
  field: ViewSort['field'],
  propMap: Map<string, CardProperty>,
): string | number | null {
  if (field === 'title') {
    return task.title
  }
  if (field === 'createdAt') {
    return new Date(task.createdAt.replace(' ', 'T')).getTime()
  }
  const prop = propMap.get(field)
  if (!prop) {
    return null
  }
  const value = task.properties?.[field]
  if (isPropertyValueEmpty(value)) {
    return null
  }
  switch (prop.type) {
    case 'select': {
      const index = prop.options?.findIndex((o) => o.id === value || o.value === value) ?? -1
      return index >= 0 ? index : null
    }
    case 'number': {
      const num = Number(value)
      return Number.isNaN(num) ? null : num
    }
    case 'date': {
      const time = new Date(String(value).replace(' ', 'T')).getTime()
      return Number.isNaN(time) ? null : time
    }
    case 'checkbox':
      return value ? 1 : 0
    default:
      return String(value)
  }
}

/**
 * 按排序规则排序任务（多字段依次比较，空值排末尾，不修改原数组）。
 */
export function applySort(
  tasks: TaskVO[],
  sorts: ViewSort[],
  cardProperties: CardProperty[],
): TaskVO[] {
  if (!sorts.length) return tasks
  const propMap = new Map(cardProperties.map((p) => [p.id, p]))
  return [...tasks].sort((a, b) => {
    for (const sort of sorts) {
      const va = sortValue(a, sort.field, propMap)
      const vb = sortValue(b, sort.field, propMap)
      // 空值统一排末尾，不受方向影响
      if (va == null && vb == null) continue
      if (va == null) return 1
      if (vb == null) return -1
      let cmp: number
      if (typeof va === 'number' && typeof vb === 'number') {
        cmp = va - vb
      } else {
        cmp = String(va).localeCompare(String(vb))
      }
      if (cmp !== 0) {
        return sort.direction === 'desc' ? -cmp : cmp
      }
    }
    return 0
  })
}

/**
 * 视图呈现管线：筛选 -> 关键词 -> 排序（筛选/排序均按项目属性模板驱动）。
 */
export function applyView(
  tasks: TaskVO[],
  config: ViewConfig,
  cardProperties: CardProperty[],
  keyword = '',
): TaskVO[] {
  return applySort(
    applyKeyword(applyFilters(tasks, config.filters, cardProperties), keyword),
    config.sorts,
    cardProperties,
  )
}
