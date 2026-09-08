package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals

/** 代码块按行拆分 + 行号生成纯函数单测。 */
class MarkdownCodeBlockTest {

    @Test
    fun split_basic() {
        val m = buildCodeBlockLines("a\nbb\nccc")
        assertEquals(listOf("a", "bb", "ccc"), m.lines)
        assertEquals(listOf("1", "2", "3"), m.lineNumbers)
    }

    @Test
    fun emptyCode_singleBlankLine() {
        val m = buildCodeBlockLines("")
        assertEquals(listOf(""), m.lines)
        assertEquals(listOf("1"), m.lineNumbers)
    }

    @Test
    fun trailingNewline_keepsEmptyLastLine() {
        val m = buildCodeBlockLines("a\nb\n")
        assertEquals(listOf("a", "b", ""), m.lines)
        assertEquals(listOf("1", "2", "3"), m.lineNumbers)
    }

    @Test
    fun windowsLineEnding_stripsCarriageReturn() {
        val m = buildCodeBlockLines("a\r\nb\r\nc")
        assertEquals(listOf("a", "b", "c"), m.lines)
    }

    @Test
    fun manyLines_rightAlignsNumbersWithPadStart() {
        // 12 行 → 行号占 2 位，右对齐（padStart 用空格补位，等宽字体下自然右对齐）。
        val code = (1..12).joinToString("\n") { "line$it" }
        val m = buildCodeBlockLines(code)
        assertEquals(listOf(" 1", " 2", " 3", " 4", " 5", " 6", " 7", " 8", " 9", "10", "11", "12"), m.lineNumbers)
        assertEquals(12, m.lines.size)
    }
}
