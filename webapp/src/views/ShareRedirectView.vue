<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appMessage } from '@/utils/appMessage'
import { useShareStore } from '@/stores/share'
import * as projectApi from '@/api/project'

const route = useRoute()
const router = useRouter()
const shareStore = useShareStore()
const { t } = useI18n()

/** 分享链接跳转：校验凭证有效后进入项目详情（只读），无效则提示并回首页 */
onMounted(async () => {
  const shareId = String(route.params.shareId ?? '')
  try {
    const project = await projectApi.resolveShare(shareId)
    // 记录分享凭证：项目详情页后续请求自动附加 X-Share-Token
    shareStore.setShareId(shareId)
    router.replace({
      name: 'project-detail',
      params: { id: project.id },
      query: { share: shareId },
    })
  } catch {
    appMessage.error(t('share.invalid'))
    router.replace({ name: 'dashboard' })
  }
})
</script>

<template>
  <div class="share-redirect">
    <el-icon class="is-loading" :size="28"><Loading /></el-icon>
    <p>{{ t('share.redirecting') }}</p>
  </div>
</template>

<style scoped>
.share-redirect {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 100vh;
  color: var(--app-text-secondary);
  font-size: 14px;
}
</style>
