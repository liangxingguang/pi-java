# 61 - B103 设计：`sessionId` 通道与会话亲和头族

**状态：✅ 已闭环（2026-09-30，用户裁决方案 2：接受非标准头不可达，body 侧落）**
> ⚠️ 实施期方向经多轮实测重定：**升级 SDK 2.52→2.66（零兼容破坏）**，但官方 Java SDK 的
> streaming transport 不向用户透传任意头（B140）⇒ Anthropic/completions 的非标准亲和头
> 登记为不可达差异；请求体 `prompt_cache_key` 照落，responses 车道头 SDK 透传。
**创建基准：** pi-java `779b644`（A-14 收尾）
**台账：** [`32` B103](32-open-items-register.md)（含并入的 **B134**：completions 的 `prompt_cache_key`/亲和头消费面）
**前置包：** A-01（`cacheRetention` 选项通道，`docs/54`）、A-14（车道每请求新建，`docs/60`）

---

## 1. pi 事实清单（逐字锚点）

### 1.1 类型

`packages/ai/src/types.ts`

```ts
// :123
export type SessionAffinityFormat = "openai" | "openai-nosession" | "openrouter";

// :186-217（StreamOptions）
cacheRetention?: CacheRetention;
/** Optional session identifier for providers that support session-based caching. … */
sessionId?: string;
```

三个车道各自的 compat 字段：

| 车道 | compat 字段（types.ts） | 缺省 |
|------|------------------------|------|
| anthropic-messages | `sendSessionAffinityHeaders?: boolean`（:789-798）；`sessionAffinityFormat?: "openrouter"` | send **false**；format 未设 |
| openai-completions | `sendSessionAffinityHeaders?: boolean`、`sessionAffinityFormat?: SessionAffinityFormat`（:736-743） | send **false**（openrouter 端点 true）；format openrouter 端点 `"openrouter"` 否则 `"openai"` |
| openai-responses | 仅 `sessionAffinityFormat?: SessionAffinityFormat`（:759-760），**无 send 开关** | format openrouter 端点 `"openrouter"` 否则 `"openai"` |

### 1.2 `cacheRetention === "none"` 的门

三条车道同形。Anthropic（`api/anthropic-messages.ts:563-573`）：

```ts
const cacheRetention = resolveCacheRetention(options?.cacheRetention, options?.env);
const cacheSessionId = cacheRetention === "none" ? undefined : options?.sessionId;
const created = createClient(model, apiKey, options?.headers, options?.fetch,
    copilotDynamicHeaders, cacheSessionId);
```

- completions：`api/openai-completions.ts:342-351`（同形，`cacheSessionId` 进 `createClient`）
- responses：`api/openai-responses.ts:144-145`（同形）

⇒ **头只挂在解析后的保留期上**；`none` 时即使 `options.sessionId` 有值也不发头。

### 1.3 头的落点

**Anthropic**（`api/anthropic-messages.ts:963-978`）：

```ts
const compat = getAnthropicCompat(model);
const sessionAffinityHeaders: ProviderHeaders = {};
if (sessionId && compat.sendSessionAffinityHeaders) {
    const header = compat.sessionAffinityFormat === "openrouter"
        ? "x-session-id" : "x-session-affinity";
    sessionAffinityHeaders[header] = sessionId;
}
```

- openrouter format ⇒ `x-session-id: <id>`；未设 ⇒ `x-session-affinity: <id>`。值原文不截断。
- ⚠️ `getAnthropicCompat` 的 send 缺省是 `model.compat?.sendSessionAffinityHeaders ?? isOpenRouter`（`:206-220`）——**构造期按 baseUrl/provider 探测 openrouter**。
- OAuth/github-copilot 提前 return 分支（`:919-961`）**不构造**亲和头。

**Completions**（`api/openai-completions.ts:766-778`）：

```ts
if (sessionId && compat.sendSessionAffinityHeaders) {
    if (compat.sessionAffinityFormat === "openrouter") {
        headers["x-session-id"] = sessionId;
    } else {
        if (compat.sessionAffinityFormat === "openai") {
            headers.session_id = sessionId;
        }
        headers["x-client-request-id"] = sessionId;
        headers["x-session-affinity"] = sessionId;
    }
}
```

| format | 发出的头 |
|--------|---------|
| `openrouter` | `x-session-id` |
| `openai` | `session_id` + `x-client-request-id` + `x-session-affinity` |
| `openai-nosession` | `x-client-request-id` + `x-session-affinity` |

**Responses**（`api/openai-responses.ts:258-267`）：无 send 开关，`sessionId` 非空即发；openrouter ⇒ `x-session-id`；否则 `openai` 时带 `session_id`，**恒发** `x-client-request-id`（**不发** `x-session-affinity`）。

### 1.4 请求体消费面（B134）

`api/openai-prompt-cache.ts:1-9`（全文）：

```ts
export const OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64;
export function clampOpenAIPromptCacheKey(key: string | undefined): string | undefined {
    if (key === undefined) return undefined;
    const chars = Array.from(key);
    if (chars.length <= OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) return key;
    return chars.slice(0, OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH).join("");
}
```

按 Unicode **码点**（非 UTF-16）截前 64。

- completions（`api/openai-completions.ts:816-826`）：

```ts
prompt_cache_key:
    (model.baseUrl.includes("api.openai.com") && cacheRetention !== "none") ||
    (cacheRetention === "long" && compat.supportsLongCacheRetention)
        ? clampOpenAIPromptCacheKey(options?.sessionId)
        : undefined,
```

⚠️ 这里读的是**原始** `options?.sessionId`（不经 `cacheSessionId`、不受 `sendSessionAffinityHeaders` 控制），门只看保留期与端点。

- responses（`:315`）：`prompt_cache_key: cacheRetention === "none" ? undefined : clampOpenAIPromptCacheKey(options?.sessionId)`。

### 1.5 sessionId 的生产者

- 值＝**会话 id**：`coding-agent/src/core/session-manager.ts:230-232` `createSessionId() = uuidv7()`；`--session-id` 可显式指定（`assertValidSessionId` :234-240）；加载历史会话取文件头 `header.id`（`:999-1001`）；fork 生成新 id（`:1700`）。
- 转发链：`coding-agent/src/core/sdk.ts:368-390` `new Agent({ …, sessionId: sessionManager.getSessionId() })` → `agent/src/agent.ts:248` 存成员、`:460-467` 放进 loop config 的 options。
- ⚠️ **压缩/摘要请求显式不带会话 id**：`coding-agent/src/core/agent-session.ts:2065` 调用处 `undefined, // sessionId`；pi 那处用独立 routing id（`… ?? uuidv7()`），**不是**主会话 id。

### 1.6 catalog 实测取值

`packages/ai/src/providers/data/*.json`：

- `"sendSessionAffinityHeaders":true` 全目录 **469** 处（openrouter 378、cloudflare-ai-gateway 30、fireworks 22、baseten 21、cloudflare-workers-ai 18），无显式 false。
- `"sessionAffinityFormat":"openai-nosession"` 全目录 **35** 处（opencode 29、opencode-go 6），无其他显式 format。
- 其余 provider（含 anthropic/openai 官方端点）均不标注 ⇒ 走缺省（不发头）。

### 1.7 本包**不做**的消费面

下列车道在 pi 也消费 sessionId，但按 [[docs41-active-plan]]「只做主流可达」与 B98「不投机加」原则，本包不移植、登记保留：

- Mistral `x-affinity` 头 + body `promptCacheKey`（`mistral-conversations.ts:336-353/:527`）；
- Azure responses `prompt_cache_key`（`azure-openai-responses.ts:301-307`）；
- OpenAI Codex responses 的 `session-id`/`x-client-request-id` + WebSocket 复用键（`openai-codex-responses.ts:1642/:561`）；
- OpenCode 专用头 `x-opencode-session`（`providers/opencode-headers.ts`）；
- pi-messages envelope 的 `sessionId`、faux 缓存仿真。

（如需对齐另立包；Google/Bedrock 在 pi 侧本就不消费。）

---

## 2. pi-java 现状差

| 面 | 现状 | 缺口 |
|----|------|------|
| `harness.StreamOptions` | 4 组件：`maxTokens/temperature/reasoning/cacheRetention`（`StreamOptions.java:36-41`），javadoc 明写 sessionId「本记录不带」 | **无 sessionId 通道** |
| 宿主注入 | `DefaultProviders.cacheExtra(options)` 只把 `cacheRetention` 放进 `ApiOptions.extra`（`DefaultProviders.java:120-125`）；`streamFnFor(args, …)` 签名无会话 id（`:91`） | sessionId 无生产者 |
| 会话 id 可得性 | `AgentSession.create` 处 `args.sessionId()` 已可用（`AgentSession.java:276`） | 只差接线 |
| Anthropic 头 | 构造期 `AnthropicOkHttpClient.builder().putHeader(...)` 仅 OAuth 两枚（`AnthropicMessagesApi.java:78-95`） | 缺亲和头 |
| Completions 头 | SDK 客户端构造无额外头；converter 明记「`prompt_cache_key` 不移植（B134）」（`OpenAICompletionsMessageConverter.java:227-228`） | 缺头＋body 键 |
| Responses 头/体 | `OpenAIResponsesApi`/`ResponsesMessageConverter` 无 sessionId 消费 | 缺头＋body 键 |
| compat 字段 | `ModelCompat` 无两个亲和字段（`ModelCompat.java:268-292`）；`ModelsJsonSchema` 无键 | 两字段从解析到线格全缺 |

**好条件**：车道每请求新建（A-14 已证），构造期读 `ApiOptions.extra` 与 pi 的「每次 stream 读一次」等价，无需新生命周期。

---

## 3. 改动方案

### 3.1 通道：`StreamOptions` 加 `sessionId`

`harness/StreamOptions.java`：

```java
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    Optional<ThinkingLevel> reasoning,
    Optional<CacheRetention> cacheRetention,
    Optional<String> sessionId          // 新增
) {
    // 保留既有三参、四参便捷构造（全部以 Optional.empty() 落 sessionId）⇒ 现有构造点零改签
}
```

javadoc 注明：空 ≙ pi 的 `undefined`；**压缩/摘要路径保持空**（对齐 §1.5）。
三个生产构造点（`PiLoopRunner:224`、`StreamSimple:76`、`LlmSummaryGenerator:227`）**不改**——它们维持空，sessionId 由宿主在更下游注入（见 3.2），与 pi「loop options 里带、压缩处显式 undefined」的净效果一致。

### 3.2 生产者：宿主把会话 id 注入每请求 extra

`DefaultProviders.streamFnFor` 增加 `String sessionId` 形参；`cacheExtra` 扩为同时输出两键：

```java
static Map<String, Object> requestExtra(harness.StreamOptions options, String sessionId) {
    var extra = new LinkedHashMap<String, Object>();
    options.cacheRetention().ifPresent(r -> extra.put("cacheRetention", r.wireName()));
    // ⚠️ 空/blank 不塞：键缺席 ≙ pi undefined，别塞默认值（同 cacheRetention 口径）。
    if (sessionId != null && !sessionId.isBlank()) {
        extra.put("sessionId", sessionId);
    }
    return extra;
}
```

`AgentSession.java:272` 调用点传入 `args.sessionId()`（保留旧 `cacheExtra` 签名或直接替换，`DefaultProvidersTest` 同步）。

### 3.3 compat：`ModelCompat` 加两字段

```java
Boolean sendSessionAffinityHeaders,           // null ≙ 车道探测缺省（三态）
SessionAffinityFormat sessionAffinityFormat   // enum；null ≙ 探测/openai
```

- 新建 `ai/catalog/SessionAffinityFormat.java`：`enum { OPENAI("openai"), OPENAI_NOSESSION("openai-nosession"), OPENROUTER("openrouter") }`，`@JsonValue` 线格名 + `parse(String)` 三态（未知 ⇒ empty）。
- 缺省探测（对齐 §1.1/§1.3）：以 `provider == "openrouter" || baseUrl.contains("openrouter.ai")` 为唯一判据，放进各车道构造期：
  - anthropic：`send ?? isOpenRouter`、`format ?? (isOpenRouter ? OPENROUTER : null)`；
  - completions：`send ?? isOpenRouter`、`format ?? (isOpenRouter ? OPENROUTER : OPENAI)`；
  - responses（无 send）：`format ?? (isOpenRouter ? OPENROUTER : OPENAI)`。
- 接线：`CompatResolver`（含 forResponses 形参链）、`ModelsJsonSchema`（两键，snake 原名）、`ModelsJsonConfig`、`ResponsesOptions` 同族读面。**内置目录一个常量都不加**（§1.6：写值的全是生成数据，随 models.json 可达；本仓内置 provider 表无对应数据文件）。

### 3.4 头落点

新增小类 `ai/protocol/SessionAffinityHeaders.java`（纯函数，exhaustive switch）：按车道＋解析后 format 产出 `Map<String,String>`。

- **Anthropic**：构造期算 `resolvedRetention`（复用 `CompatResolver.resolveCacheRetention(cacheRetention, env)`），`retention == NONE` ⇒ 不发；否则读 `extra["sessionId"]`，经 compat 门后用 builder `putHeader`。OAuth 分支照旧不挂（提前 return 路径天然不经过）。
- **Completions**：SDK 客户端需逐请求带额外头——给 `OpenAIOkHttpClient.builder()` 加 `.defaultHeaders(...)`（或 SDK 等价的 per-request header 选项，实施时按 SDK 实测确认落点；若 SDK 不支持 default header 则在 `createStreaming` 请求项上挂）。门同 §1.2/§1.3。
- **Responses**：无 send 门；头形状少 `x-session-affinity`（§1.3）。

### 3.5 请求体（B134）

新建 `ai/protocol/PromptCacheKeys`：放 `MAX = 64` 与 `clamp(String)`（**码点**截断：`string.codePoints().limit(64)` 收集）。

- completions converter：门＝`(baseUrl 含 api.openai.com 且 retention != NONE) 或 (retention == LONG 且 supportsLongCacheRetention)` ⇒ 落 `prompt_cache_key`，值 clamp(**原始 sessionId**，即未经 none 门的 `extra` 原值)。
- responses converter：门＝`retention != NONE` ⇒ 落 `prompt_cache_key`。

---

## 4. 测试计划（先红）

新增（RecordingHttpServer / wire 口径）：

1. `SessionAffinityHeadersTest`：三车道 × 三 format 的头名/头集 exhaustive；值原文不截断；`none` ⇒ 空（completions/anthropic）；responses 无 `x-session-affinity`。
2. wire（RecordingHttpServer）：
   - Anthropic openrouter 模型 ⇒ 只 `x-session-id`；fireworks 形（send:true, format 缺省）⇒ 只 `x-session-affinity`；`cacheRetention:none` ⇒ 两个头都无。
   - Completions `openai`/`openai-nosession`/`openrouter` 三形态头集；openai 官方端点＋short ⇒ body 带 `prompt_cache_key`；`none` ⇒ 无头无 key（官方端点的 `prompt_cache_key` 亦无）。
   - Responses 三形态头 + `prompt_cache_key` 门。
3. `PromptCacheKeysTest`：64 码点边界、多码点字符（emoji，按码点不按 char）、null/空透传。
4. 通道：`DefaultProvidersTest`——会话 id 经 extra 落到车道；blank 不塞键；`requestExtra` 与 cacheRetention 共存。
5. compat：`sendSessionAffinityHeaders:true`/`sessionAffinityFormat:"openai-nosession"` 经 models.json 显式值赢过探测。

Mutation probes（实施后）：
- M1 头门去掉 `retention != NONE` ⇒ 恰 wire「none」组红；
- M2 format switch 合并 openai/openai-nosession ⇒ 恰 nosession 头集红；
- M3 clamp 改按 `char`/限长改错 ⇒ 恰码点用例红；
- M4 completions 的 `prompt_cache_key` 改读 cacheSessionId ⇒ 设计上等价（零红 ⇒ 登记为语义等价，参照第五种成因）；
- M5 宿主 sessionId 恒不注入 ⇒ 恰 DefaultProviders/wire 组红。

---

## 5. 不做与遗留

- §1.7 列出的 Mistral/Azure/Codex/OpenCode/pi-messages/faux 消费面本包不做（如需另立包）。
- 压缩/摘要请求 pi 用的**独立 routing id**（`?? uuidv7()`）本包不补：java 摘要路径 `cacheRetention:none` 且 sessionId 空，净效果＝无头无 key，行为与 pi 该请求的外显一致；routing id 本体无生产可观测消费（Mistral 不做 ⇒ 无落点）。
- 预计新登记：实施后按实际探针/偏差补 `docs/32`（B140 起），并回填本文 §12 与 `docs/48` 三处（banner、概览表、§5 任务行）。

---

## 12. 实施记录（2026-09-30）

**裁决演变**：用户先批准「绕 SDK 手写 transport」，后要求评估升级成本。实测后最终裁决
**方案 2（接受差异）+ 保留 SDK 2.66**。

**关键实测事实**：

1. SDK 升级 2.52→2.66：编译/全部既有测试零改动（向后兼容），但**头问题在 2.66 完全不变**。
2. 官方 Java SDK streaming transport 上四个「改请求」口子全部封闭：
   client default `putHeader`（剥非标准头）、params `putAdditionalHeader`（不透传）、
   自定义 transport `Interceptor`（sync/async execute 都不被调用）、
   `Backend.prepareRequest`（不被调用）。
3. 根因＝Java SDK streaming transport 与 pi 的 JS SDK 开放 fetch/defaultHeaders 不对等，
   非版本旧、非功能复杂。登记 **B140**。

**落地范围**：

- `harness.StreamOptions` 加 `sessionId` 组件（旧构造降级）；宿主 `DefaultProviders.requestExtra`
  经 `ApiOptions.extra` 注入会话 id，`AgentSession` 用 `args.sessionId()`。
- **体侧**：completions/responses 的 `prompt_cache_key`（`PromptCacheKeys` 码点截 64）照落。
- **头侧**：responses 车道头 SDK 透传（`SessionAffinityHeaders` 只保留 responses）；
  Anthropic/completions 非标准头不发（wire 夹具反向断言）。

**验证**：新夹具 13/13；ai 全模块 **1195/1195** BUILD SUCCESS。

**清理**：删除调研期临时类 `AffinityBackend`/`AffinityHeaderInterceptor`；无任何 println 残留。
