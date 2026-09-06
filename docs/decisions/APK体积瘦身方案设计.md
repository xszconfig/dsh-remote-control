# [技术设计文档] APK 体积瘦身方案

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已实施 |
| 日期 | 2026-09-06 |
| 决策人 | 用户（拍板） |
| 相关 commit | `df750a9`（feat: 两 R8 变体 + keep 规则）、`bb19718`（docs: 状态台账与决策记录） |
| 关联文档 | `docs/status/pending-items.md`（状态台账）、`docs/coding-rules/verifyerror-deep-dive.md`（R4 DEX 门禁）、`docs/bugs/`（2026-09-05 VerifyError 事故） |

## 一、需求背景

- **痛点**：远程装机走 Tailscale（RTT ~375ms），12.6MB 的 debug APK 传输慢且易超时，「编译安装 → 真机自测」循环被装机环节拖慢。
- **目标**：把装机用包瘦身 50%（目标 ≤6.3MB），提速自测循环。
- **边界**：只做体积瘦身与装机提速，不改业务逻辑、不改协议、不引入动态下发；iOS 目标不在本次范围。

## 二、核心需求点拆解

1. 诊断 debug APK 12.6MB 的体积构成，定位大头（预期是未 minify 的 dex）。
2. 给出能达成 50% 目标的可行手段组合，并按性价比排序。
3. 由用户拍板 4 个决策点（R8 开启方式 / ABI / 达标口径 / 附带成本）。
4. 落地两个 release 变体并验证：构建、量体积、模拟器冒烟、关键链路回归。
5. 记录 keep 规则与后续迭代项。

## 三、核心指标拆解

| 指标 | 达标口径 | 测量方法 |
| --- | --- | --- |
| 装机包体积 | `release-in-house` ≤ 6,300,000 字节（6.3MB） | `stat -f %z` 量 APK 字节数 |
| 启动存活 | 冷启动存活 ≥5s 且 crash buffer 无本包 FATAL | `startup-smoke-test` skill（P00 硬断言） |
| 关键链路回归 | 连接/hello、日志页 tab、问题弹层可正常渲染 | uiautomator dump 树 + 服务端投影（/remote/connected、/remote/phone-logs） |

## 四、预期收益

- **装机提速**：传输字节从 12.6MB → 2.16MB（−82.9%），在高 RTT 链路下传输耗时与超时风险同比例下降。
- **保持可调试**：debug 包不 minify 保留，日常 IDE 调试不受影响；装机自测用瘦身后的 release-in-house。
- **商店可复用**：release-store 直接复用同一套 R8 + keep 规则，线上包同样享受体积收益。

## 五、技术方案设计

### 5.1 体积构成（实测，debug 12,617,453 字节）

| 构成 | 压缩后大小 | 占 APK | 说明 |
| --- | --- | --- | --- |
| **classes*.dex（6 个）** | **11,751,495** | **93.1%** | 全为库/框架代码，未 R8 |
| resources.arsc | 436,876 | 3.5% | 资源索引表 |
| res/ | 36,589 | 0.3% | 全是依赖自带资源，app 自身 0 资源 |
| lib/*.so（4 ABI） | 37,392 | 0.3% | 仅 `libandroidx.graphics.path.so` ×4 |
| okhttp publicsuffixes.gz | 41,394 | 0.3% | okhttp 域名后缀表 |
| kotlin builtins | 10,114 | 0.1% | kotlin 元数据 |
| 其它（manifest/probes/zip 开销） | ~345,000 | ~2.4% | 含 zip 对齐 padding |

**Dex 内部**（apkanalyzer 累计口径，dex 定义代码合计 12,619,860 字节）：

| 顶层包 | 字节 | 占 dex | 归属 |
| --- | --- | --- | --- |
| androidx | 7,875,934 | 62.4% | Compose 6.56M + androidx core/collection/lifecycle 等 1.32M |
| com | 1,117,774 | 8.9% | app 自身 628K + zxing 325K + mikepenz markdown 114K + journeyapps 50K |
| kotlinx | 1,026,782 | 8.1% | coroutines 482K + serialization 239K + datetime 211K + io 81K |
| kotlin | 960,516 | 7.6% | kotlin stdlib |
| io | 741,768 | 5.9% | io.ktor |
| org | 331,789 | 2.6% | org.intellij.markdown 216K + slf4j 39K |
| okhttp3 / okio | 496,454 | 3.9% | 网络栈 |
| android + 其它 | 47,121 | 0.4% | 平台桥 + 默认包残留 |

### 5.2 归因结论

- **93% 是 dex，其中 99.98% 是第三方库代码且未 R8**（debug 默认不 minify，release 也 `isMinifyEnabled=false`）。
- **资源几乎为 0**：无图片、无字体、无 assets——「纯代码」APK，资源瘦身/动态加载均不适用。
- app 自身仅 628K，其中 protocol 包（78 个 `@Serializable` 的序列化器）占 193K（31%）。
- 因此「瘦身 50%」的核心杠杆只有一个：**对第三方库代码开 R8 minify + resource shrink**。

### 5.3 落地配置（composeApp/build.gradle.kts）

```kotlin
buildTypes {
    getByName("release") { isMinifyEnabled = false }          // 保留原样
    create("release-in-house") {                              // 内部测试装机包
        isMinifyEnabled = true
        isShrinkResources = true
        signingConfig = signingConfigs.getByName("debug")     // 可直接 adb install
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
    }
    create("release-store") {                                 // 线上商店包
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        ndk { abiFilters += "arm64-v8a" }                     // 签名由 Play App Signing 发布时配置
    }
}
```

debug 保持现状（不 minify、全 ABI），用于日常调试与 x86_64 模拟器。

## 六、多方案优劣势对比

按 4 个决策点分别对比（无 license 维度，全部为开源 Apache-2.0 / MIT 依赖，无需选型）。

### 6.1 决策点 1：R8 开启方式

| 维度 | A：新建 lean/release-in-house buildType | B：直接给 debug 开 minify | C：给 release 开 minify |
| --- | --- | --- | --- |
| 体积收益 | 12.6MB → ~5.5~6.5MB | 同 A | 同 A |
| 调试可读性 | 保留纯 debug，可断点/可读堆栈 | 丧失（变量混淆、无行号） | 不可 debuggable，最差 |
| 装机便利 | debug 签名，可直接 adb install | 同 | 需额外签名配置 |
| 接入成本 | 低（一段 buildTypes 配置） | 最低 | 低 |
| 维护风险 | 低，与 release 语义隔离 | 日常调试体验长期受损 | 污染 release 语义 |

结论：**选 A**。debug 保留可调试，装机自测用独立变体，两全。

### 6.2 决策点 2：ABI 裁剪

| 维度 | A：仅 arm64-v8a | B：保留全 ABI |
| --- | --- | --- |
| 体积收益 | 省 ~28K（3 个 .so，可忽略） | 0 |
| 兼容性 | 华为真机/arm64 模拟器可用；放弃 x86/x86_64 模拟器与 32 位旧设备 | 全兼容 |
| 风险 | 低（debug 包仍保留全 ABI，模拟器调试不受影响） | 无 |

结论：**选 A**（华为真机 arm64，收益虽小但零风险，随变体一起做）。

### 6.3 决策点 3：达标口径

| 维度 | A：以 release-in-house ≤6.3MB 为准 | B：debug 包本身也要 ≤6.3MB |
| --- | --- | --- |
| 装机体验 | 自测装机用 ~2MB 瘦身包，快 | 同 |
| 调试体验 | 保留可调试 debug | 牺牲调试可读性（等价 6.1 选 B） |
| 达成难度 | 易（R8 后 2.16MB） | 需给 debug 开 minify |

结论：**选 A**。

### 6.4 决策点 4：附带成本

| 维度 | A：接受（补 keep 规则 + 一轮真机回归） | B：不接受 |
| --- | --- | --- |
| 50% 目标可达性 | 可达 | 不可达（其余手段合计不足 10%） |
| 工作量 | 低~中（keep 规则多由库 consumer-rules 自动携带） | 无 |

结论：**选 A**（−82.9% 的必然代价）。

## 七、最终选择与决策逻辑

- **最终决策**：新建 `release-in-house`（R8 + 资源裁剪 + debug 签名 + arm64-only）与 `release-store`（R8 + 资源裁剪 + arm64-only，签名占位）；达标口径以 `release-in-house ≤6.3MB` 为准；接受补 keep 规则 + 回归的附带成本。由用户 2026-09-06 拍板（九项决策之①）。
- **为什么**：体积 93% 是未 R8 的库代码，R8 是唯一能单独达成 50% 的杠杆（实测远超预期）；其余手段（换依赖/手写解析/资源瘦身/动态下发）收益趋近于零或高风险，均不采纳。
- **为什么不选其它**：debug 直接 minify 毁日常调试；release minify 污染发布语义且不可 debug；全 ABI 保留对本项目无意义（无自研 so，仅 28K 差异）。

## 八、对现有架构的影响

- **改动面**：仅 `composeApp/build.gradle.kts`（新增两个 buildType）+ 新增 `composeApp/proguard-rules.pro`。无业务代码、无协议、无依赖变更。
- **风险点**：R8 误删反射目标 → 运行时 NoClassDefFoundError / 序列化器缺失。经冒烟 + 连接回归验证，序列化（最大风险点）已确认存活；markdown 解析后端（org.intellij.markdown）因无法 100% 排除内部反射，按「宁可多 keep 不误删」整体保留。
- **兼容性与回滚**：debug 包不变，可随时回退；两个新变体独立于 `release`，互不影响；keep 规则逐步收紧路径见第九节。

## 九、后续迭代规划

1. **keep 规则收紧（阈值收紧）**：当前为「宁可多 keep 不误删」起步（org.intellij.markdown / zxing / mikepenz 整体保留）。待真机回归稳定后，逐项验证无反射路径，逐步放宽为「按需 keep」，进一步下探体积（预估可再省 ~100~200KB）。
2. **Markdown 表格渲染真机补验**：模拟器回归中，转盘开合拨动 / 排队面板 / Markdown 表格的**运行时交互**被一条真实待决问题弹层（「Jugg 秒编服务」）阻塞（该弹层按设计不可返回关闭，代答会替用户做技术决策）。待用户解决该决策后，补一轮这几项的运行时目验。
3. **release-store 签名落地**：当前占位未签，发布前接 Play App Signing / 正式签名。
4. **增量安装探索**：若后续仍觉装机慢，评估 `adb install --incremental`（Android 11+，需华为设备实测兼容性）。

## 十、参考来源

- APK 分析工具输出：`apkanalyzer apk summary / dex packages`（Android SDK cmdline-tools）+ `unzip -lv` 逐条目统计。
- 依赖树：`./gradlew :composeApp:dependencies --configuration androidDebugRuntimeClasspath`（只读）。
- keep 规则依据：kotlinx-serialization 官方 ProGuard 规则、Compose/AndroidX 库自带 consumer-rules、zxing/mikepenz 反射点静态核查。
- 决策与实施记录：`docs/status/pending-items.md`、AGENTS.md「历史决策记录」。
