package com.daniel.dshremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.graphics.drawscope.Stroke
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

// 收起圆钮命中判定（绝对 dp，pointerInput 内 dp.toPx() 换算）：
// - 命中半径：圆钮半径 24dp + 6dp 容差 = 30dp
// - 圆钮中心即手势表面中心（表面与 48dp 圆钮等大，响应区=视觉区；背景对齐跳到底按钮 48dp）
private const val KNOB_HIT_RADIUS_DP = 30f
/** 收起态手势表面边长（与 48dp 圆钮等大，对齐跳到底按钮尺寸）。 */
private val DIAL_COLLAPSED_SURFACE_SIZE = 48.dp
/** 展开扇面枢轴距列表底的距离：8dp 底边距 + 24dp 圆钮半径 = 32dp（原地展开，枢轴对齐圆钮中心 Y）。 */
private const val DIAL_PIVOT_BOTTOM_INSET_DP = 32f

/**
 * 消息转盘：右侧中间的半透明旋钮，点击展开为 90° 扇形，单指拨动快速定位到「你发的消息」。
 *
 * 数据来源一律以 [SessionUiState]（服务端投影）为准：用户消息集合由 [userMessageRefs]
 * 从 view.events 惰性扫描得出；翻页复用 [onLoadOlder]（内部已有 loadingOlder 单飞守卫）。
 */
class MessageDialState {
    var phase by mutableStateOf(DialPhase.Collapsed)
    /** 扇面刻度累计旋转角（度）；正 = 顺时针 = 更老。graphicsLayer 读它，不触发重组。 */
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

/**
 * 直接定位到目标行并把它顶到「屏幕顶部」（reverseLayout 下 scrollToItem 落底部，再向上补偿）。
 * 补偿量见 [scrollDeltaToTop]；目标行为最新一条（其下无更新内容）时 scrollBy 会被可滚动范围钳制，
 * 行会尽量靠近顶部（物理上无法越过底部最新边界）。
 */
private suspend fun LazyListState.scrollToTop(index: Int) {
    scrollToItem(index)
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportH = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    scrollBy(scrollDeltaToTop(viewportH, info.size).toFloat())
}

@Composable
fun MessageDial(
    dial: MessageDialState,
    view: SessionViewState,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val hasLiveThink = view.liveThink != null
    val refs = remember(view.events, hasLiveThink) { userMessageRefs(view.events, hasLiveThink) }

    // 完全没有用户消息、也没有更早历史 → 不渲染。
    if (refs.isEmpty() && !view.hasMore) return

    // 狩猎唤醒：loadingOlder 翻页完成 → 决策（卡住 → 继续 / 链式翻页 / snap 到第一条 / 失败退出）。
    LaunchedEffect(view.loadingOlder) {
        if (dial.phase != DialPhase.WaitingOlder) return@LaunchedEffect
        if (view.loadingOlder) return@LaunchedEffect
        if (view.events === dial.seekEventsMark) {
            // events 未变 = 发送失败/无进展：退出，不自动重试（横幅已由 loadOlderPage 提示）。
            dial.phase = DialPhase.Expanded
            return@LaunchedEffect
        }
        when (val o = seekOutcome(refs, dial.seekOldestSeq, dial.selectedSeq, view.hasMore, dial.fingerDown, dial.seekPages)) {
            is SeekOutcome.Resume -> {
                dial.selectedSeq = o.anchorSeq
                dial.phase = if (dial.fingerDown) DialPhase.Rotating else DialPhase.Expanded
            }
            SeekOutcome.ChainMore -> {
                dial.seekPages++
                dial.seekEventsMark = view.events
                onLoadOlder()
            }
            is SeekOutcome.SnapToOldest -> {
                dial.selectedSeq = o.seq
                dial.phase = DialPhase.Expanded
                scope.launch { listState.scrollToTop(o.rowIndex) }
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

    DialOverlay(
        dial = dial,
        refs = refs,
        view = view,
        listState = listState,
        scope = scope,
        onLoadOlder = onLoadOlder,
        modifier = modifier,
    )
}

/** 转盘交互面：展开 scrim + DialSurface + 狩猎进度指示，统一挂在一个 Box 里。 */
@Composable
private fun DialOverlay(
    dial: MessageDialState,
    refs: List<UserMsgRef>,
    view: SessionViewState,
    listState: LazyListState,
    scope: CoroutineScope,
    onLoadOlder: () -> Unit,
    modifier: Modifier,
) {
    // 手势 pointerInput(Unit) 不随重组重启：用 rememberUpdatedState 让 onSteps 读到最新 refs/state，
    // 否则翻页完成（events 变化）后手指仍按住继续转时，会用旧 refs 误判边界。
    val currentRefs by rememberUpdatedState(refs)
    val currentState by rememberUpdatedState(view)

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

        DialSurface(
            modifier = Modifier
                .align(if (dial.phase == DialPhase.Collapsed) Alignment.BottomStart else Alignment.CenterStart)
                .then(
                    if (dial.phase == DialPhase.Collapsed) Modifier.padding(start = 8.dp, bottom = 8.dp)
                    else Modifier
                ),
            dial = dial,
            onDragStart = {
                dial.touch()
                if (dial.phase == DialPhase.Collapsed) {
                    expandDial(dial, currentRefs, listState, currentState, onLoadOlder)
                }
                if (dial.phase == DialPhase.Expanded) dial.phase = DialPhase.Rotating
            },
            onTap = {
                if (dial.phase == DialPhase.Collapsed) {
                    dial.touch()
                    expandDial(dial, currentRefs, listState, currentState, onLoadOlder)
                }
            },
            onSteps = { delta ->
                handleSteps(dial, currentRefs, listState, scope, currentState, onLoadOlder, delta)
            },
            onFingerUp = {
                dial.fingerDown = false
                if (dial.phase == DialPhase.Rotating) dial.phase = DialPhase.Expanded
            },
        )

        if (dial.phase == DialPhase.WaitingOlder) {
            // 翻页 loading 跟随扇面锚点：枢轴右侧、枢轴高度（BottomStart + start 32dp + bottom 29dp，
            // 29 = 38(枢轴底距) - 9(半 spinner)，使 spinner 垂直居中于枢轴 Y）。收起态无 loading。
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 32.dp, bottom = 29.dp)
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
    view: SessionViewState,
    onLoadOlder: () -> Unit,
) {
    ConnLog.info("ACTION", "转盘打开 refs=${refs.size} hasMore=${view.hasMore}")
    if (refs.isNotEmpty()) {
        val center = listState.firstVisibleItemIndex + (listState.layoutInfo.visibleItemsInfo.size / 2)
        dial.selectedSeq = nearestUserSeq(refs, center) ?: refs.first().seq
        dial.phase = DialPhase.Expanded
    } else if (view.hasMore) {
        dial.selectedSeq = null
        dial.seekOldestSeq = null
        dial.seekPages = 0
        dial.seekEventsMark = view.events
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
    view: SessionViewState,
    onLoadOlder: () -> Unit,
    delta: Int,
) {
    dial.touch()
    if (dial.phase != DialPhase.Rotating && dial.phase != DialPhase.Expanded) return
    dial.phase = DialPhase.Rotating
    ConnLog.throttled(ConnLogLevel.DEBUG, "ACTION", "dial-slide", 500) { "转盘滑动 delta=$delta" }

    var selectedIndex = refs.indexOfFirst { it.seq == dial.selectedSeq }
    var remaining = delta
    while (remaining != 0) {
        val dir = if (remaining > 0) 1 else -1
        when (val r = stepResult(refs, selectedIndex, dir, view.hasMore)) {
            is DialStepResult.Jump -> {
                dial.selectedSeq = r.newSeq
                selectedIndex = refs.indexOfFirst { it.seq == r.newSeq }
                ConnLog.info("ACTION", "转盘选中 seq=${r.newSeq} targetIndex=${r.targetIndex} 视口首行=${listState.firstVisibleItemIndex}")
                scope.launch { listState.scrollToTop(r.targetIndex) }
                vibrateTick(dial, boundary = false)
            }
            DialStepResult.NeedOlderPage -> {
                dial.seekOldestSeq = refs.firstOrNull()?.seq
                dial.seekPages = 0
                dial.seekEventsMark = view.events
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

/** 收起圆钮视觉（纯绘制，无手势）：48dp 半透明「九线半圆扇」——红基准线 0°（最长最粗）+ 左右各 4 根短刻度 ±22.5°/±45°/±67.5°/±90° + 细弧线勾勒半圆轮廓（图案尺寸不变，只缩小背景圆）。 */
@Composable
private fun CollapsedKnobVisual() {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val red = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DIAL_COLLAPSED_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        // 背景 56dp→48dp，内边距 6→2 让 Canvas 仍为 44dp：刻度图案绝对尺寸不变（outer=22dp）。
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val c = this.center
            val outer = this.size.minDimension / 2f
            // 短刻度：±90°/±67.5°/±45°/±22.5°（九线半圆扇面，与展开态形状更接近）
            val tickInner = outer * 0.45f
            val tickOuter = outer * 0.82f
            for (deg in listOf(-90f, -67.5f, -45f, -22.5f, 22.5f, 45f, 67.5f, 90f)) {
                val a = deg * PI.toFloat() / 180f
                val dir = Offset(cos(a), sin(a))
                drawLine(tickColor, c + dir * tickInner, c + dir * tickOuter, strokeWidth = 2.2.dp.toPx())
            }
            // 红基准线：0°（水平向右），最长最粗
            val redInner = outer * 0.12f
            val redOuter = outer * 0.95f
            val dir = Offset(cos(0f), sin(0f))
            drawLine(red, c + dir * redInner, c + dir * redOuter, strokeWidth = 3.dp.toPx())
            // 细弧线勾勒半圆轮廓（-90°~+90°）
            val arcR = outer * 0.82f
            drawArc(
                color = tickColor.copy(alpha = 0.5f),
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - arcR, c.y - arcR),
                size = Size(arcR * 2, arcR * 2),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * 统一转盘交互面：收起态 48dp 小表面（仅覆盖圆钮，不挡发送/中断等按钮）、展开态 128dp 全高条。
 * 单一 pointerInput(Unit) 不因 phase 切换而重建，保证「摁下圆钮不抬指直接滑动 → 展开并旋转」一气呵成。
 * 收起态命中区域外不响应也不拦截（表面本身已缩小到圆钮区，事件自然落到底层按钮/列表）；
 * 展开态全条 + scrim 主动独占拦截是有意行为。
 */
@Composable
private fun DialSurface(
    modifier: Modifier,
    dial: MessageDialState,
    onDragStart: () -> Unit,
    onTap: () -> Unit,
    onSteps: (Int) -> Unit,
    onFingerUp: () -> Unit,
) {
    val collapsed = dial.phase == DialPhase.Collapsed
    Box(
        modifier
            .then(if (collapsed) Modifier.size(DIAL_COLLAPSED_SURFACE_SIZE) else Modifier.fillMaxHeight().width(128.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    // pivot 屏幕位置恒定 = (列表左缘, 圆钮中心 Y)，原地展开（不在屏幕中间弹跳）。
                    // 角度符号推导：angleDeg = atan2(Δy, Δx)，屏幕坐标 y 向下，故 atan2 角度随触点
                    // 顺时针移动而增大（右 0°→下 +90°→左 ±180°→上 -90°），normalizeAngleDelta 正值=顺时针，
                    // accumDeg 正=顺时针。方向语义：顺时针=看更老（stepResult delta +1 走更老，refs 下标递减），
                    // 逆时针=看更新。左/右缘镜像只改 pivot 位置、不改该符号。
                    fun pivot() = if (dial.phase == DialPhase.Collapsed) {
                        // 收起：56dp 表面，圆钮中心 = 表面中心
                        Offset(0f, size.height / 2f)
                    } else {
                        // 展开：全高条，圆钮中心 Y = 距列表底 38dp（10dp 底边距 + 28dp 半径）
                        Offset(0f, size.height - DIAL_PIVOT_BOTTOM_INSET_DP.dp.toPx())
                    }

                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 收起态只在圆钮命中半径内响应（表面已缩小到圆钮区，未命中自然落到底层）；展开态全条响应。
                    val active = if (dial.phase == DialPhase.Collapsed) {
                        // 表面与圆钮等大，圆钮中心=表面中心
                        val knobCenter = Offset(size.width / 2f, size.height / 2f)
                        (down.position - knobCenter).getDistance() <= KNOB_HIT_RADIUS_DP.dp.toPx()
                    } else {
                        true
                    }
                    if (!active) return@awaitEachGesture

                    down.consume()
                    var didDrag = false
                    var lastAngle = angleDeg(down.position, pivot())
                    var prevAccum = dial.accumDeg

                    val slopReached = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                        didDrag = true
                        dial.fingerDown = true
                        dial.touch()
                        // 先按收起态 pivot 记录超阈值触点角度，再展开（onDragStart 后 phase 变、surface 变大）
                        lastAngle = angleDeg(change.position, pivot())
                        prevAccum = dial.accumDeg
                        onDragStart()
                    }

                    if (slopReached == null) {
                        // 未超阈值即抬起 → 轻点
                        dial.fingerDown = false
                        onTap()
                        return@awaitEachGesture
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        val a = angleDeg(change.position, pivot())
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
                    }
                    onFingerUp()
                }
            },
    ) {
        if (collapsed) {
            CollapsedKnobVisual()
        } else {
            FanVisual(dial)
        }
    }
}

/**
 * 展开扇形视觉（纯绘制，无手势）：静态层画 90° 扇面（开口向右）+ 固定红基准线（0° 水平向右）；
 * 旋转层只画刻度，graphicsLayer 绕左缘、圆钮中心 Y（原地展开）旋转，读 accumDeg 不触发重组。
 */
@Composable
private fun FanVisual(dial: MessageDialState) {
    val fanColor = MaterialTheme.colorScheme.primary.copy(alpha = DIAL_FAN_ALPHA)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val red = MaterialTheme.colorScheme.error

    Box(Modifier.fillMaxSize()) {
        // 静态层：扇面 + 红线（固定，不随刻度旋转）。pivot = (左缘, 距列表底 38dp) = 圆钮中心 Y。
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(0f, size.height - DIAL_PIVOT_BOTTOM_INSET_DP.dp.toPx())
            val radius = size.width
            drawArc(
                color = fanColor,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(pivot.x - radius, pivot.y - radius),
                size = Size(radius * 2, radius * 2),
            )
            val inner = radius * 0.12f
            val dir = Offset(cos(0f), sin(0f))
            drawLine(red, pivot + dir * inner, pivot + dir * radius, strokeWidth = 2.5.dp.toPx())
        }
        // 旋转层：刻度（每 18° 一根，整圈 20 根；基角 0° 与红基准线对齐）。绕枢轴旋转。
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val inset = DIAL_PIVOT_BOTTOM_INSET_DP.dp.toPx()
                    transformOrigin = TransformOrigin(0f, (size.height - inset) / size.height)
                    rotationZ = dial.accumDeg
                },
        ) {
            val pivot = Offset(0f, size.height - DIAL_PIVOT_BOTTOM_INSET_DP.dp.toPx())
            val radius = size.width
            val inner = radius * 0.60f
            val outer = radius * 0.94f
            val tickCount = (360f / DIAL_STEP_ANGLE_DEG).toInt()
            for (k in 0 until tickCount) {
                val deg = k * DIAL_STEP_ANGLE_DEG
                val a = deg * PI.toFloat() / 180f
                val dir = Offset(cos(a), sin(a))
                drawLine(tickColor, pivot + dir * inner, pivot + dir * outer, strokeWidth = 2.dp.toPx())
            }
        }
    }
}
