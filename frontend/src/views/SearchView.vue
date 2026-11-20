<template>
<AppShell title="搜索" @refresh="loadFilters">
  <div class="filters glass">
    <div class="srow"><Search :size="16"/><input v-model="q" placeholder="搜索标题、描述、编号、评论…" @keyup.enter="run" aria-label="搜索关键词"/></div>
    <select v-model="fType" aria-label="类型筛选"><option value="">全部类型</option><option v-for="(m,t) in TYPE_META" :key="t" :value="t">{{m.label}}</option></select>
    <AppButton @click="run">搜索</AppButton>
    <span style="flex:1"/>
    <AppButton variant="ghost" @click="saveFilter"><BookmarkPlus :size="15"/>保存当前筛选</AppButton>
  </div>
  <div v-if="filters.length" class="chips">
    <span class="small">我的保存筛选：</span>
    <span v-for="f in filters" :key="f.id" class="fchip"><button class="press" @click="applyFilter(f)">{{f.name}}</button><button class="press del" aria-label="删除筛选" @click="removeFilter(f)"><X :size="13"/></button></span>
  </div>
  <p v-if="searching" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="run">重试</button></p>
  <p v-if="searched" class="small" style="margin:10px 0">共 {{shown.length}} 条结果{{q ? `（关键词「${q}」）` : ''}}。含已完成与已归档事项，不含回收站已删除项。</p>
  <article v-for="it in shown" :key="it.id" class="kcard" :style="{borderLeftColor:itemColor(it)}">
    <div class="krow"><span class="small">{{it.code}}</span><TypeBadge :type="it.type" :story-category="it.storyCategory"/><span style="flex:1"/><StatusPill :status="it.status"/></div>
    <button class="ktitle press" @click="openId=it.id;drawerOpen=true">{{it.title}}</button>
    <p class="small desc">{{it.description}}</p>
    <div class="krow foot"><AvatarStack v-if="it.ownerIds.length" :ids="it.ownerIds" :size="20"/><span class="tiny">{{fmt(it.updatedAt)}} 更新</span></div>
  </article>
  <p v-if="searched && !shown.length" class="small">没有匹配的事项，换个关键词试试。</p>
  <p v-if="!searched" class="small">输入关键词后回车，或按 Ctrl+K 随时呼出命令面板搜索。</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="refreshResults" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Search, BookmarkPlus, X } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import AppButton from '../components/AppButton.vue'; import TypeBadge from '../components/TypeBadge.vue'; import StatusPill from '../components/StatusPill.vue'; import AvatarStack from '../components/AvatarStack.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import { api, ApiError } from '../api/http'; import { TYPE_META, itemColor, type Item, type SavedFilter } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
import { fmtDateTime } from '../utils/datetime'
const route = useRoute(), team = useTeam(), toast = useToast()
const q = ref(''), fType = ref(''), results = ref<Item[]>([]), filters = ref<SavedFilter[]>([]), searched = ref(false), searching = ref(false), loadErr = ref('')
const drawerOpen = ref(false), openId = ref<number|null>(null)
const shown = computed(()=> fType.value ? results.value.filter(i=>i.type===fType.value) : results.value)
const fmt = fmtDateTime
async function run(){ const kw = q.value.trim(); if (!kw) { results.value = []; searched.value = false; return }
  searching.value = true; loadErr.value = ''
  try { results.value = await api.get(`/teams/${team.currentId}/search?q=${encodeURIComponent(kw)}`); searched.value = true } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '搜索失败' } finally { searching.value = false } }
async function refreshResults(){ if (searched.value) run() }
async function loadFilters(){ if (team.currentId) filters.value = await api.get(`/teams/${team.currentId}/saved-filters`).catch(()=>[]) }
async function saveFilter(){ const name = prompt('给这个筛选起个名字'); if (!name?.trim()) return
  try { const f = await api.post(`/teams/${team.currentId}/saved-filters`, { name: name.trim(), criteria: { q: q.value.trim(), type: fType.value } }); filters.value.push(f); toast.show('筛选已保存') } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
function applyFilter(f: SavedFilter){ q.value = f.criteria?.q ?? ''; fType.value = f.criteria?.type ?? ''; run() }
async function removeFilter(f: SavedFilter){ try { await api.del(`/teams/${team.currentId}/saved-filters/${f.id}`); filters.value = filters.value.filter(x=>x.id!==f.id) } catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } }
async function initFromRoute(){ await loadFilters()
  const fid = route.query.filter; if (fid) { const f = filters.value.find(x=>x.id===Number(fid)); if (f) { applyFilter(f); return } }
  const kw = route.query.q; if (kw) { q.value = String(kw); await run() }
  const open = route.query.open; if (open) { openId.value = Number(open); drawerOpen.value = true } }
onMounted(initFromRoute)
watch(()=> route.query, ()=>{ if (route.path === '/search') initFromRoute() })
</script>
<style scoped>
.filters{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
.filters select{min-height:40px;border-radius:10px;border:1px solid var(--field-brd);background:var(--field);padding:0 8px}
.srow{display:flex;align-items:center;gap:8px;background:var(--field);border:1px solid var(--field-brd);border-radius:12px;padding:0 12px;color:var(--text-3);flex:1;min-width:220px}
.srow input{flex:1;min-height:40px;border:0;background:transparent;outline:none;font-size:14px;min-width:0}
.chips{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:12px}
.fchip{display:inline-flex;align-items:center;gap:2px;background:var(--sel);border-radius:999px;padding:4px 6px 4px 12px;font-weight:600;font-size:13px}
.fchip .del{width:22px;height:22px;display:grid;place-items:center;border-radius:50%}
.kcard{position:relative;background:var(--card);border:1px solid var(--card-brd);border-left:4px solid var(--theme);border-radius:var(--r-card);box-shadow:var(--shadow-card);padding:12px 14px 12px 18px;margin-bottom:10px}
.krow{display:flex;align-items:center;gap:8px}.krow.foot{margin-top:8px}
.ktitle{display:block;font-size:15px;font-weight:600;margin:7px 0;text-align:left}
.desc{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
</style>
