# WorkBuddy 与 Trae 与 Manus 竞品档案

> 调研时间：2026-09（以所引来源发布时间为口径）
> 调研对象：腾讯 WorkBuddy、字节 TRAE（IDE + SOLO）、Manus（Butterfly Effect）
> 本文背景：本项目为**手机遥控桌面 Agent**（dsh-remote-control，手机 App 遥控桌面 DSH Agent），故「移动端 App / 远程控制 / 审批体验」为最高权重维度。
> 说明：文中数据均注明来源与时间；无法核实或存在矛盾的标注「待核实」；本文仅作产品迭代参考，不作对外发布用途。

---

## 一、腾讯 WorkBuddy

### 1. 定位与形态
- **定位**：腾讯出品的「全场景 AI 办公工作台」，覆盖日常办公、代码开发与设计创意；用户用自然语言下达任务，Agent 自主思考、拆解、规划执行并交付「可直接验收」的结果。[腾讯云开发者社区](https://cloud.tencent.com.cn/developer/article/2703043)
- **出身**：腾讯云代码助手 **CodeBuddy** 的「支线产品」，2026 年 3 月还是十几人团队，数月后扩至百人规模、马化腾亲自过问、腾讯六大事业群开绿灯，内部被称为「新太子」。[界面新闻](https://www.jiemian.com/article/14909158.html)
- **产品形态**：**独立桌面客户端（PC）+ 移动 App + 微信小程序**三端；无需部署、下载即用。官网/文档见 [WorkBuddy 文档](https://www.codebuddy.ai/docs/zh/workbuddy/pricing)（现挂在 CodeBuddy 文档站下，品牌域名 workbuddy.cn）。

### 2. 目标用户与核心场景
- **目标用户**：普通白领与知识工作者（**非开发者为主**），与面向程序员的 CodeBuddy/Coding Agent 错位竞争。
- **核心场景**：文件内容识别与处理、文档生成与编辑、数据分析可视化、自媒体运营、每日自动推送资讯简报、**远程遥控助理**、连接 Google Calendar/Drive、零代码做本地应用、创建自定义 Skills。[WorkBuddy 文档导航](https://www.codebuddy.ai/docs/zh/workbuddy/pricing)
- 行业判断：它刻意填补「OpenClaw 需部署、Coding Agent 离不开命令行、海外产品要账号/支付/环境」留下的空白，主打**低门槛**。[DoNews 实测](https://www.donews.com/article/detail/6956/105376.html)

### 3. 核心功能
- **多专家 AI Agent**：全行业专家执行，从策略到交付；自然语言任务 → 自主拆解规划执行 → 交付结果。[腾讯云开发者社区](https://cloud.tencent.com.cn/developer/article/2703043)
- **人机双写 / AI 编辑**：把腾讯文档「装」进 WorkBuddy，可在对话界面直接改文件；「AI 编辑」支持 **Word/Excel/PPT/MD 四类**（PDF 暂不支持），例如在 Excel 选中表格让 AI 生成柱状图插入原位。[DoNews 实测](https://www.donews.com/article/detail/6956/105376.html)
- **连接器 / 生态**：接入主流 IM 与协作平台——企微、飞书、钉钉、微信、QQ、Slack、Telegram、Discord 机器人/助理；整合腾讯文档能力。[WorkBuddy 文档导航](https://www.codebuddy.ai/docs/zh/workbuddy/pricing) / [腾讯云开发者社区](https://cloud.tencent.com.cn/developer/article/2703043)
- **技能 / 自动化 / 记忆**：可自定义 Skills、自动化任务、系统记忆、模型配置、数据管理。[WorkBuddy 文档导航](https://www.codebuddy.ai/docs/zh/workbuddy/pricing)
- 迭代速度：上线 4 个多月迭代 **52 个版本**，平均两三天一版，甚至同日双版本。[界面新闻](https://www.jiemian.com/article/14909158.html)

### 4. 移动端与远程能力（重点 ★ 直接对标本项目）
2026-08-11 上线「**多端同步**」，是目前同类中**手机遥控桌面 Agent** 最完整落地的样本之一。[凤凰网科技](https://tech.ifeng.com/c/8vVCwMscNlf) / [站长之家](https://m.chinaz.com/2026/0811/1770341.shtml)

- **三端实时同步**：PC 客户端、移动 App、微信小程序三端的任务、对话记录、产物实时同步。
- **手机远程控制桌面任务**：手机可对电脑端任务执行**授权、停止**等操作；同账号登录即默认开启「桌面端允许移动端连接」，**无需扫码/配对/额外授权**。
- **状态可见性（绿灯语义）**：连接成功后设备区显示**绿色圆点**，表示 Agent 正在工作；离开工位后可实时查看 Agent **当前正在读哪些文件、下一步准备执行什么**。
- **一机连多电脑**：一台手机可连接多台电脑，切换设备分别查看不同电脑上的任务；任务列表与工作空间并列展示、互不干扰。
- **手机采集 → 桌面处理**：手机拍发票/传文档，电脑端 Agent 完成识别、分类归档、表格录入。
- **锁屏远程**：桌面端开启「允许锁屏远程」后电脑不自动休眠，手机锁屏状态仍保持远程连接，无需保持屏幕常亮。
- 版本门槛：移动 App ≥ 1.2.0，PC ≥ 5.3.8。

### 5. 技术栈与模型
- 底座复用 CodeBuddy 技术栈；支持**多模型协同**、「Auto 调度 / 全模型可选」（限免期），并已接入腾讯自研**混元 Hy4 preview**。[腾讯云开发者社区](https://cloud.tencent.com.cn/developer/article/2703043) / [站长之家](https://m.chinaz.com/2026/0811/1770341.shtml)

### 6. 商业模式与定价
来源：[WorkBuddy 官方定价](https://www.codebuddy.ai/docs/zh/workbuddy/pricing)（美元标价；积分制）
- **个人版**
  - Free：100 基础积分/月；补全 5000 次/月（限免期间无限）；自动任务 3 个（限免 99 个）。
  - Pro：$10/月（连续包月）或 $96/年（≈$8/月）；1000 基础 + 1000 加赠 = 2000 积分/月；无限补全；自动任务 15 个（限免 99）；全模型可选。
- **团队版 Team**：$40/坐席/月 或 $480/坐席/年；每席 1000 积分/月、团队公共池共享、管理员控制台、统一账单、IDE/CLI 多形态。
- **积分加量包**：Pro 加量包 500 积分 $15；Team 加量包 2000 积分 $40 … 200000 积分 $4000。
- 备注：限免期结束后恢复档位额度；基础积分当月有效不结转。

### 7. 市占 / 热度 / 口碑
- 腾讯 2026 Q1 财报称：**以日活跃账户数计算，WorkBuddy 已成为中国使用最广的效率 AI 智能体服务**。[界面新闻](https://www.jiemian.com/article/14909158.html)
- 营销投入大：仅北上广深线下投放测算近亿元；地铁、电梯、社交媒体全覆盖。[界面新闻](https://www.jiemian.com/article/14909158.html)
- 口碑：「**能力及格，但生态真香**」——执行力处于及格线，核心优势是降低使用门槛 + 腾讯文档/IM 生态。[DoNews 实测](https://www.donews.com/article/detail/6956/105376.html)
- 早期毛坯期问题：语言切换出错、工作目录须在 C 盘、Windows 适配与运行稳定性问题。[界面新闻](https://www.jiemian.com/article/14909158.html)

### 8. 亮点与短板
- **亮点**：三端同步 + 手机遥控桌面 Agent 落地最早、最完整；零门槛下载即用；腾讯文档/IM 生态整合；迭代极快；免费额度大（限免期）。
- **短板**：Agent 单点执行力「及格」非顶尖；依赖腾讯生态；PDF 等格式 AI 编辑受限；工作目录/跨盘等早期工程问题。

### 9. 对我们的启示（WorkBuddy）
1. **「绿灯 + 正在读什么文件 / 下一步做什么」的实时状态可见性，是手机遥控 Agent 的信任底座**。本项目已有多设备/连接状态投影，应把「Agent 当前动作的可读性」做到位——用户离开电脑后，手机必须一眼看懂 Agent 进行到哪一步、下一步要动什么（对齐本仓库「服务端投影为准、单向数据流」铁律）。
2. **「授权 / 停止」是手机端最刚需的两个动作**。WorkBuddy 把远程操作收敛为「授权、终止」两个低频高价值动作，而非把完整 IDE 搬上手机——本项目也应优先做「放行/暂停/终止」这类控制，而非追求手机上完整编辑。
3. **免配对、同账号即连**大幅降低上手成本。WorkBuddy「登录同账号默认开启」vs 我们可能需要的显式配对，可在隐私与便利间权衡，考虑「默认信任同账号 + 可选二次确认」。
4. **锁屏远程 / 电脑不自动休眠**是「人离开工位」场景的硬需求，对应我们服务端需处理桌面休眠策略与连接保活。
5. **一机连多电脑 + 切换设备**：本项目若支持多桌面 Agent 实例，需设计「设备切换 + 任务列表并列隔离」的移动端结构。
6. **手机采集 → 桌面处理**（拍发票传文档）是差异化高频场景，可作为 MVP 之外的次优先能力。
7. 定价可参考**积分制 + 免费额度大 + 并发/自动任务分层**的漏斗设计。

---

## 二、字节 TRAE（IDE + SOLO）

### 1. 定位与形态
- **定位**：字节跳动的 **AI 开发平台**，原名含义「The Real AI Engineer」，从 AI 编程工具扩展为覆盖「产品开发全生命周期」的 Agent。[TRAE 官方博客：New SOLO](https://www.trae.ai/blog/new_solo_beta_0331)
- **形态**（三个层次）：
  1. **TRAE IDE**：VS Code 的 fork，AI 优先的集成开发环境。
  2. **TRAE SOLO**：独立 Agent 产品，含 **Desktop / Web / Mobile 三端**，2026-03-31 起 beta（原需邀请码），2026-05 起**全量免费开放、无需邀请码**。[TRAE 官方博客：SOLO Mobile](https://www.trae.ai/blog/trae_solo_mobile_0506)
  3. 移动 App（应用商店包名 `com.bytedance.trae.cn`）。[应用宝](https://sj.qq.com/appdetail/com.bytedance.trae.cn)

### 2. 目标用户与核心场景
- **TRAE IDE**：开发者（全栈/软件工程师）。
- **SOLO MTC 模式（More Than Coding）**：产品经理、数据分析师、UI/UX 设计师、财务、法务、顾问——覆盖**创作、运营、审查（review）、审批（approvals）、决策**等非编码场景。[TRAE 官方博客：SOLO Mobile](https://www.trae.ai/blog/trae_solo_mobile_0506)
- 从「只服务开发者」扩展到「整个产品开发流程中的每个角色」。

### 3. 核心功能
- **双模式**：Code Mode（对话式自主写码/运行/迭代）+ MTC Mode（文档、数据分析、报告、演示、竞品调研等非编码交付）。[TRAE 官方博客：New SOLO](https://www.trae.ai/blog/new_solo_beta_0331)
- **多 Agent 并行架构**：一个开发者可同时启动 10–20 个 agentic 任务并行，逐个验收，效率显著高于单任务串行工具。[theaiagentindex](https://theaiagentindex.com/agents/trae)
- **统一 Workspace**：文件、上下文、任务历史集中一处；理解/处理 JSON、Python、PPTX、CSV 等格式，产出在工具面板直接展示供评论与修改。[DoNews](https://www.donews.com/news/detail/4/6491623.html)
- **MCP 支持**：stdio、SSE、Streamable HTTP 三种传输。[theaiagentindex](https://theaiagentindex.com/agents/trae)
- Q3 2026 新增：**Design Mode**（设计稿生成 + 导出为代码）、**Voice Chat**（语音 + 联网搜索）、**Global Memory**（跨会话记忆）。[theaiagentindex](https://theaiagentindex.com/agents/trae)

### 4. 移动端与远程能力（重点 ★ 直接对标本项目）
2026-05-06 上线 **TRAE SOLO Mobile**，官方博客把手机定义为「**轻量调度控制台（lightweight dispatch console）**」——正是本项目「手机遥控桌面 Agent」的同类定位。[TRAE 官方博客：SOLO Mobile](https://www.trae.ai/blog/trae_solo_mobile_0506)

- **三端同源**：手机、桌面、网页共享**同一个 Agent、同一套文件系统、同一段对话上下文**，切换设备心流不中断；移动端 ≠ 缩水版（完整 MTC + Code）。
- **一次配对、多端同步**：手机可连接多个 desktop/web 端点；配对后所有设备 context、任务状态、更新保持一致；手机创建的任务**即时出现在 Web（云）与 Desktop（本地）**。
- **远程文件夹访问 + 权限审批（关键）**：SOLO Mobile 可安全连接桌面与网页，**在用户已授权的文件夹内**浏览文件、引用项目上下文、按内容执行任务；**桌面端文件夹权限确保访问严格限制在用户批准的文件夹内**——这是「远程可控边界」的范式，值得直接借鉴。
- **产出落回原目录**：生成的草稿/摘要/报告/代码文件保存回原工作目录。
- **并行分发任务**：跨已配对端点同时 dispatch 多个任务，实时追踪进度。
- **后台执行 + 推送通知**：切出 App 或离开后任务继续在后台跑，完成后 **push alerts** 通知用户回来验收。[TRAE 官方博客：SOLO Mobile](https://www.trae.ai/blog/trae_solo_mobile_0506) / [徽声在线](https://m.ahradio.com.cn/sports/101124.html)
- **Brainstorm Mode + 语音输入**：随时语音输入想法 → 转结构化执行计划；复杂意图拆解为待办并顺序执行。
- **Privacy Mode**：开启后聊天交互（含代码片段与 AI 输出）不用于分析/改进/模型训练。[TRAE 官方博客：SOLO Mobile](https://www.trae.ai/blog/trae_solo_mobile_0506)
- 远程任务不因电脑休眠停止：桌面端新建 Remote 任务，网页端同步开启实时共享，可在其他设备查看进度。[DoNews](https://www.donews.com/news/detail/4/6491623.html)
- 中国版额外能力：语音讨论、飞书 CLI 接入、定时任务。[TRAE 官方中文社区](https://forum.trae.cn/t/topic/15182)

> ⚠️ 口径提醒：第三方 CSDN 文章称 SOLO 移动端为「边缘 Runtime（内置 MicroPython、不依赖后端）」[CSDN](https://blog.csdn.net/weixin_28717807/article/details/162680828)，与官方/正规媒体「依托云端算力」的表述 [DoNews](https://www.donews.com/news/detail/4/6491623.html) 相矛盾。**以官方「云端算力 + 移动端为接入端」为准**，CSDN 说法标注「待核实」。

### 5. 技术栈与模型
- 支持 **Claude、GPT、Gemini** 家族模型；MCP 客户端；SOLO 多 Agent 架构。[theaiagentindex](https://theaiagentindex.com/agents/trae)
- 数据实践：聊天数据（含代码片段）可能用于分析/改进/训练，除非开启 Privacy Mode；代码库文件临时上传计算 embedding 后永久删除。[theaiagentindex](https://theaiagentindex.com/agents/trae)

### 6. 商业模式与定价
来源：[theaiagentindex TRAE Review](https://theaiagentindex.com/agents/trae)（2026-07-23 核验）
- **五档 freemium**：
  - Free：永久免费，TRAE IDE 有限用量、标准队列、5000 次/月补全。
  - Lite：$3/月（年付 $2.25/月），$5 用量额度、无限补全、SOLO 最多 **2 个**并发云任务。
  - Pro：$10/月，$20 用量、完整 IDE + SOLO、最多 **10 个**并发云任务。
  - Pro+：$30/月，3.5× 用量、最多 **15 个**并发。
  - Ultra：$100/月，20× 用量、模型抢先体验、最多 **20 个**并发。
- 年付全档 75 折（25% off）。

### 7. 市占 / 热度 / 口碑
- 评分：theaiagentindex 综合 **4.1/5**；GitHub 官方仓库 **901 stars**；G2 **3.5/5（仅 5 条评价）**。[theaiagentindex](https://theaiagentindex.com/agents/trae)
- 定位为 Cursor、Windsurf、Claude Code 的**高性价比挑战者**：Lite $3 是主流竞品中最低门槛。
- 口碑争议：① 字节背景带来的**地缘政治/数据治理顾虑**，部分美国企业采购受限；② 数据训练为 **opt-out**（默认参与）；③ **国内版被吐槽迭代慢、不够好用**。[theaiagentindex](https://theaiagentindex.com/agents/trae) / [TRAE 官方中文社区](https://forum.trae.cn/t/topic/18828)
- 中国版（trae.cn）与国际版并行，国内版收费方案仍在社区讨论中。[TRAE 官方中文社区](https://forum.trae.cn/t/topic/3276)；2025-03 阮一峰曾评测国内版。[阮一峰博客](https://www.ruanyifeng.com/blog/2025/03/trae.html)

### 8. 亮点与短板
- **亮点**：三端（含移动端）Agent 体验最完整、官方背书；并行多任务架构；定价激进（$3 起）；远程文件夹授权 + 产出回写；隐私模式。
- **短板**：G2 评价样本极少、第三方口碑证据弱；字节背景的地缘/数据顾虑；国内版与国际版体验割裂；SOLO 定位从「编程」向「办公」扩张后与通用 Agent 正面竞争。

### 9. 对我们的启示（TRAE）
1. **「手机 = 轻量调度控制台，重活交给桌面/云」**的定位，与本项目「手机遥控桌面 Agent」完全同构，是最值得逐条对表的竞品。它的核心交互是：**触发任务 → 实时追踪 → 后台执行 → push 通知验收**，而非在手机上重做执行。
2. **「文件夹级授权」是远程可控边界的最佳实践**：桌面端只允许 Agent 访问用户明确批准的文件夹，产出也只能写回原目录。本项目应把「可访问范围授权」作为远程控制的安全模型核心（对应「审批体验」维度），而非全盘放权。
3. **后台执行 + push 通知**是「人机分离」体验的关键闭环：任务在桌面/云持续跑，手机只需在完成时收到推送回来验收。对照本项目「消息推送 100~300ms 送达」目标，任务完成/需要授权的事件应走同一推送通道。
4. **并行 dispatch 多任务 + 实时进度追踪**：若支持多桌面 Agent 或任务队列，移动端应提供「并发任务列表 + 各自进度」视图，而非单一任务视图。
5. **三端共享同一对话上下文/文件系统**是跨设备心流的根基；本项目应保证「服务端投影为准」的会话状态在任何端都一致（已对齐本仓库铁律 6）。
6. **MTC 模式 + 语音输入 + Brainstorm**：手机端语音记录想法 → 转结构化任务 → 桌面执行，是移动端差异化卖点，可作为后续能力。
7. **审批体验**：TRAE 把「review/approvals」写进 MTC 场景描述，说明 Agent 交付物的「人在环验收」是移动端高频动作——本项目应做「成果预览 + 一键通过/打回」而非仅「放行/终止」。

---

## 三、Manus（Butterfly Effect）

### 1. 定位与形态
- **定位**：「全球首个通用 AI Agent」，一句话即可自主规划、操作浏览器/电脑执行复杂任务并直接交付成果。[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm)
- **形态**：**云端工作区（manus.im）+ 移动 App（iOS/Android）**；无本地客户端，任务在云端浏览器沙箱中执行。[Penchan 中文教程](https://penchan.co/zh-cn/ai/agent/manus/)
- **公司背景**：2022 年成立于中国（北京/武汉），法律主体迁至新加坡 Butterfly Effect Pte. Ltd.；与国内主体北京蝴蝶效应科技有限公司同源。[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm) / [Penchan](https://penchan.co/zh-cn/ai/agent/manus/)

### 2. 目标用户与核心场景
- **目标用户**：泛通用用户——研究者、分析师、运营、知识工作者；主打「开放式长时研究」「多步骤任务」「行程规划」「网站/幻灯片交付」。[Penchan](https://penchan.co/zh-cn/ai/agent/manus/)
- **核心场景**：深入研究与报告、数据整理、网站原型、演示文稿、行程规划；非「重 coding」场景（coding 并非其强项）。[Manus 定价文档](https://manus.im/docs/zh-cn/introduction/plans) / [Penchan](https://penchan.co/zh-cn/ai/agent/manus/)

### 3. 核心功能
- **自主 Agent**：任务自动拆解为多步骤 → 浏览网页、执行代码、管理文件 → 交付成品；右侧面板**实时观看**它在查什么页面、终端跑了什么指令、当前在哪一步（执行透明性是其设计特点）。[Penchan](https://penchan.co/zh-cn/ai/agent/manus/)
- **多 Agent / 子 Agent**：主 Agent 调度子 Agent 或工具分工完成子任务。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118)
- **双模式**：Agent 模式（Manus 1.6 Lite / 1.6 / 1.6 Max）+ 聊天模式（快速问答省积分）；**Plan Mode**（2025-07-22）——先出计划供人确认再执行。[Manus 会员定价](https://help.manus.im/zh-CN/articles/11711111-manus-%E7%9B%AE%E5%89%8D%E7%9A%84%E4%BC%9A%E5%91%98%E5%AE%9A%E4%BB%B7%E6%98%AF%E5%A4%9A%E5%B0%91) / [Manus 博客](https://manus.im/zh-cn/blog/manus-plan-mode)
- **定时任务 / 并发任务**：Free 支持 2 定时/1 并发；Pro 支持 20 定时/20 并发。[Manus 会员定价](https://help.manus.im/zh-CN/articles/11711111-manus-%E7%9B%AE%E5%89%8D%E7%9A%84%E4%BC%9A%E5%91%98%E5%AE%9A%E4%BB%B7%E6%98%AF%E5%A4%9A%E5%B0%91)

### 4. 移动端与远程能力（重点）
- **移动 App 已上线**：Google Play（包名 `tech.butterfly.app`，评分 **4.7 星**）[Google Play](https://play.google.com/store/apps/details?id=tech.butterfly.app)；App Store（`id6740909540`）。[App Store](https://apps.apple.com/app/manus-ai-agent-automation/id6740909540)
- **手机控制电脑**：西班牙《La Razón》2026-03-30 报道标题即「Manus 已能从手机控制你的电脑」（"controlar tu ordenador desde el móvil"）。[La Razón](https://www.larazon.es/tecnologia-consumo/tecnologia/manus-ia-ya-puede-controlar-tu-ordenador-movil_2026033069ca8a9783aca52e0e3e92e5.html)（正文未能抓取，能力细节待核实）
- 移动 App 主要承接「查看任务进展 + 任务完成推送」的远程监控体验，而非本地执行（任务仍在云端）。
- 第三方集成亦出现「Manus 任务 + 文件自动化 + 移动端审批（Mobile Approvals）」场景。[Rills 集成页](https://rills.ai/integrations/manus)（第三方，非官方）

### 5. 技术栈与模型
- **无自研基座**，底层依赖第三方模型（主要 **Claude 家族**，亦用过 GPT-4 等）；被业内质疑为「套壳缝合怪」；Claude 服务中断会连带影响 Manus 可用性。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118) / [Penchan](https://penchan.co/zh-cn/ai/agent/manus/)
- 执行环境为云端沙箱（浏览器 + 代码执行 + 文件管理）。

### 6. 商业模式与定价
来源：[Manus 会员定价帮助（2026-03-16）](https://help.manus.im/zh-CN/articles/11711111-manus-%E7%9B%AE%E5%89%8D%E7%9A%84%E4%BC%9A%E5%91%98%E5%AE%9A%E4%BB%B7%E6%98%AF%E5%A4%9A%E5%B0%91) / [Manus 定价文档](https://manus.im/docs/zh-cn/introduction/plans)（积分制）
- **Free（$0）**：每日 300 积分（刷新）；1 并发 / 2 定时；仅 Manus 1.6 Lite + 聊天模式。
- **Pro $20/月**：4000 积分起/月；1.6 Max/1.6/1.6 Lite；20 并发 / 20 定时；高级研究、专业网站部署、幻灯片生成、Beta 抢先。
- **Pro $40/月**：8000 积分起/月，其余同 Pro。
- **Team $20/席/月**：含 Pro 全部 + SSO、退出数据训练、团队分析、访问控制、共享幻灯片模板。
- 年付约 17% off；套餐积分月度重置、附加积分永不过期。
- 历史价格：早期订阅被批偏高（基础版 $39/月、专业版 $199/月），现已大幅下调至 $20/$40。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118)

### 7. 市占 / 热度 / 口碑
- **爆发期**：2025-03-06 发布，上线 4 小时访问量破千万；邀请码炒到 5 万–10 万元；候补名单 2025-03 底突破 260 万。[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm)
- **下行期**：访问量 2025-03 峰值 **2376 万** → 2025-08 回落 **1756 万** → 2026 继续下滑、独立访客/访问时长走低、用户留存长期低位。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118)
- **口碑**：被视为「国产 Agent 继 DeepSeek 后最大惊喜」的开创者，但随后被批「套壳缝合怪」「错失 Agent 浪潮」；OpenClaw（开源「龙虾」）GitHub 破 33 万星后，Manus 更显边缘化。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118)
- **国内口碑**：36 氪标题「Manus 割不动国内用户」——长期不提供中文版、停止中国境内服务，错失国内市场。[36 氪](https://36kr.com/p/3299410123000072)

### 8. 关键事件 / 风险（监管）
- 2025-04 Benchmark 领投 **7500 万美元**；2025-12-29 **Meta 宣布收购**（团队并入 Meta）；2026-04-27 中国发改委外资安全审查办公室**依法禁止该收购、要求撤销恢复原状**；2026-08-11 Manus 宣布恢复独立运营。[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm)
- 收购案被叫停的核心：底层技术/算法/数据/人才源于中国，穿透式审查「实质重于形式」。[中国青年报](https://news.youth.cn/sh/202608/t20260818_16820579.htm)
- 风险：无自研基座依赖第三方 API、单次复杂任务成本 10–50 美元、定价能否覆盖成本存疑。[澎湃](https://m.thepaper.cn/newsDetail_forward_32838118)

### 9. 对我们的启示（Manus）
1. **「执行过程透明化」是通用 Agent 建立信任的第一卖点**——Manus 右侧面板实时展示「查什么页/跑什么命令/在哪一步」，本项目手机遥控桌面 Agent 同样应把桌面 Agent 的**每一步动作投影到手机**（强化服务端投影信号：正在读的文件、执行的命令、当前步骤）。
2. **Plan Mode（先出计划再执行）对手机端尤其必要**：小屏幕上不适合实时纠偏，先让 Agent 给出计划、用户一键确认再放行，能显著降低「误操作 + 焦虑」，是移动端审批体验的关键形态，建议本项目在「授权放行」前增加「计划预览确认」。
3. **推送通知是远程监控的落点**：任务完成/需授权时推送，与 WorkBuddy/TRAE 一致；本项目已有低延迟推送，应把「Agent 生命周期事件」纳入推送，而非只推消息。
4. **积分制 + 免费额度 + 并发分层**三者都在三竞品出现，是 Agent 产品通用商业化范式；本项目若商业化可参考「免费额度钩子 + 按并发/用量分层」。
5. **需规避的教训**：① 无自研模型/强依赖单一第三方 API 的风险；② 主动与国内生态切割导致错失本土市场（Manus 反面教材）；③ 跨境并购的监管红线——本项目主体与数据边界应从一开始就清晰。
6. **差异化机会**：Manus 是「云端通用 Agent」，不占「本地桌面 + 手机遥控」这条更贴近真实工位工作流的赛道；WorkBuddy/TRAE 才是本项目直接竞品，Manus 主要提供「通用执行透明性 + Plan Mode」的产品思想。

---

## 四、三产品横向速览（供总报告汇总使用）

| 维度 | 腾讯 WorkBuddy | 字节 TRAE | Manus |
|---|---|---|---|
| 定位 | 全场景 AI 办公工作台（桌面 Agent） | AI 开发平台（IDE + SOLO 独立 Agent） | 通用云端 AI Agent |
| 形态 | PC 客户端 + App + 小程序 | IDE + SOLO（Desktop/Web/Mobile） | 云端 + 移动 App |
| 目标用户 | 普通白领/知识工作者 | 开发者 + 全流程角色 | 泛通用用户 |
| 手机遥控桌面 | ✅ 最完整（授权/停止/绿灯/锁屏远程/一机多电脑） | ✅ 三端同源（调度控制台/文件夹授权/并行分发/push） | ✅ 有手机控电脑能力（细节待核实） |
| 远程审批体验 | 授权/终止两动作 | 文件夹级授权 + 产出回写 + review/approvals | Plan Mode 计划确认 |
| 定价 | Free / Pro $10 / Team $40/席 | Free / Lite $3 / Pro $10 / Pro+ $30 / Ultra $100 | Free / Pro $20/$40 / Team $20/席 |
| 市占口碑 | 腾讯称日活中国第一效率 AI 智能体 | 4.1/5；901 stars；G2 样本少 | 峰值 2376 万访问后持续下滑 |
| 直接对标本项目 | ★★★ 高 | ★★★ 高 | ★★ 中（产品思想参考） |

---

*注：本档案仅创建该文件、未 commit（按用户指示）。所有事实均附来源链接与时间口径；「待核实」项已显式标注。*
