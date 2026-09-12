# C+ 收尾：横幅形态确认 + 点击直达链路验证（2026-09-12）

> 范围：用户拍板 C+（不含 full-screen intent）。本批只做「横幅形态确认 + 点击直达链路验证」；发现问题即修。
> 执行：通知/保活域子代理。真机 HBN-AL00 / Android 12，debug 包 `com.daniel.dshremote.debug`。

## 验收表

| # | 验收项 | 预期 | 实测 | 结论 |
|---|--------|------|------|------|
| 1 | 无 FSI | 不加 USE_FULL_SCREEN_INTENT / setFullScreenIntent | `grep` 代码无 FSI 相关（无 USE_FULL_SCREEN_INTENT 权限/调用） | ✅ PASS |
| 2 | 审批/提问 heads-up 渠道 | `dsh_approval` 渠道 IMPORTANCE_HIGH(4) + 横幅 | `dumpsys notification`：debug 包 `dsh_approval` `mImportance=4`（HIGH）；审批通知 `specialType=floating_window_notification` | ✅ PASS |
| 3 | 结果交付低打扰（无横幅） | `dsh_delivery`(DEFAULT) 不 heads-up | **发现缺陷**：交付通知 `specialType=floating_window_notification`（误走高优，根因 `0919343` 无条件 `setPriority(HIGH)`）→ 已修复 `e3fff56`（收敛为仅审批/提问 HIGH） | ⚠️→✅ 已修（待复验） |
| 4 | 点击直达（点横幅→回前台+打开会话+弹窗） | requestedTag 链路 | 代码已实现（`0919343`：PendingIntent 带 `EXTRA_NOTIFY_TAG` → MainActivity → `NotificationLaunch.requestedTag` → App.kt 优先挂载对应审批/提问）+ 单测 `parseNotificationSheetTarget` 3 例；**设备级 tap 验证未完成**（ask_user_question 触发不可靠：agent 未调用工具；approval-test 端点触发后 curl 阻塞等待裁决且通知走 release 包） | ⚠️ 代码级+单测，设备级未验 |

## 发现的问题

1. **结果交付通知误走高优 heads-up**（已修 `e3fff56`）：`0919343` 给 Notification.Builder 无条件 `setPriority(PRIORITY_HIGH)`，导致 delivery 也 `floating_window_notification`，违反「结果交付低打扰」。修复：仅审批/提问 HIGH。详见 `docs/bugs/2026-09-12-delivery-headsup-priority.md`。

## 未实测项（诚实标注）

- **点击直达设备级 tap 验证**：`ask_user_question` 触发不可靠（发送消息后 agent 未调用 ask_user_question 工具，无 question_request 到手机）；`/remote/debug/approval-test` 端点触发审批后 curl 阻塞（等待手机裁决），且审批广播走了 `com.daniel.dshremote`（release）包而非 debug 包。**建议**：桥补 `/remote/debug/question-test` 端点（对称 approval-test）做可靠触发；或在 debug 包已连桥、release 包断开的前提下用 approval-test 验证点击直达（审批与提问共用 requestedTag 链）。

## 截图清单

| 验收项 | 截图 |
|--------|------|
| 通知栏（审批通知） | [01-approval-shade-headsup.jpg](../screenshots/2026-09-12-headsup-clickthrough/01-approval-shade-headsup.jpg)（注：截图时刻审批通知已因 approval-test 超时被自动裁决，通知栏仅剩 USB 调试；审批 heads-up 形态以 dumpsys 文本为准：`specialType=floating_window_notification` + `mImportance=4`） |

## 取证路径（文本证据）

- 渠道 importance：`adb shell dumpsys notification --noredact | grep "mId='dsh_"` → debug 包 `dsh_approval mImportance=4`、`dsh_delivery mImportance=3`、`dsh_delivery_silent mImportance=2`、`dsh_keepalive mImportance=2`。
- 通知形态：`adb shell dumpsys notification --noredact | grep -A10 "需要你及时响应"` → `specialType=floating_window_notification`、`topFullscreen=false`（无 FSI）。
