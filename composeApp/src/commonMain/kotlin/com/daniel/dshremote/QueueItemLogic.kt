package com.daniel.dshremote

import com.daniel.dshremote.protocol.QueueItemWire

/**
 * 排队消息面板的纯逻辑（供 App.kt QueuePanel 与 BridgeClient.handleError 复用，便于单测）。
 *
 * 背景（bug 复盘见 docs/bugs/2026-09-07-steer-queue-item-not-found-message-vanished.md）：
 * 运行中发消息会先落一个本地乐观排队项（id=`local-<ts>`），等服务端 session_queue 回环广播
 * 换成真实 MessageId。若在替换前点「插队/删除」，`local-` id 在服务端 inbox 里不存在，
 * 必报 queue-item-not-found；随后该消息被本轮领取，session_queue(items=0) 整体替换本地队列，
 * 用户感知为「消息自动丢了」。
 */

/** 乐观排队项判定：id 以 `local-` 开头 = 尚未被服务端替换成真实 id，禁止插队/删除（显示「同步中…」）。 */
fun isSyncingQueueItem(id: String): Boolean = id.startsWith("local-")

/**
 * 队列操作错误 → 用户可读横幅文案；返回 null 表示无专属文案（走通用 `code: message` 横幅）。
 *
 * 关键语义（绝不静默消失）：
 * - `queue-item-not-found`：目标已被本轮领取/已被移除，消息未丢失，正在处理中。
 * - `steer-unavailable`：当前轮次不接受插队，消息仍在排队（未丢失）。
 */
fun queueErrorBanner(code: String): String? = when (code) {
    "queue-item-not-found" -> "排队消息已被本轮领取，正在处理中（未丢失）"
    "steer-unavailable" -> "当前轮次不接受插队，消息仍在排队（可稍后再插队）"
    else -> null
}

/**
 * 排队面板「展示」过滤：只保留用户来源项（placement = queued/steering），
 * 过滤系统注入项（placement = context：子代理收尾通知/报告、LSP 反馈等）。
 *
 * 仅过滤展示，不动服务端队列本身（铁律 6：队列状态由服务端管理）。
 */
fun userVisibleQueueItems(items: List<QueueItemWire>): List<QueueItemWire> =
    items.filter { it.placement != "context" }

/**
 * 乐观排队项插入：插到「排队」(queued) 段的末尾（即 steering/context 之前）。
 *
 * 服务端投影顺序是 [...nextTurn(queued), ...nextStep(steering/context)]（先进先出）；
 * 直接 append 到列表末尾会把新排队项排到 steering/context 之后，与服务端顺序不一致。
 * 此函数保证乐观项的展示顺序与服务端投影一致（FIFO）。
 */
fun insertOptimisticQueued(items: List<QueueItemWire>, opt: QueueItemWire): List<QueueItemWire> {
    val insertAt = items.indexOfFirst { it.placement != "queued" }.let { if (it == -1) items.size else it }
    return items.toMutableList().apply { add(insertAt, opt) }
}
