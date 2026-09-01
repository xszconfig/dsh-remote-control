package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「回到底部」按钮的可见性判定纯函数单测。
 *
 * 背景：消息列表用 reverseLayout LazyColumn，index 0 在底部（最新端）；
 * liveThink 流式行存在时占 index 0，最新消息退到 index 1；
 * loading-older 占最高 index（最早历史端），不影响底部判定。
 */
class JumpToBottomTest {

    @Test
    fun latestEventIndex_noLiveThink_isZero() {
        // 无流式思考行：最新事件占 index 0（底部）
        assertTrue(latestEventIndex(hasLiveThink = false) == 0)
    }

    @Test
    fun latestEventIndex_withLiveThink_isOne() {
        // 有流式思考行：index 0 被 live-think 占用，最新事件在 index 1
        assertTrue(latestEventIndex(hasLiveThink = true) == 1)
    }

    @Test
    fun latestMessageVisible_atBottom_true() {
        // 无 liveThink：最新消息 index 0，贴底时可见集合含 0
        assertTrue(latestMessageVisible(visibleIndexes = listOf(0, 1, 2), latestEventIndex = 0))
    }

    @Test
    fun latestMessageVisible_scrolledUp_false() {
        // 用户上滑看历史：最新消息 index 0 已滚出视口
        assertFalse(latestMessageVisible(visibleIndexes = listOf(5, 6, 7), latestEventIndex = 0))
    }

    @Test
    fun latestMessageVisible_partiallyVisible_true() {
        // 部分可见也算可见：最新消息 index 仍在可见集合里（哪怕只剩边缘）
        assertTrue(latestMessageVisible(visibleIndexes = listOf(0), latestEventIndex = 0))
    }

    @Test
    fun latestMessageVisible_emptyVisible_false() {
        // 尚未布局（visibleItemsInfo 为空）→ 视为不可见，避免首帧闪按钮
        assertFalse(latestMessageVisible(visibleIndexes = emptyList(), latestEventIndex = 0))
    }

    @Test
    fun latestMessageVisible_liveThinkLatestHidden_false() {
        // liveThink 存在：最新消息 index 1；只看到 liveThink（index 0）时最新消息尚未可见
        assertFalse(latestMessageVisible(visibleIndexes = listOf(0), latestEventIndex = 1))
    }

    @Test
    fun latestMessageVisible_liveThinkAtBottom_true() {
        // liveThink 存在且贴底：可见集合 [0,1]，最新消息 index 1 可见
        assertTrue(latestMessageVisible(visibleIndexes = listOf(0, 1, 2), latestEventIndex = 1))
    }
}
