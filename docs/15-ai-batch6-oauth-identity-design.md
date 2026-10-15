# 15 ai 批 6 详稿：OAuth 车道行为（B81 Claude Code 伪装三件套 ＋ B231 刷新取消安全/15s 超时）

> 锚点：pi `ea448f454`（v1.1.0-22，2026-10-11）。取证只走 `git show ea448f454:<path>`。
> 路线：[`docs/08`](08-ai-layer-alignment-roadmap.md) §3 批 6。台账：`docs/04` §3.2 B81/B231。
> 日期：2026-10-15。

## 0. 范围一览

| 条 | 内容 | pi 出处（ea448f454） |
|---|---|---|
| B81-a | OAuth 请求的**系统提示前缀块** `"You are Claude Code, Anthropic's official CLI for Claude."` | `packages/ai/src/api/anthropic-messages.ts:1168-1188` |
| B81-b | **工具名双向映射**：出站 `toClaudeCodeName`（3 处）＋入站 `fromClaudeCodeName`（1 处） | `:102-132`、`:728`、`:1347`、`:1443`、`:1603` |
| B81-c | **身份 beta 对** `claude-code-20250219`,`oauth-2025-04-20`（beta 序列首）＋伪装版本号 `2.1.280` | `:96`、`:1106` |
| B231-a | 已开始的 OAuth 刷新**不怕调用方取消**，轮转 token 必落盘 | `packages/ai/src/auth/resolve.ts:104-157`；commit `bde882c74`；`models.ts:619-633` |
| B231-b | 刷新 HTTP **15s 超时** | `auth/resolve.ts:105-106`（`AbortSignal.timeout(15_000)`） |

已有、**不重写**：`sk-ant-oat` 值判定、Bearer 头、`user-agent: claude-cli/…`＋`x-app: cli`
两枚身份头（`AnthropicMessagesApi.java:79-90`，包 A0 步7/步8）；OAuth 分支不挂会话亲和头
（B103）；文件锁内读-改-写与 5 分钟临期窗口（`StoredOAuthCredentials`，包 B1）。

本批**只动 `pi-java-ai` 一个模块**。

---

## 1. pi 取证（逐字）

### 1.1 版本常量与工具名表（`anthropic-messages.ts:96-132`）

```ts
// Stealth mode: Mimic Claude Code's tool naming exactly
const claudeCodeVersion = "2.1.280";

// Claude Code 2.x tool names (canonical casing)
const claudeCodeTools = [
	"Read", "Write", "Edit", "Bash", "Grep", "Glob",
	"AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "KillShell",
	"NotebookEdit", "Skill", "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch",
];

const ccToolLookup = new Map(claudeCodeTools.map((t) => [t.toLowerCase(), t]));

// Convert tool name to CC canonical casing if it matches (case-insensitive)
const toClaudeCodeName = (name: string) => ccToolLookup.get(name.toLowerCase()) ?? name;
const fromClaudeCodeName = (name: string, tools?: Tool[]) => {
	if (tools && tools.length > 0) {
		const lowerName = name.toLowerCase();
		const matchedTool = tools.find((tool) => tool.name.toLowerCase() === lowerName);
		if (matchedTool) return matchedTool.name;
	}
	return name;
};
```

语义：

- 出站：名字小写命中 17 枚常量表 ⇒ CC 规范大小写；不命中（MCP 工具、`__pi_deferred_placeholder__`）原样。
- 入站：**只**在当前工具集里做大小写不敏感匹配，命中则回到本仓工具的真名；不命中（模型乱开
  `KillShell` 之类）原样透传，由工具分发层按未知名失败。

### 1.2 客户端分支（`:978-1035`）

```ts
function isOAuthToken(apiKey: string): boolean {
	return apiKey.includes("sk-ant-oat");
}
```

```ts
	// OAuth: Bearer auth, Claude Code identity headers
	if (apiKey && isOAuthToken(apiKey)) {
		const client = new PiAnthropic({
			apiKey: null,
			authToken: apiKey,
			baseURL: model.baseUrl,
			dangerouslyAllowBrowser: true,
			fetch,
			defaultHeaders: mergeClientHeaders(
				{
					accept: "application/json",
					"anthropic-dangerous-direct-browser-access": "true",
					"user-agent": `claude-cli/${claudeCodeVersion}`,
					"x-app": "cli",
				},
				model.headers,
				optionsHeaders,
			),
		});
		return { client, isOAuthToken: true };
	}
```

> Java 现状：`accept`／`anthropic-dangerous-direct-browser-access` 经裁决不移植（浏览器场景
> 产物，`原 docs/43 §6`）；Bearer＋两身份头已在。**差**：版本号 Java 钉的是 `2.1.251`
> （`AnthropicMessagesApi.java:148`），锚点是 **`2.1.280`**，随本批 bump。

### 1.3 beta 列表与顺序（`:1083-1124`）

```ts
	const features = [];
	if (isOAuthToken) features.push("claude-code-20250219", "oauth-2025-04-20");
	if (shouldUseFineGrainedToolStreamingBeta(model, context)) features.push(FINE_GRAINED_TOOL_STREAMING_BETA);
	if (model.reasoning && options?.thinkingEnabled === true
		&& (options.interleavedThinking ?? true)
		&& model.compat?.forceAdaptiveThinking !== true) {
		features.push(INTERLEAVED_THINKING_BETA);
	}
	if (shouldUseServerSideFallbackBeta(model)) features.push(SERVER_SIDE_FALLBACK_BETA);
	if (model.compat?.supportsMidConvoEffort === true) {
		features.push(MID_CONVERSATION_OUTPUT_CONFIG_BETA, THINKING_BINDING_CONTROLS_BETA);
	}
	if (nativeToolChanges) features.push(INLINE_TOOLS_BETA);
	return [...new Set(features)];
```

两个既有行为要点：

1. OAuth 对恒在**最前**（除显式 `anthropic-beta` 配置整组替换外，见批 4 裁决③）。
2. Java 现序是 interleaved→inline→fine-grained→fallback→managed×2（`AnthropicRequestBuilder.java:155-182`），
   与 pi 序不同；既有夹具全用 `contains` 不断言序（已核 6 个文件），本批按 pi 序重排为
   **oauth×2 → fine-grained → interleaved → fallback → managed×2 → inline**，顺带消除
   OAuth＋其他 beta 同发时的逐字节差。

### 1.4 系统提示前缀（`:1168-1188`）

```ts
	// For OAuth tokens, we MUST include Claude Code identity
	if (isOAuthToken) {
		params.system = [
			{
				type: "text",
				text: "You are Claude Code, Anthropic's official CLI for Claude.",
				...(cacheControl ? { cache_control: cacheControl } : {}),
			},
		];
		if (initialSystemText) {
			params.system.push({
				type: "text",
				text: sanitizeSurrogates(initialSystemText),
				...(cacheControl ? { cache_control: cacheControl } : {}),
			});
		}
	} else if (initialSystemText) {
		params.system = [
			{ type: "text", text: sanitizeSurrogates(initialSystemText),
			  ...(cacheControl ? { cache_control: cacheControl } : {}) },
		];
	}
```

要点：

- OAuth 时 `system` **恒为数组且首块恒为前缀**（无前导系统消息也发，只有前缀一块）；
  非 OAuth 且无系统文本 ⇒ 不发 `system`（Java 现状）。
- 两块都按同一 `cacheControl` 挂/不挂断点；前缀是 ASCII 字面量，**不过** `sanitizeSurrogates`；
  第二块才是净化后的初始系统文本。

### 1.5 工具名出站三处、入站一处

1. **请求级工具表/内联定义共用的 `convertTools`**（`:1603`）：
   ```ts
   name: isOAuthToken ? toClaudeCodeName(tool.name) : tool.name,
   ```
   `buildParams` 两支（普通当前表 ＋ native 支的 initialTools）都经它；`tool_addition`
   的 definition 是它在 `convertMessages` 里的回调（`:1213`）。**占位符
   `__pi_deferred_placeholder__` 不经过映射**（独立构造，`:1203-1232`）。
2. **native 支的 `tool_removal` 引用**（`:1347`）：
   ```ts
   tool: { type: "tool_reference", name: isOAuthToken ? toClaudeCodeName(tool.name) : tool.name },
   ```
3. **历史 assistant 消息的 `tool_use` 块**（`:1443`）：
   ```ts
   blocks.push({ type: "tool_use", id: block.id,
       name: isOAuthToken ? toClaudeCodeName(block.name) : block.name, input: block.arguments ?? {} });
   ```
4. **入站 SSE `content_block_start` 的 tool_use**（`:578`、`:725-735`）：
   ```ts
   const currentTools = getCurrentTools(normalizedContext.messages);   // :578，整轮一次
   ...
   name: isOAuth
       ? fromClaudeCodeName(event.content_block.name, currentTools)
       : event.content_block.name,
   ```

> 注：tool_result 只带 `tool_use_id`（无名字），不涉及映射。

### 1.6 B231：取消安全的刷新（`auth/resolve.ts:101-157`）

```ts
const DEFAULT_OAUTH_REFRESH_TIMEOUT_MS = 15_000;

/**
 * Refresh a stored OAuth credential under the credential-store lock and persist
 * the result before the lock is released. `needsRefresh` is re-checked under the
 * lock, so concurrent callers and processes refresh only once.
 *
 * `signal` cancels only the wait for the lock. Once a refresh starts, the
 * provider may already have rotated the refresh token, so the refresh and its
 * persistence ignore `signal` and are bounded only by a timeout. Otherwise a
 * cancelled caller could discard the only valid refresh token. Callers that
 * must return promptly on cancellation race this promise with their signal.
 */
export async function refreshStoredOAuthCredential(
	credentials: CredentialStore, providerId: string, oauth: OAuthAuth,
	needsRefresh: (credential: OAuthCredential) => boolean,
	signal: AbortSignal,
): Promise<OAuthCredential | undefined> {
	let post: Credential | undefined;
	const lockWait = new AbortController();
	const cancelLockWait = () => lockWait.abort(signal.reason);
	signal.addEventListener("abort", cancelLockWait, { once: true });
	if (signal.aborted) cancelLockWait();
	try {
		post = await credentials.modify(
			providerId,
			async (current) => {
				signal.removeEventListener("abort", cancelLockWait);
				signal.throwIfAborted();                       // 进锁前：取消仍有效
				if (current?.type !== "oauth") return undefined;
				if (!needsRefresh(current)) return undefined;  // 锁内复核
				try {
					return await oauth.refresh(current, AbortSignal.timeout(DEFAULT_OAUTH_REFRESH_TIMEOUT_MS));
				} catch (error) {
					throw new ModelsError("oauth", `OAuth refresh failed for ${providerId}`, { cause: error });
				}
			},
			{ signal: lockWait.signal },                        // 调用方信号只取消等锁
		);
	} ...
}
```

调用方（`models.ts:619-633`）与请求鉴权（`resolve.ts:169-200`）共用此路。commit
`bde882c74` 的夹具把语义钉死：**刷新函数内 abort 调用方 → 调用方 Promise reject
AbortError，但落盘最终是新 refresh token**（`packages/ai/test/models-runtime.test.ts`
两条 `persists an OAuth refresh that started before …`）。修复前的旧行为是把取消传给刷新
HTTP、丢弃轮转结果，ChatGPT 那类轮转 refresh token 的 provider 随后必报
`refresh_token_invalidated`。

---

## 2. Java 现状（触点盘点）

| 位置 | 现状 | 批 6 动作 |
|---|---|---|
| `protocol/AnthropicMessagesApi.java:79-90` | 值判 `sk-ant-oat`＋`AuthKind.OAUTH` ⇒ Bearer＋两身份头；版本 `2.1.251` | 版本 bump `2.1.280`；构造期 `boolean oauth` 存字段（现是局部变量） |
| `protocol/AnthropicRequestBuilder.java:119-137` | 只有初始系统文本时发**一块** system | OAuth 时恒发，前缀块为首 |
| `protocol/AnthropicRequestBuilder.java:155-200` | beta 五段按旧序拼装 | OAuth 对插最前＋全序按 pi 重排 |
| `protocol/AnthropicToolWire.java` `addTools/toDefinitionJson/toolReferenceBlock/toolDefinitionBlock` | 工具名一律 `td.name()` | 三处加 `oauth` 形参，名走 mapper |
| `protocol/AnthropicMessageConverter.java:62-66` | 历史 tool_use 名 `tu.name()` | 加 `oauth` 形参 |
| `AnthropicMessagesApi.java:338-349`（入站 tool_use 起点） | 名直接取 `tu.name()` | `fromClaudeCodeName(name, currentTools)` |
| `auth/StoredOAuthCredentials.java:44-56` | 刷新在调用方线程的 `store.modify` lambda 内；线程被中断 ⇒ HTTP 抛 ⇒ mutation 抛 ⇒ **轮转结果丢弃** | 整段 modify 挪到**脱离调用方中断状态的虚拟线程**，调用方经可中断的 future 等待 |
| `auth/OAuthFlow.java:100-110,293-300`、`DeviceCodeFlow.java:65-80` | 刷新 POST **无超时**（HttpClient 无默认） | 刷新请求 `.timeout(Duration.ofSeconds(15))` |

---

## 3. 设计

### 3.1 新类 `ClaudeCodeNames`（`protocol` 包，包私有）

```java
package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import com.pijava.ai.api.ToolDefinition;

/**
 * B81：OAuth（Claude Code 伪装）车道的工具名双向映射。
 * pi api/anthropic-messages.ts:102-132 的逐字移植：17 枚 CC 2.x 规范名常量表。
 */
final class ClaudeCodeNames {

    private ClaudeCodeNames() {}

    /** pi {@code anthropic-messages.ts:96}。同时用于 user-agent（{@link AnthropicMessagesApi}）。 */
    static final String CLAUDE_CODE_VERSION = "2.1.280";

    /** OAuth 系统提示前缀（{@code :1172}，ASCII 字面量，不过 surrogate 净化）。 */
    static final String SYSTEM_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude.";

    /** OAuth 身份 beta 对（{@code :1106}，恒在 beta 序列最前）。 */
    static final List<String> OAUTH_BETAS = List.of("claude-code-20250219", "oauth-2025-04-20");

    // 小写名 → 规范名；Map.of 最多 10 对，故用 Map.ofEntries。
    private static final Map<String, String> LOOKUP = Map.ofEntries(
        Map.entry("read", "Read"), Map.entry("write", "Write"), Map.entry("edit", "Edit"),
        Map.entry("bash", "Bash"), Map.entry("grep", "Grep"), Map.entry("glob", "Glob"),
        Map.entry("askuserquestion", "AskUserQuestion"),
        Map.entry("enterplanmode", "EnterPlanMode"), Map.entry("exitplanmode", "ExitPlanMode"),
        Map.entry("killshell", "KillShell"), Map.entry("notebookedit", "NotebookEdit"),
        Map.entry("skill", "Skill"), Map.entry("task", "Task"),
        Map.entry("taskoutput", "TaskOutput"), Map.entry("todowrite", "TodoWrite"),
        Map.entry("webfetch", "WebFetch"), Map.entry("websearch", "WebSearch"));

    /** 出站（pi {@code toClaudeCodeName}）：小写命中 ⇒ CC 规范名；否则原样。 */
    static String toWire(String name, boolean oauth) {
        return oauth ? LOOKUP.getOrDefault(name.toLowerCase(Locale.ROOT), name) : name;
    }

    /** 入站（pi {@code fromClaudeCodeName}）：仅在当前工具集内大小写不敏感回真名；否则原样。 */
    static String fromWire(String name, List<ToolDefinition> currentTools) {
        if (currentTools != null) {
            for (ToolDefinition tool : currentTools) {
                if (tool.name().equalsIgnoreCase(name)) {
                    return tool.name();
                }
            }
        }
        return name;
    }
}
```

### 3.2 `AnthropicMessagesApi`：版本号、字段、入站 demap

- `CLAUDE_CODE_VERSION` 常量删除，两处引用改 `ClaudeCodeNames.CLAUDE_CODE_VERSION`；
  新字段 `private final boolean oauth;`（构造器现算的 `oauth` 落字段，`bearer` 保留）。
- `buildParams(...)` 私有包装方法把 `oauth` 传给 `AnthropicRequestBuilder.buildParams`。
- `streamInternal` 开头算一次当前工具真名集（pi `:578`）：

```java
// B81：入站名 demap 用的「当前工具」——pi :578 的 getCurrentTools(normalizedContext.messages)，
// 整轮一次；必须走 resolveTranscript（compat 不支中途系统消息时会折叠，影响当前工具集）。
var demapCompat = CompatResolver.forAnthropic(request.model(), baseUrl);
var demapTranscript = Transcripts.resolveTranscript(request.transcript(), demapCompat);
var currentTools = Transcripts.getCurrentTools(demapTranscript.messages());
```

`mapEvent` 加形参 `List<ToolDefinition> currentTools`，tool_use 起点：

```java
var wireName = tu.name();
pendingToolName[0] = oauth ? ClaudeCodeNames.fromWire(wireName, currentTools) : wireName;
```

（非 OAuth 分支逐字不变。）

### 3.3 `AnthropicRequestBuilder`：系统块与 beta 序

签名加 `boolean oauth`（两直调夹具形态传 `false`）。系统块段改为：

```java
// B81（pi :1168-1188）：OAuth 时恒发 system，首块为 Claude Code 前缀；
// 前缀是 ASCII 字面量，不净化；两块同挂 cacheControl。非 OAuth 行为不变。
if (oauth) {
    var blocks = new ArrayList<TextBlockParam>();
    var prefix = TextBlockParam.builder().text(ClaudeCodeNames.SYSTEM_PREFIX);
    if (cacheControl != null) prefix.cacheControl(cacheControl);
    blocks.add(prefix.build());
    if (!systemText.isEmpty()) {
        var own = TextBlockParam.builder().text(SanitizeUnicode.surrogates(systemText));
        if (cacheControl != null) own.cacheControl(cacheControl);
        blocks.add(own.build());
    }
    builder.systemOfTextBlockParams(blocks);
} else if (!systemText.isEmpty()) {
    // 现状单块支，原样
}
```

beta 段按 pi 序重排（`betas` 仍是 `ArrayList<String>`，显式 `anthropic-beta`
整组跳过的门不变）：

```java
if (oauth) betas.addAll(ClaudeCodeNames.OAUTH_BETAS);          // :1106 恒最前
if (!eagerToolInputStreaming && !currentTools.isEmpty()) betas.add(FINE_GRAINED_TOOL_STREAMING_BETA);
if (<thinking 门，现状条件>) betas.add(INTERLEAVED_THINKING_BETA);
if (fallbacks 非空) betas.add(SERVER_SIDE_FALLBACK_BETA);
if (compat.supportsMidConvoEffort) { betas.add(MID_CONVERSATION_OUTPUT_CONFIG_BETA); betas.add(THINKING_BINDING_CONTROLS_BETA); }
if (nativeToolChanges) betas.add(INLINE_TOOLS_BETA);
```

`addMessages(...)` 与 `AnthropicToolWire.addTools(...)` 的调用点把 `oauth` 传下去。

### 3.4 出站工具三处

- `AnthropicToolWire`：
  - `addTools(..., boolean oauth)`；`toDefinitionJson(td, supportsStrictTools, eager, oauth)`
    里 `definition.put("name", ClaudeCodeNames.toWire(td.name(), oauth))`。
    **占位符 `deferredToolPlaceholder()` 不过 mapper**（独立构造，保持现状）。
  - `toolDefinitionBlock(td, strict, eager, oauth)`（tool_addition 的 definition 同一个构造器）。
  - `toolReferenceBlock(type, toolName, oauth)`（tool_removal：`toWire`）。
- `AnthropicMessageConverter.toBlockParams(msg, allowEmptySignature, allowImages, oauth)`：
  tool_use 分支 `.name(ClaudeCodeNames.toWire(tu.name(), oauth))`。`toToolResultBlock`
  不动（无名字）。

### 3.5 B231-a：`StoredOAuthCredentials` 刷新脱离调用方线程

把整个「锁内读当前→复核临期→刷新→回写」搬到**新建虚拟线程**（线程是新的，不继承调用方的
中断状态），调用方在可中断的 `Future.get()` 上等：

```java
public Optional<OAuthCredential> resolveEffective(String provider) {
    var future = new CompletableFuture<OAuthCredential>();
    var caller = Thread.currentThread();
    Thread.startVirtualThread(() -> {
        try {
            // pi：调用方信号只取消「等锁」。worker 是新线程，不继承中断状态；
            // 进锁前若调用方已取消，则不发起刷新（凭据留给下一个请求刷）。
            if (caller.isInterrupted()) {
                future.complete(null);
                return;
            }
            var result = store.modify(provider, current -> {
                if (!nearExpiry(current)) {
                    return current;
                }
                try {
                    return refresher.refresh(provider, current);
                } catch (RuntimeException | IOException e) {
                    throw new OAuthRefreshException(provider, e);
                }
            });
            future.complete(result);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    });
    try {
        return Optional.ofNullable(future.get());
    } catch (InterruptedException e) {
        // 调用方取消：立刻收场（pi 的 raced caller 收 AbortError）；worker 不受影响，
        // 刷新完成后照常经 store.modify 落盘轮转 token —— 本批的核心保证。
        Thread.currentThread().interrupt();
        throw new OAuthRefreshException(provider,
            new java.io.InterruptedIOException("OAuth refresh wait interrupted"));
    } catch (ExecutionException e) {
        var cause = e.getCause();
        if (cause instanceof OAuthRefreshException ore) {
            throw ore;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        throw new OAuthRefreshException(provider, cause);
    }
}
```

与既有并发夹具的兼容性：

- `concurrentRequestsTriggerASingleRefresh`：两条调用线程各起一个 worker；第一个持锁刷新，
  第二个堵在 `ReentrantLock`/文件锁，拿锁后 `nearExpiry` 复核为 false ⇒ 仍只刷一次。
- 锁仍覆盖整个读-改-写（跨进程文件锁语义不变）；变的只是**锁的持有者线程**不再是调用方线程。
- 新鲜凭据/永久凭据两支：worker 路径同样返回原引用，`modify` 跳过覆写。

### 3.6 B231-b：刷新 HTTP 15s 超时

- `OAuthFlow`：`refresh(...)` 的 `postForm` 请求加 `.timeout(REFRESH_TIMEOUT)`；
  `postForm` 是 refresh 专用（exchange 走 `exchangeToken` 自建请求，**不加**）。
- `DeviceCodeFlow.refresh(...)` 的 POST 同样加。
- 常量各本类 `private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(15)`
  （pi 值在 `auth/resolve.ts:105`，逐字 15_000ms）。为可测，两 flow 的包私有构造器各加一个
  `Duration refreshTimeout` 形参（公开构造器传 15s），夹具用 1s。
- 超时异常：JDK 的 `HttpTimeoutException`（IOException 子类）经 `send` 原样上抛，被
  `StoredOAuthCredentials` 包成 `OAuthRefreshException`，与 pi 的 ModelsError("oauth") 同形。

---

## 4. 裁决（窄差留痕）

1. **beta 顺序整体对齐 pi 序**（3.3）。现网无消费方依赖逗号序，既有夹具全 `contains`；
   不做「只在 OAuth 时重排」的分支。
2. **worker 进锁前检查一次调用方中断状态**（3.5）：模拟 pi「信号只取消等锁」。中断发生在
   worker 等锁期间这一极窄窗不模拟（worker 本身不可中断，最坏多一次刷新 HTTP），不为此
   引入锁等待句柄。
3. **调用方中断时抛 `OAuthRefreshException(InterruptedIOException)`**，不新增异常类型；
   pi 那边是 AbortError，宿主层投影都是「请求失败」。中断标志在抛出前重设。
4. **设备码/PKCE 登录流程（`login`）不加 15s 超时**——pi 的 15s 只绑刷新信号；登录有自己的
   5/15 分钟超时，现状保留。
5. **版本号随锚点 bump 到 2.1.280**，不读运行时包版本（pi 同为硬编码）。
6. accept／direct-browser-access 两头仍不移植（沿用 `原 docs/43 §6` 裁决）。

## 5. 测试计划（全在 `pi-java-ai`）

**B81（wire 级，真 RecordingHttpServer 抓请求体/头）**：扩展 `AnthropicOAuthIdentityTest`
或新建 `AnthropicOAuthWireTest`：

1. OAuth 值（`AuthKind.OAUTH` 与 `sk-ant-oat` 两种载体各一）：`system` 是数组、首块文本
   逐字前缀；无前导系统消息时**仍有**一块；有前导时两块且序正确。
2. OAuth 请求头：`user-agent: claude-cli/2.1.280`（旧夹具 2.1.251 两断言同步改）。
3. `anthropic-beta` 头以 `claude-code-20250219,oauth-2025-04-20,` 起头；普通 API_KEY
   请求不含这两枚（回归）。
4. 请求级工具表：`read` → `Read`、`mcp__x__y` 原样、占位符名原样；非 OAuth 时 `read`
   小写不变。
5. 历史 assistant tool_use 块名映射（构造带 toolCall 的历史消息，抓 body）。
6. native 工具变更支：OAuth 下 `tool_removal.tool_reference.name` 映射、`tool_addition`
   内 definition.name 映射（复用 `AnthropicToolChangesWireTest` 的 ModelInfo compat 构造法）。
7. 入站 SSE：造 `content_block_start` tool_use 名为 `Read`、当前工具含 `read` ⇒
   toolcall_start 事件名是 `read`；当前工具无匹配（`KillShell`）⇒ 原样 `KillShell`；
   非 OAuth ⇒ 不 demap（`Read` 原样）。

**B231**：

8. `StoredOAuthCredentialsTest` 新增（镜像 pi `bde882c74` 两夹具）：刷新函数内中断调用方
   线程 ⇒ `resolveEffective` 抛 `OAuthRefreshException` 且中断标志重设；放行闸门后
   **轮询断言文件里是轮转后的新凭证**（`OAuthCredentialStore.resolve`）。
9. 既有的 5 分钟窗口/硬过期/永久凭据/失败不回落/并发单次刷新 5 夹具保持绿（实现走 worker
   后回归）。
10. `OAuthFlowTest`／`DeviceCodeFlowTest`：桩端点**挂起不响应**，注入 1s 刷新超时 ⇒
    `refresh` 在 ~1s 后抛 `HttpTimeoutException`/IOException；正常 200 响应不受影响。

验证：`JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test`；收尾
`mvn clean verify`（批 6 不出 ai 模块，-am 覆盖依赖即可；全量 verify 仍按流程跑一次）。

## 6. 明确不做

- ChatGPT 订阅登录（#95/#96/#97）＝批 7，不碰。
- workload identity federation（#98）用户裁决后排。
- pi `options.client` 直注形态（测试逃生口，Java 用直调构造器等价）。
- 模型目录刷新（`models.refresh`）那条共享路径：Java 无对应运行时（RemoteCatalogProvider
  的刷新不经 OAuth credential 轮转轴），只对齐请求鉴权这条。

## 7. 落地顺序与提交粒度

1. `fix(ai): bound OAuth refresh to 15s and persist rotated tokens when caller is cancelled (B231)`
   ——3.5/3.6＋夹具 8-10。
2. `feat(ai): Claude Code identity for anthropic OAuth requests — system prefix, tool name mapping, beta pair (B81)`
   ——3.1-3.4＋夹具 1-7。
3. `docs(ai): batch-6 closure` ——回填 docs/04（B81/B231 销号）、docs/06、docs/08 批 6 行、
   docs/05 总表、map/02 加权；记忆追加。
