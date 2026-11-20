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
          <AppButton v-if="isLeader && m.userId !== auth.user?.id" variant="ghost" @click="openRemove(m)">移除</AppButton>
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
        <h3 style="margin-top:20px">默认验收角色（修订四 P17）</h3>
        <p class="small">新建故事/任务时默认由哪个角色验收；任何人不能验收自己负责的事项，此时会自动转交给另一角色并在事项上提示。</p>
        <div class="frow">
          <label class="small">故事默认验收角色<select v-model="settings.storyAcceptRole" class="flexin"><option value="PO">PO</option><option value="LEADER">组长</option></select></label>
          <label class="small">任务默认验收角色<select v-model="settings.taskAcceptRole" class="flexin"><option value="LEADER">组长</option><option value="PO">PO</option></select></label>
        </div>
        <AppButton style="margin-top:12px" @click="saveAcceptRoles">保存验收设置</AppButton>
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
      <template v-else-if="tab==='audit'">
        <h2>审计日志</h2>
        <p class="small">记录本组关键写操作（删除事项、成员管理、生成报告等），按时间倒序。{{ isAdmin ? '平台管理员可切换查看全部小组。' : '' }}</p>
        <div class="frow" style="align-items:flex-end">
          <div v-if="isAdmin" class="seg" role="group" aria-label="审计范围">
            <button class="press" :class="{on:auditScope==='team'}" @click="setScope('team')">本组</button>
            <button class="press" :class="{on:auditScope==='all'}" @click="setScope('all')">全部小组</button>
          </div>
          <label class="small">动作<select v-model="auditFilter.action" class="flexin" @change="reloadAudit"><option value="">全部动作</option><option v-for="a in auditActions" :key="a" :value="a">{{auditActionLabel(a)}}</option></select></label>
          <label class="small">操作人<select v-model="auditFilter.actorId" class="flexin" @change="reloadAudit"><option value="">全部操作人</option><option v-for="a in auditActors" :key="a.id" :value="String(a.id)">{{a.name}}</option></select></label>
          <label class="small">起始日期<input type="date" v-model="auditFilter.from" class="flexin" @change="reloadAudit"/></label>
          <label class="small">结束日期<input type="date" v-model="auditFilter.to" class="flexin" @change="reloadAudit"/></label>
        </div>
        <table class="atable">
          <thead><tr><th>时间</th><th>操作人</th><th>动作</th><th v-if="auditScope==='all'">小组</th><th>对象</th><th>详情</th></tr></thead>
          <tbody>
            <tr v-for="l in auditLogs" :key="l.id"><td class="nowrap">{{fmt(l.createdAt)}}</td><td>{{l.actorName}}</td><td>{{auditActionLabel(l.action)}}</td><td v-if="auditScope==='all'">小组 #{{l.teamId}}</td><td class="nowrap">{{auditTargetLabel(l)}}</td><td>{{l.detail ?? '—'}}</td></tr>
            <tr v-if="!auditLogs.length"><td :colspan="auditScope==='all'?6:5" class="small">暂无符合条件的审计记录。</td></tr>
          </tbody>
        </table>
        <div class="pager">
          <span class="small">共 {{auditTotal}} 条 · 第 {{auditPage}} / {{auditPages}} 页</span>
          <AppButton variant="ghost" :disabled="auditPage<=1" @click="gotoAudit(auditPage-1)">上一页</AppButton>
          <AppButton variant="ghost" :disabled="auditPage>=auditPages" @click="gotoAudit(auditPage+1)">下一页</AppButton>
        </div>
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
          <AppButton variant="ghost" @click="leaveOpen=true">退出小组</AppButton>
        </div>
      </template>
    </GlassPanel>
  </div>
  <Modal :open="!!removeTarget" @close="removeTarget=null">
    <h2>移除成员 · {{removeTarget?.displayName}}</h2>
    <HandoverPanel v-if="removeTarget && team.currentId" :team-id="team.currentId" :user-id="removeTarget.userId" confirm-label="确认移除" @cancel="removeTarget=null" @confirm="confirmRemove"/>
  </Modal>
  <Modal :open="leaveOpen" @close="leaveOpen=false">
    <h2>退出小组前交接</h2>
    <HandoverPanel v-if="auth.user && team.currentId" :team-id="team.currentId" :user-id="auth.user.id" confirm-label="确认退出" @cancel="leaveOpen=false" @confirm="confirmLeave"/>
  </Modal>
</AppShell>
</template>
<script setup lang="ts">
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import Avatar from '../components/Avatar.vue'
import AppInput from '../components/AppInput.vue'
import LabelsPanel from '../components/settings/LabelsPanel.vue'; import AiPanel from '../components/settings/AiPanel.vue'; import IntegrationsPanel from '../components/settings/IntegrationsPanel.vue'
import { useRouter } from 'vue-router'
import { api, ApiError } from '../api/http'; import { MEMBER_STATUS_META, estimateUnitLabel, auditActionLabel, auditTargetLabel, type JoinRequest, type Member, type TeamSettings, type AuditLog } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'; import { useAuth } from '../stores/auth'
import { fmtDateTime } from '../utils/datetime'
const team = useTeam(), toast = useToast(), router = useRouter(), auth = useAuth()
const tab = ref('members')
const isAdmin = computed(()=> !!auth.user?.platformAdmin)
const canAudit = computed(()=> team.current?.myRole === 'LEADER' || isAdmin.value)
const tabs = computed(()=> { const base = [{k:'members',label:'成员与角色'},{k:'basic',label:'基本设置'},{k:'remind',label:'容量与提醒'},{k:'labels',label:'标签'},{k:'ai',label:'AI'},{k:'integrations',label:'对接'},{k:'requests',label:'加入申请'},{k:'invite',label:'邀请码'}]
  if (canAudit.value) base.push({k:'audit',label:'审计日志'})
  base.push({k:'danger',label:'归档与退出'}); return base })
// M5 Q6 审计日志：筛选（动作/操作人/起止日期）+ 分页；管理员可切「全部小组」走 /admin/audit-logs。
const auditScope = ref<'team'|'all'>('team')
const auditLogs = ref<AuditLog[]>([]); const auditTotal = ref(0); const auditPage = ref(1); const AUDIT_SIZE = 20
const auditPages = computed(()=> Math.max(1, Math.ceil(auditTotal.value / AUDIT_SIZE)))
const auditFilter = reactive({ action: '', actorId: '', from: '', to: '' })
const auditActors = ref<{id:number;name:string}[]>([]); const auditActions = ref<string[]>([])
async function loadAudit(){ if (!canAudit.value) return
  const qs = new URLSearchParams({ page: String(auditPage.value - 1), size: String(AUDIT_SIZE) })
  if (auditFilter.action) qs.set('action', auditFilter.action); if (auditFilter.actorId) qs.set('actorId', auditFilter.actorId)
  if (auditFilter.from) qs.set('from', new Date(auditFilter.from + 'T00:00:00').toISOString()); if (auditFilter.to) qs.set('to', new Date(auditFilter.to + 'T23:59:59').toISOString())
  try { const r = auditScope.value === 'all' && isAdmin.value ? await api.get(`/admin/audit-logs?${qs}`) : await api.get(`/teams/${team.currentId}/audit-logs?${qs}`)
    auditLogs.value = r.content ?? []; auditTotal.value = r.totalElements ?? 0
    // 筛选下拉取自本组数据里出现过的操作人/动作（随加载累积，避免翻页后丢失选项）
    const am = new Map(auditActors.value.map(a => [a.id, a])); const acts = new Set(auditActions.value)
    for (const l of auditLogs.value) { am.set(l.actorId, { id: l.actorId, name: l.actorName }); acts.add(l.action) }
    auditActors.value = [...am.values()]; auditActions.value = [...acts]
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '审计日志加载失败') } }
function reloadAudit(){ auditPage.value = 1; loadAudit() }
function gotoAudit(p: number){ auditPage.value = p; loadAudit() }
function setScope(s: 'team'|'all'){ auditScope.value = s; reloadAudit() }
watch(tab, t => { if (t === 'audit') { auditPage.value = 1; loadAudit() } })
const basic = reactive({ name: '', description: '', themeColor: '#0071E3', iterationWeeks: 2, joinApproval: true, estimateUnit: 'POINT' })
const settings = reactive<TeamSettings>({ storyAcceptRole: 'PO', taskAcceptRole: 'LEADER', capacityPointsPerWorkday: 1, reminderThresholds: { staleInProgressWorkdays: 2, notStartedWorkdays: 2, reviewPendingWorkdays: 2, unclaimedWorkdays: 3, blockedWorkdays: 2, dueSoonWorkdays: 1 }, specialHolidays: [], specialWorkdays: [] })
const specialOffText = ref(''), specialWorkText = ref('')
const statusDraft = reactive<Record<number,{status:string;start:string;end:string}>>({})
function syncForms(){ const t = team.current; if (!t) return
  basic.name = t.name; basic.description = t.description ?? ''; basic.themeColor = t.themeColor ?? '#0071E3'; basic.iterationWeeks = t.iterationWeeks ?? 2; basic.joinApproval = !!t.joinApproval; basic.estimateUnit = (t.estimateUnit ?? 'POINT').toUpperCase()
  if (t.settings) { settings.storyAcceptRole = t.settings.storyAcceptRole ?? 'PO'; settings.taskAcceptRole = t.settings.taskAcceptRole ?? 'LEADER'; settings.capacityPointsPerWorkday = t.settings.capacityPointsPerWorkday; Object.assign(settings.reminderThresholds, t.settings.reminderThresholds ?? {}); settings.specialHolidays = t.settings.specialHolidays ?? []; settings.specialWorkdays = t.settings.specialWorkdays ?? [] }
  specialOffText.value = (settings.specialHolidays ?? []).join('\n'); specialWorkText.value = (settings.specialWorkdays ?? []).join('\n')
  for (const m of team.members) statusDraft[m.userId] ??= { status: m.memberStatus, start: m.memberStatusStart ?? '', end: m.memberStatusEnd ?? '' } }
async function saveBasic(){ try { await api.patch(`/teams/${team.currentId}`, { ...basic }); toast.show('基本设置已保存'); await team.loadTeams(); await team.loadTeamData(); syncForms() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function saveAcceptRoles(){ try { await api.patch(`/teams/${team.currentId}`, { settings: { ...settings } }); toast.show('验收设置已保存'); await team.loadTeams(); syncForms() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
// 移除成员 / 退出小组（P05）：先开交接面板，pendingCount=0 后才真正移除/退出。
import Modal from '../components/Modal.vue'; import HandoverPanel from '../components/HandoverPanel.vue'
const removeTarget = ref<Member | null>(null), leaveOpen = ref(false)
function openRemove(m: Member){ removeTarget.value = m }
async function confirmRemove(){ if (!removeTarget.value) return; try { await api.del(`/teams/${team.currentId}/members/${removeTarget.value.userId}`); toast.show('成员已移除'); removeTarget.value = null; await team.loadTeamData(); load() } catch(e){ toast.show(e instanceof ApiError ? e.message : '移除失败') } }
async function confirmLeave(){ try { await api.post(`/teams/${team.currentId}/leave`); toast.show('已退出小组'); leaveOpen.value = false; await team.loadTeams(); router.push('/workbench') } catch(e){ toast.show(e instanceof ApiError ? e.message : '退出失败') } }
async function saveSettings(){ settings.specialHolidays = specialOffText.value.split('\n').map(s=>s.trim()).filter(Boolean); settings.specialWorkdays = specialWorkText.value.split('\n').map(s=>s.trim()).filter(Boolean)
  try { await api.patch(`/teams/${team.currentId}`, { settings: { ...settings } }); toast.show('容量与提醒设置已保存'); await team.loadTeams(); syncForms() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function saveStatus(m: Member){ const d = statusDraft[m.userId]; try { await api.patch(`/teams/${team.currentId}/members/${m.userId}/status`, { memberStatus: d.status, memberStatusStart: d.start || null, memberStatusEnd: d.end || null }); toast.show('成员状态已更新'); await team.loadTeamData() } catch(e){ toast.show(e instanceof ApiError ? e.message : '更新失败') } }
async function archiveTeam(){ if (!confirm('确定归档本小组吗？归档后小组只读。')) return; try { await api.post(`/teams/${team.currentId}/archive`); toast.show('小组已归档'); await team.loadTeams() } catch(e){ toast.show(e instanceof ApiError ? e.message : '归档失败') } }
async function leaveTeam(){ if (!confirm('确定退出本小组吗？退出后将看不到本小组的任何事项。')) return; try { await api.post(`/teams/${team.currentId}/leave`); toast.show('已退出小组'); await team.loadTeams(); router.push('/workbench') } catch(e){ toast.show(e instanceof ApiError ? e.message : '退出失败') } }
const isLeader = computed(()=> team.current?.myRole === 'LEADER')
const unit = computed(()=> estimateUnitLabel(team.current))
const requests = ref<JoinRequest[]>([]); const reqRoles = reactive<Record<number,string>>({})
const fmt = fmtDateTime
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
.seg{display:inline-flex;background:var(--track);border-radius:999px;padding:3px;gap:2px}
.seg button{padding:6px 14px;border-radius:999px;font-size:13px;font-weight:500;color:var(--text-2);min-height:32px}
.seg button.on{background:var(--card-solid);color:var(--text);box-shadow:0 1px 4px rgba(10,40,90,.18);font-weight:600}
.atable{width:100%;border-collapse:collapse;margin-top:14px;font-size:13px}
.atable th{text-align:left;color:var(--text-2);font-weight:600;padding:8px;border-bottom:1px solid var(--field-brd);white-space:nowrap}
.atable td{padding:8px;border-bottom:1px solid var(--field-brd);vertical-align:top}
.atable .nowrap{white-space:nowrap}
.pager{display:flex;gap:10px;align-items:center;margin-top:12px}.pager .small{margin-right:auto}
@media(max-width:820px){.layout{grid-template-columns:1fr}}
</style>
