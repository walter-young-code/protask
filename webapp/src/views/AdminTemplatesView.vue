<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import { deleteAdminTemplate, listAdminTemplates, updateAdminTemplate } from '@/api/admin'
import IconPicker from '@/components/IconPicker.vue'
import type { ProjectTemplateVO } from '@/types'

const { t } = useI18n()

/** 列表查询状态 */
const loading = ref(false)
const templates = ref<ProjectTemplateVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, size: 10 })

async function loadTemplates() {
  loading.value = true
  try {
    const result = await listAdminTemplates({
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
    })
    templates.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadTemplates()
}

function handleSizeChange(size: number) {
  query.size = size
  query.page = 1
  loadTemplates()
}

function handlePageChange(page: number) {
  query.page = page
  loadTemplates()
}

/** 编辑模板弹窗 */
const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editingTemplate = ref<ProjectTemplateVO | null>(null)
const editForm = reactive({ name: '', description: '', icon: '' })
const editRules = computed<FormRules>(() => ({
  name: [
    { required: true, message: t('adminTemplates.nameRequired'), trigger: 'blur' },
    { max: 100, message: t('adminTemplates.nameTooLong'), trigger: 'blur' },
  ],
}))

function openEdit(template: ProjectTemplateVO) {
  editingTemplate.value = template
  editForm.name = template.name
  editForm.description = template.description || ''
  editForm.icon = template.icon || ''
  editVisible.value = true
}

async function handleEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const template = editingTemplate.value
  if (!template) return
  await updateAdminTemplate(template.id, {
    name: editForm.name.trim(),
    description: editForm.description.trim() || undefined,
    icon: editForm.icon || undefined,
  })
  appMessage.success(t('adminTemplates.updated'))
  editVisible.value = false
  loadTemplates()
}

/** 删除模板：模板库不再展示，已从模板创建的项目不受影响 */
async function handleDelete(template: ProjectTemplateVO) {
  try {
    await ElMessageBox.confirm(
      t('adminTemplates.deleteConfirm', { name: template.name }),
      t('adminTemplates.deleteTemplate'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await deleteAdminTemplate(template.id)
  appMessage.success(t('adminTemplates.deleted'))
  // 删除当前页最后一条时回退一页
  if (templates.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  loadTemplates()
}

onMounted(loadTemplates)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ t('adminTemplates.title') }}</h2>
    </div>

    <el-card shadow="never" class="admin-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          :prefix-icon="Search"
          :placeholder="t('adminTemplates.searchPlaceholder')"
          clearable
          class="toolbar-search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
      </div>

      <el-table v-loading="loading" :data="templates" stripe>
        <el-table-column :label="t('adminTemplates.icon')" width="70" align="center">
          <template #default="{ row }">
            <span class="template-icon">{{ (row as ProjectTemplateVO).icon || '📄' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('adminTemplates.name')" min-width="180" />
        <el-table-column :label="t('adminTemplates.description')" min-width="240">
          <template #default="{ row }">
            <span v-if="(row as ProjectTemplateVO).description">{{ (row as ProjectTemplateVO).description }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('adminTemplates.sampleCount')" width="110" align="center">
          <template #default="{ row }">
            {{ (row as ProjectTemplateVO).sampleCards?.length ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('adminTemplates.createdAt')" width="170" />
        <el-table-column :label="t('adminTemplates.actions')" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row as ProjectTemplateVO)">
              {{ t('common.edit') }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row as ProjectTemplateVO)">
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

    <!-- 编辑模板弹窗 -->
    <el-dialog
      v-model="editVisible"
      :title="t('adminTemplates.editTemplate')"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="80px" @submit.prevent>
        <el-form-item :label="t('adminTemplates.iconLabel')">
          <IconPicker v-model="editForm.icon" addable />
        </el-form-item>
        <el-form-item :label="t('adminTemplates.nameLabel')" prop="name">
          <el-input v-model="editForm.name" :placeholder="t('adminTemplates.namePlaceholder')" maxlength="100" />
        </el-form-item>
        <el-form-item :label="t('adminTemplates.descriptionLabel')" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            :placeholder="t('adminTemplates.descriptionPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleEdit">{{ t('common.save') }}</el-button>
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

.template-icon {
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
