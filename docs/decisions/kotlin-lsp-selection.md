# Kotlin LSP 三大实现对比与选型论证

> 状态：调研完成，待用户拍板。生成日期 2026-09-06。
> 背景：当初选型只记了一行「官方 JetBrains kotlin-lsp 而非 fwcd 社区版」，无对比论证；现发现官方独立 LSP 为 Alpha、Android 模块导入有 LSP-1561 CCE 未修复，需充分对比后重新定夺。

## TL;DR（结论摘要）

三个候选没有一个「同时满足语义诊断 + KMP/Android 稳定 + 维护健康」：

| 维度 | 官方 JetBrains kotlin-lsp | fwcd/kotlin-language-server | qdsfdhvh/kotlin-lsp (Rust) |
|---|---|---|---|
| 语义类型诊断 | ✅ 最强（IntelliJ 内核） | ✅ 有（kotlin-compiler API） | ❌ 无（tree-sitter 语法级） |
| KMP/Android 支持 | ⚠️ 设计支持，Android 导入 CCE 未发布修复 | ❌ KMP 弱（Compose MP 有 open issue） | ❌ 语法级，无跨模块语义 |
| 成熟度 | 3 个月起持续 Alpha | 7 年老牌，但已官方 deprecated | 3 个月，单人 |
| 维护 | ✅ 活跃 | ❌ 停滞 15 个月 | ⚠️ 活跃但 star 5 |
| 体积/性能 | 重（JBR 1.1GB，JVM） | 中（JVM） | 轻（Rust 单二进制 3.5MB） |
| License | Apache-2.0 | MIT | MIT |

**推荐排序**：① 官方 kotlin-lsp（保持，等 LSP-1561 修复发布）→ ② fwcd（兜底，仅当官方长期不修复）→ ③ Rust 版（不推荐作主 LSP，无类型诊断）。

核心矛盾：我们的硬需求是「KMP + Compose 项目的**类型错误诊断 + 跨模块诊断 + 引用/跳转**」，只有官方和 fwcd 具备**语义级**诊断；但官方当前被 Android CCE 卡住（待发布修复）、fwcd 已弃维护且 KMP 支持差。Rust 版虽然最快最轻，但**没有类型检查**，无法替代。

---

## 1. 三候选现状

### 1.1 官方 JetBrains kotlin-lsp（github.com/Kotlin/kotlin-lsp）

- 仓库创建 2025-04-28，Kotlin，Apache-2.0。当前 **3496★ / 87 fork / 155 open issues**，push 活跃（2026-09-05）。
- 首个公开 release `v0.252.17811`（2025-05-27）；README 明确 **"The project is currently in the Alpha state"**，**无 stable 版**。
- 发布线：`262.x`（2026.2，到 07-27 的 `262.9593.0`）与 `263.x`（2026.3 EAP，08-03 的 `263.2689.0`、09-02 的 `263.4421.0`）。EAP 构建带 **30 天过期**（实测 `263.2689.0` 启动即自毙「This build of kotlin-server has expired」）。
- 来源：[官方仓库](https://github.com/Kotlin/kotlin-lsp)、[RELEASES.md](https://github.com/Kotlin/kotlin-lsp/blob/main/RELEASES.md)、[GitHub releases](https://github.com/Kotlin/kotlin-lsp/releases)。

### 1.2 fwcd/kotlin-language-server

- 仓库创建 2018-05-28（社区老牌 7 年），Kotlin，MIT。**2042★ / 252 fork / 227 open issues**。
- **README 顶部已官方声明 deprecated**：「There is now an official language server, so this project can be considered deprecated.」
- 最新 release `1.3.13`（2025-01-18）；最近 push 2025-06-02，**已停滞约 15 个月**。
- 基于 Kotlin 编译器 API（`kotlin-compiler`）做语义级诊断/补全/hover/跳转；classpath 通过 `DefaultClassPathResolver` 调 Maven/Gradle 解析。
- 来源：[fwcd 仓库](https://github.com/fwcd/kotlin-language-server)、[releases](https://github.com/fwcd/kotlin-language-server/releases)。

### 1.3 qdsfdhvh/kotlin-lsp（Rust）

- 仓库创建 2026-05-17，Rust，MIT。**仅 5★ / 1 fork / 0 open issues**，单人项目。
- 最新 release `v0.32.3`（2026-08-25），更新频繁（活跃但单人）。
- 定位是 **"fast, no-JVM symbol engine"**：基于 [tree-sitter](https://tree-sitter.github.io/)（**语法树**，非 Kotlin 编译器），主打 **instant startup / low memory / zero runtime**；面向 AI agent 的 `find`/`refs`/`hover`/call-graph 符号检索，附带 CLI + LSP transport。
- 类型解析是**启发式**：`src/resolver/infer.rs` 靠「line-scan `val name: Type` 注解」推断 receiver 类型，**不是真正的类型检查**。
- 来源：[qdsfdhvh/kotlin-lsp](https://github.com/qdsfdhvh/kotlin-lsp)、[releases](https://github.com/qdsfdhvh/kotlin-lsp/releases)、[infer.rs](https://github.com/qdsfdhvh/kotlin-lsp/blob/main/src/resolver/infer.rs)。

---

## 2. 对比维度

### 2.1 成熟度与发布节奏

- **官方**：发布历史最短（2025-05 首个 release），但由 JetBrains 官方维护、资源充足、节奏稳定（每周/双周），不过至今仍 Alpha、无 stable，且有 EAP 过期机制。
- **fwcd**：历史最久（2018 起），曾是 VSCode 生态默认 Kotlin LSP，功能完整；但已**官方弃维护**（README 声明 deprecated + 原作者 georgewfraser 在 README 中明说「不再使用 Kotlin」），15 个月无提交。
- **Rust**：最年轻（2026-05），star 仅 5，单人业余项目，无任何社区生态/测试背书。

### 2.2 Android/KMP 项目支持（硬需求）

我们项目是 **KMP + Compose Multiplatform（含 Android 模块 composeApp）**，这是选型的决定性维度：

- **官方**：设计上通过 Gradle/Maven 导入支持 KMP；但 **Android 模块导入有 LSP-1561 CCE**（`IdeaKotlinResolvedBinaryDependency → IdeaKotlinDependency` 跨插件 classloader cast 失败 → 模型 0K → 全项目假阴性）。修复 commit [`4be8d16815ea`](https://github.com/Kotlin/kotlin-lsp/commit/4be8d16815ea)（2026-08-10）已进源码（main + `263.3889` 分支），**但截至 09-06 未发布到任何二进制**（实测：最新 `263.4421.0` 用 javap 反汇编确认旧 cast 仍在，运行日志复现 `SourceSetDependencyResolver.kt:376` CCE）。这是当前唯一硬伤，但方向正确、修复已在路上。
- **fwcd**：KMP 支持**弱**——多个相关 issue 至今 open：[#447 Compose Multiplatform 问题](https://github.com/fwcd/kotlin-language-server/issues/447)、[#11 非 JVM 后端（Native/JS/Wasm）](https://github.com/fwcd/kotlin-language-server/issues/11)、[#376 Multiplatform 性能/unresolved references](https://github.com/fwcd/kotlin-language-server/issues/376)。其 classpath 解析面向单 JVM 模块，对 `commonMain`/`androidMain` 的跨模块诊断不健全。
- **Rust**：tree-sitter 是**语法级**，能解析 `.kt` 文件（含 expect/actual 的语法），但**无编译器级跨模块语义**——无法诊断跨模块类型错误、无法解析 Gradle/KMP 依赖图。

### 2.3 诊断能力与准确度

- **官方**：最强。IntelliJ 内核，编译器级**类型错误**、引用、定义/实现跳转、补全、hover、rename、inlay hint 等全量 LSP 能力；诊断是 **pull 模式**（`diagnosticProvider`）。实测（对带类型错误的 `.kt`）：能返回 `Initializer type mismatch: expected 'Int', actual 'String'`（见下方「实测佐证」）。
- **fwcd**：较强。Kotlin 编译器 API，能做**真实类型检查**与诊断（这是它优于 Rust 版的关键）；但绑定 Kotlin 版本滞后、KMP 下易 unresolved。
- **Rust**：**无类型诊断**。tree-sitter 只做语法/结构，`infer.rs` 是「扫描 `: Type` 注解」的启发式推断，无法判定 `val x: Int = "str"` 这类类型不匹配。其 `tool inspect`（"file diagnostics"）只能报语法/结构级问题，不满足「类型错误诊断」硬需求。

### 2.4 性能

- **官方**：最重。自包含 JBR（解压约 1.1GB），JVM 启动 30–60s、首次项目导入数分钟、内存 GB 级（实测默认 2GB 堆会 OOM，需 `-Xmx4g`）。但语义最准，属「重而全」。
- **fwcd**：中。仍需 JVM，但比 IntelliJ 轻（单一 kotlin-compiler，无完整 IDE 平台）。
- **Rust**：最快。Rust 单二进制 **3.5MB**、无 JVM、instant startup、low memory——这正是它「Fast, low-memory」卖点，但以放弃语义诊断为代价。

### 2.5 接入成本（与现有 bridge lsp.js 的适配）

- **官方**：**已接入**（前序任务完成）。bridge `lsp.ts` 已实现：二进制三级解析（`DSH_KOTLIN_LSP_BIN` → 内置 → PATH）、pull 诊断（`textDocument/diagnostic`）、项目导入（`buildTools` gradle/maven + 项目根 realpath）、`intellij/ready-for-test` 重拉、server 请求响应（`workspace/configuration` 回数组等）、`initialized` 通知 + `didChange` version 修复。**迁移成本为 0**；代价是 JBR 体积大（约 1.1GB）。
- **fwcd**：需重写 kotlin 通道。fwcd 是 **push 诊断**（`publishDiagnostics`，非 pull）、无 `buildTools`/`diagnosticProvider` 概念、classpath 靠 `DefaultClassPathResolver`（Maven/Gradle）或手工 `kls-classpath` 脚本；KMP 还要额外配置。需配 JVM 运行。**迁移成本中高（估算 2–4 人日）**，且迁完大概率还要修 fwcd 的 KMP bug。
- **Rust**：单二进制、stdio LSP（tower-lsp）+ CLI，接入最省（~0.5 人日），但**诊断能力不满足硬需求**，接了也解决不了类型错误诊断。

### 2.6 维护活跃度与风险

- **官方**：JetBrains 官方、活跃，风险主要是 **EAP 过期**（每 30 天）与 **修复发布滞后**（当前 Android CCE 修复已在源码但未发二进制）。Apache-2.0 无许可风险。
- **fwcd**：**已官方 deprecated**、原作者弃用、15 个月无提交，单点维护风险极高；虽 MIT 可 fork 自维护，但需自行跟进 Kotlin 版本与 KMP。这是最大的长期风险。
- **Rust**：单人新项目、star 5、无生态，随时可能弃坑；MIT。

### 2.7 License

- 官方：Apache-2.0（无 copyleft，商用友好）。
- fwcd：MIT。
- Rust：MIT。

---

## 3. 实测佐证（对 dsh-remote-control 仓库）

- **官方 kotlin-lsp**（前序任务已实测）：对 `composeApp/.../App.kt` 通过 bridge `lsp.query` 拉诊断，以及独立 `textDocument/diagnostic` 请求——**语义级类型诊断已验证**（对含 `val answer: Int = "类型错误演示"` 的文件返回 `Initializer type mismatch: expected 'Int', actual 'String'`，行:列准确）。但同时实测出 Android 模块导入 CCE：`Workspace model cache saved (0 K)`、`populateDependenciesForAndroidModule:376` 抛 CCE（修复未发布到二进制）。
- **fwcd / Rust**：**未实际试跑**。原因：两者预编译产物均托管于 `github.com/releases/download`，本机网络约束 `github.com` 主站不可达（连接超时），`api.github.com`/`raw.githubusercontent.com`/JetBrains CDN 可达但 release 二进制走 github.com 重定向，故无法下载；且磁盘紧张（调研前仅 ~11GB 自由）。两者的能力边界已由 README/issue/infer.rs 源码充分佐证（fwcd 语义级但 deprecated、Rust 语法级无类型检查）。

---

## 4. 推荐排序与理由

1. **官方 JetBrains kotlin-lsp（保持现状，等 LSP-1561 修复发布）**
   - 唯一在「语义类型诊断 + KMP/Compose 支持 + 活跃官方维护」上**方向正确**的候选。当前 Android CCE 是「修复已在源码、只差发布」的**临时问题**，不是选型错误；一旦含 `4be8d16815ea` 的构建发布（大概率 `263.4xxx+` 或 `264.x`），按既有升级流程换二进制即可恢复 KMP Android 诊断。
   - 已接入 bridge，迁移成本 0。

2. **fwcd/kotlin-language-server（兜底，仅当官方长期不发布修复时考虑）**
   - 备选价值在于「语义级诊断 + MIT 可 fork 自维护」。但其官方已 deprecated、15 个月无维护、Compose Multiplatform 有 open issue（#447），迁过去大概率还要自己修 KMP——不是省事的选择。

3. **qdsfdhvh/kotlin-lsp（不推荐作主 LSP）**
   - 无类型检查，**不满足「类型错误诊断」硬需求**，无法替代语义 LSP。仅可作「轻量符号检索」的**补充工具**（若 bridge 将来需要快速 refs/hover/call-graph 且可接受无类型信息）。

## 5. 迁移成本估算

| 路径 | 成本 | 说明 |
|---|---|---|
| 保持官方（当前） | 0（已接入） | 等修复发布后按 `~/.dsh/kotlin-lsp/UPGRADE-NOTES.md` 的升级步骤换二进制，约 30 分钟 |
| 官方 → fwcd | **2–4 人日** | 下载/部署 jar + 配 JVM + 重写 bridge kotlin 通道（push 诊断、classpath 解析、无 buildTools）+ KMP 自定义 classpath；且需自修 Compose MP open issue |
| 官方 → Rust | 0.5 人日（仅接入）但**诊断降级** | 单二进制接入快，但无类型诊断，不能作为类型诊断主 LSP |

## 6. 建议下一步（待用户拍板）

- 短期：维持官方 `263.4421.0`（已非过期），把「Android 模块导入 CCE 未修复」登记为已知限制；盯 JetBrains 发布含 `4be8d16815ea` 的构建后立即升级。
- 若用户认为「等不起官方修复」：再评估 fwcd 兜底方案（需接受其 deprecated + KMP 自维护成本）。
- 可选：引入 Rust 版作「轻量符号检索」补充（独立于主 LSP 诊断），不替代。
