<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import * as taskApi from '@/api/task'
import * as projectApi from '@/api/project'
import { CUSTOM_PROPERTY_TYPES, BUILTIN_PROPERTIES, createPropertyId } from '@/utils/meta'
import PropertyMenu from '@/components/PropertyMenu.vue'
import PropertyValueInput from '@/components/PropertyValueInput.vue'
import IconPicker from '@/components/IconPicker.vue'
import type { CardProperty, CustomPropertyType, TaskVO } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  projectId: number
  /** 编辑的任务；null 表示新建 */
  task: TaskVO | null
  /** 项目卡片动态属性模板 */
  cardProperties?: CardProperty[]
  /** 当前用户是否可管理属性定义（editor+） */
  canManageProperties?: boolean
  /** 新建时的初始属性值（看板列快捷新建预置分组属性值） */
  initialProperties?: Record<string, unknown>
  /** 项目成员映射（userId -> 昵称），member 类型属性的选项来源 */
  memberMap?: Record<number, string>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
  /** 属性模板变更后通知容器重新加载项目 */
  (e: 'properties-changed'): void
}>()

const formRef = ref<FormInstance>()
const loading = ref(false)
const isEdit = computed(() => props.task !== null)
/** 刚新增的属性 ID：自动展开其管理菜单便于改名（参照 Focalboard newTemplateId） */
const newPropertyId = ref('')

const form = reactive({
  title: '',
  description: '',
  /** 任务图标（emoji，空串表示无图标） */
  icon: '',
  /** 动态属性值（按项目 cardProperties 模板渲染） */
  propertyValues: {} as Record<string, unknown>,
})

const rules = computed<FormRules>(() => ({
  title: [
    { required: true, message: t('validation.taskTitleRequired'), trigger: 'blur' },
    { max: 200, message: t('validation.taskTitleLength'), trigger: 'blur' },
  ],
}))

// 打开对话框时初始化表单
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    newPropertyId.value = ''
    if (props.task) {
      form.title = props.task.title
      form.description = props.task.description || ''
      form.icon = props.task.icon ?? ''
    } else {
      form.title = ''
      form.description = ''
      form.icon = ''
    }
    form.propertyValues = {}
    for (const prop of props.cardProperties ?? []) {
      form.propertyValues[prop.id] =
        props.task?.properties?.[prop.id] ?? props.initialProperties?.[prop.id] ?? ''
    }
    formRef.value?.clearValidate()
  },
)

// 新增的属性已随模板下发后清除标记（菜单展开状态由组件自身维持）
watch(
  () => props.cardProperties,
  (properties) => {
    if (newPropertyId.value && properties?.some((p) => p.id === newPropertyId.value)) {
      newPropertyId.value = ''
    }
  },
)

// ---------- 属性模板管理（本弹窗为唯一入口，对齐 Focalboard cardDetailProperties） ----------

/** 保存属性模板（整体替换） */
async function saveProperties(template: CardProperty[], notify = true) {
  await projectApi.updateCardProperties(props.projectId, template)
  if (notify) {
    appMessage.success(t('property.saved'))
  }
  emit('properties-changed')
}

/** 属性定义变更：改类型需确认（旧值可能无法匹配新类型） */
async function handlePropertyChange(updated: CardProperty) {
  const template = props.cardProperties ?? []
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
  await saveProperties(template.map((p) => (p.id === updated.id ? updated : p)))
}

/** 新增属性：以类型默认名创建并立即保存，同时展开其菜单便于改名 */
function handlePropertyAdd(type: CustomPropertyType) {
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
  void saveProperties([...(props.cardProperties ?? []), prop])
}

/** 内置属性是否已存在于模板中（避免重复添加） */
function builtinAdded(prop: CardProperty): boolean {
  return (props.cardProperties ?? []).some((p) => p.id === prop.id)
}

/** 一键添加内置属性：选项已预配，已存在则跳过 */
async function handleAddBuiltinProperty(prop: CardProperty) {
  if (builtinAdded(prop)) return
  await saveProperties([...(props.cardProperties ?? []), prop])
}

/** 添加属性下拉回调（模板表达式不支持 TS 断言，经脚本中转） */
function handleAddPropertyCommand(command: string) {
  const builtin = BUILTIN_PROPERTIES.find((p) => `builtin:${p.id}` === command)
  if (builtin) {
    void handleAddBuiltinProperty(builtin)
    return
  }
  handlePropertyAdd(command as CustomPropertyType)
}

/** 删除属性：确认后从模板移除，后端级联清理全部卡片上的对应值 */
async function handlePropertyDelete(prop: CardProperty) {
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
  await saveProperties((props.cardProperties ?? []).filter((p) => p.id !== prop.id), false)
  delete form.propertyValues[prop.id]
  appMessage.success(t('property.deleted', { name: prop.name }))
}

function close() {
  emit('update:modelValue', false)
}

/** 空值判定（保存时过滤，避免写入无意义属性值） */
function isEmptyValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  )
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const payload = {
      title: form.title,
      icon: form.icon,
      description: form.description || undefined,
      properties: Object.fromEntries(
        Object.entries(form.propertyValues).filter(([, value]) => !isEmptyValue(value)),
      ),
    }
    if (isEdit.value && props.task) {
      await taskApi.updateTask(props.task.id, payload)
      appMessage.success(t('task.updated'))
    } else {
      await taskApi.createTask(props.projectId, payload)
      appMessage.success(t('task.created'))
    }
    close()
    emit('saved')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="isEdit ? t('task.editDialog') : t('task.newDialog')"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" class="task-form">
      <el-form-item :label="t('task.iconLabel')">
        <IconPicker v-model="form.icon" addable />
        <span class="icon-hint">{{ t('projectDialog.iconHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('task.titleLabel')" prop="title">
        <el-input v-model="form.title" :placeholder="t('task.titlePlaceholder')" maxlength="200" />
      </el-form-item>
      <el-form-item :label="t('task.descriptionLabel')" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          :placeholder="t('task.descriptionPlaceholder')"
        />
      </el-form-item>
      <!-- 动态属性：可管理时属性名挂载管理菜单（本弹窗为属性管理唯一入口） -->
      <el-form-item v-for="prop in props.cardProperties ?? []" :key="prop.id">
        <template #label>
          <PropertyMenu
            v-if="props.canManageProperties"
            :property="prop"
            :initial-open="newPropertyId === prop.id"
            @change="handlePropertyChange"
            @delete="handlePropertyDelete(prop)"
          />
          <span v-else>{{ prop.name }}</span>
        </template>
        <PropertyValueInput
          :property="prop"
          :model-value="form.propertyValues[prop.id]"
          :member-map="props.memberMap"
          @update:model-value="form.propertyValues[prop.id] = $event"
        />
      </el-form-item>
      <!-- 添加属性（仅可管理属性定义时显示） -->
      <el-form-item v-if="props.canManageProperties" label=" ">
        <el-dropdown trigger="click" @command="handleAddPropertyCommand">
          <el-button link type="primary" size="small" :icon="Plus">
            {{ t('property.add') }}
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="type in CUSTOM_PROPERTY_TYPES"
                :key="type"
                :command="type"
              >
                {{ t(`propertyType.${type}`) }}
              </el-dropdown-item>
              <!-- 内置属性：选项已预配，一键添加（已存在时置灰） -->
              <el-dropdown-item
                v-for="prop in BUILTIN_PROPERTIES"
                :key="prop.id"
                divided
                :disabled="builtinAdded(prop)"
                :command="`builtin:${prop.id}`"
              >
                {{ t(`property.builtinName.${prop.id}`) }}
                {{ builtinAdded(prop) ? t('property.builtinAdded') : '' }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">{{ t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.icon-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

/* 字段名称加宽后，输入框限定宽度（右侧留白，避免过长） */
.task-form :deep(.el-form-item__content) {
  max-width: 340px;
}
</style>