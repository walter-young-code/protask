/**
 * 密码强度检测工具：与后端 PasswordStrengthValidator 保持一致的评分规则。
 *
 * 评分维度（4 类字符）：大写字母、小写字母、数字、特殊字符
 * 规则：至少 8 位，需包含至少 3 类字符
 */

/** 密码强度等级：0=空 / 1=弱 / 2=中 / 3=强 / 4=非常强 */
export type PasswordStrengthLevel = 0 | 1 | 2 | 3 | 4

export interface PasswordStrengthResult {
  /** 强度等级 0-4 */
  level: PasswordStrengthLevel
  /** 是否满足后端校验规则（≥8 位且 ≥3 类字符） */
  valid: boolean
  /** 已包含的字符类别数 */
  categories: number
}

/** 统计密码包含的字符类别数 */
export function countCategories(password: string): number {
  if (!password) return 0
  let count = 0
  if (/[A-Z]/.test(password)) count++
  if (/[a-z]/.test(password)) count++
  if (/[0-9]/.test(password)) count++
  if (/[^A-Za-z0-9]/.test(password)) count++
  return count
}

/** 计算密码强度等级 */
export function getPasswordStrength(password: string): PasswordStrengthResult {
  if (!password || password.length < 8) {
    return { level: password ? 1 : 0, valid: false, categories: countCategories(password) }
  }
  const categories = countCategories(password)
  let level: PasswordStrengthLevel
  if (categories < 2) {
    level = 1
  } else if (categories === 2) {
    level = 2
  } else if (categories === 3) {
    level = password.length >= 10 ? 4 : 3
  } else {
    level = 4
  }
  return { level, valid: categories >= 3, categories }
}

/** 表单校验器：供 Element Plus FormRules 的 validator 使用 */
export function passwordStrengthValidator(
  _rule: unknown,
  value: string,
  callback: (err?: Error) => void,
  errorMessage: string,
) {
  if (value && !getPasswordStrength(value).valid) {
    callback(new Error(errorMessage))
  } else {
    callback()
  }
}
