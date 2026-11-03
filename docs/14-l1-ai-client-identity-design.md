# 14 - L1 AI ClientIdentity 对齐设计（B266 / #105）

> 【背景摘要】这是 L1（ai 模块）在固定锚点 `56cae7823` 下的第五个实施包设计稿。docs/13（B263 请求遥测）已闭环。本稿接手 B266：把 pi 的 `ClientIdentity`（tri-state merge、`User-Agent` 三种模式、OpenRouter／NVIDIA／OpenCode 归因头、OpenCode session 头）与上层 identity 输入（agent-core／coding-agent）移植到 pi-java，并退役旧锚点遗留的 `ProviderAttribution`。
>
> **文档状态：用户已授权设计落地后直接实施（2026-11-03），无需等待审核。实施与闭环记录见 §10。**

## 0. 范围、锚点与基线

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 目标锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec` |
| pi 证据路径 | 固定提交的 `packages/ai/`、`packages/agent/`、`packages/coding-agent/`；只用 `git show 56cae7823:<path>` 取证 |
| pi-java 实施 HEAD | `5a4f7e3d`（docs/13 闭环提交） |
| 可执行基线 | docs/13 终态：`mvn clean verify` 实测 **5,937 测试／0 失败／0 错误／29 跳过**；Checkstyle 0；SpotBugs 0 |
| 本包终态 | `mvn clean verify` 实测 **5,958 测试／0 失败／0 错误／29 跳过**；Checkstyle 0；SpotBugs 0 |
| 新文档号 | 本稿占 `docs/14`；下一新文档号为 `docs/15` |

> 行号口径：`AI/` 表示 `pi-java-ai/src/main/java/com/pijava/ai/`；其他模块写完整路径。Java 行号以 `5a4f7e3d` 工作树为准。

### 0.2 为什么先做 B266

【场景】L1 剩余 B266（ClientIdentity）、B267（混合包）。B267 必须先拆；B266 是单面（请求身份），且新锚点已把它从旧锚点的 `provider-attribution.ts` 收敛成 AI 包的一等公民 `identity.ts`。旧锚点遗留的 coding-agent `ProviderAttribution` 与新锚点语义冲突，先做 B266 才能让 B267 的 provider wire／usage 面建立在正确的请求选项上。

【决定】docs/14 只接 B266（地图 #105）。

【风险/代价】[内部，模型不可见] 本包会改动 agent-core 的 `StreamOptions`／`HarnessConfig` 形状（加 identity 输入）。L3 尚未重测，但这些改动是 L3 将来使用 AI 身份面的必要输入，且默认值 null 保持零行为变化。

### 0.3 本包计分单元

| 地图行 | 能力 | pi 证据 | Java 证据 | 当前判定 | 权重 | 目标 |
|---|---|---|---|---:|---:|---|
| #105 | Client Identity 与请求头 | `src/utils/identity.ts:4-193`；`src/models.ts:877-897` | `AI/api/ApiOptions.java:24-63` 无 identity；`AI/http/PiHttpClient.java:255` 固定 UA；`pi-java-coding-agent/.../ProviderAttribution.java:27-130` 旧锚点归因 | 缺失 | 3 | 对齐 |

账面影响（实施后复核，2026-11-03）：

| 口径 | 当前 | 闭环后 |
|---|---:|---:|
| 加权 | 196.0/240＝81.67% | **199.0/240＝82.92%** |
| 未加权 | 105.5/131＝80.5% | **106.5/131＝81.3%** |
| 对齐／存疑／缺失 | 98／15／18 | **99／15／17** |
| 权重 1 子集 | 46/57＝80.7% | 46/57＝80.7% |
| 新增单元域 | 6/16＝37.5% | **9/16＝56.3%** |

### 0.4 不在本包

- **B267**：provider wire、usage 字段（#53/#59/#107）、retry／overflow、OAuth／device／env、pi-ai CLI 权限、Faux 余量。
- **Models wrapper 的 Java 对应物**：Java 没有 pi 的 `Models`／`MutableModels` 门面（只有 `ProviderRegistry` 与 `DefaultProviders.streamFnFor`）。`withClientIdentity(models, identity)` 不新增类；身份在请求组装点合并。登记窄差 D204。
- **身份 JSON 持久化**：pi 的 `ClientIdentity` 是 plain JSON；Java 当前没有任何持久化消费者（settings 不存 identity）。本包类型保持 Jackson 友好，但不新增 codec；需要时由 L3 另立。
- **B116**：内部 usage 方言继续保留。
- 不新增 Maven 依赖；不改 `pi-java-telemetry` 契约。

### 0.5 术语

- **identity（客户端身份）**：一次请求向 provider 声明的调用方。三个字段：`userAgent`、`attribution`、`version`。
- **tri-state（三态）**：字段有三种状态——缺席（不参与合并）、显式 `null`（关闭此前合并得到的值）、有值。只有 `attribution` 需要三态。
- **detailed UA**：`pi/<version> (<platform>; <runtime>/<version>; <arch>)` 形状的详细 `User-Agent`；`short` 为 `pi`；custom 为给定字符串。
- **归因头（attribution headers）**：OpenRouter 的 `HTTP-Referer`／`X-OpenRouter-Title`／`X-OpenRouter-Categories`，NVIDIA NIM 的 `X-BILLING-INVOKE-ORIGIN`，OpenCode 的 `x-opencode-client`。
- **identity headers**：`getIdentityHeaders()` 的产物——`User-Agent` 在前，归因头随后。
- **Precedence（覆盖序）**：identity 头是**最低层**；`model.headers` 覆盖它，`options.headers` 再覆盖；`null` 删除同名低层头。
- **窄差（D 编号）**：已登记、无法或无需消除的形状差异；明细存于 `docs/map/`。

## 1. 请求主线与落点

### 1.1 pi 调用链

```text
Models.stream / streamSimple / streamDeferred          （models.ts:901-975）
  └─ applyAuth(model, options)                          （models.ts:856-898）
       ├─ headers = merge(sessionHeaders, auth.headers, options.headers)
       ├─ identity = merge(models.identity, options.identity)
       └─ requestOptions = { ...options, identity }
  └─ provider.stream(requestModel, transcript, requestOptions)
       └─ lane createClient(model, ..., options.identity)
            └─ getIdentityHeaders(model, identity)
                 └─ { User-Agent, ...attributionHeaders }
            └─ merge(identity, {defaults}, model.headers, options.headers)
agent-loop → streamFunction(model, ctx, { identity: merge(DEFAULT_AGENT_IDENTITY, config.identity) })
coding-agent sdk → buildRequestOptions(options) → identity = getClientIdentity(settings)  （覆盖 agent 默认）
```

**主线**是「请求选项 → Models.applyAuth 合并 → 车道 `createClient` 组装头表」。**支线**是 agent-loop／coding-agent 的身份来源；本包只做「来源 → 请求选项」的一段。

### 1.2 Java 调用链

```text
DefaultProviders.streamFnFor（coding-agent）
  └─ apiOptions(args, provider, settings, extra)        → ApiOptions（无 identity）
  └─ withMaxRetries / withPerModelOverrides
  └─ provider.createApi(ChatApi.class, effectiveOptions)
       └─ ConfigurableProvider.createApi / effectiveOptions
            └─ 车道构造器（Anthropic／OpenAI／Google／Mistral／…）
                 └─ 车道头表：默认头 → model/extra 头
  └─ api.streamBlocking(request, effectiveOptions)
```

Java 没有 `Models.applyAuth` 的单一合并点；`DefaultProviders.streamFnFor` 是宿主侧唯一的请求组装点，`ConfigurableProvider.createApi` 是 provider 侧唯一的车道构造点。

### 1.3 身份在哪一层计算

pi 在车道的 `createClient(model, ..., identity)` 里计算 `getIdentityHeaders(model, identity)`，因为那里同时有 **model**（provider＋baseUrl）和 **identity**。Java 的对应点有两个候选：

| 候选 | 能看到什么 | 问题 |
|---|---|---|
| 车道 `streamInternal(request, ...)` | request.model＋options | SDK client 已在构造器里建好；要重建客户端 |
| `ConfigurableProvider.createApi(options)` | provider 名＋effective baseUrl＋options.identity | 看不到 `ModelInfo.baseUrl` 的 per-model 覆盖（但 `DefaultProviders` 已把 per-model baseUrl 投影进 `options.baseUrl`） |
| **`ConfigurableProvider.createApi`（选定）** | provider 名＋effective baseUrl＋identity | 直接用户若绕过 provider 手工 new 车道，不产生身份头；这是非生产形状 |

【决定】在 `ConfigurableProvider.createApi`（以及 `FauxProvider` 之外的 provider 基类路径）把 identity 头预计算进 `ApiOptions.extra["identityHeaders"]`；车道构造器读取并把它作为**最低层**头表。理由：

1. 所有生产入口（coding-agent、AiCli、evals）都经 `Provider.createApi`；
2. 车道构造器不需要知道 model，避免为拿 provider 名而改 `createApi` 签名；
3. 预计算结果是普通 `Map<String,String>`，重建 `ApiOptions` 时随 `extra` 自动保真，不需要再守 11 个重建点。

【风险/代价】[内部，模型不可见] 直接 `new OpenAICompletionsApi(options)` 的测试夹具不会自动得到 identity 头；身份测试统一走 `provider.createApi`。这是内部形状差异，不改变 provider 线上行为。

## 2. pi 逐字契约

### 2.1 `identity.ts`（`packages/ai/src/utils/identity.ts:1-193`）

```ts
import type { Models, MutableModels } from "../models.ts";
import { PI_AI_VERSION } from "./version.ts";

/** The `User-Agent` sent to providers: detailed / short / custom. */
export type UserAgentMode = "short" | "detailed" | { custom: string };

export interface ClientAttribution {
	name: string;
	url: string;
	categories?: string[];
}

export const PI_ATTRIBUTION: ClientAttribution = {
	name: "pi", url: "https://pi.dev", categories: ["cli-agent"],
};

export interface ClientIdentity {
	userAgent?: UserAgentMode;
	attribution?: ClientAttribution | null;
	version?: string;
}

export interface ClientIdentityTarget {
	readonly provider: string;
	readonly baseUrl: string;
}

export function mergeClientIdentity(...sources: (ClientIdentity | undefined)[]): ClientIdentity | undefined {
	let merged: ClientIdentity | undefined;
	for (const source of sources) {
		if (!source) continue;
		merged = { ...merged };
		if (source.userAgent !== undefined) merged.userAgent = source.userAgent;
		if (source.attribution !== undefined) merged.attribution = source.attribution;
		if (source.version !== undefined) merged.version = source.version;
	}
	return merged;
}

export function getClientVersion(identity?: ClientIdentity): string {
	return identity?.version ?? PI_AI_VERSION;
}

/** The `User-Agent` value of an identity. */
export function formatUserAgent(identity?: ClientIdentity): string {
	const mode = identity?.userAgent ?? "detailed";
	if (typeof mode === "object") return mode.custom;
	if (mode === "short") return "pi";
	const product = `pi/${getClientVersion(identity)}`;
	const runtime = runtimeDescription();
	return runtime ? `${product} (${process.platform}; ${runtime}; ${process.arch})` : `${product} (browser)`;
}

function isOpenRouter(target) {
	return target.provider === "openrouter" || target.baseUrl.includes("openrouter.ai");
}
function isNvidiaNim(target) {
	return target.provider === "nvidia" || matchesHost(target.baseUrl, "integrate.api.nvidia.com");
}
function isOpenCode(target) {
	return target.provider === "opencode" || target.provider === "opencode-go"
		|| matchesHost(target.baseUrl, "opencode.ai");
}

export function getAttributionHeaders(target, identity?) {
	const attribution = identity?.attribution;
	if (!attribution) return {};
	const { name, url, categories } = attribution;
	if (isOpenRouter(target)) {
		return {
			"HTTP-Referer": url,
			"X-OpenRouter-Title": name,
			...(categories === undefined || categories.length === 0
				? {} : { "X-OpenRouter-Categories": categories.join(",") }),
		};
	}
	if (isNvidiaNim(target)) return {
		"X-BILLING-INVOKE-ORIGIN": name.charAt(0).toUpperCase() + name.slice(1),
	};
	if (isOpenCode(target)) return { "x-opencode-client": name };
	return {};
}

export function getOpenCodeSessionHeaders(target, sessionId) {
	return sessionId && isOpenCode(target) ? { "x-opencode-session": sessionId } : undefined;
}

export function getIdentityHeaders(target, identity?) {
	return { "User-Agent": formatUserAgent(identity), ...getAttributionHeaders(target, identity) };
}
```

`withClientIdentity(models, identity)`（:154-193）包装 `Models` 的每个 stream 方法，把 `identity` 合并进 `options.identity`。Java 无 `Models` 门面，登记 D204。

### 2.2 `ProviderRequestOptions.identity`（`packages/ai/src/types.ts:196-201`）

```ts
	/**
	 * How the request identifies the calling app: `User-Agent` and provider attribution headers.
	 * Absent fields use pi-ai's defaults: a detailed `User-Agent` and no attribution.
	 */
	identity?: ClientIdentity;
```

### 2.3 `Models.applyAuth` 的 tri-state 合并（`packages/ai/src/models.ts:856-898`）

```ts
const sessionId =
	options && "sessionId" in options && typeof options.sessionId === "string" ? options.sessionId : undefined;
let headers = mergeHeaders(
	mergeHeaders(getOpenCodeSessionHeaders(requestModel, sessionId), auth.headers),
	options?.headers,
);
if (options?.transformHeaders) headers = await options.transformHeaders(headers ?? {});
const identity = mergeClientIdentity(this.identity, options?.identity);
const { transformHeaders: _transformHeaders, ...providerOptions } = options ?? {};
const requestOptions = {
	...providerOptions,
	apiKey,
	headers,
	env,
	...(identity === undefined ? {} : { identity }),
};
```

要点：

1. `models.identity`（`CreateModelsOptions.identity`，`:377-382`）先合并，请求级 `options.identity` 后合并、逐字段赢；
2. `attribution: null` 是值，不是 `undefined`——它会清掉此前合并的 attribution；
3. OpenCode session 头在 `auth.headers` 之前合并，`options.headers` 最后（所以配置头可覆盖 session 头）。

### 2.4 车道头表顺序

| 车道 | pi 证据 | 顺序（低 → 高） |
|---|---|---|
| `openai-completions` | `openai-completions.ts:744-786` | identity → `model.headers` → copilot 动态头 → sessionAffinity → `options.headers` |
| `anthropic-messages` | `anthropic-messages.ts:1016-1072` | identity → 默认头 → sessionAffinity → `model.headers` → `options.headers`；OAuth 分支改用 Claude Code 身份，不读 identity |
| `openai-responses` | `openai-responses.ts:299-304` | identity → `model.headers` → `options.headers` |
| `azure-openai-responses` | `azure-openai-responses.ts:155` | `{ ...identity, ...model.headers }` → `options.headers` |
| `google-generative-ai` | `google-generative-ai.ts:346-353` | `providerHeadersToRecord(identity, model.headers, optionsHeaders)` |
| `mistral-conversations` | `mistral-conversations.ts:348-355` | identity → accept/auth/content-type → `model.headers` → `options.headers` |
| `pi-messages` | `pi-messages.ts:410-418` | identity → auth/accept/content-type → `options.headers` |
| `openrouter-images` | `openrouter-images.ts:125-132` | `providerHeadersToRecord(identity, model.headers, optionsHeaders)` |

`providerHeadersToRecord()`（`utils/headers.ts:11-29`）语义：**大小写不敏感**替换；值 `null` 删除同名键；空表返回 `undefined`。

### 2.5 上层 identity 来源

`packages/agent/src/agent-loop.ts:37-42`：

```ts
/** Request identity of agent loops: pi-ai's defaults with provider attribution to pi. */
export const DEFAULT_AGENT_IDENTITY: ClientIdentity = { attribution: PI_ATTRIBUTION };
```

`agent-loop.ts:411-417`：

```ts
const response = await streamFunction(config.model, llmContext, {
	...config,
	apiKey: resolvedApiKey,
	identity: mergeClientIdentity(DEFAULT_AGENT_IDENTITY, config.identity),
	signal,
});
```

`packages/coding-agent/src/core/client-identity.ts:7-14`：

```ts
export function getClientIdentity(settingsManager: SettingsManager): ClientIdentity {
	return {
		userAgent: "detailed",
		version: VERSION,
		attribution: isInstallTelemetryEnabled(settingsManager) ? PI_ATTRIBUTION : null,
	};
}
```

`packages/coding-agent/src/core/sdk.ts:340-353` 把它作为**请求级** identity 放进 `buildRequestOptions`，覆盖 agent 默认。

### 2.6 pi 测试矩阵

| 来源 | # | 场景 | 关键断言 |
|---|---:|---|---|
| `ai/test/identity.test.ts:69-75` | 1 | 版本常量与 package.json 同步 | `PI_AI_VERSION === pkg.version` |
| `:74-79` | 2 | UA 三模式 | detailed／short／custom |
| `:81-92` | 3 | tri-state merge | `attribution:null` 覆盖有值 |
| `:94-99` | 4 | 默认无归因 | 只有 UA |
| `:101-125` | 5 | OpenRouter 归因 | 三头、categories 空省略、非 URL 的 custom 也命中 |
| `:127-145` | 6 | NVIDIA／OpenCode | NIM 首字母大写；OpenCode client 名 |
| `:146-156` | 7 | 网关不误判 NIM | OpenRouter 与 Vercel 网关无 NIM 头 |
| `:159-175` | 8 | identity 低于 model／request 头 | model 头赢、request `null` 删除 |
| `:177-189` | 9 | impersonating model 头赢 UA | GitHub Copilot UA 保留 |
| `:191-233` | 10 | request > wrapper > Models | 三级合并序；Mutable 仍可写 |
| `:235-252` | 11 | OpenCode session 头 | custom provider 命中；配置头覆盖 |
| `coding-agent/test/sdk-client-identity.test.ts:142-190` | 12-16 | settings 身份 | detailed＋版本＋归因；遥测关⇒null；`PI_TELEMETRY=0`；session 头与配置覆盖 |
| `agent/test/agent.test.ts:1203-1240` | 17 | agent 默认归因 | `DEFAULT_AGENT_IDENTITY` 合并，后来赋值覆盖 |

## 3. Java 现状与已证实缺口

### 3.1 没有任何 identity 类型

`grep -rn "ClientIdentity\|PI_ATTRIBUTION" --include=*.java pi-java-ai/src/main` 零命中。`pi-java-ai` 只有 `ApiOptions`、`PiHttpClient` 的固定 UA，以及 coding-agent 的旧锚点 `ProviderAttribution`。

### 3.2 `ApiOptions` 没有 identity

`AI/api/ApiOptions.java:24-41` 七个组件：`baseUrl/apiKey/timeout/maxRetries/extra/authKind/telemetry`。pi 的 `identity` 属于 `ProviderRequestOptions`（`types.ts:196-201`），Java 对应物就是 `ApiOptions`。

### 3.3 UA 固定且不可配置

`AI/http/PiHttpClient.java:111` 传输层固定写 `.header("User-Agent", userAgent)`；`Builder` 默认 `"pi-java/dev"`（`:255`）。SDK 车道（Anthropic／OpenAI／Google／Azure）根本不经过 `PiHttpClient`，各自用 SDK 默认 UA。Mistral／PiMessages 两个手工车道显式 `userAgent("pi-java/dev")`（`MistralConversationsApi.java:119`；`PiMessagesApi.java:86`）。

### 3.4 旧锚点 `ProviderAttribution` 与新锚点冲突

`pi-java-coding-agent/.../ProviderAttribution.java:27-130` 是旧锚点 `provider-attribution.ts` 的移植：四家头集（OpenRouter／NVIDIA／Cloudflare／OpenCode）、`x-opencode-session` 与 `x-opencode-client` 同时产出、遥测门内建。新锚点：

1. Cloudflare 分支消失；
2. `x-opencode-client` 改由 attribution 产出，`x-opencode-session` 只在 session 存在时产出；
3. 归因来自 `identity.attribution`，不是硬编码 `PI_ATTRIBUTION`；
4. `User-Agent` 是可配置的 identity 字段，不再只是归因副产物。

### 3.5 车道头表只有「默认 → extra」两层

`AbstractChatApi.putExtraHeaders`（`:353-365`）把 `extra["headers"]` 交给 SDK client builder；它跳过 `null` 值。手工车道（Mistral／PiMessages）自己实现大小写不敏感覆盖与 `null` 删除。没有 identity 层，也没有 `model.headers` 与 `options.headers` 的分层（Java 把两者都压进 `extra["headers"]`）。

### 3.6 上层没有 identity 输入

`agent-core`：

- `StreamOptions`（`:46-90`）只有 `maxTokens/temperature/reasoning/cacheRetention/sessionId/thinkingBudgets`；
- `HarnessConfig`（`:88-126`）没有 identity；
- `PiLoopRunner.streamAssistantResponse`（`:290`）构造 `StreamOptions` 时不传身份。

`coding-agent`：

- `DefaultProviders.streamFnFor`（`:159-225`）逐请求组装 `ApiOptions`，但不设置 identity；
- 唯一身份头来源是旧 `ProviderAttribution.apply`（`:363`）。

### 3.7 `ApiOptions` 的 12 个重建点

新增 `identity` 组件后，下列从既有 `ApiOptions` 复制出新对象的点必须显式携带 `options.identity()`：

| # | 文件:行 | 方法／用途 |
|---:|---|---|
| 1 | `AI/provider/ConfigurableProvider.java:102` | `effectiveOptions`：补默认 baseUrl |
| 2 | `AI/provider/OpenAiCompatibleProvider.java:41` | `withPlaceholderKey`：Ollama 占位 key |
| 3 | `AI/provider/ModelsJsonProvider.java:61` | 覆盖 baseUrl |
| 4 | `AI/provider/ModelsJsonProvider.java:71` | 覆盖 inline apiKey |
| 5 | `AI/provider/builtin/OpenRouterProvider.java:59` | Anthropic 兼容端点的 baseUrl pin |
| 6 | `pi-java-coding-agent/.../DefaultProviders.java:210` | `withPerModelOverrides` |
| 7 | `pi-java-coding-agent/.../DefaultProviders.java:271` | `withModelProtocol` |
| 8 | `pi-java-coding-agent/.../DefaultProviders.java:301` | 暖请求 maxRetries＝0（实为 `withMaxRetries`） |
| 9 | `pi-java-coding-agent/.../ProviderAttribution.java:69` | 归因头合并（本包删除） |
| 10 | `pi-java-coding-agent/.../extension/bridge/ProviderEventBridge.java:161` | 头变换写回 |
| 11 | `pi-java-coding-agent/.../extension/bridge/ProviderEventBridge.java:188` | 三钩写入 extra |
| 12 | `AI/api/ApiOptions.java:58` | `defaults()` |

删除 #9 后剩 11 个点；评审必须 `grep -rn "new ApiOptions(" --include=*.java` 逐点核对。

### 3.8 现有测试没有钉住 identity

`ApiOptionsTelemetryPropagationTest` 只断言 `telemetry`；`DefaultProvidersTest` 的 `optionsSeen` 没有 identity 断言。旧 `ProviderAttributionTest` 钉的是旧锚点行为，本包删除后由新的 identity 测试替代。

## 4. 拟改设计

### 4.1 新包 `com.pijava.ai.identity`

| 文件 | 可见性 | 预计行数 |
|---|---|---:|
| `identity/UserAgentMode.java` | public sealed interface | 40–60 |
| `identity/ClientAttribution.java` | public record | 40–60 |
| `identity/AttributionSetting.java` | public sealed interface | 40–60 |
| `identity/ClientIdentity.java` | public record＋merge | 80–120 |
| `identity/ClientIdentityTarget.java` | public record | 20–30 |
| `identity/RuntimeInfo.java` | public record＋current() | 40–60 |
| `identity/IdentityHeaders.java` | public utility | 120–180 |
| `identity/PiAiVersion.java` | public constant | 10–20 |

形状：

```java
public sealed interface UserAgentMode permits UserAgentMode.Short, UserAgentMode.Detailed,
        UserAgentMode.Custom {
    UserAgentMode SHORT = new Short();
    UserAgentMode DETAILED = new Detailed();

    record Short() implements UserAgentMode {}
    record Detailed() implements UserAgentMode {}
    record Custom(String value) implements UserAgentMode {
        public Custom { java.util.Objects.requireNonNull(value, "value"); }
    }
}

public sealed interface AttributionSetting permits AttributionSetting.Unset,
        AttributionSetting.Off, AttributionSetting.Value {
    AttributionSetting UNSET = new Unset();
    AttributionSetting OFF = new Off();

    record Unset() implements AttributionSetting {}
    record Off() implements AttributionSetting {}
    record Value(ClientAttribution attribution) implements AttributionSetting {
        public Value { java.util.Objects.requireNonNull(attribution, "attribution"); }
    }
}

public record ClientAttribution(String name, String url,
                                @Nullable java.util.List<String> categories) {
    public ClientAttribution { ... 防御性拷贝 ... }
    public static final ClientAttribution PI = new ClientAttribution(
        "pi", "https://pi.dev", java.util.List.of("cli-agent"));
}

public record ClientIdentity(
        @Nullable UserAgentMode userAgent,      // null = 缺席
        AttributionSetting attribution,          // UNSET / OFF / Value
        @Nullable String version                // null = 缺席
) {
    public ClientIdentity {
        attribution = attribution == null ? AttributionSetting.UNSET : attribution;
    }
    public static ClientIdentity empty() { ... }
    public static ClientIdentity merge(ClientIdentity... sources) { ... } // 后者逐字段赢
}
```

合并规则逐条对应 pi `mergeClientIdentity`（§2.1）：

1. `userAgent == null` ⇒ 跳过；否则覆盖。
2. `attribution`：`UNSET` ⇒ 跳过；`OFF` ⇒ 把结果设为 `OFF`；`Value` ⇒ 覆盖。
3. `version == null` ⇒ 跳过；否则覆盖。
4. 全部跳过且没有源对象 ⇒ 返回 `null`（≙ pi 的 `undefined`）。

### 4.2 UA 与归因函数：`IdentityHeaders`

```java
public final class IdentityHeaders {

    public static final String EXTRA_KEY = "identityHeaders";

    public static String formatUserAgent(ClientIdentity identity) {
        return formatUserAgent(identity, RuntimeInfo.current());
    }

    public static String formatUserAgent(ClientIdentity identity, RuntimeInfo runtime) {
        var mode = identity == null || identity.userAgent() == null
            ? UserAgentMode.DETAILED : identity.userAgent();
        if (mode instanceof UserAgentMode.Custom custom) return custom.value();
        if (mode instanceof UserAgentMode.Short) return "pi";
        String version = identity != null && identity.version() != null
            ? identity.version() : PiAiVersion.VERSION;
        return "pi-java/" + version + " (" + runtime.platform() + "; "
            + runtime.runtime() + "; " + runtime.arch() + ")";
    }

    public static Map<String, String> attributionHeaders(
            ClientIdentityTarget target, ClientIdentity identity) { ... }

    public static Map<String, String> openCodeSessionHeaders(
            ClientIdentityTarget target, String sessionId) { ... }

    public static Map<String, String> identityHeaders(
            ClientIdentityTarget target, ClientIdentity identity) { ... }
}
```

约束：

1. UA 默认 `detailed`；`identity` 或 `userAgent` 缺席都走 detailed。
2. `RuntimeInfo` 形状 `record(String platform, String runtime, String arch)`；`current()` 用 `os.name`／`java/<java.version>`／`os.arch`，与既有 `BugReportCollect.java:168-172` 的 pi-java UA 形一致（品牌差归 D77 族）。
3. `isOpenRouter` 用 `provider.equals("openrouter") || baseUrl.contains("openrouter.ai")`（**contains**，不是 host 等值）。
4. `isNvidiaNim`／`isOpenCode` 用 provider 名或 host 等值（`URL` 解析失败⇒false）。
5. 归因头键序：OpenRouter `HTTP-Referer`、`X-OpenRouter-Title`、可选 `X-OpenRouter-Categories`；NVIDIA `X-BILLING-INVOKE-ORIGIN`；OpenCode `x-opencode-client`。
6. `categories == null || isEmpty()` ⇒ 省略键。
7. `identityHeaders` = `LinkedHashMap`，先 `User-Agent`，后归因头。
8. `openCodeSessionHeaders` 只在 `sessionId` 非空且 target 命中 OpenCode 时返回 `{"x-opencode-session": sessionId}`；否则空 map。

### 4.3 `ApiOptions` 扩展

```java
public record ApiOptions(
    String baseUrl,
    String apiKey,
    Duration timeout,
    int maxRetries,
    Map<String, Object> extra,
    AuthKind authKind,
    @Nullable TelemetrySpan telemetry,
    @Nullable ClientIdentity identity
) {
    public ApiOptions { ... identity 不拷贝，句柄/不可变值 ... }
    // 七参、六参、五参构造器保留，identity 置 null；defaults() 同
}
```

约束：

1. `identity` 是请求级身份；`null` ≙ pi 的 `undefined` ⇒ 车道仍发默认 detailed UA（没有“无身份”状态）。
2. 12 个重建点改用八参（§3.7）；删除 #9 后 11 个点。
3. 新增包可见静态 `ApiOptions withIdentity(ApiOptions, ClientIdentity)` 供 coding-agent 使用；不要手写第 13 个重建点。
4. 不新增 `withTelemetry` 之类的可变 API，保持显式重建风格。

### 4.4 Provider 侧预计算 identity 头

`ConfigurableProvider.createApi` 在 `effectiveOptions(options)` 之后：

```java
var opts = effectiveOptions(options);
var target = new ClientIdentityTarget(name(), opts.baseUrl() == null ? "" : opts.baseUrl());
var identityHeaders = IdentityHeaders.identityHeaders(target, opts.identity());
var extra = new LinkedHashMap<String, Object>(opts.extra());
extra.put(IdentityHeaders.EXTRA_KEY, identityHeaders);
opts = new ApiOptions(opts.baseUrl(), opts.apiKey(), opts.timeout(), opts.maxRetries(),
    extra, opts.authKind(), opts.telemetry(), opts.identity());
```

约束：

1. 预计算对 `ChatApi`／`ImageApi`／`EmbeddingApi` 一律执行；没有 header 的 Faux 车道忽略该键。
2. `OpenRouterProvider.pinnedOptions` 与 `ModelsJsonProvider.pinnedOptions` 先 pin baseUrl，再进 `ConfigurableProvider.createApi` ⇒ target 看到 pin 后的端点。
3. `FauxProvider`／`OverrideProvider` 不产生 HTTP 头；`OverrideProvider` 委托，自动继承。
4. `identityHeaders` 永远非空（至少一个 `User-Agent`），所以键总存在。

### 4.5 车道应用：最低层头表

`AbstractChatApi` 新增：

```java
/** 读取 provider 预计算的 identity 头（extra["identityHeaders"]）；空/缺席 ⇒ 空表。 */
public static Map<String, String> identityHeaders(ApiOptions options) { ... }

/** 把 identity 头逐条写入 sink；在 putExtraHeaders 之前调用。 */
public static void putIdentityHeaders(ApiOptions options, BiConsumer<String, String> sink) {
    identityHeaders(options).forEach(sink);
}
```

各车道：

| 车道 | 改动（实施终态） |
|---|---|
| `AnthropicMessagesApi` | 非 OAuth 分支：`putRequestHeaders`（Java 合并 identity＋extra）；OAuth 分支保持 Claude Code UA，不读 identity |
| `AnthropicFederationSupport` | 联邦分支 `base.withOptions(b -> putRequestHeaders(options, b::putHeader))` |
| `OpenAICompletionsApi` | `putRequestHeaders`（一次写合并后的表） |
| `OpenAIResponsesApi` | 同上 |
| `AzureOpenAIResponsesApi` | 同上 |
| `GoogleGenerativeAiApi` | 合并结果先放进 `extraHeaders` 表，再随请求发出 |
| `MistralConversationsApi` | `MistralHeaders.build(identityHeaders, ...)`：identity → accept/auth → model.headers → option headers → affinity |
| `PiMessagesApi` | 头表：identity（小写规范化） → authorization/accept → option headers（`null` 删除） |
| `OpenRouterImagesApi` | client builder：`putRequestHeaders`（此前完全不读 extra） |

`MistralHeaders.build` 的覆盖用大小写不敏感的 `putCi/removeCi`；`IdentityHeaders.mergeHeaders` 同为大小写不敏感替换＋`null` 删除。

### 4.6 `null` 删除与 SDK 车道

【实施修正】原稿打算沿用 D34 的窄差（SDK builder 只有 `putHeader`、没有删除）。实施时实测发现 SDK builder 的 `putHeader` 次序**不是**「后写赢」（`ClientIdentityWireTest` 里 `x-openrouter-title` 仍为 `pi`），于是把合并整体上移到 Java：

```java
// AbstractChatApi
public static Map<String, String> requestHeaders(ApiOptions options) {
    return IdentityHeaders.mergeHeaders(identityHeaders(options), options.extra().get("headers"));
}
protected static void putRequestHeaders(ApiOptions options, BiConsumer<String, String> sink) { ... }
```

`IdentityHeaders.mergeHeaders` 大小写不敏感替换、`null` 删除。所有 SDK 车道改为**先合并、再一次性写 builder**；手工车道（Mistral／PiMessages）本来就在 Java 侧合并。两族车道的 `null` 删除因此完整，D34 形状不再在 AI 层复刻；`D205` 只保留 UA 品牌／运行时 token 一项（裁决 3）。

### 4.7 上层 identity 输入

`agent-core`：

```java
// StreamOptions 追加第八组件
@Nullable ClientIdentity identity       // null ≙ 缺席

// HarnessConfig 追加末组件
@Nullable ClientIdentity identity       // pi AgentOptions.identity

// 新常量
public static final ClientIdentity DEFAULT_AGENT_IDENTITY =
    new ClientIdentity(null, AttributionSetting.of?(ClientAttribution.PI), null);
```

`PiLoopRunner.streamAssistantResponse`：

```java
var identity = ClientIdentity.merge(AgentIdentity.DEFAULT, config.identity());
var options = new StreamOptions(..., config.thinkingBudgets(), identity);
```

`coding-agent`：

```java
// 新类 ClientIdentityProvider（对位 client-identity.ts）
static ClientIdentity get(Settings settings) {
    return new ClientIdentity(UserAgentMode.DETAILED, 
        InstallTelemetry.isEnabled(settings.enableInstallTelemetry)
            ? AttributionSetting.value(ClientAttribution.PI) : AttributionSetting.OFF,
        Version.VERSION);
}

// DefaultProviders.streamFnFor 每个请求
var identity = ClientIdentity.merge(options.identity(), ClientIdentityProvider.get(settings));
resolved = ApiOptions.withIdentity(resolved, identity);
```

约束：

1. 请求级 `settings` 身份覆盖 agent 级 `StreamOptions.identity`（pi sdk 的 `buildRequestOptions` 在 `...options` 之后写 `identity`）。
2. `DefaultProviders` 删除 `ProviderAttribution.apply`；`ProviderAttribution` 与其测试删除。
3. OpenCode session 头由 `DefaultProviders` 在 `extra["headers"]` 的前层合并（先 session、后既有头，既有头赢），对位 pi `sdk-client-identity.test.ts:181-190`。
4. `RemoteCatalogWire.USER_AGENT` 改为同一个 detailed UA（`IdentityHeaders.formatUserAgent(new ClientIdentity(DETAILED, UNSET, Version.VERSION))`）。
5. `InstallTelemetry` 保留，消费点换成 `ClientIdentityProvider`。

### 4.8 文件与包布局

| 文件 | 可见性 | 预计行数 |
|---|---|---:|
| `AI/identity/*.java`（7 文件） | public | 350–520 总计 |
| `AI/api/ApiOptions.java` | public，加组件＋构造器＋helper | +40 |
| `AI/provider/ConfigurableProvider.java` | 预计算 | +12 |
| `AI/protocol/AbstractChatApi.java` | 两个 helper | +30 |
| 8 个车道 | 各 +1–5 行 | — |
| `agent-core`：StreamOptions／HarnessConfig／PiLoopRunner／AgentIdentity | 加组件 | +60 |
| `coding-agent`：ClientIdentityProvider＋DefaultProviders＋删除 ProviderAttribution | — | +80/−170 |
| 测试 6–8 文件 | — | 500–700 总计 |

## 5. 状态迁移与线格顺序

### 5.1 身份合并

```text
pi:      models.identity  →  options.identity  →  车道
java:    StreamOptions.identity (agent 默认＋HarnessConfig)
              →  ClientIdentityProvider.get(settings)（请求级，赢）
              →  ApiOptions.identity
              →  ConfigurableProvider 预计算 extra["identityHeaders"]
              →  车道头表（最低层）
```

### 5.2 一次 OpenRouter 请求的头表

```text
provider.createApi
  → target = (provider="openrouter", baseUrl=effective)
  → identityHeaders = { User-Agent: pi-java/<ver> (...), HTTP-Referer, X-OpenRouter-Title,
                        X-OpenRouter-Categories }
车道构造
  → putIdentityHeaders (最低层)
  → putExtraHeaders：model.headers / models.json 头 / transformHeaders 结果（覆盖 identity）
  → SDK request
```

### 5.3 tri-state 示例

```text
merge({attribution: PI, userAgent: short}, {version: "1.0"})
  → {attribution: PI, userAgent: short, version: "1.0"}
merge({attribution: PI}, {attribution: null})
  → {attribution: null}
merge({attribution: PI}, {userAgent: short})
  → {attribution: PI, userAgent: short}
```

## 6. RED-first 验收

先写测试并观看失败，再写实现。

### 6.1 纯函数（`pi-java-ai/src/test/java/com/pijava/ai/identity/IdentityHeadersTest.java`）

| Java 用例 | 对应 pi 案 | 断言 |
|---|---|---|
| `formatsUserAgents` | #2 | detailed 用注入 RuntimeInfo；short＝`pi`；custom 原样 |
| `mergesIdentitiesFieldByField` | #3 | null/absent/有值三态；`OFF` 清空 |
| `sendsNoAttributionUnlessEnabled` | #4 | 无 identity⇒只有 UA；`OFF`⇒无归因 |
| `attributesOpenRouterRequests` | #5 | 三头；空 categories 省略；非 URL custom 命中 |
| `attributesNvidiaAndOpenCode` | #6 | NIM 首字母大写；OpenCode client |
| `doesNotAttributeGatewaysToNvidia` | #7 | Vercel／OpenRouter 无 NIM 头 |
| `openCodeSessionHeaderOnlyForOpenCode` | #11 | session 空/非 OpenCode 缺席 |

### 6.2 车道 wire（`ClientIdentityWireTest`）

1. `identityIsBelowModelAndRequestHeaders`（#8）：OpenAI completions，model header 与 `extra["headers"]` 覆盖，`null` 删除。
2. `modelImpersonatingUaWins`（#9）：model `User-Agent` 保留。
3. `anthropicOAuthKeepsClaudeIdentity`：OAuth 分支不写 identity UA。
4. `mistralHeaderOrder`／`piMessagesHeaderOrder`：identity→auth→model→options；`null` 删除。
5. `imagesLaneAppliesIdentity`：OpenRouterImages 的 default headers 含 identity。

### 6.3 上层接线

| Java 用例 | 对应 pi 案 | 断言 |
|---|---|---|
| `agentMergesDefaultIdentity` | #17 | 默认 `PI_ATTRIBUTION`；HarnessConfig 覆盖 |
| `sdkIdentityOverridesAgentIdentity` | #12 | detailed＋Version＋PI_ATTRIBUTION |
| `telemetryOffTurnsAttributionOff` | #13/#14 | settings 关／`PI_TELEMETRY=0` |
| `openCodeSessionAndConfiguredOverride` | #15/#16 | session 头；模型头覆盖 |
| `propagatesIdentityAcrossRebuilds` | #10 的 Java 变体 | 11 个重建点逐点保真 |

### 6.4 回归

1. 既有 `MistralPromptCacheWireTest`、`PiHttpClient*`、`DefaultProvidersTest` 保持通过（默认 UA 由 `pi-java/dev` 变为 detailed，测试不得再逐字断言旧值）。
2. 既有 `llm.*` 遥测测试不变。
3. Checkstyle／SpotBugs 零新增。

## 7. 验证门与文件约束

```bash
mvn test -pl pi-java-ai -am
mvn test -pl pi-java-agent-core -am
mvn test -pl pi-java-coding-agent -am
mvn clean verify
```

要求：

- 测试零失败、零错误；Checkstyle 零错误零警告；SpotBugs 零新增。
- 生产文件 ≤ 500 行；不使用 `@SuppressWarnings`（除非有注释）。
- 验证日志放在包工作区，不只放 `target/`（`clean` 会删除）。
- 全部通过后才把 map #105 翻为对齐，并回填 `docs/04`／`docs/06`／`docs/08`／`docs/map/02-ai.md`。

## 8. 裁决与风险

### 裁决 1：身份头在 `ConfigurableProvider.createApi` 预计算

【场景】Java 车道构造器只拿到 `ApiOptions`，拿不到 `ModelInfo`；pi 在车道 `createClient(model, identity)` 里现算。

【决定】provider 侧用 provider 名＋effective baseUrl＋`options.identity` 预计算 `extra["identityHeaders"]`；车道按最低层应用。

【风险/代价】[内部，模型不可见] 直接 `new` 车道的测试夹具不自动获得身份头；生产入口全经 provider。登记为内部形状差异，不改变 wire。

### 裁决 2：tri-state 用 `AttributionSetting`

【场景】pi 的 `attribution?: ClientAttribution | null` 需要区分缺席与显式 `null`；Java 的 `ClientAttribution` null 无法区分。

【决定】`AttributionSetting` sealed interface：`UNSET`／`OFF`／`Value`。`userAgent`／`version` 只需要「缺席 vs 有值」，用 nullable。

【风险/代价】[内部，模型不可见] 类型比 pi 的对象字面量重；测试必须钉住 `OFF` 不是 `UNSET`。

### 裁决 3：UA 用 pi-java 品牌与 JVM 运行时 token

【场景】pi 的 detailed UA 是 `pi/<version> (<platform>; node/<version>; <arch>)`；JVM 没有 node。

【决定】`pi-java/<version> (<os.name>; java/<java.version>; <os.arch>)`，与既有 `BugReportCollect` 的 UA 形一致；归因头仍用 `pi`／`https://pi.dev` 与 pi 对齐。

【风险/代价】[内部，模型可见] provider 看到的 UA 品牌串不同；登记 **D205**（UA 品牌／运行时 token 窄差，D77 族）。

### 裁决 4：删除旧 `ProviderAttribution`

【场景】旧锚点的 `ProviderAttribution` 硬编码归因、含 Cloudflare 分支、session 与 client 头同时产出；新锚点已删除该模块。

【决定】删除 `ProviderAttribution` 与其测试；identity 归因由 AI 层产出；coding-agent 只提供身份来源（settings＋版本＋遥测门）与 OpenCode session 头。

【风险/代价】[内部，模型可见] 线上去掉 Cloudflare `User-Agent: pi-coding-agent` 头——这是新锚点行为，不是回归。

### 裁决 5：不移植 `withClientIdentity` 包装器

【场景】Java 没有 `Models`／`MutableModels` 门面，库使用者拿到的是 `StreamFn` 而非 `Models`。

【决定】不新增包装器类；身份合并落在 `StreamOptions`→`ApiOptions` 两个请求组装点。登记 **D204**。

【风险/代价】[内部，模型不可见] 依赖 pi 包装器语义的第三方库无法逐字移植；行为面（请求头）已对齐。

### 裁决 6：合并上移到 Java，全车道支持 `null` 删除（实施修正）

【场景】pi 的 `providerHeadersToRecord` 允许 `null` 删除任意低层头；Java SDK builder 只有 `putHeader`，原计划沿用 D34 的窄差。

【决定】实测 SDK `putHeader` 不保证「后写赢」后，改为在 Java 侧用 `IdentityHeaders.mergeHeaders(identity, options.headers)` 一次合并，再把结果交给 builder（`AbstractChatApi.putRequestHeaders`）。手工车道（Mistral／PiMessages）同形。`null` 删除在两族车道都完整，不登记 SDK 层窄差。

【风险/代价】[内部，模型不可见] `extra["headers"]` 的语义从「builder 层覆盖」变成「Java 合并后写死」；后续若要支持 builder 级动态头，需回到逐条写入。测试 `ClientIdentityWireTest` 钉住覆盖序与 `null` 删除。

### 风险表

| 风险 | 控制 |
|---|---|
| 加 record 组件后重建点漏传 | §3.7 清单＋`grep "new ApiOptions("` 逐点核对＋纵贯测试 |
| 默认 UA 改变影响既有 wire 断言 | 全仓 grep 旧 UA；只改注释/常量，不改测试期望值（无旧值断言） |
| identity 头顺序错误 | 每车道 wire 测试钉住覆盖序与 `null` 删除 |
| OAuth 车道误写身份 | Anthropic OAuth 分支显式跳过 + 测试 |
| session 头覆盖顺序 | 先 session、后 extra；配置头覆盖测试 |
| `StreamOptions`/`HarnessConfig` 加组件破坏调用点 | 保留旧构造器；全量 `mvn clean verify` |
| 旧 `ProviderAttribution` 删除导致 coding-agent 头缺失 | 新 `ClientIdentityProvider`＋`IdentityHeaders` 端到端测试 |

## 9. 闭环时需要回填的位置

1. `docs/04-open-items-register.md`：删除 B266 行；§6 增补 D204／D205；更新“下一新文档号”为 `docs/15`。
2. `docs/map/02-ai.md`：B266 状态；#105 翻为对齐；重算加权汇总（199/240）。
3. `docs/06-current-alignment-plan.md`：L1 行改为“B266 已闭环；B267 待拆”；下一文档号 `docs/15`。
4. `docs/08-pi-rebaseline-master-plan.md`：§9 进度表与 §11 下一步同步。
5. 本稿 §0.3 的账面数字在闭环后用实跑结果复核；测试数若与 docs/13 终态不同，以实跑为准。

## 10. 实施与闭环记录（2026-11-03）

### 10.1 提交

| 提交 | 内容 |
|---|---|
| `954f1fd2` | AI：`com.pijava.ai.identity` 包（8 文件）、`ApiOptions.identity`＋7 个重建点保真、`ConfigurableProvider` 预计算、`AbstractChatApi` 头合并 helper、8 族车道接线、14 个新测试 |
| `99c6108b` | agent-core：`StreamOptions`／`HarnessConfig`／`PiLoop.Config`／`ExecutionContext` 加 identity、`AgentIdentity.DEFAULT`、`PiLoopRunner` 请求前合并、1 个新测试 |
| `576cf263` | coding-agent：`ClientIdentityProvider`＋`DefaultProviders` 宿主任身份替换＋OpenCode session 头、`RemoteCatalogWire` UA、删除旧锚点 `ProviderAttribution`／其测试、16 个新测试 |
| 本提交 | docs/14 设计＋§10 记录，回填 `docs/04`／`docs/06`／`docs/08`／`docs/map/02-ai.md` |

### 10.2 实施要点

- **identity 包**：`UserAgentMode`（sealed：Short／Detailed／Custom）、`AttributionSetting`（sealed：Unset／Off／Value）、`ClientAttribution`、`ClientIdentity`（record＋tri-state `merge`）、`ClientIdentityTarget`、`RuntimeInfo`、`PiAiVersion`、`IdentityHeaders`（UA／归因／session／合并）。
- **provider 侧**：`ConfigurableProvider.createApi` 先 `enrichWithIdentity`，把 `IdentityHeaders.identityHeaders(target, options.identity())` 写进 `extra["identityHeaders"]`；`baseUrl` 用 effective 值（per-model 覆盖已由 `DefaultProviders` 投影）。
- **车道侧**：`putRequestHeaders` = `IdentityHeaders.mergeHeaders(identityHeaders, extra["headers"])`，大小写不敏感替换、`null` 删除；Anthropic OAuth 分支保持 Claude Code UA，不读 identity。
- **agent 层**：`PiLoopRunner` 用 `ClientIdentity.merge(AgentIdentity.DEFAULT, config.identity())`；`AgentIdentity.DEFAULT = {userAgent:null, attribution:PI_ATTRIBUTION, version:null}`。
- **coding-agent 层**：`ClientIdentityProvider.get(settings)` 逐请求求值（`PI_TELEMETRY` 优先，否则 `enableInstallTelemetry`，缺省开）；`DefaultProviders` 用宿主身份**替换** agent 默认（pi `sdk.ts:340-353` 注释原话）；OpenCode session 头先 session、后既有头（既有头赢）。

### 10.3 验证

- `mvn test -pl pi-java-ai -am`：通过（1,904 测试，0 失败）。
- `mvn test -pl pi-java-agent-core -am`：通过（1,786 测试，0 失败）。
- `mvn test -pl pi-java-coding-agent -am`：通过（925 测试，0 失败）。
- `mvn clean verify`：**5,958 测试／0 失败／0 错误／29 跳过**；Checkstyle **0 violations**；SpotBugs 通过；总耗时 **14:35**。日志在包工作区 `verify-full.log`（不入库）。

### 10.4 与设计的偏差

| 项 | 处理 |
|---|---|
| SDK 车道的合并位置 | 原计划 identity 头进 builder default headers、`extra` 头随后覆盖；实测 SDK `putHeader` 不保证「后写赢」，改为 Java 侧 `mergeHeaders` 一次合并后写 builder ⇒ `null` 删除全车道可用，D205 只保留 UA 品牌项（见 §4.6／裁决 6）。 |
| `StreamOptions` 组件数 | 设计写「第八组件」；实施为第七组件（前六组件已是 B103／B128 的形状）。 |
| `DefaultProviders` 身份来源 | 设计写 `merge(options.identity(), host)`；按 pi 注释与 `sdk.ts` 实现为宿主身份**替换**（宿主任身份三字段齐全，合并结果等价，但语义更贴近 pi）。 |
| 测试缝 | `ClientIdentityProvider.get(Settings, String telemetryEnv)` 包可见重载，供测试注入 `PI_TELEMETRY`；生产入口仍读进程环境变量（pi 默认参注入同手法）。 |
| `IdentityHeaders` 运行时 token | pi 无 runtime 时输出 `(browser)`；JVM 恒有 runtime，Java 不保留该分支（不可达）。 |
| `PiHttpClient` 的固定 UA | 设计 §0.3 旧证据提到；生产 provider 请求全经 `createApi`，identity 头已覆盖。`PiHttpClient` 保留自身 UA 供非 provider 请求使用，不在 #105 计分面。 |

### 10.5 文件规模

| 文件 | 行数 |
|---|---:|
| `IdentityHeaders.java` | 150 |
| `ClientIdentity.java` | 68 |
| `ApiOptionsTelemetryPropagationTest.java` | 125 |
| `PiLoopTest.java` | 471 |
| `ClientIdentityProviderTest.java` | 186 |
| `DefaultProvidersIdentityTest.java` | 202 |

全部 ≤500 行；无 `@SuppressWarnings`、无 `System.out.println`。

### 10.6 账面复核

- `map/02-ai.md` #105 翻为对齐；对齐／存疑／缺失＝**99／15／17**；加权 **199.0/240＝82.92%**，未加权 **106.5/131＝81.3%**，权重 3 **96/105＝91.4%**，新增单元域 **9/16＝56.3%**。
- 与 §0.3 的预测一致；仅新增单元域原稿留了旧值，已按逐行复算修正。
