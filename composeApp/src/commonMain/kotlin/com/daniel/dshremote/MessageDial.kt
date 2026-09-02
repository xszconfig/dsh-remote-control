package com.daniel.dshremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 消息转盘：右侧中间的半透明旋钮，点击展开为 90° 扇形，单指拨动快速定位到「你发的消息」。
 *
 * 数据来源一律以 [SessionUiState]（服务端投影）为准：用户消息集合由 [userMessageRefs]
 * 从 state.events 惰性扫描得出；翻页复用 [onLoadOlder]（内部已有 loadingOlder 单飞守卫）。
 */
class MessageDialState {
    var phase by mutableStateOf(DialPhase.Collapsed)
    /** 扇面刻度累计旋转角（度）；正 = 顺时针 = 更新。graphicsLayer 读它，不触发重组。 */
    var accumDeg by mutableFloatStateOf(0f)
    /** 当前锚定的用户消息 seq（跨投影重锚；null = 未选中）。 */
    var selectedSeq by mutableStateOf<Long?>(null)
    var fingerDown by mutableStateOf(false)

    internal var seekPages = 0
    internal var seekOldestSeq: Long? = null
    /** 进入狩猎时 events 的引用，用于区分「翻页到达（events 变新引用）」vs「发送失败（未变）」。 */
    internal var seekEventsMark: Any? = null
    internal var lastTickMs = 0L
    internal var lastActivityMs = 0L

    internal fun touch() {
        lastActivityMs = nowMillis()
    }

    internal fun collapse() {
        phase = DialPhase.Collapsed
        accumDeg = 0f
        fingerDown = false
        seekPages = 0
        seekOldestSeq = null
        seekEventsMark = null
    }
}

/** 单指绕 pivot 的角度（度，atan2，[-180,180]，屏幕坐标 y 向下）。 */
private fun angleDeg(pos: Offset, pivot: Offset): Float =
    (atan2((pos.y - pivot.y).toDouble(), (pos.x - pivot.x).toDouble()) * 180.0 / PI).toFloat()

@Composable
fun MessageDial(
    state: SessionUiState,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dial = remember(state.currentSessionId) { MessageDialState() }
    val hasLiveThink = state.liveThink != null
    val refs = remember(state.events, hasLiveThink) { userMessageRefs(state.events, hasLiveThink) }
    // 手势 pointerInput(Unit) 不随重组重启：用 rememberUpdatedState 让 onSteps 读到最新 refs/state，
    // 否则翻页完成（events 变化）后手指仍按住继续转时，会用旧 refs 误判边界。
    val currentRefs by rememberUpdatedState(refs)
    val currentState by rememberUpdatedState(state)

    // 完全没有用户消息、也没有更早历史 → 不渲染。
    if (refs.isEmpty() && !state.hasMore) return

    // 狩猎唤醒：loadingOlder 翻页完成 → 决策（卡住 → 继续 / 链式翻页 / snap 到第一条 / 失败退出）。
    LaunchedEffect(state.loadingOlder) {
        if (dial.phase != DialPhase.WaitingOlder) return@LaunchedEffect
        if (state.loadingOlder) return@LaunchedEffect
        if (state.events === dial.seekEventsMark) {
            // events 未变 = 发送失败/无进展：退出，不自动重试（横幅已由 loadOlderPage 提示）。
            dial.phase = DialPhase.Expanded
            return@LaunchedEffect
        }
        when (val o = seekOutcome(refs, dial.seekOldestSeq, dial.selectedSeq, state.hasMore, dial.fingerDown, dial.seekPages)) {
            is SeekOutcome.Resume -> {
                dial.selectedSeq = o.anchorSeq
                dial.phase = if (dial.fingerDown) DialPhase.Rotating else DialPhase.Expanded
            }
            SeekOutcome.ChainMore -> {
                dial.seekPages++
                dial.seekEventsMark = state.events
                onLoadOlder()
            }
            is SeekOutcome.SnapToOldest -> {
                dial.selectedSeq = o.seq
                dial.phase = DialPhase.Expanded
                scope.launch { listState.scrollToItem(o.rowIndex) }
                vibrateTick(dial, boundary = true)
            }
            SeekOutcome.NothingFound -> dial.phase = DialPhase.Expanded
        }
    }

    // 自动收起：2.5s 无操作。lastActivityMs 是普通变量（不触发重组），轮询检查避免每帧重启 effect。
    LaunchedEffect(dial.phase) {
        if (dial.phase == DialPhase.Collapsed) return@LaunchedEffect
        while (dial.phase != DialPhase.Collapsed) {
            delay(DIAL_AUTO_COLLAPSE_MS)
            if (dial.phase != DialPhase.Collapsed && nowMillis() - dial.lastActivityMs >= DIAL_AUTO_COLLAPSE_MS) {
                dial.collapse()
                break
            }
        }
    }

    // 触底/顶短暂态：回弹后回到可转动。
    LaunchedEffect(dial.phase) {
        if (dial.phase != DialPhase.AtBoundary) return@LaunchedEffect
        delay(180)
        if (dial.phase == DialPhase.AtBoundary) {
            dial.phase = if (dial.fingerDown) DialPhase.Rotating else DialPhase.Expanded
        }
    }

    Box(modifier) {
        // 展开时全屏透明 scrim：点外部收起；down 命中 scrim 后底层列表收不到拖拽 → 不误触滚动。
        if (dial.phase != DialPhase.Collapsed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { dial.collapse() },
            )
        }

        if (dial.phase == DialPhase.Collapsed) {
            CollapsedKnob(
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = {
                    dial.touch()
                    expandDial(dial, refs, listState, state, onLoadOlder)
                },
            )
        } else {
            ExpandedFan(
                modifier = Modifier.align(Alignment.CenterEnd),
                dial = dial,
                onSteps = { delta ->
                    handleSteps(dial, currentRefs, listState, scope, currentState, onLoadOlder, delta)
                },
                onFingerUp = {
                    dial.fingerDown = false
                    if (dial.phase == DialPhase.Rotating) dial.phase = DialPhase.Expanded
                },
            )
        }

        if (dial.phase == DialPhase.WaitingOlder) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/** 展开：锚定到视口中心最近的用户消息，不立即滚动；无用户消息且有更老历史则直接狩猎。 */
private fun expandDial(
    dial: MessageDialState,
    refs: List<UserMsgRef>,
    listState: LazyListState,
    state: SessionUiState,
    onLoadOlder: () -> Unit,
) {
    if (refs.isNotEmpty()) {
        val center = listState.firstVisibleItemIndex + (listState.layoutInfo.visibleItemsInfo.size / 2)
        dial.selectedSeq = nearestUserSeq(refs, center) ?: refs.first().seq
        dial.phase = DialPhase.Expanded
    } else if (state.hasMore) {
        dial.selectedSeq = null
        dial.seekOldestSeq = null
        dial.seekPages = 0
        dial.seekEventsMark = state.events
        dial.phase = DialPhase.WaitingOlder
        onLoadOlder()
    }
}

/** 把一次角度步进（可含多格）落成跳转/翻页/触边动作；进入狩猎即丢弃剩余输入（卡住）。 */
private fun handleSteps(
    dial: MessageDialState,
    refs: List<UserMsgRef>,
    listState: LazyListState,
    scope: CoroutineScope,
    state: SessionUiState,
    onLoadOlder: () -> Unit,
    delta: Int,
) {
    dial.touch()
    if (dial.phase != DialPhase.Rotating && dial.phase != DialPhase.Expanded) return
    dial.phase = DialPhase.Rotating

    var selectedIndex = refs.indexOfFirst { it.seq == dial.selectedSeq }
    var remaining = delta
    while (remaining != 0) {
        val dir = if (remaining > 0) 1 else -1
        when (val r = stepResult(refs, selectedIndex, dir, state.hasMore)) {
            is DialStepResult.Jump -> {
                dial.selectedSeq = r.newSeq
                selectedIndex = refs.indexOfFirst { it.seq == r.newSeq }
                scope.launch { listState.scrollToItem(r.targetIndex) }
                vibrateTick(dial, boundary = false)
            }
            DialStepResult.NeedOlderPage -> {
                dial.seekOldestSeq = refs.firstOrNull()?.seq
                dial.seekPages = 0
                dial.seekEventsMark = state.events
                dial.phase = DialPhase.WaitingOlder
                onLoadOlder()
                return // 丢弃剩余输入（卡住）
            }
            DialStepResult.AtOlderBoundary,
            DialStepResult.AtNewerBoundary,
            -> {
                dial.phase = DialPhase.AtBoundary
                vibrateTick(dial, boundary = true)
                return
            }
        }
        remaining -= dir
    }
}

/** 震动（节流 60ms 合并）；boundary = 触底/顶用稍重一档。 */
private fun vibrateTick(dial: MessageDialState, boundary: Boolean) {
    val now = nowMillis()
    if (!shouldVibrate(dial.lastTickMs, now)) return
    dial.lastTickMs = now
    platformVibrateTick(boundary)
}

/** 收起圆钮：44dp 半透明，3 根刻度 + 中间红基准线提示。 */
@Composable
private fun CollapsedKnob(modifier: Modifier, onClick: () -> Unit) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val red = MaterialTheme.colorScheme.error
    Box(
        modifier
            .padding(end = 8.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DIAL_COLLAPSED_ALPHA))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val c = this.center
            val outer = this.size.minDimension / 2f
            val inner = outer * 0.45f
            for (deg in listOf(160f, 200f)) {
                val a = deg * PI.toFloat() / 180f
                val dir = Offset(cos(a), sin(a))
                drawLine(tickColor, c + dir * inner, c + dir * outer, strokeWidth = 1.6.dp.toPx())
            }
            val ra = 180f * PI.toFloat() / 180f
            val rdir = Offset(cos(ra), sin(ra))
            drawLine(red, c + rdir * inner, c + rdir * outer, strokeWidth = 2.dp.toPx())
        }
    }
}

/**
 * 展开扇形：静态层画 90° 扇面（开口向左）+ 固定红基准线（180° 角平分线）；
 * 旋转层只画刻度，graphicsLayer 绕右缘中点（扇面枢轴）旋转，读 accumDeg 不触发重组。
 */
@Composable
private fun ExpandedFan(
    modifier: Modifier,
    dial: MessageDialState,
    onSteps: (Int) -> Unit,
    onFingerUp: () -> Unit,
) {
    val fanColor = MaterialTheme.colorScheme.primary.copy(alpha = DIAL_FAN_ALPHA)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val red = MaterialTheme.colorScheme.error

    Box(
        modifier
            .fillMaxHeight()
            .width(128.dp)
            .pointerInput(Unit) {
                val pivot = Offset(size.width.toFloat(), size.height / 2f)
                var lastAngle = 0f
                var prevAccum = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        dial.fingerDown = true
                        dial.touch()
                        if (dial.phase == DialPhase.Expanded) dial.phase = DialPhase.Rotating
                        lastAngle = angleDeg(start, pivot)
                        prevAccum = dial.accumDeg
                    },
                    onDragEnd = { onFingerUp() },
                    onDragCancel = { onFingerUp() },
                    onDrag = { change, _ ->
                        val a = angleDeg(change.position, pivot)
                        val d = normalizeAngleDelta(a - lastAngle)
                        lastAngle = a
                        if (d != 0f) {
                            dial.accumDeg += d
                            dial.touch()
                            val crossed = stepsCrossed(prevAccum, dial.accumDeg, DIAL_STEP_ANGLE_DEG)
                            if (crossed != 0) {
                                prevAccum = dial.accumDeg
                                onSteps(crossed)
                            }
                        }
                    },
                )
            },
    ) {
        // 静态层：扇面 + 红线（固定，不随刻度旋转）。
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width, size.height / 2f)
            val radius = size.width
            drawArc(
                color = fanColor,
                startAngle = 135f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(pivot.x - radius, pivot.y - radius),
                size = Size(radius * 2, radius * 2),
            )
            val inner = radius * 0.12f
            val dir = Offset(cos(180f * PI.toFloat() / 180f), sin(180f * PI.toFloat() / 180f))
            drawLine(red, pivot + dir * inner, pivot + dir * radius, strokeWidth = 2.5.dp.toPx())
        }
        // 旋转层：刻度（每 18° 一根，整圈 20 根）。
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0.5f)
                    rotationZ = dial.accumDeg
                },
        ) {
            val pivot = Offset(size.width, size.height / 2f)
            val radius = size.width
            val inner = radius * 0.60f
            val outer = radius * 0.94f
            val tickCount = (360f / DIAL_STEP_ANGLE_DEG).toInt()
            for (k in 0 until tickCount) {
                val deg = 180f + k * DIAL_STEP_ANGLE_DEG
                val a = deg * PI.toFloat() / 180f
                val dir = Offset(cos(a), sin(a))
                drawLine(tickColor, pivot + dir * inner, pivot + dir * outer, strokeWidth = 2.dp.toPx())
            }
        }
    }
}
