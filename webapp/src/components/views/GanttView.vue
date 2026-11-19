<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Calendar, Check, Download, Plus, ZoomIn } from '@element-plus/icons-vue'
import type { CardProperty, DependencyType, DependencyVO, GanttScale, TaskVO } from '@/types'
import { DUE_DATE_PROPERTY_ID, START_DATE_PROPERTY_ID, hasScheduleProperties } from '@/utils/meta'
import { buildTaskTree, flattenTree, type TaskTreeNode } from '@/utils/taskTree'
import {
  BAR_CONNECTOR_OFFSET,
  BAR_CONNECTOR_RADIUS,
  BAR_HEIGHT,
  DATE_MODULE_WIDTH,
  HEADER_HEIGHT,
  LEFT_FIXED_WIDTH,
  MONTH_ROW_HEIGHT,
  PX_PER_DAY,
  ROW_HEIGHT,
  TASK_COL_BOUNDARY_OFFSET,
  TASK_COL_DEFAULT_WIDTH,
  TASK_COL_MAX_WIDTH,
  TASK_COL_MIN_WIDTH,
  addDays,
  anchorX,
  arrowPoints,
  barGeometry,
  buildMonthSegments,
  buildTimeColumns,
  computeRange,
  dateToX,
  dependencyEnds,
  dependencyPath,
  dependencyType,
  diffDays,
  durationDays,
  fitRangeToWidth,
  formatDate,
  isWeekend,
  today,
  type BarEnd,
  type BarGeometry,
} from '@/utils/gantt'

const props = defineProps<{
  tasks: TaskVO[]
  loading: boolean
  editable: boolean
  cardProperties: CardProperty[]
  memberMap?: Record<number, string>
  /** WBS 门禁：项目是否添加了 WBS 编码属性 */
  hasWbs: boolean
  /** 依赖列表（FS/SS/FF/SF，父级加载） */
  dependencies: DependencyVO[]
  ganttScale?: GanttScale
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
  (e: 'scheduleChange', task: TaskVO, startDate: string, dueDate: string): void
  (e: 'scaleChange', scale: GanttScale): void
  (e: 'dependencyCreate', predecessorId: number, successorId: number, type: DependencyType): void
  (e: 'dependencyDelete', dependency: DependencyVO): void
  (e: 'milestoneChange', task: TaskVO, milestone: boolean): void
  (e: 'export'): void
  (e: 'quickAdd'): void
}>()

const { t, locale } = useI18n()

const SCALES: GanttScale[] = ['day', 'week', 'month']
const scale = computed<GanttScale>(() => props.ganttScale ?? 'week')
const pxPerDay = computed(() => PX_PER_DAY[scale.value])

/** 排期固定读内置开始/截止日期（启用甘特时由后端自动绑定，不可删除） */
const dateIds = { startId: START_DATE_PROPERTY_ID, dueId: DUE_DATE_PROPERTY_ID }
const scheduleBound = computed(() => hasScheduleProperties(props.cardProperties))

/** 折叠集合（本地状态，折叠隐藏子孙及连线） */
const collapsed = ref<Set<number>>(new Set())
function toggleCollapse(id: number) {
  const next = new Set(collapsed.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsed.value = next
}

/** 可见行（树序 + 折叠平铺） */
const rows = computed<TaskTreeNode[]>(() =>
  flattenTree(buildTaskTree(props.tasks), collapsed.value),
)

/** 图表可视区宽度（不含左侧任务表），驱动时间网格自适应铺满内容框 */
const viewportWidth = ref(0)

const baseRange = computed(() => computeRange(rows.value, dateIds.startId, dateIds.dueId))
/** 精确铺满：网格不足时取可视区宽（不产生横向滚动），否则取网格自然宽 */
const chartWidth = computed(() =>
  Math.max(baseRange.value.totalDays * pxPerDay.value, viewportWidth.value),
)
const range = computed(() => fitRangeToWidth(baseRange.value, pxPerDay.value, chartWidth.value))
const chartHeight = computed(() => Math.max(rows.value.length * ROW_HEIGHT, ROW_HEIGHT))
/** 标签最小可读宽：末列/末段窄于该值时隐藏标签，避免右缘裁切 */
const LABEL_MIN_WIDTH: Record<GanttScale, number> = { day: 16, week: 30, month: 28 }
const columns = computed(() => {
  const cols = buildTimeColumns(range.value, scale.value, pxPerDay.value, chartWidth.value, locale.value)
  const last = cols[cols.length - 1]
  if (last && last.width < LABEL_MIN_WIDTH[scale.value]) last.label = ''
  return cols
})
const months = computed(() => {
  const segs = buildMonthSegments(range.value, pxPerDay.value, chartWidth.value, locale.value)
  const last = segs[segs.length - 1]
  if (last && last.width < 78) last.label = ''
  return segs
})

/** 周末底纹（按天，适配各缩放粒度） */
const weekendRects = computed(() => {
  const rects: { x: number; width: number }[] = []
  for (let i = 0; i < range.value.totalDays; i++) {
    const date = addDays(range.value.start, i)
    if (isWeekend(date)) rects.push({ x: i * pxPerDay.value, width: pxPerDay.value })
  }
  return rects
})

/** 今日线横坐标（range 内才显示） */
const todayX = computed(() => {
  const now = today()
  if (now < range.value.start || now > range.value.end) return null
  return dateToX(now, range.value.start, pxPerDay.value) + pxPerDay.value / 2
})

/** 状态属性（条体着色依据） */
const statusProp = computed(() => props.cardProperties.find((p) => p.id === 'status'))
const DEFAULT_COLORS: Record<string, string> = {
  todo: '#c8ccd4',
  in_progress: '#a9cdf3',
  done: '#b7e0b3',
}
function barFill(task: TaskVO): string {
  const value = task.properties?.status
  const option = statusProp.value?.options?.find((o) => o.id === value || o.value === value)
  if (option?.color) return option.color
  // 未填状态视为待开始，与图例灰色一致
  return DEFAULT_COLORS[String(value)] ?? DEFAULT_COLORS.todo
}

/** 拖拽状态 */
interface DragState {
  mode: 'move' | 'resize-start' | 'resize-end' | 'link'
  taskId: number
  origStart: Date
  origEnd: Date
  curStart: Date
  curEnd: Date
  startClientX: number
  startClientY: number
  cursorX: number
  cursorY: number
  /** link 模式的源锚点端（start=条首 / finish=条尾） */
  sourceEnd: BarEnd
}
const drag = ref<DragState | null>(null)
const chartSvg = ref<SVGSVGElement | null>(null)
const scrollEl = ref<HTMLElement | null>(null)

/** 几何体点击类型菜单（零位移点击条体/菱形切换普通任务/里程碑） */
const typeMenu = ref<{ taskId: number; x: number; y: number } | null>(null)
const typeMenuEl = ref<HTMLElement | null>(null)
const typeMenuRow = computed(() => {
  const m = typeMenu.value
  return m ? rows.value.find((r) => r.id === m.taskId) ?? null : null
})
/** 菜单锚点：点击点偏移并钳制在图表区内，底部溢出时上翻 */
const typeMenuPos = computed(() => {
  const m = typeMenu.value
  if (!m) return { x: 0, y: 0 }
  const MENU_W = 148
  const MENU_H = 76
  return {
    x: Math.max(4, Math.min(m.x + 6, chartWidth.value - MENU_W - 4)),
    y: m.y + 8 + MENU_H > chartHeight.value ? Math.max(4, m.y - MENU_H - 8) : m.y + 8,
  }
})
function closeTypeMenu() {
  typeMenu.value = null
}
function selectType(milestone: boolean) {
  const row = typeMenuRow.value
  // 先取同日判定再关菜单（关菜单后 typeMenuRow 为 null）
  const sameDay = typeMenuSameDay.value
  closeTypeMenu()
  if (milestone && !sameDay) return
  if (row && (row.milestone === true) !== milestone) emit('milestoneChange', row, milestone)
}
/** 里程碑仅起止同日可设（跨日/未排期菜单项禁用） */
const typeMenuSameDay = computed(() => {
  const row = typeMenuRow.value
  if (!row) return false
  const geo = barById.value.get(row.id)?.geo
  return !!geo && diffDays(geo.start, geo.end) === 0
})
function onTypeMenuDocMouseDown(e: MouseEvent) {
  if (typeMenuEl.value && !typeMenuEl.value.contains(e.target as Node)) closeTypeMenu()
}
function onTypeMenuDocKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') closeTypeMenu()
}
watch(typeMenu, (open) => {
  if (open) {
    window.addEventListener('mousedown', onTypeMenuDocMouseDown)
    window.addEventListener('keydown', onTypeMenuDocKeydown)
  } else {
    window.removeEventListener('mousedown', onTypeMenuDocMouseDown)
    window.removeEventListener('keydown', onTypeMenuDocKeydown)
  }
})

/** 任务列宽：表头分隔条拖拽调整、双击复位；偏好本地记忆 */
const COL_WIDTH_STORAGE_KEY = 'protask_gantt_task_col_width'
function initialColWidth(): number {
  const raw = Number(localStorage.getItem(COL_WIDTH_STORAGE_KEY))
  if (!Number.isFinite(raw) || raw <= 0) return TASK_COL_DEFAULT_WIDTH
  return Math.min(TASK_COL_MAX_WIDTH, Math.max(TASK_COL_MIN_WIDTH, raw))
}
const taskColWidth = ref(initialColWidth())
const colResizing = ref(false)
/** 工期/开始/截止列模块整体显隐（工具栏「工期」按钮，偏好本地记忆） */
const DATES_STORAGE_KEY = 'protask_gantt_show_date_cols'
const showDateCols = ref(localStorage.getItem(DATES_STORAGE_KEY) !== '0')
watch(showDateCols, (v) => localStorage.setItem(DATES_STORAGE_KEY, v ? '1' : '0'))
/** 左表总宽 = 固定列 + 任务列（拖拽只改变任务列；日期模块隐藏时整体扣减） */
const leftWidth = computed(
  () => LEFT_FIXED_WIDTH + taskColWidth.value - (showDateCols.value ? 0 : DATE_MODULE_WIDTH),
)
/** 任务列右边界（分隔条/列参考线定位，相对左表） */
const colBoundaryX = computed(() => TASK_COL_BOUNDARY_OFFSET + taskColWidth.value)
watch(taskColWidth, (w) => localStorage.setItem(COL_WIDTH_STORAGE_KEY, String(w)))

let colResizeStart: { x: number; width: number } | null = null
function beginColResize(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  colResizeStart = { x: e.clientX, width: taskColWidth.value }
  colResizing.value = true
  window.addEventListener('mousemove', onColResizeMove)
  window.addEventListener('mouseup', onColResizeEnd)
}
function onColResizeMove(e: MouseEvent) {
  if (!colResizeStart) return
  const next = colResizeStart.width + (e.clientX - colResizeStart.x)
  taskColWidth.value = Math.min(TASK_COL_MAX_WIDTH, Math.max(TASK_COL_MIN_WIDTH, next))
}
function onColResizeEnd() {
  colResizeStart = null
  colResizing.value = false
  window.removeEventListener('mousemove', onColResizeMove)
  window.removeEventListener('mouseup', onColResizeEnd)
}
function resetColWidth() {
  taskColWidth.value = TASK_COL_DEFAULT_WIDTH
}

/** 监听可视区尺寸变化（窗口缩放/侧栏折叠/滚动条出现/列宽拖拽），重新铺满网格 */
function measureViewport() {
  const el = scrollEl.value
  if (el) viewportWidth.value = Math.max(0, el.clientWidth - leftWidth.value)
}
let resizeObserver: ResizeObserver | null = null
watch(scrollEl, (el) => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (!el) return
  measureViewport()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(measureViewport)
    resizeObserver.observe(el)
  }
})
watch(leftWidth, measureViewport)

/** 计算某行条体几何（拖拽中的任务用预览日期覆盖） */
function geoFor(row: TaskTreeNode): BarGeometry | null {
  const d = drag.value
  if (d && d.taskId === row.id && d.mode !== 'link') {
    const x = dateToX(d.curStart, range.value.start, pxPerDay.value)
    const days = durationDays(d.curStart, d.curEnd)
    return {
      x,
      width: Math.max(days * pxPerDay.value, pxPerDay.value),
      start: d.curStart,
      end: d.curEnd,
      milestone: row.milestone === true && !row.children.length,
      summary: row.children.length > 0,
    }
  }
  return barGeometry(row, dateIds.startId, dateIds.dueId, range.value.start, pxPerDay.value)
}

const bars = computed(() =>
  rows.value.map((row, index) => ({ row, index, geo: geoFor(row) })),
)

/** 左表行：附工期（自然日）/开始/截止，摘要行按子孙汇总，拖拽中随条体预览同步 */
const leftRows = computed(() =>
  bars.value.map(({ row, geo }) => {
    // 工期列：叶子任务显式工期优先（拖拽预览中仍按日期推导），其余按起止 diff+1
    const dragging = drag.value?.taskId === row.id && drag.value.mode !== 'link'
    const explicit = !dragging && !row.children.length && row.durationDays != null && row.durationDays > 0
    return {
      row,
      start: geo ? formatDate(geo.start) : '—',
      due: geo ? formatDate(geo.end) : '—',
      duration: geo ? (explicit ? row.durationDays! : durationDays(geo.start, geo.end)) : null,
    }
  }),
)
const barById = computed(() => {
  const map = new Map<number, { row: TaskTreeNode; index: number; geo: BarGeometry | null }>()
  for (const bar of bars.value) map.set(bar.row.id, bar)
  return map
})

/** 依赖连线（仅渲染两端均可见的依赖，按类型取锚点与箭头方向） */
const links = computed(() => {
  const result: { dep: DependencyVO; path: string; arrow: string; label: string }[] = []
  for (const dep of props.dependencies) {
    const pred = barById.value.get(dep.predecessorId)
    const succ = barById.value.get(dep.successorId)
    if (!pred?.geo || !succ?.geo) continue
    const { fromEnd, toEnd } = dependencyEnds(dep.type)
    const x1 = anchorX(pred.geo, fromEnd, pxPerDay.value)
    const y1 = pred.index * ROW_HEIGHT + ROW_HEIGHT / 2
    const x2 = anchorX(succ.geo, toEnd, pxPerDay.value)
    const y2 = succ.index * ROW_HEIGHT + ROW_HEIGHT / 2
    const dir = toEnd === 'start' ? 'right' : 'left'
    result.push({
      dep,
      path: dependencyPath(x1, y1, x2, y2, fromEnd, toEnd),
      arrow: arrowPoints(x2, y2, dir),
      label: dependencyLabel(dep),
    })
  }
  return result
})

/** 连线 tooltip：类型 + 延迟（如 SS +2天，负数为提前量） */
function dependencyLabel(dep: DependencyVO): string {
  const lag = dep.lag ? ` ${dep.lag > 0 ? '+' : ''}${dep.lag}${t('gantt.lagDay')}` : ''
  return `${dep.type}${lag}`
}

/** 拖拽建联的候选目标：按光标 y 定位行、x 相对条体中点判定左半=start/右半=finish */
const linkTarget = computed<{ row: TaskTreeNode; index: number; geo: BarGeometry; end: BarEnd } | null>(() => {
  const d = drag.value
  if (!d || d.mode !== 'link') return null
  const index = Math.floor(d.cursorY / ROW_HEIGHT)
  const row = rows.value[index]
  if (!row || row.id === d.taskId) return null
  const bar = barById.value.get(row.id)
  if (!bar?.geo) return null
  const mid = bar.geo.x + bar.geo.width / 2
  const end: BarEnd = d.cursorX < mid ? 'start' : 'finish'
  return { row, index, geo: bar.geo, end }
})

/** 目标半区高亮框（拖拽建联时指示将连接的锚点端） */
const linkHighlight = computed<{ x: number; width: number; y: number } | null>(() => {
  const target = linkTarget.value
  if (!target) return null
  const half = target.geo.width / 2
  return {
    x: target.end === 'start' ? target.geo.x : target.geo.x + half,
    width: half,
    y: target.index * ROW_HEIGHT,
  }
})

/** 拖拽连线时的临时路径：有候选目标时吸附到锚点并按类型走肘形，否则直连光标 */
const tempLink = computed(() => {
  const d = drag.value
  if (!d || d.mode !== 'link') return null
  const source = barById.value.get(d.taskId)
  if (!source?.geo) return null
  const x1 = anchorX(source.geo, d.sourceEnd, pxPerDay.value)
  const y1 = source.index * ROW_HEIGHT + ROW_HEIGHT / 2
  const target = linkTarget.value
  if (target) {
    const x2 = anchorX(target.geo, target.end, pxPerDay.value)
    const y2 = target.index * ROW_HEIGHT + ROW_HEIGHT / 2
    return dependencyPath(x1, y1, x2, y2, d.sourceEnd, target.end)
  }
  return `M ${x1} ${y1} L ${d.cursorX} ${d.cursorY}`
})

function chartPoint(e: MouseEvent): { x: number; y: number } {
  const rect = chartSvg.value?.getBoundingClientRect()
  if (!rect) return { x: 0, y: 0 }
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}

function beginDrag(e: MouseEvent, row: TaskTreeNode, mode: DragState['mode']) {
  if (!props.editable || row.children.length) return
  const geo = barById.value.get(row.id)?.geo
  if (!geo) return
  e.preventDefault()
  e.stopPropagation()
  const point = chartPoint(e)
  drag.value = {
    mode,
    taskId: row.id,
    origStart: geo.start,
    origEnd: geo.end,
    curStart: geo.start,
    curEnd: geo.end,
    startClientX: e.clientX,
    startClientY: e.clientY,
    cursorX: point.x,
    cursorY: point.y,
    sourceEnd: 'finish',
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function beginLink(e: MouseEvent, row: TaskTreeNode, end: BarEnd) {
  if (!props.editable) return
  const geo = barById.value.get(row.id)?.geo
  if (!geo) return
  e.preventDefault()
  e.stopPropagation()
  const point = chartPoint(e)
  drag.value = {
    mode: 'link',
    taskId: row.id,
    origStart: geo.start,
    origEnd: geo.end,
    curStart: geo.start,
    curEnd: geo.end,
    startClientX: e.clientX,
    startClientY: e.clientY,
    cursorX: point.x,
    cursorY: point.y,
    sourceEnd: end,
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e: MouseEvent) {
  const d = drag.value
  if (!d) return
  const point = chartPoint(e)
  d.cursorX = point.x
  d.cursorY = point.y
  if (d.mode === 'link') return
  const days = Math.round((e.clientX - d.startClientX) / pxPerDay.value)
  if (d.mode === 'move') {
    d.curStart = addDays(d.origStart, days)
    d.curEnd = addDays(d.origEnd, days)
  } else if (d.mode === 'resize-start') {
    const next = addDays(d.origStart, days)
    d.curStart = next > d.origEnd ? d.origEnd : next
    d.curEnd = d.origEnd
  } else {
    const next = addDays(d.origEnd, days)
    d.curEnd = next < d.origStart ? d.origStart : next
    d.curStart = d.origStart
  }
}

function onDragEnd(e: MouseEvent) {
  const d = drag.value
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
  drag.value = null
  if (!d) return
  const row = rows.value.find((r) => r.id === d.taskId)
  if (!row) return
  if (d.mode === 'link') {
    const point = chartPoint(e)
    const targetIndex = Math.floor(point.y / ROW_HEIGHT)
    const target = rows.value[targetIndex]
    const targetBar = target ? barById.value.get(target.id) : null
    if (target && target.id !== d.taskId && targetBar?.geo) {
      // 落点左半=开始锚点 / 右半=完成锚点，与源端组合出 FS/SS/FF/SF
      const mid = targetBar.geo.x + targetBar.geo.width / 2
      const toEnd: BarEnd = point.x < mid ? 'start' : 'finish'
      emit('dependencyCreate', d.taskId, target.id, dependencyType(d.sourceEnd, toEnd))
    }
    return
  }
  const dateChanged = formatDate(d.curStart) !== formatDate(d.origStart) || formatDate(d.curEnd) !== formatDate(d.origEnd)
  // 零位移点击条体/菱形 → 类型菜单（普通任务/里程碑切换）；拖拽改期优先
  if (d.mode === 'move' && !dateChanged && Math.hypot(e.clientX - d.startClientX, e.clientY - d.startClientY) < 3) {
    const point = chartPoint(e)
    typeMenu.value = { taskId: row.id, x: point.x, y: point.y }
    return
  }
  if (dateChanged) {
    emit('scheduleChange', row, formatDate(d.curStart), formatDate(d.curEnd))
  }
}

function handleLinkClick(dep: DependencyVO) {
  if (!props.editable) return
  emit('dependencyDelete', dep)
}

function scrollToToday() {
  const el = scrollEl.value
  if (!el || todayX.value == null) return
  el.scrollLeft = Math.max(0, todayX.value - el.clientWidth / 2 + leftWidth.value / 2)
}

function rowY(index: number): number {
  return index * ROW_HEIGHT
}

/** 摘要条轮廓：长条与两端三角下标合并为单一多边形（共享边拼接在半像素滚动/缩放下会露缝） */
function summaryPoints(x: number, width: number, midY: number): string {
  const cap = Math.min(6, width / 2)
  const top = midY - 3
  const bottom = midY + 3
  const tip = midY + 9
  const right = x + width
  return `${x},${top} ${right},${top} ${right},${tip} ${right - cap},${bottom} ${x + cap},${bottom} ${x},${tip}`
}

/** 里程碑菱形顶点（中心 cx,cy，半径 8） */
function milestonePoints(cx: number, cy: number): string {
  const r = 8
  return `${cx},${cy - r} ${cx + r},${cy} ${cx},${cy + r} ${cx - r},${cy}`
}

/** 双端连接点几何：普通条悬于条首/条尾外，里程碑悬于菱形左右顶点外（与拉伸手柄留出间隙） */
function connectorsFor(bar: { row: TaskTreeNode; index: number; geo: BarGeometry | null }): { end: BarEnd; stemX1: number; stemX2: number; dotX: number; cy: number }[] {
  const geo = bar.geo
  if (!geo) return []
  const cy = rowY(bar.index) + ROW_HEIGHT / 2
  if (geo.milestone) {
    const cx = geo.x + pxPerDay.value / 2
    const r = 8
    return [
      { end: 'start', stemX1: cx - r, stemX2: cx - r - BAR_CONNECTOR_OFFSET, dotX: cx - r - BAR_CONNECTOR_OFFSET, cy },
      { end: 'finish', stemX1: cx + r, stemX2: cx + r + BAR_CONNECTOR_OFFSET, dotX: cx + r + BAR_CONNECTOR_OFFSET, cy },
    ]
  }
  return [
    { end: 'start', stemX1: geo.x, stemX2: geo.x - BAR_CONNECTOR_OFFSET, dotX: geo.x - BAR_CONNECTOR_OFFSET, cy },
    { end: 'finish', stemX1: geo.x + geo.width, stemX2: geo.x + geo.width + BAR_CONNECTOR_OFFSET, dotX: geo.x + geo.width + BAR_CONNECTOR_OFFSET, cy },
  ]
}

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
  window.removeEventListener('mousemove', onColResizeMove)
  window.removeEventListener('mouseup', onColResizeEnd)
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <el-card class="gantt-card" :class="{ 'col-resizing': colResizing }" v-loading="loading">
    <!-- 门禁：未添加 WBS 编码属性 -->
    <el-empty v-if="!hasWbs" :description="t('gantt.gated')" :image-size="80" />

    <template v-else>
      <!-- 工具栏：缩放 / 今天 / 导出 / 新建 -->
      <div class="gantt-toolbar">
        <div class="gantt-toolbar-left">
          <el-radio-group
            :model-value="scale"
            size="small"
            @update:model-value="emit('scaleChange', $event as GanttScale)"
          >
            <el-radio-button v-for="s in SCALES" :key="s" :value="s">
              {{ t(`gantt.scale${s.charAt(0).toUpperCase() + s.slice(1)}`) }}
            </el-radio-button>
          </el-radio-group>

          <el-button size="small" :icon="ZoomIn" @click="scrollToToday">
            {{ t('gantt.today') }}
          </el-button>

          <el-button
            size="small"
            :icon="Calendar"
            :type="showDateCols ? 'primary' : 'default'"
            :title="t('gantt.datesToggleHint')"
            @click="showDateCols = !showDateCols"
          >
            {{ t('gantt.datesToggle') }}
          </el-button>
        </div>

        <div class="gantt-toolbar-right">
          <el-button v-if="editable" size="small" :icon="Plus" @click="emit('quickAdd')">
            {{ t('projectDetail.newTask') }}
          </el-button>
          <el-button size="small" type="primary" :icon="Download" @click="emit('export')">
            {{ t('gantt.export') }}
          </el-button>
        </div>
      </div>

      <!-- 图例 -->
      <div class="gantt-legend">
        <span class="legend-item"><i class="legend-swatch" :style="{ background: DEFAULT_COLORS.todo }" />{{ t('gantt.legendTodo') }}</span>
        <span class="legend-item"><i class="legend-swatch" :style="{ background: DEFAULT_COLORS.in_progress }" />{{ t('gantt.legendInProgress') }}</span>
        <span class="legend-item"><i class="legend-swatch" :style="{ background: DEFAULT_COLORS.done }" />{{ t('gantt.legendDone') }}</span>
        <span class="legend-item"><i class="legend-diamond" />{{ t('gantt.legendMilestone') }}</span>
        <span class="legend-item"><i class="legend-summary" />{{ t('gantt.legendSummary') }}</span>
        <span class="legend-item"><i class="legend-swatch legend-auto" />{{ t('gantt.autoTask') }}</span>
      </div>

      <el-empty v-if="!scheduleBound" :description="t('gantt.noDateProperty')" :image-size="70" />
      <el-empty v-else-if="!rows.length" :description="t('task.empty')" :image-size="70" />

      <!-- 主体：左 sticky 任务表 + 右 SVG 时间轴，统一滚动 -->
      <div v-else ref="scrollEl" class="gantt-scroll" @scroll="closeTypeMenu">
        <div
          class="gantt-grid"
          :style="{
            gridTemplateColumns: `${leftWidth}px ${chartWidth}px`,
            gridTemplateRows: `${HEADER_HEIGHT}px ${chartHeight}px`,
          }"
        >
          <!-- 左上角：与左表列宽一一对齐 -->
          <div class="gantt-corner">
            <span class="corner-indent" />
            <span class="corner-toggle" />
            <!-- WBS 列不显示表头名称，保留占位宽度维持列对齐 -->
            <span class="corner-wbs" />
            <span class="corner-task" :style="{ width: `${taskColWidth}px` }">{{ t('gantt.taskColumn') }}</span>
            <span
              class="col-resizer"
              :class="{ active: colResizing }"
              :style="{ left: `${colBoundaryX - 4}px` }"
              :title="t('gantt.resizeTaskCol')"
              @mousedown="beginColResize"
              @dblclick="resetColWidth"
            />
            <template v-if="showDateCols">
              <span class="corner-duration">{{ t('gantt.durationColumn') }}</span>
              <span class="corner-start">{{ t('gantt.startColumn') }}</span>
              <span class="corner-due">{{ t('gantt.dueColumn') }}</span>
            </template>
          </div>

          <!-- 右上时间轴表头 -->
          <div class="gantt-header">
            <svg :width="chartWidth" :height="HEADER_HEIGHT" class="header-svg">
              <g>
                <template v-for="(m, i) in months" :key="`m${i}`">
                  <rect :x="m.x" :y="0" :width="m.width" :height="MONTH_ROW_HEIGHT" class="month-cell" />
                  <text :x="m.x + 6" :y="MONTH_ROW_HEIGHT / 2 + 4" class="month-text">{{ m.label }}</text>
                </template>
              </g>
              <g>
                <template v-for="(c, i) in columns" :key="`c${i}`">
                  <rect
                    :x="c.x"
                    :y="MONTH_ROW_HEIGHT"
                    :width="c.width"
                    :height="HEADER_HEIGHT - MONTH_ROW_HEIGHT"
                    :class="['col-cell', { weekend: c.weekend }]"
                  />
                  <text
                    :x="c.x + c.width / 2"
                    :y="MONTH_ROW_HEIGHT + (HEADER_HEIGHT - MONTH_ROW_HEIGHT) / 2 + 4"
                    class="col-text"
                    text-anchor="middle"
                  >{{ c.label }}</text>
                </template>
              </g>
            </svg>
          </div>

          <!-- 左下任务表 -->
          <div class="gantt-left">
            <div
              class="col-guide"
              :class="{ active: colResizing }"
              :style="{ left: `${colBoundaryX - 3}px` }"
              :title="t('gantt.resizeTaskCol')"
              @mousedown="beginColResize"
              @dblclick="resetColWidth"
            />
            <div
              v-for="entry in leftRows"
              :key="entry.row.id"
              class="gantt-row-left"
              :style="{ height: `${ROW_HEIGHT}px` }"
              @click="emit('open', entry.row)"
            >
              <span class="row-indent" :style="{ width: `${entry.row.depth * 16}px` }" />
              <button
                v-if="entry.row.children.length"
                class="row-toggle"
                :title="collapsed.has(entry.row.id) ? t('gantt.expand') : t('gantt.collapse')"
                @click.stop="toggleCollapse(entry.row.id)"
              >
                <span class="toggle-caret" :class="{ collapsed: collapsed.has(entry.row.id) }">▾</span>
              </button>
              <span v-else class="row-toggle-placeholder" />
              <span class="row-wbs">{{ entry.row.wbs || '—' }}</span>
              <span class="row-title" :style="{ width: `${taskColWidth}px` }">
                <span v-if="entry.row.icon" class="row-icon">{{ entry.row.icon }}</span>
                <span class="row-title-text" :class="{ 'is-summary': entry.row.children.length > 0 }" :title="entry.row.title">{{ entry.row.title }}</span>
              </span>
              <template v-if="showDateCols">
                <span class="row-duration">{{ entry.duration != null ? t('gantt.durationDays', { count: entry.duration }) : '—' }}</span>
                <span class="row-date">{{ entry.start }}</span>
                <span class="row-date">{{ entry.due }}</span>
              </template>
            </div>
          </div>

          <!-- 右下 SVG 图表 -->
          <div class="gantt-chart">
            <svg ref="chartSvg" :width="chartWidth" :height="chartHeight" class="chart-svg">
              <!-- 周末底纹 -->
              <rect
                v-for="(w, i) in weekendRects"
                :key="`w${i}`"
                :x="w.x"
                :y="0"
                :width="w.width"
                :height="chartHeight"
                class="weekend-shade"
              />
              <!-- 列分隔线 -->
              <line
                v-for="(c, i) in columns"
                :key="`gl${i}`"
                :x1="c.x"
                :y1="0"
                :x2="c.x"
                :y2="chartHeight"
                class="grid-line"
              />
              <!-- 行分隔线 -->
              <line
                v-for="i in rows.length"
                :key="`rl${i}`"
                :x1="0"
                :y1="rowY(i)"
                :x2="chartWidth"
                :y2="rowY(i)"
                class="grid-line row-line"
              />
              <!-- 今日线 -->
              <line
                v-if="todayX != null"
                :x1="todayX"
                :y1="0"
                :x2="todayX"
                :y2="chartHeight"
                class="today-line"
              />

              <!-- 依赖连线（四类型，tooltip 显示类型+延迟，点击删除） -->
              <g v-for="link in links" :key="`dep${link.dep.id}`" class="dep-group" @click="handleLinkClick(link.dep)">
                <title>{{ link.label }}</title>
                <path :d="link.path" class="dep-hit" />
                <path :d="link.path" class="dep-line" />
                <polygon :points="link.arrow" class="dep-arrow" />
              </g>
              <!-- 拖拽建联的目标半区高亮 -->
              <rect
                v-if="linkHighlight"
                :x="linkHighlight.x"
                :y="linkHighlight.y"
                :width="linkHighlight.width"
                :height="ROW_HEIGHT"
                class="link-target-highlight"
              />
              <!-- 拖拽中的临时连线 -->
              <path v-if="tempLink" :d="tempLink" class="dep-temp" />

              <!-- 条体 -->
              <g v-for="bar in bars" :key="`bar${bar.row.id}`">
                <template v-if="bar.geo">
                  <!-- 里程碑菱形（双端连接点，锚点均在菱形中心） -->
                  <g v-if="bar.geo.milestone" class="bar-group">
                    <polygon
                      :points="milestonePoints(bar.geo.x + pxPerDay / 2, rowY(bar.index) + ROW_HEIGHT / 2)"
                      class="bar-milestone"
                      :class="{ 'bar-auto': bar.row.scheduleMode === 'auto' }"
                      @mousedown="beginDrag($event, bar.row, 'move')"
                    />
                    <template v-if="editable">
                      <template v-for="c in connectorsFor(bar)" :key="c.end">
                        <line
                          :x1="c.stemX1"
                          :y1="c.cy"
                          :x2="c.stemX2"
                          :y2="c.cy"
                          class="bar-connector-stem"
                          :class="{ active: drag && drag.mode === 'link' && drag.taskId === bar.row.id }"
                        />
                        <circle
                          :cx="c.dotX"
                          :cy="c.cy"
                          :r="BAR_CONNECTOR_RADIUS"
                          class="bar-connector"
                          :class="{ active: drag && drag.mode === 'link' && drag.taskId === bar.row.id }"
                          :title="t('gantt.connectorHint')"
                          @mousedown="beginLink($event, bar.row, c.end)"
                        />
                      </template>
                    </template>
                  </g>
                  <!-- 摘要任务汇总条：长条 + 两端三角下标单一多边形，避免拼接缝（无连接点） -->
                  <g v-else-if="bar.geo.summary" :title="t('gantt.summaryHint')">
                    <polygon
                      :points="summaryPoints(bar.geo.x, bar.geo.width, rowY(bar.index) + ROW_HEIGHT / 2)"
                      class="bar-summary"
                    />
                  </g>
                  <!-- 普通条体 -->
                  <g v-else class="bar-group">
                    <rect
                      :x="bar.geo.x"
                      :y="rowY(bar.index) + (ROW_HEIGHT - BAR_HEIGHT) / 2"
                      :width="bar.geo.width"
                      :height="BAR_HEIGHT"
                      :rx="3"
                      class="bar-body"
                      :class="{ 'bar-auto': bar.row.scheduleMode === 'auto' }"
                      :style="{ fill: barFill(bar.row) }"
                      :title="bar.row.scheduleMode === 'auto' ? `${bar.row.title} · ${t('gantt.autoTask')}` : bar.row.title"
                      @mousedown="beginDrag($event, bar.row, 'move')"
                    />
                    <!-- 左右拉伸手柄 + 双端连接点（拖拽创建四类型依赖，悬于端外不遮挡手柄） -->
                    <template v-if="editable">
                      <rect
                        :x="bar.geo.x - 3"
                        :y="rowY(bar.index) + (ROW_HEIGHT - BAR_HEIGHT) / 2"
                        :width="7"
                        :height="BAR_HEIGHT"
                        class="bar-handle"
                        @mousedown="beginDrag($event, bar.row, 'resize-start')"
                      />
                      <rect
                        :x="bar.geo.x + bar.geo.width - 4"
                        :y="rowY(bar.index) + (ROW_HEIGHT - BAR_HEIGHT) / 2"
                        :width="7"
                        :height="BAR_HEIGHT"
                        class="bar-handle"
                        @mousedown="beginDrag($event, bar.row, 'resize-end')"
                      />
                      <template v-for="c in connectorsFor(bar)" :key="c.end">
                        <line
                          :x1="c.stemX1"
                          :y1="c.cy"
                          :x2="c.stemX2"
                          :y2="c.cy"
                          class="bar-connector-stem"
                          :class="{ active: drag && drag.mode === 'link' && drag.taskId === bar.row.id }"
                        />
                        <circle
                          :cx="c.dotX"
                          :cy="c.cy"
                          :r="BAR_CONNECTOR_RADIUS"
                          class="bar-connector"
                          :class="{ active: drag && drag.mode === 'link' && drag.taskId === bar.row.id }"
                          :title="t('gantt.connectorHint')"
                          @mousedown="beginLink($event, bar.row, c.end)"
                        />
                      </template>
                    </template>
                  </g>
                </template>
                <!-- 未排期提示点 -->
                <text
                  v-else
                  :x="6"
                  :y="rowY(bar.index) + ROW_HEIGHT / 2 + 4"
                  class="unscheduled-text"
                >{{ t('gantt.unscheduled') }}</text>
              </g>
            </svg>
            <!-- 几何体点击类型菜单：普通任务 / 里程碑 -->
            <div
              v-if="typeMenu && typeMenuRow"
              ref="typeMenuEl"
              class="bar-type-menu"
              :style="{ left: `${typeMenuPos.x}px`, top: `${typeMenuPos.y}px` }"
            >
              <button
                type="button"
                class="bar-type-option"
                :class="{ current: typeMenuRow.milestone !== true }"
                @click="selectType(false)"
              >
                <el-icon class="option-check" :class="{ invisible: typeMenuRow.milestone === true }"><Check /></el-icon>
                {{ t('gantt.typeNormal') }}
              </button>
              <button
                type="button"
                class="bar-type-option"
                :class="{ current: typeMenuRow.milestone === true }"
                :disabled="!typeMenuSameDay"
                :title="!typeMenuSameDay ? t('task.milestoneSameDayHint') : undefined"
                @click="selectType(true)"
              >
                <el-icon class="option-check" :class="{ invisible: typeMenuRow.milestone !== true }"><Check /></el-icon>
                {{ t('gantt.typeMilestone') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
.gantt-card {
  --gantt-border: var(--el-border-color-lighter);
  --gantt-grid: var(--el-border-color-lighter);
  --gantt-weekend: var(--el-fill-color-light);
  --gantt-header-bg: var(--el-fill-color-light);
  --gantt-left-bg: var(--el-bg-color);
}

.gantt-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.gantt-toolbar-left,
.gantt-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.gantt-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-swatch {
  width: 14px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}

/* 自动计划图例：斜纹填充 + 虚线描边，与条体 bar-auto 样式呼应 */
.legend-auto {
  background: repeating-linear-gradient(
    45deg,
    var(--el-color-primary) 0 3px,
    var(--el-color-primary-light-7) 3px 6px
  );
  border: 1px dashed var(--el-color-primary);
}

.legend-diamond {
  width: 10px;
  height: 10px;
  background: var(--el-color-warning);
  transform: rotate(45deg);
  display: inline-block;
}

.legend-summary {
  width: 18px;
  height: 10px;
  background: var(--app-text-regular);
  display: inline-block;
  /* 与摘要条同轮廓：长条 + 两端三角下标 */
  clip-path: polygon(0 0, 100% 0, 100% 100%, calc(100% - 4px) 50%, 4px 50%, 0 100%);
}

.gantt-scroll {
  overflow: auto;
  max-height: 68vh;
  border: 1px solid var(--gantt-border);
  border-radius: 8px;
  position: relative;
  background: var(--gantt-left-bg);
}

.gantt-grid {
  display: grid;
  width: max-content;
}

.gantt-corner {
  position: sticky;
  top: 0;
  left: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  background: var(--gantt-header-bg);
  border-right: 1px solid var(--gantt-border);
  border-bottom: 1px solid var(--gantt-border);
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
}

.corner-indent {
  width: 0;
  flex-shrink: 0;
}

.corner-toggle {
  width: 18px;
  flex-shrink: 0;
}

.corner-wbs {
  width: 40px;
  flex-shrink: 0;
}

.corner-duration {
  width: 48px;
  flex-shrink: 0;
  text-align: center;
}

.corner-start,
.corner-due {
  width: 76px;
  flex-shrink: 0;
  text-align: center;
}

.corner-task {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gantt-header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: var(--gantt-header-bg);
  border-bottom: 1px solid var(--gantt-border);
}

.header-svg {
  display: block;
}

.month-cell {
  fill: var(--gantt-header-bg);
  stroke: var(--gantt-grid);
  stroke-width: 1;
}

.month-text {
  font-size: 12px;
  font-weight: 600;
  fill: var(--app-text-regular);
}

.col-cell {
  fill: transparent;
  stroke: var(--gantt-grid);
  stroke-width: 1;
}

.col-cell.weekend {
  fill: var(--gantt-weekend);
}

.col-text {
  font-size: 11px;
  fill: var(--app-text-secondary);
}

.gantt-left {
  position: sticky;
  left: 0;
  z-index: 10;
  background: var(--gantt-left-bg);
  border-right: 1px solid var(--gantt-border);
}

/* 任务列宽分隔：表头拖拽手柄 + 全高列参考线（亦可拖拽/双击复位） */
.col-resizer {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 8px;
  cursor: col-resize;
  z-index: 5;
}

.col-resizer::after {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 3px;
  width: 2px;
  border-radius: 1px;
  /* 默认隐藏，悬停/拖拽时浮现 */
  background: transparent;
  transition: background-color 0.15s ease;
}

.col-resizer:hover::after,
.col-resizer.active::after {
  background: var(--el-color-primary);
}

.col-guide {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 5;
}

.col-guide::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 2px;
  width: 1px;
  /* 默认隐藏，悬停/拖拽时浮现 */
  background: transparent;
  transition: background-color 0.15s ease;
}

.col-guide:hover::after,
.col-guide.active::after {
  background: var(--el-color-primary);
}

.gantt-card.col-resizing {
  cursor: col-resize;
  user-select: none;
}

.gantt-row-left {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  border-bottom: 1px solid var(--gantt-grid);
  cursor: pointer;
  font-size: 13px;
  box-sizing: border-box;
}

.gantt-row-left:hover {
  background: var(--el-color-primary-light-9);
}

.row-indent {
  flex-shrink: 0;
}

.row-toggle {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--app-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.toggle-caret {
  transition: transform 0.15s;
  font-size: 11px;
}

.toggle-caret.collapsed {
  transform: rotate(-90deg);
}

.row-toggle-placeholder {
  width: 18px;
  flex-shrink: 0;
}

.row-wbs {
  width: 40px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
}

.row-duration {
  width: 48px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.row-date {
  width: 76px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.row-title {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
}

.row-icon {
  flex-shrink: 0;
}

/* 摘要任务（含子孙）名称加粗，与叶子任务形成层级节奏 */
.row-title-text.is-summary {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.row-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gantt-chart {
  position: relative;
}

/* 几何体点击类型菜单（普通任务 / 里程碑） */
.bar-type-menu {
  position: absolute;
  z-index: 30;
  display: flex;
  flex-direction: column;
  min-width: 148px;
  padding: 4px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
}

.bar-type-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border: none;
  background: transparent;
  border-radius: 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  text-align: left;
  cursor: pointer;
}

.bar-type-option:hover {
  background: var(--el-fill-color-light);
}

.bar-type-option:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.bar-type-option:disabled:hover {
  background: none;
}

.bar-type-option.current {
  color: var(--el-color-primary);
  font-weight: 600;
}

.option-check {
  width: 14px;
  height: 14px;
}

.option-check.invisible {
  visibility: hidden;
}

.chart-svg {
  display: block;
}

.weekend-shade {
  fill: var(--gantt-weekend);
  opacity: 0.5;
}

.grid-line {
  stroke: var(--gantt-grid);
  stroke-width: 1;
}

.row-line {
  opacity: 0.6;
}

.today-line {
  stroke: var(--el-color-danger);
  stroke-width: 1.5;
  stroke-dasharray: 4 3;
}

.bar-body {
  stroke: rgba(0, 0, 0, 0.18);
  stroke-width: 1;
  cursor: grab;
}

.bar-body:active {
  cursor: grabbing;
}

/* 自动计划条体：虚线描边 + 半透明填充，区别于手动条（拖拽机制不变，落库后吸附到实际日期） */
.bar-auto {
  stroke: var(--el-color-primary);
  stroke-width: 1.5;
  stroke-dasharray: 5 3;
  fill-opacity: 0.75;
}

.bar-handle {
  fill: transparent;
  cursor: ew-resize;
}

.bar-handle:hover {
  fill: rgba(0, 0, 0, 0.12);
}

.bar-connector {
  fill: var(--el-color-primary);
  stroke: var(--el-bg-color);
  stroke-width: 1.5;
  cursor: crosshair;
  /* 默认隐藏；停留条体淡色浮现，悬停圆点/拖拽连线时高亮放大 */
  opacity: 0;
  transition: opacity 0.15s, transform 0.15s;
  transform-box: fill-box;
  transform-origin: center;
}

.bar-group:hover .bar-connector:not(:hover):not(.active) {
  opacity: 0.45;
}

.bar-connector:hover,
.bar-connector.active {
  opacity: 1;
  transform: scale(1.35);
}

/* 条尾 → 连接点短横线（纯视觉桥接，不拦截手柄事件） */
.bar-connector-stem {
  stroke: var(--el-color-primary);
  stroke-width: 1.5;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s;
}

.bar-group:hover .bar-connector-stem:not(.active) {
  opacity: 0.45;
}

.bar-connector-stem.active {
  opacity: 1;
}

.bar-milestone {
  /* 与图例注释菱形同配色（warning 橙） */
  fill: var(--el-color-warning);
  stroke: rgba(0, 0, 0, 0.25);
  stroke-width: 1;
  cursor: grab;
}

.bar-summary {
  fill: var(--app-text-regular);
}

.dep-group {
  cursor: pointer;
}

.dep-hit {
  fill: none;
  stroke: transparent;
  stroke-width: 12;
}

.dep-line {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 1.5;
}

.dep-group:hover .dep-line {
  stroke: var(--el-color-danger);
  stroke-width: 2;
}

.dep-arrow {
  fill: var(--el-color-primary);
}

.dep-group:hover .dep-arrow {
  fill: var(--el-color-danger);
}

.dep-temp {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 1.5;
  stroke-dasharray: 5 4;
  pointer-events: none;
}

/* 拖拽建联时目标半区高亮（指示将连接的锚点端，不拦截事件） */
.link-target-highlight {
  fill: var(--el-color-primary);
  opacity: 0.12;
  pointer-events: none;
}

.unscheduled-text {
  font-size: 11px;
  fill: var(--app-text-placeholder);
}

/* 自定义滚动条 */
.gantt-scroll::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.gantt-scroll::-webkit-scrollbar-thumb {
  background: var(--el-border-color);
  border-radius: 5px;
  border: 2px solid var(--gantt-left-bg);
}

.gantt-scroll::-webkit-scrollbar-thumb:hover {
  background: var(--el-text-color-placeholder);
}

.gantt-scroll::-webkit-scrollbar-corner {
  background: transparent;
}
</style>