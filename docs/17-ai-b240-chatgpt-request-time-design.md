# 批 8 设计：B240 — ChatGPT 订阅 token 的请求时特判

- 日期：2026-10-16
- 锚点：pi `ea448f454`（v1.1.0-22），取证全部走 `git show <sha>:<path>`
- 条目：B240（`docs/04` 在册，批 7 取证登记；`docs/06` §1 存活 11 条之一）
- 模块：`pi-java-ai`（openai-responses 适配器单元）
- 规模：S（一个分类器 ＋ 四道字段门 ＋ 夹具）

## 1. B240 的三臂与现状盘点

台账原文列了三件事，按锚点逐一取证，**只有一臂是实缺**：

| 臂 | pi 锚点 | Java 现状 | 本批动作 |
|---|---|---|---|
| ① 请求字段裁剪 | `api/openai-responses.ts:40-46`（`isChatGPTSignIn`）、`:329`、`:335-336`、`:340-345` | **缺**：字段不区分凭证一律发 | **实施** |
| ② 共享用量错误码重试 | `utils/retry.ts:25`（非重试表）、`:100-101`（重试表） | **已在**：`RetryableError.java:59`、`:142-143` 三码齐，`RetryableErrorTest` 有同族反向裁决夹具（`sharingFamilySplitsBetweenTheTwoTables`） | 仅销号确认，零代码 |
| ③ `filterAllModels` 去 classifier | `providers/openai.ts:28-29` | **真空**：Java 没有 classifier 模型类型（无 `ModelType`，内置目录只拍平 `type:"chat"`，`BuiltinModelCatalogs` javadoc 明示 image/classifier 是 R5 面；models.json schema 无 type 字段，无 openai-decisions 协议） | 不造投机骨架，登记为 R5 classifier 落地时的随附项 |

另有批 7 已落地的第四件：错误文案追加用量链接（pi `openai-responses.ts:34`、`:226-228`）
⇒ `OpenAIResponsesApi.java:111-113` 已在，本批不动。

## 2. pi 逐字取证（臂①）

`packages/ai/src/api/openai-responses.ts`：

```ts
// :32-34
// OpenAI Responses rejects max_output_tokens below 16: https://github.com/earendil-works/pi/issues/6265
const OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16;
const CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage";

// :37-46
/**
 * OpenAI API keys start with `sk-`; a different credential sent directly to OpenAI
 * is a Sign in with ChatGPT access token.
 */
function isChatGPTSignIn(model: Model<"openai-responses">, apiKey: string | undefined): boolean {
	return (
		model.provider === "openai" &&
		model.baseUrl === "https://api.openai.com/v1" &&
		apiKey !== undefined &&
		!apiKey.startsWith("sk-")
	);
}
```

`buildParams` 内（`:329-345`）：

```ts
	// Sign in with ChatGPT rejects these request fields.
	const omitUnsupportedFields = isChatGPTSignIn(model, options?.apiKey);
	const params: ResponseCreateParamsStreaming = {
		model: model.id,
		input: messages,
		stream: true,
		prompt_cache_key: cacheRetention === "none" ? undefined : clampOpenAIPromptCacheKey(options?.sessionId),
		prompt_cache_retention: omitUnsupportedFields ? undefined : getPromptCacheRetention(compat, cacheRetention),
		prompt_cache_options: omitUnsupportedFields ? undefined : getPromptCacheOptions(compat, cacheRetention),
		store: false,
	};

	if (options?.maxTokens && compat.supportsMaxOutputTokens && !omitUnsupportedFields) {
		params.max_output_tokens = Math.max(options.maxTokens, OPENAI_RESPONSES_MIN_OUTPUT_TOKENS);
	}

	if (options?.temperature !== undefined && !omitUnsupportedFields) {
		params.temperature = options?.temperature;
	}
```

事实裁决（与台账行文字面有一处出入，以锚点为准）：

1. **裁剪集合恰好四个键**：`prompt_cache_retention`、`prompt_cache_options`、`max_output_tokens`、`temperature`。
2. **`prompt_cache_key` 不裁**（`:334` 无门）——台账写的「裁掉 `prompt_cache_key`」不成立；订阅 token 照样发 key（只是 retention/options 不发）。
3. 同样**不裁**：`store`、`service_tier`（`:347-349`）、`tools`/`tool_choice`、整块 `reasoning`/`include`（`:353-360`）。
4. `resolveSamplingParams` 的 `Object.assign(params, samplingParams)`（`:363-366`）在门**之后**且无门——models.json 模型级 samplingParams 理论上能把 temperature 之类再写回去。这是 pi 的事实行为，照抄不「改进」。
5. 分类器判据三与：**provider 严格等于 `"openai"`** ＋ **model.baseUrl 严格等于官方 URL** ＋ **key 存在且非 `sk-` 前缀**。不做 trim/小写化。
6. azure 副本（`api/azure-openai-responses.ts`）grep `isChatGPTSignIn`/`omitUnsupported` **零命中**——门只在 openai 车道，不泄漏到共享转换器的另一条车道（与 `max_output_tokens` 门同一形态的先例）。
7. 传给分类器的是 **`options?.apiKey` 原文**（buildParams 的入参），不是 `getClientApiKey` 解析出的 `"unused"` 占位。生产链路上 env `OPENAI_API_KEY` 由上游鉴权解析（`envApiKeyAuth`）在进 stream 前变成 options.apiKey。

## 3. Java 现状与映射

- 车道每请求新建（`DefaultProviders.streamBlocking` → `provider.createApi(...)`，见 `AnthropicMessagesApi.java:131-135` 的同形登记），所以构造期判一次 == pi 每次 stream 判一次。
- ChatGPT 订阅 token 经批 7 链路到达：`StoredOAuthCredentials.resolveEffective` → `Credentials.resolveCredential`（`"openai"` ⇒ `AuthKind.BEARER`，`Credentials.java:130-132`）→ `ApiOptions.apiKey()`（OpenAI SDK 恒以 `Authorization: Bearer` 发，无需车道特判头）。
- `OpenAIResponsesApi` 构造器已解析 `apiKey`（当前是局部变量）、已存有效 `baseUrl`（空白回落官方 URL，`:50-51`）；`request.modelId().provider()` 在 `streamInternal` 可用（转换器里已有同形调用）。
- 四个落点在 `ResponsesMessageConverter`：`max_output_tokens`（`:151-153`）、`temperature`（`:154-156`）、`applyCacheRetention`（`:195` 调，`:569-594` 实现——retention 与 options 两支，key 支在 `:577-579`）。
- 共享转换器 7 参形态只有两个生产调用方：`OpenAIResponsesApi.java:79`（本车道）与 `AzureOpenAIResponsesApi.java:80`（恒传 false）。5参便捷形态的调用方全是测试（4 处），其语义就是「不裁」，默认 false 即可不动。

### 3.1 窄差裁决：用解析后的 key 还是 options 原文？

pi 传 `options?.apiKey`；Java 构造器持有 `resolveAuth(options, envVar)` 的**解析值**（options 优先，env 回落）。

生产路径两者等价（env 由 `Credentials` 上游解析进 options，同 pi 的 envApiKeyAuth）。仅「直造 SDK、options 空、env 里放非 `sk-` token」这一测试/旁路形状分叉：用解析值判 true，用原文判 false。pi 在同形状下（env 值上游进 options）判 true ⇒ **取解析值**，并在代码注释登记。解析值不可能为 null/blank（`resolveAuth` 取不到即抛），分类器对 null/blank 仍写防御性 false。

## 4. 改动点（逐字级）

### 4.1 `OpenAIResponsesApi.java`

```java
/** pi openai-responses.ts:42：isChatGPTSignIn 的官方 baseUrl 严格相等值。 */
private static final String OPENAI_OFFICIAL_BASE_URL = "https://api.openai.com/v1";
```

构造器：默认 URL 改用常量（`:50-51`），解析值落字段：

```java
String baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
    ? options.baseUrl() : OPENAI_OFFICIAL_BASE_URL;
...
String apiKey = resolveApiKey(options, apiKeyEnvVar);
...
this.apiKey = apiKey;
```

新增字段与包可见静态分类器（同包测试直接达，不走桩 HTTP——桩 baseUrl 必然不是官方 URL，端到端 wire 测无法构造真分支）：

```java
/** 构造期解析的凭证值（分类器用；见 isChatGptSignIn 的裁决注释）。 */
private final String apiKey;

/**
 * pi {@code openai-responses.ts:40-46} {@code isChatGPTSignIn}：provider 为 openai、
 * 走官方 baseUrl、凭证不是 {@code sk-} 前缀 ⇒ Sign in with ChatGPT 订阅 token，
 * Responses API 会拒绝四个字段（见 ResponsesMessageConverter 的 omit 门）。
 * 严格相等，不做 trim/大小写归一（pi 逐字）。key 取构造期解析值：生产链路上 env 已由
 * Credentials 上游解析进 options（同 pi envApiKeyAuth），与 pi 的 options?.apiKey 等价。
 */
static boolean isChatGptSignIn(String provider, String baseUrl, String apiKey) {
    return "openai".equals(provider)
        && OPENAI_OFFICIAL_BASE_URL.equals(baseUrl)
        && apiKey != null && !apiKey.isBlank()
        && !apiKey.startsWith("sk-");
}
```

`streamInternal` 在 buildParams 调用前算一次并传入（xai 等复用本适配器的车道 provider/baseUrl 皆不命中）：

```java
// B240（pi openai-responses.ts:329）：订阅 token 裁四个 Responses 拒收字段。
boolean omitUnsupportedFields =
    isChatGptSignIn(request.modelId().provider(), baseUrl, apiKey);
```

### 4.2 `ResponsesMessageConverter.java`

7 参 `buildParams` 加第 8 参 `boolean omitUnsupportedFields`（5 参便捷形态委托传 `false`）；javadoc 补一行。

三个落点：

```java
// pi :340 —— 门在 supportsMaxOutputTokens 之外再叠一道（顺序按 pi）。
if (request.maxTokens() > 0 && sendsMaxOutputTokens(apiName, compat) && !omitUnsupportedFields) { ... }
// pi :344
if (request.temperature() >= 0 && !omitUnsupportedFields) { ... }
...
applyCacheRetention(builder, ropts, compat, omitUnsupportedFields);
```

```java
private static void applyCacheRetention(ResponseCreateParams.Builder builder,
                                        ResponsesOptions ropts, ModelCompat compat,
                                        boolean omitUnsupportedFields) {
    ...
    // pi :334：prompt_cache_key 无门，订阅 token 照发。
    if (retention != NONE && ropts.sessionId() != null) { builder.promptCacheKey(...); }
    // pi :335-336：retention/options 两支整体在门后。
    if (!omitUnsupportedFields) {
        if (retention == LONG && longOk && !explicit) { builder.promptCacheRetention(...); }
        if (explicit) { ...原两支... }
    }
}
```

显式模式（`supportsExplicitPromptCacheMode`）内置目录无 openai 模型为真，但门仍按 pi 罩住整个 options 支。

`SamplingParamsWriter.applyToResponses`（`:202`）**有意不加门**，注释引 pi `:363-366`。

### 4.3 `AzureOpenAIResponsesApi.java:80`

7 参调用补实参 `false`（azure 副本无此门，§2 裁决 6）。

## 5. 测试设计（新文件 `ResponsesChatGptSignInWireTest`）

两层，均为既有夹具同形：

**A. 分类器真值表**（直调包可见静态，6 条）：

1. openai ＋ 官方 URL ＋ `"chatgpt-access-token-abc"` ⇒ true
2. 同上前缀 `"sk-..."` ⇒ false
3. key 为 null/空串 ⇒ false
4. provider `"xai"`（其余同真）⇒ false
5. baseUrl 自定义网关（其余同真）⇒ false
6. 大小写/尾斜杠不归一：`"https://api.openai.com/v1/"` ⇒ false（钉严格相等）

**B. buildParams 门**（直调 8 参，`ObjectMappers.jsonMapper().writeValueAsString(params)` 读真出站 JSON，
long retention ＋ sessionId ＋ maxTokens=3000 ＋ temperature=0.7）：

- omit=true：`prompt_cache_key` **在**；`prompt_cache_retention`/`prompt_cache_options` 缺席；
  `max_output_tokens`/`temperature` 缺席；`model`/`store`/`input` 照常。
- omit=false 对照组：retention=`"24h"`、`max_output_tokens=3000`、`temperature=0.7` 全在。
- 显式 cache 模式 compat ＋ omit=true：options 同样缺席（门罩住整个 explicit 支）。

既有 4 个 5 参直调测试与全部 wire 测试默认 false，行为不变。

## 6. 不做 / 出范围

- `filterAllModels`（臂③）——R5 classifier 模型面落地时随附；本批不登记新 B 号（在册 B240 文本内标注「真空、随 R5」即可）。
- `prompt_cache_key` 不裁（锚点事实，纠正台账措辞）。
- models.json samplingParams 回写四字段的「漏洞」不修——pi 同样不门。
- 不碰批 7 已在的 usage 链接文案、`utils/retry.ts` 三码（已在且已测）。

## 7. 闭环清单

1. `mvn -pl pi-java-ai -am test`（显式 `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25`），本批仅 ai 模块内改动，不触下游；收尾本模块 `checkstyle:check`。
2. 销号 B240：回填 `docs/04`（~~B240~~ 销号行，三臂分别注明）、`docs/06` §1 表与 §1 抬头计数、`docs/08` 批次表加「批 8」行；加权按批 6 先例**不动**（台账行为项，不占地图单元）；map/02 若有 B240 字样同步。
3. commit 节奏：设计稿 `docs(ai): batch-8 design ...` → 实现 `feat(ai): ... (B240)` → 闭环 `docs(ai): batch-8 closure ...`；显式路径 `git add`，trailer Co-Authored-By。
