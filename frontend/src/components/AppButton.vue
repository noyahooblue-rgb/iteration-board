<template><button class="btn press" :class="variant" :disabled="disabled"><slot/></button></template>
<script setup lang="ts">
withDefaults(defineProps<{variant?:string;disabled?:boolean}>(),{variant:'primary'})
</script>
<style scoped>.btn{min-height:40px;white-space:nowrap;padding:0 16px;border-radius:var(--r-ctl);font-weight:600;display:inline-flex;align-items:center;gap:6px;justify-content:center}
.primary{background:var(--primary-btn);color:var(--on-color);box-shadow:var(--shadow-btn-primary)}.primary:hover{background:var(--primary-btn-hover)}.primary:active{background:var(--primary-btn-active)}.ghost{background:var(--field);border:1px solid var(--field-brd);color:var(--text)}.danger{background:var(--danger);color:var(--on-color);box-shadow:var(--shadow-btn-danger)}.text{background:transparent;color:var(--theme)}
.btn:disabled{opacity:.38;cursor:not-allowed}</style>
