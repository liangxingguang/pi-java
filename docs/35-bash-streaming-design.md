# 35 - 包⑧：bash 的流式部分结果（B46）

> **文档约定**（用户 2026-09-19）：一个功能模块一份文档；`docs/32` 是唯一索引。
> 本文件按新流程：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**；
> **用户审核 §8 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：命题**已逐条核过**；§8 待审核。

---

## 1. 这一包解决什么

**`tool_execution_update` 在生产上永不发射** —— pi-java 的**没有任何一个内置工具**调
`onUpdate`，所以包⑦ 刚接通的那条线**只有测试桩能行使**。

**范围比登记时窄得多**（见 §3-R1）：pi 侧**只有 `bash` 一家**真的流式发部分结果；
`read`/`write`/`edit`/`grep`/`find`/`ls` 六家一律**声明但不用**（`_onUpdate?`）。
故本包**只做 bash 一条**。

| 处 | 现状 | 应当 |
|---|---|---|
| `BashTool.execute` | 阻塞调 `shell().execute(...)`，返回后一次性构造结果 | 起手发一条**空**部分结果；运行中按 **100 ms 节流**发**累积快照**；收尾再冲一次 |
| `ShellExecutor.execute` | **阻塞**，返回整份 `ShellResult`，无任何中途回调 | 提供一个输出汇（sink），每读到一块就报一次 |
| `DefaultShellExecutor` | 内部**已经是 8 KiB 分块读**（`:80-90`）—— 循环就在那儿，只是没人订阅 | 在同一个循环里调 sink |

---

## 2. 命题表

**判定口径**：`已核`＝本文件写下当轮**逐行读过**并附出处；`待核`＝尚未读、不许当事实用。

### 2.1 pi 侧（行为定义）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | `AgentToolUpdateCallback<T> = (partialResult: AgentToolResult<T>) => void`，**作用域是当前 `execute()` 调用**；工具 promise 落定后的调用被忽略 | `agent/src/types.ts:384`（类型）、`:378-383`（该 javadoc 原文） | **已核** |
| P2 | 部分结果的形状 = `AgentToolResult`：`{content, details, usage?, addedToolNames?, terminate?}` | `agent/src/types.ts:362-376` | **已核** |
| P3 | **pi 的 7 个内置工具里只有 `bash` 真的调 `onUpdate`**；`read`/`write`/`edit`/`grep`/`find`/`ls` 声明为 `_onUpdate?`（下划线＝故意未使用）且全文再无引用 | `bash.ts:265`、`:297`（调用）；`read.ts:82`/`write.ts:62`/`edit.ts:159`/`grep.ts:101`/`find.ts:85`/`ls.ts:69`（声明）。**本轮独立复核**：`grep -n "_onUpdate\|onUpdate("` 全部命中如上 | **已核** |
| P4 | `powershell` **复用** `createShellToolDefinition` 故同样流式；它自己没有一行 update 逻辑 | `powershell.ts:49-57` | **已核** |
| P5 | **起手先发一条空的部分结果**：`{ content: [], details: undefined }`，在任何进程被拉起**之前** | `bash.ts:296-298` | **已核** |
| P6 | 运行中每收一块调用 `handleData` → `scheduleOutputUpdate`；**节流 100 ms**，是**节流不是防抖**：距上次 ≥100 ms 就立即发（前沿），否则挂一个尾沿定时器；`lastUpdateAt` 初值 0 ⇒ **第一块总是立即发** | `bash.ts:300-304`（handleData）、`:281-294`（scheduleOutputUpdate）、`:257-258`（dirty/lastUpdateAt）；常量 `renderers/bash.ts:19` = `100` | **已核** |
| P7 | 载荷是**累积快照**（不是增量）——客户端「整块替换」即可 | `bash.ts:264` 取 `output.snapshot(...)`；`output-accumulator.ts:91-119`（快照 = 对**整段已累积尾文**再做 `truncateTail`）；`:196-203`（`getSnapshotText` 返回 `tailText`，`append` 时累加）；官方文档原文 `docs/rpc.md:1055`「contains the accumulated output so far (not just the delta)」 | **已核** |
| P8 | 快照是**被截断的尾窗**（2000 行 / 50 KiB），一旦触发截断，**相邻快照不是前缀单调的**（窗口在滑） | `truncate.ts:11-12`（`DEFAULT_MAX_LINES=2000`、`DEFAULT_MAX_BYTES=50*1024`）、`output-accumulator.ts:60`/`:157`（内存尾环上限 `maxBytes*2`） | **已核** |
| P9 | 收尾在 `finishOutput` 里**再冲一次**（`acceptingOutput=false` 后），`finally` 只清定时器、**不**补发 | `bash.ts:306-314`、`:367-369` | **已核** |
| P10 | `details` = `BashToolDetails`：`{truncation?: TruncationResult, fullOutputPath?: string}`；截断未发生时 `truncation` 被**赋 `undefined`**（键在对象里、值是 undefined ⇒ `JSON.stringify` 省略） | `bash.ts:49-52`、`:266-270` | **已核** |
| P11 | 落定后的 `update` 被丢弃：`agent-loop.ts` 用 `acceptingUpdates` 闩（`:691` 设 false 于 `:705`/`:709`/`:716`），且收集到的 promise 在定稿前 `await` 掉 | `agent-loop.ts:692-706` | **已核** |
| P12 | 消费者语义 = **整块替换**（不是追加）：TUI 收到 update 就 `component.updateResult({...event.partialResult, isError:false}, true)` | `interactive-mode.ts:3348-3353` | **已核** |

### 2.2 pi-java 侧（现状）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P13 | `ToolUpdateCallback<TDetails>` 已存在且语义与 pi 同：`void onUpdate(ToolResult<TDetails> partialResult)`，javadoc 明写「落定后的调用被忽略」 | `ToolUpdateCallback.java:10-13` | **已核** |
| P14 | `BashTool.execute` 收 `ToolUpdateCallback<BashDetails> onUpdate` 但**一次都不调** | `BashTool.java:96-97`（签名）、全方法体（`:96-165`）无调用 | **已核** |
| P15 | `BashTool` 阻塞调 `context.shell().execute(command, options)`，拿到整份 `ShellResult` 后才做截断与结果构造 | `BashTool.java:115-165` | **已核** |
| P16 | `ShellExecutor` 是**阻塞**接口：`ShellResult execute(String command, ShellOptions options)` —— **无中途回调** | `ShellExecutor.java:9-11` | **已核** |
| P17 | `DefaultShellExecutor` 内部**已经是分块读**：8 KiB 缓冲 + `while ((n = is.read(buf)) != -1)` 写进 `ByteArrayOutputStream` | `DefaultShellExecutor.java:76-92` | **已核** |
| P18 | 改动面很窄：`ShellExecutor` 实现者**只有 1 个**（`DefaultShellExecutor`）；`new ShellOptions(...)` 构造点**只有 3 处**（`BashTool:110`、`AgentSession:905`、`DefaultShellExecutorTest:57`）；`shell().execute(...)` 调用点**只有 2 处**（`BashTool:115`、`AgentSession:905`） | 全仓 grep（见 §4-D） | **已核** |
| P19 | `BashDetails(TruncationUtils.TruncationResult truncation, String fullOutputPath)` 与 pi 的 `BashToolDetails` 同形 | `BashTool.java:42` vs `bash.ts:49-52` | **已核** |
| P20 | `TruncationUtils.truncateTail(content)` 已有，且常量与 pi **不同**：`DEFAULT_MAX_LINES=2000`（同）、`DEFAULT_MAX_BYTES=100_000`（**pi 是 50×1024=51200**） | `TruncationUtils.java:15-16` | **已核** |
| P21 | pi-java **没有** `OutputAccumulator` 的对应物（`tool/` 下无此类） | `ls pi-java-agent-core/.../tool/` | **已核** |
| P22 | 包⑦ 已把 `tool_execution_update` 接到会话层与两个消费面（RPC 线 + web），所以本包**只补生产者** | `docs/34 §10` | **已核** |
| P23 | `PiToolRunner:158` 已把工具侧的 `onUpdate` 回调转成 `PiLoop.Event.ToolExecutionUpdate`，且**落定后的调用被丢弃**（既有夹具 `updatesAfterExecuteReturnsAreDiscarded` 钉住） | `PiToolRunner.java:158`、`PiToolRunnerTest.java:287-288` | **已核** |

---

## 3. 被推翻的命题

| # | 我原先说的 | 实测 | 后果 |
|---|---|---|---|
| **R1** | **「pi 侧 7 个工具全都有 `onUpdate`」**（B46 的登记理由） | **错**：那是拿 `grep -l onUpdate` 数出来的，**命中的是声明不是调用**。逐行核过：pi 侧只有 `bash.ts` 真调（`:265`/`:297`），`read`/`write`/`edit`/`grep`/`find`/`ls` 六家一律 `_onUpdate?`（**下划线＝故意未使用**，TS 的约定）且全文再无引用 | ⇒ **B46 的真实范围只有 bash 一家**，不是 7 个工具。台账已更正（`docs/32` B46 行）。**这是「拿 grep 计数代替读码」的又一次代价** |
| **R2** | 「给 7 个工具各自决定『什么是部分结果』」（B46 登记时的规模判断） | **错**：六家**没有**部分结果这个概念 —— pi 自己就没流式它们（`grep.ts:217` 的注释原文「Collect matches during streaming, then format them after rg exits.」是**刻意的**） | ⇒ 本包不是「7 个工具的小改动集」，而是「1 个工具的流式改造」 |
| **R3** | 「bash 的流式要动 `ShellExecutor` 接口，属重接线」 | **半错**：接口确实要动，但改动面窄（1 个实现者 / 3 处构造 / 2 处调用），且 `DefaultShellExecutor` **内部已经是分块读**（8 KiB）—— 循环早就在那儿，只是没人订阅 | ⇒ 是**接一根线**，不是重写 |

---

## 4. 审计（顺带发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **pi-java 没有 `OutputAccumulator` 的对应物**：`DefaultShellExecutor` 把**全部**输出收进 `ByteArrayOutputStream`（无上限），截断只在**收尾时**做一次 | `DefaultShellExecutor.java:76`/`:88`、`BashTool.java:131` | 今天如此 ⇒ 本包的快照从「完整缓冲」里截尾即可，**不新增环形缓冲**（§6-3）。⚠️ 无上限累积是**既有**问题，另计 |
| **B** | `DEFAULT_MAX_BYTES` 两仓**不同**：pi-java `100_000`、pi `51200` | `TruncationUtils.java:16` vs `truncate.ts:12` | **不是本包**（它影响终局截断阈值，是独立的口径问题）⇒ 登记，别顺手改 |
| **C** | pi 的 `update` 载荷里的 `details.truncation` 在**未截断**时被显式赋 `undefined`（键在、值 undefined ⇒ `JSON.stringify` 省略）；pi-java 的 `BashDetails` 是 record，`null` 会被包⑦ 的 `toolPayload` 投影按「缺席即省略」处理 | `bash.ts:266-270`、`docs/34 §8.0 裁决 B` | 落到线上**同形**（都省略）⇒ 无需额外处理，但**要在夹具里钉住** |
| **D** | 改动面（P18）：实现者 1、`ShellOptions` 构造 3、`execute` 调用 2 | 全仓 grep | 支持「接口可动」的结论 |
| **E** | pi 的 agent 包另有一个 **harness bash**（`harness/tools/bash.ts`），语义相同但节流换成 `BASH_CHECKPOINT_INTERVAL_MS = 2000` 的 checkpoint | 报告 §9 | pi-java 只有一条 bash 车道（`PiToolRunner` + `BashTool`）⇒ **不适用**，登记备查 |

---

## 5. 待核 / 裁决点

| # | 问题 | 结论 |
|---|---|---|
| **N1** | 输出汇怎么穿过 `ShellExecutor` | **裁决点 A**（见 §8.0） |
| **N2** | 节流值 | pi 是 **100 ms**（`renderers/bash.ts:19`）⇒ 照抄，见裁决点 B |
| **N3** | 快照从哪来 | `DefaultShellExecutor` 已持有完整缓冲；sink 收**增量**、自己攒「截断尾窗」，避免每块都复制整份（O(n²)）。见 §8.2 |
| **N4** | RPC 的 `bash` 命令（`AgentSession.executeBash`）要不要也流式 | **不做**（§6-4）：pi-java 该处 javadoc 原文「v1 阻塞执行，不增量流式」，且它是**独立功能**（用户发起的 shell），与工具调用的 `tool_execution_update` 不是一回事 |
| **N5** | 空起始载荷的 `details` | pi 是 `details: undefined` ⇒ Java `null`；`content` 是**空数组**（不是空文本块）。夹具要钉住「零 content 块」这个形状 |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 给 `read`/`write`/`edit`/`grep`/`find`/`ls` 加流式 | **pi 自己就没有**（P3/R2）⇒ 加了反而**偏离**判据 |
| 2 | 动 `DEFAULT_MAX_BYTES`（§4-B） | 影响终局截断阈值，是独立口径问题 |
| 3 | 新建 `OutputAccumulator` 环形缓冲 | 今天 `DefaultShellExecutor` 已收全文，快照从那里截尾即可；加环形缓冲是**性能优化**、不是行为对齐 |
| 4 | RPC `bash` 命令的流式（N4） | 另一件事（用户发起的 shell），且 pi-java 已明写「v1 阻塞」 |
| 5 | 改包⑦ 已接好的三个消费面 | 本包只补**生产者**（P22） |

---

## 8. 实施稿（步骤 ③ 的产物；**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决 | 后果 |
|---|---|---|
| **A** | 汇走 **`ShellOptions` 的第五个组件**（`ShellOutputSink sink`，**可空**） | 只动 3 处构造；`execute` 签名不动。⚠️ 备选：给 `execute` 加参数（要动接口 + 2 处调用）或新开 `StreamingShellExecutor` 接口（类型派发、要 feature-detect）——不取 |
| **B** | 节流 **100 ms**（照抄 pi 的 `BASH_UPDATE_THROTTLE_MS`），前沿立即发、尾沿挂定时器 | 与 pi 同值；⚠️ **但 pi-java 的 `PiLaneSink` 全程串行化**（`docs/31 §8.23`）⇒ 定时器线程进 `emit` 会被那把锁串行化，需要确认「从定时器线程调 sink」不会与工具执行线程死锁 |
| **C** | sink 收**增量**、自己攒尾窗；快照 = `TruncationUtils.truncateTail(已攒的尾文, pi 的两个上限)` | 避免每块复制整份缓冲（O(n²)）。⚠️ 上限用 **pi 的值**（2000 行 / 51200 字节），**不用** pi-java 的 `DEFAULT_MAX_BYTES=100_000` —— 因为这是**载荷快照**的口径、照 pi 走；终局截断仍是 pi-java 现值（§6-2） |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-agent-core` | `ShellOutputSink`（新）＋ `ShellOptions` 加可空组件 ＋ `DefaultShellExecutor` 在既有读循环里报增量 |
| **2** | `pi-java-agent-core` | `BashTool`：起手空载荷 ＋ 节流 sink 适配 ＋ 收尾补冲 |
| **3** | `docs/35`＋`docs/32` | 实施记录 ＋ B46 结案 |

### 8.2 精确改动（形状）

**(1) 新 `ShellOutputSink`**：

```java
/** 执行中收到输出增量的汇（pi 的 {@code onUpdate} 在 shell 层的对应物）。 */
@FunctionalInterface
public interface ShellOutputSink {
    /** @param delta 自上次调用以来新到的输出（**增量**，不是累积） */
    void onOutput(String delta);
}
```

**(2) `ShellOptions` 加第五个组件** `ShellOutputSink outputSink`（可空；`null` ＝ 不订阅）。
⚠️ 它是 record ⇒ 3 处构造点各加一个实参；既有测试的构造加 `null`。

**(3) `DefaultShellExecutor`** —— 在 `:83-89` 的循环里、`output.write(buf, 0, n)` 之后：

```java
if (sink != null) {
    sink.onOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
}
```

⚠️ **分块的边界会切断多字节字符** ⇒ 需要按字节攒到字符边界再报（或用 `CharsetDecoder` 的增量态）。**这是本包最容易写错的一处**，必须有夹具（§8.3 ③）。

**(4) `BashTool.execute`**：

```java
// 起手：pi bash.ts:296-298 的空载荷（content 是**空数组**，不是空文本块）
onUpdate.onUpdate(new ToolResult<>(List.of(), null, null, false, List.of()));
...
var options = new ShellOptions(..., new ThrottledTailSink(onUpdate, throttleMs));
```

`ThrottledTailSink` 是 pi-java 侧新增的小类（照 pi `bash.ts:255-298` 的三个位：
`updateDirty` / `lastUpdateAt` / 定时器），在 `flush()` 里发
`new ToolResult<>(List.of(new TextContent(snapshot)), details, null, false, List.of())`。

**(5) 收尾**：`shell().execute(...)` 返回后、构造终局结果**之前**，调一次 `sink.flush()`
（pi 的 `finishOutput`，`bash.ts:306-314`）。

### 8.3 测试计划（**先红**）

| 模块 | 夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-agent-core` | `ShellOutputSinkTest` | 3 | ① `ShellOptions` 带 sink 时，执行中**至少收到一次**增量、拼起来 = 完整输出；② sink 为 `null` 时行为与今天**逐字相同**（回归）；③ **多字节字符被分块切断**时不产生乱码（`charset` 边界） |
| 同上 | `BashToolUpdateTest` | 4 | ④ 起手那条**空载荷**（`content` 为空数组、`details` 为 null）；⑤ 运行中的载荷是**累积快照**（第 N 条 ⊇ 第 N-1 条的内容，未截断时）；⑥ `details.truncation` 只在**真截断**时有值；⑦ `update` 全部早于终局结果（落定后无 update） |
| 同上 | 节流 | 2 | ⑧ 高频小增量在 100 ms 窗口内**合并**（不逐块发）；⑨ ≥100 ms 之后的**第一块立即发**（前沿语义） |

**先红**：夹具按目标形状写，实施前先跑一次并**记录红集**。
**既有测试**：`DefaultShellExecutorTest` 的 `ShellOptions` 构造 +1 个 `null` 实参
（机械，不算行为改动）；`BashToolTest` 应保持绿（`onUpdate` 为 null 时路径不变）。

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | 起手不发空载荷 | ④ 有牙 |
| P2 | 载荷改成**增量**（不是累积） | ⑤ 有牙 |
| P3 | 去掉 100 ms 节流 | ⑧ 有牙 |
| P4 | 收尾不补冲 | ⑦ 有牙 |
| P5 | 多字节按 `new String(buf,0,n)` 直接切 | ③ 有牙 |

### 8.5 未覆盖（预登记）

- **端到端**：真实长命令在 web 上的连续刷新未测（需运行前端）。
- **死锁**：裁决 B 的 ⚠️（定时器线程 → `PiLaneSink.emit` 的锁）需要一次**构造性**用例，
  若写不出确定性夹具，则如实记为未覆盖（本仓口径：不钉「等延迟下的顺序」这种运气断言）。
- **截断窗滑动**的载荷（P8：相邻快照非前缀单调）—— 难造且无消费者可证，本轮不钉。

---

## 9. 下一步

用户审核 §8（含 §8.0 三个裁决点）之后才写代码；实施完成后追加**实施记录**。
