<template><button class="btn press" :class="variant" :disabled="disabled"><slot/></button></template>
<script setup lang="ts">
withDefaults(defineProps<{variant?:string;disabled?:boolean}>(),{variant:'primary'})
</script>
<style scoped>.btn{min-height:40px;white-space:nowrap;padding:0 16px;border-radius:var(--r-ctl);font-weight:600;display:inline-flex;align-items:center;gap:6px;justify-content:center}
.primary{background:var(--theme);color:#fff;box-shadow:0 6px 16px rgba(0,122,255,.32),inset 0 1px 0 rgba(255,255,255,.35)}.ghost{background:var(--field);border:1px solid var(--field-brd);color:var(--text)}.danger{background:var(--c-red);color:#fff;box-shadow:0 6px 16px rgba(255,59,48,.3)}.text{background:transparent;color:var(--theme)}
.btn:disabled{opacity:.38;cursor:not-allowed}</style>
