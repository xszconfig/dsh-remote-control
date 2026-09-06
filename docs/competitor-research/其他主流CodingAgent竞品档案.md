# 其他主流 Coding Agent 竞品档案（补录）

> 本文档是 Coding Agent 竞品调研的**补录部分**。用户已点名的 Claude Code / Cursor / Codex / Gemini / Trae / Manus / WorkBuddy 由其他调研任务覆盖，本文档负责从候选名单中甄别并深挖**市占率高 / 声量大**的补充产品。
>
> 调研范围：GitHub Copilot（含移动 App）、Windsurf、Devin、Replit、Lovable、Bolt.new、v0、Augment、JetBrains AI、通义灵码、百度 Comate、Aider、OpenHands 共 13 个候选。
> 数据口径：**截至 2026-09**，以一手来源（官网 / 官方博客 / 应用商店 / 官方 changelog）为主，二手评测为辅；不确定项明确标「待核实」。
> 本项目背景：**手机遥控桌面 Agent**（dsh-remote-control），因此所有产品重点考察「移动端 App / 远程控制 / 审批体验」三个维度。

---

## 一、入选与排除清单及理由

### 1.1 入选深挖（5 个）

| 产品 | 厂商 | 入选理由（市占/声量证据） |
|---|---|---|
| **GitHub Copilot** | GitHub / 微软 | 用户规模超 **1500 万**、付费用户约 **180 万**，全球装机与付费规模第一；且是**唯一在移动端 App 完整落地「远程接管本地 Agent 会话」**的产品，与本项目形态最直接对标。 |
| **Devin** | Cognition | 首个「自治 AI 软件工程师」，融资 **10 亿美元 @ 估值 260 亿**；一手缔造「Plan→审批→执行」的审批流范式与「500→20 美元」价格战，是「自主 Agent + 人审批」品类的标杆。 |
| **Replit** | Replit | ARR 约 **7000 万美元**、登上 CNBC Disruptor 50（2026）；**云工作区 + Agent + 移动 App**三位一体，是「云上运行 + 手机遥控」的天然样本，与本项目「远程」概念高度同构。 |
| **通义灵码** | 阿里云 | **插件下载量破千万**、国内市占第一梯队的国产编码助手；且已发布「编程智能体（Agent）」，是「国产 IDE 插件型 Agent」的代表，对本项目（国产 + 手机端）有直接参考价值。 |
| **Lovable** | Lovable（瑞典） | 「氛围编程（vibe coding）」鼻祖，用户破 **800 万**、估值从 **66 亿 → 133 亿美元**；代表「自然语言 → 直接生成可运行 App + 手机预览」这一高声量新品类，是声量最大的非专业开发者工具。 |

### 1.2 排除及理由

| 候选 | 结论 | 排除理由（附证据） |
|---|---|---|
| **Windsurf（原 Codeium）** | 排除 | 曾是企业 LLM 流量第二大来源（42 亿 tokens），但 2025-2026 在 AI IDE 战中**明显败给 Cursor 与 Claude Code**：用户流失、被收购失败、定价动荡引发开发者集体反弹。已非「值得纳入」的高增长标的，且 Cursor 已由他人覆盖。[Windsurf 衰退分析](https://agentmarketcap.ai/blog/2026/04/05/windsurf-decline-codeium-cursor-claude-code-ide-war) · [收购失败复盘](https://aihackers.net/posts/windsurf-acquisition-collapse-2025/) |
| **Bolt.new** | 排除（归类） | 与 Lovable 同为「AI 应用构建器 / vibe coding」品类，声量与估值略低（StackBlitz 旗下）。以 Lovable 为代表覆盖该品类，避免重复。 |
| **v0** | 排除 | Vercel 出品，定位偏「UI / 组件生成」，非完整 Agent，热度中等；可并入 Lovable 同品类参考。 |
| **百度 Comate（文心快码）** | 排除（归类） | 国内第二，IDC 报告代码生成评估第一、已升级 3.5S 多智能体自协同，但整体声量/装机略逊于通义灵码。以通义灵码为代表覆盖「国产 IDE 插件 Agent」品类。[文心快码 3.5S](https://segmentfault.com/a/1190000047252917) |
| **Aider** | 排除 | 开源终端结对编程工具，约 **38.1k Star**，口碑好但纯 CLI、无移动/远程能力，用户以极客个人为主，不构成「移动遥控」对标。[Aider 38.1k Star](https://blog.csdn.net/feikillyou/article/details/155450420) |
| **OpenHands（原 OpenDevin）** | 排除 | 开源自治 Agent 框架，约 **6.4 万 Star**，在开源社区有声量，但缺乏成规模的商业化用户与移动端能力，对标价值弱于 Devin。[OpenHands 6.4 万 Star](https://www.163.com/dy/article/KE0DFN650511AQHO.html) |
| **Augment Code** | 排除 | 企业级（enterprise）编码 Agent，公开用户/营收数据不透明，声量集中在企业销售渠道，不适合作为消费级「移动遥控」对标。 |
| **JetBrains AI（Junie → AI Assistant）** | 排除 | Junie 翻车后转向「AI Assistant」，是 IDE 厂商的跟随者而非引领者，声量有限；其价值主要是「捆绑 IDE」而非独立 Agent。 |

---

## 二、入选产品逐产品档案

### 1. GitHub Copilot —— 「移动端远程接管本地 Agent」的直接标杆

- **定位与形态**：IDE 插件（VS Code / JetBrains / Visual Studio / Xcode）+ **GitHub Mobile App** + Web；从「代码补全」进化为「编程伙伴」，2025 年起主推 **Coding Agent（agent 模式）** 与 CLI Agent。形态最全：桌面 IDE、终端 CLI、Web、移动 App 四端。
- **目标用户与核心场景**：覆盖从个人开发者到企业团队的**全谱系**；核心场景是「补全/问答 → 多文件编辑 → Agent 自主改代码/跑终端/提交 PR」，以及**碎片时间用手机跟进/审批代码任务**。
- **核心功能**：代码补全与内联对话、多文件编辑、Coding Agent（自主规划与执行）、终端命令执行、PR 生成与审查、代码搜索/解释、CLI Agent（Copilot CLI，可在本地环境跑 Agent）。
- **移动端与远程能力（重点，与本项目最直接对标）**：
  - **GitHub Mobile App**：原仅支持通知、PR 审查、代码浏览；2025-06 起 **Coding Agent 上线移动端（public preview）** [官方 changelog](https://github.blog/changelog/2025-06-04-github-copilot-coding-agent-now-available-on-github-mobile/)。
  - **Copilot cloud agent（云 Agent）上移动端**：2026-04 起可在手机上「随时随地研究与编码」[changelog](https://github.blog/changelog/2026-04-08-github-mobile-research-and-code-with-copilot-cloud-agent-anywhere/)，2026-07 进一步支持**在手机上修复合并冲突** [changelog](https://github.blog/changelog/2026-07-08-github-mobile-fix-merge-conflicts-with-copilot-cloud-agent/)。
  - **远程会话（Remote Sessions）——核心亮点**：官方发布「Take your local GitHub sessions anywhere」[官方博客](https://github.blog/news-insights/product-news/take-your-local-github-sessions-anywhere/) / [中文解读](https://skillnav.dev/articles/take-your-local-github-sessions-anywhere)：用户在**桌面**跑 Copilot CLI Agent，中途离开座位，可用**手机/网页**远程**连接并接管同一会话**，查看 Agent 进度、审批/拒绝其下一步操作、继续下达指令。这正是本项目「手机遥控桌面 Agent」的已落地形态。
  - **审批体验**：Agent 执行敏感操作（git 提交、终端命令、写文件）前请求确认，审批入口在 IDE、CLI、**移动端/网页端**均可完成；远程会话支持从手机「批准/拒绝」单步操作。
- **技术栈与模型**：微软/GitHub 生态；模型为多模型后端（OpenAI GPT/o 系列 + Anthropic Claude 系列，按任务路由，具体绑定版本随迭代变化，**待核实**）。Agent 运行在本地 IDE/CLI 或云端 sandbox（cloud agent）。
- **商业模式与定价**：免费版 + Pro（$10/月）+ Pro+（$39/月）+ Business（$19/用户/月）+ Enterprise（$39/用户/月）[官方定价页](https://github.com/features/copilot/plans)。靠存量 GitHub 开发者规模实现「量」的变现。
- **市占/热度/口碑**：用户超 **1500 万** [IT之家](https://www.ithome.com/0/850/432.htm)；付费用户约 **180 万**，贡献约 40% 收入增长 [东北证券](https://www.sgpjbg.com/labelsyh/githubcopilotshangyehuashuju.html)。口碑：补全质量稳定、生态最完整，但被诟病「Agent 深度/自主动手能力不如 Cursor/Claude Code」。
- **亮点与短板**：亮点=四端全覆盖 + 移动远程会话 + 最大装机量；短板=Agent 自主能力非最强、强绑定 GitHub 生态、企业版价格偏高。
- **对我们的启示（落到功能级）**：
  1. **「远程接管同一会话」是已验证刚需**：Copilot Remote Sessions 证明「桌面起任务、手机中途审批/继续」有真实需求——本项目应把「会话漫游（同一 session 桌面↔手机无缝接续）」作为核心差异点，而非只做「手机镜像桌面」。
  2. **审批要做成「单步可批准/拒绝 + 移动端可达」**：Copilot 的敏感操作逐条审批（而非整段任务一刀切）值得借鉴；审批入口必须移动端原生，不能只依赖桌面弹窗。
  3. **云 Agent（cloud agent）与本地 Agent 双轨**：Copilot 用「本地 CLI agent + 云 agent」两条腿补足手机算力不足——本项目可考虑「桌面主机跑 agent（本地）+ 手机仅做轻客户端遥控」的架构，避免手机端承担重计算。

---

### 2. Devin（Cognition）—— 「自主 AI 软件工程师 + 审批流」范式定义者

- **定位与形态**：**云端自治 AI 软件工程师**，Web 工作区（Workspace，本质是云端 Linux VM）+ Slack/Linear/Jira/GitHub 集成；无独立桌面 IDE，也无原生移动 App，主要通过 **Web + Slack** 交互。
- **目标用户与核心场景**：工程团队与创业公司；场景是「给它一个 Jira ticket / PR / 需求，它自主规划 → 写码 → 测试 → 提 PR」，人从「写代码」转为「分配任务 + 审批把关」。
- **核心功能**：**Plan → 审批 → Execute 三段式**；多文件编辑、终端/浏览器操作、跑测试、提 PR；知识库（Memory/Playbooks）；自动化（Automations）、定时会话（Scheduled Sessions）。
- **移动端与远程能力（重点）**：
  - 无原生 App，但 **Workspace 是 Web 形态**，手机浏览器可查看任务进度与 diff（体验一般，**待核实**）。
  - **Slack 集成是其「远程审批」主通道**：任务启动、阶段完成、需要决策时推到 Slack，用户可在 Slack 里直接回复批准/纠正，实现「异步远程遥控」。[Devin 框架说明](https://www.agentpatternscatalog.org/compositions/devin/)
  - **审批体验**：先看「Plan」（Agent 列出要做什么）→ 人批准或修改 → Agent 才执行；执行中遇关键决策再回调。这是「事前计划审批 + 事中回调」的标杆范式。[官方文档 Automations](https://docs.devin.ai/zh/product-guides/automations)
- **技术栈与模型**：Cognition 自研 + 多模型（具体模型组合**待核实**）；云端隔离沙箱执行，强调「安全/可回放」。
- **商业模式与定价**：从 $500/月 起家，Devin 2.0 起**大幅降价至 $20/月起**（按用量 ACU 计费 + 企业订阅）[InfoQ](https://www.infoq.cn/article/jaoqgyvvxbl31degidfz) · [至顶网](https://m.zhiding.cn/article/3165033.htm)；定价战对整个品类有风向标意义。[价格战分析](https://agentmarketcap.ai/blog/2026/04/07/coding-agent-pricing-wars-devin-commodity)
- **市占/热度/口碑**：融资 **10 亿美元 @ 估值 260 亿**，已自写约 89% 自有代码 [TFN](https://techfundingnews.com/the-ai-startup-replacing-software-engineers-just-raised-1b-at-26b-valuation-and-it-is-already-writing-89-of-cognitions-own-code/)；声量极高但**付费用户规模与 Copilot/Cursor 不在一个量级**，企业客单价高、渗透尚浅。口碑：自主能力惊艳，但「贵 + 结果需人验收 + 常跑偏需重跑」是高频吐槽。
- **亮点与短板**：亮点=自治深度最强、审批流范式、Slack 异步协作；短板=价格曾过高、无原生移动端、Web 移动体验弱、结果不确定性需反复人验收。
- **对我们的启示**：
  1. **「Plan 先行、人批准后才动工」比「边做边问」更可预期**：本项目可借鉴「Agent 先给执行计划，手机端一键批准/编辑再执行」，降低用户对「Agent 乱改」的恐惧。
  2. **异步通知通道（IM）是移动遥控的轻量替代**：Devin 用 Slack 而非原生 App 实现远程审批——提示本项目：若短期做不出完整移动 App，可先用「IM 通知 + 快捷批准」承接远程审批。
  3. **定价锚点下移是大势**：$500→$20 说明「按用量/订阅」才是主流，本项目若做商业化，应避免高门槛定价。
  4. **规避**：Devin 的「结果不可控 → 反复重跑」被大量吐槽——本项目应强化「可回放/可回滚/每步可审」，把不确定性变成可管控。

---

### 3. Replit —— 「云工作区 + Agent + 移动 App」三位一体

- **定位与形态**：**云端 IDE / 工作区**（浏览器 + 桌面 App + **移动 App**），2025 年起主推 **Replit Agent**：从一句话需求直接「生成 → 运行 → 部署」完整应用。Agent 天然跑在云端，无需本地环境。
- **目标用户与核心场景**：从学生/业余开发者到「下一个十亿软件创作者」；场景是「说人话 → 得到能跑的应用」，尤其适合 MVP 快速验证与学习。
- **核心功能**：Replit Agent（自然语言生成应用 + 自主迭代）、云端环境（每项目自带 VM/数据库）、一键部署与托管、实时协作、Agent 动作可视化（changelog 可见 Agent 每一步）。
- **移动端与远程能力（重点）**：
  - **官方移动 App 支持「完整应用开发」**：手机端不仅能看代码/运行结果，还能**驱动 Agent 创建与迭代应用、预览、部署**。[DeepLearning.AI 报道](https://www.deeplearning.ai/the-batch/replits-agent-powered-mobile-app-expands-to-full-app-development) · [移动 App 文档](https://docs.replit.com/features/platforms/mobile-app)
  - **架构本质 = 手机是「云 Agent 的遥控器」**：因为代码与 Agent 都在云端运行，手机 App 天然就是「远程遥控」形态——这对本项目「手机遥控桌面 Agent」是**最接近的同类样本**（区别：Replit 的「桌面」是云端 VM，本项目的「桌面」是用户本机）。
  - 支持原生移动 App 构建与真机预览 [Native Mobile Apps 文档](https://docs.replit.com/features/artifact-types/building-mobile-apps)。
- **技术栈与模型**：云端容器化工作区；Agent 后端多模型（Claude + 自研，具体**待核实**）；一键部署到 Replit 托管域名。
- **商业模式与定价**：免费版（额度限制）+ Core（约 $20/月）+ Teams/企业；ARR 约 **7000 万美元**（AI 时代逆袭后）[Startup Founder Stories](https://startupfounderstories.com/stories/amjad-masad-replit-ai-coding)，入选 **CNBC Disruptor 50（2026）** [CNBC](https://www.cnbc.com/2026/05/19/replit-cnbc-disruptor-50-ranking.html)。
- **市占/热度/口碑**：ARR 7000 万 + Disruptor 50 显示其声量与商业落地俱佳；口碑：上手极快、Agent 生成 MVP 强，但「复杂工程能力/大项目维护」弱于专业 IDE Agent，重度开发者留存有限。
- **亮点与短板**：亮点=端到端「生成→跑→部署」闭环、移动 App 体验最好、零环境成本；短板=不适合已有大型仓库、受限于托管生态、深度定制能力弱。
- **对我们的启示**：
  1. **「云端运行 + 手机遥控」的用户心智已被 Replit 教育过**：本项目可复用这一叙事——「桌面主机=你的云，手机=遥控器」，强调「复用你本机环境（而非迁移到云）」作差异化。
  2. **移动端要能「看 Agent 每一步」而非只看结果**：Replit 的 Agent 动作 changelog 让用户放心；本项目手机的「会话/执行流」可视化应向它看齐（时间线 + 每步状态 + 可中断）。
  3. **一键部署/预览是强粘性**：Replit 的「生成即部署」让用户有即时正反馈；本项目可考虑「Agent 产出 → 手机端直接预览/验证」的闭环，缩短反馈链。

---

### 4. 通义灵码（Tongyi Lingma）—— 国产 IDE 插件型 Agent 头号玩家

- **定位与形态**：阿里云出品，**IDE 插件**（VS Code / JetBrains / Visual Studio）+ 独立 IDE + Web 云工作区；从「代码补全」演进到「灵码 IDE + 编程智能体（Agent）」。
- **目标用户与核心场景**：国内个人开发者与企业研发团队；场景是「国产化/合规环境下的补全、问答、评审、Agent 自动化编码」，企业版主打私有化与知识库集成。
- **核心功能**：代码补全、单元测试生成、代码评审、自然语言生成代码、**Agent 模式（多文件自主编辑）**、**MCP 集成**、记忆能力（通义灵码 2.5 起）。[通义灵码 2.5 解析](https://developer.aliyun.com/article/1663414) · [编程智能体上线](https://developer.aliyun.com/article/1662816)
- **移动端与远程能力（重点）**：
  - **移动端/远程是明显短板**：通义灵码目前以桌面 IDE 插件与 Web 云工作区为主，未见成熟的「手机 App 遥控 Agent」能力（**待核实**）。这既是其短板，也是本项目在国内的差异化机会。
  - 企业版有代码评审、知识库、合规等能力，但交互仍锚定在桌面 IDE。
- **技术栈与模型**：基于阿里「通义千问（Qwen）」系列模型；企业版支持私有化部署与专属模型，合规性是其核心卖点。
- **商业模式与定价**：个人版免费（额度）+ 企业版按席位/私有化收费；靠阿里云生态与企业市场变现。
- **市占/热度/口碑**：**插件下载量破千万** [阿里云开发者社区](https://developer.aliyun.com/article/1652207)，国内市占第一梯队；口碑：补全质量国产第一档、企业合规强，但「Agent 自主深度、移动体验」落后于海外头部。
- **亮点与短板**：亮点=国产合规/私有化、企业渗透强、Qwen 模型自研；短板=无移动遥控、Agent 自主性偏弱、生态绑定阿里云。
- **对我们的启示**：
  1. **「国产 + 移动遥控」目前是真空地带**：国内主流编码助手（通义灵码/文心快码）都聚焦桌面 IDE，**没有一家把「手机遥控桌面 Agent」做起来**——这正是本项目的国内差异化定位，可作为主打叙事。
  2. **企业合规/私有化是国产工具的护城河**：若本项目未来做企业版，可借鉴通义灵码的「私有化 + 知识库 + 评审」组合，但用「移动遥控」打差异化。
  3. **MCP 集成是必补能力**：通义灵码 2.5 已接入 MCP，说明「Agent 连接外部工具」已成标配，本项目应规划 MCP 兼容以不被生态排除。

---

### 5. Lovable —— 「氛围编程（vibe coding）」鼻祖，自然语言直出可运行 App

- **定位与形态**：**Web 端 AI 应用构建器**（无 IDE/无桌面 Agent），输入自然语言 → 生成带后端（Supabase）的**可运行、可部署、可分享**的 Web/移动响应式应用。
- **目标用户与核心场景**：**非专业开发者**（创始人、产品/设计、业务人员）；场景是「我有一个想法 → 30 秒得到一个能跑能分享的 App」，即「氛围编程」。
- **核心功能**：自然语言 → 全栈应用生成；可视化编辑与迭代（点哪里改哪里）；内置 Supabase 后端（数据库/鉴权）；一键部署 + 分享链接 + 域名；多模型切换。
- **移动端与远程能力（重点）**：
  - **无「遥控桌面 Agent」概念**：Lovable 是「云端生成应用」，不是「遥控某台机器上的 Agent」——与本项目形态不同，但高度相关于「移动端体验」。
  - 生成的应用**天然移动响应式**，可手机浏览器打开分享链接直接预览/演示——「手机即验收入口」的体验极佳。
  - 无原生 App 做生成操作，编辑主要在桌面浏览器，手机以「预览/分享」为主。
- **技术栈与模型**：React/Next 前端 + Supabase 后端；模型多后端（GPT/Claude 等，可切换，**待核实**）。
- **商业模式与定价**：免费额度 + 订阅（分档，含用量 credit）+ 团队版；企业化是当前扩张方向。
- **市占/热度/口碑**：用户破 **800 万** [站长之家](https://m.chinaz.com/ainews/22684.shtml)；2025-12 融资 **3.3 亿美元 @ 估值 66 亿** [Finsmes](https://www.finsmes.com/2025/12/lovable-raises-330m-in-series-b-funding-at-6-6-billion-valuation.html)，2026-08 估值升至 **133 亿** [Business Standard](https://www.business-standard.com/technology/tech-news/lovable-s-13-3-billion-valuation-puts-ai-coding-s-saas-challenge-in-focus-126081300705_1.html)。口碑：非开发者「真能用」、正反馈极快；但「生成的应用难长期维护/工程化」，专业开发者普遍看不上。
- **亮点与短板**：亮点=极低门槛、端到端闭环、分享/部署体验；短板=不可维护、难迁移、生成质量上限明显、与「已有代码库」无关。
- **对我们的启示**：
  1. **「低门槛 + 即时正反馈」是出圈的密码**：Lovable 证明「小白 30 秒看到能跑的东西」能带来 800 万用户——本项目应把「手机上一句话 → 桌面 Agent 动起来 → 手机立刻看到结果」的**反馈链缩到最短**，做成核心爽点。
  2. **手机是天然的「验收/演示入口」**：即便本项目是「遥控桌面 Agent」，也应把「Agent 产出在手机上可预览/可分享」作为必备体验，复用 Lovable 验证过的「分享链接即演示」。
  3. **区分「生成新 App」与「维护存量工程」两个市场**：Lovable 吃掉的是「新 App 生成」市场，本项目吃的是「存量工程 + 桌面环境 + 手机遥控」——定位上要明确避开 Lovable 的正面，强调「在你的真实机器/仓库上干活」。

---

## 三、横向对比速览

| 维度 | GitHub Copilot | Devin | Replit | 通义灵码 | Lovable |
|---|---|---|---|---|---|
| 形态 | IDE插件+CLI+**移动App**+Web | 云端 Web 工作区 | 云 IDE + **移动 App** | IDE 插件 + Web | Web |
| Agent 自主度 | 中高（Agent 模式） | **高（自治工程师）** | 中（生成/迭代） | 中 | 中（生成新 App） |
| 移动端 App | ✅ 最强（含远程接管会话） | ❌（Web/Slack 替代） | ✅（完整开发） | ❌ | ❌（仅预览） |
| 远程控制 | ✅ **Remote Sessions** | ⚠️ Slack 异步 | ✅ 云即远程 | ❌ | ❌ |
| 审批体验 | ✅ 单步审批+移动端 | ✅ **Plan 审批范式** | ⚠️ Agent 动作可见 | ⚠️ | ⚠️ |
| 运行位置 | 本地或云 | 云沙箱 | 云 | 本地 IDE / 云 | 云 |
| 定价 | $10–39/月 | $20 起（曾 $500） | 免费 + ~$20/月 | 免费 + 企业 | 免费 + 订阅 |
| 与本项目相似度 | ★★★★★ | ★★★★ | ★★★★★ | ★★★ | ★★★ |

**结论**：与本项目「手机遥控桌面 Agent」最直接对标的是 **GitHub Copilot（远程会话）** 与 **Replit（云工作区 + 移动 App）**；**Devin** 提供「审批流范式」，**Lovable** 提供「低门槛 + 即时正反馈」的出圈打法，**通义灵码**则揭示了「国产 + 移动遥控」尚属真空的市场空白。

---

## 附：数据来源索引

- GitHub Copilot 用户 1500 万：[IT之家](https://www.ithome.com/0/850/432.htm)；付费 180 万：[东北证券](https://www.sgpjbg.com/labelsyh/githubcopilotshangyehuashuju.html)；移动 Coding Agent：[GitHub Changelog 2025-06](https://github.blog/changelog/2025-06-04-github-copilot-coding-agent-now-available-on-github-mobile/)；云 Agent 上移动端：[2026-04](https://github.blog/changelog/2026-04-08-github-mobile-research-and-code-with-copilot-cloud-agent-anywhere/) / [2026-07 修复合并冲突](https://github.blog/changelog/2026-07-08-github-mobile-fix-merge-conflicts-with-copilot-cloud-agent/)；远程会话：[GitHub Blog](https://github.blog/news-insights/product-news/take-your-local-github-sessions-anywhere/) / [中文解读](https://skillnav.dev/articles/take-your-local-github-sessions-anywhere)；定价：[官方](https://github.com/features/copilot/plans)
- Devin：融资与自写代码 [TFN](https://techfundingnews.com/the-ai-startup-replacing-software-engineers-just-raised-1b-at-26b-valuation-and-it-is-already-writing-89-of-cognitions-own-code/)；定价 [InfoQ](https://www.infoq.cn/article/jaoqgyvvxbl31degidfz) / [至顶网](https://m.zhiding.cn/article/3165033.htm)；价格战 [Agent Market Cap](https://agentmarketcap.ai/blog/2026/04/07/coding-agent-pricing-wars-devin-commodity)；审批/自动化 [官方文档](https://docs.devin.ai/zh/product-guides/automations)；框架 [Agent Patterns](https://www.agentpatternscatalog.org/compositions/devin/)
- Replit：ARR [Startup Founder Stories](https://startupfounderstories.com/stories/amjad-masad-replit-ai-coding)；Disruptor 50 [CNBC](https://www.cnbc.com/2026/05/19/replit-cnbc-disruptor-50-ranking.html)；移动 App 全量开发 [DeepLearning.AI](https://www.deeplearning.ai/the-batch/replits-agent-powered-mobile-app-expands-to-full-app-development)；移动 App 文档 [Replit Docs](https://docs.replit.com/features/platforms/mobile-app)；原生移动 App [Replit Docs](https://docs.replit.com/features/artifact-types/building-mobile-apps)
- 通义灵码：插件破千万 [阿里云开发者社区](https://developer.aliyun.com/article/1652207)；2.5 功能 [解析](https://developer.aliyun.com/article/1663414)；编程智能体 [上线](https://developer.aliyun.com/article/1662816)
- 文心快码（Comate）：3.5S [SegmentFault](https://segmentfault.com/a/1190000047252917)；IDC 第一 [百度官方](https://comate.baidu.com/zh/news/honor/11)
- Lovable：用户 800 万 [站长之家](https://m.chinaz.com/ainews/22684.shtml)；融资 3.3 亿 @ 66 亿 [Finsmes](https://www.finsmes.com/2025/12/lovable-raises-330m-in-series-b-funding-at-6-6-billion-valuation.html)；估值 133 亿 [Business Standard](https://www.business-standard.com/technology/tech-news/lovable-s-13-3-billion-valuation-puts-ai-coding-s-saas-challenge-in-focus-126081300705_1.html)
- 排除项：Windsurf [衰退](https://agentmarketcap.ai/blog/2026/04/05/windsurf-decline-codeium-cursor-claude-code-ide-war) / [收购失败](https://aihackers.net/posts/windsurf-acquisition-collapse-2025/)；Aider [38.1k Star](https://blog.csdn.net/feikillyou/article/details/155450420)；OpenHands [6.4 万 Star](https://www.163.com/dy/article/KE0DFN650511AQHO.html)
