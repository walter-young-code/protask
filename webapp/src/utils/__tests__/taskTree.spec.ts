import { describe, expect, it } from 'vitest'
import {
  buildParentOptions,
  buildTaskTree,
  collectDescendantIds,
  flattenTree,
} from '@/utils/taskTree'
import type { TaskVO } from '@/types'

/** 构造测试任务（仅填充层级相关字段） */
function makeTask(id: number, parentTaskId: number | null, wbs: string, title = `T${id}`): TaskVO {
  return {
    id,
    projectId: 1,
    title,
    creatorId: 1,
    createdAt: '2026-01-01 10:00:00',
    updatedAt: '2026-01-01 10:00:00',
    parentTaskId,
    wbs,
    properties: {},
  }
}

/**
 * 后端树序（DFS）扁平列表：
 * 1(根) → 1.1 → 1.1.1；1.2；2(根)
 */
const tasks: TaskVO[] = [
  makeTask(1, null, '1'),
  makeTask(2, 1, '1.1'),
  makeTask(4, 2, '1.1.1'),
  makeTask(3, 1, '1.2'),
  makeTask(5, null, '2'),
]

describe('buildTaskTree', () => {
  it('按父引用嵌套并推导深度，保持同级树序', () => {
    const roots = buildTaskTree(tasks)
    expect(roots.map((r) => r.id)).toEqual([1, 5])
    expect(roots[0].children.map((c) => c.id)).toEqual([2, 3])
    expect(roots[0].children[0].children.map((c) => c.id)).toEqual([4])
    expect(roots[0].depth).toBe(0)
    expect(roots[0].children[0].depth).toBe(1)
    expect(roots[0].children[0].children[0].depth).toBe(2)
  })

  it('父引用缺失的孤儿任务提升为根', () => {
    const orphan = makeTask(9, 999, '9')
    const roots = buildTaskTree([makeTask(1, null, '1'), orphan])
    expect(roots.map((r) => r.id)).toEqual([1, 9])
    expect(roots[1].depth).toBe(0)
  })

  it('空列表返回空树', () => {
    expect(buildTaskTree([])).toEqual([])
  })
})

describe('collectDescendantIds', () => {
  it('收集全部子孙（不含自身），跨多层级', () => {
    expect([...collectDescendantIds(tasks, 1)].sort()).toEqual([2, 3, 4])
    expect([...collectDescendantIds(tasks, 2)]).toEqual([4])
  })

  it('叶子任务无子孙', () => {
    expect(collectDescendantIds(tasks, 5).size).toBe(0)
    expect(collectDescendantIds(tasks, 4).size).toBe(0)
  })
})

describe('buildParentOptions', () => {
  it('排除自身与全部子孙（防环），按树序展开并携带深度/WBS', () => {
    const options = buildParentOptions(tasks, 2)
    expect(options.map((o) => o.id)).toEqual([1, 3, 5])
    expect(options.find((o) => o.id === 3)).toMatchObject({ depth: 1, wbs: '1.2' })
    expect(options.find((o) => o.id === 1)).toMatchObject({ depth: 0, wbs: '1' })
  })

  it('新建（不传 excludeId）时包含全部任务', () => {
    expect(buildParentOptions(tasks).map((o) => o.id)).toEqual([1, 2, 4, 3, 5])
  })

  it('排除根任务时其整棵子树均不可选', () => {
    expect(buildParentOptions(tasks, 1).map((o) => o.id)).toEqual([5])
  })
})

describe('flattenTree', () => {
  const roots = buildTaskTree(tasks)

  it('无折叠时按树序平铺全部节点', () => {
    expect(flattenTree(roots, new Set()).map((r) => r.id)).toEqual([1, 2, 4, 3, 5])
  })

  it('折叠节点隐藏其全部子孙', () => {
    expect(flattenTree(roots, new Set([1])).map((r) => r.id)).toEqual([1, 5])
    expect(flattenTree(roots, new Set([2])).map((r) => r.id)).toEqual([1, 2, 3, 5])
  })
})