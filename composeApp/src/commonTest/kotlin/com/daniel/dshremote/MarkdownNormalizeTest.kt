package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Markdown 表格前补空行的归一化纯函数单测。
 *
 * 根因：org.intellij.markdown 的 GFM 表格不支持打断段落——表格前缺空行时整块塌成纯文本；
 * 两个连续表格之间缺空行时会被合并。normalizeMarkdownTables 只在「表格表头行（行首 `|`
 * 且下一行是分隔行）」前、且前一行非空时插入一个空行，其余内容原样保留。
 */
class MarkdownNormalizeTest {

    private val single = "| 功能 | 竞品依据 | 我们现状 |\n|---|---|---|\n| a | b | c |\n"

    @Test
    fun tableAfterParagraph_insertsBlankLine() {
        val input = "**P0 批次（直接补竞争短板，1-2 周）**\n" + single
        val expected = "**P0 批次（直接补竞争短板，1-2 周）**\n\n" + single
        assertEquals(expected, normalizeMarkdownTables(input))
    }

    @Test
    fun tableAlreadyPrecededByBlankLine_isIdempotent() {
        val input = "**P0 批次**\n\n" + single
        assertEquals(input, normalizeMarkdownTables(input))
    }

    @Test
    fun bodyRowsInsideTable_untouched() {
        val input = "| a | b |\n|---|---|\n| x | y |\n| p | q |\n"
        assertEquals(input, normalizeMarkdownTables(input))
    }

    @Test
    fun consecutiveTables_insertBlankBetween() {
        val t1 = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        val t2 = "| c | d |\n|---|---|\n| 3 | 4 |\n"
        assertEquals(t1 + "\n" + t2, normalizeMarkdownTables(t1 + t2))
    }

    @Test
    fun consecutiveTables_alreadySeparated_idempotent() {
        val t1 = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        val t2 = "| c | d |\n|---|---|\n| 3 | 4 |\n"
        val input = t1 + "\n" + t2
        assertEquals(input, normalizeMarkdownTables(input))
    }

    @Test
    fun noTable_unchanged() {
        val input = "hello **world**\n\n- item\n- item2\n"
        assertEquals(input, normalizeMarkdownTables(input))
    }

    @Test
    fun pipeLineWithoutDelimiterRow_notTreatedAsTable() {
        // 行首 `|` 但下一行不是分隔行 → 不是表头，不补空行（防误判）。
        val input = "some text\n| not a table\nmore text\n"
        assertEquals(input, normalizeMarkdownTables(input))
    }

    @Test
    fun delimiterRowWithAlignmentColons_recognized() {
        val input = "text\n| a | b |\n|:---|:---:|\n| 1 | 2 |\n"
        val expected = "text\n\n| a | b |\n|:---|:---:|\n| 1 | 2 |\n"
        assertEquals(expected, normalizeMarkdownTables(input))
    }

    @Test
    fun emptyOrSingleLine_unchanged() {
        assertEquals("", normalizeMarkdownTables(""))
        assertEquals("just one line", normalizeMarkdownTables("just one line"))
    }
}
