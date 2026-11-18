<template><Teleport to="body"><Transition name="dr"><div v-if="open" class="mask" @click.self="$emit('close')">
<aside class="drawer glass" role="dialog" aria-modal="true"><div class="dbody"><slot/></div><slot name="footer"/></aside></div></Transition></Teleport></template>
<script setup lang="ts">
defineProps<{open:boolean}>(); defineEmits(['close'])
</script>
<style scoped>.mask{position:fixed;inset:0;background:rgba(8,25,55,.28);z-index:60;display:flex;justify-content:flex-end}
.drawer{width:560px;max-width:100vw;height:100dvh;border-radius:24px 0 0 24px;display:flex;flex-direction:column;overflow:hidden}
.dbody{flex:1;overflow-y:auto;padding:20px}
.dr-enter-active{transition:opacity .2s ease}.dr-enter-active .drawer{transition:transform .35s var(--ease-spring)}
.dr-enter-from{opacity:0}.dr-enter-from .drawer{transform:translateX(48px)}
.dr-leave-active{transition:opacity .18s ease}.dr-leave-to{opacity:0}
@media(max-width:820px){.drawer{width:100vw;border-radius:0}}</style>
