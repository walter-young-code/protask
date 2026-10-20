<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { UploadRequestOptions } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import * as siteApi from '@/api/site'
import { useSiteStore } from '@/stores/site'

const { t } = useI18n()
const siteStore = useSiteStore()

/** LOGO 上限跟随全局上传配置（默认 20MB），文案按实际值展示 */
const maxLogoSize = computed(() => siteStore.maxUploadBytes)
const maxLogoSizeText = computed(() => `${Math.round(maxLogoSize.value / 1024 / 1024)}MB`)

const form = reactive({ name: '', logo: '' })
const uploading = ref(false)
const saving = ref(false)

onMounted(async () => {
  await siteStore.fetchSettings()
  form.name = siteStore.name
  form.logo = siteStore.logo
})

/** 上传前校验：仅图片文件且不超过全局上传上限 */
function beforeUpload(file: File) {
  if (!file.type.startsWith('image/')) {
    appMessage.warning(t('siteSettings.uploadTypeInvalid'))
    return false
  }
  if (file.size > maxLogoSize.value) {
    appMessage.warning(t('siteSettings.uploadTooLarge', { size: maxLogoSizeText.value }))
    return false
  }
  return true
}

/** 自定义上传：调用站点 LOGO 上传接口，成功后仅更新预览，保存时才持久化 */
async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const result = await siteApi.uploadSiteLogo(options.file)
    form.logo = result.url
    appMessage.success(t('siteSettings.uploadSuccess'))
  } catch {
    appMessage.error(t('siteSettings.uploadFailed'))
  } finally {
    uploading.value = false
  }
}

/** 移除 LOGO：置空后保存即恢复内置默认 LOGO */
function handleRemoveLogo() {
  form.logo = ''
}

async function handleSave() {
  const name = form.name.trim()
  if (!name) {
    appMessage.warning(t('siteSettings.nameRequired'))
    return
  }
  saving.value = true
  try {
    await siteStore.updateSettings({ name, logo: form.logo || null })
    appMessage.success(t('siteSettings.saved'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ t('siteSettings.title') }}</h2>
    </div>
    <p class="page-desc">{{ t('siteSettings.desc') }}</p>

    <el-card class="settings-card" shadow="never">
      <el-form label-width="110px" label-position="left">
        <el-form-item :label="t('siteSettings.nameLabel')">
          <el-input
            v-model="form.name"
            :placeholder="t('siteSettings.namePlaceholder')"
            maxlength="50"
            show-word-limit
            class="name-input"
          />
          <div class="field-tip">{{ t('siteSettings.nameTip') }}</div>
        </el-form-item>

        <el-form-item :label="t('siteSettings.logoLabel')">
          <div class="logo-row">
            <div class="logo-preview">
              <img :src="form.logo || '/logo.svg'" alt="logo" />
            </div>
            <div class="logo-actions">
              <el-upload
                :show-file-list="false"
                :before-upload="beforeUpload"
                :http-request="handleUpload"
                accept="image/*"
              >
                <el-button type="primary" :loading="uploading" :icon="Plus">
                  {{ t('siteSettings.uploadLogo') }}
                </el-button>
              </el-upload>
              <el-button v-if="form.logo" @click="handleRemoveLogo">
                {{ t('siteSettings.removeLogo') }}
              </el-button>
            </div>
          </div>
          <div class="field-tip">
            {{ t('siteSettings.logoTip', { size: maxLogoSizeText }) }}
            <template v-if="form.logo"> · {{ t('siteSettings.logoRemoveTip') }}</template>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">
            {{ t('siteSettings.save') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.page-desc {
  margin: -4px 0 16px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.settings-card {
  max-width: 660px;
}

.name-input {
  width: 320px;
}

.field-tip {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.5;
}

.logo-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.logo-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 64px;
  height: 64px;
  border: 1px dashed var(--app-border);
  border-radius: 8px;
  background: var(--app-fill-lighter);
}

.logo-preview img {
  width: 48px;
  height: 48px;
}

.logo-actions {
  display: flex;
  gap: 8px;
}
</style>
