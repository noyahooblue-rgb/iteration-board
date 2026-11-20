import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../stores/auth'; import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/workbench' },
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/teams/new', component: () => import('../views/TeamsNewView.vue'), meta: { auth: true } },
  { path: '/teams/pending', component: () => import('../views/TeamsPendingView.vue'), meta: { auth: true } },
  { path: '/settings', component: () => import('../views/TeamSettingsView.vue'), meta: { auth: true, team: true } },
  { path: '/me/settings', component: () => import('../views/PersonalSettingsView.vue'), meta: { auth: true, team: true } },
  { path: '/board', component: () => import('../views/BoardView.vue'), meta: { auth: true, team: true } },
  { path: '/workbench', component: () => import('../views/WorkbenchView.vue'), meta: { auth: true, team: true } },
  { path: '/pool', component: () => import('../views/PoolView.vue'), meta: { auth: true, team: true } },
  { path: '/planning', component: () => import('../views/PlanningView.vue'), meta: { auth: true, team: true } },
  { path: '/iterations', component: () => import('../views/IterationsView.vue'), meta: { auth: true, team: true } },
  { path: '/iterations/:id', component: () => import('../views/IterationsView.vue'), meta: { auth: true, team: true } },
  { path: '/standup', component: () => import('../views/StandupView.vue'), meta: { auth: true, team: true } },
  { path: '/overview', component: () => import('../views/OverviewView.vue'), meta: { auth: true, team: true } },
  { path: '/qa', component: () => import('../views/QaView.vue'), meta: { auth: true, team: true } },
  { path: '/reports', component: () => import('../views/ReportsView.vue'), meta: { auth: true, team: true } },
  { path: '/backlog', component: () => import('../views/BacklogView.vue'), meta: { auth: true, team: true } },
  { path: '/task/:id', component: () => import('../views/TaskDetailView.vue'), meta: { auth: true, team: true } },
  { path: '/search', component: () => import('../views/SearchView.vue'), meta: { auth: true, team: true } },
  { path: '/trash', component: () => import('../views/TrashView.vue'), meta: { auth: true, team: true } },
  { path: '/admin', component: () => import('../views/AdminView.vue'), meta: { auth: true, admin: true } },
  { path: '/feedback', component: () => import('../views/FeedbackView.vue'), meta: { auth: true, team: true } },
  // 组件展示（1.0.4 C）：__IB_SHOWCASE__ 为构建期常量（vite.config define）。正式构建为 false，
  // 本分支被静态消除、ShowcaseView 不进 dist；仅 dev / 演示构建（VITE_API_MODE=mock）/ VITE_SHOWCASE=1 才包含。
  // 运行时再叠一层守卫（下方 beforeEach）：包含该路由的构建里也只有 demoMode 或 DEV 可访问。
  ...(__IB_SHOWCASE__ ? [{ path: '/showcase', component: () => import('../views/ShowcaseView.vue'), meta: { showcase: true } }] : []),
  { path: '/placeholder/:name', component: () => import('../views/PlaceholderView.vue'), meta: { auth: true, team: true } },
]})
router.beforeEach(async (to) => {
  const auth = useAuth(); await auth.loadMeta()
  // 组件展示运行时守卫：仅演示模式（登录后 /meta 的 demoMode）或 dev（import.meta.env.DEV）可访问，其余重定向工作台。
  // 整行置于 __IB_SHOWCASE__ 之后：正式构建该常量为 false，本行被静态消除，dist 不留 showcase 字样。
  if (__IB_SHOWCASE__ && (to.meta as any).showcase && !(auth.meta.demoMode || import.meta.env.DEV)) return '/workbench'
  if (!to.meta.auth) return true
  const user = auth.user ?? await auth.loadMe()
  if (!user) return '/login'
  // 平台管理（1.0.4 I）：非平台管理员直接跳走。
  if ((to.meta as any).admin && !user.platformAdmin) return '/workbench'
  if (to.meta.team) { const team = useTeam(); if (!team.teams.length) { try { await team.loadTeams() } catch {} } if (!team.teams.length) return '/teams/new' }
  return true
})
// 修订二（rkaqv6sp 第 3 处）：跳转离开页面后清空全部 toast，旧页提示不残留到新页。
router.afterEach(() => { try { useToast().clear() } catch {} })
export default router
