package com.iterationboard.integration;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.IntegrationAdapter;
import com.iterationboard.domain.SyncLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对接配置接口：适配器 CRUD（仅组长）+ 测试连接 + 立即同步 + 同步日志。密钥只写不回显。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/integrations")
public class AdapterController {
    private final AdapterService adapterService;
    private final CurrentUser currentUser;

    public AdapterController(AdapterService adapterService, CurrentUser currentUser) {
        this.adapterService = adapterService;
        this.currentUser = currentUser;
    }

    public record AdapterRequest(String name, String type, String systemName, JsonNode config, Boolean enabled) {}

    private Map<String, Object> adapterMap(IntegrationAdapter a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("teamId", a.getTeam().getId());
        m.put("name", a.getName());
        m.put("type", a.getType());
        m.put("systemName", a.getSystemName());
        m.put("config", adapterService.maskedConfig(a));
        m.put("enabled", a.isEnabled());
        m.put("consecutiveFailures", a.getConsecutiveFailures());
        m.put("createdBy", a.getCreatedBy().getId());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }

    static Map<String, Object> syncLogMap(SyncLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("adapterId", l.getAdapter().getId());
        m.put("teamId", l.getTeamId());
        m.put("createdCount", l.getCreatedCount());
        m.put("updatedCount", l.getUpdatedCount());
        m.put("failedCount", l.getFailedCount());
        m.put("message", l.getMessage());
        m.put("success", l.isSuccess());
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    @GetMapping("/adapters")
    public List<Map<String, Object>> list(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return adapterService.list(teamId, u).stream().map(this::adapterMap).toList();
    }

    @PostMapping("/adapters")
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long teamId, @RequestBody AdapterRequest req) {
        AppUser u = currentUser.require();
        var a = adapterService.create(teamId, u, req.name(), req.type(), req.systemName(), req.config());
        return ResponseEntity.status(HttpStatus.CREATED).body(adapterMap(a));
    }

    @PatchMapping("/adapters/{adapterId}")
    public Map<String, Object> update(@PathVariable Long teamId, @PathVariable Long adapterId,
                                      @RequestBody AdapterRequest req) {
        AppUser u = currentUser.require();
        var a = adapterService.update(teamId, adapterId, u, req.name(), req.type(), req.systemName(), req.config(), req.enabled());
        return adapterMap(a);
    }

    @DeleteMapping("/adapters/{adapterId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long adapterId) {
        AppUser u = currentUser.require();
        adapterService.delete(teamId, adapterId, u);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/adapters/{adapterId}/test")
    public Map<String, Object> test(@PathVariable Long teamId, @PathVariable Long adapterId) {
        AppUser u = currentUser.require();
        var r = adapterService.test(teamId, adapterId, u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("count", r.count());
        m.put("message", r.message());
        return m;
    }

    @PostMapping("/adapters/{adapterId}/sync")
    public Map<String, Object> sync(@PathVariable Long teamId, @PathVariable Long adapterId) {
        AppUser u = currentUser.require();
        return syncLogMap(adapterService.sync(teamId, adapterId, u));
    }

    @GetMapping("/sync-logs")
    public List<Map<String, Object>> syncLogs(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return adapterService.syncLogs(teamId, u).stream().map(AdapterController::syncLogMap).toList();
    }
}
