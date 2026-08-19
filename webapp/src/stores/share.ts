import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 项目分享访问状态：通过分享链接进入公开项目时记录 shareId，
 * 请求拦截器据此附加 X-Share-Token 请求头（仅授权只读访问）。
 */
export const useShareStore = defineStore('share', () => {
  const shareId = ref<string | null>(null)

  /** 设置当前分享凭证（进入分享项目时调用） */
  function setShareId(id: string) {
    shareId.value = id
  }

  /** 清除分享凭证（离开项目/退出分享模式时调用） */
  function clearShareId() {
    shareId.value = null
  }

  return { shareId, setShareId, clearShareId }
})
