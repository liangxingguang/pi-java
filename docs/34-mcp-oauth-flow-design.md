# 包 34：MCP 对齐（B160）——第 7 包：OAuth 动态流程

> **状态：✅ 已闭环（2026-10-07，R-A，26c56d8a）。**
> 总包路线见 `docs/28` §3；本文是第 7 包（pi `oauth/flow.ts` 449 行＋
> `oauth/provider.ts` 168 行＋`oauth/callback.ts` 164 行＋`oauth/types.ts`
> 剩余 76 行，合计 857 行）详细设计。**本包是 oauth 域最大一包。**
> 实施记录见 §12。

## 1. 范围边界

本包做**动态流程**：PKCE、客户端注册（DCR 与 metadata document）、授权码
交换、刷新、step-up 授权、回调服务器、默认 provider 与状态存储。发现静态
部分已在包⑥。

oauth.test.ts 8 条款归属：clause 1（全流程 PKCE）、2（并发 401 共享刷新）、
3（insufficient_scope 走重新授权）、4（凭据绑定 serverUrl）、7（iss 校验）、
8（回调页三例）归本包；clause 5/6 已在包⑥闭环。

## 2. 需要先解决的两个地基问题

### 2.1 `AuthProvider` 位置错误（包⑤遗留）

pi 的 `AuthProvider`/`McpFetch` 在 **`src/auth-provider.ts`**（根，非
transports 下），且 `oauth/flow.ts:7` 直接 import 它。包⑤把它放进了
`transport.http` 且是**包私有**：

```java
// 现状 transport/http/AuthProvider.java:12（无 public）
interface AuthProvider { ... }
```

包⑦的 `adaptOAuthProvider` 必须**返回** `AuthProvider`，包私有类型做不到，
且把 oauth 反向依赖 transport 会成环。故本包**上移**到根包
`com.pijava.mcp.AuthProvider`（对齐 pi 的文件位置）。改动面＝3 文件
（AuthProvider 移动、`StreamableHttpTransport:134`、`StreamableHttpTransportOptions:25`
各改 import），签名不变。

### 2.2 `Context` 缺 `fetch`、`token` 恒 null

pi 的 `UnauthorizedContext`（auth-provider.ts:3-10）带 `fetch` 与被拒请求的
`token`；包⑤的 `Context` 两样都缺/恒 null：

```java
// 现状 StreamableHttpTransport.java:134-136
auth.onUnauthorized(new AuthProvider.Context(
        response.status(), challenge == null ? "" : challenge,
        options.url(), null));            // ← token 恒 null
```

`token` 是 clause 2「迟到的旧 token 401 不再刷」的**活命条件**（flow.ts:426-428
读 `context.token`）。本包补：transport 传**实际发出的** `Authorization` 头值；
`Context` 增加 `fetch` 字段。

### 2.3 fetch 形状：一个还是两个

pi 全程一个 `McpFetch`（`(input, init) => Promise<Response>`）。Java 现有两个
不同形状：`transport.http.McpHttpFetch`（**流式**未消费体）与包⑥
`oauth.OAuthFetch`（**缓冲**、只 GET）。

裁决：**对齐 pi，收敛为一个根包 `com.pijava.mcp.McpFetch`**（缓冲、通用
method/headers/body），包⑥的 `OAuthFetch` 随之删除。理由：pi 只有一个；包⑧/⑨
接线时两个形状需要两份 JDK 实现与两套 mock，是持续的税。承载不了流式的部分
不影响——oauth 与 discovery 全程只需缓冲体；transport 保持自己的
`McpHttpFetch`（它要按状态分派先于读体，包⑤已闭环），二者由 transport 内一个
小桥接（`McpHttpFetch` → `McpFetch`：读全体后包成 `Fetched`）连接，桥接只用于
`Context.fetch`。改动面极小：包⑥ 生产侧**只有 1 个调用点**
（`OAuthDiscovery.java:49` 的 `fetchMetadata`，已 grep 复核）＋测试夹具
`ScriptedOAuthFetch` 的 import/形状。

```java
// com.pijava.mcp.McpFetch（新，根包）
@FunctionalInterface
public interface McpFetch {
    Fetched fetch(Request request) throws IOException;

    record Request(String method, URI url, Map<String, String> headers,
                   byte @Nullable [] body) {
        public static Request get(URI url, Map<String, String> headers) {
            return new Request("GET", url, headers, null);
        }
    }

    record Fetched(int status, byte[] body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
```

`JdkMcpFetch`（本包新增，pi 的默认 `globalThis.fetch`）：`HttpClient.send`，
`BodyHandlers.ofByteArray`，任何 IO/中断 ⇒ IOException；请求体
`BodyPublishers.ofByteArray(body)`（null ⇒ noBody）。

## 3. 类型补齐（types.ts 余下 76 行）

### 3.1 四个 record

对齐 types.ts:32-88 的 interface：

```java
public record OAuthTokens(String accessToken, String tokenType,
                          @Nullable Integer expiresIn, @Nullable String scope,
                          @Nullable String refreshToken, @Nullable String idToken) { }

public record OAuthClientMetadata(List<String> redirectUris,
        @Nullable String tokenEndpointAuthMethod, @Nullable List<String> grantTypes,
        @Nullable List<String> responseTypes, @Nullable String clientName,
        @Nullable String clientUri, @Nullable String logoUri, @Nullable String scope,
        @Nullable List<String> contacts, @Nullable String tosUri, @Nullable String policyUri,
        @Nullable String jwksUri, @Nullable Object jwks, @Nullable String softwareId,
        @Nullable String softwareVersion, @Nullable String softwareStatement) { }

public record OAuthClientInformation(String clientId, @Nullable String clientSecret,
        @Nullable Integer clientIdIssuedAt, @Nullable Integer clientSecretExpiresAt) { }

public record OAuthDiscoveryState(String authorizationServerUrl,
        @Nullable AuthorizationServerMetadata authorizationServerMetadata,
        @Nullable OAuthProtectedResourceMetadata resourceMetadata,
        @Nullable String resourceMetadataUrl) { }
```

pi 的 `OAuthClientInformationMixed = OAuthClientInformation | OAuthClientInformationFull`，
而 `Full = Information & OAuthClientMetadata`。Java 用一个 record 承载二者
（`Full` 只是「多带 metadata 字段」，流程只读 `client_id`/`client_secret`/
`token_endpoint_auth_method`）——这是**刻意偏差**：`Mixed` 的联合类型在流程里
只在 `selectClientAuthMethod`（读 auth method 提示）与 `applyClientAuthentication`
（读 id/secret）两处被消费，两处都只碰这三个字段。为避免 `sealed interface` +
downcast 噪音，用一个 record，`tokenEndpointAuthMethod` 作为其一个组件。

⚠️ record 组件名与 JSON 键不一致（`accessToken`↔`access_token`）：用
`@JsonProperty("access_token")` 标注（B139 教训：嵌套 record 被 GETTER NONE
序列化成 `{}`，这里读为主但仍标注，保证双向）。

### 3.2 两个解析器（types.ts:178-204）

- `parseOAuthTokens(Object)`：`access_token`/`token_type` 必填非空串；
  `expires_in` **absent 语义**（null/""⇒缺省，否则 `Number()` 后须有限，
  `Number(null)=0` 会立刻判过期，故先查 absent）；`scope`/`refresh_token`/
  `id_token` optionalString（""⇒缺省）。
- `parseClientInformation(Object)`：`client_id` 必填；`client_secret` optionalString；
  两个 `*_issued_at/expires_at` 须是 number（非 number ⇒ 缺省，不报错）；
  `redirect_uris` optionalStrings，缺省补 `[]`；其余未知键**原样保留**进
  metadata 侧（`compact({...input, ...})` 语义 ⇒ Java 侧把不在具名集合里的键
  收进 `extension`）。为承载它，§3.1 的 `OAuthClientInformation` 需再加一个
  `@Nullable Map<String,Object> extension` 组件。

## 4. 回调服务器（callback.ts 164 行）

文件 `OAuthCallbackServer.java`＋`OAuthCallback.java`＋`OAuthCallbackPage.java`。

- `OAuthCallback(String code, String state, @Nullable String iss)`。
- `OAuthCallbackPage`＝sealed：`Ok()` | `Failed(String message, @Nullable String details)`
  （对齐 TS 联合 `{ok:true} | {ok:false;message;details?}`）。
- `listen(options)`：`com.sun.net.httpserver.HttpServer`，绑 `host`（默认
  `127.0.0.1`）`port`（默认 0=随机），成功后 `http://<redirectHost>:<port><path>`；
  IPv6 主机名加方括号（callback.ts:81）。
- `waitForCallback(state, path?)`：返回 `CompletableFuture<OAuthCallback>`；
  state 已在 pending ⇒ 立刻 `IllegalStateException("OAuth state is already pending")`；
  超时（默认 300_000 ms，虚拟线程 ScheduledExecutor）⇒ 失败
  `"OAuth callback timed out"`。
- `close()`：所有 pending 失败 `"OAuth callback server closed"`，停服。
- `handle`（callback.ts:124-163）逐条：

| 序 | 条件 | 动作 |
|---|---|---|
| 1 | pathname ∉ paths | 404 `Not found` |
| 2 | 无 state 或无 pending | 400 `Invalid or expired OAuth state` |
| 3 | 有 pending 且 `path` 给定但 pathname≠path | pending 失败 `"The authorization response arrived on another redirect URI"`；400 `Unexpected redirect URI` |
| 4 | 有 `error` 参数 | pending 失败 `error_description ?? error`；**200** `Authorization failed. You may close this window.` + details |
| 5 | 无 `code` | pending 失败 `"OAuth callback did not include an authorization code"`；400 `Missing authorization code` |
| 6 | 否则 | 成功 `(code, state, iss?)`；200 `{ok:true}` |

- 应答头：`renderPage` 存在 ⇒ `text/html; charset=utf-8` + `cache-control: no-store`；
  否则 `text/plain; charset=utf-8`。默认正文：ok＝
  `"Authorization complete. You may close this window."`；失败＝
  `details` 存在时 `message + "\n\n" + details` 否则 `message`。

## 5. 流程主体（flow.ts，拆 4 文件）

### 5.1 纯函数与工具

| Java 方法 | pi | 要点 |
|---|---|---|
| `Pkce.generate()` | :147-152 | `SecureRandom` 32 字节 → verifier＝Base64 URL 无填充；challenge＝SHA-256(verifier) 同编码 |
| `loopback(host)` | :102-104 | `localhost`/`127.0.0.1`/`[::1]`/`::1` |
| `secureEndpoint(url)` | :106-110 | 非 https 且非 loopback ⇒ `OAuthInsecureEndpointError(url)` |
| `selectClientAuthMethod(info, supported)` | :112-126 | 见下 |
| `applyClientAuthentication(method, info, headers, params)` | :128-145 | basic ⇒ `Authorization: Basic base64(id:secret)`（缺 secret 报错）；否则 params 放 `client_id`，post 再放 `client_secret` |
| `withScope(tokens, scope)` | :277-279 | `tokens.scope==null && scope!=null` ⇒ 补 scope |
| `stepUpScope(granted, challenged)` | :286-290 | `!challenged ⇒ null`；否则 granted+challenged 各自按 `\s+` 切分、过滤空、**去重保序**、空格 join |
| `startAuthorization(as, opts)` | :154-184 | 见下 |

`selectClientAuthMethod` 顺序（逐条，勿重排）：① `info` 上带 `token_endpoint_auth_method`
且是三值之一且（supported 空或含它）⇒ 用它；② supported 空 ⇒ 有 secret
`client_secret_basic` 否则 `none`；③ 有 secret 且 supported 含 basic ⇒ basic；
④ 有 secret 且 supported 含 post ⇒ post；⑤ supported 含 none ⇒ none；
⑥ 兜底：有 secret ⇒ post 否则 none。

`startAuthorization`：`metadata.response_types_supported` 不含 `code` ⇒
`"Authorization server does not support authorization codes"`；
`code_challenge_methods_supported` 存在且不含 `S256` ⇒
`"... does not support PKCE S256"`；url＝`authorization_endpoint ?? new URL("/authorize", as)`；
设 `response_type=code`、`client_id`、`code_challenge`、`code_challenge_method=S256`、
`redirect_uri`；有则设 `state`、`scope`、`resource`；**`scope` 按 `\s+` 含
`offline_access` ⇒ 另设 `prompt=consent`**（:181）。返回 (url, verifier)。

### 5.2 令牌/注册请求

- `tokenRequest(as, opts, params)`（:186-223）：
  1. url＝`secureEndpoint(metadata.token_endpoint ?? new URL("/token", as))`；
  2. 头 `Accept: application/json`、`content-type: application/x-www-form-urlencoded`；
  3. 有 resource ⇒ params 加 `resource`；
  4. 有 `addClientAuthentication` ⇒ 调它（可改 headers/params），否则
     `applyClientAuthentication(selectClientAuthMethod(info, metadata.token_endpoint_auth_methods_supported ?? []), ...)`；
  5. POST；
  6. **先解析体再看状态**：体是对象且 `error` 是字符串 ⇒ `OAuthError(error, error_description ?? error, error_uri?)`；
  7. `!ok` ⇒ `OAuthError("server_error", "HTTP <n>: <text>")`；
  8. `parseOAuthTokens`。
- `registerClient(as, opts)`（:225-247）：`metadata` 有但无 `registration_endpoint`
  ⇒ `"Authorization server does not support dynamic client registration"`；
  POST JSON `{...clientMetadata, scope?}`；`!ok` ⇒ `OAuthRegistrationError(status, text)`；
  `parseClientInformation`。
- `exchangeAuthorizationCode`（:249-263）：params `grant_type=authorization_code`,
  `code`, `code_verifier`, `redirect_uri`。
- `refreshAuthorization`（:265-275）：params `grant_type=refresh_token`,
  `refresh_token`；返回 `{refresh_token: 传入值, ...tokens}`——⚠️ **展开次序＝
  服务器回传的 `refresh_token` 覆盖传入值，未回传时（`compact` 已丢 undefined）
  才保留传入值**，即传入值是**兜底不是覆盖**（旋转链的活命条件）。

### 5.3 `runFlow`（:292-395）——本包核心

逐步：

1. `metadataUrl`＝`authorizationServerMetadataUrl` 经 `secureEndpoint`；
2. `cached`＝`metadataUrl!=null ? none : provider.discoveryState()`；
   有缓存且带 `authorizationServerUrl` ⇒ 复用它（metadata 缺则补发现）；
   否则 `discoverOAuthServerInfo(serverUrl, ...)`；
3. `metadataUrl==null` ⇒ `saveDiscoveryState({...discovered, resourceMetadataUrl?})`；
4. `resource = selectResource(serverUrl, discovered.resourceMetadata)`；
5. **scope 用 `||` 不是 `??`**（空串落下一源）：
   `options.scope || resourceMetadata.scopes_supported.join(" ") || clientMetadata.scope`；
6. `stored = provider.clientInformation()`；
   `clientDocument = stored==null ? provider.clientMetadataDocument?(metadata) : null`；
   document 的 url 非 https 或 pathname=`"/"` ⇒ `"Invalid OAuth client metadata URL"`；
7. `client = stored ?? (document ? {client_id: document.url} : null)`；
   仍无 client ⇒ 有 `authorizationCode` 则报 `"OAuth client information is missing during code exchange"`；
   无 `saveClientInformation` 则报 `"OAuth client information cannot be persisted"`；
   否则 `registerClient` 并**保存**；
8. `redirectUrl = document?.redirectUrl ?? provider.redirectUrl`；
9. **有 authorizationCode**：RFC 9207 校验——`metadata!=null && (iss!=null ||
   metadata.authorizationResponseIssParameterSupported)` ⇒ `iss != metadata.issuer`
   即 `OAuthIssuerMismatchError(metadata.issuer, iss)`；然后交换、`saveTokens(withScope(tokens, scope))`、
   返回 `AUTHORIZED`；
10. **否则**：`existing = skipRefresh ? null : provider.tokens()`；有 `refresh_token`
    ⇒ 试刷新，成功 `saveTokens(withScope(tokens, existing.scope))` → `AUTHORIZED`；
    失败**只吞两类**：`OAuthInsecureEndpointError` **重抛**；
    `OAuthError` 且 code **不是** `server_error` ⇒ **重抛**；其余（含 server_error 与网络错）吞掉继续；
11. `state = provider.state?.()`；`startAuthorization`；`saveCodeVerifier`；
    `redirectToAuthorization`；返回 `REDIRECT`。

`authorizeMcp`（:397-411）重试包装：`OAuthError` 且 code ∈
{`invalid_client`,`unauthorized_client`} ⇒ `invalidateCredentials("all")` 重跑一次；
code=`invalid_grant` ⇒ `invalidateCredentials("tokens")` 重跑一次；否则抛。

### 5.4 `adaptOAuthProvider`（:413-449）

对齐语义，Java 用 `synchronized` + 单飞字段：

- `token()` ⇒ `provider.tokens()?.access_token`；
- `onUnauthorized(ctx)`：
  1. `challenge = parseWwwAuthenticate(ctx.wwwAuthenticate)`（包⑥）；
     `insufficientScope = challenge.error=="insufficient_scope"`；
  2. **非** insufficientScope 且**当前无在飞**且 `ctx.token != null`：
     读当前 token，**若与 `ctx.token` 不同 ⇒ 直接返回**（别的请求已刷新）；
  3. 无在飞则起：`granted = insufficientScope ? provider.tokens() : null`，
     `authorizeMcp(provider, {serverUrl: ctx.serverUrl,
      resourceMetadataUrl: challenge.resourceMetadataUrl,
      scope: insufficientScope ? stepUpScope(granted?.scope, challenge.scope) : challenge.scope,
      fetch: ctx.fetch, skipRefresh: insufficientScope})`；
  4. 结果 `REDIRECT` ⇒ 抛 `McpOAuthAuthorizationRequiredError`；
  5. 结束清在飞标志。
- 并发 401 共享：第 3 步的在飞 future，其他调用 `await` 同一个。

## 6. 默认 provider（provider.ts 168 行）

`McpOAuthState(String serverUrl, @Nullable OAuthClientInformation clientInformation,
@Nullable OAuthTokens tokens, @Nullable Long tokensExpireAt, @Nullable String codeVerifier,
@Nullable String oauthState, @Nullable OAuthDiscoveryState discovery)`
（`tokensExpireAt` 用 Long，pi 是 epoch ms 的 number）。

`OAuthStateStore` 接口（`load`/`save`）+ `MemoryOAuthStateStore`（**深拷贝**，
pi 用 `structuredClone` ⇒ Java 走 Jackson `convertValue` 往返）。

`McpOAuthProvider`：
- 构造：`serverUrl = new URI(options.serverUrl)` 规范化；
  `clientMetadata` 补默认 `redirect_uris=[redirectUrl]`、`grant_types=[authorization_code, refresh_token]`、
  `response_types=[code]`、`token_endpoint_auth_method = options.clientSecret!=null ? client_secret_post : none`；
  `configuredClient = clientId!=null ? {clientId, clientSecret?} : null`；
- `state()`：已有 `oauthState` 复用，否则 32 字节随机 **hex** 存下并返回；
- `clientInformation()`：`configuredClient ?? load().clientInformation`；
- `saveClientInformation`：有 configuredClient 则**不动**；否则存；
- `saveTokens`：`expires_in==null ⇒ 删 tokensExpireAt`，否则 `now + expires_in*1000`；
- `saveCodeVerifier`/`codeVerifier`（无 ⇒ `"No OAuth PKCE code verifier is stored"`）；
- `invalidateCredentials(kind)`：按 all/client/tokens/verifier/discovery 删；`all` 另删 `oauthState`；
- `saveDiscoveryState`/`discoveryState`；
- **写串行化**：pi 用 `writes` promise 链。Java 用 `synchronized`（或
  `synchronized(lock)` 包住 load-modify-save 全程），保证读-改-写原子；
- **`own(state)`**：`state.serverUrl != this.serverUrl ⇒ 丢弃、返回
  `{serverUrl: this.serverUrl}`**——凭据**绝不跨 server 泄漏**（clause 4 活命条件）。

## 7. 接线（transport.http）

- `AuthProvider.Context` 加 `@Nullable McpFetch fetch` 组件；
- `StreamableHttpTransport:134` 传：`token`＝本次实际发出的 `Authorization` 值
  （buildHeaders 里算过的，需提升为可读局部），`fetch`＝桥接对象
  （`McpHttpFetch` → 读全体 → `McpFetch.Fetched`）。

## 8. 文件清单

| 文件（`com.pijava.mcp` / `.oauth`） | 行数估 | 对齐 |
|---|---|---|
| `McpFetch.java`（根包） | ~40 | auth-provider.ts:1 |
| `AuthProvider.java`（根包，从 transport.http 上移＋扩 Context） | ~35 | :3-16 |
| `JdkMcpFetch.java`（根包） | ~70 | globalThis.fetch 默认 |
| `oauth/OAuthTokens…OAuthDiscoveryState`（4 record） | ~90 | types.ts:32-88 |
| `oauth/OAuthMetadataParsers`（＋2 个 parse 方法） | ~60 增 | :178-204 |
| `oauth/OAuthCallback`/`OAuthCallbackPage`/`OAuthCallbackServer` | ~200 | callback.ts |
| `oauth/flow/Pkce` ＋ `Endpoints`（纯函数） | ~120 | flow.ts:102-184 |
| `oauth/flow/TokenRequests`（token/register/exchange/refresh） | ~150 | :186-275 |
| `oauth/flow/OAuthFlow`（runFlow/authorizeMcp/stepUpScope） | ~200 | :277-411 |
| `oauth/OAuthProviders`（adaptOAuthProvider） | ~90 | :413-449 |
| `oauth/McpOAuthProvider` ＋ `OAuthStateStore`/`MemoryOAuthStateStore`/`McpOAuthState` | ~190 | provider.ts |
| 测试：脚本 fetch、Flow、Callback、Provider、端到端桥 | ~700 | 见 §9 |

每文件 ≤500 行（flow 拆三件后均 ≈200）。

## 9. 测试计划（L5）

脚本化 `McpFetch`（记录请求序列、按 URL/方法脚本化应答，含错误体、401、慢应答）。

| # | 用例 | pi 条款 |
|---|---|---|
| 1 | 发现→注册→PKCE 授权→交换：authorizationUrl 的 scope/resource 正确、状态与 verifier 落库、token 落库且**scope 补自请求**、`AUTHORIZED` | 1 |
| 2 | 刷新路径：现存 refresh_token 刷新成功 ⇒ `AUTHORIZED`、token 落库、**scope 保持旧 grant**、`refresh_token` 不回传时保住 | 1/2 |
| 3 | 并发 401 共享一次刷新（脚本：两次并发 + 一次迟到的旧 token 401 ⇒ 只刷一次，`token()` 返回新值） | 2 |
| 4 | insufficient_scope 403 ⇒ `stepUpScope`＝旧+新去重、`skipRefresh`、`REDIRECT` ⇒ `McpOAuthAuthorizationRequiredError`；**旧 token 保留** | 3 |
| 5 | 凭据绑定：同一 store 两个 serverUrl ⇒ 第二个读不到 | 4 |
| 6 | iss 校验四态：不等⇒`OAuthIssuerMismatchError`；缺且声明支持⇒报错；匹配⇒`AUTHORIZED`；缺且未声明⇒`AUTHORIZED` | 7 |
| 7 | 回调页：默认 text/plain 正文逐字；`renderPage` ⇒ HTML+no-store；另一路径 ⇒ 400 且 future 失败 `arrived on another redirect URI`；重复 state pending ⇒ 报错；无 code ⇒ 400 | 8 |
| 8 | 端到端桥：`StreamableHttpTransport` 收 401 ⇒ 经 `Context.fetch` 桥跑流 ⇒ 重试成功；403 insufficient_scope ⇒ `McpOAuthAuthorizationRequiredError`、且 `Context.token` 是**实际发出的**旧 token | 2/3 |
| 9 | 纯函数表：`selectClientAuthMethod` 六分支、`stepUpScope`、`withScope`、`secureEndpoint`（http 拒/loopback 放行）、`startAuthorization` 的 `offline_access⇒prompt=consent` 与两处不支持报错、`registerClient` 无 endpoint 报错、`tokenRequest` **错误体先于状态** | 1/7 细分 |

回调服务器用真实 loopback `HttpServer`（复用 `ScriptedHttpServer` 心法）。

## 10. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | iss 校验恒跳过 | 用例 6 |
| M2 | 刷新失败把 `OAuthError` 全吞（含 invalid_grant） | 用例 2 相关分支／case 6 组合 |
| M3 | `stepUpScope` 只取 challenged（丢 granted） | 用例 4 |
| M4 | `adaptOAuthProvider` 去掉并发共享（各自刷） | 用例 3 |
| M5 | `provider.own` 不比对 serverUrl | 用例 5 |
| M6 | 回调不校验 `path` | 用例 7 |
| M7 | `tokenRequest` 先查状态再看错误体 | 用例 9 |
| M8 | `secureEndpoint` 不拒 http | 用例 9 |

每次变异 grep 复核落地（CRLF 教训），还原后 grep 零残留＋clean 全量复跑。

## 11. 台账影响

- B160 不销号；B177 维持。新增登记候选（实施时确认）：
  - **B178**：`McpFetch` 收敛（根包）后，transport 的 `McpHttpFetch` 与它并存
    是 Java 特有（pi 只有一个）——桥接点单一，是有意偏差。
  - **B179**：`OAuthClientInformationMixed` 联合类型塌成单 record（§3.1 论证）。
- 闭环回填 docs/28 banner 与本文。

## 12. 实施记录（2026-10-07，26c56d8a）

**落地（1 feat＋1 docs）**，41 文件（22 主源新增／3 删除／4 改，14 测试）。

| 组件 | 内容 |
|---|---|
| 地基 | `McpFetch`＋`JdkMcpFetch`（根包）；`AuthProvider` 上移根包＋`Context` 补 `fetch`/`token` |
| 类型 | `OAuthTokens`/`OAuthClientMetadata`/`OAuthClientInformation`/`OAuthDiscoveryState`；`parseOAuthTokens`/`parseClientInformation` |
| flow | `OAuthPkce`、`OAuthEndpoints`（6 分支认证选择/stepUpScope/withScope/startAuthorization）、`OAuthTokenRequests`、`OAuthFlow`（runFlow/authorizeMcp/iss 校验）、`OAuthProviders`（并发单飞） |
| 回调 | `OAuthCallbackServer`（6 分支状态机）＋Page/Callback/Options |
| provider | `McpOAuthProvider`＋`McpOAuthState`/`OAuthStateStore`/`MemoryOAuthStateStore` |

**测试 115/115 绿**（原 80＋新 35：flow 5／providers 3／endpoints 9／callback 8／
provider 8／端到端桥 2）；checkstyle 0；最长文件 494 行。

**变异探针 8/8 红**（共 10 条命中）：

| # | 变异 | 红 |
|---|---|---|
| M1 | iss 校验恒跳过 | 1 |
| M2 | 刷新失败吞掉所有 OAuthError | 1 |
| M3 | stepUpScope 丢 granted | 2 |
| M4 | 去掉并发共享 | 1（见下） |
| M5 | `own` 不比对 serverUrl | 1 |
| M6 | 回调不校验 path | 1 |
| M7 | tokenRequest 先查状态再看错误体 | 2 |
| M8 | secureEndpoint 不拒 http | 1 |

**两处设计外发现**：

1. **M4 初版夹具没牙（且不稳）**：用 `CompletableFuture.runAsync` 起并发——
   公共池会把第二个任务串行化到第一个完成之后，此时「token 已被替换」护栏
   把它挡掉，于是「去掉共享」零红。改成显式虚拟线程后仍**时红时绿**：B 线程
   若在 A 完成之后才进入 `onUnauthorized`，护栏同样掩盖。**根因：事后计数
   无法区分「共享了」与「被护栏挡掉了」**。定案＝钉住**第二个线程是否进入了
   令牌临界区**（第二个闩锁 + 400 ms 上界），变异 5/5 红、还原 5/5 绿。
   教训（接续「并发夹具一律闩锁」）：**共享语义的断言必须钉在临界区入口，
   不能钉在事后计数**。
2. **`@Nullable` 不能加在外层类限定的嵌套类型前**：`@Nullable OAuthClientProvider.AddClientAuthentication`
   编译失败，须写 `OAuthClientProvider.@Nullable AddClientAuthentication`
   或 import 嵌套类型后用 `@Nullable AddClientAuthentication`（本包取后者）。

**刻意偏差（登记）**：

- **B178**：`McpFetch`（缓冲）与 transport 的 `McpHttpFetch`（流式）并存，
  pi 只有一个；桥接点唯一（`StreamableHttpTransport.bufferedFetch`）。
- **B179**：`OAuthClientInformationMixed` 联合类型塌成单 record（§3.1 论证：
  流程只读 clientId/clientSecret/tokenEndpointAuthMethod）。
- **B180**：`OAuthClientProvider` 各方法在 Java 侧同步（pi 为 `MaybePromise`）；
  `saveClientInformation` 的「能否持久化」由 `canSaveClientInformation()` 谓词
  表达（pi 靠方法存在性，用谓词才能保住「注册前先报错」的次序）。
- 实施期修正：`withScope` 把空串按缺省（JS 真值语义，clause 1 的 `scope:""` 依赖它）；
  `refreshAuthorization` 的传入 refresh_token 是**兜底不是覆盖**（除设计稿外已回源更正）。
