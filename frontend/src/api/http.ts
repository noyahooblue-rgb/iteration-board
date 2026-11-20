// 统一 http 客户端：baseURL=/api/v1。VITE_API_MODE=real 走 fetch；=mock 走 mock-backend 内存实现。
export class ApiError extends Error {
  constructor(public status:number, public code:string, message:string, public detail:any=null){ super(message) }
}
import { handleRequest as mockHandle, subscribeEvents as mockSubscribe, DEMO_ACCOUNTS, DEMO_SUBTITLE, DEMO_FILL_HINT, DEMO_REG_CLOSED_TEXT } from '@mock-adapter'
export { DEMO_ACCOUNTS, DEMO_SUBTITLE, DEMO_FILL_HINT, DEMO_REG_CLOSED_TEXT }
// 通知实时推送（设计 §8.4）：real 走 EventSource(SSE)；mock 走适配器内的页面级事件总线。返回退订函数。
export function subscribeEvents(cb: (n: any) => void): () => void {
  if (MODE === 'mock') return mockSubscribe(cb)
  const es = new EventSource('/api/v1/notifications/stream')
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
  const res = await fetch('/api/v1' + path, { method, credentials: 'include', headers: Object.keys(headers).length ? headers : undefined, body: body ? JSON.stringify(body) : undefined })
  if (res.status === 204) return null as T
  const data = await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? '请求失败', data?.detail)
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
  const res = await fetch('/api/v1' + path, { method, credentials: 'include', headers: Object.keys(headers).length ? headers : undefined, body: form })
  if (res.status === 204) return null as T
  const data = await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? '请求失败', data?.detail)
  return data as T
}
export const api = {
  get: <T=any>(p:string)=>request<T>('GET',p), post: <T=any>(p:string,b?:any)=>request<T>('POST',p,b), put: <T=any>(p:string,b?:any)=>request<T>('PUT',p,b), patch: <T=any>(p:string,b?:any)=>request<T>('PATCH',p,b), del: <T=any>(p:string)=>request<T>('DELETE',p),
  // §16.3 幂等：创建类请求带 Idempotency-Key，同一用户意图（同键）重复提交只生效一次。
  postKey: <T=any>(p:string,b:any,key:string)=>request<T>('POST',p,b,{ 'Idempotency-Key': key }),
  postForm: <T=any>(p:string,form:FormData)=>requestForm<T>('POST',p,form),
}
// 每个用户意图生成一个 UUID：弹窗打开时生成、重试复用、关闭后作废（调用方持有）。
export function newIdempotencyKey(): string {
  try { return crypto.randomUUID() } catch { return 'idem-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12) }
}
