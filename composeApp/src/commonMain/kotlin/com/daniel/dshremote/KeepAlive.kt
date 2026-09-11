package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * 按需前台服务（保活）纯逻辑与编排。
 *
 * 用户拍板细则（2026-09-10，docs/decisions/后台保活与通知兜底方案.md §6.2 方案②）：
 * - 启动：主代理（顶层会话 `status=running`）或任意子代理（`parentSessionId!=null` 且 `status=running`）任一运行。
 * - 通知：「N 个主代理 · M 个子代理正在运行」，counts 以 `SessionUiState.sessions` 的 status 为准（铁律 6，客户端不推算）。
 * - 结束：counts=0 → 停止前台服务并取消通知。
 * - 断连边缘：WS 断开用最后已知 counts 维持 FGS，重连后按服务端投影校正。
 * - Android 12+ 后台启动限制：启动/更新在 App 前台观察到 counts 变化时执行；counts>0 时服务持续存在。
 */

/** 正在运行的代理计数。 */
data class RunningAgents(val mainCount: Int, val subCount: Int) {
    val total: Int get() = mainCount + subCount
    val running: Boolean get() = total > 0
}

/** 前台服务动作。 */
enum class FgsAction { START_OR_UPDATE, STOP, NOOP }

/** 统计运行中的主代理/子代理（status == "running"；子代理 = parentSessionId != null）。纯函数。 */
fun countRunningAgents(sessions: List<SessionSummary>): RunningAgents {
    var main = 0
    var sub = 0
    for (s in sessions) {
        if (s.status == "running") {
            if (s.parentSessionId != null) sub++ else main++
        }
    }
    return RunningAgents(main, sub)
}

/**
 * 前台服务决策（纯函数）：
 * - 断连 → 维持现状（不清停，用最后已知 counts 保活）；
 * - counts=0 → 停止（无论前后台）；
 * - 前台且 counts>0 → 启动/更新（Android 12+ 后台启动限制，后台不启动不更新）。
 */
fun decideFgsAction(counts: RunningAgents, connected: Boolean, foreground: Boolean): FgsAction = when {
    !connected -> FgsAction.NOOP
    !counts.running -> FgsAction.STOP
    foreground -> FgsAction.START_OR_UPDATE
    else -> FgsAction.NOOP
}

/** 前台服务通知正文（N 个主代理 · M 个子代理正在运行）。 */
fun keepAliveNotificationBody(mainCount: Int, subCount: Int): String =
    "$mainCount 个主代理 · $subCount 个子代理正在运行"

/** 前台服务宿主（androidMain 实现）。 */
interface KeepAliveHost {
    fun startOrUpdate(mainCount: Int, subCount: Int)
    fun stop()
}

/**
 * 按需前台服务编排器：投影变化 → 计数 → 决策 → 执行（计数未变时跳过更新，避免通知刷屏）。
 * 前台态作为 [onProjectionChanged] 的显式入参传入——由调用方用「前台态流」驱动，
 * 保证「前台/后台转场」也触发重算（转前台且 counts>0 → 启动/更新；转后台 → NOOP 维持）。
 */
class KeepAliveController(private val host: KeepAliveHost) {
    private var lastCounts: RunningAgents? = null

    /** 会话投影 / 连接态 / 前台态任一变化时调用。 */
    fun onProjectionChanged(sessions: List<SessionSummary>, connected: Boolean, foreground: Boolean) {
        val counts = countRunningAgents(sessions)
        when (decideFgsAction(counts, connected, foreground)) {
            FgsAction.START_OR_UPDATE -> {
                if (counts != lastCounts) {
                    host.startOrUpdate(counts.mainCount, counts.subCount)
                    lastCounts = counts
                    ConnLog.info("FGS", "启动/更新前台服务 main=${counts.mainCount} sub=${counts.subCount}")
                }
            }
            FgsAction.STOP -> {
                if (lastCounts != null) {
                    host.stop()
                    lastCounts = null
                    ConnLog.info("FGS", "停止前台服务（无运行代理）")
                }
            }
            FgsAction.NOOP -> Unit
        }
    }
}

// ---- 平台能力（expect/actual；androidMain 落地）----

/** 平台：应用前台态流（onStart 起算前台；初始化即回填当前态，前台/后台转场都会发射）。 */
internal expect fun platformAppForegroundFlow(): StateFlow<Boolean>

/** 平台：启动/更新前台服务（connectedDevice 类型；已运行时更新通知计数）。 */
internal expect fun platformStartKeepAliveService(mainCount: Int, subCount: Int)

/** 平台：停止前台服务并取消通知。 */
internal expect fun platformStopKeepAliveService()

/** 平台：申请忽略电池优化（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
internal expect fun platformRequestIgnoreBatteryOptimizations()

/** 平台：是否已忽略电池优化。 */
internal expect fun platformIsIgnoringBatteryOptimizations(): Boolean
