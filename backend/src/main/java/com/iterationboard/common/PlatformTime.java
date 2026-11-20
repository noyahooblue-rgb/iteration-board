package com.iterationboard.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/**
 * 平台时区统一入口（1.0.4，kubhs76k 时区统一）：
 * 所有「今天 / 本周 / 工作日 / 当天零点」类业务计算都必须经此组件按平台时区（app.timezone，
 * 默认 Asia/Shanghai）取值，禁止再直接调用无参 LocalDate.now() 或 ZoneId.systemDefault()，
 * 避免服务器系统时区为 UTC 时日期整体偏移。
 *
 * <p>存储口径不变：数据库时间戳仍以 UTC Instant 存储（Hibernate jdbc time_zone=UTC），
 * 接口仍返回 Instant 的 ISO 8601（带 Z），由前端按浏览器本地时区显示；本组件只管「日期」口径。
 *
 * <p>静态访问器 {@link #zoneId()} 供无法注入的静态工具（如 RiskService.dateOf）使用，
 * 其值由本 Bean 在启动时写入；未初始化时回退到配置默认值 Asia/Shanghai。
 */
@Component
public class PlatformTime {
    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    private static volatile ZoneId currentZone = ZoneId.of(DEFAULT_ZONE_ID);

    private final ZoneId zone;

    @Autowired
    public PlatformTime(@Value("${app.timezone:" + DEFAULT_ZONE_ID + "}") String zoneId) {
        this(ZoneId.of(zoneId));
    }

    /** 测试用：直接注入固定 ZoneId 构造，不依赖 Spring 环境。 */
    public PlatformTime(ZoneId zone) {
        this.zone = zone;
    }

    @PostConstruct
    void init() {
        currentZone = zone;
        // JVM 默认时区与平台时区对齐：兜住任何遗漏的无参 now()/systemDefault() 调用点，
        // 使其结果与平台口径一致（主类 main 里已在 Spring 启动前先行设置一次）。
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
    }

    public ZoneId zone() {
        return zone;
    }

    /** 静态口径：当前平台时区（Bean 初始化后为配置值，否则为默认 Asia/Shanghai）。 */
    public static ZoneId zoneId() {
        return currentZone;
    }

    /** 平台时区下的今天。 */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** 平台时区下某 Instant 对应的日期。 */
    public LocalDate dateOf(Instant instant) {
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    public ZonedDateTime nowZoned() {
        return ZonedDateTime.now(zone);
    }

    public Instant now() {
        return Instant.now();
    }

    /** 平台时区下今天零点对应的 Instant（按天去重/日限判断的统一界限，替代原 UTC 零点口径）。 */
    public Instant startOfTodayInstant() {
        return today().atStartOfDay(zone).toInstant();
    }
}
