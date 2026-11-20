<template><Teleport to="body"><Transition name="dr"><div v-if="open" class="mask" @click.self="$emit('close')">
<aside class="drawer glass" role="dialog" aria-modal="true"><div class="dbody"><slot/></div><slot name="footer"/></aside></div></Transition></Teleport></template>
<script setup lang="ts">
import { watch, onUnmounted } from 'vue'
const props = defineProps<{open:boolean}>(); const emit = defineEmits(['close'])
// 修订五 A5：Esc 关抽屉——浮层打开时浮层在捕获阶段先拦截 Esc（OverlayMenu），不会误关抽屉。
function onKey(e: KeyboardEvent){ if (e.key === 'Escape') emit('close') }
watch(() => props.open, (v) => { if (v) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey) })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>
<style scoped>.mask{position:fixed;inset:0;background:var(--mask);z-index:var(--z-drawer);display:flex;justify-content:flex-end}
.drawer{width:560px;max-width:100vw;height:100dvh;border-radius:24px 0 0 24px;display:flex;flex-direction:column;overflow:hidden}
.dbody{flex:1;overflow-y:auto;padding:20px}
.dr-enter-active{transition:opacity .2s ease}.dr-enter-active .drawer{transition:transform .35s var(--ease-spring)}
.dr-enter-from{opacity:0}.dr-enter-from .drawer{transform:translateX(48px)}
.dr-leave-active{transition:opacity .18s ease}.dr-leave-to{opacity:0}
@media(max-width:820px){.drawer{width:100vw;border-radius:0}}</style>
