<template>
<AppShell :title="planning ? `迭代规划 · ${planning.iteration.name}` : '迭代规划'" @refresh="load">
  <div v-if="selectable.length" class="selrow">
    <label class="small" for="plan-iter">规划迭代</label>
    <select id="plan-iter" class="sel" :value="selectedId ?? ''" @change="onSelect($event)">
      <option v-for="it in selectable" :key="it.id" :value="it.id">{{ it.name }}（{{ it.status === 'PLANNING' ? '规划中' : '进行中' }} · {{ it.startDate }} ～ {{ it.endDate }}）</option>
    </select>
    <span class="tiny">默认选中规划中的迭代（没有则进行中）；两个迭代都可以放入 / 移出故事。</span>
  </div>
  <template v-if="planning">
    <GlassPanel class="cap">
      <div class="caprow"><b>容量（故事点口径）</b><span class="small">本迭代共认领 {{totalPoints}} {{unit}} · {{planning.iteration.startDate}} ～ {{planning.iteration.endDate}}</span></div>
      <ProgressBar :value="capPct" style="margin:8px 0"/>
      <div class="loads">
        <div v-for="m in team.members" :key="m.userId" class="load">
          <Avatar :name="m.displayName" :color="m.avatarColor" :size="24"/><span class="lname">{{m.displayName}}</span>
          <div class="lbar"><div class="lfill" :style="{width: loadPct(m.userId)+'%', background: over(m.userId) ? 'var(--c-red)' : 'var(--theme)'}"/></div>
          <span class="small">{{loadOf(m.userId).points}} {{unit}} / 剩余容量 {{capOf(m.userId).capacityPoints}} {{unit}}（剩余 {{capOf(m.userId).remainingDays}} 天 × 系数 {{coef}}）{{over(m.userId) ? ' · 超容量' : ''}}</span>
        </div>
      </div>
      <p class="tiny">容量口径：个人剩余容量 = 剩余可用工作日 × 小组容量系数（每人每个工作日约等于几个故事点，默认 1，在小组设置里调整）；请假/出差覆盖日不计；共同负责的故事点按人数均分。超容量只提示、不拦截。</p>
    </GlassPanel>
    <div v-for="g in planning.groups" :key="g.task?.id ?? 'indie'" class="group">
      <h2 class="ghead"><template v-if="g.task"><span class="lcode">{{g.task.code}}</span> {{g.task.title}} <RouterLink :to="`/task/${g.task.id}`" class="small link">任务详情 →</RouterLink></template><template v-else>独立事项</template></h2>
      <div class="prow glass" v-for="it in g.items" :key="it.id">
        <span class="tdot" :style="{background:itemColor(it)}"/>
        <button class="grow press tbtn" @click="openId=it.id;drawerOpen=true"><span class="small">{{it.code}}</span> {{it.title}} <span class="tiny">{{STATUS_META[it.status].label}}</span></button>
        <label class="est">估算<input type="number" min="0" step="0.5" :value="it.estimate ?? ''" @change="setEstimate(it, $event)" aria-label="估算"/> {{unit}}</label>
        <AvatarStack v-if="it.ownerIds.length" :ids="it.ownerIds" :size="22"/>
        <AppButton v-if="it.status==='UNCLAIMED'" :disabled="busy.is('claim'+it.id)" @click="claim(it)">认领</AppButton>
        <AppButton v-else-if="canJoin(it)" variant="ghost" :disabled="busy.is('join'+it.id)" @click="join(it)">加入</AppButton>
        <AppButton v-if="canPlan" variant="ghost" :disabled="busy.is('move'+it.id)" @click="moveOut(it)">移出迭代</AppButton>
      </div>
      <p v-if="!g.items.length" class="small">该组在本迭代暂无事项。</p>
    </div>
    <div v-if="canPlan" class="group">
      <h2 class="ghead">未排入本迭代的故事 <span class="small">（组长 / PO 可放入）</span></h2>
      <div class="prow glass" v-for="it in candidates" :key="it.id">
        <span class="tdot" :style="{background:itemColor(it)}"/>
        <span class="tbtn"><span class="small">{{it.code}}</span> {{it.title}}</span>
        <AppButton variant="ghost" :disabled="busy.is('move'+it.id)" @click="moveIn(it)">放入迭代</AppButton>
      </div>
      <p v-if="!candidates.length" class="small">没有可放入的故事。</p>
    </div>
  </template>
  <p v-else-if="!loading" class="small">当前没有规划中或进行中的迭代，规划页暂无数据。</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import Avatar from '../components/Avatar.vue'; import AvatarStack from '../components/AvatarStack.vue'; import ProgressBar from '../components/ProgressBar.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import { api, ApiError } from '../api/http'; import { STATUS_META, itemColor, estimateUnitLabel, type Item, type Iteration, type Planning } from '../api/types'
import { useBusy } from '../utils/busy'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const team = useTeam(), auth = useAuth(), toast = useToast(), busy = useBusy()
const planning = ref<Planning|null>(null), loading = ref(false)
// 0.5.3 结论 1：规划页顶部迭代选择器——可选规划中 + 进行中迭代，默认 planning-target（规划中优先）
const iterations = ref<Iteration[]>([]), selectedId = ref<number|null>(null)
const selectable = computed(()=> iterations.value.filter(i => i.status === 'PLANNING' || i.status === 'ACTIVE'))
const drawerOpen = ref(false), openId = ref<number|null>(null)
const candidates = ref<Item[]>([])
const canPlan = computed(()=> team.current?.myRole==='LEADER' || team.current?.myRole==='PO') // Q3：规划增减组长+PO
const unit = computed(()=> estimateUnitLabel(team.current))
const totalPoints = computed(()=> planning.value?.loads.reduce((n,l)=>n+l.points,0) ?? 0)
const totalCap = computed(()=> planning.value?.capacities.reduce((n,c)=>n+c.capacityPoints,0) ?? 0)
const capPct = computed(()=> totalCap.value ? Math.min(100, totalPoints.value / totalCap.value * 100) : 0)
const coef = computed(()=> team.current?.settings?.capacityPointsPerWorkday ?? 1)
function loadOf(uid:number){ return planning.value?.loads.find(l=>l.userId===uid) ?? { points: 0, itemCount: 0 } }
function capOf(uid:number){ return planning.value?.capacities.find(c=>c.userId===uid) ?? { availableDays: 0, claimedPoints: 0, remainingDays: 0, capacityPoints: 0, overCapacity: false } }
function over(uid:number){ return capOf(uid).overCapacity }
function loadPct(uid:number){ const c = capOf(uid); return c.capacityPoints ? Math.min(100, loadOf(uid).points / c.capacityPoints * 100) : (loadOf(uid).points ? 100 : 0) }
async function load(){ if(!team.currentId) return; loading.value = true
  try {
    iterations.value = await api.get<Iteration[]>(`/teams/${team.currentId}/iterations`).catch(()=>[] as Iteration[])
    if (selectedId.value == null || !selectable.value.some(i => i.id === selectedId.value)) {
      const target = await api.get<Iteration>(`/teams/${team.currentId}/iterations/planning-target`).catch(()=>null)
      selectedId.value = target?.id ?? team.iteration?.id ?? null
    }
    if (selectedId.value == null) { planning.value = null; return }
    planning.value = await api.get(`/teams/${team.currentId}/iterations/${selectedId.value}/planning`)
    if (canPlan.value) { const all = await api.get<Item[]>(`/teams/${team.currentId}/items?scope=all`).catch(()=>[] as Item[]); candidates.value = all.filter(i => i.type==='STORY' && !i.iterationId) }
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '加载失败') } finally { loading.value = false } }
function onSelect(ev: Event){ selectedId.value = Number((ev.target as HTMLSelectElement).value); load() }
async function moveIn(it: Item){ if (selectedId.value == null) return; await busy.wrap('move' + it.id, async () => { try { await api.patch(`/teams/${team.currentId}/items/${it.id}`, { version: it.version, iterationId: selectedId.value }); toast.show('已放入迭代'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '放入失败'); load() } }) }
async function moveOut(it: Item){ await busy.wrap('move' + it.id, async () => { try { await api.patch(`/teams/${team.currentId}/items/${it.id}`, { version: it.version, iterationId: null }); toast.show('已移出迭代'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '移出失败'); load() } }) }
onMounted(load)
async function setEstimate(it: Item, ev: Event){ const v = (ev.target as HTMLInputElement).value; const est = v === '' ? null : Number(v)
  try { await api.patch(`/teams/${team.currentId}/items/${it.id}`, { version: it.version, estimate: est }); toast.show('估算已更新'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '更新失败'); load() } }
async function claim(it: Item){ await busy.wrap('claim' + it.id, async () => { try { await api.post(`/teams/${team.currentId}/items/${it.id}/transitions`, { action: 'CLAIM', version: it.version }); toast.show('已认领'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '认领失败'); load() } }) }
function canJoin(it: Item){ return !!auth.user && it.ownerIds.length > 0 && !it.ownerIds.includes(auth.user.id) && !['DONE','CANCELLED'].includes(it.status) }
async function join(it: Item){ await busy.wrap('join' + it.id, async () => { try { await api.post(`/teams/${team.currentId}/items/${it.id}/join`, { version: it.version }); toast.show('已加入共同负责'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '加入失败'); load() } }) }
</script>
<style scoped>
.selrow{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.sel{min-height:36px;border-radius:10px;border:1px solid var(--field-brd);background:var(--field);color:var(--text);padding:0 10px;font-size:13px;max-width:100%}
.cap{padding:16px 18px;margin-bottom:6px}
.caprow{display:flex;gap:12px;align-items:baseline;flex-wrap:wrap}
.loads{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:8px 18px;margin-top:6px}
.load{display:flex;align-items:center;gap:9px}.lname{width:52px;font-size:13px;font-weight:500}
.lbar{flex:1;height:8px;border-radius:99px;background:var(--track);overflow:hidden}.lfill{height:100%;border-radius:99px;transition:width .3s}
.ghead{font-size:16px;margin:18px 0 10px;display:flex;gap:9px;align-items:center}.lcode{color:var(--theme);font-weight:700}.ghead .link{color:var(--theme)}
.prow{border-radius:14px;padding:9px 14px;display:flex;gap:11px;align-items:center;margin-bottom:8px;flex-wrap:wrap}
.tdot{width:8px;height:8px;border-radius:50%;flex:none}
.tbtn{flex:1;text-align:left;font-weight:500;min-width:200px}
.est{font-size:12px;color:var(--text-2);display:inline-flex;gap:5px;align-items:center}.est input{width:70px;min-height:34px;border-radius:9px;border:1px solid var(--field-brd);background:var(--field);padding:0 8px}
</style>
