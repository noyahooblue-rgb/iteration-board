package com.iterationboard.ai;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.IdempotencyHelper;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.*;
import com.iterationboard.imports.FileTableReader;
import com.iterationboard.repo.AiCallLogRepo;
import com.iterationboard.repo.TeamAiSettingsRepo;
import com.iterationboard.report.ReportService;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI 接口（M4，契约 0.5.0）：状态/小组开关/调用日志 + 六项功能入口。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/ai")
public class AiController {
    private final AiGateway gateway;
    private final AiFeatureService featureService;
    private final ReportService reportService;
    private final TeamService teamService;
    private final TeamAiSettingsRepo teamSettingsRepo;
    private final AiCallLogRepo callLogRepo;
    private final FileTableReader fileTableReader;
    private final CurrentUser currentUser;
    private final IdempotencyHelper idempotency;
    private final ObjectMapper om;

    public AiController(AiGateway gateway, AiFeatureService featureService, ReportService reportService,
                        TeamService teamService, TeamAiSettingsRepo teamSettingsRepo, AiCallLogRepo callLogRepo,
                        FileTableReader fileTableReader, CurrentUser currentUser, IdempotencyHelper idempotency,
                        ObjectMapper om) {
        this.gateway = gateway;
        this.featureService = featureService;
        this.reportService = reportService;
        this.teamService = teamService;
        this.teamSettingsRepo = teamSettingsRepo;
        this.callLogRepo = callLogRepo;
        this.fileTableReader = fileTableReader;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
        this.om = om;
    }

    public record SettingsRequest(Boolean pasteImport, Boolean quickUpdate, Boolean splitStories,
                                  Boolean summarize, Boolean riskSummary, Boolean qa) {}
    public record PasteImportRequest(String text, String imageBase64, String fileName, String fileContentBase64) {}
    public record QuickUpdateRequest(String text) {}
    public record SplitStoriesRequest(Long itemId) {}
    public record SummarizeRequest(String kind, LocalDate periodStart, LocalDate periodEnd, Long iterationId) {}
    public record QaRequest(String question) {}

    private Map<String, Object> settingsMap(TeamAiSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pasteImport", s.isPasteImport());
        m.put("quickUpdate", s.isQuickUpdate());
        m.put("splitStories", s.isSplitStories());
        m.put("summarize", s.isSummarize());
        m.put("riskSummary", s.isRiskSummary());
        m.put("qa", s.isQa());
        return m;
    }

    /** 合并后的可用状态：平台总开关 + 模式/配置 + 小组逐项开关；visionAvailable=false 时前端隐藏截图导入。 */
    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        AiProperties p = gateway.props();
        Map<String, Object> features = new LinkedHashMap<>();
        for (String f : AiGateway.FEATURES) features.put(f, gateway.featureAvailable(teamId, f));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformEnabled", p.isPlatformEnabled());
        out.put("mode", p.isMock() ? "mock" : "live");
        out.put("available", p.isPlatformEnabled() && (p.isMock() || p.liveConfigured()));
        out.put("visionAvailable", gateway.visionAvailable(teamId));
        out.put("textModel", p.isMock() ? "mock" : p.getTextModel());
        out.put("visionModel", p.isMock() ? "mock" : (p.getVisionModel().isBlank() ? null : p.getVisionModel()));
        out.put("features", features);
        out.put("teamSettings", settingsMap(gateway.teamSettings(teamId)));
        return out;
    }

    /** 小组逐项开关：仅组长可改。 */
    @PutMapping("/settings")
    public Map<String, Object> putSettings(@PathVariable Long teamId, @RequestBody SettingsRequest req) {
        AppUser u = currentUser.require();
        teamService.requireLeader(teamId, u);
        TeamAiSettings s = teamSettingsRepo.findById(teamId).orElseGet(() -> new TeamAiSettings(teamId));
        if (req.pasteImport() != null) s.setPasteImport(req.pasteImport());
        if (req.quickUpdate() != null) s.setQuickUpdate(req.quickUpdate());
        if (req.splitStories() != null) s.setSplitStories(req.splitStories());
        if (req.summarize() != null) s.setSummarize(req.summarize());
        if (req.riskSummary() != null) s.setRiskSummary(req.riskSummary());
        if (req.qa() != null) s.setQa(req.qa());
        teamSettingsRepo.save(s);
        return settingsMap(s);
    }

    /** AI 调用日志（本组，组长可见；平台级统计放 M5 平台管理）。 */
    @GetMapping("/logs")
    public List<Map<String, Object>> logs(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireLeader(teamId, u);
        return callLogRepo.findByTeamIdOrderByCreatedAtDesc(teamId).stream().limit(100).map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("feature", l.getFeature());
            m.put("model", l.getModel());
            m.put("durationMs", l.getDurationMs());
            m.put("promptTokens", l.getPromptTokens());
            m.put("completionTokens", l.getCompletionTokens());
            m.put("success", l.isSuccess());
            m.put("error", l.getError());
            m.put("createdAt", l.getCreatedAt());
            return m;
        }).toList();
    }

    /** 粘贴导入解析（仅 PO）：文本 / 图片（识图）/ Excel·CSV（程序先转文本）。只解析不写库，确认走 POST /imports。 */
    @PostMapping("/paste-import")
    public Map<String, Object> pasteImport(@PathVariable Long teamId, @RequestBody PasteImportRequest req) throws Exception {
        AppUser u = currentUser.require();
        String text = req.text();
        if (req.fileContentBase64() != null && !req.fileContentBase64().isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(req.fileContentBase64());
            text = fileTableReader.toText(fileTableReader.parse(bytes, req.fileName()));
        }
        String image = null;
        if (req.imageBase64() != null && !req.imageBase64().isBlank()) {
            image = req.imageBase64().startsWith("data:") ? req.imageBase64()
                    : "data:image/png;base64," + req.imageBase64();
        }
        if ((text == null || text.isBlank()) && image == null) throw ApiException.validation("请提供文本、图片或文件内容");
        return featureService.pasteImport(teamId, u, text, image);
    }

    @PostMapping("/quick-update")
    public Map<String, Object> quickUpdate(@PathVariable Long teamId, @RequestBody QuickUpdateRequest req) {
        AppUser u = currentUser.require();
        return featureService.quickUpdate(teamId, u, req.text());
    }

    public record ApplyRequest(List<AiFeatureService.QuickAction> actions) {}

    @PostMapping("/quick-update/apply")
    public ResponseEntity<?> quickUpdateApply(@PathVariable Long teamId, @RequestBody ApplyRequest req,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        String scope = "POST /teams/" + teamId + "/ai/quick-update/apply";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        var results = featureService.quickUpdateApply(teamId, u, req.actions());
        Map<String, Object> body = Map.of("results", results);
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), body);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/split-stories")
    public Map<String, Object> splitStories(@PathVariable Long teamId, @RequestBody SplitStoriesRequest req) {
        AppUser u = currentUser.require();
        if (req.itemId() == null) throw ApiException.validation("itemId 必填（要拆解的任务）");
        return featureService.splitStories(teamId, u, req.itemId());
    }

    /** 自动汇总的叙述预览/自测入口（正式生成走 POST /reports/generate，同一路径）。 */
    @PostMapping("/summarize")
    public Map<String, Object> summarize(@PathVariable Long teamId, @RequestBody SummarizeRequest req) {
        AppUser u = currentUser.require();
        ReportService.Preview p = reportService.preview(teamId, u, req.kind(), req.periodStart(), req.periodEnd(), req.iterationId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", p.content());
        out.put("aiGenerated", p.aiGenerated());
        return out;
    }

    @GetMapping("/risk-summary")
    public Map<String, Object> riskSummary(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return featureService.riskSummary(teamId, u);
    }

    @PostMapping("/qa")
    public Map<String, Object> qa(@PathVariable Long teamId, @RequestBody QaRequest req) {
        AppUser u = currentUser.require();
        return featureService.qa(teamId, u, req.question());
    }

    @GetMapping("/qa/history")
    public List<Map<String, Object>> qaHistory(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return featureService.qaHistory(teamId, u).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("question", r.getQuestion());
            m.put("answer", r.getAnswer());
            m.put("found", r.isFound());
            m.put("refs", readRefs(r.getRefsJson()));
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
    }

    @DeleteMapping("/qa/history/{recordId}")
    public ResponseEntity<Void> deleteQaHistory(@PathVariable Long teamId, @PathVariable Long recordId) {
        AppUser u = currentUser.require();
        featureService.deleteQaRecord(teamId, recordId, u);
        return ResponseEntity.noContent().build();
    }

    private List<String> readRefs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = om.readTree(json);
            List<String> out = new java.util.ArrayList<>();
            if (n.isArray()) n.forEach(x -> out.add(x.asText()));
            return out;
        } catch (Exception e) { return List.of(); }
    }

    /** 文件直传变体（multipart）：与 JSON 版等价，供前端文件选择器使用。 */
    @PostMapping(value = "/paste-import/file", consumes = "multipart/form-data")
    public Map<String, Object> pasteImportFile(@PathVariable Long teamId,
                                               @RequestParam("file") MultipartFile file) throws Exception {
        AppUser u = currentUser.require();
        String text = fileTableReader.toText(fileTableReader.parse(file.getBytes(), file.getOriginalFilename()));
        return featureService.pasteImport(teamId, u, text, null);
    }
}
