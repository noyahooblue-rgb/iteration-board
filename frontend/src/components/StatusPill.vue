<template><span class="pill" :style="{background:soft,color:fg}"><span class="dot" :style="{background:fg}"/>{{label}}</span></template>
<script setup lang="ts">
import { computed } from 'vue'; import { STATUS_META, type ItemStatus } from '../api/types'
const p=defineProps<{status:ItemStatus}>()
const label=computed(()=>STATUS_META[p.status].label)
const map:Record<string,[string,string]>={UNCLAIMED:['var(--st-claim-bg)','var(--st-claim)'],NOT_STARTED:['var(--st-todo-bg)','var(--st-todo)'],IN_PROGRESS:['var(--st-doing-bg)','var(--st-doing)'],IN_REVIEW:['var(--st-review-bg)','var(--st-review)'],DONE:['var(--st-done-bg)','var(--st-done)'],CANCELLED:['var(--st-cancel-bg)','var(--st-cancel)']}
const soft=computed(()=>map[p.status][0]); const fg=computed(()=>map[p.status][1])
</script>
<style scoped>.pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}.dot{width:7px;height:7px;border-radius:50%}</style>
