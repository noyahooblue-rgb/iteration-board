# 事项状态流转表

真实后端与浏览器模拟后端共用本表。任何流转规则变更先改本表，再改两边实现。

> M1 收尾定稿（2026-11-17 验收拍板）：①转交作用于待认领事项视同「指派」；②通过/打回验收人或组长均可；③进展代登仅负责人或组长；④新增「婉拒 DECLINE」动作。
>
> M2（修订一，最高口径）：类型改为 TASK 任务 / STORY 故事 / DEFECT 缺陷 / ANALYSIS 分析 / ADHOC 临时事项；父子只允许 STORY→TASK；负责人改为多名共同负责人（ownerIds）；DECLINE 转为正式动作并实现；任务状态由故事推算（单独成节）。
>
> M3（验收批复落实，2026-11-17）：①任务新增 REJECT 打回路径（见「任务状态推算」节改写）；②TRANSFER 共同负责口径定稿——只替换一位负责人，M2 暂行口径作废；③小组设置新增容量系数（每人每个工作日约等于几个故事点，默认 1），个人剩余容量 = 剩余可用工作日 × 系数，只提示不拦截；④检查清单权限维持 M2 口径（创建人、所有共同负责人、PO、组长可改，其余只读）；⑤新增单事项催一下与自动提醒规则节。

## 类型与层级

| 类型 | 谁可创建 | 认领 | 说明 |
| --- | --- | --- | --- |
| TASK 任务 | 仅 PO（接口层 403 拒绝非 PO） | 不认领、无负责人、无迭代归属 | 有所属 PO（poId）；状态由故事推算 |
| STORY 故事 | 仅 PO（接口层 403 拒绝非 PO） | 可认领 | 必须挂同组 TASK（parentId）且必带故事类别 storyCategory（DEV/ANALYSIS/TEST/OTHER） |
| DEFECT / ANALYSIS / ADHOC 独立事项 | 任何成员 | 可认领 | 不属于任何任务，不得带 parentId |

## 状态（故事与独立事项）

| 枚举 | 显示 | 含义 | 轮到谁 |
| --- | --- | --- | --- |
| UNCLAIMED | 待认领 | 还没有负责人 | 全组 |
| NOT_STARTED | 待开始 | 已有负责人，尚未动工 | 负责人（可多名共同负责人） |
| IN_PROGRESS | 进行中 | 正在做 | 负责人 |
| IN_REVIEW | 待| 负责人已提交 | 验收人 |
| DONE | 已完成 | 验收通过 | 无 |
| CANCELLED | 已取消 | 不再做 | 无 |

阻塞是标记（`blocked` + `blockReason`），不是状态；仅 NOT_STARTED / IN_PROGRESS 可标记/解除阻塞。

## 动作

| action | 从 | 到 | 谁可以 | 说明 |
| --- | --- | --- | --- | --- |
| CLAIM 认领 | UNCLAIMED | NOT_STARTED | 任何成员 | 认领者成为（单一）负责人；先到先得，并发时仅一人成功，另一人收到 409 |
| START 开始 | NOT_STARTED | IN_PROGRESS | 任一负责人 | 首次登记进展时自动触发（POST progress 时若为 NOT_STARTED 则先自动 START） |
| SUBMIT 提交| IN_PROGRESS | IN_REVIEW | 任一负责人 | 验收人就是提交人本人时直接到 DONE |
| APPROVE 通过 | IN_REVIEW | DONE | 验收人或组长 | 定稿（验收拍板二）：验收人、组长均可操作 |
| REJECT 打回 | IN_REVIEW | IN_PROGRESS | 验收人或组长 | 必须填写原因（reason 必填）；定稿口径同 APPROVE |
| RELEASE 释放 | NOT_STARTED / IN_PROGRESS | UNCLAIMED | 任一负责人 | 必须填写原因；**清空全部共同负责人**；通知创建人。已开始（登记过进展）的事项退回认领池走本动作，不走婉拒 |
| DECLINE 婉拒 | NOT_STARTED | UNCLAIMED | 仅被直接指派的负责人本人 | **M2 正式实现**。五条规则（验收任务第三部分第 5 条定稿）：①动作名 DECLINE，从待开始回到待认领；②只有被直接指派的负责人能做（事项 `assigned_directly=true` 且操作人是其负责人）；③只在尚未开始时可用——事项无任何 progress 记录（有进展记录返回 422）；④必须填写原因（reason 必填，缺失 400）；⑤清空全部负责人，通知创建人。已经开始的事项不婉拒，走「释放」 |
| TRANSFER 转交 | 状态不变（例外见说明） | 状态不变 | 创建人/负责人本人，或 PO/组长（见权限矩阵） | 通知新旧负责人。**例外（定稿）：作用于 UNCLAIMED 待认领事项时视同「指派」——设定负责人后状态进入 NOT_STARTED，并通知被指派人**。**共同负责口径（M3 定稿，取代 M2 暂行）：只替换一位负责人**——`replaceUserId` 指定被替换者，缺省为发起人本人；发起人不是负责人且未指定 `replaceUserId` 返回 400；其余共同负责人保留；单负责人时即整体替换，与原规则相同 |
| JOIN 加入共同负责 | NOT_STARTED / IN_PROGRESS / IN_REVIEW（已有负责人且未完成） | 状态不变 | 任何成员（POST /items/{id}/join，非 transitions 动作） | 加入者成为共同负责人，权限与原负责人相同（可登记进展、提交验收）；通知原负责人。无负责人（UNCLAIMED）不可 JOIN，走 CLAIM；已是负责人返回 409 |
| CANCEL 取消 | 任意未完成状态 | CANCELLED | 自己创建且尚未开始的：创建人；其余：PO/组长（见权限矩阵） | 必须填写原因 |
| REOPEN 重新打开 | DONE / CANCELLED | 有负责人→IN_PROGRESS；无负责人→UNCLAIMED | 创建人、验收人、PO、组长 | |

## 进展登记权限

- 登记进展（POST progress）仅**任一共同负责人本人或组长**可为事项登记；PO 不能代登他人事项（PO 仅能登记自己负责的事项）。
- 登记进展后，该事项的 nudgeCount（本轮被催次数）清零。

## 任务状态推算（TASK 专用，M2 新增成节）

任务不参与上表流转，其状态（TaskStatus）与进度由其下故事推算，在故事创建、流转、删除、恢复后立即重算：

| TaskStatus | 显示 | 推算条件 |
| --- | --- | --- |
| NOT_STARTED | 未开始 | 无（未删除的）故事，或非取消故事全部为 UNCLAIMED/NOT_STARTED |
| IN_PROGRESS | 进行中 | 存在故事处于 IN_PROGRESS / IN_REVIEW，或部分（非全部）非取消故事已 DONE |
| PENDING_ACCEPTANCE | 待| 非取消故事全部 DONE 且至少一条；推算进入本状态时给 poId 所属 PO 建一条通知（同一轮推算只建一条） |
| DONE | 已完成 | PO 通过（PENDING_ACCEPTANCE 时由 PO 执行 APPROVE）后置位，不由推算直接产生 |
| CANCELLED | 已取消 | PO 执行 CANCEL 取消任务（reason 必填）；不由推算产生 |

- 取消的故事（CANCELLED）不计入推算；已软删除的故事不计入推算，恢复后重新计入并重算。
- 任务进度 progressPercent 为推算值：按故事点（estimate）加权——已完成故事点 / 非取消故事总点；无估算（总点为 0）时按件数——已完成件数 / 非取消故事总件数；无故事时为 0。
- **任务打回（M3 新增，验收批复待问 1 定稿，取代此前「无打回路径」口径）**：任务处于 PENDING_ACCEPTANCE（内部 IN_REVIEW）时，所属 PO（或任一 PO）可执行 REJECT：`reason` 必填；且必须同时给出可做的事——`reopenItemIds` 重开至少一个该任务下已完成（DONE）的故事，或 `newStories` 新建至少一个补充故事（进认领池 UNCLAIMED，类别必填）；两者皆空返回 422，不允许「没有任何故事可做」的打回。被重开的故事回到 IN_PROGRESS（保留原负责人与进展记录），任务状态随推算立即回到 IN_PROGRESS；打回写 activity 并通知被重开故事的负责人。
- 任务本身不认领、不催更；催更对象是其下故事。

## 催一下

- 谁可以催：组内任何人；对象是**不由自己负责**的事项（自己是共同负责人之一时返回 400）。任务（TASK）不可催（422），催其下故事。
- 催谁：轮到谁催谁——NOT_STARTED / IN_PROGRESS 催全部负责人；IN_REVIEW 催验收人；UNCLAIMED / DONE / CANCELLED 不可催（422）。
- 频率：同一发起人对同一事项每天只能催一次（按 nudge 记录判定），第二次返回 409 `NUDGE_DAILY_LIMIT`。
- 被催人当日处于请假/出差状态（成员状态当日生效）时，不提醒本人：该条 nudge 改记给组长并通知组长（redirected）；组内无组长则跳过该人。至少一位真实对象被催到时事项 `nudgeCount` +1（仅改记组长不计数，与批量下发口径一致）；负责人登记进展后清零（既有规则）。
- 快捷回应：被催人（nudge 的 target 本人）可对未回应的 nudge 回应 SOON（马上更新）/ TODAY（今天搞定）/ BLOCKED（卡住了），回应写入 nudge.response 并通知发起人（NUDGE_RESPONSE）。

## 自动提醒与容量

- 自动提醒按 规则表由扫描生成（定时扫描 + 组长「立即扫描」同一实现）：阈值取小组设置 `reminderThresholds`（工作日口径，默认：进行中无进展 2、待开始未动工 2、待验收未处理 2、待认领无人领 3、阻塞未解除 2、临近截止提前 1、逾期每个工作日）；工作日 = 周一至周五，扣小组设置的特殊非工作日、加特殊工作日（调休）。请假/出差成员不提醒本人，改提醒组长。同一规则、同一事项、同一接收人当天不重复生成。
- 容量系数（验收批复待问 3）：小组设置 `capacityPointsPerWorkday`（默认 1）。个人本迭代剩余容量 = 剩余可用工作日（自今天起到迭代结束，扣本人非 NORMAL 状态覆盖日）× 系数。认领池/规划页的超容量提示用此口径比较「已认领未完成故事点（共同负责按人数均分）」，只提示、不拦截。

## 批量下发（每人一份，M2）

- 建批次为每位被选成员各生成一个独立事项、直接指派给本人（assigned_directly=true，状态 NOT_STARTED），同属一个 dispatch_batch；故事批次同样仅 PO 可发。
- 批次汇总：完成数（DONE 件数）/ 总数 + 逐人状态。
- 一键催未完成：遵守同一人对同一事项每天一次（nudge 表记录）；被催人请假/出差（成员状态当日有效）时跳过本人、改记给组长，组内无组长则只跳过。
- 整批取消：只取消未完成项，已完成保留；取消必须填写原因。

## 通用规则

- 所有流转接口必须带事项当前 `version`；版本不一致返回 409 `VERSION_CONFLICT`（含两人同时认领：落败方版本已变，返回 409 并提示已被认领）。
- 不允许的流转返回 422 `ILLEGAL_TRANSITION`；无权限返回 403 `FORBIDDEN`；非小组成员访问返回 404 `NOT_FOUND`（不泄露存在性，跨组隔离）。
- 每次流转写一条 activity 时间线（动作、操作人、前后状态、原因）。
- 删除一律软删除进回收站（DELETE /items/{id}），回收站列表 GET /trash，恢复 POST /items/{id}/restore。

## 修订二注记

- **报告生命周期**：报告不是事项状态机的一部分，但有自己的生命周期——生成（周期唯一，重复 generate 返回已有份 existed=true）→ 人工编辑（editedByHuman=true）→ 重新生成（先存历史版本，每份 ≤5 个；60 秒节流 409 REGEN_TOO_FREQUENT；生成后 editedByHuman=false）→ 软删除进回收站（deletedAt，30 天口径）→ 可恢复。已删的自动报告留墓碑行：同周期 generate 与定时任务都不得再生成。历史版本可查看与恢复，恢复前同样存版且恢复后 editedByHuman=true。
- **删除口径**：任务仅 PO/组长可删且其下故事须全部未开始（UNCLAIMED/NOT_STARTED 且无进展）；故事仅 PO/组长可删且须未开始；独立事项（DEFECT/ANALYSIS/ADHOC）维持既有口径（创建人未开始时或 PO/组长）。
- **故事 PATCH**：PO/组长可全改；负责人（非 PO/组长）只能改 description，触及其余字段返回 403；检查清单权限维持 M3 口径（canEdit）。
- **评论**：作者发出后 15 分钟内可改（PATCH），超时 403；删除为作者或组长（软删除）。
- **迭代**：创建仅组长；同时最多一个 ACTIVE（开始时已有 ACTIVE 返回 409，既有口径）与一个 PLANNING（再建返回 409 PLANNING_ITERATION_EXISTS）。结束迭代只能生效一次：重复 close 返回 existing=true 与既有迭代总结 reportId，不重复生成改进项。
- **幂等**：POST /items、POST /dispatch-batches、POST /reports/generate、POST /iterations/{id}/close 支持 Idempotency-Key 请求头；同用户同键 10 分钟内重复提交只生效一次，第二次返回首次结果（状态码与响应体）。
- **认领池可见范围**：GET /items?status=UNCLAIMED&scope=all 返回本组全部待认领故事与独立事项，不论创建人、迭代归属与邀请对象；INVITE 事项对非被邀请成员同样可见（后端已由集成测试实证）。
