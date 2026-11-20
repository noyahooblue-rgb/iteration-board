package com.iterationboard.reminder;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
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
 * 风险信号（设计 §7.6 规则版）：总览页与风险摘要报告共用。
 * 阈值取小组设置的 reminderThresholds（与自动提醒同一口径）。
 */
@Service
public class RiskService {
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final TeamMemberRepo memberRepo;
    private final IterationRepo iterationRepo;

    public RiskService(ItemRepo itemRepo, ItemOwnerRepo ownerRepo, TeamMemberRepo memberRepo, IterationRepo iterationRepo) {
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.memberRepo = memberRepo;
        this.iterationRepo = iterationRepo;
    }

    public record RiskSignal(String rule, String severity, Long itemId, String itemCode, Long userId, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("severity", severity);
            m.put("itemId", itemId);
            m.put("itemCode", itemCode);
            m.put("userId", userId);
            m.put("message", message);
            return m;
        }
    }

    public static LocalDate dateOf(java.time.Instant instant) {
        return instant == null ? null : instant.atZone(PlatformTime.zoneId()).toLocalDate();
    }

    /** 参考时间：最近进展时间，无进展时用状态变更时间，再无则创建时间。 */
    public static LocalDate lastActivityDate(Item i) {
        if (i.getLastProgressAt() != null) return dateOf(i.getLastProgressAt());
        if (i.getStatusChangedAt() != null) return dateOf(i.getStatusChangedAt());
        return dateOf(i.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<RiskSignal> computeRisks(Team team, LocalDate today) {
        TeamSettings settings = TeamSettings.from(team);
        TeamSettings.Thresholds th = settings.thresholds;
        List<Item> items = itemRepo.findByTeamId(team.getId()).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK)
                .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                .toList();
        List<RiskSignal> out = new ArrayList<>();
        Map<Long, Integer> reviewByAcceptor = new LinkedHashMap<>();
        for (Item i : items) {
            if (i.isBlocked() && i.getBlockedAt() != null
                    && Workdays.workdaysBetween(dateOf(i.getBlockedAt()), today, settings) >= th.blockedWorkdays()) {
                out.add(new RiskSignal("BLOCKED_LONG", "HIGH", i.getId(), i.getCode(), null,
                        "阻塞超过 " + th.blockedWorkdays() + " 个工作日未解除：" + i.getTitle()));
            }
            if (i.getDueDate() != null && i.getDueDate().isBefore(today)) {
                out.add(new RiskSignal("OVERDUE", "HIGH", i.getId(), i.getCode(), null,
                        "已逾期（截止 " + i.getDueDate() + "）：" + i.getTitle()));
            } else if (i.getDueDate() != null
                    && Workdays.workdaysBetween(today, i.getDueDate(), settings) <= th.dueSoonWorkdays()) {
                out.add(new RiskSignal("DUE_SOON", "MEDIUM", i.getId(), i.getCode(), null,
                        "临近截止（" + i.getDueDate() + "）：" + i.getTitle()));
            }
            if (i.getStatus() == ItemStatus.IN_PROGRESS) {
                LocalDate last = lastActivityDate(i);
                if (last != null && Workdays.workdaysBetween(last, today, settings) >= th.staleInProgressWorkdays()) {
                    out.add(new RiskSignal("STALE", "MEDIUM", i.getId(), i.getCode(), null,
                            "进行中超过 " + th.staleInProgressWorkdays() + " 个工作日无新进展：" + i.getTitle()));
                }
            }
            if (i.getStatus() == ItemStatus.IN_REVIEW && i.getAcceptor() != null) {
                reviewByAcceptor.merge(i.getAcceptor().getId(), 1, Integer::sum);
            }
            if (i.getNudgeCount() >= 3) {
                out.add(new RiskSignal("NUDGED_OFTEN", "LOW", i.getId(), i.getCode(), null,
                        "本轮已被催 " + i.getNudgeCount() + " 次仍未更新：" + i.getTitle()));
            }
            if (i.getStatus() == ItemStatus.UNCLAIMED
                    && (i.getPriority() == Priority.P0 || i.getPriority() == Priority.P1)
                    && Workdays.workdaysBetween(dateOf(i.getCreatedAt()), today, settings) >= th.unclaimedWorkdays()) {
                out.add(new RiskSignal("UNCLAIMED_PRIORITY", "MEDIUM", i.getId(), i.getCode(), null,
                        "高优先级事项无人认领已超过 " + th.unclaimedWorkdays() + " 个工作日：" + i.getTitle()));
            }
        }
        reviewByAcceptor.forEach((acceptorId, count) -> {
            if (count >= 3) {
                out.add(new RiskSignal("REVIEW_BACKLOG", "MEDIUM", null, null, acceptorId,
                        "待其验收的事项已积压 " + count + " 件"));
            }
        });
        // 超载：当前迭代内剩余故事点（均分）超过剩余容量
        iterationRepo.findFirstByTeamIdAndStatus(team.getId(), "ACTIVE").ifPresent(it -> {
            if (it.getStartDate() == null || it.getEndDate() == null) return;
            Map<Long, BigDecimal> points = new LinkedHashMap<>();
            for (Item i : items) {
                if (i.getIteration() == null || !i.getIteration().getId().equals(it.getId()) || i.getEstimate() == null) continue;
                List<ItemOwner> owners = ownerRepo.findByItemId(i.getId());
                if (owners.isEmpty()) continue;
                BigDecimal share = i.getEstimate().divide(BigDecimal.valueOf(owners.size()), 4, RoundingMode.HALF_UP);
                for (ItemOwner o : owners) points.merge(o.getUser().getId(), share, BigDecimal::add);
            }
            for (TeamMember m : memberRepo.findByTeamId(team.getId())) {
                int remainingDays = 0;
                for (LocalDate d = today.isBefore(it.getStartDate()) ? it.getStartDate() : today;
                     !d.isAfter(it.getEndDate()); d = d.plusDays(1)) {
                    if (!Workdays.isWorkday(d, settings)) continue;
                    if (m.isAwayOn(d)) continue;
                    remainingDays++;
                }
                BigDecimal capacity = settings.capacityPointsPerWorkday.multiply(BigDecimal.valueOf(remainingDays));
                BigDecimal mine = points.getOrDefault(m.getUser().getId(), BigDecimal.ZERO);
                if (mine.compareTo(capacity) > 0) {
                    out.add(new RiskSignal("OVERLOAD", "MEDIUM", null, null, m.getUser().getId(),
                            m.getUser().getDisplayName() + " 剩余故事点 " + mine.stripTrailingZeros().toPlainString()
                                    + " 超过剩余容量 " + capacity.stripTrailingZeros().toPlainString()));
                }
            }
        });
        return out;
    }
}
