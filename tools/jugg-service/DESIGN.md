# Jugg 无头编译服务 MVP —— 设计文档

> 状态：已收敛（2026-09 用户拍板）。实现见同目录脚本 + README。

## 1. 目标

一个**不驻留 IDE 的秒级编译服务**：对 dsh-remote-control（KMP + Compose Multiplatform）做 Kotlin
增量编译，单文件改动后数秒内给出「编译通过 / 错误列表」。错误结构化（文件:行:列 + 消息），可被
agent 经 CLI 调用，直接反哺 lint / 修复流程。

MVP 成功标准：改一个 Kotlin 文件 → 服务返回编译结果，耗时个位数秒级（对比 Gradle 现状 23–35s）。

## 2. 已拍板决策（2026-09-06 初拍 + 2026-09-07 三项补充）

| 决策点 | 选择 |
|---|---|
| 进程形态 | **长驻 daemon（既定方向，已实施 commit 578ae8f/4b46387）**——按需 CLI 实测 13s 冷启动不达秒级，2026-09-07 改拍转常驻摊销冷启动 |
| 实现落点 | **dsh 侧 wrapper，不动 Jugg 核心代码**（复用 Jugg `cmd_line` 现有命令；daemon 入口为 Jugg `cmd_line` 新增 `Daemon.kt`） |
| 前置成本 | 接受「首次跑一次完整 Gradle 基线 + 编译 Jugg 仓库成 jar」（需 ideaIC ~3.1GB） |
| 热应用 | 2026-09-07 拍板升级为一等目标（跳过打包+装 APK+华为确认+重启）；调研结论与路线见 `docs/decisions/Jugg秒编服务方案设计.md` 第九节 |
| 基准测量 | 2026-09-07 拍板重测口径：改一行 + 热 daemon + 3 次中位数（compileDebugKotlin 0.52s / assembleDebug 8.06s） |

## 3. 技术链路

```
jugg-init（一次性，建基线，≈23–35s）:
  cmd=buildGradleBase
  → 写 readProjectInfo.gradle.kts init script
  → ./gradlew :composeApp:assembleDebug -I readProjectInfo.gradle
  → 序列化 JuggProjectInfo(模块/sourceDirs/classpath/kotlinPlugins 含 compose-compiler)
  → 备份 classpath / library 依赖

jugg-check（每次，秒级）:
  git status 检测改动 → FileChangesHandler.filter 分类
  → IncrementalCompilerHelper.compile → JuggCompiler → K2JVMCompiler(项目自己的 Kotlin 2.1.0 + Compose 插件)
  → 提取 CompileError → 结构化 JSON
```

复用 Jugg `main` 模块的：增量影响扩散（字节码/元数据，无 PSI/VFS）、Gradle 项目模型序列化、
Kotlin 增量编译链（K2JVMCompiler + Compose 插件 + KMP `-Xmulti-platform -Xcommon-sources`）。

## 4. 关键事实（实现约束）

1. Jugg 是「基线 + 增量」架构，**首次必须跑一次完整 Gradle 构建建基线**，之后才是秒级。
2. Jugg `CompileError.errors` 是 `List<Pair<行号, 原始错误行>>`，原始行含 `path:line:col: error: msg`，
   wrapper 从 stderr 二次解析列号（`KotlinCompilerOutputParser.kt:133` 的 `errorRegex` 只取 path/line，
   列号在原始文本里）。
3. `buildIncrementalApk` 编译失败时 exit code = 255（`CmdLine.main` → `exitProcess(-1)`）；
   `logger.warn` 把错误写到 stderr，格式 `[W] /abs/path/File.kt:42:17: error: msg`（多行错误有 `#soft wrap` 续行）。
4. KMP/CMP 支持已具备（`JuggProjectInfo.kotlinCommonSourceDirs`、`kotlinFragmentSourceDirs`、
   `KotlinCompilerInvoker.handleComposeArgs` 找 `kotlin-compose-compiler`）。**Kotlin 2.1.0 + CMP 1.7.3 +
   AGP 8.7.3 精确组合需实测**，是最大不确定性。

## 5. 接口契约

### CLI

```bash
# 建基线（一次性）
tools/jugg-service/jugg-init <projectDir>

# 编译检查（自动检测改动）
tools/jugg-service/jugg-check <projectDir>

# 编译检查（显式指定文件）
tools/jugg-service/jugg-check <projectDir> --file App.kt
```

### 输出 JSON（stdout）

```json
{
  "ok": false,
  "durationMs": 2340,
  "errors": [
    {"file": "/abs/.../App.kt", "line": 42, "column": 17, "message": "smart cast to ... is impossible", "severity": "error"}
  ]
}
```

- `ok=true` 表示编译通过（errors 为空数组）。
- 非文件级错误：`{"file": null, "line": null, "column": null, "message": "..."}`。
- 无 error 行但编译失败（内部错误/超时）：`{"ok":false, "errors":[], "reason":"<stderr tail>"}`。

## 6. 崩溃 / 超时

- wrapper 对 Jugg 子进程设硬超时（默认 30s，可 `JUGG_TIMEOUT_MS` 覆盖），超时→返回 `{"ok":false,"timeout":true}`。
- Jugg 子进程异常退出→捕获 exit code 与 stderr，返回结构化结果，不崩溃。

## 7. MVP 边界（不做）

- 不做长驻 daemon / HTTP 端点 / fs.watch（后续迭代）。
- 不做多项目矩阵、缓存持久化（基线产物复用 Jugg 默认路径 `build/jugg`）。
- 不做装机 / 部署（本 MVP 只做「编译检查」，装机走现有 huawei-adb 系列 skill）。
- 不改 Jugg 核心源码（只构建并复用其 headless CLI 产物）。

## 8. 下一步迭代

1. 长驻 daemon（摊销 JVM + K2JVMCompiler 初始化）+ `POST /compile` HTTP 端点。
2. 精确影响集（接入 ConstRef/InlineMethod 完整扩散，当前复用 `IncrementalCompilerHelper` 已有能力）。
3. 结构化错误直接接入 lint / 修复闭环（与 detekt P0 门禁对齐）。
