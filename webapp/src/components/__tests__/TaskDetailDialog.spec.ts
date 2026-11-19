import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import zhCN from '@/locales/zh-CN'
import TaskDetailDialog from '@/components/TaskDetailDialog.vue'
import TaskSettingsDialog from '@/components/TaskSettingsDialog.vue'
import PropertyValueInput from '@/components/PropertyValueInput.vue'
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
vi.mock('@/api/gantt', () => ({
  listDependencies: vi.fn().mockResolvedValue([]),
  createDependency: vi.fn(),
  updateDependency: vi.fn(),
  deleteDependency: vi.fn(),
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

describe('TaskDetailDialog 任务设置入口', () => {
  function mountWbsTask(taskExtra: Record<string, unknown>, hasWbs = true) {
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
        tasks: [],
        dependencies: [],
      },
      global: { plugins: [createPinia(), i18n, ElementPlus] },
      attachTo: document.body,
    })
  }

  async function open(wrapper: ReturnType<typeof mountWbsTask>) {
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

  it('WBS 项目「更多」渲染任务设置项（不再有类型两项），非 WBS 不渲染', async () => {
    const wrapper = mountWbsTask({})
    await open(wrapper)
    const texts = (await openMore()).map((i) => i.textContent || '')
    expect(texts.some((t) => t.includes('任务设置'))).toBe(true)
    expect(texts.some((t) => t.includes('普通任务'))).toBe(false)
    expect(texts.some((t) => t.includes('里程碑'))).toBe(false)
    wrapper.unmount()

    const plain = mountWbsTask({}, false)
    await open(plain)
    const plainTexts = (await openMore()).map((i) => i.textContent || '')
    expect(plainTexts.some((t) => t.includes('任务设置'))).toBe(false)
    plain.unmount()
  })

  it('属性区不再渲染父任务选择行（已移入任务设置）', async () => {
    const wrapper = mountWbsTask({})
    await open(wrapper)
    const labels = [...document.querySelectorAll('.property-form .el-form-item__label')].map((l) => l.textContent || '')
    expect(labels.some((l) => l.includes('父任务'))).toBe(false)
    wrapper.unmount()
  })

  it('点击任务设置打开设置弹窗', async () => {
    const wrapper = mountWbsTask({})
    await open(wrapper)
    const item = (await openMore()).find((i) => (i.textContent || '').includes('任务设置')) as HTMLElement
    item.click()
    await nextTick()
    await new Promise((r) => setTimeout(r, 100))
    expect(document.querySelector('.settings-body')).toBeTruthy()
    wrapper.unmount()
  })

  it('设置弹窗 patch 写入表单并保存：payload 携带 milestone / parentTaskId', async () => {
    const updateTask = vi.mocked(taskApi.updateTask)
    updateTask.mockResolvedValue({ id: 3, title: 'M1' } as never)
    const wrapper = mountWbsTask({})
    await open(wrapper)
    const settings = wrapper.findComponent(TaskSettingsDialog)
    settings.vm.$emit('patch', { milestone: true })
    await new Promise((r) => setTimeout(r, 0))
    let payload = updateTask.mock.calls.at(-1)?.[1] as { milestone?: boolean; parentTaskId?: number }
    expect(payload.milestone).toBe(true)
    settings.vm.$emit('patch', { parentTaskId: 9 })
    await new Promise((r) => setTimeout(r, 0))
    payload = updateTask.mock.calls.at(-1)?.[1] as { milestone?: boolean; parentTaskId?: number }
    expect(payload.parentTaskId).toBe(9)
    expect(payload.milestone).toBe(true) // 局部更新不丢失前一次 patch
    wrapper.unmount()
  })
})

describe('TaskDetailDialog 排程设置透传与自动任务日期语义', () => {
  const dateProps = [
    { id: 'startDate', name: '开始日期', type: 'date' },
    { id: 'dueDate', name: '截止日期', type: 'date' },
  ] as never

  function mountSchedule(taskExtra: Record<string, unknown>) {
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
    return mount(TaskDetailDialog, {
      props: {
        modelValue: false,
        task: {
          id: 4,
          title: '自动任务',
          description: '',
          icon: '',
          properties: { startDate: '2026-11-01', dueDate: '2026-11-03' },
          ...taskExtra,
        } as never,
        projectId: 1,
        cardProperties: dateProps,
        editable: true,
        commentable: true,
        hasWbs: true,
        tasks: [],
        dependencies: [],
      },
      global: { plugins: [createPinia(), i18n, ElementPlus] },
      attachTo: document.body,
    })
  }

  async function openSchedule(wrapper: ReturnType<typeof mountSchedule>) {
    await wrapper.setProps({ modelValue: true })
    await nextTick()
    await nextTick()
  }

  it('设置 patch 透传保存：scheduleMode 常驻，durationDays/clearConstraint 一次性下发', async () => {
    const updateTask = vi.mocked(taskApi.updateTask)
    updateTask.mockResolvedValue({
      id: 4,
      title: '自动任务',
      scheduleMode: 'auto',
      properties: { startDate: '2026-11-01', dueDate: '2026-11-03' },
    } as never)
    const wrapper = mountSchedule({})
    await openSchedule(wrapper)
    const settings = wrapper.findComponent(TaskSettingsDialog)

    settings.vm.$emit('patch', { scheduleMode: 'auto' })
    await new Promise((r) => setTimeout(r, 0))
    let payload = updateTask.mock.calls.at(-1)?.[1] as unknown as Record<string, unknown>
    expect(payload.scheduleMode).toBe('auto')
    expect(payload.durationDays).toBeUndefined()

    settings.vm.$emit('patch', { durationDays: 5 })
    await new Promise((r) => setTimeout(r, 0))
    payload = updateTask.mock.calls.at(-1)?.[1] as unknown as Record<string, unknown>
    expect(payload.durationDays).toBe(5)
    expect(payload.scheduleMode).toBe('auto')

    // 工期补丁一次性：后续普通保存不再钉住工期
    settings.vm.$emit('patch', { milestone: false })
    await new Promise((r) => setTimeout(r, 0))
    payload = updateTask.mock.calls.at(-1)?.[1] as unknown as Record<string, unknown>
    expect(payload.durationDays).toBeUndefined()

    settings.vm.$emit('patch', { clearConstraint: true })
    await new Promise((r) => setTimeout(r, 0))
    payload = updateTask.mock.calls.at(-1)?.[1] as unknown as Record<string, unknown>
    expect(payload.clearConstraint).toBe(true)
    wrapper.unmount()
  })

  it('自动任务截止日期 picker 禁用并提示由工期推导；开始日期提示约束语义', async () => {
    const wrapper = mountSchedule({ scheduleMode: 'auto' })
    await openSchedule(wrapper)
    const inputs = wrapper.findAllComponents(PropertyValueInput)
    expect(inputs[0].props('disabled')).toBe(false)
    expect(inputs[1].props('disabled')).toBe(true)
    const hints = [...document.querySelectorAll('.property-schedule-hint')].map((h) => h.textContent || '')
    expect(hints.some((h) => h.includes('由开始日期与工期推导'))).toBe(true)
    expect(hints.some((h) => h.includes('不得早于'))).toBe(true)
    wrapper.unmount()
  })

  it('手动任务日期 picker 均可编辑且无排程提示', async () => {
    const wrapper = mountSchedule({})
    await openSchedule(wrapper)
    const inputs = wrapper.findAllComponents(PropertyValueInput)
    expect(inputs[0].props('disabled')).toBe(false)
    expect(inputs[1].props('disabled')).toBe(false)
    expect(document.querySelector('.property-schedule-hint')).toBeNull()
    wrapper.unmount()
  })

  it('保存后按返回 VO 回写日期（引擎吸附回弹可见）', async () => {
    const updateTask = vi.mocked(taskApi.updateTask)
    updateTask.mockResolvedValue({
      id: 4,
      title: '自动任务',
      scheduleMode: 'auto',
      properties: { startDate: '2026-11-04', dueDate: '2026-11-06' },
    } as never)
    const wrapper = mountSchedule({ scheduleMode: 'auto' })
    await openSchedule(wrapper)
    const settings = wrapper.findComponent(TaskSettingsDialog)
    settings.vm.$emit('patch', { scheduleMode: 'auto' })
    await new Promise((r) => setTimeout(r, 0))
    const inputs = wrapper.findAllComponents(PropertyValueInput)
    expect(inputs[0].props('modelValue')).toBe('2026-11-04')
    expect(inputs[1].props('modelValue')).toBe('2026-11-06')
    wrapper.unmount()
  })
})
