package com.iterationboard.admin;

import com.iterationboard.ai.AiProperties;
import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 系统信息（修订三二.2/五.3，仅平台管理员）：程序版本/构建日期、数据库版本、Flyway 迁移版本、
 * 安装目录、数据目录大小、最近备份时间与结果、服务启动类型、AI 连接状态、本机局域网地址。
 * 读不到的字段如实返回「未知」，不臆造（服务启动类型在 JVM 内无法可靠判定，固定返回未知并注明）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class SystemInfoController {
    private final CurrentUser currentUser;
    private final VersionInfo versionInfo;
    private final BackupStatusService backupStatusService;
    private final DataSource dataSource;
    private final AiProperties aiProperties;
    private final String dataDir;
    private final int serverPort;

    public SystemInfoController(CurrentUser currentUser, VersionInfo versionInfo,
                                BackupStatusService backupStatusService, DataSource dataSource,
                                AiProperties aiProperties,
                                @Value("${app.data-dir:data}") String dataDir,
                                @Value("${server.port:8080}") int serverPort) {
        this.currentUser = currentUser;
        this.versionInfo = versionInfo;
        this.backupStatusService = backupStatusService;
        this.dataSource = dataSource;
        this.aiProperties = aiProperties;
        this.dataDir = dataDir;
        this.serverPort = serverPort;
    }

    private void requirePlatformAdmin() {
        AppUser me = currentUser.require();
        if (!me.isPlatformAdmin()) throw ApiException.forbidden("仅平台管理员可查看系统信息");
    }

    @GetMapping("/system-info")
    public Map<String, Object> systemInfo() {
        requirePlatformAdmin();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appVersion", versionInfo.version());
        out.put("buildDate", versionInfo.buildDate());
        out.put("databaseVersion", databaseVersion());
        out.put("flywayVersion", flywayVersion());
        out.put("installDir", Path.of(System.getProperty("user.dir")).toAbsolutePath().toString());
        Path dataPath = Path.of(dataDir).toAbsolutePath();
        out.put("dataDir", dataPath.toString());
        out.put("dataDirSizeBytes", dirSize(dataPath));
        Map<String, Object> backup = backupStatusService.status();
        out.put("lastBackupAt", backup.get("lastBackupAt"));
        out.put("lastBackupResult", backup.get("lastBackupResult"));
        out.put("backupStale", backup.get("backupStale"));
        out.put("serviceStartType", "未知");
        out.put("serviceStartTypeNote", "JVM 内无法判定 Windows 服务启动类型，请在服务器 services.msc 中查看");
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("mode", aiProperties.getMode());
        ai.put("platformEnabled", aiProperties.isPlatformEnabled());
        ai.put("configured", aiProperties.getBaseUrl() != null && !aiProperties.getBaseUrl().isBlank());
        ai.put("status", "mock".equalsIgnoreCase(aiProperties.getMode()) ? "MOCK"
                : (aiProperties.getBaseUrl() == null || aiProperties.getBaseUrl().isBlank() ? "NOT_CONFIGURED" : "CONFIGURED"));
        out.put("ai", ai);
        out.put("lanAddresses", lanAddresses());
        out.put("serverPort", serverPort);
        return out;
    }

    private String databaseVersion() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select version()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "未知";
        } catch (Exception e) {
            return "未知";
        }
    }

    private String flywayVersion() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select version from flyway_schema_history where success order by installed_rank desc limit 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "未知";
        } catch (Exception e) {
            return "未知";
        }
    }

    private Long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            var ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add("http://" + a.getHostAddress() + ":" + serverPort);
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败时返回空列表（前端显示未知）
        }
        return out;
    }
}
