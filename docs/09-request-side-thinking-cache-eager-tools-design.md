# 09 — 请求侧对齐：思考级别跨车道 / Responses 缓存门 / Anthropic 工具流式

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f`（`docs/map/check-drift.sh` 实测无漂移）。
> **状态：✅ 已闭环（2026-10-04）** —— 用户裁决「审核通过，J1–J6 全按建议」；实施记录见 **§12**（先红 2/3/4、探针 M1–M5 全精准、全 reactor 14/14 SUCCESS）。范围收窄为 **responses/azure 思考块 ＋ B104 ＋ B90**；**google/mistral 的思考配置另立包**（新登记 **B155**）。
> 覆盖台账三条：**B142**（8 车道 clamp）／**B104**（Responses 缓存手搓近似）／**B90**（Anthropic `eager_input_streaming`）。

---

## 1. 背景与范围

三条都在「**现有车道的请求侧线格**」这一个面上 —— 不改数据模型、不改流解析、不改宿主：

| 台账 | 登记时的措辞 | 本包实测结论 |
|---|---|---|
| **B142** | 「8 车道发送前思考级别 clamp 疑似只落 1 车道」 | ⚠️ **措辞低估**：真缺口是「4 条车道的思考级别**生产不可达**」（详见 §3.1） |
| **B104** | 「Responses 车道的 cache 是手搓近似」 | ✅ 属实：缺 `prompt_cache_options` ＋ 两个 compat 门（§3.2） |
| **B90** | 「Anthropic 工具声明缺 `eager_input_streaming`」 | ✅ 属实：字段与 beta 头两样都没有（§3.3） |

**排除**：新增 provider／新增 wire（`bedrock`/`vertex`/`codex`/`cloudflare`）；思考级别的**用户面**（`/thinking` 命令、`ThinkingLevelMap` 的生产者 —— 那属宿主，已闭环于 H5）。

---

## 2. 设计期实测（pi 侧逐字码）

### 2.1 思考级别的两个函数（pi `models.ts`）

`packages/ai/src/models.ts:922`：

```ts
const EXTENDED_THINKING_LEVELS: ModelThinkingLevel[] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
```

`packages/ai/src/models.ts:924-933`：

```ts
export function getSupportedThinkingLevels<TApi extends Api>(model: Model<TApi>): ModelThinkingLevel[] {
	if (!model.reasoning) return ["off"];

	return EXTENDED_THINKING_LEVELS.filter((level) => {
		const mapped = model.thinkingLevelMap?.[level];
		if (mapped === null) return false;
		if (level === "xhigh" || level === "max") return mapped !== undefined;
		return true;
	});
}
```

`packages/ai/src/models.ts:935-955`：

```ts
export function clampThinkingLevel<TApi extends Api>(
	model: Model<TApi>,
	level: ModelThinkingLevel,
): ModelThinkingLevel {
	const availableLevels = getSupportedThinkingLevels(model);
	if (availableLevels.includes(level)) return level;

	const requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level);
	if (requestedIndex === -1) return availableLevels[0] ?? "off";

	for (let i = requestedIndex; i < EXTENDED_THINKING_LEVELS.length; i++) {
		const candidate = EXTENDED_THINKING_LEVELS[i];
		if (availableLevels.includes(candidate)) return candidate;
	}
	for (let i = requestedIndex - 1; i >= 0; i--) {
		const candidate = EXTENDED_THINKING_LEVELS[i];
		if (availableLevels.includes(candidate)) return candidate;
	}
	return availableLevels[0] ?? "off";
}
```

### 2.2 `clampThinkingLevel` 的**全部**调用点（8 条车道）

| # | pi 车道 | 行 | 逐字码 |
|---|---|---|---|
| 1 | `api/openai-responses.ts` | `:230` | `const clampedReasoning = options?.reasoning ? clampThinkingLevel(model, options.reasoning) : undefined;` |
| 2 | `api/azure-openai-responses.ts` | `:179` | 同上（逐字相同） |
| 3 | `api/openai-completions.ts` | `:741` | 同上（逐字相同） |
| 4 | `api/mistral-conversations.ts` | `:200` | 同上（逐字相同） |
| 5 | `api/openai-codex-responses.ts` | `:514` | 同上（逐字相同） |
| 6 | `api/google-generative-ai.ts` | `:322` | `const clampedReasoning = clampThinkingLevel(model, options.reasoning);`（无 `options?.reasoning` 守卫 —— 前面 `:318` 已提前返回） |
| 7 | `api/google-vertex.ts` | `:328` | 同 6（逐字相同） |
| 8 | `api/google-shared.ts` | `:105` | `const fallback = clampThinkingLevel(model, "off");`（**关**思考时的回退档） |

**Anthropic 不在这张表里**。它的级别走另一条路 —— `api/anthropic-messages.ts:838-856`：

```ts
function mapThinkingLevelToEffort(
	model: Model<"anthropic-messages">,
	level: SimpleStreamOptions["reasoning"],
): AnthropicEffort {
	const mapped = level ? model.thinkingLevelMap?.[level] : undefined;
	if (typeof mapped === "string") return mapped as AnthropicEffort;

	switch (level) {
		case "minimal":
		case "low":
			return "low";
		case "medium":
			return "medium";
		case "high":
			return "high";
		default:
			return "high";      // xhigh / max 也落这里
	}
}
```

⇒ **pi 自己也不是「8 条车道都夹」**：Anthropic 是「目录映射优先、否则 switch 兜底 `high`」，**不夹**。台账的「8 车道」口径要把 Anthropic 排除、把 google-vertex／codex 计入。

### 2.3 Responses 缓存（pi `api/openai-responses.ts`）

`:69-83` compat 默认值（与本包相关的两项）：

```ts
		supportsLongCacheRetention: model.compat?.supportsLongCacheRetention ?? true,
		...
		supportsExplicitPromptCacheMode: model.compat?.supportsExplicitPromptCacheMode ?? false,
```

`:83-99`：

```ts
function getPromptCacheRetention(
	compat: Required<OpenAIResponsesCompat>,
	cacheRetention: CacheRetention,
): "24h" | undefined {
	return cacheRetention === "long" && compat.supportsLongCacheRetention && !compat.supportsExplicitPromptCacheMode
		? "24h"
		: undefined;
}

function getPromptCacheOptions(
	compat: Required<OpenAIResponsesCompat>,
	cacheRetention: CacheRetention,
): { mode?: "explicit"; ttl?: "30m" } | undefined {
	if (!compat.supportsExplicitPromptCacheMode) return undefined;
	if (cacheRetention === "none") return { mode: "explicit" };
	if (cacheRetention === "long" && compat.supportsLongCacheRetention) return { ttl: "30m" };
	return undefined;
}
```

`:310-318` 三个键的落点：

```ts
	const params: ResponseCreateParamsStreaming & {
		prompt_cache_options?: { mode?: "explicit"; ttl?: "30m" };
	} = {
		model: model.id,
		input: messages,
		stream: true,
		prompt_cache_key: cacheRetention === "none" ? undefined : clampOpenAIPromptCacheKey(options?.sessionId),
		prompt_cache_retention: getPromptCacheRetention(compat, cacheRetention),
		prompt_cache_options: getPromptCacheOptions(compat, cacheRetention),
		store: false,
	};
```

⚠️ **`prompt_cache_key` 与 `none`**：pi 在 `none` 时**也不发 key**（三元的第一支）。本仓 `applyCacheRetention` 的 `NONE` 分支同样不发 —— 这部分**已对齐**。

### 2.4 Anthropic 工具流式（pi `api/anthropic-messages.ts`）

`:181` 常量：

```ts
const FINE_GRAINED_TOOL_STREAMING_BETA = "fine-grained-tool-streaming-2025-05-14";
```

`:207-215` compat 默认（`getAnthropicCompat` 的解构项）：

```ts
		supportsEagerToolInputStreaming: model.compat?.supportsEagerToolInputStreaming ?? true,
```

`:1449-1454`：

```ts
function shouldUseFineGrainedToolStreamingBeta(
	model: Model<"anthropic-messages">,
	context: TranscriptContext,
): boolean {
	return getCurrentTools(context.messages).length > 0 && !getAnthropicCompat(model).supportsEagerToolInputStreaming;
}
```

`:1483-1491` 工具项（`convertTools` 内）：

```ts
		return {
			name: isOAuthToken ? toClaudeCodeName(tool.name) : tool.name,
			description: tool.description,
			...(supportsEagerToolInputStreaming ? { eager_input_streaming: true } : {}),
			...(strict === true ? { strict: true } : {}),
			input_schema: inputSchema,
			...(cacheControl && index === tools.length - 1 ? { cache_control: cacheControl } : {}),
		};
```

`:1016-1018` beta 头组装（`getBetaFeatures` 内）：

```ts
	const features: NonNullable<MessageCreateParamsStreaming["betas"]> = [];
	if (isOAuthToken) features.push("claude-code-20250219", "oauth-2025-04-20");
	if (shouldUseFineGrainedToolStreamingBeta(model, context)) features.push(FINE_GRAINED_TOOL_STREAMING_BETA);
```

⚠️ `getBetaFeatures` 开头还有一段 **`anthropic-beta` 头的显式覆盖**（`:998-1015`）：模型或选项头里写了该头 ⇒ 用它的值；写成 `null` ⇒ 直接返回空数组（把 beta 全关）。本仓今天**没有**这段（`AnthropicRequestBuilder:134-146` 只无条件拼三条）。

---

## 3. 设计期实测（pi-java 侧现状 · `file:line`）

### 3.1 B142：思考级别的生产链**断在 4 条车道上**

**已经有的**（勿重复实现）：

- `catalog/ModelThinkingLevels.java` —— `supported`/`clamp` 是 pi `models.ts:924-955` 的**逐字移植**（含 `xhigh`/`max` 的 opt-in 判据、先上后下的夹取顺序）。
- `api/SimpleOptions.java:195-201` `clampedReasoningEffort` —— 包一层，返回 `Optional<ThinkingLevel>`（空 ≙ `off` 或 `undefined`）。

**唯一的生产调用者**：`protocol/ThinkingFormatWriter.java:80`

```java
        var level = SimpleOptions.clampedReasoningEffort(model, request.reasoning());
```

⇒ **只有 completions 车道**（`OpenAICompletionsMessageConverter:253-260` 调 `ThinkingFormatWriter.apply`）。

**四条车道的真实状态**：

| 车道 | 现状 | 证据 |
|---|---|---|
| responses ＋ azure | 读 `extra["reasoningEffort"]`，**该键零生产者** ⇒ 生产上永不发 `reasoning.effort` | `protocol/ResponsesMessageConverter.java:151` `effortString(ropts.reasoningEffort())`；`protocol/ResponsesOptions.java:48` `extra.get("reasoningEffort")`；`DefaultProviders.requestExtra:208-214` **只放** `cacheRetention`/`sessionId` |
| google | **一行不发**（`request.reasoning()` 从未被读） | `protocol/GoogleGenerativeAiApi.java` 全文件 `grep reasoning` 只命中**响应侧**采集 |
| mistral | **一行不发**（同上） | `protocol/MistralConversationsApi.java` 同样只命中响应侧注释 |
| anthropic | 与 pi 同形（**不夹**，走 `map` ＋ switch 兜底 `"high"`）⇒ **已对齐** | `protocol/AnthropicThinking.java:52-64` `mapLevelToEffort` 逐字对齐 pi `:838-856` |

**另有两条平行路径不夹取**（同一 `effortString` 形状）：

- `protocol/ResponsesMessageConverter.java:490-503` `effortString` —— 硬编码 switch（`XHigh`/`Max` 都落 `"high"`），注释自认「这条平行路径不在本包范围」。
- `api/SimpleOptions.clampedReasoningEffort` 的上游 `request.reasoning()` 是 `StreamRequest` 的组件（`api/StreamRequest.java:51`），pi 的 `SimpleStreamOptions.reasoning` 逐字对应 ⇒ **通道是通的，缺的是消费点**。

### 3.2 B104：Responses 缓存

`protocol/ResponsesMessageConverter.java:505-521`：

```java
    private static void applyCacheRetention(ResponseCreateParams.Builder builder,
                                            ResponsesOptions ropts) {
        switch (ropts.cacheRetention()) {
            case NONE -> {
                // prompt_cache_key/retention omitted — no implicit prompt caching.
            }
            case LONG -> {
                if (ropts.sessionId() != null) {
                    builder.promptCacheKey(clampCacheKey(ropts.sessionId()));
                }
                builder.promptCacheRetention(ResponseCreateParams.PromptCacheRetention.of("24h"));
            }
            case SHORT -> {
                if (ropts.sessionId() != null) {
                    builder.promptCacheKey(clampCacheKey(ropts.sessionId()));
                }
            }
        }
    }
```

对照 pi 的三处差异：

1. **`LONG` 无条件发 `24h`** —— pi 还要 `&& supportsLongCacheRetention && !supportsExplicitPromptCacheMode`。⇒ `supportsExplicitPromptCacheMode:true` 的模型上本仓**多发** `prompt_cache_retention`。
2. **`NONE` 什么都不发** —— pi 在 `supportsExplicitPromptCacheMode` 时发 `prompt_cache_options: {mode:"explicit"}`。
3. **`prompt_cache_options` 整个键从不出现** —— `grep -rn prompt_cache_options pi-java-ai/src` **零命中**。

`ModelCompat` 已有 `supportsLongCacheRetention`（A-01 引入），**没有** `supportsExplicitPromptCacheMode`（`catalog/ModelCompat.java:286-313` 的组件表逐条核过）。

### 3.3 B90：Anthropic 工具流式

`protocol/AnthropicRequestBuilder.java` 的 `toAnthropicTool`（逐字）：

```java
    private static Tool toAnthropicTool(ToolDefinition td, boolean deferLoading,
                                        CacheControlEphemeral cacheControl,
                                        boolean supportsStrictTools) {
        var strict = Boolean.TRUE.equals(
            StrictSampling.resolveStrict(td, supportsStrictTools));
        var schema = strict
            ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();
        var inputSchema = Tool.InputSchema.builder()
                .putAllAdditionalProperties(AnthropicMessageConverter.toJsonValues(schema))
                .build();
        var toolBuilder = Tool.builder()
                .name(td.name())
                .inputSchema(inputSchema);
        if (td.description() != null && !td.description().isBlank()) {
            toolBuilder.description(td.description());
        }
        if (deferLoading) {
            toolBuilder.deferLoading(true);
        }
        if (cacheControl != null) {
            toolBuilder.cacheControl(cacheControl);
        }
        if (strict) {
            toolBuilder.strict(true);
        }
        return toolBuilder.build();
    }
```

⇒ 缺 `eager_input_streaming`。

`protocol/AnthropicRequestBuilder.java:134-146` beta 头组装：

```java
        var betas = new ArrayList<String>();
        if (request.model() != null
                && request.model().capabilities().contains(com.pijava.ai.model.ModelCapability.THINKING)
                && request.reasoning().isPresent()
                && !compat.forceAdaptiveThinking()) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }
        if (nativeToolChanges) {
            betas.add(MID_CONVERSATION_TOOL_CHANGES_BETA);
        }
        if (!betas.isEmpty()) {
            builder.putAdditionalBodyProperty("betas", JsonValue.from(betas));
        }
```

⇒ 缺 `FINE_GRAINED_TOOL_STREAMING_BETA` 那一条，也缺 pi `:998-1015` 的 `anthropic-beta` 头覆盖语义。

`ModelCompat` **没有** `supportsEagerToolInputStreaming`。

---

## 4. 改动点（逐点：pi 落点 → java 待写代码）

### 4.1 新增两个 compat 字段（B90 ＋ B104 的前置）

`catalog/ModelCompat.java` 组件表加两项（`Boolean`，可空 ＝ 未知）：

```java
    Boolean supportsEagerToolInputStreaming,     // pi anthropic-messages.ts:209，缺省 true
    Boolean supportsExplicitPromptCacheMode,     // pi openai-responses.ts:78，缺省 false
```

连带面（照 A-01 引入 `supportsLongCacheRetention` 的旧例）：`CompatResolver` 三源合一、`ModelsJsonSchema` 的键、`CatalogCompatRules` 的目录标注（若 pi 生成器里有标注）、`ModelCompat.NONE` 构造器。

### 4.2 B142 —— 让 4 条车道的思考级别生产可达

**共同**：消费点一律走 `SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning())`，**不要再接一次夹取**（`原 docs/57 §12.3` 的归属前移）。

**responses ＋ azure**（共用 `ResponsesMessageConverter`；Azure 在 `protocol/AzureOpenAIResponsesApi.java:80` 复用 `ResponsesMessageConverter.buildParams`）：

> ⚠️ **2026-10-04 设计期实测更正**：`effortString` 的缺口比设计初稿写的**大**。pi 的写点是一整块（`api/openai-responses.ts:343-362`），本仓只移植了其中一小部分。

pi 的逐字码：

```ts
	if (model.reasoning) {
		if (options?.reasoningEffort || options?.reasoningSummary) {
			const effort = options?.reasoningEffort
				? (model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort)
				: "medium";
			params.reasoning = {
				effort: effort as NonNullable<typeof params.reasoning>["effort"],
				summary: options?.reasoningSummary || "auto",
			};
			params.include = ["reasoning.encrypted_content"];
		} else if (model.provider !== "github-copilot" && model.thinkingLevelMap?.off !== null) {
			params.reasoning = {
				effort: (model.thinkingLevelMap?.off ?? "none") as NonNullable<typeof params.reasoning>["effort"],
			};
		}
		if (model.provider === "xai") params.include = ["reasoning.encrypted_content"];
	}
```

⇒ 逐条差（java `ResponsesMessageConverter:144-160` ＋ `:490-503`）：

| # | pi | java 现状 |
|---|---|---|
| a | `model.reasoning` 门 | **无门** |
| b | 级别走 `thinkingLevelMap?.[level] ?? level`（**映射值优先**） | `effortString` 硬编码 switch，**从不读 `thinkingLevelMap`** |
| c | 类型是 `"minimal"\|"low"\|"medium"\|"high"\|"xhigh"\|"max"` 逐字 | `XHigh`/`Max` 塌成 `"high"` ⇒ 支持 xhigh 的模型上**发错值** |
| d | 只给 summary 时 effort 回落 **`"medium"`** | effort 缺席（不发该子键） |
| e | 无 effort／summary 时的 **off 支**：`{effort: thinkingLevelMap.off ?? "none"}`（`provider!=="github-copilot"` ＋ `off!==null`） | **整支没有** |
| f | `include: ["reasoning.encrypted_content"]` 随该支发 | 见 `:160` 附近的既有实现（本包**不动**，已由 Batch F 落） |

**本包的落地**（把 a–e 补齐，f 不动）：

```java
        // pi :230 的 streamSimple 包装 —— 夹取在这里；extra["reasoningEffort"] 是 pi stream()
        // 直调语义的显式覆盖通道（生产不经它）⇒ 优先。
        var level = ropts.reasoningEffort() != null
            ? Optional.of(ropts.reasoningEffort())
            : SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning());

        if (request.model().capabilities().contains(ModelCapability.THINKING)) {   // a
            if (level.isPresent() || ropts.reasoningSummary() != null) {
                // b：目录映射值优先，缺席回落级别自身的字面量 —— 不再塌成 "high"（c）
                var mapped = request.model().thinkingLevelMap()
                    .mapped(ModelThinkingLevel.of(level.orElse(ThinkingLevel.Medium)));
                var literal = mapped.orElse(level.map(ResponsesMessageConverter::wireLiteral)
                    .orElse("medium"));                                            // d
                ...
            } else if (!"github-copilot".equals(request.model().id().provider())
                    && !request.model().thinkingLevelMap().explicitlyUnsupported(ModelThinkingLevel.off())) {
                // e：off 支
                var offEffort = request.model().thinkingLevelMap().mapped(ModelThinkingLevel.off())
                    .orElse("none");
                ...
            }
        }
```

⚠️ **本条要连带改既有夹具**：`effortString` 的 `XHigh/Max → "high"` 塌缩被至少一个既有用例钉着（`OpenAIResponsesApiTest` 一族的 effort 断言），期望值会翻成 `"xhigh"`/`"max"`。实施时逐条核。
⚠️ `wireLiteral` 是新增的纯映射（`Minimal→"minimal"` … `Max→"max"`），**不含任何回落** —— 回落是 b/d 两支各管。

**google／mistral**：⚠️ **不在本包**（见 §5 J2 的取证）。pi 侧的六件套（`resolveGoogleThinkingLevel`／`usesGoogleThinkingLevel`／`toGoogleThinkingLevel`／`getDisabledGoogleThinkingConfig`／`usesPromptModeReasoning`／`usesReasoningEffort`／`mapReasoningEffort`）在本仓**一个都不存在** ⇒ 属**移植缺失**。本包只登记、不实现；另立包时按 pi `google-generative-ai.ts:318-340`／`mistral-conversations.ts:200-210` 逐条移植。

**anthropic**：**零改动**（已对齐，见 §3.1）。

### 4.3 B104 —— Responses 缓存三键

`protocol/ResponsesMessageConverter.java` 的 `applyCacheRetention` 重写为：

```java
    private static void applyCacheRetention(ResponseCreateParams.Builder builder,
                                            ResponsesOptions ropts, ModelCompat compat) {
        var retention = ropts.cacheRetention();
        var longOk = !Boolean.FALSE.equals(compat == null ? null : compat.supportsLongCacheRetention());
        var explicit = Boolean.TRUE.equals(compat == null ? null : compat.supportsExplicitPromptCacheMode());

        if (retention != CacheRetention.NONE && ropts.sessionId() != null) {
            builder.promptCacheKey(clampCacheKey(ropts.sessionId()));   // pi :315 第一支
        }
        if (retention == CacheRetention.LONG && longOk && !explicit) {
            builder.promptCacheRetention(ResponseCreateParams.PromptCacheRetention.of("24h"));
        }
        // pi :317 —— prompt_cache_options 只在显式模式下发
        if (explicit) {
            if (retention == CacheRetention.NONE) { /* {mode:"explicit"} */ }
            else if (retention == CacheRetention.LONG && longOk) { /* {ttl:"30m"} */ }
        }
    }
```

✅ **设计期实测（2026-10-04）**：openai-java-core **4.42.0** 有完整访问器，**不需要树路**：

```
ResponseCreateParams$Builder.promptCacheOptions(ResponseCreateParams$PromptCacheOptions)
ResponseCreateParams$PromptCacheOptions$Builder.mode(…$Mode)   → Mode.EXPLICIT / IMPLICIT
ResponseCreateParams$PromptCacheOptions$Builder.ttl(…$Ttl)     → Ttl._30M
```

⇒ §4.3 的落地形态：

```java
        if (explicit) {
            var pb = ResponseCreateParams.PromptCacheOptions.builder();
            if (retention == CacheRetention.NONE) {
                pb.mode(ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT);
            } else if (retention == CacheRetention.LONG && longOk) {
                pb.ttl(ResponseCreateParams.PromptCacheOptions.Ttl._30M);
            }
            builder.promptCacheOptions(pb.build());
        }
```

⚠️ `explicit` 为真但两条支都不命中（`SHORT`）⇒ pi 返回 `undefined` ⇒ **不发这个键**，上面的写法要包在「有支命中」的条件里。逐字照 pi 的 `getPromptCacheOptions` 返回 `undefined` 语义。

### 4.4 B90 —— Anthropic 工具流式

1. `ModelCompat` 加字段（§4.1）。
2. `toAnthropicTool` 的签名加 `boolean supportsEagerToolInputStreaming`，体内加：

```java
        if (supportsEagerToolInputStreaming) {
            toolBuilder.eagerInputStreaming(true);
        }
```

✅ **设计期实测（2026-10-04）**：anthropic-java-core **2.66.0** 的 `Tool.Builder` 有 `eagerInputStreaming(boolean)` 与 `eagerInputStreaming(java.lang.Boolean)` 两个重载 ⇒ **SDK 访问器，不需要树路**。pi 的 `...(cond ? {eager_input_streaming: true} : {})`（**缺席**而非 `false`）对应**不要调**该 setter。

3. `AnthropicRequestBuilder` 的 betas 组装加一条：

```java
        if (!Transcripts.getCurrentTools(transcript.messages()).isEmpty()
                && !supportsEagerToolInputStreaming) {
            betas.add(FINE_GRAINED_TOOL_STREAMING_BETA);
        }
```

### 4.5 死键 `extra["reasoningEffort"]` 的处置

见 §5 裁决 J3。

---

## 5. 设计审核门（**实施前必须裁决**）

| # | 裁决点 | 选项 | 我的建议 |
|---|---|---|---|
| **J1** | 本包范围 | ①只做「已有生产者的 clamp」（= 几乎无事可做）；②**让 4 条车道的思考级别生产可达** | **②** —— ①没有实际产出 |
| **J2** | google／mistral 的思考配置 | ①照 pi 补全；②登记为不移植；③**另立包** | ⚠️ **已取证（2026-10-04）**：pi 那六个辅助函数（`resolveGoogleThinkingLevel`／`usesGoogleThinkingLevel`／`toGoogleThinkingLevel`／`usesPromptModeReasoning`／`usesReasoningEffort`／`mapReasoningEffort`）在本仓**一个都不存在**（逐个 `grep` 全 0 命中）⇒ 这是**移植缺失**、不是接线缺失。**建议改判 ③**：本包只做 responses/azure（B142 的真生产缺口）＋ B104 ＋ B90，google/mistral 的思考配置另立包 |
| **J3** | `extra["reasoningEffort"]` 死键 | ①删（A-20 式：无生产者不写）；②保留为「显式覆盖通道」（pi 的 `stream()` 直调语义） | **②保留**，但在 `ResponsesOptions` 的 javadoc 写明「生产路径不经此键」 |
| **J4** | 两个新 compat 字段 | ①本包加；②并入 A-07 的归属表包 | **①**（B90/B104 是它们唯一的消费者，A-07 的归属表本来就是「谁消费谁落地」） |
| **J5** | SDK 是否支持 `promptCacheOptions` / `eagerInputStreaming` | — | ✅ **已实测（2026-10-04）：两个都有** ⇒ 全走 SDK 访问器，**不需要树路**。逐字见 §4.3／§4.4 |
| **J6** | `prompt_cache_options` 的线格键 | — | ✅ **已解决**：SDK 的 `PromptCacheOptions.Builder` 序列化出的顶层键就是 `prompt_cache_options`（`mode`/`ttl` 两子键），与 pi `:317` 同形 |

---

## 6. 每项验收门槛

1. **先红**：每条车道一组 wire 夹具（`RecordingHttpServer` 观测出站字节 —— `原 docs/54 §12.8` 的教训：**观测面必须盖全 SDK 访问器／序列化 JSON／录制字节三层**）；先红证旧实现下目标断言失败。
2. **GREEN**：夹具通过 ＋ 车道的 adapter-path 集成测试通过。
3. **Mutation probe**：逐条撤掉接线／改变关键条件，记录**精确红集**（⚠️ 变异后必须 `grep` 复核落地 —— CRLF 教训第 5 次）。
4. **模块回归**：`mvn -o -pl pi-java-ai -am test`。
5. **全 reactor**：`mvn -o clean verify`（⚠️ `mvn test` **不跑 spotbugs**，它绑 `verify`）。
6. **静态门禁**：checkstyle 0 新违规、文件 ≤ 500 行、无 `@SuppressWarnings`。

---

## 7. 明确不做

- **不**给 anthropic 接 `clampThinkingLevel` —— pi 自己不夹（§2.2），接上就是**发明行为**。
- **不**动 `ThinkingLevelMap` 的生产者（宿主面，H5 已闭环）。
- **不**实现 pi `getBetaFeatures` 的 `anthropic-beta` 头覆盖（`:998-1015`）—— 本仓 headers 经 client builder 出站、不经车道，属**另一条通道**（平台差异）。如实登记。
- **不**碰 `xAI include` 分支等长尾。

---

## 8. 已知风险

1. **responses/azure 改判据会动既有 wire 夹具**：`prompt_cache_retention` 从「LONG 恒发」变成「带两个门」⇒ 现有断言（若有）会翻。
2. **google/mistral 补全 = 新增出站字段** ⇒ 对真实端点是**行为变更**（此前不发、此后发），需真端点 smoke。
3. **两个新 compat 字段的默认值**必须与 pi 逐字一致（`true`/`false`），否则目录里没标注的模型行为会漂。

---

## 9. 归属与回填

- 本包闭环时回填：本文件 banner、`docs/05` 台账的 **B142/B104/B90** 三行、`docs/08` 的任务表、以及源码 javadoc。
- 归属前移回执：`SimpleOptions.clampedReasoningEffort` 由 A-10 提前落地，本包**只复用不重接**。
- 新登记（实施期发现的）走 `docs/05` 的追加节。

---

## 12. 实施记录（2026-10-04）

用户裁决：**审核通过，R1–R6 全按建议**（J1 ②让 responses/azure 的思考级别生产可达／J2 ③google·mistral 另立包／J3 ②保留 `extra["reasoningEffort"]` 为显式覆盖通道／J4 ①本包加两个 compat 字段／J5·J6 由设计期实测解决）。

### 12.1 提交清单

| 提交 | 内容 |
|---|---|
| `10febaa` | `ModelCompat` ＋2 组件、`models.json` 两键、`CompatResolver`/`CatalogCompatRules` 透传、B90、B104、B142(responses) ＋ 四组夹具（13 files, +677/−49） |

### 12.2 先红证据

| 夹具 | 跑数 | 红 |
|---|---|---|
| `AnthropicEagerToolStreamingWireTest`（新增） | 3 | **2 红**（第 3 条是负对照空绿：无工具 ⇒ 不挂 beta） |
| `ResponsesPromptCacheWireTest`（新增） | 5 | **3 红**（两条本来就对：默认 long、short 无 options） |
| `ResponsesReasoningWireTest`（新增） | 6 | **4 红**（两条负对照空绿） |
| `OpenAIResponsesApiTest.buildParamsSetsReasoningEffortFromExtra` | — | **1 错**（`NoSuchElement`：模型无 `THINKING` ⇒ 新门挡住） |
| `ResponsesReasoningIncludeWireTest.sendsIncludeWhenReasoningEffortIsSet` | — | **1 红**（同上） |

⇒ **两条既有夹具翻了**（设计稿 §4.2 的预测兑现）：两条的模型都漏声明 `ModelCapability.THINKING`，而 pi `:343` 的 `model.reasoning` 门是**本来就该有**的。修法＝给夹具补能力位（不是改断言语义）。

### 12.3 Mutation probe（∧ 指「恰」）

| 探针 | 变异 | 红集 |
|---|---|---|
| **M1**（B90） | `if (eagerToolInputStreaming)` → `if (true)` | ∧1：`eagerFalseOmitsTheFieldAndSendsTheBetaHeader` |
| **M2**（B104） | 撤掉 retention 的 `&& longOk && !explicit` 两道门 | ∧2：`explicitModeLongSendsOptionsInsteadOfRetention`／`longRetentionFalseDropsTheRetentionKey` |
| **M3**（B142 夹取） | 级别源换成 `Optional.<ThinkingLevel>empty()` | ∧2：`levelIsClampedFromTheStreamRequest`／`thinkingLevelMapValueWinsOverTheLiteral` |
| **M4**（B142 off 支） | `else if (false && !"github-copilot"…)` | ∧1：`noEffortMeansTheOffBranch` |

⚠️ **M3 第一版是「编译期变异」**（`Optional.empty()` 让三元退化成 `Optional<? extends Object>`）⇒ 给不出红集。**变异探针必须能编译通过才有意义**——这与既有教训「变异没落地 ⇒ 零红」同族，但成因是**类型推断**而非文本未落地。记为第六种「零红/假红」成因。

### 12.4 实施期实测（推翻/补强设计稿）

1. **★ 逐组件重建会静默丢新字段**：`ModelCompat` 沿用「新组件追加 + 旧规范降级为便捷构造」的手法 ⇒ **旧 29 参调用点会静默落到便捷构造上**，把两个新字段丢成 `null`。本包撞到 **4 处**：`CatalogCompatRules.withStrictTools`／`withGrammarTools`、`CompatResolver.withCompletions`／`forAnthropic`（后者是**第二次**全量重建，最隐蔽）。B90 的第一版实现因此恒为 `true`（`eager_input_streaming` 永远发），靠夹具的 `isMissingNode` 断言才暴露。⇒ **新增组件时必须 grep 全部「枚举所有组件」的构造点**，不能只改 canonical 与 `resolved`。
2. **`forAnthropic` 有两次重建**（先 `resolved(...)`、再本方法内一次全量）—— 只改前者不够。
3. **`CatalogCompatRules` 的两条目录标注在本仓无命中**：`supportsEagerToolInputStreaming:false` 只对 `github-copilot` 三条与 `fireworks`（`generate-models.ts:275-279`／`:1614`），`supportsExplicitPromptCacheMode:true` 只对 `provider==="openai" && api==="openai-responses" && cost.cacheWrite>0`（`:926-932`）——**本仓内置目录一条都不命中**（`gpt-5*` 三条无 `cacheWrite`，也不携带那两个 provider）⇒ 按 A-20 纪律**不写死规则**，只有 `models.json` 可达。已写进 `ModelCompat` 的 `@param`。
4. **M3 的编译期陷阱**（见 §12.3）。
5. 既有夹具 `GoogleProviderRetryWireTest.retriesOnceWithExponentialBackoffAndReturnsTheFreshStream` 在**满负载**下红过一次（模块耗时 22 s），隔离复跑 2.4 s 全绿 ⇒ 与 **B146** 同族的负载敏感，登记不改。

### 12.5 验收

- `mvn -o -pl pi-java-ai test`：**1364/1364 绿**（改前 1355；本包新增 15 条 = 3＋5＋6＋1）。
- checkstyle：0 violation。
- 文件长度：`AnthropicRequestBuilder` 469→**495**（≤500 ✓）、`CompatResolver` 405 ✓；`ResponsesMessageConverter` 541→**593**、`ModelCompat` **733** —— **存量超限**（前者改前即已超），不拆。
- 全 reactor：见 §12.6。

### 12.6 全 reactor

`mvn -o clean verify`（全 14 模块，含 spotbugs ＋ checkstyle）：**BUILD SUCCESS，14/14 SUCCESS，9:13 min**。

| 模块 | 结果 |
|---|---|
| BOM／pi-java／Telemetry／**AI**／Agent Core／SQLite／Coding Agent／TUI／Protocol／Client／Server／Web／Evals／Dist | 全 **SUCCESS**（AI 1:07、TUI 4:20、Web 1:22） |

⚠️ **口径如实**：该轮在 **TUI 阶段**时我往 `ModelsJsonConfigTest` 补了一条夹具（**只加测试、未动生产源**）。生产代码与最终树逐字相同 ⇒ 上表对生产面有效；那条新夹具另在串行 `mvn -o -pl pi-java-ai test` 中复核为绿（**1365/1365**），并单独做了探针 M5（撤 `def.supportsExplicitPromptCacheMode()` ⇒ **恰 1 红**）。**教训**：不得在 `clean verify` 跑动中改源（会共享 `target`）。

### 12.7 新登记

见 `docs/05` 的 **B155**（google/mistral 思考配置＝移植缺失，另立包）／**B156**（`ModelCompat` 枚举式重建静默丢新字段，结构性隐患）／**B157**（`GoogleProviderRetryWireTest` 负载 flake，与 B146 同族）。

### 12.8 未做（明确）

- **google／mistral 的思考配置**（B155）—— 本包只登记。
- pi `getBetaFeatures` 的 `anthropic-beta` 头显式覆盖（`anthropic-messages.ts:998-1015`）—— 本仓 headers 经 client builder 出站、不经车道，属另一条通道（平台差异），照 §7 不做。
- `effortString` → `wireLiteral` 的**塌缩去除**只落在 responses 车道；azure 复用同一转换器 ⇒ 同时生效。

