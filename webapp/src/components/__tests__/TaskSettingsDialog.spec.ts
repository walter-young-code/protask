import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus, { ElInputNumber, ElMessageBox, ElRadio, ElRadioGroup, ElSelect } from 'element-plus'
import { nextTick } from 'vue'
import zhCN from '@/locales/zh-CN'
import TaskSettingsDialog from '@/components/TaskSettingsDialog.vue'
import * as ganttApi from '@/api/gantt'

vi.mock('@/api/gantt', () => ({
  createDependency: vi.fn().mockResolvedValue({ id: 99 }),
  updateDependency: vi.fn().mockResolvedValue({ id: 10 }),
  deleteDependency: vi.fn().mockResolvedValue(undefined),
}))

/** 本任务：起止同日、无子孙（里程碑可标记） */
const selfTask = {
  id: 3,
  title: '本任务',
  description: '',
  icon: '',
  wbs: '1.2',
  parentTaskId: 0,
  milestone: false,
  properties: { startDate: '2026-11-18', dueDate: '2026-11-18' },
} as never

const tasks = [
  { id: 1, title: '前置A', wbs: '1.1', properties: {} },
  { id: 2, title: '后置B', wbs: '1.3', properties: {} },
  selfTask,
] as never

/** 前置依赖（后置=本任务）+ 后置依赖（前置=本任务） */
const dependencies = [
  { id: 10, predecessorId: 1, successorId: 3, type: 'FS', lag: 0 },
  { id: 11, predecessorId: 3, successorId: 2, type: 'SS', lag: 2 },
] as never

function mountSettings(overrides: Record<string, unknown> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(TaskSettingsDialog, {
    props: {
      modelValue: true,
      task: selfTask,
      projectId: 1,
      tasks,
      editable: true,
      dependencies,
      ...overrides,
    },
    global: { plugins: [createPinia(), i18n, ElementPlus] },
    attachTo: document.body,
  })
}

/** 等待异步 handler（API mock resolve + emit）落定 */
async function flush() {
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
}

/** 弹窗内 el-select 顺序：0=父任务、1=前置行类型、2=新增任务、3=新增类型 */
function selects(wrapper: ReturnType<typeof mountSettings>) {
  return wrapper.findAllComponents(ElSelect)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('TaskSettingsDialog 父任务/里程碑即改即生效', () => {
  it('父任务变更 emit patch；选「无」回传 null', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const select = selects(wrapper)[0]
    select.vm.$emit('change', 1)
    expect(wrapper.emitted('patch')?.[0]).toEqual([{ parentTaskId: 1 }])
    select.vm.$emit('change', 0)
    expect(wrapper.emitted('patch')?.[1]).toEqual([{ parentTaskId: null }])
    wrapper.unmount()
  })

  it('同日任务可标记里程碑：change true 即 emit patch；无变化不 emit', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const group = wrapper.findAllComponents(ElRadioGroup)[0]
    group.vm.$emit('change', false) // 当前已是普通任务
    expect(wrapper.emitted('patch')).toBeUndefined()
    group.vm.$emit('change', true)
    expect(wrapper.emitted('patch')?.[0]).toEqual([{ milestone: true }])
    wrapper.unmount()
  })

  it('摘要任务里程碑禁用并提示原因，change 不生效', async () => {
    const wrapper = mountSettings({ task: { ...(selfTask as object), childCount: 2 } as never })
    await nextTick()
    const radios = wrapper.findAllComponents(ElRadio)
    expect(radios[1].props('disabled')).toBe(true)
    // 弹窗 append-to-body：DOM 断言走 document
    const hints = [...document.querySelectorAll('.settings-hint')].map((h) => h.textContent || '')
    expect(hints.some((h) => h.includes('摘要任务不可设为里程碑'))).toBe(true)
    wrapper.findAllComponents(ElRadioGroup)[0].vm.$emit('change', true)
    expect(wrapper.emitted('patch')).toBeUndefined()
    wrapper.unmount()
  })

  it('起止跨日任务里程碑禁用', async () => {
    const wrapper = mountSettings({
      task: {
        ...(selfTask as object),
        properties: { startDate: '2026-11-01', dueDate: '2026-11-14' },
      } as never,
    })
    await nextTick()
    expect(wrapper.findAllComponents(ElRadio)[1].props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('仅起止同日')
    wrapper.unmount()
  })
})

describe('TaskSettingsDialog 前置依赖增改删', () => {
  it('类型变更调 updateDependency 并 emit dependenciesChanged', async () => {
    const wrapper = mountSettings()
    await nextTick()
    selects(wrapper)[1].vm.$emit('change', 'SS')
    await flush()
    expect(ganttApi.updateDependency).toHaveBeenCalledWith(10, { type: 'SS' })
    expect(wrapper.emitted('dependenciesChanged')).toHaveLength(1)
    wrapper.unmount()
  })

  it('lag 变更调 updateDependency（支持负数提前量）；同值不请求', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const lag = wrapper.findAllComponents(ElInputNumber)[1]
    lag.vm.$emit('change', 0) // 与当前值相同
    await flush()
    expect(ganttApi.updateDependency).not.toHaveBeenCalled()
    lag.vm.$emit('change', -3)
    await flush()
    expect(ganttApi.updateDependency).toHaveBeenCalledWith(10, { lag: -3 })
    wrapper.unmount()
  })

  it('删除需确认：确认后调 deleteDependency，取消不删', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountSettings()
    await nextTick()
    const deleteBtn = document.querySelector('.dep-row .el-button') as HTMLElement
    deleteBtn.click()
    await flush()
    expect(confirm).toHaveBeenCalled()
    expect(ganttApi.deleteDependency).toHaveBeenCalledWith(10)
    expect(wrapper.emitted('dependenciesChanged')).toHaveLength(1)

    confirm.mockRejectedValue('cancel' as never)
    deleteBtn.click()
    await flush()
    expect(ganttApi.deleteDependency).toHaveBeenCalledTimes(1)
    confirm.mockRestore()
    wrapper.unmount()
  })

  it('添加前置：候选排除自身与已存在前置，创建 payload 含类型与 lag', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const nameSelect = selects(wrapper)[2]
    // tasks 3 个：排除自身(3)与已存在前置(1)，仅剩后置B(2)
    expect(nameSelect.findAllComponents({ name: 'ElOption' })).toHaveLength(1)
    nameSelect.vm.$emit('update:modelValue', 2)
    await nextTick()
    ;(document.querySelector('.dep-add .el-button') as HTMLElement).click()
    await flush()
    expect(ganttApi.createDependency).toHaveBeenCalledWith(1, {
      predecessorId: 2,
      successorId: 3,
      type: 'FS',
      lag: 0,
    })
    expect(wrapper.emitted('dependenciesChanged')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('TaskSettingsDialog 后置只读与权限', () => {
  it('后置任务只读展示类型与 lag，无编辑控件', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const readonly = document.querySelector('.dep-readonly') as HTMLElement
    expect(readonly.textContent).toContain('后置B')
    expect(readonly.textContent).toContain('SS')
    expect(readonly.textContent).toContain('+2天')
    expect(readonly.querySelector('.el-select')).toBeNull()
    expect(readonly.querySelector('.el-input-number')).toBeNull()
    wrapper.unmount()
  })

  it('editable=false：控件全部禁用且无添加行', async () => {
    const wrapper = mountSettings({ editable: false })
    await nextTick()
    expect(selects(wrapper)[0].props('disabled')).toBe(true)
    expect(wrapper.findAllComponents(ElRadioGroup)[0].props('disabled')).toBe(true)
    expect(wrapper.findAllComponents(ElRadioGroup)[1].props('disabled')).toBe(true)
    expect(wrapper.findAllComponents(ElInputNumber)[0].props('disabled')).toBe(true)
    expect(selects(wrapper)[1].props('disabled')).toBe(true)
    expect(document.querySelector('.dep-add')).toBeNull()
    wrapper.unmount()
  })
})

describe('TaskSettingsDialog 排程设置', () => {
  it('计划模式切换 emit patch；同值不 emit', async () => {
    const wrapper = mountSettings()
    await nextTick()
    const modeGroup = wrapper.findAllComponents(ElRadioGroup)[1]
    modeGroup.vm.$emit('change', 'manual') // 存量默认 manual
    expect(wrapper.emitted('patch')).toBeUndefined()
    modeGroup.vm.$emit('change', 'auto')
    expect(wrapper.emitted('patch')?.[0]).toEqual([{ scheduleMode: 'auto' }])
    wrapper.unmount()
  })

  it('工期变更 emit patch；展示值=显式工期优先，其次日期推导', async () => {
    const wrapper = mountSettings({
      task: {
        ...(selfTask as object),
        properties: { startDate: '2026-11-01', dueDate: '2026-11-03' },
      } as never,
    })
    await nextTick()
    const duration = wrapper.findAllComponents(ElInputNumber)[0]
    expect(duration.props('modelValue')).toBe(3) // 日期推导 diff+1
    duration.vm.$emit('change', 3) // 与展示值相同不 emit
    expect(wrapper.emitted('patch')).toBeUndefined()
    duration.vm.$emit('change', 5)
    expect(wrapper.emitted('patch')?.[0]).toEqual([{ durationDays: 5 }])
    wrapper.unmount()

    const explicit = mountSettings({
      task: { ...(selfTask as object), durationDays: 7 } as never,
    })
    await nextTick()
    expect(explicit.findAllComponents(ElInputNumber)[0].props('modelValue')).toBe(7)
    explicit.unmount()
  })

  it('里程碑工期禁用显示 0；摘要任务模式/工期禁用并提示', async () => {
    const milestone = mountSettings({
      task: { ...(selfTask as object), milestone: true } as never,
    })
    await nextTick()
    const duration = milestone.findAllComponents(ElInputNumber)[0]
    expect(duration.props('disabled')).toBe(true)
    expect(duration.props('modelValue')).toBe(0)
    milestone.unmount()

    const summary = mountSettings({
      task: { ...(selfTask as object), childCount: 2 } as never,
    })
    await nextTick()
    expect(summary.findAllComponents(ElRadioGroup)[1].props('disabled')).toBe(true)
    expect(summary.findAllComponents(ElInputNumber)[0].props('disabled')).toBe(true)
    const hints = [...document.querySelectorAll('.settings-hint')].map((h) => h.textContent || '')
    expect(hints.some((h) => h.includes('不参与排程设置'))).toBe(true)
    summary.findAllComponents(ElRadioGroup)[1].vm.$emit('change', 'auto')
    expect(summary.emitted('patch')).toBeUndefined()
    summary.unmount()
  })

  it('自动任务有约束时展示约束行，清除按钮 emit clearConstraint', async () => {
    const wrapper = mountSettings({
      task: { ...(selfTask as object), scheduleMode: 'auto', startConstraint: '2026-11-20' } as never,
    })
    await nextTick()
    const rows = [...document.querySelectorAll('.schedule-row')]
    const constraintRow = rows.find((r) => (r.textContent || '').includes('2026-11-20')) as HTMLElement
    expect(constraintRow).toBeTruthy()
    ;(constraintRow.querySelector('.el-button') as HTMLElement).click()
    await nextTick()
    expect(wrapper.emitted('patch')?.[0]).toEqual([{ clearConstraint: true }])
    wrapper.unmount()

    // 手动任务不展示约束行
    const manual = mountSettings({
      task: { ...(selfTask as object), startConstraint: '2026-11-20' } as never,
    })
    await nextTick()
    expect(document.body.textContent).not.toContain('开始约束（不得早于）')
    manual.unmount()
  })
})