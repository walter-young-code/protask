<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import * as ganttApi from '@/api/gantt'
import { buildParentOptions } from '@/utils/taskTree'
import { START_DATE_PROPERTY_ID, DUE_DATE_PROPERTY_ID } from '@/utils/meta'
import type { DependencyType, DependencyVO, TaskVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  task: TaskVO | null
  projectId: number
  /** 项目全量任务（父任务候选与依赖任务名来源） */
  tasks: TaskVO[]
  /** 当前用户是否可编辑（editor+） */
  editable: boolean
  /** 项目依赖列表（父级加载，操作后通知刷新） */
  dependencies: DependencyVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 父任务/里程碑/排程设置局部更新（由详情弹窗写入表单并保存） */
  (e: 'patch', patch: {
    parentTaskId?: number | null
    milestone?: boolean
    scheduleMode?: 'manual' | 'auto'
    durationDays?: number
    clearConstraint?: boolean
  }): void
  /** 依赖增删改后通知容器刷新依赖列表 */
  (e: 'dependenciesChanged'): void
}>()

const { t } = useI18n()

/** 父任务/里程碑/排程本地态（即改即生效，props.task 刷新后回同步） */
const parentValue = ref<number>(0)
const milestoneValue = ref<boolean>(false)
const modeValue = ref<'manual' | 'auto'>('manual')
const durationValue = ref<number>(1)
watch(
  () => props.task,
  (task) => {
    parentValue.value = task?.parentTaskId ?? 0
    milestoneValue.value = task?.milestone ?? false
    modeValue.value = task?.scheduleMode ?? 'manual'
    durationValue.value = displayDuration(task)
  },
  { immediate: true },
)

/** 摘要任务：日期恒为子孙汇总，不参与排程设置 */
const isSummary = computed(() => (props.task?.childCount ?? 0) > 0)

/** 工期展示值：里程碑恒 0；显式工期优先，否则起止 diff+1，未排期默认 1 */
function displayDuration(task: TaskVO | null): number {
  if (!task) return 1
  if (task.milestone) return 0
  if (task.durationDays != null && task.durationDays > 0) return task.durationDays
  const s = task.properties?.[START_DATE_PROPERTY_ID]
  const e = task.properties?.[DUE_DATE_PROPERTY_ID]
  if (typeof s === 'string' && typeof e === 'string' && s.length >= 10 && e.length >= 10) {
    const days = Math.round((Date.parse(e.slice(0, 10)) - Date.parse(s.slice(0, 10))) / 86400000) + 1
    if (days >= 1) return Math.min(days, 3650)
  }
  return 1
}

/** 父任务候选（排除自身与子孙防环，带深度缩进） */
const parentOptions = computed(() => buildParentOptions(props.tasks, props.task?.id))

/** 里程碑禁用原因：摘要任务或起止非同日；空串=可用（与详情弹窗同语义） */
const milestoneDisabledReason = computed(() => {
  if ((props.task?.childCount ?? 0) > 0) return t('task.milestoneSummaryHint')
  const s = props.task?.properties?.[START_DATE_PROPERTY_ID]
  const e = props.task?.properties?.[DUE_DATE_PROPERTY_ID]
  const sameDay =
    typeof s === 'string' && typeof e === 'string' && s.length >= 10 && s.slice(0, 10) === e.slice(0, 10)
  return sameDay ? '' : t('task.milestoneSameDayHint')
})

/** 四种依赖类型选项 */
const typeOptions = computed<{ value: DependencyType; label: string }[]>(() => [
  { value: 'FS', label: t('gantt.depTypeFS') },
  { value: 'SS', label: t('gantt.depTypeSS') },
  { value: 'FF', label: t('gantt.depTypeFF') },
  { value: 'SF', label: t('gantt.depTypeSF') },
])

/** 任务 id -> 任务（依赖行任务名来源） */
const taskById = computed(() => {
  const map = new Map<number, TaskVO>()
  for (const task of props.tasks) map.set(task.id, task)
  return map
})
function taskLabel(id: number): string {
  const task = taskById.value.get(id)
  if (!task) return `#${id}`
  return (task.wbs ? `${task.wbs} ` : '') + task.title
}

/** 前置任务（后置=本任务的依赖）与后置任务（前置=本任务的依赖） */
const predecessors = computed(() => props.dependencies.filter((d) => d.successorId === props.task?.id))
const successors = computed(() => props.dependencies.filter((d) => d.predecessorId === props.task?.id))

/** 新增前置任务候选（排除自身与已存在前置） */
const predCandidates = computed(() => {
  const existing = new Set(predecessors.value.map((d) => d.predecessorId))
  return props.tasks.filter((task) => task.id !== props.task?.id && !existing.has(task.id))
})

/** 新增前置行状态 */
const newPredId = ref<number | null>(null)
const newType = ref<DependencyType>('FS')
const newLag = ref<number>(0)
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      newPredId.value = null
      newType.value = 'FS'
      newLag.value = 0
    }
  },
)

function onParentChange(value: number) {
  emit('patch', { parentTaskId: value ? value : null })
}

function onTypeChange(val: string | number | boolean | undefined) {
  const value = val === true
  if (value && milestoneDisabledReason.value) return
  if (value === (props.task?.milestone ?? false)) return
  emit('patch', { milestone: value })
}

function onModeChange(val: string | number | boolean | undefined) {
  const mode = val === 'auto' ? 'auto' : 'manual'
  if (isSummary.value) return
  if (mode === (props.task?.scheduleMode ?? 'manual')) return
  emit('patch', { scheduleMode: mode })
}

function onDurationChange(val: number | undefined) {
  if (val == null) return
  if (val === displayDuration(props.task)) return
  emit('patch', { durationDays: val })
}

function onClearConstraint() {
  emit('patch', { clearConstraint: true })
}

async function onDepTypeChange(dep: DependencyVO, type: DependencyType) {
  if (type === dep.type) return
  await ganttApi.updateDependency(dep.id, { type })
  appMessage.success(t('task.settingsDepUpdated'))
  emit('dependenciesChanged')
}

async function onDepLagChange(dep: DependencyVO, lag: number | undefined) {
  const value = lag ?? 0
  if (value === dep.lag) return
  await ganttApi.updateDependency(dep.id, { lag: value })
  appMessage.success(t('task.settingsDepUpdated'))
  emit('dependenciesChanged')
}

async function onDepDelete(dep: DependencyVO) {
  try {
    await ElMessageBox.confirm(t('task.settingsDeleteDepConfirm'), t('gantt.dependencyDeleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  await ganttApi.deleteDependency(dep.id)
  appMessage.success(t('gantt.dependencyDeleted'))
  emit('dependenciesChanged')
}

async function onAddPredecessor() {
  if (!props.task || newPredId.value == null) return
  await ganttApi.createDependency(props.projectId, {
    predecessorId: newPredId.value,
    successorId: props.task.id,
    type: newType.value,
    lag: newLag.value,
  })
  appMessage.success(t('gantt.dependencyCreated'))
  newPredId.value = null
  newType.value = 'FS'
  newLag.value = 0
  emit('dependenciesChanged')
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('task.settingsTitle')"
    width="640px"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="task" class="settings-body">
      <!-- 父任务 -->
      <section class="settings-section">
        <h4 class="settings-section-title">{{ t('task.settingsParentSection') }}</h4>
        <el-select
          v-model="parentValue"
          clearable
          filterable
          :disabled="!editable"
          :placeholder="t('task.parentNone')"
          class="settings-parent-select"
          @change="onParentChange"
        >
          <el-option :value="0" :label="t('task.parentNone')" />
          <el-option
            v-for="opt in parentOptions"
            :key="opt.id"
            :value="opt.id"
            :label="(opt.wbs ? opt.wbs + ' ' : '') + opt.title"
          >
            <span :style="{ paddingLeft: `${opt.depth * 14}px` }">
              <span class="settings-wbs">{{ opt.wbs }}</span> {{ opt.title }}
            </span>
          </el-option>
        </el-select>
        <p class="settings-hint">{{ t('task.parentHint') }}</p>
      </section>

      <!-- 任务类型 -->
      <section class="settings-section">
        <h4 class="settings-section-title">{{ t('task.settingsTypeSection') }}</h4>
        <el-radio-group v-model="milestoneValue" :disabled="!editable" @change="onTypeChange">
          <el-radio :value="false">{{ t('gantt.typeNormal') }}</el-radio>
          <el-radio :value="true" :disabled="milestoneDisabledReason !== ''">
            <span :title="milestoneDisabledReason || undefined">{{ t('gantt.typeMilestone') }}</span>
          </el-radio>
        </el-radio-group>
        <p v-if="milestoneDisabledReason" class="settings-hint">{{ milestoneDisabledReason }}</p>
      </section>

      <!-- 排程设置 -->
      <section class="settings-section">
        <h4 class="settings-section-title">{{ t('task.settingsScheduleSection') }}</h4>
        <el-radio-group v-model="modeValue" :disabled="!editable || isSummary" @change="onModeChange">
          <el-radio value="manual">{{ t('task.settingsModeManual') }}</el-radio>
          <el-radio value="auto">{{ t('task.settingsModeAuto') }}</el-radio>
        </el-radio-group>
        <p v-if="isSummary" class="settings-hint">{{ t('task.settingsSummaryScheduleHint') }}</p>
        <p v-else class="settings-hint">{{ t('task.settingsModeHint') }}</p>
        <div class="schedule-row">
          <span class="schedule-label">{{ t('task.settingsDuration') }}</span>
          <el-input-number
            v-model="durationValue"
            :min="milestoneValue ? 0 : 1"
            :max="3650"
            :step="1"
            :disabled="!editable || isSummary || milestoneValue"
            controls-position="right"
            class="schedule-duration"
            @change="onDurationChange"
          />
          <span class="dep-lag-unit">{{ t('gantt.lagDay') }}</span>
        </div>
        <p class="settings-hint">{{ t('task.settingsDurationHint') }}</p>
        <div v-if="modeValue === 'auto' && task.startConstraint" class="schedule-row">
          <span class="schedule-label">{{ t('task.settingsConstraint') }}</span>
          <span class="dep-tag">{{ task.startConstraint }}</span>
          <el-button size="small" :disabled="!editable" @click="onClearConstraint">
            {{ t('task.settingsClearConstraint') }}
          </el-button>
        </div>
      </section>

      <!-- 前置任务 -->
      <section class="settings-section">
        <h4 class="settings-section-title">{{ t('task.settingsPredecessors') }}</h4>
        <div v-if="!predecessors.length" class="settings-empty">{{ t('task.settingsNoPredecessors') }}</div>
        <div v-for="dep in predecessors" :key="dep.id" class="dep-row">
          <span class="dep-name" :title="taskLabel(dep.predecessorId)">{{ taskLabel(dep.predecessorId) }}</span>
          <el-select
            :model-value="dep.type"
            size="small"
            :disabled="!editable"
            class="dep-type"
            @change="(val: DependencyType) => onDepTypeChange(dep, val)"
          >
            <el-option v-for="opt in typeOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
          <el-input-number
            :model-value="dep.lag"
            size="small"
            :min="-365"
            :max="365"
            :step="1"
            :disabled="!editable"
            controls-position="right"
            class="dep-lag"
            @change="(val: number | undefined) => onDepLagChange(dep, val)"
          />
          <span class="dep-lag-unit">{{ t('gantt.lagDay') }}</span>
          <el-button
            size="small"
            type="danger"
            link
            :icon="Delete"
            :disabled="!editable"
            :title="t('common.delete')"
            @click="onDepDelete(dep)"
          />
        </div>
        <!-- 新增前置任务 -->
        <div v-if="editable" class="dep-row dep-add">
          <el-select
            v-model="newPredId"
            size="small"
            filterable
            clearable
            :placeholder="t('task.settingsSelectTask')"
            class="dep-name-select"
          >
            <el-option
              v-for="task in predCandidates"
              :key="task.id"
              :value="task.id"
              :label="(task.wbs ? task.wbs + ' ' : '') + task.title"
            />
          </el-select>
          <el-select v-model="newType" size="small" class="dep-type">
            <el-option v-for="opt in typeOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
          <el-input-number
            v-model="newLag"
            size="small"
            :min="-365"
            :max="365"
            :step="1"
            controls-position="right"
            class="dep-lag"
          />
          <span class="dep-lag-unit">{{ t('gantt.lagDay') }}</span>
          <el-button
            size="small"
            type="primary"
            :icon="Plus"
            :disabled="newPredId == null"
            @click="onAddPredecessor"
          >
            {{ t('task.settingsAddPredecessor') }}
          </el-button>
        </div>
        <p class="settings-hint">{{ t('task.settingsLagHint') }}</p>
      </section>

      <!-- 后置任务（只读） -->
      <section class="settings-section">
        <h4 class="settings-section-title">{{ t('task.settingsSuccessors') }}</h4>
        <div v-if="!successors.length" class="settings-empty">{{ t('task.settingsNoSuccessors') }}</div>
        <div v-for="dep in successors" :key="dep.id" class="dep-row dep-readonly">
          <span class="dep-name" :title="taskLabel(dep.successorId)">{{ taskLabel(dep.successorId) }}</span>
          <span class="dep-tag">{{ dep.type }}</span>
          <span v-if="dep.lag" class="dep-tag">{{ dep.lag > 0 ? '+' : '' }}{{ dep.lag }}{{ t('gantt.lagDay') }}</span>
        </div>
        <p class="settings-hint">{{ t('task.settingsSuccessorsHint') }}</p>
      </section>
    </div>
    <template #footer>
      <el-button @click="close">{{ t('common.close') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.settings-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.settings-section-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.settings-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.settings-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 4px 0;
}

.settings-parent-select {
  width: 100%;
}

.schedule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.schedule-label {
  font-size: 13px;
  color: var(--el-text-color-primary);
  flex-shrink: 0;
}

.schedule-duration {
  width: 130px;
}

.settings-wbs {
  color: var(--el-text-color-secondary);
  margin-right: 4px;
}

.dep-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.dep-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dep-name-select {
  flex: 1;
  min-width: 0;
}

.dep-type {
  width: 150px;
  flex-shrink: 0;
}

.dep-lag {
  width: 110px;
  flex-shrink: 0;
}

.dep-lag-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.dep-tag {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 2px 8px;
  flex-shrink: 0;
}

.dep-add {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
</style>