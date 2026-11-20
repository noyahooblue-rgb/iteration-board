package com.iterationboard.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 备份状态（P23）：与打包脚本的约定——脚本每次备份结束后把结果写入数据目录下的 backup-status.json：
 * {"lastAttemptAt":"...","lastSuccessAt":"...","result":"SUCCESS|FAILED","detail":"..."}。
 * 后端只读该文件；文件不存在时如实返回未知（lastSuccessAt=null），不臆造备份记录。
 * 「超 3 天未成功备份」判定供管理员顶部提示与系统信息页共用。
 */
@Service
public class BackupStatusService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path statusFile;

    public BackupStatusService(@Value("${app.data-dir:data}") String dataDir) {
        this.statusFile = Path.of(dataDir).resolve("backup-status.json");
    }

    public Path statusFile() { return statusFile; }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant lastSuccessAt = null;
        String result = null;
        String detail = null;
        boolean filePresent = Files.isRegularFile(statusFile);
        if (filePresent) {
            try {
                JsonNode root = MAPPER.readTree(Files.readString(statusFile));
                String s = root.path("lastSuccessAt").asText(null);
                if (s != null && !s.isBlank()) lastSuccessAt = Instant.parse(s);
                result = root.path("result").asText(null);
                detail = root.path("detail").asText(null);
            } catch (Exception ignored) {
                // 文件损坏时按未知处理，不臆造
                filePresent = false;
            }
        }
        out.put("statusFilePresent", filePresent);
        out.put("lastBackupAt", lastSuccessAt);
        out.put("lastBackupResult", result);
        out.put("lastBackupDetail", detail);
        boolean stale = lastSuccessAt == null
                || Duration.between(lastSuccessAt, Instant.now()).toDays() > 3;
        out.put("backupStale", stale);
        out.put("backupStaleMessage", stale
                ? (lastSuccessAt == null ? "尚无成功备份记录" : "已超过 3 天没有成功备份")
                : null);
        return out;
    }
}
