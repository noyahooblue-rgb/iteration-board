<template>
<Modal :open="open" @close="close">
  <p class="tiny step-n">版本 {{notes?.version}}</p>
  <h2 style="margin:6px 0 10px">{{notes?.title ?? '本次更新了什么'}}</h2>
  <div class="md" v-html="rendered"/>
  <div class="foot"><span style="flex:1"/><AppButton @click="close">知道了</AppButton></div>
</Modal>
</template>
<script setup lang="ts">
// 更新说明弹窗（修订三二.4）：登录后当前版本更新说明未读时弹；关闭即标记已读（POST /release-notes/read）；
// 系统信息页签可再次打开（ui.releaseNotesOpen 手动触发时不重复标记也无妨，接口幂等）。
import { computed } from 'vue'
import Modal from './Modal.vue'; import AppButton from './AppButton.vue'
import { api } from '../api/http'
import type { ReleaseNotes } from '../api/types'
const props = defineProps<{ open: boolean; notes: ReleaseNotes | null }>()
const emit = defineEmits(['close'])
function esc(s: string) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;') }
const rendered = computed(() => {
  const lines = (props.notes?.content ?? '').split('\n'); const out: string[] = []; let inList = false
  for (const ln of lines) { const t = ln.trim()
    if (t.startsWith('- ')) { if (!inList) { out.push('<ul>'); inList = true } out.push(`<li>${esc(t.slice(2))}</li>`); continue }
    if (inList) { out.push('</ul>'); inList = false }
    if (t.startsWith('## ')) out.push(`<h3>${esc(t.slice(3))}</h3>`)
    else if (t.startsWith('# ')) out.push(`<h3>${esc(t.slice(2))}</h3>`)
    else if (t) out.push(`<p>${esc(t)}</p>`) }
  if (inList) out.push('</ul>')
  return out.join('')
})
async function close() { try { await api.post('/release-notes/read') } catch {} emit('close') }
</script>
<style scoped>
.step-n{color:var(--text-3);font-weight:600}
.md :deep(h3){font-size:14.5px;margin:12px 0 6px}.md :deep(p){margin:5px 0;line-height:1.65;font-size:14px}.md :deep(ul){margin:4px 0 8px 18px}.md :deep(li){margin:3px 0;line-height:1.6;font-size:14px}
.foot{display:flex;gap:10px;align-items:center;margin-top:14px}
</style>
