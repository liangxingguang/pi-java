# 10 — Google / Mistral 车道的思考配置（B155）

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f`。
> **状态：✅ 已闭环（2026-10-04）** —— 用户裁决「审核通过，J1–J5 全按建议」；实施记录见 **§12**（先红 4/6・3/5、探针 M1–M6 各 ∧1、模块 1377/1377、全 reactor 14/14 SUCCESS）。⚠️ 两条车道的思考配置**从「一个字段都不发」变为按 pi 发**（§12.8 的行为变更）。
> **来源**：`docs/09 §3.1`／`§5 J2` 的设计期取证 —— 包 09 实测这两条车道的思考配置是**移植缺失**（pi 的六件套在本仓 `grep` 全 0 命中），登记为 **`docs/05` B155**。

---

## 1. 背景与范围

包 09 只做了 responses/azure 的思考块。剩下两条**主流车道**在 pi 里也发思考配置，本仓**一行都不发**：

| 车道 | pi 发什么 | 本仓 |
|---|---|---|
| `google-generative-ai` | `config.thinkingConfig = { includeThoughts:true, thinkingLevel }` 或 `{ includeThoughts:true, thinkingBudget }`；关思考时 `getDisabledGoogleThinkingConfig(model)` | `GoogleGenerativeAiApi.buildConfig` **没有任何 thinking 落点** |
| `mistral-conversations` | body 的 `promptMode:"reasoning"` ／ `reasoningEffort:"none"\|"high"` | `MistralConversationsApi` 的 body **没有这两个键** |

**可达性（设计期实测，2026-10-04）** —— 本仓内置目录（`BuiltinCatalog`）的逐条判定：

| 内置模型 | 能力位 | 命中哪一支 | 结论 |
|---|---|---|---|
| `gemini-2.5-pro` | `googleCaps()` **带 THINKING** | `/gemini-3(?:\.\d+)?-(?:pro\|flash)/` **不匹配** ⇒ **budget 分支**；`getGoogleBudget` 的 `2.5-pro` 表命中 | ✅ **生产可达** |
| `gemini-2.5-flash` | 同上 | 同上 ⇒ `2.5-flash` 表命中 | ✅ **生产可达** |
| `mistral-large` | `frontierChatCaps()` **带 THINKING** | 不在 `usesReasoningEffort` 白名单 ⇒ `usesPromptModeReasoning` 真 ⇒ **`promptMode:"reasoning"`** | ✅ **生产可达** |
| `mistral-small` | `chatCaps()` **不带 THINKING** | `shouldUseReasoning` 假 | ⛔ 不发 |

⚠️ **`thinkingLevel`（level）分支在本仓内置目录不可达** —— 唯一匹配 `gemini-3-*` 的内置条目是**图片模型**（`google/gemini-3-pro-image`）。它仍要落（`models.json` 可加 chat 型 gemini-3）。
⚠️ **`reasoningEffort` 分支在本仓内置目录不可达**（白名单四个 id 都不在内置目录里）。

**排除**：`google-vertex`（本仓不携带该车道）、Anthropic 的 budget 型思考（已落地）、Mistral 的 `promptCacheKey`（另属缓存族）。

---

## 2. 设计期实测 · pi 侧逐字码

### 2.1 Google：`streamSimple` 的钳制与两分支（`api/google-generative-ai.ts:317-346`）

```ts
	if (!options?.reasoning) {
		return stream(model, context, { ...base, thinking: { enabled: false } } satisfies GoogleOptions);
	}

	const clampedReasoning = clampThinkingLevel(model, options.reasoning);
	if (clampedReasoning === "off") {
		return stream(model, context, { ...base, thinking: { enabled: false } } satisfies GoogleOptions);
	}
	const resolvedLevel = resolveGoogleThinkingLevel(model, clampedReasoning);

	if (usesGoogleThinkingLevel(model)) {
		return stream(model, context, {
			...base,
			thinking: {
				enabled: true,
				level: toGoogleThinkingLevel(resolvedLevel),
			},
		} satisfies GoogleOptions);
	}

	return stream(model, context, {
		...base,
		thinking: {
			enabled: true,
			budgetTokens: getGoogleBudget(model, resolvedLevel, options.thinkingBudgets),
		},
	} satisfies GoogleOptions);
```

### 2.2 Google：写点（`api/google-generative-ai.ts:401-409`）

```ts
	if (options.thinking?.enabled && model.reasoning) {
		const thinkingConfig: ThinkingConfig = { includeThoughts: true };
		if (options.thinking.level !== undefined) {
			thinkingConfig.thinkingLevel = toGoogleSdkThinkingLevel(options.thinking.level);
		} else if (options.thinking.budgetTokens !== undefined) {
			thinkingConfig.thinkingBudget = options.thinking.budgetTokens;
		}
		config.thinkingConfig = thinkingConfig;
	} else if (model.reasoning && options.thinking && !options.thinking.enabled) {
		config.thinkingConfig = getDisabledGoogleThinkingConfig(model);
	}
```

⚠️ **`includeThoughts: true` 只在开思考那一支设**（`:403`）；关思考走 `getDisabledGoogleThinkingConfig`，那个对象里**没有** `includeThoughts` 键。

### 2.3 Google：六件套（`api/google-shared.ts`）

```ts
export type GoogleApiThinkingLevel = "THINKING_LEVEL_UNSPECIFIED" | "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
export type ResolvedGoogleThinkingLevel = Exclude<ThinkingLevel, "xhigh" | "max">;

const GOOGLE_SDK_THINKING_LEVEL_MAP: Record<GoogleApiThinkingLevel, GoogleSdkThinkingLevel> = {
	THINKING_LEVEL_UNSPECIFIED: GoogleSdkThinkingLevel.THINKING_LEVEL_UNSPECIFIED,
	MINIMAL: GoogleSdkThinkingLevel.MINIMAL,
	LOW: GoogleSdkThinkingLevel.LOW,
	MEDIUM: GoogleSdkThinkingLevel.MEDIUM,
	HIGH: GoogleSdkThinkingLevel.HIGH,
};

export function resolveGoogleThinkingLevel<T extends GoogleApiType>(
	model: Model<T>, level: ThinkingLevel,
): ResolvedGoogleThinkingLevel {
	const mapped = model.thinkingLevelMap?.[level];
	const resolvedLevel = typeof mapped === "string" ? mapped.toLowerCase() : level;
	switch (resolvedLevel) {
		case "minimal": case "low": case "medium": case "high":
			return resolvedLevel;
		default:
			throw new Error(
				`Unsupported Google thinking level mapping for ${model.provider}/${model.id}: ${level} -> ${String(mapped)}`,
			);
	}
}

export function usesGoogleThinkingLevel<T extends GoogleApiType>(model: Model<T>): boolean {
	const id = model.id.toLowerCase();
	return (
		/gemini-3(?:\.\d+)?-(?:pro|flash)/.test(id) ||
		id === "gemini-flash-latest" ||
		id === "gemini-flash-lite-latest" ||
		/gemma-?4/.test(id)
	);
}

export function toGoogleThinkingLevel(level: ResolvedGoogleThinkingLevel): GoogleApiThinkingLevel {
	switch (level) {
		case "minimal": return "MINIMAL";
		case "low": return "LOW";
		case "medium": return "MEDIUM";
		case "high": return "HIGH";
	}
}

export function toGoogleSdkThinkingLevel(level: GoogleApiThinkingLevel): GoogleSdkThinkingLevel {
	return GOOGLE_SDK_THINKING_LEVEL_MAP[level];
}

export function getDisabledGoogleThinkingConfig<T extends GoogleApiType>(model: Model<T>): ThinkingConfig {
	if (!usesGoogleThinkingLevel(model)) return { thinkingBudget: 0 };

	const fallback = clampThinkingLevel(model, "off");
	if (fallback === "off") return { thinkingBudget: 0 };

	const resolvedLevel = resolveGoogleThinkingLevel(model, fallback);
	const apiLevel = toGoogleThinkingLevel(resolvedLevel);
	return { thinkingLevel: toGoogleSdkThinkingLevel(apiLevel) };
}
```

⚠️ `resolveGoogleThinkingLevel` 的 **`default:` 是抛**（不是回落）——`xhigh`/`max` 若**没被夹掉**且目录没给映射，会抛。但 `streamSimple` 先 `clampThinkingLevel` ⇒ 到这里的只可能是 4 级之一**或目录映射出的串**；映射值不合 4 级 ⇒ **抛**（这是明文选择）。

### 2.4 Google：`getGoogleBudget`（`api/google-generative-ai.ts:430-471`）

```ts
function getGoogleBudget(
	model: Model<"google-generative-ai">,
	level: ResolvedGoogleThinkingLevel,
	customBudgets?: ThinkingBudgets,
): number {
	if (customBudgets?.[level] !== undefined) {
		return customBudgets[level]!;
	}

	if (model.id.includes("2.5-pro")) {
		const budgets: Record<ResolvedGoogleThinkingLevel, number> = {
			minimal: 128, low: 2048, medium: 8192, high: 32768,
		};
		return budgets[level];
	}

	if (model.id.includes("2.5-flash-lite")) {
		const budgets: Record<ResolvedGoogleThinkingLevel, number> = {
			minimal: 512, low: 2048, medium: 8192, high: 24576,
		};
		return budgets[level];
	}

	if (model.id.includes("2.5-flash")) {
		const budgets: Record<ResolvedGoogleThinkingLevel, number> = {
			minimal: 128, low: 2048, medium: 8192, high: 24576,
		};
		return budgets[level];
	}

	return -1;
}
```

⚠️ **三张表的 `low`/`medium` 相同、`minimal`/`high` 不同**；`-1` 是「未知模型」的哨兵（`thinkingBudget: -1` 在 Google 语义里＝**动态**）。

### 2.5 Mistral：`streamSimple`（`api/mistral-conversations.ts:200-208`）

```ts
	const clampedReasoning = options?.reasoning ? clampThinkingLevel(model, options.reasoning) : undefined;
	const reasoning = clampedReasoning === "off" ? undefined : clampedReasoning;
	const shouldUseReasoning = model.reasoning && reasoning !== undefined;

	return stream(model, context, {
		...base,
		promptMode: shouldUseReasoning && usesPromptModeReasoning(model) ? "reasoning" : undefined,
		reasoningEffort:
			shouldUseReasoning && usesReasoningEffort(model) ? mapReasoningEffort(model, reasoning) : undefined,
	} satisfies MistralOptions);
```

### 2.6 Mistral：三件套（`api/mistral-conversations.ts:898-916`）

```ts
function usesReasoningEffort(model: Model<"mistral-conversations">): boolean {
	return (
		model.id === "mistral-small-2603" ||
		model.id === "mistral-small-latest" ||
		model.id.startsWith("mistral-medium-") ||
		model.id === "zai-glm-5-2"
	);
}

function usesPromptModeReasoning(model: Model<"mistral-conversations">): boolean {
	return model.reasoning && !usesReasoningEffort(model);
}

function mapReasoningEffort(
	model: Model<"mistral-conversations">,
	level: Exclude<SimpleStreamOptions["reasoning"], undefined>,
): MistralReasoningEffort {
	return (model.thinkingLevelMap?.[level] ?? "high") as MistralReasoningEffort;
}
```

⚠️ **两个 helper 是互斥的两支**：命中 id 白名单 ⇒ 发 `reasoningEffort`（`"none"|"high"`，由 `thinkingLevelMap` 映射、缺省 `"high"`）；否则（只要 `model.reasoning`）⇒ 发 `promptMode:"reasoning"`。

### 2.7 Mistral：写点（`api/mistral-conversations.ts:525-526`）

```ts
	if (options?.promptMode) payload.promptMode = options.promptMode;
	if (options?.reasoningEffort) payload.reasoningEffort = options.reasoningEffort;
```

⚠️ 两处都是**真值判断** ⇒ 空串/undefined 不发。`MistralReasoningEffort = "none" | "high"`（`:34`）。

---

## 3. pi-java 侧现状（`file:line`）

| 位置 | 现状 |
|---|---|
| `protocol/GoogleGenerativeAiApi.java:327-371` `buildConfig` | systemInstruction／`maxOutputTokens`／temperature／tools／toolConfig —— **无 thinkingConfig** |
| `protocol/MistralConversationsApi.java:285-301` body 构造 | `model`／`stream`／`messages`／`tools`／`max_tokens`／`temperature` —— **无 promptMode／reasoningEffort** |
| `api/StreamRequest.java:51` | `Optional<ThinkingLevel> reasoning` —— **通道已通**（pi 的 `SimpleStreamOptions.reasoning`） |
| `api/SimpleOptions.java:195-201` `clampedReasoningEffort` | pi 的 `clampThinkingLevel` 包装，**复用即可**（包 09 §12 的归属前移） |
| `thinking/ThinkingBudgets.java` | pi 的 `ThinkingBudgets`（4 级，`DEFAULT` 1024/2048/8192/16384）—— ⚠️ **这不是 Google 的 `getGoogleBudget` 表**（两张不同的表） |

**SDK 实测（2026-10-04，`docs/09` 同法）**：`google-genai 1.72.0` **全套访问器都有**，不需要树路：

```
GenerateContentConfig$Builder.thinkingConfig(ThinkingConfig | Builder)          ✅
ThinkingConfig$Builder.thinkingBudget(java.lang.Integer)                        ✅
ThinkingConfig$Builder.thinkingLevel(ThinkingLevel | Known | String)            ✅
ThinkingConfig$Builder.includeThoughts(boolean)                                 ✅
ThinkingLevel$Known = THINKING_LEVEL_UNSPECIFIED | MINIMAL | LOW | MEDIUM | HIGH ✅（与 pi 的 MAP 一一对应）
```

Mistral 车道是**裸 JSON body（`Map`）** ⇒ 两个键直接 `put`，无 SDK 问题。

---

## 4. 改动点（逐点：pi 落点 → java 待写代码）

### 4.1 新建 `protocol/GoogleThinking.java`（§2.3 的六件套逐字移植）

```java
final class GoogleThinking {
    private GoogleThinking() {}

    /** pi google-shared.ts:72-84 —— 只选**线格形状**（level 还是 budget），与支持级别无关。 */
    static boolean usesThinkingLevel(ModelInfo model) {
        var id = model.id().modelName().toLowerCase(Locale.ROOT);
        return GEMINI3_PRO_FLASH.matcher(id).find()          // /gemini-3(?:\.\d+)?-(?:pro|flash)/
            || id.equals("gemini-flash-latest")
            || id.equals("gemini-flash-lite-latest")
            || GEMMA4.matcher(id).find();                    // /gemma-?4/
    }

    /** pi google-shared.ts:48-67 —— 目录映射优先，**不合四级则抛**（pi 的 default: throw）。 */
    static GoogleLevel resolve(ModelInfo model, ThinkingLevel level) { ... }

    /** pi google-shared.ts:85-89。 */
    static String apiLevelName(GoogleLevel level) { ... }    // MINIMAL | LOW | MEDIUM | HIGH

    /** pi google-shared.ts:101-111。 */
    static Optional<ThinkingConfig> disabledConfig(ModelInfo model) { ... }

    /** pi google-generative-ai.ts:430-471 —— 三张表 + -1 兜底。 */
    static int budget(ModelInfo model, GoogleLevel level) { ... }
}
```

⚠️ **pi 的 `ResolvedGoogleThinkingLevel = Exclude<ThinkingLevel, "xhigh"|"max">`** 在 Java 上落成一个 4 值的 sealed/枚举（`GoogleLevel`），`resolve` 的返回值即它 —— 把「不可能出现 xhigh/max」变成**类型保证**。

### 4.2 `GoogleGenerativeAiApi.buildConfig` 接线

```java
        // pi google-generative-ai.ts:317-346 的 streamSimple 分支 → :401-409 的写点。
        if (request.model().capabilities().contains(ModelCapability.THINKING)) {
            var clamped = SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning());
            if (clamped.isPresent()) {                       // pi 的 `enabled:true` 支
                var level = GoogleThinking.resolve(request.model(), clamped.get());
                var tc = ThinkingConfig.builder().includeThoughts(true);          // pi :403
                if (GoogleThinking.usesThinkingLevel(request.model())) {
                    tc.thinkingLevel(GoogleThinking.apiLevelName(level));
                } else {
                    tc.thinkingBudget(GoogleThinking.budget(request.model(), level));
                }
                builder.thinkingConfig(tc.build());
            } else {
                GoogleThinking.disabledConfig(request.model())
                    .ifPresent(builder::thinkingConfig);
            }
        }
```

⚠️ **关思考支的判据看着像两支、其实是同一个**（设计期实测化简）：pi `:317` 在 `!options?.reasoning` 时发 `{enabled:false}`、`:322-325` 在夹成 `off` 时**也**发同一个 —— 而 `SimpleOptions.clampedReasoningEffort` 对这两种情形**都**返回 `Optional.empty()` ⇒ 一个 `else` 就够。**不需要**再判 `request.reasoning().isPresent()`。

### 4.3 新建 `protocol/MistralThinking.java`（§2.6 三件套）

```java
final class MistralThinking {
    /** pi :898-905 的 id 白名单。 */
    static boolean usesReasoningEffort(ModelInfo model) { ... }

    /** pi :907-909。 */
    static boolean usesPromptModeReasoning(ModelInfo model) {
        return model.capabilities().contains(ModelCapability.THINKING)
            && !usesReasoningEffort(model);
    }

    /** pi :911-916 —— 目录映射优先，缺省 "high"。 */
    static String effort(ModelInfo model, ThinkingLevel level) {
        return model.thinkingLevelMap().mapped(ModelThinkingLevel.of(level)).orElse("high");
    }
}
```

### 4.4 `MistralConversationsApi` 接线（body 的两个键）

```java
        // pi mistral-conversations.ts:200-208 → :525-526（两处都是真值判断）。
        var clamped = SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning());
        var shouldUseReasoning = request.model().capabilities().contains(ModelCapability.THINKING)
            && clamped.isPresent();
        if (shouldUseReasoning && MistralThinking.usesPromptModeReasoning(request.model())) {
            body.put("promptMode", "reasoning");
        }
        if (shouldUseReasoning && MistralThinking.usesReasoningEffort(request.model())) {
            body.put("reasoningEffort", MistralThinking.effort(request.model(), clamped.get()));
        }
```

---

## 5. 设计审核门（**实施前必须裁决**）

| # | 裁决点 | 选项 | 我的建议 |
|---|---|---|---|
| **J1** | **Google 的 budget 三张表** | ①逐字照抄 `getGoogleBudget`（含 `-1` 兜底）；②改用本仓的 `ThinkingBudgets` | **①** —— 两者**不是同一张表**（pi 的 `ThinkingBudgets` 是 `simple-options` 的通用表 1024/2048/8192/16384，`getGoogleBudget` 是 Google 专属 128/2048/8192/32768 等），混用会发错值；且**三张表在本仓可达**（§1 的可达性表）。⚠️ `customBudgets` 那支（`options.thinkingBudgets`）本仓**无选项通道**（`原 docs/57 §6 R7` 登记）⇒ 那支写死为「永不命中」 |
| **J2** | **Mistral 的 id 白名单可达性** | ①照抄白名单；②只落代码路径、不等目录 | **①**。✅ **已取证（2026-10-04）**：本仓内置目录有 `mistral-large`（`frontierChatCaps()`，**带 `THINKING`**）与 `mistral-small`（`chatCaps()`，**不带**）⇒ `mistral-large` 满足 `shouldUseReasoning`，且它的 id **不在** `usesReasoningEffort` 白名单里（白名单是 `mistral-small-2603`／`mistral-small-latest`／`mistral-medium-*`／`zai-glm-5-2`）⇒ **`promptMode:"reasoning"` 生产可达**；**`reasoningEffort` 支在本仓内置目录不可达**（只有 `models.json` 写一个白名单 id 时才命中） |
| **J3** | `resolveGoogleThinkingLevel` 的 **throw** | ①照抛（`IllegalStateException`）；②回落 high | **①** —— pi 的 `default:` 明文抛；回落＝发明行为。⚠️ 抛点在**流开始前**（buildConfig 期）⇒ 用户看到的是请求构建失败而非流中断，与 pi 同 |
| **J4** | 「请求完全不提 reasoning」时要不要发 `{enabled:false}` | — | ✅ **已解决（设计期实测）**：见 §4.2 —— pi 的两支等价于本仓的 `clamped.isEmpty()` **一个 else**，**照办**。⚠️ 但它是**行为变更**（此前该车道一个字段都不发）⇒ 需要真端点 smoke |
| **J5** | `includeThoughts` | pi 只在开思考支设 true；关思考支**不带该键** | **照抄**（不要「顺手补上」—— 关思考时带 `includeThoughts:true` 会让 Google 返回思考内容却 budget=0，语义不同） |

---

## 6. 每项验收门槛

1. **先红**：两条车道各一组 `RecordingHttpServer` wire 夹具 —— 断言出站 body 的 `config.thinkingConfig.*`／`promptMode`／`reasoningEffort`。**观测面盖全三层**（SDK 访问器／序列化 JSON／录制字节，`原 docs/54 §12.8` 的教训）。
2. **GREEN** ＋ 车道的既有回归。
3. **Mutation probe**：逐条撤接线（关分支／映射／白名单／budget 表），记录**精确红集**；⚠️ 变异后 `grep` 复核落地，且**变异必须能编译**（包 09 的 M3 教训）。
4. `mvn -o -pl pi-java-ai -am test`。
5. 全 reactor `mvn -o clean verify`（**串行**，不得与其它 Maven 并发）。
6. checkstyle 0 新违规、文件 ≤ 500 行（新类都很小）。

---

## 7. 明确不做

- **`google-vertex`**（本仓不携带该车道）。
- **Mistral 的 `promptCacheKey`**（缓存族，另议）。
- **`options.thinkingBudgets` 的选项通道**（`原 docs/57 §6 R7` 已登记，本包只写死「永不命中」）。

---

## 8. 已知风险

1. **两车道都是「新增出站字段」** ⇒ 对真实端点是**行为变更**（此前不发、此后发），需真端点 smoke（尤其 Gemini 的 `thinkingBudget: -1`）。
2. **`-1` 的语义**：Google 侧＝动态思考；写死成其它值会改变端点的思考行为。
3. **`resolveGoogleThinkingLevel` 抛**会让**请求构建期**失败 ⇒ 用户的模型目录里若给 `gemini-*` 配了不合四级的 `thinkingLevelMap`，会从「静默不发」变成「响亮失败」。这是**对齐 pi**，但要在 §12 记明。

---

## 9. 归属与回填

- 闭环时回填：本文件 banner、`docs/05` 的 **B155** 行、`docs/08` 的任务表、以及源码 javadoc。
- 归属前移回执：`SimpleOptions.clampedReasoningEffort` **复用不重接**（包 09 已如此）。
- 新登记走 `docs/05` 的追加节。

---

## 12. 实施记录（2026-10-04）

用户裁决：**审核通过，J1–J5 全按建议**。

### 12.1 提交清单

| 提交 | 内容 |
|---|---|
| `224a1fb` | 新建 `protocol/GoogleThinking.java`（六件套）＋ `protocol/MistralThinking.java`（三件套）；`GoogleGenerativeAiApi.buildConfig` 与 `MistralConversationsApi.buildRequestBody` 两处接线；两组 wire 夹具（6 files, +527） |

### 12.2 先红证据

| 夹具 | 跑数 | 红 |
|---|---|---|
| `GoogleThinkingWireTest`（新增） | 6 | **4 红** —— ⚠️ 两条是**空绿**：非 reasoning 的负对照 ＋ **关思考支**（那时断言写成 `path("thinkingBudget").asInt().isZero()`，节点缺席时 `asInt()` 也返回 0 ⇒ **没牙**） |
| `MistralThinkingWireTest`（新增） | 5 | **3 红**（2 条负对照空绿） |

⚠️ **实施期自己抓到并修的一处「夹具没牙」**：关思考支的断言加了一条前置 `assertThat(generationConfig.has("thinkingConfig")).isTrue()`，并**用 M6 复核**（撤掉该支 ⇒ 恰 1 红）——证明加牙之后才有判别力。

⚠️ **一处夹具写在实现之后**：`unsupportedDirectoryMappingThrows`（J3 的抛）是**随实现一起加的**，**没有先红**（包 09 的第 6 条教训重演）⇒ 它的牙只由 **M3** 证。

### 12.3 Mutation probe（∧ 指「恰」）

| 探针 | 变异 | 红集 |
|---|---|---|
| **M1** | `getGoogleBudget` 的 flash 表 `HIGH 24576 → 0` | ∧1：`budgetBranchUsesTheFlashTable` |
| **M2** | `usesThinkingLevel` 恒 false | ∧1：`levelBranchSendsThinkingLevel` |
| **M3** | `resolve` 的 `default` 抛 → 返回 HIGH | ∧1：`unsupportedDirectoryMappingThrows` |
| **M4** | `usesReasoningEffort` 恒 true | ∧1：`nonWhitelistedReasoningModelSendsPromptMode` |
| **M5** | `effort` 忽略目录映射 | ∧1：`whitelistedModelHonoursTheDirectoryMapping` |
| **M6** | `disabledConfig` 恒空 | ∧1：`disabledConfigUsesZeroBudgetForBudgetModels` |

⚠️ **M3 第一版是「改坏了 switch 表达式」的编译期变异**（`sed` 把 default 臂拆得语法不合法）⇒ 给不出红集。与包 09 的 M3 同族：**变异必须能编译**。改用 `perl -0pi` 整臂替换后 ∧1。

⚠️ 本轮改用**备份文件回滚**（`cp` 到 `/tmp` 再 `cp` 回来），每次回滚都 `diff -q` 校验 —— 包 09 那次 `sed` 回滚失败过一次。

### 12.4 实施期实测（补强设计稿）

1. **SDK 全套访问器**（`google-genai 1.72.0`）：`thinkingConfig(...)`／`thinkingBudget(Integer)`／`thinkingLevel(ThinkingLevel|Known|String)`／`includeThoughts(boolean)`；线格键名 `generationConfig.thinkingConfig.{includeThoughts,thinkingBudget,thinkingLevel}`（camelCase，`javap -v` 取的 `value=`）。⇒ **不需要树路**。
2. **可达性（实测）**：内置目录 `gemini-2.5-pro`／`2.5-flash` 带 THINKING、不匹配 `gemini-3-*` 正则 ⇒ **budget 分支生产可达**；`mistral-large` 带 THINKING 且不在白名单 ⇒ **`promptMode` 生产可达**；**level 分支与 `reasoningEffort` 分支在内置目录不可达**（前者只因内置的 gemini-3 是图片模型、后者白名单四个 id 都不在目录里）。
3. **关思考支的两支等价**（设计稿化简）：pi `:317` 的「无 reasoning」与 `:322-325` 的「夹成 off」在 java 上都是 `clampedReasoningEffort(...).isEmpty()` ⇒ 一个 `else`。
4. `MistralConversationsApi` 的 `buildRequestBody` 是裸 `HashMap` ⇒ 两个键直接 `put`，无 SDK 面。

### 12.5 验收

- `mvn -o -pl pi-java-ai -am test`：telemetry 31、ai **1377/1377** 绿（改前 1365；本包新增 12 条）。
- checkstyle：0 violation。
- 文件长度：新增 `GoogleThinking` **149**／`MistralThinking` **46**；`GoogleGenerativeAiApi` 418 ✓；`MistralConversationsApi` **510** —— **存量超限**（本仓既登记项），未拆。
- 全 reactor：见 §12.6。

### 12.6 全 reactor

`mvn -o clean verify`（全 14 模块，含 spotbugs ＋ checkstyle）：**BUILD SUCCESS，14/14 SUCCESS**。

⚠️ **TUI 模块耗时 36:12**（上一轮同模块 4:20）—— 机器负载所致，非本包影响；本包只碰 `pi-java-ai` 的两个车道 + 两个新类。⚠️ 本轮**全程未在 reactor 运行中改任何源码**（包 09 的教训）。

### 12.7 未做（明确）

- `options.thinkingBudgets` 的选项通道（`原 docs/57 §6 R7` 已登记）⇒ `getGoogleBudget` 的 `customBudgets` 那一支**写死为永不命中**。
- `google-vertex` 车道（本仓不携带）。
- Mistral 的 `promptCacheKey`（缓存族，另议）。

### 12.8 行为变更（需知悉）

两条车道从「**一个思考字段都不发**」变成按 pi 发 —— 对真实端点是**行为变更**。内置目录里 `gemini-2.5-pro`／`2.5-flash`（budget）与 `mistral-large`（`promptMode`）**立刻生效**，建议做一次真端点 smoke（尤其 Gemini 的 `thinkingBudget` 取值）。
