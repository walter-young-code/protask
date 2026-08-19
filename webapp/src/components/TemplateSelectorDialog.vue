<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createProjectFromTemplate, listTemplates } from '@/api/project'
import type { CardProperty, ProjectTemplateSampleCard, ProjectTemplateVO } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 从模板创建成功，携带新项目 ID */
  (e: 'created', id: number): void
  /** 点击「创建空白项目」 */
  (e: 'blank'): void
}>()

const loading = ref(false)
const creating = ref(false)
const templates = ref<ProjectTemplateVO[]>([])
const selectedId = ref<number | null>(null)
const projectName = ref('')

const selected = computed(
  () => templates.value.find((t) => t.id === selectedId.value) ?? null,
)

/** 模板属性定义（cardProperties 为 JSON 数组字符串） */
const properties = computed<CardProperty[]>(() => {
  if (!selected.value?.cardProperties) return []
  try {
    const parsed = JSON.parse(selected.value.cardProperties)
    return Array.isArray(parsed) ? (parsed as CardProperty[]) : []
  } catch {
    return []
  }
})

/** 示例卡片属性值：选项型属性把值 ID 映射为可读文案 */
function sampleValue(card: ProjectTemplateSampleCard, prop: CardProperty): string {
  const raw = card.properties?.[prop.id]
  if (raw === undefined || raw === null || raw === '') return ''
  if (Array.isArray(raw)) {
    return raw
      .map((v) => prop.options?.find((o) => o.id === v)?.value ?? String(v))
      .join('、')
  }
  return prop.options?.find((o) => o.id === raw)?.value ?? String(raw)
}

/** 每次打开时加载模板库，默认选中第一个并预填项目名 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    projectName.value = ''
    loading.value = true
    listTemplates()
      .then((data) => {
        templates.value = data
        if (data.length) {
          selectedId.value = data[0].id
          projectName.value = data[0].name ?? ''
        } else {
          selectedId.value = null
        }
      })
      .finally(() => {
        loading.value = false
      })
  },
)

function selectTemplate(template: ProjectTemplateVO) {
  selectedId.value = template.id
  projectName.value = template.name ?? ''
}

function close() {
  emit('update:modelValue', false)
}

async function handleUseTemplate() {
  if (!selected.value) return
  const name = projectName.value.trim()
  if (!name) return
  creating.value = true
  try {
    const project = await createProjectFromTemplate({
      templateId: selected.value.id,
      name,
    })
    close()
    emit('created', project.id)
  } finally {
    creating.value = false
  }
}

/** 创建空白项目：关闭本弹窗并交由父组件打开 ProjectDialog */
function handleBlank() {
  close()
  emit('blank')
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('template.title')"
    width="860px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="template-selector">
      <!-- 左侧：模板列表 -->
      <div class="template-list" v-loading="loading">
        <template v-if="templates.length">
          <div
            v-for="template in templates"
            :key="template.id"
            class="template-item"
            :class="{ active: template.id === selectedId }"
            @click="selectTemplate(template)"
          >
            <span class="template-icon">{{ template.icon || '📋' }}</span>
            <span class="template-name">{{ template.name }}</span>
          </div>
        </template>
        <el-empty v-else-if="!loading" :description="t('template.noTemplates')" :image-size="64" />
      </div>

      <!-- 右侧：预览 -->
      <div class="template-preview">
        <template v-if="selected">
          <div class="preview-header">
            <span class="preview-icon">{{ selected.icon || '📋' }}</span>
            <h3 class="preview-title">{{ selected.name }}</h3>
          </div>
          <p v-if="selected.description" class="preview-desc">{{ selected.description }}</p>

          <div v-if="properties.length" class="preview-section">
            <div class="preview-label">{{ t('template.properties') }}</div>
            <div class="property-chips">
              <el-tag
                v-for="prop in properties"
                :key="prop.id"
                size="small"
                effect="plain"
                class="property-chip"
              >
                {{ prop.name }} · {{ t(`propertyType.${prop.type}`) }}
              </el-tag>
            </div>
          </div>

          <div v-if="selected.sampleCards?.length" class="preview-section">
            <div class="preview-label">{{ t('template.sampleCards') }}</div>
            <div class="sample-cards">
              <div v-for="(card, index) in selected.sampleCards" :key="index" class="sample-card">
                <span class="sample-card-icon">{{ card.icon || '📄' }}</span>
                <div class="sample-card-body">
                  <div class="sample-card-title">{{ card.title }}</div>
                  <div v-if="card.properties" class="sample-card-values">
                    <span
                      v-for="prop in properties"
                      :key="prop.id"
                      v-show="sampleValue(card, prop)"
                      class="sample-card-value"
                    >
                      <span class="sample-card-key">{{ prop.name }}：</span>{{ sampleValue(card, prop) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!loading" :description="t('template.noTemplates')" />
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="close">{{ t('common.cancel') }}</el-button>
        <el-button @click="handleBlank">{{ t('template.blankProject') }}</el-button>
        <el-input
          v-model="projectName"
          class="template-name-input"
          :placeholder="t('template.projectNamePlaceholder')"
          maxlength="100"
          :disabled="!selected || creating"
        />
        <el-button
          type="primary"
          :loading="creating"
          :disabled="!selected || !projectName.trim()"
          @click="handleUseTemplate"
        >
          {{ t('template.useTemplate') }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.template-selector {
  display: flex;
  gap: 16px;
  min-height: 380px;
}

.template-list {
  flex: 0 0 220px;
  border-right: 1px solid var(--app-border-light);
  padding-right: 12px;
  overflow-y: auto;
  max-height: 420px;
}

.template-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.template-item:hover {
  background-color: var(--app-fill);
}

.template-item.active {
  background-color: var(--el-color-primary-light-9);
}

.template-icon {
  font-size: 18px;
}

.template-name {
  font-size: 14px;
  color: var(--app-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.template-preview {
  flex: 1;
  overflow-y: auto;
  max-height: 420px;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.preview-icon {
  font-size: 26px;
}

.preview-title {
  margin: 0;
  font-size: 18px;
  color: var(--app-text-primary);
}

.preview-desc {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--app-text-regular);
  line-height: 1.6;
}

.preview-section {
  margin-top: 16px;
}

.preview-label {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-bottom: 8px;
}

.property-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sample-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sample-card {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--app-border-light);
  border-radius: 6px;
  background-color: var(--app-fill-lighter);
}

.sample-card-icon {
  font-size: 16px;
}

.sample-card-title {
  font-size: 13px;
  color: var(--app-text-primary);
  font-weight: 500;
}

.sample-card-values {
  margin-top: 2px;
  font-size: 12px;
  color: var(--app-text-regular);
}

.sample-card-value {
  margin-right: 12px;
}

.sample-card-key {
  color: var(--app-text-secondary);
}

.dialog-footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.template-name-input {
  width: 200px;
  margin-left: auto;
}
</style>
