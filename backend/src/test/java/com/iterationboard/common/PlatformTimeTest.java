package com.iterationboard.common;

import com.iterationboard.common.PlatformTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlatformTime 单元测试（1.0.4 时区统一）：
 * 同一 Instant 在 Asia/Shanghai 与 UTC 两个平台时区下，日期口径与日界起点必须不同且各自正确——
 * 这正是服务器系统时区为 UTC 时「今天」整体偏移一天的回归场景。
 */
class PlatformTimeTest {

    private final PlatformTime shanghai = new PlatformTime(ZoneId.of("Asia/Shanghai"));
    private final PlatformTime utc = new PlatformTime(ZoneId.of("UTC"));

    @Test
    void zoneReturnsInjectedZone() {
        assertThat(shanghai.zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(utc.zone()).isEqualTo(ZoneId.of("UTC"));
        // 字符串构造（与 Spring @Value 注入路径一致）
        assertThat(new PlatformTime("Asia/Shanghai").zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void sameInstantMapsToDifferentDatesAcrossZones() {
        // 2026-11-19T16:30:00Z：UTC 仍是 11-19，Asia/Shanghai（UTC+8）已是 11-20 00:30
        Instant instant = Instant.parse("2026-11-19T16:30:00Z");
        assertThat(utc.dateOf(instant)).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(shanghai.dateOf(instant)).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void todayFollowsInjectedZoneNotSystemDefault() {
        // today() 必须等于按注入时区换算的当前日期，与 JVM 系统默认时区无关
        assertThat(shanghai.today()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(utc.today()).isEqualTo(LocalDate.now(ZoneId.of("UTC")));
    }

    @Test
    void startOfTodayInstantIsMidnightInPlatformZone() {
        Instant startSh = shanghai.startOfTodayInstant();
        assertThat(startSh.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(shanghai.dateOf(startSh)).isEqualTo(shanghai.today());
        // 上海日界 = 当日 00:00+08:00 = 前一日 16:00Z
        assertThat(startSh).isEqualTo(shanghai.today().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());

        Instant startUtc = utc.startOfTodayInstant();
        assertThat(startUtc.atZone(ZoneId.of("UTC")).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(utc.dateOf(startUtc)).isEqualTo(utc.today());
    }

    @Test
    void startOfTodayForFixedDateDiffersByEightHours() {
        // 固定日期口径校验：同一 LocalDate 在两时区的日界 Instant 相差恰好 8 小时
        LocalDate date = LocalDate.of(2026, 11, 20);
        Instant shStart = date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant utcStart = date.atStartOfDay(ZoneId.of("UTC")).toInstant();
        assertThat(shStart).isEqualTo(Instant.parse("2026-11-19T16:00:00Z"));
        assertThat(utcStart).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
    }
}
