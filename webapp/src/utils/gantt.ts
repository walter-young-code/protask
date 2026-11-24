import type { DependencyType, GanttScale, TaskVO } from '@/types'
import type { TaskTreeNode } from '@/utils/taskTree'

/** 行高（每个任务一行，紧凑布局） */
export const ROW_HEIGHT = 32
/** 条体高度 */
export const BAR_HEIGHT = 16
/** 条尾依赖连接点半径 */
export const BAR_CONNECTOR_RADIUS = 5
/** 连接点圆心相对条尾的偏移（整体悬于条体端外，紧贴条尾仅留桥接线短间隙） */
export const BAR_CONNECTOR_OFFSET = 7
/** 表头总高（月份行 + 日/周/月刻度行） */
export const HEADER_HEIGHT = 48
/** 月份行高 */
export const MONTH_ROW_HEIGHT = 24
/** 左表固定列总宽（内边距 16 + 展开 18 + 列隙 24 + WBS 40 + 工期 48 + 起/止 76×2，与 GanttView CSS 同步） */
export const LEFT_FIXED_WIDTH = 298
/** 工期/开始/截止列模块整体宽（48 + 76×2 + 3 个列隙×4，工具栏「工期」按钮整体隐藏） */
export const DATE_MODULE_WIDTH = 48 + 76 * 2 + 4 * 3
/** 任务列默认宽度（表头分隔条可拖拽调整） */
export const TASK_COL_DEFAULT_WIDTH = 180
/** 任务列拖拽下限（保证省略号截断仍可读） */
export const TASK_COL_MIN_WIDTH = 88
/** 任务列拖拽上限（保证时间轴仍有可用视区） */
export const TASK_COL_MAX_WIDTH = 560
/** 左侧任务表默认总宽（WBS + 任务 + 工期 + 开始/截止） */
export const LEFT_WIDTH = LEFT_FIXED_WIDTH + TASK_COL_DEFAULT_WIDTH
/** 任务列右边界相对列宽的偏移（分隔条/列参考线定位，= 内边距 + 展开 + WBS + 列隙 + 半隙） */
export const TASK_COL_BOUNDARY_OFFSET = 80
/** 刻度列标签完整显示所需的最小列宽（日=单日号 / 周=月日 / 月=月份名） */
export const COLUMN_LABEL_MIN_WIDTH: Record<GanttScale, number> = { day: 16, week: 30, month: 28 }
/** 月份分段标签完整显示所需的最小段宽（12px 粗体「2026年11月」约 64px + 左内缩 6 + 右留白 8） */
export const MONTH_LABEL_MIN_WIDTH = 78

/** 各缩放粒度下每天占用的像素宽度（紧凑网格） */
export const PX_PER_DAY: Record<GanttScale, number> = {
  day: 30,
  week: 12,
  month: 4,
}

/** 甘特时间范围（含左右留白） */
export interface GanttRange {
  start: Date
  end: Date
  totalDays: number
}

/** 底部刻度列 */
export interface TimeColumn {
  date: Date
  label: string
  x: number
  width: number
  /** 日粒度下是否为周末（底纹用） */
  weekend: boolean
  /** 列宽是否足以完整容纳标签（false 时不渲染文字，避免被截断） */
  showLabel: boolean
}

/** 顶部月份分段 */
export interface MonthSegment {
  label: string
  x: number
  width: number
  /** 段宽是否足以完整容纳含年份的标签（false 时不渲染文字） */
  showLabel: boolean
}

/** 条体几何（已换算为 SVG 坐标） */
export interface BarGeometry {
  x: number
  width: number
  start: Date
  end: Date
  milestone: boolean
  summary: boolean
}

const DAY_MS = 86400000

/** 解析日期值为本地零点 Date（兼容 YYYY-MM-DD 与带时间的历史值），非法返回 null */
export function parseDate(value: unknown): Date | null {
  if (value == null || value === '') return null
  const text = String(value).slice(0, 10)
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

/** 格式化为 YYYY-MM-DD */
export function formatDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 本地零点 */
export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

/** 增减天数（返回新 Date） */
export function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

/** 两个零点日期之间的整天数（b - a） */
export function diffDays(a: Date, b: Date): number {
  return Math.round((startOfDay(b).getTime() - startOfDay(a).getTime()) / DAY_MS)
}

/** 工期（自然日，含起止当天），与条体宽度语义一致 */
export function durationDays(start: Date, end: Date): number {
  return diffDays(start, end) + 1
}

/** 月份表头标签（随语言环境：zh-CN “2026年11月”/“11月”，en-US “Nov 2026”/“Nov”） */
export function monthLabel(date: Date, locale: string, withYear: boolean): string {
  return new Intl.DateTimeFormat(locale, {
    year: withYear ? 'numeric' : undefined,
    month: 'short',
  }).format(date)
}

/** 今天（本地零点） */
export function today(): Date {
  return startOfDay(new Date())
}

/** 是否周末（周六/周日） */
export function isWeekend(date: Date): boolean {
  const day = date.getDay()
  return day === 0 || day === 6
}

/** 读取任务的开始/截止日期（开始缺省回退截止，截止缺省回退开始） */
export function taskDates(
  task: TaskVO,
  startId: string,
  dueId: string,
): { start: Date | null; end: Date | null } {
  const rawStart = parseDate(task.properties?.[startId])
  const rawEnd = parseDate(task.properties?.[dueId])
  const start = rawStart ?? rawEnd
  const end = rawEnd ?? rawStart
  return { start, end }
}

/**
 * 计算时间范围：取全部已排期任务（含摘要推导）的最小开始/最大截止，
 * 左右各留白 pad 天；无排期任务时以今天为中心展开默认窗口。
 */
export function computeRange(
  rows: TaskTreeNode[],
  startId: string,
  dueId: string,
  pad = 7,
): GanttRange {
  const startTimes: number[] = []
  const endTimes: number[] = []
  for (const row of rows) {
    const { start, end } = effectiveDates(row, startId, dueId)
    if (start) startTimes.push(start.getTime())
    if (end) endTimes.push(end.getTime())
  }
  const base = today()
  const min = startTimes.length ? new Date(startTimes.reduce((a, b) => Math.min(a, b), Infinity)) : null
  const max = endTimes.length ? new Date(endTimes.reduce((a, b) => Math.max(a, b), -Infinity)) : null
  const rangeStart = addDays(min ?? addDays(base, -pad), -pad)
  const rangeEnd = addDays(max ?? addDays(base, pad * 3), pad)
  return { start: rangeStart, end: rangeEnd, totalDays: diffDays(rangeStart, rangeEnd) + 1 }
}

/**
 * 时间范围自适应可视区宽度：任务少/条体短导致网格铺不满内容框时，
 * 向后按整天扩展直至覆盖 minWidthPx；起点与每日像素宽不变，拖拽吸附不受影响。
 */
export function fitRangeToWidth(range: GanttRange, pxPerDay: number, minWidthPx: number): GanttRange {
  if (minWidthPx <= range.totalDays * pxPerDay) return range
  const totalDays = Math.ceil(minWidthPx / pxPerDay)
  return { start: range.start, end: addDays(range.start, totalDays - 1), totalDays }
}

/**
 * 任务有效日期：摘要任务（有子孙）由子孙 min/max 推导，叶子取自身属性。
 * 子孙日期递归考虑（折叠时仍按全部子孙推导，保证汇总条稳定）。
 */
export function effectiveDates(
  node: TaskTreeNode,
  startId: string,
  dueId: string,
): { start: Date | null; end: Date | null } {
  if (!node.children.length) {
    return taskDates(node, startId, dueId)
  }
  const startTimes: number[] = []
  const endTimes: number[] = []
  const collect = (n: TaskTreeNode) => {
    const own = taskDates(n, startId, dueId)
    if (own.start) startTimes.push(own.start.getTime())
    if (own.end) endTimes.push(own.end.getTime())
    n.children.forEach(collect)
  }
  node.children.forEach(collect)
  // 摘要自身若也填了日期，纳入汇总
  const own = taskDates(node, startId, dueId)
  if (own.start) startTimes.push(own.start.getTime())
  if (own.end) endTimes.push(own.end.getTime())
  return {
    start: startTimes.length ? new Date(startTimes.reduce((a, b) => Math.min(a, b), Infinity)) : null,
    end: endTimes.length ? new Date(endTimes.reduce((a, b) => Math.max(a, b), -Infinity)) : null,
  }
}

/** 日期 → SVG 横坐标 */
export function dateToX(date: Date, rangeStart: Date, pxPerDay: number): number {
  return diffDays(rangeStart, date) * pxPerDay
}

/** SVG 横坐标 → 日期（按天吸附） */
export function xToDate(x: number, rangeStart: Date, pxPerDay: number): Date {
  return addDays(rangeStart, Math.round(x / pxPerDay))
}

/**
 * 计算条体几何：含开始/截止当天（宽度 = 天数差 + 1），
 * 里程碑按显式标记判定（未标记同日任务为 1 天短条）；摘要任务标记 summary。
 */
export function barGeometry(
  node: TaskTreeNode,
  startId: string,
  dueId: string,
  rangeStart: Date,
  pxPerDay: number,
): BarGeometry | null {
  const { start, end } = effectiveDates(node, startId, dueId)
  if (!start || !end) return null
  const x = dateToX(start, rangeStart, pxPerDay)
  const days = durationDays(start, end)
  return {
    x,
    width: Math.max(days * pxPerDay, pxPerDay),
    start,
    end,
    milestone: node.milestone === true && !node.children.length,
    summary: node.children.length > 0,
  }
}

/**
 * 左表工期列展示值（自然日）：里程碑恒 0（项目管理惯例，与 Excel/MSPDI 导出、任务设置弹窗同语义），
 * 不按起止日期 diff+1 推导；非拖拽预览时叶子显式 durationDays 优先，其余按条体起止天数。
 * previewDates=true 表示 geo 为拖拽预览几何，此时显式工期不参与（以预览日期为准）。
 */
export function barDurationDays(
  node: TaskTreeNode,
  geo: BarGeometry,
  previewDates = false,
): number {
  if (geo.milestone) return 0
  if (!previewDates && !node.children.length && node.durationDays != null && node.durationDays > 0) {
    return node.durationDays
  }
  return durationDays(geo.start, geo.end)
}

/** 刻度列/月段的构造态：showLabel 待末列钳制后统一裁决 */
type DraftColumn = Omit<TimeColumn, 'showLabel'>
type DraftSegment = Omit<MonthSegment, 'showLabel'>

/** 底部刻度列（日=每天/周=每周一/月=每月 1 号）；maxWidth 用于将末列钳制到精确铺满宽度 */
export function buildTimeColumns(range: GanttRange, scale: GanttScale, pxPerDay: number, maxWidth?: number, locale = 'zh-CN'): TimeColumn[] {
  const columns: DraftColumn[] = []
  const rangeEndEx = addDays(range.end, 1)
  if (scale === 'day') {
    for (let i = 0; i < range.totalDays; i++) {
      const date = addDays(range.start, i)
      columns.push({
        date,
        label: String(date.getDate()),
        x: i * pxPerDay,
        width: pxPerDay,
        weekend: isWeekend(date),
      })
    }
  } else if (scale === 'week') {
    // 对齐到周一作为首列起点；首尾列钳制到 range 边界，避免负坐标裁掉标签
    let cursor = startOfDay(range.start)
    const dow = (cursor.getDay() + 6) % 7
    cursor = addDays(cursor, -dow)
    while (cursor <= range.end) {
      const next = addDays(cursor, 7)
      const segStart = cursor < range.start ? range.start : cursor
      const segEnd = next > rangeEndEx ? rangeEndEx : next
      const x = dateToX(segStart, range.start, pxPerDay)
      const label = `${cursor.getMonth() + 1}/${cursor.getDate()}`
      columns.push({ date: cursor, label, x, width: diffDays(segStart, segEnd) * pxPerDay, weekend: false })
      cursor = next
    }
  } else {
    let cursor = new Date(range.start.getFullYear(), range.start.getMonth(), 1)
    while (cursor <= range.end) {
      const next = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1)
      const segStart = cursor < range.start ? range.start : cursor
      const segEnd = next > rangeEndEx ? rangeEndEx : next
      const x = dateToX(segStart, range.start, pxPerDay)
      columns.push({
        date: cursor,
        label: monthLabel(cursor, locale, false),
        x,
        width: diffDays(segStart, segEnd) * pxPerDay,
        weekend: false,
      })
      cursor = next
    }
  }
  clampLast(columns, maxWidth)
  // 被 range 边界截短的首/尾列（含 maxWidth 收缩后的末列）窄于可读宽时不渲染标签
  const minWidth = COLUMN_LABEL_MIN_WIDTH[scale]
  return columns.map((c) => ({ ...c, showLabel: c.width >= minWidth }))
}

/** 顶部月份分段（跨整月，label 含年份）；maxWidth 用于将末段钳制到精确铺满宽度 */
export function buildMonthSegments(range: GanttRange, pxPerDay: number, maxWidth?: number, locale = 'zh-CN'): MonthSegment[] {
  const segments: DraftSegment[] = []
  const rangeEndEx = addDays(range.end, 1)
  let cursor = new Date(range.start.getFullYear(), range.start.getMonth(), 1)
  while (cursor <= range.end) {
    const next = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1)
    // 首尾月段钳制到 range 边界（整月区间才有完整标签）
    const segStart = cursor < range.start ? range.start : cursor
    const segEnd = next > rangeEndEx ? rangeEndEx : next
    const x = dateToX(segStart, range.start, pxPerDay)
    segments.push({
      label: monthLabel(cursor, locale, true),
      x,
      width: diffDays(segStart, segEnd) * pxPerDay,
    })
    cursor = next
  }
  clampLast(segments, maxWidth)
  // 首段（上一个月份）与末段常被 range 边界截短，窄于标签可读宽时隐藏文字而非截断显示
  return segments.map((s) => ({ ...s, showLabel: s.width >= MONTH_LABEL_MIN_WIDTH }))
}

/** 末段超出精确铺满宽度时收缩到边界（宽度归零则移除），避免右侧标签被裁 */
function clampLast<T extends { x: number; width: number }>(items: T[], maxWidth?: number): void {
  if (maxWidth == null || !items.length) return
  const last = items[items.length - 1]
  if (last.x + last.width > maxWidth) {
    last.width = Math.max(0, maxWidth - last.x)
    if (last.width === 0) items.pop()
  }
}

/**
 * FS 肘形连线路径：从前置条尾 (x1,y1) 到后置条首 (x2,y2)。
 * 目标在右侧时直接右-竖-右；目标在左侧（重叠/前置）时绕行行间，避免穿条。
 * 保留为四类型通用路径的 FS 包装（finish→start）。
 */
export function elbowPath(x1: number, y1: number, x2: number, y2: number): string {
  return dependencyPath(x1, y1, x2, y2, 'finish', 'start')
}

/** 条体锚点端：start=条首（左）/ finish=条尾（右） */
export type BarEnd = 'start' | 'finish'

/** 依赖类型 → 前置/后置锚点端（FS=尾→首、SS=首→首、FF=尾→尾、SF=首→尾） */
export function dependencyEnds(type: DependencyType): { fromEnd: BarEnd; toEnd: BarEnd } {
  switch (type) {
    case 'SS': return { fromEnd: 'start', toEnd: 'start' }
    case 'FF': return { fromEnd: 'finish', toEnd: 'finish' }
    case 'SF': return { fromEnd: 'start', toEnd: 'finish' }
    default: return { fromEnd: 'finish', toEnd: 'start' }
  }
}

/** 前置/后置锚点端 → 依赖类型（拖拽落点判型用） */
export function dependencyType(fromEnd: BarEnd, toEnd: BarEnd): DependencyType {
  if (fromEnd === 'finish') return toEnd === 'start' ? 'FS' : 'FF'
  return toEnd === 'start' ? 'SS' : 'SF'
}

/** 锚点横坐标：普通条 start=x / finish=x+width；里程碑两锚点均在菱形中心 */
export function anchorX(geo: BarGeometry, end: BarEnd, pxPerDay: number): number {
  if (geo.milestone) return geo.x + pxPerDay / 2
  return end === 'start' ? geo.x : geo.x + geo.width
}

/**
 * 四类型肘形连线路径：从前置锚点 (x1,y1) 到后置锚点 (x2,y2)。
 * 出线方向由前置端决定（finish 向右 / start 向左），入线方向由后置端决定
 * （start 从左入、箭头指右 / finish 从右入、箭头指左）。
 * 折弯数最小化：竖段只需同时落在「出线端连接点外」与「目标箭头入线侧外」，
 * 末段横线就天然与箭头同向对齐，两点折弯即可收尾（FF/SS 不再绕行行间）；
 * 两个候选位置都与出线方向冲突（重叠/逆向）时，才绕行行间避免穿条。
 */
export function dependencyPath(
  x1: number, y1: number, x2: number, y2: number,
  fromEnd: BarEnd, toEnd: BarEnd,
): string {
  const exitDir = fromEnd === 'finish' ? 1 : -1
  const entryDir = toEnd === 'start' ? 1 : -1
  // 出弯让位条端外连接点（圆心偏移 + 半径 + 余量），避免肘弯藏进圆点
  const out = BAR_CONNECTOR_OFFSET + BAR_CONNECTOR_RADIUS + 5
  const inn = 12
  const arrow = 7
  const inX = x2 - entryDir * inn
  const tipX = x2 - entryDir * arrow
  // 候选一：紧贴出线端的竖段（FS/SF 常规形态）
  const near = x1 + exitDir * out
  if ((tipX - near) * entryDir > 0) {
    return `M ${x1} ${y1} H ${near} V ${y2} H ${tipX}`
  }
  // 候选二：竖段移到目标箭头外侧的平行线，折下后反向对准箭头（FF/SS 常规形态）
  const far = x2 - entryDir * out
  if ((far - x1) * exitDir > 0) {
    return `M ${x1} ${y1} H ${far} V ${y2} H ${tipX}`
  }
  // 重叠/逆向：绕行行间避免穿条
  const midY = y1 + (y2 > y1 ? ROW_HEIGHT / 2 : -ROW_HEIGHT / 2)
  return `M ${x1} ${y1} H ${near} V ${midY} H ${inX} V ${y2} H ${tipX}`
}

/** 连线箭头三角形顶点（尖端落在 x2,y2；dir 指定指向左/右，缺省右） */
export function arrowPoints(x2: number, y2: number, dir: 'left' | 'right' = 'right'): string {
  const size = 7
  const baseX = dir === 'right' ? x2 - size : x2 + size
  return `${x2},${y2} ${baseX},${y2 - size / 1.6} ${baseX},${y2 + size / 1.6}`
}