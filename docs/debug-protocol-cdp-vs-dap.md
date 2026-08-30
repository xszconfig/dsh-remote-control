# 调试协议技术选型：CDP vs DAP（coding agent 场景）

> 状态：已决策（CDP）· 资料归档 2026-08-30
> 决策规则：**任何架构/技术路线/重大选型变更，必须先停下来问用户**（见两仓库 AGENTS.md 铁律第 1 条）。

## 1. 背景

DSH Remote Control 的调试后端（bridge `src/debug.ts`）采用 **CDP（Chrome DevTools Protocol）直连 Node Inspector** 实现远程断点调试；开源 coding agent 项目 oh-my-pi（本地源码 `/Users/xieshaoze/github/oh-my-pi/packages/coding-agent/src/dap/`）采用 **DAP（Debug Adapter Protocol）+ 外部适配器**。本文档记录两种路线的对比分析与选型论证，供未来扩展语言时决策参考。

## 2. 两种协议概述

### 2.1 CDP —— Chrome DevTools Protocol

- **本质**：Chromium/Node 运行时**内建**的调试协议（浏览器 DevTools 与内核通信的协议，Node Inspector 直接复用）。
- **形态**：WebSocket + JSON 消息，按域组织（`Debugger`/`Runtime`/`Network`/`Profiler`/`Page`…）。
- **连接方式**：客户端直接连运行时内建端点（`node --inspect`、Chrome remote debugging port）。
- **我们项目用法**：`node --inspect-brk=0 <脚本>` → 从 stderr 解析 WS 地址 → `Debugger.enable/setBreakpointByUrl/paused/resume/stepOver/stepOut` + `Runtime.getProperties` 读变量。

### 2.2 DAP —— Debug Adapter Protocol

- **本质**：微软 2016 年定义的中立调试协议（**LSP 的调试姊妹协议**）。
- **形态**：类似 LSP 的 JSON + Content-Length 帧（stdio/TCP），`launch`/`attach` 配置驱动。
- **三层结构**：客户端（IDE/Agent）↔ 适配器进程 ↔ 真实调试器。
- **生态**：js-debug、debugpy、`gdb -i dap`、lldb-dap、codelldb、delve…（微软/各语言团队官方维护）。

## 3. 对比总表

| 维度 | CDP | DAP |
|---|---|---|
| 定位 | 运行时**内建**协议 | 编辑器中立**标准**协议 |
| 绑定 | Chrome/Node 系运行时 | 语言无关（靠适配器） |
| 层次 | 客户端 ↔ 运行时（2 层） | 客户端 ↔ 适配器 ↔ 调试器（3 层） |
| 复杂度 | 低，零适配器运维 | 中，需安装/管理适配器二进制 |
| 能力面 | 断点/单步/变量/表达式 + console/Network/性能剖析 | 断点/条件断点/栈/变量/表达式（IDE 调试视图语义，无 Network/Profile） |
| 生态 | Puppeteer/Playwright 成熟 | VS Code / nvim-dap / OMP 等通用工具 |
| 延迟 | 最低（少一跳） | 略高（多一层进程） |
| 版本演进 | 随 Chrome 演进，有碎片 | 中立标准，稳定 |

## 4. 应用场景

- **CDP 适用**：Chrome/Electron/Node 系调试；浏览器自动化；单运行时专用工具；调试对象就是 Node 脚本时。
- **DAP 适用**：多语言统一调试体验（VS Code、nvim-dap、OMP）；需要覆盖 Python/Go/Rust/JVM 等非 Chrome 运行时时。

## 5. coding agent 行业现状（选型背景）

主流 coding agent 产品的 IDE 壳几乎全是 **Electron/Chromium** 技术栈（WorkBuddy、Trae、Z Code、Cursor/Windsurf 等）——前端生态 + 快速发版（日更）是主因。OpenAI Codex 本体是 **CLI（Node/TS）+ VS Code 扩展**，agent 核心运行时本身就是 Node。这两点都**强化 CDP 路线**：

1. 调试目标（Agent 写的/跑的代码）绝大多数是 Node/TS 脚本（Node 22 可直接跑 TS）；
2. Electron 壳和浏览器上下文的调试**只能走 CDP**（DAP 反而要再包一层 CDP 适配器）；
3. 零适配器运维成本，与"快速发版"的产品哲学同源。

## 6. 我们的选型论证

**当前选型：CDP-only（维持）。理由：**

- 产品定位是控制本机一个 **Node 内核的 harness**，调试对象是 agent 在 Mac 上跑的脚本——Node/TS 全覆盖；
- 零新依赖（当时本机无 VSCode/js-debug 适配器，也不愿引入大型依赖）；
- CDP 能力面（console/Network/Profile）更贴合"agent 排查自己代码"的需求；
- 冒烟测试全链路验证通过（断点命中→栈→变量→单步→退出→输出流）。

**边界（CDP-only 的真正代价）：** 非 Chrome 系运行时无法调试——Python（诊断已支持，调试不行）、Go、Rust、JVM。

## 7. 未来扩展三条路线（变更前必须先问用户）

1. **CDP-only**（现状）：Node/TS/Electron 全覆盖，零运维；
2. **DAP 客户端**（OMP 路线）：一个客户端 + 各语言适配器（debugpy/gdb -i dap/delve…），统一但多一层运维；
3. **每语言原生协议各写后端**：pydevd/delve/jdwp 各接各的，最轻但客户端代码 N 份。

**升级 seam 已预留**：bridge `DebugManager` 的回调接口（onState/onOutput/onVariables）与传输无关，将来接 DAP 只需新增后端类平替 `InspectorSession`；手机端面板、Agent 工具（debug_start/debug_command）、WS 协议全部复用。

## 8. 参考

- OMP DAP 实现：`/Users/xieshaoze/github/oh-my-pi/packages/coding-agent/src/dap/`（client.ts / session.ts / defaults.json）
- 本项目调试后端：`/Users/xieshaoze/Code/dsh-remote-control-bridge/src/debug.ts`
- 协作铁律：两仓库 `AGENTS.md`
