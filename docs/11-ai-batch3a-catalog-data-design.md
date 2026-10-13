# 11 ai 批 3a 详稿：模型目录数据面（npm 锚点数据转资源 ＋ promptCache/inputLimits/samplingParamsByThinkingLevel 字段）

> 状态：**已闭环 2026-10-14**（两 commit `e38c3ab9` 资源＋装载器、`86465196` 三字段；全量 `mvn clean verify` 绿——11 模块 3370 测，其中 ai **1542**＋coding-agent 522，checkstyle 零警告；加权 90.2%→**92.2%**）。
> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）；数据源 **npm `@earendil-works/pi-ai@1.1.0` tarball**
> （npm 包是 pi 发布物、git 里 data/ 被 ignore，`git show` 取不到——这是走 npm 的原因）。
> 上游判据：`bin pi` 主流发布版行为；本包只做主流 7 家＋已注册 provider。
> 路线：[`08`](08-ai-layer-alignment-roadmap.md) §3 批 3（3a 已闭环，3b＝B137 主体待出稿）。
>
> **实施差异（相对本稿，均不改变设计结论）**：
> 1. §4.3 计数笔误：换源是 **17 个 provider 类**（provider 包 5 ＋ builtin 包 12；ollama 不在内，仍用 `ModelData.ollamaModels()`），非「18 个类（13 个 builtin）」。
> 2. 新增仓根 `.gitattributes`：17 个 JSON 钉 `-text`（二进制），否则 Windows autocrlf 改写字节后 sha256 夹具在他人 checkout 上变红（staged blob 字节已验与 tarball 一致）。
> 3. `BuiltinCatalog` 索引由 `HashMap` 改 `LinkedHashMap`——pi `Object.values` 保文件序，首条序断言（anthropic/openrouter 首条）需要它。
> 4. T1 两处取证值更正：haiku-5-5 **基价是 0.1/0.5**（非稿中 1/5；100K tier 0.5/2.5/.05/.625 不变）；两档样本 `qwen3-coder-flash` 在 **openrouter** 分片（id `qwen/qwen3-coder-flash`，基价 0.195/0.975＋32K/128K 两档），不在 qwen-token-plan-cn 分片。
> 5. Responses 车道接 sptl 时修出一处既有缺口：级别原来只认 `extra["reasoningEffort"]` 显式通道，现同时回退循环级 `request.reasoning()`（与同方法 reasoning 块的级别源 :167-169 同源）。

---

## 0. 范围一览

| 条目 | 内容 | 本包动作 |
|---|---|---|
| map/02 **#34**（w2，缺失） | 内置目录数据规模：Java 手写 73 条 vs pi 锚点 18 家 **588 chat/391KB** | 17 分片原样进 resources＋装载器，18 provider 全切数据驱动 |
| map/02 **#86**（w1，缺失） | `Model.promptCache`（cache 寿命秒数） | ModelInfo 落字段；资源 17 条 anthropic 带值；models.json 模型/覆盖两层接通 |
| map/02 **#90**（w1，缺失） | `Model.inputLimits`（请求字节/图片张数/缩放参数） | ModelInfo 落字段（含三层嵌套 record）；资源 17 个变体全载；models.json 两层接通。**消费链（B85 缩放）不做** |
| map/02 **#91**（w1，缺失） | `Model.samplingParamsByThinkingLevel` | ModelInfo 落字段；models.json 解析＋覆盖合并；请求期 `resolveSamplingParams` 三层合并接进 SamplingParamsWriter（completions＋responses） |
| 台账 **B232** | v1.1.0 数据漂移（Haiku 5.5、Kimi K3 钉价、prompt-length tiers 等） | 随 #34 销号 |
| 台账 **B97** | 3 个内置 (provider,modelId) 漂移＋Java 独有的 text-embedding 类 | 随 #34 销号（旧 id 全表替换，embedding 两条从内置目录移除） |
| 附带必做 | 无配置默认模型不能因首条序变成怪 id（google 首条是 deep-research 预览） | 移植 pi `defaultModelPerProvider` 表的 18 provider 子集（B137 的**最小防回归切片**，glob/alias/诊断仍留 3b） |

**加权影响**（闭环时回填）：#34 +2、#86 +1、#91 +1、#90 缺失→存疑 +0.5 ⇒
197.5＋4.5 ＝ **202.0/219 ＝ 92.2%**（总表另算）。

明确不做见 §6（B137 主体→3b、图像/分类器 R5、远程 wire 扩展、`supportsMidConvoEffort`→批 4、CacheWarmer/图片缩放消费链）。

---

## 1. 数据源取证（npm tarball，字节可复现）

### 1.1 取包与完整性

```
npm view @earendil-works/pi-ai@1.1.0 dist.tarball dist.integrity
→ https://registry.npmmirror.com/@earendil-works/pi-ai/-/pi-ai-1.1.0.tgz
→ sha512-1T7LAkc/5Bvc0v6w4vAGVdCrli0o/E0pEmYKTnixu95vSFArBjvbhS/G4ZwI0RUePgf0Imcu0VyqlM4EcXxqfw==
```

下载后本地 `sha512sum` base64 与 integrity **逐字符一致**；`package/package.json` = name `@earendil-works/pi-ai`、version `1.1.0`、**license MIT**（pi 仓库根 LICENSE：Copyright (c) 2025 Mario Zechner）。随包分发须保留 attribution（落点见 §4.1）。

### 1.2 分片与 manifest

`package/dist/providers/data/`：**42 文件**＝41 provider 分片＋`.manifest.json`（1021KB）。
manifest 原文：

```json
{"schemaVersion":6,"generatedAt":"2026-10-07T22:01:28.515Z",
 "structureHash":"080cfcf6bdd13064ce2362f26e4902c31503b675fd06a8c4685c04a0333bc465",
 "files":{"amazon-bedrock.json":"d9381ff9…", …}}
```

- `files` 是**全 41 文件**的 sha256（含我们不载的 25 家）。
- Java 取**已注册 18 provider ＝ 17 分片**（openrouter 一家含 chat＋image＋classifier）＝实测 **391,350 字节**，与 docs/08 §4 的 391KB 精确吻合。
- 17 分片：`anthropic openai google deepseek mistral moonshotai moonshotai-cn zai zai-coding-cn qwen-token-plan-cn xiaomi xiaomi-token-plan-cn minimax minimax-cn ant-ling openrouter xai`。
- 其余 25 家（bedrock/vertex/azure/radius/codex/vercel/cloudflare×2/cerebras/groq/nvidia/github-copilot/huggingface/fireworks/together/baseten/opencode×2/typesafe/meta/kimi-coding/qwen-token-plan×2）按 docs/08 §1.1 的 w0/R5 口径**不取**；ollama 在 pi 无分片（本地模型走 models.json），Java 保留手写。

### 1.3 分片形状（两层 group）

每个分片＝`{ "<api 线格名>": { "<type>:<id>": <Model> } }`。
17 分片全量枚举（node 逐文件统计）：

- 条目 **664**＝chat **588**＋image 61（全在 openrouter）＋classifier 15（openrouter 14＋openai 的 openai-decisions 1）。
- chat 数：anthropic 17、openai 44（另有 1 classifier）、google 22、deepseek 2、mistral 40、moonshotai 4、moonshotai-cn 4、zai 7、zai-coding-cn 4、qwen-token-plan-cn 20、xiaomi 6、xiaomi-token-plan-cn 4、minimax 3、minimax-cn 3、ant-ling 3、**openrouter 401**（anthropic-messages 16＋openai-completions 385；另 61 image/14 classifier）、xai 4。
- api 组键：单 api 16 家；openrouter 四组 `anthropic-messages / openai-completions / openrouter-images / typesafe-system-one`；openai 两组（`openai-responses` chat＋`openai-decisions` classifier）。
- 资源只收 `type:"chat"`（image→#33 R5、classifier→#94 R5，均出分母）。

chat 条目字段频次：`id/name/api/provider/baseUrl/input/cost/type` 664 级；`reasoning/maxTokens` 588；`contextWindow` 603；`compat` 515；`thinkingLevelMap` 320；**`promptCache` 17、`inputLimits` 467 条次→17 个去重变体**；`headers/samplingParams/samplingParamsByThinkingLevel` **零生产者**（与 map/02 #91「生成目录零生产者，只来自 models.json」一致）。

`anthropic.json` 首条逐字（字段全集示例）：

```json
"chat:claude-fable-5": {
  "id":"claude-fable-5","name":"Claude Fable 5","api":"anthropic-messages",
  "provider":"anthropic","baseUrl":"https://api.anthropic.com","reasoning":true,
  "input":["text","image"],
  "cost":{"input":10,"output":50,"cacheRead":1,"cacheWrite":12.5},
  "contextWindow":1000000,"maxTokens":128000,
  "compat":{"supportsMidConvoSystemMessages":true,"supportsMidConvoToolChanges":true,
    "forceAdaptiveThinking":true,"supportsStrictTools":true,
    "allowedFallbackModels":[{"provider":"anthropic","model":"claude-opus-4-8",
      "cost":{"input":5,"output":25,"cacheRead":0.5,"cacheWrite":6.25}}, …]},
  "thinkingLevelMap":{"off":null,"xhigh":"xhigh","max":"max"},
  "promptCache":{"short":300,"long":3600},
  "inputLimits":{"maxRequestBytes":33554432,
    "images":{"maxPerRequest":600,"resize":{"maxWidth":2000,"maxHeight":2000,
      "maxBytes":4718592,"jpegQuality":80}}},
  "type":"chat"}
```

关键值域：

- **cost**：四费率（USD/百万 token）全；**tiers 104 条**（request-wide 阶梯，最高命中阈值适用于整请求），1–2 档；如 `anthropic/claude-haiku-5-5 → [{inputTokensAbove:100000,input:0.5,output:2.5,cacheRead:0.05,cacheWrite:0.625}]`，`openrouter/qwen/qwen3-coder-flash` 两档（32000/128000）。Java `PricingInfo.CostTier` 已是同形（map/02 #25 对齐）。
- **promptCache**：恰好 17 条，全是 anthropic、全 `{short:300,long:3600}`（fable-5/5-1、haiku-4-5×2、haiku-5-5、opus-4-5/4-5-dated/4-6/4-7/4-8/5/5-5、sonnet-4-5×2/4-6/5/5-5）。
- **inputLimits 17 变体**：4 家带 `maxRequestBytes`（anthropic 32MB、openai 512MB、google 20MB；anthropic 的 `maxPerRequest` 按模型 600/100 两值，openai 1500，google 3600）；其余 13 家只有 `images.resize`（2000×2000/4718592/80）；**无 `maxPerMessage` 生产者**（schema 有位，models.json 可达）。
- **compat** 17 分片出现的 23 个键，22 个 Java `ModelsJsonSchema.CompatDef` 已覆盖；唯一不认识的是 `supportsMidConvoEffort`（9 条）——批 4（B99）的字段，本包经 `ignoreUnknown` 跳过（同今意，§6）。
- 排序敏感：anthropic 首条 `claude-fable-5`；**google 首条是 `deep-research-max-preview-04-2026`**（这是 §4.7 必须移植默认表的原因）；openai 首条 `gpt-4`；openrouter 组序 anthropic 在前（首条 `anthropic/claude-fable-5`）。

### 1.4 pi 侧装载链（锚点）

数据文件 gitignore，由 `scripts/generate-models.ts` 生成；运行时代码：

- `packages/ai/src/providers/<name>.models.ts`（自动生成，全部同形），`anthropic.models.ts:1-11`：

  ```ts
  import values from "./data/anthropic.json" with { type: "json" };
  export const ANTHROPIC_MODELS: ChatModelCatalog<typeof values, "anthropic"> =
      flattenChatModelCatalog("anthropic", values);
  ```

- `packages/ai/src/model-catalog.ts:41-63` —— **拍平语义**（跨 api 组、按 `type` 过滤、以 `id` 为键、保文件序）：

  ```ts
  function flattenModelCatalog(groups: ModelGroups, type: ModelType): Record<string, object> {
      return Object.fromEntries(
          Object.values(groups)
              .flatMap((models) => Object.values(models))
              .filter((model) => (model as { type?: unknown }).type === type)
              .map((model) => [(model as { id: string }).id, model]),
      );
  }
  ```

- `packages/ai/src/providers/all.ts:96-99` —— 全局唯一生成时间戳（远程覆盖守卫用）：

  ```ts
  export function getBuiltinModelDataGeneratedAt(): number | undefined {
      const generatedAt = Date.parse(modelDataManifest.generatedAt);
      return Number.isNaN(generatedAt) ? undefined : generatedAt;
  }
  ```

- provider 定义直接挂拍平表，`deepseek.ts:7-17`：`models: Object.values(DEEPSEEK_MODELS)`（顺序＝对象插入序＝文件序）。
- `scripts/model-data.ts` —— manifest 的生产/校验语义：`MODEL_DATA_SCHEMA_VERSION = 6`；`files`＝每文件 UTF-8 字节的 sha256 hex；`validateModelDataDirectory` 校验 schemaVersion、generatedAt 可解析、structureHash、文件名集合、逐文件 hash、条目身份（`modelKey === type:id`、`value.provider === providerId`、`value.api === 组键`）、chat 必填 reasoning/contextWindow/maxTokens 正数、cost 四费率数字。**Java 侧用「sha256＋关键身份」夹具对齐这层信任**（不移植完整 validator，数据是 pinned 不可变输入）。

### 1.5 schema 逐字（`packages/ai/src/providers/model-schema.ts`）

```ts
// :3-6
export const THINKING_LEVELS = ["minimal","low","medium","high","xhigh","max"] as const;
export const MODEL_THINKING_LEVELS = ["off", ...THINKING_LEVELS] as const;
// :15-22
export const ModelPromptCacheSchema = Type.Partial(
  Type.Record(Type.Enum(MODEL_PROMPT_CACHE_RETENTIONS), Type.Number({ exclusiveMinimum: 0 })),
  { description: "Best-effort prompt cache lifetime in seconds for each retention tier. …" });
// :24-45 cost：ModelCostRatesProperties = input/output/cacheRead/cacheWrite（Number，USD per million）；
//   ModelCostTier = { inputTokensAbove:Number, …四费率 }；cost.tiers?: Tier[] 可选
// :47-79 图片限：
//   modelImageResizeOptions = { maxWidth?, maxHeight?, maxBytes?(base64 bytes), jpegQuality?(1..100) }
//   ModelImageInputLimits = { resize?, maxPerMessage?(min1), maxPerRequest?(min1) }
//   ModelInputLimits = { maxRequestBytes?(min1), images?: ModelImageInputLimits }
```

`types.ts`（锚点）：

```ts
// :820-832 BaseModel
export interface BaseModel<TApi extends string> {
  id: string; name: string; api: TApi; provider: ProviderId; baseUrl: string;
  input: ModelInputModality[];
  inputLimits?: ModelInputLimits;
  cost: ModelCost;
  headers?: Record<string, string>;
}
// :834-866 Model（chat）
  type?: "chat";
  reasoning: boolean;
  thinkingLevelMap?: ThinkingLevelMap;          // :846
  promptCache?: ModelPromptCache;              // :848
  contextWindow: number; maxTokens: number;    // :849-850
  samplingParams?: SamplingParams;             // :852
  samplingParamsByThinkingLevel?: SamplingParamsByThinkingLevel; // :854
  compat?: …;                                   // :855-865（按 api 联合）
```

### 1.6 models.json 语义（`coding-agent/src/core/`）

`model-config.ts:40-64`：models[] 与 modelOverrides 两套 schema **都含** `inputLimits/promptCache/samplingParams/samplingParamsByThinkingLevel`（TypeBox 同一 schema 件）。

`provider-composer.ts`（锚点）：

```ts
// :144-162 inputLimits 三层深合并（maxRequestBytes/images 标量覆盖，images.resize 再深一层）
function mergeInputLimits(base, override) {
  if (!override) return base;
  return { ...base, ...override,
    images: override.images ? { ...base?.images, ...override.images,
      resize: override.images.resize ? { ...base?.images?.resize, ...override.images.resize }
                                     : base?.images?.resize } : base?.images };
}
// :164-180 sptl：7 档（off+6）逐档 map 合并，override[level] falsy 不动
function mergeSamplingParamsByThinkingLevel(base, override) { … }
// :177-208 applyModelOverride（promptCache 键级合并 :196；inputLimits :190；sptl :201-204；
//   cost 逐费率＋tiers 整值 :191-199；samplingParams 键级 :200）
// :210-249 modelFromJson：
//   inputLimits: definition.inputLimits,            // :239 整值
//   promptCache: definition.promptCache,            // :241 整值
//   samplingParamsByThinkingLevel: definition.samplingParamsByThinkingLevel, // :245 整值
//   contextWindow ?? 128000、maxTokens ?? 16384、cost ?? {0,0,0,0}
```

### 1.7 请求期 sptl 解析（`packages/ai/src/api/simple-options.ts:24-33`）

```ts
export function resolveSamplingParams(
  model: Model<Api>, thinkingLevel: ModelThinkingLevel, requestParams?: SamplingParams,
): SamplingParams | undefined {
  const effectiveThinkingLevel = clampThinkingLevel(model, thinkingLevel);
  const thinkingLevelParams = model.samplingParamsByThinkingLevel?.[effectiveThinkingLevel];
  return model.samplingParams || thinkingLevelParams || requestParams
    ? { ...model.samplingParams, ...thinkingLevelParams, ...requestParams }
    : undefined;
}
```

`buildBaseOptions:42` 调用时级别＝`options?.reasoning ?? "off"`。
`clampThinkingLevel`（`models.ts:1235-1256`）：级别不在模型可用集时先向上找、再向下找（Java 已有逐字移植 `ModelThinkingLevels.clamp`，`catalog/ModelThinkingLevels.java:62`）。

消费点：三 OpenAI 车道在 body 最后 `Object.assign(params, samplingParams)`（completions `openai-completions.ts:996-999`、responses 362-365、azure 同形副本）。Java 落点是单一文件 `protocol/SamplingParamsWriter.java`（两车道共用），其 javadoc :43 已自注「thinking-level 层（#91）随批 3」。

### 1.8 默认模型表（防回归切片，`coding-agent/src/core/model-resolver.ts:16-58`）

`defaultModelPerProvider` 是 39 provider 的常量表；已注册 18 provider 子集（ollama 无条目）的默认 id 全部实测**在 17 分片中存在**：

| provider | 默认 id（pi 逐字） | provider | 默认 id |
|---|---|---|---|
| anthropic | claude-opus-4-8 | minimax / minimax-cn | MiniMax-M2.7 |
| openai | gpt-5.5 | moonshotai / -cn | kimi-k2.6 |
| google | gemini-3.1-pro-preview | zai / zai-coding-cn | glm-5.3 |
| deepseek | deepseek-v4-pro | qwen-token-plan-cn | qwen3.7-max |
| mistral | devstral-medium-latest | xiaomi / xiaomi-token-plan-cn | mimo-v2.5-pro |
| xai | grok-4.7 | ant-ling | Ring-2.6-1T |
| openrouter | moonshotai/kimi-k2.6 | | |

表在 pi 住 coding-agent；本包把 18 行子集落 ai 模块（ai 已拥有 `DefaultModelResolver`，且 `ProviderCatalog` 也在 ai）。

---

## 2. Java 现状（改造前）

| 件 | 现状 |
|---|---|
| `catalog/BuiltinCatalog.java`（259 行） | 5 家手写工厂：anthropic 4、openai 5（含 2 embedding）、google 2、deepseek 2、mistral 2，外加 openRouterImageModels 8；compat 由 `CatalogCompatRules` 谓词现算；`of(List)` 是各装配链共用的 Map 支撑 |
| `provider/builtin/ModelData.java`（170 行） | 9 国内家手写 22 条＋ollama 2；`GENERATED_AT=2026-09-20T00:00:00Z`（javadoc 明写「刷新内置模型表时必须同步更新它」） |
| `provider/builtin/OpenRouterModels.java`（221）/`XaiModels.java`（118） | openrouter 24 chat（手写 api/baseUrl/PROMPT_CACHING 派生）、xai 4 |
| `catalog/CatalogCompatRules.java`（305） | 生成期规则的手写谓词（fable/deepseek-v4-pro 等逐 id）；**唯一消费者**就是上面 4 个手写数据源 |
| 手写合计 | **73 条**（map/02 #34 的 Java 侧数字） |
| `provider/ModelsJsonSchema.java` | models.json DTO：`ModelDef` 有 cost.tiers/compat/thinkingLevelMap/headers/samplingParams，**缺 promptCache/inputLimits/sptl**；`ModelOverrideDef` 有 promptCache（Map）但缺另两个；`CompatDef` 39 组件齐 |
| `provider/ModelsJsonConfig.java` | `toModelInfo:196`（三源 baseUrl、compat 合并、pricingFrom:296 含 tiers）；pricing/thinkingMap 私有 |
| `provider/ModelsJsonCompat.java` | raw compat → `ModelCompat`，三态保持、闭集响亮抛；**资源数据可直接复用**（generated compat 与 models.json compat 同 schema） |
| `provider/ModelJsonMerge.java` | models[] upsert＋override；`:157` 自注「o.promptCache() 解析但忽略」；inputLimits/sptl 无字段可合 |
| `protocol/SamplingParamsWriter.java` | 两层合并（模型打底→请求覆盖），javadoc 已留 #91 位 |
| `catalog/ModelInfo.java`（213） | 13 组件；分层便捷构造器齐全；加 3 组件可保持旧构造全活 |
| `model/DefaultModelResolver.java` | null/裸 provider ⇒ 取该 provider **首条**；切冒号级别；未列名 id 合成（stderr 警告） |
| coding-agent `RemoteCatalogProvider` | `localGeneratedAt` 守卫（`:255-259` 丢弃 Last-Modified 不晚于本地生成时间的 overlay）；生产装配传 `ModelData.generatedAt()`（DefaultProviders:79-87） |
| `catalog/PricingInfo.java` | 四费率＋`List<CostTier>` 已齐；`-1` 未知语义齐 |
| `catalog/ModelThinkingLevels.java` | supported/clamp 已是 pi 逐字移植 |
| resources | **pi-java-ai 无 `src/main/resources` 目录，本包新建** |

生产代码引用旧手写 id 的位置：**零**（grep 仅命中注释/测试；`AiCli.java:239` 的 `text-embedding-3-small` 是 `pi-ai embed` 子命令的 flag 默认值，不查目录，保留不动）。

---

## 3. 改动设计总览（提交序）

1. **commit 1（feat，资源＋装载器＋provider 全切＋默认表防回归）**：resources 19 文件、新 `BuiltinModelCatalogs`、新 `DefaultModelIds`、18 provider 换源、删 4 个手写数据源＋CompatRules、generatedAt 接通 manifest、旧夹具改造。
2. **commit 2（feat，三字段）**：ModelInfo 加 promptCache/inputLimits/sptl；models.json DTO/转换/override 合并；SamplingParamsWriter 三层；夹具。
3. **commit 3（docs）**：闭环回填 docs/04、06、08、map/02、05＋本文件横幅。

两 commit 的夹具各自独立绿；commit 1 不依赖 commit 2 的字段（资源里这三个字段在 commit 1 阶段随 `ignoreUnknown` 暂落不到 ModelInfo，与今「解析但忽略」同状；commit 2 立即接上，最终状态全绿）。

---

## 4. commit 1：资源目录＋装载器

### 4.1 资源文件（新建 `pi-java-ai/src/main/resources/catalog/`）

- 17 分片**逐字节原样复制**（`cp` npm tarball 解包文件，不重新序列化——保 sha256 可校验、保键序）：anthropic/openai/google/deepseek/mistral/moonshotai/moonshotai-cn/zai/zai-coding-cn/qwen-token-plan-cn/xiaomi/xiaomi-token-plan-cn/minimax/minimax-cn/ant-ling/openrouter/xai `.json`。
- `.manifest.json` **原样全量复制**（含 41 文件 hash；夹具只校验我们携带的 17 个键，其余 25 键是「上游存在、本仓不携」的记录）。
- `ATTRIBUTION.txt`（新文件，MIT 义务）：

  ```
  The JSON files in this directory (except this file) are copied verbatim from
  the npm package @earendil-works/pi-ai@1.1.0 (dist/providers/data/),
  Copyright (c) 2025 Mario Zechner, MIT License.
  Source integrity: npm sha512-1T7LAkc/…qfw== ; manifest schemaVersion 6,
  generatedAt 2026-10-07T22:01:28.515Z. Per-file sha256: see .manifest.json.
  ```

资源目录总计 ≈399KB（391,350＋manifest 约 8KB）。

### 4.2 `provider/BuiltinModelCatalogs.java`（新建，放 `provider` 包以复用包级 `ModelsJsonCompat`/Schema）

职责＝pi 的 `flattenChatModelCatalog`＋`Object.values(MODELS[provider])`＋manifest generatedAt。

```java
package com.pijava.ai.provider;
// public final class，私有构造
public static ModelCatalog catalog(String providerId)  // 懒加载、ConcurrentHashMap 缓存、不可变
public static java.time.Instant generatedAt()          // 解析 /catalog/.manifest.json 的 generatedAt，缓存
```

装载算法（纯函数部分包可见，供夹具直接测）：

1. `ClassLoader.getResourceAsStream("/catalog/<id>.json")`；缺席/IO 异常 ⇒ `IllegalStateException("builtin model catalog missing: catalog/<id>.json")`（数据 pinned，启动期响亮失败）。
2. Jackson 读成 `JsonNode root`（mapper 不排未知键）。
3. 按**文件字段序**遍历 api 组、组内条目（ObjectMapper 默认保 LinkedHashMap 序）：
   - 仅收 `node.path("type").asText("") == "chat"`（image/classifier 跳过≙flattenChatModelCatalog）；
   - `treeToValue(node, ModelsJsonSchema.ModelDef.class)`（§5 给 ModelDef 扩三字段后一次成型；commit 1 阶段先吃既有字段）；
   - 身份校验（对齐 model-data.ts validator 的关键三条，数据损坏时响亮）：`def.id()` 非空、`entry.provider == providerId`、`entry.api == 组键名`、键名 == `chat:<id>`；
   - 转 `ModelInfo`（见下 `toModelInfo`）。
4. `BuiltinCatalog.of(models)`（保序，dup id ⇒ 后者覆盖同 Map 语义；validator 保证不发生）。

`toModelInfo`（与 models.json 链的差异：**严格、无默认值**——chat 条目必填项由上游 validator 兜底）：

- caps：恒 `TEXT/TOOL_USE/STREAMING`；`reasoning==true` 加 THINKING；`input` 含 image 加 IMAGE_INPUT。
  **不派生 PROMPT_CACHING/COMPUTER_USE**（pi 无此二布尔；现 OpenRouterModels/XaiModels 的 `cacheRead>0⇒PROMPT_CACHING` 是 Java 方言，全 main 无读者，删）。
- contextWindow/maxTokens：Integer 直取（null ⇒ 响亮错；chat 必有）。
- pricing：复用 `ModelsJsonConfig.pricingFrom(provider, id, cost)`（提为 package-private static；四费率＋tiers 规则不变，错误文案前缀改可参量化或容忍「models.json」字样——数据 pinned 不会触发；**选容忍，不改签名**，在 javadoc 注明两源共用）。
- thinkingLevelMap：复用把 `ModelsJsonConfig.thinkingLevelMapOf` 提为 package-private static。
- compat：`ModelsJsonCompat.compatOf(providerId, def, def.compat(), null)`（provider 层 compat 传 null；generated 值即最终显式值，缺省位走该方法既有归一：temperature/finishReason 缺省 true、adaptive/strictTools/allowEmpty 缺省 false、三态键保 null 交 CompatResolver 探测——与 pi 读点 `explicit ?? detected` 同构）。
- api：`Protocol.fromWire(def.api())` 校验（复用 ModelsJsonConfig 里 modelApiOf 的同等逻辑，提一个 package-private `wireApi(provider, id, api)`；未知值启动期响亮）。
- baseUrl：`def.baseUrl()`（chat 条目必有；不再需要 OpenRouterProvider 的 pin 兜底？**保留** pin：它只在 options.baseUrl 缺席且模型 baseUrl 未到达车道时兜底，数据模型 baseUrl 经现有三源链生效，两条保护互不冲突——见 §4.4 接线审计）。
- headers/samplingParams：def 值或空表（目标分片零生产者，但走通以备 models.json 同构）。
- name：`def.name()`（必填，无 fallback 也可；保守 `nonBlank(name)?name:id`）。
- promptCache/inputLimits/sptl：commit 2 接入（见 §5）。

manifest：读 `/catalog/.manifest.json` 成 `JsonNode`；`generatedAt()`＝`Instant.parse("2026-10-07T22:01:28.515Z")`（直接解析字符串；测试钉死该瞬时，不允许「当前时间」）。

### 4.3 provider 换源（18 个类，每类 1 行参数）

`AnthropicProvider/OpenAIProvider/GoogleProvider/DeepSeekProvider/MistralProvider`（provider 包 5 个）＋ builtin 包 `MoonshotAi{Cn,}Provider/Zai{CodingCn,}Provider/QwenTokenPlanCnProvider/Xiaomi{,TokenPlanCn}Provider/MiniMax{Cn,}Provider/AntLingProvider/OpenRouterProvider/XaiProvider`（13 个）：catalog 参数改 `BuiltinModelCatalogs.catalog("<name>")`。名字与分片 `provider` 键已逐一核对（minimax 两家用 anthropic-messages 线格与 ProviderConfig 协议一致；openrouter 两线格、xai responses、google generative-ai 均一致）。

不动：`OllamaProvider`（pi 无数据，留 `ModelData.ollamaModels()`）、`OpenRouterImagesProvider`（image ＝ #33 R5，保留 8 条手写）。

### 4.4 删除与保留

- **删** `BuiltinCatalog` 的 `anthropicModels/openaiModels/googleModels/deepseekModels/mistralModels/all` 五工厂及其私有 helper（anthropicModel/openaiChatModel/deepseekModel/model 的启发式 provider 推断/六组 caps）；**留** `of(List)`、Map 支撑、find/search、`openRouterImageModels()`＋`imageModel/embeddingModel?`（imageModel 留；embeddingModel 随 openaiModels 一起删——唯一使用者是被删工厂）。
- **删** `ModelData` 的 9 家国内工厂；留 `ollamaModels()`（搬入本类或新小类均可——**选留原文件**，仅 ollama 两模型）与 `generatedAt()`（**改为 `return BuiltinModelCatalogs.generatedAt();`**，删 2026-09-20 常量）。
- **删** `OpenRouterModels.java`、`XaiModels.java`、`CatalogCompatRules.java` 三个文件。
- `ProviderCatalog.all()/allModels()` 不改（provider 数 19 不变；聚合数＝588 chat＋8 image＋2 ollama＝598）。
- `OpenRouterProvider.pinnedOptions`/ANTHROPIC_BASE_URL **保留**（models.json 自造 provider 与未列名模型仍可能走到；无行为害处）。
- DefaultProviders 装配不改（`ModelData.generatedAt()` 自动变成 10-07 ⇒ RemoteCatalogProvider 守卫边界同步，正是 B232 要求的「generatedAt 必须同步」）。

### 4.5 `model/DefaultModelIds.java`（新建，30 行内）＋ resolver 接线

```java
public final class DefaultModelIds {
  private static final Map<String,String> BY_PROVIDER = Map.ofEntries(
    // 18 行，值逐字照抄 model-resolver.ts:16-58 的 18 provider 子集；ollama 无条目
  );
  public static Optional<String> forProvider(String provider) { … }
}
```

`DefaultModelResolver.resolve(pattern, defaultProvider)` 两处「取 provider 首条」改为「**默认表命中且目录有此 id ⇒ 它；否则首条**」：

1. `pattern == null/blank` 支（resolve(required, preferred) 那条能力解析路径不动——那是按能力集取首，与 CLI 默认不同）；具体是 `:161-169`（name 空＝裸 provider）与 `:176-183`（裸 provider 名）两支，以及 `:131-136` 空 pattern 支。
2. 空 pattern 支的兜底 provider 字面量 `"google"` 是既有方言，不动；但选出的模型改经默认表 ⇒ `google/gemini-3.1-pro-preview`。

夹具钉：无参/null 模式给 gemini-3.1-pro-preview；裸 "anthropic" 给 claude-opus-4-8；裸 "openrouter" 给 moonshotai/kimi-k2.6；表外 provider（ollama）仍首条。

### 4.6 旧夹具改造（commit 1 内）

- 删 `BuiltinCatalogTest`、`CatalogCompatRulesTest`、`OpenRouterModelsTest`、`XaiModelsTest`（断言被新装载器夹具取代）。
- 新 `BuiltinModelCatalogsTest`（§7 T1）。
- 16 个 wire 测试里用工厂取真 id 的：工厂删后改用 `BuiltinModelCatalogs.catalog(...)` 取同 id；已消失 id 替换：
  - `deepseek-v4-flash` → `deepseek-flash`（deepseek 分片）；
  - `mistral-large/mistral-small` → 分片现 id（如 `mistral-small-latest`，按各夹具用途选）；
  - 价格/上下文断言（fable 3/15→**10/50**、200K→**1M** 等）按新数据改；deepseek-v4-pro/gpt-5/gpt-5-mini/gpt-5-nano/gemini-2.5-pro 保留；
  - 国内家旧 id（kimi-k2.5/glm-4.7/mimo-v2/MiniMax-M2/ling-1t/qwen3-*）在测试中的出现点全换分片现 id。
- `ModelDataGeneratedAtTest` 断言改 `2026-10-07T22:01:28.515Z` 且与 manifest 同源（可直接断言 `ModelData.generatedAt().equals(BuiltinModelCatalogs.generatedAt())`）。
- coding-agent：`DefaultProvidersTest`/`RemoteCatalogProviderTest` 等若钉死数量/首条，按新表改（实施时全量跑 -am 暴露）。

---

## 5. commit 2：promptCache ／ inputLimits ／ samplingParamsByThinkingLevel

### 5.1 新领域类型（`catalog` 包，3 新文件）

```java
/** pi ModelPromptCache（model-schema.ts:15-22）：short/long 秒数，null＝该档未知（不 warm）。 */
public record PromptCacheLifetimes(Integer shortSeconds, Integer longSeconds) {
    static PromptCacheLifetimes fromWire(Map<String,Double> wire) { /* 只认 short/long，>0；未知键忽略 */ }
}
```

```java
/** pi ModelInputLimits（model-schema.ts:72-79）；两级全 null ⇒ 该层缺席。 */
public record InputLimits(Integer maxRequestBytes, ImageLimits images) {
  public record ImageLimits(ResizeOptions resize, Integer maxPerMessage, Integer maxPerRequest) {}
  public record ResizeOptions(Integer maxWidth, Integer maxHeight, Integer maxBytes, Integer jpegQuality) {}
  // 嵌套 DTO→领域转换（3 层）；任一层全 null ⇒ null（不造空壳）
}
```

sptl **不造新命名类型**：`Map<ModelThinkingLevel, Map<String,Object>>`（七档闭集、值是 SamplingParams；与 ThinkingLevelMap 同理由 Map 保「键在场但 null」三态——不过 pi override 合并对 falsy 跳过，故值表本身不存 null：`null` 值档忽略）。

### 5.2 `ModelInfo` 加 3 组件（第 14–16 位，在 baseUrl 后）

```java
ModelInfo(…, String api, String baseUrl,
          PromptCacheLifetimes promptCache,
          InputLimits inputLimits,
          Map<ModelThinkingLevel, Map<String,Object>> samplingParamsByThinkingLevel)
```

compact：null 归一（promptCache/inputLimits 可空；map `Map.copyOf`＋深拷贝一层值 map）。旧 13 参「规范构造」降为便捷构造、委托三新位 null/空；**既有 12 个生产构造点与全部便捷构造零改签**（CatalogModel/RemoteModelWire/minimal 等走旧构造 ⇒ 三字段缺席，§6 非目标）。

### 5.3 models.json 链接通

- `ModelsJsonSchema`：
  - `ModelDef` 加 `promptCache(Map<String,Double>)`、`inputLimits(InputLimitsDef)`、`samplingParamsByThinkingLevel(Map<String,Map<String,Object>>)`；
  - `ModelOverrideDef` 加后两个（promptCache 已在）；
  - 新增嵌套 DTO record（`InputLimitsDef/ImageLimitsDef/ResizeDef`，全 Integer/String 宽松、ignoreUnknown；不复用领域 record，边界 DTO 惯例同 FallbackModelDef）。
- `ModelsJsonConfig.toModelInfo`：三字段转换入 ModelInfo 新构造（modelFromJson 整值语义，provider-composer.ts:239-245）。
- `ModelJsonMerge.applyOverride`：照 pi :177-208 补三路合并——
  - promptCache：`{...base, ...override}` 键级；
  - inputLimits：§1.6 的**三层**深合并（新辅助 `mergeInputLimits`）；
  - sptl：七档逐档 map 合并（`mergeSamplingParamsByThinkingLevel` 逐字；未知档忽略与现有 thinking map 同口径）；
  - `rebuild/withFields` 改走新 16 参构造，未覆盖时透传 `m.*`（现状会把字段丢成 null 的坑随接随修）。

### 5.4 请求期三层合并（#91 行为落点）

`SamplingParamsWriter`：

```java
// pi simple-options.ts:24-33
static LinkedHashMap<String,Object> merged(ModelInfo model,
        ModelThinkingLevel effectiveLevel, Map<String,Object> requestParams) {
  var m = new LinkedHashMap<String,Object>();
  SimpleOptions.samplingParamsOf(model).ifPresent(m::putAll);          // model.samplingParams
  var clamped = ModelThinkingLevels.clamp(model, effectiveLevel);      // models.ts:1235
  var byLevel = model.samplingParamsByThinkingLevel().get(clamped);
  if (byLevel != null) m.putAll(byLevel);
  if (requestParams != null) m.putAll(requestParams);                  // 请求同名最后赢
  // 三源皆空 ⇒ 空表 ⇒ apply* 既有 isEmpty 早返回（≙ pi 返回 undefined）
}
```

签名加一级别参数，两调用点：

- completions（`OpenAICompletionsMessageConverter:310`）：级别取 `request.reasoning()`（`Optional<ThinkingLevel>`；缺席＝off）经现有 ThinkingLevel→ModelThinkingLevel 适配（实施时复用 SimpleOptions 里 cl* 方法已有的适配；pi `options?.reasoning ?? "off"`）；
- responses（`:197`）：`ropts.reasoningEffort()`（ThinkingLevel，可空）同换。

Typed-setter 覆盖语义（temperature/max_tokens/max_completion_tokens/max_output_tokens/reasoning_effort 五个具名键）维持现状不变——级别层同样可能含这些键，合并后仍走 typed setter 顺序，天然正确。

### 5.5 资源装载器接三字段

commit 1 的 BuiltinModelCatalogs.toModelInfo 在 commit 2 补：`PromptCacheLifetimes.fromWire(def.promptCache())`、inputLimits 转换、sptl map 转换（七档 parse；ModelDef 新字段已就位）。于是 17 条 anthropic promptCache 与 467 条次 inputLimits 生效。

---

## 6. 明确不做（本包边界）

1. **B137 主体 → 批 3b**：glob/minimatch 多模型 scoping（`*?[]`）、子串匹配、alias vs dated 排序（localeCompare）、`ModelScopeDiagnostic` 两码、CLI `--models` 多模式解析（model-resolver.ts 全文 400+ 行，消费者 Ctrl+P cycling）。本包只拿 `defaultModelPerProvider` 18 行做防回归（§4.5），B137 台账保留并在闭环注记里缩差。
2. **图像 61 条/分类器 15 条不载**（#33/#94 R5 已出分母；openrouter 图像 provider 维持 8 条手写现状，两侧同状悬空）。
3. **RemoteModelWire/CatalogModel 不扩字段**：pi.dev overlay 与 FileModelsStore 缓存路径今天就丢 compat/tiers/headers/samplingParams（既存形状差，非本包条目）；新三字段同理不接远程线。如要做应整条「remote wire 对齐」另案。
4. **`compat.supportsMidConvoEffort` 不识别**（9 条）：批 4（B99/B110）字段；ignoreUnknown 跳过，批 4 随 schema 一起加。
5. **embedding 两条从内置目录移除**（gpt 目录本无 chat 型 embedding）：`pi-ai embed` 子命令不查目录、flag 默认值保留，行为零变化；B97 的「Java 独有类」随之消除。
6. **消费链接线不做**：CacheWarmer（#86 的 coding-agent 消费者，453 行）、图片缩放/注记管线（#90/B85，sharp 替代）、/session 费用分组等（B148 批 5）。本包只到「字段在 ModelInfo 上、从两源可解析」。
7. 不移植 `check-model-data.ts` 完整校验器；信任锚＝sha256 夹具＋装载期身份断言。
8. 不取 25 个 w0/R5 provider 分片（bedrock 103KB/vercel 125KB 等），不切 azure/radius 口径（docs/08 §1.1 用户裁决维持）。

---

## 7. 测试计划

**T1 `BuiltinModelCatalogsTest`（新建，装载器）**
1. 17 家 chat 计数逐家钉（17/44/22/2/40/4/4/7/4/20/6/4/3/3/3/401/4＝588）；`ProviderCatalog.allModels()`＝598（含 ollama 2＋image 8）。
2. 序：anthropic 首条 fable-5；openrouter 组序 anthropic 先、首条 `anthropic/claude-fable-5`。
3. fable-5 逐值：api、baseUrl、reasoning、input、cost 10/50/1/12.5、context 1,000,000、maxTokens 128,000、compat 四值（forceAdaptiveThinking/supportsStrictTools/supportsMidConvo*）＋allowedFallbackModels 两条（opus-4-8 5/25/.5/6.25、opus-5 同）、thinkingLevelMap（off 显式不支持、xhigh/max 有映射）、promptCache 300/3600、inputLimits 32MB/600/2000/2000/4MB/80。
4. haiku-5-5 tiers 命中（100K 档 0.5/2.5/.05/.625；基价 1/5）；qwen3-coder-flash 两档（32K/128K）经 CostCalculator 选择正确（边界 token 输入）。
5. kimi-k3（moonshotai 与 -cn 各一）钉价 `{3,15,0.3,0}`（B232 的 a2eef9eb6）。
6. openrouter：`:batch` 与 `~` 前缀 id 存在且可 find（冒号 id 不被解析器切碎的既有保护仍有效）；`anthropic/*` 16 条 api=anthropic-messages、baseUrl `https://openrouter.ai/api`；余 385 completions。
7. 排除面：openrouter 61 image/14 classifier、openai 1 classifier 均不载（find flux/jev-latest 空）；无任何 embedding id。
8. 怪值响亮：反射/临时坏资源不写测试（pinned 数据），改用对纯函数传 JsonNode 夹具验 provider/api/type 身份不符抛错。
9. api 闭集：588 条的 api 全部 `Protocol.fromWire` 通过（循环断言，顺带钉住 compat 闭集：thinkingFormat/maxTokensField/cacheControlFormat 无加载期异常）。

**T2 `BuiltinModelManifestTest`（新建，信任锚）**
1. classpath 17 分片逐文件 sha256 hex ⇒ 等于 `.manifest.json.files[<name>]`。
2. manifest `schemaVersion==6`；`BuiltinModelCatalogs.generatedAt()==2026-10-07T22:01:28.515Z`；`ModelData.generatedAt()` 与之一致。
3. 资源集合恰好＝17 分片＋`.manifest.json`＋`ATTRIBUTION.txt`（按固定名单断言；amazon-bedrock/azure/vercel 三个缺席探针）。

**T3 `ModelPromptCacheWireTest`（新建，models.json）**
models.json 模型带 promptCache ⇒ ModelInfo 两档；modelOverrides.promptCache 键级合并（只给 long ⇒ short 保留）；给 null/负值 ⇒ schema 宽松读、转换忽略坏档（与 TypeBox 行为对齐面：不炸文件）。资源侧 17 条 anthropic 全带 300/3600（经 T1-3 已覆盖）。

**T4 `ModelInputLimitsWireTest`（新建）**
models.json 全四层解析；override 三层深合并（base maxRequestBytes 保留、images.maxPerRequest 覆盖、resize.jpegQuality 深覆）；资源侧抽查 anthropic 100 张变体（haiku-4-5）与 deepseek「仅 resize」变体。

**T5 `SamplingParamsByThinkingLevelWireTest`（新建，#91 行为）**
1. models.json 模型 sptl：`{high:{reasoning_effort:"high"}, xhigh:{…}}`；completions 车道 reasoning=high 时 body 含合并键且顺序 model→level→request（request 同名赢；temperature typed setter 不双写）。
2. clamp：请求 xhigh 而模型未映 xhigh（opt-in 规则）⇒ 向上/向下取档（用 ModelThinkingLevels 既有规则选档），用的是 clamp 后档的 map。
3. reasoning 缺席 ⇒ off 档；三源皆空 ⇒ 请求体无杂键（回归 today）。
4. responses 车道一条同构断言；modelOverrides 的 sptl 逐档 map 合并一条。
5. 未知级别键忽略（七档闭集）。

**T6 `DefaultModelIdsTest`（新建）**
§4.5 四分支＋**全表 17 provider 默认 id 都在资源目录中**（循环断言，防未来换数据时静默默认回退首条）。

**T7 旧夹具改造**：§4.6 清单随实施全量改绿。

全量验证：`JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test`；本包触 coding-agent（DefaultModelResolver/RemoteCatalogProvider 行为），收尾 `mvn clean verify`（~34min）。checkstyle:check；无 @SuppressWarnings 新增；文件 ≤500 行。

---

## 8. 文件清单与行数预算

| 文件 | 动作 | 预算行数 |
|---|---|---|
| `resources/catalog/*.json`×17＋`.manifest.json`＋`ATTRIBUTION.txt` | 新增（数据，不计 LOC） | +399KB |
| `provider/BuiltinModelCatalogs.java` | 新增 | ~180（含 toModelInfo/身份校验/缓存） |
| `model/DefaultModelIds.java` | 新增 | ~35 |
| `catalog/PromptCacheLifetimes.java` | 新增 | ~35 |
| `catalog/InputLimits.java`（含两嵌套 record） | 新增 | ~70 |
| `catalog/ModelInfo.java` | 改（＋3 组件＋便捷构造） | 213→~250 |
| `provider/ModelsJsonSchema.java` | 改（ModelDef/OverrideDef＋3 嵌套 DTO） | 307→~370 |
| `provider/ModelsJsonConfig.java` | 改（转换＋2 helper 提权） | 322→~360 |
| `provider/ModelJsonMerge.java` | 改（三路 override 合并/rebuild） | 256→~320 |
| `protocol/SamplingParamsWriter.java` | 改（三层 merged＋签名） | 102→~125 |
| 2 个 converter 调用点 | 各改 1 行 | +2 |
| `model/DefaultModelResolver.java` | 改（3 处首条支走默认表） | 211→~225 |
| 18 个 provider | 各改 1 行 catalog 参数 | ±0 |
| `provider/builtin/ModelData.java` | 删 9 厂留 ollama＋generatedAt 委托 | 170→~45 |
| `catalog/BuiltinCatalog.java` | 删 5 厂留 of/image | 259→~110 |
| `provider/builtin/OpenRouterModels.java`、`XaiModels.java`、`catalog/CatalogCompatRules.java` | **删** | −644 |
| 测试：T1–T6 六个新类 | 新增 | ~700 |
| 测试：删 4 类＋改 ~20 类 | 改造 | 总量与现状持平 |

净生产 LOC 预估 **−100 上下**（删 644＋BuiltinCatalog −150＋ModelData −125，加 ~330 新码＋~160 改造增量）。

commit 计划：

1. `feat(ai): load builtin model catalogs from pinned npm 1.1.0 data shards (588 chat models, #34/B97/B232)`
2. `feat(ai): carry promptCache/inputLimits/samplingParamsByThinkingLevel through catalog and models.json (#86/#90/#91)`
3. `docs(ai): batch-3a closure — catalog data plane aligned to pi 1.1.0`

---

## 9. 实施与闭环

审核通过后按 §3 三 commit 推进；每 commit：显式 `git add <path>`、trailer `Co-Authored-By: Claude Code <noreply@anthropic.com>`、不 push main。

闭环回填：docs/04（B97/B232 销号；B137 注记缩差留 3b）、docs/06（#34/#86/#90/#91 行）、docs/08（批 3 行拆 3a✅/3b 待；加权 90.2%→92.2%）、map/02（四单元判定翻转＋证据列）、docs/05 总表；记忆追加。

## 附：取证命令存档

```bash
# npm 源（完整性已对 registry integrity 校验）
npm view @earendil-works/pi-ai@1.1.0 dist.tarball dist.integrity
curl -sSL -o pi-ai-1.1.0.tgz <tarball> && sha512sum → base64 比对
tar xzf pi-ai-1.1.0.tgz   # package/dist/providers/data/
# pi 源码（锚点只读）
git show ea448f454:packages/ai/src/providers/model-schema.ts
git show ea448f454:packages/ai/src/model-catalog.ts
git show ea448f454:packages/ai/src/providers/all.ts
git show ea448f454:packages/ai/src/api/simple-options.ts
git show ea448f454:packages/ai/scripts/model-data.ts
git show ea448f454:packages/coding-agent/src/core/provider-composer.ts
git show ea448f454:packages/coding-agent/src/core/model-config.ts
git show ea448f454:packages/coding-agent/src/core/model-resolver.ts
```
