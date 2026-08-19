import { get, post } from './request'
import type { InviteVO, LoginResponse, UserVO } from '@/types'

export interface LoginParams {
  username: string
  password: string
}

export interface RegisterParams {
  username: string
  password: string
  nickname?: string
  /** 名（First Name） */
  firstName?: string
  /** 姓（Last Name） */
  lastName?: string
  email?: string
  /** 邀请凭证（仅受邀用户可注册） */
  inviteToken: string
}

export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

/** 用户登录 */
export const login = (data: LoginParams) => post<LoginResponse>('/v1/auth/login', data)

/** 生成一次性注册邀请链接（需登录） */
export const createInvite = () => post<InviteVO>('/v1/auth/invites')

/** 校验邀请链接凭证是否有效（注册页调用） */
export const validateInvite = (token: string) => get<InviteVO>('/v1/auth/invites/validate', { token })

/** 用户注册（需携带邀请凭证） */
export const register = (data: RegisterParams) => post<UserVO>('/v1/auth/register', data)

/** 登出 */
export const logout = () => post<void>('/v1/auth/logout')

/** 当前登录用户信息 */
export const getMe = () => get<UserVO>('/v1/auth/me')

/** 修改当前用户密码（校验旧密码） */
export const changePassword = (data: ChangePasswordParams) => post<void>('/v1/auth/change-password', data)
