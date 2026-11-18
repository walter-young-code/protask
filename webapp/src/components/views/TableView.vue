<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Bottom, Plus, Setting, Top } from '@element-plus/icons-vue'
import { propertyDisplayText, WBS_PROPERTY_ID } from '@/utils/meta'
import { buildTaskTree } from '@/utils/taskTree'
import type { CardProperty, TaskMoveDirection, TaskVO } from '@/types'

const props = defineProps<{
  tasks: TaskVO[]
  loading: boolean
  /** 当前用户是否可编辑任务（editor+） */
  editable: boolean
  /** 项目卡片动态属性模板（全部作为动态列） */
  cardProperties: CardProperty[]
  /** 项目成员映射（userId -> 昵称），member 类型属性显示用 */
  memberMap?: Record<number, string>
  /** 当前用户是否可管理属性定义（editor+） */
  canManageProperties?: boolean
  /** WBS 门禁：项目是否添加了 WBS 编码属性（启用树型展示与重排） */
  hasWbs?: boolean
  /** 是否显示 WBS 编码列（缺省显示，持久化于视图配置） */
  showWbs?: boolean
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
  (e: 'delete', task: TaskVO): void
  /** 复制任务（同项目内） */
  (e: 'copy', task: TaskVO): void
  /** 新建任务：打开创建弹窗（表格视图无分组，不预置属性） */
  (e: 'quickAdd'): void
  /** 打开「设置属性」对话框（属性定义管理） */
  (e: 'propertySettings'): void
  /** 同级重排（上移/下移，WBS 随之重编号） */
  (e: 'move', task: TaskVO, direction: TaskMoveDirection): void
  /** WBS 编码列显示开关 */
  (e: 'showWbsChange', show: boolean): void
}>()

const { t } = useI18n()

/** WBS 项目按树序嵌套展示；非 WBS 项目保持扁平 */
const tableData = computed(() => (props.hasWbs ? buildTaskTree(props.tasks) : props.tasks))

/** WBS 编码列单独渲染（值取自 task.wbs），动态列排除 wbs 避免重复 */
const showWbsColumn = computed(() => Boolean(props.hasWbs) && props.showWbs !== false)
const displayProperties = computed(() =>
  props.cardProperties.filter((p) => p.id !== WBS_PROPERTY_ID),
)

/** 同级兄弟序列（按输入树序），用于上移/下移边界禁用 */
function siblingsOf(task: TaskVO): TaskVO[] {
  const parentId = task.parentTaskId ?? null
  return props.tasks.filter((item) => (item.parentTaskId ?? null) === parentId)
}
function canMove(task: TaskVO, direction: TaskMoveDirection): boolean {
  const siblings = siblingsOf(task)
  const index = siblings.findIndex((item) => item.id === task.id)
  if (index < 0) return false
  return direction === 'up' ? index > 0 : index < siblings.length - 1
}
</script>

<template>
  <el-card>
    <el-table
      v-loading="loading"
      :data="tableData"
      stripe
      row-key="id"
      :tree-props="{ children: 'children' }"
      :default-expand-all="hasWbs"
      @row-click="(row: TaskVO) => emit('open', row)"
    >
      <el-table-column prop="title" :label="t('task.columns.title')" min-width="220">
        <template #default="{ row }">
          <span class="task-title">
            <span v-if="(row as TaskVO).icon" class="task-title-icon">{{ (row as TaskVO).icon }}</span>
            <span class="task-title-text" :title="row.title">{{ row.title }}</span>
          </span>
        </template>
      </el-table-column>
      <!-- WBS 编码列（只读，后端树序自动编号） -->
      <el-table-column
        v-if="showWbsColumn"
        :label="t('gantt.wbsColumn')"
        width="90"
        align="left"
      >
        <template #default="{ row }">
          <span class="wbs-cell">{{ (row as TaskVO).wbs || '—' }}</span>
        </template>
      </el-table-column>
      <!-- 项目动态属性列（按 cardProperties 模板渲染，排除 wbs） -->
      <el-table-column
        v-for="prop in displayProperties"
        :key="prop.id"
        :label="prop.name"
        min-width="110"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ propertyDisplayText(row as TaskVO, prop, props.memberMap) }}</template>
      </el-table-column>
      <!-- 附件数：行尾无表头的数字标签，无附件时不占位 -->
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
      <el-table-column v-if="editable" :label="t('task.columns.actions')" :width="hasWbs ? 210 : 140" fixed="right">
        <template #default="{ row }">
          <span class="row-actions">
            <!-- WBS 项目同级重排：带横杠箭头 + 分段按钮组，与树展开箭头区分 -->
            <span v-if="hasWbs" class="move-group">
              <button
                class="move-btn"
                :disabled="!canMove(row as TaskVO, 'up')"
                :title="t('task.moveUp')"
                @click.stop="emit('move', row as TaskVO, 'up')"
              >
                <el-icon><Top /></el-icon>
              </button>
              <button
                class="move-btn"
                :disabled="!canMove(row as TaskVO, 'down')"
                :title="t('task.moveDown')"
                @click.stop="emit('move', row as TaskVO, 'down')"
              >
                <el-icon><Bottom /></el-icon>
              </button>
            </span>
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
    <!-- 底部工具栏：新建任务 / 设置属性 / WBS 列开关（仅编辑者可操作） -->
    <div v-if="editable" class="table-toolbar">
      <button class="table-add" @click="emit('quickAdd')">
        <el-icon><Plus /></el-icon>
        {{ t('projectDetail.newTask') }}
      </button>
      <button
        v-if="canManageProperties"
        class="table-add"
        @click="emit('propertySettings')"
      >
        <el-icon><Setting /></el-icon>
        {{ t('projectDetail.properties') }}
      </button>
      <label v-if="hasWbs" class="table-wbs-toggle">
        <el-switch
          :model-value="showWbs !== false"
          size="small"
          @update:model-value="emit('showWbsChange', $event as boolean)"
        />
        {{ t('gantt.wbsColumn') }}
      </label>
    </div>
  </el-card>
</template>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
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

.table-wbs-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  font-size: 13px;
  color: var(--app-text-secondary);
  cursor: pointer;
}

/* 同级重排分段按钮组：带横杠的 Top/Bottom 图标 + 描边容器，区别于树展开箭头 */
.move-group {
  display: inline-flex;
  align-items: stretch;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
  margin-right: 4px;
}

.move-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 22px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--app-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.move-btn + .move-btn {
  border-left: 1px solid var(--app-border);
}

.move-btn:hover:not(:disabled) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.move-btn:disabled {
  color: var(--app-text-placeholder);
  cursor: not-allowed;
}

.wbs-cell {
  font-variant-numeric: tabular-nums;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.task-title {
  cursor: pointer;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.task-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-title-icon {
  font-size: 14px;
  line-height: 1;
  flex-shrink: 0;
}

.task-title:hover {
  color: var(--el-color-primary);
}

/* 标题单元格单行布局：缩进 + 标题 + 右置展开箭头（参考侧边栏项目标签靠右隐藏交互） */
:deep(.el-table__body td:first-child .cell) {
  display: flex;
  align-items: center;
}

:deep(.el-table__body td:first-child .el-table__indent) {
  flex-shrink: 0;
}

/* 叶子节点占位不再占左侧空间，标题与父节点对齐 */
:deep(.el-table__body td:first-child .el-table__placeholder) {
  display: none;
}

/* 展开箭头靠右：默认隐藏，行悬停/聚焦时显现；折叠态常显以提示存在隐藏子孙 */
:deep(.el-table__expand-icon) {
  order: 3;
  margin-left: 8px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 4px;
  color: var(--app-text-secondary);
  opacity: 0;
  transition: background 0.2s, color 0.2s, opacity 0.15s;
}

:deep(.el-table__row:hover) .el-table__expand-icon,
:deep(.el-table__row:focus-within) .el-table__expand-icon,
:deep(.el-table__row.hover-row) .el-table__expand-icon,
:deep(.el-table__expand-icon:not(.el-table__expand-icon--expanded)) {
  opacity: 1;
}

:deep(.el-table__expand-icon:hover) {
  background: var(--app-fill);
  color: var(--app-text-primary);
}

/* 触屏设备无悬停语义，展开箭头始终显示 */
@media (hover: none) {
  :deep(.el-table__expand-icon) {
    opacity: 1;
  }
}

/* 操作列按钮默认隐藏，仅在鼠标悬停任务行（或键盘聚焦）时显现 */
:deep(.row-actions) {
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 2px;
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