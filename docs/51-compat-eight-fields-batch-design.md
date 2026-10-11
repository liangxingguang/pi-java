# 51 批量包：B98 compat 八字段（completions 七字段＋anthropic fallbacks 请求半）

> 📝 **状态：设计稿，待用户审核（2026-10-11）。未批准前不写生产代码。**
>
> pi 锚点 **`ea448f454`**（证据一律 `git show ea448f454:<path>`）；pi-java 基线 HEAD `8ba06ccf`。
> 「长尾批量化」第二包，承接 `docs/50`（B129–B132 族）。

---

## 1. 范围与处置

B98 登记的 8 个 compat 字段在 Java 四层（ModelCompat／CompatResolver 探测面／
ModelsJsonSchema 解析面／各车道消费点）**全部缺席**。本包补齐：

| # | 字段 | 车道 | 目录生产者（新锚点实测 `providers/data/*.json`） | 处置 |
|---|---|---|---|---|
| 1 | `requiresToolResultName` | completions | **0 文件**（探测 false） | 字段＋models.json＋消费点 |
| 2 | `requiresAssistantAfterToolResult` | completions | **0 文件**（探测 false） | 字段＋models.json＋三处消费点 |
| 3 | `requiresThinkingAsText` | completions | **0 文件**（显式-only） | 字段＋models.json＋消费点 |
| 4 | `supportsUsageInStreaming` | completions | baseten 22 条全 true（探测默认 true，冗余；Java 不携带 baseten） | 字段＋探测默认 true＋门控 include_usage |
| 5 | `zaiToolStream` | completions | **zai 7 条＋zai-coding-cn 4 条全 true**（Java 携带 zai 两 provider！） | 字段＋**CatalogCompatRules 生产者**＋models.json＋消费点 |
| 6 | `vllmPriority` | completions | **0 文件**（显式-only，getCompat 不并探测默认） | 字段＋models.json＋消费点 |
| 7 | `vercelGatewayRouting` | completions | **0 文件**（显式-only） | 新类型＋models.json＋消费点 |
| 8 | `allowedFallbackModels` | anthropic | **anthropic：claude-fable-5 两条**（opus-4-8/opus-5，带 cost） | 新类型＋**CatalogCompatRules 生产者**＋models.json＋**请求半**（beta＋params） |

**#8 的流内半不做**（随 B148）：`anthropic-messages.ts:660-690` 的 `responseModel` 改写、
`content_block_start` 的 `"fallback"` 块处理与成本归属依赖 AssistantMessage.responseModel
（B148 已登记）；服务端不收到 `fallbacks` 请求参数根本不会触发回退，故**请求半是前提**，
流内半在 B148 包补齐并在台账 B148 行补记。

台账：B98 销号，**B 102→101**。

---

## 2. pi 证据（锚点逐字）

### 2.1 #4 include_usage 门（`openai-completions.ts:834-836`；探测 :1649）

```ts
if (compat.supportsUsageInStreaming !== false) {
	params.stream_options = { include_usage: true };
}
```
探测 `supportsUsageInStreaming: true`（:1649）；getCompat `explicit ?? detected`（:1704）。
⇒ 只有**显式 false** 才不发。Java 现状 `OpenAICompletionsMessageConverter:230-232` **恒发**。

### 2.2 #5 zaiToolStream（:856-860；探测 :1671 false）

```ts
if (transcriptTools.requestTools.length > 0) {
	params.tools = convertTools(...);
	if (compat.zaiToolStream) {
		(params as any).tool_stream = true;
	}
}
```
⇒ 门是「**请求工具非空**」；else 分支的 `tools=[]`（历史里有工具调用）**不发** tool_stream。

### 2.3 #6 vllmPriority（:873-875；getCompat :1732 只透传显式值，无探测默认）

```ts
if (compat.vllmPriority !== undefined) {
	(params as any).priority = compat.vllmPriority;
}
```
schema（`compat-schema.ts:338-345`）：`Type.Optional(Type.Number)`，整数优先级，默认 0。
位置：tool_choice 之后、思考形态链条（:877）**之前**。

### 2.4 #7 vercelGatewayRouting（:992-1001；schema :151-169）

```ts
if (model.compat?.vercelGatewayRouting) {
	const routing = model.compat.vercelGatewayRouting;
	if (routing.only || routing.order) {
		const gatewayOptions: Record<string, string[]> = {};
		if (routing.only) gatewayOptions.only = routing.only;
		if (routing.order) gatewayOptions.order = routing.order;
		(params as any).providerOptions = { gateway: gatewayOptions };
	}
}
```
schema：`{ only?: string[]; order?: string[] }`；读的是 **raw model.compat**（不经解析面默认）。
位置：openRouterRouting（:988-990）之后、samplingParams（:1003）之前。

### 2.5 #1 requiresToolResultName（:1429-1432；探测 :1652 false）

```ts
if (compat.requiresToolResultName && toolMsg.toolName) {
	(toolResultMsg as any).name = toolMsg.toolName;
}
```

### 2.6 #2 requiresAssistantAfterToolResult（三处；探测 :1653 false）

```ts
// :1240-1246 —— 上一条落线消息来自 toolResult 组、下一条是 user ⇒ 插桥
if (compat.requiresAssistantAfterToolResult && lastRole === "toolResult" && msg.role === "user") {
	params.push({ role: "assistant", content: "I have processed the tool results." });
}
// :1294-1296 —— assistant 消息默认 content 是 "" 而不是 null
const assistantMsg = {
	role: "assistant",
	content: compat.requiresAssistantAfterToolResult ? "" : null,
};
// :1448-1456 —— 工具图片合成 user 消息之前同样插桥
if (compat.requiresAssistantAfterToolResult) {
	params.push({ role: "assistant", content: "I have processed the tool results." });
}
```
注意 `lastRole` 记账（:1458-1462）：toolResult 组**有图片**→`"user"`、无图片→`"toolResult"`；
其余消息在循环尾 `lastRole = msg.role`。空桥消息与既有丢弃规则并存（无 content 无 tool_calls 仍丢）。

### 2.7 #3 requiresThinkingAsText（:1322-1330；探测 :1654 false）

```ts
if (compat.requiresThinkingAsText) {
	// Convert thinking blocks to plain text (no tags to avoid model mimicking them)
	const thinkingText = nonEmptyThinkingBlocks
		.map((block) => sanitizeSurrogates(block.thinking))
		.join("\n\n");
	assistantMsg.content = [{ type: "text", text: thinkingText }, ...assistantTextParts];
}
```
⇒ assistant content 变成**文本块数组**（思考块在前、可见文本块在后），且**不发** reasoning_content/
reasoning_details（该分支整体绕过 :1340-1350 的签名字段写入）。pi 夹具
`openai-completions-thinking-as-text.test.ts` 期望逐字：
`{role:"assistant", content:[{type:"text",text:"internal reasoning"},{type:"text",text:"visible answer"}]}`。

### 2.8 #8 allowedFallbackModels（anthropic-messages.ts）

数据（`data/anthropic.json`，全目录唯一生产者）：
```json
"id":"claude-fable-5", "compat":{"allowedFallbackModels":[
  {"provider":"anthropic","model":"claude-opus-4-8","cost":{"input":5,"output":25,"cacheRead":0.5,"cacheWrite":6.25}},
  {"provider":"anthropic","model":"claude-opus-5",     "cost":{同上}}]}
```
请求半三个落点：
```ts
// :192
const SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01";
// :211-213
function shouldUseServerSideFallbackBeta(model) {
	return (model.compat?.allowedFallbackModels?.length ?? 0) > 0;
}
// :1116
if (shouldUseServerSideFallbackBeta(model)) features.push(SERVER_SIDE_FALLBACK_BETA);
// :1281-1284
const allowedFallbackModels = model.compat?.allowedFallbackModels;
if (allowedFallbackModels && allowedFallbackModels.length > 0) {
	params.fallbacks = allowedFallbackModels.map((fallback) => ({ model: fallback.model }));
}
```
schema（:485-495）：数组 `maxItems:3`；元素 `{provider:string(min1), model:string(min1), cost: ModelCost}`。
**请求只发 model**，provider/cost 是本地成本元数据。Anthropic 对无许可回退目标的模型拒绝该字段
（schema 描述）⇒ 空表必须省略。

---

## 3. Java 现状（HEAD `8ba06ccf`）

| 面 | 现状 |
|---|---|
| `ModelCompat`（737 行，E5） | 31 组件，8 字段全无；canonical＋约 10 个便捷构造 |
| `CompatResolver.forCompletions` | 探测面无 usage 门相关默认（消费点恒发）；withCompletions 逐组件重建 |
| `CatalogCompatRules` | zai 只标 thinkingFormat；anthropic 无 fallback 标注 |
| `ModelsJsonSchema.CompatDef`（:151）/`ModelsJsonCompat` | 8 键全不解析 ⇒ 手写真键被**静默丢弃**（B98 登记的面） |
| `OpenAICompletionsMessageConverter`（**496 行，差 4 行破 500**） | include_usage 恒发（:230）；tool 消息无 name；无桥消息；assistant 默认 content 省略；thinking 走签名字段；无 tool_stream/priority/providerOptions |
| `AnthropicRequestBuilder`（371 行） | betas 数组在 :135-152（INLINE_TOOLS 等），无 fallback beta/params |
| 类型 | `PricingInfo(double input, output, cacheRead, cacheWrite, List<CostTier>)` |

---

## 4. 决策

| # | 决策 |
|---|---|
| D1 | 8 字段**扁平加进 ModelCompat canonical**（追加在末尾，31→39 组件），与既有 31 字段同形；便捷构造全部默认缺席值（null/false）。嵌套类型仅 2 个：`VercelGatewayRouting(List<String> only, order)` 与 `AllowedFallbackModel(String provider, model, PricingInfo cost)`，各开一个 catalog 小文件（同 `ChatTemplateKwargValue` 先例）。E5（737→约 820 行）继续在册，本包不拆（拆构造器是独立重构活，避免混入行为包）。 |
| D2 | #4 的解析语义：raw 三态，`CompatResolver.forCompletions` 给探测默认 **TRUE**（`pick(c, TRUE)`）；消费点写 `!Boolean.FALSE.equals(...)`（≙ pi 的 `!== false`）。 |
| D3 | #5/#8 的内置目录生产者放 `CatalogCompatRules`：**zaiToolStream** 对 `"zai"`/`"zai-coding-cn"` 恒 TRUE（pi 数据 11/11 全 true，与具体模型无关）；**allowedFallbackModels** 仅 provider `"anthropic"` 且 model id **精确等于** `"claude-fable-5"`（pi 全目录唯一），两条 opus 成本 5/25/0.5/6.25（tiers 空）。 |
| D4 | #6/#7 是**显式-only**：解析面不补探测默认（#6 getCompat 直接透传；#7 消费点读 raw model.compat，与 openRouterRouting 同形）；缺席恒不发键。 |
| D5 | #2 的桥消息：在 converter 的消息循环加 `lastRole` 记账（严格照 pi :1458-1462 形状），三处插桥；桥文案逐字 `"I have processed the tool results."`。图片桥位置＝现有合成图片 user 消息**之前**。 |
| D6 | #3：思考转文本时 assistant content 用 SDK 的 `ChatCompletionAssistantMessageParam.Content.ofArrayOfContentParts(...)` 发**块数组**（`ChatCompletionContentPartText`，CompletionImageWire 有 user 侧先例）；该分支下 reasoning_content/reasoning_details 一律不写。块序＝思考块（各一块，`\n\n` 是 pi 单块文本内的 join——多思考块 pi 合成**一个** text 块）在前、可见文本逐块在后。⚠️ 实施时以 pi 夹具逐字核：`thinkingText` 是一个块，`...assistantTextParts` 是多个块。 |
| D7 | converter 496 行已临界：B98 的 body 级四写点（#4/#5/#6/#7）抽进新类 **`CompletionsCompatWire`**（包私有，同 `ThinkingFormatWriter` 先例）；消息形三处（#1/#2/#3）改在既有 `addAssistantMessage`/toolResult 组循环内，超限则同拆该类。 |
| D8 | #8 请求半：beta 加进 `AnthropicRequestBuilder:135` 的 betas（常量 `server-side-fallback-2026-07-01`）；`fallbacks` 在 build 末段（pi :1281 在 toolChoice 后）经树 putAdditionalBodyProperty 发 `[{model}]`；空/缺省略。流内半＋成本归 B148（台账补记）。 |
| D9 | models.json：CompatDef 加 8 键（5 Boolean、1 Integer、1 嵌套 `{only,order}`、1 嵌套数组 `{provider,model,cost:{…}}`，cost 复用既有 CostTierDef 同款解析）；ModelsJsonCompat 三个构造点（raw merge＋两层归一）逐键照既有 `?? ` 合流；未知键仍静默（schema additionalProperties）。 |
| D10 | baseten 的 22 条冗余 true **不搬**（Java 不携带 baseten，探测默认已是 true；携带时随 B132 触发条件）。 |

---

## 5. 红先测试（生产未动落夹具，录红集）

新类 `CompletionsCompatB98WireTest`（RecordingHttpServer 抓体，按键取值）：

| 用例 | 断言 | 红 |
|---|---|---|
| U1 usage 门关 | 显式 compat false ⇒ 请求体**无** stream_options；对照：缺席 ⇒ 有 include_usage=true | 1 |
| U2 zai 内置 | `ModelData.zaiModels()` glm-4.7 ＋请求工具 ⇒ 体含 `"tool_stream":true`；无工具 ⇒ 不含 | 1 |
| U3 vllm | 显式 priority=-1 ⇒ 体含 `priority:-1`；缺席 ⇒ 无该键 | 1 |
| U4 vercel | `{only:["bedrock","anthropic"]}` ⇒ `providerOptions.gateway.only` 逐字、无 order；`{order:[...]}` 同形；空表 ⇒ 无 providerOptions | 1 |
| U5 name | 显式 flag ＋ ToolResultMessage 带 toolName ⇒ tool 消息含 `"name"`；无 toolName/flag 关 ⇒ 不含 | 1 |
| U6a 桥（文本） | tool 结果后**直接跟 user** ＋flag ⇒ 中间 assistant 消息 content 逐字桥文案；flag 关 ⇒ 无 | 1 |
| U6b 桥（图片） | 图片合成 user 之前同样有桥（flag 开/关两态） | 1 |
| U6c 空 content | flag 开 ＋ assistant 只带 tool_calls ⇒ 该 assistant 消息 content 为 `""`（不是省略） | 1 |
| U7 thinking-as-text | 显式 flag ＋ 历史 assistant 带 thinking+text ⇒ content 为两块数组（思考文本在前）且**无** reasoning_content；思考-only ⇒ 单块数组 | 1 |
| CatalogCompatRulesTest | zai/zai-coding-cn ⇒ zaiToolStream TRUE，其余 provider null；`anthropic`+`claude-fable-5` ⇒ fallback 列表恰 2 条（model 与 cost 逐字），其它 anthropic id ⇒ null | 2 |
| AnthropicFallbacksWireTest | 内置 fable-5 请求 ⇒ betas 含 `server-side-fallback-2026-07-01`、fallbacks=`[{model:claude-opus-4-8},{model:claude-opus-5}]`；对照 sonnet ⇒ 两者皆无 | 1 |
| ModelsJsonCompatTest（扩展） | models.json 写全 8 键 ⇒ 解析后逐键存活（含 vercel/fallback 的嵌套与 cost 数值） | 1 |

预计红集 **13 左右**（以实测为准录数）。

## 6. 变异探针（实现后；grep 确认落地→录红→还原→复绿）

| 探针 | 变异 | 预期红 |
|---|---|---|
| M1 | include_usage 改回恒发 | U1（1） |
| M2 | CatalogCompatRules zaiToolStream 改恒 null | U2＋catalog 断言（2） |
| M3 | 门改成「有任何 tools（含历史 [] 分支）就发」 | U2 的无工具对照（1） |
| M4 | priority 放到思考链之后/读成 compat 默认 0 | U3 缺席对照（1） |
| M5 | vercel 空表也发 providerOptions | U4 空表对照（1） |
| M6 | tool name 忽略 toolName 非空门 | U5 对照（1） |
| M7 | 删三处桥中任一处 | U6a/U6b 各对应（分三次跑，各 1） |
| M8 | thinking-as-text 仍写 reasoning_content／块序反转 | U7（1-2） |
| M9 | fallback beta 恒加／params 发 cost/provider | Anthropic 夹具对照＋逐字断言（1-2） |

## 7. 提交次序

1. `test(ai)` 红夹具（录红集）。
2. `feat(ai)`：ModelCompat 8 组件＋两个新小类型＋便捷构造默认（先让编译过）。
3. `feat(ai)`：CompatResolver 探测面（#4 true 默认）＋CatalogCompatRules（#5/#8 生产者）。
4. `feat(ai)`：ModelsJsonSchema/CompatDef＋ModelsJsonCompat 合流（#1–#8 显式通道）。
5. `feat(ai)`：CompletionsCompatWire＋converter 七字段消费点。
6. `feat(ai)`：AnthropicRequestBuilder fallback beta＋params。
7. M1–M9 探针，记录回填 §8。
8. `docs(04,41,51,map)`：B98 销号、B148 补记流内半、§7 批次行、map/02 行 26（Model.compat）更新、banner 翻 ✅。

每步 reactor 绿；显式路径 stage；文件 ≤500 行（checkstyle＋行数核验）。

## 8. 验证（闭环回填）

- ai 模块汇总行 Failures 0 / Errors 0，计数录实际值（基线 **1456**）。
- checkstyle BUILD SUCCESS；无 `System.out.println`；ModelCompat 行数录实际（E5 更新）。
- coding-agent/tui/evals `-am` 反应堆 SUCCESS。
- 探针红集表＋偏差记录。
