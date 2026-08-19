import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true },
    },
    {
      path: '/share/:shareId',
      name: 'share-redirect',
      component: () => import('@/views/ShareRedirectView.vue'),
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
        },
        {
          path: 'projects/:id/:viewId?',
          name: 'project-detail',
          component: () => import('@/views/ProjectDetailView.vue'),
        },
      ],
    },
    // 管理后台：独立布局，侧边栏为后台功能模块专用
    {
      path: '/admin',
      redirect: { name: 'admin-users' },
      component: () => import('@/layouts/AdminLayout.vue'),
      meta: { requiresAdmin: true },
      children: [
        {
          path: 'users',
          name: 'admin-users',
          component: () => import('@/views/AdminUsersView.vue'),
          meta: { requiresAdmin: true },
        },
        {
          path: 'teams',
          name: 'admin-teams',
          component: () => import('@/views/AdminTeamsView.vue'),
          meta: { requiresAdmin: true },
        },
        {
          path: 'templates',
          name: 'admin-templates',
          component: () => import('@/views/AdminTemplatesView.vue'),
          meta: { requiresAdmin: true },
        },
        {
          path: 'archived-projects',
          name: 'admin-archived-projects',
          component: () => import('@/views/AdminArchivedProjectsView.vue'),
          meta: { requiresAdmin: true },
        },
        {
          path: 'settings',
          name: 'admin-settings',
          component: () => import('@/views/AdminSettingsView.vue'),
          meta: { requiresAdmin: true },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

/** 全局前置守卫：未登录用户只能访问公开页面；管理后台仅系统管理员可见 */
router.beforeEach(async (to) => {
  const token = localStorage.getItem('protask_token')
  if (!to.meta.public && !token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && token && (to.name === 'login' || to.name === 'register')) {
    return { name: 'dashboard' }
  }
  if (to.meta.requiresAdmin && token) {
    const userStore = useUserStore()
    // 刷新页面后用户信息尚未恢复时先拉取，失败则按现有状态放行（布局层会兜底）
    if (!userStore.user) {
      try {
        await userStore.fetchMe()
      } catch {
        /* 忽略，未登录场景由 401 拦截处理 */
      }
    }
    if (userStore.user?.role !== 'admin') {
      return { name: 'dashboard' }
    }
  }
  return true
})

export default router
