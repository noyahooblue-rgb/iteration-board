<template>
<div class="shell">
  <aside class="sidebar glass" :class="{mini:ui.collapsed}">
    <div class="teambox">
      <button class="team-switch press" @click="teamMenu=!teamMenu" :title="team.current?.name">
        <span class="team-mark">{{ (team.current?.name ?? '组').slice(0,1) }}</span>
        <span v-if="!ui.collapsed" class="team-txt"><b>{{ team.current?.name ?? '暂无小组' }}</b><small>代号 {{ team.current?.code ?? '—' }} · {{ themeName }}</small></span>
        <ChevronDown v-if="!ui.collapsed" :size="16" class="chev"/>
      </button>
      <div v-if="teamMenu && !ui.collapsed" class="team-menu glass">
        <button v-for="t in team.teams" :key="t.id" @click="onSwitch(t.id)">{{ t.name }}（{{ t.code }}）</button>
        <RouterLink to="/teams/new" class="tm-link">＋ 创建 / 加入小组</RouterLink>
      </div>
    </div>
    <nav>
      <RouterLink v-for="n in nav" :key="n.path" :to="n.path" class="nitem press" :class="{on:route.path===n.path}" :title="n.label">
        <component :is="n.icon" :size="19"/><span v-if="!ui.collapsed">{{n.label}}</span>
        <!-- 角标（1.0.4 A）：数据全部来自 shell 全局计数 store，任何页面都在；收起（mini）状态同样显示（绝对定位小徽章，不被 collapsed 隐藏） -->
        <span v-if="n.badge && n.badge() > 0" class="badge-n" :class="{ mini: ui.collapsed }">{{ n.badge() > 99 ? '99+' : n.badge() }}</span>
      </RouterLink>
    </nav>
    <div class="sidefoot">
      <RouterLink to="/settings" class="nitem press" :class="{on:route.path==='/settings'}" title="小组设置"><Settings :size="19"/><span v-if="!ui.collapsed">小组设置</span>
        <!-- 组长待审批加入申请数（1.0.4 A）：审批 UI 在小组设置「加入申请」页签，故角标挂在小组设置入口；数据源 GET /teams/{id}/join-requests -->
        <span v-if="shell.pendingJoins > 0" class="badge-n" :class="{ mini: ui.collapsed }">{{ shell.pendingJoins > 99 ? '99+' : shell.pendingJoins }}</span></RouterLink>
      <!-- 意见反馈（1.1.0 修订三四）：侧栏底部入口，任何页面打开抽屉 -->
      <button class="nitem press" title="意见反馈" @click="ui.feedbackOpen=true"><MessageSquarePlus :size="19"/><span v-if="!ui.collapsed">意见反馈</span></button>
      <!-- 帮助（1.1.0 修订三五.2）：打开随包离线说明书网页版（占位页，正文由文档阶段填充） -->
      <a class="nitem press" href="/help/index.html" target="_blank" rel="noopener" title="帮助"><CircleHelp :size="19"/><span v-if="!ui.collapsed">帮助</span></a>
      <!-- 平台管理入口（1.0.4 I）：仅 platformAdmin 显示 -->
      <RouterLink v-if="auth.user?.platformAdmin" to="/admin" class="nitem press" :class="{on:route.path==='/admin'}" title="平台管理"><ShieldCheck :size="19"/><span v-if="!ui.collapsed">平台管理</span></RouterLink>
      <button class="nitem press collapse" title="收起/展开侧栏（Ctrl+B）" @click="ui.toggleCollapsed()"><PanelLeft :size="19"/><span v-if="!ui.collapsed">{{ ui.collapsed ? '展开侧栏' : '收起侧栏' }}</span></button>
      <div class="me" v-if="auth.user"><Avatar :name="auth.user.displayName" :color="auth.user.avatarColor" :size="34"/>
        <span v-if="!ui.collapsed" class="me-txt"><b>{{auth.user.displayName}}</b><small>{{ roleLabel }} · {{ team.current?.name ?? '' }}</small></span>
        <span v-if="!ui.collapsed" class="me-menu-wrap"><button class="icon press" aria-label="用户菜单" @click="meMenu=!meMenu"><ChevronDown :size="17"/></button>
          <span v-if="meMenu" class="me-menu glass"><RouterLink to="/me/settings" @click="meMenu=false">个人设置</RouterLink><button @click="logout">退出登录</button></span></span></div>
    </div>
  </aside>
  <div class="main">
    <header class="topbar glass">
      <h1>{{title}}</h1>
      <button class="search press" @click="ui.searchOpen=true" aria-label="搜索（Ctrl+K）"><Search :size="16"/><span class="sph">搜索事项编号或标题</span><kbd>Ctrl K</kbd></button>
      <span v-if="auth.meta.demoMode" class="demo"><i/>演示环境 · 数据为模拟</span>
      <span class="bellwrap">
        <button class="icon press bell" aria-label="通知中心" @click="toggleBell"><Bell :size="19"/><span v-if="shell.unread>0" class="badge">{{shell.unread>99?'99+':shell.unread}}</span></button>
        <div v-if="bellOpen" class="bellpanel glass">
          <div class="bp-head"><b>通知中心</b><span class="small">{{shell.unread}} 条未读</span><span style="flex:1"/><button class="bp-link" @click="markAllRead">全部已读</button></div>
          <div class="bp-list">
            <button v-for="n in shell.notifs" :key="n.id" class="bp-item" :class="{un:n.readAt===null}" @click="openNotif(n)">
              <span class="bp-cat">{{NOTIFICATION_CATEGORY_META[n.category] ?? n.category}}</span>
              <span class="bp-body"><span class="bp-content">{{n.content}}</span><span class="bp-time">{{n.itemCode ? n.itemCode+' · ' : ''}}{{fmtDateTime(n.createdAt)}}</span></span>
              <span v-if="n.readAt===null" class="bp-dot"/>
            </button>
            <p v-if="!shell.notifs.length" class="small" style="padding:12px">暂无通知</p>
          </div>
        </div>
      </span>
      <AppButton @click="ui.createOpen=true"><Plus :size="16"/>新建</AppButton>
    </header>
    <!-- 备份过期横幅（P23）：仅平台管理员，超 3 天无成功备份时提示 -->
    <div v-if="backupOverdue" class="backup-banner">⚠ 备份提醒：{{backupMsg || '已超过 3 天没有成功备份'}}，请管理员尽快检查备份设置（平台管理 → 系统信息可查看最近备份状态）。</div>
    <main class="content"><slot/></main>
  </div>
  <FeedbackDrawer/>
  <OnboardingGuide v-if="auth.user && team.currentId" :open="onboardOpen" :user-id="auth.user.id" :team-id="team.currentId" @close="onboardOpen=false"/>
  <ReleaseNotesModal :open="notesOpen" :notes="releaseNotes" @close="notesOpen=false"/>
  <CreateItemModal @created="$emit('refresh')"/>
  <CommandPalette/>
</div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { LayoutDashboard, KanbanSquare, Inbox, IterationCw, Layers, MessagesSquare, Gauge, FileText, Search, Plus, Bell, PanelLeft, ChevronDown, Shapes, Settings, CalendarRange, Sparkles, ShieldCheck, MessageSquarePlus, CircleHelp } from 'lucide-vue-next'
import { NOTIFICATION_CATEGORY_META, type NotificationItem } from '../api/types'
import { fmtDateTime } from '../utils/datetime'
import Avatar from './Avatar.vue'; import AppButton from './AppButton.vue'; import CreateItemModal from './CreateItemModal.vue'; import CommandPalette from './CommandPalette.vue'
import FeedbackDrawer from './FeedbackDrawer.vue'; import OnboardingGuide from './OnboardingGuide.vue'; import ReleaseNotesModal from './ReleaseNotesModal.vue'
import { api } from '../api/http'
import type { BackupStatus, ReleaseNotes } from '../api/types'
import { tryDesktopNotify, playBeep } from '../utils/browserNotify'
import { onboardingSeen } from '../utils/onboarding'
import { onMounted, onUnmounted } from 'vue'
import { useAi } from '../stores/ai'; import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'; import { useShell } from '../stores/shell'
defineEmits(['refresh'])
const team = useTeam(), auth = useAuth(), ui = useUi(), toast = useToast(), route = useRoute(), router = useRouter(), aiStore = useAi(), shell = useShell()
const props = defineProps<{title:string}>()
const title = computed(()=>props.title)
const teamMenu = ref(false); const meMenu = ref(false)
const THEME_NAMES: Record<string,string> = { blue:'主题蓝', indigo:'主题靛', violet:'主题紫', pink:'主题粉', orange:'主题橙', green:'主题绿', teal:'主题青' }
const themeName = computed(()=> THEME_NAMES[team.current?.themeColor ?? 'blue'] ?? '主题蓝')
const roleLabel = computed(()=> { const r = team.current?.myRole; return r==='LEADER' ? '组长' : r==='PO' ? 'PO' : r==='MEMBER' ? '成员' : '' })
// 导航角标（1.0.4 A，数据来源见 stores/shell.ts 注释）：
// - 工作台：shell.workbenchBadge = 待我验收（acceptorId=我 且 IN_REVIEW）+ 被催待回应（我负责且 nudgeCount>0）
// - 认领池：shell.unclaimed = status=UNCLAIMED 的事项数（原 prop 传法已废弃，全局 store 维护，任何页面可见）
const nav = computed(() => [
  { path:'/workbench', label:'工作台', icon:LayoutDashboard, badge: () => shell.workbenchBadge },
  { path:'/board', label:'看板', icon:KanbanSquare },
  { path:'/pool', label:'认领池', icon:Inbox, badge: () => shell.unclaimed },
  { path:'/planning', label:'迭代规划', icon:IterationCw },
  { path:'/backlog', label:'待办池', icon:Layers },
  { path:'/iterations', label:'迭代', icon:CalendarRange },
  { path:'/standup', label:'站会', icon:MessagesSquare },
  { path:'/overview', label:'总览', icon:Gauge },
  { path:'/reports', label:'报告', icon:FileText },
  { path:'/search', label:'搜索', icon:Search },
  { path:'/qa', label:'智能问答', icon:Sparkles },
  // 组件展示（1.0.4 C）：构建期 __IB_SHOWCASE__ 为 false 时本项与字符串被静态消除，不进正式 dist；
  // 构建包含时再叠运行时条件——仅演示模式（auth.meta.demoMode）或 DEV 显示。
  ...(__IB_SHOWCASE__ && (auth.meta.demoMode || import.meta.env.DEV) ? [{ path:'/showcase', label:'组件展示', icon:Shapes }] : []),
] as { path: string; label: string; icon: any; badge?: () => number }[])
function isTypingTarget(e: KeyboardEvent){ const t = e.target as HTMLElement | null; return !!t && (/INPUT|TEXTAREA|SELECT/.test(t.tagName) || t.isContentEditable) }
function onKey(e: KeyboardEvent){
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); ui.searchOpen = true }
  // Ctrl+B 切换侧栏（1.0.4 E）：输入框/文本域聚焦时不触发，避免与编辑快捷键冲突。
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b' && !isTypingTarget(e)) { e.preventDefault(); ui.toggleCollapsed() }
}
// ---------- 通知中心（M3）：未读数/列表/已读在 shell store（1.0.4 并入全局计数），SSE 实时推送也在 store ----------
const bellOpen = ref(false)
function toggleBell(){ bellOpen.value = !bellOpen.value; if (bellOpen.value) { shell.loadNotifs(); shell.loadUnread() } }
async function openNotif(n: NotificationItem){ await shell.markRead(n); bellOpen.value = false; if (n.itemId) router.push(`/task/${n.itemId}`) }
function navigateNotif(n: any){ if (n?.itemId) router.push(`/task/${n.itemId}`) }
async function markAllRead(){ const ok = await shell.markAllRead(); toast.show(ok ? '已全部标为已读' : '操作失败') }
// 路由变化刷新一次计数（1.0.4 A）：进入相应页面/处理后角标随之减少。
watch(() => route.path, () => { shell.refresh() })
watch(() => team.currentId, () => { shell.refresh() })
onMounted(async () => {
  window.addEventListener('keydown', onKey)
  ui.hydrate(auth.user?.id ?? null)
  // 全局元素兜底（1.0.4 B）：路由守卫已为 team 路由统一 loadTeams；这里再确保一次，刷新/书签直开任意页都正确。
  if (auth.user && !team.teams.length) { try { await team.loadTeams() } catch {} }
  shell.refresh()
  shell.loadNotifPrefs()
  shell.start((n: any) => { if (bellOpen.value) shell.loadNotifs(); if (n?.content) toast.show('新通知：' + n.content); tryDesktopNotify(n, navigateNotif); playBeep() })
  checkBackup(); checkReleaseNotes(); checkOnboarding()
  if (team.currentId) aiStore.load(team.currentId)
})
onUnmounted(()=> { window.removeEventListener('keydown', onKey) })
// ---------- 1.1.0 外壳层全局件：备份横幅 / 更新说明 / 新手引导 ----------
const backupOverdue = ref(false), backupMsg = ref('')
async function checkBackup(){ if (!auth.user?.platformAdmin) return; try { const b: BackupStatus = await api.get('/admin/backup-status'); backupOverdue.value = !!b.backupStale; backupMsg.value = b.backupStaleMessage ?? '' } catch {} }
const releaseNotes = ref<ReleaseNotes | null>(null), notesOpen = ref(false)
async function checkReleaseNotes(){ try { const rn: ReleaseNotes = await api.get('/release-notes'); releaseNotes.value = rn; if (!rn.read) notesOpen.value = true } catch {} }
watch(() => ui.releaseNotesOpen, (v) => { if (v) { ui.releaseNotesOpen = false; if (releaseNotes.value) notesOpen.value = true; else checkReleaseNotes().then(() => { notesOpen.value = true }) } })
const onboardOpen = ref(false)
function checkOnboarding(){ if (auth.user && team.currentId && team.current?.myRole && !onboardingSeen(auth.user.id, team.currentId)) onboardOpen.value = true }
watch(() => team.currentId, () => { checkOnboarding() })
async function onSwitch(id:number){ teamMenu.value=false; if(id!==team.currentId){ await team.switchTeam(id); router.go(0) } }
async function logout(){ await auth.logout(); router.push('/login') }
</script>
<style scoped>
.shell{display:flex;gap:16px;padding:16px;min-height:100dvh}
.sidebar{width:232px;flex:none;border-radius:var(--r-panel);padding:16px 12px 12px;display:flex;flex-direction:column;gap:6px;position:sticky;top:16px;height:calc(100dvh - 32px)}
.sidebar.mini{width:64px;align-items:center}
.teambox{position:relative}
.team-switch{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:8px;border-radius:14px;min-height:52px}
.team-switch:hover{background:var(--hover)}
.team-mark{width:36px;height:36px;border-radius:11px;flex:none;display:grid;place-items:center;color:#fff;font-weight:700;font-size:15px;background:linear-gradient(140deg,#2E9BFF,#007AFF 60%,#5856D6);box-shadow:inset 0 1px 0 rgba(255,255,255,.5)}
.team-txt{display:flex;flex-direction:column;min-width:0;flex:1}.team-txt b{font-size:14px}.team-txt small{color:var(--text-3);font-size:12px}
.chev{color:var(--text-3);flex:none}
.team-menu{position:absolute;top:60px;left:0;right:0;z-index:30;border-radius:14px;padding:6px;display:flex;flex-direction:column}
.team-menu button{text-align:left;padding:8px 10px;border-radius:9px;font-weight:600}
.team-menu button:hover{background:var(--hover)}
.tm-link{padding:8px 10px;font-size:13px;color:var(--text-2);text-decoration:none;border-top:1px solid var(--field-brd);margin-top:4px}
.icon{width:38px;height:38px;display:grid;place-items:center;border-radius:11px;position:relative;flex:none;color:var(--text-2)}
.icon:hover{background:var(--hover);color:var(--text)}
nav{display:flex;flex-direction:column;gap:2px;margin-top:8px}
.nitem{display:flex;align-items:center;gap:11px;padding:8px 10px;border-radius:12px;color:var(--text-2);font-weight:500;font-size:14px;text-decoration:none;position:relative;min-height:40px;text-align:left}
.nitem:hover{background:var(--hover);color:var(--text)}
.nitem.on{background:var(--theme);color:#fff;box-shadow:0 6px 14px rgba(0,122,255,.3)}
.nitem.on:hover{background:var(--theme);color:#fff}
.badge-n{margin-left:auto;background:var(--c-adhoc);color:#fff;font-size:11px;border-radius:99px;padding:0 7px}
/* mini（收起）状态角标（1.0.4 A/E）：绝对定位到图标右上角，小圆徽章，收起时依然可见 */
.badge-n.mini{position:absolute;top:2px;right:2px;margin-left:0;min-width:17px;height:17px;display:grid;place-items:center;padding:0 4px;font-size:10px;font-weight:700;box-shadow:0 0 0 2px var(--glass)}
.sidefoot{margin-top:auto;padding-top:10px;border-top:1px solid var(--field-brd);display:flex;flex-direction:column;gap:4px}
.me{display:flex;align-items:center;gap:9px;padding:6px 4px;position:relative}
.me-menu-wrap{position:relative}.me-menu{position:absolute;bottom:44px;right:0;z-index:40;border-radius:13px;padding:6px;display:flex;flex-direction:column;min-width:132px}
.me-menu a,.me-menu button{text-align:left;padding:8px 10px;border-radius:9px;text-decoration:none;color:var(--text);font-weight:500;font-size:13.5px}
.me-menu a:hover,.me-menu button:hover{background:var(--hover)}
.me-txt{display:flex;flex-direction:column;flex:1;min-width:0}.me-txt b{font-size:13.5px}.me-txt small{color:var(--text-3);font-size:12px}
.main{flex:1;min-width:0;display:flex;flex-direction:column;gap:16px}
.topbar{border-radius:20px;padding:10px 14px;display:flex;align-items:center;gap:12px;position:sticky;top:16px;z-index:20}
.topbar h1{font-size:19px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.search{margin-left:auto;width:min(300px,34vw);display:flex;align-items:center;gap:8px;background:var(--field);border:1px solid var(--field-brd);border-radius:12px;padding:0 12px;color:var(--text-3);min-height:40px;text-align:left}
.search .sph{flex:1;font-size:13px;white-space:nowrap;overflow:hidden}
.search kbd{font-size:11px;background:var(--track);border-radius:6px;padding:2px 6px;font-family:inherit}
.search input{flex:1;min-height:38px;border:0;background:transparent;outline:none;font-size:13px;min-width:0}
.demo{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--text-2);background:var(--field);border:1px solid var(--field-brd);padding:5px 10px;border-radius:99px;white-space:nowrap}
.demo i{width:7px;height:7px;border-radius:50%;background:var(--c-yellow);box-shadow:0 0 0 3px rgba(255,204,0,.25);flex:none}
.bellwrap{position:relative;flex:none}
.bell .badge{position:absolute;top:2px;right:2px;min-width:17px;height:17px;border-radius:99px;background:var(--c-red);color:#fff;font-size:10.5px;font-weight:700;display:grid;place-items:center;padding:0 4px;box-shadow:0 0 0 2px var(--glass)}
.bellpanel{position:absolute;top:48px;right:0;width:min(380px,90vw);z-index:60;border-radius:16px;padding:10px;box-shadow:var(--shadow-lift)}
.bp-head{display:flex;align-items:center;gap:8px;padding:2px 6px 8px}
.bp-link{color:var(--theme);font-weight:600;font-size:13px}
.bp-list{max-height:420px;overflow-y:auto;display:flex;flex-direction:column}
.bp-item{display:flex;gap:9px;align-items:flex-start;text-align:left;padding:9px 8px;border-radius:11px;width:100%}
.bp-item:hover{background:var(--hover)}
.bp-item.un{background:color-mix(in srgb, var(--theme) 6%, transparent)}
.bp-cat{flex:none;font-size:11px;font-weight:700;color:#0A5CD6;background:var(--sel);border-radius:6px;padding:2px 6px;margin-top:1px}
.bp-body{flex:1;display:flex;flex-direction:column;gap:2px;min-width:0}
.bp-content{font-size:13.5px;line-height:1.45}
.bp-time{font-size:11.5px;color:var(--text-3)}
.bp-dot{flex:none;width:8px;height:8px;border-radius:50%;background:var(--c-red);margin-top:6px}
.content{min-width:0}
.backup-banner{background:rgba(255,59,48,.12);border:1px solid rgba(255,59,48,.4);color:var(--c-red);border-radius:14px;padding:10px 14px;font-size:13.5px;font-weight:600}
@media(max-width:820px){
  .shell{flex-direction:column;padding:10px;gap:10px}
  .sidebar{flex-direction:row;align-items:center;width:100%;height:auto;position:static;padding:8px 10px;gap:6px;overflow-x:auto}
  .team-switch{min-height:0;padding:4px}.team-txt{display:none}.chev{display:none}
  nav{flex-direction:row;margin:0;gap:2px}
  .nitem span:not(.badge-n){display:none}
  .sidefoot{margin:0 0 0 auto;padding:0;border:0;flex-direction:row;align-items:center}
  .sidefoot .collapse{display:none}.me-txt{display:none}.me{padding:2px}
  .topbar{position:static;top:auto}
  .demo{display:none}.search{display:none}
}
</style>
