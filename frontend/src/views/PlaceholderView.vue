<template><AppShell :title="name"><GlassPanel class="ph">
  <component :is="icon" :size="34" class="ic"/><h2>{{name}}</h2>
  <p class="small">风格确认后开放。本阶段先确认登录、看板与事项详情的视觉与交互。</p>
  <AppButton @click="$router.push('/workbench')">回到工作台</AppButton>
</GlassPanel></AppShell></template>
<script setup lang="ts">
import { computed } from 'vue'; import { useRoute } from 'vue-router'
import { Construction } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'
const route = useRoute(); const icon = Construction
const names: Record<string,string> = { workbench:'工作台', pool:'认领池', iteration:'迭代', backlog:'待办池', standup:'站会', overview:'总览', reports:'报告', settings:'小组设置' }
const name = computed(()=> names[String(route.params.name ?? '')] ?? '该页面')
</script>
<style scoped>.ph{text-align:center;padding:44px 20px;display:flex;flex-direction:column;align-items:center;gap:12px}.ic{color:var(--theme)}</style>
