# 58 - A-09：`compat.thinkingFormat` 的十一种思考开关形状设计

**状态：设计待审（2026-09-28）。尚无代码。**

> 本包裁决 `docs/48 §5` 的 **A-09** 行（P1）与 `docs/53 §4.4` 归属表分给它的四个 compat 字段
> （`thinkingFormat` / `chatTemplateKwargs` / `chatTemplateArgs` / `supportsReasoningEffort`）。
> 上游：`docs/41:76`、`docs/57 §1.3`（与 A-10 的边界）、`docs/32 B127`（Responses 的平行通道）。
> pi 锚点：`3390bd93630965a12a0a1a5c36ce890ec22f7e1d`。**一切 pi 取证走
> `git show 3390bd936:<path>`，不读工作树。**
>
> ⚠️ **本包不是「补十个分支」，是补「OpenAI 兼容车道上一个思考字段都不发」这件事。**
> 设计期实测：`OpenAICompletionsMessageConverter` 今天**没有任何** `reasoning_effort` /
> `thinking` / `enable_thinking` 的写点（`grep -n reasoning` 只命中注释与回放路径）
> ⇒ 连默认的 `openai` 形状都没落。十个显式分支是**同一处缺口**的十种填法。
>
> ⚠️ **A-10 的「归属前移」**：`clampThinkingLevel` 的接线（pi `:741-742`）已在 A-10 落在
> `SimpleOptions.clampedReasoningEffort`（`SimpleOptions.java:195`）。**本包直接复用，
> 不再接一次夹取**（`docs/57 §12.3`）。

**参考台账：** `docs/32`（B127、B98）、`docs/41:76`、`docs/53 §4.4`
**参考设计：** `docs/57`（A-10，本包的前置：`maxTokens` 生产者与顶层预算字段）、`docs/53`（compat 解析层）
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`

---

## 1. 范围

### 1.1 做

1. **四个 compat 字段**（`ModelCompat` 19 → 23 组件）：`thinkingFormat` /
   `chatTemplateKwargs` / `chatTemplateArgs` / `supportsReasoningEffort`。
2. **十一个形状的请求期实现**（pi `openai-completions.ts:873-970`）：`openai`（缺省）、`zai`、
   `qwen`、`qwen-chat-template`、`chat-template`、`baseten`、`deepseek`、`openrouter`、
   `ant-ling`、`together`、`string-thinking`。
3. **`$var` 解析**（pi `:1026-1067`）：`thinking.enabled` / `thinking.effort` / `thinking.budget`
   ＋ `omitWhenOff`。
4. **探测补齐**（pi `:1629`/`:1637-1638`/`:1646-1656`）：新增 `isGrok`；
   `supportsReasoningEffort` 与 `thinkingFormat` 的探测取值。
5. **目录标注**（pi `generate-models.ts` 的 per-provider compat 常量）：`ModelData` 的内置条目
   开始携带 `CatalogCompatRules.completions(provider, name)`。
6. **models.json 四个新键**（`ModelsJsonSchema.CompatDef` ＋ `ModelsJsonConfig.compatOf`）。

### 1.2 不做

- **`buildChatTemplateValues` 的两个消费点之外的东西**：`openRouterRouting`（A-02）、
  `vercelGatewayRouting`、`zaiToolStream` 本包不动。
- **`thinkingBudgets` 选项通道**：仍不接（A-10 §6 R7 已登记）。`$var: thinking.budget` 走
  `ThinkingBudgets.DEFAULT`，与 pi 的 `options.thinkingBudgets === undefined` 等价。
- **Responses 车道的平行 `reasoningEffort` 通道**（`ResponsesOptions`，B127）：那是 Responses，
  不属 A-09。要收口得先裁 `ApiOptions.extra` 五个键的存废，本包不碰。
- **`requiresThinkingAsText` / `requiresReasoningContentOnAssistantMessages`**：回放侧，
  已各自有主（后者 A-07 已落）。本包只做**请求侧**。

### 1.3 与 A-10 的边界（一句话）

| | 管什么 | 落点 |
|---|---|---|
| **A-10（已闭环）** | 请求体的**字段名与值**：`max_tokens` 用哪个键、发多少、顶层思考预算字段叫什么 | `SimpleOptions` ＋ `writeThinkingTokenBudget` |
| **A-09（本包）** | 请求体的**思考开关长什么样**：十一个形状 ＋ `$var` | 新增 `ThinkingFormatWriter`，插在同一个位置 |

---

## 2. pi 侧取证

### 2.1 十一个形状总表

所有分支都带 `&& model.reasoning` 门（除末两支合取）。pi `:873-970`，`model` 为
`Model<"openai-completions">`，`options.reasoningEffort` ＝ 夹取后的级别（缺失 ＝ 关思考）。

| `thinkingFormat` | 线格字段 | 门 |
|---|---|---|
| `openai`（缺省） | `reasoning_effort = map[level] ?? level`；无 effort 时若 `map.off` 是**字符串** ⇒ `reasoning_effort = map.off` | `supportsReasoningEffort` |
| `zai` | `thinking = {type:"enabled", clear_thinking:false}` / `{type:"disabled"}`；`reasoning_effort`（`map[level]` **`=== undefined` 才回落级别名**） | effort ＋ `supportsReasoningEffort` |
| `qwen` | 顶层 `enable_thinking = !!effort`；`reasoning_effort`（`?? 级别名`） | 同上 |
| `qwen-chat-template` | `chat_template_kwargs = {enable_thinking: !!effort, preserve_thinking: true}` | **无门**（恒发） |
| `chat-template` | `chat_template_kwargs = build(map)`（见 §2.4） | 空 ⇒ **什么都不写，且不回落 openai** |
| `baseten` | `chat_template_args = build(map)`；`reasoning_effort`（无 effort 时取 `map.off`，同样 `=== undefined` 语义） | `supportsReasoningEffort` |
| `deepseek` | `thinking = {type:"enabled"}` ／ `{type:"disabled"}`（后者需 `map.off !== null`）；`reasoning_effort`（`?? 级别名`） | effort ＋ `supportsReasoningEffort` |
| `openrouter` | `reasoning = {effort: map[level] ?? level}` ／ `{effort: map.off ?? "none"}`（后者需 `map.off !== null`） | — |
| `ant-ling` | `reasoning = {effort: map[level]}`，**仅当映射值是字符串** | effort 非空 |
| `together` | `reasoning = {enabled: !!effort}`；`reasoning_effort`（`?? 级别名`） | effort ＋ `supportsReasoningEffort` |
| `string-thinking` | `thinking = map[level] ?? level` ／ `map.off ?? "none"`（后者需 `map.off !== null`） | — |

`map` ≙ `model.thinkingLevelMap`（`types.ts:86`，`Partial<Record<ModelThinkingLevel, string|null>>`）。

### 2.2 形态链条（pi `openai-completions.ts:873-970`，逐字）

```ts
873	if (compat.thinkingFormat === "zai" && model.reasoning) {
878	    zaiParams.thinking = options?.reasoningEffort ? { type: "enabled", clear_thinking: false } : { type: "disabled" };
879	    if (options?.reasoningEffort && compat.supportsReasoningEffort) {
880	        const mappedEffort = model.thinkingLevelMap?.[options.reasoningEffort];
881	        const effort = mappedEffort === undefined ? options.reasoningEffort : mappedEffort;
882	        if (typeof effort === "string") { zaiParams.reasoning_effort = effort; }
886	} else if (compat.thinkingFormat === "qwen" && model.reasoning) {
887	    (params as any).enable_thinking = !!options?.reasoningEffort;
888	    if (options?.reasoningEffort && compat.supportsReasoningEffort) {
889	        const effort = model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
890	        if (typeof effort === "string") { (params as any).reasoning_effort = effort; }
894	} else if (compat.thinkingFormat === "qwen-chat-template" && model.reasoning) {
895	    (params as any).chat_template_kwargs = { enable_thinking: !!options?.reasoningEffort, preserve_thinking: true };
899	} else if (compat.thinkingFormat === "chat-template" && model.reasoning) {
900	    const chatTemplateKwargs = buildChatTemplateValues(model, options, compat.chatTemplateKwargs, thinkingBudget);
901	    if (chatTemplateKwargs) { (params as any).chat_template_kwargs = chatTemplateKwargs; }
904	} else if (compat.thinkingFormat === "baseten" && model.reasoning) {
909	    const chatTemplateArgs = buildChatTemplateValues(model, options, compat.chatTemplateArgs, thinkingBudget);
910	    if (chatTemplateArgs) { basetenParams.chat_template_args = chatTemplateArgs; }
913	    if (compat.supportsReasoningEffort) {
914	        const requestedEffort = options?.reasoningEffort;
915	        const mappedEffort = requestedEffort ? model.thinkingLevelMap?.[requestedEffort] : model.thinkingLevelMap?.off;
916	        const effort = mappedEffort === undefined ? requestedEffort : mappedEffort;
917	        if (typeof effort === "string") { basetenParams.reasoning_effort = effort; }
921	} else if (compat.thinkingFormat === "deepseek" && model.reasoning) {
922	    if (options?.reasoningEffort) { (params as any).thinking = { type: "enabled" }; }
924	    else if (model.thinkingLevelMap?.off !== null) { (params as any).thinking = { type: "disabled" }; }
927	    if (options?.reasoningEffort && compat.supportsReasoningEffort) {
928	        (params as any).reasoning_effort = model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
931	} else if (compat.thinkingFormat === "openrouter" && model.reasoning) {
934	    if (options?.reasoningEffort) {
935	        openRouterParams.reasoning = { effort: model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort };
938	    } else if (model.thinkingLevelMap?.off !== null) {
939	        openRouterParams.reasoning = { effort: model.thinkingLevelMap?.off ?? "none" };
941	} else if (compat.thinkingFormat === "ant-ling" && model.reasoning && options?.reasoningEffort) {
942	    const effort = model.thinkingLevelMap?.[options.reasoningEffort];
943	    if (typeof effort === "string") { (params as ...).reasoning = { effort }; }
946	} else if (compat.thinkingFormat === "together" && model.reasoning) {
951	    togetherParams.reasoning = { enabled: !!options?.reasoningEffort };
952	    if (options?.reasoningEffort && compat.supportsReasoningEffort) {
953	        togetherParams.reasoning_effort = model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
955	} else if (compat.thinkingFormat === "string-thinking" && model.reasoning) {
957	    if (options?.reasoningEffort) { stringThinkingParams.thinking = map?.[options.reasoningEffort] ?? options.reasoningEffort; }
959	    else if (model.thinkingLevelMap?.off !== null) { stringThinkingParams.thinking = model.thinkingLevelMap?.off ?? "none"; }
962	} else if (options?.reasoningEffort && model.reasoning && compat.supportsReasoningEffort) {
963	    // OpenAI-style reasoning_effort
964	    (params as any).reasoning_effort = model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort;
965	} else if (!options?.reasoningEffort && model.reasoning && compat.supportsReasoningEffort) {
966	    const offValue = model.thinkingLevelMap?.off;
967	    if (typeof offValue === "string") { (params as any).reasoning_effort = offValue; }
970	}
```

**三条从链条形状读出来、必须照抄的事实：**

1. **十二个 `if/else if` 臂里，每一个都带 `model.reasoning`**（第 11/12 支合取在中间）。
   ⇒ `!model.reasoning` 时整条链**恒不写**（等价化简，§4.5 会用它）。
2. **`else if` ⇒ 命中即终止**。「`chat-template` 的 map 为空」**不会**回落成 `openai` 的
   `reasoning_effort`，而是**什么都不发**。同理 `ant-ling` 无 effort。
3. **落在 `:962-970` 的只有 `thinkingFormat === "openai"`**（其余十值都在前面被吃掉）。

### 2.3 `$var` 解析（pi `:1026-1067`，逐字）

```ts
1026	function buildChatTemplateValues(model, options, values, thinkingBudget?) {
1032	    const resolvedValues: Record<string, ResolvedChatTemplateKwargValue> = {};
1034	    for (const [key, value] of Object.entries(values)) {
1035	        const resolved = resolveChatTemplateKwargValue(model, options, value, thinkingBudget);
1036	        if (resolved !== undefined) { resolvedValues[key] = resolved; }
1041	    return Object.keys(resolvedValues).length > 0 ? resolvedValues : undefined;
1044	function resolveChatTemplateKwargValue(model, options, value, thinkingBudget?) {
1050	    if (typeof value !== "object" || value === null) { return value; }        // 字面量原样
1054	    const reasoningEffort = options?.reasoningEffort;
1055	    if (!reasoningEffort && value.omitWhenOff) { return undefined; }          // omitWhenOff 门
1058	    if (value.$var === "thinking.enabled") { return !!reasoningEffort; }
1061	    if (value.$var === "thinking.budget")  { return thinkingBudget; }         // undefined ⇒ 键被删
1065	    const mappedValue = reasoningEffort ? model.thinkingLevelMap?.[reasoningEffort]
1066	                                        : model.thinkingLevelMap?.off;
1066	    return mappedValue === undefined ? reasoningEffort
1067	         : typeof mappedValue === "string" ? mappedValue : undefined;
1068	}
```

`value === null` 走 `:1050` 的早返回 ⇒ **字面量 `null` 是「写这个键，值是 null」**
（不是「不写」）。`ChatTemplateKwargValue` 的完整类型在 `types.ts:87-95`。

### 2.4 ★ 两派 null 语义（本包最容易做错的一处）

pi 的两处写法**故意不同**，Java 的 `ThinkingLevelMap`（`Optional<String>` ＋ `hasEntry`）能精确表达：

| pi 写法 | 键缺席 `undefined` | 值 `null`（显式不支持） | 字符串 |
|---|---|---|---|
| `map?.[k] ?? k`（qwen / deepseek / openrouter / together / string-thinking） | 级别名 | **级别名** | 映射值 |
| `map?.[k] === undefined ? k : map[k]` ＋ `typeof === "string"`（zai / baseten） | 级别名 | **不写** | 映射值 |
| `map?.[k]` ＋ `typeof === "string"`（**ant-ling**） | **不写** | **不写** | 映射值 |
| `map?.off !== null`（deepseek / openrouter / string-thinking 的关闭支） | 真（写关闭） | 假（不写） | 真 |
| `map?.off ?? "none"`（openrouter / string-thinking 的值） | `"none"` | `"none"` | 映射值 |
| `map?.off` ＋ `=== undefined`（baseten 无 effort 时） | 不写 | 不写 | 映射值 |

⇒ Java 映射（`ThinkingLevelMap.java:48/:53/:70`）：

```java
// `?? k` 派：
map.mapped(level).orElse(level.label())
// `=== undefined` 派（再判非 null）：
map.hasEntry(level) ? map.mapped(level).orElse(null) : level.label()
// ant-ling 派（不回落）：
map.mapped(level)   // 键缺席／显式 null 都是空 ⇒ 不写
// `off !== null`：map.supportsExplicitOff()
// `off ?? "none"`：map.mapped(ModelThinkingLevel.off()).orElse("none")
```

### 2.5 探测与目录的**分工**（pi 侧两处来源）

**(a) 探测**（`openai-completions.ts`）——只产出六个值：

```ts
1629	const isGrok = provider === "xai" || baseUrl.includes("api.x.ai");
1637	supportsReasoningEffort: !isGrok && !isZai && !isMoonshot && !isTogether
1638	    && !isCloudflareAiGateway && !isNvidia && !isAntLing,
1646	thinkingFormat: isDeepSeek ? "deepseek" : isZai ? "zai" : isTogether ? "together"
1650	    : isAntLing ? "ant-ling" : isOpenRouter ? "openrouter" : "openai",
1659	chatTemplateKwargs: {},     // 恒空对象
1660	chatTemplateArgs: {},       // 恒空对象
```
`:1685-1721 getCompat` 的覆盖律同其余字段：`model.compat.X ?? detected.X`。

**(b) 目录**（`generate-models.ts` 的 per-provider 常量）——补齐探测给不出的值：

| pi provider | 常量位置 | `thinkingFormat` | 备注 |
|---|---|---|---|
| `zai` / `zai-coding-cn` | `:1416` | `zai` | ＋`supportsReasoningEffort: thinkingLevelMap !== undefined`（`:1396`，**数据驱动**） |
| `moonshotai` / `moonshotai-cn` | `:2426` | `deepseek` | 探测会误判成 `openai` |
| `xiaomi` / `xiaomi-token-plan-*` | `:2476` | `deepseek` | 同上 |
| `qwen-token-plan*` | `:2533` | `qwen` | ＋`supportsReasoningEffort: true` |
| `deepseek` | `:2784` | `deepseek` | 与探测同值（冗余） |
| `ant-ling` | `:2874` | `ant-ling` | 同上 |
| `together` | `:189-197` | `together` | 四档常量，随模型分 `openai`／`together` |
| `baseten` | `:1452-1461` | `baseten` | ＋`chatTemplateArgs: {enable_thinking: {$var:"thinking.enabled"}}` |
| `opencode` / `opencode-go` | `:2217`/`:2236` | `deepseek`／`qwen` | java 不携带 |
| `fireworks` | `:1627` | `openai` | java 不携带 |

**(c) 三个形状在任何生成数据里都没有生产者**：`chat-template`、`qwen-chat-template`、
`string-thinking`（`grep -o '"thinkingFormat":"..."' providers/data/*.json` 的结果里零命中）
⇒ 它们**只能**由用户的 models.json 给出。

### 2.6 pi 的行为夹具（本包的验收口径来源）

| 夹具 | 钉住什么 |
|---|---|
| `test/openai-completions-thinking-token-budget.test.ts:105-201` | 11 条：预算取值/夹取/字段名优先 ＋ `$var` 的 `thinking.budget`（`:182-185`）与关思考时省略（`:200`） |
| `test/baseten-models.test.ts:55-97` | `chat_template_args == {enable_thinking:true}` ＋ `reasoning_effort` `undefined`／`"high"`／`"none"` 三态 |
| `test/together-models.test.ts:39-76` | `together` 与 `openai` 两档常量同 provider 并存 |
| `test/openrouter-reasoning-options.test.ts:28-107` | `reasoning == {effort:"low"}`；强制推理模型下**省略** `reasoning`；可选模型显式 `{effort:"none"}` |
| `test/zai-coding-plan-models.test.ts:19` | zai 目录常量 |

⚠️ `openai-completions-thinking-token-budget.test.ts` 的 `capture()`（`:73-98`）用
`onPayload` 抓请求体 —— java 的对应物是 `RecordingHttpServer`（`docs/57 §10`）。

---

## 3. pi-java 现状（缺口逐处）

| # | 文件:行 | 现状 | A-09 后 |
|---|---|---|---|
| 1 | `catalog/ModelCompat.java:240-258` | 19 组件，**无**这四个 | 23 组件 |
| 2 | `catalog/CompatResolver.java:54-118` | `forCompletions` 已算出全部谓词，但**不产出** `thinkingFormat`/`supportsReasoningEffort`；无 `isGrok` | 产出 |
| 3 | `catalog/CompatResolver.java:219-256` | `resolved(...)` 16 个形参 | 不扩（新增 `withCompletions`） |
| 4 | `protocol/OpenAICompletionsMessageConverter.java:216-218` | 落点区只有预算字段与 samplingParams | 中间插形态链条 |
| 5 | 同上，全文 | **零处**写 `reasoning_effort`/`thinking`/`enable_thinking` | 十一个形状 |
| 6 | `catalog/CatalogCompatRules.java:74-80` | `completions(provider, modelId)` 只有 `deepseek-v4-pro` 一条 | ＋provider 级常量 |
| 7 | `provider/builtin/ModelData.java:126` | `model(...)` 恒给 `ModelCompat.NONE` ⇒ moonshot/xiaomi/qwen 的**目录值丢失** | 走 `CatalogCompatRules` |
| 8 | `provider/ModelsJsonSchema.java:125-156` | `CompatDef` 无这四个键 | ＋4 键 |
| 9 | `provider/ModelsJsonConfig.java:316` | `compatOf` 用**十九参**构造 | 零改签（见 §4.3） |
| 10 | `protocol/SamplingParamsWriter.java:46-61` | 类型化重写的名单是 `temperature`/`max_tokens`/`max_completion_tokens` | ＋`reasoning_effort`（R5） |

**可复用的现成件**（不重复造）：
`SimpleOptions.clampedReasoningEffort`（`:195`，A-10 归属前移）、
`SimpleOptions.clampedThinkingBudget`（`:225`）、`ThinkingLevelMap.mapped/hasEntry/supportsExplicitOff`、
`ModelThinkingLevels.clamp`、`ModelCapability.THINKING`（≙ pi 的 `model.reasoning`）、
`SDK ChatCompletionCreateParams.Builder.reasoningEffort(ReasoningEffort.of(s))`
（`ReasoningEffort.of` 收**任意**字符串，见 §4.4）。

---

## 4. 实现方案

### 4.1 新枚举 `catalog/ThinkingFormat.java`

十一个**纯判别字面量**（无附带数据）⇒ 按 `CLAUDE.md` 用 `enum` ＋ `@JsonValue`（与
`MaxTokensField`／`ThinkingTokenBudgetField` 同形）。

```java
public enum ThinkingFormat {
    OPENAI("openai"), OPENROUTER("openrouter"), DEEPSEEK("deepseek"),
    TOGETHER("together"), BASETEN("baseten"), ZAI("zai"), QWEN("qwen"),
    CHAT_TEMPLATE("chat-template"), QWEN_CHAT_TEMPLATE("qwen-chat-template"),
    STRING_THINKING("string-thinking"), ANT_LING("ant-ling");

    private final String wireName;
    ThinkingFormat(String wireName) { this.wireName = wireName; }
    @JsonValue public String wireName() { return wireName; }

    /** 未知取值 ⇒ 空（由 models.json 的 schema 决定「响亮抛错」，见 §4.7）。 */
    public static Optional<ThinkingFormat> parse(String raw) { ... }
}
```

### 4.2 新 ADT `catalog/ChatTemplateKwargValue.java`

pi 的类型是 `string | number | boolean | null | {$var, omitWhenOff?}`（`types.ts:87-95`）——
**变体携带不同字段** ⇒ sealed interface ＋ record（`CLAUDE.md` 的判据）。

```java
public sealed interface ChatTemplateKwargValue {

    /** pi 的 {@code string | number | boolean | null}：原样上线。 */
    record Literal(Object value) implements ChatTemplateKwargValue {}

    /** pi 的 {@code {$var, omitWhenOff?}}。 */
    record Var(ThinkingVar var, boolean omitWhenOff) implements ChatTemplateKwargValue {}

    /** 三个 pi 控制的取值（`types.ts:93`）。 */
    enum ThinkingVar {
        ENABLED("thinking.enabled"), EFFORT("thinking.effort"), BUDGET("thinking.budget");
        private final String wireName;
        ThinkingVar(String wireName) { this.wireName = wireName; }
        @JsonValue public String wireName() { return wireName; }
        public static Optional<ThinkingVar> parse(String raw) { ... }
    }
}
```

`Literal` 的 `Object` 由 models.json 层校验成 `String`/`Number`/`Boolean`/`null`
（§4.7）—— 不在 `catalog` 里引 SDK 的 `JsonValue`（那会让 catalog 依赖 SDK）。

### 4.3 `ModelCompat`：＋4 组件（19 → 23）＋ 保留十九参便捷构造

```java
public record ModelCompat(boolean allowEmptySignature,
                          /* … 原 19 个组件，位置不动 … */
                          Boolean supportsMaxOutputTokens,
                          // ── 包 A-09 ──
                          ThinkingFormat thinkingFormat,
                          Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                          Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                          Boolean supportsReasoningEffort) {
```

compact 构造器：两个 map `null` ⇒ `Map.of()`（pi 的 `?? {}`；**不需要三态**，javadoc 写明）；
`thinkingFormat`／`supportsReasoningEffort` 保持 `null` ＝「按车道探测」。

**加一个十九参便捷构造**（＝A-10 第 6 步之后的规范形态），四个新字段传 `null`：

```java
    public ModelCompat(boolean allowEmptySignature, /* …19 个… */ Boolean supportsMaxOutputTokens) {
        this(allowEmptySignature, /* …19 个… */ supportsMaxOutputTokens,
             null, Map.of(), Map.of(), null);
    }
```

⇒ `ModelsJsonConfig.compatOf`（`:316`）与 `CatalogCompatRules` **零改签**。十六／十四／九／
五／四／三参那几档**不动**（它们最终也落到这一档）。

### 4.4 `CompatResolver`：不扩 `resolved`，新增 `withCompletions`

`resolved(...)` 已经 16 个位置形参；把 4 个新字段塞进去会变成 20 个且**三条无关车道跟着改签**。
四个新字段**只被 completions 读**（§2.1 的形状链全在 `openai-completions.ts`）⇒ 单独一个私有
方法，另外三条车道（anthropic／responses／mistral）**零改动**：

```java
    /** 包 A-09：只属于 completions 车道的四个字段（其余三车道不读 ⇒ 不经过这里）。 */
    private static ModelCompat withCompletions(ModelCompat c, ThinkingFormat detectedFormat,
                                               Boolean detectedSupportsReasoningEffort) {
        return new ModelCompat(c.allowEmptySignature(), /* …19 个原样…… */
            c.supportsMaxOutputTokens(),
            c.thinkingFormat() != null ? c.thinkingFormat() : detectedFormat,
            c.chatTemplateKwargs(),                     // 探测恒空 ⇒ 显式值直接用
            c.chatTemplateArgs(),
            pick(c.supportsReasoningEffort(), detectedSupportsReasoningEffort));
    }
```

`forCompletions` 的尾声（`CompatResolver.java:91-117` 之后）——**探测值**照 §2.5(a)：

```java
        var isGrok = provider.equals("xai") || url.contains("api.x.ai");   // pi :1629
        var detectedFormat = isDeepSeek ? ThinkingFormat.DEEPSEEK
            : isZai ? ThinkingFormat.ZAI
            : isTogether ? ThinkingFormat.TOGETHER
            : isAntLing ? ThinkingFormat.ANT_LING
            : isOpenRouter ? ThinkingFormat.OPENROUTER
            : ThinkingFormat.OPENAI;                                        // pi :1646-1656
        var detectedEffort = !isGrok && !isZai && !isMoonshot && !isTogether
            && !isCloudflareAiGateway && !isNvidia && !isAntLing;           // pi :1637-1638
        return withCompletions(resolved(compat, /* …原样…… */ null), detectedFormat, detectedEffort);
```

⚠️ **不许把目录值塞进探测**（R7）：`moonshotai`／`xiaomi`／`qwen-token-plan*` 的 pi 值是
**目录覆盖**，不是探测。混进探测会让「models.json 用户显式写 `thinkingFormat: "openai"`」
被吞掉。

### 4.5 新增 `protocol/ThinkingFormatWriter.java`

`OpenAICompletionsMessageConverter` 已 513 行（A-10 时已超限），**不再往里加** ——
照 `SamplingParamsWriter` 的先例开新文件，converter 只留一行调用。

落点（converter `:216-218`，顺序照 pi `:873 → :976 → :996`）：

```java
        // 包 A-10：顶层思考预算字段（pi :972-978）
        writeThinkingTokenBudget(builder, request, compat);     // ← A-09 要把它改成收 budget
        // 包 A-09：思考开关的形状（pi :873-970）
        ThinkingFormatWriter.apply(builder, request, compat, budget);
        // 包 A-10：模型级采样参数（pi :996-999，body 的最后一个变更）
        SamplingParamsWriter.applyToCompletions(builder, request.model());
```

⚠️ **`budget` 必须上提**：pi 在链条**之前**算好 `thinkingBudget`（`:871`）并把它传给
`buildChatTemplateValues`（`:900`/`:909`）的 `$var: thinking.budget`分支，预算字段的写入（`:976`）
只是复用它。所以 A-09 顺带把 `writeThinkingTokenBudget` 的算法上提成一次调用、两个消费者
（**取值零变化**：同一个 `SimpleOptions.clampedThinkingBudget` 同一个 `ceiling`）。

主体（`switch` 的臂照 §2.1 逐条，**exhaustive、无 `default`** ⇒ 编译器保证十一臂齐全）：

```java
final class ThinkingFormatWriter {

    private ThinkingFormatWriter() {}

    /** pi {@code openai-completions.ts:873-970}。 */
    static void apply(ChatCompletionCreateParams.Builder builder, StreamRequest request,
                      ModelCompat compat, OptionalInt thinkingBudget) {
        var model = request.model();
        // pi 的十二个臂全部合取 `model.reasoning` ⇒ `!reasoning` 时整条链恒不写（§2.2 事实 1）。
        if (model == null || !model.capabilities().contains(ModelCapability.THINKING)
            || compat.thinkingFormat() == null) {
            return;
        }
        // A-10 的归属前移：级别**已经**是夹取过的（pi :741-742）。
        var level = SimpleOptions.clampedReasoningEffort(model, request.reasoning());
        var map = model.thinkingLevelMap();
        var supportsEffort = Boolean.TRUE.equals(compat.supportsReasoningEffort());

        switch (compat.thinkingFormat()) {
            case ZAI -> {
                var thinking = new LinkedHashMap<String, Object>();
                thinking.put("type", level.isPresent() ? "enabled" : "disabled");
                if (level.isPresent()) {
                    thinking.put("clear_thinking", false);        // pi :878
                }
                put(builder, "thinking", thinking);
                if (level.isPresent() && supportsEffort) {
                    strictEffort(map, level.get()).ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case QWEN -> {
                put(builder, "enable_thinking", level.isPresent());
                if (level.isPresent() && supportsEffort) {
                    orLevel(map, level.get()).ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case QWEN_CHAT_TEMPLATE -> {
                var kwargs = new LinkedHashMap<String, Object>();
                kwargs.put("enable_thinking", level.isPresent());
                kwargs.put("preserve_thinking", true);            // pi :897
                put(builder, "chat_template_kwargs", kwargs);
            }
            case CHAT_TEMPLATE ->
                chatTemplateValues(map, level, compat.chatTemplateKwargs(), thinkingBudget)
                    .ifPresent(values -> put(builder, "chat_template_kwargs", values));
            case BASETEN -> {
                chatTemplateValues(map, level, compat.chatTemplateArgs(), thinkingBudget)
                    .ifPresent(values -> put(builder, "chat_template_args", values));
                if (supportsEffort) {
                    // pi :914-916 —— 无 effort 时取 `map.off`，且是 `=== undefined` 语义。
                    var effort = level.isPresent() ? strictEffort(map, level.get())
                        : map.hasEntry(ModelThinkingLevel.off())
                            ? map.mapped(ModelThinkingLevel.off()) : Optional.<String>empty();
                    effort.ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case DEEPSEEK -> {
                if (level.isPresent()) {
                    put(builder, "thinking", Map.of("type", "enabled"));
                } else if (map.supportsExplicitOff()) {           // pi :924
                    put(builder, "thinking", Map.of("type", "disabled"));
                }
                if (level.isPresent() && supportsEffort) {
                    orLevel(map, level.get()).ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case OPENROUTER -> {
                if (level.isPresent()) {
                    put(builder, "reasoning", Map.of("effort", orLevel(map, level.get()).orElseThrow()));
                } else if (map.supportsExplicitOff()) {           // pi :938-940
                    put(builder, "reasoning", Map.of("effort",
                        map.mapped(ModelThinkingLevel.off()).orElse("none")));
                }
            }
            case ANT_LING ->
                // ⚠️ 唯一**不回落级别名**的形状：pi :942-944 只认 `map[level]` 的字符串值。
                level.map(ModelThinkingLevel::of).flatMap(map::mapped)
                    .ifPresent(s -> put(builder, "reasoning", Map.of("effort", s)));
            case TOGETHER -> {
                put(builder, "reasoning", Map.of("enabled", level.isPresent()));
                if (level.isPresent() && supportsEffort) {
                    orLevel(map, level.get()).ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case STRING_THINKING -> {
                if (level.isPresent()) {
                    put(builder, "thinking", orLevel(map, level.get()).orElseThrow());
                } else if (map.supportsExplicitOff()) {           // pi :959-961
                    put(builder, "thinking", map.mapped(ModelThinkingLevel.off()).orElse("none"));
                }
            }
            case OPENAI -> {
                if (level.isPresent() && supportsEffort) {        // pi :962-964
                    orLevel(map, level.get()).ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                } else if (level.isEmpty() && supportsEffort) {   // pi :965-969
                    map.mapped(ModelThinkingLevel.off())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
        }
    }

    /** `map?.[k] ?? k` 派（§2.4 第一行）。 */
    private static Optional<String> orLevel(ThinkingLevelMap map, ThinkingLevel level) {
        return Optional.of(map.mapped(ModelThinkingLevel.of(level)).orElse(level.label()));
    }

    /** `map?.[k] === undefined ? k : map[k]` 派 ＋ `typeof === "string"`（§2.4 第二行）。 */
    private static Optional<String> strictEffort(ThinkingLevelMap map, ThinkingLevel level) {
        if (!map.hasEntry(ModelThinkingLevel.of(level))) {
            return Optional.of(level.label());
        }
        return map.mapped(ModelThinkingLevel.of(level));          // 显式 null ⇒ 空 ⇒ 不写
    }

    /** pi {@code :1026-1042} —— 空 map ⇒ {@link Optional#empty()}（pi 的 {@code undefined}）。 */
    private static Optional<Map<String, Object>> chatTemplateValues(
            ThinkingLevelMap map, Optional<ThinkingLevel> level,
            Map<String, ChatTemplateKwargValue> declared, OptionalInt thinkingBudget) {
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        var out = new LinkedHashMap<String, Object>();
        for (var entry : declared.entrySet()) {
            resolveKwarg(out, entry.getKey(), map, level, entry.getValue(), thinkingBudget);
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * pi {@code :1044-1067}。
     *
     * <p>⚠️ <b>直接写进 {@code out}、不用 {@code Optional} 收返回值</b>：pi 的字面量分支
     * （{@code :1050}）里 {@code null} 是<b>合法的上线值</b>（{@code "key": null}），
     * {@code Optional} 表达不了「值是 null」⇒ 用返回值就会把 {@code Literal(null)} 静默
     * 变成「不写」。本方法的 {@code return} 一律表示「这个键不写」。</p>
     */
    private static void resolveKwarg(Map<String, Object> out, String key, ThinkingLevelMap map,
                                     Optional<ThinkingLevel> level, ChatTemplateKwargValue value,
                                     OptionalInt thinkingBudget) {
        switch (value) {
            case ChatTemplateKwargValue.Literal literal -> out.put(key, literal.value());  // :1050
            case ChatTemplateKwargValue.Var var -> {
                if (level.isEmpty() && var.omitWhenOff()) {
                    return;                                                                 // :1055
                }
                switch (var.var()) {
                    case ENABLED -> out.put(key, level.isPresent());                        // :1058
                    case BUDGET -> thinkingBudget.ifPresent(budget -> out.put(key, budget)); // :1061
                    case EFFORT -> {
                        var target = level.map(ModelThinkingLevel::of);                     // :1065
                        if (target.isPresent()) {
                            if (!map.hasEntry(target.get())) {
                                out.put(key, level.get().label());        // mappedValue === undefined
                            } else {
                                map.mapped(target.get()).ifPresent(s -> out.put(key, s));
                            }                                                             // 显式 null ⇒ 不写
                        } else {
                            map.mapped(ModelThinkingLevel.off()).ifPresent(s -> out.put(key, s));
                        }                                          // off 缺席／null ⇒ 连 undefined 都不写
                    }
                }
            }
        }
    }

    /** `putAdditionalBodyProperty` 的薄包装（统一 `JsonValue.from`）。 */
    private static void put(ChatCompletionCreateParams.Builder builder, String key, Object value) {
        builder.putAdditionalBodyProperty(key, JsonValue.from(value));
    }
}
```

⚠️ **`Literal(null)` 是 R11 那条最容易做错的点**：pi 的 `:1050` 早返回让字面量 `null`
**上线成 `"key": null`**（不是「不写」）。夹具必须专门钉它（§7 第三行探针）。

### 4.6 目录标注：`CatalogCompatRules` ＋ `ModelData`

`ModelData.model(...)`（`:126`）本来就有 `provider` 参数 ⇒ **一行改动**让所有内置条目拿到
目录 compat（与 `BuiltinCatalog.anthropicModel:172` 的先例同形）：

```java
    private static ModelInfo model(String provider, String name, String display, /* … */) {
        return model(provider, name, display, /* … */, CatalogCompatRules.completions(provider, name));
    }
```

`CatalogCompatRules.completions(provider, modelId)`（`:74-80`）扩展成 §2.5(b) 的常量表：

```java
    public static ModelCompat completions(String provider, String modelId) {
        var isNativeDeepSeekPro = "deepseek".equals(provider) && "deepseek-v4-pro".equals(modelId);
        var format = switch (provider) {
            case "zai", "zai-coding-cn" -> ThinkingFormat.ZAI;
            case "moonshotai", "moonshotai-cn", "xiaomi", "xiaomi-token-plan-cn" -> ThinkingFormat.DEEPSEEK;
            case "qwen-token-plan-cn" -> ThinkingFormat.QWEN;
            case "deepseek" -> ThinkingFormat.DEEPSEEK;
            case "ant-ling" -> ThinkingFormat.ANT_LING;
            default -> null;
        };
        if (format == null && !isNativeDeepSeekPro) {
            return ModelCompat.NONE;
        }
        // ⚠️ zai 的 supportsReasoningEffort 在 pi 是**数据驱动**的（generate-models.ts:1396
        //    `thinkingLevelMap !== undefined`）—— java 的内置条目今天没有级别表 ⇒ 取 false
        //    （登记为数据漂移，见 §9）。qwen-token-plan 的 `true` 是 pi 的明文常量（:2537）。
        var supportsEffort = "qwen-token-plan-cn".equals(provider) ? Boolean.TRUE : null;
        return new ModelCompat(false, /* … */, format, Map.of(), Map.of(), supportsEffort);
    }
```

⚠️ 需要 `ThinkingLevelMap` 参与时（zai）走 `ModelData` 侧而非本类 —— 本类拿不到模型的级别表。
**本包取 `false`**，理由与登记见 §9。

### 4.7 models.json 的四个新键

`ModelsJsonSchema.CompatDef`（`:125-156`）追加：

```java
        /** 包 A-09（pi {@code types.ts:696-707}）：十一个形状；未知取值**响亮抛错**（同 maxTokensField）。 */
        @JsonProperty("thinkingFormat") String thinkingFormat,
        /** 包 A-09（pi {@code types.ts:708}）：`chat-template` 形状的 kwargs。 */
        @JsonProperty("chatTemplateKwargs") Map<String, Object> chatTemplateKwargs,
        /** 包 A-09（pi {@code types.ts:710}）：`baseten` 形状的 args。 */
        @JsonProperty("chatTemplateArgs") Map<String, Object> chatTemplateArgs,
        /** 包 A-09（pi {@code types.ts:694}）：端点是否吃 `reasoning_effort`；缺省按探测。 */
        @JsonProperty("supportsReasoningEffort") Boolean supportsReasoningEffort,
```

`ModelsJsonConfig` 侧新增 `chatTemplateValuesOf(Map<String,Object>)`：把 JSON 值分成
`Literal`（string/number/boolean/null）与 `Var`（`{"$var": "...", "omitWhenOff": bool}`），
**`$var` 取值未知或 `Literal` 不是标量 ⇒ 响亮报错**（与 `maxTokensField` 同口径 ——
静默降级会静默改变线格）。然后 `compatOf`（`:316`）用**十九参便捷构造 ＋ 四个具名参数**
收尾（零改签的是前 19 个）。

### 4.8 与 `SamplingParamsWriter` 的交叉（R5）

`reasoning_effort` 若既被本包用类型化 setter 写、又出现在 `samplingParams` 里，会被
`putAdditionalBodyProperty` 写成**两份**（`docs/57 §10` 的实测病理）。⇒
`SamplingParamsWriter.applyToCompletions`（`:46-61`）的类型化重写名单**加上
`reasoning_effort`**：

```java
            if (extra.remove("reasoning_effort") instanceof String effort) {
                builder.reasoningEffort(ReasoningEffort.of(effort));
            }
```

（pi 的 `Object.assign` 是「同名覆盖」⇒ 类型化是唯一保义的落法。）

---

## 5. 裁决点（R1–R10）

| # | 点 | 建议 | 理由 |
|---|---|---|---|
| **R1** | 范围：十一形状全落，含**无生成数据生产者**的三个（`chat-template`/`qwen-chat-template`/`string-thinking`） | **全落** | 形状是纯请求期逻辑、夹具可达；不落＝三个分支永远缺失，且 models.json 用户**今天就能写这三个值** |
| **R2** | `ThinkingFormat` 用 `enum` ＋ `@JsonValue` | **是** | 纯常量闭集（`CLAUDE.md` 判据）；未知值在 schema 层响亮抛错 |
| **R3** | `ChatTemplateKwargValue` 用 sealed（`Literal`/`Var`）＋ 嵌套 `ThinkingVar` enum | **是** | 变体携带不同字段 ⇒ sealed；`catalog` 不引 SDK ⇒ `Literal(Object)` 而非 `JsonValue` |
| **R4** | 两派 null 语义（`??` vs `=== undefined`）**逐形状照抄** | **是** | 统一成一种会改变线格：`zai`/`baseten` 在 `map[k] == null` 时**不发** `reasoning_effort`，其余五形状发级别名 |
| **R5** | `reasoning_effort` 一律走类型化 setter，`SamplingParamsWriter` 名单加它 | **是** | 否则与 `samplingParams` 同键写成两份 |
| **R6** | 用 `switch`（exhaustive）而非查表＋兜底 | **是** | pi 是 `else if`：「命中但不写」≠「回落 openai」（§2.2 事实 2）；`switch` 还能让编译器守住十一臂 |
| **R7** | 探测与目录**分工照抄**：目录值不进探测 | **是** | 否则 models.json 的显式覆盖被吞 |
| **R8** | `resolved(...)` **不扩参**，新增私有 `withCompletions`；其余三条车道零改签 | **是** | 四个字段只被 completions 读 |
| **R9** | `thinkingBudgets` 选项通道 | **仍不接**、登记 | A-10 §6 R7 已裁；加通道＝加一个没有生产者的形状 |
| **R10** | Responses 的平行 `reasoningEffort` 通道（B127） | **不动**、保持登记 | 不属 A-09；要动得先裁 `ApiOptions.extra` 五键存废 |
| **R11** | `Literal(null)` 的「写 null」语义 | **必须保住**，专门夹具 | `Optional` 表达不了 ⇒ 最容易被静默做错的一处 |

---

## 6. 可达性：今天就能观察到的模型

| 形状 | 内置目录可达 | 全靠 models.json |
|---|---|---|
| `deepseek` | `deepseek`、`moonshotai`/`moonshotai-cn`、`xiaomi`/`xiaomi-token-plan-cn` | — |
| `zai` | `zai`、`zai-coding-cn`（探测即命中，目录冗余） | — |
| `ant-ling` | `ant-ling` | — |
| `openai`（**含 `supportsReasoningEffort` 生效**） | `openai`、`ollama`（目录未标注 ⇒ 探测给 `openai`） | — |
| `qwen` | `qwen-token-plan-cn`（目录标注） | — |
| `openrouter` | ✗（本仓只有 `openrouter-images`，非 chat 车道） | ✓ |
| `baseten` / `together` | ✗（未携带该 provider） | ✓ |
| `chat-template` / `qwen-chat-template` / `string-thinking` | ✗（生成数据零生产者） | ✓ |

⇒ **本包最重的一条今天可达的修复**：`openai` 形状的 `reasoning_effort`（今天**一个字段都不发**）
＋ `deepseek`/`zai`/`ant-ling` 三个显式形状的 `thinking`/`reasoning` 对象。

---

## 7. 先红方案与探针

夹具：`pi-java-ai/src/test/java/com/pijava/ai/protocol/ThinkingFormatWireTest.java`，
用 `RecordingHttpServer` 抓请求体，**按 §2.1 的十一行各一个用例**，每个形状至少钉
「effort 有/无」两态 ＋ 关闭态（`map.off` 为 `null`／缺席／字符串三态）。

| 步骤 | 夹具 | 预测红 |
|---|---|---|
| 形状链未实现（当前树） | 全部十一形状用例 | 全红（今天**零个**思考字段） |
| 只实现 `openai` 臂 | 余下十形状 | 各自全红，`openai` 转绿 |
| 逐臂累加 | — | 每臂对应用例转绿 |
| **R4 反向探针** | 把 `strictEffort` 改成 `orLevel` | `zai`/`baseten` 在 `map[k]=null` 的用例**必须**恰红 |
| **R6 反向探针** | 把 `chat-template` 的空 map 改成回落到 `openai` 臂 | 该用例**必须**恰红 |
| **R11 反向探针** | 把 `Literal(null)` 改成不写 | 该用例**必须**恰红 |
| `$var` 三取值 ＋ `omitWhenOff` | 独立夹具（照 `openai-completions-thinking-token-budget.test.ts:171-201`） | 与形状链同时红 |
| 目录标注 | `CatalogCompatRulesTest`：`moonshotai`/`xiaomi`/`qwen-token-plan-cn` 三 provider 的 `thinkingFormat` | 改前恰 3 红 |

⚠️ **每一步都必须看到 `Tests run` 行**（`docs/57 §12.5` 的教训：`-Dtest='A+B'` 的 `+` 不是
分隔符，会得到零用例的 `BUILD SUCCESS`；逗号才是）。

---

## 8. 提交计划（7 步）

| # | 提交 | 内容 |
|---|---|---|
| 1 | `docs(ai): design A-09 (thinking format shapes)` | 本文档 |
| 2 | `feat(ai): carry the thinking format compat fields` | §4.1–4.4 ＋ §4.7（字段能进、能解析、**暂无读点**） |
| 3 | `feat(ai): send reasoning_effort on the OpenAI-compatible lane` | `openai` 臂 ＋ R5 ＋ 先红夹具骨架 |
| 4 | `feat(ai): port the zai, qwen, deepseek and ant-ling shapes` | 四臂 |
| 5 | `feat(ai): port the together, openrouter and string-thinking shapes` | 三臂 |
| 6 | `feat(ai): resolve the chat template kwargs variables` | `$var` ＋ `chat-template`／`baseten` ＋ `qwen-chat-template` ＋ R11 |
| 7 | `docs(ai): close out A-09` | `docs/32` 新登记 ＋ `docs/41:76` 划行 ＋ `docs/48 §5`/文首 banner（B91）＋ `docs/57 §12.3` 的归属前移回执 |

---

## 9. 未覆盖与登记（预计）

- **`zai` 的 `supportsReasoningEffort` 数据漂移**：pi 从 models.dev 的 `reasoning_options` 算
  （`generate-models.ts:1396`），java 内置条目无级别表 ⇒ 取 `false`。若 java 的 zai 条目
  日后补上 `thinkingLevelMap`，本判据应改成 `!model.thinkingLevelMap().isEmpty()`
  （**同 pi 的表达式**）而不是常量。
- **`together`／`baseten`／`opencode*`／`fireworks` 的目录常量**：java 不携带这些 provider
  ⇒ 只落**代码路径**，不落目录标注（登记）。
- **`openrouter` 形状**：逻辑落、今天不可达（本仓无 OpenRouter chat 车道）⇒ 登记，
  与 A-02 的 `openRouterRouting` 同批。
- **`thinkingBudgets` 选项通道**：R9，仍不接。
- **B127**（Responses 的平行 `reasoningEffort`）：R10，保持登记。
