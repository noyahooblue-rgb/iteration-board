import { defineStore } from 'pinia'
// 侧栏收起状态按用户持久化（1.0.4 E）：localStorage key 含用户 id（ib-ui-<userId>）。
// 无已存偏好时：窗口宽度 <1440px 默认收起，否则展开。登录拿到 user 后 AppShell 调 hydrate(userId) 应用。
function key(userId?: number | null) { return `ib-ui-${userId ?? 'anon'}` }
function readPref(userId?: number | null): boolean | null {
  try { const v = localStorage.getItem(key(userId)); return v === null ? null : v === '1' } catch { return null }
}
export const useUi = defineStore('ui', {
  state: () => ({ createOpen: false, collapsed: false, searchOpen: false, createPreset: null as any, _userId: null as number | null, _hydrated: false }),
  actions: {
    hydrate(userId: number | null) {
      this._userId = userId
      const saved = readPref(userId)
      this.collapsed = saved !== null ? saved : (typeof window !== 'undefined' && window.innerWidth < 1440)
      this._hydrated = true
    },
    toggleCollapsed() {
      this.collapsed = !this.collapsed
      try { localStorage.setItem(key(this._userId), this.collapsed ? '1' : '0') } catch {}
    },
    setCollapsed(v: boolean) {
      this.collapsed = v
      try { localStorage.setItem(key(this._userId), v ? '1' : '0') } catch {}
    },
  },
})
