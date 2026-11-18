import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import zhCN from '@/locales/zh-CN'
import TaskDetailDialog from '@/components/TaskDetailDialog.vue'
import * as taskApi from '@/api/task'

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

describe('TaskDetailDialog WBS 编码前缀于名称', () => {
  const wbsTask = { id: 2, title: 'Beta', description: '', icon: '', properties: {}, wbs: '2.1' } as never
  function mountWbs(editable: boolean, hasWbs = true) {
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
    return mount(TaskDetailDialog, {
      props: { modelValue: true, task: wbsTask, projectId: 1, cardProperties: [], editable, commentable: true, hasWbs },
      global: { plugins: [createPinia(), i18n, ElementPlus] },
      attachTo: document.body,
    })
  }

  it('编辑态：前缀徽标位于标题输入框前，无独立 WBS 属性行', async () => {
    const wrapper = mountWbs(true)
    await nextTick()
    await nextTick()
    const chip = document.querySelector('.detail-wbs-prefix')
    expect(chip?.textContent?.trim()).toBe('2.1')
    expect(document.querySelector('.detail-title input')).toBeTruthy()
    const labels = [...document.querySelectorAll('.property-form .el-form-item__label')].map((l) => l.textContent || '')
    expect(labels.some((l) => l.includes('WBS'))).toBe(false)
    wrapper.unmount()
  })

  it('只读态：前缀徽标位于只读标题前', async () => {
    const wrapper = mountWbs(false)
    await nextTick()
    await nextTick()
    expect(document.querySelector('.detail-wbs-prefix')?.textContent?.trim()).toBe('2.1')
    expect(document.querySelector('.detail-title-readonly')?.textContent?.trim()).toBe('Beta')
    expect(document.querySelector('.detail-title input')).toBeNull()
    wrapper.unmount()
  })

  it('未启用 WBS 时不渲染前缀徽标', async () => {
    const wrapper = mountWbs(true, false)
    await nextTick()
    await nextTick()
    expect(document.querySelector('.detail-wbs-prefix')).toBeNull()
    wrapper.unmount()
  })
})

describe('TaskDetailDialog 里程碑类型菜单', () => {
  function mountMilestone(taskExtra: Record<string, unknown>, hasWbs = true) {
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
    return mount(TaskDetailDialog, {
      props: {
        modelValue: false,
        task: {
          id: 3,
          title: 'M1',
          description: '',
          icon: '',
          properties: { startDate: '2026-11-18', dueDate: '2026-11-18' },
          wbs: '2.1.1',
          ...taskExtra,
        } as never,
        projectId: 1,
        cardProperties: [],
        editable: true,
        commentable: true,
        hasWbs,
      },
      global: { plugins: [createPinia(), i18n, ElementPlus] },
      attachTo: document.body,
    })
  }

  async function open(wrapper: ReturnType<typeof mountMilestone>) {
    await wrapper.setProps({ modelValue: true })
    await nextTick()
    await nextTick()
  }

  /** 展开「更多」菜单并取可见 popper 内的菜单项（排除上一次挂载残留的隐藏 popper） */
  async function openMore() {
    const btn = document.querySelector('.detail-more-btn') as HTMLElement
    btn.click()
    await nextTick()
    await nextTick()
    await new Promise((r) => setTimeout(r, 100))
    return [...document.querySelectorAll('.el-dropdown-menu__item')].filter((i) => {
      const popper = i.closest('.el-popper') as HTMLElement | null
      if (!popper) return true
      const cs = getComputedStyle(popper)
      return cs.display !== 'none' && cs.visibility !== 'hidden'
    })
  }

  it('WBS 项目「更多」渲染类型菜单，非 WBS 不渲染', async () => {
    const wrapper = mountMilestone({})
    await open(wrapper)
    const texts = (await openMore()).map((i) => i.textContent || '')
    expect(texts.some((t) => t.includes('普通任务'))).toBe(true)
    expect(texts.some((t) => t.includes('里程碑'))).toBe(true)
    wrapper.unmount()

    const plain = mountMilestone({}, false)
    await open(plain)
    const plainTexts = (await openMore()).map((i) => i.textContent || '')
    expect(plainTexts.some((t) => t.includes('里程碑'))).toBe(false)
    plain.unmount()
  })

  it('摘要任务（childCount>0）里程碑项禁用', async () => {
    const wrapper = mountMilestone({ childCount: 2 })
    await open(wrapper)
    const ms = (await openMore()).find((i) => (i.textContent || '').includes('里程碑')) as HTMLElement
    expect(ms).toBeTruthy()
    expect(ms.classList.contains('is-disabled')).toBe(true)
    wrapper.unmount()
  })

  it('起止跨日任务里程碑项禁用', async () => {
    const wrapper = mountMilestone({
      properties: { startDate: '2026-11-01', dueDate: '2026-11-14' },
    })
    await open(wrapper)
    const ms = (await openMore()).find((i) => (i.textContent || '').includes('里程碑')) as HTMLElement
    expect(ms.classList.contains('is-disabled')).toBe(true)
    wrapper.unmount()
  })

  it('选择里程碑即保存且 payload 携带 milestone', async () => {
    const updateTask = vi.mocked(taskApi.updateTask)
    updateTask.mockResolvedValue({ id: 3, title: 'M1' } as never)
    const wrapper = mountMilestone({})
    await open(wrapper)
    const ms = (await openMore()).find((i) => (i.textContent || '').includes('里程碑')) as HTMLElement
    ms.click()
    await nextTick()
    await new Promise((r) => setTimeout(r, 0))
    expect(updateTask).toHaveBeenCalled()
    const payload = updateTask.mock.calls.at(-1)?.[1] as { milestone?: boolean }
    expect(payload.milestone).toBe(true)
    wrapper.unmount()
  })
})
