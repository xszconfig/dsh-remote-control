package com.daniel.dshremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志级别。 */
enum class ConnLogLevel(val label: String) {
    DEBUG("D"), INFO("I"), WARN("W"), ERROR("E"),
}

/** 一条结构化连接日志。 */
data class ConnLogEntry(
    val seq: Long,
    val ts: Long,
    val level: ConnLogLevel,
    val tag: String,
    val message: String,
)

/**
 * 连接层结构化日志基础组件：环形缓冲（内存有界）+ 快照流 + logcat 镜像。
 * 后续所有连接相关开发统一经这里打点；UI 的「日志」页与排查都依赖它。
 */
object ConnLog {

    private const val MAX_ENTRIES = 1500
    private val buffer = ArrayDeque<ConnLogEntry>()
    private var seq = 0L

    private val _flow = MutableStateFlow<List<ConnLogEntry>>(emptyList())
    val flow: StateFlow<List<ConnLogEntry>> = _flow.asStateFlow()

    /** 节流表：key -> 上次放行时间（防高频事件刷屏）。 */
    private val throttle = mutableMapOf<String, Long>()

    @Synchronized
    fun log(level: ConnLogLevel, tag: String, message: String) {
        seq += 1
        val entry = ConnLogEntry(seq, nowMillis(), level, tag, message)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _flow.value = buffer.toList()
        platformConnLog(level, tag, message)
    }

    fun debug(tag: String, message: String) = log(ConnLogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(ConnLogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(ConnLogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(ConnLogLevel.ERROR, tag, message)

    /** 节流日志：同一 key 在 windowMs 内只放行一次。 */
    @Synchronized
    fun throttled(level: ConnLogLevel, tag: String, key: String, windowMs: Long, message: () -> String) {
        val now = nowMillis()
        val last = throttle[key] ?: 0
        if (now - last < windowMs) return
        throttle[key] = now
        log(level, tag, message())
    }

    /** 过滤后的快照（level 为 null 表示全部）。 */
    @Synchronized
    fun snapshot(level: ConnLogLevel? = null): List<ConnLogEntry> =
        if (level == null) buffer.toList() else buffer.filter { it.level == level }

    @Synchronized
    fun clear() {
        buffer.clear()
        _flow.value = emptyList()
    }
}

/** 平台 logcat 镜像（androidMain: android.util.Log）。 */
internal expect fun platformConnLog(level: ConnLogLevel, tag: String, message: String)
