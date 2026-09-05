package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaFormatTest {

    private fun session(
        updatedAt: Long = 0L,
        lastMessageAt: Long? = null,
        runDurationMs: Long? = null,
        totalTokens: Long? = null,
    ) = SessionSummary(
        id = "s1",
        cwd = "/x",
        status = "idle",
        agentCount = 0,
        subagentCount = 0,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        runDurationMs = runDurationMs,
        totalTokens = totalTokens,
    )

    @Test
    fun formatDuration_buckets() {
        assertEquals("0s", formatDuration(0))
        assertEquals("0s", formatDuration(-1000))
        assertEquals("45s", formatDuration(45_000))
        assertEquals("59s", formatDuration(59_000))
        assertEquals("1m", formatDuration(60_000))
        assertEquals("12m", formatDuration(750_000))
        assertEquals("59m", formatDuration(3_540_000))
        assertEquals("1h", formatDuration(3_600_000))
        assertEquals("2h", formatDuration(7_200_000))
        assertEquals("2h 3m", formatDuration(7_380_000))
    }

    @Test
    fun formatTokens_buckets() {
        assertEquals("0", formatTokens(0))
        assertEquals("0", formatTokens(-5))
        assertEquals("999", formatTokens(999))
        assertEquals("1k", formatTokens(1000))
        assertEquals("1.2k", formatTokens(1234))
        assertEquals("12.3k", formatTokens(12_300))
        assertEquals("120k", formatTokens(120_000))
        assertEquals("999k", formatTokens(999_000))
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("1.2M", formatTokens(1_234_567))
        assertEquals("2.3M", formatTokens(2_300_000))
    }

    @Test
    fun subagentMetaSegments_full() {
        // now = 2027-01-15T08:00:00Z（UTC 固定，保证确定性）
        val now = 1_800_000_000_000L
        val s = session(
            updatedAt = now - 3 * 60_000,
            lastMessageAt = now - 3 * 60_000,
            runDurationMs = 7_380_000,
            totalTokens = 12_300,
        )
        assertEquals(listOf("今天 07:57", "2h 3m", "12.3k"), subagentMetaSegments(s, now, TimeZone.UTC))
    }

    @Test
    fun subagentMetaSegments_missingDurationAndTokens() {
        val now = 1_800_000_000_000L
        val s = session(updatedAt = now - 60_000, lastMessageAt = now - 60_000)
        assertEquals(listOf("今天 07:59"), subagentMetaSegments(s, now, TimeZone.UTC))
    }

    @Test
    fun subagentMetaSegments_zeroDurationAndTokensSkipped() {
        val now = 1_800_000_000_000L
        val s = session(updatedAt = now, lastMessageAt = now, runDurationMs = 0, totalTokens = 0)
        assertEquals(listOf("刚刚"), subagentMetaSegments(s, now, TimeZone.UTC))
    }

    @Test
    fun subagentMetaSegments_lastMessageAtFallsBackToUpdatedAt() {
        val now = 1_800_000_000_000L
        // 旧版 bridge（0.12.0）无 lastMessageAt：回退 updatedAt
        val s = session(updatedAt = now - 5 * 60_000, lastMessageAt = null)
        assertEquals(listOf("今天 07:55"), subagentMetaSegments(s, now, TimeZone.UTC))
    }
}
