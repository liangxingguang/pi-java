# 20 - L1 AI Faux 可编程响应、usage 估算与 deferred 取回对齐设计（#80／D203／D212）

> 【背景摘要】docs/19 已闭环；L1 只剩最后 1 个计分单元 #80（Faux 测试替身）。本包把 pi `providers/faux.ts` 的三段行为补进 Java：usage 与 prompt-cache 估算、增量工具参数分块、deferred 提交／取回／取消；同时关掉 D203（`pi.ai.poll` 无生产入口）并为 D212 补生产→取回→重建的集成测试。
>
> 文档状态：**已实施并闭环（2026-11-04）**，实施记录见 §13。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。

## 0. 范围、基线与口径

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | docs/19 闭环（HEAD `cae8f79e`） |
| L1 当前账面 | **231.0/232＝99.57%**；对齐 124／存疑 0／缺失 1 |
| 本包目标 | #80 缺失→对齐；账面 **232.0/232＝100%** |
| 可执行基线 | docs/19 终态：`mvn clean verify` 实测 **6,102 测试／0 失败／0 错误／33 跳过** |
| 依赖变更 | 无（纯源码与测试） |

### 0.2 为什么最后一包是 Faux

【场景】B267 的其余面已全部闭环。`docs/map/02-ai.md` 只剩 #80（FauxProvider，测试设施，权重 1）一个缺失；docs/19 已明确把 prompt-cache 估算、增量参数分块与 deferred fetch／cancel 留给本包。

【决定】本包只做这三段行为，并把它们必须依赖的两处基础设施一并补齐：

| 地图行 | 能力 | 权重 | 现判定 | 本包动作 |
|---|---|---:|---|---|
| #80 | FauxProvider | 1 | 缺失 | 新增消息型可编程核心：usage／prompt-cache 估算、工具参数增量分块、deferred 提交／取回／取消 |

附带关闭两个挂账：

| 挂账 | 内容 | 关闭方式 |
|---|---|---|
| D203 | `pi.ai.poll` 观察者与 schema 已落地，但没有生产入口 | `StreamApi.fetchDeferred` 成为入口，观察者以 `poll=true` 挂到 faux 的取回流 |
| D212 | `DeferredHandle.data` 的 `Map` 承载差异；Faux deferred 落地时补集成测试 | 生产（faux 提交）→ 取回 → 持久化消息重建的端到端测试 |

【风险/代价】[内部，模型不可见] 这不是纯“补测试工具”：为了真实承载 deferred，必须给流式 `AssistantMessage` 加 `deferred` 组件，并给 `StreamApi` 加三个默认方法。改动面比权重 1 看起来大，但不这样做就只能造旁路，行为与 pi 不同形。

### 0.3 账面影响

| 口径 | docs/19 后 | 本包后（预计） |
|---|---:|---:|
| 加权 | 231.0/232＝99.57% | **232.0/232＝100%** |
| 未加权 | 124/125＝99.2% | **125/125＝100%** |
| 对齐／存疑／缺失 | 124／0／1 | **125／0／0** |
| 权重 1 | 52/53＝98.1% | **53/53＝100%** |
| 横切域 | 50/51＝98.0% | **51/51＝100%** |

### 0.4 不在本包

- Codex（`openai-codex-responses`）与任何真实 provider 的 deferred 实现：R2 面外，且 Java 侧没有该车道。
- pi 的 `AbortSignal` 中止分支：Java 的 `StreamApi` 没有 signal 通道，本包不新增（见 D216）。
- 异步响应工厂（`async (…) => AssistantMessage`）：本包只提供同步工厂（见 D214）。
- 不改 Faux 的事件重放模式语义（见 §7.1）。
- 不改遥测 schema 或 `pi.ai.request` 的既有观察者分支。
- 不把 `wait`（deferred 长轮询毫秒）落成 Java 选项：pi 的 faux 本身不读它；Codex 才消费，而 Codex 在 R2 外。

### 0.5 术语

- **Faux**：pi 的可编程假 provider。它不访问网络，测试通过“预置响应队列”驱动模型回复。属于【内部，模型不可见】的测试设施。
- **可编程核心**：本包新增的 Java 类，按 pi `createFauxCore` 的形状把“预置响应 → 事件流”的解析、估算与分块集中在一处。
- **响应步（response step）**：队列里的一项。它要么是一条完整的助手消息，要么是一个工厂；工厂按请求上下文、选项、状态和模型现场生成消息。
- **usage 估算**：Faux 用“序列化后的转录文本长度 ÷ 4”模拟 provider 上报的 token 用量。
- **prompt-cache 模拟**：Faux 按 `sessionId` 记住上一次的提示文本，用公共前缀长度模拟 `cacheRead`／`cacheWrite`。
- **deferred 响应**：provider 先把请求挂起，返回一个 `DeferredHandle`；调用方稍后用该 handle 取回最终消息。
- **取回（fetch）／取消（cancel）**：用 handle 拉取最终结果／声明不再取回。
- **partial**：流式事件携带的助手消息快照（`com.pijava.ai.message.AssistantMessage`），与最终持久化消息（`Message.AssistantMessage`）是两个类型。

## 1. 判据

1. **按证据列收口**：只对齐 `docs/map/02-ai.md` #80 引用的三段 pi 代码（`faux.ts:265-272` 缓存、`427-443` 参数分块、`591-665` deferred）；不扩到 pi faux 的全量注册/卸载机制。
2. **旧模式零变化**：`FauxProvider` 的四个既有构造器与 `text`／`toolCall`／`error`／`sequence` 工厂保持逐事件重放语义。仓库内约 105 处引用不回退。
3. **消息型核心是唯一载体**：估算、分块、deferred 都从同一条「解析消息 → 估算 usage → 合成事件」链产出，不写三套近似实现。
4. **handle 放回消息**：pi 的 `AssistantMessage` 本来就带 `deferred`；Java 缺口是流式 partial 记录缺这个组件。补组件，不造旁路事件。
5. **请求选项走既有通道**：`deferred` 与 `onResponse` 用 `ApiOptions.extra`（sessionId／cacheRetention 同一通道），不新造 options 类型。
6. **RED-first**：每一段先写红测，再做实现；跨模块改动跑 `mvn test -pl … -am`，收口跑全量 `mvn clean verify`。

## 2. 现状与差距

### 2.1 pi 的 faux 形状

`faux.ts` 的核心是一个闭包（`createFauxCore`，`:459-697`）：

- 预置 `pendingResponses`，每次 `stream` 取走队首；空队列报 `No more faux responses queued`（`:529-546`）。
- 解析响应时先克隆身份，再 `withUsageEstimate`（`:512-525`）。
- 单个响应按内容块合成 `start／thinking／text／toolcall／done／error` 事件（`streamWithDeltas`，`:358-457`）。
- 工具参数按随机 token 大小切块，交给真实的增量 JSON 解析器（`:427-443`）。
- deferred 提交把响应步存进 map（`:548-573`）；取回与取消在 `:591-666`。
- 状态计数 `callCount`／`deferredFetchCount`／`cancelledDeferred`（`:469`）。

### 2.2 Java 现状

| 面 | Java 证据 | 现状 |
|---|---|---|
| 重放 provider | `AI/provider/FauxProvider.java:27-166` | 预置 `List<StreamEvent>`，按序提交；无模型、工厂、状态、usage |
| 重放 API | `AI/provider/FauxProvider.java:193-237` | 继承 `AbstractChatApi`；只实现 `streamInternal` |
| usage | `AI/provider/FauxProvider.java:82-146` | 事件里的 usage 原样重放；终局缺 usage 时由出口缝兜零，没有任何估算 |
| 工具参数 | `AI/provider/FauxProvider.java:102-121` | `toolCall` 只发**一整段** JSON delta |
| deferred 类型 | `AI/message/DeferredHandle.java:28-40` | 类型存在，无生产者 |
| deferred 流 | `AI/message/AssistantMessage.java:70-86` | 流式快照**没有** `deferred` 组件 |
| 持久化投影 | `AI/message/Message.java:286-301` | `fromPartial` 把 `deferred` 写死为 `null` |
| API 入口 | `AI/api/StreamApi.java:20-49` | 只有 `stream`／`streamBlocking`，没有取回／取消 |
| poll 遥测 | `AI/protocol/AiRequestTelemetry.java:37-109` | `observe(..., poll, ...)` 支持 poll，但 `attach` 恒传 `false` |

### 2.3 差距表

| #80 证据 | 行为 | Java 差距 |
|---|---|---|
| `faux.ts:265-272` | 按 session 模拟 cacheRead／cacheWrite；`input` 归零 | 完全没有 |
| `faux.ts:427-443` | 工具参数按 token 大小分块，partial 参数逐步长大 | 夹具手写分块；无统一分块器 |
| `faux.ts:591-665` | 提交→pending 取回→最终取回→取消；错误在流内 | 没有任何入口 |

## 3. 设计 A：可编程核心与响应步

### 3.1 pi 证据

```ts
// packages/ai/src/providers/faux.ts:512-525
const resolveResponse = async (step, context, streamOptions, requestModel) => {
	const resolved = typeof step === "function" ? await step(context, streamOptions, state, requestModel) : step;
	return withUsageEstimate(
		cloneMessage(resolved, api, provider, requestModel.id),
		context,
		streamOptions,
		promptCache,
	);
};
```

```ts
// packages/ai/src/providers/faux.ts:529-546（队列消费与耗尽）
const step = pendingResponses.shift();
state.callCount++;
...
if (!step) {
	let message = createErrorMessage(new Error("No more faux responses queued"), api, provider, requestModel.id);
	message = withUsageEstimate(message, context, streamOptions, promptCache);
	outer.push({ type: "error", reason: "error", error: message });
	outer.end(message);
	return;
}
```

```ts
// packages/ai/src/providers/faux.ts:709-732（对外的 handle）
export function fauxProvider(options = {}): FauxProviderHandle {
	const core = createFauxCore(options);
	const provider = createProvider({ id: core.provider, auth: {...}, models: core.models,
		api: { stream: core.stream, streamSimple: core.streamSimple,
			fetchDeferred: core.fetchDeferred, cancelDeferred: core.cancelDeferred } });
	return { provider, api: core.api, models: core.models, getModel: core.getModel,
		state: core.state, setResponses: core.setResponses, appendResponses: core.appendResponses,
		getPendingResponseCount: core.getPendingResponseCount };
}
```

### 3.2 Java 形状

新增公开入口，旧工厂保持不动：

```java
FauxProgrammable faux = FauxProvider.programmable(FauxProviderOptions.defaults());
Provider provider = faux.provider();
faux.setResponses(List.of(FauxResponseStep.of(FauxMessages.text("hello"))));

ChatApi api = provider.createApi(ChatApi.class, ApiOptions.defaults());
ModelInfo model = faux.getModel();                 // models[0]
Optional<ModelInfo> other = faux.getModel("faux-thinker");
```

`FauxProvider` 内部保留两种模式：programmable 实例持有一个 `FauxCore`，`FauxChatApi` 拿到 core 时把 `streamInternal` 与 deferred 方法委托给它；没有 core 时走既有事件重放。`createApi` 仍然只支持 `ChatApi`。

公开类型（包 `com.pijava.ai.provider.faux`）：

| 类型 | 形状 | 对位 pi |
|---|---|---|
| `FauxProviderOptions` | `api／provider／models／deferred／tokensPerSecond／tokenSize` | `RegisterFauxProviderOptions`（`:121-134`） |
| `FauxModelDefinition` | `id／name／reasoning／imageInput／pricing／contextWindow／maxTokens／inputLimits` | `FauxModelDefinition`（`:47-58`） |
| `FauxTokenSize` | `int min, int max` | `tokenSize`（`:129-132`） |
| `FauxDeferredOptions` | `int pendingFetches, Long pollAfterMs` | `deferred`（`:123-127`） |
| `FauxResponseStep` | sealed：`Message(AssistantMessage)`／`Factory(FauxResponseFactory)` | `FauxResponseStep`（`:114`） |
| `FauxResponseFactory` | `(TranscriptContext, ApiOptions, FauxProviderState, ModelInfo) -> AssistantMessage` | `FauxResponseFactory`（`:109-114`） |
| `FauxProviderState` | `callCount()`／`deferredFetchCount()`／`cancelledDeferred()` | `FauxProviderState`（`:101-105`） |
| `FauxMessages` | `text／thinking／toolCall／assistant` 便捷构造 | `fauxText／fauxThinking／fauxToolCall／fauxAssistantMessage`（`:61-99`） |
| `FauxProgrammable` | `provider()／models()／getModel()／setResponses()／appendResponses()／pendingResponseCount()／state()` | `FauxProviderHandle`（`:155-166`） |
| `FauxCore` | 包内实现（队列、估算、分块、deferred） | `createFauxCore`（`:459-697`） |

模型映射（pi → Java `ModelInfo`）：

| pi 字段 | Java 字段 | 默认 |
|---|---|---|
| `id` / `name` | `ModelId(provider, id)` / `displayName` | `faux-1` / `Faux Model` |
| `api` | `ModelInfo.api` | 核心 api（未指定时随机 `faux:<…>`） |
| `provider` | `ModelId.provider` | `faux` |
| `baseUrl` | `ModelInfo.baseUrl` | `http://localhost:0` |
| `reasoning` | `capabilities` 含 `ModelCapability.THINKING` | false |
| `input` | `capabilities` 含 `IMAGE_INPUT`／`TEXT` | text＋image |
| `cost` | `PricingInfo` | 全 0 |
| `contextWindow` / `maxTokens` | `maxInputTokens` / `maxOutputTokens` | 128000 / 16384 |
| `inputLimits` | `InputLimits` | null |

队列语义：`setResponses` 整体替换；`appendResponses` 追加；每次流取走队首；空队列产生一条 `error` 终局，文案逐字 `No more faux responses queued`；`callCount` 每次请求加一。

### 3.3 文件与行数

| 文件 | 预计行数 |
|---|---:|
| `provider/faux/FauxProviderOptions.java`＋`FauxModelDefinition.java`＋`FauxTokenSize.java`＋`FauxDeferredOptions.java` | ~150 |
| `provider/faux/FauxResponseStep.java`＋`FauxResponseFactory.java`＋`FauxProviderState.java` | ~120 |
| `provider/faux/FauxMessages.java` | ~90 |
| `provider/faux/FauxUsageEstimator.java` | ~200 |
| `provider/faux/FauxStreamSynthesis.java` | ~200 |
| `provider/faux/FauxCore.java` | ~320 |
| `provider/faux/FauxProgrammable.java` | ~80 |
| `provider/FauxProvider.java`（新增 programmable 入口与委托，不删旧路径） | +~20 |

每个文件都低于 500 行，满足 `FileLength` 闸门。

### 3.4 裁决

1. **新增入口而不是改造重放**
   【场景】旧 `FauxProvider` 被约 105 处测试引用，事件数、顺序、消息形状都被精确断言。
   【决定】旧入口逐字保留；新增 `FauxProvider.programmable(...)`。pi 的 faux 只有可编程核心，Java 的重放模式是额外兼容面，不参与对齐判定。
   【风险/代价】[内部，模型不可见] 两套入口会让使用者困惑；`FauxProvider` 的 javadoc 必须写清“重放＝兼容夹具、programmable＝对齐 pi”。

2. **响应步只支持同步工厂**
   【场景】pi 的工厂可以是 `async`，`resolveResponse` 会 `await`。
   【决定】Java 的工厂函数返回 `AssistantMessage`，测试可阻塞等待；语义上等价于 pi 的 await。登记 D214。
   【风险/代价】[内部，模型不可见] 若未来出现必须真异步的夹具，需要复核；当前仓库没有该需求。

3. **状态线程安全**
   【场景】Java 的流在虚拟线程上生产，测试可能并发提交。
   【决定】队列用同步块保护；deferred map 用并发 map；计数用原子整数；`cancelledDeferred` 返回快照。
   【风险/代价】[内部，模型不可见] 比 pi 的单线程模型更强的保证，不改变可观察顺序。

## 4. 设计 B：usage 估算与 prompt-cache 模拟

### 4.1 pi 证据

```ts
// packages/ai/src/providers/faux.ts:246-287
function withUsageEstimate(message, context, options, promptCache) {
	// One text per message; the whole prompt joins them with blank lines.
	const prompt = context.messages.map((message) => `${message.role}:${messageToText(message)}`);
	const promptLength = joinedLength(prompt);
	const promptTokens = Math.ceil(promptLength / 4);
	const outputTokens = estimateTokens(assistantContentToText(message.content));
	let input = promptTokens;
	let cacheRead = 0;
	let cacheWrite = 0;
	const sessionId = options?.sessionId;

	if (sessionId && options?.cacheRetention !== "none") {
		const previousPrompt = promptCache.get(sessionId);
		if (previousPrompt) {
			const cachedChars = commonPromptPrefixLength(previousPrompt, prompt);
			cacheRead = Math.ceil(cachedChars / 4);
			cacheWrite = Math.max(0, promptTokens - cacheRead);
		} else {
			cacheWrite = promptTokens;
		}
		// Every prompt token is either read from or written to the simulated cache.
		input = 0;
		promptCache.set(sessionId, prompt);
	}

	return { ...message, usage: { input, output: outputTokens, cacheRead, cacheWrite,
		totalTokens: input + outputTokens + cacheRead + cacheWrite,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
}
```

```ts
// packages/ai/src/providers/faux.ts:199-216（提示文本的逐角色渲染）
function messageToText(message) {
	if (message.role === "system") {
		return [getSystemMessageText(message),
			...(message.toolsRemoved?.map((tool) => `tool-:${JSON.stringify(tool)}`) ?? []),
			...(message.toolsAdded?.map((tool) => `tool+:${JSON.stringify(tool)}`) ?? [])]
			 .filter((part) => part.length > 0).join("\n");
	}
	if (message.role === "user") return contentToText(message.content);
	if (message.role === "assistant") return assistantContentToText(message.content);
	return toolResultToText(message);
}
```

```ts
// packages/ai/src/providers/faux.ts:218-244（拼接长度与公共前缀）
function joinedLength(messages, count = messages.length) {
	let length = count > 0 ? (count - 1) * 2 : 0;
	for (let index = 0; index < count; index++) length += messages[index]!.length;
	return length;
}
function commonPromptPrefixLength(previous, current) {
	let index = 0;
	while (index < previous.length && index < current.length && previous[index] === current[index]) index++;
	const rest = (messages) => index === messages.length ? "" :
		(index > 0 ? "\n\n" : "") + messages.slice(index).join("\n\n");
	return joinedLength(previous, index) + commonPrefixLength(rest(previous), rest(current));
}
```

### 4.2 Java 逐项映射

**提示文本**：

1. 转录来源是 `StreamRequest.transcript().messages()`（`AI/api/TranscriptContext.java:1-8`）。
2. 每条消息的文章 `role:` 前缀必须用 **pi 拼写**，不能直接用 Java 的 `role()`：
   - `Message.SystemMessage` → `system`
   - `Message.UserMessage` → `user`
   - `Message.AssistantMessage` → `assistant`
   - `Message.ToolResultMessage` → `toolResult`（Java `role()` 返回持久化方言 `tool`，见 `AI/message/Message.java:477-479`）
3. 内容渲染：
   - text 块 → `text`；
   - image 块 → `[image:<mimeType>:<base64 字符串长度>]`；
   - `UrlImageContent`（Java 扩展）→ `[image-url:<url 长度>]`（见 D215）；
   - thinking 块 → `thinking` 文本；
   - tool 块 → `<name>:<参数的 JSON>`；
   - toolResult → `toolName` 换行后接各块文本；
   - 其它 Java 扩展块（如 `DiffContent`）跳过，不套用 pi 的 `else` 分支。
4. 系统消息：
   - 先取 `MessageTexts.getSystemMessageText(Message.SystemMessage)`（content＋sections，`AI/message/MessageTexts.java:58-71`）；
   - 再追加 `tool-:<工具 JSON>` 与 `tool+:<工具 JSON>`；
   - **工具 JSON 只投影 pi 的 `Tool` 形状**：`{name, description, parameters}`，有 `constrainedSampling` 时再加该键。Java `ToolDefinition` 的 `label／promptSnippet／promptGuidelines／renderShell` 默认字段不进估算文本（见 D215）。
5. 整条提示：各段以 `\n\n` 连接；`joinedLength` 与公共前缀算法逐行照搬。Java 的 `String.length()` 与 JS 的 `String.length` 都按 UTF-16 码元计数，可直接对齐。

**usage**：

- `promptTokens = ceil(promptLength / 4)`；`outputTokens = ceil(assistantContentToText(message.content).length / 4)`。
- `sessionId` 从 `ApiOptions.extra().get("sessionId")` 读；空白视为缺席。
- `cacheRetention` 从 `extra().get("cacheRetention")` 读；`"none"` 才关闭缓存，缺席/其它值都允许（pi 只判 `!== "none"`）。
- 首次会话只写不读：`cacheWrite = promptTokens`；已有会话按公共前缀拆 `cacheRead`／`cacheWrite`；两条路径都把 `input` 归零。
- 结果写入 `StreamEvent.UsageInfo` 的完整 `Usage` 分量：
  `new StreamEvent.UsageInfo(input, output, null, new Usage(input, output, cacheRead, cacheWrite, null, null, total, Cost.zero()))`。
  这正好是 `StreamPartialBuilder.usageInfoOf` 的形状（`AI/stream/StreamPartialBuilder.java:468-473`）。

**挂载点**：

- `resolveResponse`（设计 A）在克隆身份后调用估算器；等价 pi `:512-525`。
- 估算过的消息同时用于 `Start`、每个 delta 的 partial 与终局 `done`。
- 空队列错误消息也估算（pi `:535-545`）。
- deferred 消息**不估算**，使用零 `Usage`（pi `createDeferredMessage` 的 `DEFAULT_USAGE`，`:313-325`）。

**证据限制（重要）**：pi 锚点里的 `packages/ai/test/faux-provider.test.ts:214-264` 仍按旧版 `serializeContext` 期待一行 ``tools:${JSON.stringify([tool])}``，而锚点代码在 `9e05370b2`（Mid conversation system messages）后已改为把工具折进系统消息并输出 `tool+:…`。该测试在锚点上是**过期期待**。本设计以 `faux.ts:199-216`＋`utils/text.ts` 的代码为准，测试按新形状写，不照抄这条过期断言。

### 4.3 裁决

1. **`cacheWrite` 不再计入 input**
   【场景】早期实现把 cache write 同时算进 input 和 cacheWrite，`totalTokens` 重复计数；pi 在 `f3fa8bd92` 修正为 `cacheWrite = promptTokens - cacheRead`、`input = 0`。
   【决定】逐字照搬修正后的 `:262-274`。
   【风险/代价】[内部，模型不可见] 夹具的预期数字必须按新口径写，不能沿用旧文档。

2. **工具文本使用 pi 形投影**
   【场景】pi 对整个 `Tool` 对象 `JSON.stringify`；Java 的 `ToolDefinition` 字段更多，直接序列化会让估算 token 数偏大。
   【决定】只投影 `name／description／parameters／constrainedSampling`。登记 D215。
   【风险/代价】[内部，模型不可见] 这是模拟读数，不是发给模型的请求；差异只影响测试断言数字。

## 5. 设计 C：增量工具参数分块与事件合成

### 5.1 pi 证据

```ts
// packages/ai/src/providers/faux.ts:427-443
partial.content = [...partial.content, { type: "toolCall", id: block.id, name: block.name, arguments: {} }];
stream.push({ type: "toolcall_start", contentIndex: index, partial: { ...partial } });
// Partial arguments come from the same parser real providers use, so consumers see them grow.
const toolArguments = new ToolCallArgumentsStream();
for (const chunk of splitStringByTokenSize(JSON.stringify(block.arguments), minTokenSize, maxTokenSize)) {
	await scheduleChunk(chunk, tokensPerSecond);
	...
	(partial.content[index] as ToolCall).arguments = toolArguments.append(chunk);
	stream.push({ type: "toolcall_delta", contentIndex: index, delta: chunk, partial: { ...partial } });
}
(partial.content[index] as ToolCall).arguments = block.arguments;
stream.push({ type: "toolcall_end", contentIndex: index, toolCall: block, partial: { ...partial } });
```

```ts
// packages/ai/src/providers/faux.ts:289-299
function splitStringByTokenSize(text, minTokenSize, maxTokenSize) {
	const chunks = [];
	let index = 0;
	while (index < text.length) {
		const tokenSize = minTokenSize + Math.floor(Math.random() * (maxTokenSize - minTokenSize + 1));
		const charSize = Math.max(1, tokenSize * 4);
		chunks.push(text.slice(index, index + charSize));
		index += charSize;
	}
	return chunks.length > 0 ? chunks : [""];
}
```

pi 的完整合成顺序（`streamWithDeltas`，`:358-457`）：

1. 起始 partial 的 `stopReason` 是 `pending`，内容为空；先推 `start`。
2. 逐块处理 thinking／text／toolCall；每块都是 `start → delta* → end`。
3. 内容全部走完后再判终局：
   - `pending` ⇒ 抛 `Faux response ended without a stop reason`；
   - `error`／`aborted` ⇒ 推 `error`；
   - 其余 ⇒ 推 `done`。
4. 若起始就已被中止，直接推 `error`（Java 不移植，见 D216）。

### 5.2 Java 合成器

新增 `FauxStreamSynthesis`：

```java
void emit(AssistantMessage resolved, SubmissionPublisher<StreamEvent> publisher,
          FauxChunking chunking) throws InterruptedException
```

行为：

- 起始快照：`resolved.withContent(List.of()).withStopReason(StopReason.PENDING)`；保留 usage 与身份。
- thinking 块：占位 `ThinkingContent("")`；`ThinkingStart(index, partial)`；每个 chunk 追加到快照的该块；`ThinkingDelta(index, chunk, partial)`；最后 `ThinkingEnd(index, 完整 thinking, partial)`。
- text 块：同上，用 `TextStart／TextDelta／TextEnd`。
- tool 块：
  - 参数 JSON 用 `ObjectMapper.writeValueAsString(arguments)`（紧凑分隔符，等价 `JSON.stringify`）。
  - 按 `FauxChunking` 切块；每块交给 `ToolCallArgumentsStream.append(chunk)`（`AI/stream/ToolCallArgumentsStream.java:40-46`），把返回的 partial 参数表写回当前块快照；推 `ToolCallDelta(index, id, chunk, partial)`。
  - 结束时用**权威参数**写回块，推 `ToolCallEnd(index, id, name, arguments, namespace=null, argumentsError=null, partial)`。
- 分块器：
  - `min = max(1, min(minOpt, maxOpt))`，`max = max(min, maxOpt)`，默认 3／5（pi `:24-25`、`:462-466`）。
  - 每块 `charSize = max(1, tokenSize * 4)`，`tokenSize` 在 `[min, max]` 随机（`ThreadLocalRandom`）。
  - 空文本仍产生一个空串块（pi `:298`）。
- 节奏：`tokensPerSecond` 非空且大于 0 时，每块 `Thread.sleep((estimateTokens(chunk)/tps)*1000)`；否则不睡。等价 pi 的 `scheduleChunk`（`:350-356`）。
- 终局：与 pi 同判据；`done`／`error` 由 `StreamDone.settle`／`StreamError.settle` 落定（`AI/stream/StreamEvent.java`）。
- 不用 `StreamPartialBuilder`：它从零累加，会丢掉预置消息上的 `responseId`／`errorMessage` 等字段；pi 的合成起点是已解析消息，Java 也用已解析消息做快照基底。

### 5.3 裁决

1. **逐块重写不可变快照**
   【场景】pi 直接改 `partial.content[index]`（同一对象）；Java record 不可变。
   【决定】每块 delta 用新列表＋新内容块替换，再生成新消息快照。语义一致，旧快照不会被后续 delta 改写。
   【风险/代价】[内部，模型不可见] 分配次数增加；只发生在测试替身，不影响生产。

2. **分块随机数不进断言**
   【场景】pi 用 `Math.random`，块边界不可预测。
   【决定】精确顺序测试用 `tokenSize(min=1,max=1)`；其余测试只断言“至少两块、join 后是同一 JSON”。
   【风险/代价】[内部，模型不可见] 无。

## 6. 设计 D：deferred 提交、取回与取消

### 6.1 pi 证据

```ts
// packages/ai/src/types.ts:274-283
export interface DeferredFetchOptions extends ProviderRequestOptions<Model<Api>> {
	/** Maximum provider long-poll duration in milliseconds. Defaults to 0, one status check. */
	wait?: number;
}
export type DeferredCancelOptions = ProviderRequestOptions<Model<Api>>;

// packages/ai/src/types.ts:326-331
fetchDeferred?(model: Model<Api>, handle: DeferredHandle, options?: DeferredFetchOptions): AssistantMessageEventStream;
cancelDeferred?(model: Model<Api>, handle: DeferredHandle, options?: DeferredCancelOptions): Promise<void>;
```

```ts
// packages/ai/src/providers/faux.ts:548-573（提交侧）
if (streamOptions?.deferred) {
	const handle: DeferredHandle = {
		provider: requestModel.provider, modelId: requestModel.id, api: requestModel.api,
		id: randomId("deferred"),
		...(options.deferred?.pollAfterMs !== undefined ? { pollAfterMs: options.deferred.pollAfterMs } : {}),
	};
	deferredResponses.set(handle.id, { handle, step, context, options: streamOptions,
		model: requestModel, pendingFetches: Math.max(0, Math.floor(options.deferred?.pendingFetches ?? 0)),
		cancelled: false });
	await streamWithDeltas(outer, createDeferredMessage(requestModel, handle), ...);
	return;
}
```

```ts
// packages/ai/src/providers/faux.ts:599-655（取回侧，节选）
await fetchOptions?.onResponse?.({ status: 200, headers: {} }, requestModel);
const entry = deferredResponses.get(handle.id);
if (!entry || entry.handle.provider !== handle.provider || entry.handle.modelId !== handle.modelId
	|| entry.handle.api !== handle.api) throw new Error(`Unknown faux deferred response: ${handle.id}`);
if (entry.cancelled) throw new Error(`Faux deferred response was cancelled: ${handle.id}`);
if (entry.pendingFetches > 0) {
	entry.pendingFetches--;
	await streamWithDeltas(outer, createDeferredMessage(requestModel, entry.handle), ...);
	return;
}
if (!entry.final) {
	const { deferred: _deferred, signal: _submissionSignal, onResponse: _submissionOnResponse,
		...submissionOptions } = entry.options ?? {};
	try { entry.final = await resolveResponse(entry.step, entry.context, submissionOptions, entry.model); }
	catch (error) { entry.final = createErrorMessage(error, api, provider, entry.model.id); }
}
await streamWithDeltas(outer, entry.final, ...);
```

```ts
// packages/ai/src/providers/faux.ts:657-666（取消侧）
const cancelDeferred = async (requestModel, handle, cancelOptions) => {
	state.cancelledDeferred.push(structuredClone(handle));
	const entry = deferredResponses.get(handle.id);
	if (entry) entry.cancelled = true;
	await cancelOptions?.onResponse?.({ status: 200, headers: {} }, requestModel);
};
```

### 6.2 先补消息形状：流式 `AssistantMessage` 加 `deferred`

【场景】pi 的 `AssistantMessage` 带 `deferred?: DeferredHandle`（`types.ts:600`）。Java 的流式快照记录（`AI/message/AssistantMessage.java:70-86`）没有这个组件，`Message.AssistantMessage.fromPartial`（`AI/message/Message.java:286-301`）又把持久化消息的 `deferred` 写死为 `null`。因此即使 provider 产出 handle，调用方也拿不到。

【决定】给流式记录追加 `DeferredHandle deferred` 组件（放在末尾，保留旧构造器兼容），并逐点传播：

| 重建点 | 位置 | 动作 |
|---|---|---|
| 规范构造器 | `AssistantMessage.java:70-86` | 追加第 16 组件 |
| 14 参兼容构造 | `AssistantMessage.java:94-100` | 保留，委托 `deferred = null` |
| 4 参／10 参兼容构造 | `:103-123` | 保留 |
| `empty()` | `:126-133` | 传 null |
| 11 个 wither | `:136-229` | 每个都传 `deferred` |
| `StreamPartialBuilder.snapshot()` | `StreamPartialBuilder.java:106-117` | 传当前字段；新增 `noteDeferred(DeferredHandle)` |
| `Message.AssistantMessage.fromPartial` | `Message.java:286-301` | 改为 `partial.deferred()` |

线格式影响：

- `JsonEventMapper` 对 `StreamEvent` 整体忽略 `partial`（`coding-agent/mode/JsonEventMapper.java:49-53`），不会多键。
- Web 的 `WebWireJson.assistantNode` 只写 `content` 与 `responseId`（`web/WebWireJson.java:120-129`），不会多键。
- 持久化消息 `Message.AssistantMessage` 本来就有 `deferred`，JSONL 形状不变。

【风险/代价】[内部，模型不可见] record 加组件最容易“构造点改了、wither 漏了”，表现为 handle 在某条路径静默丢失。实现时必须用 `grep -rn "new AssistantMessage("` 与全部 wither 逐点核对，并加一条“提交→流终局→`send()`→handle 非空”的纵贯测试。

### 6.3 请求选项

| pi 选项 | Java 载体 | 规则 |
|---|---|---|
| `SimpleStreamOptions.deferred`（`types.ts:382`） | `ApiOptions.extra().get("deferred")` | `Boolean.TRUE` 或任意 `Map` ⇒ 开启；其它值（含 false）⇒ 关闭 |
| `DeferredFetchOptions.onResponse` | `ApiOptions.extra().get("onResponse")` | 复用 `ProviderHooks.ResponseHook`（`AI/api/ProviderHooks.java:62-65`） |
| `DeferredFetchOptions.wait` | 不落载体 | pi faux 不读；Codex 在 R2 外 |
| `signal` | 无 | 见 D216 |

提交时使用的 `ApiOptions` 原样存储；最终解析前按 pi 剥离 `deferred` 与 `onResponse` 两个 extra 键。新增 `ApiOptions.withExtra(ApiOptions, Map<String, Object>)` 作为唯一重建入口，避免手写九组件时丢字段（同 docs/19 的 15 重建点教训）。

### 6.4 `StreamApi` 新入口

```java
public interface StreamApi {

    Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options);
    StreamIterator streamBlocking(StreamRequest request, ApiOptions options);

    /** 取回 deferred 响应的事件流；不支持的实现抛 UnsupportedOperationException。 */
    default Flow.Publisher<StreamEvent> fetchDeferred(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        throw new UnsupportedOperationException("Deferred responses are not supported");
    }

    /** 同步消费 fetchDeferred 的便捷形态。 */
    default StreamIterator fetchDeferredBlocking(
            ModelInfo model, DeferredHandle handle, ApiOptions options) { ... }

    /** 尽力取消；不支持的实现抛 UnsupportedOperationException。 */
    default void cancelDeferred(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        throw new UnsupportedOperationException("Deferred responses are not supported");
    }
}
```

- `fetchDeferredBlocking` 的默认实现：订阅 `fetchDeferred`，把事件缓冲进 `LinkedBlockingQueue`，等待 `onComplete／onError` 后返回 `StreamIterator`。没有任何终局事件时合成一条 error（与 `AbstractChatApi.streamBlocking` 的活性守卫同口径）。
- 不支持的 provider 保持 pi “方法不存在”的语义：默认抛 `UnsupportedOperationException`。

### 6.5 Faux 的 deferred 实现

字段（`FauxCore`）：

```java
Map<String, DeferredEntry> deferredResponses; // id → { handle, step, transcript, options, model,
                                              //        pendingFetches, cancelled, final }
```

**提交**：

1. 从队列取走一个响应步（与普通请求同样消耗）。
2. 生成 handle：`provider／modelId／api` 取自请求模型；`id = "deferred:<ts>:<rand>"`；`pollAfterMs` 仅在有配置时写入；`expiresAt` 与 `data` 为 null。
3. 存入 map；`pendingFetches = max(0, floor(options.pendingFetches))`；`cancelled = false`。
4. 合成一条空内容、`stopReason = DEFERRED`、`usage = 零 Usage`、带 handle 的消息；事件序列为 `start` → `done`（pi 同形）。

**取回**：

1. 先触发 `onResponse(200, {}, requestModel)`（含未知/已取消路径，pi 同形）。
2. 按 id 找 entry，并校验 `provider／modelId／api` 三字段；不匹配 ⇒ 错误事件 `Unknown faux deferred response: <id>`。
3. `cancelled` ⇒ 错误事件 `Faux deferred response was cancelled: <id>`。
4. `pendingFetches > 0` ⇒ 减一，再合成一条 deferred 消息（`start` → `done`）。
5. 否则解析最终消息并缓存：
   - 用提交时的 transcript／model／step／剥离后的 options 调 `resolveResponse`（含 usage 估算）；
   - 工厂抛异常 ⇒ 缓存一条 `stopReason = ERROR`、文本取自异常的错误消息（**不**抛到调用方，错误在流内）；
   - 合成最终事件流。
6. `deferredFetchCount` 每次取回加一（含失败的取回）。

**取消**：

1. 把 handle 放入 `cancelledDeferred` 快照列表。
2. 若 entry 存在，置 `cancelled = true`。
3. 触发 `onResponse(200, {}, requestModel)`。
4. 返回 `void`（pi 的 Promise 在 Java 同步完成）。

**可选性**：`pendingFetches` 默认 0；`pollAfterMs` 默认不写；`data` 恒 null。D212 的 `Map` 承载差异不会在本包被触发，但集成测试需覆盖 handle 的逐字段往返。

### 6.6 poll 遥测（关闭 D203）

【场景】`AiRequestTelemetry.observe(..., poll, ...)` 已支持 `pi.ai.poll`（`AI/protocol/AiRequestTelemetry.java:100-112`），但 `attach` 只在 `AbstractChatApi.stream` 里以 `poll=false` 调用（`:105-107`）。D203 记录“无生产入口”。

【决定】

1. 新增 `AiRequestTelemetry.attachPoll(publisher, ModelInfo, ApiOptions, apiName)`：与 `attach` 同形，但 `poll=true`，推理级别传空（`DeferredFetchOptions` 在 pi 也没有 `reasoning`）。
2. `AbstractChatApi` 暴露 `protected static void attachPollTelemetry(...)` 给 faux 调用（`AiRequestTelemetry` 是 `protocol` 包私有类，`provider` 包不能直接引用）。
3. `FauxChatApi.fetchDeferred` 在生产者启动前挂观察者，并保证只挂一次（`AtomicBoolean`，与 `stream` 同法）。

【风险/代价】[内部，模型不可见] poll span 没有 `reasoning` 属性；pi 的 deferred fetch 选项也没有该字段，不登记窄差。观察者跟随生产端而非订阅者，取消订阅不影响收口，与 docs/13 的既有裁决一致。

### 6.7 裁决

1. **handle 挂在消息上**
   【场景】pi 的调用方通过 `message.deferred` 拿 handle；Java 的持久化消息有字段，流式快照没有。
   【决定】补组件并让 `fromPartial` 传递；不做“事件旁路字段”。
   【风险/代价】[内部，模型不可见] 见 §6.2 的重建点清单；编译能抓住漏传，但漏传会静默变 null，必须有纵贯测试。

2. **Java 不新增 signal**
   【场景】pi 的 deferred 取回与提交流都带 `AbortSignal`，中止时产出 `aborted` 终局。
   【决定】Java 不新增 signal 通道；aborted 终局仍可由响应消息显式声明（`stopReason = ABORTED` 时合成 error）。登记 D216。
   【风险/代价】[内部，模型不可见] 消费端取消只能停订阅，不能中断已启动的 faux 生产者；与 Java 现有所有车道的取消模型一致。

3. **错误在流内**
   【场景】pi 的取回失败（未知、已取消、工厂抛错）都转成 `stopReason = error` 的消息，不 reject 调用方。
   【决定】Java 同样在流内产出 `StreamError`；`cancelDeferred` 本身的异常（无 entry）不抛，只记录状态。
   【风险/代价】[内部，模型不可见] 调用方必须消费事件流才能看到错误，不能只看“有没有抛异常”。

## 7. 兼容与回归

### 7.1 旧重放模式

- 保留 `FauxProvider(String, List<StreamEvent>, long)`、`sequence(...)`、`text(...)`、`toolCall(...)`、`error(...)`。
- 保留 `FauxChatApi` 的 `resolvesRequestOptions() = false`、`apiName() = "faux"`、逐事件提交与中断行为。
- `nextResponse()` 的“最后一条重复使用”语义不动。这是 Java 兼容夹具，不按 pi 的空队列报错改写。

### 7.2 编译与重建点

| 改动 | 必查点 |
|---|---|
| `AssistantMessage` 加组件 | 11 个 wither；`empty`；`StreamPartialBuilder.snapshot`；`Message.fromPartial`；任何外部 15 参构造 |
| `StreamApi` 加默认方法 | 无需改既有实现；`ChatApi` 自动继承 |
| `ApiOptions.withExtra` | 新增唯一重建入口；不手写九组件 |
| `FauxProvider` 新增入口 | 旧构造器签名不动 |

### 7.3 规范检查

- 文件 ≤ 500 行；新增文件按 §3.3 拆分。
- 不加 `@SuppressWarnings`。
- `mvn checkstyle:check` 零违规。
- 不残留 `System.out.println`。

## 8. 测试计划（RED-first）

### 8.1 新增测试类

| 测试类 | 覆盖 | 预计用例 |
|---|---|---:|
| `FauxProgrammableTest` | 模型选择、身份改写、队列顺序／耗尽、set／append、工厂抛错、pending 终局 | ~10 |
| `FauxUsageEstimateTest` | 提示 token、输出 token、缓存首写、前缀读／写、首差截断、`cacheRetention=none`、多 session 隔离 | ~7 |
| `FauxStreamSynthesisTest` | 固定块大小的精确事件顺序、thinking／text／tool 三块、多 tool call、error／aborted 终局、pending 抛错 | ~8 |
| `FauxDeferredTest` | 提交事件形状、pending 取回、最终取回、计数器、取消、取回失败在流内、未知 handle、D212 生产→取回→重建 | ~9 |
| `FauxPollTelemetryTest` | `pi.ai.poll` span、attach=false 的 token 不计入、终局属性 | ~3 |

### 8.2 关键断言

1. **usage**：按 §4.2 的文本算法构造转录，断言 `input`／`output`／`cacheRead`／`cacheWrite`／`totalTokens`。不能沿用 pi 过期测试的 `tools:` 期望。
2. **缓存**：
   - 同一 session 第一次：`cacheWrite > 0`，`cacheRead = 0`；
   - 同一 session 扩展提示：`cacheRead` 为公共前缀的 ceil(字符/4)，`cacheWrite = promptTokens - cacheRead`；
   - 首个差异之后不再计入；
   - `cacheRetention = "none"`：两者均 0。
3. **分块**：`tokenSize(min=1,max=1)` 时事件类型序列逐项相等；tool delta 的拼接结果可 `JSON.parse` 回原参数。
4. **deferred**：
   - 提交：事件类型 `["start","done"]`，`stopReason = deferred`，`content = []`，handle 四字段与配置的 `pollAfterMs`；
   - 取回 1：仍为 `deferred`；
   - 取回 2：`stopReason = stop`，内容为预置响应，usage 非零；
   - `state.callCount = 1`、`deferredFetchCount = 2`；
   - 取消后取回：错误文本含 `was cancelled`；
   - 工厂拒绝：错误文本为工厂异常文本，流内可见。
5. **D212 纵贯**：`ChatApi.send(..., extra["deferred"])` 返回持久化 `Message.AssistantMessage`，其 `deferred()` 非空；用该 handle 走 `fetchDeferredBlocking` 取回完整消息；断言内容、usage、身份、handle 往返。
6. **poll 遥测**：`ApiOptions.telemetry` 非空时出现 `pi.ai.poll`；取回 pending（`deferred`）时不记 token 计数；最终取回记账。观察者不读正文（content-free）。

### 8.3 回归

- `mvn test -pl pi-java-ai -am`：AI 1,811＋telemetry 80 基线不得回退；新增测试全绿。
- `mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-web -am`：确认 `AssistantMessage` 加组件没有破坏既有夹具与线格式。
- 收口 `mvn clean verify`：0 失败／0 错误，Checkstyle 0，SpotBugs 0。

## 9. 实施顺序与提交粒度

| 步 | 内容 | 预计提交 |
|---|---|---:|
| 1 | `AssistantMessage.deferred` 组件＋全部 wither／builder／`fromPartial`＋编译修复＋纵贯最小测试 | 1 |
| 2 | `StreamApi` 三个默认方法＋`ApiOptions.withExtra`＋不支持路径测试 | 1 |
| 3 | `FauxUsageEstimator`（RED→GREEN）＋缓存测试 | 1 |
| 4 | `FauxStreamSynthesis`＋分块测试 | 1 |
| 5 | `FauxProviderOptions`／模型／响应步／状态／`FauxMessages`／`FauxCore` 的普通流 | 1–2 |
| 6 | deferred 提交／取回／取消＋`AiRequestTelemetry.attachPoll`＋D212 纵贯 | 1–2 |
| 7 | docs/04、docs/map/02、docs/06、docs/08 回填＋本稿 §10 | 1 |

每步先测后码；单步 200–500 行。

## 10. 风险与裁决汇总

1. **旧重放模式不回退**
   【场景】约 105 处测试依赖精确事件序列。
   【决定】旧路径逐字保留，新能力全部走 `programmable` 入口。
   【风险/代价】[内部，模型不可见] 两套入口；以 javadoc 区分用途。

2. **partial 加组件的静默丢失**
   【场景】record 加组件后，构造点能编译，但漏传的 wither 会把 handle 变 null。
   【决定】§6.2 的重建点清单逐一核对；纵贯测试钉住 `提交→send()→handle`。
   【风险/代价】[内部，模型不可见] 这是本包最高的回归风险，必须单独评审一次 diff。

3. **D214：同步响应工厂**
   【场景】pi 允许 Promise 工厂；Java 只提供同步函数。
   【决定】同步阻塞在虚拟线程上等价 await；需要真异步时再复核。
   【风险/代价】[内部，模型不可见] 边缘场景，不改变可观察结果。

4. **D215：估算文本的工具投影**
   【场景】Java `ToolDefinition` 字段多于 pi `Tool`。
   【决定】只投影 `name／description／parameters／constrainedSampling`；URL 图片用稳定占位。
   【风险/代价】[内部，模型不可见] 只影响测试断言的 token 数字，不影响任何请求体。

5. **D216：无 AbortSignal**
   【场景】pi faux 有 signal 中止分支（起始中止与块间中止）。
   【决定】本包不移植；`aborted` 终局仍可由响应消息显式产生。
   【风险/代价】[内部，模型不可见] 与 Java 全仓的取消模型一致，非 faux 特有。

6. **pi 测试过期**
   【场景】`faux-provider.test.ts:214-264` 的 `tools:` 期待早于 `9e05370b2` 的转录改造，在锚点上与代码不一致。
   【决定】以代码为准；Java 测试按 `tool+:` 形状写，并在 docs/04 记录该证据限制。
   【风险/代价】[内部，模型不可见] 若后续换锚点修了测试，需回头复核数字。

7. **deferred 只由 faux 实现**
   【场景】`StreamApi` 默认方法抛不支持；真实车道没有 deferred。
   【决定】与 pi 的可选方法一致；R2 的 Codex 不属于本包。
   【风险/代价】[内部，模型不可见] 如果未来接入真实 deferred provider，需要新设计包。

## 11. 闭环时需要回填的位置

1. `docs/04-open-items-register.md`：
   - B267 行改为“Faux 余量已按 docs/20 闭环”并说明 L1 100%；
   - D203 标为已关闭（poll 已有生产入口）；
   - D212 补“集成测试已补”；
   - 新增 D214／D215／D216；
   - 更新末尾“下一新文档号”（L1 完成后指向 L2 mcp 的首个设计号）。
2. `docs/map/02-ai.md`：
   - #80 判为对齐，证据列改为 `AI/provider/faux/`（核心、估算、合成、deferred）＋`AI/provider/FauxProvider.java`＋`AI/api/StreamApi.java`＋`AI/message/AssistantMessage.java`；
   - 头部状态与 §「差异分类与开放工作包」更新 B267 已闭环；
   - 重算汇总：125/125、232/232、权重 1 53/53、横切 51/51。
3. `docs/06-current-alignment-plan.md`：
   - L1 行改为“已闭环”，账面 232/232＝100%；
   - 下一步改为 L2（mcp）重测与设计。
4. `docs/08-pi-rebaseline-master-plan.md`：§9 进度表与 §11 下一步同步。
5. 本稿 §13 写实施记录、实跑测试数与最终账面。

## 12. 不在本稿但需保持的事实

- `DeferredHandle.data` 仍是 `Map<String,Object>`（D212）；本包不扩展顶层数组／标量。
- `StreamApi` 的 `stream`／`streamBlocking` 惰性契约不变：未订阅的流不启动生产。
- `ApiOptions` 的组件顺序与既有构造器不变；`withExtra` 只是新增重建入口。
- Faux 仍属于【内部，模型不可见】测试设施，不进入远处 provider 的运行时路径。

## 13. 实施与闭环记录

### 13.1 提交清单

| 提交 | 内容 |
|---|---|
| `3ab65aa4` | `AssistantMessage` 追加 `deferred` 组件（16 参规范构造＋15 参兼容构造＋11 个 wither＋`empty`）、`StreamPartialBuilder.snapshot`/`noteDeferred`、`Message.fromPartial` 传播、`StreamApi` deferred 三入口默认方法、`ApiOptions.withExtra`；测试 `AssistantMessageDeferredTest`（4）与 `StreamApiDeferredDefaultsTest`（5） |
| `4a2295ed` | `AI/provider/faux/` 可编程核心（options／模型定义／响应步／工厂／状态／`FauxMessages`／`FauxUsageEstimator`／`FauxStreamSynthesis`／`FauxChunking`／`FauxCore`／`FauxProgrammable`／`FauxChatApi`）、`FauxProvider.programmable` 入口、`AbstractChatApi` 选项重载与 `attachPollTelemetry`、`AiRequestTelemetry.attachPoll`；测试 `FauxUsageEstimateTest`（8）、`FauxStreamSynthesisTest`（9）、`FauxProgrammableTest`（11）、`FauxDeferredTest`（8）、`FauxPollTelemetryTest`（3） |
| 本 docs 提交 | 本稿 §13 与 docs/04／docs/map/02／docs/06／docs/08 回填 |

### 13.2 RED 证据

- `AssistantMessageDeferredTest` 先于组件：首次 `mvn test` 在编译期红，报「找不到适合的构造器」（16 参）与 `deferred()`／`withDeferred()` 缺失。
- `StreamApiDeferredDefaultsTest` 先于默认方法：首次编译期红（`fetchDeferred`／`cancelDeferred` 不存在）。实现后 5 测试全绿。
- `FauxUsageEstimateTest` 先于估算器：首次编译期红（`FauxUsageEstimator` 缺失；`estimateTokens` 收 `long` 后消除一处 `long→int` 损失编译错）。
- `FauxStreamSynthesisTest` 先于合成器：首次编译期红；实现后首次运行 1 failure＋2 error，全部来自夹具对 `SubmissionPublisher` 异步交付的读取窗口（等待终局后修复夹具），合成行为本身零改。
- `FauxProgrammableTest`／`FauxDeferredTest`／`FauxPollTelemetryTest` 先于核心：首次编译期红（`FauxProgrammable`／`FauxDeferredOptions` 缺失）；实现后全绿。

### 13.3 变异探针（各跑一次，确认测试有牙齿后回滚）

| 变异 | 预期红 | 实测 |
|---|---|---|
| `Message.fromPartial` 的 `deferred` 改成 `null` | handle 在持久化投影处丢失 | `AssistantMessageDeferredTest#persistedProjectionCopiesTheHandle` 与 `FauxDeferredTest#d212ProductionToFetchToRebuildRoundTrips` 各 1 failure |
| `cacheWrite` 改回 `promptTokens`（忽略 `cacheRead`） | 扩展提示的拆分会变 | `FauxUsageEstimateTest#anExtendedPromptReadsTheCommonPrefixAndWritesTheRest` 与 `#theFirstDifferingMessageLimitsTheCommonPrefix` 各 1 failure |

两次变异均已 `git checkout` 还原，工作树无残留。

### 13.4 实跑验证

- `mvn test -pl pi-java-ai -am`：**2,081 测试／0 失败／0 错误／4 跳过**（本包新增 48：4＋5＋8＋9＋11＋8＋3）。
- `mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-web -am`：BUILD SUCCESS。
- `mvn clean verify`：**6,150 测试／0 失败／0 错误／33 跳过**（17:02），Checkstyle 0、SpotBugs 0、BUILD SUCCESS。
  - 备注：第一次全量 verify 在既有 `LaneRecordsConcurrencyTest`（agent-core 并发夹具）上出现 1 次偶发 NPE；该测试单跑 13 次全绿，第二次全量 verify 全绿，本包未改动该测试与其被测代码。

### 13.5 最终账面复算

| 口径 | 结果 |
|---|---:|
| 计分单位 | **125**：对齐 125／存疑 0／缺失 0 |
| 加权 | **232.0/232＝100%** |
| 未加权 | **125/125＝100%** |
| 权重 1 | **53/53＝100%** |
| 权重 2 | **74/74＝100%**（原汇总行 72/74 与「仅 #80 缺失」矛盾，按逐行复算更正） |
| 权重 3 | **105/105＝100%** |
| 横切域 | **51/51＝100%** |

#80 判为对齐；B267 全闭环；D203 关闭，D212 补纵贯测试，D214／D215／D216 新增。下一新文档号 **docs/21**（L2 mcp 首个设计）。

