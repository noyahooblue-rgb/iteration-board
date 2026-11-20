<template>
<AppShell title="智能问答" @refresh="loadHistory">
  <GlassPanel class="panel">
    <div class="qhead"><AiSpark :size="17"/><b>问问小组的事</b><span class="tiny">回答基于结构化数据检索，引用可点开事项</span></div>
    <template v-if="ai.featureOn('qa')">
      <div class="qrow">
        <input v-model="question" class="flexin grow" placeholder="如：登录页视觉还原做到哪了？谁在负责导出？" @keyup.enter="ask"/>
        <AppButton :disabled="busy.is('ask') || !question.trim()" @click="ask">{{ busy.is('ask') ? '查询中…' : '提问' }}</AppButton>
      </div>
      <div v-if="answer" class="ans">
        <AiBadge :ai-generated="true"/>
        <p v-if="!answer.found" class="notfound">查不到——没有与该问题相关的事项记录。</p>
        <p v-else class="atext">{{answer.answer}}</p>
        <div class="cites">
          <button v-for="code in answer.refs" :key="code" class="cite press" @click="openRef(code)">{{code}}</button>
        </div>
      </div>
    </template>
    <p v-else class="small">AI 暂不可用{{ ai.unavailableReason ? `（${ai.unavailableReason}）` : '' }}，可手工用「搜索」页按关键词查事项。</p>
  </GlassPanel>
  <GlassPanel class="panel">
    <h3>我的提问历史</h3>
    <div v-for="h in history" :key="h.id" class="hrow">
      <span class="grow"><b>{{h.question}}</b><br/><span class="small">{{ h.found ? h.answer : '查不到' }}</span><br/><button v-for="code in h.refs" :key="code" class="cite press" @click="openRef(code)">{{code}}</button></span>
      <span class="tiny">{{fmt(h.createdAt)}}</span>
      <AppButton variant="ghost" :disabled="busy.is('del'+h.id)" @click="remove(h)">删除</AppButton>
    </div>
    <p v-if="!history.length" class="small">暂无历史。</p>
  </GlassPanel>
  <ItemDrawer :open="drawerOpen" :item-id="openId" @close="drawerOpen=false" @open-item="(id:number)=>{openId=id}"/>
</AppShell>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AiSpark from '../components/AiSpark.vue'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import ItemDrawer from '../components/ItemDrawer.vue'; import AiBadge from '../components/AiBadge.vue'
import { api, ApiError } from '../api/http'; import type { QaRecord, QaAnswer } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'; import { useAi } from '../stores/ai'
import { useBusy } from '../utils/busy'
import { fmtDateTime } from '../utils/datetime'
const team = useTeam(), toast = useToast(), ai = useAi(), busy = useBusy()
const question = ref(''), answer = ref<QaAnswer | null>(null), history = ref<QaRecord[]>([])
const drawerOpen = ref(false), openId = ref<number | null>(null)
const fmt = fmtDateTime
async function loadHistory(){ history.value = await api.get(`/teams/${team.currentId}/ai/qa/history`).catch(() => []) }
onMounted(loadHistory)
async function ask(){ await busy.wrap('ask', async () => { try { answer.value = await api.post(`/teams/${team.currentId}/ai/qa`, { question: question.value.trim() }); loadHistory() } catch(e){ toast.show(e instanceof ApiError ? e.message : '提问失败') } }) }
// 契约 refs 只有事项编号字符串，点击时按 code 在全量事项中解析 itemId 再开抽屉。
let codeIndex: Record<string, number> | null = null
async function openRef(code: string){ try { if (!codeIndex) { const all: any[] = await api.get(`/teams/${team.currentId}/items?scope=all`); codeIndex = {}; for (const it of all) codeIndex[it.code] = it.id }
  const id = codeIndex[code]; if (id == null) { toast.show(`未找到编号 ${code} 对应的事项`); return } openId.value = id; drawerOpen.value = true } catch(e){ toast.show(e instanceof ApiError ? e.message : '打开事项失败') } }
async function remove(h: QaRecord){ await busy.wrap('del' + h.id, async () => { try { await api.del(`/teams/${team.currentId}/ai/qa/history/${h.id}`); history.value = history.value.filter(x => x.id !== h.id); if (answer.value?.recordId === h.id) answer.value = null } catch(e){ toast.show(e instanceof ApiError ? e.message : '删除失败') } }) }
</script>
<style scoped>
.panel{padding:16px 18px;margin-bottom:14px}
.qhead{display:flex;gap:8px;align-items:center;margin-bottom:10px}.indigo{color:var(--c-indigo)}
.qrow{display:flex;gap:8px;align-items:center}.grow{flex:1}
.flexin{min-height:40px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px}
.ans{margin-top:12px;border:1px solid var(--indigo-brd-soft);background:var(--indigo-bg-soft);border-radius:12px;padding:12px}
.atext{margin:8px 0;font-size:14.5px;line-height:1.6}
.notfound{margin:8px 0;font-weight:700;color:var(--c-red)}
.cites{display:flex;gap:8px;flex-wrap:wrap}
.cite{font-size:12.5px;font-weight:600;color:var(--indigo-deep);background:var(--indigo-bg);border-radius:999px;padding:4px 11px}
.hrow{display:flex;gap:10px;align-items:flex-start;padding:9px 0;border-bottom:1px solid var(--field-brd)}
</style>
