# 19 - L1 AI OAuth／device／env 与 CLI 凭证权限对齐设计（B267 第五批：#43／#44／#45／#50／#112）

> 【背景摘要】docs/18 已闭环 retry 与 overflow 判定。本包接手 `docs/map/02-ai.md` 剩余 6 个「缺失」中的 **5 行**：OAuth 服务端 URL 白名单（#43）、Radius OAuth 校验（#44）、device-code 事件形状（#45）、请求级 provider env（#50）与凭证文件权限（#112）。Faux 余量（#80）单独成 docs/20。
>
> 文档状态：**已实施并闭环（2026-11-03）**。实施按 §9 完成，闭环记录见 §10。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。

## 0. 范围、基线与口径

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | docs/18 闭环（HEAD `c17c81d7`） |
| L1 当前账面 | **223.0/232＝96.12%**；对齐／存疑／缺失＝119／0／6；计分单位 125 |
| 本包目标 | 5 行缺失转对齐；账面到 **231.0/232＝99.57%** |
| 可执行基线 | docs/18 终态：`mvn clean verify` 实测 **6,042 测试／0 失败／0 错误／29 跳过** |
| 依赖变更 | 无（纯源码与测试） |

### 0.2 为什么下一批是 OAuth／device／env＋CLI 权限

【场景】B267 剩余 6 行分布在三个独立面：鉴权（#43／#44／#45／#50）、测试设施（#80 Faux）和凭证存储（#112）。#80 要新增 provider deferred 的请求／取回入口，属于测试设施重写；其余 5 行都在既有登录与请求选项链上做增量对齐。

【决定】本包只做 5 行增量对齐，把 #80 留给 docs/20：

| 地图行 | 能力 | 权重 | 现判定 | 本包动作 |
|---|---|---:|---|---|
| #43 | OAuth 服务端 URL 校验 | 2 | 缺失 | HTTP(S) 归一化与白名单；按 provider 配 https-only／complete URI 策略 |
| #44 | Radius OAuth discovery／device 校验 | 2 | 缺失 | 校验 authorizationEndpoint 与 verification_uri，补必填字段判据 |
| #45 | device-code＋PKCE 事件形状 | 2 | 缺失 | 结构化 `OAuthEvent`；确认开关默认 true；TUI 确认后开浏览器／复制代码 |
| #50 | 请求级 `ProviderEnv` | 1 | 缺失 | `ApiOptions` 加 `env`；新增 env 解析器；替换车道 `System.getenv` |
| #112 | pi-ai CLI 凭证文件权限 | 1 | 缺失 | 两个凭证存储在建文件与覆写前设为 `0600` |

合计权重 **8**。

### 0.3 账面影响

| 口径 | docs/18 后 | 本包后（预计） |
|---|---:|---:|
| 加权 | 223.0/232＝96.12% | **231.0/232＝99.57%** |
| 未加权 | 119/125＝95.2% | **124/125＝99.2%** |
| 对齐／存疑／缺失 | 119／0／6 | **124／0／1** |
| 计分单位 | 125 | 125（分母不变） |
| 权重 1 | 50/53＝94.3% | **52/53＝98.1%** |
| 权重 2 | 66/74＝89.2% | **72/74＝97.3%** |
| 权重 3 | 105/105＝100% | 105/105＝100% |

域表：鉴权／env **14/21＝66.7% → 21/21＝100%**；新增单元 **15/16＝93.8% → 16/16＝100%**；其余域不变。剩余缺失只有 #80（Faux 余量，权重 1）。

### 0.4 不在本包

- 不做 #80 Faux 的 prompt-cache 用量估算、增量参数分块与 deferred fetch／cancel；这些要新增 provider deferred 入口，单列 docs/20。
- 不改 #75 `DeferredHandle` 类型面；真实生产入口仍挂 B267／D203。
- 不把 `PI_OAUTH_CALLBACK_HOST`、`PI_JAVA_CODING_AGENT_DIR`、`HTTP_PROXY` 等**宿主级**环境变量改成请求级；它们不在 `ProviderRequestOptions.env` 的语义内。
- 不改 coding-agent 的 `AuthStorage`；#112 只覆盖 `pi-java-ai` 的两个凭证存储。
- 只动登录 URL 校验，不动 kimi／github-copilot 的 provider wire。

### 0.5 术语

- **服务端 URL**：OAuth 服务器响应里给出、客户端随后会展示或交给浏览器打开的地址，例如 `verification_uri`、`authorizationEndpoint`。它不受本机配置控制，因此必须白名单。
- **trustedHttpUrl**：pi 的 URL 白名单函数。只接受可解析且协议为 `http:`／`https:` 的绝对 URL；返回 WHATWG `URL.href` 形态的归一串，否则 `null`。
- **device-code 事件**：设备码登录时告诉 UI「打开哪个网址、输入哪个用户码」的结构化事件。pi 允许 provider 用 `openBrowserOnConfirm:false`／`copyCodeOnConfirm:false` 关掉确认后的自动动作，两者默认 `true`。
- **请求级 provider env**：随单次模型请求传入的环境覆盖表。顺序是「请求覆盖 → 进程环境」；用于地域、端点占位符、代理等 provider 配置，不用于 API key 鉴权。
- **POSIX 权限 `0600`**：文件仅属主可读写。Windows 上 POSIX 属性不可表达，pi 的 `chmodSync` 同样为空操作。
- **legacy notify**：本包保留的 `OAuthInteraction.notify(String)`；结构化事件没有消费者时降级成原来的字符串提示。

## 1. 判据

1. **在校验点收口**：服务端 URL 一旦进入「展示／打开」路径，先过白名单；不在解析层做二次包装。
2. **归一化可测**：归一结果固定大小写、默认端口、空路径与控制字符；只对齐 pi 用到的常见形态，WHATWG 边缘面不假装完整。
3. **事件形状加层不改签名**：保留 `notify(String)`；`OAuthEvent` 通过 default 方法降级，避免 20 余个测试交互实现一起改签。
4. **env 三态**：`ApiOptions.env` 的 `null` 表示 pi 的 `undefined`；空表不等于 `undefined`，但空值项在解析时回落进程 env（pi 用 `||`）。
5. **权限只落在凭证存储**：两个存储共用一个小工具；不把权限逻辑塞进 Jackson 写盘。
6. **不预登记 D**：只有实现暴露无法消除的 WHATWG 差异时才登记窄差。

## 2. #43 OAuth 服务端 URL 白名单（权重 2）

### 2.1 pi 证据

`packages/ai/src/auth/oauth/trusted-url.ts:1-10` 全文：

```ts
/**
 * The `http:` or `https:` URL `value` names, normalized, or null. Login flows open server-supplied URLs with the
 * platform URL handler, which would also launch a `file:` URL or a custom protocol handler. Normalizing
 * percent-encodes control characters, so the URL can also be embedded in terminal hyperlinks.
 */
export function trustedHttpUrl(value: unknown): string | null {
	if (typeof value !== "string" || !URL.canParse(value)) return null;
	const url = new URL(value);
	return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
}
```

`github-copilot.ts:243-246` 是标准用法：

```ts
	const trustedVerificationUri = trustedHttpUrl(verificationUri);
	if (!trustedVerificationUri) {
		throw new Error("Untrusted verification_uri in device code response");
	}
```

同能力在其它 flow 有两种变体：

- xAI 只允许 `https:`（`xai.ts:49-61`）：解析失败或协议不是 `https:` ⇒ `"Untrusted verification URI in xAI OAuth response"`；返回 `url.href`。
- Kimi 要求 `verification_uri` 与 `verification_uri_complete` 同时可信（`kimi-coding.ts:78-79`），展示用后者（`:252-258`）；缺任一条 ⇒ `"Invalid Kimi Code device authorization response: …"`。

### 2.2 Java 现状

- `AI/auth/DeviceCodeFlow.java:141-153`：标准 device 授权响应直接读 `verification_uri` 文本，没有协议检查。
- `AI/auth/DeviceCodeFlow.java:67-74`：把该 URI 直接拼进提示字符串；`:304-313` 的 `openBrowser` 原样交给 `Desktop.browse`。`file:`、`javascript:` 与自定义协议都会被平台处理器打开。
- `AI/auth/DeviceCodeFlow.java:104-123`：OpenAI Codex 风格使用配置里的固定 `verificationUri`，不是服务端响应，不需白名单。
- 没有与 `trustedHttpUrl` 对应的工具类。

### 2.3 改动方案

1. 新增 `AI/auth/TrustedHttpUrl.java`，只做一件事：把服务端字符串归一成可信 HTTP(S) URL，或返回 `null`。

   ```java
   static String normalize(String value) {
       // 1) 去掉首尾与中间的 TAB/CR/LF（WHATWG 直接删除这三种字符）
       // 2) 空格与其余 ISO 控制字符百分号编码（空格 → "%20"）
       // 3) new URI(encoded)；解析失败或非绝对 URL ⇒ null
       // 4) scheme 必须是 http/https（大小写不敏感）；host 必须非空 ⇒ 否则 null
       // 5) normalize() 后重建 href：scheme/host 小写、默认端口去掉、
       //    path 为空补 "/"、保留 query 与 fragment、非 ASCII 用 toASCIIString() 编码
   }
   ```

   - `URI.getHost()` 对 IPv6 返回带方括号的形态（实测 `"[::1]"`），重建时保留；只对 host 做小写。
   - 默认端口只认 `http:80` 与 `https:443`；其余原样保留。
   - 用户信息（`user:pass@`）不改写大小写；服务端 URL 出现用户信息时仍按 pi 接受。
   - `URI.normalize()` 折叠 `.`／`..` 路径段。

2. `DeviceCodeConfig` 增加两个策略枚举，并提供带策略的 `standard(...)` 重载；旧五参工厂默认「HTTP 或 HTTPS、忽略 complete URI」，既有调用点零改签：

   ```java
   public enum UriPolicy { HTTP_OR_HTTPS, HTTPS_ONLY }
   public enum CompleteUriMode { IGNORE, PREFER, REQUIRE }
   ```

   `OAuthProviders` 按 provider 配置：

   | provider | UriPolicy | CompleteUriMode | pi 依据 |
   |---|---|---|---|
   | `xai` | `HTTPS_ONLY` | `PREFER` | `xai.ts:49-61`、`:201-210` |
   | `kimi` | `HTTP_OR_HTTPS` | `REQUIRE` | `kimi-coding.ts:78-79`、`:252-258` |
   | `github-copilot` | 默认 `HTTP_OR_HTTPS` | 默认 `IGNORE` | `github-copilot.ts:243-251` |
   | `openai-codex` | 不适用 | 不适用 | 固定配置 URL |

3. `DeviceCodeFlow.requestDeviceAuthorization` 标准分支按策略取值：
   - `IGNORE`：用 `verification_uri`；
   - `PREFER`：`verification_uri_complete` 可信时用它，否则回落标准条；
   - `REQUIRE`：两条都必须可信，任一缺失或不可信 ⇒ 抛错；
   - 被选中的 URI 必须过 `TrustedHttpUrl.normalize`；校验点在 `:141-153`。
   - 失败文案统一为 `"Untrusted verification_uri in " + config.name() + " device authorization response"`，并带原始值（见 §8 裁决 4）。

4. `DeviceCodeFlow.login` 打开浏览器前只使用已归一的 `Device.verificationUri()`；`openBrowser` 不再接受未归一字符串。

5. `TrustedHttpUrl` 留在 `AI/auth`；ai 不能反向依赖 mcp，也不放进 `ai.http`。

### 2.4 测试计划（RED-first）

- 新增 `TrustedHttpUrlTest`：
  - 接受：`https://verify.example.com` ⇒ `https://verify.example.com/`；`HTTPS://EXAMPLE.COM/verify` ⇒ `https://example.com/verify`；
  - 接受：`http://ex.com:80/x` ⇒ `http://ex.com/x`；`https://ex.com:443/x` ⇒ `https://ex.com/x`；
  - 接受：`https://ex.com/a b` ⇒ `https://ex.com/a%20b`；`https://ex.com/中` ⇒ 百分号编码；
  - 拒绝：`file:///etc/passwd`、`javascript:alert(1)`、`ftp://ex.com/x`、`/x`、`http://`、空串、`null`。
- `DeviceCodeFlowTest`：
  - 通知里的 `verification_uri` 归一后带尾斜杠；
  - `file:///tmp/x` ⇒ `IOException`；
  - `PREFER` 选 complete、缺 complete 时回落；`REQUIRE` 缺 complete ⇒ `IOException`；`HTTPS_ONLY` 拒绝 `http://`。
- 变异探针：删协议判断 ⇒ `javascript:` 用例红；删尾斜杠补全 ⇒ 归一用例红；删 `HTTPS_ONLY` ⇒ xAI http 用例红。

## 3. #44 Radius OAuth 校验（权重 2）

### 3.1 pi 证据

`packages/ai/src/auth/oauth/radius.ts:54-59`（discovery）：

```ts
	const discovery = (await response.json()) as Partial<RadiusOAuthDiscovery>;
	const authorizationEndpoint = trustedHttpUrl(discovery.authorizationEndpoint);
	if (!authorizationEndpoint) {
		throw new Error(`Invalid Radius OAuth config from ${gateway}`);
	}
	return { authorizationEndpoint };
```

`radius.ts:211-218`（device 授权响应）：

```ts
	const data = (await response.json()) as Partial<DeviceAuthorizationResponse>;
	if (!data.device_code || !data.user_code || !data.verification_uri || !data.expires_in) {
		throw new Error("Radius OAuth device authorization response is missing required fields");
	}
	const verificationUri = trustedHttpUrl(data.verification_uri);
	if (!verificationUri) {
		throw new Error("Untrusted verification_uri in Radius OAuth device authorization response");
	}
```

要点：
- `authorizationEndpoint` 不是字符串、不可解析或协议不是 http(s) ⇒ 同一句 `Invalid Radius OAuth config`。
- 四个必填字段用**真值**判据：空串与数字 `0` 都算缺失。
- `verification_uri` 不可信时用独立的 `Untrusted verification_uri…` 文案。

### 3.2 Java 现状

- `AI/auth/RadiusOAuthHttp.java:44-56`：只要 `authorizationEndpoint` 是文本就原样返回；`RadiusOAuthFlow.loginWithBrowser`（`:83-112`）随后把它拼成授权 URL 并交给 `OAuthFlow.openBrowser`。
- `AI/auth/RadiusOAuthHttp.java:58-72`：只检查四个字段「是不是文本／数字」，空串与 `0` 能通过；`verification_uri` 不校验。
- `AI/auth/RadiusOAuthFlow.java:60-63`：把 `device.verificationUri()` 直接拼进提示字符串。

### 3.3 改动方案

1. `loadAuthorizationEndpoint`：读 `authorizationEndpoint` → `TrustedHttpUrl.normalize` → `null` ⇒ `IOException("Invalid Radius OAuth config from " + config.gateway())`（pi 逐字）。
2. `requestDevice`：
   - 必填判据改为「文本且非空」；`expires_in` 改为「数字且非 0」（pi 真值判据）；
   - `verification_uri` 过 `TrustedHttpUrl.normalize`；
   - `null` ⇒ `IOException("Untrusted verification_uri in Radius OAuth device authorization response")`（pi 逐字）。
3. `RadiusOAuthFlow` 浏览器流只使用已校验的 endpoint；device 流只使用已校验的 URI。不改回调服务器、不改 token 交换。

### 3.4 测试计划（RED-first）

- `RadiusOAuthFlowTest`：
  - discovery 返回 `"authorizationEndpoint":"file:///tmp/x"` 或相对路径 ⇒ `IOException`，含 `Invalid Radius OAuth config`；
  - device 返回 `"verification_uri":"file:///tmp/x"` ⇒ `IOException`，含 `Untrusted verification_uri`；
  - 空 `device_code`／`user_code` 或 `expires_in:0` ⇒ `missing required fields`；
  - 正常 `https://verify.example.com` ⇒ 归一后进入通知（尾斜杠断言）。
- 变异探针：`expires_in` 判据改回 `isNumber` ⇒ `0` 用例红；删 `verification_uri` 校验 ⇒ `file:` 用例红。

## 4. #45 device-code 事件形状（权重 2）

### 4.1 pi 证据

`packages/ai/src/auth/types.ts:137-151`：

```ts
export type AuthEvent =
	| { type: "info"; message: string; links?: readonly AuthInfoLink[] }
	| { type: "auth_url"; url: string; instructions?: string }
	| {
			type: "device_code";
			userCode: string;
			verificationUri: string;
			/** Offer best-effort browser opening on user confirmation. Defaults to true; clients may ignore it. */
			openBrowserOnConfirm?: boolean;
			/** Offer copying of userCode on user confirmation, replacing the clipboard. Defaults to true. */
			copyCodeOnConfirm?: boolean;
			intervalSeconds?: number;
			expiresInSeconds?: number;
	  }
	| { type: "progress"; message: string };
```

默认值落点是 coding-agent 的登录对话框（`login-dialog.ts:171-172`）：

```ts
			openBrowserOnConfirm: info.openBrowserOnConfirm ?? true,
			copyCodeOnConfirm: info.copyCodeOnConfirm ?? true,
```

确认动作**只消费一次**（`:331-341`）：

```ts
		if (this.deviceCodeAction && !this.inputResolver && !this.signal.aborted && this.focused &&
			kb.matches(data, "tui.select.confirm")) {
			const action = this.deviceCodeAction;
			// Consume once so a repeated Enter cannot launch multiple browsers or clipboard writes.
			this.deviceCodeAction = undefined;
			this.activateDeviceCode(action.info, action.hint);
```

### 4.2 Java 现状

- `AI/auth/OAuthInteraction.java:14-47`：只有 `void notify(String message)`；无法表达用户码、验证 URL 或确认开关。
- `AI/auth/DeviceCodeFlow.java:67-74`、`AI/auth/RadiusOAuthFlow.java:60-63`：把 URL 与用户码拼成一段文本。
- `pi-java-tui/.../LoginDialogScreen.java:36-40`：`notify` 只追加字符串；`:110-141` 空闲状态按 Enter 不做任何事。
- `pi-java-coding-agent/.../AuthCommand.java:249-260`：控制台实现只 `println`。

### 4.3 改动方案

1. 新增 `AI/auth/OAuthEvent.java`，四变体照 pi 形状；`DeviceCode` 的便利构造器默认两开关为 `true`：

   ```java
   public sealed interface OAuthEvent {
       record Link(String url, String label) {}
       record Info(String message, List<Link> links) implements OAuthEvent {}
       record AuthUrl(String url, String instructions) implements OAuthEvent {}
       record DeviceCode(String userCode, String verificationUri,
                         boolean openBrowserOnConfirm, boolean copyCodeOnConfirm,
                         Long intervalSeconds, Long expiresInSeconds) implements OAuthEvent {
           /** pi 默认：两个确认动作都开启。 */
           public DeviceCode(String userCode, String verificationUri,
                             Long intervalSeconds, Long expiresInSeconds) {
               this(userCode, verificationUri, true, true, intervalSeconds, expiresInSeconds);
           }
       }
       record Progress(String message) implements OAuthEvent {}

       /** 没有结构化消费者的实现沿用旧字符串提示。 */
       String legacyText();
   }
   ```

   `legacyText()` 的定义：`Info`／`Progress` 返回 `message`；`AuthUrl` 返回 `url`（有 `instructions` 时追加下一行）；`DeviceCode` 必须与当前 Java 文案逐字一致：

   ```text
   Open this URL in your browser and enter the code:
     {uri}
   User code: {userCode}
   ```

2. `OAuthInteraction` 保留 `notify(String)`，新增 default 方法：

   ```java
   default void notify(OAuthEvent event) {
       notify(event.legacyText());
   }
   ```

   存量实现（测试 `ScriptedInteraction`、`ConsoleInteraction`、`LoginDialogScreen`）不需改签名。

3. 事件生产点：`DeviceCodeFlow.login` 与 `RadiusOAuthFlow.loginWithDeviceCode` 改发 `OAuthEvent.DeviceCode(userCode, uri, interval, expiresIn)`（默认 true）。浏览器流的 `auth_url` 本包不迁移，继续走 `legacyText`；它们是固定配置 URL，不是 #45 的判据点。

4. TUI `LoginDialogScreen`：
   - 覆写 `notify(OAuthEvent)`；`DeviceCode` 时保存事件并渲染验证 URL、`Enter code: {userCode}` 与提示行；
   - 提示行按开关组合生成 `to open browser and copy code`／`to open browser`／`to copy code`；两个都 false 时不显示提示、不绑定 Enter；
   - 空闲状态 Enter：**只消费一次**，先 `ClipboardSupport.copy`（失败显示 `Copy code manually`），再 `Desktop.browse`（失败不打断登录）；状态文案对齐 pi：`Copying code...`、`Code copied to clipboard`、`Browser open requested`；
   - `requestInput`／`select` 开始时清空待确认事件（pi 的 `clearDeviceCodeAction`）；
   - 浏览器打开加包可见静态注入点，避免测试打开真实桌面。

5. 控制台不改：`notify(String)` 走 default 降级，输出与现状一致；pi 的 dev CLI 也不在控制台做确认动作。

### 4.4 测试计划（RED-first）

- `DeviceCodeFlowTest`：结构化交互收到 `DeviceCode`（用户码、归一 URI、interval、expires 齐全，两开关默认 true）；只实现 `notify(String)` 的交互收到旧文案。
- `RadiusOAuthFlowTest`：同上。
- `LoginFlowTest` 或新增 `LoginDialogDeviceCodeTest`：
  - `notify(DeviceCode)` 后渲染含 URL 与 `Enter code:`；
  - Enter ⇒ `ClipboardSupport.overrideForTest` 收到用户码，浏览器注入点收到一次 URL；
  - 再按 Enter 不重复；两开关都 false ⇒ Enter 不触发；
  - `openBrowserOnConfirm=false` 只复制，反之亦然。
- 变异探针：default `notify(OAuthEvent)` 改成空实现 ⇒ 控制台夹具红；一次性消费改回常驻 ⇒ 重复 Enter 用例红。

## 5. #50 请求级 ProviderEnv（权重 1）

### 5.1 pi 证据

`packages/ai/src/types.ts:149-150` 与 `:171-176`：

```ts
/** Provider-scoped environment overrides. Values take precedence over process.env. */
export type ProviderEnv = Record<string, string>;
...
	/**
	 * Provider-scoped environment values. These take precedence over process.env for
	 * provider configuration such as regional settings, endpoint placeholders, and
	 * proxy variables.
	 */
	env?: ProviderEnv;
```

`packages/ai/src/utils/provider-env.ts:41-51`：

```ts
/**
 * Resolve a provider env value from scoped overrides, normal process.env, then
 * the duplicated Bun sandbox fallback for direct pi-ai consumers.
 */
export function getProviderEnvValue(name: string, env?: ProviderEnv): string | undefined {
	return (
		env?.[name] ||
		(typeof process !== "undefined" ? process.env[name] : undefined) ||
		getBunSandboxEnvValue(name) ||
		undefined
	);
}
```

本包覆盖的读面：`anthropic-messages.ts:75-83`（`PI_CACHE_RETENTION`）、`:355-376`（federation 五变量）；`azure-openai-config.ts:27-35`、`:72-106`；`openai-completions.ts:300-304`、`openai-responses.ts:81-85`、`pi-messages.ts:365-370`。

### 5.2 Java 现状

- `AI/api/ApiOptions.java:29-38` 只有 8 个组件，没有 `env`。
- `AI/auth/EnvApiKeyResolver.java:31` 的注入式环境读取只服务凭证解析，不进入请求。
- 各车道直接读 `System.getenv`：
  - `AbstractChatApi.java:437-453`（API key 回落）；
  - `AnthropicMessagesApi.java:342,391`（缓存保留期）；
  - `AnthropicFederationSupport.java:69-97`（五变量，先读 `extra["federationEnv"]` 再读进程 env）；
  - `AzureOpenAIResponsesApi.java:145,159,182`（部署名映射、base URL、API version）；
  - `GoogleGenerativeAiApi.java:446-456`；`MistralConversationsApi.java:298-302,325-335`；
  - `OpenAICompletionsApi.java:210`；`OpenAIEmbeddingApi.java:75-87`；`OpenRouterImagesApi.java:171-183`。
- `DefaultProviders.java:457-462` 只把 federation 的 env 经 `extra["federationEnv"]` 过桥。

### 5.3 改动方案

1. `ApiOptions` 增加第 9 个组件 `@Nullable Map<String, String> env`：
   - `null` ≙ pi 的 `undefined`；非 null 时防御性 `Map.copyOf`；
   - 保留 8／7／6／5 参便利构造器（均置 `null`）；`defaults()` 与 `withIdentity` 保真传 `env`；
   - 新增 `withEnv(options, env)` 工厂，作为编码侧唯一第九参重建入口。
2. 新增 `AI/utils/ProviderEnv.java`：

   ```java
   public static String value(String name, Map<String, String> env) {
       if (env != null) {
           var scoped = env.get(name);
           if (scoped != null && !scoped.isEmpty()) {
               return scoped;
           }
       }
       var process = System.getenv(name);
       return process == null || process.isEmpty() ? null : process;
   }
   ```

   - 空串按 pi 的 `||` 回落进程 env；空白串（如 `"  "`）视为有值。
   - Bun 的 `/proc/self/environ` 兜底不移植：JVM 的 `System.getenv` 就是进程环境来源（§8 裁决 6）。

3. 逐点替换读取：

   | 文件 | 现读取 | 新读取 |
   |---|---|---|
   | `AbstractChatApi.java:442` | `System.getenv(envVar)` | `ProviderEnv.value(envVar, options.env())` |
   | `AnthropicMessagesApi.java:342,391` | `System.getenv(CACHE_RETENTION_ENV)` | 构造期存 `options.env()`，读点走 `ProviderEnv.value` |
   | `AnthropicFederationSupport.java:89-97` | `extra["federationEnv"]` → 进程 env | `options.env()` → `extra["federationEnv"]` → 进程 env（保留过桥兼容） |
   | `AzureOpenAIResponsesApi.java:145,159,182` | `System.getenv(...)` | 构造期存 `options.env()`，三处改走 `ProviderEnv.value` |
   | `GoogleGenerativeAiApi.java:450` | `System.getenv("GEMINI_API_KEY")` | `ProviderEnv.value("GEMINI_API_KEY", options.env())` |
   | `MistralConversationsApi.java:300,329` | `System.getenv(...)` | 构造期存 `options.env()`，两处改走 `ProviderEnv.value` |
   | `OpenAICompletionsApi.java:210` | `System.getenv(CACHE_RETENTION_ENV)` | 构造期存 `options.env()` |
   | `OpenAIEmbeddingApi.java:80`、`OpenRouterImagesApi.java:176` | `System.getenv(envVar)` | `ProviderEnv.value(envVar, options.env())` |

4. `DefaultProviders.apiOptions`（`:452-472`）：所有凭证（不再只 federation）的 `credential.env()` 非空时写入 `ApiOptions.env`；保留 `extra["federationEnv"]` 过桥，federation 读取顺序把 `options.env` 放在最前。

5. **重建点清单**：新增 record 组件必须逐点保真，漏一处 `env` 会静默丢失请求覆盖：

   | 文件 | 行 | 方法 |
   |---|---|---|
   | `ApiOptions.java` | 29、49、59、68、74、85 | canonical、7／6／5 参、`defaults`、`withIdentity` |
   | `ConfigurableProvider.java` | 106、132 | `effectiveOptions`、`enrichWithIdentity` |
   | `OpenRouterProvider.java` | 60 | `pinnedOptions` |
   | `ModelsJsonProvider.java` | 67、78 | `pinnedOptions`、`withInlineKey` |
   | `OpenAiCompatibleProvider.java` | 43 | `withPlaceholderKey` |
   | `DefaultProviders.java` | 212、274、284、469 | `withPerModelOverrides`、`withModelProtocol`、`withMaxRetries`、`apiOptions` |
   | `ProviderEventBridge.java` | 162、191 | `withRequestHooks`、`applyHeaderTransform` |

6. 不改 `PI_OAUTH_CALLBACK_HOST`、`PI_JAVA_CODING_AGENT_DIR`、代理变量；它们不是 per-request provider env。

### 5.4 测试计划（RED-first）

- 新增 `ProviderEnvTest`：scoped 优先于进程 env（用 `PATH` 做对照）；scoped 空串回落；空白串不回落；两边都没有 ⇒ `null`。
- `ApiOptionsTest`：9 参往返一致；`withIdentity`／`withEnv` 三者互不覆盖；旧 5／6／7／8 参 `env()` 为 `null`。
- 重建点夹具：仿 docs/13 §3.7／docs/14 §3.7，对表中每个 helper 传带 `env` 的 `ApiOptions`，断言产出 `env` 不丢。
- 车道夹具：`env={"PI_CACHE_RETENTION":"long"}` 使 Anthropic／Completions／Mistral 走长缓存；`env` 提供五个 federation 变量可命中；Azure API version 可由 `env` 提供；Google／Mistral／Embedding／Images 的 key 回落可由 `env` 提供。
- 变异探针：`ProviderEnv.value` 把 scoped 放到进程 env 之后 ⇒ 优先级夹具红；某重建点漏传 `env` ⇒ 对应夹具红。

## 6. #112 凭证文件权限（权重 1）

### 6.1 pi 证据

`packages/ai/src/cli.ts:28-33`：

```ts
function saveAuth(auth: Record<string, OAuthCredential>): void {
	// OAuth tokens: readable by the user only. `mode` applies when the file is created; chmod covers a file an older
	// version created, before the new tokens are in it.
	if (existsSync(AUTH_FILE)) chmodSync(AUTH_FILE, 0o600);
	writeFileSync(AUTH_FILE, JSON.stringify(auth, null, 2), { encoding: "utf-8", mode: 0o600 });
}
```

要点：已存在文件先 `chmod 0600` 再写；新文件用 `mode: 0600` 创建；旧版本留下的宽权限文件在写入新 token 前先收紧。

### 6.2 Java 现状

- `AI/auth/FileCredentialStore.java:134-150` 用 `FileChannel.open(CREATE/READ/WRITE)` 创建文件，不指定权限；`:183-188` 截断覆写。
- `AI/auth/OAuthCredentialStore.java:148-164` 同上；`:217-223` 截断覆写。
- 两个存储都没有 POSIX 权限处理，新文件通常继承进程 umask（常见 `0644`）。

### 6.3 改动方案

1. 新增包内工具 `AI/auth/CredentialFilePermissions.java`：

   ```java
   static void restrict(Path path) throws IOException {
       var view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
       if (view == null) {
           return; // Windows 等非 POSIX 文件系统：与 pi 在 Windows 上的 chmod 空操作等价
       }
       Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
   }
   ```

   - `UnsupportedOperationException` 按「不支持」返回，避免网络文件系统让正常保存失败；其它 `IOException` 原样抛出（pi 的 `chmodSync` 失败同样中止写入）。
2. 两个存储的 `withLockedFile` 在拿到 channel、执行 body **之前**调用 `restrict(path)`：`FileCredentialStore.java:144-149`、`OAuthCredentialStore.java:158-163`。新文件创建后、任何 token 写入前即收紧；旧文件也先收紧再覆写。
3. 不改写盘格式、锁顺序、`force(true)` 与错误包装；不改 coding-agent 的 `AuthStorage`。

### 6.4 测试计划（RED-first）

- 新增 `CredentialFilePermissionsTest`：POSIX 支持时新文件为 `rw-------`；预置 `0644` 文件写后变 `rw-------`；非 POSIX 环境写入不抛异常（不做权限断言）。
- `FileCredentialStoreTest`／`OAuthCredentialStoreTest` 增补同样的 POSIX 门控断言。
- 变异探针：删 `restrict` 调用 ⇒ POSIX 用例红；把 `restrict` 放到 body 之后 ⇒ 「旧文件先收紧再写」用例在模拟中断下红。

> 注：本机是 Windows，POSIX 断言会在本机跳过；实现必须保留 Linux CI 可执行用例，并由 Windows 的「不抛异常」用例守住回归。

## 7. 测试与验证（包级）

1. **RED-first**：先落 §2.4／§3.4／§4.4／§5.4／§6.4 的夹具，确认红；再改实现，确认绿。
2. 模块验证：`mvn test -pl pi-java-ai -am`；TUI 改动跑 `-pl pi-java-tui -am`；coding-agent 改动跑 `-pl pi-java-coding-agent -am`。必须带 `-am`，防止拾旧构件。
3. 全量：`mvn clean verify`，0 失败／0 错误，Checkstyle 0、SpotBugs 0；测试数不低于 6,042＋本包新增。
4. 下游冒烟：`DefaultProvidersTest`、`ProviderEventBridge` 夹具、`LoginFlowTest` 全绿；新增 `env` 导致既有重建点夹具变红时按真实行为修正，不掩盖。
5. 真实终端冒烟：用本地假 device 授权服务器走一次 TUI 登录，验证 URL 展示与 Enter 确认；无桌面环境只验证剪贴板注入点。
6. 台账回填：`docs/map/02-ai.md` 五行改「对齐」；`docs/04` 的 B267；`docs/06` 状态；docs/19 §10。

## 8. 风险与裁决

1. **WHATWG 归一化与 JDK `URI` 不等价**
   【场景】`new URL(value).href` 会处理反斜杠、IDN（转 punycode）、特殊 scheme 的端口与点段；JDK `URI` 在这些边缘面不同。
   【决定】只对齐登录流实际会遇到的形态：http(s) 校验、大小写、默认端口、空路径、空格／控制字符、非 ASCII 百分号编码。IDN 与反斜杠不强行模拟。
   【风险/代价】[内部，模型不可见] 极端 URL 的展示串可能与 pi 不同；若实现暴露不可消除的缺口，按实际材质登记 D214，设计阶段不预设。

2. **xAI 的 https-only 与 Kimi 的 complete URI**
   【场景】通用白名单允许 http/https，而 xAI 只允许 https；Kimi 要求两条 URI 同时可信并展示 complete。
   【决定】`UriPolicy` 给 xAI 配 `HTTPS_ONLY`；`CompleteUriMode` 给 Kimi 配 `REQUIRE`、xAI 配 `PREFER`、GitHub Copilot 保持默认 `IGNORE`。
   【风险/代价】[内部／用户可见] 配置面多两个枚举；Kimi 缺 complete 会从「静默用标准 URL」变成登录失败，这是 pi 的既有要求。

3. **登录错误文案不逐字复制三家**
   【场景】pi 的 GitHub Copilot、xAI、Kimi 对不可信 URL 各有一句文案；Java 把三家收敛到同一个 `DeviceCodeFlow`。
   【决定】统一为 `Untrusted verification_uri in {provider} device authorization response`，并带原始值。
   【风险/代价】[用户可见，非模型可见] 文案与 pi 各 flow 不完全逐字；若审核要求逐字，可在 `DeviceCodeConfig` 增加 per-provider 模板，不改变判据。

4. **结构化事件是加层而不是改签名**
   【场景】`OAuthInteraction` 被 20 余个测试交互实现与两个生产实现使用；直接改签名制造大面积机械改动。
   【决定】保留 `notify(String)`，新增 default `notify(OAuthEvent)`；结构化消费者覆写后者，未覆写者自动降级。
   【风险/代价】[内部，模型不可见] 忘记覆写 `notify(OAuthEvent)` 的实现会静默退回字符串；用 TUI 夹具钉住覆写。

5. **请求级 env 的语义与漏传风险**
   【场景】pi 的 `getProviderEnvValue` 有 Bun 沙箱兜底；Java 新增 `ApiOptions` 组件时，真正易漏的是 `withXxx` 类重建点。
   【决定】不移植 Bun 兜底（JVM 的 `System.getenv` 即进程来源）；§5.3 列出全部 15 个重建点，并要求每个 helper 的夹具断言 `env` 保真。
   【风险/代价】[内部，模型不可见] 任一重建点漏传会让该路径静默退回进程 env；夹具是唯一防线。

6. **Windows 无法表达 POSIX `0600`**
   【场景】`Files.getFileAttributeView(PosixFileAttributeView.class)` 在 Windows 返回 `null`。
   【决定】跳过权限设置；与 pi 在 Windows 上 `chmodSync` 的空操作等价。Linux CI 用 `Assumptions` 跑真实权限断言。
   【风险/代价】[内部，模型不可见] Windows 用户仍依赖默认 ACL；本包不自造 pi 没有的 ACL 策略。

7. **API key 回落仍是 Java 便利路径**
   【场景】Java 车道的 `System.getenv` 回落包含 API key；pi 的 key 由 auth 层解析，`env` 只用于 provider 配置。
   【决定】统一用 `ProviderEnv.value` 读取，使请求覆盖也能影响 key 回落；这是 Java 便利路径的超集，不改变 pi 的鉴权层级。
   【风险/代价】[内部，模型不可见] 若未来要求「env 不得携带 key」，需在 auth 层显式拒绝；当前没有这种约定。

## 9. 实施步骤（获批后）

1. #43：新增 `TrustedHttpUrl`；`DeviceCodeConfig` 加两个策略枚举与重载；`OAuthProviders` 配 xai／kimi；改 `DeviceCodeFlow` 取值与校验；补两个测试类。
2. #44：改 `RadiusOAuthHttp` 两处校验与必填判据；补 `RadiusOAuthFlowTest`。
3. #45：新增 `OAuthEvent`；`OAuthInteraction` 加 default；改两个 device 生产点；实现 `LoginDialogScreen` 确认动作与一次性消费；补 ai／TUI 夹具。
4. #50：`ApiOptions` 加 `env` 与构造器／工厂；新增 `ProviderEnv`；替换 §5.3 的读取点；逐个补 15 个重建点；`DefaultProviders` 写 `credential.env()`；补测试与夹具。
5. #112：新增 `CredentialFilePermissions`；两个存储接入；补权限夹具。
6. 跑模块与全量验证；回填台账并按实际结果写 §10。
7. Commit 建议：
   - `feat(ai): add trusted OAuth URL validation`
   - `feat(ai): validate Radius OAuth endpoints`
   - `feat(ai): emit structured device-code auth events`
   - `feat(ai): add request-scoped provider env`
   - `feat(ai): restrict credential file permissions`
   - `docs(ai): close docs/19 oauth env permissions package`

## 10. 闭环记录

### 10.1 落地内容

- **#43**：新增 `AI/auth/TrustedHttpUrl.java`（绝对 HTTP(S)、host 非空、scheme/host 小写、默认端口省略、空路径补 `/`、空格／控制字符百分号编码、非 ASCII `toASCIIString`、点段折叠、IPv6 方括号保留）。`DeviceCodeConfig` 增加 `UriPolicy`／`CompleteUriMode` 与带策略的 `standard(...)` 重载；`OAuthProviders` 配 `xai`（`HTTPS_ONLY`＋`PREFER`）与 `kimi`（`HTTP_OR_HTTPS`＋`REQUIRE`）；`DeviceCodeFlow` 在设备授权响应处按策略取值并统一抛出 `Untrusted verification_uri in {provider} device authorization response: {raw}`。
- **#44**：`RadiusOAuthHttp.loadAuthorizationEndpoint` 对 `authorizationEndpoint` 过 `TrustedHttpUrl`，不可信 ⇒ pi 逐字 `Invalid Radius OAuth config from {gateway}`；`requestDevice` 四字段改真值判据（空串与数字 `0` 缺失）、`verification_uri` 白名单，不可信 ⇒ pi 逐字 `Untrusted verification_uri in Radius OAuth device authorization response`。
- **#45**：新增 `AI/auth/OAuthEvent.java`（`Info`／`AuthUrl`／`DeviceCode`／`Progress`＋`legacyText()`）；`OAuthInteraction.notify(OAuthEvent)` 默认降级到字符串通道；`DeviceCodeFlow` 与 `RadiusOAuthFlow` 发结构化 `DeviceCode`（两确认开关默认 `true`）；TUI `LoginDialogScreen` 渲染 URL／`Enter code:`／提示行，Enter 只消费一次，先复制再打开浏览器，失败不打断登录，两开关都 false 时不绑定 Enter。
- **#50**：`ApiOptions` 增加第九组件 `@Nullable Map<String,String> env`，新增 `withEnv`，8／7／6／5 参构造与 `defaults()` 置 `null`；新增 `AI/utils/ProviderEnv.java`（scoped → process，空串回落、空白串有值、无 Bun 兜底）；`AbstractChatApi`、Anthropic／Completions／Mistral 缓存保留期、Azure 三处、Google／Mistral／Embedding／Images key、Anthropic federation 全部改走该解析器；15 个重建点逐点保真；`DefaultProviders.apiOptions` 把所有非空 `credential.env()` 投影进 `ApiOptions.env`，federation 仍保留 `extra["federationEnv"]` 过桥且读取顺序为 `options.env → extra → process`。
- **#112**：新增 `AI/auth/CredentialFilePermissions.java`（POSIX `rw-------`，非 POSIX no-op，`UnsupportedOperationException` 按不支持处理）；两个凭证存储的 `withLockedFile` 在持有通道／文件锁后、执行 body 前收紧权限。

### 10.2 验证证据

| 项 | 结果 |
|---|---|
| RED-first | #43 `TrustedHttpUrlTest` 12 新＋`DeviceCodeFlowTest` +6（共 15 个用例先红）；#44 `RadiusOAuthFlowTest` +6（6 红）；#45 AI +2（结构化事件 2 红）＋TUI `LoginDialogDeviceCodeTest` 6（6 红）；#50 `ProviderEnvTest`／`ApiOptionsEnvTest`／`ProviderEnvLaneTest` 与重建点夹具（先编译红、优先级 1 红）；#112 编译红后 POSIX 断言按平台跳过。本包净增 **56** 测 |
| 变异探针 | 删 `HTTPS_ONLY` ⇒ xAI http 用例红；删尾斜杠补全 ⇒ 归一样例红；`expires_in` 回退 `isNumber` ⇒ `0` 用例红；`notify(OAuthEvent)` 改空实现 ⇒ 字符串降级夹具红；去掉一次性消费 ⇒ 重复 Enter 用例红；`ProviderEnv` 优先级反转 ⇒ scoped 用例红（均恢复后绿）。#112 的删 `restrict` 探针只能在 POSIX 上观察，Windows 本机跳过（设计 §6.4 注） |
| AI 模块 | `mvn test -pl pi-java-ai -am`：**2,031 测／0 失败／0 错误／4 跳过**（含 4 个 POSIX 门控） |
| 下游冒烟 | `DefaultProvidersTest`／`DefaultProvidersIdentityTest`／`ProviderEventBridgeTest`／`LoginFlowTest` 全绿 |
| 全量 | `mvn clean verify`：**6,098 测／0 失败／0 错误／33 跳过**（基线 6,042，本包净增 56）；Checkstyle 0 违规、SpotBugs `BugInstance size is 0`；BUILD SUCCESS（18:41） |
| 真实终端冒烟 | 未执行真实 TUI 会话；由 `LoginDialogDeviceCodeTest` 的渲染／确认／失败路径夹具覆盖（无真实桌面与剪贴板注入） |

### 10.3 账面

| 口径 | docs/18 后 | docs/19 后 |
|---|---:|---:|
| 加权 | 223.0/232＝96.12% | **231.0/232＝99.57%** |
| 未加权 | 119/125＝95.2% | **124/125＝99.2%** |
| 对齐／存疑／缺失 | 119／0／6 | **124／0／1** |
| 鉴权／env | 14/21＝66.7% | **21/21＝100%** |
| 新增单元 | 15/16＝93.8% | **16/16＝100%** |
| 权重 1／2／3 | 50/53／66/74／105/105 | **52/53／72/74／105/105** |

剩余缺失仅 #80（Faux 余量，权重 1）。

### 10.4 裁决与偏差

- D **不适用**：本包未暴露无法消除的 WHATWG 差异；IDN／反斜杠按设计不模拟，未登记新 D。
- 文案统一：三家 provider 不可信 URL 收敛为同一句（§8 裁决 3），并附原始值。
- 测试接缝：`cacheRetentionEnv()`（Anthropic／Completions／Mistral）与 Azure 三个 `resolve*` 改为包可见，供夹具直接断言读取点；不是行为逻辑复制。`OpenAICompletionsApi` 已恰在 500 行上限，压缩了既有 Javadoc 以容纳新字段／方法。
- `LoginFlowTest.confirmCurrentOverlay` 增加 `await(overlay != null)`：原有 `currentScreen` 与 `host.open` 之间存在测试时序竞态，本包跑绿时偶发空指针；仅测试加固，无生产改动。
- TUI 生产路径仍使用 `DeviceCodeFlow` 的既有 `autoOpenBrowser=true`：因此浏览器可能在事件到达时自动打开一次，用户再按 Enter 会再次打开一次。设计 §2.3.4 仍要求 `login` 打开已归一 URI，本包未改该开关；按设计范围保留，列为后续可优化点（pi 的 flow 不自动打开、由 UI 确认后打开）。
- #112 的 POSIX 权限断言与删 `restrict` 探针在本机（Windows）按设计跳过；Linux CI 承担真实断言。
- 与设计的其它偏差：无。
