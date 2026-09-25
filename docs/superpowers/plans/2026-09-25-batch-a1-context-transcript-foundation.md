# Batch A1 Context/Transcript Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backward-compatible transcript model and context normalizer so pi-java can represent a leading system message without changing existing provider wire consumers.

**Architecture:** Keep the current agent-facing `Context(systemPrompt, messages, tools)` and `StreamRequest` constructors as compatibility inputs. Add an AI-layer `Message.SystemMessage` and branded-by-convention `TranscriptContext`, with a pure `ContextNormalizer` that folds the legacy fields into one leading system message. Extend the agent-core entry projection and session JSON codec to preserve system messages, but do not yet migrate providers, implement prompt-section replay, or produce tool-addition/removal events.

**Tech Stack:** JDK 25, Java records/sealed interfaces, Jackson, JUnit 5, AssertJ, Maven reactor.

**Spec:** `docs/48-ai-next-work-items-design.md` §3, §4 item 1, §5 Batch A first task, §6 acceptance gates; approved design in conversation: backward-compatible layered migration for A1.

## Global Constraints

- Do not add or remove Maven dependencies.
- Do not change provider semantics, request payloads, or provider-specific routing in A1. A provider file may receive only the mechanical exhaustive-switch/default adaptation required for the Java compiler after adding `Message.SystemMessage`, with no handling beyond an explicit unsupported-message guard.
- Do not add an independent persisted `SystemMessage` entry type; system messages remain `Entry.Message` payloads.
- Preserve existing JSONL/SQLite message shape and old `Context`/`StreamRequest` construction paths.
- Do not implement prompt-section replacement or tool incremental production; those are later A tasks.
- Use records/sealed interfaces where the data is a closed algebraic shape.
- Do not add unexplained `@SuppressWarnings`.
- Keep Java source files at or below 500 lines; split focused helpers if needed.
- Run Maven commands serially and set `JAVA_HOME` to `D:\soft\jdk\graalvm-jdk-25`.
- Each task must leave focused tests passing, include a mutation probe or explicit structural explanation, and use an explicit `git add <path>` list.

---

## File Map

- Create `pi-java-ai/src/main/java/com/pijava/ai/message/Message.java` changes only for the new message variant and compatibility documentation; it remains the AI message algebra.
- Create `pi-java-ai/src/main/java/com/pijava/ai/api/ToolReference.java` as the AI-layer tool-name reference used by future system-message deltas.
- Create `pi-java-ai/src/main/java/com/pijava/ai/api/TranscriptContext.java` as the immutable normalized provider-facing context container.
- Create `pi-java-ai/src/main/java/com/pijava/ai/api/ContextNormalizer.java` as the only legacy-context-to-transcript conversion entry point.
- Create `pi-java-ai/src/test/java/com/pijava/ai/api/TranscriptContextTest.java` for the new value types and `pi-java-ai/src/test/java/com/pijava/ai/api/ContextNormalizerTest.java` for pure normalization behavior and mutation coverage.
- Modify `pi-java-agent-core/src/main/java/com/pijava/agent/session/ContextEntries.java` only where projection must preserve `Message.SystemMessage`; existing compaction/path behavior remains unchanged.
- Create `pi-java-agent-core/src/test/java/com/pijava/agent/session/SessionJsonSystemMessageTest.java` for system-message serialization; keep `ContextEntriesTest.java` for projection coverage.
- Modify `pi-java-agent-core/src/main/java/com/pijava/agent/session/SessionJson.java` to serialize the system message's pi-shaped fields while retaining the existing role/content shape.
- Modify `docs/48-ai-next-work-items-design.md` only after implementation evidence exists: update A1 status/commit/evidence and append the A1 implementation record.

---

### Task 1: Add the system-message algebra and normalized context type

**Files:**
- Modify: `pi-java-ai/src/main/java/com/pijava/ai/message/Message.java`
- Create: `pi-java-ai/src/main/java/com/pijava/ai/api/ToolReference.java`
- Create: `pi-java-ai/src/main/java/com/pijava/ai/api/TranscriptContext.java`
- Test: `pi-java-ai/src/test/java/com/pijava/ai/api/TranscriptContextTest.java`

**Interfaces:**
- Produces `Message.SystemMessage` with `role() == "system"`, the existing Java `List<ContentBlock> content()` contract (legacy text represented by one `TextContent`), `timestamp()`, and optional `sections`, `toolsAdded`, and `toolsRemoved` fields represented as immutable collections.
- Produces `TranscriptContext(List<Message> messages)` with an immutable message list and a `messages()` accessor.
- `SystemMessage` must not alter existing `UserMessage`, `AssistantMessage`, or `ToolResultMessage` constructors.

- [ ] **Step 1: Write failing tests for the new variant and container**

Add tests that construct a system message and assert:

```java
var system = new Message.SystemMessage(
    "base prompt", Instant.EPOCH, Map.of(), List.of(), List.of());
assertThat(system.role()).isEqualTo("system");
assertThat(system.content()).containsExactly(new ContentBlock.TextContent("base prompt"));
assertThat(system.sections()).isEmpty();
assertThat(new TranscriptContext(List.of(system)).messages()).containsExactly(system);
```

Also assert that mutating the source list after construction cannot change `TranscriptContext.messages()` or the system message's tool lists.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
export JAVA_HOME='D:\\soft\\jdk\\graalvm-jdk-25' && \
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -Dtest=TranscriptContextTest test
```

Expected: compilation failure because `Message.SystemMessage`, `ToolReference`, and `TranscriptContext` do not yet exist.

- [ ] **Step 3: Implement the minimal immutable types**

Add `SystemMessage` as a fourth `Message` sealed-interface record. Its primary content component must remain `List<ContentBlock>` so it implements the existing `Message.content()` method; represent legacy text with one `ContentBlock.TextContent`. Add the convenience constructor `SystemMessage(String text, Instant timestamp, Map<String,String> sections, List<ToolDefinition> toolsAdded, List<ToolReference> toolsRemoved)` that creates that text block. Use `Map<String,String> sections`, `List<ToolDefinition> toolsAdded`, and `List<ToolReference> toolsRemoved`; normalize null collections to empty and defensively copy them. Create `ToolReference` at `pi-java-ai/src/main/java/com/pijava/ai/api/ToolReference.java` with exact shape `public record ToolReference(String name) {}`; it must not depend on agent-core. Add `TranscriptContext` as a record that defensively copies its message list.

Update the `Message` class documentation and sealed permits so the public contract states that system messages are now representable, while legacy `Context.systemPrompt` remains a compatibility input.

- [ ] **Step 4: Run focused tests and existing message tests**

Run the command from Step 2 plus:

```bash
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java -am -Dtest=MessageTest,ContentBlockJsonTest test
```

Expected: PASS with no compilation errors.

- [ ] **Step 5: Commit the self-contained AI type change**

```bash
git add pi-java-ai/src/main/java/com/pijava/ai/message/Message.java \
  pi-java-ai/src/main/java/com/pijava/ai/api/ToolReference.java \
  pi-java-ai/src/main/java/com/pijava/ai/api/TranscriptContext.java \
  pi-java-ai/src/test/java/com/pijava/ai/api/TranscriptContextTest.java
git commit -m "feat(ai): add transcript system message types

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Implement legacy `Context` normalization

**Files:**
- Create: `pi-java-ai/src/main/java/com/pijava/ai/api/ContextNormalizer.java`
- Test: `pi-java-ai/src/test/java/com/pijava/ai/api/ContextNormalizerTest.java`
- Reference only: `pi-java-agent-core/src/main/java/com/pijava/agent/harness/Context.java`, `pi-java-ai/src/main/java/com/pijava/ai/api/StreamRequest.java`

**Interfaces:**
- Produces `ContextNormalizer.normalize(String systemPrompt, List<Message> messages, List<ToolDefinition> tools): TranscriptContext`. This three-value Java signature is the module-boundary adaptation of pi's `normalizeContext(Context)`; the pi-java AI module cannot depend on agent-core's legacy `Context`.
- No `StreamRequest` overload is added in A1; `StreamRequest` remains an unchanged compatibility carrier and A2 decides how normalized contexts enter provider consumers.
- Normalization rules: treat null messages/tools as empty; if the first supplied message is already a `Message.SystemMessage`, return a defensive copy without injecting legacy fields; otherwise, empty/null system prompt and empty tools produce no synthetic message, while non-empty legacy fields produce one leading `SystemMessage`; all user-supplied message order is preserved.

- [ ] **Step 1: Write failing normalization tests**

Cover these exact cases:

```java
assertThat(normalize(null, List.of(user("hi")), List.of()).messages())
    .containsExactly(user("hi"));

var normalized = normalize("sys", List.of(user("hi")), List.of(tool("bash")));
assertThat(normalized.messages()).hasSize(2);
assertThat(normalized.messages().get(0)).isInstanceOf(Message.SystemMessage.class);
assertThat(((Message.SystemMessage) normalized.messages().get(0)).content()).isEqualTo("sys");
assertThat(normalized.messages().get(1)).isEqualTo(user("hi"));

var existing = new Message.SystemMessage("existing", Instant.EPOCH, Map.of(), List.of(), List.of());
assertThat(normalize("legacy", List.of(existing, user("hi")), List.of()).messages())
    .containsExactly(existing, user("hi"));
```

Include a mutation-sensitive assertion that the returned list is not the caller's mutable list and that a non-empty tool list is copied into the synthetic system message.

- [ ] **Step 2: Run the focused tests to verify RED**

Run:

```bash
export JAVA_HOME='D:\\soft\\jdk\\graalvm-jdk-25' && \
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -Dtest=ContextNormalizerTest test
```

Expected: compilation failure because `ContextNormalizer` does not yet exist.

- [ ] **Step 3: Implement the pure normalizer**

Implement null-safe copies, the leading-system-message duplicate guard, and no mutation of caller lists. Use `ToolRegistry` nowhere: the AI module must accept `ToolDefinition` and remain independent of agent-core. Do not call the normalizer from providers in this task.

- [ ] **Step 4: Run focused and AI-module regression tests**

```bash
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am -Dtest=ContextNormalizerTest,MessageTest,TransformMessagesTest test
```

Expected: PASS. For the mutation probe, temporarily remove the leading-message insertion or tool copy, run `ContextNormalizerTest`, and record the exact failing assertions; restore the implementation before committing.

- [ ] **Step 5: Commit the normalizer**

```bash
git add pi-java-ai/src/main/java/com/pijava/ai/api/ContextNormalizer.java \
  pi-java-ai/src/test/java/com/pijava/ai/api/ContextNormalizerTest.java
git commit -m "feat(ai): normalize legacy context into transcript

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Preserve system messages through entry projection and session JSON

**Files:**
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/ContextEntries.java`
- Modify: `pi-java-agent-core/src/main/java/com/pijava/agent/session/SessionJson.java`
- Test: `pi-java-agent-core/src/test/java/com/pijava/agent/session/ContextEntriesTest.java`
- Create: `pi-java-agent-core/src/test/java/com/pijava/agent/session/SessionJsonSystemMessageTest.java`

**Interfaces:**
- `ContextEntries.toMessages(...)` continues returning `List<Message>` but preserves `Entry.Message` whose payload is `Message.SystemMessage` in original order.
- `SessionJson.messageNode(Message.SystemMessage)` emits `role: "system"`, the existing pi-compatible `content` array of block nodes, `timestamp`, and non-empty future-compatible fields only when present; existing user/assistant/tool JSON remains unchanged.
- `Entry.ActiveToolsChange`, model changes, and other non-message entries remain skipped in A1.

- [ ] **Step 1: Write failing entry-projection and JSON tests**

Add a system entry between user messages and assert:

```java
var system = new Message.SystemMessage("changed", Instant.ofEpochMilli(7), Map.of(), List.of(), List.of());
var messages = ContextEntries.toMessages(List.of(
    entry("u", new Message.UserMessage(List.of(text("before")))),
    entry("s", system),
    entry("v", new Message.UserMessage(List.of(text("after"))))));
assertThat(messages).containsExactly(userBefore, system, userAfter);
```

Serialize with `SessionJson.messageNode(system)` and assert `role`, `content`, `timestamp`, and omission of empty optional collections. Add a round-trip tree test that existing user/assistant/tool nodes retain their current keys.

- [ ] **Step 2: Run focused tests to verify RED**

```bash
export JAVA_HOME='D:\\soft\\jdk\\graalvm-jdk-25' && \
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-agent-core -am \
  -Dtest=ContextEntriesTest,SessionJsonSystemMessageTest test
```

Expected: the projection test fails because the current implementation already returns message payloads, so if it passes without implementation changes, record that as a structural RED exception and rely on the JSON mutation probe; the JSON test must fail because `SessionJson.messageNode` currently omits system-specific fields.

- [ ] **Step 3: Implement only the preservation/serialization behavior**

Do not add a new entry subtype. In `ContextEntries`, leave the existing `Entry.Message` pass-through and adjust documentation/tests to include system messages. In `SessionJson.messageNode`, add an explicit `Message.SystemMessage` branch after common role/content fields, writing timestamp as epoch milliseconds and writing non-empty sections/tool additions/removals with stable pi field names. Keep null/empty omission explicit because the mapper's generic null behavior is not sufficient for pi-compatible shapes.

- [ ] **Step 4: Run focused regression and mutation probe**

```bash
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-agent-core -am \
  -Dtest=ContextEntriesTest,SessionJsonSystemMessageTest,JsonlSessionStorageTest test
```

Mutation probe: remove the system branch in `SessionJson.messageNode`, rerun the focused JSON test, and record the exact missing-field failure; restore it before commit. Confirm no existing JSONL test changes unexpectedly.

- [ ] **Step 5: Commit the projection/serialization change**

```bash
git add pi-java-agent-core/src/main/java/com/pijava/agent/session/ContextEntries.java \
  pi-java-agent-core/src/main/java/com/pijava/agent/session/SessionJson.java \
  pi-java-agent-core/src/test/java/com/pijava/agent/session/ContextEntriesTest.java \
  pi-java-agent-core/src/test/java/com/pijava/agent/session/SessionJsonSystemMessageTest.java
git commit -m "feat(agent-core): preserve transcript system messages

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Update A1 evidence and run gates

**Files:**
- Modify: `docs/48-ai-next-work-items-design.md`
- Reference: `docs/41-gap-inventory.md`, `docs/47-ai-transform-messages-rest-design.md`

**Interfaces:**
- Documentation records only behavior actually implemented and tested; it must explicitly leave provider migration, sections replay semantics, and tool-change production pending.

- [ ] **Step 1: Run focused module regression**

```bash
export JAVA_HOME='D:\\soft\\jdk\\graalvm-jdk-25' && \
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am test
```

Expected: PASS; if a failure is unrelated to A1, isolate with a serial module test and record the exact failure rather than changing unrelated behavior.

- [ ] **Step 2: Run static gates**

```bash
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am checkstyle:check
git diff --check
```

Also inspect changed Java files for `System.out.println`, unexplained `@SuppressWarnings`, and source files over 500 lines.

- [ ] **Step 3: Run the full reactor serially**

```bash
/d/soft/apache-maven-3.9.9/bin/mvn clean verify
```

Record the actual result, including any known TUI temporary JSONL or environment failure. Never mark the full-reactor gate green if it failed.

- [ ] **Step 4: Update `docs/48` with evidence**

Change only the A1 row from `⬜ 待开始` after all required gates pass. Record the commit range, focused tests, mutation probe result, module regression result, static checks, and any full-reactor limitation. Keep A2, A3, A4, Batch B, and all later rows untouched. Change the top status only if the documented review process accepts that A1 is an implementation slice rather than approval of the entire backlog.

- [ ] **Step 5: Run the two required review passes**

Review the final diff against the A1 design for:

1. **SPEC COMPLIANCE:** no provider migration, no invented persisted entry type, normalization semantics match the approved design, old serialization remains compatible.
2. **TASK QUALITY:** focused files, immutable records, no duplicate normalization logic, tests have mutation teeth, no unrelated cleanup.

Record both conclusions in the A1 implementation record in `docs/48`.

- [ ] **Step 6: Commit evidence documentation**

```bash
git add docs/48-ai-next-work-items-design.md
git commit -m "docs(ai): record Batch A1 implementation evidence

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Execution Notes

- A1 intentionally does not call `ContextNormalizer` from existing providers. That is the A2 task (`normalizeContext` and provider-consumer migration) and requires its own design/approval evidence.
- The plan intentionally does not turn `Entry.ActiveToolsChange` into a `SystemMessage`; doing so would invent the A3 transcript semantics before the tool-state design gate.
- The existing `ContextEntries` pass-through may make the projection RED test green before a code change. Treat that as a valid structural finding, keep the regression test, and make the JSON serialization mutation probe the required behavioral guard.
- If adding `sections`, `toolsAdded`, or `toolsRemoved` to `Message.SystemMessage` forces a dependency or changes the existing sealed hierarchy beyond the approved scope, stop and split those fields into the next A-task rather than adding a workaround.
