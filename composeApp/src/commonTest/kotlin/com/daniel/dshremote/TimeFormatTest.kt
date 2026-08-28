package com.daniel.dshremote

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {

    // 基准：2026-08-28T00:00:00Z
    private val base = 1_787_875_200_000L

    @Test
    fun formatClock_utc() {
        assertEquals("00:00:00", formatClock(base, TimeZone.UTC))
        assertEquals("12:34:56", formatClock(base + 45_296_000L, TimeZone.UTC))
    }

    @Test
    fun formatClock_positiveOffsetTimeZone() {
        // 上海 UTC+8：UTC 00:00 → 当地 08:00；UTC 前一天 16:00 → 当地 00:00
        assertEquals("08:00:00", formatClock(base, TimeZone.of("Asia/Shanghai")))
        assertEquals("00:00:00", formatClock(base - 8 * 3_600_000L, TimeZone.of("Asia/Shanghai")))
    }

    @Test
    fun formatClock_negativeOffsetTimeZone() {
        // 纽约八月为夏令时（UTC-4）：UTC 04:00 → 当地 00:00
        assertEquals("00:00:00", formatClock(base + 4 * 3_600_000L, TimeZone.of("America/New_York")))
    }

    @Test
    fun formatClock_handlesDST_transitionCorrectly() {
        // 2026-03-08T07:00:00Z 恰为美国夏令时切换瞬间（2:00 EST → 3:00 EDT）
        val dstStart = 1_772_953_200_000L
        assertEquals("03:00:00", formatClock(dstStart, TimeZone.of("America/New_York")))
        assertEquals("01:59:00", formatClock(dstStart - 60_000L, TimeZone.of("America/New_York")))
    }

    @Test
    fun relativeTime_buckets() {
        val now = 1_800_000_000_000L
        assertEquals("从未", relativeTime(0, now))
        assertEquals("刚刚", relativeTime(now - 30_000, now))
        assertEquals("刚刚", relativeTime(now + 60_000, now)) // 未来时间容忍为「刚刚」
        assertEquals("5 分钟前", relativeTime(now - 5 * 60_000, now))
        assertEquals("3 小时前", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 天前", relativeTime(now - 2 * 86_400_000, now))
    }

    @Test
    fun relativeTime_overSevenDaysFallsBackToClock() {
        val now = 1_800_000_000_000L
        val old = now - 8 * 86_400_000L
        assertEquals(formatClock(old, TimeZone.UTC), relativeTime(old, now, TimeZone.UTC))
    }
}
