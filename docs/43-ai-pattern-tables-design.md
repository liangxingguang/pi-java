# 43 ai 模式表补齐 —— `ContextOverflow` ＋ `RetryableError`

> **状态：📝 待审核（2026-10-09）。**
> 承接 `docs/41 §6` 的推进次序 **②**（「ai 平凡档」）。
> 本包是 `docs/41` 盘点里**成本最低**的一项：**纯字符串表补齐，无架构面**。
>
> **§9 是追加**：用户问「能否把 `docs/41 §1.2` 的 w1 那批并进本包」——
> 逐条回测后**否决**（9 条里只 1 条可搭车，且实际是管道包不是补行）。**本包范围不变。**

---

## 1. 缺口是什么

两个类都是 pi 模式表的**逐条移植**，但都漏了几条。全在 `pi-java-ai/src/main/java/com/pijava/ai/utils/`：

| 类 | pi 参照 | pi 条数 | Java 条数 | 缺 |
|---|---|---|---|---|
| `ContextOverflow` | `utils/overflow.ts:37-63` `OVERFLOW_PATTERNS` | **25** | **24** | **1** |
| `RetryableError`（瞬断白名单） | `utils/retry.ts:26-90` `RETRYABLE_PROVIDER_ERROR_PATTERN` | **46** | **43** | **3** |
| `RetryableError`（配额排除表） | `utils/retry.ts:7-24` `NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN` | **9** | **8** | **1** |

> 数法是**逐条 diff**（`comm -23` 对读两侧字符串表），不是目测 —— 见 §8 复现方法。

### 1.1 为什么这几条要紧

**`ContextOverflow` 缺的那条** 是 z.ai **CN 端点**的溢出文案。缺了它，CN 端点的 `Prompt exceeds max length`
不会被判为溢出 ⇒ **溢出恢复（compact-and-retry）不触发**，用户看到的是裸错误。

**`RetryableError` 缺的 3 条瞬断** 里，两条是 **ChatGPT 登录路**（`subscription_sharing_*_unavailable`）。
pi 的注释明写动机（`retry.ts:98-99`）：

```ts
// Sign in with ChatGPT: usage or user data temporarily unavailable. Usage
// failures can arrive mid-stream without an HTTP 503 in the message.
```

⇒ 缺了它们，**订阅路的中流失败不会重试**，直接终止。

**缺的那条配额排除**（`subscription_sharing_usage_limit_exceeded`）是**同前缀的相反裁决** ——
与瞬断那两条是**同一个 `subscription_sharing_` 家族**，但 pi 把它归配额墙（重试只是空耗）。
⚠️ **这条最容易被漏**：它不在瞬断白名单那一大段里，而在**文件顶部的排除表**（`retry.ts:7-24`）。

---

## 2. 逐条变更（带 pi 逐字代码）

### 2.1 `ContextOverflow` ＋1 条

**pi**（`utils/overflow.ts:38-40`）：

```ts
const OVERFLOW_PATTERNS = [
	/prompt (?:is )?too long/i, // Anthropic and z.ai token overflow
	/prompt exceeds max length/i, // z.ai CN endpoint token overflow
	/request_too_large/i, // Anthropic request byte-size overflow (HTTP 413)
```

**Java 现状**（`ContextOverflow.java:39-40`）—— 缺中间那条：

```java
        Pattern.compile("prompt (?:is )?too long", Pattern.CASE_INSENSITIVE), // Anthropic and z.ai token overflow
        Pattern.compile("request_too_large", Pattern.CASE_INSENSITIVE), // Anthropic request byte-size overflow (HTTP 413)
```

**改为**（在第 39 与 40 行之间插入）：

```java
        Pattern.compile("prompt (?:is )?too long", Pattern.CASE_INSENSITIVE), // Anthropic and z.ai token overflow
        Pattern.compile("prompt exceeds max length", Pattern.CASE_INSENSITIVE), // z.ai CN endpoint token overflow
        Pattern.compile("request_too_large", Pattern.CASE_INSENSITIVE), // Anthropic request byte-size overflow (HTTP 413)
```

⚠️ **顺序承重**：`OVERFLOW_PATTERNS` 的**顺序与 pi 逐条对应**（类 javadoc 明写），插入点必须是
`prompt too long` 之后、`request_too_large` 之前 —— 不能追加到末尾。

### 2.2 `RetryableError` 瞬断白名单 ＋3 条

**(a) `model is at capacity`** —— pi `retry.ts:32-34`：

```ts
	// Generic provider load, HTTP status, and server-side transient failures.
	"overloaded",
	"currently experiencing high demand",
	"model is at capacity",
```

**Java 现状**（`RetryableError.java:64-66`）缺第三条：

```java
            // Generic provider load, HTTP status, and server-side transient failures.
            "overloaded",
            "currently experiencing high demand", // retry.ts:29（紧跟 overloaded）
            "rate.?limit",
```

**改为**：

```java
            // Generic provider load, HTTP status, and server-side transient failures.
            "overloaded",
            "currently experiencing high demand", // retry.ts:29（紧跟 overloaded）
            "model is at capacity", // retry.ts:34
            "rate.?limit",
```

**(b)+(c) 两条 ChatGPT 订阅路** —— pi `retry.ts:95-102`：

```ts
	// gRPC based providers (e.g. NVIDIA NIM)
	"ResourceExhausted",

	// Sign in with ChatGPT: usage or user data temporarily unavailable. Usage
	// failures can arrive mid-stream without an HTTP 503 in the message.
	"subscription_sharing_usage_unavailable",
	"subscription_sharing_user_unavailable",
]);
```

**Java 现状**（`RetryableError.java:126-128`，**表尾**）：

```java
            // gRPC based providers (e.g. NVIDIA NIM)
            "ResourceExhausted"
        ));
```

**改为**：

```java
            // gRPC based providers (e.g. NVIDIA NIM)
            "ResourceExhausted",

            // Sign in with ChatGPT: usage or user data temporarily unavailable. Usage
            // failures can arrive mid-stream without an HTTP 503 in the message.（retry.ts:100-101）
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable"
        ));
```

### 2.3 `RetryableError` 配额排除表 ＋1 条

**pi**（`retry.ts:23-27`）：

```ts
	"insufficient_quota",
	"out of budget",
	"quota exceeded",
	"billing",

	// Sign in with ChatGPT: the subscription's shared usage limit, which resets
	// after hours rather than seconds.
	"subscription_sharing_usage_limit_exceeded",
]);
```

**Java 现状**（`RetryableError.java:53-55`）—— 缺 `billing` 之后那条：

```java
            "quota exceeded",
            "billing"
        ));
```

**改为**：

```java
            "quota exceeded",
            "billing",

            // Sign in with ChatGPT: the subscription's shared usage limit, which resets
            // after hours rather than seconds.（retry.ts:27）
            "subscription_sharing_usage_limit_exceeded"
        ));
```

---

## 3. ⚠️ 三处最容易写错的地方

**① 排除表 vs 白名单 —— 同族相反裁决。**
`subscription_sharing_usage_limit_exceeded`（**不重试**）与 `subscription_sharing_usage_unavailable`
（**重试**）前缀相同、裁决相反。**必须分别落在两个不同的表里** —— 全塞进白名单会
把配额墙当瞬断（无限重试），全塞进排除表会让订阅路永不重试。

**② 排除表是「先判、压倒白名单」。**
`RetryableError` 的判据是 **白名单 ∧ ¬排除表**（类 javadoc：`stopReason === "error"` ∧
`errorMessage` 非空 ∧ 不命中配额排除表 ∧ 命中瞬断白名单）。所以排除表那条**即使**同时命中白名单，
也判 false —— 无需担心两表冲突。

**③ `model is at capacity` 的位置。**
pi 把它放在**通用组**（紧跟 `overloaded`/`high demand`），不放在 `420`/HTTP 码那一段。
插入点须对应 `retry.ts:34` 的位置。

---

## 4. 文件清单与测试

**改动**：`ContextOverflow.java`（+1 行）· `RetryableError.java`（+5 行）。**无新文件、无签名变更、无 pom 变更。**
两文件现为 155 / 153 行，远低于 500。

**夹具**：两文件均已有参数化测试类，**在现有 `@MethodSource` 表里加行**即可（不新建夹具类）：

| 夹具 | 文件 | 加什么 |
|---|---|---|
| `errorMessagesAreOverflow` | `ContextOverflowTest.java:76` | ＋`"Prompt exceeds max length"`（z.ai CN） |
| `transientErrorsAreRetryable` | `RetryableErrorTest.java:78` | ＋`"model is at capacity"`、`"subscription_sharing_usage_unavailable"`、`"subscription_sharing_user_unavailable"` |
| `quotaLimitsAreNeverRetryable` | `RetryableErrorTest.java:99` | ＋`"subscription_sharing_usage_limit_exceeded"` |

**另加 1 条非参数化夹具**（钉 §3-① 的同族相反裁决）：

```
sharingFamilySplitsBetweenTheTwoTables()
  —— 「subscription_sharing_usage_limit_exceeded」⇒ false
  —— 「subscription_sharing_usage_unavailable」⇒ true
  —— 两条同前缀、断言相反值；单表实现必红
```

---

## 5. 变异探针计划（5 个，每个都必须**先确认落地**）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | 删掉 `prompt exceeds max length` | 1（`errorMessagesAreOverflow`） |
| M2 | 删掉 `model is at capacity` | 1（`transientErrorsAreRetryable`） |
| M3 | 删掉两条 `subscription_sharing_*_unavailable` | 1（同上） |
| M4 | 删掉 `subscription_sharing_usage_limit_exceeded` | 1（`quotaLimitsAreNeverRetryable`） |
| M5 | **把 `..._limit_exceeded` 从排除表挪进白名单** | 1（`sharingFamilySplitsBetweenTheTwoTables`）—— 钉 §3-① |

⚠️ **M5 是本包唯一有语义分量的探针**：M1–M4 只是「删了就没」的平凡检查；
M5 才是**两个表的分工**有没有被写错。若 M5 不红，说明「同族相反裁决」没被夹具钉住。

> **探针纪律**（本仓血泪，`docs/41`/`docs/42` 同款）：变异后必须 **① 确认编译通过**
> （否则读到的是**上一次运行的旧 surefire 报告**）＋ **② grep 复核变异是否落地**。

---

## 6. 台账影响

| # | 条目 |
|---|---|
| —— | **不新增**台账条目：这是补齐 `docs/41 §1.1` 已登记的 w2 缺口，闭环时回填 `docs/41` 的对应行 + `docs/map/02`。 |
| —— | `docs/41 §1.1` 表里的「`RetryableError` 缺 3 条」「`ContextOverflow` 缺 1 条」两行 ⇒ 闭环后标 ✅。 |
| `87` | ⚠️ **假缺失**（§9）：`MistralConversationsCompat` 实测已完全接线 ⇒ 从 `docs/41 §1.2` **移除**（地图腐烂，同 `B21`）。 |
| `37` | **改写**：非「真消费者未接」，是**已书面裁决的延后**（`Message.java:163-176`，2026-10-03）。 |
| `86` | **改写**：非「无字段」，是「字段在、刻意忽略；真缺口＝`CacheWarmer` **组件**」。 |

**回填**：`docs/map/02-ai.md` §1.1 的两条 w2 单元（`ContextOverflow` z.ai CN 模式 · `RetryableError` 新模式）
由**缺失**翻**对齐**，并重算 ai 模块的 ω/Σ(w×c)（权重各 2 ⇒ Σ(w×c) +2.0×2，见闭环时的实测）。

---

## 7. 实施记录

（待填：commit / 测试数 / 探针命中）

---

## 8. 取证方法（复现用）

```bash
# pi 侧（一律 git show，别读工作树）
git -C /d/workplaceForai/pi show 200387122:packages/ai/src/utils/overflow.ts
git -C /d/workplaceForai/pi show 200387122:packages/ai/src/utils/retry.ts

# 逐条 diff（避免目测漏条）
#   pi：抽出两个 buildProviderErrorPattern([...]) 块里的字符串
#   java：抽出两个 List.of(...) 块里的字符串
#   再 comm -23 <(sort -u pi) <(sort -u java)   # pi 有、java 无
```

⚠️ **本包的第一版就漏了一条**（`model is at capacity`）—— 初判是「缺 2 条」，逐条 diff 后是 **3 条**。
**凡是「某个表缺 N 条」的断言，必须用集合 diff 取证，不能靠通读。**

---

## 9. ⚠️ 用户问「能否把 `docs/41 §1.2` 的 w1 那批并进本包」—— 实测否决

用户提议把 `§1.2` 的「模型/目录元数据（8 条）」并进本包，一次补完 ai 广度。
**逐条回测后：不可并。** 9 条里只有 **1 条**（`91`）能搭车，其余各自单开包或即刻出局。

> 取证：`git show 200387122:<path>` 读 pi ＋ 读 pi-java 工作树；可达性口径＝
> **pi 自己的内置目录（`models.generated.ts`）有没有模型带这个字段**（生产者才是可达性证据）。

| # | 单元 | pi 证据（声明／消费） | pi 内置目录带它的模型数 | 裁决 |
|---|---|---|---|---|
| `91` | `samplingParamsByThinkingLevel` | `types.ts:1133` / `simple-options.ts:30` | **0** | ✅ **可搭车**（见下） |
| `90` | `Model.inputLimits` | `types.ts:1107` / `agent-session.ts:678`、`read.ts:117`（**仅 `images.resize`**） | **0** | ⤵ 单开包；且**跨 ai＋agent-core**（resize 消费点在 agent-core）；pi 自己的 `maxRequestBytes`/`maxPerMessage`/`maxPerRequest` **声明但未实施**（`coding-agent/docs/models.md:89`） |
| `86` | `Model.promptCache` | `types.ts:1127` / `cache-warmer.ts:44` | **0** | ⛔ **改写**：非「无字段」—— 字段以 raw `Map` 存在且**刻意忽略**（`ModelJsonMerge.java:157`、`ModelsJsonSchema.java:51`）。真缺口是 **`CacheWarmer` 组件**（未移植）⇒ 单开包 |
| `37` | `responseModel`/`responseId`/`thinkingLevel`/`diagnostics` | `types.ts:554-560` / `usage-totals.ts:68`、`cache-warmer.ts:345` | —— | ⛔ **已书面裁决的延后**（`Message.java:163-176`，2026-10-03）：消费点所在模块 pi-java 没有 ⇒ 现在移植＝有生产者无消费者。等模块包 |
| `87` | `MistralConversationsCompat` | `types.ts:966` / `mistral-conversations.ts:130` | —— | ❌ **假缺失** —— 实测已完全接线（`ModelCompat.java:302` → `Transcripts.java:209` → `MistralConversationsApi.java:291` → `CompatResolver.forMistral` 返 `FALSE`，对齐 pi 的 `?? false`）。**零移植量，出局** |
| `33` | 图像模型目录／注册表 | `providers/all.ts:72/:111`、`images-api-registry.ts:47`、`images.ts:7-24` | —— | ⛔ **shape-only** —— 缺的是**结构**（多类型统一面＋images api 注册表），不是覆盖；pi-java 只有 1 条图像车道（`openRouterImageModels()` 8 条）⇒ 注册表无处多路复用。随微信道包 |
| `93` | `ToolResultMessage.nestedCalls` | `types.ts:600/:606` / `nested-tool-calls.ts:45` | —— | ⛔ **真缺口但不可单独移植** —— 字段无生产者（`ctx.executeTool`／codemode 未实现，`ToolResult.java:24` 明写）⇒ 单字段是惰性的。**挂 codemode 包的乘客** |
| `92` | 统一模型类型系统（union） | `types.ts:1099-1171`（`BaseModel`/`Model`/`ImageModel`/`ClassifierModel`＋`isModelType`） | —— | ⛔ **shape-only** —— pi-java 用 `ModelCapability` 能力集表达「模型种类」，是刻意的 ADT 选择。要改 union＝全目录重构 ⇒ 单开包 |
| `94` | 结构化分类 API | `types.ts:318/:385/:635-688`、`models.ts:348`（`Models.classify()` 是**公开** SDK 面） | —— | ⛔ **真能力、可达**（`typesafe-system-one` 骑 openrouter＝主流 7 家），**但 coding-agent 主线从不调用**（只测试＋ README 用）⇒ 属 **pi-ai SDK 面**（同图像生成类）。单开包，数百行 |

### 9.1 唯一可搭车的 `91` —— 实际形状与成本

**pi**（`simple-options.ts:27-34`，逐字）：

```ts
export function resolveSamplingParams(
	model: Model<Api>,
	thinkingLevel: ModelThinkingLevel,
	requestParams?: SamplingParams,
): SamplingParams | undefined {
	const effectiveThinkingLevel = clampThinkingLevel(model, thinkingLevel);
	const thinkingLevelParams = model.samplingParamsByThinkingLevel?.[effectiveThinkingLevel];
	return model.samplingParams || thinkingLevelParams || requestParams
		? { ...model.samplingParams, ...thinkingLevelParams, ...requestParams }
		: undefined;
}
```

**pi-java**（`SimpleOptions.java:151-154`）只落**模型级一层**：

```java
    public static Optional<Map<String, Object>> samplingParamsOf(ModelInfo model) {
        var params = model == null ? null : model.samplingParams();
        return params == null || params.isEmpty() ? Optional.empty() : Optional.of(params);
    }
```

⇒ **差距**：`thinkingLevelParams` 那一层被静默丢弃（per-request 那层已登记为 `B125`，本包不造通道）。

**⚠️ 可达性对 pi-java 是零**（`docs/41 §1.2` 的引导线索是**不完整**的）：本字段的**唯一生产者**是 `models.json`
（`ModelsJsonSchema.ModelOverrideDef`），而 pi-java **根本不解析**它 —— 解析器里**没有这个键**
（`ModelsJsonSchema.java:45-56` 逐字段核过：`name`/`reasoning`/`thinkingLevelMap`/`input`/`cost`/`promptCache`/
`contextWindow`/`maxTokens`/`samplingParams`/`headers`/`compat`，**无 `samplingParamsByThinkingLevel`**）。

⇒ 要搭车，**不是 +1 字段**，而是链式四处：`ModelInfo` 加组件 → `ModelsJsonSchema`（`ModelDef` ＋ `ModelOverrideDef`）
加键 → `ModelJsonMerge` 加合并 → `SimpleOptions.samplingParamsOf` 加宽合并（并需加载 `clampThinkingLevel`，即
调用点要带模型思考级别）。**是一个真管道包，不是元数据补行。**

### 9.2 结论

**本包维持原样**（§2 的 +6 行、5 夹具、5 探针），**不并入 §1.2**。
w1 那批按性质分流：`87` 出局（假缺失）· `37`/`86` 改写后留待模块包 · `90`/`91`/`93` 各自单开包 ·
`33`/`92` shape-only（或随微信道包）· `94` pi-ai SDK 面单开包。

> **教训（与 §8 同源）**：`§1.2` 表**自己就标了「⤵ 未逐一重测」** —— 凡承自地图的「缺 N 条」，不重测就动手会
> 做已完成或不可达的活。**这份否决本身是回测的产物，不是阅读的产物。**
