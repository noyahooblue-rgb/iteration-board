package com.iterationboard.reminder;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 自动提醒：定时扫描与组长「立即扫描」共用同一实现。
 * 去重：同一规则、同一事项、同一接收人当天最多一条（按已生成的同类通知判定）。
 * 请假/出差成员不提醒本人，改提醒组长；逾期在每个工作日重复提醒（靠每日去重实现逐日一条）。
 */
@Service
public class ReminderService {
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final TeamMemberRepo memberRepo;
    private final TeamRepo teamRepo;
    private final NotificationRepo notificationRepo;
    private final NotifyService notifyService;
    private final PlatformTime platformTime;

    public ReminderService(ItemRepo itemRepo, ItemOwnerRepo ownerRepo, TeamMemberRepo memberRepo,
                           TeamRepo teamRepo, NotificationRepo notificationRepo, NotifyService notifyService, PlatformTime platformTime) {
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.memberRepo = memberRepo;
        this.teamRepo = teamRepo;
        this.notificationRepo = notificationRepo;
        this.notifyService = notifyService;
        this.platformTime = platformTime;
    }

    @Transactional
    public int scanAll() {
        int total = 0;
        for (Team team : teamRepo.findAll()) {
            if (!"ACTIVE".equals(team.getStatus())) continue;
            total += scanTeam(team, platformTime.today());
        }
        return total;
    }

    @Transactional
    public int scanTeam(Team team, LocalDate today) {
        TeamSettings settings = TeamSettings.from(team);
        TeamSettings.Thresholds th = settings.thresholds;
        List<TeamMember> members = memberRepo.findByTeamId(team.getId());
        List<AppUser> leaders = members.stream().filter(m -> m.getRole() == Role.LEADER).map(TeamMember::getUser).toList();
        List<AppUser> posAndLeaders = members.stream()
                .filter(m -> m.getRole() == Role.LEADER || m.getRole() == Role.PO).map(TeamMember::getUser).toList();
        List<Item> items = itemRepo.findByTeamId(team.getId()).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK)
                .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                .toList();
        int created = 0;
        for (Item i : items) {
            List<AppUser> owners = ownerRepo.findByItemId(i.getId()).stream().map(ItemOwner::getUser).toList();
            switch (i.getStatus()) {
                case IN_PROGRESS -> {
                    LocalDate last = RiskService.lastActivityDate(i);
                    if (last != null && Workdays.workdaysBetween(last, today, settings) >= th.staleInProgressWorkdays()) {
                        for (AppUser o : owners) created += send(team, members, leaders, o, "REMINDER_STALE", i, today,
                                "事项「" + i.getTitle() + "」已超过 " + th.staleInProgressWorkdays() + " 个工作日没有新进展，请更新");
                    }
                }
                case NOT_STARTED -> {
                    LocalDate since = RiskService.dateOf(i.getStatusChangedAt() != null ? i.getStatusChangedAt() : i.getCreatedAt());
                    if (since != null && Workdays.workdaysBetween(since, today, settings) >= th.notStartedWorkdays()) {
                        for (AppUser o : owners) created += send(team, members, leaders, o, "REMINDER_NOT_STARTED", i, today,
                                "事项「" + i.getTitle() + "」已认领超过 " + th.notStartedWorkdays() + " 个工作日尚未开始");
                    }
                }
                case IN_REVIEW -> {
                    LocalDate since = RiskService.dateOf(i.getStatusChangedAt() != null ? i.getStatusChangedAt() : i.getCreatedAt());
                    if (since != null && Workdays.workdaysBetween(since, today, settings) >= th.reviewPendingWorkdays()
                            && i.getAcceptor() != null) {
                        created += send(team, members, leaders, i.getAcceptor(), "REMINDER_REVIEW", i, today,
                                "事项「" + i.getTitle() + "」待验收已超过 " + th.reviewPendingWorkdays() + " 个工作日，请处理");
                    }
                }
                case UNCLAIMED -> {
                    LocalDate since = RiskService.dateOf(i.getCreatedAt());
                    if (since != null && Workdays.workdaysBetween(since, today, settings) >= th.unclaimedWorkdays()) {
                        for (AppUser p : posAndLeaders) created += sendRaw(p, "REMINDER_UNCLAIMED", i,
                                "事项「" + i.getTitle() + "」进入认领池已超过 " + th.unclaimedWorkdays() + " 个工作日无人认领");
                    }
                }
                default -> { }
            }
            if (i.isBlocked() && i.getBlockedAt() != null
                    && Workdays.workdaysBetween(RiskService.dateOf(i.getBlockedAt()), today, settings) >= th.blockedWorkdays()) {
                for (AppUser l : leaders) created += sendRaw(l, "REMINDER_BLOCKED", i,
                        "事项「" + i.getTitle() + "」阻塞已超过 " + th.blockedWorkdays() + " 个工作日，请协调解除");
            }
            if (i.getDueDate() != null && i.getDueDate().isBefore(today)) {
                for (AppUser o : owners) created += send(team, members, leaders, o, "REMINDER_OVERDUE", i, today,
                        "事项「" + i.getTitle() + "」已逾期（截止 " + i.getDueDate() + "），请更新进展或调整计划");
                for (AppUser l : leaders) created += sendRaw(l, "REMINDER_OVERDUE", i,
                        "事项「" + i.getTitle() + "」已逾期（截止 " + i.getDueDate() + "）");
            } else if (i.getDueDate() != null
                    && Workdays.workdaysBetween(today, i.getDueDate(), settings) <= th.dueSoonWorkdays()
                    && !i.getDueDate().isBefore(today)) {
                for (AppUser o : owners) created += send(team, members, leaders, o, "REMINDER_DUE_SOON", i, today,
                        "事项「" + i.getTitle() + "」将于 " + i.getDueDate() + " 截止，请留意");
            }
        }
        return created;
    }

    /** 发给某成员的提醒：本人请假/出差时改发组长（组长本人除外）。 */
    private int send(Team team, List<TeamMember> members, List<AppUser> leaders, AppUser recipient,
                     String type, Item item, LocalDate today, String content) {
        AppUser target = recipient;
        TeamMember tm = members.stream().filter(m -> m.getUser().getId().equals(recipient.getId())).findFirst().orElse(null);
        boolean away = tm != null && ("LEAVE".equals(tm.getMemberStatus()) || "TRAVEL".equals(tm.getMemberStatus()))
                && tm.isAwayOn(today);
        if (away && !leaders.isEmpty() && leaders.stream().noneMatch(l -> l.getId().equals(recipient.getId()))) {
            target = leaders.get(0);
            content = "（原负责人请假/出差）" + content;
        }
        return sendRaw(target, type, item, content);
    }

    private int sendRaw(AppUser recipient, String type, Item item, String content) {
        if (recipient == null) return 0;
        Instant startOfToday = platformTime.startOfTodayInstant();
        boolean exists = !notificationRepo.findRecent(recipient.getId(), type, item.getId(), startOfToday).isEmpty();
        if (exists) return 0;
        notifyService.notify(recipient, type, item, null, content);
        return 1;
    }
}
