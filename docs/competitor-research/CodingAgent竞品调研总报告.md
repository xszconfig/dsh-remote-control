# Coding Agent 竞品调研总报告

> 报告类型：四份竞品档案汇总（按 app-competitor-research 汇总要求）
> 汇总时间：2026-09
> 数据口径：以各档案所引来源标注时间为准；跨来源口径不一致处已显式标注「待核实」
> 本项目对照：**手机遥控桌面 Coding Agent**（dsh-remote-control，Android 优先、中文、手机遥控桌面 DSH Agent）
> 输入档案（同目录，均未 commit）：
> 1. ClaudeCode与Cursor竞品档案.md
> 2. Codex与Gemini竞品档案.md
> 3. WorkBuddy与Trae与Manus竞品档案.md
> 4. 其他主流CodingAgent竞品档案.md（GitHub Copilot / Devin / Replit / 通义灵码 / Lovable）

---

## 一、元信息与调研范围

### 1.1 覆盖产品（12 个）

> 口径说明：任务书写作「11 个产品」，经核对四份档案实际覆盖 **12 个产品**（2 + 2 + 3 + 5）。本报告以档案实数为准，在此显式标注该计数差异。

| # | 产品 | 厂商 | 档案来源 | 与本项目形态关系 |
|---|---|---|---|---|
| 1 | Claude Code | Anthropic | 档案 1 | 终端 Agent + 多端窗口，Remote Control 直接对标 |
| 2 | Cursor | Anysphere | 档案 1 | AI IDE + iOS 遥控 App，无 Android |
| 3 | OpenAI Codex | OpenAI | 档案 2 | 四形态 + ChatGPT 移动端指挥中心 |
| 4 | Google Gemini（CLI / Code Assist / Antigravity） | Google | 档案 2 | 无移动遥控，但治理/Plan 范式是金矿 |
| 5 | 腾讯 WorkBuddy | 腾讯 | 档案 3 | 手机遥控桌面 Agent 落地最完整 |
| 6 | 字节 TRAE（IDE + SOLO） | 字节跳动 | 档案 3 | 「手机 = 轻量调度控制台」同构定位 |
| 7 | Manus | Butterfly Effect | 档案 3 | 云端通用 Agent，产品思想参考 |
| 8 | GitHub Copilot | GitHub / 微软 | 档案 4 | 唯一移动端完整落地「远程接管本地会话」 |
| 9 | Devin | Cognition | 档案 4 | 「Plan→审批→执行」范式定义者 |
| 10 | Replit | Replit | 档案 4 | 「云工作区 + Agent + 移动 App」三位一体 |
| 11 | 通义灵码 | 阿里云 | 档案 4 | 国产 IDE 插件型 Agent 头号玩家 |
| 12 | Lovable | Lovable（瑞典） | 档案 4 | 氛围编程鼻祖，低门槛出圈打法参考 |

### 1.2 排除清单（8 个候选，出自档案 4）

| 候选 | 排除理由（要点） |
|---|---|
| Windsurf（原 Codeium） | AI IDE 战中败给 Cursor/Claude Code，用户流失、收购失败、定价动荡 |
| Bolt.new | 与 Lovable 同属「AI 应用构建器」品类，以 Lovable 为代表覆盖 |
| v0（Vercel） | 偏 UI/组件生成，非完整 Agent，并入 Lovable 品类参考 |
| 百度 Comate（文心快码） | 国内第二，声量/装机略逊通义灵码，以通义灵码为代表覆盖 |
| Aider | 纯 CLI 极客工具（约 38.1k Star），无移动/远程能力 |
| OpenHands（原 OpenDevin） | 开源框架（约 6.4 万 Star），缺商业化用户与移动端 |
| Augment Code | 企业级，公开用户/营收数据不透明 |
| JetBrains AI（Junie→AI Assistant） | IDE 厂商跟随者，非独立 Agent 引领者 |

---

## 二、横向对比矩阵（核心表）

> 口径：2026-09 汇总；「定价」为各档案抓取快照、以官方定价页为准；「待核实」处为来源未确认或口径矛盾的项。竞争度按「与本项目『手机遥控桌面 Agent』的直接对标程度」主观分级（★ 越多越直接）。

| 产品 | 定位形态 | 移动端与远程能力 | 审批机制 | 定价 | 市占 / 口碑 | 直接竞争度 |
|---|---|---|---|---|---|---|
| **Claude Code** | 终端 CLI 优先 + IDE 扩展/桌面/网页/手机 App | **Remote Control**：手机/浏览器成为本地会话远程窗口，执行在本机；多端同会话、断线队列补投；手机 App 偏「远程窗口」，**审批推送缺失**（issue #29438）；有 Android App | 六档权限模式（default/acceptEdits/plan/auto/dontAsk/bypassPermissions）+ auto 分类器代审 + 危险路径永不自动放行 | 早期 $20/月；2026-04 起从 Pro 移除、引导 Max $100/$200（**待核实**） | run-rate 2026-02 约 25 亿美元；开发者采用率 18%；CSAT 91%、NPS 54 | ★★★★ |
| **Cursor** | AI 原生 IDE（VS Code 分支）+ CLI/Web/手机/云端 Agent | **Cursor for iOS**：指挥+监督+审阅合并 PR+Design Mode 画图+语音+Live Activities/灵动岛+推送；Remote Control（循环在云端、工具在本地）；**无 Android、大陆不可用、仅英文、需付费** | 手机上审阅 diff/加 reviewer/让 Agent 修评论/合并；「审批→继续修→合并」闭环 | Hobby 免费 / Pro $20 / Pro+ $60 / Ultra $200 / Teams $40–120 / Enterprise | 约 20 亿美元 ARR；SpaceX 600 亿收购（2026-06）；CNBC Disruptor 50 #37 | ★★★★ |
| **OpenAI Codex** | 四形态（CLI/IDE 扩展/Web 云端/ChatGPT 集成），指挥中心 | 2026-05 集成进 ChatGPT 移动 App（iOS/Android，preview、全计划可用）：查看实时环境/审阅输出/审批命令/切模型/起新任务；**跨 thread 并行，非深度遥控单任务** | 沙箱模式 × 审批策略双轴（read-only/workspace-write/danger-full-access × untrusted/on-request/on-failure/never） | 随 ChatGPT 订阅（Free / Go $8 / Plus $20 / Pro $100 / Business-Enterprise） | GitHub 121,920 stars；OSSInsight「企业火箭」；口碑「权限两极分化」 | ★★★★ |
| **Google Gemini** | Gemini CLI（TS）/ Code Assist / Antigravity；2026-06 消费级 CLI 停服转 Antigravity | **无官方手机遥控**；仅实验性 OSC 9 终端通知（Action required / Session complete）+ headless/JSON；Plan Mode 审批在桌面终端 | **Policy Engine**（TOML allow/deny/ask_user + tier 分层）+ 审批模式（default/autoEdit/plan/yolo）+ Plan Mode + 沙箱多后端 + 动态扩权 | 免费 1000 请求/天（已随停服失效）、AI Pro $19.99、Ultra $249.99；Code Assist $19/$45 | 106,833 stars；治理能力公认最成熟；消费级路线摇摆引发不满 | ★★ |
| **腾讯 WorkBuddy** | 全场景 AI 办公工作台（PC 客户端 + App + 小程序） | **三端同步 + 手机遥控桌面**：手机授权/停止、绿灯状态可见（正在读哪些文件/下一步做什么）、一机连多电脑、锁屏远程、手机采集→桌面处理 | 手机「授权 / 停止」两动作 | Free / Pro $10 / Team $40/席（积分制） | 腾讯称日活中国第一效率 AI 智能体；口碑「能力及格、生态真香」 | ★★★★★ |
| **字节 TRAE（SOLO）** | AI 开发平台（IDE + SOLO Desktop/Web/Mobile） | **手机 = 轻量调度控制台**：三端同源、文件夹级授权+产出回写、并行分发多任务、后台执行+push、语音/Brainstorm、Privacy Mode | 文件夹级授权（远程可控边界）+ review/approvals 场景 | Free / Lite $3 / Pro $10 / Pro+ $30 / Ultra $100（并发分层） | 4.1/5；901 stars；G2 样本少；字节背景地缘顾虑 | ★★★★★ |
| **Manus** | 通用云端 AI Agent（云端工作区 + 移动 App） | 有手机控电脑能力（细节**待核实**）；App 承接「查看进展 + 完成推送」；Plan Mode | Plan Mode（先出计划再执行）+ 执行透明化 | Free / Pro $20/$40 / Team $20/席（积分制） | 峰值 2376 万访问后持续下滑；「套壳缝合怪」争议；Meta 收购被叫停 | ★★★ |
| **GitHub Copilot** | IDE 插件 + GitHub Mobile App + Web + CLI Agent | **Remote Sessions**：手机/网页远程接管本地会话、审批/拒绝单步、继续指令；云 Agent 上移动端、手机上修合并冲突 | 敏感操作（git 提交/终端命令/写文件）**逐条审批**，移动端可达 | Free / Pro $10 / Pro+ $39 / Business $19 / Enterprise $39 | 用户 1500 万、付费 180 万；生态最完整但 Agent 深度弱于 Cursor/Claude Code | ★★★★★ |
| **Devin** | 云端自治 AI 软件工程师（Web 工作区 + Slack/Linear 集成） | 无原生 App；**Slack 异步远程审批**；Web 手机浏览器体验一般（待核实） | **Plan→审批→Execute 三段式**（事前计划审批 + 事中回调） | 曾 $500/月 → 2.0 起 $20/月（ACU 按用量） | 融资 10 亿美元 @ 260 亿；自写 89% 代码；付费规模与 Copilot/Cursor 不在量级 | ★★★★ |
| **Replit** | 云 IDE/工作区 + 移动 App（云上运行） | 移动 App 完整应用开发（驱动 Agent 创建/迭代/预览/部署）；「云 Agent 遥控器」；真机预览 | Agent 动作可视化（changelog 每步可见） | 免费 + Core ~$20/月 + Teams/企业 | ARR 约 7000 万美元；CNBC Disruptor 50 2026；复杂工程弱 | ★★★★ |
| **通义灵码** | IDE 插件 + 独立 IDE + Web（国产） | **移动/远程明显短板**，无手机遥控（待核实） | 企业版代码评审/知识库/合规；Agent 模式多文件编辑 | 个人免费 + 企业席位/私有化 | 插件破千万下载；国产第一梯队；Agent 自主深度弱 | ★★★ |
| **Lovable** | Web 端 AI 应用构建器（vibe coding） | 无「遥控桌面 Agent」；生成 App 移动响应式、手机预览/分享 | 无（可视化「点哪改哪」） | 免费 + 订阅（credit）+ 团队 | 用户 800 万；估值 66 亿→133 亿；非开发者真能用但难维护 | ★★ |

---

## 三、共性趋势（6 条）

1. **「执行在本地/主机，手机是窗口/指挥台」是共同架构共识。** Claude Code Remote Control、Cursor Remote Control、GitHub Copilot Remote Sessions、TRAE「轻量调度控制台」、Replit「云 Agent 遥控器」——五家不约而同把手机定位为「启动 / 监督 / 审阅 / 审批」入口，而非在手机上重做执行。这与本项目「服务端投影为准、客户端不做本地推算」的单一数据流方向完全一致，印证了「手机遥控桌面 Agent」这一产品边界是经过巨头验证的。

2. **审批推送 / 远程审批是共同短板。** Claude Code 至今有「iOS 审批推送缺失」的公开 issue（#29438）；Gemini 只能用实验性终端 OSC 9 通知（不支持就退化响铃）；Codex 的审批只在 App 内、未强调系统级推送；Copilot 有移动端审批但偏「turn 完成」粒度。「手机端审批提醒 + 一键批/拒 + 让 Agent 继续」是各家都没做深、却是刚需的差异化点。

3. **Android 是系统性空位。** Cursor 明确无 Android（iOS 26+ 专属）；Claude 有 Android 但 Remote Control 偏「远程窗口」；Codex 借 ChatGPT App 而非独立遥控客户端；通义灵码/文心快码等国产主力聚焦桌面 IDE、无移动遥控。移动端主力普遍先做 iOS、且多数仅英文 + 大陆不可用——「Android 优先 + 中文 + 本土可部署」是三重复合空位。

4. **Plan 先行范式成为行业共识。** Gemini Plan Mode（只读研究→协同编辑计划→审批执行）、Devin「Plan→审批→Execute」三段式、Manus Plan Mode（先出计划供确认再执行）——「先给计划、人批准后才动工」比「边做边问」更可预期，尤其契合手机小屏幕「实时纠偏难」的形态。

5. **权限 / 审批普遍按风险分层，但「两极分化」是通病。** Claude Code 六档、Codex「沙箱 × 审批策略」双轴、Gemini Policy Engine + 四档审批模式——风险分层是成熟做法；但社区已点名 Codex「要么太严寸步难行、要么直接 full access」的两极陷阱，默认值取「安全但不挡路」的中位是共同教训。

6. **定价锚点下移 + 积分/用量/并发分层。** Devin $500→$20、Manus $39/$199→$20/$40、TRAE $3 起、WorkBuddy/Manus 积分制、TRAE/Manus 按并发分层——「免费额度钩子 + 按用量/并发分层」成为 Agent 产品通用商业化范式，高门槛定价被市场用脚投票否定。

---

## 四、差异化机会（本项目定位论证）

本项目定位：**Android 优先、中文、手机遥控桌面 Agent、深度遥控单任务**。对照矩阵，定位论证与空白点如下：

1. **「手机遥控桌面 Agent」赛道已被巨头背书，但「深度遥控单任务」是空白。** Codex 移动端是「多线程/多工作流的轻量指挥中心」，无实时屏幕/终端镜像、无逐条消息级协同；Copilot/Cursor/TRAE 做了远程接续与审阅，但都以「巡场」粒度为主。本项目把「实时投屏/终端流 + 逐条消息 + 逐条审批」做到位，是把「遥控」做到比「巡场」更深一层，避开与巨头正面拼通用性。

2. **Android 优先直接占位。** Cursor 无 Android、国内主流（通义灵码/文心快码）无移动遥控，本项目是「国产 + Android + 手机遥控」的真空地带着陆点，可作为主打叙事。

3. **审批推送是「咽喉」，是各家想做而没做深的一等能力。** Claude Code 有公开 issue、Gemini 只能终端通知、Codex 无系统级推送；本项目铁律已定「消息 100~300ms 送达」，把「Agent 需要审批 → 手机推送 + 横幅 + 直达审批页」做成 P0，是直接赢过三家的差异点。

4. **手机做「遥控 + 审批 + 状态」，不做完整 IDE。** Cursor「指挥 App 与编辑 IDE 分离」、WorkBuddy「授权/停止两动作」、TRAE「轻量调度控制台」都印证：移动端价值在「看得到 + 审得了 + 控得住」，而非把编辑器/终端搬上手机。本项目聚焦遥控与审批是正确的产品边界。

5. **「复用你本机环境」是相对 Replit 云工作区的差异化。** Replit 把「桌面」变成云端 VM（用户需迁移环境），本项目强调「在你的真实机器/仓库上干活」，复用既有环境、不迁移、零学习成本。

---

## 五、产品迭代方向建议（重点，功能级）

### 5.1 可借鉴功能清单（按优先级）

> P0 = 咽喉能力，先做；P1 = 差异化增强；P2 = 加分项/远期。「落地要点」已尽量对齐本仓库既有机制（服务端投影、消息队列、中断语义二选一）。

| 优先级 | 功能 | 借鉴来源 | 理由与落地要点 |
|---|---|---|---|
| **P0** | **绿灯 + Agent 动作可见性**（正在读哪个文件、下一步准备做什么） | WorkBuddy 绿灯语义、Manus 执行透明化、Replit changelog | 手机遥控的信任底座；用服务端投影信号把「当前动作」实时推到手机，一眼看懂 Agent 进行到哪一步（对齐铁律 6「以服务端投影为准」） |
| **P0** | **授权 / 停止高频动作**（放行 / 暂停 / 终止） | WorkBuddy「授权+停止」、Copilot 单步批准/拒绝 | 移动端最刚需的两个低频高价值动作，不做完整编辑；先做「放行/暂停/终止」而非追求手机上完整 IDE |
| **P0** | **审批推送 + 手机一键批/拒 + 让 Agent 继续** | Claude Code issue #29438、Gemini 终端通知痛点、TRAE push alerts | 直接打各家未补齐的咽喉缺口；「Agent 需审批 → 推送 + 横幅 + 直达审批页」走既有 100~300ms 推送通道，把 Agent 生命周期事件纳入推送而非只推消息 |
| **P0** | **断线消息队列化 / 补投** | Claude Code Remote Control「队列化消息/权限提示/状态更新，恢复后补投」 | 补足「断线不丢消息、恢复后补投」；与消息队列机制合并，避免断线期审批/状态丢失 |
| **P0** | **单步/逐条审批可批准/拒绝（移动端可达）** | Copilot 敏感操作逐条审批、移动端/网页均可批 | 敏感操作（git 提交/终端命令/写文件）逐条审批而非整段一刀切；审批入口移动端原生，不依赖桌面弹窗 |
| **P0** | **审批按风险分层 + 快捷裁决** | Claude Code 六档、Codex 沙箱×策略、Gemini 分层 | 快捷裁决「允许本次 / 本次会话允许 / 始终允许（写策略）/ 拒绝」；默认取「安全但不挡路」中位（如 workspace-write + on-request），首次审批用引导讲清三档含义，规避 Codex 两极陷阱 |
| **P1** | **文件夹级授权**（远程可控边界 + 产出回写原目录） | TRAE 文件夹级授权 + 产出回写 | 远程控制安全模型核心——只允许 Agent 访问用户明确批准的文件夹、产出写回原目录，而非全盘放权 |
| **P1** | **审批规则配置页**（借鉴 Policy Engine） | Gemini Policy Engine（TOML 规则 allow/deny/ask_user + 分层） | 手机 App 提供「审批规则」配置：只读命令（git status/diff/ls）免审批、危险命令（rm -rf/git push）必审批；规则存服务端投影、单向下发，实现「一次配置长期省心」的护城河 |
| **P1** | **Plan Mode 计划确认并入排队消息状态机** | Gemini Plan Mode、Devin Plan→审批→Execute、Manus Plan Mode | 桌面 Agent 对大任务先产出执行计划（Markdown），手机可审阅/批注/改/批准后才进入执行循环；与既有「中断语义=有排队消息时弹框二选一」合并成统一「计划-执行-审批」状态机（注意与铁律 14 的排队语义冲突需二次确认） |
| **P1** | **后台执行 + push 验收闭环** | TRAE「后台执行 + push alerts」、Manus/WorkBuddy 推送 | 任务在桌面持续跑、切出 App 不中断，完成/需授权时 push 回来验收；「成果预览 + 一键通过/打回」而非仅放行/终止 |
| **P1** | **多设备连接 + 设备切换**（一机连多电脑、任务列表并列隔离） | WorkBuddy 一机连多电脑、TRAE 并行分发多任务 | 支持多桌面 Agent 实例时，移动端提供「设备切换 + 各设备任务列表并列隔离」结构，互不干扰 |
| **P1** | **锁屏远程 / 电脑不自动休眠** | WorkBuddy「允许锁屏远程」 | 「人离开工位」场景硬需求；服务端处理桌面休眠策略与连接保活 |
| **P2** | **Design Mode 画图 + 语音口述** | Cursor Design Mode（图上点/画圈 + Apple Pencil）、TRAE 语音/Brainstorm | 给 Agent 视觉/语音指令，移动端遥控加分项 |
| **P2** | **手机采集 → 桌面处理**（拍发票/传文档） | WorkBuddy | 差异化高频场景，MVP 之外的次优先能力 |
| **P2** | **常驻状态组件（Live Activities/灵动岛/通知栏/小组件）** | Cursor Live Activities（锁屏+灵动岛跟踪最多 8 个 Agent） | 桌面 Agent 运行状态手机常驻可见 |
| **P2** | **审阅合并闭环**（看 diff/加 reviewer/让 Agent 修评论/合并） | Cursor、TRAE review/approvals | 审批 → 让 Agent 继续修 → 再合并的完整闭环 |
| **P2** | **三端共享上下文 / 文件系统** | TRAE 三端同源、Copilot 会话漫游 | 同一会话桌面↔手机无缝接续（会话漫游），保证服务端投影状态任意端一致 |
| **P2** | **headless / 结构化事件流投影** | Codex exec、Gemini `-p --output-format json` | 桌面服务端把 Agent 状态/事件/审批请求以结构化事件流推手机（对齐既有 /remote/connected、/remote/phone-logs、/remote/health），手机永不本地推算 |
| **P2** | **分享链接即演示 / 手机预览验收** | Lovable 分享链接、Replit 一键预览 | Agent 产出在手机上可预览/可分享，缩短反馈链 |
| **P2** | **MCP 集成** | 通义灵码 2.5、Claude Code/Cursor 均已接 MCP | 「Agent 连接外部工具」已成标配，规划 MCP 兼容以免被生态排除 |

### 5.2 需规避项

| 规避项 | 反面教材 | 说明 |
|---|---|---|
| **路线摇摆 / 承诺不兑现** | Gemini 开源 CLI 一年即停服转闭源 Antigravity；Manus 切割国内生态错失本土市场 | 审批/遥控这类咽喉能力不可反复；主体与数据边界从一开始清晰 |
| **权限两极分化** | Codex「太严 vs 全开」被社区点名 | 默认值取「安全但不挡路」中位，首次审批引导讲清档位 |
| **定价反复 / 限额收紧的口碑反噬** | Claude Code 从 Pro 移除、限额反复；Manus 高价被批后大幅降价 | 审批/用量设计给用户稳定预期 |
| **「只能看不能控」的伪远程** | Gemini headless 只有文本、无审批 UI | 价值在「看得到 + 审得了 + 控得住」闭环 |
| **电脑必须常亮联网的脆弱性** | Cursor Remote Control 要求电脑常亮联网、beta、付费 | 设计更稳的断线/唤醒/队列机制 |
| **无自研 / 强依赖单一第三方** | Manus 依赖 Claude 家族、中断连带可用性；跨境并购监管红线 | 技术栈与数据边界自主可控 |

---

## 六、结论与待拍板事项

**结论（一段话）**：四份档案 12 个产品共同印证——「执行在本地/主机、手机是窗口/指挥台」是经过巨头验证的架构共识，「手机遥控桌面 Agent」是真实且仍未被做深的赛道，但**审批推送、Android、中文、深度遥控单任务**四处空白同时存在且无人占据；本项目「Android 优先 + 中文 + 手机遥控桌面 Agent + 深度遥控单任务」恰落在巨头（iOS 专属/英文/轻量巡场）与国产主力（桌面 IDE、无移动）共同留下的复合空位上，应把「绿灯动作可见性、授权/停止、审批推送 + 一键批拒、断线补投、逐条审批、风险分层裁决」六项列为 P0 咽喉能力抢先做实，再以「文件夹级授权、审批规则配置、Plan Mode 计划确认」构建护城河，同时严格规避路线摇摆、权限两极与定价反复三类反面教训。

**待用户拍板的方向性选择**：

1. **是否把「Android 市场空缺」写入对外宣传口径**——（证据：Cursor 无 Android、通义灵码/文心快码无移动遥控），作为主打叙事？
2. **是否立项「审批规则配置页」**（借鉴 Gemini Policy Engine，P1）——涉及手机端新增配置界面与规则存储/下发，工作量较大，需确认优先级。
3. **是否立项「Plan Mode 计划确认并入排队消息状态机」**（P1）——与既有「中断语义二选一」及排队队列语义存在交互（见铁律 14），需二次确认「计划确认」与「消息排队/立即上屏」的状态机合并方式后再动手。
4. **「多设备连接（一机连多电脑）」是否进入近期路线**——决定移动端会话/设备切换的信息架构设计时点。
5. **商业化口径**——是否参考「免费额度 + 积分/并发分层」范式（Devin/Manus/TRAE/WorkBuddy 已验证），还是维持当前非商业化路线，先不进入视野。

---

## 七、参考来源索引

> 完整来源链接已随四份档案逐条标注，以下为按产品的主干索引；详见各档案正文与文末「一手来源清单/数据来源索引」。

**档案 1（Claude Code / Cursor）**
- Claude Code Remote Control：[官方文档](https://code.claude.com/docs/en/remote-control)
- Claude Code 权限模式：[官方文档](https://code.claude.com/docs/en/permission-modes)
- Claude Code 移动端审批推送缺失：[issue #29438](https://github.com/anthropics/claude-code/issues/29438)
- Claude Code 定价变动：[Cocoloop](https://news.cocoloop.cn/en/2026/04/claude-code-pro-plan-removed/)、[网易](https://www.163.com/dy/article/KR4MLSG30511AQHO.html)
- Claude Code 市占：[Claudify《The State of Claude Code in 2026》](https://claudify.tech/blog/state-of-claude-code-2026)
- Cursor for iOS：[官方文档](https://cursor.com/help/ai-features/mobile-app.md)、[iOS 参考](https://cursor.com/docs/cloud-agent/mobile.md)
- Cursor 定价：[官方定价页](https://cursor.com/help/account-and-billing/pricing.md)
- Cursor 收购：[CNBC](https://www.cnbc.com/2026/06/16/spacex-spcx-cursor-acquisition-ipo.html)

**档案 2（Codex / Gemini）**
- Codex 移动端：[Work with Codex from anywhere（OpenAI 官方）](https://openai.com/zh-Hans-CN/index/work-with-codex-from-anywhere/)
- Codex 沙箱/审批：[社区镜像官方文档](https://github.com/etheaven/codex-mcp-server/blob/main/docs/concepts/sandbox.md)
- Gemini Policy Engine：[官方文档](https://geminicli.com/docs/reference/policy-engine/)
- Gemini Plan Mode：[官方文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/plan-mode.md)
- Gemini Notifications：[官方文档](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/notifications.md)
- Gemini CLI 停服：[9to5Google](https://9to5google.com/2026/06/17/gemini-cli-code-assist-shutting-down/)、[The Register](https://www.theregister.com/ai-ml/2026/05/20/bye-bye-gemini-cli-google-nudges-devs-toward-antigravity/)
- 市占：[OSSInsight Coding Agent Wars 2026](https://ossinsight.io/blog/coding-agent-wars-2026)

**档案 3（WorkBuddy / TRAE / Manus）**
- WorkBuddy 多端同步：[凤凰网科技](https://tech.ifeng.com/c/8vVCwMscNlf)、[站长之家](https://m.chinaz.com/2026/0811/1770341.shtml)
- WorkBuddy 定价：[官方定价](https://www.codebuddy.ai/docs/zh/workbuddy/pricing)
- TRAE SOLO Mobile：[官方博客](https://www.trae.ai/blog/trae_solo_mobile_0506)
- TRAE 评审：[theaiagentindex](https://theaiagentindex.com/agents/trae)
- Manus 定价：[官方定价文档](https://manus.im/docs/zh-cn/introduction/plans)、[帮助中心](https://help.manus.im/zh-CN/articles/11711111-manus-%E7%9B%AE%E5%89%8D%E7%9A%84%E4%BC%9A%E5%91%98%E5%AE%9A%E4%BB%B7%E6%98%AF%E5%A4%9A%E5%B0%91)
- Manus 收购被叫停：[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm)

**档案 4（Copilot / Devin / Replit / 通义灵码 / Lovable）**
- Copilot 远程会话：[GitHub Blog](https://github.blog/news-insights/product-news/take-your-local-github-sessions-anywhere/)、[中文解读](https://skillnav.dev/articles/take-your-local-github-sessions-anywhere)
- Copilot 移动 Coding Agent：[Changelog 2025-06](https://github.blog/changelog/2025-06-04-github-copilot-coding-agent-now-available-on-github-mobile/)
- Devin 审批/自动化：[官方文档](https://docs.devin.ai/zh/product-guides/automations)
- Devin 定价：[InfoQ](https://www.infoq.cn/article/jaoqgyvvxbl31degidfz)、[至顶网](https://m.zhiding.cn/article/3165033.htm)
- Replit 移动 App：[DeepLearning.AI](https://www.deeplearning.ai/the-batch/replits-agent-powered-mobile-app-expands-to-full-app-development)、[Replit Docs](https://docs.replit.com/features/platforms/mobile-app)
- 通义灵码破千万：[阿里云开发者社区](https://developer.aliyun.com/article/1652207)
- Lovable 融资/估值：[Finsmes](https://www.finsmes.com/2025/12/lovable-raises-330m-in-series-b-funding-at-6-6-billion-valuation.html)、[Business Standard](https://www.business-standard.com/technology/tech-news/lovable-s-13-3-billion-valuation-puts-ai-coding-s-saas-challenge-in-focus-126081300705_1.html)

---

*（本报告仅创建该文件、未 commit；事实均来自四份档案已附来源链接，未臆造新事实；「待核实」处与 11/12 产品计数差异已显式标注。）*
