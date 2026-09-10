package com.daniel.dshremote

import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * App.kt 拆分后暴露出的零依赖纯辅助函数单测（覆盖 commonTest 盲区，不改实现仅补断言）。
 * - formatDivingDuration（Conversation.kt）
 * - sessionName / basenameOf（SessionList.kt）
 * - statusOf / statusLabel（LandingScreen.kt）
 */
class AppPureHelpersTest {

    // ---------- formatDivingDuration ----------

    @Test
    fun formatDivingDuration_under60Seconds() {
        assertEquals("0秒", formatDivingDuration(0))
        assertEquals("59秒", formatDivingDuration(59))
    }

    @Test
    fun formatDivingDuration_minutes() {
        assertEquals("1分", formatDivingDuration(60))
        assertEquals("1分1秒", formatDivingDuration(61))
        assertEquals("59分59秒", formatDivingDuration(3599))
    }

    @Test
    fun formatDivingDuration_hours() {
        assertEquals("1小时", formatDivingDuration(3600))
        assertEquals("1小时", formatDivingDuration(3601)) // 秒被截断
        assertEquals("1小时1分", formatDivingDuration(3660))
        assertEquals("2小时", formatDivingDuration(7200))
        assertEquals("2小时1分", formatDivingDuration(7261))
    }

    @Test
    fun formatDivingDuration_negativeClampedToZero() {
        assertEquals("0秒", formatDivingDuration(-5))
    }

    // ---------- basenameOf ----------

    @Test
    fun basenameOf_unixAndWindowsPaths() {
        assertEquals("c", basenameOf("/a/b/c"))
        assertEquals("y", basenameOf("C:\\Users\\x\\y"))
        assertEquals("foo", basenameOf("foo"))
    }

    @Test
    fun basenameOf_trailingSeparatorTrimmed() {
        assertEquals("b", basenameOf("/a/b/"))
        assertEquals("b", basenameOf("a\\b\\"))
    }

    // ---------- sessionName ----------

    private fun session(id: String, name: String?, cwd: String) = SessionSummary(
        id = id,
        name = name,
        cwd = cwd,
        status = "idle",
        agentCount = 1,
        subagentCount = 0,
        updatedAt = 0,
    )

    @Test
    fun sessionName_prefersNonBlankName() {
        assertEquals("My Session", sessionName(session("id-1", "My Session", "/proj")))
    }

    @Test
    fun sessionName_fallsBackToCwdBasename() {
        assertEquals("proj", sessionName(session("id-1", null, "/home/u/proj")))
        assertEquals("proj", sessionName(session("id-1", "  ", "/home/u/proj")))
    }

    @Test
    fun sessionName_fallsBackToIdPrefixWhenCwdBlank() {
        assertEquals("123456789012", sessionName(session("123456789012abc", null, "")))
    }

    // ---------- statusOf / statusLabel ----------

    private fun device(host: String, port: Int) = StoredDevice(
        deviceId = "dev-1",
        name = "desktop",
        host = host,
        port = port,
        createdAt = 0,
        lastSeenAt = 0,
    )

    @Test
    fun statusOf_readsFromStatusMapByHostPort() {
        val d = device("192.168.1.5", 3080)
        val state = DevicesUiState(deviceStatuses = mapOf("192.168.1.5:3080" to DeviceStatus.Online))
        assertEquals(DeviceStatus.Online, statusOf(state, d))
    }

    @Test
    fun statusOf_defaultsToCheckingWhenAbsent() {
        val d = device("192.168.1.5", 3080)
        assertEquals(DeviceStatus.Checking, statusOf(DevicesUiState(), d))
    }

    @Test
    fun statusLabel_mapsAllStatuses() {
        assertEquals("在线 · DSH Web 运行中", statusLabel(DeviceStatus.Online))
        assertEquals("检测中…", statusLabel(DeviceStatus.Checking))
        assertEquals("在线 · 设备已更换（点击重连）", statusLabel(DeviceStatus.Changed))
        assertEquals("离线 · 无法探测到 DSH Web", statusLabel(DeviceStatus.Offline))
    }
}
