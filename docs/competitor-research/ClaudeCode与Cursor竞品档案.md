# Claude Code 与 Cursor 竞品档案

> 调研对象：Claude Code（Anthropic）、Cursor（Anysphere）
> 调研人：竞品调研子代理
> 调研时间：2026-09（数据口径以各来源标注时间为准，未标注处为"待核实"）
> 调研方法：web_search 检索官网 / 官方文档 / 官方博客 / 权威评测与财经媒体，事实均附来源链接
> 说明：本文为「APP 竞品调研」子任务产出，聚焦两个产品，**重点深挖移动端 App / 远程控制 / 审批体验**（与本项目「手机遥控桌面 Agent」定位直接对标）。横向对比矩阵与总报告由主任务汇总。

---

## 一、Claude Code

### 1.1 定位与形态

- **定位**：Anthropic 官方出品的终端（CLI）优先的 AI 编程 Agent，核心理念是「在开发者本地终端里自主完成代码任务」，而非封装成一个 IDE。
- **形态**：多端并存的「本地 CLI 为核心 + 多入口」产品——
  - 本地 **CLI**（`claude` 命令，跑在你的机器上，直接读本地文件、执行终端命令）
  - **IDE 扩展**（VS Code / JetBrains）
  - **桌面 App**（Claude Desktop）
  - **网页版** `claude.ai/code`（2025-10-20 上线，云端沙箱运行，[TechCrunch](https://techcrunch.com/2025/10/20/anthropic-brings-claude-code-to-the-web/)、[官方博客](https://claude.com/blog/claude-code-on-the-web)）
  - **手机 App**（Claude iOS / Android，作为本地会话的远程窗口，见 1.4）
- **关键区别**：网页版跑在 Anthropic 云端，本地 CLI 跑在你自己的机器上；「Remote Control」把两者打通（手机/浏览器成为本地会话的窗口）。

### 1.2 目标用户与核心场景

- **目标用户**：个人开发者、AI 重度使用者、工程团队（Team/Enterprise）。
- **核心场景**：
  - 在本地终端里让 Agent 读代码库、改多文件、跑测试、执行 git/构建命令的「自主编码循环」；
  - 「中途离开工位」后继续监督/下达指令——这是 Remote Control 的典型场景（官方原话：*"Start a task at your desk, then pick it up from your phone on the couch"*，[官方文档](https://code.claude.com/docs/en/remote-control)）；
  - 程序化/批量调用（`claude -p`、Agent SDK，用于 CI/CD、脚本、多任务编排）。

### 1.3 核心功能

**代码生成与编辑**
- 读代码库、生成/修改多文件、执行 Bash（`Read`/`Edit`/`Bash` 等内置工具，[Tools 参考](https://code.claude.com/docs/en/tools-reference)）。

**Agent 自主循环能力（重点）**
- 内置「Agent Loop」：模型自主规划 → 调用工具 → 观察结果 → 继续，直到完成或需人工介入。
- **子代理（Subagents）**：可自定义隔离上下文的专用子代理（如独立 reviewer/researcher），隔离选项含 `worktree`（在独立 git worktree 内执行命令），[官方文档](https://code.claude.com/docs/en/sub-agents)。
- **Hooks**：生命周期确定性脚本（`PreToolUse`/`PostToolUse` 等），用于在工具执行前后做规则拦截，[Hooks 指南](https://code.claude.com/docs/en/hooks-guide)。
- **动态工作流（Dynamic Workflows）**、**Skills**、**MCP**、**Plugins**：可编程扩展点。
- **Headless / SDK**：`claude -p "prompt"` 非交互单次执行；Agent SDK 提供 CLI / Python / TypeScript 全编程控制，支持 `--allowedTools`（预批准工具）、`--output-format`（结构化输出）、`--bare`（跳过 hooks/skills/MCP 的纯净模式）、退出码 0/非 0 供脚本分支，[官方文档](https://code.claude.com/docs/en/headless)。

**权限 / 审批流（重点，本项目对标点）**
- **六档权限模式**（Shift+Tab 切换），[官方文档](https://code.claude.com/docs/en/permission-modes)：

  | 模式（配置值） | 免确认自动执行 | 适用 |
  |---|---|---|
  | `default`（界面名 Manual） | 仅读取 | 逐项审查、敏感工作 |
  | `acceptEdits` | 读取 + 文件编辑 + 常见文件命令（mkdir/mv/cp…） | 边看边迭代 |
  | `plan` | 读取 + 分类器放行的命令 | 先摸清代码库再动手 |
  | `auto` | 全部，后台安全复核 | 长任务、降低"审批疲劳" |
  | `dontAsk` | 仅预批准工具 | 锁死的 CI/脚本 |
  | `bypassPermissions` | 全部 | 仅隔离容器/VM |

- **auto 模式**：Pro/Max/Team 套餐的默认起始模式，由一个「第二个模型（分类器）」代替人工复核动作（后台安全检查）。
- 关键边界：受保护路径的写入、`rm` 危险路径删除等，任何模式（含 `bypassPermissions`）都不自动放行；Deny 规则在所有模式生效。

### 1.4 移动端与远程能力（★★ 本项目直接对标，重点深挖）

**Remote Control（远程控制）**，[官方文档](https://code.claude.com/docs/en/remote-control)：

- **本质**：手机/平板/任意浏览器成为「本地 Claude Code 会话的远程窗口」，会话**始终在你自己的机器上跑**，本地文件系统、MCP、工具、项目配置全部可用；手机端输入 `@` 还能自动补全本地文件路径。与网页版（云端跑）不同。
- **启动方式**（三种）：
  - `claude remote-control`（纯服务器模式，终端显示会话 URL，按空格显示二维码）
  - `claude --remote-control` / `--rc`（本地交互 + 远程并行）
  - 会话中 `/remote-control`（把当前会话历史一起带过去，显示 URL + 二维码）
- **连接方式**：打开 URL / 扫二维码 / 在 `claude.ai/code` 或手机 App 会话列表中找到它。
- **多端同步**：终端、浏览器、手机可**同时连同一会话**，子代理与工作流进度跨端实时同步；可在手机/浏览器**发图片、传文件**。
- **断线续传**：笔记本休眠/断网后自动重连，重连期间**队列化消息、权限提示、状态更新**，恢复后补投（官方原话 *"Claude Code queues messages, permission prompts, and status updates... and delivers them once the connection recovers"*）——这正是本项目「消息推送 100~300ms 送达 + 断线重连」要解决的同类问题。
- **手机端能力边界**：
  - 远端可执行：`/compact` `/clear` `/context` `/usage` `/exit` 等；仅本地可执行：`/mcp` `/plugin` `/resume` 等（不能在手机上装插件/恢复旧会话）。
  - **审批体验缺口**：GitHub issue [#29438](https://github.com/anthropics/claude-code/issues/29438) 明确记录"**iOS 端在需要权限审批时缺少推送通知**"这一需求——说明手机端「远程审批推送」是官方仍在补的能力，也是本项目的直接机会点。
- **安全模型**：流量走 HTTPS/TLS、经 Anthropic API 中转，**机器不开任何入站端口**；使用多个短期、一次性、独立过期的凭据。
- **可用性要求**：Pro/Max/Team/Enterprise 订阅（不支持 API key 登录）；Team/Enterprise 需管理员在后台开启；需先在项目目录跑过 `claude` 接受 workspace trust；Bedrock/Vertex/Foundry 等第三方接入点不可用。

**Claude 手机 App**（作为 Remote Control 的窗口）：iOS [App Store](https://apps.apple.com/us/app/claude-by-anthropic/id6473753684)、Android [Google Play](https://play.google.com/store/apps/details?id=com.anthropic.claude)。

### 1.5 技术栈与模型

- 基于 Anthropic **Claude 系列模型**（Claude Opus / Sonnet 等），通过 claude.ai 订阅或 Anthropic API 调用。
- 扩展体系：**MCP（Model Context Protocol，已成为开放标准）**、Skills（SKILL.md 按需加载）、Subagents、Hooks、Plugins。
- 运行环境：本地进程 + Node 运行时；headless/SDK 面向 Python/TypeScript。

### 1.6 商业模式与定价

- 早期：免费额度 + 20 美元/月独立 Claude Code 订阅 + 包含在 Claude Pro（20 美元/月）内，[ZDNet](https://www.zdnet.com/article/anthropics-popular-claude-code-ai-tool-now-included-with-its-20month-pro-plan/)。
- **2026-04 起定价变动（在测试中，需标注"待核实"）**：Anthropic 开始把 Claude Code 从 20 美元 Pro 套餐中移除，重度用户被引导至 **Max 套餐（100/200 美元/月）**，[Cocoloop 报道](https://news.cocoloop.cn/en/2026/04/claude-code-pro-plan-removed/)、[devby 报道](https://devby.io/ru/news/anthropic-nachala-ubirat-claude-code-iz-podpiski-pro-za-20-govorit-chto-test)、中文报道 [网易](https://www.163.com/dy/article/KR4MLSG30511AQHO.html)（"Pro 用户用不了 Claude Code，除非 100 美元买 Max"）。
- 此前曾多次收紧重度用户限额：[TechCrunch 2025-07](https://techcrunch.com/2025/07/28/anthropic-unveils-new-rate-limits-to-curb-claude-code-power-users/)。
- 结论：**定价在向"高阶 Agent 用量另计/提价"方向演进**，反映 Agent 自主循环的算力成本压力。

### 1.7 市占 / 热度 / 口碑

以下数据来自 [Claudify《The State of Claude Code in 2026》](https://claudify.tech/blog/state-of-claude-code-2026)（2026-07 综合报告，含独立口径）：

- **增速**：年化收入（run-rate）从 2025-09 约 5 亿美元 → 2025-11 约 10 亿 → **2026-02 约 25 亿美元**；开发者"工作采用率"从 2025 年中约 3% → **2026-01 达 18%**（美加 24%）；据估 2026 年初约 **4% 的 GitHub 公开提交由 Claude Code 创作**。
- **口碑**：JetBrains 2026-01 调研中 **CSAT 91%、NPS 54**，为"最受喜爱"的 AI 编程工具之一；企业编码模型层面 Menlo Ventures 估 Anthropic 占 **约 54%**。
- **生态**：围绕 CLAUDE.md / slash 命令 / subagents / hooks / MCP / plugins 形成了最大开源生态。
- **定位对比**：GitHub Copilot 在付费席位/企业装机量领先、Cursor 在编辑器使用与收入领先（约 20 亿美元 ARR），**Claude Code 在满意度与复杂 Agentic 任务上领先**；市场呈多工具并用格局。

### 1.8 亮点与短板

**亮点**
- 自主 Agent 循环 + 子代理/工作流/沙箱隔离成熟，复杂多步任务能力强；
- Remote Control 的「本地执行 + 多端窗口 + 断线补投」架构，天然契合"遥控桌面 Agent"；
- 权限模式分层清晰（尤其 auto 模式的"分类器代审"），安全边界文档详尽；
- 生态开放（MCP/Skills 已成标准）、可编程（SDK/headless）程度最高。

**短板**
- 形态偏终端/开发者，对非技术用户不友好；
- 定价不稳定、重度使用成本高且限额反复收紧（用户抱怨多）；
- 手机端仍偏"远程窗口"，**审批提醒/推送体验尚未补齐**（见 issue #29438）；
- 第三方接入点（Bedrock/Vertex/Foundry）与 API key 登录下 Remote Control 不可用，接入受限。

### 1.9 对我们的启示（Claude Code）

**可借鉴（落到功能级）**
1. **远程窗口 = 本地会话投影**：我们的「手机遥控桌面 Agent」应坚持"执行永远在桌面端/服务端、手机只是窗口"，与 Remote Control 的架构共识一致；手机端 `@` 文件补全、图片/文件上传、多端同会话同步都值得对标。
2. **断线续传 + 队列补投**：官方明确做了"重连期间队列化消息/权限提示/状态更新"——我们消息推送 100~300ms 送达目标之外，应补齐"断线后不丢消息、恢复后补投"。
3. **权限模式分层 + auto 分类器代审**：可借鉴「读-only / 接受编辑 / plan / 全自动」的分层，用"危险路径/危险命令永不自动放行 + Deny 规则全局生效"兜底。
4. **审批推送直击用户痛点**：issue #29438 证明"手机端权限审批提醒"是刚需缺口——**我们应把"审批推送通知 + 手机上直接批/拒"做成一等能力**，这是最直接的差异化。

**需规避**
- 定价反复与限额收紧造成的口碑反噬——我们的审批/用量设计要给用户稳定预期；
- 仅"远程窗口"、不能在手机端做编辑/装插件的能力边界，要明确产品边界避免用户误预期。

**差异化机会**
- Claude Code 手机端是"通用 Claude App 里接入"，**非专为"遥控桌面 Agent"设计**；我们做**专用、移动优先**的遥控 App，在审批流、触控交互、状态可视化上可做到更深。
- Claude Code 仅 Pro/Max/Team/Enterprise 订阅可用 Remote Control 且不支持 API key——我们可对自有部署/自定义接入更开放。

---

## 二、Cursor

### 2.1 定位与形态

- **定位**：Anysphere 出品的 **AI 原生 IDE（VS Code 的深度魔改分支）**，主打"AI 优先的编辑体验"，从补全到全自主 Agent 全覆盖。
- **形态**：
  - **桌面 IDE**（核心，Cursor 客户端）
  - **CLI**
  - **Web 端** `cursor.com/agents`（云端 Agent 控制台）
  - **手机 App**（Cursor for iOS，见 2.4）
  - **云端 Agent / 自托管机器**（Cloud Agents / Self-Hosted Machines / My Machines）

### 2.2 目标用户与核心场景

- **目标用户**：个人开发者 + 团队/企业（Teams/Enterprise 有管理后台、SSO、审计）。
- **核心场景**：
  - IDE 内补全（Tab）、对话（Chat）、内联编辑（Cmd-K）、**多文件自主编辑 + 跑终端**（Composer/Agent）；
  - **后台/云端 Agent**：离手执行任务、自动开 PR、自动修 CI；
  - **口袋编程**：在手机上启动/监督 Agent、审阅合并 PR（[官方 iOS 文档](https://cursor.com/help/ai-features/mobile-app.md)）。

### 2.3 核心功能

- **补全/对话/内联编辑**：Tab 补全、Chat、Cmd-K。
- **Composer → Agent 模式**：多文件编辑 + 终端执行 + 自主多步循环；支持多模型（GPT/Claude/Gemini 等）切换。
- **后台 Agent（Background Agents）**：后台持续跑任务、产出 PR，[官方文档](https://cursor.com/help/ai-features/background-agents.md)。
- **BugBot**：自动发现并修复 bug（Cursor 3.x 引入）。
- **Cloud Agents（云端 Agent）**：在隔离云端 VM（含完整桌面环境）里跑任务——可装依赖、跑测试、**用鼠标键盘操作浏览器做 UI 验证**、把截图/视频挂到 PR，[官方文档](https://cursor.com/help/ai-features/cloud-agents.md)。
- **Automations（自动化）**：定时（cron）或由 GitHub/Slack/Linear/PagerDuty/Webhook 事件触发 Cloud Agent，[官方文档](https://cursor.com/help/ai-features/automations.md)。
- **多入口发起**：Slack `@Cursor`、GitHub PR/issue 评论 `@cursor`、Linear `@Cursor`、Bitbucket、Cloud Agent API。
- **扩展体系**：Rules（.cursor/rules）、Hooks（.cursor/hooks.json）、MCP、Skills、Slash 命令。

### 2.4 移动端与远程能力（★★ 本项目直接对标，重点深挖）

**Cursor for iOS**（官方文档：[帮助页](https://cursor.com/help/ai-features/mobile-app.md)、[iOS 参考](https://cursor.com/docs/cloud-agent/mobile.md)）：

- **定位**：原生 iOS App，用于**指挥（direct）和监督（supervise）跑在云端或你自己电脑上的 Agent**，并在手机上审阅、合并 PR。App Store 链接：[id6767085653](https://apps.apple.com/app/cursor/id6767085653)。
- **能做什么**：
  - 启动 Agent（选云端机器 / Team Pool / 自有机器）、实时看它干活、给运行中的 Agent 发后续指令、点开子代理卡片读其子对话记录；
  - 审阅合并 PR：看完整 diff、commits、部署、审批、评论、CI 检查，加/换 reviewer，让 Agent 解决评论或修失败检查，支持 squash merge、auto-merge 等；
  - **Design Mode**：拍照/传图后**在图上点、画圈**给 Agent 视觉指令（iPad 上可用 Apple Pencil 画）；
  - **语音口述**指令（实时转写，免手）；
  - MCP 工具选择、slash 命令/skills/automations 在手机端与桌面一致；
  - **Live Activities**：锁屏 + 灵动岛同时跟踪**最多 8 个** Agent 的实时状态；
  - 推送通知：Agent 每完成一个 turn 推一条；cache-first 本地优先。
- **不能做什么**：**App 内没有编辑器、终端、文件浏览器**（只 diff 视图审阅），配置 secrets/环境/MCP 管理/自动化规则等在 Web 端做。
- **Remote Control（远程控制本地电脑）**：把你在电脑上跑的 Agent 交给手机继续指挥——**Agent 循环移到云端，而工具调用（终端命令、文件编辑、测试、git）仍在你自己的机器上执行**；要求 Cursor 客户端 3.9.8+、在 Agents Window 里 `Settings > Agents` 开启、`/remote-control` 后交付会话；beta、需付费套餐；Teams/Enterprise 需管理员开启；电脑须保持唤醒联网（可开 "Keep this computer awake"）。
- **限制与现状**：
  - **仅 iPhone/iPad（iOS 26 / iPadOS 26+），Android 尚未发布（计划中、无日期）**；
  - App Store **除中国大陆外**全地区可用；界面**仅英文**；
  - 免费账号可登录但**不能启动 Agent**（需付费套餐：Start/Pro/Pro+/Ultra/Teams/Enterprise）。
- 相关新闻：Cursor 推出 iOS 版被解读为"AI 编程竞争延至手机端"（[虎嗅](https://www.huxiu.com/article/4871585.html)）；与 OpenClaw 同被视为"口袋编程"时代（[腾讯新闻](https://news.qq.com/rain/a/20260701A02WOG00)）。

### 2.5 技术栈与模型

- IDE 基于 VS Code 分支；多模型路由（Auto 模式经 Cursor Router 在 GPT/Claude/Gemini 等间按成本/智能/可靠性路由）。
- Cloud Agents 跑在隔离 VM（含桌面环境 + 浏览器，可做 UI 点击验证）；支持 OIDC JWT 短期凭据联邦接入 AWS 等。

### 2.6 商业模式与定价

[官方定价页](https://cursor.com/help/account-and-billing/pricing.md)（价格以官网为准，以下为抓取快照）：

| 套餐 | 价格 |
|---|---|
| Hobby | 免费（有限额度） |
| Start（仅印度） | ₹649/月（含税） |
| **Pro** | **$20/月** |
| **Pro+** | **$60/月** |
| **Ultra** | **$200/月** |
| Teams Standard | $40/用户/月 |
| Teams Premium | $120/用户/月 |
| Enterprise | 按需联系销售（池化用量、SCIM、审计日志） |

- Cloud Agents 按所选用模型的 **API 价格** 计费（[模型与定价](https://cursor.com/docs/models-and-pricing.md)）；大上下文窗口会增加 token 成本。

### 2.7 市占 / 热度 / 口碑

- **ARR**：约 **20 亿美元 ARR**，三年达成（[The Next Web](https://thenextweb.com/news/cursor-anysphere-2-billion-funding-50-billion-valuation-ai-coding)），并在洽谈以 **500 亿美元估值融资 20 亿美元**。
- **收购事件**：2026-06-16，CNBC 报道 **SpaceX 以 600 亿美元收购 Cursor**（[CNBC](https://www.cnbc.com/2026/06/16/spacex-spcx-cursor-acquisition-ipo.html)），国内财经媒体亦跟进（[36氪](https://eu.36kr.com/zh/p/3855869611856899)、[投资界](https://m.pedaily.cn/news/565284)）。
- **榜单**：2026 CNBC Disruptor 50 排名 **第 37**（[CNBC](https://www.cnbc.com/2026/05/19/cursor-cnbc-disruptor-50-ranking.html)）。
- **定位对比**：Cursor 在**编辑器使用与收入**上领先；付费开发者心智中与 GitHub Copilot、Claude Code 形成"三强"格局（[Claudify 报告](https://claudify.tech/blog/state-of-claude-code-2026)）。

### 2.8 亮点与短板

**亮点**
- IDE 内体验完整（补全→对话→Agent 全链路），多模型灵活；
- 云端 Agent + 桌面环境浏览器操作 + PR 截图/视频，闭环"自主开发→验证→提 PR"；
- **移动端体验最完整**：远程指挥、审阅合并 PR、Design Mode 画图、语音、Live Activities/灵动岛、推送，商业上走"付费套餐解锁 Agent"；
- 多入口（Slack/GitHub/Linear/API/Automations）生态打通。

**短板**
- **无 Android 版**（本项目是 Android 优先，这正是空位）；
- 手机端**不能编辑/用终端**，是"遥控+审阅"而非"完整工作台"；
- 大陆 App Store 不可用、仅英文界面；
- Remote Control 仍 beta、依赖付费套餐与管理员开启、要求电脑常亮联网；
- Cloud Agent 每次启动环境搭建慢（官方也承认是正常现象）。

### 2.9 对我们的启示（Cursor）

**可借鉴（落到功能级）**
1. **Live Activities / 灵动岛 + 多 Agent 状态跟踪**：锁屏实时看最多 8 个 Agent 状态——我们可做"桌面 Agent 运行状态"的手机常驻可见（通知栏/小组件）。
2. **Design Mode 画图 + 语音口述**：给 Agent 视觉/语音指令的交互方式，是移动端遥控的加分项。
3. **审阅合并闭环**：手机上直接看 diff、加 reviewer、让 Agent 修评论、合并 PR——我们的审批流可参考"审批 → 让 Agent 继续修 → 再合并"的闭环。
4. **"指挥 App"与"编辑 IDE"分离的产品哲学**：手机 App 明确不做编辑器，聚焦"启动/监督/审阅"——这印证了本项目"手机做遥控与审批、不做桌面 IDE"的定位是合理的。
5. **Remote Control 的"循环在云端、工具在本地"** 混合架构：可作为"手机指令 → 云端编排 → 本地执行"的一种可选形态参考（需评估与本项目"服务端投影"架构的关系）。

**需规避**
- Cloud Agent 启动慢、仅付费可用导致的体验门槛；
- 无 Android、大陆不可用、仅英文——反衬出"Android 优先 + 中文 + 本土可部署"是清晰空位；
- 电脑必须常亮联网才能 Remote Control 的脆弱性——我们应设计更稳的断线/唤醒/队列机制。

**差异化机会**
- Cursor 移动端是"iOS 专属、英文、需付费、大陆不可用"；本项目 **Android 优先、中文、免费可控的"手机遥控桌面 Agent"** 直接卡在其空缺上；
- Cursor 手机端无编辑/终端，我们的「审批 + 状态 + 指令」可作为更聚焦、更轻的差异点。

---

## 三、两产品对"手机遥控桌面 Agent"的共性结论（供总报告参考）

1. **架构共识**：Claude Code Remote Control 与 Cursor Remote Control 都采用"**执行在本地/桌面，手机是远程窗口/指挥台**"——与本项目"服务端投影、客户端不做本地推算"的单一数据流方向一致。
2. **移动端能力分层**：二者手机端都**主动弱化"编辑"，强化"启动 Agent / 实时监督 / 审阅合并 / 审批"**——印证本项目聚焦"遥控 + 审批"是正确的产品边界。
3. **审批与推送是共同短板**：Claude Code 至今有"iOS 审批推送缺失"的公开 issue；Cursor 有推送但偏"turn 完成"而非"需要你审批"。**"手机端审批提醒 + 一键批/拒 + 让 Agent 继续"是双方都未做深、却是刚需的差异化点。**
4. **Android 是空位**：Claude 有 Android App（但 Remote Control 偏远程窗口），**Cursor 明确无 Android**——本项目 Android 优先可直接占位。

---

*（本文档按 app-competitor-research 规范产出，事实均附来源；"待核实"处为定价变动期信息，建议上线前二次核对官方定价页。）*
