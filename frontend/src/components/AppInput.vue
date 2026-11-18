<template><label class="field"><span v-if="label" class="lab">{{label}}</span>
<input v-if="type!=='textarea'" :type="type" :value="modelValue" :placeholder="placeholder" @input="$emit('update:modelValue',($event.target as HTMLInputElement).value)"/>
<textarea v-else :value="modelValue" :placeholder="placeholder" rows="3" @input="$emit('update:modelValue',($event.target as HTMLTextAreaElement).value)"></textarea>
<span v-if="error" class="err">{{error}}</span></label></template>
<script setup lang="ts">
withDefaults(defineProps<{modelValue:string;label?:string;placeholder?:string;type?:string;error?:string}>(),{type:'text'})
defineEmits(['update:modelValue'])
</script>
<style scoped>.field{display:block}.lab{display:block;font-size:13px;font-weight:600;color:var(--text-2);margin-bottom:6px}
input,textarea{width:100%;min-height:42px;padding:9px 12px;border-radius:var(--r-ctl);border:1px solid var(--field-brd);background:var(--field);outline:none;resize:vertical;transition:border-color .15s,box-shadow .15s}
input:focus,textarea:focus{border-color:var(--theme);box-shadow:0 0 0 3px rgba(0,122,255,.16)}.err{display:block;color:var(--c-red);font-size:12px;margin-top:5px}</style>
