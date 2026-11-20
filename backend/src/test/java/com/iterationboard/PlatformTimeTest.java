package com.iterationboard;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.notify.NotificationView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1.0.4 时区逻辑单元测试：固定 ZoneId 构造 PlatformTime，
 * 验证 today / startOfTodayInstant / dateOf 全部按注入时区计算，与服务器系统时区无关。
 */
class PlatformTimeTest {

    @Test
    void todayAndStartOfToday_followInjectedZone() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        PlatformTime pt = new PlatformTime(shanghai);

        LocalDate expectedToday = LocalDate.now(shanghai);
        assertThat(pt.today()).isEqualTo(expectedToday);
        assertThat(pt.startOfTodayInstant())
                .isEqualTo(expectedToday.atStartOfDay(shanghai).toInstant());
        // startOfToday 换算回平台时区必须是当天 00:00
        ZonedDateTime zdt = pt.startOfTodayInstant().atZone(shanghai);
        assertThat(zdt.toLocalDate()).isEqualTo(expectedToday);
        assertThat(zdt.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
    }

    @Test
    void dateBoundaries_differAcrossZones() {
        // 同一个 Instant 在 UTC 与 UTC+8 可能落在不同日期：取一个必然跨日的时刻验证口径
        Instant instant = Instant.parse("2026-01-15T20:30:00Z"); // 上海 01-16 04:30 / UTC 01-15 20:30
        PlatformTime shanghai = new PlatformTime(ZoneId.of("Asia/Shanghai"));
        PlatformTime utc = new PlatformTime(ZoneId.of("UTC"));
        assertThat(shanghai.dateOf(instant)).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(utc.dateOf(instant)).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(shanghai.zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void notificationCategories_newTypesMapped() {
        assertThat(NotificationView.categoryOf("INVITE")).isEqualTo("ASSIGNMENT");
        assertThat(NotificationView.categoryOf("ACCEPTOR")).isEqualTo("ASSIGNMENT");
        assertThat(NotificationView.categoryOf("CLAIM")).isEqualTo("ITEM_UPDATE");
        assertThat(NotificationView.categoryOf("CANCEL")).isEqualTo("ITEM_UPDATE");
        assertThat(NotificationView.categoryOf("COMMENT")).isEqualTo("ITEM_UPDATE");
        assertThat(NotificationView.categoryOf("JOIN_REJECTED")).isEqualTo("SYSTEM");
    }
}
