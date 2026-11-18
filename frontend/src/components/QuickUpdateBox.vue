<template>
  <GlassPanel class="qu">
    <div class="qhead"><Sparkles :size="16" class="indigo"/><b>一句话更新</b><span class="tiny">说一句你负责事项的进展，AI 帮你对上号</span></div>
    <template v-if="ai.featureOn('quickUpdate')">
      <div class="qrow">
        <input v-model="text" class="flexin grow" placeholder="如：登录页切图做到 80% 了 / 导出分页修复完成了" @keyup.enter="submit"/>
        <AppButton :disabled="busy.is('parse') || !text.trim()" @click="submit">{{ busy.is('parse') ? '解析中…' : '解析' }}</AppButton>
      </div>
      <div v-for="(a,i) in actions" :key="i" class="qcard">
        <template v-if="a.kind==='UPDATE_ITEM' && a.candidateItemIds?.length">
          <p class="small">这句话可能说的是这几个事项，点选一个：</p>
          <div class="qrow">
            <button v-for="cid in a.candidateItemIds" :key="cid" class="chip press" :class="{on:picked[i]===cid}" @click="picked[i]=cid">{{ itemLabel(cid) }}</button>
          </div>
          <div class="qrow"><AppButton variant="ghost" :disabled="!picked[i]" @click="confirmCandidate(i)">按所选生成更新卡</AppButton><button class="mini press" @click="actions.splice(i,1)">删除此卡</button></div>
        </template>
        <template v-else-if="a.kind==='UPDATE_ITEM'">
          <div class="crow"><b>{{a.itemCode}} {{ itemIndex[a.itemId ?? 0]?.title ?? '' }}</b><span style="flex:1"/><button class="mini press" @click="actions.splice(i,1)">删除此卡</button></div>
          <p class="small">进度：<input type="number" min="0" max="100" class="numin" v-model.number="a.percent"/>%</p>
          <input v-model="a.text" class="flexin grow" style="width:100%;margin-top:6px" placeholder="进展文字" aria-label="进展文字"/>
          <label class="small chk"><input type="checkbox" v-model="a.submitReview"/> 同时提交验收</label>
          <label v-if="a.needsBlockConfirm" class="small chk"><input type="checkbox" v-model="a.blocked"/> 这句话暗示有阻塞，是否标记阻塞？</label>
          <input v-if="a.blocked" v-model="a.blockReason" class="flexin grow" style="width:100%;margin-top:6px" placeholder="阻塞原因" aria-label="阻塞原因"/>
        </template>
        <template v-else-if="a.kind==='CREATE_ITEM'">
          <div class="crow"><b>新建事项</b><span style="flex:1"/><button class="mini press" @click="actions.splice(i,1)">删除此卡</button></div>
          <div class="qrow" style="margin-top:6px">
            <input v-model="a.title" class="flexin grow" aria-label="新事项标题"/>
            <select v-model="a.type" class="flexin" aria-label="事项类型"><option value="STORY">故事</option><option value="DEFECT">缺陷</option><option value="ANALYSIS">分析</option><option value="ADHOC">临时事项</option></select>
            <input type="date" v-model="a.dueDate" class="flexin" aria-label="截止日期"/>
          </div>
        </template>
      </div>
      <div v-for="(u,i) in unmatched" :key="'u'+i" class="qcard">
        <p class="small">无主片段：「{{u}}」——没有匹配到事项，可删除或改成新建。</p>
        <div class="qrow"><AppButton variant="ghost" @click="toCreate(u, i)">改为新建事项</AppButton><button class="mini press" @click="unmatched.splice(i,1)">删除</button></div>
      </div>
      <div v-if="actions.length" class="qrow" style="margin-top:8px">
        <span class="tiny">逐卡可改/删；点「全部确认」一次性生效。</span><span style="flex:1"/>
        <AppButton :disabled="busy.is('apply')" @click="applyAll">{{ busy.is('apply') ? '提交中…' : '全部确认' }}</AppButton>
      </div>
    </template>
    <p v-else class="small una">AI 暂不可用{{ ai.unavailableReason ? `（${ai.unavailableReason}）` : '' }}，可手工在事项抽屉里点「登记进展」更新。</p>
  </GlassPanel>
</template>
<script setup lang="ts">
// 契约 0.5.0：一句话更新解析返回 {actions: QuickAction[], unmatched: string[]}；确认走 apply 发 {actions}（带 Idempotency-Key），返回 {results:[{kind,itemId,ok}]}。
import { ref, reactive } from 'vue'
import { Sparkles } from 'lucide-vue-next'
import GlassPanel from './GlassPanel.vue'; import AppButton from './AppButton.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'; import type { QuickAction } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'; import { useAi } from '../stores/ai'
import { useBusy } from '../utils/busy'
const emit = defineEmits<{ changed: [] }>()
const team = useTeam(), toast = useToast(), ai = useAi(), busy = useBusy()
const text = ref(''), actions = ref<QuickAction[]>([]), unmatched = ref<string[]>([]), picked = reactive<Record<number, number>>({})
const itemIndex = ref<Record<number, { code: string; title: string }>>({})
let applyKey = newIdempotencyKey()
function itemLabel(id: number){ const it = itemIndex.value[id]; return it ? `${it.code} ${it.title}` : `#${id}` }
async function ensureIndex(){ if (Object.keys(itemIndex.value).length) return
  const items = await api.get(`/teams/${team.currentId}/items`).catch(() => [])
  for (const it of items ?? []) itemIndex.value[it.id] = { code: it.code, title: it.title } }
async function submit(){ if (!text.value.trim()) return
  await busy.wrap('parse', async () => { try { const r = await api.post(`/teams/${team.currentId}/ai/quick-update`, { text: text.value.trim() }); actions.value = r.actions ?? []; unmatched.value = r.unmatched ?? []; applyKey = newIdempotencyKey(); if (actions.value.some(a => a.candidateItemIds?.length)) await ensureIndex() } catch(e){ toast.show(e instanceof ApiError ? e.message : '解析失败') } }) }
function confirmCandidate(i: number){ const a = actions.value[i]; const id = picked[i]; if (!a || !id) return
  a.itemId = id; a.itemCode = itemIndex.value[id]?.code ?? a.itemCode; a.candidateItemIds = [] }
function toCreate(u: string, i: number){ unmatched.value.splice(i, 1); actions.value.push({ kind: 'CREATE_ITEM', itemId: null, itemCode: null, text: null, percent: null, submitReview: false, blocked: false, blockReason: null, needsBlockConfirm: false, candidateItemIds: [], type: 'ADHOC', title: u.slice(0, 60), ownerName: null, ownerId: null, dueDate: null }) }
async function applyAll(){ await busy.wrap('apply', async () => {
  const send = actions.value.filter(a => a.kind === 'CREATE_ITEM' || (a.kind === 'UPDATE_ITEM' && a.itemId && !a.candidateItemIds?.length))
    .map(a => ({ kind: a.kind, itemId: a.itemId ?? null, itemCode: a.itemCode ?? null, text: a.text ?? null, percent: a.percent ?? null, submitReview: !!a.submitReview, blocked: !!a.blocked, blockReason: a.blocked ? (a.blockReason ?? null) : null, needsBlockConfirm: false, candidateItemIds: [] as number[], type: a.type ?? null, title: a.title ?? null, ownerName: a.ownerName ?? null, ownerId: a.ownerId ?? null, dueDate: a.dueDate ?? null }))
  if (!send.length) { toast.show('没有可确认的卡片（候选卡请先点选事项）'); return }
  try { const r = await api.postKey(`/teams/${team.currentId}/ai/quick-update/apply`, { actions: send }, applyKey); const ok = (r.results ?? []).filter((x: any) => x.ok).length; toast.show(`已生效 ${ok} 项`); actions.value = []; unmatched.value = []; text.value = ''; emit('changed') } catch(e){ toast.show(e instanceof ApiError ? e.message : '提交失败') } }) }
</script>
<style scoped>
.qu{padding:14px 16px;margin-bottom:14px}
.qhead{display:flex;gap:8px;align-items:center;margin-bottom:10px}.indigo{color:#5856D6}
.qrow{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.grow{flex:1}
.flexin{min-height:40px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px}
.qcard{border:1px solid rgba(88,86,214,.3);background:rgba(88,86,214,.06);border-radius:12px;padding:10px 12px;margin-top:8px}
.crow{display:flex;gap:8px;align-items:center}
.mini{font-size:12px;color:var(--text-2);font-weight:600}
.numin{width:64px;min-height:30px;border:1px solid var(--field-brd);border-radius:8px;background:var(--field);padding:2px 6px}
.chip{border:1px solid var(--field-brd);background:var(--field);border-radius:999px;padding:5px 11px;font-size:12.5px;font-weight:500}
.chip.on{border-color:#5856D6;background:rgba(88,86,214,.14);color:#3730A3}
.chk{display:inline-flex;gap:6px;align-items:center;margin-top:6px;margin-right:14px}
.una{color:var(--text-2)}
</style>
