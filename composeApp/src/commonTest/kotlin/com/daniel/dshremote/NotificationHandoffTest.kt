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
}
