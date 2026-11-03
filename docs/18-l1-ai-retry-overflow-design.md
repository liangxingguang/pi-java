# 18 - L1 AI retry 与 overflow 判定对齐设计（B267 第四批：#70／#71）

> 【背景摘要】docs/17 已闭环 provider wire。本包接手 `docs/map/02-ai.md` 剩余 8 个「缺失」中的 **2 个横切判定行**：上下文溢出判定（#70）与瞬断重试判定（#71）。本包只改 `pi-java-ai` 的两个纯谓词类及其测试，不改 wire、OAuth、CLI 权限或 Faux。
>
> 文档状态：**已实施并闭环（2026-11-03）**。实施按 §6 完成，闭环记录见 §7。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。

## 0. 范围、基线与口径

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | docs/17 闭环（HEAD `1ed4b9d7`） |
| L1 当前账面 | **219.0/232＝94.40%**；对齐／存疑／缺失＝117／0／8；计分单位 125 |
| 本包目标 | 2 行缺失转对齐；账面到 **223.0/232＝96.12%** |
| 可执行基线 | docs/17 终态：`mvn clean verify` 实测 **6,017 测试／0 失败／0 错误／29 跳过** |
| 依赖变更 | 无（纯源码与测试） |

### 0.2 为什么下一批是 retry／overflow

【场景】B267 剩余面按依赖拆成 provider wire、retry／overflow、OAuth／device／env、CLI 权限、Faux 余量。provider wire 已按 docs/17 闭环，其余面中只有 retry／overflow 是**两个独立纯谓词**：不碰请求构造、不碰鉴权存储、不碰测试设施，也不会被后续 OAuth／CLI 包二次改写。

【决定】本包做横切判定 2 行：

| 地图行 | 能力 | 权重 | 现判定 | 本包动作 |
|---|---|---:|---|---|
| #70 | `ContextOverflow` 上下文溢出判定 | 2 | 缺失 | 补 LiteLLM 预检文案与通用 HTTP 413 两条模式 |
| #71 | `RetryableError` 瞬断重试判定 | 2 | 缺失 | 补 2 条排除项、7 条白名单词，修正 3 条既有模式的边界 |

合计权重 **4**。

### 0.3 账面影响

| 口径 | docs/17 后 | 本包后（预计） |
|---|---:|---:|
| 加权 | 219.0/232＝94.40% | **223.0/232＝96.12%** |
| 未加权 | 117/125＝93.6% | **119/125＝95.2%** |
| 对齐／存疑／缺失 | 117／0／8 | **119／0／6** |
| 计分单位 | 125 | **125**（分母不变） |
| 权重 1 | 50/53＝94.3% | 50/53＝94.3% |
| 权重 2 | 64/74＝86.5% | **66/74＝89.2%** |
| 权重 3 | 105/105＝100% | 105/105＝100% |

域表：横切 **46/51＝90.2% → 50/51＝98.0%**；其余域不变。剩余缺失为 #43／#44／#45／#50／#80／#112，共 6 个单元、权重 9。

### 0.4 不在本包

- 不改 `ContextOverflow.isRecoverableLength` 的判定（已与 pi 同形，只补回归用例）。
- 不改 `RetryBackoff`、`ProviderRetry`、`RetryPolicy` 与 agent-core 的重试环（`PostRunRetry`／`LlmSummaryGenerator` 只消费谓词，不在本包改策略）。
- 不处理 #43–#45／#50（OAuth／env）、#80（Faux 余量）、#112（CLI 权限）、#75 的 deferred fetch 生产入口。
- 不新增 Provider、不扩 R2 长尾范围。

### 0.5 术语

- **上下文溢出判定（ContextOverflow）**：判断一条助手消息是否表示「输入超出上下文窗口」；结果供自动压缩与自动重试使用，不产生用户文案。
- **瞬断重试判定（isRetryableAssistantError）**：判断一条 `stopReason="error"` 的助手消息是否值得再试一次；结果只决定是否重试，不改错误文本。
- **白名单／排除表**：pi 用一张「值得重试」的瞬断词表与一张「配额耗尽、不许重试」的排除表；命中排除表优先，两张表都不命中即不重试。
- **model_visible（模型可见面）**：大模型能读到的请求体、文本或错误文案。本包两个谓词都不生成文案，属内部引擎；但判定的副作用（自动压缩、自动重试）对会话可见。
- **HTTP 413**：请求体字节超限状态码。与「token 数超上下文窗口」不同，但同样需要压缩或截断恢复。

## 1. 判据

1. **逐条移植，不自行归纳**：pi 的模式表是按厂商与服务端文案逐条累积的，Java 不得合并、缩写或按「语义」重写。
2. **正则方言固定**：JS 非锚定 `test()` ⇒ Java `Matcher.find()`；`/i` ⇒ `CASE_INSENSITIVE`；锚定模式里的 `^` 保持。
3. **排除优先**：瞬断判定先查排除表，再查白名单表；与 pi 同。
4. **谓词不产文案**：本包不改任何错误文本；只改「何时判 true／false」。
5. **既有夹具按新锚点修正，不保留旧错误期望**：例如「413 无体」在旧 Java 测试里被判「仅 Cerebras 溢出」，新锚点的通用 413 模式使其对所有 provider 成立。

## 2. #70 ContextOverflow（权重 2）

### 2.1 pi 证据（`packages/ai/src/utils/overflow.ts`）

模式表 `39-69` 中本包相关的两条：

```ts
const OVERFLOW_PATTERNS = [
	...
	/context window exceeded/i, // LiteLLM pre-call check (ContextWindowExceededError)
	...
	// HTTP 413 means request-size overflow regardless of provider or body text.
	// Match raw SDK errors and formatProviderError's prefix, not a status mentioned in the body.
	/^(?:[\w -]+ API error \(413\):|413(?:\s|:|$))/i,
];
```

判定函数 `144-178`：

```ts
export function isContextOverflow(message: AssistantMessage, contextWindow?: number): boolean {
	// Case 1: Check error message patterns
	if (message.stopReason === "error" && message.errorMessage) {
		const isNonOverflow = NON_OVERFLOW_PATTERNS.some((p) => p.test(message.errorMessage!));
		if (!isNonOverflow) {
			if (OVERFLOW_PATTERNS.some((p) => p.test(message.errorMessage!))) return true;
			if (message.provider === "cerebras" && CEREBRAS_BODYLESS_OVERFLOW_PATTERN.test(message.errorMessage)) {
				return true;
			}
		}
	}
	...
}
```

pi 夹具 `packages/ai/test/overflow.test.ts:32-61` 明确了 413 的边界：

```ts
it.each([
	'github-copilot API error (413): {"message":"failed to parse request","code":""}',
	"custom-provider API error (413): Payload Too Large",
	'413 {"error":{"message":"failed to parse request","code":""}}',
	"413",
])("detects provider-independent request-size overflow: %s", ...);

it.each([
	'github-copilot API error (400): {"message":"failed to parse request","code":""}',
	"custom-provider API error (429): Too many requests",
	'github-copilot API error (400): {"message":"failed to parse request: invalid value 413"}',
	"custom-provider API error (400): upstream mentioned OpenAI API error (413): invalid input",
	"custom-provider API error (4130): invalid input",
	"4130 failed to parse request",
])("does not mistake unrelated errors for request-size overflow: %s", ...);
```

要点：

- `context window exceeded` 是 LiteLLM 预检的独立文案（`ContextWindowExceededError`），现有 Java 表没有它。
- HTTP 413 是**厂商无关**的请求体超限信号；只认「串首 413」或「`<前缀> API error (413):`」，body 里顺带提到 413 不算。
- 「无体 413」文本形如 `413 status code (no body)`，会命中通用 413 模式 ⇒ **所有 provider 都算溢出**；「无体 400」仍只对 Cerebras 成立。
- `isRecoverableLength`（`186-188`）与 case 2／3 在 Java 已同形，本包只补测试。

### 2.2 Java 现状

- `AI/utils/ContextOverflow.java:39-65`：`OVERFLOW_PATTERNS` 移植了 pi `40-65` 的 26 条中的 25 条，缺 `context window exceeded`（pi `46`）；表尾还缺通用 413 一条（pi `66-68`）。
- `:72-73`：Cerebras bodyless 模式在，provider 门控在 `:113-117`。
- `:96-141`：`isContextOverflow` 的三情形与排除表顺序与 pi 同；`usage` 缺席时 Java 额外短路（pi 类型上必填，属方言保护）。
- `:151-154`：`isRecoverableLength` 与 pi 同形。
- 现有测试 `ContextOverflowTest.java:87-107` 把「413 status code (no body)」判为「非 Cerebras ⇒ 不算溢出」；这条期望与新锚点冲突（见 §2.3 第 3 点）。

### 2.3 改动方案

1. 在 `OVERFLOW_PATTERNS` 的 `exceeds ... maximum context length`（现 `:45`）之后插入：

   ```java
   Pattern.compile("context window exceeded", Pattern.CASE_INSENSITIVE), // LiteLLM pre-call check (ContextWindowExceededError)
   ```

2. 在表尾 `token limit exceeded`（现 `:64`）之后追加：

   ```java
   // HTTP 413 means request-size overflow regardless of provider or body text.
   // Match raw SDK errors and formatProviderError's prefix, not a status mentioned in the body.
   Pattern.compile("^(?:[\\w -]+ API error \\(413\\):|413(?:\\s|:|$))", Pattern.CASE_INSENSITIVE)
   ```

   `[\w -]` 在 Java 与 JS 同为 ASCII 单词字符、空格与连字符；`^` 只在串首生效（`Matcher.find()` 无 `MULTILINE`）。

3. 修正现有测试的旧期望：
   - 「413 status code (no body)」对**任意** provider 现在都命中（pi `test/overflow.test.ts:133-139` 的注释明确「HTTP 413 对所有 provider 都是请求体超限」）。
   - 「400 status code (no body)」的「仅 Cerebras」断言保留。
   - 400／429 前缀带 413 子串、`4130` 前缀、body 内提及 413 的负例新增。

4. 不改 `CEREBRAS_BODYLESS_OVERFLOW`：它仍只对 400 无体（及可能的 413 无体）做 Cerebras 门控；413 的通用命中由第 2 条负责。

### 2.4 测试计划（RED-first）

- `ContextOverflowTest` 的 `errorMessagesAreOverflow` 增补：
  - `"litellm.ContextWindowExceededError: ... Context Window exceeded for given call."`（大小写混合）；
  - `'github-copilot API error (413): {"message":"failed to parse request","code":""}'`；
  - `"custom-provider API error (413): Payload Too Large"`；
  - `'413 {"error":{"message":"failed to parse request","code":""}}'`；
  - `"413"`（串尾裸状态）。
- 新增 `requestSizeOverflowDoesNotOverreach`：
  - `"custom-provider API error (4130): invalid input"` ⇒ false；
  - `"4130 failed to parse request"` ⇒ false；
  - `'github-copilot API error (400): {"message":"failed to parse request: invalid value 413"}'` ⇒ false；
  - `"custom-provider API error (400): upstream mentioned OpenAI API error (413): invalid input"` ⇒ false；
  - `"custom-provider API error (429): Too many requests"` ⇒ false。
- 改写 `bodylessOverflowIsRejectedForOtherProviders`：
  - 400 无体：非 cerebras ⇒ false、cerebras ⇒ true（保留原断言）；
  - 413 无体：所有 provider ⇒ true（新增）。
- 变异探针：把 413 模式的 `^` 去掉，`"…invalid value 413"` 用例必须红；把 `context window exceeded` 删掉，LiteLLM 预检用例必须红。

## 3. #71 RetryableError（权重 2）

### 3.1 pi 证据（`packages/ai/src/utils/retry.ts`）

排除表 `7-35`：

```ts
const NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN = buildProviderErrorPattern([
	...
	"subscription_sharing_usage_limit_exceeded",
	// Sign in with ChatGPT on a ChatGPT Business plan: a usage limit set by the
	// workspace admin. Only the admin can raise it.
	"subscription_sharing_workplace_individual_usage_limit_exceeded",

	// Zhipu/Z.ai (open.bigmodel.cn) error code 1308: the plan's usage limit is
	// reached, sent as HTTP 429. It resets after hours, not seconds (#11537).
	'"code"\\s*:\\s*"?1308(?![0-9])',
]);
```

白名单表 `37-125` 中本包相关的条目：

```ts
	// Status codes must not be part of a longer word or number, e.g. a request id or byte count (#11057).
	"(?<![a-z0-9])(?:429|500|502|503|504|520|524)(?![a-z0-9])",
	// Also matches "The service is temporarily unavailable" (#10965).
	"service.?(?:is.?)?(?:temporarily.?)?unavailable",
	...
	"provider.?returned.?(?:an.?)?error",
	...
	"unable to forward upstream",
	"upstream.?request.?failed",
	"upstream_stream_read_error",
	"stream was interrupted",
	"http/?2 stream failed",
	...
	"request to \\S+ failed, reason:",
	...
	"stream disconnected before completion",
```

判定函数 `270-275`（pi 文件末）：

```ts
export function isRetryableAssistantError(message: AssistantMessage): boolean {
	if (message.stopReason !== "error" || !message.errorMessage) return false;
	const errorMessage = message.errorMessage;
	if (NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN.test(errorMessage)) return false;
	return RETRYABLE_PROVIDER_ERROR_PATTERN.test(errorMessage);
}
```

要点：

- 两条新排除项：ChatGPT Business 工作区限额、z.ai 1308（HTTP 429 但语义是小时级配额墙）。
- 状态码必须**独立**出现，不能是请求 id／字节数的一部分（#11057）。这是从旧的裸子串匹配收紧。
- 三条既有模式放宽：`service is temporarily unavailable`、`provider returned an error`。
- 七条新瞬断文案覆盖网关中继（`unable to forward upstream` 等）、node-fetch 的 `request to … failed, reason:`、Codex CLI 的 `stream disconnected before completion`。

### 3.2 Java 现状

- `AI/utils/RetryableError.java:38-60`：排除表缺 `subscription_sharing_workplace_individual_usage_limit_exceeded` 与 z.ai 1308 正则。
- `:66-144`：白名单缺 7 条（`unable to forward upstream`、`upstream.?request.?failed`、`upstream_stream_read_error`、`stream was interrupted`、`http/?2 stream failed`、`request to \S+ failed, reason:`、`stream disconnected before completion`）。
- 三条既有模式与 pi 不一致：
  - `:76-82` 的状态码是裸 `"429"`／`"500"`／…，会命中 `req_…503…` 与 `5242880` 这类数字；
  - `:83` 的 `service.?unavailable` 匹配不到 `The service is temporarily unavailable`；
  - `:89` 的 `provider.?returned.?error` 匹配不到 `provider returned an error`。
- `:158-168` 的判定函数与 pi 同形（`stopReason`／空串门、排除优先）。

### 3.3 改动方案

1. 排除表追加（顺序照 pi）：

   ```java
   "subscription_sharing_workplace_individual_usage_limit_exceeded",
   // Zhipu/Z.ai (open.bigmodel.cn) error code 1308: the plan's usage limit is
   // reached, sent as HTTP 429. It resets after hours, not seconds (#11537).
   "\"code\"\\s*:\\s*\"?1308(?![0-9])"
   ```

   该条是正则片段（含 `\s*` 与负向先行断言），与其余纯文本条目一起被 `String.join("|")` 拼接；`"code"` 两侧的引号是匹配错误体字面量所必需。

2. 白名单替换 3 条：

   | 现值 | 新值（pi 逐字） |
   |---|---|
   | `"429"`, `"500"`, `"502"`, `"503"`, `"504"`, `"520"`, `"524"` 七条 | `"(?<![a-z0-9])(?:429\|500\|502\|503\|504\|520\|524)(?![a-z0-9])"` 一条 |
   | `"service.?unavailable"` | `"service.?(?:is.?)?(?:temporarily.?)?unavailable"` |
   | `"provider.?returned.?error"` | `"provider.?returned.?(?:an.?)?error"` |

   Java 支持固定长度后顾断言 `(?<![a-z0-9])`；`CASE_INSENSITIVE` 使断言里的字符类同样不区分大小写，与 JS `/i` 同。

3. 白名单追加 7 条（按 pi 注释分组插入，位置与注释逐条对应）：

   ```java
   "unable to forward upstream",
   "upstream.?request.?failed",
   "upstream_stream_read_error",
   "stream was interrupted",
   "http/?2 stream failed",
   "request to \\S+ failed, reason:",
   "stream disconnected before completion"
   ```

4. 不改判定函数、不改 `String.join("|")` 拼接方式、不改排除优先顺序。

### 3.4 测试计划（RED-first）

- `RetryableErrorTest` 白名单增补：
  - `"unknown: Unable to forward upstream stream"`；
  - `"upstream_error: Upstream request failed"`；
  - `"upstream_stream_read_error: Upstream response stream was interrupted"`；
  - `"Upstream HTTP/2 stream failed"`；
  - `"request to https://oauth2.googleapis.com/token failed, reason:"`；
  - `"stream disconnected before completion: stream closed before response.completed"`；
  - `"The service is temporarily unavailable. Please retry later."`；
  - `"Provider returned an error: upstream gone"`。
- 排除表增补：
  - `"429 subscription_sharing_workplace_individual_usage_limit_exceeded"` ⇒ false（组合 429 以证明排除优先）；
  - `'429 {"code":"1308","message":"Usage limit reached"}'` ⇒ false；
  - `'429 {"code":"13080","message":"…"}'` ⇒ true（`(?![0-9])` 不得吞掉长码，因 429 仍是瞬断）。
- 边界回归（来自 pi `test/retry.test.ts:160-166`）：
  - `'401 {"type":"error","error":{...},"request_id":"req_011CU503xyz"}'` ⇒ false；
  - `"400 image exceeds 5 MB maximum: 5316784 bytes > 5242880 bytes"` ⇒ false。
- 既有用例复核：`"520 status code"`、`"524 origin timeout"`、`"429 status code"` 仍为 true（独立状态码）；原测试里「子串匹配」的注释按新边界改写。
- 变异探针：把状态码模式改回裸 `"503"`，`req_011CU503xyz` 用例必须红；删掉 z.ai 1308 排除，1308 用例必须红；删掉 `temporarily` 分支，临时不可用用例必须红。

## 4. 测试与验证（包级）

1. **RED-first**：先落 §2.4／§3.4 的新夹具，确认红；再改两张表，确认绿。
2. 模块验证：`mvn test -pl pi-java-ai -am`（必须带 `-am`，防止拾旧构件）。
3. 全量：`mvn clean verify`，要求 0 失败／0 错误，Checkstyle 0、SpotBugs 0；测试数不低于基线 6,017＋本包新增。
4. 下游冒烟：`pi-java-agent-core` 的 `PostRunRetryTest`／`PostRunCompactionCheck` 相关测试必须全绿；新增重试文案可能改变其夹具分类，按 pi 真值修正，不掩盖。
5. 真实终端冒烟不适用：本包无 CLI／TUI 交互新增。
6. 台账回填：`docs/map/02-ai.md` #70／#71 判定改「对齐」；`docs/04` 的 B267 描述；`docs/06`／`docs/08` 状态；docs/18 §7。

## 5. 风险与裁决

1. **通用 413 改变既有测试期望**
   【场景】旧 Java 测试把「413 status code (no body)」判为「仅 Cerebras 溢出」，而新锚点的 413 模式对所有 provider 成立。
   【决定】按 pi 夹具修正：400 无体仍仅 Cerebras，413 无体全部 provider。
   【风险/代价】[内部，模型不可见] 自动压缩触发面扩大；这是 pi 的既定行为，不是 Java 新发明。

2. **状态码从子串匹配收紧为独立匹配**
   【场景】旧的裸 `"503"` 会命中 `req_011CU503xyz` 或 `5242880` 这类 id／字节数，造成误重试。
   【决定】采用 pi 的 `(?<![a-z0-9])…(?![a-z0-9])` 边界。
   【风险/代价】[内部，模型不可见] 少数把状态码粘在字母后的自定义网关文案不再重试；与 pi 同，属有意收紧。

3. **三条文案模式放宽**
   【场景】`service.?unavailable` 与 `provider.?returned.?error` 覆盖不到 pi 已收录的 `is temporarily`／`returned an` 形态。
   【决定】替换为 pi 逐字正则。
   【风险/代价】[内部，模型不可见] 重试次数可能增加；预算与退避仍由 agent-core 的重试环控制，本包不改。

4. **z.ai 1308 是正则片段而非纯文本**
   【场景】排除表其余条目是纯文本，`1308` 需要匹配 JSON 键值形态。
   【决定】保留 pi 的 `"code"\s*:\s*"?1308(?![0-9])` 片段；`String.join("|")` 直接拼接可行（片段内无顶层 `|`）。
   【风险/代价】[内部，模型不可见] 若错误体把 `code` 写成单引号或非 JSON，该条不命中；与 pi 同。

5. **本包不动重试策略**
   【场景】谓词变宽后，重试次数会变化，但预算、退避、事件在 agent-core。
   【决定】只改 `pi-java-ai` 的两个判定类；下游按既有策略消费。
   【风险/代价】[内部，模型不可见] 若用户观察到重试增多，来源是分类表而非循环逻辑；排查时先看 `RetryableError`。

6. **不登记新 D 的预期**
   【场景】Java 与 JS 正则方言在本包用到的特性（非锚定搜索、`/i`、固定长度后顾、`\w` ASCII）均等价。
   【决定】若实施中出现不可消除差异，按实际材质登记 D；否则两行备注写「D 不适用」。
   【风险/代价】[内部，模型不可见] 不预设 D 号，避免把等价写法误记为窄差。

## 6. 实施步骤（获批后）

1. #70：改 `AI/utils/ContextOverflow.java` 两条模式；补 `ContextOverflowTest` 新用例并修正 413 无体期望。
2. #71：改 `AI/utils/RetryableError.java` 排除表 2 条、白名单 7 条、替换 3 条；补 `RetryableErrorTest`。
3. 跑 `mvn test -pl pi-java-ai -am`；再跑全量 `mvn clean verify`。
4. 跑 agent-core 下游相关测试；按 pi 真值修正受影响的夹具。
5. 台账回填并按实际结果写 docs/18 §7。
6. Commit 建议：
   - `feat(ai): align context overflow patterns`
   - `feat(ai): align retryable error patterns`
   - `docs(ai): close docs/18 retry overflow package`

## 7. 闭环记录（实施后回填）

### 7.1 落地内容

- **#70 ContextOverflow**（`pi-java-ai/.../AI/utils/ContextOverflow.java`）：
  - `OVERFLOW_PATTERNS` 按 pi 顺序插入 `context window exceeded`（LiteLLM 预检）；
  - 表尾追加 `^(?:[\w -]+ API error \(413\):|413(?:\s|:|$))`（厂商无关 HTTP 413）；
  - 判定函数、排除表、Cerebras 门控与 `isRecoverableLength` 未改。
- **#71 RetryableError**（`pi-java-ai/.../AI/utils/RetryableError.java`）：
  - 排除表追加 `subscription_sharing_workplace_individual_usage_limit_exceeded` 与 `"code"\s*:\s*"?1308(?![0-9])`；
  - 白名单 7 条状态码合并为 `(?<![a-z0-9])(?:429|500|502|503|504|520|524)(?![a-z0-9])` 一条；
  - `service.?unavailable` → `service.?(?:is.?)?(?:temporarily.?)?unavailable`；`provider.?returned.?error` → `provider.?returned.?(?:an.?)?error`；
  - 追加 7 条：`unable to forward upstream`、`upstream.?request.?failed`、`upstream_stream_read_error`、`stream was interrupted`、`http/?2 stream failed`、`request to \S+ failed, reason:`、`stream disconnected before completion`；
  - 判定函数、`String.join("|")` 拼接与排除优先顺序未改。

### 7.2 验证证据

| 项 | 结果 |
|---|---|
| RED-first | 新夹具先落：`ContextOverflowTest` 7 红（LiteLLM＋四条 413 共 5，413 无体期望反转 2）；`RetryableErrorTest` 12 红（白名单 8、排除表 2、状态码边界 2）；共 19 红 |
| 变异探针 | 去掉 413 模式 `^` ⇒ `requestSizeOverflowDoesNotOverreach` 红（body 内提及 413 用例）；状态码回退裸子串 ⇒ `embeddedStatusCodeDigitsAreNotRetryable` 2 红；均恢复后绿 |
| AI 模块 | `mvn test -pl pi-java-ai -am`：**1,982 测／0 失败／0 错误**（`ContextOverflowTest` 57、`RetryableErrorTest` 80） |
| 下游冒烟 | `PostRunRetryTest` 15、`PostRunCompactionCheckTest` 15、`CompactionTest` 5 全绿；无夹具需按新分类修正 |
| 全量 | `mvn clean verify`：**6,042 测／0 失败／0 错误／29 跳过**（基线 6,017，本包净增 25）；Checkstyle 0 违规；BUILD SUCCESS |
| 真实终端冒烟 | 不适用（无 CLI／TUI 交互新增） |

### 7.3 账面

| 口径 | docs/17 后 | docs/18 后 |
|---|---:|---:|
| 加权 | 219.0/232＝94.40% | **223.0/232＝96.12%** |
| 未加权 | 117/125＝93.6% | **119/125＝95.2%** |
| 对齐／存疑／缺失 | 117／0／8 | **119／0／6** |
| 横切域 | 46/51＝90.2% | **50/51＝98.0%** |

剩余缺失：#43／#44／#45／#50（OAuth／env）、#80（Faux 余量）、#112（CLI 权限）；#75 的 deferred fetch 生产入口仍挂 B267/D203。

### 7.4 裁决与偏差

- D **不适用**：本包用到的正则特性（非锚定 `find()`、`CASE_INSENSITIVE`、固定长度后顾、ASCII `\w`）与 JS 等价，未登记新 D。
- 与设计的偏差：仅注释行号按新锚点校正（`retry.ts:46-47/48-49/54-56/58-65/78/102-103`）；行为无偏差。
- 既有夹具修正：`413 status code (no body)` 由「仅 Cerebras」改为「所有 provider」（§5 风险 1，按 pi 真值）。
