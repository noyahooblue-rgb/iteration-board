<template><Teleport to="body"><Transition name="md"><div v-if="open" class="mask" @click.self="$emit('close')">
<div class="modal glass" role="dialog" aria-modal="true"><slot/></div></div></Transition></Teleport></template>
<script setup lang="ts">
import { watch, onUnmounted } from 'vue'
const props = defineProps<{open:boolean}>(); const emit = defineEmits(['close'])
function onKey(e: KeyboardEvent){ if (e.key === 'Escape') emit('close') }
watch(() => props.open, (v) => { if (v) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey) })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>
<style scoped>.mask{position:fixed;inset:0;background:var(--mask-strong);z-index:var(--z-modal);display:grid;place-items:center;padding:16px}
.modal{width:440px;max-width:100%;border-radius:var(--r-panel);padding:20px}
.md-enter-active{transition:opacity .18s ease}.md-enter-active .modal{transition:transform .35s var(--ease-spring),opacity .2s}
.md-enter-from{opacity:0}.md-enter-from .modal{transform:scale(.96) translateY(8px);opacity:0}.md-leave-to{opacity:0}</style>
