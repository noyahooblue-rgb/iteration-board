// 类型层：与 contract/openapi.yaml 0.3.0 对齐（修订一新模型 + M3 协同）。
export type Role='LEADER'|'PO'|'MEMBER'
export type ItemType='TASK'|'STORY'|'DEFECT'|'ANALYSIS'|'ADHOC'
export type StoryCategory='DEV'|'ANALYSIS'|'TEST'|'OTHER'
export type ItemStatus='UNCLAIMED'|'NOT_STARTED'|'IN_PROGRESS'|'IN_REVIEW'|'DONE'|'CANCELLED'
export type TaskStatus='NOT_STARTED'|'IN_PROGRESS'|'PENDING_ACCEPTANCE'|'DONE'|'CANCELLED'
export interface User{id:number;loginName:string;displayName:string;avatarColor:string;platformAdmin:boolean;mustChangePassword:boolean}
export interface TeamSettings{capacityPointsPerWorkday:number;reminderThresholds:{staleInProgressWorkdays:number;notStartedWorkdays:number;reviewPendingWorkdays:number;unclaimedWorkdays:number;blockedWorkdays:number;dueSoonWorkdays:number};specialHolidays:string[];specialWorkdays:string[]}
export interface Team{id:number;name:string;code:string;inviteCode?:string;joinApproval:boolean;iterationWeeks:number;estimateUnit:string;themeColor:string;status:string;myRole?:Role;description?:string|null;settings?:TeamSettings}
export interface Member{userId:number;displayName:string;loginName:string;avatarColor:string;role:Role;memberStatus:string;memberStatusStart?:string|null;memberStatusEnd?:string|null}
export interface JoinRequest{id:number;userId:number;displayName:string;loginName:string;status:string;createdAt:string}
export interface ChecklistEntry{id:number;itemId:number;text:string;done:boolean;sortOrder:number}
export interface Item{allowedActions?:string[];id:number;code:string;type:ItemType;title:string;description:string;status:ItemStatus;priority:string;creatorId:number;ownerIds:number[];acceptorId:number|null;storyCategory:StoryCategory|null;poId:number|null;batchId:number|null;assignedDirectly:boolean;sortOrder:number;taskStatus:TaskStatus|null;collaboratorIds:number[];inviteeIds?:number[];parentId:number|null;iterationId:number|null;dueDate:string|null;estimate:number|null;progressPercent:number;blocked:boolean;blockReason:string|null;nudgeCount:number;labels:string[];childCount:number;childDoneCount:number;commentCount:number;version:number;createdAt:string;updatedAt:string;lastProgressAt:string|null;deletedAt?:string|null;checklist?:ChecklistEntry[]}
export interface ProgressUpdate{id:number;itemId:number;authorId:number;text:string;percent:number|null;blocked:boolean;blockReason:string|null;nextStep:string|null;kind:string;createdAt:string}
export interface Comment{id:number;itemId:number;authorId:number;content:string;mentionIds:number[];createdAt:string}
export interface Activity{id:number;itemId:number;actorId:number;action:string;fromStatus:string|null;toStatus:string|null;detail:string|null;createdAt:string}
export interface Iteration{id:number;name:string;goal:string;startDate:string;endDate:string;status:string}
export interface BacklogEntry{task:Item;stories:Item[];storyCount:number;doneStoryCount:number}
export interface BatchSummary{batchId:number;teamId:number;creatorId:number;title:string;type:ItemType;total:number;doneCount:number;members:{userId:number;itemId:number;status:ItemStatus}[];createdAt:string}
export interface SavedFilter{id:number;name:string;criteria:Record<string,any>;createdAt:string}
export interface PlanningGroup{task:Item|null;items:Item[]}
export interface Planning{iteration:Iteration;groups:PlanningGroup[];loads:{userId:number;points:number;itemCount:number}[];capacities:{userId:number;availableDays:number;claimedPoints:number;remainingDays:number;capacityPoints:number;overCapacity:boolean}[]}
// M3 协同类型（契约 0.3.0）
export interface NotificationItem{id:number;teamId:number|null;type:string;category:string;itemId:number|null;itemCode:string|null;actorId:number|null;content:string;readAt:string|null;createdAt:string}
export interface NudgeResult{itemId:number;nudgedUserIds:number[];redirectedToLeader:boolean;nudgeCount:number}
export interface KeyDate{id:number;iterationId:number;title:string;date:string;category:string}
export interface BurndownPoint{date:string;remainingEstimate:number;remainingCount:number|null;doneCount:number|null;live?:boolean}
export interface Burndown{iteration:Iteration;points:BurndownPoint[];ideal:BurndownPoint[];totalEstimate:number|null}
export interface StandupMember{userId:number;memberStatus:string;yesterdayProgress:ProgressUpdate[];yesterdayDone:Item[];todayItems:Item[];blockedItems:Item[]}
export interface StandupData{date:string;previousWorkday:string;members:StandupMember[];note:string|null}
export interface RiskSignal{rule:string;severity:string;itemId:number|null;itemCode:string|null;userId:number|null;message:string}
export interface OverviewMember{userId:number;memberStatus:string;memberStatusStart:string|null;memberStatusEnd:string|null;inProgressItems:Item[];lastProgressAt:string|null;staleCount:number;remainingPoints:number;remainingCapacityPoints:number|null;free:boolean}
export interface OverviewData{members:OverviewMember[];canViewRisks:boolean;risks:RiskSignal[];iterationHealth:{iteration:Iteration;completionRate:number;remainingEstimate:number;idealRemainingEstimate:number;behind:boolean;blockedCount:number;staleCount:number}|null}
// 报告：kind 与后端 ReportService 对齐——STANDUP_SUMMARY 站会摘要 / WEEKLY 周报 /
// ITERATION_SUMMARY 迭代总结 / PERSONAL_WEEKLY 我的周报 / RISK_SUMMARY 风险摘要（旧前端曾把站会误写为 STANDUP，已纠正）。
// 正文契约字段为 contentMd（兼容旧字段 content）；editedByHuman 表示被人工改过；version 为乐观锁版本；existed 表示 generate 命中已有报告。
export interface ReportItem{allowedActions?:string[];id:number;teamId:number;kind:string;title:string;periodStart:string|null;periodEnd:string|null;ownerId:number|null;content:string;contentMd?:string;aiGenerated:boolean;edited:boolean;editedByHuman?:boolean;version?:number;deletedAt?:string|null;existed?:boolean;createdAt:string;updatedAt:string}
export interface ReportVersion{id:number;reportId:number;title:string;contentMd?:string;content?:string;editedByHuman:boolean;createdAt:string}
export type NotificationPrefs = Record<'ASSIGNMENT'|'NUDGE'|'MENTION'|'ITEM_UPDATE'|'REMINDER'|'SYSTEM', boolean>
export const NOTIFICATION_CATEGORY_META:Record<string,string>={ASSIGNMENT:'指派',NUDGE:'催办',MENTION:'提及',ITEM_UPDATE:'事项动态',REMINDER:'自动提醒',SYSTEM:'系统'}
// 分类色：任务蓝；故事按类别；缺陷粉；独立分析紫；临时橙。
export const TYPE_META:Record<ItemType,{label:string;color:string}>={TASK:{label:'任务',color:'#007AFF'},STORY:{label:'故事',color:'#30B0C7'},DEFECT:{label:'缺陷',color:'#FF2D55'},ANALYSIS:{label:'分析',color:'#AF52DE'},ADHOC:{label:'临时事项',color:'#FF9500'}}
export const STORY_CAT_META:Record<StoryCategory,{label:string;color:string}>={DEV:{label:'开发',color:'#30B0C7'},ANALYSIS:{label:'分析',color:'#AF52DE'},TEST:{label:'测试',color:'#00C7BE'},OTHER:{label:'其他',color:'#A2845E'}}
export function itemColor(it:Pick<Item,'type'|'storyCategory'>){ return it.type==='STORY' && it.storyCategory ? STORY_CAT_META[it.storyCategory].color : TYPE_META[it.type].color }
export const STATUS_META:Record<ItemStatus,{label:string}>={UNCLAIMED:{label:'待认领'},NOT_STARTED:{label:'待开始'},IN_PROGRESS:{label:'进行中'},IN_REVIEW:{label:'待验收'},DONE:{label:'已完成'},CANCELLED:{label:'已取消'}}
export const TASK_STATUS_META:Record<TaskStatus,{label:string}>={NOT_STARTED:{label:'未开始'},IN_PROGRESS:{label:'进行中'},PENDING_ACCEPTANCE:{label:'待验收'},DONE:{label:'已完成'},CANCELLED:{label:'已取消'}}
export const MEMBER_STATUS_META:Record<string,string>={NORMAL:'正常',LEAVE:'请假',TRAVEL:'出差',SECONDMENT:'外借'}
export function estimateUnitLabel(team:{estimateUnit?:string}|null|undefined){ const u=(team?.estimateUnit ?? '').toUpperCase(); return u==='DAY' ? '人天' : u==='HOUR' ? '小时' : '点' }

// M4（契约 0.5.0）
export interface TeamAiSettings{pasteImport:boolean;quickUpdate:boolean;splitStories:boolean;summarize:boolean;riskSummary:boolean;qa:boolean}
export const DEFAULT_TEAM_AI_SETTINGS:TeamAiSettings={pasteImport:true,quickUpdate:true,splitStories:true,summarize:true,riskSummary:true,qa:true}
// AiStatus.features 的键为契约连字符名（paste-import/quick-update/split-stories/summarize/risk-summary/qa）；teamSettings 为扁平 camel 布尔。
export interface AiStatus{platformEnabled:boolean;mode:string;available:boolean;visionAvailable:boolean;textModel:string|null;visionModel:string|null;features:Record<string,boolean>;teamSettings:TeamAiSettings}
export interface LabelItem{id:number;name:string;color:string;usageCount:number}
// 契约 PasteImportRow（解析预览行）+ 前端本地 selected。diff 为 {字段名:{from,to}} 对象。
export interface ImportRow{externalId:string|null;title:string;type?:string;description:string|null;priority:string;ownerName:string|null;ownerId:number|null;estimate:number|null;dueDate:string|null;parentExternalId:string|null;externalUrl?:string|null;sourceSnippet:string;confidence:number;matchStatus:'NEW'|'UPDATE'|'UNCHANGED';existingItemId:number|null;diff:Record<string,{from:any;to:any}>;selected:boolean}
export interface ImportBatch{id:number;teamId:number;creatorId:number;source:string;total:number;createdCount:number;updatedCount:number;createdItemIds:number[];updatedItemIds:number[];skipped:number;undoneAt:string|null;createdAt:string;link:string}
// 契约 QuickAction：解析返回与确认执行共用同一形状（kind=UPDATE_ITEM/CREATE_ITEM）。
export interface QuickAction{kind:'UPDATE_ITEM'|'CREATE_ITEM';itemId:number|null;itemCode:string|null;text:string|null;percent:number|null;submitReview:boolean;blocked:boolean;blockReason:string|null;needsBlockConfirm:boolean;candidateItemIds:number[];type:string|null;title:string|null;ownerName:string|null;ownerId:number|null;dueDate:string|null}
// 契约 IntegrationAdapter：config 为 HTTP 配置对象（url/method/listPath/fields/authType/authToken/authUser/authPassword 等），密钥回显脱敏为 ***。
export interface AdapterItem{id:number;teamId:number;name:string;type:string;systemName:string;config:Record<string,any>;enabled:boolean;consecutiveFailures:number;createdBy:number;createdAt:string;updatedAt:string}
export interface SyncLog{id:number;adapterId:number;teamId:number;createdCount:number;updatedCount:number;failedCount:number;message:string|null;success:boolean;createdAt:string}
// 契约 AiCallLog：结果以 success/error 口径（无 result 字段）。
export interface AiLog{id:number;feature:string;model:string|null;durationMs:number;promptTokens:number|null;completionTokens:number|null;success:boolean;error:string|null;createdAt:string}
// 契约 QaRecord：refs 为事项编号字符串数组；问答即时响应为 QaAnswer{recordId,found,answer,refs}。
export interface QaRecord{id:number;question:string;answer:string;found:boolean;refs:string[];createdAt:string}
export interface QaAnswer{recordId:number;found:boolean;answer:string;refs:string[]}
export const AI_FEATURE_LABELS:Record<string,string>={pasteImport:'粘贴导入',quickUpdate:'一句话更新',splitStories:'AI 拆故事',summarize:'自动汇总',riskSummary:'风险摘要',qa:'智能问答'}
