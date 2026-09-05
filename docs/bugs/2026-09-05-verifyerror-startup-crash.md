# [BUG] App 启动即崩：DEX 校验器 VerifyError 拒绝 Conversation 巨型 Composable（P00）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复（修复版已装机验收 2026-09-05 10:46） |
| 仓库/模块 | dsh-remote-control / composeApp（App.kt Conversation） |
| 发现方式 | 真机验收（00:55 装机后启动即崩） |
| 日期 | 2026-09-05 |
| 相关 commit | `249bb4a`（fix）、`0543a32`（引入嫌疑：转盘改版致 Conversation 越过阈值） |
| 关联文档 | `2026-09-05-dial-surface-swallows-send-interrupt.md`（同批装机顺带暴露，L131 附带提及） |

## 背景

- Conversation 是客户端会话主界面巨型 Composable，随功能迭代持续膨胀（转盘/深潜条/Todo/Goal/队列/开发者面板/输入区先后并入），单函数承载整个会话 UI。
- 规则书（AGENTS.md）要求「编译通过 → 自动安装到华为真机 → 自动验收」，但验收对「启动崩溃」无硬性断言，导致崩版本装机后未被即时拦截回滚。

## 现象

- 2026-09-05 00:55:39 安装新 APK（0543a32 转盘改版后构建版），00:55:42 与 00:56:09 两次启动即崩，App 无法打开（用户侧 P00）。
- logcat crash buffer 实锤：

```
java.lang.VerifyError: Verifier rejected class com.daniel.dshremote.AppKt:
void AppKt.Conversation(BridgeClient, SessionUiState, String, Composer, int)
failed to verify: [0xD53] copy1 v2<-v266 type=Reference:
androidx.compose.runtime.Composer cat=1
(declaration of 'com.daniel.dshremote.AppKt' appears in
 /data/app/.../com.daniel.dshremote-3NbHT439KNS0S7gtRg7KBg==/base.apk!classes5.dex)
  at com.daniel.dshremote.AppKt.App(Unknown Source:0)
  at MainActivity$onCreate$1.invoke(MainActivity.kt:35)
```

- 特征信号：`Verifier rejected class ... failed to verify: [0x…] copyN vA<-vB` + **寄存器号深达 v266** = 单方法字节码规模失控。

## 排查过程

| 时间 | 事件 |
| --- | --- |
| 00:41 | `ab091b9` 转盘手势 P00 修复 |
| 00:54 | `0543a32` 转盘改版（Conversation 再膨胀，越过 verifier 阈值） |
| 00:55:39 | 装机（0543a32 版） |
| 00:55:42 / 00:56:09 | 两次启动 VerifyError 崩溃（logcat crash buffer） |
| 00:56–08:47 | 夜间窗口，未即时修复 |
| 08:48 | `249bb4a` 修复：拆分 Conversation 巨型 Composable（+544/-468，拆为 9 个私有 Composable：ConversationMessageList / ConversationPanels / ConversationDevPanel / ConversationComposer / DeepDivingBar / TodoPanel / GoalPanel / QueuePanel 等） |
| 08:48 | APK 重建（含修复） |
| 08:59 | `45d32e8` 转盘 bug 报告补模拟器验收结论（附一句提及 VerifyError 修复，**未建独立 bug 文档**） |
| 10:46 | 修复版 APK 装机到华为真机（Leo，fast-install）+ 启动验收通过（进程存活、MainActivity 前台、crash buffer 无新条目） |

排查受限点：修复后 agent 未按规则自动重装真机（08:48–10:46 手机仍为崩溃版）；本次由 Leo 人工补装。

## 根因分析（5Y 深挖）

### Y1 为什么启动即崩？
`App()` 入口必然调用 `Conversation()`，该类在 ART 类加载验证阶段被 DEX 校验器整体拒绝（`Verifier rejected class AppKt`），任何路径进入都抛 VerifyError。不是某条 UI 路径的问题，是**类级拒绝**。

### Y2 为什么 DEX 校验器拒绝一个「编译通过」的类？
Conversation 单方法体极大（寄存器深达 v266），Compose 编译器生成的字节码在 dex 化后超出 ART verifier 可靠校验的复杂度边界，`copy1 v2<-v266` 引用类型赋值校验失败。
关键认知：**javac/kotlinc 编译通过 ≠ dex/ART 层安全**。编译器的 64KB 方法限制与 ART verifier 的校验复杂度边界不是一回事；d8/dex 阶段也不做 ART 同等严格度的校验 → 错误被推迟到**运行时类加载**才暴露。

### Y3 为什么 LSP（kotlin-lsp）没拦住？—— 不是没调/掉线，是能力边界外
- LSP 提供的是源码级诊断：语法、类型、符号、引用（pull diagnostics + lsp_query 四动作）。本 bug **源码层完全合法**（语法/类型全对，否则编译都过不了、APK 都打不出来）。
- VerifyError 发生在编译产物（dex 字节码）→ ART 运行时校验层，**在 LSP 分析范围之外**：LSP 不生成字节码、不跑 dex、不校验寄存器分配。调了也必然查不出。
- 推论：这类 bug 的可靠拦截点只有两个——①构建期对「超大方法」的静态告警（当前缺失）；②**真机启动冒烟 + 崩溃断言**（规则书要求装机验收，但验收缺「启动存活」硬断言，形同虚设）。

### Y4 为什么验收环节没拦住 00:55 的崩版本？
装机后 3 秒即崩（00:55:39 install → 00:55:42 crash），但验收未形成「崩溃即失败」的阻断信号，agent 也未立即响应（隔 8 小时才修）。验收逻辑只验证了「装上了/能连上」，没有断言「进程存活 N 秒 + crash buffer 无新条目」。

### Y5 为什么巨型方法会一路长到爆掉（结构性根因）？
1. **无组件规模门禁**：功能迭代只加不拆，Conversation 承载了 UI 全部职责，没有「单 Composable 超限即拆分」的强制规则；
2. **验证盲区**：「编译通过 + LSP 0 诊断」被当作质量信号，而此类错误恰好绕过这两关，唯一的真机启动关卡又没有崩溃断言；
3. **修复后装机链路断裂**：修好 ≠ 用户恢复，自动装机/验收依赖 agent 自觉执行，无失败升级机制（本次最终由人肉补装）。

## 解法

- `249bb4a`：把 Conversation 按 UI 区域拆分为 9 个私有 Composable（消息列表/面板组/开发者面板/输入区/深潜条/Todo/Goal/队列…），单方法规模回归正常，ART 校验通过。
- 为什么这样修而不是调 dex 配置：根因是源码结构失控，不是构建配置问题；拆分同时改善可维护性。
- 回归验证：真机启动验收通过（10:46，进程存活 + MainActivity 前台 + crash buffer 无新 FATAL）。

## 后续改进计划（可复用经验）

1. **组件规模门禁**：单 Composable > ~400 行必须拆分；巨型函数是定时炸弹（本案例从正常到爆掉仅一步之遥）。
2. **启动崩溃硬验收**：装机验收必须显式断言「App 进程存活 ≥5s + logcat crash buffer 无新条目」，崩溃即失败并立即告警/回滚，不允许静默通过。
3. **工具能力边界认知**：「语法/类型全对 + LSP 干净 + 编译通过」≠ 没有 bug；出现「全对却启动崩」时优先怀疑编译产物层（超大方法、Compose 代码生成、混淆），排查入口 = crash buffer 的 VerifyError 特征串，它会精确点名类和方法。
4. **修复闭环**：修完必须自动重装真机验收，用户手机停留在崩溃版的每一分钟都是 P00 时长；装机失败要有升级通道（人肉补装路径已演练：huawei-adb-fast-install）。
