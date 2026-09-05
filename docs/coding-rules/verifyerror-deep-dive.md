# Android DEX 校验器「超大函数 → VerifyError」源码级深挖与编码规则

> 归属：dsh-remote-control（Android 客户端）。本文是对 `docs/bugs/2026-09-05-verifyerror-startup-crash.md`（事故记录）的**源码级机制补充**，回答「为什么一个编译通过的 ~590 行 Composable 会在类加载时被 ART 校验器拒绝」，并沉淀可并入 detekt/lint 体系的编码规则。
>
> 事故原文：
> ```
> java.lang.VerifyError: Verifier rejected class com.daniel.dshremote.AppKt:
> void AppKt.Conversation(BridgeClient, SessionUiState, String, Composer, int)
> failed to verify: [0xD53] copy1 v2<-v266 type=Reference:
> androidx.compose.runtime.Composer cat=1
> ```

---

## 一、核心机制：为什么一个超大函数会走到「copy1 v2<-v266」

结论先行，三层原因叠加：

1. **Compose 编译器把单个 Composable 展开成一个巨型 JVM 方法**（`Composer` 插桩 + `remember`/`LaunchedEffect`/控制流展开 → 局部变量爆炸）；
2. **D8 把 JVM 方法翻译成 DEX 时分配出 267+ 个寄存器**，`Composer` 参数落到最高位的寄存器 v266；
3. **DEX 的 `move-object` 指令族存在「窄形式只能寻址 v0–v15」的编码上限**，而 ART 校验器在 `CopyRegister1`（category-1 引用拷贝）这一环节拒绝了这个 267 寄存器的方法。

下面逐层展开，并区分「已确证」与「假设（待进一步验证）」两个置信等级。

### 1.1 Compose 编译器如何让一个方法膨胀（已确证）

`@Composable` 函数经 Compose Compiler 插件改写后，签名从 `f(args)` 变成 `f(args, Composer, int)`，其中：

- **`Composer` 参数**：承载「重组槽位」状态机，几乎每一行 UI 代码都被包进 `composer.startReplaceableGroup/endGroup` 或 `startRestartGroup` 调用；
- **`int` 参数（changed 位掩码）**：用于跳过未变化的 group，编译器为每个 `remember`/条件分支计算位掩码，产生大量临时变量；
- **`remember { }` / `LaunchedEffect(...)` / `DisposableEffect`**：每个都展开为一个带 key 追踪的独立 group，lambda 被提升为额外的方法/局部；
- **`if`/`when`/循环 + 重组重启逻辑**：编译器生成 `$composer` 重启状态机（`$changed` 分发、`invoke` 嵌套），进一步摊大控制流和局部变量表。

一个承载「消息列表 + 深潜条 + Todo + Goal + 队列面板 + 开发者面板 + 输入区」全部职责的 Conversation（本事故约 590 行），改写后单方法 JVM 局部变量轻松破 200，D8 寄存器分配结果达 267+（事故实锤 `v266`）。

### 1.2 方法寄存器数与 DEX 指令编码上限（已确证，一手资料）

DEX 指令是「按形式定长」的，寄存器索引的编码宽度随指令形式变化。关键形式（[DEX 指令格式](https://source.android.com/docs/core/runtime/instruction-formats)）：

| 指令形式 | 字节数 | 寄存器操作数编码 | 可寻址寄存器范围 |
| --- | --- | --- | --- |
| `12x` | 1 | `op vA, vB`，A/B 各 4 bit（nibble） | **v0–v15** |
| `22x` | 2 | `op vAA, vBBBB`，AA 8 bit / BBBB 16 bit | dst v0–v255，src v0–v65535 |
| `32x` | 3 | `op vAAAA, vBBBB`，两个 16 bit | **v0–v65535** |
| `23x` | 2 | `op vAA, vBB, vCC`，各 8 bit | v0–v255 |
| `35c` | 3 | `op {vC..vD}, kind@BBBB`，5 个 4-bit 寄存器槽 | 每个 v0–v15 |
| `3rc` | 3 | `op {vCCCC..vNNNN}, kind@BBBB`，16-bit 范围 | v0–v65535 |

`move-object`（对象/引用拷贝）一族正好演示了「同一语义、三种编码」的扩容阶梯（[DEX 字节码操作码表](https://source.android.com/docs/core/runtime/dalvik-bytecode)）：

| 助记符 | 操作码 | 形式 | 源寄存器编码宽度 |
| --- | --- | --- | --- |
| `move-object` | `0x07` | `12x` | **4 bit（v0–v15）** |
| `move-object/from16` | `0x08` | `22x` | 16 bit（v0–v65535） |
| `move-object/16` | `0x09` | `32x` | 16 bit（v0–v65535） |

**结论**：源寄存器 v266（= 267 个寄存器里的最后一个）**只能**由 `/from16`（22x）或 `/16`（32x）编码；`12x` 的 4-bit 操作数硬上限是 v15。

### 1.3 ART 校验器在这一环节如何判死（已确证，一手资料）

ART 校验器的入口是 `art/runtime/verifier/method_verifier.cc`，逐指令走 `CodeFlowVerifyInstruction` 的 `switch (insn->Opcode())`。对 `move-object`（12x）分支，[AOSP 源码](https://android.googlesource.com/platform/art/+/3ae8da0a803370be9dd410226438f636af553e22/runtime/verifier/method_verifier.cc) 明确调用：

```cpp
case Instruction::MOVE_OBJECT:
  work_line_->CopyRegister1(this, inst->VRegA_12x(), inst->VRegB_12x(), kTypeCategory1nr);
  break;
```

即：校验器把「copy 一个 category-1（32-bit / 引用）寄存器」的操作统一命名为 `CopyRegister1`——**事故信息里的 `copy1` 就是它**，`cat=1` 对应 `kTypeCategory1nr`（非 wide 的 32-bit 类别），`v2<-v266` 是「从 v266 拷到 v2」，`type=Reference: Composer` 是校验器给 v266 判定的引用类型。

`VRegB_12x()` 读的是 12x 指令的第 12–15 bit（4-bit 字段），**上限 v15**。一个 267 寄存器的方法里，任何「窄形式」的 move 指令都装不下 v266；校验器在这一拷贝处失败，向上抛 `VerifyError: Verifier rejected class AppKt`——注意它是**类级拒绝**（`App()` 入口必然触碰 `Conversation()`，故任何路径都崩，P00）。

### 1.4 D8/Kotlin 编译器本应做什么、这里为何没做（假设，标注置信度）

- **本应**：D8 做 JVM→DEX 寄存器分配与指令选择时，遇到源寄存器 > 15 的 `move-object` 应自动降级到 `move-object/16`（32x）——这是 D8 的常规职责，绝大多数含 >15 局部变量的方法都能正确处理。
- **为何没做（两种假设，均待验证，但都不改变结论）**：
  1. **D8 指令选择在超多寄存器方法的某条路径上选错了窄形式**（D8 侧 bug，或 Kotlin Compose 生成的中间码触发了 D8 的某个极端分支）；
  2. **ART 校验器对「超大寄存器方法」的寄存器类型追踪在某处失稳**（`CopyRegister1` 对 v266 的类型/初始化判定失败）。
- **版本敏感证据**：同类报错在社区里出现「**Compose compiler 与 Kotlin 版本必须精确一致**才消失」的解法（见参考 [stackoverflow #78821144](https://stackoverflow.com/questions/78821144) 的修订记录），说明触发条件与编译器/校验器版本组合强相关，是「管道各层之间的边界 bug」而非单一层的确定性缺陷。
- **本项目的落地**：`249bb4a` 把 Conversation 拆成 9 个私有 Composable（+544/-468），单方法规模回归正常，ART 校验通过——**结构性拆分是版本无关的稳健解法**，优于「调 dex 配置 / 换编译器版本」。

---

## 二、VerifyError 全景表（类加载 / 校验阶段）

> 分类依据：[ART 字节码校验](https://deepwiki.com/google-mirror/platform_art/5.2-bytecode-verification) + [AOSP method_verifier.cc](https://android.googlesource.com/platform/art/+/63a63fc38115c415863b8b2d56b012ae3d9e00c7/runtime/verifier/method_verifier.cc)。相关度针对本项目（Kotlin/Compose Android 客户端）。

| # | 场景 | 触发条件 | 依据/案例 | 相关度 |
| --- | --- | --- | --- | --- |
| 1 | **寄存器索引非法 / 指令形式不匹配** | 单方法寄存器数过大，`move-*`/`invoke-*` 窄形式装不下高索引寄存器（本事故 `copy1 v2<-v266`） | AOSP `method_verifier.cc` `VRegB_12x()`；本项目 `2026-09-05-verifyerror` | **高** |
| 2 | **寄存器类型冲突（conflict）** | 同一寄存器沿不同控制流汇合出互不兼容的类型（引用 vs int 等） | ART `RegisterLine` merge 逻辑 | **高**（与 #1 相邻，同为校验器类型追踪产物） |
| 3 | **未初始化寄存器被读** | 分支上某寄存器在赋值前被读取 | ART `CopyRegister1` 对 undefined/zero 类型判死 | 中 |
| 4 | **未解析的类/方法/字段引用** | 引用了 dex 中不存在的类型（缺依赖、R8 误删、多 dex 顺序） | `"Verifier rejected … unable to resolve"` / `NoClassDefFoundError` 前身 | 中 |
| 5 | **invoke-super 指向错误类** | `invoke-super` 的目标不在直接父类链上（重命名/依赖冲突常见） | `"invoke-super in wrong class"` | 低 |
| 6 | **final 字段在 `<init>` 外赋值** | Kotlin `val` 编译后 final 字段被 R8/字节码改写误置到构造器外 | `"field is final"` 校验 | 中（R8 边界） |
| 7 | **访问控制违规** | 跨类调用 private/包私有方法、覆盖 final 方法 | ART 访问标志检查 | 低 |
| 8 | **无效分支目标** | 跳转/switch 落到非指令边界或代码区之外 | `"bad branch target"` | 低（编译器 bug 才触发） |
| 9 | **方法代码单元/长度超限** | 单方法 `code_units` 或 JVM `code` 超过 65535 | 严格说是编译期 `code too large`（D8/JVM 报），与 VerifyError 同源同因 | **高**（巨型函数另一表现） |
| 10 | **重复方法定义** | 同签名方法在类里重复（增量编译/合并异常） | `"duplicate method"` | 低 |
| 11 | **类/接口不匹配** | 类未实现接口全部方法、接口默认方法冲突 | `"class … does not implement"` | 低 |
| 12 | **参数数量/类型不匹配** | `invoke-*` 实参/形参数量或类型不一致 | ART 方法签名校验 | 低 |
| 13 | **wide(64-bit)寄存器用法错误** | long/double 按 32-bit 使用、wide 对未对齐 | `"wide register"` 类别校验 | 中（Kotlin Long 边界） |
| 14 | **`<init>` 构造规则违规** | `super()` 前访问实例字段、未正确调用父构造 | ART 构造链校验 | 低 |

> 说明：#9（code too large）严格来说是编译期错误而非运行时 VerifyError，但它与本事故同根同源——「单函数过大」——故并入，且它是**唯一能在构建期被 D8/JVM 拦住的**，而 #1/#2/#3 都只能到运行时类加载才暴露（本事故正因如此「编译通过 + LSP 0 诊断」却崩）。

---

## 三、编码规则清单（可并入 detekt / lint）

> 另一子代理正在搭 detekt/eslint 体系，以下规则按「规则 → 依据 → 建议阈值 → lint 实现建议」给出，可直接转成配置/自定义规则。

### R1 单 Composable 行数硬上限（阻断巨型 UI 函数）
- **规则**：`@Composable` 函数体（含 lambda 展开前源码）不得超过 **~400 行**；超过必须按 UI 区域拆分为私有 Composable。
- **依据**：本事故 Conversation ~590 行 → 267 寄存器 → VerifyError；事故记录自身的「后续改进」也建议 ~400 行门禁。
- **阈值建议**：Composable ≤ 300 行（留安全余量），普通函数 ≤ 60 行（detekt 默认 `LongMethod`）。
- **lint 实现**：detekt `LongMethod`（threshold 按 `@Composable` 注解分档）；或自定义 detekt rule 检测带 `@Composable` 且行数 > N 的函数。Compose 侧也可用 `androidx.compose.runtime` 的自定义 lint `Detector` 遍历 `UFunction` 统计行数。

### R2 单函数局部变量/复杂度上限（贴近真实「寄存器」指标）
- **规则**：单函数**局部变量声明数**与**循环复杂度**设上限，作为「DEX 寄存器爆炸」的源码级代理指标。
- **依据**：寄存器数 ≈ 局部变量 + 临时 + 参数；`Composer` 参数落位受局部变量总数影响。直接数寄存器不可行（源码层拿不到 DEX 寄存器），用「局部变量 + 分支复杂度」做代理最接近根因。
- **阈值建议**：单函数局部变量 ≤ **120**、循环复杂度（CyclomaticComplexity）≤ **30**、嵌套深度 ≤ 4。
- **lint 实现**：detekt `ComplexMethod`（McCabe）、`NestedBlockDepth`、`LongParameterList`（参数本身占寄存器，Composable 参数建议 ≤ 6）；自定义规则统计 `val` 声明数。

### R3 禁止单函数巨型 `when`/状态机（用策略表/下沉拆分）
- **规则**：单函数 `when` 分支数 > **~15** 时，改为「按事件/状态下沉到独立 handler」或策略对象，不堆在一个函数里。
- **依据**：同类问题在本项目已有两次同款修复——`249bb4a`（拆 Conversation）与 `b2737e6`（拆 `BridgeClient.handle` 巨型 `when` 为 29 个 handler）；巨型 `when` 是寄存器/控制流爆炸的高发源。
- **lint 实现**：detekt `TooManyFunctions`（类级）之外，用 `ComplexMethod` 覆盖分支密度；或自定义规则统计单个 `when` 的 `else/分支` 数量。

### R4 构建期「超大方法」静态告警（把运行时崩溃前移到编译期）
- **规则**：CI/构建里对生成的 DEX 做**方法寄存器数 / 指令数**的静态扫描，超过阈值即告警。
- **依据**：本事故「编译通过 + LSP 干净」却崩，根因是「构建产物层」无校验；`code too large`（#9）能在编译期拦，但「寄存器超窄形式上限」当前无现成拦截点，需自建。
- **lint 实现**：gradle 任务对 `composeApp/build/intermediates/dex`（或 `apk` 反编译）跑 `dexdump`/`baksmali`，解析每个方法 `registers_size`，> 128 告警、> 256 报错。这是**比行数规则更贴近根因**的硬门禁，优先级高于 R1/R2 的启发式。

### R5 真机启动冒烟 + 崩溃断言（兜底拦截，防「编译通过却崩」）
- **规则**：任何装机后必须执行「App 进程存活 ≥ 5 秒 + logcat crash buffer 无新 FATAL」的硬断言，崩溃即失败。
- **依据**：事故记录 Y4/Y5 指出验收缺「启动存活」硬断言，崩版本 00:55 装机后未被即时拦截达 8 小时；这也是 `VerifyError` 类问题（编译/静态分析全盲）的唯一可靠兜底。
- **lint 实现**：不是 lint，是验收工作流——已有 `startup-smoke-test` skill（启动存活 ≥5s）可复用；crash buffer 断言建议补 `adb logcat -d -b crash` 或 `am` + `dumpsys` 校验。

### R6 R8/混淆对这类问题的影响（认知 + 流程）
- **规则**：debug（无 R8，D8 直出）与 release（R8）**都要**在真机/模拟器跑一遍；不要把「release 正常」当作「debug 崩溃可忽略」。
- **依据**：本事故发生在 **debug 构建**（无 R8，D8 输出直达设备）；R8 会重做寄存器分配与指令选择，可能**绕过或改变**触发路径——因此「debug 崩、release 不一定崩」，反之亦然。根因是源码结构，不是构建配置，不能靠切 R8 规避。
- **lint 实现**：无；属验收流程项，纳入 CI 双构建矩阵（debug + minify release）。

---

## 四、参考链接

- [Dalvik executable instruction formats（DEX 指令形式与寄存器编码）](https://source.android.com/docs/core/runtime/instruction-formats)
- [Dalvik 字节码 / 操作码表（move-object 0x07/0x08/0x09）](https://source.android.com/docs/core/runtime/dalvik-bytecode)
- [AOSP method_verifier.cc（`CopyRegister1` / `VRegB_12x`，move-object 12x 处理）](https://android.googlesource.com/platform/art/+/3ae8da0a803370be9dd410226438f636af553e22/runtime/verifier/method_verifier.cc)
- [ART 字节码校验概览（DeepWiki）](https://deepwiki.com/google-mirror/platform_art/5.2-bytecode-verification)
- [社区同款「Verifier rejected class in debug build」（stackoverflow #78821144，含 Compose/Kotlin 版本一致性的解法线索）](https://stackoverflow.com/questions/78821144)
- 项目内：`docs/bugs/2026-09-05-verifyerror-startup-crash.md`（事故记录）；commit `249bb4a`（拆 Conversation 修复）、`b2737e6`（拆 BridgeClient.handle 巨型 when）。

> 置信度标注：第二节「机制」中「已确证」部分依据 DEX 规范与 AOSP 源码；「假设」部分（1.4 节 D8/校验器具体哪一层 bug）尚未在一手 issue tracker 上定位到单条编号，结论不依赖它——无论哪层，结构性拆分（R1/R3）+ 构建产物门禁（R4）+ 启动冒烟（R5）都能阻断。
