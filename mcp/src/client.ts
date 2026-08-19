/**
 * ProTask REST API 客户端。
 * 负责登录取 token、自动重登重试、统一解包 Result<T> 响应。
 */

export interface ApiResult<T> {
  code: number
  message: string
  data: T
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

  /** 通用请求：401 时自动重新登录并重试一次 */
  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const doFetch = async () => {
      if (!this.token) await this.login()
      return fetch(`${this.baseUrl}${path}`, {
        method,
        headers: {
          'Content-Type': 'application/json',
          'Accept-Language': 'zh-CN',
          Authorization: `Bearer ${this.token}`,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    }

    let res = await doFetch()
    if (res.status === 401) {
      this.token = null
      await this.login()
      res = await doFetch()
    }

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
