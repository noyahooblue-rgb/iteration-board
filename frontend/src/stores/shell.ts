import { defineStore } from 'pinia'
import { api, subscribeEvents } from '../api/http'
import type { Item, NotificationItem } from '../api/types'
import { useTeam } from './team'
import { useAuth } from './auth'

// 全局计数 store（1.0.4 A）：所有「需要我处理」的数字统一在应用外壳层加载与维护，任何页面都显示，
// 不再由某个视图通过 prop 传入（旧实现只有 Pool/Board 传 unclaimed，切到其他页角标就消失）。
// 角标数据来源（逐个注明）：
// - unread 铃铛未读数：GET /notifications/unread-count?teamId=（后端通知中心口径）
// - unclaimed 认领池待认领数：GET /teams/{id}/items?status=UNCLAIMED&scope=all 的条数（与 PoolView 列表同口径）
// - pendingReview 待我验收数：同一事项全量列表中 acceptorId=我 且 status=IN_REVIEW 的条数（与 WorkbenchView「待我验收」分组同口径）
// - nudged 被催待回应数：同一列表中 ownerIds 含我、nudgeCount>0 且未完成/未取消的条数（与 WorkbenchView「被催提醒」同口径）
// - pendingJoins 待审批加入申请数（仅组长）：GET /teams/{id}/join-requests 的条数（与 TeamSettingsView 审批列表同接口，接口本身仅组长可见）
// 实时性：SSE 收到任意通知事件后防抖 400ms 全量刷新计数；路由变化时 AppShell 也会调 refresh() 兜底。
export const useShell = defineStore('shell', {
  state: () => ({
    unread: 0,
    notifs: [] as NotificationItem[],
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
    async loadUnread() {
      const team = useTeam(); if (!team.currentId) return
      try { const r = await api.get(`/notifications/unread-count?teamId=${team.currentId}`); this.unread = r.unread ?? 0 } catch {}
    },
    async loadNotifs() {
      const team = useTeam(); if (!team.currentId) return
      try { this.notifs = await api.get(`/notifications?teamId=${team.currentId}&limit=30`) } catch {}
    },
    async refresh() {
      const team = useTeam(), auth = useAuth()
      if (!team.currentId || !auth.user) return
      await this.loadUnread()
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
    // 挂载时启动 SSE 订阅：收到事件先本地 +1 铃铛（即时反馈），再防抖全量刷新所有计数。
    start(onNotify?: (n: any) => void) {
      if (this._unsub) return
      this._unsub = subscribeEvents((n: any) => {
        if (n?.__connected) { // 重连对账：只刷新，不计数、不弹提示
          clearTimeout(this._timer)
          this._timer = setTimeout(() => { this.refresh(); }, 400)
          return
        }
        this.unread += 1
        if (onNotify) onNotify(n)
        clearTimeout(this._timer)
        this._timer = setTimeout(() => { this.refresh(); }, 400)
      })
    },
    stop() { if (this._unsub) { this._unsub(); this._unsub = null } },
    async markRead(n: NotificationItem) {
      if (n.readAt !== null) return
      try { await api.post(`/notifications/${n.id}/read`); n.readAt = new Date().toISOString(); this.unread = Math.max(0, this.unread - 1) } catch {}
    },
    async markAllRead() {
      const team = useTeam()
      try {
        await api.post('/notifications/read-all', { teamId: team.currentId })
        this.notifs.forEach(n => n.readAt = n.readAt ?? new Date().toISOString()); this.unread = 0
        return true
      } catch { return false }
    },
  },
})
