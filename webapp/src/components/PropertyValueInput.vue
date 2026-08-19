<script setup lang="ts">
import { computed } from 'vue'
import type { CardProperty } from '@/types'

const props = defineProps<{
  /** 属性定义（决定控件类型与选项） */
  property: CardProperty
  /** 属性值（存于任务 properties[prop.id]） */
  modelValue: unknown
  /** 项目成员映射（userId -> 昵称），member 类型属性的选项来源 */
  memberMap?: Record<number, string>
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown): void
}>()

/** 多选值更新：空数组视为清空 */
function updateMultiSelect(value: string[]) {
  emit('update:modelValue', value.length ? value : undefined)
}

/** 单选展示值：仅当命中现有选项时才显示，孤儿 id（选项已删/改）显示为空 */
const selectDisplayValue = computed(() => {
  const value = props.modelValue
  if (value == null || value === '') return ''
  const hit = props.property.options?.some((o) => o.id === value || o.value === value)
  return hit ? String(value) : ''
})

/** 多选展示值：过滤掉不在选项中的孤儿 id */
const multiSelectDisplayValue = computed(() => {
  if (!Array.isArray(props.modelValue)) return []
  const optionIds = new Set((props.property.options ?? []).map((o) => o.id))
  return (props.modelValue as unknown[]).filter((id) => optionIds.has(String(id))) as string[]
})
</script>

<template>
  <!-- 单选：值为选项 id（与看板分组/拖拽一致），孤儿 id 显示为空 -->
  <el-select
    v-if="property.type === 'select'"
    :model-value="selectDisplayValue"
    clearable
    :disabled="disabled"
    class="full-width"
    @update:model-value="emit('update:modelValue', $event || undefined)"
  >
    <el-option
      v-for="option in property.options ?? []"
      :key="option.id"
      :label="option.value"
      :value="option.id"
    >
      <span v-if="option.color" class="pv-option-dot" :style="{ background: option.color }" />
      {{ option.value }}
    </el-option>
  </el-select>

  <!-- 多选：值为选项 id 数组，孤儿 id 不展示 -->
  <el-select
    v-else-if="property.type === 'multiSelect'"
    :model-value="multiSelectDisplayValue"
    multiple
    clearable
    :disabled="disabled"
    class="full-width"
    @update:model-value="updateMultiSelect"
  >
    <el-option
      v-for="option in property.options ?? []"
      :key="option.id"
      :label="option.value"
      :value="option.id"
    >
      <span v-if="option.color" class="pv-option-dot" :style="{ background: option.color }" />
      {{ option.value }}
    </el-option>
  </el-select>

  <!-- 日期：仅日期不带时间，存量带时间的值截断后展示 -->
  <el-date-picker
    v-else-if="property.type === 'date'"
    :model-value="modelValue ? String(modelValue).slice(0, 10) : null"
    type="date"
    value-format="YYYY-MM-DD"
    :disabled="disabled"
    class="full-width"
    @update:model-value="emit('update:modelValue', $event || undefined)"
  />

  <!-- 数字 -->
  <el-input-number
    v-else-if="property.type === 'number'"
    :model-value="typeof modelValue === 'number' ? modelValue : undefined"
    :controls="false"
    :disabled="disabled"
    class="full-width number-input"
    @update:model-value="emit('update:modelValue', $event ?? undefined)"
  />

  <!-- 人员：值为项目成员 userId，选项来自项目成员映射 -->
  <el-select
    v-else-if="property.type === 'member'"
    :model-value="typeof modelValue === 'number' ? modelValue : undefined"
    clearable
    :disabled="disabled"
    class="full-width"
    @update:model-value="emit('update:modelValue', $event ?? undefined)"
  >
    <el-option
      v-for="(nickname, userId) in memberMap ?? {}"
      :key="userId"
      :label="nickname"
      :value="Number(userId)"
    />
  </el-select>

  <!-- 复选框 -->
  <el-switch
    v-else-if="property.type === 'checkbox'"
    :model-value="Boolean(modelValue)"
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event)"
  />

  <!-- 文本（含未知类型兜底） -->
  <el-input
    v-else
    :model-value="modelValue != null ? String(modelValue) : ''"
    clearable
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event || undefined)"
  />
</template>

<style scoped>
.full-width {
  width: 100%;
}

.pv-option-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  border: 1px solid rgba(0, 0, 0, 0.08);
  vertical-align: middle;
}

/* 数字属性与文本属性保持一致的左对齐（el-input-number 默认居中） */
.number-input :deep(.el-input__inner) {
  text-align: left;
}
</style>
