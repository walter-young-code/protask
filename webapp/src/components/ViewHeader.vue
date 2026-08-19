<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import {
  ArrowDown,
  Calendar,
  Delete,
  EditPen,
  Filter,
  Grid,
  List,
  Menu,
  Plus,
  Search,
  Sort,
  Star,
  StarFilled,
} from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type {
  CardProperty,
  FilterField,
  ViewConfig,
  ViewFilter,
  ViewSort,
  ViewType,
  ViewTab,
} from '@/types'

const props = defineProps<{
  views: ViewTab[]
  activeViewId: number
  /** 当前用户是否可管理视图（editor+） */
  editable: boolean
  keyword: string
  /** 项目卡片动态属性模板（筛选/排序字段选项来源） */
  cardProperties: CardProperty[]
  /** 当前可见任务数（筛选后） */
  visibleCount: number
  /** 全部任务数 */
  totalCount: number
  /** 项目默认视图 id（undefined=未设置） */
  defaultViewId?: number
}>()

const emit = defineEmits<{
  (e: 'switch', viewId: number): void
  (e: 'create', type: ViewType, title: string): void
  (e: 'rename', viewId: number, title: string): void
  (e: 'remove', viewId: number): void
  (e: 'setDefault', viewId: number): void
  (e: 'updateConfig', viewId: number, config: ViewConfig): void
  (e: 'update:keyword', keyword: string): void
}>()

const { t } = useI18n()

/** 视图类型图标 */
const VIEW_ICONS: Record<ViewType, typeof List> = {
  table: List,
  board: Menu,
  gallery: Grid,
  calendar: Calendar,
}

const VIEW_TYPES: ViewType[] = ['table', 'board', 'gallery', 'calendar']

const activeView = computed(() => props.views.find((v) => v.id === props.activeViewId))

/** 本地编辑副本：确认后整体提交 */
const filterDraft = ref<ViewFilter[]>([])
const sortDraft = ref<ViewSort[]>([])

const filterActive = computed(() => (activeView.value?.config.filters.length ?? 0) > 0)
const sortActive = computed(() => (activeView.value?.config.sorts.length ?? 0) > 0)

function openFilterPopover() {
  filterDraft.value = (activeView.value?.config.filters ?? []).map((f) => ({ ...f, values: [...f.values] }))
}

function openSortPopover() {
  sortDraft.value = (activeView.value?.config.sorts ?? []).map((s) => ({ ...s }))
}

function addFilterRow() {
  filterDraft.value.push({
    field: props.cardProperties[0]?.id ?? '',
    operator: 'includes',
    values: [],
  })
}

function removeFilterRow(index: number) {
  filterDraft.value.splice(index, 1)
}

function addSortRow() {
  sortDraft.value.push({ field: 'createdAt', direction: 'desc' })
}

function removeSortRow(index: number) {
  sortDraft.value.splice(index, 1)
}

/** 切换字段时重置运算符与值 */
function handleFilterFieldChange(filter: ViewFilter) {
  filter.operator = 'includes'
  filter.values = []
}

function commitFilters() {
  if (!activeView.value) return
  const filters = filterDraft.value.filter(
    (f) => f.operator === 'empty' || f.values.length > 0,
  )
  emit('updateConfig', activeView.value.id, { ...activeView.value.config, filters })
}

function commitSorts() {
  if (!activeView.value) return
  emit('updateConfig', activeView.value.id, { ...activeView.value.config, sorts: sortDraft.value })
}

function clearFilters() {
  filterDraft.value = []
  commitFilters()
}

/** 筛选值选项：来自属性的选项定义（无选项的属性仅支持 empty 运算符） */
function filterValueOptions(field: FilterField): { value: string; label: string }[] {
  const prop = props.cardProperties.find((p) => p.id === field)
  if (!prop) return []
  return (prop.options ?? []).map((option) => ({ value: option.id, label: option.value }))
}

async function handleRename(view: ViewTab) {
  let input: string
  try {
    const result = await ElMessageBox.prompt(t('view.renamePrompt'), t('view.rename'), {
      inputValue: view.title,
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      inputValidator: (value) =>
        (value != null && value.trim().length > 0 && value.trim().length <= 50) ||
        t('view.nameInvalid'),
    })
    input = result.value.trim()
  } catch {
    return
  }
  emit('rename', view.id, input)
}

async function handleRemove(view: ViewTab) {
  try {
    await ElMessageBox.confirm(t('view.deleteConfirm', { title: view.title }), t('view.delete'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  emit('remove', view.id)
}

async function handleCreate(type: ViewType) {
  let input: string
  try {
    const result = await ElMessageBox.prompt(t('view.namePrompt'), t('view.new'), {
      inputValue: t(`view.type.${type}`),
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      inputValidator: (value) =>
        (value != null && value.trim().length > 0 && value.trim().length <= 50) ||
        t('view.nameInvalid'),
    })
    input = result.value.trim()
  } catch {
    return
  }
  emit('create', type, input)
}

function handleSwitch(viewId: number) {
  if (viewId !== props.activeViewId) {
    emit('switch', viewId)
  }
}

/** 隐藏任务数提示（筛选生效时） */
const hiddenCount = computed(() => props.totalCount - props.visibleCount)
function notifyHidden() {
  if (hiddenCount.value > 0) {
    appMessage.info(t('view.hiddenCount', { count: hiddenCount.value }))
  }
}
</script>

<template>
  <div class="view-header">
    <!-- 视图 Tab 列表 -->
    <div class="view-tabs">
      <div
        v-for="view in views"
        :key="view.id"
        class="view-tab"
        :class="{ active: view.id === activeViewId }"
        @click="handleSwitch(view.id)"
      >
        <el-icon class="view-tab-icon"><component :is="VIEW_ICONS[view.config.viewType]" /></el-icon>
        <span class="view-tab-title">{{ view.title }}</span>
        <el-icon v-if="view.id === defaultViewId" class="view-tab-star">
          <StarFilled />
        </el-icon>
        <el-dropdown
          v-if="view.id === activeViewId && editable"
          trigger="click"
          @command="(cmd: string) =>
            cmd === 'rename'
              ? handleRename(view)
              : cmd === 'setDefault'
                ? emit('setDefault', view.id)
                : handleRemove(view)
          "
        >
          <el-icon class="view-tab-menu" @click.stop><ArrowDown /></el-icon>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename" :icon="EditPen">
                {{ t('view.rename') }}
              </el-dropdown-item>
              <el-dropdown-item command="setDefault" :icon="Star" :disabled="view.id === defaultViewId">
                {{ view.id === defaultViewId ? t('view.defaultSet') : t('view.setDefault') }}
              </el-dropdown-item>
              <el-dropdown-item command="delete" :icon="Delete" divided>
                {{ t('view.delete') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <!-- 新建视图 -->
      <el-dropdown v-if="editable" trigger="click" @command="handleCreate">
        <el-button link class="new-view-btn">
          <el-icon><Plus /></el-icon>
          {{ t('view.new') }}
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="type in VIEW_TYPES" :key="type" :command="type" :icon="VIEW_ICONS[type]">
              {{ t(`view.type.${type}`) }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="view-tools">
      <!-- 筛选 -->
      <el-popover width="520" trigger="click" @before-enter="openFilterPopover">
        <template #reference>
          <el-button text :type="filterActive ? 'primary' : ''">
            <el-icon><Filter /></el-icon>
            {{ t('view.filter') }}
          </el-button>
        </template>
        <div class="filter-panel">
          <div v-for="(filter, index) in filterDraft" :key="index" class="filter-row">
            <el-select
              v-model="filter.field"
              class="filter-field"
              :teleported="false"
              @change="handleFilterFieldChange(filter)"
            >
              <el-option
                v-for="prop in cardProperties"
                :key="prop.id"
                :value="prop.id"
                :label="prop.name"
              />
            </el-select>
            <el-select v-model="filter.operator" class="filter-operator" :teleported="false">
              <el-option value="includes" :label="t('filter.operator.includes')" />
              <el-option value="empty" :label="t('filter.operator.empty')" />
            </el-select>
            <el-select
              v-if="filter.operator === 'includes'"
              v-model="filter.values"
              multiple
              collapse-tags
              class="filter-values"
              :teleported="false"
            >
              <el-option
                v-for="option in filterValueOptions(filter.field)"
                :key="option.value"
                :value="option.value"
                :label="option.label"
              />
            </el-select>
            <span v-else class="filter-values-empty">—</span>
            <el-button link type="danger" :icon="Delete" @click="removeFilterRow(index)" />
          </div>
          <div class="filter-actions">
            <el-button link type="primary" @click="addFilterRow">
              + {{ t('filter.add') }}
            </el-button>
            <div class="filter-actions-right">
              <el-button v-if="filterDraft.length" link @click="clearFilters">
                {{ t('filter.clear') }}
              </el-button>
              <el-button type="primary" size="small" @click="commitFilters">
                {{ t('common.confirm') }}
              </el-button>
            </div>
          </div>
        </div>
      </el-popover>

      <!-- 排序 -->
      <el-popover width="420" trigger="click" @before-enter="openSortPopover">
        <template #reference>
          <el-button text :type="sortActive ? 'primary' : ''">
            <el-icon><Sort /></el-icon>
            {{ t('view.sort') }}
          </el-button>
        </template>
        <div class="sort-panel">
          <div v-for="(sort, index) in sortDraft" :key="index" class="sort-row">
            <el-select v-model="sort.field" class="sort-field" :teleported="false">
              <el-option value="title" :label="t('task.columns.title')" />
              <el-option value="createdAt" :label="t('sort.createdAt')" />
              <el-option
                v-for="prop in cardProperties"
                :key="prop.id"
                :value="prop.id"
                :label="prop.name"
              />
            </el-select>
            <el-select v-model="sort.direction" class="sort-direction" :teleported="false">
              <el-option value="asc" :label="t('sort.asc')" />
              <el-option value="desc" :label="t('sort.desc')" />
            </el-select>
            <el-button link type="danger" :icon="Delete" @click="removeSortRow(index)" />
          </div>
          <div class="sort-actions">
            <el-button link type="primary" @click="addSortRow">
              + {{ t('sort.add') }}
            </el-button>
            <el-button type="primary" size="small" @click="commitSorts">
              {{ t('common.confirm') }}
            </el-button>
          </div>
        </div>
      </el-popover>

      <!-- 被筛选隐藏的任务数提示 -->
      <el-tooltip v-if="hiddenCount > 0" :content="t('view.hiddenCount', { count: hiddenCount })">
        <el-tag size="small" type="info" effect="plain" class="hidden-tag" @click="notifyHidden">
          {{ visibleCount }} / {{ totalCount }}
        </el-tag>
      </el-tooltip>

      <!-- 视图内关键词搜索 -->
      <el-input
        :model-value="keyword"
        class="view-search"
        :placeholder="t('view.searchPlaceholder')"
        clearable
        :prefix-icon="Search"
        @update:model-value="emit('update:keyword', $event ?? '')"
      />
    </div>
  </div>
</template>

<style scoped>
.view-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.view-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.view-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: var(--app-text-regular);
  transition: background 0.2s;
}

.view-tab:hover {
  background: var(--el-color-primary-light-9);
}

.view-tab.active {
  background: var(--app-header-bg);
  color: #fff;
}

.view-tab-title {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.view-tab-menu {
  font-size: 12px;
}

.view-tab-star {
  font-size: 11px;
  color: var(--el-color-warning);
}

.new-view-btn {
  margin-left: 4px;
}

.view-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hidden-tag {
  cursor: pointer;
}

.view-search {
  width: 200px;
}

.filter-row,
.sort-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.filter-field,
.sort-field {
  width: 120px;
}

.filter-operator {
  width: 110px;
}

.filter-values {
  flex: 1;
}

.filter-values-empty {
  flex: 1;
  text-align: center;
  color: var(--app-text-placeholder);
}

.sort-direction {
  width: 120px;
}

.filter-actions,
.sort-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.filter-actions-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
