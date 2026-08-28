<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { getPasswordStrength, type PasswordStrengthLevel } from '@/utils/passwordStrength'

const props = defineProps<{
  password: string
}>()

const { t } = useI18n()

const strength = computed(() => getPasswordStrength(props.password))

const bars = computed(() => {
  const level = strength.value.level
  return Array.from({ length: 4 }, (_, i) => (i < level ? level : 0))
})

const barColor = (level: number) => {
  const colors: Record<PasswordStrengthLevel, string> = {
    0: 'transparent',
    1: '#f56c6c',
    2: '#e6a23c',
    3: '#409eff',
    4: '#67c23a',
  }
  return colors[level as PasswordStrengthLevel] || 'transparent'
}

const label = computed(() => {
  const labels: Record<PasswordStrengthLevel, string> = {
    0: '',
    1: t('passwordStrength.weak'),
    2: t('passwordStrength.fair'),
    3: t('passwordStrength.good'),
    4: t('passwordStrength.strong'),
  }
  return labels[strength.value.level]
})
</script>

<template>
  <div v-if="password" class="password-strength">
    <div class="strength-bars">
      <span
        v-for="(level, index) in bars"
        :key="index"
        class="strength-bar"
        :style="{ backgroundColor: barColor(level) }"
      />
    </div>
    <span class="strength-label" :style="{ color: barColor(strength.level) }">{{ label }}</span>
  </div>
</template>

<style scoped>
.password-strength {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-top: -4px;
}

.strength-bars {
  display: flex;
  gap: 3px;
  flex: 1;
}

.strength-bar {
  height: 4px;
  flex: 1;
  border-radius: 2px;
  background: var(--el-border-color-lighter, #e4e7ed);
  transition: background-color 0.3s;
}

.strength-label {
  font-size: 12px;
  min-width: 32px;
  text-align: right;
  transition: color 0.3s;
}
</style>
