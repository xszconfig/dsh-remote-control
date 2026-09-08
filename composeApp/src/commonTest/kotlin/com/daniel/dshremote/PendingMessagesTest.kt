package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingMessagesTest {

    private fun p(
        localId: String,
        sessionId: String = "s1",
        text: String = "hello",
        status: PendingStatus = PendingStatus.Sending,
        createdAt: Long = 1_000L,
    ) = PendingMessage(localId, sessionId, text, status, createdAt)

    @Test
    fun add_pending_appends() {
        val list = addPending(emptyList(), p("a"))
        assertEquals(1, list.size)
        assertEquals("a", list.first().localId)
        assertEquals(PendingStatus.Sending, list.first().status)
    }

    @Test
    fun mark_sent_transitions_only_target() {
        val list = listOf(p("a"), p("b", text = "other"))
        val next = markPendingSent(list, "a")
        assertEquals(PendingStatus.Sent, next.first { it.localId == "a" }.status)
        assertEquals(PendingStatus.Sending, next.first { it.localId == "b" }.status)
    }

    @Test
    fun mark_failed_transitions_only_target() {
        val list = listOf(p("a"), p("b"))
        val next = markPendingFailed(list, "b")
        assertEquals(PendingStatus.Failed, next.first { it.localId == "b" }.status)
        assertEquals(PendingStatus.Sending, next.first { it.localId == "a" }.status)
    }

    @Test
    fun mark_sending_retries_from_failed() {
        val failed = markPendingFailed(listOf(p("a")), "a")
        val next = markPendingSending(failed, "a")
        assertEquals(PendingStatus.Sending, next.first().status)
    }

    @Test
    fun remove_pending_drops_target() {
        val list = listOf(p("a"), p("b"))
        val next = removePending(list, "a")
        assertEquals(listOf("b"), next.map { it.localId })
    }

    @Test
    fun echo_matches_same_session_text_time() {
        val list = listOf(p("a", sessionId = "s1", text = "hi", createdAt = 10_000L))
        assertEquals("a", matchPendingEcho(list, "s1", "hi", 12_000L))
    }

    @Test
    fun echo_ignores_different_session() {
        val list = listOf(p("a", sessionId = "s1", text = "hi", createdAt = 10_000L))
        assertNull(matchPendingEcho(list, "s2", "hi", 10_000L))
    }

    @Test
    fun echo_ignores_different_text() {
        val list = listOf(p("a", sessionId = "s1", text = "hi", createdAt = 10_000L))
        assertNull(matchPendingEcho(list, "s1", "bye", 10_000L))
    }

    @Test
    fun echo_ignores_outside_time_window() {
        val list = listOf(p("a", sessionId = "s1", text = "hi", createdAt = 10_000L))
        assertNull(matchPendingEcho(list, "s1", "hi", 10_000L + PENDING_ECHO_MATCH_WINDOW_MS))
    }

    @Test
    fun echo_matches_oldest_when_two_identical() {
        // 同文本连续发两条：第一条回显应匹配第一条（FIFO），第二条留给下一条回显
        val list = listOf(
            p("a", text = "hi", createdAt = 10_000L),
            p("b", text = "hi", createdAt = 10_100L),
        )
        assertEquals("a", matchPendingEcho(list, "s1", "hi", 11_000L))
    }

    @Test
    fun restore_sending_becomes_failed() {
        val list = listOf(
            p("a", status = PendingStatus.Sending),
            p("b", status = PendingStatus.Failed),
        )
        val restored = restorePendingFromDisk(list)
        assertEquals(PendingStatus.Failed, restored.first { it.localId == "a" }.status)
        assertEquals(PendingStatus.Failed, restored.first { it.localId == "b" }.status)
    }

    @Test
    fun restore_sent_defensively_becomes_failed() {
        // Sent 理论上不落盘；防御性：任何非 Failed 恢复时都转 failed（结果未知，不自动重发）
        val restored = restorePendingFromDisk(listOf(p("a", status = PendingStatus.Sent)))
        assertEquals(PendingStatus.Failed, restored.single().status)
    }
}
