// 外观设置（深色主题定稿）：主题四档 浅色 / 深色（暮光蓝灰）/ 石墨 / 跟随系统。
// - 选择保存在个人设置（服务端 preferences_json 的 theme 键，接口 /auth/appearance-settings），
//   换设备不丢；本地 localStorage 同步缓存一份用于首屏无闪烁应用与未登录页。
// - 未登录页面（登录页）无服务端偏好可读，用本地缓存，默认跟随系统。
// - 生效方式：把「生效主题」写入 <html data-theme>（light/dark/graphite 三值之一）；
//   跟随系统在 JS 层解析（系统深色→dark 暮光蓝灰、系统浅色→light）并监听系统变化重应用。
// - 升级兼容：旧版本本地只存过 'dark' 的用户，'dark' 即暮光蓝灰，无需迁移。
import { ref } from 'vue'
import { setAppIcon } from './favicon'

export type ThemeMode = 'system' | 'light' | 'dark' | 'graphite'
export type EffectiveTheme = 'light' | 'dark' | 'graphite'
export type GlassLevel = 'clear' | 'standard' | 'frosted'
export interface Appearance { theme: ThemeMode; glass: GlassLevel; reduceTransparency: boolean; reduceMotion: boolean }
const KEY = 'ib-appearance'
export const APPEARANCE_DEFAULTS: Appearance = { theme: 'system', glass: 'standard', reduceTransparency: false, reduceMotion: false }
const THEME_MODES: ThemeMode[] = ['system', 'light', 'dark', 'graphite']

export function loadAppearance(): Appearance {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const parsed = { ...APPEARANCE_DEFAULTS, ...JSON.parse(raw) }
      if (!THEME_MODES.includes(parsed.theme)) parsed.theme = 'system'
      return parsed
    }
  } catch {}
  return { ...APPEARANCE_DEFAULTS }
}

const darkQuery = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
export function resolveEffective(mode: ThemeMode): EffectiveTheme {
  if (mode === 'system') return darkQuery?.matches ? 'dark' : 'light'
  return mode
}
/** 当前生效主题（响应式）：lockup 等需要按生效主题选资源的组件订阅它。 */
export const effectiveTheme = ref<EffectiveTheme>('light')
export function lockupSrc(eff: EffectiveTheme): string {
  return eff === 'light' ? '/brand/lockup-horizontal-light.png' : '/brand/lockup-horizontal-dark.png'
}

let current: Appearance = loadAppearance()
export function applyAppearance(a: Appearance) {
  current = a
  const eff = resolveEffective(a.theme)
  effectiveTheme.value = eff
  const el = document.documentElement
  el.setAttribute('data-theme', eff)
  el.setAttribute('data-theme-mode', a.theme)
  // 品牌定稿：切主题同步切 favicon（dark/graphite 用 brand-dark 版）；未读数由 favicon.ts 内部保留。
  setAppIcon(eff)
  el.setAttribute('data-glass', a.glass)
  el.toggleAttribute('data-reduce-transparency', a.reduceTransparency)
  el.toggleAttribute('data-reduce-motion', a.reduceMotion)
  // 团队主题色在深色下有提亮映射，主题变化后请 team store 重应用 --theme。
  document.dispatchEvent(new CustomEvent('ib-appearance'))
}
export function saveAppearance(a: Appearance) {
  try { localStorage.setItem(KEY, JSON.stringify(a)) } catch {}
  applyAppearance(a)
}
export function initAppearance() {
  applyAppearance(loadAppearance())
  // 跟随系统时监听系统深浅变化即时重解析。
  darkQuery?.addEventListener?.('change', () => { if (current.theme === 'system') applyAppearance(current) })
}

/** 登录后拉服务端主题偏好（服务端为准）；未保存过时保持本地选择。返回是否命中服务端值。 */
export async function syncAppearanceFromServer(): Promise<boolean> {
  try {
    const { api } = await import('../api/http')
    const r = await api.get('/auth/appearance-settings')
    if (r && THEME_MODES.includes(r.theme)) {
      const next = { ...loadAppearance(), theme: r.theme as ThemeMode }
      saveAppearance(next)
      return true
    }
  } catch {}
  return false
}
/** 主题选择变更时回写服务端（失败不阻塞本地生效，下次登录以服务端为准）。 */
export async function pushAppearanceToServer(theme: ThemeMode): Promise<void> {
  try {
    const { api } = await import('../api/http')
    await api.patch('/auth/appearance-settings', { theme })
  } catch {}
}
