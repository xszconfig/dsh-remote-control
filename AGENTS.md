# AGENTS.md —— 本项目协作规则（跨会话记忆）

## 铁律（用户明确要求，违反即失误）

1. **重大技术决策必须先停下来问用户**：涉及架构、重大技术路线、重大技术选型（例如调试协议 CDP vs DAP、语言服务器选型、持久化方案、协议设计方向）一律不得擅自决定。这些决策影响项目拓展性、可维护性与后续技术规划。
2. **只做本地 commit，不推 GitHub**：推送只在用户明确指示"推 GitHub"后执行；用户验收通过前不推送。
3. **不出现用户非预期的页面跳转**：连接状态变化只用横幅/页内元素表达。
4. **重启服务端必须先问用户确认**；重启脚本须保留完整 PATH（Android platform-tools）。
5. **装机用 huawei-adb 系列 skill**（huawei-adb-install 鲁棒模式 / huawei-adb-fast-install 快速模式），不用裸 push+pm install。
6. **所有数据以服务端投影为准**：客户端不做本地推算（如计时），单向数据流。
7. **消息推送目标 100~300ms 内送达**。
8. 真机验收：编译通过 → 装机 → 自动验收，结果直接汇报。
9. **移动端优先（项目大背景）**：这是一个**手机端 Remote Control App**——所有设计、尤其 UI 与交互（字号/布局/滚动/返回导航/触控）必须以手机屏幕为出发点。桌面端/Web/手机端的设计原则不同，严禁照搬桌面惯例（教训：曾把 Markdown 标题映射到 display 级 57/45/36sp 桌面展示字号）。库/组件默认值必须先核对是桌面还是移动预设。详见 docs/ui-mobile-first.md。
10. **任务并发与子代理消费（任务编排基本原则）**：所有大型任务、耗时任务、重度分析/调研任务一律下沉子代理（后台并发执行），利用其高注意力产出更好结果；主对话绝不长时间卡在单一任务上——与"Android UI 主线程不做 IO/网络等耗时操作，否则卡 UI"同理。主对话只做：信息汇总、任务派发、验收、随时响应用户。**原则上所有任务都由子代理完成**，只有 10 秒内能解决的极简单问题才由主对话直接处理。前提：任务必须独立——共享同一未提交文件/同一特性的任务不能并发拆给多个子代理（先提交再拆）；仓库级冲突可用 git worktree 隔离并行。
11. **真机自动验收工作流（装机后自动执行，无需用户再要求）**：装机完成后，若存在待验收项且尚未完成真机自验，必须**自动**执行真机验收。**第一步永远是启动冒烟**（`startup-smoke-test` skill：安装/冷启动后存活 ≥5 秒且 crash buffer 无本包 FATAL，P00 硬断言，模拟器/真机通用；模拟器先过闸门才允许真机安装；冒烟失败 = 验收失败，修复后重来，绝不放行）。然后自测：用 adb + uiautomator dump 文本树 + 服务端投影信号（/remote/connected、/remote/phone-logs、/remote/health 等）逐项验证 UI 与行为（主对话模型不能读图时以 dump 树为准，必要时派子代理辅助）；自测中发现问题**自动修复**（下沉子代理，修复后重新编译→装机→复验）；验收通过的项整理成**表格**（验收项 / 预期 / 实测 / 结论）交给用户做人工复验。
12. **疑难 bug 必存文档（docs/bugs/ 经验库）**：每解决一个疑难问题（尤其竞态/时序/跨端协议/框架特性类），必须写一份 bug 文档存入本仓库 `docs/bugs/`（模板 `docs/bugs/_TEMPLATE.md`），内容：背景、现象、排查过程、根因分析（**必须挖到深层根因而非表面现象**——重点剖析状态归属/时间线竞争等模型缺陷）、解法（**附 commit 哈希 + 核心 Code Diff**）、后续改进计划。后续遇到类似问题先查 docs/bugs/ 快速定位；不写文档视为该 bug 未完成。

## 历史决策记录（供未来会话参考）

- 调试后端：当前用 **CDP（Node Inspector）直连**，零依赖但仅 Node；`DebugManager` 回调接口已预留 DAP 平替 seam。**未来若扩语言（Python/Go/Rust）必须先问用户**再实施 DAP 后端。
- 语言服务器：TS/JS/Python/Rust/C/C++ + 官方 JetBrains kotlin-lsp（pull 诊断 + 项目导入）。
- 自动续跑：持续重试 + 指纹幂等 + work.json sessionId 归属（根治版）。
- APK 瘦身（体积决策）：debug 包不 minify 保留可调试；新增 `release-in-house`（R8 + 资源裁剪 + debug 签名 + arm64-only，装机自测用，2.16MB vs debug 12.6MB）与 `release-store`（R8 + arm64-only，商店包，签名占位）；keep 规则见 `composeApp/proguard-rules.pro`（serialization / zxing / mikepenz-markdown / org.intellij.markdown 整体保留，宁可多 keep 不误删）。

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

### 强制流程
1. 代码变更完成后、commit 前，必跑 P0：`scripts/lint.sh p0`（agent 亦可用 `code-lint` skill 一键跑）。
2. P0 命中 → **必须先修复清零**，才允许 commit。
3. pre-commit hook 兜底：`git commit` 时自动跑 P0 闸门，未清零直接拦截。
   - 仅极特殊场景允许 `git commit --no-verify` 跳过（须在 commit message 说明原因）。
4. 全量 `detekt` 为建议项（存量风格告警不阻塞），但新增代码应尽量不引入新告警。

### 规则积累
- 新增/收紧规则与动机登记在 `docs/lint-rules.md`；阈值只收紧不放宽。
- 收紧路径：拆分 `BridgeClient` 等大类、长函数 → 逐步把 P0 阈值降到 detekt 默认（LongMethod 60 / LargeClass 600 / LongParameterList 6/7）。
- DEX 校验器防回归的 R1~R6 规则（含 R4「构建期扫 DEX registers_size，>128 告警 >256 报错」）已归档于 `docs/coding-rules/verifyerror-deep-dive.md`，其中 R4 待接入 lint 体系。

### 安装 hook（首次 / 重新 clone 后）
- 运行 `scripts/install-hooks.sh`（把 `hooks/pre-commit` 装进 `.git/hooks/`）。
