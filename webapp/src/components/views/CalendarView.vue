<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { CardProperty, TaskVO } from '@/types'

const props = defineProps<{
  tasks: TaskVO[]
  loading: boolean
  /** 项目卡片动态属性模板 */
  cardProperties: CardProperty[]
  /** 当前日期属性 id（date 属性，持久化于视图配置） */
  dateId?: string
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
  (e: 'dateChange', propertyId: string): void
}>()

const { t } = useI18n()

/** 可作为日历依据的日期属性 */
const dateProperties = computed(() =>
  (props.cardProperties ?? []).filter((p) => p.type === 'date'),
)

/** 生效的日期属性（未选或失效时回退第一个日期属性） */
const dateProperty = computed(
  () => dateProperties.value.find((p) => p.id === props.dateId) ?? dateProperties.value[0],
)

/** 任务在日期属性上的取值（YYYY-MM-DD） */
function taskDayKey(task: TaskVO): string {
  const prop = dateProperty.value
  if (!prop) return ''
  const value = task.properties?.[prop.id]
  return typeof value === 'string' ? value.slice(0, 10) : ''
}

/** 日期属性切换回调 */
function handleDateChange(propertyId: string) {
  emit('dateChange', propertyId)
}

/** 周一起始的星期标签 */
const weekdays = computed(() =>
  ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'].map((key) => t(`calendar.weekday.${key}`)),
)

/** 当前展示的月份（该月第一天） */
const cursor = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1))

function toDayKey(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 月历格：从本月第一天所在周的周一排到最后一周的周日 */
const days = computed<Date[]>(() => {
  const first = cursor.value
  // getDay(): 0=周日，换算为周一起始（0=周一）
  const startOffset = (first.getDay() + 6) % 7
  const start = new Date(first.getFullYear(), first.getMonth(), 1 - startOffset)
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0)
  const endOffset = 6 - ((last.getDay() + 6) % 7)
  const end = new Date(last.getFullYear(), last.getMonth(), last.getDate() + endOffset)
  const result: Date[] = []
  for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    result.push(new Date(d))
  }
  return result
})

/** 按所选日期属性值归组任务 */
const tasksByDay = computed(() => {
  const map = new Map<string, TaskVO[]>()
  for (const task of props.tasks) {
    const key = taskDayKey(task)
    if (!key) continue
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(task)
  }
  return map
})

/** 未设置日期属性值的任务 */
const unscheduled = computed(() => props.tasks.filter((task) => !taskDayKey(task)))

const monthTitle = computed(() =>
  t('calendar.monthTitle', {
    year: cursor.value.getFullYear(),
    month: cursor.value.getMonth() + 1,
  }),
)

function isCurrentMonth(day: Date) {
  return day.getMonth() === cursor.value.getMonth()
}

function isToday(day: Date) {
  const now = new Date()
  return (
    day.getFullYear() === now.getFullYear() &&
    day.getMonth() === now.getMonth() &&
    day.getDate() === now.getDate()
  )
}

function shiftMonth(offset: number) {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + offset, 1)
}

function goToday() {
  const now = new Date()
  cursor.value = new Date(now.getFullYear(), now.getMonth(), 1)
}
</script>

<template>
  <div v-loading="loading" class="calendar">
    <!-- 无可选日期属性时的空态 -->
    <el-empty v-if="dateProperties.length === 0" :description="t('calendar.noDateProperty')" />
    <template v-else>
      <div class="calendar-toolbar">
        <span class="calendar-title">{{ monthTitle }}</span>
        <div class="calendar-nav">
          <el-select
            :model-value="dateProperty?.id"
            size="small"
            class="calendar-date-select"
            @change="handleDateChange"
          >
            <el-option
              v-for="prop in dateProperties"
              :key="prop.id"
              :value="prop.id"
              :label="prop.name"
            />
          </el-select>
          <el-button :icon="ArrowLeft" @click="shiftMonth(-1)" />
          <el-button @click="goToday">{{ t('calendar.today') }}</el-button>
          <el-button :icon="ArrowRight" @click="shiftMonth(1)" />
        </div>
      </div>

      <div class="calendar-grid">
        <div v-for="weekday in weekdays" :key="weekday" class="calendar-weekday">
          {{ weekday }}
        </div>
        <div
          v-for="day in days"
          :key="toDayKey(day)"
          class="calendar-cell"
          :class="{ 'other-month': !isCurrentMonth(day), today: isToday(day) }"
        >
          <div class="calendar-day-num">{{ day.getDate() }}</div>
          <div
            v-for="task in tasksByDay.get(toDayKey(day)) ?? []"
            :key="task.id"
            class="calendar-task"
            @click="emit('open', task)"
          >
            <el-tag size="small" effect="plain" disable-transitions>
              <span v-if="task.icon" class="calendar-task-icon">{{ task.icon }}</span>
              {{ task.title }}
            </el-tag>
          </div>
        </div>
      </div>

      <div v-if="unscheduled.length" class="calendar-unscheduled">
        <div class="calendar-unscheduled-title">{{ t('calendar.unscheduled') }}</div>
        <div class="calendar-unscheduled-list">
          <el-tag
            v-for="task in unscheduled"
            :key="task.id"
            class="calendar-unscheduled-tag"
            effect="plain"
            @click="emit('open', task)"
          >
            <span v-if="task.icon" class="calendar-task-icon">{{ task.icon }}</span>
            {{ task.title }}
          </el-tag>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.calendar {
  background: var(--app-surface);
  border-radius: 8px;
  padding: 12px;
}

.calendar-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.calendar-title {
  font-size: 16px;
  font-weight: 600;
}

.calendar-nav {
  display: flex;
  gap: 4px;
  align-items: center;
}

.calendar-date-select {
  width: 150px;
  margin-right: 8px;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border: 1px solid var(--app-border-light);
  border-radius: 6px;
  overflow: hidden;
}

.calendar-weekday {
  padding: 8px;
  text-align: center;
  font-size: 13px;
  color: var(--app-text-regular);
  background: var(--app-fill);
  border-bottom: 1px solid var(--app-border-light);
}

.calendar-cell {
  min-height: 96px;
  padding: 6px;
  border-right: 1px solid var(--app-border-light);
  border-bottom: 1px solid var(--app-border-light);
  background: var(--app-surface);
}

.calendar-cell:nth-child(7n) {
  border-right: none;
}

.calendar-cell.other-month {
  background: var(--app-fill-lighter);
}

.calendar-cell.other-month .calendar-day-num {
  color: var(--app-text-placeholder);
}

.calendar-cell.today .calendar-day-num {
  color: var(--el-color-primary);
  font-weight: 700;
}

.calendar-day-num {
  font-size: 13px;
  color: var(--app-text-regular);
  margin-bottom: 4px;
}

.calendar-task {
  margin-bottom: 4px;
  cursor: pointer;
}

.calendar-task .el-tag {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 取消边框与底纹，仅保留文字；行高等于高度实现垂直居中 */
  display: block;
  height: var(--el-tag-height);
  line-height: var(--el-tag-height);
  border: none;
  background: transparent;
  color: var(--app-text-regular);
  padding: 0;
}

.calendar-task .el-tag:hover {
  color: var(--el-color-primary);
}

.calendar-task-icon {
  margin-right: 2px;
  font-size: 12px;
}

.calendar-unscheduled {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--app-border-light);
}

.calendar-unscheduled-title {
  font-size: 13px;
  color: var(--app-text-secondary);
  margin-bottom: 8px;
}

.calendar-unscheduled-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.calendar-unscheduled-tag {
  cursor: pointer;
  border: none;
  background: transparent;
  color: var(--app-text-regular);
  padding: 0;
}

.calendar-unscheduled-tag:hover {
  color: var(--el-color-primary);
}
</style>
