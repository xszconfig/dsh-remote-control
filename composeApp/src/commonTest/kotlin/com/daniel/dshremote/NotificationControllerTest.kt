package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeHost(
    var foreground: Boolean = true,
    var currentSessionId: String? = null,
) : NotificationHost {
    val posts = mutableListOf<NotificationSpec>()
    val cancels = mutableListOf<String>()

    override fun isForeground() = foreground
    override fun currentSessionId() = currentSessionId
    override fun post(spec: NotificationSpec) { posts.add(spec) }
    override fun cancel(tag: String) { cancels.add(tag) }
}

class NotificationControllerTest {

    private fun controller(host: FakeHost, minutes: Int = 12 * 60): NotificationController =
        NotificationController(host, nowMinutes = { minutes })

    // ---- 纯函数：三态门控 presenceOf ----

    @Test
    fun presenceOf_browsingCurrent() {
        assertEquals(Presence.BROWSING_CURRENT, presenceOf(true, "s1", "s1"))
    }

    @Test
    fun presenceOf_otherSession() {
        assertEquals(Presence.OTHER_SESSION, presenceOf(true, "s1", "s2"))
        assertEquals(Presence.OTHER_SESSION, presenceOf(true, null, "s1"))
    }

    @Test
    fun presenceOf_background() {
        assertEquals(Presence.BACKGROUND, presenceOf(false, "s1", "s1"))
        assertEquals(Presence.BACKGROUND, presenceOf(false, null, null))
    }

    // ---- 纯函数：decideNotification ----

    @Test
    fun decide_browsingCurrentNeverPosts() {
        assertFalse(decideNotification(NotificationKind.APPROVAL, Presence.BROWSING_CURRENT, true).post)
        assertFalse(decideNotification(NotificationKind.DELIVERY, Presence.BROWSING_CURRENT, false).post)
    }

    @Test
    fun decide_approvalQuestionNeverSilent() {
        assertTrue(decideNotification(NotificationKind.APPROVAL, Presence.BACKGROUND, true).post)
        assertFalse(decideNotification(NotificationKind.APPROVAL, Presence.BACKGROUND, true).silent)
        assertFalse(decideNotification(NotificationKind.QUESTION, Presence.OTHER_SESSION, true).silent)
    }

    @Test
    fun decide_deliverySilentInDnd() {
        assertTrue(decideNotification(NotificationKind.DELIVERY, Presence.BACKGROUND, true).post)
        assertTrue(decideNotification(NotificationKind.DELIVERY, Presence.BACKGROUND, true).silent)
        assertFalse(decideNotification(NotificationKind.DELIVERY, Presence.BACKGROUND, false).silent)
    }

    // ---- 纯函数：isInDndWindow（勿扰时段）----

    @Test
    fun dnd_overnightWindow() {
        assertTrue(isInDndWindow(22 * 60, 22 * 60, 8 * 60))     // 22:00 起点（含）
        assertTrue(isInDndWindow(23 * 60, 22 * 60, 8 * 60))     // 23:00
        assertTrue(isInDndWindow(0, 22 * 60, 8 * 60))           // 00:00
        assertTrue(isInDndWindow(7 * 60 + 59, 22 * 60, 8 * 60)) // 07:59
        assertFalse(isInDndWindow(8 * 60, 22 * 60, 8 * 60))     // 08:00 终点（不含）
        assertFalse(isInDndWindow(12 * 60, 22 * 60, 8 * 60))    // 12:00
    }

    @Test
    fun dnd_sameDayWindow() {
        assertTrue(isInDndWindow(9 * 60, 9 * 60, 17 * 60))
        assertTrue(isInDndWindow(16 * 60 + 59, 9 * 60, 17 * 60))
        assertFalse(isInDndWindow(8 * 60, 9 * 60, 17 * 60))
        assertFalse(isInDndWindow(17 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun dnd_disabledWhenSameBound() {
        assertFalse(isInDndWindow(0, 0, 0))
        assertFalse(isInDndWindow(23 * 60, 8 * 60, 8 * 60))
    }

    // ---- 控制器：审批/提问幂等去重 ----

    @Test
    fun approval_dedupedById() {
        val host = FakeHost(foreground = false)
        val c = controller(host)
        c.onApprovalArrived("a1", "s1", "bash", "需要确认", null)
        c.onApprovalArrived("a1", "s1", "bash", "需要确认", null)
        assertEquals(1, host.posts.size)
        assertEquals(NotificationKind.APPROVAL, host.posts[0].kind)
        assertFalse(host.posts[0].silent)
    }

    @Test
    fun approval_resolvedCancelsThenReNotifiesOnReArrival() {
        val host = FakeHost(foreground = false)
        val c = controller(host)
        c.onApprovalArrived("a1", "s1", "bash", null, null)
        c.onApprovalResolved("a1")
        assertEquals(listOf("approval:a1"), host.cancels)
        // 同一 id 重新到达（已从幂等集合移除）→ 再次通知
        c.onApprovalArrived("a1", "s1", "bash", null, null)
        assertEquals(2, host.posts.size)
    }

    @Test
    fun browsingCurrent_suppressesThenNotifiesAfterSwitch() {
        val host = FakeHost(foreground = true, currentSessionId = "s1")
        val c = controller(host)
        c.onApprovalArrived("a1", "s1", "bash", null, null)
        assertEquals(0, host.posts.size)
        // 切到其它会话后再次触发（重连 hello 补发）→ 通知
        host.currentSessionId = "s2"
        c.onApprovalArrived("a1", "s1", "bash", null, null)
        assertEquals(1, host.posts.size)
    }

    @Test
    fun question_dedupedByRpcId() {
        val host = FakeHost(foreground = false)
        val c = controller(host)
        c.onQuestionArrived("q1", "s1", 1, "是否继续？")
        c.onQuestionArrived("q1", "s1", 1, "是否继续？")
        assertEquals(1, host.posts.size)
        assertEquals(NotificationKind.QUESTION, host.posts[0].kind)
    }

    // ---- 控制器：结果交付 ----

    @Test
    fun delivery_dedupedByTurnKey() {
        val host = FakeHost(foreground = false)
        val c = controller(host)
        c.onDelivery("s1", "会话A", false, "t1", blocked = false, blockedMessage = null)
        c.onDelivery("s1", "会话A", false, "t1", blocked = false, blockedMessage = null)
        assertEquals(1, host.posts.size)
        c.onDelivery("s1", "会话A", false, "t2", blocked = false, blockedMessage = null)
        assertEquals(2, host.posts.size)
    }

    @Test
    fun delivery_blockedUsesBlockedTitle() {
        val host = FakeHost(foreground = false)
        val c = controller(host)
        c.onDelivery("s1", "会话A", false, "t1", blocked = true, blockedMessage = "依赖缺失")
        assertEquals(1, host.posts.size)
        assertEquals(DELIVERY_BLOCKED_TITLE, host.posts[0].title)
        assertTrue(host.posts[0].body.contains("目标受阻"))
    }

    @Test
    fun delivery_silentInDnd_approvalNotSilent() {
        val hostDelivery = FakeHost(foreground = false)
        val cDelivery = controller(hostDelivery, minutes = 23 * 60)
        cDelivery.onDelivery("s1", "会话A", false, "t1", blocked = false, blockedMessage = null)
        assertTrue(hostDelivery.posts[0].silent)

        val hostApproval = FakeHost(foreground = false)
        val cApproval = controller(hostApproval, minutes = 23 * 60)
        cApproval.onApprovalArrived("a1", "s1", "bash", null, null)
        assertFalse(hostApproval.posts[0].silent)
    }

    @Test
    fun delivery_browsingCurrent_suppresses() {
        val host = FakeHost(foreground = true, currentSessionId = "s1")
        val c = controller(host)
        c.onDelivery("s1", "会话A", false, "t1", blocked = false, blockedMessage = null)
        assertEquals(0, host.posts.size)
    }

    // ---- 文案构建 ----

    @Test
    fun approvalBody_prefersReasonThenCommand() {
        assertEquals("bash：确认删除？", approvalBody("bash", "确认删除？", null))
        assertEquals("bash：rm -rf x", approvalBody("bash", null, "rm -rf x"))
        assertEquals("bash", approvalBody("bash", null, null))
    }

    @Test
    fun questionBody_singleAndMultiple() {
        assertEquals("是否继续？", questionBody(1, "是否继续？"))
        assertTrue(questionBody(3, "第一个问题").startsWith("3 个问题"))
    }

    @Test
    fun truncate_capsAt80() {
        val long = "x".repeat(200)
        assertEquals(80, truncateForNotification(long).length)
    }
}
