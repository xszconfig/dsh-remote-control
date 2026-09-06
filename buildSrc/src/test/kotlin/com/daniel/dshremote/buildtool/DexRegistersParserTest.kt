package com.daniel.dshremote.buildtool

import com.daniel.dshremote.buildtool.DexRegistersParser.Finding
import com.daniel.dshremote.buildtool.DexRegistersParser.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexRegistersParserTest {

    private val prefix = "com/daniel/dshremote"

    /** 合成一段最小 dexdump -d 文本；fields 在前、methods 在后（与真实输出一致）。 */
    private fun dump(vararg methods: Triple<String, String, Int>): String =
        buildString {
            for ((cls, name, reg) in methods) {
                appendLine("  Class descriptor  : 'L$cls;'")
                appendLine("      name          : '\$thisFieldShouldBeIgnored'")
                appendLine("      type          : 'I'")
                appendLine("      access        : 0x0001 (PUBLIC)")
                appendLine("      name          : '$name'")
                appendLine("      type          : '()V'")
                appendLine("      access        : 0x0001 (PUBLIC)")
                appendLine("      code          :")
                appendLine("      registers     : $reg")
                appendLine("      ins           : 1")
            }
        }

    @Test
    fun normal() {
        val findings = DexRegistersParser.parse(
            dump(Triple("com/daniel/dshremote/AppKt", "App", 16)),
            dexName = "classes.dex",
            classPrefix = prefix,
        )
        assertEquals(1, findings.size)
        assertEquals(Finding("com.daniel.dshremote.AppKt", "App", 16, "classes.dex"), findings[0])
        assertEquals(Severity.OK, DexRegistersParser.severity(16, warnMax = 128, errorMax = 256))
    }

    @Test
    fun over128() {
        val findings = DexRegistersParser.parse(
            dump(Triple("com/daniel/dshremote/AppKt\$Foo", "bar", 150)),
            dexName = "classes.dex",
            classPrefix = prefix,
        )
        assertEquals(150, findings[0].registers)
        assertEquals(Severity.WARN, DexRegistersParser.severity(150, warnMax = 128, errorMax = 256))
    }

    @Test
    fun over256() {
        val findings = DexRegistersParser.parse(
            dump(Triple("com/daniel/dshremote/AppKt\$Foo", "bar", 300)),
            dexName = "classes.dex",
            classPrefix = prefix,
        )
        assertEquals(300, findings[0].registers)
        assertEquals(Severity.ERROR, DexRegistersParser.severity(300, warnMax = 128, errorMax = 256))
    }

    @Test
    fun multiDex() {
        val all = DexRegistersParser.parse(
            dump(Triple("com/daniel/dshremote/A", "m1", 10)),
            dexName = "classes.dex",
            classPrefix = prefix,
        ) + DexRegistersParser.parse(
            dump(Triple("com/daniel/dshremote/B", "m2", 20)),
            dexName = "classes2.dex",
            classPrefix = prefix,
        )
        assertEquals(2, all.size)
        assertEquals("classes.dex", all[0].dexName)
        assertEquals("com.daniel.dshremote.A", all[0].className)
        assertEquals("classes2.dex", all[1].dexName)
        assertEquals("com.daniel.dshremote.B", all[1].className)
    }

    @Test
    fun tolerantParsing() {
        val text = """
            |  Class descriptor  : 'Lcom/daniel/dshremote/AppKt;'
            |      registers     : 99999999999999999999
            |  Class descriptor  : 'Landroidx/compose/runtime/ComposerKt;'
            |      name          : 'sourceInformation'
            |      registers     : 128
            |  Class descriptor  : 'Lcom/daniel/dshremote/AppKt;'
            |      name          : 'ok'
            |      registers     : 7
        """.trimMargin()

        val findings = DexRegistersParser.parse(text, dexName = "classes.dex", classPrefix = prefix)

        // 只有最后一条「有归属 + 数字合法 + 包匹配」的被解析出来：
        //   1) registers 溢出 Int → 跳过；2) 库类被过滤；3) 正常。
        assertEquals(1, findings.size)
        assertEquals(7, findings[0].registers)
        assertEquals("ok", findings[0].methodName)

        // 空输入 / 无寄存器行 → 空结果，不抛异常。
        assertTrue(DexRegistersParser.parse("", "classes.dex", prefix).isEmpty())
        assertTrue(DexRegistersParser.parse("just some garbage\nno registers here", "classes.dex", prefix).isEmpty())
    }
}
