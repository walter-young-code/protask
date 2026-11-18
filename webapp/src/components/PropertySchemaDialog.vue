<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus } from '@element-plus/icons-vue'
import { BUILTIN_PROPERTIES, CUSTOM_PROPERTY_TYPES, hasWbsProperty, isGanttRequiredProperty, START_DATE_PROPERTY_ID } from '@/utils/meta'
import { usePropertySchema } from '@/composables/usePropertySchema'
import PropertyMenu from '@/components/PropertyMenu.vue'
import type { CardProperty } from '@/types'

const props = defineProps<{
  modelValue: boolean
  projectId: number
  /** 项目卡片动态属性模板 */
  cardProperties: CardProperty[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 属性定义变更（父级重新加载项目以刷新模板） */
  (e: 'changed'): void
}>()

const { t } = useI18n()

/** WBS 门禁：项目是否添加了 WBS 编码属性 */
const hasWbs = computed(() => hasWbsProperty(props.cardProperties))

/** 甘特必要字段：启用后锁定不可删除 */
const isLocked = (prop: CardProperty): boolean => hasWbs.value && isGanttRequiredProperty(prop.id)

/** 可添加的内置属性：开始日期仅 WBS 项目提供 */
const availableBuiltins = computed(() =>
  BUILTIN_PROPERTIES.filter((p) => p.id !== START_DATE_PROPERTY_ID || hasWbs.value),
)

const { newPropertyId, changeProperty, builtinAdded, addPropertyCommand, deleteProperty } =
  usePropertySchema({
    projectId: () => props.projectId,
    template: () => props.cardProperties,
    changed: () => emit('changed'),
  })

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('property.settingsTitle')"
    width="600px"
    :close-on-click-modal="false"
    class="property-schema-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="property-schema-hint">{{ t('property.settingsHint') }}</p>

    <!-- WBS 门禁说明：添加「WBS 编码」解锁层级/甘特/依赖/导出，并自动绑定开始/截止日期（必要字段锁定不可删） -->
    <el-alert
      :title="t('property.wbsHint')"
      :type="hasWbs ? 'success' : 'info'"
      :closable="false"
      show-icon
      class="property-schema-wbs-hint"
    />

    <div v-if="props.cardProperties.length" class="property-schema-list">
      <!-- 与新建任务弹窗一致：点击属性名展开菜单，在菜单内自定义名称/类型/选项或删除 -->
      <div
        v-for="prop in props.cardProperties"
        :key="prop.id"
        class="property-schema-item"
      >
        <PropertyMenu
          :property="prop"
          :initial-open="newPropertyId === prop.id"
          :delete-disabled="isLocked(prop)"
          @change="changeProperty"
          @delete="deleteProperty(prop)"
        />
        <span class="property-schema-meta">
          <el-tag v-if="isLocked(prop)" size="small" type="warning" effect="plain" round>
            {{ t('property.requiredTag') }}
          </el-tag>
          <span class="property-schema-type">{{ t(`propertyType.${prop.type}`) }}</span>
        </span>
      </div>
    </div>
    <el-empty v-else :description="t('property.empty')" :image-size="60" />

    <!-- 新增属性：自定义类型 + 内置属性一键添加 -->
    <div class="property-schema-add">
      <el-dropdown trigger="click" @command="addPropertyCommand">
        <el-button plain type="primary" :icon="Plus">
          {{ t('property.add') }}
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="type in CUSTOM_PROPERTY_TYPES" :key="type" :command="type">
              {{ t(`propertyType.${type}`) }}
            </el-dropdown-item>
            <!-- 内置属性：选项已预配，一键添加（已存在时置灰；开始日期仅 WBS 项目提供） -->
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
    </div>

    <template #footer>
      <el-button @click="close">{{ t('common.close') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.property-schema-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.property-schema-wbs-hint {
  margin-bottom: 12px;
}

.property-schema-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 56vh;
  overflow-y: auto;
  padding-right: 4px;
}

.property-schema-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  transition: background 0.15s;
}

.property-schema-item:hover {
  background: var(--el-color-primary-light-9);
}

.property-schema-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.property-schema-type {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.property-schema-add {
  margin-top: 14px;
}
</style>
