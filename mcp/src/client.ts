/**
 * ProTask REST API 客户端。
 * 负责登录取 token、自动重登重试、统一解包 Result<T> 响应；
 * 另提供 multipart 文件上传与鉴权二进制下载，供附件工具使用。
 */

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { basename, dirname } from 'node:path'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 附件下载落盘结果 */
export interface DownloadResult {
  /** 本地文件绝对路径 */
  path: string
  /** 实际写入字节数 */
  size: number
  /** 后端返回的 Content-Type（可能为空） */
  contentType: string | null
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: number,
    message: string,
  ) {
    super(message)
  }
}

/** 项目卡片属性模板中的单个选项 */
export interface PropertyOption {
  id: string
  value: string
  color?: string
}

/** 项目卡片属性模板中的单个属性定义 */
export interface CardPropertyDef {
  id: string
  name: string
  type: 'text' | 'select' | 'multiSelect' | 'date' | 'number' | 'checkbox' | 'member'
  options?: PropertyOption[]
}

export interface ProjectMemberVO {
  userId: number
  username?: string
  nickname?: string
  fullName?: string
  role?: string
}

export class ProTaskClient {
  private token: string | null = null
  private loginPromise: Promise<void> | null = null

  constructor(
    private readonly baseUrl: string,
    private readonly username: string,
    private readonly password: string,
  ) {}

  private async login(): Promise<void> {
    // 并发调用时共享同一次登录，避免重复登录触发限流
    if (!this.loginPromise) {
      this.loginPromise = (async () => {
        const res = await fetch(`${this.baseUrl}/api/v1/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Accept-Language': 'zh-CN' },
          body: JSON.stringify({ username: this.username, password: this.password }),
        })
        const body = (await res.json()) as ApiResult<{ token: string }>
        if (!res.ok || body.code !== 200 || !body.data?.token) {
          throw new ApiError(res.status, body.code, `登录失败: ${body.message || res.statusText}`)
        }
        this.token = body.data.token
      })().finally(() => {
        this.loginPromise = null
      })
    }
    return this.loginPromise
  }

  /**
   * 统一鉴权请求：注入语言与 token；401 时重新登录并重试一次。
   * JSON、multipart、二进制下载共用这一条重登链路。
   */
  private async fetchWithAuth(path: string, init: RequestInit = {}): Promise<Response> {
    const doFetch = async () => {
      if (!this.token) await this.login()
      const headers = new Headers(init.headers)
      headers.set('Accept-Language', 'zh-CN')
      headers.set('Authorization', `Bearer ${this.token}`)
      return fetch(`${this.baseUrl}${path}`, { ...init, headers })
    }

    let res = await doFetch()
    if (res.status === 401) {
      this.token = null
      await this.login()
      res = await doFetch()
    }
    return res
  }

  /**
   * 解析后端错误体为 ApiError：兼容业务 Result 与网关默认 JSON，
   * 非 JSON 错误体退化为状态码文本。
   */
  private static async toApiError(res: Response): Promise<ApiError> {
    let code = res.status
    let message = res.statusText || `HTTP ${res.status}`
    try {
      const body = (await res.json()) as Partial<ApiResult<unknown>>
      if (typeof body.code === 'number') code = body.code
      if (typeof body.message === 'string' && body.message) message = body.message
    } catch {
      // 非 JSON 错误体，保留状态码文本
    }
    return new ApiError(res.status, code, message)
  }

  /** 通用 JSON 请求：401 时自动重新登录并重试一次 */
  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.fetchWithAuth(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

    let parsed: ApiResult<T> | null = null
    try {
      parsed = (await res.json()) as ApiResult<T>
    } catch {
      throw new ApiError(res.status, res.status, `后端返回非 JSON 响应 (${res.status})`)
    }
    if (!res.ok || parsed.code !== 200) {
      throw new ApiError(res.status, parsed.code, parsed.message || res.statusText)
    }
    return parsed.data
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path)
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
  }

  put<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>('PUT', path, body)
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }

  /**
   * 上传本地文件（multipart/form-data）。
   *
   * 注意：绝不手动设置 Content-Type —— boundary 由 fetch 依据 FormData 生成，
   * 手写头会丢失 boundary，导致后端 MultipartFile 解析失败。
   */
  async uploadFile<T>(path: string, filePath: string, filename?: string): Promise<T> {
    const data = await readFile(filePath)
    const originalName = filename || basename(filePath)
    const form = new FormData()
    form.append('file', new Blob([data], { type: guessContentType(originalName) }), originalName)

    const res = await this.fetchWithAuth(path, { method: 'POST', body: form })
    if (!res.ok) throw await ProTaskClient.toApiError(res)

    const parsed = (await res.json()) as ApiResult<T>
    if (parsed.code !== 200) {
      throw new ApiError(res.status, parsed.code, parsed.message || res.statusText)
    }
    return parsed.data
  }

  /**
   * 下载鉴权附件并写入本地文件（自动创建父目录，同名文件覆盖）。
   * 二进制不进入 MCP 文本通道，只返回落盘路径与元信息。
   */
  async downloadToFile(path: string, destPath: string): Promise<DownloadResult> {
    const res = await this.fetchWithAuth(path)
    if (!res.ok) throw await ProTaskClient.toApiError(res)

    const content = Buffer.from(await res.arrayBuffer())
    await mkdir(dirname(destPath), { recursive: true })
    await writeFile(destPath, content)
    return {
      path: destPath,
      size: content.byteLength,
      contentType: res.headers.get('content-type'),
    }
  }

  /** 解析项目的 cardProperties JSON 字符串为属性模板数组 */
  static parseCardProperties(raw: unknown): CardPropertyDef[] {
    if (!raw) return []
    try {
      const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
      return Array.isArray(parsed) ? (parsed as CardPropertyDef[]) : []
    } catch {
      return []
    }
  }
}

/** 扩展名到 MIME 的映射（与后端附件白名单一致，供上传时标注 Blob 类型） */
const MIME_BY_EXTENSION: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  txt: 'text/plain',
  md: 'text/markdown',
  csv: 'text/csv',
  zip: 'application/zip',
  rar: 'application/vnd.rar',
  '7z': 'application/x-7z-compressed',
}

/** 按扩展名推断 MIME；未知类型退化为二进制流 */
export function guessContentType(filename: string): string {
  const dot = filename.lastIndexOf('.')
  const ext = dot < 0 ? '' : filename.slice(dot + 1).toLowerCase()
  return MIME_BY_EXTENSION[ext] ?? 'application/octet-stream'
}
