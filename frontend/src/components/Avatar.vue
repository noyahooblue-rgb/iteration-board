<template><span class="av" :style="{background:color,width:size+'px',height:size+'px',fontSize:(size*0.42)+'px'}" :title="name">{{initial}}</span></template>
<script setup lang="ts">
import { computed } from 'vue'; const p=withDefaults(defineProps<{name:string;color?:string;size?:number}>(),{color:'var(--primary)',size:28}); const initial=computed(()=>p.name.slice(0,1))
</script>
<style scoped>.av{display:inline-flex;align-items:center;justify-content:center;border-radius:50%;color:var(--on-color);font-weight:600;flex:none}</style>
