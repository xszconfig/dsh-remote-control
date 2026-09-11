package com.daniel.dshremote

/**
 * 子代理多级返回链（栈）纯逻辑，commonTest 直测。
 *
 * 数据模型：`SessionUiState.subagentReturnStack: List<String>` 记录从「根主会话」到
 * 「立即父会话」的层级链（栈底=根主会话，栈顶=立即父）。下钻 openSubagent 压栈，
 * 返回 closeSession 弹栈，逐级 C→B→A→主会话；切会话/断开清栈。空栈 = 无子代理打开。
 */

/** 压栈：下钻打开子代理时把当前父会话压入栈顶；栈顶已同 id 则去重（幂等）。 */
fun subagentReturnPush(stack: List<String>, parentId: String): List<String> =
    if (stack.lastOrNull() == parentId) stack else stack + parentId

/** 弹栈：返回一步后的新栈（空栈保持不变）。 */
fun subagentReturnPop(stack: List<String>): List<String> =
    if (stack.isEmpty()) stack else stack.dropLast(1)

/** 立即父会话（返回键下一步回到的目标）；空栈 = null（回到会话列表）。 */
fun subagentReturnPeek(stack: List<String>): String? = stack.lastOrNull()

/** 根主会话（平板中栏 live 渲染对象）；空栈 = null。 */
fun subagentReturnRoot(stack: List<String>): String? = stack.firstOrNull()

/**
 * 服务端快照对账：栈中某层会话已被删除时，该层及其之后的更深层一并失效（截断），
 * 保留仍存活的前缀链。返回对账后的新栈。
 */
fun pruneSubagentReturn(stack: List<String>, liveIds: Set<String>): List<String> {
    val cut = stack.indexOfFirst { it !in liveIds }
    return if (cut < 0) stack else stack.take(cut)
}
