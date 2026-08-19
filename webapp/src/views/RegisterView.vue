<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type FormInstance, type FormRules } from 'element-plus'
import { appMessage } from '@/utils/appMessage'
import { useI18n } from 'vue-i18n'
import { register, validateInvite } from '@/api/auth'
import { useSiteStore } from '@/stores/site'

const { t } = useI18n()
const siteStore = useSiteStore()
const router = useRouter()
const route = useRoute()

/** 邀请链接校验状态：checking 校验中 / valid 有效 / invalid 无效或已使用 / closed 未携带邀请凭证 */
type InviteState = 'checking' | 'valid' | 'invalid' | 'closed'

const formRef = ref<FormInstance>()
const loading = ref(false)
const inviteState = ref<InviteState>('checking')
const inviteToken = (route.query.invite as string) || ''
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  firstName: '',
  lastName: '',
  nickname: '',
})

const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('validation.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('validation.usernameLength'), trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: t('validation.usernamePattern'), trigger: 'blur' },
  ],
  password: [
    { required: true, message: t('validation.passwordRequired'), trigger: 'blur' },
    { min: 6, max: 50, message: t('validation.passwordLength'), trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: t('validation.confirmPasswordRequired'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error(t('validation.passwordMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}))

// 注册页仅接受携带邀请凭证的访问：校验通过才展示注册表单
onMounted(async () => {
  if (!inviteToken) {
    inviteState.value = 'closed'
    return
  }
  try {
    await validateInvite(inviteToken)
    inviteState.value = 'valid'
  } catch {
    // 具体原因（无效/已使用/已过期）由请求拦截器统一提示
    inviteState.value = 'invalid'
  }
})

async function handleRegister() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await register({
      username: form.username,
      password: form.password,
      firstName: form.firstName.trim() || undefined,
      lastName: form.lastName.trim() || undefined,
      nickname: form.nickname || undefined,
      inviteToken,
    })
    appMessage.success(t('register.success'))
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="always">
      <img :src="siteStore.logo || '/logo.svg'" alt="logo" class="auth-logo" />
      <h1 class="auth-title">{{ t('register.title') }}</h1>

      <!-- 未携带邀请凭证 / 凭证无效：不展示注册表单 -->
      <template v-if="inviteState === 'closed'">
        <el-empty :description="t('register.inviteRequired')" :image-size="72" />
      </template>
      <template v-else-if="inviteState === 'invalid'">
        <el-empty :description="t('register.inviteInvalid')" :image-size="72" />
      </template>
      <template v-else-if="inviteState === 'checking'">
        <div v-loading="true" class="invite-checking" />
      </template>

      <!-- 邀请凭证有效：正常注册 -->
      <template v-else>
        <p class="auth-subtitle">{{ t('register.subtitle') }}</p>
        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleRegister">
          <el-form-item prop="username">
            <el-input v-model="form.username" :placeholder="t('register.usernamePlaceholder')" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item prop="firstName">
                <el-input v-model="form.firstName" maxlength="50" :placeholder="t('register.firstNamePlaceholder')" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item prop="lastName">
                <el-input v-model="form.lastName" maxlength="50" :placeholder="t('register.lastNamePlaceholder')" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item prop="nickname">
            <el-input v-model="form.nickname" :placeholder="t('register.nicknamePlaceholder')" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              :placeholder="t('register.passwordPlaceholder')"
              show-password
            />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              :placeholder="t('register.confirmPasswordPlaceholder')"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="auth-submit" :loading="loading" @click="handleRegister">
              {{ t('register.submit') }}
            </el-button>
          </el-form-item>
        </el-form>
      </template>

      <div class="auth-footer">
        {{ t('register.hasAccount') }}
        <router-link to="/login" class="auth-link">{{ t('register.goLogin') }}</router-link>
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
  font-size: 26px;
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

.invite-checking {
  min-height: 120px;
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
