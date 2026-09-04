package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * 移动端 Markdown 表格的横向滑动渲染。
 *
 * 背景（对应 docs/ui-mobile-first.md 教训 #1 的同类问题）：库（mikepenz 0.28）自带的
 * `MarkdownTable` 用固定列宽 `tableCellWidth = 160.dp` 且单元格 `overflow = Ellipsis`，
 * 宽表格的超宽单元格在固定宽度处被截断成「…」，即便库内部已经包了 horizontalScroll 也
 * 只能滑动一个被截断的固定宽表格，右侧真实内容永远看不见。
 *
 * 这里通过库的 `markdownComponents { table = ... }` 扩展点，用一个最小复制版表格替换默认
 * 表格：单元格 `softWrap = false`、`maxLines = 1`、`overflow = Clip`，用 TextMeasurer 按
 * 真实内容宽度量出每列宽度（表头加粗、表体常规分别计量后取最大值），再整体包一层
 * horizontalScroll。这样表格以真实内容宽度测量，超宽部分在气泡宽度内左右滑动查看。
 *
 * 视觉样式（背景/圆角/单元格内边距/表头加粗/正文字号/配色）全部沿用库的
 * LocalMarkdownColors / LocalMarkdownDimens 与传入的 typography.text，与替换前一致，
 * 不改变现有移动端表格样式约定。
 */
@Composable
internal fun ScrollableMarkdownTable(model: MarkdownComponentModel) {
    val content = model.content
    val colors = LocalMarkdownColors.current
    val dimens = LocalMarkdownDimens.current
    val bodyStyle = model.typography.text
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPadding = dimens.tableCellPadding

    // 表结构 + 每列宽度都只随内容变化（样式/字号在本 App 内固定），按 content 记忆即可。
    val table = remember(content) { parseTableNode(model.node) }
    val columnWidthsPx = remember(content) {
        val padPx = with(density) { cellPadding.toPx() }
        computeColumnWidthsPx(content, table, headerStyle, bodyStyle, measurer, padPx)
    }
    val columnWidths = columnWidthsPx.map { with(density) { it.toDp() } }
    val totalWidth = with(density) { columnWidthsPx.sum().toDp() }

    // 外层 fillMaxWidth 把可滚动区域死死限制在气泡宽度内；内层 horizontalScroll 只消费
    // 横向手势，竖向手势照常透传给外层消息列表（铁律 9：移动端滚动语义）。
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(dimens.tableCornerSize)),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                TableCellsRow(content, table.header, columnWidths, headerStyle, colors.tableText, cellPadding)
                Box(Modifier.width(totalWidth)) { MarkdownDivider() }
                table.rows.forEach { row ->
                    TableCellsRow(content, row, columnWidths, bodyStyle, colors.tableText, cellPadding)
                }
            }
        }
    }
}

/** 表格结构：表头单元格 + 表体各行（每行一组单元格，均以 ASTNode 保留 inline 标记）。 */
internal data class MarkdownTableModel(
    val header: List<ASTNode>,
    val rows: List<List<ASTNode>>,
)

/** 从 TABLE 节点解析出表头与表体行（与库 MarkdownTable 的遍历方式一致）。 */
internal fun parseTableNode(node: ASTNode): MarkdownTableModel {
    val headerCells = node.findChildOfType(HEADER)?.children?.filter { it.type == CELL } ?: emptyList()
    val rows = node.children
        .filter { it.type == ROW }
        .map { row -> row.children.filter { it.type == CELL } }
    return MarkdownTableModel(header = headerCells, rows = rows)
}

/** 在整篇 Markdown 的根节点里找到第一个表格节点（无则返回 null，供纯函数测试用）。 */
internal fun findTableNode(root: ASTNode): ASTNode? =
    root.children.firstOrNull { it.type == TABLE }

/** 按真实内容宽度计算每列宽度（px）：表头/表体同列取最大，再加左右单元格内边距。 */
private fun computeColumnWidthsPx(
    content: String,
    table: MarkdownTableModel,
    headerStyle: TextStyle,
    bodyStyle: TextStyle,
    measurer: TextMeasurer,
    cellPaddingPx: Float,
): List<Float> {
    val columnCount = table.header.size
    if (columnCount == 0) return emptyList()
    val widths = FloatArray(columnCount)
    table.header.forEachIndexed { i, cell ->
        widths[i] = maxOf(widths[i], measureCellPx(content, cell, headerStyle, measurer))
    }
    table.rows.forEach { row ->
        row.forEachIndexed { i, cell ->
            if (i < columnCount) {
                widths[i] = maxOf(widths[i], measureCellPx(content, cell, bodyStyle, measurer))
            }
        }
    }
    return widths.map { it + 2 * cellPaddingPx }
}

private fun measureCellPx(content: String, cell: ASTNode, style: TextStyle, measurer: TextMeasurer): Float =
    measurer.measure(content.buildMarkdownAnnotatedString(cell, style), style = style, softWrap = false)
        .size.width.toFloat()

@Composable
private fun TableCellsRow(
    content: String,
    cells: List<ASTNode>,
    columnWidths: List<Dp>,
    style: TextStyle,
    color: Color,
    cellPadding: Dp,
) {
    Row {
        cells.forEachIndexed { i, cell ->
            if (i < columnWidths.size) {
                Box(Modifier.width(columnWidths[i]).padding(horizontal = cellPadding)) {
                    BasicText(
                        text = content.buildMarkdownAnnotatedString(cell, style),
                        style = style,
                        color = { color },
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
