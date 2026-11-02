# 12 - L1 AI 目录数据与运行时对齐设计（B265b）

> 【背景摘要】这是 L1（ai 模块）在固定锚点 `56cae7823` 下的第三个实施包设计稿。B264（流式工具参数）与 docs/11（跨模型重放、上下文归一、转录工具状态、ModelsStore 取消）已闭环；本稿接手 B265 的剩余部分：内置模型目录数据重生成（地图 #34）、图像模型目录（#33）、OpenRouter 运行时个性化（#16）、Radius 公共基线与 legacy 导入（#21）。
>
> **文档状态：用户已审核通过，阶段 A/B 已实施并闭环（2026-11-02）。闭环记录见 §10。**

## 0. 范围、锚点与基线

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 目标锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec` |
| pi 证据路径 | 固定提交的 `packages/ai/` 与 `packages/coding-agent/`；只用 `git show 56cae7823:<path>` |
| pi-java 当前 HEAD | `59249bbd`（本稿写作时） |
| 可执行基线 | docs/11 终态：`mvn test -pl pi-java-ai -am` 实测 telemetry 80 ＋ AI 1819 ＝ 1899 测试，0 失败 |
| 全仓基线 | `mvn clean verify` 5,856 测试，0 失败／0 错误；Checkstyle 0 违规；SpotBugs 0 |
| 新文档号 | 本稿占 `docs/12` |

### 0.2 拆分依据

【场景】B265 原本合并了 9 个计分单元。docs/11 已闭环其中四个纯行为单元；剩下的四个都围绕「模型目录从哪里来、运行时怎么按 Key 收窄」，并且需要提交外部数据快照。把数据生成与行为改动混在一包里，无法分别审核。

【决定】docs/12 接地图 #16、#21、#33、#34，并拆成两个可分别提交的实施阶段：

- **阶段 A（数据面）**：#34 目录重生成、#33 图像目录、#21 公共基线。
- **阶段 B（运行时面）**：#16 OpenRouter 个性化、#21 legacy `gatewayConfig` 导入、刷新上下文接线。

阶段 A 只改「目录里有什么」；阶段 B 才改「刷新时做什么」。两阶段各自 RED-first、各自验证，但共用本稿的设计与审核。

【风险/代价】[内部，模型不可见] 审核次数增加一次；换来的是数据快照可以先定格，运行时代码不需要为数据漂移反复改断言。

### 0.3 本包计分单元

| 地图行 | 能力 | pi 证据 | Java 证据 | 当前判定 | 权重 | 目标 |
|---|---|---|---|---:|---:|---|
| #34 | models.dev v2 内置模型目录数据 | `catalog/sources/models-dev.ts:6-8,182-242`；`catalog/generate.ts:98-170` | `AI/provider/BuiltinModelCatalogs.java:49-53,113-135`；`src/main/resources/catalog/.manifest.json` | 缺失 | 2 | 对齐 |
| #33 | 图像模型目录 | `src/types.ts:890-898`；`src/providers/images/register-builtins.ts:50-57`；`catalog/sources/openrouter.ts:54-131` | `AI/api/ImageApi.java:9-18`；`AI/catalog/BuiltinCatalog.java:34-44` | 缺失 | 1 | 对齐 |
| #16 | OpenRouter 按 Key 收窄与 `/models/user` | `src/providers/openrouter.ts:14-88`；`coding-agent/src/core/remote-catalog-provider.ts:195-240` | `AI/provider/builtin/OpenRouterProvider.java:33-52`（无刷新）；`AI/catalog/RefreshModelsContext.java:22-43` | 缺失 | 3 | 对齐 |
| #21 | Radius 公共基线与 legacy `gatewayConfig` 导入 | `src/providers/radius.ts:22-90`；`src/providers/radius-config.ts:62-99`；`catalog/providers/radius.ts:1-7` | `AI/provider/builtin/RadiusProvider.java:29-88` | 缺失 | 2 | 对齐 |

加权：2 ＋ 1 ＋ 3 ＋ 2 ＝ **+8**。L1 由 `187.0/240＝77.9%` 变为 `195.0/240＝81.25%`。未加权 131 个计分单位不变，由 `100.5/131＝76.7%` 变为 `104.5/131＝79.8%`。

> 行号口径：`AI/` 表示 `pi-java-ai/src/main/java/com/pijava/ai/`；其他模块写完整路径。Java 行号以 `59249bbd` 工作树为准。

### 0.4 不在本包

- 地图 #24/#28（`Model.input`／`Model.reasoning` 的能力投影窄差）：它们不是数据缺失；重新生成数据不会消除 Java `ModelInfo.capabilities` 与 pi 原始模态列表之间的投影差异。
- 分类器目录（#94／R5）：`gpt-6-luna` 决策模型、TypeSafe System One 不进入 Java 分母，生成的 shard 仍可携带，Java 装载器明确丢弃。
- 扩展 API 的 `ProviderImageModelConfig`（`coding-agent/src/core/extensions/types.ts:2052-2056`）：属 L5 扩展面，另行设计。本包只说明它是 #33 回到分母的理由，不在本包移植。
- pi.dev 远程目录新增的 `?types=chat,image,classifier` 查询参数：属 coding-agent 远程目录协议，另行复核；本包只复用现有 chat 目录。
- `B58`（OpenAI 默认 wire）仍等用户单独拍板。
- 不新增 Maven 依赖；Node 只作为**生成期工具**，不进入构建链。

### 0.5 术语

- **目录（catalog）**：模型 id、名称、端点、价格、上下文上限、compat、thinking 档位等元数据的集合。
- **分片（shard）**：一个 provider 一份 JSON，形如 `{"<api 组>": {"chat:<id>": {...}}}`。Java 从 `resources/catalog/<provider>.json` 装载。
- **生成器（generator）**：把上游目录数据按 pi 的规则折算成分片的程序。本仓采用「仓内 vendor 锚点生成器」：生成规则逐字复制 pi `56cae7823` 的 `catalog/` 规则文件，输入是提交进仓库的快照。
- **快照（snapshot）**：从上游 URL 一次性抓取并提交进仓库的原始数据。它固定「某一天上游长什么样」，使生成结果可复现，不随线上数据漂移。
- **manifest**：分片目录的信任锚，记录 `schemaVersion`、`generatedAt`、结构哈希和逐文件 sha256。
- **个性化（personalize）**：用调用方 API Key 请求 OpenRouter `/models/user`，把目录收窄到该 Key 可用的 chat 模型，并用该账号的实际限额/价格覆盖目录值。
- **legacy `gatewayConfig`**：Radius 旧实现把账号目录写进 OAuth 凭证的 `gatewayConfig` 字段；现行 pi 只读不写，用于一次性导入旧缓存。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本。目录数据本身不直接进入提示，但决定模型选择、价格展示和请求参数。
- **窄差（D 编号）**：已登记、无法或无需消除的行为/形状差异，明细在 `docs/map/`。

## 1. 数据流与实施阶段

### 1.1 目录生成链

```text
上游快照（提交进仓库）
  ├─ models.dev v2        （15 个 provider 键）
  ├─ OpenRouter /models   （默认列表 ＋ output_modalities=image）
  └─ Radius /v1/config    （公共基线）
        │
        ▼
仓内 vendor 生成器（pi 56cae7823 的 catalog 规则，逐字）
  ├─ buildCatalog：provider 定义 → 共享家族规则 → finalize
  ├─ 过滤：只留 Java 的 18 个 provider、只留 chat ＋ image
  └─ scripts/model-data.ts：分组 ＋ manifest ＋ 校验
        │
        ▼
pi-java-ai/src/main/resources/catalog/
  ├─ <provider>.json × 18
  ├─ .manifest.json
  └─ ATTRIBUTION.txt
```

### 1.2 运行时链

```text
ModelCatalogRefresh.refresh
  ├─ 离线相：恢复 ModelsStore 缓存（不联网）
  └─ 联网相：解析凭证 → provider.refreshModels
        ├─ RadiusProvider：stored → legacy 导入 → /v1/config → 发布
        ├─ PersonalizedCatalogProvider（openrouter）
        │     ├─ stored 恢复
        │     ├─ pi.dev 目录（有更新才用）
        │     ├─ OpenRouterPersonalization（/models/user）
        │     └─ 发布：持久化个性化后的列表
        └─ RemoteCatalogProvider（其余内置 provider，行为不变）
```

### 1.3 阶段边界

| 阶段 | 改动面 | 可独立验证 |
|---|---|---|
| A | 生成器、快照、18 个分片、Java 装载器、图像目录、Radius 基线 | 目录计数/字段/哈希；不联网 |
| B | `RefreshModelsContext`、`Provider.baseUrl`、OpenRouter 个性化、Radius legacy、coding-agent 包装器 | 本地 HTTP 夹具；刷新决策表 |

阶段 A 合入后，`openrouter-images` 与 `radius` 立即拥有真实目录；阶段 B 合入后才改变刷新行为。两阶段之间账面不翻转（见 §9）。

## 2. pi 逐字契约

### 2.1 生成器入口与产物

`packages/ai/catalog/README.md:1-47`（逐字节摘要）：

- `node catalog/generate.ts` 抓取上游并写 `src/providers/data/<provider>.json`（gitignored）、`src/providers/<provider>.models.ts` 与 `src/models.generated.ts`（提交）。
- `sources/`：上游 fetcher；models.dev v2 由 `models-dev.ts` 归一，OpenRouter、Vercel AI Gateway、NVIDIA NIM、Radius 各有自己的模块。
- `providers/<id>.ts`：每个内置 provider 的来源、API、baseUrl、compat 默认值、修正项、上游未列出的手写模型。
- `families/`：跨宿主成立的模型家族事实（Claude effort、GPT-6 thinking 等）。
- `build.ts`：按固定顺序运行 provider 定义。

`packages/ai/catalog/generate.ts:98-170` 的关键顺序：

```ts
async function generateModels(generatorOptions: GeneratorOptions): Promise<void> {
	const sources = await loadCatalogSources(generatorOptions.strict);
	const catalogs = buildCatalog(PROVIDERS, sources, generatorOptions.strict);
	// Providers without any models are left out, as if they did not exist.
	const providers: Record<string, ProviderCatalog> = Object.fromEntries(
		Object.entries(catalogs).filter(
			([, catalog]) => catalog.chat.length + catalog.image.length + catalog.classifier.length > 0,
		),
	);

	const sortedProviderIds = Object.keys(providers).sort();
	const jsonChatProviders: Record<string, Record<string, Model<Api> & { type: "chat" }>> = {};
	const jsonImageProviders: Record<string, Record<string, ImageModel<ImageApi>>> = {};
	const jsonClassifierProviders: Record<string, Record<string, ClassifierModel<ClassifierApi>>> = {};
	const jsonAllProviders: Record<string, CatalogEntry[]> = {};
	for (const providerId of sortedProviderIds) {
		jsonChatProviders[providerId] = Object.fromEntries(sortedById(providers[providerId].chat));
		jsonImageProviders[providerId] = Object.fromEntries(sortedById(providers[providerId].image));
		jsonClassifierProviders[providerId] = Object.fromEntries(sortedById(providers[providerId].classifier));
		jsonAllProviders[providerId] = [
			...Object.values(jsonChatProviders[providerId]),
			...Object.values(jsonImageProviders[providerId]),
			...Object.values(jsonClassifierProviders[providerId]),
		];
	}
	...
	const { groups, structure } = groupProviderModelData(providerId, jsonAllProviders[providerId]);
	...
	const generatedAt = new Date().toISOString();
	...
	writeJson(join(stagedDataDir, MODEL_DATA_MANIFEST_FILE),
		createModelDataManifest(modelDataStructure, fileContents, generatedAt));
	validateModelDataDirectory(modelDataStructure, stagedDataDir);
```

关键点：

1. **先构建、再过滤空 provider、再按 api 分组**。分组键是 `<type>:<id>`，组名是 api。
2. **manifest 在写盘前生成**，写完立即校验；失败回滚旧数据。
3. `generatedAt` 是生成时刻，不是上游数据的抓取时刻。

### 2.2 models.dev v2 归一

`packages/ai/catalog/sources/models-dev.ts:6-8`：

```ts
export const MODELS_DEV_URL = "https://models.dev/experimental/v2.0/api.json";
// v2 has no provider-agnostic decision metadata endpoint; v1 still serves it.
export const MODELS_DEV_DECISIONS_URL = "https://models.dev/models.json?type=decision";
```

`models-dev.ts:110-151` 的价格归一：

```ts
function normalizeCost(cost: ModelsDevV2Model["cost"]): ModelsDevCost {
	const input = cost?.text?.input;
	const output = cost?.text?.output;
	const perToken = (unit: string | undefined) => unit === undefined || unit === "token";
	if (!input || !output || !perToken(input.unit) || !perToken(output.unit)) return {};

	// Only context-size overrides map onto pi's cost tiers. Region, service-tier,
	// and time-based prices have no pi equivalent yet.
	const tiers = new Map<number, NonNullable<ModelsDevCost["tiers"]>[number]>();
	const tier = (above: number) => { ... };
	for (const override of input.overrides ?? []) {
		const above = contextThreshold(override.when);
		if (above === undefined) continue;
		const entry = tier(above);
		if (override.price !== undefined) entry.input = override.price;
		if (override.cache?.read !== undefined) entry.cacheRead = override.cache.read;
		const cacheWrite = cacheWritePrice(override.cache);
		if (cacheWrite !== undefined) entry.cacheWrite = cacheWrite;
	}
	for (const override of output.overrides ?? []) { ... }

	return {
		input: input.price,
		output: output.price,
		cacheRead: input.cache?.read,
		cacheWrite: cacheWritePrice(input.cache),
		...(tiers.size > 0 ? { tiers: [...tiers.values()] } : {}),
	};
}
```

`models-dev.ts:182-242` 的模型归一与 chat 过滤：

```ts
export function normalizeModelsDevModel(model: ModelsDevV2Model): ModelsDevModel {
	const input = model.modalities?.input ?? [];
	const reasoning = model.capabilities?.reasoning;
	return {
		id: model.id,
		name: model.name,
		type: model.type,
		status: model.status,
		toolCall: model.capabilities?.tools?.supported === true,
		structuredOutput: model.capabilities?.structured_output === true,
		reasoning: reasoning?.supported === true,
		reasoningOptions: reasoning?.supported ? reasoningOptions(reasoning) : undefined,
		imageInput: input.some((modality) => modality === "image" || modality.startsWith("image/")),
		textInput: input.includes("text"),
		textOutput: (model.modalities?.output ?? []).includes("text"),
		contextWindow: model.limit?.context,
		maxTokens: model.limit?.output,
		cost: normalizeCost(model.cost),
		apis: Object.entries(model.api ?? {}).map(([protocol, entry]) => ({ ... })),
		source: model,
	};
}

export function modelsDevChatModels(catalog: ModelsDevV2Catalog, provider: string): ModelsDevModel[] {
	return Object.values(catalog[provider]?.models ?? {})
		.filter(({ type }) => type === "chat")
		.map(normalizeModelsDevModel);
}
```

关键点：

- v2 schema 是 experimental，所有读取都经过 `normalizeModelsDevModel()`，缺字段降级而不是抛错。
- 只有 `type === "chat"` 的条目进入 chat 目录；image/classifier 走各自的列表。
- 价格缺字段保持 `undefined`，最终 `modelsDevCost()` 统一把缺失费率折算成 `0`。

### 2.3 共享模型规则（Java 需要的子集）

`packages/ai/catalog/build.ts:84-160` 的构建顺序：

```ts
for (const model of chat) {
	applyCompatDefaults(model, definition.compat);
	applyDeepSeekCompatRules(model);
	applyModelsDevEffort(model, ctx.modelsDevSource(model));
	applyOpenAiFamilyRules(model);
	applyClaudeFamilyRules(model);
	applyDeepSeekFamilyRules(model);
}
for (const model of chat) definition.finalize?.(model, ctx);
for (const model of chat) {
	if (model.compat && Object.keys(model.compat).length === 0) delete model.compat;
	applyImageResizeDefaults(model);
}
definition.finalizeAll?.(chat, ctx);

const image = definition.images?.(ctx) ?? [];
for (const model of image) applyImageResizeDefaults(model);
```

`build.ts:30-38,74-81` 的图片输入默认值：

```ts
const DEFAULT_IMAGE_RESIZE = {
	maxWidth: 2000,
	maxHeight: 2000,
	maxBytes: 4.5 * 1024 * 1024,
	jpegQuality: 80,
} as const;

function applyImageResizeDefaults(model: AnyModel): void {
	if (!model.input.includes("image")) return;
	const images = model.inputLimits?.images;
	model.inputLimits = {
		...model.inputLimits,
		images: { ...images, resize: { ...DEFAULT_IMAGE_RESIZE, ...images?.resize } },
	};
}
```

`catalog/define.ts:111-157` 是 models.dev → pi 模型的映射器，所有 provider 共用：

```ts
export function modelsDevModels<TApi extends Api>(
	ctx: ProviderContext,
	sourceProvider: string,
	options: ModelsDevOptions<TApi>,
): ChatModel[] {
	const models: ChatModel[] = [];
	for (const source of modelsDevChatModels(ctx.sources.modelsDev, sourceProvider)) {
		if (!source.toolCall) continue;
		if (options.protocol && !source.apis.some((api) => api.protocol === options.protocol)) continue;
		if (options.skipDeprecated && source.status === "deprecated") continue;
		if (options.include && !options.include(source)) continue;
		const base: Model<TApi> = {
			id: source.id,
			name: source.name || source.id,
			api: options.api,
			provider: ctx.providerId,
			baseUrl: options.baseUrl,
			reasoning: source.reasoning,
			input: source.imageInput ? ["text", "image"] : ["text"],
			cost: modelsDevCost(source.cost),
			contextWindow: source.contextWindow || options.defaultContextWindow || 4096,
			maxTokens: source.maxTokens || options.defaultMaxTokens || 4096,
			...(options.headers ? { headers: { ...options.headers } } : {}),
			...(options.compat ? { compat: { ...options.compat } } : {}),
		};
		...
	}
	return models;
}
```

Java 的 18 个 provider 只用到其中 15 个 models.dev 键（见 §4.3），因此 vendor 的规则文件虽包含全部 provider 定义，生成器只实例化 Java 子集。

### 2.4 OpenRouter 目录源

`packages/ai/catalog/sources/openrouter.ts:5,54-130`：

```ts
const OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

export function buildOpenRouterCatalog(
	listed: readonly OpenRouterModelListItem[],
	imageListed: readonly OpenRouterModelListItem[],
	decisionListed: readonly OpenRouterModelListItem[],
): OpenRouterCatalog {
	const chat: OpenRouterCatalog["chat"] = [];
	for (const model of listed) {
		if (!model.supported_parameters?.includes("tools")) continue;
		const input: ("text" | "image")[] = ["text"];
		if (model.architecture?.modality?.includes("image")) input.push("image");
		const thinkingLevelMap = getOpenRouterThinkingLevelMap(model.reasoning);
		const useAnthropicMessages = /^~?anthropic\//.test(model.id) && !model.id.endsWith(":batch");
		chat.push({
			type: "chat",
			id: model.id,
			name: model.name,
			api: useAnthropicMessages ? "anthropic-messages" : "openai-completions",
			baseUrl: useAnthropicMessages ? "https://openrouter.ai/api" : "https://openrouter.ai/api/v1",
			provider: "openrouter",
			reasoning: model.supported_parameters?.includes("reasoning") || false,
			...(thinkingLevelMap && { thinkingLevelMap }),
			input,
			cost: openRouterCost(model.pricing),
			contextWindow: model.top_provider?.context_length || model.context_length || 4096,
			maxTokens: model.top_provider?.max_completion_tokens || 4096,
		});
	}

	const images: OpenRouterCatalog["images"] = [];
	for (const model of imageListed) {
		if (images.some((entry) => entry.id === model.id)) continue;
		const output = modalities(model.architecture?.output_modalities);
		if (!output.includes("image")) continue;
		const input = modalities(model.architecture?.input_modalities);
		images.push({
			type: "image",
			id: model.id,
			name: model.name,
			api: "openrouter-images",
			provider: "openrouter",
			baseUrl: "https://openrouter.ai/api/v1",
			input: input.length > 0 ? input : ["text"],
			output,
			cost: openRouterCost(model.pricing),
		});
	}
	...
	return { chat, images, classifiers };
}
```

关键点：

- chat 只收 `supported_parameters` 含 `tools` 的条目；`~anthropic/*` 与 `anthropic/*`（非 `:batch`）走 `anthropic-messages` 车道，其余走 `openai-completions`。
- 图片列表来自 `?output_modalities=image`，只收输出含 `image` 的条目；输出模态可为 `["image"]` 或 `["image","text"]`。
- 图片条目的 `provider` 仍是 `"openrouter"`，与 chat 同属一个 pi provider。

### 2.5 运行时 OpenRouter 个性化

`packages/ai/src/providers/openrouter.ts:14-88`：

```ts
const OPENROUTER_USER_MODELS_TIMEOUT_MS = 15_000;

export async function personalizeOpenRouterCatalog(
	catalog: readonly AnyModel[],
	context: RefreshModelsContext,
): Promise<readonly AnyModel[]> {
	const key = context.credential?.type === "oauth" ? context.credential.access : context.credential?.key;
	if (!key) return catalog;
	const response = await fetch(`${(context.baseUrl ?? OPENROUTER_BASE_URL).replace(/\/+$/, "")}/models/user`, {
		headers: { accept: "application/json", authorization: `Bearer ${key}` },
		signal: AbortSignal.any([context.signal, AbortSignal.timeout(OPENROUTER_USER_MODELS_TIMEOUT_MS)]),
	});
	if (response.status === 401 || response.status === 403 || response.status === 404) return catalog;
	if (!response.ok) throw new Error(`OpenRouter model list request failed: ${response.status}`);
	const body: unknown = await response.json();
	if (typeof body !== "object" || body === null || !("data" in body) || !Array.isArray(body.data)) {
		throw new Error("Invalid OpenRouter model list response");
	}
	const allowed = new Map<string, OpenRouterModel>();
	for (const entry of body.data as unknown[]) {
		if (typeof entry === "object" && entry !== null && "id" in entry && typeof entry.id === "string") {
			allowed.set(entry.id, entry as OpenRouterModel);
		}
	}
	return catalog.flatMap((model): AnyModel[] => {
		if (!isModelType(model, "chat")) return [model];
		const entry = allowed.get(model.id);
		if (!entry) return [];
		return [
			{
				...model,
				contextWindow: entry.top_provider?.context_length || entry.context_length || model.contextWindow,
				maxTokens: entry.top_provider?.max_completion_tokens || model.maxTokens,
				cost: entry.pricing ? openRouterCost(entry.pricing) : model.cost,
			},
		];
	});
}
```

关键点：

1. **没有 Key 就不请求**，直接返回原目录（同一个引用）。
2. **401/403/404 返回原目录**：代理/中转站可能没有 `/models/user`，此时保留完整列表而不是刷新失败。
3. **其他非 2xx 抛错**；响应体不是 `{data: []}` 也抛错。
4. **只过滤 chat**；图片与分类器原样保留（Java 的图片是独立 provider，见 §4.5）。
5. 条目给出的限额为 `0`/缺席时保留目录值；价格缺席时保留目录值。

`openRouterCost()`（`src/providers/openrouter-api.ts:40-73`）的要点：

```ts
function perMillion(value: string | undefined, fallback: number): number {
	const perToken = Number.parseFloat(value ?? "");
	return perToken >= 0 ? Number((perToken * 1_000_000).toFixed(6)) : fallback;
}

export function openRouterCost(pricing: OpenRouterPricing | undefined): ModelCost {
	const base = {
		input: perMillion(pricing?.prompt, 0),
		output: perMillion(pricing?.completion, 0),
		cacheRead: perMillion(pricing?.input_cache_read, 0),
		cacheWrite: perMillion(pricing?.input_cache_write, 0),
	};
	const tiers = (pricing?.overrides ?? []).flatMap((override): ModelCostTier[] => {
		if (override.min_prompt_tokens === undefined
			|| override.utc_start !== undefined || override.utc_end !== undefined
			|| override.utc_days !== undefined) return [];
		return [{
			inputTokensAbove: override.min_prompt_tokens,
			input: perMillion(override.prompt, base.input),
			output: perMillion(override.completion, base.output),
			cacheRead: perMillion(override.input_cache_read, base.cacheRead),
			cacheWrite: perMillion(override.input_cache_write, base.cacheWrite),
		}];
	});
	return tiers.length > 0 ? { ...base, tiers } : base;
}
```

负数（路由器模型的 `-1`）、非数字、缺席都按 fallback；基础费率 fallback 是 `0`；覆盖档的 fallback 是基础费率。

### 2.6 `withPersonalizedCatalog` 包装器

`packages/coding-agent/src/core/remote-catalog-provider.ts:195-240`：

```ts
export function withPersonalizedCatalog(
	provider: Provider,
	personalize: PersonalizeCatalog,
	catalogBaseUrl: string = DEFAULT_CATALOG_BASE_URL,
	localGeneratedAt?: number,
): Provider {
	let personalModels: readonly AnyModel[] = [];
	const builtinModels = (): readonly AnyModel[] => provider.getAllModels?.() ?? provider.getModels();
	const getAllModels = (): readonly AnyModel[] => (personalModels.length > 0 ? personalModels : builtinModels());

	return {
		...provider,
		getModels: () => getAllModels().filter((model) => isModelType(model, "chat")),
		getAllModels,
		refreshModels: async (context) => {
			const restored = (context.stored?.models ?? []).filter((model) => model.provider === provider.id);
			if (!(await context.publish({ update: () => { personalModels = restored; } }))) return;
			if (!context.allowNetwork || context.signal.aborted) return;

			const response = await requestCatalog(provider.id, catalogBaseUrl, context.signal);
			if (context.signal.aborted) return;
			let remote: readonly AnyModel[] = [];
			if (response.status !== 404 && response.status !== 501) {
				if (!response.ok) throw new Error(`Model catalog request failed for ${provider.id}: ${response.status}`);
				const catalog = await readCatalog(provider.id, response);
				if (isNewer(catalog.lastModified, localGeneratedAt)) remote = catalog.models;
			}
			const personalized = await personalize(mergeModels(builtinModels(), remote), context);
			if (context.signal.aborted) return;
			await context.publish({
				persist: { models: personalized, checkedAt: Date.now() },
				update: () => { personalModels = personalized; },
			});
		},
	};
}
```

关键点：

1. **离线恢复优先**：先发布 stored 里的个性化列表；没有网络就直接结束。
2. **不做 TTL/ETag 短路**：每次联网刷新都请求 pi.dev 目录；仅当 `Last-Modified` 晚于内置数据生成时刻才采用远端条目。
3. **个性化结果整体替换**：持久化的条目是「按 Key 收窄后的列表」，不是 pi.dev 原始目录。
4. **空列表回退内置**：`personalModels.length > 0` 才用它，否则用内置目录。
5. `personalize` 被调用时，`context` 里带凭据、provider baseUrl 和取消信号。

`packages/coding-agent/src/core/model-runtime.ts:229-240` 的装配：

```ts
const providers = builtinProviderCatalog.builtinProviders().map((provider) => {
	if (provider.id === "radius") return provider;
	// OpenRouter narrows the catalog to the models, limits and prices of the user's key.
	if (provider.id === "openrouter") {
		return withPersonalizedCatalog(
			provider,
			personalizeOpenRouterCatalog,
			options.catalogBaseUrl,
			builtinModelDataGeneratedAt,
		);
	}
	return withRemoteCatalog(provider, options.catalogBaseUrl, builtinModelDataGeneratedAt);
});
```

### 2.7 Radius 公共基线与 legacy 导入

`packages/ai/src/providers/radius.ts:22-90`：

```ts
export function radiusProvider(options: RadiusProviderOptions = {}): Provider<"pi-messages"> {
	const id = options.id ?? "radius";
	const name = options.name ?? "Radius";
	const gateway = normalizeRadiusGatewayUrl(options.gateway ?? DEFAULT_RADIUS_GATEWAY);
	const baselineModels: Model<"pi-messages">[] =
		gateway === normalizeRadiusGatewayUrl(DEFAULT_RADIUS_GATEWAY)
			? Object.values(RADIUS_MODELS).map((model) => ({ ...model, provider: id }))
			: [];
	// Gateway catalog for this account. Radius org owners can disable models, so once known it replaces
	// the shipped baseline instead of overlaying it. The baseline only covers the time before any catalog exists.
	let dynamicModels: Model<"pi-messages">[] | undefined;

	return {
		...
		getModels: () => dynamicModels ?? baselineModels,
		refreshModels: async (context) => {
			const stored = context.stored;
			if (stored) {
				const restored = stored.models.filter((model) => model.provider === id) as Model<"pi-messages">[];
				if (!(await context.publish({ update: () => { dynamicModels = restored; } }))) return;
			}

			// Import catalogs cached by the pre-ModelsStore Radius implementation.
			if (!stored && context.credential?.type === "oauth") {
				const legacy = getRadiusModels(id, context.credential);
				if (legacy.length > 0) {
					if (!(await context.publish({
						persist: { models: legacy, checkedAt: Date.now() },
						update: () => { dynamicModels = legacy; },
					}))) return;
				}
			}

			if (!context.allowNetwork || context.signal.aborted) return;
			const apiKey = context.credential?.type === "oauth" ? context.credential.access : context.credential?.key;
			const config = await loadRadiusGatewayConfig(gateway, apiKey, context.signal);
			if (context.signal.aborted) return;
			const refreshed = getRadiusModelsFromConfig(id, config);
			await context.publish({
				persist: { models: refreshed, checkedAt: Date.now() },
				update: () => { dynamicModels = refreshed; },
			});
		},
		...
	};
}
```

`packages/ai/src/providers/radius-config.ts:62-99`：

```ts
export function getRadiusModelsFromConfig(providerId: string, config: RadiusGatewayConfig): Model<"pi-messages">[] {
	return config.models.map((model) => ({
		...model,
		api: "pi-messages",
		provider: providerId,
		baseUrl: config.baseUrl,
	}));
}

export function getRadiusModels(providerId: string, credential: OAuthCredential | undefined): Model<"pi-messages">[] {
	const config = getRadiusCredentialConfig(credential);
	return config ? getRadiusModelsFromConfig(providerId, config) : [];
}

async function loadRadiusGatewayConfig(...): Promise<RadiusGatewayConfig> {
	const headers: Record<string, string> = { accept: "application/json" };
	if (apiKey) headers.authorization = `Bearer ${apiKey}`;
	const response = await fetch(new URL("/v1/config", gateway), { headers, signal });
	if (!response.ok) throw new Error(
		`Could not load Radius config from ${gateway}: ${response.status}: ${truncateHttpBody(await response.text())}`);
	const config = sanitizeRadiusGatewayConfig(await response.json());
	if (!config) throw new Error(`Invalid Radius config from ${gateway}`);
	return config;
}
```

`packages/ai/catalog/providers/radius.ts:1-7` 的公共基线来源：

```ts
import { defineProvider } from "../define.ts";

// The unauthenticated public catalog; authenticated clients overlay it at runtime.
export default defineProvider({
	id: "radius",
	models: (ctx) => structuredClone(ctx.sources.radius),
});
```

关键点：

1. 只有默认网关 `https://radius.pi.dev` 使用随包携带的公共基线；自定义网关的基线为空。
2. 账号目录一旦存在就**整体替换**基线，不做并集；被组织禁用的模型不会从基线复活。
3. 没有 stored 且凭据是 OAuth 时，先尝试从旧 `gatewayConfig` 导入；导入成功就持久化。
4. 网络相即使没有任何模型也发布空列表（组织禁用全部模型时不能回退基线）。

### 2.8 `RefreshModelsContext` 与 `Provider.baseUrl`

`packages/ai/src/models.ts:76-109`：

```ts
export interface ModelsPublication {
	persist?: ModelsStoreEntry | null;
	update?: () => void;
}

export interface RefreshModelsContext {
	credential?: Credential;
	stored?: Readonly<ModelsStoreEntry>;
	publish(publication: ModelsPublication): Promise<boolean>;
	allowNetwork: boolean;
	force?: boolean;
	signal: AbortSignal;
	baseUrl?: string;
}
```

`models.ts:565-620` 的刷新编排：先读 stored 凭证，跑离线相（`allowNetwork=false`）；再解析可刷新凭证，跑联网相。每个 provider 有独立世代控制器；发布时比对世代和信号。

`models.ts:1036-1049` 的 `createProvider.fetchModels`：把 fetch 结果做成动态 overlay，恢复/发布都是事务化的。

Java 的 `Provider` 目前没有 `baseUrl()`，`RefreshModelsContext` 也没有 `credential`/`baseUrl`/`signal`（见 §3.5）。

### 2.9 图片模型类型

`packages/ai/src/types.ts:840-898`：

```ts
export interface BaseModel<TApi extends string> {
	id: string;
	name: string;
	api: TApi;
	provider: ProviderId;
	baseUrl: string;
	input: ModelInputModality[];
	inputLimits?: ModelInputLimits;
	cost: ModelCost;
	headers?: Record<string, string>;
}

export interface ImageModel<TApi extends ImageApi> extends BaseModel<TApi> {
	type: "image";
	/** Output modalities. Always includes "image"; "text" means the model can also return text blocks. */
	output: ("text" | "image")[];
}
```

图片模型没有 `reasoning`、`contextWindow`、`maxTokens`，价格沿用 `ModelCost`。`applyImageResizeDefaults` 会为输入含 image 的条目补上 2000×2000／4.5MiB／JPEG 80 的默认缩放参数。

## 3. Java 现状与已证实缺口

### 3.1 #34：内置目录仍是旧 npm 数据

`AI/provider/BuiltinModelCatalogs.java:49-53`：

```java
static final List<String> SHARD_IDS = List.of(
    "anthropic", "openai", "google", "deepseek", "mistral",
    "moonshotai", "moonshotai-cn", "zai", "zai-coding-cn",
    "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
    "minimax", "minimax-cn", "ant-ling", "openrouter", "xai");
```

`resources/catalog/ATTRIBUTION.txt` 声明这些 JSON 逐字节来自 npm `@earendil-works/pi-ai@1.1.0`，manifest `generatedAt=2026-10-07T22:01:28.515Z`。装载器只收 chat：

`BuiltinModelCatalogs.java:113-135`：

```java
static List<ModelInfo> loadChatModels(String providerId, JsonNode root) {
    ...
    var entries = group.getValue().fields();
    while (entries.hasNext()) {
        var entry = entries.next();
        if ("chat".equals(entry.getValue().path("type").asText(""))) {
            models.add(chatModel(providerId, groupKey, entry.getKey(), entry.getValue()));
        }
    }
    ...
}
```

已证实的漂移（2026-11-02 对上游探测，非锚点源码事实）：

| 项 | 旧分片 | 上游快照 | 后果 |
|---|---|---|---|
| OpenAI | 无 `gpt-6.1-terra` | 存在（`type:"chat"`） | 用户看不到新模型 |
| MiniMax | 无 `MiniMax-M3.1-Flash` | 存在（`type:"chat"`） | 同上 |
| models.dev v2 | 未使用 | 15 个 Java 相关 provider 共约 300 条 chat 原始条目 | 价格/限额/模态漂移 |
| OpenRouter 图片 | 8 条手写 | 64 条上游 | 缺 56 条 |
| Radius 基线 | 0 条 | 28 条公共模型 | 首次登录前无模型可选 |

旧 manifest 还记录了 41 个上游文件，但只携带 17 个；新生成器的 manifest 只记录实际携带的 18 个分片。

### 3.2 #33：图片目录是手写常量

`AI/catalog/BuiltinCatalog.java:34-44`：

```java
public static ModelCatalog openRouterImageModels() {
    return new BuiltinCatalog(List.of(
        imageModel("black-forest-labs/flux.2-flex", "Black Forest Labs: FLUX.2 Flex"),
        imageModel("black-forest-labs/flux.2-klein-4b", "Black Forest Labs: FLUX.2 Klein 4B"),
        imageModel("black-forest-labs/flux.2-max", "Black Forest Labs: FLUX.2 Max"),
        imageModel("black-forest-labs/flux.2-pro", "Black Forest Labs: FLUX.2 Pro"),
        imageModel("bytedance-seed/seedream-4.5", "Bytedance Seed: Seedream 4.5"),
        imageModel("google/gemini-2.5-flash-image", "Google: Gemini 2.5 Flash Image"),
        imageModel("google/gemini-3-pro-image", "Google: Gemini 3 Pro Image"),
        imageModel("google/gemini-3-pro-image-preview", "Google: Gemini 3 Pro Image Preview")
    ));
}
```

`imageModel()` 固定 `maxInputTokens=0`、`maxOutputTokens=0`、`PricingInfo.UNKNOWN`。上游图片条目带真实价格与输入限制，这里全部丢弃。

`AI/provider/builtin/OpenRouterImagesProvider.java:24-30` 直接用这份常量：

```java
protected ProviderConfig config() {
    return new ProviderConfig(
        "openrouter-images", "OpenRouter Images",
        "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
        Protocol.OPENAI_COMPLETIONS, Set.of(),
        BuiltinCatalog.openRouterImageModels());
}
```

### 3.3 #16：OpenRouter 没有运行时收窄

`AI/provider/builtin/OpenRouterProvider.java:39-52` 只有静态配置，没有覆写 `refreshModels`：

```java
protected ProviderConfig config() {
    return new ProviderConfig(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
        "OPENROUTER_API_KEY", Protocol.OPENAI_COMPLETIONS,
        Set.of(Protocol.OPENAI_COMPLETIONS, Protocol.ANTHROPIC_MESSAGES),
        BuiltinModelCatalogs.catalog("openrouter"));
}
```

`DefaultProviders.wrapRemoteCatalogs` 会给它套上通用 `RemoteCatalogProvider`（pi.dev 目录），但不会按 Key 收窄，也不会请求 `/models/user`。用户会看到所有模型，且价格/限额是公共目录值而不是账号实际值。

### 3.4 #21：Radius 没有基线，也不导入 legacy

`AI/provider/builtin/RadiusProvider.java:29-31`：

```java
private final ProviderConfig config = ProviderConfig.single(
    "radius", "Radius", DEFAULT_GATEWAY, "RADIUS_API_KEY", Protocol.PI_MESSAGES, ModelCatalog.empty());
```

`RadiusProvider.java:59-63`：`dynamicModels == null` 时返回空目录。

`RadiusProvider.java:65-88` 的刷新只有「stored 恢复 → 网络拉取」两条，缺少：

- 默认网关的公共基线；
- 无 stored 时的 legacy `gatewayConfig` 导入；
- 自定义网关不套公共基线的判断。

### 3.5 刷新上下文缺三个字段

`AI/catalog/RefreshModelsContext.java:22-43`：

```java
public record RefreshModelsContext(
    Optional<ModelsStoreEntry> stored,
    CatalogPublisherPort publish,
    boolean allowNetwork,
    boolean force
) {
    ...
    public static RefreshModelsContext offline(Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish) {
        return new RefreshModelsContext(stored, publish, false, false);
    }

    public static RefreshModelsContext online(Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish, boolean force) {
        return new RefreshModelsContext(stored, publish, true, force);
    }
}
```

注释明确写着「`credential` 不移植」「`signal` 不移植」。docs/11 已把「signal 接进刷新协调器」挂到 L5；但 #16 的 `/models/user` 需要凭据和 baseUrl，否则无法对齐。本稿按 pi 形状补字段，见 §4.9。

### 3.6 固定在旧行为上的测试

| 测试 | 钉住的旧行为 | 处置 |
|---|---|---|
| `BuiltinModelCatalogsTest.chatCountsPerShard` | 17 个分片的旧计数（合计 588） | 按提交快照重钉，并加入 radius |
| `BuiltinModelCatalogsTest.aggregateCatalogAddsImagesAndOllama` | 598 条（588＋8＋2） | 改为「chat＋图像＋radius＋ollama」 |
| `BuiltinModelCatalogsTest.openRouterKeepsTwoLanesBatchIdsAndTieredPricing` | 16／385 车道计数、旧模型价格 | 按新快照重钉 |
| `BuiltinModelCatalogsTest.imageClassifierAndEmbeddingEntriesAreNotCarried` | 图片条目不携带 | 改为「chat 装载器不携带；图像装载器单独携带」 |
| `BuiltinModelCatalogsTest.everyEntryHoldsAKnownApiAndItsFourRates` | API 闭集只有五条 chat 车道 | 增加 `pi-messages`（radius 分片） |
| `BuiltinModelManifestTest` | 41 文件 manifest、17 分片 | 改为 18 分片、manifest 只列携带文件 |
| `BuiltinModelManifestTest.nonCarriedShardsAreAbsentFromTheClasspath` | `radius.json` 必须缺席 | 删除 radius；保留其他 R2 缺席断言 |
| `ProviderCatalogConformanceTest.everyProviderHasSelfConsistentConfig` | radius 允许空目录 | radius 默认网关必须有基线 |
| `DefaultModelIdsTest` / `ModelResolverTest` | 默认模型 id 必须在分片中存在 | 按新快照逐条复核；数据删除了默认模型时先改 `DefaultModelIds` 再跑 |
| `ModelDataGeneratedAtTest` | 旧时间戳 | 继续断言「存在且不在未来」 |
| `DefaultProvidersRemoteCatalogTest` | openrouter 是 `RemoteCatalogProvider` | 改为 `PersonalizedCatalogProvider` |
| `OpenRouterProviderTest.imagesProviderRemainsSeparate` | 图片 provider 是独立对象 | 保留，但目录数据来自分片 |

## 4. 拟改设计

### 4.1 仓内 vendor 布局

新增目录 `pi-java-ai/catalog-generator/`（Maven 不编译、不打包）：

```text
pi-java-ai/catalog-generator/
├─ README.md                     # 生成/校验命令、快照更新流程
├─ VENDOR.json                   # 每个 vendor 文件的 pi 路径 + sha256 + 锚点
├─ ATTRIBUTION.txt               # pi（MIT）、models.dev（MIT）、OpenRouter/Radius 数据来源
├─ generate-java-catalog.ts      # 本仓编剧：读快照、调用 vendor 规则、写资源
├─ vendor/                       # 逐字节来自 pi 56cae7823（只读）
│  ├─ catalog/build.ts
│  ├─ catalog/define.ts
│  ├─ catalog/reasoning.ts
│  ├─ catalog/families/{anthropic,deepseek,google,kimi,openai}.ts
│  ├─ catalog/providers/{anthropic,openai,google,deepseek,mistral,
│  │                     moonshotai,moonshotai-cn,zai,zai-coding-cn,
│  │                     qwen-token-plan,qwen-token-plan-cn,xiaomi,
│  │                     xiaomi-token-plan-cn,minimax,minimax-cn,
│  │                     ant-ling,openrouter,xai,radius}.ts
│  ├─ catalog/sources/{models-dev,openrouter,vercel-ai-gateway,index}.ts
│  ├─ scripts/model-data.ts
│  ├─ src/types.ts
│  ├─ src/utils/text.ts
│  └─ src/providers/{openrouter-api,radius-config}.ts
└─ snapshots/
   ├─ SOURCES.json               # URL、抓取时刻、上游 sha256/字节数、存储 sha256
   ├─ models-dev-v2.json         # 只含 15 个 provider 键
   ├─ openrouter-models.json     # 默认列表
   ├─ openrouter-images.json     # output_modalities=image
   └─ radius-config.json         # 默认网关的公共基线
```

**为什么 vendor 规则而不是用 Java 重写**：

- pi 的目录规则约 1,500 行，含 Claude/GPT/DeepSeek/Google/Kimi 五个家族的事实。Java 重写会立刻产生第二套真相，数据每刷新一次就要重新对齐一遍。
- vendor 文件的 sha256 固定在 `VENDOR.json`，用户可逐字比对锚点源码。
- Node 只在「刷新目录数据」时运行，Maven 构建与运行时不依赖 Node。

**vendor 文件的文件长度豁免**：`src/types.ts` 等上游文件可能超过 500 行。它们是第三方逐字副本，不参与项目代码风格闸门；不拆分、不改写，只在 `VENDOR.json` 记录哈希。

### 4.2 快照格式与元数据

`snapshots/SOURCES.json` 的形态：

```json
{
  "anchor": "56cae782341c339d3bb820081f0f9425d8c5b1ec",
  "generatedAt": "2026-11-02T05:24:00.000Z",
  "inputs": {
    "modelsDev": {
      "url": "https://models.dev/experimental/v2.0/api.json",
      "fetchedAt": "2026-11-02T05:20:00.000Z",
      "upstreamBytes": 10414748,
      "upstreamSha256": "<原响应字节的 sha256>",
      "providerKeys": ["anthropic", "openai", "..."],
      "stored": "models-dev-v2.json",
      "storedSha256": "<过滤后文件字节的 sha256>"
    },
    "openrouter": {
      "url": "https://openrouter.ai/api/v1/models",
      "fetchedAt": "2026-11-02T05:21:00.000Z",
      "stored": ["openrouter-models.json", "openrouter-images.json"],
      "sha256": { "openrouter-models.json": "...", "openrouter-images.json": "..." }
    },
    "radius": {
      "url": "https://radius.pi.dev/v1/config",
      "fetchedAt": "2026-11-02T05:22:00.000Z",
      "stored": "radius-config.json",
      "sha256": "..."
    }
  }
}
```

约束：

1. `anchor` 必须等于 `VENDOR.json.anchor`。
2. `generatedAt` 取所有输入 `fetchedAt` 的最大值，**不是**生成命令的运行时刻。
3. 生成器在读快照前校验 `storedSha256`；不匹配立即失败。
4. `modelsDev.providerKeys` 是**过滤白名单**，必须恰好等于 §4.3 的 15 个键；快照文件里若出现白名单之外的顶层键，生成器不报错、也不会写出，但白名单本身必须完整（缺键立即失败）。
5. `radius-config.json` 保存 `/v1/config` 的原始响应；生成器用 vendor 的 `getRadiusModelsFromConfig()` 映射，不自己造模型字段。

**为什么过滤 models.dev 而不提交全量 10.4MB**：

- 全量响应 10.4MB（gzip 约 0.8MB），过滤后约 0.4MB（gzip 约 29KB）。
- 过滤只按 provider 键裁剪，不删模型内部字段，原始条目的字节序和字段保持不变；上游完整哈希仍记录在元数据里，可反查。
- 15 个键来自 18 个 provider 定义实际读取的 source（§4.3），不是手工猜测。

### 4.3 生成器

`generate-java-catalog.ts` 的主流程（关键签名）：

```ts
const PROVIDER_IDS = [
  "anthropic", "openai", "google", "deepseek", "mistral",
  "moonshotai", "moonshotai-cn", "zai", "zai-coding-cn",
  "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
  "minimax", "minimax-cn", "ant-ling", "openrouter", "xai", "radius",
] as const;

const MODELS_DEV_PROVIDER_KEYS = [
  "anthropic", "openai", "google", "mistral",
  "moonshotai", "moonshotai-cn",
  "zai", "zai-coding-plan", "zhipuai-coding-plan",
  "alibaba-token-plan-cn",
  "xiaomi", "xiaomi-token-plan-cn",
  "minimax", "minimax-cn", "xai",
] as const;

interface GeneratorInputs {
  modelsDev: ModelsDevV2Catalog;
  openRouter: OpenRouterCatalog;
  radius: Model<"pi-messages">[];
}

function loadSnapshots(): GeneratorInputs;
function buildJavaCatalog(inputs: GeneratorInputs): Record<string, ProviderCatalog>;
function writeJavaResources(catalogs: Record<string, ProviderCatalog>): void;
function checkResources(catalogs: Record<string, ProviderCatalog>): void;
```

流程：

1. 读 `SOURCES.json`，校验 `anchor`、各文件 `storedSha256`。
2. 读 `models-dev-v2.json`，按 `MODELS_DEV_PROVIDER_KEYS` 裁剪（缺失键直接失败）。
3. 读 `openrouter-models.json` 与 `openrouter-images.json`，调用 vendor 的 `buildOpenRouterCatalog(chat, images, [])`；classifier 列表传空。
4. 读 `radius-config.json`，校验 `baseUrl` 为字符串、`models` 为数组，调用 vendor 的 `getRadiusModelsFromConfig("radius", config)`。
5. 构造 vendor `CatalogSources`：
   ```ts
   const sources: CatalogSources = {
     modelsDev,
     modelsDevDecisions: {},
     openRouter,
     aiGateway: { chat: [], classifiers: [] },
     nvidiaModelIds: new Map(),
     radius,
   };
   ```
6. `buildCatalog(PROVIDERS_SUBSET, sources, true)`；`PROVIDERS_SUBSET` 是 18 个 provider 定义。provider 之间没有 `ctx.providerModels()` 依赖，实例化顺序不影响模型内容；分片最终按 provider id 排序写出。
7. 每个 provider 只保留 `type: "chat"` 与 `type: "image"`；classifier 丢弃（R5）。若 18 个 provider 中任何一个 chat＋image 为空，立即失败（Java 需要分片）。
8. 用 vendor 的 `groupProviderModelData()` 分组，`createModelDataManifest(structure, fileContents, generatedAt)` 生成 manifest。
9. 写临时目录 → `validateModelDataDirectory(structure, tempDir)` → 原子替换 `src/main/resources/catalog/` 下的 18 个分片和 `.manifest.json`；`ATTRIBUTION.txt` 由 `--write-attribution` 生成。
10. `--check` 模式写临时目录后逐字节比较资源；不一致打印差异并以非零退出。
11. `--fetch` 模式（显式联网）抓取上游、写 `SOURCES.json` 与快照；默认模式**永不联网**。

### 4.4 Java 装载器

`BuiltinModelCatalogs` 的改动：

1. `SHARD_IDS` 增加 `"radius"`（18 个）。
2. 新增图片装载：
   ```java
   public static ModelCatalog imageCatalog(String providerId) {
       // 只允许 "openrouter"；懒加载、缓存、不可变。
   }
   ```
   新增包内 `ImageModelDef` 记录（`id`、`name`、`api`、`baseUrl`、`input`、`output`、`cost`、`inputLimits`），**不扩展** `ModelsJsonSchema.ModelDef`；成本复用 `ModelsJsonConfig.pricingFrom(...)`，输入限制复用 `ModelsJsonConfig.inputLimitsFrom(...)`。
   读取同一分片中 `type == "image"` 的条目，校验：
   - 键名 `image:<id>`；
   - 条目 `provider` 等于分片名（`openrouter`）；
   - 条目 `api` 等于组键（`openrouter-images`）；
   - `name` 非空、`baseUrl` 是字符串、`input` 是模态列表、`output` 含 `image`；
   - 四条成本费率齐全。
   映射为 `ModelInfo`：
   - `ModelId` 的 provider 用 `openrouter-images`（Java 的图片 provider id），`modelName` 用条目 id；
   - `capabilities` = `{IMAGE_OUTPUT}`，输入含 `image` 时再加 `IMAGE_INPUT`；
   - `pricing` 来自 `cost`（含 tiers）；
   - `maxInputTokens`/`maxOutputTokens` = 0（pi 图片模型没有这两个字段）；
   - `api = null`（≙ provider 默认协议），`baseUrl` 来自条目，`inputLimits` 来自条目。
   - **不要**走 `ModelsJsonConfig.modelApiOf`：`openrouter-images` 不是 chat `Protocol` 闭集成员，走它会响亮抛 `unknown api`。分片里的组键 `openrouter-images` 仍然校验，但不写进 `ModelInfo.api`；图片面按 provider 分发，与现有手写图片模型的 `api=null` 形状一致。
3. `chatModel` 不因图像条目改变；它只处理 `type == "chat"`。
4. manifest 校验的 `schemaVersion` 仍为 6；`generatedAt` 来自新 manifest。

### 4.5 图片目录接线

`OpenRouterImagesProvider`：

```java
protected ProviderConfig config() {
    return new ProviderConfig(
        "openrouter-images", "OpenRouter Images",
        "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
        Protocol.OPENAI_COMPLETIONS, Set.of(),
        BuiltinModelCatalogs.imageCatalog("openrouter"));
}
```

删除 `BuiltinCatalog.openRouterImageModels()` 及其 `imageModel()` 辅助方法（无其他调用者）。

**与 pi 的形状差异**：pi 的 `openrouter` 是一个 provider，同时持有 chat/image/classifier；Java 保持现有的两个 provider（`openrouter` 与 `openrouter-images`）。生成的 shard 逐字保留 pi 的 `provider: "openrouter"`，由装载器在图片面上重映射为 `openrouter-images`。这不是模型可见文本差异；CLI 列表仍按 Java 的 provider 分组显示。

### 4.6 Radius 基线与 legacy

`RadiusProvider` 的构造：

```java
private static ModelCatalog baselineFor(String gateway) {
    return RadiusGatewayClient.normalizeGateway(gateway)
            .equals(RadiusGatewayClient.normalizeGateway(DEFAULT_GATEWAY))
        ? BuiltinModelCatalogs.catalog("radius")
        : ModelCatalog.empty();
}
```

生产构造器与 DI 构造器都经过这个判断；DI 的 `gateway` 是注入的 `RadiusGatewayClient` 的网关值。

legacy 读取（新增 `AI/provider/RadiusLegacyCatalog.java`）：

```java
public final class RadiusLegacyCatalog {
    public static List<ModelInfo> load(String providerId, OAuthCredentialStore store) {
        return store.resolveRawEntry(providerId)
            .map(node -> node.path("gatewayConfig"))
            .filter(RadiusGatewayConfig::isValidConfigNode)
            .map(node -> RadiusGatewayConfig.fromNode(providerId, node).models())
            .orElse(List.of());
    }
}
```

配套：

- `OAuthCredentialStore` 增加 `public Optional<JsonNode> resolveRawEntry(String provider)`，在现有文件锁内读取原始节点；不改变 `OAuthCredential` 的公开形状，不把 `gatewayConfig` 写回。
- `RadiusGatewayConfig` 抽出 `static RadiusGatewayConfig fromNode(String providerId, JsonNode node)` 与 `static boolean isValidConfigNode(JsonNode node)`；现有 `parse(String, String)` 委托它。
- `RadiusProvider.refreshModels` 的顺序：
  1. `stored` 存在 → 恢复并发布；继续（pi 不在 stored 分支 return）。
  2. `stored` 不存在且 `RadiusLegacyCatalog.load(...)` 非空 → 发布 `persist(models, checkedAt=now)` ＋更新 `dynamicModels`；发布失败则 return。
  3. `!allowNetwork` → return。
  4. 联网拉取 `/v1/config`，发布并更新；失败抛 `IllegalStateException`（现有行为）。
- 网络相 API key 的取值：优先 `context.credential()`（与 pi 一致），为空时回落到现有 `credential` supplier（DI 夹具仍可直接注入）。
- legacy 判定不依赖 `context.credential`：OAuth 存储里存在 `gatewayConfig` 就说明是旧 OAuth 账号；API key 账号没有该字段，自然也导入不到。

### 4.7 OpenRouter 个性化

新增 `AI/provider/builtin/OpenRouterCost.java`：

```java
public final class OpenRouterCost {
    public static PricingInfo fromWire(JsonNode pricing) { ... }
    static double perMillion(String value, double fallback) { ... }
}
```

新增 `AI/provider/builtin/OpenRouterPersonalization.java`：

```java
public final class OpenRouterPersonalization {
    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final Duration USER_MODELS_TIMEOUT = Duration.ofSeconds(15);

    public static List<ModelInfo> personalize(
        List<ModelInfo> catalog,
        RefreshModelsContext context,
        HttpClient http);

    static List<ModelInfo> personalize(
        List<ModelInfo> catalog,
        Optional<RecordedCredential> credential,
        String baseUrl,
        AbortSignal signal,
        HttpClient http);
}
```

行为逐条对齐 §2.5：

| 输入 | 行为 |
|---|---|
| 无凭据 / 凭据值为空 | 返回原列表（同一引用），不发请求 |
| 请求超时（15s）或 IO 失败 | 抛 `IllegalStateException`，消息含底层错误 |
| 401 / 403 / 404 | 返回原列表 |
| 其他非 2xx | 抛 `IllegalStateException("OpenRouter model list request failed: " + status)` |
| 响应体不是对象 / 无 `data` 数组 | 抛 `IllegalStateException("Invalid OpenRouter model list response")` |
| 条目 id 不在 `data` | 从结果中删除 |
| 条目 `context_length`/`top_provider.context_length` 为 0/null | 保留原 `maxInputTokens` |
| 条目 `top_provider.max_completion_tokens` 为 0/null | 保留原 `maxOutputTokens` |
| 条目无 `pricing` | 保留原 `PricingInfo` |
| 条目 pricing 为 `-1` | 按 §2.5 的 fallback 折算成 `0` |

`ModelInfo` 增加专用复制方法：

```java
public ModelInfo withLimitsAndPricing(int maxInputTokens, int maxOutputTokens, PricingInfo pricing) {
    return new ModelInfo(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
        deprecated, pricing, thinkingLevelMap, headers, samplingParams, compat,
        api, baseUrl, promptCache, inputLimits, samplingParamsByThinkingLevel);
}
```

**B156 约束**：`ModelInfo` 有 16 个组件；新增组件时必须同时更新这个复制点。测试 `ModelInfoCopyTest` 用全非默认值构造并断言复制后其余 13 个组件不变；漏更新会让测试变红。

### 4.8 包装器与装配

新增 `pi-java-coding-agent/.../core/PersonalizedCatalogProvider.java`，镜像 §2.6：

```java
public final class PersonalizedCatalogProvider implements Provider {
    public static Provider wrap(Provider delegate, String catalogBaseUrl,
                                Optional<Instant> localGeneratedAt, HttpClient http);
    // getModels(): personalModels 非空用它，否则 delegate.getModels()
    // refreshModels(context): 恢复 → 联网 pi.dev（无 TTL/ETag）→ personalize → 发布
    // baseUrl(): delegate.baseUrl()
}
```

共享逻辑抽取到 `RemoteCatalogWire`（包内可见）：

- `mergeModels`
- `parseCatalog`
- `readCatalog`
- `requestCatalog`
- `isNewer`

`RemoteCatalogProvider` 改为调用这些静态方法；行为不变（304/404/501/TTL 分支保留）。`PersonalizedCatalogProvider` 按 pi 的分支走「404/501 → remote 空、其他非 2xx 抛错、新鲜度门」。

`DefaultProviders` 的装配：

```java
for (var provider : registry.listAll()) {
    if (!builtinIds.contains(provider.name())
            || REMOTE_CATALOG_EXCLUDED.contains(provider.name())) continue;
    if ("openrouter".equals(provider.name())) {
        registry.register(PersonalizedCatalogProvider.wrap(
            provider, catalogBaseUrl, generatedAt, http));
    } else {
        registry.register(RemoteCatalogProvider.wrap(
            provider, catalogBaseUrl, generatedAt, http));
    }
}
```

**文件长度前置改造**：`DefaultProviders.java` 当前恰好 500 行，任何新增都会触发 FileLength error 闸门。实施阶段 B 的第一步是把 `wrapRemoteCatalogs` 与 `REMOTE_CATALOG_EXCLUDED` 整体移到新类 `CatalogProviderWiring`，`DefaultProviders` 只保留两处调用。该重构零行为变化，单独提交。

### 4.9 刷新上下文与协调器

`RefreshModelsContext` 改为 7 组件：

```java
public record RefreshModelsContext(
    Optional<ModelsStoreEntry> stored,
    CatalogPublisherPort publish,
    boolean allowNetwork,
    boolean force,
    Optional<RecordedCredential> credential,
    String baseUrl,
    AbortSignal signal
) {
    public RefreshModelsContext { /* null 归一：stored/credential 空 Optional，signal 新建 */ }
    public static RefreshModelsContext offline(Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish) { ... }
    public static RefreshModelsContext online(
        Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish, boolean force,
        Optional<RecordedCredential> credential, String baseUrl, AbortSignal signal) { ... }
}
```

`Provider` 增加：

```java
/** 注册时生效的 provider baseUrl；null 表示该 provider 没有固定端点。 */
default String baseUrl() { return null; }
```

实现点：

- `ConfigurableProvider.baseUrl()` → `config().defaultBaseUrl()`；
- `RemoteCatalogProvider.baseUrl()` → `delegate.baseUrl()`；
- `PersonalizedCatalogProvider.baseUrl()` → `delegate.baseUrl()`；
- `OverrideProvider.baseUrl()` → 被覆盖 provider 的 `baseUrl()`；
- `FauxProvider.baseUrl()` → null。

`ModelCatalogRefresh`：

```java
public ModelCatalogRefresh(ModelsStore store) {
    this(store, Credentials::resolveCredential);
}

public ModelCatalogRefresh(ModelsStore store,
                           Function<String, Optional<RecordedCredential>> credentials) { ... }

private void runOne(Provider provider, boolean allowNetwork, boolean force, AbortSignal signal) {
    ...
    Optional<RecordedCredential> credential = allowNetwork
        ? credentials.apply(provider.name()) : Optional.empty();
    var context = allowNetwork
        ? RefreshModelsContext.online(stored, publish, force, credential, provider.baseUrl(), signal)
        : RefreshModelsContext.offline(stored, publish);
    provider.refreshModels(context);
}
```

`refresh(...)` 增加重载 `refresh(providers, allowNetwork, force, AbortSignal signal)`；不传时内部 `AbortSignal.create()`。**本仓没有生产取消路径**：信号在整批刷新期间不会被触发；网络请求仍由 4s（pi.dev）／15s（`/models/user`）／现有 HTTP 超时兜底。这与 docs/11 裁决 6 的口径一致：接口对齐，不新增线程竞速。

`credential` 只在联网相解析，避免离线恢复触发 OAuth 刷新网络请求。Radius legacy 导入不读 `context.credential`（见 §4.6），因此离线相不需要凭据。

`CatalogRefreshCoordinator` 的 `ModelsStore.read/write/delete` 本轮仍走一参方法，不接 signal：docs/11 裁决 6 的边界保持不变，存储写入由世代检查兜底。个性化与远程请求在发出前、返回后各检查一次 `signal.isAborted()`；请求本身由 HTTP 超时封顶。

## 5. 状态迁移与线格顺序

### 5.1 生成管线

| 步骤 | 输入 | 输出 | 失败行为 |
|---|---|---|---|
| 1 快照校验 | `SOURCES.json` ＋ 四个快照 | 校验通过 | 缺文件/哈希不符 → 非零退出 |
| 2 models.dev 裁剪 | v2 全量对象 | 15 个 provider 键 | 缺键 → 非零退出 |
| 3 OpenRouter 构建 | chat ＋ image 列表 | `{chat, images, classifiers}` | 条目缺 id/name → 跳过该条 |
| 4 Radius 构建 | `/v1/config` 原始对象 | `Model<"pi-messages">[]` | baseUrl/models 不合法 → 非零退出 |
| 5 规则构建 | 18 个 provider 定义 ＋ sources | `ProviderCatalog` | provider 产出自相矛盾 → 非零退出 |
| 6 类型过滤 | 三类型目录 | chat ＋ image | provider 为空 → 非零退出 |
| 7 分组/manifest | 过滤后目录 | 18 个分片 ＋ manifest | 校验失败 → 回滚 |
| 8 `--check` | 重新生成 | 与资源逐字节比较 | 不一致 → 打印差异并非零退出 |

### 5.2 OpenRouter 个性化决策表

| 输入 | 结果 |
|---|---|
| 无凭据 | 原目录 |
| 401／403／404 | 原目录 |
| 其他非 2xx | 抛错，本次刷新记入 errors，旧 overlay 保留 |
| 响应体非法 | 抛错 |
| chat 模型不在 `data` | 删除 |
| chat 模型在 `data`，限额/价格缺失 | 保留目录值 |
| chat 模型在 `data`，限额/价格存在 | 账号值覆盖 |
| 图片/分类器（若出现在输入列表） | 原样返回；Java 的 chat 包装器输入里没有它们 |

### 5.3 Radius 刷新顺序

| stored | legacy | 网络 | 结果 |
|---|---|---|---|
| 有 | 任意 | 离线 | 恢复 stored |
| 有 | 任意 | 联网 | 恢复 stored → 网络刷新 |
| 无 | 有 | 离线 | 导入 legacy 并持久化 |
| 无 | 有 | 联网 | 导入 legacy → 网络刷新 |
| 无 | 无 | 离线 | 公共基线（默认网关）／空（自定义网关） |
| 无 | 无 | 联网 | 网络刷新；失败则保留基线 |

「网络刷新返回空列表」时发布空列表并更新 `dynamicModels`，不回退基线（组织禁用全部模型的语义）。

### 5.4 刷新上下文传播

| 字段 | 离线相 | 联网相 | 消费者 |
|---|---|---|---|
| `stored` | 有 | 有 | 所有动态 provider |
| `allowNetwork` | false | true | 所有动态 provider |
| `force` | false | 调用方值 | `RemoteCatalogProvider` |
| `credential` | 空 | 解析结果（可为空） | OpenRouter 个性化 |
| `baseUrl` | provider 配置 | provider 配置 | OpenRouter 个性化 |
| `signal` | 新建未触发 | 批次信号 | 所有 provider 的取消检查 |

## 6. RED-first 验收

### 6.1 生成器与数据

| 项 | 用例 |
|---|---|
| `BuiltinModelManifestTest` | 18 个分片逐字节匹配 manifest；`schemaVersion=6`；`generatedAt == SOURCES.generatedAt`；非携带分片仍缺席 |
| `BuiltinModelCatalogsTest` | 新计数（按提交快照钉死）；`gpt-6.1-terra` 与 `MiniMax-M3.1-Flash` 存在；`openrouter` 双车道与 `:batch` id；tiers 经 `CostCalculator` 选档 |
| `ImageModelCatalogTest`（新增） | 64 条（按快照）；provider 重映射为 `openrouter-images`；`black-forest-labs/flux.2-flex` 存在；`output` 含 image；价格来自条目；`inputLimits.images.resize` 默认值 |
| `RadiusBaselineTest`（新增） | 默认网关 28 条；自定义网关空；`balanced` 存在 |
| 生成器自检 | `node catalog-generator/generate-java-catalog.ts --check` 退出 0；输出差异为空 |

生成器自检不是 Maven 测试（构建不依赖 Node），但必须作为阶段 A 的提交前证据记录到本稿 §10。

### 6.2 运行时单测

| 文件 | 用例 |
|---|---|
| `OpenRouterCostTest`（新增） | 缺字段→0；负数→fallback；`min_prompt_tokens` 档；UTC 档跳过；每档缺费率继承基础费率；六位小数 |
| `OpenRouterPersonalizationTest`（新增） | 无 Key 不发请求且返回同一引用；401/403/404 原样；500 抛精确文案；非法体抛精确文案；收窄；限额覆盖；`0/null` 保留；`-1` 价格变 0 |
| `ModelInfoCopyTest`（新增） | `withLimitsAndPricing` 只改三字段，其余 13 个组件保持（B156 守卫） |
| `RefreshModelsContextTest`（新增/扩展） | 7 组件工厂；null 归一；离线 credential 空；联网传 credential/baseUrl/signal |
| `RadiusProviderTest`（扩展） | 基线默认/自定义；legacy 导入并持久化；stored 优先于 legacy；API key 无 legacy；网络空列表不回退 |
| `OAuthCredentialStoreTest`（扩展） | `resolveRawEntry` 返回 `gatewayConfig` 节点；加锁读；不存在返回空 |

### 6.3 包装器与装配

| 文件 | 用例 |
|---|---|
| `PersonalizedCatalogProviderTest`（新增） | 离线恢复；空 stored 回退内置；联网合并 pi.dev ＋个性化；404/501 remote 空；非 2xx 抛错；持久化的是个性化列表；信号中止不发布 |
| `DefaultProvidersRemoteCatalogTest`（扩展） | `openrouter` 是 `PersonalizedCatalogProvider`；`openrouter-images` 不被包装；其余内置仍是 `RemoteCatalogProvider` |
| `ModelCatalogRefreshTest`（新增/扩展） | 注入 credential resolver；联网相传 baseUrl/signal；resolver 抛错记入 errors 且不联网 |

### 6.4 回归

- `mvn test -pl pi-java-ai -am`
- `mvn test -pl pi-java-coding-agent -am`
- 全仓 `mvn clean verify`：测试零失败、Checkstyle 零错误零警告、SpotBugs 零新增。
- 阶段 A 前后各跑一次 `BuiltinModelCatalogsTest`；阶段 B 前后各跑一次 coding-agent 目录刷新套件。

## 7. 验证门与文件约束

1. 先落红灯测试，再写实现；阶段 A/B 各自一轮。
2. 模块验证：`mvn test -pl pi-java-ai -am`；阶段 B 加 `mvn test -pl pi-java-coding-agent -am`。
3. 包闭环前：`mvn clean verify`；日志不写 `target/`，关键结果回填 §10。
4. Java 单文件 ≤ 500 行：
   - `DefaultProviders.java` 当前 500 行 → 先抽 `CatalogProviderWiring`；
   - `RemoteCatalogProvider` 当前 329 行 → 抽 `RemoteCatalogWire` 后仍应 < 400；
   - 新增 `PersonalizedCatalogProvider`、`OpenRouterPersonalization`、`OpenRouterCost`、`RadiusLegacyCatalog` 各自 < 300。
5. 不使用 `@SuppressWarnings`。
6. vendor 的 `.ts` 文件不参与 Java 风格闸门，但必须逐字节来自锚点并记录哈希。
7. 不新增 Maven 依赖；Node 版本要求写入 `README.md`（Node ≥ 24，原生 TypeScript 类型擦除）。

## 8. 裁决与风险

### 裁决 1：采用「仓内 vendor 规则 ＋ 提交快照」，不用 Java 重写生成器

- 【场景】pi 的目录规则约 1,500 行，含多个模型家族的事实；Java 重写会形成第二套真相，且每次数据刷新都要重新同步。
- 【决定】把锚点的 `catalog/` 规则、`scripts/model-data.ts` 与最小运行时依赖逐字节 vendor 到 `pi-java-ai/catalog-generator/vendor/`，用本仓编剧读快照并写 Java 资源；`VENDOR.json` 记录逐文件 sha256。
- 【风险/代价】[内部，模型不可见] 仓库多约 2,500 行第三方 TS 代码；生成期需要 Node ≥ 24，但构建/运行不依赖。vendor 升级必须整体换锚，不能局部改规则。

### 裁决 2：models.dev 快照按 provider 键裁剪，并记录上游全量哈希

- 【场景】上游 v2 全量 10.4MB；Java 只消费 15 个 provider 键。
- 【决定】快照只存 15 个键（约 0.4MB），`SOURCES.json` 记录上游 URL、抓取时刻、上游字节数/哈希、过滤键列表和存储哈希。
- 【风险/代价】[内部，模型不可见] 快照不再逐字节等于上游响应；但过滤是「删顶层键」，不碰模型内部字段，上游哈希仍可反查。若未来 Java 新增 provider，必须补键并重新抓取快照。

### 裁决 3：`generatedAt` 取快照抓取时刻，不取生成命令时刻

- 【场景】pi 用 `new Date()`；同一份快照在不同时间生成会得到不同 manifest，无法逐字节复现。`generatedAt` 又是远程目录守卫的比较基准。
- 【决定】`generatedAt` 取所有输入 `fetchedAt` 的最大值，写入 `SOURCES.json` 并由生成器读取。生成命令不读系统时钟。
- 【风险/代价】[内部，模型不可见] 与 pi 的字面实现不同，但语义更准确：它表示「这份数据在那一刻是上游的最新值」。远程守卫的行为不变。

### 裁决 4：manifest 只列实际携带的 18 个分片

- 【场景】旧 manifest 复制了上游 41 个文件，但仓库只携带 17 个；校验器要额外解释「缺失是正常的」。
- 【决定】新生成器只输出 18 个分片的 manifest；`BuiltinModelManifestTest` 不再需要「41 里缺 24」的豁免。
- 【风险/代价】[内部，模型不可见] manifest 不再是上游全量清单；上游全量可通过 `SOURCES.json` 追溯。

### 裁决 5：图片目录进入 `openrouter.json`，Java 装载时重映射 provider

- 【场景】pi 的 `openrouter` provider 同时持有 chat/image/classifier；Java 现有两个 provider（`openrouter`、`openrouter-images`）。
- 【决定】生成的分片保留 pi 的 `provider: "openrouter"`；`BuiltinModelCatalogs.imageCatalog("openrouter")` 把 `ModelId` 的 provider 重映射为 `openrouter-images`；classifier 条目丢弃（R5）。
- 【风险/代价】[内部，模型不可见] 数据形状与 pi 一致，Java 的 provider 分组保持现状。图片模型不进入 pi.dev 远程 overlay（Java 的图片 provider 不被包装），与今天一致。

### 裁决 6：OpenRouter 个性化放在 AI 层，包装器放在 coding-agent

- 【场景】pi 的 `personalizeOpenRouterCatalog` 是 AI 包导出的函数；`withPersonalizedCatalog` 是 coding-agent 的装配层。
- 【决定】AI 层新增 `OpenRouterPersonalization` 与 `OpenRouterCost`；coding-agent 新增 `PersonalizedCatalogProvider`，`DefaultProviders` 对 `openrouter` 选用它而不是 `RemoteCatalogProvider`。
- 【风险/代价】[内部，模型不可见] 这是本包唯一的跨层行为改动；只碰 `DefaultProviders`、新包装器和共享 wire 抽取，不实施其他 L5 行为。`DefaultProviders` 先做 500 行拆文件。

### 裁决 7：`RefreshModelsContext` 补 `credential`/`baseUrl`/`signal`，但不新增生产取消路径

- 【场景】`/models/user` 需要凭据和 provider 端点；docs/11 只把 `ModelsStore` 的操作信号补上，刷新上下文仍是四组件。
- 【决定】按 pi 形状补三字段；联网相由 `ModelCatalogRefresh` 解析凭据并传 `provider.baseUrl()`；`AbortSignal` 由批次创建，但当前没有调用方触发。
- 【风险/代价】[内部，模型不可见] 接口对齐，行为上仍由 HTTP 超时兜底；不引入线程竞速。离线相不解析凭据，避免 OAuth 刷新副作用。

### 裁决 8：Radius legacy 读 OAuth 存储原始节点，不扩展 `OAuthCredential`

- 【场景】pi 的 `gatewayConfig` 是 `OAuthCredential` 的交叉类型扩展；Java 的 record 若加字段会把旧字段写回存储，改变文件形状。
- 【决定】`OAuthCredentialStore.resolveRawEntry()` 在锁内返回原始 `JsonNode`；`RadiusLegacyCatalog` 只读 `gatewayConfig`；不写回、不改变 `OAuthCredential` 的公开构造。
- 【风险/代价】[内部，模型不可见] 旧字段在后续凭证刷新时仍可能被 store 的整对象回写覆盖（与 pi 的 store 行为一致）；导入只发生一次，导入后目录进入 ModelsStore。

### 裁决 9：阶段 A/B 分开提交、分开翻账

- 【场景】数据快照与运行时行为混在一个提交里，测试红点难以归因。
- 【决定】阶段 A 只翻 #34/#33 与 #21 的「基线」部分；阶段 B 只翻 #16 与 #21 的「legacy/运行时」部分。两阶段都通过后才把 #21 整行翻为对齐。
- 【风险/代价】[内部，模型不可见] 提交数增加；账面在阶段 A 后暂不发布 #21，避免半行计分。

### 风险表

| 风险 | 控制 |
|---|---|
| vendor 规则与锚点不一致 | `VENDOR.json` 逐文件 sha256；`--verify-vendor` 复核 |
| 快照抓取时上游正在变化 | `SOURCES.json` 固定 URL/时刻/哈希；生成器不联网 |
| 新数据导致大量旧测试红 | 只改数据断言，不改行为断言；按 §3.6 清单逐项处置 |
| `DefaultProviders` 超 500 行 | 先抽 `CatalogProviderWiring`，零行为变化单独提交 |
| 个性化误删用户模型 | 只在 chat 列表上按 `/models/user` 收窄；401/403/404 原样保留 |
| `/models/user` 超时拖慢启动 | 15s 请求超时；刷新在虚拟线程里；失败记入 errors 不阻断其他 provider |
| Radius 基线复活被组织禁用的模型 | 账号目录整体替换基线；空列表也发布，不回退 |
| B156 复制点漏字段 | `ModelInfoCopyTest` 全字段守卫 |
| Node 版本不符 | `README.md` 写明 Node ≥ 24；`--check` 在版本不足时明确报错 |

## 9. 账面影响与后续

- 阶段 A 完成后：地图 #34/#33 翻「对齐」；#21 仍保持「缺失」（运行时未完成）。L1 加权由 `187.0/240` 变为 `190.0/240＝79.2%`（+3）。
- 阶段 B 完成后：#16 与 #21 翻「对齐」。L1 加权变为 **`195.0/240＝81.25%`**；未加权 `104.5/131＝79.8%`。
- 域表：
  - Provider 端点：`33/39` → `38/39＝97.4%`；
  - 模型目录／元数据：`23/28` → `26/28＝92.9%`；
  - 其他域不变。
- `docs/04` 同步：B265b 备注拆「阶段 A/阶段 B」；#16/#21/#33/#34 的旧结论改为「已按 docs/12 闭环」。
- 后续：
  1. `docs/map/02-ai.md` 的 #24/#28 能力投影窄差仍为「存疑」，由后续 AI 包处理。
  2. B263（AI telemetry）、B266（ClientIdentity）、B267（Provider wire/usage/OAuth）继续独立设计。
  3. 阶段 B 的 `RefreshModelsContext.signal` 只做到接口对齐；真正的调用方取消（`/model` 刷新中断、Ctrl+C）仍属 L5 行为。
  4. pi.dev 远程目录的 `?types=` 参数与图片/分类器 overlay 另行复核，不在本包翻账。

## 10. 闭环记录

### 10.1 阶段 A

| 项 | 结果 |
|---|---|
| 快照抓取时刻 | `2026-11-02T07:47:46.212Z`（所有输入同一时刻；`generatedAt` 同值） |
| `models.dev` 上游 sha256／字节 | `6e43d13f7f301b80c296d69f9d2aa792baab0aa8f5e98a8b3f322aebb8925a3f`／10,407,077 字节；裁剪后 424,945 字节 |
| 15 个 provider 键的模型计数 | 15 键共 317 条原始模型条目（快照只删顶层键） |
| 18 个分片计数（chat／image） | chat 600，image 64；逐家见生成器输出（anthropic 17、openai 44、google 23、mistral 34、openrouter 388＋64、radius 28 等） |
| `--check` 退出码/差异 | 0；`Catalog resources match the committed snapshots.` |
| `mvn test -pl pi-java-ai -am` | telemetry 80 ＋ AI 1,828，0 失败／0 错误 |
| 提交 | `000e161c`（vendor 生成器）、`d0590b33`（快照与 18 分片）、`3de72378`（Java 装载器/图像目录/Radius 基线） |

### 10.2 阶段 B

| 项 | 结果 |
|---|---|
| `/models/user` 夹具用例数 | `OpenRouterPersonalizationTest` 9、`OpenRouterCostTest` 6 |
| 包装器/装配用例数 | `PersonalizedCatalogProviderTest` 7、`ModelCatalogRefreshTest` 6、`DefaultProvidersRemoteCatalogTest` 4 |
| 下游 `mvn test -pl pi-java-coding-agent -am` | telemetry 80 ＋ AI 1,852 ＋ mcp 260 ＋ agent-core 37 ＋ coding-agent 1,780，0 失败／0 错误 |
| 全仓 `mvn clean verify` | 见下方验证行 |
| 账面 | L1 **195.0/240＝81.25%**；未加权 104.5/131＝79.8% |
| 提交 | `b4ce4db4`（装配抽取）、`474e0f3e`（刷新上下文）、`9ba73363`（个性化与 legacy）、`e0f5cb8d`（coding-agent 包装） |

**验证**：全仓 `mvn clean verify`（2026-11-02 18:24）BUILD SUCCESS，5,901 测试／0 失败／0 错误／29 skipped（存量跳过），Checkstyle 0 违规，SpotBugs 0。

**评审修复（2026-11-02）**：终审发现「个性化列表经 `RemoteModelWire` 持久化后离线重启丢元数据」。`RemoteModelWire` 补 `api`/`baseUrl`/cache 费率/`tiers`；个性化包装器离线恢复时把存储条目叠回内置目录的完整元数据（headers／inputLimits／compat），并保留账号限额与价格。`PersonalizedCatalogProviderTest.offlineRestoreRebuildsMetadataLostByTheStoreWire` RED→GREEN（绕过叠回时 `api` 断言为 `null`）；提交 `be71e3e4`。其余评审 Minor 项（生成器原子替换/排序、ATTRIBUTION 行尾、Radius signal 等）作为已知低风险项记入评审记录，不在本包展开。
