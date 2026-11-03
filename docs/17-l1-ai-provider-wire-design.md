# 17 - L1 AI Provider wire 对齐设计（B267 第三批：#1／#2／#108／#101／#110／#111）

> 【背景摘要】docs/15 已闭环 usage 与 OpenRouter 账单，docs/16 已清零 15 个存疑行。本包接手 `docs/map/02-ai.md` 剩余 14 个「缺失」中的 **6 个 provider wire 行**，并把 `openai-java` 由 4.42.0 升级到 4.96.0（用户 2026-11-03 已批准依赖升级）。本包改 Java 代码，不改已闭环的判定。
>
> 文档状态：**设计待审（2026-11-03）**。用户批准前不得写代码。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。

## 0. 范围、基线与口径

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | docs/16 闭环（HEAD `cfcffbdc`） |
| L1 当前账面 | **208.0/232＝89.66%**；对齐／存疑／缺失＝111／0／14；计分单位 125 |
| 本包目标 | 6 行缺失转对齐；账面到 **219.0/232＝94.40%** |
| 可执行基线 | docs/15 终态：`mvn clean verify` 实测 **5,987 测试／0 失败／0 错误／29 跳过** |
| 依赖变更 | `openai-java` **4.42.0 → 4.96.0**（用户已批准） |

### 0.2 为什么先做 provider wire

【场景】B267 剩余面按依赖拆成 provider wire、retry／overflow、OAuth／device／env、CLI 权限、Faux 余量。wire 面是其余面的上游：模型可见的请求体、流事件顺序与工具 schema 先稳定，retry 与 OAuth 才不用二次改请求构造。

【决定】本包做 wire 面 6 行：

| 地图行 | 能力 | 权重 | 现判定 | 本包动作 |
|---|---|---:|---|---|
| #1 | `anthropic-messages` managed effort | 3 | 缺失 | 对齐：顶层 effort `high`→`low`；`thinkingEnabled=false` 抑制 managed 与 marker |
| #2 | `openai-completions` 同 chunk reasoning 先于 content | 3 | 缺失 | 对齐：调整同一 delta 内的处理与建块顺序 |
| #108 | Responses mid-conversation effort | 2 | 缺失 | 对齐：SDK 升级后发 `configuration_update`；顶层 effort 固定 `medium` |
| #101 | strict 默认＋Anthropic fallback | 1 | 缺失 | 对齐：prefer 工具 400 一次性降级＋diagnostic |
| #110 | pi-messages `contentIndex` 安全 | 1 | 缺失 | 对齐：非负整数、上界与缺失键校验 |
| #111 | Gemini JSON-Schema `const` | 1 | 缺失 | 对齐：string/number `const` 递归改单值 `enum` |

合计权重 **11**。

### 0.3 SDK 升级（#108 的前置）

【场景】#108 需要发送 `configuration_update` 输入项。4.42.0 的稳定 `ResponseInputItem` 没有该变体，也没有公开的任意 `JsonValue` item 工厂。

【决定】升级 `openai-java` 到 **4.96.0**，使用其**稳定** API：

```text
com.openai.models.responses.ResponseInputItem.ofConfigurationUpdate(
    ResponseConfigurationUpdateItemParam)
```

`ResponseConfigurationUpdateItemParam` 的 builder 默认把 `type` 设为 `"configuration_update"`，`reasoning.effort` 使用既有的 `com.openai.models.ReasoningEffort`。4.96.0 同时提供 beta 命名空间版本，但本包**不迁 beta 车道**。

【可行性探针】工作树临时把 BOM 的 `version.openai-java` 改为 4.96.0 后，`mvn -DskipTests compile` 与 `mvn -DskipTests test-compile` 均 **EXIT 0**（主源码与测试源码全部通过编译）；探针已还原，未提交。运行时测试未在探针阶段执行，列入 §8。

【风险/代价】[内部，模型不可见] 4.42→4.96 跨 54 个版本，序列化默认值、`Optional` 访问器或未知键处理可能有行为漂移。实施第一步必须**只升版本、跑全量测试**，确认零回归后再做功能改动；任何漂移按普通 SDK 兼容问题修复，不掩盖。

### 0.4 账面影响

| 口径 | docs/16 后 | 本包后（预计） |
|---|---:|---:|
| 加权 | 208.0/232＝89.66% | **219.0/232＝94.40%** |
| 未加权 | 111/125＝88.8% | **117/125＝93.6%** |
| 对齐／存疑／缺失 | 111／0／14 | **117／0／8** |
| 计分单位 | 125 | **125**（分母不变） |
| 权重 1 | 47/53＝88.7% | **50/53＝94.3%** |
| 权重 2 | 62/74＝83.8% | **64/74＝86.5%** |
| 权重 3 | 99/105＝94.3% | **105/105＝100%** |

域表：Wire 适配器 **17/17＝100%**；横切 **46/51＝90.2%**；新增单元 **15/16＝93.8%**；其余域不变。剩余缺失为 #43／#44／#45／#50／#70／#71／#80／#112，共 8 个单元、权重 13。

### 0.5 不在本包

- 不改 `docs/15`／`docs/16` 已闭环的实现与判定。
- 不处理 #70／#71（retry／overflow）、#43–#45／#50（OAuth／env）、#80（Faux）、#112（CLI 权限）。
- 不迁 Responses beta 车道；不使用反射或私有 constructor 绕过 SDK。
- 不改共享 `Usage`、identity、catalog 数据面。

### 0.6 术语

- **managed effort**：Anthropic 模型的 `supportsMidConvoEffort` 能力；请求用消息级 `output_config:{effort}` marker 表达每轮 effort。
- **mid-conversation effort**：Responses 的同类能力；用输入项 `configuration_update.reasoning.effort` 表达每轮 effort。
- **strict 工具**：工具声明 `constrainedSampling.strict=prefer|require` 且 schema 可转 strict 子集。
- **prefer**：不支持或不可转时静默回落普通工具；运行时 400 也允许一次性降级。
- **contentIndex**：pi-messages 线格中事件的块下标，pi 用它键控 `partial.content` 数组。
- **模型可见面**：#1／#2／#108／#111 的请求体与事件顺序、#101 的工具声明、#110 的流错误文本；均须逐字对齐。

## 1. 判据

1. **pi 有、Java 无且行为可观察** ⇒ 按缺失行实现；不得只改文档。
2. **SDK 不承载** ⇒ 先升级到承载该能力的稳定版本；仍不可行才另立工单，不用反射或私有 API 绕过。
3. **线格错误必须成为 assistant error 事件**：pi 的 `throw` 在 Java 车道层由 `catch` 转 `StreamError`，不得吞掉。
4. **诊断只进 `AssistantMessage.diagnostics`**，不得进入任何线格文本。

## 2. #1 Anthropic managed effort（权重 3）

### 2.1 pi 证据（`packages/ai/src/api/anthropic-messages.ts`，逐字要点）

`1197-1213`：

```ts
const thinkingDisabled =
	model.reasoning && options?.thinkingEnabled === false && model.thinkingLevelMap?.off !== null;
// Effort cannot change between messages while thinking is disabled, so "off" sends no effort markers.
const effortMessages = model.compat?.supportsMidConvoEffort === true && !thinkingDisabled;
...
const activeEffort = options?.effort ?? "high";
...
messages: effortMessages ? insertThinkingLevelMessages(converted, activeEffort) : converted.messages,
```

`1270-1285`：

```ts
if (thinkingDisabled) {
	// No effort is sent, so the model default applies. Anthropic rejects `block_binding`
	// with disabled thinking.
	params.thinking = { type: "disabled" };
} else if (effortMessages) {
	params.thinking = {
		type: "adaptive",
		display: options?.thinkingDisplay ?? "summarized",
		block_binding: { prefix_mismatch_behavior: "drop_block" },
	};
	// Effort markers set the level for every reply. The top-level effort is part of the cached
	// prompt, so it stays fixed. It is "low" because organizations can cap effort and reject
	// requests containing any level above the cap.
	params.output_config = { effort: "low" };
}
```

`1326` 与 `1561-1574`：

```ts
return { params, hasPreferredStrictTools, providerThinkingLevel: effortMessages ? activeEffort : undefined };
...
function insertThinkingLevelMessages(converted, activeEffort) {
	const messages = [];
	for (...) {
		const historicalEffort = converted.assistantLevels.get(index);
		if (historicalEffort !== undefined) {
			messages.push({ role: "system", content: [], output_config: { effort: historicalEffort } });
		}
		messages.push(converted.messages[index]);
	}
	messages.push({ role: "system", content: [], output_config: { effort: activeEffort } });
	return messages;
}
```

要点：
- 顶层 `output_config.effort` 固定 **`"low"`**，不是 `activeEffort`。
- `thinkingDisabled` 时**不**发 managed `thinking`、**不**发任何 effort marker，`thinking` 落 `{type:"disabled"}`。
- marker 的 effort 仍是 `activeEffort = options?.effort ?? "high"`（无 reasoning 时为 `high`）。
- `providerThinkingLevel` 只在 `effortMessages` 为真时有值。

### 2.2 Java 现状

- `AnthropicThinking.java:142-145`：只要 `supportsMidConvoEffort` 为真，无条件返回 managed 树＋`outputConfigHigh()`，**先于** `reasoning.isEmpty()` 判定 ⇒ `thinkingEnabled=false` 不抑制。
- `AnthropicThinking.java:243-246`：`outputConfigHigh()` 恒发 `HIGH` ⇒ 与新锚点的 `"low"` 不符。
- `AnthropicThinking.java:183-185`：`activeEffort` 已正确（`reasoning` 缺席回落 `"high"`）。
- `AnthropicRequestBuilder.java:178-184`：`managed`／`activeEffort` 只在 `supportsMidConvoEffort` 上判定；`thinkingDisabled` 未参与 ⇒ 历史与队尾 marker 仍会发。
- `AnthropicMessagesApi.java:325-334`：`providerThinkingLevel` 只认 compat flag，未排除 `thinkingDisabled`。

### 2.3 改动方案

1. `AnthropicThinking.resolve` 调整分支顺序：
   - 先算 `thinkingDisabled = model.capabilities().contains(THINKING) && reasoning.isEmpty() && model.thinkingLevelMap().supportsExplicitOff()`；
   - `thinkingDisabled` ⇒ `disabledParam()`，outputConfig 空，maxTokens 空，managed 树空；
   - 否则 `supportsMidConvoEffort` ⇒ `managedThinkingTree()` ＋ `outputConfigLow()`；
   - 否则 `reasoning.isEmpty()` ⇒ 空（off 为 null 的 disabled 语义已在第一支覆盖）。
2. `outputConfigHigh()` 改名为 `outputConfigLow()`，effort 常量为 `"low"`；同步修正 `:139-145`／`:243-246` 的 javadoc（旧锚点 `:1241` 的 `high` 已失效）。
3. `AnthropicRequestBuilder.buildParams` 的 `managed` 改为 `supportsMidConvoEffort && !thinkingDisabled`；`activeEffort` 仅在 managed 时非空。`addMessages` 的历史 marker 与队尾 marker 因此自动消失。
4. `AnthropicMessagesApi.providerThinkingLevel` 增加 `thinkingDisabled` 排除，返回 `null`。
5. 不改变 `temperature` 四重门：`supportsMidConvoEffort === true` 仍使 temperature 不发（pi 同）。

### 2.4 测试计划（RED-first）

- `AnthropicThinkingTest`
  - managed＋无 reasoning ⇒ `thinking` 含 `adaptive`／`block_binding.drop_block`，顶层 `output_config.effort="low"`；`activeEffort="high"`。
  - managed＋reasoning=medium ⇒ 顶层 `"low"`；队尾 marker 为目录映射值。
  - managed＋无 reasoning＋`off` 支持 ⇒ `thinking={type:"disabled"}`；无顶层 `output_config`；无历史/队尾 marker；`providerThinkingLevel()==null`。
  - managed＋无 reasoning＋`off` 为 null ⇒ 仍走 managed（`low`＋marker `high`）。
  - 非 managed 的 adaptive／budget／disabled 分支输出逐字节不变。
- `AnthropicRequestBuilderTest`：同一 transcript 的 marker 数量在 disabled 时归零。
- 变异探针：把 `"low"` 改回 `"high"`、把 `thinkingDisabled` 判定删掉，夹具必须红。

## 3. #2 openai-completions 同 chunk reasoning 先于 content（权重 3）

### 3.1 pi 证据（`packages/ai/src/api/openai-completions.ts:576-629`）

```ts
// Handle reasoning before content: some proxies (e.g. LiteLLM) aggregate reasoning and
// answer into one delta, and the thinking block must precede the text block.
...
const block = ensureThinkingBlock(thinkingSignature);
block.thinking += delta;
stream.push({ type: "thinking_delta", ... });
...
const reasoningDetails = ...;
if (Array.isArray(reasoningDetails)) { ... }
...
if (choice.delta.content !== null && ...) {
	const block = ensureTextBlock();
	block.text += choice.delta.content;
	stream.push({ type: "text_delta", ... });
}
```

要点：同一 `delta` 内处理顺序是 **reasoning 字段 → `reasoning_details` → content**；因此 thinking 块先建，`partial.content` 与最终块序都是 thinking 在前。注释明确针对 LiteLLM 聚合 delta。

### 3.2 Java 现状

- `OpenAICompletionsApi.java:294-305`：先处理 `delta.content()` 并 `emitTextStart`。
- `:307-323`：再处理 reasoning 字段。
- `:325-345`：再处理 `reasoning_details`。
- `:193-195` 的收尾注释仍写「同一个 delta 里两者都有时 content 先处理、块先建」——这是与 pi 相反的旧结论。

### 3.3 改动方案

1. 把 `:294-345` 的代码块整体重排为：reasoning 字段 → `reasoning_details` → content → tool calls。
2. `blockEnds` 的入队顺序随之变为 thinking→text；收尾仍按入队序发 `thinking_end`／`text_end`。
3. 更新 `:193-195` 注释为「reasoning 先于 content，thinking 块先建」。
4. `hasFinishReason`、usage 回退、finish_reason 的位置保持不动（都在 delta 处理之前）。
5. `reasoning_details` 建块后，后续 content 不得复用同一 thinking 块；保持现有 `thinkingStarted` 标志语义。

### 3.4 测试计划

- 新夹具：单个 chunk 同时带 `reasoning_content` 与 `content`。
  - 事件序列：`thinking_start` → `thinking_delta` → `text_start` → `text_delta`；不得出现 `text_delta` 在 `thinking_start` 之前。
  - 终局 `partial.content` 块序为 `[thinking, text]`。
- 仅 content／仅 reasoning／`reasoning_details` 的既有夹具逐字节不变。
- 变异探针：交换两段处理顺序，夹具必须红。

## 4. #108 Responses mid-conversation effort（权重 2）

### 4.1 pi 证据

`packages/ai/src/api/openai-responses.ts:43`：

```ts
const CONFIGURATION_UPDATE_REQUEST_EFFORT = "medium";
```

`openai-responses.ts:103-121`：

```ts
function getConfigurationUpdateEffort(model, options, compat) {
	if (
		!compat.supportsMidConvoEffort ||
		!options?.reasoningEffort ||
		// Sign in with ChatGPT rejects configuration_update items.
		isChatGPTSignIn(model, options.apiKey)
	) {
		return undefined;
	}
	return model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
}
```

`openai-responses.ts:165-172` 与 `:396-405`：

```ts
const configurationUpdateEffort = getConfigurationUpdateEffort(model, options, compat);
...
...(configurationUpdateEffort === undefined ? {} : { providerThinkingLevel: configurationUpdateEffort }),
...
const requestedEffort = options?.reasoningEffort
	? (model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort)
	: reasoningEffort;
const effort = configurationUpdateEffort === undefined ? requestedEffort : CONFIGURATION_UPDATE_REQUEST_EFFORT;
params.reasoning = { effort, summary: options?.reasoningSummary || "auto" };
```

`openai-responses-shared.ts:267-272,401-430`：

```ts
const pushConfigurationUpdate = (effort: string): void => {
	messages.push({
		type: "configuration_update",
		reasoning: { effort: effort as ... },
	});
};
...
if (output.length === 0) continue;
if (
	options?.configurationUpdateEffort !== undefined &&
	isSameModel &&
	msg.providerThinkingLevel !== undefined
) {
	pushConfigurationUpdate(msg.providerThinkingLevel);
}
messages.push(...output);
...
if (options?.configurationUpdateEffort !== undefined) pushConfigurationUpdate(options.configurationUpdateEffort);
```

要点：
- 只有 `supportsMidConvoEffort`、有请求级别、且**不是** ChatGPT 订阅登录时才启用。
- 启用时顶层 `reasoning.effort` 固定 **`"medium"`**；每轮真实 effort 由 `configuration_update` 输入项携带。
- 历史 assistant 消息在其输入项之前插一条 `configuration_update`，条件同时要求同模型且该消息有 `providerThinkingLevel`；转录末尾再补一条当前 effort。
- 输出 assistant 消息盖上 `providerThinkingLevel = configurationUpdateEffort`，供下一轮回放。

### 4.2 Java 现状

- `ResponsesMessageConverter.java:143-175`：顶层 `reasoning.effort` 直接发请求级别（目录映射），**没有** `medium` 常量，也没有 `configuration_update`。
- `ResponsesMessageItems.java:41-121`：消息转换循环没有 config update；`:215-267` 的 assistant 分支只在 tool call 内部计算 `sameModel`。
- `OpenAIResponsesApi.java:110` 已有 `isChatGptSignIn`；转换器通过 `omitUnsupportedFields` 拿到同一判定（`:132-136`）。
- `AbstractChatApi.java:70` 的 `providerThinkingLevel` 默认 `null`；Responses 车道未覆盖 ⇒ 输出消息不盖章。
- SDK 4.96.0 已提供 `ResponseInputItem.ofConfigurationUpdate(ResponseConfigurationUpdateItemParam)`（§0.3）。

### 4.3 改动方案

1. 在 `ResponsesMessageConverter` 增加共享辅助（`OpenAIResponsesApi` 复用）：

   ```java
   static Optional<ThinkingLevel> effectiveLevel(StreamRequest request, ResponsesOptions ropts)
   static Optional<String> configurationUpdateEffort(ModelInfo model, ModelCompat compat,
                                                     Optional<ThinkingLevel> level,
                                                     boolean chatGptSignIn)
   ```

   `effectiveLevel` 保留现有优先级：`extra["reasoningEffort"]` 优先，否则 `SimpleOptions.clampedReasoningEffort`；`configurationUpdateEffort` 要求 `supportsMidConvoEffort==true`、`level` 非空、`!chatGptSignIn`，值取 `thinkingLevelMap.mapped(level).orElse(wireLiteral(level))`。

2. 顶层 reasoning：
   - `configurationUpdateEffort` 非空 ⇒ `builder.reasoning(Reasoning.builder().effort(ReasoningEffort.of("medium")).summary(...).build())` ＋ `include(...)`；
   - 否则保持现有 `requestedEffort` 分支与 off 分支。
3. `ResponsesMessageItems.convertMessages` 增加 `String configurationUpdateEffort` 参数（`null` ≙ pi 的 `undefined`）。
4. assistant 分支：
   - 在循环外计算一次 `isSameModel`（同 provider＋同 api＋`assistant.model()==target.modelName()`）；
   - 若该消息产出了任何输入项（`textMessages`／`toolCalls`／`reasoningItems` 非空），且 `configurationUpdateEffort != null`、`isSameModel`、`assistant.providerThinkingLevel() != null` ⇒ 在推入该消息的项**之前** `items.add(configurationUpdate(msg.providerThinkingLevel()))`；
   - 转录循环结束后，若 `configurationUpdateEffort != null` ⇒ `items.add(configurationUpdate(configurationUpdateEffort))`。
5. 新增私有构造：

   ```java
   private static ResponseInputItem configurationUpdate(String effort) {
       return ResponseInputItem.ofConfigurationUpdate(
           ResponseConfigurationUpdateItemParam.builder()
               .reasoning(ResponseConfigurationUpdateItemParam.Reasoning.builder()
                   .effort(ReasoningEffort.of(effort)).build())
               .build());
   }
   ```
   builder 自动把 `type` 设为 `"configuration_update"`（4.96.0 实测）。
6. `OpenAIResponsesApi` 覆盖 `providerThinkingLevel(StreamRequest)`：模型有 THINKING、`compat.supportsMidConvoEffort()` 为真、`!isChatGptSignIn(...)`、`effectiveLevel` 非空时返回 `configurationUpdateEffort`；否则 `null`。Azure 车道（`omitUnsupportedFields=false` 且 provider 非 openai）按同一 compat 门自然启用或禁用。
7. ChatGPT 订阅登录：`omitUnsupportedFields=true` ⇒ 不启用 config update，顶层 effort 保持请求级别（与 pi `getConfigurationUpdateEffort` 返回 undefined 同）。

### 4.4 测试计划

- `ResponsesConfigurationUpdateTest`（构造 `ResponseCreateParams` 后断言 input 项与 JSON）：
  - `supportsMidConvoEffort=true`＋level=high ⇒ 顶层 `reasoning.effort="medium"`；历史同模型 assistant（`providerThinkingLevel=low`）之前有一条 `configuration_update{effort:"low"}`；队尾有一条 `{effort:"high"}`。
  - 跨 provider／跨 api 的历史 assistant ⇒ 不为其插 update；队尾仍插。
  - 历史 assistant 无 `providerThinkingLevel` ⇒ 不插。
  - ChatGPT 登录（omit=true）⇒ 无 config update；顶层 effort=high。
  - `supportsMidConvoEffort=false` ⇒ 输出与现有逐字节一致。
  - 输出 assistant 消息的 `providerThinkingLevel` = wire 映射值（high→high，目录映射走目录值）。
- 序列化夹具：`configuration_update` 的 JSON 键序为 `type, reasoning{effort}`；不得出现额外键。
- 变异探针：把 `"medium"` 改回请求级别、删掉同模型门，夹具必须红。

## 5. #101 Anthropic strict 一次性降级（权重 1）

### 5.1 pi 证据

`packages/ai/src/api/constrained-sampling.ts:141-159`：

```ts
export function prefersJsonSchemaStrictSampling(tool: Tool): boolean {
	const config = tool.constrainedSampling;
	return !!config && config.type === "json_schema" && config.strict === "prefer";
}
...
const STRICT_TOOL_LIMIT_ERROR_MESSAGES = [
	"The compiled grammar is too large",
	"Too many strict tools (",
	"Schemas contains too many parameters with union types (",
];
export function isStrictToolLimitErrorMessage(message: string): boolean {
	return STRICT_TOOL_LIMIT_ERROR_MESSAGES.some((limitMessage) => message.includes(limitMessage));
}
```

`packages/ai/src/api/anthropic-messages.ts:649-689`：

```ts
let disablePreferredStrictTools = false;
for (;;) {
	const built = buildParams(model, normalizedContext, isOAuth, options, disablePreferredStrictTools);
	if (built.providerThinkingLevel) output.providerThinkingLevel = built.providerThinkingLevel;
	...
	try {
		response = await retryProviderRequest(...);
		break;
	} catch (error) {
		// Only downgrade a rejected request, never an already-started stream.
		if (
			options?.signal?.aborted ||
			disablePreferredStrictTools ||
			!built.hasPreferredStrictTools ||
			!(error instanceof Anthropic.APIError) ||
			error.status !== 400 ||
			error.type !== "invalid_request_error" ||
			!isStrictToolLimitErrorMessage(error.message)
		) throw error;
		disablePreferredStrictTools = true;
		appendAssistantMessageDiagnostic(output, {
			type: "anthropic_strict_tools_fallback",
			timestamp: Date.now(),
			error: { message: error.message },
			details: { reason: "strict_tool_limits_exceeded" },
		});
	}
}
```

要点：只降级「尚未开始流」的请求；只有 `hasPreferredStrictTools` 为真才降级；`require` 工具不得降级；第二次不再降级。

### 5.2 Java 现状

- `StrictSampling.resolveStrict` 已实现 prefer／require 的静态回落（`StrictSampling.java:37-61`），但**没有**运行时 400 降级。
- `AnthropicToolWire.java:108-134` 每次构造都按 `StrictSampling.resolveStrict(...)` 决定 `strict`，没有 `disablePreferredStrictTools` 参数。
- `AnthropicMessagesApi.java:233-281` 只调一次 `ProviderRetry.retry`；400 直接进 `catch(Exception)` 转 `StreamError`。
- `ProviderRetry.ofAnthropic`（`ProviderRetry.java:103-112`）已能拿到 `AnthropicServiceException.statusCode()`；SDK 的 `AnthropicServiceException.errorType()` 暴露 `ErrorType.INVALID_REQUEST_ERROR`。
- `StreamPartialBuilder.appendDiagnostic`（`:155-160`）已支持诊断；`AssistantMessageDiagnostic` 形状已对齐 pi。

### 5.3 改动方案

1. `StrictSampling` 增加：

   ```java
   public static boolean prefersJsonSchemaStrictSampling(ToolDefinition tool)
   ```

   仅当 `constrainedSampling` 是 `JsonSchemaSampling(StrictMode.PREFER)` 时为真。

2. `AnthropicToolWire.addTools(...)` 增加 `boolean disablePreferredStrictTools` 参数并返回 `boolean hasPreferredStrictTools`：
   - `strict = !disablePreferredStrictTools && TRUE.equals(resolveStrict(...))`；
   - 当 `strict==true && prefersJsonSchemaStrictSampling(td)` 时累计 `hasPreferredStrictTools`。
3. `AnthropicRequestBuilder`：
   - 新增 `record BuiltParams(MessageCreateParams params, boolean hasPreferredStrictTools)`；
   - `buildParams(..., boolean disablePreferredStrictTools)` 返回 `BuiltParams`；
   - 保留现有 `buildParams(StreamRequest)`／测试重载，返回 `.params()`。
4. `AnthropicMessagesApi.streamInternal` 的请求获取改为 pi 式循环：
   - 每轮 `buildParams(..., disable)`；
   - `ProviderRetry.retry` 成功后 `break`；
   - 捕获 `AnthropicServiceException`，满足全部条件时置 `disable=true` 并 `builder.appendDiagnostic(...)`，否则原样抛出；
   - 条件：`!Thread.currentThread().isInterrupted()` ＋ `!disable` ＋ `built.hasPreferredStrictTools()` ＋ `statusCode()==400` ＋ `errorType()` 为 `INVALID_REQUEST_ERROR` ＋ `isStrictToolLimitErrorMessage(e.getMessage())`。
   - `emitStart` 移到请求成功之后（pi `:692`）；失败路径只发 `StreamError`，不发 `start`。这一处会改变失败时序，需用现有错误路径测试确认。
   - `payloadHook.onPayload` 每轮重新调用（pi `:640` 在 `buildParams` 后、循环内）；`responseHook.onResponse` 只在成功响应后调用一次。
5. 新增 `AnthropicStrictFallback` 工具类承载：
   - `STRICT_TOOL_LIMIT_ERROR_MESSAGES` 三串；
   - `isStrictToolLimitErrorMessage(String)`；
   - diagnostic 构造（`type="anthropic_strict_tools_fallback"`、`details={"reason":"strict_tool_limits_exceeded"}`）。

### 5.4 测试计划

- `AnthropicStrictFallbackTest`：
  - prefer 工具＋首次 400「Too many strict tools」⇒ 第二次请求的 strict 全关，成功；diagnostic 恰好一条，`details.reason=strict_tool_limits_exceeded`。
  - require 工具＋同样 400 ⇒ **不**降级，错误照发。
  - 非 prefer（`strict:true` 普通）＋400 ⇒ 不降级。
  - 第二次仍 400 ⇒ 不再降级，错误照发。
  - 中断线程 ⇒ 不降级。
  - `onPayload` 每次尝试都调用；`onResponse` 只在成功响应后调用。
- 变异探针：删掉 `errorType`／消息白名单任一条件，夹具必须红。

## 6. #110 pi-messages `contentIndex` 安全（权重 1）

### 6.1 pi 证据（`packages/ai/src/api/pi-messages.ts:195-205`）

```ts
return (event: PiMessagesEvent): AssistantMessageEvent => {
	if ("contentIndex" in event) {
		// The index comes from the wire and keys the content array: `__proto__` would reach Array.prototype, and a huge
		// index would make a sparse array of billions of holes. A start adds the next block; other events need one.
		const index: unknown = event.contentIndex;
		const limit = event.type.endsWith("_start") ? partial.content.length : partial.content.length - 1;
		if (typeof index !== "number" || !Number.isInteger(index) || index < 0 || index > limit) {
			throw new Error(`Invalid contentIndex ${JSON.stringify(index)} in pi-messages ${event.type} event`);
		}
	}
	...
```

要点：`_start` 允许等于当前长度（追加新块）；其他事件必须落在已有块内；非整数、负数、超界、非 number 全部抛错，抛错由车道层转 `error` 事件。

### 6.2 Java 现状

- `PiMessagesEvent.java:32-53`：`contentIndex` 是原始 `int`；JSON 缺键会被 Jackson 静默填 `0`，无法区分「合法 0」与「缺键」。
- `PiMessagesApi.java:155-160`：直接 `JSON.readValue(data, PiMessagesEvent.class)`，无内容下标校验。
- `PiMessagesApi.java:184-201`：`toolIds` 直接以 `contentIndex` 为键；负值/超界会产生错误或稀疏语义（Java Map 不会稀疏，但会静默接受越界键）。
- `StreamPartialBuilder.contentIndex()`（`:182-184`）返回「下一个槽位」，正好等于 pi 的 `partial.content.length`。

### 6.3 改动方案

1. `PiMessagesEvent` 的 `contentIndex` 组件由 `int` 改为 `Integer`（保留现有 record 形状与自动装箱调用点）。
2. `PiMessagesApi` 在 `JSON.readValue` 前先 `JSON.readTree`：
   - 若事件类型属于带 `contentIndex` 的 9 个变体（text/thinking/toolcall 的 start/delta/end）：
     - 键缺失 ⇒ `IllegalArgumentException`（比 pi 的 `in` 检查更严；记为 D213）；
     - 值不是整数字节点（`isIntegralNumber()`）⇒ 抛错；
   - 再 `JSON.treeToValue(node, PiMessagesEvent.class)`。
3. 在 switch 之前统一校验：
   - `int index`；
   - `int limit = isStart ? builder.contentIndex() : builder.contentIndex() - 1`；
   - `index < 0 || index > limit` ⇒ 抛错。
   - 错误文本：`"Invalid contentIndex " + literal + " in pi-messages " + wireType + " event"`，`literal` 用 JSON 字面量（整数原样；缺失为 `null`）。
4. `__proto__` 在 Java 无原型链语义 ⇒ 不实现该分支；只保留整数/范围/缺失校验。
5. 错误在 `catch(Exception)` 中转 `emitError(StopReason.ERROR, e)`（现有路径，已对齐 pi 的抛错收尾）。

### 6.4 测试计划

- `PiMessagesContentIndexTest`：
  - `text_start` index=0 于空流 ⇒ 允许；index=1 于空流 ⇒ 抛错。
  - `text_delta` index=0 于已有 1 块 ⇒ 允许；index=1 ⇒ 抛错；index=-1 ⇒ 抛错。
  - `text_start` index=`"0"`（字符串）⇒ 抛错（Jackson 不会先把它强转成 int）。
  - 缺 `contentIndex` 的 `text_delta` ⇒ 抛错（D213）。
  - `done`／`error` 无 `contentIndex` ⇒ 不校验。
  - 每个错误用例的终局都是 `StreamError`，文本含 `Invalid contentIndex`。
- 变异探针：把 `limit` 的 `-1` 去掉，delta 越界夹具必须红。

## 7. #111 Gemini `const`→单值 `enum`（权重 1）

### 7.1 pi 证据（`packages/ai/src/api/google-shared.ts:390-430,450-455`）

```ts
const JSON_SCHEMA_NAME_MAPS = new Set([
	"properties", "patternProperties", "$defs", "definitions", "dependentSchemas", "dependencies",
]);
const JSON_SCHEMA_SUBSCHEMAS = new Set([
	"additionalProperties", "unevaluatedProperties", "propertyNames", "items", "prefixItems",
	"additionalItems", "unevaluatedItems", "contains", "allOf", "anyOf", "oneOf", "not",
	"if", "then", "else", "contentSchema",
]);

function rewriteConstAsEnum(schema: unknown, isNameMap = false): unknown {
	if (Array.isArray(schema)) return schema.map((item) => rewriteConstAsEnum(item));
	if (typeof schema !== "object" || schema === null) return schema;
	const result = Object.fromEntries(
		Object.entries(schema).map(([key, value]) => {
			if (isNameMap || JSON_SCHEMA_SUBSCHEMAS.has(key)) return [key, rewriteConstAsEnum(value)];
			if (JSON_SCHEMA_NAME_MAPS.has(key)) return [key, rewriteConstAsEnum(value, true)];
			return [key, value];
		}),
	);
	if (!isNameMap && "const" in result && result.enum === undefined) {
		const value = result.const;
		if (typeof value === "string" || typeof value === "number") {
			delete result.const;
			result.enum = [value];
		}
	}
	return result;
}
...
: { parametersJsonSchema: rewriteConstAsEnum(parameters) }),
```

要点：只改 string/number `const`；已有 `enum` 时不覆盖；名字映射下的键（如 `properties` 里的属性名）不得当成 schema 递归；`const` 键本身不得被当作名字映射。

### 7.2 Java 现状

- `GoogleMessageConverter.functions`（`:394-410`）直接把 `tool.inputSchema()`（strict 转换前后）交给 `builder.parametersJsonSchema(schema)`，没有 `const`→`enum` 改写。
- 其余车道（Anthropic／Responses／Completions／Mistral）不在此车道，不能共用本改写。

### 7.3 改动方案

1. 新增 `AI/protocol/GoogleJsonSchema.java`：
   - `static Object rewriteConstAsEnum(Object schema)`；
   - 递归规则逐字对位 pi：数组逐项；`Map` 逐键；`isNameMap` 或子 schema 键递归；名字映射键以 `isNameMap=true` 递归；非名字映射节点且 `const` 为 String／Number 且无 `enum` ⇒ 删 `const` 加 `List.of(value)`。
   - Java `Map` 无 `__proto__` 原型问题；仍按键名照常处理。
2. `GoogleMessageConverter.functions`：`var schema = ...` 之后统一改为
   `schema = GoogleJsonSchema.rewriteConstAsEnum(schema);`，再 `builder.parametersJsonSchema(schema)`。
   strict 与非 strict 两路都改写（pi `:450-455` 是转换后统一改写）。
3. 不改变 `supportsStrictMode` 门与 `sanitizeForOpenApi` 路径。

### 7.4 测试计划

- `GoogleJsonSchemaTest`：
  - 顶层 `{type:"string", const:"x"}` ⇒ `{type:"string", enum:["x"]}`。
  - 数字 `const: 3` ⇒ `enum:[3]`；boolean／null／object `const` 原样保留。
  - 已有 `enum` ⇒ `const` 保留、`enum` 不动。
  - 嵌套 `properties.flag.{const:"on"}` 与数组 `items.const` 递归命中。
  - `properties` 下的键名 `const` 作为属性名时不得被改写（名字映射语义）。
  - `oneOf`／`anyOf`／`allOf` 内递归。
- 变异探针：把名字映射分支去掉，属性名 `const` 用例必须红；把 string/number 判定放宽成任意值，boolean 用例必须红。

## 8. 测试与验证（包级）

1. **第 0 步（依赖升级隔离）**：只改 BOM 版本到 4.96.0，跑 `mvn clean verify`，确认既有 5,987 测试零失败、Checkstyle／SpotBugs 零告警；有漂移先修，再进入功能改动。
2. **RED-first**：每个小节先落夹具（红），再写实现（绿）。
3. 模块验证：`java -version` 用 GraalVM 25，`mvn test -pl pi-java-ai -am`。
4. 全量：`mvn clean verify`，要求 **0 失败／0 错误／0 跳过新增失败**、Checkstyle 0、SpotBugs 0；目标测试数不低于 5,987＋本包新增。
5. 真实终端冒烟不适用：本包是库内 wire 行为，无 TUI／CLI 交互新增；#101 的错误收尾与 #108 的回放用 `AgentSessionToolIntegrationTest` 的 Faux 流覆盖。
6. 台账回填：`docs/map/02-ai.md` 六行的判定与备注；`docs/04` 的 B267 描述与 D213；`docs/06`／`docs/08` 状态；docs/17 §11。

## 9. 风险与裁决

1. **SDK 跨 54 版本升级**
   【场景】4.42→4.96 可能改变序列化默认、Optional 访问器或未知键保留策略。
   【决定】升级单独成第一步并跑全量测试；功能改动在绿色基线上进行。
   【风险/代价】[内部，模型不可见] 若运行时测试暴露漂移，必须逐项修复或登记 D，不得用 `-DskipTests` 掩盖。

2. **#108 顶层 effort 从请求级别改为固定 `medium`**
   【场景】启用 mid-convo effort 后，请求体顶层 `reasoning.effort` 不再等于用户级别。
   【决定】照 pi：顶层固定 `medium`，真实级别由 `configuration_update` 携带。
   【风险/代价】[内部，模型不可见] 这是模型可见的请求体变化；仅对 `supportsMidConvoEffort=true` 且非 ChatGPT 登录的模型生效。

3. **`emitStart` 移到请求成功之后**
   【场景】#101 的降级诊断必须进入 start 快照；pi 本就在响应后才发 start。
   【决定】请求获取循环成功后 `emitStart`；非降级失败只发 `StreamError`。
   【风险/代价】[内部，模型不可见] 错误路径不再出现 `start`；现有测试若断言 start-then-error 需按 pi 修正。

4. **`PiMessagesEvent` 的 `int`→`Integer` 是模块内 API 变更**
   【场景】record 组件类型变化会影响构造点与访问器。
   【决定】保留组件名与顺序，自动装箱兼容构造；访问处显式判空。
   【风险/代价】[内部，模型不可见] 若遗漏某处 `contentIndex` 访问，可能 NPE；由 switch 前统一校验兜底。

5. **#101 的降级会重复 `onPayload`**
   【场景】每轮重新构建参数，钩子会再次收到载荷。
   【决定】与 pi 同：每轮 `onPayload` 都调用；`onResponse` 只在成功响应后调用。
   【风险/代价】[内部，模型不可见] 依赖 hook 计数的测试需更新。

6. **#2 的顺序变化会改 `partial.content` 块序**
   【场景】同一 delta 同时带 reasoning 与 content 时，thinking 块先建。
   【决定】按 pi；这是模型可见的助手消息块序。
   【风险/代价】[内部，模型不可见] web／TUI 渲染顺序随块序变化；夹具须覆盖。

## 10. 实施步骤（获批后）

0. 升级 `pi-java-bom/pom.xml` 的 `version.openai-java` 为 `4.96.0`；全量 `mvn clean verify` 确认零回归。
1. #1：改 `AnthropicThinking`／`AnthropicRequestBuilder`／`AnthropicMessagesApi`；补夹具。
2. #2：重排 `OpenAICompletionsApi`；补夹具。
3. #108：改 `ResponsesMessageConverter`／`ResponsesMessageItems`／`OpenAIResponsesApi`；补夹具。
4. #110：改 `PiMessagesEvent` 与 `PiMessagesApi`；补夹具。
5. #111：新增 `GoogleJsonSchema` 并接入 `GoogleMessageConverter`；补夹具。
6. #101：新增 `AnthropicStrictFallback`，改 `StrictSampling`／`AnthropicToolWire`／`AnthropicRequestBuilder`／`AnthropicMessagesApi`；补夹具。
7. 全量验证与台账回填；`docs/map/02-ai.md` 六行判定改「对齐」，并按实际材质差异挂 D（#110 新增 D213；无新窄差的行写「D 不适用」）。
8. Commit 建议：
   - `build(bom): upgrade openai-java to 4.96.0`
   - `feat(ai): align anthropic managed effort`
   - `feat(ai): reorder completions reasoning before content`
   - `feat(ai): send responses mid-conversation effort`
   - `feat(ai): guard pi-messages content index`
   - `feat(ai): rewrite gemini const schemas as enums`
   - `feat(ai): add anthropic strict tool fallback`
   - `docs(ai): close docs/17 provider wire package`

## 11. 闭环记录（实施后回填）

- 实施 HEAD：待填
- 依赖：`openai-java 4.42.0 → 4.96.0`
- 地图翻行：#1／#2／#108／#101／#110／#111 → 对齐
- 新增 D：D213（pi-messages 缺键校验严于 pi 的 `in` 检查）
- 复算结果：待填（预计 219.0/232＝94.40%，对齐 117／缺失 8）
- 与计划的偏差：待填
