package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary

/**
 * 子代理嵌套树（纯函数，commonTest 直测）。
 *
 * 数据事实（铁律 6，服务端投影为准）：`SessionSummary.parentSessionId` 是「直接父」，
 * 嵌套子代理（子代理再 spawn）的 parentSessionId = 直接父会话 id。hello 已全量下发，
 * 因此客户端按 parentSessionId 归组建树即可，展开/折叠由 UI 本地状态控制，零请求。
 */

/** 扁平化后的一个可见树节点。 */
data class SubagentNode(
    val session: SessionSummary,
    /** 相对根（当前会话的直接子代=0）的层级深度。 */
    val depth: Int,
    /** 是否有直接子代（决定是否显示展开箭头）。 */
    val hasChildren: Boolean,
    /** 后代总数（子 + 孙 + …，沿 parentSessionId 链递归累加）。 */
    val descendantCount: Int,
)

/** 父 id → 直接子代（按服务端下发顺序，仅含非空父）。 */
fun childrenByParent(sessions: List<SessionSummary>): Map<String, List<SessionSummary>> =
    sessions.filter { it.parentSessionId != null }.groupBy { it.parentSessionId!! }

/** 每个会话的后代总数（递归，防环）。 */
fun descendantCounts(sessions: List<SessionSummary>): Map<String, Int> {
    val children = childrenByParent(sessions)
    val memo = mutableMapOf<String, Int>()
    val visiting = mutableSetOf<String>()
    fun count(id: String): Int {
        memo[id]?.let { return it }
        if (id in visiting) return 0 // 防环：畸形投影不会出现，兜底返回 0
        visiting.add(id)
        val n = children[id].orEmpty().sumOf { 1 + count(it.id) }
        visiting.remove(id)
        memo[id] = n
        return n
    }
    sessions.forEach { count(it.id) }
    return memo
}

/** 扁平化可见子代理树（DFS，展开态由 [expanded] 控制），供移动端 Column 平铺渲染。 */
fun flattenSubagentTree(
    rootParentId: String,
    children: Map<String, List<SessionSummary>>,
    counts: Map<String, Int>,
    expanded: Set<String>,
): List<SubagentNode> {
    val out = mutableListOf<SubagentNode>()
    fun dfs(parentId: String, depth: Int) {
        for (c in children[parentId].orEmpty()) {
            val has = children.containsKey(c.id)
            out.add(SubagentNode(c, depth, has, counts[c.id] ?: 0))
            if (has && c.id in expanded) dfs(c.id, depth + 1)
        }
    }
    dfs(rootParentId, 0)
    return out
}
