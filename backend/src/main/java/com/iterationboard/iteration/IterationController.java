package com.iterationboard.iteration;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.KeyDate;
import com.iterationboard.report.ReportService;
import com.iterationboard.repo.IterationRepo;
import com.iterationboard.team.TeamService;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 迭代管理（M3 全量）：列表/创建/修改/开始/结束结转/燃尽/关键日期/快照/规划。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/iterations")
public class IterationController {
    private final IterationRepo iterationRepo;
    private final IterationService iterationService;
    private final TeamService teamService;
    private final CurrentUser currentUser;
    private final PlanningService planningService;
    private final ReportService reportService;
    private final com.iterationboard.common.IdempotencyHelper idempotency;

    public IterationController(IterationRepo iterationRepo, IterationService iterationService,
                               TeamService teamService, CurrentUser currentUser,
                               PlanningService planningService, ReportService reportService,
                               com.iterationboard.common.IdempotencyHelper idempotency) {
        this.iterationRepo = iterationRepo;
        this.iterationService = iterationService;
        this.teamService = teamService;
        this.currentUser = currentUser;
        this.planningService = planningService;
        this.reportService = reportService;
        this.idempotency = idempotency;
    }

    public record CreateIterationRequest(String name, String goal, LocalDate startDate, LocalDate endDate) {}
    public record CloseDecisionInput(Long itemId, String action) {}
    public record CloseIterationRequest(List<CloseDecisionInput> decisions, List<String> improvements, Boolean createNextIteration) {}
    public record KeyDateRequest(String title, LocalDate date, String category) {}

    @GetMapping
    public List<Dtos.IterationDto> list(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return iterationService.list(teamId, u).stream().map(Dtos.IterationDto::of).toList();
    }

    @PostMapping
    public ResponseEntity<Dtos.IterationDto> create(@PathVariable Long teamId, @RequestBody CreateIterationRequest req) {
        AppUser u = currentUser.require();
        var it = iterationService.create(teamId, u, req.name(), req.goal(), req.startDate(), req.endDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.IterationDto.of(it));
    }

    @GetMapping("/current")
    public Dtos.IterationDto current(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        return iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE")
                .map(Dtos.IterationDto::of)
                .orElseThrow(() -> ApiException.notFound("当前没有进行中的迭代"));
    }

    /**
     * 规划页默认迭代（契约 0.5.3，M5 验收结论 1）：有「规划中」迭代时返回它，
     * 否则返回「进行中」迭代；两者都没有时 404。规划页顶部选择器据此定默认选中项，
     * 选中后走 GET /{iterationId}/planning 拉取该迭代的规划数据。
     */
    @GetMapping("/planning-target")
    public Dtos.IterationDto planningTarget(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        return iterationRepo.findFirstByTeamIdAndStatus(teamId, "PLANNING")
                .or(() -> iterationRepo.findFirstByTeamIdAndStatus(teamId, "ACTIVE"))
                .map(Dtos.IterationDto::of)
                .orElseThrow(() -> ApiException.notFound("当前没有规划中或进行中的迭代"));
    }

    @GetMapping("/{iterationId}")
    public Dtos.IterationDto get(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        return Dtos.IterationDto.of(iterationService.get(teamId, iterationId, u));
    }

    @PatchMapping("/{iterationId}")
    public Dtos.IterationDto patch(@PathVariable Long teamId, @PathVariable Long iterationId, @RequestBody JsonNode body) {
        AppUser u = currentUser.require();
        var it = iterationService.patch(teamId, iterationId, u,
                body.hasNonNull("name") ? body.get("name").asText() : null,
                body.hasNonNull("goal") ? body.get("goal").asText() : null,
                body.has("startDate") && !body.get("startDate").isNull() ? LocalDate.parse(body.get("startDate").asText()) : null,
                body.has("startDate"),
                body.has("endDate") && !body.get("endDate").isNull() ? LocalDate.parse(body.get("endDate").asText()) : null,
                body.has("endDate"));
        return Dtos.IterationDto.of(it);
    }

    @DeleteMapping("/{iterationId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        iterationService.delete(teamId, iterationId, u);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{iterationId}/start")
    public Dtos.IterationDto start(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        return Dtos.IterationDto.of(iterationService.start(teamId, iterationId, u));
    }

    @PostMapping("/{iterationId}/close")
    public Object close(@PathVariable Long teamId, @PathVariable Long iterationId,
                        @RequestBody(required = false) CloseIterationRequest req,
                        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        String scope = "POST /teams/" + teamId + "/iterations/" + iterationId + "/close";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        List<IterationService.CloseDecision> decisions = req == null || req.decisions() == null ? List.of()
                : req.decisions().stream().map(d -> new IterationService.CloseDecision(d.itemId(), d.action())).toList();
        IterationService.CloseResult result = iterationService.close(teamId, iterationId, u, decisions,
                req == null ? null : req.improvements(),
                req == null ? null : req.createNextIteration(),
                reportService);
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), result);
        return result;
    }

    @GetMapping("/{iterationId}/burndown")
    public Map<String, Object> burndown(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        return iterationService.burndown(teamId, iterationId, u);
    }

    @PostMapping("/{iterationId}/snapshot")
    public Map<String, Object> snapshot(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        var it = iterationService.requireIteration(teamId, iterationId);
        var snap = iterationService.takeSnapshot(it, LocalDate.now());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iterationId", iterationId);
        m.put("date", snap.getSnapshotDate());
        m.put("remainingEstimate", snap.getRemainingEstimate());
        m.put("remainingCount", snap.getRemainingCount());
        m.put("doneCount", snap.getDoneCount());
        return m;
    }

    @GetMapping("/{iterationId}/key-dates")
    public List<Map<String, Object>> keyDates(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        return iterationService.keyDates(teamId, iterationId, u).stream().map(IterationController::keyDateMap).toList();
    }

    @PostMapping("/{iterationId}/key-dates")
    public ResponseEntity<Map<String, Object>> addKeyDate(@PathVariable Long teamId, @PathVariable Long iterationId,
                                                          @RequestBody KeyDateRequest req) {
        AppUser u = currentUser.require();
        KeyDate kd = iterationService.addKeyDate(teamId, iterationId, u, req.title(), req.date(), req.category());
        return ResponseEntity.status(HttpStatus.CREATED).body(keyDateMap(kd));
    }

    static Map<String, Object> keyDateMap(KeyDate kd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kd.getId());
        m.put("iterationId", kd.getIteration().getId());
        m.put("title", kd.getTitle());
        m.put("date", kd.getDate());
        m.put("category", kd.getCategory());
        return m;
    }

    @PatchMapping("/{iterationId}/key-dates/{keyDateId}")
    public Map<String, Object> patchKeyDate(@PathVariable Long teamId, @PathVariable Long iterationId,
                                            @PathVariable Long keyDateId, @RequestBody KeyDateRequest req) {
        AppUser u = currentUser.require();
        var kd = iterationService.patchKeyDate(teamId, keyDateId, u, req.title(), req.date(), req.category());
        return keyDateMap(kd);
    }

    @GetMapping("/{iterationId}/planning")
    public Dtos.PlanningDto planning(@PathVariable Long teamId, @PathVariable Long iterationId) {
        AppUser u = currentUser.require();
        return planningService.planning(teamId, iterationId, u);
    }
}
