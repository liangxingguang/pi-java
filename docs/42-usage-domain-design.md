# 42 - 包H1：usage 域（四分量生产者 ＋ `calculateCost`）

> **来源**：`docs/41 §7.2` 第一梯队 H1 —— 「`ai` usage 域两条（四分量生产者 ＋ `calculateCost`）」，
> 报告口径 **+5.50pp**，占四条 P0 收益的 76%，且同属一个域 ⇒ **一包做完**。
> **状态**：**设计，待用户审核。** 审核通过后才写代码；实施记录续在 §10。
> **基准**：pi @ `3390bd936`（已核 `git rev-parse HEAD` ＝ 该提交、工作树干净）· pi-java @ `65d054e`
> **证据**：本文件所有 `file:line` 均已实读；pi 侧路径相对 `D:\workplaceForai\pi`，java 侧相对 `D:\workplaceForai\pi-java`。

---

## 1. 这一包解决什么

**一句话**：`cacheRead` / `cacheWrite` / `cacheWrite1h` / `reasoning` 四个分量**在生产上恒为 0/null**，
`cost` **恒为 `Cost.zero()`** ⇒ 成本永远显示 0、开 caching 时上下文占用被低估（压缩时机晚于 pi）。

**为什么它是 P0**：下游**已经在读**这些数，只是读到的是 0 ——
`ContextOverflow.java:110,120`（读 `input + cacheRead`）· `ContextUsageEstimator.java:78`（读四项和）·
`SessionState.java:329-330`（四路累加 ＋ `cost().total()`）· `SessionRunner.java:78-79`（累加 cost）·
`SessionStats`/`RunSummaryAggregator`（唯一把 cost 打成人类可读文本的两处）。

---

## 2. 命题表

### 2.1 pi 侧（行为）

| # | 命题 | 证据 |
|---|---|---|
| P1 | **usage 不是一条事件**，而是**就地写进 `output: AssistantMessage` 对象**，因此出现在之后每一次 `partial` 快照与终结 `done.message` 里 | `ai/src/types.ts:652-668`（12 个事件变体里没有 usage 变体）；`done` 载荷 `{type,reason,message}` `:663-667` |
| P2 | `Usage` 只有 `cacheWrite1h` / `reasoning` **可选**；`cost` 5 项**必填** | `types.ts:396-417` |
| P3 | `totalTokens` **无统一口径**，五条车道分三派：Anthropic/OpenAI-completions **自算四分量和**；Responses/Google **直取 provider 字段**；Mistral **优先 provider、兜底自算** | `anthropic-messages.ts:622-624`、`openai-completions.ts:1545`、`openai-responses-shared.ts:572`、`google-generative-ai.ts:240`、`mistral-conversations.ts:603-605` |
| P4 | **Anthropic 首帧保留**：`message_start` 五字段**无条件** `\|\| 0` 写入 ＋ 自算 total ＋ 计价；`message_delta` 四字段**各自 `!= null`** 才覆盖（注释逐字：*"Preserves input_tokens from message_start when proxies omit it in message_delta"*） | `anthropic-messages.ts:615-625` / `:763-787` |
| P5 | Anthropic 的 `cacheWrite1h` **只在 `message_start` 设**（取自 `usage.cache_creation.ephemeral_1h_input_tokens`），delta **不更新** | `:621`（全文件仅此一处） |
| P6 | Anthropic 的 `reasoning` 取自 `event.usage.output_tokens_details?.thinking_tokens`（**与四字段不同层**），同样 `!= null` 才写 | `:779-782` |
| P7 | Anthropic 的 `totalTokens` 重算与 `calculateCost` 在 `if (event.usage)` **块外**，**无条件执行** | `:784-787` |
| P8 | OpenAI-completions 的 `parseChunkUsage`：`cacheRead` **三路 `??` 链**（`prompt_tokens_details.cached_tokens` → `prompt_cache_hit_tokens` → 顶层 `cached_tokens`）；`cacheWrite` **单路**；**减法语义** `input = Math.max(0, prompt_tokens − cacheRead − cacheWrite)` | `openai-completions.ts:1521-1536` |
| P9 | ⚠️ **明令不从 `cached_tokens` 里减 `cacheWrite`**（注释逐字：*"Do not subtract writes from cached_tokens, otherwise spec-compliant providers are under-reported"*）——但 `cacheWrite` **要**从 `promptTokens` 里减 | `:1533-1536` |
| P10 | OpenAI-completions 的 `choice.usage` 回退：条件 `!chunk.usage && choice.usage`，且**必须 `choice` 存在** | `:566-573` |
| P11 | OpenAI-responses 的 `totalTokens` **直取** provider 值；减法用 `input_tokens_details.{cached_tokens, cache_write_tokens}`；`calculateCost` 在 `if (response?.usage)` **块外** | `openai-responses-shared.ts:559-582` |
| P12 | **Google 的 `cacheRead` 是「搬移」**：`cacheRead = cachedContentTokenCount`，同时 `input = promptTokenCount − cachedContentTokenCount`；`output = candidatesTokenCount + thoughtsTokenCount`（thought 计入 output **且**另设 `reasoning`） | `google-generative-ai.ts:231-250` |
| P13 | ⚠️ **Google 的减法没有 `Math.max(0,…)` 钳位**（与 OpenAI 两条车道不同）⇒ 理论上可产出负 `input` | `google-generative-ai.ts:233-234`、`google-vertex.ts:241-242` |
| P14 | Mistral 的 `cacheRead` 是**六路 `??` 链**（驼峰/下划线 × 三容器 ＋ 两个顶层 `num*`），再**双重钳位** `Math.min(promptTokens, Math.max(0, cached))` | `mistral-conversations.ts:536-555` |
| P15 | Mistral 的 `totalTokens` **优先 provider `total_tokens`、兜底自算**；`reasoning` **从不设置**（保持 undefined） | `:603-605`、`:599` |
| P16 | **`calculateCost` 的算法**：① 阶梯判据量 ＝ `input + cacheRead + cacheWrite`（**不含 output**）② 条件**严格 `>`**、取**最高命中档**、**整单适用** ③ **1h 缓存按 `2 × rates.input` 计价**，5m 部分按 `rates.cacheWrite` ④ **就地写回 `usage.cost` 并返回同一对象** | `ai/src/models.ts:900-920` |
| P17 | `ModelCost` 四费率**必填**、`tiers` **可选**；生成器缺项落 `0`、空 tiers **不写键** | `types.ts:941-956`、`scripts/generate-models.ts:1200-1207` |
| P18 | OpenAI 长上下文档阈值 **272000**，命中后 input×2 / output×1.5 / cacheRead×2 / cacheWrite×2 | `generate-models.ts:383, 404-419` |
| P19 | **`addUsage` 的「可选键」约定**：`cacheWrite1h`/`reasoning` **两边都 undefined 才省略** | `agent/src/harness/utils/usage.ts:14-35` 等 3 份同形 |
| P20 | 消费侧 `calculateContextTokens` 是 **`totalTokens` 优先、falsy 才自算**（**与车道写入口径不同**）；`shouldCompact` 是**严格 `>`** | `ai/src/utils/estimate.ts:18-20`、`coding-agent/src/core/compaction/compaction.ts:250-253` |

### 2.2 pi-java 侧（现状）

| # | 事实 | 证据 |
|---|---|---|
| J1 | **形状瓶颈**：`StreamPartialBuilder.emitUsage(long inputTokens, long outputTokens)` —— **只有两个 `long` 参数**，cache/cost/reasoning **无入口** | `ai/stream/StreamPartialBuilder.java:316-324` |
| J2 | 它造**两个**对象：`this.usage = new UsageInfo(i, o, null)`（先赋值）＋ 返回 `new UsageInfo(i, o, snapshot())`。**两个的 `usage` 分量都是 null** | `:321` / `:323` |
| J3 | 但 `snapshot()` **确实把 `this.usage` 装进 partial**（`AssistantMessage(..., usage, ...)`）⇒ partial 上有 `UsageInfo`，`JsonEventMapper` 正是读它 | `:93`（已实读）· `coding-agent/mode/JsonEventMapper.java:66-70` |
| J4 | **生产侧 `UsageInfo.usage()` 恒为 null** —— 非 null 只出现在测试手工构造 | 全仓 grep；`ScriptedStreams.java:45` 等 5 处测试 |
| J5 | `StreamEvent.UsageInfo.from(...)` **全仓零调用者**（含测试） | `StreamEvent.java:191-194` |
| J6 | **6 个生产发射点，全是两参数**：anthropic `:286-288` · openai-completions `:182` 与 `:254`（**两处**）· openai-responses `:247` · google `:141` · mistral `:251` · pi-messages `:125-126` | 逐条实读 |
| J7 | **Google 是唯一把 reasoning 折进 output 的车道**（`output = candidatesTokenCount + thoughtsTokenCount`）—— 与 pi **同形**，但没设 `reasoning` | `GoogleGenerativeAiApi.java:139-140` |
| J8 | **pi-messages 是唯一把 `double` 显式截断成 `long` 的车道**（`(long) done.usage().input()`） | `PiMessagesApi.java:126` |
| J9 | **`PricingInfo` 只有 2 维**（`inputPrice`/`outputPrice`），**无 cacheRead/cacheWrite 价、无 tiers、无换算函数** | `ai/model/PricingInfo.java:12` |
| J10 | `models.json` 的 `cost` 也只认两键，且 **`ignoreUnknown=true`** ⇒ 用户写的 `cacheRead`/`cacheWrite` **被静默吞掉** | `ai/provider/ModelsJsonSchema.java:87-92` |
| J11 | ⚠️ cost 只给一半时落 **`new PricingInfo(0, 0)`** 而**不是 `UNKNOWN`(-1,-1)** ⇒ 该模型被当成**「免费」而不是「未知」** | `ai/provider/ModelsJsonConfig.java:201-203` |
| J12 | **`PiLaneSink` 落 `UsageRecord` 用 `Usage.of(inputTokens, outputTokens)`** ⇒ 即使帧带全量分解也**会丢** | `agent/harness/PiLaneSink.java:436-440` |
| J13 | `Message.usageOf` 已是**三分支**：有全量用全量 / 只有计数则合成 / null 则 `Usage.of(0,0)` —— **归一逻辑已就位，只缺上游喂数据** | `ai/message/Message.java:146-157` |
| J14 | 存储层 **已能读写全字段**（JSONL codec 逐键读 8 项 ＋ `cost` 5 项；SQLite `session_stats` 四列累加） | `EntryJsonCodec.java:101-130`、`sqlite/storage/StatsRows.java:54-64` |
| J15 | `FrameNormalizer.usageOf` 是 usage 域**唯一的差分归一化器**，键序与 pi 对齐，已含 `cacheWrite1h`/`reasoning`/`cost` | `agent-core/.../conformance/FrameNormalizer.java:262-285` |

### 2.3 差异的形状：一句话

> **pi-java 的 usage 走「两个 `long`」；pi 的走「消息上的一个 `Usage` 对象」。**
> pi-java **多一条事件**（`StreamEvent.UsageInfo`，pi 没有），但那条事件**只搬两个数**，
> 而**归一与落盘的下游其实全都齐了**（J13/J14/J15）——**缺的只是最上游那一段**。

⇒ 本包的形状不是「造一套新管线」，是**把最上游的两个 `long` 加宽成一个 `Usage`，并逐车道移植 pi 的归一语义**。

---

## 3. 被推翻的命题

| 命题 | 结论 |
|---|---|
| 「`ModelCapability.THINKING` 不可达」（`docs/41 §1.7` 已登记，此处复述） | **不成立** —— 它在生产被读（`OpenAICompletionsApi.java:544` 做 deepseek `reasoning_content` 补空串的第二道门）。与本包无关，但**别顺手改它** |
| 「`usage.cacheRead` 缺的是「读 provider 字段」」 | **不完整** —— 缺的还有**归一语义**（三路链/六路链、减法、钳位）。只读字段不做归一，会得到**与 pi 不同的数** |
| 「下游没准备好」 | **反了** —— 下游（`ContextOverflow`/`ContextUsageEstimator`/`SessionState`/`SessionRunner`/存储/差分器）**全都已就位并在读**，读到的是 0 |

---

## 4. 审计（顺带发现，**不在本包修**）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| A1 | **L5 差分结构上跑不到 usage** —— 14 个剧本**零 `usage` 字段**；`ScriptedStreams` **从不发 `UsageInfo` 帧**，只在 partial 上挂 `ZERO_USAGE` ⇒ 两侧 usage **恒零且逐字相同** | `conformance/scripts/*.json`（grep 无）；`ScriptedStreams.java:34-35,45` | **本包的验证不能靠 L5**（§8.3）；是否扩展剧本见 §8.0 裁决点 C |
| A2 | **`models.json` 半价 ⇒ 免费**（J11） | `ModelsJsonConfig.java:201-203` | **本包顺手修**（同属 cost 链，且是一行） |
| A3 | **`PiLaneSink` 落 `UsageRecord` 丢全量**（J12） | `PiLaneSink.java:436-440` | **本包必修** —— 不修则四分量在会话账上仍是 0 |
| A4 | `StreamEvent.UsageInfo.from(...)` 零调用者（J5） | `StreamEvent.java:191-194` | 本包若加宽管线，**它就是现成的入口**；否则登记 |
| A5 | `pi.ai.usage.*` 遥测属性在 pi **有 schema 声明、生产零发射点** | pi `harness/telemetry.ts:94-103` | 非目标（R5 排除面） |
| A6 | `ModelMetadata`（`pi-java-protocol`）**main 侧无生产者** | 唯一 `new` 在测试 | 属 C12/R5 面，登记 |

---

## 5. 待核 / 裁决点

见 §8.0（六个）。其中 **B**（目录价格数据）与 **C**（L5 剧本扩展）**会改变本包的规模**，请优先看。

---

## 6. 明确不做（含理由）

| 不做 | 理由 |
|---|---|
| **`cache-warmer` 子系统**（pi `core/cache-warmer.ts` 453 行 ＋ `settings.cacheWarming` 三档） | 它**消费** `calculateCost`，但**自己是独立子系统**（`docs/41 §2.2` 已单列）。本包只交付 cost 计算能力，预热另立包 |
| **`usage-totals` 的按 provider/model 成本分解**（pi `usage-totals.ts:31-74`） | 纯展示层，且依赖 `/session` 命令面（`docs/41 §2.4`） |
| **`cache-stats` 命中率/浪费分析**（pi `core/cache-stats.ts`） | 同上；且它读的 `cost.cacheRead` 在本包后才有值 ⇒ **本包是它的前置** |
| **Bedrock / Vertex / Codex 三条 wire** | `docs/41 §1.3` 已判权重 1；车道本身不存在，不在本包 |
| **`pi-messages` 车道的 usage 语义** | 它不是 pi 的车道（`docs/41 §2.4` 记「pi 无此车道」）⇒ 只做**形状一致性**（不截断 `double`），不追对齐 |
| **`cacheWrite1h` 的 Bedrock 求和式拆分** | 车道不存在 |

---

## 8. 实施稿（**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决点 | 我的建议 | 影响 |
|---|---|---|---|
| **A** | `PricingInfo` 怎么扩？①**扩到 6 组件**（4 费率 ＋ `tiers`）＋ 保留 2 参便捷构造器 ⇒ 现有 ~40 处调用点**零改动**；② 新增 `ModelCost` 与 `PricingInfo` 并存 | **①** —— pi 的 `ModelCost extends ModelCostRates` 本就是一个类型，并存会造出两个真值源 | 小 |
| **B** | **目录价格数据**：`BuiltinCatalog`（13 条）＋ `ModelData`（22 条）现在只有 input/output 价。补 cache 价需要数据源（pi 由 models.dev 生成） | **不补，但把「未知」表达对** —— 即 cache 费率缺省 `-1`（未知）而不是 `0`（免费），并**登记为缺口**。理由：编价格是**数据工作**不是对齐工作，且编错比缺更糟 | **中**：不补 ⇒ `cost.cacheRead`/`cost.cacheWrite` 仍恒 0，但 `cost.input`/`cost.output` 立刻有值 |
| **C** | **L5 剧本要不要加 `usage` 字段**（改两侧孪生：`conformance/scripts/*.json` ＋ `ScriptedStreams` ＋ pi 侧 `conformance/pi/run.test.ts`） | **要**（另立一步）—— 这是**唯一能差分守护 usage 形状**的手段（A1）。但它是**夹具改动**，按 `docs/38` 的口径必须**夹具与生产同路**，且要先修 P8 那处已知的 thinking 不对称 | **大**：可能引出独立的夹具包 |
| **D** | **Google 的「无 `Math.max` 钳位」（P13）照抄不照抄？** | **照抄** —— 判据是「行为和 pi 一样」，pi 没有钳位就是没有。**但要写进 javadoc 说明这是刻意偏差于"更安全"的写法** | 小 |
| **E** | `StreamEvent.UsageInfo` 事件**保留为扩展**（pi 没有它）还是收进 partial？ | **保留** —— 收进 partial 会动 RPC 线格式（`JsonEventMapper` 的顶层 usage 来自 `partial.usage()`）与 L5 帧，属**另一次行为变更**，不在本包 | 中 |
| **F** | **`PricingInfo(0,0)` → `UNKNOWN`**（A2）在本包修还是另立？ | **本包顺手修** —— 同属 cost 链、一行、且不修则 B 的「未知 vs 免费」讲不通 | 小 |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-ai` | **数据模型 ＋ 计价**：`PricingInfo` 扩 6 组件（裁决 A）＋ `CostCalculator.calculateCost(PricingInfo, Usage)`（P16 逐条）＋ `ModelInfo` 接线 |
| **2** | `pi-java-ai` | **管线加宽**：`emitUsage(Usage)`（保留 `(long,long)` 薄封装）⇒ `UsageInfo.usage()` 生产上非 null（J1/J2/J4） |
| **3** | `pi-java-ai` | **Anthropic 车道**：首帧五字段 ＋ delta 逐字段 `!= null` ＋ `reasoning` 走 `output_tokens_details` ＋ `cacheWrite1h` 只在 start ＋ total/计价在块外（P4–P7） |
| **4** | `pi-java-ai` | **OpenAI-completions 车道**：`parseChunkUsage`（三路链 ＋ 减法 ＋ `Math.max`）＋ `choice.usage` 回退 ＋ 请求侧 `stream_options.include_usage`（P8–P10） |
| **5** | `pi-java-ai` | **OpenAI-responses ＋ Google ＋ Mistral 三条车道**：P11 / P12–P13 / P14–P15（三条各自独立、形状小，可合一步） |
| **6** | `pi-java-ai` ＋ `pi-java-agent-core` | **下游接线**：`PiLaneSink` 落 `UsageRecord` 改传全量（A3）＋ `models.json` cost 扩键（J10）＋ 半价→UNKNOWN（J11，裁决 F） |
| **7** | 夹具 | **L5 剧本加 `usage`**（裁决 C，若批准）—— 两侧孪生同步 |

> 步 3–5 **互不依赖**（不同车道），可按任意顺序；步 6 依赖步 1–5。

### 8.2 精确改动（形状）

```java
// 步 1：PricingInfo 扩 6 组件（4 费率 + tiers），2 参便捷构造器保留 ⇒ 现有调用点零改动
public record PricingInfo(double inputPrice, double outputPrice,
                          double cacheReadPrice, double cacheWritePrice,
                          List<CostTier> tiers) {
    public record CostTier(double inputTokensAbove, double inputPrice, double outputPrice,
                           double cacheReadPrice, double cacheWritePrice) {}
    /** 旧形状：cache 费率未知（-1），无阶梯。 */
    public PricingInfo(double inputPrice, double outputPrice) {
        this(inputPrice, outputPrice, -1, -1, List.of());
    }
    public static final PricingInfo UNKNOWN = new PricingInfo(-1, -1, -1, -1, List.of());
}

// 步 1：计价（P16 逐条；就地写回 usage.cost）
public static Usage.Cost calculateCost(PricingInfo pricing, Usage usage) { ... }

// 步 2：管线加宽
public StreamEvent.UsageInfo emitUsage(Usage usage) { ... }        // 新
public StreamEvent.UsageInfo emitUsage(long in, long out) {        // 旧形状保留
    return emitUsage(Usage.of(in, out));
}
```

### 8.3 测试计划（**先红**）

⚠️ **L5 结构上覆盖不到本包**（A1）⇒ 全部靠单元 ＋ 端到端桩。**每条都要先证明它会红。**

| # | 夹具 | 断言 |
|---|---|---|
| T1 | `calculateCost` 表驱动（纯函数） | 四费率 × 四分量；**1h 缓存 2× 输入价**；`tiers` 边界（`==` 不命中、`>` 命中、取最高档、整单适用） |
| T2 | Anthropic 桩流：`message_start` 带 input=100/cacheRead=20，`message_delta` **不带** `input_tokens` | 终局 `usage.input() == 100`（**首帧保留**）、`cacheRead == 20` |
| T3 | Anthropic 桩流：`message_delta` 带 `input_tokens = 0` | `usage.input() == 0`（**`0` 是合法值，必须覆盖** —— 这条专打 `!= null` vs truthy） |
| T4 | Anthropic 桩流：`message_start` 带 `cacheWrite1h`，delta 带 `cache_creation_input_tokens` | `cacheWrite1h` **保持首帧值**（P5） |
| T5 | OpenAI-completions 桩 chunk：`prompt_tokens=1000`、`cached_tokens=400`、`cache_write_tokens=100` | `input == 500`、`cacheRead == 400`、`cacheWrite == 100`（**减法**）；且 `cacheRead` **不减** `cacheWrite`（P9） |
| T6 | OpenAI-completions 桩 chunk：`prompt_tokens=100`、`cached_tokens=400`（越界） | `input == 0`（`Math.max` 钳位） |
| T7 | OpenAI-completions 桩：usage 只出现在 `choice.usage` | 回退生效（P10） |
| T8 | Google 桩 chunk：`promptTokenCount=100`、`cachedContentTokenCount=400` | `input == -300`（**照抄 pi 的无钳位**，裁决 D）—— 这条同时是 D 的钉子 |
| T9 | Mistral 桩：六种容器名各一 ＋ 越界值 | 六路链都命中；双重钳位生效 |
| T10 | 端到端：桩流喂非零四分量 ⇒ 走完 `PiLaneSink` | `SessionState.costTotal() > 0`、落盘 `UsageRecord.usage()` 四分量非零（**A3 的钉子**） |
| T11 | `models.json` 只写 `cost.input` | `pricing().isKnown() == false`（**A2 的钉子**：半价 ⇒ 未知，不是免费） |

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| 探针 | 变异 | 预期命中 |
|---|---|---|
| M1 | `parseChunkUsage` 的 `input` **不减** `cacheWrite` | T5 |
| M2 | Anthropic delta 改成**无条件覆盖** | T2/T3 |
| M3 | `cacheWrite1h` 改在 delta 也更新 | T4 |
| M4 | `calculateCost` 的 1h 用 `rates.cacheWrite` 而非 `2 × rates.input` | T1 |
| M5 | 阶梯判据 `>` 改 `>=` | T1（边界那条） |
| M6 | Google 加 `Math.max(0, …)` 钳位 | T8 |
| M7 | `PiLaneSink` 落 `UsageRecord` 仍用 `Usage.of(i, o)` | T10 |
| M8 | `Math.max(0,…)` 钳位去掉（OpenAI 两条） | T6 |

### 8.5 未覆盖（预登记）

- **目录价格数据**（裁决 B 若选「不补」）⇒ cache 费率为未知，`cost.cacheRead`/`cost.cacheWrite` 仍恒 0。
- **Bedrock / Vertex / Codex 三条 wire**（车道不存在）。
- **`cacheWrite1h` 的真实来源**：只有 Anthropic 报，且需真 key 才能端到端验 ⇒ 靠桩。
- **`pi.ai.usage.*` 遥测**（R5 排除面）。
- **`cache-warmer` / `cache-stats` / `usage-totals` 三个消费者**（§6 已列不做）。

---

## 9. 下一步

1. **你审本文件**（重点：§8.0 六个裁决点，尤其 **B** 与 **C** —— 它们改变本包规模）。
2. 审核通过 ⇒ 按 §8.1 步 1 起实施，**每步一个 commit**，实施记录续在 §10。
3. 实施完成后回填 `docs/41 §1`（把已闭环的条目从清单里划掉）与 `docs/32` 台账。

---

## 10. 实施记录（2026-09-21–22，七步七提交，包已闭环）

| 步 | Commit | 先红（实测） | 变异探针 ⇒ **实测红集** |
|---|---|---|---|
| 1 | `5b703a9` | `CostCalculatorTest` 14 例先红＝编译失败 | M4（1h 丢 `×2`）恰 2；M5（`>`→`>=`）恰 1 |
| 2 | `655e1cd` | 2 例先红＝编译失败 | usage 分量强置 null ⇒ 恰这 2 |
| 3 | `d7fedfb` | 8 例先红 6/8 | M2 恰 3；M3 恰 2；M2b 恰 1。⚠️ M3 第一版哑弹（行为没变⇒0 红），重做 |
| 4 | `2ea3e9a` | 9 例先红 8/9 | M1 恰 2；M8 恰 1；M9（`??`→`\|\|`）恰 1 |
| 5 | `8c7fb39` | 三套 25 例先红 20 | 九针全恰中（P1–P4、M1、M2fix、M3、M4、M5fix）。⚠️ 哑弹两次：`!"".equals()` 不实现 JS 假值⇒0 红；`if(false)` 整块关⇒10 红不证位置 |
| 6 | `29102a4` | T10 恰 1 红（cacheRead 0≠40）；cost 六条先红 5＋guard 1 条**先绿**（如实记录） | M7、N1、N2、N3 各恰 1。⚠️ M7 探针第一版编译失败（import 已删）——**探针须先证明「改到了、跑起来了」才有牙** |
| 7 | `7c3db0d` | ConformanceTest 15 跑恰 S15 红（缺 pi 真相）⇒ 落真相同跑 15/15 绿 | Q1（桩恒零）恰 1；Q2（丢 `cacheWrite1h`）恰 1；Q3（`num()` 整数归一失效）**15/15 红** |

### 10.1 步 7（裁决 C）执行纪要

- **锚点验证**：重建 `pi-v0.85.1` worktree（detached）后重生成 S1–S14，剥 CRLF 与已提交基线
  **逐字节相同** ⇒ 孪生编辑（`usage` 为默认参数的可选形参）对既有剧本行为守恒。
- **S15 钉三类形状**：全部分量非零；**可选键在场**（钉 `usageOf` 的「非空才带」＝pi 侧
  stringify 丢 undefined 的对偶）；cost 全取**二分小数** —— 新发现：JS 与 Java 的小数格式在
  `<1e-3` 分叉（`0.00002` ≠ `2.0E-5`），二分小数的最短往返表示两侧一致，写进 `CostScript` javadoc。
- **裁决 C 预告的「thinking 不对称」已核实**：实质是 pi 侧剧本桩对 thinking 块**不发任何生命周期
  事件**（`run.test.ts` 的 forEach 只有 text/toolCall 分支），Java 桩发 `thinking_start/end`
  ⇒ 含 thinking 的剧本结构上必红。**与 usage 正交**（`usage.reasoning` 只是数字）⇒ S15 不依赖它。
  该不对称登记为剧本夹具盲区（续记于 `docs/29 §10`），另裁。
- **A4 核对**：`StreamEvent.UsageInfo.from(...)` 在生产上仍零调用者（步 2 走的是
  `emitUsage(Usage)` ⇒ `new`）⇒ 按 §5 A4 预登记，已记入 `docs/41 §1.5`。

### 10.2 回归与门

| 轮 | ai | agent-core | coding-agent | evals |
|---|---|---|---|---|
| 步 2 / 3 / 4 | 468 / 476 / 485 | 487 | 266 | 35 |
| 步 5 / 6 | 516 | 488 | 266 | 43 |
| 步 7 | 516 | **489**（+S15） | — | — |

全步 checkstyle 干净；`-am` 全程在场（~/.m2 旧构件假绿的教训在本包又兑现一次：步 6 不带
`-am` 直接编译失败）。

### 10.3 六裁决落点

A ✅步 1（5 组件＋便捷构造器）；B ✅步 1/6（−1 未知哨兵，`CostCalculator.rate()` 把 −1 按 0 计价、
哨兵只驱动 `isKnown()` 显示；目录 cache 价数据不编）；C ✅步 7（S15）；D ✅步 5（Google 无钳位照抄，
`negativeInputIsNotClampedAwayAsPiDoes` 钉住）；E ✅不动（UsageInfo 事件保留为扩展）；
F ✅步 6（半价⇒UNKNOWN、缺席⇒免费、tier 残缺⇒拒载）。

### 10.4 本包遗留登记（不重开，指路）

1. 目录 cache 价数据（裁决 B 的数据面）——补数据是独立工作。
2. `cacheWrite1h` 真 key 端到端（§8.5）。
3. thinking 剧本不对称（§10.1，夹具层，另裁）。
4. `UsageInfo.from` 零调用者（§10.1，死码，R5 面）。
