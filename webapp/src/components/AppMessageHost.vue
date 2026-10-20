<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import {
  CircleCloseFilled,
  Close,
  InfoFilled,
  SuccessFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import { appMessage, appMessages } from '@/utils/appMessage'
import type { AppMessageType } from '@/utils/appMessage'

const { t } = useI18n()

/** 类型 → 图标与主题色映射（与 Element Plus 消息语义一致） */
const typeMeta: Record<AppMessageType, { icon: typeof SuccessFilled; color: string }> = {
  success: { icon: SuccessFilled, color: '#67c23a' },
  error: { icon: CircleCloseFilled, color: '#f56c6c' },
  warning: { icon: WarningFilled, color: '#e6a23c' },
  info: { icon: InfoFilled, color: '#909399' },
}
</script>

<template>
  <Transition name="app-message-panel">
    <div v-if="appMessages.length" class="app-message-panel">
      <div class="app-message-panel-header">
        <span class="app-message-panel-title">{{ t('message.title') }}</span>
        <el-button link size="small" class="app-message-clear" @click="appMessage.clear()">
          {{ t('message.clearAll') }}
        </el-button>
      </div>
      <TransitionGroup tag="div" name="app-message" class="app-message-list">
        <div v-for="item in appMessages" :key="item.id" class="app-message-item">
          <el-icon class="app-message-icon" :style="{ color: typeMeta[item.type].color }">
            <component :is="typeMeta[item.type].icon" />
          </el-icon>
          <span class="app-message-content">{{ item.content }}</span>
          <el-icon class="app-message-close" @click="appMessage.close(item.id)">
            <Close />
          </el-icon>
        </div>
      </TransitionGroup>
    </div>
  </Transition>
</template>

<style scoped>
/* 消息弹窗：顶部居中固定，有消息时出现，1 秒未手动关闭则自动消失 */
.app-message-panel {
  position: fixed;
  top: 20px;
  left: 50%;
  /* 使用独立 translate 属性水平居中，避免与入场 transform 动画互相覆盖 */
  translate: -50% 0;
  z-index: 3000;
  width: 360px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  background: var(--app-surface);
  border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.14);
  border: 1px solid var(--app-border-light);
  overflow: hidden;
}

.app-message-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 8px;
  border-bottom: 1px solid var(--app-border-lighter);
}

.app-message-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.app-message-clear {
  font-size: 12px;
}

.app-message-list {
  overflow-y: auto;
  padding: 4px;
}

.app-message-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--app-text-primary);
  line-height: 1.5;
}

.app-message-item:hover {
  background: var(--app-fill);
}

.app-message-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 16px;
}

.app-message-content {
  flex: 1;
  min-width: 0;
  word-break: break-word;
  white-space: pre-wrap;
}

.app-message-close {
  flex-shrink: 0;
  margin-top: 3px;
  font-size: 14px;
  color: var(--app-text-placeholder);
  cursor: pointer;
}

.app-message-close:hover {
  color: var(--app-text-regular);
}

/* 面板整体出现动画：由上向下弹出 */
.app-message-panel-enter-active,
.app-message-panel-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.app-message-panel-enter-from,
.app-message-panel-leave-to {
  opacity: 0;
  transform: translateY(-16px);
}

/* 单条消息进出动画：由上向下弹出 */
.app-message-enter-active,
.app-message-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.app-message-enter-from,
.app-message-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
