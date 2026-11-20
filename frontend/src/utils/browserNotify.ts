// 浏览器通知三层（1.1.0，任务 7g8rzm2n 一）：
// 第 1 层 标签页标题未读数、第 2 层 favicon 红点角标——所有来源可用，不依赖安全上下文；
// 第 3 层 桌面通知 + 提示音——可选开启，受安全来源限制（非 secure context 时设置页置灰）。
// 本模块只管浏览器侧机制；未读数真值来自 shell store（SSE/角标同源）。
export const BASE_TITLE = '迭代小组平台'

// ---------- 第 1 层：标题未读数 ----------
export function applyTitleBadge(unread: number) {
  document.title = unread > 0 ? `(${unread}) ${BASE_TITLE}` : BASE_TITLE
}

// ---------- 第 2 层：favicon 未读角标（1.1.0 品牌定稿：改用定稿 badges 资产，不再 canvas 现画） ----------
// 有未读 → 定稿未读角标版 favicon（红点（定稿未读红） + 白描边，右上角覆盖星芒位置，定稿第 2 条）；
// 无未读 → 按当前主题恢复标准/深色版。切换逻辑统一在 utils/favicon.ts 的 setAppIcon，
// 主题切换（外观子任务）调用同一函数，两边状态互不冲掉。
import { setAppIcon } from './favicon'
export function applyFaviconBadge(unread: number) {
  setAppIcon(document.documentElement.dataset.theme || 'light', unread)
}
export function applyUnreadBadge(unread: number) { applyTitleBadge(unread); applyFaviconBadge(unread) }

// ---------- 第 3 层：桌面通知 + 提示音（本地开关，随个人设置同步到后端偏好） ----------
export interface NotifyPrefs { desktop: boolean; sound: boolean }
const LS_KEY = 'ib-notify-prefs'
export function loadNotifyPrefs(): NotifyPrefs {
  try { const raw = localStorage.getItem(LS_KEY); if (raw) return { desktop: false, sound: false, ...JSON.parse(raw) } } catch {}
  return { desktop: false, sound: false }
}
export function saveNotifyPrefs(p: NotifyPrefs) { try { localStorage.setItem(LS_KEY, JSON.stringify(p)) } catch {} }
export function desktopSupported(): boolean { return typeof window !== 'undefined' && 'Notification' in window }
export function isSecureForNotify(): boolean { return typeof window !== 'undefined' && window.isSecureContext }

// 只对五类弹桌面通知：指派给我 / 邀请我认领 / @我 / 催我 / 待我验收。
// SSE 通知形状 {type, category, content, itemId}：ASSIGNMENT 含指派与邀请认领；待我验收以 type=REVIEW_PENDING 或正文含「待验收」识别。
export function isDesktopWorthy(n: any): boolean {
  if (!n) return false
  if (n.category === 'ASSIGNMENT' || n.category === 'MENTION' || n.category === 'NUDGE') return true
  if (n.type === 'REVIEW_PENDING') return true
  if (typeof n.content === 'string' && n.content.includes('待验收')) return true
  return false
}
const recentByItem = new Map<string, number>()
const DEDUPE_MS = 30 * 1000 // 同一事项 30 秒内合并只弹一条
export function tryDesktopNotify(n: any, navigate: (n: any) => void) {
  const prefs = loadNotifyPrefs()
  if (!prefs.desktop || !desktopSupported() || !isSecureForNotify()) return
  if (document.visibilityState === 'visible') return // 前台不弹，避免重复打扰
  if (Notification.permission !== 'granted') return
  if (!isDesktopWorthy(n)) return
  const key = String(n.itemId ?? n.content ?? '')
  const now = Date.now()
  if (key && now - (recentByItem.get(key) ?? 0) < DEDUPE_MS) return
  recentByItem.set(key, now)
  try {
    const notif = new Notification(BASE_TITLE, { body: n.content ?? '', tag: 'ib-' + key, icon: '/icons/icon-192.png' })
    notif.onclick = () => { window.focus(); navigate(n); notif.close() }
  } catch {}
}

// 提示音：WebAudio 合成一声短音（880Hz、0.15s 衰减），不引外部音频文件。
let audioCtx: AudioContext | null = null
let lastBeepAt = 0
export function playBeep() {
  if (!loadNotifyPrefs().sound) return
  const now = Date.now(); if (now - lastBeepAt < 1000) return; lastBeepAt = now
  try {
    audioCtx = audioCtx ?? new (window.AudioContext || (window as any).webkitAudioContext)()
    const t = audioCtx.currentTime
    const osc = audioCtx.createOscillator(), gain = audioCtx.createGain()
    osc.type = 'sine'; osc.frequency.value = 880
    gain.gain.setValueAtTime(0.12, t); gain.gain.exponentialRampToValueAtTime(0.001, t + 0.15)
    osc.connect(gain); gain.connect(audioCtx.destination)
    osc.start(t); osc.stop(t + 0.16)
  } catch {}
}
