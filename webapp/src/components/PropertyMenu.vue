<script setup lang="ts">
import { ref, watch } from 'vue'
import { appMessage } from '@/utils/appMessage'
import { useI18n } from 'vue-i18n'
import { ArrowDown, Close, Delete, Plus } from '@element-plus/icons-vue'
import { CUSTOM_PROPERTY_TYPES, createPropertyId, OPTION_COLORS } from '@/utils/meta'
import type { CardProperty, PropertyType } from '@/types'

const props = defineProps<{
  /** 被管理的属性定义 */
  property: CardProperty
  /** 初始展开（刚新增的属性自动打开菜单便于改名，参照 Focalboard newTemplateId） */
  initialOpen?: boolean
}>()

const emit = defineEmits<{
  /** 属性定义变更（改名/改类型/选项编辑），由父级持久化 */
  (e: 'change', updated: CardProperty): void
  /** 请求删除属性，由父级弹确认后处理 */
  (e: 'delete'): void
}>()

const { t } = useI18n()

const visible = ref(Boolean(props.initialOpen))
const name = ref(props.property.name)
/** 本地草稿选项：新增选项先进草稿，录入非空值后才随 change 持久化（后端校验选项值非空） */
const draftOptionId = ref('')
const draftOptionValue = ref('')
/** 选项值本地编辑态：受控 :model-value 不处理 update 会把键入内容重置回原值，故用 v-model 绑本地态 */
const optionDrafts = ref<Record<string, string>>({})
/** 草稿选项的颜色（随选项转正一并持久化） */
const draftOptionColor = ref<string | undefined>(undefined)

watch(
  () => props.initialOpen,
  (open) => {
    visible.value = open
  },
)

watch(
  () => props.property.name,
  (value) => {
    name.value = value
  },
)

// 属性定义刷新后：同步选项编辑态，草稿选项若已并入模板则清除草稿状态
watch(
  () => props.property.options,
  (options) => {
    const next: Record<string, string> = {}
    for (const option of options ?? []) {
      next[option.id] = option.value
    }
    optionDrafts.value = next
    if (draftOptionId.value && options?.some((o) => o.id === draftOptionId.value)) {
      draftOptionId.value = ''
      draftOptionValue.value = ''
      draftOptionColor.value = undefined
    }
  },
  { immediate: true },
)

/** 提交改名（空名或未变化时回退） */
function commitName() {
  const trimmed = name.value.trim()
  if (!trimmed || trimmed === props.property.name) {
    name.value = props.property.name
    return
  }
  emit('change', { ...props.property, name: trimmed })
}

const needsOptions = (type: PropertyType) => type === 'select' || type === 'multiSelect'

/** 切换类型：切到选项类型且无选项时预置一个默认选项 */
function changeType(type: PropertyType) {
  if (type === props.property.type) return
  const options =
    needsOptions(type) && !(props.property.options?.length ?? 0)
      ? [{ id: createPropertyId(), value: t('property.defaultOption') }]
      : props.property.options
  emit('change', { ...props.property, type, options })
}

/** 类型下拉回调（模板表达式不支持 TS 断言，经脚本中转） */
function onTypeChange(type: PropertyType) {
  changeType(type)
}

/** 更新选项值：空值回退原值不提交；草稿选项录入非空值时转正持久化 */
function updateOptionValue(optionId: string, value: string) {
  const trimmed = value.trim()
  if (optionId === draftOptionId.value) {
    if (!trimmed) {
      draftOptionValue.value = ''
      return
    }
    const options = [
      ...(props.property.options ?? []),
      { id: optionId, value: trimmed, color: draftOptionColor.value },
    ]
    draftOptionId.value = ''
    draftOptionValue.value = ''
    draftOptionColor.value = undefined
    emit('change', { ...props.property, options })
    return
  }
  const original = props.property.options?.find((o) => o.id === optionId)?.value ?? ''
  if (!trimmed || trimmed === original) {
    optionDrafts.value[optionId] = original
    if (!trimmed) {
      appMessage.warning(t('property.optionValueRequired'))
    }
    return
  }
  const options = (props.property.options ?? []).map((o) =>
    o.id === optionId ? { ...o, value: trimmed } : o,
  )
  emit('change', { ...props.property, options })
}

/** 更新选项颜色：color 传 undefined 表示移除颜色 */
function updateOptionColor(optionId: string, color: string | undefined) {
  const options = (props.property.options ?? []).map((o) =>
    o.id === optionId ? { ...o, color } : o,
  )
  emit('change', { ...props.property, options })
}

function removeOption(optionId: string) {
  const options = props.property.options ?? []
  // 最后一个选项不可删除：选项类型必须至少保留一个选项
  if (options.length <= 1) {
    appMessage.warning(t('property.lastOptionHint'))
    return
  }
  emit('change', {
    ...props.property,
    options: options.filter((o) => o.id !== optionId),
  })
}

/** 新增选项：先进入本地草稿，待用户录入值后再持久化（后端要求选项值非空） */
function addOption() {
  if (draftOptionId.value) return
  draftOptionId.value = createPropertyId()
  draftOptionValue.value = ''
  draftOptionColor.value = undefined
}

/** 关闭菜单并请求父级删除属性 */
function requestDelete() {
  visible.value = false
  emit('delete')
}
</script>

<template>
  <el-popover
    v-model:visible="visible"
    trigger="click"
    placement="bottom-start"
    :width="280"
  >
    <template #reference>
      <span class="property-menu-trigger" @click.stop>
        {{ property.name }}
        <el-icon class="property-menu-caret"><ArrowDown /></el-icon>
      </span>
    </template>

    <div class="property-menu">
      <el-input
        v-model="name"
        size="small"
        maxlength="50"
        :placeholder="t('property.namePlaceholder')"
        @change="commitName"
        @keydown.enter="commitName"
      />

      <div class="property-menu-row">
        <span class="property-menu-label">{{ t('property.typeLabel') }}</span>
        <el-select
          :model-value="property.type"
          size="small"
          class="property-menu-type"
          @update:model-value="onTypeChange"
        >
          <el-option
            v-for="type in CUSTOM_PROPERTY_TYPES"
            :key="type"
            :label="t(`propertyType.${type}`)"
            :value="type"
          />
        </el-select>
      </div>

      <!-- select/multiSelect 的选项编辑器 -->
      <div
        v-if="property.type === 'select' || property.type === 'multiSelect'"
        class="property-menu-options"
      >
        <div
          v-for="option in property.options ?? []"
          :key="option.id"
          class="property-option-row"
        >
          <el-popover trigger="click" :width="200" placement="left">
            <template #reference>
              <button
                type="button"
                class="option-color-dot"
                :class="{ empty: !option.color }"
                :style="option.color ? { background: option.color } : undefined"
                :title="t('property.pickColor')"
              />
            </template>
            <div class="option-color-palette">
              <span
                v-for="color in OPTION_COLORS"
                :key="color"
                class="option-color-cell"
                :class="{ selected: option.color === color }"
                :style="{ background: color }"
                @click="updateOptionColor(option.id, color)"
              />
              <span
                class="option-color-cell option-color-clear"
                :class="{ selected: !option.color }"
                :title="t('property.removeColor')"
                @click="updateOptionColor(option.id, undefined)"
              >
                <el-icon><Close /></el-icon>
              </span>
            </div>
          </el-popover>
          <el-input
            v-model="optionDrafts[option.id]"
            size="small"
            maxlength="50"
            :placeholder="t('property.optionPlaceholder')"
            @change="updateOptionValue(option.id, $event)"
          />
          <el-button
            link
            size="small"
            type="danger"
            :icon="Delete"
            @click="removeOption(option.id)"
          />
        </div>
        <!-- 草稿选项：录入非空值后自动转正持久化 -->
        <div v-if="draftOptionId" class="property-option-row">
          <el-popover trigger="click" :width="200" placement="left">
            <template #reference>
              <button
                type="button"
                class="option-color-dot"
                :class="{ empty: !draftOptionColor }"
                :style="draftOptionColor ? { background: draftOptionColor } : undefined"
                :title="t('property.pickColor')"
              />
            </template>
            <div class="option-color-palette">
              <span
                v-for="color in OPTION_COLORS"
                :key="color"
                class="option-color-cell"
                :class="{ selected: draftOptionColor === color }"
                :style="{ background: color }"
                @click="draftOptionColor = color"
              />
              <span
                class="option-color-cell option-color-clear"
                :class="{ selected: !draftOptionColor }"
                :title="t('property.removeColor')"
                @click="draftOptionColor = undefined"
              >
                <el-icon><Close /></el-icon>
              </span>
            </div>
          </el-popover>
          <el-input
            v-model="draftOptionValue"
            size="small"
            maxlength="50"
            :placeholder="t('property.optionPlaceholder')"
            @change="updateOptionValue(draftOptionId, $event)"
          />
          <el-button
            link
            size="small"
            type="danger"
            :icon="Delete"
            @click="draftOptionId = ''; draftOptionColor = undefined"
          />
        </div>
        <el-button link size="small" :icon="Plus" @click="addOption">
          {{ t('property.addOption') }}
        </el-button>
      </div>

      <div class="property-menu-footer">
        <el-button link size="small" type="danger" @click="requestDelete">
          {{ t('property.delete') }}
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.property-menu-trigger {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  color: var(--app-text-primary);
}

.property-menu-trigger:hover {
  color: var(--el-color-primary);
}

.property-menu-caret {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.property-menu {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.property-menu-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.property-menu-label {
  flex-shrink: 0;
  font-size: 13px;
  color: var(--app-text-regular);
}

.property-menu-type {
  flex: 1;
}

.property-menu-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.property-option-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.option-color-dot {
  width: 18px;
  height: 18px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid rgba(0, 0, 0, 0.12);
  flex-shrink: 0;
  cursor: pointer;
  transition: transform 0.15s;
}

.option-color-dot:hover {
  transform: scale(1.15);
}

.option-color-dot.empty {
  background: var(--app-surface);
  border-style: dashed;
  border-color: var(--app-text-placeholder);
}

.option-color-palette {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 6px;
}

.option-color-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  cursor: pointer;
  transition: transform 0.15s;
}

.option-color-cell:hover {
  transform: scale(1.1);
}

.option-color-cell.selected {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.option-color-clear {
  background: var(--app-surface);
  color: var(--app-text-secondary);
  font-size: 12px;
}

.property-menu-footer {
  border-top: 1px solid var(--app-border-light);
  padding-top: 6px;
}
</style>
