package com.daniel.dshremote

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
