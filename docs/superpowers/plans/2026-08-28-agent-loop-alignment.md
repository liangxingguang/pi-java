# Agent Loop 对齐（钩子×2 / 图片输入 / reset·continueRun）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 pi-java `AgentHarness` 相对 pi `Agent` 的 4 个功能缺口：`should_stop_after_turn` / `prepare_next_turn` 钩子、`PromptImage` 图片输入（5 入口）、`reset`/`continueRun` 入口。

**Architecture:** 钩子走既有 HookSystem（每钩子 on*/fire* + 专用函数式接口，链式/首非 null 生效）；触发点在 `ActionExecutor.executeTryFinishRun`。图片经 `PromptImage` record 映射 `ContentBlock.ImageContent`，队列元素升为 `QueuedPrompt`。reset/continueRun 是 `AgentHarness` 公开 API，`continueRun` 新增 `ActionExecutor.runContinue` 复用 run 启动路径但跳过 user entry。

**Tech Stack:** JDK 25 · JUnit 5 + AssertJ · Maven（模块 `pi-java-agent-core`）

**Spec:** `docs/16-agent-loop-alignment-design.md`

## Global Constraints

- 文件 ≤ 500 行；无 `@SuppressWarnings`；不加 `System.out.println`
- Commit 格式 `{feat,fix,docs}({module}): <message>`；`git add <path>` 显式路径
- 验证命令：`mvn test -pl pi-java-agent-core`（每任务）；`mvn clean verify`（收尾）
- 钩子异常永不传播：recordHookError（既有机制），视为弃权
- 现有调用方（TUI/RPC/coding-agent）零改动编译通过——所有新参数走重载

---

### Task 1: `should_stop_after_turn` 钩子

**Files:**
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/ShouldStopAfterTurnContext.java`
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/ShouldStopAfterTurnHook.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/HookSystem.java`（+2 方法）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`（executeTryFinishRun 注入判定）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/HookTest.java`（追加）

**Interfaces:**
- Consumes: 既有 `HookSystem.registry.register(lane, name, hook)`、`executeTryFinishRun` 内 `HarnessUtils.determineOutcome(lane)`
- Produces: `HookSystem.onShouldStopAfterTurn(String laneName, ShouldStopAfterTurnHook hook): AutoCloseable`、`fireShouldStopAfterTurn(String laneName, ShouldStopAfterTurnContext ctx): boolean`；context record `ShouldStopAfterTurnContext(String lane, String runId, AssistantMessage assistantMessage, List<Message> toolResults)`

- [ ] **Step 1: 写失败测试**（追加到 `HookTest.java`）

```java
@Test
void shouldStopAfterTurnTrueEndsRunAfterFirstTurn() {
    var h = harness();
    h.hookSystem().onShouldStopAfterTurn("default",
        ctx -> ctx.assistantMessage().content().stream()
            .anyMatch(b -> b instanceof ContentBlock.TextContent t && t.text().contains("stop-me"))
            ? Boolean.TRUE : null);
    h.run("go");
    var action = h.peekAction();
    while (action != null) { action = h.executeAction(action); }
    // after finish, queue a followUp: with hook true it must NOT start another run
    h.followUp("default", "second");
    assertThat(h.peekAction("default")).isNull(); // hook said stop → no new turn
}

@Test
void shouldStopAfterTurnNullFallsThrough() {
    var h = harness();
    h.hookSystem().onShouldStopAfterTurn("default", ctx -> null);
    driveToCompletion(h, "go");
    h.followUp("default", "second");
    assertThat(h.peekAction("default")).isNotNull(); // null → 不终止，正常起 run
}
```

注意：`followUp` 消费点在 `executeTryFinishRun` 末尾（`drainFollowUp`）。测试断言点应设在 finish 路径——若钩子 true，`run` 完成后 `peekAction` 为 null（不排 followUp）。若实现后发现 finish 路径已无条件排 followUp，则把断言改为：钩子 true 时 finish 记录的 outcome 仍为 completed 且**下一轮 LLM 调用次数不变**（用计数 StreamFn 断言调用次数 == 1）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl pi-java-agent-core -Dtest=HookTest`
Expected: COMPILATION ERROR（`onShouldStopAfterTurn` 不存在）

- [ ] **Step 3: 实现**——新建两个类型 + HookSystem 方法 + 触发点

`ShouldStopAfterTurnContext.java`:

```java
package com.pijava.agent.hook;

import java.util.List;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/** Context for {@code should_stop_after_turn} hooks (pi alignment). */
public record ShouldStopAfterTurnContext(
    String lane, String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults
) {
    public ShouldStopAfterTurnContext {
        toolResults = List.copyOf(toolResults);
    }
}
```

`ShouldStopAfterTurnHook.java`:

```java
package com.pijava.agent.hook;

/** Returns TRUE to end the run after this turn; NULL abstains. */
@FunctionalInterface
public interface ShouldStopAfterTurnHook {
    Boolean shouldStopAfterTurn(ShouldStopAfterTurnContext ctx);
}
```

`HookSystem.java` 追加（Registration 区 + Firing 区）:

```java
/** Register a {@code should_stop_after_turn} hook for the lane. */
public AutoCloseable onShouldStopAfterTurn(String laneName, ShouldStopAfterTurnHook hook) {
    return registry.register(laneName, "should_stop_after_turn", hook);
}

/**
 * Fire {@code should_stop_after_turn} hooks.
 * @return true when any hook returns TRUE (first non-null wins); false otherwise
 */
public boolean fireShouldStopAfterTurn(String laneName, ShouldStopAfterTurnContext ctx) {
    for (var hook : registry.get(laneName, "should_stop_after_turn")) {
        try {
            var r = ((ShouldStopAfterTurnHook) hook).shouldStopAfterTurn(ctx);
            if (r != null) return r;
        } catch (Exception e) {
            recordHookError(laneName, "should_stop_after_turn", e);
        }
    }
    return false;
}
```

`ActionExecutor.executeTryFinishRun` 注入——在 `// Terminal outcome` 注释块前（即 `status` 收敛为 completed/error 之后、写 `OperationFinished` 之前）:

```java
// pi alignment: shouldStopAfterTurn — after a completed turn, hooks may end
// the run before queued follow-ups would start the next one.
if ("completed".equals(status)) {
    var stop = ctx.hookSystem().fireShouldStopAfterTurn(laneName,
        new ShouldStopAfterTurnContext(laneName, lane.runId, lane.partial, List.of()));
    if (stop) {
        // Skip follow-up drain: the run ends now (pi agent-loop.ts:247-257).
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            OperationOutcome.COMPLETED, null));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, status));
        lane.phase = RunPhase.IDLE;
        return null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl pi-java-agent-core -Dtest=HookTest`
Expected: PASS（全部既有 + 2 新增）

- [ ] **Step 5: 全模块回归 + Commit**

```bash
mvn test -pl pi-java-agent-core
git add pi-java-agent-core/src/main/java/com/pijava/agent/hook/ShouldStopAfterTurnContext.java pi-java-agent-core/src/main/java/com/pijava/agent/hook/ShouldStopAfterTurnHook.java pi-java-agent-core/src/main/java/com/pijava/agent/hook/HookSystem.java pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java pi-java-agent-core/src/test/java/com/pijava/agent/harness/HookTest.java
git commit -m "feat(agent): add should_stop_after_turn hook"
```

---

### Task 2: `prepare_next_turn` 钩子（原子轮间切换）

**Files:**
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/PrepareNextTurnContext.java`
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/TurnUpdate.java`
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/PrepareNextTurnHook.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/hook/HookSystem.java`（+2 方法）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneState.java`（+1 字段）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`（触发 + 应用两处）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/HookTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的触发点位置；`AgentHarness.setModel/setThinkingLevel`（写 `state`）；`Entry.ModelChange(provider, modelId)` / `Entry.ThinkingLevelChange(thinkingLevel)`（label 字符串）；`ThinkingLevel` label：`minimal|low|medium|high|xhigh`
- Produces: `HookSystem.onPrepareNextTurn(String, PrepareNextTurnHook): AutoCloseable`、`firePrepareNextTurn(String, PrepareNextTurnContext): TurnUpdate`（链式合入，null 跳过）；`TurnUpdate(ModelId<?> model, String thinkingLevel)`；`LaneState.pendingTurnUpdate: TurnUpdate`（包私有字段）

**关键语义**：update 只影响本 run 内下一轮（挂 LaneState，`TryFinishRun` 完成时清除）；应用时 model/thinkingLevel 真变化才写 entry。因为 `ExecutionContext` 的 supplier 读的是 harness `state`，应用即调 `harnessState` 不可达——**通过新的 ctx 回调实现**：`ExecutionContext` 需加两个 setter 通道。见 Step 3。

- [ ] **Step 1: 写失败测试**（追加到 `HookTest.java`）

```java
@Test
void prepareNextTurnSwitchesModelForNextRequest() {
    // StreamFn captures which model each request used
    var seenModels = new java.util.ArrayList<String>();
    var partial = AssistantMessage.empty()
        .withContent(List.of(new ContentBlock.TextContent("ok")))
        .withStopReason("tool_use");
    StreamFn sf = (messages, model, options) -> {
        seenModels.add(model.modelName());
        return StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone("tool_use", null, partial)));
    };
    var h = harnessWith(sf);
    h.hookSystem().onPrepareNextTurn("default", ctx ->
        new TurnUpdate(ModelId.of("faux", "next-model"), null));
    h.run("go");
    drive(h);  // run to completion: turn1 (test-model) → hook → turn2 (next-model)
    assertThat(seenModels).containsExactly("test-model", "next-model");
    // transcript records the switch (pi-java auditability, pi does not write)
    assertThat(h.snapshot("default").transcript().stream()
        .anyMatch(e -> e instanceof com.pijava.agent.entry.Entry.ModelChange)).isTrue();
}

@Test
void prepareNextTurnDoesNotLeakAcrossRuns() {
    var seenModels = new java.util.ArrayList<String>();
    StreamFn sf = capturingStreamFn(seenModels, "stop");
    var h = harnessWith(sf);
    h.hookSystem().onPrepareNextTurn("default", ctx ->
        new TurnUpdate(ModelId.of("faux", "next-model"), null));
    drive(h, startedBy(h.run("run1")));  // full run with hook
    h.setModel(ModelId.of("faux", "test-model")); // reset model for run2
    drive(h, startedBy(h.run("run2")));  // update must NOT apply: cleared at run end
    assertThat(seenModels).containsExactly("test-model", "next-model", "test-model");
}
```

（`harnessWith`/`drive`/`startedBy` 是从既有 `harness()`/`driveToCompletion` 微调出的辅助：drive 返回 final null；`startedBy(Action a)` 直接返回传入 action。若已在测试内实现等价 helper 则复用。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl pi-java-agent-core -Dtest=HookTest`
Expected: COMPILATION ERROR（`TurnUpdate`/`onPrepareNextTurn` 不存在）

- [ ] **Step 3: 实现**

**3a. 三个新类型**（`hook` 包）：

```java
// PrepareNextTurnContext.java
package com.pijava.agent.hook;

import java.util.List;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;

/** Context for {@code prepare_next_turn} hooks (pi alignment). */
public record PrepareNextTurnContext(
    String lane, String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults
) {
    public PrepareNextTurnContext {
        toolResults = List.copyOf(toolResults);
    }
}

// TurnUpdate.java
package com.pijava.agent.hook;

import com.pijava.ai.model.ModelId;

/** Pending per-turn config change: null field = unchanged. */
public record TurnUpdate(ModelId<?> model, String thinkingLevel) {}

// PrepareNextTurnHook.java
package com.pijava.agent.hook;

/** Returns a TurnUpdate to apply to the next turn, or null. */
@FunctionalInterface
public interface PrepareNextTurnHook {
    TurnUpdate prepareNextTurn(PrepareNextTurnContext ctx);
}
```

**3b. HookSystem 两方法**（链式合入，同 `fireBeforePayload` 模式）：

```java
/** Register a {@code prepare_next_turn} hook for the lane. */
public AutoCloseable onPrepareNextTurn(String laneName, PrepareNextTurnHook hook) {
    return registry.register(laneName, "prepare_next_turn", hook);
}

/**
 * Fire {@code prepare_next_turn} hooks, chaining updates.
 * @return the merged update (null fields preserved from earlier hooks), or null
 */
public TurnUpdate firePrepareNextTurn(String laneName, PrepareNextTurnContext ctx) {
    TurnUpdate result = null;
    for (var hook : registry.get(laneName, "prepare_next_turn")) {
        try {
            var u = ((PrepareNextTurnHook) hook).prepareNextTurn(ctx);
            if (u != null) {
                result = new TurnUpdate(
                    u.model() != null ? u.model() : result != null ? result.model() : null,
                    u.thinkingLevel() != null ? u.thinkingLevel() : result != null ? result.thinkingLevel() : null);
            }
        } catch (Exception e) {
            recordHookError(laneName, "prepare_next_turn", e);
        }
    }
    return result;
}
```

**3c. LaneState 字段**：

```java
/** Pending turn update from prepare_next_turn hooks; consumed by next StreamAssistant. */
TurnUpdate pendingTurnUpdate;
```

**3d. ExecutionContext 增加两个状态回写通道**（record 加组件——`AgentHarness` 构造处同步传入）：

```java
// 新增两个 record 组件（放 summaryGenerator 之后）：
java.util.function.BiConsumer<ModelId<?>, String> turnConfigApplier,   // (model, thinkingLevelLabel) — model/label 可分别为 null
// 以及 LaneState 清理仍走直接字段访问（同包），无需新通道
```

`AgentHarness` 构造时：

```java
(model, label) -> {
    if (model != null) state.model = model;
    if (label != null) {
        state.thinkingLevel = "off".equals(label)
            ? ModelThinkingLevel.off()
            : ModelThinkingLevel.of(parseLevel(label));  // parseLevel: label → ThinkingLevel，未知抛 IllegalArgumentException
    }
}
```

**3e. ActionExecutor 触发点**（`executeTryFinishRun`，Task 1 的 shouldStop 判定**之前**——pi 顺序 agent-loop.ts:232→248）：

```java
// pi alignment: prepareNextTurn — hooks may change model/thinking for the
// next turn of THIS run (cleared at run end, so it never leaks across runs).
if ("completed".equals(status) || "tool_use".equals(status)) {
    var upd = ctx.hookSystem().firePrepareNextTurn(laneName,
        new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
    if (upd != null) lane.pendingTurnUpdate = upd;
}
```

注意：`tool_use` 分支在方法开头已提前 return，所以此块放在 method 开头 status 判定**之前**用 `tfr.outcome()` 原值判断，或放在 tool_use 提前 return 块内各加一次。**采用前者**：方法开头加：

```java
// fire prepare_next_turn before any outcome branch (pi fires after turn_end, before shouldStop)
var outcome0 = tfr.outcome();
if ("completed".equals(outcome0) || "tool_use".equals(outcome0)) {
    var upd = ctx.hookSystem().firePrepareNextTurn(laneName,
        new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
    if (upd != null) lane.pendingTurnUpdate = upd;
}
```

**3f. 应用点**（`executeStreamAssistant`，abort 检查后、`checkAutoCompact` 前）：

```java
// Apply pending prepare_next_turn update (pi alignment: atomic next-turn switch).
if (lane.pendingTurnUpdate != null) {
    var upd = lane.pendingTurnUpdate;
    lane.pendingTurnUpdate = null;
    applyTurnUpdate(laneName, lane, upd);
}
```

新私有方法：

```java
private void applyTurnUpdate(String laneName, LaneState lane, TurnUpdate upd) {
    if (upd.model() != null) {
        var current = ctx.model().get();
        boolean changed = !current.modelName().equals(upd.model().modelName())
            || !current.provider().equals(upd.model().provider());
        ctx.turnConfigApplier().accept(upd.model(), null);
        if (changed) {
            var e = new Entry.ModelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                HarnessUtils.lastEntryId(lane), java.time.Instant.now(),
                upd.model().provider(), upd.model().modelName());
            lane.transcript.add(e);
            lane.pendingWrites.add(e);
        }
    }
    if (upd.thinkingLevel() != null) {
        boolean changed;
        if ("off".equals(upd.thinkingLevel())) {
            changed = !(ctx.thinkingLevel().get() instanceof ModelThinkingLevel.Off);
        } else {
            var cur = ctx.thinkingLevel().get();
            changed = !(cur instanceof ModelThinkingLevel.Enabled en
                && en.level().label().equals(upd.thinkingLevel()));
        }
        ctx.turnConfigApplier().accept(null, upd.thinkingLevel());
        if (changed) {
            var e = new Entry.ThinkingLevelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                HarnessUtils.lastEntryId(lane), java.time.Instant.now(), upd.thinkingLevel());
            lane.transcript.add(e);
            lane.pendingWrites.add(e);
        }
    }
}
```

**3g. run 结束清除**（`executeTryFinishRun` 的 Terminal outcome 收敛路径与 shouldStop 提前 return 路径，`lane.phase = RunPhase.IDLE` 旁）：

```java
lane.pendingTurnUpdate = null;  // pi: config lives in runLoop locals, dies with the run
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl pi-java-agent-core -Dtest=HookTest`
Expected: PASS

- [ ] **Step 5: 全模块回归 + Commit**

```bash
mvn test -pl pi-java-agent-core
git add pi-java-agent-core/src/main/java/com/pijava/agent/hook/ pi-java-agent-core/src/main/java/com/pijava/agent/harness/ pi-java-agent-core/src/test/java/com/pijava/agent/harness/HookTest.java
git commit -m "feat(agent): add prepare_next_turn hook with atomic per-run turn switch"
```

---

### Task 3: 图片输入（PromptImage + 5 入口重载 + QueuedPrompt）

**Files:**
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/PromptImage.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/AgentHarness.java`（5 个重载）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`（run 构造 entry 合并 content；injectUserMessages 同）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneInfo.java`（QueuedItem 升级）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/QueueManager.java`（队列元素/排空类型）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneState.java`（三队列泛型）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/PromptImageTest.java`（新建）

**Interfaces:**
- Consumes: `ContentBlock.ImageContent(mediaType, data)`；既有 run/steer/followUp/nextRun 签名
- Produces: `PromptImage(String mimeType, String data)` + `toContentBlock(): ContentBlock.ImageContent` + static `validate`；`AgentHarness.run(String, List<PromptImage>)` / `run(String, String, List<PromptImage>)` / `steer(String, String, List<PromptImage>)` / `followUp(String, String, List<PromptImage>)` / `nextRun(String, String, List<PromptImage>)`；`LaneInfo.QueuedItem(String prompt, List<PromptImage> images, long seq)`（**三参，破坏性改动——包内私有，编译错误驱动修改所有构造点**）

- [ ] **Step 1: 写失败测试**（新建 `PromptImageTest.java`，setup 复制 `QueueSchedulingTest.harness()` 模式）

```java
package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptImageTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", partial),
            new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            DriveMode.MANUAL, null, java.util.Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static void drive(AgentHarness h) {
        var action = h.peekAction();
        while (action != null) { action = h.executeAction(action); }
    }

    private static List<ContentBlock> lastUserContent(AgentHarness h) {
        var msgs = h.snapshot("default").transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .toList();
        return ((Entry.Message) msgs.get(msgs.size() - 1)).message().content();
    }

    @Test
    void runWithImagesBuildsTextThenImageContent() {
        var h = harness();
        h.run("look", List.of(new PromptImage("image/png", "aGk=")));
        var content = lastUserContent(h);
        assertThat(content).hasSize(2);
        assertThat(content.get(0)).isInstanceOf(ContentBlock.TextContent.class);
        var img = (ContentBlock.ImageContent) content.get(1);
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(img.data()).isEqualTo("aGk=");
    }

    @Test
    void steerWithImagesCarriesImagesIntoInjectedEntry() {
        var h = harness();
        h.run("first");
        drive(h);
        h.steer("default", "with pic", List.of(new PromptImage("image/jpeg", "eg==")));
        var action = h.peekAction("default"); // idle + steer queued → new run
        assertThat(action).isNotNull();
        drive2(h, action);
        var content = lastUserContent(h);
        assertThat(content.get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(content).anyMatch(b -> b instanceof ContentBlock.ImageContent);
    }

    @Test
    void invalidMimeTypeRejectedAtEntry() {
        var h = harness();
        assertThatThrownBy(() -> h.run("x", List.of(new PromptImage("text/plain", "aGk="))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.run("x", List.of(new PromptImage("image/png", ""))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plainTextOverloadUnchanged() {
        var h = harness();
        h.run("plain");
        assertThat(lastUserContent(h)).hasSize(1);
    }

    private static void drive2(AgentHarness h, Action first) {
        var action = first;
        while (action != null) { action = h.executeAction(action); }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl pi-java-agent-core -Dtest=PromptImageTest`
Expected: COMPILATION ERROR（`PromptImage` 不存在）

- [ ] **Step 3: 实现**

**3a. PromptImage**：

```java
package com.pijava.agent.harness;

import com.pijava.ai.message.ContentBlock;

/**
 * A base64 inline image attached to a prompt (pi {@code ImageContent} alignment:
 * base64 only; URL images are a pi-java provider-layer extension, out of scope).
 */
public record PromptImage(String mimeType, String data) {

    public PromptImage {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("mimeType must start with 'image/': " + mimeType);
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("data must be non-empty");
        }
    }

    public ContentBlock.ImageContent toContentBlock() {
        return new ContentBlock.ImageContent(mimeType, data);
    }
}
```

（校验放 canonical constructor → 任何入口构造即抛，天然满足"入口即抛"。）

**3b. LaneInfo.QueuedItem 升级**：

```java
/** A queued steer/followUp/nextRun item. */
public record QueuedItem(String prompt, List<PromptImage> images, long seq) {
    public QueuedItem {
        images = List.copyOf(images);
    }
    /** 纯文本便利构造（现有调用点兼容）。 */
    public QueuedItem(String prompt, long seq) {
        this(prompt, List.of(), seq);
    }
}
```

**3c. QueueManager**：`steer/followUp/nextRun` 各加 `List<PromptImage> images` 参数版本（原 String 版委托之：`steer(lane, prompt, List.of())`）；drain 返回 `List<LaneInfo.QueuedItem>`（改名 `drainSteerItems` 等，或直接改返回类型——**改返回类型**，调用点只有 `ActionExecutor` 两处）。

**3d. ActionExecutor**：`run(laneName, prompt)` → `run(laneName, prompt, List<PromptImage> images)`，构造 content：

```java
var content = new java.util.ArrayList<ContentBlock>();
content.add(new ContentBlock.TextContent(prompt));
if (images != null) images.forEach(img -> content.add(img.toContentBlock()));
```

（`run(laneName, prompt)` 保留为委托 `run(laneName, prompt, List.of())`。）`injectUserMessages(LaneState, List<LaneInfo.QueuedItem>)` 同样合并。`peekAction` Idle/Assistant 分支把 `String.join` 改为传递 items（多 item 合并策略：多个 queued item 拼接为一个 run 时，**逐 item 追加 content 到同一 user entry**——保持与现状 `String.join("\n\n", steer)` 等价的"合为一条消息"语义，文本间以 `\n\n` 连接）。

**3e. AgentHarness 五个重载**（默认 lane 版 + lane 版；纯文本版保留委托）：

```java
public Action run(String prompt, List<PromptImage> images) { return run(defaultLaneName, prompt, images); }
public Action run(String laneName, String prompt, List<PromptImage> images) {
    if (closed) throw new HarnessClosedException();
    telemetry.incrementCounter("harness.turn", 1);
    return actionExecutor.run(laneName, prompt, images == null ? List.of() : images);
}
public String steer(String laneName, String prompt, List<PromptImage> images) { ... }
public String followUp(String laneName, String prompt, List<PromptImage> images) { ... }
public String nextRun(String laneName, String prompt, List<PromptImage> images) { ... }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl pi-java-agent-core -Dtest=PromptImageTest`
Expected: PASS

- [ ] **Step 5: 全模块回归 + Commit**

```bash
mvn test -pl pi-java-agent-core
git add pi-java-agent-core/src/main/java/com/pijava/agent/harness/ pi-java-agent-core/src/test/java/com/pijava/agent/harness/PromptImageTest.java
git commit -m "feat(agent): base64 image input for run/steer/followUp/nextRun"
```

---

### Task 4: `reset(lane)` / `continueRun(lane)`

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/AgentHarness.java`（+2 公开方法）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`（+`runContinue`；`run` 抽出共用启动逻辑）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/ResetContinueTest.java`（新建）

**Interfaces:**
- Consumes: Task 2 的 `pendingTurnUpdate` 字段（reset 须清）；既有 `queueManager.cancelQueued`；`RunPhase.Idle` 判定
- Produces: `AgentHarness.reset(String laneName): void`、`AgentHarness.continueRun(String laneName): Action`、`AgentHarness.reset(): void` / `continueRun(): void` 默认 lane 便利重载；`ActionExecutor.runContinue(String laneName): Action`

- [ ] **Step 1: 写失败测试**（新建 `ResetContinueTest.java`，setup 同 PromptImageTest 模式）

```java
@Test
void resetClearsTranscriptAndQueuesWhenIdle() {
    var h = harness();
    h.run("one");
    drive(h);
    h.followUp("default", "queued");
    h.reset("default");
    var snap = h.snapshot("default");
    assertThat(snap.transcript()).isEmpty();
    assertThat(snap.queues().followUp()).isEmpty();
    assertThat(snap.queues().steer()).isEmpty();
    assertThat(snap.queues().nextRun()).isEmpty();
    // can seed + run again afterwards
    assertThat(h.peekAction("default")).isNull();
}

@Test
void resetWhileRunningThrows() {
    var h = harness();
    h.run("start");
    assertThatThrownBy(() -> h.reset("default")).isInstanceOf(IllegalStateException.class);
    drive(h); // cleanup for later tests' isolation
}

@Test
void continueRunFromToolResultEndRuns() {
    // last transcript message is a user → continue adds no new user entry
    var h = harness();
    h.run("first");
    drive(h);
    int entriesBefore = h.snapshot("default").transcript().size();
    h.continueRun("default");
    drive(h);
    var entries = h.snapshot("default").transcript();
    // only assistant entries appended (no new user entry)
    assertThat(entries.size()).isGreaterThan(entriesBefore);
    assertThat(entries.subList(entriesBefore, entries.size()))
        .noneMatch(e -> e instanceof Entry.Message m && "user".equals(m.message().role()));
}

@Test
void continueRunOnEmptyOrAssistantLastThrows() {
    var h = harness();
    assertThatThrownBy(() -> h.continueRun("default"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no messages");
    h.run("go");
    drive(h); // last message is assistant
    assertThatThrownBy(() -> h.continueRun("default"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("assistant");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl pi-java-agent-core -Dtest=ResetContinueTest`
Expected: COMPILATION ERROR（`reset`/`continueRun` 不存在）

- [ ] **Step 3: 实现**

**3a. AgentHarness**（Operation 区，abort 附近）：

```java
/** Clear lane transcript, all queues, and run state (pi Agent.reset alignment). */
public void reset(String laneName) {
    if (closed) throw new HarnessClosedException();
    actionExecutor.reset(laneName);
    publishState(laneName);
}

/** Continue a run from the current transcript tail (pi Agent.continue alignment). */
public Action continueRun(String laneName) {
    if (closed) throw new HarnessClosedException();
    var action = actionExecutor.runContinue(laneName);
    publishState(laneName);
    return action;
}

public void reset() { reset(defaultLaneName); }
public Action continueRun() { return continueRun(defaultLaneName); }
```

**3b. ActionExecutor.reset**：

```java
void reset(String laneName) {
    var lane = ctx.requireLane(laneName);
    if (!(lane.phase instanceof RunPhase.Idle)) {
        throw new IllegalStateException("Cannot reset: lane " + laneName + " is running");
    }
    synchronized (lane) {
        lane.transcript.clear();
        lane.pendingWrites.clear();
        lane.records.clear();
        lane.pendingToolCalls.clear();
        lane.partial = null;
        lane.newestOwn = null;
        lane.runId = null;
        lane.stepIndex = 0;
        lane.pendingTurnUpdate = null;
        lane.steerQueue.clear();
        lane.followUpQueue.clear();
        lane.nextRunQueue.clear();
    }
}
```

**3c. ActionExecutor.runContinue**（复用 run 的启动骨架，跳过 user entry / before_run 的 prompt 语义——before_run hook 仍发，promptList 为空）：

```java
/** Continue a run from the current transcript (pi agentLoopContinue alignment). */
Action runContinue(String laneName) {
    var lane = ctx.requireLane(laneName);
    if (!(lane.phase instanceof RunPhase.Idle)) {
        throw new IllegalStateException("Cannot continue: lane " + laneName + " is not idle");
    }
    if (lane.transcript.isEmpty()) {
        throw new IllegalStateException("Cannot continue: no messages in context");
    }
    var last = lane.lastEntry();
    if (last instanceof Entry.Message m
            && m.message() instanceof Message.AssistantMessage) {
        throw new IllegalStateException("Cannot continue from message role: assistant");
    }
    lane.runId = UUID.randomUUID().toString();
    lane.stepIndex = 0;
    lane.partial = null;
    lane.newestOwn = null;
    lane.records.clear();
    lane.pendingToolCalls.clear();
    lane.abortSignal = AbortSignal.create();

    ctx.hookSystem().fireBeforeRun(laneName,
        new RunContext(laneName, lane.runId, List.of()));
    lane.records.add(new LaneRecord.OperationStarted(
        UUID.randomUUID().toString(), 0, laneName, null, null,
        new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)));
    ctx.incrementTurn();
    lane.phase = RunPhase.ASSISTANT;
    return peekAction(laneName);
}
```

（注意：`continueRun` 不清 transcript/pendingWrites——这正是与 `run` 的差异点。`pendingWrites` 有残留属于上一 run 未持久化的 tail，保留让 AppendEntry 继续消费。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl pi-java-agent-core -Dtest=ResetContinueTest`
Expected: PASS

- [ ] **Step 5: 全模块回归 + 全仓 verify + Commit**

```bash
mvn test -pl pi-java-agent-core
mvn clean verify
git add pi-java-agent-core/src/main/java/com/pijava/agent/harness/AgentHarness.java pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java pi-java-agent-core/src/test/java/com/pijava/agent/harness/ResetContinueTest.java
git commit -m "feat(agent): add reset and continueRun entry points"
```

---

## 收尾

- [ ] `mvn clean verify` 零错误零警告、无 `System.out.println` 残留
- [ ] 更新 `docs/phase1-pi-code-mapping.md` 对照表（4 项缺口标记已闭环）——单独 commit `docs: mark loop-alignment gaps closed in pi mapping`
