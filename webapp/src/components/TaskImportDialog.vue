<script setup lang="ts">
import { ref, watch } from 'vue'
import { Download, UploadFilled } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import * as taskExcelApi from '@/api/taskExcel'
import type { TaskImportResultVO } from '@/types'
import type { UploadFile, UploadInstance, UploadRawFile } from 'element-plus'

const props = defineProps<{
  modelValue: boolean
  projectId: number
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'imported'): void
}>()

const { t } = useI18n()
const uploadRef = ref<UploadInstance>()
const file = ref<File | null>(null)
const importing = ref(false)
/** 校验失败报告（非空时展示逐行错误表格，未导入任何任务） */
const result = ref<TaskImportResultVO | null>(null)

// 每次打开重置选择与上次结果
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      file.value = null
      result.value = null
      uploadRef.value?.clearFiles()
    }
  },
)

function close() {
  emit('update:modelValue', false)
}

function handleChange(uploadFile: UploadFile) {
  file.value = uploadFile.raw ?? null
  result.value = null
}

/** 超出单文件限制时以新文件替换 */
function handleExceed(files: File[]) {
  uploadRef.value?.clearFiles()
  const replacement = files[0]
  if (replacement) {
    const raw = replacement as UploadRawFile
    raw.uid = Date.now()
    file.value = raw
    uploadRef.value?.handleStart(raw)
  }
}

/** 下载导入模板（浏览器下载，鉴权走请求头） */
async function handleDownloadTemplate() {
  const blob = await taskExcelApi.downloadImportTemplate(props.projectId)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `project-${props.projectId}-import-template.xlsx`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/** 提交导入：成功提示并刷新任务；校验失败展示逐行错误报告 */
async function handleImport() {
  if (!file.value) {
    appMessage.warning(t('taskExcel.fileRequired'))
    return
  }
  importing.value = true
  try {
    const res = await taskExcelApi.importTasksXlsx(props.projectId, file.value)
    result.value = res
    if (res.errors.length === 0) {
      appMessage.success(
        t('taskExcel.importSuccess', { created: res.created, deps: res.dependencyCount }),
      )
      emit('imported')
      close()
    }
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('taskExcel.importTitle')"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="import-body">
      <div class="import-toolbar">
        <el-button :icon="Download" link type="primary" @click="handleDownloadTemplate">
          {{ t('taskExcel.downloadTemplate') }}
        </el-button>
      </div>
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx"
        :on-change="handleChange"
        :on-exceed="handleExceed"
        :on-remove="() => (file = null)"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">{{ t('taskExcel.uploadTip') }}</div>
      </el-upload>
      <p class="import-hint">{{ t('taskExcel.uploadHint') }}</p>

      <template v-if="result && result.errors.length > 0">
        <el-alert
          :title="t('taskExcel.failedTitle')"
          type="error"
          :closable="false"
          class="import-error-alert"
        />
        <el-table :data="result.errors" size="small" max-height="240" class="import-error-table">
          <el-table-column prop="row" :label="t('taskExcel.errorRow')" width="70" />
          <el-table-column prop="message" :label="t('taskExcel.errorMessage')" />
        </el-table>
      </template>
      <p v-if="result && result.ignoredColumns.length > 0" class="import-hint">
        {{ t('taskExcel.ignoredColumns', { columns: result.ignoredColumns.join('、') }) }}
      </p>
    </div>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="importing" :disabled="!file" @click="handleImport">
        {{ t('taskExcel.import') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.import-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}

.import-error-alert {
  margin-top: 12px;
}

.import-error-table {
  margin-top: 8px;
}
</style>