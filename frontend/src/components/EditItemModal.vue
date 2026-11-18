<template>
<Modal :open="!!item" @close="$emit('close')">
  <h2 style="margin-bottom:12px">编辑事项 <span class="small">{{item?.code}}</span></h2>
  <template v-if="item">
    <AppInput v-model="f.title" label="标题" placeholder="事项标题"/>
    <AppInput v-model="f.description" label="描述" type="textarea" placeholder="背景、范围、验收口径…" style="margin-top:10px"/>
    <div class="row">
      <label class="fld">优先级<select v-model="f.priority"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select></label>
      <label class="fld">截止日期<input type="date" v-model="f.dueDate"/></label>
      <label class="fld">估算（{{unit}}）<input type="number" min="0" step="0.5" v-model.number="f.estimate"/></label>
    </div>
    <div class="row">
      <label class="fld">验收人<select v-model.number="f.acceptorId"><option :value="0">未设置</option><option v-for="m in team.members" :key="m.userId" :value="m.userId">{{m.displayName}}</option></select></label>
      <label v-if="item.type!=='TASK'" class="fld">所属迭代<select v-model="f.iterationId"><option value="">日常（不属于迭代）</option><option v-if="team.iteration" :value="String(team.iteration.id)">{{team.iteration.name}}</option></select></label>
      <label v-if="item.type==='STORY'" class="fld">故事类别<select v-model="f.storyCategory"><option v-for="(m,k) in STORY_CAT_META" :key="k" :value="k">{{m.label}}</option></select></label>
      <label v-if="item.type==='STORY'" class="fld">所属任务<select v-model.number="f.parentId"><option v-for="t in tasks" :key="t.id" :value="t.id">{{t.code}} · {{t.title}}</option></select></label>
      <label v-if="item.type==='TASK'" class="fld">所属 PO<select v-model.number="f.poId"><option v-for="m in team.members" :key="m.userId" :value="m.userId">{{m.displayName}}</option></select></label>
    </div>
    <p v-if="err" class="err">{{err}}</p>
    <div class="foot"><span style="flex:1"/><AppButton variant="ghost" @click="$emit('close')">取消</AppButton><AppButton :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</AppButton></div>
  </template>
</Modal>
</template>
<script setup lang="ts">
import { reactive, ref, watch, computed } from 'vue'
import Modal from './Modal.vue'; import AppButton from './AppButton.vue'; import AppInput from './AppInput.vue'
import { api, ApiError } from '../api/http'; import { STORY_CAT_META, estimateUnitLabel, type Item } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const props = defineProps<{item:Item|null;tasks:Item[]}>()
const emit = defineEmits(['close','saved'])
const team = useTeam(), toast = useToast()
const err = ref(''), saving = ref(false)
const unit = computed(()=> estimateUnitLabel(team.current))
const f = reactive<any>({ title:'', description:'', priority:'P2', dueDate:'', estimate:null, acceptorId:0, iterationId:'', storyCategory:'DEV', parentId:0, poId:0 })
watch(()=> props.item, (it)=>{ if(!it) return; Object.assign(f, { title: it.title, description: it.description ?? '', priority: it.priority, dueDate: it.dueDate ?? '', estimate: it.estimate, acceptorId: it.acceptorId ?? 0, iterationId: it.iterationId ? String(it.iterationId) : '', storyCategory: it.storyCategory ?? 'DEV', parentId: it.parentId ?? 0, poId: it.poId ?? 0 }) })
async function save(){
  if (!props.item || saving.value) return; err.value = ''
  if (!f.title.trim()) { err.value = '标题必填'; return }
  const body:any = { version: props.item.version, title: f.title.trim(), description: f.description, priority: f.priority, dueDate: f.dueDate || null, estimate: f.estimate ?? null, acceptorId: f.acceptorId || null }
  if (props.item.type !== 'TASK') body.iterationId = f.iterationId ? Number(f.iterationId) : null
  if (props.item.type === 'STORY') { body.storyCategory = f.storyCategory; body.parentId = f.parentId || null }
  if (props.item.type === 'TASK') body.poId = f.poId || null
  saving.value = true; try { const it = await api.patch(`/teams/${team.currentId}/items/${props.item.id}`, body); toast.show('已保存'); emit('saved', it); emit('close') }
  catch(e){ err.value = e instanceof ApiError ? e.message : '保存失败' } finally { saving.value = false }
}
</script>
<style scoped>
.row{display:flex;gap:10px;flex-wrap:wrap;margin-top:10px}.fld{display:block;font-size:12px;color:var(--text-2);flex:1;min-width:110px}
.fld select,.fld input{width:100%;min-height:42px;margin-top:6px;padding:8px 10px;border-radius:var(--r-ctl);border:1px solid var(--field-brd);background:var(--field)}
.foot{display:flex;align-items:center;gap:10px;margin-top:16px}.err{color:var(--c-red);font-size:12px;margin-top:10px}
</style>
