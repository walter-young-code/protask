import { describe, expect, it } from 'vitest'
import {
  DEFAULT_VIEW_CONFIG,
  applyFilters,
  applyKeyword,
  applySort,
  applyView,
  parseViewConfig,
  toViewFields,
} from '@/utils/viewConfig'
import type { CardProperty, TaskVO } from '@/types'

/** 测试属性模板 */
const envProp: CardProperty = {
  id: 'env',
  name: '环境',
  type: 'select',
  options: [
    { id: 'o1', value: '测试' },
    { id: 'o2', value: '生产' },
  ],
}
const tagProp: CardProperty = {
  id: 'tag',
  name: '标签',
  type: 'multiSelect',
  options: [
    { id: 't1', value: '前端' },
    { id: 't2', value: '后端' },
  ],
}
const effortProp: CardProperty = { id: 'effort', name: '工作量', type: 'number' }
const dueProp: CardProperty = { id: 'due', name: '截止', type: 'date' }
const doneProp: CardProperty = { id: 'done', name: '完成', type: 'checkbox' }
const noteProp: CardProperty = { id: 'note', name: '备注', type: 'text' }
const TEMPLATE: CardProperty[] = [envProp, tagProp, effortProp, dueProp, doneProp, noteProp]

/** 构造测试任务 */
function makeTask(overrides: Partial<TaskVO> = {}): TaskVO {
  return {
    id: 1,
    projectId: 1,
    title: '任务',
    creatorId: 1,
    createdAt: '2026-01-01 10:00:00',
    updatedAt: '2026-01-01 10:00:00',
    properties: {},
    ...overrides,
  }
}

describe('parseViewConfig', () => {
  it('空 fields 回退默认配置', () => {
    expect(parseViewConfig(undefined)).toEqual(DEFAULT_VIEW_CONFIG)
    expect(parseViewConfig({})).toEqual(DEFAULT_VIEW_CONFIG)
  })

  it('非法视图类型回退 table', () => {
    expect(parseViewConfig({ viewType: 'mindmap' }).viewType).toBe('table')
  })

  it('解析合法配置并剔除非法条目', () => {
    const config = parseViewConfig({
      viewType: 'board',
      groupById: 'env',
      filters: [
        { field: 'env', operator: 'includes', values: ['o2'] },
        { values: 'not-array' },
      ],
      sorts: [{ field: 'due', direction: 'asc' }, { field: 'title', direction: 'both' }],
    })
    expect(config.viewType).toBe('board')
    expect(config.groupById).toBe('env')
    expect(config.filters).toHaveLength(1)
    expect(config.sorts).toHaveLength(1)
  })

  it('序列化与解析互逆（含 groupById/dateId/cardPropertyIds）', () => {
    const config = {
      viewType: 'calendar' as const,
      filters: [{ field: 'env', operator: 'includes' as const, values: ['o1'] }],
      sorts: [{ field: 'title' as const, direction: 'desc' as const }],
      groupById: 'env',
      dateId: 'due',
      cardPropertyIds: ['env', 'due'],
    }
    expect(parseViewConfig(toViewFields(config))).toEqual(config)
  })

  it('cardPropertyIds 过滤非字符串条目', () => {
    const config = parseViewConfig({ viewType: 'board', cardPropertyIds: ['env', 42, 'due', null] })
    expect(config.cardPropertyIds).toEqual(['env', 'due'])
  })
})

describe('applyFilters', () => {
  const tasks = [
    makeTask({ id: 1, properties: { env: 'o1', tag: ['t1'], effort: 3 } }),
    makeTask({ id: 2, properties: { env: 'o2', tag: ['t1', 't2'] } }),
    makeTask({ id: 3, properties: { tag: [] } }),
  ]

  it('select 按选项 id 匹配', () => {
    const result = applyFilters(tasks, [{ field: 'env', operator: 'includes', values: ['o2'] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2])
  })

  it('select 按选项值匹配', () => {
    const result = applyFilters(tasks, [{ field: 'env', operator: 'includes', values: ['测试'] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([1])
  })

  it('multiSelect 任一选项命中即匹配', () => {
    const result = applyFilters(tasks, [{ field: 'tag', operator: 'includes', values: ['t2'] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2])
  })

  it('text 按值字符串匹配', () => {
    const withNote = [...tasks, makeTask({ id: 4, properties: { note: 'hello' } })]
    const result = applyFilters(withNote, [{ field: 'note', operator: 'includes', values: ['hello'] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([4])
  })

  it('多条件 AND 组合', () => {
    const result = applyFilters(
      tasks,
      [
        { field: 'env', operator: 'includes', values: ['o1'] },
        { field: 'tag', operator: 'includes', values: ['t1'] },
      ],
      TEMPLATE,
    )
    expect(result.map((t) => t.id)).toEqual([1])
  })

  it('属性值为空的筛选', () => {
    const result = applyFilters(tasks, [{ field: 'env', operator: 'empty', values: [] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([3])
  })

  it('空数组视为未设置', () => {
    const result = applyFilters(tasks, [{ field: 'tag', operator: 'empty', values: [] }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([3])
  })

  it('无筛选条件时返回原数组', () => {
    expect(applyFilters(tasks, [], TEMPLATE)).toBe(tasks)
  })
})

describe('applyKeyword', () => {
  const tasks = [
    makeTask({ id: 1, title: 'Design Login Page' }),
    makeTask({ id: 2, title: '任务二', description: '包含 login 关键词' }),
    makeTask({ id: 3, title: '任务三' }),
  ]

  it('忽略大小写匹配标题与描述', () => {
    const result = applyKeyword(tasks, 'LOGIN')
    expect(result.map((t) => t.id)).toEqual([1, 2])
  })

  it('空关键词返回原数组', () => {
    expect(applyKeyword(tasks, '  ')).toBe(tasks)
  })
})

describe('applySort', () => {
  it('select 按选项定义顺序排序', () => {
    const tasks = [
      makeTask({ id: 1, properties: { env: 'o2' } }),
      makeTask({ id: 2, properties: { env: 'o1' } }),
    ]
    const result = applySort(tasks, [{ field: 'env', direction: 'asc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2, 1])
  })

  it('number 按数值排序', () => {
    const tasks = [
      makeTask({ id: 1, properties: { effort: 10 } }),
      makeTask({ id: 2, properties: { effort: 2 } }),
      makeTask({ id: 3, properties: { effort: 5 } }),
    ]
    const result = applySort(tasks, [{ field: 'effort', direction: 'asc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2, 3, 1])
  })

  it('date 值为空的任务排末尾', () => {
    const tasks = [
      makeTask({ id: 1, properties: {} }),
      makeTask({ id: 2, properties: { due: '2026-02-01 00:00:00' } }),
      makeTask({ id: 3, properties: { due: '2026-01-01 00:00:00' } }),
    ]
    const result = applySort(tasks, [{ field: 'due', direction: 'asc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([3, 2, 1])
  })

  it('checkbox 勾选排后（升序）', () => {
    const tasks = [
      makeTask({ id: 1, properties: { done: true } }),
      makeTask({ id: 2, properties: { done: false } }),
    ]
    const result = applySort(tasks, [{ field: 'done', direction: 'asc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2, 1])
  })

  it('按标题排序', () => {
    const tasks = [
      makeTask({ id: 1, title: 'B' }),
      makeTask({ id: 2, title: 'A' }),
    ]
    const result = applySort(tasks, [{ field: 'title', direction: 'asc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2, 1])
  })

  it('按创建时间倒序', () => {
    const tasks = [
      makeTask({ id: 1, createdAt: '2026-01-01 10:00:00' }),
      makeTask({ id: 2, createdAt: '2026-01-03 10:00:00' }),
      makeTask({ id: 3, createdAt: '2026-01-02 10:00:00' }),
    ]
    const result = applySort(tasks, [{ field: 'createdAt', direction: 'desc' }], TEMPLATE)
    expect(result.map((t) => t.id)).toEqual([2, 3, 1])
  })

  it('不修改原数组', () => {
    const tasks = [
      makeTask({ id: 1, properties: { effort: 9 } }),
      makeTask({ id: 2, properties: { effort: 1 } }),
    ]
    applySort(tasks, [{ field: 'effort', direction: 'asc' }], TEMPLATE)
    expect(tasks.map((t) => t.id)).toEqual([1, 2])
  })
})

describe('applyView 管线', () => {
  it('筛选、关键词、排序依次生效', () => {
    const tasks = [
      makeTask({ id: 1, title: 'B任务', properties: { env: 'o2', effort: 9 } }),
      makeTask({ id: 2, title: 'A任务', properties: { env: 'o1', effort: 1 } }),
      makeTask({ id: 3, title: 'C任务', properties: { env: 'o2', effort: 2 } }),
    ]
    const result = applyView(
      tasks,
      {
        viewType: 'table',
        filters: [{ field: 'env', operator: 'includes', values: ['o2'] }],
        sorts: [{ field: 'effort', direction: 'asc' }],
      },
      TEMPLATE,
      '任务',
    )
    expect(result.map((t) => t.id)).toEqual([3, 1])
  })
})
