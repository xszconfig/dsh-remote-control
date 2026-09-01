package com.daniel.dshremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * reverseLayout 消息列表下，「最新一条消息」气泡的 index。
 *
 * LazyColumn 的 item 顺序（reverseLayout=true，index 0 在底部 = 最新端）：
 *   index 0     = live-think 流式行（仅思考流式期间存在）
 *   index 1..N  = events.asReversed()（最新事件在最前，即底部）
 *   最高 index   = loading-older（最早历史端，翻页加载时存在）
 *
 * 因此最新事件气泡 = liveThink 存在时 index 1，否则 index 0。
 * 该列表分支只在 events 非空时渲染，调用方保证至少存在一条事件。
 */
internal fun latestEventIndex(hasLiveThink: Boolean): Int = if (hasLiveThink) 1 else 0

/**
 * 判定「最新一条消息」是否可见（reverseLayout 下）。
 *
 * [visibleIndexes]：`listState.layoutInfo.visibleItemsInfo.map { it.index }`。
 * [latestEventIndex]：[latestEventIndex] 的计算结果。
 *
 * 部分可见也算可见：visibleItemsInfo 包含任何与视口相交的 item，用户已能看到其边缘，
 * 此时不必再弹「回到底部」按钮。返回 true = 最新消息已可见（按钮应隐藏）。
 */
internal fun latestMessageVisible(visibleIndexes: List<Int>, latestEventIndex: Int): Boolean =
    latestEventIndex in visibleIndexes

/** 「回到底部」圆形悬浮按钮：尺寸与发送按钮一致（48dp），半透明底不挡下方消息阅读。 */
@Composable
internal fun JumpToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        DownArrowIcon()
    }
}

/**
 * 带淡入淡出的「回到底部」悬浮层：独立成顶层 composable 是为了绕开
 * `ColumnScope.AnimatedVisibility` 扩展在 Box 内无法隐式解析的歧义
 * （此处只有 BoxScope receiver，无 ColumnScope，稳定命中顶层 AnimatedVisibility）。
 */
@Composable
internal fun JumpToBottomOverlay(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        JumpToBottomButton(onClick = onClick)
    }
}

/** 向下箭头：Material arrow-downward 路径 Canvas 自绘（与 SendIcon/StopIcon 同款，无新依赖）。 */
@Composable
private fun DownArrowIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp)) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(20f * s, 12f * s)
            lineTo(18.59f * s, 10.59f * s)
            lineTo(13f * s, 16.17f * s)
            lineTo(13f * s, 4f * s)
            lineTo(11f * s, 4f * s)
            lineTo(11f * s, 16.17f * s)
            lineTo(5.41f * s, 10.59f * s)
            lineTo(4f * s, 12f * s)
            lineTo(12f * s, 20f * s)
            close()
        }
        drawPath(path, color)
    }
}
