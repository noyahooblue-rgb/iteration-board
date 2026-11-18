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
  { path: '/showcase', component: () => import('../views/ShowcaseView.vue') },
  { path: '/placeholder/:name', component: () => import('../views/PlaceholderView.vue'), meta: { auth: true, team: true } },
]})
router.beforeEach(async (to) => {
  const auth = useAuth(); await auth.loadMeta()
  if (!to.meta.auth) return true
  const user = auth.user ?? await auth.loadMe()
  if (!user) return '/login'
  if (to.meta.team) { const team = useTeam(); if (!team.teams.length) { try { await team.loadTeams() } catch {} } if (!team.teams.length) return '/teams/new' }
  return true
})
// 修订二（rkaqv6sp 第 3 处）：跳转离开页面后清空全部 toast，旧页提示不残留到新页。
router.afterEach(() => { try { useToast().clear() } catch {} })
export default router
