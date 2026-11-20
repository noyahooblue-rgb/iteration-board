<template>
<div class="mi">
  <textarea ref="ta" :value="modelValue" :rows="rows" :placeholder="placeholder" :aria-label="ariaLabel"
    @input="onInput" @keydown="onKey" @click="syncTrigger" @keyup="onKeyup" @blur="onBlur"/>
  <div v-if="open && filtered.length" class="mipop glass" role="listbox" aria-label="选择要 @ 的成员">
    <button v-for="(m, i) in filtered" :key="m.userId" class="mrow" :class="{ on: i === sel }" role="option" :aria-selected="i === sel"
      @mousedown.prevent="pick(m)" @mouseenter="sel = i">
      <Avatar :name="m.displayName" :color="m.avatarColor" :size="24"/><span class="mn">{{ m.displayName }}</span><span class="tiny">@{{ m.loginName }}</span>
    </button>
  </div>
</div>
</template>
<script setup lang="ts">
// @提及输入框（1.0.4 G）：输入 @ 弹出本组成员列表，按 姓名子串 / 登录名 / 姓名拼音首字母 筛选；
// ↑↓ 选择、Enter 确认、Esc 关闭；选中后文本中插入 @姓名 并通过 update:mentionIds 回传被 @ 的 userId 列表。
import { ref, computed, nextTick } from 'vue'
import Avatar from './Avatar.vue'
import { useTeam } from '../stores/team'
import { pinyinInitials } from '../utils/pinyin'
import type { Member } from '../api/types'
const props = withDefaults(defineProps<{ modelValue: string; mentionIds?: number[]; placeholder?: string; rows?: number; ariaLabel?: string; enterSubmits?: boolean }>(), { mentionIds: () => [], rows: 2, enterSubmits: false })
const emit = defineEmits(['update:modelValue', 'update:mentionIds', 'enter'])
const team = useTeam()
const ta = ref<HTMLTextAreaElement | null>(null)
const open = ref(false), sel = ref(0), query = ref(''), atIdx = ref(-1)
const filtered = computed<Member[]>(() => {
  const q = query.value.trim().toLowerCase()
  const all = team.members
  if (!q) return all.slice(0, 8)
  return all.filter(m => m.displayName.includes(query.value.trim()) || m.loginName.toLowerCase().includes(q) || pinyinInitials(m.displayName).toLowerCase().startsWith(q) || pinyinInitials(m.displayName).toLowerCase().includes(q)).slice(0, 8)
})
function syncTrigger() {
  const el = ta.value; if (!el) { open.value = false; return }
  const pos = el.selectionStart ?? el.value.length
  const before = el.value.slice(0, pos)
  const at = Math.max(before.lastIndexOf('@'), before.lastIndexOf('＠'))
  if (at < 0) { open.value = false; return }
  const seg = before.slice(at + 1)
  if (/[\s@＠]/.test(seg) || seg.length > 20) { open.value = false; return }
  atIdx.value = at; query.value = seg; open.value = true; sel.value = 0
}
function onInput(e: Event) { emit('update:modelValue', (e.target as HTMLTextAreaElement).value); syncTrigger(); pruneIds() }
function onKeyup(e: KeyboardEvent) { if (!['Enter', 'ArrowDown', 'ArrowUp', 'Escape'].includes(e.key)) syncTrigger() }
function onBlur() { setTimeout(() => { open.value = false }, 120) }
function pruneIds() {
  const kept = props.mentionIds.filter(id => props.modelValue.includes('@' + team.memberName(id)))
  if (kept.length !== props.mentionIds.length) emit('update:mentionIds', kept)
}
function pick(m: Member) {
  const el = ta.value; if (!el || atIdx.value < 0) return
  const pos = el.selectionStart ?? el.value.length
  const insert = '@' + m.displayName + ' '
  const next = el.value.slice(0, atIdx.value) + insert + el.value.slice(pos)
  emit('update:modelValue', next)
  if (!props.mentionIds.includes(m.userId)) emit('update:mentionIds', [...props.mentionIds, m.userId])
  open.value = false
  nextTick(() => { const p = atIdx.value + insert.length; el.focus(); el.setSelectionRange(p, p) })
}
function onKey(e: KeyboardEvent) {
  if (open.value && filtered.value.length) {
    if (e.key === 'ArrowDown') { e.preventDefault(); sel.value = (sel.value + 1) % filtered.value.length; return }
    if (e.key === 'ArrowUp') { e.preventDefault(); sel.value = (sel.value - 1 + filtered.value.length) % filtered.value.length; return }
    if (e.key === 'Enter') { e.preventDefault(); pick(filtered.value[sel.value]); return }
    if (e.key === 'Escape') { e.preventDefault(); open.value = false; return }
  }
  if (e.key === 'Escape') { open.value = false; return }
  if (props.enterSubmits && e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); emit('enter') }
}
</script>
<style scoped>
.mi{position:relative;flex:1;display:flex}
.mi textarea{flex:1;border:1px solid var(--field-brd);background:var(--field);border-radius:var(--r-ctl);padding:9px 12px;resize:none;max-height:110px;outline:none;font-size:14px;font-family:inherit}
.mi textarea:focus{border-color:var(--theme);box-shadow:0 0 0 3px rgba(0,122,255,.16)}
.mipop{position:absolute;left:0;bottom:calc(100% + 6px);z-index:60;min-width:220px;max-height:264px;overflow-y:auto;border-radius:13px;padding:5px;box-shadow:var(--shadow-lift);display:flex;flex-direction:column}
.mrow{display:flex;align-items:center;gap:8px;text-align:left;padding:6px 9px;border-radius:9px;font-size:13.5px}
.mrow.on{background:var(--hover)}
.mrow .mn{font-weight:600}
</style>
