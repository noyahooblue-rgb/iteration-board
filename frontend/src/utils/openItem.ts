// 修订六契约 4：全平台唯一的「打开事项」入口。任何地方要打开某个事项都走这里，
// 跳到统一直达地址 /i/:id，由 ItemLinkView 按 resolution 实时解析（任务转详情页、
// 故事/独立事项定位背景页后开抽屉），禁止各处再自己拼 /task/ 或本地开抽屉。
// commentId / progressId 透传为 query，抽屉打开后滚动并高亮对应评论/进展。
import type { Router } from 'vue-router'
export interface OpenItemOpts { commentId?: number | null; progressId?: number | null }
export function openItem(router: Router, id: number, opts?: OpenItemOpts) {
  const query: Record<string, string> = {}
  if (opts?.commentId != null) query.comment = String(opts.commentId)
  if (opts?.progressId != null) query.progress = String(opts.progressId)
  return router.push({ path: `/i/${id}`, query })
}
// 通知点击的 refId 映射（修订六契约 4）：后端通知 type 常量（backend NotificationView/ItemService 实查）：
// - 评论 @ 与进展 @ 同为 type=MENTION，靠 content 区分（「有人在评论里 @ 了你」/「有人在进展里 @ 了你」）；
// - type=COMMENT 为「关注/负责事项有新评论」；type 含 PROGRESS 的按进展处理。
// 注意：当前后端 MENTION/COMMENT 通知尚未写入 refId（NotifyService.notify 未传 refId，仅反馈类通知带 refId），
// refId 为空时不带高亮参数、只打开事项本身；后端补上 refId 后本映射即自动生效。
export function notifHighlightOpts(n: { type?: string | null; refId?: number | null; content?: string | null }): OpenItemOpts {
  const refId = n.refId ?? null
  if (refId == null) return {}
  const type = n.type ?? ''
  if (type.includes('PROGRESS') || (type === 'MENTION' && (n.content ?? '').includes('进展'))) return { progressId: refId }
  if (type === 'MENTION' || type.includes('COMMENT')) return { commentId: refId }
  return {}
}
