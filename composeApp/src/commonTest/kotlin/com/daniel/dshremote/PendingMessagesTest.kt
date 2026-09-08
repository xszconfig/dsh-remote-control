package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingMessagesTest {

    private fun p(
        msgId: String,
        sessionId: String = "s1",
        text: String = "hello",
        status: PendingStatus = PendingStatus.Sending,
        createdAt: Long = 1_000L,
        retryCount: Int = 0,
    ) = PendingMessage(msgId, sessionId, text, status, createdAt, retryCount)

    @Test
    fun add_pending_appends() {
        val list = addPending(emptyList(), p("a"))
        assertEquals(1, list.size)
        assertEquals("a", list.first().msgId)
        assertEquals(PendingStatus.Sending, list.first().status)
    }

    @Test
    fun mark_sent_transitions_only_target() {
        val list = listOf(p("a"), p("b", text = "other"))
        val next = markPendingSent(list, "a")
        assertEquals(PendingStatus.Sent, next.first { it.msgId == "a" }.status)
        assertEquals(PendingStatus.Sending, next.first { it.msgId == "b" }.status)
    }

    @Test
    fun mark_failed_transitions_only_target() {
        val list = listOf(p("a"), p("b"))
        val next = markPendingFailed(list, "b")
        assertEquals(PendingStatus.Failed, next.first { it.msgId == "b" }.status)
        assertEquals(PendingStatus.Sending, next.first { it.msgId == "a" }.status)
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
        assertEquals(listOf("b"), next.map { it.msgId })
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
        assertEquals(PendingStatus.Failed, restored.first { it.msgId == "a" }.status)
        assertEquals(PendingStatus.Failed, restored.first { it.msgId == "b" }.status)
    }

    @Test
    fun restore_sent_defensively_becomes_failed() {
        // Sent 理论上不落盘；防御性：任何非 Failed 恢复时都转 failed（结果未知，不自动重发）
        val restored = restorePendingFromDisk(listOf(p("a", status = PendingStatus.Sent)))
        assertEquals(PendingStatus.Failed, restored.single().status)
    }

    @Test
    fun restore_preserves_retryCount() {
        // 跨重启不重置自动重放配额：retryCount=2 的 failed 恢复后仍为 2（还剩 1 次自动重放）
        val restored = restorePendingFromDisk(listOf(p("a", status = PendingStatus.Sending, retryCount = 2)))
        assertEquals(PendingStatus.Failed, restored.single().status)
        assertEquals(2, restored.single().retryCount)
    }

    @Test
    fun mark_sending_manual_retry_resets_retryCount() {
        val failed = listOf(p("a", status = PendingStatus.Failed, retryCount = 2))
        val next = markPendingSending(failed, "a")
        assertEquals(PendingStatus.Sending, next.single().status)
        assertEquals(0, next.single().retryCount)
    }

    @Test
    fun ack_ok_removes_pending() {
        val list = listOf(p("a"), p("b"))
        assertEquals(listOf("b"), ackPendingOk(list, "a").map { it.msgId })
    }

    @Test
    fun auto_retryable_filters_failed_under_limit_in_order() {
        val list = listOf(
            p("a", status = PendingStatus.Failed, retryCount = 0),
            p("b", status = PendingStatus.Sending, retryCount = 0),
            p("c", status = PendingStatus.Failed, retryCount = MAX_AUTO_RETRY), // 已达上限
            p("d", status = PendingStatus.Failed, retryCount = 1),
        )
        assertEquals(listOf("a", "d"), autoRetryablePendings(list, "s1").map { it.msgId })
    }

    @Test
    fun auto_retryable_scopes_by_session() {
        val list = listOf(
            p("a", sessionId = "s1", status = PendingStatus.Failed),
            p("b", sessionId = "s2", status = PendingStatus.Failed),
        )
        assertEquals(listOf("a"), autoRetryablePendings(list, "s1").map { it.msgId })
    }

    @Test
    fun mark_auto_retry_increments_and_sends() {
        val next = markPendingAutoRetry(p("a", status = PendingStatus.Failed, retryCount = 1))
        assertEquals(PendingStatus.Sending, next.status)
        assertEquals(2, next.retryCount)
    }

    @Test
    fun auto_retry_backoff_increases() {
        assertEquals(1_000L, autoRetryBackoffMs(0))
        assertEquals(2_000L, autoRetryBackoffMs(1))
        assertEquals(4_000L, autoRetryBackoffMs(2))
        assertEquals(1_000L, autoRetryBackoffMs(-1)) // 负数 coerce 到 0
    }
}
