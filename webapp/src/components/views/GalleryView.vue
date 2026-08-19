<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { TaskVO } from '@/types'

defineProps<{
  tasks: TaskVO[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskVO): void
}>()

const { t } = useI18n()

/** 从任务描述 Markdown 中提取第一张图片 URL（画廊卡片封面） */
function cardCover(task: TaskVO): string | undefined {
  return task.description?.match(/!\[[^\]]*\]\(([^)]+)\)/)?.[1]
}

/** 图片加载失败时隐藏，露出占位背景 */
function handleCoverError(event: Event) {
  ;(event.target as HTMLImageElement).style.display = 'none'
}
</script>

<template>
  <div v-loading="loading">
    <el-empty v-if="tasks.length === 0" :description="t('task.empty')" />
    <el-row v-else :gutter="16">
      <el-col
        v-for="task in tasks"
        :key="task.id"
        :xs="24"
        :sm="12"
        :md="8"
        :lg="6"
      >
        <el-card class="gallery-card" shadow="hover" @click="emit('open', task)">
          <!-- 图片预览区：有图显示描述中第一张图，无图显示描述文本占位 -->
          <div class="gallery-card-cover">
            <img
              v-if="cardCover(task)"
              :src="cardCover(task)"
              class="gallery-card-img"
              alt=""
              @error="handleCoverError"
            />
            <div v-else class="gallery-card-cover-empty">
              {{ task.description || t('dashboard.noDescription') }}
            </div>
          </div>
          <div class="gallery-card-title">
            <span v-if="task.icon" class="gallery-card-icon">{{ task.icon }}</span>
            {{ task.title }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.gallery-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.2s;
  overflow: hidden;
}

.gallery-card:hover {
  transform: translateY(-2px);
}

/* 封面区：固定高度，图片 cover 裁剪铺满，无图时浅灰占位 */
.gallery-card-cover {
  height: 150px;
  margin: -20px -20px 12px;
  background: var(--app-fill);
  overflow: hidden;
}

.gallery-card-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.gallery-card-cover-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  font-size: 13px;
  color: var(--app-text-secondary);
  text-align: center;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.gallery-card-title {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}

.gallery-card-icon {
  font-size: 15px;
  line-height: 1;
  flex-shrink: 0;
}
</style>
