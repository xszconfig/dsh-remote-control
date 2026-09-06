package com.daniel.dshremote.buildtool

/**
 * R4「构建期 DEX registers_size 门禁」的解析器（纯函数，无副作用，可单测）。
 *
 * 输入是 `dexdump -d <dex>` 的 stdout 文本，输出该 dex 内属于目标包（classPrefix）的
 * 每个方法的寄存器数。方法级寄存器 `registers : N` 是 VerifyError 根因（窄寄存器上限）的
 * 直接信号，比行数启发式更贴近运行时崩溃。
 *
 * dexdump 结构（-d 反汇编）：
 * ```
 *   Class descriptor  : 'Lcom/daniel/dshremote/AppKt$ContextRow$3;'
 *       name          : 'invoke'
 *       type          : '(...)V'
 *       access        : 0x0011 (PUBLIC FINAL)
 *       code          :
 *       registers     : 130
 * ```
 * 类名是 DEX 描述符（`L...;`、斜杠分隔）；字段 `name` 排在方法 `name` 之前且无 `registers` 行，
 * 因此「最后一个 `name` + 最近的 `Class descriptor`」在遇到 `registers` 时即为该方法的归属。
 */
object DexRegistersParser {

    /** 单个方法的寄存器数命中。 */
    data class Finding(
        /** 点分隔类名，如 `com.daniel.dshremote.AppKt$ContextRow$3` */
        val className: String,
        /** 方法名，如 `invoke` */
        val methodName: String,
        /** 方法寄存器数（registers_size） */
        val registers: Int,
        /** 所属 dex 文件名，如 `classes5.dex` */
        val dexName: String,
    )

    enum class Severity { OK, WARN, ERROR }

    private val classRegex = Regex("""Class descriptor\s*:\s*'([^']*)'""")
    private val nameRegex = Regex("""name\s*:\s*'([^']*)'""")
    private val registersRegex = Regex("""registers\s*:\s*(\d+)""")

    /**
     * 解析一段 dexdump 输出。
     *
     * @param dexdumpText `dexdump -d <dex>` 的 stdout 文本（可为非严格 UTF-8，调用方应按
     *   ISO-8859-1 解码，本函数只匹配 ASCII 结构行，对乱码字节不敏感）。
     * @param dexName 该段文本对应的 dex 文件名，原样写入 [Finding.dexName]（用于多 dex 溯源）。
     * @param classPrefix 只保留类描述符（斜杠形式、不含 `L`/`;`）以该前缀开头的类；
     *   传空串表示不过滤（扫描全部类，含三方库）。
     * @return 目标类内所有「有 code 的方法」的寄存器数；解析失败/无归属的行静默跳过（容错）。
     */
    fun parse(dexdumpText: String, dexName: String, classPrefix: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        var currentClass: String? = null
        var currentMethod: String? = null

        for (rawLine in dexdumpText.lineSequence()) {
            val line = rawLine.trim()
            when {
                classRegex.containsMatchIn(line) -> {
                    currentClass = classRegex.find(line)!!.groupValues[1].normalizeDescriptor()
                    currentMethod = null
                }

                nameRegex.containsMatchIn(line) -> {
                    currentMethod = nameRegex.find(line)!!.groupValues[1]
                }

                registersRegex.containsMatchIn(line) -> {
                    val reg = registersRegex.find(line)!!.groupValues[1].toIntOrNull()
                    val cls = currentClass
                    val method = currentMethod
                    if (reg != null && cls != null && method != null && cls.startsWith(classPrefix)) {
                        findings += Finding(
                            className = cls.replace('/', '.'),
                            methodName = method,
                            registers = reg,
                            dexName = dexName,
                        )
                    }
                    // 容错：registers 非数字 / 无类 / 无方法名 时跳过
                }
            }
        }
        return findings
    }

    /** 寄存器数 → 门禁级别（>errorMax 报错、>warnMax 告警、否则 OK）。 */
    fun severity(registers: Int, warnMax: Int, errorMax: Int): Severity = when {
        registers > errorMax -> Severity.ERROR
        registers > warnMax -> Severity.WARN
        else -> Severity.OK
    }

    private fun String.normalizeDescriptor(): String =
        removePrefix("L").removeSuffix(";")
}
