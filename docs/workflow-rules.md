# 工作流规则（Memory）

> 记录于 2026-08-29，用户明确指示。

## Git 推送纪律

1. **修完 bug / 实现完功能 → 只做本地 commit，禁止直接 push 到 GitHub。**
2. **推送必须等用户明确说「推到 GitHub」之类的指示再执行。**
3. 本地 commit 仍然保持小而清晰（一个修复/功能一个提交），随时可推。

## 其它约定

- **重启 DSH Web / 影响服务器的操作：agent 可以自行执行，但执行前必须找用户确认一次**（用户授权后执行；会话会短暂中断并自动恢复）。
- **重启脚本必须保留完整 PATH**（Android platform-tools 等）：新进程从 nohup 环境继承 PATH，
  漏了会导致 adb 等工具找不到——重启脚本里显式 export
  `PATH=/opt/homebrew/opt/node@22/bin:/opt/homebrew/bin:$HOME/Library/Android/sdk/platform-tools:/usr/bin:/bin:/usr/sbin:/sbin`。
- **重启脚本的延时 5 秒即可**（用户要求，勿用 15 秒）。
- 用户日常只用手机端交互，尽量不碰桌面端——诊断、日志拉取、安装都要支持无线路径。
- 重要原则类内容写入 `docs/` 的 Memory 文档（对比度、导航、本工作流规则等），跨会话持续生效；
- 排查问题优先读两端结构化日志（服务端 `/remote/logs`、手机端内置日志页 / `/remote/phone-logs`）；
- 安装 APK 用快装脚本（固定坐标）或鲁棒 dump 模式，二选一按场景。
