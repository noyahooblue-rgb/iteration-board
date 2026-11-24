// 统一 http 客户端：baseURL=/api/v1。VITE_API_MODE=real 走 fetch；=mock 走 mock-backend 内存实现。
export class ApiError extends Error {
  constructor(public status:number, public code:string, message:string, public detail:any=null){ super(message) }
}
import { handleRequest as mockHandle, subscribeEvents as mockSubscribe, DEMO_ACCOUNTS, DEMO_SUBTITLE, DEMO_FILL_HINT, DEMO_REG_CLOSED_TEXT, DEMO_PASSWORD_FILL } from '@mock-adapter'
export { DEMO_ACCOUNTS, DEMO_SUBTITLE, DEMO_FILL_HINT, DEMO_REG_CLOSED_TEXT, DEMO_PASSWORD_FILL }
// 通知实时推送：real 走 EventSource(SSE)；mock 走适配器内的页面级事件总线。返回退订函数。
export function subscribeEvents(cb: (n: any) => void): () => void {
  if (MODE === 'mock') return mockSubscribe(cb)
  // 订阅带上当前团队（P2c 裁定）：服务端按该视图口径在增量里给 unreadCount；
  // 切换团队时外壳整页重载，会以新团队重新订阅。
  const teamId = Number(localStorage.getItem('current-team') ?? 0)
  const es = new EventSource('/api/v1/notifications/stream' + (teamId ? `?teamId=${teamId}` : ''))
  const handler = (e: MessageEvent) => { try { cb(JSON.parse(e.data)) } catch {} }
  es.addEventListener('notification', handler as EventListener)
  // 断线重连/休眠恢复：EventSource 自带自动重连；服务端每次订阅都先发 connected 事件，
  // 订阅方据此全量对账一次，补上断线期间错过的通知（标记 __connected，不是新通知）。
  const onConnected = () => cb({ __connected: true })
  es.addEventListener('connected', onConnected as EventListener)
  return () => { es.removeEventListener('notification', handler as EventListener); es.removeEventListener('connected', onConnected as EventListener); es.close() }
}
const MODE = import.meta.env.VITE_API_MODE === 'mock' ? 'mock' : 'real'
export const apiMode = MODE
let redirectingToLogin = false
let redirectLockTimer: ReturnType<typeof setTimeout> | null = null
/** 释放 401 跳转锁：登录页挂载与登录成功时调用（C2 复核处置 2.1）。整页 assign 后
 * 模块状态本会随新页面重置，但 SPA 内到达登录页（路由守卫客户端跳转）时不重载，
 * 锁若只置位不释放，后续会话再过期时的 401 将永远跳不动——故显式提供释放点，
 * 并在置锁时挂 10 秒兜底定时释放，双保险防永久死锁。 */
export function resetLoginRedirectLock() {
  redirectingToLogin = false
  if (redirectLockTimer) { clearTimeout(redirectLockTimer); redirectLockTimer = null }
}
/**
 * 站内回跳地址严格校验（C2 复核处置 2.2）：回跳参数可由外部链接构造，单纯
 * startsWith('/') 会被 //evil.com、/\evil.com 等形式绕过校验本身。故先拒绝
 * // 与 /\ 开头，再用 URL 解析并严格比对 origin；合法时返回站内
 * pathname+search+hash，否则返回 null。
 */
export function safeInternalPath(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  // C3 复核第二遍核查：先 trim 再判开头，与 URL 解析对空白的处理口径一致，
  // 避免 " //evil.com" 这类带前导空白的输入在前缀判断与解析之间出现不一致。
  const s = raw.trim()
  if (!s) return null
  if (!s.startsWith('/') || s.startsWith('//') || s.startsWith('/\\')) return null
  try {
    const u = new URL(s, window.location.origin)
    if (u.origin !== window.location.origin) return null
    return u.pathname + u.search + u.hash
  } catch { return null }
}
export function redirectToLogin() {
  if (redirectingToLogin) return
  if (typeof window === 'undefined') return
  if (window.location.pathname === '/login') return
  redirectingToLogin = true
  redirectLockTimer = setTimeout(() => { redirectingToLogin = false; redirectLockTimer = null }, 10000)
  const target = safeInternalPath(window.location.pathname + window.location.search)
  window.location.assign('/login' + (target ? '?redirect=' + encodeURIComponent(target) : ''))
}
function wrapFetchError(e: unknown): never {
  if (e instanceof DOMException && e.name === 'AbortError') throw new ApiError(0, 'CANCELED', '请求已取消')
  if ((e as any)?.name === 'AbortError') throw new ApiError(0, 'CANCELED', '请求已取消')
  throw new ApiError(0, 'NETWORK', '网络连接失败，请检查网络后重试')
}
export async function request<T=any>(method:string, path:string, body?:any, extraHeaders?:Record<string,string>): Promise<T> {
  if (MODE === 'mock') {
    const res = await mockHandle(method, '/api/v1' + path, body, extraHeaders)
    if (res.status >= 400) throw new ApiError(res.status, res.data?.code ?? 'ERROR', res.data?.message ?? '请求失败', res.data?.detail)
    return res.data as T
  }
  const headers: Record<string,string> = { ...(extraHeaders ?? {}) }
  if (body) headers['Content-Type'] = 'application/json'
  // CSRF（契约 0.2.0）：变更类请求带 X-XSRF-TOKEN 头，值取 XSRF-TOKEN Cookie。
  if (!['GET','HEAD','OPTIONS'].includes(method.toUpperCase())) { const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/); if (m) headers['X-XSRF-TOKEN'] = decodeURIComponent(m[1]) }
  let res: Response
  try { res = await fetch('/api/v1' + path, { method, credentials: 'include', headers: Object.keys(headers).length ? headers : undefined, body: body ? JSON.stringify(body) : undefined }) } catch (e) { wrapFetchError(e) }
  if (res!.status === 204) return null as T
  const data = await res!.json().catch(() => null)
  if (!res!.ok) throw new ApiError(res!.status, data?.code ?? 'ERROR', data?.message ?? '请求失败', data?.detail)
  return data as T
}
// multipart 上传（契约 imports/file/parse、ai/paste-import/file 均为 multipart/form-data，字段名 file）：
// real 走 FormData fetch（不设 JSON Content-Type，浏览器自动带 boundary）；mock 直传 FormData 给 handleRequest。
export async function requestForm<T=any>(method:string, path:string, form:FormData): Promise<T> {
  if (MODE === 'mock') {
    const res = await mockHandle(method, '/api/v1' + path, form)
    if (res.status >= 400) throw new ApiError(res.status, res.data?.code ?? 'ERROR', res.data?.message ?? '请求失败', res.data?.detail)
    return res.data as T
  }
  const headers: Record<string,string> = {}
  if (!['GET','HEAD','OPTIONS'].includes(method.toUpperCase())) { const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/); if (m) headers['X-XSRF-TOKEN'] = decodeURIComponent(m[1]) }
  let res: Response
  try { res = await fetch('/api/v1' + path, { method, credentials: 'include', headers: Object.keys(headers).length ? headers : undefined, body: form }) } catch (e) { wrapFetchError(e) }
  if (res!.status === 204) return null as T
  const data = await res!.json().catch(() => null)
  if (!res!.ok) throw new ApiError(res!.status, data?.code ?? 'ERROR', data?.message ?? '请求失败', data?.detail)
  return data as T
}
export const api = {
  get: <T=any>(p:string)=>request<T>('GET',p), post: <T=any>(p:string,b?:any)=>request<T>('POST',p,b), put: <T=any>(p:string,b?:any)=>request<T>('PUT',p,b), patch: <T=any>(p:string,b?:any)=>request<T>('PATCH',p,b), del: <T=any>(p:string)=>request<T>('DELETE',p),
  // 幂等：创建类请求带 Idempotency-Key，同一用户意图（同键）重复提交只生效一次。
  postKey: <T=any>(p:string,b:any,key:string)=>request<T>('POST',p,b,{ 'Idempotency-Key': key }),
  postForm: <T=any>(p:string,form:FormData)=>requestForm<T>('POST',p,form),
}
// 每个用户意图生成一个 UUID：弹窗打开时生成、重试复用、关闭后作废（调用方持有）。
export function newIdempotencyKey(): string {
  try { return crypto.randomUUID() } catch { return 'idem-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12) }
}
