<template>
<span class="stack" v-if="ids.length">
  <Avatar v-for="(id,i) in shown" :key="id" :name="team.memberName(id)" :color="team.member(id)?.avatarColor" :size="size" class="av" :style="{marginLeft: i? '-7px':'0', zIndex: shown.length-i}"/>
  <span v-if="ids.length>max" class="more" :style="{width:size+'px',height:size+'px',fontSize:(size*0.36)+'px',marginLeft:'-7px'}">+{{ids.length-max}}</span>
</span>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import Avatar from './Avatar.vue'; import { useTeam } from '../stores/team'
const p = withDefaults(defineProps<{ids:number[];size?:number;max?:number}>(),{size:22,max:3})
const team = useTeam()
const shown = computed(()=> p.ids.slice(0, p.max))
</script>
<style scoped>
.stack{display:inline-flex;align-items:center}
.stack .av{border:2px solid var(--card-solid,#fff);box-sizing:content-box}
.more{display:inline-flex;align-items:center;justify-content:center;border-radius:50%;background:var(--track);color:var(--text-2);font-weight:700;border:2px solid var(--card-solid,#fff);box-sizing:content-box}
</style>
