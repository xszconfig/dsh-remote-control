package com.daniel.dshremote

import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.StoredEndpoint

/** 设备列表条目 key（host:port 唯一标识一台桌面）。 */
fun deviceKey(device: StoredDevice): String = "${device.host}:${device.port}"
fun deviceKey(host: String, port: Int): String = "$host:$port"

/** 合并候选端点：当前连接端点置顶，去重（主端点优先保证快速重连）。 */
internal fun mergeEndpoints(primary: Endpoint, server: List<StoredEndpoint>): List<StoredEndpoint> {
    val list = mutableListOf(StoredEndpoint(primary.host, primary.port))
    for (e in server) if (e.host != primary.host || e.port != primary.port) list.add(e)
    return list.distinctBy { "${it.host}:${it.port}" }
}

/** 侧边栏「未分组」桶的虚拟 id。 */
const val UNGROUPED_KEY = "__ungrouped__"

/** lastSeenAt 落盘节流间隔：探测循环 12s 一次，但只有超过该间隔才真正写文件。 */
const val LAST_SEEN_PERSIST_INTERVAL_MS: Long = 10 * 60_000

/** 错误提示保留上限（只保留最近 N 条，防止无界增长）。 */
const val MAX_ERRORS = 20

/** 单个会话在内存中保留的事件上限（长会话防 OOM；只保留最新）。 */
const val MAX_EVENTS = 500

/** 视为「凭据失效」的服务端错误码：停止自动重连。 */
val AUTH_FATAL_CODES = setOf("auth", "unauthorized", "forbidden", "token", "device_revoked")

/**
 * 汇总多候选连接失败的可读诊断：逐候选列出原因；候选里含 127.0.0.1
 * （bridge 仅监听本机、依赖 USB adb reverse 的场景）时追加操作指引。
 */
internal fun buildConnectFailureDetail(failures: List<Pair<String, String>>): String {
    val sb = StringBuilder("所有候选地址均连接失败")
    failures.take(3).forEach { (host, reason) ->
        sb.append("\n· ").append(host).append("：").append(reason.ifBlank { "连接失败" })
    }
    if (failures.size > 3) sb.append("\n· …等 ").append(failures.size).append(" 个候选")
    if (failures.any { it.first == "127.0.0.1" }) {
        sb.append("\n提示：桌面端仅监听 127.0.0.1；USB 连接请先在电脑上执行 adb reverse tcp:3080 tcp:3080")
    }
    return sb.toString()
}

/** 历史事件裁剪到上限（保留最新）。 */
internal fun List<EventProjection>.bounded(): List<EventProjection> =
    if (size <= MAX_EVENTS) this else takeLast(MAX_EVENTS)

/**
 * 自动打开候选：当前工作区范围内（null=全部 / UNGROUPED_KEY=未分组 / 具体 id）的
 * 主会话（parentSessionId == null），按 updatedAt 降序取最近一个；无候选返回 null。
 * 过滤语义与 [com.daniel.dshremote.SessionList] 完全一致、排序与列表 updatedAt 倒序一致。
 */
internal fun pickRecentSession(sessions: List<SessionSummary>, workspaceId: String?): SessionSummary? =
    sessions
        .filter { s ->
            s.parentSessionId == null && when (workspaceId) {
                null -> true
                UNGROUPED_KEY -> s.workspaceId == null
                else -> s.workspaceId == workspaceId
            }
        }
        .maxByOrNull { it.updatedAt }
