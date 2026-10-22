<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { CopyDocument, Delete, Document, Download, MoreFilled, Position, Upload, View } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import * as taskApi from '@/api/task'
import * as blockApi from '@/api/block'
import * as attachmentApi from '@/api/attachment'
import type { AttachmentVO } from '@/api/attachment'
import { useSiteStore } from '@/stores/site'
import PropertyValueInput from '@/components/PropertyValueInput.vue'
import IconPicker from '@/components/IconPicker.vue'
import type { BlockVO, CardProperty, ProjectMemberVO, TaskVO } from '@/types'

const props = defineProps<{
  modelValue: boolean
  task: TaskVO | null
  projectId: number
  /** 项目卡片动态属性模板 */
  cardProperties: CardProperty[]
  /** 当前用户是否可编辑任务（editor+） */
  editable: boolean
  /** 当前用户是否可评论（commenter+） */
  commentable: boolean
  /** 当前登录用户 ID（判断能否删除自己的评论） */
  currentUserId?: number
  /** 项目成员映射（userId -> 昵称），member 类型属性的选项来源 */
  memberMap?: Record<number, string>
  /** 项目成员列表（评论 @提及的候选来源，即成员管理中加入的成员） */
  members?: ProjectMemberVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 任务保存或删除后通知容器刷新，携带更新后的任务（如有） */
  (e: 'saved', task?: TaskVO): void
}>()

const { t } = useI18n()
const siteStore = useSiteStore()

const saving = ref(false)
const submittingComment = ref(false)
const comments = ref<BlockVO[]>([])
const commentContent = ref('')

const form = reactive({
  title: '',
  description: '',
  /** 任务图标（emoji，空串表示无图标） */
  icon: '',
  /** 动态属性值（按项目 cardProperties 模板全量渲染） */
  propertyValues: {} as Record<string, unknown>,
})

/** 描述编辑/预览模式（默认预览，双击预览区进入编辑） */
const descMode = ref<'edit' | 'preview'>('preview')

/** 描述编辑区原生 textarea（Markdown 工具条光标操作） */
const descEditorRef = ref<{ $el: HTMLElement }>()

/** 点击图片按钮时的选区快照：打开选择器到插入之间是异步的，插入位置以打开选择器时刻为准 */
let imageInsertRange: { start: number; end: number } | null = null

/** 图片选择器是否打开（工具条 🖼 按钮触发） */
const imagePickerVisible = ref(false)

/** 任务附件列表（分页加载，按上传时间倒序） */
const attachments = ref<AttachmentVO[]>([])

/** 服务端返回的附件总数（与已加载条数区分） */
const attachmentTotal = ref(0)

/** 已加载到的附件页码 */
const attachmentPage = ref(1)

/** 附件列表加载中标记 */
const loadingAttachments = ref(false)

/** 是否还有未加载的附件 */
const hasMoreAttachments = computed(() => attachments.value.length < attachmentTotal.value)

/** 附件上传中标记（按钮 loading） */
const uploadingAttachment = ref(false)

/** 单文件上传上限（字节，随后端 multipart 配置刷新） */
const maxAttachmentBytes = computed(() => siteStore.maxUploadBytes)

/** 隐藏的附件文件选择框（附件区上传按钮触发，accept 放开多类型） */
const attachmentInputRef = ref<HTMLInputElement>()

/** 描述中已引用的图片 URL 集合（附件区标记“已在描述中”，禁用重复插入） */
const descriptionImageUrls = computed<Set<string>>(() => {
  const urls = new Set<string>()
  for (const match of form.description.matchAll(/!\[[^\]]*\]\(([^)]+)\)/g)) {
    urls.add(match[1])
  }
  return urls
})

/** 附件区中的图片附件（可供描述插入） */
const imageAttachments = computed(() => attachments.value.filter((att) => isImageAttachment(att)))

/** 是否为图片附件（按存储名扩展名判定，与后端公开/私有存储策略一致） */
function isImageAttachment(att: AttachmentVO): boolean {
  return /\.(png|jpe?g|gif|webp|bmp)$/i.test(att.originalName)
}

/** 是否为 PDF 附件（contentType 或扩展名判定，兼容上传时未带 contentType 的情况） */
function isPdfAttachment(att: AttachmentVO): boolean {
  return att.contentType === 'application/pdf' || att.originalName.toLowerCase().endsWith('.pdf')
}

/** 新标签页在线打开 PDF（请求头鉴权获取 Blob 后打开，token 不出现在 URL） */
async function openPdfPreview(att: AttachmentVO) {
  try {
    await attachmentApi.previewAttachment(att.url)
  } catch {
    // 错误提示由请求拦截器统一处理
  }
}

/** 下载附件（请求头鉴权后通过 Blob 保存，token 不出现在 URL） */
async function downloadAttachment(att: AttachmentVO) {
  try {
    await attachmentApi.downloadAttachment(att.url, att.originalName)
  } catch {
    // 错误提示由请求拦截器统一处理
  }
}

/** 加载任务附件：reset=true 从第一页重载，否则加载下一页追加 */
async function loadAttachments(reset = true) {
  if (!props.task) return
  const page = reset ? 1 : attachmentPage.value + 1
  loadingAttachments.value = true
  try {
    const result = await attachmentApi.listAttachments(props.task.id, {
      page,
      size: attachmentApi.ATTACHMENT_PAGE_SIZE,
    })
    attachmentTotal.value = result.total
    attachmentPage.value = page
    attachments.value = reset ? result.records : [...attachments.value, ...result.records]
  } catch {
    // 错误提示由请求拦截器统一处理
  } finally {
    loadingAttachments.value = false
  }
}

/** 打开图片选择器：记录打开时刻的选区（插入位置以打开时为准） */
function openImagePicker() {
  const textarea = descTextarea()
  imageInsertRange = textarea
    ? { start: textarea.selectionStart, end: textarea.selectionEnd }
    : null
  imagePickerVisible.value = true
}

/** 从附件区选择图片：在快照位置插入 Markdown 并关闭选择器 */
async function insertAttachmentImage(att: AttachmentVO) {
  imagePickerVisible.value = false
  await insertImageSyntax(att.url)
}

/**
 * 选择附件文件：大小/分辨率预校验后多选串行上传（支持连续选择同一文件）。
 * 单个文件失败不阻断剩余文件，失败提示由请求拦截器统一处理。
 */
async function handleAttachmentSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 清空以便再次选择同一文件
  const task = props.task
  if (!files.length || !task) return
  const accepted: File[] = []
  for (const file of files) {
    if (file.size > maxAttachmentBytes.value) {
      appMessage.warning(
        t('attachment.tooLarge', { size: formatFileSize(maxAttachmentBytes.value) }),
      )
      continue
    }
    if (file.type.startsWith('image/')) {
      const sizeOk = await checkImageSize(file)
      if (!sizeOk) continue
    }
    accepted.push(file)
  }
  if (!accepted.length) return
  uploadingAttachment.value = true
  let success = 0
  try {
    for (const file of accepted) {
      try {
        await attachmentApi.uploadAttachment(task.id, file)
        success++
      } catch {
        // 单个文件失败已由请求拦截器提示，继续上传剩余文件
      }
    }
    if (success === 1) {
      appMessage.success(t('attachment.uploadSuccess'))
    } else if (success > 1) {
      appMessage.success(t('attachment.uploadSuccessCount', { count: success }))
    }
    await loadAttachments()
    // 同步容器中的卡片附件数徽标
    if (success > 0) {
      emit('saved', { ...task, attachmentCount: attachmentTotal.value })
    }
  } finally {
    uploadingAttachment.value = false
  }
}

/** 删除附件：被描述引用时提示将同步移除引用；确认后后端在事务内删除文件与记录并移除描述引用 */
async function handleDeleteAttachment(att: AttachmentVO) {
  const task = props.task
  if (!task) return
  const inUse = descriptionImageUrls.value.has(att.url)
  try {
    await ElMessageBox.confirm(
      inUse ? t('attachment.deleteInUseConfirm') : t('attachment.deleteConfirm'),
      t('common.delete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  try {
    await attachmentApi.deleteAttachment(task.id, att.id)
    appMessage.success(t('attachment.deleted'))
    // 后端已同步移除描述中的 Markdown 引用：前端同样移除并复位未保存标记
    const escaped = att.url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    form.description = form.description.replace(new RegExp(`!\\[[^\\]]*\\]\\(${escaped}\\)`, 'g'), '')
    initialSnapshot.value = snapshotForm()
    await loadAttachments()
    emit('saved', { ...task, description: form.description, attachmentCount: attachmentTotal.value })
  } catch {
    // 错误提示已由请求拦截器统一处理
  }
}

/** 格式化文件大小显示 */
function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

/** 表单初始快照（打开弹窗时的值，用于判断是否存在未保存修改） */
const initialSnapshot = ref('')

/** 表单当前状态序列化（标题/描述/属性值任一变化都会改变快照） */
function snapshotForm(): string {
  return JSON.stringify({ title: form.title, icon: form.icon, description: form.description, propertyValues: form.propertyValues })
}

/** 是否有未保存修改（预览模式下改动标题/属性后据此显示保存按钮） */
const formDirty = computed(() => snapshotForm() !== initialSnapshot.value)

/** Markdown 工具条动作定义 */
interface MarkdownAction {
  key: string
  /** 按钮显示文本 */
  label: string
  /** tooltip */
  title: string
  /** 插入/包裹前缀 */
  before: string
  /** 包裹后缀（空则仅插入前缀） */
  after?: string
  /** 无选区时的占位文本 */
  placeholder?: string
}

/** 工具条按钮列表（文案随语言切换） */
const markdownActions = computed<MarkdownAction[]>(() => [
  { key: 'bold', label: 'B', title: t('markdown.bold'), before: '**', after: '**', placeholder: t('markdown.boldText') },
  { key: 'italic', label: 'I', title: t('markdown.italic'), before: '*', after: '*', placeholder: t('markdown.italicText') },
  { key: 'strike', label: 'S', title: t('markdown.strike'), before: '~~', after: '~~' },
  { key: 'heading1', label: 'H1', title: t('markdown.heading1'), before: '# ', placeholder: t('markdown.headingText') },
  { key: 'heading2', label: 'H2', title: t('markdown.heading2'), before: '## ' },
  { key: 'quote', label: '❝', title: t('markdown.quote'), before: '> ' },
  { key: 'code', label: '`', title: t('markdown.code'), before: '`', after: '`', placeholder: t('markdown.codeText') },
  { key: 'codeBlock', label: '</>', title: t('markdown.codeBlock'), before: '\n```\n', after: '\n```\n' },
  { key: 'bulletList', label: '•', title: t('markdown.bulletList'), before: '- ' },
  { key: 'orderedList', label: '1.', title: t('markdown.orderedList'), before: '1. ' },
  { key: 'task', label: '☑', title: t('markdown.task'), before: '- [ ] ' },
  { key: 'link', label: '↗', title: t('markdown.link'), before: '[', after: '](url)', placeholder: t('markdown.linkText') },
  { key: 'image', label: '🖼', title: t('markdown.image'), before: '', after: '', placeholder: '' },
  { key: 'table', label: 'Tb', title: t('markdown.table'), before: '| 列1 | 列2 |\n| --- | --- |\n|  |  |\n' },
])

/** 获取描述编辑区原生 textarea */
function descTextarea(): HTMLTextAreaElement | null {
  return descEditorRef.value?.$el?.querySelector('textarea') ?? null
}

/** 在光标/选区处插入或包裹 Markdown 语法，并恢复焦点与选区（图片动作走文件上传） */
async function applyMarkdownSyntax(action: MarkdownAction) {
  const textarea = descTextarea()
  if (!textarea) return
  if (action.key === 'image') {
    openImagePicker()
    return
  }
  if (document.activeElement !== textarea) {
    textarea.focus()
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)
  }
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const selected = form.description.slice(start, end)
  const inner = selected || action.placeholder || ''
  form.description =
    form.description.slice(0, start) + action.before + inner + (action.after ?? '') + form.description.slice(end)
  requestAnimationFrame(() => {
    textarea.focus()
    const caret = start + action.before.length
    textarea.setSelectionRange(caret, caret + inner.length)
  })
}

/** 图片分辨率上限：宽 ≤ 1280 且高 ≤ 720，超限提醒并拒绝 */
const IMAGE_MAX_WIDTH = 1280
const IMAGE_MAX_HEIGHT = 720

/** 读取图片分辨率，超过上限时提醒并返回 false */
function checkImageSize(file: File): Promise<boolean> {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(objectUrl)
      if (image.naturalWidth > IMAGE_MAX_WIDTH || image.naturalHeight > IMAGE_MAX_HEIGHT) {
        appMessage.warning(t('markdown.imageSizeLimit'))
        resolve(false)
      } else {
        resolve(true)
      }
    }
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      appMessage.error(t('attachment.invalidImage'))
      resolve(false)
    }
    image.src = objectUrl
  })
}

/**
 * 上传成功后，在点击按钮时的位置插入图片语法。
 * 光标定位到语法末尾（不选中 alt，避免继续输入误替换 alt，以及连续插入时选区残留造成嵌套损坏）。
 */
async function insertImageSyntax(url: string) {
  let textarea = descTextarea()
  if (!textarea) {
    // 上传期间描述区被切到预览/卸载：切回编辑模式，等渲染完成后再插入
    if (!props.modelValue) return
    descMode.value = 'edit'
    await nextTick()
    textarea = descTextarea()
    if (!textarea) return
  }
  // 快照一次性使用（点击按钮时刻的选区）；无快照时退回当前选区
  const range = imageInsertRange ?? { start: textarea.selectionStart, end: textarea.selectionEnd }
  imageInsertRange = null
  const { start, end } = range
  const alt = form.description.slice(start, end) || t('markdown.imageAltDefault')
  const syntax = `![${alt}](${url})`
  form.description = form.description.slice(0, start) + syntax + form.description.slice(end)
  requestAnimationFrame(() => {
    textarea.focus()
    const caret = start + syntax.length // 定位到图片语法之后，继续输入直接跟在图片后面
    textarea.setSelectionRange(caret, caret)
  })
}
const renderedDescription = computed(() => {
  const raw = form.description || ''
  if (!raw.trim()) return ''
  return DOMPurify.sanitize(marked.parse(raw, { gfm: true, breaks: true }) as string)
})

function initForm() {
  if (!props.task) return
  form.title = props.task.title
  form.description = props.task.description || ''
  form.icon = props.task.icon ?? ''
  form.propertyValues = {}
  for (const prop of props.cardProperties ?? []) {
    form.propertyValues[prop.id] = props.task.properties?.[prop.id] ?? ''
  }
  initialSnapshot.value = snapshotForm()
}

/** 空值判定（保存时过滤，避免写入无意义属性值） */
function isEmptyValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  )
}

/** 加载当前任务的评论（comment 块按 parentId 过滤） */
async function loadComments() {
  if (!props.task) return
  const blocks = await blockApi.listBlocks(props.projectId, 'comment')
  comments.value = blocks
    .filter((block) => block.parentId === props.task?.id)
    .sort((a, b) => a.createAt.localeCompare(b.createAt))
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    initForm()
    commentContent.value = ''
    closeMention()
    mentionedUsers.value.clear()
    descMode.value = 'preview'
    loadComments()
    loadAttachments()
  },
)

function close() {
  emit('update:modelValue', false)
}

/** 关闭前拦截：有未保存修改时弹窗询问（保存并关闭 / 放弃修改 / 继续编辑） */
async function handleBeforeClose(done: () => void) {
  if (!formDirty.value) {
    done()
    return
  }
  try {
    await ElMessageBox.confirm(t('taskDetail.unsavedConfirm'), t('taskDetail.unsavedTitle'), {
      type: 'warning',
      confirmButtonText: t('taskDetail.saveAndClose'),
      cancelButtonText: t('taskDetail.discard'),
      distinguishCancelAndClose: true,
    })
    await handleSave()
    // 保存成功（快照已复位）才关闭；校验未过（如标题为空）则留在窗口
    if (!formDirty.value) {
      done()
    }
  } catch (action) {
    // 取消（放弃修改）直接关闭；右上角 X 关闭询问框则继续编辑
    if (action === 'cancel') {
      done()
    }
  }
}

/** 双击描述预览区进入编辑模式（仅可编辑时响应） */
function handleDescDblClick() {
  if (props.editable) {
    descMode.value = 'edit'
  }
}

/** 回车保存：标题输入框回车即保存（阻止表单隐式提交） */
function handleTitleEnter(event: Event) {
  event.preventDefault()
  handleSave()
}

/** 回车保存：属性区单行输入框回车即保存（下拉选择/多行输入除外） */
function handlePropertyEnter(event: Event) {
  const target = event.target as HTMLElement
  // 下拉选择面板内回车是“选中选项”，不触发保存（选中本身已触发保存）
  if (target.closest('.el-select')) return
  // 多行文本回车是换行，不触发保存
  if (target.tagName === 'TEXTAREA') return
  event.preventDefault()
  handleSave()
}

/** 属性值变更：选择类属性（单选/多选/复选/人员）选中即保存，文本/数字/日期等回车保存 */
function onPropertyValueChange(prop: CardProperty, value: unknown) {
  form.propertyValues[prop.id] = value
  if (prop.type === 'select' || prop.type === 'multiSelect' || prop.type === 'checkbox' || prop.type === 'member') {
    handleSave()
  }
}

async function handleSave() {
  if (!props.task) return
  if (!form.title.trim()) {
    appMessage.warning(t('validation.taskTitleRequired'))
    return
  }
  saving.value = true
  try {
    const updated = await taskApi.updateTask(props.task.id, {
      title: form.title.trim(),
      icon: form.icon,
      description: form.description || undefined,
      properties: Object.fromEntries(
        Object.entries(form.propertyValues).filter(([, value]) => !isEmptyValue(value)),
      ),
    })
    initialSnapshot.value = snapshotForm() // 保存成功后重拍快照，未保存标记复位
    appMessage.success(t('task.updated'))
    emit('saved', updated)
  } finally {
    saving.value = false
  }
}

async function handleDeleteTask() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      t('task.deleteConfirm', { title: props.task.title }),
      t('task.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
  } catch {
    return
  }
  await taskApi.deleteTask(props.task.id)
  appMessage.success(t('task.deleted'))
  close()
  emit('saved')
}

/** 复制任务：有未保存修改时先保存，保证复制内容与当前表单一致；复制后保留弹窗并刷新列表 */
async function handleCopyTask() {
  if (!props.task) return
  if (formDirty.value) {
    await handleSave()
    if (formDirty.value) return // 校验未通过（如标题为空），中止复制
  }
  await taskApi.copyTask(props.task.id)
  appMessage.success(t('task.copied'))
  emit('saved')
}

/** 头部“更多”菜单命令分发（复制 / 删除） */
function handleHeaderCommand(command: string) {
  if (command === 'copy') {
    void handleCopyTask()
  } else if (command === 'delete') {
    void handleDeleteTask()
  }
}

async function handleSubmitComment() {
  if (!props.task || !commentContent.value.trim()) return
  submittingComment.value = true
  // 仅保留文本中仍存在的 @提及（手打昵称或删除的提及不产生通知）
  const mentionUserIds = [...mentionedUsers.value.entries()]
    .filter(([, label]) => commentContent.value.includes(`@${label}`))
    .map(([userId]) => userId)
  try {
    await blockApi.createBlock(props.projectId, {
      type: 'comment',
      parentId: props.task.id,
      title: commentContent.value.trim(),
      mentionUserIds,
    })
    commentContent.value = ''
    closeMention()
    mentionedUsers.value.clear()
    await loadComments()
  } finally {
    submittingComment.value = false
  }
}

async function handleDeleteComment(comment: BlockVO) {
  await blockApi.deleteBlock(comment.id)
  appMessage.success(t('comment.deleted'))
  await loadComments()
}

/** 评论输入框原生 el-input 引用（读取光标位置与面板滚动定位） */
const commentInputRef = ref<{ $el: HTMLElement }>()

/** @提及触发位置（@ 字符下标），-1 表示未激活 */
const mentionStart = ref(-1)
/** 当前 @提及过滤词（@ 与光标之间的文本） */
const mentionQuery = ref('')
/** 提及面板中高亮的候选下标（键盘上下选择） */
const mentionActiveIndex = ref(0)

/** @提及候选成员 */
interface MentionCandidate {
  userId: number
  /** 展示与插入用名称（昵称优先） */
  label: string
  /** 姓名（辅助显示，未填写时为空） */
  fullName?: string
}

/** 提及候选：项目成员管理中加入的成员，按过滤词模糊匹配昵称/姓名 */
const mentionCandidates = computed<MentionCandidate[]>(() => {
  const list = (props.members ?? []).map((m) => ({
    userId: m.userId,
    label: m.nickname ?? m.username ?? String(m.userId),
    fullName: m.fullName,
  }))
  const q = mentionQuery.value.trim().toLowerCase()
  if (!q) return list
  return list.filter(
    (c) => c.label.toLowerCase().includes(q) || c.fullName?.toLowerCase().includes(q),
  )
})

/** 提及面板是否展开 */
const mentionOpen = computed(() => mentionStart.value >= 0)

/** 获取评论输入框原生 textarea */
function commentTextarea(): HTMLTextAreaElement | null {
  return commentInputRef.value?.$el?.querySelector('textarea') ?? null
}

function closeMention() {
  mentionStart.value = -1
  mentionQuery.value = ''
  mentionActiveIndex.value = 0
}

/** 检测光标前是否存在未结束的 @提及，刷新候选面板（@ 需在行首或空白后，过滤词不含空白） */
function refreshMentionState() {
  const textarea = commentTextarea()
  if (!textarea) {
    closeMention()
    return
  }
  const cursor = textarea.selectionStart
  const before = commentContent.value.slice(0, cursor)
  const match = /(^|[\s\n])([@＠])([^\s@＠]*)$/.exec(before)
  if (!match) {
    closeMention()
    return
  }
  mentionStart.value = cursor - match[3].length - 1
  mentionQuery.value = match[3]
  mentionActiveIndex.value = 0
}

/** 面板打开时的键盘导航：上下切换、Enter/Tab 确认、Esc 关闭 */
function handleCommentKeydown(evt: Event | KeyboardEvent) {
  if (!mentionOpen.value) return
  const event = evt as KeyboardEvent
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!mentionCandidates.value.length) return
    const delta = event.key === 'ArrowDown' ? 1 : -1
    const len = mentionCandidates.value.length
    mentionActiveIndex.value = (mentionActiveIndex.value + delta + len) % len
    nextTick(() => {
      commentInputRef.value?.$el
        ?.querySelector('.mention-item.active')
        ?.scrollIntoView({ block: 'nearest' })
    })
  } else if (
    (event.key === 'Enter' || event.key === 'Tab') &&
    !event.ctrlKey && !event.metaKey && !event.shiftKey
  ) {
    if (!mentionCandidates.value.length) return
    event.preventDefault()
    insertMention(mentionCandidates.value[mentionActiveIndex.value])
  } else if (event.key === 'Escape') {
    event.preventDefault()
    closeMention()
  }
}

/** 本次输入中通过选择器插入的提及（userId -> 成员名），提交时用于生成通知 */
const mentionedUsers = ref<Map<number, string>>(new Map())

/** 选中候选：将触发处的 "@过滤词" 替换为 "@成员名 " 并恢复焦点光标 */
function insertMention(candidate: MentionCandidate) {
  const textarea = commentTextarea()
  const start = mentionStart.value
  const end = textarea?.selectionStart ?? commentContent.value.length
  const insert = `@${candidate.label} `
  commentContent.value = commentContent.value.slice(0, start) + insert + commentContent.value.slice(end)
  mentionedUsers.value.set(candidate.userId, candidate.label)
  closeMention()
  requestAnimationFrame(() => {
    if (!textarea) return
    textarea.focus()
    const caret = start + insert.length
    textarea.setSelectionRange(caret, caret)
  })
}

/** 将评论文本切分为普通文本与 @提及片段（项目成员提及高亮展示） */
function mentionSegments(content: string): { text: string; mention: boolean }[] {
  const names = [
    ...new Set(
      (props.members ?? []).map((m) => m.nickname ?? m.username).filter((n): n is string => !!n),
    ),
  ]
  if (!names.length || !content.includes('@')) {
    return [{ text: content, mention: false }]
  }
  const escaped = names
    .sort((a, b) => b.length - a.length)
    .map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const parts = content.split(new RegExp(`@(${escaped.join('|')})`, 'g'))
  const segments: { text: string; mention: boolean }[] = []
  for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue
    segments.push(
      i % 2 === 1 ? { text: `@${parts[i]}`, mention: true } : { text: parts[i], mention: false },
    )
  }
  return segments
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    width="960px"
    top="6vh"
    :show-close="true"
    :close-on-click-modal="false"
    class="task-detail-dialog"
    :before-close="handleBeforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="detail-header">
        <el-icon class="detail-header-icon"><Position /></el-icon>
        <span class="detail-header-hint">{{ t('taskDetail.title') }}</span>
        <!-- 复制/删除折叠入“更多”菜单，避免头部按钮过多 -->
        <el-dropdown
          v-if="editable"
          trigger="click"
          class="detail-more"
          @command="handleHeaderCommand"
        >
          <el-button link class="detail-more-btn" :title="t('common.more')">
            <el-icon :size="18"><MoreFilled /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="copy" :icon="CopyDocument">
                {{ t('task.copy') }}
              </el-dropdown-item>
              <el-dropdown-item
                command="delete"
                :icon="Delete"
                style="color: var(--el-color-danger)"
              >
                {{ t('common.delete') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </template>

    <div v-if="task" class="detail-body">
      <!-- 标题与描述 -->
      <div class="detail-title-row">
        <IconPicker v-model="form.icon" :readonly="!editable" addable />
        <el-input
          v-if="editable"
          v-model="form.title"
          class="detail-title"
          size="large"
          maxlength="200"
          :placeholder="t('task.titlePlaceholder')"
          @keydown.enter="handleTitleEnter"
        />
        <h3 v-else class="detail-title-readonly">{{ task.title }}</h3>
      </div>

      <!-- 属性区：单列布局（参照 Focalboard 属性行：名称固定宽 + 值）；回车即保存 -->
      <el-form
        class="property-form"
        label-width="150px"
        :disabled="!editable"
        @keydown.enter="handlePropertyEnter"
      >
        <el-form-item
          v-for="prop in props.cardProperties ?? []"
          :key="prop.id"
          :label="prop.name"
          class="property-item"
        >
          <PropertyValueInput
            :property="prop"
            :model-value="form.propertyValues[prop.id]"
            :member-map="props.memberMap"
            @update:model-value="onPropertyValueChange(prop, $event)"
          />
        </el-form-item>
        <el-empty
          v-if="(props.cardProperties ?? []).length === 0"
          :description="t('taskDetail.noProperties')"
          :image-size="40"
        />
      </el-form>

      <!-- 描述区：Markdown 编辑/预览（移至属性下方） -->
      <div v-if="editable" class="desc-toolbar">
        <div v-if="descMode === 'edit'" class="markdown-actions">
          <template v-for="action in markdownActions" :key="action.key">
            <el-tooltip v-if="action.key !== 'image'" :content="action.title" placement="top">
              <el-button
                text
                size="small"
                class="markdown-action-btn"
                @click="applyMarkdownSyntax(action)"
              >
                {{ action.label }}
              </el-button>
            </el-tooltip>
            <!-- 图片按钮：打开附件图片选择器，从本任务附件区选择已上传的图片插入 -->
            <el-popover
              v-else
              v-model:visible="imagePickerVisible"
              placement="bottom-start"
              :width="340"
              trigger="click"
              @show="openImagePicker"
            >
              <template #reference>
                <el-button text size="small" class="markdown-action-btn">
                  {{ action.label }}
                </el-button>
              </template>
              <div v-if="imageAttachments.length" class="image-picker-grid">
                <div
                  v-for="att in imageAttachments"
                  :key="att.id"
                  class="image-picker-item"
                  @click="insertAttachmentImage(att)"
                >
                  <img :src="att.url" class="image-picker-thumb" :alt="att.originalName" />
                  <span class="image-picker-name">{{ att.originalName }}</span>
                </div>
              </div>
              <el-empty v-else :description="t('attachment.noImageHint')" :image-size="40" />
            </el-popover>
          </template>
        </div>
        <el-radio-group v-model="descMode" size="small">
          <el-radio-button value="edit">{{ t('taskDetail.edit') }}</el-radio-button>
          <el-radio-button value="preview">{{ t('taskDetail.preview') }}</el-radio-button>
        </el-radio-group>
      </div>
      <div v-if="editable && descMode === 'edit'" class="detail-desc-edit-area">
        <el-input
          ref="descEditorRef"
          v-model="form.description"
          type="textarea"
          class="detail-desc-editor"
          :autosize="{ minRows: 2, maxRows: 20 }"
          :placeholder="t('task.descriptionPlaceholder')"
          @keydown.ctrl.enter="handleSave"
        />
        <!-- 已插入图片列表已迁移至附件区（图片附件可插入描述/删除） -->
      </div>
      <div v-else class="markdown-body" @dblclick="handleDescDblClick">
        <div v-if="renderedDescription" class="markdown-content" v-html="renderedDescription" />
        <p v-else class="detail-desc-empty">{{ t('taskDetail.noDescription') }}</p>
      </div>

      <!-- 附件区：上传/管理任务附件（图片可插入描述；删除被引用图片时后端同步移除描述引用） -->
      <div class="attachment-section">
        <div class="attachment-header">
          <span class="attachment-title">{{ t('attachment.title') }}</span>
          <span v-if="attachmentTotal" class="attachment-count">{{ attachmentTotal }}</span>
          <el-button
            v-if="editable"
            size="small"
            type="primary"
            plain
            class="attachment-upload-btn"
            :loading="uploadingAttachment"
            @click="attachmentInputRef?.click()"
          >
            <el-icon class="attachment-upload-icon"><Upload /></el-icon>
            {{ t('attachment.upload') }}
          </el-button>
          <!-- 隐藏的文件选择框：附件区上传按钮触发，支持多选（串行上传） -->
          <input
            ref="attachmentInputRef"
            type="file"
            class="attachment-file-input"
            multiple
            @change="handleAttachmentSelected"
          />
        </div>
        <el-empty v-if="!attachments.length" :description="t('attachment.empty')" :image-size="40" />
        <div v-else class="attachment-list">
          <div v-for="att in attachments" :key="att.id" class="attachment-item">
            <img
              v-if="isImageAttachment(att)"
              :src="att.url"
              class="attachment-thumb"
              :alt="att.originalName"
            />
            <div v-else class="attachment-icon">
              <el-icon><Document /></el-icon>
            </div>
            <div class="attachment-info">
              <div class="attachment-name" :title="att.originalName">{{ att.originalName }}</div>
              <div class="attachment-meta">
                {{ formatFileSize(att.size) }} · {{ att.createdByName }} · {{ formatTime(att.createdAt) }}
              </div>
            </div>
            <div class="attachment-actions">
              <el-button
                v-if="editable && isImageAttachment(att)"
                link
                type="primary"
                size="small"
                :disabled="descriptionImageUrls.has(att.url)"
                @click="insertAttachmentImage(att)"
              >
                {{ descriptionImageUrls.has(att.url) ? t('attachment.inserted') : t('attachment.insertImage') }}
              </el-button>
              <el-button
                v-if="commentable && isPdfAttachment(att)"
                link
                type="primary"
                size="small"
                :icon="View"
                @click="openPdfPreview(att)"
              >
                {{ t('attachment.preview') }}
              </el-button>
              <a
                v-if="commentable"
                class="attachment-download"
                :href="att.url"
                :title="t('attachment.download')"
                @click.prevent="downloadAttachment(att)"
              >
                <el-icon><Download /></el-icon>
                <span>{{ t('attachment.download') }}</span>
              </a>
              <el-button
                v-if="editable"
                link
                type="danger"
                size="small"
                :icon="Delete"
                @click="handleDeleteAttachment(att)"
              />
            </div>
          </div>
        </div>
        <!-- 分页加载：已加载条数少于服务端总数时显示 -->
        <div v-if="hasMoreAttachments" class="attachment-more">
          <el-button link type="primary" size="small" :loading="loadingAttachments" @click="loadAttachments(false)">
            {{ t('attachment.loadMore') }}
          </el-button>
        </div>
      </div>

      <!-- 保存按钮：编辑模式或存在未保存修改时显示（纯浏览态隐藏） -->
      <div v-if="editable && (descMode === 'edit' || formDirty)" class="detail-save">
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ t('common.save') }}
        </el-button>
      </div>

      <!-- 评论区 -->
      <div class="comment-list">
        <div v-for="comment in comments" :key="comment.id" class="comment-item">
          <el-avatar :size="28" class="comment-avatar">
            {{ comment.createdByName?.charAt(0) || 'U' }}
          </el-avatar>
          <div class="comment-main">
            <div class="comment-meta">
              <span class="comment-author">{{ comment.createdByName }}</span>
              <span class="comment-time">{{ formatTime(comment.createAt) }}</span>
              <el-button
                v-if="currentUserId != null && comment.createdBy === currentUserId"
                link
                type="danger"
                size="small"
                :icon="Delete"
                @click="handleDeleteComment(comment)"
              />
            </div>
            <div class="comment-content">
              <template v-for="(segment, idx) in mentionSegments(comment.title)" :key="idx">
                <span v-if="segment.mention" class="comment-mention">{{ segment.text }}</span>
                <template v-else>{{ segment.text }}</template>
              </template>
            </div>
          </div>
        </div>
        <el-empty
          v-if="comments.length === 0"
          :description="t('comment.empty')"
          :image-size="40"
        />
      </div>

      <div v-if="commentable" class="comment-input">
        <div class="comment-input-wrap">
          <el-input
            ref="commentInputRef"
            v-model="commentContent"
            type="textarea"
            :rows="2"
            :placeholder="t('comment.placeholder')"
            @keydown="handleCommentKeydown"
            @keydown.ctrl.enter="handleSubmitComment"
            @input="refreshMentionState"
            @keyup="refreshMentionState"
            @click="refreshMentionState"
          />
          <!-- @提及候选面板：范围为项目成员管理中加入的成员 -->
          <div v-if="mentionOpen" class="mention-panel">
            <div v-if="mentionCandidates.length" class="mention-list">
              <div
                v-for="(candidate, idx) in mentionCandidates"
                :key="candidate.userId"
                class="mention-item"
                :class="{ active: idx === mentionActiveIndex }"
                @mousedown.prevent="insertMention(candidate)"
                @mouseenter="mentionActiveIndex = idx"
              >
                <el-avatar :size="22" class="mention-avatar">{{ candidate.label.charAt(0) }}</el-avatar>
                <span class="mention-name">{{ candidate.label }}</span>
                <span v-if="candidate.fullName" class="mention-fullname">
                  {{ candidate.fullName }}
                </span>
              </div>
            </div>
            <div v-else class="mention-empty">{{ t('comment.mentionNoMatch') }}</div>
          </div>
        </div>
        <el-button
          type="primary"
          :loading="submittingComment"
          :disabled="!commentContent.trim()"
          @click="handleSubmitComment"
        >
          {{ t('comment.submit') }}
        </el-button>
      </div>
      <el-alert
        v-else
        :title="t('comment.noPermission')"
        type="info"
        :closable="false"
        class="comment-no-permission"
      />
    </div>
  </el-dialog>
</template>

<style scoped>
.detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-header-icon {
  color: var(--el-color-primary);
}

.detail-header-hint {
  font-size: 14px;
  color: var(--app-text-secondary);
}

.detail-more {
  margin-left: auto;
  margin-right: 24px;
}

.detail-more-btn {
  padding: 4px;
  color: var(--app-text-secondary);
}

.detail-more-btn:hover {
  color: var(--el-color-primary);
}

.detail-title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-title {
  margin-bottom: 0;
}

.detail-title :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
}

.detail-title :deep(.el-input__wrapper:hover) {
  background: var(--app-fill);
}

.detail-title :deep(.el-input__wrapper.is-focus) {
  background: var(--el-color-primary-light-9);
}

.detail-title :deep(.el-input__inner) {
  font-size: 20px;
  font-weight: 600;
  text-align: center;
}

.detail-title-readonly {
  margin: 0 0 12px;
  font-size: 20px;
  font-weight: 600;
  text-align: center;
}

/* 属性区：无边框控件，hover/聚焦浅色背景反馈；底部分隔线与描述区隔开 */
.property-form {
  border-bottom: 1px solid var(--app-border-light);
  padding-bottom: 16px;
}

.property-form :deep(.el-input__wrapper),
.property-form :deep(.el-select__wrapper) {
  box-shadow: none;
  background: transparent;
}

.property-form :deep(.el-input__wrapper:hover),
.property-form :deep(.el-select__wrapper:hover) {
  background: var(--app-fill);
}

.property-form :deep(.el-input__wrapper.is-focus),
.property-form :deep(.el-select__wrapper.is-focused) {
  background: var(--el-color-primary-light-9);
}

.detail-body {
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding-right: 4px;
}

.detail-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-primary);
  margin: 16px 0 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--app-border-light);
}

.desc-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 16px 0 8px;
}

.markdown-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}

.markdown-action-btn {
  min-width: 26px;
  padding: 2px 4px;
  font-size: 13px;
  font-weight: 600;
}

/* 附件区：上传按钮 + 附件列表（图片缩略图/文件图标 + 元信息 + 操作） */
.attachment-section {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border-light);
}

.attachment-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.attachment-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.attachment-count {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.attachment-upload-btn {
  margin-left: auto;
}

.attachment-upload-icon {
  margin-right: 4px;
}

/* 隐藏的附件文件选择框（附件区上传按钮触发） */
.attachment-file-input {
  display: none;
}

.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 分页“加载更多”：居中轻量按钮 */
.attachment-more {
  display: flex;
  justify-content: center;
  margin-top: 6px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--app-fill);
}

.attachment-thumb {
  width: 44px;
  height: 44px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
}

.attachment-icon {
  width: 44px;
  height: 44px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 22px;
}

.attachment-info {
  flex: 1;
  min-width: 0;
}

.attachment-download {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  height: 24px;
  padding: 0 6px;
  font-size: 12px;
  color: var(--el-color-primary);
  text-decoration: none;
  border-radius: 4px;
}

.attachment-download:hover {
  background: var(--el-color-primary-light-9);
}

.attachment-name {
  font-size: 13px;
  color: var(--app-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.attachment-meta {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-top: 2px;
}

.attachment-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* 附件图片选择器：缩略图网格 */
.image-picker-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.image-picker-item {
  cursor: pointer;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--app-border-light);
  transition: border-color 0.2s;
}

.image-picker-item:hover {
  border-color: var(--el-color-primary);
}

.image-picker-thumb {
  width: 100%;
  height: 64px;
  object-fit: cover;
  display: block;
}

.image-picker-name {
  display: block;
  padding: 4px 6px;
  font-size: 12px;
  color: var(--app-text-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 编辑/预览切换固定在右侧，不被工具条挤压 */
.desc-toolbar :deep(.el-radio-group) {
  margin-left: auto;
  flex-shrink: 0;
}

.detail-desc-empty {
  margin: 0;
  color: var(--app-text-placeholder);
  font-size: 13px;
}

/* 编辑模式：无边框输入区，高度跟随内容动态增长 */
.detail-desc-editor :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: var(--app-fill);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.6;
  transition: background 0.2s;
}

.detail-desc-editor :deep(.el-textarea__inner:focus) {
  background: var(--el-color-primary-light-9);
}

.detail-save {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* Markdown 渲染样式（v-html 内容经 :deep 命中） */
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  color: var(--app-text-primary);
  line-height: 1.4;
}

.markdown-content :deep(h1) {
  font-size: 20px;
}

.markdown-content :deep(h2) {
  font-size: 18px;
}

.markdown-content :deep(h3) {
  font-size: 16px;
}

.markdown-content :deep(h4) {
  font-size: 14px;
}

.markdown-content :deep(p) {
  margin: 6px 0;
  line-height: 1.6;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 20px;
  margin: 6px 0;
}

.markdown-content :deep(li) {
  margin: 2px 0;
}

.markdown-content :deep(code) {
  background: var(--app-fill);
  border-radius: 4px;
  padding: 1px 5px;
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
}

.markdown-content :deep(pre) {
  background: var(--app-fill);
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
}

.markdown-content :deep(pre code) {
  background: none;
  padding: 0;
}

.markdown-content :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--app-border);
  color: var(--app-text-secondary);
}

.markdown-content :deep(a) {
  color: var(--el-color-primary);
}

.markdown-content :deep(img) {
  max-width: 100%;
  border-radius: 6px;
}

.markdown-content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
}

.markdown-content :deep(th),
.markdown-content :deep(td) {
  border: 1px solid var(--app-border);
  padding: 4px 8px;
  font-size: 13px;
}

.comment-list {
  min-height: 40px;
}

.comment-item {
  display: flex;
  gap: 10px;
  padding: 8px 0;
}

.comment-avatar {
  background: var(--el-color-primary);
  color: #fff;
  flex-shrink: 0;
}

.comment-main {
  flex: 1;
  min-width: 0;
}

.comment-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.comment-author {
  font-weight: 600;
  color: var(--app-text-primary);
}

.comment-time {
  color: var(--app-text-secondary);
}

.comment-content {
  margin-top: 4px;
  font-size: 14px;
  color: var(--app-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.comment-input .el-textarea {
  flex: 1;
}

/* @提及面板：悬浮于评论输入框上方，候选为项目成员管理中加入的成员 */
.comment-input-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
}

.mention-panel {
  position: absolute;
  left: 0;
  bottom: calc(100% + 4px);
  z-index: 10;
  min-width: 220px;
  max-height: 240px;
  overflow-y: auto;
  padding: 4px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--app-border-light);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
}

.mention-item.active {
  background: var(--el-color-primary-light-9);
}

.mention-avatar {
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.mention-name {
  font-size: 13px;
  color: var(--app-text-primary);
}

.mention-fullname {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.mention-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

/* 评论内容中对项目成员的 @提及高亮 */
.comment-mention {
  color: var(--el-color-primary);
  font-weight: 600;
}

.comment-no-permission {
  margin-top: 8px;
}
</style>
