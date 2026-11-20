// 修订五 B：进度 100% 就地提示的「暂不」记录。
// 选择：存前端本地（localStorage，按事项 id），不存后端——理由：
// 1) 这是纯 UI 提示偏好，不影响状态/进度等业务数据，后端规则（补满/清零/清阻塞）已在流转层兜底；
// 2) 「可提交验收」标记可由 progress=100 && status=IN_PROGRESS 派生，本地 dismiss 记录只决定
//    「提示条是否收起、卡片标记是否显示」，多端不一致最多是多看一次提示，无数据风险；
// 3) 进度先降到 100 以下再回到 100% 时必须重新提示——由 clearDismissOnDrop 在观察到 <100 时清除记录实现。
const KEY = 'ib-r5-prompt-dismiss'
function read(): Record<string, boolean> {
  try { return JSON.parse(localStorage.getItem(KEY) ?? '{}') } catch { return {} }
}
function write(m: Record<string, boolean>) {
  try { localStorage.setItem(KEY, JSON.stringify(m)) } catch { /* 隐私模式等场景忽略 */ }
}
export function isPromptDismissed(itemId: number): boolean { return !!read()[itemId] }
export function dismissPrompt(itemId: number) { const m = read(); m[itemId] = true; write(m) }
/** 进度降到 100 以下时清除 dismiss，使再次回到 100% 时重新提示。 */
export function clearDismissOnDrop(itemId: number, progress: number) {
  if (progress >= 100) return
  const m = read(); if (m[itemId]) { delete m[itemId]; write(m) }
}
