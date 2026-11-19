import { describe, expect, it } from 'vitest'
import {
  BAR_CONNECTOR_OFFSET,
  BAR_CONNECTOR_RADIUS,
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
  effectiveDates,
  elbowPath,
  fitRangeToWidth,
  formatDate,
  isWeekend,
  monthLabel,
  parseDate,
  startOfDay,
  taskDates,
  xToDate,
} from '@/utils/gantt'
import type { TaskVO } from '@/types'
import type { TaskTreeNode } from '@/utils/taskTree'

/** 构造叶子任务节点 */
function leaf(id: number, properties: Record<string, unknown> = {}, extra: Partial<TaskVO> = {}): TaskTreeNode {
  const task: TaskVO = {
    id,
    projectId: 1,
    title: `T${id}`,
    creatorId: 1,
    createdAt: '2026-01-01 10:00:00',
    updatedAt: '2026-01-01 10:00:00',
    properties,
  }
  return { ...task, ...extra, children: [], depth: 0 }
}

/** 构造摘要节点（含子孙） */
function summary(id: number, children: TaskTreeNode[], properties: Record<string, unknown> = {}): TaskTreeNode {
  return { ...leaf(id, properties), children, depth: 0 }
}

describe('durationDays / monthLabel', () => {
  it('工期按自然日计算（含起止当天），与条体宽度语义一致', () => {
    const start = parseDate('2026-03-02') as Date
    expect(durationDays(start, parseDate('2026-03-04') as Date)).toBe(3)
    expect(durationDays(start, start)).toBe(1) // 同日工期 1 天
    expect(durationDays(start, parseDate('2026-04-01') as Date)).toBe(31) // 跨月
  })

  it('月份标签随语言环境切换（含年/不含年）', () => {
    const d = parseDate('2026-11-17') as Date
    expect(monthLabel(d, 'zh-CN', true)).toBe('2026年11月')
    expect(monthLabel(d, 'zh-CN', false)).toBe('11月')
    expect(monthLabel(d, 'en-US', true)).toBe('Nov 2026')
    expect(monthLabel(d, 'en-US', false)).toBe('Nov')
  })
})

describe('日期基础工具', () => {
  it('parseDate 解析 YYYY-MM-DD 为本地零点，兼容带时间的历史值', () => {
    const d = parseDate('2026-03-15')
    expect(d).not.toBeNull()
    expect(formatDate(d as Date)).toBe('2026-03-15')
    expect((d as Date).getHours()).toBe(0)
    expect(formatDate(parseDate('2026-03-15 18:30:00') as Date)).toBe('2026-03-15')
  })

  it('parseDate 非法/空值返回 null', () => {
    expect(parseDate(null)).toBeNull()
    expect(parseDate('')).toBeNull()
    expect(parseDate('not-a-date')).toBeNull()
    expect(parseDate('2026-3-5')).toBeNull() // 月/日需两位，格式不匹配
  })

  it('addDays / diffDays 互逆且跨月正确', () => {
    const base = parseDate('2026-01-30') as Date
    expect(formatDate(addDays(base, 3))).toBe('2026-02-02')
    expect(diffDays(base, addDays(base, 3))).toBe(3)
    expect(diffDays(addDays(base, 3), base)).toBe(-3)
  })

  it('startOfDay 归零时分秒', () => {
    const d = new Date(2026, 4, 20, 15, 45, 30)
    expect(startOfDay(d).getHours()).toBe(0)
    expect(startOfDay(d).getMinutes()).toBe(0)
  })

  it('isWeekend 识别周六/周日', () => {
    expect(isWeekend(parseDate('2026-03-14') as Date)).toBe(true) // 周六
    expect(isWeekend(parseDate('2026-03-15') as Date)).toBe(true) // 周日
    expect(isWeekend(parseDate('2026-03-16') as Date)).toBe(false) // 周一
  })

  it('dateToX / xToDate 按天吸附互逆', () => {
    const rangeStart = parseDate('2026-03-01') as Date
    const x = dateToX(parseDate('2026-03-08') as Date, rangeStart, 14)
    expect(x).toBe(7 * 14)
    expect(formatDate(xToDate(x, rangeStart, 14))).toBe('2026-03-08')
  })
})

describe('taskDates / effectiveDates', () => {
  it('叶子取自身属性，开始/截止缺省互相回退', () => {
    expect(taskDates(leaf(1, { startDate: '2026-03-01', dueDate: '2026-03-05' }), 'startDate', 'dueDate')).toEqual({
      start: parseDate('2026-03-01'),
      end: parseDate('2026-03-05'),
    })
    const onlyDue = taskDates(leaf(2, { dueDate: '2026-03-09' }), 'startDate', 'dueDate')
    expect(formatDate(onlyDue.start as Date)).toBe('2026-03-09')
    expect(formatDate(onlyDue.end as Date)).toBe('2026-03-09')
  })

  it('摘要任务日期由子孙 min/max 推导', () => {
    const node = summary(1, [
      leaf(2, { startDate: '2026-03-05', dueDate: '2026-03-08' }),
      leaf(3, { startDate: '2026-03-01', dueDate: '2026-03-12' }),
    ])
    const { start, end } = effectiveDates(node, 'startDate', 'dueDate')
    expect(formatDate(start as Date)).toBe('2026-03-01')
    expect(formatDate(end as Date)).toBe('2026-03-12')
  })

  it('摘要自身日期也纳入汇总', () => {
    const node = summary(
      1,
      [leaf(2, { startDate: '2026-03-05', dueDate: '2026-03-08' })],
      { startDate: '2026-03-01', dueDate: '2026-03-20' },
    )
    const { start, end } = effectiveDates(node, 'startDate', 'dueDate')
    expect(formatDate(start as Date)).toBe('2026-03-01')
    expect(formatDate(end as Date)).toBe('2026-03-20')
  })
})

describe('barGeometry', () => {
  const rangeStart = parseDate('2026-03-01') as Date

  it('宽度含起止当天（天数差 + 1）', () => {
    const geo = barGeometry(leaf(1, { startDate: '2026-03-02', dueDate: '2026-03-04' }), 'startDate', 'dueDate', rangeStart, 10)
    expect(geo).not.toBeNull()
    expect(geo?.x).toBe(1 * 10)
    expect(geo?.width).toBe(3 * 10)
    expect(geo?.milestone).toBe(false)
    expect(geo?.summary).toBe(false)
  })

  it('未标记同日任务为 1 天短条（非里程碑）', () => {
    const geo = barGeometry(leaf(1, { startDate: '2026-03-02', dueDate: '2026-03-02' }), 'startDate', 'dueDate', rangeStart, 10)
    expect(geo?.milestone).toBe(false)
    expect(geo?.width).toBe(10)
  })

  it('显式标记同日任务为里程碑', () => {
    const geo = barGeometry(leaf(1, { startDate: '2026-03-02', dueDate: '2026-03-02' }, { milestone: true }), 'startDate', 'dueDate', rangeStart, 10)
    expect(geo?.milestone).toBe(true)
  })

  it('显式标记跨日任务仍为里程碑', () => {
    const geo = barGeometry(leaf(1, { startDate: '2026-03-02', dueDate: '2026-03-05' }, { milestone: true }), 'startDate', 'dueDate', rangeStart, 10)
    expect(geo?.milestone).toBe(true)
  })

  it('有子孙标记为摘要（即使同日）', () => {
    const node = summary(1, [leaf(2, { startDate: '2026-03-02', dueDate: '2026-03-02' })])
    const geo = barGeometry(node, 'startDate', 'dueDate', rangeStart, 10)
    expect(geo?.summary).toBe(true)
    expect(geo?.milestone).toBe(false)
  })

  it('标记但有子孙时摘要优先', () => {
    const node = { ...summary(1, [leaf(2, { startDate: '2026-03-02', dueDate: '2026-03-02' })]), milestone: true }
    const geo = barGeometry(node, 'startDate', 'dueDate', rangeStart, 10)
    expect(geo?.summary).toBe(true)
    expect(geo?.milestone).toBe(false)
  })

  it('无日期返回 null', () => {
    expect(barGeometry(leaf(1), 'startDate', 'dueDate', rangeStart, 10)).toBeNull()
  })
})

describe('computeRange', () => {
  it('取全部已排期任务 min/max 并左右留白', () => {
    const rows = [
      leaf(1, { startDate: '2026-03-10', dueDate: '2026-03-12' }),
      leaf(2, { startDate: '2026-03-05', dueDate: '2026-03-20' }),
    ]
    const range = computeRange(rows, 'startDate', 'dueDate', 7)
    expect(formatDate(range.start)).toBe('2026-02-26') // 03-05 前 7 天
    expect(formatDate(range.end)).toBe('2026-03-27') // 03-20 后 7 天
    expect(range.totalDays).toBe(diffDays(range.start, range.end) + 1)
  })

  it('无排期任务时以今天为中心展开默认窗口', () => {
    const range = computeRange([leaf(1)], 'startDate', 'dueDate', 7)
    expect(range.totalDays).toBeGreaterThan(0)
    expect(range.start.getTime()).toBeLessThanOrEqual(range.end.getTime())
  })
})

describe('fitRangeToWidth', () => {
  const range = { start: parseDate('2026-03-01') as Date, end: parseDate('2026-03-10') as Date, totalDays: 10 }

  it('网格已不窄于可视区时原样返回', () => {
    expect(fitRangeToWidth(range, 10, 80)).toBe(range)
    expect(fitRangeToWidth(range, 10, 100)).toBe(range)
  })

  it('网格不足时向后按整天扩展铺满可视区，起点不变', () => {
    const fitted = fitRangeToWidth(range, 10, 205)
    expect(fitted.totalDays).toBe(21) // ceil(205 / 10)
    expect(formatDate(fitted.start)).toBe('2026-03-01')
    expect(formatDate(fitted.end)).toBe('2026-03-21')
    expect(fitted.totalDays * 10).toBeGreaterThanOrEqual(205)
  })

  it('零/负宽度不扩展', () => {
    expect(fitRangeToWidth(range, 10, 0)).toBe(range)
    expect(fitRangeToWidth(range, 10, -50)).toBe(range)
  })
})

describe('时间刻度', () => {
  const range = { start: parseDate('2026-03-01') as Date, end: parseDate('2026-03-31') as Date, totalDays: 31 }

  it('日粒度每天一列并标记周末', () => {
    const cols = buildTimeColumns(range, 'day', 10)
    expect(cols).toHaveLength(31)
    expect(cols[0].label).toBe('1')
    expect(cols.some((c) => c.weekend)).toBe(true)
  })

  it('周粒度对齐周一，首列钳制到 range 起点', () => {
    const cols = buildTimeColumns(range, 'week', 10)
    expect(cols[0].date.getDay()).toBe(1) // 周一
    expect(cols[0].x).toBe(0)
    expect(cols[0].width).toBe(10) // 3/1 为周日，首周仅 1 天在范围内
    expect(cols[1].width).toBe(70)
  })

  it('月粒度每月一列', () => {
    const cols = buildTimeColumns(range, 'month', 10)
    expect(cols).toHaveLength(1)
    expect(cols[0].width).toBe(31 * 10)
  })

  it('月粒度列标签随语言环境（zh 3月 / en Mar）', () => {
    expect(buildTimeColumns(range, 'month', 10, undefined, 'zh-CN')[0].label).toBe('3月')
    expect(buildTimeColumns(range, 'month', 10, undefined, 'en-US')[0].label).toBe('Mar')
  })

  it('月份分段含年份标签', () => {
    const segments = buildMonthSegments(range, 10)
    expect(segments).toHaveLength(1)
    expect(segments[0].label).toContain('2026')
  })

  it('月份分段标签随语言环境（zh 2026年3月 / en Mar 2026）', () => {
    expect(buildMonthSegments(range, 10, undefined, 'zh-CN')[0].label).toBe('2026年3月')
    expect(buildMonthSegments(range, 10, undefined, 'en-US')[0].label).toBe('Mar 2026')
  })

  it('首尾月段钳制到 range 边界，标签不越界', () => {
    const r = { start: parseDate('2026-03-05') as Date, end: parseDate('2026-04-10') as Date, totalDays: 37 }
    const segments = buildMonthSegments(r, 10)
    expect(segments).toHaveLength(2)
    expect(segments[0].x).toBe(0)
    expect(segments[0].width).toBe(27 * 10) // 3/5–3/31
    expect(segments[1].width).toBe(10 * 10) // 4/1–4/10
  })

  it('maxWidth 将日/周末列收缩到精确铺满宽度', () => {
    const dayCols = buildTimeColumns(range, 'day', 10, 305)
    expect(dayCols[dayCols.length - 1].x + dayCols[dayCols.length - 1].width).toBe(305)
    const weekCols = buildTimeColumns(range, 'week', 10, 305)
    const lastWeek = weekCols[weekCols.length - 1]
    expect(lastWeek.x + lastWeek.width).toBe(305)
  })

  it('maxWidth 将末月段收缩到边界', () => {
    const segs = buildMonthSegments(range, 10, 200)
    const last = segs[segs.length - 1]
    expect(last.x + last.width).toBe(200)
  })
})

describe('FS 连线路径', () => {
  it('目标在右侧：右-竖-右肘形，返回 SVG path 字符串', () => {
    const path = elbowPath(100, 18, 300, 54)
    expect(typeof path).toBe('string')
    expect(path.startsWith('M 100 18')).toBe(true)
    expect(path).toContain('V 54')
  })

  it('目标在左侧（重叠/前置）：绕行行间避免穿条', () => {
    const path = elbowPath(300, 18, 100, 54)
    expect(path.startsWith('M 300 18')).toBe(true)
    // 绕行路径包含两次竖直段
    expect((path.match(/V /g) ?? []).length).toBeGreaterThanOrEqual(2)
  })

  it('出弯让位条尾外连接点：首横段越过圆点右缘', () => {
    const path = elbowPath(100, 18, 300, 54)
    const dotRight = 100 + BAR_CONNECTOR_OFFSET + BAR_CONNECTOR_RADIUS
    const bend = Number(/H (\d+)/.exec(path)?.[1])
    expect(bend).toBeGreaterThan(dotRight)
  })

  it('arrowPoints 生成指向右的三角形顶点', () => {
    const points = arrowPoints(200, 54)
    expect(points.startsWith('200,54')).toBe(true)
    expect(points.split(' ')).toHaveLength(3)
  })
})

describe('四类型依赖几何', () => {
  it('dependencyEnds / dependencyType 四类型互逆', () => {
    for (const type of ['FS', 'SS', 'FF', 'SF'] as const) {
      const { fromEnd, toEnd } = dependencyEnds(type)
      expect(dependencyType(fromEnd, toEnd)).toBe(type)
    }
    expect(dependencyEnds('FS')).toEqual({ fromEnd: 'finish', toEnd: 'start' })
    expect(dependencyEnds('SS')).toEqual({ fromEnd: 'start', toEnd: 'start' })
    expect(dependencyEnds('FF')).toEqual({ fromEnd: 'finish', toEnd: 'finish' })
    expect(dependencyEnds('SF')).toEqual({ fromEnd: 'start', toEnd: 'finish' })
  })

  it('FS 路径与存量 elbowPath 完全一致（向后兼容）', () => {
    expect(dependencyPath(100, 18, 300, 54, 'finish', 'start')).toBe(elbowPath(100, 18, 300, 54))
    expect(dependencyPath(300, 18, 100, 54, 'finish', 'start')).toBe(elbowPath(300, 18, 100, 54))
  })

  it('SS：条首向左出线，入点在目标条首左侧（箭头指右），逆向时绕行', () => {
    const path = dependencyPath(100, 18, 300, 54, 'start', 'start')
    const firstH = Number(/H (-?\d+)/.exec(path)?.[1])
    expect(firstH).toBeLessThan(100) // 向左出线
    expect(path.endsWith('H 293')).toBe(true) // 尖端 x2-7，箭头指右
    expect((path.match(/V /g) ?? []).length).toBeGreaterThanOrEqual(2) // 出左入左需绕行
  })

  it('FF：条尾向右出线，入点在目标条尾右侧（箭头指左），逆向时绕行', () => {
    const path = dependencyPath(100, 18, 300, 54, 'finish', 'finish')
    const firstH = Number(/H (-?\d+)/.exec(path)?.[1])
    expect(firstH).toBeGreaterThan(100) // 向右出线
    expect(path.endsWith('H 307')).toBe(true) // 尖端 x2+7，箭头指左
    expect((path.match(/V /g) ?? []).length).toBeGreaterThanOrEqual(2)
  })

  it('SF：目标在左侧时同向直连（左出左入，横-竖-横）', () => {
    const path = dependencyPath(300, 18, 100, 54, 'start', 'finish')
    expect((path.match(/V /g) ?? []).length).toBe(1) // 无需绕行
    expect(path.endsWith('H 107')).toBe(true) // 入点在条尾右侧，箭头指左
  })

  it('anchorX：普通条 start=x / finish=x+width，里程碑两锚点均在菱形中心', () => {
    const rangeStart = parseDate('2026-03-01') as Date
    const bar = barGeometry(leaf(1, { startDate: '2026-03-02', dueDate: '2026-03-04' }), 'startDate', 'dueDate', rangeStart, 10)
    expect(anchorX(bar!, 'start', 10)).toBe(10)
    expect(anchorX(bar!, 'finish', 10)).toBe(40)
    const ms = barGeometry(leaf(2, { startDate: '2026-03-02', dueDate: '2026-03-02' }, { milestone: true }), 'startDate', 'dueDate', rangeStart, 10)
    expect(anchorX(ms!, 'start', 10)).toBe(15)
    expect(anchorX(ms!, 'finish', 10)).toBe(15)
  })

  it('arrowPoints 方向参数：右指尖端在右，左指尖端在左', () => {
    const right = arrowPoints(200, 54, 'right').split(' ').map((p) => Number(p.split(',')[0]))
    expect(right[1]).toBeLessThan(200)
    expect(right[2]).toBeLessThan(200)
    const left = arrowPoints(200, 54, 'left').split(' ').map((p) => Number(p.split(',')[0]))
    expect(left[1]).toBeGreaterThan(200)
    expect(left[2]).toBeGreaterThan(200)
  })
})