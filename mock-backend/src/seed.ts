// 演示种子数据（修订一新模型）：全部虚构，通用软件开发场景（在线协作文档产品）。
// 严禁特定行业业务词汇、真实系统/公司名、真实人名。演示账号口令由部署/运行时环境变量 DEMO_PASSWORD 提供；未设置时使用下方占位符（仅占位、不可作为真实口令）。
// 小组「云雀小组」SKY，9 名成员；迭代 12 进行中（含任务 SKY-1/2/3 的故事），
// 大任务 SKY-3 的故事分布在迭代 12 与规划中的迭代 13；含 7 人批量下发批次、共同负责故事、独立事项。
import type { Item, User, ProgressUpdate, Comment, Activity, Iteration, Batch, ChecklistEntry } from './types'

export const DEMO_PASSWORD = (import.meta as any).env?.DEMO_PASSWORD ?? (globalThis as any).process?.env?.DEMO_PASSWORD ?? 'CHANGE_ME_DEMO_PASSWORD'

export const seedUsers: (User & { password: string })[] = [
  { id: 1, loginName: 'leader', displayName: '林小满', avatarColor: '#007AFF', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 2, loginName: 'po', displayName: '陈一舟', avatarColor: '#5856D6', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 3, loginName: 'member', displayName: '苏晴', avatarColor: '#FF2D55', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 4, loginName: 'wangqm', displayName: '王启明', avatarColor: '#30B0C7', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 5, loginName: 'lianran', displayName: '李安然', avatarColor: '#34C759', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 6, loginName: 'zhaozm', displayName: '赵子墨', avatarColor: '#FF9500', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 7, loginName: 'zhoukx', displayName: '周可欣', avatarColor: '#AF52DE', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 8, loginName: 'wutong', displayName: '吴桐', avatarColor: '#00C7BE', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 9, loginName: 'zhengnan', displayName: '郑楠', avatarColor: '#FF6482', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  // 平台管理员（M5 审计日志「全部小组」范围用）：不属于任何小组，仅平台级权限。
  { id: 10, loginName: 'admin', displayName: '平台管理员', avatarColor: '#636366', platformAdmin: true, mustChangePassword: false, password: DEMO_PASSWORD },
  // P04 同名演示：两名「张三」同在极光小组（999），选人/显示姓名时须附带登录名消歧（不进云雀小组，不影响 9 人口径）。
  { id: 11, loginName: 'zhangsan', displayName: '张三', avatarColor: '#30B0C7', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
  { id: 12, loginName: 'zhangsan2', displayName: '张三', avatarColor: '#FF9500', platformAdmin: false, mustChangePassword: false, password: DEMO_PASSWORD },
]
export const seedMembers = [
  { userId: 1, role: 'LEADER', memberStatus: 'NORMAL' }, { userId: 2, role: 'PO', memberStatus: 'NORMAL' },
  { userId: 3, role: 'MEMBER', memberStatus: 'NORMAL' }, { userId: 4, role: 'MEMBER', memberStatus: 'NORMAL' },
  { userId: 5, role: 'MEMBER', memberStatus: 'NORMAL' }, { userId: 6, role: 'MEMBER', memberStatus: 'NORMAL' },
  { userId: 7, role: 'MEMBER', memberStatus: 'NORMAL' }, { userId: 8, role: 'MEMBER', memberStatus: 'NORMAL' },
  { userId: 9, role: 'MEMBER', memberStatus: 'LEAVE' },
] as { userId:number; role:string; memberStatus:string }[]

export const seedIterations: Iteration[] = [
  { id: 1, teamId: 1, name: '迭代 12 · 体验优化', goal: '让登录、阅读与导出这三条高频路径更顺手', startDate: '2026-11-10', endDate: '2026-11-21', status: 'ACTIVE' },
  { id: 2, teamId: 1, name: '迭代 13 · 导出增强', goal: '把大文档导出做稳，支持分片生成与进度提示', startDate: '2026-11-24', endDate: '2026-12-05', status: 'PLANNING' },
]
export const seedBatches: Batch[] = [
  { id: 1, teamId: 1, creatorId: 1, title: '阅读并确认新版代码评审规范', type: 'ADHOC', createdAt: '2026-11-16T09:00:00' },
]
// M3 种子（非事项数据，不改变事项数量/状态）：关键日期、站会备注、通知、报告。
export const seedKeyDates = [
  { id: 1, teamId: 1, iterationId: 1, title: '封版', date: '2026-11-19', category: 'FREEZE' },
  { id: 2, teamId: 1, iterationId: 1, title: '迭代评审', date: '2026-11-20', category: 'REVIEW' },
  { id: 3, teamId: 1, iterationId: 1, title: '版本投产', date: '2026-11-21', category: 'RELEASE' },
]
// 站会备注按「种子加载当天」生成键：standupOf 以今天日期取备注，写死历史日期会随日期推移失效（M5 修复）。
function seedToday(): string { const d = new Date(); const p = (n: number) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` }
export const seedStandupNotes: Record<string, string> = {
  [`1|${seedToday()}`]: '今天重点：SKY-14 的设计稿确认由组长跟进；封版前请把待验收事项清一清。',
}
// M5 审计日志种子（全部虚构）：覆盖删除事项 / 成员管理 / 生成报告 / 小组设置等动作。
export function buildSeedAuditLogs() {
  const t = (h: number, mi: number) => `${seedToday()}T${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}:00`
  return [
    { id: 5, teamId: 1, actorId: 1, actorName: '林小满', action: 'UPDATE_TEAM_SETTINGS', targetType: 'TEAM', targetId: 1, detail: '修改小组设置：迭代周期 2 周', createdAt: t(9, 2) },
    { id: 4, teamId: 1, actorId: 2, actorName: '陈一舟', action: 'GENERATE_REPORT', targetType: 'REPORT', targetId: 1, detail: '生成报告「周报（2026-11-10 ~ 2026-11-16）」', createdAt: t(9, 20) },
    { id: 3, teamId: 1, actorId: 1, actorName: '林小满', action: 'UPDATE_MEMBER_STATUS', targetType: 'MEMBER', targetId: 9, detail: '将郑楠的成员状态设为请假（2026-11-16 ~ 2026-11-20）', createdAt: t(10, 5) },
    { id: 2, teamId: 1, actorId: 3, actorName: '苏晴', action: 'DELETE_ITEM', targetType: 'ITEM', targetId: 38, detail: '删除事项 SKY-38「旧版首页横幅残留样式」（移入回收站）', createdAt: t(11, 30) },
    { id: 1, teamId: 1, actorId: 1, actorName: '林小满', action: 'RESET_INVITE_CODE', targetType: 'TEAM', targetId: 1, detail: '重置小组邀请码', createdAt: t(13, 45) },
  ]
}
export function buildSeedNotifications() {
  return [
    { id: 9001, userId: 1, teamId: 1, type: 'ASSIGNMENT', category: 'ASSIGNMENT', itemId: 25, itemCode: 'SKY-25', actorId: 2, content: '你被指派了 SKY-25 编辑器协同冲突方案分析', readAt: null as string|null, createdAt: '2026-11-17T08:32:00' },
    { id: 9002, userId: 1, teamId: 1, type: 'REMINDER', category: 'REMINDER', itemId: 14, itemCode: 'SKY-14', actorId: null, content: 'SKY-14 已阻塞：等设计稿确认', readAt: null as string|null, createdAt: '2026-11-17T09:05:00' },
    { id: 9003, userId: 3, teamId: 1, type: 'NUDGE', category: 'NUDGE', itemId: 4, itemCode: 'SKY-4', actorId: 1, content: '林小满 催了你一下：SKY-4 登录页视觉还原与切图', readAt: null as string|null, createdAt: '2026-11-17T09:12:00' },
    { id: 9004, userId: 2, teamId: 1, type: 'MENTION', category: 'MENTION', itemId: 1, itemCode: 'SKY-1', actorId: 3, content: '苏晴 在 SKY-1 的评论中 @ 了你', readAt: '2026-11-16T18:20:00' as string|null, createdAt: '2026-11-15T10:12:00' },
  ]
}
export function buildSeedReports() {
  return [{
    id: 1, teamId: 1, kind: 'WEEKLY', title: '周报（2026-11-10 ~ 2026-11-16）', periodStart: '2026-11-10', periodEnd: '2026-11-16', ownerId: null as number|null,
    content: '# 周报（2026-11-10 ~ 2026-11-16）\n\n> 本报告由系统按结构化数据自动生成（非 AI 生成），生成后可编辑。\n\n## 本周完成（8 件）\n- SKY-7 登录页埋点接入\n- SKY-16 导出性能瓶颈分析\n- SKY-24 搜索结果高亮错位\n\n## 进行中（10 件）\n- SKY-4 登录页视觉还原与切图（60%）\n- SKY-14 导出分页计算修复（45%，阻塞：等设计稿确认）\n\n## 风险\n- SKY-14 已阻塞：等设计稿确认\n',
    aiGenerated: false, edited: false, editedByHuman: false, version: 1, deletedAt: null, lastRegeneratedAt: null, versions: [], createdAt: '2026-11-16T18:30:00', updatedAt: '2026-11-16T18:30:00',
  }]
}

type P = Partial<Item> & { title: string; type: Item['type'] }
function mk(id: number, p: P): Item {
  return {
    id, code: `SKY-${id}`, description: `「${p.title}」的说明：范围、做法与验收口径在评审时已对齐，按看板进展推进即可。`,
    status: 'UNCLAIMED', priority: 'P2', creatorId: 2, ownerIds: [], acceptorId: null, storyCategory: null, poId: null,
    batchId: null, assignedDirectly: false, sortOrder: id, taskStatus: null, collaboratorIds: [], inviteeIds: [],
    parentId: null, iterationId: 1, dueDate: null, estimate: null, progressPercent: 0, blocked: false, blockReason: null,
    nudgeCount: 0, labels: [], childCount: 0, childDoneCount: 0, commentCount: 0, version: 1,
    createdAt: '2026-11-10T09:00:00', updatedAt: '2026-11-16T16:00:00', lastProgressAt: null, deletedAt: null, checklist: [],
    ...p,
  } as Item
}
const T = (id:number, title:string, extra:P['description'] extends never?never:any={}) => mk(id, { type:'TASK', title, poId: 2, creatorId: 2, iterationId: null, acceptorId: null, ...extra })
const S = (id:number, parentId:number, cat:Item['storyCategory'], title:string, extra:any={}) => mk(id, { type:'STORY', title, parentId, storyCategory: cat, creatorId: 2, acceptorId: 2, ...extra })

export function buildItems(): Item[] {
  const items: Item[] = [
    T(1, '登录页改版', { description: '## 背景\n登录页还是老版本式样，与新版品牌视觉不一致。\n\n## 目标\n- 完成新版登录页视觉与交互\n- 错误提示就地显示\n\n## 验收\n主流分辨率下无错位。', labels: ['体验优化'] }),
    T(2, '模板库一期'),
    T(3, '文档导出稳定性', { description: '长文档导出偶发空白页且耗时不稳定，本任务跨两个迭代处理：本迭代先修分页与图片跨页，下个迭代做分片生成与进度提示。' }),
    // SKY-1 的故事（迭代 12）
    S(4, 1, 'DEV', '登录页视觉还原与切图', { ownerIds: [3], status: 'IN_PROGRESS', priority: 'P1', estimate: 3, progressPercent: 60, dueDate: '2026-11-18', lastProgressAt: '2026-11-16T15:30:00' }),
    S(5, 1, 'DEV', '登录表单校验与错误提示', { ownerIds: [3], status: 'IN_PROGRESS', priority: 'P1', estimate: 2, progressPercent: 40, dueDate: '2026-11-19', lastProgressAt: '2026-11-16T11:00:00' }),
    S(6, 1, 'TEST', '登录页多分辨率回归', { ownerIds: [7], status: 'NOT_STARTED', estimate: 2, dueDate: '2026-11-20', assignedDirectly: true }),
    S(7, 1, 'DEV', '登录页埋点接入', { ownerIds: [8], status: 'DONE', estimate: 1, progressPercent: 100, dueDate: '2026-11-15', lastProgressAt: '2026-11-14T17:00:00' }),
    S(8, 1, 'OTHER', '登录页空状态与加载态文案', { status: 'UNCLAIMED', estimate: 1, dueDate: '2026-11-21' }),
    // SKY-2 的故事（迭代 12）
    S(9, 2, 'ANALYSIS', '模板数据结构设计', { ownerIds: [4], status: 'IN_PROGRESS', priority: 'P1', estimate: 3, progressPercent: 30, dueDate: '2026-11-19', lastProgressAt: '2026-11-16T10:00:00' }),
    S(10, 2, 'DEV', '模板预览缩略图生成', { status: 'UNCLAIMED', estimate: 2, dueDate: '2026-11-21', inviteeIds: [5] }),
    S(11, 2, 'DEV', '模板列表与搜索', { ownerIds: [5], status: 'NOT_STARTED', estimate: 3, dueDate: '2026-11-21', assignedDirectly: true }),
    S(12, 2, 'TEST', '模板导入导出兼容测试', { ownerIds: [7], status: 'NOT_STARTED', estimate: 2, dueDate: '2026-11-20', assignedDirectly: true }),
    S(13, 2, 'DEV', '模板分享链接生成', { ownerIds: [5, 6], status: 'IN_PROGRESS', estimate: 3, progressPercent: 50, dueDate: '2026-11-20', lastProgressAt: '2026-11-16T14:00:00' }),
    // SKY-3 的故事：迭代 12
    S(14, 3, 'DEV', '导出分页计算修复', { ownerIds: [6], status: 'IN_PROGRESS', priority: 'P0', estimate: 3, progressPercent: 45, dueDate: '2026-11-17', blocked: true, blockReason: '等设计稿确认', nudgeCount: 3, lastProgressAt: '2026-11-16T11:00:00' }),
    S(15, 3, 'DEV', '导出图片跨页处理', { ownerIds: [6], status: 'IN_REVIEW', estimate: 2, progressPercent: 100, dueDate: '2026-11-17', lastProgressAt: '2026-11-16T14:20:00' }),
    S(16, 3, 'ANALYSIS', '导出性能瓶颈分析', { ownerIds: [4], status: 'DONE', estimate: 2, progressPercent: 100, dueDate: '2026-11-15', lastProgressAt: '2026-11-14T17:00:00' }),
    // SKY-3 的故事：迭代 13（规划中）
    S(17, 3, 'DEV', '导出大文档分片生成', { ownerIds: [8], status: 'NOT_STARTED', estimate: 5, dueDate: '2026-12-03', iterationId: 2, assignedDirectly: true }),
    S(18, 3, 'TEST', '导出长文档回归测试集', { status: 'UNCLAIMED', estimate: 3, dueDate: '2026-12-04', iterationId: 2 }),
    S(19, 3, 'DEV', '导出进度提示与取消', { status: 'UNCLAIMED', estimate: 2, dueDate: '2026-12-05', iterationId: 2 }),
    // 独立事项
    mk(20, { type: 'DEFECT', title: '文档导出 PDF 偶发空白', ownerIds: [6], status: 'IN_PROGRESS', priority: 'P0', estimate: 1, progressPercent: 40, dueDate: '2026-11-17', creatorId: 7, acceptorId: 7, lastProgressAt: '2026-11-16T11:30:00', description: '## 现象\n长文档导出 PDF 时偶发空白页。\n\n## 初步判断\n与分页时图片跨页有关，先补复现用例再修。' }),
    mk(21, { type: 'DEFECT', title: '图片上传旋转方向不对', ownerIds: [7], status: 'IN_REVIEW', priority: 'P1', estimate: 1, progressPercent: 100, dueDate: '2026-11-17', creatorId: 3, acceptorId: 3, iterationId: null, lastProgressAt: '2026-11-16T13:00:00' }),
    mk(22, { type: 'DEFECT', title: '评论区长链接撑破布局', ownerIds: [9], status: 'NOT_STARTED', estimate: 1, dueDate: '2026-11-19', creatorId: 3, acceptorId: 3, assignedDirectly: true }),
    mk(23, { type: 'DEFECT', title: '移动端键盘遮挡输入框', status: 'UNCLAIMED', priority: 'P1', estimate: 1, dueDate: '2026-11-20', creatorId: 3, acceptorId: 3, iterationId: null }),
    mk(24, { type: 'DEFECT', title: '搜索结果高亮错位', ownerIds: [4], status: 'DONE', estimate: 1, progressPercent: 100, dueDate: '2026-11-15', creatorId: 5, acceptorId: 5, lastProgressAt: '2026-11-14T17:00:00' }),
    mk(25, { type: 'ANALYSIS', title: '编辑器协同冲突方案分析', ownerIds: [4], status: 'IN_PROGRESS', estimate: 2, progressPercent: 35, dueDate: '2026-11-20', creatorId: 1, acceptorId: 1, blocked: true, blockReason: '依赖的接口还没联调完', lastProgressAt: '2026-11-15T15:00:00' }),
    mk(26, { type: 'ANALYSIS', title: '离线编辑可行性调研', status: 'UNCLAIMED', priority: 'P3', estimate: 2, dueDate: '2026-11-21', creatorId: 1, acceptorId: 1, iterationId: null }),
    mk(27, { type: 'ADHOC', title: '整理本周用户反馈清单', ownerIds: [9], status: 'IN_PROGRESS', estimate: 1, progressPercent: 80, dueDate: '2026-11-17', creatorId: 1, acceptorId: 1, iterationId: null, nudgeCount: 2, lastProgressAt: '2026-11-16T17:10:00' }),
    mk(28, { type: 'ADHOC', title: '准备迭代评审演示环境', ownerIds: [7], status: 'NOT_STARTED', estimate: 1, dueDate: '2026-11-19', creatorId: 1, acceptorId: 1, assignedDirectly: true }),
    mk(29, { type: 'ADHOC', title: '更新帮助中心常见问题', ownerIds: [9], status: 'IN_REVIEW', priority: 'P3', estimate: 1, progressPercent: 100, dueDate: '2026-11-18', creatorId: 1, acceptorId: 1, iterationId: null, lastProgressAt: '2026-11-16T16:00:00' }),
    mk(30, { type: 'ADHOC', title: '整理模板库示例文档', status: 'UNCLAIMED', priority: 'P3', estimate: 1, dueDate: '2026-11-21', creatorId: 1, acceptorId: 1, iterationId: null }),
    // 批次 1：7 人每人一份（成员 3–9）
    ...[3, 4, 5, 6, 7, 8, 9].map((uid, i) => mk(31 + i, {
      type: 'ADHOC', title: '阅读并确认新版代码评审规范', ownerIds: [uid], batchId: 1, assignedDirectly: true,
      status: uid === 3 || uid === 4 ? 'DONE' : uid === 5 ? 'IN_PROGRESS' : 'NOT_STARTED',
      progressPercent: uid === 3 || uid === 4 ? 100 : uid === 5 ? 50 : 0,
      estimate: 1, dueDate: '2026-11-19', creatorId: 1, acceptorId: 1, iterationId: null,
      lastProgressAt: uid === 5 ? '2026-11-16T15:00:00' : uid === 3 || uid === 4 ? '2026-11-15T17:00:00' : null,
    })),
    mk(38, { type: 'DEFECT', title: '旧版首页横幅残留样式', ownerIds: [8], status: 'NOT_STARTED', estimate: 1, creatorId: 3, acceptorId: 3, deletedAt: '2026-11-16T18:00:00' }),
    mk(39, { type: 'ADHOC', title: '收集深色模式用户访谈提纲', ownerIds: [3], status: 'DONE', priority: 'P3', estimate: 1, progressPercent: 100, dueDate: '2026-11-14', creatorId: 1, acceptorId: 1, iterationId: null, lastProgressAt: '2026-11-14T17:00:00', labels: ['改进项'] }),
    mk(40, { type: 'STORY', title: '登录页深色模式适配', parentId: 1, storyCategory: 'DEV', ownerIds: [8], status: 'IN_PROGRESS', estimate: 2, progressPercent: 25, dueDate: '2026-11-21', creatorId: 2, acceptorId: 2, lastProgressAt: '2026-11-13T10:00:00' }),
  ]
  const cl = (id:number, itemId:number, text:string, done:boolean, sortOrder:number): ChecklistEntry => ({ id, itemId, text, done, sortOrder })
  items.find(i => i.id === 20)!.checklist = [cl(1, 20, '补复现用例', true, 1), cl(2, 20, '定位分页计算', false, 2), cl(3, 20, '回归长文档导出', false, 3)]
  items.find(i => i.id === 4)!.checklist = [cl(4, 4, '视觉稿确认', true, 1), cl(5, 4, '切图与变量整理', true, 2), cl(6, 4, '联调与走查', false, 3)]
  return items
}
export function buildProgress(items: Item[]): ProgressUpdate[] {
  const out: ProgressUpdate[] = []; let id = 1
  const add = (itemId:number, authorId:number, text:string, percent:number|null, createdAt:string, blocked=false, blockReason:string|null=null, nextStep:string|null=null) =>
    out.push({ id: id++, itemId, authorId, text, percent, blocked, blockReason, nextStep, kind: 'NORMAL', createdAt })
  add(4, 3, '视觉稿已过评审，切图和变量整理完成，开始联调表单校验。', 60, '2026-11-16T15:30:00', false, null, '完成登录表单校验联调')
  add(5, 3, '校验规则梳理完，正在逐条接入表单。', 40, '2026-11-16T11:00:00')
  add(14, 6, '已确认空白页与图片跨页分页有关，正在补日志验证。', 45, '2026-11-16T11:00:00', true, '等设计稿确认', '拿到确认稿后回归长文档')
  add(15, 6, '图片跨页处理已实现，提交验收，请重点看跨页大图场景。', 100, '2026-11-16T14:20:00')
  add(25, 4, '冲突合并策略对比到一半，等接口联调完再补结论。', 35, '2026-11-15T15:00:00', true, '依赖的接口还没联调完')
  add(27, 9, '用户反馈清单整理到八成，剩访谈记录待合并。', 80, '2026-11-16T17:10:00')
  add(33, 5, '规范读完一半，示例部分再细看一遍。', 50, '2026-11-16T15:00:00')
  add(40, 8, '深色模式变量梳理中，进度偏慢。', 25, '2026-11-13T10:00:00')
  for (const it of items) if (it.status === 'DONE') add(it.id, it.ownerIds[0] ?? it.creatorId, '已完成并通过验收。', 100, '2026-11-14T17:00:00')
  return out
}
export function buildComments(items: Item[]): Comment[] {
  const out: Comment[] = []; let id = 1
  const add = (itemId:number, authorId:number, content:string, createdAt:string, mentionIds:number[] = []) => out.push({ id: id++, itemId, authorId, content, mentionIds, createdAt })
  add(1, 3, '登录页空状态的文案还没定，麻烦 PO 确认一下。', '2026-11-15T10:12:00', [2])
  add(1, 2, '收到，文案今天定稿后同步到故事 SKY-8。', '2026-11-15T10:40:00')
  add(14, 1, '这个阻塞超过一天了，设计稿确认我来催一下。', '2026-11-16T12:00:00')
  add(21, 7, '已按图片方向信息自动校正，麻烦在待验收里看一下效果。', '2026-11-16T13:00:00')
  void items; return out
}
export function buildActivities(items: Item[]): Activity[] {
  const out: Activity[] = []; let id = 1
  for (const it of items) {
    out.push({ id: id++, itemId: it.id, actorId: it.creatorId, action: 'CREATE', fromStatus: null, toStatus: it.type === 'TASK' ? null : 'UNCLAIMED', detail: '创建事项', createdAt: it.createdAt })
    if (it.ownerIds.length) out.push({ id: id++, itemId: it.id, actorId: it.ownerIds[0], action: it.assignedDirectly ? 'TRANSFER' : 'CLAIM', fromStatus: 'UNCLAIMED', toStatus: 'NOT_STARTED', detail: it.assignedDirectly ? '直接指派' : '认领事项', createdAt: '2026-11-11T09:30:00' })
    if (it.blocked) out.push({ id: id++, itemId: it.id, actorId: it.ownerIds[0] ?? it.creatorId, action: 'BLOCK', fromStatus: it.status, toStatus: it.status, detail: it.blockReason, createdAt: '2026-11-15T15:00:00' })
  }
  return out
}
