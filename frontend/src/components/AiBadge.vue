<template>
  <span v-if="aiGenerated" class="aibadge">
    <Sparkles :size="12"/>{{ edited ? 'AI 生成，已编辑' : 'AI 生成' }}
    <span class="fb">
      <button class="fbtn press" :class="{on:fb==='up'}" title="有帮助" @click="fb = fb==='up' ? '' : 'up'"><ThumbsUp :size="12"/></button>
      <button class="fbtn press" :class="{on:fb==='down'}" title="没帮助" @click="fb = fb==='down' ? '' : 'down'"><ThumbsDown :size="12"/></button>
      <span v-if="fb" class="tiny">已反馈</span>
    </span>
  </span>
</template>
<script setup lang="ts">
// AI 生成标记：靛色徽标；被人编辑后文案变「AI 生成，已编辑」。
// 反馈（有帮助/没帮助）后端暂无接口，仅前端本地状态（见 M4 worklog）。
import { ref } from 'vue'
import { Sparkles, ThumbsUp, ThumbsDown } from 'lucide-vue-next'
defineProps<{ aiGenerated: boolean; edited?: boolean }>()
const fb = ref('')
</script>
<style scoped>
.aibadge{display:inline-flex;gap:6px;align-items:center;font-size:11.5px;font-weight:700;color:#3730A3;background:rgba(88,86,214,.14);border:1px solid rgba(88,86,214,.35);border-radius:999px;padding:2px 9px}
.fb{display:inline-flex;gap:2px;align-items:center}
.fbtn{display:inline-flex;padding:2px;border-radius:6px;color:#3730A3}.fbtn.on{background:#5856D6;color:#fff}
</style>
