import { reactive, readonly } from 'vue'

/** 应用级消息提示类型（对应 Element Plus 消息语义） */
export type AppMessageType = 'success' | 'error' | 'warning' | 'info'

export interface AppMessageItem {
  id: number
  type: AppMessageType
  content: string
}

/** 消息列表：统一提示弹窗数据源（只读暴露，仅通过 appMessage 操作） */
const messages = reactive<AppMessageItem[]>([])

/** 每条消息的自动关闭定时器（手动关闭时需清除，避免误删后续同 id 消息） */
const timers = new Map<number, ReturnType<typeof setTimeout>>()

let nextId = 1

/** 最大同时展示条数：超出移除最旧，防止面板无限膨胀 */
const MAX_MESSAGES = 8

/** 自动关闭时长：5 秒内无人手动关闭则自动消失 */
export const AUTO_CLOSE_MS = 5000

function clearTimer(id: number) {
  const timer = timers.get(id)
  if (timer) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

/** 移除指定消息（自动关闭定时器与手动关闭共用入口） */
function closeMessage(id: number) {
  clearTimer(id)
  const index = messages.findIndex((item) => item.id === id)
  if (index >= 0) messages.splice(index, 1)
}

function push(type: AppMessageType, content: string) {
  const id = nextId++
  messages.push({ id, type, content })
  // 5 秒后自动关闭；若期间被手动关闭，定时器在 closeMessage 中清除
  timers.set(id, setTimeout(() => closeMessage(id), AUTO_CLOSE_MS))
  if (messages.length > MAX_MESSAGES) {
    const removed = messages.shift()
    if (removed) clearTimer(removed.id)
  }
}

/**
 * 统一消息提示：右上角弹窗堆叠展示，5 秒内无人关闭自动消失，也可手动关闭。
 * 替代 ElMessage（3 秒闪现后自动消失，用户难以看清）。
 */
export const appMessage = {
  success: (content: string) => push('success', content),
  error: (content: string) => push('error', content),
  warning: (content: string) => push('warning', content),
  info: (content: string) => push('info', content),
  /** 关闭单条消息 */
  close: closeMessage,
  /** 关闭全部消息 */
  clear: () => {
    timers.forEach((timer) => clearTimeout(timer))
    timers.clear()
    messages.splice(0, messages.length)
  },
}

/** 只读消息列表（供全局提示组件渲染） */
export const appMessages = readonly(messages)
