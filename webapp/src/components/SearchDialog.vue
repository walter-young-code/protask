<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import * as taskApi from '@/api/task'
import type { TaskVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { t } = useI18n()
const router = useRouter()

const keyword = ref('')
const searching = ref(false)
const results = ref<TaskVO[]>([])
const searched = ref(false)
let debounceTimer: ReturnType<typeof setTimeout> | undefined
// 请求序号：关键字变化后丢弃在途的旧响应，避免慢请求覆盖新结果
let searchSeq = 0

/** 按项目分组展示搜索结果 */
const grouped = computed(() => {
  const groups = new Map<string, TaskVO[]>()
  for (const task of results.value) {
    const key = task.projectName ?? t('search.unknownProject')
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(task)
  }
  return [...groups.entries()]
})

/** 取消尚未触发的防抖搜索 */
function stopDebounce() {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
    debounceTimer = undefined
  }
}

/** 输入防抖 300ms 后搜索 */
watch(keyword, (value) => {
  stopDebounce()
  searchSeq++
  const kw = value.trim()
  if (!kw) {
    results.value = []
    searched.value = false
    return
  }
  debounceTimer = setTimeout(async () => {
    const seq = ++searchSeq
    searching.value = true
    try {
      const items = await taskApi.searchTasks(kw)
      if (seq !== searchSeq) return
      results.value = items
      searched.value = true
    } finally {
      if (seq === searchSeq) searching.value = false
    }
  }, 300)
})

// 打开时重置状态
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      keyword.value = ''
      results.value = []
      searched.value = false
    }
  },
)

function close() {
  stopDebounce()
  emit('update:modelValue', false)
}

onBeforeUnmount(stopDebounce)

/** 点击结果：跳转项目详情并自动打开任务卡片 */
function handleSelect(task: TaskVO) {
  close()
  router.push({
    name: 'project-detail',
    params: { id: task.projectId },
    query: { task: task.id },
  })
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    width="560px"
    :show-close="false"
    :close-on-click-modal="true"
    class="search-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-input
      v-model="keyword"
      size="large"
      :prefix-icon="Search"
      :placeholder="t('search.placeholder')"
      class="search-input"
      @keydown.esc="close"
    />

    <div v-loading="searching" class="search-results">
      <template v-for="[projectName, tasks] in grouped" :key="projectName">
        <div class="search-group-title">{{ projectName }}</div>
        <div
          v-for="task in tasks"
          :key="task.id"
          class="search-result-item"
          @click="handleSelect(task)"
        >
          <span class="search-result-title">
            <span v-if="task.icon" class="search-result-icon">{{ task.icon }}</span>
            {{ task.title }}
          </span>
          <span v-if="task.creatorName" class="search-result-creator">{{ task.creatorName }}</span>
        </div>
      </template>
      <el-empty
        v-if="searched && results.length === 0"
        :description="t('search.empty')"
        :image-size="60"
      />
      <div v-if="!searched && !searching" class="search-hint">
        {{ t('search.hint') }}
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.search-input {
  margin-bottom: 8px;
}

.search-results {
  min-height: 120px;
  max-height: 400px;
  overflow-y: auto;
}

.search-group-title {
  font-size: 12px;
  color: var(--app-text-secondary);
  padding: 8px 4px 4px;
}

.search-result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}

.search-result-item:hover {
  background: var(--el-color-primary-light-9);
}

.search-result-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}

.search-result-icon {
  font-size: 14px;
  line-height: 1;
  flex-shrink: 0;
}

.search-result-creator {
  font-size: 12px;
  color: var(--app-text-secondary);
  flex-shrink: 0;
}

.search-hint {
  padding: 24px;
  text-align: center;
  color: var(--app-text-placeholder);
  font-size: 13px;
}
</style>
