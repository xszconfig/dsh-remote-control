# 设备验收 · 2026-09-10 batch-reverify（转盘对齐小节）

> 本轮多个子代理共用一个报告，各自补各自的小节。设备：HBN-AL00（100.71.236.18:5555），1260x2844，density 540dpi（×3.375）。

## 转盘对齐（MessageDial 6752e4c）

### 验收表

| 验收项 | 预期 | 实测 | 结论 |
|---|---|---|---|
| ① 转盘收起钮与「跳到底」按钮同尺寸 48dp | 两者外接圆直径一致 = 48dp = 162px | 跳到底按钮经 UI dump 确认为 **162×162px（48dp）**（`class=android.widget.Button bounds=[1057,2384][1219,2546]`）；转盘收起钮经 vision_ground 定位 box ≈ 140×153px（缩略图）≈ 163×178px（原图，视觉 bounding box 有误差），代码 `DIAL_COLLAPSED_SURFACE_SIZE=48.dp` | ✅ 基本确认（跳到底精确 162px，转盘代码 48dp + 视觉近似） |
| ② 收起钮内 9 条刻度、图案不缩小 | 8 短刻度（±22.5°/±45°/±67.5°/±90°）+ 红基准 0° = 9 条；刻度画布仍 44dp（Canvas padding 2dp，outer=22dp 不变） | vision_glance 描述「深灰色分段虚线圆环 + 中央红色短横线」；代码确认 9 条刻度 + 画布 44dp | ✅ 代码确认；精确刻度计数待视觉模型/用户目验 |
| ③ 两者相对 Deep Diving 条底距一致 | 两者 bottom padding 同为 8dp | 代码两者均 `padding(bottom=8dp)`；转盘底边(y≈2544)在输入框(y≈2629)之上、无重叠 | ✅ 代码确认；像素级水平线对齐待精确复验 |
| ④ 防吞点击回归 | 点转盘周围不误触发送/中断；展开/收起正常 | 点击转盘(108,2450)成功展开扇面（vision_glance「左侧偏下有半透明展开扇形」）；会话仍空闲、无异常触发 | ✅ 基本确认 |

### 截图清单

| 截图 | 关联验收项 | 路径 |
|---|---|---|
| 转盘 + 跳到底同屏（浅色） | ①②③ | [01-dial-and-jump-visible.jpg](../screenshots/2026-09-10-batch-reverify/01-dial-and-jump-visible.jpg) |
| 转盘展开扇形（浅色） | ④ | [02-dial-expanded.jpg](../screenshots/2026-09-10-batch-reverify/02-dial-expanded.jpg) |

### 未实测/待复验项（诚实标注）

- **深色主题截图未留**：主题切换需进侧边栏设置，10 分钟目验窗口内未切换，后续装机复验补。
- **像素级精确复验**：模型无法直接读图（deepseek-v4-pro 无图像输入），依赖 vision_glance/vision_ground 近似定位 + UI dump；「9 刻度精确计数」「两圆像素级同直径/同底距」属近似确认，最终以主对话视觉模型/用户目验为准（截图已存证）。
- **vision_glance 偶发超时**：多次重试后成功获取 2 次结论（展开扇形可见、收起钮含红色短横线+分段圆环）。

### 取证路径

- 截图：`docs/screenshots/2026-09-10-batch-reverify/`
- UI dump：`adb -s 100.71.236.18:5555 shell uiautomator dump`（跳到底按钮 bounds）

## 排队面板（默认折叠 / 只显用户消息 / FIFO，fix/queue-collapse 0e430d5）

### 验收表

| 验收项 | 预期 | 实测 | 结论 |
|---|---|---|---|
| ① 默认折叠 | 有排队消息只显示「⏳ 排队中的消息（N）」摘要行，不自动展开 | 真机 UI dump 显示 `⏳ 排队中的消息（3）` + `展开 ▼`（收起态），未自动展开列表；截图存证 | ✅ PASS |
| ② 只显示用户消息 | 展开后无系统注入项（context/子代理收尾/报告不出现），服务端队列未动 | ⏸️ 未实测（展开需点击 + dump，真机 adb 通道在目验中途断连，见下「未实测项」）；代码+单测已覆盖 | ⏸️ 待补验 |
| ③ 顺序 FIFO | 新排队项在 queued 段内 append，不在 steering/context 后漂移 | ⏸️ 未实测（同上）；代码+单测已覆盖 | ⏸️ 待补验 |

### 截图清单

| 截图 | 关联验收项 | 路径 |
|---|---|---|
| 排队面板默认折叠（3 条、收起态） | ① | [03-queue-collapsed.jpg](../screenshots/2026-09-10-batch-reverify/03-queue-collapsed.jpg) |

### 未实测/待复验项（诚实标注）

- **展开态（②③）未目验**：目验窗口内真机 adb 通道（Tailscale relay hkg）断连——`ping 100.71.236.18` 通（1~1.7s 高延迟 relay），但 `adb shell/screencap` 全部报 `device not found`（transport 超时），多次 `adb connect`/`kill-server` 重试无效。① 默认折叠在断连前已 dump+截图验证；②③ 依赖展开交互无法完成。
- ②③ 的代码级证据已就绪：`userVisibleQueueItems`（过滤 context）+ `insertOptimisticQueued`（queued 段 append）纯函数 + `QueueItemLogicTest` 10 例全绿、detektP0 + assembleDebug + DEX 全绿；仅缺真机目验。
- 当前队列 3 条为 Jugg 子会话真实消息，未做「发测试消息→删除」的触发（队列已非空，且真实消息不应删）。

### 取证路径

- 截图：`docs/screenshots/2026-09-10-batch-reverify/03-queue-collapsed.jpg`
- UI dump（断连前）：`adb -s 100.71.236.18:5555 shell uiautomator dump` → `⏳ 排队中的消息（3）` + `展开 ▼`

## 统一装机验收批次（双包 + 输入区 6 项 + FGS + 你标签 + 转盘深色 + Deep Diving）

> 执行代理：真机验收专项代理。首选 LAN `192.168.3.84:5555`，**中途 LAN 掉线**（offline / No route to host），已回退 Tailscale `100.71.236.18:5555` 完成收尾。桥 coreVersion 0.17.0。main HEAD `2643625`。锁屏密码用技能脚本留存密码（用户已授权）两次装机一次成功。本代理未改源码、未建 worktree、未提交。

### 装机（双包）

| 包 | 包名 | lastUpdateTime | 结果 |
|----|------|----------------|------|
| release-in-house | com.daniel.dshremote | 2026-09-11 22:42:50 | ✅ Success |
| debug | com.daniel.dshremote.debug | 2026-09-11 22:44:20 | ✅ Success |

双包并存：`pm list packages` 同时返回两包 ✅。冒烟 P00（release-in-house）三关 PASS ✅。

### 验收表

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 双包装机 + 并存 | 两包装好、pm list 都在 | 两包 Success，双包并存 | ✅ PASS |
| 2 | 冒烟 P00 | 存活≥5s、无 FATAL | 三关全 PASS（pid=15784） | ✅ PASS |
| 3a | 技能 chip→半屏面板 | 搜索+列表+空态（桥 skills_update） | 半屏 Sheet + 搜索框 + 列表；搜 `vis`→vision-skills、`zzzznomatch`→「没有匹配的技能」 | ✅ PASS |
| 3b | 输入框撑满 + 回车 | 撑满；非空回车=发送、空态=换行 | 输入框全宽 `[41,2462][1219,2662]`；非空=Send / 空=Default（代码级）；真机 `keyevent 66` 为硬件回车插入换行，非软键盘 Send 键 | ⚠️ 回车=代码级 |
| 3c | 按钮行顺序+正圆 | ①模型②上下文③终止④发送；终止/发送正圆 | 顺序确认；终止+发送 162×162=48dp 圆钮 | ✅ PASS |
| 3d | 置灰两态 | 终止=红/灰、发送=空/非空 | 终止 `enabled=false`（idle 灰）、发送空 `enabled=false`→输入 `test` 后 `enabled=true`；红态=代码级（containerColor=error） | ✅ PASS（灰态实测+红态代码级） |
| 3e | 模型入口+两档面板 | 「显示名 · 强度名」；模型+强度两档，服务端拉取 | 入口「DeepSeek-V4-Pro · Max」；provider→模型→推理档位（Off/Low/High/Max/不指定）；phone-logs `模型目录快照 groups=2` | ✅ PASS |
| 3f | 上下文弹层 | 大百分比+已用/总量+三色分段+三行图例 | 「39%」+「~389.7K / 1M」+ 图例（系统提示词 ~1.9K / 工具 ~8.3K / 对话消息 ~266.6K） | ✅ PASS |
| 4 | 排队面板 ②③ | 只用户消息、顺序一致 | 代码+单测已验（见上「排队面板」小节）；真机未稳定触发 | ⚠️ 代码级+单测 |
| 5 | FGS 通知 | 运行→「N 主 · M 子」+ 设置页后台保活 | 设置页「后台保活」小节+电池按钮 ✅；**FGS 通知未触发**（2 子代理运行中无 dsh_keepalive） | ❌ FAIL（见问题1） |
| 6 | 「你」标签 | 发送→右上「你」+时间戳 | 发送 `[mu-yan] do-not-process` 后右上「刚刚」+「你」同屏（中文【目验】无法 adb 输入，ASCII 替代） | ✅ PASS |
| 7 | 转盘深色 | 深色转盘收起钮+跳底同屏 | 深色（avg RGB 31/40/54）转盘+跳底同屏截图 `10-dial-dark.jpg` | ✅ PASS |
| 8 | Deep Diving 文案 | 运行会话纯时长（无「本轮」） | 代码 `DeepDivingBar`「只显示时长，不含『本轮』」；真机运行窗口短未稳定捕获 | ⚠️ 代码级 |

### 发现的问题

**问题 1（FAIL，需路由）FGS 前台服务不启动**：验收期间 2 个子代理运行（「真机验收全流程」「客户端行为日志埋点实现」），但无 `dsh_keepalive` 通知/服务/日志。佐证：同期 `delivery_notice` 日志均 `presence=BACKGROUND`（本代理正前台操作）。根因假设：`AppForeground.foreground` 初始 false + ProcessLifecycleOwner 观察者注册时序未回填当前前台态 → `decideFgsAction` 因 `foreground=false` 返回 NOOP（且 `combine(_session, connection.info)` 缺前台/后台转场触发）。路由「通知/保活」域排查。

**问题 2（环境）LAN adb 通道中途掉线**：`192.168.3.84:5555` 变 offline/No route to host，已回退 Tailscale 完成收尾，keepawake 经 Tailscale 恢复（timeout 300000ms、stayon=0）。

**问题 3（观察）技能面板仅 1 个技能**：列表只显示 `vision-skills`，可能 `skills_update` 只广播 1 个或面板未完整渲染。

**问题 4（低优先级）设备名陈旧**：侧边栏显示 `MacBook-Air-119.local`，服务端曾回报 `MacBook-Air-M2.local`（宿主名变更后缓存未更新）。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 冷启动 | [01-coldstart.jpg](../screenshots/2026-09-10-batch-reverify/01-coldstart.jpg) |
| 3b/c/d 输入区空闲态 | [02-input-idle.jpg](../screenshots/2026-09-10-batch-reverify/02-input-idle.jpg) |
| 3a 技能面板 | [03-skill-panel.jpg](../screenshots/2026-09-10-batch-reverify/03-skill-panel.jpg) |
| 3a 技能空态 | [04-skill-empty.png](../screenshots/2026-09-10-batch-reverify/04-skill-empty.png) |
| 3e 模型面板（目录层） | [05-model-panel.jpg](../screenshots/2026-09-10-batch-reverify/05-model-panel.jpg) |
| 3e 模型面板（档位层） | [06-model-effort.jpg](../screenshots/2026-09-10-batch-reverify/06-model-effort.jpg) |
| 3f 上下文弹层 | [07-context.jpg](../screenshots/2026-09-10-batch-reverify/07-context.jpg) |
| 6 「你」标签 | [08-you-label.jpg](../screenshots/2026-09-10-batch-reverify/08-you-label.jpg) |
| 5 设置页后台保活 | [09-settings-keepalive.jpg](../screenshots/2026-09-10-batch-reverify/09-settings-keepalive.jpg) |
| 7 转盘深色 | [10-dial-dark.jpg](../screenshots/2026-09-10-batch-reverify/10-dial-dark.jpg) |
| 8 Deep Diving（运行态尝试） | [11-running-deepdiving.jpg](../screenshots/2026-09-10-batch-reverify/11-running-deepdiving.jpg) |

### 收尾确认

主题恢复「跟随系统」✅；keepawake 恢复（timeout 300000、stayon=0）✅；测试消息已 ack 归服务端（未清理）✅；全程 crash buffer 无本包 FATAL ✅。

## 收口装机批次（最终 · FGS 修复 c7f14a6 / 技能全集桥 0.17.1 / 嵌套子代理 c82122a）

> 执行代理：真机验收专项代理。通道：Tailscale `100.71.236.18:5555`（LAN 已不可用）。桥 coreVersion 0.17.1。main HEAD `bdfe4e8`。release-in-house 新包 lastUpdateTime 2026-09-11 23:28:35。本代理未改源码、未提交。

### 验收表（四项收口 + 抽查）

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 装机+冒烟+双包并存 | 新包装好、P00、双包并存 | release-in-house 23:28:35 Success；冒烟三关 PASS（pid=21046）；`pm list` 双包并存 | ✅ PASS |
| 2 | FGS 通知 | 运行→「N 主 · M 子」+ 持续 | 通知栏「0 个主代理 · 1 个子代理正在运行」（channel dsh_keepalive「后台保活」）；phone-logs `FGS 启动/更新前台服务 main=0 sub=1`；轮询 6 次（23:29:43→23:32:16，≥2.5min）持续 | ✅ PASS（上批 FAIL 已修复） |
| 3 | 技能面板全集 | 几十个技能 + 搜索 | 面板 21+ 去重技能（huawei-adb-install/fast-install、device-keepawake、lark-* 等）；搜 `huawei`→huawei-adb 系列 | ✅ PASS（上批仅 1 个已修复） |
| 4 | 嵌套子代理 | 🤖N=后代总数、▸展开、二/三级 | 顶部 🤖62=后代总数；一级平铺；「输入区操作条PRD与技术调研」带 ▸+🤖2，点 ▸ 展开二级「App 输入区重构+技能面板」「App 操作条 UI 实施」；三级无此数据；空态「暂无子代理」代码已实现（WorkspaceChrome.kt L254-260） | ✅ PASS（二级实测；三级=无此数据；空态=代码级） |
| 5 | 快速抽查 | 模型文案/上下文/发送圆按钮 | 「DeepSeek-V4-Pro · Max」模型入口；上下文 40% 环；终止+发送 162×162=48dp 圆按钮 | ✅ PASS |

### 发现的问题

- **无新增 FAIL**。上批「FGS 不启动」已修复（c7f14a6）并真机复验通过；「技能面板仅 1 个」已修复（桥 0.17.1）并复验通过。
- **观察（低优先级）**：嵌套子代理「暂无子代理」空态代码已实现，但在 `if (totalDescendants > 0)` 守卫下（🤖 按钮仅在有后代时渲染）该空态为防御性兜底、实际不可达；不影响功能。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 2 FGS 通知 | [12-fgs-notification.jpg](../screenshots/2026-09-10-batch-reverify/12-fgs-notification.jpg) |
| 3 技能面板全集 | [13-skill-full.jpg](../screenshots/2026-09-10-batch-reverify/13-skill-full.jpg) |
| 3 技能搜索 huawei | [14-skill-search-huawei.jpg](../screenshots/2026-09-10-batch-reverify/14-skill-search-huawei.jpg) |
| 4 子代理一级平铺 | [15-subagent-l1.jpg](../screenshots/2026-09-10-batch-reverify/15-subagent-l1.jpg) |
| 4 子代理二级展开 | [16-subagent-l2.jpg](../screenshots/2026-09-10-batch-reverify/16-subagent-l2.jpg) |
| 5 快速抽查（底部操作条） | [17-quickcheck-bottom.jpg](../screenshots/2026-09-10-batch-reverify/17-quickcheck-bottom.jpg) |

### 收尾确认

主题保持「跟随系统」（本批未改动）；keepawake 恢复（timeout 300000、stayon=0）；全程 crash buffer 无本包 FATAL ✅。

## 最终集成批次（BridgeClient 拆分后回归 · main 49f95f4）

> 执行代理：真机验收专项代理。通道：LAN `192.168.3.84:5555`（**中途掉线**，INJECT_EVENTS 权限异常，主对话已介入恢复设备与修复 keepawake 脚本）。桥 coreVersion 0.17.1。release-in-house 新包 lastUpdateTime 2026-09-12 00:58:07。本代理未改源码、未提交。

### 验收表

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 装机+冒烟+双包并存 | 新包装好、P00、双包并存 | release-in-house 00:58:07 `pm install` Success（未知来源权限已授，免密）；冒烟三关 PASS（pid=21422）；`pm list` 双包并存 | ✅ PASS |
| 2.1 | 连接/重连横幅 | 断开→横幅→自动重连→清除 | 横幅「检测到连接断开，正在自动重连…」；phone-logs `连接断开且有凭据→启动自动重连→第1次重连→hello到达→横幅清除`（两轮） | ✅ PASS |
| 2.2 | 会话导航+返回链两级下钻 | 下钻两级逐级返回 | ⏸️ 未实测（技能面板弹出占位，未完成下钻） | ⏸️ 未验 |
| 2.3 | 发送消息 ack | 发送→ack 归服务端 | ⏸️ 未实测（本批未发消息） | ⏸️ 未验 |
| 2.4 | 事件投影渲染 | 消息/工具结果/时间戳正常渲染 | 会话内 Agent 消息、✓结果、工具调用块、`今天 00:56` 时间戳均正常渲染 | ✅ PASS |
| 2.5 | 审批/提问/交付通知代码路径不崩 | 三路径不崩 | 本批未显式触发审批/提问；交付通知上批已触发、本批全程无本包 FATAL | ✅ PASS（交付上批已验；审批/提问未触发） |
| 2.6 | 设备注册 | register_device + /remote/connected | phone-logs `已发送 register_device`；`/remote/connected` 出现 deviceId cc4d6501（HBN-AL00） | ✅ PASS |
| 2.7 | FGS 通知 | 运行→「N 主 · M 子」 | 通知栏「0 个主代理 · 1 个子代理正在运行」（channel dsh_keepalive） | ✅ PASS |
| 3 | 技能面板高度 ~1/3 | 面板占底部 ~1/3、上方 2/3 会话可见 | 「技能」标题 y=1943/2844≈68% → 面板底部 ~1/3；截图 19 存证 | ✅ PASS（高度） |
| 3′ | 搜索框固定 + 列表滚动 | 搜索框固定、列表可滚 | ⏸️ 未实测（swipe 时 LAN 掉线 INJECT_EVENTS 权限失败） | ⏸️ 未验 |

### 未验项清单（诚实标注）

- **2.2 会话导航+返回链两级下钻**：未实测（导航前技能面板弹出占位）。
- **2.3 发送消息 ack**：本批未发送测试消息（非破坏原则 + 消息预算），ack 机制前几批已验。
- **2.5 审批/提问通知**：未显式触发（无稳定触发手段），交付通知上批已真机验证。
- **3′ 技能面板搜索框固定 + 列表滚动**：未实测（LAN 掉线导致 swipe 失败）；面板高度已验。

### 发现的问题

- **无功能级回归**：BridgeClient 拆分（174 行主类 + 5 扩展文件）后，连接/重连/注册/FGS/事件投影均无行为回归。
- **环境**：LAN 通道 `192.168.3.84:5555` 中途掉线（swipe 报 `Injecting to another application requires INJECT_EVENTS permission`），主对话已介入恢复；keepawake 备份遗留亦由主对话恢复。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 2.4 事件投影渲染（会话视图） | [18-mainflow-session.jpg](../screenshots/2026-09-10-batch-reverify/18-mainflow-session.jpg) |
| 3 技能面板高度（底部 1/3） | [19-skill-height.jpg](../screenshots/2026-09-10-batch-reverify/19-skill-height.jpg) |

> 其余条目（2.1/2.6/2.7）以 uiautomator dump 文本树 + phone-logs + `/remote/connected` + `dumpsys notification` 为证据，未逐项截图。

## USB 补验（最终批次 4 个未验项）

> 通道：USB `2NP0224806003991`（HBN-AL00，已授权）。keepawake 已 on→off 成功（备份已清、timeout 恢复 300000/stayon 0）。本代理未改源码、未提交。

### 验收表

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 会话导航+返回链两级下钻 | 主会话→一级→二级→逐级返回 | 主会话「自举开发」→ 一级子代理「输入区操作条PRD与技术调研」（🤖2）→ 二级「App 输入区重构+技能面板」→ 逐级返回两级回主会话 | ✅ PASS |
| 2 | 发送消息 ack | 立即上屏 + ack ok + 服务端一次 | 发送 `[bu-yan] do-not-process`（ASCII 代理【补验】请勿处理）：上屏 + 右上「你」+「刚刚」+ 输入框清空；phone-logs `ack ok msgId=m-session--1789168481695-0，消息已归服务端，删除持久化记录`（幂等去重=恰好一次） | ✅ PASS |
| 3 | 技能面板搜索框固定+列表滚动 | 搜索框固定、列表可滚、搜 huawei 命中 | 面板底部 ~1/3（y=1943）；列表滚动后搜索框仍固定在 y=2123；搜 `huawei` 命中 `huawei-adb-fast-install`（另有 huawei-adb-install） | ✅ PASS |
| 4 | 审批/提问显式触发 | 触发审批/提问 | 无稳定触发手段，未显式触发；代码级 + `NotificationControllerTest` 已验；结果交付通知上批已真机验证 | ⚠️ 诚实标注（未触发） |

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 1 二级子代理打开 | [20-subagent-l2-open.jpg](../screenshots/2026-09-10-batch-reverify/20-subagent-l2-open.jpg) |
| 2 发送 ack（消息上屏+你标签） | [21-send-ack.jpg](../screenshots/2026-09-10-batch-reverify/21-send-ack.jpg) |
| 3 技能面板搜索 huawei 命中 | [23-skill-search-huawei-hit.jpg](../screenshots/2026-09-10-batch-reverify/23-skill-search-huawei-hit.jpg) |

### 收尾确认

keepawake off 成功（status：timeout=300000、stayon=0、备份=无）✅；全程 crash buffer 无本包 FATAL ✅。

## 技能面板修复 + 审批/提问触发（用户反馈跟进）

> 用户反馈三点：① 技能面板高度 1/3 太矮，改成屏幕一半；② 输入「华为」搜索后清空 query 列表不恢复（bug）；③ 审批/提问可以用 ask_user_question 直接触发。执行代理：真机验收专项代理，直接改 Composer.kt + 复验。

### 修复

| 问题 | 根因 | 修复 | commit |
|------|------|------|--------|
| 面板高度 1/3 → 1/2 | `maxHeight = screenHeightDp / 3` | 改为 `/ 2`，且 `heightIn(max)` 改固定 `height()` | Composer.kt SkillPanel |
| 清空 query 列表不恢复 | LazyColumn 在 filtered 从「少量命中」变回「全量」时沿用旧 item 集合不刷新 | 列表子树加 `key(query)`，query 变化强制整体重建 | Composer.kt SkillPanel |

- lint：`detektP0` 全绿（99 kotlin files，0 违规）；`assembleRelease-in-house` BUILD SUCCESSFUL（DEX registers max=39，无告警/报错）。
- 复验：面板「技能」标题 y=1494（≈52.5% 屏高，半屏）；搜 `huawei` 命中 2 条 → 清空后列表恢复全量（首屏 arkui-scoring-workflow / ask-matt / batch-grill-me）。截图 24/25/26 存证。

### 审批/提问触发（已验证）

- 用 `ask_user_question` 直接触发：phone-logs `QUESTION 收到提问 rpc=86d4ab73 questions=1`；前台浏览当前会话时 `NOTIFY 抑制 QUESTION 原因=BROWSING_CURRENT`（防打扰硬规则，正确）；退后台后再触发 → 通知栏出现「需要你回答」（用户已人工确认通知出现）。
- **新发现（待路由）**：用户反馈「app 缩后台时通知栏出现测试内容，但 app 未自动拉回前台」，期望审批/提问到达时 app 自动回前台展示半屏强提醒。属 Android 12+ 后台启动限制 + 行为决策，本代理未擅自改，待主对话路由「通知/保活」域评估（full-screen intent 或 FGS 全屏通知）。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 面板半屏高度 | [24-skill-halfheight.jpg](../screenshots/2026-09-10-batch-reverify/24-skill-halfheight.jpg) |
| 清空 query 恢复全量（修复后） | [26-skill-clear-fixed.jpg](../screenshots/2026-09-10-batch-reverify/26-skill-clear-fixed.jpg) |
| 清空 query 未恢复（修复前证据） | [25-skill-clear-restored.jpg](../screenshots/2026-09-10-batch-reverify/25-skill-clear-restored.jpg) |

### 收尾确认

keepawake 已恢复（timeout=300000、stayon=0、备份=无）；crash buffer 无本包 FATAL ✅；代码未提交（本代理只改 Composer.kt，待主对话决定是否 commit）。

## 输入区操作条修复（模型入口 Max 恒显 + 终止/发送正圆 + 子会话只读）

> 用户反馈：①「推力强度 Max」被挤成三个小点；② 终止/发送按钮仍是椭圆；③ 模型选择只留主会话、子会话继承（readOnly）。执行代理直接改 Composer.kt + InputBarLogic.kt 并复验。

### 修复

| 问题 | 根因 | 修复 |
|------|------|------|
| 「· Max」被挤成 `...` | 模型名+强度拼成单串、`weight(1f)`+`Ellipsis` 整串截断，长模型名时强度被截 | `modelEntryParts` 拆出「模型名 + 强度」，入口渲染：模型名可截断 + 「· Max」固定恒显 |
| 终止/发送按钮椭圆 | 操作条 Row `height(48.dp).padding(bottom=6.dp)` 把 48dp 按钮压成 42dp 高椭圆 | 移除 Row 的 `padding(bottom=6.dp)`，按钮恢复 48×48 正圆 |
| 子会话模型入口可点开 | 入口无条件 `onClick` | 加 `isSubagent`（parentSessionId!=null）→ `ModelEntry(readOnly=true)`：`clickable(enabled=false)` + 隐藏「▾」 |

- lint：`detektP0` 全绿；`assembleRelease-in-house` BUILD SUCCESSFUL（DEX max=39 无告警）。冒烟 PASS；crash buffer 无 FATAL。
- 复验：主会话入口「DeepSeek-V4-Pro」+「· Max」分行恒显、按钮 162×162px=48dp 正圆；子会话入口「选择模型」只读（无 ▾、不可点）。截图 27/28 存证。

### 已知遗留（待主对话/通知域）

- 子会话模型入口显示「选择模型」占位（`state.models` 仅主会话有），未展示「继承自主会话的模型名」——服务端继承已生效（子代理用父会话模型），客户端展示父模型名需数据下沉，未在本轮做。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 主会话模型入口 Max 恒显 + 正圆按钮 | [27-modelentry-max-circle.jpg](../screenshots/2026-09-10-batch-reverify/27-modelentry-max-circle.jpg) |
| 子会话模型入口只读（无 ▾） | [28-subagent-model-readonly.jpg](../screenshots/2026-09-10-batch-reverify/28-subagent-model-readonly.jpg) |

### 收尾确认

keepawake 已恢复（timeout=300000、stayon=0、备份=无）；crash buffer 无本包 FATAL ✅；代码未提交。

## 审批/提问「自服务回答」验证（手机端自动作答，无需人工）

> 用户指出：提问/审批可以直接在手机端自服务回答（找到输入框/选项，自己点一下），无需人工介入。本代理验证了完整链路。

### 流程与结论

| 步骤 | 实测 |
|------|------|
| 触发 | `ask_user_question`（带 2 个选项）→ 服务端生成待回答提问 rpc=8c77c992 |
| 手机端弹窗 | 手机自动弹出「等待回答」半屏弹窗（QuestionSheet），含选项行（◉/○）+「提交」 |
| 自服务作答 | 后台 adb 脚本轮询 dump → 检测到弹窗 → 点选第一个选项「选项A」→ 点「提交」（无需人工） |
| 回传 | phone-logs：`提交提问答案 answers=1` → `已发送 answer_question` → `提问已解决 outcome=answered` |
| 结果 | `ask_user_question` 返回 `selected:["选项A"]`（手机端作答答案回传桌面端 Agent） |

**结论**：审批/提问可在手机端自服务回答，全程无需人工介入。上批「审批/提问未显式触发」的诚实标注在此闭环。

### 说明

- 提问通知（presence=BACKGROUND/OTHER_SESSION）也随弹窗同时触发，通知 + 弹窗双通道。
- 后台脚本 `/tmp/answer-question.sh`：轮询 dump → 点选首个选项 → 点「提交」，可复用于后续审批/提问回归。

## 上下文入口在子代理加载即展示（bridge 修复）

> 用户反馈：子代理会话加载时上下文入口（占用环）不出现，要客户端发消息后才出现，且不同子代理表现不一致。

### 根因（诊断）

- 上下文占用（`contextPressure`：contextWindow + usedTokens）是**事件驱动**：只在投影变更（模型跑过/发消息）后才写入投影。
- 订阅（subscribe）时 bridge 用 `contextUsageWireOf` 读投影：无 contextPressure → `percent=undefined` → 客户端 `ContextRing` 按「无数据不渲染」隐藏占用环。
- 因此「发消息后才出现」——发消息触发投影变更，才有 contextPressure。

### 修复（dsh-remote-control-bridge）

- `src/core.ts` 新增 `contextUsageWithDefault(sessionId, base)`：当 `base.percent === undefined` 时，用「继承模型」的静态 `contextWindow`（`llm.resolveModelInfo().context.contextWindow`）补 `contextWindow` + `percent=0`，让会话加载即展示占用环；拿不到容量则保持原样（不伪造）。
- 订阅（live 分支 + 冷会话分支）的 `context_usage` 推送改用 `contextUsageWithDefault(...).then(send)`。
- typecheck / build / lint:p0 全绿；bridge 热重载已生效（/remote/hot reloads 14→21，lastError=null）。

### 复验（真机）

| 子代理 | 状态 | 上下文环（加载即显） |
|--------|------|----------------------|
| 真机验收全流程 | 运行中 | 59% ✅ |
| 输入区操作条PRD与技术调研 | 运行中 | 67% ✅ |
| 架构优化与App.kt拆分 | 空闲（冷会话） | 25% ✅ |

截图：29-context-on-load.jpg。三例均在**未发消息**情况下加载即显示占用环。

## debug 包功能验收（08:37:43，冒烟 PASS）

> 设备 USB `2NP0224806003991`，debug 包 `com.daniel.dshremote.debug`。keepawake on→off 成功。

### 验收表

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 模型入口左右分区 | 左区=模型名→模型面板、右区=强度名→强度面板；选择即 set_model 回显 | 左区点「DeepSeek-V4-Flash」开「选择模型」面板；右区点「· Low」开「推理强度」面板；改强度→入口回显「· Max」、改模型→回显「DeepSeek-V4-Pro · High」 | ✅ PASS |
| 2 | 占位文案 | 空态「给智能体发消息」 | dump 确认占位「给智能体发消息」 | ✅ PASS |
| 3 | 按钮正圆 | 终止/发送 48dp 正圆 | 两按钮 bounds 162×162px=48dp 方形 | ✅ PASS |
| 4 | 技能面板 | 半屏、搜索固定、清空恢复、搜 huawei | 面板顶部 y=1494≈半屏；搜 huawei 命中 huawei-adb-install/fast-install；清空恢复全量 | ✅ PASS |
| 5 | C+ heads-up 通知 | 触发提问→高优横幅→点横幅回前台弹提问弹窗 | 通知出现（title「需要你回答」、specialType=floating_window_notification）；**但 FSI 未生效**（`topFullscreen=false`、`USE_FULL_SCREEN_INTENT` 未申请） | ⚠️ 部分（见下） |
| 6 | 上下文加载即展示 | 冷子代理占用环立即显示 | 「架构优化与App.kt拆分」冷子代理加载即显 25% 环 | ✅ PASS |
| 7 | 子代理模型继承 | 入口显示父会话「模型名 · 强度」只读 | 子代理入口「DeepSeek-V4-Pro · High」无 ▾、无点击区 | ✅ PASS |
| 8 | 嵌套子代理+返回链 | 下钻两级→逐级返回 | 主会话→「输入区操作条PRD与技术调研」(🤖3)→「App 模型入口左右分区可选」→逐级返回两级回主会话 | ✅ PASS |

### 项 5 未验/发现问题

- **C+ heads-up（full-screen intent）未生效**：通知以 `floating_window_notification` 形态出现（heads-up 横幅），但 `topFullscreen=false`、`USE_FULL_SCREEN_INTENT` 权限未声明/未申请——用户已指出「这个权限要单独申请」。属「自动回前台半屏强提醒」的关键一环，需 manifest 加 `USE_FULL_SCREEN_INTENT` + 华为悬浮窗 app-op/Android 14+ 运行时申请，待路由「通知/保活」域。
- 「点横幅回前台并自动弹提问弹窗」未自动化验证（通知栏 dump 未取到横幅文本节点）；通知点击直达（NotificationLaunch）代码已实现，上批已验提问弹窗自服务作答闭环。

### 截图清单

| 验收项 | 截图 |
|--------|------|
| 1/2/3 输入区（占位+左右分区+正圆按钮） | [30-debug-main-input.jpg](../screenshots/2026-09-10-batch-reverify/30-debug-main-input.jpg) |
| 1 模型面板（左区） | [31-model-left-panel.jpg](../screenshots/2026-09-10-batch-reverify/31-model-left-panel.jpg) |
| 1 强度面板（右区） | [32-strength-right-panel.jpg](../screenshots/2026-09-10-batch-reverify/32-strength-right-panel.jpg) |
| 6 冷子代理上下文环 | [33-cold-subagent-context.jpg](../screenshots/2026-09-10-batch-reverify/33-cold-subagent-context.jpg) |
| 8 二级子代理 | [34-subagent-l2.jpg](../screenshots/2026-09-10-batch-reverify/34-subagent-l2.jpg) |

### 收尾确认

keepawake off 成功（timeout=300000、stayon=0、备份=无）；crash buffer 无本包 FATAL ✅。

## 模型/强度切换「服务端是否真正生效」硬证据核验

> 用户实测怀疑「手机切模型/强度 → 发新消息 → 服务端仍没变化」。自动化核验，证据源：桥日志（`/remote/logs`）+ 会话日志（`~/.dsh/sessions/.../session.jsonl.zstd` 的 agent request header）+ 桥投影（models_update current）。

### 数据流与根因背景

- `set_model` → 桥 `case 'set_model'` 解析后 `installSelectionFor(a).current = selected`（写桥侧可变 ref）。
- 桥 `installModelSelectionPrepend`（`{ prepend: true }`）在 `agent/request` 钩子里用 selected 覆盖 provider/model/reasoningEffort——**这是修复「apiproxy 链头覆盖桥 ref、set_model 被覆盖不切换」的根因补丁**（bridge 0.17.2 已含）。

### 结论表

| 切换动作 | 入口回显 | 新一轮实际用值（会话日志 request header） | Web 显示值（桥投影 current） |
|---------|---------|------------------------------------------|------------------------------|
| High→Max | 「· Max」✅ | `reasoningEffort:"max"` ✅（切换后新一轮实用了 max） | deepseek-v4-pro + max ✅（models_update 广播） |
| Max→Off | 「· Off」✅ | 未完成新一轮（重装打断）⏸️ | deepseek-v4-pro + off ✅（桥日志 effort=off 已下发） |

### 判定

- **「实际生效」成立**：桥日志逐条记录 `切换模型 ... effort=max / effort=off`（set_model 被处理并写 ref）；会话日志证实 High→Max 之后的新一轮 request header 为 `reasoningEffort:"max"`（agent 实际用了新值）。
- **「仅显示滞后」不成立**：不是只有客户端入口变，服务端 request header 同步变。
- Max→Off 的「新一轮实际用值」因中途重装打断未完成 round 核验，但切换本身已确认下发（桥日志 effort=off）。

### 证据链

- 桥日志 `/remote/logs`（tag=MODEL）：`1789174775677 effort=high`（基线）→ `1789174824970 effort=max`（High→Max）→ `1789174978097 effort=off`（Max→Off）。
- 会话日志 session-ab787050 的 `session.jsonl.zstd`：最后一条 `"reasoningEffort":"max"`。
- 代码 `src/core.ts` `installModelSelectionPrepend`（agent/request prepend 覆盖 provider/model/reasoningEffort）。

### 说明（诚实标注）

- Web 界面「选择器显示」未直接读 Web DOM（无浏览器）；以桥投影 current（models_update 广播给 Web）为准——该值已随 set_model 同步更新。
- 测试消息 `[verify-model] reply OK only`（因输入框残留草稿拼接成 65 字符，但 ack ok、已触发新一轮，不影响核验）。

## #57 技能点选（85321e4）装机 + 验收

> debug 包 `com.daniel.dshremote.debug`（13:49 构建，含 85321e4 技能点选）。USB `2NP0224806003991`。桥 coreVersion 0.17.5。

### 验收表

| 验收项 | 预期 | 实测 | 结论 |
|--------|------|------|------|
| 装机+冒烟 | 装 debug 包、P00 | 13:51:42 装机 Success；冒烟三关 PASS（pid=19812） | ✅ PASS |
| 技能面板搜索点选 | 搜 `code-lint` → 点选 | 面板打开（01）；搜 code-lint 命中（02）；点选后输入框预填 `/code-lint `（03） | ✅ PASS |
| 预填回显 | 输入框 `/code-lint ` + 焦点回输入框 | EditText 预填 `/code-lint `、focused=true | ✅ PASS（键盘未自动弹起，见下） |
| 发送送达 | 消息含 `/code-lint` 送达 | `发送点击 输入长度=35` → `发送消息 首20字="/code-lint /code-lin"` → `ack ok msgId=…` | ✅ PASS |
| 技能 pre-step 注入 | 服务端识别 `/code-lint` 注入技能 | 桥日志 `斜杠 token 命中 user-invocable 技能 name=/code-lint …（放行 followup，pre-step 注入）` | ✅ PASS |

### 发现的小问题（建议修）

1. **预填后光标在位置 0（开头）而非末尾**：点选技能预填 `/code-lint ` 后，光标落在文本开头，导致补输入提示词会插到前面（实测首次补输入得到 ` run P0/code-lint `，需手动 KEYCODE_MOVE_END 才能正确追加到末尾）。预期：`onInputChange("/$name ")` 后应把选区/光标置到文本末尾（TextRange(length)）。
2. **键盘未自动弹起**：`onInputChange` 后虽 `pendingSkillFocus=true`（焦点回输入框），但实测 `mInputShown=false`（键盘未弹起）。与「焦点回输入框键盘弹起」预期不符。

### 截图清单（docs/screenshots/2026-09-12-skill-pick/）

| 步骤 | 截图 |
|------|------|
| 技能面板 | [01-skill-panel.jpg](../screenshots/2026-09-12-skill-pick/01-skill-panel.jpg) |
| 搜索 code-lint | [02-search-code-lint.jpg](../screenshots/2026-09-12-skill-pick/02-search-code-lint.jpg) |
| 点选后预填 | [03-prefilled.jpg](../screenshots/2026-09-12-skill-pick/03-prefilled.jpg) |
| 追加提示词 | [04-appended.jpg](../screenshots/2026-09-12-skill-pick/04-appended.jpg) |

### 收尾

keepawake off 成功（timeout=300000、stayon=0、备份=无）；crash buffer 无 FATAL ✅。

## #57 技能点选「两个小问题」修复 + 真机复验（c4b2bd7 / bd74cf2）

> 承接上一节发现的 2 个小问题。修复：光标置末尾 `c4b2bd7`（TextFieldValue + selection=TextRange(length)）；
> 键盘自动弹起 `755f3a3`（InputMethodManager）+ `bd74cf2`（时序 tweak：退场释放焦点后再 requestFocus）。
> 复验设备 LAN `192.168.3.84:5555`（华为 HBN-AL00 / Android 12），桥 coreVersion 0.17.5。

### 验收表

| 验收项 | 预期 | 实测 | 结论 |
|--------|------|------|------|
| 装机+冒烟 | 装新 debug 包、P00 | 14:43:40 装机 Success；冒烟 PASS（pid=11589 存活、crash 0） | ✅ PASS |
| 光标置末尾 | 点选后补输入追加到 `/code-lint ` 末尾 | 追加 `run P0` 后 `text='/code-lint run P0'`（末尾追加，非前置） | ✅ PASS |
| 键盘自动弹起 | 点选后焦点回输入框 + 键盘弹起 | `mShowRequested=true mShowForced=true mInputShown=true`，`mServedView=AndroidComposeView` | ✅ PASS |
| 服务正确 view | showSoftInput 服务 Compose 输入框（非 DecorView） | `mServedInputConnectionWrapper=…NullableInputConnectionWrapperApi25… mServedView=AndroidComposeView` | ✅ PASS |

### 根因（两问题）

- **光标在开头**：`OutlinedTextField` 的 String 重载会重置 selection 到 0；改 `TextFieldValue` + `selection = TextRange(length)`。
- **键盘不弹**：`requestFocus()` 在 `showSkillPanel=false` 同一帧执行，早于 `ModalBottomSheet` 退场动画（约 300ms）
  结束；退场结束时的焦点回收把刚建立的焦点冲掉 → `activity.currentFocus==null` → `showSoftInput` 回退 `decorView`
  （错误 view），键盘不弹。修法：`delay(380)` 等退场 → `requestFocus()` → `delay(160)` 等 window focus 落定 → `showSoftInput`。

### 截图清单（docs/screenshots/2026-09-12-skill-pick/）

| 步骤 | 截图 |
|------|------|
| 点选后键盘弹起 + 输入框预填并追加（光标末尾） | [06-keyboard-shown-cursor-end.png](../screenshots/2026-09-12-skill-pick/06-keyboard-shown-cursor-end.png) |

### 取证路径

- 键盘状态：`adb -s 192.168.3.84:5555 shell dumpsys input_method`（`mInputShown=true`、`mServedView=AndroidComposeView`）。
- 输入框内容：`uiautomator dump` → `EditText '/code-lint run P0'`。
- 疑难 bug 文档：[docs/bugs/2026-09-12-skill-pick-keyboard-not-shown.md](../bugs/2026-09-12-skill-pick-keyboard-not-shown.md)。

### 收尾

keepawake off 已恢复（LAN `192.168.3.84:5555`：timeout=300000、stayon=0）；crash buffer 无本包 FATAL ✅。
