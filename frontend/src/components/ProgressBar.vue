<template><div class="track" role="progressbar" :aria-valuenow="v"><div class="fill" :style="{width:v+'%',background:color}"/></div></template>
<script setup lang="ts">
import { computed } from 'vue'
import { clampPct } from '../utils/progress'
// value 统一钳制（1.0.4 F）：NaN/null→0、负数→0、>100→100，避免 0% 显示半条、超宽溢出。
const props = withDefaults(defineProps<{value:number;color?:string}>(),{color:'var(--theme)'})
const v = computed(()=> clampPct(props.value))
</script>
<style scoped>.track{height:6px;border-radius:99px;background:var(--track);overflow:hidden}.fill{height:100%;border-radius:99px;transition:width .45s var(--ease-spring)}</style>
