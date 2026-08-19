import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { UserVO } from '@/types'

const TOKEN_KEY = 'protask_token'

/** 用户全局状态：token、当前用户信息 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserVO | null>(null)

  const isLoggedIn = computed(() => token.value.length > 0)

  /** 登录并持久化 token */
  async function login(username: string, password: string) {
    const result = await authApi.login({ username, password })
    token.value = result.token
    user.value = result.user
    localStorage.setItem(TOKEN_KEY, result.token)
  }

  /** 拉取当前用户信息（刷新页面后恢复会话） */
  async function fetchMe() {
    user.value = await authApi.getMe()
  }

  /** 登出并清理本地状态 */
  async function logout() {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  function clear() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, isLoggedIn, login, fetchMe, logout, clear }
})
