package com.daniel.dshremote

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 当前时间（epoch 毫秒）。 */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** HH:mm:ss（按指定时区；默认系统时区）。日志页专用绝对时间戳。 */
fun formatClock(ts: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(ts).toLocalDateTime(timeZone)
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}
