<template>
<Modal :open="ui.createOpen" @close="ui.createOpen=false">
  <h2 style="margin-bottom:14px">新建事项</h2>
  <div class="types">
    <button v-for="t in visibleTypes" :key="t" class="tp press" :class="{on:form.type===t}" :style="{borderColor: form.type===t? TYPE_META[t].color : 'var(--separator)', color: TYPE_META[t].color}" @click="form.type=t">{{TYPE_META[t].label}}</button>
  </div>
  <p v-if="!isPO" class="tiny" style="margin-top:8px">故事只能由 PO 创建；任务由 PO 在待办池新建。</p>
  <template v-if="form.type==='STORY'">
    <label class="fld" style="margin-top:12px">所属任务（必选）<select v-model.number="form.parentId"><option :value="0" disabled>请选择任务</option><option v-for="t in tasks" :key="t.id" :value="t.id">{{t.code}} · {{t.title}}</option></select></label>
    <label class="fld" style="margin-top:10px">故事类别（必选）<select v-model="form.storyCategory"><option v-for="(m,k) in STORY_CAT_META" :key="k" :value="k">{{m.label}}</option></select></label>
  </template>
  <AppInput v-model="form.title" label="标题（必填）" placeholder="用一句话说清要做什么" :error="errTitle" style="margin-top:12px"/>
  <AppInput v-model="form.description" label="描述（可不填）" type="textarea" placeholder="背景、范围、验收口径…"/>
  <div class="row">
    <label class="fld">优先级<select v-model="form.priority"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select></label>
    <label class="fld">截止日期<input type="date" v-model="form.dueDate"/></label>
    <label class="fld">估算（{{unit}}）<input type="number" min="0" step="0.5" v-model.number="form.estimate"/></label>
    <label class="fld">所属迭代<select v-model="form.iterationId"><option value="">日常</option><option v-if="team.iteration" :value="String(team.iteration.id)">{{team.iteration.name}}</option></select></label>
  </div>
  <div class="fld" style="margin-top:12px">去向
    <div class="types" style="margin-top:6px">
      <button class="tp press" :class="{on:form.destination==='POOL'}" @click="form.destination='POOL'">认领池</button>
      <button class="tp press" :class="{on:form.destination==='INVITE'}" @click="form.destination='INVITE'">邀请认领</button>
      <button class="tp press" :class="{on:form.destination==='ASSIGN'}" @click="form.destination='ASSIGN'">直接指派</button>
      <button class="tp press" :class="{on:form.destination==='COOWN'}" @click="form.destination='COOWN'">共同负责</button>
      <button class="tp press" :class="{on:form.destination==='EVERYONE'}" @click="form.destination='EVERYONE'">每人一份</button>
    </div>
  </div>
  <div v-if="form.destination==='ASSIGN'" class="fld">负责人（单选）
    <div class="chips"><button v-for="m in team.members" :key="m.userId" class="mchip press" :class="{on:form.ownerIds.includes(m.userId)}" @click="form.ownerIds=[m.userId]"><Avatar :name="m.displayName" :color="m.avatarColor" :size="20"/>{{memberLabel(team.members, m)}}<span class="tiny">{{memberNote(m)}}</span></button></div>
  </div>
  <div v-if="form.destination==='COOWN'" class="fld">共同负责人（多选，权限相同）
    <div class="chips"><button v-for="m in team.members" :key="m.userId" class="mchip press" :class="{on:form.ownerIds.includes(m.userId)}" @click="toggleId(form.ownerIds, m.userId)"><Avatar :name="m.displayName" :color="m.avatarColor" :size="20"/>{{memberLabel(team.members, m)}}<span class="tiny">{{memberNote(m)}}</span></button></div>
  </div>
  <div v-if="form.destination==='INVITE'" class="fld">邀请谁认领（多选，任意一人认领即可）
    <div class="chips"><button v-for="m in team.members" :key="m.userId" class="mchip press" :class="{on:form.inviteeIds.includes(m.userId)}" @click="toggleId(form.inviteeIds, m.userId)"><Avatar :name="m.displayName" :color="m.avatarColor" :size="20"/>{{memberLabel(team.members, m)}}</button></div>
  </div>
  <div v-if="form.destination==='EVERYONE'" class="fld">每人一份：勾选成员（或全组），每人生成一个独立事项并直接指派给本人
    <div class="chips"><button class="mchip press" :class="{on:allSelected}" @click="toggleAll">全组 {{team.members.length}} 人</button>
    <button v-for="m in team.members" :key="m.userId" class="mchip press" :class="{on:form.memberIds.includes(m.userId)}" @click="toggleId(form.memberIds, m.userId)"><Avatar :name="m.displayName" :color="m.avatarColor" :size="20"/>{{memberLabel(team.members, m)}}</button></div>
    <label class="small" style="display:block;margin-top:8px"><input type="checkbox" v-model="includeSelf"/> 包含我自己（默认不含发布人本人；不勾时即使全选也会自动排除我）</label>
  </div>
  <p v-if="err" class="err">{{err}}</p>
  <div class="foot"><label class="small"><input type="checkbox" v-model="keepOpen"/> 连续新建</label>
    <span style="flex:1"/><AppButton variant="ghost" @click="ui.createOpen=false">取消</AppButton>
    <AppButton :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : (form.destination==='EVERYONE' ? '批量下发' : '创建事项') }}</AppButton></div>
  <p class="tiny" style="margin-top:8px">不要录入客户信息、生产数据、密码和密钥。</p>
</Modal>
</template>
<script setup lang="ts">
import { reactive, ref, computed, watch } from 'vue'
import Modal from './Modal.vue'; import AppButton from './AppButton.vue'; import AppInput from './AppInput.vue'; import Avatar from './Avatar.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'; import { TYPE_META, STORY_CAT_META, MEMBER_STATUS_META, estimateUnitLabel, type Item, type ItemType, type Member } from '../api/types'
import { useUi } from '../stores/ui'; import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
import { memberLabel } from '../utils/memberLabel'; import { useAuth } from '../stores/auth'
const auth = useAuth()
// P14：每人一份默认不含发布人自己，可勾选包含。
const includeSelf = ref(false)
const ui = useUi(), team = useTeam(), toast = useToast()
const emit = defineEmits(['created'])
const keepOpen = ref(false), err = ref(''), errTitle = ref(''), tasks = ref<Item[]>([])
const submitting = ref(false)
//：弹窗打开时生成一个幂等键，本次意图的重试复用同键，弹窗关闭后作废。
let idemKey = ''
const isPO = computed(()=> team.current?.myRole === 'PO' || team.current?.myRole === 'LEADER')
const visibleTypes = computed<ItemType[]>(()=> isPO.value ? ['STORY','DEFECT','ANALYSIS','ADHOC'] : ['DEFECT','ANALYSIS','ADHOC'])
const unit = computed(()=> estimateUnitLabel(team.current))
const form = reactive<any>({ type: 'DEFECT', title: '', description: '', priority: 'P2', dueDate: '', estimate: null, iterationId: '', destination: 'POOL', parentId: 0, storyCategory: 'DEV', ownerIds: [] as number[], inviteeIds: [] as number[], memberIds: [] as number[] })
const allSelected = computed(()=> team.members.length > 0 && form.memberIds.length === team.members.length)
function toggleId(list:number[], id:number){ const i = list.indexOf(id); i >= 0 ? list.splice(i,1) : list.push(id) }
function toggleAll(){ if (allSelected.value) { form.memberIds = []; return } form.memberIds = team.members.map(m=>m.userId).filter(id => includeSelf.value || id !== auth.user?.id) }
function memberNote(m: Member){ return m.memberStatus !== 'NORMAL' ? MEMBER_STATUS_META[m.memberStatus] : '' }
watch(()=> ui.createOpen, async (open)=>{ if (!open) { idemKey = ''; return } idemKey = newIdempotencyKey(); err.value = ''; errTitle.value = ''
  if (team.currentId) tasks.value = await api.get(`/teams/${team.currentId}/items?scope=all&type=TASK`).catch(()=>[])
  const p = ui.createPreset; if (p) { form.type = p.type ?? form.type; form.parentId = p.parentId ?? 0; form.iterationId = p.iterationId != null ? String(p.iterationId) : form.iterationId; ui.createPreset = null }
  if (!isPO.value && form.type === 'STORY') form.type = 'DEFECT' })
async function submit(){
  if (submitting.value) return
  errTitle.value = form.title.trim() ? '' : '标题必填'; if (errTitle.value) return
  err.value = ''
  const base:any = { type: form.type, title: form.title.trim(), description: form.description || undefined, priority: form.priority, dueDate: form.dueDate || undefined, estimate: form.estimate ?? undefined, iterationId: form.iterationId ? Number(form.iterationId) : undefined }
  if (form.type === 'STORY') { if (!form.parentId) { err.value = '故事必须选择所属任务'; return } base.parentId = form.parentId; base.storyCategory = form.storyCategory }
  submitting.value = true
  try {
    if (!idemKey) idemKey = newIdempotencyKey()
    if (form.destination === 'EVERYONE') {
      const memberIds = includeSelf.value ? [...form.memberIds] : form.memberIds.filter((id:number) => id !== auth.user?.id)
      if (!memberIds.length) { err.value = '请至少勾选一位成员（未勾「包含我自己」时不能只选自己）'; submitting.value = false; return }
      const b = await api.postKey(`/teams/${team.currentId}/dispatch-batches`, { ...base, memberIds }, idemKey)
      toast.show(`已批量下发：${b.total} 人各一份`)
    } else {
      const body:any = { ...base, destination: form.destination === 'COOWN' ? 'ASSIGN' : form.destination }
      if (form.destination === 'ASSIGN' || form.destination === 'COOWN') { if (!form.ownerIds.length) { err.value = '请选择负责人'; return } body.ownerIds = [...form.ownerIds] }
      if (form.destination === 'INVITE') body.inviteeIds = [...form.inviteeIds]
      await api.postKey(`/teams/${team.currentId}/items`, body, idemKey)
      toast.show('事项已创建')
    }
    emit('created'); form.title=''; form.description=''; idemKey = newIdempotencyKey()
    if (!keepOpen.value) ui.createOpen = false
  } catch(e){ err.value = e instanceof ApiError ? e.message : '创建失败' } finally { submitting.value = false }
}
</script>
<style scoped>
.types{display:flex;gap:8px;flex-wrap:wrap}.tp{padding:7px 14px;border-radius:999px;border:1.5px solid var(--separator);font-weight:600;background:var(--card)}
.tp.on{background:var(--theme-soft)}
.row{display:flex;gap:10px;flex-wrap:wrap}.fld{display:block;font-size:12px;color:var(--text-2);flex:1;min-width:110px;margin-top:10px}
.fld select,.fld input{width:100%;min-height:44px;margin-top:6px;padding:8px 10px;border-radius:var(--r-ctl);border:1px solid var(--separator);background:var(--fill)}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.mchip{display:inline-flex;gap:6px;align-items:center;padding:6px 11px;border-radius:999px;border:1.5px solid var(--separator);background:var(--card);font-weight:500;min-height:36px}
.mchip.on{border-color:var(--theme);background:var(--theme-soft)}
.foot{display:flex;align-items:center;gap:10px;margin-top:16px}.err{color:var(--c-red);font-size:12px;margin-top:10px}
</style>
