# [BUG] 重连成功后「正在自动重连」横幅滞留约 1 分钟才消失

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已定位；修复随「提示条统一」重构实施中（worktree `fix/connection-notice`，合并后回填 commit 与 diff） |
| 仓库/模块 | App（`BridgeClient.kt` 重连状态机 / `App.kt` 横幅渲染） |
| 发现方式 | 真机验收（2026-09-03，用户拔插 USB 现场） |
| 日期 | 2026-09-03 |
| 相关 commit | （待回填） |
| 关联文档 | `docs/connection-issues.md`、`docs/changelog/2026-08-31.md`（startReconnect 重断言修复） |

## 背景

- 重连横幅（「检测到连接断开，正在自动重连…」+ Loading）由 `BridgeClient._reconnecting` 状态流驱动，渲染于 `App.kt:551`。
- 设计意图：hello 是「连接建立且会话同步完成」的权威信号，**唯一清除点**是 `handle(Hello)`（`BridgeClient.kt:863-867`：`if (_reconnecting.value) { _reconnecting.value = false; … "hello 到达，重连完成，横幅清除" }`）。hello 没到 = 没真正同步，横幅不撤。
- 历史补丁：08-31 修过另一个 bug（`startReconnect` 成功路径误清 `reconnecting` 标志，导致重连循环存活期再次断开时标志为 false → 路由落入设备页/Connecting 整屏）。当时的修法之一是在 **`startReconnect` 入口无条件重断言 `_reconnecting = true`**（`BridgeClient.kt:289-291`）。

## 现象

1. 用户拔掉 USB → App 断连 → 横幅正确出现（此时为真实断连，横幅显示无误）。
2. 用户重新插上 USB，等待很久横幅仍在。
3. 服务端日志显示「0 个客户端」、手机 logcat 显示第 18 次重试三个候选全部失败——**插上 USB 后连接确实没恢复**（`adb reverse --list` 为空：USB 重连会清掉 reverse 映射；LAN 192.168.3.82 是过期地址；Tailscale 超时）。所以「插上 USB 后横幅不消失」本身是正确行为。
4. 我在 Mac 侧重新执行 `adb reverse tcp:3080 tcp:3080` 后：手机 00:32:25 重连成功（服务端 `connectedAt=1788366745995`，此后无第二次连接）、00:32:26 收到 hello（sessions=52、对账正常）。**但 00:33 的 UI dump 中横幅仍存在**（`横幅计数=1`），约 1 分钟后再次 dump 才消失（`横幅计数=0`）——重连成功后横幅滞留。

## 排查过程

- 先验证「连接是否真实健康」：`/remote/connected` 有设备且 `connectedAt` 未变 → 连接持续存活、无二次连接 → 不是"连上又断"。
- 读代码确认清除路径唯一（hello），且 hello 确实已到达（logcat 有 `收到 hello: sessions=52`）。
- 试图取 00:32:26~00:33:30 窗口内 `dsh-conn/RECONNECT` 日志确认「hello 到达，重连完成，横幅清除」是否打出——**失败**：华为 logcat 主缓冲 1~2 分钟被系统刷屏滚动覆盖（FrameRate/AGPService），关键窗口日志已丢失。这是本次排查的取证受限点。
- 推断（无直接日志证据）：30s 重试定时器在成功连接后仍触发了一轮 `startReconnect`，入口重断言 `_reconnecting=true`；该轮 open() 未产生新连接（服务端 connectedAt 未变）→ 无新 hello → 标志滞留，直到后续某个路径再次置 false。

## 根因分析

**表面原因**：重试循环的 30s 定时器在「连接已成功、hello 已清标志」之后仍可能再触发 `startReconnect`，其入口无条件 `_reconnecting.value = true` 重断言，此后没有新连接就没有新 hello 来清它 → 横幅卡住。

**深层根因（洞察）**：`reconnecting` 标志位被**两条异步时间线共同写入**——
- 时间线 A（重试循环）：定时器驱动，入口**无条件置 true**；
- 时间线 B（连接生命周期）：open→hello，**唯一置 false**。

两条时间线之间没有互斥、没有「成功后循环必须退出」的保证，标志位也不是从真实连接状态**推导**（derived）出来的，而是靠两边事件的对称置/清。这种模型的固有缺陷是：**任何一方漏发一次事件，标志就永久卡在错误状态**。更值得注意的是：08-31 的「入口重断言」补丁正是为修上一个 bug（成功路径误清标志）打的，而它恰好构成了本次滞留的直接源头——同一个标志位在两个 bug 之间摇摆，说明标志的「归属模型」有缺陷，靠打补丁只会振荡，必须收敛为一个单一写入者/推导式的状态机（这正是「提示条统一」重构要做的 `ConnectionNotice`：连接状态推导出唯一展示态，重连成功即 Hidden）。

**可推广教训**：凡是「一个布尔状态被 >1 个异步源写入」的设计都要警惕；正确姿势是「单一写入者 + 其余方只读」，或「从权威状态推导（derivedState）」，并在成功路径上显式取消/退出所有定时器与循环。

## 解法

方案（随 `fix/connection-notice` 落地）：

1. 统一 `ConnectionNotice` 状态机（Hidden / Reconnecting(attempt) / Error），单一写入者，UI 只读。
2. `open()` 成功（或 hello 到达）时**取消已调度的下一次重试定时器**，重试循环成功路径**必定退出**。
3. 重断言 reconnecting 只发生在**真实发起新连接尝试**时，且与「hello 已到达」互斥（同 tick 内 hello 优先）。
4. 新增单测：成功连接后无新连接也不再置位重连状态。

核心 Code Diff 与提交哈希：**（待合并后回填）**

## 后续改进计划

- [ ] 合并 `fix/connection-notice` 后回填 commit 哈希与核心 diff。
- [ ] 华为 logcat 缓冲滚动导致竞态取证困难 → 评估 ConnLog 落盘（本地文件环形）或缩短上传间隔，为下次取证留证据。
- [ ] 同类排查清单：任何「拔插 USB 后连不上」先查 `adb reverse --list`（USB 重连必清 reverse，这是环境常识不是 bug）；任何「横幅/标志卡住」先查「谁在写这个标志、谁在清、两条时间线是否互斥」。
