# Jugg 无头编译 + 热应用服务（jugg-service）

一个**不驻留 IDE 的秒级编译 + 热应用服务**：复用腾讯音乐 Jugg 的增量编译引擎
（`main` 模块，0 PSI/VFS 依赖，字节码/元数据影响分析），对 dsh-remote-control
（KMP + Compose Multiplatform）做单文件改动后的秒级「编译通过 / 错误列表」检查，
并可把改动**热应用到真机**（绕开打包 + 装 APK + 华为确认 + 重启）。
错误结构化（文件:行:列 + 消息），可直接反哺 lint / 修复流程。

## 架构

```
jugg-init（一次性）  建立基线: 跑一次 assembleDebug + 序列化工程模型（≈3 分钟）
jugg-daemon start    常驻进程: 摊销 JVM + K2JVMCompiler 初始化 + 基线加载（一次性 ≈13s）
jugg-check <file>    秒级编译检查: HTTP POST /compile → 增量编译 → 结构化错误 JSON
jugg-apply <file>    秒级热应用: HTTP POST /apply → 增量编译 + mergeDex → 写 code_cache/.overlay → am 重启
```

关键点：**按需 CLI 每次冷启动 ≈13s，比 Gradle 热 daemon（compileDebugKotlin 0.52s）还慢**；
转常驻 daemon 后，13s 初始化只发生一次。「编译检查」Gradle 已秒级，**Jugg 的价值在热应用**——跳过
`assembleDebug`（8s）+ 装机确认 + 重启的 1 分钟级全链路，以 overlay dex 把代码直接应用到设备。

## 前置

1. **Jugg 产物**（一次性构建，需 JDK 17 + Android SDK + ≈1.5GB 磁盘下载 IntelliJ IDEA 依赖）：
   ```bash
   cd ~/GitHub/jugg && ./gradlew :cmd_line:installDist
   ```
   产物在 `~/GitHub/jugg/cmd_line/build/install/cmd_line`（可用 `JUGG_HOME` 覆盖）。

2. **JDK 17 + ANDROID_HOME**：脚本默认用
   `~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home`
   与 `~/Library/Android/sdk`，可用环境变量覆盖。

## 快速开始

```bash
cd tools/jugg-service

# 1. 建基线（首次，≈3 分钟；改依赖/构建脚本后需重跑）
./jugg-init /Users/xieshaoze/Code/dsh-remote-control

# 2. 启动常驻 daemon（首次初始化 ≈13s）
./jugg-daemon /Users/xieshaoze/Code/dsh-remote-control start

# 3. 秒级编译检查（自动检测 git 改动的 .kt）
./jugg-check /Users/xieshaoze/Code/dsh-remote-control
# 或显式指定文件
./jugg-check /Users/xieshaoze/Code/dsh-remote-control App.kt

# 4. 秒级热应用（改一行 → 真机可见；需 debug 包 + 设备解锁）
./jugg-apply /Users/xieshaoze/Code/dsh-remote-control
# 或显式指定文件 + 设备 + 包名
JUGG_DEVICE=100.71.236.18:5555 JUGG_PACKAGE=com.daniel.dshremote.debug \
  ./jugg-apply /Users/xieshaoze/Code/dsh-remote-control App.kt
```

## 调用方式（agent / lint 体系）

- **CLI**：`jugg-check <projectDir> [file...]`（编译检查）、`jugg-apply <projectDir> [file...]`（热应用），stdout 一行 JSON。
- **HTTP**（daemon 内）：
  - `GET  /health` → `{"status":"ready","baseline":"..."}`
  - `POST /compile`，body `{"files":["/abs/App.kt"]}` → 编译结果 JSON。
  - `POST /apply`，body `{"files":["/abs/App.kt"],"deviceSerial":"...","packageName":"com.daniel.dshremote.debug"}` → 三段计时 JSON。

## 输出契约

```json
// 编译通过
{"ok":true,"durationMs":1234,"errors":[]}

// 编译失败（结构化错误，可直接反哺 lint）
{"ok":false,"durationMs":2340,"errors":[
  {"file":"/abs/.../App.kt","line":3482,"column":31,
   "message":"initializer type mismatch: expected 'kotlin.Int', actual 'kotlin.String'.","severity":"error"}
]}

// 非文件级错误（file/line/column 为 null）
{"ok":false,"durationMs":0,"errors":[{"file":null,"line":null,"column":null,"message":"...","severity":"error"}]}
```

## 实测耗时对比（dsh-remote-control，KMP+CMP+Kotlin 2.1.0）

| 场景 | 耗时 |
|---|---|
| Gradle 热 daemon `compileDebugKotlin`（改一行，3 次中位数） | 0.52s |
| Gradle 热 daemon `assembleDebug`（改一行，3 次中位数） | 8.06s |
| Jugg 按需 CLI（改小文件，含冷启动） | 13.2s |
| Jugg 常驻 daemon `/compile`（改小文件） | 1.0–1.6s |
| **Jugg 常驻 daemon `/apply` 热应用（改 MessageBubbles 一行，真机）** | **4.1s**（compile 1.5s + mergeDex 0.02s + overlay 写 2.0s + 重启 0.6s） |
| Jugg 常驻 daemon `/apply` 热应用（首次，改 LandingScreen） | 8.2s（compile 5.0s + mergeDex 0.4s + overlay 写 2.0s + 重启 0.8s） |
| **全链路对比（assembleDebug + 华为装机确认 + 重启）** | **≈40–60s**（打包 8s + 华为确认 20–30s + 重启） |
| Jugg 建基线（一次性） | 3m11s |

> 热应用端到端结论（2026-09-11 真机实测）：`jugg-apply` 把「改一行 → 设备可见」压到 **4–8 秒**，
> 比全链路（≈40–60s）快 **5–15 倍**。overlay dex 已确认写入 `code_cache/.overlay/base.apk/classes.dex`
> 且含改动类（`MessageBubblesKt`）+ 新文案 UTF-8 字节，冷启动正常。

## 已知限制（诚实标注）

- **compose 假错误已修复**：Kotlin 2.x 把整个编译器 classpath（stdlib/reflect/daemon/trove4j 等）混进
  `kotlinPlugins` 并全部当 `-Xplugin` 传，导致 compose 插件加载被干扰（表现为 `unresolved reference 'androidx.compose.*'`）。
  已在 Jugg `KotlinCompilerInvoker` 修复（只对真插件传 `-Xplugin`），`App.kt`（4098 行重度 Compose）编译 `ok:True errors:0`。
- **热应用需 debug 包（debuggable）**：overlay dex 写入走 `run-as`，仅 debuggable 包可用；`release-in-house`（非 debuggable）不可用。
- 基线过期需重跑 `jugg-init`：基线建立后若源码新增类/依赖（如新 commit），增量编译会因旧 classpath 缺符号报假错误，
  重跑 `jugg-init` 即可。

## MVP 边界（当前不做的）

- 不做装机/部署（本服务只做编译检查，装机走 huawei-adb 系列 skill）。
- 不做文件监听自动触发（`jugg-check` 由调用方触发，或外层 watch 循环）。
- 不做多项目矩阵 / 缓存持久化（基线复用 Jugg 默认 `build/jugg`）。
- 不做 daemon 自愈/进程监控（由外层 `jugg-daemon start/stop` 管理，日志 `/tmp/jugg-daemon.log`）。

## 下一步迭代建议

1. 拆 `App.kt`（3481 行）与 `BridgeClient.kt`（1451 行）为多文件 → 改 App 相关逻辑才秒级。
2. daemon 加 fs.watch 自动触发 + 把错误直接接到 detekt/修复闭环。
3. 接入 DSH 服务端作为「秒级编译检查」后端（与现有 Kotlin LSP pull 诊断互补：LSP 管语义提示，Jugg 管编译确定性）。

## 两仓库落点

- Jugg 侧（`~/GitHub/jugg`）：`cmd_line` 新增 `Daemon.kt`（常驻无头编译服务）+ `CmdLine.kt` 加 `cmd=daemon` 分发。
- dsh 侧（本目录）：`jugg-init` / `jugg-daemon` / `jugg-check` 脚本 + `DESIGN.md` + 本文档。
