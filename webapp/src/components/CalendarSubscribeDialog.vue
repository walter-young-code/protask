<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { CopyDocument, Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import * as projectApi from '@/api/project'
import type { CalendarSubscriptionVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  projectId: number
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { t } = useI18n()
const loading = ref(false)
const subscription = ref<CalendarSubscriptionVO | null>(null)

/** 是否已开启订阅（计算属性驱动开关展示，取消关闭时开关自动回弹） */
const enabled = computed(() => Boolean(subscription.value?.calendarToken))

// 每次打开时查询当前订阅状态
watch(
  () => props.modelValue,
  async (visible) => {
    if (visible) {
      loading.value = true
      try {
        subscription.value = await projectApi.getCalendarSubscription(props.projectId)
      } finally {
        loading.value = false
      }
    }
  },
)

function close() {
  emit('update:modelValue', false)
}

/** 开启/关闭订阅：关闭需确认（旧订阅地址将立即失效） */
async function handleToggle(val: string | number | boolean) {
  const enabledValue = Boolean(val)
  if (enabledValue) {
    loading.value = true
    try {
      subscription.value = await projectApi.enableCalendarSubscription(props.projectId)
      appMessage.success(t('calendarSubscribe.enabled'))
    } finally {
      loading.value = false
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      t('calendarSubscribe.disableConfirm'),
      t('calendarSubscribe.disableTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  loading.value = true
  try {
    subscription.value = await projectApi.disableCalendarSubscription(props.projectId)
    appMessage.success(t('calendarSubscribe.disabled'))
  } finally {
    loading.value = false
  }
}

/** 重新生成凭证：旧订阅地址立即失效（泄露后止损），需确认 */
async function handleRegenerate() {
  try {
    await ElMessageBox.confirm(
      t('calendarSubscribe.regenerateConfirm'),
      t('calendarSubscribe.regenerateTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  loading.value = true
  try {
    subscription.value = await projectApi.regenerateCalendarSubscription(props.projectId)
    appMessage.success(t('calendarSubscribe.regenerated'))
  } finally {
    loading.value = false
  }
}

/** 复制订阅地址到剪贴板 */
async function handleCopy() {
  if (!subscription.value?.subscribeUrl) return
  try {
    await navigator.clipboard.writeText(subscription.value.subscribeUrl)
    appMessage.success(t('calendarSubscribe.copied'))
  } catch {
    appMessage.error(t('calendarSubscribe.copyFailed'))
  }
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('calendarSubscribe.title')"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="subscribe-body">
      <el-form label-width="90px">
        <el-form-item :label="t('calendarSubscribe.enableLabel')">
          <el-switch :model-value="enabled" :loading="loading" @update:model-value="handleToggle" />
          <span class="subscribe-status">{{
            enabled ? t('calendarSubscribe.on') : t('calendarSubscribe.off')
          }}</span>
        </el-form-item>
        <el-form-item v-if="enabled && subscription?.subscribeUrl" :label="t('calendarSubscribe.linkLabel')">
          <el-input :model-value="subscription.subscribeUrl" readonly class="subscribe-link-input">
            <template #append>
              <el-button :icon="CopyDocument" @click="handleCopy">{{
                t('calendarSubscribe.copy')
              }}</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item v-if="enabled" label=" ">
          <el-button :icon="Refresh" :loading="loading" @click="handleRegenerate">{{
            t('calendarSubscribe.regenerate')
          }}</el-button>
        </el-form-item>
      </el-form>
      <p class="subscribe-note">{{ t('calendarSubscribe.note') }}</p>
    </div>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.subscribe-status {
  margin-left: 8px;
  font-size: 13px;
  color: var(--app-text-regular);
}

.subscribe-link-input :deep(.el-input__inner) {
  color: var(--app-text-regular);
}

.subscribe-note {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}
</style>
