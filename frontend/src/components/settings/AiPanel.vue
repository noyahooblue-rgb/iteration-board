<template>
  <h2>AI 设置</h2>
  <p v-if="status && !status.platformEnabled" class="small warnbox">平台已关闭 AI，本组设置暂不可用；各 AI 入口会显示「AI 暂不可用」并给出手工路径。</p>
  <template v-else>
    <label v-for="(label,k) in AI_FEATURE_LABELS" :key="k" class="srow"><span class="grow">{{label}}</span>
      <input type="checkbox" :checked="settingOn(k)" :disabled="!isLeader || busy.is('save')" @change="toggleFeature(k)"/></label>
    <p class="tiny">{{ isLeader ? '开关修改后立即生效（契约 TeamAiSettings：逐项开关，无小组总开关）。' : '仅组长可修改 AI 设置。' }}</p>
  </template>
  <template v-if="isLeader">
    <h3 style="margin-top:18px">调用日志（最近）</h3>
    <div v-for="l in logs" :key="l.id" class="lrow"><span class="small grow">{{ AI_FEATURE_LABELS[l.feature] ?? l.feature }}</span><span class="tiny">{{l.model ?? '—'}}</span><span class="tiny">{{l.durationMs}} ms</span><span class="tiny" :style="l.success ? '' : 'color:var(--c-red)'">{{ l.success ? '成功' : `失败${l.error ? '：' + l.error : ''}` }}</span><span class="tiny">{{fmt(l.createdAt)}}</span></div>
    <p v-if="!logs.length" class="tiny">暂无调用记录。</p>
  </template>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api, ApiError } from '../../api/http'; import { AI_FEATURE_LABELS, DEFAULT_TEAM_AI_SETTINGS, type AiStatus, type AiLog, type TeamAiSettings } from '../../api/types'
import { useTeam } from '../../stores/team'; import { useToast } from '../../stores/toast'; import { useAi } from '../../stores/ai'
import { useBusy } from '../../utils/busy'
const team = useTeam(), toast = useToast(), aiStore = useAi(), busy = useBusy()
const status = ref<AiStatus | null>(null), settings = ref<TeamAiSettings>({ ...DEFAULT_TEAM_AI_SETTINGS }), logs = ref<AiLog[]>([])
const isLeader = computed(() => team.current?.myRole === 'LEADER')
const fmt = (s: string) => s ? s.slice(0, 16).replace('T', ' ') : ''
async function load(){ status.value = await api.get(`/teams/${team.currentId}/ai/status`).catch(() => null)
  if (status.value) settings.value = { ...status.value.teamSettings }
  if (isLeader.value) logs.value = await api.get(`/teams/${team.currentId}/ai/logs`).catch(() => []) }
onMounted(load)
// 契约 0.5.0：PUT ai/settings 请求/响应均为扁平 TeamAiSettings（功能布尔字段），不再发 {enabled, features}。
async function save(){ await busy.wrap('save', async () => { try { const saved: TeamAiSettings = await api.put(`/teams/${team.currentId}/ai/settings`, { ...settings.value }); settings.value = { ...saved }; if (status.value) status.value.teamSettings = { ...saved }; toast.show('AI 设置已保存'); aiStore.refresh(team.currentId) } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败'); load() } }) }
function settingOn(k: string){ return settings.value[k as keyof TeamAiSettings] !== false }
function toggleFeature(k: string){ const key = k as keyof TeamAiSettings; settings.value = { ...settings.value, [key]: settings.value[key] === false }; save() }
</script>
<style scoped>
.srow{display:flex;gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--field-brd)}.grow{flex:1}
.srow input{width:19px;height:19px}
.warnbox{background:rgba(255,204,0,.18);border:1px solid rgba(183,121,31,.4);border-radius:12px;padding:9px 13px}
.lrow{display:flex;gap:12px;align-items:center;padding:6px 0;border-bottom:1px solid var(--field-brd)}
</style>
