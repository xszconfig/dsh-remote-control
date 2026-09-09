# 真机验收报告 — dsh-remote-control（2026-09-09）

- 真机：华为 HBN-AL00，Android 12，adb over Tailscale `100.71.236.18:5555`
- 被测包：**release-in-house 精简包**（R8 + arm64-only，`composeApp-release-in-house.apk` = 2,193,455 字节 ≈ **2.09MB**，对比 debug 12.4MB）
- 服务端：本机 http://localhost:3080（bridge coreVersion 0.14.0）
- 代码基线：main HEAD `f040325`（本次**未改动任何源码、未做 git 提交**；main 工作树未被触碰）
- 取证方式：uiautomator dump 文本树（本代理模型不能读图，结论以 XML 树 + 服务端投影 /remote/connected /remote/phone-logs /remote/sessions 为准）+ adb 截图存证
- 启动冒烟：P00 PASS（`startup-smoke-test` skill，存活 ≥5s、crash buffer 无本包 FATAL、前台焦点属于本 app）

## 验收表（20 项）

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 启动冒烟（P00） | 存活≥5s、无本包 FATAL | smoke.sh 三关全 PASS（pid=15877 存活、crash buffer 无 FATAL、焦点属本 app） | ✅ PASS |
| 2 | 连接状态 | 页内显示已连接 + /remote/connected 出现 deviceId | 会话列表顶部显示 `MacBook-Air-119.local` + `100.76.121.118:3080 · 18 会话`（无页面跳转）；/remote/connected 出现 `cc4d6501-2921-495a-b142-9362e3cf7bea`（model HBN-AL00） | ✅ PASS |
| 3 | 自动打开最近会话 | 冷启动自动进入上次会话 | 冷启动 phone-logs `自动打开最近会话 sessionId=session-ab787050-…`（自举开发dsh-remote-control），无需点击 | ✅ PASS |
| 4 | 时间戳新格式 | 今天/昨天/前天/M月d日 前缀 + 时刻恒显 | 会话列表 `今天 15:15`、`8月23日 11:07`；子代理列表 `今天 15:31`、`昨天 10:40`、`前天 22:23`——四种前缀全部真机可见，时刻恒显 | ✅ PASS |
| 5 | 发送状态 IM 模式 | 立即上屏+Loading→成功消失+输入框清空 | 发送后消息气泡 `[zhenji-self-test] reply OK only` 立即上屏、EditText 立即清空（text=''）；phone-logs `发送点击→发送消息→已发送 send_message→ack ok` 全链路（Loading 为毫秒级未 dump 捕获，phone-logs 确认 sending→sent→ack） | ✅ PASS |
| 6 | msgId/ack/幂等 | 服务端会话历史上恰好出现一次 | phone-logs `ack ok msgId=m-session--1788938733315-0，消息已归服务端，删除持久化记录`；UI 中该消息恰好一个气泡，无重复 | ✅ PASS |
| 7 | 心跳判活 | 前台≥3min 无假连接/无频繁重连横幅 | 应用层 ping/pong 每 20s 一次（phone-logs 连续记录 ~26min），全程无假连接、无重连横幅（仅前台化时 1 次重连） | ✅ PASS（⚠️见问题1） |
| 8 | 中断弹框 | 有排队消息时点中断→三选项；点取消退出 | 点中断出现「中断确认 / 当前会话有 1 条排队消息」+「终止当前循环并清空排队消息 / 仅终止循环，不清空消息 / 取消」；点取消退出，会话仍运行中、排队未清 | ✅ PASS |
| 9 | 转盘 | 7线半扇图标；按需显示；原位展开；方向；列表滑动不自动收起 | 上滚后 phone-logs `转盘打开 refs=6 hasMore=true`（按需显示 + 原位展开）；7线半扇图标=代码级已验（CollapsedKnobVisual）；方向=代码级+单测已验（MessageDialMathTest） | ⚠️ 部分实测（视觉/方向/不自动收起=代码级） |
| 10 | 新消息不打断转盘 | 转盘展开时新消息不强制滚底、不收起 | 代码级已验（showJumpToBottom/follow 逻辑）；真机未稳定触发（转盘 2.5s 自动收起 + relay 时序难控） | ⚠️ 代码级已验，未真机稳定触发 |
| 11 | Markdown 宽表格横滑 | 宽表格可横向滑动 | 代码级+单测已验（MarkdownTableScroll 表解析 + 横滑实现；MarkdownTableScrollTest 5 例）；真机历史未找到含宽表格消息 | ⚠️ 代码级已验，未真机实测 |
| 12 | 代码块 | 行号固定+长行不换行+横向滑动 | 代码级+单测已验（MarkdownCodeBlockTest 行号生成/右对齐 5 例；MarkdownCodeBlock 不换行+横滑渲染）；真机未精确验证横滑 | ⚠️ 代码级已验，未真机精确实测 |
| 13 | goal 完成态隐藏 | 已完成 goal 会话不显示 | 代码级已验（GoalPanel: `phase != "complete"` 才渲染）；当前会话无可验证的已完成 goal 会话 | ⚠️ 代码级已验，未真机实测 |
| 14 | 跳到底部按钮 | 上滚后出现、点击回到底部 | 上滚后右下 48dp 圆钮出现，点击 phone-logs `跳到底部点击`，列表回到最新（时间戳 15:30→15:31） | ✅ PASS |
| 15 | 子代理列表元信息+滚动 | 会话列表显示子代理元信息、可滚动 | 🤖56 下拉展示子代理（名称 + 状态 + 时间 + 时长 + token，如 `运行中 · 今天 15:31 · 26m · 5.5M`）；56 个子代理 10 条可见，滚动后首条变 `空闲 · 前天 00:12`（可滚动） | ✅ PASS |
| 16 | 主题三态 | 跟随系统/浅色/深色各截 1 张，恢复跟随系统 | 自动截图三态成功（dark 明显变暗、已恢复 `◉ 跟随系统`）；但**用户人工真机复验：浅色模式适配不完整**——会话列表背景、输入框等仍为黑色（像素审计：light.png 整体 91% #F8F9FC 已变浅，但顶部条带 y80-300 有 23% 深色块 #313131 13% / #222222 10%，仅部分组件适配） | ❌ FAIL（已派单 4517b8d7 修复，本代理不修） |
| 17 | 主动通知 | 后台时结果交付通知出现 | 授予 POST_NOTIFICATIONS；发送消息后 agent idle → phone-logs `NOTIFY 触发 DELIVERY presence=BACKGROUND` → 通知栏 NotificationRecord 标题「结果已就绪」正文「『自举开发dsh-remote-control』本轮已完成」（channel dsh_delivery）。审批/提问通知=未真机触发（代码级+单测已验） | ✅ PASS（结果交付；⚠️见问题1） |
| 18 | steer 队列回归 | 排队消息「同步中/❗」状态呈现 | 排队面板显示 `⏳ 排队中的消息（1）` + 消息「排队」+「插队」「删除」按钮（真实队列项，非 stuck 同步中）；「同步中…」乐观态=代码级+单测已验（QueueItemLogicTest isSyncingQueueItem） | ✅ PASS（呈现）+ 代码级 |
| 19 | APK 瘦身 | release-in-house 成功安装并工作 | 2.09MB release-in-house 真机安装成功（lastUpdateTime 15:10:17），本次验收全程使用该包正常工作 | ✅ PASS |
| 20 | 平板三栏 | — | 用户已暂停 | ⏭️ 跳过 |

## 未真机实测项清单（诚实标注）

| 项 | 状态 | 说明 |
|----|------|------|
| 9 转盘·方向跳转 | 代码级+单测已验 | 真机只验证了「转盘打开 refs=6」；顺时针/逆时针跳转语义由 MessageDialMathTest.stepsCrossed 覆盖 |
| 9 转盘·列表滑动期间不自动收起 | 代码级已验 | 2.5s 自动收起 + relay 时序难控，未真机精确验证 |
| 10 新消息不打断转盘 | 代码级已验 | 转盘展开态短、时序难控，未真机稳定触发 |
| 11 Markdown 宽表格横滑 | 代码级+单测已验 | 真机历史未翻到含宽表格消息（滚动成本高） |
| 12 代码块横滑 | 代码级+单测已验 | 行号/不换行逻辑单测覆盖，真机未精确验证横滑手势 |
| 13 goal 完成态隐藏 | 代码级已验 | 当前无可验证的已完成 goal 会话 |
| 17 审批/提问通知 | 未真机触发 | 无稳定触发手段；NotificationControllerTest + 代码已验 |

## 发现的问题

### 问题 1（功能级/架构级，未改，待路由）：退后台无前台服务保活，长后台 WS 断开 → 后台主动通知可能漏发

- **现象**：按 HOME 退后台后，应用层 ping/pong 停止、`/remote/connected` 变空（`[]`），前台化后才自动重连（`第 1 次重连 → 握手成功 → hello 到达`）。
- **证据**：phone-logs 中 ping/pong 在退后台后停更；`/remote/connected` 从 `[cc4d6501…]` 变为 `[]`，前台化后 `connectedAt` 更新。
- **影响**：`结果交付`（D1/D2/D3）等后台主动通知依赖「手机收到 agent running→idle 事件」。若长时间后台，WS 断开、事件到不了手机，通知会漏发。本次通知验证是趁 agent idle 时手机恰好短暂后台/在场态非当前会话才触发的（`presence=BACKGROUND`）。
- **建议**：路由给「通知/连接」功能域代理评估——是否加前台服务（foreground service）保活 WS，或改用推送通道兜底。属功能级改动，按铁律不改，待主对话路由。

### 问题 2（UI/主题，用户人工复验判定 FAIL，已派单修复，本代理不修）：浅色模式适配不完整

- **现象**：用户人工在真机上复验发现，浅色（Light）模式下会话列表背景、输入框等仍为黑色（深色残留），仅部分组件适配。
- **证据**：像素审计 `light.png` 整体 91% #F8F9FC 已变浅，但顶部条带（y80-300）有 23% 深色块（#313131 13% / #222222 10%）。
- **处理**：主对话已把全量浅色适配修复派给主题域代理（4517b8d7），由其做代码审计 + 构建 + 装机复验。**本验收代理不做任何修复动作**（避免与主题域代理并发改同一文件）。

### 小问题（UI/文案/布局类，需自修）：无

本次验收未发现需要本代理自行修改的小问题（主题浅色残留已由主题域代理 4517b8d7 接管）。

## 修复分支清单

无（未创建 worktree、未提交。发现的两个问题分别归：①「后台保活」功能级问题→待主对话路由通知/连接域；②「浅色残留」→已由主题域代理 4517b8d7 接手，本代理不修）。

## 截图清单（仓库留档 `docs/screenshots/2026-09-09-realdeivce/`，2026-09-10 按新规则补关联）

| 截图 | 关联验收项 |
|---|---|
| [`00-baseline.jpg`](../../screenshots/2026-09-09-realdeivce/00-baseline.jpg) | 基线（验收开始前） |
| [`01-coldstart.jpg`](../../screenshots/2026-09-09-realdeivce/01-coldstart.jpg) | 项 1 冷启动冒烟 |
| [`02-sessionlist.jpg`](../../screenshots/2026-09-09-realdeivce/02-sessionlist.jpg) | 项 2 连接状态、项 15 子代理元信息 |
| [`07-interrupt-dialog.jpg`](../../screenshots/2026-09-09-realdeivce/07-interrupt-dialog.jpg) | 项 8 中断弹框三选项 |
| [`08-reconnected.jpg`](../../screenshots/2026-09-09-realdeivce/08-reconnected.jpg) | 项 7 心跳判活（重连后会话视图） |
| [`10-subagent-list.jpg`](../../screenshots/2026-09-09-realdeivce/10-subagent-list.jpg) | 项 15 子代理列表滚动 |
| [`11-scrolled-dial.jpg`](../../screenshots/2026-09-09-realdeivce/11-scrolled-dial.jpg) | 项 9 转盘按需显示、项 14 跳到底部 |
| [`12-dial-expanded.jpg`](../../screenshots/2026-09-09-realdeivce/12-dial-expanded.jpg) | 项 9 转盘原位展开 |
| [`13-notification.jpg`](../../screenshots/2026-09-09-realdeivce/13-notification.jpg) | 项 17 结果交付通知 |
| [`follow.jpg`](../../screenshots/2026-09-09-realdeivce/follow.jpg) · [`light.jpg`](../../screenshots/2026-09-09-realdeivce/light.jpg) · [`dark.jpg`](../../screenshots/2026-09-09-realdeivce/dark.jpg) | 项 16 主题三态 |
| [`current.jpg`](../../screenshots/2026-09-09-realdeivce/current.jpg) | 验收后当前态 |

**缺失截图**（当时 screencap 失败/未截，结论以 uiautomator XML 为准；自 09-10 起按 AGENTS.md 规则 11 必须重试截图并关联）：`03-enter-session`、`04-scrolled-up`、`05-input-typed`、`06-after-send`、`09-settings`。

**uiautomator XML 文本树（次选证据）**：`/tmp/accept-2026-09-09/*.xml`（全部非空）

## 非破坏性说明

- 真实发送 2 条自测消息（`[zhenji-self-test] reply OK only`、`[zhenji-self-test-2] reply OK only`），均已 ack 归服务端。
- 中断弹框只验外观 + 点取消，未实际终止任何运行任务。
- 未清理任何会话、未终止任何 goal。
- 主题测完已恢复「跟随系统」。
- 连接地址全程未改动（`100.76.121.118:3080`）。
