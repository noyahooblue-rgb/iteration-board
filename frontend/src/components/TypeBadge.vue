<template><span class="badge" :style="{background:color}"><span class="wdot"/>{{label}}</span></template>
<script setup lang="ts">
import { computed } from 'vue'; import { TYPE_META, STORY_CAT_META, type ItemType, type StoryCategory } from '../api/types'
const p=defineProps<{type:ItemType;storyCategory?:StoryCategory|null}>(); const label=computed(()=> p.type==='STORY' && p.storyCategory ? `故事 · ${STORY_CAT_META[p.storyCategory].label}` : TYPE_META[p.type].label)
const color=computed(()=> p.type==='STORY' && p.storyCategory ? STORY_CAT_META[p.storyCategory].color : TYPE_META[p.type].color)
</script>
<style scoped>.badge{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap;color:var(--on-color)}.wdot{width:7px;height:7px;border-radius:50%;background:var(--glass-highlight);flex:none}</style>
