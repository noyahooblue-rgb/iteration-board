package com.iterationboard.common;

import com.iterationboard.team.TeamSettings;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 工作日计算：周一至周五为工作日，扣小组设置的特殊非工作日、
 * 加特殊工作日（调休）。所有「几个工作日未更新」类判断都走这里。
 */
public final class Workdays {
    private Workdays() { }

    public static boolean isWorkday(LocalDate date, TeamSettings settings) {
        if (settings != null) {
            if (settings.specialWorkdays.contains(date)) return true;
            if (settings.specialHolidays.contains(date)) return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** (fromExclusive, toInclusive] 区间内的工作日数；to<=from 时为 0。 */
    public static int workdaysBetween(LocalDate fromExclusive, LocalDate toInclusive, TeamSettings settings) {
        if (fromExclusive == null || toInclusive == null || !toInclusive.isAfter(fromExclusive)) return 0;
        int count = 0;
        LocalDate d = fromExclusive.plusDays(1);
        while (!d.isAfter(toInclusive)) {
            if (isWorkday(d, settings)) count++;
            d = d.plusDays(1);
        }
        return count;
    }

    /** [fromInclusive, toInclusive] 闭区间内的工作日数。 */
    public static int workdaysInclusive(LocalDate fromInclusive, LocalDate toInclusive, TeamSettings settings) {
        if (fromInclusive == null || toInclusive == null || toInclusive.isBefore(fromInclusive)) return 0;
        return workdaysBetween(fromInclusive.minusDays(1), toInclusive, settings);
    }

    /** date 之前最近的工作日（不含 date 本身）。 */
    public static LocalDate previousWorkday(LocalDate date, TeamSettings settings) {
        LocalDate d = date.minusDays(1);
        while (!isWorkday(d, settings)) d = d.minusDays(1);
        return d;
    }

    /** date 之后第 n 个工作日（n>=0；n=0 返回 date 本身若为工作日，否则下一个工作日）。 */
    public static LocalDate plusWorkdays(LocalDate date, int n, TeamSettings settings) {
        LocalDate d = date;
        int remaining = n;
        while (true) {
            if (isWorkday(d, settings)) {
                if (remaining == 0) return d;
                remaining--;
            }
            d = d.plusDays(1);
        }
    }
}
