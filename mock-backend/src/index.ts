// 浏览器模拟后端入口：handleRequest(method, path, body) -> { status, data }
// 严格按 contract/openapi.yaml 0.5.0 与 contract/state-machine.md 实现；错误统一 {code,message,detail}。
// 规则与真实后端一致：故事/任务仅 PO 创建、婉拒 DECLINE、共同负责 join、任务状态推算、
// 批量下发、检查清单、搜索、回收站、保存筛选、迭代规划。
import { MockError, type Item, type Team, type User, type Batch, type Nudge, type SavedFilter, type Notification, type ChecklistEntry, type KeyDate, type MockReport } from './types'
import { seedUsers, seedMembers, seedIterations, seedBatches, buildItems, buildProgress, buildComments, buildActivities, seedKeyDates, seedStandupNotes, buildSeedNotifications, buildSeedReports, buildSeedAuditLogs } from './seed'

// 演示构建开关：演示环境关闭开放注册、开启演示标识。
export const MOCK_CONFIG = { registrationOpen: false, demoMode: true }

interface StoredUser extends User { password: string }
const users: StoredUser[] = seedUsers.map(u => ({ ...u }))
let nextUserId = 100
export const DEFAULT_SETTINGS = { capacityPointsPerWorkday: 1, reminderThresholds: { staleInProgressWorkdays: 2, notStartedWorkdays: 2, reviewPendingWorkdays: 2, unclaimedWorkdays: 3, blockedWorkdays: 2, dueSoonWorkdays: 1 }, specialHolidays: [] as string[], specialWorkdays: [] as string[] }
const teamRow: Team = { id: 1, name: '云雀小组', code: 'SKY', inviteCode: 'SKY2026A', joinApproval: true, iterationWeeks: 2, estimateUnit: 'point', themeColor: 'blue', status: 'ACTIVE', description: '在线协作文档产品的体验与稳定性小组（演示数据，全部虚构）。', settings: JSON.parse(JSON.stringify(DEFAULT_SETTINGS)) }
const auroraTeam: Team = { id: 999, name: '极光小组', code: 'AUR', joinApproval: true, iterationWeeks: 2, estimateUnit: 'point', themeColor: 'violet', status: 'ACTIVE', description: null, settings: JSON.parse(JSON.stringify(DEFAULT_SETTINGS)) }
const extraTeams: Team[] = []
const allTeams = () => [teamRow, auroraTeam, ...extraTeams]
let members = seedMembers.map(m => ({ teamId: 1, userId: m.userId, role: m.role as string, memberStatus: m.memberStatus, memberStatusStart: m.userId === 9 ? '2026-11-16' : null as string|null, memberStatusEnd: m.userId === 9 ? '2026-11-20' : null as string|null }))
let items: Item[] = buildItems()
let progressList = buildProgress(items)
let comments = buildComments(items)
let activities = buildActivities(items)
let batches: Batch[] = seedBatches.map(b => ({ ...b }))
let nudges: Nudge[] = []
let savedFilters: SavedFilter[] = []
let notifications: Notification[] = buildSeedNotifications()
// M3 状态：关键日期 / 站会备注 / 报告 / 通知偏好 / 提醒去重
let keyDates: KeyDate[] = seedKeyDates.map(k => ({ ...k }))
let standupNotes = new Map<string, string>(Object.entries(seedStandupNotes))
let reports: MockReport[] = buildSeedReports()
// M5 审计日志（Q6）：种子预置 + 关键写操作追加；形状 {id, teamId, actorId, actorName, action, targetType, targetId, detail, createdAt}，时间倒序。
interface AuditLog { id: number; teamId: number; actorId: number; actorName: string; action: string; targetType: string; targetId: number | null; detail: string | null; createdAt: string }
let auditLogs: AuditLog[] = buildSeedAuditLogs()
let auditSeq = 100
function audit(teamId: number, actor: StoredUser, action: string, targetType: string, targetId: number | null, detail: string | null) {
  auditLogs.unshift({ id: auditSeq++, teamId, actorId: actor.id, actorName: actor.displayName, action, targetType, targetId, detail, createdAt: new Date().toISOString() })
}
function auditPage(list: AuditLog[], q: URLSearchParams) {
  let out = [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id)
  const actorId = q.get('actorId'); if (actorId) out = out.filter(l => l.actorId === Number(actorId))
  const action = q.get('action'); if (action) out = out.filter(l => l.action === action)
  // 契约 0.5.2 口径：from/to 为 ISO date-time，分页 0 起，信封 {content,page,size,totalElements,totalPages}
  const from = q.get('from'); if (from) { const t = Date.parse(from); out = out.filter(l => Date.parse(l.createdAt.endsWith('Z') ? l.createdAt : l.createdAt + 'Z') >= t) }
  const to = q.get('to'); if (to) { const t = Date.parse(to); out = out.filter(l => Date.parse(l.createdAt.endsWith('Z') ? l.createdAt : l.createdAt + 'Z') <= t) }
  const totalElements = out.length
  const page = Math.max(0, Number(q.get('page') ?? 0)), size = Math.min(200, Math.max(1, Number(q.get('size') ?? 20)))
  return { content: out.slice(page * size, page * size + size), page, size, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)) }
}
const userPrefs = new Map<number, Record<string, boolean>>()
const reminderLog = new Set<string>()
let nextKeyDateId = 100, nextReportId = 100
let joinRequests: any[] = []
let nextItemId = 41, nextId = 1000, nextBatchId = 2, nextChecklistId = 100
const iterations = seedIterations.map(i => ({ ...i }))
const pendingNotified = new Set<number>()

let sessionUserId: number | null = (() => { try { const v = localStorage.getItem('mock-session'); return v ? Number(v) : null } catch { return null } })()
function setSession(id: number | null) { sessionUserId = id; try { id ? localStorage.setItem('mock-session', String(id)) : localStorage.removeItem('mock-session') } catch {} }

const captchas = new Map<string, string>()
const failures = new Map<string, { count: number; lockedUntil: number }>()
const err = (s: number, c: string, m: string, d: any = null) => new MockError(s, c, m, d)
const pub = (u: StoredUser): User => ({ id: u.id, loginName: u.loginName, displayName: u.displayName, avatarColor: u.avatarColor, platformAdmin: u.platformAdmin, mustChangePassword: u.mustChangePassword })
const byId = (id: number) => users.find(u => u.id === id)
function me(): StoredUser { const u = sessionUserId ? byId(sessionUserId) : undefined; if (!u) throw err(401, 'UNAUTHENTICATED', '未登录'); return u }
function memberOf(teamId: number, userId: number) { return members.find(m => m.teamId === teamId && m.userId === userId) }
function requireMember(teamId: number) { const u = me(); const m = memberOf(teamId, u.id); if (!m) throw err(404, 'NOT_FOUND', '小组或事项不存在'); return { u, m } }
function teamView(t: Team, userId: number, includeInvite = false): Team {
  const m = memberOf(t.id, userId)
  return { ...t, myRole: m?.role as any, inviteCode: includeInvite || m?.role === 'LEADER' ? t.inviteCode : undefined }
}
const PREF_CATS = ['ASSIGNMENT', 'NUDGE', 'MENTION', 'ITEM_UPDATE', 'REMINDER', 'SYSTEM']
function prefsOf(userId: number) { return userPrefs.get(userId) ?? { ASSIGNMENT: true, NUDGE: true, MENTION: true, ITEM_UPDATE: true, REMINDER: true, SYSTEM: true } }
function notify(userId: number, teamId: number | null, category: string, item: Item | null, content: string, actorId: number | null = null) {
  if (prefsOf(userId)[category] === false && category !== 'ASSIGNMENT' && category !== 'NUDGE') return
  const n: Notification = { id: nextId++, userId, teamId, type: category, category, itemId: item?.id ?? null, itemCode: item?.code ?? null, actorId, content, readAt: null, createdAt: new Date().toISOString() }
  notifications.unshift(n)
  if (sessionUserId === userId) { try { const set = (globalThis as any).__ibMockEventListeners as Set<(x: any) => void> | undefined; set?.forEach(cb => { try { cb(notifView(n)) } catch {} }) } catch {} }
}
const notifView = (n: Notification) => ({ id: n.id, teamId: n.teamId, type: n.type, category: n.category, itemId: n.itemId, itemCode: n.itemCode, actorId: n.actorId, content: n.content, readAt: n.readAt, createdAt: n.createdAt })
// 同步连败通知（对齐真实后端 yefqkvf3 待问 1）：type=SYNC_FAILED、类别 ITEM_UPDATE（后端 NotificationView.categoryOf 的默认归口）；
// 发给所有组长，包括触发者本人（此路径不跳过自己）；偏好层不变——关闭 ITEM_UPDATE 者不生成。
function notifySyncFailed(userId: number, teamId: number, content: string, actorId: number) {
  if (prefsOf(userId)['ITEM_UPDATE'] === false) return
  const n: Notification = { id: nextId++, userId, teamId, type: 'SYNC_FAILED', category: 'ITEM_UPDATE', itemId: null, itemCode: null, actorId, content, readAt: null, createdAt: new Date().toISOString() }
  notifications.unshift(n)
  if (sessionUserId === userId) { try { const set = (globalThis as any).__ibMockEventListeners as Set<(x: any) => void> | undefined; set?.forEach(cb => { try { cb(notifView(n)) } catch {} }) } catch {} }
}
function act(itemId: number, actorId: number, action: string, from: string | null, to: string | null, detail: string | null) {
  activities.unshift({ id: nextId++, itemId, actorId, action, fromStatus: from, toStatus: to, detail, createdAt: new Date().toISOString() })
}
// 任务状态推算（state-machine.md「任务状态推算」）：故事创建/流转/删除/恢复后调用。
function deriveTask(task: Item) {
  if (task.type !== 'TASK') return
  if (task.taskStatus === 'DONE' || task.taskStatus === 'CANCELLED') { /* PO 置位，不由推算覆盖 */ }
  else {
    const stories = items.filter(k => k.parentId === task.id && !k.deletedAt)
    const live = stories.filter(k => k.status !== 'CANCELLED')
    let st: Item['taskStatus'] = 'NOT_STARTED'
    if (live.length && live.every(k => k.status === 'DONE')) st = 'PENDING_ACCEPTANCE'
    else if (live.some(k => k.status === 'IN_PROGRESS' || k.status === 'IN_REVIEW') || live.some(k => k.status === 'DONE')) st = 'IN_PROGRESS'
    const was = task.taskStatus
    task.taskStatus = st
    if (st === 'PENDING_ACCEPTANCE' && was !== 'PENDING_ACCEPTANCE' && !pendingNotified.has(task.id)) {
      pendingNotified.add(task.id); if (task.poId) notify(task.poId, 1, 'ITEM_UPDATE', task, `任务 ${task.code} 的故事已全部完成，待验收`)
    }
    if (st !== 'PENDING_ACCEPTANCE') pendingNotified.delete(task.id)
    const totalPts = live.reduce((n, k) => n + (k.estimate ?? 0), 0)
    if (totalPts > 0) task.progressPercent = Math.round(live.filter(k => k.status === 'DONE').reduce((n, k) => n + (k.estimate ?? 0), 0) / totalPts * 100)
    else task.progressPercent = live.length ? Math.round(live.filter(k => k.status === 'DONE').length / live.length * 100) : 0
  }
}
function recompute(it: Item): Item {
  if (it.type === 'TASK') {
    const kids = items.filter(k => k.parentId === it.id && !k.deletedAt)
    it.childCount = kids.length; it.childDoneCount = kids.filter(k => k.status === 'DONE').length
    deriveTask(it)
  } else { it.childCount = 0; it.childDoneCount = 0 }
  it.commentCount = comments.filter(c => c.itemId === it.id).length
  it.checklist = [...(it.checklist ?? [])].sort((a, b) => a.sortOrder - b.sortOrder)
  return it
}
// Q11（契约 0.5.0）：服务端计算当前用户对该事项可执行的操作，前端只读不再重写权限规则。
function allowedActionsFor(it: Item): string[] {
  const u = sessionUserId ? byId(sessionUserId) : undefined; if (!u) return []
  const m = members.find(x => x.userId === u.id)
  const role = m?.role ?? 'MEMBER'
  const isOwner = it.ownerIds.includes(u.id), isCreator = it.creatorId === u.id, leadPO = role === 'LEADER' || role === 'PO'
  if (it.deletedAt) return (leadPO || isCreator) ? ['RESTORE'] : []
  const acts: string[] = ['COMMENT']
  if (leadPO || isCreator || isOwner) acts.push('EDIT')
  if (leadPO || isCreator) acts.push('DELETE')
  if (it.type !== 'TASK') {
    if (it.status === 'UNCLAIMED') acts.push('CLAIM')
    if (!isOwner && ['NOT_STARTED', 'IN_PROGRESS', 'IN_REVIEW'].includes(it.status)) acts.push('NUDGE')
    if (!isOwner && it.ownerIds.length && !['DONE', 'CANCELLED'].includes(it.status)) acts.push('JOIN')
    if (isOwner && it.status === 'IN_PROGRESS') acts.push('PROGRESS')
    if (isOwner && ['IN_PROGRESS', 'IN_REVIEW'].includes(it.status)) acts.push('RESPOND_NUDGE')
  } else {
    if (it.taskStatus === 'PENDING_ACCEPTANCE' && (role === 'PO' || it.poId === u.id)) acts.push('APPROVE', 'REJECT')
  }
  return acts
}
const view = (it: Item) => { const v = JSON.parse(JSON.stringify(recompute(it))); v.allowedActions = allowedActionsFor(it); return v }
function getItem(teamId: number, itemId: number, includeDeleted = false) { requireMember(teamId); const it = items.find(i => i.id === itemId); if (!it || (it.deletedAt && !includeDeleted)) throw err(404, 'NOT_FOUND', '事项不存在'); return it }
function captchaSvg(code: string) {
  const chars = code.split('').map((c, i) => `<text x="${14 + i * 24}" y="30" font-size="24" font-family="monospace" fill="hsl(${i * 70 + 200},70%,45%)" transform="rotate(${(i % 2 ? -1 : 1) * 8} ${14 + i * 24} 24)">${c}</text>`).join('')
  return `<svg xmlns="http://www.w3.org/2000/svg" width="120" height="44"><rect width="120" height="44" rx="8" fill="#eef4ff"/><line x1="4" y1="32" x2="116" y2="10" stroke="#b9cdf5"/><line x1="4" y1="10" x2="116" y2="36" stroke="#f3c2d4"/>${chars}</svg>`
}
function checkCaptcha(id?: string, code?: string) {
  if (!id || !code || captchas.get(id) !== code.toUpperCase()) throw err(400, 'CAPTCHA_INVALID', '验证码不正确')
  captchas.delete(id)
}
const validPwd = (p: string) => p.length >= 8 && /[A-Za-z]/.test(p) && /\d/.test(p)

// ---------------- 路由 ----------------
let currentHeaders: Record<string, string> = {}
const idemStore = new Map<string, { at: number; status: number; data: any }>()
// 幂等键：同一键 10 分钟内重复提交只生效一次，重复请求直接返回首次结果。
function idemWrap(scope: string, fn: () => { status: number; data: any }): { status: number; data: any } {
  const key = currentHeaders['Idempotency-Key'] ?? currentHeaders['idempotency-key']
  if (!key) return fn()
  const k = scope + '|' + key + '|' + (sessionUserId ?? 0)
  const hit = idemStore.get(k)
  if (hit && Date.now() - hit.at < 10 * 60 * 1000) return { status: hit.status, data: JSON.parse(JSON.stringify(hit.data)) }
  const r = fn()
  if (r.status < 400) idemStore.set(k, { at: Date.now(), status: r.status, data: JSON.parse(JSON.stringify(r.data)) })
  return r
}
export async function handleRequest(method: string, rawPath: string, body?: any, headers?: Record<string, string>): Promise<{ status: number; data: any }> {
  currentHeaders = headers ?? {}
  // multipart（契约 imports/file/parse、ai/paste-import/file）：FormData 直传时解出 file 字段的文本内容供路由使用。
  let b = body
  if (b && typeof b.get === 'function' && typeof b.append === 'function') { const f = b.get('file'); b = f ? { __fileName: f.name ?? '', __fileText: typeof f.text === 'function' ? await f.text() : '' } : {} }
  try { return route(method.toUpperCase(), rawPath, b) }
  catch (e) { if (e instanceof MockError) return { status: e.status, data: { code: e.code, message: e.message, detail: e.detail } }; throw e }
}
function route(method: string, rawPath: string, body: any): { status: number; data: any } {
  const [path, qs] = rawPath.split('?'); const q = new URLSearchParams(qs ?? '')
  const seg = path.replace(/^\/api\/v1/, '').split('/').filter(Boolean)
  if (method === 'GET' && path.endsWith('/meta')) return { status: 200, data: { ...MOCK_CONFIG } }
  if (method === 'GET' && seg.join('/') === 'auth/captcha') {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let code = ''; for (let i = 0; i < 4; i++) code += chars[Math.floor(Math.random() * chars.length)]
    const id = 'cap-' + (nextId++); captchas.set(id, code); return { status: 200, data: { captchaId: id, imageSvg: captchaSvg(code) } }
  }
  if (seg[0] === 'auth') return auth(method, seg[1], body)
  if (seg[0] === 'notifications') return notifRoute(method, seg, body, q)
  if (seg[0] === 'reports') return reportTopRoute(method, seg, body)
  if (seg[0] === 'admin') return adminRoute(method, seg, body, q)
  if (seg[0] === 'teams') return teams(method, seg, body, q)
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// ---------------- 平台管理（M5 Q6 审计日志） ----------------
function adminRoute(method: string, seg: string[], body: any, q: URLSearchParams) {
  const u = me(); if (!u.platformAdmin) throw err(403, 'FORBIDDEN', '仅平台管理员可访问')
  if (method === 'GET' && seg[1] === 'audit-logs') {
    const tid = q.get('teamId')
    return { status: 200, data: auditPage(tid ? auditLogs.filter(l => l.teamId === Number(tid)) : auditLogs, q) }
  }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// ---------------- 通知中心（M3） ----------------
function notifRoute(method: string, seg: string[], body: any, q: URLSearchParams) {
  const u = me()
  if (method === 'GET' && seg[1] === 'unread-count') { const tid = Number(q.get('teamId') ?? 0); return { status: 200, data: { unread: notifications.filter(n => n.userId === u.id && !n.readAt && (!tid || n.teamId === tid)).length } } }
  if (method === 'POST' && seg[1] === 'read-all') { const tid = Number(body?.teamId ?? 0); for (const n of notifications) if (n.userId === u.id && !n.readAt && (!tid || n.teamId === tid)) n.readAt = new Date().toISOString(); return { status: 200, data: { updated: 0 } } }
  if (method === 'POST' && seg[2] === 'read') { const n = notifications.find(x => x.id === Number(seg[1]) && x.userId === u.id); if (!n) throw err(404, 'NOT_FOUND', '通知不存在'); n.readAt = n.readAt ?? new Date().toISOString(); return { status: 200, data: notifView(n) } }
  if (method === 'GET' && seg.length === 1) { const tid = Number(q.get('teamId') ?? 0); const limit = Number(q.get('limit') ?? 50); return { status: 200, data: notifications.filter(n => n.userId === u.id && (!tid || n.teamId === tid)).slice(0, limit).map(notifView) } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function auth(method: string, sub: string, body: any) {
  if (method === 'POST' && sub === 'register') {
    if (!MOCK_CONFIG.registrationOpen) throw err(403, 'REGISTRATION_CLOSED', '演示环境已关闭开放注册')
    checkCaptcha(body.captchaId, body.captchaCode)
    if (!body.loginName || body.loginName.length < 3) throw err(400, 'VALIDATION', '登录名至少 3 位')
    if (!validPwd(body.password ?? '')) throw err(400, 'VALIDATION', '密码至少 8 位且同时含字母和数字')
    if (users.some(u => u.loginName === body.loginName)) throw err(409, 'LOGIN_NAME_TAKEN', '登录名已被占用')
    const u: StoredUser = { id: nextUserId++, loginName: body.loginName, displayName: body.displayName || body.loginName, avatarColor: '#007AFF', platformAdmin: users.length === 0, mustChangePassword: false, password: body.password }
    users.push(u); setSession(u.id); return { status: 201, data: pub(u) }
  }
  if (method === 'POST' && sub === 'login') {
    const now = Date.now(); const f = failures.get(body.loginName) ?? { count: 0, lockedUntil: 0 }
    if (f.lockedUntil > now) throw err(423, 'ACCOUNT_LOCKED', '账号已锁定，请稍后再试', { remainingMinutes: Math.ceil((f.lockedUntil - now) / 60000) })
    const needCaptcha = f.count >= 3
    const u = users.find(x => x.loginName === body.loginName)
    let ok = !!u && u.password === body.password
    if (needCaptcha) { try { checkCaptcha(body.captchaId, body.captchaCode) } catch { ok = false } }
    if (!ok) {
      f.count++; failures.set(body.loginName, f)
      if (f.count >= 5) { f.lockedUntil = now + 15 * 60000; failures.set(body.loginName, f); throw err(423, 'ACCOUNT_LOCKED', '连续失败 5 次，账号锁定 15 分钟', { remainingMinutes: 15 }) }
      throw err(401, 'INVALID_CREDENTIALS', needCaptcha ? '登录名、密码或验证码不正确' : '登录名或密码不正确', { needCaptcha, failCount: f.count })
    }
    failures.delete(body.loginName); setSession(u!.id); return { status: 200, data: pub(u!) }
  }
  if (method === 'POST' && sub === 'logout') { setSession(null); return { status: 204, data: null } }
  if (method === 'GET' && sub === 'me') return { status: 200, data: pub(me()) }
  if (sub === 'notification-prefs') {
    const u = me()
    if (method === 'GET') return { status: 200, data: { ...prefsOf(u.id) } }
    if (method === 'PATCH') { const p = { ...prefsOf(u.id) }; for (const k of PREF_CATS) if (typeof body?.[k] === 'boolean') p[k] = body[k]; p.ASSIGNMENT = true; p.NUDGE = true; userPrefs.set(u.id, p); return { status: 200, data: { ...p } } }
  }
  if (method === 'POST' && sub === 'change-password') {
    const u = me(); if (u.password !== body.oldPassword) throw err(400, 'VALIDATION', '原密码不正确')
    if (!validPwd(body.newPassword ?? '')) throw err(400, 'VALIDATION', '新密码至少 8 位且同时含字母和数字')
    u.password = body.newPassword; u.mustChangePassword = false; return { status: 204, data: null }
  }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function teams(method: string, seg: string[], body: any, q: URLSearchParams) {
  if (method === 'GET' && seg[1] === 'mine') { const u = me(); return { status: 200, data: members.filter(m => m.userId === u.id).map(m => teamView(allTeams().find(x => x.id === m.teamId) ?? teamRow, u.id)) } }
  if (method === 'GET' && seg[1] === 'by-invite') {
    const u = me(); const code = seg[2]
    if (code === teamRow.inviteCode) return { status: 200, data: { team: teamView(teamRow, u.id), leaderName: '林小满', alreadyMember: !!memberOf(1, u.id) } }
    if (code && code.length === 8) return { status: 200, data: { team: teamView(auroraTeam, u.id), leaderName: '沈亦帆', alreadyMember: false } }
    throw err(404, 'NOT_FOUND', '邀请码不存在')
  }
  if (method === 'POST' && seg[1] === 'join') {
    const u = me(); const code = body.inviteCode
    const target = code === teamRow.inviteCode ? teamRow : (code && code.length === 8 ? auroraTeam : null)
    if (!target) throw err(404, 'NOT_FOUND', '邀请码不存在')
    if (memberOf(target.id, u.id)) throw err(409, 'ALREADY_MEMBER', '你已是该小组成员')
    if (joinRequests.some(r => r.teamId === target.id && r.userId === u.id && r.status === 'PENDING')) throw err(409, 'ALREADY_MEMBER', '加入申请已提交，等待审批')
    if (target.joinApproval) { joinRequests.push({ id: nextId++, teamId: target.id, userId: u.id, displayName: u.displayName, loginName: u.loginName, status: 'PENDING', createdAt: new Date().toISOString() }); return { status: 200, data: { joined: false, team: teamView(target, u.id) } } }
    members.push({ teamId: target.id, userId: u.id, role: 'MEMBER', memberStatus: 'NORMAL', memberStatusStart: null, memberStatusEnd: null }); return { status: 200, data: { joined: true, team: teamView(target, u.id) } }
  }
  if (method === 'POST' && seg.length === 1) {
    const u = me(); if (!body.name || !body.code || !/^[A-Za-z]{2,5}$/.test(body.code)) throw err(400, 'VALIDATION', '小组代号需为 2 至 5 个字母')
    if (allTeams().some(t => t.code.toUpperCase() === body.code.toUpperCase())) throw err(409, 'TEAM_CODE_TAKEN', '小组代号已被占用')
    const t: Team = { id: nextId++, name: body.name, code: body.code.toUpperCase(), inviteCode: Math.random().toString(36).slice(2, 10).toUpperCase(), joinApproval: body.joinApproval ?? true, iterationWeeks: body.iterationWeeks ?? 2, estimateUnit: 'point', themeColor: body.themeColor ?? 'blue', status: 'ACTIVE' }
    members.push({ teamId: t.id, userId: u.id, role: 'LEADER', memberStatus: 'NORMAL', memberStatusStart: null, memberStatusEnd: null }); extraTeams.push(t)
    return { status: 201, data: { ...t, myRole: 'LEADER' } }
  }
  const teamId = Number(seg[1]); if (!teamId) throw err(404, 'NOT_FOUND', '小组不存在')
  if (seg.length === 2 && method === 'PATCH') {
    const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可修改小组设置')
    const t = allTeams().find(x => x.id === teamId); if (!t) throw err(404, 'NOT_FOUND', '小组不存在')
    if (body.name !== undefined) t.name = body.name
    if (body.description !== undefined) t.description = body.description
    if (body.themeColor !== undefined) t.themeColor = body.themeColor
    if (body.iterationWeeks !== undefined) t.iterationWeeks = body.iterationWeeks
    if (body.joinApproval !== undefined) t.joinApproval = body.joinApproval
    if (body.estimateUnit !== undefined) t.estimateUnit = String(body.estimateUnit).toLowerCase()
    if (body.settings !== undefined) t.settings = { ...JSON.parse(JSON.stringify(DEFAULT_SETTINGS)), ...body.settings, reminderThresholds: { ...DEFAULT_SETTINGS.reminderThresholds, ...(body.settings.reminderThresholds ?? {}) } }
    return { status: 200, data: teamView(t, me().id, true) }
  }
  if (seg.length === 3 && seg[2] === 'archive' && method === 'POST') {
    const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可归档小组')
    const t = allTeams().find(x => x.id === teamId); if (t) t.status = 'ARCHIVED'
    return { status: 200, data: t ? teamView(t, me().id) : null }
  }
  if (seg.length === 3 && seg[2] === 'leave' && method === 'POST') {
    const u = me(); const mine = memberOf(teamId, u.id); if (!mine) throw err(404, 'NOT_FOUND', '你不是该小组成员')
    if (mine.role === 'LEADER' && members.some(x => x.teamId === teamId && x.userId !== u.id)) throw err(400, 'VALIDATION', '组长需先移交组长职责再退出')
    members = members.filter(x => !(x.teamId === teamId && x.userId === u.id))
    return { status: 204, data: null }
  }
  if (seg[2] === 'join-requests') {
    const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可查看加入申请')
    if (method === 'GET') return { status: 200, data: joinRequests.filter(r => r.teamId === teamId && r.status === 'PENDING') }
    const req = joinRequests.find(r => r.id === Number(seg[3])); if (!req) throw err(404, 'NOT_FOUND', '申请不存在')
    if (seg[4] === 'approve') { req.status = 'APPROVED'; members.push({ teamId, userId: req.userId, role: body?.role ?? 'MEMBER', memberStatus: 'NORMAL', memberStatusStart: null, memberStatusEnd: null }) }
    if (seg[4] === 'reject') req.status = 'REJECTED'
    return { status: 204, data: null }
  }
  if (seg[2] === 'members') {
    requireMember(teamId)
    if (method === 'GET') return { status: 200, data: members.filter(m => m.teamId === teamId).map(m => { const u = byId(m.userId)!; return { userId: u.id, displayName: u.displayName, loginName: u.loginName, avatarColor: u.avatarColor, role: m.role, memberStatus: m.memberStatus, memberStatusStart: m.memberStatusStart, memberStatusEnd: m.memberStatusEnd } }) }
    if (method === 'PATCH' && seg[4] === 'status') {
      const { u, m } = requireMember(teamId); const target = memberOf(teamId, Number(seg[3])); if (!target) throw err(404, 'NOT_FOUND', '成员不存在')
      if (!(m.role === 'LEADER' || target.userId === u.id)) throw err(403, 'FORBIDDEN', '只能修改自己的状态（组长可改全员）')
      if (!['NORMAL', 'LEAVE', 'TRAVEL', 'SECONDMENT'].includes(body.memberStatus)) throw err(400, 'VALIDATION', 'memberStatus 须为 NORMAL / LEAVE / TRAVEL / SECONDMENT')
      target.memberStatus = body.memberStatus; target.memberStatusStart = body.memberStatusStart ?? null; target.memberStatusEnd = body.memberStatusEnd ?? null
      audit(teamId, u, 'UPDATE_MEMBER_STATUS', 'MEMBER', target.userId, `将 ${byId(target.userId)?.displayName ?? target.userId} 的成员状态设为 ${body.memberStatus}`)
      return { status: 200, data: { userId: target.userId, memberStatus: target.memberStatus, memberStatusStart: target.memberStatusStart, memberStatusEnd: target.memberStatusEnd } }
    }
    if (method === 'PATCH' && seg[4] === 'role') {
      const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可分配角色')
      const target = memberOf(teamId, Number(seg[3])); if (!target) throw err(404, 'NOT_FOUND', '成员不存在')
      if (target.role === 'LEADER' && body.role !== 'LEADER' && members.filter(x => x.teamId === teamId && x.role === 'LEADER').length <= 1) throw err(400, 'VALIDATION', '不能移除最后一名组长')
      target.role = body.role; audit(teamId, me(), 'CHANGE_ROLE', 'MEMBER', target.userId, `将 ${byId(target.userId)?.displayName ?? target.userId} 的角色调整为 ${body.role}`); return { status: 204, data: null }
    }
  }
  if (method === 'POST' && seg[2] === 'invite-code' && seg[3] === 'reset') {
    const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可重置邀请码')
    const t = allTeams().find(x => x.id === teamId); if (t) t.inviteCode = Math.random().toString(36).slice(2, 10).toUpperCase()
    return { status: 200, data: { inviteCode: t?.inviteCode } }
  }
  if (seg[2] === 'iterations') {
    requireMember(teamId)
    if (seg[3] === 'current' && method === 'GET') { const cur = iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE'); if (!cur) throw err(404, 'NOT_FOUND', '当前没有进行中的迭代'); return { status: 200, data: cur } }
    // 契约 0.5.3 结论 1：规划页默认迭代——优先 PLANNING，否则 ACTIVE，两者皆无 404
    if (seg[3] === 'planning-target' && method === 'GET') { const t = iterations.find(i => i.teamId === teamId && i.status === 'PLANNING') ?? iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE'); if (!t) throw err(404, 'NOT_FOUND', '当前没有规划中或进行中的迭代'); return { status: 200, data: t } }
    if (seg[4] === 'planning' && method === 'GET') return { status: 200, data: planning(teamId, Number(seg[3])) }
    return iterationRoute(method, teamId, seg, body)
  }
  if (seg[2] === 'key-dates' && method === 'DELETE') { requireMember(teamId); const i = keyDates.findIndex(k => k.id === Number(seg[3]) && k.teamId === teamId); if (i < 0) throw err(404, 'NOT_FOUND', '关键日期不存在'); keyDates.splice(i, 1); return { status: 204, data: null } }
  if (seg[2] === 'standup') return standupRoute(method, teamId, seg, body, q)
  if (seg[2] === 'overview' && method === 'GET') { requireMember(teamId); return { status: 200, data: overview(teamId) } }
  if (seg[2] === 'reminders' && seg[3] === 'scan' && method === 'POST') { const { m } = requireMember(teamId); if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可手动触发提醒扫描'); return { status: 200, data: { generated: scanReminders(teamId) } } }
  if (seg[2] === 'audit-logs' && method === 'GET') {
    // Q6：组长或平台管理员可见；普通成员 403，非成员 404 隔离（requireMember 先行，但管理员可非成员查看本组）。
    const u = me(); const m = memberOf(teamId, u.id)
    if (!m && !u.platformAdmin) throw err(404, 'NOT_FOUND', '小组或事项不存在')
    if (!(m?.role === 'LEADER' || u.platformAdmin)) throw err(403, 'FORBIDDEN', '仅组长或平台管理员可查看审计日志')
    return { status: 200, data: auditPage(auditLogs.filter(l => l.teamId === teamId), q) }
  }
  if (seg[2] === 'reports') return reportRoute(method, teamId, seg, body)
  if (seg[2] === 'trash' && method === 'GET') { const { u, m } = requireMember(teamId); const type = (q.get('type') ?? 'ALL').toUpperCase() // Q9 统一回收站
    const out: any[] = []
    // 条目形状与契约 TrashEntry 对齐（kind/id/code/itemType/reportKind/title/deletedAt/deletedById/deletedByName）
    if (type !== 'REPORT') out.push(...items.filter(i => i.deletedAt).map(i => ({ kind: 'ITEM', id: i.id, code: i.code, itemType: i.type, reportKind: null, title: i.title, deletedAt: i.deletedAt, deletedById: (i as any).deletedById ?? null, deletedByName: (i as any).deletedById ? (byId((i as any).deletedById)?.displayName ?? null) : null })))
    if (type !== 'ITEM') out.push(...reports.filter(r => r.teamId === teamId && r.deletedAt && canViewReport(r, u, m)).map(r => ({ kind: 'REPORT', id: r.id, code: null, itemType: null, reportKind: r.kind, title: r.title, deletedAt: r.deletedAt, deletedById: (r as any).deletedById ?? null, deletedByName: (r as any).deletedById ? (byId((r as any).deletedById)?.displayName ?? null) : null })))
    return { status: 200, data: out } }
  if (seg[2] === 'backlog' && method === 'GET') {
    requireMember(teamId)
    const tasks = items.filter(i => i.type === 'TASK' && !i.deletedAt)
    return { status: 200, data: tasks.map(t => { const stories = items.filter(k => k.parentId === t.id && !k.deletedAt).sort((a, b) => a.sortOrder - b.sortOrder); return { task: view(t), stories: stories.map(view), storyCount: stories.length, doneStoryCount: stories.filter(k => k.status === 'DONE').length } }) }
  }
  if (seg[2] === 'search' && method === 'GET') {
    requireMember(teamId); const kw = (q.get('q') ?? '').trim(); if (!kw) throw err(400, 'VALIDATION', '搜索关键词必填')
    const inComments = new Set(comments.filter(c => c.content.includes(kw)).map(c => c.itemId))
    return { status: 200, data: items.filter(i => !i.deletedAt && (i.title.includes(kw) || (i.description ?? '').includes(kw) || i.code.toLowerCase() === kw.toLowerCase() || i.code.includes(kw) || inComments.has(i.id))).map(view) }
  }
  if (seg[2] === 'saved-filters') {
    const { u } = requireMember(teamId)
    if (method === 'GET') return { status: 200, data: savedFilters.filter(f => f.teamId === teamId && f.userId === u.id) }
    if (method === 'POST') { if (!body.name || !body.criteria) throw err(400, 'VALIDATION', '名称与筛选条件必填'); const f: SavedFilter = { id: nextId++, userId: u.id, teamId, name: body.name, criteria: body.criteria, createdAt: new Date().toISOString() }; savedFilters.push(f); return { status: 201, data: f } }
    if (method === 'DELETE') { const i = savedFilters.findIndex(f => f.id === Number(seg[3]) && f.userId === u.id); if (i < 0) throw err(404, 'NOT_FOUND', '保存的筛选不存在'); savedFilters.splice(i, 1); return { status: 204, data: null } }
  }
  if (seg[2] === 'labels') return labelsRoute(method, teamId, seg, body, q)
  if (seg[2] === 'ai') return aiRoute(method, teamId, seg, body)
  if (seg[2] === 'imports') return importsRoute(method, teamId, seg, body)
  if (seg[2] === 'integrations') return integrationsRoute(method, teamId, seg, body)
  if (seg[2] === 'dispatch-batches') return batchRoute(method, teamId, seg, body)
  if (seg[2] === 'items') return itemsRoute(method, teamId, seg, body, q)
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// ---------------- M3：日期工具 / 迭代 / 站会 / 总览 / 提醒 / 报告 ----------------
const todayStr = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
function settingsOf(teamId: number) { const t = allTeams().find(x => x.id === teamId); return { ...JSON.parse(JSON.stringify(DEFAULT_SETTINGS)), ...(t?.settings ?? {}), reminderThresholds: { ...DEFAULT_SETTINGS.reminderThresholds, ...(t?.settings?.reminderThresholds ?? {}) } } }
function isWorkday(ds: string, st: any) { if ((st.specialHolidays ?? []).includes(ds)) return false; if ((st.specialWorkdays ?? []).includes(ds)) return true; const w = new Date(ds + 'T00:00:00').getDay(); return w >= 1 && w <= 5 }
function workdaysBetween(a: string, b: string, st: any) { if (!a || !b || a > b) return 0; let n = 0; const d = new Date(a + 'T00:00:00'); const end = new Date(b + 'T00:00:00'); while (d <= end) { const ds = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; if (isWorkday(ds, st)) n++; d.setDate(d.getDate() + 1) } return n }
function prevWorkday(ds: string, st: any) { const d = new Date(ds + 'T00:00:00'); do { d.setDate(d.getDate() - 1) } while (!isWorkday(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`, st)); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
function awayOn(m: { memberStatus: string; memberStatusStart: string | null; memberStatusEnd: string | null }, ds: string) { if (m.memberStatus !== 'LEAVE' && m.memberStatus !== 'TRAVEL') return false; if (m.memberStatusStart && ds < m.memberStatusStart) return false; if (m.memberStatusEnd && ds > m.memberStatusEnd) return false; return true }
function leaderOf(teamId: number) { return members.find(m => m.teamId === teamId && m.role === 'LEADER') }
let nextIterationId = 100
function iterationRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  const canManage = m.role === 'LEADER' || m.role === 'PO'
  const isLeader = m.role === 'LEADER' // Q3：迭代新建/编辑/开始/结束/删除仅组长（规划增减成员仍组长+PO）
  if (seg.length === 3) {
    if (method === 'GET') return { status: 200, data: iterations.filter(i => i.teamId === teamId) }
    if (method === 'POST') { if (!isLeader) throw err(403, 'FORBIDDEN', '仅组长可创建迭代'); if (!body.name) throw err(400, 'VALIDATION', '迭代名称必填')
      const weeks = allTeams().find(t => t.id === teamId)?.iterationWeeks ?? 2
      const start = body.startDate ?? todayStr(); const endD = new Date(start + 'T00:00:00'); endD.setDate(endD.getDate() + weeks * 7 - 3)
      const it = { id: nextIterationId++, teamId, name: body.name, goal: body.goal ?? '', startDate: start, endDate: body.endDate ?? `${endD.getFullYear()}-${String(endD.getMonth() + 1).padStart(2, '0')}-${String(endD.getDate()).padStart(2, '0')}`, status: 'PLANNING' }
      iterations.push(it); return { status: 201, data: it } }
  }
  const it = iterations.find(i => i.id === Number(seg[3]) && i.teamId === teamId); if (!it) throw err(404, 'NOT_FOUND', '迭代不存在')
  if (seg.length === 4 && method === 'PATCH') { if (!isLeader) throw err(403, 'FORBIDDEN', '仅组长可修改迭代'); for (const k of ['name', 'goal', 'startDate', 'endDate'] as const) if (body[k] !== undefined) (it as any)[k] = body[k]; return { status: 200, data: it } }
  if (seg[4] === 'start' && method === 'POST') { if (!isLeader) throw err(403, 'FORBIDDEN', '仅组长可开始迭代'); if (it.status !== 'PLANNING') throw err(422, 'ILLEGAL_TRANSITION', '只有未开始的迭代可以开始'); if (iterations.some(x => x.teamId === teamId && x.status === 'ACTIVE')) throw err(409, 'VERSION_CONFLICT', '已有进行中的迭代，请先结束它'); it.status = 'ACTIVE'; for (const mm of members.filter(x => x.teamId === teamId)) notify(mm.userId, teamId, 'SYSTEM', null, `迭代「${it.name}」已开始`, u.id); return { status: 200, data: it } }
  if (seg.length === 4 && method === 'DELETE') { if (!isLeader) throw err(403, 'FORBIDDEN', '仅组长可删除迭代'); if (it.status === 'ACTIVE') throw err(422, 'ILLEGAL_TRANSITION', '进行中的迭代不能删除，请先结束'); for (const i of items.filter(x => x.iterationId === it.id)) i.iterationId = null; iterations.splice(iterations.indexOf(it), 1); return { status: 204, data: null } }
  if (seg[4] === 'snapshot' && method === 'POST') return { status: 200, data: burndownOf(teamId, it) }
  if (seg[4] === 'burndown' && method === 'GET') return { status: 200, data: burndownOf(teamId, it) }
  if (seg[4] === 'key-dates') {
    if (method === 'GET') return { status: 200, data: keyDates.filter(k => k.iterationId === it.id) }
    if (method === 'POST') { if (!body.title || !body.date) throw err(400, 'VALIDATION', '标题与日期必填'); const kd: KeyDate = { id: nextKeyDateId++, teamId, iterationId: it.id, title: body.title, date: body.date, category: body.category ?? 'OTHER' }; keyDates.push(kd); return { status: 201, data: kd } }
  }
  if (seg[4] === 'close' && method === 'POST') return idemWrap(`iteration-close|${it.id}`, () => {
    if (!isLeader) throw err(403, 'FORBIDDEN', '仅组长可结束迭代')
    if (it.status !== 'ACTIVE') throw err(422, 'ILLEGAL_TRANSITION', '只有进行中的迭代可以结束')
    const decisions: { itemId: number; action: string }[] = body.decisions ?? []
    const unfinished = items.filter(i => !i.deletedAt && i.iterationId === it.id && i.type !== 'TASK' && i.status !== 'DONE' && i.status !== 'CANCELLED')
    let next = iterations.filter(x => x.teamId === teamId && x.status === 'PLANNING').sort((a, b) => a.id - b.id)[0]
    const needNext = unfinished.some(i => (decisions.find(d => d.itemId === i.id)?.action ?? 'NEXT') === 'NEXT')
    if (needNext && !next && !body.createNextIteration) throw err(422, 'VALIDATION', '没有可承接的下一迭代，且未同意自动新建')
    if (needNext && !next) { const st = new Date(it.endDate + 'T00:00:00'); st.setDate(st.getDate() + 3); const en = new Date(st); en.setDate(en.getDate() + (allTeams().find(t => t.id === teamId)?.iterationWeeks ?? 2) * 7 - 3); const f = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; next = { id: nextIterationId++, teamId, name: '下一迭代（自动新建）', goal: '', startDate: f(st), endDate: f(en), status: 'PLANNING' }; iterations.push(next) }
    let movedNext = 0, movedBacklog = 0
    for (const i of unfinished) { const a = decisions.find(d => d.itemId === i.id)?.action ?? 'NEXT'; if (a === 'NEXT' && next) { i.iterationId = next.id; movedNext++ } else { i.iterationId = null; movedBacklog++ } i.version++ }
    it.status = 'FINISHED'
    const improvements: string[] = (body.improvements ?? []).filter((s: string) => s && s.trim())
    if (next) for (const imp of improvements) createItemCore(teamId, u, m.role, { type: 'ADHOC', title: imp.trim(), destination: 'POOL', iterationId: next.id, labelNames: ['改进项'] })
    const summary = createReport(teamId, u.id, 'ITERATION_SUMMARY', null, null)
    for (const mm of members.filter(x => x.teamId === teamId)) notify(mm.userId, teamId, 'SYSTEM', null, `迭代「${it.name}」已结束，总结报告已生成`, u.id)
    return { status: 200, data: { iteration: it, movedToNext: movedNext, movedToBacklog: movedBacklog, nextIterationId: next?.id ?? null, reportId: summary.id } }
  })
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// 燃尽（mock 合成）：由种子事项的估算与完成时间确定性生成，非真实快照表；演示口径，报告中注明。
function burndownOf(teamId: number, it: any) {
  const st = settingsOf(teamId)
  const inIter = items.filter(i => !i.deletedAt && i.iterationId === it.id && i.type !== 'TASK' && i.status !== 'CANCELLED')
  const total = inIter.reduce((n, i) => n + (i.estimate ?? 0), 0)
  const days: string[] = []; { const d = new Date(it.startDate + 'T00:00:00'); const end = new Date(it.endDate + 'T00:00:00'); while (d <= end) { const ds = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; if (isWorkday(ds, st)) days.push(ds); d.setDate(d.getDate() + 1) } }
  const totalWd = Math.max(1, days.length)
  const ideal = days.map((ds, idx) => ({ date: ds, remainingEstimate: Math.round(total * (1 - (idx + 1) / totalWd) * 10) / 10, remainingCount: null, doneCount: null }))
  const lastDay = it.status === 'FINISHED' ? it.endDate : (todayStr() < it.endDate ? todayStr() : it.endDate)
  const points = days.filter(ds => ds <= lastDay).map(ds => ({ date: ds, remainingEstimate: Math.round((total - inIter.filter(i => i.status === 'DONE' && (i.lastProgressAt ?? '') <= ds + 'T23:59:59').reduce((n, i) => n + (i.estimate ?? 0), 0)) * 10) / 10, remainingCount: null, doneCount: null, live: ds === todayStr() }))
  return { iteration: it, points, ideal, totalEstimate: total }
}
function standupRoute(method: string, teamId: number, seg: string[], body: any, q: URLSearchParams) {
  requireMember(teamId); const st = settingsOf(teamId)
  if (method === 'PUT' && seg[3] === 'note') { const d = body.date ?? todayStr(); standupNotes.set(`${teamId}|${d}`, body.content ?? ''); return { status: 200, data: standupOf(teamId, d) } }
  if (method === 'GET' && seg.length === 3) { const d = q.get('date') ?? todayStr(); return { status: 200, data: standupOf(teamId, d) } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function standupOf(teamId: number, date: string) {
  const st = settingsOf(teamId); const prev = prevWorkday(date, st)
  const ms = members.filter(m => m.teamId === teamId).map(m => ({
    userId: m.userId, memberStatus: m.memberStatus,
    yesterdayProgress: progressList.filter(p => p.authorId === m.userId && p.createdAt.startsWith(prev)),
    yesterdayDone: items.filter(i => !i.deletedAt && i.status === 'DONE' && i.ownerIds.includes(m.userId) && (i.lastProgressAt ?? '').startsWith(prev)).map(view),
    todayItems: items.filter(i => !i.deletedAt && i.ownerIds.includes(m.userId) && (i.status === 'IN_PROGRESS' || i.status === 'NOT_STARTED')).map(view),
    blockedItems: items.filter(i => !i.deletedAt && i.ownerIds.includes(m.userId) && i.blocked).map(view),
  }))
  return { date, previousWorkday: prev, members: ms, note: standupNotes.get(`${teamId}|${date}`) ?? null }
}
function riskSignals(teamId: number) {
  const st = settingsOf(teamId); const th = st.reminderThresholds; const today = todayStr(); const out: any[] = []
  const live = items.filter(i => !i.deletedAt && i.status !== 'DONE' && i.status !== 'CANCELLED')
  for (const i of live) {
    if (i.blocked) out.push({ rule: 'BLOCKED_LONG', severity: 'HIGH', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 已阻塞：${i.blockReason ?? ''}` })
    if (i.dueDate && i.dueDate < today) out.push({ rule: 'OVERDUE', severity: 'HIGH', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 已逾期（截止 ${i.dueDate}）` })
    else if (i.dueDate && workdaysBetween(today, i.dueDate, st) <= th.dueSoonWorkdays && i.dueDate >= today) out.push({ rule: 'DUE_SOON', severity: 'MEDIUM', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 将在 ${i.dueDate} 到期` })
    if (i.status === 'IN_PROGRESS' && i.lastProgressAt && workdaysBetween(i.lastProgressAt.slice(0, 10), today, st) > th.staleInProgressWorkdays) out.push({ rule: 'STALE', severity: 'MEDIUM', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 长期未更新` })
    if (i.status === 'UNCLAIMED' && (i.priority === 'P0' || i.priority === 'P1')) out.push({ rule: 'UNCLAIMED_PRIORITY', severity: 'MEDIUM', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 高优先级仍无人认领` })
    if (i.nudgeCount >= 3) out.push({ rule: 'NUDGED_OFTEN', severity: 'LOW', itemId: i.id, itemCode: i.code, userId: null, message: `${i.code} ${i.title} 已被催 ${i.nudgeCount} 次` })
  }
  return out
}
function overview(teamId: number) {
  const st = settingsOf(teamId); const today = todayStr(); const { m } = requireMember(teamId)
  const active = iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE')
  const ms = members.filter(x => x.teamId === teamId).map(mm => {
    const mine = items.filter(i => !i.deletedAt && i.ownerIds.includes(mm.userId) && i.status !== 'DONE' && i.status !== 'CANCELLED')
    const inProg = mine.filter(i => i.status === 'IN_PROGRESS')
    const remainingCapacity = active ? Math.round(workdaysBetween(today, active.endDate, st) * Number(st.capacityPointsPerWorkday) * 10) / 10 : null
    return { userId: mm.userId, memberStatus: mm.memberStatus, memberStatusStart: mm.memberStatusStart, memberStatusEnd: mm.memberStatusEnd, inProgressItems: inProg.map(view), lastProgressAt: mine.map(i => i.lastProgressAt).filter(Boolean).sort().pop() ?? null, staleCount: inProg.filter(i => !i.lastProgressAt || workdaysBetween(i.lastProgressAt.slice(0, 10), today, st) > st.reminderThresholds.staleInProgressWorkdays).length, remainingPoints: mine.reduce((n, i) => n + (i.estimate ?? 0), 0), remainingCapacityPoints: remainingCapacity, free: inProg.length === 0 && !awayOn(mm, today) }
  })
  let health: any = null
  if (active) {
    const inIter = items.filter(i => !i.deletedAt && i.iterationId === active.id && i.type !== 'TASK')
    const total = inIter.length, done = inIter.filter(i => i.status === 'DONE').length
    const bd = burndownOf(teamId, active)
    const remaining = bd.points.length ? bd.points[bd.points.length - 1].remainingEstimate : bd.totalEstimate
    const idealToday = bd.ideal.filter(p => p.date <= today).pop()?.remainingEstimate ?? bd.totalEstimate
    health = { iteration: active, completionRate: total ? Math.round(done * 100 / total) : 0, remainingEstimate: remaining, idealRemainingEstimate: idealToday, behind: remaining > idealToday * 1.2, blockedCount: inIter.filter(i => i.blocked).length, staleCount: inIter.filter(i => i.status === 'IN_PROGRESS' && i.lastProgressAt && workdaysBetween(i.lastProgressAt.slice(0, 10), today, st) > st.reminderThresholds.staleInProgressWorkdays).length }
  }
  const canView = m.role === 'LEADER' || m.role === 'PO'
  return { members: ms, canViewRisks: canView, risks: canView ? riskSignals(teamId) : [], iterationHealth: health }
}
function scanReminders(teamId: number) {
  const today = todayStr(); let generated = 0
  const send = (uid: number, it: Item, rule: string, text: string) => { const key = `${rule}|${it.id}|${uid}|${today}`; if (reminderLog.has(key)) return; reminderLog.add(key); notify(uid, teamId, 'REMINDER', it, text); generated++ }
  for (const sig of riskSignals(teamId)) { const it = items.find(i => i.id === sig.itemId); if (!it) continue
    if (sig.rule === 'REVIEW_BACKLOG') continue
    const targets = it.ownerIds.length ? it.ownerIds : [leaderOf(teamId)?.userId].filter(Boolean) as number[]
    for (const uid of targets) { const mm = memberOf(teamId, uid); if (mm && awayOn(mm, today)) { const ld = leaderOf(teamId); if (ld && ld.userId !== uid) send(ld.userId, it, sig.rule + '_LEADER', `${sig.message}（负责人请假/出差，转提醒组长）`) } else send(uid, it, sig.rule, sig.message) } }
  return generated
}
// ---------------- 报告（） ----------------
// kind 与真实后端 ReportService 对齐：STANDUP_SUMMARY / WEEKLY / ITERATION_SUMMARY / PERSONAL_WEEKLY / RISK_SUMMARY。
// 可见范围服务端过滤；一个周期一份（generate 命中已有返回 existed=true）；重新生成 60 秒节流；
// 历史版本最多 5 个（覆盖前留存，可查看/恢复）；删除为软删除进回收站并记墓碑（自动任务不再重建该周期）。
let nextReportVersionId = 1
const deletedReportPeriods = new Set<string>()
const REPORT_KINDS = ['STANDUP_SUMMARY', 'WEEKLY', 'ITERATION_SUMMARY', 'PERSONAL_WEEKLY', 'RISK_SUMMARY']
const normKind = (k: string) => k === 'STANDUP' ? 'STANDUP_SUMMARY' : k
const fmtD = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
function mondayOf(ds: string) { const d = new Date(ds + 'T00:00:00'); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return fmtD(d) }
function reportView(r: MockReport) { const { versions, lastRegeneratedAt, ...rest } = r as any
  const u = sessionUserId ? byId(sessionUserId) : undefined; const m = u ? memberOf(r.teamId, u.id) : undefined
  let allowedActions: string[] = []
  if (u && m) { if (r.deletedAt) allowedActions = canEditReport(r, u, m) ? ['RESTORE'] : []; else { allowedActions = ['VIEW']; if (canEditReport(r, u, m)) allowedActions.push('EDIT', 'DELETE'); if (canGenReport(r.kind, u, m)) allowedActions.push('REGENERATE') } }
  return { ...rest, contentMd: r.content, editedByHuman: !!r.editedByHuman, version: r.version ?? 1, allowedActions } }
function canViewReport(r: MockReport, u: StoredUser, m: any) { if (r.kind === 'PERSONAL_WEEKLY') return r.ownerId === u.id; if (r.kind === 'RISK_SUMMARY') return m.role === 'LEADER' || m.role === 'PO'; return true }
function canGenReport(kind: string, u: StoredUser, m: any) { kind = normKind(kind); if (kind === 'ITERATION_SUMMARY') return m.role === 'LEADER'; if (kind === 'PERSONAL_WEEKLY') return true; return m.role === 'LEADER' || m.role === 'PO' }
function canEditReport(r: MockReport, u: StoredUser, m: any) { if (r.kind === 'PERSONAL_WEEKLY') return r.ownerId === u.id; if (r.kind === 'ITERATION_SUMMARY') return m.role === 'LEADER'; return m.role === 'LEADER' || m.role === 'PO' }
function pushReportVersion(r: MockReport) { r.versions = r.versions ?? []; r.versions.unshift({ id: nextReportVersionId++, reportId: r.id, title: r.title, contentMd: r.content, editedByHuman: !!r.editedByHuman, createdAt: new Date().toISOString() }); r.versions = r.versions.slice(0, 5) }
function effectivePeriod(teamId: number, kind: string, ps: string | null, pe: string | null): { ps: string | null; pe: string | null } {
  const today = todayStr(); kind = normKind(kind)
  if (kind === 'STANDUP_SUMMARY' || kind === 'RISK_SUMMARY') { const d = ps ?? today; return { ps: d, pe: d } }
  if (kind === 'WEEKLY' || kind === 'PERSONAL_WEEKLY') { const from = ps ?? mondayOf(today); const toD = new Date(from + 'T00:00:00'); toD.setDate(toD.getDate() + 6); return { ps: from, pe: pe ?? fmtD(toD) } }
  if (kind === 'ITERATION_SUMMARY') { const it = iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE') ?? iterations.filter(i => i.teamId === teamId).pop(); return { ps: it?.startDate ?? ps, pe: it?.endDate ?? pe } }
  return { ps, pe }
}
function buildReportContent(teamId: number, userId: number, kind: string, ps: string | null, pe: string | null): { title: string; content: string } {
  kind = normKind(kind); const today = todayStr(); const name = (id: number) => byId(id)?.displayName ?? '未知成员'
  const live = items.filter(i => !i.deletedAt)
  const line = (i: Item) => `- ${i.code} ${i.title}${i.blocked ? `（阻塞：${i.blockReason}）` : ''}`
  let title = '', content = ''
  if (kind === 'STANDUP_SUMMARY') { const d = ps ?? today; const s = standupOf(teamId, d)
    title = `站会摘要（${d}）`; content = `# ${title}\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n` + s.members.map(mm => `\n## ${name(mm.userId)}\n- 昨日进展：${mm.yesterdayProgress.length} 条\n- 今日在做：${mm.todayItems.length} 件\n- 阻塞：${mm.blockedItems.length} 件`).join('') + (s.note ? `\n\n## 站会备注\n${s.note}\n` : '\n')
  } else if (kind === 'ITERATION_SUMMARY') { const it = iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE') ?? iterations.filter(i => i.teamId === teamId).pop()
    const inIter = it ? live.filter(i => i.iterationId === it.id && i.type !== 'TASK') : []; const done = inIter.filter(i => i.status === 'DONE')
    title = `迭代总结（${it?.name ?? '无迭代'}）`; content = `# ${title}\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n\n## 完成情况\n- 完成率：${inIter.length ? Math.round(done.length * 100 / inIter.length) : 0}%（${done.length}/${inIter.length}）\n\n## 已完成\n${done.map(line).join('\n') || '- 无'}\n\n## 未完成\n${inIter.filter(i => i.status !== 'DONE' && i.status !== 'CANCELLED').map(line).join('\n') || '- 无'}\n`
  } else if (kind === 'PERSONAL_WEEKLY') { const mine = live.filter(i => i.ownerIds.includes(userId))
    title = `个人周报（${name(userId)}）`; content = `# ${title}\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n\n## 本周完成\n${mine.filter(i => i.status === 'DONE').map(line).join('\n') || '- 无'}\n\n## 进行中\n${mine.filter(i => i.status === 'IN_PROGRESS').map(i => `- ${i.code} ${i.title}（${i.progressPercent}%）`).join('\n') || '- 无'}\n`
  } else if (kind === 'RISK_SUMMARY') { const sigs = riskSignals(teamId); title = `风险摘要（${ps ?? today}）`; content = `# ${title}\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n` + ['HIGH', 'MEDIUM', 'LOW'].map(sev => `\n## ${sev === 'HIGH' ? '高' : sev === 'MEDIUM' ? '中' : '低'}风险（${sigs.filter(s => s.severity === sev).length}）\n${sigs.filter(s => s.severity === sev).map(s => `- ${s.message}`).join('\n') || '- 无'}`).join('') + '\n'
  } else { const done = live.filter(i => i.status === 'DONE'); const prog = live.filter(i => i.status === 'IN_PROGRESS')
    title = `周报（${ps ?? ''} ~ ${pe ?? today}）`; content = `# ${title}\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n\n## 本周完成（${done.length} 件）\n${done.slice(0, 10).map(line).join('\n') || '- 无'}\n\n## 进行中（${prog.length} 件）\n${prog.map(i => `- ${i.code} ${i.title}（${i.progressPercent}%）`).join('\n') || '- 无'}\n\n## 风险\n${riskSignals(teamId).filter(s => s.severity === 'HIGH').map(s => `- ${s.message}`).join('\n') || '- 无'}\n` }
  return { title, content }
}
function createReport(teamId: number, userId: number, kind: string, ps: string | null, pe: string | null): MockReport {
  kind = normKind(kind); const ep = effectivePeriod(teamId, kind, ps, pe); const c = buildReportContent(teamId, userId, kind, ep.ps, ep.pe)
  const now = new Date().toISOString()
  const r: MockReport = { id: nextReportId++, teamId, kind, title: c.title, periodStart: ep.ps, periodEnd: ep.pe, ownerId: kind === 'PERSONAL_WEEKLY' ? userId : null, content: c.content, aiGenerated: false, edited: false, editedByHuman: false, version: 1, deletedAt: null, lastRegeneratedAt: null, versions: [], createdAt: now, updatedAt: now }
  reports.unshift(r); return r
}
function findReport(id: number) { return reports.find(x => x.id === id) }
function patchReport(r: MockReport, u: StoredUser, m: any, body: any) {
  if (!canEditReport(r, u, m)) throw err(403, 'FORBIDDEN', '无权编辑该报告')
  if (body?.version != null && body.version !== r.version) throw err(409, 'VERSION_CONFLICT', '该报告已被他人修改，请刷新后重试')
  if (body?.title !== undefined) r.title = body.title
  const content = body?.contentMd ?? body?.content
  if (content !== undefined) { r.content = content; r.edited = true; r.editedByHuman = true }
  r.version++; r.updatedAt = new Date().toISOString(); return { status: 200, data: reportView(r) }
}
function reportRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  if (method === 'POST' && seg[3] === 'generate') return idemWrap(`reports-generate|${teamId}`, () => {
    const kind = normKind(body?.kind ?? '')
    if (!REPORT_KINDS.includes(kind)) throw err(400, 'VALIDATION', 'kind 须为 STANDUP_SUMMARY / WEEKLY / ITERATION_SUMMARY / PERSONAL_WEEKLY / RISK_SUMMARY')
    if (!canGenReport(kind, u, m)) throw err(403, 'FORBIDDEN', '无权生成该类报告')
    const ep = effectivePeriod(teamId, kind, body.periodStart ?? null, body.periodEnd ?? null)
    if (ep.ps && ep.ps > todayStr()) throw err(400, 'VALIDATION', '不能选择未来的周期')
    const existing = reports.find(r => r.teamId === teamId && !r.deletedAt && r.kind === kind && r.periodStart === ep.ps && r.periodEnd === ep.pe && (kind !== 'PERSONAL_WEEKLY' || r.ownerId === u.id))
    if (existing) return { status: 200, data: { ...reportView(existing), existed: true } }
    const r = createReport(teamId, u.id, kind, body.periodStart ?? null, body.periodEnd ?? null)
    audit(teamId, u, 'GENERATE_REPORT', 'REPORT', r.id, `生成报告「${r.title}」`)
    return { status: 201, data: { ...reportView(r), existed: false } }
  })
  if (method === 'GET' && seg.length === 3) return { status: 200, data: reports.filter(r => r.teamId === teamId && !r.deletedAt && canViewReport(r, u, m)).map(reportView) }
  // 团队路径别名（真实后端把全部报告端点挂在 /teams/{tid}/reports 下）：转顶层逻辑统一处理
  if (seg[3] === 'trash' || seg[3] !== 'generate') return reportTopRoute(method, ['reports', ...seg.slice(3)], body)
  const r = findReport(Number(seg[3])); if (!r || r.teamId !== teamId || r.deletedAt) throw err(404, 'NOT_FOUND', '报告不存在')
  if (method === 'GET') { if (!canViewReport(r, u, m)) throw err(404, 'NOT_FOUND', '报告不存在'); return { status: 200, data: reportView(r) } }
  if (method === 'PATCH') return patchReport(r, u, m, body)
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// 顶层 /reports/{id} 系列（修订二契约：regenerate / versions / restore / trash / PATCH / DELETE）
function reportTopRoute(method: string, seg: string[], body: any) {
  const u = me()
  if (method === 'GET' && seg[1] === 'trash') return { status: 200, data: reports.filter(r => r.deletedAt && memberOf(r.teamId, u.id) && canViewReport(r, u, memberOf(r.teamId, u.id))).map(reportView) }
  const r = findReport(Number(seg[1])); if (!r) throw err(404, 'NOT_FOUND', '报告不存在')
  const mm = memberOf(r.teamId, u.id); if (!mm) throw err(404, 'NOT_FOUND', '报告不存在')
  if (method === 'POST' && seg[2] === 'restore') { if (!canEditReport(r, u, mm)) throw err(403, 'FORBIDDEN', '无权恢复该报告'); r.deletedAt = null; r.version++; r.updatedAt = new Date().toISOString(); return { status: 200, data: reportView(r) } }
  if (r.deletedAt) throw err(404, 'NOT_FOUND', '报告不存在')
  if (method === 'GET' && seg.length === 2) { if (!canViewReport(r, u, mm)) throw err(404, 'NOT_FOUND', '报告不存在'); return { status: 200, data: reportView(r) } }
  if (method === 'POST' && seg[2] === 'regenerate') {
    if (!canGenReport(r.kind, u, mm) || (r.kind === 'PERSONAL_WEEKLY' && r.ownerId !== u.id)) throw err(403, 'FORBIDDEN', '无权重新生成该报告')
    if (r.lastRegeneratedAt && Date.now() - new Date(r.lastRegeneratedAt).getTime() < 60000) throw err(409, 'REGENERATE_THROTTLED', '同一份报告 1 分钟内只能重新生成一次')
    pushReportVersion(r)
    const c = buildReportContent(r.teamId, u.id, r.kind, r.periodStart, r.periodEnd)
    r.title = c.title; r.content = c.content; r.edited = false; r.editedByHuman = false; r.version++
    r.lastRegeneratedAt = new Date().toISOString(); r.updatedAt = r.lastRegeneratedAt
    return { status: 200, data: reportView(r) }
  }
  if (seg[2] === 'versions') {
    if (!canViewReport(r, u, mm)) throw err(404, 'NOT_FOUND', '报告不存在')
    if (method === 'GET' && seg.length === 3) return { status: 200, data: (r.versions ?? []).map(v => ({ ...v })) }
    const v = (r.versions ?? []).find(x => x.id === Number(seg[3])); if (!v) throw err(404, 'NOT_FOUND', '历史版本不存在')
    if (method === 'GET') return { status: 200, data: { ...v } }
    if (method === 'POST' && seg[4] === 'restore') { if (!canEditReport(r, u, mm)) throw err(403, 'FORBIDDEN', '无权恢复该报告'); pushReportVersion(r); r.content = v.contentMd; r.title = v.title; r.editedByHuman = v.editedByHuman; r.edited = v.editedByHuman; r.version++; r.updatedAt = new Date().toISOString(); return { status: 200, data: reportView(r) } }
  }
  if (method === 'PATCH' && seg.length === 2) return patchReport(r, u, mm, body)
  if (method === 'DELETE' && seg.length === 2) { if (!canEditReport(r, u, mm)) throw err(403, 'FORBIDDEN', '无权删除该报告'); r.deletedAt = new Date().toISOString(); deletedReportPeriods.add(`${r.teamId}|${r.kind}|${r.periodStart}|${r.periodEnd}|${r.ownerId ?? ''}`); return { status: 204, data: null } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}

// ---------------- 迭代规划 ----------------
function weekdays(a: string, b: string) { let n = 0; const d = new Date(a + 'T00:00:00'); const end = new Date(b + 'T00:00:00'); while (d <= end) { const w = d.getDay(); if (w >= 1 && w <= 5) n++; d.setDate(d.getDate() + 1) } return n }
function planning(teamId: number, iterationId: number) {
  const it = iterations.find(i => i.id === iterationId && i.teamId === teamId); if (!it) throw err(404, 'NOT_FOUND', '迭代不存在')
  const inIter = items.filter(i => !i.deletedAt && i.iterationId === iterationId && i.type !== 'TASK')
  const byTask = new Map<number | null, Item[]>()
  for (const i of inIter) { const k = i.type === 'STORY' ? i.parentId : null; byTask.set(k, [...(byTask.get(k) ?? []), i]) }
  const groups = [...byTask.entries()].map(([tid, list]) => ({ task: tid ? view(items.find(t => t.id === tid)!) : null, items: list.map(view) }))
  groups.sort((a, b) => (a.task ? 0 : 1) - (b.task ? 0 : 1))
  const teamMembers = members.filter(m => m.teamId === teamId)
  const loads = teamMembers.map(m => {
    const mine = inIter.filter(i => i.ownerIds.includes(m.userId) && i.status !== 'DONE' && i.status !== 'CANCELLED')
    return { userId: m.userId, points: mine.reduce((n, i) => n + (i.estimate ?? 0) / Math.max(1, i.ownerIds.length), 0), itemCount: mine.length }
  })
  const totalDays = it.startDate && it.endDate ? weekdays(it.startDate, it.endDate) : 0
  const st = settingsOf(teamId); const today = todayStr(); const mm2 = (m: any) => members.find(x => x.teamId === teamId && x.userId === m.userId)!
  const capacities = teamMembers.map(m => {
    const claimed = loads.find(l => l.userId === m.userId)?.points ?? 0
    const awayWhole = (m.memberStatus === 'LEAVE' || m.memberStatus === 'TRAVEL')
    const from = today > it.startDate ? today : it.startDate
    const remainingDays = awayWhole ? 0 : workdaysBetween(from, it.endDate, st)
    const capacityPoints = Math.round(remainingDays * Number(st.capacityPointsPerWorkday) * 10) / 10
    void mm2
    return { userId: m.userId, availableDays: awayWhole ? 0 : totalDays, claimedPoints: claimed, remainingDays, capacityPoints, overCapacity: capacityPoints > 0 && claimed > capacityPoints }
  })
  return { iteration: it, groups, loads, capacities }
}
// ---------------- 批量下发 ----------------
function batchSummary(b: Batch) {
  const list = items.filter(i => i.batchId === b.id && !i.deletedAt)
  return { batchId: b.id, teamId: b.teamId, creatorId: b.creatorId, title: b.title, type: b.type, total: list.length, doneCount: list.filter(i => i.status === 'DONE').length, members: list.map(i => ({ userId: i.ownerIds[0], itemId: i.id, status: i.status })), createdAt: b.createdAt }
}
function createItemCore(teamId: number, creator: StoredUser, role: string, body: any): Item {
  if (!body.title || !body.type) throw err(400, 'VALIDATION', '类型与标题必填')
  const isPO = role === 'PO' || role === 'LEADER'
  if ((body.type === 'TASK' || body.type === 'STORY') && !isPO) throw err(403, 'FORBIDDEN', '只有 PO 可以创建任务和故事')
  if (body.type === 'STORY') {
    if (!body.parentId) throw err(400, 'VALIDATION', '故事必须选择所属任务')
    if (!body.storyCategory) throw err(400, 'VALIDATION', '故事必须选择类别')
    const parent = items.find(i => i.id === body.parentId && !i.deletedAt)
    if (!parent || parent.type !== 'TASK') throw err(400, 'VALIDATION', '所属任务不存在或不是任务')
  } else if (body.parentId) throw err(400, 'VALIDATION', '只有故事可以挂在任务下')
  const dest = body.destination ?? 'ASSIGN'
  const ownerIds: number[] = body.ownerIds ?? (body.assigneeId ? [body.assigneeId] : [])
  if (dest === 'ASSIGN' && body.type !== 'TASK' && !ownerIds.length) throw err(400, 'VALIDATION', '直接指派时必须选择负责人')
  if (body.type === 'TASK' && body.iterationId) throw err(400, 'VALIDATION', '任务没有迭代归属')
  // 契约 0.5.3 结论 1：新建时指定迭代同样只允许 PLANNING / ACTIVE
  if (body.iterationId) { const tgt = iterations.find(x => x.id === body.iterationId && x.teamId === teamId); if (!tgt) throw err(400, 'VALIDATION', '迭代不存在'); if (tgt.status !== 'PLANNING' && tgt.status !== 'ACTIVE') throw err(400, 'VALIDATION', '已结束的迭代不能再排入事项') }
  const it: Item = {
    id: nextItemId, code: `SKY-${nextItemId}`, type: body.type, title: body.title, description: body.description ?? '',
    status: body.type === 'TASK' ? 'NOT_STARTED' : dest === 'ASSIGN' ? 'NOT_STARTED' : 'UNCLAIMED',
    priority: body.priority ?? 'P2', creatorId: creator.id,
    ownerIds: body.type === 'TASK' ? [] : dest === 'ASSIGN' ? ownerIds : [],
    acceptorId: body.type === 'TASK' ? null : body.acceptorId ?? creator.id,
    storyCategory: body.type === 'STORY' ? body.storyCategory : null,
    poId: body.type === 'TASK' ? body.poId ?? creator.id : null,
    batchId: body.batchId ?? null, assignedDirectly: dest === 'ASSIGN' && body.type !== 'TASK',
    sortOrder: nextItemId, taskStatus: body.type === 'TASK' ? 'NOT_STARTED' : null,
    collaboratorIds: [], inviteeIds: dest === 'INVITE' ? body.inviteeIds ?? [] : [],
    parentId: body.type === 'STORY' ? body.parentId : null,
    iterationId: body.type === 'TASK' ? null : body.iterationId ?? null,
    dueDate: body.dueDate ?? null, estimate: body.estimate ?? null, progressPercent: 0, blocked: false, blockReason: null,
    nudgeCount: 0, labels: body.labelNames ?? [], childCount: 0, childDoneCount: 0, commentCount: 0, version: 1,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), lastProgressAt: null, deletedAt: null, checklist: [],
  }
  nextItemId++; items.push(it); act(it.id, creator.id, 'CREATE', null, it.status, '创建事项')
  for (const oid of it.ownerIds) if (oid !== creator.id) notify(oid, teamId, 'ASSIGNMENT', it, `你被指派了 ${it.code} ${it.title}`, creator.id)
  if (it.parentId) { const p = items.find(x => x.id === it.parentId); if (p) deriveTask(p) }
  return it
}
function batchRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  if (method === 'POST' && seg.length === 3) return idemWrap(`dispatch-batches|${teamId}`, () => {
    if (!body.type || !body.title || !Array.isArray(body.memberIds) || !body.memberIds.length) throw err(400, 'VALIDATION', '类型、标题与接收成员必填')
    if (body.type === 'TASK') throw err(400, 'VALIDATION', '任务不能批量下发')
    const b: Batch = { id: nextBatchId++, teamId, creatorId: u.id, title: body.title, type: body.type, createdAt: new Date().toISOString() }
    // 先校验第一条（故事权限/父任务），避免半批生成
    createItemCore(teamId, u, m.role, { ...body, destination: 'ASSIGN', ownerIds: [body.memberIds[0]], batchId: b.id })
    batches.push(b)
    for (const uid of body.memberIds.slice(1)) createItemCore(teamId, u, m.role, { ...body, destination: 'ASSIGN', ownerIds: [uid], batchId: b.id })
    return { status: 201, data: batchSummary(b) }
  })
  const b = batches.find(x => x.id === Number(seg[3]) && x.teamId === teamId); if (!b) throw err(404, 'NOT_FOUND', '批次不存在')
  if (method === 'GET') return { status: 200, data: batchSummary(b) }
  if (method === 'POST' && seg[4] === 'nudge') {
    const today = new Date().toISOString().slice(0, 10)
    const nudgedItemIds: number[] = [], skippedItemIds: number[] = [], redirectedItemIds: number[] = []
    for (const it of items.filter(i => i.batchId === b.id && !i.deletedAt && i.status !== 'DONE' && i.status !== 'CANCELLED')) {
      const owner = it.ownerIds[0]; if (!owner) { skippedItemIds.push(it.id); continue }
      if (nudges.some(n => n.itemId === it.id && n.fromUserId === u.id && n.date === today)) { skippedItemIds.push(it.id); continue }
      const om = memberOf(teamId, owner)
      if (om && (om.memberStatus === 'LEAVE' || om.memberStatus === 'TRAVEL')) {
        const leader = members.find(x => x.teamId === teamId && x.role === 'LEADER')
        if (!leader) { skippedItemIds.push(it.id); continue }
        nudges.push({ id: nextId++, itemId: it.id, fromUserId: u.id, toUserId: leader.userId, date: today, createdAt: new Date().toISOString() }); redirectedItemIds.push(it.id); continue
      }
      nudges.push({ id: nextId++, itemId: it.id, fromUserId: u.id, toUserId: owner, date: today, createdAt: new Date().toISOString() }); it.nudgeCount++; nudgedItemIds.push(it.id)
    }
    return { status: 200, data: { nudgedItemIds, skippedItemIds, redirectedItemIds } }
  }
  if (method === 'POST' && seg[4] === 'cancel') {
    if (!body?.reason) throw err(400, 'VALIDATION', '整批取消必须填写原因')
    for (const it of items.filter(i => i.batchId === b.id && !i.deletedAt && i.status !== 'DONE' && i.status !== 'CANCELLED')) {
      const from = it.status; it.status = 'CANCELLED'; it.version++; act(it.id, u.id, 'CANCEL', from, 'CANCELLED', body.reason)
    }
    return { status: 200, data: batchSummary(b) }
  }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// ---------------- 事项 ----------------
function itemsRoute(method: string, teamId: number, seg: string[], body: any, q: URLSearchParams) {
  if (seg[3] === 'export' && method === 'GET') {
    requireMember(teamId)
    const rows = items.filter(i => !i.deletedAt).map(i => [i.code, i.title, i.type, i.status, i.ownerIds.map(id => byId(id)?.displayName ?? '').join('、'), i.estimate ?? '', i.dueDate ?? ''].map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))
    return { status: 200, data: { mockCsv: '﻿编号,标题,类型,状态,负责人,估算,截止日期\n' + rows.join('\n') } }
  }
  if (seg.length === 3) {
    requireMember(teamId)
    if (method === 'GET') {
      let list = items.filter(i => q.get('includeDeleted') === 'true' ? true : !i.deletedAt)
      const status = q.get('status'), type = q.get('type'), owner = q.get('ownerId'), parent = q.get('parentId'), iter = q.get('iterationId'), scope = q.get('scope') ?? 'all'
      if (status) list = list.filter(i => i.status === status)
      if (type) list = list.filter(i => i.type === type)
      if (owner) list = list.filter(i => i.ownerIds.includes(Number(owner)))
      if (parent) list = list.filter(i => i.parentId === Number(parent))
      if (iter) list = list.filter(i => i.iterationId === Number(iter))
      else if (scope === 'current') { const cur = iterations.find(i => i.teamId === teamId && i.status === 'ACTIVE'); list = list.filter(i => i.iterationId === cur?.id) }
      else if (scope === 'daily') list = list.filter(i => i.iterationId === null)
      return { status: 200, data: list.map(view) }
    }
    if (method === 'POST') return idemWrap(`items|${teamId}`, () => { const { u, m } = requireMember(teamId); const it = createItemCore(teamId, u, m.role, body); return { status: 201, data: view(it) } })
  }
  const itemId = Number(seg[3])
  if (seg[4] === 'restore' && method === 'POST') { const it = getItem(teamId, itemId, true); it.deletedAt = null; it.version++; act(it.id, me().id, 'RESTORE', it.status, it.status, '从回收站恢复'); if (it.parentId) { const p = items.find(x => x.id === it.parentId); if (p) deriveTask(p) } return { status: 200, data: view(it) } }
  const it = getItem(teamId, itemId)
  if (seg.length === 4 && method === 'GET') return { status: 200, data: view(it) }
  if (seg.length === 4 && method === 'DELETE') {
    const { u, m } = requireMember(teamId)
    if (!(m.role !== 'MEMBER' || it.creatorId === u.id)) throw err(403, 'FORBIDDEN', '无权删除该事项')
    it.deletedAt = new Date().toISOString(); it.version++; act(it.id, u.id, 'DELETE', it.status, it.status, '移入回收站'); audit(teamId, u, 'DELETE_ITEM', 'ITEM', it.id, `删除事项 ${it.code}「${it.title}」（移入回收站）`)
    if (it.parentId) { const p = items.find(x => x.id === it.parentId); if (p) deriveTask(p) }
    return { status: 204, data: null }
  }
  if (seg.length === 4 && method === 'PATCH') {
    const { u, m } = requireMember(teamId)
    if (!(m.role !== 'MEMBER' || it.creatorId === u.id || it.ownerIds.includes(u.id))) throw err(403, 'FORBIDDEN', '只能编辑自己创建或负责的事项')
    if (body.version !== it.version) throw err(409, 'VERSION_CONFLICT', '该事项已被他人修改，请刷新后重试')
    if ('parentId' in body && body.parentId != null) {
      if (it.type !== 'STORY') throw err(400, 'VALIDATION', '只有故事可以挂在任务下')
      const parent = items.find(i => i.id === body.parentId && !i.deletedAt); if (!parent || parent.type !== 'TASK') throw err(400, 'VALIDATION', '所属任务不存在或不是任务')
    }
    if (it.type === 'TASK' && 'iterationId' in body && body.iterationId != null) throw err(400, 'VALIDATION', '任务没有迭代归属')
    // 契约 0.5.3 结论 1：目标迭代须为 PLANNING 或 ACTIVE，已结束迭代拒绝排入（与真实后端一致）
    if ('iterationId' in body && body.iterationId != null) { const tgt = iterations.find(x => x.id === body.iterationId && x.teamId === teamId); if (!tgt) throw err(400, 'VALIDATION', '迭代不存在'); if (tgt.status !== 'PLANNING' && tgt.status !== 'ACTIVE') throw err(400, 'VALIDATION', '已结束的迭代不能再排入事项') }
    for (const k of ['title', 'description', 'priority', 'acceptorId', 'dueDate', 'estimate', 'iterationId', 'parentId', 'storyCategory', 'poId'] as const) if (k in body) (it as any)[k] = body[k]
    if ('ownerIds' in body) { if (!Array.isArray(body.ownerIds)) throw err(400, 'VALIDATION', 'ownerIds 须为数组'); it.ownerIds = body.ownerIds }
    if ('labelNames' in body) { if (!Array.isArray(body.labelNames)) throw err(400, 'VALIDATION', 'labelNames 须为数组'); it.labels = body.labelNames }
    it.version++; it.updatedAt = new Date().toISOString(); act(it.id, u.id, 'EDIT', it.status, it.status, '编辑事项'); return { status: 200, data: view(it) }
  }
  if (seg[4] === 'join' && method === 'POST') {
    const { u } = requireMember(teamId)
    if (body.version !== it.version) throw err(409, 'VERSION_CONFLICT', '该事项已被他人修改，请刷新后重试')
    if (it.type === 'TASK') throw err(422, 'ILLEGAL_TRANSITION', '任务不认领，不能加入')
    if (!it.ownerIds.length) throw err(422, 'ILLEGAL_TRANSITION', '该事项还没有负责人，请直接认领')
    if (it.status === 'DONE' || it.status === 'CANCELLED') throw err(422, 'ILLEGAL_TRANSITION', '已结束的事项不能加入')
    if (it.ownerIds.includes(u.id)) throw err(409, 'VERSION_CONFLICT', '你已是该事项的负责人')
    const prev = [...it.ownerIds]; it.ownerIds.push(u.id); it.version++; it.updatedAt = new Date().toISOString()
    act(it.id, u.id, 'JOIN', it.status, it.status, '加入共同负责'); for (const o of prev) notify(o, teamId, 'ITEM_UPDATE', it, `${u.displayName} 加入共同负责 ${it.code}`, u.id)
    return { status: 200, data: view(it) }
  }
  if (seg[4] === 'stories') {
    requireMember(teamId)
    if (it.type !== 'TASK') throw err(400, 'VALIDATION', '只有任务有故事列表')
    if (method === 'GET') return { status: 200, data: items.filter(k => k.parentId === it.id && !k.deletedAt).sort((a, b) => a.sortOrder - b.sortOrder).map(view) }
    if (method === 'POST' && seg[5] === 'reorder') {
      const { u, m } = requireMember(teamId)
      if (!(m.role === 'PO' || m.role === 'LEADER' || it.creatorId === u.id)) throw err(403, 'FORBIDDEN', '只有 PO、组长或任务创建人可以调整故事顺序')
      if (!Array.isArray(body.orderedIds)) throw err(400, 'VALIDATION', 'orderedIds 必填')
      body.orderedIds.forEach((id: number, idx: number) => { const s = items.find(k => k.id === id && k.parentId === it.id); if (s) s.sortOrder = idx + 1 })
      return { status: 200, data: items.filter(k => k.parentId === it.id && !k.deletedAt).sort((a, b) => a.sortOrder - b.sortOrder).map(view) }
    }
  }
  if (seg[4] === 'checklist') return checklistRoute(teamId, it, method, seg, body)
  if (seg[4] === 'transitions' && method === 'POST') return transition(teamId, it, body)
  if (seg[4] === 'nudge' && seg.length === 5 && method === 'POST') {
    const { u } = requireMember(teamId)
    if (it.type === 'TASK') throw err(422, 'ILLEGAL_TRANSITION', '任务不催办，请催它下面的故事')
    if (it.ownerIds.includes(u.id)) throw err(400, 'VALIDATION', '不能催自己负责的事项')
    if (!['NOT_STARTED', 'IN_PROGRESS', 'IN_REVIEW'].includes(it.status)) throw err(422, 'ILLEGAL_TRANSITION', '当前状态不能催')
    const today = todayStr(); const targets = it.ownerIds.length ? it.ownerIds : [leaderOf(teamId)?.userId].filter(Boolean) as number[]
    const nudgedUserIds: number[] = []; let redirected = false
    for (const uid of targets) {
      if (nudges.some(n => n.itemId === it.id && n.fromUserId === u.id && n.toUserId === uid && n.date === today)) throw err(409, 'VERSION_CONFLICT', '今天已经催过该事项了')
      const mm = memberOf(teamId, uid)
      if (mm && awayOn(mm, today)) { const ld = leaderOf(teamId); if (ld && ld.userId !== uid) { nudges.push({ id: nextId++, itemId: it.id, fromUserId: u.id, toUserId: ld.userId, date: today, createdAt: new Date().toISOString() }); notify(ld.userId, teamId, 'NUDGE', it, `${u.displayName} 催办 ${it.code}（负责人请假/出差，转组长跟进）`, u.id); redirected = true; continue } }
      nudges.push({ id: nextId++, itemId: it.id, fromUserId: u.id, toUserId: uid, date: today, createdAt: new Date().toISOString() }); notify(uid, teamId, 'NUDGE', it, `${u.displayName} 催了你一下：${it.code} ${it.title}`, u.id); nudgedUserIds.push(uid)
    }
    if (nudgedUserIds.length) it.nudgeCount++
    return { status: 200, data: { itemId: it.id, nudgedUserIds, redirectedToLeader: redirected, nudgeCount: it.nudgeCount } }
  }
  if (seg[4] === 'nudge' && seg[5] === 'respond' && method === 'POST') {
    const { u } = requireMember(teamId)
    const mine = nudges.filter(n => n.itemId === it.id && n.toUserId === u.id).sort((a, b) => b.id - a.id)[0]
    if (!mine) throw err(404, 'NOT_FOUND', '没有待回应的催')
    const label = body.response === 'SOON' ? '马上更新' : body.response === 'TODAY' ? '今天搞定' : body.response === 'BLOCKED' ? '卡住了' : null
    if (!label) throw err(400, 'VALIDATION', 'response 须为 SOON / TODAY / BLOCKED')
    if (body.response === 'BLOCKED') { it.blocked = true; it.blockReason = '被催办后反馈：卡住了'; const ld = leaderOf(teamId); if (ld) notify(ld.userId, teamId, 'ITEM_UPDATE', it, `${u.displayName} 回应催办：${it.code} 卡住了`, u.id) }
    notify(mine.fromUserId, teamId, 'NUDGE', it, `${u.displayName} 回应了对 ${it.code} 的催办：${label}`, u.id)
    nudges = nudges.filter(n => n.id !== mine.id)
    return { status: 200, data: { itemId: it.id, response: body.response } }
  }
  if (seg[4] === 'progress' && method === 'POST') {
    const { u, m } = requireMember(teamId)
    if (!(it.ownerIds.includes(u.id) || m.role === 'LEADER')) throw err(403, 'FORBIDDEN', '只能登记自己负责事项的进展（PO 不能代登他人事项）')
    if (!body.text && body.kind !== 'KEEP_GOING') throw err(400, 'VALIDATION', '进展文字必填')
    if (body.kind === 'KEEP_GOING' && !body.text) body = { ...body, text: '照常推进' }
    if (it.type === 'TASK') throw err(422, 'ILLEGAL_TRANSITION', '任务不登记进展，进展登记在故事上')
    if (it.status === 'NOT_STARTED') { it.status = 'IN_PROGRESS'; act(it.id, u.id, 'START', 'NOT_STARTED', 'IN_PROGRESS', '登记进展时自动开始') }
    if (it.status !== 'IN_PROGRESS') throw err(422, 'ILLEGAL_TRANSITION', '当前状态不能登记进展')
    const p = { id: nextId++, itemId: it.id, authorId: u.id, text: body.text, percent: body.percent ?? null, blocked: !!body.blocked, blockReason: body.blockReason ?? null, nextStep: body.nextStep ?? null, kind: body.kind ?? 'NORMAL', createdAt: new Date().toISOString() }
    progressList.unshift(p); if (body.percent != null) it.progressPercent = body.percent
    it.blocked = !!body.blocked; it.blockReason = body.blocked ? (body.blockReason ?? '未填写原因') : null
    it.lastProgressAt = p.createdAt; it.nudgeCount = 0; it.version++; it.updatedAt = p.createdAt
    if (body.blocked) { const ld = leaderOf(teamId); if (ld && ld.userId !== u.id) notify(ld.userId, teamId, 'ITEM_UPDATE', it, `${it.code} 已标记阻塞：${it.blockReason}`, u.id) }
    act(it.id, u.id, body.blocked ? 'BLOCK' : 'EDIT', it.status, it.status, body.text); return { status: 201, data: p }
  }
  if (seg[4] === 'progress-list' && method === 'GET') return { status: 200, data: progressList.filter(p => p.itemId === itemId) }
  if (seg[4] === 'comments' && seg[5]) {
    const c = comments.find(x => x.id === Number(seg[5]) && x.itemId === itemId); if (!c) throw err(404, 'NOT_FOUND', '评论不存在')
    const { u, m } = requireMember(teamId)
    if (method === 'PATCH') { if (c.authorId !== u.id) throw err(403, 'FORBIDDEN', '只有作者可以编辑评论'); if (Date.now() - new Date(c.createdAt).getTime() > 15 * 60000) throw err(403, 'FORBIDDEN', '评论发出 15 分钟后不可编辑'); if (!body.content) throw err(400, 'VALIDATION', '评论内容必填'); c.content = body.content; return { status: 200, data: c } }
    if (method === 'DELETE') { if (!(c.authorId === u.id || m.role === 'LEADER')) throw err(403, 'FORBIDDEN', '只有作者或组长可以删除评论'); comments = comments.filter(x => x.id !== c.id); return { status: 204, data: null } }
    throw err(404, 'NOT_FOUND', '接口不存在')
  }
  if (seg[4] === 'comments') {
    if (method === 'GET') return { status: 200, data: comments.filter(c => c.itemId === itemId) }
    if (method === 'POST') { const u = me(); if (!body.content) throw err(400, 'VALIDATION', '评论内容必填'); const c = { id: nextId++, itemId, authorId: u.id, content: body.content, mentionIds: body.mentionIds ?? [], createdAt: new Date().toISOString() }; comments.push(c); it.version++; for (const mid of c.mentionIds) if (mid !== u.id) notify(mid, teamId, 'MENTION', it, `${u.displayName} 在 ${it.code} 的评论中 @ 了你`, u.id); return { status: 201, data: c } }
  }
  if (seg[4] === 'activities' && method === 'GET') return { status: 200, data: activities.filter(a => a.itemId === itemId) }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function canEditChecklist(it: Item, u: StoredUser, role: string) { return role !== 'MEMBER' || it.creatorId === u.id || it.ownerIds.includes(u.id) }
function checklistRoute(teamId: number, it: Item, method: string, seg: string[], body: any) {
  requireMember(teamId)
  if (method === 'GET' && seg.length === 5) return { status: 200, data: [...it.checklist].sort((a, b) => a.sortOrder - b.sortOrder) }
  const { u, m } = requireMember(teamId)
  if (!canEditChecklist(it, u, m.role)) throw err(403, 'FORBIDDEN', '只能编辑自己创建或负责事项的清单')
  if (method === 'POST' && seg.length === 5) {
    if (!body.text) throw err(400, 'VALIDATION', '清单文字必填')
    const e: ChecklistEntry = { id: nextChecklistId++, itemId: it.id, text: body.text, done: false, sortOrder: body.sortOrder ?? (it.checklist.reduce((n, x) => Math.max(n, x.sortOrder), 0) + 1) }
    it.checklist.push(e); it.version++; return { status: 201, data: e }
  }
  const e = it.checklist.find(x => x.id === Number(seg[5])); if (!e) throw err(404, 'NOT_FOUND', '清单条目不存在')
  if (method === 'PATCH') { if ('text' in body) e.text = body.text; if ('done' in body) e.done = !!body.done; if ('sortOrder' in body) e.sortOrder = body.sortOrder; it.version++; return { status: 200, data: e } }
  if (method === 'DELETE') { it.checklist = it.checklist.filter(x => x.id !== e.id); it.version++; return { status: 204, data: null } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function transition(teamId: number, it: Item, body: any) {
  const { u, m } = requireMember(teamId)
  if (body.version !== it.version) throw err(409, 'VERSION_CONFLICT', '该事项已被他人修改或已被认领，请刷新后重试')
  const isLeaderOrPO = m.role === 'LEADER' || m.role === 'PO'
  const illegal = () => err(422, 'ILLEGAL_TRANSITION', '当前状态不能执行该操作')
  const needReason = () => { if (!body.reason) throw err(400, 'VALIDATION', '该操作必须填写原因') }
  // 任务专用：仅 PO 的 APPROVE（待验收→已完成）与 CANCEL
  if (it.type === 'TASK') {
    if (body.action === 'APPROVE') {
      recompute(it); if (it.taskStatus !== 'PENDING_ACCEPTANCE') throw illegal()
      if (!(m.role === 'PO' || it.poId === u.id)) throw err(403, 'FORBIDDEN', '只有 PO 可以验收任务')
      it.taskStatus = 'DONE'; it.progressPercent = 100; it.version++; act(it.id, u.id, 'APPROVE', 'PENDING_ACCEPTANCE', 'DONE', null); return { status: 200, data: view(it) }
    }
    if (body.action === 'CANCEL') {
      if (it.taskStatus === 'DONE' || it.taskStatus === 'CANCELLED') throw illegal()
      needReason(); if (!(m.role === 'PO' || it.poId === u.id)) throw err(403, 'FORBIDDEN', '只有 PO 可以取消任务')
      it.taskStatus = 'CANCELLED'; it.version++; act(it.id, u.id, 'CANCEL', it.taskStatus, 'CANCELLED', body.reason); return { status: 200, data: view(it) }
    }
    if (body.action === 'REJECT') {
      // 待问 1 定稿：PO 打回任务必须填原因，且至少重开一个已完成故事或新建一个补充故事。
      recompute(it); if (it.taskStatus !== 'PENDING_ACCEPTANCE') throw illegal()
      if (!(m.role === 'PO' || it.poId === u.id)) throw err(403, 'FORBIDDEN', '只有 PO 可以打回任务')
      needReason()
      const reopenIds: number[] = body.reopenItemIds ?? []; const newStories: any[] = body.newStories ?? []
      if (!reopenIds.length && !newStories.filter(s => s?.title?.trim()).length) throw err(422, 'NO_ACTIONABLE_STORY', '打回任务必须至少重开一个已完成故事，或新建一个补充故事')
      for (const sid of reopenIds) { const s = items.find(k => k.id === sid && k.parentId === it.id && !k.deletedAt); if (s && s.status === 'DONE') { s.status = 'IN_PROGRESS'; if (s.progressPercent >= 100) s.progressPercent = 80; s.version++ } }
      for (const ns of newStories.filter(s => s?.title?.trim())) createItemCore(teamId, u, m.role, { type: 'STORY', title: ns.title.trim(), storyCategory: ns.storyCategory ?? 'DEV', parentId: it.id, destination: 'POOL', iterationId: iterations.find(x => x.teamId === teamId && x.status === 'ACTIVE')?.id ?? null })
      it.taskStatus = null; it.version++; act(it.id, u.id, 'REJECT', 'PENDING_ACCEPTANCE', 'IN_PROGRESS', body.reason); deriveTask(it)
      return { status: 200, data: view(it) }
    }
    throw illegal()
  }
  const from = it.status; const isOwner = it.ownerIds.includes(u.id)
  switch (body.action) {
    case 'CLAIM': if (from !== 'UNCLAIMED') throw illegal(); it.ownerIds = [u.id]; it.assignedDirectly = false; it.inviteeIds = []; it.status = 'NOT_STARTED'; break
    case 'START': if (from !== 'NOT_STARTED') throw illegal(); if (!isOwner) throw err(403, 'FORBIDDEN', '只有负责人可以开始'); it.status = 'IN_PROGRESS'; break
    case 'SUBMIT': if (from !== 'IN_PROGRESS') throw illegal(); if (!isOwner) throw err(403, 'FORBIDDEN', '只有负责人可以提交验收'); it.status = it.acceptorId === u.id ? 'DONE' : 'IN_REVIEW'; if (it.status === 'DONE') it.progressPercent = 100; break
    case 'APPROVE': if (from !== 'IN_REVIEW') throw illegal(); if (!(it.acceptorId === u.id || m.role === 'LEADER')) throw err(403, 'FORBIDDEN', '只有验收人或组长可以通过'); it.status = 'DONE'; it.progressPercent = 100; break
    case 'REJECT': if (from !== 'IN_REVIEW') throw illegal(); if (!(it.acceptorId === u.id || m.role === 'LEADER')) throw err(403, 'FORBIDDEN', '只有验收人或组长可以打回'); needReason(); it.status = 'IN_PROGRESS'; break
    case 'RELEASE': if (from !== 'NOT_STARTED' && from !== 'IN_PROGRESS') throw illegal(); if (!isOwner) throw err(403, 'FORBIDDEN', '只有负责人可以释放'); needReason(); it.status = 'UNCLAIMED'; it.ownerIds = []; it.assignedDirectly = false; it.blocked = false; it.blockReason = null; break
    case 'DECLINE':
      if (from !== 'NOT_STARTED') throw illegal()
      if (!it.assignedDirectly || !isOwner) throw err(403, 'FORBIDDEN', '只有被直接指派的负责人可以婉拒')
      needReason()
      if (progressList.some(p => p.itemId === it.id)) throw err(422, 'ILLEGAL_TRANSITION', '已登记过进展，不能婉拒，请使用释放')
      it.status = 'UNCLAIMED'; it.ownerIds = []; it.assignedDirectly = false
      notify(it.creatorId, teamId, 'ITEM_UPDATE', it, `${u.displayName} 婉拒了 ${it.code}：${body.reason}`, u.id); break
    case 'TRANSFER': {
      if (from === 'DONE' || from === 'CANCELLED') throw illegal()
      const nid = body.newOwnerId ?? body.newAssigneeId; if (!nid) throw err(400, 'VALIDATION', '转交必须选择新负责人')
      if (!(it.creatorId === u.id || isOwner || isLeaderOrPO)) throw err(403, 'FORBIDDEN', '无权转交该事项')
      // 待问 2 定稿：共同负责只替换一人（replaceUserId 指定，缺省发起人本人），其余共同负责人保留。
      if (it.ownerIds.length > 1) {
        let rep = body.replaceUserId ?? (it.ownerIds.includes(u.id) ? u.id : null)
        if (rep == null || !it.ownerIds.includes(rep)) throw err(400, 'REPLACE_REQUIRED', '共同负责事项转交需指定被替换的负责人')
        if (it.ownerIds.includes(nid)) throw err(409, 'VERSION_CONFLICT', '新负责人已是共同负责人')
        it.ownerIds = it.ownerIds.map(o => o === rep ? nid : o)
      } else it.ownerIds = [nid]
      it.assignedDirectly = true; if (from === 'UNCLAIMED') it.status = 'NOT_STARTED'
      notify(nid, teamId, 'ASSIGNMENT', it, `你被转交了 ${it.code} ${it.title}`, u.id); break }
    case 'CANCEL': if (from === 'DONE' || from === 'CANCELLED') throw illegal(); needReason(); if (!((it.creatorId === u.id && (from === 'NOT_STARTED' || from === 'UNCLAIMED')) || isLeaderOrPO)) throw err(403, 'FORBIDDEN', '无权取消该事项'); it.status = 'CANCELLED'; break
    case 'REOPEN': if (from !== 'DONE' && from !== 'CANCELLED') throw illegal(); if (!(it.creatorId === u.id || it.acceptorId === u.id || isLeaderOrPO)) throw err(403, 'FORBIDDEN', '无权重新打开'); it.status = it.ownerIds.length ? 'IN_PROGRESS' : 'UNCLAIMED'; if (it.status === 'IN_PROGRESS' && it.progressPercent >= 100) it.progressPercent = 80; break
    default: throw err(400, 'VALIDATION', '未知流转动作')
  }
  it.version++; it.updatedAt = new Date().toISOString(); act(it.id, u.id, body.action, from, it.status, body.reason ?? null)
  if (it.parentId) { const p = items.find(x => x.id === it.parentId); if (p) deriveTask(p) }
  return { status: 200, data: view(it) }
}

// ---------------- M4：标签 / AI / 导入 / 对接（契约 0.5.0） ----------------
let labelSeq = 100
const labelStore: { id: number; teamId: number; name: string; color: string }[] = (() => {
  const names = new Map<string, string>()
  for (const i of buildItems()) for (const n of i.labels) names.set(n, '#5856D6')
  return [...names.entries()].map(([name, color], idx) => ({ id: idx + 1, teamId: 1, name, color }))
})()
labelSeq = labelStore.length + 100
function labelView(l: { id: number; name: string; color: string }) { return { id: l.id, name: l.name, color: l.color, usageCount: items.filter(i => !i.deletedAt && i.labels.includes(l.name)).length } }
function labelsRoute(method: string, teamId: number, seg: string[], body: any, q: URLSearchParams) {
  const { m } = requireMember(teamId)
  const mine = () => labelStore.filter(l => l.teamId === teamId)
  if (seg.length === 3 && method === 'GET') return { status: 200, data: mine().map(labelView) }
  if (seg.length === 3 && method === 'POST') {
    if (!(m.role === 'LEADER' || m.role === 'PO')) throw err(403, 'FORBIDDEN', '仅组长/PO 可新建标签')
    if (!body?.name?.trim()) throw err(400, 'VALIDATION', '标签名必填')
    if (mine().some(l => l.name === body.name.trim())) throw err(409, 'LABEL_NAME_TAKEN', '标签名已存在')
    const l = { id: labelSeq++, teamId, name: body.name.trim(), color: body.color ?? '#5856D6' }; labelStore.push(l); return { status: 201, data: labelView(l) }
  }
  const l = mine().find(x => x.id === Number(seg[3])); if (!l) throw err(404, 'NOT_FOUND', '标签不存在')
  if (method === 'PATCH') {
    if (!(m.role === 'LEADER' || m.role === 'PO')) throw err(403, 'FORBIDDEN', '仅组长/PO 可修改标签')
    if (body?.name && body.name !== l.name) { if (mine().some(x => x.name === body.name)) throw err(409, 'LABEL_NAME_TAKEN', '标签名已存在'); for (const i of items) i.labels = i.labels.map(n => n === l.name ? body.name : n); l.name = body.name }
    if (body?.color) l.color = body.color
    return { status: 200, data: labelView(l) }
  }
  if (method === 'DELETE') {
    if (!(m.role === 'LEADER' || m.role === 'PO')) throw err(403, 'FORBIDDEN', '仅组长/PO 可删除标签')
    const usage = labelView(l).usageCount
    if (usage > 0 && q.get('force') !== 'true') throw err(409, 'LABEL_IN_USE', `标签正在被 ${usage} 个事项使用`, { usageCount: usage })
    for (const i of items) i.labels = i.labels.filter(n => n !== l.name)
    labelStore.splice(labelStore.indexOf(l), 1); return { status: 204, data: null }
  }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// AI 状态（模拟模式：平台开、服务可用；功能开关按组存）——契约 0.5.0：TeamAiSettings 扁平布尔，无小组总开关。
interface AiSettings { pasteImport: boolean; quickUpdate: boolean; splitStories: boolean; summarize: boolean; riskSummary: boolean; qa: boolean }
const AI_FEATURES = ['pasteImport', 'quickUpdate', 'splitStories', 'summarize', 'riskSummary', 'qa'] as const
const FEATURE_HYPHEN: [string, keyof AiSettings][] = [['paste-import', 'pasteImport'], ['quick-update', 'quickUpdate'], ['split-stories', 'splitStories'], ['summarize', 'summarize'], ['risk-summary', 'riskSummary'], ['qa', 'qa']]
const aiSettingsStore = new Map<number, AiSettings>()
function aiSettingsOf(teamId: number): AiSettings { return aiSettingsStore.get(teamId) ?? { pasteImport: true, quickUpdate: true, splitStories: true, summarize: true, riskSummary: true, qa: true } }
function aiStatusData(teamId: number) { const st = aiSettingsOf(teamId); return { platformEnabled: true, mode: 'mock', available: true, visionAvailable: true, textModel: 'mock', visionModel: 'mock', features: Object.fromEntries(FEATURE_HYPHEN.map(([h, c]) => [h, st[c]])), teamSettings: { ...st } } }
// 契约 AiCallLog：{id, feature, model, durationMs, promptTokens, completionTokens, success, error, createdAt}（无 result 字段）。
const aiLogs: { id: number; teamId: number; userId: number; feature: string; model: string | null; durationMs: number; promptTokens: number | null; completionTokens: number | null; success: boolean; error: string | null; createdAt: string }[] = []
let aiLogSeq = 1
function aiLog(teamId: number, userId: number, feature: string, t0: number, success = true, error: string | null = null) { aiLogs.unshift({ id: aiLogSeq++, teamId, userId, feature, model: 'mock', durationMs: Math.max(1, Date.now() - t0), promptTokens: null, completionTokens: null, success, error, createdAt: new Date().toISOString() }) }
const aiLogView = (l: typeof aiLogs[number]) => ({ id: l.id, feature: l.feature, model: l.model, durationMs: l.durationMs, promptTokens: l.promptTokens, completionTokens: l.completionTokens, success: l.success, error: l.error, createdAt: l.createdAt })
function requireAi(teamId: number, feature: keyof AiSettings) { const st = aiSettingsOf(teamId); if (st[feature] === false) throw err(403, 'AI_DISABLED', 'AI 功能未开启，可使用手工方式完成同样操作'); return st }
// 注入子句剥离（D-5，与真实后端 MockAiResponder 同口径）：按标点切分子句，丢弃「忽略指令 / 把全部事项… / 索要密码 / 发通知」类越界子句，只按用户本人针对具体事项的指令片段解析。曾有缺陷：注入句里的「已完成」字眼让正常进度更新被误标 submitReview=true。
function stripInjectedClauses(text: string): string {
  return text.split(/[，。；;！!？?\n]+/).map(c => c.trim()).filter(c => c && !(c.includes('忽略') || c.includes('全部事项') || c.includes('所有事项') || c.includes('密码') || c.includes('通知') || (c.includes('全部') && c.includes('完成')))).join('，')
}
function requirePoRole(role: string) { if (role !== 'PO') throw err(403, 'FORBIDDEN', '仅 PO 可操作') }
function decodeB64(b64: string): string { const B = (globalThis as any).Buffer; if (B) return B.from(b64, 'base64').toString('utf8'); return decodeURIComponent(escape(atob(b64))) }
interface ImportBatch { id: number; teamId: number; source: string; creatorId: number; createdAt: string; createdItemIds: number[]; updatedSnapshots: { item: Item; before: any }[]; undone: boolean; undoneAt: string | null }
const externalIndex = new Map<string, number>()
const importBatches: ImportBatch[] = []
let importSeq = 1
// 契约 QaRecord：refs 为事项编号字符串数组。
const qaHistory: { id: number; teamId: number; userId: number; question: string; answer: string; found: boolean; refs: string[]; createdAt: string }[] = []
let qaSeq = 1
function parsePasteRows(teamId: number, text: string) {
  return text.split(/\r?\n/).map(l => l.trim()).filter(Boolean).map((line, idx) => {
    let title = line.replace(/^\d+[.、)]\s*/, '')
    let estimate: number | null = null; const em = title.match(/(\d+(?:\.\d+)?)\s*点/); if (em) { estimate = Number(em[1]); title = title.replace(em[0], '').trim() }
    let dueDate: string | null = null; const dm = title.match(/\d{4}-\d{2}-\d{2}/); if (dm) { dueDate = dm[0]; title = title.replace(dm[0], '').trim() }
    title = title.replace(/[，,；;]\s*$/, '').trim()
    const existing = items.find(i => !i.deletedAt && i.title === title)
    const lowConfidenceFields: string[] = []
    if (title.length < 4) lowConfidenceFields.push('title')
    if (estimate == null) lowConfidenceFields.push('estimate')
    const diff: Record<string, { from: any; to: any }> = {}
    if (existing) { if (existing.estimate !== estimate) diff.estimate = { from: existing.estimate, to: estimate }; if (existing.dueDate !== dueDate) diff.dueDate = { from: existing.dueDate, to: dueDate } }
    return { externalId: null, title, type: 'TASK', description: null, priority: 'P2', ownerName: null, ownerId: null, estimate, dueDate, parentExternalId: null, sourceSnippet: line, confidence: lowConfidenceFields.length ? 0.5 : 0.9, matchStatus: existing ? 'UPDATE' : 'NEW', existingItemId: existing?.id ?? null, diff }
  })
}
function aiRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  const sub = seg[3]
  if (sub === 'status' && method === 'GET') return { status: 200, data: aiStatusData(teamId) }
  if (sub === 'settings' && method === 'PUT') {
    if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可修改 AI 设置')
    const next: AiSettings = { ...aiSettingsOf(teamId) }; for (const k of AI_FEATURES) if (typeof body?.[k] === 'boolean') next[k] = body[k]
    aiSettingsStore.set(teamId, next); return { status: 200, data: { ...next } }
  }
  if (sub === 'logs' && method === 'GET') { if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可查看 AI 调用日志'); return { status: 200, data: aiLogs.filter(l => l.teamId === teamId).slice(0, 50).map(aiLogView) } }
  if (sub === 'paste-import' && seg[4] === 'file' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'pasteImport'); requirePoRole(m.role)
    const text = body?.__fileText ?? ''
    if (!text.trim()) throw err(400, 'VALIDATION', '文件内容为空')
    if (/\.xlsx$/i.test(body?.__fileName ?? '')) throw err(400, 'VALIDATION', 'mock 仅解析 CSV，xlsx 请另存为 CSV')
    const rows = parsePasteRows(teamId, text)
    aiLog(teamId, u.id, 'paste-import', t0); return { status: 200, data: { rows, truncated: false } } }
  if (sub === 'paste-import' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'pasteImport'); requirePoRole(m.role)
    let text = body?.text ?? ''
    if (body?.fileContentBase64) { try { text = decodeB64(body.fileContentBase64) } catch { throw err(400, 'VALIDATION', '文件内容 base64 无效') } }
    const hasImage = !!(body?.imageBase64 && String(body.imageBase64).length > 0)
    if (!text.trim() && !hasImage) throw err(400, 'VALIDATION', '请粘贴文本、选择图片或文件')
    const rows = parsePasteRows(teamId, text || '（图片识别示例）识别出的故事标题')
    aiLog(teamId, u.id, 'paste-import', t0); return { status: 200, data: { rows, truncated: false } } }
  if (sub === 'quick-update' && seg[4] === 'apply' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'quickUpdate')
    const results: any[] = []
    for (const a of body?.actions ?? []) {
      if (a.kind === 'CREATE_ITEM' && a.title) { const it = createItemCore(teamId, u, m.role, { type: a.type ?? 'ADHOC', title: a.title, destination: a.ownerId ? 'ASSIGN' : 'POOL', ownerIds: a.ownerId ? [a.ownerId] : undefined, dueDate: a.dueDate ?? null }); results.push({ kind: a.kind, itemId: it.id, ok: true }); continue }
      if (a.kind !== 'UPDATE_ITEM') continue
      const it = items.find(i => i.id === a.itemId && !i.deletedAt); if (!it) { results.push({ kind: a.kind, itemId: a.itemId ?? null, ok: false }); continue }
      if (a.percent != null) it.progressPercent = Math.min(100, Math.max(0, a.percent))
      if (a.blocked) { it.blocked = true; it.blockReason = a.blockReason ?? '一句话更新标记阻塞' }
      if ((a.submitReview || it.progressPercent >= 100) && it.status === 'IN_PROGRESS') it.status = 'IN_REVIEW'
      it.version++; it.lastProgressAt = new Date().toISOString(); results.push({ kind: a.kind, itemId: it.id, ok: true })
      if (it.parentId) { const p = items.find(x => x.id === it.parentId); if (p) deriveTask(p) }
    }
    aiLog(teamId, u.id, 'quick-update/apply', t0); return { status: 200, data: { results } } }
  if (sub === 'quick-update' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'quickUpdate')
    const rawText: string = body?.text ?? ''; if (!rawText.trim()) throw err(400, 'VALIDATION', '请输入一句话进展')
    const text = stripInjectedClauses(rawText)
    if (!text) { aiLog(teamId, u.id, 'quick-update', t0); return { status: 200, data: { actions: [], unmatched: [rawText] } } }
    const mine = items.filter(i => !i.deletedAt && i.ownerIds.includes(u.id) && i.type !== 'TASK' && !['DONE', 'CANCELLED'].includes(i.status))
    const byCode = mine.filter(i => text.includes(i.code))
    const byKw = mine.filter(i => !byCode.includes(i) && [...text].length > 1 && i.title.split('').some((_, ix) => ix < i.title.length - 3 && text.includes(i.title.slice(ix, ix + 4))))
    const matched = byCode.length ? byCode : byKw
    const actions: any[] = []; const unmatched: string[] = []
    const pctM = text.match(/(\d{1,3})\s*%/)
    const doneKw = /完成|搞定|做完了/.test(text)
    const blockedKw = /卡住|阻塞|等/.test(text) && /设计稿|接口|确认|联调/.test(text)
    const pct = pctM ? Math.min(100, Number(pctM[1])) : doneKw ? 100 : null
    const base = { itemId: null as number | null, itemCode: null as string | null, text, percent: pct, submitReview: doneKw, blocked: false, blockReason: null as string | null, needsBlockConfirm: blockedKw, candidateItemIds: [] as number[], type: null as string | null, title: null as string | null, ownerName: null as string | null, ownerId: null as number | null, dueDate: null as string | null }
    if (matched.length > 1) actions.push({ ...base, kind: 'UPDATE_ITEM', candidateItemIds: matched.slice(0, 3).map(i => i.id) })
    else if (matched.length === 1) { const it = matched[0]; actions.push({ ...base, kind: 'UPDATE_ITEM', itemId: it.id, itemCode: it.code }) }
    else if (doneKw || pctM) unmatched.push(text)
    else actions.push({ ...base, kind: 'CREATE_ITEM', type: 'ADHOC', title: text.slice(0, 60), percent: null, submitReview: false, needsBlockConfirm: false })
    aiLog(teamId, u.id, 'quick-update', t0); return { status: 200, data: { actions, unmatched } } }
  if (sub === 'split-stories' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'splitStories')
    const task = items.find(i => i.id === Number(body?.itemId) && !i.deletedAt); if (!task || task.type !== 'TASK') throw err(404, 'NOT_FOUND', '任务不存在')
    if (m.role !== 'PO') throw err(403, 'FORBIDDEN', '仅 PO 可使用 AI 拆故事')
    const base = task.title
    const stories = [
      { title: `${base}：方案与范围确认`, storyCategory: 'ANALYSIS', estimate: 1 },
      { title: `${base}：核心功能实现`, storyCategory: 'DEV', estimate: 3 },
      { title: `${base}：异常与边界处理`, storyCategory: 'DEV', estimate: 2 },
      { title: `${base}：回归测试与验收`, storyCategory: 'TEST', estimate: 2 },
    ]
    aiLog(teamId, u.id, 'split-stories', t0)
    return { status: 200, data: { taskId: task.id, stories, clarifyingQuestions: [`「${base}」的验收口径是否已在评审时对齐？`, '是否有依赖其他小组的接口或数据？'], acceptancePoints: ['主流场景走通且无阻塞问题', '异常场景有明确提示'] } } }
  if (sub === 'summarize' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'summarize')
    const kind = normKind(body?.kind ?? 'WEEKLY'); if (!canGenReport(kind, u, m)) throw err(403, 'FORBIDDEN', '无权生成该类报告')
    const ep = effectivePeriod(teamId, kind, body?.periodStart ?? null, body?.periodEnd ?? null)
    const existing = reports.find(r => r.teamId === teamId && !r.deletedAt && r.kind === kind && r.periodStart === ep.ps && r.periodEnd === ep.pe && (kind !== 'PERSONAL_WEEKLY' || r.ownerId === u.id))
    if (existing) { aiLog(teamId, u.id, 'summarize', t0); return { status: 200, data: { ...reportView(existing), existed: true } } }
    const r = createReport(teamId, u.id, kind, body?.periodStart ?? null, body?.periodEnd ?? null)
    r.aiGenerated = true; r.content = r.content.replace('本报告由系统按结构化数据自动生成（非 AI 生成）', '本报告由 AI 基于结构化数据汇总生成，数字以程序统计区为准')
    aiLog(teamId, u.id, 'summarize', t0); return { status: 201, data: { ...reportView(r), existed: false } } }
  if (sub === 'risk-summary' && method === 'GET') { const t0 = Date.now(); requireAi(teamId, 'riskSummary')
    if (!(m.role === 'LEADER' || m.role === 'PO')) throw err(403, 'FORBIDDEN', '风险摘要仅组长、PO 可见')
    const signals = riskSignals(teamId)
    const top = signals.slice(0, 3).map((sg: any) => ({ itemId: sg.itemId, itemCode: sg.itemCode, reason: sg.message, action: sg.rule === 'BLOCKED_LONG' ? '请负责人今天内同步阻塞进展，组长协助推进解除' : sg.rule === 'OVERDUE' ? '优先处理或申请调整截止日期' : '在站会上确认下一步与负责人' }))
    aiLog(teamId, u.id, 'risk-summary', t0); return { status: 200, data: { aiGenerated: true, top, signals } } }
  if (sub === 'qa' && seg[4] === 'history') {
    if (method === 'GET') return { status: 200, data: qaHistory.filter(h => h.teamId === teamId && h.userId === u.id).map(h => ({ id: h.id, question: h.question, answer: h.answer, found: h.found, refs: h.refs, createdAt: h.createdAt })) }
    if (method === 'DELETE') { const i = qaHistory.findIndex(h => h.id === Number(seg[5]) && h.userId === u.id); if (i < 0) throw err(404, 'NOT_FOUND', '历史不存在'); qaHistory.splice(i, 1); return { status: 204, data: null } }
  }
  if (sub === 'qa' && method === 'POST') { const t0 = Date.now(); requireAi(teamId, 'qa')
    const question: string = body?.question ?? ''; if (!question.trim()) throw err(400, 'VALIDATION', '问题必填')
    const kws = question.replace(/[？?，。、\s]/g, ' ').split(' ').filter(w => w.length >= 2)
    const grams: string[] = []; for (let n = 4; n >= 3; n--) for (let i2 = 0; i2 + n <= question.length; i2++) grams.push(question.slice(i2, i2 + n))
    const hits = items.filter(i => !i.deletedAt && (question.includes(i.code) || kws.some(w => i.title.includes(w) || (i.description ?? '').includes(w)) || grams.some(g => i.title.includes(g))))
    const found = hits.length > 0
    const answer = found ? `共找到 ${hits.length} 条相关事项：` + hits.slice(0, 5).map(i => `${i.code}《${i.title}》（${i.status}，进度 ${i.progressPercent}%）`).join('；') : '查不到与该问题相关的事项记录。'
    const rec = { id: qaSeq++, teamId, userId: u.id, question, answer, found, refs: hits.slice(0, 5).map(i => i.code), createdAt: new Date().toISOString() }
    qaHistory.unshift(rec); aiLog(teamId, u.id, 'qa', t0)
    // 契约 POST 响应为 {recordId, found, answer, refs}，历史列表才回 QaRecord 全形状。
    return { status: 200, data: { recordId: rec.id, found: rec.found, answer: rec.answer, refs: rec.refs } } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
function importsRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  // 契约 0.5.0：批次视图为 ImportBatch 形状（undoneAt/link/total 等），撤销口径同后端（超时 409 UNDO_EXPIRED）。
  const batchView = (b: ImportBatch) => ({ id: b.id, teamId: b.teamId, creatorId: b.creatorId, source: b.source, total: b.createdItemIds.length + b.updatedSnapshots.length, createdCount: b.createdItemIds.length, updatedCount: b.updatedSnapshots.length, createdItemIds: b.createdItemIds, updatedItemIds: b.updatedSnapshots.map(x => x.item.id), skipped: 0, undoneAt: b.undoneAt, createdAt: b.createdAt, link: `/teams/${b.teamId}/imports/${b.id}` })
  if (seg[3] === 'file' && seg[4] === 'parse' && method === 'POST') {
    requirePoRole(m.role)
    const content: string = body?.__fileText ?? ''; if (!content.trim()) throw err(400, 'VALIDATION', '文件内容为空')
    if (/\.xlsx$/i.test(body?.__fileName ?? '')) throw err(400, 'VALIDATION', 'mock 仅解析 CSV，xlsx 请另存为 CSV')
    const lines = content.replace(/^\uFEFF/, '').split(/\r?\n/).filter(l => l.trim())
    const headers = lines[0].split(/[,，\t]/).map(h => h.trim())
    const rows = lines.slice(1).map(l => l.split(/[,，\t]/).map(c => c.trim()))
    return { status: 200, data: { headers, rows } }
  }
  if (method === 'GET' && seg.length === 3) return { status: 200, data: importBatches.filter(b => b.teamId === teamId).map(batchView) }
  if (method === 'POST' && seg.length === 3) return idemWrap(`imports|${teamId}`, () => {
    requirePoRole(m.role)
    const source: string = body?.source ?? 'AI_PASTE'
    if (!['AI_PASTE', 'FILE_MAPPING', 'ADAPTER'].includes(source)) throw err(400, 'VALIDATION', 'source 须为 AI_PASTE / FILE_MAPPING / ADAPTER')
    const systemName: string = body?.systemName ?? (source === 'FILE_MAPPING' ? 'FILE' : 'AI_PASTE')
    let rows: any[] = body?.rows ?? []
    if (!rows.length && Array.isArray(body?.rawRows) && body?.mapping) { // 契约 rawRows+mapping：按字段→列序号取数
      const mp = body.mapping; const cell = (r: string[], k: string) => { const ix = mp[k]; return typeof ix === 'number' && r[ix] ? r[ix] : null }
      rows = body.rawRows.map((r: string[]) => ({ externalId: cell(r, 'externalId'), title: cell(r, 'title'), description: cell(r, 'description'), priority: cell(r, 'priority'), estimate: cell(r, 'estimate') != null && !Number.isNaN(Number(cell(r, 'estimate'))) ? Number(cell(r, 'estimate')) : null, dueDate: cell(r, 'dueDate'), externalUrl: cell(r, 'externalUrl') })).filter((r: any) => r.title)
    }
    if (!rows.length) throw err(400, 'VALIDATION', '没有可导入的行')
    const batch: ImportBatch = { id: importSeq++, teamId, source, creatorId: u.id, createdAt: new Date().toISOString(), createdItemIds: [], updatedSnapshots: [], undone: false, undoneAt: null }
    for (const row of rows) {
      const extKey = row.externalId ? `${teamId}|${systemName}|${row.externalId}` : null
      const existingId = extKey ? externalIndex.get(extKey) : undefined
      const existing = existingId ? items.find(i => i.id === existingId && !i.deletedAt) : undefined
      if (existing) { // 更新只动标题/描述/截止（契约口径），存快照供整批撤销
        batch.updatedSnapshots.push({ item: existing, before: { title: existing.title, description: existing.description, dueDate: existing.dueDate } })
        if (row.title) existing.title = row.title; if (row.description !== undefined) existing.description = row.description ?? ''; if (row.dueDate !== undefined) existing.dueDate = row.dueDate; existing.version++; continue }
      // 契约 0.5.1：estimate 仅新建落库；上方更新分支只覆盖标题/描述/截止，不覆盖 estimate。
      const it = createItemCore(teamId, u, m.role, { type: 'TASK', title: row.title, description: row.description ?? undefined, priority: row.priority ?? undefined, estimate: row.estimate ?? undefined, destination: 'POOL', dueDate: row.dueDate ?? null })
      if (extKey) externalIndex.set(extKey, it.id)
      batch.createdItemIds.push(it.id)
    }
    importBatches.unshift(batch)
    return { status: 201, data: batchView(batch) }
  })
  const batch = importBatches.find(b => b.id === Number(seg[3]) && b.teamId === teamId); if (!batch) throw err(404, 'NOT_FOUND', '导入批次不存在')
  if (method === 'POST' && seg[4] === 'undo') {
    if (batch.undone) throw err(409, 'VERSION_CONFLICT', '该批次已撤销')
    if (Date.now() - new Date(batch.createdAt).getTime() >= 24 * 3600 * 1000) throw err(409, 'UNDO_EXPIRED', '导入超过 24 小时，不能整批撤销')
    for (const id of batch.createdItemIds) { const it = items.find(i => i.id === id); if (it) it.deletedAt = new Date().toISOString() }
    for (const snap of batch.updatedSnapshots) { snap.item.title = snap.before.title; snap.item.description = snap.before.description; snap.item.dueDate = snap.before.dueDate; snap.item.version++ }
    batch.undone = true; batch.undoneAt = new Date().toISOString(); return { status: 200, data: batchView(batch) }
  }
  throw err(404, 'NOT_FOUND', '接口不存在')
}
// 契约 IntegrationAdapter：{id, teamId, name, type, systemName, config, enabled, consecutiveFailures, createdBy, createdAt, updatedAt}；密钥在 config.authToken/authPassword，回显脱敏 ***。
interface Adapter { id: number; teamId: number; name: string; type: string; systemName: string; config: any; enabled: boolean; consecutiveFailures: number; createdBy: number; createdAt: string; updatedAt: string }
const adapters: Adapter[] = []
let adapterSeq = 1
const syncLogs: { id: number; teamId: number; adapterId: number; createdCount: number; updatedCount: number; failedCount: number; message: string | null; success: boolean; createdAt: string }[] = []
let syncLogSeq = 1
function maskedConfig(cfg: any) { const c = JSON.parse(JSON.stringify(cfg ?? {})); for (const k of ['authToken', 'authPassword']) if (c[k]) c[k] = '***'; return c }
function mergeSecrets(oldCfg: any, newCfg: any) { const c = { ...(newCfg ?? {}) }; for (const k of ['authToken', 'authPassword']) if (c[k] === '***') { if (oldCfg?.[k]) c[k] = oldCfg[k]; else delete c[k] } return c }
const adapterView = (a: Adapter) => ({ id: a.id, teamId: a.teamId, name: a.name, type: a.type, systemName: a.systemName, config: maskedConfig(a.config), enabled: a.enabled, consecutiveFailures: a.consecutiveFailures, createdBy: a.createdBy, createdAt: a.createdAt, updatedAt: a.updatedAt })
function integrationsRoute(method: string, teamId: number, seg: string[], body: any) {
  const { u, m } = requireMember(teamId)
  if (seg[3] === 'sync-logs' && method === 'GET') return { status: 200, data: syncLogs.filter(l => l.teamId === teamId) }
  if (seg[3] !== 'adapters') throw err(404, 'NOT_FOUND', '接口不存在')
  if (method === 'GET' && seg.length === 4) return { status: 200, data: adapters.filter(a => a.teamId === teamId).map(adapterView) }
  if (method === 'POST' && seg.length === 4) {
    if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可管理对接')
    if (!body?.name || !['MOCK', 'HTTP'].includes(body?.type ?? '') || !body?.systemName) throw err(400, 'VALIDATION', '名称与系统名必填，类型须为 MOCK / HTTP')
    const now = new Date().toISOString()
    const a: Adapter = { id: adapterSeq++, teamId, name: body.name, type: body.type, systemName: body.systemName, config: body.config ?? {}, enabled: body.enabled !== false, consecutiveFailures: 0, createdBy: u.id, createdAt: now, updatedAt: now }
    adapters.push(a); return { status: 201, data: adapterView(a) }
  }
  const a = adapters.find(x => x.id === Number(seg[4]) && x.teamId === teamId); if (!a) throw err(404, 'NOT_FOUND', '适配器不存在')
  if (method === 'PATCH') { if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可管理对接'); for (const k of ['name', 'type', 'systemName', 'enabled'] as const) if (body?.[k] !== undefined) (a as any)[k] = body[k]; if (body?.config !== undefined) a.config = mergeSecrets(a.config, body.config); a.updatedAt = new Date().toISOString(); return { status: 200, data: adapterView(a) } }
  if (method === 'DELETE') { if (m.role !== 'LEADER') throw err(403, 'FORBIDDEN', '仅组长可管理对接'); adapters.splice(adapters.indexOf(a), 1); return { status: 204, data: null } }
  if (method === 'POST' && seg[5] === 'test') { if (a.type === 'HTTP' && !a.config?.url) return { status: 200, data: { ok: false, count: 0, message: '连接失败：HTTP 适配器未配置 url' } }; return { status: 200, data: { ok: true, count: a.type === 'MOCK' ? 3 : 0, message: a.type === 'MOCK' ? '模拟适配器连接正常' : `已模拟连通 ${a.config?.url || '(未填地址)'}` } } }
  if (method === 'POST' && seg[5] === 'sync') {
    if (!a.enabled) throw err(400, 'VALIDATION', '适配器已停用')
    // 失败路径（对齐真实后端）：HTTP 适配器未配置 url 时拉取必失败（真实后端 fetch 抛「HTTP 适配器未配置 url」）；
    // mock 不做真实外呼，已配置 url 的 HTTP 同步仍按既有口径模拟成功。失败累计 consecutiveFailures，成功清零。
    if (a.type === 'HTTP' && !a.config?.url) {
      a.consecutiveFailures++; a.updatedAt = new Date().toISOString()
      const log = { id: syncLogSeq++, teamId, adapterId: a.id, createdCount: 0, updatedCount: 0, failedCount: 0, message: '同步失败：HTTP 适配器未配置 url', success: false, createdAt: new Date().toISOString() }
      syncLogs.unshift(log)
      if (a.consecutiveFailures >= 3) for (const mm of members.filter(x => x.teamId === teamId && x.role === 'LEADER')) notifySyncFailed(mm.userId, teamId, `适配器「${a.name}」已连续同步失败 ${a.consecutiveFailures} 次，请检查配置`, u.id)
      return { status: 200, data: log }
    }
    a.consecutiveFailures = 0
    const log = { id: syncLogSeq++, teamId, adapterId: a.id, createdCount: a.type === 'MOCK' ? 1 : 0, updatedCount: 1, failedCount: 0, message: null as string | null, success: true, createdAt: new Date().toISOString() }; syncLogs.unshift(log); return { status: 200, data: log } }
  throw err(404, 'NOT_FOUND', '接口不存在')
}

// 供冒烟测试读取内部通知（无契约接口，仅测试钩子，不经 handleRequest 暴露）
export const __testHooks = { notifications: () => notifications, nudges: () => nudges }
