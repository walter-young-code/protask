import { del, get, post, put } from './request'
import type { CategoryRequest, CategoryVO } from '@/types'

/** 查询全部分类 */
export const listCategories = () => get<CategoryVO[]>('/v1/categories')

/** 新建分类 */
export const createCategory = (data: CategoryRequest) => post<CategoryVO>('/v1/categories', data)

/** 修改分类名称（仅创建者） */
export const updateCategory = (id: number, data: CategoryRequest) =>
  put<CategoryVO>(`/v1/categories/${id}`, data)

/** 删除分类（仅创建者可删，项目置为未分类） */
export const deleteCategory = (id: number) => del<void>(`/v1/categories/${id}`)
