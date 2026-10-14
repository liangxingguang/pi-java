# 13 ai 批 4 详稿：thinking/effort 通道（B128 thinkingBudgets 选项通道 ＋ B99 supportsMidConvoEffort 全家桶 ＋ B110 providerThinkingLevel）

> 状态：**已闭环（2026-10-14）**。三批提交：① `9368bfe1`/`941e6898`/`b1c38a0f`
> B128 三层通道＋beta 通道修复；② `aa29df66` compat 字段链＋managed 请求体＋
> providerThinkingLevel 盖章/JSONL/终局帧/pi-messages 透传；③ `ae9fc831`
> effort marker 历史/队尾重建。台账 B99/B110/B128 销号，加权 **92.2% 不变**。
> **裁决①探针结论（2026-10-14，SDK 2.66.0 loopback 抓包）**：body 的 `betas`
> 未知键**不会**被 SDK 提升为 `anthropic-beta` 请求头（请求头集合无该头，键只留在 JSON 体里）
> ⇒ 全部 beta 改走 `putAdditionalHeader("anthropic-beta", 逗号连接)`；既有 4 个 beta
> （interleaved/inline-tools/fine-grained/fallback）此前确实一直没真正生效，本批一并修正。
> 固化夹具＝`AnthropicBetaHeaderProbeTest`。
> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）；pi 证据一律 `git show ea448f454:<path>`，不读工作树。
> 上游判据：`bin pi` 主流发布版行为。
> 路线：[`08`](08-ai-layer-alignment-roadmap.md) §3 批 4＝台账 **B128 ＋ B99 ＋ B110**。
> 前序：[`12`](12-ai-batch3b-model-scope-design.md)（批 3b 已闭环；目录 9 个 managed 模型的
> `supportsMidConvoEffort:true` 已随批 3a 字节数据在资源里，当前被 `ignoreUnknown` 跳过）。
> SDK：anthropic-java **2.66.0**（`pi-java-bom/pom.xml:30`；注意多处旧注释写的 2.52.0 已过时）。

---

## 0. 范围一览

| 条目 | pi 行为 | 本批动作 |
|---|---|---|
| **B128** thinkingBudgets 选项通道 | `settings.json` 四段（minimal/low/medium/high，全 optional 无默认）→ `new Agent({thinkingBudgets})` → `AgentLoopConfig extends SimpleStreamOptions` → agent-loop `...config` 展开 → 4 条车道消费 | ai/agent-core/coding-agent 三层打通通道；消费点 3 个（anthropic adjust、openai-completions 顶层预算字段、google custom 优先支） |
| **B99** supportsMidConvoEffort 全家桶 | compat 字段（目录生成期谓词烘入 9 个 Claude 5 模型）→ ① 两 beta 无条件挂 ② thinking 恒为 adaptive＋`block_binding:{prefix_mismatch_behavior:"drop_block"}`、top-level `output_config:{effort:"high"}` 恒发（与是否开思考无关）③ 历史 assistant 前插 effort marker、队尾插当前 effort marker ④ 禁 temperature ⑤ 产出消息盖章 | compat 解析链补字段；请求构造 5 处改动；marker 重建落 `AnthropicRequestBuilder` |
| **B110** providerThinkingLevel | 唯一生产者＝anthropic-messages 车道（`flag ? options.effort ?? "high" : undefined`），start 帧即携带、消息整体持久化；唯一语义消费者＝B99 的 marker 重建；pi-messages 终局帧透传；proxy 终局帧透传 | 两层 AssistantMessage 加字段＋盖章缝＋JSONL round-trip＋JSON 终局线格式＋pi-messages 透传 |

**三条耦合关系（为什么必须同批）**：

1. B110 的字段是 B99 marker 重建的**输入**——历史消息上没有 `providerThinkingLevel`，marker 就插不出来；
   pi 的 e2e 夹具证实缺 marker 的签名思考块真实 API 回 400（`Invalid signature`）。
2. B99 managed 分支的盖章值（B110）就是 active marker 的 effort，同一个计算 `options.effort ?? "high"`。
3. B128 的 budget 通道对 managed 模型**不可达**（forceAdaptive 分支忽略 budget），但同属 thinking 面、
   共用 `AnthropicThinking.resolve` 分流，拆两次会反复改同一函数。

**加权影响**：三项均为台账行为项（B 编号），不占 map/ 加权单元 ⇒ **闭环不改百分比**
（92.2% 不变；同批 2／批 3b 先例）。销号时回填 docs/04、docs/06、docs/08、map/02 头部。

---

## 1. pi 取证（逐字）

### 1.1 B128：thinkingBudgets

#### 1.1.1 类型与默认（`packages/ai/src/types.ts:135-141`、`:368-377`）

```ts
/** Token budgets for each thinking level (token-based providers only) */
export interface ThinkingBudgets {
	minimal?: number;
	low?: number;
	medium?: number;
	high?: number;
}
// ──
/** Custom token budgets for thinking levels (token-based providers only) */
thinkingBudgets?: ThinkingBudgets;
```

只有 **4 档**（无 off/xhigh/max）；全 optional；接口无默认值。

#### 1.1.2 核心纯函数（`packages/ai/src/api/simple-options.ts:67-108`）

```ts
export const MIN_ANSWER_TOKENS = 1024;

export const DEFAULT_THINKING_BUDGETS: ThinkingBudgets = {
	minimal: 1024,
	low: 2048,
	medium: 8192,
	high: 16384,
};

export function clampReasoning(effort: ThinkingLevel | undefined): Exclude<ThinkingLevel, "xhigh" | "max"> | undefined {
	return effort === "xhigh" || effort === "max" ? "high" : effort;
}

export function thinkingBudgetForLevel(reasoningLevel: ThinkingLevel, customBudgets?: ThinkingBudgets): number {
	const budgets = { ...DEFAULT_THINKING_BUDGETS, ...customBudgets };
	const level = clampReasoning(reasoningLevel)!;
	return budgets[level]!;
}

export function clampThinkingBudgetToAnswerRoom(thinkingBudget: number, ceiling: number): number {
	return Math.min(thinkingBudget, Math.max(0, ceiling - MIN_ANSWER_TOKENS));
}

export function adjustMaxTokensForThinking(
	baseMaxTokens: number | undefined,
	modelMaxTokens: number,
	reasoningLevel: ThinkingLevel,
	customBudgets?: ThinkingBudgets,
): { maxTokens: number; thinkingBudget: number } {
	let thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets);
	const maxTokens =
		baseMaxTokens === undefined ? modelMaxTokens : Math.min(baseMaxTokens + thinkingBudget, modelMaxTokens);

	if (maxTokens <= thinkingBudget) {
		thinkingBudget = clampThinkingBudgetToAnswerRoom(thinkingBudget, maxTokens);
	}

	return { maxTokens, thinkingBudget };
}
```

要点：浅合并逐档覆盖；xhigh/max 钳 high 查 budget；显式 cap 才抬高上限（`cap + budget`，不超模型上限），
无 cap 用模型上限；budget ≥ maxTokens 时钳到 `ceiling-1024`。

> **Java 现状：数学已全有**（`ThinkingBudgets.java`——DEFAULT、`budgetFor`（含 xhigh/max→high）、
> `adjust`、`clampThinkingBudgetToAnswerRoom` 与 pi 逐字对应）。缺的只是**通道**。

#### 1.1.3 生产者链（唯一用户面入口＝settings.json，无 CLI、无 slash 命令）

- schema 无默认：`packages/coding-agent/src/core/settings-schema.ts:121-133`（4 个 `Type.Optional(Type.Number())`），
  挂在 `:353-357`；
- 读取：`settings-manager.ts:1166-1168` `getThinkingBudgets()` 未配置即 `undefined`；
- 注入：`packages/coding-agent/src/core/sdk.ts:444` `thinkingBudgets: settingsManager.getThinkingBudgets(),`
  进 `new Agent({...})`（runtimeOptions，`:425-446`）；
- Agent 持有：`packages/agent/src/agent.ts:137`（AgentOptions）、`:221-222`（实例字段）、
  `:250`（构造赋值）、`:477`（挂进 loop config）：

```ts
	private createLoopConfig(options: ...): AgentLoopConfig {
		return {
			model: this._state.model,
			reasoning: this._state.thinkingLevel === "off" ? undefined : this._state.thinkingLevel,
			sessionId: this.sessionId,
			...
			thinkingBudgets: this.thinkingBudgets,
```

- 到流的一跳：`packages/agent/src/types.ts:193` `interface AgentLoopConfig extends SimpleStreamOptions`；
  `packages/agent/src/agent-loop.ts:403-407` 靠展开透传：

```ts
	const response = await streamFunction(config.model, llmContext, {
		...config,
		apiKey: resolvedApiKey,
		signal,
	});
```

- proxy 白名单透传：`packages/agent/src/proxy.ts:59`（可序列化选项白名单含 `"thinkingBudgets"`）、
  `:102`（`buildProxyRequestOptions` 逐字段转发）。

> ⚠️ **纠正 Java 旧结论**：`StreamOptions.java:22-25` 的 javadoc 称「harness 路径 pi 不转发
> thinkingBudgets（`harness/execution/assistant.ts:69-97`）」。该路径在锚点树中**已被整个删除**
> （`git ls-tree ea448f454 -- packages/agent/src/harness` 为空）；bin pi 主流走 Agent/agent-loop，
> **转发**。按「pi 删掉的，pi-java 也删」原则，旧结论作废，通道照本批接通。

#### 1.1.4 消费点（3 个在 Java 有车道的）

**(a) anthropic-messages**（`packages/ai/src/api/anthropic-messages.ts:961-974`）：

```ts
	const adjusted = adjustMaxTokensForThinking(
		base.maxTokens,
		model.maxTokens,
		options.reasoning,
		options.thinkingBudgets,
	);

	const maxTokens = clampMaxTokensToContext(model, context, adjusted.maxTokens);

	return stream(model, context, {
		...base,
		maxTokens,
		thinkingEnabled: true,
		thinkingBudgetTokens: Math.min(adjusted.thinkingBudget, Math.max(0, maxTokens - 1024)),
	} satisfies AnthropicOptions);
```

只走**老模型 budget 分支**；forceAdaptive（含全部 managed 模型）在 `:950-956` 提前返回，budget 不可达。
落线 `:1258`：`budget_tokens: options.thinkingBudgetTokens || 1024`——**`||` 不是 `??`，0 回落 1024**
（Java 现有 `FALLBACK_BUDGET` 已复刻）。

**(b) openai-completions**（`openai-completions.ts:736-755` streamSimple 透传 thinkingBudgets；
`:1020-1032` 解析；`:979-985` 写出）：

```ts
function resolveClampedThinkingBudget(model, options, params): number | undefined {
	if (!options?.reasoningEffort || !model.reasoning) return undefined;
	const ceiling = params.max_tokens ?? params.max_completion_tokens ?? model.maxTokens;
	const budget = clampThinkingBudgetToAnswerRoom(
		thinkingBudgetForLevel(options.reasoningEffort, options.thinkingBudgets),
		ceiling,
	);
	return budget > 0 ? budget : undefined;
}
// ──
if (thinkingTokenBudgetField && thinkingBudget !== undefined) {
	Object.assign(params, { [thinkingTokenBudgetField]: thinkingBudget });
}
```

**不抬 max_tokens**；字段名三选一（`thinking_token_budget`/`thinking_budget`/`thinking_budget_tokens`），
生成目录默认不标；budget ≤0 整键省略。同一 budget 还供 `{ "$var": "thinking.budget" }` 模板注入。

**(c) google-generative-ai**（`google-generative-ai.ts:431-471` `getGoogleBudget`）：

```ts
	function getGoogleBudget(model, level, customBudgets?): number {
		if (customBudgets?.[level] !== undefined) {
			return customBudgets[level]!;
		}
		if (model.id.includes("2.5-pro"))     { /* 128/2048/8192/32768 */ }
		if (model.id.includes("2.5-flash-lite")) { /* 512/2048/8192/24576 */ }
		if (model.id.includes("2.5-flash"))   { /* 128/2048/8192/24576 */ }
		return -1;
	}
```

**custom 优先且原样返回**：不与硬编码表合并、不做 answer-room 钳制、不抬上限；`-1`＝Google 动态预算哨兵。
离散 thinkingLevel 模型（Gemini 3 系）完全不碰 budget。

**不消费 budget 的车道**（取证确认）：openai-responses／azure／codex responses、mistral、
google-vertex 的 level 支。**bedrock 与 vertex 的 budget 支 Java 无车道，不做（§6）**。

### 1.2 B99：supportsMidConvoEffort 全家桶

#### 1.2.1 字段定义与数据

`packages/ai/src/providers/compat-schema.ts:466-472`：

```ts
	supportsMidConvoEffort: Type.Optional(
		Type.Boolean({
			description:
				"Whether the exact model transport supports effort-only system messages and thinking binding controls.",
			default: false,
		}),
	),
```

默认 false，全部读点用 `=== true`。生成器谓词（`scripts/generate-models.ts:599-612`）：

```ts
const VERIFIED_ANTHROPIC_MID_CONVO_EFFORT_PROVIDERS = new Set(["anthropic", "openrouter"]);
// OpenRouter rejects `configuration_update` system messages on Opus 5 … while accepting them on Fable 5.1
const MID_CONVO_EFFORT_UNSUPPORTED_ANTHROPIC_MODELS = new Set(["openrouter:anthropic/claude-opus-5"]);

function supportsAnthropicMidConvoEffort(modelId: string): boolean {
	const id = modelId.toLowerCase().replace(/^~?anthropic\//, "");
	return (
		/^claude-opus-(?:5|5[.-]5)(?:-\d{8})?$/.test(id) ||
		/^claude-(?:sonnet|haiku)-5[.-]5(?:-\d{8})?$/.test(id) ||
		/^claude-(?:fable|mythos)-5(?:[.-]1)(?:-\d{8})?$/.test(id)
	);
}
```

两个联动副作用（生成期）：`:826-832` flag 为真 ⇒ `mergeThinkingLevelMap(model, { off: null })`（思考不可关）；
`:849-851` allowedFallbackModels 只保留同谓词为真的 id。

**批 3a 的 17 分片已逐字携带这些数据**——实测资源目录 9 条（`node` 解析 resources/catalog）：

| provider | id | forceAdaptive | map |
|---|---|---|---|
| anthropic | claude-fable-5-1 / haiku-5-5 / opus-5 / opus-5-5 / sonnet-5-5 | true | 均含 `off:null`、xhigh/max 映射 |
| openrouter | anthropic/claude-fable-5.1 / haiku-5.5 / opus-5.5 / sonnet-5.5 | true | 同上 |

注意排除集生效：openrouter 分片**无** `anthropic/claude-opus-5`（有 5.5）。字段与 forceAdaptive
在内置目录里恒同现，但两者是独立开关（custom models.json 可单设）。

#### 1.2.2 两个 beta（逐字）与门控

`packages/ai/src/api/anthropic-messages.ts:190-195`：

```ts
const MID_CONVERSATION_OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01";
const THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01";
```

`:1108-1121`：

```ts
	if (
		model.reasoning &&
		options?.thinkingEnabled === true &&
		(options.interleavedThinking ?? true) &&
		model.compat?.forceAdaptiveThinking !== true
	) {
		features.push(INTERLEAVED_THINKING_BETA);
	}
	if (shouldUseServerSideFallbackBeta(model)) features.push(SERVER_SIDE_FALLBACK_BETA);
	if (model.compat?.supportsMidConvoEffort === true) {
		features.push(MID_CONVERSATION_OUTPUT_CONFIG_BETA, THINKING_BINDING_CONTROLS_BETA);
	}
```

- 两个新 beta **无条件**随 flag 加（不看 thinkingEnabled/effort）；
- managed 模型必带 forceAdaptive ⇒ interleaved beta 被第四个合取项自然排除（内置目录两者互斥）；
  custom 模型单设 flag 不设 adaptive 且开思考时，三个 beta 同帧（代码允许）；
- **覆写陷阱**（`getBetaFeatures` 开头 `:1087-1103`）：model.headers/options.headers 里显式配置
  `anthropic-beta` 头（大小写不敏感）⇒ 自动 beta 列表被**整体替换**；头值为 null ⇒ 空列表。

#### 1.2.3 请求体：managed 分支无条件、effort 恒 high（`:1233-1264`）

```ts
	// Managed effort models always use adaptive thinking so prefix mismatches can
	// be dropped instead of surfacing as persistent 400 responses.
	if (model.compat?.supportsMidConvoEffort === true) {
		params.thinking = {
			type: "adaptive",
			display: options?.thinkingDisplay ?? "summarized",
			block_binding: { prefix_mismatch_behavior: "drop_block" },
		};
		params.output_config = { effort: "high" };
	} else if (model.reasoning) {
		if (options?.thinkingEnabled) {
			const display: AnthropicThinkingDisplay = options.thinkingDisplay ?? "summarized";
			if (model.compat?.forceAdaptiveThinking === true) {
				params.thinking = { type: "adaptive", display };
				if (options.effort) {
					params.output_config = { effort: options.effort };
				}
			} else {
				params.thinking = { type: "enabled", budget_tokens: options.thinkingBudgetTokens || 1024, display };
			}
		} else if (options?.thinkingEnabled === false && model.thinkingLevelMap?.off !== null) {
			params.thinking = { type: "disabled" };
		}
	}
```

反直觉点（夹具 `anthropic-mid-conversation-effort.test.ts:111-112` 钉死）：

1. managed 模型 top-level output_config **恒定 `{effort:"high"}`**，与当前用户级别无关——当前级别
   只体现在队尾 marker 与回写的 providerThinkingLevel；
2. managed 分支**无视 thinkingEnabled**——即便 streamSimple 在无 reasoning 时传 thinkingEnabled:false，
   buildParams 仍发 adaptive ＋ output_config ＋ 两 beta；
3. 非 managed 的 forceAdaptive 模型：无 block_binding，output_config 仅 `options.effort` 真值时发。

#### 1.2.4 marker 重建（`:1518-1532` 全文）

```ts
function insertThinkingLevelMessages(
	converted: ConvertedAnthropicMessages,
	activeEffort: AnthropicEffort,
): MessageParam[] {
	const messages: MessageParam[] = [];
	for (let index = 0; index < converted.messages.length; index++) {
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

- **不是检测相邻级别变化**，是逐回合重放：每条合格历史 assistant 消息前插 marker，队尾**无条件**插
  当前 active marker（`:1154-1161` `const activeEffort = options?.effort ?? "high"`），即使与历史相同也插
  （测试名："reconstructs an exact historical marker prefix and appends the current marker"）；
- marker 形状：`role:"system"`、`content: []`（空数组，无文本无签名）、消息级 `output_config:{effort}`；
- 历史索引条件（convertMessages `:1449-1461`，消息 push 后记其下标；空 blocks 消息 `:1448` 已 continue）：

```ts
			if (
				managedProvider !== undefined &&
				msg.api === "anthropic-messages" &&
				msg.provider === managedProvider &&
				isAnthropicEffort(msg.providerThinkingLevel)
			) {
				assistantLevels.set(messageIndex, msg.providerThinkingLevel);
			}
```

`managedProvider` 仅 flag 为真时传入（`buildParams:1148` `model.compat?.supportsMidConvoEffort === true ? model.provider : undefined`）；
effort 白名单（`:1514-1516`）：`low|medium|high|xhigh|max`；
- **marker 绝不插进 tool_use↔tool_result 之间**：转换层的 pending system 缓冲保证
  user(tool_result) 紧贴 assistant(tool_use)；marker 只在 assistant 边界插。线上顺序：

```
… user(tool_result) │ [marker system] │ assistant(tool_use…) │ user(tool_result) │ … │ [active marker system]
```

- 缓存断点：pi 的断点挂载发生在 convertMessages **之内**（`:1398-1424`），insert 在其后 ⇒
  **队尾 marker 不吃断点**，断点仍挂在 insert 前的最后一条消息上。

#### 1.2.5 temperature 门第三项（`:1195-1202` 附近）

```ts
	if (
		params.temperature !== undefined &&
		!thinkingEnabled &&
		model.compat?.supportsMidConvoEffort !== true &&
		compat.supportsTemperature !== false
	) { … }
```

Java 现有门缺第三项（`AnthropicRequestBuilder.java:179-186` 注释自陈）。

#### 1.2.6 effort 映射（`:908-926`，B110 盖章共用）

```ts
function mapThinkingLevelToEffort(model, level): AnthropicEffort {
	const mapped = level ? model.thinkingLevelMap?.[level] : undefined;
	if (typeof mapped === "string") return mapped as AnthropicEffort;
	switch (level) {
		case "minimal":
		case "low": return "low";
		case "medium": return "medium";
		case "high": return "high";
		default: return "high";
	}
}
```

Java `AnthropicThinking.mapLevelToEffort` 已逐字在。

### 1.3 B110：providerThinkingLevel

#### 1.3.1 类型与语义（`packages/ai/src/types.ts:565-596`）

```ts
	/** Exact provider-native effort level used for this response. Absent for legacy or unmanaged responses. */
	providerThinkingLevel?: string;
	/** Pi thinking level the agent loop requested for this response. Absent outside the agent loop and for legacy responses. */
	thinkingLevel?: ModelThinkingLevel;
```

- 只在 AssistantMessage 上，StreamEvent 联合无独立字段（事件携整条 partial）；
- 与 `thinkingLevel` 的区别：后者是 agent-loop 盖的**请求侧 pi 级别**（含 "off"，
  `agent-loop.ts:409` `Object.assign(await response.result(), { thinkingLevel: config.reasoning ?? "off" })`），
  本批不做 thinkingLevel（台账批 5「消息级 thinkingLevel」）；
- 键序：`responseId` 之后、`thinkingLevel` 之前（Java 两者都还没有，位置紧跟 model 身份字段）。

#### 1.3.2 唯一真生产者（`anthropic-messages.ts:581-588`）

```ts
		const providerThinkingLevel = model.compat?.supportsMidConvoEffort ? (options?.effort ?? "high") : undefined;
		const output: AssistantMessage = {
			role: "assistant",
			content: [],
			api: model.api as Api,
			provider: model.provider,
			model: model.id,
			...(providerThinkingLevel === undefined ? {} : { providerThinkingLevel }),
			usage: { … },
			stopReason: "pending",
			timestamp: Date.now(),
		};
```

- 值**不从响应解析**，请求发出前定死；`:658` start 帧即携带，全程不更新；
- 门只看 `supportsMidConvoEffort`，**不看 forceAdaptiveThinking**（后者模型走顶层 output_config
  但不盖章＝"unmanaged"）；
- 无 reasoning 时 options.effort 缺席 ⇒ 盖章 `"high"`（managed 分支无 reasoning 也恒 high）。

#### 1.3.3 两个透传面

- **pi-messages**（Radius 网关，`packages/ai/src/api/pi-messages.ts:71-87` 终局帧线格，
  `:180-217` 搬运）：done/error 帧带可选 `providerThinkingLevel`，`!== undefined` 才写 partial；
- **agent proxy**（`packages/agent/src/proxy.ts:34-46` 类型，`:370-385` 生产者）：
  终局扁平帧 `{type,reason,usage,providerThinkingLevel?}`／`{type,reason,errorMessage?,usage,providerThinkingLevel?}`，
  无换行终局帧也要吃（issue #8996 回归夹具 `proxy.test.ts:77-97`）。

#### 1.3.4 消费者与持久化

- 唯一行为消费者＝§1.2.4 marker 重建；tui/rpc/agent-loop/record-log **零按名读取**；
- start 帧克隆保留（`utils/assistant-message-frame.ts:86`）；
- 持久化零特殊处理：`session-manager.ts` 整条消息 `JSON.stringify` 追加（`:1187`），
  读回 `JSON.parse`，无白名单；压缩后旧消息被摘要取代、字段自然消失。

---

## 2. Java 现状（触点盘点）

### 2.1 已存在（不用重写）

| 能力 | 位置 |
|---|---|
| budget 全数学（DEFAULT/adjust/夹取/xhigh→high） | `ai/thinking/ThinkingBudgets.java` |
| effort 映射 | `AnthropicThinking.mapLevelToEffort` |
| adaptive/enabled/disabled 三分流（**缺 managed 顶支**） | `AnthropicThinking.resolve`（`AnthropicThinking.java:117-144`） |
| interleaved beta（已按 forceAdaptive 排除） | `AnthropicRequestBuilder.java:149-155` |
| 两副 compat 骨架＋解析合并 | `ModelCompat.java:312`、`ModelsJsonSchema.java:165` anthropic CompatDef、`CompatResolver.forAnthropic:234` |
| 消息身份统一挂载缝 | `AbstractChatApi.IdentitySubscriber:191-224`（api/provider/model/timestamp） |
| 终局线格式投影（自陈缺 providerThinkingLevel） | `JsonEventMapper.terminalWireNode:352-375` |
| 目录 9 条 flag 数据 | `resources/catalog/anthropic.json`、`openrouter.json`（ignoreUnknown 跳过中） |
| 原始 JSON 上线先例 | OpenAI 侧 `ThinkingFormatWriter.put`（树过 NON_NULL 逃生）；Anthropic 侧 tool_addition 的 `_json()` 回读 |

### 2.2 要改的文件（三层）

**ai 模块**

1. `api/StreamRequest.java`：加 `ThinkingBudgets thinkingBudgets` 组件（null≙undefined；见 §3.1）；
2. `thinking/ThinkingBudgets.java`：无数学改动，只补通道相关 javadoc；
3. `catalog/ModelCompat.java`：anthropic 侧加 `Boolean supportsMidConvoEffort`；
4. `provider/ModelsJsonSchema.java`（anthropic CompatDef）＋`ModelsJsonConfig.java`（映射）
   ＋`ModelJsonMerge.java`（深合并）＋`CompatResolver.java`（anthropic 合并支）：补字段；
5. `protocol/AnthropicThinking.java`：managed 顶支（恒 adaptive＋block_binding＋high），
   `resolve` 增加 raw-thinking 输出（见 §3.2）；新增 `activeEffort(model, reasoning)` 供盖章/marker 共用；
   消费 `request.thinkingBudgets()` 替换 `:137` 硬编码 DEFAULT；
6. `protocol/AnthropicRequestBuilder.java`：两 beta、managed marker 重建、temperature 第三项、
   beta 覆写陷阱（裁决③）、断点顺序（§3.3）；
7. `protocol/AnthropicMessageConverter.java` 或本 builder 内：marker row 构造（`output_config` 附加属性）；
8. `protocol/OpenAICompletionsMessageConverter.java:286-287`：硬编码 `ThinkingBudgets.DEFAULT`
   改读 `request.thinkingBudgets()`（空⇒DEFAULT）；
9. `protocol/GoogleThinking.java:121`：`budget(model, level)` 加 custom 首支
   （`GoogleGenerativeAiApi.java:383` 调用点传入）；
10. `message/AssistantMessage.java`（流式记录，10 组件）：加 `String providerThinkingLevel`
    ＋wither；`stream/StreamPartialBuilder.java:96-99` snapshot 携带；
11. `message/Message.java`（会话记录，10 组件）：加同款组件＋`fromPartial:225-238` 携带
    ＋更新 `:159-179` 五字段裁决注释（B110/B99 销号）；
12. `protocol/AbstractChatApi.java`：identity 缝加车道可覆盖钩子
    `String providerThinkingLevel(StreamRequest, ApiOptions)`（默认 null）；
13. `protocol/AnthropicMessagesApi.java`：覆盖钩子（compat 门＋activeEffort）；
14. `protocol/PiMessagesEvent.java:55`：Done/Error wire 加 `providerThinkingLevel`；
    `protocol/PiMessagesApi.java:143-152`：终局写 partial（经 builder 新 mutator）。

**agent-core 模块**

15. `agent/harness/StreamOptions.java`：加 `ThinkingBudgets` 组件（保留旧参便捷构造），
    **作废 `:22-25` 旧 javadoc**；
16. `agent/harness/PiLoop.java`：`Config` 加 thinkingBudgets；
17. `agent/harness/HarnessConfig.java:86`：加 thinkingBudgets；
18. `agent/harness/PiLoopRunner.java:226-228`：StreamOptions 携带；
19. `session/jsonl/MessageJsonCodec.java:46-56`：decode 加 `optionalString(node,"providerThinkingLevel")`；
    写侧靠 `SessionJson` mapper 的 NON_NULL（`SessionJson.java:244`）自动出键；
20. 会话记录 6 个构造点随组件加参（null）：
    `LlmSummaryGenerator`×3、`PiLoopRunner:296`、`RunFailure:81`、`ContextEntries:242`。

**coding-agent 模块**

21. `core/Settings.java`：加嵌套 `ThinkingBudgets`（4 个 Integer 公共字段，同 Tui/Retry 风格）；
22. `core/SettingsAccessors.java`：`getThinkingBudgets()`（缺席⇒null）；
23. `core/AgentSession.java`（HarnessConfig 装配处）：settings → ai `ThinkingBudgets` 转换；
24. `core/DefaultProviders.java:276-281`：StreamRequest 传 budgets；
25. `mode/JsonEventMapper.java`：终局帧 done/error 在 usage 后按 `?` 写 providerThinkingLevel，
    更新 `:336-349` 注释。

---

## 3. 设计

### 3.1 B128：通道形状

**null≙undefined，不造哨兵。** 三层载体都直接持 `ThinkingBudgets`（可空）：

```java
// StreamRequest（canonical 构造器末尾加组件；保留两个 legacy 构造器，内部传 null）
public record StreamRequest(
    ModelInfo model, TranscriptContext transcript, int maxTokens, double temperature,
    Optional<ThinkingLevel> reasoning,
    ThinkingBudgets thinkingBudgets   // null ≙ pi options.thinkingBudgets === undefined
) { … }
```

消费点统一一道归一：`ThinkingBudgets` 自身的 `budgetFor` 已是「custom 逐档覆盖、未给取默认」语义，
故 null 只需在**调用边界**替换成 `DEFAULT`——不加第二张表、不改 4 个 OptionalInt 的形状：

```java
// ThinkingBudgets.java 新增
/** null 通道值 ≙ pi 的 undefined（浅合并等价于全默认表）。 */
public static ThinkingBudgets orDefault(ThinkingBudgets b) { return b == null ? DEFAULT : b; }
```

- anthropic：`AnthropicThinking.resolve(…, budgets)` 内 `ThinkingBudgets.adjust(..., orDefault(budgets))`；
- completions：`SimpleOptions.clampedThinkingBudget(..., orDefault(request.thinkingBudgets()), ceiling)`
  （该助手签名本就收 `ThinkingBudgets`，只是当前调用点硬塞 DEFAULT）；
- google：custom 支语义不同（不合并），单独判空，见 §3.4。

**harness 层**：`StreamOptions` 加组件后保留 4/3 参便捷构造（传 null）；`PiLoop.Config`、
`HarnessConfig` 同加；`PiLoopRunner:226` 把 config 的 budgets 透传。压缩摘要路
（`LlmSummaryGenerator:334` 自建 StreamOptions）传 null——pi 压缩也是另起摘要调用、不带用户 budget 表。

**settings 面**（逐字对齐 settings-schema，无默认值）：

```java
public static final class ThinkingBudgetsSettings {
    public Integer minimal;
    public Integer low;
    public Integer medium;
    public Integer high;
}
public ThinkingBudgetsSettings thinkingBudgets;
```

装配时逐段转 `OptionalInt`（null 段⇒empty，与浅合并同效）；负数/非整数？pi schema 只验 number，
不做范围校验 ⇒ Java 也不做（忠实上线，服务端拒就拒）。

**不做 CLI flag、不做 slash 命令**（pi 无）。proxy 白名单透传随 proxy 传输缺席挂起（§6）。

### 3.2 B99：managed 请求体（AnthropicThinking 重构）

`resolve` 的分流序照 pi 改成四支，**managed 顶支在 reasoning 空判定之前**：

```java
static Resolved resolve(ModelInfo model, ModelCompat compat, Optional<ThinkingLevel> reasoning,
                        OptionalInt baseMaxTokens, TranscriptContext transcript,
                        ThinkingBudgets budgets) {
    if (!thinkingCapable(model)) return EMPTY;                    // 非推理模型恒空（现状）
    if (compat != null && Boolean.TRUE.equals(compat.supportsMidConvoEffort())) {
        // pi :1235-1241：无条件 adaptive＋drop_block＋顶层 high，与 reasoning 无关
        return new Resolved(
            Optional.empty(),                          // 不走 typed builder.thinking()
            Optional.of(OutputConfig.high()),         // typed：builder.outputConfig（见下）
            OptionalInt.empty(),                      // 不抬 maxTokens
            Optional.of(managedThinkingTree()));      // 原始 JSON：见下
    }
    if (reasoning.isEmpty()) { /* 现状 disabled 支，off:null 模型恒空 */ }
    if (compat.forceAdaptiveThinking()) { /* 现状 adaptive（无 block_binding、effort 用映射值） */ }
    /* 现状 budget 支，budgets 换成入参 */
}
```

**raw thinking 树（裁决②）**：stable SDK 2.66.0 的 `ThinkingConfigAdaptive` **无** blockBinding
（javap 实测；只有 `com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive` 有）。
不引入整套 beta client 类型替换，用与 OpenAI 侧相同的成熟逃生舱——managed 支不调 `builder.thinking()`，
改在 builder 上 `putAdditionalBodyProperty("thinking", JsonValue.from(tree))`，树用无 inclusion 的
`ObjectMapper` 构建（`ThinkingFormatWriter` 的 PLAIN_JSON 先例），键序照 pi：

```json
{"type":"adaptive","display":"summarized","block_binding":{"prefix_mismatch_behavior":"drop_block"}}
```

`Resolved` 加第 4 组件 `Optional<JsonNode> rawThinking`（只 managed 支有值）；
builder 侧 `rawThinking.ifPresent(t -> builder.putAdditionalBodyProperty("thinking", JsonValue.from(t)))`。
typed 与 raw 同键不会撞——两分支互斥。top-level `output_config` 继续走 typed
`builder.outputConfig(OutputConfig.builder().effort(Effort.HIGH).build())`（stable Effort 枚举有 HIGH）。

**beta 通道（裁决①，实施第 0 步先探针）**：现有 4 个 beta 走
`putAdditionalBodyProperty("betas", list)`，但 stable SDK 的 MessageCreateParams **无 betas 字段**
（javap 实测），pi TS SDK 是把 `params.betas` 编成 `anthropic-beta` **请求头**。
两件新 beta 要激活服务端 managed 行为，body 里多一个未知键大概率不生效。开工先写一个 loopback
抓包夹具（项目已有假服务器测试先例） settle：

- 若现状 body `betas` 不会变成请求头 ⇒ 全部 beta 改走
  `builder.putAdditionalHeader("anthropic-beta", String.join(",", betas))`（顺带修正既有 4 个）；
- 探针夹具固化为回归测试（断言请求头含全部 beta 字面量）。

**两个新 beta 门控**：`Boolean.TRUE.equals(compat.supportsMidConvoEffort())` ⇒
追加 `mid-conversation-output-config-2026-07-01`、`thinking-binding-controls-2026-08-01`，
与 thinkingEnabled 无关；interleaved 门维持现状（managed 模型 forceAdaptive 恒真被自然排除）。

**显式 anthropic-beta 头覆写（裁决③）**：复刻 pi 的整体替换——生效 headers（per-model 合并后，
`DefaultProviders.withPerModelOverrides`）里有 `anthropic-beta`（大小写不敏感）⇒ 自动列表整组跳过。
实现：`AnthropicMessagesApi` 调 buildParams 处把有效头名集合传进来（仅名集合，不传值）。
目录 9 个 managed 模型无自定义头，主流路径行为不变；此支只防手搓 models.json 双发。

### 3.3 B99：marker 重建（AnthropicRequestBuilder.addMessages）

参数增加 `boolean managed`、`String managedProvider`、`String activeEffort`（仅 managed 时非空，
值＝`AnthropicThinking.activeEffort(model, request.reasoning())`）。

**历史 marker**——在 assistant 分支、`blockParams` 非空判定通过之后、`rows.add` 之前：

```java
if (managed
        && msg instanceof Message.AssistantMessage a
        && "anthropic-messages".equals(a.api())
        && managedProvider.equals(a.provider())
        && isAnthropicEffort(a.providerThinkingLevel())) {
    rows.add(markerRow(a.providerThinkingLevel()));
}
rows.add(new Row(ASSISTANT, blockParams));
```

`isAnthropicEffort` 五值闭集（逐字白名单，非法值/旧消息 null ⇒ 不插）。
空 blockParams 的 assistant 在 `:290-292` 已 continue ⇒ 与 pi `blocks.length===0 不索引` 同点。

**marker row 形状（裁决⑧）**：role SYSTEM ＋ **空** block 列表 ＋ 附加属性 `output_config`：

```java
var param = MessageParam.builder()
    .role(MessageParam.Role.SYSTEM)
    .content(MessageParam.Content.ofBlockParams(List.of()))
    .putAdditionalProperty("output_config",
        JsonValue.from(PLAIN_JSON.createObjectNode().put("effort", effort)))
    .build();
```

（stable `MessageParam.Builder.putAdditionalProperty` 与 Role.SYSTEM 均在 2.66.0 javap 实测存在。）

**队尾 active marker 与断点顺序**——当前代码是「flush → applyConversationCacheBreakpoint → 逐条 emit」。
改成「flush → **断点** → **追加 active marker（仅 managed）** → emit」：

```java
flushPendingSystemMessages(rows, pendingSystem);
applyConversationCacheBreakpoint(rows, cacheControl);   // 不含 marker（同 pi：断点在 insert 前挂）
if (managed) rows.add(markerRow(activeEffort));         // pi :1530 队尾无条件
for (var row : rows) builder.addMessage(row.toParam());
```

pending system 的刷出点（assistant 分支入口 `:286`）保持在历史 marker 之前 ⇒
tool_result 后顺序为 `user(result) → marker → assistant`，与 pi 同形（marker 不进 tool 间隙）。

**temperature**：`:183` 门加第四合取项 `&& !Boolean.TRUE.equals(compat.supportsMidConvoEffort())`，
删旧注释里「第三项缺失」的登记。

### 3.4 B128：Google custom 首支

`GoogleThinking.budget(model, level, customBudgets)` 照 pi `getGoogleBudget` 加顶支——
**custom 原样返回，不合并、不钳、不抬**：

```java
if (custom != null) {
    var customValue = switch (level) {
        MINIMAL -> custom.minimal(); LOW -> custom.low();
        MEDIUM -> custom.medium(); HIGH -> custom.high();
    };
    if (customValue.isPresent()) return customValue.getAsInt();  // 含 0/-1 都原样
}
/* 三张硬编码表 → -1，现状 */
```

入参从 `GoogleGenerativeAiApi.java:383` 的 `request.thinkingBudgets()` 来（可能 null）。
调用点 level 已是 4 档之一（离散 level 模型在更上游分流，不进 budget）。

### 3.5 B110：字段、盖章、线格式

**两层消息记录**：

- `ai/message/AssistantMessage`（流式）：10→11 组件，位置在 `model` 之后（贴 pi 键序）；
  全参构造器、4 参兼容构造器、所有 wither 同步；
- `ai/message/Message.AssistantMessage`（会话）：10→11 组件同位；`fromPartial` 携带；
  3 个兼容构造器补 null；SessionJson NON_NULL 自动省略 null 键（与 pi `JSON.stringify` 丢 undefined 同形）。

**盖章缝**（裁决⑨）：pi 在车道创建流时盖章；Java 统一身份缝在 `AbstractChatApi`。
加可覆盖钩子（默认 null），`IdentitySubscriber.onNext` 挂载身份时一并 withProviderThinkingLevel：

```java
/** pi anthropic-messages.ts:581：managed 模型在消息上盖实际生效的 provider-native effort；其余车道 null。 */
protected String providerThinkingLevel(StreamRequest request, ApiOptions options) { return null; }
```

`AnthropicMessagesApi` 覆盖：`CompatResolver.forAnthropic(request.model(), baseUrl)` flag 真 ⇒
`AnthropicThinking.activeEffort(model, request.reasoning())`（reasoning 空⇒`"high"`，
有值⇒`mapLevelToEffort`——与 managed 请求的 active marker **同一个函数**）。
门只认 flag 不认 forceAdaptive（§1.3.2 风险点）。onError 路的 settle 消息不经 IdentitySubscriber，
与身份字段同等待遇（生产上 error 消息由车道/缝补身份处同形处理；实施时确认 StreamError.settle
路径一并盖章，夹具覆盖）。

**pi-messages 透传**：wire Done/Error 加可空 `providerThinkingLevel`（Jackson 忽略缺席），
`PiMessagesApi` 终局分支写 builder（新增 `StreamPartialBuilder.noteProviderThinkingLevel`，
在 emitDone/emitError 前进快照）。

**JSON 终局线格式**（`JsonEventMapper.terminalWireNode`）：done/error 两分支在 `usage` 后、
partial 值非空时写 `providerThinkingLevel`，键序 `type, reason, errorMessage?, usage, providerThinkingLevel?`
（逐字 pi proxy.ts:42-58）。

**JSONL**：decode 补 `optionalString`；encode 自动。旧盘无该键⇒null⇒不出键，向前兼容。

---

## 4. 裁决（请逐条过）

1. **beta 通道探针先行** ✅ **已 settle（2026-10-14）**：body `betas` 不成为
   `anthropic-beta` 头（抓包实测）⇒ 全部 beta 走 `putAdditionalHeader`；既有 interleaved/
   inline-tools/fine-grained/fallback 四 beta 确实从未真正生效，已随 commit 1 一并修正
   （夹具 `AnthropicBetaHeaderProbeTest` ＋三处旧夹具断言改读请求头）。
2. **managed thinking 走 raw JSON 逃生舱**，不迁移到 SDK beta client（后者要替换全部消息/块类型，
   成本与回归面大；raw 树是仓内成熟先例）。
3. **复刻 anthropic-beta 头覆写陷阱**（有效头含该名⇒跳过全部自动 beta）；需要把有效头名集合
   从 ApiOptions 管道进 buildParams。
4. **Bedrock／Vertex 不做**：Java 无对应车道（grep 实测零文件）；bedrock 的 6 键默认 budget 表、
   GovCloud display 省略、block_binding bedrock 版、vertex 缺 flash-lite 分支的 getGoogleBudget
   全部不移植，闭环台账记窄差（与既有 bedrock 缺口同列）。
5. **thinkingBudgets 只走 settings，无 CLI/slash**（同 pi）；不做范围校验；压缩摘要路传 null。
6. **StreamRequest/StreamOptions 用 nullable ThinkingBudgets**（null≙undefined⇒DEFAULT 合并），
   不引入 sentinel；旧便捷构造器全部保留传 null。
7. **Google custom 值原样上线**（0/负数/-1 都不夹），逐字 pi；只有三张硬编码表的支保持现状。
8. **消息级 marker 用 stable MessageParam 的 putAdditionalProperty("output_config", …)**
   ＋空 blockParams 列表，不引入 BetaMessageParam。
9. **盖章点放 AbstractChatApi identity 缝的可覆盖钩子**，不在车道内部自建 partial——
   与 api/provider/model 挂载同一生命周期，start 帧即携带（pi :658 同效）。
10. **B110 的消息级 `thinkingLevel`（agent-loop :409）不随本批**：它属台账批 5
    （docs/08 §3 批 5「消息级 thinkingLevel」），本批只做 providerThinkingLevel。
11. **agent proxy 传输面（proxy.ts 终局帧生产/无换行帧）不做**：Java 无 proxy HTTP 传输
    （只有 ProxyDetector）；B110 销号时记该透传随 proxy 传输整体挂起。JSON 模式终局帧照做（那是
    宿主自己的线，不是 agent proxy）。

## 5. 测试计划

**新增（对齐 pi 夹具）**

- `AnthropicManagedEffortTest`（移植 `packages/ai/test/anthropic-mid-conversation-effort.test.ts`
  210 行断言面）：
  1. managed 模型请求：两 beta 在**请求头**（探针后定通道）、thinking 树三键含 block_binding、
     top-level output_config effort=high（low/high 两次请求都 high）、无 temperature；
  2. **无 reasoning 也发** adaptive＋output_config＋两 beta（反直觉支，重点夹具）；
  3. marker：仅同 provider＋api=anthropic-messages＋五档值的非空历史 assistant 前插；
     队尾 active marker 恒在；不同 provider/旧消息(null)/非法值不插；空 assistant 不插；
  4. 历史前缀逐字节重放（同 effort 也插）；marker 不进 tool_result 间隙；
  5. 缓存断点仍挂 active marker **之前**的末条 user/system；
  6. 非 managed 的 forceAdaptive 模型：无 block_binding、无两新 beta、output_config 用映射 effort；
  7. custom 模型单设 flag 不设 adaptive：两 beta 与 interleaved 同帧（裁决允许）；
  8. 显式 anthropic-beta 头 ⇒ 自动 beta 全撤。
- thinkingBudgets：
  9. anthropic 老模型（非 adaptive）custom medium=4096 ⇒ adjust 用 4096、budget_tokens 随动；
     xhigh/max 查 high 档；
  10. completions：custom budget 经顶层字段（field 三态）；无 field 不出键；budget 钳 0 省略；
  11. google：custom 首支原样值（含不钳）；无 custom 走三张表/-1 现状；
  12. settings→请求 全链一个装配夹具（settings 写四段⇒StreamRequest 携带；缺席⇒null⇒DEFAULT 行为）。
- B110：
  13. managed 模型 start 帧 partial 即带 providerThinkingLevel（reasoning 空⇒high，
      medium＋map⇒映射值）；非 managed/非 anthropic 车道不出键；
  14. JSONL round-trip（带值/旧盘无键两态）；
  15. JsonEventMapper done/error 终局帧形状（usage 后可选键、缺席省略）；
  16. pi-messages done 帧 providerThinkingLevel=high 落到终局消息。

**回归**：ai 全量（现 1589）＋coding-agent 536＋tui 213；checkstyle；
`mvn clean verify` 11 模块绿。目录 manifest 不变（数据 3a 已在）。

## 6. 明确不做

- Bedrock／Vertex 两车道的一切（裁决④）；agent proxy HTTP 传输透传（裁决⑪）；
- 消息级 thinkingLevel（批 5）；responseModel/diagnostics（B148，批 5）；durationMs（B234，批 5）；
- thinkingDisplay 用户面（pi 也无主流入口，恒 summarized）；interleavedThinking 关闭选项
  （pi `AnthropicOptions.interleavedThinking` 无 settings/CLI 生产者）；
- 不动 3a 目录数据（flag/off:null/fallback 过滤已逐字在资源里）；
- 不新增 CLI 参数、slash 命令。

## 7. 落地顺序与提交粒度

1. **commit 1（探针＋通道）**：beta 头探针夹具 settle 通道；StreamRequest/StreamOptions/Config
   三层 nullable ThinkingBudgets；3 个消费点接通；settings 面＋装配。测 9-12。
2. **commit 2（B99 请求体＋B110 字段）**：compat 字段链；AnthropicThinking managed 支；
   两 beta＋temperature 门＋头覆写；两层消息加字段＋盖章缝＋JSONL＋线格式。测 1-2、13-16。
3. **commit 3（marker 重建）**：addMessages 历史/队尾 marker＋断点顺序。测 3-8。
4. **docs commit**：台账 B99/B110/B120 销号、docs/06、docs/08 批 4 行、map/02 头部、
   本稿头部闭环 banner（加权不变 92.2%）。

预估新增/修改：新文件 2-3 个（settings 嵌套类型可内联；主要是测试类），修改约 22 个产品文件（§2.2）。
