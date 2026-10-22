<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Plus } from '@element-plus/icons-vue'
import { propertyDisplayText } from '@/utils/meta'
import type { CardProperty, TaskVO } from '@/types'

const props = defineProps<{
  tasks: TaskVO[]
  loading: boolean
  /** 当前用户是否可编辑任务（editor+） */
  editable: boolean
  /** 项目卡片动态属性模板（全部作为动态列） */
  cardProperties: CardProperty[]
  /** 项目成员映射（userId -> 昵称），member 类型属性显示用 */
  memberMap?: Record<number, string>
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
  (e: 'delete', task: TaskVO): void
  /** 复制任务（同项目内） */
  (e: 'copy', task: TaskVO): void
  /** 新建任务：打开创建弹窗（表格视图无分组，不预置属性） */
  (e: 'quickAdd'): void
}>()

const { t } = useI18n()
</script>

<template>
  <el-card>
    <el-table v-loading="loading" :data="tasks" stripe @row-click="(row: TaskVO) => emit('open', row)">
      <el-table-column prop="title" :label="t('task.columns.title')" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="task-title">
            <span v-if="(row as TaskVO).icon" class="task-title-icon">{{ (row as TaskVO).icon }}</span>
            {{ row.title }}
          </span>
        </template>
      </el-table-column>
      <!-- 项目动态属性列（按 cardProperties 模板全量渲染） -->
      <el-table-column
        v-for="prop in props.cardProperties"
        :key="prop.id"
        :label="prop.name"
        min-width="110"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ propertyDisplayText(row as TaskVO, prop, props.memberMap) }}</template>
      </el-table-column>
      <!-- 附件数：行尾无表头的数字标签（样式同仪表盘统计标签），无附件时不占位 -->
      <el-table-column width="56" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="(row as TaskVO).attachmentCount"
            size="small"
            type="info"
            effect="plain"
            :title="t('kanban.attachmentCount', { count: (row as TaskVO).attachmentCount })"
          >
            {{ (row as TaskVO).attachmentCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="editable" :label="t('task.columns.actions')" width="140" fixed="right">
        <template #default="{ row }">
          <span class="row-actions">
            <el-button link type="primary" size="small" @click.stop="emit('copy', row as TaskVO)">
              {{ t('task.copy') }}
            </el-button>
            <el-button link type="danger" size="small" @click.stop="emit('delete', row as TaskVO)">
              {{ t('common.delete') }}
            </el-button>
          </span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="t('task.empty')" />
      </template>
    </el-table>
    <!-- 底部工具栏：新建任务入口（与看板列内快捷新建一致，仅编辑者可操作） -->
    <div v-if="editable" class="table-toolbar">
      <button class="table-add" @click="emit('quickAdd')">
        <el-icon><Plus /></el-icon>
        {{ t('projectDetail.newTask') }}
      </button>
    </div>
  </el-card>
</template>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}

.table-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 7px 14px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.table-add:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.task-title {
  cursor: pointer;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}

.task-title-icon {
  font-size: 14px;
  line-height: 1;
  flex-shrink: 0;
}

.task-title:hover {
  color: var(--el-color-primary);
}

/* 操作列按钮默认隐藏，仅在鼠标悬停任务行（或键盘聚焦）时显现 */
:deep(.row-actions) {
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s;
}

:deep(.el-table__row:hover) .row-actions,
:deep(.el-table__row:focus-within) .row-actions,
:deep(.el-table__row.hover-row) .row-actions {
  opacity: 1;
  pointer-events: auto;
}

/* 触屏设备无悬停语义，操作按钮始终显示 */
@media (hover: none) {
  :deep(.row-actions) {
    opacity: 1;
    pointer-events: auto;
  }
}
</style>
