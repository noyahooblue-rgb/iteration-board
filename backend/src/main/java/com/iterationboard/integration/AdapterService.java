package com.iterationboard.integration;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.domain.*;
import com.iterationboard.imports.ImportService;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.IntegrationAdapterRepo;
import com.iterationboard.repo.SyncLogRepo;
import com.iterationboard.repo.TeamMemberRepo;
import com.iterationboard.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 外部系统适配器（设计 §9）：MOCK 内置虚构样例 / HTTP 通用配置适配器。
 * 同步规则：按「系统名+外部编号」识别；新任务进待办池（任务状态 NOT_STARTED 即待办池口径）；
 * 已存在只更新外部字段；外部关闭只加「外部已关闭」标记不改状态；
 * 每次同步写日志；连续失败 3 次通知组长。密钥只写不回显（读取时脱敏）。
 */
@Service
public class AdapterService {
    private final IntegrationAdapterRepo adapterRepo;
    private final SyncLogRepo syncLogRepo;
    private final ImportService importService;
    private final TeamService teamService;
    private final TeamMemberRepo memberRepo;
    private final NotifyService notifyService;
    private final ObjectMapper om;
    private final PlatformTime platformTime;

    public AdapterService(IntegrationAdapterRepo adapterRepo, SyncLogRepo syncLogRepo, ImportService importService,
                          TeamService teamService, TeamMemberRepo memberRepo, NotifyService notifyService, ObjectMapper om, PlatformTime platformTime) {
        this.adapterRepo = adapterRepo;
        this.syncLogRepo = syncLogRepo;
        this.importService = importService;
        this.teamService = teamService;
        this.memberRepo = memberRepo;
        this.notifyService = notifyService;
        this.om = om;
        this.platformTime = platformTime;
    }

    // ---------- CRUD（仅组长配置） ----------

    @Transactional(readOnly = true)
    public List<IntegrationAdapter> list(Long teamId, AppUser user) {
        teamService.requireLeader(teamId, user);
        return adapterRepo.findByTeamId(teamId);
    }

    @Transactional
    public IntegrationAdapter create(Long teamId, AppUser user, String name, String type, String systemName, JsonNode config) {
        teamService.requireLeader(teamId, user);
        validate(name, type, systemName);
        IntegrationAdapter a = new IntegrationAdapter();
        a.setTeam(teamService.requireTeam(teamId));
        a.setName(name);
        a.setType(type);
        a.setSystemName(systemName);
        a.setConfigJson(config == null ? "{}" : config.toString());
        a.setCreatedBy(user);
        return adapterRepo.save(a);
    }

    @Transactional
    public IntegrationAdapter update(Long teamId, Long adapterId, AppUser user, String name, String type,
                                     String systemName, JsonNode config, Boolean enabled) {
        teamService.requireLeader(teamId, user);
        IntegrationAdapter a = requireAdapter(teamId, adapterId);
        if (name != null) a.setName(name);
        if (type != null) a.setType(type);
        if (systemName != null) a.setSystemName(systemName);
        if (enabled != null) a.setEnabled(enabled);
        if (config != null) a.setConfigJson(mergeSecrets(a.getConfigJson(), config));
        return adapterRepo.save(a);
    }

    @Transactional
    public void delete(Long teamId, Long adapterId, AppUser user) {
        teamService.requireLeader(teamId, user);
        adapterRepo.delete(requireAdapter(teamId, adapterId));
    }

    private void validate(String name, String type, String systemName) {
        if (name == null || name.isBlank()) throw ApiException.validation("适配器名称不能为空");
        if (!List.of("MOCK", "HTTP").contains(type)) throw ApiException.validation("type 须为 MOCK / HTTP");
        if (systemName == null || systemName.isBlank()) throw ApiException.validation("系统名不能为空（同步按系统名+外部编号识别）");
    }

    public IntegrationAdapter requireAdapter(Long teamId, Long adapterId) {
        return adapterRepo.findById(adapterId)
                .filter(a -> a.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("适配器不存在"));
    }

    /** 读取回显：密钥字段脱敏。 */
    public JsonNode maskedConfig(IntegrationAdapter a) {
        JsonNode cfg = parseConfig(a);
        if (cfg instanceof ObjectNode o) {
            for (String key : List.of("authToken", "authPassword")) {
                if (o.hasNonNull(key) && !o.get(key).asText().isBlank()) o.put(key, "***");
            }
        }
        return cfg;
    }

    /** 更新时密钥传 "***" 表示保留原值。 */
    private String mergeSecrets(String oldJson, JsonNode newCfg) {
        if (newCfg instanceof ObjectNode o) {
            JsonNode old = parseConfigJson(oldJson);
            for (String key : List.of("authToken", "authPassword")) {
                if (o.has(key) && "***".equals(o.get(key).asText()) && old.hasNonNull(key)) {
                    o.put(key, old.get(key).asText());
                }
            }
        }
        return newCfg.toString();
    }

    private JsonNode parseConfig(IntegrationAdapter a) { return parseConfigJson(a.getConfigJson()); }

    private JsonNode parseConfigJson(String json) {
        try { return om.readTree(json == null || json.isBlank() ? "{}" : json); }
        catch (Exception e) { return om.createObjectNode(); }
    }

    // ---------- 拉取 ----------

    public record ExternalTask(String externalId, String title, String description, String priority,
                               LocalDate dueDate, String url, boolean closed) {}

    /** MOCK 适配器内置虚构样例数据（通用软件开发场景）。 */
    List<ExternalTask> mockTasks() {
        return List.of(
                new ExternalTask("MOCK-101", "门户首页改版需求确认", "确认新版门户首页的信息架构与视觉稿", "P1",
                        platformTime.today().plusDays(7), "https://example.invalid/mock/101", false),
                new ExternalTask("MOCK-102", "导出功能性能优化", "大文件导出改为异步生成并通知下载", "P2",
                        platformTime.today().plusDays(14), "https://example.invalid/mock/102", false),
                new ExternalTask("MOCK-103", "历史缺陷单关闭确认", "该外部任务已在源系统关闭", "P3",
                        platformTime.today().minusDays(3), "https://example.invalid/mock/103", true));
    }

    private List<ExternalTask> fetch(IntegrationAdapter a) throws Exception {
        if ("MOCK".equals(a.getType())) return mockTasks();
        JsonNode cfg = parseConfig(a);
        String url = cfg.path("url").asText("");
        if (url.isBlank()) throw new IllegalStateException("HTTP 适配器未配置 url");
        String method = cfg.path("method").asText("GET").toUpperCase();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        String authType = cfg.path("authType").asText("");
        if ("TOKEN".equals(authType) && cfg.hasNonNull("authToken")) {
            b.header(cfg.path("authHeader").asText("Authorization"), "Bearer " + cfg.get("authToken").asText());
        } else if ("BASIC".equals(authType) && cfg.hasNonNull("authUser")) {
            String raw = cfg.get("authUser").asText() + ":" + cfg.path("authPassword").asText("");
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofString(cfg.path("body").asText("{}")));
        else b.GET();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("外部系统 HTTP " + resp.statusCode());
        JsonNode root = om.readTree(resp.body());
        String listPath = cfg.path("listPath").asText("$");
        List<JsonNode> nodes = JsonPathMini.eval(root, listPath);
        JsonNode fields = cfg.path("fields");
        List<ExternalTask> out = new ArrayList<>();
        for (JsonNode n : nodes) {
            String extId = fieldText(n, fields, "externalId", "$.id");
            if (extId == null || extId.isBlank()) continue;
            String title = fieldText(n, fields, "title", "$.title");
            String statusVal = fieldText(n, fields, "status", "$.status");
            String closedVals = cfg.path("closedValues").asText("CLOSED,CANCELLED,DONE");
            boolean closed = statusVal != null && List.of(closedVals.split(",")).contains(statusVal.strip());
            out.add(new ExternalTask(extId, title == null ? extId : title,
                    fieldText(n, fields, "description", "$.description"),
                    mapValue(cfg.path("priorityMap"), fieldText(n, fields, "priority", "$.priority"), "P2"),
                    parseDate(fieldText(n, fields, "dueDate", "$.dueDate")),
                    fieldText(n, fields, "url", "$.url"), closed));
        }
        return out;
    }

    private String fieldText(JsonNode node, JsonNode fields, String key, String dflt) {
        String path = fields != null && fields.hasNonNull(key) ? fields.get(key).asText() : dflt;
        return JsonPathMini.text(node, path);
    }

    private String mapValue(JsonNode map, String v, String dflt) {
        if (v == null) return dflt;
        if (map != null && map.hasNonNull(v)) return map.get(v).asText();
        return v;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length()))); }
        catch (Exception e) { return null; }
    }

    // ---------- 测试连接 / 同步 ----------

    public record TestResult(boolean ok, int count, String message) {}

    @Transactional(readOnly = true)
    public TestResult test(Long teamId, Long adapterId, AppUser user) {
        teamService.requireLeader(teamId, user);
        IntegrationAdapter a = requireAdapter(teamId, adapterId);
        try {
            List<ExternalTask> tasks = fetch(a);
            return new TestResult(true, tasks.size(), "连接成功，拉取到 " + tasks.size() + " 条外部任务");
        } catch (Exception e) {
            return new TestResult(false, 0, "连接失败：" + e.getMessage());
        }
    }

    @Transactional
    public SyncLog sync(Long teamId, Long adapterId, AppUser user) {
        teamService.requireLeader(teamId, user);
        IntegrationAdapter a = requireAdapter(teamId, adapterId);
        if (!a.isEnabled()) throw ApiException.validation("适配器已停用");
        try {
            List<ExternalTask> tasks = fetch(a);
            List<ImportService.ImportRow> rows = tasks.stream().map(t -> new ImportService.ImportRow(
                    t.externalId(), t.title(), t.description(), t.priority(), t.dueDate(), t.url(), t.closed(), null)).toList();
            var result = importService.applyImport(teamId, user, "ADAPTER", a.getSystemName(), rows);
            a.setConsecutiveFailures(0);
            adapterRepo.save(a);
            SyncLog logEntry = syncLogRepo.save(new SyncLog(a, teamId, user.getId(),
                    result.createdItemIds().size(), result.updatedItemIds().size(), result.skipped(), "同步成功", true));
            return logEntry;
        } catch (Exception e) {
            int fails = a.getConsecutiveFailures() + 1;
            a.setConsecutiveFailures(fails);
            adapterRepo.save(a);
            SyncLog logEntry = syncLogRepo.save(new SyncLog(a, teamId, user.getId(), 0, 0, 0,
                    "同步失败：" + trim(e.getMessage()), false));
            if (fails >= 3) notifyLeader(teamId, user, a, fails);
            return logEntry;
        }
    }

    private void notifyLeader(Long teamId, AppUser actor, IntegrationAdapter a, int fails) {
        memberRepo.findByTeamId(teamId).stream()
                .filter(m -> m.getRole() == Enums.Role.LEADER)
                // yefqkvf3 待问 1：连败通知发给所有组长，包括亲手触发同步的组长本人（includeSelf=true 豁免 self 跳过）
                .forEach(m -> notifyService.notifyInTeam(m.getUser(), "SYNC_FAILED", null,
                        teamService.requireTeam(teamId), actor,
                        "适配器「" + a.getName() + "」已连续同步失败 " + fails + " 次，请检查配置", true));
    }

    @Transactional(readOnly = true)
    public List<SyncLog> syncLogs(Long teamId, AppUser user) {
        teamService.requireLeader(teamId, user);
        return syncLogRepo.findByTeamIdOrderByCreatedAtDesc(teamId);
    }

    private static String trim(String s) {
        if (s == null) return "未知错误";
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
