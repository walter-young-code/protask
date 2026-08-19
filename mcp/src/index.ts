#!/usr/bin/env node
/**
 * ProTask MCP 服务入口。
 *
 * 通过 stdio 暴露 ProTask 的读写能力给 AI Agent，所有写入操作
 * 均经过 guard.ts 的格式规范化与模板校验，防止产生污染数据。
 *
 * 环境变量：
 * - PROTASK_BASE_URL    后端地址，默认 http://localhost:8000
 * - PROTASK_USERNAME    登录用户名（与 PROTASK_CREDS_FILE 二选一）
 * - PROTASK_PASSWORD    登录密码
 * - PROTASK_CREDS_FILE  凭证 JSON 文件路径（{username,password,baseUrl?}），
 *                       优先级高于环境变量，避免在 MCP 配置中明文写密码
 */

import { readFileSync } from 'node:fs'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import { ApiError, ProTaskClient, type ProjectMemberVO } from './client.js'
import {
  ValidationError,
  sanitizeIcon,
  sanitizeMarkdown,
  sanitizeTitle,
  validateMentionIds,
  validateProperties,
} from './guard.js'

function loadCreds(): { baseUrl: string; username: string; password: string } {
  let file: { username?: string; password?: string; baseUrl?: string } = {}
  if (process.env.PROTASK_CREDS_FILE) {
    try {
      file = JSON.parse(readFileSync(process.env.PROTASK_CREDS_FILE, 'utf8'))
    } catch (err) {
      console.error(`[protask-mcp] 凭证文件读取失败: ${(err as Error).message}`)
      process.exit(1)
    }
  }
  const baseUrl = file.baseUrl || process.env.PROTASK_BASE_URL || 'http://localhost:8000'
  const username = file.username || process.env.PROTASK_USERNAME
  const password = file.password || process.env.PROTASK_PASSWORD
  if (!username || !password) {
    console.error('[protask-mcp] 缺少凭证：请配置 PROTASK_USERNAME/PROTASK_PASSWORD 或 PROTASK_CREDS_FILE')
    process.exit(1)
  }
  return { baseUrl, username, password }
}

const { baseUrl: BASE_URL, username: USERNAME, password: PASSWORD } = loadCreds()

const client = new ProTaskClient(BASE_URL, USERNAME, PASSWORD)

/* ------------------------- 类型与输出裁剪 ------------------------- */

interface TaskVO {
  id: number
  projectId: number
  projectName?: string
  title: string
  icon?: string | null
  description?: string | null
  creatorName?: string
  createdAt?: string
  updatedAt?: string
  properties?: Record<string, unknown>
}

interface ProjectVO {
  id: number
  name: string
  description?: string | null
  type?: string
  icon?: string | null
  categoryId?: number | null
  categoryName?: string | null
  cardProperties?: string | null
  ownerId?: number
  ownerName?: string
  myRole?: string
  status?: string
  isTemplate?: boolean
}

/** 列表输出裁剪：描述截断，避免超大响应挤爆 Agent 上下文 */
function compactTask(t: TaskVO): Record<string, unknown> {
  return {
    id: t.id,
    projectId: t.projectId,
    ...(t.projectName ? { projectName: t.projectName } : {}),
    title: t.title,
    ...(t.icon ? { icon: t.icon } : {}),
    ...(t.description
      ? { description: t.description.length > 200 ? t.description.slice(0, 200) + '…' : t.description }
      : {}),
    ...(t.creatorName ? { creatorName: t.creatorName } : {}),
    ...(t.updatedAt ? { updatedAt: t.updatedAt } : {}),
    properties: t.properties ?? {},
  }
}

function json(data: unknown): { content: { type: 'text'; text: string }[] } {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] }
}

function failResult(err: unknown): { content: { type: 'text'; text: string }[]; isError: true } {
  let text: string
  if (err instanceof ValidationError) {
    text = `[写入被拒绝] ${err.message}\n请按上述指引修正参数后重试，不要绕过校验。`
  } else if (err instanceof ApiError) {
    text = `[后端错误 ${err.status}/${err.code}] ${err.message}`
  } else if (err instanceof Error) {
    text = `[错误] ${err.message}`
  } else {
    text = `[错误] ${String(err)}`
  }
  return { content: [{ type: 'text', text }], isError: true }
}

/** 获取项目详情 + 属性模板 + 成员（写入工具的统一前置步骤） */
async function loadProjectContext(projectId: number) {
  const project = await client.get<ProjectVO>(`/api/v1/projects/${projectId}`)
  const template = ProTaskClient.parseCardProperties(project.cardProperties)
  const members = await client.get<ProjectMemberVO[]>(`/api/v1/projects/${projectId}/members`)
  return { project, template, members }
}

/* ------------------------- 服务定义 ------------------------- */

const server = new McpServer(
  { name: 'protask', version: '1.0.0' },
  {
    instructions: `ProTask 团队任务管理系统的 MCP 服务。

写入规范（务必遵守，违反将被拒绝）：
1. 创建/更新任务前先调用 get_project 了解项目属性模板（cardProperties）与成员列表。
2. properties 只能包含模板中已定义的属性 id；select/multiSelect 的值必须使用模板中存在的选项 id。
3. 日期一律 YYYY-MM-DD；成员类型属性一律使用成员 userId 数字。
4. 标题必须是单行文本；正文使用纯 Markdown，不要夹带 HTML。
5. 创建任务前先 list_tasks 确认无同名任务，避免重复创建；更新任务只传需要变更的字段。
6. 删除操作必须显式传入 confirm=true，且仅当用户明确要求时执行。`,
  },
)

/* ------------------------- 只读工具 ------------------------- */

server.registerTool(
  'list_projects',
  {
    title: '查询我的项目列表',
    description: '列出当前用户参与的所有项目（含角色、分类）。只读。',
    inputSchema: {},
  },
  async () => {
    try {
      const projects = await client.get<ProjectVO[]>('/api/v1/projects')
      return json(
        projects.map((p) => ({
          id: p.id,
          name: p.name,
          type: p.type,
          icon: p.icon ?? '',
          categoryName: p.categoryName ?? null,
          myRole: p.myRole,
          description: p.description ?? '',
        })),
      )
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'get_project',
  {
    title: '查询项目详情',
    description:
      '获取项目详情，包含已解析的 cardProperties 属性模板（id/type/options）与成员列表（userId/角色）。写入任务属性前必须先调用本工具。只读。',
    inputSchema: { projectId: z.number().int().positive().describe('项目 ID') },
  },
  async ({ projectId }) => {
    try {
      const { project, template, members } = await loadProjectContext(projectId)
      return json({
        id: project.id,
        name: project.name,
        description: project.description ?? '',
        type: project.type,
        icon: project.icon ?? '',
        categoryName: project.categoryName ?? null,
        myRole: project.myRole,
        cardProperties: template,
        members: members.map((m) => ({
          userId: m.userId,
          nickname: m.nickname ?? m.username,
          fullName: m.fullName ?? null,
          role: m.role,
        })),
      })
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'list_tasks',
  {
    title: '查询项目任务列表',
    description: '列出指定项目下的全部任务（含动态属性 properties）。只读。',
    inputSchema: { projectId: z.number().int().positive().describe('项目 ID') },
  },
  async ({ projectId }) => {
    try {
      const tasks = await client.get<TaskVO[]>(`/api/v1/projects/${projectId}/tasks`)
      return json({ total: tasks.length, tasks: tasks.map(compactTask) })
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'search_tasks',
  {
    title: '全局搜索任务',
    description: '按关键词搜索当前用户可见项目内的任务。只读。',
    inputSchema: { keyword: z.string().max(100).describe('搜索关键词') },
  },
  async ({ keyword }) => {
    try {
      const tasks = await client.get<TaskVO[]>(
        `/api/v1/tasks/search?keyword=${encodeURIComponent(keyword)}`,
      )
      return json({ total: tasks.length, tasks: tasks.map(compactTask) })
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'get_dashboard',
  {
    title: '查询仪表盘',
    description: '跨项目任务统计：状态分布、P0 任务、临近截止任务等。只读。',
    inputSchema: {},
  },
  async () => {
    try {
      return json(await client.get<unknown>('/api/v1/dashboard'))
    } catch (err) {
      return failResult(err)
    }
  },
)

/* ------------------------- 写入工具 ------------------------- */

server.registerTool(
  'create_project',
  {
    title: '创建项目',
    description:
      '创建新项目，当前用户自动成为负责人。写入规范：name 单行 ≤100 字符；description 为纯 Markdown ≤500 字符；type 仅 public/private，默认 private。',
    inputSchema: {
      name: z.string().describe('项目名称（单行，≤100 字符）'),
      description: z.string().optional().describe('项目描述（纯 Markdown，≤500 字符）'),
      type: z.enum(['public', 'private']).optional().describe('项目可见性，默认 private'),
      icon: z.string().optional().describe('项目图标（emoji）'),
    },
  },
  async (args) => {
    try {
      const body = {
        name: sanitizeTitle(args.name, 100, 'name'),
        description: sanitizeMarkdown(args.description, 500),
        type: args.type === 'public' ? 'O' : 'P',
        icon: sanitizeIcon(args.icon),
      }
      return json(await client.post<ProjectVO>('/api/v1/projects', body))
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'update_project',
  {
    title: '更新项目',
    description:
      '更新项目基础信息（读取当前值后合并，只覆盖传入的字段）。写入规范同 create_project；需要负责人权限。',
    inputSchema: {
      projectId: z.number().int().positive().describe('项目 ID'),
      name: z.string().optional().describe('项目名称（单行，≤100 字符）'),
      description: z.string().optional().describe('项目描述（纯 Markdown，≤500 字符，传空串清除）'),
      type: z.enum(['public', 'private']).optional().describe('项目可见性'),
      icon: z.string().optional().describe('项目图标（emoji，传空串移除）'),
    },
  },
  async (args) => {
    try {
      const current = await client.get<ProjectVO>(`/api/v1/projects/${args.projectId}`)
      const body = {
        name: args.name !== undefined ? sanitizeTitle(args.name, 100, 'name') : current.name,
        description:
          args.description !== undefined ? sanitizeMarkdown(args.description, 500) : current.description,
        type: args.type === undefined ? current.type : args.type === 'public' ? 'O' : 'P',
        icon: args.icon !== undefined ? sanitizeIcon(args.icon) : current.icon,
      }
      return json(await client.put<ProjectVO>(`/api/v1/projects/${args.projectId}`, body))
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'create_task',
  {
    title: '创建任务',
    description:
      '在项目下创建任务。写入规范：title 单行 ≤200 字符；description 为纯 Markdown；properties 必须严格匹配项目 cardProperties 模板（先用 get_project 查看），select 用选项 id、日期用 YYYY-MM-DD、成员用 userId。同名任务已存在时会被拒绝（防重复污染），除非显式 allowDuplicate=true。',
    inputSchema: {
      projectId: z.number().int().positive().describe('项目 ID'),
      title: z.string().describe('任务标题（单行，≤200 字符）'),
      description: z.string().optional().describe('任务描述（纯 Markdown）'),
      icon: z.string().optional().describe('任务图标（emoji）'),
      properties: z
        .record(z.unknown())
        .optional()
        .describe('动态属性键值对，键必须是项目模板中的属性 id'),
      allowDuplicate: z.boolean().optional().describe('允许创建同名任务，默认 false'),
    },
  },
  async (args) => {
    try {
      const title = sanitizeTitle(args.title, 200)
      const description = sanitizeMarkdown(args.description, 20000)
      const icon = sanitizeIcon(args.icon)

      const { template, members } = await loadProjectContext(args.projectId)

      // 防重复创建：同名任务已存在时拒绝，引导改用 update_task
      const existing = await client.get<TaskVO[]>(`/api/v1/projects/${args.projectId}/tasks`)
      const dup = existing.find((t) => t.title === title)
      if (dup && !args.allowDuplicate) {
        throw new ValidationError(
          `项目中已存在同名任务「${title}」(id=${dup.id})，禁止重复创建。` +
            `如需修改请调用 update_task；确需重复请显式传入 allowDuplicate=true。`,
        )
      }

      const properties = args.properties ? validateProperties(template, args.properties, members) : undefined
      const body = { title, description, icon, properties }
      return json(await client.post<TaskVO>(`/api/v1/projects/${args.projectId}/tasks`, body))
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'update_task',
  {
    title: '更新任务',
    description:
      '更新任务（只覆盖传入的字段）。properties 默认与现有属性合并（只改传入的键），传 replaceAllProperties=true 才整体替换。写入规范同 create_task。',
    inputSchema: {
      projectId: z.number().int().positive().describe('任务所属项目 ID'),
      taskId: z.number().int().positive().describe('任务 ID'),
      title: z.string().optional().describe('任务标题（单行，≤200 字符）'),
      description: z.string().optional().describe('任务描述（纯 Markdown，传空串清除）'),
      icon: z.string().optional().describe('任务图标（emoji，传空串移除）'),
      properties: z
        .record(z.unknown())
        .optional()
        .describe('要更新的属性键值对（与现有属性合并）；值为 null 表示清空该属性'),
      replaceAllProperties: z.boolean().optional().describe('整体替换所有属性，默认 false（合并）'),
    },
  },
  async (args) => {
    try {
      const { template, members } = await loadProjectContext(args.projectId)

      const tasks = await client.get<TaskVO[]>(`/api/v1/projects/${args.projectId}/tasks`)
      const current = tasks.find((t) => t.id === args.taskId)
      if (!current) throw new ValidationError(`任务 ${args.taskId} 不存在于项目 ${args.projectId} 中`)

      const title = args.title !== undefined ? sanitizeTitle(args.title, 200) : current.title
      const description =
        args.description !== undefined ? sanitizeMarkdown(args.description, 20000) : current.description
      const icon = args.icon !== undefined ? sanitizeIcon(args.icon) : (current.icon ?? undefined)

      let properties: Record<string, unknown> | undefined
      if (args.properties) {
        const cleaned = validateProperties(template, args.properties, members)
        properties = args.replaceAllProperties ? cleaned : { ...(current.properties ?? {}), ...cleaned }
      } else {
        properties = current.properties
      }

      const body = { title, description, icon, properties }
      return json(await client.put<TaskVO>(`/api/v1/tasks/${args.taskId}`, body))
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'add_comment',
  {
    title: '添加任务评论',
    description:
      '给任务添加评论（Markdown）。写入规范：content ≤5000 字符纯 Markdown；mentionUserIds 必须是项目成员 userId（先用 get_project 查看成员）。',
    inputSchema: {
      projectId: z.number().int().positive().describe('任务所属项目 ID'),
      taskId: z.number().int().positive().describe('任务 ID'),
      content: z.string().describe('评论内容（纯 Markdown，≤5000 字符）'),
      mentionUserIds: z.array(z.number().int().positive()).optional().describe('@提及的成员 userId 列表'),
    },
  },
  async (args) => {
    try {
      const content = sanitizeMarkdown(args.content, 5000, 'content')
      if (!content) throw new ValidationError('content 不能为空（净化后无有效内容）')

      const { members } = await loadProjectContext(args.projectId)
      const mentionUserIds = args.mentionUserIds ? validateMentionIds(args.mentionUserIds, members) : undefined

      const body = {
        type: 'comment',
        parentId: args.taskId,
        title: content,
        mentionUserIds,
      }
      return json(await client.post<unknown>(`/api/v1/projects/${args.projectId}/blocks`, body))
    } catch (err) {
      return failResult(err)
    }
  },
)

/* ------------------------- 危险操作（需显式确认） ------------------------- */

server.registerTool(
  'delete_task',
  {
    title: '删除任务',
    description: '删除任务（不可恢复）。危险操作：必须显式传入 confirm=true，且仅在用户明确要求时调用。',
    inputSchema: {
      taskId: z.number().int().positive().describe('任务 ID'),
      confirm: z.literal(true).describe('必须显式传 true 才会执行删除'),
    },
  },
  async (args) => {
    try {
      await client.delete<void>(`/api/v1/tasks/${args.taskId}`)
      return json({ deleted: true, taskId: args.taskId })
    } catch (err) {
      return failResult(err)
    }
  },
)

server.registerTool(
  'delete_project',
  {
    title: '删除项目',
    description: '删除项目（软删除，含项目下全部任务）。危险操作：必须显式传入 confirm=true，且仅在用户明确要求时调用。',
    inputSchema: {
      projectId: z.number().int().positive().describe('项目 ID'),
      confirm: z.literal(true).describe('必须显式传 true 才会执行删除'),
    },
  },
  async (args) => {
    try {
      await client.delete<void>(`/api/v1/projects/${args.projectId}`)
      return json({ deleted: true, projectId: args.projectId })
    } catch (err) {
      return failResult(err)
    }
  },
)

/* ------------------------- 启动 ------------------------- */

async function main() {
  const transport = new StdioServerTransport()
  await server.connect(transport)
  console.error(`[protask-mcp] 已启动，后端: ${BASE_URL}，用户: ${USERNAME}`)
}

main().catch((err) => {
  console.error('[protask-mcp] 启动失败:', err)
  process.exit(1)
})
