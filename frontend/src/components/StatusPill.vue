<template><span class="pill" :style="{background:soft,color:fg}"><span class="dot" :style="{background:fg}"/>{{label}}</span></template>
<script setup lang="ts">
import { computed } from 'vue'; import { STATUS_META, type ItemStatus } from '../api/types'
const p=defineProps<{status:ItemStatus}>()
const label=computed(()=>STATUS_META[p.status].label)
const map:Record<string,[string,string]>={UNCLAIMED:['var(--track)','var(--text-2)'],NOT_STARTED:['rgba(0,122,255,.14)','#0A5CD6'],IN_PROGRESS:['rgba(48,176,199,.18)','#0B6E80'],IN_REVIEW:['rgba(175,82,222,.16)','#7D2CA8'],DONE:['rgba(52,199,89,.16)','#177245'],CANCELLED:['var(--track)','var(--text-3)']}
const soft=computed(()=>map[p.status][0]); const fg=computed(()=>map[p.status][1])
</script>
<style scoped>.pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}.dot{width:7px;height:7px;border-radius:50%}</style>
