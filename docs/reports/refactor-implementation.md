# 重构实施记录（refactor/arch-split）

> 专项域：架构优化与重构。日期 2026-09-10。**只改结构不改行为**。
> 分支 `refactor/arch-split`（worktree 隔离，铁律 10）；不 merge 回 main、不推 GitHub。

## 1. 交付物与 commit 列表

| commit | 内容 |
|---|---|
| `b9610ed` | `refactor(app)`: App.kt 4098 行 → 13 文件（纯机械搬移） |
| `06efae4` | `refactor(app)`: BridgeClient.kt 顶层声明抽取 → 3 文件 |
| `72baeb4` | `test(app)`: 补纯辅助函数单测（12 例） |
| `__docs__` | `docs`: 设计文档 + 回归清单 + 本实施记录 |

## 2. 前后行数对比

| 文件 | 拆前 | 拆后 |
|---|---|---|
| `App.kt` | 4098 | 134 |
| `BridgeClient.kt` | 1774 | 1529（类体仍 ~1490，待拍板项） |
| 新增 App 拆分文件 | —— | 12 个，最大 `Conversation.kt` 645 / `MessageBubbles.kt` 646 |
| 新增 BridgeClient 抽取文件 | —— | `SessionUiState.kt` 163 / `BridgeClientHelpers.kt` 61 / `ConnectionNotice.kt` 16 |
| 新增单测 | —— | `AppPureHelpersTest.kt` 12 例 |

App.kt 拆分后文件清单（全部 `package com.daniel.dshremote`）：`App` / `LandingScreen` / `ConnectionBanners` / `MainScreen` / `WorkspaceChrome` / `SessionList` / `Conversation` / `Composer` / `DebugPanel` / `MessageBubbles` / `ApprovalSheets` / `LogScreen` / `SettingsScreen`。

## 3. 门禁证据（每 commit 真实通过，日志留证）

| 门禁 | 结果 |
|---|---|
| `compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `testDebugUnitTest` | ✅ 267 tests / 0 failures / 0 errors（拆前 255，+12 新例） |
| `detektP0` | ✅ 0 命中（90 个 Kotlin 文件；拆前 74 文件亦 0） |
| `assembleDebug` + `checkDexRegisters` | ✅ 0 报错(>256)；debug 最大 registers=202，>128 告警 6 条（均为**拆分前既有**函数体，未改） |
| `checkDexRegisters`（全变体） | ✅ release 最大 160 / release-in-house & store 最大 38（R8 后） |
| pre-commit hook（P0 闸门） | ✅ 三次 commit 均通过 |

## 4. 零行为变化铁证

- **App.kt**：行级多集 diff —— 拆前 3871 非空行 == 拆后 3871 非空行，**0 增删改**（仅 `private→internal` + 换文件 + 收敛 import）。
- **BridgeClient.kt**：行级多集 diff —— 1615 非空行 == 1615，**0 增删改**。
- **顶层声明数**：拆前 75 == 拆后 75（App.kt）；无函数丢失/重复。
- 未改任何 `@Composable` 签名 / `remember` 状态 / 文案 / 数值 / 逻辑。

## 5. 未做的高风险重构项（待用户拍板，见设计文档 §6）

1. **BridgeClient 类体拆分**：~30 个 `handle*` + 连接/会话/发送/缓存/通知协作类提取（需 `private→internal`，接口层面重构）。
2. **重复代码合并**：`ApprovalDragHandle`/`QuestionDragHandle`、`levelColor`/`levelColorOf`、气泡卡壳。
3. **状态管理迁移**：是否引入 ViewModel / 更细粒度 StateFlow。
4. **P0 阈值收紧**：`LongMethod 60 / LargeClass 600 / LongParameterList 6/7`（BridgeClient 类体仍 ~1490，需先拍板项 1）。

## 6. 后续

- 合并由主对话统一执行 + 全量门禁 + 集成验证（真机统一装机批次）。
- 回归清单：`docs/reports/refactor-regression-checklist.md`（每功能点一条，供批次目验）。
- 设计/测试计划：`docs/decisions/架构优化与App.kt拆分方案.md`。
