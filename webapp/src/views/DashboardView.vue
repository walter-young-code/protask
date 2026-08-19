<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { getDashboard } from '@/api/task'
import ProjectDialog from '@/components/ProjectDialog.vue'
import TemplateSelectorDialog from '@/components/TemplateSelectorDialog.vue'
import type { DashboardTaskItemVO, DashboardVO } from '@/types'

const { t } = useI18n()
const router = useRouter()
const loading = ref(false)
const dashboard = ref<DashboardVO | null>(null)
const dialogVisible = ref(false)
/** 项目模板选择弹窗（新建项目入口） */
const templateSelectorVisible = ref(false)

/** 统计卡片：key 对应 i18n dashboard.stats.*，tone 控制数字颜色 */
const statCards = computed(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'total', value: d.totalTasks, tone: 'default' },
    { key: 'my', value: d.myTasks, tone: 'default' },
    { key: 'urgent', value: d.urgentTasks, tone: 'danger' },
    { key: 'overdue', value: d.overdueTasks, tone: 'danger' },
    { key: 'dueSoon', value: d.dueSoonTasks, tone: 'warning' },
    { key: 'done', value: d.doneTasks, tone: 'success' },
  ]
})

/** 分类任务列表：key 对应 i18n dashboard.lists.*，count 为完整统计数（列表最多展示 20 条） */
const taskLists = computed(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'my', items: d.myTaskList, count: d.myTasks },
    { key: 'urgent', items: d.urgentList, count: d.urgentTasks },
    { key: 'overdue', items: d.overdueList, count: d.overdueTasks },
    { key: 'dueSoon', items: d.dueSoonList, count: d.dueSoonTasks },
  ]
})

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await getDashboard()
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)

function handleCreated(id: number) {
  dialogVisible.value = false
  router.push({ name: 'project-detail', params: { id } })
}

/** 模板选择器「创建空白项目」：关闭后打开空白项目表单 */
function handleTemplateBlank() {
  dialogVisible.value = true
}

/** 打开项目内对应任务详情（复用全局搜索的 ?task= 跳转机制） */
function openTask(task: DashboardTaskItemVO) {
  router.push({ name: 'project-detail', params: { id: task.projectId }, query: { task: task.id } })
}
</script>

<template>
  <div v-loading="loading" class="page-container">
    <div class="page-header">
      <h2>{{ t('dashboard.title') }}</h2>
      <el-button type="primary" :icon="Plus" @click="templateSelectorVisible = true">
        {{ t('dashboard.newProject') }}
      </el-button>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col v-for="stat in statCards" :key="stat.key" :xs="12" :sm="8" :md="4">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-value" :class="`stat-value-${stat.tone}`">{{ stat.value }}</div>
          <div class="stat-label">{{ t(`dashboard.stats.${stat.key}`) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty
      v-if="!loading && dashboard && dashboard.totalTasks === 0"
      :description="t('dashboard.emptyTasks')"
    />

    <el-row v-else-if="dashboard && dashboard.totalTasks > 0" :gutter="16" class="task-list-row">
      <el-col v-for="list in taskLists" :key="list.key" :xs="24" :md="12">
        <el-card class="task-list-card" shadow="hover">
          <template #header>
            <div class="task-list-header">
              <span class="task-list-title">{{ t(`dashboard.lists.${list.key}`) }}</span>
              <el-tag size="small" :type="list.key === 'overdue' || list.key === 'urgent' ? 'danger' : 'info'" effect="plain">
                {{ list.count }}
              </el-tag>
            </div>
          </template>
          <div v-if="list.items.length === 0" class="task-list-empty">
            {{ t('dashboard.listEmpty') }}
          </div>
          <div
            v-for="task in list.items"
            :key="task.id"
            class="task-row"
            @click="openTask(task)"
          >
            <span v-if="task.icon" class="task-icon">{{ task.icon }}</span>
            <span class="task-title">{{ task.title }}</span>
            <el-tag size="small" effect="plain" class="task-project">{{ task.projectName }}</el-tag>
            <span
              v-if="task.dueDate"
              class="task-due"
              :class="{ 'task-due-overdue': list.key === 'overdue' }"
            >
              {{ task.dueDate }}
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <ProjectDialog v-model="dialogVisible" @created="handleCreated" />

    <!-- 项目模板选择弹窗（新建项目入口：模板库 / 空白项目） -->
    <TemplateSelectorDialog v-model="templateSelectorVisible" @created="handleCreated" @blank="handleTemplateBlank" />
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  margin-bottom: 12px;
  text-align: center;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-value-default {
  color: var(--app-text-primary);
}

.stat-value-danger {
  color: var(--el-color-danger);
}

.stat-value-warning {
  color: var(--el-color-warning);
}

.stat-value-success {
  color: var(--el-color-success);
}

.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.task-list-row {
  margin-top: 4px;
}

.task-list-card {
  margin-bottom: 16px;
}

.task-list-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-list-title {
  font-size: 15px;
  font-weight: 600;
}

.task-list-empty {
  padding: 8px 0;
  font-size: 13px;
  color: var(--app-text-secondary);
  text-align: center;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 4px;
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.task-row + .task-row {
  border-top: 1px solid var(--app-border-lighter);
}

.task-row:hover {
  background-color: var(--app-hover);
}

.task-icon {
  flex-shrink: 0;
  font-size: 16px;
  line-height: 1;
}

.task-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-project {
  flex-shrink: 0;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-due {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.task-due-overdue {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
