<template>
<AppShell title="站会" @refresh="load">
  <div class="bar glass">
    <label class="small">日期 <input type="date" v-model="date" class="flexin" style="flex:0 0 170px" @change="load"/></label>
    <span class="small">上一工作日 {{data?.previousWorkday ?? '—'}} · {{team.iteration?.name ?? '无进行中迭代'}}</span>
    <span class="small">摘要数据截至 {{data?.dataAsOf ? fmtTime(data.dataAsOf) : '—'}}</span>
    <AppButton v-if="canRefresh" variant="ghost" :disabled="refreshLeft > 0 || refreshing" @click="refreshSummary">{{ refreshing ? '刷新中…' : refreshLeft > 0 ? `刷新（${refreshLeft}s）` : '刷新摘要' }}</AppButton>
    <span style="flex:1"/>
    <button v-if="members.length>1" class="small press walk" @click="nextSpeaker">站会走查：{{speaker ? '当前 '+team.memberName(speaker) : '点此开始'}} →</button>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <div class="grid">
    <GlassPanel v-for="m in members" :key="m.userId" class="card" :class="{speaking: speaker===m.userId}">
      <div class="chead"><Avatar :name="team.memberName(m.userId)" :color="team.member(m.userId)?.avatarColor" :size="34"/><div class="grow"><b>{{team.memberName(m.userId)}}</b><span class="tiny">{{MEMBER_STATUS_META[m.memberStatus] ?? m.memberStatus}}<span v-if="team.member(m.userId)?.memberStatusStart || team.member(m.userId)?.memberStatusEnd"> {{team.member(m.userId)?.memberStatusStart ?? ''}}{{team.member(m.userId)?.memberStatusEnd ? ' ～ '+team.member(m.userId)?.memberStatusEnd : ''}}</span></span></div>
        <span v-if="m.blockedItems.length" class="blk"><OctagonAlert :size="14"/>{{m.blockedItems.length}} 阻塞</span></div>
      <div class="sec"><h4>昨日进展（{{data?.previousWorkday ?? ''}}）</h4>
        <p v-for="y in m.yesterdayProgress" :key="y.id" class="tiny line"><span class="cnum">{{codeOf(y.itemId)}}</span> {{y.text}}<span v-if="y.percent!=null">（{{y.percent}}%）</span></p>
        <p v-for="d in m.yesterdayDone" :key="'d'+d.id" class="tiny line done"><span class="cnum">{{d.code}}</span> 已完成：{{d.title}}</p>
        <p v-if="!m.yesterdayProgress.length && !m.yesterdayDone.length" class="tiny dim">昨日未登记进展</p></div>
      <div class="sec"><h4>今日计划</h4>
        <button v-for="it in m.todayItems" :key="it.id" class="tiny line press tbtn" @click="openId=it.id;drawerOpen=true"><span class="cnum">{{it.code}}</span> {{it.title}}</button>
        <p v-if="!m.todayItems.length" class="tiny dim">暂无在做事项</p></div>
      <div class="sec" v-if="m.blockedItems.length"><h4>阻塞</h4>
        <p v-for="b in m.blockedItems" :key="b.id" class="tiny line blocked"><span class="cnum">{{b.code}}</span> {{b.blockReason ?? '已标记阻塞'}}</p></div>
    </GlassPanel>
    <p v-if="!members.length" class="small">暂无成员数据。</p>
  </div>
  <GlassPanel class="notecard">
    <h3>站会备注（全组共享，按日保存）</h3>
    <textarea v-model="noteDraft" rows="3" placeholder="如：今天重点跟进封版风险；依赖事项等下午评审结论"/>
    <div style="display:flex;justify-content:flex-end;margin-top:8px"><AppButton @click="saveNote">保存备注</AppButton></div>
  </GlassPanel>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { OctagonAlert } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import Avatar from '../components/Avatar.vue'; import AppButton from '../components/AppButton.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import { api, ApiError } from '../api/http'; import { MEMBER_STATUS_META, type StandupData, type Item } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const team = useTeam(), toast = useToast()
const fmtTime = (isoStr: string) => { const d = new Date(isoStr); return isNaN(d.getTime()) ? isoStr : `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}` }
import { useAuth } from '../stores/auth'
const auth = useAuth(); void auth
// P12：摘要头「数据截至 HH:MM」+ 组长/PO 刷新（1 分钟节流，按钮倒计时置灰）；逐人卡片始终实时，不受影响。
const canRefresh = computed(()=> team.current?.myRole === 'LEADER' || team.current?.myRole === 'PO')
const refreshLeft = ref(0), refreshing = ref(false)
let refreshTimer: any = null
function startRefreshCountdown(sec: number){ refreshLeft.value = sec; clearInterval(refreshTimer); refreshTimer = setInterval(() => { refreshLeft.value = Math.max(0, refreshLeft.value - 1); if (!refreshLeft.value) clearInterval(refreshTimer) }, 1000) }
async function refreshSummary(){ if (refreshing.value || refreshLeft.value > 0) return; refreshing.value = true
  try { data.value = await api.post(`/teams/${team.currentId}/standup/refresh?date=${date.value}`); noteDraft.value = data.value?.note ?? ''; startRefreshCountdown(60); toast.show('摘要已刷新') }
  catch(e){ if (e instanceof ApiError && e.status === 429) { startRefreshCountdown(e.detail?.retryAfterSeconds ?? 60); toast.show(e.message) } else toast.show(e instanceof ApiError ? e.message : '刷新失败') } finally { refreshing.value = false } }
const now = new Date(); const iso = (d:Date)=> `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`
const date = ref(iso(now))
const data = ref<StandupData|null>(null), speaker = ref<number|null>(null), noteDraft = ref(''), loading = ref(false), loadErr = ref('')
const allItems = ref<Item[]>([])
const drawerOpen = ref(false), openId = ref<number|null>(null)
const members = computed(()=> data.value?.members ?? [])
const codeMap = computed(()=> { const m = new Map<number,string>(); for (const i of allItems.value) m.set(i.id, i.code); return m })
const codeOf = (id:number)=> codeMap.value.get(id) ?? ''
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''; try { data.value = await api.get(`/teams/${team.currentId}/standup?date=${date.value}`); noteDraft.value = data.value?.note ?? ''
  allItems.value = await api.get(`/teams/${team.currentId}/items?scope=all`).catch(()=>allItems.value) } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
function nextSpeaker(){ const ids = members.value.map(m=>m.userId); if (!ids.length) return; const i = speaker.value==null ? -1 : ids.indexOf(speaker.value); speaker.value = ids[(i+1) % ids.length]; toast.show('轮到：' + team.memberName(speaker.value)) }
async function saveNote(){ try { await api.put(`/teams/${team.currentId}/standup/note`, { date: date.value, content: noteDraft.value }); toast.show('备注已保存'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
</script>
<style scoped>
.bar{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;margin-bottom:14px;flex-wrap:wrap}
.flexin{min-height:36px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:4px 10px}
.walk{color:var(--theme);font-weight:600}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:14px}
.card{padding:16px 18px}.card.speaking{outline:2px solid var(--theme)}
.chead{display:flex;gap:10px;align-items:center}.chead .grow{flex:1;display:flex;flex-direction:column}
.blk{display:inline-flex;gap:4px;align-items:center;color:var(--c-red);font-size:12px;font-weight:700;background:rgba(255,59,48,.1);border-radius:99px;padding:3px 9px}
.sec{margin-top:12px}.sec h4{font-size:12px;color:var(--text-2);margin-bottom:5px}
.line{padding:3px 0;line-height:1.5}.line.blocked{color:var(--c-red)}.line.done{color:#1D7A3A}
.cnum{color:var(--text-3);font-weight:600}
.dim{color:var(--text-3)}
.tbtn{display:block;text-align:left;width:100%}
.notecard{padding:16px 18px;margin-top:14px}.notecard textarea{width:100%;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:8px 10px;margin-top:8px}
</style>
