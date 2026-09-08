package com.daniel.dshremote

import com.daniel.dshremote.protocol.EventProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDialMathTest {

    private fun ev(seq: Long, type: String, source: String? = null) =
        EventProjection(seq, type, source = source, timestamp = seq)

    // ---- userMessageRefs：索引映射（reverseLayout + liveThink 偏移）----

    @Test
    fun userMessageRefs_filtersUserMessagesOnly() {
        val events = listOf(
            ev(1, "user_message"),
            ev(2, "assistant_message"),
            ev(3, "tool_call"),
            ev(4, "user_message"),
        )
        val refs = userMessageRefs(events, hasLiveThink = false)
        assertEquals(2, refs.size)
        assertEquals(listOf(1L, 4L), refs.map { it.seq })
    }

    @Test
    fun userMessageRefs_rowIndex_reverseLayoutMapping() {
        // events 旧→新，size=4；无 liveThink：rowIndex = (size-1-j)
        // j=0(seq1) → 3；j=3(seq4) → 0
        val events = listOf(ev(1, "user_message"), ev(2, "assistant_message"), ev(3, "assistant_message"), ev(4, "user_message"))
        val refs = userMessageRefs(events, hasLiveThink = false)
        // 最老在前：seq1 行号最大，seq4 行号最小
        assertEquals(UserMsgRef(1, 3), refs[0])
        assertEquals(UserMsgRef(4, 0), refs[1])
    }

    @Test
    fun userMessageRefs_rowIndex_liveThinkOffset() {
        // liveThink 占 index 0 → 每行 +1
        val events = listOf(ev(1, "user_message"), ev(2, "assistant_message"), ev(3, "user_message"))
        val refs = userMessageRefs(events, hasLiveThink = true)
        // size=3：j=0 → (3-1-0)+1 = 3；j=2 → (3-1-2)+1 = 1
        assertEquals(UserMsgRef(1, 3), refs[0])
        assertEquals(UserMsgRef(3, 1), refs[1])
    }

    @Test
    fun userMessageRefs_empty() {
        assertTrue(userMessageRefs(emptyList(), hasLiveThink = false).isEmpty())
        assertTrue(userMessageRefs(listOf(ev(1, "assistant_message")), hasLiveThink = true).isEmpty())
    }

    @Test
    fun userMessageRefs_excludesInjectedSource() {
        val events = listOf(
            ev(1, "user_message", source = "user"),    // 真实用户 → 收
            ev(2, "user_message", source = "inject"),  // 注入 → 排除
            ev(3, "user_message"),                     // 旧桥无 source(null) → 兼容收
            ev(4, "user_message", source = "user"),
        )
        val refs = userMessageRefs(events, hasLiveThink = false)
        assertEquals(listOf(1L, 3L, 4L), refs.map { it.seq })
    }

    @Test
    fun userMessageRefs_unknownSourceExcluded() {
        // 前向安全：未来新 source（如 context/recall）不得混入用户定位目标
        val events = listOf(
            ev(1, "user_message", source = "context"),
            ev(2, "user_message", source = "user"),
        )
        val refs = userMessageRefs(events, hasLiveThink = false)
        assertEquals(listOf(2L), refs.map { it.seq })
    }

    // ---- normalizeAngleDelta：跨 ±180° 回绕 ----

    @Test
    fun normalizeAngleDelta_wraps() {
        assertEquals(10f, normalizeAngleDelta(10f))
        assertEquals(-10f, normalizeAngleDelta(-10f))
        assertEquals(-10f, normalizeAngleDelta(350f))
        assertEquals(10f, normalizeAngleDelta(-350f))
        assertEquals(180f, normalizeAngleDelta(180f))
        assertEquals(180f, normalizeAngleDelta(-180f)) // 映射到 (-180, 180]，-180 归一为 180
    }

    // ---- stepsCrossed：角度 → 步进换算（方向 + 不到一格不触发）----

    @Test
    fun stepsCrossed_directionAndMagnitude() {
        assertEquals(1, stepsCrossed(0f, 18.1f, DIAL_STEP_ANGLE_DEG))   // 顺时针 1 格
        assertEquals(-1, stepsCrossed(0f, -18.1f, DIAL_STEP_ANGLE_DEG)) // 逆时针 1 格
        assertEquals(2, stepsCrossed(0f, 36.2f, DIAL_STEP_ANGLE_DEG))   // 快速 2 格
        assertEquals(-2, stepsCrossed(0f, -36.2f, DIAL_STEP_ANGLE_DEG))
    }

    @Test
    fun stepsCrossed_noTriggerBelowOneStep() {
        assertEquals(0, stepsCrossed(0f, 17.9f, DIAL_STEP_ANGLE_DEG))
        assertEquals(0, stepsCrossed(0f, -17.9f, DIAL_STEP_ANGLE_DEG))
        assertEquals(0, stepsCrossed(0f, -0.1f, DIAL_STEP_ANGLE_DEG)) // 关键：微小逆时针不误触发
        assertEquals(0, stepsCrossed(18.1f, 18.1f, DIAL_STEP_ANGLE_DEG)) // 无变化
    }

    // ---- stepResult：状态机单步决策 ----

    private val refs3 = listOf(UserMsgRef(10, 30), UserMsgRef(20, 20), UserMsgRef(30, 10))

    @Test
    fun stepResult_jumpToAdjacent() {
        // 从中间(下标1)顺时针一格(delta=+1) → 更老的 seq10
        assertEquals(DialStepResult.Jump(30, 10), stepResult(refs3, 1, 1, hasMore = false))
        // 从中间逆时针一格(delta=-1) → 更新的 seq30
        assertEquals(DialStepResult.Jump(10, 30), stepResult(refs3, 1, -1, hasMore = false))
    }

    @Test
    fun stepResult_boundaryAndPaging() {
        // delta=+1(顺时针)=更老：下标 0 再往更老 → 触底/需翻页
        assertEquals(DialStepResult.AtOlderBoundary, stepResult(refs3, 0, 1, hasMore = false))
        assertEquals(DialStepResult.NeedOlderPage, stepResult(refs3, 0, 1, hasMore = true))
        // delta=-1(逆时针)=更新：下标 2 再往更新 → 触顶
        assertEquals(DialStepResult.AtNewerBoundary, stepResult(refs3, 2, -1, hasMore = true))
    }

    @Test
    fun stepResult_emptyRefs() {
        assertEquals(DialStepResult.AtOlderBoundary, stepResult(emptyList(), -1, -1, hasMore = false))
        assertEquals(DialStepResult.NeedOlderPage, stepResult(emptyList(), -1, -1, hasMore = true))
    }

    // ---- seekOutcome：卡住→唤醒决策 ----

    @Test
    fun seekOutcome_resumeWhenNewerUserMessageFound() {
        // 狩猎前最老 seq=10；翻页后最老 seq=5 → 找到更老用户消息，锚定保留原 selectedSeq(10)
        val refs = listOf(UserMsgRef(5, 50), UserMsgRef(10, 40))
        assertEquals(
            SeekOutcome.Resume(10L),
            seekOutcome(refs, seekOldestSeq = 10, selectedSeq = 10, hasMore = false, fingerDown = true, seekPages = 1),
        )
    }

    @Test
    fun seekOutcome_chainWhileFingerDown() {
        // 页内无更老用户消息（newOldest 仍是 10），按住 + 有更多历史 + 未到上限 → 链式翻页
        val refs = listOf(UserMsgRef(10, 40))
        assertEquals(
            SeekOutcome.ChainMore,
            seekOutcome(refs, seekOldestSeq = 10, selectedSeq = 10, hasMore = true, fingerDown = true, seekPages = 3),
        )
    }

    @Test
    fun seekOutcome_chainStopsWhenFingerUp_orCap() {
        val refs = listOf(UserMsgRef(10, 40))
        // 手指抬起 → 不再链式，snap 到最老
        assertEquals(
            SeekOutcome.SnapToOldest(10, 40),
            seekOutcome(refs, seekOldestSeq = 10, selectedSeq = 10, hasMore = true, fingerDown = false, seekPages = 3),
        )
        // 到 10 页上限 → snap
        assertEquals(
            SeekOutcome.SnapToOldest(10, 40),
            seekOutcome(refs, seekOldestSeq = 10, selectedSeq = 10, hasMore = true, fingerDown = true, seekPages = 10),
        )
    }

    @Test
    fun seekOutcome_nothingFound() {
        assertEquals(
            SeekOutcome.NothingFound,
            seekOutcome(emptyList(), seekOldestSeq = null, selectedSeq = null, hasMore = false, fingerDown = true, seekPages = 0),
        )
    }

    // ---- nearestUserSeq ----

    @Test
    fun nearestUserSeq_picksClosestRow() {
        val refs = listOf(UserMsgRef(1, 100), UserMsgRef(2, 50), UserMsgRef(3, 10))
        assertEquals(2L, nearestUserSeq(refs, viewportCenterRow = 45))
        assertNull(nearestUserSeq(emptyList(), 0))
    }

    // ---- shouldVibrate：60ms 合并节流 ----

    @Test
    fun shouldVibrate_throttle() {
        assertTrue(shouldVibrate(lastTickMs = 0, nowMs = 100))
        assertFalse(shouldVibrate(lastTickMs = 100, nowMs = 140)) // 40ms < 60ms
        assertTrue(shouldVibrate(lastTickMs = 100, nowMs = 160))  // 60ms 边界
    }

    // ---- scrollDeltaToTop：reverseLayout 顶部定位补偿 ----

    @Test
    fun scrollDeltaToTop_signAndMagnitude() {
        assertEquals(-920, scrollDeltaToTop(viewportHeightPx = 1000, itemHeightPx = 80))  // 行短于视口 → 负（向上）
        assertEquals(0, scrollDeltaToTop(viewportHeightPx = 1000, itemHeightPx = 1000))   // 行=视口 → 不动
        assertEquals(200, scrollDeltaToTop(viewportHeightPx = 1000, itemHeightPx = 1200)) // 超长行 → 正（向下对齐顶部）
    }

    // ---- showDial：按需显示（手指活动优先于「到底隐藏」）----

    @Test
    fun showDial_visibleWhenNotAtBottom() {
        // 上翻离开底部 → 显示（收起态圆钮）
        assertTrue(showDial(showJumpToBottom = true, phase = DialPhase.Collapsed))
    }

    @Test
    fun showDial_hiddenOnlyWhenCollapsedAndAtBottom() {
        // 已到底部且收起 → 隐藏
        assertFalse(showDial(showJumpToBottom = false, phase = DialPhase.Collapsed))
    }

    @Test
    fun showDial_staysVisibleWhenRotatingAtBottom() {
        // 已到底部但旋转中 → 保持挂载（不打断手指）
        assertTrue(showDial(showJumpToBottom = false, phase = DialPhase.Rotating))
        assertTrue(showDial(showJumpToBottom = false, phase = DialPhase.Expanded))
        assertTrue(showDial(showJumpToBottom = false, phase = DialPhase.WaitingOlder))
        assertTrue(showDial(showJumpToBottom = false, phase = DialPhase.AtBoundary))
    }
}
