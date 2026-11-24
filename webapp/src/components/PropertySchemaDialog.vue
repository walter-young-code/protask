<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Lock, Plus, Rank } from '@element-plus/icons-vue'
import { BUILTIN_PROPERTIES, CUSTOM_PROPERTY_TYPES, hasWbsProperty, isGanttRequiredProperty, orderRequiredFirst, START_DATE_PROPERTY_ID } from '@/utils/meta'
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

const { newPropertyId, changeProperty, builtinAdded, addPropertyCommand, reorderProperties, deleteProperty } =
  usePropertySchema({
    projectId: () => props.projectId,
    template: () => props.cardProperties,
    changed: () => emit('changed'),
  })

/** 展示列表：必要字段置顶锁定，其余按模板顺序 */
const displayProperties = computed(() => orderRequiredFirst(props.cardProperties))

/** 拖拽预览序：dragover 实时 splice，dragend 对比原序后持久化 */
const localOrder = ref<CardProperty[]>([])
watch(
  displayProperties,
  (list) => {
    localOrder.value = [...list]
  },
  { immediate: true },
)

const dragId = ref('')

function onDragStart(prop: CardProperty, event: DragEvent) {
  if (isLocked(prop)) {
    event.preventDefault()
    return
  }
  dragId.value = prop.id
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', prop.id)
  }
}

/** 悬停目标行时实时移动预览；锁定行不可作放置目标，保证必要字段恒置顶 */
function onDragOver(prop: CardProperty, event: DragEvent) {
  if (!dragId.value || isLocked(prop) || prop.id === dragId.value) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  const list = localOrder.value
  const from = list.findIndex((p) => p.id === dragId.value)
  const to = list.findIndex((p) => p.id === prop.id)
  if (from < 0 || to < 0 || from === to) return
  const next = [...list]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  localOrder.value = next
}

function onDragEnd() {
  const dragged = dragId.value
  dragId.value = ''
  if (!dragged) return
  const original = displayProperties.value.map((p) => p.id).join(',')
  const current = localOrder.value.map((p) => p.id).join(',')
  if (original !== current) {
    reorderProperties(localOrder.value.map((p) => p.id))
  }
}

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
    <p class="property-schema-hint">{{ t('property.settingsHint') }}{{ t('property.dragHint') }}</p>

    <!-- WBS 门禁说明：添加「WBS 编码」解锁层级/甘特/依赖/导出，并自动绑定开始/截止日期（必要字段锁定不可删） -->
    <el-alert
      :title="t('property.wbsHint')"
      :type="hasWbs ? 'success' : 'info'"
      :closable="false"
      show-icon
      class="property-schema-wbs-hint"
    />

    <div v-if="localOrder.length" class="property-schema-list">
      <!-- 与新建任务弹窗一致：点击属性名展开菜单；拖拽手柄调整显示顺序（必要字段置顶不可拖） -->
      <div
        v-for="prop in localOrder"
        :key="prop.id"
        class="property-schema-item"
        :class="{ 'is-dragging': dragId === prop.id }"
        @dragover="onDragOver(prop, $event)"
        @drop.prevent
      >
        <span
          v-if="!isLocked(prop)"
          class="property-schema-drag"
          draggable="true"
          :title="t('property.dragHint')"
          @dragstart="onDragStart(prop, $event)"
          @dragend="onDragEnd"
        >
          <el-icon><Rank /></el-icon>
        </span>
        <span v-else class="property-schema-drag is-locked">
          <el-icon><Lock /></el-icon>
        </span>
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

.property-schema-item.is-dragging {
  opacity: 0.5;
}

.property-schema-drag {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  cursor: grab;
  color: var(--app-text-secondary);
}

.property-schema-drag.is-locked {
  cursor: not-allowed;
  opacity: 0.45;
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
