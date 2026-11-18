// 仅演示构建引用：vite.config 按 VITE_API_MODE 把 '@mock-adapter' 别名指向本文件或 null-adapter。
export { handleRequest } from '@mock/index'
import { DEMO_PASSWORD } from '@mock/seed'
// 一键填入使用的演示口令：与种子同一来源（环境变量 DEMO_PASSWORD，未设置时为占位符）。
export const DEMO_PASSWORD_FILL = DEMO_PASSWORD

// 演示展示数据：仅 mock 构建会打包本文件，real 构建走 null-adapter（空值），种子字符串不进真实产物。
export const DEMO_ACCOUNTS = [
  { label:'组长', login:'leader', name:'林小满', color:'#007AFF' },
  { label:'PO', login:'po', name:'陈一舟', color:'#5856D6' },
  { label:'成员', login:'member', name:'苏晴', color:'#FF9500' },
]
export const DEMO_SUBTITLE = '云雀小组 · Scrum 协同看板'
export const DEMO_FILL_HINT = '一键填入演示账号（口令为部署时设置的 DEMO_PASSWORD）'
export const DEMO_REG_CLOSED_TEXT = '本演示环境仅用于界面风格确认，请使用预置的演示账号登录体验：组长 leader、PO po、成员 member，口令为部署时设置的 DEMO_PASSWORD。'
// 演示模式的事件订阅：mock-backend 在同个页面里运行，通知生成时通过全局注册表推送（模拟 SSE）。
export function subscribeEvents(cb: (n: any) => void): () => void {
  const g = globalThis as any
  const set: Set<(n: any) => void> = g.__ibMockEventListeners ?? (g.__ibMockEventListeners = new Set())
  set.add(cb)
  return () => { set.delete(cb) }
}
