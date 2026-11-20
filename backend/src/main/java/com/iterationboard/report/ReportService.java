package com.iterationboard.report;

import com.iterationboard.collab.StandupService;
import com.iterationboard.common.ApiException;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.reminder.RiskService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.TeamRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * 报告与导出：站会摘要 / 周报 / 迭代总结 / 个人周报 / 风险摘要。
 * 本阶段（M3）报告正文由程序按结构化数据生成（aiGenerated=false），可在线编辑；
 * AI 叙述生成属 M4，不在本期范围。所有数字取自真实事项数据，不估算。
 */
@Service
public class ReportService {
    private final ReportRepo reportRepo;
    private final ReportVersionRepo versionRepo;
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final TeamMemberRepo memberRepo;
    private final ProgressRepo progressRepo;
    private final IterationRepo iterationRepo;
    private final TeamService teamService;
    private final RiskService riskService;
    private final StandupService standupService;
    private final UserRepo userRepo;
    private final TeamRepo teamRepo;
    private final NotifyService notifyService;
    private final com.iterationboard.common.AuditService auditService;
    private final org.springframework.beans.factory.ObjectProvider<ReportNarrator> narratorProvider;
    private final PlatformTime platformTime;

    public ReportService(ReportRepo reportRepo, ReportVersionRepo versionRepo, ItemRepo itemRepo, ItemOwnerRepo ownerRepo,
                         TeamMemberRepo memberRepo, ProgressRepo progressRepo, IterationRepo iterationRepo,
                         TeamService teamService, RiskService riskService, StandupService standupService,
                         UserRepo userRepo, TeamRepo teamRepo, NotifyService notifyService,
                         com.iterationboard.common.AuditService auditService,
                         org.springframework.beans.factory.ObjectProvider<ReportNarrator> narratorProvider, PlatformTime platformTime) {
        this.reportRepo = reportRepo;
        this.versionRepo = versionRepo;
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.memberRepo = memberRepo;
        this.progressRepo = progressRepo;
        this.iterationRepo = iterationRepo;
        this.teamService = teamService;
        this.riskService = riskService;
        this.standupService = standupService;
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
        this.notifyService = notifyService;
        this.auditService = auditService;
        this.narratorProvider = narratorProvider;
        this.platformTime = platformTime;
    }

    /**
     * 报告正文（M4 自动汇总）：先由程序算事实表（现有模板即事实表），AI 可用时模型只写叙述，
     * 校验通过才采用（aiGenerated=true）；否则用程序模板（aiGenerated=false）。
     */
    private String narrateOrTemplate(Team team, AppUser actor, Report r, String facts) {
        ReportNarrator narrator = narratorProvider.getIfAvailable();
        if (narrator != null) {
            var n = narrator.narrate(team.getId(), actor, r.getKind(), facts);
            if (n.isPresent()) {
                r.setAiGenerated(true);
                return n.get();
            }
        }
        r.setAiGenerated(false);
        return facts;
    }

    // ----------  权限口径（kind 沿用 M3 字符串） ----------
    // STANDUP_SUMMARY 站会摘要 / WEEKLY 周报 / ITERATION_SUMMARY 迭代总结 / PERSONAL_WEEKLY 我的周报 / RISK_SUMMARY 风险摘要
    public static final java.util.Set<String> KINDS = java.util.Set.of(
            "STANDUP_SUMMARY", "WEEKLY", "ITERATION_SUMMARY", "PERSONAL_WEEKLY", "RISK_SUMMARY");

    static boolean leaderOrPo(TeamMember m) { return m.getRole() == Role.LEADER || m.getRole() == Role.PO; }

    /** 可见：风险仅组长/PO；我的周报仅本人；其余全组。 */
    public boolean canView(Report r, TeamMember me, AppUser user) {
        return switch (r.getKind()) {
            case "RISK_SUMMARY" -> leaderOrPo(me);
            case "PERSONAL_WEEKLY" -> r.getOwner() != null && r.getOwner().getId().equals(user.getId());
            default -> true;
        };
    }

    /** 生成/重新生成：站会/周报/风险=组长/PO，迭代总结=组长，我的周报=本人。 */
    public boolean canGenerate(String kind, TeamMember me, AppUser user, Report existing) {
        return switch (kind) {
            case "ITERATION_SUMMARY" -> me.getRole() == Role.LEADER;
            case "PERSONAL_WEEKLY" -> existing == null || (existing.getOwner() != null
                    && existing.getOwner().getId().equals(user.getId()));
            default -> leaderOrPo(me);
        };
    }

    /** 编辑/删除：与生成口径一致（迭代总结=组长，我的周报=本人，其余组长/PO）。 */
    public boolean canEdit(Report r, TeamMember me, AppUser user) {
        return switch (r.getKind()) {
            case "ITERATION_SUMMARY" -> me.getRole() == Role.LEADER;
            case "PERSONAL_WEEKLY" -> r.getOwner() != null && r.getOwner().getId().equals(user.getId());
            default -> leaderOrPo(me);
        };
    }

    /** Q11：当前用户对该报告可执行的操作键（权限判断只在服务端这一处实现）。 */
    @Transactional(readOnly = true)
    public List<String> allowedActions(Report r, AppUser user) {
        TeamMember me = teamService.requireMember(r.getTeam().getId(), user);
        List<String> out = new java.util.ArrayList<>();
        boolean deleted = r.getDeletedAt() != null;
        if (canView(r, me, user)) {
            out.add("VIEW");
            if (!deleted) out.add("EXPORT");
        }
        if (canEdit(r, me, user)) {
            if (deleted) out.add("RESTORE");
            else { out.add("EDIT"); out.add("DELETE"); }
        }
        if (!deleted && canGenerate(r.getKind(), me, user, r)) out.add("REGENERATE");
        return out;
    }

    private List<Item> teamItems(Long teamId) {
        return itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK)
                .toList();
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    private String memberName(Long teamId, Long userId) {
        return memberRepo.findByTeamIdAndUserId(teamId, userId)
                .map(m -> m.getUser().getDisplayName()).orElse("成员 " + userId);
    }

    public record GenResult(Report report, boolean existed, boolean restored) {
        public GenResult(Report report, boolean existed) { this(report, existed, false); }
    }

    private Optional<Report> findExisting(Long teamId, String kind, AppUser owner,
                                          LocalDate from, LocalDate to, Long iterationId) {
        if ("ITERATION_SUMMARY".equals(kind) && iterationId != null) {
            return reportRepo.findFirstByTeamIdAndKindAndIterationIdOrderByIdAsc(teamId, kind, iterationId);
        }
        if ("PERSONAL_WEEKLY".equals(kind) && owner != null) {
            return reportRepo.findFirstByTeamIdAndKindAndOwnerIdAndPeriodStartAndPeriodEndOrderByIdAsc(
                    teamId, kind, owner.getId(), from, to);
        }
        return reportRepo.findFirstByTeamIdAndKindAndPeriodStartAndPeriodEndOrderByIdAsc(teamId, kind, from, to);
    }

    private String buildContent(Team team, AppUser actor, Report r) {
        return switch (r.getKind()) {
            case "WEEKLY" -> buildWeekly(team, r.getPeriodStart(), r.getPeriodEnd());
            case "PERSONAL_WEEKLY" -> buildPersonalWeekly(team,
                    r.getOwner() != null ? r.getOwner() : actor, r.getPeriodStart(), r.getPeriodEnd());
            case "STANDUP_SUMMARY" -> buildStandupSummary(team.getId(), actor, r.getPeriodStart());
            case "RISK_SUMMARY" -> buildRiskSummary(team, r.getPeriodStart());
            case "ITERATION_SUMMARY" -> {
                Iteration it = r.getIterationId() == null ? null
                        : iterationRepo.findById(r.getIterationId()).orElse(null);
                yield it == null ? "# 迭代总结\n" : buildIterationSummary(team, it);
            }
            default -> throw ApiException.validation("未知报告类型 " + r.getKind());
        };
    }

    private String titleOf(Report r, AppUser actor) {
        return switch (r.getKind()) {
            case "WEEKLY" -> "周报 · " + r.getPeriodStart() + " ~ " + r.getPeriodEnd();
            case "PERSONAL_WEEKLY" -> "个人周报 · " + (r.getOwner() != null ? r.getOwner().getDisplayName() : actor.getDisplayName())
                    + " · " + r.getPeriodStart() + " ~ " + r.getPeriodEnd();
            case "STANDUP_SUMMARY" -> "站会摘要 · " + r.getPeriodStart();
            case "RISK_SUMMARY" -> "风险摘要 · " + r.getPeriodStart();
            case "ITERATION_SUMMARY" -> {
                Iteration it = r.getIterationId() == null ? null : iterationRepo.findById(r.getIterationId()).orElse(null);
                yield "迭代总结 · " + (it == null ? "" : it.getName());
            }
            default -> r.getKind();
        };
    }

    /**
     * 生成（）：一个周期只有一份——该周期已有未删报告时不新建，返回 existed=true；
     * 已有墓碑（已删）时同样返回 existed=true，定时任务据此跳过。拒绝未来周期。
     */
    @Transactional
    public GenResult generate(Long teamId, AppUser user, String kind, LocalDate periodStart, LocalDate periodEnd) {
        return generate(teamId, user, kind, periodStart, periodEnd, null);
    }

    @Transactional
    public GenResult generate(Long teamId, AppUser user, String kind, LocalDate periodStart, LocalDate periodEnd,
                              Long iterationIdParam) {
        TeamMember me = teamService.requireMember(teamId, user);
        Team team = me.getTeam();
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiException.validation("kind 须为 STANDUP_SUMMARY / WEEKLY / ITERATION_SUMMARY / PERSONAL_WEEKLY / RISK_SUMMARY");
        }
        if (!canGenerate(kind, me, user, null)) {
            throw ApiException.forbidden("无权生成该类报告（站会/周报/风险=组长/PO，迭代总结=组长，我的周报=本人）");
        }
        LocalDate today = platformTime.today();
        Report r = new Report();
        r.setTeam(team);
        r.setKind(kind);
        r.setAiGenerated(false);
        switch (kind) {
            case "WEEKLY", "PERSONAL_WEEKLY" -> {
                LocalDate from = periodStart != null ? periodStart : today.with(DayOfWeek.MONDAY);
                LocalDate to = periodEnd != null ? periodEnd : from.plusDays(6);
                if (to.isBefore(from)) throw ApiException.validation("周期结束不能早于开始");
                if (from.isAfter(today)) throw ApiException.validation("不能生成未来周期的报告");
                r.setPeriodStart(from);
                r.setPeriodEnd(to);
                if ("PERSONAL_WEEKLY".equals(kind)) r.setOwner(user);
            }
            case "STANDUP_SUMMARY", "RISK_SUMMARY" -> {
                LocalDate date = periodStart != null ? periodStart : today;
                if (date.isAfter(today)) throw ApiException.validation("不能生成未来周期的报告");
                r.setPeriodStart(date);
                r.setPeriodEnd(date);
            }
            case "ITERATION_SUMMARY" -> {
                // Q7：迭代总结按 iterationId 唯一；可显式指定迭代，未指定时取进行中或最近结束的迭代
                Iteration it = iterationIdParam != null
                        ? iterationRepo.findById(iterationIdParam)
                                .filter(x -> x.getTeam().getId().equals(teamId))
                                .orElseThrow(() -> ApiException.validation("迭代不存在"))
                        : iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE")
                                .orElseGet(() -> iterationRepo.findByTeamIdOrderByStartDateDescIdDesc(teamId).stream()
                                        .filter(x -> "FINISHED".equals(x.getStatus())).findFirst()
                                        .orElseThrow(() -> ApiException.validation("没有可总结的迭代（需要进行中或已结束的迭代）")));
                r.setPeriodStart(it.getStartDate());
                r.setPeriodEnd(it.getEndDate());
                r.setIterationId(it.getId());
            }
            default -> throw ApiException.validation("未知报告类型");
        }
        Optional<Report> existing = findExisting(teamId, kind, r.getOwner(), r.getPeriodStart(), r.getPeriodEnd(), r.getIterationId());
        if (existing.isPresent()) {
            Report ex = existing.get();
            if (ex.getDeletedAt() == null) return new GenResult(ex, true);
            // Q1：墓碑报告被有生成权者手动「生成」= 有意恢复——原地重生并写审计日志；
            // 定时任务走 autoGenerate* 路径，仍按 findExisting 跳过墓碑，不受影响。
            ex.setDeletedAt(null);
            ex.setDeletedById(null);
            ex.setContent(narrateOrTemplate(team, user, ex, buildContent(team, user, ex)));
            ex.setEditedByHuman(false);
            ex.setLastGeneratedAt(Instant.now());
            Report saved = reportRepo.save(ex);
            auditService.log(teamId, user, "REPORT_REGENERATE_FROM_TRASH", "REPORT", saved.getId(),
                    "手动生成恢复已删报告 kind=" + kind + " period=" + saved.getPeriodStart() + "~" + saved.getPeriodEnd());
            return new GenResult(saved, false, true);
        }
        r.setTitle(titleOf(r, user));
        r.setContent(narrateOrTemplate(team, user, r, buildContent(team, user, r)));
        r.setLastGeneratedAt(Instant.now());
        Report saved = reportRepo.save(r);
        auditService.log(teamId, user, "GENERATE_REPORT", "REPORT", saved.getId(),
                "生成报告 kind=" + kind + " period=" + saved.getPeriodStart() + "~" + saved.getPeriodEnd());
        return new GenResult(saved, false);
    }

    public record Preview(String content, boolean aiGenerated) {}

    /** POST /ai/summarize 预览/自测入口：与正式生成同一条事实表+叙述路径，但不落库。 */
    @Transactional // D-1：预览经 AiGateway 写 ai_call_log，必须可写事务（原只读事务使 /ai/summarize 直调 500）
    public Preview preview(Long teamId, AppUser user, String kind, LocalDate periodStart, LocalDate periodEnd, Long iterationId) {
        TeamMember me = teamService.requireMember(teamId, user);
        Team team = me.getTeam();
        if (kind == null || !KINDS.contains(kind)) throw ApiException.validation("kind 不合法");
        if (!canGenerate(kind, me, user, null)) throw ApiException.forbidden("无权生成该类报告");
        LocalDate today = platformTime.today();
        Report r = new Report();
        r.setTeam(team);
        r.setKind(kind);
        switch (kind) {
            case "WEEKLY", "PERSONAL_WEEKLY" -> {
                LocalDate from = periodStart != null ? periodStart : today.with(DayOfWeek.MONDAY);
                LocalDate to = periodEnd != null ? periodEnd : from.plusDays(6);
                if (from.isAfter(today)) throw ApiException.validation("不能生成未来周期的报告");
                r.setPeriodStart(from);
                r.setPeriodEnd(to);
                if ("PERSONAL_WEEKLY".equals(kind)) r.setOwner(user);
            }
            case "STANDUP_SUMMARY", "RISK_SUMMARY" -> {
                LocalDate date = periodStart != null ? periodStart : today;
                if (date.isAfter(today)) throw ApiException.validation("不能生成未来周期的报告");
                r.setPeriodStart(date);
                r.setPeriodEnd(date);
            }
            case "ITERATION_SUMMARY" -> {
                Iteration it = iterationId != null
                        ? iterationRepo.findById(iterationId).filter(x -> x.getTeam().getId().equals(teamId))
                                .orElseThrow(() -> ApiException.validation("迭代不存在"))
                        : iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE")
                                .orElseGet(() -> iterationRepo.findByTeamIdOrderByStartDateDescIdDesc(teamId).stream()
                                        .filter(x -> "FINISHED".equals(x.getStatus())).findFirst()
                                        .orElseThrow(() -> ApiException.validation("没有可总结的迭代")));
                r.setPeriodStart(it.getStartDate());
                r.setPeriodEnd(it.getEndDate());
                r.setIterationId(it.getId());
            }
            default -> throw ApiException.validation("未知报告类型");
        }
        String facts = buildContent(team, user, r);
        String content = narrateOrTemplate(team, user, r, facts);
        return new Preview(content, r.isAiGenerated());
    }

    /** 迭代总结：结束迭代时自动生成，也可手动生成。 */
    @Transactional
    public Report generateIterationSummary(Team team, Iteration it, AppUser actor) {
        Optional<Report> existing = reportRepo.findFirstByTeamIdAndKindAndIterationIdOrderByIdAsc(
                team.getId(), "ITERATION_SUMMARY", it.getId());
        if (existing.isPresent()) return existing.get();
        Report r = new Report();
        r.setTeam(team);
        r.setKind("ITERATION_SUMMARY");
        r.setAiGenerated(false);
        r.setPeriodStart(it.getStartDate());
        r.setPeriodEnd(it.getEndDate());
        r.setIterationId(it.getId());
        r.setTitle("迭代总结 · " + it.getName());
        r.setContent(narrateOrTemplate(team, actor, r, buildIterationSummary(team, it)));
        r.setLastGeneratedAt(Instant.now());
        return reportRepo.save(r);
    }

    private String buildIterationSummary(Team team, Iteration it) {
        List<Item> items = teamItems(team.getId()).stream()
                .filter(i -> i.getIteration() != null && i.getIteration().getId().equals(it.getId()))
                .toList();
        long done = items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
        long cancelled = items.stream().filter(i -> i.getStatus() == ItemStatus.CANCELLED).count();
        long total = items.size() - cancelled;
        int rate = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        StringBuilder sb = new StringBuilder();
        sb.append("# 迭代总结 · ").append(it.getName()).append("\n\n");
        if (it.getGoal() != null && !it.getGoal().isBlank()) sb.append("迭代目标：").append(it.getGoal()).append("\n\n");
        sb.append("周期：").append(it.getStartDate()).append(" ~ ").append(it.getEndDate())
          .append("｜状态：").append(it.getStatus()).append("\n\n");
        sb.append("## 完成情况\n\n");
        sb.append("- 事项总数（不含取消）：").append(total).append("\n");
        sb.append("- 已完成：").append(done).append("（完成率 ").append(rate).append("%）\n");
        sb.append("- 已取消：").append(cancelled).append("\n\n");
        sb.append("## 已完成事项\n\n");
        items.stream().filter(i -> i.getStatus() == ItemStatus.DONE)
                .forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle()).append("\n"));
        List<Item> unfinished = items.stream()
                .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED).toList();
        if (!unfinished.isEmpty()) {
            sb.append("\n## 未完成事项（已按结转决定处理）\n\n");
            unfinished.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle())
                    .append("（").append(i.getStatus()).append("）\n"));
        }
        sb.append("\n## 各成员完成件数\n\n");
        Map<Long, Long> doneByOwner = new java.util.LinkedHashMap<>();
        items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).forEach(i ->
                ownerRepo.findByItemId(i.getId()).forEach(o -> doneByOwner.merge(o.getUser().getId(), 1L, Long::sum)));
        if (doneByOwner.isEmpty()) sb.append("（无）\n");
        doneByOwner.forEach((uid, c) -> sb.append("- ").append(memberName(team.getId(), uid)).append("：").append(c).append(" 件\n"));
        // 修订四 P13：迭代外工作单列——独立事项（缺陷/分析/临时事项，不属于任何迭代）计入个人负载与总览，
        // 不计入燃尽图与上面的迭代完成率，此处单独汇总。
        List<Item> outside = teamItems(team.getId()).stream()
                .filter(i -> i.getIteration() == null)
                .filter(i -> i.getType() == ItemType.DEFECT || i.getType() == ItemType.ANALYSIS || i.getType() == ItemType.ADHOC)
                .toList();
        sb.append("\n## 迭代外工作（独立事项，不计入燃尽图）\n\n");
        if (outside.isEmpty()) {
            sb.append("（无）\n");
        } else {
            long outsideDone = outside.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
            sb.append("- 独立事项共 ").append(outside.size()).append(" 件，已完成 ").append(outsideDone).append(" 件\n");
            outside.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle())
                    .append("（").append(i.getType()).append(" / ").append(i.getStatus()).append("）\n"));
        }
        return sb.toString();
    }

    private String buildWeekly(Team team, LocalDate from, LocalDate to) {
        List<Item> items = teamItems(team.getId());
        List<Item> doneInPeriod = items.stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE && inRange(RiskService.dateOf(i.getStatusChangedAt()), from, to))
                .toList();
        List<Item> createdInPeriod = items.stream()
                .filter(i -> inRange(RiskService.dateOf(i.getCreatedAt()), from, to)).toList();
        List<Long> ids = items.stream().map(Item::getId).toList();
        long progressCount = ids.isEmpty() ? 0 : progressRepo.findByItemIdIn(ids).stream()
                .filter(p -> inRange(RiskService.dateOf(p.getCreatedAt()), from, to)).count();
        List<Item> blocked = items.stream().filter(i -> i.isBlocked()
                && i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("# 周报 · ").append(from).append(" ~ ").append(to).append("\n\n");
        sb.append("- 本期完成：").append(doneInPeriod.size()).append(" 件\n");
        sb.append("- 本期新建：").append(createdInPeriod.size()).append(" 件\n");
        sb.append("- 本期进展登记：").append(progressCount).append(" 条\n");
        sb.append("- 当前阻塞：").append(blocked.size()).append(" 件\n\n");
        sb.append("## 本期完成\n\n");
        if (doneInPeriod.isEmpty()) sb.append("（无）\n");
        doneInPeriod.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle()).append("\n"));
        if (!blocked.isEmpty()) {
            sb.append("\n## 当前阻塞\n\n");
            blocked.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle())
                    .append("：").append(i.getBlockReason() == null ? "" : i.getBlockReason()).append("\n"));
        }
        return sb.toString();
    }

    private String buildPersonalWeekly(Team team, AppUser user, LocalDate from, LocalDate to) {
        List<Item> items = teamItems(team.getId());
        java.util.Set<Long> ownedIds = new java.util.HashSet<>(
                ownerRepo.findByUserId(user.getId()).stream().map(o -> o.getItem().getId()).toList());
        List<Item> mine = items.stream().filter(i -> ownedIds.contains(i.getId())).toList();
        List<Item> doneInPeriod = mine.stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE && inRange(RiskService.dateOf(i.getStatusChangedAt()), from, to))
                .toList();
        List<Item> inProgress = mine.stream().filter(i -> i.getStatus() == ItemStatus.IN_PROGRESS).toList();
        List<Long> ids = items.stream().map(Item::getId).toList();
        long myProgress = ids.isEmpty() ? 0 : progressRepo.findByItemIdIn(ids).stream()
                .filter(p -> p.getAuthor().getId().equals(user.getId()) && inRange(RiskService.dateOf(p.getCreatedAt()), from, to))
                .count();
        StringBuilder sb = new StringBuilder();
        sb.append("# 个人周报 · ").append(user.getDisplayName()).append(" · ").append(from).append(" ~ ").append(to).append("\n\n");
        sb.append("- 本期完成：").append(doneInPeriod.size()).append(" 件\n");
        sb.append("- 本期进展登记：").append(myProgress).append(" 条\n");
        sb.append("- 当前进行中：").append(inProgress.size()).append(" 件\n\n");
        sb.append("## 本期完成\n\n");
        if (doneInPeriod.isEmpty()) sb.append("（无）\n");
        doneInPeriod.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle()).append("\n"));
        if (!inProgress.isEmpty()) {
            sb.append("\n## 进行中\n\n");
            inProgress.forEach(i -> sb.append("- ").append(i.getCode()).append(" ").append(i.getTitle())
                    .append("（进度 ").append(i.getProgressPercent()).append("%）\n"));
        }
        return sb.toString();
    }

    private String buildStandupSummary(Long teamId, AppUser user, LocalDate date) {
        Map<String, Object> standup = standupService.standup(teamId, user, date);
        StringBuilder sb = new StringBuilder();
        sb.append("# 站会摘要 · ").append(date).append("\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) standup.get("members");
        for (Map<String, Object> m : members) {
            Long uid = (Long) m.get("userId");
            sb.append("## ").append(memberName(teamId, uid)).append("\n\n");
            @SuppressWarnings("unchecked")
            List<com.iterationboard.config.Dtos.ProgressDto> progress =
                    (List<com.iterationboard.config.Dtos.ProgressDto>) m.get("yesterdayProgress");
            sb.append("昨日进展：");
            if (progress == null || progress.isEmpty()) sb.append("（无登记）\n");
            else progress.forEach(p -> sb.append("\n- ").append(p.text()));
            sb.append("\n");
            @SuppressWarnings("unchecked")
            List<com.iterationboard.config.Dtos.ItemDto> blocked =
                    (List<com.iterationboard.config.Dtos.ItemDto>) m.get("blockedItems");
            if (blocked != null && !blocked.isEmpty()) {
                sb.append("阻塞：");
                blocked.forEach(i -> sb.append("\n- ").append(i.code()).append(" ").append(i.title()));
                sb.append("\n");
            }
            sb.append("\n");
        }
        Object note = standup.get("note");
        if (note != null && !note.toString().isBlank()) sb.append("## 站会备注\n\n").append(note).append("\n");
        return sb.toString();
    }

    private String buildRiskSummary(Team team, LocalDate today) {
        List<RiskService.RiskSignal> risks = riskService.computeRisks(team, today);
        StringBuilder sb = new StringBuilder();
        sb.append("# 风险摘要 · ").append(today).append("\n\n");
        if (risks.isEmpty()) {
            sb.append("当前没有触发任何风险规则。\n");
            return sb.toString();
        }
        for (String sev : List.of("HIGH", "MEDIUM", "LOW")) {
            List<RiskService.RiskSignal> group = risks.stream().filter(r -> r.severity().equals(sev)).toList();
            if (group.isEmpty()) continue;
            sb.append("## ").append(switch (sev) { case "HIGH" -> "高"; case "MEDIUM" -> "中"; default -> "低"; })
              .append("风险（").append(group.size()).append("）\n\n");
            group.forEach(r -> sb.append("- ").append(r.message()).append("\n"));
            sb.append("\n");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<Report> list(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        return reportRepo.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .filter(r -> r.getDeletedAt() == null)
                .filter(r -> canView(r, me, user))
                .toList();
    }

    @Transactional(readOnly = true)
    public Report get(Long teamId, Long reportId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = reportRepo.findById(reportId)
                .filter(x -> x.getTeam().getId().equals(teamId))
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("报告不存在"));
        if (!canView(r, me, user)) throw ApiException.notFound("报告不存在");
        return r;
    }

    private Report requireRaw(Long teamId, Long reportId) {
        return reportRepo.findById(reportId)
                .filter(x -> x.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("报告不存在"));
    }

    /** 编辑（ 编辑权表 + 版本冲突）；version 为空时不校验（兼容 M3 调用）。 */
    @Transactional
    public Report patch(Long teamId, Long reportId, AppUser user, String title, String contentMd, Integer version) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = requireRaw(teamId, reportId);
        if (r.getDeletedAt() != null) throw ApiException.notFound("报告不存在");
        if (!canEdit(r, me, user)) throw ApiException.forbidden("无权编辑该报告（按 编辑权表）");
        if (version != null && version != r.getVersion()) {
            throw ApiException.versionConflict("报告已被他人修改，请刷新后重试");
        }
        if (title != null) {
            if (title.isBlank() || title.length() > 200) throw ApiException.validation("报告标题不能为空且不超过 200 字");
            r.setTitle(title);
        }
        if (contentMd != null) r.setContent(contentMd);
        r.setEditedByHuman(true);
        return reportRepo.save(r);
    }

    /** 重新生成：覆盖前存历史版本（最多 5 个）；距上次生成 <60 秒返回 409 REGEN_TOO_FREQUENT。 */
    @Transactional
    public Report regenerate(Long teamId, Long reportId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = requireRaw(teamId, reportId);
        if (r.getDeletedAt() != null) throw ApiException.notFound("报告不存在");
        if (!canGenerate(r.getKind(), me, user, r)) throw ApiException.forbidden("无权重新生成该报告");
        if (r.getLastGeneratedAt() != null
                && Instant.now().isBefore(r.getLastGeneratedAt().plusSeconds(60))) {
            throw new ApiException(HttpStatus.CONFLICT, "REGEN_TOO_FREQUENT", "同一份报告 1 分钟内只能重新生成一次");
        }
        saveVersion(r);
        r.setContent(narrateOrTemplate(r.getTeam(), user, r, buildContent(r.getTeam(), user, r)));
        r.setEditedByHuman(false);
        r.setLastGeneratedAt(Instant.now());
        return reportRepo.save(r);
    }

    private void saveVersion(Report r) {
        versionRepo.save(new ReportVersion(r));
        List<ReportVersion> all = versionRepo.findByReportIdOrderByCreatedAtDescIdDesc(r.getId());
        for (int i = 5; i < all.size(); i++) versionRepo.delete(all.get(i));
    }

    @Transactional(readOnly = true)
    public List<ReportVersion> versions(Long teamId, Long reportId, AppUser user) {
        get(teamId, reportId, user);
        return versionRepo.findByReportIdOrderByCreatedAtDescIdDesc(reportId);
    }

    @Transactional(readOnly = true)
    public ReportVersion version(Long teamId, Long reportId, Long versionId, AppUser user) {
        get(teamId, reportId, user);
        return versionRepo.findByIdAndReportId(versionId, reportId)
                .orElseThrow(() -> ApiException.notFound("历史版本不存在"));
    }

    /** 恢复历史版本：恢复前同样把当前内容存版，恢复后 editedByHuman=true。 */
    @Transactional
    public Report restoreVersion(Long teamId, Long reportId, Long versionId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = requireRaw(teamId, reportId);
        if (r.getDeletedAt() != null) throw ApiException.notFound("报告不存在");
        if (!canEdit(r, me, user)) throw ApiException.forbidden("无权恢复该报告的历史版本");
        ReportVersion v = versionRepo.findByIdAndReportId(versionId, reportId)
                .orElseThrow(() -> ApiException.notFound("历史版本不存在"));
        saveVersion(r);
        r.setTitle(v.getTitle());
        r.setContent(v.getContent());
        r.setEditedByHuman(true);
        return reportRepo.save(r);
    }

    /** 软删除（回收站 30 天口径）：保留行作为墓碑，定时任务不得再生成该周期那一份。 */
    @Transactional
    public void softDelete(Long teamId, Long reportId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = requireRaw(teamId, reportId);
        if (r.getDeletedAt() != null) throw ApiException.notFound("报告不存在");
        if (!canEdit(r, me, user)) throw ApiException.forbidden("无权删除该报告（按 编辑权表）");
        r.setDeletedAt(Instant.now());
        r.setDeletedById(user.getId());
        reportRepo.save(r);
        auditService.log(teamId, user, "DELETE_REPORT", "REPORT", r.getId(),
                "删除报告 kind=" + r.getKind() + " period=" + r.getPeriodStart() + "~" + r.getPeriodEnd());
    }

    @Transactional(readOnly = true)
    public List<Report> trash(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        return reportRepo.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .filter(r -> r.getDeletedAt() != null)
                .filter(r -> canEdit(r, me, user))
                .toList();
    }

    @Transactional
    public Report restore(Long teamId, Long reportId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        Report r = requireRaw(teamId, reportId);
        if (r.getDeletedAt() == null) throw ApiException.validation("报告不在回收站中");
        if (!canEdit(r, me, user)) throw ApiException.forbidden("无权恢复该报告");
        r.setDeletedAt(null);
        r.setDeletedById(null);
        return reportRepo.save(r);
    }

    // ---------- 定时自动生成：已存在（含墓碑）则跳过 ----------

    /** 工作日 9:00：当日站会摘要（不通知）+ 风险摘要（通知组长/PO）。返回新建份数。 */
    @Transactional
    public int autoGenerateDaily(LocalDate date) {
        int created = 0;
        for (Team team : teamRepo.findAll()) {
            AppUser leader = leaderUser(team.getId());
            if (leader == null) continue;
            for (String kind : List.of("STANDUP_SUMMARY", "RISK_SUMMARY")) {
                if (findExisting(team.getId(), kind, null, date, date, null).isPresent()) continue;
                Report r = new Report();
                r.setTeam(team);
                r.setKind(kind);
                r.setAiGenerated(false);
                r.setPeriodStart(date);
                r.setPeriodEnd(date);
                r.setTitle(titleOf(r, leader));
                r.setContent(narrateOrTemplate(team, leader, r, buildContent(team, leader, r)));
                r.setLastGeneratedAt(Instant.now());
                reportRepo.save(r);
                created++;
                if ("RISK_SUMMARY".equals(kind)) {
                    for (TeamMember m : memberRepo.findByTeamId(team.getId())) {
                        if (m.getRole() == Role.LEADER || m.getRole() == Role.PO) {
                            notifyService.notifyInTeam(m.getUser(), "REPORT_GENERATED", null, team, null,
                                    "风险摘要已自动生成 · " + date);
                        }
                    }
                }
            }
        }
        return created;
    }

    /** 周五 16:00：当周周报（周一至周日），通知全组。返回新建份数。 */
    @Transactional
    public int autoGenerateWeekly(LocalDate anyDayInWeek) {
        LocalDate from = anyDayInWeek.with(DayOfWeek.MONDAY);
        LocalDate to = from.plusDays(6);
        int created = 0;
        for (Team team : teamRepo.findAll()) {
            AppUser leader = leaderUser(team.getId());
            if (leader == null) continue;
            if (findExisting(team.getId(), "WEEKLY", null, from, to, null).isPresent()) continue;
            Report r = new Report();
            r.setTeam(team);
            r.setKind("WEEKLY");
            r.setAiGenerated(false);
            r.setPeriodStart(from);
            r.setPeriodEnd(to);
            r.setTitle(titleOf(r, leader));
            r.setContent(narrateOrTemplate(team, leader, r, buildContent(team, leader, r)));
            r.setLastGeneratedAt(Instant.now());
            reportRepo.save(r);
            created++;
            for (TeamMember m : memberRepo.findByTeamId(team.getId())) {
                notifyService.notifyInTeam(m.getUser(), "REPORT_GENERATED", null, team, null,
                        "周报已自动生成 · " + from + " ~ " + to);
            }
        }
        return created;
    }

    private AppUser leaderUser(Long teamId) {
        return memberRepo.findByTeamId(teamId).stream()
                .filter(m -> m.getRole() == Role.LEADER)
                .map(TeamMember::getUser).findFirst().orElse(null);
    }
}
