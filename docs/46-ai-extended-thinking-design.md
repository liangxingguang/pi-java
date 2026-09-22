# 46 - 包H5：扩展思考打通（B15）

> 状态：**设计待审**（2026-09-23）。本文档只落设计，不含实现。
> 上游：`docs/41 §7.2` 第一梯队 H5（「B15 扩展思考打通」）；`docs/32` 台账 **B15**；
> `docs/31 §8.34.4 决策 5`（投送链，已由包② 落地）。
> pi 锚点：`3390bd936`。**一切 pi 取证走 `git show 3390bd936:<path>`，不读工作树。**

---

## 1. 这一包解决什么

**用户可观察的缺陷**：`pi-java --thinking high` **在生产上完全无效**。

`--thinking` 的值一路传到了 `StreamOptions.thinking()`，但它在 `ThinkingLevelMap` 上被翻译时，
那张表**永远是空的**（7 个生产构造点全部 `ThinkingLevelMap.empty()`）⇒ `forLevel` 恒返回
`ThinkingConfig.OFF` ⇒ `DefaultProviders:115` 的门恒假 ⇒ `extra` 里永无 `thinking.budgetTokens`
⇒ `AnthropicMessagesApi:482` 取不到值 ⇒ **请求里从来没有 `thinking` 参数**。

同一根因还让另外两件事静默失效：

| 现象 | 实测 |
|---|---|
| `settings.defaultThinkingLevel` 改了没用 | 该设置只被 TUI/Web 读（`SettingsScreen:38`／`WebDispatcher:254`），**从不进请求路径** |
| `PromptConfig.thinkingLevel` 改了没用 | 每个 `processPrompt` 生产调用点都传 `PromptConfig.defaults()`（`thinkingLevel == null`）⇒ `AgentSession:599` 的分支从不触发 |
| `AgentSessionEvent.ThinkingLevelChanged` 事件 | 声明了（`:76`）、消费了（`JsonEventMapper:108`），**零生产者** |

**根因不是「少接一根线」，是「形状不对」**：java 的 `ThinkingLevelMap` 是 **Phase 2a 的发明**，
pi 从未有过这个形状（见 §2.1 / §3-D1）。投送链（`StreamRequest` 带整个 `ModelInfo`）已由包② 修好，
所以本包**只剩「生产侧构造 + 翻译层」这半边** —— 但这一半必须先把形状摆正。

---

## 2. 命题表

### 2.1 pi 侧（行为）

全部取自 `3390bd936`。

| # | 命题 | 出处 |
|---|---|---|
| P1 | `ThinkingLevel = "minimal"\|"low"\|"medium"\|"high"\|"xhigh"\|"max"`（**6 级**） | `types.ts:84` |
| P2 | `ModelThinkingLevel = "off" \| ThinkingLevel` | `types.ts:85` |
| P3 | **`ThinkingLevelMap = Partial<Record<ModelThinkingLevel, string \| null>>`** —— 值是**字符串**（provider 的 effort 名）或 `null`（显式不支持） | `types.ts:86` |
| P4 | `ThinkingBudgets = { minimal?, low?, medium?, high? }`（**只有 4 级**，无 xhigh/max） | `types.ts:101-107` |
| P5 | `SimpleStreamOptions` 带 `reasoning?: ThinkingLevel` ＋ `thinkingBudgets?: ThinkingBudgets`；**车道拿到的是一级 `ThinkingLevel`，翻译在车道内做** | `types.ts:325-336` |
| P6 | `EXTENDED_THINKING_LEVELS = ["off","minimal","low","medium","high","xhigh","max"]`（**含 off**） | `models.ts:922` |
| P7 | `getSupportedThinkingLevels(model)`：`!model.reasoning ⇒ ["off"]`；否则过滤 —— 映射值 `=== null` ⇒ 排除；`xhigh`/`max` ⇒ 仅当映射值 `!== undefined`（**opt-in**）；其余 ⇒ 保留 | `models.ts:924-933` |
| P8 | `clampThinkingLevel(model, level)`：可用集里没有 ⇒ **先向上、再向下**找；都不行 ⇒ `availableLevels[0] ?? "off"` | `models.ts:935-955` |
| P9 | `clampReasoning(effort)`：`xhigh`/`max` ⇒ `"high"` | `simple-options.ts:64-66` |
| P10 | `DEFAULT_THINKING_BUDGETS = {minimal:1024, low:2048, medium:8192, high:16384}` | `simple-options.ts:57-62` |
| P11 | `thinkingBudgetForLevel(level, custom)` = `{...DEFAULT, ...custom}[clampReasoning(level)]` | `simple-options.ts:68-72` |
| P12 | `MIN_ANSWER_TOKENS = 1024`；`clampThinkingBudgetToAnswerRoom(budget, ceiling)` = `min(budget, max(0, ceiling - 1024))` | `simple-options.ts:55, 75-77` |
| P13 | `adjustMaxTokensForThinking(base\|undefined, modelMax, level, custom)`：`budget = thinkingBudgetForLevel(...)`；`maxTokens = base === undefined ? modelMax : min(base + budget, modelMax)`；**若 `maxTokens <= budget`** ⇒ `budget = clampThinkingBudgetToAnswerRoom(budget, maxTokens)` | `simple-options.ts:79-94` |
| P14 | Anthropic 车道的 `streamSimple`：无 `reasoning` ⇒ `thinkingEnabled:false`；`compat.forceAdaptiveThinking === true` ⇒ `{thinkingEnabled:true, effort: mapThinkingLevelToEffort(model, reasoning)}`；否则 ⇒ `adjustMaxTokensForThinking(...)` → `clampMaxTokensToContext(...)` → `{thinkingEnabled:true, thinkingBudgetTokens: min(adjusted.thinkingBudget, max(0, maxTokens - 1024))}` | `anthropic-messages.ts:858-904` |
| P15 | `mapThinkingLevelToEffort(model, level)`：`model.thinkingLevelMap?.[level]` 是字符串 ⇒ 直接用它；否则 switch：`minimal`/`low`⇒`"low"`、`medium`⇒`"medium"`、`high`⇒`"high"`、**default⇒`"high"`** | `anthropic-messages.ts:838-856` |
| P16 | Anthropic 请求构建**三分支**：`supportsMidConvoEffort` ⇒ `{type:"adaptive", display, block_binding:{prefix_mismatch_behavior:"drop_block"}}` ＋ `output_config={effort:"high"}`；否则 `model.reasoning` 为真时 —— `thinkingEnabled` ⇒ `forceAdaptiveThinking ? {type:"adaptive", display} (+ output_config={effort}) : {type:"enabled", budget_tokens: thinkingBudgetTokens \|\| 1024, display}`；**`thinkingEnabled === false && thinkingLevelMap?.off !== null`** ⇒ `{type:"disabled"}` | `anthropic-messages.ts:1152-1180` |
| P17 | `display` 默认 `"summarized"` | `anthropic-messages.ts:1167` |
| P18 | `clampThinkingLevel` 在 **8 条车道**被调用（azure / google-generative-ai / google-shared / google-vertex / mistral / codex / completions / responses）；**Anthropic 不在内**（它走 `mapThinkingLevelToEffort`） | `git grep clampThinkingLevel` |
| P19 | 用户入口在 coding-agent：`getAvailableThinkingLevels()` = `getSupportedThinkingLevels(model)`；`supportsThinking()` = `!!model.reasoning`；`setThinkingLevel` 先 clamp、再落 `agent.state.thinkingLevel`、变了才写 transcript ＋ 发 `thinking_level_changed` ＋ 发扩展事件 `thinking_level_select` | `agent-session.ts:1934-1976` |
| P20 | 切模型的级别解析顺序：显式值 → `settings.getModelThinkingLevel(provider, id)` → `settings.getDefaultThinkingLevel() ?? 当前 ?? DEFAULT_THINKING_LEVEL` | `agent-session.ts:1992-2005` |
| P21 | **`thinkingLevelMap` 与 `forceAdaptiveThinking` 的数据不在仓库里** —— 由 `scripts/generate-models.ts` 从 **models.dev** 生成，落在 `src/providers/data/*.json`，而该目录**被 `.gitignore`** | `.gitignore`：`packages/ai/src/providers/data/` |
| P22 | effort 型模型的映射由 `getEffortThinkingLevelMap(options)` 推导：`{off: 支持"none" ? "none" : null}` ＋ 6 级各自 `supported.has(level) ? level : null` | `scripts/models-dev-reasoning-options.ts` |
| P23 | 其余映射来自**大表手写覆盖**（按模型 id 子串）：`opus-4.8/opus-5/sonnet-5` ⇒ `{xhigh:"xhigh", max:"max"}`；`fable-5` ⇒ `{off:null, xhigh:"xhigh", max:"max"}`；`isAnthropicAdaptiveThinkingModel(id)` ⇒ `forceAdaptiveThinking:true` …… | `generate-models.ts:1024-1100` 等 |
| P24 | **温度与思考互斥**：`options.temperature !== undefined && !options.thinkingEnabled && compat.supportsMidConvoEffort !== true && compat.supportsTemperature` 才写 `params.temperature` ⇒ **`thinkingEnabled` 一开，`temperature` 就不发** | `anthropic-messages.ts:1104-1112` |
| P25 | interleaved-thinking beta 头：`model.reasoning && thinkingEnabled === true && (interleavedThinking ?? true) && forceAdaptiveThinking !== true` ⇒ push `"interleaved-thinking-2025-05-14"` | `anthropic-messages.ts:1018-1025`、常量 `:182` |
| P26 | `AnthropicEffort = "low"\|"medium"\|"high"\|"xhigh"\|"max"`；`AnthropicThinkingDisplay = "summarized"\|"omitted"` | `anthropic-messages.ts:177, 180` |
| P27 | `providerThinkingLevel` 只在 `supportsMidConvoEffort` 时进 `AssistantMessage`（`options?.effort ?? "high"`） | `anthropic-messages.ts:521, 528` |
| P28 | **映射表的真实样本**（生成器里的字面常量）：`DEEPSEEK_V4 = {minimal:null, low:null, medium:null, high:"high", max:"max"}`；`Gemma 4 = {off:null, minimal:"MINIMAL", low:null, medium:null, high:"HIGH"}`；`gpt-6-astra = {off:null, minimal:null, low:"low", medium:"medium", high:"high", xhigh:"xhigh", max:"max"}`；`Together gpt-oss = {off:null, minimal:null}`；`GitHub Copilot Claude = {"claude-sonnet-4.6": {minimal:"low", max:"max"}}` | `generate-models.ts:206-220, 285-296, 477-482, 957-963, 975-986` |
| P29 | **pi 自己的用户配置也有这个键**：`ThinkingLevelMapSchema`（coding-agent 的 `model-config.ts:56`，用于 `ModelDefinitionSchema:178` 与 `ModelOverrideSchema:192`） | `packages/coding-agent/src/core/model-config.ts:56` |
| P30 | **harness 路径不传 `thinkingBudgets`**：`AgentHarnessStreamOptions`（`harness/types.ts:130-146`）没有该字段，`createRequestOptions`（`harness/execution/assistant.ts:69-97`）因此不转发 ⇒ 该路径下 token 型车道**一律回落 `DEFAULT_THINKING_BUDGETS`**；legacy 路径才带（`agent.ts:470`） | 见左 |
| P31 | `reasoning` **不经过** `buildBaseOptions`（`simple-options.ts:21-48` 不转发它）⇒ 它**只走车道专属字段**，不进共享选项袋 | `simple-options.ts:21-48` |

### 2.2 pi-java 侧（现状）

| # | 现状 | 出处 |
|---|---|---|
| J1 | `ThinkingLevelMap = Map<ThinkingLevel, ThinkingConfig>` —— **`ThinkingLevel` 作键（无 `off`）、值是配置对象** | `thinking/ThinkingLevelMap.java:14-15` |
| J2 | `ThinkingLevel` 只有 **5 级**（`Minimal…XHigh`），**无 `Max`** | `thinking/ThinkingLevel.java:17-21` |
| J3 | `ThinkingConfig(enabled, OptionalInt budgetTokens, Optional<String> effort)` | `thinking/ThinkingConfig.java:17-21` |
| J4 | `forLevel`：`Off ⇒ OFF`；`Enabled ⇒ clamp(e.level(), levelMap.keySet())` 后 `getOrDefault(..., OFF)` | `ThinkingLevelMap.java:26-34` |
| J5 | **7 个生产构造点全部 `empty()`**：`ModelInfo:46,66,97,154`、`CatalogModel:41`、`ModelsJsonConfig:209`、`HarnessConfig:107,159` | 实测 grep |
| J6 | `ThinkingLevelMap.of(...)`、`ThinkingConfig.withBudget(...)` **零生产调用者**；`withEffort` 连测试都没有 | 实测 grep |
| J7 | `HarnessConfig.Builder.thinkingLevelMap(...)` **零生产调用者**（3 处测试，全传 `empty()`） | 实测 grep |
| J8 | `--thinking` → `ThinkingLevels.parse` → `SessionSetup.thinkingLevelFor` → `AgentSession:395` → `HarnessConfig:172` → `AgentHarness:99` → `ExecutionContext:37` → `PiLaneEngine:278` → `PiLoop.Config.thinking` → `PiLoopRunner:376` `forLevel` → `StreamOptions.thinking` → `DefaultProviders:114` | 实测链路 |
| J9 | `DefaultProviders:115-117`：`thinking.enabled() && budgetTokens().isPresent()` ⇒ `extra.put("thinking.budgetTokens", …)` —— 门**恒假** | 实测 |
| J10 | `AnthropicMessagesApi:478-489`：唯一读 `request.extra()` 的地方；只发 `ofEnabled(budgetTokens)`，**无 `adaptive`、无 `disabled`、无 `display`、无 `output_config`** | 实测 |
| J11 | `ThinkingLevels.parse` 把 `"max"` **并进 `XHigh`**（label `"xhigh"`） | `cli/ThinkingLevels.java:31` |
| J12 | `ModelInfo` **无 `reasoning` 组件**；对应物是 `ModelCapability.THINKING`（由 `models.json` 的 `reasoning:true` 置位） | `ModelInfo.java:29-41`、`ModelsJsonConfig:194-195` |
| J13 | `ModelCompat` 只有 3 个旗标（`allowEmptySignature`／`requiresReasoningContentOnAssistantMessages`／`supportsFinishReason`），**无 `forceAdaptiveThinking`／`supportsMidConvoEffort`** | `catalog/ModelCompat.java` |
| J14 | `CatalogModel`（目录 wire DTO）**双向丢弃 `thinkingLevelMap`** ⇒ 远程目录与 `FileModelsStore` 结构上装不了这张表 | `CatalogModel.java:41,52` |
| J15 | `ModelsJsonSchema` **没有 `thinkingLevelMap` 键** ⇒ 用户在 models.json 里写它会被静默吞掉（与 B8 的 `compat` 同病） | `provider/ModelsJsonSchema.java` |
| J16 | `settings.defaultThinkingLevel` 只被 TUI/Web 读写，**不进请求路径** | 实测 grep |
| J17 | `AgentSessionEvent.ThinkingLevelChanged` 有声明、有消费者、**零生产者** | `AgentSessionEvent:76`、`JsonEventMapper:108` |
| J18 | `PromptConfig.thinkingLevel` 生产调用点全传 `defaults()`（`null`） | `AgentSession:599-601` |
| J19 | `ResponsesOptions` 已有 `ThinkingLevel reasoningEffort` 组件、`ResponsesMessageConverter:326-334` 有 `effortString(ThinkingLevel)` 硬编码 switch —— **responses 车道自有一条平行路径，不走这张表** | 实测 |

---

## 3. 设计决策

### D1 —— `ThinkingLevelMap` **照 pi 重塑**（推荐，见 §8 ①）

**pi 的形状**（P3）：`Partial<Record<ModelThinkingLevel, string | null>>` —— 一张 **level → provider effort 名**的
**命名/可用性表**；「到底发 adaptive 还是 budget」由**车道**按 `compat` 旗标 ＋ `thinkingEnabled` 决定（P14/P16）。
**java 的形状**（J1）把这两件事揉成了一个对象，于是**结构上表达不出**：

- `off` 这个键（pi 有 **5 条车道**读 `thinkingLevelMap?.off !== null` 来决定是否发显式「关闭」）
- 「键缺席」（默认支持）与「值为 `null`」（**显式不支持**）的**三态**区别 —— `Map.copyOf` 还不允许 null 值
- `xhigh`/`max` 的 **opt-in** 语义（P7）
- 非 Anthropic 车道的 effort **字符串**

> ⚠️ **这不是「pi 删掉的，pi-java 也删」**（见 `[[pi-deleted-then-drop]]`）：pi 的这个形状**自 `80f06d363`
> （2026-05-02）引入时就是字符串映射**，其前身 `reasoningEffortMap?: Partial<Record<ThinkingLevel, string>>`
> **也是字符串** ⇒ **pi 在全部历史里从未有过「level → 配置对象」这种形状**。java 的包建于 `44659c1`
> （2026-08-11，晚三个月），出自 `docs/07-phase2a §3.2`，那份设计只对齐了**名字**。
>
> **取证**：`git log -S "ThinkingLevelMap" -- packages/ai/src/types.ts` 只返回 `80f06d363` 一个提交，
> 逐个回放该提交的 `types.ts` 全部是 `Partial<Record<ModelThinkingLevel, string | null>>` —— **定义一次写成，从未改过**。
>
> ⚠️ **另一个易混点**：pi 里**确实有**一个叫 `ThinkingConfig` 的类型，但那是 **Google SDK 的**
> （`@google/genai`，带 `includeThoughts`，见 `google-shared.ts:11,102`），**与思考级别表无关**。
> java 的 `com.pijava.ai.thinking.ThinkingConfig` 是**同名异物** —— 重塑时值得顺带改名（见 §8 ①的备注）。

**Java 方言**：`Map<ModelThinkingLevel, Optional<String>>` —— 三态齐备且无 null：
**键缺席** ≙ pi 的 `undefined`；**`Optional.empty()`** ≙ pi 的 `null`；**`Optional.of(s)`** ≙ pi 的字符串。

**`ThinkingConfig` 的去留**：**保留但改职**。pi 侧其实是**两个不同的东西** ——
`Model.thinkingLevelMap`（目录数据）与 `AnthropicOptions.thinkingEnabled/thinkingBudgetTokens/effort/thinkingDisplay`
（**请求选项**）。java 把两者揉进了一个 `ThinkingConfig`。重塑后：
`ThinkingLevelMap` 只管数据，`ThinkingConfig` 只管**车道请求选项**，组件对齐 pi 的四个字段。

### D2 —— 范围：**只做 Anthropic 车道 ＋ 共享机制**（推荐，见 §8 ②）

本包落地：`models.ts:922-953` 的两个函数（P6–P8）、`simple-options.ts` 的五个纯函数（P9–P13）、
`mapThinkingLevelToEffort`（P15）、Anthropic 的**三分支**请求构建（P16–P17）。

**其余 7 条车道（P18）不在本包** —— 它们各自还牵扯 `compat.thinkingFormat`（**10 种形状**，`docs/41 §1.2`
单列的权重 2 条目）与 `thinking_token_budget` 类字段名。**另立一包**（`docs/41 §1.2` 已单列）。

### D3 —— `compat` 加 `forceAdaptiveThinking`（本包）／`supportsMidConvoEffort` **不做**（推荐，见 §8 ④）

`forceAdaptiveThinking` 是 P14/P16 的**分支开关**，不加则 adaptive 路径整条不存在 ⇒ 本包加。

`supportsMidConvoEffort` 那条分支要写 `thinking.block_binding = {prefix_mismatch_behavior: "drop_block"}`。
**实测（javap `anthropic-java-core:2.52.0`）**：

| 需要的 SDK 面 | 实测结果 |
|---|---|
| `ThinkingConfigParam.ofEnabled/ofDisabled/**ofAdaptive**` | ✅ 三态齐备 |
| `ThinkingConfigEnabled.builder().budgetTokens(long).display(Display)` | ✅ |
| `ThinkingConfigAdaptive.builder().display(Display)` | ✅（`Display = SUMMARIZED \| OMITTED`） |
| `MessageCreateParams.Builder.outputConfig(OutputConfig)`；`OutputConfig.Effort = LOW\|MEDIUM\|HIGH\|XHIGH\|MAX` | ✅ |
| **`block_binding`** | ❌ **jar 里没有任何 `BlockBinding` 类型**（`unzip -l \| grep -i blockbinding` 零命中） |

⇒ `supportsMidConvoEffort` 只能靠 `putAdditionalProperty("block_binding", JsonValue.from(Map.of(...)))` **硬写**。
**建议不做**（登记）：它是 pi 最新的 beta 面（`thinking-binding-controls-2026-08-01`），
**且该旗标的值同样来自仓库外的生成数据**（P21）⇒ 在 pi-java 里**今天必然不可达**。

### D3b —— 温度抑制 ＋ interleaved beta：**本包做**（推荐，见 §8 ⑦）

两处都是**思考开关的连带线格后果**，且今天 java **两侧都不存在**：

| 项 | pi（P24/P25） | pi-java 现状 |
|---|---|---|
| `temperature` | `thinkingEnabled` 为真 ⇒ **不发** | `AnthropicMessagesApi:474-475`：`temperature() >= 0` 就发，**无思考门** |
| interleaved beta 头 | `reasoning && thinkingEnabled && !forceAdaptiveThinking` ⇒ push | **零 beta 头**（该文件里 `beta` 一字不见） |

⇒ **不移植就等于「开了思考还带着 temperature」**，是 provider 侧真实的参数冲突。
**建议做**：温度抑制一行门；beta 头按 P25 的字面条件 push。
⚠️ 两处都**只在 `thinkingEnabled` 为真时可观察** ⇒ 夹具必须先把思考打开，否则恒绿（见 §5 步 6）。

### D4 —— `maxTokens` 侧：**`adjustMaxTokensForThinking` 做，`clampMaxTokensToContext` 不做**（推荐，见 §8 ③）

- `adjustMaxTokensForThinking`（P13）是**纯函数** ⇒ 逐字移植。
- `clampMaxTokensToContext`（`simple-options.ts:15-19`）**签名要 `TranscriptContext`**，依赖
  `normalizeContext` ＋ `estimateContextTokens` —— 那是 `docs/41 §1.1` 单列的**三条权重 3 缺口**
  （`SystemMessage` / `normalizeContext` / `TranscriptContext`）。**拉进来会让本包膨胀数倍**。

**代价（必须登记，不能含糊）**：pi 的 `max_tokens` 被上下文钳制，java 不会 ⇒
**同一会话在长上下文下发出的 `max_tokens` 与 pi 不同**。这是**已知偏差**，不是「等价实现」。

### D5 —— 数据面：**schema 开键 ＋ 内置目录不编**（推荐，见 §8 ⑤）

照 **H1 裁决 B 的先例**（「未知表未知，**不编假价**」，`docs/42 §10`）与 **B8 的 `compat` 先例**（决策 2：
「静默吞掉的根治办法就是让 `compat` 成为已知键」）：

1. `ModelsJsonSchema` 加 `thinkingLevelMap` 键（用户可写）——**根治 J15 的静默吞掉**。
   ✅ **这条有 pi 侧的直接依据**：pi 的**用户配置 schema 里本来就有这个键**
   （`ThinkingLevelMapSchema`，`model-config.ts:56`；被 `ModelDefinitionSchema:178` 与
   `ModelOverrideSchema:192` 引用 —— P29）⇒ 不是我们发明的接口，是**照抄 pi 的用户面**。
2. `CatalogModel`（wire DTO）**补 `thinkingLevelMap` 字段** —— 否则远程目录与 `FileModelsStore`
   结构上装不了这张表（J14），第 1 条只对本地 models.json 有效。
3. **内置目录不编这张表**。理由：pi 的数据**不在仓库里**（P21），无法逐条取证；凭印象编 = 编假数据。
   后果**如实登记**：内置模型上 `--thinking` 仍只走**默认可用集**（`off..high`）与 P15 的 switch 回退。

> ⚠️ **连带后果，必须在验收里写死**：`forceAdaptiveThinking`（D3）的值同样来自仓库外的数据 ⇒
> **adaptive 分支在内置目录上不可达**。这与 H2/B84 的「车道侧能力门不可达」是**同一类现象的第 4 例**
> —— 但它**不是**共享闸先剥造成的，而是**数据缺席**造成的。登记时要把这两类分开写。

### D6 —— 用户入口：`defaultThinkingLevel` 接线 ＋ `ThinkingLevelChanged` 生产者（推荐，见 §8 ⑥）

三件 J16/J17/J11 都是**本包根因的同族后果**（级别到不了请求 / 级别变化不外显），且都**改动很小**：

- `SessionSetup.thinkingLevelFor` 补读 `settings.defaultThinkingLevel`（J16）—— 对齐 P20 的
  「显式 → 模型级 → 全局默认」顺序中的**全局默认**那一档（模型级那档 `getModelThinkingLevel`
  在 pi-java 没有对应设置键，**登记不做**）。
- `AgentHarness.setThinkingLevel` 补发 `AgentSessionEvent.ThinkingLevelChanged`（J17）——
  对齐 P19 的 `thinking_level_changed`。
- `ThinkingLevels.parse` 拆出 `"max"`（J11）—— 否则 P1/P6 的 6 级刻度无法表达。

### D7 —— 不做（登记，不在本包）

见 §9。

---

## 4. 关键签名（完整 Java，供审核）

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/thinking/ThinkingLevel.java ──
/** pi types.ts:84。**新增 Max** ⇒ 6 级（J2 的缺口）。 */
public sealed interface ThinkingLevel {
    record Minimal() implements ThinkingLevel {}
    record Low()     implements ThinkingLevel {}
    record Medium()  implements ThinkingLevel {}
    record High()    implements ThinkingLevel {}
    record XHigh()   implements ThinkingLevel {}
    record Max()     implements ThinkingLevel {}   // ← 新增

    /** label 与 pi 字面量逐字一致：minimal/low/medium/high/xhigh/max。 */
    default String label() { return getClass().getSimpleName().toLowerCase(Locale.ROOT); }

    /** pi models.ts:922 的 EXTENDED_THINKING_LEVELS 去掉 off 的那 6 项，顺序照抄。 */
    static List<ThinkingLevel> ordered() { /* Minimal..Max */ }

    /** 从 pi 字面量解析（供 schema / CLI 共用）。 */
    static Optional<ThinkingLevel> parse(String raw) { /* "max" ⇒ Max，不再并进 XHigh */ }
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/thinking/ModelThinkingLevel.java ──
public sealed interface ModelThinkingLevel {
    record Off() implements ModelThinkingLevel {}
    record Enabled(ThinkingLevel level) implements ModelThinkingLevel {}

    static ModelThinkingLevel off() { return new Off(); }
    static ModelThinkingLevel of(ThinkingLevel level) { return new Enabled(level); }

    /** pi models.ts:922 的字面量表（**含 off**），顺序逐字照抄。 */
    static List<ModelThinkingLevel> extended() { /* off, minimal..max */ }

    /** pi models.ts:924-933 `getSupportedThinkingLevels` 的逐字移植。 */
    static List<ModelThinkingLevel> supported(ModelInfo model);

    /** pi models.ts:935-953 `clampThinkingLevel` 的逐字移植（**先向上、再向下**）。 */
    static ModelThinkingLevel clamp(ModelInfo model, ModelThinkingLevel level);

    /** pi 字面量 ↔ 本类型（wire / schema / CLI 共用）。 */
    static Optional<ModelThinkingLevel> parse(String raw);
    default String label() { /* off / minimal / … */ }
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/thinking/ThinkingLevelMap.java（**重塑**）──
/**
 * pi types.ts:86 `Partial<Record<ModelThinkingLevel, string | null>>`。
 *
 * <p><b>三态</b>（Java 方言，见 §3-D1）：<b>键缺席</b> ≙ pi 的 {@code undefined}（默认支持）；
 * <b>{@code Optional.empty()}</b> ≙ pi 的 {@code null}（<b>显式不支持</b>）；
 * <b>{@code Optional.of(s)}</b> ≙ pi 的字符串（该级的 provider effort 名）。</p>
 */
public record ThinkingLevelMap(Map<ModelThinkingLevel, Optional<String>> entries) {
    public ThinkingLevelMap { entries = Map.copyOf(entries); }

    /** pi 的 `map[level]` —— 缺席与显式 null 都返回 empty；**判「是否显式不支持」用 hasEntry**。 */
    public Optional<String> mapped(ModelThinkingLevel level) {
        return entries.getOrDefault(level, Optional.empty());
    }

    /** 键**在场**（pi 的 `level in map`）—— `xhigh`/`max` 的 opt-in 判据。 */
    public boolean hasEntry(ModelThinkingLevel level) { return entries.containsKey(level); }

    /** 键在场**且**值为 null —— pi 的 `map[level] === null`，P7/P16 的排除判据。 */
    public boolean explicitlyUnsupported(ModelThinkingLevel level) {
        return entries.containsKey(level) && entries.get(level).isEmpty();
    }

    /** pi 的 `thinkingLevelMap?.off !== null`（P16）：**缺席也算「支持 off」**。 */
    public boolean supportsExplicitOff() { return !explicitlyUnsupported(ModelThinkingLevel.off()); }

    public static ThinkingLevelMap empty() { return new ThinkingLevelMap(Map.of()); }
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/thinking/ThinkingConfig.java（**改职**）──
/**
 * 车道级思考请求选项 —— 对齐 pi {@code AnthropicOptions} 的四个 thinking 字段
 * （{@code anthropic-messages.ts:224-262}）。**不再**承担「目录数据」职责（那是
 * {@link ThinkingLevelMap}）—— 这是 J1 那个揉合的拆分。
 */
public record ThinkingConfig(
    Optional<Boolean> enabled,   // pi thinkingEnabled（**三态**：缺席 ≠ false，见 P16）
    OptionalInt budgetTokens,    // pi thinkingBudgetTokens
    Optional<String> effort,     // pi effort（adaptive 型）
    Optional<String> display     // pi thinkingDisplay，默认 "summarized"（P17）
) {
    public static final ThinkingConfig OFF = new ThinkingConfig(
        Optional.of(false), OptionalInt.empty(), Optional.empty(), Optional.empty());
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/thinking/ThinkingBudgets.java（**新增**）──
/** pi types.ts:101-107。**只有 4 级**（无 xhigh/max —— P4）。 */
public record ThinkingBudgets(
    OptionalInt minimal, OptionalInt low, OptionalInt medium, OptionalInt high) {

    /** pi simple-options.ts:57-62 `DEFAULT_THINKING_BUDGETS`。 */
    public static final ThinkingBudgets DEFAULT = new ThinkingBudgets(
        OptionalInt.of(1024), OptionalInt.of(2048), OptionalInt.of(8192), OptionalInt.of(16384));

    /** pi simple-options.ts:68-72 `thinkingBudgetForLevel`（含 `clampReasoning`，P9）。 */
    public int budgetFor(ThinkingLevel level);

    /** pi simple-options.ts:79-94 `adjustMaxTokensForThinking`（P13）—— 纯函数。 */
    public static Adjusted adjust(OptionalInt baseMaxTokens, int modelMaxTokens,
                                  ThinkingLevel level, ThinkingBudgets custom);
    /** pi 的 `{ maxTokens; thinkingBudget }`。 */
    record Adjusted(int maxTokens, int thinkingBudget) {}
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/protocol/AnthropicThinking.java（**新增**）──
/**
 * Anthropic 车道的思考翻译 —— 逐字移植 `anthropic-messages.ts:838-856`（effort）
 * 与 `:1152-1180`（请求三分支）。拆成独立类：`AnthropicMessagesApi` 已 500+ 行。
 */
final class AnthropicThinking {
    private AnthropicThinking() {}

    /** pi anthropic-messages.ts:838-853 `mapThinkingLevelToEffort`（P15）。 */
    static String mapLevelToEffort(ModelInfo model, ThinkingLevel level);

    /** pi anthropic-messages.ts:1152-1180（P16/P17）。缺席 = 不发 thinking 参数。 */
    static Optional<ThinkingConfigParam> resolve(ModelInfo model, ThinkingConfig options);
}
```

```java
// ── pi-java-ai/src/main/java/com/pijava/ai/api/StreamRequest.java（**加组件**）──
public record StreamRequest(
    ModelInfo model, String systemPrompt, List<Message> messages,
    List<ToolDefinition> tools, int maxTokens, double temperature,
    Map<String, Object> extra,
    Optional<ThinkingLevel> reasoning,          // ← 新增：pi SimpleStreamOptions.reasoning（P5）
    ThinkingBudgets thinkingBudgets             // ← 新增：pi thinkingBudgets（P5）
) {
    /** 7 参便捷构造器**保留**（`reasoning` 缺席、budgets 取 DEFAULT）⇒ 现有构造点零改签。 */
    public StreamRequest(ModelInfo model, String systemPrompt, List<Message> messages,
                         List<ToolDefinition> tools, int maxTokens, double temperature,
                         Map<String, Object> extra) {
        this(model, systemPrompt, messages, tools, maxTokens, temperature, extra,
             Optional.empty(), ThinkingBudgets.DEFAULT);
    }
}
```

> ⚠️ **`reasoning` 必须走类型化组件，不能塞 `extra`**：`docs/31 §8.34.4 决策 5` 已经否掉过
> `extra` 方案（「非类型化；且那个『同形先例』本身是休眠的」）。本包**顺手删掉**那个休眠先例
> （`DefaultProviders:115-117` 的 `thinking.budgetTokens`）。

```java
// ── pi-java-agent-core/.../harness/StreamOptions.java（**改组件**）──
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    Optional<ThinkingLevel> reasoning,   // ← 由 ThinkingConfig 改为**未翻译的级别**（P5）
    ThinkingBudgets thinkingBudgets      // ← 新增
) { /* defaults() 相应改 */ }
```

> **为什么把翻译从 `PiLoopRunner` 挪进车道**：pi 的 `streamSimple` 收到的就是**原始 `reasoning`**
> （P5），翻译在车道内做（P14/P15）。`PiLoopRunner:375-377` 的 `thinkingConfig(config)` 是**错层**
> —— 它在引擎里就把级别翻成了配置，而引擎**没有 `ModelInfo` 的 compat/车道上下文**。
> 本包删掉这个方法，`PiLoopRunner:201-203` 改为把 `config.thinking()` 原样投下去。

```java
// ── pi-java-ai/.../catalog/ModelCompat.java（**加一位**）──
public record ModelCompat(boolean allowEmptySignature,
                          Boolean requiresReasoningContentOnAssistantMessages,
                          boolean supportsFinishReason,
                          boolean forceAdaptiveThinking) { /* NONE 第三位仍 true、第四位 false */ }
```

```java
// ── pi-java-ai/.../provider/ModelsJsonSchema.java（**加键**，D5-1）──
/** pi Model.thinkingLevelMap。三态用 `String` ＋ 显式 `null` 表达；键集 = off..max。 */
record ThinkingLevelMapDef(Map<String, String> levels) {}   // 值 null ⇒ 显式不支持

// ── pi-java-ai/.../catalog/CatalogModel.java（**加字段**，D5-2）──
// 现有 9 组件后追加 thinkingLevelMap（可空）⇒ toModelInfo/fromModelInfo 双向保留。
```

---

## 5. 实施步骤（每步：先红 → 实现 → 变异探针 → 回归 → 一个提交）

| 步 | 内容 | 先红怎么造 |
|---|---|---|
| 1 | **形状重塑**：`ThinkingLevel` 加 `Max`；`ThinkingLevelMap` 改 `Map<ModelThinkingLevel, Optional<String>>`；`ThinkingConfig` 改四组件；`ThinkingBudgets` 新增 | 新类型先写夹具（`ThinkingLevelMapTest`：三态、`supportsExplicitOff`、`extended()` 顺序）⇒ 编译不过即红 |
| 2 | **两个枢轴函数**：`ModelThinkingLevel.supported` / `.clamp`（P7/P8） | 夹具**镜像 `max-thinking.test.ts`**（跨实现 oracle，见 §6）：普通 `reasoning` 模型 ⇒ `off..high`；`{xhigh:null,max:"max"}` ⇒ 空洞且 `clamp(xhigh)⇒max` |
| 3 | **预算纯函数**：`ThinkingBudgets.budgetFor` / `.adjust`（P9–P13） | 夹具钉 `adjust` 的**三条分支**：`base` 缺席 / `maxTokens <= budget` 的收缩 / 正常 |
| 4 | **`mapThinkingLevelToEffort`**（P15） | 夹具：有映射 ⇒ 用映射；无映射 ⇒ switch（`minimal`→`low`、`max`→`high`） |
| 5 | **Anthropic 三分支**（P16/P17）＋ `ModelCompat.forceAdaptiveThinking` | 线格夹具（`AnthropicThinkingTest`）：adaptive／enabled(budget)／disabled／**都不发**（无 reasoning） |
| 6 | **投送链改道**：`StreamRequest` ＋ `StreamOptions` 加组件、删 `extra` 休眠键、删 `PiLoopRunner.thinkingConfig` | 夹具：`--thinking high` ⇒ 线格出现 `thinking.type`；**先红**（今天恒无） |
| 7 | **温度抑制 ＋ interleaved beta**（D3b，P24/P25） | 夹具：**开了思考**的请求里**没有** `temperature`；`forceAdaptiveThinking` 时**没有** `interleaved-thinking` 头。⚠️ 不先开思考 ⇒ 恒绿 |
| 8 | **数据面**：`ModelsJsonSchema` 加键 ＋ `CatalogModel` 加字段（D5） | 夹具：models.json 写 `thinkingLevelMap` ⇒ 到得了 `ModelInfo`（今天被静默吞掉） |
| 9 | **入口三件**（D6）：`defaultThinkingLevel` 接线 ＋ `ThinkingLevelChanged` 生产者 ＋ `parse("max")` | 各一条夹具；`parse` 那条**先红**（今天 `"max"` ⇒ `XHigh`） |

⚠️ **步 6 的夹具必须先证「今天会红」**：`--thinking high` 的端到端请求里**没有** `thinking` 键。
这是本包**唯一**的「用户今天撞得到」证据，务必在实现前把红记下来。

---

## 6. 验收

1. **端到端**：`--thinking high` ⇒ Anthropic 请求体含 `thinking`，且**形状随模型分流**：
   `forceAdaptiveThinking` 模型 ⇒ `{type:"adaptive", display:"summarized"}` ＋ `output_config.effort`；
   其余 ⇒ `{type:"enabled", budget_tokens: <P13 算出的值>, display:"summarized"}`。
2. **零 reasoning ⇒ 不发 thinking 参数**（对齐 P14 的 `thinkingEnabled:false` 路径）。
3. `thinkingLevelMap.off === null` 的模型在 `Off` 时 ⇒ `{type:"disabled"}`（P16 第三分支）。
4. **开了思考的请求里没有 `temperature`**（P24）；**非 adaptive 的 reasoning 模型带
   `interleaved-thinking-2025-05-14` beta 头**（P25）、adaptive 模型**不带**。
4. `ModelThinkingLevel.supported/clamp` **逐条对上 pi 自己的测试**（`max-thinking.test.ts` 三个用例
   ＋ `reasoning-options.test.ts` 三个用例）—— **跨实现 oracle**，不自造夹具。
5. `mvn clean verify` 零错误零警告；`ai` ／ `agent-core` ／ `coding-agent` 全绿；L5 14/14。
6. 无 `@SuppressWarnings`、无 `System.out.println` 残留、触及的主源码 ≤ 500 行。

---

## 7. 遗留登记（本包预计产出）

| 编号 | 内容 |
|---|---|
| **B15-残留-1** | `clampMaxTokensToContext` 未移植 ⇒ **`max_tokens` 不被上下文钳制**（D4，已知偏差） |
| **B15-残留-2** | `supportsMidConvoEffort` ＋ `block_binding` 未做（SDK 无类型支持，D3） |
| **B15-残留-3** | 其余 **7 条车道**的思考翻译未做（D2）—— 含 `compat.thinkingFormat` 10 形状 |
| **B15-残留-4** | **adaptive 分支在内置目录上不可达**（数据缺席，D5）—— ⚠️ 与 H2/B84 的「共享闸先剥」**不同类**，登记时分开写 |
| **B15-残留-5** | `settings.getModelThinkingLevel(provider,id)`（P20 第二档）无对应设置键 ⇒ 不做 |
| **B15-残留-6** | `CatalogModel` 的 `thinkingLevelMap` 是否需要 JSON 嵌套 schema（当前 DTO 是扁平 9 列） |
| **B15-残留-7** | **其余 beta 头**未移植：pi 的 Anthropic 车道另有 5 个常量（`FINE_GRAINED_TOOL_STREAMING_BETA`／`SERVER_SIDE_FALLBACK_BETA`／`MID_CONVERSATION_OUTPUT_CONFIG_BETA`／`THINKING_BINDING_CONTROLS_BETA`／OAuth 两枚），pi-java **零 beta 头**；本包只落 interleaved 一枚（P25） |
| **B15-残留-8** | `providerThinkingLevel`（P27）随 `supportsMidConvoEffort` 一起不做 ⇒ `AssistantMessage.providerThinkingLevel` 仍无生产者（与 `docs/41 §1.3` 那条合并登记） |

---

## 8. 待裁决（七点，请审核时给结论）

### ① `ThinkingLevelMap` 形状：**照 pi 重塑**（推荐） vs 保留发明形状只接线

- **推荐重塑**。理由见 §3-D1：pi 的形状**从来**是字符串映射；java 的形状**结构上表达不出**
  `off` 键、三态、`xhigh/max` opt-in、非 Anthropic 的 effort 字符串。
- **反方（保留）**：改动面大（约 30 文件），且今天**用户还撞不到**这些差异（因为表是空的）。
  ⇒ 若你认为「先把最小闭环做出来」，可以只做 D2 的翻译层，形状另立一包。
- ⚠️ 但**保留形状就不能移植 P7/P8**（`supported`/`clamp` 的判据建立在三态上），
  也做不了 P16 的 `off` 分支 ⇒ 那时 H5 的产出要重新定义。

### ② 范围：**只做 Anthropic**（推荐） vs 含其余 7 条车道

- **推荐只做 Anthropic**：其余车道牵扯 `thinkingFormat` 10 形状（`docs/41 §1.2` 已单列的独立条目）。
- **反方**：`clampThinkingLevel` 在 8 条车道被调（P18），只做一条会让「级别裁剪」在其余车道仍缺失
  —— 但那**不影响**今天的用户（那些车道今天根本不发 thinking 参数）。

### ③ `clampMaxTokensToContext`：**不做、登记偏差**（推荐） vs 一并做

- **推荐不做**：它要 `TranscriptContext` ＋ `estimateContextTokens` ⇒ 会拉进 `docs/41 §1.1`
  的三条**权重 3** 缺口，本包膨胀数倍。
- **代价**：`max_tokens` 与 pi 不同（长上下文下）。**这是真偏差，必须写进验收与台账，不能含糊成「等价」。**
- **反方**：既然要「和 pi 表现一样」，`max_tokens` 不同就是不一样。

### ④ `supportsMidConvoEffort`：**不做、登记**（推荐） vs 用 `putAdditionalProperty` 硬写

- **推荐不做**：SDK 无 `BlockBinding` 类型（实测，§3-D3）；且该旗标的值来自仓库外数据 ⇒
  **在 pi-java 里今天必然不可达**。硬写等于加一条**没有生产消费者**的代码。
- **反方**：`putAdditionalProperty` 是可用的（B84 也用过 SDK 的非类型化通道）——
  但你若选它，请同时决定**谁来提供这个旗标**（D5 已裁定内置目录不编数据）。

### ⑤ 数据面：**schema 开键 ＋ 内置目录不编**（推荐） vs 内置目录也手编

- **推荐不编**：pi 的数据**不在仓库里**（P21，`providers/data/` 被 gitignore），无法逐条取证；
  凭印象编 = 编假数据，违背 H1 裁决 B 的先例。
- **反方**：不编 ⇒ 内置模型上 `--thinking` 只走**默认可用集**（`off..high`）＋ P15 的 switch 回退，
  **adaptive 分支不可达**。若你希望内置的 Claude 模型真的走 adaptive，就得手编（并承担「与 pi 不同步」）。

### ⑥ 入口三件（D6）：**一并做**（推荐） vs 另立小包

- **推荐一并做**：三处都是本包根因的同族后果，且改动都很小（一处读设置、一处发事件、一处拆字面量）。
- **反方**：它们分属 `coding-agent`，而 `docs/41 §7.2` 给 H5 划的模块是 `ai` ＋ `agent-core`。

### ⑦ 温度抑制与 interleaved beta（D3b）：**一并做**（推荐） vs 另立包

- **推荐一并做**：两处都只在思考打开时可观察，属于本包的**同一根线**；分开做会让「本包之后
  思考能开」这句话**不成立**（开了之后参数冲突仍在）。
- **反方**：beta 头是一整块 pi-java 至今零移植的面（`interleaved-thinking-2025-05-14`
  ＋ 另外四个 beta 常量），单独拉进来会带出「beta 头管理」这个更大的话题。

---

## 9. 不在本包范围

- 其余 **7 条车道**的思考翻译（含 `compat.thinkingFormat` 的 **11 条分支** —— 10 种具名形状 ＋ `openai` 默认）。
  实测各车道的落线字段（供下一包直接引用）：

  | 车道 | pi 落点 | 发的字段 |
  |---|---|---|
  | `openai-completions` | `:730-750`, `:870-978` | `reasoning_effort` ／ `reasoning:{effort}` ／ `thinking:{type}` ／ `enable_thinking` ／ `chat_template_kwargs`／`args` ／ `thinking:<string>` ／ `<thinkingTokenBudgetField>` |
  | `openai-responses` | `:343-358` | `reasoning:{effort,summary}` ＋ `include:["reasoning.encrypted_content"]` |
  | `azure-openai-responses` | `:326-341` | 同上 |
  | `openai-codex-responses` | `:582-597` | `reasoning:{effort,summary}` |
  | `google-generative-ai` | `:322-344` | `thinking:{enabled,level}` 或 `{enabled,budgetTokens}` |
  | `google-vertex` | `:328-352` | 同上 |
  | `bedrock-converse-stream` | `:538-577` | `reasoning` ＋ 重写 `thinkingBudgets[level]` |
  | `mistral-conversations` | `:200-212` | `promptMode:"reasoning"` ／ `reasoningEffort` |

  另有三条**只属于该车道**的 compat 键：`supportsReasoningEffort`（`:679`）、
  `thinkingTokenBudgetField`（`:726`）／`supportsThinkingTokenBudget`（`:728`）、
  `chatTemplateKwargs`（`:709`）／`chatTemplateArgs`（`:711`）。
- `clampMaxTokensToContext` ／ `normalizeContext` ／ `TranscriptContext` ／ `SystemMessage`（`docs/41 §1.1` 三条权重 3）。
- `sections` 分段 ／ `toolsAdded`/`toolsRemoved` 工具增量（`docs/41 §1.2`）。
- Anthropic 的 `cache_control` 提示缓存（`docs/41 §1.1` 另一条权重 3）。
- `ResponsesOptions` 那条**平行**的 `effortString` 路径（J19）—— 本包**不动**它，但会登记它与新翻译层的重复。

---

## 10. 待补取证（实施前必须闭合）

| # | 问题 | 怎么测 |
|---|---|---|
| 1 | `AnthropicMessagesApi` 当前行数、拆出 `AnthropicThinking` 后的落点 | `wc -l` |
| 2 | `ThinkingConfig` 改四组件后，`PayloadRecordingStreamFn:112-113` 读 `enabled()` 的那处怎么改 | 读源码 |
| 3 | `extra` 通道删掉 `thinking.budgetTokens` 后，`ApiOptions.extra()` 是否还有别的键 | grep |
| 4 | `anthropic-java-core` 的 `JsonValue.from(Map)` 能否承载 `block_binding` 嵌套（若裁决④选硬写） | 序列化探针 |
