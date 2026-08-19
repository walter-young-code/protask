/**
 * 写入规范守卫：规范 AI Agent 的写入格式，避免产生污染数据。
 *
 * 规则总览：
 * 1. 标题类文本强制单行化（去换行/控制字符/HTML 标签、折叠空白、长度上限）
 * 2. Markdown 正文净化（去控制字符、统一 LF、剥离 script/style 与 HTML 标签）
 * 3. 任务 properties 必须严格对照项目 cardProperties 模板逐项校验：
 *    未知属性 id 一律拒绝；select/multiSelect 值必须命中选项；
 *    date 归一化为 YYYY-MM-DD；member 必须是项目成员 ID
 * 4. 所有校验失败抛出带纠正指引的 ValidationError，引导 Agent 修正而非盲目重试
 */

import type { CardPropertyDef, ProjectMemberVO } from './client.js'

export class ValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ValidationError'
  }
}

/** 匹配 HTML/XML 标签（含 script/style 块整体移除） */
const SCRIPT_BLOCK_RE = /<(script|style)\b[\s\S]*?<\/\1>/gi
const HTML_TAG_RE = /<\/?[a-z][^>]*>/gi
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_RE = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g

function fail(message: string): never {
  throw new ValidationError(message)
}

/**
 * 标题规范化：单行文本。
 * 去 HTML/script、控制字符，折叠连续空白，trim，超长拒绝。
 */
export function sanitizeTitle(raw: string, max: number, fieldLabel = 'title'): string {
  if (typeof raw !== 'string') fail(`${fieldLabel} 必须是字符串`)
  let text = raw.replace(SCRIPT_BLOCK_RE, '').replace(HTML_TAG_RE, '')
  text = text.replace(CONTROL_CHARS_RE, '').replace(/[\r\n\t]+/g, ' ')
  text = text.replace(/\s{2,}/g, ' ').trim()
  if (!text) fail(`${fieldLabel} 不能为空（净化后无有效内容）`)
  if (text.length > max) fail(`${fieldLabel} 长度不能超过 ${max} 字符（当前 ${text.length}）`)
  return text
}

/**
 * Markdown 正文规范化：允许多行。
 * 去 script/style 块与 HTML 标签、控制字符，统一 LF 换行，超长拒绝。
 * 传入 undefined 时原样返回（表示不修改该字段）。
 */
export function sanitizeMarkdown(
  raw: string | undefined,
  max: number,
  fieldLabel = 'description',
): string | undefined {
  if (raw === undefined) return undefined
  if (typeof raw !== 'string') fail(`${fieldLabel} 必须是字符串`)
  let text = raw.replace(SCRIPT_BLOCK_RE, '').replace(HTML_TAG_RE, '')
  text = text.replace(CONTROL_CHARS_RE, '')
  text = text.replace(/\r\n?/g, '\n')
  // 折叠超过 2 个的连续空行，避免无意义的大段空白
  text = text.replace(/\n{3,}/g, '\n\n')
  text = text.replace(/[ \t]+$/gm, '').trim()
  if (text.length > max) fail(`${fieldLabel} 长度不能超过 ${max} 字符（当前 ${text.length}）`)
  return text
}

/**
 * 图标规范化：允许 undefined（不修改）、空串（移除）或短 emoji 文本。
 */
export function sanitizeIcon(raw: string | undefined): string | undefined {
  if (raw === undefined) return undefined
  if (typeof raw !== 'string') fail('icon 必须是字符串')
  const icon = raw.replace(CONTROL_CHARS_RE, '').trim()
  if (icon.length > 32) fail('icon 长度不能超过 32 个字符')
  if (/<|>/.test(icon)) fail('icon 不允许包含 HTML 标签字符')
  return icon
}

/** 将各种日期输入归一化为 YYYY-MM-DD；无法解析则报错 */
function normalizeDate(value: unknown, propId: string): string {
  const s = typeof value === 'string' ? value.trim() : String(value ?? '')
  const match = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (match) {
    const [, y, m, d] = match
    const mm = m.padStart(2, '0')
    const dd = d.padStart(2, '0')
    if (Number(mm) < 1 || Number(mm) > 12 || Number(dd) < 1 || Number(dd) > 31) {
      fail(`属性 ${propId} 的日期无效: ${s}`)
    }
    return `${y}-${mm}-${dd}`
  }
  fail(`属性 ${propId} 是日期类型，请使用 YYYY-MM-DD 格式（收到: ${JSON.stringify(value)}）`)
}

/** select 值解析：接受选项 id 或选项显示文本，统一映射为选项 id */
function resolveOptionId(prop: CardPropertyDef, value: unknown): string {
  const options = prop.options ?? []
  const s = String(value ?? '').trim()
  const byId = options.find((o) => o.id === s)
  if (byId) return byId.id
  const byValue = options.find((o) => o.value.toLowerCase() === s.toLowerCase())
  if (byValue) return byValue.id
  const valid = options.map((o) => `${o.id}(${o.value})`).join(', ') || '无选项'
  fail(
    `属性 ${prop.id} 的值 "${s}" 不在其选项内。合法选项: ${valid}。` +
      `不要自造选项值；若确需新选项，应先由用户在界面上修改项目属性模板。`,
  )
}

/**
 * 依据项目属性模板逐项校验并净化任务 properties。
 * - 模板中不存在的属性 id 一律拒绝（防止 Agent 写入自由格式垃圾字段）
 * - 值类型必须匹配属性类型，并做归一化
 * - null / 空串视为清空该属性
 */
export function validateProperties(
  template: CardPropertyDef[],
  properties: Record<string, unknown>,
  members: ProjectMemberVO[],
): Record<string, unknown> {
  const templateIds = new Set(template.map((p) => p.id))
  const cleaned: Record<string, unknown> = {}

  for (const [key, rawValue] of Object.entries(properties)) {
    if (!templateIds.has(key)) {
      const available = template.map((p) => p.id).join(', ') || '（该项目暂无属性模板）'
      fail(
        `属性 "${key}" 不在项目属性模板中，禁止写入模板外的属性。` +
          `当前项目可用属性: ${available}。请先调用 get_project 查看 cardProperties 模板。`,
      )
    }
    const def = template.find((p) => p.id === key)!

    // 空值统一规范为 null（清空）
    if (rawValue === null || rawValue === undefined || rawValue === '') {
      cleaned[key] = null
      continue
    }

    switch (def.type) {
      case 'select':
        cleaned[key] = resolveOptionId(def, rawValue)
        break
      case 'multiSelect': {
        const arr = Array.isArray(rawValue) ? rawValue : [rawValue]
        const ids = [...new Set(arr.map((v) => resolveOptionId(def, v)))]
        cleaned[key] = ids
        break
      }
      case 'date':
        cleaned[key] = normalizeDate(rawValue, key)
        break
      case 'number': {
        const n = typeof rawValue === 'number' ? rawValue : Number(rawValue)
        if (!Number.isFinite(n)) fail(`属性 ${key} 是数字类型，收到无效值: ${JSON.stringify(rawValue)}`)
        cleaned[key] = n
        break
      }
      case 'checkbox': {
        if (typeof rawValue === 'boolean') cleaned[key] = rawValue
        else if (rawValue === 'true' || rawValue === 'false') cleaned[key] = rawValue === 'true'
        else fail(`属性 ${key} 是复选框类型，请传 true/false（收到: ${JSON.stringify(rawValue)}）`)
        break
      }
      case 'member': {
        const uid = typeof rawValue === 'number' ? rawValue : Number(rawValue)
        if (!Number.isInteger(uid) || uid <= 0) {
          fail(`属性 ${key} 是成员类型，值必须是成员 userId 数字（收到: ${JSON.stringify(rawValue)}）`)
        }
        if (!members.some((m) => m.userId === uid)) {
          const valid = members.map((m) => `${m.userId}(${m.nickname || m.username})`).join(', ') || '无成员'
          fail(`属性 ${key} 的成员 ${uid} 不在项目成员中。项目成员: ${valid}`)
        }
        cleaned[key] = uid
        break
      }
      case 'text':
      default:
        cleaned[key] = sanitizeMarkdown(String(rawValue), 2000, `属性 ${key} 的文本值`) ?? ''
        break
    }
  }
  return cleaned
}

/** @提及成员 ID 校验：必须是项目成员 */
export function validateMentionIds(mentionUserIds: number[], members: ProjectMemberVO[]): number[] {
  const ids = [...new Set(mentionUserIds)]
  for (const uid of ids) {
    if (!members.some((m) => m.userId === uid)) {
      const valid = members.map((m) => `${m.userId}(${m.nickname || m.username})`).join(', ') || '无成员'
      fail(`@提及的用户 ${uid} 不是项目成员。项目成员: ${valid}`)
    }
  }
  return ids
}
