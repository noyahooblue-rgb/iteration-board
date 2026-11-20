package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ItemService {
    private final ItemRepo itemRepo;
    private final TeamRepo teamRepo;
    private final TeamMemberRepo memberRepo;
    private final UserRepo userRepo;
    private final CollaboratorRepo collaboratorRepo;
    private final InviteeRepo inviteeRepo;
    private final LabelRepo labelRepo;
    private final ItemLabelRepo itemLabelRepo;
    private final ProgressRepo progressRepo;
    private final CommentRepo commentRepo;
    private final ActivityRepo activityRepo;
    private final IterationRepo iterationRepo;
    private final ChecklistRepo checklistRepo;
    private final ItemOwnerRepo ownerRepo;
    private final WatcherRepo watcherRepo;
    private final TeamService teamService;
    private final ItemTransitionService transitionService;
    private final TaskDerivationService derivationService;
    private final NotifyService notifyService;
    private final com.iterationboard.common.AuditService auditService;

    public ItemService(ItemRepo itemRepo, TeamRepo teamRepo,
                       TeamMemberRepo memberRepo, UserRepo userRepo,
                       CollaboratorRepo collaboratorRepo, InviteeRepo inviteeRepo,
                       LabelRepo labelRepo, ItemLabelRepo itemLabelRepo,
                       ProgressRepo progressRepo, CommentRepo commentRepo,
                       ActivityRepo activityRepo, IterationRepo iterationRepo,
                       ChecklistRepo checklistRepo, ItemOwnerRepo ownerRepo, WatcherRepo watcherRepo,
                       TeamService teamService, ItemTransitionService transitionService,
                       TaskDerivationService derivationService, NotifyService notifyService,
                       com.iterationboard.common.AuditService auditService) {
        this.auditService = auditService;
        this.itemRepo = itemRepo; this.teamRepo = teamRepo; this.memberRepo = memberRepo; this.userRepo = userRepo;
        this.collaboratorRepo = collaboratorRepo; this.inviteeRepo = inviteeRepo; this.labelRepo = labelRepo;
        this.itemLabelRepo = itemLabelRepo; this.progressRepo = progressRepo; this.commentRepo = commentRepo;
        this.activityRepo = activityRepo; this.iterationRepo = iterationRepo; this.checklistRepo = checklistRepo;
        this.ownerRepo = ownerRepo; this.watcherRepo = watcherRepo;
        this.teamService = teamService; this.transitionService = transitionService;
        this.derivationService = derivationService; this.notifyService = notifyService;
    }

    @Transactional(readOnly = true)
    public Item get(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        return requireItem(teamId, itemId);
    }

    public Item requireItem(Long teamId, Long itemId) {
        return itemRepo.findByIdAndTeamId(itemId, teamId)
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("事项不存在"));
    }

    public Item requireItemIncludingDeleted(Long teamId, Long itemId) {
        return itemRepo.findByIdAndTeamId(itemId, teamId)
                .orElseThrow(() -> ApiException.notFound("事项不存在"));
    }

    /**
     * 可排期迭代（契约 0.5.3，M5 验收结论 1）：只有「规划中」或「进行中」的迭代能放入事项；
     * 已结束的迭代不可再排期。迭代不存在或不属于本组时按校验错误处理（与原口径一致）。
     */
    public Iteration requirePlannableIteration(Long teamId, Long iterationId) {
        Iteration it = iterationRepo.findById(iterationId)
                .filter(i -> i.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.validation("迭代不存在"));
        if (!"PLANNING".equals(it.getStatus()) && !"ACTIVE".equals(it.getStatus())) {
            throw ApiException.validation("已结束的迭代不能再排入事项");
        }
        return it;
    }

    private AppUser requireTeamUser(Long teamId, Long userId, String what) {
        return userRepo.findById(userId)
                .filter(u -> memberRepo.findByTeamIdAndUserId(teamId, u.getId()).isPresent())
                .orElseThrow(() -> ApiException.validation(what + "必须是小组成员"));
    }

    public record CreateItemCmd(ItemType type, String title, String description, Priority priority,
                                String destination, List<Long> ownerIds, List<Long> inviteeIds, Long acceptorId,
                                LocalDate dueDate, BigDecimal estimate, Long parentId, Long iterationId,
                                List<String> labelNames, StoryCategory storyCategory, Long poId) {}

    @Transactional
    public Item create(Long teamId, AppUser creator, CreateItemCmd cmd) {
        TeamMember member = teamService.requireMember(teamId, creator);
        if (cmd.type() == null) throw ApiException.validation("事项类型不能为空");
        // 修订四 P06：TASK 与 STORY 由组长和 PO 都可创建（修订一「仅 PO」放宽）
        if ((cmd.type() == ItemType.TASK || cmd.type() == ItemType.STORY)
                && member.getRole() != Role.PO && member.getRole() != Role.LEADER) {
            throw ApiException.forbidden("只有组长或 PO 可以创建" + (cmd.type() == ItemType.TASK ? "任务" : "故事"));
        }
        if (cmd.title() == null || cmd.title().isBlank() || cmd.title().length() > 200) throw ApiException.validation("标题不能为空且不超过 200 字");
        String dest = cmd.destination() == null ? "POOL" : cmd.destination();
        Team team = teamRepo.findByIdForUpdate(teamId).orElseThrow(() -> ApiException.notFound("小组不存在"));
        long seq = team.getItemSeq() + 1;
        team.setItemSeq(seq);
        teamRepo.save(team);

        Item item = new Item();
        item.setTeam(team);
        item.setSeq(seq);
        item.setCode(team.getCode() + "-" + seq);
        item.setType(cmd.type());
        item.setTitle(cmd.title());
        item.setDescription(cmd.description());
        if (cmd.priority() != null) item.setPriority(cmd.priority());
        item.setCreator(creator);
        item.setDueDate(cmd.dueDate());
        item.setEstimate(cmd.estimate());

        Item parent = null;
        if (cmd.type() == ItemType.STORY) {
            if (cmd.parentId() == null) throw ApiException.validation("故事必须挂在任务下（parentId 必填）");
            parent = requireItem(teamId, cmd.parentId());
            if (parent.getType() != ItemType.TASK) throw ApiException.validation("故事的父事项必须是任务（父子只允许 STORY→TASK）");
            if (cmd.storyCategory() == null) throw ApiException.validation("故事必须带类别 storyCategory");
            item.setParent(parent);
            item.setStoryCategory(cmd.storyCategory());
            int nextSort = itemRepo.findByParentId(parent.getId()).stream()
                    .mapToInt(Item::getSortOrder).max().orElse(-1) + 1;
            item.setSortOrder(nextSort);
        } else if (cmd.parentId() != null) {
            throw ApiException.validation("只有故事可以挂父任务（父子只允许 STORY→TASK）");
        }

        if (cmd.type() == ItemType.TASK) {
            if (cmd.iterationId() != null) throw ApiException.validation("任务没有迭代归属，不能指定 iterationId");
            item.setPo(cmd.poId() != null ? requireTeamUser(teamId, cmd.poId(), "所属 PO") : creator);
            item.setStatus(ItemStatus.NOT_STARTED);
            item.setProgressPercent(0);
        } else {
            if (cmd.iterationId() != null) {
                item.setIteration(requirePlannableIteration(teamId, cmd.iterationId()));
            }
            // P17：废弃「故事默认验收人=创建人」——故事未指定验收人时按小组设置「故事默认验收角色」
            // （默认 PO）取该角色成员；独立事项维持未指定时为创建人。
            if (cmd.acceptorId() != null) {
                item.setAcceptor(requireTeamUser(teamId, cmd.acceptorId(), "验收人"));
            } else if (cmd.type() == ItemType.STORY) {
                item.setAcceptor(resolveDefaultAcceptor(teamId, creator,
                        com.iterationboard.team.TeamSettings.from(teamRepo.findById(teamId).orElse(null)).storyAcceptorRole));
            } else {
                item.setAcceptor(creator);
            }
            switch (dest) {
                case "POOL", "INVITE" -> item.setStatus(ItemStatus.UNCLAIMED);
                case "ASSIGN" -> item.setStatus(ItemStatus.NOT_STARTED);
                default -> throw ApiException.validation("destination 须为 POOL / INVITE / ASSIGN");
            }
        }
        itemRepo.save(item);
        auditService.log(teamId, creator, "CREATE_ITEM", "ITEM", item.getId(),
                item.getCode() + " " + item.getTitle());

        if (cmd.type() != ItemType.TASK && "ASSIGN".equals(dest)) {
            List<Long> owners = cmd.ownerIds() == null ? List.of() : cmd.ownerIds();
            if (owners.isEmpty()) throw ApiException.validation("直接指派时 ownerIds 必填");
            List<AppUser> assigned = owners.stream()
                    .map(uid -> requireTeamUser(teamId, uid, "负责人")).toList();
            transitionService.setOwners(item, assigned);
            for (AppUser o : assigned) {
                notifyService.notify(o, "ASSIGN", item, creator, "你被设为事项负责人");
            }
            item.setAssignedDirectly(true);
            itemRepo.save(item);
        }
        if ("INVITE".equals(dest) && cmd.inviteeIds() != null) {
            for (Long uid : cmd.inviteeIds()) {
                AppUser invitee = requireTeamUser(teamId, uid, "被邀请人");
                inviteeRepo.save(new ItemInvitee(item, invitee));
                // 1.0.4 修复：邀请认领必须发通知（此前只落 invitee 记录，被邀请人铃铛无任何提示）
                notifyService.notify(invitee, "INVITE", item, creator,
                        "你被邀请认领事项「" + item.getTitle() + "」，请到认领池处理");
            }
        }
        // 创建时设定的验收人通知（默认验收人=创建人本人时，由 NotifyService「不给自己发」规则自动跳过）
        if (cmd.type() != ItemType.TASK && item.getAcceptor() != null) {
            notifyService.notify(item.getAcceptor(), "ACCEPTOR", item, creator, "你被设为事项验收人");
        }
        if (cmd.labelNames() != null) {
            for (String name : cmd.labelNames()) {
                Label label = labelRepo.findByTeamIdAndName(teamId, name).orElseGet(() -> {
                    Label l = new Label();
                    l.setTeam(team);
                    l.setName(name);
                    return labelRepo.save(l);
                });
                itemLabelRepo.save(new ItemLabel(item, label));
            }
        }
        activityRepo.save(new Activity(item, creator, "CREATE", null, item.getStatus().name(), null));
        if (parent != null) derivationService.recalc(parent);
        return item;
    }

    @Transactional(readOnly = true)
    public List<Item> list(Long teamId, AppUser user, ItemStatus status, Long ownerId, ItemType type,
                           Long iterationId, String scope) {
        teamService.requireMember(teamId, user);
        Long currentIterationId = null;
        if ("current".equals(scope)) {
            currentIterationId = iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE")
                    .map(Iteration::getId).orElse(-1L);
        }
        Long cur = currentIterationId;
        java.util.Set<Long> ownedIds = ownerId == null ? null : new java.util.HashSet<>(
                ownerRepo.findByUserId(ownerId).stream().map(o -> o.getItem().getId()).toList());
        return itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> status == null || i.getStatus() == status)
                .filter(i -> ownedIds == null || ownedIds.contains(i.getId()))
                .filter(i -> type == null || i.getType() == type)
                .filter(i -> iterationId == null || (i.getIteration() != null && i.getIteration().getId().equals(iterationId)))
                .filter(i -> {
                    if ("daily".equals(scope)) return i.getIteration() == null;
                    if ("current".equals(scope)) return i.getIteration() != null && i.getIteration().getId().equals(cur);
                    return true;
                })
                .toList();
    }

    /** P17 默认验收人解析：取指定角色的第一位成员；无该角色时依次回落组长、PO；再无则创建人。 */
    private AppUser resolveDefaultAcceptor(Long teamId, AppUser creator, String roleName) {
        List<TeamMember> members = memberRepo.findByTeamId(teamId);
        for (String role : new String[]{roleName, "LEADER", "PO"}) {
            for (TeamMember m : members) {
                if (m.getRole().name().equals(role)) return m.getUser();
            }
        }
        return creator;
    }

    public record PatchCmd(int version, String title, String description, Priority priority, Long acceptorId,
                           boolean acceptorPresent, LocalDate dueDate, boolean dueDatePresent,
                           BigDecimal estimate, boolean estimatePresent,
                           Long iterationId, boolean iterationPresent, Long parentId, boolean parentPresent,
                           StoryCategory storyCategory, Long poId, boolean poPresent,
                           List<Long> ownerIds, boolean ownerIdsPresent,
                           List<String> labelNames, boolean labelNamesPresent) {}

    @Transactional
    public Item patch(Long teamId, Long itemId, AppUser user, PatchCmd cmd) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (!canEdit(member, item, user)) throw ApiException.forbidden("仅创建人、负责人、PO 或组长可编辑事项");
        // ：故事 PATCH——PO/组长可全改；负责人（非 PO/组长）只能改 description，其余字段 403。
        if (item.getType() == ItemType.STORY && member.getRole() != Role.LEADER && member.getRole() != Role.PO) {
            if (!transitionService.isOwner(item, user)) {
                throw ApiException.forbidden("故事仅 PO、组长或负责人可编辑");
            }
            boolean touchesOther = cmd.title() != null || cmd.priority() != null || cmd.storyCategory() != null
                    || cmd.acceptorPresent() || cmd.dueDatePresent() || cmd.estimatePresent()
                    || cmd.iterationPresent() || cmd.parentPresent() || cmd.poPresent()
                    || cmd.ownerIdsPresent() || cmd.labelNamesPresent();
            if (touchesOther) throw ApiException.forbidden("故事负责人只能修改描述（description）");
        }
        // Q3：规划成员增减（把事项放进/移出迭代）保持组长+PO，普通成员即使可编辑事项也不能改迭代归属
        if (cmd.iterationPresent()) {
            Long cur = item.getIteration() == null ? null : item.getIteration().getId();
            if (!java.util.Objects.equals(cur, cmd.iterationId())
                    && member.getRole() != Role.LEADER && member.getRole() != Role.PO) {
                throw ApiException.forbidden("仅组长或 PO 可把事项放进/移出迭代（规划）");
            }
        }
        if (cmd.version() != item.getVersion()) throw ApiException.versionConflict("事项已被他人修改，请刷新后重试");
        ItemStatus st = item.getStatus();
        // 修订五规则 5：已完成/已取消的事项只能改描述类字段；改其他字段须先「重新打开」（REOPEN 本身留痕）。
        if (st == ItemStatus.DONE || st == ItemStatus.CANCELLED) {
            boolean touchesNonDesc = cmd.title() != null || cmd.priority() != null || cmd.storyCategory() != null
                    || cmd.acceptorPresent() || cmd.dueDatePresent() || cmd.estimatePresent()
                    || cmd.iterationPresent() || cmd.parentPresent() || cmd.poPresent()
                    || cmd.ownerIdsPresent() || cmd.labelNamesPresent();
            if (touchesNonDesc) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DONE_ITEM_LOCKED",
                        "已完成/已取消的事项只能修改描述，其他修改请先重新打开事项");
            }
        }
        if (cmd.title() != null) item.setTitle(cmd.title());
        if (cmd.description() != null) item.setDescription(cmd.description());
        if (cmd.priority() != null) item.setPriority(cmd.priority());
        if (cmd.acceptorPresent()) {
            AppUser newAcceptor = cmd.acceptorId() == null ? null : requireTeamUser(teamId, cmd.acceptorId(), "验收人");
            boolean acceptorChanged = newAcceptor != null
                    && (item.getAcceptor() == null || !item.getAcceptor().getId().equals(newAcceptor.getId()));
            item.setAcceptor(newAcceptor);
            if (acceptorChanged) {
                notifyService.notify(newAcceptor, "ACCEPTOR", item, user, "你被设为事项验收人");
            }
        }
        if (cmd.dueDatePresent()) item.setDueDate(cmd.dueDate());
        if (cmd.estimatePresent()) item.setEstimate(cmd.estimate());
        if (cmd.iterationPresent()) item.setIteration(cmd.iterationId() == null ? null
                : requirePlannableIteration(teamId, cmd.iterationId()));
        if (cmd.parentPresent()) {
            if (item.getType() != ItemType.STORY) throw ApiException.validation("只有故事可以挂父任务");
            Item p = cmd.parentId() == null ? null : requireItem(teamId, cmd.parentId());
            if (p != null && p.getType() != ItemType.TASK) throw ApiException.validation("故事的父事项必须是任务");
            item.setParent(p);
        }
        if (cmd.storyCategory() != null) item.setStoryCategory(cmd.storyCategory());
        if (cmd.poPresent()) item.setPo(cmd.poId() == null ? null : requireTeamUser(teamId, cmd.poId(), "所属 PO"));
        if (cmd.ownerIdsPresent() && item.getType() != ItemType.TASK) {
            // 抽屉内联改负责人（契约 0.3.0）：整体替换共同负责人，新增者收到指派通知
            List<AppUser> newOwners = (cmd.ownerIds() == null ? List.<Long>of() : cmd.ownerIds()).stream()
                    .map(uid -> requireTeamUser(teamId, uid, "负责人")).toList();
            java.util.Set<Long> before = new java.util.HashSet<>(transitionService.ownerIds(item));
            transitionService.setOwners(item, newOwners);
            for (AppUser o : newOwners) {
                if (!before.contains(o.getId())) notifyService.notify(o, "ASSIGN", item, user, "你被设为事项负责人");
            }
        }
        if (cmd.labelNamesPresent()) {
            itemLabelRepo.deleteByItemId(item.getId());
            itemLabelRepo.flush();
            Team team = item.getTeam();
            for (String name : (cmd.labelNames() == null ? List.<String>of() : cmd.labelNames())) {
                Label label = labelRepo.findByTeamIdAndName(teamId, name).orElseGet(() -> {
                    Label l = new Label();
                    l.setTeam(team);
                    l.setName(name);
                    return labelRepo.save(l);
                });
                itemLabelRepo.save(new ItemLabel(item, label));
            }
        }
        itemRepo.save(item);
        activityRepo.save(new Activity(item, user, "EDIT", st.name(), st.name(), null));
        auditService.log(teamId, user, "EDIT_ITEM", "ITEM", item.getId(),
                item.getCode() + " " + item.getTitle());
        return item;
    }

    @Transactional
    public Item transition(Long teamId, Long itemId, AppUser user, ItemTransitionService.Action action,
                           int version, String reason, Long newAssigneeId) {
        return transition(teamId, itemId, user, action, version, reason, newAssigneeId, null);
    }

    @Transactional
    public Item transition(Long teamId, Long itemId, AppUser user, ItemTransitionService.Action action,
                           int version, String reason, Long newAssigneeId,
                           ItemTransitionService.Extras extras) {
        teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        return transitionService.transition(item, user, action, version, reason, newAssigneeId, extras);
    }

    /** 加入共同负责（修订一第 5 节）：已有负责人的未完成事项，任何成员可加入，通知原负责人。 */
    @Transactional
    public Item join(Long teamId, Long itemId, AppUser user, int version) {
        teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (item.getType() == ItemType.TASK) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "任务不认领，不能加入负责");
        }
        List<ItemOwner> owners = ownerRepo.findByItemId(itemId);
        if (owners.stream().anyMatch(o -> o.getUser().getId().equals(user.getId()))) {
            // Q13：已是负责人再「加入」→ 返回当前状态，不报错（动作本身幂等安全，先于版本校验）
            return item;
        }
        if (version != item.getVersion()) throw ApiException.versionConflict("事项已被他人修改，请刷新后重试");
        if (item.getStatus() == ItemStatus.DONE || item.getStatus() == ItemStatus.CANCELLED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "已完成/已取消的事项不能加入负责");
        }
        if (owners.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "待认领事项请走认领（CLAIM）");
        }
        ownerRepo.save(new ItemOwner(item, user));
        for (ItemOwner o : owners) notifyService.notify(o.getUser(), "JOIN", item, user, "有成员加入共同负责");
        activityRepo.save(new Activity(item, user, "JOIN", item.getStatus().name(), item.getStatus().name(), null));
        itemRepo.save(item);
        return item;
    }

    /**
     * 软删除进回收站。：
     * 任务仅 PO/组长，且其下故事全部未开始（UNCLAIMED/NOT_STARTED 且无进展）才可删；
     * 故事仅 PO/组长且未开始时；独立事项（DEFECT/ANALYSIS/ADHOC）维持既有口径
     * （创建人未开始时或 PO/组长）。
     */
    @Transactional
    public void softDelete(Long teamId, Long itemId, AppUser user) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        boolean leaderOrPo = member.getRole() == Role.LEADER || member.getRole() == Role.PO;
        boolean notStarted = item.getStatus() == ItemStatus.UNCLAIMED || item.getStatus() == ItemStatus.NOT_STARTED;
        if (item.getType() == ItemType.TASK) {
            if (!leaderOrPo) throw ApiException.forbidden("仅 PO 或组长可删除任务");
            boolean anyStarted = itemRepo.findByParentId(itemId).stream()
                    .filter(c -> c.getDeletedAt() == null)
                    .anyMatch(c -> !(c.getStatus() == ItemStatus.UNCLAIMED || c.getStatus() == ItemStatus.NOT_STARTED)
                            || c.getProgressPercent() > 0 || c.getLastProgressAt() != null);
            if (anyStarted) {
                throw new ApiException(HttpStatus.CONFLICT, "DELETE_BLOCKED", "任务下已有故事开始，不能删除");
            }
        } else if (item.getType() == ItemType.STORY) {
            if (!leaderOrPo) throw ApiException.forbidden("仅 PO 或组长可删除故事");
            if (!notStarted) {
                throw new ApiException(HttpStatus.CONFLICT, "DELETE_BLOCKED", "故事已开始，不能删除");
            }
        } else {
            boolean allowed = leaderOrPo || (item.getCreator().getId().equals(user.getId()) && notStarted);
            if (!allowed) throw ApiException.forbidden("仅创建人（尚未开始时）或 PO/组长可删除事项");
        }
        item.setDeletedAt(Instant.now());
        item.setDeletedById(user.getId());
        itemRepo.save(item);
        activityRepo.save(new Activity(item, user, "DELETE", item.getStatus().name(), item.getStatus().name(), null));
        auditService.log(teamId, user, "DELETE_ITEM", "ITEM", item.getId(),
                item.getCode() + " " + item.getTitle());
        if (item.getParent() != null) derivationService.recalc(item.getParent());
    }

    @Transactional
    public Item restore(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        Item item = requireItemIncludingDeleted(teamId, itemId);
        if (item.getDeletedAt() == null) throw ApiException.validation("事项不在回收站中");
        item.setDeletedAt(null);
        item.setDeletedById(null);
        itemRepo.save(item);
        activityRepo.save(new Activity(item, user, "RESTORE", item.getStatus().name(), item.getStatus().name(), null));
        auditService.log(teamId, user, "RESTORE_ITEM", "ITEM", item.getId(),
                item.getCode() + " " + item.getTitle());
        if (item.getParent() != null) derivationService.recalc(item.getParent());
        return item;
    }

    @Transactional(readOnly = true)
    public List<Item> trash(Long teamId, AppUser user) {
        teamService.requireMember(teamId, user);
        return itemRepo.findByTeamId(teamId).stream().filter(i -> i.getDeletedAt() != null).toList();
    }

    @Transactional(readOnly = true)
    public List<Item> storiesOf(Long teamId, Long taskId, AppUser user) {
        teamService.requireMember(teamId, user);
        Item task = requireItem(teamId, taskId);
        if (task.getType() != ItemType.TASK) throw ApiException.validation("只有任务有故事列表");
        return childrenOf(task);
    }

    public List<Item> childrenOf(Item task) {
        return itemRepo.findByParentId(task.getId()).stream()
                .filter(s -> s.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparingInt(Item::getSortOrder).thenComparingLong(Item::getSeq))
                .toList();
    }

    @Transactional
    public List<Item> reorderStories(Long teamId, Long taskId, AppUser user, List<Long> orderedIds) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item task = requireItem(teamId, taskId);
        if (task.getType() != ItemType.TASK) throw ApiException.validation("只有任务可以排序故事");
        boolean allowed = member.getRole() == Role.PO || member.getRole() == Role.LEADER
                || task.getCreator().getId().equals(user.getId());
        if (!allowed) throw ApiException.forbidden("仅 PO、组长或任务创建人可排序故事");
        if (orderedIds == null || orderedIds.isEmpty()) throw ApiException.validation("orderedIds 不能为空");
        List<Item> children = childrenOf(task);
        java.util.Map<Long, Item> byId = new java.util.HashMap<>();
        children.forEach(c -> byId.put(c.getId(), c));
        int idx = 0;
        for (Long id : orderedIds) {
            Item c = byId.get(id);
            if (c == null) throw ApiException.validation("orderedIds 含不属于该任务的故事：" + id);
            c.setSortOrder(idx++);
        }
        for (Item c : children) {
            if (!orderedIds.contains(c.getId())) c.setSortOrder(idx++);
            itemRepo.save(c);
        }
        return childrenOf(task);
    }

    /** 搜索：标题/描述/编号/评论（含已归档与已完成，不含回收站）。 */
    @Transactional(readOnly = true)
    public List<Item> search(Long teamId, AppUser user, String q) {
        teamService.requireMember(teamId, user);
        if (q == null || q.isBlank()) throw ApiException.validation("搜索关键词不能为空");
        String needle = q.toLowerCase();
        java.util.Set<Long> commentHitIds = new java.util.HashSet<>();
        commentRepo.findByContentContainingIgnoreCase(q).forEach(c -> commentHitIds.add(c.getItem().getId()));
        return itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> i.getTitle().toLowerCase().contains(needle)
                        || (i.getDescription() != null && i.getDescription().toLowerCase().contains(needle))
                        || i.getCode().toLowerCase().contains(needle)
                        || commentHitIds.contains(i.getId()))
                .toList();
    }

    /** 待办池：任务列表 + 其下故事概要。 */
    @Transactional(readOnly = true)
    public List<Dtos.BacklogEntryDto> backlog(Long teamId, AppUser user) {
        teamService.requireMember(teamId, user);
        return itemRepo.findByTeamIdAndType(teamId, ItemType.TASK).stream()
                .filter(t -> t.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparingLong(Item::getSeq))
                .map(t -> {
                    List<Item> stories = childrenOf(t);
                    int done = (int) stories.stream().filter(s -> s.getStatus() == ItemStatus.DONE).count();
                    return new Dtos.BacklogEntryDto(toDto(t),
                            stories.stream().map(this::toDto).toList(), stories.size(), done);
                })
                .toList();
    }

    @Transactional
    public ProgressUpdate addProgress(Long teamId, Long itemId, AppUser user, String text, Integer percent,
                                      Boolean blocked, String blockReason, String nextStep) {
        return addProgress(teamId, itemId, user, text, percent, blocked, blockReason, nextStep, "NORMAL");
    }

    @Transactional
    public ProgressUpdate addProgress(Long teamId, Long itemId, AppUser user, String text, Integer percent,
                                      Boolean blocked, String blockReason, String nextStep, String kind) {
        return addProgress(teamId, itemId, user, text, percent, blocked, blockReason, nextStep, kind, null);
    }

    /** 进展 @ 提及版（1.0.4）：mentionIds 落 progress_mention 并给被 @ 者发 MENTION 通知，口径与评论一致。 */
    @Transactional
    public ProgressUpdate addProgress(Long teamId, Long itemId, AppUser user, String text, Integer percent,
                                      Boolean blocked, String blockReason, String nextStep, String kind,
                                      List<Long> mentionIds) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        boolean allowed = member.getRole() == Role.LEADER || transitionService.isOwner(item, user);
        if (!allowed) throw ApiException.forbidden("仅负责人或组长可登记进展");
        boolean keepGoing = "KEEP_GOING".equals(kind);
        if (keepGoing && (text == null || text.isBlank())) text = "照常推进";
        if (text == null || text.isBlank()) throw ApiException.validation("进展文字必填");
        if (percent != null && (percent < 0 || percent > 100)) throw ApiException.validation("进度须为 0 至 100");
        if (item.getStatus() == ItemStatus.DONE || item.getStatus() == ItemStatus.CANCELLED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ILLEGAL_TRANSITION", "已完成/已取消的事项不能登记进展");
        }
        if (item.getStatus() == ItemStatus.NOT_STARTED) {
            transitionService.transition(item, user, ItemTransitionService.Action.START, item.getVersion(), null, null);
        }
        ProgressUpdate p = new ProgressUpdate();
        p.setItem(item);
        p.setAuthor(user);
        p.setText(text);
        p.setPercent(percent);
        p.setNextStep(nextStep);
        p.setKind(keepGoing ? "KEEP_GOING" : "NORMAL");
        boolean nowBlocked = blocked != null ? blocked : item.isBlocked();
        p.setBlocked(nowBlocked);
        p.setBlockReason(Boolean.TRUE.equals(blocked) ? blockReason : (nowBlocked ? item.getBlockReason() : null));
        if (mentionIds != null) p.setMentionIds(new java.util.HashSet<>(mentionIds));
        progressRepo.save(p);
        if (mentionIds != null) {
            for (Long uid : mentionIds) {
                userRepo.findById(uid)
                        .filter(x -> memberRepo.findByTeamIdAndUserId(teamId, x.getId()).isPresent())
                        .ifPresent(x -> notifyService.notify(x, "MENTION", item, user, "有人在进展里 @ 了你"));
            }
        }
        if (percent != null) item.setProgressPercent(percent);
        if (blocked != null) {
            boolean was = item.isBlocked();
            item.setBlocked(blocked);
            item.setBlockReason(blocked ? blockReason : null);
            item.setBlockedAt(blocked ? Instant.now() : null);
            if (blocked && !was) {
                activityRepo.save(new Activity(item, user, "BLOCK", item.getStatus().name(), item.getStatus().name(), blockReason));
                // 新阻塞通知组长
                memberRepo.findByTeamId(teamId).stream()
                        .filter(m -> m.getRole() == Role.LEADER)
                        .forEach(m -> notifyService.notify(m.getUser(), "BLOCK", item, user,
                                "事项被标记阻塞：" + (blockReason == null ? "" : blockReason)));
            }
            if (!blocked && was) activityRepo.save(new Activity(item, user, "UNBLOCK", item.getStatus().name(), item.getStatus().name(), null));
        }
        item.setLastProgressAt(Instant.now());
        item.setNudgeCountReset();
        itemRepo.save(item);
        return p;
    }

    @Transactional(readOnly = true)
    public List<ProgressUpdate> progressList(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        return progressRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    @Transactional
    public Comment addComment(Long teamId, Long itemId, AppUser user, String content, List<Long> mentionIds) {
        teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (content == null || content.isBlank()) throw ApiException.validation("评论内容不能为空");
        Comment c = new Comment();
        c.setItem(item);
        c.setAuthor(user);
        c.setContent(content);
        if (mentionIds != null) c.setMentionIds(new java.util.HashSet<>(mentionIds));
        commentRepo.save(c);
        java.util.Set<Long> mentioned = mentionIds == null ? java.util.Set.of() : new java.util.HashSet<>(mentionIds);
        if (mentionIds != null) {
            for (Long uid : mentionIds) {
                userRepo.findById(uid)
                        .filter(x -> memberRepo.findByTeamIdAndUserId(teamId, x.getId()).isPresent())
                        .ifPresent(x -> notifyService.notify(x, "MENTION", item, user, "有人在评论里 @ 了你"));
            }
        }
        // 关注或负责事项有新评论 → 通知负责人与关注者（设计 §3.7，此前缺失）；
        // 被 @ 者已单独收到 MENTION 不重复发；评论作者本人由 NotifyService 自动跳过。
        java.util.Set<Long> commentNotified = new java.util.HashSet<>(mentioned);
        List<AppUser> audience = new ArrayList<>();
        ownerRepo.findByItemId(itemId).forEach(o -> audience.add(o.getUser()));
        watcherRepo.findByItemId(itemId).forEach(w -> audience.add(w.getUser()));
        for (AppUser target : audience) {
            if (commentNotified.add(target.getId())) {
                notifyService.notify(target, "COMMENT", item, user, "你关注或负责的事项有新评论");
            }
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        return commentRepo.findByItemIdOrderByCreatedAtAsc(itemId).stream()
                .filter(c -> c.getDeletedAt() == null).toList();
    }

    /** 评论修改（）：仅作者，且发出后 15 分钟内，超时 403。 */
    @Transactional
    public Comment patchComment(Long teamId, Long itemId, Long commentId, AppUser user, String content) {
        teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        Comment c = commentRepo.findByIdAndItemId(commentId, itemId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("评论不存在"));
        if (!c.getAuthor().getId().equals(user.getId())) throw ApiException.forbidden("仅评论作者可修改评论");
        if (c.getCreatedAt().plus(15, java.time.temporal.ChronoUnit.MINUTES).isBefore(Instant.now())) {
            throw ApiException.forbidden("评论发出超过 15 分钟，不能再修改");
        }
        if (content == null || content.isBlank()) throw ApiException.validation("评论内容不能为空");
        c.setContent(content);
        c.setUpdatedAt(Instant.now());
        return commentRepo.save(c);
    }

    /** 评论删除（）：作者或组长可删（软删除）。 */
    @Transactional
    public void deleteComment(Long teamId, Long itemId, Long commentId, AppUser user) {
        TeamMember member = teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        Comment c = commentRepo.findByIdAndItemId(commentId, itemId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("评论不存在"));
        if (!c.getAuthor().getId().equals(user.getId()) && member.getRole() != Role.LEADER) {
            throw ApiException.forbidden("仅评论作者或组长可删除评论");
        }
        c.setDeletedAt(Instant.now());
        commentRepo.save(c);
    }

    @Transactional(readOnly = true)
    public List<Activity> activities(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        return activityRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    private boolean canEdit(TeamMember member, Item item, AppUser user) {
        return member.getRole() == Role.LEADER || member.getRole() == Role.PO
                || item.getCreator().getId().equals(user.getId())
                || transitionService.isOwner(item, user);
    }

    // ---------- 检查清单 ----------

    @Transactional(readOnly = true)
    public List<ChecklistEntry> checklist(Long teamId, Long itemId, AppUser user) {
        teamService.requireMember(teamId, user);
        requireItem(teamId, itemId);
        return checklistRepo.findByItemIdOrderBySortOrderAsc(itemId);
    }

    @Transactional
    public ChecklistEntry addChecklistEntry(Long teamId, Long itemId, AppUser user, String text, Integer sortOrder) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (!canEdit(member, item, user)) throw ApiException.forbidden("仅创建人、负责人、PO 或组长可编辑检查清单");
        if (text == null || text.isBlank() || text.length() > 500) throw ApiException.validation("清单条目文字不能为空且不超过 500 字");
        ChecklistEntry e = new ChecklistEntry();
        e.setItem(item);
        e.setText(text);
        e.setDone(false);
        if (sortOrder != null) {
            e.setSortOrder(sortOrder);
        } else {
            int next = checklistRepo.findByItemIdOrderBySortOrderAsc(itemId).stream()
                    .mapToInt(ChecklistEntry::getSortOrder).max().orElse(-1) + 1;
            e.setSortOrder(next);
        }
        return checklistRepo.save(e);
    }

    @Transactional
    public ChecklistEntry updateChecklistEntry(Long teamId, Long itemId, Long entryId, AppUser user,
                                               String text, Boolean done, Integer sortOrder) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (!canEdit(member, item, user)) throw ApiException.forbidden("仅创建人、负责人、PO 或组长可编辑检查清单");
        ChecklistEntry e = checklistRepo.findById(entryId)
                .filter(x -> x.getItem().getId().equals(itemId))
                .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
        if (text != null) {
            if (text.isBlank() || text.length() > 500) throw ApiException.validation("清单条目文字不能为空且不超过 500 字");
            e.setText(text);
        }
        if (done != null) e.setDone(done);
        if (sortOrder != null) e.setSortOrder(sortOrder);
        return checklistRepo.save(e);
    }

    @Transactional
    public void deleteChecklistEntry(Long teamId, Long itemId, Long entryId, AppUser user) {
        TeamMember member = teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (!canEdit(member, item, user)) throw ApiException.forbidden("仅创建人、负责人、PO 或组长可编辑检查清单");
        ChecklistEntry e = checklistRepo.findById(entryId)
                .filter(x -> x.getItem().getId().equals(itemId))
                .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
        checklistRepo.delete(e);
    }

    /**
     * Q11：当前请求用户对该事项可执行的操作键（权限判断只在服务端这一处实现，前端按此显隐按钮）。
     * user 为 null（内部无用户上下文的组装）时返回空列表。
     */
    public List<String> allowedActions(Item item, AppUser user) {
        if (user == null) return List.of();
        TeamMember member = memberRepo.findByTeamIdAndUserId(item.getTeam().getId(), user.getId()).orElse(null);
        if (member == null) return List.of();
        List<String> out = new ArrayList<>();
        if (item.getDeletedAt() != null) {
            out.add("RESTORE");
            return out;
        }
        out.add("VIEW");
        boolean leaderOrPo = member.getRole() == Role.LEADER || member.getRole() == Role.PO;
        boolean owner = transitionService.isOwner(item, user);
        if (canEdit(member, item, user)) out.add("EDIT");
        if (canDeleteItem(member, item, user)) out.add("DELETE");
        if (item.getType() != ItemType.TASK) {
            ItemStatus st = item.getStatus();
            if (st == ItemStatus.UNCLAIMED) out.add("CLAIM");
            if (!transitionService.ownerIds(item).isEmpty() && !owner
                    && st != ItemStatus.DONE && st != ItemStatus.CANCELLED) out.add("JOIN");
            if (!owner && (st == ItemStatus.NOT_STARTED || st == ItemStatus.IN_PROGRESS
                    || st == ItemStatus.IN_REVIEW)) out.add("NUDGE");
            if ((owner || member.getRole() == Role.LEADER)
                    && st != ItemStatus.DONE && st != ItemStatus.CANCELLED) out.add("PROGRESS");
            // 修订五 A7：状态菜单项同样来自 allowedActions（前端只渲染这里给出的流转动作，不可用动作不出现）。
            boolean leaderOrPo2 = leaderOrPo;
            if (owner && st == ItemStatus.NOT_STARTED) out.add("START");
            if (owner && st == ItemStatus.IN_PROGRESS) out.add("SUBMIT");
            if (st == ItemStatus.IN_REVIEW && !owner
                    && (user.getId().equals(item.getAcceptor() == null ? null : item.getAcceptor().getId()) || leaderOrPo2)) {
                out.add("APPROVE"); out.add("REJECT");
            }
            if (owner && (st == ItemStatus.NOT_STARTED || st == ItemStatus.IN_PROGRESS)) out.add("RELEASE");
            if (owner && st == ItemStatus.NOT_STARTED && item.isAssignedDirectly()) out.add("DECLINE");
            if (st != ItemStatus.DONE && st != ItemStatus.CANCELLED
                    && (item.getCreator().getId().equals(user.getId()) || owner || leaderOrPo2)) out.add("TRANSFER");
            boolean cancelOk = leaderOrPo2 || (item.getCreator().getId().equals(user.getId())
                    && (st == ItemStatus.UNCLAIMED || st == ItemStatus.NOT_STARTED));
            if (st != ItemStatus.DONE && st != ItemStatus.CANCELLED && cancelOk) out.add("CANCEL");
            if ((st == ItemStatus.DONE || st == ItemStatus.CANCELLED)
                    && (item.getCreator().getId().equals(user.getId()) || leaderOrPo2
                    || (item.getAcceptor() != null && item.getAcceptor().getId().equals(user.getId())))) out.add("REOPEN");
        } else {
            // 任务：待验收时组长/PO 的验收动作也进 allowedActions
            if (item.getStatus() == ItemStatus.IN_REVIEW && leaderOrPo) { out.add("APPROVE"); out.add("REJECT"); }
            if (item.getStatus() != ItemStatus.DONE && item.getStatus() != ItemStatus.CANCELLED && leaderOrPo) out.add("CANCEL");
        }
        return out;
    }

    /** 与 softDelete 的拒绝规则同口径的布尔版（供 allowedActions 使用，不抛异常）。 */
    private boolean canDeleteItem(TeamMember member, Item item, AppUser user) {
        boolean leaderOrPo = member.getRole() == Role.LEADER || member.getRole() == Role.PO;
        boolean notStarted = item.getStatus() == ItemStatus.UNCLAIMED || item.getStatus() == ItemStatus.NOT_STARTED;
        if (item.getType() == ItemType.TASK) {
            if (!leaderOrPo) return false;
            return itemRepo.findByParentId(item.getId()).stream()
                    .filter(c -> c.getDeletedAt() == null)
                    .noneMatch(c -> !(c.getStatus() == ItemStatus.UNCLAIMED || c.getStatus() == ItemStatus.NOT_STARTED)
                            || c.getProgressPercent() > 0 || c.getLastProgressAt() != null);
        }
        if (item.getType() == ItemType.STORY) return leaderOrPo && notStarted;
        return leaderOrPo || (item.getCreator().getId().equals(user.getId()) && notStarted);
    }

    @Transactional(readOnly = true)
    public Dtos.ItemDto toDto(Item item) { return toDto(item, null); }

    @Transactional(readOnly = true)
    public Dtos.ItemDto toDto(Item item, AppUser user) {
        List<Long> collabs = collaboratorRepo.findByItemId(item.getId()).stream().map(c -> c.getUser().getId()).toList();
        List<String> labels = itemLabelRepo.findByItemId(item.getId()).stream().map(il -> il.getLabel().getName()).toList();
        List<Item> children = itemRepo.findByParentId(item.getId()).stream()
                .filter(c -> c.getDeletedAt() == null).toList();
        return new Dtos.ItemDto(
                item.getId(), item.getCode(), item.getType().name(), item.getTitle(), item.getDescription(),
                item.getStatus().name(), item.getPriority().name(), item.getCreator().getId(),
                transitionService.ownerIds(item),
                item.getAcceptor() == null ? null : item.getAcceptor().getId(),
                collabs,
                item.getParent() == null ? null : item.getParent().getId(),
                item.getIteration() == null ? null : item.getIteration().getId(),
                item.getDueDate(), item.getEstimate(), item.getProgressPercent(), item.isBlocked(), item.getBlockReason(),
                item.getNudgeCount(), labels,
                children.size(),
                children.stream().filter(c -> c.getStatus() == ItemStatus.DONE).count(),
                commentRepo.countByItemId(item.getId()),
                item.getVersion(), item.getCreatedAt(), item.getUpdatedAt(), item.getLastProgressAt(),
                checklistRepo.findByItemIdOrderBySortOrderAsc(item.getId()).stream().map(Dtos.ChecklistDto::of).toList(),
                item.getStoryCategory() == null ? null : item.getStoryCategory().name(),
                item.getPo() == null ? null : item.getPo().getId(),
                item.getBatchId(), item.isAssignedDirectly(), item.getSortOrder(),
                item.getType() == ItemType.TASK ? TaskDerivationService.toTaskStatus(item).name() : null,
                inviteeRepo.findByItemId(item.getId()).stream().map(iv -> iv.getUser().getId()).toList(),
                item.getSource(), item.getExternalSystem(), item.getExternalId(), item.getExternalUrl(),
                item.isExternalClosed(), allowedActions(item, user), item.getAcceptorNote(),
                rejected(item), rejectReason(item));
    }

    /**
     * 修订五 B「已退回」标记（派生，不加表字段）：事项处于进行中，且最近一次关键流转是 REJECT
     * （其后没有 SUBMIT / APPROVE / REOPEN 覆盖）时为 true；退回意见取该次 REJECT 的原因。
     */
    private boolean rejected(Item item) { return rejectReason(item) != null; }

    private String rejectReason(Item item) {
        if (item.getStatus() != ItemStatus.IN_PROGRESS) return null;
        List<Activity> acts = activityRepo.findByItemIdOrderByCreatedAtAsc(item.getId());
        for (int i = acts.size() - 1; i >= 0; i--) {
            String a = acts.get(i).getAction();
            if ("REJECT".equals(a)) return acts.get(i).getDetail() == null ? "" : acts.get(i).getDetail();
            if ("SUBMIT".equals(a) || "APPROVE".equals(a) || "REOPEN".equals(a)) return null;
        }
        return null;
    }
}
