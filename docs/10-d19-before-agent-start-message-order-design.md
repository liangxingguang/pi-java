# D19 对齐包设计稿：`before_agent_start` 处理器消息的落盘序

> **状态**：已实施（设计经用户批准；2026-11-25）。
> **锚点**：pi `a00a5048d`。
> **范围**：`pi-java-coding-agent`（`core/RunStarter`、`core/SessionMessagingLifecycle`、`core/SessionRunner`）＋ 两个测试类；不改 agent-core 公共面。
> **关闭条件**：prompt 臂处理器消息 = pi 的 `messages` 序；`map/04` D19 行销号。

## 1. 差异（`map/04` D19）

> `before_agent_start` 的 result.message 落盘序为 custom 条目先于用户消息。等级：[内部，边缘]。

本仓把它当窄差登记，但它同时带出两个可观察后果（见 §4）。pi 的顺序是**起始消息 → nextTurn → 处理器消息**，两侧都在同一次运行的待发消息里。

## 2. pi 事实

### 2.1 `_prepareRun` 组装起始消息（`packages/coding-agent/src/core/agent-session.ts:2029-2061`，逐字）

```ts
const messages: AgentMessage[] = [];
if (trigger.messageType === "custom") {
    messages.push(trigger.message);
} else {
    // Normalize images after before_agent_start so extension-driven model selection
    // determines the resize profile used for the request and history.
    const normalized = await this._normalizePromptImages(trigger.images);
    const text =
        normalized.hints.length > 0 ? `${trigger.prompt}\n\n${normalized.hints.join("\n")}` : trigger.prompt;
    messages.push({
        role: "user",
        content: [{ type: "text", text }, ...normalized.images],
        timestamp: Date.now(),
    });
}
// Inject any pending "nextTurn" messages as context alongside the starting message
messages.push(...this._pendingNextTurnMessages);
this._pendingNextTurnMessages = [];
for (const msg of result.messages) {
    messages.push({
        role: "custom",
        customType: msg.customType,
        // Untyped extensions can pass null/missing content; normalize at ingestion.
        content: msg.content ?? [],
        display: msg.display,
        details: msg.details,
        timestamp: Date.now(),
    });
}
const updateMessage = this._preparePromptAndToolLoadout(result.systemPromptOptions);
this._runSystemPromptOptions = result.systemPromptOptions;
if (updateMessage) messages.unshift(updateMessage);
return messages;
```

要点：起始消息（用户或 custom）→ nextTurn → 处理器消息；这三段**同属一次 `agent.prompt(messages)`**，因此既按此序落盘，也按此序进本轮 LLM 请求。段补丁 `updateMessage` 再 `unshift` 到最前。

### 2.2 `_startRun` 的时点（`agent-session.ts:1958-1984`）

```ts
messages = await this._prepareRun(trigger);
...
// Messages held while the run was starting go before its starting messages, as they would
// have while idle.
this._flushPendingBashMessages();
this._flushPendingCustomMessages();
onStarted?.();
...
await this.agent.prompt(messages);
```

启动窗内延迟的 bash/custom 排在起始消息**之前**（本仓已对齐，`RunStarter` 同位保留）；处理器消息不是延迟缓冲，**不走 flush**，而是随 `messages` 落盘 —— 即排在起始消息之后。

### 2.3 处理器消息的聚合与归一（`core/extensions/runner.ts:1469`、`agent-session.ts:2052`）

```ts
if (result.message) messages.push(result.message);          // runner.ts:1469，按处理器注册序
content: msg.content ?? [],                                  // agent-session.ts:2052
```

## 3. Java 现状

| 位置 | 现状 |
|---|---|
| `RunStarter.start`（prompt 臂） | hook 返回后**先把每条 draft 写进会话**，再 `onStarted`、再 `drive` |
| `RunStarter.start`（custom 臂） | 起始 custom → nextTurn → draft，序对；但 draft 走同一个直写会话的方法 |
| `PiLaneEngine.run` | `initial = [userPrompt, customs...]`，customs 由 `promptWithCustoms` 传入 |
| `RunLifecycle.startRun` | 先写段补丁、再写用户 entry（日志序 `[system, user]`） |
| `PiLaneSink.appendCustom` | custom 投影在 `message_end` 落成 `Entry.CustomMessage`（`appendDeferredEntry`） |
| `SessionMessagingLifecycle.appendCustomDraft` | 持久会话走 `session().appendCustomMessageEntry(...)`（**直接写存储**，不经过车道）；无会话走 `harness().appendLaneEntry(...)` |

关键代码（[RunStarter.java](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/RunStarter.java#L96-L135) 现状节选）：

```java
if (trigger.custom() == null) {
    for (var draft : handlerDrafts) {
        owner.messaging().appendCustomDraft(draft);   // ← 用户 entry 之前就已落盘
    }
}
...
if (trigger.custom() != null) {
    owner.messaging().appendCustomMessageNow(trigger.custom());
    for (var nextTurn : owner.drainPendingNextTurnMessages()) {
        owner.messaging().appendCustomMessageNow(nextTurn);
    }
    for (var draft : handlerDrafts) {
        owner.messaging().appendCustomDraft(draft);
    }
    ...
} else {
    var nextTurnCustoms = owner.drainPendingNextTurnMessages().stream()
        .map(QueuedCustomMessage::toQueuedItem).toList();
    ... SessionRunner.drive(owner, trigger.prompt(), trigger.images(), nextTurnCustoms, ...)
}
```

## 4. 差异后果

| 面 | pi | Java 现状 | 影响 |
|---|---|---|---|
| 日志/JSONL 序（prompt 臂） | user → nextTurn → handler | handler → user → nextTurn | D19 本体；`/resume`、导出、分支树可见 |
| 本轮 LLM 上下文 | 处理器消息在 `messages` 里 ⇒ 发给模型 | `appendCustomDraft` 只写存储，不重建车道工作副本 ⇒ **本轮不发给模型** | 模型可见；处理器注入的提示可能整轮缺失 |
| 会话事件 | `message_end` ⇒ 条目 + 渲染 | 无 `EntryAppended`、本轮 `entries()` 里也没有（不在车道转录） | TUI/RPC 在 run 结束后才可能从存储看到 |
| custom 臂 | start → nextTurn → handler，同一次 `prompt(messages)` | 序对；但 handler 走直写存储（同样不进本轮上下文） | 两臂对同一份 hook 返回值表现不一致 |

（持久会话路径可以确认：`Session.appendCustomMessageToLane` 只落存储；车道工作副本只在 `refreshLaneMessages`/`appendLaneEntry` 时重建，`appendCustomDraft` 两者都不做。）

## 5. 方案

### T1（主修）prompt 臂：处理器消息并入本轮的 customs 列表

- 删除 `RunStarter.start` 中 prompt 臂起跑前的 `appendCustomDraft` 循环。
- prompt 分支改为：先 `drainPendingNextTurnMessages()` 转 `QueuedItem.Custom`，再把 `handlerDrafts` 逐个转换**追加其后**，整体交给 `SessionRunner.drive(..., customs, ...)` → `promptWithCustoms`。
- 转换复用既有归一（content null ⇒ 空 Blocks）：`new QueuedCustomMessage(...).toQueuedItem()`，seq 传 0（投影路径不使用；真实 seq 由 `appendDeferredEntry` 在落盘时分配）。

改动后 `PiLaneEngine.run` 的 `initial` 天然是 `[user, nextTurn..., drafts...]`，日志序与 LLM 上下文序同时对齐 pi。

### T2（一致性）custom 臂：处理器消息走 `appendCustomMessageNow`

custom 臂的 `appendCustomDraft` 换成 `appendCustomMessageNow(new QueuedCustomMessage(...))`：

- 序不变（起始 custom → nextTurn → handler，`appendCustomMessageNow` 空闲臂立即追加并重建工作副本）；
- 与起始 custom/nextTurn 同一条落盘＋`EntryAppended` 路径，处理器消息进入本轮上下文（pi 的 `messages` 含它们）；
- 之后 `SessionMessagingLifecycle.appendCustomDraft` 不再有调用者 ⇒ 删除（不留兼容壳）。

### 不做的事

- 不动启动窗 flush 顺序（bash/custom 仍在起始消息之前，pi :1971-1974）。
- 不动 `LaneInfo.QueuedItem` 形状、`appendCustomMessageNow` 的既有行为、`SessionRunner` 的两条驱动路。
- 不引入 harness 新参数（customs 通道已存在）。
- 无 seq 规则改动：处理器消息的 seq 由车道在追加时分配，落在用户 entry 之后。

### 代码草案（仅示意，审核后落地）

```java
// RunStarter.start：prompt 臂起跑前的写盘循环删除

if (trigger.custom() != null) {
    owner.messaging().appendCustomMessageNow(trigger.custom());
    for (var nextTurn : owner.drainPendingNextTurnMessages()) {
        owner.messaging().appendCustomMessageNow(nextTurn);
    }
    for (var draft : handlerDrafts) {
        owner.messaging().appendCustomMessageNow(toQueued(draft));
    }
    ...
} else {
    var customs = new ArrayList<LaneInfo.QueuedItem.Custom>();
    for (var nextTurn : owner.drainPendingNextTurnMessages()) {
        customs.add(nextTurn.toQueuedItem());
    }
    for (var draft : handlerDrafts) {
        customs.add(toQueued(draft).toQueuedItem());
    }
    Thread.startVirtualThread(() -> SessionRunner.drive(
        owner, trigger.prompt(), trigger.images(), customs, ...));
}

private static QueuedCustomMessage toQueued(CustomMessageDraft draft) {
    return new QueuedCustomMessage(draft.customType(), draft.content(),
        draft.display(), draft.details());
}
```

（`toQueued(draft).toQueuedItem()` 只借归一；也可以直接构造 `QueuedItem.Custom`，实现时取更短者。）

## 6. 回归测试

新增/增强（全部用 faux provider，不触网）：

1. `RunStartWindowTest` 新增 `promptArmOrdersHookMessagesAfterUserAndNextTurn`
   - 起跑前 `sendCustomMessage(..., deliverAs=NEXT_TURN)` 入 nextTurn；
   - `processPrompt("hi")` 后按转录序过滤：用户 `Entry.Message` 在前，custom 文本序恰为 `["next-turn", "from-hook"]`；
   - `harness().getMessages()` 里能读到 `from-hook`（本轮上下文可见）。
2. `RunStartWindowTest.customTriggerFiresCustomHookArmAndOrdersMessages`
   - 保留现有 `containsExactly("trigger", "from-hook")`；补一条「工作副本含 from-hook」。
3. `RunStartWindowTest.startWindowBashAndCustomFlushBeforeUserMessage`
   - 原样通过（启动窗 flush 序未动）。
4. `SessionLifecycleEventsTest.beforeAgentStartInjectsCustomMessageAndOverridesSystemPrompt`
   - 持久会话路径：`CustomMessage` 仍恰一条；补断言其 seq/位置在用户 entry 之后。
5. `mvn -q -pl pi-java-coding-agent -am test -Dtest=RunStartWindowTest,SessionLifecycleEventsTest`，随后全仓 `mvn -q verify`。

## 7. 风险与回滚

| 风险 | 处置 |
|---|---|
| 处理器消息改由 sink 落盘，run 中途失败时是否落盘 | `SessionRunner` 在 try/catch 后都 `flush`；pi 同样以 `message_end` 落盘 |
| RPC `started` 回执时转录里暂时没有处理器条目 | pi 也是 `onStarted` 后才 `agent.prompt(messages)`；属对齐后的时序，非回归 |
| 空内容/`details` 归一差 | 复用 `QueuedCustomMessage` 的既有归一（`content == null ⇒ Blocks([])`、`details == null` 允许），与 pi `content ?? []` 同义 |
| custom 臂改路径引入双写 | `appendCustomMessageNow` 空闲臂只走 `appendLaneEntry` 一次；测试 2 断言行数 |
| 回滚 | T1/T2 均为 `RunStarter` 局部改动；`git revert` 本包提交即可回到现状 |

## 8. 文档回填（闭环时）

- `docs/map/04-coding-agent.md`：删 D19 行；D 域读数不变（D 域已是 100%，窄差不参与分子）。
- `docs/04-open-items-register.md`：§0 L5 收口补记一条（D19 销号）；不新开 B/D 号。
- `docs/05-module-alignment-map.md`：L5 读数确认（预期不变，457.0/459＝99.6%），仅差异清单减一条。
- 新增窄差 **D322**：custom 队列项的 LLM 投影是 `UserMessage`，`message_end` 扩展事件里 role 为 `user`（pi 为 `custom`）；本包把处理器消息接入车道投影后该方言随之显形，条目/落盘序/上下文不受影响。
- 实施记录：`RunStarter` 两臂改序；删除 `SessionMessagingLifecycle.appendCustomDraft`；新增／增强三处测试。
