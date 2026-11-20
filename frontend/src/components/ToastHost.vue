<template><div class="host" aria-live="polite"><TransitionGroup name="t"><div v-for="i in toast.items" :key="i.id" class="toast glass">{{i.text}}<button v-if="i.actionLabel" class="tact press" @click="toast.runAction(i.id)">{{i.actionLabel}}</button></div></TransitionGroup></div></template>
<script setup lang="ts">
import { useToast } from '../stores/toast'; const toast=useToast()
</script>
<style scoped>.host{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:var(--z-toast);display:flex;flex-direction:column;gap:8px;align-items:center}
.toast{padding:10px 16px;border-radius:999px;font-size:13px;box-shadow:var(--shadow-lift);display:flex;gap:10px;align-items:center}
.tact{color:var(--theme);font-weight:700;font-size:13px}
.t-enter-active,.t-leave-active{transition:all .25s var(--ease-out)}.t-enter-from,.t-leave-to{opacity:0;transform:translateY(8px)}</style>
