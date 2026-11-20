// 站点图标切换（1.1.0 品牌定稿集成）：主题 × 未读数 → favicon。
// - 主题口径：document.documentElement.dataset.theme 为 'dark' / 'graphite' 时用深色版
//   （/brand/brand-dark.svg），'light' 或未设置时用标准版（/favicon.svg，内含
//   prefers-color-scheme 媒体查询，系统深色时自动切深色配色）。
// - 未读口径：unread > 0 时换第二轮定稿未读版图标（浅色 /brand/favicon-badge-64.png、
//   深色 /brand/favicon-badge-dark-64.png：图形本体自带大红点 + 白圈、位于右上极角
//   压住星芒，以 Claude 第二轮定稿为准）；归零恢复主题对应版。计数角标已整类删除
//   （裁定 nyuqsfc9），真实计数由标签页标题 (n) 前缀承载。
// 本模块独立可用：shell 的未读同步与外观（主题）切换都调用 setAppIcon，
// 内部记住最近一次的 theme / unread，单方变化不会冲掉另一方状态。
export type AppIconTheme = 'light' | 'dark' | 'graphite' | string

const state = { theme: '', unread: 0 }

function currentTheme(): string {
  return state.theme || document.documentElement.dataset.theme || 'light'
}
function isDarkTheme(t: string): boolean { return t === 'dark' || t === 'graphite' }

function ensureIconLink(): HTMLLinkElement {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) { link = document.createElement('link'); link.rel = 'icon'; document.head.appendChild(link) }
  return link
}

export function setAppIcon(theme?: AppIconTheme | null, unreadCount?: number) {
  if (typeof theme === 'string' && theme) state.theme = theme
  if (typeof unreadCount === 'number') state.unread = Math.max(0, unreadCount)
  const link = ensureIconLink()
  if (state.unread > 0) {
    link.setAttribute('type', 'image/png')
    link.href = isDarkTheme(currentTheme()) ? '/brand/favicon-badge-dark-64.png' : '/brand/favicon-badge-64.png'
    return
  }
  link.setAttribute('type', 'image/svg+xml')
  link.href = isDarkTheme(currentTheme()) ? '/brand/brand-dark.svg' : '/favicon.svg'
}

export function getAppIconState() { return { ...state } }
