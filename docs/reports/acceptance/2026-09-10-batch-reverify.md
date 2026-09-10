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
