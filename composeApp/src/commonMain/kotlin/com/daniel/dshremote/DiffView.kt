package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.FileDiffWire

// ==================== 文件变更 Code Diff 渲染（红删绿增，与 DSH Web 对齐） ====================

private sealed interface DiffLine {
    data class Eq(val text: String) : DiffLine
    data class Del(val text: String) : DiffLine
    data class Add(val text: String) : DiffLine
}

/** 行级 LCS diff：old 删除行标红、new 新增行标绿、公共行原样。超限退化（全删+全增）。 */
private fun diffLines(old: String?, new: String): List<DiffLine> {
    val a = (old ?: "").split('\n')
    val b = new.split('\n')
    if (a.size.toLong() * b.size.toLong() > 200_000) {
        return a.map { DiffLine.Del(it) } + b.map { DiffLine.Add(it) }
    }
    val n = a.size
    val m = b.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { out.add(DiffLine.Eq(a[i])); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(DiffLine.Del(a[i])); i++ }
            else -> { out.add(DiffLine.Add(b[j])); j++ }
        }
    }
    while (i < n) { out.add(DiffLine.Del(a[i])); i++ }
    while (j < m) { out.add(DiffLine.Add(b[j])); j++ }
    return out
}

// 与 Markdown 代码块一致的深底 + GitHub 风格的增减配色
private val DiffBg = Color(0xFF14181F)
private val DiffContextFg = Color(0xFFDCE4EF)
private val DiffDelBg = Color(0xFF3B1D24)
private val DiffDelFg = Color(0xFFFF9AA2)
private val DiffAddBg = Color(0xFF17321F)
private val DiffAddFg = Color(0xFF7EE787)

private data class DiffStyle(val bg: Color, val fg: Color, val prefix: String, val text: String)

@Composable
fun CodeDiffBlock(diffs: List<FileDiffWire>) {
    Column {
        diffs.forEachIndexed { idx, d ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            CodeDiffCard(d)
        }
    }
}

@Composable
private fun CodeDiffCard(d: FileDiffWire) {
    val lines = remember(d.oldText, d.newText) { diffLines(d.oldText, d.newText) }
    val adds = lines.count { it is DiffLine.Add }
    val dels = lines.count { it is DiffLine.Del }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DiffBg,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    d.path.ifBlank { "(文件)" },
                    color = DiffContextFg.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "+$adds −$dels",
                    color = DiffContextFg.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column(Modifier.padding(vertical = 4.dp)) {
                lines.forEach { l ->
                    val st = when (l) {
                        is DiffLine.Del -> DiffStyle(DiffDelBg, DiffDelFg, "−", l.text)
                        is DiffLine.Add -> DiffStyle(DiffAddBg, DiffAddFg, "+", l.text)
                        is DiffLine.Eq -> DiffStyle(Color.Transparent, DiffContextFg, " ", l.text)
                    }
                    Row(
                        Modifier.fillMaxWidth().background(st.bg).padding(horizontal = 10.dp, vertical = 1.dp),
                    ) {
                        Text(st.prefix, color = st.fg.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            st.text.ifEmpty { " " },
                            color = st.fg,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
