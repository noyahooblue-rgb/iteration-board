// P04 同名消歧：同一小组成员里出现重名（displayName 相同）时，显示姓名附带登录名，如「张三（zhangsan）」。
// 选人组件与姓名显示处统一走这里，避免各处口径不一致。
import type { Member } from '../api/types'
export function duplicateNames(members: Pick<Member, 'displayName'>[]): Set<string> {
  const seen = new Map<string, number>()
  for (const m of members) seen.set(m.displayName, (seen.get(m.displayName) ?? 0) + 1)
  return new Set([...seen.entries()].filter(([, n]) => n > 1).map(([name]) => name))
}
export function memberLabel(members: Pick<Member, 'displayName' | 'loginName'>[], m: Pick<Member, 'displayName' | 'loginName'>): string {
  return duplicateNames(members).has(m.displayName) ? `${m.displayName}（${m.loginName}）` : m.displayName
}
export function memberLabelById(members: Member[], userId: number | null | undefined): string {
  if (userId == null) return '未指派'
  const m = members.find(x => x.userId === userId)
  return m ? memberLabel(members, m) : '未指派'
}
