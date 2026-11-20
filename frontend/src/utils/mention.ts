import { useTeam } from '../stores/team'
// 评论/进展文本中 @姓名 的高亮拆分（1.0.4 G）：仅高亮 mentionIds 对应成员的 @姓名，其余按普通文本。
export interface MentionSeg { text: string; mention: boolean }
export function mentionSegments(text: string, mentionIds: number[] | null | undefined): MentionSeg[] {
  if (!text) return []
  if (!mentionIds?.length) return [{ text, mention: false }]
  const team = useTeam()
  const names = mentionIds.map(id => team.memberName(id)).filter(n => n && n !== '未指派')
    .map(n => '@' + n).sort((a, b) => b.length - a.length)
  if (!names.length) return [{ text, mention: false }]
  const out: MentionSeg[] = []
  let rest = text
  while (rest) {
    let idx = -1, hit = ''
    for (const n of names) { const i = rest.indexOf(n); if (i >= 0 && (idx < 0 || i < idx)) { idx = i; hit = n } }
    if (idx < 0) { out.push({ text: rest, mention: false }); break }
    if (idx > 0) out.push({ text: rest.slice(0, idx), mention: false })
    out.push({ text: hit, mention: true })
    rest = rest.slice(idx + hit.length)
  }
  return out
}
