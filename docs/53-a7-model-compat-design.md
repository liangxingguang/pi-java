# 53 - 包 A7：Model.compat（解析链、目录标注与已消费字段的生产者）

> **状态：已闭环（2026-09-27）** —— R1–R8 全按建议实施；A7a／A7b／A7c 三步全落（`9eee9f8`／
> `22bde33`／`3770fd3`，收口 `3d7898e`），实施记录见 §12。四条车道原生渲染与 sections 段补丁
> **从「测试可达」变成「生产可达」**（`docs/51 §3 F5/F6` 随之结案）。
> 队列来源：`docs/48 §5` 批次 B 行「`Model.compat` 字段、JSON 映射与 request consumers」
> （P1，依赖**设计门 7**）＋ `docs/48 §2` 的 **A-07**。
> pi 参照锚点：`3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（`git status` 空）。
> 本包闭环时**已**回填：本文 §12、`docs/48 §5` 的 B 行与 banner、`docs/41` 的 A-07 行、
> `docs/51 §3` 的 **F5/F6**、`docs/32` 的 **B97–B102**。

---

## 1. 范围

### 1.1 这一包解决什么

pi 的 `Model.compat` 是**一个模型能不能按某种线格说话**的能力表。它在 pi 里有三条来源，
按优先级从低到高：

1. **探测**（detect）—— 由 `provider` 与 `baseUrl` 推导（`openai-completions.ts:1583`
   `detectCompat`）；
2. **生成的模型目录**（`generate-models.ts` 的 `apply*CompatMetadata`，写进被 gitignore 的
   `data/*.json`）—— 它自身又是「探测的**差量**」＋ 一批**纯字符串谓词**的产物；
3. **用户 `models.json`** —— 逐字段覆盖（`explicit ?? detected`）。⚠️ 这条只有三条路径中的
   **两条**是逐字段的，第三条是整条替换 —— 逐条见 §3 F7。

pi-java 今天的状况是：**读点有九个，生产者只有四个键**。`ModelsJsonConfig.CompatDef`
只声明了 `allowEmptySignature` / `requiresReasoningContentOnAssistantMessages` /
`supportsFinishReason` / `forceAdaptiveThinking` 四个键，`BuiltinCatalog` 的每个模型都拿
`ModelCompat.NONE`。⇒ 包 A3 加的四个工具增删标志与包 A2 加的
`supportsMidConvoSystemMessages` **在任何路径上都没有生产者**（这正是 `docs/51 §3 F5/F6`
记录的「原生渲染在生产上零可达」），而 `forceAdaptiveThinking` 在内置模型上恒为 `false`。

本包落地三件事：

1. **解析链** —— 新增 `CompatResolver`，把「常量默认 / 探测 / 目录·models.json 覆盖」
   三源按 pi 的优先级合一，产出**每个组件都已确定**的 `ModelCompat`；顺带把今天**内联**在
   completions 转换器里的那一处探测（`OpenAICompletionsMessageConverter:339-346`）收进去；
2. **目录标注** —— `BuiltinCatalog` 给 (provider, modelId) 与 pi 目录**精确匹配**的内置模型
   标上 compat（值取自跑 pi 生成器拿到的真数据，§7.1）；
3. **models.json 面** —— `CompatDef` 扩到 java 携带的全部键，缺席语义按 pi 逐个写清
   （有的三态、有的二态且默认方向相反）。

没有这一包，**A3 的四条车道原生渲染与 A4 的 sections 段补丁在生产上永远不会被走到**，
而且今天就有三处可观察的行为差异（§3 F2/F3）。

### 1.2 本包不做什么

pi 的五个 compat 接口共 **52 个字段**（§4.4 逐字段归属表）。本包**只**做 java 今天读得到的
字段的生产者，加上三个「今天就可观察」的新字段。其余字段按**消费者所属的包**归属，本包
一个都不加 —— 依据是仓库既有原则，写在 `ModelCompat` 的类 javadoc 里：

> Only flags pi-java actually **consults** are carried here — five so far. pi's own interface
> has eight …; each gets added when its consumer is ported, never speculatively
> (`docs/31 §8.34.4` 决策 2).

具体不做的：

- **`cache_control` / `cacheControlFormat` / `supportsLongCacheRetention` /
  `supportsCacheControlOnTools` / `supportsExplicitPromptCacheMode`** → **A-01**（P0，批次 B
  的另一行，自带设计门 4）；
- **`thinkingFormat` / `chatTemplateKwargs` / `chatTemplateArgs` / `supportsReasoningEffort`**
  → **A-09**（`docs/48 §5` B 行，依赖 B2＝本包）；
- **`thinkingTokenBudgetField` / `supportsThinkingTokenBudget`** → **A-10**；
- **`openRouterRouting`** → **A-02**（OpenRouter chat provider）；
- **`supportsEagerToolInputStreaming`** → **B90**（已登记，属 compat 线格编码）；
- **OpenAI 默认 wire（java 的 `openai` 默认走 completions、pi 走 responses）** → **A-04**，
  它是**独立的 P0 裁决**，`docs/48 §3` 明写「不能以改常量代替设计」。本包只在 §3 F3
  记下它的连带面；
- **内置模型目录的覆盖度与 id 漂移** → **A-08**。本包只登记，不改内置目录的 (provider, id)
  —— 改 id 会改用户解析模型名的方式，是另一个包的事（§3 F5）；
- **`models.json` 与内置目录的另外两条覆盖路径**（`modelOverrides.<id>.compat` 与 provider 级
  `compat`，即 §3 F7 的 path A/B）→ **A-16**。它们是「**合并面**」的问题，不是 compat 字段的
  问题：java 的 models.json provider 与内置 provider 没有合并面，单独做 compat 的逐字段合并
  会得到半条语义。本包只在 `models[]`（＝ pi 的 path C，语义**本就一致**）上把键扩全；
- **`supportsMidConvoEffort`** → 新登记（§10）。它的主体行为（`block_binding`）在钉住的
  Anthropic SDK 上**写不出来**（`docs/46 §3-D3`），只加它会得到一个半截的开关。

### 1.3 与 A2／A3／A4／H5 的关系

| 包 | 关系 |
|---|---|
| **A2** | 加了 `supportsMidConvoSystemMessages` 字段与消费点（`Transcripts.resolveTranscript`），当时按 R4 **明写**「探测/目录接线归 A7」 |
| **A3** | 加了四个工具增删标志与消费点（四条车道的原生渲染），同样把生产者留给 A7；`docs/51 §3 F5/F6` 记录了两条可达性发现 |
| **A4** | sections 段补丁的生产者。它让「会话中途出现系统消息」第一次**真的会发生** ⇒ 本包对这一条的可观察性负责 |
| **H5** | `forceAdaptiveThinking` 的字段与读点（Anthropic 思考三分支）。当时 `docs/46 §3-D5` 记：「它的值来自 pi 的**生成目录数据**，而那份数据不在仓库里 ⇒ pi-java 的内置目录不会置位它，只能由用户经 models.json 提供」—— 该结论的**前半句仍成立**，但后半句被本包推翻：那些谓词是**纯字符串函数**，可以照抄（§3 F2） |

---

## 2. 命题表（pi 侧，均已实读并逐条核对）

### P1 —— 五个 per-api compat 接口（`packages/ai/src/types.ts`）

| 接口 | 行 | 字段数 | 挂在哪 |
|---|---|---:|---|
| `OpenAICompletionsCompat` | `:674` | 27 | `Model.compat`，`api === "openai-completions"` |
| `OpenAIResponsesCompat` | `:754` | 10 | `api ∈ {openai-responses, openai-codex-responses, azure-openai-responses}` |
| `AnthropicMessagesCompat` | `:778` | 13 | `api === "anthropic-messages"` |
| `BedrockCompat` | `:843` | 1 | `api === "bedrock-converse-stream"` |
| `MistralConversationsCompat` | `:849` | 1 | `api === "mistral-conversations"` |

`Model.compat` 是一个**条件类型**（`:981-992`）：`TApi extends "openai-completions" ?
OpenAICompletionsCompat : …`。所有字段都是**可选**的，注释里逐个写了缺省与「auto-detected
from URL」。

### P2 —— 解析函数：每条车道一份，**返回全字段确定的形状**

pi 不在模型对象上做解析，而是在**车道内、请求期**算：

| 车道 | 解析函数 | 行 | 探测来源 |
|---|---|---|---|
| completions | `detectCompat(model)` → `getCompat(model)` | `openai-completions.ts:1583` / `:1685` | **URL 探测最重的一条**：14 个 provider/baseUrl 谓词 |
| responses | `getCompat(model)` | `openai-responses.ts:68` | 只有 `sessionAffinityFormat` 来自 `detectSessionAffinityFormat`（`:50`），其余全是字面量 |
| anthropic | `getAnthropicCompat(model)` | `anthropic-messages.ts:208` | 只有 `isOpenRouter`（`provider === "openrouter" \|\| baseUrl.includes("openrouter.ai")`） |
| mistral | **没有解析函数** | `mistral-conversations.ts:130` | 直接读 partial：`resolveTranscript(context, model.compat?.supportsMidConvoSystemMessages)` ⇒ 等价于 `?? false` |
| bedrock | 无 | — | — |

⚠️ **anthropic 的探测面只有 `isOpenRouter` 一条**，而它只影响 `sendSessionAffinityHeaders`
与 `sessionAffinityFormat` —— 这两个字段 java **不携带**。⇒ 对 java 而言，Anthropic 车道的
compat **全部是「字面量默认 ＋ 目录/用户覆盖」**，没有 URL 探测。这一点直接决定了 §4.1 的
解析层可以只有一处真探测（P4）。

### P3 —— 合并语义：`explicit ?? detected`，**逐字段**（不是 `||`，不是整块替换）

`openai-completions.ts:1685-1721` 的每一行都是同一个形状：

```ts
supportsStore: model.compat.supportsStore ?? detected.supportsStore,
…
supportsMidConvoSystemMessages: model.compat.supportsMidConvoSystemMessages ?? detected.supportsMidConvoSystemMessages,
```

`openai-responses.ts:68-81` 与 `anthropic-messages.ts:208-219` 同形，只是右侧是字面量：

```ts
supportsEagerToolInputStreaming: model.compat?.supportsEagerToolInputStreaming ?? true,
supportsTemperature: model.compat?.supportsTemperature ?? true,
allowEmptySignature: model.compat?.allowEmptySignature ?? false,
supportsMidConvoSystemMessages: model.compat?.supportsMidConvoSystemMessages ?? false,
```

⚠️ **两个例外，都不能照抄成 `??` 的机械形式**：
- `openai-completions.ts:1718` 的 `openRouterRouting: model.compat.openRouterRouting ?? {}`
  —— 右侧**不**是 `detected.openRouterRouting`（探测给的是同一个 `{}`，等价，但是形状不对称）；
- `:1721` 的 `vllmPriority: model.compat.vllmPriority` —— **根本没有默认值**，
  `?? detected` 里 `detected` 不含这个键（探测函数不返回它）⇒ 缺席就是 `undefined`。

### P4 —— 探测只有 completions 一条，且它的谓词被**写了两遍**

pi 有两份几乎相同的探测函数：

- **运行期**：`api/openai-completions.ts:1583` `detectCompat`（14 个谓词，返回值经 `getCompat` 合并）；
- **生成期**：`scripts/generate-models.ts:656` `detectOpenAICompletionsCompat`（同名同形），
  用来算「目录里要写哪些差量」（`:757` `openAICompletionsCompatDelta`，`OPENAI_COMPLETIONS_DEFAULT_COMPAT`
  在 `:616`）。

两份的谓词集合**逐字相同**（`isZai` / `isTogether` / `isMoonshot` / `isOpenRouter` /
`isCloudflareWorkersAI` / `isCloudflareAiGateway` / `isNvidia` / `isAntLing` / `isCerebras` /
`isDeepSeek` → `isNonStandard` / `useMaxTokens` / `isGrok` /
`isOpenRouterDeveloperRoleModel`）。

⚠️ **`detectCompat(model)` 读的是 `model.baseUrl`，不是请求选项里的 baseUrl。** pi 的
`StreamOptions` 没有 baseUrl 覆盖面（探测只认模型对象上那个）—— 而 pi-java 的
`ApiOptions.baseUrl` 是**请求期覆盖**（`ApiOptions.baseUrl()`），今天那处内联探测用的正是
**覆盖后**的值。⇒ 这是一处**形状差异**（§3 F4），本包必须显式选一边（§9 R2）。

### P5 —— 目录 compat 是**代码推导**的，不只是数据

`generate-models.ts` 里有八个 `apply*CompatMetadata`，各自对应一批规则：

| 函数 | 行 | 写什么 |
|---|---|---|
| `applyOpenAICompletionsCompatMetadata` | `:773` | 把探测的**差量**写进 `compat`，再让模型上已有的 `compat` 覆盖它；键数归零就 `delete model.compat` |
| `applyAnthropicMessagesCompatMetadata` | `:782` | 调 `getAnthropicMessagesCompat`（`:1142`） |
| `applyAnthropicAllowedFallbackModelMetadata` | `:799` | `allowedFallbackModels`（只在 `supportsMidConvoEffort` 为真时） |
| `applyOpenAIGrammarToolCompatMetadata` | `:849` | `supportsOpenAIGrammarTools` |
| `applyOpenAIToolSearchMetadata` | `:856` | `supportsToolSearch` ＋ 条件性的 `supportsAdditionalTools` |
| `applyOpenAICompletionsTranscriptMetadata` | `:875` | `supportsMidConvoSystemMessages`（＋ Kimi K3 的 `supportsMidConvoToolAdditions`） |
| `applyOpenAIResponsesTranscriptMetadata` | `:905` | `supportsMidConvoSystemMessages`（＋ 代理的 `supportsAdditionalTools`） |
| `applyOpenAIExplicitPromptCacheMetadata` | `:926` | `supportsExplicitPromptCacheMode`（`cost.cacheWrite > 0` 即可） |

其中与本包直接相关的三条谓词**是纯字符串匹配**，与远端数据无关：

```ts
// :578
function supportsAnthropicMidConvoSystemMessages(modelId: string): boolean {
	return (
		/^claude-opus-(?:4[.-]8|5)(?:-\d{8})?$/.test(modelId) ||
		/^claude-(?:fable|mythos)-5(?:[.-]1)?(?:-\d{8})?$/.test(modelId)
	);
}
// :585
function isAnthropicAdaptiveThinkingModel(modelId: string): boolean {
	return ( modelId.includes("opus-4-8") || … || modelId.includes("sonnet-4-6")
		|| … || modelId.includes("fable-5") || modelId.includes("mythos-5") );
}
// :604 —— opus-4-7 / 4.7 / 4-8 / 4.8 / opus-5 / opus.5
function isAnthropicTemperatureUnsupportedModel(modelId: string): boolean { … }
```

`getAnthropicMessagesCompat`（`:1142`）把它们组装起来：

```ts
if (provider === "anthropic" && supportsAnthropicMidConvoSystemMessages(modelId)) {
	compat.supportsMidConvoSystemMessages = true;
	compat.supportsMidConvoToolChanges = true;      // ← 两个一起给
}
…
if (EAGER_TOOL_INPUT_STREAMING_UNSUPPORTED_ANTHROPIC_MODELS.has(`${provider}:${modelId}`)) {
	compat.supportsEagerToolInputStreaming = false;
}
```

### P6 —— 目录数据是**生成物**，但它可以被重新生成

`packages/ai/src/providers/*.models.ts` 是**手写 8 行的壳**，真正的数据在
`./data/<provider>.json` —— 该目录**被 gitignore**，由 `scripts/generate-models.ts`
从 models.dev 抓取后算出。⇒ 锚点上**读不到**那份数据，但**可以跑出来**（§7.1 是本包的做法）。
⚠️ 这条同时是本包证据的**软肋**：数据随 models.dev 漂移，所以本包只把**代码推导面**
（P5 的三个谓词 ＋ 探测）当契约，把数据面当**佐证**（§9 R3）。

### P7 —— 三个「今天可观察」的字段的读点

```ts
// anthropic-messages.ts:1104-1111 —— temperature 的四合取
if ( options?.temperature !== undefined && !options?.thinkingEnabled
	&& model.compat?.supportsMidConvoEffort !== true && compat.supportsTemperature ) {
	params.temperature = options.temperature;
}

// openai-completions.ts:832-842
if (compat.supportsStore) { params.store = false; }          // ← 注意是 false，不是 true
if (options?.maxTokens) {
	if (compat.maxTokensField === "max_tokens") { (params as any).max_tokens = options.maxTokens; }
	else { params.max_completion_tokens = options.maxTokens; }
}

// openai-completions.ts:1225 —— 系统/开发者角色
const instructionRole = model.reasoning && compat.supportsDeveloperRole ? "developer" : "system";
```

### P8 —— 同名字段在不同车道**不冲突**（本包把五份解析函数合成一份的依据）

java 只有一个 `ModelCompat` 类型（9 字段），pi 有五个接口。把五份解析函数合一是否安全，
取决于**同名字段是否在不同车道有不同的默认/探测**。逐字段核对 java 携带的九个：

| 字段 | completions | responses | anthropic | mistral | bedrock |
|---|---|---|---|---|---|
| `allowEmptySignature` | — | — | `?? false` | — | — |
| `requiresReasoningContentOnAssistantMessages` | 探测(isDeepSeek) | — | — | — | — |
| `supportsFinishReason` | 探测＝常量 `true` | — | — | — | — |
| `forceAdaptiveThinking` | — | — | `=== true`（⇒ 二态） | — | — |
| `supportsMidConvoSystemMessages` | `?? false` | `?? false` | `?? false` | `?? false` | — |
| `supportsMidConvoToolAdditions` | `?? false` | — | — | — | — |
| `supportsMidConvoToolChanges` | — | — | `?? false` | — | — |
| `supportsAdditionalTools` | — | `?? false` | — | — | — |
| `supportsToolSearch` | — | `?? false` | — | — | — |

⇒ **除 `supportsMidConvoSystemMessages` 外，每个字段只被一条车道读**，而那一个在四条车道上
的缺省**同为 `false`**。合一安全。✅

---

## 3. 顺带发现（Java 侧，全部为今天可核实的状态）

### F1 —— `CompatDef` 只暴露九个字段里的四个 ⇒ 五个字段**零生产者**

`ModelsJsonSchema.CompatDef`（`:87-93`）只有 4 个键，`ModelsJsonConfig.compatOf`（`:302-311`）
用四参构造器，剩下的五个组件**永远走 `null`**：

```java
return new ModelCompat(
    def.allowEmptySignature() != null && def.allowEmptySignature(),
    def.requiresReasoningContentOnAssistantMessages(),
    def.supportsFinishReason() == null || def.supportsFinishReason(),
    def.forceAdaptiveThinking() != null && def.forceAdaptiveThinking());
//  ↑ 四参便捷构造器把 supportsMidConvoSystemMessages 及其后四个全填 null
```

`grep -rln "supportsMidConvoSystemMessages" pi-java-ai/src/main` 命中**五个文件，全是读点**
（`ModelCompat` 的字段本身、`Transcripts`、`AnthropicRequestBuilder`、`MistralConversationsApi`、
`OpenAICompletionsMessageConverter`）⇒ **零写入点**。这就是 `docs/51 §3 F5/F6` 的根因，本包修它。

⚠️ 顺带核实（写在这里免得实施时重查）：`supportsTemperature` / `maxTokensField` /
`supportsStore` / `supportsDeveloperRole` 在 `pi-java-ai/src/main` 里**零命中**；
`supportsStrictMode` 有 16 处，但**全部在 Responses 车道的三个文件**里，且都是**形参/车道标志**
（`ResponsesMessageConverter:293` 的 `strictField(supportsStrictMode)`、两个 Api 类的调用点），
**没有**一处从 `model.compat()` 读 ⇒ 属 §9 R4。

### F2 —— 内置目录全部 `ModelCompat.NONE`，而 pi 的目录给这些模型**置了位**

`BuiltinCatalog.model(...)` 不传 compat ⇒ `ModelInfo` 的 compact constructor 回落
`ModelCompat.NONE`（`ModelInfo:50-52`）。而 pi 的生成目录给 java 的**四个 Anthropic 内置模型
里的三个**置了位（§7.1 逐字）。⇒ 今天**可观察**的差异（§3 F3）。

### F3 —— 三处今天就可观察的行为差异（本包最硬的动机）

判据：**两侧都用各自的内置模型目录 + 各自的默认配置**，不依赖任何中途事件。

| # | 模型 | pi 的行为 | java 今天的行为 | 触发条件 |
|---|---|---|---|---|
| **D1** | `anthropic/claude-sonnet-4-6`、`claude-fable-5`、`claude-opus-4-8` | `forceAdaptiveThinking: true` ⇒ 发 `{type:"adaptive", display}` ＋ `output_config.effort`（`anthropic-messages.ts:1165`） | `false` ⇒ 发 `{type:"enabled", budget_tokens}`（`AnthropicThinking:121` 的反支） | **任何开思考的请求** |
| **D2** | `anthropic/claude-opus-4-8` | `supportsTemperature: false` ⇒ **不发** `temperature` | `true`（恒值）⇒ `AnthropicRequestBuilder:116` 照发 | 请求带 `temperature >= 0` 且未开思考 |
| **D3** | `deepseek/deepseek-v4-pro` | `maxTokensField: "max_tokens"` ⇒ 发 `max_tokens` | 恒发 `max_completion_tokens`（`OpenAICompletionsMessageConverter:184`） | 任何带 maxTokens 的请求 |
| **D4** | `anthropic/claude-fable-5`、`claude-opus-4-8`、`deepseek/deepseek-v4-pro` | `supportsMidConvoSystemMessages: true` ⇒ 中途系统消息**原样保留**并就地渲染 | 缺席 ⇒ `Transcripts.resolveTranscript` 走**折叠**支 | 转录里存在中途系统消息（A2 的 held 系统消息今天就可达；A4 的段补丁让它更容易发生） |
| **D5** | `openai/gpt-5`（Responses 车道） | `supportsStrictMode: true` ⇒ 工具声明发 `strict` | B88 落的是**车道的 compat 缺省**（openai `?? false` ⇒ 不发） | 走 responses 车道且带工具；⚠️ java 的 `openai` **默认不是** responses（**A-04**）⇒ 需显式配置才可达 |

⚠️ **D1/D2/D3 的可达性是「用户选到那三个内置 Anthropic 模型／那个 DeepSeek 模型就成立」**
—— java-java 的内置 Anthropic 目录只有四个模型，三个命中；不需要任何特殊配置。

### F4 —— `ModelInfo` 不带 baseUrl，而探测需要它 ⇒ 解析只能在请求期

`ModelInfo`（`catalog/ModelInfo.java:29-41`）的组件里没有 `baseUrl`（pi 的 `Model` **有**）。
provider 在 `ModelId` 上，baseUrl 只有车道知道（`ApiOptions.baseUrl()`）。⇒ §4.1 的解析层
**必须在车道入口**，且要显式决定「用哪个 baseUrl」（§9 R2）。

### F5 —— java 的内置模型 (provider, id) 与 pi 目录的匹配情况

跑生成器后逐条比对（§7.1 是原始输出）：

| java 内置 | pi 目录有没有 | 说明 |
|---|---|---|
| `anthropic/claude-fable-5` | ✅ 精确匹配 | |
| `anthropic/claude-opus-4-8` | ✅ | |
| `anthropic/claude-sonnet-4-6` | ✅ | |
| `anthropic/claude-haiku-4-5-20251001` | ✅ | pi 只给它 `supportsStrictTools`（java 无消费者）⇒ 标注后仍是 `NONE` |
| `openai/gpt-5` / `gpt-5-mini` / `gpt-5-nano` | ✅ | ⚠️ **pi 的 api 是 `openai-responses`，java 的默认是 `openai-completions`**（**A-04**） |
| `google/gemini-2.5-pro` / `-flash` | ✅ | pi 的 `compat` 是 `null` ⇒ 无事可做 |
| `deepseek/deepseek-v4-pro` | ✅ | |
| `deepseek/deepseek-v4-flash` | ❌ | pi 在 **2026-09-10**（`12f59336a fix(ai): update DeepSeek Flash catalog`）把它**改名**成 `deepseek-flash`（commit message：*Replace retired Flash aliases with the canonical deepseek-flash model*）⇒ java 用的是**已退役的别名** |
| `mistral/mistral-large`、`mistral-small` | ❌ | 全库零命中；pi 是 `mistral-large-latest` / `mistral-small-2603` 一类 ⇒ java 这两个 id 是**发明的** |

⇒ **本包只标注精确匹配的那些**（§4.2）。三个不匹配的登记给 A-08（§10），**不在本包改 id**：
改 id 会改「用户怎么点名模型」，是目录覆盖度的裁决（`docs/48 §2 A-08`）。

### F6 —— 两个字段 java 根本没有（`supportsTemperature`、`maxTokensField`）

`grep -rn "supportsTemperature\|maxTokensField" pi-java-ai/src/main` 零命中；
`supportsStore` / `supportsDeveloperRole` 同样零命中。这四个字段的读点（P7）在 java 侧
全都不存在 ⇒ 本包给前两个加字段＋读点（§9 R4/R5），后两个按裁决处理。

### F7 —— java 只有 pi 的**三条** compat 覆盖路径中**最粗**的那一条，且那一条语义**本就一致**

pi 的 `models.json` 与内置目录有**三条**合并面（全部在
`packages/coding-agent/src/core/provider-composer.ts`，共三处调用同一个 `mergeCompat`）：

| 路径 | pi 的位置 | compat 语义 | java |
|---|---|---|---|
| **A** `providers.<id>.modelOverrides.<modelId>.compat` | `applyModelOverride:108-134` → `:449-461` | **逐字段合并**到内置模型上（`mergeCompat(model.compat, override.compat)`） | ❌ **完全没有这个键** |
| **B** `providers.<id>.compat`（provider 级） | `:209-213` | **逐字段合并**到该 provider 的**每个模型**（含内置） | ❌ 没有 |
| **C** `providers.<id>.models[].compat` | `modelFromJson:136-173` → `:214-220` | **整条替换** —— 只从内置继承 `api`/`baseUrl`，内置的 compat **被静默丢弃** | ✅ 已一致 |
| **D** 嵌套路由键的例外 | `mergeCompat:86-106` | 四个路由键**递归合并**而非替换 | n/a（java 零消费者） |

⚠️ **C 的「静默丢弃」是 pi 的文档化行为，不是它的 bug**：`docs/models.md:351` 写着
*"If a custom model `id` matches a built-in model `id`, the custom model replaces that built-in
model."* ⇒ 我最初把 F7 写成「java 整条替换 ⇒ 与 pi 的逐字段合并不符」是**读错了路径**
（本条的更正过程记在 §12.0）。

`mergeCompat`（`provider-composer.ts:86-106`）的形状值得逐字记下，因为它是 A/B 的语义核心：

```ts
function mergeCompat(base, override) {
	if (!override) return base;
	const merged = { ...base, ...override };
	// 四个路由键**嵌套合并**（不是替换）
	for (const key of ["openRouterRouting", "vercelGatewayRouting",
	                   "chatTemplateKwargs", "chatTemplateArgs"] as const) {
		const baseValue = baseNested?.[key];
		const overrideValue = overrideNested[key];
		if ((typeof baseValue === "object" && baseValue !== null)
			|| (typeof overrideValue === "object" && overrideValue !== null)) {
			mergedNested[key] = { ...(baseValue as object | undefined),
			                      ...(overrideValue as object | undefined) };
		}
	}
	return merged;
}
```

⚠️ 注意 `null` 的处置：`typeof null === "object"` 但代码显式排除了 `null` ⇒ 把某个嵌套键写成 `null`
**不**会清空它（两侧的既有键都活下来）。这一段是子代理的逐字引用（**未跑探针**）。

⇒ **标量旗标逐字段「用户赢」；四个路由键递归合并；数组（`allowedFallbackModels`）整条替换。**

java 的结构性差距因此不在 compat 的合并语义，而在**合并面本身**：`ModelsJsonConfig.allModels()`
（`:138-151`）把内置模型与 models.json 的模型**拼成一个 list** 再交给 `BuiltinCatalog.of(...)`
（HashMap 同键后者赢）⇒ java 的 models.json 是「**另一个 provider 对象**」，与内置 provider
**没有合并面**。后果：用户**无法**为内置模型逐字段调 compat（A/B），只能整条重定义（C）。
⇒ 归 **A-16**（它的标题就是「`models.json` provider/protocol/baseUrl **覆盖**」），本包只登记（§10 B102）。

---

## 4. 设计

### 4.1 解析层：`CompatResolver`（请求期）

新增 `pi-java-ai/src/main/java/com/pijava/ai/catalog/CompatResolver.java`：

```java
public final class CompatResolver {
    private CompatResolver() {}

    /**
     * pi 的五份 per-api 解析函数（`detectCompat`/`getCompat`/`getAnthropicCompat`）在 java 上的
     * **合一**实现 —— 依据见 §2 P8（除 supportsMidConvoSystemMessages 外每个字段只被一条车道读，
     * 而那一个在四条车道上缺省同为 false）。
     *
     * @param model    目标模型（携带目录标注或 models.json 给出的 compat —— 即「覆盖」那一源）
     * @param protocol 车道，决定应用哪一份探测与缺省
     * @param baseUrl  车道的**有效** baseUrl（可选，null 表示无）
     * @return 每个组件都已确定的 ModelCompat
     */
    public static ModelCompat resolve(ModelInfo model, Protocol protocol, String baseUrl) { … }
}
```

实现要点：

1. **探测**只对 `OPENAI_COMPLETIONS` 生效，且只算 java 携带的四个字段
   （`requiresReasoningContentOnAssistantMessages`、`maxTokensField`、`supportsStore`、
   `supportsDeveloperRole`）；谓词**逐字**照抄 `detectCompat:1583-1670` 的那 14 个布尔量
   —— 今天内联在 `OpenAICompletionsMessageConverter:339-346` 的那一处搬进来，**行为不变**；
2. **覆盖**：`model.compat()` 里**非空**的组件赢（pi 的 `explicit ?? detected`）；由于
   `ModelCompat` 的九个组件里有三个是原始 `boolean`（无三态），它们的「覆盖」在类型的
   `ModelCompat.of(...)` / 便捷构造器里已经归一 —— 本包**不**动那三个的语义；
3. **缺省**：其余全按 §2 P2 的表中字面量。

⚠️ **解析后的不变量**（写进类 javadoc ＋ §8 的验收 grep）：**生产路径上不存在返回后仍为
`null` 的组件**。这是 java 把 pi 的 `Compat`（partial）与 `Required<Compat>`（resolved）
**两个类型合一**的代价：类型上无法阻止「读了未解析的值」。缓解手段两条：
（a）§8 的验收 grep 禁止车道直接读 `model.compat()`；
（b）每条车道一条「解析后取值」的夹具（§6）。

### 4.2 目录标注：`BuiltinCatalog`

给 §3 F5 里**精确匹配**的模型显式传 compat。值取自 §7.1 的 oracle：

| 模型 | 标注的字段 | 依据 |
|---|---|---|
| `claude-fable-5` | `supportsMidConvoSystemMessages=true`、`supportsMidConvoToolChanges=true`、`forceAdaptiveThinking=true` | oracle 逐字 |
| `claude-opus-4-8` | 同上 ＋ `supportsTemperature=false` | oracle 逐字 |
| `claude-sonnet-4-6` | `forceAdaptiveThinking=true` | oracle 逐字 |
| `claude-haiku-4-5-20251001` | **不动**（pi 只给 `supportsStrictTools`，java 无消费者） | oracle 逐字 |
| `deepseek-v4-pro` | `supportsMidConvoSystemMessages=true`（`requiresReasoningContentOnAssistantMessages` 已由探测覆盖，**显式写出**亦无害且更可读 —— 见 §9 R6） | oracle 逐字 |
| `gpt-5*` / `gemini-*` | **不动**（pi 给 `gpt-5*` 的是 responses 车道的 `supportsStrictMode`/`supportsOpenAIGrammarTools`；java 的默认车道不同 ⇒ 归 **A-04** 裁决） | |
| `mistral-*` / `deepseek-v4-flash` | **不动**（pi 无此 id） | F5 |

形状：`BuiltinCatalog` 的 `model(...)` 助手加一个 `ModelCompat` 重载（或新增 `anthropicModel(...)`
一类的显式助手），**不**改其他调用点 —— 5 个 provider 工厂里只有 `anthropicModels()` 与
`deepseekModels()` 的两条要用。

### 4.3 models.json 面：`CompatDef` 扩键

`CompatDef` 从 4 键扩到 java 携带的全部键（11 个：9 ＋ 本包新增的 2）：

| 键 | java 类型 | 缺席 ≙ | 依据 |
|---|---|---|---|
| `allowEmptySignature` | `boolean` | `false` | pi `?? false` |
| `requiresReasoningContentOnAssistantMessages` | `Boolean` **三态** | 探测 | pi `explicit ?? detected` |
| `supportsFinishReason` | `boolean` | `true`（**方向相反**） | pi 的探测值是常量 `true` |
| `forceAdaptiveThinking` | `boolean` | `false` | pi 判据 `=== true` |
| `supportsMidConvoSystemMessages` | `Boolean` **三态** | `false` | 缺席与显式 `false` 在 pi 上**行为相同**（四条车道的 `?? false`），但目录会给它置位 ⇒ 保留三态以便区分「用户显式关」与「没写」（§9 R6） |
| `supportsMidConvoToolAdditions` | `Boolean` | `false` | 读点 `=== true`（二态） |
| `supportsMidConvoToolChanges` | `Boolean` | `false` | 读点真值判断 ＋ 与上一个相与（二态） |
| `supportsAdditionalTools` | `Boolean` | `false` | 二态 |
| `supportsToolSearch` | `Boolean` | `false` | 二态 |
| `supportsTemperature`（**新**） | `Boolean`（`CompatDef` 里缺席＝「没写」） | 解析后 `true`（**方向相反**），除非目录给了 `false` | pi `?? true`；oracle 给 `opus-4-8` 写 `false` |
| `maxTokensField`（**新**） | `String`（`CompatDef` 里缺席＝「没写」） | 解析后 = 探测值 | pi `explicit ?? detected` |

⚠️ 上表的「缺席 ≙」一列要分**两层**读：`CompatDef` 的缺席只表示「用户没写这个键」，
**解析层**再把「没写」翻译成探测值或目录值。两者不可混同。⚠️ 但要说清**今天**混同的
后果有多大：在 **path C**（`models[]` 重定义）下，那个模型的 compat **整个**来自这份定义
（内置的目录值本就被丢弃，§3 F7）⇒ 今天混同在行为上是等价的。这一列之所以仍要写对，
是为了 **path A/B**（§3 F7 的逐字段合并面，归 A-16）落地时不必回头改这里 ——
那时「没写」与「写了缺省值」必须可区分（§9 R6）。

`maxTokensField` 在 Java 侧落成**嵌套 `enum`**（两个取值的闭集，`CLAUDE.md` 的约定），
带 `wireName()` 给出 `max_tokens` / `max_completion_tokens`（照 `Protocol` 的先例）。

`compatOf` 相应改写；`CompatDef` 的 javadoc 按上表逐键写清（沿用现有风格：每个键一段，
写缺席语义与依据）。

### 4.4 逐字段归属表（pi 的 52 个字段一个不丢）

**`OpenAICompletionsCompat`（27）**

| 字段 | 归属 |
|---|---|
| `supportsFinishReason` | ✅ 已消费（`OpenAICompletionsApi:248`）—— 本包**不**动 |
| `requiresReasoningContentOnAssistantMessages` | ✅ 已消费 —— 本包把探测收进解析层 |
| `supportsMidConvoSystemMessages` | ✅ 已消费（A2）—— 本包给生产者 |
| `supportsMidConvoToolAdditions` | ✅ 已消费（A3）—— 本包给生产者 |
| `maxTokensField` | **本包**（新字段＋读点） |
| `supportsStore` | **待裁决**（§9 R5） |
| `supportsDeveloperRole` | **待裁决**（§9 R5） |
| `supportsStrictMode` | ✅ 已消费（B88 的 Responses 面）；completions 面**零消费者** ⇒ 登记 |
| `thinkingFormat` / `supportsReasoningEffort` | **A-09** |
| `chatTemplateKwargs` / `chatTemplateArgs` | **A-09**（baseten / chat-template 形态） |
| `openRouterRouting` | **A-02** |
| `cacheControlFormat` / `supportsLongCacheRetention` | **A-01** |
| `thinkingTokenBudgetField` / `supportsThinkingTokenBudget` | **A-10** |
| `requiresToolResultName` / `requiresAssistantAfterToolResult` | 零消费者（B86 已登记：加进来恒 `false` ＝ 死码） |
| `supportsUsageInStreaming` / `requiresThinkingAsText` / `vercelGatewayRouting` / `zaiToolStream` / `supportsOpenAIGrammarTools` / `sendSessionAffinityHeaders` / `sessionAffinityFormat` / `vllmPriority` | 零消费者 ⇒ **新登记**（§10） |

**`OpenAIResponsesCompat`（10）**：`supportsMidConvoSystemMessages` ✅（本包给生产者）、
`supportsAdditionalTools` ✅（本包）、`supportsToolSearch` ✅（本包）、`supportsStrictMode` ✅
（B88 已落读点，本包给生产者 ⇒ §3 D5）、`supportsLongCacheRetention` → A-01、
`supportsExplicitPromptCacheMode` → A-01、`supportsOpenAIGrammarTools` / `supportsDeveloperRole` /
`sessionAffinityFormat` / `supportsMaxOutputTokens` → 零消费者（新登记）。

**`AnthropicMessagesCompat`（13）**：`allowEmptySignature` ✅、`forceAdaptiveThinking` ✅
（本包给生产者）、`supportsMidConvoSystemMessages` ✅（本包）、`supportsMidConvoToolChanges` ✅
（本包）、`supportsTemperature` **本包**（新字段＋读点）、`supportsEagerToolInputStreaming` → **B90**、
`supportsLongCacheRetention` / `supportsCacheControlOnTools` → A-01、
`supportsMidConvoEffort` / `supportedStrictTools`（`supportsStrictTools`）/ `allowedFallbackModels` /
`sendSessionAffinityHeaders` / `sessionAffinityFormat` → 零消费者（新登记）。

**`BedrockCompat`（1）**：`supportsStrictMode` → E 批次（java 无 bedrock 车道）。
**`MistralConversationsCompat`（1）**：`supportsMidConvoSystemMessages` ✅（本包给生产者）。

### 4.5 实施影响面（先查清，免得实施时踩）

- **`ModelCompat` 的记录组件数从 9 → 11**（＋`supportsTemperature`、＋`maxTokensField`）⇒
  四个便捷构造器要跟着加参数，而**今天有 18 个构造点**，其中三个用的是**九参规范构造器**
  （`AnthropicToolChangesWireTest:201`、`CompletionsToolChangesWireTest:202`、
  `ResponsesToolChangesWireTest:229`）⇒ 那三处会**编译失败**并逼出改动（与包 A2 的
  `ExecutionContext` 同一形态，`docs/49 §12.2-2`）。把新组件**加在末尾**能让「四参/五参便捷
  构造器」的语义不变。
- **既有夹具不会因目录标注变红**：`BuiltinCatalogTest`（139 行）**一处都没碰 `compat`**；
  `TranscriptsTest:154/157/158` 钉的是 `ModelCompat.NONE` 与手搓实例；
  `AnthropicThinking*Test` 全部用 `ModelInfo` 手工构造。⇒ **A7b 的牙只能来自新夹具**
  （`docs/52 §12.5` 教训 3 的镜像形态：不是「通道没夹具」而是「既有夹具不覆盖该通道」）。
- **探针手势**：本仓已有两次 `git checkout -- <file>` 吃掉未提交改动的教训
  （`docs/52 §12.5` 教训 4）⇒ 本包一律**先提交、后探针**，或先备份。
- **可达性已核实（好消息）**：目录里的 `ModelInfo` **确实**会随请求走到车道 ——
  `DefaultProviders.streamBlocking:124` 用 `provider.builtinModels().find(model)` 取到的
  那个 `ModelInfo` 直接进 `StreamRequest`（注释里写明「此前只投 `ModelId` ⇒ 目录里的
  per-model 开关到不了适配器」）⇒ §3 F3 的 D1–D4 **在生产链路上可达**，不需要额外接线。
  ⚠️ 同处的注释（`:117-118`）写的是「`ModelInfo.minimal`（compat 缺席 ≡ pi 的 `?? false`，
  安全方向）」—— 这句在 A7 之后**要改写**：缺席仍然走探测/字面量缺省，安全方向不变，
  但「≡ false」不再是无条件成立的（`supportsTemperature` 的缺省是 `true`）。

---

## 5. 三步拆分

| 步 | 内容 | 目标模块 | 可独立编译 |
|---|---|---|---|
| **A7a** | `CompatResolver` ＋ 把内联探测搬进去 ＋ `supportsTemperature`/`maxTokensField` 两个字段与读点 | `ai` | ✅ |
| **A7b** | `BuiltinCatalog` 标注（含 `CompatDef` 扩键 ＋ `compatOf` 改写）；若 R5 通过，再加 `supportsStore`/`supportsDeveloperRole` | `ai` | ✅ |
| **A7c** | 车道读点改走解析层（Anthropic／Completions／Responses／Mistral 四条） | `ai` | ✅ |

⚠️ A7a 的 `supportsTemperature` 读点是**改行为**的一步（D2），A7b 的目录标注同样是（D1/D4）。
两步都要各自的先红（§6）。

---

## 6. 先红与变异矩阵（**实测**；设计期的预测见 §12.4 的对账）

### 6.1 先红

| 步 | 夹具 | 实际形态 |
|---|---|---|
| A7a | `CompatResolverTest`（新，19 例） | 编译失败（类不存在，与 A3a/A4a 同型） |
| A7b | `CatalogCompatRulesTest`（新，15 例） | 编译失败（类不存在）；其中 `theMidConvoRegexIsAnchored` / `theThreePredicatesDisagreeOnCase` 是**辨伪**用例 |
| A7c | `AnthropicCompatWireTest`（新，6 例）／`CompletionsCompatWireTest`（新，5 例） | 编译失败（签名变更）＋ 见下面 M 矩阵：**逐条本包新增的行为在旧代码下都红**（M1/M3/M4/M5c/M6/M7/M8 的红集就是「旧行为」） |

⚠️ 本包**没有**做「stash 实现 ＋ 旧代码复跑」那一步（A2 的手法）—— 因为 A7c 改了四个签名，
stash 主源码会让夹具无法编译。替代证据是 §6.2 的变异矩阵：每一处新行为的读点被改成旧行为都拿到了红。

### 6.2 变异矩阵（**实测红集**，八处；夹具集＝两个 wire 夹具 ＋ `CompatResolverTest` ＋ `CatalogCompatRulesTest` ＋ `TranscriptsTest`）

| 变异 | 位置 | 落地检查 | **红集** |
|---|---|---|---|
| **M1** 去掉 `&& compat.supportsTemperature()` | `AnthropicRequestBuilder` | old=0 | **1**（`opus48DropsTheTemperatureField`） |
| **M2** `AnthropicThinking` 改读 `model.compat().forceAdaptiveThinking()` | `AnthropicThinking` | old=0 | **0** ⇒ 见下 |
| **M3** `maxTokensField == MAX_TOKENS` 恒假 | `OpenAICompletionsMessageConverter` | old=0 | **2** |
| **M4** `supportsStore()` 恒假 | 同上 | old=0 | **1** |
| **M5** 把 `&& Boolean.TRUE.equals(compat.supportsDeveloperRole());` 换成 `;` | 同上 | old=0 | **0** ⇒ 见下（**语义 no-op**） |
| **M5c** 同上但换成 `&& false;` | 同上 | old=0 | **1** |
| **M6** `resolveTranscript` 恒定折叠 | `Transcripts` | old=0 | **2** |
| **M7** `useMaxTokens ? MAX_TOKENS : …` 换成恒 `MAX_COMPLETION_TOKENS` | `CompatResolver` | old=0 | **7** |
| **M8** baseUrl 谓词 `contains("deepseek.com")` 换个串 | `CompatResolver` | old=0 | **2** |

两条零红**都不是「夹具没牙」**，成因不同，都要记住：

- **M2（零红，且**本该**零红）**：`forceAdaptiveThinking` 是**二态常量字段** —— pi 的探测值是常量
  `false`（`getAnthropicCompat:214` 那一段里没有任何分支碰它），故 `explicit ?? false` 里
  「解析」与「不解析」**必然同值**。⇒ 本包把 `compat` 传进 `AnthropicThinking` 是**形状改动**
  （收口到单一解析点），**不是行为改动**。设计稿 §6.2 预测它「1–3 红」是**错的**。
  代价如实登记：那一处**没有**行为层面的守护，只有形状层面的（`docs/53 §8` 的 grep 1）。
- **M5（零红，**不该**零红）**：`A && B ;` 里的 `;` 让表达式**仍是** `A && B`（javac 把空语句折叠掉），
  ⇒ 变异体等于原条件，是**语义 no-op**。这与包 A4 的 **M3**（`|| false`）**同型，第二次兑现**
  （`docs/52 §12.5` 记的是同一条）。改成 `&& false;` 后（M5c）恰 1 红。
  ⇒ **教训不变**：变异体要先问「它改的是不是条件本身」；`A && B` 上挂一个恒真/空语句
  是**假变异**，不是零红。

⚠️ 另一条与变异无关的坑：本文件的多行模式匹配在**CRLF** 工作副本上失败（`old=2` 报「没落地」）——
这是本仓第 4 次撞 CRLF（`docs/51 §12.4.2` 记过两次，A7 一次，本次 M5b 一次）。
**凡跨行模式，用单行锚点或显式 `\r?\n`。**

⚠️ 还有一条**收口时才发现的漏网读点**：`OpenAICompletionsApi:248` 当时仍写
`request.model().compat().supportsFinishReason()` —— 设计稿 §8 的 grep 1 就是为抓它而设的，
抓到了。它在行为上是**解析不变**的（`resolved()` 对该字段原样透传），故收口提交
（`3d7898e`）零行为改动，只为让 grep 成为**可靠**的门。

---

## 7. 实测记录

### 7.1 pi 的生成目录（**跑生成器**，不是读源码）

```
$ cd packages/ai && node scripts/generate-models.ts --strict     # 退出码 0
```

产物 `src/providers/data/*.json`（**被 gitignore**，故只能用工作树读）。再用 pi **自己的
代码路径**取内置模型（`node --input-type=module -e 'import("…/src/providers/all.ts")'`）：

```
### anthropic/claude-fable-5  api=anthropic-messages  baseUrl=https://api.anthropic.com
    compat = {"supportsMidConvoSystemMessages":true,"supportsMidConvoToolChanges":true,
              "forceAdaptiveThinking":true,"supportsStrictTools":true,
              "allowedFallbackModels":[{"provider":"anthropic","model":"claude-opus-4-8",…},…]}
### anthropic/claude-opus-4-8  api=anthropic-messages  baseUrl=https://api.anthropic.com
    compat = {"supportsMidConvoSystemMessages":true,"supportsMidConvoToolChanges":true,
              "forceAdaptiveThinking":true,"supportsTemperature":false,"supportsStrictTools":true}
### anthropic/claude-sonnet-4-6  api=anthropic-messages  baseUrl=https://api.anthropic.com
    compat = {"forceAdaptiveThinking":true,"supportsStrictTools":true}
### anthropic/claude-haiku-4-5-20251001  api=anthropic-messages  baseUrl=https://api.anthropic.com
    compat = {"supportsStrictTools":true}
### openai/gpt-5  api=openai-responses  baseUrl=https://api.openai.com/v1
    compat = {"supportsStrictMode":true,"supportsOpenAIGrammarTools":true}
### deepseek/deepseek-v4-pro  api=openai-completions  baseUrl=https://api.deepseek.com
    compat = {"supportsStore":false,"supportsDeveloperRole":false,"maxTokensField":"max_tokens",
              "requiresReasoningContentOnAssistantMessages":true,"thinkingFormat":"deepseek",
              "supportsMidConvoSystemMessages":true}
### deepseek/deepseek-flash  api=openai-completions  baseUrl=https://api.deepseek.com
    compat = {"supportsStore":false,"supportsDeveloperRole":false,"maxTokensField":"max_tokens",
              "requiresReasoningContentOnAssistantMessages":true,"thinkingFormat":"deepseek"}
### mistral/mistral-large-latest  api=mistral-conversations  baseUrl=https://api.mistral.ai
    compat = null
### google/gemini-2.5-pro  api=google-generative-ai  baseUrl=https://generativelanguage.googleapis.com/v1beta
    compat = null
```

⚠️ 三点注意：
1. 上面是**目录里存的那一份**（partial）。车道的**有效值**是它经 `?? 字面量缺省`（P2/P3）
   之后的形状 —— 例如 `sonnet-4-6` 的 `supportsTemperature` 不在目录里 ⇒ 有效值 `true`；
   `opus-4-8` 显式写了 `false` ⇒ 有效值 `false`。§4.2 的表按**有效值**写。
2. **`deepseek-flash` 没有 `supportsMidConvoSystemMessages`**，而 `deepseek-v4-pro` 有 ——
   依据是 `applyOpenAICompletionsTranscriptMetadata:875-900` 的 `isTextOnly` 里那条
   `model.provider === "deepseek" && model.id === "deepseek-v4-pro"`，**逐 id 写死**。
3. `mistral-large-latest` 与 `gemini-2.5-pro` 的 `compat` 是 `null`（两家的目录数据里根本没有
   `compat` 键）⇒ pi 对这两条一律走字面量缺省。

### 7.1b 独立佐证：pi 自己的测试（**可跑**，不是读源码）

`packages/ai/test/providers.test.ts` 里就有一条**把目录 compat 写进断言**的用例
（`:115-146`）—— 一份 `supported` 列表（断言
`expect(models.getModel(provider, modelId)).toHaveProperty("compat.supportsMidConvoSystemMessages", true)`）
与一份 `unsupported` 列表（断言 `not.toHaveProperty`）。其中：

```ts
const supported = [ … ["deepseek", "deepseek-v4-pro"], … ];
const unsupported = [ … ["deepseek", "deepseek-flash"], … ];
```

⇒ **与我跑出来的目录数据独立一致**（`deepseek-v4-pro` 有、`deepseek-flash` 没有）。
实测（锚点 ＋ 刚生成的目录）：

```
$ ./node_modules/.bin/vitest run packages/ai/test/providers.test.ts
 Test Files  1 passed (1)
      Tests  26 passed (26)
```

⚠️ 两点注意：① 该列表是**抽样**不是全集（`anthropic/claude-opus-4-8` 有该标志却**不在**
`supported` 里，`anthropic/claude-opus-4-5` 一类的历史模型也没列）⇒ 判据仍以 §7.1 为准；
② 这条用例是 A7b 夹具的**现成骨架**（`docs/44 §10` 的同一手法：夹具优先镜像 pi 自己的测试，
比自造强一档）。

### 7.2 models.json 与内置目录的三条合并面（**已答**，见 §3 F7）

取证方式：一个专门的子代理在锚点上逐文件实读（`model-config.ts` 的 schema、
`provider-composer.ts` 的 `mergeCompat`/`applyModelOverride`/`modelFromJson`、
`model-runtime.ts` 的组装与三个入口），并给出 `path:line` ＋ 逐字引用；结论三条：

1. **`models[]` 里与内置同 id 的条目 = 整条替换**（pi 文档化了这一点）⇒ java 今天的行为
   **已经一致**，不需要改；
2. **`modelOverrides.<id>.compat` 逐字段合并**到内置模型上（标量「用户赢」、四个路由键嵌套、
   数组整条替换）⇒ **java 缺这条路径**；
3. **provider 级 `compat` 逐字段合并到该 provider 的每个模型**（含内置）⇒ **java 也缺**。

⚠️ 该报告同时纠正了我最初的假设（我以为 pi 的 `models[]` 是逐字段合并、java 是整条替换 ⇒ 缺口）。
**两条如实登记**：① 报告是源码实读，**未跑探针**（它自己声明了这一点）⇒「`models[]` 撞内置 id 时
内置 compat 真的被丢掉」这句话在 pi 侧**没有执行级证据**，只有源码与文档；本包**不依赖该结论**
（B102 归 A-16，本包照 path C 的既有语义不动）；② 另有一条 `remote-catalog-provider.ts:46-55`
的 overlay 会**先**把内置模型换成远端拉取的版本，那一层携带什么 compat 未核 —— 与 pj-java 无关
（java 没有这条 overlay），只记在这里免得将来误读。

### 7.3 未做的实测（如实登记）

- **没有**跑 pi 的车道级请求体抓取来验证 D1–D5 的**线上形状**。本包的 D1–D5 全部由
  「目录数据（§7.1）＋ 读点源码（P7）＋ java 现状（F3）」三截拼出，每一截都是实读/实跑，
  但**三段没有在同一次请求里被同时观测**。实施 A7a 时**必须**补一次两侧的桩服务器抓体
  （`RecordingHttpServer`，`docs/49 §12.2` 的模板）—— 这是本包最重要的一条自我约束。

---

## 8. 验收 grep（**实测结果**，逐步命令与输出见 §12.5）

1. `grep -rn "compat()" --include=*.java pi-java-ai/src/main/java/com/pijava/ai/protocol/`
   ⇒ 车道里**零命中**（只剩两行注释）。全仓 `main` 只剩 `ModelsJsonConfig:214` 的
   `compatOf(providerId, model, model.compat())` —— 那是**定义变更**路径（models.json → ModelInfo），
   不是读点，**允许**。✅
2. `grep -rln "supportsMidConvoSystemMessages" pi-java-ai/src/main` ⇒ 命中**十个**文件，
   生产者三个（`BuiltinCatalog`／`CatalogCompatRules`／`CompatResolver`）＋ `ModelsJsonSchema`/`ModelsJsonConfig`
   （models.json 面）＋ 四个读点 ＋ `ModelCompat` 自身。✅（旧状态：只有 `ModelCompat` ＋ `Transcripts` 等读点）
3. `grep -rn "deepseek" …/OpenAICompletionsMessageConverter.java` ⇒ **只剩注释**（内联探测已搬走）。✅
4. `grep -rn "maxCompletionTokens\|MAX_COMPLETION_TOKENS" pi-java-ai/src/main` ⇒ 四处：枚举定义、
   `CompatResolver` 的探测缺省、`ModelsJsonConfig` 的串映射、**唯一**的落线点
   `OpenAICompletionsMessageConverter:207`（在读 `compat.maxTokensField()` 的分支内）。✅
5. `grep -rln "supportsTemperature" pi-java-ai/src/main` ⇒ 五个文件：`ModelCompat`（字段）、
   `CompatDef`（schema）、`ModelsJsonConfig`（归一）、`CatalogCompatRules`（标注）、
   `CompatResolver`、`AnthropicRequestBuilder`（读点）。✅
6. `docs` 侧：`docs/48 §5` 的 B 行、`docs/41` 的两行、`docs/51 §3 F5/F6`、`docs/32` 的
   B97–B102、本文 §12 —— 全部已回填。✅

---

## 9. 裁决点（请审核时给结论）

### R1 —— 本包的范围

| 选项 | 内容 | 代价 |
|---|---|---|
| **A（推荐）** | 解析链 ＋ 已消费字段的生产者 ＋ 3 个今天可观察的新字段（`supportsTemperature`、`maxTokensField`，以及 R5 的两个）；其余 40 余字段按 §4.4 归属 | 需要 §4.4 那张表被接受（本包变成「compat 的**地基**」，后续包各取一片） |
| B | 整体移植五个接口的全部 52 个字段与全部读点 | 吞掉 A-01／A-09／A-10／A-14 的地盘；约 40 个字段加进来没有读点 ⇒ 与 `ModelCompat` 类 javadoc 的既有原则冲突；且 52 个字段的「线格是否正确」不可能在一个包里验证完 |

**建议 A。** 依据：`docs/48 §2` 给 A-07 的批次是 **B/D**，而 A-01／A-09／A-10／A-14 与它
**同批**、优先级更高或相当 ⇒ A-07 的合理定位就是它们共同的地基。

### R2 —— 解析放在请求期，且用哪个 baseUrl？

| 选项 | 内容 | 代价 |
|---|---|---|
| **A（推荐）** | 请求期：`CompatResolver.resolve(model, protocol, baseUrl)`，`baseUrl` 取**车道的有效值**（`ApiOptions.baseUrl()` 覆盖后的那个） | 与 pi 的 `detectCompat(model)` 用的 `model.baseUrl` **不同**（pi 的探测不吃请求期覆盖）⇒ 一处刻意的形状偏差，写进 javadoc |
| B | 给 `ModelInfo` 加 `baseUrl` 组件，解析退化成纯函数 `resolve(model)`，与 pi 逐字同形 | `ModelInfo` 的构造点（`BuiltinCatalog`／`ModelsJsonConfig`／`ModelInfo.minimal`／夹具）全要改；且 baseUrl 覆盖度是 **A-16** 的地盘 ⇒ 越界 |

**建议 A。** 偏差方向是「java 比 pi **多**认一次请求期覆盖」，对内置模型**不可观察**
（两者的值相同），只在用户同时写 models.json 与 `--base-url` 时可见。javadoc 写明。

### R3 —— 目录标注的值以「代码推导」还是「生成数据」为准？

| 选项 | 内容 | 代价 |
|---|---|---|
| **A（推荐）** | 以**代码推导面**（P5 的三个谓词 ＋ 探测）为**契约**，生成数据只作**佐证**；标注时把谓词**照抄进 Java**（`AnthropicCompatRules` 一类的小助手），而不是把 §7.1 的字符串硬编进目录 | 多一个小类与一组纯函数夹具；好处是 models.dev 漂移时 Java 侧的行为**只随 pi 的代码变**，与 pi 的变更点一一对应 |
| B | 把 §7.1 的值直接硬编进 `BuiltinCatalog` | 更少代码；但数据会静默陈旧（`deepseek-v4-flash` 的教训就在眼前），且「为什么是 true」在代码里查不到 |

**建议 A。** 注意 `isAnthropicAdaptiveThinkingModel` 是 `includes` 而不是正则 ⇒ 照抄要
**逐字**（否则会漏掉 `opus-4.8` / `opus.8` 一类的别名形态）。

### R4 —— 是否有**不**入本包的已消费字段？

按 §4.4，`supportsStrictMode`（Responses）与 `supportsFinishReason` 已有读点。前者由
**B88** 落的 `strictField(supportsStrictMode)` 消费，而它的值是**车道的常量缺省**，
本包把它改成「目录驱动」。⚠️ 这会让 `openai/gpt-5` 在 Responses 车道上从「不发 `strict`」
变成「发 `strict`」—— **但 java 的 `openai` 默认不是 Responses**（A-04）⇒ 只有显式配置才可达。
| 选项 | 内容 |
|---|---|
| **A（推荐）** | 一并改（字段已经是模型级的，读点已经在了，只差生产者）；并在 §3 D5 写明可达条件 |
| B | 不动，等 A-04 决定默认 wire 之后再一起改 |

**建议 A**，但若审核时认为「D5 的可达性依赖 A-04」会制造一个半截状态，则取 B 并把
`supportsStrictMode` 的标注一起推给 A-04。

### R5 —— `supportsStore` / `supportsDeveloperRole` 要不要进本包？

两者今天**零消费者**（`grep` 零命中），pi 的读点各一处（P7）：`store:false` 与
`developer` 角色。对 java 的**内置** deepseek 模型，pi 的探测把两者都关掉 ⇒ **无差异**；
差异只出现在「标准 OpenAI 兼容端点」上（pi 发 `store:false`、推理模型用 `developer` 角色，
java 两样都不做）。

| 选项 | 内容 | 代价 |
|---|---|---|
| **A（推荐）** | 进本包：两个字段 ＋ 两个读点 ＋ 探测（都在 completions 车道，与 `maxTokensField` 同一处） | 包再大一点；`store:false` 是**隐私面**的差异（pi 显式关存储，java 依赖服务端默认） |
| B | 登记，留给「OpenAI 默认 wire」那一包（A-04） | 更小；但 A-04 是**默认 wire** 的裁决，与这两个字段不是同一件事，可能再来回一次 |

**建议 A**（理由是 `store:false` 的后果比线格形状更实在），但这条**最容易被否**，请明确表态。

### R6 —— 九个 `Boolean` 组件的**三态**要不要保

`ModelCompat` 里六个 `Boolean` 组件现在的三态理由是「缺席 ≙ 探测」。有了 §3 F7 的三条路径
之后，这条的理由要**换一个说法**（我最初的推理是错的，记在这里免得再错一次）：

- 在 **path C**（`models[]` 整条替换）下，定义的 compat **就是**该模型的全部 compat
  ⇒ 三态**不保护任何东西**（内置的目录值本来就被丢弃，而这是 pi 的行为）；
- 三态真正有价值的地方是 **path A/B**（§3 F7 的逐字段合并面，今天 java 没有、登记给 A-16）
  —— 一旦那两条落地，「用户只写一个键」必须**不**把其余键从目录值上抹掉，而这就**必须**
  区分「写了 `false`」与「没写」。

| 选项 | 内容 |
|---|---|
| **A（推荐）** | 保持 `Boolean` 三态；`CompatDef` 的 javadoc 把「用户没写」与「解析后缺省」**分两层**写清（§4.3 的 ⚠️） |
| B | 收紧为 `boolean` ＋ 在 `compatOf` 里归一成缺省值 | 今天无行为差异（path C 下等价），但 A-16 落地时要**改回来**，且收紧的那一刻起「没写」这个信息就永久丢失了 |

**建议 A**（成本为零：三个原始 `boolean` 组件不动，六个 `Boolean` 也不动；收益是 A-16 落地时
不需要回头改本包）。

### R7 —— 要不要在本包补上 pi 的另外两条覆盖路径（A/B）？

按 §3 F7，用户今天**无法**为内置模型**逐字段**调 compat（例如「我的 Claude 走一个不支持
mid-convo 的代理，把 `supportsMidConvoToolChanges` 关掉但保留 `forceAdaptiveThinking`」）。

| 选项 | 内容 | 代价 |
|---|---|---|
| **A（推荐）** | 不补，登记给 **A-16**（它的标题就是 models.json 的「provider/protocol/baseUrl 覆盖」）；本包只把 path C 的键扩全 | 用户想要细调只能整条重定义（＝ pi 的 path C 语义，**不是**缺口，只是少了更细的两条路） |
| B | 本包补 **provider 级 `compat`**（最便宜的一条：`ProviderDef.compat` ＋ 以它为基）＋ 登记 `modelOverrides` | ⚠️ java 的 models.json provider 是「**另一个 provider 对象**」，provider 级 compat 只会作用于**该文件自己的模型**，而 pi 的 path B 作用于**内置模型**（`applyModelsJson` 跑在 `base.getModels()` 上）⇒ 补出来的是**半条**，反而制造一处看起来相等实则不同的语义 |
| C | 本包补全 A＋B（含完整的 `modelOverrides` 记录：`name`/`cost`/`contextWindow`/`maxTokens`/`thinkingLevelMap`/`samplingParams`/`compat`） | 吃掉 A-16 的地盘；`modelOverrides` 里除 compat 外的组件都有各自的覆盖语义与边界（`cost` 的逐字段、`thinkingLevelMap` 的逐键…），需要各自的设计门 |

**建议 A。** 理由：R1 已经把本包定为「compat 的地基」，而 A/B 两条路径的地基是
「models.json 与内置目录的**合并面**」—— 那是 A-16 的地基，先做 compat 只会得到一个
半截的合并面（选项 B 的后果）。⚠️ 但如果审核认为「用户今天就没有任何细调手段」不可接受，
请选 C 并把 A-16 与它合并成一个包。

### R8 —— 三步拆分（§5）

按建议则 A7a／A7b／A7c 各一次提交（加文档两次）。

---

## 10. 遗留登记（本包预计产出，实施后落 `docs/32`，编号从 **B97** 起）

| 预编号 | 内容 | 归属 |
|---|---|---|
| **B97** | `deepseek/deepseek-v4-flash` 是 pi 于 2026-09-10 **改名**前的退役别名（`12f59336a`，现名 `deepseek-flash`）；`mistral/mistral-large` / `mistral-small` 在 pi 目录里**完全不存在**（pi 是 `mistral-large-latest` 一类）⇒ java 内置目录的三个 (provider, id) 与 pi 不匹配 | **A-08**（改 id 会改用户点名模型的方式） |
| **B98** | 零消费者的 compat 字段清单（`supportsUsageInStreaming`／`requiresThinkingAsText`／`vercelGatewayRouting`／`zaiToolStream`／`supportsOpenAIGrammarTools`／`sendSessionAffinityHeaders`／`sessionAffinityFormat`／`vllmPriority`／`supportsMaxOutputTokens`／`supportedStrictTools`／`allowedFallbackModels` 等）—— 今天**不可达**，本包照原则不加 | 逐项待裁决（`docs/48 §5` 的 E 批次「无生产者代码逐项裁决」） |
| **B99** | `supportsMidConvoEffort` 的主体行为（`thinking.block_binding`）在钉住的 `anthropic-java-core` 上**写不出来**（`docs/46 §3-D3`），而它另外还门着「temperature 抑制」（P7 的第一个合取项）⇒ 该字段是**半可移植**的 | 独立裁决（与 Anthropic SDK 升级捆绑） |
| **B100** | pi 的 `detectCompat(model)` 只读 `model.baseUrl`（请求期覆盖**不**参与探测），而 java 今天那处内联探测读的是**覆盖后**的 baseUrl（§9 R2 的偏差） | 随 A-16（baseUrl 覆盖）一并收敛 |
| **B101** | `docs/51 §3 F5/F6` 的**根因**在 A7 落地后消失：四条车道的原生渲染与 sections 段补丁从「测试可达」变成「生产可达」—— 该两条登记随之结案 | `docs/51` |
| **B102** | **java 缺 pi 的两条 compat 覆盖路径**：`providers.<id>.modelOverrides.<modelId>.compat`（逐字段合并到内置模型，`provider-composer.ts:108-134` → `:449-461`）与 provider 级 `providers.<id>.compat`（逐字段合并到该 provider 的每个模型，`:209-213`）。java 只有 `models[]` 的**整条替换**（＝ pi 的 path C，语义一致，`docs/models.md:351` 文档化）。后果：用户**无法**为内置模型逐字段调 compat，只能整条重定义。⚠️ 根因是结构性的：java 的 models.json provider 是「另一个 provider 对象」，与内置 provider 没有合并面 ⇒ 补 provider 级 compat 会得到**半条**（只作用于该文件自己的模型） | **A-16**（`models.json` 的 provider/protocol/baseUrl 覆盖）|

---

## 11. 本次设计的取证方式与已知不足

**取证方式**：
1. pi 源码全部经 `git show 3390bd936:<path>`（工作树**只**用于读被 gitignore 的
   `src/providers/data/*.json`，而那是我**自己跑生成器**产出的）；
2. **跑**了 pi 的 `scripts/generate-models.ts --strict`（退出码 0）取目录真值，而不是只读谓词；
3. **用 pi 自己的代码路径**（`getBuiltinModel`）取 compat，而不是读 JSON 猜语义；
4. java 侧全部 `grep`／实读，行号逐条核过。

**已知不足**（与 `docs/32 §10.7` 的「不准只读源码」对齐）：
- §7.3 记的那条：**没有**做两侧的线上请求体抓取 ⇒ D1–D5 是三截拼接，实施时必须补一次
  同请求的对照（本包把它列为 A7a 的第一条先红）；
- 目录数据的**漂移性**：§7.1 的值是我 2026-09-26 跑出来的，随 models.dev 变。这正是 §9 R3
  建议「以代码推导面为契约」的理由；
- `docs/48 §2` 的 A-07 行还列了「会话亲和性」这一族的**用户可观察后果**，而本包把它
  归为零消费者登记（B98）。若审核认为会话亲和性属于本包的必做面，R1 需回到 B 选项。

---

## 12. 实施记录（2026-09-27 闭环）

R1–R8 **全按建议实施**。提交：`9eee9f8`（A7a 解析层）· `22bde33`（A7b 目录标注 ＋ models.json 面）·
`3770fd3`（A7c 车道接线）· `3d7898e`（收口：最后一处直接读点）· 文档 `docs/53`/`docs/48`/`docs/41`/`docs/32`。

### 12.0 设计期的两处自我更正（保留在此，免得只留在 §3/§7 里被漏读）

- **R7 的判据（§7.2）在实施前已答**：起初我把 F7 写成「pi 的 `models[]` 逐字段合并、java 整条替换」
  ⇒ 缺口。**读错了路径**：pi 是**三条**路径（`modelOverrides` 逐字段、provider 级逐字段、
  `models[]` 整条替换），java 有的那条**与 pi 一致**，真正的差距在另外两条（属 **A-16**）。
  取证方式是**去读 pi 自己的 `docs/models.md` 与 `provider-composer.ts`** —— 我先前只读两侧源码
  就下了结论，正是 `docs/32 §10.7`「不准只读源码」要防的那件事。
- **§3 F5 的 id 漂移**：`deepseek/deepseek-v4-flash` 是 pi 于 2026-09-10（`12f59336a`）**改名**前的
  退役别名（现名 `deepseek-flash`）；`mistral-large`/`mistral-small` 在 pi 目录里**不存在**。
  ⇒ 本包**不**改 id（改它会改用户点名模型的方式），归 **A-08**，登记为 B97。

### 12.1 A7a —— 解析层（`9eee9f8`）

- `MaxTokensField`（新，2 值枚举 ＋ `wireName()`）、`CompatResolver`（新，186 行，四个 per-lane
  静态方法 ＋ 私有 `resolved`/`pick`）。
- `ModelCompat` 从 9 组件扩到 **14**（`supportsTemperature`、`maxTokensField`、`supportsStore`、
  `supportsDeveloperRole`、`supportsStrictMode`）；**九参构造器保留为便捷构造器** ⇒ 既有 18 个
  构造点**零改签**（与 §4.5 预期的「三处会编译失败」**相反**，见 §12.4）。
- ⚠️ 一处**设计稿没写、实施中定下来的**：`supportsStrictMode` 的缺省**随车道相反**
  （responses `?? false`／azure `?? true`）⇒ 由 `forResponses(model, strictModeDefault)` 的
  **形参**给，而不是写死在解析器里 —— 否则「随车道变」的语义会被固化成一个值。

### 12.2 A7b —— 目录标注 ＋ models.json 面（`22bde33`）

- `CatalogCompatRules`（新，116 行）：pi 生成期的**三条纯字符串谓词逐字照抄**
  （正则/`includes`/是否小写化**三处不一致，刻意保留**），加 `getAnthropicMessagesCompat`
  的两条分支（mid-convo 两个标志同给、temperature 抑制）。
- `BuiltinCatalog`：`anthropicModel(...)`/`deepseekModel(...)` 两个助手 ＋ 8 参 `model(...)`
  重载；标注了 `claude-fable-5`／`claude-opus-4-8`／`claude-sonnet-4-6`（haiku 标注结果 ≡ `NONE`）
  与 `deepseek-v4-pro`。
- `CompatDef` 从 4 键扩到 **14** 键；`compatOf` 改签名（带 provider/model 上下文）以便
  **未知 `maxTokensField` 响亮抛错**。
- ⚠️ **一处设计稿没写**：`maxTokensField` 的未知取值是**抛弃**还是**报错** —— 实施选了报错
  （理由：那是线格**字段名**，写错一个字母除「字段名换了」以外没有任何症状），
  与同文件「未知**键**被忽略」的既有裁决**并存**（两者代价不同，夹具各钉一条）。

### 12.3 A7c —— 车道接线（`3770fd3` ＋ `3d7898e`）

- `Transcripts.resolveTranscript` 的形参从 `ModelInfo` **换成 `ModelCompat`**（四条车道 ＋ 夹具同步）。
- 四条车道各在入口解析一次：`AnthropicRequestBuilder`（＋ 温度抑制 ＋ `AnthropicThinking` 收 compat）、
  `OpenAICompletionsMessageConverter`（＋ `maxTokensField`／`store`／`developer` 角色／
  删掉内联 deepseek 探测）、`ResponsesMessageConverter`（把车道的 `supportsStrictMode` 形参
  **换成解析后的 `ModelCompat`**，两条 responses 车道各自喂缺省）、`MistralConversationsApi`。
- 新夹具：`AnthropicCompatWireTest`（6）／`CompletionsCompatWireTest`（5）—— 都用**内置目录**的
  真模型，每条都配了「配对用例」（缺席断言不空过）。

### 12.4 与设计稿的对账（**4 处偏离／更正，全部实测**）

1. **§4.5 预测错了**：设计稿说「三个九参规范构造器会编译失败、逼出改动」—— 实际**一处都没炸**，
   因为 A7a 把九参形态**保留成便捷构造器**（写代码时才发现这个更省的形状）。
   ⇒ 影响面从「18 个构造点要改」变成 **0**。
2. **§5 的步骤划分微调**：设计稿把「`supportsTemperature`/`maxTokensField` 的读点」放在 A7a、
   「`supportsStore`/`supportsDeveloperRole` 的读点」放在 A7b。实际把**四个新字段的读点全部**
   放在 A7c（车道接线那一趟），A7a 只加字段与解析器 ⇒ 避免同一文件被改两遍。
3. **§6.2 的预测红集与实测不符**（设计期是猜的）：见表内对账；两条零红各有成因（M2 二态、
   M5 假变异）。
4. **`AnthropicThinking` 的 `compat` 形参是形状改动、不是行为改动**（M2 零红，§6.2）——
   设计稿把它算进「改行为」的读数里，实测**不成立**。

### 12.5 验收 grep 的逐步实测

| # | 命令 | 结果 |
|---|---|---|
| 1 | `grep -rn "compat()" --include=*.java pi-java-ai/src/main/java/com/pijava/ai/protocol/` | 车道**零命中**（两行注释除外）。收口前 `OpenAICompletionsApi:248` 命中一次 ⇒ `3d7898e` |
| 1b | `grep -rn "\.compat()" --include=*.java pi-java-ai/src/main …` | 只剩 `ModelsJsonConfig:214`（定义变更路径，允许） |
| 2 | `grep -rln "supportsMidConvoSystemMessages" pi-java-ai/src/main` | **10** 个文件（生产者 3 ＋ models.json 面 2 ＋ 读点 4 ＋ 字段自身） |
| 3 | `grep -rn "deepseek" …/OpenAICompletionsMessageConverter.java` | 只剩注释 |
| 4 | `grep -rn "maxCompletionTokens\|MAX_COMPLETION_TOKENS" pi-java-ai/src/main` | 4 处，落线点**唯一**且在 `compat.maxTokensField()` 的分支内 |
| 5 | `grep -rln "supportsTemperature" pi-java-ai/src/main` | 5 个文件（字段／schema／归一／标注／解析／读点） |

### 12.6 回归证据

- `ai`：**893 ⇒ 942**（＋49：A7a 19 ＋ A7b 15 ＋ A7b 的 `ModelsJsonConfigTest` 4 ＋ A7c 11）。
- 全 reactor `mvn -o test` **BUILD SUCCESS**（14/14）：telemetry 31 · ai 942 · agent-core 519 ·
  sqlite 35 · coding-agent 272 · protocol 14 · client 2 · server 48 · tui/web/evals/dist 各自 SUCCESS。
- **conformance 15/15 仍绿**（`docs/51 §12.4.2` 的金标未受本包影响）。
- checkstyle 0 新违规；`git diff --check` clean；无 `System.out.println`；改动文件均 ≤500 行，
  **两处存量超限**（`MistralConversationsApi` 508⇒512、`ResponsesMessageConverter` 544⇒545，
  两条在 A7c **之前**就已超限）**未拆**，登记在 `docs/48 §5` 的 B 行。

### 12.7 本包的方法论产出（写进 memory 与本记录）

1. **「零红」的第四种成因：变异体语义等价**（M2 的 `forceAdaptiveThinking` 是二态常量字段）。
   与 A4 的三条（变异没落地／夹具没牙／通道没夹具）并列。
2. **「假变异」第 2 次兑现**（M5：`A && B ;` 被折叠成原条件）—— 与 A4 的 `|| false` 同型。
   ⇒ 变异体落地检查之外，还要问「它到底改了哪个条件」。
3. **CRLF 第 4 次**（多行模式匹配失败）⇒ 凡跨行模式用单行锚点。
4. **验收 grep 的价值被实证**：唯一漏网的读点（`OpenAICompletionsApi:248`）是**设计期就写下的
   grep** 抓到的，不是人眼找到的。
