<template>
<AppShell title="平台管理">
  <div class="tabs">
    <button v-for="t in tabs" :key="t.k" class="tab press" :class="{ on: tab === t.k }" @click="tab = t.k; load()">{{ t.label }}</button>
  </div>
  <GlassPanel class="body">
    <p v-if="err" class="err">{{ err }}</p>
    <p v-if="loading" class="small">加载中…</p>
    <!-- 账号：GET /admin/users；授予/撤销管理员（修订三三）+ 停用/启用（停用前走离组交接，P05） -->
    <template v-if="tab === 'users'">
      <p class="small" style="margin-bottom:8px">启用中的平台管理员共 {{ enabledAdminCount }} 名；最后一名启用中的管理员不可撤销、不可停用（按钮置灰，后端也会校验）。建议至少保留两名管理员。</p>
      <table class="tbl">
        <thead><tr><th>账号</th><th>登录名</th><th>平台角色</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id"><td>{{ u.displayName }}</td><td>@{{ u.loginName }}</td><td>{{ u.platformAdmin ? '平台管理员' : '普通用户' }}</td><td>{{ statusOf(u) === 'DISABLED' ? '已停用' : '启用中' }}</td><td class="nowrap">{{ fmtDateTime(u.createdAt) }}</td>
            <td class="acts">
              <AppButton v-if="!u.platformAdmin" variant="ghost" @click="grant(u)">授予管理员</AppButton>
              <AppButton v-else variant="ghost" :disabled="enabledAdminCount <= 1" :title="enabledAdminCount <= 1 ? '最后一名启用中的管理员不可撤销' : ''" @click="revoke(u)">撤销管理员</AppButton>
              <AppButton v-if="statusOf(u) !== 'DISABLED'" variant="ghost" :disabled="u.platformAdmin && enabledAdminCount <= 1" :title="u.platformAdmin && enabledAdminCount <= 1 ? '最后一名启用中的管理员不可停用' : ''" @click="openDisable(u)">停用账号</AppButton>
              <AppButton v-else variant="ghost" @click="enable(u)">启用账号</AppButton>
            </td></tr>
          <tr v-if="!users.length && !loading"><td colspan="6" class="small">暂无账号数据。</td></tr>
        </tbody>
      </table>
    </template>
    <!-- 小组：GET /admin/teams -->
    <table v-else-if="tab === 'teams'" class="tbl">
      <thead><tr><th>小组</th><th>代号</th><th>成员数</th><th>创建时间</th></tr></thead>
      <tbody>
        <tr v-for="t in teamsList" :key="t.id"><td>{{ t.name }}</td><td>{{ t.code }}</td><td>{{ t.memberCount }}</td><td class="nowrap">{{ fmtDateTime(t.createdAt) }}</td></tr>
        <tr v-if="!teamsList.length && !loading"><td colspan="4" class="small">暂无小组数据。</td></tr>
      </tbody>
    </table>
    <!-- 系统信息（修订三二/五.3）：GET /admin/system-info + /admin/backup-status -->
    <template v-else-if="tab === 'system'">
      <template v-if="sys">
        <table class="tbl"><tbody>
          <tr><th>程序版本</th><td>{{ sys.appVersion }}（构建日期 {{ sys.buildDate }}）</td></tr>
          <tr><th>数据库版本</th><td>{{ sys.databaseVersion }}</td></tr>
          <tr><th>表结构迁移版本（Flyway）</th><td>{{ sys.flywayVersion }}</td></tr>
          <tr><th>安装目录</th><td>{{ sys.installDir }}</td></tr>
          <tr><th>数据目录大小</th><td>{{ fmtSize(sys.dataDirSizeBytes) }}（{{ sys.dataDir }}）</td></tr>
          <tr><th>最近备份</th><td>{{ sys.lastBackupAt ? fmtDateTime(sys.lastBackupAt) : '暂无成功备份记录' }} · 结果：{{ sys.lastBackupResult ?? '—' }}<span v-if="backup?.backupStale" class="err">（{{ backup.backupStaleMessage ?? '已超过 3 天无成功备份' }}）</span></td></tr>
          <tr><th>服务启动类型</th><td>{{ sys.serviceStartType }}<span v-if="sys.serviceStartTypeNote" class="tiny"> · {{ sys.serviceStartTypeNote }}</span></td></tr>
          <tr><th>AI 连接状态</th><td>{{ aiText(sys) }}</td></tr>
          <tr><th>本机局域网访问地址</th><td><span v-for="a in sys.lanAddresses" :key="a" class="lan">http://{{ a }}:{{ sys.serverPort }}</span>
            <p class="tiny" style="margin-top:6px">若同事无法打开上述地址，多半是本机防火墙未放行 {{ sys.serverPort }} 端口入站，请按部署手册放行。</p></td></tr>
        </tbody></table>
        <div style="margin-top:14px"><AppButton variant="ghost" @click="ui.releaseNotesOpen = true">查看本次更新说明</AppButton></div>
      </template>
      <p v-else-if="!loading" class="small">系统信息加载失败，请重试。</p>
    </template>
    <!-- 审计日志：GET /admin/audit-logs -->
    <template v-else>
      <table class="tbl">
        <thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>小组</th><th>对象</th><th>详情</th></tr></thead>
        <tbody>
          <tr v-for="l in auditLogs" :key="l.id"><td class="nowrap">{{ fmtDateTime(l.createdAt) }}</td><td>{{ l.actorName }}</td><td>{{ auditActionLabel(l.action) }}</td><td>小组 #{{ l.teamId }}</td><td class="nowrap">{{ auditTargetLabel(l) }}</td><td>{{ l.detail ?? '—' }}</td></tr>
          <tr v-if="!auditLogs.length && !loading"><td colspan="6" class="small">暂无审计记录。</td></tr>
        </tbody>
      </table>
      <div class="pager" v-if="auditTotal > AUDIT_SIZE">
        <AppButton variant="ghost" :disabled="auditPage <= 1" @click="auditPage--; load()">上一页</AppButton>
        <span class="small">第 {{ auditPage }} / {{ Math.max(1, Math.ceil(auditTotal / AUDIT_SIZE)) }} 页</span>
        <AppButton variant="ghost" :disabled="auditPage >= Math.ceil(auditTotal / AUDIT_SIZE)" @click="auditPage++; load()">下一页</AppButton>
      </div>
    </template>
  </GlassPanel>
  <!-- 停用账号：后端逐组校验未交接事项并拦截（409 HANDOVER_PENDING，文案含待交接数）；前端不预检（交接清单接口仅小组成员/组长可见） -->
  <Modal :open="!!disableUser" @close="disableUser = null">
    <h2>停用账号 · {{ disableUser?.displayName }}</h2>
    <p class="small" style="margin:8px 0">停用后该账号无法登录。系统会逐个小组检查其未完成事项、待验收事项与发出的批次：只要还有未交接条目，停用会被拦截并提示数量，请先让组长在对应小组的「小组设置 → 成员」里完成交接，或由本人先处理。</p>
    <p v-if="disableErr" class="err">{{ disableErr }}</p>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px">
      <AppButton variant="ghost" @click="disableUser = null">取消</AppButton>
      <AppButton variant="danger" :disabled="disabling" @click="confirmDisable">{{ disabling ? '停用中…' : '确认停用' }}</AppButton>
    </div>
  </Modal>
</AppShell>
</template>
<script setup lang="ts">
// 平台管理（1.0.4 I + 1.1.0 修订三三）：仅平台管理员可见（侧栏入口按 platformAdmin 渲染，路由 /admin 有守卫）。
import { ref, computed, onMounted } from 'vue'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import Modal from '../components/Modal.vue'
import { api, ApiError } from '../api/http'
import { fmtDateTime } from '../utils/datetime'
import { auditActionLabel, auditTargetLabel, type AuditLog, type SystemInfo, type BackupStatus } from '../api/types'
import { useToast } from '../stores/toast'; import { useUi } from '../stores/ui'; import { useTeam } from '../stores/team'
const toast = useToast(), ui = useUi(), teamStore = useTeam()
const tabs = [{ k: 'users', label: '账号' }, { k: 'teams', label: '小组' }, { k: 'system', label: '系统信息' }, { k: 'audit', label: '审计日志' }]
const tab = ref('users'), loading = ref(false), err = ref('')
// 注：后端 AdminUserDto 暂未含 status 字段；mock 返回该字段（超集），真实环境用本地操作记录兜底显示。
interface AdminUser { id: number; loginName: string; displayName: string; platformAdmin: boolean; status?: string; createdAt: string }
const localStatus = ref<Record<number, string>>({})
function statusOf(u: AdminUser) { return u.status ?? localStatus.value[u.id] ?? 'ACTIVE' }
function aiText(sys: SystemInfo) { const a = sys.ai; if (!a) return '—'; return a.status === 'CONFIGURED' ? `已配置（模式 ${a.mode}）` : a.status === 'MOCK' ? '演示模式（MOCK）' : '未配置' }
interface AdminTeam { id: number; name: string; code: string; memberCount: number; createdAt: string }
const users = ref<AdminUser[]>([]), teamsList = ref<AdminTeam[]>([]), auditLogs = ref<AuditLog[]>([])
const sys = ref<SystemInfo | null>(null), backup = ref<BackupStatus | null>(null)
const auditTotal = ref(0), auditPage = ref(1); const AUDIT_SIZE = 20
const enabledAdminCount = computed(() => users.value.filter(u => u.platformAdmin && statusOf(u) !== 'DISABLED').length)
const fmtSize = (b: number) => b >= 1024 * 1024 * 1024 ? (b / 1024 / 1024 / 1024).toFixed(2) + ' GB' : b >= 1024 * 1024 ? (b / 1024 / 1024).toFixed(1) + ' MB' : Math.ceil(b / 1024) + ' KB'
async function load() {
  loading.value = true; err.value = ''
  try {
    if (tab.value === 'users') users.value = await api.get('/admin/users')
    else if (tab.value === 'teams') teamsList.value = await api.get('/admin/teams')
    else if (tab.value === 'system') { sys.value = await api.get('/admin/system-info'); backup.value = await api.get('/admin/backup-status').catch(() => null) }
    else { const r = await api.get(`/admin/audit-logs?page=${auditPage.value - 1}&size=${AUDIT_SIZE}`); auditLogs.value = r.content ?? []; auditTotal.value = r.totalElements ?? 0 }
  } catch (e) { err.value = e instanceof ApiError ? e.message : '加载失败'; toast.show(err.value) } finally { loading.value = false }
}
onMounted(load)
async function grant(u: AdminUser) { try { await api.patch(`/admin/users/${u.id}/platform-admin`, { platformAdmin: true }); toast.show(`已授予 ${u.displayName} 平台管理员`); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '操作失败') } }
async function revoke(u: AdminUser) { if (!confirm(`确定撤销 ${u.displayName} 的平台管理员吗？`)) return; try { await api.patch(`/admin/users/${u.id}/platform-admin`, { platformAdmin: false }); toast.show('已撤销平台管理员'); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '撤销失败') } }
async function enable(u: AdminUser) { try { await api.patch(`/admin/users/${u.id}/status`, { status: 'ACTIVE' }); localStatus.value[u.id] = 'ACTIVE'; toast.show('账号已启用'); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '操作失败') } }
const disableUser = ref<AdminUser | null>(null), disableErr = ref(''), disabling = ref(false)
function openDisable(u: AdminUser) { disableUser.value = u; disableErr.value = '' }
async function confirmDisable() {
  if (!disableUser.value || disabling.value) return
  disabling.value = true; disableErr.value = ''
  try { await api.patch(`/admin/users/${disableUser.value.id}/status`, { status: 'DISABLED' }); localStatus.value[disableUser.value.id] = 'DISABLED'; toast.show('账号已停用'); disableUser.value = null; load() }
  catch (e) { disableErr.value = e instanceof ApiError ? e.message : '停用失败' } finally { disabling.value = false }
}
void teamStore
</script>
<style scoped>
.tabs{display:flex;gap:4px;margin-bottom:12px}
.tab{padding:8px 16px;border-radius:11px;font-weight:500;color:var(--text-2);min-height:40px}
.tab.on{background:var(--theme);color:var(--on-color);font-weight:600}
.body{padding:18px 20px}
.tbl{width:100%;border-collapse:collapse;font-size:13.5px}
.tbl th{text-align:left;color:var(--text-2);font-weight:600;padding:8px 10px;border-bottom:1px solid var(--field-brd);white-space:nowrap}
.tbl td{padding:8px 10px;border-bottom:1px solid var(--field-brd);vertical-align:top}
.nowrap{white-space:nowrap}
.err{color:var(--c-red);font-size:13px;margin-bottom:8px}
.pager{display:flex;gap:10px;align-items:center;margin-top:12px}
.acts{display:flex;gap:6px;flex-wrap:wrap}
.lan{display:inline-block;background:var(--sel);color:var(--primary-deep);border-radius:8px;padding:3px 10px;font-weight:600;margin-right:8px}
</style>
