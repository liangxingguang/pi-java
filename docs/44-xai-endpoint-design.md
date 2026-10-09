# 44 xai provider 端点补齐

> **状态：✅ 已闭环（2026-10-10）。** 设计稿经用户审核通过后实施；探针 M1–M5 全中；
> `pi-java-ai` 1398 测试、`pi-java-evals` 45 测试、上游闭包 `clean verify` 全绿。
> 交付形态 ＝ **一条新 provider 车道** —— **无新协议**（`openai-responses` wire 已存在）、
> **无新架构面**。
> 承接 `docs/41 §1.1` w2 的**第①条**（`docs/41 §6` 推进次序里「ai 平凡档」之后的下一项）。
> 本包是 w2 里**边际最大**的一条：计分母 **+2 个单位**。
> 交付形态 ＝ **一条新 provider 车道** —— **无新协议**（`openai-responses` wire 已存在）、
> **无新架构面**。
>
> **先纠一处盘点错**：`docs/41` 写「xai.models.ts（**14 模型**）」—— 实测是 **4 条**
> （那个「14」是 `.ts` 文件的**行数**，不是模型数）。见 §1.1。

---

## 0. 一句话

xAI 是主流 7 家里**唯一没上车**的端点。compat 层**早就认识**它
（`CompatResolver.java:79/:98` 有 `provider.equals("xai")` 与 `isGrok` 分支），
OAuth 也在册（`OAuthProviders.java:30` 设备码），但**没有 provider 类承载它**
⇒ 用户把模型名写成 `xai/grok-4.7` 时，`ProviderRegistry` 查不到 provider，
模型退化成 `ModelInfo.minimal`，**一条车道都走不通**。

本包补的就是这个缺：一个 `XaiProvider`（`openai-responses` 单车道）
＋一份 4 条的内置目录 ＋一条 generator 期 compat 规则。

---

## 1. 缺口是什么

| 面 | pi 参照（锚点 `200387122`） | pi-java 现状 | 缺 |
|---|---|---|---|
| provider 类 | `providers/xai.ts`（13 行，`createProvider`） | — | **整个类** |
| 车道 | `api: openAIResponsesApi()` | `OpenAIResponsesApi` **已存在**（`openai` provider 在用） | 只是没接给 xai |
| 目录 | `providers/xai.models.ts`（14 **行**）＋ 生成数据 `data/xai.json` | — | **4 条模型** |
| compat 规则 | `generate-models.ts:472-474` `XAI_RESPONSES_COMPAT` | — | **1 个常量** |
| OAuth | `auth/oauth/load.ts`（xAI） | ✅ `OAuthProviders.java:30-35` 已在册 | 无 |
| 注册 | `providers/all.ts:173` `xaiProvider()` | — | `ProviderCatalog.all()` 加一行 |

### 1.1 ⚠️ 先纠一处盘点错（`docs/41 §1.1`）

`docs/41` 的 xai 行写：**`providers/xai.models.ts`（14 模型）**。

**实测是 4 条。** 取证（clone HEAD 恰是锚点 `200387122`，`data/.manifest.json`
的 `generatedAt` = `2026-10-04T15:45:53Z` ＝ 锚点当日 ⇒ 磁盘数据即锚点数据）：

```
$ node -e "const d=require('./packages/ai/src/providers/data/xai.json'); \
  for(const [g,m] of Object.entries(d)) console.log(g, Object.keys(m).length)"
openai-responses 4
```

那个「14」是 `xai.models.ts` 的**行数**（`git show 200387122:... | wc -l` = 14），
被误读成了模型数。这是 `docs/41` 里的**取证错**，本包顺带纠正 ——
`docs/41` 的 xai 行闭环回填时一并订正（见 §5）。

> **教训形态**：数「某文件有几条东西」时，**别用行数代替条数** ——
> 尤其当文件是「import + 三行导出」的生成壳子时（本仓已有一次同类：
> `docs/41` 曾把 `RetryableError` 的条数记错 2 条）。

---

## 2. 逐条变更（带 pi 逐字代码）

### 2.1 pi 侧三个文件（逐字）

**`providers/xai.ts`（全文 13 行）**：

```ts
import { openAIResponsesApi } from "../api/openai-responses.lazy.ts";
import { envApiKeyAuth, lazyOAuth } from "../auth/helpers.ts";
import { loadXaiOAuth } from "../auth/oauth/load.ts";
import { createProvider, type Provider } from "../models.ts";
import { XAI_MODELS } from "./xai.models.ts";

export function xaiProvider(): Provider<"openai-responses"> {
	return createProvider({
		id: "xai",
		name: "xAI",
		baseUrl: "https://api.x.ai/v1",
		auth: {
			apiKey: envApiKeyAuth("xAI API key", ["XAI_API_KEY"]),
			oauth: lazyOAuth({
				name: "xAI (Grok/X subscription)",
				isSubscription: true,
				loginLabel: "Sign in with SuperGrok or X Premium",
				load: loadXaiOAuth,
			}),
		},
		models: Object.values(XAI_MODELS),
		api: openAIResponsesApi(),
	});
}
```

⇒ 三个可移植点：**id `xai`／name `xAI`／baseUrl `https://api.x.ai/v1`**、
**apiKey 环境变量 `XAI_API_KEY`**、**单车道 `openai-responses`**。

**`generate-models.ts:472-474`（compat 常量）**：

```ts
const XAI_RESPONSES_COMPAT: OpenAIResponsesCompat = {
	supportsLongCacheRetention: false,
};
```

应用点 `generate-models.ts:2041-2061`：

```ts
		// Process xAi models
		if (data.xai?.models) {
			for (const [modelId, model] of Object.entries(data.xai.models)) {
				const m = model as ModelsDevModel;
				if (m.tool_call !== true) continue;

				models.push({
					id: modelId,
					name: m.name || modelId,
					api: "openai-responses",
					provider: "xai",
					baseUrl: "https://api.x.ai/v1",
					compat: { ...XAI_RESPONSES_COMPAT },
					...
```

⇒ **无条件**给每条 xai responses 模型 `supportsLongCacheRetention: false`
（与 id 无关 —— 是 provider 级常量）。

**`all.ts:173`（注册）**：`xaiProvider(),`（在 `vercelAIGatewayProvider()` 与
`xiaomiProvider()` 之间 —— 顺序无功能意义，仅叙事）。

### 2.2 新增 `XaiProvider.java`

`pi-java-ai/src/main/java/com/pijava/ai/provider/builtin/XaiProvider.java`：

```java
package com.pijava.ai.provider.builtin;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.OpenAIResponsesApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * xAI (Grok) provider —— 镜像 pi {@code providers/xai.ts}：一个 provider、一条车道
 * （{@code openai-responses}），baseUrl {@code https://api.x.ai/v1}，apiKey 取
 * {@code XAI_API_KEY}（pi {@code envApiKeyAuth("xAI API key", ["XAI_API_KEY"])}）。
 *
 * <p>OAuth（SuperGrok／X Premium 订阅，RFC 8628 设备码）已在
 * {@link com.pijava.ai.auth.OAuthProviders} 的 {@code "xai"} 条目落地 —— 本类补的是
 * <b>车道</b>：此前 compat 层（{@code CompatResolver} 的 {@code provider.equals("xai")}
 * 与 {@code isGrok} 分支）已认识 xai，却没有 provider 类可承载，是主流 7 家里唯一
 * 没上车的端点。</p>
 */
public final class XaiProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        // pi 是单车道 provider ⇒ ProviderConfig.single（OpenAIProvider 是多车道，不同形）。
        return ProviderConfig.single(
            "xai", "xAI", "https://api.x.ai/v1",
            "XAI_API_KEY", Protocol.OPENAI_RESPONSES, XaiModels.catalog());
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case OPENAI_RESPONSES -> new OpenAIResponsesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException(
                "XaiProvider cannot serve protocol " + protocol);
        };
    }
}
```

> **为什么 `single` 不是 `new ProviderConfig(...)` 六参**：pi 只有一条车道，
> 没有 per-protocol 派发（对比 `OpenRouterProvider` 的双车道）。`ProviderConfig.single`
> 把 `supportedProtocols` 收敛成 `{OPENAI_RESPONSES}`，`createChatApi` 的 `default`
> 分支因此**结构上不可达** —— 与 `OpenRouterProvider` 的防御式 `default` 同形。

### 2.3 新增 `XaiModels.java`

`pi-java-ai/src/main/java/com/pijava/ai/provider/builtin/XaiModels.java`
（转录自生成数据 `data/xai.json`，锚点快照 2026-10-04）：

```java
public final class XaiModels {

    private XaiModels() {}

    private static final String PROVIDER = "xai";

    // ── 思考级别表（转录自 pi 数据的 thinkingLevelMap；null 值 ≙ 显式不支持）────
    //    ⚠️ 常量名按「off / xhigh / max 三态」起，**不**沿用 OpenRouterModels 的
    //    TLM_OFF_UNSUPPORTED（那个只含 {off:null}，本组三条都含完整 low/medium/high）
    //    —— 同名不同内容是最容易骗过实现者的坑。

    /** {@code {off:"none", …, xhigh:null, max:null}} —— grok-4.3。 */
    private static final ThinkingLevelMap TLM_NONE_OFF_NO_XHIGH_NO_MAX = tlm(
        lv("off", "none"), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", null));

    /** {@code {off:null, …, xhigh:null, max:null}} —— grok-4.5。 */
    private static final ThinkingLevelMap TLM_NO_OFF_NO_XHIGH = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", null));

    /** {@code {off:null, …, xhigh:"xhigh", max:null}} —— grok-4.6、grok-4.7。 */
    private static final ThinkingLevelMap TLM_NO_OFF_XHIGH = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", "xhigh"), lv("max", null));

    /** 全部内置 xai chat 模型（4 条，全走 openai-responses）。 */
    public static ModelCatalog catalog() {
        return BuiltinCatalog.of(List.of(
            chat("grok-4.3", "Grok 4.3", 1_000_000, 30_000, 1.25, 2.5, 0.2,
                tiers(2.5, 5, 0.4), TLM_NONE_OFF_NO_XHIGH_NO_MAX),
            chat("grok-4.5", "Grok 4.5", 500_000, 500_000, 2, 6, 0.3,
                tiers(4, 12, 0.6), TLM_NO_OFF_NO_XHIGH),
            chat("grok-4.6", "Grok 4.6", 500_000, 500_000, 2, 6, 0.5,
                tiers(4, 12, 1), TLM_NO_OFF_XHIGH),
            chat("grok-4.7", "Grok 4.7", 500_000, 500_000, 2, 6, 0.5,
                tiers(4, 12, 1), TLM_NO_OFF_XHIGH)));
    }

    /**
     * 一条 xai chat 模型：{@code api} 组件必带 {@code "openai-responses"}
     * （pi 数据每组每条都是该值）；compat 走 {@link CatalogCompatRules#xaiResponses}。
     *
     * <p>{@code baseUrl} 组件**缺席**（{@code null}）：pi 数据每条都写
     * {@code "https://api.x.ai/v1"}，但那与 provider 默认值同源 ⇒ 由
     * {@code ConfigurableProvider.effectiveOptions} 在请求期补，不逐条转录
     * （与 {@code OpenRouterModels} 同口径）。</p>
     */
    private static ModelInfo chat(String id, String display, int contextWindow, int maxTokens,
            double in, double out, double cacheRead, List<CostTier> tiers,
            ThinkingLevelMap levels) {
        return new ModelInfo(
            ModelId.of(PROVIDER, id), display,
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT, ModelCapability.TOOL_USE,
                ModelCapability.THINKING, ModelCapability.STREAMING, ModelCapability.PROMPT_CACHING),
            contextWindow, maxTokens, false,
            new PricingInfo(in, out, cacheRead, 0, tiers), levels, Map.of(), Map.of(),
            CatalogCompatRules.xaiResponses(PROVIDER), "openai-responses", null);
    }

    /** pi 每条 xai 模型的 {@code cost.tiers}（{@code inputTokensAbove:200000}）。 */
    private static List<CostTier> tiers(double in, double out, double cacheRead) {
        return List.of(new PricingInfo.CostTier(200_000, in, out, cacheRead, 0));
    }

    // lv() / tlm() 两个 helper 与 OpenRouterModels 逐字相同（转录同形）。
}
```

**能力集映射**（与 `OpenRouterModels` 同口径）：4 条全是
`reasoning:true`、`input:["text","image"]`、`cost.cacheRead>0` ⇒
`TEXT + IMAGE_INPUT + TOOL_USE + THINKING + STREAMING + PROMPT_CACHING`（恒定）。

### 2.4 `CatalogCompatRules` ＋1 条规则

`catalog/CatalogCompatRules.java` 末尾（`openaiResponses` 之后）：

```java
    /**
     * openai-responses 车道的 xai 目录标注 —— pi {@code XAI_RESPONSES_COMPAT}
     * （{@code generate-models.ts:472-474}）＋ 应用点 {@code :2053}：<b>全部</b> xai
     * responses 模型**无条件**得 {@code supportsLongCacheRetention:false}（与 id 无关）。
     * 非 xai provider 返回 {@link ModelCompat#NONE}。
     *
     * <p>动机：xAI 的 prompt cache 不走 OpenAI 的 {@code prompt_cache_retention:"24h"}
     * 长缓存语义（{@code openai-responses.ts:101}）⇒ 该位为假后，responses 车道在
     * {@code cacheRetention=="long"} 时**不发**那个键。java 侧读点
     * （{@code ResponsesMessageConverter.applyCacheRetention} 的 {@code longOk}）
     * 已就位 ⇒ 本规则一落，行为即刻对齐。</p>
     */
    public static ModelCompat xaiResponses(String provider) {
        if (!"xai".equals(provider)) {
            return ModelCompat.NONE;
        }
        // 23 参便捷构造，位置 15（supportsLongCacheRetention）显式 false。
        return new ModelCompat(false, null, true, false, null, null, null, null, null, true,
            null, null, null, null, Boolean.FALSE, null, null, null, null, null,
            Map.of(), Map.of(), null);
    }
```

> **为什么进 `CatalogCompatRules` 不进 `XaiModels`**：该类的定位是
> 「pi **生成期**规则里与代码推导相关的那几条 —— 照抄谓词而非硬编值」。
> `XAI_RESPONSES_COMPAT` 正是生成期常量（`generate-models.ts` 里的字面量）
> ⇒ 放这里，pi 改常量时 java 的对应点**一一可查**；硬编进目录文件则会与
> `openaiResponses`／`anthropic` 两条既有规则**不同源**。

### 2.5 注册 —— `ProviderCatalog.all()` ＋1 行

```java
        providers.add(new OpenRouterProvider());
        providers.add(new OpenRouterImagesProvider());
        providers.add(new XaiProvider());            // ← 新增
        return List.copyOf(providers);
```

同时改类注释的计数（`18 total` → `19 total`）。**顺序无功能意义**
（registry 是 map），追加在末尾 ⇒ 最小 diff。

> ⚠️ **`ModelData.generatedAt()` 不动**：它是**整批内置目录共用的单一时间戳**，
> 语义是「这批数据对应的 pi 生成数据是哪天」（现为 `2026-09-20`）。xai 这 4 条
> 与新锚点的关系**不因本包改变该值** —— 改它会波及远程目录守卫（详见该字段 javadoc）。

---

## 3. 目录数据（逐条转录核对表）

转录源：`data/xai.json`（锚点快照）。**逐条**对照，无省略：

| id | display | ctx | maxTokens | in | out | cacheRead | tier(>200k) in/out/cacheRead | thinkingLevelMap |
|---|---|---|---|---|---|---|---|---|
| `grok-4.3` | Grok 4.3 | 1 000 000 | 30 000 | 1.25 | 2.5 | 0.2 | 2.5 / 5 / 0.4 | off=none, xhigh=null |
| `grok-4.5` | Grok 4.5 | 500 000 | 500 000 | 2 | 6 | 0.3 | 4 / 12 / 0.6 | off=null, xhigh=null |
| `grok-4.6` | Grok 4.6 | 500 000 | 500 000 | 2 | 6 | 0.5 | 4 / 12 / 1 | off=null, xhigh=xhigh |
| `grok-4.7` | Grok 4.7 | 500 000 | 500 000 | 2 | 6 | 0.5 | 4 / 12 / 1 | off=null, xhigh=xhigh |

四条恒有：`api="openai-responses"`、`baseUrl="https://api.x.ai/v1"`、
`provider="xai"`、`compat={supportsLongCacheRetention:false}`、
`reasoning=true`、`input=["text","image"]`、`cacheWrite=0`。

**不转录的两项**（各有归属）：

| pi 数据键 | 为何不转录 |
|---|---|
| `inputLimits.images.resize`（4 条都有） | java `ModelInfo` **无该组件** —— 属 `docs/41 §1.2` 的「inputLimits」缺口行（`90`），**不在本包** |
| `cost.tiers` 的 `cacheWrite` | pi 恒 `0`；java `CostTier` 收下（`0`），无差 |

---

## 4. 波及面（计数与下游）

| 落点 | 现状 | 变更 | 触发文件 |
|---|---|---|---|
| `ProviderCatalog` 注释 | `18 total` | `19 total` | `ProviderCatalog.java:17` |
| `ProviderRegistryTest` | `isEqualTo(18)` / `hasSize(18)` ＋ 方法名 `…Eighteen` | `19` / `…Nineteen` ＋ `get("xai")` | `provider/ProviderRegistryTest.java:12-20` |
| `ProviderCatalogConformanceTest` | `EXPECTED_NAMES` 18 项 ＋ 方法名 `catalogContainsEighteenProviders` | 加 `"xai"` ＋ 改名 `…NineteenProviders` | `provider/ProviderCatalogConformanceTest.java:23-34` |
| evals 一致性 | `size() != 18` ＋ 用例名 `eighteen-providers` | `19` ＋ `nineteen-providers` | `evals/.../ProviderCatalogConformance.java:31-36` |
| `ProviderCatalogTest`（evals） | javadoc 写「17 built-in providers」（陈旧）、断言 `hasSize(3)`（套件用例数，不变） | **只改注释**（非计数断言） | `evals/ProviderCatalogTest.java:12` |
| `EnvApiKeyResolver` | 遍历 `ProviderCatalog.all()` | **自动**收下 `xai→XAI_API_KEY` | 无（零改） |
| `DefaultProviders.wrapRemoteCatalogs` | 按 `builtinIds` 远程包装 | **自动**包装 xai（pi 的 `withRemoteCatalog` 同样覆盖 xai） | 无（零改） |
| `AiCli` 列表 | 遍历 `ProviderCatalog.all()` | **自动**多一行 | 无（零改） |

> ⚠️ **实施实录**：§4 初稿只列了前三行 —— 第四行（`ProviderCatalogConformanceTest` 的
> `EXPECTED_NAMES` ＋ 方法名）是**跑测试才撞到**的（实施时的 grep 复核抓到）。
> 这正是本表那条「实施前须 grep 复核」预警的价值所在。
>
> ⚠️ **`-pl <module>` 不带 `-am` 会读到 `~/.m2` 里的旧构件**：改完 `pi-java-ai` 后只跑
> `mvn -pl pi-java-evals test`，evals 拿到的是**上一版** AI 构件 ⇒ 报 `expected 19 providers,
> got 18`（假红）。凡「跨模块改动」一律带 `-am`（本仓既有教训）。

---

## 5. 台账回填（闭环时）

1. ✅ `docs/41 §1.1` xai 行：标闭环（`docs/44`，2026-10-10）＋ **订正「14 模型」→「4 模型」**（§1.1）。
2. ✅ `docs/41` banner：w2 由 3 条降为 **2 条**（目录规模 / 中段工具内联）；§6 条目 ② 标闭环。
3. ✅ `docs/map/02-ai.md` 单元 **19**（`xai`）：`缺失` → **对齐（2026-10-10，docs/44）**；
   连带订正 7 处派生计数（端点 18→19、覆盖 17/43→18/43、未加权 82→85、加权 183.5→189.5、
   域表 Provider/横切两行、w2 簇 5→2 条、缺失清单 54→51）。⚠️ 顺带订正一处陈旧账：
   单元 70/71（`docs/43` 已闭环）**逐行标了「对齐」但合计没跟上** —— 本轮一并折入。
4. ✅ `docs/04-open-items-register.md`：新增 **B222**（§8-2 的 responses developer 角色缺口）。

---

## 6. 探针剧本（写码前先定红）

**RED（先红）**：写 `XaiModelsTest` 断言 `ProviderCatalog.all()` 含 `xai`、且目录 4 条
—— 在加 `XaiProvider` 之前**必须红**（provider 不存在）。

**变异探针（M1–M5）**，每条预测 1 红（除注明）：

| 编号 | 变异 | 预期红点 | 预测 |
|---|---|---|---|
| M1 | `xaiResponses` 里 `Boolean.FALSE` → `null` | 夹具 `compat.supportsLongCacheRetention()==false` | **1** |
| M2 | 目录条目 `api="openai-responses"` → `null` | 夹具 `model.api()=="openai-responses"` | **1** |
| M3 | `ProviderCatalog` 删 `new XaiProvider()` | registry 计数 ＋ evals 计数 ＋ xai 查表 | **≥2** |
| M4 | `config()` baseUrl → `https://api.openai.com/v1` | 夹具 `config().defaultBaseUrl()` | **1** |
| M5 | `grok-4.6`／`grok-4.7` 的 `TLM_NO_OFF_XHIGH` → `TLM_NO_OFF_NO_XHIGH` | 夹具 thinkingLevelMap 的 `xhigh` | **1** |

> ⚠️ **CRLF 纪律**（本仓第 5 次教训）：每次变异后**必须 grep 复核变异是否落地**，
> 否则读到的是**上一轮的 surefire 报告**（零红时尤其可疑）。
> ⚠️ **夹具要有牙**：M1 若只断言「目录里 compat 非空」则零红 —— 必须钉**字段值**。

**回归**：`mvn -pl pi-java-ai test`（含新夹具）＋ `pi-java-evals` ＋ 全 reactor。

---

## 7. 不做什么 / 裁决

| 项 | 裁决 | 依据 |
|---|---|---|
| xai **OAuth 流程** | **不做**（已在册） | `OAuthProviders.java:30` device code 早落 |
| xai **image / classifier 模型** | **不做**（pi 也无） | `xai.json` 无 image/classifier 组；`XAI_IMAGE_MODELS` 过滤后为空 |
| 补齐 `CompatResolver` 的 xai completions 分支 | **不改** | 那两条分支今天**不可达**（xai 只走 responses）；保留供 models.json 用户显式改道 |
| `inputLimits` 转录 | **不做**（归 `90`） | java `ModelInfo` 无该组件，跨 ai+agent-core |
| pi 的 `xai.models.ts` **生成脚本** | **不做** | 本仓目录一贯手写常量（既有结构差异，`docs/41 §1.1` 目录规模行） |

---

## 8. 残留 / 待登记

1. ~~**`OpenRouterModels` 未转录 `cost.tiers`**~~ —— **结案为「不是缺口」**：实测
   pi `providers/data/openrouter.json` 共 **472 条**、**带 `cost.tiers` 的 0 条**
   ⇒ openrouter 目录本就无分档计价，不转录是**正确**的。（本包对 xai 转录 tiers 因为
   xai 数据**有** tiers。）**无条目登记。**
2. **responses 车道恒发 `system`，pi 对推理模型发 `developer`** —— 已登记
   **`docs/04` B222**。pi `openai-responses-shared.ts:215` 判据
   `model.reasoning && compat?.supportsDeveloperRole !== false`；java 的
   `ModelCompat.supportsDeveloperRole` 有字段/生产者/completions 读点，**独缺 responses
   读点**（`ResponsesMessageConverter` 硬编 `Role.SYSTEM`）。⚠️ 影响面**不止 xai** ——
   openai 自己的 gpt-5 responses 模型同样中招（pi 发 `developer`、java 发 `system`）。
   xai 的 compat 不设该位 ⇒ **本包无差**，属独立既有缺口。

---

## 9. 复现方法（取证）

```bash
# pi 锚点（clone HEAD 恰为锚点；data/ 被 gitignore 但磁盘即锚点数据）
cd /d/workplaceForai/pi
git show 200387122:packages/ai/src/providers/xai.ts
git show 200387122:packages/ai/src/providers/xai.models.ts | wc -l     # 14 行（不是 14 模型）
cat packages/ai/src/providers/data/.manifest.json                       # generatedAt = 2026-10-04
node -e "const d=require('./packages/ai/src/providers/data/xai.json'); \
  console.log(Object.keys(d['openai-responses']).length)"               # 4
git show 200387122:packages/ai/scripts/generate-models.ts | sed -n '472,474p'   # XAI_RESPONSES_COMPAT
git show 200387122:packages/ai/src/providers/all.ts | grep -n xai      # :173

# pi-java 现状
grep -n "xai" pi-java-ai/src/main/java/com/pijava/ai/catalog/CompatResolver.java
grep -rn "ProviderCatalog.all()" pi-java-*/src/*/java --include=*.java
```
