# 22 - L7 W1 状态面设计稿：loader/spinner、working 指示器、bordered/cancellable loader（目标锚点 `a00a5048d`）

> 【背景摘要】`docs/21` §7 把 W1 定为「每轮都走」的最大缺口：**C5 loader/spinner(w3)、C6 working 指示器(w3)、C7 bordered loader(w1)、C8 cancellable loader(w1)、C10 文案复核**。当前 Java 状态槽只有静态文本（`StatusBar.java:27-31` 是静态 `● running`；指示器三变体无 spinner）。本稿是 spec＋实施计划：先设计、经审核、再实施。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | C5–C8 四行从「缺失」翻「对齐」；C10 维持「对齐」＋复核注；W1 段预期读数 +7~8 |
| 行为不变量 | 指示器单槽＋kind 守卫语义不变；retry/compaction/branchSummary 文案逐字不变；扩展 `ExtensionUI` 接口面不新增成员（只补 TUI 臂实现）；不引入新模块 |
| 完成判据 | ①`mvn test -pl pi-java-tui -am` 全绿（JDK25）；②`map/05` C5–C8/C10 逐行改判＋`docs/05` §1.2／`docs/09`／`docs/04` §0 回填 |
| 不做项 | 缓存预热合并提示、pasteToEditor 重绘（docs/21 §3 已登记漏项，不在 W1）；terminal progress（N5）；embedded editor indicator（N1） |

## 2. 上游证据（逐项，均对 `a00a5048d`）

### 2.1 C5 `tui/src/components/loader.ts`（101 行）

- 帧表 `["⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"]`、`DEFAULT_INTERVAL_MS = 80`。
- `setIndicator(indicator?)`：给 options 时**自定义帧逐字渲染**（`renderIndicatorVerbatim`，不加 spinner 色）；不给时默认帧过 `spinnerColorFn`。`intervalMs <= 0` 回默认。
- `frames=[]` ⇒ 无定时器且 `frames[0] ?? ""` ⇒ **无 spinner 符号、message 保留**；单帧 ⇒ 静态（无定时器）。
- `updateDisplay()` 每帧重算 `spinner + " " + messageColorFn(message)` 并 `ui.requestRender()`。

### 2.2 C6 `modes/interactive/components/status-indicator.ts` + `interactive-mode.ts` 接线

- `StatusIndicator extends Loader`，四 kind：`working`/`retry`/`compaction`/`branchSummary`——**全部都是 Loader ⇒ 全带 spinner**。
- 配色：working spinner `accent`（embedded 时改编辑器 thinking border 色，见 N1）＋message `muted`；retry spinner `warning`；compaction/branchSummary spinner `accent`。
- 单槽接线：`showStatusIndicator`（:2512，先试 embedded，否则进 `statusContainer`）；`clearStatusIndicator(kind?)`（:2525，kind 守卫第一句）；`showWorkingStatusIndicator`（:2546，message=`workingMessage ?? "Working"`）；`setWorkingVisible`（:2561，false ⇒ 清 working，true 且 streaming 且非 working ⇒ 置）；`setWorkingIndicator`（:2574，活动 working 直接换帧表）。
- 事件：`turn_start`（:3612-3627）——`workingVisible` 为真且槽内非 working ⇒ 置 working；否则 `clearStatusIndicator()` 全清；`agent_end`（:3848）——`clearStatusIndicator("working")`（守卫 ⇒ 不误清并发 compaction）。
- 扩展面：`setWorkingMessage`（:2840，null ⇒ 回默认）、`setWorkingVisible`（:2847）、`setWorkingIndicator`（:2844 面上方）。
- `resetExtensionUI`（:2665-2682）：复位 `workingMessage=undefined / workingVisible=true / setWorkingIndicator()`，且活动 working 时把 message 置为 **`"Working (esc to interrupt)"`**（pi 原样 quirk，逐字保留）。
- `IdleStatus`：regular 模式 clearOnShrink 时回填两空行（见 N2）。

### 2.3 C7 `modes/interactive/components/bordered-loader.ts` + C8 `tui/src/components/cancellable-loader.ts`

- C7：`DynamicBorder`（`"─" × max(1,width)`，`theme.fg("border")` 色）＋ loader（cancellable ⇒ `CancellableLoader`，否则 `Loader`）＋ `Spacer(1)` ＋ cancel hint（`keyHint("tui.select.cancel","cancel")`）＋ `Spacer(1)` ＋ border。消费点：`bug-report.ts:275`（`/bug` 摘要与上传）、`session-share.ts:114/:167`（`Uploading to Radius...`/`Creating gist...`）。
- C8：`CancellableLoader extends Loader`——`tui.select.cancel`（Esc）⇒ `abortController.abort()` ＋ `onAbort?.()`；暴露 `signal`/`aborted`。

### 2.4 C10 文案复核（本轮成立）

Java `StatusIndicator.java` 的 retry/compaction/branchSummary 三文案与 pi 逐字一致（`Retrying (n/m) in Ns... (esc to cancel)`、`Compacting context...`/`Context overflow detected, Auto-compacting...`/`Auto-compacting...`、`Summarizing branch... (esc to cancel)`）；working 默认文案 pi 为 `"Working"`（无省略号）。复核通过，无需改文案。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| 指示器单槽 | 已有（C10 对齐基础）：volatile 单槽＋kind 守卫＋三 record（textAt 时间驱动倒计时） | `SessionEventRouter.java:23,49-115,156-161`、`StatusIndicator.java` |
| spinner | 无——指示器渲染为 `" " + textAt(now)` 纯文本单 span | `ChatScreenLayout.java:104-106` |
| working 指示器 | 无——`TurnStart`/`AgentEnd` 落 `default -> {}` | `SessionEventRouter.java:113`；事件源已在：`SessionRunner.java:300` 发 TurnStart、`AgentSessionEvent.java:31,47` |
| 扩展三方法 | 接口已声明，default no-op（docs/28 §4 预留）；examples 已按 pi 逐字调用 | `ExtensionUI.java:84-91`、`TuiExtensionUI.java`（未覆写）、`WorkingMessageTestExtension.java:35-36` |
| working 选项 | `WorkingIndicatorOptions(frames, intervalMs)` 已存在 | `WorkingIndicatorOptions.java` |
| bordered loader | 无框无 spinner：`BugReportLoaderScreen`（纯文本＋esc abort）、`ShareFlow.Loader`（纯文本＋esc abort＋stage message 更新） | `BugReportLoaderScreen.java:68-73`、`ShareFlow.java:32-73` |
| cancellable 语义 | 已有：两 overlay 的 `isCancel ⇒ aborted + onAbort`（＝pi `signal.aborted`/`onAbort`） | 同上 |
| inline 唤醒 | `CountdownWake` 固定 1s 周期 | `CountdownWake.java:23` |
| 状态栏冗余 | `StatusBar` 静态 `● running`（指示器槽会整体替换状态行 ⇒ 流式期不并存；但 workingVisible=false 时仍显示静态点，pi 无此物） | `StatusBar.java:27-31` |

## 4. 设计（Java 方案）

### 4.1 新建 `tui/component/LoaderSpinner.java`（C5 落点）

pi `Loader` 的应用层函数式等价（窄差 N3）：不可变值＋时间驱动帧计算，无定时器。

```java
public final class LoaderSpinner {
    public static final List<String> DEFAULT_FRAMES = List.of("⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏");
    public static final long DEFAULT_INTERVAL_MS = 80;
    // frameAt：frames 为空 ⇒ ""（无 spinner 符号）；单帧 ⇒ frames[0]（静态）；
    // 多帧 ⇒ frames[((now-start)/intervalMs) % len]（时间驱动，无漂移）。
    public static String frameAt(List<String> frames, long intervalMs, long startNanos, long nowNanos);
}
```

### 4.2 `StatusIndicator` 扩展

- `Kind` 增 `WORKING`。
- 三既有 record 增加 `long startNanos` 首组件（spinner 起点＝建指示器时刻）；工厂 `retry(...)` 内部 `System.nanoTime()`，调用点（`SessionEventRouter` 2 处＋测试）随改。
- 新增 `String spinnerFrameAt(long nowNanos)`（各 record 按 frames/interval 计算；默认接口方法返回 `""`）；`ticking()` 语义扩为「倒计时或帧动画」（四 kind 全为真——pi 四变体全带 spinner）。
- 新 record：

```java
record Working(String message, List<String> frames, long intervalMs, long startNanos)
        implements StatusIndicator
```

  语义：frames 空 ⇒ 无 spinner 符号（pi `frames: []`）；单帧 ⇒ 静态逐字（custom frames verbatim，N4）；默认帧表 ⇒ spinner 加色（渲染层按 kind）。

### 4.3 `SessionEventRouter`（C6 事件与状态）

- 字段：`volatile String workingMessage`（null ⇒ 默认）、`volatile boolean workingVisible = true`、`volatile WorkingIndicatorOptions workingOptions`（null ⇒ 默认帧）；常量 `DEFAULT_WORKING_MESSAGE = "Working"`。
- 事件：
  - `case TurnStart` ⇒ pi :3612 逐字：`workingVisible` 且（槽空或 kind≠WORKING）⇒ `setIndicator(workingNow())`；不可见 ⇒ `clearIndicator(null)`。
  - `case AgentEnd` ⇒ `clearIndicator(WORKING)`（kind 守卫 ⇒ 不误清并发 compaction）。
- `workingNow()`：message＝`workingMessage ?? 默认`；frames/interval＝`workingOptions ?? 默认`；`startNanos=System.nanoTime()`。
- 新方法 `setWorkingMessage(String)` / `setWorkingVisible(boolean)` / `setWorkingIndicator(WorkingIndicatorOptions)`（pi :2840-2847：message 换若有活动 working 直接生效——record 不可变 ⇒ 替换为新 Working 保留 startNanos）。
- `resetWorkingState()`：三态复位＋活动 working ⇒ 替换为 message `"Working (esc to interrupt)"`（pi :2673-2682 quirk 逐字）。
- 重绘：事件到达的既有标脏路径不动；spinner 连续动画靠 4.5。

### 4.4 渲染与接线

- `ChatScreenLayout.statusBar()`：indicator 分支改 span 组——spinner 段（色按 kind：WORKING/COMPACTION/BRANCH_SUMMARY ⇒ `SemanticColors.accent()`，RETRY ⇒ `SemanticColors.warning()`）＋ `" "` ＋ message 段（`SemanticColors.muted()`），`TamboUIAdapter.richText(Text.from(Line.from(spans)))`（`ExtensionEditorOverlay.java:171-172` 先例形状）；无 spinner 帧（空帧表）⇒ 只 message 段。
- `TuiExtensionUI` 覆写三方法（形状同 `setStatus`：`host.dispatcher().dispatch(() -> host.chatScreen().setWorkingXxx(...))`）；`ChatScreen` 三委托方法转 router。
- `ChatScreen.resetExtensionUi()` 增调 `router.resetWorkingState()`（pi resetExtensionUI 同函数复位面）。
- `CountdownWake` `PERIOD` 1s → 80ms，javadoc 更新（Loader interval 对位：pi 四指示器全带 spinner ⇒ inline 12.5Hz 重绘本就是 pi 行为；retry 倒计时按帧重算结果不变）。
- `StatusBar`：删除静态 `● running` 分支（`running()` 方法）；状态行形状与 pi footer 一致（name/tokens/model）。无测试断言该文案。

### 4.5 C7/C8：`tui/screen/BorderedLoaderOverlay` 改造

- 新建共享渲染 helper `BorderedLoaderFrame`（静态方法）：入参 `spinnerFrame`、`message`、`cancellable`、宽度；产出 column＝border 行（`"─"`×宽，border 色——`ThemeToken` 无 border 则用 `dim` 近似并注记）＋ spinner+message 行（同 4.5 配色：spinner accent/message muted）＋ cancellable ⇒ `"(esc to cancel)"` 行 ＋ border。宽度用 `host.terminalCols()`（两 overlay 均可达）。
- `BugReportLoaderScreen.render()` / `ShareFlow.Loader.render()` 改调 helper；spinner 帧由各自 `startNanos`（构造时记）计算。abort/done/stage 逻辑不动（C8 语义已对位：`isCancel ⇒ aborted＋onAbort`）。

## 5. 窄差与不做项（提前写明）

| # | 上游能力 | Java 处置 | 判定 |
|---|---|---|---|
| N1 | embedded editor indicator：pi 编辑器组件支持 `setWorkingStatusIndicator`（:2505-2510），embedded 时 spinner/message 用编辑器 thinking border 色、渲进编辑器框 | Java 编辑器组件（TamboUI `EditorElement`）无该挂点 ⇒ 恒走状态槽、色恒 accent/muted（不随 thinking level） | **窄差 D** |
| N2 | `IdleStatus` 两空行＋regular `clearOnShrink` 回填（:2540-2545） | Java inline 无 clearOnShrink 语义 ⇒ 不做 | **窄差 D** |
| N3 | `Loader` 是 pi tui 框架组件（`Text` 子类＋setInterval 定时器＋invalidate 钩） | Java 无框架层组件模型 ⇒ `LoaderSpinner` 函数式等价：fullscreen 33ms 帧驱动重算＋inline 80ms 唤醒；行为等价（原 docs/31 §8.38.4 裁决点 B 同构） | **窄差注记（判对齐）** |
| N4 | custom frames verbatim（扩展自染色） | Java 同语义：`workingOptions.frames` 非默认帧表时渲染层不加色 | 逐字对位 |
| N5 | turn_start 的 `terminal.setProgress(true)`（:3614-3616） | Java 无对应面，随 K39/平台域 | 不做 |

## 6. 测试计划

| 测试 | 覆盖 |
|---|---|
| `LoaderSpinnerTest`（新） | 帧推进取模、空帧 ⇒ ""、单帧静态、interval 边界 |
| `StatusIndicatorTest`（改） | WORKING record、spinnerFrameAt、startNanos、ticking |
| `ChatScreenSessionEventTest`（改） | TurnStart ⇒ WORKING 置位（默认/自定义 message、自定义帧表）、AgentEnd ⇒ 清（kind 守卫保留 compaction）、workingVisible=false 不置＋全清、resetWorkingState 复位＋interrupt 文案 quirk |
| `TuiExtensionUI` 臂测试（新/改） | 三方法经 dispatcher 在渲染线程生效（沿用现有 fake host 模式） |
| `BorderedLoader` 渲染测试（新） | border 行、spinner 行、cancel hint、cancellable=false 无 hint |
| BugReport/ShareFlow 既有测试 | render 断言随新形状更新 |

定向收口：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am`。

## 7. 实施步骤（tasks）

1. ✅ `LoaderSpinner` ＋ 测试。
2. ✅ `StatusIndicator` 扩展（WORKING/startNanos/spinnerFrameAt）＋ 调用点随改。
3. ✅ `SessionEventRouter` working 三态＋TurnStart/AgentEnd＋reset。
4. ✅ `ChatScreenLayout` span 渲染＋`StatusBar` 删静态点＋`CountdownWake` 80ms。
5. ✅ `TuiExtensionUI` 三臂＋`ChatScreen` 委托＋resetExtensionUi 复位。
6. ✅ `BorderedLoaderFrame` ＋ BugReport/ShareFlow 改造。
7. ✅ 定向测试全绿（`mvn test -pl pi-java-tui -am`：712 tests 全绿）→ `map/05` C5–C8/C10 改判 → `docs/05` §1.2／`docs/09`／`docs/04` §0 回填。

> **收口结论（2026-11-23）**：全部落地，与设计一致——仅一处实施修正：`resetWorkingState` 的帧表回默认
> （pi resetExtensionUI 先 `setWorkingIndicator()` 再 setMessage ⇒ 帧表/间隔一并回默认，动画重启）。
> 读数：**186.5/244＝76.4%**（旧口径；投影回算 190.0/259＝73.4%）。窄差 N1/N2 随正式编号在 P12 落。
