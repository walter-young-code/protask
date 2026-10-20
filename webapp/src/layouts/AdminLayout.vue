<script setup lang="ts">
import { ArrowLeft, Box, Coin, Files, Moon, OfficeBuilding, Setting, Sunny, User } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { appMessage } from '@/utils/appMessage'
import { isDark, toggleTheme } from '@/utils/theme'
import { useUserStore } from '@/stores/user'
import { useSiteStore } from '@/stores/site'

const { t } = useI18n()
const userStore = useUserStore()
const siteStore = useSiteStore()
const route = useRoute()
const router = useRouter()

/** 侧边栏菜单命令：index 即路由 name */
function handleMenuSelect(key: string) {
  router.push({ name: key })
}

/** 退出管理后台：返回项目总览 */
function handleBackToProjects() {
  router.push({ name: 'dashboard' })
}

async function handleLogout() {
  await userStore.logout()
  appMessage.success(t('layout.logoutSuccess'))
  router.push({ name: 'login' })
}

/** 用户菜单命令：明暗主题切换 / 退出登录 */
function handleUserMenuCommand(command: string) {
  if (command === 'toggleTheme') {
    toggleTheme()
  } else if (command === 'logout') {
    handleLogout()
  }
}
</script>

<template>
  <el-container class="admin-layout">
    <el-header class="admin-header">
      <div class="header-left">
        <router-link to="/" class="logo">
          <img :src="siteStore.logo || '/logo.svg'" alt="logo" class="logo-icon" />
          {{ siteStore.name || t('app.name') }}
        </router-link>
        <span class="admin-title">{{ t('admin.homeTitle') }}</span>
      </div>
      <div class="header-right">
        <!-- 退出管理后台，返回项目页面 -->
        <span class="back-to-projects" @click="handleBackToProjects">
          <el-icon><ArrowLeft /></el-icon>
          {{ t('admin.backToProjects') }}
        </span>

        <el-dropdown @command="handleUserMenuCommand">
          <span class="user-info">
            <el-avatar :size="28" class="user-avatar">
              {{ userStore.user?.nickname?.charAt(0) || userStore.user?.username?.charAt(0) || 'U' }}
            </el-avatar>
            <span class="user-name">
              {{ userStore.user?.nickname || userStore.user?.username || t('layout.notLoggedIn') }}
            </span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="toggleTheme" :icon="isDark ? Sunny : Moon">
                {{ t(isDark ? 'layout.lightMode' : 'layout.darkMode') }}
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>{{ t('layout.logout') }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="admin-body">
      <!-- 管理后台功能模块专用侧边栏（后续功能在此扩展） -->
      <el-aside width="200px" class="admin-aside">
        <el-menu :default-active="String(route.name)" class="admin-menu" @select="handleMenuSelect">
          <el-menu-item index="admin-users">
            <el-icon><User /></el-icon>
            <span>{{ t('admin.title') }}</span>
          </el-menu-item>
          <el-menu-item index="admin-teams">
            <el-icon><OfficeBuilding /></el-icon>
            <span>{{ t('adminTeams.title') }}</span>
          </el-menu-item>
          <el-menu-item index="admin-templates">
            <el-icon><Files /></el-icon>
            <span>{{ t('adminTemplates.title') }}</span>
          </el-menu-item>
          <el-menu-item index="admin-archived-projects">
            <el-icon><Box /></el-icon>
            <span>{{ t('adminArchivedProjects.title') }}</span>
          </el-menu-item>
          <el-menu-item index="admin-storage">
            <el-icon><Coin /></el-icon>
            <span>{{ t('adminStorage.title') }}</span>
          </el-menu-item>
          <el-menu-item index="admin-settings">
            <el-icon><Setting /></el-icon>
            <span>{{ t('siteSettings.title') }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100%;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--app-header-bg);
  color: #fff;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
}

.logo-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}

.admin-title {
  padding-left: 16px;
  border-left: 1px solid rgba(255, 255, 255, 0.35);
  font-size: 14px;
  color: rgba(255, 255, 255, 0.9);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 24px;
}

.back-to-projects {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #fff;
  cursor: pointer;
  transition: opacity 0.2s;
}

.back-to-projects:hover {
  opacity: 0.8;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #fff;
}

.user-avatar {
  background: var(--el-color-primary);
  color: #fff;
}

.user-name {
  font-size: 14px;
}

.admin-body {
  flex: 1;
  min-height: 0;
}

.admin-aside {
  background: var(--app-surface);
  border-right: 1px solid var(--app-border-regular);
}

.admin-menu {
  border-right: none;
}

.admin-main {
  background: var(--app-page-bg);
  overflow-y: auto;
}
</style>
