<template>
  <h2>对接</h2>
  <p class="small">把外部系统的事项同步进来。密钥只写不回显（保存后脱敏显示为 ***，编辑时保留 *** 即不更换密钥）。</p>
  <div v-for="a in adapters" :key="a.id" class="arow">
    <span class="grow"><b>{{a.name}}</b> <span class="tiny">{{a.type}} · 系统名 {{a.systemName}}{{ a.enabled ? '' : ' · 已停用' }}{{ hasSecret(a) ? ' · 密钥已设置' : '' }}</span><br/><span class="tiny">{{a.config?.url || '（无地址）'}}</span></span>
    <AppButton variant="ghost" :disabled="busy.is('test'+a.id)" @click="test(a)">测试连接</AppButton>
    <AppButton variant="ghost" :disabled="busy.is('sync'+a.id)" @click="sync(a)">立即同步</AppButton>
    <AppButton v-if="isLeader" variant="ghost" :disabled="busy.is('del'+a.id)" @click="remove(a)">删除</AppButton>
  </div>
  <p v-if="!adapters.length" class="small">暂无适配器。</p>
  <template v-if="isLeader">
    <h3 style="margin-top:14px">新建 / 编辑适配器</h3>
    <div class="frow">
      <label class="small">名称<input v-model="form.name" class="flexin"/></label>
      <label class="small">类型<select v-model="form.type" class="flexin"><option value="MOCK">MOCK（模拟）</option><option value="HTTP">HTTP</option></select></label>
      <label class="small">外部系统名（同步识别键）<input v-model="form.systemName" class="flexin" placeholder="如 JIRA"/></label>
      <label class="small">地址（config.url）<input v-model="form.url" class="flexin" placeholder="https://…"/></label>
      <label class="small">方法（config.method）<select v-model="form.method" class="flexin"><option value="GET">GET</option><option value="POST">POST</option></select></label>
      <label class="small">启用<input type="checkbox" v-model="form.enabled"/></label>
    </div>
    <div class="frow">
      <label class="small">认证方式（config.authType）<select v-model="form.authType" class="flexin"><option value="NONE">无</option><option value="TOKEN">Token</option><option value="BASIC">Basic</option></select></label>
      <label v-if="form.authType==='TOKEN'" class="small">令牌（config.authToken，只写）<input v-model="form.authToken" type="password" class="flexin" placeholder="*** 表示保留原值"/></label>
      <label v-if="form.authType==='BASIC'" class="small">账号（config.authUser）<input v-model="form.authUser" class="flexin"/></label>
      <label v-if="form.authType==='BASIC'" class="small">密码（config.authPassword，只写）<input v-model="form.authPassword" type="password" class="flexin" placeholder="*** 表示保留原值"/></label>
    </div>
    <div class="frow">
      <label class="small">字段路径 · 标题（config.fields.title）<input v-model="form.fields.title" class="flexin" placeholder="$.title"/></label>
      <label class="small">字段路径 · 状态（config.fields.status）<input v-model="form.fields.status" class="flexin" placeholder="$.status"/></label>
      <label class="small">字段路径 · 负责人（config.fields.owner）<input v-model="form.fields.owner" class="flexin" placeholder="$.assignee"/></label>
    </div>
    <div style="margin-top:10px;display:flex;gap:8px">
      <AppButton :disabled="busy.is('save') || !form.name.trim()" @click="save">{{ editingId ? '保存修改' : '新建适配器' }}</AppButton>
      <AppButton v-if="editingId" variant="ghost" @click="resetForm">取消编辑</AppButton>
      <span class="tiny" style="align-self:center">点列表外的「编辑」：在下方表单改后保存（先点适配器名旁的编辑入口即本表单回填）</span>
    </div>
    <div class="frow" style="margin-top:6px">
      <button v-for="a in adapters" :key="'e'+a.id" class="mini press" @click="edit(a)">编辑「{{a.name}}」</button>
    </div>
  </template>
  <h3 style="margin-top:18px">同步日志</h3>
  <div v-for="l in logs" :key="l.id" class="lrow">
    <span class="small grow">{{fmt(l.createdAt)}}</span><span class="tiny">新增 {{l.createdCount}}</span><span class="tiny">更新 {{l.updatedCount}}</span><span class="tiny">失败 {{l.failedCount}}</span><span class="tiny">{{l.message ?? '—'}}</span>
  </div>
  <p v-if="!logs.length" class="tiny">暂无同步记录。</p>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import AppButton from '../AppButton.vue'
import { api, ApiError } from '../../api/http'; import type { AdapterItem, SyncLog } from '../../api/types'
import { useTeam } from '../../stores/team'; import { useToast } from '../../stores/toast'
import { useBusy } from '../../utils/busy'
const team = useTeam(), toast = useToast(), busy = useBusy()
const adapters = ref<AdapterItem[]>([]), logs = ref<SyncLog[]>([]), editingId = ref<number | null>(null)
const form = reactive({ name: '', type: 'MOCK', systemName: '', url: '', method: 'GET', enabled: true, authType: 'NONE', authToken: '', authUser: '', authPassword: '', fields: { title: '', status: '', owner: '' } })
function hasSecret(a: AdapterItem){ const c = a.config ?? {}; return c.authToken === '***' || c.authPassword === '***' || !!c.authToken || !!c.authPassword }
const isLeader = computed(() => team.current?.myRole === 'LEADER')
const fmt = (s: string) => s ? s.slice(0, 16).replace('T', ' ') : ''
async function load(){ adapters.value = await api.get(`/teams/${team.currentId}/integrations/adapters`).catch(() => []); logs.value = await api.get(`/teams/${team.currentId}/integrations/sync-logs`).catch(() => []) }
onMounted(load)
function resetForm(){ editingId.value = null; Object.assign(form, { name: '', type: 'MOCK', systemName: '', url: '', method: 'GET', enabled: true, authType: 'NONE', authToken: '', authUser: '', authPassword: '', fields: { title: '', status: '', owner: '' } }) }
function edit(a: AdapterItem){ editingId.value = a.id; const c = a.config ?? {}; form.name = a.name; form.type = a.type; form.systemName = a.systemName ?? ''; form.url = c.url ?? ''; form.method = c.method ?? 'GET'; form.enabled = a.enabled !== false; form.authType = c.authType ?? 'NONE'; form.authToken = c.authToken ?? ''; form.authUser = c.authUser ?? ''; form.authPassword = c.authPassword ?? ''; form.fields = { title: c.fields?.title ?? '', status: c.fields?.status ?? '', owner: c.fields?.owner ?? '' } }
async function save(){ await busy.wrap('save', async () => { // 契约 IntegrationAdapterInput：{name,type,systemName,config,enabled}；密钥在 config 内，*** 表示保留原值（编辑回填的脱敏值原样回传即可）。
  const config: Record<string, any> = { authType: form.authType }; if (form.url) config.url = form.url; config.method = form.method
  if (form.authType === 'TOKEN' && form.authToken) config.authToken = form.authToken
  if (form.authType === 'BASIC') { if (form.authUser) config.authUser = form.authUser; if (form.authPassword) config.authPassword = form.authPassword }
  const fields: Record<string, string> = {}; for (const k of ['title', 'status', 'owner'] as const) if (form.fields[k]) fields[k] = form.fields[k]; if (Object.keys(fields).length) config.fields = fields
  const body: any = { name: form.name.trim(), type: form.type, systemName: form.systemName.trim(), config, enabled: form.enabled }
  try { if (editingId.value) await api.patch(`/teams/${team.currentId}/integrations/adapters/${editingId.value}`, body); else await api.post(`/teams/${team.currentId}/integrations/adapters`, body); toast.show('适配器已保存'); resetForm(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }) }
async function test(a: AdapterItem){ await busy.wrap('test' + a.id, async () => { try { const r = await api.post(`/teams/${team.currentId}/integrations/adapters/${a.id}/test`); toast.show(r.message ?? (r.ok ? '连接正常' : '连接失败')) } catch(e){ toast.show(e instanceof ApiError ? e.message : '测试失败') } }) }
async function sync(a: AdapterItem){ await busy.wrap('sync' + a.id, async () => { try { const r = await api.post(`/teams/${team.currentId}/integrations/adapters/${a.id}/sync`); toast.show(r.success === false ? `同步失败：${r.message ?? '未知原因'}` : `同步完成：新增 ${r.createdCount}、更新 ${r.updatedCount}、失败 ${r.failedCount}`); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '同步失败') } }) }
async function remove(a: AdapterItem){ if (!confirm(`确定删除适配器「${a.name}」吗？`)) return; await busy.wrap('del' + a.id, async () => { try { await api.del(`/teams/${team.currentId}/integrations/adapters/${a.id}`); toast.show('已删除'); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } }) }
</script>
<style scoped>
.arow{display:flex;gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--field-brd)}.grow{flex:1}
.frow{display:flex;gap:12px;flex-wrap:wrap;margin-top:10px}.frow label{display:flex;flex-direction:column;gap:6px;flex:1;min-width:160px}
.flexin{min-height:38px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:5px 9px}
.lrow{display:flex;gap:12px;align-items:center;padding:6px 0;border-bottom:1px solid var(--field-brd)}
.mini{font-size:12px;color:var(--theme);font-weight:600}
</style>
