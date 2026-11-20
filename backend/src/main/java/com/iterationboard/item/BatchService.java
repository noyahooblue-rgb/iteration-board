package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量下发（修订一第 5 节）：每人一份生成 N 个独立事项直接指派；
 * 批次汇总、一键催未完成（每天一次 + 请假/出差跳过本人改记组长）、整批取消（保留已完成）。
 */
@Service
public class BatchService {
    private final DispatchBatchRepo batchRepo;
    private final ItemRepo itemRepo;
    private final TeamMemberRepo memberRepo;
    private final NudgeRepo nudgeRepo;
    private final ActivityRepo activityRepo;
    private final TeamService teamService;
    private final ItemService itemService;
    private final NotifyService notifyService;
    private final ItemOwnerRepo ownerRepo;
    private final TaskDerivationService derivationService;
    private final PlatformTime platformTime;

    public BatchService(DispatchBatchRepo batchRepo, ItemRepo itemRepo, TeamMemberRepo memberRepo,
                        NudgeRepo nudgeRepo, ActivityRepo activityRepo, TeamService teamService,
                        ItemService itemService, NotifyService notifyService,
                        ItemOwnerRepo ownerRepo, TaskDerivationService derivationService, PlatformTime platformTime) {
        this.batchRepo = batchRepo; this.itemRepo = itemRepo; this.memberRepo = memberRepo;
        this.nudgeRepo = nudgeRepo; this.activityRepo = activityRepo; this.teamService = teamService;
        this.itemService = itemService; this.notifyService = notifyService;
        this.ownerRepo = ownerRepo; this.derivationService = derivationService; this.platformTime = platformTime;
    }

    public record BatchCmd(ItemType type, String title, String description, Priority priority,
                           List<Long> memberIds, StoryCategory storyCategory, Long parentId,
                           LocalDate dueDate, BigDecimal estimate, Long iterationId,
                           Boolean includeCreator) {
        public BatchCmd(ItemType type, String title, String description, Priority priority,
                        List<Long> memberIds, StoryCategory storyCategory, Long parentId,
                        LocalDate dueDate, BigDecimal estimate, Long iterationId) {
            this(type, title, description, priority, memberIds, storyCategory, parentId,
                    dueDate, estimate, iterationId, null);
        }
    }

    @Transactional
    public Dtos.BatchSummaryDto create(Long teamId, AppUser creator, BatchCmd cmd) {
        teamService.requireMember(teamId, creator);
        if (cmd.type() == null) throw ApiException.validation("事项类型不能为空");
        if (cmd.type() == ItemType.TASK) throw ApiException.validation("任务不能批量下发（每人一份只适用于故事与独立事项）");
        if (cmd.memberIds() == null || cmd.memberIds().isEmpty()) throw ApiException.validation("至少选择一名成员");
        // P14：每人一份默认不含发布人本人；includeCreator=true 时才把发布人加入名单
        List<Long> effectiveMemberIds = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(cmd.memberIds()));
        if (Boolean.TRUE.equals(cmd.includeCreator())) {
            if (!effectiveMemberIds.contains(creator.getId())) effectiveMemberIds.add(creator.getId());
        } else {
            effectiveMemberIds.removeIf(uid -> uid.equals(creator.getId()));
        }
        if (effectiveMemberIds.isEmpty()) throw ApiException.validation("至少选择一名成员（默认不含发布人本人）");
        DispatchBatch batch = new DispatchBatch();
        batch.setTeam(teamService.requireTeam(teamId));
        batch.setCreator(creator);
        batch.setTitle(cmd.title());
        batch.setItemType(cmd.type().name());
        batchRepo.save(batch);
        for (Long uid : effectiveMemberIds) {
            // 复用单建逻辑（含 STORY 仅 PO、父任务与类别校验、直接指派 assignedDirectly=true）
            Item item = itemService.create(teamId, creator, new ItemService.CreateItemCmd(
                    cmd.type(), cmd.title(), cmd.description(), cmd.priority(), "ASSIGN",
                    List.of(uid), null, null, cmd.dueDate(), cmd.estimate(), cmd.parentId(),
                    cmd.iterationId(), null, cmd.storyCategory(), null));
            item.setBatchId(batch.getId());
            itemRepo.save(item);
        }
        return summary(teamId, batch.getId(), creator);
    }

    @Transactional(readOnly = true)
    public Dtos.BatchSummaryDto summary(Long teamId, Long batchId, AppUser user) {
        teamService.requireMember(teamId, user);
        DispatchBatch batch = batchRepo.findByIdAndTeamId(batchId, teamId)
                .orElseThrow(() -> ApiException.notFound("批次不存在"));
        List<Item> items = itemRepo.findByBatchId(batchId).stream()
                .filter(i -> i.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparingLong(Item::getSeq)).toList();
        List<Dtos.BatchMemberDto> members = new ArrayList<>();
        // 每人一份：逐人状态取其事项的负责人与状态
        for (Item i : items) {
            List<Long> owners = itemServiceOwnerIds(i);
            Long uid = owners.isEmpty() ? null : owners.get(0);
            members.add(new Dtos.BatchMemberDto(uid, i.getId(), i.getStatus().name()));
        }
        int done = (int) items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
        return new Dtos.BatchSummaryDto(batch.getId(), teamId, batch.getCreator().getId(), batch.getTitle(),
                batch.getItemType(), items.size(), done, members, batch.getCreatedAt());
    }

    private List<Long> itemServiceOwnerIds(Item i) {
        return ownerRepo.findByItemId(i.getId()).stream().map(o -> o.getUser().getId()).toList();
    }

    @Transactional
    public Dtos.BatchNudgeDto nudge(Long teamId, Long batchId, AppUser actor) {
        teamService.requireMember(teamId, actor);
        batchRepo.findByIdAndTeamId(batchId, teamId).orElseThrow(() -> ApiException.notFound("批次不存在"));
        List<Item> items = itemRepo.findByBatchId(batchId).stream()
                .filter(i -> i.getDeletedAt() == null
                        && i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                .toList();
        Instant startOfToday = platformTime.startOfTodayInstant();
        // 组长（请假成员改记对象）：取组内第一名 LEADER
        AppUser leader = memberRepo.findByTeamId(teamId).stream()
                .filter(m -> m.getRole() == Role.LEADER).map(TeamMember::getUser).findFirst().orElse(null);
        List<Long> nudged = new ArrayList<>(), skipped = new ArrayList<>(), redirected = new ArrayList<>();
        LocalDate today = platformTime.today();
        for (Item item : items) {
            // 同一人对同一事项每天一次
            if (nudgeRepo.existsByItemIdAndNudgerIdAndCreatedAtGreaterThanEqual(item.getId(), actor.getId(), startOfToday)) {
                skipped.add(item.getId());
                continue;
            }
            List<Long> owners = itemServiceOwnerIds(item);
            if (owners.isEmpty()) { skipped.add(item.getId()); continue; }
            boolean anyNudged = false, anyRedirected = false;
            for (Long uid : owners) {
                TeamMember tm = memberRepo.findByTeamIdAndUserId(teamId, uid).orElse(null);
                boolean away = tm != null && (tm.getMemberStatus().equals("LEAVE") || tm.getMemberStatus().equals("TRAVEL"))
                        && tm.isAwayOn(today);
                if (away) {
                    if (leader == null) continue;
                    AppUser target = leader;
                    nudgeRepo.save(new Nudge(item, actor, target));
                    notifyService.notify(target, "NUDGE", item, actor,
                            "成员请假/出差，代为跟进事项「" + item.getTitle() + "」");
                    anyRedirected = true;
                } else {
                    AppUser target = tm == null ? null : tm.getUser();
                    nudgeRepo.save(new Nudge(item, actor, target));
                    notifyService.notify(target, "NUDGE", item, actor, "催一下：请更新事项「" + item.getTitle() + "」");
                    anyNudged = true;
                }
            }
            if (anyNudged) {
                item.incrementNudgeCount();
                itemRepo.save(item);
                nudged.add(item.getId());
            } else if (anyRedirected) {
                redirected.add(item.getId());
            } else {
                skipped.add(item.getId());
            }
        }
        return new Dtos.BatchNudgeDto(nudged, skipped, redirected);
    }

    @Transactional
    public Dtos.BatchSummaryDto cancel(Long teamId, Long batchId, AppUser actor, String reason) {
        teamService.requireMember(teamId, actor);
        batchRepo.findByIdAndTeamId(batchId, teamId).orElseThrow(() -> ApiException.notFound("批次不存在"));
        if (reason == null || reason.isBlank()) {
            throw new com.iterationboard.common.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "整批取消必须填写原因");
        }
        for (Item item : itemRepo.findByBatchId(batchId)) {
            if (item.getDeletedAt() == null
                    && item.getStatus() != ItemStatus.DONE && item.getStatus() != ItemStatus.CANCELLED) {
                ItemStatus from = item.getStatus();
                item.setStatus(ItemStatus.CANCELLED);
                item.setStatusChangedAt(Instant.now());
                itemRepo.save(item);
                activityRepo.save(new Activity(item, actor, "CANCEL", from.name(), "CANCELLED", reason));
                if (item.getParent() != null) derivationService.recalc(item.getParent());
            }
        }
        return summary(teamId, batchId, actor);
    }
}
