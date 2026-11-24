<template>
<AppShell title="事项直达">
  <GlassPanel class="box">
    <p v-if="state === 'loading'" class="small">{{ loadingText }}</p>
    <template v-else-if="state === 'unavailable'">
      <h2>服务接口不可用</h2>
      <p class="small" style="margin-top:8px">服务器版本可能不匹配（错误 404），请稍后重试或联系管理员。</p>
      <div class="row"><AppButton @click="load">重试</AppButton><AppButton variant="ghost" @click="router.push('/workbench')">回工作台</AppButton></div>
    </template>
    <template v-else-if="state === 'forbidden'">
      <h2>没有权限</h2>
      <p class="small" style="margin-top:8px">你没有权限查看该事项。</p>
      <div class="row"><AppButton @click="router.push('/workbench')">回工作台</AppButton></div>
    </template>
    <template v-else-if="state === 'error'">
      <h2>打开失败</h2>
      <p class="small" style="margin-top:8px">{{ errorText }}</p>
      <div class="row"><AppButton @click="load">重试</AppButton><AppButton variant="ghost" @click="router.push('/workbench')">回工作台</AppButton></div>
    </template>
    <template v-else-if="state === 'notfound'">
      <h2>事项不存在</h2>
      <p class="small" style="margin-top:8px">这个事项可能已被彻底删除，或链接有误。</p>
      <div class="row"><AppButton @click="router.push('/workbench')">回工作台</AppButton></div>
    </template>
    <template v-else-if="state === 'deleted'">
      <h2>该事项已被删除</h2>
      <p class="small" style="margin-top:8px"><span v-if="res?.title">「{{ res.code }} {{ res.title }}」</span>已在回收站中，30 天内可恢复。</p>
      <div class="row">
        <AppButton v-if="res?.hasAccess" variant="ghost" @click="goTrash">去回收站</AppButton>
        <AppButton @click="router.push('/workbench')">回工作台</AppButton>
      </div>
    </template>
    <template v-else-if="state === 'cross'">
      <h2>切换小组查看？</h2>
      <p class="small" style="margin-top:8px">该事项属于「{{ res?.teamName }}」，是否切换过去查看？</p>
      <div class="row">
        <AppButton @click="confirmSwitch">切换</AppButton>
        <AppButton variant="ghost" @click="router.push('/workbench')">取消</AppButton>
      </div>
    </template>
    <template v-else-if="state === 'noaccess'">
      <h2>你没有权限查看该事项</h2>
      <p class="small" style="margin-top:8px">该事项属于「{{ res?.teamName }}」，你不是该小组成员。</p>
      <div class="row"><AppButton @click="router.push('/workbench')">回工作台</AppButton></div>
    </template>
  </GlassPanel>
</AppShell>
</template>
<script setup lang="ts">
// 修订六契约 4：统一直达地址 /i/:id 的落地页。先调 resolution 实时解析，再按类型定位：
// 任务 → /task/:id；故事 → 背景任务详情页 + 开抽屉；独立事项 → 当前迭代看板 / 待办池 + 开抽屉。
// 跨组先确认切换；已删除/无权限/不存在给出明确提示，绝不按错误类型渲染。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'
import { api, ApiError, redirectToLogin } from '../api/http'
import type { ItemResolution } from '../api/types'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const route = useRoute(), router = useRouter(), team = useTeam(), auth = useAuth(), toast = useToast()
const id = Number(route.params.id)
const state = ref<'loading' | 'notfound' | 'deleted' | 'cross' | 'noaccess' | 'unavailable' | 'forbidden' | 'error'>('loading')
const res = ref<ItemResolution | null>(null)
const loadingText = ref('正在打开事项…')
const errorText = ref('')
function passQuery(extra: Record<string, string> = {}) {
  const q: Record<string, string> = { ...extra }
  if (route.query.comment) q.comment = String(route.query.comment)
  if (route.query.progress) q.progress = String(route.query.progress)
  return q
}
function locate(r: ItemResolution) {
  if (r.type === 'TASK') { router.replace({ path: `/task/${r.id}`, query: passQuery() }); return }
  if (r.parentId != null) { router.replace({ path: `/task/${r.parentId}`, query: passQuery({ open: String(r.id) }) }); return }
  const onBoard = r.iterationId != null && team.iteration != null && r.iterationId === team.iteration.id
  router.replace({ path: onBoard ? '/board' : '/pool', query: passQuery({ open: String(r.id) }) })
}
async function goTrash() {
  const r = res.value; if (!r) return
  if (team.currentId !== r.teamId) { try { await team.switchTeam(r.teamId) } catch {} }
  router.push('/trash')
}
async function confirmSwitch() {
  const r = res.value; if (!r) return
  try { await team.switchTeam(r.teamId) } catch {}
  if (!r.hasAccess && !auth.user?.platformAdmin) { state.value = 'noaccess'; return }
  locate(r)
}
async function load() {
  state.value = 'loading'; loadingText.value = '正在打开事项…'
  if (!team.teams.length) { try { await team.loadTeams() } catch {} }
  let r: ItemResolution
  try { r = await api.get(`/items/${id}/resolution`) }
  catch (e) {
    if (e instanceof ApiError) {
      if (e.code === 'CANCELED') { return }
      if (e.status === 401) { loadingText.value = '正在跳转登录…'; redirectToLogin(); return }
      if (e.status === 404 && e.code === 'ITEM_NOT_FOUND') { state.value = 'notfound'; return }
      if (e.status === 404) { state.value = 'unavailable'; return }
      if (e.status === 403) { state.value = 'forbidden'; return }
      if (e.status === 0 && e.code === 'NETWORK') { errorText.value = '网络连接失败，请检查网络后重试'; state.value = 'error'; return }
      errorText.value = `打开失败（错误码 ${e.status}），请稍后重试或联系管理员`; state.value = 'error'; return
    }
    errorText.value = '打开失败，请稍后重试或联系管理员'; state.value = 'error'; return
  }
  res.value = r
  if (r.deleted) { state.value = 'deleted'; return }
  if (r.teamId !== team.currentId) { state.value = 'cross'; return }
  if (!r.hasAccess && !auth.user?.platformAdmin) { state.value = 'noaccess'; return }
  locate(r)
}
onMounted(load)
</script>
<style scoped>
.box{padding:22px;max-width:560px}
.box h2{font-size:17px}
.row{display:flex;gap:10px;margin-top:16px}
</style>
