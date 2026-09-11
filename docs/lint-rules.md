# lint 规则库（dsh-remote-control / App，Kotlin detekt）

本文档记录 lint 规则的定义、起步阈值、收紧路径与新增规则动机。是「代码质量闸门」的配套说明（见 AGENTS.md「代码质量闸门」一节）。

## 工具链

- **detekt** 1.23.8（gradle 插件 `io.gitlab.arturbosch.detekt`）
  - 选择理由：当前稳定版（2025-02 发布），兼容本仓库 Kotlin 2.1.0 / AGP 8.7.3；规则集成熟、配置为官方默认配置起步。
- 配置：`config/detekt/detekt.yml`（全量，官方默认配置 + P0 阈值放宽）、`config/detekt/detekt-p0.yml`（P0 子集）。
- 任务：`./gradlew :composeApp:detekt`（全量）、`./gradlew :composeApp:detektP0`（P0 闸门）。

## P0 规则清单（起步版）

P0 = 「超大函数 / 类过大 / 超长参数列表」等高风险项，**任何命中即 commit 闸门拦截**（pre-commit hook）。

| 规则（detekt id） | 含义 | 起步阈值 | 目标值 | 收紧方式 |
| --- | --- | --- | --- | --- |
| `complexity/LongMethod` | 超大函数 | 200 行 | 60 行 | 按季度下调（200→120→80→60） |
| `complexity/LargeClass` | 类过大 | 1200 行 | **600 行（2026-09-10 已收紧）** | 拆分 `BridgeClient` 等大类后下调 |
| `complexity/LongParameterList` | 超长参数列表 | 函数 10 / 构造器 10 | 函数 6 / 构造器 7 | 参数对象化后下调 |

### 为什么起步阈值这么宽

- 存量代码（如 `App.kt` 3199 行、`BridgeClient.kt` 1294 行）含大量历史函数/类，直接套用 detekt 默认阈值会立刻阻塞所有 commit。
- 起步策略是「**存量告警不阻塞、新增违规必须清零**」：阈值放宽到存量当前最大规模以下安全线，保证 `detektP0` 当前通过；后续一边拆分一边收紧阈值，让存量逐步消化。

## 收紧路径（roadmap）

1. **拆大类（✅ 已完成 2026-09-10）**：`BridgeClient`（~1500 行类体）按职责拆成连接/会话/发送/事件投影/审批交互 5 个扩展函数文件（主类收敛到 174 行）→ `LargeClass` 阈值已从 1200 下调到 600，`detektP0` 99 文件 0 命中。
2. **拆长函数**：对 `detekt` 全量报告中 `LongMethod` 命中的函数逐个拆分 → `LongMethod` 阈值下调到 60。
3. **参数对象化**：把 6+ 参数的 Compose 函数改成 data class 入参 → `LongParameterList` 阈值下调到 6/7。
4. **扩充 P0 集**：稳定后把 `complexity/CyclomaticComplexMethod`、`complexity/NestedBlockDepth` 等纳入 P0。

## 规则积累机制

- 新增/修改规则时：在本文档登记「规则 id + 动机 + 阈值 + 影响范围」。
- 阈值只能**收紧**（下调），不能反向放宽（除非在本文档写明理由并经 review）。
- 全量 `detekt` 报告为建议项（不阻塞 commit），P0 报告为闸门（阻塞 commit）。

## R4 DEX registers 硬门禁（构建产物层，非 detekt）

- **规则**：构建期扫 APK 内所有 classes*.dex，解析本应用包每个方法的 `registers_size`——**>256 报错（构建失败）、>128 告警**。
- **动机**：VerifyError 事故「编译通过 + LSP 干净却崩」的根因在「构建产物层」无校验（见 `docs/coding-rules/verifyerror-deep-dive.md` R4）；方法寄存器数 >256 有触发 ART 校验器窄寄存器上限的风险，是比行数规则更贴近根因的硬门禁。
- **实现**：`composeApp/build.gradle.kts` 的 `checkDexRegisters` 任务族（随 `assemble<Variant>` 自动执行）+ buildSrc 纯函数 `DexRegistersParser`（`./gradlew -p buildSrc test`，5 例单测覆盖正常/超128/超256/多dex/解析容错）。
- **阈值覆盖参数**：`-PdexRegistersMax`（报错阈值，默认 256）、`-PdexRegistersWarn`（告警阈值，默认 128）、`-PdexRegistersPackage`（包前缀，默认 `com/daniel/dshremote`）。
- **只扫本包**：三方库（如 Compose Material3 的 `colors-0hiis_0` 268 寄存器方法）不在本应用控制范围，全量扫描会误报；故默认只扫 `com/daniel/dshremote`，如需全量可传 `-PdexRegistersPackage=`（空串）。
