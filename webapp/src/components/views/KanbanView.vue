<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChatDotRound, Paperclip, Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { propertyDisplayText } from '@/utils/meta'
import type { CardProperty, TaskVO } from '@/types'

const props = defineProps<{
  tasks: TaskVO[]
  loading: boolean
  /** 当前用户是否可编辑任务（editor+） */
  editable: boolean
  /** 项目卡片动态属性模板 */
  cardProperties: CardProperty[]
  /** 当前分组属性 id（select 属性，持久化于视图配置） */
  groupById?: string
  /** 卡片显示的属性 id 列表（undefined=默认前 3 个有值属性） */
  cardPropertyIds?: string[]
  /** 各任务的评论数（任务 id -> 评论数） */
  commentCounts?: Record<number, number>
  /** 项目成员映射（userId -> 昵称），member 类型属性显示用 */
  memberMap?: Record<number, string>
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
  /** 拖拽换列：value 为分组属性新值（单选传选项 id 字符串、多选传选项 id 数组、复选传布尔、人员传成员 userId 数字、undefined 表示清除） */
  (e: 'moved', task: TaskVO, propertyId: string, value: unknown): void
  /** 列内快捷新建：预置分组属性值 */
  (e: 'quickAdd', initialProperties: Record<string, unknown>): void
  (e: 'groupChange', propertyId: string): void
  /** 卡片显示属性变更：全选传 undefined 表示默认 */
  (e: 'cardPropertiesChange', propertyIds: string[] | undefined): void
}>()

const { t } = useI18n()

/** 复选属性勾选列的虚拟选项 id（复选无选项定义，用常量区分勾选/未勾选两列） */
const CHECKED_OPTION_ID = '__checked__'

/** 可作为分组依据的属性（单选/多选/复选/人员） */
const selectProperties = computed(() =>
  (props.cardProperties ?? []).filter(
    (p) =>
      p.type === 'select' ||
      p.type === 'multiSelect' ||
      p.type === 'checkbox' ||
      p.type === 'member',
  ),
)

/** 生效的分组属性（未选或失效时回退第一个可分组属性） */
const groupProperty = computed(
  () =>
    selectProperties.value.find((p) => p.id === props.groupById) ?? selectProperties.value[0],
)

/** 卡片可选显示的属性（排除分组属性） */
const displayableProperties = computed(() =>
  (props.cardProperties ?? []).filter((p) => p.id !== groupProperty.value?.id),
)

/** 显示属性选择器当前值（undefined 视为全选） */
const selectedCardProperties = computed(() => {
  if (props.cardPropertyIds) {
    return props.cardPropertyIds.filter((id) =>
      displayableProperties.value.some((p) => p.id === id),
    )
  }
  return displayableProperties.value.map((p) => p.id)
})

/** 显示属性变化：选中全部时回退 undefined（默认），否则持久化选中列表 */
function handleCardPropertiesChange(ids: string[]) {
  const allIds = displayableProperties.value.map((p) => p.id)
  const selected = ids.filter((id) => allIds.includes(id))
  emit('cardPropertiesChange', selected.length === allIds.length ? undefined : selected)
}

/** 卡片在分组属性上的值是否命中某选项：单选按选项 id/值，多选按选项 id 数组包含 */
function taskInOption(
  task: TaskVO,
  propertyId: string,
  option: { id: string; value: string },
  isMulti: boolean,
) {
  const value = task.properties?.[propertyId]
  if (isMulti) {
    return Array.isArray(value) && (value as unknown[]).includes(option.id)
  }
  return value === option.id || value === option.value
}

interface KanbanColumn {
  /** 选项 id；空串表示未分组列 */
  optionId: string
  title: string
  /** 选项颜色（无颜色或非选项列时缺省） */
  color?: string
  tasks: TaskVO[]
}

/** 按所选属性的选项分列（多选卡片可同时出现在多个选项列），复选分勾选/未勾选两列，人员按成员分列，末尾附未分组列 */
const columns = computed<KanbanColumn[]>(() => {
  const prop = groupProperty.value
  if (!prop) return []
  // 人员：按项目成员分列（值为成员 userId），孤儿值（成员已移除）与未设置均归未分组列
  if (prop.type === 'member') {
    const result: KanbanColumn[] = Object.entries(props.memberMap ?? {}).map(
      ([userId, nickname]) => ({
        optionId: userId,
        title: nickname,
        tasks: props.tasks.filter((task) => String(task.properties?.[prop.id]) === userId),
      }),
    )
    result.push({
      optionId: '',
      title: t('kanban.noGroup'),
      tasks: props.tasks.filter((task) => task.properties?.[prop.id] == null),
    })
    return result
  }
  // 复选：勾选列 + 未勾选列（未设置视为未勾选）
  if (prop.type === 'checkbox') {
    return [
      {
        optionId: CHECKED_OPTION_ID,
        title: t('kanban.checked'),
        tasks: props.tasks.filter((task) => task.properties?.[prop.id] === true),
      },
      {
        optionId: '',
        title: t('kanban.unchecked'),
        tasks: props.tasks.filter((task) => task.properties?.[prop.id] !== true),
      },
    ]
  }
  const isMulti = prop.type === 'multiSelect'
  const result: KanbanColumn[] = (prop.options ?? []).map((option) => ({
    optionId: option.id,
    title: option.value,
    color: option.color,
    tasks: props.tasks.filter((task) => taskInOption(task, prop.id, option, isMulti)),
  }))
  // 未分组列：属性值未设置或为空的任务
  const ungrouped = props.tasks.filter((task) => {
    const value = task.properties?.[prop.id]
    return value == null || value === '' || (Array.isArray(value) && value.length === 0)
  })
  result.push({
    optionId: '',
    title: t('kanban.noGroup'),
    tasks: ungrouped,
  })
  return result
})

/** 卡片展示的属性（只显示值）：按显示配置过滤 + 排除分组属性，select 附选项颜色，复选直接渲染勾选状态 */
function cardMetaProperties(task: TaskVO) {
  const prop = groupProperty.value
  return (props.cardProperties ?? [])
    .filter((p) => p.id !== prop?.id)
    .filter((p) => !props.cardPropertyIds || props.cardPropertyIds.includes(p.id))
    .map((p) => {
      const value = task.properties?.[p.id]
      // 复选：无论勾选与否都渲染为复选框样式（未勾选也占位展示）
      if (p.type === 'checkbox') {
        return { prop: p, type: 'checkbox' as const, checked: value === true, text: '' }
      }
      let color: string | undefined
      if (p.type === 'select' && typeof value === 'string') {
        color = p.options?.find((o) => o.id === value || o.value === value)?.color
      }
      return { prop: p, type: 'text' as const, text: propertyDisplayText(task, p, props.memberMap), color }
    })
    .filter((item) => item.type === 'checkbox' || item.text !== '—')
    .slice(0, props.cardPropertyIds ? undefined : 3)
}

/** 任务评论数（无数据按 0 计） */
function commentCountOf(task: TaskVO): number {
  return props.commentCounts?.[task.id] ?? 0
}

/** 评论提醒 tooltip 文案 */
function commentTip(task: TaskVO): string {
  const count = commentCountOf(task)
  return count > 0 ? t('kanban.commentCount', { count }) : t('kanban.noComment')
}

/** 任务附件数（无数据按 0 计） */
function attachmentCountOf(task: TaskVO): number {
  return task.attachmentCount ?? 0
}

/** 附件提醒 tooltip 文案 */
function attachmentTip(task: TaskVO): string {
  const count = attachmentCountOf(task)
  return count > 0 ? t('kanban.attachmentCount', { count }) : t('kanban.noAttachment')
}

/** 拖拽状态 */
const draggingTask = ref<TaskVO | null>(null)
const dragOverColumn = ref<string | null>(null)

function handleDragStart(task: TaskVO, event: DragEvent) {
  draggingTask.value = task
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(task.id))
  }
}

function handleDragOver(optionId: string, event: DragEvent) {
  event.preventDefault()
  dragOverColumn.value = optionId
}

function handleDragLeave(optionId: string) {
  if (dragOverColumn.value === optionId) {
    dragOverColumn.value = null
  }
}

/** 放置换列：单选替换值；多选追加选项；复选设勾选/未勾选；人员替换成员；拖到未分组列清除属性值 */
function handleDrop(column: KanbanColumn, event: DragEvent) {
  event.preventDefault()
  dragOverColumn.value = null
  const task = draggingTask.value
  const prop = groupProperty.value
  draggingTask.value = null
  if (!task || !prop) return
  if (prop.type === 'checkbox') {
    const target = column.optionId === CHECKED_OPTION_ID
    if ((task.properties?.[prop.id] === true) === target) return
    emit('moved', task, prop.id, target)
    return
  }
  if (prop.type === 'member') {
    if (column.optionId) {
      const target = Number(column.optionId)
      if (task.properties?.[prop.id] === target) return
      emit('moved', task, prop.id, target)
    } else {
      if (task.properties?.[prop.id] == null) return
      emit('moved', task, prop.id, undefined)
    }
    return
  }
  const isMulti = prop.type === 'multiSelect'
  const current = task.properties?.[prop.id]
  if (isMulti) {
    const list = Array.isArray(current) ? (current as unknown[]) : []
    if (column.optionId) {
      if (list.includes(column.optionId)) return
      emit('moved', task, prop.id, [...list, column.optionId])
    } else {
      if (list.length === 0) return
      emit('moved', task, prop.id, undefined)
    }
    return
  }
  if (column.optionId && taskInOption(task, prop.id, { id: column.optionId, value: '' }, false)) return
  if (!column.optionId && current == null) return
  emit('moved', task, prop.id, column.optionId || undefined)
}

/** 列内快捷新建：单选预置选项 id，多选预置选项 id 数组，复选预置布尔，人员预置成员 userId（模板表达式不支持计算键名对象，经脚本中转） */
function handleQuickAdd(column: KanbanColumn) {
  const prop = groupProperty.value
  if (!prop) return
  if (prop.type === 'checkbox') {
    emit('quickAdd', column.optionId === CHECKED_OPTION_ID ? { [prop.id]: true } : {})
    return
  }
  if (prop.type === 'member') {
    emit('quickAdd', column.optionId ? { [prop.id]: Number(column.optionId) } : {})
    return
  }
  if (!column.optionId) {
    emit('quickAdd', {})
    return
  }
  const preset = prop.type === 'multiSelect' ? [column.optionId] : column.optionId
  emit('quickAdd', { [prop.id]: preset })
}

/** 分组属性切换回调 */
function handleGroupChange(propertyId: string) {
  emit('groupChange', propertyId)
}
</script>

<template>
  <div v-loading="loading">
    <!-- 无可选分组属性时的空态 -->
    <el-empty
      v-if="selectProperties.length === 0"
      :description="t('kanban.noGroupProperty')"
    />
    <template v-else>
      <div class="kanban-toolbar">
        <span class="kanban-group-label">{{ t('kanban.groupBy') }}</span>
        <el-select
          :model-value="groupProperty?.id"
          size="small"
          class="kanban-group-select"
          @change="handleGroupChange"
        >
          <el-option
            v-for="prop in selectProperties"
            :key="prop.id"
            :value="prop.id"
            :label="prop.name"
          />
        </el-select>
        <span class="kanban-group-label">{{ t('kanban.showProperties') }}</span>
        <el-select
          :model-value="selectedCardProperties"
          multiple
          collapse-tags
          size="small"
          class="kanban-props-select"
          @update:model-value="handleCardPropertiesChange"
        >
          <el-option
            v-for="prop in displayableProperties"
            :key="prop.id"
            :value="prop.id"
            :label="prop.name"
          />
        </el-select>
      </div>

      <div class="kanban">
        <div v-for="column in columns" :key="column.optionId || '__no_group__'" class="kanban-column">
          <div class="kanban-column-header">
            <span
              v-if="column.color"
              class="kanban-column-dot"
              :style="{ background: column.color }"
            />
            <span class="kanban-column-title">{{ column.title }}</span>
            <span class="kanban-count">{{ column.tasks.length }}</span>
          </div>

          <div
            class="kanban-column-body"
            :class="{ 'drag-over': dragOverColumn === column.optionId }"
            @dragover="handleDragOver(column.optionId, $event)"
            @dragleave="handleDragLeave(column.optionId)"
            @drop="handleDrop(column, $event)"
          >
            <div
              v-for="task in column.tasks"
              :key="task.id"
              class="kanban-card"
              :draggable="editable"
              @dragstart="handleDragStart(task, $event)"
              @click="emit('open', task)"
            >
              <div class="kanban-card-title">
                <span v-if="task.icon" class="kanban-card-icon">{{ task.icon }}</span>
                {{ task.title }}
              </div>
              <div v-if="cardMetaProperties(task).length" class="kanban-card-meta">
                <span
                  v-for="item in cardMetaProperties(task)"
                  :key="item.prop.id"
                  class="kanban-card-prop"
                >
                  <span
                    v-if="item.type === 'checkbox'"
                    class="kanban-card-switch"
                    :class="{ checked: item.checked }"
                  />
                  <span
                    v-else-if="item.color"
                    class="kanban-card-prop-dot"
                    :style="{ background: item.color }"
                  />
                  <template v-if="item.type === 'text'">{{ item.text }}</template>
                </span>
              </div>
              <div class="kanban-card-footer">
                <el-tooltip :content="attachmentTip(task)" placement="top">
                  <span
                    class="kanban-card-attachments"
                    :class="{ active: attachmentCountOf(task) > 0 }"
                  >
                    <el-icon><Paperclip /></el-icon>
                    <span v-if="attachmentCountOf(task) > 0" class="kanban-card-attachment-count">
                      {{ attachmentCountOf(task) }}
                    </span>
                  </span>
                </el-tooltip>
                <el-tooltip :content="commentTip(task)" placement="top">
                  <span
                    class="kanban-card-comments"
                    :class="{ active: commentCountOf(task) > 0 }"
                  >
                    <el-icon><ChatDotRound /></el-icon>
                    <span v-if="commentCountOf(task) > 0" class="kanban-card-comment-count">
                      {{ commentCountOf(task) }}
                    </span>
                  </span>
                </el-tooltip>
              </div>
            </div>

            <el-empty
              v-if="column.tasks.length === 0"
              :description="t('kanban.emptyColumn')"
              :image-size="40"
            />

            <button v-if="editable" class="kanban-add" @click="handleQuickAdd(column)">
              <el-icon><Plus /></el-icon>
              {{ t('kanban.addCard') }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.kanban-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.kanban-group-label {
  font-size: 13px;
  color: var(--app-text-regular);
}

.kanban-group-select {
  width: 160px;
}

.kanban-props-select {
  width: 180px;
}

.kanban {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-height: 300px;
  overflow-x: auto;
}

.kanban-column {
  flex: 1;
  min-width: 240px;
  /* 列宽上限：列数少时避免单列过宽，超出部分横向滚动 */
  max-width: 320px;
  /* 暗色下用更暗一级的填充色作衬底，避免列背景比卡片亮显得刺眼 */
  background: var(--app-fill-light);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 300px);
}

.kanban-column-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}

.kanban-column-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.kanban-column-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1px solid rgba(0, 0, 0, 0.12);
}

.kanban-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.kanban-column-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
  border-radius: 0 0 8px 8px;
  transition: background 0.2s;
}

.kanban-column-body.drag-over {
  background: var(--el-color-primary-light-9);
}

.kanban-card {
  background: var(--app-surface);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.kanban-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.kanban-card-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
  word-break: break-word;
  display: flex;
  align-items: center;
  gap: 4px;
}

.kanban-card-icon {
  font-size: 15px;
  line-height: 1;
  flex-shrink: 0;
}

.kanban-card-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}

.kanban-card-prop {
  font-size: 12px;
  color: var(--app-text-secondary);
  display: flex;
  align-items: center;
  gap: 5px;
}

.kanban-card-prop-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.kanban-card-switch {
  width: 26px;
  height: 14px;
  border-radius: 7px;
  background: var(--app-border);
  position: relative;
  flex-shrink: 0;
  transition: background 0.2s;
}

.kanban-card-switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  transition: left 0.2s;
}

.kanban-card-switch.checked {
  background: var(--el-color-primary);
}

.kanban-card-switch.checked::after {
  left: 14px;
}

.kanban-card-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.kanban-card-attachments {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 13px;
  color: var(--app-text-placeholder);
}

.kanban-card-attachments.active {
  color: var(--el-color-primary);
}

.kanban-card-attachment-count {
  font-size: 12px;
  line-height: 1;
}

.kanban-card-comments {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 13px;
  color: var(--app-text-placeholder);
}

.kanban-card-comments.active {
  color: var(--el-color-primary);
}

.kanban-card-comment-count {
  font-size: 12px;
  line-height: 1;
}

.kanban-add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.kanban-add:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
</style>
