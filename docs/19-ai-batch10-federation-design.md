# 批 10 设计：Anthropic workload identity federation（#98）

锚点 `ea448f454`（v1.1.0-22）。JS SDK 版本 `@anthropic-ai/sdk@0.124.0`（pi 工作树实测）；
java 侧 BOM 已钉 `anthropic-java 2.66.0`（`pi-java-bom/pom.xml:30`）。

## 0. 本批范围

- **做**：#98 Anthropic workload identity federation —— ai 层鉴权解析末位回落 ＋ 车道
  SDK 接线 ＋ 跨请求 token 缓存。
- **不做**：#8 `ToolUseContent.namespace` —— 用户裁决**随 MCP 包**（B203 接线时一并），
  台账措辞不变。

## 1. 关键转向：不用逆协议

`docs/08:58` 原裁决「pi 借官方 SDK 白拿 OIDC 交换，java 要逆协议」**作废**：
`anthropic-java 2.66.0` 已自带全链路（jar 实测，类均在 `anthropic-java-core`）：

| 能力 | JS SDK 0.124.0 | Java SDK 2.66.0 |
|------|----------------|------------------|
| 交换体 `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`、`assertion`、`federation_rule_id`、`organization_id`（＋可选 `service_account_id`/`workspace_id`） | `lib/credentials/oidc-federation.js` | `internal/credentials/WorkloadIdentityCredentials.class`（字符串常量逐字同） |
| 交换头 `anthropic-beta: oauth-2025-04-20,oidc-federation-2026-04-01` | `lib/credentials/types.js` | 同上类常量池逐字同 |
| token 缓存（advisory 120s 后台刷／mandatory 30s 同步刷／失败退避 5s／401 invalidate） | `lib/credentials/token-cache.js` | `core/auth/CachingAccessTokenProvider`（`mandatoryRefreshThreshold`/`advisoryRefreshThreshold`/`advisoryRefreshBackoff`/`invalidate()` 同构） |
| 身份 token 文件**每次调用重读**（K8s projected token 轮转） | `lib/credentials/identity-token.js`（readFile＋trim） | `core/auth/FileIdentityTokenProvider`（`readAllBytes` per `get()`） |
| profile 配置公共 API | `ClientOptions.config` | `ProfileConfig`/`AuthenticationConfig`/`IdentityTokenConfig` builder ＋ `InMemoryProfileConfigProvider.of(...)` ＋ `AnthropicOkHttpClient$Builder.configurationProvider(...)` |
| 每请求克隆共享 token 缓存 | `client.withOptions({defaultHeaders})`（pi :1065） | `AnthropicClient.withOptions(Consumer<ClientOptions.Builder>)` |

java 要写的是**接线**（pi 的 resolve 次序、守卫、跨请求缓存键），协议面 SDK 全包。
以下三处 SDK 行为**实现期先实测**再定稿（本仓惯例：判「SDK 表达不了」前跑往返）：

1. `configurationProvider(...)` 且无 apiKey/authToken 时 `build()` 不抛、首个请求触发交换；
2. `withOptions` 克隆**共享** `CachingAccessTokenProvider`（3 次 stream ⇒ 1 次交换的夹具即验证）；
3. 不设 `configurationProvider` 时**不跑**任何环境凭证链（apiKey/authToken 车道零交换）。

## 2. pi 侧行为（锚点逐字）

### 2.1 五个环境变量（`packages/ai/src/env-api-keys.ts:32-36`）

```ts
export const ANTHROPIC_FEDERATION_RULE_ID_ENV = "ANTHROPIC_FEDERATION_RULE_ID";
export const ANTHROPIC_ORGANIZATION_ID_ENV = "ANTHROPIC_ORGANIZATION_ID";
export const ANTHROPIC_SERVICE_ACCOUNT_ID_ENV = "ANTHROPIC_SERVICE_ACCOUNT_ID";
export const ANTHROPIC_IDENTITY_TOKEN_FILE_ENV = "ANTHROPIC_IDENTITY_TOKEN_FILE";
export const ANTHROPIC_WORKSPACE_ID_ENV = "ANTHROPIC_WORKSPACE_ID";
```

### 2.2 鉴权解析：末位回落（`packages/ai/src/providers/anthropic.ts:49-69`）

排在 stored credential → `ANTHROPIC_AUTH_TOKEN` → `ANTHROPIC_OAUTH_TOKEN`/`ANTHROPIC_API_KEY`
**之后**，3 必需缺任一直接 `undefined`；2 可选有则收：

```ts
// Workload identity federation: the Anthropic SDK exchanges the identity
// token for a short-lived access token and refreshes it itself. Last in
// line so keys and ANTHROPIC_AUTH_TOKEN keep winning, as in the SDK. The
// ids are provider config rather than auth, so they travel in `env`.
const federation: ProviderEnv = {};
for (const envVar of [
	ANTHROPIC_FEDERATION_RULE_ID_ENV,
	ANTHROPIC_ORGANIZATION_ID_ENV,
	ANTHROPIC_IDENTITY_TOKEN_FILE_ENV,
]) {
	const value = await ctx.env(envVar);
	signal.throwIfAborted();
	if (!value) return undefined;
	federation[envVar] = value;
}
for (const envVar of [ANTHROPIC_SERVICE_ACCOUNT_ID_ENV, ANTHROPIC_WORKSPACE_ID_ENV]) {
	const value = await ctx.env(envVar);
	signal.throwIfAborted();
	if (value) federation[envVar] = value;
}
return { auth: {}, env: federation, source: "workload identity federation" };
```

行为钉（`test/anthropic-federation.test.ts`）：env 原样透传（含 `ANTHROPIC_WORKSPACE_ID`）、
`auth` 为空、`source === "workload identity federation"`、缺一必需 ⇒ `undefined`。

### 2.3 请求侧（`packages/ai/src/api/anthropic-messages.ts`）

**a. SDK 默认凭证链守卫（:330-337）**——header 自有鉴权时禁 SDK 暗跑凭证链：

```ts
class PiAnthropic extends Anthropic {
	protected override _shouldResolveDefaultCredentials(): boolean {
		return false;
	}
}
```

**b. 配置构造（:346-370）**——仅 `model.provider === "anthropic"` 且无 key/无 Authorization
头；3 必需缺任一 ⇒ `undefined`：

```ts
function getAnthropicFederation(
	model: Model<"anthropic-messages">,
	apiKey: string | undefined,
	headers: ProviderHeaders | undefined,
	env: ProviderEnv | undefined,
): AnthropicFederationConfig | undefined {
	if (model.provider !== "anthropic" || hasRequestAuth(apiKey, headers)) return undefined;
	const federationRuleId = getProviderEnvValue(ANTHROPIC_FEDERATION_RULE_ID_ENV, env);
	const organizationId = getProviderEnvValue(ANTHROPIC_ORGANIZATION_ID_ENV, env);
	const identityTokenFile = getProviderEnvValue(ANTHROPIC_IDENTITY_TOKEN_FILE_ENV, env);
	if (!federationRuleId || !organizationId || !identityTokenFile) return undefined;
	return {
		organization_id: organizationId,
		workspace_id: getProviderEnvValue(ANTHROPIC_WORKSPACE_ID_ENV, env),
		authentication: {
			type: "oidc_federation",
			federation_rule_id: federationRuleId,
			service_account_id: getProviderEnvValue(ANTHROPIC_SERVICE_ACCOUNT_ID_ENV, env),
			identity_token: { source: "file", path: identityTokenFile },
		},
	};
}
```

**c. 跨请求客户端缓存（:373-377 声明，:1053-1065 使用）**——pi 车道同样每请求建
client，故按 `(baseUrl, config)` ＋ fetch 键缓存基座，`withOptions` 逐请求克隆以共享
token 缓存：

```ts
let federationClient: { key: string; fetch: typeof globalThis.fetch | undefined; client: Anthropic } | undefined;
```

```ts
if (federation) {
	const key = JSON.stringify([model.baseUrl, federation]);
	if (federationClient?.key !== key || federationClient.fetch !== fetch) {
		const client = new PiAnthropic({
			apiKey: null,
			authToken: null,
			config: federation,
			baseURL: model.baseUrl,
			dangerouslyAllowBrowser: true,
			fetch,
		});
		federationClient = { key, fetch, client };
	}
	return { client: federationClient.client.withOptions({ defaultHeaders }), isOAuthToken: false };
}
```

`defaultHeaders` 与 key 车道同配方（accept/json ＋ 会话亲和头 ＋ model.headers ＋
optionsHeaders，:1036-1046）；federation 在场时 `stream()`（:612-613）与
`streamSimple()`（:933-934）都跳过 `assertRequestAuth`。

**d. env 读取回落（`utils/provider-env.ts:44-50`）**：`env[name] → process.env[name] →
Bun /proc/self/environ`。「直连 ai 包、不过宿主解析层」的调用也能靠进程环境命中
federation。Bun 沙盒 fallback 是 Bun 编译产物 bug 的变通，**java 无此问题不移植**。

### 2.4 SDK 交换细节（仅录行为，java 由 SDK 代劳，不写协议码）

- `oidc-federation.js`：JWT 超 16 KiB 客户端先抛；POST `{baseURL}/v1/oauth/token`；
  401 附排障提示（rule 匹配／未设 workspace 时建议设 `ANTHROPIC_WORKSPACE_ID`／Console
  Workload identity 页）；错误体按 RFC 6749 三键白名单脱敏。
- `credential-chain.js`：env-only（无 profile 文件、无 `credentials_path`）⇒ **纯内存
  缓存**，不落盘。pi 走的就是这条（`config` 直给，`credentials_path ?? null`）。
- `token-cache.js`：`getToken` 三分支（剩余 >120s 直接用；30–120s 后台刷＋用旧值；
  <30s 同步刷）；并发刷新合并；401 后 `invalidate()` 强制重换。

## 3. java 待写代码

### 3.1 `AuthKind` 加 `FEDERATION`（`pi-java-ai/.../api/AuthKind.java`）

纯常量闭集，按编码规范用 `enum` 加一枚：

```java
/** workload identity federation：无 key，凭证由 SDK 以 OIDC 交换自管（pi anthropic.ts:49-69）。 */
FEDERATION
```

### 3.2 `RecordedCredential` 加 `env` 组件（`auth/RecordedCredential.java`）

pi 的 resolve 返回 `{auth, env, source}` 三件套；本仓 record 目前只有
`(kind, value, source)`。加第 4 组件并保留 3 参便利构造（既有 `new RecordedCredential(...)`
调用点零改签）：

```java
public record RecordedCredential(AuthKind kind, String value, String source, Map<String, String> env) {
    /** 3 参便利构造：无 provider env（等价 pi 的 env 缺席）。 */
    public RecordedCredential(AuthKind kind, String value, String source) {
        this(kind, value, source, Map.of());
    }
    //  canonical 里 defencive copy：env = env == null ? Map.of() : Map.copyOf(env)
}
```

### 3.3 `Credentials.resolveCredential` 末位回落（`auth/Credentials.java`）

照 pi :49-69 插在**现有链末尾**（默认文件凭证之后仍空 ⇒ 才试 federation；仅
`"anthropic"`）。环境读取走注入的 `EnvApiKeyResolver.resolveEnv`（blank 过滤已有，
等价 pi 的 `if (!value)`）：

```java
/** 仅 Anthropic：federation 三必需 ＋ 两可选（pi env-api-keys.ts:32-36）。 */
private static final List<String> FEDERATION_REQUIRED = List.of(
    "ANTHROPIC_FEDERATION_RULE_ID", "ANTHROPIC_ORGANIZATION_ID", "ANTHROPIC_IDENTITY_TOKEN_FILE");
private static final List<String> FEDERATION_OPTIONAL = List.of(
    "ANTHROPIC_SERVICE_ACCOUNT_ID", "ANTHROPIC_WORKSPACE_ID");

// resolveCredential 末尾（:117-118 的 env/file 落空之后）：
//   if ("anthropic".equals(provider)) {
//       return resolveFederation(env);
//   }
//   return Optional.empty();

/** pi anthropic.ts:53-69 逐字移植：3 必需缺任一 ⇒ empty；2 可选有则收。 */
private static Optional<RecordedCredential> resolveFederation(EnvApiKeyResolver env) {
    var federation = new LinkedHashMap<String, String>();
    for (var name : FEDERATION_REQUIRED) {
        var value = env.resolveEnv(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        federation.put(name, value.get());
    }
    for (var name : FEDERATION_OPTIONAL) {
        env.resolveEnv(name).ifPresent(v -> federation.put(name, v));
    }
    return Optional.of(new RecordedCredential(
        AuthKind.FEDERATION, "", "workload identity federation", federation));
}
```

配套：`resolveApiKey`（:69-71）滤 blank —— federation 无 key，pi 的等价物此时返回
`undefined`（`auth` 为空对象）：

```java
return resolveCredential(provider).map(RecordedCredential::value).filter(v -> !v.isBlank());
```

`AuthCommand` 展示侧因此照旧拿到 empty（打印「未配置」），不炸；`RecordedCredential`
的 source 文案（"workload identity federation"，pi 逐字）留给 status 类消费者。

### 3.4 `DefaultProviders.apiOptions` 过桥（coding-agent）

`kind == FEDERATION` 时 `apiKey` 保持空串、env map 进 `extra["federationEnv"]`
（与 `headers`/`cacheRetention` 同通道）。三态口径同既有约定：**键缺席 ≙ pi 的
undefined**，非 federation 不塞。

```java
if (credential != null) {
    apiKey = credential.value();
    kind = credential.kind();
    if (kind == AuthKind.FEDERATION) {
        mergedExtra.put("federationEnv", credential.env());
    }
}
```

### 3.5 `AnthropicMessagesApi` federation 车道（ai 层核心）

构造器在 `resolveAuth` **之前**判 federation（对应 pi :612-613 的
`if (!federation) assertRequestAuth(...)`）。env 取值次序照
`provider-env.ts:44-50`：`extra["federationEnv"]` → `System.getenv`（Bun fallback
不移植）。3 必需缺任一 ⇒ 落回 `resolveAuth` 走既有「No API key」抛（＝ pi 的
`assertRequestAuth`）。

```java
// 构造器开头：
var federationEnv = federationEnvOf(options);   // extra 优先，System.getenv 回落；三必需齐才非空
if (federationEnv != null && options.apiKey().isBlank()
        && options.authKind() == AuthKind.FEDERATION或extra自带) {
    // 1) 缓存键：baseUrl ＋ 五值（pi :1054 的 JSON.stringify([baseUrl, config])）
    // 2) 基座 client：静态 ConcurrentHashMap<String, AnthropicClient> 缓存，
    //    configurationProvider(InMemoryProfileConfigProvider.of(profileConfig))、
    //    baseUrl、maxRetries(0)；**不挂** per-request 头（pi :1056-1062 同）
    // 3) 本车道 client = 基座.withOptions(b -> { 挂头 })（pi :1065）
    //    头配方与 key 车道同：models.json headers（putExtraHeaders）＋ B103 亲和头
    // 4) bearer=false、oauth=false；sessionId/保留期/effectiveHeaderNames 照旧
} else {
    ... 既有 resolveAuth 三分支 ...
}
```

`ProfileConfig` 构造（SDK 公共 builder，:2.2 节 pi 配置逐字段对应）：

```java
var identityToken = IdentityTokenConfig.builder()
    .source("file").path(env.get("ANTHROPIC_IDENTITY_TOKEN_FILE")).build();
var authentication = AuthenticationConfig.builder()
    .type(AuthenticationType.OIDC_FEDERATION)
    .federationRuleId(env.get("ANTHROPIC_FEDERATION_RULE_ID"))
    .serviceAccountId(Optional.ofNullable(env.get("ANTHROPIC_SERVICE_ACCOUNT_ID")))
    .identityToken(identityToken)
    .build();
var profile = ProfileConfig.builder()
    .organizationId(env.get("ANTHROPIC_ORGANIZATION_ID"))
    .workspaceId(Optional.ofNullable(env.get("ANTHROPIC_WORKSPACE_ID")))
    .authentication(authentication)
    .build();
```

**守卫裁决（pi :330-337 的 `PiAnthropic` 等价物）**：Java SDK 的凭证链是**显式
opt-in**（`fromEnv()`／`configurationProvider()`），不设即不跑 —— apiKey/authToken
车道天然无链，无需 guard 类。用夹具钉死（§4 测 6）。`AnthropicMessagesApi` 预计
越 500 行红线：federation 分支独立新类 `AnthropicFederationSupport`（同包
`AnthropicStreamSupport` 模式），构造器只留分派。

**底座共享的实测判据**：`withOptions` 若不共享 token 缓存（3 次 stream 交换 3 次），
退回 `AnthropicBackend.builder().configurationProvider(...).build()` 缓存**backend**
再逐请求 `AnthropicOkHttpClient.builder().backend(shared).putHeader(...)` —— 两条路
径都写进夹具再定稿，**不**凭 javap 清单下结论。

### 3.6 明确不做

- **不落盘凭证缓存**：pi env-only 链纯内存（`credential-chain.js` 注释逐字），java 不
  设 `credentialsPath`。
- **`ANTHROPIC_IDENTITY_TOKEN`（静态值变体）**：SDK 支持但 pi 的五个变量里没有它
  （pi 只收 FILE），不引入。
- **user_oauth profile／`ANTHROPIC_PROFILE` 配置文件链**：pi 有守卫显式禁用，java
  本就不接，本批不碰。
- **交换错误路径自测**（16 KiB、401 提示、脱敏）：SDK 内部行为，pi 也不测（pi 两个
  测试文件无此用例），不移植。

## 4. 测试计划

镜像 pi 的两个测试文件（`test/anthropic-federation.test.ts`、
`test/anthropic-federation-sdk.test.ts`）＋ 本仓链路口径：

1. **解析序**（`CredentialsTest` 风格，注入 `envLookup`）：federation 四变量齐 ⇒
   `FEDERATION`＋env 透传＋source 文案；缺一必需 ⇒ empty；`ANTHROPIC_AUTH_TOKEN`／
   `ANTHROPIC_OAUTH_TOKEN`／`ANTHROPIC_API_KEY`／stored 各自**赢过** federation（末位
   次序逐条钉）；可选两变量透传与缺席。
2. **`resolveApiKey` blank 过滤**：federation 命中 ⇒ `Optional.empty()`。
3. **过桥**（`DefaultProvidersTest`）：FEDERATION 凭证 ⇒ `apiKey=""`、
   `extra["federationEnv"]` 原样、kind 保真。
4. **车道配置构造**：extra 三必需齐 ⇒ 走 federation 车道不抛「No API key」；缺 ⇒ 抛
   （pi `assertRequestAuth` 等价）。
5. **SDK 实测一（交换一次）**：本地 `HttpServer` 夹具，`/v1/oauth/token` 返
   `{access_token:"federated-token",expires_in:3600}`、`/v1/messages` 返最小 SSE；3 次
   stream ⇒ token 端点命中**恰 1 次**、messages 请求 `Authorization: Bearer
   federated-token`、交换体含 `grant_type`/`assertion`/`federation_rule_id`/
   `organization_id`、交换头 `anthropic-beta` 含 `oidc-federation-2026-04-01`。
6. **SDK 实测二（守卫）**：federation 变量在场但走 authToken 车道 ⇒ 零次 token 端点
   命中（pi「does not run the SDK credential chain for header-owned auth」等价）。

## 5. 闭环回填（通过后）

- `docs/06`：#98 移出缺口表（鉴权节清空），批 10 行入变动日志；
- `docs/08`：批 10 行 ＋ 缺失清单删 #98（仅剩 #8 随 MCP）；加权 206.0 → **207.0/219
  ≈ 94.5%**；
- `docs/04`：#98 销号登记；`docs/05`/map：ai 鉴权行更新；
- #8 措辞不动（随 MCP 包 B203）。
