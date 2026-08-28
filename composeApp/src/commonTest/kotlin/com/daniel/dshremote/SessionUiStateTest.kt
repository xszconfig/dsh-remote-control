package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.WorkspaceSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionUiStateTest {

    private fun device(host: String, port: Int = 3080, lastSeenAt: Long = 0, serverId: String? = "srv", hostname: String? = "mac") =
        StoredDevice(
            deviceId = "d-$host", name = host, host = host, port = port,
            token = "t", serverId = serverId, hostname = hostname,
            createdAt = 0, lastSeenAt = lastSeenAt,
        )

    @Test
    fun clearedForDisconnect_clearsVolatileData_preservesUserPreference() {
        val state = SessionUiState(
            connectedDevice = device("127.0.0.1"),
            sessions = listOf(SessionSummary("s1", "重构", "w1", "/a", "idle", 1, 0, 1)),
            agents = listOf(AgentSummary("s1", "main", "idle", 0)),
            workspaces = listOf(WorkspaceSummary("w1", "W", "/a", 1)),
            selectedWorkspaceId = "w1",
            currentSessionId = "s1",
            events = listOf(EventProjection(1, "user_message", text = "hi", timestamp = 1)),
            approvals = listOf(ApprovalRequestWire("a1", "s1", "bash", "why")),
            errors = listOf("历史错误"),
        )
        val cleared = state.clearedForDisconnect()

        // 易变数据清空
        assertNull(cleared.connectedDevice)
        assertEquals(emptyList(), cleared.sessions)
        assertEquals(emptyList(), cleared.agents)
        assertEquals(emptyList(), cleared.workspaces)
        assertNull(cleared.currentSessionId)
        assertEquals(emptyList(), cleared.events)
        assertEquals(emptyList(), cleared.approvals)

        // 用户偏好保留：工作区选择在重连后仍是用户上次的视图
        assertEquals("w1", cleared.selectedWorkspaceId)
        assertEquals(listOf("历史错误"), cleared.errors)
    }

    // ---- 探测结果合并（applyPingResults） ----

    @Test
    fun applyPingResults_lastSeenThrottled() {
        val now = 1_000_000L
        val fresh = device("a", lastSeenAt = now - 60_000) // 1 分钟前 seen，间隔内
        val stale = device("b", lastSeenAt = now - LAST_SEEN_PERSIST_INTERVAL_MS - 1)
        val out = applyPingResults(
            listOf(fresh, stale),
            listOf(
                PingResult(deviceKey(fresh), DeviceStatus.Online),
                PingResult(deviceKey(stale), DeviceStatus.Online),
            ),
            now,
        )
        assertEquals(fresh, out[0]) // 节流窗口内：原样返回（不落盘）
        assertEquals(now, out[1].lastSeenAt) // 超窗：刷新
    }

    @Test
    fun applyPingResults_fillsMissingFingerprint() {
        val d = device("a", serverId = null, hostname = null, lastSeenAt = nowMillis())
        val out = applyPingResults(
            listOf(d),
            listOf(PingResult(deviceKey(d), DeviceStatus.Online, "srv-9", "new-host")),
            nowMillis(),
        )
        assertEquals("srv-9", out[0].serverId)
        assertEquals("new-host", out[0].hostname)
        // 已有指纹不覆盖（防探到别的机器时串指纹）
        val d2 = device("b", serverId = "old", lastSeenAt = nowMillis())
        val out2 = applyPingResults(
            listOf(d2),
            listOf(PingResult(deviceKey(d2), DeviceStatus.Online, "srv-9", "new-host")),
            nowMillis(),
        )
        assertEquals("old", out2[0].serverId)
    }

    @Test
    fun applyPingResults_offlineUntouched() {
        val d = device("a", lastSeenAt = 0)
        val out = applyPingResults(
            listOf(d),
            listOf(PingResult(deviceKey(d), DeviceStatus.Offline)),
            nowMillis(),
        )
        assertEquals(d, out[0])
    }

    @Test
    fun applyPingResults_noChangeReturnsSameList_noDiskWrite() {
        val d = device("a", lastSeenAt = nowMillis()) // 全部字段已齐且在节流窗内
        val out = applyPingResults(
            listOf(d),
            listOf(PingResult(deviceKey(d), DeviceStatus.Online, "srv", "mac")),
            nowMillis(),
        )
        assertTrue(out === listOf(d) || out == listOf(d)) // 相等即调用方跳过落盘
    }
}
