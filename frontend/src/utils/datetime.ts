// 时间显示统一工具（1.0.4 H）：接口返回带偏移的 ISO 时间，前端一律按「浏览器本地时区」格式化。
// 禁止再用 slice(0,16).replace('T',' ') 直接截字符串——那会把 UTC 时间当本地时间显示（真机差 7~8 小时）。
const pad = (n: number) => String(n).padStart(2, '0')
// 'YYYY-MM-DD HH:mm'（本地时区）
export function fmtDateTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
// 'HH:mm'（本地时区）
export function fmtTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}
// 本地「今天」的日期字符串 'YYYY-MM-DD'。禁用 toISOString()（UTC）做今天判断，临界时刻会差一天。
export function localToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
// 本地日期字符串（任意时刻）
export function localDateStr(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
// 已逾期天数（1.1.0-rc2 裁定 hsa43amn 二.1）：dueDate（'YYYY-MM-DD'）距本地今天的日历天数；
// 未逾期（到期日为今天或更晚）或无日期返回 0。用 UTC 午夜差避免夏令时跨日误差。
export function overdueDays(dueDate?: string | null): number {
  if (!dueDate) return 0
  const m = dueDate.match(/^(\d+)-(\d+)-(\d+)$/)
  if (!m) return 0
  const now = new Date()
  const due = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.max(0, Math.round((today - due) / 86400000))
}
