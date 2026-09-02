package com.daniel.dshremote

import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.CachedSessionSnapshot
import com.daniel.dshremote.protocol.EventProjection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 统一连接状态提示槽（ConnectionNotice）回归：
 * 连接类错误（「连接已断开」）可自动恢复、重连中抑制、hello 到达清除；
 * 业务类错误保留需手动清除；旧的 errors 横幅并入单一槽。
 */
class BridgeClientTest {

    private fun newClient(scope: CoroutineScope): BridgeClient {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-client-test-${System.nanoTime()}")
        return BridgeClient(
            scope = scope,
            store = AndroidDeviceStore(dir),
            eventCache = object : EventCache {
                override suspend fun load(key: String): List<EventProjection> = emptyList()
                override suspend fun save(key: String, events: List<EventProjection>) {}
            },
            sessionCache = object : SessionCache {
                override suspend fun load(key: String): CachedSessionSnapshot? = null
                override suspend fun save(key: String, snapshot: CachedSessionSnapshot) {}
            },
            draftCache = object : DraftCache {
                override suspend fun load(key: String): String? = null
                override suspend fun save(key: String, text: String) {}
            },
            bootNoticeCache = object : BootNoticeCache {
                override suspend fun load(key: String): String? = null
                override suspend fun save(key: String, version: String) {}
            },
        )
    }

    @Test
    fun subscribeFailureWhileDisconnected_surfacesError() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("s1")
        runCurrent() // 执行 scope.launch 里的 send → 未连接 → 失败
        assertEquals(listOf(NoticeError("订阅会话失败（连接已断开）", recoverable = true)), client.session.value.errors)
        assertEquals(ConnectionNotice.Error("订阅会话失败（连接已断开）"), client.notice.value)
    }

    @Test
    fun sendFailures_cappedAtMaxErrors() = runTest {
        val client = newClient(backgroundScope)
        repeat(30) { i ->
            client.approve(
                ApprovalRequestWire(approvalId = "a$i", sessionId = "s1", toolName = "bash"),
                ApprovalDecision.AllowedOnce,
            )
        }
        runCurrent()
        assertEquals(MAX_ERRORS, client.session.value.errors.size)
        // 只保留最近的：最后一条对应审批发送失败（连接类 → 可自动恢复）
        assertEquals(true, client.session.value.errors.last().message.contains("审批决策发送失败"))
        assertEquals(true, client.session.value.errors.last().recoverable)
    }

    @Test
    fun dismissErrors_clearsList_andNotice() = runTest {
        val client = newClient(backgroundScope)
        client.interrupt("s1")
        runCurrent()
        assertEquals(1, client.session.value.errors.size)
        assertEquals(ConnectionNotice.Error("中断指令发送失败（连接已断开）"), client.notice.value)
        client.dismissErrors()
        assertEquals(emptyList(), client.session.value.errors)
        assertEquals(ConnectionNotice.Hidden, client.notice.value)
    }

    @Test
    fun closeSession_isLocalNavigation_noProtocolTraffic() = runTest {
        // 回归：旧版返回按钮调 openSession("") 会向 bridge 发 subscribe:"",
        // bridge 回 error not_found；现在必须纯本地切换且不产生任何错误
        val client = newClient(backgroundScope)
        client.openSession("s1")
        client.closeSession()
        runCurrent()
        assertEquals(null, client.session.value.currentSessionId)
        assertEquals(emptyList(), client.session.value.events)
        assertEquals(1, client.session.value.errors.size) // 只有订阅失败那条，无 not_found
    }

    @Test
    fun openSession_blankId_ignored() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("")
        client.openSession("  ")
        runCurrent()
        assertEquals(null, client.session.value.currentSessionId)
        assertEquals(0, client.session.value.errors.size)
    }

    @Test
    fun hello_clearsRecoverableErrors_keepsBusinessErrors() {
        assertEquals(
            listOf(NoticeError("not_found: session not found: x", recoverable = false)),
            reconcileErrorsOnHello(
                listOf(
                    NoticeError("订阅会话失败（连接已断开）", recoverable = true),
                    NoticeError("not_found: session not found: x", recoverable = false),
                    NoticeError("设备注册失败（连接已断开）", recoverable = true),
                ),
            ),
        )
    }

    @Test
    fun connectionErrors_suppressedWhileReconnecting() = runTest {
        val client = newClient(backgroundScope)
        client.beginReconnectNotice(1)
        client.openSession("s1")
        runCurrent()
        // 重连中：连接类错误被抑制（不堆积、不覆盖 Reconnecting 槽）
        assertEquals(0, client.session.value.errors.size)
        assertTrue(client.notice.value is ConnectionNotice.Reconnecting)
    }

    @Test
    fun pushBusinessError_isNotRecoverable_andShown() = runTest {
        val client = newClient(backgroundScope)
        client.pushBusinessError("not_found: session not found: x")
        assertEquals(listOf(NoticeError("not_found: session not found: x", recoverable = false)), client.session.value.errors)
        assertEquals(ConnectionNotice.Error("not_found: session not found: x"), client.notice.value)
    }
}
