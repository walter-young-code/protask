import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as categoryApi from '@/api/category'
import type { CategoryVO } from '@/types'

/** 项目分类全局状态（分类私有：仅当前用户可见） */
export const useCategoryStore = defineStore('category', () => {
  const categories = ref<CategoryVO[]>([])

  /** 加载当前用户的分类 */
  async function fetchCategories() {
    categories.value = await categoryApi.listCategories()
  }

  /** 新建分类并刷新列表 */
  async function createCategory(name: string): Promise<CategoryVO> {
    const category = await categoryApi.createCategory({ name })
    await fetchCategories()
    return category
  }

  /** 修改分类名称并刷新列表 */
  async function updateCategory(id: number, name: string): Promise<CategoryVO> {
    const category = await categoryApi.updateCategory(id, { name })
    await fetchCategories()
    return category
  }

  /** 删除分类并刷新列表 */
  async function removeCategory(id: number) {
    await categoryApi.deleteCategory(id)
    await fetchCategories()
  }

  return { categories, fetchCategories, createCategory, updateCategory, removeCategory }
})
