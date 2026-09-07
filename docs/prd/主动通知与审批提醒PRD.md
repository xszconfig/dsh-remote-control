# 主动通知与审批提醒 PRD

> **一句话总结**：在权限审批 / 提问（ask_user_question）到达、以及异步会话产生「明确交付」时，主动向用户发系统通知召回客户端处理，但严格遵循「用户正在浏览该会话时不打扰」的硬规则——把「Agent 需要你」的召回从「被动开屏查看」升级为「主动推送召回」，补齐对标产品（Claude Code / Gemini）都没做深的「审批推送」咽喉能力。

---

## 一、需求背景

### 1.1 痛点

本项目是**手机遥控桌面 Coding Agent**（Android 优先、中文、深度遥控单任务）。核心人机协作模型是：桌面 Agent 在跑，用户拿着手机「遥控 / 监督 / 审批」。当前 App 只在**前台打开对应会话**时才有提醒（页内弹窗 + 振动），一旦锁屏或切到别的会话，用户对「Agent 正在等你」一无所知，只能被动地反复开屏查看。

两类高频损失场景：

1. **审批/提问被晾着**：权限审批或 `ask_user_question` 到达时，Agent 会**卡住等裁决**。用户若锁屏或正在看别的会话，完全无感知 → 协作咽喉堵死，任务挂起。
2. **结果交付被漏掉**：异步长任务（子代理执行、整个 agent loop 跑完、goal 完成）跑完后，用户不知道「该回来验收了」，只能靠自觉回来查看。

### 1.2 竞品调研结论（差异化机会）

> 结论均出自 `docs/competitor-research/CodingAgent竞品调研总报告.md` 与四份分档案，原文引用如下。

- **Claude Code**：官方 issue [#29438](https://github.com/anthropics/claude-code/issues/29438) 明确记录「**iOS 端在需要权限审批时缺少推送通知**」——手机端「审批推送」是官方至今仍未补齐的能力。
- **Google Gemini**：**无官方手机遥控**，只能用**实验性终端 OSC 9 序列**发系统通知（覆盖「Action required / Session complete」两类），依赖特定终端（iTerm2/WezTerm/Ghostty/Kitty），不支持就退化响铃。总报告结论原话：「Gemini 想做而做不到」。
- **WorkBuddy / TRAE**：**已有 push**——WorkBuddy 三端同步 + 手机遥控（授权/停止/绿灯），TRAE SOLO Mobile「后台执行 + push alerts」通知用户回来验收；但都偏「任务完成」粒度，且属非开源、字节/腾讯生态。
- **Cursor**：iOS 有推送（「Agent 每完成一个 turn 推一条」+ Live Activities/灵动岛），但**无 Android、大陆不可用、仅英文**。

**我们的差异化机会**（总报告 §四/§五已列为 P0 咽喉能力）：审批推送是各家「想做而没做深」的一等能力，本项目铁律已定「消息 100~300ms 送达」，把「Agent 需要审批 → 手机推送 + 横幅 + 直达审批页」做成 P0，是直接赢过 Claude Code / Gemini / Codex 的差异点。

### 1.3 边界（做什么 / 不做什么）

| 做 | 不做（v1） |
|---|---|
| 审批/提问到达时按门控发通知 | 不做审批规则配置页（Policy Engine，另立 PRD） |
| 「明确交付」三类信号（子代理 settle / 主会话空闲 / goal 终态）发通知 | 不做「阶段性里程碑」自动通知（todos/命令/Plan 计划产出，仅留扩展点） |
| 去重 + 降噪 + 点击直达会话 | 不做桌面端/Web 端通知（只做 Android App） |
| 通知门控（正在浏览该会话不打扰） | 不做通知内容自定义/富媒体（仅标题+正文+图标） |

---

## 二、规则细化（核心）

> 本节是本 PRD 的核心：把用户「比较模糊」的规则描述细化为**可判定、可去重、可降噪、可验收**的明确规则集。

### 2.0 术语与前提（先定义，划清边界）

| 术语 | 定义 |
|---|---|
| **浏览该会话** | App 处于**前台** **且** 当前打开会话 `currentSessionId == 事件归属 sessionId` |
| **App 前台** | Activity 处于 RESUMED（屏幕亮且 App 在最前） |
| **后台/锁屏** | 其余一切状态：切后台、锁屏、息屏、被其它 App 覆盖 |
| **系统通知** | 走 Android `NotificationManager` 的通知栏条目（可声音+振动+锁屏可见） |
| **页内弹窗/振动** | App 前台时的既有提醒路径（`ApprovalSheet`/`QuestionSheet` 半屏 + `platformVibrateApproval`） |

**门控归属原则**：是否发通知是**本地 UI 决策**，由 App 依据本地前台/浏览状态判定；服务端只负责把事件以服务端投影推过来（铁律 6「所有数据以服务端投影为准，客户端不做本地推算」）。服务端**不参与**通知门控。

### 2.1 防打扰硬规则（最高优先级，先于一切触发条件）

> **规则：当且仅当「用户没有正在浏览该会话」时，才发系统通知。**

三态判定：

| 用户状态 | 是否发系统通知 | 页内行为 |
|---|---|---|
| 前台 + 浏览该会话 | ❌ 不发（**避免重复打扰**） | 仅页内弹窗 + 振动（维持现状） |
| 前台 + 浏览其它会话 | ✅ 发 | 页内无该会话弹窗（用户注意力在别处） |
| 后台 / 锁屏 | ✅ 发 | 无（进程可能被系统冻结，全靠通知） |

**边界强调**：锁屏但 App 进程仍存活、且该会话仍是 `currentSessionId` → **仍发通知**（锁屏下用户看不到页内弹窗，必须走系统通知）。

### 2.2 触发条件 1：审批 / 提问提醒（高优先级 · 咽喉）

#### 触发信号

- `approval_request`（bridge 持有的审批，裁决走 `approve`）到达；
- `approval_request`（桌面端 apiproxy 持有、bridge 经 mux 转发的审批，裁决走 `answer_approval`）到达；
- `question_request`（桌面端 `ask_user_question` 转发，回答走 `answer_question`）到达；
- 连接/重连 `hello` 补发的 `pendingApprovals` / `pendingRemoteApprovals` / `pendingQuestions`（**补发 ≠ 新事件**，见去重）。

#### 通知条件（门控后）

| 用户状态 | 行为 |
|---|---|
| 前台 + 浏览该会话 | 仅页内弹窗 + 振动（现有行为，**不发系统通知**） |
| 前台 + 浏览其它会话 | **高优先级**系统通知（声音 + 振动）+ 点击直达该会话并展开弹窗 |
| 后台 / 锁屏 | **高优先级**系统通知（声音 + 振动）+ 点击直达该会话并展开弹窗 |

#### 优先级与降噪豁免

- 审批/提问 = **高优先级**（heads-up / 锁屏可见 / 声音 + 振动）。审批与提问是「人机协作咽喉」，Agent 会因等裁决而**挂起**，故 **永不纳入降噪合并**（见 2.5）。

#### 去重（幂等）

- **幂等键**：审批 = `approvalId`；提问 = `rpcId`。
- App 维护「**已通知幂等集合**」并**本地持久化**（随 App 重启保留），保证：
  1. 同一 `approvalId`/`rpcId` **最多通知一次**（覆盖：事件重复广播、`hello` 重连补发、断线期间已到达的补发）。
  2. 现有 `handleApprovalRequest`/`handleQuestionRequest` 已用 `known`（`approvalId`/`rpcId`）去重弹窗与振动（见 `BridgeClient.kt` L1386/L1419）——**通知复用同一幂等键**，弹窗与通知永不同源重复。

#### 补发场景边界

- **断线期间审批已到达 → 重连 `hello` 补发**：若该 `approvalId` 不在「已通知集合」，且当前非「前台浏览该会话」→ **补发一次通知**（否则用户可能永久错过挂起的审批）；若已通知过 → 不重复。
- **已被裁决**：收到 `approval_resolved`/`question_resolved` → 从幂等集合移除，并**撤回/更新**通知栏对应条目（标记「已解决」或清除），避免点进去发现已无待办。

### 2.3 触发条件 2：结果交付提醒（v1 保守规则集）

> 核心：定义「**明确交付**」的**可判定信号**。v1 只做三类**高置信度**终态，其余「阶段性里程碑」暂不自动通知、留配置扩展点。

#### 「明确交付」可判定信号（v1 规则集）

| # | 信号（服务端投影） | 判定条件 | 判定依据 | 误报风险与边界 |
|---|---|---|---|---|
| **D1** | 子代理 settle 收尾 | 子代理会话（`parentSessionId != null`）`agent_status` 由 running→idle，**且**末轮存在实质产出（末轮事件含 `assistant_message` 或非空 `tool_result`） | 子代理是「委托出去、异步等结果」的典型形态，settle = 跑完一轮；「实质产出」条件防「空转/被中断」误报 | 中：子代理 idle 也可能因被中断或等待 followup；v1 用「末轮实质产出」收紧，残余误报由去重+降噪兜底 |
| **D2** | 主会话 agent 空闲且最后一轮完成 | 主会话（`parentSessionId == null`）`agent_status` 由 running→idle，**且**该轮以最终结论收尾（末轮含 `assistant_message`） | 用户挂机等「agent 跑完一整轮给结论」是主场景；idle + 结论 = 明确交付 | 中：多轮连续任务每次 turn 结束都 idle → 高频触发，由降噪合并（2.5）压成一条摘要 |
| **D3** | goal phase 变为 complete / blocked | `goal_update` 且 `goal.phase ∈ {complete, blocked}` | goal 是用户显式立的持久化目标，phase 终态是**强信号**（服务端投影，铁律 6） | 低：complete 是明确终态；**blocked 是「卡住需人介入」**，文案与 complete 区分（提示受阻而非完成） |

**实现锚点（现状）**：三类信号在 App 端已有处理函数，通知判定即插入其中：
- D1/D2 → `handleAgentStatus`（`BridgeClient.kt` L1354，需对比新旧 `status` 检测 running→idle 边沿）；
- D2 的「轮次结束」也可由 `handleTurnStatus`（L1241，`turn_status` open=false 即 `turn/end`）触发；
- D3 → `handleGoalUpdate`（L1273，检测 `goal.phase` 变化）。

#### 明确不做（v1 暂不自动通知，留扩展点）

- **阶段性里程碑**：todos 全部完成、斜杠命令 done、Plan Mode 计划产出、LSP 诊断、单条 `assistant_message`——粒度太细或置信度不足，v1 不通知。
- **扩展点**：定义统一「**交付事件**」抽象（`delivery`：`sessionId` + `kind` + `轮次标识` + `摘要` + `优先级` + `是否可降噪`）；未来新增里程碑类型只需服务端加一个 `kind`，App 通知框架不变（对齐 Bridge 可选增强，见 §三）。

#### 去重（幂等）

- **幂等键**：`sessionId + 轮次标识`（轮次标识 = 该次交付对应的 `turn/end` 轮次起点 `since`（`turn_status` open 时下发、close 时同源）；goal 终态用 `goal.updatedAt`）。
- 同一「会话 × 轮次/终态」**只通知一次**。
- **多信号同源去重**：`agent_status(idle)`、`turn_status(close)`、`goal_update(终态)` 若指向同一交付，只取其一（**优先级：D3 goal 终态 > D1 子代理 settle > D2 主会话空闲**），避免同一结果被多路触发重复通知。

#### 降噪（结果交付可降噪；审批/提问永不降噪）

- **合并（✅ 已拍板：不合并）**：多条结果交付**不合并**，每条交付单独发一条通知。
- **夜间勿扰（✅ 已拍板：开启 22:00–08:00）**：结果交付类通知在免打扰时段 22:00–08:00 降级为**静默**（仅通知栏、无声音/振动）；审批/提问不受此限制（高优先级永不静默）。

### 2.4 通知内容模板

| 类型 | 标题 | 正文 | 点击行为 |
|---|---|---|---|
| 审批 | 「需要你及时响应」 | `[toolName]` + reason 摘要（无 reason 用 command 首行；截断 ≤80 字） | 打开 App → 跳到 `sessionId` 会话 → 展开 `ApprovalSheet` |
| 提问 | 「需要你回答」 | 首问 `question` 摘要（多问显示「N 个问题」；截断 ≤80 字） | 打开 App → 跳到 `sessionId` 会话 → 展开 `QuestionSheet` |
| 结果交付（complete） | 「结果已就绪」 | 会话标题 + 一句摘要（子代理：「《标签》已完成」；主会话：「本轮已完成」；goal：「目标已完成」） | 打开 App → 跳到 `sessionId` 会话（滚动到最新） |
| 结果交付（blocked） | 「目标受阻」 | 会话标题 + 「目标受阻：`blockedMessage` 摘要」 | 打开 App → 跳到 `sessionId` 会话（goal 面板展开） |

- **通知渠道分离**：高优先级渠道（审批/提问）与普通渠道（结果交付）分开建 `NotificationChannel`，用户可按渠道分别静音。

---

## 三、涉及产品改动

### 3.1 App（主要）

| # | 改动 | 现状 → 目标 | 备注 |
|---|---|---|---|
| 1 | **前台/后台状态跟踪（新增，门控前提）** | 现状：仅二维码扫描器有局部 lifecycle 观测，**无全局前台跟踪** | 在 `MainActivity`/`AppContext` 增加全局生命周期观测（`ProcessLifecycleOwner` 或 `ActivityLifecycleCallbacks`），维护 `AppForeground`（RESUMED ↔ 非 RESUMED） |
| 2 | **通知通道 + 权限（新增）** | 现状：无 `POST_NOTIFICATIONS`、无 `NotificationChannel` | Android 13+ 运行时申请 `POST_NOTIFICATIONS`（首次触发时）；两条 channel（high/普通）；**拒绝则降级为现状页内弹窗 + 振动** |
| 3 | **通知管理器（新增）** | 现状：无 | 封装 `NotificationManager` 发/撤/更新通知；维护「已通知幂等集合」并本地持久化 |
| 4 | **通知门控判定（新增）** | 现状：`handleApprovalRequest`/`handleQuestionRequest` 只弹窗+振动 | 在审批/提问/结果交付处理处插入**三态门控分支**（2.1），决定走系统通知还是仅页内弹窗 |
| 5 | **点击直达会话（新增）** | 现状：无 | `PendingIntent` 携带 `sessionId`，冷/热启动后打开对应会话并展开对应弹窗（Deep Link / intent extra 解析） |
| 6 | **结果交付信号监听（新增）** | 现状：`handleAgentStatus`/`handleTurnStatus`/`handleGoalUpdate` 只更新状态、无通知 | 按 2.3 判定 + 去重 + 降噪，插桩到这三个 handler |
| 7 | **振动复用** | 现状：`platformVibrateApproval()`（双短振+长振）已存在 | 页内路径继续复用；系统通知的声音/振动由通知渠道承担 |

### 3.2 Bridge（可能，视选型，非必须）

- **门控不需要 Bridge 参与**：前台/浏览会话是 App 本地状态，服务端无需上报（维持铁律 6 单向数据流）。
- **可选增强（P2，非阻塞）**：为结果交付补一个明确的结构化 `delivery`/`turn_settled` 事件（携带 `sessionId`、`kind`、轮次 id、摘要、是否实质产出），把「末轮是否实质产出」的判定**下沉到服务端**（服务端有完整会话日志，判定更准）。**v1 可先不碰 Bridge 协议**，用 App 端基于 `agent_status`/`turn_status`/`goal_update` 的本地判定即可上线。

---

## 四、关键技术选型（✅ 已拍板：案 A 客户端本地通知 v1）

> **已拍板（2026-09-07）**：采用**案 A（客户端本地通知）**——零外部依赖、1~2 人日；华为保活用「前台服务 + 电池优化白名单」策略；真推送（案 B）留对外发布前再评估。下方保留两案对比论证备查。

### 案 A：客户端本地通知

- **原理**：WS 事件到达时，App 本地 `NotificationManager` 立即发通知。
- **优点**：零外部依赖、零凭据、零服务端下发链路；接入成本极低（预估 1~2 人日）；与「消息 100~300ms 送达」同链路一致。
- **缺点/风险**：**依赖 App 进程存活**；Android 后台（尤其**华为/EMUI 激进杀后台**）可能杀进程 → 事件到达时无进程、通知发不出。本项目真机为**华为 HBN-AL00**，杀后台风险高，需配合「前台服务（foreground service）+ 电池优化白名单」保活，仍不保证 100% 送达。
- **可靠性**：中等（进程存活时即时，进程被杀时漏）。

### 案 B：真推送（FCM / 厂商通道）

- **原理**：服务端（bridge/桌面）在事件发生时经推送服务下发，Android 系统级送达，进程被杀也能展示/拉起。
- **优点**：**可靠送达**（进程被杀也能到达）、业界标准做法（Cursor/TRAE 均如此）。
- **缺点/风险**：**接入成本与外部依赖高**——注册推送服务、管理凭据、服务端下发链路、处理国内厂商通道碎片化；**华为大陆设备无 FCM**，必须走 **HMS Push（华为推送）**，且要与「本地内网部署」架构协调（推送需公网可达）。工作量显著大于案 A（预估 1~2 周起步）。
- **可靠性**：高。

### 对比表

| 维度 | 案 A 本地通知 | 案 B 真推送 |
|---|---|---|
| 成熟度/生态 | Android 原生 `NotificationManager`，成熟 | 需 FCM/HMS 等，成熟但接入面大 |
| 功能满足度 | 前台即时；后台/进程被杀不可靠 | 进程被杀也能送达，覆盖全场景 |
| 性能/资源 | 零额外资源 | 推送 SDK 常驻，占少量内存/电量 |
| 接入成本 | 1~2 人日 | 1~2 周起步（凭据/服务端/厂商适配） |
| 维护风险 | 低（华为杀后台需保活配置） | 中（厂商通道碎片化 + 凭据轮换） |
| 外部依赖/License | 无 | 有（FCM/HMS SDK，需同意其条款） |
| 与本项目适配 | 契合「本地内网部署」 | 需解决「公网可达 + 华为 HMS」 |

### 结论指向（已拍板）

- ✅ **已拍板（2026-09-07）：采用案 A 客户端本地通知 v1**；真推送（案 B）留对外发布前再评估。

---

## 五、关键指标（含口径）

| 指标 | 定义与口径 |
|---|---|
| **通知触发率** | 满足门控条件（非「前台浏览该会话」）的事件中，实际发出系统通知的比例 = 发通知数 / 应通知事件数。目标 → 100%（门控判定正确） |
| **通知点击率** | 通知点击数 / 通知展示（送达）数。反映召回有效性 |
| **审批响应时延** | 审批到达（`approval_request` 的 `requestedAt`）→ 用户裁决完成（`approval_resolved`）的时延；对比「有通知 vs 无通知」或功能上线前后。目标：锁屏/后台场景从「无限等待」降到「分钟级」 |
| **误报骚扰率** | 被判定为「不该发」的通知占比 =（门控漏判数 + 降噪前重复数）/ 总通知数。**门控漏判** = 用户正浏览该会话却仍收到通知。目标 → 0 |
| **通知到达时延** | 事件到达 WS → 通知展示的系统时延（案 A 同链路，对齐铁律 7 的 100~300ms） |

---

## 六、预期收益

- **审批/提问不堵死**：锁屏/后台不再「干等」，审批响应时延大幅下降，人机协作咽喉（P0）不再挂起。
- **结果交付不遗漏**：异步长任务（子代理、agent loop、goal）完成后主动召回，用户不必反复开屏查看。
- **差异化坐实**：直接补齐 Claude Code（issue #29438）、Gemini（仅实验性 OSC 9）未做深的「审批推送」，兑现竞品总报告 5.1 已列的 P0 咽喉能力。
- **架构一致**：与既有「100~300ms 送达」目标、服务端投影单一数据流（铁律 6）天然一致，无方向性冲突。

---

## 七、核心功能点（编号可验证）

- **F1** 前台/后台状态跟踪：`AppForeground` 状态可读、锁屏/切后台时正确翻转。
- **F2** 通知通道 + 权限：两条 `NotificationChannel`；Android 13+ 首次触发申请 `POST_NOTIFICATIONS`；拒绝则降级页内弹窗+振动不崩溃。
- **F3** 审批通知门控：三态判定（前台浏览该会话=不通知；前台其它会话=通知；后台/锁屏=通知）。
- **F4** 提问通知门控：同上。
- **F5** 结果交付信号判定：D1/D2/D3 三类信号 + 「末轮实质产出」收紧。
- **F6** 幂等去重：`approvalId` / `rpcId` / `会话×轮次` 三套幂等键，持久化，重连补发不重复。
- **F7** 降噪合并：同会话 5 分钟窗合并摘要；审批/提问永不合并。
- **F8** 通知点击直达会话：`PendingIntent` → 冷/热启动 → 落在对应会话 + 展开对应弹窗。
- **F9** 通知撤回/更新：审批被裁决后清除/标记已解决。
- **F10** 夜间勿扰：结果交付静默时段（默认关闭，待用户选择）。
- **F11** 埋点：见第八节。

---

## 八、核心埋点或日志

App 端沿用 `ConnLog`（现有 `ConnLog.info("ACTION"/"APPROVAL"/"QUESTION", ...)` 风格，可被 `/remote/phone-logs` 回传），新增 `NOTIFY` tag：

| 埋点 | 字段 |
|---|---|
| 通知触发 | `type`（approval/question/delivery）、`sessionId`、幂等键、门控状态（`browsing_current`/`other_session`/`background`）、channel、优先级 |
| 通知点击 | `type`、`sessionId`、冷启动/热启动 |
| 抑制原因 | `browsing_current`（正浏览该会话）/ `duplicate`（幂等命中）/ `merged`（降噪合并）/ `dnd`（夜间勿扰静默）/ `permission_denied`（权限拒绝降级） |
| 结果交付判定 | `kind`（D1/D2/D3）、轮次标识、是否实质产出、是否合并 |

服务端 tag：沿用 `APPROVAL`/`QUESTION`（已有），结果交付若判定下沉服务端则加 `DELIVERY` tag（可选增强项）。

---

## 九、验收口径

### 9.1 真机验收矩阵（华为 HBN-AL00，覆盖「三种用户状态 × 三类事件」）

| 用户状态 \ 事件 | 审批 | 提问 | 结果交付 |
|---|---|---|---|
| 前台 + 浏览该会话 | 仅弹窗+振动，**无系统通知** | 同左 | **不通知** |
| 前台 + 浏览其它会话 | 高优先级通知，点击直达+弹窗 | 同左 | 通知（门控后） |
| 后台 / 锁屏 | 高优先级通知，点击直达+弹窗 | 同左 | 通知（门控后） |

### 9.2 专项验收项

- **去重**：同一审批/提问经重连补发 / 事件重复广播 → 只通知一次。
- **降噪**：同会话 5 分钟内多次结果交付 → 合并为一条摘要。
- **点击直达**：通知点击 → 冷/热启动 → 落在对应会话 + 展开对应弹窗。
- **权限**：Android 13+ 首次触发申请 `POST_NOTIFICATIONS`；拒绝 → 降级页内弹窗 + 振动、不崩溃。
- **blocked 文案**：goal blocked 时通知标题为「目标受阻」而非「完成」。

---

## 十、相关文档链接

- 竞品调研总报告：`docs/competitor-research/CodingAgent竞品调研总报告.md`（§5.1 P0「审批推送」、§四差异化机会）
- Claude Code issue #29438（iOS 审批推送缺失）：`docs/competitor-research/ClaudeCode与Cursor竞品档案.md`
- Gemini OSC 9 通知痛点 / TRAE push alerts / WorkBuddy 推送：`docs/competitor-research/Codex与Gemini竞品档案.md`、`docs/competitor-research/WorkBuddy与Trae与Manus竞品档案.md`
- 现有审批/提问弹窗实现：`docs/feature-priority-map.md`（第 8 节「审批与提问」）；`composeApp/src/commonMain/kotlin/com/daniel/dshremote/BridgeClient.kt`（`handleApprovalRequest` L1386 / `handleQuestionRequest` L1419 / `handleAgentStatus` L1354 / `handleTurnStatus` L1241 / `handleGoalUpdate` L1273）
- 协议定义：`composeApp/src/commonMain/kotlin/com/daniel/dshremote/protocol/Protocol.kt`；bridge `src/protocol.ts`
- 平台能力现状：`composeApp/src/androidMain/kotlin/com/daniel/dshremote/Platform.android.kt`（`platformVibrateApproval`）、`MainActivity.kt`、`AndroidManifest.xml`（无 `POST_NOTIFICATIONS`/无 `NotificationChannel`）
- 铁律：`AGENTS.md`（铁律 1 重大选型拍板 / 6 服务端投影 / 7 100~300ms 送达 / 9 移动端优先 / 14 语义冲突二次确认）
- 技术设计模板：`docs/decisions/_技术设计文档模板.md`

---

*（本 PRD 仅创建该文件、未 commit；事实依据均来自已读代码与竞品调研文档，未臆造新事实；「待用户拍板/待用户选择」项已显式标注。）*
