<template>
  <h2>标签</h2>
  <p class="small">标签在事项上以名称引用；改名会同步到已打标签的事项。在用标签删除时会被拦下，可强制删除（同时解除事项上的标签）。</p>
  <div v-for="l in labels" :key="l.id" class="lrow">
    <span class="dot" :style="{background:l.color}"/>
    <input v-model="draft[l.id]" class="flexin grow" :disabled="!canManage" aria-label="标签名"/>
    <input type="color" v-model="colorDraft[l.id]" class="colorin" :disabled="!canManage" aria-label="颜色"/>
    <span class="tiny">{{ l.usageCount ? `在用 ${l.usageCount} 个事项` : '未使用' }}</span>
    <AppButton v-if="canManage" variant="ghost" :disabled="busy.is('save'+l.id)" @click="save(l)">保存</AppButton>
    <AppButton v-if="canManage" variant="ghost" :disabled="busy.is('del'+l.id)" @click="remove(l)">删除</AppButton>
  </div>
  <p v-if="!labels.length" class="small">暂无标签。</p>
  <div v-if="canManage" class="lrow" style="margin-top:10px">
    <input v-model="newName" class="flexin grow" placeholder="新标签名" @keyup.enter="create"/>
    <input type="color" v-model="newColor" class="colorin" aria-label="颜色"/>
    <AppButton :disabled="busy.is('create') || !newName.trim()" @click="create">新建标签</AppButton>
  </div>
  <p v-if="!canManage" class="tiny">仅组长 / PO 可增删改标签。</p>
</template>
<script setup lang="ts">
// Q12：标签管理放在小组设置「标签」分组，用既有 labels 接口，不建独立页。
import { ref, reactive, computed, onMounted } from 'vue'
import AppButton from '../AppButton.vue'
import { api, ApiError } from '../../api/http'; import type { LabelItem } from '../../api/types'
import { useTeam } from '../../stores/team'; import { useToast } from '../../stores/toast'
import { useBusy } from '../../utils/busy'
const team = useTeam(), toast = useToast(), busy = useBusy()
const labels = ref<LabelItem[]>([]), draft = reactive<Record<number, string>>({}), colorDraft = reactive<Record<number, string>>({})
const newName = ref(''), newColor = ref('#5856D6')
const canManage = computed(() => team.current?.myRole === 'LEADER' || team.current?.myRole === 'PO')
async function load(){ labels.value = await api.get(`/teams/${team.currentId}/labels`).catch(() => []); for (const l of labels.value) { draft[l.id] = l.name; colorDraft[l.id] = l.color } }
onMounted(load)
async function create(){ await busy.wrap('create', async () => { try { await api.post(`/teams/${team.currentId}/labels`, { name: newName.value.trim(), color: newColor.value }); newName.value = ''; toast.show('标签已创建'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '创建失败') } }) }
async function save(l: LabelItem){ await busy.wrap('save' + l.id, async () => { try { await api.patch(`/teams/${team.currentId}/labels/${l.id}`, { name: draft[l.id], color: colorDraft[l.id] }); toast.show('标签已更新'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }) }
async function remove(l: LabelItem){ await busy.wrap('del' + l.id, async () => {
  try { await api.del(`/teams/${team.currentId}/labels/${l.id}`); toast.show('标签已删除'); load() }
  catch(e){ if (e instanceof ApiError && e.code === 'LABEL_IN_USE') { if (confirm(`该标签正在被 ${e.detail?.usageCount ?? l.usageCount} 个事项使用，强制删除会同时解除这些事项的标签，继续吗？`)) { try { await api.del(`/teams/${team.currentId}/labels/${l.id}?force=true`); toast.show('已强制删除'); load() } catch(e2){ toast.show(e2 instanceof ApiError ? e2.message : '删除失败') } } }
    else toast.show(e instanceof ApiError ? e.message : '删除失败') } }) }
</script>
<style scoped>
.lrow{display:flex;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid var(--field-brd)}
.dot{width:12px;height:12px;border-radius:50%;flex:none}.grow{flex:1}
.flexin{min-height:38px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:5px 9px}
.colorin{width:48px;height:34px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:2px}
</style>
