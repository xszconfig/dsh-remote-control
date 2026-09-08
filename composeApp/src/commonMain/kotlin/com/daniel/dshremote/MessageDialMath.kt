package com.daniel.dshremote

import com.daniel.dshremote.protocol.EventProjection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 消息转盘纯函数与常量（无 Compose 依赖，commonTest 直测）。
 *
 * 映射约定（与设计一致）：
 * - 一格 = 一条用户消息 = 一步进；刻度按固定角度 [DIAL_STEP_ANGLE_DEG] 排布，仅作视觉。
 * - 顺时针（角度增）→ 更新消息（step +1）；逆时针（角度减）→ 更老消息（step -1）。
 * - [userMessageRefs] 返回「最老在前」：refs[0] = 第一条用户消息（LazyColumn 行号最大）。
 */

/** 一格角度（度）：每格 = 一条用户消息 = 一步进。 */
const val DIAL_STEP_ANGLE_DEG = 18.0f

/** 对齐阈值（度）：刻度中心与红线夹角小于此值视为对齐。 */
const val DIAL_ALIGN_THRESHOLD_DEG = DIAL_STEP_ANGLE_DEG / 3f

/** 对齐迟滞（度）：防边界来回抖动。 */
const val DIAL_ALIGN_HYSTERESIS_DEG = 2.5f

/** 震动最小间隔（毫秒）：快速旋转时合并震动（只震落地格）。 */
const val DIAL_HAPTIC_MIN_INTERVAL_MS = 60L

/** 连续狩猎翻页安全上限（页）。 */
const val DIAL_SEEK_MAX_PAGES = 10

/** 转盘自动收起空闲时长（毫秒）。 */
const val DIAL_AUTO_COLLAPSE_MS = 2500L

/** 展开扇面半透明透明度。 */
const val DIAL_FAN_ALPHA = 0.12f

/** 收起圆钮透明度。 */
const val DIAL_COLLAPSED_ALPHA = 0.38f

/** 一条已定位的用户消息：seq 跨投影重锚，rowIndex 是 LazyColumn 行号。 */
data class UserMsgRef(val seq: Long, val rowIndex: Int)

/**
 * 计算已加载窗口内所有「用户消息」的 LazyColumn 行号，按「最老在前」排序。
 *
 * 只收「真实用户输入」：type == user_message 且 source 非注入（source == null 旧桥兼容
 * 或 source == "user"）；注入/上下文（source="inject" 等）由 [isInjectedUserMessage] 排除，
 * 转盘只服务「用户找自己发的消息」，绝不把注入消息当成定位目标。
 *
 * 行号映射（reverseLayout=true，App.kt 的 LazyColumn 行序）：
 * - index 0 = 底部最新；DSL 顺序 = [liveThink?] + events.asReversed()。
 * - events 旧→新（下标 j=0 最老），events[j] 的行号 = (events.size - 1 - j) + (liveThink 占位的偏移)。
 *
 * @param events 旧→新（index 0 最老，末位最新）。
 * @param hasLiveThink liveThink 思考行是否占据 index 0。
 * @return refs[0]=最老用户消息（行号最大）、末位=最新（行号最小）。
 */
fun userMessageRefs(events: List<EventProjection>, hasLiveThink: Boolean): List<UserMsgRef> {
    if (events.isEmpty()) return emptyList()
    val offset = if (hasLiveThink) 1 else 0
    val size = events.size
    val refs = ArrayList<UserMsgRef>()
    for (j in events.indices) {
        val e = events[j]
        if (e.type == "user_message" && !isInjectedUserMessage(e.source)) {
            refs.add(UserMsgRef(e.seq, (size - 1 - j) + offset))
        }
    }
    return refs
}

/** 归一化角度增量到 (-180, 180]，处理跨 ±180° 回绕。 */
fun normalizeAngleDelta(deltaDeg: Float): Float {
    var d = deltaDeg
    while (d > 180f) d -= 360f
    while (d <= -180f) d += 360f
    return d
}

/** 累计角度对应的整数格数（向零截断：|accum| ≥ 一格才算一步）。 */
fun stepsAt(accumDeg: Float, stepAngleDeg: Float): Int {
    val x = (accumDeg / stepAngleDeg).toDouble()
    return if (x >= 0) floor(x).toInt() else ceil(x).toInt()
}

/**
 * 角度增量跨过的格数（带符号）。
 * + = 顺时针 = 更新；- = 逆时针 = 更老。向零截断保证「不到一格不触发」。
 */
fun stepsCrossed(prevAccumDeg: Float, newAccumDeg: Float, stepAngleDeg: Float): Int =
    stepsAt(newAccumDeg, stepAngleDeg) - stepsAt(prevAccumDeg, stepAngleDeg)

/** 转盘状态机阶段。 */
enum class DialPhase { Collapsed, Expanded, Rotating, WaitingOlder, AtBoundary }

/** 单次步进的可能结果。 */
sealed interface DialStepResult {
    data class Jump(val targetIndex: Int, val newSeq: Long) : DialStepResult
    data object NeedOlderPage : DialStepResult   // 需翻更老一页（卡住）
    data object AtOlderBoundary : DialStepResult // 已到第一条用户消息
    data object AtNewerBoundary : DialStepResult // 已到最后一条用户消息
}

/**
 * 给定当前选中下标 + 步进方向，返回下一步动作。
 *
 * @param refs 用户消息（最老在前）。
 * @param selectedIndex 当前选中下标（<0 视为未初始化）。
 * @param delta 步进方向：+1 更老（对应顺时针）/ -1 更新（对应逆时针）。
 * @param hasMore 服务端是否还有更早历史。
 */
fun stepResult(refs: List<UserMsgRef>, selectedIndex: Int, delta: Int, hasMore: Boolean): DialStepResult {
    val n = refs.size
    if (n == 0) return if (hasMore) DialStepResult.NeedOlderPage else DialStepResult.AtOlderBoundary
    val target = selectedIndex - delta
    return when {
        target < 0 -> if (hasMore) DialStepResult.NeedOlderPage else DialStepResult.AtOlderBoundary
        target >= n -> DialStepResult.AtNewerBoundary
        else -> DialStepResult.Jump(refs[target].rowIndex, refs[target].seq)
    }
}

/** 狩猎翻页完成后的决策。 */
sealed interface SeekOutcome {
    data class Resume(val anchorSeq: Long?) : SeekOutcome
    data object ChainMore : SeekOutcome
    data class SnapToOldest(val seq: Long, val rowIndex: Int) : SeekOutcome
    data object NothingFound : SeekOutcome
}

/**
 * 分页加载完成后的「卡住→唤醒」决策。
 *
 * @param seekOldestSeq 进入狩猎前的最老用户消息 seq（据此判断是否有更老用户消息被加载）。
 * @param selectedSeq 当前锚定（未失效则保留）。
 * @param fingerDown 用户是否仍在按住转动（链式翻页只在按住时继续，防无意图分页风暴）。
 * @param seekPages 本次狩猎已翻页数（上限 [DIAL_SEEK_MAX_PAGES]）。
 */
fun seekOutcome(
    refs: List<UserMsgRef>,
    seekOldestSeq: Long?,
    selectedSeq: Long?,
    hasMore: Boolean,
    fingerDown: Boolean,
    seekPages: Int,
): SeekOutcome {
    val newOldest = refs.firstOrNull()?.seq
    val foundNewer = refs.isNotEmpty() && newOldest != seekOldestSeq
    return when {
        foundNewer -> {
            val anchor = selectedSeq?.takeIf { s -> refs.any { it.seq == s } } ?: newOldest
            SeekOutcome.Resume(anchor)
        }
        hasMore && fingerDown && seekPages < DIAL_SEEK_MAX_PAGES -> SeekOutcome.ChainMore
        refs.isNotEmpty() -> SeekOutcome.SnapToOldest(refs.first().seq, refs.first().rowIndex)
        else -> SeekOutcome.NothingFound
    }
}

/** 视口中心行最近的用户消息 seq（展开时初始化锚点，不立即滚动）。 */
fun nearestUserSeq(refs: List<UserMsgRef>, viewportCenterRow: Int): Long? =
    refs.minByOrNull { abs(it.rowIndex - viewportCenterRow) }?.seq

/** 震动节流判定：距上次震动不足最小间隔则跳过。 */
fun shouldVibrate(lastTickMs: Long, nowMs: Long, minIntervalMs: Long = DIAL_HAPTIC_MIN_INTERVAL_MS): Boolean =
    lastTickMs <= 0L || nowMs - lastTickMs >= minIntervalMs

/**
 * reverseLayout 下把目标行从底部推到顶部所需的 scrollBy 增量（像素）。
 *
 * 推导：scrollToItem(index) 把目标行放到 reverseLayout 起始端 = 屏幕底部；随后 scrollBy
 * 负向（向后滚 = 内容上移）把它顶到视口顶部。目标行顶边对齐视口顶所需位移
 * = -(视口高 - 行高) = 行高 - 视口高。行高 > 视口高时（超长消息）为正，向下对齐顶部。
 */
fun scrollDeltaToTop(viewportHeightPx: Int, itemHeightPx: Int): Int = itemHeightPx - viewportHeightPx

/**
 * 转盘显隐判定（按需显示）：最新消息不可见（showJumpToBottom）或转盘处于非收起态时显示。
 *
 * 语义（手指活动优先于「到底隐藏」）：转盘展开/旋转/狩猎/触边时，即使列表已滚到最新一条
 * （showJumpToBottom=false），也保持挂载不打断手指；只有收起态（Collapsed）且已到底部才隐藏。
 */
fun showDial(showJumpToBottom: Boolean, phase: DialPhase): Boolean =
    showJumpToBottom || phase != DialPhase.Collapsed
