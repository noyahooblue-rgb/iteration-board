<template>
<AppShell :title="boardTitle" @refresh="load">
  <div v-if="!team.iteration" class="noiter">
    <span class="grow"><b>当前没有进行中的迭代</b>，看板已自动切到「全部」范围。</span>
    <RouterLink to="/planning" class="nbtn press">去规划</RouterLink>
    <RouterLink to="/backlog" class="nbtn press">去待办池</RouterLink>
  </div>
  <QuickUpdateBox @changed="load"/>
  <div class="filters glass">
    <SegmentedControl v-model="scope" :options="[{label:'当前迭代',value:'current'},{label:'日常',value:'daily'},{label:'全部',value:'all'}]"/>
    <label class="chk"><input type="checkbox" v-model="onlyMine"/> 仅看我的</label>
    <button v-for="(m,t) in TYPE_META" :key="t" class="tchip press" :class="{off:fType && fType!==t}" :style="{borderColor:`color-mix(in srgb, ${m.color} 45%, transparent)`,background:`color-mix(in srgb, ${m.color} 10%, transparent)`}" @click="fType = fType===t ? '' : t" :aria-pressed="fType===t"><span class="tdot" :style="{background:m.color}"/>{{m.label}}</button>
    <label class="chk"><input type="checkbox" v-model="onlyBlocked"/> 仅阻塞</label>
    <label class="fld-sm">泳道<select v-model="laneMode" aria-label="泳道分组"><option value="task">按任务</option><option value="owner">按人</option><option value="type">按类型</option><option value="none">不分泳道</option></select></label>
    <label class="chk switchrow">显示已取消<span class="switch"><input type="checkbox" v-model="showCancelled"/><span class="tr"/></span></label>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <div v-for="lane in lanes" :key="lane.key" class="lane">
    <button v-if="laneMode!=='none'" class="lanehead press" @click="toggleLane(lane.key)">
      <ChevronDown :size="16" :class="{rot:collapsed.has(lane.key)}"/>
      <template v-if="lane.task">
        <span class="lcode">{{lane.task.code}}</span><b>{{lane.task.title}}</b>
        <span class="small">PO：{{team.memberName(lane.task.poId)}}</span>
        <span class="small">故事进度 {{lane.donePts}}/{{lane.totalPts}} {{unit}} · {{lane.doneCnt}}/{{lane.totalCnt}} 件</span>
        <ProgressBar :value="lane.totalPts? lane.donePts/lane.totalPts*100 : (lane.totalCnt? lane.doneCnt/lane.totalCnt*100:0)" style="width:130px"/>
        <span class="small link" @click.stop="$router.push(`/task/${lane.task.id}`)">任务详情 →</span>
      </template>
      <template v-else><b>{{lane.label}}</b><span class="small">{{lane.items.length}} 项</span></template>
    </button>
    <div v-if="!collapsed.has(lane.key)" class="board" :class="{dragging: dragId!==null}">
      <section v-for="col in colsComputed" :key="col.status" class="col" :class="{dim: dragOver===col.status && !canDrop(col.status), over: dragOver===col.status && canDrop(col.status)}"
        @dragover.prevent="dragOver=col.status" @dragleave="dragOver=null" @drop.prevent="onDrop(col.status)">
        <header class="colhead"><h2>{{col.label}}</h2><span class="cnt">{{laneCol(lane,col.status).length}} 项</span><span style="flex:1"/><span class="small">估算 {{estSum(laneCol(lane,col.status))}} {{unit}}</span></header>
        <div class="cards">
          <article v-for="it in sortedLane(lane,col.status)" :key="it.id" class="kcard press" draggable="true"
            :class="{blocked:it.blocked, dragging:dragId===it.id}" :style="{borderLeftColor:itemColor(it)}"
            @dragstart="dragId=it.id" @dragend="dragId=null;dragOver=null" @click="openId=it.id;drawerOpen=true">
            <span v-if="it.nudgeCount>=2" class="nudge" title="被催次数"><Zap :size="12"/>被催 {{it.nudgeCount}} 次</span>
            <div class="krow"><span class="small">{{it.code}}</span>
              <span v-if="it.type==='STORY' && parentOf(it)" class="ptag" :title="`所属任务 ${parentOf(it)!.title}`">{{parentOf(it)!.code}}</span>
              <span v-if="it.batchId" class="btag" title="批量下发事项"><Package :size="12"/>批次</span>
              <span style="flex:1"/><span class="prio">{{it.priority}}</span></div>
            <p class="ktitle">{{it.title}}</p>
            <p v-if="it.type==='STORY' && it.storyCategory" class="kcat"><span class="tdot" :style="{background:STORY_CAT_META[it.storyCategory].color}"/>{{STORY_CAT_META[it.storyCategory].label}}故事</p>
            <p v-if="it.blocked" class="kblock"><OctagonAlert :size="14"/> <span>阻塞：{{it.blockReason}}</span></p>
            <p v-if="it.rejected || readyMark(it) || (it.estimate==null && it.status==='IN_PROGRESS')" class="kmarks"><span v-if="it.rejected" class="mark red">已退回</span><span v-if="readyMark(it)" class="mark green">可提交验收</span><span v-if="it.estimate==null && it.status==='IN_PROGRESS'" class="mark amber">未估算</span></p>
            <ProgressBar v-if="showBar(it)" :value="barPct(it)" :color="barPct(it)>=100 ? 'var(--c-green)' : 'var(--theme)'"/>
            <div class="krow foot">
              <AvatarStack v-if="it.ownerIds.length" :ids="it.ownerIds" :size="22"/>
              <span v-else class="meta-ico"><Users :size="14"/>未认领</span>
              <span v-if="it.dueDate" class="due" :class="dueClass(it)"><Calendar :size="13"/> {{fmtDue(it.dueDate)}}{{dueSuffix(it)}}</span>
              <span v-if="it.commentCount" class="meta-ico"><MessageSquare :size="14"/> {{it.commentCount}}</span>
              <span style="flex:1"/>
              <button v-if="canQuickNudge(it)" class="qbtn press" title="催一下" :disabled="busy.is('nudge'+it.id)" @click.stop="quickNudge(it)"><Zap :size="13"/>催一下</button>
              <button v-if="canQuickKeep(it)" class="qbtn press" title="照常推进（一键登记进展）" :disabled="busy.is('keep'+it.id)" @click.stop="quickKeep(it)">照常推进</button>
            </div>
          </article>
          <p v-if="!laneCol(lane,col.status).length" class="empty small">暂无事项</p>
        </div>
      </section>
    </div>
  </div>
  <p v-if="!lanes.length && !loading" class="small">当前范围没有事项。</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { Zap, OctagonAlert, MessageSquare, Users, Calendar, ChevronDown, Package } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import AppShell from '../components/AppShell.vue'; import ItemDrawer from '../components/ItemDrawer.vue'; import QuickUpdateBox from '../components/QuickUpdateBox.vue'
import { useBusy } from '../utils/busy'
import AvatarStack from '../components/AvatarStack.vue'; import ProgressBar from '../components/ProgressBar.vue'; import SegmentedControl from '../components/SegmentedControl.vue'
import { api, ApiError } from '../api/http'; import { TYPE_META, STORY_CAT_META, itemColor, estimateUnitLabel, type Item, type ItemStatus } from '../api/types'
import { localToday, overdueDays } from '../utils/datetime'
import { isPromptDismissed } from '../utils/progressPrompt'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const team = useTeam(), auth = useAuth(), toast = useToast(), busy = useBusy()
const items = ref<Item[]>([]), tasks = ref<Item[]>([]), loading = ref(false), loadErr = ref('')
const scope = ref('current'), onlyMine = ref(false), fType = ref(''), onlyBlocked = ref(false), showCancelled = ref(false)
const laneMode = ref<'task'|'owner'|'type'|'none'>('task'), collapsed = ref(new Set<string>())
const dragId = ref<number|null>(null), dragOver = ref<ItemStatus|null>(null)
const drawerOpen = ref(false), openId = ref<number|null>(null)
const unit = computed(()=> estimateUnitLabel(team.current))
const columns: {status:ItemStatus;label:string}[] = [
  {status:'UNCLAIMED',label:'待认领'},{status:'NOT_STARTED',label:'待开始'},{status:'IN_PROGRESS',label:'进行中'},{status:'IN_REVIEW',label:'待验收'},{status:'DONE',label:'已完成'}]
const colsComputed = computed(()=> showCancelled.value ? [...columns, {status:'CANCELLED' as ItemStatus, label:'已取消'}] : columns)
const boardTitle = computed(()=> team.iteration ? `看板 · ${team.iteration.name}` : '看板')
async function load(){ if(!team.currentId) return; loading.value=true; loadErr.value=''
  try{ const q = new URLSearchParams({scope: scope.value}); const list = await api.get<Item[]>(`/teams/${team.currentId}/items?${q}`)
    items.value = list.filter(i=>i.type!=='TASK'); tasks.value = await api.get<Item[]>(`/teams/${team.currentId}/items?scope=all&type=TASK`).catch(()=>[])
  }catch(e){ loadErr.value = e instanceof ApiError? e.message:'加载失败' } finally{ loading.value=false } }
onMounted(async () => { if (!team.iteration) scope.value = 'all'; await load() }); watch(scope, load)
function filtered(){ let l = items.value
  if (onlyMine.value && auth.user) l = l.filter(i=>i.ownerIds.includes(auth.user!.id))
  if (fType.value) l = l.filter(i=>i.type===fType.value)
  if (onlyBlocked.value) l = l.filter(i=>i.blocked)
  return l }
function colItems(s:ItemStatus){ return filtered().filter(i=>i.status===s) }
function parentOf(it:Item){ return it.parentId ? tasks.value.find(t=>t.id===it.parentId) : undefined }
interface Lane { key:string; label:string; task?:Item; items:Item[]; donePts:number; totalPts:number; doneCnt:number; totalCnt:number }
const lanes = computed<Lane[]>(()=>{
  const l = filtered()
  const mk = (key:string,label:string,list:Item[],task?:Item):Lane => { const stories = task ? l.filter(i=>i.parentId===task.id) : list
    return { key, label, task, items: list, totalPts: stories.reduce((n,i)=>n+(i.estimate??0),0), donePts: stories.filter(i=>i.status==='DONE').reduce((n,i)=>n+(i.estimate??0),0), totalCnt: stories.length, doneCnt: stories.filter(i=>i.status==='DONE').length } }
  if (laneMode.value === 'none') return [mk('all','全部',l)]
  if (laneMode.value === 'owner') { const out:Lane[] = []; for (const m of team.members) { const mine = l.filter(i=>i.ownerIds.includes(m.userId)); if (mine.length) out.push(mk('u'+m.userId, m.displayName, mine)) } const un = l.filter(i=>!i.ownerIds.length); if (un.length) out.push(mk('un','未认领',un)); return out }
  if (laneMode.value === 'type') { const out:Lane[] = []; for (const t of ['STORY','DEFECT','ANALYSIS','ADHOC'] as const) { const g = l.filter(i=>i.type===t); if (g.length) out.push(mk('t'+t, TYPE_META[t].label, g)) } return out }
  // 默认按任务分泳道：任务按编号序，独立事项最后
  const out:Lane[] = []
  for (const t of [...tasks.value].sort((a,b)=>a.id-b.id)) { const g = l.filter(i=>i.parentId===t.id); if (g.length) out.push(mk('task'+t.id, t.title, g, t)) }
  const indie = l.filter(i=>i.type!=='STORY'); if (indie.length) out.push(mk('indie','独立事项',indie))
  const orphans = l.filter(i=>i.type==='STORY' && !tasks.value.some(t=>t.id===i.parentId)); if (orphans.length) out.push(mk('orphan','其他故事',orphans))
  return out })
function toggleLane(k:string){ const s = new Set(collapsed.value); s.has(k) ? s.delete(k) : s.add(k); collapsed.value = s }
function laneCol(lane:Lane, s:ItemStatus){ return lane.items.filter(i=>i.status===s) }
function sortedLane(lane:Lane, s:ItemStatus){ return [...laneCol(lane,s)].sort((a,b)=> Number(b.blocked)-Number(a.blocked) || a.id-b.id) }
function estSum(list:Item[]){ return list.reduce((n,i)=>n+(i.estimate??0),0) }
function fmtDue(d:string){ const m = d.match(/^(\d+)-(\d+)-(\d+)$/); return m ? `${Number(m[2])}月${Number(m[3])}日` : d }
function dueSuffix(it:Item){ const c = dueClass(it); return c==='over' ? ` · 已逾期 ${overdueDays(it.dueDate)} 天` : c==='today' ? ' · 今天' : '' }
function showBar(it:Item){ return it.status==='IN_PROGRESS' || it.status==='IN_REVIEW' || it.status==='DONE' }
function barPct(it:Item){ return it.progressPercent }
// 修订五 B：卡片小标记——「可提交验收」= 进度 100% 且进行中且用户已选过「暂不」（dismiss 存本地，见 utils/progressPrompt）
function readyMark(it:Item){ return it.status==='IN_PROGRESS' && it.progressPercent>=100 && isPromptDismissed(it.id) }
function dueClass(it:Item){ if(!it.dueDate || it.status==='DONE') return ''; const today=localToday(); return it.dueDate < today ? 'over' : it.dueDate === today ? 'today' : '' }
function canQuickNudge(it:Item){ if (it.allowedActions) return it.allowedActions.includes('NUDGE'); return !!auth.user && !it.ownerIds.includes(auth.user.id) && ['NOT_STARTED','IN_PROGRESS','IN_REVIEW'].includes(it.status) }
function canQuickKeep(it:Item){ if (it.allowedActions) return it.allowedActions.includes('PROGRESS'); return !!auth.user && it.ownerIds.includes(auth.user.id) && it.status==='IN_PROGRESS' }
async function quickNudge(it:Item){ await busy.wrap('nudge' + it.id, async () => { try { const r = await api.post(`/teams/${team.currentId}/items/${it.id}/nudge`); toast.show(r.redirectedToLeader ? '对方请假/出差，已改催组长' : `已催一下（本轮第 ${r.nudgeCount} 次）`); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '催办失败') } }) }
async function quickKeep(it:Item){ await busy.wrap('keep' + it.id, async () => { try { await api.post(`/teams/${team.currentId}/items/${it.id}/progress`, { kind: 'KEEP_GOING' }); toast.show('已登记：照常推进'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '登记失败') } }) }
function dragItem(){ return items.value.find(i=>i.id===dragId.value) }
function canDrop(target:ItemStatus){ const it = dragItem(); if(!it || it.status===target) return !!it && it.status===target
  const s = it.status
  if (target==='NOT_STARTED') return s==='UNCLAIMED'
  if (target==='IN_PROGRESS') return s==='NOT_STARTED' || s==='IN_REVIEW'
  if (target==='IN_REVIEW') return s==='IN_PROGRESS'
  if (target==='DONE') return s==='IN_REVIEW'
  if (target==='UNCLAIMED') return s==='NOT_STARTED' || s==='IN_PROGRESS'
  return false }
async function onDrop(target:ItemStatus){ const it = dragItem(); dragOver.value=null; if(!it) return
  if (it.status===target) return
  if (!canDrop(target)){ toast.show('不能直接移到该列，请打开详情按流转操作'); return }
  let action: string|null = null; const s = it.status
  if (target==='UNCLAIMED') action='RELEASE'; else if (target==='NOT_STARTED') action='CLAIM'
  else if (target==='IN_REVIEW') action='SUBMIT'; else if (target==='DONE') action='APPROVE'
  else if (target==='IN_PROGRESS') action = s==='NOT_STARTED' ? 'START' : 'REJECT'
  if (!action) return
  if (action==='RELEASE' || action==='REJECT'){ openId.value=it.id; drawerOpen.value=true; toast.show(action==='RELEASE' ? '释放需填写原因，请在详情抽屉的「更多」里操作' : '打回需填写原因，请在详情抽屉里操作'); return }
  try{ await api.post(`/teams/${team.currentId}/items/${it.id}/transitions`, { action, version: it.version }); toast.show('已移动'); load() }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '流转失败'); load() }
  dragId.value=null }
</script>
<style scoped>
.filters{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-bottom:14px}
.tchip{display:inline-flex;align-items:center;gap:6px;border:1px solid var(--field-brd);background:var(--field);border-radius:999px;padding:5px 11px;font-size:12px;font-weight:500;min-height:32px}
.tchip.off{opacity:.45}.tdot{width:7px;height:7px;border-radius:50%;flex:none}
.switchrow{margin-left:auto}
.switch{position:relative;width:48px;height:28px;flex:none;display:inline-block}.switch input{position:absolute;opacity:0;width:100%;height:100%;cursor:pointer;margin:0}
.switch .tr{position:absolute;inset:0;border-radius:999px;background:var(--track);transition:background-color .2s;pointer-events:none}
.switch .tr::after{content:"";position:absolute;top:2px;left:2px;width:24px;height:24px;border-radius:50%;background:var(--knob);box-shadow:var(--shadow-switch);transition:transform .2s}
.switch input:checked + .tr{background:var(--c-green)}.switch input:checked + .tr::after{transform:translateX(20px)}
.chk{display:inline-flex;gap:6px;align-items:center;font-size:13px}.chk input{width:17px;height:17px}
.fld-sm{font-size:13px;display:inline-flex;gap:6px;align-items:center}.fld-sm select{min-height:32px;border-radius:9px;border:1px solid var(--field-brd);background:var(--field);padding:0 6px}
.lane{margin-bottom:18px}
.lanehead{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:9px 14px;border-radius:14px;background:var(--lane-bg);border:1px solid var(--lane-brd);margin-bottom:10px;flex-wrap:wrap}
.lanehead .rot{transform:rotate(-90deg)}.lanehead svg{transition:transform .2s}
.lcode{color:var(--theme);font-weight:700}.lanehead .link{color:var(--theme);font-weight:600;margin-left:auto}
.board{display:flex;gap:12px;align-items:flex-start;overflow-x:auto;padding-bottom:10px;scroll-snap-type:x proximity}
.col{width:288px;flex:none;background:var(--col-bg);border:1px solid var(--col-brd);border-radius:20px;padding:12px 10px 10px;scroll-snap-align:start;transition:opacity .2s}
.col.dim{opacity:.45}.col.over{outline:2px solid var(--theme)}
.colhead{display:flex;align-items:baseline;gap:8px;padding:2px 6px 10px}.colhead .cnt{color:var(--text-2);font-size:13px}
.cards{display:flex;flex-direction:column;gap:12px}
.kcard{position:relative;background:var(--card);border:1px solid var(--card-brd);border-left:4px solid var(--theme);border-radius:var(--r-card);box-shadow:var(--shadow-card-sm);padding:12px 14px 12px 18px;cursor:grab;text-align:left;width:100%;overflow:hidden}
/* 拖拽期间降级：关阴影与过渡，减少重绘（M5 性能优化） */
.board.dragging .kcard{box-shadow:none;transition:none}
.board.dragging .kcard.dragging{box-shadow:var(--shadow-lift)}
.kcard.blocked{border-color:var(--c-red);border-left-color:var(--c-red);box-shadow:0 0 0 1.5px var(--c-red)}
.kcard.dragging{transform:scale(1.03);box-shadow:var(--shadow-lift);opacity:.9}
.krow{display:flex;align-items:center;gap:6px}.krow.foot{margin-top:8px}
.ktitle{font-size:15px;font-weight:600;line-height:1.4;margin:7px 0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.kcat{font-size:12px;color:var(--text-2);display:flex;gap:5px;align-items:center;margin:2px 0}
.kblock{color:var(--c-red);font-size:12.5px;margin:4px 0;display:flex;gap:5px;align-items:center}
.kmarks{display:flex;gap:5px;flex-wrap:wrap;margin:4px 0}
.mark{font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px}
.mark.red{color:var(--danger-deep);background:var(--danger-soft)}
.mark.green{color:var(--success-deep);background:var(--success-soft)}
.mark.amber{color:var(--warn-deep);background:var(--warn-soft)}
.ptag{font-size:11px;font-weight:700;color:var(--primary-deep);background:var(--sel);border-radius:6px;padding:1px 6px}
.btag{font-size:11px;font-weight:600;color:var(--violet-deep);background:var(--violet-soft);border-radius:6px;padding:1px 6px;display:inline-flex;gap:3px;align-items:center}
.nudge{position:absolute;top:0;right:14px;background:var(--c-yellow);color:var(--nudge-text);font-size:11px;font-weight:700;padding:3px 8px;border-radius:0 0 9px 9px;display:inline-flex;gap:4px;align-items:center;box-shadow:var(--shadow-nudge);z-index:var(--z-sticky)}
.prio{font-size:13px;font-weight:700}.meta-ico{display:inline-flex;gap:4px;align-items:center;font-size:12px;color:var(--text-2)}.due{font-size:12px;color:var(--text-2);display:inline-flex;gap:4px;align-items:center}.due.over,.due.today{color:var(--c-red);font-weight:700}
.qbtn{display:inline-flex;gap:3px;align-items:center;font-size:11.5px;font-weight:600;color:var(--text-2);background:var(--field);border:1px solid var(--field-brd);border-radius:99px;padding:3px 9px;min-height:26px}.qbtn:hover{color:var(--theme);border-color:var(--theme)}
.empty{padding:10px 4px}
.noiter{display:flex;gap:10px;align-items:center;background:var(--warn-bg);border:1px solid var(--warn-brd-deep);border-radius:14px;padding:10px 14px;margin-bottom:12px;flex-wrap:wrap}.noiter .grow{flex:1}.nbtn{font-size:13px;font-weight:700;color:var(--theme);text-decoration:none}
@media(max-width:820px){.col{width:78vw}}
</style>
