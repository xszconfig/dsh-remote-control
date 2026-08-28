package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.WorkspaceSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeUiStateTest {

    @Test
    fun clearedForDisconnect_clearsVolatileData_preservesUserPreference() = run {
        val state = BridgeUiState(
            connection = ConnectionState.Connected,
            connectedDevice = StoredDevice(
                deviceId = "d1", name = "Mac", host = "127.0.0.1", port = 3080,
                token = "t", serverId = "srv", hostname = "mac",
                createdAt = 1, lastSeenAt = 2,
            ),
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
        // errors 保留（用户可能还没看到）
        assertEquals(listOf("历史错误"), cleared.errors)
    }

    @Test
    fun clearedForDisconnect_keepsDeviceListAndStatuses() = run {
        // 设备列表/在线状态属于首页资产，断开会话不应被清掉
        val state = BridgeUiState(
            devices = listOf(
                StoredDevice("d1", "Mac", "127.0.0.1", 3080, createdAt = 1, lastSeenAt = 1),
            ),
            deviceStatuses = mapOf("127.0.0.1:3080" to com.daniel.dshremote.protocol.DeviceStatus.Online),
        )
        val cleared = state.clearedForDisconnect()
        assertEquals(state.devices, cleared.devices)
        assertEquals(state.deviceStatuses, cleared.deviceStatuses)
    }
}
