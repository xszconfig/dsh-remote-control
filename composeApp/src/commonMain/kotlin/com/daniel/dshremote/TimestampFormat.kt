package com.daniel.dshremote

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 统一的时间戳展示规则：**日期作为前缀 + 时刻恒显**。
 *
 * 映射表（以**本地时区自然日边界**为准，而非「距现在多少小时」的时长推算，
 * 避免「24 小时前但跨了日」仍显示「今天」的错位）：
 *
 * | 场景            | 展示                    |
 * |-----------------|-------------------------|
 * | ts ≤ 0          | 从未                     |
 * | <60s            | 刚刚                     |
 * | 今天            | 今天 HH:mm               |
 * | 昨天            | 昨天 HH:mm               |
 * | 前天            | 前天 HH:mm               |
 * | 更早 · 今年内    | M月d日 HH:mm             |
 * | 更早 · 跨年      | yyyy年M月d日 HH:mm       |
 *
 * 时刻（HH:mm）在所有非「刚刚/从未」场景恒显，不因列表紧凑而省略。
 * 优先级：昨天/前天 > 年份（跨年的「昨天」仍显示「昨天」，例如 12-31 → 1-1）。
 * 「现在」参照 = 本地 [nowMillis]（服务端时间戳 + 本地时钟，铁律 6：只做展示格式化，
 * 不引入状态推算）。Deep Diving 的时长与日志页的绝对时间戳（HH:mm:ss，见 [formatClock]）
 * 不在此规则内。
 */

private data class TsParts(
    val dayDiff: Int,
    val year: Int,
    val todayYear: Int,
    val month: Int,
    val day: Int,
    val hm: String,
)

private fun tsParts(ts: Long, now: Long, timeZone: TimeZone): TsParts {
    val d = Instant.fromEpochMilliseconds(ts).toLocalDateTime(timeZone)
    val t = Instant.fromEpochMilliseconds(now).toLocalDateTime(timeZone)
    return TsParts(
        dayDiff = t.date.toEpochDays() - d.date.toEpochDays(),
        year = d.date.year,
        todayYear = t.date.year,
        month = d.date.monthNumber,
        day = d.date.dayOfMonth,
        hm = "%02d:%02d".format(d.hour, d.minute),
    )
}

/** 统一时间戳：日期前缀 + 时刻恒显。用于所有用户感知层（消息气泡/会话/设备/子代理列表）。 */
fun formatTimestamp(ts: Long, now: Long = nowMillis(), timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (ts <= 0) return "从未"
    val p = tsParts(ts, now, timeZone)
    return when {
        p.dayDiff == 0 -> if (now - ts < 60_000) "刚刚" else "今天 ${p.hm}"
        p.dayDiff == 1 -> "昨天 ${p.hm}"
        p.dayDiff == 2 -> "前天 ${p.hm}"
        p.year == p.todayYear -> "${p.month}月${p.day}日 ${p.hm}"
        else -> "${p.year}年${p.month}月${p.day}日 ${p.hm}"
    }
}
