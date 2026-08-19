import { get } from './request'
import type { UserVO } from '@/types'

/** 查询所有启用状态的用户（任务分配下拉用） */
export const listUsers = () => get<UserVO[]>('/v1/users')
