# [技术设计文档] Kotlin LSP 选型论证

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已决策（用户拍板：维持官方等修复） |
| 日期 | 2026-09-06 |
| 决策人 | 用户（拍板）/ 主对话 |
| 相关 commit | 本文档未提交（待主对话提交） |
| 关联文档 | `docs/decisions/语言服务器六语言选型设计.md`、`~/.dsh/kotlin-lsp/UPGRADE-NOTES.md` |

## 一、需求背景

- **为什么做**：当初选型只记了一行「官方 JetBrains kotlin-lsp 而非 fwcd 社区版」，无对比论证；后经排查发现官方独立 LSP 才以 Alpha 发布（无稳定版）、且 Android 模块导入存在 LSP-1561 ClassCastException（CCE）尚未发布修复——导致 Kotlin 诊断对 KMP Android 项目「假阴性」。需把三个实现充分对比后重新定夺（铁律 1：重大选型须先对比论证）。
- **边界**：只论证 Kotlin 语言服务器选型（官方 JetBrains kotlin-lsp vs fwcd 社区版 vs Rust 实现）；不涉及 TS/JS/Python/Rust/C·C++ 等其他语言（见六语言选型文档）。

## 二、核心需求点拆解

1. **语义级类型诊断**：能报 `val x: Int = "str"` 这类类型不匹配（非仅语法错误）。
2. **KMP + Compose 项目支持**：Android 模块导入、`commonMain`/`androidMain` 跨模块诊断——本项目是 KMP+Compose，此为决定性硬需求。
3. **引用/跳转/hover/补全**：定义跳转、找引用、悬停类型。
4. **stdio LSP 兼容**：可与现有 bridge `lsp.js` 适配（Content-Length 帧、懒启动、诊断广播）。
5. **可维护性**：非 deprecated、维护活跃、许可友好。

## 三、核心指标拆解

| 指标 | 口径 | 达标标准 |
| --- | --- | --- |
| 诊断准确度 | 对含类型错误的 `.kt` 返回带行:列的类型错误 | 能报 `Initializer type mismatch` |
| 启动时间 | 冷启动到首次诊断 | ≤ 数分钟可接受（首次项目导入） |
| 内存占用 | 常驻内存 | ≤ 数 GB（官方需 `-Xmx4g`） |
| 体积 | 解压大小 | 官方约 1.1GB（自包含 JBR），Rust 单二进制 3.5MB |
| 维护活跃 | 最近 release / push 时间 | 官方活跃（push 2026-09-05）；fwcd 停滞 15 个月 |
| 接入成本 | 迁移人日 | 官方已接入=0；fwcd 2–4 人日 |

## 四、预期收益

- **对用户**：Kotlin 代码的类型错误/未解析引用在编辑时实时上屏，减少「编译—报错—再编译」往返。
- **对开发效率**：Agent 编辑 `.kt` 后即时拿到语义诊断，缩短定位问题时间。
- **对稳定性**：选对维护活跃、方向正确的实现，避免 EAP 过期/弃维护导致的诊断静默失效。

## 五、技术方案设计

三种实现的架构本质不同：

- **官方 JetBrains kotlin-lsp**：IntelliJ 内核（JBR 自包含）。诊断是 **pull 模式**（`diagnosticProvider`，客户端发 `textDocument/diagnostic`）；项目导入靠 `buildTools`（gradle/maven）+ workspaceFolders + 项目根。已接入 bridge `lsp.ts`（二进制三级解析、pull 诊断、`intellij/ready-for-test` 重拉、server 请求响应等）。
- **fwcd/kotlin-language-server**：Kotlin 编译器 API（`kotlin-compiler`）。诊断是 **push 模式**（`publishDiagnostics`）；classpath 靠 `DefaultClassPathResolver` 调 Maven/Gradle 或手工 `kls-classpath` 脚本。
- **qdsfdhvh/kotlin-lsp（Rust）**：tree-sitter 语法树 + 启发式类型推断（`infer.rs` 靠「line-scan `val name: Type` 注解」），**无编译器级类型检查**；附 CLI + LSP transport（tower-lsp）。

## 六、多方案优劣势对比

| 维度 | 官方 JetBrains kotlin-lsp | fwcd/kotlin-language-server | qdsfdhvh/kotlin-lsp (Rust) |
| --- | --- | --- | --- |
| 语义类型诊断 | ✅ 最强（IntelliJ 内核） | ✅ 有（kotlin-compiler API） | ❌ 无（tree-sitter 语法级） |
| KMP/Android | ⚠️ 设计支持，Android 导入 CCE 未发布修复 | ❌ KMP 弱（Compose MP #447 等 open） | ❌ 语法级，无跨模块语义 |
| 成熟度/发布 | 2025-05 首个 release，至今 Alpha 无 stable，3496★ 活跃 | 2018 老牌，**已官方 deprecated**，停滞 15 月 | 2026-05 新建，v0.32.3 活跃但 5★ 单人 |
| 性能/体积 | 重（JBR 1.1GB，JVM 启动 30-60s，需 -Xmx4g） | 中（JVM） | 轻（Rust 单二进制 3.5MB，无 JVM） |
| 接入/迁移成本 | 0（已接入 bridge） | 中高 2–4 人日（重写通道 + 自修 KMP） | 低 ~0.5 人日（但诊断降级） |
| 维护风险 | EAP 过期 + 修复发布滞后 | deprecated + 原作者弃用 | 单人、star 5、随时弃坑 |
| License | Apache-2.0 | MIT | MIT |

**逐条事实依据**：

- 官方：仓库 2025-04-28 创建，首个 release `v0.252.17811`（2025-05-27），README 明确 "Alpha state"、无 stable；当前 3496★/87 fork/155 open issues，push 活跃（2026-09-05）。来源：[官方仓库](https://github.com/Kotlin/kotlin-lsp)、[RELEASES.md](https://github.com/Kotlin/kotlin-lsp/blob/main/RELEASES.md)。
- 官方 Android CCE：LSP-1561（`IdeaKotlinResolvedBinaryDependency → IdeaKotlinDependency` 跨插件 classloader cast 失败 → 模型 0K → 全项目假阴性），修复 commit [`4be8d16815ea`](https://github.com/Kotlin/kotlin-lsp/commit/4be8d16815ea)（2026-08-10）已进源码（main + `263.3889` 分支）但**截至 09-06 未发布到任何二进制**（实测 `263.4421.0` javap 反汇编确认旧 cast 仍在）。
- fwcd：README 顶部声明 deprecated（"There is now an official language server, so this project can be considered deprecated"）；最新 release `1.3.13`（2025-01-18）；KMP issue [#447 Compose Multiplatform](https://github.com/fwcd/kotlin-language-server/issues/447)、[#11 非 JVM 后端](https://github.com/fwcd/kotlin-language-server/issues/11)、[#376 Multiplatform unresolved](https://github.com/fwcd/kotlin-language-server/issues/376) 均 open。来源：[fwcd 仓库](https://github.com/fwcd/kotlin-language-server)。
- Rust：README 定位 "fast, no-JVM symbol engine"（tree-sitter 语法级，面向 AI agent 符号检索）；[infer.rs](https://github.com/qdsfdhvh/kotlin-lsp/blob/main/src/resolver/infer.rs) 是启发式「扫描 `: Type` 注解」，非真实类型检查。来源：[qdsfdhvh/kotlin-lsp](https://github.com/qdsfdhvh/kotlin-lsp)。

## 七、最终选择与决策逻辑

**用户最终决策（2026-09-06 拍板）：维持官方 JetBrains kotlin-lsp，等 LSP-1561 修复发布后升级。**

决策逻辑（对照第六节逐条论证）：

- **为什么选官方**：三个候选中唯一「语义类型诊断最强 + KMP/Compose 方向正确 + JetBrains 官方活跃维护」；诊断能力（IntelliJ 编译器级类型诊断/引用/跳转）和 KMP 支持设计上完全匹配本项目的 KMP+Compose 硬需求。已接入 bridge，迁移成本为 0。
- **为什么不选 fwcd**：虽具备语义级诊断且 MIT 可 fork，但已官方 deprecated、停滞 15 个月、原作者弃用，且 Compose Multiplatform 有 open issue（#447）——迁过去大概率还要自修 KMP，是「弃一个待修复的官方 bug 换一个自己维护的 deprecated 项目」，不划算。
- **为什么不选 Rust 版**：tree-sitter 语法级、无类型检查，**不满足「类型错误诊断」硬需求**，无法替代语义 LSP；仅可作「轻量符号检索」补充。
- **决策人**：用户拍板（2026-09-06）。

**已知限制（如实记录）**：官方当前 Android 模块导入 CCE（LSP-1561）未发布修复 → Kotlin 诊断对 KMP Android 项目假阴性（TS/JS/Python/Rust/C·C++ 不受影响）。这是「修复已在源码、只差发布」的临时问题，方向正确。

## 八、对现有架构的影响

- **改动面**：无代码改动——官方 kotlin-lsp 已接入 bridge `lsp.ts`（二进制三级解析 `DSH_KOTLIN_LSP_BIN` → 内置 → PATH、pull 诊断、项目导入、`intellij/ready-for-test` 重拉、server 请求响应等）。当前只涉及二进制升级。
- **风险点**：① EAP 每 30 天过期，需跟踪升级；② Android CCE 假阴性（当前已知限制）。
- **兼容性/回滚**：旧二进制已备份为 `~/.dsh/kotlin-lsp/server.bak-2026-09-06`，可回滚；bridge 侧有死进程快速失败 + 10s deadline，换二进制后新进程自动拉起。

## 九、后续迭代规划

- **roadmap**：① 短期维持官方 `263.4421.0`（已非过期），登记「Android CCE 未修复」为已知限制；② 盯 JetBrains 发布含 `4be8d16815ea` 的构建（大概率 `263.4xxx+` 或 `264.x`）后，按 `~/.dsh/kotlin-lsp/UPGRADE-NOTES.md` 升级二进制并复测 CCE 消失；③ 可选引入 Rust 版作「轻量符号检索」补充（独立于主 LSP 诊断）。
- **已知限制**：LSP-1561 Android 导入 CCE（假阴性）；EAP 过期风险。
- **触发再评估条件**：官方长期（如再一个季度）不发布修复 → 评估 fwcd 兜底（需接受其 deprecated + KMP 自维护成本）；官方若出稳定版 → 优先切稳定版。

## 十、参考来源

- [官方 kotlin-lsp 仓库](https://github.com/Kotlin/kotlin-lsp) / [RELEASES.md](https://github.com/Kotlin/kotlin-lsp/blob/main/RELEASES.md) / [releases](https://github.com/Kotlin/kotlin-lsp/releases)
- [官方 LSP-1561 修复 commit 4be8d16815ea](https://github.com/Kotlin/kotlin-lsp/commit/4be8d16815ea)
- [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server) / [releases](https://github.com/fwcd/kotlin-language-server/releases) / issue [#447](https://github.com/fwcd/kotlin-language-server/issues/447)、[#11](https://github.com/fwcd/kotlin-language-server/issues/11)、[#376](https://github.com/fwcd/kotlin-language-server/issues/376)
- [qdsfdhvh/kotlin-lsp](https://github.com/qdsfdhvh/kotlin-lsp) / [releases](https://github.com/qdsfdhvh/kotlin-lsp/releases) / [infer.rs](https://github.com/qdsfdhvh/kotlin-lsp/blob/main/src/resolver/infer.rs)
- `~/.dsh/kotlin-lsp/UPGRADE-NOTES.md`（升级与验证步骤）
