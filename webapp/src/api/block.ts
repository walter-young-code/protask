import { del, get, patch, post } from './request'
import type { BlockPatchRequest, BlockRequest, BlockVO } from '@/types'

/** 查询项目下的块列表（可按类型过滤） */
export const listBlocks = (projectId: number, type?: string) =>
  get<BlockVO[]>(`/v1/projects/${projectId}/blocks${type ? `?type=${type}` : ''}`)

/** 在项目下创建块 */
export const createBlock = (projectId: number, data: BlockRequest) =>
  post<BlockVO>(`/v1/projects/${projectId}/blocks`, data)

/** 局部更新块 */
export const patchBlock = (id: number, data: BlockPatchRequest) =>
  patch<BlockVO>(`/v1/blocks/${id}`, data)

/** 软删除块 */
export const deleteBlock = (id: number) => del<void>(`/v1/blocks/${id}`)
