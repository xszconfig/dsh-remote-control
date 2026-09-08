package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownTypography

/** 代码块按行拆分 + 右对齐（padStart）的行号文本。 */
internal data class CodeBlockLines(val lines: List<String>, val lineNumbers: List<String>)

/** 将代码拆成行（去掉 \r），并按最大行号位数生成 padStart 右对齐的行号。 */
internal fun buildCodeBlockLines(code: String): CodeBlockLines {
    val lines = code.split('\n').map { it.removeSuffix("\r") }
    val digits = lines.size.toString().length
    val numbers = List(lines.size) { (it + 1).toString().padStart(digits, ' ') }
    return CodeBlockLines(lines, numbers)
}

/**
 * Markdown 代码块渲染：一行代码 = 一行展示（不换行），代码区横向滚动，行号固定。
 *
 * 取舍：选「行号固定、代码区横滑」而非「整体横滑」——符合 IDE/代码浏览习惯：往右看长行时
 * 行号始终可见。实现上把行号放在外层 Row 的固定列（自然宽度），代码列用 `weight(1f)` 占
 * 剩余宽度并套 `horizontalScroll`：横向手势只作用在代码区，竖向手势照常透传给外层消息列表
 * （铁律 9：移动端滚动语义）。
 *
 * 配色/字号沿用 mikepenz 的 LocalMarkdownColors/LocalMarkdownTypography（App 已把 code 定为
 * 12sp mono、codeBackground/codeText 定为深底浅字），与替换前的库默认代码块视觉一致。
 */
@Composable
internal fun ScrollableCodeBlock(code: String) {
    val colors = LocalMarkdownColors.current
    val codeStyle = LocalMarkdownTypography.current.code
    val corner = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val model = remember(code) { buildCodeBlockLines(code) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.codeBackground, RoundedCornerShape(corner))
            .padding(vertical = 8.dp),
    ) {
        Row {
            // 行号固定列：不随横向滚动，右对齐（等宽字体 + padStart）。
            Column(Modifier.padding(start = 12.dp, end = 10.dp)) {
                model.lineNumbers.forEach { num ->
                    Text(
                        text = num,
                        style = codeStyle.copy(color = colors.codeText.copy(alpha = 0.45f)),
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
            // 代码区：占剩余宽度 + 横向滚动；每行独立 Text 且 softWrap=false → 不折行。
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Column(Modifier.padding(end = 12.dp)) {
                    model.lines.forEach { line ->
                        Text(
                            text = line.ifEmpty { " " },
                            style = codeStyle,
                            color = colors.codeText,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
