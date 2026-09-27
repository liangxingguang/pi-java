# 57 - A-10：请求侧选项层与 `max_tokens`（pi `simple-options` 移植）设计

**状态：设计待审核（2026-09-27）**

> 本包裁决 `docs/48 §5` 的 **A-10** 行：「`simple-options` max-token/thinking budget 夹取」，
> 以及 `docs/53 §4.4` 归属表分给它的两个 compat 字段
> （`thinkingTokenBudgetField` / `supportsThinkingTokenBudget`）。
> 上游：`docs/41:77`、`docs/46 §3-D4`（当年**拒绝**移植 `clampMaxTokensToContext` 的记录）。
> pi 锚点：`3390bd93630965a12a0a1a5c36ce890ec22f7e1d`。**一切 pi 取证走
> `git show 3390bd936:<path>`，不读工作树。**
>
> ⚠️ **本包不是「补一个夹取」，是「补一个没有生产者的字段」。** 设计期实测：`maxTokens`
> 在这条路径上**从来没有被谁写过**，Anthropic 车道因此发一个**自家发明的字面量 `4096`**，
> 其余五条车道干脆**一个上限都不发**。详见 §4.2 —— 这比台账 `docs/41:77` 的
> 「不按 contextWindow 夹取」重得多，也**不是** B20 那一类「值映射错」。
>
> ⚠️ **`docs/46 §3-D4` 当年拒绝的理由已经消失**：它说「要 `TranscriptContext` ＋
> `estimateContextTokens` ⇒ 会拉进 `docs/41 §1.1` 三条权重 3 缺口（`SystemMessage` /
> `normalizeContext` / `TranscriptContext`）」—— **那三条已由 A1/A2 全部闭环**
> （`docs/48 §10`、`docs/49 §12`）。⇒「继续搁置」不再有依据。
>
> ⚠️ **A-09（`compat.thinkingFormat`）是下一包，不在本包。** 两包的边界见 §1.3：
> 本包落「请求侧选项从哪来」，A-09 落「思考开关长什么样」。**顺序是 A-10 先** —— 因为
> A-09 的 `chat-template` / `baseten` 两形态要算 `thinkingBudget`，而那个预算的**天花板**
> 正是本包要修出来的 `max_tokens`（§6 R8）。

**参考台账：** `docs/32`（B15-残留-1、B107、B98）、`docs/41 §1.1`/`:77`、`docs/53 §4.4`、`docs/46 §3-D4`
**参考设计：** `docs/49`（transcript 归一，本包的输入形状）、`docs/53`（compat 解析层，本包要扩它）
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`

---

## 1. 范围

### 1.1 做

1. **给 `maxTokens` 装上生产者**：缺席时回落模型自己的输出上限（pi
   `options?.maxTokens ?? model.maxTokens`，`simple-options.ts:34`）。
2. **移植 `clampMaxTokensToContext`**（`simple-options.ts:15-19`）——按「上下文窗口 −
   已估 token − 4096 安全余量」夹取。
3. **把 pi `utils/estimate.ts` 的估算器移植进 `pi-java-ai`** ——`clampMaxTokensToContext`
   要用它，而它必须住在 `ai`（§6 R3：模块方向不允许反向依赖）。
4. **给 `ModelInfo.samplingParams` 装上消费者**：按 pi `buildBaseOptions:27-30` 的合并
   语义落到三条 OpenAI 兼容车道的 body **最后**。
5. **两个 compat 字段 ＋ 它们的读点**：`thinkingTokenBudgetField` /
   `supportsThinkingTokenBudget` ⇒ `resolveThinkingTokenBudgetField`（`:1004-1010`）＋
   `resolveClampedThinkingBudget`（`:1012-1024`）＋ 落点（`:972-978`）。
6. **`supportsMaxOutputTokens`（仅 Responses）** ——本包**第一次**给 Responses 车道发
   `max_output_tokens`，而 pi 的 `openai-responses` 拿它做门（`openai-responses.ts:321`），
   不发门就等于给不支持的端点塞新字段。

### 1.2 不做

- **`compat.thinkingFormat` 的十种形状** ⇒ **A-09**（下一包）。
- **`buildBaseOptions` 里 java 没有对应物的字段**（`fetch` / `onPayload` / `onResponse` /
  `transport` / `websocketConnectTimeoutMs` / `metadata` / `env` / `timeoutMs` / `maxRetries` /
  `maxRetryDelayMs` / `deferred`）。**这些不是偏差，是子系统缺席**——java 根本没有可注入的
  fetch 或 websocket 通道，写一个没有装配点的字段就是没有消费者的形状。
  逐字段对账见 §3 P2 表。
- **`thinkingBudgets` 的选项通道**（`SimpleStreamOptions.thinkingBudgets`）⇒ 决策 **R7**：
  不做、登记。理由：pi 的 harness 自己**也不传**它（§3 P8），今天两侧同值。
- **`thinkingTokenBudgetField` 的 models.json 键之外的目录标注** ⇒ 按 `docs/46 §3-D5` 的
  先例（**不编假数据**），内置目录一个都不标；只有 models.json 可达。
- **A-09 的 `clampThinkingLevel` 接线**（`openai-completions.ts:741`）⇒ A-09。
  今天 `ModelThinkingLevels.clamp` 有移植**零生产调用者**（`docs/46 §7` B15-残留-9）。
- **`ModelInfo.headers` 的消费者** ⇒ 零消费者，登记（§10）。
- **压缩摘要路径**（`LlmSummaryGenerator` 走同一条 `DefaultProviders.streamBlocking`）
  ⇒ **自动跟随**，不单独改。

### 1.3 与 A-09 的边界（一句话）

| 包 | 管什么 | 可观察面 |
|---|---|---|
| **A-10（本包）** | 请求的**选项从哪来**：`max_tokens` / `temperature` / `samplingParams` / 顶层预算字段 | 请求体里**有没有**这些字段、值是多少 |
| **A-09（下一包）** | 请求的**思考开关长什么样**：`thinkingFormat` 十种形状 ＋ `$var` 解析 | 请求体里 `thinking` / `reasoning` / `enable_thinking` / `chat_template_kwargs` 等的**形状** |

两包都要扩 `ModelCompat` 与 models.json，且**同一处** `buildParams`（`openai-completions.ts`
的 `:866-978`）——故本包先把「字段名与值」这一半定型，A-09 只加形状。

**验收判据**（沿用本仓既定口径）：**行为等价** —— 对同一个模型、同一份 context，java 发出的
请求体里这些字段的**存在性与值**与 pi 逐字相同；`clampMaxTokensToContext` 的输出与 pi
逐值相同（含 `contextWindow <= 0` 的分支）。

---

## 2. 术语与三个陷阱

### 2.1 三个 `4096`

本包里 `4096` 这个数字出现三次，**是两件不同的事**，混掉会让整包判据失效：

| 出处 | 是什么 | 本包 |
|---|---|---|
| `simple-options.ts:12` `CONTEXT_SAFETY_TOKENS = 4096` | 夹取时的**安全余量**（减法项） | **移植**（就是这个数） |
| `AnthropicRequestBuilder:84` `4096L` | java **发明的**请求上限（`max_tokens` 的值） | **删除**（§6 R9） |
| `ThinkingBudgets.MIN_ANSWER_TOKENS = 1024` | 思考预算留出答案的余量（**1024**，不是 4096） | 不动（H5 已移植） |

⚠️ 第二行那个 `4096L` 与第一行**数值巧合相同**，极易被读成「照抄了安全余量」。**它是巧合**：
`AnthropicRequestBuilder` 的 javadoc 与 git 历史都没有提到 `CONTEXT_SAFETY_TOKENS`。

### 2.2 pi 有**三份** `estimateContextTokens`，java 只移植了其中一份

`clampMaxTokensToContext` 用的是 `packages/ai/src/utils/estimate.ts:97`。同名函数在 pi 还有
**两份**，算法**不同**：

| # | 位置 | 扫描 | 时间戳守卫 | `system` 角色 |
|---|---|---|---|---|
| ① | `ai/src/utils/estimate.ts:97` | **正向** | **有**（`timestamp >= latestPrefixTimestamp`） | 计入（文本 ＋ `toolsAdded`/`toolsRemoved`） |
| ② | `agent/src/harness/compaction/compaction.ts:215` | 反向 | 无 | 落到 `return 0` |
| ③ | `coding-agent/src/core/compaction/compaction.ts:217` | 反向 | 无 | 同 ② |

java 的 `ContextUsageEstimator`（agent-core，3b）是 **②** 的忠实移植 —— 它的 javadoc 自己
写明 port 的是 `harness/compaction/compaction.ts`，且 `estimateTokens` 的 `system` 也是 0。
**本包要的却是 ①** ⇒ 必须在 `ai` 里另立一份（§6 R3）。这是**对齐 pi**，不是重复代码：
pi 自己就有三份，因为三个调用方要的语义不同。

### 2.3 六条车道对同一个值用**六个字段名**

`maxTokens` 落到线格时字段名各不相同，本包的夹具必须逐车道按各自的名字断言：

| 车道 | 线格字段 | java 落点 |
|---|---|---|
| Anthropic | `max_tokens`（**必填**） | `AnthropicRequestBuilder:82` |
| OpenAI Completions | `max_tokens` 或 `max_completion_tokens`（按 `compat.maxTokensField`） | `OpenAICompletionsMessageConverter:203-208` |
| OpenAI Responses / Azure | `max_output_tokens`（有 `max(·, 16)` **下限**，且**仅 openai-responses** 有 compat 门） | `ResponsesMessageConverter:103-105` |
| Google / Vertex | `generationConfig.maxOutputTokens` | `GoogleGenerativeAiApi:307-309` |
| Mistral | `max_tokens`（body 字符串键） | `MistralConversationsApi:288-290` |
| pi-messages | envelope 里的 `options.maxTokens` | `PiMessagesApi:182-184` |

**观测面**（A-01 的教训：同一字段在 SDK 对象／序列化 JSON／录制字节三层有三个访问器）：
本包的三层分别是 **SDK 参数对象**（`MessageCreateParams.maxTokens()` /
`ChatCompletionCreateParams.maxTokens()`）、**序列化 JSON**、**录制字节**
（`RecordingHttpServer.body()`）。夹具一律走**字节**（§4.5），因为前三层里
`writeValueAsString` 那条路已被 A-01 证伪过。

---

## 3. pi 侧取证

### 3.1 命题表

| # | 命题 | 出处 |
|---|---|---|
| **P1** | `StreamOptions` 与 `SimpleStreamOptions` 是**两层**：后者 `extends` 前者，只加 **4** 个字段 —— `toolChoice` / `reasoning` / `deferred` / `thinkingBudgets` | `types.ts:186-230`、`:324-333` |
| **P2** | `buildBaseOptions(model, context, options?, apiKey?)` 返回 `StreamOptions`（**返回值是完整选项对象**，共 20 个键） | `simple-options.ts:21-52` |
| **P3** | 其第 34 行：`maxTokens: clampMaxTokensToContext(model, context, options?.maxTokens ?? model.maxTokens)` | `simple-options.ts:34` |
| **P4** | `samplingParams` 合并：`model.samplingParams \|\| options?.samplingParams ? {...model.samplingParams, ...options?.samplingParams} : undefined` | `simple-options.ts:27-30` |
| **P5** | `clampMaxTokensToContext`：`contextWindow <= 0` ⇒ `Math.max(1, maxTokens)`（**不夹**）；否则 `Math.min(maxTokens, Math.max(1, contextWindow - estimateContextTokens(context).tokens - 4096))` | `simple-options.ts:12-19` |
| **P6** | 直接调用者有**恰两处**（`anthropic-messages.ts:896`、`bedrock-converse-stream.ts:559`），且都在 `adjustMaxTokensForThinking` **之后**再夹一次 —— 因为 adjust 可能**抬高**上限（`baseMaxTokens + thinkingBudget`） | `simple-options.ts:79-94` |
| **P7** | `buildBaseOptions` 有 **9** 个调用者（anthropic／azure／bedrock／google-genai／google-vertex／mistral／codex／completions／responses），每个都是 `{...buildBaseOptions(...), toolChoice}` 然后交给自己的 `stream` | `git grep buildBaseOptions` |
| **P8** | ★ **pi 的 harness 不传 `maxTokens`／`temperature`／`samplingParams`／`thinkingBudgets`** —— `createRequestOptions` 只列了 transport/timeoutMs/maxRetries/maxRetryDelayMs/headers/metadata/cacheRetention/deferred/reasoning/signal/telemetryContext/onPayload/onResponse | `harness/execution/assistant.ts:65-91` |
| **P9** | ⇒ 生产路径上 `options.maxTokens` 恒 `undefined` ⇒ 恒走 `model.maxTokens`（再夹） | P3 ＋ P8 |
| **P10** | **唯一入口是 `streamSimple`**：驱动循环调 `lane.models.streamSimple(...)` | `harness/runtime/drive/generation.ts:216` |
| **P11** | `estimateContextTokens`（①）：正向扫，记 `latestPrefixTimestamp`，只收 `assistant.timestamp >= latestPrefixTimestamp` 且 `stopReason ∉ {aborted, error}` 且 `calculateContextTokens(usage) > 0` 的那条；命中 ⇒ `usage + trailing`，未命中 ⇒ 全表估 | `utils/estimate.ts:71-112` |
| **P12** | `estimateMessageTokens`：`system` ⇒ 文本 ＋ `toolsAdded`/`toolsRemoved` 的 JSON；`user`/`toolResult` ⇒ 文本 ＋ 图（每图 **4800 字符**）；其余（assistant）⇒ `text` ＋ `thinking` ＋ `name + JSON(args)`；一律 `Math.ceil(chars / 4)` | `utils/estimate.ts:30-69` |
| **P13** | `calculateContextTokens(usage) = usage.totalTokens \|\| input + output + cacheRead + cacheWrite` —— **`\|\|` 不是 `??`**（`totalTokens === 0` 会落到求和） | `utils/estimate.ts:18-20` |
| **P14** | Anthropic 的 `max_tokens` 三分支：无 reasoning ⇒ `base.maxTokens`；`forceAdaptiveThinking` ⇒ `base.maxTokens`；否则 ⇒ `clampMaxTokensToContext(model, context, adjustMaxTokensForThinking(base.maxTokens, model.maxTokens, reasoning, thinkingBudgets).maxTokens)` | `anthropic-messages.ts:865-903` |
| **P15** | Anthropic 的**低层** `stream` 自己也有回落：`options?.maxTokens ?? model.maxTokens` | `anthropic-messages.ts:1072` |
| **P16** | 其余车道**没有**这个回落，它们只写 `if (options?.maxTokens)` —— 之所以总能写上，是因为 `streamSimple` 已经用 `buildBaseOptions` 填过 | `openai-completions.ts:836-842`、`google-generative-ai.ts:381-383`、`mistral-conversations.ts:523`、`openai-responses.ts:321-322` |
| **P17** | ★ `openai-codex-responses` **完全忽略** `maxTokens`（全文件零命中 `maxTokens`） | `openai-codex-responses.ts` |
| **P18** | ★ `pi-messages` **不调** `buildBaseOptions`：它的 `streamSimple` 直接 `{...options, reasoning, toolChoice, debug}` ⇒ **无钳制、无 samplingParams 合并** | `pi-messages.ts:431-443` |
| **P19** | `resolveThinkingTokenBudgetField(compat)`：显式 `thinkingTokenBudgetField` 优先，否则 `supportsThinkingTokenBudget` ⇒ `"thinking_token_budget"`，否则无 | `openai-completions.ts:1004-1010` |
| **P20** | `resolveClampedThinkingBudget(model, options, params)`：无 `reasoningEffort` 或无 `model.reasoning` ⇒ `undefined`；否则 `ceiling = params.max_tokens ?? params.max_completion_tokens ?? model.maxTokens`，`clampThinkingBudgetToAnswerRoom(thinkingBudgetForLevel(effort, budgets), ceiling)`，`> 0` 才返回 | `openai-completions.ts:1012-1024` |
| **P21** | 落点在 thinkingFormat 链条**之外**（`if (thinkingTokenBudgetField && thinkingBudget !== undefined)`），故 `"openai"` 形态也有 | `openai-completions.ts:972-978` |
| **P22** | 三条 OpenAI 车道的 `samplingParams` 是 body 的**最后一个变更**（`Object.assign(params, options.samplingParams)`，注释：「Last so custom keys override the named request fields」） | `openai-completions.ts:996-999`、`openai-responses.ts:362-365`、`azure-openai-responses.ts:345-347` |
| **P23** | ⇒ `samplingParams` 里的键**压过** `max_tokens`／`temperature`／`reasoning_effort`／预算字段 | P22（`types.ts:193-199` 的契约） |
| **P24** | `supportsMaxOutputTokens` **只被 `openai-responses` 读**（`?? true`）；**Azure 那份副本没有这道门** | `openai-responses.ts:79`/`:321`、`azure-openai-responses.ts:309-310`、`types.ts:774` |
| **P25** | `Responses` 两车道的下限夹取 `Math.max(x, 16)` 是**下限**，与 `clampMaxTokensToContext`（上限）是**不同函数、不同方向** | `openai-responses.ts:322`、`azure-openai-responses.ts:310` |
| **P26** | `Model.contextWindow` / `Model.maxTokens` 都是**必填无默认**（`number`，无 `?`）；`<= 0` 被当「未知」哨兵 | `types.ts:975-976`、`simple-options.ts:16` |
| **P27** | `clampReasoning`／`thinkingBudgetForLevel`／`clampThinkingBudgetToAnswerRoom` **只**服务 `packages/ai` 内部（外部消费者只有 bedrock 与 completions 两处） | `git grep` |
| **P28** | `ai/utils/estimate.ts` **不在** `packages/ai/src/index.ts` 的导出列表里；它以 `./utils/*` 子路径公开 | `index.ts:39-49`、`package.json` |

### 3.2 `buildBaseOptions` 的字段对账（P2 → java）

| `StreamOptions` 键 | pi 语义 | java 现状 | 本包 |
|---|---|---|---|
| `maxTokens` | **本包的核心** | `StreamRequest.maxTokens`（int，-1 哨兵）恒 -1 | **做** |
| `temperature` | 直通 | `StreamRequest.temperature`（double，-1 哨兵） | 不动（读点已对） |
| `samplingParams` | model ⨁ options 合并 | `ModelInfo.samplingParams` **零消费者** | **做** |
| `apiKey` | 直通 | `ApiOptions.apiKey` ＋ `Credentials` 链 | 不动 |
| `cacheRetention` | 直通 | `ApiOptions.extra["cacheRetention"]`（A-01） | 不动 |
| `signal` | 中止信号 | `AbstractChatApi` 的流生命周期（虚拟线程） | 不动 |
| `headers` | 直通 | `ModelInfo.headers` 零消费者 | **不做**，登记 |
| `sessionId` | 会话亲和 | java 无 | **不做**（无装配点） |
| `timeoutMs` / `maxRetries` / `maxRetryDelayMs` | 传输 | `ApiOptions.timeout` / `maxRetries` | 不动（另一条链，A-14） |
| `metadata` | 仅 Anthropic `user_id` | java 无 | **不做** |
| `transport` / `websocketConnectTimeoutMs` | 仅 codex | java 无该车道 | **不做** |
| `fetch` / `env` / `onPayload` / `onResponse` | 可注入点 | java 无 | **不做** |
| `telemetryContext` | 遥测父级 | java 走 `PiLaneSink`（D 包已裁决线程归属） | 不动 |

⚠️ **「不做」的那几行不是缺口偏差，是子系统缺席**：它们在 java 侧没有装配点，
写出来就是没有生产消费者的形状（`docs/48 §7`「不在没有 pi 行为证据、Java 可达路径和
测试断言的情况下添加仅形状字段」）。对账表留在这里，是为了让后续包**不必重新查一遍**。

---

## 4. java 侧现状（实测）

### 4.1 选项的两层与那条通道

| pi | java | 位置 |
|---|---|---|
| `SimpleStreamOptions` | `StreamOptions`（**agent-core**） | `harness/StreamOptions.java`（`OptionalInt maxTokens` / `OptionalDouble temperature` / `ModelThinkingLevel thinking`） |
| `StreamOptions` | `StreamRequest`（**ai**） | `api/StreamRequest.java`（`int maxTokens` / `double temperature` / `Map extra` / `Optional<ThinkingLevel> reasoning`） |

**唯一的生产构造点**：`DefaultProviders.streamBlocking:147-154` ——
`new StreamRequest(modelInfo, …, options.maxTokens().orElse(-1), options.temperature().orElse(-1), Map.of(), options.reasoning())`。

**唯一的生产 `StreamOptions` 构造点**：`PiLoopRunner:224-226`
（另两处是 `LlmSummaryGenerator:227` 与**生产不可达**的 `StreamSimple:76`）。
**三处全部**传 `OptionalInt.empty()`。

⇒ ★ **`request.maxTokens()` 运行期恒为 `-1`。**

### 4.2 后果：一条车道发错值，五条车道一个上限都不发

| 车道 | pi 生产路径发什么 | **java 今天发什么** |
|---|---|---|
| **Anthropic** | `clamp(model.maxTokens)`（thinking on 时 `clamp(adjust(...))`） | **`4096`**（无思考／adaptive）／`model.maxOutputTokens()`（budget 型，**未夹取**） |
| OpenAI Completions | `max_tokens` 或 `max_completion_tokens` = `clamp(model.maxTokens)` | **不发**（`if (request.maxTokens() > 0)` 恒假） |
| OpenAI Responses / Azure | `max_output_tokens` = `max(clamp(model.maxTokens), 16)` | **不发** |
| Google | `generationConfig.maxOutputTokens` = `clamp(model.maxTokens)` | **不发** |
| Mistral | `max_tokens` = `clamp(model.maxTokens)` | **不发** |
| **pi-messages** | `options.maxTokens` —— 因 P18 不过 `buildBaseOptions`，**恒为 `undefined` ⇒ 不发** | **不发** ✅ **已一致** |

**Anthropic 那一格是本包最重的后果**：`4096` 是按默认分支（**不传 `--thinking`**）与
adaptive 分支（`fable-5` / `opus-4-8` / `sonnet-4-6` 三个内置模型命中
`forceAdaptiveThinking`）走的 ⇒ **不开思考时每条请求的输出上限被砍到 4096**。

- `claude-sonnet-4-6` 内置目录 `maxOutputTokens = 8192` ⇒ **2 倍差**；
- `claude-opus-4-8` 是 `32768` ⇒ **8 倍差**。

**而且它现在会被看见**：B109 刚把 `length` 这条通路修活（`docs/56`）⇒ 截断会真报
`stopReason: "length"`，接着撞 B20 的 length 门（本回合全部工具调用判失败）。

### 4.3 `ModelInfo` 的三个相关字段与「目录未命中」这一格

- `ModelInfo.maxInputTokens` **就是** pi 的 `contextWindow`（javadoc 明写，单位 token）。
- `ModelInfo.maxOutputTokens` **就是** pi 的 `maxTokens`（既作缺席时的默认值，也作
  `adjustMaxTokensForThinking` 的天花板 —— pi 是**同一个字段担两角**，java 同）。
- **`ModelInfo.minimal(id)` 把两者都置 0**（`:152`），而它是
  `DefaultProviders:145-146` 的 `orElseGet` ⇒ **任何不在目录里的模型都走 0/0**。
- models.json 路径有真值：`ModelsJsonConfig:204-205` 默认 `contextWindow 128000` /
  `maxTokens 16384`。

⚠️ pi 在 `contextWindow <= 0` 时的公式是 `Math.max(1, maxTokens)`；若 `maxTokens` 也是 0
⇒ **pi 会发 `max_tokens: 1`**。但 pi 的 `Model` 这两个字段**必填无默认**（P26），它的数据面
里不存在 0 ⇒ **这一格在 pi 不可达**，没有可对齐的行为。⇒ 决策 **R4**。

⚠️ **这一格今天就有夹具走在上面**（实测）：`AnthropicMessagesApiBuildParamsTest` 的
`request(...)` 助手用的是 **`ModelId` 版构造器**（`StreamRequest:108`）⇒ 模型是
`ModelInfo.minimal` ⇒ **`maxOutputTokens == 0`**，而它**直接调 `buildParams`**
（反射，绕过 `AbstractChatApi`）。`AnthropicSurrogateSanitizeTest` 同法。
⇒ **R9 的「回落改成 `model.maxOutputTokens()`」若不同时给 R4 的兜底，这些夹具会产出
`max_tokens: 0`**（一个 Anthropic 会 400 的值），而它们**都不断言 `max_tokens`**
⇒ **仍会全绿**。这正是「夹具没牙」的形态：不是夹具坏，是**它不覆盖这一格**。
故 R4 的兜底**必须落在共享助手里**（§6 R4），让「经漏斗」与「直接调 builder」两条路
拿到同一个安全值。

### 4.4 三个既有事实（本包要绕开的坑）

1. **`StreamRequest.transcript()` 永不为 null** —— 紧凑构造器把 null 折成空
   `TranscriptContext`（`StreamRequest:55`）⇒ 夹取**不需要**处理 null transcript。
2. **`AbstractChatApi.stream` 是唯一漏斗** —— `streamBlocking:93` 与 `send:139` 都经它；
   `streamInternal` 在 subscriber lambda 里被调用（`started` 闸保证一次）。8 个子类：
   `AnthropicMessagesApi` / `AzureOpenAIResponsesApi` / `GoogleGenerativeAiApi` /
   `MistralConversationsApi` / `OpenAICompletionsApi` / `OpenAIResponsesApi` /
   `PiMessagesApi` / `FauxProvider.FauxChatApi`。`OpenRouterImagesApi` 与
   `OpenAIEmbeddingApi` 是**另两个接口**，不在漏斗里。
3. **`pi-java-ai` 不依赖 `pi-java-agent-core`**（两侧 pom 已核）⇒ 估算器只能落在 `ai`。

### 4.5 夹具的观察面

`pi-java-ai/src/test/.../RecordingHttpServer.java`：根上下文接所有路径，**恒回 400**，
只录**最后一次**请求体与头。断言形态是「把 `ApiOptions.baseUrl` 指过去 → 排空流（吞掉 400）
→ 断言 `body()`」。**它不能编排响应**，但请求侧断言够用 —— 而这正是本包的观测面。
既有的请求体夹具（`AnthropicCacheControlWireTest`、`CompletionsCompatWireTest`、
`AnthropicThinkingWireTest` …）全部按**键取值**断言，不逐字节比对。

### 4.6 今天**没有**任何夹具钉住 `max_tokens` 的夹取

`grep` 全模块测试：唯一与输出上限值有关的断言是
`CompletionsCompatWireTest:56`（`maxTokens()).contains(512L)`，夹具**显式**给了 512）与
`AnthropicThinkingWireTest:138`/`:160`/`:178`/`:233`（同样显式给了上限）。
⇒ **没有任何一条用例断言「缺席时发什么」**。这是本包先红的天然入口（§8）。

---

## 5. 改动面清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ai/utils/Estimate.java`（**新建**） | pi `utils/estimate.ts` 的移植（①）：`calculateContextTokens` / `estimateContextTokens(TranscriptContext)` / `estimateMessageTokens` / `estimateTextTokens` / `estimateTextAndImageContentTokens` ＋ `record Estimate(tokens, usageTokens, trailingTokens, lastUsageIndex)` |
| 2 | `ai/api/SimpleOptions.java`（**新建**） | `clampMaxTokensToContext(model, transcript, maxTokens)` ＋ `maxTokensOrDefault(model, requested)`（**builder 层回落**：`requested > 0 ? requested : model.maxOutputTokens() > 0 ? model.maxOutputTokens() : NO_MODEL_CAP_FALLBACK`，＝ pi 的 `options?.maxTokens ?? model.maxTokens` ＋ R4 的兜底）＋ `resolveMaxTokens(model, transcript, requested)`（＝ `clamp(maxTokensOrDefault(...))`，**仅供漏斗用**）＋ `mergeSamplingParams(model, requested)` |
| 3 | `ai/protocol/AbstractChatApi.java` | `stream` 里把 request 换成解析后的副本后再进 `streamInternal`；新增 `protected boolean resolvesRequestOptions()`（默认 `true`，**两处**覆写为 `false`） |
| 4 | `ai/protocol/PiMessagesApi.java` | `resolvesRequestOptions()` 返 `false`（P18） |
| 5 | `ai/provider/FauxProvider.java` | `FauxChatApi.resolvesRequestOptions()` 返 `false`（测试替身，保持确定性） |
| 6 | `ai/protocol/AnthropicRequestBuilder.java` | 删 `4096L`（R9）；回落改成 `SimpleOptions.maxTokensOrDefault(model, request.maxTokens())`（P15 ＋ R4 —— **必须走共享助手**，否则 §4.3 的 `minimal` 夹具产出 0 而全绿） |
| 7 | `ai/protocol/OpenAICompletionsMessageConverter.java` | body **最后**加 `samplingParams` 合并（P22）；顶层预算字段落点（P21） |
| 8 | `ai/protocol/ResponsesMessageConverter.java` | 同上（samplingParams）；`max_output_tokens` 补 `supportsMaxOutputTokens` 门（**仅 openai-responses**，P24） |
| 9 | `ai/catalog/ModelCompat.java` | `16 → 19` 个组件：`thinkingTokenBudgetField`（`ThinkingTokenBudgetField` 新枚举）、`supportsThinkingTokenBudget`、`supportsMaxOutputTokens`；便捷构造器按 A7 的「加在末尾」办法扩 |
| 10 | `ai/catalog/CompatResolver.java` | 三个新字段的解析：completions 侧 `?? false` / `?? undefined`；responses 侧 `supportsMaxOutputTokens ?? true`（P24）；**azure 侧恒 `true`**（P24 的副本无门） |
| 11 | `ai/provider/ModelsJsonSchema.java` ＋ `ModelsJsonConfig.java` | compat 加三键：`thinkingTokenBudgetField`（未知值**响亮报错**，照 `maxTokensField` 的做法）、`supportsThinkingTokenBudget`、`supportsMaxOutputTokens` |
| 12 | `ai/catalog/ModelInfo.java` | 无结构性改动；**可能**给 `minimal` 加一条注释（R4 的兜底只在目录未命中处生效） |
| 13 | 夹具 | §8 的六条新用例 ＋ 既有 wire 夹具按实测校正（§7） |

**行数预算**：`Estimate` ≈ 120 行、`SimpleOptions` ≈ 90 行 ⇒ **新类都在 500 行以下**。
`AbstractChatApi`（305）＋约 25 行；`ResponsesMessageConverter` **已超限**（545，A7 之前即超），
本包**不再加**（samplingParams 三行 ＋ 门三行 ⇒ 约 +8）；`MistralConversationsApi`（512）本包不动。

---

## 6. 决策点

| # | 决策点 | 建议 | 理由 |
|---|---|---|---|
| **R1** | 夹取与回落的**落点** | **`AbstractChatApi.stream`**（唯一漏斗，把 request 换成解析后的副本再进 `streamInternal`） | ① pi 的语义是「每条车道的 `streamSimple` 都做」，java 的 `stream` **就是** pi 的 `streamSimple`（生产唯一入口是 `streamSimple`，P10）；② 放在这里 ⇒ 8 条车道**零改签**地拿到解析值，不需要在 8 处 `buildParams` 复制同一段；③ 放 `DefaultProviders` 会让 `ChatApi` 的**直接**调用方（RPC / web / SDK / evals / 夹具）拿不到，凭空造出一处 pi 没有的「只有 CLI 才夹取」 |
| **R2** | 豁免车道与机制 | **`PiMessagesApi` 必须豁免**（pi 的 pi-messages 不过 `buildBaseOptions`，P18）＋ **`FauxChatApi` 豁免**（测试替身）。机制：`protected boolean resolvesRequestOptions()` 默认 `true`，两处覆写 `false` | ⚠️ 不豁免 `PiMessagesApi` 会**凭空多发**一个 `maxTokens` 字段 ⇒ 是对齐的**反向**破坏。默认 `true` 让「新车道默认跟着 pi 的通用路径」这一侧是安全的 |
| **R3** | 估算器落点与命名 | **新建 `com.pijava.ai.utils.Estimate`**（对齐 pi 的文件名 `utils/estimate.ts`）；**不与** agent-core 的 `ContextUsageEstimator` 合流 | ① 模块方向不允许反向依赖（§4.4-3）；② 两份的语义**真的不同**（§2.2），合流必然要选一份 ⇒ 就等于放弃另一份的对齐；③ pi 自己就是三份并存。⚠️ **`agent-core` 另有一个 `ContextEstimator`**（自造 3.5 字符/token 启发式、生产不可达）—— 三处同名，javadoc 必须互相指路 |
| **R4** | **目录未命中**（`minimal`：`maxInputTokens == 0 && maxOutputTokens == 0`）时发什么 | **保留 `4096` 兜底，并写成一条有名字的常量**（`SimpleOptions.NO_MODEL_CAP_FALLBACK`，带 javadoc 说明它**不是** pi 的行为）**＋ 登记**（不照抄 pi 的 `1`）。⚠️ **兜底必须落在共享助手里**（`SimpleOptions.maxTokensOrDefault`），由 **builder 的回落**与 **`AbstractChatApi` 的夹取**共用 —— 否则「直接调 `buildParams` 的夹具」那条路会产出 `max_tokens: 0`（§4.3 实测） | pi 的 0/0 格**不可达**（P26，它的模型必填真值）⇒ 没有可对齐的行为。而 java 这一格是**今天就有生产路径**的（`DefaultProviders:145` 的 `orElseGet`）。`max_tokens: 1` 会把会话变成一字一停；`0` 会被 Anthropic 直接 400。⇒ 取「无模型数据时的一个保守默认」并**如实登记**它不是 pi 的行为。反方：判据是行为等价 ⇒ 但那要求**存在**一个可对齐的 pi 行为，这里不存在 |
| **R5** | `samplingParams` 是否本包做 | **做**，且只落**三条 OpenAI 兼容**车道（completions／responses／azure），**必须是 body 的最后一个变更** | ① 它在 `buildBaseOptions` 里，本包就是它的包；② `ModelInfo.samplingParams` **今天零消费者**（`docs/53 §4.4` 列在「零消费者」），不接就是一个没有消费者的字段；③ 顺序是**语义**（P22/P23：压过具名字段），夹具必须钉「最后一个」而不是「存在」。⚠️ **per-request 那一半没有生产者**（`StreamRequest.extra` 生产恒 `Map.of()`，B107）⇒ 只做模型级那一半，另一半**登记** |
| **R6** | `maxTokens` 的哨兵形态 | **保持 `int` ＋ `-1`**，不改成 `OptionalInt` | pi 的 `options.maxTokens === 0` 与 `undefined` 是**两回事**（`??` 不回落 0）；java 的 `-1` 把两者合并。但：① pi 的 harness 从不传（P8）⇒ 0 无生产路径；② 改签会波及 20+ 构造点与全部夹具，收益是「不可达的一格」。⇒ 保持 ＋ **登记** |
| **R7** | `thinkingBudgets` 选项通道 | **不做**、登记 | pi 的 harness **也不传**（P8）⇒ 两侧今天同值（都用默认表）。java 硬写 `ThinkingBudgets.DEFAULT` 与 pi 的 `options.thinkingBudgets === undefined` 等价。加通道＝加一个没有生产者的形状。**A-09 会再碰它一次**（`$var: thinking.budget`），届时若要接，接的是同一个字段 |
| **R8** | `thinkingTokenBudgetField` / `supportsThinkingTokenBudget` | **本包做**（含读点与落点） | `docs/53 §4.4` 明确归属 A-10。它与 `thinkingFormat` **无关**（P21 的注释：同一台服务器可能同时服务 zai/qwen/chat-template 模型）⇒ 能独立落。⚠️ **顺序上与 A-09 有耦合**：`resolveClampedThinkingBudget` 的 `ceiling` 读 `params.max_tokens`（P20）—— 那边界正是本包修出来的。故 A-10 必须先落 |
| **R9** | `AnthropicRequestBuilder:84` 的 `4096L` | **删除**，回落改 `model.maxOutputTokens()`；`4096` 只允许以 **R4 的目录未命中兜底**这一种身份出现一次 | pi 的 builder 层回落是 `options?.maxTokens ?? model.maxTokens`（P15），**没有**字面量。留着会与 R4 的兜底形成两个真相 |
| **R10** | Anthropic adaptive 分支的 `max_tokens` | **跟随 R1/R9 自动变成 `clamp(model.maxTokens)`**，不单独处理 | `AnthropicThinking.resolve` 在 adaptive 分支返回 `OptionalInt.empty()`（「沿用调用方给的值」）—— 调用方给的就是 R1 解析后的值。**javadoc 里「`clampMaxTokensToContext` 不在内」那句要改写**（`AnthropicThinking:102-104`），否则与代码矛盾 |
| **R11** | 是否顺带把 `ModelInfo.headers` 接上 | **不接**、登记 | 它在 `buildBaseOptions` 的 `apiKey`/`headers` 面上，但 java 的 `ApiOptions` 里没有「模型级头」这一层，且 pi 的那条路（`ProviderRequestOptions.headers`）是**调用方**给的、不是模型给的 —— 是两件事，另立 |
| **R12** | `temperature` | **不动** | 两侧读点已一致（`request.temperature() >= 0` 对 `options.temperature !== undefined`，同样是「缺席不发」）。⚠️ 唯一差别：java 的 `-1` 同样合并了「0 与缺席」——0 是合法温度 ⇒ **这是 R6 的同族问题且更真实**；登记，不在本包改签 |

---

## 7. 不变量与对照组

### 7.1 唯一的不变量

**`model.samplingParams` 为空、且请求没给 `maxTokens`／`temperature` 时，三条 OpenAI 车道的
body 除了新增的 `max_*` 字段外，其余键与值逐字不变。**

理由：`samplingParams` 的落点是**最后**一步（P22），它一旦写错位置就会静默改写具名字段；
`temperature` 与 `maxTokens` 的读点则完全不该动。

### 7.2 对照组（必须从头到尾保持绿）

| 断言 | 值 |
|---|---|
| `CompletionsCompatWireTest:56` | 显式 `maxTokens=512` ⇒ `max_tokens: 512`（**值不变**，只多一层夹取） |
| `AnthropicThinkingWireTest:138` | 模型上限 5000、base 缺席 ⇒ `max_tokens: 5000` |
| `AnthropicThinkingWireTest:160` | 显式 base ⇒ `min(base + budget, 模型上限)` |
| `AnthropicThinkingWireTest:233` | adaptive 分支沿用调用方的 `1234` |
| `AnthropicCacheControlWireTest` 全部 | 缓存断点与 `max_tokens` 无关 |
| `CompatResolverTest` 全部 | `maxTokensField` 的五条探测不变 |

⚠️ **它们「应该」保持绿，但每条都依赖一个尚未测量的前提**：夹具的模型 `maxInputTokens`
减去估出的 token 再减 4096，必须 **≥ 该用例的显式上限**（否则夹取会真的把值压小）。
**实施时必须逐条测量并如实记录**，不许为了让它们变绿而调夹具的期望值 —— 若某条**真的**
被夹小了，那是**夹具的输入不现实**（例如手搓 `ModelInfo` 的 contextWindow 太小），
应按 pi 的真实数据改**输入**并在 §12 记明。这是本节最重要的一句。

**已实测的两类夹具（实施时按此核对，不必重新发现）**：

| 类 | 例子 | 模型 | 走哪条路 | 预期 |
|---|---|---|---|---|
| **手搓真值** | `AnthropicThinkingWireTest`（`thinkingModel` 200_000/maxOut；`roomyModel` 64_000）、`AnthropicCacheControlWireTest`（200_000/32_000） | `maxInputTokens` 都是 **200_000** ⇒ 夹取惰性 | 前者直调 `buildParams`、后者走 wire | **绿**（显式上限远小于可用余量） |
| ★ **`ModelInfo.minimal`（0/0）** | `AnthropicMessagesApiBuildParamsTest`、`AnthropicSurrogateSanitizeTest`（都用 `ModelId` 版构造器） | **0/0** ⇒ 走 R4 的兜底 | 直调 `buildParams`（反射） | **绿但无牙** —— 它们**都不断言 `max_tokens`** ⇒ 无论兜底写对写错都绿（§4.3）。**故必须有新用例专门钉这一格**（RED-8，§8.1） |

### 7.3 今日应当**变红**的既有断言

**预测：零。** 理由：§4.6 实测没有任何夹具钉住「缺席时发什么」。按 C 批次的规矩：
**先声明、再测量**；若实测有红，逐条查是不是 §7.2 那个前提，不当成「设计错了」。

---

## 8. 先红计划与变异探针

### 8.1 先红（写在实施之前）

| # | 夹具 | 旧实现下的红 |
|---|---|---|
| **RED-1** | Anthropic wire：内置 `claude-sonnet-4-6`（`maxOutputTokens=8192`）、**无 reasoning** ⇒ 断言 `max_tokens == 8192` | 今天得 **4096** ⇒ 红 |
| **RED-2** | 同上但模型换 `claude-fable-5`（`forceAdaptiveThinking`）⇒ 断言 `max_tokens == 16384` | 今天得 **4096** ⇒ 红（**adaptive 分支**） |
| **RED-3** | Completions wire：标准端点、无 reasoning ⇒ 断言 body **有** `max_completion_tokens == 模型上限` | 今天**字段缺席** ⇒ 红 |
| **RED-4** | `SimpleOptions` 单元：长 context 下 `clampMaxTokensToContext` 逐值（含 `contextWindow == 0` ⇒ 不夹；`available <= 0` ⇒ `1`） | 类不存在 ⇒ **编译失败**先红（A7a 形态） |
| **RED-5** | `Estimate` 单元：`usage` 命中／未命中两路 ＋ **时间戳守卫**（在 assistant 之后插一条更晚的 system ⇒ 锚点必须前移）＋ `system` 角色计入 | 同上，编译失败先红 |
| **RED-6** | Completions wire：models.json 给 `samplingParams: {top_p: 0.9}` ⇒ 断言 `top_p` 在 body 里，**且是最后一个键**；配对用例：`samplingParams: {max_tokens: 7}` ⇒ 压过 `max_completion_tokens` | 今天不发 ⇒ 红 |
| **RED-7** | Completions wire：`thinkingTokenBudgetField: "thinking_budget"` ＋ reasoning ⇒ 断言该字段存在且值 = `min(budgetForLevel, max_tokens - 1024)`；配对：无 reasoning ⇒ **字段缺席** | 今天不发 ⇒ 红 |
| **RED-8** | Anthropic `buildParams`**直调**（不经漏斗）、模型为 `ModelInfo.minimal`、`maxTokens = -1` ⇒ 断言 `max_tokens == NO_MODEL_CAP_FALLBACK`（R4） | 今天得 `4096` ⇒ **若兜底恰好也是 4096 则绿** ⇒ 该用例的**牙来自 M7**（把兜底改成别的值），不是来自先红。⚠️ 这条要**如实**记为「先红不成立、改由变异守门」（`docs/48 §5` A 行 Task 3 有同形先例） |

RED-4／RED-5 是**结构性**先红（类不存在 ⇒ 编译失败），与包 A7a/A4a 同形；它们在
「先红证据」列里如实记为编译失败，不伪装成断言失败。

### 8.2 变异探针

| 探针 | 变异 | 预期 |
|---|---|---|
| **M1** | `resolveMaxTokens` 里的**回落**改成恒返 `requested`（不复落到模型上限） | RED-1/2/3 红（值回到 -1 ⇒ Anthropic 又走兜底、其余不发） |
| **M2** | `clampMaxTokensToContext` 的减法项去掉（不扣 `estimateContextTokens`） | RED-4 的部分用例红（长 context 不再夹小）—— **且应当只在长上下文用例上红**，这是「夹具真的有牙」的测量 |
| **M3** | `AbstractChatApi` 不替换 request（直接 `streamInternal(request, …)`） | RED-1/2/3 全红 ⇒ 证明落点确实是 R1 那一处 |
| **M4** | `PiMessagesApi.resolvesRequestOptions()` 改成 `true` | **恰 1 红**（新加的「pi-messages 不发 maxTokens」用例）⇒ 证明 R2 的豁免有牙 |
| **M5** | `responseForResponses` 的 `supportsMaxOutputTokens` 默认由 `true` 翻 `false` | **恰 1 红**（responses 那条）；azure 那条**仍绿** ⇒ 证明 P24 的「只有 openai-responses 有门」被夹具钉住 |
| **M6** | `samplingParams` 的落点从「最后」挪到 `temperature` 之前 | RED-6 的配对用例红（`max_tokens` 压不过具名字段）⇒ 证明钉的是**顺序**不是存在性 |
| **M7** | `NO_MODEL_CAP_FALLBACK` 由 `4096` 改成 `4097` | **恰 1 红**（RED-8）⇒ 这是 RED-8 唯一的牙（它先红不成立，因为今天的字面量恰好也是 4096） |

⚠️ 按 `docs/52 §12.5` 的教训，**每次变异后必须 grep 复核是否落地**（CRLF 已五次吃掉
多行模式的锚点）。

⚠️ **事先声明**：`agent-core` 的引擎夹具在本包应**零红** —— 本包只改 `ai` 的请求构建，
`agent-core` 侧的输入是同一份 `StreamOptions`（三个构造点都没动）。
这属于「**探针打在了不在测试路径上的对象**」（`docs/56 §12.9` 的第五种成因），
不是「夹具没牙」。**先声明、再测量，零红时如实记录成因。**

---

## 9. 提交计划

1. `docs(ai): A-10 请求侧选项层与 max_tokens 的设计` —— 本文件（**先行落地再动代码**）。
2. `feat(ai): 移植 pi 的上下文 token 估算器` —— `Estimate` ＋ 单元夹具（RED-5）。
3. `feat(ai): 给 max_tokens 装上生产者并夹取到上下文窗口` —— `SimpleOptions` ＋
   `AbstractChatApi` 落点 ＋ 两处豁免 ＋ `AnthropicRequestBuilder` 的 `4096L` 删除
   （RED-1/2/3/4/8、M1–M4 ＋ **M7**）。
4. `feat(ai): 把模型级 samplingParams 合并进请求体` —— 三条 OpenAI 车道 ＋ 顺序（RED-6、M6）。
5. `feat(ai): 顶层思考预算字段` —— 两个 compat 字段 ＋ 解析层 ＋ models.json ＋ 落点（RED-7）。
6. `feat(ai): Responses 的 max_output_tokens 与 compat 门` —— `supportsMaxOutputTokens`（M5）。
7. `docs(ai): A-10 闭环` —— `docs/32`／`docs/41`／`docs/46 §3-D4` 回填 ＋ `docs/48` §5 行 ＋
   **文首 banner**（B91）。

---

## 10. 登记

- **B122**：**`maxTokens` 在生产路径上没有生产者**（本包修复）。修复前的完整后果见 §4.2 ——
  一条车道发**自家发明的 4096**、五条车道**一个上限都不发**。台账 `docs/41:77` 的措辞
  （「不按 contextWindow 夹取」）**低估了它**，本包一并更正。
- **B123**：**pi 有三份 `estimateContextTokens`**（§2.2），语义真不同（正向＋时间戳守卫 vs
  反向取末条；`system` 角色计入 vs 计 0）。java 现有 `ContextUsageEstimator` 是 compaction 那份；
  本包新增的是 `utils/estimate.ts` 那份；另有 `ContextEstimator`（自造启发式、生产不可达）。
  ⇒ **三处同名，后来的包不要试图「统一」它们**。
- **B124**：**`options.maxTokens === 0` 与 `undefined` 之别被 `int -1` 哨兵吞掉**（R6）；
  `temperature` 同族且**更真实**（`0` 是合法温度，R12）。pi 用 `??`／`!== undefined` 区分。
- **B125**：**`ModelInfo.headers` 零消费者**（R11）；`samplingParams` 的**per-request 那一半**
  同样无生产者（B107 的同源）。
- **B126**：**pi 的 `openai-codex-responses` 车道完全忽略 `maxTokens`**（P17，全文件零命中）。
  java 无该车道 ⇒ 今天不适用；登记以防将来移植时「照抄别的 OpenAI 车道」。
- **B127**：**Responses 车道的 reasoning 走一条平行通道** ——`ResponsesOptions.reasoningEffort()`
  读的是 `ApiOptions.extra["reasoningEffort"]`（**调用方给的字符串**），而**不是**
  `request.reasoning()`（pi 的 `SimpleStreamOptions.reasoning`）。同一个概念两条来源，
  且 `effortString` 硬编了映射（`ResponsesMessageConverter:490-502`，代码里已有注释承认）。
  ⇒ 归 A-09 一族，本包**不动**。
- **B128**：**`thinkingBudgets` 无选项通道**（R7）；**`supportsUsageInStreaming` /
  `requiresThinkingAsText` 等零消费者 compat 字段**清单见 `docs/53 §4.4`，本包不新增。

---

## 11. 未覆盖 / 今天不可观察

- **真实 provider 车道验证**：与 B20 的 D2 同样受限（relay 无 `/v1/messages` 路由）。
  本包**不新增**该限制，也**没有解除**它。
- **目录未命中路径的可观察性**（R4）：`ModelInfo.minimal` 的 0/0 格今天走兜底 ⇒
  **本包在该路径上不改变任何字节**。⇒ 对上一条真实 provider 的「`max_tokens` 是否变化」
  取决于该模型**是否在目录里**（内置目录 / 远程目录 / models.json）。
  **实施时必须先测一次**（`pi-ai models` 或一个请求体探针）并如实记录，不许假定。
- **`samplingParams` 的 per-request 半**（B125）：`StreamRequest.extra` 生产恒 `Map.of()`
  ⇒ 没有夹具能覆盖「调用方给的 samplingParams 压过模型级」这一半。
- **`thinkingBudgets` 自定义表**（R7）：无通道 ⇒ 「自定义预算」这一支没有夹具。
- **`clampMaxTokensToContext` 在长上下文下的端到端**：夹具是**单元**级（手搓 context）＋
  wire 级（手搓 `ModelInfo`），没有一条真实长会话。R2 的豁免也只在单元/wire 级被钉。
- **Azure 与 openai-responses 在 java 里共用同一个 converter**：P24 的「只有
  openai-responses 有门」靠 `apiName` 分支实现；若将来拆成两个 converter，那道门要跟着走
  —— 本文档与 M5 是这条约束的唯一记录处。
