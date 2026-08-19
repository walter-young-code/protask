<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Lock } from '@element-plus/icons-vue'
import { appMessage } from '@/utils/appMessage'
import * as authApi from '@/api/auth'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const submitting = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

/** 表单校验规则（新密码至少 6 位，确认密码需一致） */
const rules = {
  oldPassword: [
    { required: true, message: () => t('password.oldRequired'), trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: () => t('password.newRequired'), trigger: 'blur' },
    { min: 6, max: 50, message: () => t('password.lengthHint'), trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void) => {
        if (value && value === form.oldPassword) {
          callback(new Error(t('password.sameAsOld')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    {
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void) => {
        if (value !== form.newPassword) {
          callback(new Error(t('password.confirmMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await authApi.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    // 旧密码已生效，其余会话的 token 已失效；当前会话登出后引导重新登录
    appMessage.success(t('password.success'))
    await userStore.logout()
    router.push({ name: 'login' })
  } finally {
    submitting.value = false
  }
}

function close() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('password.title')"
    width="420px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item :label="t('password.oldPassword')" prop="oldPassword">
        <el-input
          v-model="form.oldPassword"
          type="password"
          show-password
          :prefix-icon="Lock"
          :placeholder="t('password.oldPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('password.newPassword')" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          :prefix-icon="Lock"
          :placeholder="t('password.newPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('password.confirmPassword')" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          :prefix-icon="Lock"
          :placeholder="t('password.confirmPlaceholder')"
          @keyup.enter="handleSubmit"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ t('common.save') }}
      </el-button>
    </template>
  </el-dialog>
</template>
