<template>
<AppShell title="意见反馈" @refresh="load">
  <div class="bar glass">
    <span class="small">平台级反馈，所有登录用户可见；觉得有用就 +1，避免重复提交。</span><span style="flex:1"/>
    <AppButton @click="ui.feedbackOpen=true"><Plus :size="15"/>提交反馈</AppButton>
  </div>
  <div class="filters">
    <select v-model="filter.status" class="flexin" @change="load"><option value="">全部状态</option><option v-for="(l,k) in FEEDBACK_STATUS_LABELS" :key="k" :value="k">{{l}}</option></select>
    <select v-model="filter.type" class="flexin" @change="load"><option value="">全部类型</option><option v-for="(l,k) in FEEDBACK_TYPE_LABELS" :key="k" :value="k">{{l}}</option></select>
    <select v-model="filter.sort" class="flexin" @change="load"><option value="PLUS_ONES">按 +1 数</option><option value="LATEST">按时间</option></select>
    <AppButton v-if="isAdmin" variant="ghost" :disabled="!selectedIds.length || exporting" @click="exportSelected">{{ exporting ? '导出中…' : `导出已选（${selectedIds.length}）zip` }}</AppButton>
  </div>
  <p v-if="loading" class="small">加载中…</p>
  <p v-if="loadErr" class="errbar">{{loadErr}}</p>
  <div class="list">
    <GlassPanel v-for="f in list" :key="f.id" class="fcard">
      <div class="frow1">
        <label v-if="isAdmin" class="ck"><input type="checkbox" :value="f.id" v-model="selectedIds"/></label>
        <span class="tpill" :class="f.type">{{FEEDBACK_TYPE_LABELS[f.type]}}</span>
        <b class="grow">{{f.title}}</b>
        <span class="spill" :class="f.status">{{FEEDBACK_STATUS_LABELS[f.status]}}</span>
        <button class="up press" :class="{on: f.myPlusOne}" @click="toggleUp(f)" title="+1（再点取消）"><ThumbsUp :size="14"/>{{f.plusOneCount}}</button>
      </div>
      <p class="small desc">{{f.description}}</p>
      <p v-if="f.images.length" class="tiny">附件图片：{{f.images.map(i=>i.fileName).join('、')}}</p>
      <p v-if="f.plannedVersion" class="tiny">计划版本：{{f.plannedVersion}}</p>
      <p v-if="f.rejectReason" class="tiny">不采纳原因：{{f.rejectReason}}</p>
      <p v-if="f.releasedVersion" class="tiny">上线版本：{{f.releasedVersion}}</p>
      <p v-if="f.officialReply" class="official">官方回复：{{f.officialReply}}</p>
      <p class="tiny meta">{{f.submitterName}}（{{f.submitterLoginName}}） · {{fmtDateTime(f.createdAt)}}<span v-if="f.pageUrl"> · 来自 {{f.pageUrl}}</span><span v-if="f.appVersion"> · v{{f.appVersion}}</span></p>
      <div class="acts">
        <button class="press act" @click="toggleComments(f)">评论（{{f.commentCount}}）</button>
        <button v-if="f.submitterId===auth.user?.id && f.status==='NEW'" class="press act" @click="startEdit(f)">编辑</button>
      </div>
      <div v-if="openId===f.id" class="comments">
        <div v-for="c in commentsOf(f.id)" :key="c.id" class="crow2"><b>{{c.authorName}}</b> <span class="tiny">{{fmtDateTime(c.createdAt)}}</span><p>{{c.content}}</p></div>
        <p v-if="!commentsOf(f.id).length" class="small">暂无评论。</p>
        <div class="cform"><input v-model="commentDraft[f.id]" class="flexin grow" placeholder="写评论…" @keyup.enter="addComment(f)"/><AppButton variant="ghost" @click="addComment(f)">发表</AppButton></div>
      </div>
      <div v-if="editingId===f.id" class="editbox">
        <AppInput v-model="editForm.title" label="标题"/>
        <AppInput v-model="editForm.description" type="textarea" label="描述" style="margin-top:8px"/>
        <div class="cform" style="margin-top:8px"><span style="flex:1"/><AppButton variant="ghost" @click="editingId=null">取消</AppButton><AppButton @click="saveEdit(f)">保存</AppButton></div>
      </div>
      <div v-if="isAdmin" class="adminbox">
        <select v-model="statusDraft[f.id].status" class="flexin"><option v-for="(l,k) in FEEDBACK_STATUS_LABELS" :key="k" :value="k">{{l}}</option></select>
        <input v-if="statusDraft[f.id].status==='ACCEPTED'" v-model="statusDraft[f.id].plannedVersion" class="flexin" placeholder="计划版本（必填）"/>
        <input v-if="statusDraft[f.id].status==='REJECTED'" v-model="statusDraft[f.id].rejectReason" class="flexin" placeholder="不采纳原因（必填）"/>
        <input v-if="statusDraft[f.id].status==='RELEASED'" v-model="statusDraft[f.id].releasedVersion" class="flexin" placeholder="上线版本（必填）"/>
        <AppButton variant="ghost" @click="applyStatus(f)">更新状态</AppButton>
        <input v-model="replyDraft[f.id]" class="flexin grow" placeholder="官方回复…"/>
        <AppButton variant="ghost" @click="applyReply(f)">回复</AppButton>
      </div>
    </GlassPanel>
    <p v-if="!list.length && !loading" class="small">暂无符合条件的反馈，点右上角提交第一条。</p>
  </div>
</AppShell>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus, ThumbsUp } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'
import { api, apiMode, ApiError } from '../api/http'
import { FEEDBACK_TYPE_LABELS, FEEDBACK_STATUS_LABELS, type FeedbackItem, type FeedbackCommentItem } from '../api/types'
import { fmtDateTime } from '../utils/datetime'
import { useAuth } from '../stores/auth'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'
const auth = useAuth(), ui = useUi(), toast = useToast()
const isAdmin = computed(() => !!auth.user?.platformAdmin)
const list = ref<FeedbackItem[]>([]), loading = ref(false), loadErr = ref(''), exporting = ref(false)
const filter = reactive({ status: '', type: '', sort: 'PLUS_ONES' })
const openId = ref<number | null>(null), editingId = ref<number | null>(null)
const commentDraft = reactive<Record<number, string>>({}), replyDraft = reactive<Record<number, string>>({})
const commentsMap = reactive<Record<number, FeedbackCommentItem[]>>({})
function commentsOf(id: number) { return commentsMap[id] ?? [] }
async function toggleComments(f: FeedbackItem) { const open = openId.value !== f.id; openId.value = open ? f.id : null; if (open && !commentsMap[f.id]) { try { commentsMap[f.id] = await api.get(`/feedback/${f.id}/comments`) } catch (e) { toast.show(e instanceof ApiError ? e.message : '评论加载失败') } } }
const editForm = reactive({ title: '', description: '' })
const statusDraft = reactive<Record<number, { status: string; plannedVersion: string; rejectReason: string; releasedVersion: string }>>({})
const selectedIds = ref<number[]>([])
async function load() {
  loading.value = true; loadErr.value = ''
  try {
    const qs = new URLSearchParams(); if (filter.status) qs.set('status', filter.status); if (filter.type) qs.set('type', filter.type); qs.set('sort', filter.sort)
    list.value = await api.get(`/feedback?${qs}`)
    for (const f of list.value) statusDraft[f.id] ??= { status: f.status, plannedVersion: f.plannedVersion ?? '', rejectReason: f.rejectReason ?? '', releasedVersion: f.releasedVersion ?? '' }
  } catch (e) { loadErr.value = e instanceof ApiError ? e.message : '加载失败' } finally { loading.value = false }
}
onMounted(load)
async function toggleUp(f: FeedbackItem) { try { const r = f.myPlusOne ? await api.del(`/feedback/${f.id}/plus-one`) : await api.post(`/feedback/${f.id}/plus-one`); Object.assign(f, r) } catch (e) { toast.show(e instanceof ApiError ? e.message : '操作失败') } }
async function addComment(f: FeedbackItem) { const c = (commentDraft[f.id] ?? '').trim(); if (!c) return
  try { const cm: FeedbackCommentItem = await api.post(`/feedback/${f.id}/comments`, { content: c }); commentDraft[f.id] = ''; (commentsMap[f.id] ??= []).push(cm); f.commentCount++; toast.show('评论已发表') } catch (e) { toast.show(e instanceof ApiError ? e.message : '发表失败') } }
function startEdit(f: FeedbackItem) { editingId.value = f.id; editForm.title = f.title; editForm.description = f.description }
async function saveEdit(f: FeedbackItem) { try { await api.patch(`/feedback/${f.id}`, { ...editForm }); editingId.value = null; toast.show('已保存'); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function applyStatus(f: FeedbackItem) { const d = statusDraft[f.id]
  try { await api.patch(`/feedback/${f.id}/status`, { status: d.status, plannedVersion: d.plannedVersion || undefined, rejectReason: d.rejectReason || undefined, releasedVersion: d.releasedVersion || undefined }); toast.show('状态已更新'); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '更新失败') } }
async function applyReply(f: FeedbackItem) { const c = (replyDraft[f.id] ?? '').trim(); if (!c) { toast.show('请填写回复内容'); return }
  try { await api.patch(`/feedback/${f.id}/official-reply`, { officialReply: c }); replyDraft[f.id] = ''; toast.show('已回复'); load() } catch (e) { toast.show(e instanceof ApiError ? e.message : '回复失败') } }
async function exportSelected() {
  exporting.value = true
  try {
    if (apiMode === 'mock') {
      const r: any = await api.post('/feedback/export', { ids: [...selectedIds.value] })
      const blob = new Blob([r.summaryMarkdown ?? ''], { type: 'text/markdown' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'feedback-export.md'; a.click(); URL.revokeObjectURL(a.href)
      toast.show('演示模式：已导出 Markdown 汇总（真实环境为 zip，含图片）')
    } else {
      // 真实环境：后端直接返回 zip 二进制（Markdown 汇总 + 图片）。
      const res = await fetch('/api/v1/feedback/export', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: [...selectedIds.value] }) })
      if (!res.ok) throw new Error('导出失败')
      const blob = await res.blob(); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'feedback-export.zip'; a.click(); URL.revokeObjectURL(a.href)
      toast.show('已导出 zip')
    }
    selectedIds.value = []
  } catch (e) { toast.show(e instanceof ApiError ? e.message : '导出失败') } finally { exporting.value = false }
}
</script>
<style scoped>
.bar{border-radius:var(--r-card);padding:10px 14px;display:flex;gap:14px;align-items:center;margin-bottom:12px}
.filters{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:12px;align-items:center}
.flexin{min-height:38px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px}
.flexin.grow{flex:1}
.errbar{color:var(--c-red);background:var(--danger-bg-soft);border-radius:12px;padding:10px 14px;margin-bottom:12px}
.list{display:flex;flex-direction:column;gap:12px}
.fcard{padding:14px 16px}
.frow1{display:flex;gap:10px;align-items:center}.frow1 .grow{flex:1}
.ck input{width:17px;height:17px}
.tpill{font-size:11px;font-weight:700;border-radius:6px;padding:2px 7px;background:var(--sel);color:var(--primary-deep);flex:none}
.tpill.ISSUE{background:var(--danger-soft);color:var(--danger-deep)}.tpill.SUGGESTION{background:var(--success-bg);color:var(--success-text)}
.spill{font-size:11.5px;font-weight:700;border-radius:99px;padding:2px 9px;background:var(--track);color:var(--text-2);flex:none}
.spill.ACCEPTED{background:var(--success-soft);color:var(--success-text)}.spill.REJECTED{background:var(--danger-bg-strong);color:var(--c-red)}.spill.RELEASED{background:var(--sel);color:var(--primary-deep)}
.up{display:inline-flex;gap:5px;align-items:center;border:1.5px solid var(--separator);background:var(--card);border-radius:999px;padding:5px 12px;font-weight:700;min-height:34px;flex:none}
.up.on{border-color:var(--theme);color:var(--theme);background:var(--theme-soft)}
.desc{margin-top:8px;line-height:1.6;white-space:pre-wrap}
.official{margin-top:8px;background:var(--sel);border-radius:10px;padding:8px 12px;font-size:13px}
.meta{margin-top:8px;color:var(--text-3)}
.acts{display:flex;gap:14px;margin-top:8px}.act{color:var(--theme);font-weight:600;font-size:13px}
.comments{margin-top:10px;border-top:1px solid var(--field-brd);padding-top:10px}
.crow2{padding:6px 0}.crow2 p{margin:2px 0;font-size:13.5px}
.cform{display:flex;gap:8px;align-items:center;margin-top:8px}.cform .grow{flex:1}
.editbox{margin-top:10px;border-top:1px solid var(--field-brd);padding-top:10px}
.adminbox{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px;border-top:1px dashed var(--field-brd);padding-top:10px;align-items:center}
.adminbox .grow{flex:1;min-width:160px}
</style>
