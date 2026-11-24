import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePropertySchema } from '@/composables/usePropertySchema'
import * as projectApi from '@/api/project'
import { appMessage } from '@/utils/appMessage'
import type { CardProperty } from '@/types'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('@/api/project', () => ({
  updateCardProperties: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/utils/appMessage', () => ({
  appMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

const status: CardProperty = { id: 'status', name: '状态', type: 'select' }
const priority: CardProperty = { id: 'priority', name: '优先级', type: 'select' }
const note: CardProperty = { id: 'p1', name: '备注', type: 'text' }

function setup(template: CardProperty[]) {
  let current = [...template]
  const changed = vi.fn()
  const schema = usePropertySchema({
    projectId: () => 1,
    template: () => current,
    changed: () => {
      changed()
    },
  })
  return { schema, changed, setTemplate: (next: CardProperty[]) => (current = next) }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('usePropertySchema.reorderProperties（拖拽排序）', () => {
  it('按 id 序列重排模板并静默保存（不弹成功提示），成功后回调 changed', async () => {
    const { schema, changed } = setup([status, priority, note])
    await schema.reorderProperties(['p1', 'status', 'priority'])
    expect(projectApi.updateCardProperties).toHaveBeenCalledWith(1, [note, status, priority])
    expect(appMessage.success).not.toHaveBeenCalled()
    expect(changed).toHaveBeenCalledTimes(1)
  })

  it('id 序列与模板数量不一致（未知/缺失 id）时不保存', async () => {
    const { schema, changed } = setup([status, priority, note])
    await schema.reorderProperties(['p1', 'status', 'unknown'])
    await schema.reorderProperties(['p1', 'status'])
    expect(projectApi.updateCardProperties).not.toHaveBeenCalled()
    expect(changed).not.toHaveBeenCalled()
  })

  it('顺序未变时仍走整体替换保存（幂等）', async () => {
    const { schema } = setup([status, priority, note])
    await schema.reorderProperties(['status', 'priority', 'p1'])
    expect(projectApi.updateCardProperties).toHaveBeenCalledWith(1, [status, priority, note])
  })
})