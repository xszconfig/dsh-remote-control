package com.daniel.dshremote.buildtool

import java.io.File
import java.util.zip.ZipFile

/**
 * R4 门禁的「扫描器」：定位 dexdump、解包 APK 里的 classes*.dex、逐 dex 跑 dexdump 并交给
 * [DexRegistersParser] 解析。全部逻辑不依赖 Gradle API（用 JDK ZipFile / ProcessBuilder），
 * 因此可被 composeApp/build.gradle.kts 的任务安全调用，且与配置缓存兼容。
 */
object DexRegistersScanner {

    /** 解析 Android SDK 根目录：优先 local.properties 的 sdk.dir，再回退 ANDROID_HOME/ANDROID_SDK_ROOT。 */
    fun findSdkDir(rootDir: File): File? {
        val lp = File(rootDir, "local.properties")
        if (lp.isFile) {
            val m = Regex("""sdk\.dir\s*=\s*(.+)""").find(lp.readText())
            if (m != null) {
                val f = File(m.groupValues[1].trim())
                if (f.isDirectory) return f
            }
        }
        val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!env.isNullOrBlank()) {
            val f = File(env)
            if (f.isDirectory) return f
        }
        return null
    }

    /** 在 SDK 的 build-tools 里挑「版本号最大且有 dexdump」的目录，返回其 dexdump 可执行文件。 */
    fun findDexdump(sdkDir: File): File? {
        val bt = File(sdkDir, "build-tools")
        if (!bt.isDirectory) return null
        fun versionKey(name: String): String =
            name.split('.').joinToString(".") { p -> (p.toIntOrNull() ?: 0).toString().padStart(5, '0') }
        val candidates = bt.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "dexdump").isFile }
            .sortedByDescending { versionKey(it.name) }
        return candidates.firstOrNull()?.let { File(it, "dexdump") }
    }

    /** 扫描 APK 内所有 classes*.dex，返回目标包内每个方法的 registers 数。 */
    fun scanApk(apk: File, dexdump: File, classPrefix: String): List<DexRegistersParser.Finding> {
        val findings = mutableListOf<DexRegistersParser.Finding>()
        ZipFile(apk).use { zip ->
            val dexNames = zip.entries().asSequence()
                .map { it.name }
                .filter { it == "classes.dex" || it.matches(Regex("""classes\d+\.dex""")) }
                .sorted()
                .toList()

            for (dexName in dexNames) {
                val entry = zip.getEntry(dexName) ?: continue
                val tmp = File.createTempFile("dexregisters-", ".dex")
                try {
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    findings += DexRegistersParser.parse(runDexdump(dexdump, tmp), dexName, classPrefix)
                } finally {
                    tmp.delete()
                }
            }
        }
        return findings
    }

    private fun runDexdump(dexdump: File, dex: File): String {
        val out = StringBuilder()
        val proc = ProcessBuilder(dexdump.absolutePath, "-d", dex.absolutePath)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader(Charsets.ISO_8859_1).use { r ->
            r.forEachLine { out.appendLine(it) }
        }
        val code = proc.waitFor()
        check(code == 0) { "dexdump 失败（exit=$code）：${dex.name}" }
        return out.toString()
    }
}
