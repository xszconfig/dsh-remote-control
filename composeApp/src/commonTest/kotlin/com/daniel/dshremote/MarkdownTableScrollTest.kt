package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown 表格横向滑动渲染的表结构解析纯函数单测。
 *
 * 覆盖：表格识别、表头/表体行列数、单列表格、空单元格 + 代码块/长 URL 等极端内容不崩。
 * （列宽计算依赖 Compose 的 TextMeasurer，属 UI 层，不在纯函数单测范围。）
 */
class MarkdownTableScrollTest {

    private fun parse(md: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)

    @Test
    fun findTableNode_tablePresent() {
        assertNotNull(findTableNode(parse("| A | B |\n|---|---|\n| 1 | 2 |\n")))
    }

    @Test
    fun findTableNode_noTable() {
        assertNull(findTableNode(parse("just some **bold** text\n")))
    }

    @Test
    fun parseTableNode_headerAndRows() {
        val table = findTableNode(parse("| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |\n"))!!
        val model = parseTableNode(table)
        assertEquals(3, model.header.size)
        assertEquals(2, model.rows.size)
        assertEquals(listOf(3, 3), model.rows.map { it.size })
    }

    @Test
    fun parseTableNode_singleColumn() {
        val table = findTableNode(parse("| A |\n|---|\n| 1 |\n| 2 |\n"))!!
        val model = parseTableNode(table)
        assertEquals(1, model.header.size)
        assertEquals(2, model.rows.size)
        assertEquals(listOf(1, 1), model.rows.map { it.size })
    }

    @Test
    fun parseTableNode_emptyCellsAndInlineCodeAndLongUrl() {
        // 空单元格 + 行内代码 + 无空格长 URL：结构应完整解析、不抛异常
        val md = "| a | b |\n|---|---|\n| `x` | https://example.com/very/long/path?q=1 |\n|  |  |\n"
        val table = findTableNode(parse(md))!!
        val model = parseTableNode(table)
        assertEquals(2, model.header.size)
        assertEquals(2, model.rows.size)
        assertEquals(listOf(2, 2), model.rows.map { it.size })
    }

    // —— 列宽限界（用户拍板 #62）：长内容限宽、短内容自适应 ——

    @Test
    fun clampColumnWidths_longContent_clampedToMax() {
        // 内容宽超过上限 → 列宽 = 上限 + 左右内边距（不再无限拉长）
        val widths = clampColumnWidthsPx(listOf(5000f), maxContentWidthPx = 360f, cellPaddingPx = 16f)
        assertEquals(listOf(360f + 32f), widths)
    }

    @Test
    fun clampColumnWidths_shortContent_keepsAdaptive() {
        // 内容宽未超上限 → 保持自适应（内容宽 + 左右内边距）
        val widths = clampColumnWidthsPx(listOf(100f, 200f), maxContentWidthPx = 360f, cellPaddingPx = 16f)
        assertEquals(listOf(132f, 232f), widths)
    }

    @Test
    fun clampColumnWidths_mixed_shortAdaptiveLongClamped() {
        val widths = clampColumnWidthsPx(listOf(50f, 9999f, 300f), maxContentWidthPx = 360f, cellPaddingPx = 10f)
        assertEquals(listOf(70f, 380f, 320f), widths)
    }

    @Test
    fun clampColumnWidths_empty_returnsEmpty() {
        assertEquals(emptyList(), clampColumnWidthsPx(emptyList(), maxContentWidthPx = 360f, cellPaddingPx = 16f))
    }
}
