<script setup lang="ts">
import { ref, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import PropertyDefinitionForm from '@/components/PropertyDefinitionForm.vue'
import type { CardProperty } from '@/types'

const props = defineProps<{
  /** 被管理的属性定义 */
  property: CardProperty
  /** 初始展开（刚新增的属性自动打开菜单便于改名，参照 Focalboard newTemplateId） */
  initialOpen?: boolean
  /** 甘特必要字段：删除锁定 */
  deleteDisabled?: boolean
}>()

const emit = defineEmits<{
  /** 属性定义变更（改名/改类型/选项编辑），由父级持久化 */
  (e: 'change', updated: CardProperty): void
  /** 请求删除属性，由父级弹确认后处理 */
  (e: 'delete'): void
}>()

const visible = ref(Boolean(props.initialOpen))

watch(
  () => props.initialOpen,
  (open) => {
    visible.value = open
  },
)

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

    <PropertyDefinitionForm
      :property="property"
      :delete-disabled="deleteDisabled"
      @change="emit('change', $event)"
      @delete="requestDelete"
    />
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
</style>
