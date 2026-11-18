<template><div class="track" role="progressbar" :aria-valuenow="value"><div class="fill" :style="{width:value+'%',background:color}"/></div></template>
<script setup lang="ts">
withDefaults(defineProps<{value:number;color?:string}>(),{color:'var(--theme)'})
</script>
<style scoped>.track{height:6px;border-radius:99px;background:var(--track);overflow:hidden}.fill{height:100%;border-radius:99px;transition:width .45s var(--ease-spring)}</style>
