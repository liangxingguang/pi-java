# 65 — D-P1：models.json 对**现有 provider** 的覆盖合并

**状态：✅ 已闭环（2026-10-02，`cbe1f76`，15 files +1271/−256；R1–R4 全按建议）**
**创建基准：** pi-java `0aa62d8`（D3 收尾）

> 范围＝当 `models.json` 的 provider id **命中内置 provider**（openai/anthropic/google…）时，
> 按 pi 的分层**合并**（baseUrl/api/headers/compat/modelOverrides ＋ models[] upsert），
> 而不是报错或整条替换。**不新增 provider、不动凭据链。**

---

## 1. pi 事实清单（file:line 已核，源 `coding-agent/src/core/`）

### 1.1 models.json 与内置 provider 逐层合并（`provider-composer.ts:209-221`）

```ts
const models: Model<Api>[] = baseModels.map((model) => ({
    ...model,
    baseUrl: config.oauth === "radius" ? model.baseUrl : (config.baseUrl ?? model.baseUrl),
    compat: mergeCompat(model.compat, config.compat),
}));
for (const definition of config.models ?? []) {
    const existingIndex = models.findIndex((model) => model.id === definition.id);
    const defaults = findModelDefaults(models, definition.id, definition.api ?? config.api);
    const model = modelFromJson(providerId, definition, config, defaults);
    if (existingIndex >= 0) models[existingIndex] = model;
    else models.push(model);
}
```
⇒ **provider 级 baseUrl/compat 是 `??` 叠加到每一个内置模型**；`models[]` 里同 id **整条替换**、
新 id **追加**。

### 1.2 provider 级各字段的 `??` 与 api 的三源（`model-config.ts:211-222`、`provider-composer.ts:142-149`）

```ts
// ProviderConfigSchema
name?, baseUrl?, apiKey?, api?, oauth?, headers?, compat?, authHeader?, models?, modelOverrides?
// modelFromJson
const api = definition.api ?? providerConfig.api ?? defaults?.api;
const baseUrl = definition.baseUrl ?? providerConfig.baseUrl ?? defaults?.baseUrl;
```
⇒ per-model api 缺席 ⇒ provider 级 api；provider 级也缺席 ⇒ 内置模型自己的 api（`defaults`）。
**只有三者皆无才抛错**（`:143`）。

### 1.3 新增 model 的缺省继承内置形状（`provider-composer.ts:156-172`）

```ts
reasoning: definition.reasoning ?? false,
input: (definition.input ?? ["text"]),
cost: definition.cost ?? { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
contextWindow: definition.contextWindow ?? 128000,
maxTokens: definition.maxTokens ?? 16384,
compat: mergeCompat(providerConfig.compat, definition.compat),
```
`findModelDefaults`（`:175-182`）：同 id → 同 api → 任意 completions → models[0]，
**仅用于「三者皆无 api」兜底**，不做逐字段继承。

### 1.4 headers 三层合并（`provider-composer.ts:408-421`、`model-runtime.ts:480-491`）

```ts
// rawModelHeaders：per-model，三源同键合并（均缺席 ⇒ undefined）
{ ...config?.modelOverrides?.[model.id]?.headers,
  ...definition?.headers,
  ...extensionModel?.headers }
// getAuth：model.headers（内置目录自带）再 mergeHeaders(configuredHeaders)
```
⇒ 次序：**内置 model.headers → modelOverrides.headers → models[].headers**（后者同键覆盖）。

### 1.5 compat 的合并：浅合并 ＋ 四个嵌套 map 深合并（`provider-composer.ts:86-106`）

```ts
const merged = { ...base, ...override };
for (const key of ["openRouterRouting", "vercelGatewayRouting",
                   "chatTemplateKwargs", "chatTemplateArgs"]) {
    // 任一侧为 object ⇒ {...baseValue, ...overrideValue}
}
```

### 1.6 modelOverrides 是**最顶层**逐字段覆盖（`provider-composer.ts:108-134`、`:458-461`）

```ts
// 顺序：base → models.json models[] → extension → modelOverrides（最后）
models.map((model) => {
    const override = config?.modelOverrides?.[model.id];
    return override ? applyModelOverride(model, override) : model;
});
```
覆盖字段：name/reasoning/input/contextWindow/maxTokens（`??`）；thinkingLevelMap/cost/
promptCache/samplingParams（键级合并）；compat（mergeCompat）；headers（在 1.4 已处理）。

### 1.7 provider 级校验（`provider-composer.ts:193-207`）

只配了 `name`（或全空）⇒ 抛 `must specify "baseUrl", "headers", "compat",
"modelOverrides", or "models"`。**baseUrl 单独不再是必填** —— 覆盖现有 provider 时可以只给
headers/compat/modelOverrides。

---

## 2. pi-java 现状差（`ModelsJsonConfig.java` 已 530 行，超 500）

| # | 点 | 现状 |
|---|---|---|
| 2.1 | **命中内置 id ⇒ 要么报错要么整条替换** | `DefaultProviders.registerModelsJsonProviders:48-57` 把 models.json 建成的 provider 直接 `registry.register`：① 只给 baseUrl/headers/compat（无 api/baseUrl/models 形态的旧校验）⇒ `buildProvider:159-168` 抛错被吞成 warning；② 给了 models ⇒ provider 整条替换内置，内置模型全丢。**无合并**（B102 已登记）。 |
| 2.2 | **per-model baseUrl 被静默吞**（B136） | `ModelDef.baseUrl()` 已解析，`toModelInfo:192-221` 不读；`ModelsJsonProvider` 只有 provider 级一个 baseUrl。 |
| 2.3 | **headers 无生产者** | `ModelInfo.headers` 进了记录但全树零主代码消费点（grep `\.headers\(\)` 仅赋值处）。 |
| 2.4 | **modelOverrides 未加载** | `ModelsJsonSchema.ProviderDef` 无该字段；pi 的 `modelOverrides`（model-config.ts:221）整条缺失。 |
| 2.5 | provider 级 apiKey/oauth/authHeader | 现状用 `apiKey`（已支持）；`oauth`/`authHeader` 本包不做（oAuth 层与新 auth 面，超范围，登记）。 |

**结论**：本包＝把「models.json 配置」从「替换/报错」改造成**合并层**，落在一个新的
`ModelOverrides` 合并器里；同时接通 B136（per-model baseUrl）、headers 消费、modelOverrides
加载。**B102 随包结案。**

---

## 3. 改动方案

### Step 1 — schema 补字段（`ModelsJsonSchema.java`）

`ProviderDef` 增：
```java
@JsonProperty("headers") Map<String, String> headers,
@JsonProperty("modelOverrides") Map<String, ModelOverrideDef> modelOverrides
```
新增（逐字段对应 §1.6，全部可空 ⇒ 组件用包装类型）：
```java
@JsonIgnoreProperties(ignoreUnknown = true)
record ModelOverrideDef(
    @JsonProperty("name") String name,
    @JsonProperty("reasoning") Boolean reasoning,
    @JsonProperty("thinkingLevelMap") Map<String, String> thinkingLevelMap,
    @JsonProperty("input") List<String> input,
    @JsonProperty("cost") Cost cost,
    @JsonProperty("promptCache") Map<String, Double> promptCache,
    @JsonProperty("contextWindow") Integer contextWindow,
    @JsonProperty("maxTokens") Integer maxTokens,
    @JsonProperty("samplingParams") Map<String, Object> samplingParams,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("compat") CompatDef compat) {}
```
`ModelDef` 已含 `baseUrl/headers`（B136 的 JSON 侧已具备）。

### Step 2 — 新合并器 `ModelJsonMerge`（`provider` 包，新文件）

纯函数，镜像 §1.1-1.6；签名：
```java
static List<ModelInfo> merge(String providerId, List<ModelInfo> base, ProviderDef cfg)
```
步骤（与 pi 同序）：
1. **provider 级覆盖到每个内置模型**：`cfg.baseUrl()` 缺席 ⇒ 原样；在场 ⇒
   `withBaseUrl`；`cfg.compat()` ⇒ `mergeCompat`（Step 3）；`cfg.headers()` 在此**不**
   直接挂（per-model 合并在第 3 步，§1.4 的次序）。
2. **models[] upsert**：同 id ⇒ 由 `toModelInfo` 构造（per-model `api` 缺席时
   **继承内置模型的 api**：取被替换项的 `api()`；新增 id 的 api 三源＝
   `model.api ?? cfg.api ?? null`，全缺时**新增模型**按现有默认仍给 null 走 provider 默认——
   见 R1）；**per-model baseUrl（B136）**：`toModelInfo` 读 `model.baseUrl()`，三源
   `model.baseUrl ?? cfg.baseUrl ?? 内置`。
3. **headers 合并**（每模型）：内置 headers → `modelOverrides[id].headers` →
   `models[].同 id 的 headers`，经 `ApiOptions.extra["headers"]` 在请求面接线（Step 4）。
4. **modelOverrides 最后**逐字段覆盖（§1.6）：`??` 标量 ＋ thinkingLevelMap/cost/
   promptCache/samplingParams 键级合并 ＋ compat mergeCompat。
5. provider 级校验（§1.7）：`cfg` 除 name 外全空 ⇒ 抛错（带 provider id）。

### Step 3 — compat 合并（放 `ModelCompat` 同包的新 `CompatMerge`，或并入 Step 2 文件）

浅合并（null 组件 ⇒ 取 base）＋ 四个嵌套 `Map` 深合并
（`openRouterRouting/chatTemplateKwargs/chatTemplateArgs`；**`vercelGatewayRouting`
pi-java 无此组件 ⇒ 不做**）。null 语义：override 的三态 `Boolean` 组件缺席（null）⇒
保留 base 组件 —— 与会「显式 false」可区分。

### Step 4 — 接线

- **`DefaultProviders.registerModelsJsonProviders`**：改为先读 `ModelsJsonConfig.loadDefault()`，
  对每个 provider id：命中已注册 provider ⇒ **不另建 provider**，而把合并后的 catalog
  经一个薄的 `OverrideProvider`（包装原 provider、`builtinModels()` 返回合并 catalog、
  `createApi` 转发）注册；未命中 ⇒ 维持现有 `buildProviders()` 路径（自定义新 provider）。
- **headers 到请求面**：合并后的 `ModelInfo.headers` 需要真正出站 ⇒ 在
  `DefaultProviders.streamBlocking` 组 `StreamRequest` 前，把 `modelInfo.headers()` 写入
  `ApiOptions.extra["headers"]`；`AbstractChatApi` 出口（三条 SDK 车道的 client builder）
  读取并 `putHeader`/HttpOptions。⚠️ **streaming 任意头限制（B140）**：default headers
  在 OpenAI/Anthropic SDK 的 **client builder** 上是否对 streaming 生效需实测
  （B140 测的是 params additionalHeaders/transport interceptor，**没测 client builder
  default headers**）⇒ 若仍不生效，按 B140 口径登记、反向断言，不强行改。
- **per-model baseUrl**：client 每请求新建已支持（OpenAICompletionsApi 构造器读
  options.baseUrl）；车道在请求期按 modelInfo 的 baseUrl 建 client（新增 extra 通道
  `"baseUrl"`，与 headers 同一出口）。

### Step 5 — 拆分 `ModelsJsonConfig`（530 行 ⇒ ≤500）

`compatOf` ＋ 其 6 个 helper（cacheControlFormatOf/thinkingFormatOf/chatTemplateValuesOf/
kwargValueOf/thinkingTokenBudgetFieldOf/maxTokensFieldOf，约 200 行）抽到新
`ModelsJsonCompat`；`ModelsJsonConfig` 留加载/校验/toModelInfo/pricing/thinking map。

**不做**：oauth "radius"、authHeader、extension 层（Java 无）；`vercelGatewayRouting`
（无组件）；为新 provider 开门。

---

## 4. 待裁决点（实施前）

- **R1 新增 models[] 的 api 缺省**：pi 在「三源皆无」时**抛错**；Java 内置模型的 api
  多为 null（provider 默认驱动）。建议：**命中内置 provider 且新增模型无 api ⇒
  不抛**，走 provider 默认协议（与现有 java 形状一致；pi 抛错是因为它要求显式）。
  备选：严格照 pi 抛。⚠️ 倾向建议前者（减少发明、对用户宽松）。
- **R2 headers client-builder 实测**：若 streaming 仍不透传，建议照 B140 接受差异、
  登记 B142；合并/catalog 层照常。
- **R3 modelOverrides.promptCache**：Java 的 `ModelInfo` **无 promptCache 组件**
  （CacheWarmer 整条未移植）⇒ 建议该 JSON 键**解析但忽略**并登记，不为此加组件。
- **R4 OverrideProvider 形状**：建议用包装（不修改内置 provider 单例）；备选直接替换注册。
  倾向包装（可还原、无副作用）。

---

## 5. 测试计划（RED 先）

观测面统一 `RecordingHttpServer`（路径＋头＋体）；`PI_JAVA_CODING_AGENT_DIR` 指向临时目录
加载 models.json。

1. **provider 级 baseUrl 叠加**：models.json `{"openai":{"baseUrl":X}}` 无 models ⇒
   内置 gpt 模型请求打到 X（旧实现 warning 吞掉、仍打官方 ⇒ 红）。
2. **models[] 同 id 替换不丢其它模型**：内置 provider 仍能列出未被替换的其它 gpt；
   被替换的走新参数（旧实现整 provider 替换 ⇒ 红）。
3. **per-model baseUrl（B136）**：被替换模型请求打到 model 级 URL（旧实现 ⇒ 红）。
4. **headers**：合并后请求带 `X-Test`（三层覆盖次序各一条；若 R2 证 streaming 不可达
   ⇒ 改反向断言并登记）。
5. **modelOverrides**：只给 `contextWindow` ⇒ 该模型窗口变、其余字段不动；compat 深合并
   一条（chatTemplateKwargs 键级）。
6. **api 继承（R1）**：替换内置模型不给 api ⇒ 仍走内置原车道；provider 级 `api`
   能覆盖；显式 per-model api 最高。
7. **校验**：provider 条目全空（只有 name）⇒ 响亮抛；只给 headers ⇒ 不抛（§1.7）。

Mutation：去掉 provider 级 baseUrl 叠加 ⇒ 1 红；headers 三层去掉一层 ⇒ 对应红；
modelOverrides 改道 models[] 之前 ⇒ 覆盖失效红。

**回归**：`pi-java-ai` 全模块；`coding-agent`（DefaultProviders）；全 reactor。

---

## 6. 风险

- 合并层是纯函数、不碰凭据 ⇒ 风险集中在接线（OverrideProvider/extra 通道）。
- 无真实 key ⇒ 形状照 pi ＋ wire 夹具，与各包同口径。
- `ModelsJsonConfig` 拆分属移动不改行为，须同包测试全绿护航。

---

## 12. 实施记录

**2026-10-02 闭环，`cbe1f76`（15 files, +1271/−256）。R1–R4 全按建议。**

- **实现调整（比设计更干净的一处）**：`baseUrl` 没走 extra 通道，而是给
  `ModelInfo` 加了一等组件 `baseUrl`（13 canonical 组件，旧 12 组件降级便捷
  构造、存量零改签）——与 D3 加 `api` 同一手法。
- **Step 1 schema**：`ProviderDef` 增 `headers/modelOverrides/compat`；新增
  `ModelOverrideDef`（11 可空组件）。
- **Step 2 `ModelJsonMerge`（新，256 行）**：Phase A provider 级
  baseUrl/compat/headers 叠加每个内置模型 → Phase B models[] upsert（R1：
  model/provider api 皆缺席时继承被替换模型 api）→ Phase C modelOverrides
  最后逐字段覆盖 → headers 三层最终合成（models[] 同键最后赢）。
- **Step 3 compat 合并**：raw 两层合并（`mergeRawCompat`）＋已归一层叠加
  （`mergeNormalizedCompat`），嵌套 map（openRouterRouting/chatTemplateKwargs/
  chatTemplateArgs）键级合并。
- **Step 4 接线**：`DefaultProviders.registerModelsJsonProviders` 命中内置 id ⇒
  `OverrideProvider` 包装注册（R4，内置单例不动），未命中 ⇒ 独立 provider；
  `withPerModelOverrides` 把 per-model baseUrl/headers 投影到请求选项；
  `putExtraHeaders` 在三条 SDK 车道经 client-builder default headers 出站。
- **Step 5 拆分**：`ModelsJsonConfig` 733 ⇒ **330** 行；compat 层
  （`ModelsJsonCompat` 新，431 行）独立。三个文件均 ≤500。
- **R2 实测结论**：client-builder default headers **对 streaming 生效**
  （`mergedHeadersAreSentOnTheStreamingRequest` 真线 X-Test=yes）⇒ 不登记 B142；
  B140 的限制仅限 params additionalHeaders／transport interceptor。
- **R3**：`modelOverrides.promptCache` 解析但忽略（ModelInfo 无该组件，
  CacheWarmer 未移植），未单独登记（沿用 B106 口径）。
- **旧测试更新 1 处**：`ModelsJsonConfigTest.perModelApiIsCarriedOntoModelInfo`
  —— provider 级 api 现照 pi 三源下发（旧断言 plain.api 为 null）。
- **Mutation**：M-A 去 provider baseUrl 叠加 ⇒ 2 红；M-B 跳过 models[]
  headers 合成 ⇒ 1 红；均恢复复绿。
- **回归**：pi-java-ai **1231/1231**、coding-agent **290/290**（checkstyle 0）。
  **B102、B136 随包结案**；未跑全 reactor verify（tui/evals/web 无调用面）。
