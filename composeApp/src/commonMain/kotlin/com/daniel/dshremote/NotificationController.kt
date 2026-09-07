package com.daniel.dshremote

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 主动通知（审批提醒 + 结果交付提醒）的纯逻辑与编排。
 *
 * 规则来源：docs/prd/主动通知与审批提醒PRD.md（用户已拍板）：
 * - 防打扰硬规则：用户正「前台浏览该会话」时不发系统通知（仅页内弹窗 + 振动），其余状态发。
 * - 审批/提问：高优先级、永不降噪；`approvalId`/`rpcId` 幂等；已裁决撤回。
 * - 结果交付：D1 子代理 settle / D2 主会话空闲 / D3 goal 终态；`会话×轮次` 幂等；不合并；
 *   夜间勿扰 22:00–08:00 仅静默结果交付类（仍展示、无声音振动）。
 */

/** 通知类别。 */
enum class NotificationKind { APPROVAL, QUESTION, DELIVERY }

/** 用户在场三态（防打扰硬规则的门控输入）。 */
enum class Presence { BROWSING_CURRENT, OTHER_SESSION, BACKGROUND }

/** 一次通知决策：是否发系统通知 + 是否静默（勿扰时段仅结果交付静默）。 */
data class NotificationDecision(val post: Boolean, val silent: Boolean)

/** 系统通知内容规格（纯数据；平台层据此渲染 Notification）。 */
data class NotificationSpec(
    val kind: NotificationKind,
    val sessionId: String?,
    val title: String,
    val body: String,
    val silent: Boolean,
    val tag: String,
)

/** 平台宿主：前台状态 + 当前会话 + 实际发/撤通知（androidMain 实现）。 */
interface NotificationHost {
    fun isForeground(): Boolean
    fun currentSessionId(): String?
    fun post(spec: NotificationSpec)
    fun cancel(tag: String)
}

// ---- 平台能力（expect/actual；androidMain 落地）----

internal expect fun platformIsAppForeground(): Boolean

internal expect fun platformPostNotification(spec: NotificationSpec)

internal expect fun platformCancelNotification(tag: String)

// ---- 纯函数（可单测）----

/** 三态门控：前台浏览该会话 / 前台浏览其它会话 / 后台锁屏。 */
fun presenceOf(isForeground: Boolean, currentSessionId: String?, eventSessionId: String?): Presence = when {
    isForeground && currentSessionId != null && currentSessionId == eventSessionId -> Presence.BROWSING_CURRENT
    isForeground -> Presence.OTHER_SESSION
    else -> Presence.BACKGROUND
}

/** 是否应发系统通知 + 是否静默。审批/提问永不静默（即便勿扰时段）；结果交付落入勿扰时段则静默。 */
fun decideNotification(kind: NotificationKind, presence: Presence, inDnd: Boolean): NotificationDecision {
    if (presence == Presence.BROWSING_CURRENT) return NotificationDecision(post = false, silent = false)
    val silent = kind == NotificationKind.DELIVERY && inDnd
    return NotificationDecision(post = true, silent = silent)
}

/**
 * 是否落在勿扰时段（分钟制，支持跨午夜，如 22:00–08:00）。
 * start == end 视为未启用勿扰。
 */
fun isInDndWindow(minutesOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean = when {
    startMinutes == endMinutes -> false
    startMinutes < endMinutes -> minutesOfDay in startMinutes until endMinutes
    else -> minutesOfDay >= startMinutes || minutesOfDay < endMinutes
}

/** 通知正文截断（默认 80 字）。 */
fun truncateForNotification(text: String, max: Int = 80): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

const val APPROVAL_TITLE = "需要你及时响应"
const val QUESTION_TITLE = "需要你回答"
const val DELIVERY_COMPLETE_TITLE = "结果已就绪"
const val DELIVERY_BLOCKED_TITLE = "目标受阻"

/** 审批正文：toolName + reason（无 reason 用 command 首行）。 */
fun approvalBody(toolName: String, reason: String?, command: String?): String {
    val detail = reason?.takeIf { it.isNotBlank() } ?: command?.takeIf { it.isNotBlank() }
    return truncateForNotification(if (detail != null) "$toolName：$detail" else toolName)
}

/** 提问正文：单问直接显示问题，多问显示「N 个问题 + 首问」。 */
fun questionBody(questionCount: Int, firstQuestion: String?): String {
    val first = firstQuestion?.takeIf { it.isNotBlank() }
    return when {
        questionCount > 1 && first != null -> truncateForNotification("$questionCount 个问题：$first")
        first != null -> truncateForNotification(first)
        else -> "有 $questionCount 个问题待回答"
    }
}

/** 结果交付（完成）正文。 */
fun deliveryCompleteBody(sessionTitle: String?, isSubagent: Boolean): String {
    val name = sessionTitle?.takeIf { it.isNotBlank() }
    return truncateForNotification(
        when {
            isSubagent && name != null -> "「$name」已完成"
            isSubagent -> "子代理已完成"
            name != null -> "「$name」本轮已完成"
            else -> "本轮已完成"
        }
    )
}

/** 结果交付（受阻）正文。 */
fun deliveryBlockedBody(sessionTitle: String?, blockedMessage: String?): String {
    val name = sessionTitle?.takeIf { it.isNotBlank() }
    val msg = blockedMessage?.takeIf { it.isNotBlank() }
    val base = if (name != null) "「$name」目标受阻" else "目标受阻"
    return truncateForNotification(if (msg != null) "$base：$msg" else base)
}

/** 当前时刻（分钟）。 */
internal fun systemMinutesOfDay(): Int {
    val t = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return t.hour * 60 + t.minute
}

/**
 * 主动通知编排器：三态门控 + 幂等去重 + 勿扰时段 + 通知构建。
 * 纯逻辑，不依赖平台；平台能力经 [NotificationHost] 注入，时钟可注入以便单测。
 */
class NotificationController(
    private val host: NotificationHost,
    private val nowMinutes: () -> Int = { systemMinutesOfDay() },
) {
    companion object {
        const val DND_START_MINUTES = 22 * 60 // 22:00
        const val DND_END_MINUTES = 8 * 60     // 08:00
    }

    /** 已通知幂等键（approval:{id} / question:{rpcId} / delivery:{sessionId}:{turnKey}）。 */
    private val notifiedKeys = mutableSetOf<String>()

    private fun decideAndPost(key: String, kind: NotificationKind, sessionId: String?, title: String, body: String) {
        if (!notifiedKeys.add(key)) return // 幂等：已通知过
        val presence = presenceOf(host.isForeground(), host.currentSessionId(), sessionId)
        val inDnd = isInDndWindow(nowMinutes(), DND_START_MINUTES, DND_END_MINUTES)
        val decision = decideNotification(kind, presence, inDnd)
        if (!decision.post) {
            // 未发通知则不占幂等位：后续（如重连 hello 补发）仍有第二次机会
            notifiedKeys.remove(key)
            ConnLog.info("NOTIFY", "抑制 $kind key=${key.take(16)} 原因=${presence.name}")
            return
        }
        host.post(NotificationSpec(kind, sessionId, title, body, decision.silent, key))
        ConnLog.info("NOTIFY", "触发 $kind key=${key.take(16)} presence=${presence.name} silent=${decision.silent}")
    }

    /** 审批到达（实时事件或 hello 补发共用，幂等）。 */
    fun onApprovalArrived(approvalId: String, sessionId: String?, toolName: String, reason: String?, command: String?) {
        decideAndPost(
            "approval:$approvalId", NotificationKind.APPROVAL, sessionId,
            APPROVAL_TITLE, approvalBody(toolName, reason, command),
        )
    }

    /** 审批已裁决：撤回对应通知。 */
    fun onApprovalResolved(approvalId: String) {
        val key = "approval:$approvalId"
        if (notifiedKeys.remove(key)) host.cancel(key)
    }

    /** 提问到达（实时事件或 hello 补发共用，幂等）。 */
    fun onQuestionArrived(rpcId: String, sessionId: String?, questionCount: Int, firstQuestion: String?) {
        decideAndPost(
            "question:$rpcId", NotificationKind.QUESTION, sessionId,
            QUESTION_TITLE, questionBody(questionCount, firstQuestion),
        )
    }

    /** 提问已回答：撤回对应通知。 */
    fun onQuestionResolved(rpcId: String) {
        val key = "question:$rpcId"
        if (notifiedKeys.remove(key)) host.cancel(key)
    }

    /** 结果交付到达（D1/D2/D3）。turnKey 为「会话×轮次」幂等键。 */
    fun onDelivery(
        sessionId: String?,
        sessionTitle: String?,
        isSubagent: Boolean,
        turnKey: String,
        blocked: Boolean,
        blockedMessage: String?,
    ) {
        val key = "delivery:$sessionId:$turnKey"
        val title = if (blocked) DELIVERY_BLOCKED_TITLE else DELIVERY_COMPLETE_TITLE
        val body = if (blocked) deliveryBlockedBody(sessionTitle, blockedMessage) else deliveryCompleteBody(sessionTitle, isSubagent)
        decideAndPost(key, NotificationKind.DELIVERY, sessionId, title, body)
    }
}
