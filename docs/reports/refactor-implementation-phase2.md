# 重构实施记录 phase2（refactor/arch-split-phase2）

> 专项域：架构优化与重构。日期 2026-09-10。范围=设计文档 §6 的 1/2/4（第 3 项 ViewModel 迁移不做）。
> 分支 `refactor/arch-split-phase2`（worktree 隔离，铁律 10）；不 merge 回 main、不推 GitHub、不碰 build.gradle。

## 1. commit 列表

| commit | 内容 |
|---|---|
| `79aed61` | `refactor(app)`: BridgeClient 类体拆分——方法下沉为 5 个扩展函数文件 |
| `71d4f54` | `refactor(app)`: ApprovalDragHandle/QuestionDragHandle 参数化合一 |
| `01f14da` | `chore(lint)`: P0 LargeClass 阈值 1200→600 收紧 |

## 2. 拆分结构（第 1 项：BridgeClient 类体拆分）

类体 1526 行 → **主类 174 行 + 5 个扩展函数文件**（方法体零改动，仅 `private→internal` + 加 `BridgeClient.` 接收者）：

| 文件 | 职责 | 行数 |
|---|---|---|
| `BridgeClient.kt` | 主类：字段 + init（连接/通知/KeepAlive 三路 combine 编排） | 174 |
| `BridgeClientConnect.kt` | 连接编排：扫码/手动/自动连接、多设备切换、指数退避重连、设备注册/撤销 | 321 |
| `BridgeClientSession.kt` | 会话/缓存/草稿/子代理导航 + 通知直达 | 211 |
| `BridgeClientSend.kt` | 发送出口：消息/重发/中断/模型/审批/提问/日志/错误推送 | 258 |
| `BridgeClientEvents.kt` | 核心事件投影：hello/history/event/队列/模型/调试/诊断/会话 | 430 |
| `BridgeClientInteractions.kt` | 审批/提问/交付通知/设备注册/错误处理 + goal 通知 | 180 |

**语义原样保留**（用户点名的新增功能全部不动）：`subagentReturnStack` 栈（navigateToSession/closeSession/openSubagent/handleHello 的 prune）、`pendingDeliveries`（handleHello/handleDeliveryNotice/confirmDeliveries）、`KeepAlive` 三路 combine（init）。

## 3. 前后行数

| 文件 | 拆前 | 拆后 |
|---|---|---|
| `BridgeClient.kt` | 1526 | 174（主类，detekt LOC=80） |
| 新增 5 个扩展函数文件 | —— | 180~430 各 |

## 4. 门禁证据（每 commit 真实通过）

| 门禁 | 结果 |
|---|---|
| `compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `testDebugUnitTest` | ✅ 298 tests / 0 failures / 0 errors |
| `detektP0`（LargeClass 600 后） | ✅ 99 文件 0 命中 |
| `assembleDebug` + `checkDexRegisters` | ✅ 0 报错(>256)；debug max registers=202（拆分前既有函数体，未改） |
| `checkDexRegisters`（全变体） | ✅ release/in-house/store 全部 0 报错 |
| pre-commit hook | ✅ 三次 commit 均通过 |

## 5. 零行为铁证

- **BridgeClient 类体拆分**：行级多集 diff（去缩进 + 去可见性 + 去接收者 normalize）——类体 1370 非空行 before==after，**0 增删改**。
- **DragHandle 合一**：参数化重构（icon/title/countLabel 原样传入），渲染逐像素一致；徽章（圆点 vs 💬）与文案（等待审批 vs 等待回答）原样保留。

## 6. P0 收紧结果（第 4 项）

- `LargeClass` 阈值 1200 → **600**（`config/detekt/detekt-p0.yml`）。
- `detektP0 --rerun-tasks`：99 文件 0 命中，**无类越线**。
- sanity check：临时 `threshold=50` 报 78 命中，证明规则 active（非静默失效）。
- 全仓最大类 LOC：`BridgeClientTest` 230（测试类）、`ConnectionManager` 128、`SessionUiState` 105、`DeviceRepository` 104、`BridgeClient` 主类 80。
- 已登记 `docs/lint-rules.md`（roadmap 第 1 条标记完成 + 阈值表更新）。

## 7. 重复代码合并结论（第 2 项）

| 项 | 结论 |
|---|---|
| `ApprovalDragHandle`≈`QuestionDragHandle` | ✅ **已参数化合一**（抽 `DragHandleShell`，渲染零变化） |
| `levelColor`≈`levelColorOf` | ❌ **不合并**：输入语义不同——`levelColor(ConnLogLevel)` 枚举 label=D/I/W/E（本地日志），`levelColorOf(String)` 匹配服务端字符串 warn/error/info（默认 debug）；强行合一需新增 enum→字符串映射，引入新逻辑，违背「零行为变化」。 |
| 气泡卡壳去重 | ❌ **不合并**：`containerColor`（errorContainer/surfaceVariant/alpha 0.35/0.5）与卡内容各异，抽共享壳收益低、风险高（细微视觉差异宁可保留）。 |

## 8. 后续

- 合并由主对话统一执行 + 全量门禁 + 集成验证（真机统一装机批次）。
- 回归清单：`docs/reports/refactor-regression-checklist.md`（已补「BridgeClient 拆分后主流程」7 条目）。
- phase1 设计文档：`docs/decisions/架构优化与App.kt拆分方案.md`。
