<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import * as projectApi from '@/api/project'
import type { ShareVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  projectId: number
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { t } = useI18n()
const loading = ref(false)
const share = ref<ShareVO | null>(null)

/** 是否已开启分享（计算属性驱动开关展示，取消关闭时开关自动回弹） */
const enabled = computed(() => Boolean(share.value?.shareId))

// 每次打开时查询当前分享状态
watch(
  () => props.modelValue,
  async (visible) => {
    if (visible) {
      loading.value = true
      try {
        share.value = await projectApi.getShare(props.projectId)
      } finally {
        loading.value = false
      }
    }
  },
)

function close() {
  emit('update:modelValue', false)
}

/** 开启/关闭分享：关闭需确认（旧链接将立即失效） */
async function handleToggle(val: string | number | boolean) {
  const enabledValue = Boolean(val)
  if (enabledValue) {
    loading.value = true
    try {
      share.value = await projectApi.createShare(props.projectId)
      appMessage.success(t('share.enabled'))
    } finally {
      loading.value = false
    }
    return
  }
  try {
    await ElMessageBox.confirm(t('share.disableConfirm'), t('share.disableTitle'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  loading.value = true
  try {
    share.value = await projectApi.disableShare(props.projectId)
    appMessage.success(t('share.disabled'))
  } finally {
    loading.value = false
  }
}

/** 复制分享链接到剪贴板 */
async function handleCopy() {
  if (!share.value?.shareUrl) return
  try {
    await navigator.clipboard.writeText(share.value.shareUrl)
    appMessage.success(t('share.copied'))
  } catch {
    appMessage.error(t('share.copyFailed'))
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('share.title')"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="share-body">
      <el-form label-width="90px">
        <el-form-item :label="t('share.enableLabel')">
          <el-switch :model-value="enabled" :loading="loading" @update:model-value="handleToggle" />
          <span class="share-status">{{ enabled ? t('share.on') : t('share.off') }}</span>
        </el-form-item>
        <el-form-item v-if="enabled && share?.shareUrl" :label="t('share.linkLabel')">
          <el-input :model-value="share.shareUrl" readonly class="share-link-input">
            <template #append>
              <el-button :icon="CopyDocument" @click="handleCopy">{{ t('share.copy') }}</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <p class="share-note">{{ t('share.note') }}</p>
    </div>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-status {
  margin-left: 8px;
  font-size: 13px;
  color: var(--app-text-regular);
}

.share-link-input :deep(.el-input__inner) {
  color: var(--app-text-regular);
}

.share-note {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}
</style>
