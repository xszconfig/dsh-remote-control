# AGENTS.md —— 本项目协作规则（跨会话记忆）

## 铁律（用户明确要求，违反即失误）

1. **重大技术决策必须先停下来问用户**：涉及架构、重大技术路线、重大技术选型（例如调试协议 CDP vs DAP、语言服务器选型、持久化方案、协议设计方向）一律不得擅自决定。这些决策影响项目拓展性、可维护性与后续技术规划。
2. **只做本地 commit，不推 GitHub**：推送只在用户明确指示"推 GitHub"后执行；用户验收通过前不推送。
3. **不出现用户非预期的页面跳转**：连接状态变化只用横幅/页内元素表达。
4. **重启服务端必须先问用户确认**；重启脚本须保留完整 PATH（Android platform-tools）。
5. **装机用 huawei-adb 系列 skill**（huawei-adb-install 鲁棒模式 / huawei-adb-fast-install 快速模式），不用裸 push+pm install。
6. **所有数据以服务端投影为准**：客户端不做本地推算（如计时），单向数据流。
7. **消息推送目标 100~300ms 内送达**。
8. 真机验收：编译通过 → 装机 → 自动验收，结果直接汇报。

## 历史决策记录（供未来会话参考）

- 调试后端：当前用 **CDP（Node Inspector）直连**，零依赖但仅 Node；`DebugManager` 回调接口已预留 DAP 平替 seam。**未来若扩语言（Python/Go/Rust）必须先问用户**再实施 DAP 后端。
- 语言服务器：TS/JS/Python/Rust/C/C++ + 官方 JetBrains kotlin-lsp（pull 诊断 + 项目导入）。
- 自动续跑：持续重试 + 指纹幂等 + work.json sessionId 归属（根治版）。
