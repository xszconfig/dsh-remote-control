# 输入区改版（输入框 + 操作条）PRD

> **一句话总结**：把会话底部输入区重构为「**输入框上方入口列表 + 输入框撑满一整行 + 下方按钮行**」结构；输入框上方是**可扩展入口列表**（当前仅「技能」一个入口，点开浏览电脑端全部技能 + 可搜索）；输入框行内右侧内嵌**上下文窗口环形进度**（占用百分比，点击弹分类 Token 明细）；按钮行从左到右固定三项——**选择模型**、**终止循环**（未运行置灰、运行中红色可点）、**发送消息**（最右核心，输入非空即可点、发送后自然置灰）——对齐 DSH Web 的模型选择 / 技能目录 / 上下文占用交互，把「当前用什么模型、电脑端有哪些技能、上下文还剩多少」从桌面端专属能力下沉到手机遥控面。

---

## 一、需求背景

### 1.1 痛点

本项目是**手机遥控桌面 Coding Agent**（Android 优先）。当前底部输入区（`ConversationComposer`，`App.kt` L1835–1951）只有：一个输入框 + 「运行中才渲染」的红色终止圆钮 + 一个始终可点（空输入点击 no-op）的发送圆钮，全部挤在同一行。存在三处能力缺口：

1. **看不见上下文占用**：Agent 跑长任务时上下文逼近窗口上限（触顶→压缩/失败），用户只能在桌面端 DSH Web 看到「上下文已占用百分比」，手机端完全无感知，只能事后靠报错发现。
2. **不能切模型**：桌面端可随时切换 provider/model/reasoning effort，手机端完全没有入口——遇到「该模型不合适/上下文太小/要换 reasoning 档」只能回桌面操作，遥控体验断裂。
3. **终止/发送两态不清晰**：终止按钮在未运行时「整个消失」（用户找不到），发送按钮空输入时也能点但点了没反应（无可用性反馈）。

### 1.2 对标依据（DSH Web 现状）

> 事实均出自 DSH checkout 内 `@deepseek-ai/dsh-*` 包源码（只读调研，见附录 A/B），无臆造。

- **上下文占用环**：DSH Web 的 `ContextMeter` 组件（`dsh-client-ui-conversation`）正是「**发送按钮旁的环形进度**」，数据来自 `contextPressure` 投影；点击打开 `contextBreakdown` 的启发式组成面板（**系统提示 / 工具 / 对话**三类）。本 PRD 的手机端环形进度即对齐它。
- **模型选择**：DSH Web 的 `ModelSelect` 组件（`dsh-client-ui-model-selection`）在 composer 提供「模型 seat」，两级菜单：provider 分组 → 模型 → 该模型的 reasoning effort 档位。选择经 `session.selectModel` 提交。

### 1.3 边界（做什么 / 不做什么）

| 做 | 不做（v1） |
|---|---|
| 输入框在上、操作条在下的两行布局 | 不做输入框内富文本/图片附件/语音（现状即纯文本） |
| 操作条四项：模型入口 / 上下文环 / 终止 / 发送 | 不做 provider 配置页、API Key 管理（桌面端职责） |
| 模型切换入口（选择后生效） | 不做「会话创建时」模型选择、子代理会话独立选模型（对齐 DSH Web 限制） |
| 上下文占用环形进度 + 分类明细弹窗 | 不做历史占用曲线图、精确计费口径（占用是近似参考值） |
| 终止按钮两态（灰/红）+ 发送按钮两态（可点/置灰） | 不改中断语义本身（沿用现有 clear/keep 二选一弹框） |

---

## 二、规则细化（核心）

### 2.0 术语与前提

| 术语 | 定义 |
|---|---|
| **入口列表** | 输入框**上方**的一行横向 chips/按钮容器，**数据驱动可扩展**（入口由配置/状态定义），当前仅「技能」一个入口 |
| **按钮行（操作条）** | 输入框**下方**的一行横向工具条，三项从左到右固定：选择模型、终止循环、发送消息 |
| **agentRunning** | 现有终止/运行态判定信号：`sessions.firstOrNull{it.id==sessionId}?.status == "running" \|\| modelWaitingSince != null`（`App.kt` 现 `Conversation.kt`） |
| **输入非空** | `input.trim().isNotEmpty()`（与现有发送 onClick 的空值判定一致） |
| **上下文占用百分比** | `projectedTokens / contextWindow × 100%`，向上 clamp 至 100%（对齐 Web `contextOccupancy()`） |
| **当前模型** | 会话最近一次记录的选择（provider/model/reasoningEffort），无记录回退部署默认 |
| **技能目录** | 电脑端 DSH 全部技能（`ctx.skills.list()` 全局层，name=kebab-case id + description + whenToUse） |

### 2.1 布局

- **三行结构**：`Column` 包 `入口列表行(输入框上方)` + `输入框行` + `按钮行(输入框下方)`。
- **入口列表（输入框上方）**：横向 chips/按钮容器，**数据驱动可扩展**——入口由配置/状态定义（如 `List<ComposerEntry>`），当前仅「技能」一个入口（点击弹技能面板，见 2.6）；后续加新入口只增数据项、不改容器结构。
- **输入框行**：输入框**从左到右撑满一整行**（`fillMaxWidth`）；行内右侧 `trailingIcon` 内嵌**上下文进度环**（约 36dp，点击弹分类 Token 明细，见 2.3）。
- **按钮行（输入框下方）从左到右固定顺序**：① 选择模型 → ② 终止循环 → ③ 发送消息（最右）。
- 输入框样式（`OutlinedTextField` 圆角 22dp、蓝边、`maxLines=4`、placeholder「发指令给DeepSeek Harness」）**保持不变**；按钮全部下沉到按钮行、上下文环移入输入框 trailingIcon、技能入口上移到入口列表。

### 2.2 切换模型入口

- **入口形态**：显示当前模型名（短标签，如 provider 展示名 + 模型名，过长截断），右侧带下拉/切换指示；点击弹出**模型选择弹窗（半屏 Sheet 或对话框，移动端优先）**。
- **弹窗内容**（对齐 `SessionModels` wire）：
  - 按 provider 分组（`ModelProviderGroup[]`），组内列出模型（`ModelCatalogModel[]`：`id/name/description`）。
  - 选中某模型后，若该模型带 `reasoning` 元数据（`efforts/defaultEffort`），进一步列出 effort 档位供选。
  - provider 级失败（`ModelCatalogFailure[]`）在该组内联展示错误，不影响其它可用组（对齐 Web）。
- **当前项高亮**：以 `SessionModels.current`（provider/model/reasoningEffort）为准；若 current 不在目录组内（目录是 advisory），入口显示「选择模型」占位，不合成过期行（对齐 Web 语义）。
- **生效方式（范围与时机）**：**下一步生效**——选择只在「下一次 prompt 组装边界」被快照，**正在运行的 step 保留其组装时的选择**，绝不打断当前推理；选择只有被后续请求消费后才持久化（`installModelSelection` 语义，见附录 B）。即：**仅影响新消息/下一轮，不影响已发出的消息与当前推理**。
- **持久化（已拍板）**：切换**仅对当前会话生效，不保存为部署默认**（不调用 `saveDefaultModelSelection`，避免「切一次全局改默认」的副作用）；选择仍会被该会话后续消费它的请求记录为 durable（`installModelSelection` 语义）。
- **routable 门控**：`SessionModels.routable == false`（当前 provider 无 adapter 服务）时，输入框置灰并显示提示（对齐 Web 的 composer block）；`routable == null`（未加载/加载失败）**不**置灰（防慢 host 锁死可用输入框）。

### 2.3 上下文窗口环形进度

- **展示**：环形进度（小尺寸圆环）内/旁显示**占用百分比**（整数，clamp 0–100%）。参考 Web：**无 provider 上报 pressure 且无路由容量时，什么都不渲染**（占位空状态，不显示 0%）。
- **口径（谁统计）**：**服务端投影权威**（铁律 6）。分子 = `contextPressure.projectedTokens`（下一次请求的 prompt 成本预测），分母 = `contextPressure.contextWindow`（最新 `request/context` 记录的路由容量）。客户端**只渲染、不推算**百分比。
- **更新频率（机制）**：投影是**事件驱动推送**（`sessionProjections` seam 的 change feed，每 committed event 触发），非轮询。手机端由 bridge 订阅投影变更后推 `context_usage`（见附录 C）；由于 `projectedTokens` 只在「内容落盘/下一请求上报 usage」时变化、流式期间 `pressureTokens` 保持不变（输出不计入），**天然低频**，无需定时器。
- **点击弹窗 —— 分类 Token 明细**：弹窗展示**每种类别内容的 Token 数量与占比**，类别对齐 DSH Web 实际三类（`contextBreakdown` 投影，**不是** system/user/assistant/tool 四类）：
  - **系统提示 system**（`systemTokens`：最新请求 envelope 的 system prompt）；
  - **工具 tools**（`toolsTokens`：最新请求 envelope 的 tool schema）；
  - **对话 conversation**（`messageTokens`：当前模型可见的对话 surface）。
- **分类口径说明（重要）**：DSH Web 把 **user 与 assistant 合并进「对话 conversation」**，tool 结果也属于对话 surface；「工具」专指 tool schema。三类均用 meter 的**固定启发式**（4 字符/token + 结构开销），**三者之和 ≠ 占用分子**（`projectedTokens` 是 provider 锚定的），故弹窗标题标注「近似组成」而非「总计」，避免用户误解（对齐 token-meter README「present as approximate composition, never as a total」）。
- **百分比是近似参考值**：切换模型会把新容量与上一路由的采样短暂配对，直到下一请求上报 usage。明确标注「参考值，非计费/门控输入」。

### 2.4 终止按钮

- **始终存在**（不再条件渲染）。
- **两态判定信号：复用现有 `agentRunning`**（`App.kt` L1895–1896：`status=="running" || modelWaitingSince!=null`），**不新增信号源**：
  - `agentRunning == true` → **红色、可点**（`containerColor = error`，现状红色）；
  - `agentRunning == false` → **灰色、不可点**（`enabled=false` + 灰态容器色）。
- **点击行为沿用现状**：`queuedCount > 0` 时弹 `InterruptConfirmDialog`（终止并清空 / 仅终止保留 / 取消）；`queuedCount == 0` 直接 `interrupt(sessionId, "clear")`（`App.kt` L1898–1909）。**不改中断语义**（历史决策④）。

### 2.5 发送按钮（最右 · 核心）

- **位置**：操作条最右，48dp 圆钮（现状尺寸/图标不变）。
- **可点条件**：`input.trim().isNotEmpty()`（输入非空即可点，**不置灰**）。
- **置灰条件**：输入为空（`trim()` 后为空）时置灰不可点；**发送点击后立即置灰**。
- **✅ 已拍板（原铁律 14 冲突点，用户确认）**：「发送后立即变置灰」与现有「输入框立即清空」（IM 模式既定决策⑤）取**解 A（自然置灰）**：

  | 解 | 定义 | 结论 |
  |---|---|---|
  | **解 A（已拍板）** | 可点 = `trim(input) 非空`；发送点击后沿用现有「立即清空」（`onInputChange("")`，`App.kt` L1926）→ 输入变空 → 按钮**自然立即变灰**。**不新增任何「提交锁」状态**。 | 与「输入框立即清空」完全自洽；与 IM 模式「可连续发多条」一致（清空后用户再输入即恢复可点）。 |
  | ~~解 B~~（弃） | 额外引入「发送中/提交锁」瞬时置灰（如直到送达回显或固定 200ms）。 | 与「输入非空即可点」矛盾、与「可连续发多条」IM 模式冲突，**不做**。 |

- **现状差异标注**：现状发送按钮「始终可点、空输入点击 no-op」→ 改为「空输入置灰不可点」。这是交互变更（去掉无反馈的可点态），非逻辑冲突。

### 2.6 技能面板（新功能）

- **入口**：输入框**上方入口列表**的「技能」chip（入口列表数据驱动可扩展，当前仅此一项），点击弹出**半屏技能面板**（移动端优先）。
- **数据面**：**服务端投影权威**（铁律 6）——bridge 连接即下发 `skills_update`（全量技能目录）+ `skills/change` 时增量广播；客户端**零本地枚举**、只渲染。wire `SkillWire { name, description, whenToUse? }`，`name` 即 DSH 的 kebab-case id（DSH 无独立显示名）。
- **数据源调研结论（已拍板 #42）**：DSH 暴露**官方技能目录接口 `ctx.skills.list()`**（`@deepseek-ai/dsh-skill` 的 `SkillRegistry`），返回全部胜出 `SkillSummary`（name/description/whenToUse/invocation/source），按名排序——这正是 DSH Web 技能面板（`dsh-client-ui-skill` → `skill.list` RPC）所用；shipped `dsh-skill-filesystem` 读取 `~/.agents/skills/*/SKILL.md` frontmatter + 内置技能。**故无需「扫描本机技能目录」回退**，桥直接 `ctx.get('skills').list()` 即得「电脑端全部技能」，与 Web 一一对应。
- **列表展示**：每项**技能名（加粗）+ 描述**（次要色）；`whenToUse` 有则附一行小字；**可搜索**（顶部搜索框按 name/description 过滤）；空列表/无匹配显示空态文案。
- **范围**：仅浏览（只读），**不做技能调用**（技能调用仍走发送框的 `/技能名` 斜杠通道，与 Web composer 同链，本面板不承担）。
- **实时性**：桌面端增删/改技能 → `skills/change` 失效通知 → bridge refetch `ctx.skills.list()` 全量广播 `skills_update` → 手机面板即时反映。

---

## 三、涉及产品改动

### 3.1 App（composeApp）

| # | 改动 | 现状 → 目标 | 备注 |
|---|---|---|---|
| 1 | **输入区三行布局（重构）** | `Row(输入框+按钮)` 单行 → `Column(入口列表行 + 输入框撑满 + 按钮行三项)` | `ConversationComposer` |
| 2 | **模型入口 + 选择弹窗** | 无 → 按钮行第 1 项，弹窗选 provider/模型/effort，`set_model` 命令下发 | 数据源 = bridge `models`（附录 C） |
| 3 | **上下文环形进度 + 明细弹窗** | 原操作条第 2 项 → **移入输入框 `trailingIcon`（行内右侧，约 36dp）**，渲染 `contextUsage.percent`，点开分类明细 | 数据源 = bridge `contextUsage` |
| 4 | **技能面板（新增）** | 无 → 输入框上方入口列表「技能」chip，弹半屏面板浏览全部技能（name+description+whenToUse）+ 可搜索；入口列表数据驱动可扩展 | 数据源 = bridge `skills_update`（全局） |
| 5 | **终止循环按钮常驻两态** | 仅 running 渲染红色圆钮 → 按钮行第 2 项常驻；running 红可点 / 非 running 灰不可点 | `enabled = agentRunning` |
| 6 | **发送按钮两态** | 始终可点（空输入 no-op）→ 按钮行第 3 项（最右）；非空可点、空/发送后置灰 | 置灰条件见 2.5 解 A |
| 7 | **协议模型（Protocol.kt 新增 wire 类型）** | 无 → `SessionModelsWire`/`ContextUsageWire`/`SkillWire` 等 + `models_update`/`context_usage`/`skills_update` 事件 + `set_model` 命令 | 详见附录 C |
| 8 | **状态字段（SessionUiState 新增）** | 无 → `models`/`contextUsage`（每会话）、`skills`（全局） | `BridgeClient.kt` `SessionUiState` |

### 3.2 Bridge（dsh-remote-control-bridge）

- **必改**：新增下发 `models`（模型目录 + 当前选择 + routable）、`contextUsage`（占用总量 + 分类 + 占比）、`skills_update`（全部技能目录，`ctx.skills.list()` 官方接口 + `skills/change` 增量广播），新增接收 `set_model` 命令（详见附录 C）。
- **版本**：`BRIDGE_VERSION` 0.16.0 → 0.17.0（新增 `skills_update`），协议向后兼容（见附录 C 兼容策略）。

---

## 四、关键技术选型

> 本改版**不涉及**重大技术选型（模型目录/上下文占用/切换均复用 DSH 官方既有能力与 wire 形状，1:1 映射到 bridge；无第三方库、无新协议方向）。两处产品取舍已由用户拍板：**2.5 发送置灰 = 解 A（自然置灰）**、**2.2 模型切换 = 仅当前会话（不存默认）**，非架构选型，故不落 `docs/decisions/`。

---

## 五、关键指标（含口径）

| 指标 | 定义与口径 |
|---|---|
| **上下文占用可见率** | 有 provider 上报 pressure 且已知容量的会话中，手机端环形进度正确展示的比例（目标 → 100%，对齐 Web 的「无数据不渲染」） |
| **占用推送时延** | 投影变更（内容落盘/下一请求上报）→ 手机端 `context_usage` 到达的时延（对齐铁律 7：100~300ms） |
| **模型切换成功率** | `set_model` 成功回执数 / 切换请求数（`resolveCallConfig` 校验失败计为失败；目标 → 100% 对合法选择） |
| **切换生效正确性** | 切换后「正在运行的 step 不被打断、下一轮使用新模型」的达成率（目标 100%；口径 = 切换后下一步的 `request/header` 为新选择） |
| **终止/发送两态准确率** | 终止灰/红态、发送可点/置灰态与 `agentRunning`/`input.trim()` 的匹配率（目标 100%，无「红却点不动」「有内容却置灰」） |

---

## 六、预期收益

- **上下文透明**：手机端可实时看到占用百分比与分类明细，长任务「触顶前」即可感知并手动处理（切更大窗口模型/压缩），减少静默触顶失败。
- **模型即换**：遥控面补上「切模型/切 effort」咽喉能力，无需回桌面，协作闭环更完整。
- **操作确定性**：终止常驻两态、发送两态，消除「按钮消失 / 点了没反应」的可用性困惑。
- **架构一致**：全程服务端投影权威（铁律 6）、事件推送（铁律 7）、移动端优先（铁律 9），复用 DSH 官方 wire 形状，零新依赖。

---

## 七、核心功能点（编号可验证）

- **F1** 输入区两行布局：输入框在上、操作条在下，四项顺序固定（模型入口→上下文环→终止→发送）。
- **F2** 模型入口展示当前模型（含「选择模型」占位态），点击弹模型选择弹窗（provider 分组 + effort 档 + 失败内联）。
- **F3** 模型切换：`set_model` 下发、`resolveCallConfig` 校验、下一步生效、运行中不打断、失败保留旧选择。
- **F4** 上下文环形进度：渲染 `projectedTokens/contextWindow` 百分比，无数据不渲染。
- **F5** 上下文明细弹窗：三类（系统提示/工具/对话）数量与占比，标注「近似组成」。
- **F6** 终止按钮常驻两态：`agentRunning` 红可点 / 非 running 灰不可点，点击行为沿用现状（含排队二选一弹框）。
- **F7** 发送按钮两态：`trim(input)` 非空可点、空/发送后置灰（解 A）。
- **F8** bridge 消息：`models` / `context_usage` 下发 + `set_model` 命令（附录 C），向后兼容。
- **F9** 埋点：见第八节。

---

## 八、核心埋点或日志

App 端沿用 `ConnLog`，新增 `MODEL` / `CTX` tag（可被 `/remote/phone-logs` 回传）：

| 埋点 | 字段 |
|---|---|
| 模型入口点击 | `sessionId`、当前 `provider/model`、是否占位态 |
| 模型切换 | `sessionId`、`from{provider,model,effort}`、`to{...}`、结果（成功/`resolveCallConfig` 失败码）、是否保存默认 |
| 上下文环渲染 | `sessionId`、`projectedTokens`、`contextWindow`、`percent`；无数据时记 `absent`（缺 pressure/缺容量） |
| 上下文明细弹窗 | `sessionId`、三类 token（system/tools/message）、打开次数 |
| 终止点击 | `sessionId`、`agentRunning`、`queuedCount`、`mode`（clear/keep/cancel）——沿用现有 `ACTION` 中断埋点 |
| 发送点击/置灰 | `sessionId`、`inputLen`、`trimLen`、是否因空输入置灰 |

服务端（bridge）tag：沿用 `MODEL`（新增）/`CTX`（新增），`set_model` 路由与 `context_usage` 推送各记一条。

---

## 九、验收口径

### 9.1 真机验收矩阵（华为 HBN-AL00）

| 验收项 | 预期 | 实测结论 |
|---|---|---|
| 两行布局 | 输入框在上、操作条在下，四项顺序正确 | |
| 模型入口 | 显示当前模型；点开弹窗按 provider 分组、选中模型后可再选 effort | |
| 模型切换 | 选中后下一步使用新模型；运行中切换不打断当前 step；失败保留旧选择并提示 | |
| 上下文环 | 有数据时显示百分比；无数据时不渲染；切换会话环值不串扰 | |
| 上下文明细 | 点开显示系统提示/工具/对话三类数量与占比，标注近似组成 | |
| 终止两态 | 未运行灰色不可点、运行中红色可点；点击走现状二选一弹框 | |
| 发送两态 | 空输入置灰；输入非空可点；点击后输入清空、按钮立即置灰 | |
| 向后兼容 | 旧 bridge（无 `models`/`context_usage`）连接时，模型入口与上下文环隐藏、其余功能不回归 | |

### 9.2 专项验收项

- **会话隔离**：切换会话后模型入口、上下文环均按 `sessionId` 隔离刷新，不串扰。
- **不打断推理**：运行中切换模型 → 当前 step 继续、下一轮才生效（观察 `request/header`）。
- **近似口径**：明细弹窗三类之和 ≠ 占用分子时，界面明确标注「近似组成」，不误导为总计。
- **单测**：新增 wire 反序列化单测（`Protocol` 新类型）、`agentRunning`/`input.trim()` 两态判定单测（对齐 `SessionUiStateTest`/`ProtocolTest` 现状）。

---

## 十、相关文档链接

- 现有输入区实现：`composeApp/src/commonMain/kotlin/com/daniel/dshremote/App.kt`（`ConversationComposer` L1835–1951、`agentRunning` L1895–1896、发送 onClick L1919–1940）
- 状态与事件处理：`composeApp/src/commonMain/kotlin/com/daniel/dshremote/BridgeClient.kt`（`SessionUiState` L118、`sendMessage` L746、`interrupt` L820、`handleAgentStatus` L1354、`handleModelWaiting` L1206）
- 协议定义：`composeApp/src/commonMain/kotlin/com/daniel/dshremote/protocol/Protocol.kt`；bridge `src/protocol.ts`（`BRIDGE_VERSION` 0.13.0）
- 铁律与决策：`AGENTS.md`（铁律 6 服务端投影 / 7 100~300ms 送达 / 9 移动端优先 / 14 语义冲突二次确认；历史决策⑤ 发送状态=IM 模式）
- 移动端 UI 规范：`docs/ui-mobile-first.md`
- 同模板参考：`docs/prd/主动通知与审批提醒PRD.md`
- 技术调研来源（DSH checkout，只读）：`@deepseek-ai/dsh-token-meter`、`@deepseek-ai/dsh-session-stats`、`@deepseek-ai/dsh-session-projection`、`@deepseek-ai/dsh-client-ui-model-selection`、`@deepseek-ai/dsh-agent-default-model`、`@deepseek-ai/dsh-llm`、`@deepseek-ai/dsh-host-apiproxy`（见附录 A/B/C）

---

## 附录 A：DSH Web 上下文窗口实现（调研结论）

> 只读调研，来源为 DSH checkout `@deepseek-ai/` 下包源码；禁止 lsp_query；未 commit。

### A.1 数据源服务与字段

上下文窗口由 **`@deepseek-ai/dsh-token-meter`** 提供，经 **`@deepseek-ai/dsh-session-projection`** 的投影 seam 投递。`ctx.tokenMeter` 是单例服务，每会话独立折叠完整持久化日志，注册三个投影单元（`SessionProjectionMap`）：

| 投影键 | 字段 | 含义 |
|---|---|---|
| `tokenUsage` | `uncachedInputTokens / outputTokens / cacheReadTokens / cacheWriteTokens` | 全日志累计 provider 用量（四桶不相交，reasoning 已含在 outputTokens） |
| `contextPressure` | `pressureTokens? / projectedTokens? / contextWindow?` | 占用率显示：`pressureTokens`=最近请求 prompt 用量；`projectedTokens`=**下一次请求的 prompt 成本**（采样 + surface 变动的启发式重计价）；`contextWindow`=最新 `request/context` 记录的路由容量 |
| `contextBreakdown` | `systemTokens / toolsTokens / messageTokens` | 启发式组成：系统提示 / 工具 schema / 对话 surface |

### A.2 口径关键点

- **占用分子用 `projectedTokens`**（不是 `pressureTokens`）：`projectedTokens` 把「压缩（compaction）不自己上报 usage」的坑补上——压缩会让下一次请求的 prompt 立即变化，而 `pressureTokens` 要等下一个完整轮次才更新。Web `contextOccupancy()`（`dsh-client-ui-conversation` 的 `StatsLine`）即 `projectedTokens / contextWindow`，向上 clamp。
- **分母（容量）来源**：adapter 的 `ctx.llm.resolveModelInfo().context.contextWindow`（即 `request/context` 记录）。
- **三类组成是近似**：固定 4 字符/token 启发式 + 结构开销；`systemTokens + toolsTokens + messageTokens` **不等于** `projectedTokens`（CJK 文本/JSON schema 会被系统性低估）。Web 明确「按近似组成呈现，绝不当作总计」。
- **占用是参考值，非门控/计费**：切换模型会把新容量与旧路由采样短暂配对；`pressureTokens` 是「上一次请求」而非「此刻 surface」。harness 内部决策（压缩）读的是 `measure()`，不读该占用。

### A.3 更新机制（事件驱动，非轮询）

投影 seam（`dsh-session-projection`）提供三条投递通道：**registry snapshot**（连接时）、**change feed**（每 committed event、每 client-visible 单元一次 `onChanged`）、以及**每个投影 carrier**（api-proxy history tail page 的 `projections` block、`session/projection` push frame、session list rows）。Web 端靠「tail page 基线 + live push frame + higher-seq-wins store + JSON checkpoint」更新，无轮询。

**对手机端的映射**：bridge 已用 `ctx.get('sessionProjections').snapshot(session).values` 读 `sessionStats`/`tokenUsage`/`todos`/`goal`（`core.ts` L381 起）。上下文占用只需同法读 `contextPressure`/`contextBreakdown`，并订阅 change feed 推 `context_usage` 事件（见附录 C）。

---

## 附录 B：DSH 模型列表与切换（调研结论）

### B.1 可用模型列表从哪读

Web 走 apiproxy RPC `session.models`，其 host 侧实现 `buildModelCatalog(ctx)`（`dsh-host-apiproxy/api-proxy.js` L186–233）依次调用：

- `ctx.llm.listProviders()` → `LlmProviderInfo[]`（`{ id, name, ... }`）
- `ctx.llm.listModels(provider.id)` → `LlmModelInfo[]`（`{ id, name, description? }`）
- `ctx.llm.resolveModelInfo(provider.id, model.id)` → `{ context?: { contextWindow }, reasoning?: { efforts, defaultEffort } }`（用于 effort 档位与容量）

wire 形状（`dsh-host-apiproxy/api/sessions.d.ts` L96–166）：

```
ModelSelection      = { provider, model, reasoningEffort? }
ModelCatalogModel   = { id, name, description?, reasoning?: { efforts: ModelReasoningEffort[], defaultEffort? } }
ModelProviderGroup  = { id, name, models: ModelCatalogModel[] }
ModelCatalogFailure = { id, name, message }
SessionModels       = { current: ModelSelection, routable: boolean, groups: ModelProviderGroup[], failures: ModelCatalogFailure[] }
```

### B.2 当前模型怎么定

`selectionFor(agent).current`（`api-proxy.js` L866–896）：优先 `agent.session.requestHeader()?.config`（会话最近记录的 provider/model/reasoningEffort），无记录回退 `defaults.defaultModelSelection()`（即 `ctx.agentDefaultModel.currentSelection()`）。**bridge 现状已复刻只读版** `modelSelectionOf(agent)`（`core.ts` L224–240，setter no-op）。

### B.3 切换模型的正确调用面（in-process）

Web 走 apiproxy RPC `session.selectModel`（`api-proxy.js` L1905–1943），host 侧三步：

1. `ctx.llm.resolveCallConfig({ provider, model, reasoningEffort? })` —— 校验 + 规范化（失败 → `model-unavailable`）；
2. `selectionFor(agent).current = selected` —— 经 `installModelSelection(agent.ctx, selection)` 在**下一步 prompt 组装边界**快照生效；
3. `defaults.saveDefaultModelSelection(selected)` —— best-effort 存为部署默认（失败仅告警，不阻断会话内生效）。【Web 默认保存；本方案已拍板不保存，见附录 C.4】

**生效时机**：`installModelSelection`（`dsh-agent/model-selection`）的注释明确——prompt 组装先快照所选模型再委托，**并发切换只在后续 step 生效，不撕裂当前请求**；选择只有被「消费它的请求」记录后才是 durable。

**bridge 现状差距**：bridge 已在 resume 时 `installModelSelection(agentCtx, modelSelectionOf(agent))` 挂载，但 `modelSelectionOf` 每次新建 ref 且 setter 为空。要支持切换，需改为「per-agent 可变选择 ref（`WeakMap<Agent, ModelSelectionRef>`，同 apiproxy 的 `selections`）」，并实现 `set_model` → `resolveCallConfig` → `ref.current = selected`（**不保存默认，已拍板**，见附录 C.4）。

### B.4 官方 client 组件参考

`@deepseek-ai/dsh-client-ui-model-selection` 的 `ModelSelect` 组件（`lib/types/client/ModelSelect.d.ts`）与 `ModelDirectory`（`directory.d.ts`）：两级 Model/Effort 菜单、`current`/`routable`/`groups`/`failures`/`status` 状态、`load()`/`select()` 方法、generation 计数防旧响应覆盖新响应。手机端选择弹窗可 1:1 借鉴其目录结构与失败内联策略，但 UI 落地为 Compose 移动端形态。

---

## 附录 C：bridge 新增消息设计（仅设计，不实现）

### C.1 兼容策略总纲

- 客户端 `BridgeJson` 已 `ignoreUnknownKeys = true`；所有新字段**可选**（`?`），旧 bridge 不推 → 手机端隐藏模型入口与上下文环、其余功能零回归；新 bridge 对旧 App 只多推字段（旧 App 忽略）。
- `BRIDGE_VERSION` 0.13.0 → 0.14.0。

### C.2 下发：`models`（列表 + 当前）

新增 wire 类型（对齐 `SessionModels`，去掉客户端不用的 `failures` 细节可选保留）：

```ts
export interface ModelReasoningEffortWire { id: string; name: string; description?: string }
export interface ModelCatalogModelWire {
  id: string; name: string; description?: string
  reasoning?: { efforts: ModelReasoningEffortWire[]; defaultEffort?: string }
}
export interface ModelProviderGroupWire { id: string; name: string; models: ModelCatalogModelWire[] }
export interface ModelSelectionWire { provider: string; model: string; reasoningEffort?: string }
export interface SessionModelsWire {
  current: ModelSelectionWire | null          // null = 未加载/占位
  routable: boolean | null                    // null = 未加载
  groups: ModelProviderGroupWire[]
  failures: { id: string; name: string; message: string }[]
}
```

**携带位置**（三选一或组合，推荐 ①+②）：
1. **`history` 响应新增 `models?: SessionModelsWire`**（订阅/切会话时随历史一并发下，复用现有 `subscribe → history` 链路，含冷会话）；
2. **新增事件 `models_update`**（`{ type:'models_update', sessionId, models: SessionModelsWire }`，目录变更/`llm/adapters-updated`/`settings/document-updated` 时推，对齐 Web 的 refetch 触发）；
3. `hello` 携带全局模型目录（session-independent，`llm.models` 同源）+ 当前会话 current（可选，首屏加速；非必须）。

### C.3 下发：`contextUsage`（总量 + 分类 + 占比）

```ts
export interface ContextUsageWire {
  contextWindow?: number          // 容量，缺省 = 无 adapter 上报
  pressureTokens?: number         // 最近请求 prompt 用量
  projectedTokens?: number        // 占用分子（下一次请求成本）
  percent?: number                // 服务端算好（clamp 0-100），客户端只渲染（铁律 6）
  breakdown?: {                   // 启发式组成，缺省 = 无 breakdown 投影
    systemTokens: number
    toolsTokens: number
    messageTokens: number
  }
}
```

- **携带位置**：`history` 响应新增 `contextUsage?: ContextUsageWire`（进入会话即显）；新增事件 `context_usage`（`{ type:'context_usage', sessionId, usage: ContextUsageWire }`）做实时推送。
- **推送触发**：bridge 订阅 `ctx.sessionProjections.onChanged`（change feed）→ 该会话 `contextPressure`/`contextBreakdown` 引用变化时重读 snapshot 并推 `context_usage`（与现有 `todos_update`/`goal_update` 的「落库后重读广播」同构）。**无需轮询**；`projectedTokens` 流式期间不涨（输出不计入），天然低频。

### C.4 命令：`set_model`

```ts
export interface CmdSetModel {
  type: 'set_model'
  sessionId: string
  provider: string
  model: string
  reasoningEffort?: string
}
```

- **bridge 处理**：`resolveCallConfig` 校验 → 写 per-agent 可变 selection ref（`WeakMap`）→ 回 `models_update`（含新 current）给手机端确认。**已拍板：不调用 `saveDefaultModelSelection`（仅当前会话生效，不存默认）**。
- **响应/错误**：失败复用现有 `{ type:'error', code:'model_unavailable', message }`；成功以 `models_update`（current 更新）体现（对齐「服务端投影为准」），**不新增专用 ack 类型**（保持协议瘦）。

### C.5 下发：`skills_update`（全部技能目录）

```ts
export interface SkillWire {
  name: string        // kebab-case id（DSH SkillSummary.name，无独立显示名）
  description: string
  whenToUse?: string  // 可选额外路由指引
}
export interface EvSkillsUpdate {
  type: 'skills_update'
  skills: SkillWire[]  // 全局全部技能（ctx.skills.list()），无 sessionId
}
```

- **数据源**：`ctx.get('skills').list()`（`@deepseek-ai/dsh-skill` 官方 SkillRegistry，对齐 Web `skill.list` RPC），全局层全部技能按名排序；`name` 即 id。
- **携带位置/触发**：连接即下发（随 `hello`/`server_boot` 之后）+ 订阅 `skills/change` 失效通知 → refetch 全量广播（桌面端增删技能实时反映）。无 `skills` 服务（headless 部署）→ 空数组，客户端显示空态。

---

*（本 PRD 事实依据均来自已读代码与 DSH 包源码，未臆造新事实；产品取舍已由用户拍板——2.5 发送置灰 = 解 A 自然置灰、2.2 模型切换 = 仅当前会话不存默认、#42 技能面板数据面 = `ctx.skills.list()` 官方接口无回退——已回填至正文。）*
