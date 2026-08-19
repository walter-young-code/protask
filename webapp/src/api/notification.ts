import { get, put } from './request'
import type { NotificationVO } from '@/types'

/** 查询我的通知列表（按时间倒序） */
export const listNotifications = (unreadOnly = false, limit = 50) =>
  get<NotificationVO[]>('/v1/notifications', { unreadOnly, limit })

/** 未读通知数（顶栏铃铛角标） */
export const getUnreadCount = () => get<number>('/v1/notifications/unread-count')

/** 标记单条通知已读 */
export const markRead = (id: number) => put<void>(`/v1/notifications/${id}/read`)

/** 标记全部通知已读 */
export const markAllRead = () => put<void>('/v1/notifications/read-all')
