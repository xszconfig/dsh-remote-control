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
