<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import {
  cleanupDeletedProjects,
  deleteArchivedProject,
  listArchivedProjects,
  restoreArchivedProject,
  statDeletedProjects,
} from '@/api/admin'
import type { ProjectVO } from '@/types'

const { t } = useI18n()

/** 列表查询状态 */
const loading = ref(false)
const projects = ref<ProjectVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

async function loadProjects() {
  loading.value = true
  try {
    const result = await listArchivedProjects({
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
    })
    projects.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadProjects()
}

function handleSizeChange(size: number) {
  query.size = size
  query.page = 1
  loadProjects()
}

function handlePageChange(page: number) {
  query.page = page
  loadProjects()
}

/** 恢复归档项目：恢复正常状态，重新出现在成员侧边栏 */
async function handleRestore(project: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      t('adminArchivedProjects.restoreConfirm', { name: project.name }),
      t('adminArchivedProjects.restore'),
      {
        type: 'info',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await restoreArchivedProject(project.id)
  appMessage.success(t('adminArchivedProjects.restored'))
  if (projects.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  loadProjects()
}

/** 删除归档项目：软删除，不可恢复 */
async function handleDelete(project: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      t('adminArchivedProjects.deleteConfirm', { name: project.name }),
      t('adminArchivedProjects.delete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await deleteArchivedProject(project.id)
  appMessage.success(t('adminArchivedProjects.deleted'))
  if (projects.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  loadProjects()
}

/** 物理清理已删除项目：选择日期 → 预览影响范围 → 确认后级联删除 */
const cleanupDate = ref('')
const cleaning = ref(false)

async function handleCleanup() {
  if (!cleanupDate.value) {
    appMessage.warning(t('adminArchivedProjects.cleanupDateRequired'))
    return
  }
  const before = cleanupDate.value
  const stat = await statDeletedProjects(before)
  if (stat.projects === 0) {
    appMessage.info(t('adminArchivedProjects.cleanupEmpty'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('adminArchivedProjects.cleanupConfirm', {
        projects: stat.projects,
        blocks: stat.blocks,
        attachments: stat.attachments,
        members: stat.members,
        notifications: stat.notifications,
      }),
      t('adminArchivedProjects.cleanupConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  cleaning.value = true
  try {
    const result = await cleanupDeletedProjects(before)
    appMessage.success(
      t('adminArchivedProjects.cleanupSuccess', {
        projects: result.projects,
        blocks: result.blocks,
        attachments: result.attachments,
        attachmentFiles: result.attachmentFiles,
        members: result.members,
        notifications: result.notifications,
      }),
    )
    cleanupDate.value = ''
    loadProjects()
  } finally {
    cleaning.value = false
  }
}

onMounted(loadProjects)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ t('adminArchivedProjects.title') }}</h2>
    </div>

    <el-card shadow="never" class="admin-card cleanup-card">
      <div class="cleanup-header">
        <h3>{{ t('adminArchivedProjects.cleanupTitle') }}</h3>
        <p class="cleanup-desc">{{ t('adminArchivedProjects.cleanupDesc') }}</p>
      </div>
      <div class="cleanup-actions">
        <el-date-picker
          v-model="cleanupDate"
          type="date"
          value-format="YYYY-MM-DD"
          :placeholder="t('adminArchivedProjects.cleanupDatePlaceholder')"
          :disabled-date="(d: Date) => d.getTime() > Date.now()"
        />
        <el-button type="danger" :loading="cleaning" @click="handleCleanup">
          {{ t('adminArchivedProjects.cleanup') }}
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="admin-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :prefix-icon="Search"
          :placeholder="t('adminArchivedProjects.searchPlaceholder')"
          clearable
          class="toolbar-search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
      </div>

      <el-table v-loading="loading" :data="projects" stripe>
        <el-table-column :label="t('adminArchivedProjects.icon')" width="70" align="center">
          <template #default="{ row }">
            <span class="project-icon">{{ (row as ProjectVO).icon || '📋' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('adminArchivedProjects.name')" min-width="200" />
        <el-table-column prop="ownerName" :label="t('adminArchivedProjects.owner')" width="140">
          <template #default="{ row }">
            <span v-if="(row as ProjectVO).ownerName">{{ (row as ProjectVO).ownerName }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('adminArchivedProjects.type')" width="100" align="center">
          <template #default="{ row }">
            {{ (row as ProjectVO).type === 'O' ? t('projectDialog.typeOpen') : t('projectDialog.typePrivate') }}
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="t('adminArchivedProjects.updatedAt')" width="170" />
        <el-table-column :label="t('adminArchivedProjects.actions')" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleRestore(row as ProjectVO)">
              {{ t('adminArchivedProjects.restore') }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row as ProjectVO)">
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.admin-card {
  border-radius: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.toolbar-search {
  width: 260px;
}

.cleanup-card {
  margin-bottom: 16px;
}

.cleanup-header h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.cleanup-desc {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.cleanup-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.project-icon {
  font-size: 20px;
  line-height: 1;
}

.text-muted {
  color: var(--app-text-secondary);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
