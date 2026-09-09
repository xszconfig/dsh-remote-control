# 主题与深色模式（白天/黑夜 + 跟随系统）PRD

> 阶段：实现需求流水线 ①-④（本阶段仅文档，不改代码、不 commit）。
> 编码负责人：App.kt 结构子代理（本 PRD 作者），等主对话放行后进入编码阶段。
> 相关规范：`docs/ui-contrast-guidelines.md`、`docs/ui-mobile-first.md`。

---

## 一、需求背景

### 1.1 痛点 / 现状

- **现状：App 是「深色专享」**——`Theme.kt` 只定义了一个 `darkColorScheme`（`DshColors`），`DshTheme` 无条件套用深色，**没有浅色主题、没有跟随系统**。用户白天户外/亮环境使用体验差（深底反光、对比不足）。
- 用户诉求：① 白天/黑夜两套；② 跟随系统自动切换；③ **硬约束：先做颜色盘（token 体系），再映射到两套色**，保证 UI 无缝衔接；④ 所有 UI 展示正确。

### 1.2 目标

1. 建立**语义颜色 token 体系**（单一事实源），Material 槽位 + 自定义语义色统一入盘。
2. 提供 `lightColorScheme` + `darkColorScheme` 两套映射，替换/收敛现有硬编码色。
3. 主题三态：跟随系统 / 浅色 / 深色，手动覆盖优先，持久化选择。
4. 手机 + 平板两设备、三主题态逐屏回归。

### 1.3 边界（做什么 / 不做什么）

**做：**
- 颜色盘 token 化 + 两套 ColorScheme + 三态切换 + 持久化。
- 全量替换硬编码颜色 → 语义 token。
- 深色/浅色两套下的 UI 细节逐项核对。

**不做：**
- 不用 dynamicColor（Material You 动态取色），理由见四。
- 不改 bridge、不改协议、不改任何业务逻辑。
- 不引入第三方主题框架（用 Compose 原生 `MaterialTheme` + 自定义 `CompositionLocal`）。

---

## 二、规则细化（核心）

### 2.0 术语与前提

- **Material 槽位**：Material3 `ColorScheme` 的 20 个标准槽（primary/onPrimary/…/outline/outlineVariant），现已有 `darkColorScheme`。
- **自定义语义 token**：现有顶层 `val` 常量（`StatusGreen`/`AccentBlue`/`DeepSeekBlue` 等）+ 散落 `Color(0x…)` 字面量，需收敛为 token。
- **主题三态**：`FollowSystem` / `Light` / `Dark`。
- **硬约束实现顺序**：先建颜色盘 → 再映射 → 再切换（禁止边改边换色）。

### 2.1 主题模式三态与切换入口

**三态：**
| 模式 | 行为 | 持久化值 |
|---|---|---|
| 跟随系统 | `isSystemInDarkTheme()` 决定深浅 | `"system"` |
| 浅色 | 恒浅色 | `"light"` |
| 深色 | 恒深色（等于现状） | `"dark"` |

**切换入口（最终，用户拍板）**：**侧边栏（导航抽屉）底部新增「设置」入口行（⚙️ + 「设置」），点击进入 `SettingsScreen`**（主题三态切换 + 通知说明占位）。不放在会话 TopBar 右上角（用户后续明确否决 TopBar 位置）。
- 平板对应：左栏（项目+会话合并侧栏）底部同样放「设置」入口（无抽屉场景）。
- 返回导航：设置页为普通页面覆盖层，返回键回退到进入前页面（与设备页/日志页一致，铁律 3）。

### 2.2 颜色盘盘点（现状全量清单）

#### 2.2.1 Material 槽位（已 token 化，仅缺浅色）
现状 `Theme.kt` 的 `DshColors = darkColorScheme(...)` 已覆盖全部 20 槽。缺：`lightColorScheme` 一套对应值。

#### 2.2.2 自定义语义 token（需新建，现状散落）

| Token（拟） | 用途 | 现状值（dark） | 建议 light 值 | 主题相关 |
|---|---|---|---|---|
| `StatusGreen` | 运行中/在线状态点、成功 | `0xFF34D399` | `0xFF10B981` | ✅ 需区分 |
| `StatusGray` | 空闲/离线状态点 | `0xFF8A93A6` | `0xFF6B7280` | ✅ |
| `StatusAmber` | 等待/排队/警示 | `0xFFF2C14E` | `0xFFB45309` | ✅ |
| `StatusOrange` | 设备已更换 | `0xFFFFA94D` | `0xFFEA580C` | ✅ |
| `AccentBlue` | 强调小字/链接 | `0xFF9DB8FF` | `0xFF2563EB` | ✅ |
| `DeepSeekBlue` | 品牌蓝（Deep Diving 条等） | `0xFF4D6BFE` | `0xFF3057D5` | ✅ |
| `MarkdownCodeBg` | Markdown 代码块背景 | `0xFF14181F` | `0xFFF6F8FA` | ✅ |
| `MarkdownCodeFg` | 代码块前景 | `0xFFDCE4EF` | `0xFF24292E` | ✅ |
| `DiffBg`/`DiffContextFg`/`DiffDelBg/Fg`/`DiffAddBg/Fg` | 文件 diff 配色（6 个） | 见 DiffView.kt:64-69 | 待定（浅色下红绿要加深） | ✅ |
| `LogDebug`/`LogInfo`/`LogWarn`/`LogError` | 日志页级别色（`levelColor`/`levelColorOf`） | 0xFF8B93A7/6E9BFF/F2C14E/FF6B6B | 待定（浅色下加深） | ✅ |
| `ApproveCmdGreen` | 审批命令文本（`Color(0xFFB8E6B8)`） | 0xFFB8E6B8 | 待定 | ✅ |

**散落 `Color.Black/White.copy(alpha=…)`**（App.kt:2930/3025/3100、DiffView.kt:115）：scrim/命令背景/分隔线——多数可跨主题复用（Black 半透明 scrim、White 半透明分隔线），需逐项核对，少量需 token 化。

**盘点规模结论**：
- Material 槽位 20（已 token，缺浅色）。
- 自定义语义色 **12 个顶层常量 + ~13 个内联字面量 + 4 个 Black/White 派生** ≈ **29 处**需收敛/映射。
- 使用频次（影响面）：`AccentBlue` 20 处、`StatusAmber` 18、`StatusGreen` 11、`DeepSeekBlue` 7、`StatusGray` 6、`StatusOrange` 3，其余 1-2 处。

#### 2.2.3 映射表（token → 浅色 → 深色，完整版）
见附录 A「颜色 token 映射总表」。

### 2.3 深色适配 UI 细节风险清单（逐项核对）

| UI 块 | 风险 | 核对要点 |
|---|---|---|
| 消息气泡 | 用户气泡 primary 底 + 助手气泡 surfaceVariant 底 | 两主题下 onPrimary/onSurface 对比度 ≥4.5:1 |
| Markdown 代码块 | 现 `MarkdownCodeBg/Fg` 硬编码深色 | 浅色下需换浅底深字，token 化 |
| Markdown 表格 | `ScrollableMarkdownTable` 依赖 `content`/`dividerColor`（由 markdownColor 传 content） | 两主题下表格线/单元格色 |
| 转盘（MessageDial） | 扇面 `DIAL_FAN_ALPHA` 半透明、圆钮 `surfaceVariant`、红基准线 `error` | 浅色下刻度/扇面可读性 |
| 横幅（重连/错误/重启） | tertiaryContainer/errorContainer/surface 底 | 两主题容器色+前景对比 |
| 弹窗（审批/提问/中断） | `Color.Black.copy(alpha=0.72)` scrim + 命令 `Color(0xFFB8E6B8)` | scrim 跨主题复用；命令绿浅色下需加深 |
| 日志页级别色 | `levelColor`/`levelColorOf` 硬编码 | token 化，浅色下 INFO/WARN 加深 |
| Diff 卡片 | 6 个 diff 色硬编码深色 | token 化，浅色下红绿加深 |
| 状态点/状态字 | StatusGreen/Amber 深色值在浅底偏亮 | 浅色加深 |
| `LocalContentColor` 兜底 | 现兜底 = `onBackground`（深色） | 浅色主题需兜底 = 浅色 onBackground |

### 2.4 跟随系统实现

- `isSystemInDarkTheme()`（Compose 官方）取系统深浅。
- 三态优先级：`Light/Dark` 手动覆盖 > `FollowSystem`。
- 判定纯函数：`resolveDarkTheme(mode, systemDark) -> Boolean`（可单测）。
- 持久化：与现有 DraftCache/EventCache 同机制的键值存储（新增 `ThemePrefStore`，key=`theme_mode`），冷启动读取。

### 2.5 关键指标 / 埋点

| 指标 | 口径 | 埋点 |
|---|---|---|
| 主题切换 | 每次手动切换 | `ACTION`：`主题切换 mode=light/dark/system` |
| 模式分布 | 冷启动读取持久化值 | `ACTION`：`主题启动 mode=... systemDark=...` |
| 深色判定 | 每次主题决议 | DEBUG 可选：`主题决议 mode=... resolved=dark/light` |

### 2.6 验收口径

- 三主题态（跟随/浅/深）各逐屏核对：会话列表、会话详情（气泡/代码块/表格/转盘/排队/Goal/Deep Diving）、日志页（级别色）、设备页、审批/提问/中断弹窗、Diff 卡片。
- 用 `uiautomator dump` 文本树 + 截图（或 `read_image`）双设备核对；对比度抽检 ≥4.5:1（正文）。

---

## 三、涉及产品改动

- **仅 App（composeApp）**：`Theme.kt` 重写（两套 ColorScheme + 三态）、新增 `ColorTokens.kt`、新增 `ThemePrefStore`、`App.kt`/`DiffView.kt` 硬编码色替换。
- **bridge 无变化**（纯客户端）。

---

## 四、关键技术选型：dynamicColor 取舍（结论：不用）

| 维度 | dynamicColor（Material You） | 手动两套 ColorScheme（推荐） |
|---|---|---|
| 可控性 | 色来自壁纸，不可预测 | 完全可控 |
| 品牌/语义一致 | 无法保证 DeepSeekBlue/状态色/代码块色一致 | 保证 |
| 自定义语义色 | 不影响（我们的 Status/代码块/日志色仍是硬编码，会与系统色打架） | 全 token 化统一 |
| 对比度保证 | 难保证（壁纸深浅不定） | 可逐项核定 |
| 移动端优先 | 引入不可控变量 | 符合「可预测、可验收」 |

**结论**：Remote Control 需「任意主题下 UI 可读 + 品牌一致」，dynamicColor 引入不可控变量，故**不用**；手动 `lightColorScheme`/`darkColorScheme` + 自定义 token。

---

## 五、关键指标（含口径）

见 2.5。

---

## 六、预期收益

- 白天/户外可读性大幅提升；跟随系统省手动。
- 颜色盘收敛消除 ~29 处硬编码散点，服务 detekt 收紧与后续主题扩展。

---

## 七、核心功能点（编号可验证）

1. `F1`：颜色盘 token 体系建立（Material 槽 + 自定义语义色统一）。
2. `F2`：lightColorScheme 落地，浅色主题可渲染。
3. `F3`：三态切换 + 跟随系统 + 持久化。
4. `F4`：硬编码颜色全量替换为 token（零 `Color(0x` 残留，lint/脚本校验）。
5. `F5`：手机/平板双设备、三主题态逐屏回归通过。

---

## 八、核心埋点或日志

见 2.5 表。

---

## 九、验收口径

见 2.6。

---

## 十、相关文档链接

- `docs/ui-contrast-guidelines.md`：对比度原则。
- `docs/ui-mobile-first.md`：移动端字号/布局基线。
- `docs/prd/平板适配三栏布局PRD.md`：并发改动（平板分流也改 `App.kt`/主题入口）。

---

## 附录 A：颜色 token 映射总表（技术方案 + 单测设计）

### A.1 文件结构

```
composeApp/src/commonMain/kotlin/com/daniel/dshremote/
  ui/theme/
    ColorTokens.kt      // 语义 token 定义（object ColorTokens { val StatusGreen: Color ... }，浅/深两套）
    DshTheme.kt         // 重写 Theme.kt：lightColorScheme/darkColorScheme + ThemeMode 三态 + resolveDarkTheme
    ThemePrefStore.kt   // 主题选择持久化（接口 + 默认内存实现，对齐 DraftCache 风格）
```

- `ColorTokens` 通过 `CompositionLocal` 或直接由 `MaterialTheme` 派生暴露；自定义语义色（Status/Accent/DeepSeek/Markdown/Diff/Log）读 `LocalColorTokens.current`。
- 替换策略：分阶段——① 建 token 与 CompositionLocal；② 逐文件把 `Color(0x…)`/顶层 `val` 替换为 `tokens.X`；③ lint/脚本扫 `Color\(0x` 残留清零。

### A.2 映射总表（token → light → dark）

| Token | light | dark（=现状） |
|---|---|---|
| primary | 0xFF3057D5 | 0xFF3057D5 |
| onPrimary | 0xFFFFFFFF | 0xFFFFFFFF |
| primaryContainer | 0xFFDCE6FF | 0xFF2A3C66 |
| onPrimaryContainer | 0xFF1B2B5C | 0xFFDCE6FF |
| background / surface | 0xFFFFFFFF / 0xFFF8F9FC | 0xFF0B0F1A / 0xFF151B2C |
| onBackground / onSurface | 0xFF1A1F2B | 0xFFE6E9F2 |
| surfaceVariant | 0xFFEAEEF6 | 0xFF1E2638 |
| onSurfaceVariant | 0xFF4A5468 | 0xFF9AA3B8 |
| outline / outlineVariant | 0xFFC7CFDD / 0xFFD7DEEA | 0xFF2A3348 / 0xFF232C40 |
| error / errorContainer / onError / onErrorContainer | 0xFFB3261E / 0xFFF9DEDC / 0xFFFFFFFF / 0xFF410E0B | 0xFFFF6B6B / 0xFF4A1D1D / 0xFF3A0B0B / 0xFFFFD9D9 |
| secondary/tertiary 等 | 按 Material 规范补 | 现状 |
| StatusGreen | 0xFF10B981 | 0xFF34D399 |
| StatusGray | 0xFF6B7280 | 0xFF8A93A6 |
| StatusAmber | 0xFFB45309 | 0xFFF2C14E |
| StatusOrange | 0xFFEA580C | 0xFFFFA94D |
| AccentBlue | 0xFF2563EB | 0xFF9DB8FF |
| DeepSeekBlue | 0xFF3057D5 | 0xFF4D6BFE |
| MarkdownCodeBg/Fg | 0xFFF6F8FA / 0xFF24292E | 0xFF14181F / 0xFFDCE4EF |
| DiffDelBg/Fg | 0xFFFFEBEE / 0xFFB71C1C | 0xFF3B1D24 / 0xFFFF9AA2 |
| DiffAddBg/Fg | 0xFFE8F5E9 / 0xFF1B5E20 | 0xFF17321F / 0xFF7EE787 |
| LogDebug/Info/Warn/Error | 0xFF6B7280 / 0xFF2563EB / 0xFFB45309 / 0xFFB3261E | 0xFF8B93A7 / 0xFF6E9BFF / 0xFFF2C14E / 0xFFFF6B6B |
| ApproveCmdGreen | 0xFF1B5E20 | 0xFFB8E6B8 |

> 上表 light 列为**建议初值**，落地时按 `ui-contrast-guidelines.md` 抽检对比度后再定稿。

### A.3 与并发改动兼容

- **平板适配（`docs/prd/平板适配三栏布局PRD.md`）**：平板分流改 `App.kt` 根部分流 + `TopBar`（主题入口所在）；本主题改动也改 `Theme.kt`/`App.kt`/`DiffView.kt`/`TopBar`。**建议用 `git worktree` 各自隔离，合并时解决 `App.kt`/`TopBar` 冲突**；主题入口建议放 TopBar，与平板「中/右栏精简 TopBar」需在合并时对齐。
- **输入区操作条 / 通知**：不直接冲突，但 `App.kt` 共享，仍走 worktree。

### A.4 单测点清单（纯逻辑，commonTest）

1. **token 完整性**：`ColorTokens.light/dark` 两套对象字段一一对应（无缺漏）。
2. **三态解析**：`resolveDarkTheme(FollowSystem, true/false)` / `(Light, *)` / `(Dark, *)` 全 6 组合。
3. **持久化往返**：`ThemePrefStore.save/load` 默认值 + 三态往返。
4. **映射函数**：token → Color 在浅/深各返回正确值（抽样 StatusGreen/AccentBlue/MarkdownCodeBg）。
5. **对比度抽检**：浅/深两套下「onSurface vs surface」「onPrimary vs primary」「代码块 fg vs bg」≥ 阈值（纯函数算 WCAG 对比度）。

### A.5 设备自测方案

- 三主题态 × 两设备（手机 AVD `dsh-test` 1080×2400 + 平板 AVD 见平板 PRD）各一轮。
- 每态逐屏：会话列表 / 会话详情（气泡/代码块/表格/转盘/排队/Goal/DeepDiving）/ 日志页 / 设备页 / 审批 / 提问 / 中断弹窗 / Diff 卡片。
- 每屏用 `uiautomator dump` 文本树 + 截图核对；正文对比度抽检 ≥4.5:1。
- 冒烟：装机后 `startup-smoke-test` 双设备先过闸门。

---

## 附录 B：待澄清点清单（主对话转用户拍板）

1. **切换入口位置**：TopBar 图标按钮（推荐） vs 日志页旁 vs 新增设置页？
2. **深色默认策略**：现状是「深色专享」；升级后默认应改为**跟随系统**，还是保持深色？（推荐跟随系统）
3. **Markdown 代码块/表格是否需要深色专项**：浅色下代码块要换浅底深字（token 化即可），是否需要额外打磨表格配色？（推荐 token 化一次性做对）
4. **主题选择是否跟随设备/账号**：同一 token 是否跨设备同步（当前本地持久化）？（推荐仅本地，不跨设备）
5. **自定义语义色浅色值是否需用户先目验**：Status/Accent 等浅色加深幅度（本表给建议值，是否先出截图让用户确认？）
6. **dynamicColor 确认不用**（本 PRD 结论，需用户点头）。

---

## 附录 C：实施阶段建议拆分（含文件冲突协调）

- **阶段 0（本阶段）**：PRD + 技术方案 + 待澄清 → 用户拍板（尤其待澄清 1/2）。
- **阶段 1**：`ColorTokens.kt` + `lightColorScheme` + `ThemeMode` 三态 + `resolveDarkTheme`（纯逻辑可单测）→ 编译 + 单测 → commit。
- **阶段 2**：硬编码替换（`Color(0x` 清零 + 顶层 `val` 收敛）→ 编译 + 单测 + 手机浅色冒烟 → commit。
- **阶段 3**：三态切换入口 + 持久化 → 双设备三态逐屏自测 → commit。
- **冲突协调**：涉及 `App.kt`/`Theme.kt`/`DiffView.kt`（与平板适配、输入区、通知等并发），统一走 `git worktree` 隔离，合并时解 `App.kt`/`TopBar` 冲突。
