package com.daniel.dshremote

/**
 * wire 协议 `EventProjection.source` 的取值（与桥 protocol.ts 的 `EventSource` 对齐）。
 * 铁律 6：分类必须在桥侧完成（按 DSH 会话日志节点 source.kind 权威判定），
 * 客户端只做渲染映射、不做本地推算。
 */
object MessageSource {
    /** 真实用户输入（DSH UserMessage.source.kind === 'user'）。 */
    const val USER = "user"

    /** 注入的上下文/系统消息（agent.inject()/plugin/steer/压缩检查点/session 起始等）。 */
    const val INJECT = "inject"
}

/**
 * 判定一条 `user_message` 行是否应降级为「上下文/注入」行（而非用户气泡）。
 *
 * 规则（向后兼容 + 前向安全）：
 * - source == null（旧桥无 source 字段）→ false，按用户气泡呈现（保持老行为）。
 * - source == "user" → false，用户气泡。
 * - 其它任何非空值（"inject" 及未来的 "context"/"recall" 等）→ true，弱化呈现，
 *   不得与用户消息混淆。
 *
 * 纯函数，便于单测；渲染分支见 App.kt 的 EventBubble。
 */
fun isInjectedUserMessage(source: String?): Boolean =
    source != null && source != MessageSource.USER
