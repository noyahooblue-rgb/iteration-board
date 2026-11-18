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
        <span v-if="n.path==='/pool' && unclaimed && !ui.collapsed" class="badge-n">{{unclaimed}}</span>
      </RouterLink>
    </nav>
    <div class="sidefoot">
      <RouterLink to="/settings" class="nitem press" :class="{on:route.path==='/settings'}" title="小组设置"><Settings :size="19"/><span v-if="!ui.collapsed">小组设置</span></RouterLink>
      <button class="nitem press collapse" @click="ui.collapsed=!ui.collapsed"><PanelLeft :size="19"/><span v-if="!ui.collapsed">{{ ui.collapsed ? '展开侧栏' : '收起侧栏' }}</span></button>
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
        <button class="icon press bell" aria-label="通知中心" @click="toggleBell"><Bell :size="19"/><span v-if="unread>0" class="badge">{{unread>99?'99+':unread}}</span></button>
        <div v-if="bellOpen" class="bellpanel glass">
          <div class="bp-head"><b>通知中心</b><span class="small">{{unread}} 条未读</span><span style="flex:1"/><button class="bp-link" @click="markAllRead">全部已读</button></div>
          <div class="bp-list">
            <button v-for="n in notifs" :key="n.id" class="bp-item" :class="{un:n.readAt===null}" @click="openNotif(n)">
              <span class="bp-cat">{{NOTIFICATION_CATEGORY_META[n.category] ?? n.category}}</span>
              <span class="bp-body"><span class="bp-content">{{n.content}}</span><span class="bp-time">{{n.itemCode ? n.itemCode+' · ' : ''}}{{fmtTime(n.createdAt)}}</span></span>
              <span v-if="n.readAt===null" class="bp-dot"/>
            </button>
            <p v-if="!notifs.length" class="small" style="padding:12px">暂无通知</p>
          </div>
        </div>
      </span>
      <AppButton @click="ui.createOpen=true"><Plus :size="16"/>新建</AppButton>
    </header>
    <main class="content"><slot/></main>
  </div>
  <CreateItemModal @created="$emit('refresh')"/>
  <CommandPalette/>
</div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { LayoutDashboard, KanbanSquare, Inbox, IterationCw, Layers, MessagesSquare, Gauge, FileText, Search, Plus, Bell, PanelLeft, ChevronDown, LogOut, Shapes, Settings, CalendarRange, Sparkles } from 'lucide-vue-next'
import { api, subscribeEvents } from '../api/http'; import { NOTIFICATION_CATEGORY_META, type NotificationItem } from '../api/types'
import Avatar from './Avatar.vue'; import AppButton from './AppButton.vue'; import CreateItemModal from './CreateItemModal.vue'; import CommandPalette from './CommandPalette.vue'
import { onMounted, onUnmounted } from 'vue'
import { useAi } from '../stores/ai'; import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'
defineEmits(['refresh'])
const team = useTeam(), auth = useAuth(), ui = useUi(), toast = useToast(), route = useRoute(), router = useRouter(), aiStore = useAi()
const props = defineProps<{title:string; unclaimed?:number}>()
const title = computed(()=>props.title)
const teamMenu = ref(false); const meMenu = ref(false)
const THEME_NAMES: Record<string,string> = { blue:'主题蓝', indigo:'主题靛', violet:'主题紫', pink:'主题粉', orange:'主题橙', green:'主题绿', teal:'主题青' }
const themeName = computed(()=> THEME_NAMES[team.current?.themeColor ?? 'blue'] ?? '主题蓝')
const roleLabel = computed(()=> { const r = team.current?.myRole; return r==='LEADER' ? '组长' : r==='PO' ? 'PO' : r==='MEMBER' ? '成员' : '' })
const nav = [
  { path:'/workbench', label:'工作台', icon:LayoutDashboard }, { path:'/board', label:'看板', icon:KanbanSquare },
  { path:'/pool', label:'认领池', icon:Inbox }, { path:'/planning', label:'迭代规划', icon:IterationCw },
  { path:'/backlog', label:'待办池', icon:Layers }, { path:'/iterations', label:'迭代', icon:CalendarRange },
  { path:'/standup', label:'站会', icon:MessagesSquare },
  { path:'/overview', label:'总览', icon:Gauge }, { path:'/reports', label:'报告', icon:FileText },
  { path:'/search', label:'搜索', icon:Search }, { path:'/qa', label:'智能问答', icon:Sparkles },
  { path:'/showcase', label:'组件展示', icon:Shapes },
]
function onKey(e: KeyboardEvent){ if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); ui.searchOpen = true } if (e.key === 'c' && !ui.createOpen && !/INPUT|TEXTAREA|SELECT/.test((e.target as HTMLElement)?.tagName ?? '')) { /* C 新建由各页自行处理，避免误触 */ } }
// ---------- 通知中心（M3）：未读数 + 列表 + 已读 + SSE 实时推送 ----------
const bellOpen = ref(false), unread = ref(0), notifs = ref<NotificationItem[]>([])
let unsub: (() => void) | null = null
const fmtTime = (s:string)=> s ? s.slice(0,16).replace('T',' ') : ''
async function loadUnread(){ if (!team.currentId) return; try { const r = await api.get(`/notifications/unread-count?teamId=${team.currentId}`); unread.value = r.unread ?? 0 } catch {} }
async function loadNotifs(){ if (!team.currentId) return; try { notifs.value = await api.get(`/notifications?teamId=${team.currentId}&limit=30`) } catch {} }
function toggleBell(){ bellOpen.value = !bellOpen.value; if (bellOpen.value) { loadNotifs(); loadUnread() } }
async function openNotif(n: NotificationItem){ if (n.readAt === null) { try { await api.post(`/notifications/${n.id}/read`); n.readAt = new Date().toISOString(); unread.value = Math.max(0, unread.value - 1) } catch {} } }
async function markAllRead(){ try { await api.post('/notifications/read-all', { teamId: team.currentId }); notifs.value.forEach(n => n.readAt = n.readAt ?? new Date().toISOString()); unread.value = 0; toast.show('已全部标为已读') } catch { toast.show('操作失败') } }
onMounted(()=> { window.addEventListener('keydown', onKey); loadUnread(); if (team.currentId) aiStore.load(team.currentId)
  unsub = subscribeEvents((n:any) => { unread.value += 1; if (bellOpen.value) loadNotifs(); if (n?.content) toast.show('新通知：' + n.content) })
})
onUnmounted(()=> { window.removeEventListener('keydown', onKey); if (unsub) unsub() })
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
@media(max-width:820px){
  .shell{flex-direction:column;padding:10px;gap:10px}
  .sidebar{flex-direction:row;align-items:center;width:100%;height:auto;position:static;padding:8px 10px;gap:6px;overflow-x:auto}
  .team-switch{min-height:0;padding:4px}.team-txt{display:none}.chev{display:none}
  nav{flex-direction:row;margin:0;gap:2px}
  .nitem span{display:none}
  .sidefoot{margin:0 0 0 auto;padding:0;border:0;flex-direction:row;align-items:center}
  .sidefoot .collapse{display:none}.me-txt{display:none}.me{padding:2px}
  .topbar{position:static;top:auto}
  .demo{display:none}.search{display:none}
}
</style>
