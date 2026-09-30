# A-14 设计：Provider retry（`x-should-retry` 头 + `maxRetryDelayMs` cap）

> **状态：已闭环（2026-09-30）。实施记录见 §12。**
> 登记：`docs/48 §2` A-14；`docs/41:106`（provider-retry 差异）、`:120`（J5 死功能表）；
> `docs/map/02-ai.md:205`（第 73 项）。关联：3d 自动重试环（docs/31 §8.22）读 provider 错误投影，
> 本包是**该投影的传输层对端**；`Settings.Retry.ProviderRetry` 自 3d 起保 JSON 往返但**零消费者**。

---

## 1. 范围

### 1.1 Do

1. 新建 `ai/http/ProviderRetry.java`：逐字移植 pi `utils/provider-retry.ts` 的判据/延迟/cap 循环
   （不含 AbortSignal，见 R5）。
2. 全部 SDK 车道构造时把 SDK 自带重试关到 0（`.maxRetries(0)`），**初始请求获取**包进
   `ProviderRetry.retry(...)`：
   - `AnthropicMessagesApi`、`OpenAICompletionsApi`、`OpenAIResponsesApi`、
     `AzureOpenAIResponsesApi`、`GoogleGenerativeAiApi`、`OpenRouterImagesApi`、`OpenAIEmbeddingApi`。
3. `MistralConversationsApi` 走 `PiHttpClient`：把 `x-should-retry` / `retry-after-ms` 读点和
   cap 补进 `RetryPolicy` + `PiHttpClient`，重试次数取选项（默认 0）。
4. `SettingsAccessors` 新增 `getProviderRetrySettings()`：pi `settings-manager.ts:966-972` 的
   `??` 链（`maxRetryDelayMs ?? 60000`，maxRetries 无默认＝缺席＝0）。
5. `DefaultProviders.apiOptions` 硬编码的 `2` 改为读 settings（CLI 直给 ?? provider retry 设置
   `?? 0`），`maxRetryDelayMs` 经 `ApiOptions.extra` 过桥（新键 `maxRetryDelayMs`）。

### 1.2 Don't

- **不**移植退避睡眠的 AbortSignal 中断（R5：车道层无信号，结构性不可达，与 B20/§8.35.14 同裁决）。
- **不**对已开始的 SSE 流做中断后重试（pi 只包初始请求，见 R7）。
- **不**删 `RetryPolicy` 的五个预设工厂（anthropic/openai/google/mistral/deepseek）；本包后它们
  是否有调用者如实登记（J5 仍在死功能表，本包不发明调用者）。
- **不动** agent 级重试环（`retry.enabled/maxRetries/baseDelayMs/maxAgentDelayMs`）——不同层。

---

## 2. pi 证据（逐字）

### 2.1 `packages/ai/src/utils/provider-retry.ts`（全文 125 行，2026-09-30 取证）

```ts
const DEFAULT_MAX_RETRY_DELAY_MS = 60_000;

interface ProviderRetryOptions {
	maxRetries?: number;
	maxRetryDelayMs?: number;
	signal?: AbortSignal;
}

interface ProviderError extends Error {
	status: number | undefined;
	headers: Headers | undefined;
}

function isProviderError(error: unknown): error is ProviderError {
	if (!(error instanceof Error) || !("status" in error) || !("headers" in error)) return false;
	return (
		(error.status === undefined || typeof error.status === "number") &&
		(error.headers === undefined || error.headers instanceof Headers)
	);
}

/** Mirrors the pinned OpenAI/Anthropic SDK retry policy; review when either SDK is upgraded. */
function isRetryableProviderError(error: ProviderError): boolean {
	const shouldRetry = error.headers?.get("x-should-retry");
	if (shouldRetry === "true") return true;
	if (shouldRetry === "false") return false;

	if (error.status === undefined) return true;
	return (
		error.status === 408 ||
		error.status === 409 ||
		error.status === 429 ||
		(typeof error.status === "number" && error.status >= 500)
	);
}

function validateServerRetryDelayMs(
	delayMs: number,
	maxRetryDelayMs: number | undefined,
	providerErrorMessage: string,
): number {
	const maxDelayMs = maxRetryDelayMs ?? DEFAULT_MAX_RETRY_DELAY_MS;
	if (maxDelayMs > 0 && delayMs > maxDelayMs) {
		throw new Error(
			`Server requested ${Math.ceil(delayMs / 1000)}s retry delay (max: ${Math.ceil(maxDelayMs / 1000)}s). ${providerErrorMessage}`,
		);
	}
	return delayMs;
}

function getRetryDelayMs(error: ProviderError, retryIndex: number, maxRetryDelayMs: number | undefined): number {
	const retryAfterMs = error.headers?.get("retry-after-ms");
	if (retryAfterMs) {
		const value = Number.parseFloat(retryAfterMs);
		if (!Number.isNaN(value)) return validateServerRetryDelayMs(value, maxRetryDelayMs, error.message);
	}

	const retryAfter = error.headers?.get("retry-after");
	if (retryAfter) {
		const seconds = Number.parseFloat(retryAfter);
		const delayMs = Number.isNaN(seconds) ? Date.parse(retryAfter) - Date.now() : seconds * 1000;
		return validateServerRetryDelayMs(delayMs, maxRetryDelayMs, error.message);
	}

	const exponentialDelay = Math.min(0.5 * 2 ** retryIndex, 8) * 1000;
	return exponentialDelay * (1 - Math.random() * 0.25);
}
```

循环主体（`:105-125`）：

```ts
export async function retryProviderRequest<T>(
	request: () => Promise<T>,
	options: ProviderRetryOptions = {},
): Promise<T> {
	const maxRetries = options.maxRetries ?? 0;
	let retriesRemaining = maxRetries;

	for (;;) {
		try {
			// Each retry is a fresh SDK request, so X-Stainless-Retry-Count remains zero.
			return await request();
		} catch (error) {
			if (options.signal?.aborted) throw createAbortError();
			if (retriesRemaining <= 0 || !isProviderError(error) || !isRetryableProviderError(error)) throw error;

			const retryIndex = maxRetries - retriesRemaining;
			retriesRemaining--;
			await abortableSleep(getRetryDelayMs(error, retryIndex, options.maxRetryDelayMs), options.signal);
		}
	}
}
```

### 2.2 调用点形状（每条车道同形）

`openai-completions.ts:362-379`：

```ts
const requestOptions = {
	...(options?.signal ? { signal: options.signal } : {}),
	...(options?.timeoutMs !== undefined ? { timeout: options.timeoutMs } : {}),
	maxRetries: 0,
};
const { data: openaiStream, response } = await retryProviderRequest(
	() => client.chat.completions.create(params, requestOptions).withResponse(),
	{
		maxRetries: options?.maxRetries,
		maxRetryDelayMs: options?.maxRetryDelayMs,
		signal: options?.signal,
	},
);
```

同样的 `maxRetries: 0` + wrap：
- `anthropic-messages.ts:580-595`（`client.beta.messages.create(...).asResponse()`）
- `openai-responses.ts:165-176`、`azure-openai-responses.ts:118-130`
- `openrouter-images.ts:65-81`（非流式 `.withResponse()`）
- `google-shared.ts:496-513` 的 `retryGoogleRequest`：Google SDK 的 ApiError **无 headers**，
  先补 `headers = undefined` 再 wrap。

### 2.3 默认值在 coding-agent，不在 ai 层

`packages/coding-agent/src/core/settings-manager.ts:966-972`：

```ts
getProviderRetrySettings(): { timeoutMs?: number; maxRetries?: number; maxRetryDelayMs: number } {
	return {
		timeoutMs: this.settings.retry?.provider?.timeoutMs,
		maxRetries: this.settings.retry?.provider?.maxRetries,
		maxRetryDelayMs: this.settings.retry?.provider?.maxRetryDelayMs ?? 60000,
	};
}
```

`sdk.ts:325`：`maxRetries: options.maxRetries ?? providerRetrySettings.maxRetries`（缺席一路传到
`retryProviderRequest` 的 `?? 0` ⇒ **生产默认 provider 层 0 次重试**）。

pi settings 文档明文（`docs/settings.md:203,210`）：

> `retry.provider.maxRetries` | number | **`0`** | Provider/SDK retry attempts
> Keep `retry.provider.maxRetries` at `0` unless provider-level retries are explicitly needed.
> Setting it above `0` can make SDK/provider retries handle out-of-usage-limit errors before Pi
> sees them, which may block the agent until the provider quota resets.

---

## 3. pi-java 现状缺口

| # | 缺口 | 取证 |
|---|------|------|
| G1 | SDK 车道**从不关** SDK 内置重试，默认 2 次（Stainless `RetryingHttpClient$Builder` 默认 `iconst_2`）；pi 默认 0。生产行为：pi-java 在 408/409/429/5xx 上自行退避 2 轮且**上层环看不见** | javap 4.42.0 core；`OpenAICompletionsApi.java:125-126`、`AnthropicMessagesApi.java:77-92` 等 7 处 builder 无 maxRetries |
| G2 | SDK 内置重试**不执行** 60s cap：`RetryingHttpClient.shouldRetry(Throwable)` 只读 `Retry-After-Ms`/`Retry-After` 直接睡，无 cap 字符串/分支（javap -c 全方法无 60000/cap 判定）。pi：服务器要求 >60s ⇒ 立即硬失败 | javap -c RetryingHttpClient |
| G3 | `MistralConversationsApi` 经 `PiHttpClient` + `RetryPolicy.defaultPolicy()`：**3 次**重试、不读 `x-should-retry`、`Retry-After` 只在 429/503 读、不读 `retry-after-ms`、无 cap | `MistralConversationsApi.java:85`、`RetryPolicy.java:99-120`、`PiHttpClient.java:137-148` |
| G4 | `DefaultProviders.apiOptions` 硬编码 `maxRetries=2`，且 `Settings.Retry.provider` **零消费者** | `DefaultProviders.java:221`；grep `ProviderRetry` 仅 Settings.java 命中 |
| G5 | `ApiOptions` 无 `maxRetryDelayMs` 通道 | `ApiOptions.java` 六组件无该字段 |

### 3.1 一个关键的「反缺口」

Stainless SDK 自带判据（javap -c 实证）**已包含** `X-Should-Retry: true/false` 短路、
408/409/429/>=500、`Retry-After-Ms`、`Retry-After` —— 与 pi 的 `isRetryableProviderError`/
`getRetryDelayMs` 同形。所以**当用户把 maxRetries 配成 >0 时**，除 cap（G2）外判据无需重写；
本包主工作量是「默认归零 + cap + Mistral 补头」，不是重造判据。

---

## 4. 实施设计

### 4.1 `ai/http/ProviderRetry.java`（新建，约 150 行）

```java
public final class ProviderRetry {

    /** pi provider-retry.ts:1 的默认 cap。 */
    static final long DEFAULT_MAX_RETRY_DELAY_MS = 60_000L;

    /**
     * pi {@code ProviderRetryOptions}（去掉 signal，R5）。
     *
     * @param maxRetries     null ≙ pi 的 undefined ⇒ 0
     * @param maxRetryDelayMs null ≙ 60000；0 ⇒ 关闭 cap
     */
    public record Options(Integer maxRetries, Long maxRetryDelayMs) {
        public Options {
            // 便捷构造器：Options(0) / Options(0, 60000)
        }
        public Options(int maxRetries) { this(maxRetries, DEFAULT_MAX_RETRY_DELAY_MS); }
    }

    /** 携带 status + 可选 headers 的错误形状（pi ProviderError）。 */
    public record ProviderFailure(String message, Integer status,
                                  Map<String, List<String>> headers, Throwable cause) {}

    public static <T> T retry(Supplier<T> request, Options options) { … }
}
```

判据（逐字对应 :23-35）：

```java
static boolean isRetryable(ProviderFailure e, long maxDelayMs) {
    var h = e.headers();
    if (h != null) {
        var values = h.getOrDefault("x-should-retry", List.of());
        if (!values.isEmpty()) {
            var v = values.get(0);
            if ("true".equals(v)) return true;
            if ("false".equals(v)) return false;
        }
    }
    if (e.status() == null) return true;          // pi :28
    int s = e.status();
    return s == 408 || s == 409 || s == 429 || s >= 500;
}
```

延迟（:37-67）：

```java
static long delayMs(ProviderFailure e, int retryIndex, Long capMs) {
    long cap = capMs == null ? DEFAULT_MAX_RETRY_DELAY_MS : capMs;
    // 1) retry-after-ms（Float.parseFloat；NaN 落下一格）
    // 2) retry-after：数字 ⇒ ×1000；非数字 ⇒ HTTP-date，RFC_1123_DATE_TIME 解析 − now（pi Date.parse）
    //    两条都经 validate：cap > 0 && delay > cap ⇒ throw pi 逐字文案
    // 3) min(0.5 * 2^retryIndex, 8) * 1000 * (1 - random*0.25)
}
```

cap 异常文案（逐字，后缀是 provider 错误 message）：

```
Server requested %ds retry delay (max: %ds). %s
```

`%d` = `Math.ceil(delay/1000)` / `Math.ceil(cap/1000)`。

### 4.2 SDK 异常 → ProviderFailure 适配

各车道私有静态映射（不统一进 ProviderRetry —— 两个 SDK 类型无公共接口）：

```java
// OpenAI 族（completions/responses/azure/images/embedding 共用 helper，放 ProviderRetry）
static ProviderFailure of(com.openai.errors.OpenAIException e) {
    if (e instanceof OpenAIServiceException se)
        return new ProviderFailure(se.getMessage(), se.statusCode(), toMap(se.headers()), se);
    if (e instanceof OpenAIIoException)
        return new ProviderFailure(e.getMessage(), null, null, e);   // pi status undefined ⇒ 可重试
    return nonProvider(e);   // 非 provider 错误 ⇒ isProviderError=false，不重试
}
```

- `toMap(Headers)`：SDK `Headers` 有 `names()`/`values(name)`，聚成 `Map<String,List<String>>`。
- Anthropic 族同形（`AnthropicServiceException.statusCode()/headers()`、`AnthropicIoException`）。
- Google 族：SDK ApiError 无 headers（pi 补 undefined）—— 映射时 headers=null、status 取异常
  status 访问器；先实测 google-genai SDK 异常类型（`com.google.genai.errors.ApiException`？）
  再定稿，写在实施记录里。

### 4.3 车道接线形状（以 Anthropic 为例）

构造器：

```java
var builder = AnthropicOkHttpClient.builder();
… // 凭证同现状
builder.maxRetries(0);                          // G1：pi requestOptions.maxRetries:0
this.client = builder.build();
this.providerRetry = providerRetryOf(options);  // 从 ApiOptions 读 maxRetries + extra.maxRetryDelayMs
```

`streamInternal` 初始请求获取（pi 同点；流体消费仍在 try-with-resources）：

```java
StreamResponse<RawMessageStreamEvent> sr = ProviderRetry.retry(
    () -> client.messages().createStreaming(params), providerRetry);
try (sr) { … }
```

- 循环只包**获取**：重试拿到的永远是新响应（`X-Stainless-Retry-Count` 恒 0，pi 注释 :114）。
- OpenAI 族同点：`ProviderRetry.retry(() -> client.chat().completions().createStreaming(params), …)`。
- Images/Embedding 非流：直接包 `client.chat().completions().create(params)` 等。
- Google：包 `client.models.generateContentStream(...)`。

### 4.4 选项读取（车道私有，与 retentionOf 同形）

```java
private static ProviderRetry.Options providerRetryOf(ApiOptions options) {
    long cap = options.extra().get("maxRetryDelayMs") instanceof Number n
        ? n.longValue() : ProviderRetry.DEFAULT_MAX_RETRY_DELAY_MS;
    return new ProviderRetry.Options(options.maxRetries(), cap);
}
```

### 4.5 Mistral / PiHttpClient / RetryPolicy（G3）

`RetryPolicy` 增组件（尾部，Erasable 模式）：

```java
private final Long maxRetryDelayMs;          // null ⇒ 60000
public record Config(… 旧字段 …, Long maxRetryDelayMs) {}
```

判据方法改为接收响应：

```java
public boolean shouldRetry(int status, HttpResponse<?> response) {
    // x-should-retry true/false 短路优先；其余旧集合判据
}
public long delayMs(int status, int attempt, HttpResponse<?> response) {
    // 1) retry-after-ms（任意可重试状态都读，不再限于 429/503）
    // 2) retry-after：数字 / HTTP-date；两条过同一个 cap
    // 3) 指数退避
}
```

`PiHttpClient.executeWithRetrySse` 两处调用点随之传 `response`；默认 policy 的 maxRetries
须能为 0（`MistralConversationsApi` 构造时以 options 值建 policy，默认 0）。

### 4.6 Settings 消费链（G4/G5）

`SettingsAccessors` 新增（逐字 pi :966-972）：

```java
public record ProviderRetrySettings(Long timeoutMs, Integer maxRetries, long maxRetryDelayMs) {}

public ProviderRetrySettings getProviderRetrySettings() {
    var p = providerRetry();                       // merged settings（项目覆盖合并后的 retry.provider）
    return new ProviderRetrySettings(
        p == null ? null : p.timeoutMs(),
        p == null ? null : p.maxRetries(),
        p == null || p.maxRetryDelayMs() == null ? 60_000L : p.maxRetryDelayMs());
}
```

`DefaultProviders.apiOptions`：

```java
var pr = settings == null ? null : settings.accessors().getProviderRetrySettings();
int maxRetries = pr == null || pr.maxRetries() == null ? 0 : pr.maxRetries();
var extra = new LinkedHashMap<>(baseExtra);
if (pr != null) extra.put("maxRetryDelayMs", pr.maxRetryDelayMs());
// timeoutMs：pr.timeoutMs() ?? 现状 120s（保持旧行为，除非 settings 明给）
```

⚠️ settings 参数类型在 apiOptions 里是 `core/Settings`（扁平 POJO）还是带 accessors 的
manager？实施时先核调用点；若只拿到 POJO，则在 POJO 上加同 `??` 链的便捷方法，不新建 manager
依赖（取证结果写实施记录）。

---

## 5. 裁决点

| # | 裁决 | 建议 |
|---|------|------|
| R1 | 架构：**(A)** SDK 恒 maxRetries(0)＋自写 `ProviderRetry` 循环（pi 同构，cap 文案可逐字）／(B) maxRetries 透传 SDK＋自定义 `Sleeper` 做 cap（Sleeper 只见 Duration，cap 文案的 provider message 后缀无法复现 ⇒ 判据必败） | **A** |
| R2 | 默认 provider maxRetries：2→**0**（pi 文档/代码双证；上层环 3 次预算才是 pi 的重试面） | **0** |
| R3 | `maxRetryDelayMs`：默认 60000、0 关闭、异常文案逐字（含 ceil 秒数与错误后缀） | 按 pi |
| R4 | Mistral：补 `x-should-retry`（任意状态）、`retry-after-ms`（任意状态）、cap；次数默认 0 | 按 pi |
| R5 | AbortSignal：**不移植**退避中断（车道无信号，宿主 markAborted 承担；B20 同族） | 不移植 |
| R6 | 范围车道：7 条全含（含 images/embedding） | 全含 |
| R7 | 只包初始请求获取；流中错误不进 provider 重试（pi 形状；流中错误由上层环投影处理） | 按 pi |
| R8 | `retry-after` HTTP-date 分支移植（RFC_1123；负延迟夹 0——pi 没夹，**照 pi 不夹**，延迟为负则等效立即重试） | 照 pi |

---

## 6. 可达性

- 生产主链：`DefaultProviders.streamBlocking` → `apiOptions`（本包起读 settings）→
  `provider.createApi` → 车道构造（maxRetries(0) + options）→ stream → ProviderRetry。
- 默认配置下行为变化：**少**重试（SDK 2 轮 → 0 轮；Mistral 3 轮 → 0 轮）。429/5xx 立即
  抛出，经 3d 的错误投影进 agent 级重试环（退避 2/4/8s、3 次）——与 pi 完全同面。
- cap 可达：服务器给 `retry-after: >60`（或 retry-after-ms >60000）且用户配了 maxRetries>0
  ⇒ 立即硬失败。默认 maxRetries=0 时 cap 分支不可达（不睡），但判据代码仍须有夹具。

## 7. 先红与变异探针

- 先红：`ProviderRetry` 红夹具先写（5 场景，平移 pi test 的断言，定时器换可控 `Clock`/
  短延迟）；Mistral wire 夹具（RecordingHttpServer：429+x-should-retry:false 不重试、
  retry-after-ms 生效、超限硬失败）。
- 探针（RE）：
  - RE-1 删掉 `x-should-retry` 短路 ⇒ Mistral/ProviderRetry 各有红；
  - RE-2 cap 判定反转（`<=` 或去掉）⇒ cap 用例红；
  - RE-3 SDK builder 不打 `.maxRetries(0)` ⇒ wire 夹具录制到第 2 次请求（429 场景）⇒ 红；
  - RE-4 `status==null ⇒ true` 改成 false ⇒ IO 错误用例红。
- 变异默认走 Edit 工具（CRLF 第 6 次教训），变异后 grep 复核落地；模块汇总行为准。

## 8. 提交计划（8 步）

1. `ProviderRetry` 核心 + 单测（先红→绿）
2. OpenAI 族异常适配 + 车道接线（completions/responses/azure/images/embedding），wire 测试
3. Anthropic 车道接线 + wire 测试
4. Google 车道接线（先补异常类型取证）
5. `RetryPolicy`/`PiHttpClient` 三头 + Mistral 接线 + wire 测试
6. `SettingsAccessors.getProviderRetrySettings` + 测试
7. `DefaultProviders.apiOptions` 读 settings + extra 过桥 + 测试
8. docs 收口（docs/32、41、48、60 banner，map/02 第 73 项）

## 9. 行数预算

- 新增：ProviderRetry ~160、测试 ~250；各车道 +5~15 行；RetryPolicy +40；SettingsAccessors +40。
- 超限风险：OpenAICompletionsApi 现 402 行（+10 内安全）；RetryPolicy 现 151（+40 安全）；
  PiHttpClient 295。无超 500 风险。

## 10. 最终登记状态

实施完成后回填：A-14 结案；J5（RetryPolicy 预设零调用者）状态更新；新登记（如有）。

---

## 12. 实施记录（2026-09-30）

**裁决**：用户批复「按照建议实施」⇒ R1–R8 全按建议。

**7 commits**：

| 步 | Commit | 内容 |
|----|--------|------|
| 1 | `16a9144` | `ProviderRetry` 循环移植（判据/延迟/cap）＋11 单测；RE-1/RE-2/RE-4 各命中预期红 |
| 2 | `dfec702` | OpenAI 族五车道（completions/responses/azure/images/embedding）`.maxRetries(0)`＋wrap；`ofOpenAi` 适配；RecordingHttpServer 脚本化；RE-3 恰 2 红 |
| 3 | `016832c` | Anthropic 车道＋`ofAnthropic`；4 wire 测试 |
| 4 | `fe8e7b1` | Google 车道——实施期取证发现运行时 genai 是 **1.72**（BOM 声明，非设计时看的 1.15），内置 `RetryInterceptor` 默认 2 attempts；以 `HttpRetryOptions.attempts(1)` 关闭（探针命中 2 红），`ofGoogle` 只走指数退避 |
| 5 | `ac2b220` | RetryPolicy/PiHttpClient 三头＋cap，Mistral 按选项建 policy（默认 0）；RE-1 命中 |
| 6 | `14d87db` | `SettingsAccessors.getProviderRetrySettings`；**顺带修复 3d 起的潜在 JSON 往返 bug**：Json mapper GETTER 可见性 NONE ⇒ Retry/ProviderRetry record 序列化成 `{}`，组件全部丢失；显式 `@JsonProperty` 修复 |
| 7 | `f09c400` | DefaultProviders.apiOptions 硬编码 `2` ⇒ 读 settings 的 ?? 链，cap 经 extra，timeoutMs 明给替换 120s |

**验证**：ai 模块 1182/1182；coding-agent 287/287（-am 新构件；不带 -am 跑出的 28 红为 D:/repository 旧构件假红，同既往教训）。

**与设计的偏差（实施期取证）**：

1. Google SDK 版本：设计取证看的 1.15 是 `~/.m2` 旧件，实际本地仓库 `D:/repository` + BOM 钉的是 1.72；关闭重试的落点相应改为 1.72 的 `HttpRetryOptions`（比设计假设的「SDK 无重试」多一道关）。
2. OpenAI SDK 错误 message 带状态码前缀（实测 `429: slow down`）；cap 后缀判据＝SDK error.message 逐字保留。

**新登记**：

- **B139**：Json mapper 配置（GETTER NONE）下**所有嵌套 record** 组件默认不可见——Retry/ProviderRetry 已修；同族的 `Settings.Compaction` 等嵌套 record 可能同病，本包未扩查，登记待取证。
- **J5 更新**：Mistral 不再走 `defaultPolicy()`、改按 ApiOptions 建 policy；五个预设工厂仍零调用者，死功能表条目保留。

