import { describe, expect, it } from 'vitest'
import { CUSTOM_PROPERTY_TYPES, createPropertyId, propertyDisplayText } from '@/utils/meta'
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
})
