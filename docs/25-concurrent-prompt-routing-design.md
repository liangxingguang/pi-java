# 包 25：并发 prompt 路由语义（streamingBehavior：steer / followUp）（A5）

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 承接台账 **A5**：F4 已裁「对齐 pi」，本包落地该裁决，A5 销号。
> 实施记录见 §7。

## 1. 问题

引擎层队列基建（`QueueManager` 的 steer/followUp/nextRun、`QueueMode`
排空、`QueueEnqueued` 记录）和 `AgentSession.steer()/followUp()` 都已存在，
但**宿主层 `processPrompt` 没有路由门**：

- 运行中再来一条 prompt：`PiLaneEngine.run` 在驱动线程深处抛
  `IllegalStateException("Cannot start run: lane ... is not idle")`
  （`PiLaneEngine.java:91-93`），被 `SessionRunner` 兜底成一条通用
  `StreamError` + `reason=error` 的失败 run。pi 的对应行为是**在调用线程
  同步抛出带指引的错误**，不产生任何 run / 错误帧。
- 压缩中提交 prompt：宿主无预检，同样沉到引擎内部失败路径。
- RPC `prompt` 帧的 `streamingBehavior` 字段（`RpcCommand.java:66-69`）
  已解析但 `RpcDispatcher.handlePrompt`（`:289-295`）**完全忽略**；
  响应在预检前写出且无 `disposition`（pi：预检通过后才写，带
  `data.disposition`）。
- TUI：Enter 运行中应按 steer 排队、Alt+Enter 按 followUp 排队；
  当前 `PiTuiApp.submitPrompt` 无区分，运行中 Enter 会走上述失败路径；
  FOLLOW_UP 动作在空闲时也盲目入队。

## 2. pi 锚点事实

### 2.1 `prompt()` 路由（`agent-session.ts:1921-1979`）

顺序：扩展命令（即使在流中也立即执行）→ **压缩门** → input 处理器 →
模板展开 → **流中门**：

```ts
// :1939-1943
if (this._compactionAbortController !== undefined) {
    throw new Error(
        "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry.",
    );
}

// :1966-1979
if (this.isStreaming) {
    if (!options?.streamingBehavior) {
        throw new Error(
            "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.",
        );
    }
    if (options.streamingBehavior === "followUp") {
        await this._queueFollowUp(expandedText, currentImages);
    } else {
        await this._queueSteer(expandedText, currentImages);
    }
    preflightResult?.("queued");
    return;
}
```

- `isStreaming` = `_isAgentRunActive`（`:1430-1432`）。
- 正常路径在真正发请求前调 `preflightResult?.("started")`（`:2062`）。
- disposition 类型（`:294-295`）：
  `QueuedInputDisposition = "handled" | "queued"`；
  `PromptDisposition = QueuedInputDisposition | "started"`。

### 2.2 steer / followUp 入队（`:2191-2216`）

```ts
private async _queueSteer(text: string, images?: ImageContent[]): Promise<void> {
    this._steeringMessages.push(text);
    this._emitQueueUpdate();
    const content: (TextContent | ImageContent)[] = [{ type: "text", text }];
    if (images) { content.push(...images); }
    this.agent.steer({ role: "user", content, timestamp: Date.now() });
}
// _queueFollowUp 同形：推 _followUpMessages → agent.followUp(...)
```

空闲时调用 `steer()/followUp()` 仍入对应队列（input 处理器不收到
behavior，`:2140`），disposition 为 `"queued"`。

### 2.3 RPC 线（`rpc-mode.ts:394-424`）

- `prompt`：`preflightResult` 回调写成功响应（success + `{disposition}`）；
  promise reject 且预检未成功 ⇒ 一条 `error(id,"prompt",e.message)`。
- 独立 `steer` / `follow_up` 命令：`session.steer(...)` 返回 disposition，
  成功响应 `data: { disposition }`。

测试证据（`test/rpc-prompt-response-semantics.test.ts`）：

| 用例 | 响应 |
|---|---|
| 预检失败（无 API key，`:197`） | 恰一条 `{success:false,error:…}` |
| 预检成功（`:237`） | `{success:true,data:{disposition:"started"}}` |
| 流中排队（`:291`） | `{success:true,data:{disposition:"queued"}}` |
| `steer`/`follow_up` 入队（`:377`） | `{success:true,data:{disposition:"queued"}}` |

### 2.4 TUI（`interactive-mode.ts`）

- 运行中 Enter ⇒ `prompt(text,{streamingBehavior:"steer"})`（`:3331-3338`）
- 运行中 Alt+Enter ⇒ `prompt(text,{streamingBehavior:"followUp"})`（`:4418-4424`）
- 空闲 Alt+Enter ⇒ 等同普通 Enter（`:4426-4429`）
- 压缩中：非扩展输入**客户端缓冲**，压缩结束后自动重放
  （`queueCompactionMessage :4688-4694`、`flushCompactionQueue :4706+`）。

## 3. 方案

### 3.1 核心路由（coding-agent）

**新增** `com.pijava.coding.agent.core.StreamingBehavior`（纯常量闭集 ⇒
enum，CLAUDE.md ADT 规则）：

```java
public enum StreamingBehavior {
    STEER("steer"), FOLLOW_UP("followUp");
    // @JsonValue wireName() + @JsonCreator fromValue(String)
}
```

**新增** `PromptRouter`（包私有小类，门逻辑单点，可无重夹具单测）：

```java
final class PromptRouter {
    static final String MSG_COMPACTING =
        "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry.";
    static final String MSG_ALREADY_PROCESSING =
        "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.";

    /** pi prompt() 门：compacting → 抛；running+无 behavior → 抛；否则定案。 */
    Verdict route(boolean compacting, boolean running, StreamingBehavior behavior) { … }
    enum Verdict { START, QUEUE_STEER, QUEUE_FOLLOW_UP }
}
```

**改 `PromptConfig`**：末位加组件 `StreamingBehavior streamingBehavior`
（null = 不排队）；`defaults()` 末位 null。全部调用点同步。

**改 `AgentSession.processPrompt`（4 参版，起手处、启动虚拟线程之前）**：

```java
var verdict = promptRouter.route(
    harness.isCompacting(laneName()), harness.isRunning(laneName()),
    config.streamingBehavior());
if (verdict == QUEUE_STEER)   { harness.steer(laneName(), prompt);   return SessionResult.queued(); }
if (verdict == QUEUE_FOLLOW_UP){ harness.followUp(laneName(), prompt); return SessionResult.queued(); }
// verdict == START：原路径
```

异常类型用 `IllegalStateException`，**消息逐字**（javadoc 引 pi file:line）。

**改 `SessionResult`**：加 `disposition` 构造参（`"started"` / `"queued"`）
+ `String disposition()`；新增静态工厂 `queued()`：空 stream、
`entries` 已完成（`List.of()`）、`status` 已完成（`RunStatus(0,"queued")`）。

### 3.2 harness（agent-core）

`AgentHarness` 补公开口（与 `isCompacting` 同形，`:403`）：

```java
/** Whether an agent run is active on this lane. A5（docs/25）. */
public boolean isRunning(String laneName) {
    return requireLane(laneName).isRunning();
}
```

### 3.3 RPC（coding-agent）

- `handlePrompt`：`PromptConfig` 末位传 `p.streamingBehavior()`
  （core enum；`RpcCommand.Prompt` 的字段类型改用 core enum，删除
  `RpcCommand` 内嵌套同名 enum）。try/catch 门异常：
  - 成功：`processPrompt` 返回后写一条响应——
    disposition 取 `result.disposition()`：`"started"` | `"queued"`
    ⇒ `RpcResponse.ok(id,"prompt", new RpcPayloads.Disposition(value))`。
  - 门异常：`RpcResponse.fail(id,"prompt", e.getMessage())`（恰一条）。
- `steer`/`follow_up` 两个 case：先调 `session.steer/followUp`，
  再写 `ok(…, data disposition("queued"))`。
- payload record 形状 `{"disposition":"…"}`，按 RpcResponse 既有 data 通道。

### 3.4 InteractiveMode / TUI

- `InteractiveMode.submit(text)`：
  - `session.isCompacting()` ⇒ 抛 `IllegalStateException(MSG_COMPACTING)`
    （TUI catch 后 showStatus，不产生帧）；
  - `session.isRunning()` ⇒ `session.steer(text)`，返回 `SessionResult.queued()`；
  - 否则原路径。
- `PiTuiApp.submitPrompt`：`result.disposition()` 为 `"queued"` 时
  不挂 finishRun（不画 run 分隔线），直接返回。
- `PiTuiApp` FOLLOW_UP 动作对齐 pi：运行中 ⇒ `mode.followUp`（现状）；
  **空闲 ⇒ 走普通 `submitPrompt(text)`**（现状是盲目入队，改为按 idle 分流）。

### 3.5 web（pi 无 web，pi-java 自有面）

门随 `processPrompt` 统一生效；`WebDispatcher.handlePrompt` 的外层
catch（`WebDispatcher.java:115-118`）已把异常映射成
`WebServerMessage.Error`，无需额外改动。web 前端的「运行中排队」交互
不在本包。

### 3.6 不做 / 新登记

- **新 B 项**：TUI 压缩窗口客户端缓冲 + 压缩结束自动重放
  （pi `interactive-mode.ts:4688-4760`），含扩展命令立即执行的分支。
  本包只保证「压缩中 prompt 同步抛错、不产生坏帧」。
- pi 的扩展 input 事件（`handled` disposition）与扩展命令
  pi-java 未移植（无 `emitInput`/`registerCommand`），`"handled"`
  在本包不可达；相关功能随扩展子系统包再对齐。
- 队列变化推送 `queue_update`（B34）维持开放：本包排队走的是
  harness 既有入队点，不产生 `queue_update`。
- TUI「待处理消息」可视化（pi 的 updatePendingMessagesDisplay）
  随新 B 项一起评估。

## 4. 测试计划（RED-first）

**agent-core**

1. `AgentHarness.isRunning`：空闲 false；运行中 true（复用现有
   并发/latch 夹具形态，如 PiLoopTest）。

**coding-agent**

2. `PromptRouterTest`（6 条）：
   - compacting ⇒ 抛、消息逐字（字面量断言，不用常量自比）
   - running + null ⇒ 抛、消息逐字
   - running + STEER ⇒ QUEUE_STEER；running + FOLLOW_UP ⇒ QUEUE_FOLLOW_UP
   - idle（两 behavior 各一及 null）⇒ START
3. `SessionResultQueuedTest`：queued() 的 disposition/status/空 entries/
   空 stream；正常结果 disposition="started"。
4. RPC E2E（扩 `RpcModeEndToEndTest` 形态，pipes + delayed Faux）：
   - 运行中发无 behavior prompt ⇒ 恰一条 fail 响应、文本逐字、无新 agent_start
   - 运行中发 `streamingBehavior:"followUp"` ⇒ `{success:true,data:{disposition:"queued"}}`
     且原 run 结束后 followUp 队列为空（消息被消费）
   - 空闲 prompt ⇒ disposition "started"（回归）
   - `steer`/`follow_up` 命令响应带 `data.disposition:"queued"`
5. `InteractiveMode` 运行中 submit ⇒ 不产生驱动线程（无 agent_start）、
   steer 队列入账；压缩中 submit ⇒ 同步抛出。

## 5. 变异探针（实施后）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `PromptRouter` running 门删除（恒 START） | router 测试 + RPC queued 用例红 |
| M2 | running+STEER  verdict 翻成 FOLLOW_UP | router 1 红 |
| M3 | RPC 响应删 `disposition` data | E2E 恰 1 红 |
| M4 | `AgentHarness.isRunning` 取反 | agent-core 测试 + 门行为红 |
| M5 | queued() 的 status reason 改成 "started" | SessionResult 测试红 |

每次变异后 grep 复核落地（CRLF 教训）。

## 6. 台账影响

- **A5 销号**（A 类 5 → 4）。
- 新增 1 条 B（TUI 压缩窗口缓冲重放）。
- docs/04 同步；闭环后本文件 banner 与实施记录回填。

## 7. 实施记录（2026-10-07）

**落地（6 commits）**：

| commit | 内容 |
|---|---|
| `74c160a` | `AgentHarness.isRunning(lane)` 公开口 + 闩锁测试 |
| `8edadea` | `StreamingBehavior` enum（core）＋ `PromptRouter` 门（含逐字消息常量） |
| `85a8b00` | `LaneState.activeRun` 改 **volatile**（见下）；FauxProvider sequence+delay 工厂 |
| `e0ddb8d` | processPrompt 路由：QUEUE_STEER/FOLLOW_UP 入队返回 queued |
| `352c14d` | RPC：prompt 预检后写、`data.disposition`；steer/follow_up 带 disposition |
| `9673b20` | InteractiveMode 提交门＋TUI（queued 不画分隔线、空闲 Alt+Enter 普通提交） |

**测试（新增 13 条全绿）**：AgentHarnessIsRunningTest ×1、PromptRouterTest ×5、
SessionResultQueuedTest ×2、RpcConcurrentPromptRoutingTest ×4、
InteractiveModeRoutingTest ×1。全 reactor `clean verify` **14/14 BUILD SUCCESS**（6:33）。

**变异探针 5/5 红**：M1 删 running 门＝5 红；M2 STEER verdict 翻 FOLLOW_UP＝1 红；
M3 RPC 删 disposition data＝2 红；M4 isRunning 取反＝1 红；M5 queued status
reason 改 started＝1 红。

**实施中两处设计外发现**：

1. **`activeRun` 可见性是真差距**：A5 门设计上被 RPC reader/TUI/web 线程跨线程
   读取，普通字段不保证可见性（原 docs/31 §8.27.4 的残留成为可达），改 volatile。
2. **PiLoop pass 起手即 drain steer**（`PiLoopRunner.java:54`）：并发路由夹具
   若在 run 刚置位时就入队，steer 会被并进首次请求而非触发第二回合（首轮测试
   因此假失败）。真实语义位置＝首轮 LLM 调用在飞之后；夹具改为等首个流事件再
   排队。新教训形态：并发夹具的入队点必须按「pass 内 drain 时机」定位，不能只
   看 `activeRun != null`。

**已知残留**：InteractiveMode 压缩中提交（同步抛错分支）无会话级夹具
（构造压缩窗口成本高），由 `PromptRouter` 单测与 RPC 路径间接覆盖；
压缩窗口客户端缓冲/重放登记为 **B175**。台账现状：A 4 条、B 71 条。
