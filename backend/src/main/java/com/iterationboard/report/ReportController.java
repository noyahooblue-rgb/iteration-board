package com.iterationboard.report;

import com.iterationboard.common.IdempotencyHelper;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import com.iterationboard.domain.Report;
import com.iterationboard.domain.ReportVersion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报告与导出：生成（周期唯一）/列表（服务端可见过滤）/查看/编辑/删除回收站/历史版本 + 事项 Excel 导出。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class ReportController {
    private final ReportService reportService;
    private final ExportService exportService;
    private final CurrentUser currentUser;
    private final IdempotencyHelper idempotency;

    public ReportController(ReportService reportService, ExportService exportService, CurrentUser currentUser,
                            IdempotencyHelper idempotency) {
        this.reportService = reportService;
        this.exportService = exportService;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    public record GenerateRequest(String kind, LocalDate periodStart, LocalDate periodEnd, Long iterationId) {}
    public record PatchReportRequest(String title, String content, String contentMd, Integer version) {}

    static Map<String, Object> reportMap(Report r) { return reportMap(r, List.of()); }

    static Map<String, Object> reportMap(Report r, List<String> allowedActions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("teamId", r.getTeam().getId());
        m.put("kind", r.getKind());
        m.put("title", r.getTitle());
        m.put("periodStart", r.getPeriodStart());
        m.put("periodEnd", r.getPeriodEnd());
        m.put("ownerId", r.getOwner() == null ? null : r.getOwner().getId());
        m.put("content", r.getContent());
        m.put("contentMd", r.getContent());
        m.put("aiGenerated", r.isAiGenerated());
        m.put("edited", r.isEdited());
        m.put("editedByHuman", r.isEditedByHuman());
        m.put("version", r.getVersion());
        m.put("iterationId", r.getIterationId());
        m.put("lastGeneratedAt", r.getLastGeneratedAt());
        m.put("deletedAt", r.getDeletedAt());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        m.put("deletedById", r.getDeletedById());
        m.put("allowedActions", allowedActions);
        return m;
    }

    static Map<String, Object> versionMap(ReportVersion v, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("reportId", v.getReport().getId());
        m.put("title", v.getTitle());
        if (full) m.put("content", v.getContent());
        m.put("editedByHuman", v.isEditedByHuman());
        m.put("createdAt", v.getCreatedAt());
        return m;
    }

    @PostMapping("/reports/generate")
    public ResponseEntity<?> generate(@PathVariable Long teamId, @RequestBody GenerateRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        String scope = "POST /teams/" + teamId + "/reports/generate";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        ReportService.GenResult res = reportService.generate(teamId, u, req.kind(), req.periodStart(), req.periodEnd(), req.iterationId());
        Map<String, Object> body = new LinkedHashMap<>(reportMap(res.report(), reportService.allowedActions(res.report(), u)));
        body.put("existed", res.existed());
        body.put("restored", res.restored());
        int status = res.existed() ? HttpStatus.OK.value() : HttpStatus.CREATED.value();
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, status, body);
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> list(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return reportService.list(teamId, u).stream().map(r -> reportMap(r, reportService.allowedActions(r, u))).toList();
    }

    @GetMapping("/reports/trash")
    public List<Map<String, Object>> trash(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return reportService.trash(teamId, u).stream().map(r -> reportMap(r, reportService.allowedActions(r, u))).toList();
    }

    @GetMapping("/reports/{reportId}")
    public Map<String, Object> get(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.get(teamId, reportId, u);
        return reportMap(r, reportService.allowedActions(r, u));
    }

    @PatchMapping("/reports/{reportId}")
    public Map<String, Object> patch(@PathVariable Long teamId, @PathVariable Long reportId,
                                     @RequestBody PatchReportRequest req) {
        AppUser u = currentUser.require();
        String content = req.contentMd() != null ? req.contentMd() : req.content();
        Report r = reportService.patch(teamId, reportId, u, req.title(), content, req.version());
        return reportMap(r, reportService.allowedActions(r, u));
    }

    @DeleteMapping("/reports/{reportId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        reportService.softDelete(teamId, reportId, u);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reports/{reportId}/restore")
    public Map<String, Object> restore(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.restore(teamId, reportId, u);
        return reportMap(r, reportService.allowedActions(r, u));
    }

    @PostMapping("/reports/{reportId}/regenerate")
    public Map<String, Object> regenerate(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.regenerate(teamId, reportId, u);
        return reportMap(r, reportService.allowedActions(r, u));
    }

    @GetMapping("/reports/{reportId}/versions")
    public List<Map<String, Object>> versions(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        return reportService.versions(teamId, reportId, u).stream().map(v -> versionMap(v, false)).toList();
    }

    @GetMapping("/reports/{reportId}/versions/{versionId}")
    public Map<String, Object> version(@PathVariable Long teamId, @PathVariable Long reportId,
                                       @PathVariable Long versionId) {
        AppUser u = currentUser.require();
        return versionMap(reportService.version(teamId, reportId, versionId, u), true);
    }

    @PostMapping("/reports/{reportId}/versions/{versionId}/restore")
    public Map<String, Object> restoreVersion(@PathVariable Long teamId, @PathVariable Long reportId,
                                              @PathVariable Long versionId) {
        AppUser u = currentUser.require();
        Report r = reportService.restoreVersion(teamId, reportId, versionId, u);
        return reportMap(r, reportService.allowedActions(r, u));
    }

    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<String> exportMarkdown(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.get(teamId, reportId, u);
        String filename = URLEncoder.encode(r.getTitle(), StandardCharsets.UTF_8).replace("+", "%20") + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(r.getContent());
    }

    /** P20：报告导出 Word（.docx）。 */
    @GetMapping("/reports/{reportId}/export-docx")
    public ResponseEntity<byte[]> exportDocx(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.get(teamId, reportId, u);
        String filename = URLEncoder.encode(r.getTitle(), StandardCharsets.UTF_8).replace("+", "%20") + ".docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(exportService.exportReportDocx(r));
    }

    /** P20：「复制为邮件正文」所需的结构化 HTML（保留标题/列表/表格）。 */
    @GetMapping("/reports/{reportId}/export-html")
    public Map<String, Object> exportHtml(@PathVariable Long teamId, @PathVariable Long reportId) {
        AppUser u = currentUser.require();
        Report r = reportService.get(teamId, reportId, u);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", r.getTitle());
        out.put("html", ExportService.markdownToHtml(r.getContent()));
        return out;
    }

    @GetMapping("/items/export")
    public ResponseEntity<byte[]> exportItems(@PathVariable Long teamId,
                                              @RequestParam(required = false) Long iterationId,
                                              @RequestParam(required = false) Enums.ItemStatus status) {
        AppUser u = currentUser.require();
        byte[] bytes = exportService.exportItems(teamId, u, iterationId, status);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"items.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
