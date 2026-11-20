<template>
<AppShell title="迭代" @refresh="load">
  <div class="filters glass">
    <span class="small">共 {{iterations.length}} 个迭代</span><span style="flex:1"/>
    <AppButton v-if="isLeader" @click="createOpen=true"><Plus :size="15"/>新建迭代</AppButton>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <div class="layout">
    <div class="list">
      <button v-for="it in iterations" :key="it.id" class="icard press" :class="{on: selected?.id===it.id}" @click="select(it)">
        <div class="irow"><b>{{it.name}}</b><span class="chip" :class="it.status">{{statusLabel(it.status)}}</span></div>
        <p class="small">{{it.startDate ?? '—'}} ～ {{it.endDate ?? '—'}}</p>
        <p v-if="it.goal" class="tiny goal">{{it.goal}}</p>
      </button>
      <p v-if="!iterations.length" class="small">暂无迭代，点右上角新建。</p>
    </div>
    <div class="detail" v-if="selected">
      <GlassPanel class="dhead">
        <div class="irow"><h2>{{selected.name}}</h2><span class="chip" :class="selected.status">{{statusLabel(selected.status)}}</span><span style="flex:1"/>
          <AppButton v-if="isLeader && selected.status==='PLANNING'" @click="startIteration">开始迭代</AppButton>
          <AppButton v-if="isLeader && selected.status==='ACTIVE'" variant="danger" @click="openClose">结束迭代</AppButton>
          <AppButton v-if="isLeader && selected.status==='FINISHED' && canUndoClose" variant="ghost" :disabled="busy.is('undoClose')" @click="undoClose">撤销结束（24h 内）</AppButton>
          <AppButton v-if="isLeader && selected.status!=='ACTIVE'" variant="ghost" :disabled="busy.is('delIter')" @click="deleteIteration">删除迭代</AppButton>
          <RouterLink :to="`/planning`" class="small" style="color:var(--theme)">迭代规划 →</RouterLink>
        </div>
        <p class="small">{{selected.startDate ?? '—'}} ～ {{selected.endDate ?? '—'}}<span v-if="selected.goal"> · 目标：{{selected.goal}}</span></p>
        <div class="stats">
          <span>事项 {{iterItems.length}} 件</span><span>已完成 {{doneCount}} 件</span><span>完成率 {{iterItems.length ? Math.round(doneCount/iterItems.length*100) : 0}}%</span><span>剩余估算 {{remainingEst}} 点</span>
        </div>
      </GlassPanel>

      <GlassPanel class="chart" v-if="burndown">
        <h3>燃尽图</h3>
        <svg :viewBox="`0 0 ${W} ${H}`" class="burn" role="img" aria-label="燃尽图">
          <line :x1="PAD" :y1="H-PAD" :x2="W-PAD/2" :y2="H-PAD" class="axis"/>
          <line :x1="PAD" :y1="PAD/2" :x2="PAD" :y2="H-PAD" class="axis"/>
          <polyline v-if="idealPath" :points="idealPath" class="ideal"/>
          <polyline v-if="actualPath" :points="actualPath" class="actual"/>
          <circle v-for="(p,i) in burndown.points" :key="i" :cx="xOf(p.date)" :cy="yOf(p.remainingEstimate)" r="3.5" class="dot"/>
          <text :x="PAD" :y="H-8" class="svg-t">{{burndown.points[0]?.date ?? selected.startDate}}</text>
          <text :x="W-PAD/2" :y="H-8" text-anchor="end" class="svg-t">{{selected.endDate}}</text>
          <text :x="4" :y="PAD/2+4" class="svg-t">{{maxY}}</text>
        </svg>
        <p class="tiny">实线为每日剩余估算（快照＋当天实时点），虚线为理想线（按工作日线性降至结束日 0）。快照每晚 23:30 自动记录，也可在下方手动补记。</p>
        <AppButton variant="ghost" @click="snapshotNow">立即补记今日快照</AppButton>
      </GlassPanel>

      <GlassPanel class="keys">
        <h3>关键日期</h3>
        <div v-for="kd in keyDates" :key="kd.id" class="kdrow">
          <span class="kdcat" :class="kd.category">{{catLabel(kd.category)}}</span><b>{{kd.title}}</b><span class="small">{{kd.date}}</span><span style="flex:1"/>
          <button v-if="canManage" class="icon sm press" aria-label="删除关键日期" @click="removeKeyDate(kd)"><X :size="14"/></button>
        </div>
        <p v-if="!keyDates.length" class="small">暂无关键日期。</p>
        <div v-if="canManage" class="foot-row" style="margin-top:10px">
          <input v-model="kdForm.title" class="flexin" placeholder="如：封版"/>
          <input type="date" v-model="kdForm.date" class="flexin" style="flex:0 0 160px"/>
          <select v-model="kdForm.category" class="flexin" style="flex:0 0 110px"><option value="FREEZE">封版</option><option value="RELEASE">投产</option><option value="REVIEW">评审</option><option value="OTHER">其他</option></select>
          <AppButton @click="addKeyDate">添加</AppButton>
        </div>
      </GlassPanel>

      <GlassPanel class="items">
        <h3>迭代外工作（独立事项）</h3>
        <p class="tiny" style="margin:4px 0 8px">进行中的缺陷 / 分析 / 临时事项：计入个人负载与总览，不计入本迭代燃尽图（P13），在迭代总结里单列。</p>
        <button v-for="it in outsideItems" :key="it.id" class="iitem press" @click="openId=it.id;drawerOpen=true">
          <span class="tdot" :style="{background:itemColor(it)}"/><span class="small">{{it.code}}</span><span class="grow">{{it.title}}</span>
          <span class="tiny">{{STATUS_META[it.status].label}}</span><span class="tiny">{{it.estimate ?? '—'}} 点</span>
        </button>
        <p v-if="!outsideItems.length" class="small">暂无进行中的迭代外事项。</p>
      </GlassPanel>

      <GlassPanel class="items">
        <h3>迭代事项</h3>
        <button v-for="it in iterItems" :key="it.id" class="iitem press" @click="openId=it.id;drawerOpen=true">
          <span class="tdot" :style="{background:itemColor(it)}"/><span class="small">{{it.code}}</span><span class="grow">{{it.title}}</span>
          <span class="tiny">{{STATUS_META[it.status].label}}</span><span class="tiny">{{it.estimate ?? '—'}} 点</span>
        </button>
        <p v-if="!iterItems.length" class="small">本迭代暂无事项（在新建事项或规划页把事项排进本迭代）。</p>
      </GlassPanel>
    </div>
    <p v-else class="small">从左侧选择一个迭代查看详情。</p>
  </div>

  <Modal :open="createOpen" @close="createOpen=false">
    <h2>新建迭代</h2>
    <AppInput v-model="form.name" label="名称" placeholder="如：迭代 12"/>
    <AppInput v-model="form.goal" type="textarea" label="目标" placeholder="本迭代要达成什么" style="margin-top:10px"/>
    <div class="foot-row" style="margin-top:10px"><label class="small">开始<input type="date" v-model="form.startDate" class="flexin"/></label><label class="small">结束<input type="date" v-model="form.endDate" class="flexin"/></label></div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="createOpen=false">取消</AppButton><AppButton @click="createIteration">创建</AppButton></div>
  </Modal>

  <Modal :open="closeOpen" @close="closeOpen=false">
    <h2>结束迭代 · 结转</h2>
    <div v-if="closePreview" class="preview-box">
      <p class="small"><b>结束后果（后端预览）：</b>未完成 {{closePreview.unfinishedCount}} 件；{{closePreview.hasPlanningIteration ? '已有规划中的下一迭代，未处理项默认结转过去' : '暂无规划中的下一迭代，未处理项默认退回待办池（或在下方选择新建下一迭代）'}}。结束后 {{closePreview.undoWindowHours}} 小时内组长可撤销结束。</p>
      <p v-for="w in closePreview.willGenerate" :key="w" class="tiny">· {{w}}</p>
    </div>
    <p class="small">逐项决定未完成事项去向：结转到下一迭代，或退回待办池。已完成/已取消事项不处理。</p>
    <div v-for="it in unfinished" :key="it.id" class="crow">
      <span class="small">{{it.code}}</span><span class="grow">{{it.title}}</span>
      <label class="small"><input type="radio" :name="'d'+it.id" value="NEXT" v-model="decisions[it.id]"/> 结转</label>
      <label class="small"><input type="radio" :name="'d'+it.id" value="BACKLOG" v-model="decisions[it.id]"/> 待办池</label>
    </div>
    <p v-if="!unfinished.length" class="small">没有未完成事项，可直接结束。</p>
    <label v-if="needsNext && !hasPlanning" class="small" style="display:block;margin-top:10px"><input type="checkbox" v-model="createNext"/> 没有未开始的迭代，自动新建下一迭代承接结转</label>
    <AppInput v-model="improvementsText" type="textarea" label="复盘改进项（每行一条，将生成到下一迭代并打「改进项」标签）" placeholder="如：补齐自动化测试" style="margin-top:10px"/>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="closeOpen=false">取消</AppButton><AppButton variant="danger" :disabled="closing" @click="confirmClose">{{ closing ? '结束中…' : '结束迭代并生成总结' }}</AppButton></div>
  </Modal>

  <Modal :open="nextOpen" @close="nextOpen=false">
    <h2>迭代已结束 · 开始下一个迭代</h2>
    <template v-if="nextPlanning">
      <p class="small">已有规划中的迭代「{{nextPlanning.name}}」（{{nextPlanning.startDate}} ～ {{nextPlanning.endDate}}），可直接开始。</p>
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="nextOpen=false">稍后再说</AppButton><AppButton :disabled="busy.is('startNext')" @click="startNextPlanning">开始「{{nextPlanning.name}}」</AppButton></div>
    </template>
    <template v-else>
      <p class="small">当前没有规划中的迭代，快速新建一个并立即开始：</p>
      <AppInput v-model="nextForm.name" label="名称" placeholder="如：迭代 14"/>
      <div class="foot-row" style="margin-top:10px"><label class="small">开始<input type="date" v-model="nextForm.startDate" class="flexin"/></label><label class="small">结束<input type="date" v-model="nextForm.endDate" class="flexin"/></label></div>
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="nextOpen=false">稍后再说</AppButton><AppButton :disabled="busy.is('createNext')" @click="createAndStartNext">新建并开始</AppButton></div>
    </template>
  </Modal>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Plus, X } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'; import Modal from '../components/Modal.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'; import { STATUS_META, itemColor, type Item, type Iteration, type KeyDate, type Burndown, type ClosePreview } from '../api/types'
import { useBusy } from '../utils/busy'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const team = useTeam(), toast = useToast(), route = useRoute(), busy = useBusy()
const iterations = ref<Iteration[]>([]), selected = ref<Iteration|null>(null)
const iterItems = ref<Item[]>([]), keyDates = ref<KeyDate[]>([]), burndown = ref<Burndown|null>(null)
const createOpen = ref(false), closeOpen = ref(false), nextOpen = ref(false)
const nextForm = reactive({ name: '', startDate: '', endDate: '' })
const loading = ref(false), loadErr = ref(''), closing = ref(false)
let closeKey = ''
const form = reactive({ name: '', goal: '', startDate: '', endDate: '' })
const kdForm = reactive({ title: '', date: '', category: 'FREEZE' })
const decisions = reactive<Record<number,string>>({}), createNext = ref(true), improvementsText = ref('')
const drawerOpen = ref(false), openId = ref<number|null>(null)
const closePreview = ref<ClosePreview|null>(null), allItemsCache = ref<Item[]>([])
const outsideItems = computed(()=> allItemsCache.value.filter(i=> i.iterationId==null && i.type!=='TASK' && !['DONE','CANCELLED'].includes(i.status)))
// 撤销结束可用性：前端以本地记录的结束时间粗判 24h（真值以后端 undo-close 校验为准，过期后端会明确报错）。
const closedAtMap = ref<Record<number, number>>({})
const canUndoClose = computed(()=> { const t = selected.value ? closedAtMap.value[selected.value.id] : 0; return !!t && Date.now() - t < 24 * 3600 * 1000 })
async function undoClose(){ if (!selected.value) return; if (!confirm('确定撤销结束本次迭代吗？将恢复结转前的事项归属，并删除本次自动生成的改进项与总结（已被操作过的会保留并提示）。')) return
  await busy.wrap('undoClose', async () => { try { const r = await api.post(`/teams/${team.currentId}/iterations/${selected.value!.id}/undo-close`); toast.show(r.keptImprovementIds?.length ? `已撤销结束，恢复 ${r.restoredItems} 件事项；${r.keptImprovementIds.length} 条改进项已被操作，已保留` : `已撤销结束，恢复 ${r.restoredItems ?? 0} 件事项，迭代恢复进行中`); if (selected.value) delete closedAtMap.value[selected.value.id]; team.loadTeamData(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '撤销失败') } }) }
const canManage = computed(()=> team.current?.myRole==='LEADER' || team.current?.myRole==='PO')
const isLeader = computed(()=> team.current?.myRole==='LEADER') // Q3：迭代新建/编辑/开始/结束/删除仅组长
const statusLabel = (s:string)=> s==='ACTIVE' ? '进行中' : s==='FINISHED' ? '已结束' : '未开始'
const catLabel = (c:string)=> ({FREEZE:'封版',RELEASE:'投产',REVIEW:'评审',OTHER:'其他'} as Record<string,string>)[c] ?? c
const doneCount = computed(()=> iterItems.value.filter(i=>i.status==='DONE').length)
const remainingEst = computed(()=> iterItems.value.filter(i=>!['DONE','CANCELLED'].includes(i.status)).reduce((n,i)=>n+(i.estimate??0),0))
const unfinished = computed(()=> iterItems.value.filter(i=>!['DONE','CANCELLED'].includes(i.status)))
const hasPlanning = computed(()=> iterations.value.some(i=>i.status==='PLANNING' && i.id!==selected.value?.id))
const nextPlanning = computed(()=> iterations.value.filter(i=>i.status==='PLANNING').sort((a,b)=>a.id-b.id)[0] ?? null)
const needsNext = computed(()=> unfinished.value.some(i=> (decisions[i.id] ?? 'NEXT')==='NEXT'))
// 燃尽图几何
const W = 720, H = 260, PAD = 44
const allDates = computed(()=> { const ds = new Set<string>(); burndown.value?.points.forEach(p=>ds.add(p.date)); burndown.value?.ideal.forEach(p=>ds.add(p.date)); if (selected.value?.endDate) ds.add(selected.value.endDate); return [...ds].sort() })
const maxY = computed(()=> Math.max(1, burndown.value?.totalEstimate ?? 0, ...((burndown.value?.points ?? []).map(p=>p.remainingEstimate))))
function xOf(date:string){ const ds = allDates.value; const i = Math.max(0, ds.indexOf(date)); return PAD + (ds.length<=1 ? 0 : i/(ds.length-1)) * (W - PAD - PAD/2) }
function yOf(v:number){ return (H-PAD) - (Math.max(0, v) / maxY.value) * (H - PAD - PAD/2) } // 剩余估算为负时钳到 0，不画到轴下（1.0.4 F）
const actualPath = computed(()=> (burndown.value?.points ?? []).map(p=>`${xOf(p.date)},${yOf(p.remainingEstimate)}`).join(' '))
const idealPath = computed(()=> (burndown.value?.ideal ?? []).map(p=>`${xOf(p.date)},${yOf(p.remainingEstimate)}`).join(' '))
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''
  try { iterations.value = await api.get(`/teams/${team.currentId}/iterations`)
    const want = Number(route.params.id ?? 0)
    const target = iterations.value.find(i=>i.id===want) ?? iterations.value.find(i=>i.status==='ACTIVE') ?? iterations.value[0] ?? null
    if (target) await select(target); else selected.value = null
  } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
async function select(it: Iteration){ selected.value = it; if(!team.currentId) return
  iterItems.value = await api.get(`/teams/${team.currentId}/items?scope=all&iterationId=${it.id}`).catch(()=>[])
  allItemsCache.value = await api.get(`/teams/${team.currentId}/items?scope=all`).catch(()=>allItemsCache.value)
  keyDates.value = await api.get(`/teams/${team.currentId}/iterations/${it.id}/key-dates`).catch(()=>[])
  burndown.value = await api.get(`/teams/${team.currentId}/iterations/${it.id}/burndown`).catch(()=>null) }
onMounted(load); watch(()=>route.params.id, load)
async function createIteration(){ if(!form.name.trim()){ toast.show('请填写迭代名称'); return }
  try { const it = await api.post(`/teams/${team.currentId}/iterations`, { name: form.name.trim(), goal: form.goal || undefined, startDate: form.startDate || undefined, endDate: form.endDate || undefined }); createOpen.value=false; toast.show('迭代已创建'); await load(); if (it?.id) { const t = iterations.value.find(x=>x.id===it.id); if (t) select(t) } } catch(e){ toast.show(e instanceof ApiError ? e.message : '创建失败') } }
async function startIteration(){ if(!selected.value) return; try { await api.post(`/teams/${team.currentId}/iterations/${selected.value.id}/start`); toast.show('迭代已开始'); team.loadTeamData(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '开始失败') } }
async function snapshotNow(){ if(!selected.value) return; try { await api.post(`/teams/${team.currentId}/iterations/${selected.value.id}/snapshot`); toast.show('今日快照已记录'); select(selected.value) } catch(e){ toast.show(e instanceof ApiError ? e.message : '记录失败') } }
async function addKeyDate(){ if(!selected.value || !kdForm.title.trim() || !kdForm.date){ toast.show('请填写标题与日期'); return }
  try { await api.post(`/teams/${team.currentId}/iterations/${selected.value.id}/key-dates`, { title: kdForm.title.trim(), date: kdForm.date, category: kdForm.category }); kdForm.title=''; toast.show('关键日期已添加'); select(selected.value) } catch(e){ toast.show(e instanceof ApiError ? e.message : '添加失败') } }
async function removeKeyDate(kd: KeyDate){ try { await api.del(`/teams/${team.currentId}/key-dates/${kd.id}`); toast.show('已删除'); if (selected.value) select(selected.value) } catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } }
async function openClose(){ for (const it of unfinished.value) decisions[it.id] ??= 'NEXT'; improvementsText.value=''; createNext.value = true; closeKey = newIdempotencyKey(); closePreview.value = null; closeOpen.value = true
  if (selected.value) closePreview.value = await api.get(`/teams/${team.currentId}/iterations/${selected.value.id}/close-preview`).catch(()=>null) }
async function confirmClose(){ if(!selected.value || closing.value) return; closing.value = true
  try { if (!closeKey) closeKey = newIdempotencyKey(); const res = await api.postKey(`/teams/${team.currentId}/iterations/${selected.value.id}/close`, { decisions: unfinished.value.map(i=>({ itemId: i.id, action: decisions[i.id] ?? 'NEXT' })), improvements: improvementsText.value.split('\n').map(s=>s.trim()).filter(Boolean), createNextIteration: createNext.value }, closeKey)
    closeOpen.value=false; closeKey=''; if (selected.value) closedAtMap.value[selected.value.id] = Date.now(); toast.show(`迭代已结束：结转 ${res.movedToNext} 件、退回待办池 ${res.movedToBacklog} 件，总结报告已生成`); team.loadTeamData(); await load(); nextOpen.value = true } catch(e){ toast.show(e instanceof ApiError ? e.message : '结束失败') } finally { closing.value = false } }
async function deleteIteration(){ if(!selected.value) return; if (!confirm(`确定删除迭代「${selected.value.name}」吗？其中事项会回到未排期状态。`)) return; await busy.wrap('delIter', async () => { try { await api.del(`/teams/${team.currentId}/iterations/${selected.value!.id}`); toast.show('迭代已删除'); selected.value = null; load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } }) }
async function startNextPlanning(){ const it = nextPlanning.value; if (!it) return; await busy.wrap('startNext', async () => { try { await api.post(`/teams/${team.currentId}/iterations/${it.id}/start`); toast.show(`「${it.name}」已开始`); nextOpen.value = false; team.loadTeamData(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '开始失败') } }) }
async function createAndStartNext(){ if (!nextForm.name.trim()) { toast.show('请填写迭代名称'); return } await busy.wrap('createNext', async () => { try { const it = await api.post(`/teams/${team.currentId}/iterations`, { name: nextForm.name.trim(), startDate: nextForm.startDate || undefined, endDate: nextForm.endDate || undefined }); await api.post(`/teams/${team.currentId}/iterations/${it.id}/start`); toast.show(`「${it.name}」已新建并开始`); nextOpen.value = false; nextForm.name = ''; team.loadTeamData(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '新建并开始失败') } }) }
</script>
<style scoped>
.filters{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;margin-bottom:14px}
.layout{display:grid;grid-template-columns:280px 1fr;gap:14px;align-items:start}
.list{display:flex;flex-direction:column;gap:10px}
.icard{text-align:left;background:var(--card);border:1px solid rgba(255,255,255,.65);border-radius:var(--r-card);padding:12px 14px;box-shadow:0 4px 14px rgba(10,60,140,.06)}
.icard.on{outline:2px solid var(--theme)}
.irow{display:flex;align-items:center;gap:10px}.irow h2{font-size:17px}
.chip{font-size:11.5px;font-weight:700;border-radius:99px;padding:2px 9px;background:var(--track);color:var(--text-2)}
.chip.ACTIVE{background:rgba(52,199,89,.18);color:#1D7A3A}.chip.FINISHED{background:var(--sel);color:#0A5CD6}
.goal{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.detail{display:flex;flex-direction:column;gap:14px;min-width:0}
.dhead,.chart,.keys,.items{padding:16px 18px}
.stats{display:flex;gap:16px;margin-top:10px;font-size:13px;color:var(--text-2);flex-wrap:wrap}
.burn{width:100%;height:auto;margin-top:8px}
.axis{stroke:var(--field-brd);stroke-width:1}
.ideal{fill:none;stroke:var(--text-3);stroke-width:2;stroke-dasharray:6 5}
.actual{fill:none;stroke:var(--theme);stroke-width:2.5}
.dot{fill:var(--theme)}
.svg-t{font-size:11px;fill:var(--text-3)}
.kdrow{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--field-brd)}
.kdcat{font-size:11px;font-weight:700;border-radius:6px;padding:2px 7px;background:var(--sel);color:#0A5CD6}
.kdcat.FREEZE{background:rgba(0,122,255,.14)}.kdcat.RELEASE{background:rgba(52,199,89,.16);color:#1D7A3A}.kdcat.REVIEW{background:rgba(175,82,222,.14);color:#7D2CA8}
.foot-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.flexin{flex:1;min-height:40px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px;min-width:120px}
.iitem{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:9px 10px;border-radius:11px}.iitem:hover{background:var(--hover)}.iitem .grow{flex:1;font-weight:500}
.tdot{width:8px;height:8px;border-radius:50%;flex:none}
.preview-box{background:var(--sel);border-radius:12px;padding:10px 12px;margin-bottom:10px}
.crow{display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--field-brd)}.crow .grow{flex:1;font-weight:500}
.icon.sm{width:26px;height:26px;display:grid;place-items:center;border-radius:8px}
@media(max-width:900px){.layout{grid-template-columns:1fr}}
</style>
