<template>
<div class="hp">
  <p v-if="loading" class="small">正在盘点待交接事项…</p>
  <p v-else-if="loadErr" class="err">{{loadErr}}</p>
  <template v-else>
    <p class="small">离组前必须处理完以下条目：逐条选择「转交给某人」或「退回认领池」（验收事项退回池后改由组长验收；其发出批次里的事项按条处理，批次改由接收人发出）。共同负责的事项会自动去掉此人，不阻塞、无需处理。全部处理完后才能确认移除/退出。</p>
    <template v-if="groups.OWNED.length">
      <h4>未完成事项（{{groups.OWNED.length}}）</h4>
      <div v-for="it in groups.OWNED" :key="it.itemId" class="row">
        <span class="grow"><b>{{it.code}}</b> {{it.title}}<span class="tiny"> · {{STATUS_META[it.status as ItemStatus]?.label ?? it.status}}</span></span>
        <select v-model="dec[it.itemId].action" class="flexin sm"><option value="TRANSFER">转交</option><option value="RETURN_TO_POOL">退回认领池</option></select>
        <select v-if="dec[it.itemId].action==='TRANSFER'" v-model.number="dec[it.itemId].toUserId" class="flexin sm"><option :value="0" disabled>选择接收人</option><option v-for="m in targets" :key="m.userId" :value="m.userId">{{memberLabel(team.members, m)}}</option></select>
      </div>
    </template>
    <template v-if="groups.ACCEPTOR.length">
      <h4>待其验收的事项（{{groups.ACCEPTOR.length}}）</h4>
      <div v-for="it in groups.ACCEPTOR" :key="it.itemId" class="row">
        <span class="grow"><b>{{it.code}}</b> {{it.title}}</span>
        <select v-model="dec[it.itemId].action" class="flexin sm"><option value="TRANSFER">转交验收</option><option value="RETURN_TO_POOL">改由组长验收</option></select>
        <select v-if="dec[it.itemId].action==='TRANSFER'" v-model.number="dec[it.itemId].toUserId" class="flexin sm"><option :value="0" disabled>选择接收人</option><option v-for="m in targets" :key="m.userId" :value="m.userId">{{memberLabel(team.members, m)}}</option></select>
      </div>
    </template>
    <template v-if="groups.BATCH.length">
      <h4>其发出的「每人一份」批次内事项（{{groups.BATCH.length}}）</h4>
      <div v-for="it in groups.BATCH" :key="it.itemId" class="row">
        <span class="grow"><b>{{it.code}}</b> {{it.title}}<span class="tiny"> · 批次 #{{it.batchId}}</span></span>
        <select v-model="dec[it.itemId].action" class="flexin sm"><option value="TRANSFER">转交</option><option value="RETURN_TO_POOL">退回认领池</option></select>
        <select v-if="dec[it.itemId].action==='TRANSFER'" v-model.number="dec[it.itemId].toUserId" class="flexin sm"><option :value="0" disabled>选择接收人</option><option v-for="m in targets" :key="m.userId" :value="m.userId">{{memberLabel(team.members, m)}}</option></select>
      </div>
    </template>
    <p v-if="!pending.length" class="small" style="color:var(--success-text)">没有待交接条目，可以直接确认。</p>
    <p v-if="err" class="err">{{err}}</p>
    <div class="foot">
      <AppButton variant="ghost" @click="$emit('cancel')">取消</AppButton>
      <AppButton v-if="pending.length" variant="ghost" :disabled="applying" @click="returnAll">一键全部退回认领池</AppButton>
      <AppButton v-if="pending.length" :disabled="!allDecided || applying" @click="apply">{{ applying ? '交接中…' : '执行交接' }}</AppButton>
      <AppButton v-else variant="danger" :disabled="confirming" @click="$emit('confirm')">{{confirmLabel}}</AppButton>
    </div>
    <p v-if="pending.length && !allDecided" class="tiny">还有条目未选接收人，处理完后「执行交接」才会可用；交接完成后才能{{confirmLabel}}。</p>
  </template>
</div>
</template>
<script setup lang="ts">
// 离组交接面板（修订四 P05）：移除成员 / 退出小组共用（停用账号由后端逐组校验拦截，前端不预检）。
// 契约（与后端 HandoverService/TeamController 一致）：
//   GET  /teams/{tid}/members/{uid}/handover-pending → [{itemId, code, title, kind: OWNED|ACCEPTOR|BATCH, status, batchId}]
//   POST /teams/{tid}/members/{uid}/handover {actions:[{itemId, action: TRANSFER|RETURN_TO_POOL, targetUserId}]} → {remaining}
//   POST /teams/{tid}/members/{uid}/handover/return-all → {remaining:0}
// pending 清空后 emit confirm，由父级执行移除（DELETE member）/退出（POST leave）。
import { ref, reactive, computed, watch } from 'vue'
import AppButton from './AppButton.vue'
import { api, ApiError } from '../api/http'
import { STATUS_META, type HandoverPendingEntry, type ItemStatus } from '../api/types'
import { memberLabel } from '../utils/memberLabel'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const props = defineProps<{ teamId: number; userId: number; confirmLabel: string }>()
const emit = defineEmits(['cancel', 'confirm', 'done'])
const team = useTeam(), toast = useToast()
const pending = ref<HandoverPendingEntry[]>([]), loading = ref(false), loadErr = ref(''), err = ref(''), applying = ref(false), confirming = ref(false)
const dec = reactive<Record<number, { action: string; toUserId: number }>>({})
const targets = computed(() => team.members.filter(m => m.userId !== props.userId))
const groups = computed(() => ({ OWNED: pending.value.filter(e => e.kind === 'OWNED'), ACCEPTOR: pending.value.filter(e => e.kind === 'ACCEPTOR'), BATCH: pending.value.filter(e => e.kind === 'BATCH') }))
const allDecided = computed(() => pending.value.every(e => { const d = dec[e.itemId]; return d && (d.action === 'RETURN_TO_POOL' || !!d.toUserId) }))
async function load() {
  loading.value = true; loadErr.value = ''
  try {
    pending.value = await api.get(`/teams/${props.teamId}/members/${props.userId}/handover-pending`)
    for (const e of pending.value) dec[e.itemId] ??= { action: 'RETURN_TO_POOL', toUserId: 0 }
  } catch (e) { loadErr.value = e instanceof ApiError ? e.message : '交接清单加载失败' } finally { loading.value = false }
}
watch(() => [props.teamId, props.userId], load, { immediate: true })
async function apply() {
  if (applying.value) return
  applying.value = true; err.value = ''
  try {
    const r: { remaining: number } = await api.post(`/teams/${props.teamId}/members/${props.userId}/handover`, {
      actions: pending.value.map(e => ({ itemId: e.itemId, action: dec[e.itemId].action, targetUserId: dec[e.itemId].action === 'TRANSFER' ? dec[e.itemId].toUserId : undefined })),
    })
    toast.show('交接已执行')
    emit('done')
    if (r.remaining > 0) await load(); else pending.value = []
  } catch (e) { err.value = e instanceof ApiError ? e.message : '交接失败' } finally { applying.value = false }
}
async function returnAll() {
  if (applying.value) return
  applying.value = true; err.value = ''
  try {
    await api.post(`/teams/${props.teamId}/members/${props.userId}/handover/return-all`)
    pending.value = []
    toast.show('已全部退回认领池（验收事项改由组长验收）')
    emit('done')
  } catch (e) { err.value = e instanceof ApiError ? e.message : '操作失败' } finally { applying.value = false }
}
</script>
<style scoped>
.hp h4{font-size:13.5px;margin:14px 0 4px}
.row{display:flex;gap:8px;align-items:center;padding:7px 0;border-bottom:1px solid var(--field-brd);flex-wrap:wrap}
.row .grow{flex:1;min-width:180px;font-size:13.5px}
.flexin{min-height:36px;border:1px solid var(--field-brd);border-radius:9px;background:var(--field);padding:4px 8px}
.flexin.sm{flex:0 1 170px}
.foot{display:flex;gap:10px;justify-content:flex-end;margin-top:16px;flex-wrap:wrap}
.err{color:var(--c-red);font-size:12.5px;margin-top:8px}
</style>
