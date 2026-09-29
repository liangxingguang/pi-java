# 59 - A-02：OpenRouter chat 车道（吸收 B133／B105／openRouterRouting）设计

> **状态：设计稿，待审核；零行代码。**（2026-09-29）
> 判据＝与 pi 行为一致（不是文档一致）。参照 pi 工作树 `D:\workplaceForai\pi`（下文的 pi 行号均指它）。
> 吸收面：`docs/32` **B105**（completions 的 `cacheControlFormat`）、**B133**（openrouter 思考形状不可达）、
> `docs/53 §4.4` 归属表 **openRouterRouting → A-02** 三处登记，本包全部闭环。
> 前包约束继承：A-09 的 `ThinkingFormatWriter.OPENROUTER` 臂**已落、不动**（`docs/58 §9`）；
> A-01 的 `cacheRetention` 通道（`ApiOptions.extra`）**已通、只加消费端**；A-10 的夹取**已落、不重接**。

## 1. 范围

### 1.1 做

| # | 面 | 一句话 |
|---|---|---|
| 1 | **provider 注册** | 内置 `openrouter` chat provider（双协议：`anthropic-messages` ＋ `openai-completions`），与既有 `openrouter-images` 并存（pi 就是两个 provider） |
| 2 | **per-model api 通道** | `ModelInfo` 新增 `api` 组件（pi `Model.api`）＋ 宿主派发（`extra["protocol"]` 首个生产者）＋ models.json 的 per-model `api` 接线（pi `provider-composer.ts:142`） |
| 3 | **内置目录** | 14 条 anthropic 车道模型**全量**（派发正确性所系）＋ completions 车道精选子集（含 `anthropic/*:batch`——B105 的内置消费者） |
| 4 | **resolver 修复** | `DefaultModelResolver.resolve` 精确匹配先于 `:thinking` 后缀切分（pi `parseModelPattern:209-216`）——`:batch` id 今天会被静默解析成非 batch 模型 |
| 5 | **B105** | completions 车道 `cacheControlFormat` 探测＋`cacheRetention` 消费＋三断点 `cache_control`＋`prompt_cache_retention:"24h"`（pi `:814-825`、`:1069-1180`） |
| 6 | **openRouterRouting** | `ModelCompat` 新组件＋models.json 键＋落线 `provider` 体键（pi `:980-982`，**读 raw compat**） |
| 7 | **B133** | 上述落完后 openrouter 思考形状生产可达——补可达性夹具收口 |
| 8 | **顺带修复** | `ConfigurableProvider.effectiveOptions` 丢 `authKind`（本包 anthropic 车道的 baseUrl pin 会重建 ApiOptions，必须先修；§4.5） |

### 1.2 不做（各有归属，不是遗漏）

| 面 | pi 位置 | 归属 |
|---|---|---|
| 会话亲和头族（`x-session-id`／`x-session-affinity`／`session_id`／`x-client-request-id`） | `openai-completions.ts:770-778`、`anthropic-messages.ts:965-968` | **B103**（独立包；java 无 `sessionId` 通道，字段按 B98「不投机加」不加） |
| `prompt_cache_key` | `:819-823` | 同上（`clampOpenAIPromptCacheKey(undefined)` → `undefined`，无 sessionId 时 pi 也不发）——**新登记 B134** |
| `reasoning_details` 结构化收/放 | `:664-676`、`:342` | **B19 行外**（既有登记，不动） |
| 错误 `metadata.raw` 追加 | `:714-720` | **新登记 B135**（pi-java 无 `normalizeProviderError` 家族，属错误归一化包） |
| `vercelGatewayRouting` | `:985-995` | **B98**（无 vercel provider） |
| models.json per-model `baseUrl` | `provider-composer.ts` | **新登记 B136**（`ModelDef.baseUrl` 今天被静默吞；`ModelInfo` 无 baseUrl 组件，不在本包扩） |
| 378 条 completions 目录全量搬运 | `data/openrouter.json` | 既有「内置目录数据规模」缺口行（`docs/41:69`）；未知 id 走 `ModelInfo.minimal`＋探测，completions 车道行为不残缺（§6） |
| resolver 的 glob/scope 解析（minimatch） | `model-resolver.ts:290+` | **新登记 B137**（本包只落「精确匹配先行」这一处修复） |
| `supportsMidConvoEffort`（fable-5.1 的目录键） | `types.ts` | B98 家族（java 不携带；**新登记并入 B136 备注**） |
| OpenRouter OAuth 登录流进请求路径 | `auth/oauth/load.ts` | **B82**（既有登记；`OAuthProviders` 的 PKCE 配置已在，凭证链接线是 B82 的事） |

## 2. pi 侧取证

### 2.1 provider 定义（`providers/openrouter.ts`，全文 27 行）

```ts
export function openrouterProvider(): Provider<"anthropic-messages" | "openai-completions"> {
	return createProvider({
		id: "openrouter",
		name: "OpenRouter",
		baseUrl: "https://openrouter.ai/api/v1",
		auth: {
			apiKey: envApiKeyAuth("OpenRouter API key", ["OPENROUTER_API_KEY"]),
			oauth: lazyOAuth({ … loadOpenRouterOAuth }),
		},
		models: Object.values(OPENROUTER_MODELS),
		api: {
			"anthropic-messages": anthropicMessagesApi(),
			"openai-completions": openAICompletionsApi(),
		},
	});
}
```

目录数据（`providers/data/openrouter.json`，按 api 分组）实测：

- `anthropic-messages`：**14 条**，每条 `baseUrl: "https://openrouter.ai/api"`（⚠️ 无 `/v1`——Anthropic SDK 自己拼 `/v1/messages`）；
- `openai-completions`：**378 条**，`baseUrl: "https://openrouter.ai/api/v1"`；其中 **13 条 `anthropic/*:batch`**；
- 任何模型都**没有** `openRouterRouting`（0 条）⇒ 该键的生产者只有 models.json（§2.4-5）；
- completions 条目的目录 compat 实测三键：`thinkingFormat:"openrouter"`（与探测同值）、
  `sendSessionAffinityHeaders:true`（java 不携带）、部分 `supportsDeveloperRole:false`（与探测同值）；
  `:batch` 条目另带 `cacheControlFormat:"anthropic"`（与探测同值）。

### 2.2 派发链：键是 `model.api`，不是 provider

`compat.ts:262/:287`（`stream`/`streamSimple`）：`resolveApiProvider(model.api)`——**每个模型自带 api**。
models.json 侧 `provider-composer.ts:142`：

```ts
const api = definition.api ?? providerConfig.api ?? defaults?.api;
```

resolver（`model-resolver.ts:204-216`）：**精确匹配先行**，全串匹配不上才按最后一个冒号切
`:thinking` 后缀——注释明写「Supports models with colons in their IDs (e.g., OpenRouter's model:exacto)」，
`:batch` 同理。

### 2.3 completions 车道的 openrouter 特有点（`api/openai-completions.ts`）

1. **探测**（`detectCompat:1583-1679`，java 已抄大半）——本包补的两行：

```ts
const cacheControlFormat = provider === "openrouter" && model.id.startsWith("anthropic/") ? "anthropic" : undefined;   // :1632
…
supportsLongCacheRetention: !(isTogether || isCloudflareWorkersAI || isCloudflareAiGateway || isNvidia || isAntLing),   // :1671-1677
```

⚠️ `:1632` 判据是 **`provider === "openrouter"` 严格等**＋模型 id 前缀，**不**含 `baseUrl.includes("openrouter.ai")`
（与 `isOpenRouter`（:1595）刻意不同——中转站拿不到 anthropic 形状的 `cache_control`）。
`sendSessionAffinityHeaders: isOpenRouter`／`sessionAffinityFormat`（`:1669-1670`）本包**不加**（§1.2）。

2. **合并**（`getCompat:1704`）：`openRouterRouting: model.compat.openRouterRouting ?? {}`——但**无读者**读 resolved 面（下条）。

3. **落线**（`buildParams:980-982`）——⚠️ **读 raw `model.compat`，不是 resolved compat**：

```ts
// OpenRouter provider routing preferences
if (model.compat?.openRouterRouting) {
	(params as any).provider = model.compat.openRouterRouting;
}
```

位置：思考预算字段（`:972-978`）之后、`samplingParams`（`:996-999`，「body 的最后一个变更」）之前。
JS 真值语义：`{}` 为真 ⇒ 用户显式写空对象也会发 `provider:{}`。

4. **缓存**（`:814-825`、`:858-859`、`:1069-1180`）：

```ts
const cacheControl = getCompatCacheControl(compat, cacheRetention);          // :814
const params = { model, messages, stream: true,
	prompt_cache_key: (model.baseUrl.includes("api.openai.com") && cacheRetention !== "none")
		|| (cacheRetention === "long" && compat.supportsLongCacheRetention)
			? clampOpenAIPromptCacheKey(options?.sessionId) : undefined,      // :819-823（无 sessionId ⇒ 恒 undefined）
	prompt_cache_retention: cacheRetention === "long" && compat.supportsLongCacheRetention ? "24h" : undefined };  // :825
…
if (cacheControl) { applyAnthropicCacheControl(messages, params.tools, cacheControl); }   // :858-859

function getCompatCacheControl(compat, cacheRetention) {                     // :1069-1079
	if (compat.cacheControlFormat !== "anthropic" || cacheRetention === "none") return undefined;
	const ttl = cacheRetention === "long" && compat.supportsLongCacheRetention ? "1h" : undefined;
	return { type: "ephemeral", ...(ttl ? { ttl } : {}) };
}
function applyAnthropicCacheControl(messages, tools, cacheControl) {          // :1081-1089 —— 三断点，注意顺序
	addCacheControlToSystemPrompt(messages, cacheControl);    // 第一条 system|developer 消息
	addCacheControlToLastTool(tools, cacheControl);           // 工具表末项（⚠️ 无 supportsCacheControlOnTools 门——与 anthropic 车道不同）
	addCacheControlToLastConversationMessage(messages, cacheControl);  // 倒序找第一条「挂得上」的 user|assistant|tool
}
```

「挂得上」（`addCacheControlToTextContent:1152-1180`）：串 content 非空 ⇒ 改写成
`[{type:"text",text,cache_control}]`；数组 content ⇒ 挂在**最后一个** `type:"text"` 分片上；
content 缺席/空串/无数列文本分片 ⇒ `false`，继续往前找。
`cacheRetention` 来源（`:342`）：`resolveCacheRetention(options?.cacheRetention, options?.env)`——与 anthropic 车道同一个 helper（A-01 已移植为 `extra["cacheRetention"]` → `PI_CACHE_RETENTION` → `SHORT` 链）。

5. **思考形状**（`:931-940`，`thinkingFormat === "openrouter"` 臂）——**A-09 已移植**
（`ThinkingFormatWriter:131-142`），本包只让它生产可达（B133）。

### 2.4 anthropic 车道的 openrouter 特有点（`api/anthropic-messages.ts`）

`getAnthropicCompat:206-213` 的 `isOpenRouter` **只**影响 `sendSessionAffinityHeaders/sessionAffinityFormat`
（`:965-968` 的 `x-session-id` 头）⇒ 本包 anthropic 车道**零车道改动**（亲和头族归 B103）。
其余全部走既有 `AnthropicMessagesApi`（baseUrl 覆盖 `:89-90` 已支持）。

## 3. pi-java 现状（缺口逐处）

| # | 缺口 | 位置 |
|---|---|---|
| G1 | `ProviderCatalog` 只注册 `OpenRouterImagesProvider`（`:44`）——chat 面整条不可用 | `provider/builtin/ProviderCatalog.java` |
| G2 | `ModelInfo` **无 `api` 组件**；`extra["protocol"]` 通道存在（`ConfigurableProvider.resolveProtocol:108-120`）但**生产上零写入者** ⇒ provider 内永远走 `defaultProtocol` | `catalog/ModelInfo.java`、`coding-agent DefaultProviders.streamBlocking:133` |
| G3 | `ModelsJsonSchema.ModelDef.api()/baseUrl()` **解析后被吞**（`toModelInfo:192-218` 不读）——正是 `CompatDef` javadoc 自己警告的「known key 被静默吞」形态 | `provider/ModelsJsonConfig.java` |
| G4 | `ModelCompat`（23 组件）无 `cacheControlFormat`、无 `openRouterRouting`；`CompatResolver.forCompletions` 对 `cacheControlFormat`/`supportsLongCacheRetention` **传 null**（`:115-121` 注释明写「A-02 之前不可达」） | `catalog/ModelCompat.java`、`catalog/CompatResolver.java` |
| G5 | completions 车道**不读** `cacheRetention`（`OpenAICompletionsApi` 无 `retentionOf`），`buildParams` 无第 4 参 ⇒ 断点与 `prompt_cache_retention` 都无处落 | `protocol/OpenAICompletionsApi.java`、`OpenAICompletionsMessageConverter.java` |
| G6 | `DefaultModelResolver.resolve:120-122` **冒号切分先行** ⇒ `openrouter/anthropic/claude-fable-5:batch` 被切成 `…fable-5` ＋ 伪 thinking 后缀，静默解析成**非 batch** 模型 | `ai/model/DefaultModelResolver.java` |
| G7 | `ConfigurableProvider.effectiveOptions:127-129` 与 `ModelsJsonProvider.createApi:45-46`/`withInlineKey:55-56` 用**五参** `ApiOptions` 构造重建 ⇒ `authKind` 被 compact 构造器归一成 `API_KEY`。生产上 baseUrl 恒空 ⇒ `Credentials` 给的 `BEARER`/`OAUTH` 形态**进不了车道**（A0 步7 的车道分派被上游拆台；未登记 ⇒ 本包 **B138**） | `provider/ConfigurableProvider.java` 等 |
| G8 | `CatalogCompatRules.thinkingFormatOf` 无 `openrouter` 臂（A-09 时 java 不携带该 provider） | `catalog/CatalogCompatRules.java:117-130` |

**已就绪、本包直接复用**：`CompatResolver.forCompletions` 的 `isOpenRouter` 探测（`:68`）、
`isOpenRouterDeveloperRoleModel`（`:88-89`）与 `detectedFormat=OPENROUTER`（`:100`）；
`ThinkingFormatWriter.OPENROUTER` 臂；`OAuthProviders` 的 openrouter PKCE 配置；
`AnthropicMessagesApi` 的 baseUrl 覆盖与 `retentionOf` 模式；`CatalogCompatRules.anthropic` 谓词。

**复用证据（设计期实测）**：`CatalogCompatRules.anthropic("openrouter", "anthropic/claude-*")` 的三个谓词
对 14 条 anthropic 车道模型**逐条给出与 pi 生成数据相同的 compat**（`fable-5`→adaptive ✓、
`opus-4.7/4.8/5/5.5`→adaptive＋temperature:false ✓、`haiku-4.5`/`opus-4.1`/`opus-4.5`/`sonnet-4`/`sonnet-4.5`→无标注 ✓、
midConvo 因 `provider !== "anthropic"` 恒缺席 ✓——pi 数据同样没有）。唯一差异：`fable-5.1` 的
`supportsMidConvoEffort:true` java 不携带（§1.2）。**谓词可用，无需硬编值**（A-07 R3 原则）。

## 4. 实现方案

### 4.1 `ModelInfo`：＋`api` 组件（11 → 12）

pi 的 `Model.api` 是字符串联合（`types.ts`）。java 侧存**线格字符串**（`"anthropic-messages"` 等），不存
`Protocol` 枚举——`catalog` 包今天不依赖 `provider` 包，不为一个字段造包环（`CacheRetention` 的同一裁决，
`docs/54 §9 R2`）。`null` ≙ 「provider 默认协议」（既有全部目录零改动）。

```java
public record ModelInfo(
    …
    ModelCompat compat,
    /** pi {@code Model.api}（线格名，如 "anthropic-messages"）；{@code null} ≙ provider 默认协议。
     *  只有多协议 provider 的内置目录（openrouter）与 models.json 的 per-model "api" 会写它。 */
    String api
) {
    public ModelInfo { … api = api == null || api.isBlank() ? null : api; }   // 空串归一成缺席
    // 既有 11 参规范构造降级为便捷构造（api=null）——A-09/A-10 同一手法，全部存量调用点零改签
    public ModelInfo(…11 参…) { this(…11 参…, null); }
}
```

`Protocol` 补一个共享解析口（今天同一行 `valueOf(toUpperCase.replace('-','_'))` 已在
`ConfigurableProvider.resolveProtocol:113` 与 `ModelsJsonConfig:163` 各写一遍，本包出现第三处）：

```java
/** 线格名（"openai-completions"）→ 枚举；未知值响亮抛（与 models.json 其它闭集键同口径）。 */
public static Protocol fromWire(String wire) {
    try { return valueOf(wire.toUpperCase(Locale.ROOT).replace('-', '_')); }
    catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown api: " + wire, e); }
}
```

### 4.2 models.json 的 per-model `api`（G3 的一半）

`ModelsJsonConfig.toModelInfo` 增读 `model.api()`（pi `provider-composer.ts:142` 的
`definition.api ?? providerConfig.api`；`defaults?.api` 那一级 java 无对应物——java 的 models.json
是整条替换语义，`docs/53 §3 F7` path C）：

```java
// toModelInfo 内：未知 api 响亮抛（Protocol.fromWire），合法则存线格名
var api = model.api() == null || model.api().isBlank() ? null
    : Protocol.fromWire(model.api()).wireName();
return new ModelInfo(…, compatOf(…), api);
```

`ModelsJsonProvider` 的 `supportedProtocols` 从「provider 级单协议」扩成
「provider 协议 ∪ 各模型 api」——否则 `resolveProtocol:115-117` 会把 per-model api 拒掉：

```java
ModelsJsonProvider(String id, String displayName, ProviderDef def, Protocol protocol, ModelCatalog catalog) {
    var protocols = new LinkedHashSet<Protocol>();
    protocols.add(protocol);
    for (var m : def.models() == null ? List.<ModelDef>of() : def.models()) {
        if (m.api() != null && !m.api().isBlank()) protocols.add(Protocol.fromWire(m.api()));
    }
    this.config = new ProviderConfig(id, displayName, def.baseUrl(), null,
        protocol, Set.copyOf(protocols), catalog, null);
    …
}
```

（`createChatApi` 已支持两条车道，`:60-70`，零改动。`baseUrl` 每模型覆盖**不做**，B136。）

### 4.3 新文件 `provider/builtin/OpenRouterModels.java`：目录数据

**anthropic 车道 14 条全量**——不是数据洁癖：未入目录的 `openrouter/anthropic/*` 会退化成
`ModelInfo.minimal`（api=null ⇒ 走 completions 默认协议 ⇒ **发错车道**）。字段逐条转录自
`data/openrouter.json`（id/name/reasoning/input/cost 四费率/contextWindow/maxTokens/thinkingLevelMap），
compat **不硬编**、经 `CatalogCompatRules.anthropic("openrouter", id)` 算（§3 的复用证据）。
`api` 组件写 `"anthropic-messages"`。

**completions 车道精选子集**（裁决点 R2，推荐 10 条）：

| 组 | 条目 | 存在理由 |
|---|---|---|
| `anthropic/*:batch` ×4 | `claude-fable-5:batch`、`claude-sonnet-4.5:batch`、`claude-haiku-4.5:batch`、`claude-opus-4.8:batch` | **B105 的内置消费者**（`cacheControlFormat` 探测命中的唯一内置模型族）＋ developer 角色（`anthropic/` 前缀） |
| `openai/*` ×2 | `gpt-5.1`、`gpt-5.1-codex-max` | developer 角色（`openai/` 前缀）＋思考级别表非平凡（`xhigh:null` 等三态样本） |
| `deepseek/*` ×2 | `deepseek-chat`（非 reasoning）、`deepseek-r1`（reasoning，tlm `{off:null}`） | 非 reasoning 对照＋「off 显式 null ⇒ 整键不发」样本 |
| `google/*` ×2 | `gemini-2.5-pro`（reasoning）、`gemini-2.5-flash`（非 reasoning） | 同上对照 |

compat 经 `CatalogCompatRules.completions("openrouter", id)`；该表随本包加一臂（G8）：

```java
case "openrouter" -> ThinkingFormat.OPENROUTER;   // pi 生成器给全部 completions 条目写死同值（冗余照抄，与 zai 同口径）
```

`sendSessionAffinityHeaders`／`supportsDeveloperRole:false`／`cacheControlFormat:"anthropic"` 三个目录键
**不转录**：第一个 java 不携带（B98），后两个与探测同值（A-07 原则：「探测的差量不在这里标注」）。
能力集：anthropic 车道 `TEXT+IMAGE_INPUT+TOOL_USE+THINKING+STREAMING+PROMPT_CACHING`；
completions 按 `reasoning`/`input` 数据逐条给（THINKING/IMAGE_INPUT 位与 pi 数据一一对应）。

### 4.4 新文件 `provider/builtin/OpenRouterProvider.java`（G1）

```java
public final class OpenRouterProvider extends ConfigurableProvider {

    /** anthropic 车道的默认 baseUrl（pi 数据的 per-model baseUrl；Anthropic SDK 自拼 /v1/messages）。 */
    private static final String ANTHROPIC_BASE_URL = "https://openrouter.ai/api";

    @Override
    protected ProviderConfig config() {
        return new ProviderConfig(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY", Protocol.OPENAI_COMPLETIONS,
            Set.of(Protocol.OPENAI_COMPLETIONS, Protocol.ANTHROPIC_MESSAGES),
            OpenRouterModels.catalog(), null);
    }

    /** baseUrl 缺席时按协议 pin（pi 是 per-model baseUrl，java 是 per-protocol 默认——两条车道各一个值，
     *  对内置目录等价；models.json/--base-url 覆盖仍然赢）。⚠️ 六参构造：authKind 必须保真（B138）。 */
    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (resolveProtocol(options) == Protocol.ANTHROPIC_MESSAGES
                && (options.baseUrl() == null || options.baseUrl().isBlank())) {
            options = new ApiOptions(ANTHROPIC_BASE_URL, options.apiKey(), options.timeout(),
                options.maxRetries(), options.extra(), options.authKind());
        }
        return super.createApi(apiType, options);
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_COMPLETIONS -> new OpenAICompletionsApi(options, config().apiKeyEnvVar());
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException("OpenRouterProvider cannot serve " + protocol);
        };
    }
}
```

`ProviderCatalog.all()` 在 `OpenRouterImagesProvider` 旁注册它（16 → **17**，javadoc 计数与
`DefaultProviders.defaultProviders` 的「16 built-in providers」javadoc 同步改）。
探测面自动就位：`forCompletions` 的 `provider.equals("openrouter")`（`CompatResolver:68`）与
`modelName.startsWith("anthropic/")`（`:88-89`）读的都是既有字段。

### 4.5 `authKind` 保真修复（G7，B138）

三处五参重建改六参（把 `options.authKind()` 带上）：`ConfigurableProvider.effectiveOptions:127-129`、
`ModelsJsonProvider.createApi:45-46`、`ModelsJsonProvider.withInlineKey:55-56`。
先红夹具：BEARER 凭证 ＋ 空 baseUrl 的 provider ⇒ 改前车道收到 `API_KEY`。
（这不是 openrouter 专属 bug，但本包的 anthropic-lane pin 与 OpenRouter OAuth 凭证都会踩它，随包修。）

### 4.6 宿主派发（G2）：`DefaultProviders.streamBlocking`

pi 的派发键是 `model.api`（`compat.ts:262`）；java 的落点是**唯一生产调用点**
`streamBlocking`（`:127-155`，全仓 `createApi(ChatApi…)` 只此一处）——把 modelInfo 查找**提到
createApi 之前**，api 在场就注入既有 `extra["protocol"]` 通道：

```java
private static StreamIterator streamBlocking(…) {
    var modelInfo = provider.builtinModels().find(model)
            .orElseGet(() -> ModelInfo.minimal(model));
    var api = provider.createApi(ChatApi.class, withModelProtocol(apiOptions, modelInfo));
    …（request 构造不变，复用同一个 modelInfo）
}

/** pi {@code compat.ts:262} 的 {@code resolveApiProvider(model.api)} 在本仓的落点：
 *  模型自带 api ⇒ 走 extra["protocol"]（ConfigurableProvider.resolveProtocol 校验 supportedProtocols，
 *  不支持则响亮抛）；缺席 ⇒ 不注入，provider 默认协议照旧。显式 extra 已有 protocol 时不覆盖。 */
static Map<String, Object> withModelProtocol(ApiOptions options, ModelInfo modelInfo) { … }
```

（`withModelProtocol` 返回新 `ApiOptions`；包可见供 `DefaultProvidersTest` 变异探针，与 `cacheExtra` 同形。）

### 4.7 `DefaultModelResolver.resolve`：精确匹配先行（G6）

pi `parseModelPattern:209-216`：先 `tryMatchModel(pattern)` 全串精确匹配，**不中**才按最后一个冒号切。
java 对齐（`:120-122` 前插一段）：

```java
var trimmed = pattern.trim();
// pi parseModelPattern:209-216 —— 精确匹配先行：OpenRouter 的 ":batch"/":exacto" 一族 id 自带冒号，
// 先切后缀会把它们静默解析成另一个模型。
var exact = catalog.listModels().stream()
    .filter(m -> matchesPattern(m, trimmed))     // "provider/modelName" 或裸 "modelName"，忽略大小写（与下文同口径）
    .map(m -> m.id()).findFirst();
if (exact.isPresent()) return exact.get();
var colon = trimmed.lastIndexOf(':');            // 既有逻辑不动
…
```

glob/`*` 扫描**不做**（B137）。

### 4.8 `ModelCompat`：＋2 组件（23 → 25）＋探测

新枚举 `catalog/CacheControlFormat.java`（单值闭集 ⇒ enum，`@JsonValue wireName()` = `"anthropic"`；
pi `types.ts:737` `cacheControlFormat?: "anthropic"`）。新组件（追加在尾部，23 参规范构造降级为便捷构造，
A-09 手法，`CompatResolver.resolved`/`withCompletions`/全部夹具零改签）：

```java
…
Boolean supportsReasoningEffort,
/** pi {@code compat.cacheControlFormat}（openai-completions.ts:1632/:1073）：completions 线上发
 *  anthropic 形状 cache_control 的唯一开关。null ≙ 探测（provider=="openrouter" && id 以 "anthropic/" 开头）。 */
CacheControlFormat cacheControlFormat,
/** pi {@code compat.openRouterRouting}（:981）：原样发成 body 的 provider 键。
 *  ⚠️ 三态且 null **不**归一成空表——pi 读点是 raw model.compat 的真值判断，归一会让每个
 *  openrouter 请求都发 provider:{}（§5 R6）。 */
Map<String, Object> openRouterRouting
```

`CompatResolver.forCompletions` 两处探测值落位（替换 `:115-121` 的两个 null）：

```java
// pi detectCompat:1632 —— ⚠️ 严格 provider 等值，baseUrl 命中不算（中转站没有 anthropic 形状断点）
var cacheControlFormat = provider.equals("openrouter") && modelName.startsWith("anthropic/")
    ? CacheControlFormat.ANTHROPIC : null;
// pi detectCompat:1671-1677
var longCacheRetention = !(isTogether || isCloudflareWorkersAi || isCloudflareAiGateway
    || isNvidia || isAntLing);
```

`resolved()` 对两个新组件**原样透传**（`c.cacheControlFormat()`/`c.openRouterRouting()`）；
`cacheControlFormat` 的「explicit ?? detected」合一并进 `withCompletions`（+1 形参，
只有 completions 车道调它——A-09 R8 的同一形状）。`openRouterRouting` **无探测面**
（pi `:1657` 的 `{}` 无读者，§5 R6）。`forAnthropic/forResponses/forMistral` 零改动。

### 4.9 completions 的缓存面（G5，B105）

**通道**：`OpenAICompletionsApi` 加 `retentionOf(options)`（逐字复制 `AnthropicMessagesApi:105-113` 的
extra→parse 两分支）＋ 构造期读 `PI_CACHE_RETENTION` 回落 SHORT（pi `resolveCacheRetention`，A-01 已验证的同一链）。
`buildParams` 加第 4 参 `CacheRetention`（pi `:349-357` 同形；夹具调用点同批改）。

**新文件 `protocol/CompletionsCacheControl.java`**（`OpenAICompletionsMessageConverter` 已 513 行超限，
缓存面不再往里堆——pi `:1069-1180` 的六个函数整体落这里）：

```java
/** pi getCompatCacheControl:1069-1079。 */
static Optional<Map<String, String>> cacheControlOf(ModelCompat compat, CacheRetention retention) {
    if (compat.cacheControlFormat() != CacheControlFormat.ANTHROPIC || retention == CacheRetention.NONE)
        return Optional.empty();
    var ttl = retention == CacheRetention.LONG
        && Boolean.TRUE.equals(compat.supportsLongCacheRetention()) ? "1h" : null;
    return Optional.of(ttl == null ? Map.of("type", "ephemeral") : Map.of("type", "ephemeral", "ttl", ttl));
}

/** pi applyAnthropicCacheControl:1081-1089 —— 三断点。消息/工具已是 SDK 不可变对象 ⇒
 *  经 Jackson 树改写后 treeToValue 回类型（kimiToolSystemMessage:295-305 的既有通路，
 *  SdkJsonEscapeHatchTest 钉过往返）。改写规则逐字对应 addCacheControlToTextContent:1152-1180：
 *  串 content ⇒ [{type:"text",text,cache_control}]；数组 ⇒ 最后一个 text 分片；挂不上 ⇒ 往前找。 */
static List<ChatCompletionMessageParam> applyToMessages(List<ChatCompletionMessageParam> messages, Map<String,String> cc);
static List<ChatCompletionTool> applyToTools(List<ChatCompletionTool> tools, Map<String,String> cc);
```

`buildParams` 的接线：消息与工具先收集进局部 `List`（今天直接 `builder.addMessage/addTool`），
缓存面在场就走 post-pass 再整体入 builder——**顺序对应 pi 的 `:858-859`**（消息/工具建完之后、
`tool_choice` 之前）。⚠️ `addCacheControlToLastTool` **没有** `supportsCacheControlOnTools` 门
（那是 anthropic 车道 `:1114` 的，completions 侧 pi 就是裸挂）——别把 A-01 的门「顺手统一」过来。

**`prompt_cache_retention`**（pi `:825`）：`retention == LONG && TRUE.equals(compat.supportsLongCacheRetention())`
⇒ `builder.putAdditionalBodyProperty("prompt_cache_retention", JsonValue.from("24h"))`。
`prompt_cache_key`（`:819-823`）**不移植**：两个合取支都终结在 `clampOpenAIPromptCacheKey(options?.sessionId)`，
java 无 sessionId 通道 ⇒ pi 在同样条件下也发不出这个键（B134）。
对既有 provider 的线格影响＝**零**（SHORT 缺省下两个键都不出现；`cacheControlFormat` 探测只命中 openrouter）。

### 4.10 openRouterRouting 落线（§2.3-3）

`buildParams` 内、`writeThinkingTokenBudget` 之后 `SamplingParamsWriter.applyToCompletions` 之前
（pi `:976 → :981 → :996` 的同序；samplingParams 压过它也是 pi 语义）：

```java
// pi :980-982 —— ⚠️ 读 raw model.compat（不是 resolved），null 不发、空表发 provider:{}。
var routing = request.model() == null ? null : request.model().compat().openRouterRouting();
if (routing != null) {
    // 经树（A-09 R11：JsonValue.from(map 含 null) 被 NON_NULL 静默丢键；routing 值可含 null，
    // 如 pi 类型 sort.partition?: string | null）
    builder.putAdditionalBodyProperty("provider", JsonValue.from(PLAIN_JSON.valueToTree(routing)));
}
```

（`PLAIN_JSON` = `ThinkingFormatWriter` 同款无 inclusion mapper；若其可见性为 private 则抽到包内共享常量。）

### 4.11 models.json 两键（G3 的另一半）

`CompatDef` 追加（javadoc 注明 pi `model-config.ts:101-102`）：

```java
@JsonProperty("cacheControlFormat") String cacheControlFormat,   // 闭集："anthropic"；未知响亮抛（maxTokensField 同口径）
@JsonProperty("openRouterRouting") Map<String, Object> openRouterRouting   // 纯透传（pi 的 TypeBox 对象同样不拒未知键）
```

`compatOf` 尾部两位接上（`cacheControlFormatOf` 校验闭集；routing 原样透传，null 保持 null）。

## 5. 裁决点（R1–R8，各附推荐）

| # | 问题 | 推荐 | 备选与代价 |
|---|---|---|---|
| R1 | `ModelInfo.api` 用 String 还是 `Protocol` | **String 线格名**（pi 同形；免 `catalog→provider` 包环；`CatalogModel` DTO 天然可round-trip） | `Protocol`：类型安全但造包环，且 catalog 序列化面要多一层映射 |
| R2 | completions 内置子集规模 | **10 条**（§4.3 表：4 batch ＋ 2 openai ＋ 2 deepseek ＋ 2 google）＋14 anthropic 全量 | 全量 378：与「目录数据规模」既有缺口的裁决冲突（手写目录会陈旧）；只 batch 不 flagship：B133/developer-role 夹具没有正向样本 |
| R3 | `prompt_cache_retention`（`:825`）随本包还是另立 | **随本包**——它和 B105 共用同一条 cacheRetention 通道与同一个 compat 门，拆开则通道落两次 | 另立：通道重复接线，且 `supportsLongCacheRetention` 探测落了没有读者 |
| R4 | resolver 修复的幅度 | **只落「精确匹配先行」**（`:batch` 可达的最小修复，pi `:209-216` 逐字） | 连带 glob scope：pi 的 minimatch 面是另一个量级，B137 |
| R5 | models.json per-model `api` 随本包还是登记 | **随本包**（`ModelDef.api` 已解析、派发通道本包就要建，接线约 10 行；不接则它继续被静默吞——`CompatDef` javadoc 自己立的规矩） | 登记：多一个 B，且 openrouter 的 models.json 用户（自定义 batch 模型）用不上 |
| R6 | `openRouterRouting` 的三态处理 | **null 不归一**（compact 构造器跳过它）：pi 读点是 raw compat 真值判断，`{}` 与缺席**线格可区分**（发 `provider:{}` vs 不发） | 归一成 `Map.of()`：每个 openrouter 请求都会多发一个 `provider:{}`——静默改线格 |
| R7 | 缓存断点的实现机制 | **树改写 post-pass**（新文件；kimi 通路已验证；与 pi 的「建完再变异」语义 1:1，含「挂不上就往前找」） | 建时预判落点：需要在建消息时预知「谁是最后一条挂得上的」，与 pi 语义等价但证明负担大、易错 |
| R8 | `effectiveOptions` 丢 `authKind`（G7） | **随包修**＋新登记 B138（本包的 anthropic-lane pin 与 OAuth 凭证直接踩它） | 只登记不修：A-02 的 pin 代码就得自己绕过，等于修了一半 |

## 6. 可达性（改完后今天就能观察）

- `--provider openrouter --model anthropic/claude-fable-5` ⇒ **anthropic 车道**、baseUrl
  `https://openrouter.ai/api`、adaptive thinking（谓词标注）、A-01 三断点；
- `--model openrouter/anthropic/claude-fable-5:batch` ⇒ **completions 车道**（resolver 精确匹配）、
  developer 角色、`reasoning:{effort}` 嵌套（B133）、`cache_control` 三断点（B105）；
- `PI_CACHE_RETENTION=long` ＋ batch 模型 ⇒ 断点带 `ttl:"1h"` ＋ 顶层 `prompt_cache_retention:"24h"`；
- `cacheRetention:"none"`（压缩路径，A-01 已有生产者）⇒ 断点整族不发；
- models.json `compat.openRouterRouting:{order:[…]}` ⇒ body 出现 `provider:{order:[…]}`；
- 未入目录的 openrouter completions id（如 `meta-llama/llama-4-maverick`）⇒ `minimal` 退化但探测全中
  （thinkingFormat/developer-role/maxTokensField 都是 provider+前缀函数）——与 pi 的差距只剩目录元数据
  （价目/窗口），属既有「目录数据规模」行；
- 未入目录的 `openrouter/anthropic/*`（非 batch、不在 14 条内——今天不存在这种模型）⇒ 无派发标记，
  但 14 条已全量覆盖 pi 数据的 anthropic 车道 ⇒ 实际不可达。

## 7. 先红方案与变异探针

**先红夹具**（每条都在实现前跑红，红因注明）：

| 夹具 | 钉什么 | 改前红因 |
|---|---|---|
| `OpenRouterModelsTest` | 14 条 anthropic 条目 api="anthropic-messages"、compat 谓词产物（fable-5 adaptive、opus-5.5 temperature:false）、batch 条目存在 | 目录类不存在 |
| `OpenRouterProviderTest` | 缺省协议→`OpenAICompletionsApi`；extra protocol=anthropic-messages→`AnthropicMessagesApi` 且 baseUrl pin 成 `/api`；显式 baseUrl 赢；authKind 保真 | provider 不存在 |
| `CompletionsCacheControlWireTest` | batch 模型 buildParams：三断点各恰一处、串 content 改数组、`ttl` 随 retention、`none` 全撤、`prompt_cache_retention` 门；**对照**：openai/gpt-5.1 零断点 | `cacheControlFormat` 组件不存在 |
| `OpenRouterRoutingWireTest` | raw compat 有表⇒`provider` 键（含 null 值存活）；null⇒键缺席；空表⇒`provider:{}`；samplingParams 同名压过 | 组件不存在 |
| `CompatResolverTest` 增补 | `:1632` 严格 provider 等值（baseUrl 含 openrouter.ai 但 provider 不是 ⇒ null）；`supportsLongCacheRetention` 五谓词否定 | 探测传 null |
| `DefaultProvidersTest` 增补 | modelInfo.api 注入 extra["protocol"]；api=null 不注入；显式 extra 不被覆盖 | 注入函数不存在 |
| `DefaultModelResolverTest` 增补 | `openrouter/anthropic/claude-fable-5:batch` 解析成 batch 本尊（改前：解析成非 batch） | 精确匹配缺失 |
| `ModelsJsonConfigTest` 增补 | per-model api 进 ModelInfo 且 provider supportedProtocols 扩容；`cacheControlFormat` 未知值响亮抛；routing 透传 | 键被吞 |
| `ConfigurableProviderAuthKindTest` | BEARER＋空 baseUrl ⇒ 车道收到 BEARER | 五参重建归一 |

**变异探针（RE，实现后逐个跑）**：

1. `:1632` 判据放宽成 `isOpenRouter`（含 baseUrl）⇒「中转站不发断点」对照夹具恰红；
2. `openRouterRouting` compact 归一 null→`Map.of()` ⇒「null 不发键」夹具恰红（R6 的牙）；
3. routing 落线改 `JsonValue.from(map)` 不经树 ⇒ 含 null 值的 escape-hatch 夹具恰红（A-09 R11 复验）;
4. 删「串 content ⇒ 数组改写」支 ⇒ 串形态末条消息断点夹具恰红；
5. 三断点顺序里把工具断点挂到**首**项 ⇒ 末项夹具恰红；
6. resolver 撤回精确匹配先行 ⇒ `:batch` 夹具恰红；
7. `effectiveOptions` 改回五参 ⇒ authKind 夹具恰红；
8. 派发注入挪到 `createApi` 之后（或删掉）⇒ anthropic 车道模型走错车道，`OpenRouterProviderTest` 双车道夹具恰红。

## 8. 提交计划（8 步，每步独立编译＋夹具）

| # | 提交 | 内容 |
|---|---|---|
| 1 | `docs(ai): A-02 design`（本文档） | 设计稿落地待审 |
| 2 | `feat(ai): carry the per-model api on ModelInfo` | §4.1＋§4.2（含 `Protocol.fromWire`、ModelsJsonProvider 多协议）＋夹具 |
| 3 | `feat(ai): register the OpenRouter chat provider` | §4.3＋§4.4＋§4.5（authKind 修复随行，G7 在 pin 路径上）＋夹具 |
| 4 | `feat(coding-agent): dispatch the lane by model api` | §4.6＋§4.7＋夹具 |
| 5 | `feat(ai): detect the completions cache-control format` | §4.8＋§4.11＋夹具（探测/解析/models.json 三层） |
| 6 | `feat(ai): apply anthropic cache control on the completions lane` | §4.9（通道＋post-pass＋prompt_cache_retention）＋夹具（B105 闭环） |
| 7 | `feat(ai): send the openrouter routing preferences` | §4.10＋escape-hatch 夹具＋B133 可达性收口夹具 |
| 8 | `docs: close out A-02` | 本文档 §12＋`docs/32`（B105/B133 结案、B134–B138 新登）＋`docs/41:55` 划行＋`docs/48` banner/表行＋`docs/53 §4.4` 回执＋记忆 |

## 9. 行数预算与存量超限

新增：`OpenRouterModels` ~330、`OpenRouterProvider` ~55、`CacheControlFormat` ~30、
`CompletionsCacheControl` ~170；修改合计 ~+250（ModelInfo/ModelsJsonConfig/ModelsJsonSchema/CompatResolver/
ModelCompat/Converter/Api/DefaultProviders/DefaultModelResolver/ProviderCatalog/CatalogCompatRules）。
存量超限增量：`OpenAICompletionsMessageConverter` 513→~545（缓存主体已外置新文件；与
`ResponsesMessageConverter` 578、`AgentSession` 988 同列登记）；`ModelCompat` 499→~560（＋2 组件
＋便捷构造链，javadoc 从简以控行数）。

## 10. 登记终态（编号以收口时 `docs/32` 实际为准）

| 号 | 内容 | 处置 |
|---|---|---|
| B105 | completions `cacheControlFormat` | **结案**（§4.8/§4.9） |
| B133 | openrouter 思考形状不可达 | **结案**（§4.3/§4.4 起生产可达＋夹具） |
| `docs/53 §4.4` openRouterRouting→A-02 | — | **结案回执** |
| B134 | completions 的 sessionId 消费面（`prompt_cache_key`＋亲和头 `:770-778`）| **新登**（并 B103 家族；通道建成前结构性不可达） |
| B135 | 错误 `metadata.raw` 追加（`:714-720`） | **新登**（错误归一化家族） |
| B136 | models.json per-model `baseUrl` 被静默吞；`supportsMidConvoEffort` 不携带 | **新登** |
| B137 | resolver 的 glob/scope 面 | **新登** |
| B138 | `effectiveOptions`/`ModelsJsonProvider` 丢 `authKind` | **新登＋本包已修**（§4.5） |
