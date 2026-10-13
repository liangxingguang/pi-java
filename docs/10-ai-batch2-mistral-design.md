# 10 ai 批 2 详稿：Mistral 两件（B80 数组 content 三支 ＋ B233 x-affinity/prompt_cache_key）

> **✅ 已闭环 2026-10-13**（3 commits：`21d187c6` 重放/B239、`edc2e54f` 数组三支、
> `05022365` 缓存门；ai 模块 1561 测全绿，其中 Mistral 夹具 71 条）。

> 基准：pi `ea448f454`（v1.1.0-22）；pi 证据一律 `git show ea448f454:<path>`。
> 主文件：`packages/ai/src/api/mistral-conversations.ts`（952 行；下文行号对应该文件，简称 **mc.ts**）。
> 判据＝`bin pi` 主流发布版行为。路线图见 [`08`](08-ai-layer-alignment-roadmap.md) §3 批 2；台账 [`04`](04-open-items-register.md)。
> 流程：**本稿审核通过后才写代码**（[[design-doc-before-code]]）。

---

## 0. 范围一览

| 条目 | 面 | 内容 | 本批动作 |
|---|---|---|---|
| **B80** | 响应面 | 数组形态 `delta.content` 的 string/thinking/text 三支（mc.ts:643-708） | 修（当前数组直接 `(String)` 强转 ⇒ ClassCastException ⇒ 整条流 error） |
| **B80** | 请求面 | assistant 重放：thinking 块 ⇒ `{type:"thinking",…}` 数组项（mc.ts:851-858） | 修（响应面产出 thinking 块后，第二轮不回送＝白做；与响应面同属一条 round-trip） |
| **B239 新登** | 请求面 | assistant 消息**整体**不发 `tool_calls`、不发 `prefix:false`（mc.ts:860-871） | **本批新登并同批闭环**（设计取证撞出，见 §2.3） |
| **B233** | 请求面 | 缓存门：body `prompt_cache_key`（mc.ts:546,399）＋头 `x-affinity`（mc.ts:365-369）＋显式覆盖压制 | 修 |
| 传输层 | http | `PiHttpClient.postSse` 逐头合并是 `.header()`（追加 ⇒ 重复头），pi 是 `Headers.set` 语义（替换） | 改 `.setHeader()`（两个调用方已审计，见 §4） |

不做什么见 §6（onPayload、null 删除传输默认头等结构性不可达面）。

---

## 1. B80-A：响应面 `delta.content` 三支

### 1.1 pi 逐字证据（mc.ts:642-708）

```ts
const delta = choice.delta;
if (delta.content !== null && delta.content !== undefined) {
    const contentItems = typeof delta.content === "string" ? [delta.content] : delta.content;
    for (const item of contentItems) {
        if (typeof item === "string") {                       // 支① 串
            const textDelta = sanitizeSurrogates(item);
            // GLM models on Mistral send empty content deltas around thinking and tool calls.
            // Opening a block for them splits thinking into multiple blocks, which Mistral rejects on replay.
            if (!textDelta) continue;
            if (!currentBlock || currentBlock.type !== "text") {
                finishCurrentBlock(currentBlock);
                currentBlock = { type: "text", text: "" };
                output.content.push(currentBlock);
                stream.push({ type: "text_start", contentIndex: blockIndex(), partial: output });
            }
            currentBlock.text += textDelta;
            stream.push({ type: "text_delta", contentIndex: blockIndex(), delta: textDelta, partial: output });
            continue;
        }
        if (item.type === "thinking") {                       // 支② thinking 数组项
            const deltaText = (item.thinking ?? [])
                .map((part) => part.text ?? "")
                .filter((text) => text.length > 0)
                .join("");
            const thinkingDelta = sanitizeSurrogates(deltaText);
            if (!thinkingDelta) continue;
            if (!currentBlock || currentBlock.type !== "thinking") {
                finishCurrentBlock(currentBlock);
                currentBlock = { type: "thinking", thinking: "" };
                output.content.push(currentBlock);
                stream.push({ type: "thinking_start", contentIndex: blockIndex(), partial: output });
            }
            currentBlock.thinking += thinkingDelta;
            stream.push({ type: "thinking_delta", contentIndex: blockIndex(), delta: thinkingDelta, partial: output });
            continue;
        }
        if (item.type === "text") {                           // 支③ 类型化 text 数组项
            const textDelta = sanitizeSurrogates(item.text ?? "");
            if (!textDelta) continue;
            if (!currentBlock || currentBlock.type !== "text") {
                finishCurrentBlock(currentBlock);
                currentBlock = { type: "text", text: "" };
                output.content.push(currentBlock);
                stream.push({ type: "text_start", contentIndex: blockIndex(), partial: output });
            }
            currentBlock.text += textDelta;
            stream.push({ type: "text_delta", contentIndex: blockIndex(), delta: textDelta, partial: output });
        }
        // 无 else：未知 type 静默忽略
    }
}
```

配套的块状态机：

- `currentBlock: TextContent | ThinkingContent | null`（mc.ts:583）；
- `finishCurrentBlock`：text ⇒ `text_end`，thinking ⇒ `thinking_end`（mc.ts:588-607）；
- **工具调用到达即关块**：`if (currentBlock) { finishCurrentBlock(currentBlock); currentBlock = null; }`（mc.ts:712-715，在每个 toolCall 循环体内，首个调用关块、其后空转）；
- 流末统一收尾 `finishCurrentBlock(currentBlock)`（mc.ts:759）。

### 1.2 Java 现状（`MistralConversationsApi.java`）

- `processSseData` :244 `var content = (String) delta.get("content");` —— **只认串**。数组形态抛
  `ClassCastException`（不被 `catch (JsonProcessingException)` 接住）⇒ 冒到 `streamInternal`
  的 catch ⇒ 整流一条 error。Mistral 自 2025 末起 reasoning 模型逐块流即此形状（GLM 同）。
- 块状态只有 `boolean[] textStarted`（:126）：单一文本槽，流末发一次 `emitTextEnd`（:140）；
  工具调用到达**不关**文本块（事件序与 pi 不同：pi 是 `text_end → toolcall_start`）。
- `StreamPartialBuilder` 的 thinking 三件套**已具备**（`emitThinkingStart/Delta/End`，
  `StreamPartialBuilder.java:241-289,370-373`；Anthropic 车道在用），每次 `*Start()`
  重置对应缓冲、块下标取 `blocks.size()` ⇒ 文本/思考交替天然支持，车道只需在换型时先补发上一块的 `*_end`。

### 1.3 改动设计

**(a) 车道内新增块状态机**（替换 `boolean[] textStarted`，`streamInternal` 每流 new 一个传入
`processSseData`；流末 `state.finish(...)` 取代 :140 的单行收尾）：

```java
/** pi mc.ts:583 的 currentBlock：NONE/TEXT/THINKING 三态；换型先关旧块再开新块。 */
private static final class ContentState {
    private enum Open { NONE, TEXT, THINKING }
    private Open current = Open.NONE;

    /** 切到目标块型：同型空转；异型先 finish 旧块（发 *_end），再发目标 *_start。 */
    void switchTo(Open target, StreamPartialBuilder b, SubmissionPublisher<StreamEvent> p) {
        if (current == target) return;
        finish(b, p);
        current = target;
        switch (target) {
            case TEXT -> p.submit(b.emitTextStart());
            case THINKING -> p.submit(b.emitThinkingStart());
            case NONE -> { }
        }
    }

    /** pi mc.ts:588-607 的 finishCurrentBlock；关完归 NONE。 */
    void finish(StreamPartialBuilder b, SubmissionPublisher<StreamEvent> p) {
        switch (current) {
            case TEXT -> p.submit(b.emitTextEnd());
            case THINKING -> p.submit(b.emitThinkingEnd());
            case NONE -> { }
        }
        current = Open.NONE;
    }
}
```

**(b) 新增纯函数 `MistralStreamDelta.java`**（package-private，文件行数纪律：车道本体
预计 +70 行逼近 450，把 JSON 树判定抽走）：

```java
/** 数组形态 delta.content 单项（pi mc.ts:88-92 MistralStreamContentChunk）的解析结果。 */
sealed interface MistralContentDelta {
    /** 串支①与类型化 text 支③合一：已是净化后文本；空串＝pi 的 GLM 空 delta 跳过支。 */
    record Text(String text) implements MistralContentDelta {}
    /** 支②：parts 已按 pi :668-671 map→filter(非空)→join("") 拼好并净化。 */
    record Thinking(String text) implements MistralContentDelta {}
    /** 未知 type / 非串非对象元素：pi 无 else 支 ⇒ 静默忽略。 */
    record Ignored() implements MistralContentDelta {}

    /** pi mc.ts:644 的 [string] 归一 + :646-706 三支。 */
    static MistralContentDelta ofItem(Object item) { /* String→Text；Map→按 type 分派 */ }

    /** pi mc.ts:668-671：(item.thinking ?? []).map(p -> p.text ?? "").filter(非空).join("")。 */
    private static String joinThinking(List<?> parts) { /* 只认 String 值 */ }
}
```

净化在**调用点**做（与 pi 同位置；`SanitizeUnicode.surrogates` 既有），即
`ofItem` 只取原文、车道 `var d = SanitizeUnicode.surrogates(delta.text()); if (d.isEmpty()) continue;`。

**(c) `processSseData` 内容段重写**（替换 :243-253）：

```java
Object contentNode = delta.get("content");
if (contentNode != null) {
    List<?> items = contentNode instanceof String s ? List.of(s)
                 : contentNode instanceof List<?> l ? l : List.of();
    for (Object item : items) {
        switch (MistralStreamDelta.ofItem(item)) {
            case MistralContentDelta.Text(var raw) when !raw.isEmpty() -> {
                var d = SanitizeUnicode.surrogates(raw);
                if (d.isEmpty()) break;                              // GLM 空 delta（mc.ts:650）
                state.switchTo(ContentState.Open.TEXT, builder, publisher);
                publisher.submit(builder.emitTextDelta(d));
            }
            case MistralContentDelta.Thinking(var raw) when !raw.isEmpty() -> {
                var d = SanitizeUnicode.surrogates(raw);
                if (d.isEmpty()) break;
                state.switchTo(ContentState.Open.THINKING, builder, publisher);
                publisher.submit(builder.emitThinkingDelta(d));
            }
            default -> { }                                          // Ignored / 空串守卫
        }
    }
}
```

> 注：守卫落在净化**之后**（pi 先 sanitize 再判空）；空 raw 先被 record 模式 `when` 挡住也等价，
> 实际写法以两处不重复为准（实现时二选一，夹具钉同一结果）。

**(d) 工具调用到达关块**（:256-257 之间，pi mc.ts:712-715）：

```java
var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
if (toolCalls != null && !toolCalls.isEmpty()) {
    state.finish(builder, publisher);   // 首个工具调用到达即关当前文本/思考块；其后空转
    for (var tc : toolCalls) { /* 既有逻辑不动 */ }
}
```

**(e) 流末收尾**（:140）：`state.finish(builder, publisher);` 取代
`if (textStarted[0]) publisher.submit(builder.emitTextEnd());`。
ToolCallEnd 的补发逻辑（:294-301，刻意偏差：在 TOOL_USE 取值处而非流末）**不动**。

事件序后果（与 pi 对齐）：`… text_end → toolcall_start … toolcall_end`；thinking→text 同帧
（GLM 夹具：一个事件里 `[thinking(" second part."), {text:"Reading."}]`）⇒
`thinking_delta → thinking_end → text_start → text_delta`。

### 1.4 终态消息断言（pi 黄金夹具，`test/mistral-http-transport.test.ts:238-323`）

输入事件：thinking 块 → text 块 → 分片 tool_calls（首片带 id/name，末片带 finish_reason）
→ usage `{prompt_tokens:10, completion_tokens:4, total_tokens:14, prompt_tokens_details:{cached_tokens:3}}`。

```ts
expect(message.content).toEqual([
  { type: "thinking", thinking: "reason" },
  { type: "text", text: "answer" },
  { type: "toolCall", id: "abc123456", name: "lookup", arguments: { query: "pi" } },
]);
expect(message.usage).toMatchObject({ input: 7, output: 4, cacheRead: 3, cacheWrite: 0, totalTokens: 14 });
```

GLM 空 delta 夹具（同文件 :325-365，模型 `zai-glm-5-3`，9 个 delta：空串/空 text 项/
thinking 分片/同帧 thinking+text/带 tool_calls 的空串）终态恰为 **3 块**：

```ts
[ { type: "thinking", thinking: "first part, second part." },
  { type: "text", text: "Reading." },
  { type: "toolCall", id: "abc123456", name: "read", arguments: { path: "a.txt" } } ]
```

---

## 2. B80-B ＋ B239：请求面 assistant 重放

### 2.1 pi 逐字证据（mc.ts:840-872）

```ts
if (msg.role === "assistant") {
    const contentParts: MistralContentChunk[] = [];
    const toolCalls: MistralRequestToolCall[] = [];
    for (const block of msg.content) {
        if (block.type === "text") {
            if (block.text.trim().length > 0) {
                contentParts.push({ type: "text", text: sanitizeSurrogates(block.text) });
            }
            continue;
        }
        if (block.type === "thinking") {
            if (block.thinking.trim().length > 0) {
                contentParts.push({
                    type: "thinking",
                    thinking: [{ type: "text", text: sanitizeSurrogates(block.thinking) }],
                });
            }
            continue;
        }
        toolCalls.push({
            id: block.id,
            type: "function",
            function: { name: block.name, arguments: JSON.stringify(block.arguments || {}) },
            index: 0,
        });
    }
    const assistantMessage: MistralChatMessage = { role: "assistant", prefix: false };
    if (contentParts.length > 0) assistantMessage.content = contentParts;
    if (toolCalls.length > 0) assistantMessage.toolCalls = toolCalls;
    if (contentParts.length > 0 || toolCalls.length > 0) result.push(assistantMessage);
    continue;
}
```

线格改名（`toMistralWireMessage` mc.ts:422-430）：`toolCalls → tool_calls`。
`toMistralWirePayload` :399：`promptCacheKey → prompt_cache_key`（§3 用）。

注意四点：

1. assistant content **恒为数组**（哪怕只有文本），且每条都带 **`prefix: false`**；
2. text/thinking 都用 **trim 判空**（非空才落），逐块净化；
3. 每个 toolCall 的 `index` **恒 0**（pi 怪癖，照抄）；arguments 是 **JSON 串**（`JSON.stringify` ⇒ 紧凑无空格）；
4. thinking 块能走到这里，靠共享预通道 `TransformMessages` 的同模型闸（异模型已降成 TextContent；
   redacted 空文本被 trim 门丢弃）——java 侧闸已在（`TransformMessages.java:299-314`），直接消费。

### 2.2 Java 现状（`MistralMessages.java:66-70`）

```java
case Message.AssistantMessage a -> {
    m.put("role", "assistant");
    m.put("content", extractSanitizedText(a.content()));   // 串形态；只收 TextContent
}
```

- thinking 块被滤掉（B80 请求半）；
- **ToolUseContent 整块消失**：历史 assistant 工具调用不发 `tool_calls`，下一轮的
  `role:"tool"` 消息成了孤儿——Mistral API 必拒（多轮 agent 实质不可用）。这是本批取证
  新撞出的账外缺口（§2.3）；
- `prefix` 不发；
- 现有回归夹具 `MistralImageContentTest.assistantMessageStaysAString`（:215-223）**钉死的就是
  这个偏差**（注释自述「pi 的助手分支是块数组 —— 既有偏差，本包不动」）⇒ 本批**翻掉该夹具**
  （改断言 pi 形状，不是悄悄改红）。

### 2.3 B239 台账号（本批新登并闭环）

> **B239 Mistral assistant 重放缺 `tool_calls`/`prefix`**：`MistralMessages` 的 assistant
> 分支只发文本串，ToolUseContent 不落线、`prefix:false` 缺席（pi mc.ts:860-871）。
> 批 2 设计取证撞出，随 B80 请求半同一分支重写一并闭环。地图单元 #6 同址。

### 2.4 改动设计

**(a) `MistralMessages.java` assistant 分支重写**：

```java
case Message.AssistantMessage a -> {
    // pi mc.ts:840-872：parts(text/thinking) + tool_calls，prefix:false；皆空则整条不落（:871）。
    var assistant = assistantMessage(a);
    if (assistant != null) {
        messages.add(assistant);
    }
    return null; // 已自行落线
}
```

```java
/** pi mc.ts:840-872。@return null＝pi :871 的「parts/toolCalls 皆空 ⇒ 不落线」。 */
private static Map<String, Object> assistantMessage(Message.AssistantMessage a)
        throws JsonProcessingException {
    var parts = new ArrayList<Map<String, Object>>();
    var toolCalls = new ArrayList<Map<String, Object>>();
    for (var block : a.content()) {
        if (block instanceof ContentBlock.TextContent tc
                && !tc.text().trim().isEmpty()) {                    // pi :846 trim 门
            parts.add(MistralContent.textChunk(SanitizeUnicode.surrogates(tc.text())));
        } else if (block instanceof ContentBlock.ThinkingContent th
                && th.text() != null && !th.text().trim().isEmpty()) { // pi :852 trim 门
            parts.add(MistralContent.thinkingChunk(SanitizeUnicode.surrogates(th.text())));
        } else if (block instanceof ContentBlock.ToolUseContent tu) {
            toolCalls.add(toolCallWire(tu));                          // pi :860-865
        }
    }
    if (parts.isEmpty() && toolCalls.isEmpty()) {
        return null;
    }
    var m = new LinkedHashMap<String, Object>();
    m.put("role", "assistant");
    m.put("prefix", false);                                           // pi :868 恒带
    if (!parts.isEmpty()) {
        m.put("content", parts);                                      // 恒数组（pi 同）
    }
    if (!toolCalls.isEmpty()) {
        m.put("tool_calls", toolCalls);                               // 线格名（pi :424）
    }
    return m;
}

/** pi mc.ts:860-865：id/type/function{name,arguments 紧凑 JSON 串}/index 恒 0。 */
private static Map<String, Object> toolCallWire(ContentBlock.ToolUseContent tu)
        throws JsonProcessingException {
    var fn = new LinkedHashMap<String, Object>();
    fn.put("name", tu.name());
    fn.put("arguments", MAPPER.writeValueAsString(tu.arguments()));   // ≠null（紧凑构造器 copyOf）
    var call = new LinkedHashMap<String, Object>();
    call.put("id", tu.id());                                          // 已过 MistralToolCallIds 9 字符归一
    call.put("type", "function");
    call.put("function", fn);
    call.put("index", 0);
    return call;
}
```

- `MistralMessages` 新增 `private static final ObjectMapper MAPPER = new ObjectMapper();`
  （Jackson 默认紧凑输出，等价 `JSON.stringify`；非 ASCII 不转义，同为 UTF-8 原文）。
- 删除 `extractSanitizedText`（:194-199，随旧分支一起退役；注意它与 tool result 侧
  `:160-163` 无关，不删 tool 侧逻辑）。
- ToolUseContent 的 `thoughtSignature` 不落（pi 也只读 id/name/arguments）。

**(b) `MistralContent.java` 加一个 chunk helper**：

```java
/** pi mc.ts:853-856：{type:"thinking", thinking:[{type:"text", text}]}。 */
static Map<String, Object> thinkingChunk(String text) {
    var chunk = new LinkedHashMap<String, Object>();
    chunk.put("type", "thinking");
    chunk.put("thinking", List.of(
        java.util.Map.of("type", "text", "text", text)));
    return chunk;
}
```

### 2.5 黄金夹具（pi `mistral-http-transport.test.ts:161-236`，请求体逐字）

```json
{ "role": "assistant", "prefix": false,
  "content": [
    { "type": "thinking", "thinking": [{ "type": "text", "text": "reason" }] },
    { "type": "text", "text": "answer" }],
  "tool_calls": [
    { "id": "abc123456", "type": "function",
      "function": { "name": "lookup", "arguments": "{\"query\":\"pi\"}" },
      "index": 0 } ] }
```

同用例的 tool 消息：`{role:"tool", tool_call_id:"abc123456", name:"lookup",
content:[{text:"found"},{image_url:"data:image/png;base64,aGVsbG8="}]}`
（java 现状已对齐，作回归钉子保留。）

---

## 3. B233：缓存门 `prompt_cache_key` ＋ `x-affinity`

### 3.1 pi 逐字证据

**门**（mc.ts:551-553）——`cacheRetention` 缺省不是 `"none"`，故只有显式 none 才关：

```ts
function shouldUsePromptCaching(options?: MistralOptions): options is MistralOptions & { sessionId: string } {
    return options?.cacheRetention !== "none" && !!options?.sessionId;
}
```

**body**（mc.ts:546，wire 改名 :399 `promptCacheKey → prompt_cache_key`）：

```ts
if (shouldUsePromptCaching(options)) payload.promptCacheKey = options.sessionId;
```

> ⚠️ **不截断**：Mistral 直发原始 sessionId；`PromptCacheKeys.clamp`（64 码点）是
> OpenAI 系 `clampOpenAIPromptCacheKey` 的事，**不要**复用到本车道。

**头**（mc.ts:355-372）：

```ts
function buildMistralHeaders(model, apiKey, options?): Headers {
    const headers = new Headers({
        "User-Agent": getPiUserAgent(),
        accept: "text/event-stream",
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
    });
    applyMistralHeaderOverrides(headers, model.headers);
    applyMistralHeaderOverrides(headers, options?.headers);
    const hasExplicitAffinity =
        hasMistralHeaderOverride(model.headers, "x-affinity") ||
        hasMistralHeaderOverride(options?.headers, "x-affinity");
    if (shouldUsePromptCaching(options) && !hasExplicitAffinity) {
        headers.set("x-affinity", options.sessionId);
    }
    return headers;
}
// :374-384
function applyMistralHeaderOverrides(headers, overrides?) {
    if (!overrides) return;
    for (const [name, value] of Object.entries(overrides)) {
        if (value === null) headers.delete(name); else headers.set(name, value);
    }
}
function hasMistralHeaderOverride(overrides, target) {
    return !!overrides && Object.keys(overrides).some((name) => name.toLowerCase() === target);
}
```

语义要点：

1. 头覆盖**两层**：model.headers（目录/models.json）先、options.headers（per-request）后；
   `Headers.set/delete` 都是**大小写不敏感的替换/删除**；
2. 显式亲和判定看两层原始 map 的**键存在性**（值不管，哪怕是空串或 null——
   `{x-affinity:null}` 既算「显式」（压制自动值）又把 model 层的值删掉 ⇒ 头缺席，
   见 pi 夹具 :421-445）；
3. **无 compat 开关**：不像 completions 要看 `sendSessionAffinityHeaders`，门只有
   retention≠none ＋ sessionId 真值。

夹具（pi）：默认 retention ⇒ 头＋键都在（`mistral-http-transport.test.ts:113,118,129`）；
`cacheRetention:"none"` ⇒ 键缺席（`mistral-reasoning-mode.test.ts:145-160` 两条）；
覆盖压制（:421-445：model `X-Affinity` 被 options `x-affinity:null` 删净、
`authorization:null` 删除、`User-Agent` 替换）。

### 3.2 Java 现状与既有通道

- 宿主**已统一注入**：`DefaultProviders.requestExtra(options, sessionId)` 把
  `cacheRetention`（wireName 串）与 `sessionId` 放进 `ApiOptions.extra`
  （`DefaultProviders.java:207-216`），适配器每请求新建 ⇒ **Mistral 零宿主改动，读就行**。
- 读法照抄 completions 车道：`OpenAICompletionsApi.sessionIdOf`（:126-130）/
  `retentionOf`（:156-162）；门合并用公共口
  `CompatResolver.resolveCacheRetention(requested, System.getenv("PI_CACHE_RETENTION"))`
  （:349-353）。
- model 头：`request.model().headers()`（`ModelInfo` 既有字段，models.json 合并链已供值）。
  **Mistral raw 车道此前压根没发 model/options 头**（:115-117 只写 Authorization/Accept）
  ⇒ 本批顺带把 D-P1 的默认头通道接到本车道（pi 在同一函数里，属 B233 移植面）。
- per-request 头：`extra["headers"]`（`AbstractChatApi.putExtraHeaders` :292-303 既有读法，
  但其跳 null、无删除语义；本车道要保留 null 删除 ⇒ 直接读原始 `Map<?,?>`，不复用该 helper）。

### 3.3 改动设计

**(a) 新文件 `MistralHeaders.java`**（package-pivable 纯函数，约 90 行）：

```java
/**
 * pi mc.ts:355-384 buildMistralHeaders 的组装。基底头（Authorization/Accept；
 * content-type/User-Agent 由 PiHttpClient 传输默认给）→ model.headers 覆盖 →
 * options.headers 覆盖（null=大小写不敏感删除）→ 缓存门自动补 x-affinity。
 */
final class MistralHeaders {
    static LinkedHashMap<String, String> build(
            String apiKey,
            Map<String, String> modelHeaders,
            Map<?, ?> optionHeaders,
            String sessionId,
            boolean useCaching) {
        var h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + apiKey);   // pi :359
        h.put("Accept", "text/event-stream");         // pi :358
        applyModelOverrides(h, modelHeaders);         // pi :362
        applyOptionOverrides(h, optionHeaders);       // pi :363（null 删除）
        // pi :365-366：键存在（哪怕值 null）即「显式」⇒ 自动值不补
        boolean explicit = hasKeyCi(modelHeaders, "x-affinity")
                || hasRawKeyCi(optionHeaders, "x-affinity");
        if (useCaching && !explicit) {
            h.put("x-affinity", sessionId);           // pi :368
        }
        return h;
    }

    /** pi Headers.set：大小写不敏感替换；put 前先删异写键。 */
    private static void putCi(Map<String,String> h, String name, String value) { ... }
    /** pi Headers.delete：大小写不敏感删除。 */
    private static void removeCi(Map<String,String> h, String name) { ... }
    private static boolean hasKeyCi(Map<String,String> src, String key) { ... }
    private static boolean hasRawKeyCi(Map<?,?> src, String key) { ... } // null/非串键防御
}
```

`applyModelOverrides`：值类型 String（`Map.copyOf` 保证无 null），逐对 `putCi`。
`applyOptionOverrides`：raw map；值为 `null` ⇒ `removeCi`，否则
`putCi(name, value.toString())`。

**(b) `MistralConversationsApi` 构造器加两个字段**（同 completions 形状）：

```java
/** B233：原始 sessionId（mc.ts:368 头 与 :546 体共用）。 */
private final String sessionId;          // sessionIdOf(options)，blank ⇒ null
/** A-02 同形：extra["cacheRetention"]，缺席 Optional.empty（别塞默认值，会屏蔽环境变量）。 */
private final Optional<CacheRetention> cacheRetention;
private static final String CACHE_RETENTION_ENV = "PI_CACHE_RETENTION";
```

每请求门（与 completions :192-193 同时点）：

```java
/** pi mc.ts:551-553：retention 三源合并后 ≠ NONE 且 sessionId 真值。 */
private boolean useCaching() {
    return CompatResolver.resolveCacheRetention(cacheRetention,
               System.getenv(CACHE_RETENTION_ENV)) != CacheRetention.NONE
            && sessionId != null && !sessionId.isBlank();
}
```

**(c) `streamInternal` 头组装替换**（:115-117）：

```java
Object rawHeaderOpt = options == null ? null : options.extra() == null
        ? null : options.extra().get("headers");
Map<?,?> optionHeaders = rawHeaderOpt instanceof Map<?,?> m ? m : null;
var headers = MistralHeaders.build(apiKey,
        request.model() == null ? Map.of() : request.model().headers(),
        optionHeaders, sessionId, useCaching());
```

> 实现取巧：把构造器入参 `ApiOptions options` 存成字段（车道内多处要用），或只存
> 需要的两件（sessionId/retention/raw headers）。以后者为准，不持整个 options。

**(d) `buildRequestBody` 加体键**（在 reasoning 段之前或之后皆可，pi 写在 :546 末尾；
Jackson HashMap 键序无线格语义）：

```java
if (useCaching()) {
    body.put("prompt_cache_key", sessionId);   // pi mc.ts:546 + :399；原样不 clamp
}
```

`MistralSurrogateSanitizeTest` 反射调 `buildRequestBody(StreamRequest)` 的接缝**保持不变**
（无 extra ⇒ sessionId null ⇒ 无键）。

### 3.4 x-affinity 与三车道亲和头不是一套

`SessionAffinityHeaders`（B103）是 openrouter/openai 形（x-session-id / session_id /
x-session-affinity），**Mistral 不用它**：pi 的 Mistral 头是字面 `x-affinity`、无 compat
format 探测。不进 `SessionAffinityHeaders`，避免误挂 openrouter 形。

---

## 4. 传输层：`PiHttpClient.postSse` 合并语义改替换

`PiHttpClient.java:108-110`：

```java
if (headers != null) {
    headers.forEach(requestBuilder::header);   // .header() = 追加 ⇒ 与默认头重名时发两份
}
```

默认头 `Content-Type/User-Agent/Accept`（:98-100）先写；车道传入的同名头走追加 ⇒
当前 Mistral 每个请求其实带着**两个 Accept**。pi 的 `Headers.set` 是大小写不敏感替换。

改为：

```java
if (headers != null) {
    headers.forEach((k, v) -> requestBuilder.setHeader(k, v));
}
```

调用方审计（全仓仅两处）：

- `MistralConversationsApi.java:122` —— 本批改产出的就是「最终头表」，替换正解；
- `PiMessagesApi.java:88` —— 同样传 per-request 最终头表，重复头从无消费者，改后只更对。

§3.3 夹具里 `User-Agent` 替换能生效全靠这一改（client 默认先写、车道值后替换）。

---

## 5. 测试计划（全 wire 级，真 HTTP/SSE 路径）

| # | 新/改夹具 | 钉的 pi 原夹具 | 断言要点 |
|---|---|---|---|
| T1 | **新** `MistralStreamContentWireTest`：thinking+text+分片 toolcall+缓存 usage | http-transport :238-323 | 终态消息恰 3 块（thinking「reason」/text「answer」/toolCall `abc123456`，args `{query:"pi"}`）；usage `{input:7,output:4,cacheRead:3,cacheWrite:0,totalTokens:14}`；事件序含 `thinking_start/delta/end → text_*` |
| T2 | 同类：GLM 九 delta | :325-365 | 终态恰 3 块（一块 thinking「first part, second part.」、一块 text「Reading.」、一个 toolCall）；空串/空 text 项不开块；同帧 thinking+text 发 `thinking_end→text_start`；空串带 tool_calls 不关空块 |
| T3 | 同类：串形态回归＋未知 type | :367-400 等 | `{content:"hello"}` 仍走文本块；`{content:[{type:"image_url",…}]}` 等未知项静默忽略、不出块 |
| T4 | **新** `MistralReplayWireTest`（或反射 buildRequestBody 风格沿用 `MistralSurrogateSanitizeTest` 接缝） | :161-236 | 请求体逐字：assistant `prefix:false` 恒带、content 数组（thinking 项 +text 项）、`tool_calls`（紧凑 args、`index:0`）；tool 消息形状回归；trim 空文本/空 thinking 不落；皆空整条不落 |
| T5 | **改** `MistralImageContentTest.assistantMessageStaysAString` | — | 翻成数组形状断言（`prefix:false` + `[{type:text,...}]`），测试改名并更新「既有偏差」注释 |
| T6 | **新** `MistralPromptCacheWireTest`（`RecordingHttpServer` 录头/体，仿 `SessionAffinityWireTest:58-80`） | reasoning-mode :145-160；http-transport :113-129 | ①extra 只有 sessionId（retention 缺省）⇒ 头 `x-affinity:<sid>` ＋体 `prompt_cache_key:<sid>` 原值（长键**不截断**夹具：>64 码点原样）；②`cacheRetention:"none"` ⇒ 两头皆无；③无 sessionId ⇒ 无 |
| T7 | 同类：覆盖与压制 | http-transport :421-445 | model.headers 带 `X-Affinity` ⇒ 发 model 的值、不自动补；options `x-affinity:null` 叠加 ⇒ 头缺席；`authorization:null` ⇒ 无 Authorization 头；`User-Agent` 覆盖生效（依赖 §4）；异写键大小写不敏感 |
| T8 | 既有回归 | — | 全套 mistral 既有夹具（终局四段判定、头超时、usage、图片、净化、provider retry、reasoning effort）保持绿 |

观测面：T1-T3/T6/T7 走 `RecordingHttpServer`/既有 SSE harness 录事件＋头＋体；
T4 可走反射接缝（`buildRequestBody` 私有方法，先例 `MistralSurrogateSanitizeTest:59-64`），
不触网。

---

## 6. 明确不做（结构性不可达 / 别批条目）

1. **onPayload 回调面**（mc.ts:148-151 的 `promptCacheKey` camelCase 观察）：java 无 onPayload
   注入点（SimpleOptions javadoc 已列「不移植」清单），只保线格 `prompt_cache_key`。
2. **options.headers 删除传输默认头**（`content-type`/`accept`/`user-agent: null`）：默认头在
   `PiHttpClient` 内先写，车道头表没有「删除传输默认」的通道；能达的是
   同表键删除（Authorization、model/options 头）与任意键**替换**（§4 后含 UA）。
   夹具不涉及默认头删除；按既有「结构性不可达」注释口径在代码注释登记，不造新通道。
3. **头覆盖的通用化收口**（D-P1 当初只接 SDK 车道）：本批只把 Mistral 所需的两层覆盖接到
   `MistralHeaders`；`putExtraHeaders` helper 不改、其余 raw 车道（pi-messages）不随动。
4. B103 的 `SessionAffinityHeaders`/compat `sendSessionAffinityHeaders`/`sessionAffinityFormat`
   与本车道无关（§3.4）。
5. Mistral 车道 `onProviderStreamEvent`（mc.ts:611）仍不移植（批 0 #100 已 R5）。
6. 请求串形 user content、system 折叠等既有形状不在本批翻动。

## 7. 文件清单与行数预算

| 文件 | 动作 | 预估 |
|---|---|---|
| `protocol/MistralConversationsApi.java`（361） | 字段＋门、头组装委托、状态机、三支解析 | 361 → ≈440（≤500） |
| `protocol/MistralMessages.java`（200） | assistant 分支重写、toolCallWire、MAPPER | 200 → ≈270 |
| `protocol/MistralContent.java`（41） | +thinkingChunk | ≈55 |
| `protocol/MistralHeaders.java` | 新建 | ≈90 |
| `protocol/MistralStreamDelta.java` | 新建（sealed ADT ＋树解析） | ≈80 |
| `http/PiHttpClient.java` | `.header()`→`.setHeader()` 一行＋javadoc | +6 |
| 测试 3 新类＋1 改类 | T1-T7 | ≈450 |

## 8. 实施与闭环

1. 顺序（每步独立可编译、独立 commit、独立 `mvn -pl pi-java-ai -am test`）：
   - `fix(ai): replay mistral assistant tool_calls and thinking blocks (B239/B80)` —— §2＋T4/T5；
   - `feat(ai): parse mistral array content with thinking/text branches (B80)` —— §1＋T1-T3；
   - `feat(ai): mistral prompt_cache_key and x-affinity session headers (B233)` —— §3＋§4＋T6/T7。
2. 全量验证：批 2 仅触 ai 模块（postSse 两个调用方均在 ai 内）⇒
   `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test`；不跑 34min 全量 verify。
3. 闭环回填：`docs/04`（B80/B233 销号、**B239 新登即销**）、`docs/06`、`docs/08` 批 2 行打勾、
   `docs/map/02-ai.md`（单元 #6 等判定/加权；x-affinity 窄角注释转已修）、`docs/05` 总表；记忆追加。
4. Commit trailer `Co-Authored-By: Claude Code <noreply@anthropic.com>`；显式 `git add <path>`；
   无 `System.out.println`、checkstyle 零警告（无 `@SuppressWarnings`，既有两处 SSE 泛型警告
   有注释豁免，不新增）。

---

## 附：取证命令存档

```bash
# pi 源码（锚点只读，不读工作树）
git -C D:/workplaceForai/pi show ea448f454:packages/ai/src/api/mistral-conversations.ts
git -C D:/workplaceForai/pi show ea448f454:packages/ai/test/mistral-http-transport.test.ts
git -C D:/workplaceForai/pi show ea448f454:packages/ai/test/mistral-reasoning-mode.test.ts
```
