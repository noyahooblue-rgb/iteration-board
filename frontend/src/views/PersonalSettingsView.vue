<template>
<AppShell title="个人设置">
  <GlassPanel class="body">
    <h2>账号</h2>
    <div class="mrow"><Avatar v-if="auth.user" :name="auth.user.displayName" :color="auth.user.avatarColor" :size="38"/><span class="grow"><b>{{auth.user?.displayName}}</b><br/><span class="small">登录名 @{{auth.user?.loginName}}（注册后不可修改）· 当前小组角色：{{roleName}} · 平台角色：{{ auth.user?.platformAdmin ? '平台管理员' : '普通用户' }}</span></span></div>
    <h2 style="margin-top:22px">修改密码</h2>
    <p class="small">新密码至少 8 位且同时含字母和数字。修改成功后，其他设备的登录会话会失效。</p>
    <form @submit.prevent="submit" style="max-width:420px;margin-top:12px">
      <AppInput v-model="f.old" type="password" label="当前密码" placeholder="请输入当前密码"/>
      <AppInput v-model="f.next" type="password" label="新密码" placeholder="至少 8 位，含字母和数字" style="margin-top:10px"/>
      <AppInput v-model="f.confirm" type="password" label="确认新密码" placeholder="再输入一次新密码" style="margin-top:10px"/>
      <p v-if="err" class="err">{{err}}</p>
      <AppButton style="margin-top:14px" @click="submit">保存新密码</AppButton>
    </form>
    <h2 style="margin-top:22px">我的成员状态</h2>
    <p class="small">请假/出差等状态会影响催更（改催组长）与迭代容量计算。</p>
    <div class="frow">
      <label class="small">状态<select v-model="myStatus.status" class="flexin"><option value="NORMAL">正常</option><option value="LEAVE">请假</option><option value="TRAVEL">出差</option><option value="SECONDMENT">外借</option></select></label>
      <label class="small">开始<input type="date" v-model="myStatus.start" class="flexin"/></label>
      <label class="small">结束<input type="date" v-model="myStatus.end" class="flexin"/></label>
      <AppButton variant="ghost" @click="saveMyStatus">保存状态</AppButton>
    </div>
    <h2 style="margin-top:22px">外观</h2>
    <p class="small">仅保存在本机浏览器，立即生效；真实与演示环境行为一致。</p>
    <div class="prefrow"><span class="grow"><b>主题模式</b><br/><span class="tiny">默认跟随系统，也可固定浅色或深色</span></span>
      <div class="seg" role="group" aria-label="主题模式">
        <button v-for="o in themeOpts" :key="o.v" class="press" :class="{on: appearance.theme===o.v}" @click="setAppearance({theme:o.v})">{{o.label}}</button>
      </div></div>
    <div class="prefrow"><span class="grow"><b>玻璃强度</b><br/><span class="tiny">调整面板的通透程度与背景模糊</span></span>
      <div class="seg" role="group" aria-label="玻璃强度">
        <button v-for="o in glassOpts" :key="o.v" class="press" :class="{on: appearance.glass===o.v}" @click="setAppearance({glass:o.v})">{{o.label}}</button>
      </div></div>
    <div class="prefrow"><span class="grow"><b>减少透明度</b><br/><span class="tiny">玻璃面板改为不透明实底</span></span>
      <label class="switch"><input type="checkbox" :checked="appearance.reduceTransparency" @change="setAppearance({reduceTransparency: ($event.target as HTMLInputElement).checked})"/><span/></label></div>
    <div class="prefrow"><span class="grow"><b>减少动效</b><br/><span class="tiny">动画与过渡降级为淡入淡出（系统「减少动态效果」同样生效）</span></span>
      <label class="switch"><input type="checkbox" :checked="appearance.reduceMotion" @change="setAppearance({reduceMotion: ($event.target as HTMLInputElement).checked})"/><span/></label></div>
    <h2 style="margin-top:22px">通知偏好</h2>
    <p class="small">指派类与催办类通知不可关闭（保证工作不漏接）；其余类别可按需开关。</p>
    <div v-for="c in prefCats" :key="c.key" class="prefrow">
      <span class="grow"><b>{{c.label}}</b><br/><span class="tiny">{{c.desc}}<span v-if="prefs.COMPACT_NOTIFICATIONS && c.key==='REMINDER'">（精简通知已开启，此类不发送）</span></span></span>
      <label class="switch"><input type="checkbox" :checked="prefs[c.key]" :disabled="c.locked || (!!prefs.COMPACT_NOTIFICATIONS && c.key==='REMINDER')" @change="togglePref(c.key, ($event.target as HTMLInputElement).checked)"/><span/></label>
    </div>
    <div class="prefrow">
      <span class="grow"><b>精简通知</b><br/><span class="tiny">开启后只收六类：指派给我、邀请我认领、@我、催我、待我验收、加入申请；同一事项 5 分钟内的多条变化会合并成一条</span></span>
      <label class="switch"><input type="checkbox" :checked="!!prefs.COMPACT_NOTIFICATIONS" @change="togglePref('COMPACT_NOTIFICATIONS', ($event.target as HTMLInputElement).checked)"/><span/></label>
    </div>
    <h2 style="margin-top:22px">浏览器提醒</h2>
    <p class="small">标签页标题未读数与图标红点角标对所有人自动生效，不受下面开关影响。桌面通知与提示音为可选项：</p>
    <div class="prefrow">
      <span class="grow"><b>桌面通知</b><br/><span class="tiny">仅对「指派给我 / 邀请我认领 / @我 / 催我 / 待我验收」五类弹出系统通知，点击直达对应事项；平台标签页在前台时不弹，同一事项 30 秒内只弹一条。</span></span>
      <label class="switch"><input type="checkbox" :checked="!!prefs.DESKTOP_NOTIFICATIONS" :disabled="!secureOk" @change="toggleDesktop(($event.target as HTMLInputElement).checked)"/><span/></label>
    </div>
    <p v-if="!secureOk" class="errbox2">当前访问地址不是安全来源（通过局域网 IP 用 http 访问时，浏览器不允许使用桌面通知），开关已置灰。请管理员按部署手册为平台启用 HTTPS 后再开启；标题未读数与图标角标不受影响，照常工作。</p>
    <p class="tiny">注意：只有平台网页处于打开状态（含后台标签页）时才能收到桌面通知，关掉浏览器就收不到；建议把平台固定为浏览器标签页。</p>
    <div class="prefrow">
      <span class="grow"><b>提示音</b><br/><span class="tiny">有新通知时播放一声短提示音（默认关闭）</span></span>
      <label class="switch"><input type="checkbox" :checked="!!prefs.NOTIFICATION_SOUND" @change="togglePref('NOTIFICATION_SOUND', ($event.target as HTMLInputElement).checked)"/><span/></label>
    </div>
    <template v-if="myStatus.status==='LEAVE'">
      <h2 style="margin-top:22px">请假期间到期事项</h2>
      <p class="small">以下事项在你的请假期间到期，请提前转交或请组长协助跟进：</p>
      <p v-if="leaveDueLoading" class="small">加载中…</p>
      <div v-for="it in leaveDue" :key="it.itemId" class="mrow"><span class="grow"><b>{{it.code}}</b> {{it.title}}<br/><span class="tiny">截止 {{it.dueDate}}</span></span><span class="tiny">请在事项详情里转交</span></div>
      <p v-if="!leaveDueLoading && !leaveDue.length" class="small">请假期间没有到期事项。</p>
    </template>
  </GlassPanel>
</AppShell>
</template>
<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import AppShell from '../components/AppShell.vue'; import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'; import Avatar from '../components/Avatar.vue'
import { api, ApiError } from '../api/http'; import { useAuth } from '../stores/auth'; import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const auth = useAuth(), team = useTeam(), toast = useToast()
// 外观（M5）：本地偏好，改动即时生效并持久化（localStorage，不走后端）
import { loadAppearance, saveAppearance, type Appearance, type ThemeMode, type GlassLevel } from '../utils/appearance'
const appearance = reactive<Appearance>(loadAppearance())
const themeOpts = [{ v: 'system', label: '跟随系统' }, { v: 'light', label: '浅色' }, { v: 'dark', label: '深色' }] as { v: ThemeMode; label: string }[]
const glassOpts = [{ v: 'clear', label: '通透' }, { v: 'standard', label: '标准' }, { v: 'frosted', label: '磨砂' }] as { v: GlassLevel; label: string }[]
function setAppearance(patch: Partial<Appearance>){ Object.assign(appearance, patch); saveAppearance({ ...appearance }) }
const f = reactive({ old: '', next: '', confirm: '' }); const err = ref('')
// M3：通知偏好 + 我的成员状态
import type { NotificationPrefs } from '../api/types'
const prefs = reactive<NotificationPrefs>({ ASSIGNMENT: true, NUDGE: true, MENTION: true, ITEM_UPDATE: true, REMINDER: true, SYSTEM: true, COMPACT_NOTIFICATIONS: false, DESKTOP_NOTIFICATIONS: false, NOTIFICATION_SOUND: false })
// 桌面通知安全来源（1.1.0）：非 secure context（局域网 http）时开关置灰；localhost 与 HTTPS 为安全来源。
const secureOk = typeof window !== 'undefined' && window.isSecureContext
import { saveNotifyPrefs } from '../utils/browserNotify'
import { useShell } from '../stores/shell'
import type { Item } from '../api/types'
const leaveDue = ref<{ itemId: number; code: string; title: string; status: string; dueDate: string }[]>([]), leaveDueLoading = ref(false)
async function loadLeaveDue(){ if (!auth.user || !team.currentId) return; leaveDueLoading.value = true
  try { const qs = new URLSearchParams(); if (myStatus.start) qs.set('start', myStatus.start); if (myStatus.end) qs.set('end', myStatus.end)
    const r = await api.get(`/teams/${team.currentId}/members/${auth.user.id}/leave-due-items?${qs}`); leaveDue.value = Array.isArray(r) ? r : (r.items ?? []) } catch { leaveDue.value = [] } finally { leaveDueLoading.value = false } }
async function toggleDesktop(v: boolean){
  if (v) {
    if (!secureOk) { toast.show('当前不是安全来源，无法开启桌面通知'); return }
    if (!('Notification' in window)) { toast.show('当前浏览器不支持桌面通知'); (prefs as any).DESKTOP_NOTIFICATIONS = false; return }
    const perm = await Notification.requestPermission()
    if (perm !== 'granted') { toast.show('浏览器未授予通知权限，桌面通知未开启'); (prefs as any).DESKTOP_NOTIFICATIONS = false; return }
  }
  (prefs as any).DESKTOP_NOTIFICATIONS = v
  saveNotifyPrefs({ desktop: v, sound: !!prefs.NOTIFICATION_SOUND }); useShell().loadNotifPrefs(); toast.show('浏览器提醒设置已保存')
}
const prefCats = [
  { key: 'ASSIGNMENT', label: '指派类', desc: '被指派、认领、转交、邀请（不可关闭）', locked: true },
  { key: 'NUDGE', label: '催办类', desc: '催一下与催的回应（不可关闭）', locked: true },
  { key: 'MENTION', label: '@提及', desc: '评论里被 @ 到', locked: false },
  { key: 'ITEM_UPDATE', label: '事项动态', desc: '我关注事项的状态变化、验收结果', locked: false },
  { key: 'REMINDER', label: '自动提醒', desc: '未更新、到期、逾期、阻塞等自动提醒', locked: false },
  { key: 'SYSTEM', label: '系统', desc: '迭代开始/结束、成员变动等系统消息', locked: false },
] as const
const myStatus = reactive({ status: 'NORMAL', start: '', end: '' })
onMounted(async ()=> { try { const p = await api.get('/auth/notification-prefs'); Object.assign(prefs, p ?? {}) } catch {}
  // 精简通知（P25）以后端 notification-settings 为准：{categories, compactNotification}
  try { const st = await api.get('/auth/notification-settings'); if (st?.categories) Object.assign(prefs, st.categories); (prefs as any).COMPACT_NOTIFICATIONS = !!st?.compactNotification } catch {}
  const me = team.members.find(m=>m.userId===auth.user?.id); if (me) { myStatus.status = me.memberStatus; myStatus.start = me.memberStatusStart ?? ''; myStatus.end = me.memberStatusEnd ?? '' } if (myStatus.status==='LEAVE') loadLeaveDue() })
const CAT_KEYS = ['ASSIGNMENT','NUDGE','MENTION','ITEM_UPDATE','REMINDER','SYSTEM']
async function togglePref(key: string, v: boolean){ (prefs as any)[key] = v
  try {
    if (key === 'COMPACT_NOTIFICATIONS') { await api.patch('/auth/notification-settings', { compactNotification: v }) }
    else if (CAT_KEYS.includes(key)) { const cats: Record<string, boolean> = {}; for (const k of CAT_KEYS) cats[k] = !!(prefs as any)[k]; await api.patch('/auth/notification-prefs', cats) }
    // 桌面通知/提示音为浏览器本地偏好（后端无此字段），只存 localStorage
    saveNotifyPrefs({ desktop: !!prefs.DESKTOP_NOTIFICATIONS, sound: !!prefs.NOTIFICATION_SOUND }); useShell().loadNotifPrefs()
    toast.show('通知偏好已保存')
  } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
async function saveMyStatus(){ if (!auth.user) return; try { await api.patch(`/teams/${team.currentId}/members/${auth.user.id}/status`, { memberStatus: myStatus.status, memberStatusStart: myStatus.start || null, memberStatusEnd: myStatus.end || null }); toast.show('成员状态已保存'); await team.loadTeamData(); if (myStatus.status==='LEAVE') loadLeaveDue() } catch(e){ toast.show(e instanceof ApiError ? e.message : '保存失败') } }
const roleName = computed(()=> { const r = team.current?.myRole; return r==='LEADER' ? '组长' : r==='PO' ? 'PO' : r==='MEMBER' ? '成员' : '—' })
async function submit(){
  err.value = ''
  if (!f.old) { err.value = '请输入当前密码'; return }
  if (!(f.next.length >= 8 && /[A-Za-z]/.test(f.next) && /\d/.test(f.next))) { err.value = '新密码至少 8 位且同时含字母和数字'; return }
  if (f.next !== f.confirm) { err.value = '两次输入的新密码不一致'; return }
  try { await api.post('/auth/change-password', { oldPassword: f.old, newPassword: f.next }); toast.show('密码已修改'); f.old = f.next = f.confirm = ''; if (auth.user) auth.user.mustChangePassword = false }
  catch(e){ err.value = e instanceof ApiError ? e.message : '修改失败' }
}
</script>
<style scoped>
.body{padding:20px;max-width:760px}
.mrow{display:flex;align-items:center;gap:12px;margin-top:10px}.mrow .grow{flex:1}
.err{color:var(--c-red);font-size:12px;margin-top:10px}
.errbox2{color:var(--c-red);background:rgba(255,59,48,.08);border-radius:10px;padding:9px 12px;font-size:13px;margin-top:10px;line-height:1.6}
.frow{display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap;margin-top:10px}.frow label{display:flex;flex-direction:column;gap:6px}
.flexin{min-height:40px;border:1px solid var(--field-brd);border-radius:10px;background:var(--field);padding:6px 10px}
.prefrow{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--field-brd)}.prefrow .grow{flex:1}
.switch{position:relative;width:48px;height:28px;flex:none}.switch input{opacity:0;width:0;height:0}.switch span{position:absolute;inset:0;background:var(--track);border-radius:99px;transition:.2s;cursor:pointer}.switch span:before{content:'';position:absolute;width:22px;height:22px;border-radius:50%;background:#fff;top:3px;left:3px;transition:.2s;box-shadow:0 1px 4px rgba(0,0,0,.25)}
.switch input:checked + span{background:var(--c-green)}.switch input:checked + span:before{transform:translateX(20px)}
.switch input:disabled + span{opacity:.55;cursor:not-allowed}
.seg{display:flex;background:var(--track);border-radius:999px;padding:3px;gap:2px;flex:none}
.seg button{padding:6px 14px;border-radius:999px;font-size:13px;font-weight:500;color:var(--text-2);min-height:32px}
.seg button.on{background:var(--card-solid);color:var(--text);box-shadow:0 1px 4px rgba(10,40,90,.18);font-weight:600}
</style>
