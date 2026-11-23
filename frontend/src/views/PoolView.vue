<template>
<AppShell title="认领池" @refresh="load">
  <div class="filters glass">
    <span class="small">待认领 {{items.length}} 项 · 默认按任务分组</span>
    <label class="fld-sm">排序<select v-model="sort" aria-label="排序"><option value="new">最新发布</option><option value="due">截止最近</option><option value="wait">等待最久</option></select></label>
    <label class="fld-sm">类型<select v-model="fType" aria-label="类型筛选"><option value="">全部</option><option v-for="(m,t) in TYPE_META" :key="t" :value="t">{{m.label}}</option></select></label>
    <span style="flex:1"/>
    <AppButton v-if="isPo" variant="ghost" @click="importOpen=true"><Import :size="15"/>导入向导</AppButton>
    <AppButton @click="ui.createOpen=true"><Plus :size="15"/>发布到认领池</AppButton>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <p v-if="warn" class="warnbar">提示：你在本迭代已认领未完成的故事点已达 {{cap?.claimedPoints}} {{unit}}，超过你的剩余容量 {{cap?.capacityPoints}} {{unit}}（剩余 {{cap?.remainingDays}} 个可用工作日 × 容量系数）。仍可认领，不做拦截。</p>
  <div v-for="g in groups" :key="g.key" class="group">
    <h2 class="ghead"><template v-if="g.task"><span class="lcode">{{g.task.code}}</span> {{g.task.title}} <span class="small">PO：{{team.memberName(g.task.poId)}}</span> <RouterLink :to="`/task/${g.task.id}`" class="small link">任务详情 →</RouterLink></template><template v-else>独立事项</template></h2>
    <div class="grid">
      <article v-for="it in g.items" :key="it.id" class="kcard" :style="{borderLeftColor:itemColor(it)}">
        <!-- 「邀请我认领」标签（1.0.4 D）：放进卡片正常流（标题上方独立一行），不再绝对定位，避免盖住右上角优先级 P2，任何宽度下不重叠 -->
        <div v-if="invitedMe(it)" class="invrow"><span class="inv">邀请我认领</span></div>
        <div class="krow"><span class="small">{{it.code}}</span><span v-if="it.batchId" class="btag">批次</span><span style="flex:1"/><span class="prio">{{it.priority}}</span></div>
        <p class="ktitle">{{it.title}}</p>
        <p class="small desc">{{it.description}}</p>
        <div class="krow foot">
          <span class="meta-ico"><User :size="13"/>发布人 {{team.memberName(it.creatorId)}}</span>
          <span v-if="it.estimate!=null" class="meta-ico">{{it.estimate}} {{unit}}</span>
          <span v-if="it.dueDate" class="meta-ico" :class="{over: overdueDays(it.dueDate)>0}"><Calendar :size="13"/>{{fmtDue(it.dueDate)}}<template v-if="overdueDays(it.dueDate)>0"> · 已逾期 {{overdueDays(it.dueDate)}} 天</template></span>
          <span class="meta-ico"><Clock :size="13"/>已等待 {{waitDays(it)}} 天</span>
        </div>
        <div class="krow" style="margin-top:10px"><AppButton @click="claim(it)"><Check :size="15"/>认领</AppButton><AppButton variant="ghost" @click="openId=it.id;drawerOpen=true">详情</AppButton></div>
      </article>
    </div>
  </div>
  <p v-if="!items.length && !loading" class="small">认领池是空的，大家手头都排满了。</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" :highlight-comment-id="hlComment" :highlight-progress-id="hlProgress" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
  <ImportWizard :open="importOpen" @close="importOpen=false" @done="load"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Import, Plus, Check, User, Calendar, Clock } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import AppButton from '../components/AppButton.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import ImportWizard from '../components/ImportWizard.vue'
import { api, ApiError } from '../api/http'; import { TYPE_META, itemColor, estimateUnitLabel, type Item, type Planning } from '../api/types'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'
import { overdueDays } from '../utils/datetime'
const team = useTeam(), auth = useAuth(), ui = useUi(), toast = useToast(), route = useRoute()
// 修订六契约 4：/i/:id 独立事项落地待办池时以 ?open= 开抽屉，comment/progress 透传高亮。
const hlComment = route.query.comment ? Number(route.query.comment) : null
const hlProgress = route.query.progress ? Number(route.query.progress) : null
const items = ref<Item[]>([]), tasks = ref<Item[]>([]), planning = ref<Planning|null>(null), loading = ref(false)
const sort = ref('new'), fType = ref(''), loadErr = ref('')
const importOpen = ref(false)
const drawerOpen = ref(false), openId = ref<number|null>(null)
const unit = computed(()=> estimateUnitLabel(team.current))
// 导入仅 PO：导入向导入口只对 PO 显示，与后端 requirePo 口径一致
const isPo = computed(()=> team.current?.myRole === 'PO')
const cap = computed(()=> planning.value?.capacities.find(c=>c.userId===auth.user?.id))
const myPoints = computed(()=> planning.value?.loads.find(l=>l.userId===auth.user?.id)?.points ?? 0)
const warn = computed(()=> !!cap.value && cap.value.overCapacity)
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''
  try { items.value = await api.get(`/teams/${team.currentId}/items?status=UNCLAIMED&scope=all`)
    tasks.value = await api.get(`/teams/${team.currentId}/items?scope=all&type=TASK`).catch(()=>[])
    if (team.iteration) planning.value = await api.get(`/teams/${team.currentId}/iterations/${team.iteration.id}/planning`).catch(()=>null)
  } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(async () => { await load(); const open = route.query.open; if (open) { openId.value = Number(open); drawerOpen.value = true } })
const groups = computed(()=>{ let l = items.value; if (fType.value) l = l.filter(i=>i.type===fType.value)
  const srt = (a:Item,b:Item)=> sort.value==='due' ? (a.dueDate ?? '9999').localeCompare(b.dueDate ?? '9999') : sort.value==='wait' ? a.createdAt.localeCompare(b.createdAt) : b.id - a.id
  const out: {key:string;task?:Item;items:Item[]}[] = []
  for (const t of [...tasks.value].sort((a,b)=>a.id-b.id)) { const g = l.filter(i=>i.parentId===t.id); if (g.length) out.push({ key:'t'+t.id, task:t, items:[...g].sort(srt) }) }
  const indie = l.filter(i=>i.type!=='STORY'); if (indie.length) out.push({ key:'indie', items:[...indie].sort(srt) })
  const orphans = l.filter(i=>i.type==='STORY' && !tasks.value.some(t=>t.id===i.parentId)); if (orphans.length) out.push({ key:'orphan', items:[...orphans].sort(srt) })
  // 邀请我的排到各组最前
  for (const g of out) g.items.sort((a,b)=> Number(invitedMe(b)) - Number(invitedMe(a)))
  return out })
function invitedMe(it:Item){ return !!auth.user && (it.inviteeIds ?? []).includes(auth.user.id) }
function fmtDue(d:string){ const m = d.match(/^(\d+)-(\d+)-(\d+)$/); return m ? `${Number(m[2])}月${Number(m[3])}日` : d }
function waitDays(it:Item){ return Math.max(0, Math.floor((Date.now() - new Date(it.createdAt).getTime()) / 864e5)) }
async function claim(it:Item){
  if (warn.value && !confirm(`你已认领未完成的故事点已达 ${cap.value?.claimedPoints} ${unit.value}，超过本迭代剩余容量 ${cap.value?.capacityPoints} ${unit.value}。仍要认领吗？`)) return
  try { await api.post(`/teams/${team.currentId}/items/${it.id}/transitions`, { action:'CLAIM', version: it.version }); toast.show('认领成功，已进入你的待开始'); load() }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '认领失败'); load() } }
</script>
<style scoped>
.filters{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.fld-sm{font-size:13px;display:inline-flex;gap:6px;align-items:center}.fld-sm select{min-height:32px;border-radius:9px;border:1px solid var(--field-brd);background:var(--field);padding:0 6px}
.warnbar{background:var(--warn-bg-strong);border:1px solid var(--warn-brd-strong);border-radius:12px;padding:10px 14px;font-size:13px;margin-bottom:12px}
.ghead{font-size:16px;margin:16px 0 10px;display:flex;gap:9px;align-items:center}.lcode{color:var(--theme);font-weight:700}.ghead .link{color:var(--theme)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}
.kcard{position:relative;background:var(--card);border:1px solid var(--card-brd);border-left:4px solid var(--theme);border-radius:var(--r-card);box-shadow:var(--shadow-card);padding:14px 14px 14px 18px;overflow:hidden}
.invrow{margin-bottom:7px}
.inv{display:inline-block;background:var(--c-indigo);color:var(--on-color);font-size:11px;font-weight:700;padding:3px 8px;border-radius:99px}
.krow{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.krow.foot{margin-top:8px}
.ktitle{font-size:15px;font-weight:600;line-height:1.4;margin:7px 0}
.desc{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.prio{font-size:13px;font-weight:700}.meta-ico{display:inline-flex;gap:4px;align-items:center;font-size:12px;color:var(--text-2)}.meta-ico.over{color:var(--c-red);font-weight:700}
.btag{font-size:11px;font-weight:600;color:var(--violet-deep);background:var(--violet-soft);border-radius:6px;padding:1px 6px}
</style>
