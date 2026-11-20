<template>
  <Modal :open="open" @close="emit('close')">
    <h2>导入向导</h2>
    <div class="tabs">
      <button class="tchip press" :class="{on:mode==='paste'}" @click="mode='paste'"><AiSpark :size="13"/>粘贴导入</button>
      <button class="tchip press" :class="{on:mode==='file'}" @click="mode='file'">Excel / CSV 列映射</button>
    </div>
    <template v-if="step==='input'">
      <template v-if="mode==='paste'">
        <template v-if="ai.featureOn('pasteImport')">
          <textarea v-model="pasteText" rows="6" class="bulk" placeholder="每行一条，如：导出接口联调 3点 2026-11-20&#10;标题与已有事项相同时会标为更新"/>
          <div v-if="ai.status?.visionAvailable" class="imgrow">
            <label class="small">粘贴截图（取第一张识别）：<input type="file" accept="image/*" @change="onImage"/></label>
            <span class="tiny" v-if="imageName">已选 {{imageName}}</span>
          </div>
          <div class="imgrow">
            <label class="small">或选 Excel / CSV 文件解析：<input type="file" accept=".csv,.xlsx,text/csv" @change="onPasteFile"/></label>
          </div>
          <div class="foot"><AppButton variant="ghost" @click="emit('close')">取消</AppButton><AppButton :disabled="busy.is('parse') || (!pasteText.trim() && !imageDataUrl)" @click="parsePaste">{{ busy.is('parse') ? '解析中…' : '解析预览' }}</AppButton></div>
        </template>
        <p v-else class="small">AI 暂不可用{{ ai.unavailableReason ? `（${ai.unavailableReason}）` : '' }}，可手工逐条新建，或用下方「粘贴批量新建」类入口手工导入。</p>
      </template>
      <template v-else>
        <label class="small">选择 .csv / .xlsx 文件：<input type="file" accept=".csv,.xlsx,text/csv" @change="onFile"/></label>
        <p v-if="parseErr" class="small" style="color:var(--c-red)">{{parseErr}}</p>
        <template v-if="parsed">
          <p class="small" style="margin-top:8px">共 {{parsed.rows.length}} 行数据。给每个平台字段选择一列：</p>
          <div class="maprow" v-for="f in mapFields" :key="f.key">
            <span class="small grow">{{f.label}}</span>
            <select v-model="mapping[f.key]" class="flexin"><option value="">（不导入）</option><option v-for="(h,hi) in parsed.headers" :key="hi" :value="hi">{{h}}</option></select>
          </div>
          <p class="tiny">样例：{{ parsed.rows[0]?.join(' ｜ ') }}</p>
          <div class="foot"><AppButton variant="ghost" @click="emit('close')">取消</AppButton><AppButton :disabled="mapping.title==='' || mapping.title===undefined" @click="buildFileRows">生成预览</AppButton></div>
        </template>
      </template>
    </template>
    <template v-else>
      <p class="small">预览（可编辑；黄色 = AI 把握低的字段；更新行展开看差异）：</p>
      <div class="prow" v-for="(r,i) in rows" :key="i" :class="{low: r.confidence < 0.7}">
        <input type="checkbox" v-model="r.selected"/>
        <span class="act" :class="r.matchStatus">{{ r.matchStatus==='NEW' ? '新增' : r.matchStatus==='UPDATE' ? '更新' : '不变' }}</span>
        <input v-model="r.title" class="flexin grow" :class="{warn: r.confidence < 0.7}" aria-label="标题"/>
        <select v-model="r.priority" class="flexin num" aria-label="优先级"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select>
        <input type="date" v-model="r.dueDate" class="flexin num2" aria-label="截止日期"/>
        <span v-if="r.estimate != null" class="tiny">估算 {{r.estimate}}</span>
        <span v-if="r.matchStatus==='UPDATE'" class="tiny diff">{{ Object.entries(r.diff).map(([f,d])=>`${f}: ${d.from ?? '—'} → ${d.to ?? '—'}`).join('；') || '字段无变化' }}</span>
      </div>
      <div class="foot"><AppButton variant="ghost" @click="step='input'">返回</AppButton><AppButton :disabled="busy.is('confirm') || !rows.some(r=>r.selected)" @click="confirmImport">{{ busy.is('confirm') ? '导入中…' : `确认导入 ${rows.filter(r=>r.selected).length} 行` }}</AppButton></div>
    </template>
    <div class="hist">
      <h4>导入历史（24 小时内可整批撤销）</h4>
      <div v-for="b in history" :key="b.id" class="hrow">
        <span class="small">#{{b.id}} {{ b.source==='FILE_MAPPING' ? '文件列映射' : b.source==='ADAPTER' ? '对接同步' : '粘贴导入' }} · 新建 {{b.createdCount}} / 更新 {{b.updatedCount}} · {{fmt(b.createdAt)}}</span><span style="flex:1"/>
        <span v-if="b.undoneAt" class="tiny">已撤销</span>
        <AppButton v-else-if="canUndo(b)" variant="ghost" :disabled="busy.is('undo'+b.id)" @click="undo(b)">整批撤销</AppButton>
      </div>
      <p v-if="!history.length" class="tiny">暂无导入记录。</p>
    </div>
  </Modal>
</template>
<script setup lang="ts">
// 导入向导（契约 0.5.0）：粘贴路径走 POST ai/paste-import 解析（文本 / imageBase64 图片 / paste-import/file 文件变体）；
// 文件路径走 POST imports/file/parse（multipart）出 {headers, rows}，前端按列映射拼预览行后走 POST /imports 确认（rows 口径）。
// 确认请求只发契约字段：source=AI_PASTE 时 rows 为 ImportRowInput{externalId,title,description,priority,estimate,dueDate,externalUrl}（estimate 为契约 0.5.1 新增，仅新建落库）；无 parentId。
import { ref, watch } from 'vue'
import Modal from './Modal.vue'; import AppButton from './AppButton.vue'; import AiSpark from './AiSpark.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'; import type { ImportRow, ImportBatch } from '../api/types'
import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'; import { useAi } from '../stores/ai'
import { useBusy } from '../utils/busy'
import { fmtDateTime } from '../utils/datetime'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; done: [] }>()
const team = useTeam(), toast = useToast(), ai = useAi(), busy = useBusy()
const mode = ref<'paste'|'file'>('paste'), step = ref<'input'|'preview'>('input')
const pasteText = ref(''), imageDataUrl = ref(''), imageName = ref(''), rows = ref<ImportRow[]>([]), history = ref<ImportBatch[]>([])
const parsed = ref<{ headers: string[]; rows: string[][] } | null>(null)
const parseErr = ref(''), mapping = ref<Record<string, number | ''>>({ title: 0, description: '', priority: '', estimate: '', dueDate: '', externalId: '', externalUrl: '' })
const mapFields = [{ key: 'title', label: '标题（必选）' }, { key: 'description', label: '描述' }, { key: 'priority', label: '优先级' }, { key: 'estimate', label: '估算' }, { key: 'dueDate', label: '截止日期' }, { key: 'externalId', label: '外部编号' }, { key: 'externalUrl', label: '外部链接' }]
let importKey = ''
const fmt = fmtDateTime
const canUndo = (b: ImportBatch) => !b.undoneAt && Date.now() - new Date(b.createdAt).getTime() < 24 * 3600 * 1000
watch(() => props.open, async (o) => { if (o) { importKey = newIdempotencyKey(); step.value = 'input'; await loadHistory() } })
async function loadHistory(){ history.value = await api.get(`/teams/${team.currentId}/imports`).catch(() => []) }
function onImage(e: Event){ const f = (e.target as HTMLInputElement).files?.[0]; if (!f) { imageDataUrl.value = ''; imageName.value = ''; return }
  imageName.value = f.name; const rd = new FileReader(); rd.onload = () => { imageDataUrl.value = String(rd.result ?? '') }; rd.readAsDataURL(f) }
async function parsePaste(){ await busy.wrap('parse', async () => {
  try { const r = await api.post(`/teams/${team.currentId}/ai/paste-import`, { text: pasteText.value || undefined, imageBase64: imageDataUrl.value || undefined }); rows.value = (r.rows ?? []).map((x: any) => ({ ...x, selected: x.matchStatus !== 'UNCHANGED' })); step.value = 'preview' }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '解析失败') } }) }
async function onPasteFile(e: Event){ const f = (e.target as HTMLInputElement).files?.[0]; if (!f) return
  await busy.wrap('parse', async () => { try { const fd = new FormData(); fd.append('file', f); const r = await api.postForm(`/teams/${team.currentId}/ai/paste-import/file`, fd); rows.value = (r.rows ?? []).map((x: any) => ({ ...x, selected: x.matchStatus !== 'UNCHANGED' })); step.value = 'preview' }
  catch(err){ toast.show(err instanceof ApiError ? err.message : '文件解析失败') } }) }
async function onFile(e: Event){ parseErr.value = ''; const f = (e.target as HTMLInputElement).files?.[0]; if (!f) return
  try { const fd = new FormData(); fd.append('file', f); parsed.value = await api.postForm(`/teams/${team.currentId}/imports/file/parse`, fd) }
  catch(err){ parseErr.value = err instanceof ApiError ? err.message : '文件解析失败' } }
function buildFileRows(){ if (!parsed.value || mapping.value.title === '' || mapping.value.title === undefined) return
  const cell = (r: string[], k: string) => { const v = mapping.value[k]; return v === '' || v === undefined ? null : (r[Number(v)] ?? null) }
  rows.value = parsed.value.rows.filter(r => cell(r, 'title')).map(r => ({ externalId: cell(r, 'externalId'), title: cell(r, 'title') ?? '', description: cell(r, 'description'), priority: ['P0','P1','P2','P3'].includes(cell(r, 'priority') ?? '') ? cell(r, 'priority')! : 'P2', ownerName: null, ownerId: null, estimate: cell(r, 'estimate') != null && !Number.isNaN(Number(cell(r, 'estimate'))) ? Number(cell(r, 'estimate')) : null, dueDate: /^\d{4}-\d{2}-\d{2}$/.test(cell(r, 'dueDate') ?? '') ? cell(r, 'dueDate') : null, parentExternalId: null, externalUrl: cell(r, 'externalUrl'), sourceSnippet: '', confidence: 1, matchStatus: 'NEW' as const, existingItemId: null, diff: {}, selected: true }))
  step.value = 'preview' }
async function confirmImport(){ await busy.wrap('confirm', async () => {
  // 确认统一走契约 rows（ImportRowInput[]，保留预览编辑/勾选）；rawRows+mapping 路径由后端与 mock 同样支持（冒烟直测）。
  try { const body: any = { source: mode.value === 'file' ? 'FILE_MAPPING' : 'AI_PASTE', rows: rows.value.filter(r => r.selected).map(r => ({ externalId: r.externalId ?? null, title: r.title, description: r.description ?? null, priority: r.priority, estimate: r.estimate ?? null, dueDate: r.dueDate ?? null, externalUrl: r.externalUrl ?? null })) }
    const r = await api.postKey(`/teams/${team.currentId}/imports`, body, importKey)
    toast.show(`导入完成：新建 ${r.createdCount}、更新 ${r.updatedCount}`); importKey = newIdempotencyKey(); await loadHistory(); emit('done'); emit('close') }
  catch(e){ toast.show(e instanceof ApiError ? e.message : '导入失败') } }) }
async function undo(b: ImportBatch){ await busy.wrap('undo' + b.id, async () => {
  try { await api.post(`/teams/${team.currentId}/imports/${b.id}/undo`); toast.show('已整批撤销'); await loadHistory(); emit('done') } catch(e){ toast.show(e instanceof ApiError ? e.message : '撤销失败') } }) }
</script>
<style scoped>
.tabs{display:flex;gap:8px;margin:10px 0}
.tchip{border:1px solid var(--field-brd);background:var(--field);border-radius:999px;padding:6px 13px;font-size:13px;font-weight:600}
.tchip.on{border-color:var(--theme);color:var(--theme)}
.bulk{width:100%;border:1px solid var(--field-brd);background:var(--field);border-radius:var(--r-ctl);padding:10px 12px;outline:none;font-size:14px;resize:vertical}
.imgrow{margin-top:8px;display:flex;gap:10px;align-items:center}
.foot{display:flex;gap:10px;justify-content:flex-end;margin-top:14px}
.maprow{display:flex;gap:10px;align-items:center;margin-top:8px}.grow{flex:1}
.flexin{min-height:38px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:5px 9px}
.flexin.warn{background:var(--warn-bg-max);border-color:var(--warn-text)}
.prow{display:flex;gap:8px;align-items:center;padding:7px 0;border-bottom:1px solid var(--field-brd);flex-wrap:wrap}
.prow .num{width:76px;flex:none}.prow .num2{width:140px;flex:none}
.act{font-size:11px;font-weight:700;border-radius:6px;padding:2px 7px;flex:none}
.act.NEW{background:var(--success-bg-mid);color:var(--success-text)}.act.UPDATE{background:var(--primary-soft-12);color:var(--primary-deep)}.act.UNCHANGED{background:var(--field);color:var(--text-2)}
.diff{flex-basis:100%;padding-left:28px}
.hist{margin-top:18px;border-top:1px solid var(--field-brd);padding-top:10px}
.hrow{display:flex;gap:8px;align-items:center;padding:5px 0}
</style>
