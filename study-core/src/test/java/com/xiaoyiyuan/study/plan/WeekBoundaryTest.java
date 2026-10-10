package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WeekBoundaryTest {

    @Test
    void anchor_isAMonday() {
        assertEquals(java.time.DayOfWeek.MONDAY, WeekCalendar.ANCHOR_MONDAY.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 14), WeekCalendar.ANCHOR_MONDAY);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-14, 1", // 周一
            "2026-09-20, 1", // 周日
            "2026-09-21, 2",
            "2026-09-27, 2",
            "2026-09-28, 3",
            "2026-10-04, 3",
            "2026-10-05, 4",
            "2026-10-10, 4", // 周六
            "2026-10-11, 4"  // 周日
    })
    void weekNumber_matchesArchiveLabels(String date, int expected) {
        assertEquals(expected, WeekCalendar.weekNumber(LocalDate.parse(date)));
    }

    @Test
    void mondayAndSunday_areCorrect() {
        assertEquals(LocalDate.of(2026, 10, 5), WeekCalendar.mondayOf(LocalDate.of(2026, 10, 10)));
        assertEquals(LocalDate.of(2026, 10, 11), WeekCalendar.sundayOf(LocalDate.of(2026, 10, 10)));
    }

    @Test
    void crossesMonthAndYearBoundaries() {
        // 跨月
        assertEquals(LocalDate.of(2026, 9, 28), WeekCalendar.mondayOf(LocalDate.of(2026, 10, 1)));
        // 跨年（2027-01-01 是周五，其所在周的周一是 2026-12-28）
        assertEquals(LocalDate.of(2026, 12, 28), WeekCalendar.mondayOf(LocalDate.of(2027, 1, 1)));
        // 闰年 2028-02-29 是周二，周一为 2028-02-28
        assertEquals(LocalDate.of(2028, 2, 28), WeekCalendar.mondayOf(LocalDate.of(2028, 2, 29)));
    }

    @Test
    void weekStart_isInverseOfWeekNumber() {
        for (int w = 1; w <= 60; w++) {
            LocalDate start = WeekCalendar.weekStart(w);
            assertEquals(w, WeekCalendar.weekNumber(start), "周 " + w + " 起止必须自洽");
            assertEquals(java.time.DayOfWeek.MONDAY, start.getDayOfWeek());
        }
    }

    @Test
    void weekRangeLabel_isHumanReadable() {
        assertEquals("2026-10-05 – 2026-10-11", WeekCalendar.weekRangeLabel(4));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-13, 0",  // 锚点前一天的周日，其周一 2026-09-07 正好早一个整周 → 第 0 周
            "2026-09-07, 0",  // 第 0 周的周一
            "2026-09-06, -1", // 再往前一个整周
            "2025-12-29, -36" // 跨到上一年：与锚点相差 36 个整周（252 天）
    })
    void weekNumber_beforeAnchor_isWellDefined(String date, int expected) {
        // 锚点之前的日期超出本项目的正式范围，但必须算得清楚、不能抛异常，也不能静默算成第 1 周。
        //
        // 这里说明清楚：本用例<b>不</b>声称能抓住 floorDiv 与 `/` 的差别。mondayOf 返回的必然是周一，
        // 于是「锚点 → 该周一」的天数差永远是 7 的整数倍，整除在这个输入域上与 floorDiv 等价
        // （用 `/` 实跑过，本用例照样绿）。用例的价值是钉死「锚点前的日期不准抛异常，
        // 也不准被静默当成第 1 周」，也就是这个负号方向本身。
        // floorDiv 仍然要写——它是这个量本该有的语义；一旦以后有人把被除数从「周一之差」
        // 改成别的天数差，`/` 的向零取整就会在负数侧悄悄算错。
        assertEquals(expected, WeekCalendar.weekNumber(LocalDate.parse(date)));
    }
}
