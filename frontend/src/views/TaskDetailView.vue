<template>
<AppShell :title="task ? `任务 · ${task.code}` : '任务详情'" @refresh="load">
  <RouterLink to="/backlog" class="small back">← 待办池</RouterLink>
  <template v-if="task">
    <GlassPanel class="head">
      <div class="hrow"><TypeBadge type="TASK"/><span class="small">{{task.code}}</span>
        <span class="tpill">{{TASK_STATUS_META[task.taskStatus ?? 'NOT_STARTED'].label}}</span><span style="flex:1"/>
        <AppButton v-if="isPO" variant="ghost" @click="editOpen=true">编辑任务</AppButton>
        <AppButton v-if="task.taskStatus==='PENDING_ACCEPTANCE' && canAccept" @click="acceptTask">验收任务</AppButton>
      </div>
      <h2 class="ttitle">{{task.title}}</h2>
      <p class="small">所属 PO：{{team.memberName(task.poId)}} · 整体进度 {{task.progressPercent}}%（故事点加权，无估算按件数）· {{doneCount}}/{{stories.length}} 个故事完成</p>
      <ProgressBar :value="task.progressPercent" style="margin:10px 0 4px"/>
      <div class="md" v-html="html"/>
    </GlassPanel>

    <GlassPanel class="panel">
      <h3>故事在各迭代的分布</h3>
      <div v-for="d in distribution" :key="d.key" class="drow">
        <span class="grow"><b>{{d.label}}</b> <span class="small">{{d.items.length}} 个故事 · {{d.donePts}}/{{d.totalPts}} {{unit}}</span></span>
        <ProgressBar :value="d.totalPts ? d.donePts/d.totalPts*100 : (d.items.length ? d.items.filter(i=>i.status==='DONE').length/d.items.length*100 : 0)" style="width:140px"/>
      </div>
      <p v-if="!distribution.length" class="small">还没有故事分布。</p>
    </GlassPanel>

    <GlassPanel class="panel">
      <div class="hrow"><h3 class="grow">故事列表（可拖拽排序）</h3>
        <AppButton v-if="isPO" variant="ghost" @click="aiSplit"><Sparkles :size="15"/>AI 拆故事</AppButton>
        <AppButton v-if="isPO" @click="newStory"><Plus :size="15"/>新建故事</AppButton>
      </div>
      <p v-if="aiNote" class="note">{{aiNote}}</p>
      <div v-if="splitResult" class="splitpanel">
        <div class="hrow"><b>AI 拆分草稿</b><AiBadge :ai-generated="true"/><span style="flex:1"/><span class="tiny">可增删改、调序；勾选后批量创建</span></div>
        <div v-for="(d,i) in splitResult.stories" :key="i" class="drow2">
          <input type="checkbox" v-model="d.selected"/>
          <input v-model="d.title" class="flexin grow"/>
          <select v-model="d.storyCategory" class="flexin"><option value="DEV">开发</option><option value="ANALYSIS">分析</option><option value="TEST">测试</option><option value="OTHER">其他</option></select>
          <input type="number" v-model.number="d.estimate" class="flexin num" aria-label="估算"/>
          <button class="mini press" @click="moveDraft(i,-1)">↑</button><button class="mini press" @click="moveDraft(i,1)">↓</button>
          <button class="mini press" @click="splitResult.stories.splice(i,1)">删</button>
        </div>
        <div class="hrow" style="margin-top:6px"><AppButton variant="ghost" @click="splitResult.stories.push({title:'',storyCategory:'DEV',estimate:1,selected:true})">＋ 加一条草稿</AppButton></div>
        <h4 class="sub">待澄清问题</h4>
        <div v-for="(q,i) in splitResult.clarifyingQuestions" :key="'q'+i" class="hrow"><span class="grow small">{{q}}</span><AppButton variant="ghost" :disabled="busy.is('q'+i)" @click="askQuestion(q,i)">发评论并 @PO</AppButton></div>
        <h4 class="sub">验收要点</h4>
        <p v-for="(a,i) in splitResult.acceptancePoints" :key="'a'+i" class="small">· {{a}}</p>
        <div class="hrow" style="margin-top:8px">
          <AppButton variant="ghost" :disabled="busy.is('appendAcc')" @click="appendAcceptance">把验收要点追加到任务描述</AppButton><span style="flex:1"/>
          <AppButton :disabled="busy.is('createSplit') || splitCreated" @click="createSplitStories">{{ splitCreated ? '已生成（重复点击不会重复建）' : '勾选确认，批量建故事' }}</AppButton>
        </div>
      </div>
      <div v-for="(s,i) in stories" :key="s.id" class="srow" draggable="true" @dragstart="dragIdx=i" @dragover.prevent @drop.prevent="dropStory(i)" :class="{dragging:dragIdx===i}">
        <GripVertical :size="15" class="grip"/>
        <span class="tdot" :style="{background:itemColor(s)}"/>
        <button class="grow press tbtn" @click="openId=s.id;drawerOpen=true"><span class="small">{{s.code}}</span> {{s.title}}</button>
        <span class="catpill" v-if="s.storyCategory" :style="{background:STORY_CAT_META[s.storyCategory].color}">{{STORY_CAT_META[s.storyCategory].label}}</span>
        <span class="tiny">{{STATUS_META[s.status].label}}</span>
        <AvatarStack v-if="s.ownerIds.length" :ids="s.ownerIds" :size="21"/>
        <span class="tiny">{{s.estimate ?? '—'}} {{unit}}</span>
      </div>
      <p v-if="!stories.length" class="small">还没有故事，用下方逐条新建或粘贴批量新建。</p>
      <template v-if="isPO">
        <h4 class="sub">粘贴批量新建（每行一个故事，可写「类别 | 标题 | 点数」，类别可填 开发/分析/测试/其他）</h4>
        <textarea v-model="bulk" rows="5" class="bulk" placeholder="开发 | 故事标题 | 3&#10;测试 | 回归测试集 | 2&#10;只写标题也可以"/>
        <div class="hrow" style="margin-top:8px"><span class="small">新故事去向：认领池（建后在规划页/认领池认领）</span><span style="flex:1"/><AppButton @click="submitBulk">批量新建 {{bulkLines}} 条</AppButton></div>
        <p v-if="bulkMsg" class="small" style="margin-top:6px">{{bulkMsg}}</p>
      </template>
      <p v-else class="small">只有 PO 可以拆故事。发现遗漏时，在任务里评论并 @PO。</p>
    </GlassPanel>
  </template>
  <p v-else-if="!loading" class="small">任务不存在或已删除。</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
  <EditItemModal :item="editOpen && task ? task : null" :tasks="[]" @close="editOpen=false" @saved="load"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { marked } from 'marked'; import DOMPurify from 'dompurify'
import { Plus, Sparkles, GripVertical } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import ProgressBar from '../components/ProgressBar.vue'; import AvatarStack from '../components/AvatarStack.vue'; import TypeBadge from '../components/TypeBadge.vue'; import ItemDrawer from '../components/ItemDrawer.vue'; import EditItemModal from '../components/EditItemModal.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'; import { TASK_STATUS_META, STATUS_META, STORY_CAT_META, itemColor, estimateUnitLabel, type Item, type Planning, type StoryCategory } from '../api/types'
import AiBadge from '../components/AiBadge.vue'
import { useBusy } from '../utils/busy'; import { useAi } from '../stores/ai'
import { useTeam } from '../stores/team'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'
const route = useRoute(), team = useTeam(), ui = useUi(), toast = useToast(), busy = useBusy(), ai = useAi()
const taskId = Number(route.params.id)
const task = ref<Item|null>(null), stories = ref<Item[]>([]), iterNames = ref<Record<number,string>>({}), loading = ref(false)
const drawerOpen = ref(false), openId = ref<number|null>(null), editOpen = ref(false)
const bulk = ref(''), bulkMsg = ref(''), aiNote = ref(''), dragIdx = ref<number|null>(null)
const isPO = computed(()=> team.current?.myRole === 'PO' || team.current?.myRole === 'LEADER')
const canAccept = computed(()=> isPO.value)
const unit = computed(()=> estimateUnitLabel(team.current))
const doneCount = computed(()=> stories.value.filter(s=>s.status==='DONE').length)
const html = computed(()=> task.value ? DOMPurify.sanitize(marked.parse(task.value.description || '暂无描述') as string) : '')
const bulkLines = computed(()=> bulk.value.split('\n').map(x=>x.trim()).filter(Boolean).length)
const distribution = computed(()=> { const map = new Map<string, Item[]>()
  for (const s of stories.value) { const k = s.iterationId ? 'i'+s.iterationId : 'daily'; map.set(k, [...(map.get(k) ?? []), s]) }
  return [...map.entries()].map(([k, list]) => ({ key: k, label: k === 'daily' ? '未排入迭代' : (iterNames.value[Number(k.slice(1))] ?? `迭代 #${k.slice(1)}`), items: list, totalPts: list.reduce((n,i)=>n+(i.estimate??0),0), donePts: list.filter(i=>i.status==='DONE').reduce((n,i)=>n+(i.estimate??0),0) })) })
async function load(){ if(!team.currentId) return; loading.value = true
  try { task.value = await api.get(`/teams/${team.currentId}/items/${taskId}`)
    stories.value = await api.get(`/teams/${team.currentId}/items/${taskId}/stories`)
    const ids = [...new Set(stories.value.map(s=>s.iterationId).filter(Boolean))] as number[]
    for (const id of ids) { if (iterNames.value[id]) continue; const p = await api.get<Planning>(`/teams/${team.currentId}/iterations/${id}/planning`).catch(()=>null); if (p) iterNames.value[id] = p.iteration.name }
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '加载失败') } finally { loading.value = false } }
onMounted(load)
async function dropStory(i:number){ if (dragIdx.value === null || dragIdx.value === i) { dragIdx.value = null; return }
  const list = [...stories.value]; const [mv] = list.splice(dragIdx.value, 1); list.splice(i, 0, mv); dragIdx.value = null; stories.value = list
  try { stories.value = await api.post(`/teams/${team.currentId}/items/${taskId}/stories/reorder`, { orderedIds: list.map(s=>s.id) }); toast.show('顺序已保存') } catch(e){ toast.show(e instanceof ApiError ? e.message : '排序失败'); load() } }
function newStory(){ ui.createPreset = { type: 'STORY', parentId: taskId }; ui.createOpen = true }
const splitResult = ref<{ stories: { title: string; storyCategory: string; estimate: number; selected: boolean }[]; clarifyingQuestions: string[]; acceptancePoints: string[] } | null>(null)
const splitCreated = ref(false)
let splitKey = ''
async function aiSplit(){ splitCreated.value = false; splitKey = newIdempotencyKey()
  if (!ai.featureOn('splitStories')) { aiNote.value = 'AI 暂不可用，可手工用下方「粘贴批量新建」逐条拆故事。'; return }
  aiNote.value = ''
  try { const r = await api.post(`/teams/${team.currentId}/ai/split-stories`, { itemId: taskId }); splitResult.value = { stories: r.stories.map((x: any) => ({ ...x, selected: true })), clarifyingQuestions: r.clarifyingQuestions ?? [], acceptancePoints: r.acceptancePoints } }
  catch(e){ aiNote.value = e instanceof ApiError ? e.message : 'AI 拆分失败'; toast.show(aiNote.value) } }
function moveDraft(i: number, d: number){ if (!splitResult.value) return; const j = i + d; const list = splitResult.value.stories; if (j < 0 || j >= list.length) return; const [mv] = list.splice(i, 1); list.splice(j, 0, mv) }
async function askQuestion(q: string, i: number){ if (!task.value) return; await busy.wrap('q' + i, async () => { try { await api.post(`/teams/${team.currentId}/items/${taskId}/comments`, { content: `【待澄清】${q}`, mentionIds: task.value!.poId ? [task.value!.poId] : [] }); toast.show('已发评论并 @PO') } catch(e){ toast.show(e instanceof ApiError ? e.message : '发送失败') } }) }
async function appendAcceptance(){ if (!task.value || !splitResult.value) return; await busy.wrap('appendAcc', async () => { const add = '\n\n## 验收要点（AI 拆分追加）\n' + splitResult.value!.acceptancePoints.map(a => '- ' + a).join('\n')
  try { task.value = await api.patch(`/teams/${team.currentId}/items/${taskId}`, { version: task.value!.version, description: (task.value!.description ?? '') + add }); toast.show('验收要点已追加到任务描述'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '追加失败') } }) }
async function createSplitStories(){ if (!splitResult.value) return; await busy.wrap('createSplit', async () => {
  const chosen = splitResult.value!.stories.filter(d => d.selected && d.title.trim()); if (!chosen.length) { toast.show('请至少勾选一条草稿'); return }
  let ok = 0
  // 幂等键只能用 ASCII：曾把故事标题拼进 Idempotency-Key 请求头，中文标题使 fetch 同步抛 ByteString 错且被单条 catch 吞掉、确认建故事静默全灭（修复轮复验发现，记 D-6）。改用草稿序号。
  for (const [ix, d] of chosen.entries()) { try { await api.postKey(`/teams/${team.currentId}/items`, { type: 'STORY', title: d.title.trim(), destination: 'POOL', parentId: taskId, storyCategory: d.storyCategory, estimate: d.estimate }, splitKey + '-' + ix); ok++ } catch { /* 单条失败不阻断其余 */ } }
  if (ok) { splitCreated.value = true; toast.show(`已批量新建 ${ok} 个故事`); load() } }) }
const CATMAP: Record<string, StoryCategory> = { '开发': 'DEV', '分析': 'ANALYSIS', '测试': 'TEST', '其他': 'OTHER', DEV: 'DEV', ANALYSIS: 'ANALYSIS', TEST: 'TEST', OTHER: 'OTHER' }
async function submitBulk(){ const lines = bulk.value.split('\n').map(x=>x.trim()).filter(Boolean); if (!lines.length) return
  let ok = 0, fail = 0
  for (const line of lines) { const parts = line.split('|').map(x=>x.trim())
    let cat: StoryCategory = 'DEV', title = line, est: number|undefined
    if (parts.length >= 2 && CATMAP[parts[0]]) { cat = CATMAP[parts[0]]; title = parts[1]; if (parts[2] && !Number.isNaN(Number(parts[2]))) est = Number(parts[2]) }
    try { await api.post(`/teams/${team.currentId}/items`, { type: 'STORY', title, destination: 'POOL', parentId: taskId, storyCategory: cat, estimate: est }); ok++ } catch { fail++ } }
  bulkMsg.value = `已新建 ${ok} 条${fail ? `，失败 ${fail} 条` : ''}。`; if (ok) { bulk.value = ''; load() } }
async function acceptTask(){ if(!task.value) return; try { task.value = await api.post(`/teams/${team.currentId}/items/${taskId}/transitions`, { action: 'APPROVE', version: task.value.version }); toast.show('任务已验收完成'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '验收失败') } }
</script>
<style scoped>
.back{display:inline-block;margin-bottom:10px;color:var(--theme)}
.head{padding:18px 20px;margin-bottom:12px}.panel{padding:16px 20px;margin-bottom:12px}
.hrow{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.ttitle{font-size:20px;margin:8px 0 4px}.grow{flex:1}
.tpill{font-size:12px;font-weight:600;background:var(--sel);color:#0A5CD6;padding:2px 9px;border-radius:999px}
.md :deep(p){margin:6px 0}.md :deep(h2){margin:10px 0 4px}
.drow{display:flex;gap:12px;align-items:center;padding:7px 0;border-bottom:1px solid var(--field-brd)}
.srow{display:flex;gap:9px;align-items:center;padding:9px 10px;border:1px solid rgba(255,255,255,.65);background:var(--card);border-radius:12px;margin-bottom:8px}
.srow.dragging{opacity:.5}.grip{color:var(--text-3);cursor:grab;flex:none}
.tbtn{text-align:left;font-weight:500}.tdot{width:8px;height:8px;border-radius:50%;flex:none}
.catpill{font-size:11px;padding:2px 8px;border-radius:999px;font-weight:700;color:#fff}
.sub{margin:16px 0 8px;font-size:14px}
.bulk{width:100%;border:1px solid var(--field-brd);background:var(--field);border-radius:var(--r-ctl);padding:10px 12px;outline:none;font-size:14px;resize:vertical}
.bulk:focus{border-color:var(--theme);box-shadow:0 0 0 3px rgba(0,122,255,.16)}
.note{background:rgba(88,86,214,.12);border:1px solid rgba(88,86,214,.35);border-radius:12px;padding:9px 13px;font-size:13px;margin:10px 0}
.splitpanel{border:1px solid rgba(88,86,214,.35);background:rgba(88,86,214,.05);border-radius:14px;padding:12px 14px;margin:10px 0}
.drow2{display:flex;gap:7px;align-items:center;padding:5px 0}
.drow2 .grow{flex:1}.drow2 .num{width:70px;flex:none}
.flexin{min-height:36px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:5px 9px}
.mini{font-size:12px;color:var(--text-2);font-weight:700}
</style>
