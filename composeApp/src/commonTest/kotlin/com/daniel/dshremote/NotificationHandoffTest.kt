package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationHandoffTest {

    private fun session(id: String) = SessionSummary(
        id = id,
        cwd = "/tmp/$id",
        status = "idle",
        agentCount = 1,
        subagentCount = 0,
        updatedAt = 0L,
    )

    private val sessions = listOf(session("s1"), session("s2"))

    @Test
    fun nullSessionId_returnsNull() {
        assertNull(resolveNotificationOpenTarget(null, sessions))
    }

    @Test
    fun existingSession_returnsSessionId() {
        assertEquals("s1", resolveNotificationOpenTarget("s1", sessions))
        assertEquals("s2", resolveNotificationOpenTarget("s2", sessions))
    }

    @Test
    fun missingSession_returnsNull() {
        assertNull(resolveNotificationOpenTarget("sX", sessions))
    }

    @Test
    fun emptyList_returnsNull() {
        assertNull(resolveNotificationOpenTarget("s1", emptyList()))
    }

    // ---- parseNotificationSheetTarget ----

    @Test
    fun parseTag_approval() {
        assertEquals(
            NotificationSheetTarget.Approval("appr-1"),
            parseNotificationSheetTarget("approval:appr-1"),
        )
    }

    @Test
    fun parseTag_question() {
        assertEquals(
            NotificationSheetTarget.Question("q-1"),
            parseNotificationSheetTarget("question:q-1"),
        )
    }

    @Test
    fun parseTag_deliveryOrNull_isNone() {
        assertEquals(NotificationSheetTarget.None, parseNotificationSheetTarget(null))
        assertEquals(NotificationSheetTarget.None, parseNotificationSheetTarget("delivery:s1:tk-1"))
        assertEquals(NotificationSheetTarget.None, parseNotificationSheetTarget(""))
    }
}
