package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectDiagnosticsTest {

    @Test
    fun failureDetail_listsPerHostReasons() {
        val detail = buildConnectFailureDetail(
            listOf(
                "127.0.0.1" to "Connection refused",
                "192.168.3.82" to "Connect timeout",
                "100.76.121.118" to "Connect timeout",
            ),
        )
        assertTrue(detail.startsWith("所有候选地址均连接失败"))
        assertTrue(detail.contains("127.0.0.1：Connection refused"))
        assertTrue(detail.contains("192.168.3.82：Connect timeout"))
        // 含 127.0.0.1 候选 → 必须给出 adb reverse 指引
        assertTrue(detail.contains("adb reverse tcp:3080 tcp:3080"))
    }

    @Test
    fun failureDetail_withoutLoopback_noUsbHint() {
        val detail = buildConnectFailureDetail(listOf("192.168.3.82" to "timeout"))
        assertFalse(detail.contains("adb reverse"))
    }

    @Test
    fun failureDetail_capsListAtThree() {
        val detail = buildConnectFailureDetail(
            (1..5).map { "10.0.0.$it" to "timeout" },
        )
        assertTrue(detail.contains("10.0.0.1：timeout"))
        assertTrue(detail.contains("10.0.0.3：timeout"))
        assertFalse(detail.contains("10.0.0.4："))
        assertTrue(detail.contains("…等 5 个候选"))
    }

    @Test
    fun failureDetail_blankReasonGetsFallback() {
        val detail = buildConnectFailureDetail(listOf("127.0.0.1" to ""))
        assertTrue(detail.contains("127.0.0.1：连接失败"))
    }
}
