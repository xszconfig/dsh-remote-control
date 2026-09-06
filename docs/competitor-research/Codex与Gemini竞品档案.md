# Codex 与 Gemini 竞品档案

> 调研对象：**OpenAI Codex**（含 Codex CLI / IDE / 云端 / ChatGPT 集成）与 **Google Gemini 编程能力**（含 Gemini CLI / Gemini Code Assist / Antigravity）。
> 调研时间：2026-09（数据口径见各节注明，标注「待核实」的为尚未在官方渠道确认的信息）。
> 所属主题：Coding Agent 主流产品竞品调研（本档案为分产品档案，横向对比矩阵见总报告）。
> 本项目定位对照：**手机遥控桌面 Coding Agent**（DSH Remote Control）——重点深挖两家的移动端/远程/审批体验。

---

## 一、OpenAI Codex

### 1. 定位与形态（多形态并存）

Codex 是 OpenAI 的编码 Agent 产品线，同一品牌下并存四种形态，共用 GPT-5-Codex 系列模型：

| 形态 | 说明 | 运行位置 |
|---|---|---|
| **Codex CLI** | 开源（Apache-2.0）、Rust 编写的终端编码 Agent，本地运行 | 本地终端 |
| **Codex IDE 扩展** | VS Code / Cursor / Windsurf 插件，`codex app` 或桌面 App 体验 | 本地 IDE |
| **Codex Web（云端工作区）** | `chatgpt.com/codex`，云端 Agent，并行多任务 | 云端 |
| **Codex × ChatGPT** | 内置于 ChatGPT（含移动 App），随时随地带入 | 云端 + 移动端 |

来源：[openai/codex README（GitHub）](https://github.com/openai/codex)、[Codex 全面升级（OpenAI 官方）](https://openai.com/zh-Hans-CN/index/introducing-upgrades-to-codex/)

- 官方将 Codex App 定位为「agentic coding 的**指挥中心**」，Agent 可跨项目通过 **worktrees 与云端环境并行工作**。
- Codex CLI 于 2025-04-13 开源，与 Claude Code、Gemini CLI 同属「终端 coding agent」第一梯队。

### 2. 目标用户与核心场景

- **个体开发者**：本地仓库的自主编码、重构、测试、生成 PR、写文档。
- **团队/企业**：通过 config.toml 共享配置、MCP 集成、managed hooks 做组织级管控。
- **云端并行场景**：把多个任务丢到云端工作区并行跑，用户在手机/浏览器「巡场」。

### 3. 核心功能

- **多文件编辑 + 终端执行 + 自主循环**：读写工作区文件、执行 shell、按 AGENTS.md 项目指令行动。
- **子代理（agents）**：在 `[agents.<name>]` 注册角色化子代理，可指向独立角色文件。
- **Skills / AGENTS.md / AGENTS.override.md**：项目级指令注入，override 层用于个人指令（不提交 git）。
- **MCP 服务器**：在 config.toml 声明共享集成。
- **非交互模式**：`codex exec`，供脚本/CI 自动化调用。
- **配置分层**：CLI 参数 > 项目级 `.codex/config.toml` > 全局 `~/.codex/config.toml`，支持 profiles 预设。

来源：[openai/codex docs（config.md / exec.md / agents_md.md / skills.md）](https://github.com/openai/codex/tree/main/docs)、[Codex 配置最佳实践](https://github.com/shanraisshan/codex-cli-best-practice/blob/main/best-practice/codex-config.md)

### 4. 沙箱与审批机制（关键）

Codex 用「沙箱模式 × 审批策略」两轴组合控制自主度：

**沙箱模式（文件系统访问级别）**

| 模式 | 读 | 写 | 网络/命令 | 适用 |
|---|---|---|---|---|
| `read-only` | ✅ | ❌ | ❌ | 评审、审计、CI 分析 |
| `workspace-write` | ✅ | 仅工作区 | 阻断网络 | 本地开发、代码/文档编辑 |
| `danger-full-access` | ✅ | 全盘 | 放开 | 完全信任、跨项目/系统级 |

**审批策略（何时要人确认）**

| 策略 | 行为 | 适用 |
|---|---|---|
| `untrusted` | 仅自动跑受信任的只读类命令，其余询问 | 新仓库、审计 |
| `on-request` | 由模型自行判断何时询问 | 日常开发 |
| `on-failure` | 自动执行，仅在命令失败时询问 | 稳定环境、高效迭代 |
| `never` | 从不询问，失败直接回模型 | 非交互、受控自动化 |

- 快捷组合：`--full-auto` = `workspace-write + on-failure`；`--dangerously-bypass-approvals-and-sandbox`（YOLO）绕过一切限制。
- 推荐路径：先 `read-only` 探索 → 再 `workspace-write` 改 → 仅在必要时 `danger-full-access`。

来源：[Codex 沙箱与审批（社区镜像官方文档）](https://github.com/etheaven/codex-mcp-server/blob/main/docs/concepts/sandbox.md)、[Codex 沙箱官方文档（redirect）](https://github.com/openai/codex/blob/main/docs/sandbox.md)、[Codex 权限·沙箱·审批（Rookie）](https://cloud.tencent.com.cn/developer/article/2702951)

### 5. 移动端与远程能力（重点深挖 ⭐）

**这是与本项目最直接对标的部分：OpenAI 已于 2026-05 将 Codex 集成进 ChatGPT 移动 App（iOS/Android），实现「手机遥控桌面/云端 Coding Agent」。**

- 该功能处于 **preview**，**所有订阅计划可用**。
- 手机端可做：**查看实时 Codex 环境、审阅输出、审批（approve）命令、切换模型、启动新任务**。
- 官方定位是「支持跨多个 thread / workflow 并行工作」，而**不是**深度遥控单个任务。
- 配套更新：桌面端「后台操作（background operations）」+ 浏览器扩展「实时会话（live sessions）」。

来源：[Work with Codex from anywhere（OpenAI 官方）](https://openai.com/zh-Hans-CN/index/work-with-codex-from-anywhere/)、[OpenAI integrates Codex into ChatGPT mobile app（Digital Watch Observatory，2026-05-15）](https://dig.watch/updates/openai-brings-codex-into-chatgpt-mobile)、[Codex 接入手机（Dealmoon 编译）](https://www.dealmoon.com.au/post/3222592)

**要点判断**：Codex 的移动端是「轻量监控 + 审批入口 + 任务启动」的**指挥中心**形态，未做实时屏幕/终端镜像、逐条消息级遥控。这既是「手机遥控桌面 Agent」赛道的巨头背书，也留下了「深度遥控单任务」的差异化空白（详见第六节）。

### 6. 技术栈与模型

- **CLI**：Rust（`codex` 二进制），Apache-2.0。
- **模型**：GPT-5-Codex 系列，最新为 **GPT-5.1-Codex-Max**（官方称可「通宵」跑 24 小时级任务，编程跑分反超 Gemini 3 Pro）。
- **安全底座**：macOS 用 Seatbelt 沙箱；提供 `CODEX_ENABLE_FIREWALL=1` + `OPENAI_ALLOWED_DOMAINS` 出网限制、加固版 VS Code Dev Container profile（bubblewrap + egress 限制）用于不可信代码。

来源：[GPT-5.1-Codex-Max 发布（VentureBeat）](https://venturebeat.com/ai/openai-debuts-gpt-5-1-codex-max-coding-model-and-it-already-completed-a-24)、[Codex 配置最佳实践（unix socket allowlist / secure devcontainer）](https://github.com/shanraisshan/codex-cli-best-practice/blob/main/best-practice/codex-config.md)

### 7. 商业模式与定价

Codex 主要随 **ChatGPT 订阅计划** 提供（也可用 API key 按 token 计费）：

| 计划 | 月费 | 说明 |
|---|---|---|
| Free | $0 | 有限额度的 Codex 试用 |
| Go | $8 | 轻量计划（2026 新增） |
| Plus | $20 | 主力个人档 |
| Pro | $100 | 高用量档（此前为 $200，2026 有调整，**待核实**） |
| Business / Enterprise | 按席位 | 团队/企业 |

> 注：具体各档「Codex 用量配额/会话数」随政策频繁调整，不同来源口径不一；建议以 [Codex 费率卡（OpenAI Help Center）](https://help.openai.com/en/articles/20001106-codex-usage-and-rate-limits) 为准。

来源：[Codex Pricing 2026（MorphLLM）](https://www.morphllm.com/codex-pricing)、[OpenAI Codex Pricing 2026（Taskade）](https://www.taskade.com/blog/codex-pricing-explained)、[ChatGPT 订阅定价（AI Pricing Guru）](https://www.aipricing.guru/chatgpt-subscription-pricing/)

### 8. 市占/热度/口碑

- **GitHub stars：121,920**（openai/codex，2026-09-06 查询），forks 18,714，Apache-2.0，Rust。
- **OSSInsight《Coding Agent Wars》（2026-03）**：Codex 当时 66,969 stars、383 位贡献者、近 30 天 754 commits（增速第一梯队）。归类为「企业火箭」——大厂背书 + 高速迭代，但 contributor/star 比仅 5.7（开源社区属性弱）。
- **口碑**：Infoworld 等评测称其直接对标 Claude Code；开发者反馈集中在「权限配置两极分化——要么太严寸步难行、要么直接 full access」（[Codex 避坑指南](https://cloud.tencent.com.cn/developer/article/2704656)）。

来源：[OSSInsight Coding Agent Wars 2026](https://ossinsight.io/blog/coding-agent-wars-2026)、[Infoworld: OpenAI Codex rivals Claude Code](https://www.infoworld.com/article/4071047/openai-codex-rivals-claude-code.html)

### 9. 亮点与短板

**亮点**
- 四形态全覆盖（CLI/IDE/云端/移动），生态最完整。
- 云端并行多任务 + worktrees 的「指挥中心」心智。
- 沙箱 × 审批双轴细粒度可控，是业界事实标准。
- **唯一把「手机审批桌面 Agent」做成官方能力**的巨头产品。

**短板**
- 审批粒度对新手偏复杂，「太严 vs 全开」的两极陷阱明显。
- 移动端只是轻量监控/审批入口，非深度遥控（无实时屏幕/终端镜像/逐条消息级协同）。
- 开源社区贡献密度低（企业主导），路线受 OpenAI 商业策略牵动。

---

## 二、Google Gemini（编程相关能力 / Gemini CLI）

### 1. 定位与形态（注意：2026-06 重大转向）

| 形态 | 说明 | 状态（截至 2026-09） |
|---|---|---|
| **Gemini CLI** | 开源（Apache-2.0）、TypeScript 终端编码 Agent，Google Cloud 官方 | **消费级已于 2026-06-18 停服**，功能并入 Antigravity CLI |
| **Gemini Code Assist** | IDE 插件（VS Code / JetBrains / Android Studio / Cloud Shell / Cloud Workstations），Standard / Enterprise 两档 | **Enterprise/团队版不变**；消费级个人版 + AI Pro/Ultra 用户 2026-06-18 迁移到 Antigravity |
| **Gemini Code Assist for GitHub** | GitHub PR 评审 / issue 分诊 | 消费级 2026-06-18 deprecate、07-17 关闭；企业版不变 |
| **Antigravity** | Google 新一代「agent-first 开发平台」：Antigravity 2.0 桌面 App + Antigravity CLI + 服务端 harness | 承接 Gemini CLI/Code Assist 消费级用户 |

来源：[Gemini CLI 官方博客（Google Cloud）](https://cloud.google.com/blog/ja/topics/developers-practitioners/introducing-gemini-cli/)、[Gemini CLI 与 Code Assist 停服（9to5Google，2026-06-17）](https://9to5google.com/2026/06/17/gemini-cli-code-assist-shutting-down/)、[Bye-bye Gemini CLI（The Register）](https://www.theregister.com/ai-ml/2026/05/20/bye-bye-gemini-cli-google-nudges-devs-toward-antigravity/)

> **关键背景**：Google 官方口径是「Gemini CLI 证明了终端是 Agent 任务的绝佳界面，但你们的需求已转向**多 Agent 协作 + 统一后端**」，故把精力收敛到 Antigravity。官方承认 Antigravity CLI 上线初期「无 1:1 功能对等」，但保留 **Agent Skills / Hooks / Subagents / Extensions** 等最关键能力。

### 2. 目标用户与核心场景

- **个体开发者**：免费额度（个人 Google 账户）下在终端内查询/编辑大型代码库、从 PDF/图片/草图生成 App、调试排障。
- **企业团队**：Code Assist Enterprise 的私有仓库代码定制（索引）与合规管控。
- **自动化/CI**：headless 模式 + GitHub Action（PR 评审、issue 分诊）。

### 3. 核心功能

- **1M token 上下文 + Gemini 3 系列模型**（推理 + 多模态）。
- **内置工具**：Google Search grounding、文件操作、shell 命令、web fetch。
- **MCP 扩展**：可接 Imagen/Veo/Lyria 等媒体生成。
- **Headless 模式**：`gemini -p` 非交互运行，支持 JSON / JSONL（流式）输出与结构化退出码（0 成功 / 42 输入错 / 53 超轮次）——天然适合远程/脚本驱动。
- **会话 checkpointing、GEMINI.md 项目上下文、Agent Skills、Subagents、Extensions、Hooks**。
- **GitHub Action 集成**（run-gemini-cli）。

来源：[google-gemini/gemini-cli README](https://github.com/google-gemini/gemini-cli)、[Gemini CLI headless 模式文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/headless.md)

### 4. 审批与沙箱机制（关键，成熟度高）

**政策引擎（Policy Engine）**：TOML 规则，每条规则 = 条件 + 决策（`allow` / `deny` / `ask_user`）+ 优先级；按 **tier 分层**（Admin > User > Workspace > Extension > Default），final_priority = tier_base + toml_priority/1000。`deny` 会把工具从模型记忆里整体排除（省 context 且更安全）。

**审批模式（Approval Modes）**：`Shift+Tab` 循环切换

| 模式 | 行为 |
|---|---|
| `default` | 标准交互，多数写工具需确认 |
| `autoEdit` | 面向自动编辑，部分写工具自动放行 |
| `plan` | 严格只读，用于研究与设计（Plan Mode） |
| `yolo` | 全自动放行（慎用） |

**Plan Mode**：先只读研究 → 用 `ask_user` 对齐方案 → 产出 Markdown 计划文件（可 `Ctrl+X` 在编辑器里直接改/留言，Agent 自动感知并调整）→ 用户正式审批后自动退出并执行。退出时可选「自动接受编辑 / 手动接受编辑」。

**沙箱**：macOS Seatbelt（permissive/restrictive/strict × open/proxied 多档）、Docker/Podman、Windows 原生、gVisor/runsc（最强）、LXC/LXD；支持 **Sandbox Expansion（动态扩权）**——命令被沙箱拦下时弹「扩展请求」对话框，批准后仅本次以扩展权限执行。

来源：[Gemini CLI Policy Engine 文档](https://geminicli.com/docs/reference/policy-engine/)、[Gemini CLI Sandbox 文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/sandbox.md)、[Gemini CLI Plan Mode 文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/plan-mode.md)

### 5. 移动端与远程能力（重点深挖 ⭐）

**Gemini 编程能力没有官方移动端遥控桌面 Agent 的产品**，其「远程/异步审批」能力由以下机制拼成：

- **无官方手机 App 遥控 CLI**：Gemini 手机 App 是通用聊天助手，不能查看/审批 CLI 会话。
- **Headless + JSON**：把 CLI 变成可被脚本/服务端驱动的远程接口（但输出是文本，无审批 UI）。
- **系统通知（实验性）**：用终端 OSC 9 序列发系统通知，覆盖两类事件——**「Action required」（模型等待输入或工具审批，提醒你去干预）** 与 **「Session complete」（会话完成）**；支持 iTerm2 / WezTerm / Ghostty / Kitty，不支持则回退到终端响铃。定位正是「长任务 / Plan Mode 下切去干别的，让 CLI 后台跑」。
- **Plan Mode 的审批流**是唯一的「人在环」异步审批形态，但发生在桌面终端内。

来源：[Gemini CLI Notifications 文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/notifications.md)、[Gemini CLI headless 文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/headless.md)

**要点判断**：Gemini 完全放弃「手机遥控」这条线（连桌面通知都只是实验性），把未来押在 Antigravity 的「多 Agent 平台」。这印证了「手机遥控桌面 Agent」是 Codex 刚试水、Gemini 空白的细分机会。

### 6. 技术栈与模型

- **CLI**：TypeScript（npm `@google/gemini-cli`），Apache-2.0，周更（preview/stable/nightly 三通道）。
- **模型**：Gemini 3 系列（Gemini 3 Pro 发布时多项 benchmark 屠榜，随后被 GPT-5.1-Codex-Max 反超）。
- **沙箱底座**：Seatbelt / Docker / gVisor 等多后端，隔离强度可选。

### 7. 商业模式与定价

**Gemini CLI 配额**（按账户类型，请求数/天）：

| 认证方式 | 档位 | 请求/天 |
|---|---|---|
| Google 账户 | Code Assist 个人（免费） | 1,000 |
| Google AI Pro | $19.99/月 | 1,500 |
| Google AI Ultra | $249.99/月 | 2,000 |
| Workspace | Code Assist Standard | 1,500 |
| Workspace | Code Assist Enterprise / AI Ultra | 2,000 |
| Gemini API key | 免费（仅 Flash） | 250 |
| Gemini API key / Vertex | 按量付费 | 按 token |

**Gemini Code Assist 席位价**：Standard **$19/用户/月**（年付；月付 $22.80）、Enterprise **$45/用户/月**（年付，含私有仓库代码定制索引）。两者均带 1M context、GCP 全家桶集成（BigQuery/Firebase/Databases/Colab Enterprise）、ISO/SOC 合规、生成式 AI 侵权补偿。

来源：[Gemini CLI Quotas and pricing（官方）](https://github.com/google-gemini/gemini-cli/blob/main/docs/resources/quota-and-pricing.md)、[Gemini Code Assist Review 2026（AI Coder Scope）](https://aicoderscope.com/blog/gemini-code-assist-review-2026/)

> 注意：消费级免费 + AI Pro/Ultra 的 Gemini CLI 通道已于 2026-06 停用，上述「Google 账户 1,000/天」免费档对 CLI 而言实际已随停服失效（保留给 Antigravity 过渡），**具体以 Google 官方 Antigravity 公告为准，待核实**。

### 8. 市占/热度/口碑

- **GitHub stars：106,833**（google-gemini/gemini-cli，2026-09-06 查询），forks 14,532，Apache-2.0，TypeScript。
- **OSSInsight《Coding Agent Wars》（2026-03）**：Gemini CLI 以 98,735 stars 排第 2，590 位贡献者，近 30 天 603 commits。归类「企业火箭」。
- **口碑**：免费额度慷慨、沙箱/政策引擎/Plan Mode 被公认是 CLI 生态里最成熟的治理能力；但**消费级路线反复**（开源 CLI 一年即停服转向闭源 Antigravity）引发社区不满——The Register 标题直言「Google 拿你换了闭源 AI」。

来源：[OSSInsight Coding Agent Wars 2026](https://ossinsight.io/blog/coding-agent-wars-2026)、[Bye-bye Gemini CLI（The Register）](https://www.theregister.com/ai-ml/2026/05/20/bye-bye-gemini-cli-google-nudges-devs-toward-antigravity/)

### 9. 亮点与短板

**亮点**
- **Policy Engine + 分层 tier** 是治理能力天花板（规则化 allow/deny/ask_user，admin 可强覆盖）。
- Plan Mode「先规划→协同编辑计划→审批执行」的人机协作范式成熟。
- 沙箱后端最全（Seatbelt/Docker/gVisor/LXC）+ 动态扩权，隔离强度业界领先。
- Headless/JSON + 退出码，远程自动化接口设计干净。

**短板**
- **无移动端遥控能力**，远程审批体验几乎为零（仅实验性终端通知）。
- 消费级路线摇摆、开源承诺被打破（CLI 停服转闭源），开发者信任受损。
- 多 Agent 平台（Antigravity）尚新，1:1 功能未对等。

---

## 三、对我们的启示（功能级，可直接被迭代消费）

> 视角：本项目是「手机遥控桌面 Coding Agent」，核心支柱是①连接稳定②消息及时准确③审批是遥控人机协作的咽喉。

### 1. 「手机审批桌面 Agent」方向被巨头验证，但存在明确的差异化空白
- **事实**：Codex 已把「手机查看实时环境 / 审阅输出 / 审批命令 / 换模型 / 起新任务」做成官方能力（preview、全计划可用）；Gemini 完全没做移动端。
- **机会**：Codex 移动端是「多任务指挥中心」的**轻量**形态，**没有实时屏幕/终端镜像、没有逐条消息级协同**。我们的差异点是**深度遥控单任务**——实时投屏/终端流 + 逐条消息 + 逐条审批，把「遥控」做到比「巡场」更深。
- **可借鉴**：Codex 移动端的「审批命令 + 切换模型 + 起新任务」三件套，应作为我们手机端审批/控制面板的最低功能对齐线。

### 2. 审批粒度必须「按风险分层」，而非一刀切
- Codex 的「沙箱模式 × 审批策略」、Gemini 的「审批模式 + 政策引擎」都是**四档左右的风险分级**。
- **落地建议**：我们桌面 Agent 的执行/文件访问也应分 `read-only / workspace-write / full-access` 三档；手机端审批给出**快捷裁决**——「允许本次 / 本次会话允许 / 始终允许（写入策略）/ 拒绝」，与 Gemini 的「context-aware 持久批准」对齐，避免「要么太严寸步难行、要么全开」的 Codex 两极陷阱（社区已点名此痛点）。

### 3. 借鉴 Gemini「Policy Engine」做手机端可配置的审批策略
- Gemini 的 TOML 规则（toolName + commandPrefix + allow/deny/ask_user + 优先级 + 分层）是**规则化免审批/必审批**的范本。
- **落地建议**：在手机 App 提供「审批规则」配置页——用户可设「`git status`/`git diff`/`ls` 等只读命令免审批」「`rm -rf`/`git push` 等危险命令必审批」，规则存服务端投影、单向数据流下发。这是可让用户「一次配置、长期省心」的护城河功能，且完全契合「审批是咽喉」的 P0 定位。

### 4. 借鉴「Plan Mode」做「先规划→手机审批→执行」
- Gemini 的 Plan Mode 流程（只读研究 → ask_user 对齐 → 产出计划文件 → 用户协同编辑 → 正式审批 → 自动执行）与我们的 **Goal/排队消息语义**天然契合。
- **落地建议**：让桌面 Agent 对大任务先产出「执行计划」（Markdown），手机端可**审阅/批注/改/批准**后 Agent 才进入执行循环；与既有「中断语义=有排队消息时弹框二选一」合并成统一的「计划-执行-审批」状态机。

### 5. 手机推送天然是「审批提醒」的最优解，Gemini 用终端通知的痛我们直接赢
- Gemini CLI 只能用 OSC 9 终端系统通知（实验性、依赖特定终端、不支持就退化响铃）来提醒「Agent 等审批」。
- **我们优势**：手机推送就是原生能力，且铁律已定「100~300ms 送达」。把「Agent 需要审批 → 手机推送 + 横幅 + 可直达审批页」做成 P0，是 Gemini 想做而做不到、Codex 只在 App 内做（未必有系统级推送）的差异点。

### 6. Headless/JSON 接口应成为桌面 Agent 的标配能力
- 两家都把 CLI 做成可被脚本/服务端驱动的 headless 接口（`codex exec` / `gemini -p --output-format json`）。
- **落地建议**：我们的桌面 DSH 服务端应保留「headless 投影」——把 Agent 状态/事件/审批请求以结构化事件流推给手机（本项目已有 `/remote/connected`、`/remote/phone-logs`、`/remote/health` 投影机制），确保手机端永不本地推算、一切以服务端投影为准（契合铁律 6）。

### 7. 警惕：产品路线摇摆会摧毁开发者信任（Gemini 的反面教材）
- Google 开源 Gemini CLI 仅一年即停服消费级、转向闭源 Antigravity，社区强烈不满。
- **启示**：①我们作为独立工具，**路线要稳、承诺要兑现**（尤其审批/遥控这类咽喉能力不可反复）；②「多 Agent 协作 + 统一后端」是 Antigravity 押注的大趋势，值得作为远期方向关注，但当前不盲从——先把「手机深度遥控单 Agent」做透。

### 8. 需规避
- **不要**像 Codex 那样让权限「两极分化」：默认值应取「安全但不挡路」的中位（如 `workspace-write + on-request`），并在首次审批时用一次引导讲清三档含义。
- **不要**把远程能力做成「只能看不能控」：Gemini 的 headless 输出只有文本、无审批 UI，价值有限；我们的价值正在于「看得到 + 审得了 + 控得住」闭环。

---

## 附：一手来源清单（按产品）

**OpenAI Codex**
- [openai/codex GitHub（README / docs）](https://github.com/openai/codex)
- [Codex 全面升级（OpenAI 官方）](https://openai.com/zh-Hans-CN/index/introducing-upgrades-to-codex/)
- [Work with Codex from anywhere（OpenAI 官方）](https://openai.com/zh-Hans-CN/index/work-with-codex-from-anywhere/)
- [Codex 费率卡（OpenAI Help Center）](https://help.openai.com/en/articles/20001106-codex-usage-and-rate-limits)
- [Codex 沙箱与审批（社区镜像官方文档）](https://github.com/etheaven/codex-mcp-server/blob/main/docs/concepts/sandbox.md)
- [OpenAI integrates Codex into ChatGPT mobile（Digital Watch Observatory）](https://dig.watch/updates/openai-brings-codex-into-chatgpt-mobile)
- [GPT-5.1-Codex-Max（VentureBeat）](https://venturebeat.com/ai/openai-debuts-gpt-5-1-codex-max-coding-model-and-it-already-completed-a-24)

**Google Gemini**
- [google-gemini/gemini-cli GitHub（README / docs）](https://github.com/google-gemini/gemini-cli)
- [Gemini CLI 官方博客（Google Cloud）](https://cloud.google.com/blog/ja/topics/developers-practitioners/introducing-gemini-cli/)
- [Gemini CLI Policy Engine / Sandbox / Plan Mode / Notifications / Headless 文档](https://geminicli.com/docs/)
- [Gemini CLI Quotas and pricing（官方）](https://github.com/google-gemini/gemini-cli/blob/main/docs/resources/quota-and-pricing.md)
- [Gemini CLI 与 Code Assist 停服（9to5Google）](https://9to5google.com/2026/06/17/gemini-cli-code-assist-shutting-down/)
- [Bye-bye Gemini CLI（The Register）](https://www.theregister.com/ai-ml/2026/05/20/bye-bye-gemini-cli-google-nudges-devs-toward-antigravity/)

**市占/口碑**
- [OSSInsight: The Coding Agent Wars 2026](https://ossinsight.io/blog/coding-agent-wars-2026)
- [Stack Overflow 2025 Developer Survey](https://stackoverflow.blog/2025/12/29/developers-remain-willing-but-reluctant-to-use-ai-the-2025-developer-survey-results-are-here/)
