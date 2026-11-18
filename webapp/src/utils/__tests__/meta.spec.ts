import { describe, expect, it } from 'vitest'
import {
  BUILTIN_PROPERTIES,
  CUSTOM_PROPERTY_TYPES,
  createPropertyId,
  DUE_DATE_PROPERTY_ID,
  GANTT_REQUIRED_PROPERTY_IDS,
  hasScheduleProperties,
  hasWbsProperty,
  isGanttRequiredProperty,
  propertyDisplayText,
  START_DATE_PROPERTY_ID,
  WBS_PROPERTY_ID,
} from '@/utils/meta'
import type { CardProperty, TaskVO } from '@/types'

/** 构造测试任务 */
function makeTask(properties: Record<string, unknown> = {}): TaskVO {
  return {
    id: 1,
    projectId: 1,
    title: '任务',
    creatorId: 1,
    createdAt: '2026-01-01 10:00:00',
    updatedAt: '2026-01-01 10:00:00',
    properties,
  }
}

describe('CUSTOM_PROPERTY_TYPES', () => {
  it('包含全部七种可新建属性类型', () => {
    expect(CUSTOM_PROPERTY_TYPES).toEqual([
      'text',
      'select',
      'date',
      'number',
      'multiSelect',
      'checkbox',
      'member',
    ])
  })
})

describe('createPropertyId', () => {
  it('生成非空且互不相同的 ID', () => {
    const ids = new Set(Array.from({ length: 50 }, () => createPropertyId()))
    expect(ids.size).toBe(50)
    for (const id of ids) {
      expect(id.length).toBeGreaterThan(0)
    }
  })
})

describe('propertyDisplayText', () => {
  const selectProp: CardProperty = {
    id: 'p1',
    name: '环境',
    type: 'select',
    options: [
      { id: 'o1', value: '测试' },
      { id: 'o2', value: '生产' },
    ],
  }

  it('select 按选项 id 解析为选项值', () => {
    expect(propertyDisplayText(makeTask({ p1: 'o1' }), selectProp)).toBe('测试')
  })

  it('select 值兼容选项值本身，孤儿值（选项已删）显示占位符', () => {
    expect(propertyDisplayText(makeTask({ p1: '生产' }), selectProp)).toBe('生产')
    expect(propertyDisplayText(makeTask({ p1: 'orphan-id' }), selectProp)).toBe('—')
  })

  it('multiSelect 解析选项 id 数组，孤儿 id 被过滤', () => {
    const prop: CardProperty = { ...selectProp, type: 'multiSelect' }
    expect(propertyDisplayText(makeTask({ p1: ['o1', 'o2'] }), prop)).toBe('测试, 生产')
    expect(propertyDisplayText(makeTask({ p1: ['o1', 'orphan-id'] }), prop)).toBe('测试')
    expect(propertyDisplayText(makeTask({ p1: ['orphan-id'] }), prop)).toBe('—')
  })

  it('checkbox 显示勾选符，未勾选与空值显示占位符', () => {
    const prop: CardProperty = { id: 'p2', name: '已验证', type: 'checkbox' }
    expect(propertyDisplayText(makeTask({ p2: true }), prop)).toBe('✓')
    expect(propertyDisplayText(makeTask({ p2: false }), prop)).toBe('—')
    expect(propertyDisplayText(makeTask({}), prop)).toBe('—')
  })

  it('其余类型原样转字符串', () => {
    const prop: CardProperty = { id: 'p3', name: '工作量', type: 'number' }
    expect(propertyDisplayText(makeTask({ p3: 42 }), prop)).toBe('42')
  })

  it('date 仅展示日期部分，兼容存量带时间的值', () => {
    const prop: CardProperty = { id: 'p5', name: '截止日期', type: 'date' }
    expect(propertyDisplayText(makeTask({ p5: '2026-08-14' }), prop)).toBe('2026-08-14')
    expect(propertyDisplayText(makeTask({ p5: '2026-08-14 18:30:00' }), prop)).toBe('2026-08-14')
  })

  it('member 按成员映射解析为昵称，孤儿值（成员已移除）显示占位符', () => {
    const prop: CardProperty = { id: 'p4', name: '负责人', type: 'member' }
    const memberMap = { 1: '张三', 2: '李四' }
    expect(propertyDisplayText(makeTask({ p4: 2 }), prop, memberMap)).toBe('李四')
    expect(propertyDisplayText(makeTask({ p4: 99 }), prop, memberMap)).toBe('—')
    expect(propertyDisplayText(makeTask({}), prop, memberMap)).toBe('—')
  })

  it('WBS 编码只读：值取自 task.wbs 而非 properties，缺省显示占位符', () => {
    const prop: CardProperty = { id: WBS_PROPERTY_ID, name: 'WBS编码', type: 'text' }
    const task = { ...makeTask({ wbs: '伪造值' }), wbs: '1.2' }
    expect(propertyDisplayText(task, prop)).toBe('1.2')
    expect(propertyDisplayText(makeTask({}), prop)).toBe('—')
  })
})

describe('hasWbsProperty（WBS 门禁）', () => {
  it('模板含内置 wbs 属性时启用门禁', () => {
    expect(hasWbsProperty([{ id: WBS_PROPERTY_ID, name: 'WBS编码', type: 'text' }])).toBe(true)
  })

  it('模板不含 wbs 时关闭门禁（空模板/其他属性）', () => {
    expect(hasWbsProperty([])).toBe(false)
    expect(hasWbsProperty([{ id: 'status', name: '状态', type: 'select' }])).toBe(false)
  })
})

describe('甘特必要字段与排期绑定', () => {
  it('必要字段为 WBS/开始日期/截止日期三个内置 id', () => {
    expect(GANTT_REQUIRED_PROPERTY_IDS).toEqual([WBS_PROPERTY_ID, START_DATE_PROPERTY_ID, DUE_DATE_PROPERTY_ID])
    expect(isGanttRequiredProperty(WBS_PROPERTY_ID)).toBe(true)
    expect(isGanttRequiredProperty(START_DATE_PROPERTY_ID)).toBe(true)
    expect(isGanttRequiredProperty(DUE_DATE_PROPERTY_ID)).toBe(true)
    expect(isGanttRequiredProperty('status')).toBe(false)
    expect(isGanttRequiredProperty('custom-date')).toBe(false)
  })

  it('hasScheduleProperties 需开始/截止日期同时在模板中', () => {
    const start: CardProperty = { id: START_DATE_PROPERTY_ID, name: '开始日期', type: 'date' }
    const due: CardProperty = { id: DUE_DATE_PROPERTY_ID, name: '截止日期', type: 'date' }
    expect(hasScheduleProperties([start, due])).toBe(true)
    expect(hasScheduleProperties([start])).toBe(false)
    expect(hasScheduleProperties([due])).toBe(false)
    expect(hasScheduleProperties([])).toBe(false)
  })
})

describe('BUILTIN_PROPERTIES', () => {
  it('包含只读 WBS 编码（text）与开始日期（date）内置属性', () => {
    const wbs = BUILTIN_PROPERTIES.find((p) => p.id === WBS_PROPERTY_ID)
    const startDate = BUILTIN_PROPERTIES.find((p) => p.id === START_DATE_PROPERTY_ID)
    expect(wbs?.type).toBe('text')
    expect(startDate?.type).toBe('date')
  })
})
