package com.daniel.dshremote

import com.daniel.dshremote.protocol.ApprovalDecision
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 未连接状态下指令发送失败的用户可见性回归：
 * 旧实现 ws?.send 静默丢弃且无任何提示；现在必须进入 errors 状态并可被 UI 展示。
 */
class BridgeClientTest {

    private fun newClient(scope: kotlinx.coroutines.CoroutineScope): BridgeClient {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-client-test-${System.nanoTime()}")
        return BridgeClient(scope, AndroidDeviceStore(dir))
    }

    @Test
    fun subscribeFailureWhileDisconnected_surfacesError() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("s1")
        runCurrent() // 执行 scope.launch 里的 send → ws 为 null → 失败
        assertEquals(listOf("订阅会话失败（连接已断开）"), client.state.value.errors)
    }

    @Test
    fun sendFailures_cappedAtMaxErrors() = runTest {
        val client = newClient(backgroundScope)
        repeat(30) { client.approve("a$it", ApprovalDecision.AllowedOnce) }
        runCurrent()
        assertEquals(MAX_ERRORS, client.state.value.errors.size)
        // 只保留最近的：最后一条对应 a29
        assertEquals(true, client.state.value.errors.last().contains("审批决策发送失败"))
    }

    @Test
    fun dismissErrors_clearsList() = runTest {
        val client = newClient(backgroundScope)
        client.interrupt("s1")
        runCurrent()
        assertEquals(1, client.state.value.errors.size)
        client.dismissErrors()
        assertEquals(emptyList(), client.state.value.errors)
    }

    @Test
    fun closeSession_isLocalNavigation_noProtocolTraffic() = runTest {
        // 回归：旧版返回按钮调 openSession("") 会向 bridge 发 subscribe:"",
        // bridge 回 error not_found；现在必须纯本地切换且不产生任何错误
        val client = newClient(backgroundScope)
        client.openSession("s1")
        client.closeSession()
        runCurrent()
        assertEquals(null, client.state.value.currentSessionId)
        assertEquals(emptyList(), client.state.value.events)
        assertEquals(1, client.state.value.errors.size) // 只有订阅失败那条，无 not_found
    }

    @Test
    fun openSession_blankId_ignored() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("")
        client.openSession("  ")
        runCurrent()
        assertEquals(null, client.state.value.currentSessionId)
        assertEquals(0, client.state.value.errors.size)
    }
}
