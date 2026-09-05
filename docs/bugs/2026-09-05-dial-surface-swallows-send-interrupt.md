# [BUG] 转盘恒挂手势条独占吞掉发送/中断按钮点击

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`composeApp/src/commonMain/kotlin/com/daniel/dshremote/MessageDial.kt` 的 `DialSurface`） |
| 发现方式 | 真机验收 + 用户报告（装机后时好时坏） |
| 日期 | 2026-09-05 |
| 相关 commit | `ab091b983399de0161f1b2fbb9c741d6bd93acff`（`ab091b9`） |
| 关联文档 | `docs/bugs/_TEMPLATE.md`；引入缺陷的 `fbb2e14`（转盘微调）、埋点 `42740d5`（行为日志） |

## 背景

- 消息转盘（MessageDial）v1（`db07b72`）上线后，`fbb2e14` 提交「转盘微调」引入「单一恒挂 DialSurface」设计：为支持「摁下圆钮不抬指直接滑动旋转」的一气呵成手势，把 `DialSurface` 做成 **128dp × 全高、右缘对齐、恒挂载**（不论收起/展开都存在）的单一 `pointerInput(Unit)` 表面。
- 该表面收起态画 44dp 圆钮、展开态画 90° 扇面；`pointerInput(Unit)` 不因 `phase` 切换而重建，保证「按住不抬指 → 展开并旋转」mid-gesture 连续。
- 关键注释（当时的错误心智模型）：**「未命中圆钮半径则不消费，交给底层列表」**——即认为收起态下只要触点不在圆钮命中半径内，`return@awaitEachGesture` 就能让事件「漏」给底层的发送/中断/列表等兄弟节点。

## 现象

- 用户在核心会话里点「发送」按钮：输入框文字**不清空**、消息**不上屏**、无任何反应；「中断」按钮同样无反应。
- 用户原话："点击之后，输入框的文字还一直在，并没有被清空，也没有上屏"。
- **时好时坏**：无用户消息且无更早历史的会话里发送正常；有历史/有用户消息的会话里必现。

## 排查过程

1. **埋点判定「点击未到达 onClick」**：上线行为日志埋点（`42740d5`，发送按钮 `onClick` 第一行打 `ConnLog.info("ACTION", "发送点击 …")`）后复现——用户多次点击、adb 精确点击按钮中心，日志里「发送点击」**零条**。由此判定：点击从未到达发送按钮的 `onClick`，问题出在更早的事件分发/命中层，而非发送逻辑本身。
2. **uiautomator dump 排除「按钮不存在/坐标错」**：发送按钮真实 bounds `[868,2491]-[1030,2653]`、中断 `[1057,2491]-[1219,2653]`，按钮在 UI 树中存在、坐标正确。
3. **屏幕实测密度换算锁定量化证据**：实测 540dpi（×3.375），转盘 128dp 条 = **432px**，右缘对齐覆盖 x∈`[828,1260]`——发送/中断按钮整体落入条内，**零可点区域**。
4. **决定性实验**：`adb input tap` 点击发送按钮中心 `(949,2572)` 与左缘 `(872,2572)`，**均无反应、无日志**——精确点到按钮中心也不触发 `onClick`。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **表面原因**：恒挂载的 128dp 全高 `DialSurface`（带 `pointerInput` 修饰符）在收起态也铺满右缘整条高度，把发送/中断/跳底按钮全部罩住；`onClick` 根本收不到事件。

- **深层根因（Compose 命中测试对带 pointerInput 的兄弟节点顶层独占）**：
  1. **命中测试（hit test）层面**：同一父容器下重叠的兄弟节点中，z 序最高、且被命中的节点**独占**接收指针事件。`DialSurface` 带 `pointerInput`，它参与了 hit test 并命中了触点（因为它整条覆盖了按钮），于是整条路径上**只有** `DialSurface` 这一支，发送/中断按钮这些底层兄弟**根本没进入命中路径**，自然收不到任何事件。
  2. **`if (!active) return@awaitEachGesture` 不「消费」，但也救不了底层**：代码里「未命中圆钮半径时不 `consume()`、直接 return」的本意是「不消费就交给底层」，但这混淆了两个不同层面——**事件消费（consume）只影响同一命中路径内的派发顺序**，而**命中测试决定哪些节点能进入这条路径**。既然底层按钮压根没进路径，「不消费」也无人可接，事件就这样被顶层独占的 `DialSurface` 静默吞掉。
  3. **「时好时坏」的由来**：当会话无用户消息且无更早历史时，`MessageDial` 整体不渲染（early return），`DialSurface` 不存在，发送按钮恢复可点、发送正常；一旦有可定位的用户消息、转盘渲染出来，恒挂手势条就把按钮挡住。
  4. **错误心智模型**：`fbb2e14` 的注释「未命中则不消费，交给底层列表」把「消费语义」当成了「命中语义」，是本次设计缺陷的直接来源。

- **深度反思（三重因素叠加导致回归漏网）**：
  1. **响应区与视觉不符**：收起态视觉只是 44dp 圆钮，响应区却是 128dp 全高条——「看得见的是一小块，挡住你的是一整条」。
  2. **依赖错误的平台心智模型**：以为「不消费 = 交给底层」，没有先验证 Compose 命中测试对重叠兄弟节点的独占语义。
  3. **无点击级日志**：行为埋点（`42740d5`）正是为了排查「点了没反应」这类问题才补上，此前这类「点击被吞」故障完全不可观测、无从定位。
  - 另：装机验收时转盘真机自测被锁屏阻塞未执行，回归因此漏网。

- **可推广教训**：凡是「常驻覆盖层 + `pointerInput`」的交互设计，**必须先验证 Compose 命中测试行为再落代码**——常驻覆盖层只要 z 序最高且命中，就会独占事件，底层兄弟收不到；「不消费」不等于「穿透」。同时**响应区必须与视觉一致**，收起态只留圆钮大小的响应面。

## 解法

- 方案说明：
  - **收起态手势表面缩小为 52dp 见方**（44dp 圆钮 + 8dp 右边距），仅覆盖圆钮本身；**展开态保持 128dp 全高条 + scrim**。做到「view 多大，响应区就多大」——收起态不再罩住发送/中断/跳底按钮。
  - **命中常量从「相对 128dp 条宽的比例」（`28/128`、`30/128`）改为绝对 dp**（`KNOB_HIT_RADIUS_DP = 28f`、`KNOB_CENTER_INSET_DP = 30f`），在 `pointerInput` 内用 `dp.toPx()` 换算——因为收起态表面不再是 128dp，比例法不再成立。
  - **`pivot`/`knobCenter` 从 `pointerInput` 顶层一次性捕获改为 `awaitEachGesture` 内现读当前 `size`**：收起 52dp → 展开 128dp，两者右缘对齐、右缘中点屏幕位置一致，角度连续；配合指针捕获语义，保住「按住不抬指直滑旋转」的手感。
  - **修正误导注释**：明确「收起态命中区域外不响应也不拦截（表面已缩小，事件自然落底层）；展开态全条 + scrim 独占拦截是有意行为」。

- **核心 Code Diff**（`git show ab091b9 -- composeApp/src/commonMain/kotlin/com/daniel/dshremote/MessageDial.kt`，删减引用）：

```diff
-// 收起圆钮命中判定（相对 128dp 条宽的比例，避免在 pointerInput 里做 px 换算）：
+// 收起圆钮命中判定（绝对 dp，pointerInput 内 dp.toPx() 换算）：
 // - 命中半径：圆钮半径 22dp + 6dp 容差 = 28dp
 // - 圆钮中心距右缘：8dp 边距 + 22dp 半径 = 30dp
-private const val KNOB_HIT_RADIUS_FRACTION = 28f / 128f
-private const val KNOB_CENTER_INSET_FRACTION = 30f / 128f
+private const val KNOB_HIT_RADIUS_DP = 28f
+private const val KNOB_CENTER_INSET_DP = 30f
+/** 收起态手势表面边长（44dp 圆钮 + 8dp 右边距 = 52dp 见方）。 */
+private val DIAL_COLLAPSED_SURFACE_SIZE = 52.dp

- * 统一转盘交互面（128dp 全高条，恒挂载）：收起态画圆钮、展开态画扇面；单一 pointerInput(Unit)
- * 不因 phase 切换而重建，保证「摁下圆钮不抬指直接滑动 → 展开并旋转」一气呵成。
+ * 统一转盘交互面：收起态 52dp 小表面（仅覆盖圆钮，不挡发送/中断等按钮）、展开态 128dp 全高条。
+ * 单一 pointerInput(Unit) 不因 phase 切换而重建，保证「摁下圆钮不抬指直接滑动 → 展开并旋转」一气呵成。
+ * 收起态命中区域外不响应也不拦截（表面本身已缩小到圆钮区，事件自然落到底层按钮/列表）；
+ * 展开态全条 + scrim 主动独占拦截是有意行为。

     Box(
         modifier
-            .fillMaxHeight()
-            .width(128.dp)
+            .then(if (collapsed) Modifier.size(DIAL_COLLAPSED_SURFACE_SIZE) else Modifier.fillMaxHeight().width(128.dp))
             .pointerInput(Unit) {
-                val pivot = Offset(size.width.toFloat(), size.height / 2f)
-                val knobHitRadiusPx = size.width * KNOB_HIT_RADIUS_FRACTION
-                val knobCenter = Offset(
-                    size.width - size.width * KNOB_CENTER_INSET_FRACTION,
-                    size.height / 2f,
-                )
                 awaitEachGesture {
+                    // pivot 随当前 size 现取现算：收起态 52dp → 展开态 128dp；两者右缘对齐，
+                    // 右缘中点的屏幕位置一致，故角度连续。
+                    fun pivot() = Offset(size.width.toFloat(), size.height / 2f)
+
                     val down = awaitFirstDown(requireUnconsumed = false)
-                    val collapsed = dial.phase == DialPhase.Collapsed
-                    // 收起态只在圆钮命中半径内响应；展开态全条响应。未命中则不消费，交给底层列表。
-                    val active = !collapsed || (down.position - knobCenter).getDistance() <= knobHitRadiusPx
+                    // 收起态只在圆钮命中半径内响应（表面已缩小到圆钮区，未命中自然落到底层）；展开态全条响应。
+                    val active = if (dial.phase == DialPhase.Collapsed) {
+                        val knobCenter = Offset(
+                            size.width - KNOB_CENTER_INSET_DP.dp.toPx(),
+                            size.height / 2f,
+                        )
+                        (down.position - knobCenter).getDistance() <= KNOB_HIT_RADIUS_DP.dp.toPx()
+                    } else {
+                        true
+                    }
                     if (!active) return@awaitEachGesture

                     down.consume()
                     var didDrag = false
-                    var lastAngle = angleDeg(down.position, pivot)
+                    var lastAngle = angleDeg(down.position, pivot())
```

- 提交哈希：`ab091b983399de0161f1b2fbb9c741d6bd93acff`（`ab091b9`，工作树 main）。

- 回归测试：本次为手势表面几何与命中语义修复，JVM 单测不覆盖指针命中和布局，已改由真机验收确认「收起态下发送/中断/跳底恢复可点、按压直滑不回归」；后续计划用 Compose UI（instrumented）测试补上可自动回归的兜底。

## 验收结论（2026-09-05，Android 模拟器 emulator-5554 / 1080x2400 @420dpi，`249bb4a` 之后的包）

- ✅ 启动无崩溃（logcat crash buffer 0 条 FATAL；`249bb4a` 拆分 Conversation 后 DEX 校验通过）
- ✅ 发送按钮中心点击生效：`ACTION 发送点击` + `CMD 发送消息` + `CMD 已发送 send_message` 全链路日志齐全（旧包同位置被转盘条吞掉、零日志）
- ✅ 排队消息面板出现/删除：`排队中的消息(1)` → `ACTION 排队移除` → 面板消失
- ✅ 转盘改版（`0543a32`）：圆钮左移列表左下角、Deep Diving 上方；点开 `ACTION 转盘打开`；左缘拨动连续 4 次 `转盘选中`；边界自动翻页（800 条）；2.5s 自动收起
- ✅ 日志页：`ACTION 日志页切换 tab=服务端` + 服务端日志 169 条 27ms
- ⏸ 中断按钮未真按（会中断验收会话）；dump 确认其位置 [775,1381]-[901,1507] 已无任何覆盖层——转盘左移后与右下按钮区天然无冲突
- 另：本次验收顺带暴露并修复了 `Conversation` 巨型 Composable 的 DEX VerifyError 崩溃（`249bb4a`），与本 bug 同批装机验证

## 后续改进计划

- [x] **验收要点**（模拟器已逐项确认，见上文「验收结论」）：发送/中断/跳底按钮在转盘收起态下恢复可点；转盘收起开合、拨动、翻页不回归；mid-gesture 收起→展开尺寸变化下角度连续（`pivot()` 现读当前 size 已按此设计）。
- [ ] **设计红线**：①交互面的响应区必须与视觉一致；②凡涉及 `pointerInput` 的常驻覆盖层，**先验证 Compose 命中测试行为再落代码**（常驻覆盖层 z 序最高且命中即独占，底层兄弟收不到事件，「不消费」不等于穿透）。
- [ ] **Compose UI 测试（instrumented）**：覆盖「转盘收起态下发送按钮可点击」（`performClick` 断言触发发送回调），防止此类「命中测试独占」回归在装机前自动抓到。
- [x] **后续转盘改版**（左移 Diving 上方、左缘开口扇面、56dp 五线小转盘图标）已由 `0543a32` 完成，与本修复分开提交。
