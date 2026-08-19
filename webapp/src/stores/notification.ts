import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as notificationApi from '@/api/notification'
import type { NotificationVO } from '@/types'

/** 轮询间隔：30s 拉取未读数（页面隐藏时暂停，保持架构简单不引入 WebSocket） */
const POLL_INTERVAL_MS = 30_000

/** 站内通知全局状态：未读数角标、通知列表与轮询控制 */
export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)
  const list = ref<NotificationVO[]>([])

  let timer: ReturnType<typeof setInterval> | null = null
  let polling = false

  /** 刷新未读数（失败静默，避免轮询干扰用户操作） */
  async function fetchUnreadCount() {
    try {
      unreadCount.value = await notificationApi.getUnreadCount()
    } catch {
      // 静默：网络抖动或登出时不打扰用户
    }
  }

  /** 拉取通知列表（面板打开时调用）；已读即删除，现存记录均为未读 */
  async function fetchList() {
    list.value = await notificationApi.listNotifications()
    unreadCount.value = list.value.length
  }

  /** 已读单条即删除：后端物理删除，本地同步移除 */
  async function markRead(item: NotificationVO) {
    await notificationApi.markRead(item.id)
    list.value = list.value.filter((n) => n.id !== item.id)
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }

  /** 全部已读即清空：后端删除全部记录，本地同步清空 */
  async function markAllRead() {
    await notificationApi.markAllRead()
    list.value = []
    unreadCount.value = 0
  }

  function pausePolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function resumePolling() {
    if (!polling || timer) return
    timer = setInterval(() => void fetchUnreadCount(), POLL_INTERVAL_MS)
  }

  function handleVisibilityChange() {
    if (document.hidden) {
      pausePolling()
    } else {
      void fetchUnreadCount()
      resumePolling()
    }
  }

  /** 启动轮询（登录后调用一次），页面隐藏时自动暂停 */
  function startPolling() {
    if (polling) return
    polling = true
    void fetchUnreadCount()
    resumePolling()
    document.addEventListener('visibilitychange', handleVisibilityChange)
  }

  /** 停止轮询（登出时调用） */
  function stopPolling() {
    polling = false
    pausePolling()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    unreadCount.value = 0
    list.value = []
  }

  return {
    unreadCount,
    list,
    fetchUnreadCount,
    fetchList,
    markRead,
    markAllRead,
    startPolling,
    stopPolling,
  }
})
