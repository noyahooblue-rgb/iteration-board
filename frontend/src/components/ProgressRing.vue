<template><svg :width="size" :height="size" viewBox="0 0 44 44" role="progressbar" :aria-valuenow="value">
<circle cx="22" cy="22" r="18" fill="none" stroke="var(--fill)" stroke-width="5"/>
<circle cx="22" cy="22" r="18" fill="none" :stroke="color" stroke-width="5" stroke-linecap="round" :stroke-dasharray="113" :stroke-dashoffset="113-113*value/100" transform="rotate(-90 22 22)" class="ring"/>
<text x="22" y="26" text-anchor="middle" font-size="11" font-weight="600" fill="var(--text)">{{value}}%</text></svg></template>
<script setup lang="ts">
withDefaults(defineProps<{value:number;size?:number;color?:string}>(),{size:44,color:'var(--theme)'})
</script>
<style scoped>.ring{transition:stroke-dashoffset .45s var(--ease-spring)}</style>
