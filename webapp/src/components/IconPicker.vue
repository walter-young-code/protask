<script setup lang="ts">
import { computed, ref } from 'vue'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { EMOJI_CATEGORIES, randomEmoji, type EmojiCategory } from '@/utils/emoji'

/**
 * 图标选择器（对齐 Focalboard BlockIconSelector）：
 * 显示当前图标，点击弹出「随机 / 选择表情 / 移除」操作面板。
 */
const props = defineProps<{
  modelValue: string
  /** 只读模式：仅展示图标，不可编辑 */
  readonly?: boolean
  /** 无图标时是否显示“添加图标”入口 */
  addable?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const { t } = useI18n()

const visible = ref(false)

/** 滚动浏览容器（分类定位滚动目标） */
const scrollRef = ref<HTMLElement>()
/** 各分类区块 DOM（按分类 key 收集） */
const categoryEls = ref<Record<string, HTMLElement>>({})
/** 当前激活分类（导航高亮，滚动联动更新） */
const activeCategory = ref(EMOJI_CATEGORIES[0].key)

/** v-for 分类块的 ref 收集器 */
function categoryRef(category: EmojiCategory) {
  return (el: unknown) => {
    if (el instanceof Element) categoryEls.value[category.key] = el as HTMLElement
  }
}

/** 点击分类导航：平滑滚动定位到对应分类 */
function scrollToCategory(key: string) {
  const container = scrollRef.value
  const el = categoryEls.value[key]
  if (!container || !el) return
  container.scrollTo({ top: el.offsetTop, behavior: 'smooth' })
}

/** 滚动时联动高亮当前分类（sticky 头吸顶即视为进入该分类） */
function handleScroll() {
  const container = scrollRef.value
  if (!container) return
  if (container.scrollTop >= container.scrollHeight - container.clientHeight - 2) {
    activeCategory.value = EMOJI_CATEGORIES[EMOJI_CATEGORIES.length - 1].key
    return
  }
  let current = EMOJI_CATEGORIES[0].key
  for (const category of EMOJI_CATEGORIES) {
    const el = categoryEls.value[category.key]
    if (el && el.offsetTop <= container.scrollTop + 2) {
      current = category.key
    }
  }
  activeCategory.value = current
}

/** 是否有图标 */
const hasIcon = computed(() => props.modelValue.length > 0)

function selectEmoji(emoji: string) {
  emit('update:modelValue', emoji)
  visible.value = false
}

function handleRandom() {
  emit('update:modelValue', randomEmoji())
}

function handleRemove() {
  emit('update:modelValue', '')
  visible.value = false
}
</script>

<template>
  <!-- 只读：仅展示图标 -->
  <span v-if="readonly && hasIcon" class="icon-display">{{ modelValue }}</span>

  <!-- 可编辑 -->
  <el-popover
    v-else
    v-model:visible="visible"
    trigger="click"
    placement="bottom-start"
    :width="320"
    :disabled="readonly"
  >
    <template #reference>
      <span
        class="icon-trigger"
        :class="{ 'is-empty': !hasIcon, 'is-addable': addable && !hasIcon }"
        :title="t('icon.pick')"
      >
        <span v-if="hasIcon" class="icon-current">{{ modelValue }}</span>
        <el-icon v-else-if="addable" class="icon-add"><Plus /></el-icon>
      </span>
    </template>

    <div class="icon-picker">
      <div class="icon-picker-toolbar">
        <el-button size="small" :icon="Refresh" @click="handleRandom">
          {{ t('icon.random') }}
        </el-button>
        <el-button
          v-if="hasIcon"
          size="small"
          type="danger"
          plain
          :icon="Delete"
          @click="handleRemove"
        >
          {{ t('icon.remove') }}
        </el-button>
      </div>
      <!-- 顶部分类导航：点击定位到对应分类（对齐 Focalboard emoji-mart） -->
      <div class="icon-category-nav">
        <button
          v-for="category in EMOJI_CATEGORIES"
          :key="category.key"
          type="button"
          class="icon-category-tab"
          :class="{ active: activeCategory === category.key }"
          :title="t(`icon.category.${category.key}`)"
          @click="scrollToCategory(category.key)"
        >
          <span class="icon-category-tab-emoji">{{ category.emojis[0] }}</span>
          <span class="icon-category-tab-label">{{ t(`icon.category.${category.key}`) }}</span>
        </button>
      </div>
      <!-- 分类滚动浏览（对齐 Focalboard emoji-mart）：sticky 分类头 + 网格 -->
      <div ref="scrollRef" class="icon-scroll" @scroll="handleScroll">
        <div
          v-for="category in EMOJI_CATEGORIES"
          :key="category.key"
          :ref="categoryRef(category)"
          class="icon-category"
        >
          <div class="icon-category-title">
            {{ t(`icon.category.${category.key}`) }}
          </div>
          <div class="icon-grid">
            <span
              v-for="emoji in category.emojis"
              :key="emoji"
              class="icon-cell"
              :class="{ selected: emoji === modelValue }"
              @click="selectEmoji(emoji)"
            >
              {{ emoji }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.icon-display {
  font-size: 20px;
  line-height: 1;
  display: inline-flex;
}

.icon-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
  vertical-align: middle;
}

.icon-trigger:hover {
  background: var(--app-fill);
}

.icon-current {
  font-size: 20px;
  line-height: 1;
}

.icon-trigger.is-empty {
  border: 1px dashed var(--app-border);
}

.icon-trigger.is-empty:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.icon-add {
  font-size: 14px;
  color: var(--app-text-secondary);
}

.icon-picker-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.icon-category-nav {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-bottom: 8px;
  overflow-x: auto;
}

.icon-category-tab {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  padding: 3px 8px;
  border: none;
  border-radius: 12px;
  background: transparent;
  font-size: 12px;
  color: var(--app-text-regular);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.icon-category-tab:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.icon-category-tab.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.icon-category-tab-emoji {
  font-size: 14px;
  line-height: 1;
}

.icon-scroll {
  position: relative;
  max-height: 240px;
  overflow-y: auto;
}

.icon-category + .icon-category {
  margin-top: 4px;
}

.icon-category-title {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 4px 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  background: var(--app-surface);
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: 2px;
}

.icon-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  font-size: 18px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.icon-cell:hover {
  background: var(--el-color-primary-light-9);
}

.icon-cell.selected {
  background: var(--el-color-primary-light-8);
  outline: 1px solid var(--el-color-primary);
}
</style>
