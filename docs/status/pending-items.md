# 验收与决策状态台账（早报数据源）

> 本文件是每日早报的唯一事实源：任何任务状态变化（完成/自测/待验收/待决策）都要同步更新本文件。
> 早报生成方式见 `morning-report` skill；每天 08:12 自动生成。

## A. 自测完成、待用户人工验收（真机装机后逐项复核）

- [ ] APK 瘦身（新增 release-in-house + release-store 两 buildType 开 R8 + arm64-only）—— `df750a9`，release-in-house 2.16MB（debug 12.6MB → −83%，目标 ≤6.3MB）；模拟器冒烟 PASS + 连接/hello/序列化/日志页 tab/问题弹层回归通过；待真机装机验收
- [ ] 时间戳新格式（前缀+时刻恒显：今天/昨天/前天/M月d日/跨年）—— `688257e`，单测 22 例 + 模拟器 UI 已验证；待真机装机后验收
- [ ] 转盘改版：左移 Deep Diving 上方 + 56dp 五线小转盘图标 —— `0543a32`，模拟器验证；待真机目验位置/图标
- [ ] 转盘方向调转（顺时针=更老 / 逆时针=更新）—— `b4af84e`，模拟器日志证据验证；待真机手感验收
- [ ] 新消息不打断转盘（非收起态挂起强制滚底）—— `868b547`，门控代码验证；待真机实测（自动化实测受会话事件流干扰）
- [ ] 行为日志埋点全覆盖（发送/中断/输入/列表/翻页/转盘/日志页）—— `42740d5`，日志证据验证；待抽查
- [ ] Markdown 宽表格横滑 —— `86f6c68`，5 条单测；待人工目验（此前锁屏未复验）
- [ ] 跳到底部按钮回归 —— `4184b59`；待目验
- [ ] 子代理列表元信息 + 滚动 —— `fc1dfe19`；待目验

## B. 做完尚未自测

- （暂无）

## C. 调研完成、未实施（等待用户决策）

> 原第 1 项「APK 瘦身」已于 `df750a9` 实施（两 buildType 开 R8 + arm64-only），见 A 节。

1. **Jugg 无头编译服务 MVP**（2-5 人日）vs 维持 Gradle 现状
2. **Kotlin LSP 二进制更新**：JetBrains EAP 已过期（更新路径已调研，见 bridge `77e4146` 报告）
3. **中断语义**：排队消息随「中断」清除 vs 需显式撤销（曾致 3 条消息丢失）
4. **发送失败不丢字**：发送失败时保留输入框文字 + 醒目报错（当前失败即清空且报错可被重连态抑制）
5. **装机进度显示**：包大小/传输进度/安装器调用/错误上屏（需求已提出未实施）
6. **R4 规则接入 lint**：构建期扫 DEX registers_size（>128 告警 >256 报错）——已登记 `docs/coding-rules/verifyerror-deep-dive.md`
7. **goal 完成态隐藏**：客户端对齐 DSH Web 隐藏已完成/解除的目标
8. **推 GitHub**：等待用户明确指示
9. **TS core.ts 3 处 lint 豁免拆分**（apply/handleCommand/projectEvent）——收紧路径已登记 `docs/lint-rules.md`

## D. 流程/工具新增（自测完，无需人工验收，知晓即可）

- `startup-smoke-test` skill：P00 启动冒烟（存活 ≥5s 硬断言），真机+模拟器双 PASS；已写入 AGENTS.md 规则 11
- `code-lint` skill + pre-commit P0 闸门（detekt/ESLint），拦截与放行双验证；已写入双仓库 AGENTS.md
- `docs/coding-rules/verifyerror-deep-dive.md`：14 条 VerifyError 全景 + R1~R6 规则
- `docs/feature-priority-map.md`：13 条 P0 功能地图
- 工具调用超时修复（lsp_query/debug 10s deadline，热重载上线）
- 真机安装提醒：手机 adb 通道（Tailscale/USB）恢复后安装 20:55 最新包（含时间戳新格式+转盘两改版）
