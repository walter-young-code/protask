<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useProjectStore } from '@/stores/project'
import { useCategoryStore } from '@/stores/category'
import IconPicker from '@/components/IconPicker.vue'
import type { ProjectVO } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  /** 编辑对象：传入时进入编辑模式（预填表单并更新），否则为创建模式 */
  project?: ProjectVO | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'created', id: number): void
  (e: 'updated', project: ProjectVO): void
}>()

const projectStore = useProjectStore()
const categoryStore = useCategoryStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  name: '',
  description: '',
  type: 'P' as 'O' | 'P',
  /** 分类ID：0 表示无分类 */
  categoryId: 0 as number,
  /** 项目图标（emoji，空串表示无图标） */
  icon: '',
})

const rules = computed<FormRules>(() => ({
  name: [
    { required: true, message: t('validation.projectNameRequired'), trigger: 'blur' },
    { max: 100, message: t('validation.projectNameLength'), trigger: 'blur' },
  ],
}))

// 每次打开时重置表单；编辑模式预填项目信息；同时确保分类列表已加载
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      form.name = props.project?.name ?? ''
      form.description = props.project?.description ?? ''
      form.type = props.project?.type ?? 'P'
      form.categoryId = props.project?.categoryId ?? 0
      form.icon = props.project?.icon ?? ''
      if (!categoryStore.categories.length) {
        categoryStore.fetchCategories()
      }
      formRef.value?.clearValidate()
    }
  },
)

function close() {
  emit('update:modelValue', false)
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    if (props.project) {
      const project = await projectStore.updateProject(props.project.id, {
        name: form.name,
        description: form.description || undefined,
        type: form.type,
        icon: form.icon,
        // 0 表示清除分类，数字表示设置分类
        categoryId: form.categoryId,
      })
      close()
      emit('updated', project)
    } else {
      const project = await projectStore.createProject({
        name: form.name,
        description: form.description || undefined,
        type: form.type,
        icon: form.icon,
        // 0/无 → null（未分类）
        categoryId: form.categoryId || null,
      })
      close()
      emit('created', project.id)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.project ? t('projectDialog.editTitle') : t('projectDialog.title')"
    width="600px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item :label="t('projectDialog.iconLabel')">
        <IconPicker v-model="form.icon" addable />
        <span class="icon-hint">{{ t('projectDialog.iconHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('projectDialog.nameLabel')" prop="name">
        <el-input v-model="form.name" :placeholder="t('projectDialog.namePlaceholder')" maxlength="100" />
      </el-form-item>
      <el-form-item :label="t('projectDialog.descriptionLabel')" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          :placeholder="t('projectDialog.descriptionPlaceholder')"
          maxlength="500"
        />
      </el-form-item>
      <el-form-item :label="t('projectDialog.typeLabel')" prop="type">
        <el-radio-group v-model="form.type">
          <el-radio value="P">{{ t('projectDialog.typePrivate') }}</el-radio>
          <el-radio value="O">{{ t('projectDialog.typeOpen') }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="t('projectDialog.categoryLabel')" prop="categoryId">
        <el-select v-model="form.categoryId" class="full-width">
          <el-option :label="t('projectDialog.categoryNone')" :value="0" />
          <el-option
            v-for="category in categoryStore.categories"
            :key="category.id"
            :label="category.name"
            :value="category.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">
        {{ props.project ? t('common.save') : t('common.create') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.icon-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.full-width {
  width: 100%;
}
</style>
