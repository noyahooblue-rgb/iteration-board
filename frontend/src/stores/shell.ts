import { defineStore } from 'pinia'
import { api, subscribeEvents } from '../api/http'
import type { Item, NotificationItem } from '../api/types'
import { useTeam } from './team'
import { useAuth } from './auth'
import { applyUnreadBadge, saveNotifyPrefs, loadNotifyPrefs } from '../utils/browserNotify'

// 全局计数 store（1.0.4 A）：所有「需要我处理」的数字统一在应用外壳层加载与维护，任何页面都显示，
// 不再由某个视图通过 prop 传入（旧实现只有 Pool/Board 传 unclaimed，切到其他页角标就消失）。
// 角标数据来源（逐个注明）：
// - unread 铃铛未读数 + 通知面板条目：GET /notifications?scope=current&teamId=（P2c 裁定 zu5skyzg：
//   当前团队通知 + 平台通知同一后端查询，返回 { items, unreadCount }；角标、面板、标题 (n)、
//   favicon 红点全部取自这个 unreadCount，前端不再自行过滤/计算——旧口径按 teamId 过滤会把
//   平台通知（team 为空，如反馈回复）排除在面板外、角标却计数，造成两边对不上）
// - teamUnread 团队切换器各团队未读数：GET /notifications/unread-counts-by-team（仅团队通知，平台单列）
// - unclaimed 认领池待认领数：GET /teams/{id}/items?status=UNCLAIMED&scope=all 的条数（与 PoolView 列表同口径）
// - pendingReview 待我验收数：同一事项全量列表中 acceptorId=我 且 status=IN_REVIEW 的条数（与 WorkbenchView「待我验收」分组同口径）
// - nudged 被催待回应数：同一列表中 ownerIds 含我、nudgeCount>0 且未完成/未取消的条数（与 WorkbenchView「被催提醒」同口径）
// - pendingJoins 待审批加入申请数（仅组长）：GET /teams/{id}/join-requests 的条数（与 TeamSettingsView 审批列表同接口，接口本身仅组长可见）
// 实时性：SSE 增量带服务端按当前视图算好的 unreadCount（连接订阅时传 teamId），命中当前视图时
// 直接采用；其余情况防抖 400ms 全量对账（scope=current + 按团队计数 + 事项计数）。
export const useShell = defineStore('shell', {
  state: () => ({
    unread: 0,
    notifs: [] as NotificationItem[],
    teamUnread: {} as Record<number, number>,
    platformUnread: 0,
    unclaimed: 0,
    pendingReview: 0,
    nudged: 0,
    pendingJoins: 0,
    _unsub: null as (() => void) | null,
    _timer: 0 as any,
  }),
  getters: {
    // 「工作台」导航角标 = 待我验收 + 被催待回应
    workbenchBadge: (s) => s.pendingReview + s.nudged,
  },
  actions: {
    // 当前视图一次拉齐：面板条目与未读数同源（裁定规则 2）。
    async loadCurrent() {
      const team = useTeam(); if (!team.currentId) return
      try {
        const r = await api.get(`/notifications?scope=current&teamId=${team.currentId}&limit=30`)
        this.notifs = r.items ?? []
        this.unread = r.unreadCount ?? 0
      } catch {}
      this.syncBadge()
    },
    async loadUnread() { await this.loadCurrent() },
    async loadNotifs() { await this.loadCurrent() },
    async loadTeamUnread() {
      try {
        const r = await api.get('/notifications/unread-counts-by-team')
        this.teamUnread = r.teams ?? {}
        this.platformUnread = r.platform ?? 0
      } catch {}
    },
    async refresh() {
      const team = useTeam(), auth = useAuth()
      if (!team.currentId || !auth.user) return
      await this.loadCurrent()
      await this.loadTeamUnread()
      try {
        const items: Item[] = await api.get(`/teams/${team.currentId}/items?scope=all`)
        const me = auth.user.id
        this.unclaimed = items.filter(i => i.status === 'UNCLAIMED').length
        this.pendingReview = items.filter(i => i.acceptorId === me && i.status === 'IN_REVIEW').length
        this.nudged = items.filter(i => i.ownerIds.includes(me) && i.nudgeCount > 0 && !['DONE', 'CANCELLED'].includes(i.status)).length
      } catch {}
      if (team.current?.myRole === 'LEADER') {
        try { const reqs = await api.get(`/teams/${team.currentId}/join-requests`); this.pendingJoins = Array.isArray(reqs) ? reqs.length : 0 } catch { this.pendingJoins = 0 }
      } else this.pendingJoins = 0
    },
    // 浏览器通知第 1/2 层（1.1.0）：标题未读数 + favicon 角标随未读数实时同步，归零恢复。
    syncBadge() { applyUnreadBadge(this.unread) },
    // 桌面通知/提示音是浏览器本地偏好（后端偏好接口无此字段），以 localStorage 为准，此处仅确保默认值就位，不覆盖用户已保存的选择。
    async loadNotifPrefs() { saveNotifyPrefs(loadNotifyPrefs()) },
    // 挂载时启动 SSE 订阅：增量带 unreadCount（当前视图口径）时直接采用，否则属于当前视图才 +1；
    // 其他团队的通知不动角标（切换器计数靠对账刷新）。随后防抖全量对账，保证恒等式不漂移。
    start(onNotify?: (n: any) => void) {
      if (this._unsub) return
      this._unsub = subscribeEvents((n: any) => {
        if (n?.__connected) { // 重连对账：只刷新，不计数、不弹提示
          clearTimeout(this._timer)
          this._timer = setTimeout(() => { this.refresh(); }, 400)
          return
        }
        const team = useTeam()
        const inCurrentView = n?.teamId == null || n?.teamId === team.currentId
        if (typeof n?.unreadCount === 'number' && inCurrentView) {
          this.unread = n.unreadCount
          this.syncBadge()
        } else if (inCurrentView) {
          this.unread += 1
          this.syncBadge()
        }
        if (onNotify) onNotify(n)
        clearTimeout(this._timer)
        this._timer = setTimeout(() => { this.refresh(); }, 400)
      })
    },
    stop() { if (this._unsub) { this._unsub(); this._unsub = null } },
    async markRead(n: NotificationItem) {
      if (n.readAt !== null) return
      try { await api.post(`/notifications/${n.id}/read`); n.readAt = new Date().toISOString(); await this.loadCurrent() } catch {}
    },
    async markAllRead() {
      const team = useTeam()
      try {
        // 后端按当前视图口径标记（当前团队 + 平台，裁定规则 6）
        await api.post('/notifications/read-all', { teamId: team.currentId })
        await this.loadCurrent()
        await this.loadTeamUnread()
        return true
      } catch { return false }
    },
  },
})
