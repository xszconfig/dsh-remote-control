# 验收报告：技能点选两小问题修复（#57 验收修复）

- 日期：2026-09-12
- 主题：技能面板「浏览→点选→写入输入框」链路的两个体验缺陷修复 + 真机目验
- 实现代理：App-UI 子代理
- 相关 commit：`c4b2bd7`（App 侧修复，已 fast-forward 到 main）

## 背景

#57 核心链路（点技能 → 写入 `/技能名 ` → 发送 → Agent 调技能）已 PASS，但真机验收发现两个体验问题：

1. **预填后光标在位置 0**：`onInputChange("/$name ")` 走 `OutlinedTextField` 的 `String` 重载，其内部自管 `TextFieldValue` 选区，状态提升写 `String` 时选区被重置到 0，用户接续输入会插到技能名前。
2. **键盘未自动弹起**：`pendingSkillFocus` 已把焦点拉回输入框，但 `keyboardController.show()` 在 `FocusRequester.requestFocus()` 的焦点请求真正生效（下一帧）之前调用，被 IME 吞掉。

## 修复内容（diff 摘要）

文件：`composeApp/src/commonMain/kotlin/com/daniel/dshremote/Composer.kt`、`Conversation.kt`

- 输入框状态类型 `String` → `TextFieldValue`（`Conversation` 状态 + `ConversationComposer` 参数 + `onInputChange` 回调全链路）。
- `SkillPanel.onPick` 与斜杠命令 `CommandCandidatePopup.onPick`：写入
  `TextFieldValue("/$name ", selection = TextRange("/$name ".length))`，光标置末尾。
- 草稿载入 `input = TextFieldValue(it)`、发送清空 `onInputChange(TextFieldValue(""))` 同步改类型。
- 键盘时序：`LaunchedEffect(showSkillPanel)` 内 `requestFocus()` 后加 `withFrameNanos { }` 等一帧再 `keyboardController?.show()`，让焦点落定后唤起键盘。

## 闸门

| 闸门 | 结果 |
| --- | --- |
| detektP0 | 99 kotlin files analyzed，零命中 |
| testDebugUnitTest | PASS |
| assembleDebug | BUILD SUCCESSFUL |
| checkDexRegistersDebug（DEX 门禁） | max registers=191，报错(>256)=0，告警(>128)=6（存量，非新增） |

## 真机目验（HBN-AL00 / Android 12 / USB 2NP0224806003991）

热更新：jugg-apply（HOT_FIX overlay，compile 6001ms / mergeDex 585ms / apply 1631ms / restart 579ms）→ `am force-stop` + 冷启动冒烟（存活 >5s，crash buffer 无本包 FATAL）。

| 验收项 | 预期 | 实测 | 结论 |
| --- | --- | --- | --- |
| 点「技能」chip 打开面板 | 半屏面板 + 技能列表 | 面板打开，列出 arkui-scoring-workflow / ask-matt / batch-grill-me 等 | PASS |
| 点技能行写入输入框 | 输入框 = `/技能名 `（带尾空格） | `/arkui-scoring-workflow ` | PASS |
| 光标在文本末尾 | 接续输入追加到技能名后 | 选技能后注入字符 q → 输入框 = `/arkui-scoring-workflow q`（q 在末尾，非 q/arkui…） | PASS |
| 键盘自动弹起 | 选技能后 IME 弹出 | 选技能后 `dumpsys input_method`：`mInputShown=true mIsInputViewShown=true` | PASS（但见下「遗留」） |

说明：光标末尾目验采用「选技能 → `input text q` → dump 读输入框文本」间接取证——若光标在 0，结果应为 `q/arkui-scoring-workflow `；实测为 `/arkui-scoring-workflow q`，证明光标在末尾。

## 遗留（诚实标注）

- 键盘弹起这一项，本代理首版用 `withFrameNanos{}` 等一帧再 `show()`（commit `c4b2bd7`）。目验 `mInputShown=true` 存在歧义：可能是技能面板搜索框键盘在面板收起时「延续」到输入框，而非 `show()` 真正生效。后续有并发代理进一步定位「一帧不够、IME 接管输入框异步」，在未提交的工作区把键盘唤起改为 `LaunchedEffect(inputFocused)` 在焦点真正落定后 `delay(120)` 再 `show()`（见 `Composer.kt` 工作区 diff）。该 refinement 尚未提交，由对应代理/主对话落 commit——本报告键盘项以「已修复但有更强 refinement 待提交」为准确口径。

## 截图清单

| 序号 | 文件 | 对应验收项 |
| --- | --- | --- |
| 05 | [05-before-pick.jpg](../../screenshots/2026-09-12-skill-pick/05-before-pick.jpg) | 点选前（技能 chip + 输入框） |
| 06 | [06-skill-panel-open.jpg](../../screenshots/2026-09-12-skill-pick/06-skill-panel-open.jpg) | 技能面板打开 |
| 07 | [07-after-pick.jpg](../../screenshots/2026-09-12-skill-pick/07-after-pick.jpg) | 点选后输入框 = `/arkui-scoring-workflow ` |
| 08 | [08-cursor-end-verified.jpg](../../screenshots/2026-09-12-skill-pick/08-cursor-end-verified.jpg) | 注入 q 后 = `/arkui-scoring-workflow q`（光标末尾佐证） |

（01–04 为上轮「核心链路」验收截图，由前一代理落盘。）

## 取证路径

- 光标末尾：`adb shell uiautomator dump` → 输入框节点 text = `/arkui-scoring-workflow q`
- 键盘弹起：`adb shell dumpsys input_method` → `mInputShown=true mIsInputViewShown=true`
- 冒烟：`am force-stop` + `am start` 后 `ps` 存活 >5s，`logcat -b crash` 无本包 FATAL

## 未实测项（诚实标注）

- 端到端「发送 → Agent 实际调用该技能」不在本次范围（核心链路上一轮已 PASS，本次只验证两个体验缺陷）。
- 并发干扰：目验期间有并发代理在同一真机执行 release 包 huawei-adb-install，曾两次打断；最终在 install 结束后重测取到干净证据。
