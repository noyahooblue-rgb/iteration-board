<template><Teleport to="body"><Transition name="md"><div v-if="open" class="mask" @click.self="$emit('close')">
<div class="modal glass" role="dialog" aria-modal="true"><slot/></div></div></Transition></Teleport></template>
<script setup lang="ts">
defineProps<{open:boolean}>(); defineEmits(['close'])
</script>
<style scoped>.mask{position:fixed;inset:0;background:rgba(8,25,55,.3);z-index:70;display:grid;place-items:center;padding:16px}
.modal{width:440px;max-width:100%;border-radius:var(--r-panel);padding:20px}
.md-enter-active{transition:opacity .18s ease}.md-enter-active .modal{transition:transform .35s var(--ease-spring),opacity .2s}
.md-enter-from{opacity:0}.md-enter-from .modal{transform:scale(.96) translateY(8px);opacity:0}.md-leave-to{opacity:0}</style>
