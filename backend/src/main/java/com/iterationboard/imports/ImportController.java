package com.iterationboard.imports;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.IdempotencyHelper;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import com.iterationboard.domain.ImportBatch;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 导入批次接口：确认写入 / 批次列表 / 整批撤销 / 文件解析。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/imports")
public class ImportController {
    private final ImportService importService;
    private final FileTableReader fileTableReader;
    private final TeamService teamService;
    private final CurrentUser currentUser;
    private final IdempotencyHelper idempotency;

    public ImportController(ImportService importService, FileTableReader fileTableReader, TeamService teamService,
                            CurrentUser currentUser, IdempotencyHelper idempotency) {
        this.importService = importService;
        this.fileTableReader = fileTableReader;
        this.teamService = teamService;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    public record RowInput(String externalId, String title, String description, String priority,
                           LocalDate dueDate, String externalUrl, Double estimate) {}
    public record ConfirmRequest(String source, String systemName, List<RowInput> rows,
                                 List<List<String>> rawRows, Map<String, Integer> mapping) {}

    static Map<String, Object> batchMap(ImportBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("teamId", b.getTeam().getId());
        m.put("creatorId", b.getCreator().getId());
        m.put("source", b.getSource());
        m.put("total", b.getTotal());
        m.put("createdCount", b.getCreatedCount());
        m.put("updatedCount", b.getUpdatedCount());
        m.put("undoneAt", b.getUndoneAt());
        m.put("createdAt", b.getCreatedAt());
        m.put("link", "/teams/" + b.getTeam().getId() + "/imports/" + b.getId());
        return m;
    }

    private void requirePo(Long teamId, AppUser u) {
        var me = teamService.requireMember(teamId, u);
        if (me.getRole() != Enums.Role.PO) throw ApiException.forbidden("导入任务仅 PO 可操作");
    }

    @PostMapping
    public ResponseEntity<?> confirm(@PathVariable Long teamId, @RequestBody ConfirmRequest req,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        requirePo(teamId, u);
        String scope = "POST /teams/" + teamId + "/imports";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        String source = req.source() == null ? "AI_PASTE" : req.source();
        if (!List.of("AI_PASTE", "FILE_MAPPING", "ADAPTER").contains(source)) {
            throw ApiException.validation("source 须为 AI_PASTE / FILE_MAPPING / ADAPTER");
        }
        List<ImportService.ImportRow> rows = toRows(req);
        if (rows.isEmpty()) throw ApiException.validation("没有可导入的行");
        if (rows.size() > ImportService.MAX_IMPORT_ROWS) {
            throw ApiException.validation("单次导入最多 " + ImportService.MAX_IMPORT_ROWS + " 条");
        }
        String systemName = req.systemName() != null && !req.systemName().isBlank()
                ? req.systemName() : ("FILE_MAPPING".equals(source) ? "FILE" : "AI_PASTE");
        var result = importService.applyImport(teamId, u, source, systemName, rows);
        Map<String, Object> body = new LinkedHashMap<>(batchMap(result.batch()));
        body.put("createdItemIds", result.createdItemIds());
        body.put("updatedItemIds", result.updatedItemIds());
        body.put("skipped", result.skipped());
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.CREATED.value(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private List<ImportService.ImportRow> toRows(ConfirmRequest req) {
        List<ImportService.ImportRow> rows = new ArrayList<>();
        if (req.rows() != null) {
            for (RowInput r : req.rows()) {
                rows.add(new ImportService.ImportRow(r.externalId(), r.title(), r.description(),
                        r.priority(), r.dueDate(), r.externalUrl(), false, r.estimate()));
            }
            return rows;
        }
        // 文件列映射：服务端按 mapping（字段→列序号）从 rawRows 取数，不依赖 AI
        if (req.rawRows() != null && req.mapping() != null) {
            Map<String, Integer> mp = req.mapping();
            for (List<String> raw : req.rawRows()) {
                rows.add(new ImportService.ImportRow(
                        cell(raw, mp.get("externalId")), cell(raw, mp.get("title")), cell(raw, mp.get("description")),
                        cell(raw, mp.get("priority")), parseDate(cell(raw, mp.get("dueDate"))),
                        cell(raw, mp.get("externalUrl")), false, parseNumber(cell(raw, mp.get("estimate")))));
            }
        }
        return rows;
    }

    private static String cell(List<String> raw, Integer idx) {
        if (raw == null || idx == null || idx < 0 || idx >= raw.size()) return null;
        String v = raw.get(idx);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static Double parseNumber(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.strip()); }
        catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try { return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length()))); }
        catch (Exception e) { return null; }
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return importService.list(teamId, u).stream().map(ImportController::batchMap).toList();
    }

    @PostMapping("/{batchId}/undo")
    public Map<String, Object> undo(@PathVariable Long teamId, @PathVariable Long batchId) {
        AppUser u = currentUser.require();
        return batchMap(importService.undo(teamId, batchId, u));
    }

    /** Excel/CSV 列映射导入第一步：解析文件返回 {headers, rows}，确认走 POST /imports 带 mapping。 */
    @PostMapping("/file/parse")
    public Map<String, Object> parseFile(@PathVariable Long teamId,
                                         @RequestParam("file") MultipartFile file) throws Exception {
        AppUser u = currentUser.require();
        requirePo(teamId, u);
        if (file == null || file.isEmpty()) throw ApiException.validation("文件为空");
        FileTableReader.Table t = fileTableReader.parse(file.getBytes(), file.getOriginalFilename());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", t.headers());
        out.put("rows", t.rows());
        return out;
    }
}
