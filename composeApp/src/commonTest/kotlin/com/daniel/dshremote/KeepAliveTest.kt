package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAliveTest {

    private fun session(id: String, status: String, parentSessionId: String? = null) = SessionSummary(
        id = id,
        cwd = "/tmp/$id",
        status = status,
        agentCount = 1,
        subagentCount = 0,
        updatedAt = 0L,
        parentSessionId = parentSessionId,
    )

    // ---- countRunningAgents ----

    @Test
    fun count_empty() {
        assertEquals(RunningAgents(0, 0), countRunningAgents(emptyList()))
    }

    @Test
    fun count_mixedMainAndSub() {
        val sessions = listOf(
            session("m1", "running"),
            session("m2", "idle"),
            session("s1", "running", parentSessionId = "m1"),
            session("s2", "running", parentSessionId = "m1"),
            session("m3", "running"),
        )
        assertEquals(RunningAgents(mainCount = 2, subCount = 2), countRunningAgents(sessions))
    }

    @Test
    fun count_ignoresNonRunning() {
        val sessions = listOf(
            session("m1", "idle"),
            session("s1", "idle", parentSessionId = "m1"),
        )
        val counts = countRunningAgents(sessions)
        assertFalse(counts.running)
        assertEquals(0, counts.total)
    }

    @Test
    fun count_allRunning() {
        val sessions = listOf(
            session("m1", "running"),
            session("s1", "running", parentSessionId = "m1"),
            session("s2", "running", parentSessionId = "m1"),
        )
        assertTrue(countRunningAgents(sessions).running)
        assertEquals(3, countRunningAgents(sessions).total)
    }

    // ---- decideFgsAction ----

    @Test
    fun decide_disconnectedKeepsState() {
        assertEquals(
            FgsAction.NOOP,
            decideFgsAction(RunningAgents(1, 0), connected = false, foreground = true),
        )
        // 断连即便 counts=0 也维持（不停止）
        assertEquals(
            FgsAction.NOOP,
            decideFgsAction(RunningAgents(0, 0), connected = false, foreground = false),
        )
    }

    @Test
    fun decide_zeroCountsStops() {
        assertEquals(
            FgsAction.STOP,
            decideFgsAction(RunningAgents(0, 0), connected = true, foreground = true),
        )
        // 后台也停（停止不受后台启动限制）
        assertEquals(
            FgsAction.STOP,
            decideFgsAction(RunningAgents(0, 0), connected = true, foreground = false),
        )
    }

    @Test
    fun decide_foregroundRunningStarts() {
        assertEquals(
            FgsAction.START_OR_UPDATE,
            decideFgsAction(RunningAgents(1, 2), connected = true, foreground = true),
        )
    }

    @Test
    fun decide_backgroundRunningNoop() {
        // 后台不启动/更新（Android 12+ 后台启动限制），但也不停
        assertEquals(
            FgsAction.NOOP,
            decideFgsAction(RunningAgents(1, 0), connected = true, foreground = false),
        )
    }

    // ---- keepAliveNotificationBody ----

    @Test
    fun notificationBody_counts() {
        assertEquals("1 个主代理 · 2 个子代理正在运行", keepAliveNotificationBody(1, 2))
        assertEquals("0 个主代理 · 1 个子代理正在运行", keepAliveNotificationBody(0, 1))
    }
}
