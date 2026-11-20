<template>
<AppShell title="待办池" @refresh="load">
  <div class="filters glass">
    <span class="small">待办池列的是任务（由 PO 维护），展开可看其下故事与进度。</span><span style="flex:1"/>
    <AppButton v-if="team.current?.myRole === 'PO'" variant="ghost" @click="importOpen=true">导入向导</AppButton>
    <AppButton v-if="isPO" @click="newTask"><Plus :size="15"/>新建任务</AppButton>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="small" style="color:var(--c-red);margin:8px 0">{{loadErr}} <button class="press" style="color:var(--theme);font-weight:600" @click="load">重试</button></p>
  <div v-for="e in entries" :key="e.task.id" class="trow glass">
    <button class="thead press" @click="toggle(e.task.id)">
      <ChevronDown :size="16" :class="{rot:!open.has(e.task.id)}"/>
      <span class="bar" :style="{background:TYPE_META.TASK.color}"/>
      <span class="code">{{e.task.code}}</span><b class="grow">{{e.task.title}}</b>
      <span class="small">PO：{{team.memberName(e.task.poId)}}</span>
      <span class="tpill">{{TASK_STATUS_META[e.task.taskStatus ?? 'NOT_STARTED'].label}}</span>
      <span class="small">{{e.doneStoryCount}}/{{e.storyCount}} 故事 · {{clampPct(e.task.progressPercent)}}%</span>
      <ProgressBar :value="e.task.progressPercent" style="width:120px"/>
    </button>
    <div v-if="open.has(e.task.id)" class="stories">
      <button v-for="s in e.stories" :key="s.id" class="srow press" @click="openId=s.id;drawerOpen=true">
        <span class="tdot" :style="{background:itemColor(s)}"/><span class="small">{{s.code}}</span><span class="grow">{{s.title}}</span>
        <span class="tiny">{{s.storyCategory ? STORY_CAT_META[s.storyCategory].label : ''}} · {{STATUS_META[s.status].label}}</span>
        <AvatarStack v-if="s.ownerIds.length" :ids="s.ownerIds" :size="20"/><span v-if="s.estimate!=null" class="tiny">{{s.estimate}} {{unit}}</span>
      </button>
      <p v-if="!e.stories.length" class="small" style="padding:6px 4px">还没有故事。在任务详情里逐条新建或粘贴批量新建。</p>
      <div class="sfoot"><AppButton variant="ghost" @click="$router.push(`/task/${e.task.id}`)">打开任务详情</AppButton>
      <AppButton v-if="isPO" variant="ghost" @click="newStory(e.task)">在此任务下新建故事</AppButton></div>
    </div>
  </div>
  <p v-if="!entries.length && !loading" class="small">待办池还没有任务。{{ isPO ? '点右上角「新建任务」开始。' : '等 PO 把任务放进来。' }}</p>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @changed="load" @open-item="(id:number)=>{openId=id}"/>
  <ImportWizard :open="importOpen" @close="importOpen=false" @done="load"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ChevronDown, Plus } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import AppButton from '../components/AppButton.vue'; import ProgressBar from '../components/ProgressBar.vue'; import AvatarStack from '../components/AvatarStack.vue'; import ItemDrawer from '../components/ItemDrawer.vue'
import ImportWizard from '../components/ImportWizard.vue'
import { api, ApiError } from '../api/http'; import { TYPE_META, TASK_STATUS_META, STATUS_META, STORY_CAT_META, itemColor, estimateUnitLabel, type BacklogEntry, type Item } from '../api/types'
import { useTeam } from '../stores/team'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'
import { clampPct } from '../utils/progress'
const team = useTeam(), ui = useUi(), toast = useToast()
const entries = ref<BacklogEntry[]>([]), loading = ref(false), loadErr = ref(''), open = ref(new Set<number>())
const drawerOpen = ref(false), openId = ref<number|null>(null)
const importOpen = ref(false)
const isPO = computed(()=> team.current?.myRole === 'PO' || team.current?.myRole === 'LEADER')
const unit = computed(()=> estimateUnitLabel(team.current))
async function load(){ if(!team.currentId) return; loading.value = true; loadErr.value = ''; try { entries.value = await api.get(`/teams/${team.currentId}/backlog`) } catch(e){ loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
function toggle(id:number){ const s = new Set(open.value); s.has(id) ? s.delete(id) : s.add(id); open.value = s }
async function newTask(){ const title = prompt('任务标题（PO 新建任务）'); if (!title?.trim()) return
  try { await api.post(`/teams/${team.currentId}/items`, { type: 'TASK', title: title.trim(), destination: 'POOL' }); toast.show('任务已创建'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '创建失败') } }
function newStory(t: Item){ ui.createPreset = { type: 'STORY', parentId: t.id }; ui.createOpen = true }
</script>
<style scoped>
.filters{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:14px}
.trow{border-radius:var(--r-card);padding:6px 12px;margin-bottom:10px}
.thead{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:9px 4px;flex-wrap:wrap}
.thead .rot{transform:rotate(-90deg)}.thead svg{transition:transform .2s}
.bar{width:4px;height:22px;border-radius:2px;flex:none}.code{color:var(--theme);font-weight:700}.grow{flex:1}
.tpill{font-size:12px;font-weight:600;background:var(--sel);color:#0A5CD6;padding:2px 9px;border-radius:999px}
.stories{border-top:1px solid var(--field-brd);padding:8px 2px}
.srow{display:flex;align-items:center;gap:9px;width:100%;text-align:left;padding:8px 10px;border-radius:11px}.srow:hover{background:var(--hover)}
.tdot{width:8px;height:8px;border-radius:50%;flex:none}
.sfoot{display:flex;gap:8px;margin-top:8px}
</style>
