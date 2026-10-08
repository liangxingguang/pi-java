# 43 ai 模式表补齐 —— `ContextOverflow` ＋ `RetryableError`

> **状态：📝 待审核（2026-10-09）。**
> 承接 `docs/41 §6` 的推进次序 **②**（「ai 平凡档」）。
> 本包是 `docs/41` 盘点里**成本最低**的一项：**纯字符串表补齐，无架构面**。

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
