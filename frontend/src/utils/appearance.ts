// 外观设置：主题模式 / 玻璃强度 / 减少透明度 / 减少动效。
// 纯本地偏好：存 localStorage，不走后端接口，真实与 mock 两种构建行为一致。
// 生效方式：写入 <html> 的 data-theme / data-glass / data-reduce-* 属性，CSS 变量据此切换。
export type ThemeMode = 'system' | 'light' | 'dark'
export type GlassLevel = 'clear' | 'standard' | 'frosted'
export interface Appearance { theme: ThemeMode; glass: GlassLevel; reduceTransparency: boolean; reduceMotion: boolean }
const KEY = 'ib-appearance'
export const APPEARANCE_DEFAULTS: Appearance = { theme: 'system', glass: 'standard', reduceTransparency: false, reduceMotion: false }
export function loadAppearance(): Appearance {
  try { const raw = localStorage.getItem(KEY); if (raw) return { ...APPEARANCE_DEFAULTS, ...JSON.parse(raw) } } catch {}
  return { ...APPEARANCE_DEFAULTS }
}
export function applyAppearance(a: Appearance) {
  const el = document.documentElement
  if (a.theme === 'system') el.removeAttribute('data-theme'); else el.setAttribute('data-theme', a.theme)
  el.setAttribute('data-glass', a.glass)
  el.toggleAttribute('data-reduce-transparency', a.reduceTransparency)
  el.toggleAttribute('data-reduce-motion', a.reduceMotion)
}
export function saveAppearance(a: Appearance) {
  try { localStorage.setItem(KEY, JSON.stringify(a)) } catch {}
  applyAppearance(a)
}
export function initAppearance() { applyAppearance(loadAppearance()) }
