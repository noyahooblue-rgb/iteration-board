<template>
<AppShell title="小组设置" @refresh="load">
  <div class="layout">
    <GlassPanel class="side">
      <button v-for="t in tabs" :key="t.k" class="tab press" :class="{on:tab===t.k}" @click="tab=t.k">{{t.label}}</button>
      <RouterLink to="/trash" class="tab link">回收站</RouterLink>
    </GlassPanel>
    <GlassPanel class="body">
      <template v-if="tab==='members'">
        <h2>成员与角色</h2>
        <p class="small">共 {{team.members.length}} 名成员{{ isLeader ? '；组长可调整角色' : '' }}。成员状态影响催更与迭代容量。</p>
        <div v-for="m in team.members" :key="m.userId" class="mrow">
          <Avatar :name="m.displayName" :color="m.avatarColor" :size="34"/>
          <span class="grow"><b>{{m.displayName}}</b> <span class="small">@{{m.loginName}}</span><br/><span class="tiny">{{MEMBER_STATUS_META[m.memberStatus] ?? m.memberStatus}}</span></span>
          <select v-if="isLeader" :value="m.role" @change="setRole(m, ($event.target as HTMLSelectElement).value)" aria-label="角色">
            <option value="LEADER">组长</option><option value="PO">PO</option><option value="MEMBER">成员</option>
          </select>
          <span v-else class="rolepill">{{roleName(m.role)}}</span>
          <span v-if="isLeader" class="statusctl">
            <select v-model="statusDraft[m.userId].status" aria-label="成员状态">
              <option value="NORMAL">正常</option><option value="LEAVE">请假</option><option value="TRAVEL">出差</option><option value="SECONDMENT">外借</option>
            </select>
            <input type="date" v-model="statusDraft[m.userId].start" aria-label="状态开始"/>
            <input type="date" v-model="statusDraft[m.userId].end" aria-label="状态结束"/>
            <AppButton variant="ghost" @click="saveStatus(m)">保存状态</AppButton>
          </span>
        </div>
      </template>
      <template v-else-if="tab==='basic'">
        <h2>基本设置</h2>
        <AppInput v-model="basic.name" label="小组名称"/>
        <AppInput v-model="basic.description" type="textarea" label="小组描述" style="margin-top:10px"/>
        <div class="frow">
          <label class="small">主题色<input type="color" v-model="basic.themeColor" class="colorin"/></label>
          <label class="small">迭代周期（周）<input type="number" min="1" max="8" v-model.number="basic.iterationWeeks" class="flexin"/></label>
          <label class="small">加入需审批<select v-model="basic.joinApproval" class="flexin"><option :value="true">需要审批</option><option :value="false">凭邀请码直接加入</option></select></label>
          <label class="small">估算单位<select v-model="basic.estimateUnit" class="flexin"><option value="POINT">点</option><option value="HOUR">小时</option><option value="DAY">人天</option></select></label>
        </div>
        <AppButton style="margin-top:14px" @click="saveBasic">保存基本设置</AppButton>
      </template>
      <template v-else-if="tab==='remind'">
        <h2>容量与自动提醒</h2>
        <p class="small">自动提醒在工作日 9:00–18:00 每小时扫描；同规则、同事项、同人每天只提醒一次。请假/出差成员不被提醒，改提醒组长。</p>
        <div class="frow">
          <label class="small">容量系数（每人每个工作日的故事点）<input type="number" min="0" step="0.25" v-model.number="settings.capacityPointsPerWorkday" class="flexin"/></label>
          <label class="small">进行中未更新提醒（工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.staleInProgressWorkdays" class="flexin"/></label>
          <label class="small">已认领未开始提醒（工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.notStartedWorkdays" class="flexin"/></label>
          <label class="small">待验收积压提醒（工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.reviewPendingWorkdays" class="flexin"/></label>
          <label class="small">无人认领提醒（工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.unclaimedWorkdays" class="flexin"/></label>
          <label class="small">阻塞未解提醒（工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.blockedWorkdays" class="flexin"/></label>
          <label class="small">即将到期提醒（提前工作日）<input type="number" min="0" v-model.number="settings.reminderThresholds.dueSoonWorkdays" class="flexin"/></label>
        </div>
        <AppInput v-model="specialOffText" type="textarea" label="特殊非工作日（每行一个日期 YYYY-MM-DD，如节假日）" style="margin-top:10px"/>
        <AppInput v-model="specialWorkText" type="textarea" label="特殊工作日（每行一个日期 YYYY-MM-DD，如周末补班）" style="margin-top:10px"/>
        <AppButton style="margin-top:14px" @click="saveSettings">保存容量与提醒设置</AppButton>
      </template>
      <template v-else-if="tab==='labels'"><LabelsPanel/></template>
      <template v-else-if="tab==='ai'"><AiPanel/></template>
      <template v-else-if="tab==='integrations'"><IntegrationsPanel/></template>
      <template v-else-if="tab==='requests'">
        <h2>加入申请审批</h2>
        <template v-if="isLeader">
          <div v-for="r in requests" :key="r.id" class="mrow">
            <span class="grow"><b>{{r.displayName}}</b> <span class="small">@{{r.loginName}}</span><br/><span class="tiny">申请于 {{fmt(r.createdAt)}}</span></span>
            <select v-model="reqRoles[r.id]" aria-label="分配角色"><option value="MEMBER">成员</option><option value="PO">PO</option><option value="LEADER">组长</option></select>
            <AppButton @click="approve(r)">通过</AppButton><AppButton variant="ghost" @click="reject(r)">拒绝</AppButton>
          </div>
          <p v-if="!requests.length" class="small">暂无待审批的加入申请。</p>
        </template>
        <p v-else class="small">只有组长可以审批加入申请。</p>
      </template>
      <template v-else-if="tab==='invite'">
        <h2>邀请码</h2>
        <template v-if="isLeader">
          <p class="small">把邀请码发给组员，对方在「创建 / 加入小组」页输入后{{ team.current?.joinApproval ? '提交申请、由你审批' : '直接加入' }}。</p>
          <p class="code">{{ team.current?.inviteCode ?? '—' }}</p>
          <AppButton variant="ghost" @click="resetCode">重置邀请码</AppButton>
          <p class="tiny">重置后旧邀请码立即失效。</p>
        </template>
        <p v-else class="small">邀请码只有组长可见。</p>
      </template>
      <template v-else>
        <h2>小组与退出</h2>
        <p class="small">小组：<b>{{team.current?.name}}</b>（代号 {{team.current?.code}}）· 估算单位：{{unit}} · 迭代周期：{{team.current?.iterationWeeks}} 周 · 状态：{{team.current?.status === 'ARCHIVED' ? '已归档' : '正常'}}</p>
        <div class="danger">
          <p><b>归档小组</b><br/><span class="small">归档后小组只读（仅组长可归档）。</span></p>
          <AppButton variant="danger" :disabled="!isLeader || team.current?.status==='ARCHIVED'" @click="archiveTeam">{{team.current?.status==='ARCHIVED' ? '已归档' : '归档小组'}}</AppButton>
          <p style="margin-top:14px"><b>退出小组</b><br/><span class="small">退出后将看不到本小组的任何事项；组长退出需先移交组长职责。</span></p>
          <AppButton variant="ghost" @click="leaveTeam">退出小组</AppButton>
        </div>
      </template>
    </GlassPanel>
  </div>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import Avatar from '../components/Avatar.vue'
import AppInput from '../components/AppInput.vue'
import LabelsPanel from '../components/settings/LabelsPanel.vue'; import AiPanel from '../components/settings/AiPanel.vue'; import IntegrationsPanel from '../components/settings/IntegrationsPanel.vue'
import { useRouter } from 'vue-router'
import { api, ApiError } from '../api/http'; import { MEMBER_STATUS_META, estimateUnitLabel, type JoinRequest, type Member, type TeamSettings } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const team = useTeam(), toast = useToast(), router = useRouter()
const tab = ref('members')
const tabs = [{k:'members',label:'成员与角色'},{k:'basic',label:'基本设置'},{k:'remind',label:'容量与提醒'},{k:'labels',label:'标签'},{k:'ai',label:'AI'},{k:'integrations',label:'对接'},{k:'requests',label:'加入申请'},{k:'invite',label:'邀请码'},{k:'danger',label:'归档与退出'}]
const basic = reactive({ name: '', description: '', themeColor: '#0071E3', iterationWeeks: 2, joinApproval: true, estimateUnit: 'POINT' })
const settings = reactive<TeamSettings>({ capacityPointsPerWorkday: 1, reminderThresholds: { staleInProgressWorkdays: 2, notStartedWorkdays: 2, reviewPendingWorkdays: 2, unclaimedWorkdays: 3, blockedWorkdays: 2, dueSoonWorkdays: 1 }, specialHolidays: [], specialWorkdays: [] })
const specialOffText = ref(''), specialWorkText = ref('')
const statusDraft = reactive<Record<number,{status:string;start:string;end:string}>>({})
function syncForms(){ const t = team.current; if (!t) return
  basic.name = t.name; basic.description = t.description ?? ''; basic.themeColor = t.themeColor ?? '#0071E3'; basic.iterationWeeks = t.iterationWeeks ?? 2; basic.joinApproval = !!t.joinApproval; basic.estimateUnit = (t.estimateUnit ?? 'POINT').toUpperCase()
  if (t.settings) { settings.capacityPointsPerWorkday = t.settings.capacityPointsPerWorkday; Object.assign(settings.reminderThresholds, t.settings.reminderThresholds ?? {}); settings.specialHolidays = t.settings.specialHolidays ?? []; settings.specialWorkdays = t.settings.specialWorkdays ?? [] }
  specialOffText.value = (settings.specialHolidays ?? []).join('\n'); specialWorkText.value = (settings.specialWorkdays ?? []).join('\n')
  for (const m of team.members) statusDraft[m.userId] ??= { status: m.memberStatus, start: m.memberStatusStart ?? '', end: m.memberStatusEnd ?? '' } }
async function saveBasic(){ try { await api.patch(`/teams/${team.currentId}`, { ...basic }); toast.show('基本设置已保存'); await team.loadTeams(); await team.loadTeamData(); syncForms() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function saveSettings(){ settings.specialHolidays = specialOffText.value.split('\n').map(s=>s.trim()).filter(Boolean); settings.specialWorkdays = specialWorkText.value.split('\n').map(s=>s.trim()).filter(Boolean)
  try { await api.patch(`/teams/${team.currentId}`, { settings: { ...settings } }); toast.show('容量与提醒设置已保存'); await team.loadTeams(); syncForms() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function saveStatus(m: Member){ const d = statusDraft[m.userId]; try { await api.patch(`/teams/${team.currentId}/members/${m.userId}/status`, { memberStatus: d.status, memberStatusStart: d.start || null, memberStatusEnd: d.end || null }); toast.show('成员状态已更新'); await team.loadTeamData() } catch(e){ toast.show(e instanceof ApiError ? e.message : '更新失败') } }
async function archiveTeam(){ if (!confirm('确定归档本小组吗？归档后小组只读。')) return; try { await api.post(`/teams/${team.currentId}/archive`); toast.show('小组已归档'); await team.loadTeams() } catch(e){ toast.show(e instanceof ApiError ? e.message : '归档失败') } }
async function leaveTeam(){ if (!confirm('确定退出本小组吗？退出后将看不到本小组的任何事项。')) return; try { await api.post(`/teams/${team.currentId}/leave`); toast.show('已退出小组'); await team.loadTeams(); router.push('/workbench') } catch(e){ toast.show(e instanceof ApiError ? e.message : '退出失败') } }
const isLeader = computed(()=> team.current?.myRole === 'LEADER')
const unit = computed(()=> estimateUnitLabel(team.current))
const requests = ref<JoinRequest[]>([]); const reqRoles = reactive<Record<number,string>>({})
const fmt = (s:string)=> s ? s.slice(0,16).replace('T',' ') : ''
const roleName = (r:string)=> r==='LEADER' ? '组长' : r==='PO' ? 'PO' : '成员'
async function load(){ syncForms(); if (isLeader.value && team.currentId) { requests.value = await api.get(`/teams/${team.currentId}/join-requests`).catch(()=>[]); for (const r of requests.value) reqRoles[r.id] ??= 'MEMBER' } }
onMounted(load)
async function setRole(m: Member, role: string){ try{ await api.patch(`/teams/${team.currentId}/members/${m.userId}/role`, { role }); toast.show('角色已更新'); await team.loadTeamData(); if (team.currentId === team.current?.id) await team.loadTeams() }catch(e){ toast.show(e instanceof ApiError ? e.message : '更新失败') } }
async function approve(r: JoinRequest){ try{ await api.post(`/teams/${team.currentId}/join-requests/${r.id}/approve`, { role: reqRoles[r.id] ?? 'MEMBER' }); toast.show('已通过申请'); load(); team.loadTeamData() }catch(e){ toast.show(e instanceof ApiError ? e.message : '审批失败') } }
async function reject(r: JoinRequest){ try{ await api.post(`/teams/${team.currentId}/join-requests/${r.id}/reject`); toast.show('已拒绝申请'); load() }catch(e){ toast.show(e instanceof ApiError ? e.message : '审批失败') } }
async function resetCode(){ if (!confirm('确定要重置邀请码吗？旧邀请码将立即失效。')) return; try{ const res = await api.post(`/teams/${team.currentId}/invite-code/reset`); toast.show(`新邀请码：${res.inviteCode}`); await team.loadTeams() }catch(e){ toast.show(e instanceof ApiError ? e.message : '重置失败') } }
</script>
<style scoped>
.layout{display:grid;grid-template-columns:200px 1fr;gap:14px;align-items:start}
.side{display:flex;flex-direction:column;gap:4px;padding:14px}
.tab{text-align:left;padding:9px 12px;border-radius:11px;font-weight:500;min-height:40px;text-decoration:none;color:var(--text)}
.tab.on{background:var(--theme);color:#fff}.tab.link{color:var(--text-2)}
.body{padding:20px}
.mrow{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--field-brd)}
.mrow .grow{flex:1}.mrow select{min-height:38px;border-radius:10px;border:1px solid var(--field-brd);background:var(--field);padding:0 8px}
.rolepill{font-size:12px;font-weight:600;background:var(--sel);color:#0A5CD6;padding:3px 10px;border-radius:999px}
.code{font-size:26px;font-weight:700;letter-spacing:3px;margin:14px 0}
.danger{margin-top:10px;border:1px solid rgba(255,59,48,.3);background:rgba(255,59,48,.06);border-radius:14px;padding:14px}
.frow{display:flex;gap:14px;flex-wrap:wrap;margin-top:12px}.frow label{display:flex;flex-direction:column;gap:6px;flex:1;min-width:180px}
.flexin{min-height:40px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px}
.colorin{width:56px;height:36px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:2px}
.statusctl{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.statusctl input[type="date"]{min-height:38px;border-radius:10px;border:1px solid var(--field-brd);background:var(--field);padding:0 8px}
@media(max-width:820px){.layout{grid-template-columns:1fr}}
</style>
