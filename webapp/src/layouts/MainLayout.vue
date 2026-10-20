<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, type InputInstance } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { convertProjectToTemplate, copyProject } from '@/api/project'
import { createInvite } from '@/api/auth'
import { useSiteStore } from '@/stores/site'
import {
  Bell,
  Box,
  Collection,
  CopyDocument,
  Delete,
  Edit,
  Expand,
  FolderAdd,
  Fold,
  Grid,
  Lock,
  Moon,
  MoreFilled,
  Plus,
  Promotion,
  Search,
  Setting,
  Sunny,
} from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { setLocale, type AppLocale } from '@/i18n'
import { isDark, toggleTheme } from '@/utils/theme'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import { useProjectStore } from '@/stores/project'
import { useCategoryStore } from '@/stores/category'
import { isAdmin } from '@/utils/permission'
import ProjectDialog from '@/components/ProjectDialog.vue'
import TemplateSelectorDialog from '@/components/TemplateSelectorDialog.vue'
import SearchDialog from '@/components/SearchDialog.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import type { InviteVO, NotificationVO, ProjectVO } from '@/types'

const { t, locale } = useI18n()
const userStore = useUserStore()
const notificationStore = useNotificationStore()
const siteStore = useSiteStore()
const projectStore = useProjectStore()
const categoryStore = useCategoryStore()
const route = useRoute()
const router = useRouter()

const currentLocale = computed(() => locale.value as AppLocale)

/** 侧边栏状态：折叠与项目搜索 */
const asideCollapsed = ref(false)
const projectKeyword = ref('')
const projectDialogVisible = ref(false)
/** 项目模板选择弹窗（新建项目入口） */
const templateSelectorVisible = ref(false)
const searchDialogVisible = ref(false)
/** 项目编辑弹窗状态（侧边栏项目「···」菜单 → 编辑项目） */
const editProjectDialogVisible = ref(false)
const editingProject = ref<ProjectVO | null>(null)
/** 修改密码弹窗状态（顶栏用户菜单入口） */
const changePasswordVisible = ref(false)
/** 邀请加入弹窗状态（顶栏用户菜单入口） */
const inviteDialogVisible = ref(false)
const inviteCreating = ref(false)
const invite = ref<InviteVO | null>(null)

/** 通知面板展开状态（顶栏铃铛） */
const notificationPanelVisible = ref(false)

/** 新建分类弹窗状态 */
const categoryDialogVisible = ref(false)
const categoryName = ref('')
const categoryCreating = ref(false)

/** 分类名称内联编辑状态（双击分类标题进入） */
const editingCategoryId = ref<number | null>(null)
const editingCategoryName = ref('')
const categorySaving = ref(false)
const categoryEditInput = ref<InputInstance>()

/** 侧边栏分类分组：分类名 + 是否当前用户创建（决定是否展示重命名/删除菜单） */
interface SidebarCategoryGroup {
  id: number
  name: string
  owned: boolean
  projects: ProjectVO[]
}

/** 侧边栏项目分组：未分类 + 分类分组（空分类也展示，含搜索过滤） */
const groupedProjects = computed(() => {
  const kw = projectKeyword.value.trim().toLowerCase()
  const list = kw
    ? projectStore.projects.filter((p) => p.name.toLowerCase().includes(kw))
    : projectStore.projects
  const uncategorized: ProjectVO[] = []
  const byCategory = new Map<number, ProjectVO[]>()
  for (const p of list) {
    if (p.categoryId) {
      const arr = byCategory.get(p.categoryId) ?? []
      arr.push(p)
      byCategory.set(p.categoryId, arr)
    } else {
      uncategorized.push(p)
    }
  }
  // 当前用户的分类始终展示（含无项目的空分类）；搜索时仅保留有匹配项目的分组
  const categories: SidebarCategoryGroup[] = []
  for (const category of categoryStore.categories) {
    const projects = byCategory.get(category.id) ?? []
    if (kw && !projects.length) continue
    categories.push({ id: category.id, name: category.name, owned: true, projects })
    byCategory.delete(category.id)
  }
  // 兜底：分类不在当前用户分类列表（如他人分类）时仍按原分组展示，避免项目从侧边栏消失
  for (const [id, projects] of byCategory) {
    categories.push({ id, name: '', owned: false, projects })
  }
  return { uncategorized, categories }
})

/** 分类名（优先分类列表，缺失时用项目自带名称兜底） */
function categoryNameOf(id: number): string {
  return categoryStore.categories.find((c) => c.id === id)?.name ?? ''
}

/** 是否命中项目搜索关键词（用于无结果提示） */
const hasSearchKeyword = computed(() => projectKeyword.value.trim().length > 0)

onMounted(async () => {
  // 刷新页面后恢复用户信息（token 已在 localStorage）
  if (!userStore.user) {
    try {
      await userStore.fetchMe()
    } catch {
      return
    }
  }
  await projectStore.fetchProjects()
  try {
    await categoryStore.fetchCategories()
  } catch {
    // 分类列表加载失败不影响主流程
  }
  notificationStore.startPolling()
  window.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

/** Ctrl+K / Cmd+K 打开全局搜索 */
function handleGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchDialogVisible.value = true
  }
}

function goToProject(id: number) {
  router.push({ name: 'project-detail', params: { id } })
}

function handleMenuSelect(key: string) {
  if (key === 'dashboard') {
    router.push({ name: 'dashboard' })
  } else if (key.startsWith('project-')) {
    goToProject(Number(key.slice(8)))
  }
}

function handleLangChange(lang: AppLocale) {
  setLocale(lang)
}

async function handleLogout() {
  notificationStore.stopPolling()
  await userStore.logout()
  appMessage.success(t('layout.logoutSuccess'))
  router.push({ name: 'login' })
}

/** 铃铛面板展开时拉取通知列表 */
function handleNotifyPanelShow() {
  void notificationStore.fetchList().catch(() => {
    // 拉取失败保持面板展开，错误提示由请求拦截器统一弹出
  })
}

/** 点击通知：标记已读并跳转项目页打开对应任务详情（复用既有 ?task= 深链接） */
async function handleNotificationClick(item: NotificationVO) {
  notificationPanelVisible.value = false
  if (!item.isRead) {
    try {
      await notificationStore.markRead(item)
    } catch {
      // 已读标记失败不阻断跳转
    }
  }
  router.push({
    name: 'project-detail',
    params: { id: item.projectId },
    query: { task: item.taskId },
  })
}

/** 相对时间展示：一分钟内/小时/天数，超过 7 天回退日期 */
function formatRelativeTime(value?: string): string {
  if (!value) return '—'
  const time = new Date(value.replace(' ', 'T')).getTime()
  const diffMs = Date.now() - time
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 1) return t('notification.timeJustNow')
  if (minutes < 60) return t('notification.timeMinutesAgo', { n: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('notification.timeHoursAgo', { n: hours })
  const days = Math.floor(hours / 24)
  if (days < 7) return t('notification.timeDaysAgo', { n: days })
  return value.slice(0, 10)
}

/** 用户菜单命令：邀请加入 / 修改密码 / 明暗主题切换 / 管理后台（仅 admin 可见） / 退出登录 */
function handleUserMenuCommand(command: string) {
  if (command === 'invite') {
    handleOpenInvite()
  } else if (command === 'changePassword') {
    changePasswordVisible.value = true
  } else if (command === 'toggleTheme') {
    toggleTheme()
  } else if (command === 'admin') {
    router.push({ name: 'admin-users' })
  } else if (command === 'logout') {
    handleLogout()
  }
}

/** 打开邀请弹窗并生成一次性注册邀请链接 */
async function handleOpenInvite() {
  inviteDialogVisible.value = true
  inviteCreating.value = true
  try {
    invite.value = await createInvite()
  } catch {
    // 生成失败时关闭弹窗，错误提示由请求拦截器统一弹出
    inviteDialogVisible.value = false
  } finally {
    inviteCreating.value = false
  }
}

/** 复制邀请链接到剪贴板 */
async function handleCopyInvite() {
  if (!invite.value?.inviteUrl) return
  try {
    await navigator.clipboard.writeText(invite.value.inviteUrl)
    appMessage.success(t('invite.copied'))
  } catch {
    appMessage.error(t('invite.copyFailed'))
  }
}

/** 侧边栏新建项目：模板/空白项目创建时 store 已刷新列表，直接进入项目 */
function handleProjectCreated(id: number) {
  projectDialogVisible.value = false
  router.push({ name: 'project-detail', params: { id } })
}

/** 侧边栏设置菜单命令：新建项目（模板库） / 新建分类 */
function handleSettingsCommand(command: string) {
  if (command === 'newProject') {
    templateSelectorVisible.value = true
  } else if (command === 'newCategory') {
    categoryName.value = ''
    categoryDialogVisible.value = true
  }
}

/** 模板选择器「创建空白项目」：关闭后打开空白项目表单 */
function handleTemplateBlank() {
  projectDialogVisible.value = true
}

/** 新建分类：名称必填，成功后刷新分类列表（错误提示由请求拦截器统一弹出） */
async function handleCreateCategory() {
  const name = categoryName.value.trim()
  if (!name) {
    appMessage.warning(t('validation.categoryNameRequired'))
    return
  }
  categoryCreating.value = true
  try {
    await categoryStore.createCategory(name)
    appMessage.success(t('layout.categoryCreated'))
    categoryDialogVisible.value = false
    categoryName.value = ''
  } finally {
    categoryCreating.value = false
  }
}

/** 双击分类名进入编辑态 */
async function startEditCategory(id: number) {
  editingCategoryId.value = id
  editingCategoryName.value = categoryNameOf(id)
  await nextTick()
  categoryEditInput.value?.focus()
}

/** 保存改名：回车/失焦触发；重名或空名时保持编辑态（错误提示由请求拦截器统一弹出） */
async function saveEditCategory() {
  const id = editingCategoryId.value
  if (id == null || categorySaving.value) return
  const name = editingCategoryName.value.trim()
  if (!name) {
    appMessage.warning(t('validation.categoryNameRequired'))
    return
  }
  if (name === categoryNameOf(id)) {
    editingCategoryId.value = null
    return
  }
  categorySaving.value = true
  try {
    await categoryStore.updateCategory(id, name)
    appMessage.success(t('layout.categoryRenamed'))
    editingCategoryId.value = null
  } catch {
    // 保存失败保持编辑态，方便用户修改后重试
  } finally {
    categorySaving.value = false
  }
}

/** 取消编辑：Esc */
function cancelEditCategory() {
  editingCategoryId.value = null
}

/** 分类菜单命令：重命名 / 删除分类（菜单仅创建者可见） */
async function handleCategoryMenuCommand(command: string, id: number) {
  if (command === 'rename') {
    startEditCategory(id)
    return
  }
  if (command !== 'delete') return
  const name = categoryNameOf(id)
  try {
    await ElMessageBox.confirm(
      t('layout.deleteCategoryConfirm', { name }),
      t('layout.deleteCategory'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await categoryStore.removeCategory(id)
  // 后端已将该分类下的项目置为未分类，刷新项目列表同步侧边栏分组
  await projectStore.fetchProjects()
  appMessage.success(t('layout.categoryDeleted'))
}

/** 项目项「···」菜单命令：编辑项目 / 复制项目 / 转为模板 / 归档项目 / 删除项目（菜单仅管理员可见），删除当前所在项目后跳回总览 */
async function handleProjectMenuCommand(command: string, project: ProjectVO) {
  if (command === 'edit') {
    editingProject.value = project
    editProjectDialogVisible.value = true
    return
  }
  if (command === 'copy') {
    await handleCopyProject(project)
    return
  }
  if (command === 'toTemplate') {
    await handleConvertToTemplate(project)
    return
  }
  if (command === 'archive') {
    await handleArchiveProject(project)
    return
  }
  if (command !== 'delete') return
  try {
    await ElMessageBox.confirm(
      t('dashboard.deleteConfirm', { name: project.name }),
      t('dashboard.deleteProject'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await projectStore.removeProject(project.id)
  appMessage.success(t('dashboard.deleted'))
  if (route.name === 'project-detail' && Number(route.params.id) === project.id) {
    router.push({ name: 'dashboard' })
  }
}

/** 将项目转为模板：复制项目结构到模板库，原项目不受影响 */
async function handleConvertToTemplate(project: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      t('template.convertConfirm', { name: project.name }),
      t('template.convertToTemplate'),
      {
        type: 'info',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await convertProjectToTemplate(project.id)
  appMessage.success(t('template.converted'))
}

/** 复制项目：确认新名称后克隆项目内容，刷新侧边栏列表并跳转到新项目 */
async function handleCopyProject(project: ProjectVO) {
  let name: string
  try {
    const result = await ElMessageBox.prompt(
      t('dashboard.copyPrompt'),
      t('dashboard.copyProject'),
      {
        inputValue: `${project.name}${t('dashboard.copySuffix')}`,
        inputPlaceholder: t('dashboard.copyPrompt'),
        inputValidator: (value: string) =>
          value.trim() ? true : t('validation.projectNameRequired'),
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
    name = result.value.trim()
  } catch {
    return
  }
  const copy = await copyProject(project.id, { name })
  await projectStore.fetchProjects()
  appMessage.success(t('dashboard.copied'))
  router.push({ name: 'project-detail', params: { id: copy.id } })
}

/** 归档项目：确认后从侧边栏移除，仅管理后台可见；归档当前所在项目后跳回总览 */
async function handleArchiveProject(project: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      t('dashboard.archiveConfirm', { name: project.name }),
      t('dashboard.archiveProject'),
      {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await projectStore.archiveProject(project.id)
  appMessage.success(t('dashboard.archived'))
  if (route.name === 'project-detail' && Number(route.params.id) === project.id) {
    router.push({ name: 'dashboard' })
  }
}

/** 编辑项目保存回调：store 已刷新项目列表并同步当前项目上下文，仅需成功提示 */
function handleProjectEdited() {
  appMessage.success(t('dashboard.updated'))
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout-header">
      <router-link to="/" class="logo">
        <img :src="siteStore.logo || '/logo.svg'" alt="logo" class="logo-icon" />
        {{ siteStore.name || t('app.name') }}
      </router-link>
      <div class="header-right">
        <!-- 站内通知铃铛：未读数角标 + 点击展开通知面板 -->
        <el-popover
          v-model:visible="notificationPanelVisible"
          placement="bottom-end"
          :width="340"
          trigger="click"
          popper-class="notify-popover"
          @show="handleNotifyPanelShow"
        >
          <template #reference>
            <el-badge
              :value="notificationStore.unreadCount"
              :hidden="!notificationStore.unreadCount"
              :max="99"
              class="notify-badge"
            >
              <span class="notify-bell" :title="t('notification.title')">
                <el-icon :size="18"><Bell /></el-icon>
              </span>
            </el-badge>
          </template>
          <div class="notify-panel">
            <div class="notify-panel-header">
              <span>{{ t('notification.title') }}</span>
              <el-button
                v-if="notificationStore.unreadCount"
                link
                type="primary"
                size="small"
                @click="notificationStore.markAllRead()"
              >
                {{ t('notification.clearAll') }}
              </el-button>
            </div>
            <div v-if="notificationStore.list.length" class="notify-list">
              <div
                v-for="item in notificationStore.list"
                :key="item.id"
                class="notify-item"
                :class="{ unread: !item.isRead }"
                @click="handleNotificationClick(item)"
              >
                <div class="notify-item-title">
                  {{ t('notification.mentionText', { actor: item.actorName, task: item.taskTitle }) }}
                </div>
                <div v-if="item.snippet" class="notify-item-snippet">{{ item.snippet }}</div>
                <div class="notify-item-time">{{ formatRelativeTime(item.createAt) }}</div>
              </div>
            </div>
            <el-empty v-else :description="t('notification.empty')" :image-size="48" />
          </div>
        </el-popover>

        <!-- 语言切换：点击展开，避免悬停弹出菜单面板 -->
        <el-dropdown trigger="click" @command="handleLangChange">
          <span class="lang-btn" :title="t('layout.language')">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <path d="M3 12h18" />
              <path d="M12 3a13.5 13.5 0 0 1 0 18a13.5 13.5 0 0 1 0-18z" />
            </svg>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="zh-CN" :disabled="currentLocale === 'zh-CN'">
                简体中文
              </el-dropdown-item>
              <el-dropdown-item command="en-US" :disabled="currentLocale === 'en-US'">
                English
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-dropdown @command="handleUserMenuCommand">
          <span class="user-info">
            <el-avatar :size="28" class="user-avatar">
              {{ userStore.user?.nickname?.charAt(0) || userStore.user?.username?.charAt(0) || 'U' }}
            </el-avatar>
            <span class="user-name">
              {{ userStore.user?.nickname || userStore.user?.username || t('layout.notLoggedIn') }}
            </span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="invite" :icon="Promotion">
                {{ t('layout.inviteMember') }}
              </el-dropdown-item>
              <el-dropdown-item command="changePassword" :icon="Lock">
                {{ t('layout.changePassword') }}
              </el-dropdown-item>
              <el-dropdown-item command="toggleTheme" :icon="isDark ? Sunny : Moon">
                {{ t(isDark ? 'layout.lightMode' : 'layout.darkMode') }}
              </el-dropdown-item>
              <el-dropdown-item v-if="userStore.user?.role === 'admin'" command="admin" :icon="Setting">
                {{ t('layout.adminCenter') }}
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>{{ t('layout.logout') }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="layout-body">
      <el-aside :width="asideCollapsed ? '52px' : '220px'" class="layout-aside">
        <div class="aside-top">
          <span v-if="!asideCollapsed" class="aside-title">{{ t('layout.myProjects') }}</span>
          <el-button
            link
            :icon="asideCollapsed ? Expand : Fold"
            class="aside-collapse"
            @click="asideCollapsed = !asideCollapsed"
          />
        </div>

        <template v-if="!asideCollapsed">
          <el-input
            v-model="projectKeyword"
            :prefix-icon="Search"
            size="small"
            clearable
            :placeholder="t('layout.projectSearchPlaceholder')"
            class="aside-search"
          />

          <el-menu
            :default-active="route.name === 'project-detail' ? `project-${route.params.id}` : 'dashboard'"
            class="aside-menu"
            @select="handleMenuSelect"
          >
            <el-menu-item index="dashboard">
              <el-icon><Grid /></el-icon>
              <span>{{ t('layout.overview') }}</span>
            </el-menu-item>

            <!-- 未分类项目 -->
            <el-menu-item
              v-for="project in groupedProjects.uncategorized"
              :key="project.id"
              :index="`project-${project.id}`"
            >
              <span class="project-icon">{{ project.icon || '📋' }}</span>
              <span class="project-name">{{ project.name }}</span>
              <el-dropdown
                v-if="isAdmin(project)"
                trigger="click"
                class="project-more-wrap"
                @command="(cmd: string) => handleProjectMenuCommand(cmd, project)"
              >
                <span class="project-more" :title="t('common.more')" @click.stop>
                  <el-icon><MoreFilled /></el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit" :icon="Edit">
                      {{ t('projectDetail.editProject') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="copy" :icon="CopyDocument">
                      {{ t('dashboard.copyProject') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="toTemplate" :icon="Collection">
                      {{ t('template.convertToTemplate') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="archive" :icon="Box">
                      {{ t('dashboard.archiveProject') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" :icon="Delete">
                      {{ t('common.delete') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </el-menu-item>

            <!-- 按分类分组的项目（可折叠/展开，空分类同样展示） -->
            <el-sub-menu
              v-for="group in groupedProjects.categories"
              :key="group.id"
              :index="`category-${group.id}`"
            >
              <template #title>
                <el-input
                  v-if="editingCategoryId === group.id"
                  ref="categoryEditInput"
                  v-model="editingCategoryName"
                  size="small"
                  maxlength="50"
                  class="category-edit-input"
                  @click.stop
                  @keyup.enter="saveEditCategory"
                  @keyup.esc="cancelEditCategory"
                  @blur="saveEditCategory"
                />
                <template v-else>
                  <span
                    class="category-name"
                    :title="group.owned ? t('layout.renameCategoryTip') : undefined"
                    @dblclick.stop="group.owned && startEditCategory(group.id)"
                  >
                    {{ group.name }}
                  </span>
                  <el-dropdown
                    v-if="group.owned"
                    trigger="click"
                    class="project-more-wrap"
                    @command="(cmd: string) => handleCategoryMenuCommand(cmd, group.id)"
                  >
                    <span class="project-more" :title="t('common.more')" @click.stop>
                      <el-icon><MoreFilled /></el-icon>
                    </span>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="rename" :icon="Edit">
                          {{ t('layout.renameCategory') }}
                        </el-dropdown-item>
                        <el-dropdown-item command="delete" :icon="Delete">
                          {{ t('layout.deleteCategory') }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
              </template>
              <el-menu-item
                v-for="project in group.projects"
                :key="project.id"
                :index="`project-${project.id}`"
              >
                <span class="project-icon">{{ project.icon || '📋' }}</span>
                <span class="project-name">{{ project.name }}</span>
                <el-dropdown
                  v-if="isAdmin(project)"
                  trigger="click"
                  class="project-more-wrap"
                  @command="(cmd: string) => handleProjectMenuCommand(cmd, project)"
                >
                  <span class="project-more" :title="t('common.more')" @click.stop>
                    <el-icon><MoreFilled /></el-icon>
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit" :icon="Edit">
                        {{ t('projectDetail.editProject') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="copy" :icon="CopyDocument">
                        {{ t('dashboard.copyProject') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="toTemplate" :icon="Collection">
                        {{ t('template.convertToTemplate') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="archive" :icon="Box">
                        {{ t('dashboard.archiveProject') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" :icon="Delete">
                        {{ t('common.delete') }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </el-menu-item>
              <!-- 空分类占位提示：新建后立即可见，避免展开后一片空白 -->
              <li v-if="!group.projects.length" class="category-empty">
                {{ t('layout.categoryEmpty') }}
              </li>
            </el-sub-menu>
          </el-menu>
          <el-empty
            v-if="groupedProjects.uncategorized.length === 0 && groupedProjects.categories.length === 0 && hasSearchKeyword"
            :description="t('layout.noMatchedProject')"
            :image-size="40"
          />
        </template>

        <!-- 侧边栏页脚：设置菜单（折叠时也可见） -->
        <div class="aside-footer" :class="{ collapsed: asideCollapsed }">
          <el-dropdown trigger="click" @command="handleSettingsCommand">
            <span class="aside-settings-btn" :title="t('layout.settings')">
              <el-icon :size="18"><Setting /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="newProject" :icon="Plus">
                  {{ t('layout.newProject') }}
                </el-dropdown-item>
                <el-dropdown-item command="newCategory" :icon="FolderAdd">
                  {{ t('layout.newCategory') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-aside>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <ProjectDialog v-model="projectDialogVisible" @created="handleProjectCreated" />

  <!-- 项目模板选择弹窗（新建项目入口：模板库 / 空白项目） -->
  <TemplateSelectorDialog v-model="templateSelectorVisible" @created="handleProjectCreated" @blank="handleTemplateBlank" />

  <!-- 项目编辑弹窗（侧边栏「···」菜单入口） -->
  <ProjectDialog
    v-model="editProjectDialogVisible"
    :project="editingProject"
    @updated="handleProjectEdited"
  />

  <!-- 新建分类弹窗 -->
  <el-dialog
    v-model="categoryDialogVisible"
    :title="t('layout.newCategory')"
    width="420px"
    :close-on-click-modal="false"
  >
    <el-form @submit.prevent>
      <el-form-item :label="t('layout.categoryNameLabel')">
        <el-input
          v-model="categoryName"
          maxlength="50"
          :placeholder="t('layout.categoryNamePlaceholder')"
          @keyup.enter="handleCreateCategory"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="categoryDialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="categoryCreating" @click="handleCreateCategory">
        {{ t('common.create') }}
      </el-button>
    </template>
  </el-dialog>

  <SearchDialog v-model="searchDialogVisible" />

  <!-- 修改密码弹窗（顶栏用户菜单入口） -->
<ChangePasswordDialog v-model="changePasswordVisible" />

  <!-- 邀请加入弹窗（顶栏用户菜单入口：生成一次性注册邀请链接） -->
  <el-dialog
    v-model="inviteDialogVisible"
    :title="t('invite.title')"
    width="560px"
    :close-on-click-modal="false"
  >
    <div v-loading="inviteCreating" class="invite-body">
      <el-form label-width="90px">
        <el-form-item :label="t('invite.linkLabel')">
          <el-input :model-value="invite?.inviteUrl" readonly class="invite-link-input">
            <template #append>
              <el-button :icon="CopyDocument" @click="handleCopyInvite">{{ t('invite.copy') }}</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <p class="invite-note">{{ t('invite.note') }}</p>
    </div>
    <template #footer>
      <el-button @click="inviteDialogVisible = false">{{ t('common.cancel') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.layout {
  height: 100%;
}

/* 头部下方区域占满剩余高度，避免内容超高时撑破 100% 高度导致整页滚动 */
.layout-body {
  flex: 1;
  min-height: 0;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--app-header-bg);
  color: #fff;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
}

.logo-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

/* 语言切换：顶部图标按钮，悬停动效与设置齿轮一致 */
.lang-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  color: #fff;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.lang-btn:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

/* 通知铃铛：头部白色图标，未读时右上角红色角标 */
.notify-badge {
  line-height: 1;
}

.notify-bell {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  color: #fff;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.notify-bell:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.notify-panel {
  max-height: 420px;
  display: flex;
  flex-direction: column;
}

.notify-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--app-border-light);
  font-weight: 600;
  color: var(--app-text-primary);
}

.notify-list {
  overflow-y: auto;
}

.notify-item {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}

.notify-item:hover {
  background: var(--app-fill);
}

.notify-item-title {
  font-size: 13px;
  line-height: 1.5;
  color: var(--app-text-regular);
}

.notify-item.unread .notify-item-title {
  font-weight: 600;
  color: var(--app-text-primary);
}

.notify-item-snippet {
  margin-top: 2px;
  font-size: 12px;
  color: var(--app-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notify-item-time {
  margin-top: 2px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #fff;
}

.user-avatar {
  background: var(--el-color-primary);
  color: #fff;
}

.user-name {
  font-size: 14px;
}

.layout-aside {
  display: flex;
  flex-direction: column;
  background: var(--app-surface);
  border-right: 1px solid var(--app-border-regular);
  transition: width 0.2s;
  overflow: hidden;
}

.aside-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 10px 6px 18px;
}

.aside-title {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.aside-collapse {
  color: var(--app-text-secondary);
}

.aside-search {
  /* el-input 默认宽度 100%，用内边距留白避免 margin 撑破侧边栏被右边缘遮盖 */
  margin-bottom: 6px;
  padding: 0 10px;
}

.project-icon {
  margin-right: 6px;
  font-size: 13px;
}

.category-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 分组标题：比项目文字小一号的粗体，左对齐到项目图标列 */
  font-size: 12px;
  font-weight: 600;
  cursor: default;
}

.category-edit-input {
  flex: 1;
  min-width: 0;
}

.aside-menu {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-right: none;
  /* 紧凑列表：小号字体与行高，减小项间距 */
  --el-menu-item-height: 34px;
  --el-menu-sub-item-height: 32px;
  --el-menu-item-font-size: 13px;
  --el-menu-base-level-padding: 12px;
  --el-menu-level-padding: 14px;
  /* 未选中项弱化为次要文字色，悬停时提亮 */
  --el-menu-text-color: var(--app-text-secondary);
  --el-menu-hover-text-color: var(--app-text-primary);
  --el-menu-hover-bg-color: var(--app-fill);
  padding: 2px 0;
}

.aside-menu :deep(.el-menu-item),
.aside-menu :deep(.el-sub-menu__title) {
  margin: 1px 6px;
  border-radius: 6px;
}

/* 空分类占位提示：缩进与分类下项目名一致 */
.category-empty {
  list-style: none;
  padding: 4px 12px 6px 50px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--app-text-secondary);
}

/* 选中项：浅色底 + 主色文字，与弱化的未选中项形成对比 */
.aside-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

.project-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-more-wrap {
  display: flex;
  align-items: center;
}

.project-more {
  /* 默认隐藏，悬停在项目项（或按钮本身）上时显示 */
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 4px;
  color: var(--app-text-secondary);
  cursor: pointer;
  opacity: 0;
  transition: background 0.2s, color 0.2s, opacity 0.15s;
}

:deep(.el-menu-item:hover) .project-more,
:deep(.el-sub-menu__title:hover) .project-more,
.project-more-wrap:hover .project-more {
  opacity: 1;
}

.project-more:hover {
  background: var(--app-fill);
  color: var(--app-text-primary);
}

.aside-action {
  padding: 12px 16px;
  border-top: 1px solid var(--app-border-light);
}

.aside-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 8px 12px;
  border-top: 1px solid var(--app-border-light);
}

.aside-footer.collapsed {
  justify-content: center;
  padding: 12px 10px;
}

.aside-settings-btn {
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

.aside-settings-btn:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.invite-link-input :deep(.el-input__inner) {
  color: var(--app-text-regular);
}

.invite-note {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}

.layout-main {
  background: var(--app-page-bg);
  overflow-y: auto;
}
</style>
