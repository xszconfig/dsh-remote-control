package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentReturnStackTest {

    // ---- push：下钻压栈 + 去重 ----

    @Test
    fun push_appends_parent() {
        assertEquals(listOf("m"), subagentReturnPush(emptyList(), "m"))
        assertEquals(listOf("m", "a"), subagentReturnPush(listOf("m"), "a"))
        assertEquals(listOf("m", "a", "b"), subagentReturnPush(listOf("m", "a"), "b"))
    }

    @Test
    fun push_dedupes_repeated_top() {
        // 连续对同一父会话压栈是幂等的（防御重复 openSubagent）
        assertEquals(listOf("m"), subagentReturnPush(listOf("m"), "m"))
        assertEquals(listOf("m", "a"), subagentReturnPush(listOf("m", "a"), "a"))
    }

    // ---- pop / peek / root：返回目标解析 ----

    @Test
    fun pop_and_peek_walk_back_level_by_level() {
        val stack = listOf("m", "a", "b") // m→a→b，当前在 b 的子代理
        assertEquals("b", subagentReturnPeek(stack), "立即父 = 栈顶")
        assertEquals("m", subagentReturnRoot(stack), "根主会话 = 栈底")

        val s2 = subagentReturnPop(stack)
        assertEquals(listOf("m", "a"), s2)
        assertEquals("a", subagentReturnPeek(s2))

        val s1 = subagentReturnPop(s2)
        assertEquals(listOf("m"), s1)
        assertEquals("m", subagentReturnPeek(s1))

        val s0 = subagentReturnPop(s1)
        assertEquals(emptyList(), s0)
        assertNull(subagentReturnPeek(s0), "空栈 = 回到会话列表")
        assertNull(subagentReturnRoot(s0))
    }

    @Test
    fun pop_on_empty_stays_empty() {
        assertEquals(emptyList(), subagentReturnPop(emptyList()))
        assertNull(subagentReturnPeek(emptyList()))
        assertNull(subagentReturnRoot(emptyList()))
    }

    // ---- 完整多级下钻→逐级返回链路：C→B→A→主会话→列表 ----

    @Test
    fun full_drill_then_return_chain() {
        var stack = emptyList<String>()
        stack = subagentReturnPush(stack, "m") // 打开 A（父=m）
        stack = subagentReturnPush(stack, "a") // 打开 B（父=a）
        stack = subagentReturnPush(stack, "b") // 打开 C（父=b）
        assertEquals(listOf("m", "a", "b"), stack)

        // 返回 C→B
        assertEquals("b", subagentReturnPeek(stack))
        stack = subagentReturnPop(stack) // [m, a]
        // 返回 B→A
        assertEquals("a", subagentReturnPeek(stack))
        stack = subagentReturnPop(stack) // [m]
        // 返回 A→主会话 m
        assertEquals("m", subagentReturnPeek(stack))
        stack = subagentReturnPop(stack) // []
        // 返回主会话→列表
        assertNull(subagentReturnPeek(stack))
    }

    // ---- prune：服务端快照对账（删层则后代层一并失效）----

    @Test
    fun prune_keeps_live_chain() {
        val live = setOf("m", "a", "b")
        assertEquals(listOf("m", "a", "b"), pruneSubagentReturn(listOf("m", "a", "b"), live))
    }

    @Test
    fun prune_cuts_at_first_missing_ancestor() {
        // a 被删 → a 及更深层（b）一并失效，仅保留 m
        assertEquals(listOf("m"), pruneSubagentReturn(listOf("m", "a", "b"), setOf("m", "b")))
        // 根 m 被删 → 整链失效
        assertEquals(emptyList(), pruneSubagentReturn(listOf("m", "a", "b"), setOf("a", "b")))
    }
}
