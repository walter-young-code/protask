import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import zhCN from '@/locales/zh-CN'
import TaskDetailDialog from '@/components/TaskDetailDialog.vue'

vi.mock('@/api/task', () => ({
  updateTask: vi.fn(),
  deleteTask: vi.fn(),
  copyTask: vi.fn(),
}))
vi.mock('@/api/block', () => ({
  listBlocks: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/api/attachment', () => ({
  ATTACHMENT_PAGE_SIZE: 20,
  listAttachments: vi.fn().mockResolvedValue({ total: 0, records: [] }),
  uploadAttachment: vi.fn(),
  deleteAttachment: vi.fn(),
  previewAttachment: vi.fn(),
  downloadAttachment: vi.fn(),
}))

const task = {
  id: 1,
  title: '测试任务',
  description: 'ab cdef',
  icon: '',
  properties: {},
} as never

function mountDialog() {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(TaskDetailDialog, {
    props: {
      modelValue: false,
      task,
      projectId: 1,
      cardProperties: [],
      editable: true,
      commentable: true,
    },
    global: { plugins: [createPinia(), i18n, ElementPlus] },
    attachTo: document.body,
  })
}

describe('TaskDetailDialog 描述工具条光标插入', () => {
  it('失焦后点击加粗按钮，语法插入到原光标处而非文本末尾', async () => {
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await nextTick()
    await nextTick()
    // 进入编辑模式（默认预览，双击预览区切换）
    await wrapper.find('.markdown-body').trigger('dblclick')
    await nextTick()

    const textarea = document.querySelector('.detail-desc-editor textarea') as HTMLTextAreaElement
    expect(textarea).toBeTruthy()

    // 模拟：光标落在下标 2（"ab| cdef"）后焦点被按钮抢走
    textarea.focus()
    textarea.setSelectionRange(2, 2)
    textarea.dispatchEvent(new Event('keyup'))
    textarea.blur()
    await nextTick()

    const boldBtn = document.querySelector('.markdown-action-btn') as HTMLElement
    boldBtn.click()
    await nextTick()
    await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))

    expect(textarea.value).toBe('ab**加粗文本** cdef')
    wrapper.unmount()
  })
})
