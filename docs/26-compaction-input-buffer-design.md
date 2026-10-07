# 包 26：压缩窗口输入缓冲与自动重放（B175）＋ queue_update 生产者（B34）

> **状态：⏳ 设计待审核，未写生产代码。**
> 承接台账 **B175**，并顺带销号高度耦合的 **B34**（同一实现面）。

## 1. 问题

- 压缩中用户在 TUI 提交文本：`InteractiveMode.submit`（A5 包）直接抛
  ISE——pi 的行为是**客户端缓冲**，压缩结束自动投递（`queueCompactionMessage`）。
- `AgentSessionEvent.QueueUpdate` 定义了但**零生产者**（B34）：TUI 的
  「待处理消息」区无数据源；pi 在每个排队点发射 `queue_update`。
- AgentSession 缺队列内容读口（pi `getSteeringMessages`/
  `getFollowUpMessages`）与 `clearQueue()`。

## 2. pi 锚点事实

### 2.1 缓冲（`interactive-mode.ts`）

```ts
// :4688-4694
private queueCompactionMessage(text: string, mode: "steer" | "followUp"): void {
    this.compactionQueuedMessages.push({ text, mode });
    this.editor.addToHistory?.(text);
    this.editor.setText("");
    this.updatePendingMessagesDisplay();
    this.showStatus("Queued message for after compaction");
}
// type :265-268：{ text: string; mode: "steer" | "followUp" }
```

两个输入处理：Enter 压缩中 ⇒ `queueCompactionMessage(text,"steer")`（`:3318-3326`）；
Alt+Enter ⇒ `"followUp"`（`:4405-4413`）。**TUI-only**：RPC 不缓冲
（`prompt()` 在压缩中抛错，rpc-mode 返回 error）。

### 2.2 重放（`flushCompactionQueue`，`:4706-4783`）

```ts
// willRetry=true（自动压缩后续跑被打断的回合）：逐条入引擎队列
for (const m of queued) {
    if (m.mode === "followUp") await session.followUp(m.text);
    else await session.steer(m.text);
}
// willRetry=false：
const firstPrompt = queued[0];           // Java 无扩展命令 ⇒ 第一条即 prompt
const promptPromise = session.prompt(firstPrompt.text,
    { streamingBehavior: firstPrompt.mode }).catch(restoreQueue);
for (const m of queued.slice(1)) {       // 其余按 mode 排队
    if (m.mode === "followUp") await session.followUp(m.text);
    else await session.steer(m.text);
}
// restoreQueue(error)：session.clearQueue()；缓冲还原；showError(...)
```

主触发点＝`compaction_end` 事件处理（`:3682`，无条件）。

### 2.3 事件发射前先暴露 idle（`agent-session.ts:2828-2829`）

```ts
// compaction_end listeners may submit queued prompts, so expose idle state before notifying them.
this._clearManualCompactionState();
this._emit({ type: "compaction_end", ... });
```

错误路同样先清（`:2842`）。否则 flush 里的 `prompt()` 会被压缩门挡。

### 2.4 queue_update / 队列口

```ts
// :1017-1023
private _emitQueueUpdate(): void {
    this._emit({ type: "queue_update",
        steering: [...this._steeringMessages],
        followUp: [...this._followUpMessages] });
}
```

发射点：`_queueSteer :2193`、`_queueFollowUp :2210`、`clearQueue :2361`。
`clearQueue()`（`:2355-2363`）清空两队列＋`agent.clearAllQueues()`＋发射，
返回 `{steering, followUp}`；RPC `clear_queue` 响应 data 即此形状
（`test/.../rpc-prompt-response-semantics.test.ts:446-457`）。

## 3. 方案

### 3.1 压缩窗口顺序（agent-core）

`LaneState` 加幂等清位：

```java
/** 窗口在则清并返回 true（用于 onEnd 发射前先暴露 idle，pi :2829）。 */
synchronized boolean closeCompactionWindow() {
    if (compactionInFlight == 0) return false;
    compactionInFlight = 0;
    return true;
}
```

`exitCompaction()` 改调它（finally 兜底，已清则 no-op）。`CompactionExecutor`
4 个 onEnd 发射点前插 `lane.closeCompactionWindow()`：`applyCompaction`
中止（`:287`）/成功（`:318`）、compact 错误 catch（`:104`）、
runAutoCompaction 错误 catch（`:235`）。

队列读口：`QueueManager` 加

```java
List<String> steeringTexts(String laneName);   // 按队列序取 QueuedItem.prompt
List<String> followUpTexts(String laneName);
```

`AgentHarness` 公开 `queuedSteering/queuedFollowUp(lane)`。

### 3.2 缓冲器（coding-agent，NEW）

`com.pijava.coding.agent.core.CompactionInputBuffer`（纯逻辑，无 TUI/IO）：

```java
public final class CompactionInputBuffer {
    public record Queued(String text, StreamingBehavior mode) {}

    public void add(String text, StreamingBehavior mode);
    public boolean isEmpty();
    public void clear();
    public List<Queued> contents();

    /**
     * pi flushCompactionQueue（无扩展命令分支）：
     * willRetry ⇒ 逐条 steer/followUp；否则第一条 prompt(behavior)、其余排队。
     * 任一步抛 ⇒ 撤两队列、缓冲还原后再抛。
     */
    public void flush(boolean willRetry, Replayer sink);

    /** 投递端口（生产＝AgentSession；测试可桩）。 */
    public interface Replayer {
        void prompt(String text, StreamingBehavior mode);
        void steer(String text);
        void followUp(String text);
        void cancelQueued(String queueType);   // 失败还原时撤引擎队列
    }
}
```

### 3.3 AgentSession（coding-agent）

- 加 `private void emitQueueUpdate(List<String> bufferedSteering,
  List<String> bufferedFollowUp)`：聚合 `harness.queuedSteering/FollowUp`
  ＋缓冲后发 `QueueUpdate`。
- `steer()/followUp()` 排队后发射（缓冲无内容）；`processPrompt`
  QUEUE 分支返回前发射。
- 新增 `clearQueue()`：`harness.cancelQueued("steer")`＋`"followUp"`
  ＋发射，返回 `QueueUpdate` 形状记录。

### 3.4 InteractiveMode（coding-agent）

- 持有 `CompactionInputBuffer`；构造时 `session.subscribe`：
  `CompactionEnd e` ⇒ `buffer.flush(e.willRetry(), replayer)`＋发射
  queue_update（缓冲此时已空，聚合的是刚入引擎队列的内容）。
- `submit` 压缩分支改为：`buffer.add(prompt, STEER)` ＋发射
  queue_update ＋发 `FlashStatus`；返回 `SessionResult.queued()`。
- `followUp(text)`：压缩中 ⇒ `buffer.add(text, FOLLOW_UP)`（同通知）；
  运行中 ⇒ 现状；空闲 ⇒ submit。
- `switchSession/close`：重接订阅、`buffer.clear()`。

**新事件** `AgentSessionEvent.FlashStatus(String message)`：pi 无此事件——
方言，成因是 pi 的 InteractiveMode 把 UI/会话混在一个 6000 行对象里，
Java 刻意分层（InteractiveMode 无 TUI 类型），状态文本必须经事件面到
ChatScreen。RpcMode 事件映射忽略它（不上 RPC 线）。

### 3.5 RPC（coding-agent）

- `RpcCommand` 加 `ClearQueue(id)`（`@JsonTypeName("clear_queue")`）。
- RpcDispatcher case：`session.clearQueue()` ⇒ `ok(id,"clear_queue",
  data{steering,followUp})`。
- prompt 压缩中行为不变（error 响应；缓冲 TUI-only，pi 同）。

### 3.6 ChatScreen / TUI

- `onSessionEvent`：
  - `QueueUpdate q` ⇒ 存两列表（驱动 pending 区渲染）。
  - `FlashStatus s` ⇒ `showStatus(s.message)`。
- `showStatus`（pi `:3773`）：往 chatPanel 追加 dim 文本
  （连续状态行合并——简化为直接追加，不做合并）。
- render：chatPanel 与 editor 之间插 pending 区——每条一行 dim 文本
  `Steering: <x>` / `Follow-up: <x>`（pi `:4648-4660`）。
- PiTuiApp FOLLOW_UP 动作：压缩中 ⇒ `mode.followUp(text)`（缓冲
  followUp），运行中/空闲现状不变。

### 3.7 过时断言翻改

`CompactionInFlightTest`：`:111`/`:134` 的 `onEnd.containsExactly(true)`
翻为 **false**（pi 发事件前已清窗口，§2.3）；`onStart` 断言不变。
注释同步说明 pi :2828-2829。

## 4. 测试计划（RED-first）

**agent-core**

1. `CompactionInFlightTest` 翻改（onEnd 窗口 false；start 仍 true）。
2. `QueueManager` steering/followUp 文本读口：入队后按序返回。

**coding-agent**

3. `CompactionInputBufferTest`（7 条）：空 flush no-op；willRetry
   混合消息各自入队且无 prompt；willRetry=false 第一条走 prompt、
   其余按序 steer/followUp；Replayer 第 2 步抛 ⇒ 缓冲原样还原＋
   cancelQueued 两队列都调；clear；add 顺序。
4. `InteractiveModeCompactionBufferTest`（真 harness，truncating
   SummaryGenerator；调 `session.compact` 触发同步事件链）：
   - 压缩中 submit：无驱动线程、queue_update 含缓冲、FlashStatus 文本
   - compact 返回后：缓冲消息已起新 run（首轮驱动在飞，停因正常）
   - 压缩中 followUp(text) 缓冲为 FOLLOW_UP
5. RPC：clear_queue 响应 data 两键（含被清文本）。

**tui**

6. ChatScreen：QueueUpdate 后 pending 行可见（Steering/Follow-up 文本）；
   FlashStatus 追加 dim 行。

## 5. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | flush willRetry 分支删 prompt 区分（恒排队/恒 prompt） | buffer 测试 ≥1 |
| M2 | onEnd 前不调 closeCompactionWindow（恢复旧序） | E2E：flush 的 prompt 抛 ISE |
| M3 | flush 失败不还原缓冲 | buffer 测试 1 |
| M4 | queue_update 聚合漏缓冲 | E2E 1 |
| M5 | clearQueue 后不发 update | RPC/会话测试 1 |

每次变异 grep 复核落地（CRLF 教训）。

## 6. 台账影响

- **B175、B34 销号**（B 71 → 69）。
- docs/04 同步；闭环后 banner 与实施记录回填。
