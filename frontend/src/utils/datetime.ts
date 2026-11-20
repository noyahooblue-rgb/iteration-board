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
