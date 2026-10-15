# 16 - ai 批 7 设计：共享 OAuth 回调服务器（#97）→ ChatGPT 订阅登录（#95）→ Anthropic copy-code（#96）

> 状态：**已闭环 2026-10-15**（见文末横幅）。
> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）。pi 证据一律 `git show ea448f454:<path>`。
> 路线：[`docs/08`](08-ai-layer-alignment-roadmap.md) 批 7；地图单元 map/02 #95/#96/#97（鉴权组）。
> 判据＝`bin pi` 主流发布版可达的**行为**，不是文件形状。

---

## 0. 范围

| 单元 | 内容 | 现状 | 目标 | 权重增量 |
|---|---|---|---|---:|
| #97 | 共享 loopback 回调服务器：固定/随机端口、state 校验、**品牌页**、回调-手贴**竞速**、claim-once 409、取消/超时 | 存疑（`OAuthFlow` 内嵌一次性服务器，朴素 HTML，无竞速） | 抽共享件，anthropic/openrouter（及 chatgpt，见裁决 §4.1）共用 | +0.5（存疑→对齐） |
| #95 | OpenAI **ChatGPT 订阅登录**（"Sign in with ChatGPT"，动态客户端：`dynamic_agent_client`＋`ext_agent_host_id`，回调里带**签发的 client_id**；固定端口 1455 占用即硬失败；token 响应校验 `chatgpt.tokens.use.direct`；刷新用签发 client_id） | 缺失 | 新增 `ChatGptOAuthFlow`，注册键 `openai`（pi 存 provider id） | +1（缺失→对齐） |
| #96 | Anthropic 登录法 **select**：browser（默认）/ copy_code（headless）；copy-code 用固定 `https://platform.claude.com/oauth/code/callback`，手贴 `code#state` | 缺失 | 新增专用 `AnthropicOAuthFlow` | +1（缺失→对齐） |

合计加权 **+2.5 → 205.5/219＝93.8%**（总表 816.5/1385）。

**顺手修掉的既有偏差**（不单列单元，随 #97/#96 走）：

1. Anthropic token 端点 pi 恒发 **JSON**（`anthropic.ts:76-91` postJson），Java 现状 `OAuthConfig.standard` 走**表单**；且交换体缺 `state`。专用流改正。
2. Anthropic 凭证到期时间 pi **提前 5 分钟**（`anthropic.ts:138`），Java 现状无 margin。
3. Anthropic 回调 pi 是**固定 53692 端口、`/callback` 固定路径、redirectHost 换 `localhost`、占用回落 0、再失败无服务器**（#10571）；Java 现状随机端口＋随机路径。
4. OpenRouter pi 的 token 交换发生在回调 `complete` **之内**（交换失败页面 502 可见），且有 5 分钟登录超时、30s 交换超时。

**不做**（§6）：#98 federation；ChatGPT token 的**请求时**行为差（`isChatGPTSignIn` 裁字段/usage 链接/重试）；`PI_OAUTH_CALLBACK_HOST` 之外的 settings 注入；浏览器自动打开策略变更。

---

## 1. pi 逐字证据

### 1.1 共享回调服务器 `packages/ai/src/auth/oauth/callback-server.ts`（183 行）

选项契约（`:12-43`）：

```ts
export interface OAuthCallbackServerOptions<T> {
	providerName: string;
	host: string;
	port: number;                 // 0 = 空闲端口
	path: string;
	redirectHost?: string;        // redirectUri 里与 host 不同时（如 localhost）
	state?: string;               // 不发 state 的 provider 省略
	/** 页面发送之前完成交换，页面才能展示交换失败；async (code) => code 表示稍后再换 */
	complete: (code: string) => Promise<T>;
	signal?: AbortSignal;
	timeoutMs?: number;
}
export interface OAuthCallbackServer<T> {
	redirectUri: string;
	/** complete 的结果；cancel 后 undefined；error/complete 失败/abort/超时则 reject */
	wait(): Promise<T | undefined>;
	cancel(): void;               // 除非回调已被 claim
	close(): void;
}
```

请求分派（`:78-118`，逐字要点）：

```ts
if (request.method !== "GET" || url.pathname !== options.path) {
	sendPage(response, 404, oauthErrorHtml("Callback route not found.")); return;
}
if (options.state !== undefined && url.searchParams.get("state") !== options.state) {
	sendPage(response, 400, oauthErrorHtml("State mismatch.")); return;          // 不 settle
}
if (claimed || settled) {
	sendPage(response, 409, oauthErrorHtml("This sign-in has already been handled.")); return;
}
const error = url.searchParams.get("error");
if (error) {
	const description = url.searchParams.get("error_description") ?? error;
	sendPage(response, 400, oauthErrorHtml(`${providerName} authorization failed.`, description));
	finish({ error: new Error(`${providerName} authorization failed: ${description}`) }); return;
}
const code = url.searchParams.get("code");
if (!code) {
	sendPage(response, 400, oauthErrorHtml("Missing authorization code.")); return; // 不 settle
}
claimed = true;
try {
	const value = await options.complete(code);
	sendPage(response, 200, oauthSuccessHtml(`Signed in to ${providerName}. You may now close this page.`));
	finish({ value });
} catch (error) { … sendPage(response, 502, oauthErrorHtml(`${providerName} sign-in failed.`, failure.message)); finish({error}) }
```

- 端口绑定失败**原样抛**（`:120-129`，`server.once("error", reject)` → Node `EADDRINUSE`）；调用方自行决定回落（anthropic）或硬失败（chatgpt）。
- `redirectUri = http://${redirectHost ?? host}:${address.port}${path}`，IPv6 加方括号（`:148-151`）。
- `cancel()`：`if (!claimed) finish({ value: undefined })`（`:152-154`）——已 claim 的回调即使调用方转手工输入也照样完成（夹具：`:119-137`）。
- 页面头：`content-type: text/html; charset=utf-8`、`cache-control: no-store`（`:46-49`）。

**回调/手工竞速**（`:155-183`）：

```ts
export async function waitForCallbackOrManualInput<T>(interaction, callback, prompt)
  // manual prompt 带独立 signal；无论 resolve/reject 都 callback?.cancel()
  // callback.wait() 先回非 undefined → {type:"callback",value}（manual signal abort）
  // undefined（被 cancel）→ 等 manual；manual 失败则抛
  // callback 为 undefined（无服务器）→ 只用 manual
```

夹具（`test/oauth-callback-server.test.ts`）：野请求 404／错 state 400 且不 settle／POST 404／缺 code 400／成功页含品牌三色 SVG；无 state ＋ `redirectHost: "localhost"`；complete 失败 502；error 回调 400＋reject；**只完成首个回调**（第二个 409；claim 后 cancel 仍完成）；cancel 后 wait undefined、迟到回调 409；abort「Login cancelled」、timeout「Example sign-in timed out」；端口被占 `EADDRINUSE`（**不挑端口**）；竞速四夹具（回调赢中断 prompt／手贴赢停服务器／无服务器只 prompt／prompt 失败穿透）。

### 1.2 品牌页 `packages/ai/src/utils/oauth-page.ts`（104 行）

- 深色品牌页：固定三色 logo（`#F09082/#4D9ABF/#F1BE58` 三段 SVG path），`escapeHtml` 转义 `& < > " '` 五字符；
- `oauthSuccessHtml(message)` 标题/heading「Authentication successful」；`oauthErrorHtml(message, details?)` 「Authentication failed」，details 等宽字体块。
- 夹具断言：`Authentication successful`、`Signed in to Example.`、三个 `fill="#…"`。

### 1.3 Anthropic 流 `packages/ai/src/auth/oauth/anthropic.ts`（309 行）

常量（`:14-27`）：

```ts
const CLIENT_ID = decode("OWQxYzI1MGEtZTYxYi00NGQ5LTg4ZWQtNTk0NGQxOTYyZjVl"); // 9d1c250a-e61b-44d9-88ed-5944d1962f5e
const AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
const TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
const CALLBACK_HOST = getProviderEnvValue("PI_OAUTH_CALLBACK_HOST") || "127.0.0.1";
const CALLBACK_PORT = 53692;                          // 优先固定端口（容器/SSH 转发）；占用回落空闲
const CALLBACK_PATH = "/callback";
const REDIRECT_URI = `http://localhost:${CALLBACK_PORT}${CALLBACK_PATH}`;
const COPY_CODE_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";
const SCOPES = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
```

- token 请求恒 **JSON**（`postJson` `:76-91`，30s 超时 `AbortSignal.any([signal, AbortSignal.timeout(30_000)])`）；
- 授权交换体：`grant_type/client_id/code/state/redirect_uri/code_verifier`（`:99-107`）；
- 凭证：`expires: Date.now() + expires_in*1000 - 5*60*1000`（`:138`）；
- 浏览器登录（`:140-197`）：state＝**PKCE verifier**；`startOAuthCallbackServer({port:53692}).catch(() => port 0).catch(() => undefined)`；`redirectUri = callback?.redirectUri ?? REDIRECT_URI`；`redirectHost:"localhost"`；`complete: async code => code`（交换在竞速之后）；authorize 参数序 `code=true, client_id, response_type=code, redirect_uri, scope, code_challenge, code_challenge_method=S256, state`；manual 经 `parseAuthorizationInput`（`:29-52`：URL → `#` 分割 → `code=` query → 裸 code），`parsed.state && parsed.state !== verifier` 即「OAuth state mismatch」；
- copy-code（`:200-235`）：**不起服务器**，authorize 的 redirect_uri 用 `COPY_CODE_REDIRECT_URI`，唯一 prompt「Paste the code Anthropic shows after you sign in:」placeholder `code#state`，交换时 redirect_uri 同常量；
- refresh（`:240-287`）：JSON 体仅 `grant_type=refresh_token/client_id/refresh_token`（**无 scope**），同样 5 分钟提前到期；
- 入口（`:288-299`）：先 `prompt({type:"select", message:"Select Anthropic login method:", options:[{id:"browser",label:"Browser login (default)"},{id:"copy_code",label:"Copy code login (headless)"}]})`；非 browser/copy_code 抛「Unknown Anthropic login method」。

夹具（`test/anthropic-oauth.test.ts`，`describe.sequential`）7 个：手工回调 URL 保持 `http://localhost:53692/callback`；select copy_code（断言 select 提示逐字、redirect 常量、`code#state`、JSON state）；select 取消穿透；refresh 无 scope；manual_code settle 后其 signal abort；浏览器回调 200 页面含「Signed in to Anthropic.」；**53692 被占时回落空闲端口**（hostname 仍 localhost、path `/callback`）。

### 1.4 ChatGPT 流 `packages/ai/src/auth/oauth/openai-chatgpt.ts`（309 行）

常量（`:16-29`）：

```ts
const DYNAMIC_CLIENT_ID = "dynamic_agent_client";     // 每次登录都是新客户端；签发 id 在回调 query 里
const AGENT_NAME_HINT = "Pi";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize";
const TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token";
const RESOURCE = "https://api.openai.com/v1";
const CALLBACK_PORT = 1455; const CALLBACK_PATH = "/auth/callback";
const REDIRECT_URI = `http://127.0.0.1:${CALLBACK_PORT}${CALLBACK_PATH}`;
const DIRECT_TOKEN_SCOPE = "chatgpt.tokens.use.direct";
const SCOPE = `openid profile email offline_access resource.invoke ${DIRECT_TOKEN_SCOPE}`;
const EXPIRY_MARGIN_MS = 3 * 60 * 1000;
```

- 回调必含 `code/state/client_id`（`:53-62`）；state 不等抛「OAuth state mismatch」；client_id 缺失抛「OpenAI OAuth registration callback did not contain an issued client ID」；
- 手工粘贴必须是**完整回调 URL**且 origin+path 完全一致（`:64-78`），否则「The pasted callback URL must start with …」；
- **端口 1455 被占硬失败**（`:243-251`）：只认 `EADDRINUSE`，改抛「Port 1455 is in use, probably by an unfinished login in another pi session or by the Codex CLI. Cancel that login and try again.」——不回落，否则浏览器回调会落到别的登录/Codex CLI 手里被 state mismatch 拒掉；
- `finally` 里除 `server.close()` 还 `server.closeAllConnections()`（`:288-296`）：浏览器预开的空闲连接可能附着旧服务器，下次登录的回调经旧连接到达会被旧 state 拒；
- 登录（`:233-286`）：`hostId = urn:uuid:<deviceId 小写>`（`:225-230`，非 UUID 抛「Sign in with ChatGPT requires a device ID (UUID) for this installation」，**在绑定端口/授权开始之前**）；state 与 nonce 各 32 字节 base64url（`:49-51`）；authorize 参数 `client_id=dynamic_agent_client, agent_name_hint(默认 Pi), ext_agent_host_id, response_type, redirect_uri, resource, scope, state, code_challenge, code_challenge_method, nonce`；回调与手工 **Promise.race**；
- 交换（`:183-201`，**表单**）：`grant_type=authorization_code/client_id=<签发id>/code/code_verifier/redirect_uri/resource`；响应必须是对象、`id_token` 必须是非空串（在场性检查，不读内容）；
- 响应校验（`:155-181`）：`access_token/refresh_token/scope` 都必须非空白字符串；`expires_in` 必须正有限数；scope 拆白名单后必须**含 `chatgpt.tokens.use.direct`**；凭证 `expires = now + expires_in*1000 - 3min`，带 `clientId` 与 `scopes`；
- 刷新（`:203-223`）：凭证无 clientId 抛「Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT」；表单 `grant_type=refresh_token/client_id=<签发id>/refresh_token/resource`（**无 scope 键**）；不查 id_token；scope 仍强制直连 scope，scopes 以响应替换；
- 注册名「OpenAI (ChatGPT subscription)」、`loginLabel: "Sign in with ChatGPT"`、`isSubscription: true`，挂在 **provider id `openai`** 上（`providers/openai.ts:11-24`），凭证按 provider id 存。

设备 ID 来源：交互模式 `settingsManager.getOrCreateDeviceId()`（`interactive-mode.ts:6331`）；**pi-ai CLI 用 `randomUUID`，不持久化**（`cli.ts:71-73` 注释「This dev CLI does not persist an installation ID; apps should reuse one across logins.」）。

夹具（`test/openai-chatgpt-oauth.test.ts`）7 个：注册客户端（authorize 全参数逐字＋交换表单 client_id＝签发 id＋凭证带 clientId/scopes）；agentName hint 覆盖；无签发 client_id 失败且**不打 token 端点**；未授直连 scope 失败；deviceId 缺失/非 UUID 在授权前失败；刷新缺 refresh_token 失败；刷新用签发 client_id＋`resource`、无 scope 键、scopes 替换、到期窗口 [now+(3600−180)s, …]。

### 1.5 OpenRouter 流（共用件第三消费者，`auth/oauth/openrouter.ts`）

- 端口 0、路径 `/oauth/callback/<crypto.randomUUID()>`、**无 state**（随机路径挡野请求）、`timeoutMs: 5*60*1000`；
- `complete` **内联做交换**（`:101-105`），交换 30s 超时、超时文案「OpenRouter OAuth token exchange timed out」；
- 手工解析 URL→`code=`→裸串；交换失败页走共享件 502。

---

## 2. Java 触点

| 文件 | 动作 |
|---|---|
| `auth/OAuthPages.java` | **新建**：oauth-page.ts 逐字移植（text block 模板＋SVG＋五字符转义） |
| `auth/LoopbackCallbackServer.java` | **新建**：共享服务器（start/await/cancel/close）＋回调/手工竞速 helper |
| `auth/Pkce.java` | **新建**：从 `OAuthFlow` 抽出的 verifier/challenge/base64Url（三流共用） |
| `auth/OAuthFlow.java` | **改造**：只剩 openrouter 通用 PKCE；回调走共享件（随机 UUID 路径、无 state、5min 超时、交换内联、30s 交换超时）；删内嵌 HTML/服务器 |
| `auth/AnthropicOAuthFlow.java` | **新建**：select＋browser（53692→0→无）/copy-code；JSON 交换与刷新；−5min 到期 |
| `auth/ChatGptOAuthFlow.java` | **新建**：动态客户端登录/刷新、端口 1455 硬失败、clientId/scopes 凭证、设备 ID 校验 |
| `auth/OAuthCredential.java` | **加组件** `clientId`、`scopes`（可空；旧 4 参构造保留） |
| `auth/OAuthProvider.java` | sealed 加 `Anthropic`、`ChatGpt` 两变体 |
| `auth/OAuthProviders.java` | `anthropic`→新变体；新增 `openai`→ChatGpt；openrouter/device 不变 |
| `auth/DefaultOAuthTokenRefresher.java` | switch 加两支 |
| `auth/OAuthInteraction.java` | 加 `select(message, options)`（default 编号菜单回落 prompt）＋ `LoginOption` record |
| `coding-agent …/subcommand/AuthCommand.java` | 分派两新变体；ChatGPT 登录 hints＝`UUID.randomUUID()`/「Pi」（对齐 pi-ai CLI） |
| 测试 | 新建 `LoopbackCallbackServerTest`、`AnthropicOAuthFlowTest`、`ChatGptOAuthFlowTest`；改 `OAuthFlowTest`/`OAuthProvidersTest`；凭证兼容夹具 |

---

## 3. 设计

### 3.1 `OAuthPages`（约 95 行）

```java
final class OAuthPages {
    private OAuthPages() {}
    static String success(String message) { return page("Authentication successful",
        "Authentication successful", message, null); }
    static String error(String message) { return error(message, null); }
    static String error(String message, String details) {
        return page("Authentication failed", "Authentication failed", message, details); }
    private static String page(String title, String heading, String message, String details) { … text block … }
    static String escapeHtml(String v) {
        return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;"); }
}
```

HTML/CSS/SVG 逐字搬 `oauth-page.ts` 模板（`text block` 内嵌，`${}` 换 `%s`/`String.format` 或直接拼接；转义在插值前完成）。

### 3.2 `LoopbackCallbackServer<T>`（约 210 行）

```java
final class LoopbackCallbackServer<T> {
    record Options<T>(String providerName, String host, int port, String path,
                      String redirectHost,          // null → host
                      String expectedState,         // null → 不校验
                      Duration timeout,             // null → 不超时
                      Completer<T> complete) {
        interface Completer<T> { T complete(String code, Map<String,String> query) throws Exception; }
    }
    sealed interface Outcome<T> {
        record Callback<T>(T value) implements Outcome<T> {}
        record Manual<T>(String input) implements Outcome<T> {}
    }

    static <T> LoopbackCallbackServer<T> start(Options<T> options) throws IOException; // BindException 原样抛
    String redirectUri();
    /** 等到回调结束：complete 成功→value；cancel→null；error/失败/超时→ExecutionException 包 IOException */
    CompletableFuture<T> outcome();
    void cancel();                  // !claimed 才 finish(null)
    void close();                   // finish(closed) ＋ stop(0)

    /** 对齐 waitForCallbackOrManualInput；server==null 时只走手工。 */
    static <T> Outcome<T> awaitCallbackOrManual(OAuthInteraction interaction,
            LoopbackCallbackServer<T> server, String prompt, Function<String,T> manualParser)
            throws IOException;
}
```

实现要点：

- JDK `com.sun.net.httpserver.HttpServer`（现状已用），virtual-thread executor；start 后读 `getAddress().getPort()`；`redirectUri` 用 `redirectHost??host`（127.0.0.1 无方括号，保留 IPv6 方括号分支）。
- 分派逐字按 §1.1：404/400-state/409/400-error＋settle/400-code/claim→complete→200/502。query 解析小工具（`URLDecoder`），传给 completer，供 ChatGPT 取 `client_id`。
- 状态 `AtomicBoolean claimed/settled`；`finish` 幂等；超时用一个虚拟线程 `sleep` 后 `finish(new IOException(providerName + " sign-in timed out"))`（settle 后 sleep 醒来 no-op）。
- 响应头两枚照 pi：`content-type: text/html; charset=utf-8`、`cache-control: no-store`。
- `close()` 的 `stop(0)`：JDK `ServerImpl.stop()` 会关闭全部在册连接（含空闲 keep-alive），等价 Node `closeAllConnections()`（§4.4 取证说明）。
- **竞速**：手工 prompt 跑在独立虚拟线程（`future`），回调 outcome 也包成 future：
  - 手工 future 完成（成功或失败）→ `server.cancel()`；
  - 回调先非 null → `Callback`，interrupt 手工线程（控制台 readLine 不可中断，daemon 虚拟线程自然消亡，同 pi CLI 现实）；
  - 回调 null（被手工 cancel）→ 取手工结果：成功 `Manual`，失败抛；
  - 手工先失败而回调仍在等 → 等回调（对齐 `if (manualError) throw manualError` 在 wait 之后的语义：回调先到仍成功；两边都败才抛）；
  - `InterruptedException` → 重设中断位并抛 `IOException`。
- 手工解析由各流传 `manualParser`（openrouter/anthropic/chatgpt 解析规则不同），解析异常即竞速失败（chatgpt 会在 parser 里做完整 URL 校验）。

### 3.3 `Pkce`（约 40 行）

`static String newVerifier()`、`static String challenge(String verifier)`、`static String base64Url(byte[])`——逐字搬 `OAuthFlow` 现有实现（与 pi `pkce.ts` 等价：32 字节、SHA-256、base64url 无填充）。

### 3.4 `OAuthFlow`（openrouter 通用 PKCE）改造

- `login`：`Pkce` 生成对；`start(Options{providerName:"OpenRouter", host: envOrLoopback, port:0,
  path:"/oauth/callback/"+UUID.randomUUID(), state:null, timeout:Duration.ofMinutes(5),
  complete:(code,q)->exchange(code, verifier)})`——交换**内联**（页面可见 502）；
- authorize URL 仍由 `OAuthConfig` 模板拼（openrouter 模板不变）；notify 文案照 pi（「Listening for OpenRouter OAuth callback on …」＋auth_url）；
- `awaitCallbackOrManual`：回调值直接返回；手工经 openrouter 版 `parseAuthorizationInput`（URL `code` 参→`code=`→裸串）再交换（交换请求 30s 超时，超时文案对齐）；
- `refresh`/`postForm` 保留 15s（B231）；openrouter 凭证 permanent 不会走到。
- `exchangeToken` 的 JSON/FORM 两支保留（openrouter 用 JSON）；到期语义不变（`key`→permanent）。

### 3.5 `AnthropicOAuthFlow`（约 270 行）

```java
final class AnthropicOAuthFlow {
    AnthropicOAuthFlow();
    AnthropicOAuthFlow(boolean autoOpenBrowser, Duration refreshTimeout, String callbackHost); // 测试注入
    OAuthCredential login(OAuthInteraction interaction) throws IOException;   // select → browser/copyCode
    OAuthCredential refresh(OAuthCredential credential) throws IOException;
}
```

- 常量照 §1.3；CLIENT_ID 直接写字面量（现状即如此，不再 decode）。
- `login`：`var method = interaction.select("Select Anthropic login method:",
  List.of(new LoginOption("browser","Browser login (default)"),
          new LoginOption("copy_code","Copy code login (headless)")));`
  select 抛错穿透；未知值抛 `IOException("Unknown Anthropic login method: " + method)`。
- browser：
  ```java
  LoopbackCallbackServer<String> server = null;
  try { server = start(53692); } catch (BindException e) {
      try { server = start(0); } catch (BindException e2) { server = null; }
  }
  String redirectUri = server != null ? server.redirectUri() : STATIC_REDIRECT; // http://localhost:53692/callback
  ```
  Options：providerName「Anthropic」、host＝callbackHost（env `PI_OAUTH_CALLBACK_HOST` 默认 127.0.0.1）、path `/callback`、**redirectHost `localhost`**、expectedState＝verifier、timeout null、complete 恒等。
- authorize 参数序照 pi（含 `code=true`）；auth_url notify 带 pi 的 instructions 原文。
- `awaitCallbackOrManual(i, server, "Complete login in your browser, or paste the authorization code / redirect URL here:", this::parseAuthorizationInput)`：
  - Callback(code)；Manual(input)→parse（抛 IOException state mismatch）；
  - 交换 JSON：`{grant_type:"authorization_code", client_id, code, state, redirect_uri, code_verifier:verifier}`，30s 超时；`finally server.close()`。
- copy-code：无服务器；auth_url（redirect_uri 常量）；prompt「Paste the code Anthropic shows after you sign in:」（placeholder `code#state`）；parse；交换 JSON，redirect_uri 用常量，state＝parsed.state ?? verifier。
- `parseAuthorizationInput`（照 `:29-52`）：trim 空→空；试 URI（`code`/`state` query）；含 `#` → 两段；含 `code=` → query 解析；否则裸 code。
- 响应解析：access/refresh/expires_in 必备；`expiresAtSec = now + expires_in − 300`；错误文案含 url/redirect_uri（照 pi 格式）。
- refresh：JSON `{grant_type:"refresh_token", client_id, refresh_token}`，15s 超时（B231），−5min 到期。
- HTTP 用本类一个 JSON mapper + `HttpClient`（与 `OAuthFlow` 同形，不复用其 private 件）。

### 3.6 `ChatGptOAuthFlow`（约 300 行）

```java
record LoginHints(Supplier<String> deviceId, String agentName) {
    static LoginHints cli() { return new LoginHints(() -> UUID.randomUUID().toString(), "Pi"); }
}
final class ChatGptOAuthFlow {
    OAuthCredential login(OAuthInteraction interaction, LoginHints hints) throws IOException;
    OAuthCredential refresh(OAuthCredential credential) throws IOException;
}
```

- 登录前置校验（在绑端口**之前**）：`deviceId` null 或不匹配 UUID 正则（`Pattern` CASE_INSENSITIVE）→「Sign in with ChatGPT requires a device ID (UUID) for this installation」。
- hostId＝`urn:uuid:`＋小写 UUID；state/nonce 经 `Pkce.base64Url(32 随机字节)`（`randomValue`）；verifier/challenge 经 `Pkce`。
- 端口：
  ```java
  LoopbackCallbackServer<AuthorizationResult> server;
  try { server = LoopbackCallbackServer.start(optsPort1455); }
  catch (BindException e) { throw new IOException(
      "Port 1455 is in use, probably by an unfinished login in another pi session or by the Codex CLI. "
      + "Cancel that login and try again."); }
  ```
  Options：providerName「ChatGPT」（页面文案差见 §4.1）、path `/auth/callback`、expectedState＝state、timeout null；
  completer：`new AuthorizationResult(code, require(query,"client_id")（trim 后非空）)`——缺失抛
  「OpenAI OAuth registration callback did not contain an issued client ID」。
- authorize 参数序：`client_id=dynamic_agent_client, agent_name_hint, ext_agent_host_id, response_type=code,
  redirect_uri, resource, scope, state, code_challenge, code_challenge_method=S256, nonce`。
- 竞速的 manualParser：先解析完整 URL，origin+path 必须等于 `http://127.0.0.1:1455/auth/callback`，
  否则「The pasted callback URL must start with …」；`error` 参→「ChatGPT authorization failed: …」；
  随后复用回调同款 code/state/clientId 校验。
- 交换（表单，无超时——pi 只受 signal 约束；§4.3）：grant_type/client_id=**签发id**/code/code_verifier/redirect_uri/resource；
  非 2xx 文案「OpenAI OAuth token request failed (<status>): <body>」；
  JSON 必须是对象；**id_token 必须是非空串**（仅交换时）。
- `credentialFromResponse`：access/refresh/scope 非空白；expires_in 正有限数；scope 拆分后必须含
  `chatgpt.tokens.use.direct`，否则「…grant did not include chatgpt.tokens.use.direct」；
  `new OAuthCredential(access, refresh, nowSec+expires_in−180, /*baseUrl*/null, issuedClientId, scopes)`。
- refresh：`credential.clientId()` 空→「Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT」；
  表单 grant_type/client_id/refresh_token/resource（无 scope 键）；15s；不查 id_token；其余校验同上。

### 3.7 凭证与注册

`OAuthCredential`：

```java
public record OAuthCredential(String accessToken, String refreshToken, long expiresAtEpochSec,
                              String baseUrl, String clientId, List<String> scopes) {
    public OAuthCredential(String accessToken, String refreshToken, long expiresAtEpochSec, String baseUrl) {
        this(accessToken, refreshToken, expiresAtEpochSec, baseUrl, null, List.of());
    }
    public static OAuthCredential permanent(String accessToken) { … null clientId/List.of() scopes … }
}
```

Jackson 旧文件缺两字段 → 反序列化 null/空（record 引用类型组件缺省允许 null）；新写出两字段，旧版本读盘 `FAIL_ON_UNKNOWN_PROPERTIES=false` 已配置 ⇒ 双向兼容。

`OAuthProvider`：

```java
sealed interface OAuthProvider permits Pkce, Device, Anthropic, ChatGpt {
    record Pkce(OAuthConfig config) implements OAuthProvider {}          // 仅 openrouter
    record Device(DeviceCodeConfig config) implements OAuthProvider {}
    record Anthropic() implements OAuthProvider {}
    record ChatGpt() implements OAuthProvider {}
}
```

`OAuthProviders`：`anthropic`→`new Anthropic()`；新增 `openai`→`new ChatGpt()`（键即 pi provider id；存储键随之＝`openai`，请求侧 `Credentials.resolveCredential("openai")` 既有非 anthropic→`AuthKind.BEARER` 链正好适用）；`openai-codex` device 键保留不动。

`DefaultOAuthTokenRefresher`：

```java
-> switch (spec) {
    case OAuthProvider.Pkce(OAuthConfig c) -> new OAuthFlow(c).refresh(credential);
    case OAuthProvider.Device(DeviceCodeConfig d) -> new DeviceCodeFlow(d).refresh(credential);
    case OAuthProvider.Anthropic ignored -> new AnthropicOAuthFlow().refresh(credential);
    case OAuthProvider.ChatGpt ignored -> new ChatGptOAuthFlow().refresh(credential);
};
```

`AuthCommand.login`：同型分派；ChatGPT 传 `LoginHints.cli()`（每次登录随机 UUID，对齐 `packages/ai/src/cli.ts:73`；持久安装 ID 是 coding-agent 交互模式的事，CLI 不持久化）。

`OAuthInteraction`：

```java
record LoginOption(String id, String label) {}
default String select(String message, List<LoginOption> options) throws IOException {
    // 编号菜单：逐行 "1. label"，prompt 读入；空/1 → 首项；接受编号或 id；非法重问一次？——
    // 对齐 pi CLI answerPrompt：直接返回输入串（id 由调用方校验）。默认实现拼菜单后调 prompt()。
}
```

默认实现只拼菜单再 `prompt`（pi CLI 对 select 也是原样读输入，非法 id 由流内 unknown-method 处理）；`ConsoleInteraction` 不覆写。

---

## 4. 裁决点

1. **ChatGPT 也用共享件**（map/02 #97 证据说 chatgpt 共用，锚点里它其实内嵌了一份更早的私有服务器）。用共享件，接受三处页面文案差：成功页「Signed in to ChatGPT.」vs pi「ChatGPT authentication completed. You can close this window.」；缺 client_id 走 502+settle vs pi 400+不 settle；error 页抬头「ChatGPT authorization failed:」（pi 同名，providerName 取「ChatGPT」即**一致**）。判据是登录**行为**（硬失败端口、state、竞速、凭证字段），错误页排版细节同结果（用户都看到失败原因），夹具不钉这两句。
2. **无 AbortSignal**：Java 没有贯穿取消信号。替代：openrouter 保留 5min 服务器超时（pi 有）；anthropic/chatgpt 服务器无超时（pi 也只有 signal，CLI signal 永不触发＝无限等，Ctrl-C 可中断）；竞速手工线程用中断位收尾。
3. **ChatGPT token 请求不挂超时**（pi 只受 signal 约束）；anthropic/openrouter 交换 **30s**（pi 明确）；refresh 三支保持 B231 的 15s。
4. **`closeAllConnections` 等价性**：JDK `HttpServer.stop(0)` 关闭监听并关闭全部连接（含空闲 keep-alive，`ServerImpl.stop()` 遍历 connections close），等价 Node 两句连用。夹具用「同进程二次登录不串味」（首登关闭后立刻在同端口起新服务器，旧连接被浏览器复用的场景 Java 端无法造，列手工验证项）。
5. **固定端口的环境覆盖**：支持 `PI_OAUTH_CALLBACK_HOST`（pi 有），经包私有构造器注入以便夹具；不做 settings 层注入（pi 也只 env）。
6. **随机安装 ID**：AuthCommand 用 `UUID.randomUUID()`（对齐 pi-ai CLI），不落盘；签发 client_id 已在凭证里，刷新只靠它。交互模式持久 deviceId 是 TUI 面的事，不在本批。
7. **select 默认实现**放接口 default（编号菜单拼字符串后走 `prompt`），不逼所有测试替身实现新方法；文案不进夹具。
8. **openrouter 随机路径**用 `UUID.randomUUID()`（pi 是 `crypto.randomUUID()`），形态等价；仍无 state。

---

## 5. 测试计划（镜像 pi 三组夹具）

**`LoopbackCallbackServerTest`（对应 oauth-callback-server.test.ts，约 14 例）**

1. 野路径 404；错 state 400 页面「State mismatch.」且 outcome 未完成；POST 同路径 404；缺 code 400；正确回调 200，页面含「Authentication successful」「Signed in to Example.」与三色 `fill="#F09082/#4D9ABF/#F1BE58"`；
2. 无 state＋redirectHost localhost → redirectUri `http://localhost:<port>/callback`，无 state 回调 200；
3. complete 抛错 → 502 页面含「Example sign-in failed.」＋异常信息，outcome 异常完成；
4. `error=access_denied&error_description=…` → 400 页面含描述、outcome 异常文案；
5. 双发：第二个 409；claim 后 cancel，首个 complete 仍成功 200、outcome 得值；
6. cancel 后 outcome 得 null，迟到回调 409；
7. timeout 10ms → 异常「Example sign-in timed out」；
8. 端口被占（先 bind blocker）→ start 抛 `BindException`；
9. 竞速四例：回调赢（手工线程被中断）、手贴赢（server 被 cancel，回调迟到 409）、无服务器只手工、手工 parser 抛错穿透。

**`AnthropicOAuthFlowTest`（对应 anthropic-oauth.test.ts，7 例）**——token 端点用本地 HttpServer 桩（auth 测试包内新建小桩，scripted JSON/记录请求体）：

1. select=browser＋手工粘贴完整 redirect URL：交换体 JSON、`redirect_uri=http://localhost:53692/callback`、state 字段、code；
2. select=copy_code：select 提示两选项逐字；authorize redirect_uri＝平台常量；`code#state` 粘贴；交换体 redirect_uri 常量；
3. select 抛错 → login 抛同文；未知方法 → unknown method；
4. refresh JSON 体无 scope 键；
5. 真回调：起流后对 redirectUri（hostname 换 127.0.0.1）发 GET → 交换 code=browser-code、页面 200 含「Signed in to Anthropic.」；
6. blocker 占 53692 → redirectUri hostname localhost、path `/callback`、port≠53692，登录成功；
7. 到期 margin：`expires_in=3600` → 凭证 expiresAt ∈ [now+3300, now+3300]（秒）。

**`ChatGptOAuthFlowTest`（对应 openai-chatgpt-oauth.test.ts，8 例）**

1. 全参数 authorize URL（11 个参数逐字，含固定 redirect/resource/scope/dynamic client/hostId 小写）；手工贴完整 URL（带签发 client_id）→ 交换表单字段、凭证带 clientId/scopes；
2. `LoginHints(()->uuid, "my-app")` → agent_name_hint=my-app；
3. 回调/粘贴缺 client_id → 失败且 token 桩未收到请求；
4. scope 缺直连项 → 「grant did not include chatgpt.tokens.use.direct」；
5. deviceId null 与「not-a-uuid」→ 「requires a device ID」，且 token 桩/端口绑定均未发生（用桩工厂断言未建连）；
6. 刷新响应无 refresh_token → invalid refresh_token；
7. 刷新表单用签发 client_id＋resource、无 scope 键；新 scopes 替换；expiresAt 窗口 ±1s（now+3420s）；无 id_token 也成功；
8. blocker 占 1455 → 端口占用文案（含 Codex CLI 字样），不回落。

**回归/兼容**

- `OAuthFlowTest` 改：openrouter 随机 UUID 路径、品牌页、5min 超时（注入短值）、交换内联失败 502；既有 openrouter JSON 交换夹具保留；
- `OAuthProvidersTest`：键集合含 `openai`，anthropic/openai 变体类型；
- `OAuthCredentialStoreTest` 加：旧形态 JSON（无 clientId/scopes）可读回 null/空；新形态写盘含两字段；
- `CredentialChainTest`：stored OAuth provider=openai → `AuthKind.BEARER`（防回归）；
- AuthCommand 分派走通（既有命令式夹具或新增轻量用例）。

---

## 6. 不做 / 出范围

- **#98** Anthropic workload identity federation（5 个 `ANTHROPIC_*` 变量）——后排，用户已裁决。
- ChatGPT 凭证的**请求时**行为：`isChatGPTSignIn`（非 `sk-` 前缀）裁掉 Responses 不支持字段、usage 链接提示、共享限额重试（`openai-responses.ts:38-46,226-228,328-329`、`utils/retry.ts:25,103`）——属 openai-responses 适配器单元，不属鉴权 #95；若地图无条目，闭环时登记新 B 项。
- `openai-codex`（R5/w0）与 radius/meta 等排除流不挪到共享件。
- 交互模式（TUI）的持久 deviceId、oauth-selector 组件面——coding-agent/TUI 另案。
- MCP 模块自己的 `mcp/oauth/OAuthFlow` 不动（独立 RFC 流程）。

---

## 7. 提交计划

| # | commit |
|---|---|
| 1 | `docs(ai): batch-7 design — shared callback server, ChatGPT login, anthropic copy-code`（本稿） |
| 2 | `feat(ai): shared OAuth loopback callback server with branded pages and manual race (#97)`（OAuthPages/LoopbackCallbackServer/Pkce＋OAuthFlow 改造＋夹具） |
| 3 | `feat(ai): dedicated Anthropic OAuth flow with copy-code login (#96)`（AnthropicOAuthFlow＋凭证/注册/分派；JSON 交换刷新、53692 回落） |
| 4 | `feat(ai): Sign in with ChatGPT subscription login (#95)`（ChatGptOAuthFlow＋clientId/scopes＋openai 键＋刷新分派） |
| 5 | `docs(ai): batch-7 closure — #95/#96/#97 aligned, weighted 92.7→93.8%`（04/05/06/08/map/02＋本稿横幅） |

验证：`mvn test -pl pi-java-ai`（带 `-am`）全绿；`mvn checkstyle:check`；全量 `mvn clean verify`；文件均 ≤500 行。

---

> **闭环横幅（2026-10-15）**：本批已实施并闭环，见 commits：设计 `<design-sha>`；#97 `<s97>`；#96 `<s96>`；#95 `<s95>`；
> 闭环台账 `<docs-sha>`。map/02 #95/#96 缺失→对齐、#97 存疑→对齐；ai 加权 203.0→**205.5/219＝93.8%**。
