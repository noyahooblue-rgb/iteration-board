package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单事项催一下与快捷回应。
 * 口径与批量下发一致：同一发起人对同一事项每天一次；请假/出差者不提醒本人，
 * 改记给组长且不计入事项被催次数；轮到谁催谁（进行中催负责人、待验收催验收人）。
 */
@Service
public class NudgeService {
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final NudgeRepo nudgeRepo;
    private final TeamMemberRepo memberRepo;
    private final ActivityRepo activityRepo;
    private final TeamService teamService;
    private final NotifyService notifyService;

    public NudgeService(ItemRepo itemRepo, ItemOwnerRepo ownerRepo, NudgeRepo nudgeRepo,
                        TeamMemberRepo memberRepo, ActivityRepo activityRepo,
                        TeamService teamService, NotifyService notifyService) {
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.nudgeRepo = nudgeRepo;
        this.memberRepo = memberRepo;
        this.activityRepo = activityRepo;
        this.teamService = teamService;
        this.notifyService = notifyService;
    }

    private Item requireItem(Long teamId, Long itemId) {
        return itemRepo.findByIdAndTeamId(itemId, teamId)
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("事项不存在"));
    }

    private boolean isAway(Long teamId, Long userId, LocalDate today) {
        return memberRepo.findByTeamIdAndUserId(teamId, userId)
                .map(tm -> ("LEAVE".equals(tm.getMemberStatus()) || "TRAVEL".equals(tm.getMemberStatus()))
                        && tm.isAwayOn(today))
                .orElse(false);
    }

    @Transactional
    public Map<String, Object> nudge(Long teamId, Long itemId, AppUser actor) {
        teamService.requireMember(teamId, actor);
        Item item = requireItem(teamId, itemId);
        if (item.getType() == ItemType.TASK) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "任务不可催，请催其下故事");
        }
        ItemStatus st = item.getStatus();
        if (st == ItemStatus.UNCLAIMED || st == ItemStatus.DONE || st == ItemStatus.CANCELLED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "当前状态 " + st + " 不可催");
        }
        List<AppUser> owners = ownerRepo.findByItemId(itemId).stream().map(ItemOwner::getUser).toList();
        if (owners.stream().anyMatch(o -> o.getId().equals(actor.getId()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_NUDGE_OWN", "不能催自己负责的事项");
        }
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (nudgeRepo.existsByItemIdAndNudgerIdAndCreatedAtGreaterThanEqual(itemId, actor.getId(), startOfToday)) {
            throw new ApiException(HttpStatus.CONFLICT, "NUDGE_DAILY_LIMIT", "今天已经催过这个事项了");
        }
        List<AppUser> targets = new ArrayList<>();
        if (st == ItemStatus.IN_REVIEW) {
            if (item.getAcceptor() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_NUDGE_TARGET", "事项没有验收人，无法催");
            }
            targets.add(item.getAcceptor());
        } else {
            targets.addAll(owners);
        }
        if (targets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_NUDGE_TARGET", "事项没有负责人，无法催");
        }
        AppUser leader = memberRepo.findByTeamId(teamId).stream()
                .filter(m -> m.getRole() == Role.LEADER).map(TeamMember::getUser).findFirst().orElse(null);
        LocalDate today = LocalDate.now();
        List<Long> nudgedIds = new ArrayList<>();
        boolean redirected = false;
        for (AppUser target : targets) {
            if (isAway(teamId, target.getId(), today)) {
                if (leader == null || leader.getId().equals(target.getId())) continue;
                nudgeRepo.save(new Nudge(item, actor, leader));
                notifyService.notify(leader, "NUDGE", item, actor,
                        "成员请假/出差，代为跟进事项「" + item.getTitle() + "」");
                redirected = true;
            } else {
                nudgeRepo.save(new Nudge(item, actor, target));
                notifyService.notify(target, "NUDGE", item, actor, "催一下：请更新事项「" + item.getTitle() + "」");
                nudgedIds.add(target.getId());
            }
        }
        if (!nudgedIds.isEmpty()) {
            item.incrementNudgeCount();
            itemRepo.save(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", item.getId());
        out.put("nudgedUserIds", nudgedIds);
        out.put("redirectedToLeader", redirected);
        out.put("nudgeCount", item.getNudgeCount());
        return out;
    }

    @Transactional
    public Map<String, Object> respond(Long teamId, Long itemId, AppUser user, String response) {
        teamService.requireMember(teamId, user);
        Item item = requireItem(teamId, itemId);
        if (!List.of("SOON", "TODAY", "BLOCKED").contains(response)) {
            throw ApiException.validation("response 须为 SOON / TODAY / BLOCKED");
        }
        var pending = nudgeRepo.findFirstByItemIdAndTargetIdAndResponseIsNullOrderByCreatedAtDesc(itemId, user.getId());
        if (pending.isEmpty()) {
            // Q13：重复回应已回应过的催 → 返回当前状态，不报错
            var done = nudgeRepo.findFirstByItemIdAndTargetIdAndResponseIsNotNullOrderByRespondedAtDesc(itemId, user.getId());
            if (done.isPresent()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("itemId", itemId);
                out.put("response", done.get().getResponse());
                out.put("respondedAt", done.get().getRespondedAt());
                out.put("alreadyResponded", true);
                return out;
            }
            throw ApiException.notFound("没有待回应的催");
        }
        Nudge nudge = pending.get();
        nudge.setResponse(response);
        nudge.setRespondedAt(Instant.now());
        nudgeRepo.save(nudge);
        String label = switch (response) {
            case "SOON" -> "马上更新";
            case "TODAY" -> "今天搞定";
            default -> "卡住了";
        };
        notifyService.notify(nudge.getNudger(), "NUDGE_RESPONSE", item, user,
                "对催一下的回应：" + label);
        if ("BLOCKED".equals(response) && !item.isBlocked()) {
            // 快捷回应「卡住了」同步标记阻塞并通知组长
            item.setBlocked(true);
            item.setBlockReason("快捷回应：卡住了");
            item.setBlockedAt(Instant.now());
            itemRepo.save(item);
            activityRepo.save(new Activity(item, user, "BLOCK", item.getStatus().name(), item.getStatus().name(), "快捷回应：卡住了"));
            memberRepo.findByTeamId(teamId).stream()
                    .filter(m -> m.getRole() == Role.LEADER)
                    .forEach(m -> notifyService.notify(m.getUser(), "BLOCK", item, user, "事项被标记阻塞：快捷回应"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", item.getId());
        out.put("response", response);
        out.put("respondedAt", nudge.getRespondedAt());
        return out;
    }
}
