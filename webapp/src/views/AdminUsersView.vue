<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Key, Plus, Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import {
  createAdminUser,
  deleteAdminUser,
  listAdminUsers,
  listAllTeams,
  resetAdminUserPassword,
  updateAdminUser,
} from '@/api/admin'
import * as userApi from '@/api/user'
import { useUserStore } from '@/stores/user'
import type { AdminUserVO, TeamVO, UserVO } from '@/types'
import { passwordStrengthValidator } from '@/utils/passwordStrength'
import PasswordStrengthBar from '@/components/PasswordStrengthBar.vue'

const { t } = useI18n()
const userStore = useUserStore()

/** 列表查询状态 */
const loading = ref(false)
const users = ref<AdminUserVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', status: undefined as number | undefined, page: 1, size: 10 })

/** 团队下拉选项（全量） */
const teams = ref<TeamVO[]>([])

async function loadAllTeams() {
  teams.value = await listAllTeams()
}

/** 新建用户弹窗 */
const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  username: '',
  password: '',
  firstName: '',
  lastName: '',
  nickname: '',
  email: '',
  role: 'user',
  teamId: 0,
})
const createRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('validation.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('validation.usernameLength'), trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_]+$/,
      message: t('validation.usernamePattern'),
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: t('validation.passwordRequired'), trigger: 'blur' },
    { min: 8, max: 50, message: t('validation.passwordLength'), trigger: 'blur' },
    {
      validator: (rule: unknown, value: string, callback: (err?: Error) => void) =>
        passwordStrengthValidator(rule, value, callback, t('validation.passwordStrengthHint')),
      trigger: 'blur',
    },
  ],
}))

/** 编辑用户弹窗（自己不能修改角色/状态） */
const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editingUser = ref<AdminUserVO | null>(null)
const editForm = reactive({ firstName: '', lastName: '', nickname: '', email: '', role: 'user', status: 1, teamId: 0 })

/** 重置密码弹窗 */
const resetVisible = ref(false)
const resetFormRef = ref<FormInstance>()
const resettingUser = ref<AdminUserVO | null>(null)
const resetForm = reactive({ password: '', confirmPassword: '' })
const resetRules = computed<FormRules>(() => ({
  password: [
    { required: true, message: t('validation.passwordRequired'), trigger: 'blur' },
    { min: 8, max: 50, message: t('validation.passwordLength'), trigger: 'blur' },
    {
      validator: (rule: unknown, value: string, callback: (err?: Error) => void) =>
        passwordStrengthValidator(rule, value, callback, t('validation.passwordStrengthHint')),
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback) => {
        if (!value) {
          callback(new Error(t('validation.confirmPasswordRequired')))
        } else if (value !== resetForm.password) {
          callback(new Error(t('admin.passwordMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}))

const isSelf = (user: AdminUserVO | null) => user != null && user.id === userStore.user?.id

async function loadUsers() {
  loading.value = true
  try {
    const result = await listAdminUsers({
      keyword: query.keyword || undefined,
      status: query.status,
      page: query.page,
      size: query.size,
    })
    users.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadUsers()
}

function handleSizeChange(size: number) {
  query.size = size
  query.page = 1
  loadUsers()
}

function handlePageChange(page: number) {
  query.page = page
  loadUsers()
}

function openCreate() {
  createForm.username = ''
  createForm.password = ''
  createForm.firstName = ''
  createForm.lastName = ''
  createForm.nickname = ''
  createForm.email = ''
  createForm.role = 'user'
  createForm.teamId = 0
  createVisible.value = true
}

async function handleCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  await createAdminUser({
    username: createForm.username.trim(),
    password: createForm.password,
    firstName: createForm.firstName.trim() || undefined,
    lastName: createForm.lastName.trim() || undefined,
    nickname: createForm.nickname.trim() || undefined,
    email: createForm.email.trim() || undefined,
    role: createForm.role,
    teamId: createForm.teamId || undefined,
  })
  appMessage.success(t('admin.created'))
  createVisible.value = false
  loadUsers()
}

function openEdit(user: AdminUserVO) {
  editingUser.value = user
  editForm.firstName = user.firstName || ''
  editForm.lastName = user.lastName || ''
  editForm.nickname = user.nickname
  editForm.email = user.email || ''
  editForm.role = user.role
  editForm.status = user.status
  editForm.teamId = user.teamId ?? 0
  editVisible.value = true
}

async function handleEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const user = editingUser.value
  if (!user) return
  await updateAdminUser(user.id, {
    firstName: editForm.firstName.trim() || undefined,
    lastName: editForm.lastName.trim() || undefined,
    nickname: editForm.nickname.trim() || undefined,
    email: editForm.email.trim() || undefined,
    role: editForm.role,
    status: editForm.status,
    teamId: editForm.teamId,
  })
  appMessage.success(t('admin.updated'))
  editVisible.value = false
  // 若修改的是自己，同步更新会话中的用户信息
  if (isSelf(user)) {
    await userStore.fetchMe()
  }
  loadUsers()
}

/** 状态开关：乐观更新，确认取消或请求失败时回滚 */
async function handleStatusChange(user: AdminUserVO, value: number | string | boolean) {
  const targetStatus = value ? 1 : 0
  if (targetStatus === user.status) return
  const prevStatus = user.status
  user.status = targetStatus
  const action = targetStatus === 0 ? 'disable' : 'enable'
  try {
    await ElMessageBox.confirm(t(`admin.${action}Confirm`, { name: user.username }), t('admin.title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    user.status = prevStatus
    return
  }
  try {
    await updateAdminUser(user.id, { status: targetStatus })
    appMessage.success(t(`admin.${action}Success`))
  } catch {
    user.status = prevStatus
  }
}

function openReset(user: AdminUserVO) {
  resettingUser.value = user
  resetForm.password = ''
  resetForm.confirmPassword = ''
  resetVisible.value = true
}

async function handleReset() {
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) return
  await resetAdminUserPassword(resettingUser.value!.id, { password: resetForm.password })
  appMessage.success(t('admin.passwordReset'))
  resetVisible.value = false
}

/** 注销用户弹窗：可指定项目接管人（不指定时自动接管） */
const deleteVisible = ref(false)
const deletingUser = ref<AdminUserVO | null>(null)
const takeoverUserId = ref<number | null>(null)
const takeoverCandidates = ref<UserVO[]>([])

async function openDelete(user: AdminUserVO) {
  deletingUser.value = user
  takeoverUserId.value = null
  // 拉取启用状态用户作为接管人候选（已注销/禁用用户不可接管）
  takeoverCandidates.value = (await userApi.listUsers()).filter((u) => u.id !== user.id)
  deleteVisible.value = true
}

async function handleDelete() {
  const user = deletingUser.value
  if (!user) return
  await deleteAdminUser(user.id, { takeoverUserId: takeoverUserId.value ?? undefined })
  appMessage.success(t('admin.userDeleted'))
  deleteVisible.value = false
  // 删除当前页最后一条时回退一页
  if (users.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  loadUsers()
}

onMounted(() => {
  loadUsers()
  loadAllTeams()
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ t('admin.title') }}</h2>
      <el-button type="primary" :icon="Plus" @click="openCreate">
        {{ t('admin.createUser') }}
      </el-button>
    </div>

    <el-card shadow="never" class="admin-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :prefix-icon="Search"
          :placeholder="t('admin.searchPlaceholder')"
          clearable
          class="toolbar-search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.status"
          :placeholder="t('admin.statusAll')"
          clearable
          class="toolbar-status"
          @change="handleSearch"
        >
          <el-option :label="t('admin.statusEnabled')" :value="1" />
          <el-option :label="t('admin.statusDisabled')" :value="0" />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="users" stripe>
        <el-table-column prop="username" :label="t('admin.username')" min-width="120" />
        <el-table-column :label="t('admin.fullName')" min-width="130">
          <template #default="{ row }">{{ (row as AdminUserVO).fullName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="nickname" :label="t('admin.nickname')" min-width="120">
          <template #default="{ row }">{{ (row as AdminUserVO).nickname || '—' }}</template>
        </el-table-column>
        <el-table-column prop="email" :label="t('admin.email')" min-width="160">
          <template #default="{ row }">{{ (row as AdminUserVO).email || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('admin.team')" min-width="120">
          <template #default="{ row }">
            <span v-if="(row as AdminUserVO).teamName">{{ (row as AdminUserVO).teamName }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.role')" width="110">
          <template #default="{ row }">
            <el-tag :type="(row as AdminUserVO).role === 'admin' ? 'warning' : 'info'" size="small">
              {{ (row as AdminUserVO).role === 'admin' ? t('admin.roleAdmin') : t('admin.roleUser') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.status')" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="(row as AdminUserVO).status === 1"
              :disabled="isSelf(row as AdminUserVO)"
              @change="(value: number | string | boolean) => handleStatusChange(row as AdminUserVO, value)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('admin.createdAt')" width="170" />
        <el-table-column :label="t('admin.actions')" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row as AdminUserVO)">
              {{ t('common.edit') }}
            </el-button>
            <el-button link type="primary" size="small" :icon="Key" @click="openReset(row as AdminUserVO)">
              {{ t('admin.resetPassword') }}
            </el-button>
            <el-button link type="danger" size="small" @click="openDelete(row as AdminUserVO)">
              {{ t('admin.deleteUser') }}
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

    <!-- 新建用户弹窗 -->
    <el-dialog
      v-model="createVisible"
      :title="t('admin.createUser')"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="80px" @submit.prevent>
        <el-form-item :label="t('admin.username')" prop="username">
          <el-input v-model="createForm.username" maxlength="50" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item :label="t('admin.firstName')">
              <el-input v-model="createForm.firstName" maxlength="50" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('admin.lastName')">
              <el-input v-model="createForm.lastName" maxlength="50" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="t('admin.password')" prop="password">
          <el-input v-model="createForm.password" type="password" show-password maxlength="50" />
          <PasswordStrengthBar :password="createForm.password" />
        </el-form-item>
        <el-form-item :label="t('admin.nickname')" prop="nickname">
          <el-input v-model="createForm.nickname" maxlength="50" />
        </el-form-item>
        <el-form-item :label="t('admin.email')" prop="email">
          <el-input v-model="createForm.email" maxlength="100" />
        </el-form-item>
        <el-form-item :label="t('admin.role')" prop="role">
          <el-select v-model="createForm.role" class="dialog-select">
            <el-option :label="t('admin.roleUser')" value="user" />
            <el-option :label="t('admin.roleAdmin')" value="admin" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.team')">
          <el-select v-model="createForm.teamId" class="dialog-select">
            <el-option :label="t('admin.teamNone')" :value="0" />
            <el-option v-for="team in teams" :key="team.id" :label="team.name" :value="team.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleCreate">{{ t('common.create') }}</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户弹窗 -->
    <el-dialog
      v-model="editVisible"
      :title="t('admin.editUser')"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-form ref="editFormRef" :model="editForm" label-width="80px" @submit.prevent>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item :label="t('admin.firstName')">
              <el-input v-model="editForm.firstName" maxlength="50" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('admin.lastName')">
              <el-input v-model="editForm.lastName" maxlength="50" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="t('admin.nickname')">
          <el-input v-model="editForm.nickname" maxlength="50" />
        </el-form-item>
        <el-form-item :label="t('admin.email')">
          <el-input v-model="editForm.email" maxlength="100" />
        </el-form-item>
        <el-form-item :label="t('admin.role')">
          <el-select v-model="editForm.role" :disabled="isSelf(editingUser)" class="dialog-select">
            <el-option :label="t('admin.roleUser')" value="user" />
            <el-option :label="t('admin.roleAdmin')" value="admin" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.status')">
          <el-radio-group v-model="editForm.status" :disabled="isSelf(editingUser)">
            <el-radio :value="1">{{ t('admin.statusEnabled') }}</el-radio>
            <el-radio :value="0">{{ t('admin.statusDisabled') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('admin.team')">
          <el-select v-model="editForm.teamId" class="dialog-select">
            <el-option :label="t('admin.teamNone')" :value="0" />
            <el-option v-for="team in teams" :key="team.id" :label="team.name" :value="team.id" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="isSelf(editingUser)"
          type="info"
          :closable="false"
          :title="t('admin.selfLockedTip')"
        />
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleEdit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog
      v-model="resetVisible"
      :title="t('admin.resetPassword')"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-alert type="warning" :closable="false" :title="t('admin.resetPasswordTip')" class="reset-tip" />
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="80px" @submit.prevent>
        <el-form-item :label="t('admin.password')" prop="password">
          <el-input v-model="resetForm.password" type="password" show-password maxlength="50" />
          <PasswordStrengthBar :password="resetForm.password" />
        </el-form-item>
        <el-form-item :label="t('admin.confirmPassword')" prop="confirmPassword">
          <el-input v-model="resetForm.confirmPassword" type="password" show-password maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleReset">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 注销用户弹窗：可选指定项目接管人 -->
    <el-dialog
      v-model="deleteVisible"
      :title="t('admin.deleteUser')"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-alert type="warning" :closable="false" :title="t('admin.deleteUserAlert')" class="reset-tip" />
      <el-form label-width="80px" @submit.prevent>
        <el-form-item :label="t('admin.takeoverLabel')">
          <el-select
            v-model="takeoverUserId"
            filterable
            clearable
            :placeholder="t('admin.takeoverPlaceholder')"
            class="dialog-select"
          >
            <el-option
              v-for="user in takeoverCandidates"
              :key="user.id"
              :label="user.nickname || user.username"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
        <div class="takeover-tip">{{ t('admin.takeoverTip') }}</div>
      </el-form>
      <template #footer>
        <el-button @click="deleteVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" @click="handleDelete">{{ t('admin.deleteUser') }}</el-button>
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

.toolbar-status {
  width: 140px;
}

.text-muted {
  color: var(--app-text-secondary);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.dialog-select {
  width: 100%;
}

.reset-tip {
  margin-bottom: 16px;
}

.takeover-tip {
  padding: 0 0 4px 80px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}
</style>
