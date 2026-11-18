<template><div class="host" aria-live="polite"><TransitionGroup name="t"><div v-for="i in toast.items" :key="i.id" class="toast glass">{{i.text}}</div></TransitionGroup></div></template>
<script setup lang="ts">
import { useToast } from '../stores/toast'; const toast=useToast()
</script>
<style scoped>.host{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:100;display:flex;flex-direction:column;gap:8px;align-items:center}
.toast{padding:10px 16px;border-radius:999px;font-size:13px;box-shadow:var(--shadow-lift)}
.t-enter-active,.t-leave-active{transition:all .25s var(--ease-out)}.t-enter-from,.t-leave-to{opacity:0;transform:translateY(8px)}</style>
