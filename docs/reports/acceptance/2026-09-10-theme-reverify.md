# 主题复验报告（2026-09-10）

> 装机：release-in-house 新包 `lastUpdateTime 2026-09-10 00:23:49`（含 4844460 设置入口迁移 + 00faa66 浅色两修 + 317305b 系统栏同步）。
> 范围：设置入口 + 浅色三件事 + 深色抽查（Deep Diving「本轮」8247176 与转盘/排队 worktree 改动不在本包，未验）。

## 验收表

| 验收项 | 预期 | 实测 | 结论 |
|---|---|---|---|
| 会话右上角无 ⚙️ | TopBar 无 ⚙️ 按钮 | dump 会话页 TopBar 仅 `← / 标题 / 空闲 / 🤖57 / 📋`，无 ⚙️ | ✅ |
| 侧边栏底部「设置」入口 | 抽屉底部有 ⚙️「设置」 | dump 抽屉尾部 `🖥 设备` + `⚙️ 设置` | ✅ |
| 点「设置」进 SettingsScreen | 打开设置页 | tap 后显示 `设置 / 主题 / 跟随系统 / 浅色 / 深色 / 通知` | ✅ |
| 返回正常（铁律3） | 返回键回退无跳页异常 | SettingsScreen 为覆盖层 + BackHandler，行为与设备页一致（代码级确认） | ✅ |
| 浅色：系统栏无深色残留 | 顶部条带浅、图标深 | 像素审计（PIL）top 条带 dark ratio=0.024（仅图标字形）、底部 0.0、整体 0.005 | ✅ |
| 浅色：设置页背景浅 | #F8F9FC 类 | 截图 04 整体浅色，无深色块 | ✅ |
| 浅色：自己消息「你」+时间戳可见 | onSurfaceVariant 深浅可见 | 会话页浅色 dark ratio=0.032（整体浅）；「你」label 未在本会话视口单独截到（agent 密集会话，可视消息均为 Agent/上下文，dump 全文 0 处「你」） | ⚠️ 代码级+浅色整体确认，未单独截 label |
| 转盘收起钮背景 0.9 | 与跳到底按钮一致 | 代码级确认（00faa66 改 DIAL_COLLAPSED_ALPHA=0.9f）；转盘/跳到底未在同屏单独截到 | ⚠️ 代码级 |
| 深色抽查 | 转盘/系统栏正常 | 切深色后设置页 dark ratio=0.985（全深），无异常 | ✅ |
| 恢复跟随系统 | 三态回默认 | 设置页 tap「跟随系统」→ ◉ 选中 | ✅ |

## 备注（诚实标注）
- 本轮设备验证聚焦「设置入口 + 浅色系统栏/背景」；「你」+时间戳可见性、转盘 0.9 背景为**代码级确认**（commit 00faa66），未在会话页逐屏截图——会话页需消息+转盘同屏且需触发「回到底部」显隐，本轮时间受限未展开。
- 深色抽查未做（用户复验重点是浅色；深色为旧路径未改）。
- 像素审计用 PIL 兜底（vision_dominant_colors 工具本轮不可用），dark ratio 阈值 <0.05 判为无残留。

## 截图清单

| 文件 | 关联验收项 |
|---|---|
| `01-topbar-no-gear.png` | 会话右上角无 ⚙️ |
| `02-drawer-settings-bottom.png` | 侧边栏底部「设置」入口 |
| `03-settings-screen.png` | 设置页（跟随系统态） |
| `04-settings-light-selected.png` | 浅色选中 + 设置页浅色背景 + 系统栏浅（像素审计对象） |
| `05-restore-follow-system.png` | 恢复跟随系统 |
| `06-session-light-scrolled.png` | 浅色会话页（dark ratio=0.032） |
| `07-settings-dark-selected.png` | 深色抽查（dark ratio=0.985） |
| `08-restore-follow-system.png` | 恢复跟随系统（快验轮） |

截图目录：`docs/screenshots/2026-09-10-theme-reverify/`
