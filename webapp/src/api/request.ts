import axios, { AxiosError } from 'axios'
import { appMessage } from '@/utils/appMessage'
import router from '@/router'
import i18n from '@/i18n'
import { useShareStore } from '@/stores/share'
import { useUserStore } from '@/stores/user'
import type { ApiResult } from '@/types'

/** Axios 实例：baseURL 走 Vite 代理 -> 后端 8000 端口 */
const instance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

/** 请求拦截器：附加 JWT token、分享凭证与当前语言（后端按 Accept-Language 返回对应语言的错误消息） */
instance.interceptors.request.use((config) => {
  const token = localStorage.getItem('protask_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // 分享访问模式：公开项目非成员凭分享凭证只读访问（动态导入避免循环依赖）
  const shareId = useShareStore().shareId
  if (shareId) {
    config.headers['X-Share-Token'] = shareId
  }
  config.headers['Accept-Language'] = i18n.global.locale.value
  return config
})

function handleUnauthorized() {
  localStorage.removeItem('protask_token')
  // 同步清理内存中的用户状态
  useUserStore().clear()
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login' })
  }
}

/** 响应拦截器：解包 Result 结构，统一错误提示 */
instance.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>
    if (result.code === 200) {
      return response
    }
    if (result.code === 401) {
      handleUnauthorized()
    }
    appMessage.error(result.message || i18n.global.t('request.failed'))
    return Promise.reject(new Error(result.message || 'request failed'))
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    if (error.response?.status === 401) {
      handleUnauthorized()
    }
    const message =
      error.response?.data?.message || error.message || i18n.global.t('request.networkError')
    appMessage.error(message)
    return Promise.reject(error)
  },
)

/** GET 请求，直接返回业务数据 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await instance.get<ApiResult<T>>(url, { params })
  return response.data.data
}

/** POST 请求 */
export async function post<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.post<ApiResult<T>>(url, data)
  return response.data.data
}

/** PUT 请求 */
export async function put<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.put<ApiResult<T>>(url, data)
  return response.data.data
}

/** PATCH 请求 */
export async function patch<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.patch<ApiResult<T>>(url, data)
  return response.data.data
}

/** DELETE 请求（可选请求体，如注销时指定项目接管人） */
export async function del<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.delete<ApiResult<T>>(url, { data })
  return response.data.data
}
