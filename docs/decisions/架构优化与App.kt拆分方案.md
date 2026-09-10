# 架构优化与 App.kt 拆分方案

> 专项域：架构优化与重构（新域）
> 日期：2026-09-10
> 分支：`refactor/arch-split`（worktree 隔离，铁律 10）
> 硬约束：**只改结构不改行为**；不碰 `build.gradle` / `AndroidManifest`；不 merge 回 main、不推 GitHub。

---

## 0. 结论速览

| 项 | 现状 | 目标（本次落地） |
|---|---|---|
| `App.kt` | 4098 行，~90 个顶层 `@Composable` + 辅助函数混在一个文件 | 拆成 **13 个文件**（App.kt + 12 新文件），最大文件 ~710 行 |
| `BridgeClient.kt` | 1774 行（类体 ~1490 行） | **零风险顶层抽取**（3 个新文件）；类体拆分**标注待拍板** |
| `detektP0` | 绿（74 文件 0 命中） | 保持绿，并显著降低未来收紧阈值的暴露面 |
| 行为 | —— | **零变化**（纯机械搬移 + `private→internal` 可见性，不改任何签名/逻辑/文案） |

---

## 1. 现状审计

### 1.1 God 文件清单与行数

| 文件 | 行数 | 类型 | 职责混杂情况 |
|---|---|---|---|
| `composeApp/src/commonMain/kotlin/com/daniel/dshremote/App.kt` | 4098 | 全顶层 `@Composable` + 辅助函数 | **极度混杂**：入口路由、落地页、连接页、手机/平板主屏、会话列表、会话详情、输入区、调试面板、消息气泡、审批/提问弹窗、日志页、设置页，全部平铺在一个文件 |
| `composeApp/src/commonMain/kotlin/com/daniel/dshremote/BridgeClient.kt` | 1774 | 单 class + 顶层 data class/辅助函数 | 连接生命周期、协议事件处理（~30 个 `handle*`）、会话管理、发送、缓存持久化、通知、日志、设备注册纠缠在单类中 |
| `composeApp/src/commonMain/kotlin/com/daniel/dshremote/protocol/Protocol.kt` | 819 | 协议 wire 类型 | 合理（协议域单一），**不动** |
| `composeApp/src/commonMain/kotlin/com/daniel/dshremote/MessageDial.kt` | 530 | 转盘 UI + 逻辑 | 中等，**本次不动** |

### 1.2 App.kt 顶层函数规模（P0 LongMethod 阈值 200 行）

实测最大单函数 173 行（`ConversationComposer`）、133 行（`App`）、120 行（`TopBar`）——**均未触 200 行 LongMethod**。因此 App.kt 的 P0 风险不是 LongMethod，而是**单文件体量与可维护性**（历史教训 commit 249bb4a：Conversation 单函数过大触发 VerifyError）。

### 1.3 BridgeClient 职责边界审计（纠缠点）

已抽取的协作类（良好）：`ConnectionManager`（WS 连接/收发/重连延迟）、`DeviceRepository`（设备列表/探测）、`PendingSender`（消息 ack/重放）、`NotificationController`（通知决策/文案）、`KeepAliveController`（FGS/保活）、`PendingMessages`（纯函数集合）。

仍纠缠在 `BridgeClient` 类体中的职责：
1. **连接编排**：`connect/connectFromQr/connectDevice/connectManual/disconnect/startReconnect/onConnectionLost/finishSession/registerIfNeeded`
2. **协议事件投影**（~30 个 `handle*` 方法，把 `ServerEvent` 折叠进 `SessionUiState`）
3. **会话/视图管理**：`openSession/closeSession/openSubagent/selectWorkspace/loadOlderPage/updateView`
4. **发送出口**：`sendMessage/retryMessage/interrupt/setModel/sendQueueAction/sendDebugCommand/approve/answerQuestion`
5. **本地缓存持久化**：`loadDraft/saveDraft/hydrateFromSessionCache/scheduleCacheSave/scheduleSessionCacheSave`
6. **通知编排**：`handleNotificationOpen/maybeOpenPendingNotificationSession/notifyGoalDelivery`
7. **日志拉取**：`loadServerLogs`
8. **设备管理**：`forgetDevice`

类体 ~1490 行（detekt `LargeClass` 阈值 1200，按 lines-of-code 计当前未命中但极贴近，见 1.4）。

### 1.4 门禁基线（实测）

- `./gradlew :composeApp:detektP0 --rerun-tasks` → **BUILD SUCCESSFUL**（74 个 Kotlin 文件，0 命中）。
- detekt `LargeClass` 以 lines-of-code（去空行/纯注释行）计：`BridgeClient` 类体非空行 1270，贴近 1200 阈值但当前不报——**拆分是预防性 + 可维护性驱动**，非修门禁。
- R4 DEX 门禁 `checkDexRegisters` 已在 HEAD 的 `build.gradle.kts` 接线（`>128 告警 / >256 报错`），随 `assembleDebug` 自动跑。

### 1.5 Compose 状态管理审计

- **无 ViewModel / 无 DI 框架**。`BridgeClient` 即状态持有者：暴露 `StateFlow<SessionUiState>` / `StateFlow<ConnectionInfo>` / `StateFlow<DevicesUiState>` / `StateFlow<ConnectionNotice>` / `StateFlow<Boolean>`(scanning)。
- 根 `App()` 用 `collectAsState()` 收集，UI 局部状态（`showLogs/showDevices/showSettings/themeMode`）用 `remember { mutableStateOf(...) }` 提升在根节点，经回调下发。**单向数据流已成立**（铁律 6：服务端投影为准）。
- 本次拆分**不做**状态管理迁移（属行为/接口层面，待拍板），仅把 `@Composable` 函数整段搬到新文件，函数内部 `remember` 状态随函数整体迁移，不拆不升。

### 1.6 重复代码清单（本次不改行为，仅登记）

| 位置 | 重复点 | 建议（待拍板，不属机械拆分） |
|---|---|---|
| `ApprovalDragHandle` vs `QuestionDragHandle` | 两处拖拽把手 Composable 近乎相同 | 可合并为共享 `DragHandle(queueCount)` |
| `levelColor(ConnLogLevel)` vs `levelColorOf(String)` | 两套颜色映射（本地 enum / 服务端 string） | 可统一为 string→Color 一处 |
| `SessionCard` 内 `combinedClickable` 气泡卡样式 | 与 `MessageBubbles` 多个卡重复 | 可抽共享 `CardShell` |

> 以上仅登记，**本次不实施**（涉及抽公共组件=签名变更，需拍板）。

### 1.7 commonTest 覆盖盲区

已有 24 个测试文件（commonTest 21 + androidUnitTest 3），覆盖：协议解析、通知决策/文案、Pending 纯函数、KeepAlive 决策、QueueItem 逻辑、输入条逻辑、转盘数学、Markdown、平板判定、主题、时间戳、Pairing、`SessionUiState` 转换、`pickRecentSession`、`BridgeClient`（androidUnitTest，连接/ack）、存储层。

**盲区**（本次拆分不新增 UI 测试，仅登记）：
1. `App.kt` 内大量纯辅助函数（`statusOf/statusLabel/sessionName/basenameOf/levelColorOf/formatDivingDuration`）**无单测**——这些是零依赖纯函数，可低成本补测（本次实施项）。
2. `BridgeClient.handle*(ev)` 事件投影逻辑覆盖薄弱（仅 `SessionUiStateTest` 少量）。
3. Compose UI 无 UI 测试（commonTest 无法跑 UI；真机回归靠统一装机批次）。

---

## 2. 目标结构

### 2.1 App.kt 拆分文件结构（全部 `package com.daniel.dshremote`，平铺不建新包）

> 拆分原则：**按页面/功能域聚合**，同屏强耦合的 Composable 放同文件，跨文件的共享纯函数（`sessionName`）保持 `internal` 供同包引用。所有文件 `internal`（模块内可见），不改任何 `@Composable` 签名。

| # | 文件 | 职责 | 源行区间 | 预估行数 |
|---|---|---|---|---|
| 1 | `App.kt` | 入口 `App()`：状态收集、页面路由（设置/设备/日志/扫码/主屏/落地）、审批/提问/权限弹窗编排 | 125-260 | ~130 |
| 2 | `LandingScreen.kt` | 落地页/设备列表/手动连接/设备卡/连接中/通知权限弹窗 + `statusOf`/`statusLabel` | 263-649 | ~390 |
| 3 | `ConnectionBanners.kt` | 重连横幅/连接错误横幅/错误历史 Sheet | 873-993 | ~120 |
| 4 | `MainScreen.kt` | 手机主屏/平板三栏主屏/平板左栏 | 650-872 | ~225 |
| 5 | `WorkspaceChrome.kt` | 顶栏 TopBar/工作区抽屉/过滤/入口 + `SUBAGENT_MENU_*` 常量 | 994-1108, 1109-1228, 125-126 | ~235 |
| 6 | `SessionList.kt` | 会话列表/中断确认弹框/会话卡/子代理副标题 + `sessionName`/`basenameOf` | 1229-1455 | ~230 |
| 7 | `Conversation.kt` | 会话详情/消息列表/面板组/排队/深度潜水/开发面板 + `formatDivingDuration` | 1456-2076, 4008-4025 | ~710 |
| 8 | `Composer.kt` | 输入区/模型入口/上下文环/模型选择/图标 | 2077-2505 | ~430 |
| 9 | `DebugPanel.kt` | 调试面板/暂停区/调用栈/变量树 | 2506-2789 | ~285 |
| 10 | `MessageBubbles.kt` | 事件/待发/气泡/工具卡/思考卡/上下文/命令/工具结果 | 2790-3436 | ~650 |
| 11 | `ApprovalSheets.kt` | 审批弹窗/拖拽把手/内容/提问弹窗/选项 | 3437-3802 | ~365 |
| 12 | `LogScreen.kt` | 日志页/本地列表/服务端列表/过滤片/行 + `levelColor`/`levelColorOf` | 3803-4007 | ~205 |
| 13 | `SettingsScreen.kt` | 设置页（主题三态/通知占位/后台保活） | 4026-4098 | ~72 |

### 2.2 Composable 依赖方向（单向，无环）

```
App.kt（根）
 ├─→ LandingScreen.kt（含 ConnectingScreen）
 ├─→ MainScreen.kt ──→ WorkspaceChrome.kt (TopBar/抽屉)
 │                   ├─→ SessionList.kt ──→ sessionName（同文件）
 │                   ├─→ Conversation.kt ──→ Composer.kt / DebugPanel.kt / MessageBubbles.kt
 │                   └─→ ConnectionBanners.kt
 ├─→ LogScreen.kt
 ├─→ SettingsScreen.kt
 └─→ ApprovalSheets.kt（+ sessionName from SessionList.kt）
```

依赖均为**同包 `internal` 顶层函数调用**，无 import 变更，无跨文件 `remember` 状态共享（每个 Composable 的状态仍在其函数内部）。

### 2.3 BridgeClient.kt 拆分

**A. 零风险顶层抽取（本次实施）**——移出的是**顶层声明（非类成员）**，同包同可见性，测试零改动：

| 新文件 | 移入内容 | 源行区间 |
|---|---|---|
| `SessionUiState.kt` | `data class SessionUiState` + `clearedForDisconnect`/`clearConnectedDeviceIf` 扩展函数 | 121-284 |
| `ConnectionNotice.kt` | `data class NoticeError` + `sealed interface ConnectionNotice` + `reconcileErrorsOnHello` | 85-104 |
| `BridgeClientHelpers.kt` | `deviceKey`×2 + `mergeEndpoints` + `buildConnectFailureDetail` + `bounded` + `pickRecentSession` | 39-119 |

**B. 类体拆分（标注待拍板，本次不实施）**——需把 `private` 成员提升为 `internal` 供扩展函数/协作类访问，属**接口改动**，违反本次「不得擅自做接口重构」约束：

- 事件投影：~30 个 `handle*` → `BridgeClientEventHandlers.kt`（扩展函数）
- 连接编排 → `BridgeClientConnect.kt`；发送出口 → `BridgeClientSend.kt`；缓存 → `BridgeClientCache.kt`
- 收益：`BridgeClient` 类体 1490 → 目标 <600；风险：`private→internal` 扩大封装面、协作类需回传 `SessionUiState`（可能引入接口 churn）。

---

## 3. 迁移策略（纯机械，分模块 commit）

1. **worktree 隔离**：`git worktree add ...-refactor -b refactor/arch-split HEAD`（已完成，基于 `fbe6406`）。
2. **机械搬移**：按 §2.1 行区间把函数体**整段复制**到新文件（保留原始顺序），顶层 `private` → `internal`；`@OptIn`/`@file:OptIn` 随函数/按需迁移（`MessageBubbles.kt` 需 `@file:OptIn(ExperimentalFoundationApi::class)`）。
3. **import 收敛**：每文件只保留被引用符号的 import（脚本按「import 符号在文件正文出现」判定，再经编译兜底校验），剔除孤儿 import。
4. **分模块 commit + 门禁**：每搬 1~2 个文件 → `compile` + `testDebugUnitTest` + `detektP0` + `assembleDebug`(+DEX) 全绿 → commit。任一步失败即回退该步（§5.3 回滚策略）。
5. **行为零变化验证**：拆前拆后对每函数做文本级 diff 确认「仅 `private→internal` + 换文件 + 删 import」，无任何逻辑/文案/数值改动。

---

## 4. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| **R4 / DEX registers** | 拆文件不改变单个 `@Composable` 方法体，registers_size 理论不变；但必须实测 | 每模块跑 `assembleDebug`（`checkDexRegistersDebug` 随包自动跑）+ 全量 `checkDexRegisters`，留日志 |
| **Composable 签名稳定性** | 任何参数顺序/默认值/名称改动都会改变调用点语义 | 本次**零签名改动**；`private→internal` 仅是可见性，不改函数体 |
| **`remember` 状态跨文件迁移** | 若把函数内部 `remember` 状态提升/拆分会引入状态共享 bug | 本次**整函数搬移**，状态不跨函数移动；每个 Composable 内部 `remember` 原样保留 |
| **`@file:OptIn` / 实验 API** | 新文件缺 OptIn 会编译失败（非行为问题，但会卡门禁） | `MessageBubbles.kt`（`combinedClickable`）显式 `@file:OptIn(ExperimentalFoundationApi::class)`；`ModalBottomSheet` 相关已按函数带 `@OptIn(ExperimentalMaterial3Api::class)` |
| **孤儿 import / 缺 import** | 拆后编译报错 | 脚本符号匹配 + 编译兜底，逐文件修 |
| **并行主线冲突** | main 有 APK 域代理改 build.gradle / 其他域并行 | worktree 隔离；我不碰 build.gradle/Manifest；合并由主对话统一处理 |
| **LargeClass 误判** | `internal` 顶层函数不属任何类，不影响 LargeClass；`BridgeClient` 类体不动 | 确认拆分对象均为顶层声明 |

---

## 5. 测试计划

### 5.1 分模块自测（每拆分模块）

| 步骤 | 命令 | 通过标准 |
|---|---|---|
| 编译 | `./gradlew :composeApp:compileDebugKotlin` | 0 错误 |
| 相关单测 | `./gradlew :composeApp:testDebugUnitTest` | 全绿（commonTest + androidUnitTest 全量） |
| P0 | `./gradlew :composeApp:detektP0` | 0 命中 |
| 打包+DEX | `./gradlew :composeApp:assembleDebug` + `:composeApp:checkDexRegisters` | registers 无 >256 报错（>128 仅告警记录） |

**新增可测纯逻辑单测**（本次实施，落在 `commonTest`，覆盖 1.7 盲区 1）：
- `formatDivingDuration`（秒→「Xm Ys」格式化，边界 0/60/3600）
- `sessionName` / `basenameOf`（会话名回退、路径 basename）
- `statusOf` / `statusLabel`（设备状态枚举→UI 状态/文案映射）
- `levelColorOf`（日志级别字符串→颜色映射，未知级别回退）
- `mergeEndpoints` / `buildConnectFailureDetail`（已有 `ConnectDiagnosticsTest`，核对覆盖）

> 这些函数已随拆分变为 `internal`，同包 `commonTest` 可直接访问；**不改函数实现，仅补断言**。

### 5.2 集成测试计划（真机，统一装机批次，主对话协调）

拆分后回归清单（见 `docs/reports/refactor-regression-checklist.md`，每功能点一条，供批次目验）：

1. 启动冒烟（P00）：冷启动存活 ≥5s，crash buffer 无本包 FATAL
2. 发送 / ack：发消息 → 立即上屏 + Loading → 成功消失；失败红❗可重发
3. 转盘（MessageDial）：拖拽/对齐/深色主题
4. 排队面板：默认折叠、展开、中断二选一弹框（终止并清空 / 仅终止保留 / 取消）
5. 设置 / 主题三态：跟随系统/浅色/深色 即时生效 + 持久化
6. 中断弹框：有排队消息时中断 → 弹框选择语义
7. FGS 通知：后台运行时结果交付通知送达
8. 平板布局：宽 ≥840dp 三栏；手机单页流零回归
9. 页面导航：返回键逐级回退（设备页/日志页/设置页覆盖层）
10. 审批/提问弹窗：半屏强提醒，裁决/回答后关闭

### 5.3 回滚策略

- **分步 commit**：每个模块独立 commit（`refactor(app): 拆分 xxx 到独立文件`），任何一步门禁失败 → `git checkout -- <该模块文件>` 回退该步，其余模块不受影响。
- **整体回滚**：若合并后集成回归发现问题 → `git revert` 单个 commit 或整体回退 `refactor/arch-split`。
- **零行为承诺**：回退依据=文本级 diff 确认无行为差异。

---

## 6. 待拍板项（不属本次机械拆分，需主对话转用户确认）

1. **BridgeClient 类体拆分**（§2.3-B）：`private→internal` 扩展函数/协作类，接口层面重构。
2. **重复代码合并**（§1.6）：`DragHandle`/`levelColor`/气泡卡壳抽公共组件。
3. **状态管理迁移**（§1.5）：是否引入 ViewModel / 更细粒度 StateFlow 切分。
4. **P0 阈值收紧**（`LongMethod 200→60 / LargeClass 1200→600 / LongParameterList 10→6`）——本次拆分后 App.kt 各文件均 <600 行（除 Conversation.kt 710），`BridgeClient` 类体仍 1490，需先拍板项 1 才能全面收紧。
