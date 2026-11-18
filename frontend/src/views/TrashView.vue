<template>
<AppShell title="回收站" @refresh="load">
  <p class="small" style="margin-bottom:12px">统一回收站（Q9）：已软删除的事项与报告保留 30 天，可随时恢复；恢复故事后其所属任务的状态会重新推算。</p>
  <SegmentedControl v-model="typeFilter" :options="[{label:'全部',value:'ALL'},{label:'事项',value:'ITEM'},{label:'报告',value:'REPORT'}]" style="margin-bottom:12px"/>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <article v-for="e in entries" :key="e.kind + e.id" class="kcard" :style="{borderLeftColor: e.kind==='REPORT' ? '#5856D6' : itemColorOf(e)}">
    <div class="krow">
      <span class="etag" :class="e.kind">{{ e.kind==='REPORT' ? '报告' : '事项' }}</span>
      <span class="small">{{ e.kind==='REPORT' ? kindLabel(e.reportKind) : e.code }}</span>
      <TypeBadge v-if="e.kind==='ITEM'" :type="e.itemType"/>
      <span style="flex:1"/><span class="tiny">删除于 {{fmt(e.deletedAt)}}</span>
    </div>
    <p class="ktitle">{{e.title}}</p>
    <div class="krow"><AppButton variant="ghost" :disabled="busy.is('restore'+e.kind+e.id)" @click="restore(e)"><RotateCcw :size="14"/>恢复</AppButton></div>
  </article>
  <p v-if="!entries.length && !loading" class="small">回收站是空的。</p>
</AppShell>
</template>
<script setup lang="ts">
// Q9：统一回收站页，按类型筛选（事项/报告/全部）；报告页不再另设回收站入口。
import { ref, watch, onMounted } from 'vue'
import { RotateCcw } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import AppButton from '../components/AppButton.vue'; import TypeBadge from '../components/TypeBadge.vue'; import SegmentedControl from '../components/SegmentedControl.vue'
import { api, ApiError } from '../api/http'; import { itemColor } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
import { useBusy } from '../utils/busy'
const team = useTeam(), toast = useToast(), busy = useBusy()
const entries = ref<any[]>([]), loading = ref(false), loadErr = ref(''), typeFilter = ref('ALL')
const fmt = (s?: string | null) => s ? s.slice(0, 16).replace('T', ' ') : '—'
const kindLabel = (k: string) => ({ STANDUP_SUMMARY: '站会摘要', WEEKLY: '周报', ITERATION_SUMMARY: '迭代总结', PERSONAL_WEEKLY: '我的周报', RISK_SUMMARY: '风险摘要' } as Record<string, string>)[k] ?? k
const itemColorOf = (e: any) => e.kind === 'ITEM' ? itemColor({ type: e.itemType, storyCategory: null }) : '#5856D6'
async function load(){ if (!team.currentId) return; loading.value = true; loadErr.value = ''; try { entries.value = await api.get(`/teams/${team.currentId}/trash?type=${typeFilter.value}`) } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load); watch(typeFilter, load)
async function restore(e: any){ await busy.wrap('restore' + e.kind + e.id, async () => {
  try { if (e.kind === 'REPORT') await api.post(`/teams/${team.currentId}/reports/${e.id}/restore`); else await api.post(`/teams/${team.currentId}/items/${e.id}/restore`); toast.show('已恢复'); load() }
  catch(err){ toast.show(err instanceof ApiError ? err.message : '恢复失败') } }) }
</script>
<style scoped>
.kcard{background:var(--card);border:1px solid rgba(255,255,255,.65);border-left:4px solid var(--theme);border-radius:var(--r-card);box-shadow:0 4px 14px rgba(10,60,140,.08);padding:12px 14px 12px 18px;margin-bottom:10px}
.krow{display:flex;align-items:center;gap:8px}
.ktitle{font-size:15px;font-weight:600;margin:7px 0}
.etag{font-size:11px;font-weight:700;border-radius:6px;padding:2px 7px}
.etag.ITEM{background:var(--sel);color:#0A5CD6}.etag.REPORT{background:rgba(88,86,214,.14);color:#3730A3}
</style>
