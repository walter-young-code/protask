<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import * as taskApi from '@/api/task'
import { CUSTOM_PROPERTY_TYPES, BUILTIN_PROPERTIES, WBS_PROPERTY_ID, START_DATE_PROPERTY_ID, isGanttRequiredProperty } from '@/utils/meta'
import { buildParentOptions } from '@/utils/taskTree'
import { usePropertySchema } from '@/composables/usePropertySchema'
import PropertyMenu from '@/components/PropertyMenu.vue'
import PropertyValueInput from '@/components/PropertyValueInput.vue'
import IconPicker from '@/components/IconPicker.vue'
import type { CardProperty, TaskVO } from '@/types'

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
  /** 项目全量任务（父任务选择候选来源，仅 WBS 项目用） */
  tasks?: TaskVO[]
  /** WBS 门禁：项目是否添加了 WBS 编码属性 */
  hasWbs?: boolean
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

const form = reactive({
  title: '',
  description: '',
  /** 任务图标（emoji，空串表示无图标） */
  icon: '',
  /** 父任务 ID（null=根任务，仅 WBS 项目生效） */
  parentTaskId: null as number | null,
  /** 动态属性值（按项目 cardProperties 模板渲染） */
  propertyValues: {} as Record<string, unknown>,
})

/** 父任务候选（排除自身与子孙防环，带深度缩进） */
const parentOptions = computed(() => buildParentOptions(props.tasks ?? [], props.task?.id))

/** 父任务下拉值：0 代表「无父任务」（ElOption 不接受 null），与表单 null 双向映射 */
const parentSelectValue = computed<number>({
  get: () => form.parentTaskId ?? 0,
  set: (v) => {
    form.parentTaskId = v ? v : null
  },
})

/** 可编辑的动态属性（排除只读 WBS 编码） */
const editableProperties = computed(() =>
  (props.cardProperties ?? []).filter((p) => p.id !== WBS_PROPERTY_ID),
)

/** 可添加的内置属性：开始日期仅 WBS 项目提供 */
const availableBuiltins = computed(() =>
  BUILTIN_PROPERTIES.filter((p) => p.id !== START_DATE_PROPERTY_ID || props.hasWbs),
)

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
      form.parentTaskId = props.task.parentTaskId ?? null
    } else {
      form.title = ''
      form.description = ''
      form.icon = ''
      form.parentTaskId = null
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

// ---------- 属性模板管理（与「设置属性」对话框共用同一套定义管理逻辑） ----------

/** 刚新增的属性 ID：自动展开其管理菜单便于改名（参照 Focalboard newTemplateId） */
const { newPropertyId, changeProperty, builtinAdded, addPropertyCommand, deleteProperty } =
  usePropertySchema({
    projectId: () => props.projectId,
    template: () => props.cardProperties ?? [],
    changed: () => emit('properties-changed'),
    // 删除属性后同步清理表单中该属性的值
    onDeleted: (prop) => {
      delete form.propertyValues[prop.id]
    },
  })

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
        Object.entries(form.propertyValues).filter(
          ([id, value]) => id !== WBS_PROPERTY_ID && !isEmptyValue(value),
        ),
      ),
      // 父任务语义：创建 null=根；更新 null→0(设根)、其余=指定父（仅 WBS 项目下发）
      ...(props.hasWbs
        ? { parentTaskId: isEdit.value ? (form.parentTaskId ?? 0) : form.parentTaskId }
        : {}),
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
      <!-- WBS 项目：父任务选择（排除自身与子孙防环） -->
      <el-form-item v-if="props.hasWbs" :label="t('task.parentLabel')">
        <el-select
          v-model="parentSelectValue"
          clearable
          filterable
          :placeholder="t('task.parentNone')"
          class="parent-select"
        >
          <el-option :value="0" :label="t('task.parentNone')" />
          <el-option
            v-for="opt in parentOptions"
            :key="opt.id"
            :value="opt.id"
            :label="(opt.wbs ? opt.wbs + ' ' : '') + opt.title"
          >
            <span :style="{ paddingLeft: `${opt.depth * 14}px` }">
              <span class="parent-wbs">{{ opt.wbs }}</span> {{ opt.title }}
            </span>
          </el-option>
        </el-select>
        <span class="parent-hint">{{ t('task.parentHint') }}</span>
      </el-form-item>
      <!-- WBS 编码只读展示（编辑态，后端树序自动编号） -->
      <el-form-item v-if="props.hasWbs && isEdit && props.task?.wbs" :label="t('gantt.wbsColumn')">
        <span class="wbs-readonly">{{ props.task.wbs }}</span>
        <span class="parent-hint">{{ t('task.wbsReadonly') }}</span>
      </el-form-item>
      <!-- 动态属性：可管理时属性名挂载管理菜单（本弹窗为属性管理唯一入口） -->
      <el-form-item v-for="prop in editableProperties" :key="prop.id">
        <template #label>
          <PropertyMenu
            v-if="props.canManageProperties"
            :property="prop"
            :initial-open="newPropertyId === prop.id"
            :delete-disabled="Boolean(props.hasWbs) && isGanttRequiredProperty(prop.id)"
            @change="changeProperty"
            @delete="deleteProperty(prop)"
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
        <el-dropdown trigger="click" @command="addPropertyCommand">
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
                v-for="prop in availableBuiltins"
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

.parent-select {
  width: 100%;
}

.parent-wbs {
  color: var(--app-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
}

.parent-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-placeholder);
  line-height: 1.4;
}

.wbs-readonly {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--app-text-regular);
}

/* 字段名称加宽后，输入框限定宽度（右侧留白，避免过长） */
.task-form :deep(.el-form-item__content) {
  max-width: 340px;
}
</style>