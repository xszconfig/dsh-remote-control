package com.daniel.dshremote

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 当前时间（epoch 毫秒）。 */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** HH:mm:ss（按指定时区；默认系统时区）。 */
fun formatClock(ts: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(ts).toLocalDateTime(timeZone)
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}

/** 相对时间："刚刚 / N 分钟前 / N 小时前 / N 天前"，超过 7 天退化为时刻。 */
fun relativeTime(ts: Long, now: Long = nowMillis(), timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (ts <= 0) return "从未"
    val diff = now - ts
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    if (minutes < 1) return "刚刚"
    if (minutes < 60) return "$minutes 分钟前"
    val hours = minutes / 60
    if (hours < 24) return "$hours 小时前"
    val days = hours / 24
    if (days < 7) return "$days 天前"
    return formatClock(ts, timeZone)
}
