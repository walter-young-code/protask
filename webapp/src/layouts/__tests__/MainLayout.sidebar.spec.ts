import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import zhCN from '@/locales/zh-CN'
import MainLayout from '@/layouts/MainLayout.vue'

const USER_ID = 1
const STORAGE_KEY = `protask-sidebar-opened-categories-${USER_ID}`

const fixtures = vi.hoisted(() => ({
  me: { id: 1, username: 'tester', nickname: '测试员', role: 'user' },
  categories: [
    { id: 2, name: '工作' },
    { id: 3, name: '生活' },
  ],
  projects: [
    { id: 11, name: '项目A', icon: '', categoryId: 2, myRole: 'admin' },
    { id: 12, name: '项目B', icon: '', categoryId: 3, myRole: 'admin' },
    { id: 13, name: '项目C', icon: '' },
  ],
}))

vi.mock('@/api/auth', () => ({
  getMe: vi.fn(() => Promise.resolve(fixtures.me)),
  login: vi.fn(),
  register: vi.fn(),
  validateInvite: vi.fn(),
  createInvite: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
}))
vi.mock('@/api/project', () => ({
  listMyProjects: vi.fn(() => Promise.resolve(fixtures.projects)),
  assignProjectCategory: vi.fn(() => Promise.resolve()),
  convertProjectToTemplate: vi.fn(() => Promise.resolve()),
  copyProject: vi.fn(() => Promise.resolve(fixtures.projects[0])),
}))
vi.mock('@/api/category', () => ({
  listCategories: vi.fn(() => Promise.resolve(fixtures.categories)),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  deleteCategory: vi.fn(),
}))
vi.mock('@/api/notification', () => ({
  getUnreadCount: vi.fn(() => Promise.resolve(0)),
  listNotifications: vi.fn(() => Promise.resolve([])),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))
vi.mock('@/api/site', () => ({
  getSiteSettings: vi.fn(() => Promise.resolve({ name: 'ProTask', logo: '', maxUploadBytes: 0 })),
  updateSiteSettings: vi.fn(),
  uploadSiteLogo: vi.fn(),
}))
// 子弹窗与侧边栏行为无关，用空组件替换以隔离依赖
vi.mock('@/components/ProjectDialog.vue', () => ({
  default: { name: 'ProjectDialogStub', props: ['modelValue', 'project'], template: '<div />' },
}))
vi.mock('@/components/TemplateSelectorDialog.vue', () => ({
  default: { name: 'TemplateSelectorDialogStub', props: ['modelValue'], template: '<div />' },
}))
vi.mock('@/components/SearchDialog.vue', () => ({
  default: { name: 'SearchDialogStub', props: ['modelValue'], template: '<div />' },
}))
vi.mock('@/components/ChangePasswordDialog.vue', () => ({
  default: { name: 'ChangePasswordDialogStub', props: ['modelValue'], template: '<div />' },
}))

async function mountLayout(path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
      { path: '/projects/:id', name: 'project-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const wrapper = mount(MainLayout, {
    global: { plugins: [createPinia(), i18n, ElementPlus, router] },
    attachTo: document.body,
  })
  // 等待用户信息、项目、分类加载完成及展开状态恢复
  for (let i = 0; i < 3; i += 1) {
    await flushPromises()
    await nextTick()
  }
  return wrapper
}

/** 当前处于展开状态的分类名（按侧边栏顺序） */
function openedCategoryNames(wrapper: VueWrapper): string[] {
  return wrapper
    .findAll('li.el-sub-menu')
    .filter((sub) => sub.classes().includes('is-opened'))
    .map((sub) => sub.find('.category-name').text().trim())
}

/** 点击分类标题以展开/折叠 */
async function toggleCategory(wrapper: VueWrapper, name: string) {
  const sub = wrapper
    .findAll('li.el-sub-menu')
    .find((item) => item.find('.category-name').text().trim() === name)
  if (!sub) throw new Error(`未找到分类：${name}`)
  await sub.find('.el-sub-menu__title').trigger('click')
  await nextTick()
  await nextTick()
}

function storedOpenedIds(): number[] {
  return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') as number[]
}

describe('MainLayout 侧边栏分类展开状态持久化', () => {
  it('刷新后恢复已展开的分类', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([2]))
    const wrapper = await mountLayout()
    expect(openedCategoryNames(wrapper)).toEqual(['工作'])
    wrapper.unmount()
    localStorage.clear()
  })

  it('当前路由所在分类已折叠时，刷新后仍保持折叠', async () => {
    // 项目B 属于「生活」分类：菜单会因当前激活项自动展开该分类，持久化状态应覆盖它
    localStorage.setItem(STORAGE_KEY, JSON.stringify([2]))
    const wrapper = await mountLayout('/projects/12')
    expect(openedCategoryNames(wrapper)).toEqual(['工作'])
    wrapper.unmount()
    localStorage.clear()
  })

  it('展开/折叠分类会持久化，重新挂载后保持一致', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([2]))
    const wrapper = await mountLayout()

    await toggleCategory(wrapper, '生活')
    expect(openedCategoryNames(wrapper)).toEqual(['工作', '生活'])
    expect(storedOpenedIds()).toEqual([2, 3])

    await toggleCategory(wrapper, '工作')
    expect(openedCategoryNames(wrapper)).toEqual(['生活'])
    expect(storedOpenedIds()).toEqual([3])

    wrapper.unmount()
    const remounted = await mountLayout()
    expect(openedCategoryNames(remounted)).toEqual(['生活'])
    remounted.unmount()
    localStorage.clear()
  })
})
