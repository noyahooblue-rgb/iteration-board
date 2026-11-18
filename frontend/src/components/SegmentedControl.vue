<template><div class="seg" role="tablist"><button v-for="o in options" :key="o.value" role="tab" :aria-selected="modelValue===o.value" :class="{on:modelValue===o.value}" @click="$emit('update:modelValue',o.value)">{{o.label}}</button></div></template>
<script setup lang="ts">
defineProps<{modelValue:string;options:{label:string;value:string}[]}>(); defineEmits(['update:modelValue'])
</script>
<style scoped>.seg{display:inline-flex;background:var(--track);border-radius:11px;padding:3px;gap:3px}.seg button{min-height:32px;padding:4px 12px;border-radius:8px;font-size:13px;font-weight:500;color:var(--text-2)}
.seg button.on{background:var(--card);color:var(--text);font-weight:600;box-shadow:0 1px 5px rgba(10,60,140,.15)}</style>
