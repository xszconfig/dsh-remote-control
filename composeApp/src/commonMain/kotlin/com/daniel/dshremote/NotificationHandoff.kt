package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 主动通知的「点击直达」与「权限引导」交接状态（App.kt / MainActivity / BridgeClient 三方协作）。
 *
 * - 点击直达：通知 PendingIntent 携带 [EXTRA_NOTIFY_SESSION_ID] → MainActivity 写入
 *   [NotificationLaunch.requestedSessionId] → BridgeClient 消费后打开对应会话（命中才开，否则停留列表）。
 * - 权限引导：未授权 POST_NOTIFICATIONS 时 [NotificationPermissionState.prompt] 置为
 *   [NotificationPermissionPrompt.Rationale]，App.kt 渲染小弹窗说明用途；同意后经
 *   [platformRequestNotificationPermission] 申请，拒绝后转 [NotificationPermissionPrompt.GoSettings]（去系统设置）。
 */

/** 通知点击直达的 intent extra key。 */
const val EXTRA_NOTIFY_SESSION_ID = "notify_session_id"

/** POST_NOTIFICATIONS 运行时申请的 requestCode（MainActivity 与平台层共用）。 */
const val NOTIFY_PERMISSION_REQ_CODE = 8201

/** 通知权限引导状态（App.kt 渲染，平台层驱动）。 */
enum class NotificationPermissionPrompt {
    /** 无引导。 */
    Hidden,
    /** 首次未授权：展示用途说明，同意后申请权限。 */
    Rationale,
    /** 已被拒绝：引导去系统设置开启。 */
    GoSettings,
}

object NotificationPermissionState {
    val prompt = MutableStateFlow(NotificationPermissionPrompt.Hidden)
}

/** 通知点击直达的目标会话 id（MainActivity 写入，BridgeClient 消费后置空）。 */
object NotificationLaunch {
    val requestedSessionId = MutableStateFlow<String?>(null)
}

/**
 * 通知直达目标解析：sessionId 命中会话列表才打开，否则返回 null（停留会话列表）。
 * 纯函数，可单测（intent 解析结果的「是否直达」决策）。
 */
internal fun resolveNotificationOpenTarget(sessionId: String?, sessions: List<SessionSummary>): String? =
    sessionId?.takeIf { sid -> sessions.any { it.id == sid } }

/** 平台：请求 POST_NOTIFICATIONS 运行时权限（androidMain 经 Activity.requestPermissions）。 */
internal expect fun platformRequestNotificationPermission()

/** 平台：打开系统通知设置页（androidMain 跳 ACTION_APP_NOTIFICATION_SETTINGS）。 */
internal expect fun platformOpenNotificationSettings()
