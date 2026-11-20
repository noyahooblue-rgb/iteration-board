<template>
<AppShell title="总览" @refresh="load">
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <GlassPanel class="head">
    <div class="hrow"><h2>{{health?.iteration?.name ?? '无进行中迭代'}}</h2><span class="small">{{health?.iteration?.startDate ?? ''}} {{health ? '～' : ''}} {{health?.iteration?.endDate ?? ''}}</span><span style="flex:1"/>
      <AppButton v-if="isLeader" variant="ghost" @click="scan">立即扫描提醒</AppButton></div>
    <div class="stats" v-if="health">
      <span>剩余估算 <b>{{health.remainingEstimate}}</b> 点</span>
      <span>理想剩余 <b>{{health.idealRemainingEstimate}}</b> 点</span>
      <span>进度 <b :class="{bad: health.behind}">{{health.behind ? '落后于理想线' : '正常'}}</b></span>
      <span>阻塞 <b class="bad">{{health.blockedCount}}</b></span>
      <span>长期未更新 <b>{{health.staleCount}}</b></span>
      <span>完成率 <b>{{clampPct(health.completionRate)}}%</b></span>
    </div>
    <p class="tiny">健康度（§7.5）：剩余估算与理想线对比、阻塞数、长期未更新数与完成率。</p>
  </GlassPanel>

  <GlassPanel class="panel">
    <h3>成员情况</h3>
    <div class="trow thead"><span class="c1">成员</span><span>状态</span><span>在做</span><span>待更新</span><span>剩余点数</span><span>剩余容量</span><span>空闲</span></div>
    <div v-for="m in data?.members ?? []" :key="m.userId" class="trow">
      <span class="c1"><Avatar :name="team.memberName(m.userId)" :color="team.member(m.userId)?.avatarColor" :size="24"/> {{team.memberName(m.userId)}}</span>
      <span>{{MEMBER_STATUS_META[m.memberStatus] ?? m.memberStatus}}</span>
      <span>{{m.inProgressItems.length}} 件</span>
      <span :class="{bad: m.staleCount>0}">{{m.staleCount}} 件</span>
      <span>{{m.remainingPoints}} 点</span>
      <span>{{m.remainingCapacityPoints ?? '—'}} 点</span>
      <span class="free" :class="m.free ? 'FREE' : 'BUSY'">{{m.free ? '有空' : '在忙'}}</span>
    </div>
    <p class="tiny">剩余容量 = 剩余可用工作日 × 容量系数（小组设置）；「有空」指当前没有进行中事项且今日不在请假/出差。</p>
  </GlassPanel>

  <GlassPanel class="panel" v-if="data?.canViewRisks">
    <h3>AI 风险摘要 <AiBadge v-if="aiRisk" :ai-generated="aiGen"/></h3>
    <template v-if="aiRisk">
      <div v-for="r in aiRisk" :key="r.itemId ?? r.itemCode ?? r.reason" class="airisk">
        <button class="press grow" :disabled="!r.itemId" @click="r.itemId && (openId=r.itemId, drawerOpen=true)"><b>{{r.itemCode}}</b> {{r.reason}}</button>
        <span class="tiny sug">建议：{{r.action}}</span>
      </div>
      <p class="tiny">今日最需关注（最多 3 件）。AI 不可用时自动退回下方规则清单。</p>
    </template>
    <p v-else class="small">AI 暂不可用，已退回规则清单展示（见下）。</p>
  </GlassPanel>

  <GlassPanel class="panel" v-if="data?.canViewRisks">
    <h3>风险提醒 <span class="tiny">（仅组长与 PO 可见）</span></h3>
    <div v-for="sev in ['HIGH','MEDIUM','LOW']" :key="sev">
      <p v-if="risksBy(sev).length" class="sev" :class="sev">{{sev==='HIGH' ? '高' : sev==='MEDIUM' ? '中' : '低'}}风险 · {{risksBy(sev).length}} 条</p>
      <button v-for="r in risksBy(sev)" :key="(r.itemId ?? r.userId ?? 0) + r.rule" class="risk press" :disabled="!r.itemId" @click="r.itemId && (openId=r.itemId, drawerOpen=true)">
        <span class="rcat">{{ruleLabel(r.rule)}}</span><span class="grow">{{r.itemCode ? r.itemCode+' · ' : ''}}{{r.message}}</span>
      </button>
    </div>
    <p v-if="!(data?.risks ?? []).length" class="small">暂无风险信号。</p>
  </GlassPanel>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import Avatar from '../components/Avatar.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import AiBadge from '../components/AiBadge.vue'
import { api, ApiError } from '../api/http'; import { MEMBER_STATUS_META, type OverviewData, type RiskSignal } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
import { clampPct } from '../utils/progress'
const team = useTeam(), toast = useToast()
const data = ref<OverviewData|null>(null), loading = ref(false), loadErr = ref('')
const drawerOpen = ref(false), openId = ref<number|null>(null)
const aiRisk = ref<{itemId:number|null;itemCode:string|null;reason:string;action:string}[]|null>(null), aiGen = ref(false)
const isLeader = computed(()=> team.current?.myRole==='LEADER' || team.current?.myRole==='PO')
const health = computed(()=> data.value?.iterationHealth ?? null)
const RULE_LABELS: Record<string,string> = { BLOCKED_LONG: '阻塞过久', OVERDUE: '已逾期', DUE_SOON: '即将到期', STALE: '长期未更新', NUDGED_OFTEN: '被催多次', UNCLAIMED_PRIORITY: '高优先未认领', REVIEW_BACKLOG: '待验收积压' }
const ruleLabel = (r:string)=> RULE_LABELS[r] ?? r
function risksBy(sev:string){ return (data.value?.risks ?? []).filter(r=>r.severity===sev) as RiskSignal[] }
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''; try { data.value = await api.get(`/teams/${team.currentId}/overview`); const rs = await api.get(`/teams/${team.currentId}/ai/risk-summary`).catch(()=>null); aiRisk.value = rs?.top ?? null; aiGen.value = !!rs?.aiGenerated } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
async function scan(){ try { const r = await api.post(`/teams/${team.currentId}/reminders/scan`); toast.show(`扫描完成，生成提醒 ${r.generated} 条`); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '扫描失败') } }
</script>
<style scoped>
.head,.panel{padding:16px 18px;margin-bottom:14px}
.hrow{display:flex;align-items:center;gap:12px}.hrow h2{font-size:17px}
.stats{display:flex;gap:20px;flex-wrap:wrap;margin:10px 0 6px;font-size:13.5px;color:var(--text-2)}.stats b{color:var(--text-1);font-size:15px}
.bad{color:var(--c-red);font-weight:700}
.trow{display:grid;grid-template-columns:1.6fr 1fr .7fr .7fr .9fr .9fr .8fr;gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--field-brd);font-size:13.5px}
.trow.thead{font-size:12px;color:var(--text-2);font-weight:600}
.c1{display:flex;gap:8px;align-items:center;font-weight:600}
.free{font-weight:700}.free.FREE{color:#1D7A3A}.free.BUSY{color:var(--text-2)}
.sev{font-weight:700;margin:12px 0 4px}.sev.HIGH{color:var(--c-red)}.sev.MEDIUM{color:#B7791F}.sev.LOW{color:var(--text-2)}
.risk{display:flex;gap:10px;align-items:center;width:100%;text-align:left;padding:8px 10px;border-radius:10px}.risk:hover{background:var(--hover)}.risk .grow{flex:1;font-weight:500}
.rcat{font-size:11px;font-weight:700;background:rgba(255,59,48,.1);color:var(--c-red);border-radius:6px;padding:2px 7px;flex:none}
.airisk{display:flex;gap:10px;align-items:center;padding:7px 0;border-bottom:1px solid var(--field-brd);flex-wrap:wrap}.airisk .grow{flex:1;text-align:left;font-weight:500}.airisk .sug{color:#3730A3}
</style>
