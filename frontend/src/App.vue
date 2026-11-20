<template><div class="page-bg"/><RouterView/><ToastHost/></template>
<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'; import ToastHost from './components/ToastHost.vue'
// Chrome 128 兼容门禁（wb89pp2z）：根组件挂载完成后在 <html> 上置 ibReady 标志，
// 冒烟脚本以此断言 SPA 在目标浏览器上真正挂载成功（区别于「HTML 下载到了但脚本报错白屏」）。
onMounted(() => { document.documentElement.dataset.ibReady = '1' })
</script>
