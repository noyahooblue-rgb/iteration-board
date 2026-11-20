package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
* 状态流转的唯一实现（设计 §8.2）：看板拖拽、详情页按钮、一句话更新、导入都调用此处。
* 规则唯一来源 contract/state-machine.md。
* 校验顺序：版本(409) → 流转合法性(422) → 权限(403) → 原因必填(400)。
* M2：负责人改为 item_owner 多名共同负责；新增 DECLINE；TASK 任务只允许 PO 的 APPROVE/CANCEL。
*/
@Service
public class ItemTransitionService {
private final ItemRepo itemRepo;
private final ActivityRepo activityRepo;
private final TeamMemberRepo memberRepo;
private final UserRepo userRepo;
private final ItemOwnerRepo ownerRepo;
private final ProgressRepo progressRepo;
private final NotifyService notifyService;
private final TaskDerivationService derivationService;
private final TeamRepo teamRepo;

public ItemTransitionService(ItemRepo itemRepo, ActivityRepo activityRepo,
TeamMemberRepo memberRepo, UserRepo userRepo,
ItemOwnerRepo ownerRepo, ProgressRepo progressRepo,
NotifyService notifyService, TaskDerivationService derivationService,
TeamRepo teamRepo) {
this.itemRepo = itemRepo;
this.activityRepo = activityRepo;
this.memberRepo = memberRepo;
this.userRepo = userRepo;
this.ownerRepo = ownerRepo;
this.progressRepo = progressRepo;
this.notifyService = notifyService;
this.derivationService = derivationService;
this.teamRepo = teamRepo;
}

/** M3 附加入参：TRANSFER 的被替换者；任务 REJECT 的重开故事与补充故事。 */
public record NewStoryInput(String title, StoryCategory storyCategory) {}
public record Extras(Long replaceUserId, List<Long> reopenItemIds, List<NewStoryInput> newStories) {}

public enum Action { CLAIM, START, SUBMIT, APPROVE, REJECT, RELEASE, TRANSFER, DECLINE, CANCEL, REOPEN}

private boolean isLeaderOrPo(Long teamId, AppUser u) {
return memberRepo.findByTeamIdAndUserId(teamId, u.getId())
.map(m -> m.getRole() == Role.LEADER || m.getRole() == Role.PO).orElse(false);
}
private boolean isLeader(Long teamId, AppUser u) {
return memberRepo.findByTeamIdAndUserId(teamId, u.getId())
.map(m -> m.getRole() == Role.LEADER).orElse(false);
}
private static boolean eq(AppUser a, AppUser b) { return a!= null && b!= null && a.getId().equals(b.getId());}

public boolean isOwner(Item item, AppUser u) {
return u!= null && ownerRepo.existsByItemIdAndUserId(item.getId(), u.getId());
}
public List<Long> ownerIds(Item item) {
return ownerRepo.findByItemId(item.getId()).stream().map(o -> o.getUser().getId()).toList();
}
/** 替换全部负责人（CLAIM、ASSIGN、PATCH 改负责人、TRANSFER 内部组装共用）。 */
public void setOwners(Item item, List<AppUser> owners) {
ownerRepo.deleteByItemId(item.getId());
ownerRepo.flush();
for (AppUser o: owners) ownerRepo.save(new ItemOwner(item, o));
}
public void clearOwners(Item item) { ownerRepo.deleteByItemId(item.getId());}

private ApiException illegal(Item item, Action action) {
return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION",
"当前状态 " + item.getStatus() + " 不允许执行 " + action);
}

private void requireReason(String reason) {
if (reason == null || reason.isBlank()) {
throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "该操作必须填写原因");
}
}

@Transactional
public Item transition(Item item, AppUser actor, Action action, int version, String reason, Long newAssigneeId) {
return transition(item, actor, action, version, reason, newAssigneeId, null);
}

@Transactional
public Item transition(Item item, AppUser actor, Action action, int version, String reason, Long newAssigneeId,
Extras extras) {
// Q13：已认领再认领（本人已是负责人）→ 返回当前状态，不报错（动作本身幂等安全）
if (action == Action.CLAIM && item.getType() != ItemType.TASK
        && item.getStatus() != ItemStatus.UNCLAIMED && isOwner(item, actor)) {
return item;
}
// 版本冲突优先：并发认领落败方在此收到 409 并提示已被认领
if (version!= item.getVersion()) {
if (action == Action.CLAIM &&!ownerIds(item).isEmpty() && item.getStatus()!= ItemStatus.UNCLAIMED) {
throw ApiException.versionConflict("该事项已被他人认领");
}
throw ApiException.versionConflict("事项已被他人修改，请刷新后重试");
}
ItemStatus from = item.getStatus();
Long teamId = item.getTeam().getId();

// TASK 任务：不参与普通流转；仅 PO 的 APPROVE（待验收→已完成）与 CANCEL（取消任务）。
if (item.getType() == ItemType.TASK) {
boolean isPo = eq(item.getPo(), actor)
|| memberRepo.findByTeamIdAndUserId(teamId, actor.getId()).map(m -> m.getRole() == Role.PO).orElse(false);
switch (action) {
case APPROVE -> {
if (from!= ItemStatus.IN_REVIEW) throw illegal(item, action);
if (!isPo) throw ApiException.forbidden("仅 PO 可验收通过任务");
item.setStatus(ItemStatus.DONE);
item.setProgressPercent(100);
notifyService.notify(item.getCreator(), "APPROVE", item, actor, "任务已验收通过");
}
case CANCEL -> {
if (from == ItemStatus.DONE || from == ItemStatus.CANCELLED) throw illegal(item, action);
if (!isPo) throw ApiException.forbidden("仅 PO 可取消任务");
requireReason(reason);
item.setStatus(ItemStatus.CANCELLED);
// 任务被取消 → 通知创建人（与普通事项 CANCEL 口径一致；操作者本人由 NotifyService 自动跳过）
notifyService.notify(item.getCreator(), "CANCEL", item, actor, "任务已被取消：" + reason);
}
case REJECT -> {
// 任务打回（M3，验收批复待问 1 定稿）：必须说明原因，且必须同时重开已完成故事或新建补充故事，
// 不允许「没有任何故事可做」的打回；任务状态由故事推算回到进行中。
if (from != ItemStatus.IN_REVIEW) throw illegal(item, action);
if (!isPo) throw ApiException.forbidden("仅 PO 可打回任务");
requireReason(reason);
List<Long> reopenIds = extras == null || extras.reopenItemIds() == null ? List.of() : extras.reopenItemIds();
List<NewStoryInput> newStories = extras == null || extras.newStories() == null ? List.of() : extras.newStories();
if (reopenIds.isEmpty() && newStories.isEmpty()) {
throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_ACTIONABLE_STORY",
"打回必须同时选择要重开的已完成故事，或新建补充故事");
}
List<Item> children = itemRepo.findByParentId(item.getId()).stream()
.filter(s -> s.getDeletedAt() == null).toList();
for (Long sid : reopenIds) {
Item story = children.stream().filter(s -> s.getId().equals(sid)).findFirst()
.orElseThrow(() -> ApiException.validation("重开的故事不属于该任务：" + sid));
if (story.getStatus() != ItemStatus.DONE) {
throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION",
"只能重开已完成的故事：" + story.getCode());
}
story.setStatus(ItemStatus.IN_PROGRESS);
story.setStatusChangedAt(Instant.now());
itemRepo.save(story);
activityRepo.save(new Activity(story, actor, "REOPEN", ItemStatus.DONE.name(), ItemStatus.IN_PROGRESS.name(), reason));
for (ItemOwner o : ownerRepo.findByItemId(story.getId())) {
notifyService.notify(o.getUser(), "REJECT", story, actor, "任务被打回，该故事已重开：" + reason);
}
}
int nextSort = children.stream().mapToInt(Item::getSortOrder).max().orElse(-1) + 1;
for (NewStoryInput ns : newStories) {
if (ns.title() == null || ns.title().isBlank() || ns.title().length() > 200) {
throw ApiException.validation("补充故事标题不能为空且不超过 200 字");
}
if (ns.storyCategory() == null) throw ApiException.validation("补充故事必须带类别 storyCategory");
Team team = teamRepo.findByIdForUpdate(teamId).orElseThrow(() -> ApiException.notFound("小组不存在"));
long seq = team.getItemSeq() + 1;
team.setItemSeq(seq);
teamRepo.save(team);
Item story = new Item();
story.setTeam(team);
story.setSeq(seq);
story.setCode(team.getCode() + "-" + seq);
story.setType(ItemType.STORY);
story.setTitle(ns.title());
story.setParent(item);
story.setStoryCategory(ns.storyCategory());
story.setCreator(actor);
story.setAcceptor(item.getPo() != null ? item.getPo() : actor);
story.setStatus(ItemStatus.UNCLAIMED);
story.setSortOrder(nextSort++);
itemRepo.save(story);
activityRepo.save(new Activity(story, actor, "CREATE", null, ItemStatus.UNCLAIMED.name(), "任务打回补充故事"));
}
// 任务状态随故事推算回到进行中（至少一个故事未完成时推算结果即 IN_PROGRESS）
derivationService.recalc(item);
}
default -> throw illegal(item, action);
}
item.setStatusChangedAt(Instant.now());
itemRepo.save(item);
activityRepo.save(new Activity(item, actor, action.name(), from.name(), item.getStatus().name(), reason));
return item;
}

switch (action) {
case CLAIM -> {
if (from!= ItemStatus.UNCLAIMED) throw illegal(item, action);
setOwners(item, List.of(actor));
item.setAssignedDirectly(false);
item.setStatus(ItemStatus.NOT_STARTED);
// 发布的事项被认领 → 通知创建人（设计 §3.7，此前缺失）
notifyService.notify(item.getCreator(), "CLAIM", item, actor, "你发布的事项已被认领");
}
case START -> {
if (from!= ItemStatus.NOT_STARTED) throw illegal(item, action);
if (!isOwner(item, actor)) throw ApiException.forbidden("仅负责人可开始事项");
item.setStatus(ItemStatus.IN_PROGRESS);
}
case SUBMIT -> {
if (from!= ItemStatus.IN_PROGRESS) throw illegal(item, action);
if (!isOwner(item, actor)) throw ApiException.forbidden("仅负责人可提交验收");
// 验收人就是提交人本人时直接完成
item.setStatus(eq(item.getAcceptor(), actor)? ItemStatus.DONE: ItemStatus.IN_REVIEW);
if (item.getStatus() == ItemStatus.IN_REVIEW) {
notifyService.notify(item.getAcceptor(), "SUBMIT", item, actor, "事项已提交验收，请处理");
}
}
case APPROVE -> {
if (from!= ItemStatus.IN_REVIEW) throw illegal(item, action);
if (!eq(item.getAcceptor(), actor) &&!isLeader(teamId, actor)) throw ApiException.forbidden("仅验收人或组长可通过验收");
item.setStatus(ItemStatus.DONE);
for (ItemOwner o : ownerRepo.findByItemId(item.getId())) {
notifyService.notify(o.getUser(), "APPROVE", item, actor, "事项已验收通过");
}
}
case REJECT -> {
if (from!= ItemStatus.IN_REVIEW) throw illegal(item, action);
if (!eq(item.getAcceptor(), actor) &&!isLeader(teamId, actor)) throw ApiException.forbidden("仅验收人或组长可打回");
requireReason(reason);
item.setStatus(ItemStatus.IN_PROGRESS);
for (ItemOwner o : ownerRepo.findByItemId(item.getId())) {
notifyService.notify(o.getUser(), "REJECT", item, actor, "事项被打回：" + reason);
}
}
case RELEASE -> {
if (from!= ItemStatus.NOT_STARTED && from!= ItemStatus.IN_PROGRESS) throw illegal(item, action);
if (!isOwner(item, actor)) throw ApiException.forbidden("仅负责人可释放事项");
requireReason(reason);
clearOwners(item);
item.setAssignedDirectly(false);
item.setStatus(ItemStatus.UNCLAIMED);
notifyService.notify(item.getCreator(), "RELEASE", item, actor, "事项被释放：" + reason);
}
case DECLINE -> {
if (from!= ItemStatus.NOT_STARTED) throw illegal(item, action);
if (!item.isAssignedDirectly() ||!isOwner(item, actor)) {
throw ApiException.forbidden("仅被直接指派的负责人可婉拒");
}
if (progressRepo.existsByItemId(item.getId())) {
throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION",
"已登记过进展的事项不能婉拒，请走释放");
}
requireReason(reason);
clearOwners(item);
item.setAssignedDirectly(false);
item.setStatus(ItemStatus.UNCLAIMED);
notifyService.notify(item.getCreator(), "DECLINE", item, actor, "事项被婉拒：" + reason);
}
case TRANSFER -> {
if (from == ItemStatus.DONE || from == ItemStatus.CANCELLED) throw illegal(item, action);
boolean self = eq(item.getCreator(), actor) || isOwner(item, actor);
if (!self &&!isLeaderOrPo(teamId, actor)) throw ApiException.forbidden("仅创建人、负责人、PO 或组长可转交");
if (newAssigneeId == null) throw ApiException.validation("转交必须指定新负责人 newAssigneeId");
AppUser target = userRepo.findById(newAssigneeId)
.filter(u -> memberRepo.findByTeamIdAndUserId(teamId, u.getId()).isPresent())
.orElseThrow(() -> ApiException.validation("新负责人必须是小组成员"));
List<AppUser> oldOwners = ownerRepo.findByItemId(item.getId()).stream().map(ItemOwner::getUser).toList();
if (from == ItemStatus.UNCLAIMED || oldOwners.isEmpty()) {
// 待认领事项的转交视同指派（定稿）：设定负责人并进入待开始
setOwners(item, List.of(target));
item.setAssignedDirectly(true);
item.setStatus(ItemStatus.NOT_STARTED);
notifyService.notify(target, "ASSIGN", item, actor, "事项已指派给你");
} else {
// 共同负责口径（M3 定稿，验收批复待问 2）：只替换一位负责人，其余共同负责人保留。
// 被替换者：replaceUserId 显式指定 > 发起人本人 > 单负责人时的唯一负责人；多负责人且无法确定时 400。
Long replaceId = extras == null ? null : extras.replaceUserId();
AppUser replaced;
if (replaceId != null) {
replaced = oldOwners.stream().filter(o -> o.getId().equals(replaceId)).findFirst()
.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "REPLACE_NOT_OWNER", "被替换者不是该事项的负责人"));
} else if (isOwner(item, actor)) {
replaced = actor;
} else if (oldOwners.size() == 1) {
replaced = oldOwners.get(0);
} else {
throw new ApiException(HttpStatus.BAD_REQUEST, "REPLACE_REQUIRED",
"该事项有多名共同负责人，请用 replaceUserId 指定要替换的负责人");
}
if (oldOwners.stream().anyMatch(o -> o.getId().equals(target.getId()) && !o.getId().equals(replaced.getId()))) {
throw new ApiException(HttpStatus.CONFLICT, "ALREADY_OWNER", "新负责人已是该事项的共同负责人");
}
List<AppUser> next = new java.util.ArrayList<>();
for (AppUser o : oldOwners) next.add(o.getId().equals(replaced.getId()) ? target : o);
setOwners(item, next);
notifyService.notify(replaced, "TRANSFER", item, actor, "事项已转交他人");
notifyService.notify(target, "TRANSFER", item, actor, "事项已转交给你");
}
}
case CANCEL -> {
if (from == ItemStatus.DONE || from == ItemStatus.CANCELLED) throw illegal(item, action);
boolean notStarted = from == ItemStatus.UNCLAIMED || from == ItemStatus.NOT_STARTED;
boolean allowed = (eq(item.getCreator(), actor) && notStarted) || isLeaderOrPo(teamId, actor);
if (!allowed) throw ApiException.forbidden("仅创建人（尚未开始时）或 PO/组长可取消事项");
requireReason(reason);
item.setStatus(ItemStatus.CANCELLED);
// 事项被取消 → 通知负责人与创建人（设计 §3.7，此前缺失；操作者本人由 NotifyService 自动跳过）
for (ItemOwner o : ownerRepo.findByItemId(item.getId())) {
notifyService.notify(o.getUser(), "CANCEL", item, actor, "事项已被取消：" + reason);
}
notifyService.notify(item.getCreator(), "CANCEL", item, actor, "事项已被取消：" + reason);
}
case REOPEN -> {
if (from!= ItemStatus.DONE && from!= ItemStatus.CANCELLED) throw illegal(item, action);
boolean allowed = eq(item.getCreator(), actor) || eq(item.getAcceptor(), actor) || isLeaderOrPo(teamId, actor);
if (!allowed) throw ApiException.forbidden("仅创建人、验收人、PO 或组长可重新打开");
item.setStatus(!ownerIds(item).isEmpty()? ItemStatus.IN_PROGRESS: ItemStatus.UNCLAIMED);
}
}
if (item.getStatus()!= from) item.setStatusChangedAt(Instant.now());
itemRepo.save(item);
activityRepo.save(new Activity(item, actor, action.name(),
from.name(), item.getStatus().name(), reason));
// 故事状态变化后重算父任务
if (item.getParent()!= null) derivationService.recalc(item.getParent());
return item;
}
}
