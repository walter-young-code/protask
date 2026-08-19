<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import {
  createAdminTeam,
  deleteAdminTeam,
  listAdminTeams,
  updateAdminTeam,
} from '@/api/admin'
import type { TeamVO } from '@/types'

const { t } = useI18n()

/** 列表查询状态 */
const loading = ref(false)
const teams = ref<TeamVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

/** 新建/重命名团队弹窗（复用同一表单） */
const dialogVisible = ref(false)
const dialogTitle = ref('')
const editingTeam = ref<TeamVO | null>(null)
const teamName = ref('')
const saving = ref(false)

async function loadTeams() {
  loading.value = true
  try {
    const result = await listAdminTeams({
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
    })
    teams.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadTeams()
}

function handleSizeChange(size: number) {
  query.size = size
  query.page = 1
  loadTeams()
}

function handlePageChange(page: number) {
  query.page = page
  loadTeams()
}

function openCreate() {
  editingTeam.value = null
  teamName.value = ''
  dialogTitle.value = t('adminTeams.createTeam')
  dialogVisible.value = true
}

function openRename(team: TeamVO) {
  editingTeam.value = team
  teamName.value = team.name
  dialogTitle.value = t('adminTeams.renameTeam')
  dialogVisible.value = true
}

async function handleSave() {
  const name = teamName.value.trim()
  if (!name) {
    appMessage.warning(t('adminTeams.nameRequired'))
    return
  }
  if (saving.value) return
  saving.value = true
  try {
    if (editingTeam.value) {
      await updateAdminTeam(editingTeam.value.id, { name })
      appMessage.success(t('adminTeams.renamed'))
    } else {
      await createAdminTeam({ name })
      appMessage.success(t('adminTeams.created'))
    }
    dialogVisible.value = false
    loadTeams()
  } catch {
    // 保存失败保持弹窗，方便修改后重试（错误提示由请求拦截器统一弹出）
  } finally {
    saving.value = false
  }
}

/** 删除团队：成员数 > 0 时提示将变为未分配 */
async function handleDelete(team: TeamVO) {
  try {
    await ElMessageBox.confirm(
      team.memberCount > 0
        ? t('adminTeams.deleteConfirmWithMembers', { name: team.name, count: team.memberCount })
        : t('adminTeams.deleteConfirm', { name: team.name }),
      t('adminTeams.deleteTeam'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await deleteAdminTeam(team.id)
  appMessage.success(t('adminTeams.deleted'))
  loadTeams()
}

onMounted(loadTeams)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ t('adminTeams.title') }}</h2>
      <el-button type="primary" :icon="Plus" @click="openCreate">
        {{ t('adminTeams.createTeam') }}
      </el-button>
    </div>

    <el-card shadow="never" class="admin-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :prefix-icon="Search"
          :placeholder="t('adminTeams.searchPlaceholder')"
          clearable
          class="toolbar-search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
      </div>

      <el-table v-loading="loading" :data="teams" stripe>
        <el-table-column prop="name" :label="t('adminTeams.name')" min-width="200" />
        <el-table-column prop="memberCount" :label="t('adminTeams.memberCount')" width="120" />
        <el-table-column prop="createdAt" :label="t('adminTeams.createdAt')" width="170" />
        <el-table-column :label="t('adminTeams.actions')" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRename(row as TeamVO)">
              {{ t('common.edit') }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row as TeamVO)">
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

    <!-- 新建/重命名团队弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="420px"
      :close-on-click-modal="false"
      @closed="teamName = ''"
    >
      <el-form @submit.prevent>
        <el-form-item :label="t('adminTeams.nameLabel')">
          <el-input
            v-model="teamName"
            maxlength="50"
            :placeholder="t('adminTeams.namePlaceholder')"
            @keyup.enter="handleSave"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
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

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
