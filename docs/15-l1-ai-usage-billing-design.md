# 15 - L1 AI usage 与 OpenRouter 账单对齐设计（B267 第一批：#53／#59／#107）

> 【背景摘要】这是 L1（ai 模块）在固定锚点 `56cae7823` 下的第六个实施包设计稿。docs/10–docs/14 已闭环；B267 是最后一个 L1 混合工单，docs/13／docs/14 均要求先按依赖拆分。本稿只接其中的「usage 字段与 provider-reported billing」一面（地图 #53／#59／#107），不改 provider wire、retry／overflow、OAuth／device／env、pi-ai CLI 权限、Faux 余量与 deferred fetch。
>
> **文档状态：已实施并闭环（2026-11-03）。**
>
> **下一新文档号：`docs/16`（存疑复核，设计待审）。**

## 0. 范围、锚点与基线

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 目标锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec` |
| pi 证据路径 | 固定提交的 `packages/ai/`、`packages/coding-agent/`；只用 `git show 56cae7823:<path>` 取证 |
| pi-java 设计基线 | `5aef5574`（docs/14 闭环提交） |
| 可执行基线 | docs/14 终态：`mvn clean verify` 实测 **5,958 测试／0 失败／0 错误／29 跳过**；Checkstyle 0；SpotBugs 0 |
| 本包终态 | 实施后实测，回填 §10 |
| 本稿占号 | `docs/15`，下一新文档号为 `docs/16` |

> 行号口径：`AI/` 表示 `pi-java-ai/src/main/java/com/pijava/ai/`；`agent-core/` 表示 `pi-java-agent-core/src/main/java/com/pijava/agent/`；其他模块写完整路径。Java 行号以 `5aef5574` 工作树为准。

### 0.2 为什么先做 usage 面

【场景】B267 是混合工单：provider wire、usage、retry／overflow、OAuth／device／env、pi-ai CLI 权限、Faux 余量之间没有共同实现面（`docs/13` §1）。docs/13／docs/14 均要求先拆再设计。

【决定】本稿只接 usage 与 billing 三行（#53／#59／#107）。拆分顺序如下：

1. **docs/15（本包）**：usage 合并语义 ＋ `totalFromProvider` ＋ OpenRouter 账单。
2. `docs/16`：provider wire（#1／#2／#101／#108／#110／#111）。
3. `docs/17`：retry／overflow（#70／#71）。
4. `docs/18`：OAuth／device／env ＋ pi-ai CLI 权限（#43／#44／#45／#50／#112）。
5. `docs/19`：Faux 余量（#80）＋ deferred fetch 生产入口（#75／D203）。

【风险/代价】[内部，模型不可见] usage 面是 B267 中唯一会改共享数据形状 `Usage` 的一面。`Usage.Cost` 新增组件会波及 agent-core 的会话 JSONL 与会话合计。先做它可以把「record 重建点」的审查集中在一个小包里，避免以后与各车道的 wire 改动混在一起。

### 0.3 本包计分单元

| 地图行 | 能力 | pi 证据 | Java 证据 | 当前判定 | 权重 | 目标 |
|---|---|---|---|---:|---:|---|
| #53 | `cacheWrite`／`cacheWrite1h` 的 delta 覆盖 | `packages/ai/src/api/anthropic-messages.ts:864-870` | `AI/protocol/AnthropicUsageState.java:85-102`；`AnthropicEventMapper.java:219-228` | 缺失 | 3 | 对齐 |
| #59 | `message_start`＋逐字段 delta 覆盖 | `packages/ai/src/api/anthropic-messages.ts:849-880` | `AI/protocol/AnthropicUsageState.java:69-115`；`AnthropicEventMapper.java:197-228` | 缺失 | 2 | 对齐 |
| #107 | OpenRouter provider-reported billing | `packages/ai/src/utils/openrouter-cost.ts:20-54`；`packages/ai/src/types.ts:485` | `AI/Usage.java:35-47` 无 `totalFromProvider`；`AI/protocol/OpenAICompletionsUsage.java:57-76` 无账单 | 缺失 | 2 | 对齐 |

账面影响（实施后复核，2026-11-03）：

| 口径 | 当前 | 闭环后（预计） |
|---|---:|---:|
| 加权 | 199.0/240＝82.92% | **206.0/240＝85.83%** |
| 未加权 | 106.5/131＝81.3% | **109.5/131＝83.6%** |
| 对齐／存疑／缺失 | 99／15／17 | **102／15／14** |
| 权重 1 子集 | 46/57＝80.7% | 46/57＝80.7% |
| 权重 2 子集 | 55/78＝70.5% | **59/78＝75.6%** |
| 权重 3 子集 | 96/105＝91.4% | **99/105＝94.3%** |
| usage 域 | 16/21＝76.2% | **21/21＝100%** |
| 新增单元域 | 9/16＝56.3% | **11/16＝68.8%** |

### 0.4 不在本包

- **B267 的其余面**：provider wire（#1／#2／#101／#108／#110／#111）、retry／overflow（#70／#71）、OAuth／device／env（#43／#44／#45／#50）、pi-ai CLI 权限（#112）、Faux 余量（#80）、deferred fetch 生产入口（#75／D203）。
- **B116**：Java 内部 `StreamDone` 的 usage 方言继续保留；本包只改公共 `Usage`。
- **B58**：OpenAI 默认 wire 仍是待用户拍板项，本包不碰。
- **OpenRouter 图片车道**：pi 的 `openrouter-images.ts:187-200` 也给图片 usage 写 `totalFromProvider: false`，但 Java 的 `OpenRouterImagesApi` 不构造 `Usage`；本包不扩面，留待图片 usage 有消费者时另立。
- **`combineUsage` 以外的会话合计**：`SessionState`／`SessionStats` 直接累加 `cost.total`，不涉及 provider-reported 语义，不改。
- 不新增 Maven 依赖；不改 telemetry schema（`pi.ai.cost` 仍只看 `cost.total`）。

### 0.5 术语

- **usage（用量）**：一次 LLM 调用上报的 token 与成本。字段为 `input`／`output`／`cacheRead`／`cacheWrite`／`totalTokens`／`cost`。
- **cacheWrite1h**：`cacheWrite` 中按 1 小时保留期写入的 **子集**。只有 Anthropic 系报文会拆分；价格是普通 input 的 2 倍（`models.ts:1247-1253`）。
- **reasoning**：thinking token 数，是 `output` 的 **子集**；provider 不报就保持缺席。
- **catalog estimate（目录估算）**：按 `ModelInfo.pricing()` 的费率表算出的成本；Java 的 `CostCalculator` 就是它。
- **provider-reported billing（provider 实报账单）**：provider 在响应里直接给出的计费金额。OpenRouter 会在聊天响应的 `usage` 里附带 `cost`／`is_byok`／`cost_details`。
- **BYOK**：Bring Your Own Key。请求使用用户自己的上游 provider key 时 `is_byok === true`，OpenRouter 的 `cost` 只覆盖自己的服务费，还要加上游推理费。
- **totalFromProvider**：`cost` 里的布尔标记。`true` 表示 `cost.total` 是 provider 实报金额；`false` 表示目录估算；**键缺席也按 `false` 理解**（旧会话兼容）。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本。本包的 usage 与账单都是【内部，模型不可见】。
- **窄差（D 编号）**：已登记、无法或无需消除的形状差异；明细存于 `docs/map/`。

## 1. 请求主线与落点

### 1.1 pi 调用链

```text
streamAnthropic（anthropic-messages.ts）
  ├─ message_start（:704-727）
  │    ├─ 五字段无条件 || 0 写入（input/output/cacheRead/cacheWrite/cacheWrite1h）
  │    ├─ totalTokens = 四分量和
  │    └─ calculateCost(usageModel, usage) → cost.totalFromProvider = false
  └─ message_delta（:838-889）
       ├─ if (event.usage) 逐字段 != null 覆盖
       │    ├─ input / output / cacheRead / cacheWrite
       │    ├─ cacheWrite1h ← event.usage.cache_creation?.ephemeral_1h_input_tokens
       │    └─ reasoning ← output_tokens_details.thinking_tokens
       ├─ totalTokens = 四分量和
       ├─ calculateCost(usageModel, usage)
       └─ if (isOpenRouterModel(model) && event.usage)
            └─ applyOpenRouterReportedCost(usage, event.usage)

parseChunkUsage（openai-completions.ts:1509-1554）
  ├─ 四分量 + reasoning + totalTokens
  ├─ calculateCost(model, usage)
  └─ if (isOpenRouterModel(model)) applyOpenRouterReportedCost(usage, rawUsage)

calculateCost（models.ts:1236-1257）→ cost.totalFromProvider = false
applyOpenRouterReportedCost（openrouter-cost.ts:29-54）→ 有效账单时 coverage.totalFromProvider = true
combineUsage（coding-agent/src/core/usage-totals.ts:30-54）→ totalFromProvider 只有两侧都为 true 才为 true
```

**主线**是「车道把 provider 原始 usage 归一成 `Usage`，先做目录估算，再用 provider 实报金额覆盖 `cost.total`」。**支线**是会话层把多段 usage 相加时如何传播 `totalFromProvider`。

### 1.2 Java 调用链

```text
AnthropicMessagesApi.streamInternal
  └─ AnthropicEventMapper.mapEvent（AI/protocol/AnthropicEventMapper.java）
       ├─ isMessageStart → AnthropicUsageState.onMessageStart → reprice（CostCalculator）
       └─ isMessageDelta → AnthropicUsageState.onMessageDelta → reprice（CostCalculator）

OpenAICompletionsApi.streamInternal
  └─ OpenAICompletionsUsage.parse（顶层 chunk 与 choice.usage 两处）
       └─ CostCalculator.calculateCost

压缩摘要 / 会话合计
  └─ UsageCombiner.combine（agent-core/compaction/UsageCombiner.java）
  └─ EntryJsonCodec.decodeUsage（agent-core/session/jsonl/EntryJsonCodec.java）
```

### 1.3 OpenRouter 判定在哪一层

pi 的车道在 **usage 归一时**直接看 `model.provider` 与 `model.baseUrl`（`openrouter-cost.ts:24-26`）。Java 的车道构造器只有 `ApiOptions`，没有 provider 名；`ModelInfo` 也没有 provider 字段。

| 候选 | 能看到什么 | 问题 |
|---|---|---|
| 车道内只看 `options.baseUrl().contains("openrouter.ai")` | effective baseUrl | 漏掉 pi 的 `model.provider === "openrouter"` 分支；CLI 把 baseUrl 改成代理后仍应命中 |
| 在 `ApiOptions` 增加 `provider` 组件 | provider 名＋baseUrl | 会波及 docs/14 已登记的 11 个 `ApiOptions` 重建点，超出本包 |
| **`ConfigurableProvider.createApi` 预计算布尔放进 `extra`（选定）** | provider 名＋effective baseUrl | 直接 `new` 车道的测试夹具不会自动得到标记；这是非生产形状 |

【决定】沿用 docs/14 `extra["identityHeaders"]` 的先例：在 `ConfigurableProvider.createApi` 里把 `isOpenRouterModel(name(), effectiveBaseUrl)` 结果写进 `extra["openRouterModel"]`。车道读这个布尔，不自行推断。

【风险/代价】[内部，模型不可见] 生产入口（coding-agent、AiCli、evals）都经过 `provider.createApi`，行为与 pi 一致。直接构造车道的测试需要显式塞标记；新增的 provider 级测试会钉住「标记确实被预计算」。

## 2. pi 逐字契约

### 2.1 `Usage` 类型（`packages/ai/src/types.ts:459-487`）

```ts
export interface Usage {
	input: number;
	output: number;
	cacheRead: number;
	cacheWrite: number;
	/** Subset of `cacheWrite` written with 1h retention. Only Anthropic reports this split. */
	cacheWrite1h?: number;
	/**
	 * Reasoning/thinking tokens, when the provider reports them. This is a subset of
	 * `output`: `output` already includes these tokens. Set to a number (possibly 0) by
	 * providers that expose a reasoning breakdown; left undefined by providers that don't.
	 */
	reasoning?: number;
	totalTokens: number;
	cost: {
		/** Catalog-derived input cost. */
		input: number;
		/** Catalog-derived output cost. */
		output: number;
		/** Catalog-derived cache-read cost. */
		cacheRead: number;
		/** Catalog-derived cache-write cost. */
		cacheWrite: number;
		/** Best available total: provider-reported billed cost when supported, otherwise the catalog estimate. */
		total: number;
		/** True when `total` is the billed cost reported by the provider. False when it is a catalog estimate; absent also means false. */
		totalFromProvider?: boolean;
	};
}
```

### 2.2 `calculateCost` 尾部（`packages/ai/src/models.ts:1250-1256`）

```ts
	usage.cost.input = (rates.input / 1000000) * usage.input;
	usage.cost.output = (rates.output / 1000000) * usage.output;
	usage.cost.cacheRead = (rates.cacheRead / 1000000) * usage.cacheRead;
	usage.cost.cacheWrite = (rates.cacheWrite * shortWrite + rates.input * 2 * longWrite) / 1000000;
	usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite;
	usage.cost.totalFromProvider = false;
	return usage.cost;
```

### 2.3 Anthropic `message_start`（`packages/ai/src/api/anthropic-messages.ts:717-727`）

```ts
					// Capture initial token usage from message_start event
					// This ensures we have input token counts even if the stream is aborted early
					output.usage.input = event.message.usage.input_tokens || 0;
					output.usage.output = event.message.usage.output_tokens || 0;
					output.usage.cacheRead = event.message.usage.cache_read_input_tokens || 0;
					output.usage.cacheWrite = event.message.usage.cache_creation_input_tokens || 0;
					output.usage.cacheWrite1h = event.message.usage.cache_creation?.ephemeral_1h_input_tokens || 0;
					// Anthropic doesn't provide total_tokens, compute from components
					output.usage.totalTokens =
						output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite;
					calculateCost(usageModel, output.usage);
```

### 2.4 Anthropic `message_delta`（`packages/ai/src/api/anthropic-messages.ts:849-889`）

```ts
					// Only update usage fields if present (not null).
					// Preserves input_tokens from message_start when proxies omit it in message_delta.
					if (event.usage) {
						if (event.usage.input_tokens != null) {
							output.usage.input = event.usage.input_tokens;
						}
						if (event.usage.output_tokens != null) {
							output.usage.output = event.usage.output_tokens;
						}
						if (event.usage.cache_read_input_tokens != null) {
							output.usage.cacheRead = event.usage.cache_read_input_tokens;
						}
						if (event.usage.cache_creation_input_tokens != null) {
							output.usage.cacheWrite = event.usage.cache_creation_input_tokens;
						}
						// Vercel AI Gateway includes the TTL breakdown in deltas, though the SDK only types it on message_start.
						const cacheCreation = (
							event.usage as typeof event.usage & { cache_creation?: { ephemeral_1h_input_tokens?: number } }
						).cache_creation;
						if (cacheCreation?.ephemeral_1h_input_tokens != null) {
							output.usage.cacheWrite1h = cacheCreation.ephemeral_1h_input_tokens;
						}
						// Anthropic reports reasoning tokens as a subset of output tokens.
						const thinkingTokens = event.usage.output_tokens_details?.thinking_tokens;
						if (thinkingTokens != null) {
							output.usage.reasoning = thinkingTokens;
						}
					}
					// Anthropic doesn't provide total_tokens, compute from components
					output.usage.totalTokens =
						output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite;
					calculateCost(usageModel, output.usage);
					if (isOpenRouterModel(model) && event.usage) {
						// OpenRouter adds billing fields to streamed message_delta usage at runtime,
						// although its OpenAPI schema and the Anthropic SDK do not type those fields.
						applyOpenRouterReportedCost(
							output.usage,
							event.usage as typeof event.usage & OpenRouterUsageAccounting,
						);
					}
```

关键语义：

1. `cacheWrite1h` 在 delta 里**有就覆盖**；没有就保留 `message_start` 的值（可能是 0）。
2. `reasoning` 也只在 delta 里设；`message_start` 不读它。
3. `totalTokens` 重算与 `calculateCost` 在 `if (event.usage)` **块外**，无条件执行。
4. OpenRouter 账单只在 `event.usage` 存在时尝试；账单无效就保留目录估算。

### 2.5 `parseChunkUsage`（`packages/ai/src/api/openai-completions.ts:1540-1554`）

```ts
	const usage: AssistantMessage["usage"] = {
		input,
		output: outputTokens,
		cacheRead: cacheReadTokens,
		cacheWrite: cacheWriteTokens,
		reasoning: rawUsage.completion_tokens_details?.reasoning_tokens || 0,
		totalTokens: input + outputTokens + cacheReadTokens + cacheWriteTokens,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
	calculateCost(model, usage);
	if (isOpenRouterModel(model)) {
		applyOpenRouterReportedCost(usage, rawUsage as typeof rawUsage & OpenRouterUsageAccounting);
	}
	return usage;
```

### 2.6 OpenRouter 账单（`packages/ai/src/utils/openrouter-cost.ts:1-55`）

```ts
import type { Usage } from "../types.ts";

export interface OpenRouterUsageAccounting {
	/** Amount charged to the user's OpenRouter account. */
	cost: number | null;
	/** Whether OpenRouter used the user's upstream provider key. */
	is_byok: boolean;
	cost_details: {
		/** Total inference cost billed through the user's upstream provider account, when available. */
		upstream_inference_cost: number | null;
		/** Upstream cost for prompt processing, including any provider-side cache pricing. */
		upstream_inference_prompt_cost: number;
		/** Upstream cost for completion generation. */
		upstream_inference_completions_cost: number;
		/** Metered server-tool cost already included in OpenRouter's charged `cost`. */
		server_tool_cost?: number | null;
	} | null;
}

function isValidCost(value: unknown): value is number {
	return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

export function isOpenRouterModel(model: { provider: string; baseUrl: string }): boolean {
	return model.provider === "openrouter" || model.baseUrl.includes("openrouter.ai");
}

/** Replace the catalog total with OpenRouter's billed cost when the response contains complete, valid accounting. */
export function applyOpenRouterReportedCost(usage: Usage, reported: OpenRouterUsageAccounting): void {
	if (!isValidCost(reported.cost)) return;

	if (reported.is_byok === false) {
		usage.cost.total = reported.cost;
		usage.cost.totalFromProvider = true;
		return;
	}

	if (reported.is_byok === true) {
		const details = reported.cost_details;
		if (!details) return;
		let upstreamCost: unknown = details.upstream_inference_cost;
		if (upstreamCost === null) {
			if (
				!isValidCost(details.upstream_inference_prompt_cost) ||
				!isValidCost(details.upstream_inference_completions_cost)
			) {
				return;
			}
			upstreamCost = details.upstream_inference_prompt_cost + details.upstream_inference_completions_cost;
		}
		if (!isValidCost(upstreamCost)) return;
		usage.cost.total = reported.cost + upstreamCost;
		usage.cost.totalFromProvider = true;
	}
}
```

必须照抄的边界：

1. `cost` 无效（缺席／null／非数字／NaN／Infinity／负数）⇒ **整体不动**。
2. `is_byok === false` ⇒ 直接用 `reported.cost`。
3. `is_byok === true` 且 `cost_details` 缺席 ⇒ 不动。
4. `upstream_inference_cost` **显式为 null** ⇒ 用 prompt＋completions 两个分量求和；两个分量任一无效 ⇒ 不动。
5. `upstream_inference_cost` **键缺席** ⇒ 视为 `undefined`，`isValidCost` 失败 ⇒ 不动（**不能**走 null 的求和分支）。
6. `is_byok` 缺席或不是布尔 ⇒ 两个 `if` 都不命中 ⇒ 不动。

### 2.7 `combineUsage`（`packages/coding-agent/src/core/usage-totals.ts:30-54`）

```ts
/** Sum of two usages, keeping the optional token splits when either side reports them. */
export function combineUsage(first: Usage, second: Usage): Usage {
	return {
		input: first.input + second.input,
		output: first.output + second.output,
		cacheRead: first.cacheRead + second.cacheRead,
		cacheWrite: first.cacheWrite + second.cacheWrite,
		...(first.cacheWrite1h !== undefined || second.cacheWrite1h !== undefined
			? { cacheWrite1h: (first.cacheWrite1h ?? 0) + (second.cacheWrite1h ?? 0) }
			: {}),
		...(first.reasoning !== undefined || second.reasoning !== undefined
			? { reasoning: (first.reasoning ?? 0) + (second.reasoning ?? 0) }
			: {}),
		totalTokens: first.totalTokens + second.totalTokens,
		cost: {
			input: first.cost.input + second.cost.input,
			output: first.cost.output + second.cost.output,
			cacheRead: first.cost.cacheRead + second.cost.cacheRead,
			cacheWrite: first.cost.cacheWrite + second.cost.cacheWrite,
			total: first.cost.total + second.cost.total,
			// A sum is provider-reported only when every part is.
			totalFromProvider: first.cost.totalFromProvider === true && second.cost.totalFromProvider === true,
		},
	};
}
```

注意：结果里的 `totalFromProvider` **永远是布尔**（`false` 也显式写），不是缺席。

### 2.8 pi 测试矩阵

| 来源 | # | 场景 | 关键断言 |
|---|---:|---|---|
| `packages/ai/test/anthropic-cache-write-1h-cost.test.ts:62-82` | 1 | 1h 拆分在 `message_start` | `cacheWrite1h = 400_000`；cacheWrite 成本 7.75 |
| `:84-110` | 2 | 1h 拆分只在 `message_delta`（Vercel AI Gateway） | `cacheWrite = 6535`、`cacheWrite1h = 6535`；成本按 2×input |
| `:112-122` | 3 | 无拆分回落 5m 价 | `cacheWrite1h ?? 0 === 0` |
| `packages/ai/test/anthropic-sse-parsing.test.ts:142-176` | 4 | OpenRouter Anthropic 端点实报账单 | `cost.total = 0.004`、`totalFromProvider = true`；分量和 ≠ total |
| `packages/ai/test/openrouter-cost.test.ts:11-23` | 5 | OpenRouter 模型判定 | 内置 provider 与 custom provider 的 openrouter.ai baseUrl 命中；普通 custom 不命中 |
| `:25-77` | 6 | 账单四种边界 | 非 BYOK；BYOK 加上游；上游总额缺席用两分量；账目不完整保留目录合计 |
| `packages/ai/test/openai-completions-provider-stream-event.test.ts:100-155` | 7 | OpenAI-completions 账单 | 非 BYOK 的 `cost` 全零但 `total = 0.004`；BYOK `total ≈ 0.0032` |
| `packages/coding-agent/test/usage-totals.test.ts:20-28` | 8 | 合计传播 | 两侧都 true ⇒ true；任一缺失／false ⇒ false |

## 3. Java 现状与已证实缺口

### 3.1 `Usage.Cost` 没有 `totalFromProvider`

`AI/Usage.java:36-47` 的 `Cost` 只有五个组件：

```java
public record Cost(
    double input,
    double output,
    double cacheRead,
    double cacheWrite,
    double total
) {
```

pi 的 `types.ts:485` 是 `totalFromProvider?: boolean`。Java 的目录估算结果无法标记「这是目录价还是实报价」。

### 3.2 `CostCalculator` 不写标记

`AI/model/CostCalculator.java:74-75` 只返回五字段成本。pi 的 `models.ts:1255` 在每次目录估算后写 `totalFromProvider = false`。

### 3.3 Anthropic `message_start` 已对齐，delta 缺 `cacheWrite1h`

`AI/protocol/AnthropicUsageState.java:69-73` 的 `onMessageStart` 已经按 pi `:717-727` 写五字段（含 1h 拆分），并调用 `reprice()`。

`AI/protocol/AnthropicUsageState.java:85-102` 的 `onMessageDelta` 只覆盖 input／output／cacheRead／cacheWrite／reasoning，**没有** `cacheWrite1h` 参数，且 `AI/protocol/AnthropicEventMapper.java:221-226` 也不从 delta 的 `cache_creation` 取 1h 值。

现有测试 `AnthropicMessagesApiUsageTest.oneHourCacheSplitIsOnlyCapturedFromMessageStart`（`:107-119`）还**钉着旧行为**：断言 delta 的 1h 拆分被忽略。本包必须翻转这条测试。

### 3.4 OpenAI-completions 的 usage 字段已对齐，但没有账单

`AI/protocol/OpenAICompletionsUsage.java:57-76` 的四分量、`reasoning`、`totalTokens` 与减法钳位都对齐 pi `:1521-1547`；缺口只有 `:1549-1552` 的两步：

1. `calculateCost` 后没有 `totalFromProvider = false`；
2. 没有 `if (isOpenRouterModel(model)) applyOpenRouterReportedCost`。

调用点 `OpenAICompletionsApi.java:255-257`、`:266-270`、`:364-367` 都走同一个 `parse`，改一处即可覆盖三条路径。

### 3.5 车道拿不到 provider 名

`AI/api/ApiOptions.java` 没有 provider 组件。车道只有 effective `baseUrl`（`OpenAICompletionsApi.java:90,108-110`；`AnthropicMessagesApi.java:185-186`），不能完整复刻 `provider === "openrouter" || baseUrl.includes("openrouter.ai")`。

### 3.6 `UsageCombiner` 会丢标记

`agent-core/compaction/UsageCombiner.java:22-36` 重建 `Usage.Cost` 时只写五个字段。若 `Cost` 加组件而不改这里，两段摘要的合计会把 `totalFromProvider` 静默丢掉（B156 同族：record 重建点不只构造点）。

### 3.7 会话 JSONL 的两条读路径

- `agent-core/session/jsonl/EntryJsonCodec.java:212-240` **手工**逐字段解码 usage 与 cost；必须显式读 `totalFromProvider`。
- `agent-core/session/jsonl/MessageJsonCodec.java:289-299` 用 `SessionJson.mapper().treeToValue`；Jackson 自动映射新组件，缺席 ⇒ `null`。
- 写侧走 `SessionJson` 的全局 `NON_NULL`（`SessionJson.java:createMapper`），`null` 键自动省略。

### 3.8 主代码里的 record 重建点（全量清单）

```text
AI/Usage.java:45            Cost.zero()
AI/model/CostCalculator.java:74-75
agent-core/compaction/UsageCombiner.java:30-35
agent-core/session/jsonl/EntryJsonCodec.java:240
```

测试里还有约 20 处 `new Usage.Cost(...)` 五参构造。本包用**五参便捷构造器**保持它们零改签；生产重建点逐点显式处理。

### 3.9 现有测试钉住的旧语义

| 文件 | 现有断言 | 本包动作 |
|---|---|---|
| `AnthropicMessagesApiUsageTest.java:106-119` | delta 不更新 `cacheWrite1h` | 改写为「delta 有就覆盖」＋ 0 值覆盖 |
| `AnthropicMessagesApiUsageTest.java:163-175` | 只钉 `message_start` 的 1h 计价 | 追加 delta-only 的 1h 计价用例 |
| `OpenAICompletionsApiUsageTest.java` | 只钉 token 归一 | 追加账单四分支用例 |
| `CostCalculatorTest.java` | 只钉组件金额 | 追加 `totalFromProvider = false` |
| `PiV3UsageEntryTest.java` | 只钉 JSON 读写 | 追加缺席／false／true 三态 |

## 4. 拟改设计

### 4.1 `Usage.Cost` 增加可空布尔组件

`AI/Usage.java`：

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Cost(
    double input,
    double output,
    double cacheRead,
    double cacheWrite,
    double total,
    Boolean totalFromProvider   // null = 键缺席；false = 目录估算；true = provider 实报
) {
    /** 五参便捷构造：旧调用点零改签，缺席 ≙ pi 的 undefined。 */
    public Cost(double input, double output, double cacheRead, double cacheWrite, double total) {
        this(input, output, cacheRead, cacheWrite, total, null);
    }

    public static Cost zero() {
        return new Cost(0, 0, 0, 0, 0);
    }
}
```

【决定】用可空 `Boolean`，不用 primitive `boolean`。pi 的键是 optional；旧会话与流起点没有键。`null` 与 `false` 语义等价，但保留缺席才能在 JSON 往返中不改写旧文件。

【风险/代价】[内部，模型不可见] 新增组件会改变 `Cost` 的规范构造签名。五参便捷构造器保证约 20 处测试与 `zero()` 零改签；生产重建点（§3.8）逐一显式更新，实施后必须重新 `grep -rn "new Usage.Cost(" --include=*.java .` 核对。

### 4.2 `CostCalculator` 写 `false`

```java
return new Usage.Cost(costInput, costOutput, costCacheRead, costCacheWrite,
    costInput + costOutput + costCacheRead + costCacheWrite, false);
```

与 pi `models.ts:1255` 一字不差：目录估算结果显式带 `false`。

### 4.3 新工具类 `AI/utils/OpenRouterBilling.java`

```java
public final class OpenRouterBilling {

    /** Internal {@code ApiOptions.extra} key: provider 预计算的 OpenRouter 判定。 */
    public static final String EXTRA_KEY = "openRouterModel";

    private static final String OPENROUTER_TOKEN = "openrouter.ai";

    private OpenRouterBilling() {}

    /** pi {@code isOpenRouterModel}（{@code :24-26}）。 */
    public static boolean isOpenRouterModel(String provider, String baseUrl) {
        return "openrouter".equals(provider)
            || (baseUrl != null && baseUrl.contains(OPENROUTER_TOKEN));
    }

    /** 车道读 provider 预计算的判定；键缺席 ⇒ false。 */
    public static boolean isOpenRouterModel(ApiOptions options) {
        return options != null
            && Boolean.TRUE.equals(options.extra().get(EXTRA_KEY));
    }

    /**
     * pi {@code applyOpenRouterReportedCost}（{@code :29-54}）。
     *
     * <p>Java 的 {@link Usage} 不可变 ⇒ 返回新对象；无有效账单时原样返回。</p>
     */
    public static Usage applyReportedCost(Usage usage, Map<String, Object> reported) {
        ...
    }
}
```

`applyReportedCost` 的逐行对应：

1. `reported == null` ⇒ 原样返回。
2. `reported.get("cost")` 通过 `isValidCost`（Number、有限、≥ 0），否则原样返回。
3. `is_byok`：`Boolean.FALSE` ⇒ 只把 `total` 换成 `reported.cost`，`totalFromProvider = true`。
4. `Boolean.TRUE` ⇒ 取 `cost_details`；不是 Map ⇒ 原样返回。
5. `details.containsKey("upstream_inference_cost")` 为 **false** ⇒ 原样返回（≙ pi 的 `undefined` 不满足 `=== null`）。
6. 键存在且值为 **null** ⇒ 用 `upstream_inference_prompt_cost + upstream_inference_completions_cost`；两分量任一无效 ⇒ 原样返回。
7. 键存在且值非 null ⇒ 本身必须是有效成本，否则原样返回。
8. 有效 ⇒ `total = reported.cost + upstreamCost`，`totalFromProvider = true`。
9. 其余 `is_byok`（缺席或非布尔）⇒ 原样返回。

> ⚠️ 第 5 与第 6 条必须用 `containsKey` 区分「键缺席」和「显式 null」。Jackson 把两者都解成 `Map.get() == null`，只看 `get()` 会错走求和分支。

【风险/代价】[内部，模型不可见] 无新增依赖；数值比较用 `double` 与 pi 的 JS number 等价（本包金额远小于 2^53）。

### 4.4 `ConfigurableProvider` 预计算标记

在现有 `enrichWithIdentity`（`AI/provider/ConfigurableProvider.java:121-128`）里追加一项：

```java
var extra = new LinkedHashMap<String, Object>(options.extra());
extra.put(IdentityHeaders.EXTRA_KEY, headers);
if (OpenRouterBilling.isOpenRouterModel(name(), options.baseUrl())) {
    extra.put(OpenRouterBilling.EXTRA_KEY, Boolean.TRUE);
}
return new ApiOptions(...);  // 八参构造，identity/telemetry 保真
```

【决定】与 identity 头同点、同一次预计算；不在车道里重复推断。

【风险/代价】[内部，模型不可见] 只写 `true`、缺席即 `false`，与 `cacheRetention`／`sessionId` 的「键缺席 ≙ undefined」口径一致。`OpenRouterProvider.createApi` 先 `pinnedOptions` 再 `super.createApi`，因此预计算看到的是 pin 后的 effective baseUrl。

### 4.5 `AnthropicUsageState` 接受 delta 的 1h 拆分与账单

新增构造器重载，保留旧的单参构造器给测试：

```java
AnthropicUsageState(ModelInfo model) {
    this(model, false);
}

AnthropicUsageState(ModelInfo model, boolean openRouterBilling) {
    this.model = model;
    this.openRouterBilling = openRouterBilling;
}
```

`onMessageDelta` 签名变为：

```java
void onMessageDelta(Long input, Long output, Long cacheRead, Long cacheWrite,
                    Long cacheWrite1h, Long reasoning,
                    Map<String, Object> reportedBilling)
```

逻辑：

1. 五字段各自 `null ⇒ 保留`；`0` 是合法值，必须覆盖（含 `cacheWrite1h`）。
2. `cacheWrite1h` 直接从参数取值，不再沿用旧值。
3. `reprice()` 照旧：重算 `totalTokens` ＋ 目录估算（cost 带 `false`）。
4. `reprice()` 之后：

```java
if (openRouterBilling && reportedBilling != null) {
    usage = OpenRouterBilling.applyReportedCost(usage, reportedBilling);
}
```

【决定】账单在 `reprice` 之后应用，与 pi `:878-888` 的次序一致：目录分量与合计先成形，再让 provider 实报金额覆盖 `total`。

【风险/代价】[内部，模型不可见] `cacheWrite1h` 一旦由 delta 覆盖，`CostCalculator` 的 2×input 计价会自动跟着变；现有 `message_start` 用例不受影响。

### 4.6 `AnthropicEventMapper` 读取 delta 的原始 usage

`isMessageDelta` 分支改为：

```java
var deltaEvent = event.asMessageDelta();
var deltaUsage = deltaEvent._usage().asKnown().orElse(null);  // 用 _usage()，缺失不抛

usageState.onMessageDelta(
    deltaUsage == null ? null : deltaUsage._inputTokens().asKnown().orElse(null),
    deltaUsage == null ? null : deltaUsage._outputTokens().asKnown().orElse(null),
    deltaUsage == null ? null : deltaUsage._cacheReadInputTokens().asKnown().orElse(null),
    deltaUsage == null ? null : deltaUsage._cacheCreationInputTokens().asKnown().orElse(null),
    deltaCacheWrite1h(deltaUsage),
    deltaUsage == null ? null
        : deltaUsage.outputTokensDetails().flatMap(d -> d._thinkingTokens().asKnown()).orElse(null),
    deltaUsage == null ? null : rawUsageMap(deltaUsage));
```

两个私有辅助：

```java
/** delta 的 cache_creation.ephemeral_1h_input_tokens；键缺席或显式 null ⇒ null。 */
private static Long deltaCacheWrite1h(MessageDeltaUsage usage) {
    if (usage == null) return null;
    Object raw = usage._additionalProperties().get("cache_creation");
    if (raw == null) return null;
    Object converted = raw.convert(Object.class);
    if (!(converted instanceof Map<?, ?> map)) return null;
    Object oneHour = map.get("ephemeral_1h_input_tokens");
    return oneHour instanceof Number n ? n.longValue() : null;
}

/** SDK 丢弃的扩展键（cost／is_byok／cost_details 等）还原成 pi 键名普通 Map。 */
private static Map<String, Object> rawUsageMap(MessageDeltaUsage usage) {
    var out = new LinkedHashMap<String, Object>();
    usage._additionalProperties().forEach((key, value) -> out.put(key, value.convert(Object.class)));
    return out;
}
```

【决定】用 `_usage()` 而不是 `usage()`。后者是 SDK 的**非 Optional 访问器**，字段缺席时行为不保证；pi 的 `event.usage` 是可缺席的。

【风险/代价】[内部，模型不可见] `convert(Object.class)` 失败时按扩展键缺失处理；本包测试会覆盖带账单与不带账单两种报文。

### 4.7 OpenAI-completions 两处调用加账单

`OpenAICompletionsUsage.parse` 增加布尔参数（保留旧签名委托 `false`）：

```java
static Usage parse(CompletionUsage raw, ModelInfo model) { ... }
static Usage parse(CompletionUsage raw, ModelInfo model, boolean openRouterModel) {
    return parse(rawOf(raw), model, openRouterModel);
}

static Usage parse(Map<String, Object> raw, ModelInfo model, boolean openRouterModel) {
    ...
    Usage usage = model == null ? usage0
        : usage0.withCost(CostCalculator.calculateCost(model.pricing(), usage0));
    return openRouterModel
        ? OpenRouterBilling.applyReportedCost(usage, raw)
        : usage;
}
```

`OpenAICompletionsApi` 构造器加字段：

```java
this.openRouterModel = OpenRouterBilling.isOpenRouterModel(options);
```

三处调用（`:257`、`:270`、`:366`）传入该字段。

【风险/代价】[内部，模型不可见] `choice.usage` 回退路径的原始 Map 由 `choiceUsage(...)` 构造，已包含 `cost`／`is_byok`／`cost_details` 扩展键；与顶层路径共用同一个应用函数。

### 4.8 `UsageCombiner` 传播标记

```java
new Usage.Cost(
    first.cost().input() + second.cost().input(),
    ...,
    first.cost().total() + second.cost().total(),
    Boolean.TRUE.equals(first.cost().totalFromProvider())
        && Boolean.TRUE.equals(second.cost().totalFromProvider()));
```

结果永远是 bool（`false` 显式），与 pi `:51` 的 `first === true && second === true` 等价。

### 4.9 会话 JSONL 解码补字段

`EntryJsonCodec.decodeUsage`（`:219-240`）：

```java
Boolean totalFromProvider = null;
if (cost != null && cost.isObject()
        && cost.has("totalFromProvider") && !cost.get("totalFromProvider").isNull()) {
    totalFromProvider = cost.get("totalFromProvider").asBoolean();
}
new com.pijava.ai.Usage.Cost(costInput, costOutput, costCacheRead, costCacheWrite,
    costTotal, totalFromProvider);
```

【决定】缺席保持 `null` 而不是 `false`：旧文件往返不改写。pi 文档（`packages/ai/README.md:236`）明确「缺席也按 false 理解」。

【风险/代价】[内部，模型不可见] `MessageJsonCodec` 走 Jackson，无需手改；写侧全局 `NON_NULL` 自动省略 `null`，`false`／`true` 正常写出。

### 4.10 改动文件清单与预计规模

| 模块 | 文件 | 动作 | 预计行数 |
|---|---|---|---:|
| ai | `AI/Usage.java` | `Cost` 加组件＋便捷构造＋`@JsonInclude` | +25 |
| ai | `AI/model/CostCalculator.java` | 返回 `false` | +1 |
| ai | `AI/utils/OpenRouterBilling.java` | 新类：判定＋账单 | 150–190 |
| ai | `AI/provider/ConfigurableProvider.java` | 预计算标记 | +8 |
| ai | `AI/protocol/AnthropicUsageState.java` | 构造器／delta 签名／账单应用 | +35 |
| ai | `AI/protocol/AnthropicEventMapper.java` | `_usage()`＋两个辅助 | +40 |
| ai | `AI/protocol/AnthropicMessagesApi.java` | 传标记进 usageState | +2 |
| ai | `AI/protocol/OpenAICompletionsUsage.java` | 账单 | +15 |
| ai | `AI/protocol/OpenAICompletionsApi.java` | 字段＋三调用点 | +6 |
| agent-core | `agent/compaction/UsageCombiner.java` | 传播标记 | +4 |
| agent-core | `agent/session/jsonl/EntryJsonCodec.java` | 解码字段 | +6 |
| ai 测试 | `AnthropicMessagesApiUsageTest.java` | 翻转 T4＋新增账单/1h 用例 | +80 |
| ai 测试 | `OpenAICompletionsApiUsageTest.java` | 新增账单分支 | +70 |
| ai 测试 | `OpenRouterBillingTest.java`（新） | pi 单测 5–8 的移植 | 150–190 |
| ai 测试 | `CostCalculatorTest.java` | 断言 `false` | +5 |
| agent-core 测试 | `UsageCombinerTest.java`（新或并入现有） | 传播矩阵 | 60–80 |
| agent-core 测试 | `PiV3UsageEntryTest.java` | 三态往返 | +30 |

## 5. 测试矩阵（实施时逐条 RED→GREEN）

| # | 层 | 测试 | 断言 |
|---:|---|---|---|
| 1 | ai 单元 | `OpenRouterBillingTest.isOpenRouterModel` | `("openrouter", 任意 baseUrl)`⇒true；custom＋`openrouter.ai`⇒true；custom＋普通 URL⇒false |
| 2 | ai 单元 | 非 BYOK | `cost=0` ⇒ `total=0`、`totalFromProvider=true` |
| 3 | ai 单元 | BYOK 带上游总额 | `cost=0.1`＋`upstream=0.9` ⇒ `total=1`、true |
| 4 | ai 单元 | BYOK 上游总额显式 null | prompt 0.6＋completions 0.3 ⇒ `total≈1`、true |
| 5 | ai 单元 | 上游总额键缺席 | 原样返回，`total` 与 `totalFromProvider` 都不变 |
| 6 | ai 单元 | 账目不完整 | `cost=null` 或 `is_byok=true`＋`cost_details=null` ⇒ 保留目录合计 |
| 7 | ai 集成 | Anthropic delta-only 1h | `cacheWrite1h = 6535`；成本 = `2×input×6535/1M` |
| 8 | ai 集成 | Anthropic delta 1h = 0 | 把首帧非零拆分覆盖成 0；成本回落 5m 价 |
| 9 | ai 集成 | Anthropic OpenRouter 账单 | delta usage 带 `cost/is_byok/cost_details` ⇒ `total=0.004`、true；分量和 ≠ total |
| 10 | ai 集成 | Anthropic 非 OpenRouter 同报文 | 不应用账单，保留目录合计、`totalFromProvider=false` |
| 11 | ai 集成 | OpenAI-completions 非 BYOK | `cost` 全零、`total=0.004`、true |
| 12 | ai 集成 | OpenAI-completions BYOK | `total≈0.0032`、true |
| 13 | ai 单元 | `CostCalculator` | 返回值 `totalFromProvider=false` |
| 14 | agent-core 单元 | `UsageCombiner` | `(true,true)⇒true`；`(true,false)⇒false`；`(true,缺席)⇒false`；`(缺席,缺席)⇒false` |
| 15 | agent-core 集成 | JSONL 三态往返 | 缺席 ⇒ 键省略；false ⇒ 显式 false；true ⇒ 显式 true |
| 16 | provider 集成 | `ConfigurableProvider` 预计算 | `openrouter` provider ⇒ extra 有 true；普通 provider ⇒ 键缺席 |
| 17 | 回归 | 现有 `AnthropicMessagesApiUsageTest` | 除改写的那条外全部保持绿；`message_start` 1h 行为不变 |

模型可见面：本包**无**新增或改动的模型可见文本；错误文案、提示、工具声明都不变。测试只钉内部数据形状。

## 6. 验证命令与门

1. 模块快速验证：

   ```bash
   mvn test -pl pi-java-ai -am
   mvn test -pl pi-java-agent-core -am
   ```

2. 完整收口（docs/08 §8）：

   ```bash
   mvn clean verify
   ```

   要求：测试零失败、零错误；Checkstyle 零错误零警告；SpotBugs 零新增；日志不得只放 `target/`。

3. 重建点复核：

   ```bash
   grep -rn "new Usage.Cost\|new com.pijava.ai.Usage.Cost" --include=*.java . | grep -v /target/
   grep -rn "new ApiOptions(" --include=*.java pi-java-ai pi-java-coding-agent | grep -v /target/
   ```

## 7. 风险与裁决

1. **`Cost` 新增组件**
   【场景】`Usage.Cost` 是 record，新增组件会改变规范构造器签名。
   【决定】加可空 `Boolean totalFromProvider`，并提供五参便捷构造器；生产重建点四处逐点显式更新。
   【风险/代价】[内部，模型不可见] 五参构造器会让「从旧 Cost 重建新 Cost」的代码静默丢标记。实施后按 §6 第 3 条全仓 grep，逐个确认。

2. **`totalFromProvider=false` 会出现在会话 JSON**
   【场景】pi 的 `calculateCost` 显式写 `false`；旧 Java 输出没有这个键。
   【决定】照 pi 写 `false`；只有 `Cost.zero()` 与旧文件解码保持 `null`。
   【风险/代价】[内部，模型不可见] 新会话的 assistant message 与压缩条目多一个 `"totalFromProvider": false`。这是向 pi 收敛，不是回归；L4 会话重测时以此为准。

3. **OpenRouter 判定依赖 provider 预计算**
   【场景】Java 车道没有 provider 名。
   【决定】`ConfigurableProvider.createApi` 预计算到 `extra`；车道只读布尔。
   【风险/代价】[内部，模型不可见] 直接构造车道的测试/夹具不带标记 ⇒ 账单不生效。生产入口全部经过 `provider.createApi`。此形状差异与 docs/14 D204 同族，不登记模型可见差异。

4. **delta usage 可缺席**
   【场景】SDK 的 `RawMessageDeltaEvent.usage()` 是非 Optional 访问器。
   【决定】改用 `_usage().asKnown().orElse(null)`；缺失时只重算合计与目录估算，不碰账单。
   【风险/代价】[内部，模型不可见] 与 pi 的 `if (event.usage)` 门一致；现有报文的帧数不变。

5. **`cache_creation` 是 SDK 未建模的扩展键**
   【场景】Anthropic SDK 的 `MessageDeltaUsage` 只有 typed 的 `cache_creation_input_tokens`，没有 `cache_creation` 对象。
   【决定】从 `_additionalProperties()` 读，`ephemeral_1h_input_tokens` 用 `Number.longValue()`。
   【风险/代价】[内部，模型不可见] 若 SDK 丢弃未知键，测试 #7 会先红；这是实施第一步要验证的事实，不能假定。

6. **BYOK 的「键缺席 vs 显式 null」**
   【场景】pi 用 `=== null` 决定是否回落到两个分量求和；键缺席走 `isValidCost(undefined)` 的无操作分支。
   【决定】Java 用 `containsKey` 区分两者，不只看 `Map.get()`。
   【风险/代价】[内部，模型不可见] 差异只影响账目不完整时的保留行为，不产生错误的实报金额。

7. **算术与浮点**
   【场景】pi 的金额是 JS number；Java 用 `double`。
   【决定】照用 `double`；测试用 `isCloseTo(…, within(1e-12))` 或 `isCloseTo(…, 1e-10)`。
   【风险/代价】[内部，模型不可见] 本包金额远低于 2^53，无精度语义差。

## 8. 地图与账面影响

实施并验证通过后：

1. `docs/map/02-ai.md` 三行翻为「对齐」：
   - #53（权重 3）
   - #59（权重 2）
   - #107（权重 2）
2. 地图头部状态追加：`B267 第一批（#53/#59/#107）已按 docs/15 闭环（日期）`。
3. 汇总口径：
   - 加权：199.0/240＝82.92% → **206.0/240＝85.83%**
   - 未加权：106.5/131＝81.3% → **109.5/131＝83.6%**
   - 对齐／存疑／缺失：99／15／17 → **102／15／14**
4. `docs/04`：B267 仍开放；描述改为「provider wire、retry／overflow、OAuth／device／env、pi-ai CLI 权限、Faux 余量与 deferred fetch 未对齐；usage／billing 已闭环」。
5. `docs/06`：L1 行记录本包闭环，下一文档号 `docs/16`。
6. `docs/08` §9 L1 行同步。

## 9. 实施步骤（获批后）

1. **RED**：先加 `OpenRouterBillingTest` 的纯函数用例与 `Usage` 三态 JSON 用例；确认类型/断言红。
2. **GREEN-1**：`Usage.Cost` 加组件＋便捷构造；`CostCalculator` 写 `false`；`UsageCombiner` 传播；`EntryJsonCodec` 解码。
3. **GREEN-2**：`OpenRouterBilling`＋`ConfigurableProvider` 预计算＋`OpenAICompletionsUsage`/`OpenAICompletionsApi`。
4. **GREEN-3**：`AnthropicEventMapper` 的 `_usage()`＋`cache_creation`＋账单；`AnthropicUsageState` 签名；`AnthropicMessagesApi` 传标记。
5. **测试翻转**：改写 `AnthropicMessagesApiUsageTest` T4；补集成用例。
6. 模块验证 → 完整 `mvn clean verify` → 回填 docs/04、docs/06、docs/08、map/02 与本稿 §10。
7. Commit 建议（每步可独立编译）：
   - `feat(ai): carry provider-reported cost flag in usage`
   - `feat(ai): apply OpenRouter reported cost in chat lanes`
   - `feat(agent-core): preserve usage totalFromProvider in session totals`
   - `docs(ai): close docs/15 usage billing package`

## 10. 闭环记录（实施后回填）

- 实施 HEAD：`ef38326b`（usage flag／OpenRouter 账单工具类／provider 预计算）＋`18cef3da`（Anthropic／OpenAI 车道应用账单）＋`1283f3e7`（agent-core 会话合计传播）。
- `mvn clean verify`（2026-11-03，`verify-docs15-rerun.log`）：**5,987 测试，0 失败／0 错误／29 跳过**；15 个模块 Checkstyle 0 violations；SpotBugs 13 个分析模块 `BugInstance size is 0`。
- 地图翻行：#53（权重 3）、#59（权重 2）、#107（权重 2）→ 对齐；L1 账面 **206.0/240＝85.83%**，未加权 109.5/131＝83.6%，对齐／存疑／缺失 102／15／14。
- 新窄差（D 编号）：无。`totalFromProvider` 的缺席语义与 pi 的 optional 键逐条对应；`OpenRouterBilling` 的「键缺席≠显式 null」有专门测试。
- 与计划的偏差：
  1. §0.3 原预测「新增单元域 9/16 不变」有误——#107 属于「新锚点新增计分单元」域；实际为 **11/16＝68.8%**，usage 域同时到 **21/21＝100%**。已在本稿 §0.3 与地图修正。
  2. 第一次全量 verify 在 `PiTuiAppDoubleEscapeTest.escapeBeyondWindowDoesNotOpen` 上出现一次 `ConcurrentModificationException`（TUI 会话条目读取竞态）；单测复跑绿、第二次全量 verify 绿，判定为既有 flaky，与本包改动无关。
  3. 为满足 `OpenAICompletionsApi.java` 的 500 行硬闸门，字段注释压缩为单行；无语义变化。
