# 包 14：pi 原生消息/块拼写对齐设计（真互读收口）

> **状态：设计稿已落地，待用户审核 —— 尚未写代码。**
> 由**真实 pi 端到端互读验证**逼出（包 12/13 的夹具只仿写了 pi 的解析语义，一直缺这一步）。
> 真实 pi 会话原文已存为测试资源：
> `pi-java-agent-core/src/test/resources/interop/real-pi-session.jsonl`。

## 1. 范围与裁决建议

**做（读侧）**：解码接受 pi 拼写（含历史本仓拼写作 legacy 别名）：

1. 系统/用户消息 content **裸串**；
2. 工具结果块 `{"type":"toolCall",…}`；
3. 工具结果消息 role `"toolResult"` ＋ 字段 `"toolCallId"`。

**做（写侧）**：会话编码改为 **pi 原生拼写**（`toolResult`/`toolCallId`/`toolCall`），
经一个共享转换点，JSONL 与 SQLite 同时生效。**不改** `Message.role()`、不改 record 分量名 ——
`Message.java:313` 的 javadoc 早已钉死：改它会连带改破既有会话文件。

**不做**：内部模型改名；provider 适配层（各 API 转换器有自己的块形状，本包不碰）。

## 2. 验证方法与证据（2026-10-05 实跑）

### 2.1 真实一轮怎么跑的

- pi clone @ **`200387122`**（工作树干净）；构建出 dist（`codemode` 缺可选原生依赖、
  `ai` 有 5 处 SDK 类型错，均不影响 OpenAI 兼容路径，JS 正常产出）；
- 补了 `node_modules/@earendil-works/{pi-codemode,pi-mcp}` 两个缺失的 workspace 链接；
- 本地 OpenAI 协议 HTTP 桩（`node:http`，端口 18791）：首轮回 read 工具调用、
  次轮（见到工具结果）回最终文本；
- 真 pi CLI：`--session-dir … --model stub/stub-1 --api-key stub-key "read the note file"`
  ⇒ 输出 `final answer after tool result`，留下 8 行会话原文。

### 2.2 装载真文件：逐级放行探针，恰三缺口

对原文件做三级手改（后一级含前一级的放行），实测：

| 探针 | 放行内容 | 装载结果 |
|---|---|---|
| 原文 | — | **line4: `has invalid content`**（系统消息 content 裸串 `""`） |
| A | 系统 content `""`→`[]` | **line6: `has unknown content block type`**（块 `"toolCall"`） |
| B | ＋ 块 `"toolCall"`→`"tool_use"` | **line7: `has unknown message role`**（role `"toolResult"`） |
| C | ＋ role `"toolResult"`→`"tool"`、`toolCallId`→`toolUseId` | ✅ **LOADS** |

**没有第四处**。三缺口的共同形状＝**本仓线格式方言与 pi 主流拼写不同**，不是缺条目型。

### 2.3 反向：pi 读本仓文件会静默丢消息

pi `convertToLlm`（`coding-agent/src/core/messages.ts:144-198`）：`system/user/assistant/toolResult`
之外的角色走 `default` ⇒ 返回 `undefined` ⇒ `.filter` 掉：

```ts
case "system":
case "user":
case "assistant":
case "toolResult":
    return m;
default:
    const _exhaustiveCheck: never = m;
    return undefined;
```

⇒ pi 读本仓现有文件：**工具结果消息整条无声消失**（角色两侧 system/user/assistant 同名不受影响；
助手 content 里若有本仓 `"tool_use"` 块同样不被识别）。这是比「报错」更坏的故障形状。

## 3. pi 侧逐字类型（`packages/ai/src/types.ts`）

```ts
// :419
export interface ToolCall {
	type: "toolCall";
	id: string;
	name: string;
	arguments: JsonObject;
	thoughtSignature?: string;
	namespace?: string;
}

// :524
export interface SystemMessage {
	role: "system";
	content: string | TextContent[];        // 裸串合法
	sections?: Record<string, string | null>;
	toolsAdded?: Tool[];
	toolsRemoved?: ToolReference[];
	timestamp: number;
}

// :541（UserMessage）
export interface UserMessage {
	role: "user";
	content: string | (TextContent | ImageContent)[];  // 裸串合法
	timestamp: number;
}

// :596
export type ToolResultMessage<TDetails = JsonValue> = … ? {
	role: "toolResult";
	toolCallId: string;
	toolName: string;
	content: (TextContent | ImageContent)[];
	details?: …;
	usage?: Usage;
	nestedCalls?: NestedToolCalls;
	isError: boolean;
	timestamp: number;
} : never;
```

AssistantMessage（`:548`）content 恒为块数组、无裸串。

## 4. Java 设计

### 4.1 读：`MessageJsonCodec` 加别名（单解码点）

SQLite 也经 `JsonlCodec.decodeEntryPayload`（`JsonlCodec.java:370-379`）⇒
改这一处，JSONL/SQLite 读侧同时覆盖。

角色（`:43` switch）：

```java
return switch (role) {
    case "user" -> …
    case "assistant" -> …
    // pi 主流 "toolResult"；2026-10-05 之前本仓写的是 "tool"（legacy，保留可读）。
    case "toolResult", "tool" -> new Message.ToolResultMessage(
        JsonlCodec.firstText(node, "toolCallId", "toolUseId"),
        JsonlCodec.requireString(node, "toolName"),
        content,
        JsonlCodec.optionalAny(node, "details"),
        JsonlCodec.optionalAny(node, "usage"),
        decodeStringList(node.get("addedToolNames")),
        node.has("isError") && node.get("isError").asBoolean(false),
        decodeTimestamp(node.get("timestamp")));
    case "system" -> …
};
```

新增小工具（JsonlCodec，≤6 行）：

```java
/** 读取第一个存在且为文本的键（pi 与本仓历史拼写并存的字段）。 */
static String firstText(JsonNode node, String... keys) {
    for (String key : keys) {
        var value = node.get(key);
        if (value != null && value.isTextual()) {
            return value.textValue();
        }
    }
    throw DecodeError.schema("missing one of " + String.join("/", keys));
}
```

**裸串 content**（`decodeBlocks` 头部，`:293`）：

```java
static List<ContentBlock> decodeBlocks(JsonNode node) {
    if (node == null) { throw JsonlCodec.DecodeError.schema("has invalid content"); }
    // pi 的 system/user content 可为裸串（types.ts:527/:544）⇒ 包成单个 text 块。
    if (node.isTextual()) {
        return List.of(new ContentBlock.TextContent(node.textValue()));
    }
    if (!node.isArray()) { throw JsonlCodec.DecodeError.schema("has invalid content"); }
    …
}
```

注意：user/system 才合法裸串；但 `decodeBlocks` 也被 tool/assistant 路径调用 —— 对它们
裸串在 pi 数据里不可达，统一按 text 块处理是安全的宽容（与 pi 的「parse 不校验」一致）。

**块别名**（`decodeBlock` switch）：

```java
case "toolCall", "tool_use" -> new ContentBlock.ToolUseContent(
    JsonlCodec.requireString(node, "id"),
    JsonlCodec.requireString(node, "name"),
    JsonlCodec.optionalObject(node, "arguments"),
    JsonlCodec.optionalString(node, "thoughtSignature"));
```

`"tool_use"` 是本仓 legacy（写入侧 2026-10-05 前）；其余块名两侧相同。

### 4.2 写：共享 `SessionWireShape.toPiTree(Entry)`

新类 `session/jsonl/SessionWireShape.java`（≤70 行）：

```java
/**
 * 把一条 entry 铺成 pi 主流拼写的 JSON 树（与包 12/13 的落线形状同源）。
 * valueToTree 后只做三处键名映射；内部模型与 record 分量名不动。
 */
static ObjectNode toPiTree(Entry entry) {
    ObjectNode node = (ObjectNode) SessionJson.mapper().valueToTree(entry);
    remapMessages(node);
    return node;
}

// 递归：节点是对象时按形状改键，数组逐元素递归。
private static void remapMessages(JsonNode node) { … }
```

三处映射（深搜，消息可能在 entry.message、compaction.retainedTail 等处）：

| 本仓拼写（valueToTree 产出） | pi 拼写 |
|---|---|
| `"role":"tool"` | `"role":"toolResult"` |
| `"toolUseId":…` | `"toolCallId":…` |
| 块 `"type":"tool_use"` | `"type":"toolCall"` |

实现按对象逐键重建（保留顺序），数组递归；其余键原样。

接线两处：

```java
// PiV3Wire.encodeEntryLine:139
- target.setAll((ObjectNode) SessionJson.mapper().valueToTree(m.entry()));
+ target.setAll(SessionWireShape.toPiTree(m.entry()));
```

```java
// sqlite EntryRows:34
- JsonNode node = SessionJson.mapper().valueToTree(entry);
+ JsonNode node = SessionWireShape.toPiTree(entry);
```

**system/user 裸串写侧不需要**：本仓内部 `UserMessage` 恒块列表、`SystemMessage`
同样产出 pi 接受的形状（pi 两形都吃）。

### 4.3 不变量与盲区

- 内部模型零改动 ⇒ 引擎/快照/TUI 读到的仍是 `"tool"` 角色，既有逻辑不动；
- provider 适配层不经此路径（转换器直接读 record 分量），无连带；
- legacy 文件：新解码器两拼写并读，无需迁移工具、不改旧文件；
- WS wire（`WebWireJson`）本来就已输出 pi 拼写，不重复处理。

## 5. 测试与证据

### 5.1 先红

| 测试 | 红 |
|---|---|
| `RealPiInteropTest`（已写好，2 用例：装载＋投影） | 当前 **2 红**，首错 line4 `has invalid content` |
| 读别名：补 `MessageJsonCodec` 用例（toolResult 角色/toolCallId 字段/toolCall 块/系统用户裸串） | 实现前红 |
| 写形状：新用例驱动 repository 落 tool 结果，断言行上是 `toolResult`/`toolCallId`/`toolCall` | 实现前红 |

`RealPiInteropTest.projectsTheRealSession` 的投影断言：`system,user,assistant,tool,assistant`
（内部角色名，投影在 ContextEntries 内仍是本仓拼写 —— 钉的是语义不是线上拼写）。

### 5.2 变异探针

- **M1**：写侧三处映射删掉 ⇒ 写形状用例恰红（钉映射承重）；
- **M2**：`firstText` 只留 pi 键 ⇒ legacy 夹具红（钉两拼写并存）；
- 变异后 grep 复核落地（老规矩）。

### 5.3 全量

`mvn -o clean verify` 14/14（checkstyle/spotbugs 含）；重点回归：agent-core 全部 JSONL/SQLite
互转用例、TUI 消息渲染、evals 夹具。

## 6. 实施步骤与提交

1. `feat(agent-core): accept pi-native message and tool-call spellings` ——
   解码别名 ＋ `RealPiInteropTest` 转绿 ＋ 读别名用例；
2. `feat(agent-core): write sessions with pi-native message spellings` ——
   `SessionWireShape` ＋ 两处接线 ＋ 写形状用例 ＋ M1/M2；
3. 闭环回填：本包 banner/§7、`docs/12`（B60/B61 状态）、`docs/05`
   （B60/B61 **销号**或仅留残余、新登记项）。

显式路径 `git add`；commit 末尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## 7. 验收与闭环记录（实施后回填）

- [ ] 真实 pi 会话原文装载＋投影全绿（`RealPiInteropTest` 2/2）；
- [ ] 写侧产物经 M1 钉为 pi 拼写；**补一次反向实跑**（本仓写会话 → pi CLI 读取/继续），
  验证工具结果不再被静默丢弃 —— 这是双向互读的最后一锤；
- [ ] 全 reactor 14/14；
- [ ] docs/05：**B60/B61 销号**（真互读已双向完成）。

（实施期裁决、红绿证据、提交哈希待回填。）
