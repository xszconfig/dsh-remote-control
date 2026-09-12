# AGENTS.md —— 本项目协作规则（跨会话记忆）

## 铁律（用户明确要求，违反即失误）

1. **重大技术决策必须先停下来问用户**：涉及架构、重大技术路线、重大技术选型（例如调试协议 CDP vs DAP、语言服务器选型、持久化方案、协议设计方向）一律不得擅自决定。这些决策影响项目拓展性、可维护性与后续技术规划。**重大选型在请示前必须先完成充分对比论证**：候选清单、各维度优劣（成熟度/发布节奏/生态/性能/接入成本/维护风险/license）、事实来源链接、推荐与理由，落文档 `docs/decisions/<主题>.md` 后再请用户拍板——禁止只凭印象选型（教训：Kotlin LSP 选型只记一行「官方而非 fwcd」，未查明官方独立 LSP 才发布数月、社区版已成熟多年，导致反复）。
2. **只做本地 commit，不推 GitHub**：推送只在用户明确指示"推 GitHub"后执行；用户验收通过前不推送。
3. **不出现用户非预期的页面跳转**：连接状态变化只用横幅/页内元素表达。
4. **重启服务端必须先问用户确认**；重启脚本须保留完整 PATH（Android platform-tools）。
5. **装机与更新路线（2026-09-12 用户拍板）**：装 **release 包**（release-in-house / release-store）用 huawei-adb 系列 skill（鲁棒/快速），不用裸 push+pm install；**开发调试阶段（debug 包）客户端更新一律优先走秒级编译热更新（jugg-apply），不走 ADB 安装**——只有装 release 包才走 ADB 安装。**补充（同日）**：热更新仅适用于**增量小改动**（方法体/文案/小 UI）；大规模结构性改动（新增类/文件拆分）超出增量语义，需重装 debug 包。
6. **所有数据以服务端投影为准**：客户端不做本地推算（如计时），单向数据流。
7. **消息推送目标 100~300ms 内送达**。
8. 真机验收：编译通过 → 装机/热更新 → 自动验收，结果直接汇报。**不攒「装机批次」（2026-09-12 用户指出）**：小改动完成后，若真机（debug 包）在线，**立即 jugg-apply 热更新 + 启动冒烟 + 自动验收**；仅 release 包装机和结构性大改（新增类/文件拆分，热更新不适用）才走 huawei-adb 装机，且装完立即验收，不等下一批。
9. **移动端优先（项目大背景）**：这是一个**手机端 Remote Control App**——所有设计、尤其 UI 与交互（字号/布局/滚动/返回导航/触控）必须以手机屏幕为出发点。桌面端/Web/手机端的设计原则不同，严禁照搬桌面惯例（教训：曾把 Markdown 标题映射到 display 级 57/45/36sp 桌面展示字号）。库/组件默认值必须先核对是桌面还是移动预设。详见 docs/ui-mobile-first.md。
10. **任务并发与子代理消费（任务编排基本原则）**：所有大型任务、耗时任务、重度分析/调研任务一律下沉子代理（后台并发执行），利用其高注意力产出更好结果；主对话绝不长时间卡在单一任务上——与"Android UI 主线程不做 IO/网络等耗时操作，否则卡 UI"同理。主对话只做：信息汇总、任务派发、验收、随时响应用户。**原则上所有任务都由子代理完成**，只有 10 秒内能解决的极简单问题才由主对话直接处理。**功能域路由**：需求与既有子代理高度相关时自动派发给对应子代理（按功能域复用：转盘/发送会话/队列中断/Markdown/LSP/lint/装机/通知/Jugg 等各有归属）；无匹配则新起专项子代理并登记到域映射；**主对话永不被执行阻塞**。**并发与隔离**：多个子代理需要操作同一文件/同一特性时，**用 git worktree 各自隔离修改，最后合并回主分支**；合并遇到冲突就解决冲突（子代理或主对话），**不因共享文件而串行等待**——时间是最宝贵资源，能并发就尽量并发；任务必须独立才可并发，仓库级冲突可用 worktree 隔离并行。
11. **真机自动验收工作流（装机后自动执行，无需用户再要求）**：装机完成后，若存在待验收项且尚未完成真机自验，必须**自动**执行真机验收。**真机在线（adb devices 有真机且可用）时，自测一律优先在真机上进行**；模拟器仅当真机不可用、或自测会对真机会话/数据产生破坏性影响时作为替代。**第一步永远是启动冒烟**（`startup-smoke-test` skill：安装/冷启动后存活 ≥5 秒且 crash buffer 无本包 FATAL，P00 硬断言，模拟器/真机通用；模拟器先过闸门才允许真机安装；冒烟失败 = 验收失败，修复后重来，绝不放行）。然后自测：用 adb + uiautomator dump 文本树 + 服务端投影信号（/remote/connected、/remote/phone-logs、/remote/health 等）逐项验证 UI 与行为（主对话模型不能读图时以 dump 树为准，必要时派子代理辅助）；自测中发现问题**自动修复**（下沉子代理，修复后重新编译→装机→复验）；验收通过的项整理成**表格**（验收项 / 预期 / 实测 / 结论）交给用户做人工复验。**验收报告必须落盘**：每次设备验收/自测完成后、向用户汇报前，把完整报告写入 `docs/reports/acceptance/<日期>-<主题>.md`（含验收表、未实测项诚实标注、发现的问题、取证路径），并同步更新 `docs/status/pending-items.md` 台账——报告是验收的产物之一，不落盘视为验收未完成（2026-09-09 教训：此前多轮验收只存在于对话与台账，docs/reports/acceptance/ 长期只有一份报告）。**每个验证步骤必须截图存证并关联进报告**：截图统一落盘 `docs/screenshots/<日期>-<主题>/`（screencap 失败必须重试；手机全分辨率截图压缩到宽 ≤1080、单张尽量 ≤200KB）；报告必须附「截图清单」小节，按验收项逐张关联（Markdown 相对路径链接）；纯时序/事件流类验证无法截图的，在报告中注明原因（2026-09-09 教训：真机验收只留存了少量截图且报告未关联，用户翻「迭代验收截图」对不上账）。**真机测试窗口用 `device-keepawake` skill 保持屏幕常亮**（防系统自动锁屏打断测试）：测试开始 `on`、结束必须 `off`（推荐 `trap 'keepawake.sh off' EXIT` 兜底），严禁遗留常亮。
12. **疑难 bug 必存文档（docs/bugs/ 经验库）**：每解决一个疑难问题（尤其竞态/时序/跨端协议/框架特性类），必须写一份 bug 文档存入本仓库 `docs/bugs/`（模板 `docs/bugs/_TEMPLATE.md`），内容：背景、现象、排查过程、根因分析（**必须挖到深层根因而非表面现象**——重点剖析状态归属/时间线竞争等模型缺陷）、解法（**附 commit 哈希 + 核心 Code Diff**）、后续改进计划。后续遇到类似问题先查 docs/bugs/ 快速定位；不写文档视为该 bug 未完成。
13. **交付标准（主观能动性，禁止微操）**：改动必须把**关联细节一并做到位**——任何布局/位置/锚点变化，同屏相关元素（loading 指示、图标、弹层、坐标）必须同步自查修正；功能改动连带的状态/埋点/文档/台账同步更新。交付前自问「用户接下来还会指出什么小毛病」并先改掉。**小细节不需用户逐个点名**（用户明确要求不微操）；重大技术决策、关键选型才回主对话请示（铁律 1）。**设备级功能自测由实现子代理自己完成**：模拟器可用→模拟器自测（启动冒烟+UI/行为逐项）；真机可用→真机自测；两者都不可用→才允许仅代码级单测并在报告注明「未设备自测」。**单测是基线必加**，但不替代设备级功能自测。**实现子代理对自己实现的功能负责到底**：自测发现问题自己修复后再交付；不得把测试丢给主对话、更不得依赖用户真人代测（教训：转盘改动未在实现子代理内自测，由主对话代测且过程混乱）。其设备自测报告同样按规则 11 落盘 `docs/reports/acceptance/`，且每个自测步骤同样必须截图存证、关联进报告（未设备自测时在报告注明）。
14. **语义冲突必二次确认**：用户表述与现有功能/代码逻辑**明显冲突**时（如「立即上屏」vs 已有排队队列语义），必须先指出冲突点、给出两种理解及影响，**二次确认后再执行**；不得按字面语义直接实现（教训：发送状态按「立即上屏」执行，导致消息既上屏又排队双份显示，用户澄清后返工）。用户思考未必能覆盖全部逻辑，代理有责任把冲突提醒出来。
15. **用户技术决策台账纪律（2026-09-10 教训：Jugg 三项补充决策在对话中拍板后滞留 3 天，未记台账、未送达 Jugg 子代理，决策空转）**：① 每次向用户请示的技术问题与用户的答复，**同一轮内**记入 `docs/decisions/决策台账.md`——原始问题摘要、用户答案、日期时间（到分钟）、分发对象（功能域）、落实状态；② 决策一旦产生，**同一轮内**以消息送达对应功能域子代理（子代理空闲就发，正在跑就排队），不得等子代理被动来问；③ 子代理消费/实施后回写台账落实状态；④ 每日早报前核对台账「已决策未分发/未实施」项并推动清零；⑤ 用户对同一问题的新答复以新条目记录，不覆盖历史（可审计）。

## 历史决策记录（供未来会话参考）

- 调试后端：当前用 **CDP（Node Inspector）直连**，零依赖但仅 Node；`DebugManager` 回调接口已预留 DAP 平替 seam。**未来若扩语言（Python/Go/Rust）必须先问用户**再实施 DAP 后端。
- 语言服务器：TS/JS/Python/Rust/C/C++ + 官方 JetBrains kotlin-lsp（pull 诊断 + 项目导入）。Kotlin LSP 选型（2026-09-06 用户拍板，论证见 docs/decisions/kotlin-lsp-selection.md）：维持官方（唯一语义诊断最强+KMP 方向正确+活跃；fwcd 已 deprecated、Rust 版无类型检查均不迁）；已知限制=LSP-1561 Android 导入 CCE 未发布修复（Kotlin 诊断假阴性，TS/Python/Rust/C 不受影响），监控上游发布后按 UPGRADE-NOTES 升级。
- 自动续跑：持续重试 + 指纹幂等 + work.json sessionId 归属（根治版）。
- APK 瘦身（体积决策）：debug 包不 minify 保留可调试；新增 `release-in-house`（R8 + 资源裁剪 + debug 签名 + arm64-only，装机自测用，2.16MB vs debug 12.6MB）与 `release-store`（R8 + arm64-only，商店包，签名占位）；keep 规则见 `composeApp/proguard-rules.pro`（serialization / zxing / mikepenz-markdown / org.intellij.markdown 整体保留，宁可多 keep 不误删）。
- 连接与弱网可靠性（2026-09-07 用户拍板，调研见 docs/decisions/网络连接与弱网可靠性调研.md）：① send_message 升级 msgId+ack+幂等去重（bridge v0.14）；② 本地消息队列=文件 KV 持久化 + 断线自动重放（上限/超时后转显式失败红❗，at-least-once 语义）；③ 应用层 ping/pong 判活（假连接秒级判死）+ bridge 新增 LAN 接口二级 WS 监听 + mDNS（保留鉴权）；④ frp/VPS 暂不租（Tailscale 过渡保留）；⑤ 弱网模拟器=mock-bridge 注入为主+TCP 代理为辅；⑥ 演进顺序=消息底座→LAN→frp→弱网模拟。
- 九项决策（2026-09-05 用户拍板）：① APK 瘦身=两 release 变体+arm64-only+≤6.3MB（已实施 2.16MB）；② Jugg 无头编译服务 MVP（2-5 人日）；③ Kotlin LSP 优先稳定版（含 LSP-1561 修复）；④ 中断语义=有排队消息时弹框二选一（终止并清空 / 仅终止保留，加取消）；「仅终止保留」后队列仍有待执行消息时自动启动新一轮 Agent 循环消费；⑤ 发送状态=IM 模式（点发送立即上屏+同时间行 Loading；成功消失；失败红色❗点击重发；输入框立即清空）；⑥ 装机进度=全程可见（包大小/传输百分比/步骤/错误归类）；⑦ R4 DEX registers_size 门禁挂构建自动执行（>128 告警 >256 失败）；⑧ goal 完成态对齐 DSH Web 隐藏；⑨ GitHub 推送=暂不推（等全部验收后再说）。

## 代码质量闸门（lint）

**强制流程：每次代码变更完成 → lint P0 → 清零 → commit；pre-commit hook 兜底拦截。**

### 工具与用法
- Kotlin lint 用 **detekt**（gradle 插件 `io.gitlab.arturbosch.detekt` 1.23.8）。
  - 全量：`./gradlew :composeApp:detekt`（或 `scripts/lint.sh`）
  - P0 闸门：`./gradlew :composeApp:detektP0`（或 `scripts/lint.sh p0`）
- 配置：`config/detekt/detekt.yml`（全量）、`config/detekt/detekt-p0.yml`（P0 子集）。

### P0 定义（高风险规则，命中必须清零才允许 commit）
- `complexity/LongMethod`（超大函数，阈值 200 行）
- `complexity/LargeClass`（类过大，阈值 1200 行）
- `complexity/LongParameterList`（超长参数列表，函数 10 / 构造器 10）

### R4 DEX registers 硬门禁（构建期自动执行，VerifyError 根因兜底）
- 任务：`checkDexRegisters`（全量聚合）、`checkDexRegisters<Variant>`（随 `assemble<Variant>` 自动执行，`finalizedBy` 接线，无循环依赖、每变体只跑一次）。
- 规则：扫 APK 内所有 classes*.dex，解析本应用包（`com/daniel/dshremote`）每个方法的 `registers_size`——**>256 报错（构建失败）、>128 告警**。
- 用法：`./gradlew :composeApp:checkDexRegisters`（全量）、`:composeApp:checkDexRegistersDebug`（单变体，会先打包再扫描）。
- 覆盖参数：`-PdexRegistersMax=NNN`（报错阈值，默认 256，用于验证与 CI）、`-PdexRegistersWarn=NNN`（告警阈值，默认 128）、`-PdexRegistersPackage=...`（包前缀，默认 `com/daniel/dshremote`；三方库如 Compose Material3 的 268 寄存器方法会误报，故只扫本包）。
- 解析器：buildSrc 纯函数 `DexRegistersParser`（`./gradlew -p buildSrc test`，5 例单测覆盖正常/超128/超256/多dex/解析容错）。

### 强制流程
1. 代码变更完成后、commit 前，必跑 P0：`scripts/lint.sh p0`（agent 亦可用 `code-lint` skill 一键跑）。
2. P0 命中 → **必须先修复清零**，才允许 commit。
3. pre-commit hook 兜底：`git commit` 时自动跑 P0 闸门，未清零直接拦截。
   - 仅极特殊场景允许 `git commit --no-verify` 跳过（须在 commit message 说明原因）。
4. 全量 `detekt` 为建议项（存量风格告警不阻塞），但新增代码应尽量不引入新告警。

### 规则积累
- 新增/收紧规则与动机登记在 `docs/lint-rules.md`；阈值只收紧不放宽。
- 收紧路径：拆分 `BridgeClient` 等大类、长函数 → 逐步把 P0 阈值降到 detekt 默认（LongMethod 60 / LargeClass 600 / LongParameterList 6/7）。
- DEX 校验器防回归的 R1~R6 规则（含 R4「构建期扫 DEX registers_size，>128 告警 >256 报错」）已归档于 `docs/coding-rules/verifyerror-deep-dive.md`；其中 R4 已落地为「R4 DEX 硬门禁」（见上）。

### 安装 hook（首次 / 重新 clone 后）
- 运行 `scripts/install-hooks.sh`（把 `hooks/pre-commit` 装进 `.git/hooks/`）。
