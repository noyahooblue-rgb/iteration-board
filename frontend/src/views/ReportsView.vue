<template>
<AppShell title="报告" @refresh="load">
  <div class="bar glass">
    <span class="small">报告可由程序按结构化数据生成，也可 AI 汇总（带「AI 生成」标记，数字以程序统计为准）；一个周期只有一份，生成后可编辑、看历史版本、删除进统一回收站。</span><span style="flex:1"/>
    <AppButton variant="ghost" @click="exportExcel"><FileSpreadsheet :size="15"/>导出事项清单 Excel</AppButton>
  </div>

  <div class="gen glass">
    <div class="genrow">
      <button v-for="g in visibleGens" :key="g.kind" class="gchip press" :class="{on: genKind===g.kind}" @click="genKind=g.kind">{{g.label}}</button>
      <span v-if="!visibleGens.length" class="small">当前角色没有可生成的报告类型。</span>
    </div>
    <div v-if="currentGen" class="genrow" style="margin-top:10px">
      <template v-if="currentGen.mode==='date'">
        <label class="small">日期 <input type="date" v-model="dateArg" :max="todayIso" class="flexin"/></label>
      </template>
      <template v-else-if="currentGen.mode==='week'">
        <label class="small">周 <input type="week" v-model="weekArg" :max="thisWeek" class="flexin"/></label>
        <span class="tiny">{{period.ps}} ~ {{period.pe}}</span>
      </template>
      <template v-else>
        <label class="small">迭代 <select v-model="iterArg" class="flexin"><option v-for="it in iterations" :key="it.id" :value="it.id">{{it.name}}</option></select></label>
      </template>
      <AppButton :disabled="generating" @click="generateOrView">{{ generating ? '生成中…' : (existing ? '查看' : '生成') }}</AppButton>
      <AppButton v-if="!existing" variant="ghost" :disabled="aiBusy" @click="generateAi" ><AiSpark :size="15"/>{{ aiBusy ? 'AI 汇总中…' : 'AI 汇总生成' }}</AppButton>
      <span v-if="!existing && !aiAvail" class="tiny">AI 暂不可用，可手工用左侧「生成」走程序报告。</span>
      <span v-if="existing" class="tiny">该周期已有报告，点「查看」打开；要更新内容请在报告内用「重新生成」。</span>
    </div>
  </div>

  <p v-if="loading" class="small">加载中…</p>
  <div v-else-if="loadErr" class="errbar"><span>{{loadErr}}</span><AppButton variant="ghost" @click="load">重试</AppButton></div>
  <div v-else class="layout">
    <div class="list">
      <button v-for="r in reports" :key="r.id" class="rcard press" :class="{on: current?.id===r.id}" @click="openReport(r)">
        <b>{{r.title}}</b><span class="tiny">{{kindLabel(r.kind)}} · {{fmtTime(r.createdAt)}}<span v-if="r.editedByHuman"> · 已手工修改</span></span><AiBadge v-if="r.aiGenerated" :ai-generated="true" :edited="r.editedByHuman"/>
      </button>
      <p v-if="!reports.length" class="small">暂无你可见的报告。</p>
    </div>
    <GlassPanel class="reader" v-if="current">
      <div class="rhead"><h2>{{current.title}}</h2><AiBadge v-if="current.aiGenerated" :ai-generated="true" :edited="current.editedByHuman"/><span style="flex:1"/>
        <AppButton variant="ghost" @click="copyMd">复制 Markdown</AppButton>
        <AppButton variant="ghost" @click="downloadMd">导出 .md</AppButton>
        <AppButton variant="ghost" @click="exportWord">导出 Word</AppButton>
        <AppButton variant="ghost" @click="copyAsEmail">复制为邮件正文</AppButton>
        <AppButton v-if="canEditCurrent" variant="ghost" :disabled="regenBusy" @click="regenerate">{{ regenBusy ? '重新生成中…' : '重新生成' }}</AppButton>
        <AppButton v-if="canEditCurrent" variant="ghost" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存编辑' }}</AppButton>
        <AppButton v-if="canEditCurrent" variant="danger" :disabled="deleting" @click="removeReport">{{ deleting ? '删除中…' : '删除' }}</AppButton></div>
      <div class="md" v-html="rendered"/>
      <details class="editbox" v-if="canEditCurrent"><summary>编辑 Markdown 原文</summary><textarea v-model="editContent" rows="14"/></details>
      <div class="verbox">
        <AppButton variant="ghost" @click="loadVersions">历史版本（最多 5 个）</AppButton>
        <div v-if="versions !== null" style="margin-top:8px">
          <p v-if="!versions.length" class="small">暂无历史版本（重新生成或恢复时，覆盖前的内容会留在这里）。</p>
          <div v-for="v in versions" :key="v.id" class="vrow">
            <span class="small">{{fmtTime(v.createdAt)}}<span v-if="v.editedByHuman"> · 含手工修改</span></span><span style="flex:1"/>
            <AppButton variant="ghost" @click="viewVersion(v)">查看全文</AppButton>
            <AppButton v-if="canEditCurrent" variant="ghost" :disabled="restoring" @click="restoreVersion(v)">恢复此版本</AppButton>
          </div>
          <pre v-if="versionText" class="vtext">{{versionText}}</pre>
        </div>
      </div>
    </GlassPanel>
    <p v-else class="small">从左侧选择一份报告查看。</p>
  </div>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { FileSpreadsheet } from 'lucide-vue-next'
import AiSpark from '../components/AiSpark.vue'; import AiBadge from '../components/AiBadge.vue'; import { useAi } from '../stores/ai'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'
import { api, apiMode, ApiError, newIdempotencyKey } from '../api/http'; import type { ReportItem, ReportVersion, Iteration } from '../api/types'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
import { fmtDateTime } from '../utils/datetime'
const team = useTeam(), auth = useAuth(), toast = useToast()
// kind 映射（与后端 ReportService 对齐）：STANDUP_SUMMARY 站会摘要 / WEEKLY 周报 / ITERATION_SUMMARY 迭代总结 / PERSONAL_WEEKLY 我的周报 / RISK_SUMMARY 风险摘要
const KIND_LABELS: Record<string,string> = { STANDUP_SUMMARY:'站会摘要', WEEKLY:'周报', ITERATION_SUMMARY:'迭代总结', PERSONAL_WEEKLY:'个人周报', RISK_SUMMARY:'风险摘要' }
const kindLabel = (k:string)=> KIND_LABELS[k] ?? k
const reports = ref<ReportItem[]>([]), current = ref<ReportItem|null>(null), iterations = ref<Iteration[]>([])
const loading = ref(false), loadErr = ref('')
const generating = ref(false), regenBusy = ref(false), saving = ref(false), deleting = ref(false), restoring = ref(false)
const editContent = ref('')
const versions = ref<ReportVersion[]|null>(null), versionText = ref('')
const role = computed(()=> team.current?.myRole)
const isLeaderOrPO = computed(()=> role.value==='LEADER' || role.value==='PO')
interface GenDef { kind:string; label:string; mode:'date'|'week'|'iteration'; can:boolean }
const gens = computed<GenDef[]>(()=> [
  { kind:'STANDUP_SUMMARY', label:'站会摘要', mode:'date', can:isLeaderOrPO.value },
  { kind:'RISK_SUMMARY', label:'风险摘要', mode:'date', can:isLeaderOrPO.value },
  { kind:'WEEKLY', label:'周报', mode:'week', can:isLeaderOrPO.value },
  { kind:'PERSONAL_WEEKLY', label:'我的周报', mode:'week', can:true },
  { kind:'ITERATION_SUMMARY', label:'迭代总结', mode:'iteration', can:role.value==='LEADER' },
])
const visibleGens = computed(()=> gens.value.filter(g=>g.can))
const genKind = ref('WEEKLY')
const currentGen = computed(()=> visibleGens.value.find(g=>g.kind===genKind.value) ?? visibleGens.value[0] ?? null)
watch(visibleGens, (v)=>{ if (v.length && !v.some(g=>g.kind===genKind.value)) genKind.value = v[0].kind }, { immediate:true })
const iso = (d:Date)=> `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`
const todayIso = iso(new Date())
const dateArg = ref(todayIso)
function weekInputValue(d:Date){ const date = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate())); const day = (date.getUTCDay()+6)%7; date.setUTCDate(date.getUTCDate()-day+3); const firstThu = new Date(Date.UTC(date.getUTCFullYear(),0,4)); const fday = (firstThu.getUTCDay()+6)%7; firstThu.setUTCDate(firstThu.getUTCDate()-fday+3); const week = 1 + Math.round((date.getTime()-firstThu.getTime())/(7*864e5)); return `${date.getUTCFullYear()}-W${String(week).padStart(2,'0')}` }
function mondayFromWeekInput(v:string){ const m = v.match(/^(\d{4})-W(\d{2})$/); if (!m) return todayIso; const jan4 = new Date(Date.UTC(Number(m[1]),0,4)); const fday = (jan4.getUTCDay()+6)%7; const mon = new Date(jan4); mon.setUTCDate(jan4.getUTCDate()-fday+(Number(m[2])-1)*7); return iso(new Date(mon.getUTCFullYear(), mon.getUTCMonth(), mon.getUTCDate())) }
const weekArg = ref(weekInputValue(new Date()))
const thisWeek = weekInputValue(new Date())
const iterArg = ref<number>(0)
const period = computed(()=> {
  const g = currentGen.value; if (!g) return { ps:'', pe:'' }
  if (g.mode==='date') return { ps: dateArg.value, pe: dateArg.value }
  if (g.mode==='week') { const ps = mondayFromWeekInput(weekArg.value); const d = new Date(ps+'T00:00:00'); d.setDate(d.getDate()+6); return { ps, pe: iso(d) } }
  const it = iterations.value.find(i=>i.id===iterArg.value); return { ps: it?.startDate ?? '', pe: it?.endDate ?? '' }
})
const existing = computed(()=> { const g = currentGen.value; if (!g) return null
  return reports.value.find(r=> r.kind===g.kind && r.periodStart===period.value.ps && r.periodEnd===period.value.pe && (g.kind!=='PERSONAL_WEEKLY' || r.ownerId===auth.user?.id)) ?? null })
const canEditCurrent = computed(()=> { const r = current.value; if (!r) return false; if (r.allowedActions) return r.allowedActions.includes('EDIT') // Q11
  if (r.kind==='PERSONAL_WEEKLY') return r.ownerId===auth.user?.id
  if (r.kind==='ITERATION_SUMMARY') return role.value==='LEADER'
  return isLeaderOrPO.value })
const contentOf = (r:ReportItem)=> r.contentMd ?? r.content ?? ''
const fmtTime = fmtDateTime
function esc(s:string){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;') }
function inline(s:string){ return esc(s).replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>') }
const rendered = computed(()=> { if (!current.value) return ''
  const lines = contentOf(current.value).split('\n'); const out: string[] = []; let inList = false
  for (const ln of lines) { const t = ln.trim()
    if (t.startsWith('- ')) { if (!inList) { out.push('<ul>'); inList = true } out.push(`<li>${inline(t.slice(2))}</li>`); continue }
    if (inList) { out.push('</ul>'); inList = false }
    if (t.startsWith('## ')) out.push(`<h3>${inline(t.slice(3))}</h3>`)
    else if (t.startsWith('# ')) out.push(`<h2 class="rt">${inline(t.slice(2))}</h2>`)
    else if (t.startsWith('|')) out.push(`<p class="trow-md">${inline(t)}</p>`)
    else if (t) out.push(`<p>${inline(t)}</p>`) }
  if (inList) out.push('</ul>')
  return out.join('') })
function openReport(r:ReportItem){ current.value = r; editContent.value = contentOf(r); versions.value = null; versionText.value = '' }
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''
  try { reports.value = await api.get(`/teams/${team.currentId}/reports`)
    iterations.value = await api.get(`/teams/${team.currentId}/iterations`).catch(()=>[])
    if (!iterArg.value && iterations.value.length) iterArg.value = (iterations.value.find(i=>i.status==='ACTIVE') ?? iterations.value[iterations.value.length-1]).id
    if (current.value) { const fresh = reports.value.find(r=>r.id===current.value!.id); if (fresh) openReport(fresh); else current.value = null }
    if (!current.value && reports.value.length) openReport(reports.value[0])
  } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
//：一次点击意图一个幂等键；失败重试复用同键，成功或换周期后作废重建。
let genKey = ''
watch([genKind, dateArg, weekArg, iterArg], ()=>{ genKey = '' })
async function generateOrView(){ const g = currentGen.value; if (!g || generating.value) return
  if (existing.value) { openReport(existing.value); return }
  generating.value = true
  try { if (!genKey) genKey = newIdempotencyKey()
    const r: ReportItem = await api.postKey(`/teams/${team.currentId}/reports/generate`, { kind: g.kind, periodStart: period.value.ps || undefined, periodEnd: period.value.pe || undefined }, genKey)
    genKey = ''
    toast.show(r.existed ? '该周期已有报告，已打开已有那份' : '报告已生成')
    await load(); const fresh = reports.value.find(x=>x.id===r.id); if (fresh) openReport(fresh)
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '生成失败') } finally { generating.value = false } }
const aiStore = useAi()
const aiBusy = ref(false)
const aiAvail = computed(()=> aiStore.featureOn('summarize'))
async function generateAi(){ const g = currentGen.value; if (!g || aiBusy.value) return; aiBusy.value = true
  try { const r: ReportItem = await api.post(`/teams/${team.currentId}/ai/summarize`, { kind: g.kind, periodStart: period.value.ps || undefined, periodEnd: period.value.pe || undefined }); toast.show(r.existed ? '该周期已有报告，已打开' : 'AI 汇总已生成'); await load(); const f = reports.value.find(x => x.id === r.id); if (f) openReport(f) } catch(e){ toast.show(e instanceof ApiError ? e.message : 'AI 汇总失败') } finally { aiBusy.value = false } }
async function regenerate(){ const r = current.value; if (!r || regenBusy.value) return
  const msg = r.editedByHuman ? '该报告已被手工修改，重新生成后手工修改将被覆盖（覆盖前的内容会保留为历史版本）。确定重新生成？' : '重新生成将覆盖当前内容（覆盖前的内容会保留为历史版本）。确定重新生成？'
  if (!confirm(msg)) return
  regenBusy.value = true
  try { const fresh: ReportItem = await api.postKey(`/teams/${team.currentId}/reports/${r.id}/regenerate`, {}, newIdempotencyKey()); toast.show('已重新生成'); await load(); const f = reports.value.find(x=>x.id===fresh.id); if (f) openReport(f) }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '重新生成失败') } finally { regenBusy.value = false } }
async function saveEdit(){ const r = current.value; if (!r || saving.value) return; saving.value = true
  try { const fresh: ReportItem = await api.patch(`/teams/${team.currentId}/reports/${r.id}`, { contentMd: editContent.value, version: r.version }); toast.show('已保存'); await load(); const f = reports.value.find(x=>x.id===fresh.id); if (f) openReport(f) }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } finally { saving.value = false } }
async function removeReport(){ const r = current.value; if (!r || deleting.value) return
  if (!confirm('确定删除这份报告吗？删除后进入回收站，30 天内可恢复。')) return
  deleting.value = true
  try { await api.del(`/teams/${team.currentId}/reports/${r.id}`); current.value = null; toast.show('已删除，报告已进入回收站'); await load() }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } finally { deleting.value = false } }
async function loadVersions(){ const r = current.value; if (!r) return
  try { versions.value = await api.get(`/teams/${team.currentId}/reports/${r.id}/versions`) } catch(e){ toast.show(e instanceof ApiError ? e.message : '加载历史版本失败') } }
async function viewVersion(v:ReportVersion){ const r = current.value; if (!r) return
  try { const full: ReportVersion = await api.get(`/teams/${team.currentId}/reports/${r.id}/versions/${v.id}`); versionText.value = full.contentMd ?? full.content ?? '' } catch(e){ toast.show(e instanceof ApiError ? e.message : '加载版本失败') } }
async function restoreVersion(v:ReportVersion){ const r = current.value; if (!r || restoring.value) return
  if (!confirm('恢复此版本将覆盖当前内容（当前内容会保留为历史版本）。确定恢复？')) return
  restoring.value = true
  try { const fresh: ReportItem = await api.post(`/teams/${team.currentId}/reports/${r.id}/versions/${v.id}/restore`); toast.show('已恢复该版本'); await load(); const f = reports.value.find(x=>x.id===fresh.id); if (f) openReport(f); versions.value = null }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '恢复失败') } finally { restoring.value = false } }
async function exportWord(){ const r = current.value; if (!r) return
  try { if (apiMode === 'mock') { const d: any = await api.get(`/reports/${r.id}/export-docx`); const blob = new Blob([d.contentHtml ?? ''], { type: 'application/msword' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = d.filename ?? (r.title + '.docx'); a.click(); URL.revokeObjectURL(a.href); toast.show('演示模式：已导出 Word 可打开的 .docx（HTML 封装）') ; return }
    window.location.href = `/api/v1/reports/${r.id}/export-docx` } catch(e){ toast.show(e instanceof ApiError ? e.message : '导出失败') } }
async function copyAsEmail(){ if (!current.value) return
  // 复制富文本 HTML 到剪贴板（保留标题/列表/表格），粘到邮件正文即带格式；不支持富文本剪贴板时退回纯文本。
  const html = '<div>' + rendered.value + '</div>'
  try {
    if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) { await navigator.clipboard.write([new ClipboardItem({ 'text/html': new Blob([html], { type: 'text/html' }), 'text/plain': new Blob([contentOf(current.value)], { type: 'text/plain' }) })]) }
    else await navigator.clipboard.writeText(contentOf(current.value))
    toast.show('已复制为邮件正文（富文本）') } catch { toast.show('复制失败，请手动选择原文') } }
async function copyMd(){ if(!current.value) return; try { await navigator.clipboard.writeText(contentOf(current.value)); toast.show('已复制 Markdown') } catch { toast.show('复制失败，请手动选择原文') } }
function downloadMd(){ if(!current.value) return; const blob = new Blob([contentOf(current.value)], { type: 'text/markdown' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = current.value.title + '.md'; a.click(); URL.revokeObjectURL(a.href) }
async function exportExcel(){ try { const r:any = await api.get(`/teams/${team.currentId}/items/export`)
  if (r && r.mockCsv !== undefined) { const blob = new Blob([r.mockCsv], { type: 'text/csv' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'items.csv'; a.click(); URL.revokeObjectURL(a.href); toast.show('演示模式：已导出 CSV（真实环境为 Excel .xlsx）'); return }
  window.location.href = `/api/v1/teams/${team.currentId}/items/export`
} catch(e){ toast.show(e instanceof ApiError ? e.message : '导出失败') } }
</script>
<style scoped>
.bar{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;margin-bottom:14px}
.gen{border-radius:var(--r-card);padding:12px;margin-bottom:14px}
.genrow{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.gchip{padding:7px 14px;border-radius:999px;border:1.5px solid var(--separator);font-weight:600;background:var(--card)}
.gchip.on{border-color:var(--theme);background:var(--theme-soft);color:var(--theme)}
.flexin{min-height:36px;border:1px solid var(--field-brd);border-radius:9px;background:var(--card);padding:4px 9px}
.errbar{display:flex;gap:12px;align-items:center;color:var(--c-red);background:var(--danger-bg-soft);border-radius:12px;padding:10px 14px}
.layout{display:grid;grid-template-columns:280px 1fr;gap:14px;align-items:start}
.list{display:flex;flex-direction:column;gap:10px}
.rcard{text-align:left;background:var(--card);border:1px solid var(--card-brd);border-radius:var(--r-card);padding:12px 14px;display:flex;flex-direction:column;gap:3px;box-shadow:var(--shadow-card-soft)}
.rcard.on{outline:2px solid var(--theme)}
.reader{padding:18px 20px;min-width:0}
.rhead{display:flex;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}.rhead h2{font-size:17px}
.md :deep(h2.rt){font-size:18px;margin:6px 0 10px}.md :deep(h3){font-size:14.5px;margin:14px 0 6px}.md :deep(p){margin:5px 0;line-height:1.65;font-size:14px}.md :deep(ul){margin:4px 0 8px 18px}.md :deep(li){margin:3px 0;line-height:1.6;font-size:14px}
.md :deep(.trow-md){font-family:ui-monospace,monospace;font-size:12.5px;color:var(--text-2)}
.editbox{margin-top:14px}.editbox textarea{width:100%;border:1px solid var(--field-brd);border-radius:12px;background:var(--field);padding:10px 12px;margin-top:8px;font-family:ui-monospace,monospace;font-size:13px}
.verbox{margin-top:16px;border-top:1px solid var(--field-brd);padding-top:12px}
.vrow{display:flex;gap:8px;align-items:center;padding:5px 0}
.vtext{white-space:pre-wrap;background:var(--field);border:1px solid var(--field-brd);border-radius:12px;padding:10px 12px;font-size:12.5px;max-height:300px;overflow:auto;margin-top:8px}
@media(max-width:900px){.layout{grid-template-columns:1fr}}
</style>
