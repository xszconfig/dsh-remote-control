package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 「连接后自动打开最近会话」的候选选取纯函数单测。 */
class PickRecentSessionTest {

    private fun session(
        id: String,
        workspaceId: String? = null,
        updatedAt: Long = 0,
        parentSessionId: String? = null,
    ) = SessionSummary(
        id = id,
        name = id,
        workspaceId = workspaceId,
        cwd = "/proj",
        status = "idle",
        agentCount = 1,
        subagentCount = 0,
        updatedAt = updatedAt,
        parentSessionId = parentSessionId,
    )

    @Test
    fun emptyList_returnsNull() {
        assertNull(pickRecentSession(emptyList(), null))
    }

    @Test
    fun onlySubagentSessions_returnsNull() {
        assertNull(pickRecentSession(listOf(session("sub-1", updatedAt = 100, parentSessionId = "main-1")), null))
    }

    @Test
    fun multipleMainSessions_picksNewestUpdatedAt() {
        val sessions = listOf(
            session("old", updatedAt = 100),
            session("newest", updatedAt = 300),
            session("mid", updatedAt = 200),
        )
        assertEquals("newest", pickRecentSession(sessions, null)?.id)
    }

    @Test
    fun mixedSubagentWithNewerTimestamp_doesNotWinOverMainSession() {
        // 子代理 updatedAt 更大也必须被排除：只取主会话里最新的
        val sessions = listOf(
            session("main-new", updatedAt = 500),
            session("sub-newer", updatedAt = 900, parentSessionId = "main-new"),
        )
        assertEquals("main-new", pickRecentSession(sessions, null)?.id)
    }

    @Test
    fun workspaceFilter_specificWorkspace() {
        val sessions = listOf(
            session("w1-new", workspaceId = "w1", updatedAt = 500),
            session("w2-new", workspaceId = "w2", updatedAt = 900),
        )
        assertEquals("w1-new", pickRecentSession(sessions, "w1")?.id)
    }

    @Test
    fun workspaceFilter_ungrouped() {
        val sessions = listOf(
            session("ug-new", workspaceId = null, updatedAt = 500),
            session("w1-new", workspaceId = "w1", updatedAt = 900),
        )
        assertEquals("ug-new", pickRecentSession(sessions, UNGROUPED_KEY)?.id)
    }

    @Test
    fun workspaceNull_includesAll() {
        val sessions = listOf(
            session("ug", workspaceId = null, updatedAt = 100),
            session("w1", workspaceId = "w1", updatedAt = 900),
        )
        assertEquals("w1", pickRecentSession(sessions, null)?.id)
    }
}
