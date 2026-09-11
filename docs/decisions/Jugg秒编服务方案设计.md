# [技术设计文档] Jugg 秒编服务方案设计（无头编译 + 热应用）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已实施（编译 daemon，commit 578ae8f/4b46387）/ 演进中（热应用待拍板） |
| 日期 | 2026-09-10 |
| 决策人 | 用户（多轮拍板，见第七节演进逻辑） |
| 已拍板决定（2026-09-07 三项补充决策） | ① 转长驻 daemon（按需 CLI 13s 未达标）；② 热应用（跳过安装）研究并纳入路线；③ 基准测量方法重测（3 次中位数口径） |
| 相关 commit | Jugg `578ae8f3a9a5ae6683b2fb941c2e18925239fc19`；dsh `4b4638721bddc81d51d9f98250a5b5d4d8984824` |
| 关联文档 | `秒编框架全景调研.md`（选型前置）、`决策台账.md`（#3/#14/#37/#38/#39/O4）、`tools/jugg-service/README.md`（实测口径与调用契约） |

## 一、需求背景

- **为什么做这件事**：本项目是「手机端 Remote Control App」，开发闭环高度依赖 AI Agent 反复执行「改代码 → 编译 → 装机 → 验收」。当前每次改动都走完整 Gradle 构建（Kotlin 2.1.0 / Gradle 8.11.1 / AGP 8.7.3 / KMP + Compose Multiplatform / minSdk 26），从「改完」到「设备可见」要 1 分钟级（Gradle 打包 + 华为装机确认流程 + 重启 App），Agent 迭代被这个耗时卡死。
- **最初诉求**：不常驻打开 IDE，只要一个「秒级编译服务」——常驻后台编译 daemon + CLI，Agent 改完代码调它，几秒内给出「编译通过/错误列表」。
- **目标演进**：经实测（见第三节、第七节），「编译检查」这一目标 Gradle 热 daemon 已秒级，Jugg 无优势；**价值重定向为「热应用」**——跳过「打包→装 APK→华为确认流程→重启 App」，以热替换/增量 dex 把最新代码直接应用到设备，「改一行代码 → 设备上可见」端到端秒级。
- **边界**：MVP 只做「编译 daemon + 热应用通道」最小组合；不做多项目矩阵、不做缓存持久化、不做 UI。Jugg 改动只在本地分支，dsh 侧脚本落 `tools/jugg-service/`。

## 二、核心需求点拆解

1. **无头可 CLI/HTTP 驱动**：不依赖 GUI IDE 窗口即可触发编译/应用（核心诉求）。
2. **秒级编译**：单文件改动后数秒内给出「编译通过/错误列表」，错误结构化（文件:行:列 + 消息）。
3. **Agent 可调用**：CLI 脚本 + 简单 HTTP 端点，JSON 输出，可直接反哺 lint/修复流程。
4. **热应用（一等目标）**：把最新代码以热修复方式直接应用到设备，跳过「打包→装 APK→华为确认→重启 App」。
5. **适配 KMP + Compose Multiplatform**：覆盖本工程 Kotlin 2.1.0 / Compose Multiplatform 1.7.3 / AGP 8.7.3。

## 三、核心指标拆解

### 3.1 编译检查耗时（实测口径）

> **测量口径**：场景统一为「JumpToBottom.kt（105 行）末尾加一行 `private fun __bench(): Int = 1` 再删除」；每项至少 3 次取中位数；「热 daemon」= Gradle daemon + Kotlin 编译 daemon 均已驻留（先跑一次预热），run1 偏大是 configuration-cache 重算/首次增量，run2/run3 为稳态。**阶段明细为代码结构 + 日志推断，未做 `--profile` 精确 per-task 剖面**。

| 场景 | run1 | run2 | run3 | 中位数 | 阶段构成 |
| --- | --- | --- | --- | --- | --- |
| Gradle `compileDebugKotlin`（热 daemon，改一行） | 6110ms | 519ms | 445ms | **519ms** | configuration-cache 命中 + Kotlin 增量编译（Kotlin daemon 热），仅编译 Kotlin，不含 dex/打包 |
| Gradle `assembleDebug`（热 daemon，改一行） | 13690ms | 8058ms | 7504ms | **8058ms** | 上述 + D8 dex 转换 + 资源处理 + APK 打包 + R4 DEX 门禁（`checkDexRegistersDebug`，finalizedBy） |
| Jugg 按需 CLI（改小文件） | — | 13.2s（1 次） | — | 13.2s | JVM 启动 + 加载 64 jar + K2JVMCompiler 初始化 + 基线加载（`gradleProjectInfoFile` + APK 解析 + classpath 扫描）≈13s 冷启动 |
| Jugg 常驻 daemon（改小文件） | 1.75s | 1.05s（JIT 热） | — | ≈1.05s | 摊销冷启动后仅剩：`FileChangesHandler.filter`（毫秒级）+ K2JVMCompiler 增量编译（≈1s）+ 错误提取 |
| Jugg 常驻 daemon（类型错误注入） | — | 0.27s | — | 0.27s | 类型错误在编译早期即失败，耗时更短 |

**关键修正（诚实记录）**：早期报告中的「Gradle 8.9s」是**未充分预热的一次性测量**（Kotlin daemon 未热 + 首次增量），口径有误；充分预热后 `compileDebugKotlin` 热增量稳态仅 **0.52s**，比 Jugg daemon 1.05s 还快。

### 3.2 热应用端到端耗时（重定成功标准）

- **达标口径**：「改一行代码 → 设备上可见」三段计时（编译 + 应用 + 进程恢复），目标秒级。
- **对比基线**：当前全链路 = `assembleDebug` 8s + 华为装机确认（十几秒~几十秒）+ 重启 App ≈ 1 分钟级。

### 3.3 错误结构化

- 输出 JSON：`{"ok":bool,"durationMs":int,"errors":[{"file","line","column","message","severity"}]}`，文件:行:列 + 消息 + 源码插入符，可直接反哺 lint/修复。

## 四、预期收益

- **Agent 迭代提速**：把「改代码 → 设备可见」从 1 分钟级压到秒级，放大 AI coding 反馈闭环速度。
- **不绑 IDE**：摆脱「必须开着 Android Studio」才能秒编/热应用的约束，能力下沉为后台服务，可被任意 Agent/脚本调用。
- **跳过重复装机**：热应用跳过华为确认流程与 App 冷重启，减少等待与设备磨损。

## 五、技术方案设计

### 5.1 Jugg 引擎复用的可行性依据（代码级实证）

对 Jugg 源码（`~/GitHub/jugg`，main 分支）的代码级分析结论：

- **依赖方向**：`idea → main`、`cmd_line → main`；核心引擎 `main` 不依赖 `idea`（IDE 胶水层）。
- **IDE 耦合度**：`main` 的 244 处 `com.intellij.*` import 中 **0 个 PSI、0 个 VFS、0 个 ApplicationManager**；其引用的 12 类 IntelliJ API 恰好全部被 `platform_compat/base_api` 桩覆盖（`Project` 桩仅 10 行 = `getName`/`getBasePath`）。影响扩散用字节码/元数据（`kotlinx-metadata-jvm`、`javaparser`、`classgraph`、自研 `ClassFileParser`），工程模型经 Gradle init 脚本（`readProjectInfo.gradle.kts`）序列化，变更检测用 JGit——**无头天然成立**。
- **已证实的无头路径**：`cmd_line` 模块是进程内 Kotlin CLI，`implementation project(':main')`，可 `buildGradleBase`（建基线）+ `buildIncrementalApk`（增量编译产出 APK），不依赖插件运行时。
- **MCP/协议层**：`McpLocalServer`（JDK `HttpServer`，端口 12320–12329，JSON-RPC）与部署原语（`JuggJvmtiAgentManager`、`DirectOverlayWriter`、`SliceDeployHelper`）全在 `main`，只依赖 `IDeviceAdb`（自研 adb 抽象接口）。

### 5.2 编译 daemon 架构（已实施）

```
jugg-init（一次性）   建基线: ./gradlew :composeApp:assembleDebug -I readProjectInfo.gradle
                      → 序列化工程模型(gradle_project_infos.json) + 备份 classpath/依赖
jugg-daemon start    常驻进程: 复用基线 + JuggCompiler(K2JVMCompiler 实例跨请求复用，warmUp 预热)
jugg-check <file>    秒级编译: HTTP POST /compile → 增量编译 → 结构化错误 JSON
```

关键实现（Jugg `cmd_line` 新增 `Daemon.kt`）：进程常驻，`init()` 一次性加载基线 + 创建并预热 `JuggCompiler`（内含 `K2JVMCompiler`，最重的初始化），此后每次请求只做 `FileChangesHandler.filter → IncrementalCompilerHelper.compile → 提取 CompileError`，摊销按需 CLI 的 ~13s 冷启动。

### 5.3 热应用架构（规划，见第九节）

```
编译段: JuggCompiler 增量编译 → mergeDex 产出增量 dex
应用段: 优先 JuggJvmtiAgentManager(JVMTI 方法级热替换，不重启) + DirectOverlayWriter(dex overlay)
       fallback 增量 dex push(code_cache) + am force-stop/start(结构性改动秒级重启)
进程恢复段: am 重启 / JVMTI RedefineClasses 免重启
```

需把 Jugg `main` 的部署原语编排从 `idea` 模块 headless 化（含 headless `IDeviceAdb` 实现 + 部署编排，评估 ~10-15 人日）。

## 六、多方案优劣势对比（落地形态）

| 维度 | Jugg 无头 daemon（本方案） | Jugg 按需 CLI | Gradle 现状 | Bazel / Buck2 | Compose Hot Reload MCP |
| --- | --- | --- | --- | --- | --- |
| 成熟度/发布节奏 | Jugg 活跃（MIT，push 2026-09-05，80 万+ 次增量编译验证）；daemon 为自研新增 | 同上，CLI 为官方形态 | Gradle 8.11.1 官方主线 | Bazel 25.8k⭐ / Buck2 4.4k⭐ 活跃 | 活跃（1.4k⭐，1.0.0 已发） |
| 编译检查耗时 | 小文件 1.05s（实测） | 13.2s（冷启动） | **compileDebugKotlin 0.52s（实测，已秒级）** | 未测 | 未测（仅 Desktop） |
| 热应用能力 | ✅ 复用 Jugg JVMTI/dex-overlay 原语，headless 化即可 | ⚠️ 官方 CLI 不部署设备 | ❌ 无（需完整打包+装机） | ❌ 无热应用 | ⚠️ HotSwap 仅 Desktop JVM |
| KMP + 真机 | ✅ 官方列明 KMP+CMP，兼容 Kotlin 2.1.0 | ✅ | ✅ 官方主线 | ❌ KMP 弱/无成熟规则集 | ❌ 仅 Desktop target，不落 Android 真机 |
| 接入/迁移成本 | 低（复用 Jugg + 自研 daemon，已 commit） | 低 | 零 | 高（重写构建，人周级） | 中（Kotlin 2.1.20+，本工程 2.1.0 不满足） |
| 维护风险 | 中（daemon 自研 + KMP/CMP compose 插件加载缺陷待修） | 低 | 低 | 高（规则集维护） | 低 |
| License | MIT | MIT | Apache-2.0 | Apache-2.0 | Apache-2.0 |

事实依据（不凭印象）：

- **Jugg 按需 CLI 13.2s 未达标**：实测单进程冷启动（JVM + K2JVMCompiler 初始化 + 基线加载）≈13s，慢于 Gradle 热 daemon，故否决（见第七节）。
- **Gradle compileDebugKotlin 0.52s**：3 次中位数实测（519ms），已秒级——「编译检查」交给 Gradle 即可，Jugg 无优势。
- **Gradle assembleDebug 8.06s**：含 D8/dex/打包/门禁，是「热应用」要跳过的对象，正是 Jugg 价值所在。
- **Bazel/Buck2 KMP 弱**：Bazel 靠社区 `rules_kotlin`，官方 Slack 自认 KMP 不够好；Buck2 prelude 有 `kotlin_library` 但 KMP 无成熟规则集（见 `秒编框架全景调研.md` 第六节）。
- **Compose Hot Reload MCP 不落真机**：JetBrains 官方方案仅 Desktop JVM target，要求 Kotlin 2.1.20+，本工程 2.1.0 不满足；其 MCP server 面向桌面 UI 热重载，非 Android 真机编译/热应用。

## 七、最终选择与决策逻辑（演进逻辑）

**演进脉络（三次决策，均由用户拍板）**：

1. **2026-09-05 九项决策拍板「Jugg 无头编译服务 MVP（2-5 人日）」**，落地方向先定「按需 CLI 优先，长驻 daemon 后置」（选型论证见 `秒编框架全景调研.md`）。
2. **实测发现「按需 CLI 13s 未达标」**：Jugg `cmd_line` 每次冷启动 ≈13s（JVM + K2JVMCompiler + 基线加载），比 Gradle 热 daemon 还慢，且改 `App.kt`（4098 行）需 15.8s——**按需 CLI 形态被实测否决**。→ 用户拍板**转长驻 daemon**（摊销冷启动），实施后小文件达 1.05–1.75s。
3. **进一步实测发现「编译检查 Gradle 已秒级」**：按「改一行 + 热 daemon + 3 次中位数」重测，Gradle `compileDebugKotlin` 稳态 0.52s，比 Jugg daemon 还快——**「编译检查」作为 Jugg 价值的定位被推翻**。→ 用户明确**「热应用（跳过安装）升级为一等目标」**：Jugg 的真正价值是跳过 `assembleDebug`（8s）+ 装机确认 + 重启的 1 分钟级全链路，以热替换/增量 dex 把代码直接应用到设备。

**为什么最终落「Jugg 无头 daemon + 热应用」**：对照第六节——Gradle 无热应用；按需 CLI 慢；Bazel/Buck2 KMP 弱 + 迁移贵；Compose Hot Reload 不落真机。**唯一「KMP + 真机 + 秒级增量 + 自带 JVMTI/dex 热修复原语」的组合是 Jugg 无头化**，其「无独立 headless daemon」缺口已由 MVP 补齐（编译段），「热应用」是下一步复用其部署原语。

## 八、对现有架构的影响

- **改动面**：Jugg 侧新增 `cmd_line/Daemon.kt`（常驻 HTTP 编译服务）+ `CmdLine.kt` 加 `cmd=daemon` 分发 + `KotlinCompilerInvoker.kt` 修 Kotlin 2.x `-Xplugin` 假参数 + `build.gradle` 补 gson；dsh 侧 `tools/jugg-service/` 三脚本 + 文档。**不侵入现有 App / Bridge 主链路**。
- **风险点**：① **Jugg 对 KMP+CMP 的 compose 插件/classpath 加载链缺陷**（基线重建后 `JumpToBottom.kt` 从编译通过回归为 56 个 `unresolved reference 'androidx.compose.*'`，`App.kt` 同）——是当前最大阻塞；② 热应用需 debuggable 包，当前设备装的是非 debuggable 的 `release-in-house`；③ 构建 Jugg 需下载 IntelliJ IDEA Community（ideaIC ~3.1GB，因 `deploy_compat:interface` 用 intellij 插件）。
- **兼容性/回滚**：Jugg 与原生 Gradle 完全并行、独立，MVP 失败随时退回现有 `./gradlew` + huawei-adb 装机流程，无锁死风险。

## 九、后续迭代规划（热应用调研结论 + roadmap）

### 9.1 热应用调研结论（四条技术路线 + 候选对比）

「热应用」= 把编译产物直接应用到真机，跳过「打包 → 装 APK → 华为确认 → 重启 App」。按「改动类型 × 是否重启」分四条路线：

| 路线 | 原理 | 是否重启 | 改动范围 | 无 root 可行性 | 代表实现 |
| --- | --- | --- | --- | --- | --- |
| A. instrumentation 热替换（JVMTI） | JVMTI agent attach 到 app 进程，`RedefineClasses` 方法级重定义 | **否** | 仅方法体 | 需 debuggable + agent | AS Apply Changes、Jugg `JuggJvmtiAgentManager` |
| B. classloader 热替换（patch dex） | 反射替换 `ClassLoader.dexElements`，注入 patch dex | 需重启（或框架 hook） | 方法体/新增类/签名（dex 级） | 是（patch dex 放 app 私有目录） | Tinker、AndFix（停更）、Sophix（商业） |
| C. 增量 dex push + 重启 | `run-as` push 增量 dex 到 `code_cache` → `am force-stop/start` | 是（冷启动重走 ClassLoader） | dex 级 | 是（需 debuggable 才有 `run-as`） | libwebrtc `incremental_install`、Jugg HOT_FIX/INSTALL |
| D. 仅替换资源 | 增量编译资源（aapt2 增量）→ push 覆盖 res → 重启/热刷新 | 是（资源在进程启动时加载） | 仅资源/asset | 是 | Jugg `ResourceOverlayCompiler` |

**逐条结论与事实来源**：

- **A. instrumentation 热替换（JVMTI）**：Android Studio Apply Changes 的原理即编译增量 → JVMTI agent 在 app 进程内 `RedefineClasses`（方法级热替换）。**无独立 CLI**：AGP 无命令行 `applyChanges` 任务，依赖 AS 内置 deployer + agent 注入，不可直接命令行化。Jugg `main` 模块已有 `JuggJvmtiAgentManager`（JVMTI push/attach）+ `DirectOverlayWriter`（dex overlay），是 A 路线 headless 化的现成基座。
- **B. classloader 热替换（patch dex）**：腾讯 Tinker 是成熟代表——把 patch dex 放 app 私有目录，用 `DexClassLoader` 加载后反射替换 `ClassLoader.dexElements`。无需 root，但**需框架接入**（改 build + 接 SDK），违背本工程「不接 SDK」诉求，且 Tinker 体积/维护成本高、AndFix 已停更，故不选 B。
- **C. 增量 dex push + 重启**：libwebrtc `incremental_install` 方案对 debuggable APK 可行——`run-as <包名>` push 增量 dex 到 `code_cache` → `am force-stop/start` 重启让 ART 重新加载。无 root 可行但**需重启**，非真热替换；与 Jugg 的 HOT_FIX（dex 热修）/INSTALL（重装）同族。
- **D. 仅替换资源**：Jugg 有 `ResourceOverlayCompiler`（aapt2 增量链接）产出增量资源，可 push 覆盖；但 Android 资源在进程启动时加载，替换后仍需重启（秒级重启而非分钟级重装）。
- **与 Jugg daemon 的衔接**：编译 daemon（已实施）产出增量产物后，由「热应用通道」按改动类型分发——**方法体改动 → A（JVMTI 热替换，不重启）**；**方法签名/新增类 → C（增量 dex + 秒级重启）**；**资源改动 → D（资源 push + 秒级重启）**。这个通道选择逻辑即 Jugg `idea` 模块的 `JuggDeployer` 编排，需 headless 化。
- **华为 EMUI/Android 12 实测**（HBN-AL00，Android 12/SDK 31，网络 adb）：`am force-stop/start` ✅ 不拦截；`run-as` ❌ 当前设备装的是 `release-in-house`（非 debuggable），`run-as: package not debuggable`，C 路线的 dex push 与 A 路线的 agent attach 均不可用；`adb install`/`install-multiple` 走华为确认流程（huawei-adb skill 处理）。
- **结构性 vs 纯方法体改动策略差异**：纯方法体改动→A（JVMTI，不重启，秒级）；方法签名/新增类/Compose 大改→超出 JVMTI `RedefineClasses` 能力，走 C（增量 dex + 秒级重启），仍远快于分钟级重装。

**推荐**：**A + C + D 组合，复用 Jugg `main` 模块原语**（`JuggJvmtiAgentManager` + `DirectOverlayWriter` + `ResourceOverlayCompiler` + `SliceDeployHelper`），把 `idea` 模块的 `JuggDeployer` 编排 headless 化（评估 ~10-15 人日）。不选 B（Tinker 需接 SDK，违背「不接 SDK」）；Apply Changes 本体无 CLI，但可复用其 JVMTI agent 机制（即 Jugg 的 `jvmti_agent`）。

### 9.2 迭代规划（roadmap）

1. **修复 Jugg KMP/CMP compose 插件加载链缺陷**（当前阻塞）：`isNeedCompileCompose` 依赖 `androidExt.buildFeatures.compose`，KMP 工程为 false → compose 插件不加载 → `unresolved reference`。改法：对 `org.jetbrains.compose` 插件工程也置 `isUseCompose=true`（或按 `kotlin-compose-compiler-plugin` 在 classpath 判定）。
2. **热应用 headless 化（核心投入 ~10-15 人日）**：实现 headless `IDeviceAdb`（adb CLI）+ 部署编排（复用 `JuggJvmtiAgentManager` + `DirectOverlayWriter`），先跑通「方法体改动 → JVMTI 热替换 → 设备可见」秒级链路。
3. **前置决策（待用户拍板，见决策台账 O4）**：① 装机测试包从 `release-in-house` 换回 debug（可调试，12.6MB），或给 `release-in-house` 加 debuggable（R8 混淆对 JVMTI 热替换有风险需评估）；② 是否投入 ~10-15 人日实施热应用 headless 化。
4. **结构性改动 fallback**：增量 dex push + `am force-stop/start` 秒级重启，覆盖方法签名/新增类场景。

## 十、参考来源

- Jugg 仓库与 Wiki：https://github.com/tencentmusic/jugg 、https://tencentmusic.github.io/jugg/
- Jugg 源码（本地 `~/GitHub/jugg`，main 分支）：`main/build.gradle`、`idea/build.gradle`、`cmd_line/build.gradle`、`platform_compat/base_api`、`main/.../compiler/source/kotlin/KotlinCompilerInvoker.kt`、`main/.../ai/mcp/McpLocalServer.kt`、`main/.../deploy/`（`IDeviceAdb.kt`/`JuggJvmtiAgentManager.kt`/`DirectOverlayWriter.kt`）
- 选型前置调研：`docs/decisions/秒编框架全景调研.md`（Bazel/Buck2/Compose Hot Reload/Freeline/ByteX 等候选的机制与维护状态，GitHub API 实测核实）
- 决策台账：`docs/decisions/决策台账.md`（#3/#14/#37/#38/#39/O4 + Jugg 域价值修正记录）
- 实测与调用契约：`tools/jugg-service/README.md`（`jugg-init`/`jugg-daemon`/`jugg-check` 脚本 + 耗时表）
- Apply Changes 原理：https://developer.android.com/studio/run#apply-changes 、https://developer.cloud.tencent.com/article/1775804
- 增量 dex 安装方案（libwebrtc incremental_install）：https://searchfox.org/mozilla-esr102/source/third_party/libwebrtc/build/android/incremental_install/README.md
- 华为 HarmonyOS adb 限制讨论：https://github.com/RikkaApps/Shizuku/issues/302
- adb install-multiple 讨论：https://stackoverflow.com/questions/67263215/adb-install-multi-package-wanting-to-install-apks-with-separate-packages-as-f
- Tinker（classloader 热替换代表）：https://github.com/Tencent/tinker
- AndFix（已停更）：https://github.com/alibaba/AndFix

> 说明：本文实测数据均来自本机（16GB M2 Air，Kotlin 2.1.0 / Gradle 8.11.1 / AGP 8.7.3 / KMP + Compose Multiplatform 1.7.3）对 dsh-remote-control 工程的真机实测；「热 daemon」与「改一行」口径见第三节 3.1。阶段明细为代码结构推断，未做 `--profile` 精确 per-task 剖面，后续如需精确归因可补 `./gradlew --profile`。
