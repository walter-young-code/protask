<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import { cleanupOrphanImages, getStorageStats } from '@/api/admin'
import type { StorageStatsVO } from '@/types'

const { t } = useI18n()

const loading = ref(false)
const cleaning = ref(false)
const stats = ref<StorageStatsVO | null>(null)

/** 两桶合计文件数与占用 */
const totalFiles = computed(() =>
  stats.value ? stats.value.publicImages.fileCount + stats.value.privateAttachments.fileCount : 0,
)
const totalBytes = computed(() =>
  stats.value ? stats.value.publicImages.totalBytes + stats.value.privateAttachments.totalBytes : 0,
)

/** 字节数转可读文本（B/KB/MB/GB） */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

async function loadStats() {
  loading.value = true
  try {
    stats.value = await getStorageStats()
  } catch {
    // 错误提示由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 清理孤儿图片：确认后删除无引用且超过保留期的插图并刷新统计 */
async function handleCleanup() {
  if (!stats.value || stats.value.orphanImages === 0) {
    appMessage.info(t('adminStorage.cleanupEmpty'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('adminStorage.cleanupConfirm', {
        images: stats.value.orphanImages,
        size: formatBytes(stats.value.orphanImageBytes),
      }),
      t('adminStorage.cleanupConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('adminStorage.cleanup'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  cleaning.value = true
  try {
    const result = await cleanupOrphanImages()
    appMessage.success(
      t('adminStorage.cleanupSuccess', {
        images: result.deletedImages,
        size: formatBytes(result.freedBytes),
      }),
    )
    await loadStats()
  } finally {
    cleaning.value = false
  }
}

onMounted(loadStats)
</script>

<template>
  <div class="page-container" v-loading="loading">
    <div class="page-header">
      <h2>{{ t('adminStorage.title') }}</h2>
      <el-button :icon="Refresh" :loading="loading" @click="loadStats">
        {{ t('adminStorage.refresh') }}
      </el-button>
    </div>
    <p class="page-desc">{{ t('adminStorage.desc') }}</p>

    <el-alert
      v-if="stats?.warning"
      type="warning"
      :closable="false"
      class="storage-warning"
      :title="t('adminStorage.warning', { files: totalFiles, size: formatBytes(totalBytes) })"
    />

    <div v-if="stats" class="storage-grid">
      <el-card shadow="never" class="storage-card">
        <div class="bucket-head">
          <span class="bucket-title">{{ t('adminStorage.publicImages') }}</span>
          <span class="bucket-hint">{{ t('adminStorage.publicImagesHint') }}</span>
        </div>
        <div class="bucket-stats">
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.files') }}</span>
            <span class="stat-value">{{ stats.publicImages.fileCount }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.size') }}</span>
            <span class="stat-value">{{ formatBytes(stats.publicImages.totalBytes) }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.uploadImageRecords') }}</span>
            <span class="stat-value">{{ stats.uploadImageRecords }}</span>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="storage-card">
        <div class="bucket-head">
          <span class="bucket-title">{{ t('adminStorage.privateAttachments') }}</span>
          <span class="bucket-hint">{{ t('adminStorage.privateAttachmentsHint') }}</span>
        </div>
        <div class="bucket-stats">
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.files') }}</span>
            <span class="stat-value">{{ stats.privateAttachments.fileCount }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.size') }}</span>
            <span class="stat-value">{{ formatBytes(stats.privateAttachments.totalBytes) }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">{{ t('adminStorage.attachmentRecords') }}</span>
            <span class="stat-value">{{ stats.attachmentRecords }}</span>
          </div>
        </div>
      </el-card>
    </div>

    <el-card v-if="stats" shadow="never" class="orphan-card">
      <div class="orphan-head">
        <div>
          <h3>{{ t('adminStorage.orphanTitle') }}</h3>
          <p class="orphan-desc">
            {{ t('adminStorage.orphanDesc', { days: stats.orphanRetentionDays }) }}
          </p>
        </div>
        <el-button
          type="danger"
          :loading="cleaning"
          :disabled="stats.orphanImages === 0"
          @click="handleCleanup"
        >
          {{ t('adminStorage.cleanup') }}
        </el-button>
      </div>
      <div class="orphan-stats">
        <div class="stat-item">
          <span class="stat-label">{{ t('adminStorage.orphanCount') }}</span>
          <span class="stat-value">{{ stats.orphanImages }}</span>
        </div>
        <div class="stat-item">
          <span class="stat-label">{{ t('adminStorage.orphanSize') }}</span>
          <span class="stat-value">{{ formatBytes(stats.orphanImageBytes) }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-desc {
  margin: -4px 0 16px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.storage-warning {
  margin-bottom: 16px;
}

.storage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.storage-card,
.orphan-card {
  border-radius: 8px;
}

.bucket-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.bucket-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.bucket-hint,
.orphan-desc {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.bucket-stats,
.orphan-stats {
  display: flex;
  gap: 32px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.orphan-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.orphan-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.orphan-stats {
  margin-top: 12px;
}
</style>
