<template>
<Teleport to="body"><Transition name="md"><div v-if="ui.searchOpen" class="mask" @click.self="ui.searchOpen=false">
  <div class="panel glass" role="dialog" aria-modal="true">
    <div class="srow"><Search :size="17"/><input ref="inp" v-model="q" placeholder="搜索标题、描述、编号、评论…（回车看全部结果）" @input="onInput" @keyup.enter="goAll" aria-label="搜索事项"/></div>
    <div v-if="filters.length" class="sec"><p class="tiny">我的保存筛选</p>
      <button v-for="f in filters" :key="f.id" class="res press" @click="applyFilter(f)"><Bookmark :size="14"/><span class="grow">{{f.name}}</span><span class="tiny">{{critLabel(f)}}</span></button>
    </div>
    <div class="sec">
      <button v-for="it in results" :key="it.id" class="res press" @click="open(it)">
        <span class="bar" :style="{background:itemColor(it)}"/><span class="code">{{it.code}}</span><span class="grow">{{it.title}}</span>
        <span class="tiny">{{TYPE_META[it.type].label}} · {{STATUS_META[it.status].label}}</span>
      </button>
      <p v-if="q && !results.length && !loading" class="small" style="padding:8px 10px">没有匹配的事项。</p>
      <p v-if="loading" class="small" style="padding:8px 10px">搜索中…</p>
    </div>
  </div>
</div></Transition></Teleport>
</template>
<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Bookmark } from 'lucide-vue-next'
import { api } from '../api/http'; import { TYPE_META, STATUS_META, itemColor, type Item, type SavedFilter } from '../api/types'
import { useUi } from '../stores/ui'; import { useTeam } from '../stores/team'
import { openItem } from '../utils/openItem'
const ui = useUi(), team = useTeam(), router = useRouter()
const q = ref(''), results = ref<Item[]>([]), filters = ref<SavedFilter[]>([]), loading = ref(false), inp = ref<HTMLInputElement|null>(null)
let timer: any = null
watch(()=> ui.searchOpen, async (open)=>{ if (!open) return; q.value=''; results.value=[]; await nextTick(); inp.value?.focus(); if (team.currentId) filters.value = await api.get(`/teams/${team.currentId}/saved-filters`).catch(()=>[]) })
function onInput(){ clearTimeout(timer); timer = setTimeout(run, 220) }
async function run(){ const kw = q.value.trim(); if (!kw || !team.currentId) { results.value = []; return } loading.value = true; try { results.value = (await api.get(`/teams/${team.currentId}/search?q=${encodeURIComponent(kw)}`)).slice(0, 8) } catch { results.value = [] } finally { loading.value = false } }
function open(it: Item){ ui.searchOpen = false; openItem(router, it.id) }
function goAll(){ if (!q.value.trim()) return; ui.searchOpen = false; router.push({ path: '/search', query: { q: q.value.trim() } }) }
function critLabel(f: SavedFilter){ const c = f.criteria ?? {}; return [c.q, c.type].filter(Boolean).join(' · ') }
function applyFilter(f: SavedFilter){ ui.searchOpen = false; router.push({ path: '/search', query: { filter: String(f.id) } }) }
</script>
<style scoped>
.mask{position:fixed;inset:0;background:var(--mask-strong);z-index:var(--z-modal);display:flex;justify-content:center;align-items:flex-start;padding:12vh 16px 16px}
.panel{width:620px;max-width:100%;border-radius:20px;padding:12px;max-height:70vh;overflow-y:auto}
.srow{display:flex;align-items:center;gap:9px;background:var(--field);border:1px solid var(--field-brd);border-radius:13px;padding:0 13px;color:var(--text-3)}
.srow input{flex:1;min-height:44px;border:0;background:transparent;outline:none;font-size:14.5px;min-width:0}
.sec{margin-top:10px;display:flex;flex-direction:column;gap:2px}
.res{display:flex;align-items:center;gap:9px;text-align:left;padding:8px 10px;border-radius:11px;width:100%}
.res:hover{background:var(--hover)}.res .grow{flex:1;font-weight:500}.res .code{color:var(--text-2);font-size:12.5px}
.bar{width:4px;height:20px;border-radius:2px;flex:none}
.md-enter-active{transition:opacity .16s ease}.md-enter-from{opacity:0}.md-leave-to{opacity:0}
</style>
