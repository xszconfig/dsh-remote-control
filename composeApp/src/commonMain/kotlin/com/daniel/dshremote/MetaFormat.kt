package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlinx.datetime.TimeZone

/**
 * 子代理列表副标题的三段元信息格式化（纯函数，服务端投影值为唯一输入，客户端不做本地推算）。
 *
 * 格式约定（与 App 现有文案风格一致）：
 * - 最后消息时间：复用 [formatTimestamp] 的统一 IM 时间规则
 *   （刚刚 / 今天 HH:mm / 昨天 HH:mm / 前天 HH:mm / M月d日 HH:mm / yyyy年M月d日 HH:mm）；
 * - 运行时长：紧凑形式 "45s / 12m / 2h / 2h 3m"（移动端小屏优先，不写 "2 小时 3 分"）；
 * - token 量：紧凑形式 "999 / 12.3k / 1.2M"（k/M 后缀，与 🤖N 等紧凑计数风格一致）。
 */

/** 时长紧凑格式：45s / 12m / 2h / 2h 3m。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSec = ms / 1000
    if (totalSec < 60) return "${totalSec}s"
    val totalMin = totalSec / 60
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}

/** token 紧凑格式：999 / 12.3k / 1.2M（一位小数，整则省略）。 */
fun formatTokens(n: Long): String {
    if (n < 0) return "0"
    if (n < 1000) return n.toString()
    if (n < 1_000_000) return compactToken(n, 1000, "k")
    return compactToken(n, 1_000_000, "M")
}

private fun compactToken(n: Long, div: Long, suffix: String): String {
    val whole = n / div
    val frac = (n % div) * 10 / div
    return if (frac == 0L) "${whole}$suffix" else "${whole}.${frac}$suffix"
}

/**
 * 子代理副标题的元信息段（除状态标志外的三段，按序）：
 * 最后消息时间 / 总运行时长 / 总 token。缺失或为 0 的段跳过（旧版 bridge 无这些字段时优雅降级）。
 *
 * @param now 当前时间（由调用方传入，保证纯函数可测）。
 * @param timeZone 时区（默认系统时区；测试传 TimeZone.UTC 保证确定性）。
 */
fun subagentMetaSegments(s: SessionSummary, now: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<String> {
    val out = mutableListOf<String>()
    val ts = s.lastMessageAt ?: s.updatedAt
    if (ts > 0) out.add(formatTimestamp(ts, now, timeZone))
    s.runDurationMs?.takeIf { it > 0 }?.let { out.add(formatDuration(it)) }
    s.totalTokens?.takeIf { it > 0 }?.let { out.add(formatTokens(it)) }
    return out
}
