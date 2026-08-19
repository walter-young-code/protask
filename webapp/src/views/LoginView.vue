<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type FormInstance, type FormRules } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useSiteStore } from '@/stores/site'

const { t } = useI18n()
const userStore = useUserStore()
const siteStore = useSiteStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
})

const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('validation.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('validation.passwordRequired'), trigger: 'blur' }],
}))

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    // 登录成功：清空上一会话残留的过期错误提示（如 401 未登录消息），再展示成功提示
    appMessage.clear()
    appMessage.success(t('login.success'))
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="always">
      <img :src="siteStore.logo || '/logo.svg'" alt="logo" class="auth-logo" />
      <h1 class="auth-title">{{ siteStore.name || t('app.name') }}</h1>
      <p class="auth-subtitle">{{ t('app.subtitle') }}</p>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            :placeholder="t('login.usernamePlaceholder')"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="t('login.passwordPlaceholder')"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="auth-submit" :loading="loading" @click="handleLogin">
            {{ t('login.submit') }}
          </el-button>
        </el-form-item>
      </el-form>
      <div class="auth-footer">
        {{ t('login.noAccount') }}
        <router-link to="/register" class="auth-link">{{ t('login.registerNow') }}</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #409eff 0%, #1d3a8f 100%);
}

html.dark .auth-page {
  background: linear-gradient(135deg, #1a2332 0%, #0f1724 100%);
}

.auth-card {
  width: 400px;
  padding: 12px 8px;
  border-radius: 8px;
}

.auth-logo {
  display: block;
  width: 56px;
  height: 56px;
  margin: 0 auto 10px;
}

.auth-title {
  text-align: center;
  font-size: 28px;
  color: var(--app-text-primary);
}

.auth-subtitle {
  margin: 8px 0 24px;
  text-align: center;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.auth-submit {
  width: 100%;
}

.auth-footer {
  margin-top: 8px;
  text-align: center;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.auth-link {
  color: var(--el-color-primary);
}
</style>
