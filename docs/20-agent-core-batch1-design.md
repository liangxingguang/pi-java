# agent-core 批 1 设计：B158 截断预算 ＋ B159 finishTurn ＋ #17 compact abort-first

锚点 `ea448f454`（v1.1.0-22）。范围＝用户裁决的三口（map/03 边际表前三）：
**B158**（#23，存疑→对齐，w3）、**B159＋#41**（#6，存疑→对齐，w3×2，同根）、
**#17**（缺失→对齐，w2）。预计加权 132.0 → 138.5/183 ≈ **75.7%**（+6.5）。

## 0. 本批范围

- **做**：① `TruncationUtils.DEFAULT_MAX_BYTES` 100_000 → 51_200（pi 50KB）；
  ② `shouldStopAfterTurn` 更名 `finishTurn` 且布尔改 `{action}` 裁决（循环层 B159
  ＋ HookSystem 层 #41 同一次改）；③ 手动 `/compact` 开头先 abort 在飞运行并等停。
- **不做**（各自有主）：`abortCompaction`（B36，#17 的 pi abort() 还中止在飞压缩，
  java 无此能力，本批只落「abort run＋等停」）；grep/find/ls 的参数面与 50KB 截断
  （map/03 §2.2 内置工具行，另案）；pi 扩展 `turn_end` boundary 的 entry 起草面
  （`_dispatchTurnEndBoundary`，扩展域 B221 族）；`AgentTurnContext.newMessages`
  之外的钩子面扩写。

## 1. B158：截断字节预算 → 50KB（map/03 #23）

### 1.1 pi 侧（`packages/coding-agent/src/core/tools/truncate.ts:11-12`）

```ts
export const DEFAULT_MAX_LINES = 2000;
export const DEFAULT_MAX_BYTES = 50 * 1024; // 50KB
```

行预算两侧早同（2000）；字节预算 pi 主流恒 **51200**。bash 工具描述
（`bash.ts:255`）与截断注记（`bash.ts:352`）都由该常量现算：

```ts
description: `Execute a ${config.shellName} command ... Output is truncated to last
  ${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). ...`
text += `\n\n[Showing lines ${startLine}-${endLine} of ${truncation.totalLines}
  (${formatSize(DEFAULT_MAX_BYTES)} limit). Full output: ${snapshot.fullOutputPath}]`;
```

### 1.2 java 现状（`agent/tool/TruncationUtils.java:17`）

```java
public static final long DEFAULT_MAX_BYTES = 100_000L;
```

消费面（全部由常量现算，改常量即全链对齐，无第二处字面量）：

| 落点 | 内容 |
|---|---|
| `BashTool.java:61` | 工具描述 `... or 97KB (whichever is hit first).` → `50KB` |
| `BashTool.java:154,166-175` | 终局 `truncateTail(output)` 默认重载；`"(50.0KB limit). Full output saved to: …"` |
| `ReadTool.java:45` | 工具描述同构 → `50KB` |
| `ReadTool.java:111,116-119,128` | 终局 `truncateHead(selectedContent)` 默认重载；首行超限注记与 `"(50.0KB limit). Use offset=…"` |
| `BashUpdateEmitter.java:156` | 流式快照**本就显式 51200**（刻意值，`:30-32` 注释）⇒ 改后两口径合一 |

`formatSize(51200)`＝`"50.0KB"`（`%.1fKB`，与 pi `formatSize` 的 `toFixed(1)` 同构）；
描述里的 `/ 1024`＝50 ⇒ `"50KB"` 与 pi 逐字同。

### 1.3 java 待写

```java
/** pi {@code truncate.ts:12}：50 * 1024。 */
public static final long DEFAULT_MAX_BYTES = 51_200L;
```

＋ `BashUpdateEmitter.java:30-32` 注释更新（「而不是 pi-java 的 100_000」「终局截断仍走
pi-java 现值」两句作废——快照与终局自此同口径 51200；显式实参保留，防回归靠夹具）。

**明确不动**：`truncateMiddle`（MCP 20 KiB 独立预算，`:166-167` 注释本就在）；
grep/find/ls 的参数面（另案）；`STRUCTURED_OUTPUT_MAX_BYTES = 1MiB`（bash.ts:24，pi 同值）。

### 1.4 夹具

既有 `TruncationUtilsTest` 全走显式实参 ⇒ 零红（恰证明默认重载此前无牙）。新增：
1. **边界对**：默认重载 51200 字节整不截、51201 截（head/tail 各一）；
2. **描述钉**：BashTool/ReadTool 的 `description()` 含 `"50KB"`（此前是 `"97KB"`）；
3. **模型可见注记**：BashTool 截断文本含 `"(50.0KB limit)"`、ReadTool 首行超限注记含
   `"50.0KB"` 与 `"head -c 51200"`（此前 `100000`）；
4. **快照一致性**：`BashUpdateEmitter` 显式值与 `DEFAULT_MAX_BYTES` 相等（防再分叉）。

## 2. B159＋#41：finishTurn 裁决形状（map/03 #6/#41）

### 2.1 pi 侧

**a. 类型（`packages/agent/src/types.ts:146-157`）**——布尔已成裁决对象：

```ts
/** Decision returned by {@link FinishTurn}. Returning undefined preserves normal scheduling. */
export type AgentTurnDecision = { action: "continue" } | { action: "end" };

export type FinishTurn = (
	turn: AgentTurnContext,
	signal?: AbortSignal,
) => AgentTurnDecision | void | Promise<AgentTurnDecision | undefined> | Promise<void>;
```

`AgentTurnContext`（`:134-144`）＝ `{message, toolResults, context, newMessages}`；
`PrepareNextTurnContext extends AgentTurnContext`（`:191`）——**两个钩子同一份上下文形状**。

**b. 循环（`packages/agent/src/agent-loop.ts`）**——三处行为：

错误路（`:250-256`）：finishTurn **仍被调用**（裁决忽略），随后 turn_end/agent_end：

```ts
				await config.finishTurn?.(lastCompletedTurn, signal);
				await emit({ type: "turn_end", message, toolResults: [] });
				await emit({ type: "agent_end", messages: newMessages });
				return;
```

正常路（`:286-298`）：**先 finishTurn 后 emit turn_end**；`end` 直接收口；
`continue` 记 `explicitContinuation`，被工具结果/steering 满足则清零：

```ts
			const decision = await config.finishTurn?.(lastCompletedTurn, signal);
			await emit({ type: "turn_end", message, toolResults });

			if (decision?.action === "end") {
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}

			explicitContinuation = decision?.action === "continue";
			pendingMessages = (await config.getSteeringMessages?.()) || [];
			if (hasMoreToolCalls || pendingMessages.length > 0) {
				explicitContinuation = false;
			}
```

内层结束后（`:300-315`）：follow-up 满足也清零；否则**白送一轮 context-only 请求**：

```ts
		const followUpMessages = (await config.getFollowUpMessages?.()) || [];
		if (followUpMessages.length > 0) {
			explicitContinuation = false;
			pendingMessages = followUpMessages;
			continue;
		}
		if (explicitContinuation) {
			explicitContinuation = false;
			continue;
		}
		break;
```

**c. 会话层链接（`coding-agent/.../agent-session.ts:887-896`）**——END 先赢、否则 CONTINUE：

```ts
		this.agent.finishTurn = async (turn, signal) => {
			this._boundaryDispatchedMessages.add(turn.message);
			const extensionContinue = await this._dispatchTurnEndBoundary(turn.message, turn.toolResults);
			const previousDecision = await previousFinishTurn?.(turn, signal);
			if (previousDecision?.action === "end") return previousDecision;
			if (extensionContinue || previousDecision?.action === "continue") return { action: "continue" };
			return undefined;
		};
```

### 2.2 java 现状与差距

| # | pi | java 现状 | 差距 |
|---|---|---|---|
| ① | `AgentTurnDecision {continue\|end} / undefined` | `PiLoop.StopHook` 返 `boolean`（真＝停） | 「continue 强制多跑一轮」**表达不了** |
| ② | 错误路 finishTurn 照调（裁决忽略） | `PiLoopRunner:104-108` 错误路**不调钩子** | 少一次钩子调用 |
| ③ | finishTurn 在 **emit turn_end 之前** | `PiLoopRunner:126` 先 `TurnEnd` 后钩子（:134） | 顺序反（`:131-133` 注释是旧锚行号，已过时） |
| ④ | `explicitContinuation` 三段清零 | 无此概念 | continue 满足语义整体缺 |
| ⑤ | `AgentTurnContext.newMessages` | `PiLoop.NextTurnContext` 三组件无它 | 钩子看不到本轮新消息 |
| ⑥ | 会话层链接：END 先赢、否则 CONTINUE | `HookSystem.fireShouldStopAfterTurn` 首个非 null 即返（`Boolean.FALSE`＝「不许停」是 java 方言，pi 无此物） | 多钩子合并语义不同 |
| ⑦ | 更名 `finishTurn` | `shouldStopAfterTurn` / `should_stop_after_turn` | 名随义走 |

### 2.3 java 待写

**a. `PiLoop.java`**——`StopHook` 退役，裁决类型入循环面（pi 定义在 agent 的 types.ts，
故枚举放 harness 包；hook 包已依赖 harness，无新环）：

```java
/** pi {@code AgentTurnDecision}（types.ts:146）；纯常量闭集按规范用 enum。 */
public enum TurnDecision { CONTINUE, END }

/**
 * pi {@code FinishTurn}（types.ts:148-157）：assistant 与其工具结果全部落完后、
 * emit {@code turn_end} 之前调用。{@code END} 直接收口（不再查队列）；
 * {@code CONTINUE} 保证再来一次 provider 请求（被工具结果/steering/follow-up 满足
 * 则不额外发）；返回 {@code null} ≙ pi 的 undefined（照常调度）。
 */
@FunctionalInterface
public interface FinishTurnHook {
    TurnDecision apply(NextTurnContext context);
}
```

`NextTurnContext` 加第 4 组件（pi `AgentTurnContext.newMessages`，`PrepareNextTurnContext`
继承同形 ⇒ java 两个钩子共用一个 record 的既有形状不动）：

```java
public record NextTurnContext(Message.AssistantMessage message,
                              List<Message.ToolResultMessage> toolResults,
                              Context context,
                              /** pi AgentTurnContext.newMessages（本轮 run 的新消息，只读视图）。 */
                              List<Message> newMessages) {}
```

`Config` 组件 `shouldStopAfterTurn` → `finishTurn`（类型 `FinishTurnHook`）。

**b. `PiLoopRunner.java`**——按 §2.1-b/c 重排（关键段待写）：

```java
// 错误路（pi :250-256）：钩子照调、裁决忽略，错误/中止仍是硬出口。
if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
    if (config.finishTurn() != null) {
        config.finishTurn().apply(
            new NextTurnContext(message, List.of(), context, List.copyOf(newMessages)));
    }
    emit.emit(new Event.TurnEnd(message, List.of()));
    emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
    return;
}
...
// 正常路（pi :286-298）：先 finishTurn，后 TurnEnd。
lastCompletedTurn = new NextTurnContext(message, List.copyOf(toolResults), context,
    List.copyOf(newMessages));
var decision = config.finishTurn() == null ? null : config.finishTurn().apply(lastCompletedTurn);
emit.emit(new Event.TurnEnd(message, List.copyOf(toolResults)));
if (decision == PiLoop.TurnDecision.END) {
    emit.emit(new Event.AgentEnd(List.copyOf(newMessages)));
    return;
}
explicitContinuation = decision == PiLoop.TurnDecision.CONTINUE;
pending = poll(config.steeringMessages());
if (hasMoreToolCalls || !pending.isEmpty()) {
    explicitContinuation = false;
}
```

外层（pi :300-315）：follow-up 非空 ⇒ `explicitContinuation = false` 后 `continue`；
`explicitContinuation` 为真 ⇒ 清零后 `continue`（白送一轮 context-only 请求——外层顶部
`hasMoreToolCalls = true` 重置使内层必进，走完整 prepareNextTurn＋TurnStart＋请求）；
否则 `break`。`explicitContinuation` 声明在外层 `while` 之前。
`:131-133` 的旧顺序注释随之重写（finishTurn 在 **TurnEnd 之前**、prepareNextTurn 在下一轮
开头，两者中间隔着 TurnEnd——pi 新锚 :286 与 :176 的相对序）。

**c. `PiLaneEngine.java`**（:339）：`next -> fireShouldStopAfterTurn(laneName, lane)`
→ `next -> fireFinishTurn(laneName, lane)`（返 `TurnDecision`）。

**d. HookSystem 层（#41）**——`ShouldStopAfterTurnHook` 的 `Boolean` 三态换裁决
（`FALSE`＝「不许停」方言删除，pi 无此物；`TRUE`→`END`，新增 `CONTINUE`）：

```java
@FunctionalInterface
public interface ShouldStopAfterTurnHook {
    /** pi FinishTurn 裁决：END＝收口，CONTINUE＝强制再来一次请求，null＝弃权。 */
    TurnDecision shouldStopAfterTurn(ShouldStopAfterTurnContext ctx);
}
```

`HookSystem.fireShouldStopAfterTurn` → `fireFinishTurn`，合并语义照 pi
agent-session :891-895：**任一 END 短路即赢；无 END 有 CONTINUE ⇒ CONTINUE；全弃权 ⇒ null**
（不再是「首个非 null 即返」）。注册键 `"should_stop_after_turn"` 保留——它是 java
HookSystem 的内部事件名（扩展面键名对齐属 B221 族，不在本批）；方法名
`onShouldStopAfterTurn` 保留做委托或直接更名 `onFinishTurn`，实施期按调用点数量定
（现存仅测试）。`TurnDecision` 枚举由 `PiLoop` 出，`hook` 包直接复用（已 import harness）。

### 2.4 夹具

镜像 pi 语义，全走 `PiLoop` 直驱（既有 `PiLoopRunnerTest` 同装置）＋ HookSystem 层：
1. **END**：裁决 END ⇒ TurnEnd 照发、AgentEnd 收尾、不再发请求（streamFn 调用数钉 1）；
2. **CONTINUE 白送一轮**：无工具/steering/follow-up ⇒ **恰 2 次**请求后正常收尾，
   第二轮走完整 prepareNextTurn＋TurnStart（事件序列钉）；
3. **CONTINUE 被满足×3**：有工具结果／有 steering／有 follow-up 三种各自**不**多发请求
   （pi :296-297、:303-305 的三条清零路径逐条钉）；
4. **错误路钩子照调**：error 终局 ⇒ 钩子被调 1 次（裁决忽略）、TurnEnd/AgentEnd 硬出口；
5. **顺序钉**：钩子调用点在 TurnEnd **之前**（sink 事件流里插探针）；
6. **newMessages**：第二轮钩子的 `newMessages` 含首轮 assistant＋工具结果；
7. **#41 合并**：END＋CONTINUE 两钩子两序 ⇒ END 赢；CONTINUE＋弃权 ⇒ CONTINUE；
   抛错钩子记账并弃权（既有语义保留）。

## 3. #17：手动 /compact 先 abort（map/03 #17）

### 3.1 pi 侧（`coding-agent/.../agent-session.ts`）

```ts
	async compact(customInstructions?: string): Promise<CompactionResult> {
		await this.abort();                                     // :2764-2765
		this._compactionAbortController = new AbortController();
		this._emit({ type: "compaction_start", reason: "manual" });
		...
```

`abort()`（`:2434-2443`）＝ abortRetry ＋ abortCompaction ＋ abortBranchSummary ＋
`agent.abort()` ＋ **`await this.waitForIdle()`**。

### 3.2 java 现状与差距

`coding-agent AgentSession.compact(String)`（:809-813）直入 `harness.compact`；
`AgentSession.abort()`（:788-793）＝ abortRetry＋`harness.abort`（置旗即发，**不等停**
——UI Esc 路径需要非阻塞，不动它）。设施已齐：`AgentHarness.waitForIdle(laneName)`
（:335-341，pi `Agent.waitForIdle` 对应物，空闲即返）。生产调用点：
`RpcDispatcher:176`（worker 虚拟线程）、`MiscCommands:133`——都**不是** run 线程，
`waitForIdle` 的 `run.done().join()` 无自 join 死锁。

差距：在飞 run 与手动压缩**并发跑**（CompactionExecutor:357-368 还专门给
「run 中压缩」记 StepAttempt——pi 里手动路根本到不了那）。

### 3.3 java 待写（`AgentSession.java`，三手动入口统一）

```java
public com.pijava.agent.compaction.CompactionResult compact(String customInstructions) {
    // #17（pi agent-session.ts:2764-2766）：手动压缩开头即 abort 在飞 run 并等它停。
    // abort() 本身保持非阻塞（UI Esc 同一条路）；等待只加在压缩路，净效果与
    // pi 的 await abort() 相同。⚠️ pi 的 abort() 还中止在飞压缩/分支摘要——java 无
    // abortCompaction（B36 另案），在飞压缩撞上本调用仍走既有行为。
    abort();
    harness.waitForIdle(laneName());
    var resolved = services.settings().accessors().getCompactionSettings(harness.getModel());
    return harness.compact(laneName(), resolved, customInstructions);
}
```

`compact(CompactionSettings)`／`compact(CompactionSettings, String)` 两便利重载同样补
abort-first（它们也是手动入口；引擎内部的自动压缩走 `CompactionExecutor` 直路，不受影响）。
`AgentHarness`/`RunLifecycle`/`CompactionExecutor` **零改动**（abort/waitForIdle/compact
三件都在）。

### 3.4 夹具（coding-agent）

1. **abort-first 顺序**：慢 streamFn 挂住 run，另一线程 `compact(...)` ⇒ 断言
   `AbortRequested` 记录/`aborted` 终局**先于** `compaction_start`，且压缩正常完成；
2. **空闲直过**：无 run 时 `compact` 不产生 abort 痕迹（`waitForIdle` 即返，行为同今）；
3. **等待语义**：abort 后到 run 真正落地才进压缩（压缩读到的 transcript 不含
   在飞 run 的半截消息）。

## 4. 测试与验证

- 新夹具：B158×4（§1.4）、B159×7（§2.4）、#17×3（§3.4）；既有 `TruncationUtilsTest`／
  `HookTest`（TRUE/null 用例改裁决枚举）／`PiLoopRunnerTest` 随改签。
- `mvn -pl pi-java-agent-core,pi-java-coding-agent -am test` 全绿后，全量 `mvn clean verify`。
- 文件红线：`AgentSession` 已 988 行（E 项在册），本批净增 ≤10 行不拆（登记不动）。

## 5. 闭环回填（通过后）

- `docs/07`：§1 删 #17 行；§2.1 删 #6/#41、#23 三行；
- `map/03`：三单元翻对齐＋加权汇总（132.0→138.5/183≈**75.7%**；权重 3 层 13 对齐/9 存疑
  → 16/6）＋边际表基线；
- `docs/04`：B158/B159 销号登记；
- `docs/05`：agent-core 72.1%→75.7%；合计 818.0→824.5/1385 ≈ **59.5%**；
- commit：`feat(agent-core)`（B158＋B159）、`feat(coding-agent)`（#17）、docs 各一。
