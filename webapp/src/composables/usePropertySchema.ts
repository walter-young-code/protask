import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import * as projectApi from '@/api/project'
import { appMessage } from '@/utils/appMessage'
import { BUILTIN_PROPERTIES, createPropertyId, hasWbsProperty, isGanttRequiredProperty, START_DATE_PROPERTY_ID } from '@/utils/meta'
import type { CardProperty, CustomPropertyType } from '@/types'

interface UsePropertySchemaOptions {
  /** 项目 id（属性模板挂在项目上） */
  projectId: () => number
  /** 当前项目的属性模板（随父级数据刷新） */
  template: () => CardProperty[]
  /** 模板保存成功后回调父级重新加载项目 */
  changed: () => void
  /** 属性删除后清理依赖该属性的本地状态（如表单里的属性值） */
  onDeleted?: (property: CardProperty) => void
}

/**
 * 项目属性定义管理：改名 / 改类型 / 选项编辑 / 新增 / 删除，
 * 统一走「自定义属性整体替换」接口，父级负责在 changed 后刷新模板数据。
 */
export function usePropertySchema(options: UsePropertySchemaOptions) {
  const { t } = useI18n()
  /** 刚新增的自定义属性 ID（供调用方自动展开其编辑器） */
  const newPropertyId = ref('')

  /** 保存属性模板（整体替换；notify=false 时不弹保存成功提示） */
  async function saveTemplate(template: CardProperty[], notify = true) {
    await projectApi.updateCardProperties(options.projectId(), template)
    if (notify) {
      appMessage.success(t('property.saved'))
    }
    options.changed()
  }

  /** 属性定义变更：改类型需确认（旧值可能无法匹配新类型） */
  async function changeProperty(updated: CardProperty) {
    const template = options.template()
    const old = template.find((p) => p.id === updated.id)
    if (old && old.type !== updated.type) {
      try {
        await ElMessageBox.confirm(
          t('property.typeChangeConfirm', { name: old.name }),
          t('property.typeChangeTitle'),
          {
            type: 'warning',
            confirmButtonText: t('property.typeChangeAction'),
            cancelButtonText: t('common.cancel'),
          },
        )
      } catch {
        return
      }
    }
    await saveTemplate(template.map((p) => (p.id === updated.id ? updated : p)))
  }

  /** 新增自定义属性：以类型默认名创建并立即保存 */
  async function addProperty(type: CustomPropertyType) {
    const prop: CardProperty = {
      id: createPropertyId(),
      name: t(`propertyType.${type}`),
      type,
      options:
        type === 'select' || type === 'multiSelect'
          ? [{ id: createPropertyId(), value: t('property.defaultOption') }]
          : undefined,
    }
    newPropertyId.value = prop.id
    await saveTemplate([...options.template(), prop])
  }

  /** 内置属性是否已存在于模板中（避免重复添加） */
  const builtinAdded = (prop: CardProperty): boolean =>
    options.template().some((p) => p.id === prop.id)

  /** 一键添加内置属性：选项已预配，已存在则跳过；开始日期需先有 WBS 编码 */
  async function addBuiltinProperty(prop: CardProperty) {
    if (builtinAdded(prop)) return
    if (prop.id === START_DATE_PROPERTY_ID && !hasWbsProperty(options.template())) {
      appMessage.warning(t('property.startDateRequiresWbs'))
      return
    }
    await saveTemplate([...options.template(), prop])
  }

  /** 添加属性下拉命令分发（builtin:xxx 为内置属性，其余为自定义类型） */
  async function addPropertyCommand(command: string) {
    const builtin = BUILTIN_PROPERTIES.find((p) => `builtin:${p.id}` === command)
    if (builtin) {
      await addBuiltinProperty(builtin)
      return
    }
    await addProperty(command as CustomPropertyType)
  }

  /** 拖拽排序：按 id 序列重排模板后静默保存（必要字段置顶由展示层/后端规范化保证） */
  async function reorderProperties(orderedIds: string[]) {
    const template = options.template()
    const byId = new Map(template.map((p) => [p.id, p]))
    const reordered = orderedIds
      .map((id) => byId.get(id))
      .filter((p): p is CardProperty => Boolean(p))
    if (reordered.length !== template.length) return
    await saveTemplate(reordered, false)
  }

  /** 删除属性：确认后从模板移除，后端级联清理全部卡片上的对应值 */
  async function deleteProperty(prop: CardProperty) {
    // 甘特必要字段锁定：启用（含 WBS）后 WBS/开始日期/截止日期不可删（后端兜底校验）
    if (hasWbsProperty(options.template()) && isGanttRequiredProperty(prop.id)) {
      appMessage.warning(t('property.requiredLockedHint'))
      return
    }
    try {
      await ElMessageBox.confirm(
        t('property.deleteConfirmSubtext', { name: prop.name }),
        t('property.deleteConfirmTitle'),
        {
          type: 'warning',
          confirmButtonText: t('common.delete'),
          cancelButtonText: t('common.cancel'),
        },
      )
    } catch {
      return
    }
    const template = options.template()
    await saveTemplate(template.filter((p) => p.id !== prop.id), false)
    options.onDeleted?.(prop)
    appMessage.success(t('property.deleted', { name: prop.name }))
  }

  return {
    newPropertyId,
    saveTemplate,
    changeProperty,
    addProperty,
    builtinAdded,
    addBuiltinProperty,
    addPropertyCommand,
    reorderProperties,
    deleteProperty,
  }
}
