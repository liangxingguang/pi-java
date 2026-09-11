# Deferred 写入记录层 + entry 溯源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/23` 的四个工作流——#4 assistant `stopReason` 落到 entry（唯一真相，删 `StepAttempt.stopReason`）、#1 `WriteDeferred` 记录层（真实发射点 + fold 派生 + 校验）、#2 `toolBatch` 派生、#3 `terminalFailure` 溯源。

**Architecture:** 先打溯源地基（entry 携带 stopReason），再建 deferred 记录层（生产者接到既有 `lane.pendingWrites` 入列点），最后在其 `deferredWriteIds` 之上派生 `toolBatch`/`terminalFailure`。期间把 `LaneStateFolder` 拆为 3 类、从 `ActionExecutor` 抽出 `AssistantStreamExecutor`，保证每个文件 ≤ 500 行。

**Tech Stack:** JDK 25 · JUnit 5 + AssertJ · Jackson · Maven（模块 `pi-java-ai` / `pi-java-agent-core`）

**Spec:** `docs/23-deferred-writes-and-entry-provenance-design.md`

## Global Constraints

- **不碰 provider 层**：不加 `SimpleStreamOptions.deferred`，不加 `fetchDeferred`/`cancelDeferred`（docs/23 §6-1）
- **`DeferredHandle` 无生产者**：`deferred` 派生与 `invalid_deferred_handle` 校验**仅由测试覆盖**；不得为了让它们"有生产者"而偷偷加 provider 代码
- **D4 是行为变更**：`stopReason ∈ {deferred, error, aborted}` 的 assistant entry 投影为零条 provider 消息。**不允许为了让测试变绿而回退该规则**；若既有用例依赖旧行为，逐个审查并在 commit message 中说明
- 文件 ≤ 500 行；无 `@SuppressWarnings`；不加 `System.out.println`
- Commit 格式 `{feat,fix,docs,refactor}({module}): <message>`；`git add <path>` 显式路径，禁 `-A`/`.`
- 分支 `phase23-deferred-provenance`
- 验证命令（每任务）：
  `JAVA_HOME="D:/soft/jdk/graalvm-jdk-25" D:/soft/apache-maven-3.9.9/bin/mvn -o -am -pl <模块> clean verify`
  **`-am` 必须带**：不带会吃 `~/.m2` 旧构件导致假绿（本仓库反复踩过）
- 收尾：全 reactor `mvn -o clean verify` 零错误零警告、spotbugs/checkstyle 零违规

## 写计划时发现的三处实施级问题（已并入任务）

| # | 问题 | 处置 |
|---|---|---|
| **P1** | 设计 §3.2 写了 `lane.partial.deferred()`，但流式快照 `com.pijava.ai.message.AssistantMessage` 只有 `(id, content, usage, stopReason)`，**无 `deferred`** | **不给它加**（无生产者，加了是死字段）。Task 2 传 `null` 并在代码注释说明；`Message.AssistantMessage.deferred` 仅由测试构造（与 D2/B 一致）。设计文档 §3.2 已同步勘误 |
| **P2** | `MistralConversationsApi.java:204` 的 `case Message.AssistantMessage(var content)` 是**解构模式**，组件 1→3 直接编译失败。32 个单参构造点有兼容构造器兜住，模式匹配兜不住 | Task 1 显式修该模式为 `Message.AssistantMessage a` + `a.content()` |
| **P3** | `JsonEventMapper`（`coding-agent/mode/`）用**裸 `ObjectMapper`** 序列化 `agent_end.messages`，加组件会**自动**让 RPC/print wire 多出 `stopReason`/`deferred` 键——计划外的对外协议变更 | Task 2 加 `MessageMixin`（`@JsonInclude(NON_NULL)`，与既有 `StreamEventMixin` 同法）抑制 null；并用测试**钉住** `stopReason` 出现在 wire 上是**有意**的。⚠️ 这是对外可见变更，见 Task 2 的决策说明 |

## 目标文件结构（设计 §3.5 锁定）

| 文件 | 动作 | 职责 |
|---|---|---|
| `pi-java-ai/.../ai/message/DeferredHandle.java` | 新建 | provider 延迟响应句柄（无生产者） |
| `pi-java-ai/.../ai/message/Message.java` | 改 | `AssistantMessage` +`stopReason` +`deferred` +兼容构造器 |
| `pi-java-ai/.../ai/protocol/MistralConversationsApi.java` | 改 | 解构模式 arity 修复（P2） |
| `pi-java-agent-core/.../session/SessionJson.java` | 改 | 编码 assistant 的 stopReason/deferred |
| `pi-java-agent-core/.../session/jsonl/MessageJsonCodec.java` | 改 | 解码 stopReason/deferred |
| `pi-java-coding-agent/.../mode/JsonEventMapper.java` | 改 | `MessageMixin` 抑制 null（P3） |
| `pi-java-agent-core/.../session/ContextEntries.java` | 改 | D4 投影规则 |
| `pi-java-agent-core/.../record/LaneRecord.java` | 改 | 删 `StepAttempt.stopReason` |
| `pi-java-agent-core/.../harness/LaneStateFolder.java` | 改/瘦身 | `fold` 入口 + `FoldedState` + `EffectiveConfiguration` + 编排 |
| `pi-java-agent-core/.../harness/RecordLogValidator.java` | 新建 | `validateRecordLog` 子集 + 全部 corruption 规则 |
| `pi-java-agent-core/.../harness/LaneOperationFold.java` | 新建 | op 派生：phase/stepIndex/newestOwn/faulted/aborted/deferred/pendingWrites/toolBatch/terminalFailure |
| `pi-java-agent-core/.../harness/AssistantStreamExecutor.java` | 新建 | `executeStreamAssistant` 全流程 |
| `pi-java-agent-core/.../harness/ActionExecutor.java` | 改/瘦身 | 委托 `AssistantStreamExecutor`；pendingWrites 入列处发 `WriteDeferred` |

---

### Task 1: ai 层 — `DeferredHandle` + assistant 消息携带 stopReason

**Files:**
- Create: `pi-java-ai/src/main/java/com/pijava/ai/message/DeferredHandle.java`
- Modify: `pi-java-ai/src/main/java/com/pijava/ai/message/Message.java:47-57`
- Modify: `pi-java-ai/src/main/java/com/pijava/ai/protocol/MistralConversationsApi.java:204`
- Test: `pi-java-ai/src/test/java/com/pijava/ai/message/MessageTest.java`

**Interfaces:**
- Produces: `DeferredHandle(String provider, String modelId, String api, String id, Long expiresAt, Long pollAfterMs, Map<String,Object> data)`；`Message.AssistantMessage(List<ContentBlock> content, String stopReason, DeferredHandle deferred)` + 兼容构造器 `AssistantMessage(List<ContentBlock>)`；访问器 `stopReason()` / `deferred()`

- [ ] **Step 1: 写失败测试**（追加到 `MessageTest.java`）

```java
    @Test
    void assistantMessageCarriesStopReasonAndDeferredHandle() {
        var handle = new DeferredHandle("faux", "test-model", "faux-api", "batch-1",
            1_700_000_000_000L, 500L, Map.of("row", 2));
        var msg = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")), "deferred", handle);

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.stopReason()).isEqualTo("deferred");
        assertThat(msg.deferred()).isEqualTo(handle);
        assertThat(msg.role()).isEqualTo("assistant");
    }

    @Test
    void singleArgConstructorLeavesStopReasonAndDeferredNull() {
        var msg = new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")));

        assertThat(msg.stopReason()).isNull();
        assertThat(msg.deferred()).isNull();
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME="D:/soft/jdk/graalvm-jdk-25" D:/soft/apache-maven-3.9.9/bin/mvn -o -am -pl pi-java-ai test -Dtest=MessageTest`
Expected: FAIL — 编译错误 `找不到符号: 类 DeferredHandle`

- [ ] **Step 3: 建 `DeferredHandle`**

```java
package com.pijava.ai.message;

import java.util.Map;

/**
 * Provider handle for a deferred (asynchronous) response, aligned with pi
 * {@code ai/types.ts:397}.
 *
 * <p>The handle is what a provider returns instead of a final assistant
 * message when the request continues in the background. Carrying it lets the
 * final message be reconstructed later from {@code data}.</p>
 *
 * <p><b>No producer in pi-java</b> (docs/23 D2): no provider implements
 * deferral, so nothing constructs one outside tests. pi is in the same state —
 * its type exists but only the faux test provider returns one.</p>
 *
 * @param provider    provider name (e.g. {@code "anthropic"})
 * @param modelId     model id the request was made against
 * @param api         provider API discriminator (e.g. {@code "anthropic-messages"})
 * @param id          provider token: a response id or batch id plus row id
 * @param expiresAt   optional epoch-ms expiry
 * @param pollAfterMs optional suggested poll delay
 * @param data        provider conversion data needed to rebuild the message
 */
public record DeferredHandle(
    String provider,
    String modelId,
    String api,
    String id,
    Long expiresAt,
    Long pollAfterMs,
    Map<String, Object> data
) {
    /** Defensively copies {@code data} when non-null. */
    public DeferredHandle {
        data = data == null ? null : Map.copyOf(data);
    }
}
```

- [ ] **Step 4: 改 `Message.AssistantMessage`**

把 `Message.java:47-57` 的 record 替换为（`DeferredHandle` 同包，无需 import）：

```java
    /**
     * A message from the assistant (LLM).
     *
     * <p>{@code stopReason} is the reason the turn ended ({@code stop} /
     * {@code tool_use} / {@code length} / {@code error} / {@code aborted} /
     * {@code deferred}), or {@code null} for messages that never came from a
     * completed stream. It is the single source of truth for the reason
     * (docs/23 D1) — readers must not keep a parallel copy.
     * {@code deferred} is the provider handle carried only when
     * {@code stopReason} is {@code "deferred"}.</p>
     */
    record AssistantMessage(
        List<ContentBlock> content,
        String stopReason,
        DeferredHandle deferred
    ) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public AssistantMessage {
            content = List.copyOf(content);
        }

        /**
         * Compatibility constructor: every pre-existing call site, and data
         * written before stop reasons were recorded, carries neither field.
         */
        public AssistantMessage(List<ContentBlock> content) {
            this(content, null, null);
        }

        @Override
        public String role() {
            return "assistant";
        }
    }
```

- [ ] **Step 5: 修解构模式 arity（P2）**

`MistralConversationsApi.java:204` 的 `case Message.AssistantMessage(var content) ->` 改为：

```java
                case Message.AssistantMessage a -> {
                    m.put("role", "assistant");
                    m.put("content", extractText(a.content()));
                }
```

（record 组件 1→3 后，`Message.AssistantMessage(var content)` 会报 arity 不匹配。该文件是全仓唯一的 `Message` 解构模式 switch。）

- [ ] **Step 6: 跑测试确认通过**

Run: `JAVA_HOME="D:/soft/jdk/graalvm-jdk-25" D:/soft/apache-maven-3.9.9/bin/mvn -o -am -pl pi-java-ai clean verify`
Expected: PASS（含既有 32 个调用点——全部经兼容构造器不加改动编译通过）

- [ ] **Step 7: 提交**

```bash
git add pi-java-ai/src/main/java/com/pijava/ai/message/DeferredHandle.java \
        pi-java-ai/src/main/java/com/pijava/ai/message/Message.java \
        pi-java-ai/src/main/java/com/pijava/ai/protocol/MistralConversationsApi.java \
        pi-java-ai/src/test/java/com/pijava/ai/message/MessageTest.java
git commit -m "feat(ai): DeferredHandle + stopReason/deferred on assistant messages

..."
```

---

### Task 2: agent-core — entry 持久化 stopReason，删除 `StepAttempt.stopReason`

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/SessionJson.java`（`messageNode`，约 :55）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/jsonl/MessageJsonCodec.java:29-46`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java:462-465`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/record/LaneRecord.java`（`StepAttempt` 185-201 + `committed` 75-78）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/jsonl/RecordJsonCodec.java:35-46`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneStateFolder.java`（`newestOwn` 176-194，删 `stopReasonFor` 196-206）
- Modify: `pi-java-coding-agent/src/main/java/com/pijava/coding/agent/mode/JsonEventMapper.java`（+`MessageMixin`，`render` 的 `SessionJson` 字节格式与 PiWireByteFormat 测试不受影响）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/session/jsonl/RecordObservabilityCodecTest.java`、`pi-java-agent-core/src/test/java/com/pijava/agent/harness/LaneStateFoldTest.java`、`pi-java-coding-agent/src/test/java/com/pijava/coding/agent/mode/JsonEventMapperTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Message.AssistantMessage(content, stopReason, deferred)` 与 `stopReason()`
- Produces: JSONL `{role:"assistant", content:[...], stopReason?, deferred?}`；`LaneRecord.StepAttempt` 组件表**不含** `stopReason`；`LaneStateFolder.newestOwn` 从 entry 读 stopReason

- [ ] **Step 1: 写失败测试（持久化 + 旧文件兼容）**

追加到 `RecordObservabilityCodecTest.java`：

```java
    @Test
    void assistantEntryRoundTripsItsStopReason() {
        var entry = new Entry.Message("e-1", 11L, null, Instant.now(),
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("done")), "tool_use", null), null);

        var parsed = (Entry.Message) encodeThenParseEntry(entry);

        assertThat(parsed.message()).isInstanceOf(Message.AssistantMessage.class);
        assertThat(((Message.AssistantMessage) parsed.message()).stopReason())
            .isEqualTo("tool_use");
    }

    @Test
    void assistantEntryWithoutStopReasonDecodesAsNull() {
        // 旧文件只有 {role, content}（Task 2 之前的格式）。
        var node = new ObjectMapper().createObjectNode();
        node.put("role", "assistant");
        node.putArray("content").addObject().put("type", "text").put("text", "old");

        var decoded = MessageJsonCodec.decode(node);

        assertThat(((Message.AssistantMessage) decoded).stopReason()).isNull();
        assertThat(((Message.AssistantMessage) decoded).deferred()).isNull();
    }
```

> 若 `RecordObservabilityCodecTest` 无 `encodeThenParseEntry` 之类的 helper，用既有的 `JsonlCodec.encodeMutation` + `EntryJsonCodec.decode` 组合（参照该文件既有 round-trip 用例的写法）。

追加到 `JsonEventMapperTest.java`（P3：钉住对外 wire 形状）：

```java
    @Test
    void agentEndWireCarriesAssistantStopReasonAndOmitsNullDeferred() {
        var end = new AgentSessionEvent.AgentEnd(List.of(
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("done")), "stop", null)),
            false);

        var node = render(end);

        var msg = node.get("messages").get(0);
        assertThat(msg.get("stopReason").asText()).isEqualTo("stop");
        assertThat(msg.has("deferred")).isFalse();   // NON_NULL mixin
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=RecordObservabilityCodecTest`
Run: `... mvn -o -am -pl pi-java-coding-agent test -Dtest=JsonEventMapperTest`
Expected: FAIL — assistant 分支只读 `content`，`stopReason` 解码为 null；wire 上没有 `stopReason` 键

- [ ] **Step 3: 编码（`SessionJson.messageNode`）**

在 `messageNode` 的 `ToolResultMessage` 分支之后追加：

```java
        if (message instanceof Message.AssistantMessage assistant) {
            if (assistant.stopReason() != null) {
                node.put("stopReason", assistant.stopReason());
            }
            if (assistant.deferred() != null) {
                node.set("deferred", MAPPER.valueToTree(assistant.deferred()));
            }
        }
```

（`NON_NULL` 配置已在 mapper 上，null 字段自动省略——不需要额外判空，但显式判空让意图明确。）

- [ ] **Step 4: 解码（`MessageJsonCodec`）**

替换 `case "assistant"` 分支：

```java
            case "assistant" -> new Message.AssistantMessage(
                content,
                JsonlCodec.optionalString(node, "stopReason"),
                decodeDeferred(node.get("deferred")));
```

新增私有方法：

```java
    private static DeferredHandle decodeDeferred(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid deferred handle");
        }
        return new DeferredHandle(
            JsonlCodec.requireString(node, "provider"),
            JsonlCodec.requireString(node, "modelId"),
            JsonlCodec.requireString(node, "api"),
            JsonlCodec.requireString(node, "id"),
            JsonlCodec.optionalLong(node, "expiresAt"),
            JsonlCodec.optionalLong(node, "pollAfterMs"),
            JsonlCodec.optionalObject(node, "data"));
    }
```

（`optionalString` 在缺字段时返回 null → 旧文件天然兼容。）

- [ ] **Step 5: 落库时保留 stopReason（丢弃点）**

`ActionExecutor.java:464`：

```java
                new Message.AssistantMessage(lane.partial.content(),
                    // stopReason 随 entry 落库，成为唯一真相（docs/23 D1）。
                    lane.partial.stopReason(),
                    // 无 provider 支持 deferral，此处恒为 null（docs/23 D2/P1）。
                    null), null);
```

- [ ] **Step 6: 删除 `StepAttempt.stopReason`**

1. `LaneRecord.java`：`StepAttempt` record 去掉最后一个 `String stopReason` 组件（185-201）；`committed()` 的 `case StepAttempt`（75-78）去掉 `e.stopReason()` 实参。
2. `RecordJsonCodec.java:35-46`：`step_attempt` 分支去掉 `JsonlCodec.optionalString(node, "stopReason")` 实参。
3. 更新 **6 处**构造点（位置参数少一个）：
   - `LaneStateFoldTest.java`（helper `step`，去掉末位 `"stop"`）
   - `LaneRecordTest.java` 两处（去掉末位值，并删对应的 `rec.stopReason()` 断言）
   - `RecordObservabilityCodecTest.java` 两处（去掉末位值，并删对应的 `"stopReason"` 断言）
   - `ConformanceSupport.java`（去掉末位 null）
   - `RunSummaryAggregatorTest.java` fixture（去掉末位 `"completed"` 实参）
   - **`CompactionExecutor.java`**（第 6 处，plan 初版遗漏；该处末位传 `null`，不改则不编译）
   - ⚠️ **`RunSummaryAggregatorTest` 的 `assertThat(full.stopReason())` 断言必须保留**：它断言的是 `RunSummaryAggregator.Summary.stopReason`（由 `withMeta(int, long, String)` 设置），与被删的 `LaneRecord.StepAttempt.stopReason` 是**无关组件**，且是该参数唯一的覆盖。plan 初版误判为应删（Task 2 review 已裁决）。

> `StepAttempt` 的 `compactionReason` 组件**保留**（`invalid_compaction_reason` 校验依赖它）。

- [ ] **Step 7: fold 改从 entry 读 stopReason**

`LaneStateFolder.newestOwn`（176-194）改为读 entry 的 stopReason，并删除 `stopReasonFor`（196-206）：

```java
    private static LaneState.NewestOwn newestOwn(List<Entry> ownEntries) {
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && msg.message() instanceof Message.AssistantMessage assistant) {
                return new LaneState.NewestOwn(
                    msg.id(), "message", "assistant", assistant.stopReason());
            }
        }
        return null;
    }
```

调用点 `fold()` 相应改为 `newestOwn(ownEntries)`（去掉 `ordered` 实参）。同时在 `fold()` 内 `newestOwn` 之后不再需要 `ordered` 参与该派生。

> 断言依据：`Message.AssistantMessage` 是 `Entry.Message` 的唯一 assistant 载体；用类型匹配而非 `"assistant".equals(role())`，顺带与 `HarnessUtils.deriveNewestOwn`（live 侧）语义对齐。

- [ ] **Step 8: 抑制 wire 上的 null（P3）**

`JsonEventMapper.java`：仿照既有 `StreamEventMixin` 加混入，并在 mapper 构建处注册：

```java
    /** Omits null components so the wire shape only grows when a field is set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class MessageMixin {}
```

```java
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .addMixIn(StreamEvent.class, StreamEventMixin.class)
        .addMixIn(Message.class, MessageMixin.class);
```

> ⚠️ **对外协议变更（需知悉）**：加 mixin 后 `agent_end.messages` 里每条 assistant 消息会多出 **非 null** 的 `stopReason`（`deferred` 仍不出现）。这是**有意的**（该信息此前不可见），Task 的测试已钉住。若你希望 wire 保持原样，改法是给 mixin 加 `@JsonIgnoreProperties`/`@JsonIgnore` 排除这两个组件——**请在审核本计划时告知**。

- [ ] **Step 9: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`
Run: `... mvn -o -am -pl pi-java-coding-agent clean verify`
Expected: PASS。**特别确认** `LaneStateFoldTest` 的哨兵断言（fold==live）仍成立——它比对 `newestOwn.stopReason` 与 live 的 `lastAssistantMessage().stopReason()`，是本任务最强的回归网。

- [ ] **Step 10: 提交**

```bash
git add <上述全部路径，逐条显式>
git commit -m "feat(agent-core): persist assistant stopReason on the entry, drop StepAttempt.stopReason

..."
```

---

### Task 3: agent-core — D4 上下文投影规则

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/ContextEntries.java`（`project`，148-165）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/session/ContextEntriesTest.java`

**Interfaces:**
- Consumes: Task 2 落库的 `Message.AssistantMessage.stopReason()`
- Produces: `ContextEntries.toMessages` 对 `stopReason ∈ {deferred, error, aborted}` 的 assistant 消息返回零条

- [ ] **Step 1: 写失败测试**

追加到 `ContextEntriesTest.java`：

```java
    @Test
    void deferredErrorAndAbortedAssistantMessagesProjectToNothing() {
        for (String stopReason : List.of("deferred", "error", "aborted")) {
            var assistant = assistantEntry("a-" + stopReason, "partial text", stopReason);
            var messages = ContextEntries.toMessages(List.of(
                userEntry("u-1", "hello"), assistant));

            assertThat(messages)
                .as("stopReason=%s 的 assistant 消息必须投影为零条（pi spec docs/harness-v2.md:164）", stopReason)
                .extracting(Message::role)
                .containsExactly("user");
        }
    }

    @Test
    void completedToolUseAndLengthMessagesStillProject() {
        for (String stopReason : List.of("stop", "tool_use", "length")) {
            var assistant = assistantEntry("a-" + stopReason, "answer", stopReason);

            assertThat(ContextEntries.toMessages(List.of(assistant)))
                .as("stopReason=%s 不属于被投影掉的集合", stopReason)
                .hasSize(1);
        }
    }

    @Test
    void assistantWithoutStopReasonStillProjects() {
        // 旧数据 / 非流式构造的消息 stopReason 为 null，必须保留。
        var assistant = (Entry.Message) new Entry.Message("a-null", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("answer"))), null);

        assertThat(ContextEntries.toMessages(List.of(assistant))).hasSize(1);
    }
```

> 复用该文件既有的 `entry(...)`/`user(...)` 构造 helper；若命名不同，按文件内既有风格加一个 `assistantEntry(id, text, stopReason)` 局部 helper。

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=ContextEntriesTest`
Expected: FAIL — `deferredErrorAndAbortedAssistantMessagesProjectToNothing` 得到 2 条而非 1 条

- [ ] **Step 3: 实现投影规则**

`ContextEntries` 加常量：

```java
    /**
     * Assistant stop reasons whose message carries no content worth sending
     * back to the provider. Authority is pi's **spec**
     * ({@code docs/harness-v2.md:164}: "Assistant responses with stop reason
     * error, aborted, or deferred project to no provider message. A genuine
     * output-limit length response remains in context"), not pi's current
     * code — pi's {@code session/context.ts:72} filters {@code deferred} only,
     * so this rule is a superset of pi's code and an exact match of its spec.
     * Cite the spec when claiming alignment.
     */
    private static final Set<String> NON_PROJECTED_STOP_REASONS =
        Set.of("deferred", "error", "aborted");
```

`project(Entry)` 的 assistant 分支（当前 `if (e instanceof Entry.Message m) { return m.message(); }`）改为：

```java
        if (e instanceof Entry.Message m) {
            if (m.message() instanceof Message.AssistantMessage assistant
                    && assistant.stopReason() != null
                    && NON_PROJECTED_STOP_REASONS.contains(assistant.stopReason())) {
                return null;
            }
            return m.message();
        }
```

> **必须显式判 null**：`Set.of(...)` 的不可变集合在 `contains(null)` 时抛 NPE（Phase 21 在 `COMPACTION_REASONS` 上踩过同一个坑）。

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=ContextEntriesTest`
Expected: PASS

- [ ] **Step 5: 跑全模块，逐个审查受影响的既有用例**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`

预期会触及（据普查，未必全红，需逐个看）：`CrossTurnContextTest`（断言请求消息条数）、`CompactionContextMessagesTest`、`HookTest`（记录 stream 调用次数）、`AgentHarnessTest`/`ManualDriveTurnTest`（error stream）。

**处置规则**：若某用例因为「error/aborted 消息不再进请求」而变红，说明它钉的是**旧行为**——更新该用例的期望值，并在 commit message 里列出改了哪几个用例、为什么。**不得回退规则本身**（Global Constraints）。

Run: `... mvn -o -am -pl pi-java-coding-agent clean verify`
（特别看 `SessionRunnerRetryContextTest`——它钉 `dropTrailingErrorAssistant`；该机制移除的是 transcript 里的尾部 error entry，与投影规则正交，应仍绿。若红，说明两者叠加后语义有变，需单独审查。）

- [ ] **Step 6: 提交**

```bash
git add <ContextEntries.java + 受影响的测试文件，逐条显式>
git commit -m "feat(agent-core): project deferred/error/aborted assistant entries out of provider context

..."
```

---

### Task 4: refactor — 拆 `LaneStateFolder`、抽 `AssistantStreamExecutor`

> **纯搬迁，零行为变更。** 所有既有测试必须原样通过——这是本任务唯一的验收标准。

**Files:**
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/RecordLogValidator.java`
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneOperationFold.java`
- Create: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/AssistantStreamExecutor.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneStateFolder.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`

**Interfaces:**
- Produces:
  - `RecordLogValidator.validate(String lane, List<LaneRecord> records)`（原 `LaneStateFolder.validateRecordLog`）
  - `LaneOperationFold.fold(String lane, List<LaneRecord> ordered, List<Entry> ownEntries, List<Entry> configurationEntries) : LaneOperationFold.Result`（承载 phase/runId/stepIndex/newestOwn/faulted/aborted/配置/三个队列）
  - `AssistantStreamExecutor.execute(String laneName, LaneState lane, Action.StreamAssistant sa) : Action`
- Consumes: 既有 `ExecutionContext`

- [ ] **Step 1: 确认基线全绿**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`
Expected: PASS（记录当前测试数，作为搬迁后的比对基线）

- [ ] **Step 2: 抽 `RecordLogValidator`**

把 `LaneStateFolder` 的 `validateRecordLog`（294-356）、`validateCompactionReason`（358-370）、`validateAttemptSequence`（372-383）、`validateQueueEnqueue`（385-395）、`validateQueueCancellation`（397-412）、`corrupt`（444-446）、`COMPACTION_REASONS`（40-42）整块搬到新类。`LaneStateFolder.validateRecordLog` 变成一行委托（或调用方改调新类）。

**`RecordLogValidator` 用 JUnit 是包内可见**：保持 `package-private final class` + `private` 构造器，与 `LaneStateFolder` 同法。

- [ ] **Step 3: 抽 `LaneOperationFold`**

搬 `openOperation`（135-147）、`lastFinish`（149-158）、`stepIndex`（160-174）、`newestOwn`（176-194）、`effectiveConfiguration`（208-225）、`pendingQueue`（227-259）、`toQueuedItem`（261-288）、`runIdOf`（418-437）、`orderBySeq`（439-442）到新类；`fold()` 的编排逻辑一并搬入，`LaneStateFolder.fold` 只保留入口 + `FoldedState`/`EffectiveConfiguration` 两个 record 的定义。

> 若搬完 `LaneOperationFold` 仍 > 500 行，按派生主题再切一刀（`LaneOperationFold` 管 op/phase/newestOwn，`LaneQueueFold` 管 pendingQueue/toQueuedItem）——**但只在确实超限时才切**，不要预先拆。

- [ ] **Step 4: 抽 `AssistantStreamExecutor`**

搬 `executeStreamAssistant`（335-488）与 `failTruncatedToolCalls`（644-671，含 javadoc），以及**只属于 stream 路径**的字段 `contextAssembler`（51、58）与这 13 个 import：`java.util.stream.Collectors`、`CompactionService`、`OverflowDetector`、`RequestContext`、`ResponseContext`、`StepKind`、`UsageCause`、`AgentTool`、`Usage`、`ToolDefinition`、`ContentBlock`、`StreamEvent`、`SpanOptions`。

`ActionExecutor` 的 dispatch（288）改为委托：

```java
            case Action.StreamAssistant sa -> assistantStream.execute(laneName, lane, sa);
```

`ActionExecutor` 侧删掉已经搬走、且无其他引用的 import（`CompactionService`、`OverflowDetector`、`RequestContext`、`ResponseContext`、`StepKind`、`UsageCause`、`AgentTool`、`Usage`、`ToolDefinition`、`ContentBlock`、`StreamEvent`、`SpanOptions`、`Collectors`），**保留**仍被非 stream 路径使用的：`CompactionSettings`、`AssistantMessage`、`Message`、`ModelThinkingLevel`、`AbortSignal`、`ExecutionMode`、`Entry`、`LaneRecord`、`OperationOutcome`、`QueueKind`。**删完必须编译**——checkstyle 会因未用 import 失败，这正是我们要的信号。

> `LoopInvariants`（phase→allowed-action）**不需要改**：本任务是搬迁，action 的产生逻辑与类型集合都没变。

- [ ] **Step 5: 校验文件行数**

```bash
wc -l pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneStateFolder.java \
      pi-java-agent-core/src/main/java/com/pijava/agent/harness/RecordLogValidator.java \
      pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneOperationFold.java \
      pi-java-agent-core/src/main/java/com/pijava/agent/harness/AssistantStreamExecutor.java \
      pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java
```
Expected: **每个都 ≤ 500**。`ActionExecutor` 从 691 降下来是本任务的硬指标之一。

- [ ] **Step 6: 跑测试确认零行为变更**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`
Expected: 与 Step 1 的**测试数完全一致**、全 PASS、spotbugs 0

- [ ] **Step 7: 提交**

```bash
git add <上述全部路径，逐条显式>
git commit -m "refactor(agent-core): split LaneStateFolder; extract AssistantStreamExecutor

..."
```

---

### Task 5: agent-core — `WriteDeferred` 发射 + fold 派生 + 校验

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java`（4 处 `pendingWrites.add`）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneOperationFold.java`（+`pendingWrites`/`deferred`/`deferredWriteIds`）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneStateFolder.java`（`FoldedState` +3 字段）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/RecordLogValidator.java`（+2 条规则）
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/AgentHarness.java`（`restoreFromRecords` 落 `pendingWrites`）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/WriteDeferredEmissionTest.java`（新）、`LaneStateFoldTest.java`（追加）

**Interfaces:**
- Consumes: `LaneRecord.WriteDeferred(id, seq, lane, timestamp, runId, ProvisionedEntry<?> target)`（已存在）
- Produces: `FoldedState.pendingWrites() : List<ProvisionedEntry<?>>`、`FoldedState.deferred() : DeferredHandle`、`LaneOperationFold.deferredWriteIds()`

- [ ] **Step 1: 写失败测试（发射点）**

新建 `WriteDeferredEmissionTest.java`，沿用 `QueueRecordEmissionTest` 的 harness 脚手架（`simpleStreamFn` / `echoTool` / `toolUseThenStopStreamFn` / `drive`）：

```java
    @Test
    void runStartUserPromptIsNotRecordedAsDeferred() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // run 起始时 lane 仍为 IDLE，属直接 append（docs/23 D3）。
        assertThat(ofType(h, "default", LaneRecord.WriteDeferred.class)).isEmpty();
    }

    @Test
    void assistantReplyProducedMidRunIsRecordedAsDeferred() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        var deferred = ofType(h, "default", LaneRecord.WriteDeferred.class);
        assertThat(deferred).hasSize(1);
        assertThat(((LaneRecord.WriteDeferred) deferred.get(0)).runId()).isNotEmpty();
    }

    @Test
    void toolResultsAndMidRunSteerAreRecordedAsDeferred() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);
        var action = h.run("default", "go");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            action = h.executeAction("default", action);
        }
        h.steer("default", "steer mid-run");
        var next = action;
        while (next != null) {
            next = h.executeAction("default", next);
        }

        // 工具结果条目 + 中途 steer 注入条目 + 第二轮 assistant 回复
        assertThat(ofType(h, "default", LaneRecord.WriteDeferred.class).size())
            .isGreaterThanOrEqualTo(3);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=WriteDeferredEmissionTest`
Expected: FAIL — `WriteDeferred` 零发射，全部 `isEmpty()`

- [ ] **Step 3: 实现发射点**

`ActionExecutor` 新增私有方法，并在**全部 4 处** `lane.pendingWrites.add(entry)` 之后调用（`:108`(run)、`:307`(injectUserMessages)、`:464`(assistant)、`:668`(failTruncatedToolCalls)）：

```java
    /**
     * Record an in-flight write as deferred (docs/23 D3).
     *
     * <p>pi's rule is "a lane-view entry write during a run becomes a durable
     * deferred write; while idle it appends" (docs/harness-v2.md:1894). The
     * lane's own equivalent of "writing while a run is in flight" is adding to
     * {@code pendingWrites} while the lane is not idle — a run's own output
     * (assistant reply, tool results, mid-run steer, truncation feedback) is
     * deferred, while the prompt that starts the run is a direct append.</p>
     */
    private static void recordDeferredWrite(LaneState lane, Entry entry) {
        if (lane.phase instanceof RunPhase.Idle) {
            return;
        }
        lane.records.add(new LaneRecord.WriteDeferred(
            UUID.randomUUID().toString(), 0, lane.laneName, null,
            lane.runId == null ? "" : lane.runId,
            new ProvisionedEntry<>(entry)));
    }
```

> `runId` 判空理由同 `AbortRequested`：`RecordJsonCodec` 对 `write_deferred.runId` 用 `requireString`，空串可序列化；`RecordLogValidator.runIdOf` 把空串当"无 runId"跳过 unknown_operation 检查。

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=WriteDeferredEmissionTest`
Expected: PASS

- [ ] **Step 5: 写失败测试（fold 派生 + 校验）**

追加到 `LaneStateFoldTest.java`：

```java
    @Test
    void foldPendingWritesIsEmptyOnALiveLane() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // 有意的退化解：每个写入点都是 `lane.transcript.add(e)` 紧跟 `lane.pendingWrites.add(e)`，
        // 所以 WriteDeferred 的 target 必然已在 ownEntries 里 ⇒ fold 视为「已应用」。
        // live 的 pendingWrites 是「尚未持久化」的流动标记，与 fold 的「已接受未应用」
        // 不是同一个集合，因此不可拿两者比大小。
        assertThat(foldOf(h, "default").pendingWrites()).isEmpty();
    }

    @Test
    void foldPendingWritesSurfacesWritesWhoseTargetNeverLanded() {
        // 崩溃场景：write_deferred 记录已落库，target entry 没落库 —— 恢复时必须视为待应用。
        // 这才是本派生的唯一真实消费者。
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(userMessage("never-persisted", "lost")));

        var folded = LaneStateFolder.fold("default", List.of(write), List.of(), List.of());

        assertThat(folded.pendingWrites()).hasSize(1);
        assertThat(folded.pendingWrites().get(0).entry().id()).isEqualTo("never-persisted");
    }

    @Test
    void foldKeepsPendingWritesWhenTheOperationAborted() {
        // 与 steer/followUp 不同：延迟写入在 abort 后仍保留（pi reducer.ts:543-558）。
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(userMessage("never-persisted", "lost")));
        var records = List.<LaneRecord>of(
            new LaneRecord.OperationStarted("run-1", 0, "default", null, null,
                new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)),
            new LaneRecord.AbortRequested("a-1", 0, "default", null, "run-1"),
            write,
            new LaneRecord.OperationFinished("f-1", 0, "default", null, "run-1",
                OperationOutcome.ABORTED, null, null));

        assertThat(LaneStateFolder.fold("default", records, List.of(), List.of()).pendingWrites())
            .hasSize(1);
    }

    @Test
    void foldRejectsDeferredAssistantEntryWithoutHandle() {
        var entry = new Entry.Message("a-1", 0, null, null,
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("")), "deferred", null), null);
        assertThatThrownBy(() -> LaneStateFolder.fold("default", List.of(), List.of(entry), List.of()))
            .isInstanceOf(RecordLogCorruption.class)
            .hasMessageContaining("invalid_deferred_handle");
    }

    @Test
    void foldRejectsWriteDeferredTargetThatContradictsAnExistingEntry() {
        var existing = new Entry.Message("t-1", 1, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("real"))), null);
        var contradicting = new ProvisionedEntry<>(new Entry.Message("t-1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("other"))), null));
        var record = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "", contradicting);

        assertThatThrownBy(() -> LaneStateFolder.fold("default", List.of(record),
            List.of(existing), List.of()))
            .isInstanceOf(RecordLogCorruption.class)
            .hasMessageContaining("provisioned_entry_mismatch");
    }
```

- [ ] **Step 6: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=LaneStateFoldTest`
Expected: FAIL — `FoldedState` 无 `pendingWrites()`；两条校验规则不存在

- [ ] **Step 7: 实现派生**

`LaneOperationFold` 新增（对齐 pi `reducer.ts:553-558 / 595-603 / 611-613`）：

```java
    /**
     * Accepted-but-not-yet-applied writes: a {@code write_deferred} whose
     * target id is absent from the operation's own entries.
     *
     * <p>Unlike the queue pending sets, this is NOT zeroed on abort — a
     * deferred write survives cancellation and is still applied
     * (pi reducer.ts:543-558).</p>
     */
    private static List<ProvisionedEntry<?>> pendingWrites(
            List<LaneRecord> operationRecords, Set<String> ownEntryIds) {
        List<ProvisionedEntry<?>> pending = new ArrayList<>();
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.WriteDeferred write
                    && !ownEntryIds.contains(write.target().entry().id())) {
                pending.add(write.target());
            }
        }
        return pending;
    }

    /** The ids of every deferred write this operation requested. */
    static Set<String> deferredWriteIds(List<LaneRecord> operationRecords) {
        Set<String> ids = new LinkedHashSet<>();
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.WriteDeferred write) {
                ids.add(write.target().entry().id());
            }
        }
        return ids;
    }

    /**
     * The provider handle of an unredeemed deferred response: only the
     * newest own entry counts, so a follow-on entry redeems it
     * (pi reducer.ts:595-603).
     */
    private static DeferredHandle deferred(List<Entry> ownEntries) {
        if (ownEntries.isEmpty()) {
            return null;
        }
        var newest = ownEntries.get(ownEntries.size() - 1);
        if (newest instanceof Entry.Message msg
                && msg.message() instanceof Message.AssistantMessage assistant
                && "deferred".equals(assistant.stopReason())) {
            return assistant.deferred();
        }
        return null;
    }
```

`FoldedState` 增 3 个组件（`pendingWrites`、`deferred`；`deferredWriteIds` 是本任务内部用、Task 6 用，**不进** `FoldedState`——它由 Task 6 在 `LaneOperationFold` 内部消费）：

```java
        List<ProvisionedEntry<?>> pendingWrites,
        DeferredHandle deferred,
```

compact 构造器里 `pendingWrites = List.copyOf(pendingWrites);`。

`AgentHarness.restoreFromRecords` 增加一行：`lane.pendingWrites.clear(); lane.pendingWrites.addAll(folded.pendingWrites());`

- [ ] **Step 8: 实现两条校验规则**

`RecordLogValidator.validate` 内：
1. 遍历 entries，命中「assistant message 且 `stopReason=="deferred"` 且 `deferred()==null`」→ `corrupt("invalid_deferred_handle", ...)`（pi `reducer.ts:272-283`）。**签名需增加 entries 入参**（当前只有 records）——同步改 `LaneStateFolder.fold` 的调用点。
2. `WriteDeferred` 的 target：若其 id 已在 entries 中存在，则内容必须一致，否则 `corrupt("provisioned_entry_mismatch", ...)`（pi `reducer.ts:383-385`）。

- [ ] **Step 9: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`
Expected: PASS

- [ ] **Step 10: 提交**

```bash
git add <逐条显式>
git commit -m "feat(agent-core): emit write_deferred from in-flight pending writes

..."
```

---

### Task 6: agent-core — `toolBatch` + `terminalFailure` 派生

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneOperationFold.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/LaneStateFolder.java`（`FoldedState` +2 字段）
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/harness/ToolBatchFoldTest.java`（新）、`TerminalFailureFoldTest.java`（新）

**Interfaces:**
- Consumes: Task 5 的 `deferredWriteIds`
- Produces: `FoldedState.toolBatch() : ToolBatch`、`FoldedState.terminalFailure() : TerminalFailure`；`LaneOperationFold.ToolBatch(String assistantEntryId, List<ToolBatchCall> calls)`、`ToolBatchCall(String toolCallId, String toolName, String resultEntryId, boolean missing)`、`TerminalFailure(String entryId, String source, Message message)`

- [ ] **Step 1: 写失败测试（toolBatch）**

新建 `ToolBatchFoldTest.java`。核心断言：

```java
    @Test
    void eachToolCallIsMatchedToItsResultByToolCallId() {
        // assistant entry 含 2 个 toolCall；两条 toolResult 均有对应 toolUseId。
        var assistant = assistantWithToolCalls("a-1", List.of("call-1", "call-2"));
        var results = List.<Entry>of(
            toolResult("r-1", "call-1", "ok"),
            toolResult("r-2", "call-2", "ok"));
        var own = new ArrayList<Entry>();
        own.add(assistant);
        own.addAll(results);

        var folded = LaneStateFolder.fold("default", List.of(), own, List.of());

        assertThat(folded.toolBatch().assistantEntryId()).isEqualTo("a-1");
        assertThat(folded.toolBatch().calls())
            .extracting(ToolBatchFold.ToolBatchCall::toolCallId)
            .containsExactly("call-1", "call-2");
        assertThat(folded.toolBatch().calls())
            .noneMatch(ToolBatchFold.ToolBatchCall::missing);
    }

    @Test
    void unmatchedToolCallIsMarkedMissing() {
        var assistant = assistantWithToolCalls("a-1", List.of("call-1", "call-2"));
        var own = List.<Entry>of(assistant, toolResult("r-1", "call-1", "ok"));

        var folded = LaneStateFolder.fold("default", List.of(), own, List.of());

        assertThat(folded.toolBatch().calls())
            .filteredOn(ToolBatchFold.ToolBatchCall::missing)
            .extracting(ToolBatchFold.ToolBatchCall::toolCallId)
            .containsExactly("call-2");
    }

    @Test
    void deferredWriteIsNotMistakenForAToolResult() {
        // 延迟写入的 target id 与被排除的 toolResult 同 id：必须不被当成结果。
        var assistant = assistantWithToolCalls("a-1", List.of("call-1"));
        var ghostResult = toolResult("r-ghost", "call-1", "should not count");
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(ghostResult));
        var own = List.<Entry>of(assistant, ghostResult);

        var folded = LaneStateFolder.fold("default", List.of(write), own, List.of());

        assertThat(folded.toolBatch().calls().get(0).missing()).isTrue();
    }
```

> 局部 helper：`assistantWithToolCalls(id, ids)` 构造内容为 `ToolUseContent(ids.get(i), "echo", Map.of())` 的 `Entry.Message`；`toolResult(id, toolUseId, text)` 构造 `Entry.Message` 包 `new Message.ToolResultMessage(toolUseId, "echo", List.of(TextContent(text)), false)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=ToolBatchFoldTest`
Expected: FAIL — `FoldedState` 无 `toolBatch()`

- [ ] **Step 3: 实现 toolBatch**

```java
    /** A tool batch: the assistant entry that requested calls, and each call's outcome. */
    record ToolBatch(String assistantEntryId, List<ToolBatchCall> calls) {
        ToolBatch {
            calls = List.copyOf(calls);
        }
    }

    /** One call of a batch; {@code missing} when no result entry was found. */
    record ToolBatchCall(String toolCallId, String toolName, String resultEntryId,
                         boolean missing) {}

    /**
     * Pair the newest assistant entry's tool calls with their results
     * (pi {@code deriveToolBatch}, reducer.ts:479-486).
     *
     * <p>Results are matched by {@code toolCallId}, not by queue-drain
     * correspondence — a tool result is always one entry per call, so the
     * merged-drain shape that blocks queue inference elsewhere does not apply
     * here. Entries are compared by position, not {@code seq}: an uncommitted
     * entry's seq is 0.</p>
     */
    private static ToolBatch toolBatch(List<Entry> ownEntries, Set<String> deferredWriteIds) {
        int assistantIndex = -1;
        List<ContentBlock.ToolUseContent> calls = List.of();
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && msg.message() instanceof Message.AssistantMessage assistant) {
                var toolCalls = assistant.content().stream()
                    .filter(ContentBlock.ToolUseContent.class::isInstance)
                    .map(ContentBlock.ToolUseContent.class::cast)
                    .toList();
                if (!toolCalls.isEmpty()) {
                    assistantIndex = i;
                    calls = toolCalls;
                    break;
                }
            }
        }
        if (assistantIndex < 0) {
            return null;
        }
        List<ToolBatchCall> matched = new ArrayList<>();
        for (var call : calls) {
            String resultEntryId = null;
            for (int i = assistantIndex + 1; i < ownEntries.size(); i++) {
                if (ownEntries.get(i) instanceof Entry.Message msg
                        && msg.message() instanceof Message.ToolResultMessage result
                        && call.id().equals(result.toolUseId())
                        && !deferredWriteIds.contains(msg.id())) {
                    resultEntryId = msg.id();
                    break;
                }
            }
            matched.add(new ToolBatchCall(call.id(), call.name(), resultEntryId,
                resultEntryId == null));
        }
        return new ToolBatch(((Entry.Message) ownEntries.get(assistantIndex)).id(), matched);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=ToolBatchFoldTest`
Expected: PASS

- [ ] **Step 5: 写失败测试（terminalFailure）**

新建 `TerminalFailureFoldTest.java`：

```java
    @Test
    void errorEntryProducedByAStepIsAttributedToStep() {
        var assistant = errorAssistant("a-1");
        var record = new LaneRecord.StepAttempt("s-1", 0, "default", null, "run-1",
            StepKind.ASSISTANT, 0, "a-1", null, null, null, null, null, null);

        var folded = LaneStateFolder.fold("default", List.of(record), List.of(assistant), List.of());

        assertThat(folded.terminalFailure()).isNotNull();
        assertThat(folded.terminalFailure().entryId()).isEqualTo("a-1");
        assertThat(folded.terminalFailure().source()).isEqualTo("step");
    }

    @Test
    void errorEntryPrecededByADeferredEntryIsAttributedToDeferredFetch() {
        var deferred = deferredAssistant("a-0", "handle-1");
        var error = errorAssistant("a-1");

        var folded = LaneStateFolder.fold("default", List.of(), List.of(deferred, error), List.of());

        assertThat(folded.terminalFailure().source()).isEqualTo("deferred_fetch");
        assertThat(folded.terminalFailure().entryId()).isEqualTo("a-1");
    }

    @Test
    void errorEntryWithNoProvenanceYieldsNoTerminalFailure() {
        var folded = LaneStateFolder.fold("default", List.of(),
            List.of(errorAssistant("a-1")), List.of());

        assertThat(folded.terminalFailure()).isNull();
    }

    @Test
    void deferredWriteErrorEntryIsNotATerminalFailure() {
        var error = errorAssistant("a-1");
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(error));

        var folded = LaneStateFolder.fold("default", List.of(write), List.of(error), List.of());

        assertThat(folded.terminalFailure()).isNull();
    }
```

> helper：`errorAssistant(id)` = `stopReason=="error"` 且无 handle；`deferredAssistant(id, handleId)` = `stopReason=="deferred"` 且带 `DeferredHandle`。

- [ ] **Step 6: 跑测试确认失败**

Run: `... mvn -o -am -pl pi-java-agent-core test -Dtest=TerminalFailureFoldTest`
Expected: FAIL — `FoldedState` 无 `terminalFailure()`

- [ ] **Step 7: 实现 terminalFailure**

```java
    /** The newest error entry, with the provenance that explains how it arose. */
    record TerminalFailure(String entryId, String source, Message message) {}

    /**
     * Attribute an error entry to what produced it (pi reducer.ts:614-640).
     *
     * <p>An error entry counts only if a step attempt or a deferred fetch
     * produced it — otherwise the error is not the operation's terminal
     * failure. An applied deferred write is excluded outright, so a write that
     * happens to be an error message is never mistaken for a failure.</p>
     */
    private static TerminalFailure terminalFailure(List<Entry> ownEntries,
                                                   List<LaneRecord> operationRecords,
                                                   Set<String> deferredWriteIds) {
        if (ownEntries.isEmpty()) {
            return null;
        }
        var newest = ownEntries.get(ownEntries.size() - 1);
        if (!(newest instanceof Entry.Message msg)
                || !(msg.message() instanceof Message.AssistantMessage assistant)
                || !"error".equals(assistant.stopReason())
                || deferredWriteIds.contains(msg.id())) {
            return null;
        }
        boolean producedByStep = false;
        boolean producedByDeferredFetch = false;
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.StepAttempt step
                    && msg.id().equals(step.resultEntryId())) {
                producedByStep = true;
            }
            if (record instanceof LaneRecord.UsageRecord usage
                    && usage.cause() == UsageCause.DEFERRED_FETCH
                    && msg.id().equals(usage.entryId())) {
                producedByDeferredFetch = true;
            }
        }
        if (ownEntries.size() >= 2
                && ownEntries.get(ownEntries.size() - 2) instanceof Entry.Message previous
                && previous.message() instanceof Message.AssistantMessage prevAssistant
                && "deferred".equals(prevAssistant.stopReason())) {
            producedByDeferredFetch = true;
        }
        if (producedByStep || producedByDeferredFetch) {
            return new TerminalFailure(msg.id(),
                producedByStep ? "step" : "deferred_fetch", msg.message());
        }
        return null;
    }
```

`FoldedState` 增 `ToolBatch toolBatch`、`TerminalFailure terminalFailure` 两个组件。

- [ ] **Step 8: 跑测试确认通过**

Run: `... mvn -o -am -pl pi-java-agent-core clean verify`
Expected: PASS

- [ ] **Step 9: 全 reactor 收尾验证**

Run: `JAVA_HOME="D:/soft/jdk/graalvm-jdk-25" D:/soft/apache-maven-3.9.9/bin/mvn -o clean verify`
Expected: 11 模块全 SUCCESS、零失败、spotbugs/checkstyle 零违规

- [ ] **Step 10: 提交**

```bash
git add <逐条显式>
git commit -m "feat(agent-core): derive toolBatch + terminalFailure

..."
```

---

## 验收对照（docs/23 §9）

| 验收项 | 由哪个任务保证 |
|---|---|
| 全 reactor `mvn clean verify` 零错误零警告 | Task 6 Step 9 |
| `LaneStateFoldTest` 哨兵不退化 | Task 2 Step 9、Task 5 Step 9 |
| `ContextProjectionTest` 三种 stopReason 各自断言 | Task 3 Step 1–4（落在 `ContextEntriesTest`） |
| `WriteDeferredEmissionTest` run 中发记录、run 起始不发 | Task 5 Step 1–4 |
| `ToolBatchFoldTest`/`TerminalFailureFoldTest` 含 `missing` 与两种 provenance | Task 6 |
| `WriteDeferred` 有真实发射点（docs/21 §9 的 11 变体至此达成） | Task 5 Step 3 |
| `StepAttempt` 无 `stopReason`；entry 为唯一真相 | Task 2 Step 6–7 |
| 拆分后每文件 ≤ 500 行 | Task 4 Step 5 |
| 无 `System.out.println` 残留 | 各任务 Step 的 verify 兜底 |

## Self-Review 记录

- **spec 覆盖**：docs/23 §3.1→Task 1；§3.2→Task 2；§3.3(D4)→Task 3；§3.5(拆分)→Task 4；§3.4 的 `pendingWrites`/`deferred`（#1）→Task 5；`toolBatch`/`terminalFailure`（#2/#3）+ 两条校验→Task 5/6。§5 测试清单逐条落到任务。**无遗漏**。
- **类型一致性**：`DeferredHandle`（7 组件，Task 1 定义）在 Task 2/5 一致使用；`ToolBatch`/`ToolBatchCall`/`TerminalFailure` 在 Task 6 定义并自洽；`FoldedState` 分两批加字段（Task 5 加 2、Task 6 加 2），Task 5 的测试不引用 Task 6 的字段。
- **占位符**：无 TBD/TODO；每个代码步骤都给了可编译的实体代码。
