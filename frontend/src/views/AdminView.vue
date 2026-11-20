<template>
<AppShell title="平台管理">
  <div class="tabs">
    <button v-for="t in tabs" :key="t.k" class="tab press" :class="{ on: tab === t.k }" @click="tab = t.k; load()">{{ t.label }}</button>
  </div>
  <GlassPanel class="body">
    <p v-if="err" class="err">{{ err }}</p>
    <p v-if="loading" class="small">加载中…</p>
    <!-- 账号：GET /admin/users（1.0.4 后端新增）字段 [{id,loginName,displayName,platformAdmin,createdAt}] -->
    <table v-if="tab === 'users'" class="tbl">
      <thead><tr><th>账号</th><th>登录名</th><th>平台角色</th><th>注册时间</th></tr></thead>
      <tbody>
        <tr v-for="u in users" :key="u.id"><td>{{ u.displayName }}</td><td>@{{ u.loginName }}</td><td>{{ u.platformAdmin ? '平台管理员' : '普通用户' }}</td><td class="nowrap">{{ fmtDateTime(u.createdAt) }}</td></tr>
        <tr v-if="!users.length && !loading"><td colspan="4" class="small">暂无账号数据。</td></tr>
      </tbody>
    </table>
    <!-- 小组：GET /admin/teams（1.0.4 后端新增）字段 [{id,name,code,memberCount,createdAt}] -->
    <table v-else-if="tab === 'teams'" class="tbl">
      <thead><tr><th>小组</th><th>代号</th><th>成员数</th><th>创建时间</th></tr></thead>
      <tbody>
        <tr v-for="t in teamsList" :key="t.id"><td>{{ t.name }}</td><td>{{ t.code }}</td><td>{{ t.memberCount }}</td><td class="nowrap">{{ fmtDateTime(t.createdAt) }}</td></tr>
        <tr v-if="!teamsList.length && !loading"><td colspan="4" class="small">暂无小组数据。</td></tr>
      </tbody>
    </table>
    <!-- 审计日志：复用 GET /admin/audit-logs（既有实现），写法参照 TeamSettingsView 审计表格，简化版 -->
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
</AppShell>
</template>
<script setup lang="ts">
// 平台管理（1.0.4 I）：仅平台管理员可见（侧栏入口按 platformAdmin 渲染，路由 /admin 有守卫）。
import { ref, onMounted } from 'vue'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'
import { api, ApiError } from '../api/http'
import { fmtDateTime } from '../utils/datetime'
import { auditActionLabel, auditTargetLabel, type AuditLog } from '../api/types'
import { useToast } from '../stores/toast'
const toast = useToast()
const tabs = [{ k: 'users', label: '账号' }, { k: 'teams', label: '小组' }, { k: 'audit', label: '审计日志' }]
const tab = ref('users'), loading = ref(false), err = ref('')
interface AdminUser { id: number; loginName: string; displayName: string; platformAdmin: boolean; createdAt: string }
interface AdminTeam { id: number; name: string; code: string; memberCount: number; createdAt: string }
const users = ref<AdminUser[]>([]), teamsList = ref<AdminTeam[]>([]), auditLogs = ref<AuditLog[]>([])
const auditTotal = ref(0), auditPage = ref(1); const AUDIT_SIZE = 20
async function load() {
  loading.value = true; err.value = ''
  try {
    if (tab.value === 'users') users.value = await api.get('/admin/users')
    else if (tab.value === 'teams') teamsList.value = await api.get('/admin/teams')
    else { const r = await api.get(`/admin/audit-logs?page=${auditPage.value - 1}&size=${AUDIT_SIZE}`); auditLogs.value = r.content ?? []; auditTotal.value = r.totalElements ?? 0 }
  } catch (e) { err.value = e instanceof ApiError ? e.message : '加载失败'; toast.show(err.value) } finally { loading.value = false }
}
onMounted(load)
</script>
<style scoped>
.tabs{display:flex;gap:4px;margin-bottom:12px}
.tab{padding:8px 16px;border-radius:11px;font-weight:500;color:var(--text-2);min-height:40px}
.tab.on{background:var(--theme);color:#fff;font-weight:600}
.body{padding:18px 20px}
.tbl{width:100%;border-collapse:collapse;font-size:13.5px}
.tbl th{text-align:left;color:var(--text-2);font-weight:600;padding:8px 10px;border-bottom:1px solid var(--field-brd);white-space:nowrap}
.tbl td{padding:8px 10px;border-bottom:1px solid var(--field-brd);vertical-align:top}
.nowrap{white-space:nowrap}
.err{color:var(--c-red);font-size:13px;margin-bottom:8px}
.pager{display:flex;gap:10px;align-items:center;margin-top:12px}
</style>
