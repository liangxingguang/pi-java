# 11 - L1 AI 行为面对齐设计（B265 第一部分）

> 【背景摘要】这是 L1（ai 模块）在固定锚点 `56cae7823` 下的第二个实施包设计稿。B264（流式工具参数与消息形状）已于 2026-11-02 闭环；本稿接手 B265 中不依赖外部目录数据的行为面四条：跨模型重放、上下文归一、转录工具状态、ModelsStore 取消。
>
> **文档状态：用户已批准并实施闭环（2026-11-02）。**

## 0. 范围、锚点与基线

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 目标锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec` |
| pi 证据路径 | 固定提交的 `packages/ai/` 与 `packages/coding-agent/`；只用 `git show <sha>:<path>` |
| pi-java 当前 HEAD | `8adb55a4`（本稿写作时） |
| 可执行基线 | B264 终态：`mvn test -pl pi-java-ai -am` 实测 telemetry 80 ＋ AI 1811 ＝ 1891 测试，0 失败 |
| 新文档号 | 本稿占 `docs/11`；目录数据与运行时留给 `docs/12` |

### 0.2 拆分依据（2026-11-02 用户裁决）

【场景】B265 原本合并了 9 个计分单元，其中目录数据（#34）依赖外部快照，无法与纯行为改动同步审核。

【决定】B265 拆两包。本包只做行为面：

- 地图 #61（跨模型重放）。
- 地图 #83（`normalizeContext`）。
- 地图 #84（转录工具状态）。
- 地图 #31（`ModelsStore` 操作取消）。

目录数据与运行时（地图 #16/#21/#33/#34）在 `docs/12` 另行设计，输入口径已定为「仓内 vendor 锚点生成器＋提交输入快照」。

【风险/代价】[内部，模型不可见] 两包分别回填地图；L1 总账面在 `docs/12` 闭环前只增加本包的 +7.5。

### 0.3 本包计分单元

| 地图行 | 能力 | pi 证据 | Java 证据 | 当前判定 | 权重 | 目标 |
|---|---|---|---|---|---:|---|
| #61 | `transformMessages` 跨模型重放 | `src/api/transform-messages.ts:119-177` | `AI/api/TransformMessages.java:278-323` | 缺失 | 3 | 对齐 |
| #83 | `normalizeContext` | `src/utils/transcript.ts:6-34` | `AI/api/ContextNormalizer.java:24-46` | 存疑 | 3 | 对齐 |
| #84 | 工具状态增量／transcript tools | `src/utils/transcript.ts:199-263` | `AI/api/TranscriptTools.java:21`；`AI/api/Transcripts.java:351-368` | 缺失 | 2 | 对齐 |
| #31 | `ModelsStore` CRUD 的 operation signal | `src/models-store.ts:17-46` | `AI/catalog/ModelsStore.java:13-23` | 存疑 | 2 | 对齐 |

加权：3 ＋ 1.5 ＋ 2 ＋ 1 ＝ **+7.5**。L1 由 `179.5/240＝74.8%` 变为 `187.0/240＝77.9%`。

### 0.4 不在本包

- 目录数据、图像目录、OpenRouter/Radius 运行时目录：`docs/12`。
- AI telemetry（B263）、ClientIdentity（B266）、Provider wire/usage/OAuth（B267）。
- `includeSystemPrompt` 选项：唯一生产者是 Codex 车道，R2 面外（见 §8 裁决 7）。
- Anthropic 的 inline tools：当前已对齐，`resolveTranscriptTools` 不参与该车道。
- 不新增 Maven 依赖。

### 0.5 术语

- **转录（transcript）**：请求发给模型前的那份消息序列。系统提示与工具声明在转录里以「系统消息」承载。
- **跨模型重放（cross-model replay）**：把历史助手消息发给**另一个模型**。别的模型产出的 thinking 文本与签名不可复用。
- **工具状态增量**：系统消息上的 `toolsAdded`／`toolsRemoved`，表示会话中途增删工具。
- **折叠（folded）**：把前若干个系统消息里的工具声明并进请求级 `tools` 字段；后续系统消息用「就地声明」表达增量。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本。此类契约逐字对齐。
- **窄差（D 编号）**：已登记、无法或无需消除的行为/形状差异，明细在 `docs/map/`。

## 1. 请求主线与四个改动点

### 1.1 调用链

```text
公开入口 StreamRequest
  └─ ContextNormalizer.normalize           ← 改动点 #83
       └─ 车道入口 resolveTranscript（折叠中途系统消息）
            └─ TransformMessages.apply     ← 改动点 #61
                 └─ resolveTranscriptTools ← 改动点 #84
                      ├─ Completions：请求级 tools ＋ Kimi 就地增量
                      └─ Responses：请求级 tools / additional_tools / tool_search
ModelsStore.read/write/delete              ← 改动点 #31
```

### 1.2 数据所有权

- `ContextNormalizer` 只负责把 legacy 字段折成首条系统消息，不改写调用方消息。
- `TransformMessages` 决定哪些内容块能活到重放；它不写线格。
- `Transcripts` / `TranscriptTools` 只做工具状态的「切分」，不决定具体线格。
- 三条车道各自把切分结果落成 provider 线格；**通知文案属于模型可见面**，集中放在 Responses 车道。

## 2. pi 逐字契约

### 2.1 `normalizeContext`

`packages/ai/src/utils/transcript.ts:6-34`：

```ts
export function createInitialSystemMessage(
	systemPrompt: string | undefined,
	tools: Tool[] | undefined,
): SystemMessage | undefined {
	const hasSystemPrompt = systemPrompt !== undefined && systemPrompt.length > 0;
	const hasTools = tools !== undefined && tools.length > 0;
	if (!hasSystemPrompt && !hasTools) return undefined;
	return {
		role: "system",
		content: systemPrompt ?? "",
		...(hasTools ? { toolsAdded: tools } : {}),
		timestamp: 0,
	};
}

export function normalizeContext(context: Context): TranscriptContext {
	const initialMessage = createInitialSystemMessage(context.systemPrompt, context.tools);
	const messages = initialMessage ? [initialMessage, ...context.messages] : context.messages;
	return { messages } as TranscriptContext;
}
```

关键点：

- **无条件前置**。即使 `context.messages` 的第一条已经是系统消息，也会再插一条。
- legacy 字段都为空时不插（`undefined`）。
- 新消息时间戳固定为 `0`。

`packages/ai/src/types.ts:780-790` 说明该行为是对外契约：

```ts
/**
 * Request input accepted by the public stream entry points (`Models.stream()`,
 * `streamSimple()`, ...). `systemPrompt` and `tools` are shorthand for a leading
 * system message; `normalizeContext()` folds them into one before the request
 * reaches a provider.
 */
export interface Context {
	systemPrompt?: string;
	messages: Message[];
	tools?: Tool[];
}
```

### 2.2 跨模型重放：thinking 与 text

`packages/ai/src/api/transform-messages.ts:119-177`：

```ts
if (msg.role === "assistant") {
	const assistantMsg = msg as AssistantMessage;
	const isSameModel =
		assistantMsg.provider === model.provider &&
		assistantMsg.api === model.api &&
		assistantMsg.model === model.id;

	const transformedContent = assistantMsg.content.flatMap((block) => {
		if (block.type === "thinking") {
			// Thinking from another model is dropped. Signatures and encrypted reasoning are
			// only valid for the model that produced them, and sending readable thinking as
			// plain text makes the new model treat it as part of the visible reply.
			if (!isSameModel) return [];
			// Keep redacted and signed blocks even if the thinking text is empty
			// (OpenAI encrypted reasoning); they are needed for replay.
			if (block.redacted || block.thinkingSignature) return block;
			if (!block.thinking || block.thinking.trim() === "") return [];
			return block;
		}

		if (block.type === "text") {
			if (isSameModel) return block;
			return {
				type: "text" as const,
				text: block.text,
			};
		}
		...
```

关键点：

- **跨模型 thinking 一律丢弃**，不再降级为可见文本。
- **跨模型 text 保留文本，丢掉 `textSignature`**（返回的新对象只有 `type` 与 `text`）。
- 同模型的 thinking 分支保持原样：redacted／带签名保留；空文本丢弃。
- `thoughtSignature` 的跨模型剥离与 toolCall id 归一不变（B264 已闭环）。

### 2.3 转录工具状态与 `foldedSystemMessages`

`packages/ai/src/utils/transcript.ts:199-263`：

```ts
/** Whether tool history contains a removal or same-name redeclaration that an addition-only transport cannot replay. */
export function hasNonAdditiveToolChanges(messages: TranscriptMessages): boolean {
	return findLastNonAdditiveToolChange(messages.filter(isSystemMessage)) >= 0;
}

/** Index of the last system message with a removal or same-name redeclaration, or -1. */
function findLastNonAdditiveToolChange(systemMessages: readonly SystemMessage[]): number {
	// A name declared before counts as a redeclaration even after its removal: Kimi K3 ignores a
	// removed tool that a later tool message declares again (verified October 2026).
	const declared = new Set<string>();
	let last = -1;
	for (let index = 0; index < systemMessages.length; index++) {
		const message = systemMessages[index];
		if ((message.toolsRemoved?.length ?? 0) > 0) last = index;
		for (const tool of message.toolsAdded ?? []) {
			if (declared.has(tool.name)) last = index;
			declared.add(tool.name);
		}
	}
	return last;
}

export type TranscriptToolSupport = "none" | "additions" | "changes";

export interface TranscriptTools {
	/** Tools sent in the top-level request field. */
	requestTools: Tool[];
	/**
	 * Number of leading system messages whose tool changes `requestTools` already holds. Every
	 * later system message carries its own tool changes in place.
	 */
	foldedSystemMessages: number;
}

export function resolveTranscriptTools(messages: TranscriptMessages, support: TranscriptToolSupport): TranscriptTools {
	const systemMessages = messages.filter(isSystemMessage);
	if (support === "none") {
		return { requestTools: getCurrentTools(systemMessages), foldedSystemMessages: systemMessages.length };
	}
	if (support === "changes") return { requestTools: [], foldedSystemMessages: 0 };
	const folded = Math.max(
		getInitialSystemMessage(messages) ? 1 : 0,
		findLastNonAdditiveToolChange(systemMessages) + 1,
	);
	return { requestTools: getCurrentTools(systemMessages.slice(0, folded)), foldedSystemMessages: folded };
}
```

三态含义（`transcript.ts:221-250` 的注释逐字摘要）：

- `"none"`：传输只有请求级工具表，表里永远是当前工具集。折叠数等于全部系统消息数。
- `"additions"`：工具只能在中途**新增**，不能删除或重定义。边界移到「最后一次非增量变更」之后；再往后的纯新增可以就地锚定。
- `"changes"`：每个工具（含初始工具）都在系统消息里就地声明，同名后声明覆盖前声明。请求级工具表为空，折叠数为 0。

### 2.4 Responses 工具支持三态与两条通知

`packages/ai/src/api/openai-responses-shared.ts:157-190`：

```ts
export function getResponsesToolSupport(options: {
	supportsMidConvoSystemMessages?: boolean;
	supportsAdditionalTools?: boolean;
	supportsToolSearch?: boolean;
}): TranscriptToolSupport {
	if (options.supportsMidConvoSystemMessages && options.supportsAdditionalTools) return "changes";
	return options.supportsToolSearch ? "additions" : "none";
}

function renderIncrementalToolsNotice(unavailable: ReadonlySet<string>): string {
	const notice =
		"This is an incremental tools update. Previously declared tools remain available unless explicitly marked unavailable. A tool declared again here is available again, and its latest definition replaces the earlier one.";
	return unavailable.size > 0 ? `${notice}\nStill unavailable: ${[...unavailable].join(", ")}.` : notice;
}

function renderToolRemovalNotice(names: readonly string[]): string {
	return `The following tools are no longer available. Do not call them:\n${names.map((name) => `- ${name}`).join("\n")}`;
}
```

`packages/ai/src/api/openai-responses-shared.ts:232-299`（`changes` 分支与系统消息循环）：

```ts
const appendSystemToolAdditions = (message: SystemMessage, seed: string): void => {
	const tools = message.toolsAdded ?? [];
	for (const tool of tools) unavailableTools.delete(tool.name);
	if (tools.length === 0) return;
	if (toolSupport === "changes") {
		if (declaredAdditionalTools) {
			messages.push({ role: instructionRole, content: renderIncrementalToolsNotice(unavailableTools) });
		}
		declaredAdditionalTools = true;
		messages.push({
			type: "additional_tools",
			role: "developer",
			tools: convertResponsesTools(tools, options?.toolOptions),
		} satisfies ResponseInputItem);
		return;
	}
	if (toolSupport !== "additions") return;
	// ...合成 tool_search_call + tool_search_output...
};

// 系统消息循环
if (msg.role === "system") {
	const anchorsToolChanges = systemIndex++ >= transcriptTools.foldedSystemMessages;
	if (anchorsToolChanges) appendSystemToolAdditions(msg, `system:${msgIndex}`);
	if (!isLeadingSystemMessage || includeInitialSystemMessage) {
		const parts = [isLeadingSystemMessage ? getSystemMessageText(msg) : renderSystemMessageUpdate(msg)];
		if (anchorsToolChanges) {
			const added = new Set((msg.toolsAdded ?? []).map((tool) => tool.name));
			const removed = (msg.toolsRemoved ?? []).map((tool) => tool.name).filter((name) => !added.has(name));
			if (removed.length > 0) parts.push(renderToolRemovalNotice(removed));
			for (const name of removed) unavailableTools.add(name);
		}
		const text = parts.filter((part) => part.length > 0).join("\n\n");
		if (text.length > 0) {
			messages.push({ role: instructionRole, content: sanitizeSurrogates(text) });
		}
	}
}
```

逐消息落线顺序（`changes` 模式）：

1. 增量通知（仅当此前已发过 `additional_tools`）。
2. `additional_tools` 项。
3. 该条系统消息的正文（前导用完整提示；中途用分段差分）。
4. 删除通知（与正文同一条指令消息，用两个换行连接；同名重声明不算删除）。
5. 本消息实际删除的工具加入「仍不可用」集合。

`additions` 模式下第 3、4 步只对「边界之后」的系统消息执行；`tool_search` 合成对也只在这些消息上出现。

### 2.5 Completions 的 folded 读取

`packages/ai/src/api/openai-completions.ts:1213-1249`：

```ts
const transcriptTools = resolveTranscriptTools(
	normalizedContext.messages,
	compat.supportsMidConvoSystemMessages === true && compat.supportsMidConvoToolAdditions === true
		? "additions"
		: "none",
);

let systemIndex = 0;
for (let i = 0; i < transformedMessages.length; i++) {
	const msg = transformedMessages[i];
	...
	if (msg.role === "system") {
		const addedTools = systemIndex++ >= transcriptTools.foldedSystemMessages ? (msg.toolsAdded ?? []) : [];
		if (addedTools.length > 0) {
			const kimiToolMessage: KimiToolSystemMessageParam = {
				role: "system",
				tools: convertTools(addedTools, compat),
			};
			params.push(kimiToolMessage as unknown as ChatCompletionMessageParam);
		}
		const text = i === 0 ? getSystemMessageText(msg) : renderSystemMessageUpdate(msg);
		if (text.length > 0) {
			params.push({ role: instructionRole, content: sanitizeSurrogates(text) });
		}
	}
	...
}
```

关键点：`systemIndex` 对**每条系统消息**自增，包括前导那条；只有序号 ≥ 折叠数的才发就地工具增量。

### 2.6 `ModelsStore` 的 operation signal

`packages/ai/src/models-store.ts:17-46`：

```ts
export interface ModelsStoreOperationOptions {
	signal?: AbortSignal;
}

/** Persistent model catalogs keyed by provider ID. */
export interface ModelsStore {
	read(providerId: string, options?: ModelsStoreOperationOptions): Promise<ModelsStoreEntry | undefined>;
	write(providerId: string, entry: ModelsStoreEntry, options?: ModelsStoreOperationOptions): Promise<void>;
	delete(providerId: string, options?: ModelsStoreOperationOptions): Promise<void>;
}

export class InMemoryModelsStore implements ModelsStore {
	async read(providerId: string, options?: ModelsStoreOperationOptions): Promise<ModelsStoreEntry | undefined> {
		options?.signal?.throwIfAborted();
		const entry = this.entries.get(providerId);
		return entry ? structuredClone(entry) : undefined;
	}
	// write/delete 同样在入口 throwIfAborted()
}
```

`packages/coding-agent/src/core/models-store.ts:120-146`（文件实现）在读取前、读取后各检查一次；写入与删除在拿锁的辅助器内检查，锁等待期间被取消时不落盘。

## 3. Java 现状与已证实缺口

### 3.1 #83 `normalizeContext`：多了一条「重复守卫」

`pi-java-ai/src/main/java/com/pijava/ai/api/ContextNormalizer.java:30-34`：

```java
if (!suppliedMessages.isEmpty()
        && suppliedMessages.getFirst() instanceof Message.SystemMessage) {
    normalized.addAll(suppliedMessages);
    return new TranscriptContext(normalized);
}
```

后果：当调用方同时给出 legacy 的 `systemPrompt`／`tools`，且消息已经以系统消息开头时，Java 会**丢掉** legacy 字段；pi 会再插一条。直接调用公开入口的用户因此看到不同转录。

为什么当年加守卫：harness 生产路径同时满足两个条件——

- `DefaultProviders` 把 `context.systemPrompt()` 与 `context.tools()` 传给 `StreamRequest`；
- 转录首条已是由 `promptLoadout`／`ToolChangeDeclaration` 落好的系统消息。

`pi-java-agent-core/.../PiLaneEngine.java:300-330` 与 `ContextAssembler.java:154-166` 的注释写明：生产路径上 `systemPrompt` 是**派生值**，模型实际看到的提示走转录。守卫在这里防止重复前置。

pi 没有这个问题，因为它的 agent-loop 只传 messages：

`packages/agent/src/agent-loop.ts:406`：

```ts
const llmContext = normalizeContext({ messages: llmMessages });
```

所以本包要同时改 API 语义和那一个生产调用点，不能只删守卫。

### 3.2 #61 跨模型重放：把 thinking 降级成了文本

`pi-java-ai/src/main/java/com/pijava/ai/api/TransformMessages.java:307-323`：

```java
if (th.redacted()) {
    return same ? th : null;
}
if (same && !th.signature().isEmpty()) {
    return th;
}
if (th.text() == null || th.text().trim().isEmpty()) {
    return null;
}
if (same) {
    return th;
}
return new ContentBlock.TextContent(th.text());
```

与 pi 的差异：

- 跨模型、有文本的 thinking：Java 返回可见文本，pi 丢弃。**模型可见行为变化**。
- 跨模型文本块：Java 原样返回（保留 `textSignature`），pi 返回只含 `text` 的新块。

### 3.3 #84 工具状态：布尔开关没有边界数

`pi-java-ai/src/main/java/com/pijava/ai/api/TranscriptTools.java:21`：

```java
public record TranscriptTools(List<ToolDefinition> requestTools, boolean anchorsAdditions) {
```

`pi-java-ai/src/main/java/com/pijava/ai/api/Transcripts.java:357-368`：

```java
public static TranscriptTools resolveTranscriptTools(List<Message> messages,
                                                     boolean supportsToolAdditions) {
    boolean anchorsAdditions = supportsToolAdditions && !hasNonAdditiveToolChanges(messages);
    List<ToolDefinition> requestTools;
    if (anchorsAdditions) {
        var head = getInitialSystemMessage(messages);
        requestTools = head == null ? List.of() : head.toolsAdded();
    } else {
        requestTools = getCurrentTools(messages);
    }
    return new TranscriptTools(requestTools, anchorsAdditions);
}
```

缺口：

1. 没有 `"changes"` 三态。
2. 没有 `foldedSystemMessages`：一旦历史里出现删除/重定义，Java 退回「完整当前工具表」，后面的纯新增也不再就地锚定。pi 会把边界移到**最后一次非增量变更**，之后的纯新增继续锚定（`#10940` 的缓存前缀语义）。
3. Responses 支持判定用 `supportsAdditionalTools || supportsToolSearch`，没有与 `supportsMidConvoSystemMessages` 相与；pi 在没有中途系统消息时不会用 `additional_tools` 锚定。
4. Responses 没有 `changes` 模式的两条通知，也没有「仍不可用」集合。

### 3.4 #31 `ModelsStore`：没有 operation signal

`pi-java-ai/src/main/java/com/pijava/ai/catalog/ModelsStore.java:13-23` 只有 `read(String)`／`write(String, Entry)`／`delete(String)` 三个方法。

`pi-java-ai/src/main/java/com/pijava/ai/AbortSignal.java:13-43` 只有 `isAborted()`／`abort()`／`onAbort()`，没有 `throwIfAborted()`。

`RefreshModelsContext.java:17` 的旧裁决写的是「signal 不移植」，本包按新锚点改写该结论：接口形状对齐 pi，调用方可选传入。

### 3.5 固定在旧行为上的测试

| 测试 | 钉住的旧行为 | 处置 |
|---|---|---|
| `ContextNormalizerTest.preservesExistingLeadingSystemMessageWithoutInjectingLegacyFields` | 首条系统消息时丢弃 legacy 字段 | 改为 pi 语义：两条系统消息 |
| `TransformMessagesTest` 多条 | 跨模型 thinking 降级为 text | 改为丢弃；新增 textSignature 剥离用例 |
| `LaneTransformMessagesWiringTest` | 车道收到降级后的文本 | 改为断言 thinking 不落线 |
| `ToolStateChangesTest` 四条 `resolveTranscriptTools` | 布尔语义 | 改为三态＋折叠数 |
| `ResponsesToolChangesWireTest` | `additional_tools` 只在有 `supportsAdditionalTools` 时锚定 | 改为 `changes`／`additions`／`none` 三组 |
| `CompletionsToolChangesWireTest` | Kimi 只在「纯增量」时锚定 | 增加边界移动用例 |
| `FileModelsStoreTest` | 无 signal | 增加取消用例 |

## 4. 拟改 API 与实现顺序

### 4.1 公共类型（`com.pijava.ai.api`）

```java
public enum TranscriptToolSupport { NONE, ADDITIONS, CHANGES }

public record TranscriptTools(List<ToolDefinition> requestTools, int foldedSystemMessages) {
    public TranscriptTools {
        requestTools = List.copyOf(requestTools);
    }
}
```

`TranscriptToolSupport` 用 `enum`：它是纯常量闭集，无附加字段（符合项目编码规范）。

`Transcripts.resolveTranscriptTools(List<Message>, TranscriptToolSupport)` 替换旧的布尔重载。旧的 `hasNonAdditiveToolChanges` 保留布尔外壳，内部委托新的 `findLastNonAdditiveToolChange`。

### 4.2 `ContextNormalizer` 与生产调用点

`ContextNormalizer.normalize` 删除首条系统消息的提前返回，始终按 pi 前置。

`pi-java-coding-agent/.../DefaultProviders.java:400` 增加一处机械判定：

- 若 `context.messages()` 首条已是 `SystemMessage` ⇒ 传 `systemPrompt = null`、`tools = List.of()`。
- 否则维持现状。

这样模型可见请求与今天完全一致（生产路径仍只有转录里的那条系统消息），而公开入口获得 pi 的无条件前置语义。

【内部，模型不可见】改动只影响「legacy 字段与系统消息同时存在」的调用形状，不改变任何现有生产请求。

### 4.3 `TransformMessages`

`gateBlock` 的 thinking 分支改为：

```java
if (!same) {
    return null;                       // 跨模型 thinking 一律丢弃
}
if (th.redacted() || !th.signature().isEmpty()) {
    return th;
}
if (th.text() == null || th.text().trim().isEmpty()) {
    return null;
}
return th;
```

text 分支新增：

```java
if (block instanceof ContentBlock.TextContent tc && !same) {
    return new ContentBlock.TextContent(tc.text(), null);   // 剥离 textSignature
}
```

顺序保持 pi：thinking → text → toolCall。同模型行为不变。

### 4.4 `Transcripts`

新增 `findLastNonAdditiveToolChange`（按系统消息序号返回最后一次非增量变更，未找到返回 -1），并按 §2.3 的三分支实现 `resolveTranscriptTools`。

`getCurrentTools` 已存在，直接复用。折叠切片只取系统消息子序列。

### 4.5 Responses 车道

`ResponsesMessageConverter.buildParams` 改为：

```java
var support = ResponsesToolChanges.support(
    Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages()),
    Boolean.TRUE.equals(compat.supportsAdditionalTools()),
    Boolean.TRUE.equals(compat.supportsToolSearch()));
var transcriptTools = Transcripts.resolveTranscriptTools(transcript.messages(), support);
```

`ResponsesMessageItems.convertMessages` 重构：

- 删除循环前的「前导系统文本先行落线」。
- 循环内统一处理前导与中途系统消息（`isLeadingSystemMessage = i == 0 && msg instanceof SystemMessage`）。
- 用 `systemIndex` 与 `foldedSystemMessages` 决定是否就地锚定。
- `changes` 模式的状态（`declaredAdditionalTools`、`unavailableTools`）放在一个包内可见的小类型里，避免把 `ResponsesMessageItems` 撑大。
- 文案集中在 `ResponsesToolChanges`，逐字对齐 §2.4：
  - `renderIncrementalToolsNotice`
  - `renderToolRemovalNotice`

`ResponseToolWire.anchorSystemToolAdditions` 由布尔参数改为按 `TranscriptToolSupport` 分支；工具转换函数保持不变。

### 4.6 Completions 车道

`OpenAICompletionsMessageConverter`：

- 支持判定改为 `ADDITIONS` / `NONE`（与 pi 同式）。
- 循环增加 `systemIndex`；系统消息分支用 `systemIndex++ >= foldedSystemMessages` 控制 Kimi 工具消息。
- 前导系统文本仍由循环前预发，循环内 `i == 0` 跳过文本；这与 pi 的线格顺序一致。

### 4.7 `ModelsStore` 与 `AbortSignal`

新增：

```java
public record ModelsStoreOperationOptions(AbortSignal signal) {
    public static final ModelsStoreOperationOptions NONE = new ModelsStoreOperationOptions(null);
    public void throwIfAborted() {
        if (signal != null) signal.throwIfAborted();
    }
}
```

`AbortSignal` 增加：

```java
public void throwIfAborted() {
    if (aborted) throw new AbortedException();
}
```

`AbortedException extends RuntimeException` 放在 `com.pijava.ai`，消息固定为 `"This operation was aborted"`（对应 pi 的 `DOMException` 名 `AbortError`，但 Java 侧不伪造 DOM 类型）。

接口改为两参方法与一参默认方法：

```java
Optional<ModelsStoreEntry> read(String providerId, ModelsStoreOperationOptions options);
default Optional<ModelsStoreEntry> read(String providerId) { return read(providerId, ModelsStoreOperationOptions.NONE); }
// write/delete 同形
```

实现：

- `InMemoryModelsStore`：入口 `throwIfAborted()`，与 pi 逐字同形。
- `FileModelsStore`：入口检查；拿锁后再检查；写入前再检查一次。信号在等待文件锁期间被触发时，拿到锁后立即失败且不写盘。

`RefreshModelsContext` 的旧「不移植 signal」注释同步改写；本包不把信号接进刷新协调器（那是 L5 行为），只保证接口可选。

## 5. 状态迁移与线格顺序

### 5.1 Responses `changes` 模式

| 输入系统消息 | 落线 |
|---|---|
| 前导，`toolsAdded=[A]` | `additional_tools([A])`；然后完整提示文本 |
| 中途，`toolsAdded=[B]`，无删除 | 增量通知；`additional_tools([B])`；然后分段差分文本 |
| 中途，`toolsAdded=[B]`，`toolsRemoved=[A]` | 增量通知（不含 A）；`additional_tools([B])`；然后「差分文本 ＋ 删除通知(A)」同一条消息 |
| 中途，只删除 `[A]` | 无增量通知；无 `additional_tools`；然后「差分文本 ＋ 删除通知(A)」 |
| 中途，重声明 A（`toolsRemoved=[A]` 且 `toolsAdded=[A]`） | 视为重定义：不发删除通知；A 仍可用 |

### 5.2 `additions` 模式

| 历史 | `requestTools` | `foldedSystemMessages` |
|---|---|---:|
| `[head(A,B)]` | `[A,B]` | 1 |
| `[head(A,B), remove(B)]` | `[A]` | 2 |
| `[head(A,B), remove(B), add(B)]` | `[A,B]` | 3 |
| `[head(A,B), remove(B), add(B), add(C)]` | `[A,B]` | 3（C 就地锚定） |
| 无前导系统消息，`[sys(A)]` | `[]` | 0（A 就地锚定） |

### 5.3 `none` 模式

请求级工具表永远是完整当前集；没有就地锚定；折叠数等于系统消息条数。

## 6. RED-first 验收

### 6.1 单元测试

| 文件 | 用例 |
|---|---|
| `ContextNormalizerTest` | legacy 字段＋首条系统消息 ⇒ 两条系统消息；空 legacy ⇒ 原样 |
| `TransformMessagesCrossModelThinkingTest`（新增） | 镜像 pi `transform-messages-cross-model-thinking.test.ts`：跨模型 thinking 丢弃；同模型保留；交错模型只保留目标模型 |
| `TransformMessagesTest` | 更新旧「降级为文本」断言 |
| `TransformMessagesThoughtSignatureTest` | 增加跨模型 textSignature 剥离 |
| `ToolStateChangesTest` | 按 pi `transcript-tool-changes.test.ts:515-544` 的六条期望重写 `resolveTranscriptTools` |
| `FileModelsStoreTest` / `InMemoryModelsStoreTest`（新增） | 已中止信号在读写删入口抛 `AbortedException`；File 实现在拿锁后中止不落盘 |

### 6.2 线格测试

| 文件 | 用例 |
|---|---|
| `ResponsesToolChangesWireTest` | `changes`：顶层 `tools` 缺席；前导 `additional_tools`；增量通知文案；删除通知文案；「仍不可用」随重声明消失 |
| `ResponsesToolChangesWireTest` | `additions`：删除后顶层表退到边界，后续纯新增仍用 `tool_search`，请求前缀只回退一次 |
| `ResponsesToolChangesWireTest` | `none`：即使带 `additional_tools`，没有 `midConvoSystemMessages` 也不锚定 |
| `CompletionsToolChangesWireTest` | 删除后边界移动；后续纯新增仍发 Kimi `{role:"system", tools}` |
| `LaneTransformMessagesWiringTest` | 跨模型 thinking 不落线；同模型签名 thinking 保留 |

文案断言逐字包含：

- `"This is an incremental tools update. Previously declared tools remain available unless explicitly marked unavailable. A tool declared again here is available again, and its latest definition replaces the earlier one."`
- `"\nStill unavailable: "` ＋ 名单。
- `"The following tools are no longer available. Do not call them:\n- "` ＋ 名单。

### 6.3 纵贯与回归

- `DefaultProviders` 变更后，harness 的生产请求仍只有一条系统消息：跑 `mvn test -pl pi-java-agent-core -am` 与 `mvn test -pl pi-java-coding-agent -am`。
- 全仓 `mvn clean verify`：测试零失败、Checkstyle 零错误零警告、SpotBugs 零新增。

## 7. 验证门与文件约束

1. 先落红灯测试，再写实现。
2. 模块验证：`mvn test -pl pi-java-ai -am`。
3. 包闭环前：`mvn clean verify`。
4. 验证日志不写在 `target/`；关键结果回填本稿 §10。
5. 单文件 ≤ 500 行。`OpenAICompletionsMessageConverter` 当前 462 行，改动需控制在 500 以内；超出则抽出状态辅助类型。
6. 不使用 `@SuppressWarnings`。

## 8. 裁决与风险

### 裁决 1：`normalizeContext` 改为 pi 的无条件前置，守卫下移到生产调用点

- 【场景】Java 的公开入口在「legacy 字段＋首条系统消息」时丢弃 legacy 字段；pi 会再插一条。但 harness 生产路径依赖旧守卫，否则会重复前置系统提示。
- 【决定】`ContextNormalizer` 按 pi 语义无条件前置；`DefaultProviders` 在检测到首条系统消息时改传 `systemPrompt = null`、`tools = List.of()`。生产请求内容不变，公开入口语义对齐。
- 【风险/代价】[内部，模型不可见] 只改一个生产调用点的形状；harness 回归测试必须全绿。若未来新增直接使用 legacy 构造器的生产调用点，需要显式判断是否已带系统消息。

### 裁决 2：跨模型 thinking 一律丢弃，不再降级为文本

- 【场景】旧实现把别的模型的 thinking 当可见文本发给新模型，新模型会把它当成回复的一部分。
- 【决定】按 pi `transform-messages.ts:128-132` 丢弃。
- 【风险/代价】[模型可见] 跨模型历史里少一段文本；这是有意的契约修正。同模型 thinking 行为不变。

### 裁决 3：跨模型文本剥离 `textSignature`

- 【场景】签名只对产出它的模型有效。
- 【决定】跨模型返回只含 `text` 的新 `TextContent`。
- 【风险/代价】[模型可见] 线上不再携带过期签名；同模型保留。

### 裁决 4：工具状态改用三态＋折叠数

- 【场景】布尔开关无法表达「边界之后仍可就地新增」，且 Responses 的 `additional_tools` 判据缺少中途系统消息门。
- 【决定】引入 `TranscriptToolSupport` 与 `foldedSystemMessages`；Responses 用三段判定；Completions 用 `ADDITIONS`／`NONE`。
- 【风险/代价】[模型可见] `additional_tools` 在 `changes` 模式下顶层不再有 `tools`；工具声明顺序与缓存前缀随 pi。属于有意对齐。

### 裁决 5：`changes` 模式采用全量就地声明＋文本通知

- 【场景】`additional_tools` 不能删除工具，也不能覆盖顶层工具。
- 【决定】顶层工具表置空；每个系统消息就地声明增量；删除用固定文案通知；重声明同名工具视为重定义，不算删除。
- 【风险/代价】[模型可见] 新增两条固定文案；必须逐字。旧模型若把增量读成完整工具集，通知文案先于工具项给出缓解（pi 同款）。

### 裁决 6：`ModelsStore` 移植 operation signal

- 【场景】pi 的接口允许每次读写删传入中止信号。
- 【决定】新增 `ModelsStoreOperationOptions` 与 `AbortSignal.throwIfAborted()`；一参方法保留为默认方法；`FileModelsStore` 在入口、拿锁后、写盘前检查。
- 【风险/代价】[内部，模型不可见] 异常类型是 Java 自己的 `AbortedException`，不伪造 `DOMException`。文件锁等待中的取消不是「立即」返回，而是拿到锁后立即失败且不写盘；pi 的即时竞速依赖 JS 事件循环，Java 不额外起线程模拟。此差异不改变可见结果，不登记 D。

### 裁决 7：不移植 `includeSystemPrompt`

- 【场景】pi 的 Responses 选项允许不发前导系统消息。
- 【决定】唯一生产者是 Codex 车道（`openai-codex-responses.ts:560,1588`），属 R2 面外；本包不新增该选项。
- 【风险/代价】[内部，模型不可见] 主流 Responses 车道与 Azure 都发送前导系统消息，与今天一致。

### 裁决 8：不把 signal 接进刷新协调器

- 【场景】`RefreshModelsContext` 目前没有 signal 字段。
- 【决定】本包只对齐 `ModelsStore` 的接口形状；刷新链路是否传信号属于 L5（coding-agent）行为，另行设计。
- 【风险/代价】[内部，模型不可见] 接口能力已具备，但没有新的生产取消路径。

### 风险表

| 风险 | 控制 |
|---|---|
| 删除 `ContextNormalizer` 守卫导致生产重复系统提示 | `DefaultProviders` 同步下移判定；agent-core/coding-agent 全量回归 |
| 跨模型 thinking 丢弃影响旧会话重放 | 按 pi 锚点；旧会话属于新锚点重测范围，不做兼容层 |
| `foldedSystemMessages` 计算错位（含前导消息计数） | 用 pi 的六条期望逐条单元测试 |
| `changes` 模式线格顺序错误 | 用 pi `transcript-tool-changes.test.ts` 的输入逐条对照线格 |
| 文件超 500 行 | 抽出 `ResponsesToolChanges` / `ModelsStoreOperationOptions` |
| 旧测试钉住旧行为 | 本包同步更新断言，不保留双语义 |

## 9. 账面影响与后续

- 本包闭环后翻地图 #31/#61/#83/#84 四行。
- L1 加权：`187.0/240 = 77.9%`；未加权分母不变（131 个计分单位）。
- `docs/12` 处理 #16/#21/#33/#34：目录数据、图像目录、OpenRouter/Radius 运行时目录；输入口径已定，设计时另附快照哈希与生成器来源。
- `docs/04` 同步：B265 备注拆两段；#31/#61/#83/#84 的旧结论改为「已按 docs/11 闭环」。

## 10. 闭环记录

### 10.1 提交

| 模块 | 提交 |
|---|---|
| pi-java-ai | `ea2f3db6` `feat(ai): align replay, transcript tools and store cancellation` |
| pi-java-coding-agent | `dd42fe0e` `feat(coding-agent): keep one system message on harness requests` |

### 10.2 验证证据

- 模块：`mvn test -pl pi-java-ai -am`，telemetry 80 ＋ AI 1819 ＝ 1899 测试，0 失败／0 错误／0 跳过；Checkstyle 0 违规。
- 下游：`mvn test -pl pi-java-coding-agent -am` 全绿。
- 全仓：`mvn clean verify`，16 个 reactor 模块全部 SUCCESS，合计 5856 测试（0 失败／0 错误；29 个既有条件跳过）；Checkstyle 0 违规，SpotBugs `BugInstance size is 0`。
- 最终工作树在 `AbortedException` 补 `serialVersionUID` 后复跑 AI 模块，仍为 1819 测试零失败。

### 10.3 账面

`docs/map/02-ai.md` 翻 #31/#61/#83/#84 四行：L1 由 `179.5/240＝74.8%` 变为 **`187.0/240＝77.9%`**；未加权 `100.5/131＝76.7%`；域表「模型目录／元数据」23/28＝82.1%，「横切」44/52＝84.6%。
