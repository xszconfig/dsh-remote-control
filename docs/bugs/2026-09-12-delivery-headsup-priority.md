# [BUG] 结果交付通知误走高优 heads-up（setPriority 无条件应用）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`Platform.android.kt` `NotificationPoster.post`） |
| 发现方式 | 真机目验（C+ 收尾验收，dumpsys notification） |
| 日期 | 2026-09-12 |
| 相关 commit | `e3fff56` |
| 关联文档 | `docs/decisions/审批提问自动回前台方案.md`、`docs/prd/主动通知与审批提醒PRD.md` |

## 背景

- C+ 方案（`0919343`）：审批/提问走高优 heads-up 横幅（声音+振动），**结果交付保持低打扰（无横幅）**。
- 实现时给 `Notification.Builder` 无条件 `.setPriority(Notification.PRIORITY_HIGH)`（本意是审批/提问的 heads-up）。

## 现象

真机 `dumpsys notification --noredact`：结果交付通知（`channel=dsh_delivery`，`mImportance=3`=DEFAULT）的
`specialType=floating_window_notification`（heads-up 形态），违反「结果交付低打扰（无横幅）」要求。

## 排查过程

1. 观察到 `dsh_delivery`（DEFAULT）通知出现 `specialType=floating_window_notification`，与「DEFAULT 不 heads-up」预期不符。
2. 对照 `dsh_approval`（HIGH）通知同样为 `floating_window_notification`（正确）。
3. 定位到 `0919343` 引入的无条件 `.setPriority(PRIORITY_HIGH)`：华为 EMUI 对 `setPriority` 的 heads-up 判定可能不完全遵循「API26+ 渠道 importance 覆盖 priority」的规则，导致 DEFAULT 渠道 + HIGH priority 仍被判 floating。

## 根因分析

- 表面原因：`setPriority(PRIORITY_HIGH)` 无条件作用于所有通知。
- 深层根因：**「优先级」是分类属性，不该无条件铺平**——heads-up 是审批/提问的专属语义（咽喉、需打断），
  结果交付是「尽力提醒、不打扰」；把两者统一按 HIGH 处理，丢失了 PRD 里「审批高优 / 交付低打扰」的层级区分。
  可推广教训：**通知的 priority/渠道选择必须由 kind（语义分类）驱动，不能用一个全局默认铺平所有类型**。

## 解法

- 收敛 `setPriority(PRIORITY_HIGH)`：仅 `APPROVAL`/`QUESTION` 走高优；`DELIVERY` 不请求高优（走渠道 DEFAULT/LOW）。
- 核心 Diff：
  ```kotlin
  // before：无条件 HIGH
  .setPriority(Notification.PRIORITY_HIGH)
  // after：仅审批/提问 HIGH
  .apply {
      if (spec.kind == NotificationKind.APPROVAL || spec.kind == NotificationKind.QUESTION) {
          setPriority(Notification.PRIORITY_HIGH)
      }
  }
  ```
- 提交哈希：`e3fff56`（main）
- 回归：`testDebugUnitTest` + `detektP0`（99 文件）+ `assembleDebug` + DEX 全绿。

## 后续改进计划

- 设备复验（下一装机批次）：结果交付通知 `specialType` 应为空（非 floating）、无横幅；
  审批/提问通知 `specialType=floating_window_notification` + `mImportance=4`（HIGH）有横幅。
- 点击直达（requestedTag）设备级 tap 验证：ask_user_question 触发不可靠（agent 未必调用工具），
  建议桥补 `/remote/debug/question-test` 端点（对称 approval-test），做可靠触发。
