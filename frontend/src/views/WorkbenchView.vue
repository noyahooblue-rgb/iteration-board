<template>
<AppShell title="工作台" @refresh="load">
  <QuickUpdateBox @changed="load"/>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <div class="hello glass">
    <div class="grow">
      <h2>{{greeting}}，{{auth.user?.displayName}}</h2>
      <p class="small" v-if="team.iteration">{{team.iteration.name}} · {{team.iteration.startDate}} ～ {{team.iteration.endDate}} · 整体进度 {{iterPct}}%</p>
      <p class="small" v-else>当前没有进行中的迭代。</p>
    </div>
    <ProgressRing :value="iterPct" :size="72"/>
  </div>
  <div class="quick glass">
    <input v-model="quickText" placeholder="说说进展，或者让我建个事项…" @keyup.enter="quickSubmit" aria-label="一句话更新"/>
    <AppButton @click="quickSubmit">发送</AppButton>
  </div>
  <p v-if="quickNote" class="note">{{quickNote}}</p>
  <div class="layout">
    <div class="main">
      <section v-for="g in groups" :key="g.key" class="gsec">
        <h2>{{g.label}} <span class="small">{{g.items.length}} 项</span></h2>
        <article v-for="it in g.items" :key="it.id" class="kcard" :style="{borderLeftColor:itemColor(it)}">
          <span v-if="it.nudgeCount>=2" class="nudge"><Zap :size="12"/>被催 {{it.nudgeCount}} 次</span>
          <div class="krow"><span class="small">{{it.code}}</span><span v-if="it.batchId" class="btag">批次</span><span style="flex:1"/><span class="prio">{{it.priority}}</span></div>
          <button class="ktitle press" @click="openId=it.id;drawerOpen=true">{{it.title}}</button>
          <p v-if="it.blocked" class="kblock"><OctagonAlert :size="14"/> 阻塞：{{it.blockReason}}</p>
          <p v-if="it.rejected || readyMark(it) || (it.estimate==null && it.status==='IN_PROGRESS')" class="kmarks"><span v-if="it.rejected" class="mark red">已退回</span><span v-if="readyMark(it)" class="mark green">可提交验收</span><span v-if="it.estimate==null && it.status==='IN_PROGRESS'" class="mark amber">未估算</span></p>
          <ProgressBar v-if="it.status==='IN_PROGRESS'||it.status==='IN_REVIEW'" :value="it.progressPercent" style="margin:6px 0"/>
          <p class="tiny">上次更新：{{it.lastProgressAt ? fmt(it.lastProgressAt) : '暂无'}}<span v-if="it.dueDate"> · 截止 {{fmtDue(it.dueDate)}}</span><span v-if="isOver(it)" class="over"> · 已逾期 {{overdueDays(it.dueDate)}} 天</span></p>
          <div class="acts">
            <template v-if="g.key==='doing'">
              <AppButton variant="ghost" @click="openProgress(it)">更新</AppButton>
              <AppButton variant="ghost" @click="keepGoing(it)">照常推进</AppButton>
              <AppButton @click="act(it,'SUBMIT')">提交验收</AppButton>
            </template>
            <template v-else-if="g.key==='todo'">
              <AppButton @click="act(it,'START')">开始</AppButton>
              <AppButton v-if="canDecline(it)" variant="ghost" @click="decline(it)">婉拒</AppButton>
            </template>
            <template v-else-if="g.key==='review'">
              <AppButton @click="act(it,'APPROVE')">通过</AppButton>
              <AppButton variant="ghost" @click="reject(it)">打回</AppButton>
            </template>
            <template v-else>
              <AppButton variant="ghost" @click="nudge(it)"><BellRing :size="14"/>催一下</AppButton>
              <AppButton variant="ghost" @click="openId=it.id;drawerOpen=true">查看</AppButton>
            </template>
          </div>
        </article>
        <p v-if="!g.items.length" class="small empty">暂无</p>
      </section>
    </div>
    <aside class="side">
      <GlassPanel class="spanel warn" v-if="nudgedMine.length">
        <h3><Zap :size="16"/>被催提醒</h3>
        <button v-for="it in nudgedMine" :key="it.id" class="srow press" @click="openId=it.id;drawerOpen=true"><span class="grow">{{it.code}} {{it.title}}</span><span class="tiny">被催 {{it.nudgeCount}} 次</span></button>
      </GlassPanel>
      <GlassPanel class="spanel">
        <h3><CalendarDays :size="16"/>近期关键日期</h3>
        <p v-if="team.iteration" class="srow"><span class="grow">{{team.iteration.name}} 结束</span><span class="tiny">{{team.iteration.endDate}}</span></p>
        <p v-for="kd in keyDates" :key="kd.id" class="srow"><span class="grow">{{kd.title}}（{{ {FREEZE:'封版',RELEASE:'投产',REVIEW:'评审',OTHER:'关键日期'}[kd.category] }}）</span><span class="tiny">{{kd.date}}</span></p>
        <button v-for="it in dueSoon" :key="it.id" class="srow press" @click="openId=it.id;drawerOpen=true"><span class="grow">{{it.code}} {{it.title}}</span><span class="tiny" :class="{over: isOver(it)}">{{fmtDue(it.dueDate!)}}<template v-if="isOver(it)"> · 已逾期 {{overdueDays(it.dueDate)}} 天</template></span></button>
        <p v-if="!dueSoon.length && !keyDates.length" class="small">近期没有关键日期与我负责事项的截止日期。</p>
        <RouterLink to="/iterations" class="tiny" style="color:var(--theme)">迭代关键日期在「迭代」页登记 →</RouterLink>
      </GlassPanel>
      <GlassPanel class="spanel">
        <h3><MailPlus :size="16"/>邀请我认领</h3>
        <button v-for="it in invited" :key="it.id" class="srow press" @click="openId=it.id;drawerOpen=true"><span class="grow">{{it.code}} {{it.title}}</span><span class="tiny">去看看 →</span></button>
        <p v-if="!invited.length" class="small">暂无邀请。</p>
      </GlassPanel>
    </aside>
  </div>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { Zap, OctagonAlert, BellRing, CalendarDays, MailPlus } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import ProgressBar from '../components/ProgressBar.vue'; import ProgressRing from '../components/ProgressRing.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import QuickUpdateBox from '../components/QuickUpdateBox.vue'
import { api, ApiError, apiMode } from '../api/http'; import { itemColor, type Item } from '../api/types'
import { fmtDateTime, localToday, overdueDays } from '../utils/datetime'
import { isPromptDismissed } from '../utils/progressPrompt'
function readyMark(it:Item){ return it.status==='IN_PROGRESS' && it.progressPercent>=100 && isPromptDismissed(it.id) }
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const team = useTeam(), auth = useAuth(), toast = useToast()
const items = ref<Item[]>([]), quickText = ref(''), quickNote = ref('')
const keyDates = ref<import('../api/types').KeyDate[]>([])
const drawerOpen = ref(false), openId = ref<number|null>(null)
const me = computed(()=> auth.user?.id ?? 0)
const greeting = computed(()=> { const h = new Date().getHours(); return h < 6 ? '夜深了' : h < 12 ? '上午好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好' })
const mine = computed(()=> items.value.filter(i=>i.ownerIds.includes(me.value)))
const iterPct = computed(()=> { const l = items.value.filter(i=>i.type!=='TASK' && i.iterationId===team.iteration?.id && i.status!=='CANCELLED'); return l.length ? Math.round(l.filter(i=>i.status==='DONE').length / l.length * 100) : 0 })
const groups = computed(()=> [
  { key:'doing', label:'进行中', items: mine.value.filter(i=>i.status==='IN_PROGRESS') },
  { key:'todo', label:'待开始', items: mine.value.filter(i=>i.status==='NOT_STARTED') },
  { key:'review', label:'待我验收', items: items.value.filter(i=>i.acceptorId===me.value && i.status==='IN_REVIEW') },
  { key:'sent', label:'我发出的', items: items.value.filter(i=>i.creatorId===me.value && !i.ownerIds.includes(me.value) && !['DONE','CANCELLED'].includes(i.status)) },
])
const nudgedMine = computed(()=> mine.value.filter(i=>i.nudgeCount>0 && !['DONE','CANCELLED'].includes(i.status)))
const dueSoon = computed(()=> mine.value.filter(i=>i.dueDate && !['DONE','CANCELLED'].includes(i.status)).sort((a,b)=> (a.dueDate!).localeCompare(b.dueDate!)).slice(0,5))
const invited = computed(()=> items.value.filter(i=>i.status==='UNCLAIMED' && (i.inviteeIds ?? []).includes(me.value)))
const loading = ref(false), loadErr = ref('')
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''; try { items.value = await api.get(`/teams/${team.currentId}/items?scope=all`)
  if (team.iteration) keyDates.value = await api.get(`/teams/${team.currentId}/iterations/${team.iteration.id}/key-dates`).catch(()=>[])
} catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
const fmt = fmtDateTime
function fmtDue(d:string){ const m = d.match(/^(\d+)-(\d+)-(\d+)$/); return m ? `${Number(m[2])}月${Number(m[3])}日` : d }
function isOver(it:Item){ return !!it.dueDate && it.dueDate < localToday() }
function canDecline(it:Item){ return it.assignedDirectly && it.status==='NOT_STARTED' }
async function act(it:Item, action:string){ try { await api.post(`/teams/${team.currentId}/items/${it.id}/transitions`, { action, version: it.version }); toast.show('已更新'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '操作失败'); load() } }
async function keepGoing(it:Item){ try { await api.post(`/teams/${team.currentId}/items/${it.id}/progress`, { text: '无新情况，继续推进', kind: 'KEEP_GOING' }); toast.show('已登记照常推进'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '登记失败') } }
function openProgress(it:Item){ openId.value = it.id; drawerOpen.value = true }
function reject(it:Item){ const r = prompt('打回原因（必填）'); if (!r?.trim()) return; runReason(it, 'REJECT', r.trim()) }
function decline(it:Item){ const r = prompt('婉拒原因（必填）'); if (!r?.trim()) return; runReason(it, 'DECLINE', r.trim()) }
async function runReason(it:Item, action:string, reason:string){ try { await api.post(`/teams/${team.currentId}/items/${it.id}/transitions`, { action, version: it.version, reason }); toast.show('已处理'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '操作失败'); load() } }
async function nudge(it:Item){ try { const r = await api.post(`/teams/${team.currentId}/items/${it.id}/nudge`); toast.show(r.redirectedToLeader ? '对方请假/出差，已改催组长' : `已催一下（本轮第 ${r.nudgeCount} 次）`); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '催办失败') } }
function quickSubmit(){ if (!quickText.value.trim()) return
  quickNote.value = apiMode === 'mock'
    ? '演示模式下不做 AI 解析（AI 一句话更新为 M4 功能）。请点下方卡片上的「更新 / 照常推进」手工登记进展。'
    : 'AI 解析将在 M4 上线。当前可先用下方卡片上的「更新 / 照常推进」手工登记进展。'
  quickText.value = '' }
</script>
<style scoped>
.hello{border-radius:var(--r-panel);padding:18px 20px;display:flex;gap:16px;align-items:center;margin-bottom:12px}
.hello .grow{flex:1}.hello h2{font-size:20px}
.quick{border-radius:16px;padding:10px 12px;display:flex;gap:10px;align-items:center}
.quick input{flex:1;min-height:42px;border:0;background:transparent;outline:none;font-size:15px;min-width:0}
.note{background:var(--indigo-bg);border:1px solid var(--indigo-brd);border-radius:12px;padding:9px 13px;font-size:13px;margin-top:10px}
.layout{display:grid;grid-template-columns:1fr 320px;gap:16px;margin-top:14px;align-items:start}
.gsec{margin-bottom:18px}.gsec h2{font-size:16px;margin-bottom:10px}
.kcard{position:relative;background:var(--card);border:1px solid var(--card-brd);border-left:4px solid var(--theme);border-radius:var(--r-card);box-shadow:var(--shadow-card);padding:12px 14px 12px 18px;margin-bottom:10px;overflow:hidden}
.nudge{position:absolute;top:0;right:14px;background:var(--c-yellow);color:var(--nudge-text);font-size:11px;font-weight:700;padding:3px 8px;border-radius:0 0 9px 9px;display:inline-flex;gap:4px;align-items:center}
.krow{display:flex;align-items:center;gap:6px}.prio{font-size:13px;font-weight:700}
.btag{font-size:11px;font-weight:600;color:var(--violet-deep);background:var(--violet-soft);border-radius:6px;padding:1px 6px}
.ktitle{display:block;font-size:15px;font-weight:600;line-height:1.4;margin:6px 0;text-align:left}
.kblock{color:var(--c-red);font-size:12.5px;display:flex;gap:5px;align-items:center}
.kmarks{display:flex;gap:5px;flex-wrap:wrap;margin:4px 0}
.mark{font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px}
.mark.red{color:var(--danger-deep);background:var(--danger-soft)}
.mark.green{color:var(--success-deep);background:var(--success-soft)}
.mark.amber{color:var(--warn-deep);background:var(--warn-soft)}
.acts{display:flex;gap:8px;margin-top:9px;flex-wrap:wrap}
.empty{padding:4px 2px}
.side{display:flex;flex-direction:column;gap:12px}
.spanel{padding:14px 16px}.spanel h3{display:flex;gap:7px;align-items:center;font-size:14.5px;margin-bottom:8px}
.spanel.warn{background:var(--warn-bg-soft)}
.srow{display:flex;gap:8px;align-items:center;width:100%;text-align:left;padding:7px 6px;border-radius:9px}.srow:hover{background:var(--hover)}.srow .grow{flex:1;font-size:13.5px}
.over{color:var(--c-red);font-weight:700}
@media(max-width:1000px){.layout{grid-template-columns:1fr}}
</style>
