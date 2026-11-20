// 模拟后端类型：与 contract/openapi.yaml 0.5.0 对齐（修订一新模型）。
export type Role = 'LEADER' | 'PO' | 'MEMBER'
export type ItemType = 'TASK' | 'STORY' | 'DEFECT' | 'ANALYSIS' | 'ADHOC'
export type StoryCategory = 'DEV' | 'ANALYSIS' | 'TEST' | 'OTHER'
export type ItemStatus = 'UNCLAIMED' | 'NOT_STARTED' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE' | 'CANCELLED'
export type TaskStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PENDING_ACCEPTANCE' | 'DONE' | 'CANCELLED'
export interface User { id:number; loginName:string; displayName:string; avatarColor:string; platformAdmin:boolean; mustChangePassword:boolean }
export interface Team { id:number; name:string; code:string; inviteCode?:string; joinApproval:boolean; iterationWeeks:number; estimateUnit:string; themeColor:string; status:string; myRole?:Role; description?:string|null; settings?:any }
export interface Member { userId:number; displayName:string; loginName:string; avatarColor:string; role:Role; memberStatus:string }
export interface ChecklistEntry { id:number; itemId:number; text:string; done:boolean; sortOrder:number }
export interface Item {
  id:number; code:string; type:ItemType; title:string; description:string; status:ItemStatus; priority:string;
  creatorId:number; ownerIds:number[]; acceptorId:number|null; storyCategory:StoryCategory|null; poId:number|null;
  batchId:number|null; assignedDirectly:boolean; sortOrder:number; taskStatus:TaskStatus|null;
  collaboratorIds:number[]; inviteeIds:number[];
  parentId:number|null; iterationId:number|null; dueDate:string|null; estimate:number|null;
  progressPercent:number; blocked:boolean; blockReason:string|null; nudgeCount:number; labels:string[];
  childCount:number; childDoneCount:number; commentCount:number; version:number;
  createdAt:string; updatedAt:string; lastProgressAt:string|null; deletedAt:string|null;
  checklist: ChecklistEntry[]
}
export interface ProgressUpdate { id:number; itemId:number; authorId:number; text:string; percent:number|null; blocked:boolean; blockReason:string|null; nextStep:string|null; kind:string; createdAt:string; mentionIds?:number[] }
export interface Comment { id:number; itemId:number; authorId:number; content:string; mentionIds:number[]; createdAt:string }
export interface Activity { id:number; itemId:number; actorId:number; action:string; fromStatus:string|null; toStatus:string|null; detail:string|null; createdAt:string }
export interface Iteration { id:number; teamId:number; name:string; goal:string; startDate:string; endDate:string; status:string }
export interface Batch { id:number; teamId:number; creatorId:number; title:string; type:ItemType; createdAt:string }
export interface Nudge { id:number; itemId:number; fromUserId:number; toUserId:number; date:string; createdAt:string }
export interface SavedFilter { id:number; userId:number; teamId:number; name:string; criteria:any; createdAt:string }
export interface Notification { id:number; userId:number; teamId:number|null; type:string; category:string; itemId:number|null; itemCode:string|null; actorId:number|null; content:string; readAt:string|null; createdAt:string }
export interface KeyDate { id:number; teamId:number; iterationId:number; title:string; date:string; category:string }
export interface MockReportVersion { id:number; reportId:number; title:string; contentMd:string; editedByHuman:boolean; createdAt:string }
export interface MockReport { id:number; teamId:number; kind:string; title:string; periodStart:string|null; periodEnd:string|null; ownerId:number|null; content:string; aiGenerated:boolean; edited:boolean; editedByHuman:boolean; version:number; deletedAt:string|null; lastRegeneratedAt:string|null; versions:MockReportVersion[]; createdAt:string; updatedAt:string }
export class MockError extends Error { constructor(public status:number, public code:string, message:string, public detail:any=null){ super(message) } }
