package com.iterationboard.collab;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.ItemService;
import com.iterationboard.reminder.RiskService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import com.iterationboard.team.TeamSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组长总览：每位成员的状态、在做事项、待更新数、剩余点数与剩余容量；
 * 风险清单仅组长/PO 可见；附当前迭代健康度（完成率、燃尽偏差、阻塞数、待更新数）。
 */
@Service
public class OverviewService {
    private final TeamMemberRepo memberRepo;
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final IterationRepo iterationRepo;
    private final TeamService teamService;
    private final ItemService itemService;
    private final RiskService riskService;
    private final PlatformTime platformTime;

    public OverviewService(TeamMemberRepo memberRepo, ItemRepo itemRepo, ItemOwnerRepo ownerRepo,
                           IterationRepo iterationRepo, TeamService teamService, ItemService itemService,
                           RiskService riskService, PlatformTime platformTime) {
        this.memberRepo = memberRepo;
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.iterationRepo = iterationRepo;
        this.teamService = teamService;
        this.itemService = itemService;
        this.riskService = riskService;
        this.platformTime = platformTime;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Team team = me.getTeam();
        TeamSettings settings = TeamSettings.from(team);
        TeamSettings.Thresholds th = settings.thresholds;
        LocalDate today = platformTime.today();
        boolean canViewRisks = me.getRole() == Role.LEADER || me.getRole() == Role.PO;

        List<Item> teamItems = itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK)
                .toList();
        Map<Long, Item> byId = new LinkedHashMap<>();
        teamItems.forEach(i -> byId.put(i.getId(), i));
        Iteration active = iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE").orElse(null);

        List<Map<String, Object>> members = new ArrayList<>();
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            Long uid = m.getUser().getId();
            List<Item> owned = ownerRepo.findByUserId(uid).stream()
                    .map(o -> byId.get(o.getItem().getId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<Item> open = owned.stream()
                    .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                    .toList();
            List<Item> inProgress = open.stream().filter(i -> i.getStatus() == ItemStatus.IN_PROGRESS).toList();
            BigDecimal remainingPoints = BigDecimal.ZERO;
            for (Item i : open) {
                if (i.getEstimate() == null) continue;
                int n = Math.max(1, ownerRepo.findByItemId(i.getId()).size());
                remainingPoints = remainingPoints.add(
                        i.getEstimate().divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP));
            }
            long stale = inProgress.stream().filter(i -> {
                LocalDate last = RiskService.lastActivityDate(i);
                return last != null && Workdays.workdaysBetween(last, today, settings) >= th.staleInProgressWorkdays();
            }).count();
            java.time.Instant lastProgressAt = owned.stream()
                    .map(Item::getLastProgressAt).filter(java.util.Objects::nonNull)
                    .max(java.time.Instant::compareTo).orElse(null);
            BigDecimal remainingCapacity = null;
            if (active != null && active.getStartDate() != null && active.getEndDate() != null) {
                int days = 0;
                for (LocalDate d = today.isBefore(active.getStartDate()) ? active.getStartDate() : today;
                     !d.isAfter(active.getEndDate()); d = d.plusDays(1)) {
                    if (!Workdays.isWorkday(d, settings)) continue;
                    if (m.isAwayOn(d)) continue;
                    days++;
                }
                remainingCapacity = settings.capacityPointsPerWorkday.multiply(BigDecimal.valueOf(days));
            }
            boolean awayToday = ("LEAVE".equals(m.getMemberStatus()) || "TRAVEL".equals(m.getMemberStatus()))
                    && m.isAwayOn(today);
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("userId", uid);
            mm.put("memberStatus", m.getMemberStatus());
            mm.put("memberStatusStart", m.getStatusStart());
            mm.put("memberStatusEnd", m.getStatusEnd());
            mm.put("inProgressItems", inProgress.stream().map(i -> itemService.toDto(i, user)).toList());
            mm.put("lastProgressAt", lastProgressAt);
            mm.put("staleCount", stale);
            mm.put("remainingPoints", remainingPoints);
            mm.put("remainingCapacityPoints", remainingCapacity);
            mm.put("free", inProgress.isEmpty() && !awayToday);
            members.add(mm);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", members);
        out.put("canViewRisks", canViewRisks);
        out.put("risks", canViewRisks
                ? riskService.computeRisks(team, today).stream().map(RiskService.RiskSignal::toMap).toList()
                : List.of());
        out.put("iterationHealth", active == null ? null : iterationHealth(active, teamItems, settings, today));
        return out;
    }

    private Map<String, Object> iterationHealth(Iteration it, List<Item> teamItems, TeamSettings settings, LocalDate today) {
        List<Item> items = teamItems.stream()
                .filter(i -> i.getIteration() != null && i.getIteration().getId().equals(it.getId()))
                .toList();
        long total = items.stream().filter(i -> i.getStatus() != ItemStatus.CANCELLED).count();
        long done = items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
        int completionRate = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        BigDecimal totalEst = BigDecimal.ZERO, remainingEst = BigDecimal.ZERO;
        for (Item i : items) {
            if (i.getStatus() == ItemStatus.CANCELLED || i.getEstimate() == null) continue;
            totalEst = totalEst.add(i.getEstimate());
            if (i.getStatus() != ItemStatus.DONE) remainingEst = remainingEst.add(i.getEstimate());
        }
        BigDecimal ideal = totalEst;
        boolean behind = false;
        if (it.getStartDate() != null && it.getEndDate() != null) {
            int totalWd = Workdays.workdaysInclusive(it.getStartDate(), it.getEndDate(), settings);
            if (totalWd > 0) {
                LocalDate from = today.isBefore(it.getStartDate()) ? it.getStartDate() : today;
                int remainWd = from.isAfter(it.getEndDate()) ? 0 : Workdays.workdaysInclusive(from, it.getEndDate(), settings);
                ideal = totalEst.multiply(BigDecimal.valueOf(remainWd))
                        .divide(BigDecimal.valueOf(totalWd), 2, RoundingMode.HALF_UP);
                behind = remainingEst.compareTo(ideal.multiply(new BigDecimal("1.2"))) > 0
                        && !today.isBefore(it.getStartDate()) && !today.isAfter(it.getEndDate());
            }
        }
        long blocked = items.stream().filter(i -> i.isBlocked() && i.getStatus() != ItemStatus.DONE
                && i.getStatus() != ItemStatus.CANCELLED).count();
        long stale = items.stream().filter(i -> i.getStatus() == ItemStatus.IN_PROGRESS).filter(i -> {
            LocalDate last = RiskService.lastActivityDate(i);
            return last != null && Workdays.workdaysBetween(last, today, settings) >= settings.thresholds.staleInProgressWorkdays();
        }).count();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("iteration", com.iterationboard.config.Dtos.IterationDto.of(it));
        h.put("completionRate", completionRate);
        h.put("remainingEstimate", remainingEst);
        h.put("idealRemainingEstimate", ideal);
        h.put("behind", behind);
        h.put("blockedCount", blocked);
        h.put("staleCount", stale);
        return h;
    }
}
