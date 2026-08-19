<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import AppMessageHost from '@/components/AppMessageHost.vue'
import { useSiteStore } from '@/stores/site'

const { locale } = useI18n()
const siteStore = useSiteStore()

// Element Plus 组件内置文案（分页、日期选择器等）随语言同步切换
const elLocale = computed(() => (locale.value === 'zh-CN' ? zhCn : en))

// 应用启动时拉取站点品牌设置（应用名称/LOGO），失败时保持默认品牌
onMounted(() => {
  siteStore.fetchSettings()
})
</script>

<template>
  <el-config-provider :locale="elLocale">
    <router-view />
    <AppMessageHost />
  </el-config-provider>
</template>
