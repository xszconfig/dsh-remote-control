package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentTreeTest {

    private fun sess(id: String, parentId: String? = null) = SessionSummary(
        id = id,
        cwd = "/work/$id",
        status = "idle",
        agentCount = 1,
        subagentCount = 0,
        updatedAt = 0L,
        parentSessionId = parentId,
    )

    // ---- childrenByParent：仅按非空 parentSessionId 分组 ----

    @Test
    fun children_grouped_by_direct_parent() {
        val s = listOf(sess("root"), sess("a", "root"), sess("b", "root"), sess("a1", "a"))
        val c = childrenByParent(s)
        assertEquals(listOf("a", "b"), c["root"]?.map { it.id })
        assertEquals(listOf("a1"), c["a"]?.map { it.id })
        assertFalse(c.containsKey("b"), "无子代的节点不应出现在映射键中")
    }

    @Test
    fun top_level_sessions_are_not_children() {
        val c = childrenByParent(listOf(sess("x"), sess("y")))
        assertTrue(c.isEmpty(), "顶层会话（parent=null）不应成为任何节点的子代")
    }

    // ---- descendantCounts：沿父链递归累加（含孙代）----

    @Test
    fun descendant_count_includes_grandchildren() {
        // root -> a -> a1, a2 ; root -> b
        val s = listOf(sess("root"), sess("a", "root"), sess("b", "root"), sess("a1", "a"), sess("a2", "a"))
        val counts = descendantCounts(s)
        assertEquals(4, counts["root"], "root 后代 = a,b,a1,a2 共 4")
        assertEquals(2, counts["a"], "a 后代 = a1,a2 共 2")
        assertEquals(0, counts["a1"])
        assertEquals(0, counts["b"])
    }

    @Test
    fun descendant_count_three_levels() {
        val s = listOf(sess("r"), sess("c1", "r"), sess("c2", "c1"), sess("c3", "c2"))
        assertEquals(3, descendantCounts(s)["r"], "三层链 r->c1->c2->c3 后代 = 3")
    }

    @Test
    fun descendant_count_cycle_is_guarded() {
        // 畸形投影（理论不会发生）：a -> b -> a 环，防环兜底不无限递归
        val s = listOf(sess("a", "b"), sess("b", "a"))
        val counts = descendantCounts(s)
        assertTrue(counts.containsKey("a") && counts.containsKey("b"), "环内节点仍应返回有限计数")
    }

    // ---- flattenSubagentTree：DFS 顺序 / 深度 / 折叠控制 ----

    @Test
    fun flatten_dfs_order_with_depth() {
        val s = listOf(sess("r"), sess("a", "r"), sess("b", "r"), sess("a1", "a"), sess("b1", "b"))
        val nodes = flattenSubagentTree("r", childrenByParent(s), descendantCounts(s), expanded = setOf("a", "b"))
        assertEquals(listOf("a", "a1", "b", "b1"), nodes.map { it.session.id }, "DFS：先序，展开 a 与 b")
        assertEquals(listOf(0, 1, 0, 1), nodes.map { it.depth })
        assertEquals(listOf(true, false, true, false), nodes.map { it.hasChildren })
        assertEquals(listOf(1, 0, 1, 0), nodes.map { it.descendantCount })
    }

    @Test
    fun flatten_collapsed_hides_grandchildren() {
        val s = listOf(sess("r"), sess("a", "r"), sess("a1", "a"))
        val collapsed = flattenSubagentTree("r", childrenByParent(s), descendantCounts(s), expanded = emptySet())
        assertEquals(listOf("a"), collapsed.map { it.session.id }, "未展开时只显示一级子代")
        val expanded = flattenSubagentTree("r", childrenByParent(s), descendantCounts(s), expanded = setOf("a"))
        assertEquals(listOf("a", "a1"), expanded.map { it.session.id }, "展开 a 后显示其直接子代")
    }

    @Test
    fun flatten_no_children_returns_empty() {
        val nodes = flattenSubagentTree("r", emptyMap(), emptyMap(), expanded = emptySet())
        assertTrue(nodes.isEmpty())
    }
}
