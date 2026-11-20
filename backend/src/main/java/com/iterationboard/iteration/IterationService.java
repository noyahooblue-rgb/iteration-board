package com.iterationboard.iteration;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.ItemService;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import com.iterationboard.team.TeamSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代全生命周期（M3，设计 §7.5 / §12.4）：创建/列表/修改/开始/结束与结转、
 * 燃尽快照与燃尽图数据、关键日期。已结束迭代状态值 FINISHED（契约 Iteration.status 枚举）。
 */
@Service
public class IterationService {
    private final IterationRepo iterationRepo;
    private final ItemRepo itemRepo;
    private final IterationSnapshotRepo snapshotRepo;
    private final KeyDateRepo keyDateRepo;
    private final TeamMemberRepo memberRepo;
    private final TeamService teamService;
    private final NotifyService notifyService;
    private final ItemService itemService;
    private final PlatformTime platformTime;
    private final ReportRepo reportRepo;

    public IterationService(IterationRepo iterationRepo, ItemRepo itemRepo, IterationSnapshotRepo snapshotRepo,
                            KeyDateRepo keyDateRepo, TeamMemberRepo memberRepo, TeamService teamService,
                            NotifyService notifyService, ItemService itemService, ReportRepo reportRepo, PlatformTime platformTime) {
        this.iterationRepo = iterationRepo;
        this.itemRepo = itemRepo;
        this.snapshotRepo = snapshotRepo;
        this.keyDateRepo = keyDateRepo;
        this.memberRepo = memberRepo;
        this.teamService = teamService;
        this.notifyService = notifyService;
        this.itemService = itemService;
        this.reportRepo = reportRepo;
        this.platformTime = platformTime;
    }

    private void requireLeaderOrPo(Long teamId, AppUser user) {
        TeamMember m = teamService.requireMember(teamId, user);
        if (m.getRole() != Role.LEADER && m.getRole() != Role.PO) {
            throw ApiException.forbidden("仅组长或 PO 可管理迭代");
        }
    }

    /** Q3（待问结论）：迭代创建/修改属性/开始/结束/删除仅组长。 */
    private void requireLeader(Long teamId, AppUser user) {
        TeamMember m = teamService.requireMember(teamId, user);
        if (m.getRole() != Role.LEADER) {
            throw ApiException.forbidden("仅组长可管理迭代（创建/修改/开始/结束/删除）");
        }
    }

    /** 删除迭代（Q3 仅组长；§16.2：仅规划中且未放入事项时）。 */
    @Transactional
    public void delete(Long teamId, Long iterationId, AppUser user) {
        requireLeader(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        if (!"PLANNING".equals(it.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETE_BLOCKED", "只有规划中的迭代可以删除");
        }
        boolean hasItems = itemRepo.findByTeamId(teamId).stream()
                .anyMatch(i -> i.getDeletedAt() == null && i.getIteration() != null
                        && i.getIteration().getId().equals(iterationId));
        if (hasItems) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETE_BLOCKED", "迭代中已放入事项，不能删除");
        }
        iterationRepo.delete(it);
    }

    public Iteration requireIteration(Long teamId, Long iterationId) {
        return iterationRepo.findByIdAndTeamId(iterationId, teamId)
                .orElseThrow(() -> ApiException.notFound("迭代不存在"));
    }

    @Transactional(readOnly = true)
    public List<Iteration> list(Long teamId, AppUser user) {
        teamService.requireMember(teamId, user);
        return iterationRepo.findByTeamIdOrderByStartDateDescIdDesc(teamId);
    }

    @Transactional
    public Iteration create(Long teamId, AppUser user, String name, String goal, LocalDate start, LocalDate end) {
        // 修订二 §16.2：迭代创建仅组长
        TeamMember creator = teamService.requireMember(teamId, user);
        if (creator.getRole() != Role.LEADER) throw ApiException.forbidden("仅组长可创建迭代");
        return doCreate(teamId, name, goal, start, end);
    }

    private Iteration doCreate(Long teamId, String name, String goal, LocalDate start, LocalDate end) {
        if (name == null || name.isBlank() || name.length() > 100) throw ApiException.validation("迭代名称不能为空且不超过 100 字");
        if (start != null && end != null && end.isBefore(start)) throw ApiException.validation("结束日期不能早于开始日期");
        // 修订二 §16.2：同时最多一个 PLANNING（新建即 PLANNING）
        if (iterationRepo.findFirstByTeamIdAndStatus(teamId, "PLANNING").isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLANNING_ITERATION_EXISTS", "已有规划中的迭代，同时最多一个");
        }
        Iteration it = new Iteration();
        it.setTeam(teamService.requireTeam(teamId));
        it.setName(name);
        it.setGoal(goal);
        it.setStartDate(start);
        it.setEndDate(end);
        it.setStatus("PLANNING");
        return iterationRepo.save(it);
    }

    @Transactional(readOnly = true)
    public Iteration get(Long teamId, Long iterationId, AppUser user) {
        teamService.requireMember(teamId, user);
        return requireIteration(teamId, iterationId);
    }

    @Transactional
    public Iteration patch(Long teamId, Long iterationId, AppUser user, String name, String goal,
                           LocalDate start, boolean startPresent, LocalDate end, boolean endPresent) {
        requireLeader(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        if ("FINISHED".equals(it.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "已结束的迭代不能修改");
        }
        if (name != null) {
            if (name.isBlank() || name.length() > 100) throw ApiException.validation("迭代名称不能为空且不超过 100 字");
            it.setName(name);
        }
        if (goal != null) it.setGoal(goal);
        if (startPresent) it.setStartDate(start);
        if (endPresent) it.setEndDate(end);
        if (it.getStartDate() != null && it.getEndDate() != null && it.getEndDate().isBefore(it.getStartDate())) {
            throw ApiException.validation("结束日期不能早于开始日期");
        }
        return iterationRepo.save(it);
    }

    @Transactional
    public Iteration start(Long teamId, Long iterationId, AppUser user) {
        requireLeader(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        if (!"PLANNING".equals(it.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "只有未开始的迭代可以开始");
        }
        if (iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE").isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_ITERATION_EXISTS", "已有进行中的迭代，请先结束它");
        }
        it.setStatus("ACTIVE");
        iterationRepo.save(it);
        takeSnapshot(it, platformTime.today());
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            notifyService.notifyInTeam(m.getUser(), "ITERATION_STARTED", null, it.getTeam(), user,
                    "迭代「" + it.getName() + "」已开始");
        }
        return it;
    }

    public record CloseDecision(Long itemId, String action) {}
    public record CloseResult(Dtos.IterationDto iteration, Long reportId, Long nextIterationId,
                              int movedToNext, int movedToBacklog, int improvementCreated, boolean existing) {}

    @Transactional
    public CloseResult close(Long teamId, Long iterationId, AppUser user, List<CloseDecision> decisions,
                             List<String> improvements, Boolean createNextIteration,
                             com.iterationboard.report.ReportService reportService) {
        requireLeader(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        if ("FINISHED".equals(it.getStatus())) {
            // 修订二 §16.3：结束迭代只能生效一次——重复 close 返回已生成结果，不再重复生成改进项/总结
            Long existingReportId = reportRepo.findFirstByTeamIdAndKindAndIterationIdOrderByIdAsc(
                    teamId, "ITERATION_SUMMARY", iterationId).map(com.iterationboard.domain.Report::getId).orElse(null);
            return new CloseResult(Dtos.IterationDto.of(it), existingReportId, null, 0, 0, 0, true);
        }
        if (!"ACTIVE".equals(it.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", "只有进行中的迭代可以结束");
        }
        Team team = it.getTeam();
        Map<Long, String> decisionMap = new LinkedHashMap<>();
        if (decisions != null) {
            for (CloseDecision d : decisions) {
                if (!List.of("NEXT", "BACKLOG").contains(d.action())) {
                    throw ApiException.validation("结转动作须为 NEXT / BACKLOG");
                }
                decisionMap.put(d.itemId(), d.action());
            }
        }
        List<Item> unfinished = iterationItems(it).stream()
                .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                .toList();
        boolean anyNext = unfinished.stream()
                .anyMatch(i -> "NEXT".equals(decisionMap.getOrDefault(i.getId(), "NEXT")));
        Iteration next = null;
        if (anyNext) {
            next = iterationRepo.findByTeamIdOrderByStartDateDescIdDesc(teamId).stream()
                    .filter(x -> !x.getId().equals(iterationId) && "PLANNING".equals(x.getStatus()))
                    .min(java.util.Comparator.comparing(Iteration::getId))
                    .orElse(null);
            if (next == null) {
                if (!Boolean.TRUE.equals(createNextIteration)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NEXT_ITERATION_REQUIRED",
                            "存在结转到下一迭代的事项，但没有未开始的迭代；请先创建，或传 createNextIteration=true");
                }
                LocalDate ns = it.getEndDate() == null ? platformTime.today() : it.getEndDate().plusDays(1);
                int weeks = Math.max(1, team.getIterationWeeks());
                next = doCreate(teamId, it.getName() + "（续）", it.getGoal(), ns, ns.plusWeeks(weeks).minusDays(1));
            }
        }
        // 先拍关闭快照（反映结转前的燃尽终点），再执行结转
        takeSnapshot(it, platformTime.today());
        int toNext = 0, toBacklog = 0;
        for (Item item : unfinished) {
            String action = decisionMap.getOrDefault(item.getId(), "NEXT");
            if ("BACKLOG".equals(action)) {
                item.setIteration(null);
                toBacklog++;
            } else {
                item.setIteration(next);
                toNext++;
            }
            itemRepo.save(item);
        }
        int improvementCreated = 0;
        if (improvements != null) {
            for (String title : improvements) {
                if (title == null || title.isBlank()) continue;
                itemService.create(teamId, user, new ItemService.CreateItemCmd(
                        ItemType.ADHOC, title, "迭代复盘改进项", null, "POOL",
                        null, null, null, null, null, null,
                        next == null ? null : next.getId(), List.of("改进项"), null, null));
                improvementCreated++;
            }
        }
        it.setStatus("FINISHED");
        it.setClosedAt(Instant.now());
        iterationRepo.save(it);
        Report report = reportService.generateIterationSummary(team, it, user);
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            notifyService.notifyInTeam(m.getUser(), "ITERATION_FINISHED", null, team, user,
                    "迭代「" + it.getName() + "」已结束，总结报告已生成");
        }
        return new CloseResult(Dtos.IterationDto.of(it), report.getId(),
                next == null ? null : next.getId(), toNext, toBacklog, improvementCreated, false);
    }

    /** 迭代内事项（非任务、未删除）。 */
    public List<Item> iterationItems(Iteration it) {
        return itemRepo.findByTeamId(it.getTeam().getId()).stream()
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> i.getType() != ItemType.TASK)
                .filter(i -> i.getIteration() != null && i.getIteration().getId().equals(it.getId()))
                .toList();
    }

    /** 燃尽快照：当日剩余估算/件数与完成件数（同日重复调用覆盖）。 */
    @Transactional
    public IterationSnapshot takeSnapshot(Iteration it, LocalDate date) {
        List<Item> items = iterationItems(it);
        BigDecimal remaining = BigDecimal.ZERO;
        int remainingCount = 0, doneCount = 0;
        for (Item i : items) {
            if (i.getStatus() == ItemStatus.DONE) { doneCount++; continue; }
            if (i.getStatus() == ItemStatus.CANCELLED) continue;
            remainingCount++;
            if (i.getEstimate() != null) remaining = remaining.add(i.getEstimate());
        }
        IterationSnapshot snap = snapshotRepo.findByIterationIdAndSnapshotDate(it.getId(), date)
                .orElseGet(() -> {
                    IterationSnapshot s = new IterationSnapshot();
                    s.setIteration(it);
                    s.setSnapshotDate(date);
                    return s;
                });
        snap.setRemainingEstimate(remaining);
        snap.setRemainingCount(remainingCount);
        snap.setDoneCount(doneCount);
        return snapshotRepo.save(snap);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> burndown(Long teamId, Long iterationId, AppUser user) {
        teamService.requireMember(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        TeamSettings settings = TeamSettings.from(it.getTeam());
        List<IterationSnapshot> snaps = snapshotRepo.findByIterationIdOrderBySnapshotDateAsc(iterationId);
        List<Map<String, Object>> points = new ArrayList<>();
        for (IterationSnapshot s : snaps) points.add(pointMap(s.getSnapshotDate(), s.getRemainingEstimate(), s.getRemainingCount(), s.getDoneCount(), false));
        LocalDate today = platformTime.today();
        if ("ACTIVE".equals(it.getStatus())) {
            // 当天实时点（不落库）：快照里还没有今天时补上
            boolean hasToday = snaps.stream().anyMatch(s -> s.getSnapshotDate().equals(today));
            if (!hasToday) {
                List<Item> items = iterationItems(it);
                BigDecimal remaining = BigDecimal.ZERO;
                int remainingCount = 0, doneCount = 0;
                for (Item i : items) {
                    if (i.getStatus() == ItemStatus.DONE) { doneCount++; continue; }
                    if (i.getStatus() == ItemStatus.CANCELLED) continue;
                    remainingCount++;
                    if (i.getEstimate() != null) remaining = remaining.add(i.getEstimate());
                }
                points.add(pointMap(today, remaining, remainingCount, doneCount, true));
            }
        }
        List<Map<String, Object>> ideal = new ArrayList<>();
        BigDecimal total = null;
        if (!snaps.isEmpty() && it.getEndDate() != null) {
            IterationSnapshot first = snaps.get(0);
            total = first.getRemainingEstimate();
            int totalWorkdays = Workdays.workdaysInclusive(first.getSnapshotDate(), it.getEndDate(), settings);
            if (totalWorkdays > 0) {
                for (IterationSnapshot s : snaps) {
                    int left = Workdays.workdaysBetween(s.getSnapshotDate(), it.getEndDate(), settings);
                    BigDecimal v = total.multiply(BigDecimal.valueOf(left))
                            .divide(BigDecimal.valueOf(totalWorkdays), 2, java.math.RoundingMode.HALF_UP);
                    ideal.add(pointMap(s.getSnapshotDate(), v, null, null, null));
                }
                ideal.add(pointMap(it.getEndDate(), BigDecimal.ZERO, null, null, null));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iteration", Dtos.IterationDto.of(it));
        out.put("points", points);
        out.put("ideal", ideal);
        out.put("totalEstimate", total);
        return out;
    }

    private Map<String, Object> pointMap(LocalDate date, BigDecimal remaining, Integer remainingCount, Integer doneCount, Boolean live) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("remainingEstimate", remaining);
        m.put("remainingCount", remainingCount);
        m.put("doneCount", doneCount);
        if (live != null) m.put("live", live);
        return m;
    }

    // ---------- 关键日期 ----------

    @Transactional(readOnly = true)
    public List<KeyDate> keyDates(Long teamId, Long iterationId, AppUser user) {
        teamService.requireMember(teamId, user);
        requireIteration(teamId, iterationId);
        return keyDateRepo.findByIterationIdOrderByDateAsc(iterationId);
    }

    @Transactional
    public KeyDate addKeyDate(Long teamId, Long iterationId, AppUser user, String title, LocalDate date, String category) {
        requireLeaderOrPo(teamId, user);
        Iteration it = requireIteration(teamId, iterationId);
        if (title == null || title.isBlank() || title.length() > 200) throw ApiException.validation("关键日期标题不能为空且不超过 200 字");
        if (date == null) throw ApiException.validation("关键日期必须带日期");
        String cat = category == null ? "OTHER" : category;
        if (!List.of("FREEZE", "RELEASE", "REVIEW", "OTHER").contains(cat)) throw ApiException.validation("category 须为 FREEZE / RELEASE / REVIEW / OTHER");
        KeyDate kd = new KeyDate();
        kd.setIteration(it);
        kd.setTitle(title);
        kd.setDate(date);
        kd.setCategory(cat);
        return keyDateRepo.save(kd);
    }

    /** 关键日期修改（修订二 §16.2：增删改仅组长/PO）。 */
    @Transactional
    public KeyDate patchKeyDate(Long teamId, Long keyDateId, AppUser user, String title, LocalDate date, String category) {
        requireLeaderOrPo(teamId, user);
        KeyDate kd = keyDateRepo.findById(keyDateId)
                .filter(k -> k.getIteration().getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("关键日期不存在"));
        if (title != null) {
            if (title.isBlank() || title.length() > 200) throw ApiException.validation("关键日期标题不能为空且不超过 200 字");
            kd.setTitle(title);
        }
        if (date != null) kd.setDate(date);
        if (category != null) {
            if (!List.of("FREEZE", "RELEASE", "REVIEW", "OTHER").contains(category)) throw ApiException.validation("category 须为 FREEZE / RELEASE / REVIEW / OTHER");
            kd.setCategory(category);
        }
        return keyDateRepo.save(kd);
    }

    @Transactional
    public void deleteKeyDate(Long teamId, Long keyDateId, AppUser user) {
        requireLeaderOrPo(teamId, user);
        KeyDate kd = keyDateRepo.findById(keyDateId)
                .filter(k -> k.getIteration().getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("关键日期不存在"));
        keyDateRepo.delete(kd);
    }
}
