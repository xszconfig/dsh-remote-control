package com.daniel.dshremote

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampFormatTest {

    private val utc = TimeZone.UTC

    // 基准「现在」：2026-06-15T12:00:00Z（UTC 固定，保证确定性）
    private val now = 1_781_524_800_000L

    @Test
    fun invalidTsShowsNever() {
        assertEquals("从未", formatTimestamp(0, now, utc))
        assertEquals("从未", formatTimestamp(-5, now, utc))
        assertEquals("从未", formatTimestampCompact(0, now, utc))
    }

    @Test
    fun justNowWithinOneMinute() {
        assertEquals("刚刚", formatTimestamp(now - 30_000, now, utc))
        assertEquals("刚刚", formatTimestampCompact(now - 30_000, now, utc))
    }

    @Test
    fun futureWithinOneMinuteToleratedAsJustNow() {
        // 未来时间（时钟微偏）容忍为「刚刚」
        assertEquals("刚刚", formatTimestamp(now + 60_000, now, utc))
    }

    @Test
    fun todayShowsClock() {
        assertEquals("10:00", formatTimestamp(now - 2 * 3_600_000, now, utc))
        // 个位小时补零
        assertEquals("01:00", formatTimestamp(now - 11 * 3_600_000, now, utc))
    }

    @Test
    fun yesterdayFullCarriesTime() {
        assertEquals("昨天 12:00", formatTimestamp(now - 86_400_000, now, utc))
    }

    @Test
    fun yesterdayCompactOmitsTime() {
        assertEquals("昨天", formatTimestampCompact(now - 86_400_000, now, utc))
    }

    @Test
    fun dayBeforeYesterday() {
        assertEquals("前天", formatTimestamp(now - 2 * 86_400_000, now, utc))
        assertEquals("前天", formatTimestampCompact(now - 2 * 86_400_000, now, utc))
    }

    @Test
    fun earlierSameYearShowsMonthDay() {
        // 2026-03-01T12:00:00Z → 同年更早
        assertEquals("3月1日", formatTimestamp(1_772_366_400_000L, now, utc))
    }

    @Test
    fun crossYearShowsFullDate() {
        // 2025-12-31T12:00:00Z → 跨年
        assertEquals("2025年12月31日", formatTimestamp(1_767_182_400_000L, now, utc))
        assertEquals("2025年12月31日", formatTimestampCompact(1_767_182_400_000L, now, utc))
    }

    @Test
    fun midnightBoundaryIsYesterday() {
        // 2026-06-15T00:30Z 的「1 小时前」= 2026-06-14T23:30Z → 昨天，不是今天
        val midnightNow = 1_781_483_400_000L
        assertEquals("昨天 23:30", formatTimestamp(midnightNow - 3_600_000, midnightNow, utc))
    }

    @Test
    fun monthBoundaryIsYesterday() {
        // 2026-03-01T00:30Z 的「1 小时前」= 2026-02-28T23:30Z → 昨天（跨月）
        val monthStart = 1_772_325_000_000L
        assertEquals("昨天 23:30", formatTimestamp(monthStart - 3_600_000, monthStart, utc))
    }

    @Test
    fun yearBoundaryIsYesterdayNotCrossYearDate() {
        // 2026-01-01T00:30Z 的「1 小时前」= 2025-12-31T23:30Z → 昨天（跨年也优先「昨天」）
        val yearStart = 1_767_227_400_000L
        assertEquals("昨天 23:30", formatTimestamp(yearStart - 3_600_000, yearStart, utc))
        assertEquals("昨天", formatTimestampCompact(yearStart - 3_600_000, yearStart, utc))
    }

    @Test
    fun yearBoundaryDayBeforeYesterday() {
        // 2026-01-02T00:30Z 的「前天」= 2025-12-31T00:30Z（前天跨年）
        val jan2 = 1_767_313_800_000L
        assertEquals("前天", formatTimestamp(1_767_141_000_000L, jan2, utc))
    }
}
