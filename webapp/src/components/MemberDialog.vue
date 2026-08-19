<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { useI18n } from 'vue-i18n'
import * as projectApi from '@/api/project'
import * as userApi from '@/api/user'
import type { ProjectMemberVO, ProjectRole, UserVO } from '@/types'

const { t } = useI18n()

const ROLES: ProjectRole[] = ['viewer', 'commenter', 'editor', 'admin']

const props = defineProps<{
  modelValue: boolean
  projectId: number
  /** 项目负责人ID，其角色不可变更/移除 */
  ownerId?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const loading = ref(false)
const members = ref<ProjectMemberVO[]>([])
const users = ref<UserVO[]>([])
const newUser = ref<number | null>(null)
const newRole = ref<ProjectRole>('viewer')
/** 用户选择框当前搜索关键字 */
const searchQuery = ref('')

async function loadMembers() {
  loading.value = true
  try {
    members.value = await projectApi.listMembers(props.projectId)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    searchQuery.value = ''
    await Promise.all([loadMembers(), loadUsers()])
  },
)

async function loadUsers() {
  users.value = await userApi.listUsers()
}

function handleUserFilter(query: string) {
  searchQuery.value = query
}

/** 候选用户：尚未加入项目，且命中搜索关键字（用户名/昵称/姓名/团队）；搜索栏为空时不展示 */
const filteredCandidates = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return []
  const memberIds = new Set(members.value.map((m) => m.userId))
  return users.value.filter((u) => {
    if (memberIds.has(u.id)) return false
    return [u.username, u.nickname, u.fullName, u.teamName].some((v) =>
      v?.toLowerCase().includes(q),
    )
  })
})

async function handleAdd() {
  if (!newUser.value) return
  await projectApi.addMember(props.projectId, { userId: newUser.value, role: newRole.value })
  appMessage.success(t('memberDialog.added'))
  newUser.value = null
  newRole.value = 'viewer'
  searchQuery.value = ''
  await loadMembers()
}

async function handleRoleChange(member: ProjectMemberVO, role: ProjectRole) {
  await projectApi.updateMemberRole(props.projectId, { userId: member.userId, role })
  appMessage.success(t('memberDialog.roleUpdated'))
  await loadMembers()
}

async function handleRemove(member: ProjectMemberVO) {
  try {
    await ElMessageBox.confirm(
      t('memberDialog.removeConfirm', { name: member.nickname || member.username }),
      t('memberDialog.removeTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await projectApi.removeMember(props.projectId, member.userId)
  appMessage.success(t('memberDialog.removed'))
  await loadMembers()
}

function isOwner(member: ProjectMemberVO) {
  return member.userId === props.ownerId
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('memberDialog.title')"
    width="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="add-member">
      <el-select
        v-model="newUser"
        filterable
        :filter-method="handleUserFilter"
        :placeholder="t('memberDialog.userPlaceholder')"
        class="user-select"
      >
        <el-option
          v-for="user in filteredCandidates"
          :key="user.id"
          :label="user.nickname || user.username"
          :value="user.id"
        >
          <div class="user-option">
            <span class="user-option-name">{{ user.nickname || user.username }}</span>
            <span v-if="user.fullName" class="user-option-fullname">{{ user.fullName }}</span>
            <span v-if="user.teamName" class="user-option-team">{{ user.teamName }}</span>
          </div>
        </el-option>
      </el-select>
      <el-select v-model="newRole" class="role-select">
        <el-option v-for="role in ROLES" :key="role" :label="t(`role.${role}`)" :value="role" />
      </el-select>
      <el-button type="primary" :disabled="!newUser" @click="handleAdd">
        {{ t('memberDialog.addUser') }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="members" stripe>
      <el-table-column :label="t('memberDialog.columns.user')" min-width="140">
        <template #default="{ row }">
          {{ row.nickname || row.username }}
          <el-tag v-if="isOwner(row as ProjectMemberVO)" size="small" type="warning" effect="plain">
            {{ t('memberDialog.owner') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('memberDialog.columns.role')" width="160">
        <template #default="{ row }">
          <el-select
            v-if="!isOwner(row as ProjectMemberVO)"
            :model-value="row.role"
            size="small"
            @change="(role: ProjectRole) => handleRoleChange(row as ProjectMemberVO, role)"
          >
            <el-option v-for="role in ROLES" :key="role" :label="t(`role.${role}`)" :value="role" />
          </el-select>
          <el-tag v-else size="small">{{ t('role.admin') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('memberDialog.columns.actions')" width="90">
        <template #default="{ row }">
          <el-button
            v-if="!isOwner(row as ProjectMemberVO)"
            link
            type="danger"
            size="small"
            @click="handleRemove(row as ProjectMemberVO)"
          >
            {{ t('memberDialog.remove') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="t('memberDialog.empty')" />
      </template>
    </el-table>

    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.add-member {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.user-select {
  flex: 1;
}

.role-select {
  width: 140px;
}

.user-option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.user-option-fullname {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.user-option-team {
  margin-left: auto;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  border-radius: 4px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}
</style>
