# Batch A1 Context/Transcript Foundation Design

**Status:** Approved for implementation on 2026-09-25.

**Source:** `docs/48-ai-next-work-items-design.md`, Batch A / A1.

## Goal

Establish a backward-compatible transcript data shape in `pi-java-ai` so a system instruction can exist in the ordered message stream and be projected from persisted entries without changing provider request builders yet.

## Scope

A1 includes:

- a fourth `Message` variant, `Message.SystemMessage`;
- an immutable `TranscriptContext` containing ordered `Message` values;
- a pure `ContextNormalizer.normalize(String, List<Message>, List<ToolDefinition>)` adapter from the legacy context shape;
- system-message preservation in `ContextEntries.toMessages`;
- system-message serialization in `SessionJson.messageNode`;
- focused tests, mutation probes, and regression evidence.

A1 explicitly excludes:

- calling `ContextNormalizer` from provider implementations;
- changing `StreamRequest` or provider wire signatures;
- prompt-section replay/replacement semantics;
- producing system messages from `Entry.ActiveToolsChange`;
- tool-addition/removal production or provider-specific `tool_addition`/`tool_removal` wire;
- a new persisted `Entry.SystemMessage` subtype.

## Data model

### `Message.SystemMessage`

Add this record as a permitted `Message` subtype in:

`pi-java-ai/src/main/java/com/pijava/ai/message/Message.java`

Exact components:

```java
record SystemMessage(
    List<ContentBlock> content,
    Instant timestamp,
    Map<String, String> sections,
    List<ToolDefinition> toolsAdded,
    List<ToolReference> toolsRemoved
) implements Message
```

The `content` component intentionally uses the existing Java `Message.content()` contract (`List<ContentBlock>`). Pi accepts `SystemMessage.content` as either a string or text-content array; Java represents the string form as a single `ContentBlock.TextContent`. Changing the shared `Message.content()` return type to `Object` would break every existing provider, agent-core, web, and stream consumer, so this is a compatibility adaptation rather than a new protocol claim.

The record returns `"system"` from `role()`. Its compact constructor:

- converts a null content/list to an empty list;
- converts null `sections`, `toolsAdded`, and `toolsRemoved` to empty collections;
- defensively copies every collection;
- does not mutate or normalize section/tool contents beyond copying.

Add a convenience constructor:

```java
public SystemMessage(String text, Instant timestamp,
                     Map<String, String> sections,
                     List<ToolDefinition> toolsAdded,
                     List<ToolReference> toolsRemoved) {
    this(List.of(new ContentBlock.TextContent(text == null ? "" : text)), timestamp,
         sections, toolsAdded, toolsRemoved);
}
```

The convenience constructor is for the legacy normalizer and tests; provider-facing code still sees the common `List<ContentBlock>` interface.

`ToolReference` is an AI-layer value type in:

`pi-java-ai/src/main/java/com/pijava/ai/api/ToolReference.java`

with exact shape:

```java
public record ToolReference(String name) {}
```

It has no dependency on `pi-java-agent-core`. A1 stores these fields so the transcript shape can carry later A3 deltas, but A1 does not produce non-empty values through entry projection or tool execution.

### `TranscriptContext`

Add:

`pi-java-ai/src/main/java/com/pijava/ai/api/TranscriptContext.java`

Exact shape:

```java
public record TranscriptContext(List<Message> messages) {
    public TranscriptContext {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
```

This is an ordinary immutable Java value; no runtime brand or provider migration is introduced in A1.

## Normalization

`ContextNormalizer` is a Java module-boundary adapter, not a verbatim signature copy of pi's function. Pi's `normalizeContext(context)` receives an AI-layer `Context` because its packages share the type. In pi-java, the legacy `Context` lives in `pi-java-agent-core`, which may depend on `pi-java-ai` but not vice versa. Therefore A1 exposes the three AI-layer values directly and keeps the dependency direction intact.

Exact public API:

```java
public final class ContextNormalizer {
    public static TranscriptContext normalize(
        String systemPrompt,
        List<Message> messages,
        List<ToolDefinition> tools
    );
}
```

Rules:

1. Treat null messages/tools as empty.
2. If the first supplied message is already `Message.SystemMessage`, return a defensive copy of the supplied sequence and do not inject legacy fields. This duplicate guard belongs to the agent-initialization adapter in A1's compatibility layer; it is not a promise that pi's raw `normalizeContext` merges or deduplicates an already-normalized list.
3. Otherwise create one leading `SystemMessage` only when `systemPrompt` is non-null/non-empty or tools are non-empty.
4. The synthetic message uses `Instant.EPOCH`, the supplied prompt (or `""`) represented as one `ContentBlock.TextContent`, a copied empty `sections` map, copied tool definitions in `toolsAdded`, and an empty `toolsRemoved` list.
5. Preserve every supplied message and its order.
6. Never mutate caller-owned lists or objects.

No `normalize(StreamRequest)` overload is included in A1. `StreamRequest` remains an unchanged compatibility carrier; A2 will decide how and where normalized contexts enter provider consumers.

## Entry projection

`ContextEntries.toMessages` already passes through `Entry.Message.message()` for ordinary messages. A1 retains that behavior and adds explicit tests proving that a `Message.SystemMessage` payload remains in place between other messages. No code path converts `ActiveToolsChange` into a system message.

## JSON shape

Extend `SessionJson.messageNode` for `Message.SystemMessage`:

- always write `role: "system"`, `content` as the pi-compatible array of content-block nodes, and `timestamp` as epoch milliseconds;
- omit empty `sections`, `toolsAdded`, and `toolsRemoved` fields;
- write non-empty optional fields under the pi names `sections`, `toolsAdded`, and `toolsRemoved`;
- preserve all existing user/assistant/tool result node behavior exactly.

A1 only requires serialization-node coverage. It does not add a generic deserializer for `Message` because the existing session layer uses explicit entry/message codecs and no current message deserialization path can safely infer the new subtype without a dedicated compatibility design.

## Compatibility and dependencies

- Existing `Message` constructors remain source-compatible.
- Existing `Context`, `StreamRequest`, provider APIs, and provider request builders are unchanged.
- Existing JSONL/SQLite entries remain valid; system messages are still stored as `Entry.Message` payloads.
- No Maven dependency changes.
- Java source files remain at or below 500 lines.

## Verification

Required focused tests:

- `MessageTest`/`TranscriptContextTest`: role, fields, null defaults, defensive copies;
- `ContextNormalizerTest`: no synthetic message, synthetic leading message, existing leading system message, order preservation, caller-list isolation;
- `ContextEntriesTest`: system payload preserved in order;
- `SessionJsonSystemMessageTest`: required fields, optional-field omission, optional-field emission, and unchanged legacy message nodes.

Required gates:

```bash
export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && \
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am test

/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am checkstyle:check
git diff --check

/d/soft/apache-maven-3.9.9/bin/mvn clean verify
```

The implementation record must state the exact focused result, mutation-probe red set, module regression result, static gate result, full-reactor result, and any environment limitation. It must also include separate SPEC COMPLIANCE and TASK QUALITY conclusions.
