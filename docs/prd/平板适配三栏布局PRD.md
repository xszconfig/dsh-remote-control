# 平板适配：三栏布局 PRD

> 阶段：实现需求流水线 ①-④（本阶段仅文档，不改代码、不 commit）。
> 相关决策/规范：`docs/ui-mobile-first.md`、`docs/ui-navigation-guidelines.md`、`docs/feature-priority-map.md`。
> 编码负责人：App.kt 结构子代理（本 PRD 作者），等主对话放行后进入编码阶段。

---

## 一、需求背景

### 1.1 痛点

- 现状 App 是**手机单页流**：会话列表 → 会话详情 → 子代理会话，逐层覆盖，靠返回键/左上角 ← 后退。
- 平板（≥10"）屏幕宽裕，单页流会**大面积留白**：会话列表撑满整屏、消息气泡占据全宽，信息密度低、来回切换频繁。
- 用户明确诉求（原话要点）：**项目列表 + 会话列表合并放左侧，右侧展示选中主会话；子代理会话展示在最右侧且左侧列表收起**——即经典「三栏」信息架构（类似邮箱/IM 桌面端）。

### 1.2 目标

1. 平板（Expanded）下提供三栏布局：左栏=项目+会话合并侧栏；中栏=主会话；右栏=子代理会话（出现时左栏收起）。
2. 返回键三档语义：第 1 次关子会话 → 第 2 次关主会话回列表 → 第 3 次回桌面。
3. **手机全流程零回归**：Compact（手机竖屏）与 Medium（手机横屏/折叠屏）保持现有单页流，展示与交互完全不变。

### 1.3 边界（做什么 / 不做什么）

**做：**
- 仅 App 端布局与导航适配（无 bridge 协议变化）。
- 平板三栏布局 + 返回键三档语义 + 复用抽取。
- 平板设备（模拟器/真机）自测 + 手机回归。

**不做（本阶段 / 本期）：**
- 不改 bridge、不改协议、不改服务端投影字段。
- 不做拖拽分栏、不自由调整栏宽（固定比例，见 2.2）。
- 不做多窗口/分屏（split-screen）适配（WindowSizeClass 天然应对，但不专项）。
- 不改任何会话/排队/发送等业务逻辑（仅展示与导航）。

---

## 二、规则细化（核心）

### 2.0 术语与前提

- **栏（Column/Pane）**：平板布局的水平分区。左栏=合并侧栏；中栏=主会话；右栏=子代理会话。
- **主会话**：`parentSessionId == null` 的顶层会话。
- **子代理会话**：`parentSessionId != null` 的会话，其 `parentSessionId` 指向主会话。
- **断点触发**：仅当 `WindowWidthSizeClass == Expanded`（宽 ≥ 840dp）时启用平板三栏；其余宽度走现有手机单页流。

### 2.1 平板判定断点

**结论（推荐）：采用 Compose 官方 `WindowSizeClass`，以 `WindowWidthSizeClass.Expanded`（宽 ≥ 840dp）作为唯一平板触发条件。**

- 官方三档：Compact（<600dp）/ Medium（600–839dp）/ Expanded（≥840dp）。
  依据：[Use window size classes — Android Developers](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)
- **为什么只用 Expanded、不用 Medium**：
  1. 手机横屏宽度多在 600–840dp 区间（Medium）。若在 Medium 就切三栏，**手机横屏也会变成三栏**，违反「手机零回归」铁律。
  2. 折叠屏展开态也常落在 Medium；三栏会与折叠屏交互冲突，风险高。
  3. 三栏需要真实宽度 ≥ 840dp 才有足够空间（左栏 280dp + 中栏 + 右栏）。Medium 的 600–840dp 塞三栏会挤压消息气泡（违背 `docs/ui-mobile-first.md` 移动端字号/布局原则）。
- **兜底策略**：不硬编码 840dp，统一走 `calculateWindowSizeClass(activity)`（material3-window-size-class），避免多窗口/分屏/旋转下失效。
- **灰度开关**：预留 `isTabletLayout` 的单一判定函数（纯函数，可单测），后续若需把 Medium 也纳入三栏，只改一个断点。

### 2.2 三栏布局细节

**布局结构（Row，三栏，Expanded）：**

```
┌─────────────┬──────────────────────┬──────────────────┐
│ 左栏(280dp)  │ 中栏(自适应 weight)   │ 右栏(自适应,可选) │
│ 项目+会话合并 │ 主会话 Conversation    │ 子代理 Conversation│
│ 侧栏         │  (现有组件)           │  (现有组件)        │
└─────────────┴──────────────────────┴──────────────────┘
```

- **左栏（合并侧栏，固定 ~280dp，可收起）**：项目列表（工作区过滤，现 `WorkspaceDrawer` 内容）+ 会话列表（现 `SessionList`/`SessionCard`）自上而下合并为一列；顶部保留设备名/连接状态（现抽屉头部）。点击会话 → 中栏打开该主会话。
- **中栏（主会话）**：复用现 `Conversation` 组件渲染 `currentSessionId` 主会话；含 TopBar 精简版（会话标题/子代理入口/日志）。
- **右栏（子代理会话，出现条件）**：当 `subagentReturnTo != null` 时出现，渲染 `currentSessionId`（即子代理会话）；此时**左栏收起**（隐藏，非删除状态），中栏保持主会话可见。
- **栏宽**：左栏固定 280dp；中/右栏 `Modifier.weight(1f)` 平分剩余宽度。子代理出现时中栏+右栏平分（左栏收起后中栏自动变宽，无需特殊处理）。
- **无主会话时**（`currentSessionId == null`）：只显示左栏（占满或按需居中），中/右栏隐藏——即「回会话列表」态。

### 2.3 返回键三档语义（平板）

**优先级链（平板，Expanded，后注册优先——与现手机 BackHandler 机制一致）：**

| 档位 | 当前状态 | 返回动作 | 对应现有逻辑 |
|---|---|---|---|
| 1 | 子代理会话打开（`subagentReturnTo != null`） | 关闭子会话，回主会话视图（左栏恢复展开） | `closeSession()` 的 `returnTo` 分支 |
| 2 | 主会话打开（`currentSessionId != null && subagentReturnTo == null`） | 关闭主会话，回会话列表（`currentSessionId = null`） | `closeSession()` 的列表分支 |
| 3 | 会话列表（`currentSessionId == null`） | 返回桌面 | 现 `MainScreen` 抽屉展开时 `platformExitApp()` |

**与手机端差异表：**

| 场景 | 手机（现状） | 平板（Expanded） |
|---|---|---|
| 子会话打开时返回 | 关子会话→回主会话 | 关子会话→回主会话（+左栏恢复） |
| 主会话打开时返回 | 关主会话→回列表 | 关主会话→回列表 |
| 列表态返回 | 打开抽屉（项目过滤） | 直接回桌面（三档） |
| 抽屉 | 有（ModalNavigationDrawer） | 无（左栏常驻替代抽屉） |

> 说明：平板的「第 3 档回桌面」与手机「列表态返回=打开抽屉」语义不同——平板项目过滤已常驻左栏，无需抽屉，故列表态返回直达桌面。

### 2.4 复用抽取清单（改动面与风险）

| 可复用块 | 现状位置 | 抽取目标 | 改动面 | 风险 |
|---|---|---|---|---|
| `SessionList`（会话列表，按工作区过滤） | App.kt `SessionList` | 抽取为共享组件，供手机单页 + 平板左栏复用 | 中 | 需保证 `onClick`/`onInterrupt` 回调参数不变 |
| `SessionCard`（会话行，含中断/子代理数） | App.kt `SessionCard` | 已较独立，直接复用 | 低 | 无 |
| `WorkspaceDrawer`（项目/工作区过滤） | App.kt `WorkspaceDrawer` | 抽取其「过滤条目」为共享 `WorkspaceFilter`，供抽屉 + 平板左栏复用 | 中 | 抽屉版依赖 `DrawerState.close()`，需剥离为 `onSelect` 回调 |
| `Conversation`（会话详情，含输入/列表/面板） | App.kt `Conversation` | 已参数化（`client/state/sessionId`），直接复用为中/右栏 | 低 | 需支持同一屏幕渲染 2 个 Conversation（见附录 A.2 数据模型） |
| `TopBar`（标题/子代理下拉/日志） | App.kt `TopBar` | 平板中栏/右栏各自精简 TopBar | 低 | 子代理入口形态见待澄清 4 |
| 导航状态机 | BridgeClient `currentSessionId`/`subagentReturnTo` | **复用现有状态机**，平板只新增「视图层」派生（见附录 A.1） | 低 | 不新增协议/字段 |

### 2.5 手机零影响保证

- **断点隔离**：所有平板分支统一收敛到 `isTabletLayout(widthSizeClass)` 单点判定；`false` 时走**完全现有的 `App`/`MainScreen` 代码路径**（不改原有分支）。
- **回归清单（必过）**：见 9.2。
- **不共享可变状态**：平板三栏是纯「视图层」差异，复用同一 `SessionUiState`，不新增全局布尔/枚举到数据层，避免污染手机路径。

### 2.6 关键指标 / 埋点

| 指标 | 口径 | 埋点 |
|---|---|---|
| 平板布局启用率 | 每次冷启动/旋转判定一次 | `ACTION`：`平板布局切换 enabled/disabled widthDp=...` |
| 返回档位触发 | 每次返回键在平板按下 | `ACTION`：`平板返回档位 level=1/2/3` |
| 子会话开关 | openSubagent/closeSession 在平板的触发 | 复用现有 `打开子代理`/`关闭会话` 埋点，追加 `layout=tablet` 上下文 |
| 手机零回归 | 手机 Compact/Medium 不应出现任何 `layout=tablet` 日志 | 回归断言 |

---

## 三、涉及产品改动

### 3.1 App（composeApp，本期唯一改动面）

- `App.kt`：`App()` 根部接入 `calculateWindowSizeClass`；新增 `TabletMainScreen`（三栏）与 `PhoneMainScreen`（现 MainScreen 改名/薄封装）的分流。
- `App.kt`：抽取 `WorkspaceFilter`/`SessionList`/精简 `TopBar` 复用块。
- `App.kt`：平板返回三档 `BackHandler` 优先级链。
- 无新依赖（`material3-window-size-class` 若未引入则新增，见四）。

### 3.2 Bridge（dsh-remote-control-bridge）

- **无协议变化**（预期）。导航纯客户端视图层，`currentSessionId`/`subagentReturnTo` 已足够（见附录 A.1）。
- 若后续采纳「中栏+右栏双 live 会话」方案（待澄清 1），可能需客户端多会话事件缓存（仍为客户端改动，不涉及协议），届时再标注。

---

## 四、关键技术选型

- **断点判定**：`androidx.compose.material3:material3-window-size-class` 官方 `calculateWindowSizeClass`（若工程尚未引入则新增；需核对当前依赖版本）。
- **布局**：自研三栏 `Row`（左固定 + 中/右 `weight(1f)`），**不用** Material3 `ListDetailPaneScaffold`/`SupportingPaneScaffold`——现有 `Conversation` 是重度自定义组件，套用 Pane Scaffold 会引入不必要的 API 约束；自研 Row 更贴合现有结构、改动更小、风险更低（详见附录 A.3）。
- **导航状态**：复用 `currentSessionId` + `subagentReturnTo`，不引入 Navigation Compose（避免重构现有页面栈，见附录 A.1）。

---

## 五、关键指标（含口径）

见 2.6。

---

## 六、预期收益

- 平板信息密度与操作效率对齐桌面端 IM 体验，减少列表↔会话反复切换。
- 复用抽取降低 App.kt 复杂度（`MainScreen`/`WorkspaceDrawer`/`SessionList` 解耦），顺带服务 detekt LargeClass/LongMethod 收紧路径。

---

## 七、核心功能点（编号可验证）

1. `F1`：Expanded 断点下进入平板三栏布局；Compact/Medium 走手机单页流。
2. `F2`：左栏 = 项目过滤 + 会话列表合并；点会话 → 中栏打开主会话。
3. `F3`：主会话点开子代理 → 右栏展示子代理、左栏收起。
4. `F4`：返回键三档语义（关子会话→关主会话→回桌面）。
5. `F5`：手机全流程零回归（见 9.2）。

---

## 八、核心埋点或日志

见 2.6 表。

---

## 九、验收口径

### 9.1 平板 AVD 创建方案（自测环境）

- 现状：现有 AVD `dsh-test` 为手机 1080×2400（Compact），不满足平板。
- **新建平板 AVD**（优先官方 Pixel Tablet，或自定义 10" 分辨率）：
  ```
  # 推荐方案一：官方平板镜像
  avdmanager create avd -n pixel_tablet -k "system-images;android-34;google_apis;x86_64" -d pixel_tablet
  # 方案二：自定义 10" 分辨率（若无 tablet device 定义）
  avdmanager create avd -n tablet10 -k "system-images;android-34;google_apis;x86_64" \
      --device "Nexus 10"   # 或 -c 自定义 skin 2560x1600
  ```
- 启动后确认横屏宽度 ≥ 840dp（`wm size` / 分辨率换算）；确保 `calculateWindowSizeClass` 返回 Expanded。

### 9.2 专项验收项

| 验收项 | 预期 | 实测 |
|---|---|---|
| 平板三栏显示（Expanded） | 左栏合并侧栏 + 中栏主会话 | 待测 |
| 点子会话 | 右栏出现子会话、左栏收起、中栏保留主会话 | 待测 |
| 返回 1 档 | 关子会话回主会话、左栏恢复 | 待测 |
| 返回 2 档 | 关主会话回会话列表（仅左栏） | 待测 |
| 返回 3 档 | 回桌面 | 待测 |
| 手机回归（Compact/Medium） | 现有单页流、抽屉、返回逻辑逐项不变 | 待测 |

### 9.3 手机回归清单（零回归，逐项）

1. 会话列表 → 点会话 → 会话详情（单页流）。
2. 会话详情 → 子代理下拉 → 子会话 → 返回回主会话 → 返回回列表。
3. 列表态返回 = 打开抽屉（项目过滤）。
4. 抽屉展开返回 = 回桌面。
5. 发送/排队/转盘/日志页/设备页/审批提问弹窗全部原样。

---

## 十、相关文档链接

- `docs/ui-mobile-first.md`：移动端字号/布局基线。
- `docs/ui-navigation-guidelines.md`：页面栈与返回语义原则。
- `docs/decisions/`：重大选型论证（本 PRD 无新重大选型，断点属轻量决策，已论证见 2.1）。

---

## 附录 A：技术方案 + 单测设计

### A.1 导航状态模型（复用 vs 平板专用）

**结论：复用现有 `currentSessionId` + `subagentReturnTo`，平板只加「视图层」派生，不新增数据字段。**

现状状态机（BridgeClient.kt 已核实）：
- `openSession(id)`：`currentSessionId = id`，并清空会话级投影（events/queue/…）。
- `openSubagent(subagentId)`：`subagentReturnTo = 当前主会话 id`，再 `openSession(subagentId)`。
- `closeSession()`：`subagentReturnTo != null` → 清 `subagentReturnTo` 并 `openSession(returnTo)`；否则 `currentSessionId = null`。

三栏映射（纯派生，不改 BridgeClient）：
| 平板栏 | 数据来源 |
|---|---|
| 中栏（主会话） | `subagentReturnTo ?: currentSessionId`（无子会话时=currentSessionId；有子会话时=parent） |
| 右栏（子会话） | `subagentReturnTo != null` 时的 `currentSessionId` |
| 左栏（列表） | 恒渲染（子会话打开时收起） |

**关键约束（需向主对话澄清，见待澄清 1）**：`SessionUiState` 是**单会话投影**（events/queueItems/modelWaiting 等都只属于 `currentSessionId`）。平板若要求「中栏主会话 + 右栏子会话**同时 live**」，则中栏的 events 在子会话打开时会被 `openSession(subagentId)` 清空，中栏只能渲染「静态快照」（标题/状态）而非常 live 消息流。

**三条实现路线：**
- **路线 A（推荐 MVP，改动最小）**：中栏在子会话打开时降级为「主会话锚点卡」（标题+状态+「← 返回主会话」），右栏渲染 live 子会话。零数据模型改动。
- **路线 B（体验最好，改动大）**：客户端引入「多会话事件缓存」（per-session events 常驻），中/右栏双 live。改 `SessionUiState` 或新增旁路缓存，涉及事件/队列/面板的会话隔离重构。
- **路线 C（折中）**：中栏保持 live 主会话，右栏子会话**只读快照**（不含实时输入/流式）。实现夹在 A/B 之间。

> 建议编码阶段先按 A 落地（满足「右栏展示子会话 + 左栏收起」字面诉求），路线 B 留作二期。

### A.2 布局架构（自适应 Scaffold vs 自研三栏 Row）

- **不引入 Navigation Compose / Material3 Pane Scaffold**：现有 `App()` 是手写 `when` 页面栈 + `ModalNavigationDrawer`，引入 Pane Scaffold 需大规模重构且收益低。
- **自研三栏 `Row`**：在 `App()` 的 `keepSessionUi` 分支内，用 `isTabletLayout(widthSizeClass)` 分流到 `TabletMainScreen`（三栏 Row）。`TabletMainScreen` 内部：
  - `Row { LeftPane(280dp, 可收起) ; MainConversation(weight 1f) ; SubagentConversation(weight 1f, 可选) }`
  - `Conversation` 组件已参数化（`client/state/sessionId`），中/右栏直接调用（路线 A 下中栏传主会话快照或 live 主会话、右栏传子会话）。
- **演进路径**：`MainScreen` 现含 `ModalNavigationDrawer + TopBar + SessionList/Conversation`。抽取后拆为 `PhoneMainScreen`（原逻辑）与 `TabletMainScreen`（新三栏），两者共享 `SessionList`/`Conversation`/`TopBar`。

### A.3 返回键 BackHandler 优先级实现

- 沿用现有「后注册优先」机制：在 `TabletMainScreen` 按状态注册三档 BackHandler：
  1. 子会话态：`PlatformBackHandler(enabled = subagentReturnTo != null) { client.closeSession() }`（现 `closeSession` 已正确回父会话）。
  2. 主会话态：`PlatformBackHandler(enabled = currentSessionId != null && subagentReturnTo == null) { client.closeSession() }`（回列表）。
  3. 列表态：`PlatformBackHandler(enabled = currentSessionId == null) { platformExitApp() }`。
- 因为三档 `enabled` 互斥，同一时刻只有一个 handler 生效，无歧义。与手机 `MainScreen` 现有两档 handler 并存但互不影响（由 `isTabletLayout` 分流，同一时刻只渲染手机或平板路径）。

### A.4 单测点清单（纯逻辑，commonTest）

1. **断点判定**：`isTabletLayout(widthSizeClass)` —— Compact→false / Medium→false / Expanded→true（覆盖边界 600/840）。
2. **三栏可见性状态机**：抽纯函数 `tabletPaneState(currentSessionId, subagentReturnTo) -> {leftVisible, midVisible, rightVisible, backLevel}`，覆盖 4 态：
   - (null, null) → 左栏可见、中/右隐藏、backLevel=3
   - (main, null) → 左+中可见、右隐藏、backLevel=2
   - (sub, parent) → 中+右可见、左收起、backLevel=1
3. **复用组件参数矩阵**：`SessionList`/`SessionCard` 在「手机回调 / 平板回调」两组参数下行为一致（onClick/onInterrupt/onSelect 不回归）。
4. **返回档位状态机**：`tabletBackLevel(...)` 与 `closeSession()` 现有分支对拍（子会话回主、主会话回列表、列表回桌面）。

### A.5 设备自测方案

- **手机回归**：真机（华为 HBN-AL00，若在线）或手机 AVD `dsh-test`（1080×2400）跑 9.3 全清单；断言无 `layout=tablet` 埋点。
- **平板**：新建平板 AVD（见 9.1）跑 9.2 全矩阵；`wm size` 确认 Expanded。
- **冒烟**：装机后 `startup-smoke-test`（存活 ≥5s + crash buffer 无本包 FATAL）双设备先过闸门。

---

## 附录 B：待澄清点清单（主对话转用户拍板）

1. **中栏主会话在子会话打开时是否保持 live？**（决定走路线 A/B/C，改动量差别 3~5 倍；推荐路线 A）
2. **断点范围**：仅 Expanded(≥840dp)，还是含 Medium(600–839dp)？（推荐仅 Expanded，保手机横屏零回归）
3. **子会话关闭后是否恢复左栏展开**？（推荐恢复）
4. **子代理入口形态**：复用现有 🤖N 下拉，还是右栏位置常驻「子代理列表」？（推荐先复用下拉）
5. **平板冷启动/重连后是否自动打开最近主会话**（对齐手机 auto-open 语义）？（推荐跟随现有 auto-open 逻辑）
6. **平板是否需要保留顶部全局 TopBar/日志入口**，还是完全三栏无顶栏？（推荐中/右栏各保留精简标题+日志）

---

## 附录 C：实施阶段建议拆分（含并发冲突协调）

- **阶段 0（本阶段）**：PRD + 技术方案 + 待澄清 → 用户拍板（尤其待澄清 1/2）。
- **阶段 1（编码，需等主对话放行）**：`isTabletLayout` 判定 + 断点隔离（纯视图分流，不动数据层）→ 编译 + 手机 AVD 回归 → commit。
- **阶段 2**：抽取 `WorkspaceFilter`/`SessionList` 复用块（不改手机行为）→ 编译回归 → commit。
- **阶段 3**：`TabletMainScreen` 三栏 + 返回三档 → 编译 + 平板 AVD 自测 → commit。
- **并发冲突协调**：本 PRD 仅**新增** `docs/prd/` 文档，不改 Kotlin；编码阶段涉及 `App.kt`（当前有转盘/发送/中断等并发改动），需在主对话的 worktree 隔离下进行，避免与其它功能域子代理共享未提交文件。建议编码阶段用 `git worktree` 单独分支，合并时解决 `App.kt` 冲突。
