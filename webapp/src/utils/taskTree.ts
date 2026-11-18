import type { TaskVO } from '@/types'

/** 树形任务节点（在 TaskVO 基础上嵌套 children 与深度，供表格树型展示与甘特行渲染） */
export interface TaskTreeNode extends TaskVO {
  children: TaskTreeNode[]
  /** 树深度（根=0，逐级 +1） */
  depth: number
}

/**
 * 将后端按树序（DFS）返回的扁平任务列表构建为嵌套树。
 * 保持后端同级排序；父引用缺失（孤儿）的任务提升为根，与后端编号语义一致。
 */
export function buildTaskTree(tasks: TaskVO[]): TaskTreeNode[] {
  const map = new Map<number, TaskTreeNode>()
  for (const task of tasks) {
    map.set(task.id, { ...task, children: [], depth: 0 })
  }
  const roots: TaskTreeNode[] = []
  // 后端 DFS 顺序保证父先于子出现，depth 可即时推导
  for (const task of tasks) {
    const node = map.get(task.id)
    if (!node) continue
    const parent = task.parentTaskId != null ? map.get(task.parentTaskId) : undefined
    if (parent) {
      node.depth = parent.depth + 1
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  return roots
}

/** 收集指定任务的全部子孙 id（不含自身），供父任务选择防环与级联判断 */
export function collectDescendantIds(tasks: TaskVO[], rootId: number): Set<number> {
  const childrenMap = new Map<number, number[]>()
  for (const task of tasks) {
    if (task.parentTaskId != null) {
      const arr = childrenMap.get(task.parentTaskId) ?? []
      arr.push(task.id)
      childrenMap.set(task.parentTaskId, arr)
    }
  }
  const result = new Set<number>()
  const stack = [rootId]
  while (stack.length) {
    const id = stack.pop()
    if (id == null) continue
    for (const child of childrenMap.get(id) ?? []) {
      if (!result.has(child)) {
        result.add(child)
        stack.push(child)
      }
    }
  }
  return result
}

/** 父任务下拉选项（带深度缩进与 WBS 编号） */
export interface ParentOption {
  id: number
  title: string
  depth: number
  wbs: string
}

/**
 * 构建父任务候选列表：按树序展开，排除自身与全部子孙（防环）。
 * @param excludeId 当前任务 id（编辑时排除自身及其子孙；新建时不传）
 */
export function buildParentOptions(tasks: TaskVO[], excludeId?: number): ParentOption[] {
  const excluded = new Set<number>()
  if (excludeId != null) {
    excluded.add(excludeId)
    for (const id of collectDescendantIds(tasks, excludeId)) excluded.add(id)
  }
  const options: ParentOption[] = []
  const walk = (nodes: TaskTreeNode[]) => {
    for (const node of nodes) {
      if (!excluded.has(node.id)) {
        options.push({ id: node.id, title: node.title, depth: node.depth, wbs: node.wbs ?? '' })
      }
      walk(node.children)
    }
  }
  walk(buildTaskTree(tasks))
  return options
}

/**
 * 按折叠集合将树平铺为可见行（折叠节点隐藏其子孙），保持树序。
 * 供甘特图行渲染与表格自定义展开使用。
 */
export function flattenTree(roots: TaskTreeNode[], collapsed: Set<number>): TaskTreeNode[] {
  const result: TaskTreeNode[] = []
  const walk = (nodes: TaskTreeNode[]) => {
    for (const node of nodes) {
      result.push(node)
      if (node.children.length && !collapsed.has(node.id)) {
        walk(node.children)
      }
    }
  }
  walk(roots)
  return result
}