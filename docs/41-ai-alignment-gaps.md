# 41 pi-ai 层对齐盘点（剩余缺口清单）

> **状态：📝 盘点稿（2026-10-09）。** 不是设计稿 —— 只回答「pi-ai 层还剩什么没对齐」，
> 逐包设计在各自文档里出。
>
> **进度**：§1.1 的 `ContextOverflow` / `RetryableError` 两条**已闭环**（`docs/43`，2026-10-09）；
> `xai` provider 端点**已闭环**（`docs/44`，2026-10-10）⇒ w2 由 5 条降为 **2 条**
> （目录规模 / 中段工具内联）。
>
> **数据源**：`docs/map/02-ai.md`（基准 pi-java `351d199` / pi `200387122`，2026-10-04）
> ＋ `docs/04-open-items-register.md` §3.2。
> **本稿新增**：对 w2 五条做了**逐条取证**（读 pi `git show 200387122:<path>` 与 pi-java 当前工作树），
> 并对 §3.2 的 43 条做了**过期抽查**。取证过的条目在表里标 ✅；
> 直接沿用地图判定的标 ⤵（那些我未逐一重测）。

---

## 0. 一句话

**加权 83.48%，全部 32 个权重 3 单元已对齐** —— 主流车道的请求 / 响应 / 计量全通。
剩下的不是「能不能用」，而是**广度**：w2 两条 ＋ w1 十五条 ＋ 4 条 wire 适配器。
计分母的真缺口 **22 个单位**，全补完 **+16.52pp**。

> ⚠️ 本稿的判定以 `docs/map/02-ai.md` 的基准为准；pi-java 侧此后仍在前进
> （`ai` 模块最近一次改动 `224a1fb3`，2026-10-04），个别条目可能已过期 ——
> 见 §5 的过期抽查。

---

## 1. 计分母的缺口（22 个单位）

### 1.1 w2 —— 边际最大的一块（2 条，补完 **+1.76pp**）

| 单元 | pi 侧证据 | pi-java 现状 | 差距 |
|---|---|---|---|
| ⬜→✅ `xai` provider 端点 | `providers/xai.ts`（`api: openAIResponsesApi()`、`baseUrl: https://api.x.ai/v1`）＋ `providers/data/xai.json`（**4 模型**，由 `scripts/generate-models.ts` 生成） | **只有 OAuth**（`auth/OAuthProviders.java:30` device code）；**无 provider 类、无 wire** | 主流 7 家里唯一没上车的端点。⚠️ `CompatResolver.java:79/:98` 已有 `provider.equals("xai")`／`isGrok` 分支 ⇒ **compat 层认识 xai，但没有车道可用**。**✅ 已闭环 2026-10-10**（`docs/44`，见 §12）。⚠️ 本行原写「14 模型」是**取证错**（那个 14 是 `xai.models.ts` 的**行数**），实测 `data/xai.json` 是 **4 条** |
| ⤵ 内置目录规模 | 42 个 provider 由 `generate-models.ts` 生成 | `catalog/BuiltinCatalog.java` 手写 **24 个模型**；`B97` 另有 3 个 (provider,modelId) 与 pi 对不上 | 结构性差异：手写常量 vs 生成目录 |
| ⬜→✅ `ContextOverflow` z.ai CN 模式 | `utils/overflow.ts:38-39`：`/prompt (?:is )?too long/i` ＋ **`/prompt exceeds max length/i`（CN endpoint）** | `utils/ContextOverflow.java` 26 条模式已逐条移植，**独缺第二条**（`prompt exceeds max length`） | **恰 1 条模式**。其余 z.ai 形状（`model_context_window_exceeded`、静默溢出）都已落。**✅ 已闭环 2026-10-09**（`docs/43`，见 §12） |
| ⬜→✅ `RetryableError` 新模式 | `utils/retry.ts:26-90` 白名单 | `utils/RetryableError.java` 已落 40+ 条，**缺 3 条**：`model is at capacity`（:30）、`subscription_sharing_usage_unavailable`、`subscription_sharing_user_unavailable`（ChatGPT 登录路） | ⚠️ **地图记「两条」，实测为 3 条** —— 多出的 `model is at capacity` 是逐条 diff 才看见的。**✅ 已闭环 2026-10-09**（含配额排除表同族那条，见 `docs/43` §12） |
| ✅ Anthropic 中段工具内联（`B165`） | `api/anthropic-messages.ts:195` `INLINE_TOOLS_BETA = "inline-tools-2026-09-15"`；`:1346-1351` 发 `tool_addition`／`tool_removal`，工具**按值内联**在 `{type:"tool_definition", definition}` 里 | `protocol/AnthropicRequestBuilder.java` 有 `defer_loading`（:236）、`DEFERRED_TOOL_PLACEHOLDER`、`tool_reference`（:483） | **旧形状**：`tool_reference`（按名引用）表达不了 pi 的**同名重定义**（pi 注释 `:1137` 明写这是新形状的动机） |

### 1.2 w1 —— 广度（15 条，补完 **+6.60pp**）

**模型 / 目录元数据（8）** ⤵（除注明外均未逐一重测）

| 单元 | pi-java 现状 |
|---|---|
| 统一模型类型系统（92） | 缺 |
| 结构化分类 API（94） | ✅ 全仓 grep `classifier\|Classifier` **零命中**（端点 `typesafe` 本身权 0，但**能力**计分母） |
| 图像模型目录 / 注册表（33） | ✅ 只有 `BuiltinCatalog` 内 `imageModel(...)` 若干 ＋ `provider/builtin/OpenRouterImagesProvider.java` |
| `responseModel` / `responseId` / `thinkingLevel` / `diagnostics`（37） | 缺；`B148` 记「真消费者未接」 |
| `Model.promptCache`（86） | ✅ 只有注释提到（`CompatResolver.java:114`），无字段 |
| `MistralConversationsCompat`（87） | 缺 |
| `Model.inputLimits`（90） | ✅ 全仓 grep `inputLimit` **零命中** |
| `samplingParamsByThinkingLevel`（91） | ✅ 只有 `SimpleOptions.samplingParamsOf`（模型级那份），无 per-thinking-level |
| `ToolResultMessage.nestedCalls`（93） | ✅ 全仓 grep `nestedCalls` **零命中** |

**鉴权（3）** ⤵

| 单元 | pi-java 现状 |
|---|---|
| OAuth `openai-chatgpt`（95） | 缺（pi 侧 `auth/oauth/openai-chatgpt.ts` 是换锚新增） |
| Anthropic OAuth copy-code 登录（96） | 缺 |
| Anthropic workload identity federation（98） | 缺 |

**横切（4）** ⤵（均经 grep 确认零命中）

| 单元 | pi-java 现状 |
|---|---|
| `transport`（74） | ✅ 零命中 |
| `session-resources`（78） | ✅ 零命中 |
| `onProviderStreamEvent`（100） | ✅ 零命中 |
| `ToolUseContent.namespace`（6） | ✅ 零命中 |

### 1.3 wire 适配器（4 条，计分母）

`bedrock-converse-stream`（1373 行）· `google-vertex`（554）· `openai-codex-responses`（1697）· `cloudflare-ai-binding`（90）⤵

> ⚠️ **口径本身值得再裁决一次**：这四条的**端点是权重 0**（R2 长尾，pi-java 没有），
> 但地图把 **wire 协议能力**单独计进了分母。按 R2 的精神，若「端点都判非目标」，
> 对应的 wire 是否也该一并出分母？（对照 `docs/map/02-ai.md` §「逐条复核说明」第 5 条：
> 「能力面与端点分开计」的既有裁决 —— 那条是针对**分类器**的，未必适用于 wire。）

---

## 2. 不进分母的缺口（29 条，R2/R5 已裁决）

**25 个长尾 provider 端点** ＋ **4 条对应鉴权**（`OAuth radius`、`google-vertex ADC`、
`amazon-bedrock 多凭证源`、`OAuth meta`）。

22 个长尾 provider：`amazon-bedrock` `azure-openai-responses` `baseten` `cerebras`
`cloudflare-ai-gateway` `cloudflare-workers-ai` `fireworks` `github-copilot` `google-vertex`
`groq` `huggingface` `kimi-coding` `nvidia` `openai-codex` `opencode` `opencode-go`
`qwen-token-plan` `qwen-token-plan-individual` `together` `vercel-ai-gateway`
`xiaomi-token-plan-ams` `xiaomi-token-plan-sgp`；＋ `meta` / `radius` / `typesafe`。

> 这是最大的一堆（29/54 条缺失），但**已按你的 R2（主流 7 家之外）与 R5（不可达）排除**。
> ⚠️ 分母口径提醒：R2 裁的是「pi-java 没有的端点」= 非目标；**主流 7 家是
> Anthropic / OpenAI / Google / xAI / DeepSeek / Mistral / OpenRouter** ——
> `xai` 在列，故它是 w2 **计分母**的（见 §1.1）。

---

## 3. 存疑 20 条（形状差；多半是刻意设计）

| 分组 | 条目 |
|---|---|
| Provider / 适配器（2） | `ollama` provider（**pi-java 独有**）· `pi-messages` wire 线格自造（A2c 后部分向 transcript 形状对齐，未逐帧比对） |
| ContentBlock（2） | `UrlImageContent`（`ContentBlock.java:97`，pi 无）· `DiffContent`（`:148`，pi 无） |
| StreamEvent（3） | `error` 载荷（pi 载 `AssistantMessage`，java 载 `Throwable` + `partial`）· `UsageInfo` 事件（`StreamEvent.java:247`，pi 无）· signature 多发 `ThinkingDelta`（台账 `C8`） |
| 模型目录 / 元数据（5） | `Model.input` 形状（pi 数组 vs `ModelCapability`＋`supportsImageInput`）· `Model.compat` 28/52 分量 · `Model.reasoning` → `ModelCapability.THINKING` · `ToolResultMessage.usage`/`details`（usage 无生产者）· `JsonObject`/`JsonRepresentation`（刻意以 `Map` + Jackson 表达） |
| 鉴权 / env（3） | 多 profile 凭证（`AuthProfileManager.java`，**pi-java 扩展**）· `models.json` 自定义 provider（与 pi `createProvider` 形状不同）· 共享 OAuth 回调服务器 / 登录页（形状不同、行为近似） |
| 横切（4） | `PiMessagesApi` thinking 线格（`B18`）· `assistant-message-frame`（对应物在 `pi-java-web`/`pi-java-protocol`，跨模块）· `DeferredHandle`（两侧同状无生产者）· lazy `lazyApi`/`lazyStream`（java 用 `ProviderFactory`/`Protocol`）· strict 工具默认 ＋ Anthropic 不支持关键字拒绝 |

> 这一档**不建议当缺口清**：其中多数是「pi-java 有、pi 无」（本仓扩展）或
> 「两侧形状不同、行为近似」。真要收口，得**先定「形状差算不算偏差」的口径**。

---

## 4. 最细一层账：`docs/04` §3.2（43 条）

粒度比地图细，是**请求选项 / Provider 协议面**的登记。地图管「能力单元」，台账管「同一个单元里的字段级差异」。

挑几条代表：

| # | 条目 | 一句话 |
|---|---|---|
| `B21` | ~~thinking 开关的 provider 形状面~~ | ✅ **已销号**（已过期）：多形状随 `A-09` 落，见 §5 |
| `B121` | `AssistantMessage.stopReason()` 是 `String` 非闭集 | pi 是 `StopReason` 联合类型；Java 自由字符串，拼错无人察觉 |
| `B124` | `maxTokens: 0` 与缺席之别被哨兵吞掉 | pi 用 `??`／`!== undefined` 区分；Java `int` ＋ `-1` 哨兵 |
| `B128` | `thinkingBudgets` 无选项通道 | pi `types.ts:359` 真字段（`adjustMaxTokensForThinking` 消费）✅ **确认仍缺**，见 §5 |
| `B158` | 输出截断字节预算差一倍 | Java `TruncationUtils.DEFAULT_MAX_BYTES = 100_000`（`TruncationUtils.java:17`），pi 主流 50KB ⇒ **模型可见文案不同** |
| `B159` | `shouldStopAfterTurn` 更名 finishTurn 且语义变 | pi 从布尔改 `{action: continue/end}`，能强制多跑一轮 |
| `B107` | 两条请求选项通道恒空 | `ApiOptions.java:43`／`StreamRequest.java:144` 都传 `Map.of()` |
| `B139` | 嵌套 record 经 Json mapper 序列化成空对象 | mapper 关 GETTER；`Retry`/`ProviderRetry` 已修，其余嵌套 record 待取证 |

---

## 5. ✅ §3.2 过期抽查（**2026-10-09 已逐条回测完毕**）

四条疑似全部回测，结论如下（台账已按此回填，见 `docs/04`）：

| # | 原台账原文 | 实测 | 裁决 |
|---|---|---|---|
| `B21` | 「Java 只有 `thinking.budgetTokens` 一种」 | `catalog/ThinkingFormat.java` **11 档枚举**；`protocol/ThinkingFormatWriter.java:84` **exhaustive switch、无 default（编译器守十一臂）**，生产调用点 `OpenAICompletionsMessageConverter.java:256` | ✅ **确已过期** —— **已从台账移除**（多形状随 `A-09` 落，唯一残留 `B131` 已单独在册） |
| `B98` | 「约 40 个零消费者 compat 字段」 | 逐字段对读 pi 三个 compat 接口（**40 字段**）vs `ModelCompat`（**32 组件**）⇒ 真缺口 **8 个**：`requiresToolResultName` · `requiresAssistantAfterToolResult` · `requiresThinkingAsText` · `supportsUsageInStreaming` · `zaiToolStream` · `vllmPriority` · `vercelGatewayRouting` · `allowedFallbackModels`（每个都有 pi 消费点） | ⚠️ **文字误记，缺口真实** —— 「40」是 **pi 的字段总数**，不是缺口数。**已改写该行** |
| `B103` | 「Anthropic 会话亲和头族**未落**」 | 组件、探测器、模型目录全在；Java **只接了一条车道**（`OpenAIResponsesApi.java:82`），pi 是**三条**（`anthropic-messages.ts:1040`／`openai-completions.ts:777`／`openai-responses.ts`） | ⚠️ **半闭** —— 不是「未落」，是「**只落 1/3 条车道**」。**已改写该行** |
| `B128` | 「`thinkingBudgets` 无选项通道」 | ✅ **确认仍缺**：全仓非注释命中**零**；`SimpleOptions.java:213/:223` 只在 **javadoc 引用** pi 的写法；pi `types.ts:359` 是**真字段**（`adjustMaxTokensForThinking` 消费，`anthropic-messages.ts:965`） | ✅ **保留**（真缺口，不是过期） |

> **回测结论**：4 条里 1 条确已过期（`B21`）、1 条文字误记但缺口真实（`B98`）、
> 1 条半闭（`B103`）、1 条属实（`B128`）。**没有一条是「台账过时但缺口不真实」** ——
> 但**两条的行文会把工作量算错**（`B98` 高估 5 倍、`B103` 高估为「整族未落」）。
> 教训：**台账的行文与实际缺口要分开核** —— 行文错了不等于条目该销。

### 5.1 ⚠️ B98 的 8 个字段：不是「零消费者」，是「Java 缺字段」

旧行写「**零消费者** compat 字段」，读起来像「字段在、没人读」。实测相反：
**这 8 个字段 Java 根本没有**，而 pi **有标注且有消费点**。即行为面真的少 8 处：

| 字段 | pi 消费点 | 缺了会怎样 |
|---|---|---|
| `requiresToolResultName` | `openai-completions.ts:1429` | tool 结果消息不补 `name` ⇒ 部分后端拒 |
| `requiresAssistantAfterToolResult` | `:1241,1295,1451` | tool 结果后不插 assistant 空消息 ⇒ 部分后端拒 |
| `requiresThinkingAsText` | `:1323` | thinking 块不转 `<thinking>` 文本 ⇒ 该路后端收不到思考 |
| `supportsUsageInStreaming` | `:834` | 恒发 `stream_options.include_usage`（Java `:230-231` 已恒发 `includeUsage(true)`）⇒ **对不支持的端点多发字段** |
| `zaiToolStream` | `:857` | 不发顶层 `tool_stream` ⇒ z.ai 工具流无增量 |
| `vllmPriority` | `:873` | 不发顶层 `priority` ⇒ vLLM 调度优先级不可控 |
| `vercelGatewayRouting` | `:993` | 不发 routing ⇒ Vercel 网关路由不可控 |
| `allowedFallbackModels` | `anthropic-messages.ts:212,674,1281` | 不退到允许的回退模型 |

---

## 6. 建议的推进次序

按**边际 ÷ 可移植成本**排：

| 序 | 内容 | 边际 | 理由 |
|---|---|---|---|
| **①** | `ContextOverflow` 补 1 条模式 ＋ `RetryableError` 补 3 条 | 计入 w2 簇 | **成本最低** —— 纯字符串表，都是逐条移植，无架构面；两条合一个包。**✅ 已闭环 2026-10-09**（`docs/43`） |
| **②** | `xai` provider 端点 | 计入 w2 簇 | 纯补车道：`openai-responses` wire **已存在**，只需 provider 类 ＋ 模型目录（可手写 14 条或接生成脚本）；OAuth 已落。**✅ 已闭环 2026-10-10**（`docs/44`）—— 目录实测 **4 条**（非 14） |
| **③** | `B165` Anthropic 中段工具内联 | 计入 w2 簇 | 依赖 `inline-tools-2026-09-15` 形状，改动集中在 `AnthropicRequestBuilder`；**与 ①② 无耦合** |
| **④** | §3.2 逐条回测（先清过期账） | — | 不产码，但**解锁**后续：避免做到已完成的活；建议**在 ①②③ 之前或并行**做 |
| —— | w1 十五条 / 4 条 wire | +6.60pp / 计分母 | 广度活，单条边际低，宜搭车不宜单开包 |

**①② 均已闭环**（① `docs/43`、② `docs/44`；两者各自独立成包），**③ 单独一包**（协议形状）。

---

## 7. 台账影响

| # | 条目 |
|---|---|
| —— | 本稿**不新增**台账条目（盘点稿）。 |
| `B165` | 已在册，本稿补 `inline-tools-2026-09-15` 的 pi 证据。 |
| `B21` | ✅ **已销号**（2026-10-09 回测：确已过期，多形状随 `A-09` 落）。 |
| `B98` | ⚠️ **已改写**：真缺口是 **8 个字段**（不是「约 40 个零消费者字段」）。 |
| `B103` | ⚠️ **已改写**：半闭（只落 1/3 条车道，不是「整族未落」）。 |
| `B128` | ✅ **保留**：回测确认真缺口。 |
| `B148` | 与本稿 §1.2 的 `responseModel`/`diagnostics` 同一条。 |

---

## 8. 取证方法（复现用）

1. **pi 侧一律 `git show 200387122:<path>`**（本地工作树可能停在别的提交，直接读会静默取到旧状态）。
2. **pi-java 侧直接读工作树**（即当前 HEAD）。
3. 逐条 diff 用 `grep -n`／`sed -n` 对读两边的模式表、字段表、常量表。
4. ⚠️ 行号会漂：pi 侧引用优先给**文件 + 关键行**；无法稳妥定位的只给文件。
