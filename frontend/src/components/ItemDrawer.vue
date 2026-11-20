<template>
<Drawer :open="open" @close="$emit('close')">
  <div v-if="loading" class="small">加载中…</div>
  <template v-else-if="item">
    <div v-if="item.blocked" class="banner red"><OctagonAlert :size="19"/><span class="grow"><b>当前阻塞</b>：{{item.blockReason}}</span><AppButton variant="ghost" class="banner-btn" @click="unblock">解除阻塞</AppButton></div>
    <div v-if="item.nudgeCount>0" class="banner yellow"><Zap :size="19"/><span class="grow"><b>已被催办 {{item.nudgeCount}} 次</b>，请尽快更新进展或回复状态。<span class="quick-replies">
      <button class="qr press" :disabled="busy.is('respond')" @click="respondNudge('SOON')">马上更新</button>
      <button class="qr press" :disabled="busy.is('respond')" @click="respondNudge('TODAY')">今天搞定</button>
      <button class="qr press" :disabled="busy.is('respond')" @click="respondNudge('BLOCKED')">卡住了</button></span></span></div>

    <div class="head"><TypeBadge :type="item.type" :story-category="item.storyCategory"/><span v-if="item.batchId" class="btag">批次 #{{item.batchId}}</span><span class="small">{{item.code}}</span><span style="flex:1"/>
      <button class="icon press" aria-label="关闭" @click="$emit('close')"><X :size="18"/></button>
    </div>

    <input class="title" v-model="titleDraft" @blur="saveTitle" aria-label="事项标题"/>
    <div class="mainrow">      <div class="rel"><button class="press pillbtn" @click="showActions=!showActions"><StatusPill :status="item.status"/><ChevronDown :size="15"/></button>
        <div v-if="showActions" class="menu glass"><button v-for="a in actions" :key="a.action" @click="doAction(a.action)">{{a.label}}</button>
        <button v-if="canJoin" :disabled="busy.is('join')" @click="doJoin">加入共同负责</button>
        <button v-if="canDecline" @click="askReason('DECLINE')">婉拒</button>
        <button @click="showTransfer=true;showActions=false">转交</button>
        <button v-if="canDeleteItem" @click="removeItem">删除（进回收站）</button>
        <button @click="askReason('RELEASE')">释放回待认领</button>
        <button @click="askReason('CANCEL')">取消事项</button>
        <span v-if="!actions.length" class="small" style="padding:8px 12px">当前无可执行动作</span></div></div>
      <AppButton v-if="mainAction" :disabled="busy.is('transition')" @click="doAction(mainAction.action)">{{mainAction.label}}</AppButton>
      <AppButton v-if="canNudge" variant="ghost" :disabled="busy.is('nudge')" @click="nudgeItem"><Zap :size="15"/>催一下</AppButton>
      <AppButton variant="ghost" @click="editOpen=true">编辑</AppButton></div>

    <div class="attrs">
      <div class="attr rel"><span>负责人</span><b class="avaline" :class="{editable:canInlineEdit && item.type!=='TASK'}" @click="toggleEdit('owners')"><Avatar v-if="item.ownerIds.length" :name="team.memberName(item.ownerIds[0])" :color="team.member(item.ownerIds[0])?.avatarColor" :size="22"/>{{item.ownerIds.length ? item.ownerIds.map(id=>team.memberName(id)).join('、') : '未认领'}}<Pencil v-if="canInlineEdit && item.type!=='TASK'" :size="13" class="pen"/></b>
        <div v-if="editField==='owners'" class="pop glass">
          <label v-for="m in team.members" :key="m.userId" class="poprow"><input type="checkbox" :value="m.userId" v-model="ownerDraft"/>{{m.displayName}}<span class="tiny">{{m.role==='LEADER'?'组长':m.role==='PO'?'PO':'成员'}}</span></label>
          <div class="popfoot"><AppButton variant="ghost" @click="editField=null">取消</AppButton><AppButton @click="saveOwners">保存</AppButton></div>
        </div></div>
      <div class="attr"><span>协作人</span><b>{{item.collaboratorIds.map(id=>team.memberName(id)).join('、')||'无'}}</b></div>
      <div class="attr"><span>验收人</span><b class="avaline"><Avatar v-if="item.acceptorId" :name="team.memberName(item.acceptorId)" :color="team.member(item.acceptorId)?.avatarColor" :size="22"/>{{item.acceptorId ? team.memberName(item.acceptorId) : '未设置'}}</b></div>
      <div class="attr rel"><span>优先级</span><b :class="{editable:canInlineEdit}" @click="toggleEdit('priority')"><span class="prio" :class="item.priority">{{item.priority}}</span><Pencil v-if="canInlineEdit" :size="13" class="pen"/></b>
        <div v-if="editField==='priority'" class="pop glass"><div class="poprow" style="gap:8px"><button v-for="p in ['P0','P1','P2','P3']" :key="p" class="prio press" :class="p" @click="savePriority(p)">{{p}}</button></div></div></div>
      <div class="attr rel"><span>截止日期</span><b :class="{editable:canInlineEdit}" @click="toggleEdit('due')">{{item.dueDate ? fmtDue(item.dueDate) : '未设置'}}<span v-if="dueState==='over'" class="overdue"> 已逾期</span><span v-else-if="dueState==='today'" class="overdue"> 今天到期</span><Pencil v-if="canInlineEdit" :size="13" class="pen"/></b>
        <div v-if="editField==='due'" class="pop glass"><input type="date" v-model="dueDraft" class="flexin"/><div class="popfoot"><AppButton variant="ghost" @click="saveDue(null)">清除</AppButton><AppButton @click="saveDue(dueDraft)">保存</AppButton></div></div></div>
      <div class="attr rel"><span>估算</span><b :class="{editable:canInlineEdit}" @click="toggleEdit('estimate')">{{item.estimate ?? '—'}} 点<Pencil v-if="canInlineEdit" :size="13" class="pen"/></b>
        <div v-if="editField==='estimate'" class="pop glass"><input type="number" min="0" step="0.5" v-model="estimateDraft" class="flexin" placeholder="故事点"/><div class="popfoot"><AppButton variant="ghost" @click="editField=null">取消</AppButton><AppButton @click="saveEstimate">保存</AppButton></div></div></div>
      <div class="attr"><span>所属迭代</span><b>{{item.iterationId ? (team.iteration?.name ?? '当前迭代') : '日常事项'}}</b></div>
      <div class="attr rel"><span>标签</span><b :class="{editable:canInlineEdit && canManageLabels}" @click="toggleEdit('labels')"><span v-for="l in item.labels" :key="l" class="tag">{{l}}</span><span v-if="!item.labels.length">无</span><Pencil v-if="canInlineEdit && canManageLabels" :size="13" class="pen"/></b>
        <div v-if="editField==='labels'" class="pop glass"><input v-model="labelsDraft" class="flexin" placeholder="多个标签用逗号分隔"/><div class="popfoot"><AppButton variant="ghost" @click="editField=null">取消</AppButton><AppButton @click="saveLabels">保存</AppButton></div></div></div>
      <div class="attr" v-if="item.parentId"><span>所属任务</span><b class="parentlink press" @click="$emit('open-item', item.parentId)">{{parentTitle}}</b></div>
      <div class="attr" v-if="item.type==='STORY' && item.storyCategory"><span>故事类别</span><b><span class="catpill" :style="{background:STORY_CAT_META[item.storyCategory].color}">{{STORY_CAT_META[item.storyCategory].label}}</span></b></div>
      <div class="attr" v-if="item.type==='TASK'"><span>所属 PO</span><b class="avaline"><Avatar v-if="item.poId" :name="team.memberName(item.poId)" :color="team.member(item.poId)?.avatarColor" :size="22"/>{{item.poId ? team.memberName(item.poId) : '未设置'}}</b></div>
      <div class="attr" v-if="item.type==='TASK' && item.taskStatus"><span>任务状态</span><b>{{TASK_STATUS_META[item.taskStatus].label}} · 进度 {{item.progressPercent}}%（由故事推算）</b></div>
      <div class="attr" v-if="item.batchId"><span>下发批次</span><b>批次 #{{item.batchId}}（每人一份）</b></div>
      <div class="attr" v-if="item.assignedDirectly"><span>来源</span><b>直接指派{{canDecline ? '（未开始前可婉拒）' : ''}}</b></div>
    </div>

    <div class="sec"><h2 class="sec-title"><FileText :size="16"/>描述</h2><div class="md" v-html="html"/></div>

    <div v-if="children.length" class="sec"><h2 class="sec-title"><Layers :size="16"/>{{item.type==='TASK' ? '故事' : '子事项'}}<span class="tiny">{{item.childDoneCount}}/{{item.childCount}} 已完成</span></h2>
      <ProgressBar :value="item.childCount? item.childDoneCount/item.childCount*100:0" style="margin:8px 0"/>
      <button v-for="c in children" :key="c.id" class="child press" @click="$emit('open-item',c.id)"><span class="st-ico" :style="{background:c.status==='DONE' ? 'var(--c-green)' : typeColor(c.type)}"><Check v-if="c.status==='DONE'" :size="12"/><Clock v-else :size="12"/></span><span class="sid">{{c.code}}</span><span class="ct">{{c.title}}</span><span class="tiny">{{STATUS_META[c.status].label}}</span><Avatar v-if="c.ownerIds.length" :name="team.memberName(c.ownerIds[0])" :color="team.member(c.ownerIds[0])?.avatarColor" :size="22"/></button>
    </div>

    <div class="sec"><h2 class="sec-title"><ClipboardList :size="16"/>检查清单<span class="tiny">{{checklist.filter(c=>c.done).length}}/{{checklist.length}} 已完成</span></h2>
      <div v-for="c in checklist" :key="c.id" class="check"><input type="checkbox" :checked="c.done" @change="toggleCheck(c)"/><span :class="{done:c.done}" class="grow">{{c.text}}</span><button class="icon sm press" aria-label="删除清单条目" @click="removeCheck(c)"><X :size="14"/></button></div>
      <div class="foot-row" style="margin-top:8px"><input v-model="newCheck" class="flexin" placeholder="新增清单条目，回车添加" @keyup.enter="addCheck"/><AppButton variant="ghost" @click="addCheck">添加</AppButton></div></div>

    <div class="sec">
      <div class="dtabs"><button :class="{on:tab==='progress'}" @click="tab='progress'">进展</button><button :class="{on:tab==='comments'}" @click="tab='comments'">评论{{comments.length ? `（${comments.length}）` : ''}}</button><button :class="{on:tab==='activities'}" @click="tab='activities'">动态</button></div>
      <div v-if="tab==='progress'" class="tl">
        <div v-for="p in progress" :key="p.id" class="tl-item"><Avatar :name="team.memberName(p.authorId)" :color="team.member(p.authorId)?.avatarColor"/><div>
          <b>{{team.memberName(p.authorId)}}</b> <span class="tiny">{{fmt(p.createdAt)}}</span>
          <p><template v-for="(seg,si) in mentionSegments(p.text, p.mentionIds)" :key="si"><span v-if="seg.mention" class="mention">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></p><p v-if="p.percent!=null"><span class="tl-pct">进度 {{p.percent}}%</span></p>
          <p v-if="p.blocked" class="blocked-txt">阻塞：{{p.blockReason}}</p><p v-if="p.nextStep" class="small">下一步：{{p.nextStep}}</p></div></div>
        <p v-if="!progress.length" class="small">暂无进展记录，在下方输入框里将提交第一条进展。</p>
      </div>
      <div v-else-if="tab==='comments'" class="tl">
        <div v-for="c in comments" :key="c.id" class="tl-item"><Avatar :name="team.memberName(c.authorId)" :color="team.member(c.authorId)?.avatarColor"/><div><b>{{team.memberName(c.authorId)}}</b> <span class="tiny">{{fmt(c.createdAt)}}</span>
          <template v-if="editCommentId===c.id"><textarea v-model="editCommentText" rows="2" style="width:100%"/><span style="display:flex;gap:6px;margin-top:4px"><AppButton variant="ghost" @click="editCommentId=null">取消</AppButton><AppButton :disabled="commentBusy" @click="saveComment(c)">{{ commentBusy ? '保存中…' : '保存' }}</AppButton></span></template>
          <p v-else><template v-for="(seg,si) in mentionSegments(c.content, c.mentionIds)" :key="si"><span v-if="seg.mention" class="mention">{{seg.text}}</span><template v-else>{{seg.text}}</template></template></p>
          <span v-if="editCommentId!==c.id" style="display:flex;gap:8px"><button v-if="canEditComment(c)" class="press tiny" style="color:var(--theme)" @click="editCommentId=c.id;editCommentText=c.content">编辑</button><button v-if="canDeleteComment(c)" class="press tiny" style="color:var(--c-red)" @click="removeComment(c)">删除</button></span></div></div>
        <p v-if="!comments.length" class="small">暂无评论</p>
      </div>
      <div v-else class="tl">
        <div v-for="a in activities" :key="a.id" class="tl-item"><Avatar :name="team.memberName(a.actorId)" :color="team.member(a.actorId)?.avatarColor"/><div><b>{{team.memberName(a.actorId)}}</b> <span class="tiny">{{fmt(a.createdAt)}}</span><p>{{a.action}}<span v-if="a.detail"> · {{a.detail}}</span></p></div></div>
        <p v-if="!activities.length" class="small">暂无动态</p>
      </div>
    </div>
  </template>

  <template #footer>
    <div class="dfoot" v-if="item">
      <p v-if="item.type==='TASK'" class="small">任务不登记进展：进展登记在它下面的故事上，任务进度由故事自动推算。</p>
      <template v-else-if="tab==='progress'">
        <!-- 进度滑杆（1.0.4 F）：未操作时绑定 item.progressPercent（刚认领=0），不能用 v-model 的 null 初始值——
             range 在 value 为 null 时浏览器默认停在 50%，会出现「文字 0% 但填充到中间」。只有用户 input 后才写入 pPercent。 -->
        <div class="pct-row"><span>进度</span><input type="range" min="0" max="100" step="5" :value="pPercent ?? item.progressPercent" @input="pPercent = Number(($event.target as HTMLInputElement).value)"/><b>{{pPercent ?? item.progressPercent}}%</b>
          <label class="small"><input type="checkbox" v-model="pBlocked"/> 阻塞</label></div>
        <input v-if="pBlocked" v-model="pBlockReason" placeholder="阻塞原因" class="flexin" style="margin-bottom:8px"/>
        <div class="foot-row"><MentionInput v-model="pText" v-model:mention-ids="pMentionIds" :rows="2" placeholder="记录最新进展，例如：已完成联调，正在回归…（输入 @ 可提及成员）"/><AppButton variant="ghost" @click="submitKeepGoing">照常推进</AppButton><AppButton @click="submitProgress"><Send :size="15"/>提交</AppButton></div>
      </template>
      <div v-else-if="tab==='comments'" class="foot-row"><MentionInput v-model="cText" v-model:mention-ids="cMentionIds" :rows="2" placeholder="发表评论…（输入 @ 可提及成员）" enter-submits @enter="submitComment"/><AppButton :disabled="commentBusy" @click="submitComment"><Send :size="15"/>{{ commentBusy ? '提交中…' : '提交' }}</AppButton></div>
    </div>
  </template>

  <Modal :open="!!reasonAction" @close="reasonAction=null">
    <h2>{{reasonAction==='REJECT'?'打回原因':reasonAction==='RELEASE'?'释放原因':reasonAction==='DECLINE'?'婉拒原因':'取消原因'}}</h2>
    <AppInput v-model="reason" type="textarea" label="原因（必填）" placeholder="说清原因，对方会看到"/>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="reasonAction=null">返回</AppButton><AppButton variant="danger" @click="confirmReason">确认</AppButton></div>
  </Modal>
  <EditItemModal :item="editOpen ? item : null" :tasks="allItems.filter(i=>i.type==='TASK')" @close="editOpen=false" @saved="(it:any)=>{item=it; emit('changed'); load()}"/>
  <Modal :open="showTransfer" @close="showTransfer=false">
    <h2>转交给</h2>
    <p class="small">共同负责时只替换一位负责人，其余共同负责人保留。</p>
    <label v-if="item && item.ownerIds.length>1" class="small" style="display:block;margin-top:10px">替换哪位负责人
      <select v-model.number="replaceDraft" class="flexin" style="margin-top:6px">
        <option v-for="oid in item.ownerIds" :key="oid" :value="oid">{{team.memberName(oid)}}</option>
      </select></label>
    <div class="tlist"><button v-for="m in team.members" :key="m.userId" class="child press" @click="transfer(m.userId)"><Avatar :name="m.displayName" :color="m.avatarColor"/><span>{{m.displayName}}</span></button></div>
  </Modal>
  <Modal :open="taskRejectOpen" @close="taskRejectOpen=false">
    <h2>打回任务</h2>
    <p class="small">打回必须说明原因，并且至少重开一个已完成故事、或新建一个补充故事（不允许没有任何故事可做）。</p>
    <AppInput v-model="reason" type="textarea" label="打回原因（必填）" placeholder="说清哪里不达标"/>
    <p class="small" style="margin-top:12px"><b>重开已完成的故事</b></p>
    <label v-for="c in children.filter(x=>x.status==='DONE')" :key="c.id" class="poprow"><input type="checkbox" :value="c.id" v-model="reopenDraft"/>{{c.code}} {{c.title}}</label>
    <p v-if="!children.some(x=>x.status==='DONE')" class="tiny">该任务暂无已完成的故事可重开。</p>
    <p class="small" style="margin-top:12px"><b>或新建补充故事</b></p>
    <div v-for="(ns, idx) in newStoryDraft" :key="idx" class="foot-row" style="margin-top:6px">
      <input v-model="ns.title" class="flexin" placeholder="补充故事标题"/>
      <select v-model="ns.storyCategory" class="flexin" style="flex:0 0 110px"><option value="DEV">开发</option><option value="ANALYSIS">分析</option><option value="TEST">测试</option><option value="OTHER">其他</option></select>
      <AppButton variant="ghost" @click="newStoryDraft.splice(idx,1)">删除</AppButton>
    </div>
    <AppButton variant="ghost" style="margin-top:8px" @click="newStoryDraft.push({title:'',storyCategory:'DEV'})">＋ 添加补充故事</AppButton>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px"><AppButton variant="ghost" @click="taskRejectOpen=false">返回</AppButton><AppButton variant="danger" @click="confirmTaskReject">确认打回</AppButton></div>
  </Modal>
</Drawer>
</template>
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { marked } from 'marked'; import DOMPurify from 'dompurify'
import { X, OctagonAlert, Zap, ChevronDown, FileText, Layers, ClipboardList, Check, Clock, Send, Pencil } from 'lucide-vue-next'
import Drawer from './Drawer.vue'; import Modal from './Modal.vue'; import AppButton from './AppButton.vue'; import AppInput from './AppInput.vue'
import StatusPill from './StatusPill.vue'; import EditItemModal from './EditItemModal.vue'; import { STATUS_META, TYPE_META, STORY_CAT_META, TASK_STATUS_META } from '../api/types'; import TypeBadge from './TypeBadge.vue'; import Avatar from './Avatar.vue'; import ProgressBar from './ProgressBar.vue'
import MentionInput from './MentionInput.vue'; import { mentionSegments } from '../utils/mention'; import { fmtDateTime, localToday } from '../utils/datetime'
import { api, ApiError } from '../api/http'; import type { Item, ProgressUpdate, Comment, Activity } from '../api/types'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'; import { useBusy } from '../utils/busy'
const props = defineProps<{open:boolean; itemId:number|null}>()
const emit = defineEmits(['close','changed','open-item'])
const team = useTeam(), auth = useAuth(), toast = useToast(), busy = useBusy()
const item = ref<Item|null>(null), loading = ref(false), titleDraft = ref(''), tab = ref('progress')
const progress = ref<ProgressUpdate[]>([]), comments = ref<Comment[]>([]), activities = ref<Activity[]>([]), allItems = ref<Item[]>([])
const showActions = ref(false), showTransfer = ref(false), reasonAction = ref<string|null>(null), reason = ref(''), editOpen = ref(false)
const pText = ref(''), pPercent = ref<number|null>(null), pBlocked = ref(false), pBlockReason = ref(''), cText = ref('')
// @提及（1.0.4 G）：MentionInput 维护被 @ 成员 id，提交评论/进展时随 mentionIds 一起发（评论 POST 已支持；进展 POST 后端 1.0.4 同步加同名字段）
const pMentionIds = ref<number[]>([]), cMentionIds = ref<number[]>([])
const checklist = ref<import('../api/types').ChecklistEntry[]>([]), newCheck = ref('')
// M3：抽屉内联编辑（待问 5 批复：常用字段点击即改小浮层；全字段「编辑」弹窗保留）
const editField = ref<string|null>(null), ownerDraft = ref<number[]>([]), dueDraft = ref(''), estimateDraft = ref(''), labelsDraft = ref('')
const replaceDraft = ref<number|null>(null)
const taskRejectOpen = ref(false), reopenDraft = ref<number[]>([]), newStoryDraft = ref<{title:string;storyCategory:string}[]>([])
const canInlineEdit = computed(()=> { const it = item.value; if (!it) return false; if (it.allowedActions) return it.allowedActions.includes('EDIT'); return !!auth.user && (team.current?.myRole==='LEADER' || team.current?.myRole==='PO' || it.creatorId===auth.user.id || it.ownerIds.includes(auth.user.id)) }) // Q11：优先读 DTO allowedActions
const canManageLabels = computed(()=> team.current?.myRole==='LEADER' || team.current?.myRole==='PO')
const editCommentId = ref<number|null>(null), editCommentText = ref(''), commentBusy = ref(false)
// §16.2 删除收口：任务 PO/组长且其故事均未开始；故事 PO/组长且未开始（待认领/待开始）；独立事项维持原口径（后端再校验）。
const canDeleteItem = computed(()=> { const it = item.value; if (!it) return false; if (it.allowedActions) return it.allowedActions.includes('DELETE'); const r = team.current?.myRole
  if (it.type==='TASK') return (r==='LEADER' || r==='PO') && (it.taskStatus==='NOT_STARTED' || it.taskStatus==null) && children.value.every(c=> c.status==='UNCLAIMED' || c.status==='NOT_STARTED')
  if (it.type==='STORY') return (r==='LEADER' || r==='PO') && (it.status==='UNCLAIMED' || it.status==='NOT_STARTED')
  return true })
function canEditComment(c:Comment){ return !!auth.user && c.authorId===auth.user.id && Date.now() - new Date(c.createdAt).getTime() <= 15*60000 }
function canDeleteComment(c:Comment){ return !!auth.user && (c.authorId===auth.user.id || team.current?.myRole==='LEADER') }
async function saveComment(c:Comment){ if(!item.value || commentBusy.value) return; commentBusy.value = true; try{ await api.patch(`/teams/${team.currentId}/items/${item.value.id}/comments/${c.id}`, { content: editCommentText.value }); editCommentId.value=null; toast.show('评论已编辑'); load() }catch(e){ handleErr(e) } finally { commentBusy.value = false } }
async function removeComment(c:Comment){ if(!item.value) return; if(!confirm('确定删除这条评论吗？')) return; try{ await api.del(`/teams/${team.currentId}/items/${item.value.id}/comments/${c.id}`); toast.show('评论已删除'); load() }catch(e){ handleErr(e) } }
const canNudge = computed(()=> { const it = item.value; if (!it) return false; if (it.allowedActions) return it.allowedActions.includes('NUDGE'); return !!auth.user && it.type!=='TASK' && !it.ownerIds.includes(auth.user.id) && ['NOT_STARTED','IN_PROGRESS','IN_REVIEW'].includes(it.status) })
function toggleEdit(f:string){ if (!canInlineEdit.value) return; if (editField.value===f) { editField.value=null; return }
  if (!item.value) return
  if (f==='owners') ownerDraft.value = [...item.value.ownerIds]
  if (f==='due') dueDraft.value = item.value.dueDate ?? ''
  if (f==='estimate') estimateDraft.value = item.value.estimate != null ? String(item.value.estimate) : ''
  if (f==='labels') { if (!canManageLabels.value) return; labelsDraft.value = item.value.labels.join('，') }
  editField.value = f }
async function saveOwners(){ editField.value=null; await patch({ ownerIds: ownerDraft.value }); load() }
async function savePriority(p:string){ editField.value=null; await patch({ priority: p }) }
async function saveDue(v:string|null){ editField.value=null; await patch({ dueDate: v }) }
async function saveEstimate(){ const v = estimateDraft.value === '' ? null : Number(estimateDraft.value); editField.value=null; await patch({ estimate: v }) }
async function saveLabels(){ const names = labelsDraft.value.split(/[,，]/).map(s=>s.trim()).filter(Boolean); editField.value=null; await patch({ labelNames: names }); load() }
async function nudgeItem(){ if(!item.value) return; await busy.wrap('nudge', async () => { try{ const r = await api.post(`/teams/${team.currentId}/items/${item.value!.id}/nudge`); toast.show(r.redirectedToLeader ? '对方请假/出差，已改催组长' : `已催一下（本轮第 ${r.nudgeCount} 次）`); emit('changed'); load() }catch(e){ handleErr(e) } }) }
async function respondNudge(response:string){ if(!item.value) return; await busy.wrap('respond', async () => { try{ await api.post(`/teams/${team.currentId}/items/${item.value!.id}/nudge/respond`, { response }); toast.show(response==='SOON' ? '已回应：马上更新' : response==='TODAY' ? '已回应：今天搞定' : '已回应：卡住了'); emit('changed'); load() }catch(e){ toast.show(e instanceof ApiError ? (e.status===404 ? '没有待回应的催' : e.message) : '回应失败') } }) }
async function submitKeepGoing(){ if(!item.value) return; try{ await api.post(`/teams/${team.currentId}/items/${item.value.id}/progress`, { text: pText.value.trim() || undefined, kind: 'KEEP_GOING', percent: pPercent.value ?? undefined, mentionIds: pMentionIds.value.length ? [...pMentionIds.value] : undefined }); pText.value=''; pMentionIds.value=[]; toast.show('已登记：照常推进'); emit('changed'); load() }catch(e){ handleErr(e) } }
function openTaskReject(){ reason.value=''; reopenDraft.value=[]; newStoryDraft.value=[]; taskRejectOpen.value=true; showActions.value=false }
async function confirmTaskReject(){ if(!item.value) return
  if (!reason.value.trim()){ toast.show('请填写打回原因'); return }
  const stories = newStoryDraft.value.filter(s=>s.title.trim()).map(s=>({ title: s.title.trim(), storyCategory: s.storyCategory }))
  if (!reopenDraft.value.length && !stories.length){ toast.show('请至少重开一个已完成故事，或新建一个补充故事'); return }
  taskRejectOpen.value=false
  await runTransition('REJECT', reason.value, undefined, { reopenItemIds: reopenDraft.value, newStories: stories }) }
const children = computed(()=> allItems.value.filter(i=>i.parentId===item.value?.id))
const parentTitle = computed(()=> { const p = allItems.value.find(i=>i.id===item.value?.parentId); return p ? `${p.code} · ${p.title}` : '无' })
const html = computed(()=> item.value ? DOMPurify.sanitize(marked.parse(item.value.description || '暂无描述') as string) : '')
const fmt = fmtDateTime
const fmtDue = (d:string)=> { const m = d.match(/^(\d+)-(\d+)-(\d+)$/); return m ? `${Number(m[2])}月${Number(m[3])}日` : d }
const dueState = computed(()=> { const d = item.value?.dueDate; if(!d || item.value?.status==='DONE') return ''; const today = localToday(); return d < today ? 'over' : d === today ? 'today' : '' })
function typeColor(t:string){ return (TYPE_META as any)[t]?.color ?? '#007AFF' }
const actions = computed(()=>{
  if(!item.value || !auth.user) return [] as {action:string;label:string}[]
  const s = item.value.status, meId = auth.user.id, out:{action:string;label:string}[] = []
  if (item.value.type==='TASK') { if (item.value.taskStatus==='PENDING_ACCEPTANCE' && (team.current?.myRole==='PO' || item.value.poId===meId)) { out.push({action:'APPROVE',label:'验收任务'}); out.push({action:'REJECT',label:'打回任务'}) } return out }
  if (s==='UNCLAIMED') out.push({action:'CLAIM',label:'认领'})
  if (s==='NOT_STARTED' && item.value.ownerIds.includes(meId)) out.push({action:'START',label:'开始'})
  if (s==='IN_PROGRESS' && item.value.ownerIds.includes(meId)) out.push({action:'SUBMIT',label:'提交验收'})
  if (s==='IN_REVIEW') { out.push({action:'APPROVE',label:'通过'},{action:'REJECT',label:'打回'}) }
  if (s==='DONE'||s==='CANCELLED') out.push({action:'REOPEN',label:'重新打开'})
  return out
})
const mainAction = computed(()=> actions.value[0] ?? null)
const canJoin = computed(()=> { const it = item.value; if (!it) return false; if (it.allowedActions) return it.allowedActions.includes('JOIN'); return !!auth.user && it.type!=='TASK' && it.ownerIds.length>0 && !it.ownerIds.includes(auth.user.id) && !['DONE','CANCELLED'].includes(it.status) })
const canDecline = computed(()=> !!item.value && !!auth.user && item.value.status==='NOT_STARTED' && item.value.assignedDirectly && item.value.ownerIds.includes(auth.user.id) && progress.value.length===0)
async function load(){
  if (!props.itemId || !team.currentId) return; loading.value = true
  try { const tid = team.currentId
    item.value = await api.get(`/teams/${tid}/items/${props.itemId}`); titleDraft.value = item.value!.title
    progress.value = await api.get(`/teams/${tid}/items/${props.itemId}/progress-list`).catch(()=>[])
    comments.value = await api.get(`/teams/${tid}/items/${props.itemId}/comments`).catch(()=>[])
    activities.value = await api.get(`/teams/${tid}/items/${props.itemId}/activities`).catch(()=>[])
    allItems.value = await api.get(`/teams/${tid}/items?scope=all`).catch(()=>[])
    checklist.value = await api.get(`/teams/${tid}/items/${props.itemId}/checklist`).catch(()=> item.value?.checklist ?? [])
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '加载失败') } finally { loading.value = false }
}
watch(()=> [props.open, props.itemId], ()=>{ if (props.open) load() })
function handleErr(e:any){ if (e instanceof ApiError && e.status===409){ toast.show('已被他人修改，已为你刷新'); load(); emit('changed') } else toast.show(e instanceof ApiError ? e.message : '操作失败') }
async function patch(fields:any){ if(!item.value) return; try{ item.value = await api.patch(`/teams/${team.currentId}/items/${item.value.id}`, { version: item.value.version, ...fields }); toast.show('已保存'); emit('changed') }catch(e){ handleErr(e) } }
async function saveTitle(){ if(item.value && titleDraft.value.trim() && titleDraft.value!==item.value.title) patch({title:titleDraft.value.trim()}) }
async function doAction(action:string){ showActions.value=false; if (action==='REJECT' && item.value?.type==='TASK'){ openTaskReject(); return } if(['REJECT','RELEASE','CANCEL','DECLINE'].includes(action)){ askReason(action); return } await runTransition(action) }
function askReason(a:string){ reasonAction.value=a; reason.value=''; showActions.value=false }
async function confirmReason(){ if(!reason.value.trim()){ toast.show('请填写原因'); return } const a=reasonAction.value; reasonAction.value=null; await runTransition(a!, reason.value) }
async function runTransition(action:string, rsn?:string, newAssigneeId?:number, extra?:any){ if(!item.value) return; await busy.wrap('transition', async () => { try{ item.value = await api.post(`/teams/${team.currentId}/items/${item.value!.id}/transitions`, { action, version: item.value!.version, reason: rsn, newAssigneeId, ...(extra ?? {}) }); toast.show('已更新状态'); emit('changed'); load() }catch(e){ handleErr(e) } }) }
watch(showTransfer, (open)=>{ if (open && item.value && item.value.ownerIds.length>1) { replaceDraft.value = auth.user && item.value.ownerIds.includes(auth.user.id) ? auth.user.id : item.value.ownerIds[0] } })
async function transfer(uid:number){ showTransfer.value=false; const extra = item.value && item.value.ownerIds.length>1 ? { replaceUserId: replaceDraft.value } : undefined; await runTransition('TRANSFER', undefined, uid, extra) }
async function doJoin(){ showActions.value=false; if(!item.value) return; await busy.wrap('join', async () => { try{ item.value = await api.post(`/teams/${team.currentId}/items/${item.value!.id}/join`, { version: item.value!.version }); toast.show('已加入共同负责'); emit('changed'); load() }catch(e){ handleErr(e) } }) }
async function removeItem(){ showActions.value=false; if(!item.value) return; if(!confirm('确定删除该事项吗？删除后进入回收站，30 天内可恢复。')) return; try{ await api.del(`/teams/${team.currentId}/items/${item.value.id}`); toast.show('已移入回收站'); emit('changed'); emit('close') }catch(e){ handleErr(e) } }
async function submitProgress(){ if(!item.value || !pText.value.trim()){ toast.show('进展文字必填'); return }
  try{ await api.post(`/teams/${team.currentId}/items/${item.value.id}/progress`, { text:pText.value, percent:pPercent.value ?? undefined, blocked:pBlocked.value, blockReason:pBlocked.value? pBlockReason.value||undefined:undefined, mentionIds: pMentionIds.value.length ? [...pMentionIds.value] : undefined }); pText.value=''; pMentionIds.value=[]; pPercent.value=null; pBlocked.value=false; toast.show('进展已登记'); emit('changed'); load() }catch(e){ handleErr(e) } }
async function unblock(){ if(!item.value) return; try{ await api.post(`/teams/${team.currentId}/items/${item.value.id}/progress`, { text:'阻塞已解除，继续推进', blocked:false }); toast.show('已解除阻塞'); emit('changed'); load() }catch(e){ handleErr(e) } }
async function toggleCheck(c:import('../api/types').ChecklistEntry){ if(!item.value) return; const prev = c.done; c.done = !prev // 乐观更新
  try{ const e = await api.patch(`/teams/${team.currentId}/items/${item.value.id}/checklist/${c.id}`, { done: !prev }); c.done = e.done }catch(e){ c.done = prev; toast.show('勾选同步失败，已回滚'); handleErr(e) } }
async function addCheck(){ if(!item.value || !newCheck.value.trim()) return; try{ const e = await api.post(`/teams/${team.currentId}/items/${item.value.id}/checklist`, { text: newCheck.value.trim() }); checklist.value.push(e); newCheck.value='' }catch(e){ handleErr(e) } }
async function removeCheck(c:import('../api/types').ChecklistEntry){ if(!item.value) return; try{ await api.del(`/teams/${team.currentId}/items/${item.value.id}/checklist/${c.id}`); checklist.value = checklist.value.filter(x=>x.id!==c.id) }catch(e){ handleErr(e) } }
async function submitComment(){ if(!item.value || !cText.value.trim() || commentBusy.value) return; commentBusy.value = true; try{ await api.post(`/teams/${team.currentId}/items/${item.value.id}/comments`, { content:cText.value, mentionIds: [...cMentionIds.value] }); cText.value=''; cMentionIds.value=[]; emit('changed'); load() }catch(e){ handleErr(e) } finally { commentBusy.value = false } }
</script>
<style scoped>
.banner{display:flex;gap:10px;align-items:flex-start;padding:12px 14px;border-radius:12px;font-size:13.5px;margin-bottom:12px}.banner .grow{flex:1}.banner-btn{min-height:34px;padding:5px 12px}
.banner.red{background:rgba(255,59,48,.12);border:1px solid rgba(255,59,48,.35)}.banner.yellow{background:rgba(255,204,0,.16);border:1px solid rgba(230,180,0,.4)}
.quick-replies{display:flex;gap:6px;margin-top:8px;flex-wrap:wrap}.qr{border:1px solid rgba(160,120,0,.35);background:var(--card);border-radius:999px;padding:5px 12px;font-size:12px;font-weight:600;min-height:32px}
.link{color:inherit;font-weight:700;text-decoration:underline;margin-left:6px}
.head{display:flex;align-items:center;gap:8px;position:relative}
.icon{width:36px;height:36px;display:grid;place-items:center;border-radius:10px;background:var(--fill)}
.rel{position:relative}.menu{position:absolute;top:42px;right:0;z-index:5;border-radius:14px;padding:6px;min-width:150px;display:flex;flex-direction:column}
.menu.more{top:44px;right:44px}.menu button{text-align:left;padding:8px 12px;border-radius:9px}.menu button:hover{background:var(--fill)}
.title{width:100%;font-size:19px;font-weight:600;line-height:1.35;border:0;background:transparent;padding:8px 6px;margin:8px -6px 4px;border-radius:10px;outline:none}.title:hover,.title:focus{background:var(--field)}
.mainrow{display:flex;align-items:center;gap:10px;margin:8px 0 14px;position:relative}
.attrs{display:grid;grid-template-columns:1fr 1fr;gap:10px 18px;background:var(--card);border:1px solid rgba(255,255,255,.65);border-radius:var(--r-card);padding:14px;margin-bottom:4px}
.attr>span{display:block;font-size:12px;color:var(--text-2);margin-bottom:2px}.attr b{font-weight:500;display:block}
.attr .editable{cursor:pointer;border-radius:8px;padding:2px 6px;margin:-2px -6px}.attr .editable:hover{background:var(--hover)}
.attr .pen{color:var(--text-3);margin-left:6px;vertical-align:-2px}
.pop{position:absolute;top:calc(100% + 4px);left:0;z-index:40;border-radius:14px;padding:10px;min-width:230px;box-shadow:var(--shadow-lift);display:flex;flex-direction:column;gap:4px}
.poprow{display:flex;align-items:center;gap:8px;padding:6px 8px;border-radius:9px;font-size:13.5px;font-weight:500}.poprow input{width:17px;height:17px}
.popfoot{display:flex;gap:8px;justify-content:flex-end;margin-top:8px}
.avaline{display:inline-flex !important;align-items:center;gap:7px;font-weight:600 !important}
.prio{font-size:12px;padding:2px 9px;border-radius:999px;font-weight:700}.prio.P0{color:#fff;background:var(--c-red)}.prio.P1{color:#B25E00;background:rgba(255,149,0,.18)}.prio.P2{color:#0A5CD6;background:rgba(0,122,255,.12)}.prio.P3{color:var(--text-2);background:var(--track)}
.btag{font-size:11px;font-weight:600;color:#7D2CA8;background:rgba(175,82,222,.14);border-radius:6px;padding:2px 7px}
.catpill{font-size:12px;padding:2px 9px;border-radius:999px;font-weight:700;color:#fff}
.tag{font-size:12px;padding:2px 9px;border-radius:999px;background:var(--sel);color:#0A5CD6;font-weight:500;margin-right:4px}
.overdue{color:var(--c-red);font-weight:700}.parentlink{color:var(--theme);font-weight:600}
.pillbtn{display:inline-flex;align-items:center;gap:2px}
.sec-title{display:flex;align-items:center;gap:7px;font-size:15px}.sec-title .tiny{font-weight:400}
.dtabs{display:flex;gap:2px;border-bottom:1px solid var(--field-brd);margin-top:6px}
.dtabs button{padding:8px 14px;font-size:14px;font-weight:500;color:var(--text-2);border-bottom:2px solid transparent;margin-bottom:-1px;min-height:40px}
.dtabs button.on{color:var(--theme);border-bottom-color:var(--theme);font-weight:600}
.st-ico{width:22px;height:22px;border-radius:50%;display:grid;place-items:center;color:#fff;flex:none}
.child .sid{color:var(--text-2);font-size:12.5px}.child .ct{flex:1;font-weight:500}
.tl-pct{display:inline-block;font-size:12px;font-weight:600;color:#0A5CD6;background:var(--sel);padding:2px 9px;border-radius:999px}
.dfoot{flex:none;padding:12px 22px;background:var(--glass);backdrop-filter:blur(20px) saturate(180%);border-top:1px solid var(--field-brd)}
.pct-row{display:flex;align-items:center;gap:10px;margin-bottom:10px;font-size:13px}.pct-row input[type=range]{flex:1;accent-color:var(--theme);min-height:32px}
.foot-row{display:flex;gap:10px;align-items:flex-end}.foot-row textarea{flex:1;border:1px solid var(--field-brd);background:var(--field);border-radius:var(--r-ctl);padding:9px 12px;resize:none;max-height:110px;outline:none;font-size:14px}
.foot-row textarea:focus{border-color:var(--theme);box-shadow:0 0 0 3px rgba(0,122,255,.16)}
.sec{margin-top:20px}.md :deep(p){margin:6px 0}.md :deep(h2){margin:10px 0 4px}
.child{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:9px 12px;border:1px solid rgba(255,255,255,.65);background:var(--card);border-radius:12px;margin-bottom:8px;font-size:13px}.child:hover{background:var(--hover)}
.check{display:flex;gap:8px;align-items:center;padding:5px 0}.check .grow{flex:1}.icon.sm{width:26px;height:26px}.check input{width:18px;height:18px}.check .done{text-decoration:line-through;color:var(--text-2)}
.mention{color:var(--theme);font-weight:600;background:var(--sel);border-radius:5px;padding:0 3px}
.tl{margin-top:14px;display:flex;flex-direction:column;gap:14px}
.tl-item{display:flex;gap:10px}.tl-item p{margin:2px 0}.blocked-txt{color:var(--c-red);font-size:12px}
.prow{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.num{width:96px;min-height:40px;border:1px solid var(--separator);border-radius:10px;background:var(--fill);padding:6px 10px}
.flexin{flex:1;min-height:40px;border:1px solid var(--separator);border-radius:10px;background:var(--fill);padding:6px 10px;min-width:140px}
.tlist{display:flex;flex-direction:column;gap:4px;margin-top:10px}
</style>
