<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { Calendar, Operation, Plus, Share, User } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import * as projectApi from '@/api/project'
import * as taskApi from '@/api/task'
import * as blockApi from '@/api/block'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import {
  canManageCards,
  canManageProperties,
  canManageRoles,
  canComment,
  parseCardProperties,
} from '@/utils/permission'
import { applyView, DEFAULT_VIEW_CONFIG, parseViewConfig, toViewFields } from '@/utils/viewConfig'
import type {
  BlockVO,
  ProjectMemberVO,
  TaskVO,
  ViewConfig,
  ViewTab,
  ViewType,
} from '@/types'
import ViewHeader from '@/components/ViewHeader.vue'
import TableView from '@/components/views/TableView.vue'
import KanbanView from '@/components/views/KanbanView.vue'
import GalleryView from '@/components/views/GalleryView.vue'
import CalendarView from '@/components/views/CalendarView.vue'
import TaskDialog from '@/components/TaskDialog.vue'
import TaskDetailDialog from '@/components/TaskDetailDialog.vue'
import MemberDialog from '@/components/MemberDialog.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import CalendarSubscribeDialog from '@/components/CalendarSubscribeDialog.vue'
import { useShareStore } from '@/stores/share'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const userStore = useUserStore()

const loading = ref(false)
const tasks = ref<TaskVO[]>([])
const viewBlocks = ref<BlockVO[]>([])
const keyword = ref('')
/** 各任务的评论数（任务 id -> 评论数，供卡片徽标展示） */
const commentCounts = ref<Record<number, number>>({})
/** 项目成员列表（member 类型属性的选项来源） */
const members = ref<ProjectMemberVO[]>([])

/** 成员映射：userId -> 昵称（member 属性显示/选择用） */
const memberMap = computed<Record<number, string>>(() =>
  Object.fromEntries(
    members.value.map((m) => [m.userId, m.nickname ?? m.username ?? String(m.userId)]),
  ),
)

const createDialogVisible = ref(false)
const createInitialProperties = ref<Record<string, unknown> | undefined>(undefined)
const memberDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const detailTask = ref<TaskVO | null>(null)
const shareDialogVisible = ref(false)
const calendarSubscribeDialogVisible = ref(false)

/** 当前用户在项目中的生效角色（后端下发） */
const myRole = () => projectStore.currentProject?.myRole
const editable = () => canManageCards(projectStore.currentProject ?? undefined)
const manageProperties = () => canManageProperties(projectStore.currentProject ?? undefined)
const adminLevel = () => canManageRoles(projectStore.currentProject ?? undefined)
const commentable = () => canComment(projectStore.currentProject ?? undefined)

const projectId = () => Number(route.params.id)

/** view 块解析为视图 Tab */
const views = computed<ViewTab[]>(() => {
  if (viewBlocks.value.length > 0) {
    return viewBlocks.value.map((block) => ({
      id: block.id,
      title: block.title || t('view.defaultName'),
      config: parseViewConfig(block.fields),
    }))
  }
  // 无视图块时（如只读成员进入尚无视图的项目）使用内存默认视图
  return [{ id: -1, title: t('view.defaultName'), config: DEFAULT_VIEW_CONFIG }]
})

/** 项目默认视图 id（存于 project.properties.defaultViewId，容错解析） */
const defaultViewId = computed<number | undefined>(() => {
  const raw = projectStore.currentProject?.properties
  if (!raw) return undefined
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    return typeof parsed.defaultViewId === 'number' ? parsed.defaultViewId : undefined
  } catch {
    return undefined
  }
})

/** 激活视图：优先路由参数（显式指定），其次项目默认视图，最后第一个视图 */
const activeView = computed<ViewTab | undefined>(() => {
  const viewId = Number(route.params.viewId)
  if (viewId) {
    const explicit = views.value.find((v) => v.id === viewId)
    if (explicit) return explicit
  }
  return views.value.find((v) => v.id === defaultViewId.value) ?? views.value[0]
})

/** 呈现管线：筛选 -> 关键词 -> 排序（按属性模板驱动） */
const visibleTasks = computed(() =>
  applyView(
    tasks.value,
    activeView.value?.config ?? DEFAULT_VIEW_CONFIG,
    cardProperties.value,
    keyword.value,
  ),
)

const cardProperties = computed(() => parseCardProperties(projectStore.currentProject ?? undefined))

async function loadProject() {
  const project = await projectApi.getProject(projectId())
  projectStore.setCurrentProject(project)
}

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await taskApi.listTasks(projectId())
    await loadCommentCounts()
  } finally {
    loading.value = false
  }
}

/** 批量加载评论块并统计各任务评论数（避免 N+1 查询） */
async function loadCommentCounts() {
  const blocks = await blockApi.listBlocks(projectId(), 'comment')
  const counts: Record<number, number> = {}
  for (const block of blocks) {
    if (block.parentId != null) {
      counts[block.parentId] = (counts[block.parentId] ?? 0) + 1
    }
  }
  commentCounts.value = counts
}

async function loadViews() {
  viewBlocks.value = await blockApi.listBlocks(projectId(), 'view')
  // 编辑者首次进入时自动创建默认表格视图
  if (viewBlocks.value.length === 0 && editable()) {
    await createViewBlock('table', t('view.defaultName'), DEFAULT_VIEW_CONFIG, true)
  }
}

/** 加载项目成员列表（member 类型属性需要） */
async function loadMembers() {
  members.value = await projectApi.listMembers(projectId())
}

async function createViewBlock(
  type: ViewType,
  title: string,
  config: ViewConfig,
  switchTo = false,
) {
  const block = await blockApi.createBlock(projectId(), {
    type: 'view',
    title,
    fields: { ...toViewFields(config), viewType: type },
  })
  viewBlocks.value = [...viewBlocks.value, block]
  if (switchTo) {
    switchView(block.id)
  }
  return block
}

function switchView(viewId: number) {
  keyword.value = ''
  router.replace({
    name: 'project-detail',
    params: { id: projectId(), viewId },
    query: route.query,
  })
}

async function handleCreateView(type: ViewType, title: string) {
  const block = await createViewBlock(type, title, { ...DEFAULT_VIEW_CONFIG, viewType: type }, true)
  appMessage.success(t('view.created', { title: block.title }))
}

/** 设置项目默认视图（切换项目后优先展示） */
async function handleSetDefaultView(viewId: number) {
  const project = await projectApi.setDefaultView(projectId(), viewId)
  projectStore.setCurrentProject(project)
  appMessage.success(t('view.defaultSet'))
}

async function handleRenameView(viewId: number, title: string) {
  const updated = await blockApi.patchBlock(viewId, { title })
  viewBlocks.value = viewBlocks.value.map((b) => (b.id === viewId ? updated : b))
}

async function handleRemoveView(viewId: number) {
  await blockApi.deleteBlock(viewId)
  viewBlocks.value = viewBlocks.value.filter((b) => b.id !== viewId)
  if (activeView.value?.id === viewId) {
    if (viewBlocks.value.length > 0) {
      switchView(viewBlocks.value[0].id)
    } else if (editable()) {
      // 删除最后一个视图后重建默认视图
      await createViewBlock('table', t('view.defaultName'), DEFAULT_VIEW_CONFIG, true)
    }
  }
  // 被删除的视图是默认视图时同步清除默认设置
  if (defaultViewId.value === viewId) {
    const project = await projectApi.setDefaultView(projectId(), null)
    projectStore.setCurrentProject(project)
  }
}

/** 视图配置变更：持久化到 view 块的 fields */
async function handleUpdateConfig(viewId: number, config: ViewConfig) {
  if (viewId === -1) return
  const updated = await blockApi.patchBlock(viewId, { updatedFields: toViewFields(config) })
  viewBlocks.value = viewBlocks.value.map((b) => (b.id === viewId ? updated : b))
}

function openCreateDialog(initialProperties?: Record<string, unknown>) {
  createInitialProperties.value = initialProperties
  createDialogVisible.value = true
}

/** 头部 Operation 下拉按钮命令分发 */
function handleHeaderCommand(command: string) {
  if (command === 'create') {
    openCreateDialog()
  } else if (command === 'members') {
    memberDialogVisible.value = true
  } else if (command === 'share') {
    shareDialogVisible.value = true
  } else if (command === 'calendarSubscribe') {
    calendarSubscribeDialogVisible.value = true
  }
}

function openDetail(task: TaskVO) {
  detailTask.value = task
  detailDialogVisible.value = true
}

/** 看板拖拽换列：整体替换 properties 中的分组属性值后刷新（单选传选项 id、多选传选项 id 数组、undefined 清除） */
async function handleCardMoved(task: TaskVO, propertyId: string, value: unknown) {
  const properties = { ...(task.properties ?? {}) }
  if (value == null || (Array.isArray(value) && value.length === 0)) {
    delete properties[propertyId]
  } else {
    properties[propertyId] = value
  }
  await taskApi.updateTask(task.id, {
    title: task.title,
    description: task.description,
    properties,
  })
  await loadTasks()
}

/** 看板分组属性切换：持久化到当前视图配置 */
function handleGroupChange(propertyId: string) {
  const view = activeView.value
  if (!view) return
  handleUpdateConfig(view.id, { ...view.config, groupById: propertyId })
}

/** 看板卡片显示属性变更：持久化到当前视图配置 */
function handleCardPropertiesChange(propertyIds: string[] | undefined) {
  const view = activeView.value
  if (!view) return
  handleUpdateConfig(view.id, { ...view.config, cardPropertyIds: propertyIds })
}

/** 日历日期属性切换：持久化到当前视图配置 */
function handleDateChange(propertyId: string) {
  const view = activeView.value
  if (!view) return
  handleUpdateConfig(view.id, { ...view.config, dateId: propertyId })
}

async function handleDelete(task: TaskVO) {
  try {
    await ElMessageBox.confirm(t('task.deleteConfirm', { title: task.title }), t('task.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  await taskApi.deleteTask(task.id)
  appMessage.success(t('task.deleted'))
  await loadTasks()
}

/** 详情弹窗保存回调：有更新结果则就地替换，否则整体刷新；评论数可能变化，同步重统计 */
async function handleDetailSaved(updated?: TaskVO) {
  if (updated) {
    tasks.value = tasks.value.map((task) => (task.id === updated.id ? updated : task))
    if (detailTask.value?.id === updated.id) {
      detailTask.value = updated
    }
  } else {
    loadTasks()
  }
  await loadCommentCounts()
}

/** 处理全局搜索跳转携带的 ?task=xxx：打开对应任务详情 */
async function handleTaskQuery() {
  const taskId = Number(route.query.task)
  if (!taskId) return
  if (!tasks.value.length) {
    await loadTasks()
  }
  const task = tasks.value.find((item) => item.id === taskId)
  if (task) {
    openDetail(task)
  }
  const restQuery = { ...route.query }
  delete restQuery.task
  router.replace({ query: restQuery })
}

onMounted(async () => {
  // 先加载项目（拿到 myRole 权限上下文），再并行加载任务与视图，
  // 避免默认视图自动创建时 editable() 尚未就绪的竞态
  await loadProject()
  await Promise.all([loadTasks(), loadViews(), loadMembers()])
  await handleTaskQuery()
})

// 路由参数变化时（侧边栏切换项目）重新加载
watch(
  () => route.params.id,
  (id) => {
    if (route.name === 'project-detail' && id) {
      keyword.value = ''
      loadProject().then(() => {
        loadTasks()
        loadViews()
        loadMembers()
      })
    }
  },
)

// 分享访问模式：路由携带 share 参数时记录分享凭证（请求自动附加 X-Share-Token），否则清除
const shareStore = useShareStore()
watch(
  () => [route.params.id, route.query.share],
  () => {
    const shareId = typeof route.query.share === 'string' ? route.query.share : null
    if (shareId) {
      shareStore.setShareId(shareId)
    } else {
      shareStore.clearShareId()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  shareStore.clearShareId()
})

watch(
  () => route.query.task,
  (taskId) => {
    if (taskId && route.name === 'project-detail') {
      handleTaskQuery()
    }
  },
)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2 class="page-title">
          <span v-if="projectStore.currentProject?.icon" class="page-title-icon">
            {{ projectStore.currentProject.icon }}
          </span>
          {{ projectStore.currentProject?.name || t('projectDetail.fallbackTitle') }}
          <el-tag
            v-if="projectStore.currentProject?.type"
            size="small"
            effect="plain"
            class="meta-tag"
          >
            {{
              projectStore.currentProject.type === 'O'
                ? t('projectDetail.open')
                : t('projectDetail.private')
            }}
          </el-tag>
          <el-tag v-if="myRole()" size="small" type="info" effect="plain" class="meta-tag">
            {{ t(`role.${myRole()}`) }}
          </el-tag>
        </h2>
        <p class="project-desc">
          {{ projectStore.currentProject?.description || t('projectDetail.noDescription') }}
        </p>
      </div>
      <div class="header-actions">
        <el-dropdown
          v-if="adminLevel() || editable()"
          trigger="click"
          @command="handleHeaderCommand"
        >
          <span class="header-control-btn" :title="t('projectDetail.controls')">
            <el-icon :size="16"><Operation /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="editable()" command="create" :icon="Plus">
                {{ t('projectDetail.newTask') }}
              </el-dropdown-item>
              <el-dropdown-item v-if="adminLevel()" command="members" :icon="User">
                {{ t('projectDetail.manageMembers') }}
              </el-dropdown-item>
              <el-dropdown-item
                v-if="adminLevel() && projectStore.currentProject?.type === 'O'"
                command="share"
                :icon="Share"
              >
                {{ t('projectDetail.shareProject') }}
              </el-dropdown-item>
              <el-dropdown-item v-if="adminLevel()" command="calendarSubscribe" :icon="Calendar">
                {{ t('projectDetail.calendarSubscribe') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-alert
      v-if="!editable()"
      :title="t('projectDetail.readOnly')"
      type="info"
      :closable="false"
      class="readonly-alert"
    />

    <!-- 视图工具栏：视图切换 / 筛选 / 排序 / 搜索 -->
    <ViewHeader
      :views="views"
      :active-view-id="activeView?.id ?? -1"
      :editable="editable()"
      :default-view-id="defaultViewId"
      v-model:keyword="keyword"
      :card-properties="cardProperties"
      :visible-count="visibleTasks.length"
      :total-count="tasks.length"
      @switch="switchView"
      @create="handleCreateView"
      @rename="handleRenameView"
      @remove="handleRemoveView"
      @set-default="handleSetDefaultView"
      @update-config="handleUpdateConfig"
    />

    <!-- 按视图类型渲染 -->
    <TableView
      v-if="activeView?.config.viewType === 'table'"
      :tasks="visibleTasks"
      :loading="loading"
      :editable="editable()"
      :card-properties="cardProperties"
      :member-map="memberMap"
      @open="openDetail"
      @delete="handleDelete"
      @quick-add="openCreateDialog"
    />
    <KanbanView
      v-else-if="activeView?.config.viewType === 'board'"
      :tasks="visibleTasks"
      :loading="loading"
      :editable="editable()"
      :card-properties="cardProperties"
      :group-by-id="activeView?.config.groupById"
      :card-property-ids="activeView?.config.cardPropertyIds"
      :comment-counts="commentCounts"
      :member-map="memberMap"
      @open="openDetail"
      @moved="handleCardMoved"
      @quick-add="openCreateDialog"
      @group-change="handleGroupChange"
      @card-properties-change="handleCardPropertiesChange"
    />
    <GalleryView
      v-else-if="activeView?.config.viewType === 'gallery'"
      :tasks="visibleTasks"
      :loading="loading"
      @open="openDetail"
    />
    <CalendarView
      v-else-if="activeView?.config.viewType === 'calendar'"
      :tasks="visibleTasks"
      :loading="loading"
      :card-properties="cardProperties"
      :date-id="activeView?.config.dateId"
      @open="openDetail"
      @date-change="handleDateChange"
    />

    <!-- 快速新建任务（属性模板管理的唯一入口） -->
    <TaskDialog
      v-model="createDialogVisible"
      :project-id="projectId()"
      :task="null"
      :card-properties="cardProperties"
      :can-manage-properties="manageProperties()"
      :initial-properties="createInitialProperties"
      :member-map="memberMap"
      @saved="loadTasks"
      @properties-changed="loadProject"
    />

    <!-- 卡片详情与评论 -->
    <TaskDetailDialog
      v-model="detailDialogVisible"
      :task="detailTask"
      :project-id="projectId()"
      :card-properties="cardProperties"
      :editable="editable()"
      :commentable="commentable()"
      :current-user-id="userStore.user?.id"
      :member-map="memberMap"
      :members="members"
      @saved="handleDetailSaved"
    />

    <MemberDialog
      v-model="memberDialogVisible"
      :project-id="projectId()"
      :owner-id="projectStore.currentProject?.ownerId"
    />

    <ShareDialog v-model="shareDialogVisible" :project-id="projectId()" />

    <CalendarSubscribeDialog v-model="calendarSubscribeDialogVisible" :project-id="projectId()" />
  </div>
</template>

<style scoped>
.page-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.page-title-icon {
  font-size: 22px;
  line-height: 1;
}

.project-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.header-actions {
  display: flex;
  gap: 8px;
}

.header-control-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  color: var(--app-text-regular);
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.header-control-btn:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.meta-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.readonly-alert {
  margin-bottom: 12px;
}
</style>
