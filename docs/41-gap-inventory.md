# 41 - 模块缺口清单与补全计划

> **这份文件回答什么**：**哪些功能缺失、哪些需要完善、按什么顺序补、每包的交付物是什么。**
> **不回答什么**：**不打分**。加权完成度、权重口径、范围裁决的账在 `docs/40`；逐条 `file:line` 证据在 `docs/map/01..06`。
> 本文件是**可执行的工作清单**，从上面两处抽出来、按模块重排。

**基准**：pi @ `3390bd936`（2026-09-20，锚点见 `docs/map/ANCHOR.md`）· pi-java @ `34849a2`（2026-09-20）

**范围**：已剔除**裁决 R5**（2026-09-21「只做 pi 主流发布版可达的功能」）判定的非目标 ——
`chord` · `pico3` · `micro` · `packages/durable` · 多进程子系统 · `protocol`/`client`/`server` 三模块 ·
pi `harness/` 里 `AgentHarness` 独有物（具名钩子 / `HarnessEvent` / `run_suspend` / effect gate / `reduceLaneSnapshot` / lane）。

**R5 判据**：从 **`bin pi → dist/bundle/cli.js ← src/cli.ts → main()`** 这条线可达，才算对齐目标；不可达者权重 0、不进分母、不进缺口表。
⚠️ **harness 按「细分」口径**（用户 2026-09-21 选定）：只踢 harness **独有**；
`harness/compaction`、`harness/messages`、`harness/session`、`harness/tools` 在主流 `coding-agent/src/core/*` 有**独立副本** ⇒ **仍算主流**，锚点应改指 `core/*`。
实证：`AgentHarness` 在 `coding-agent/src/{core,modes}` ＋ `main.ts` ＋ `cli.ts` **零消费者**；主流唯一的值导入是 `core/sdk.ts` 的 `Agent` ＋ `setDefaultStreamFn`（都在**顶层** `agent.ts`/`stream-fn.ts`）。

> ⚠️ **2026-09-21：R5 在 `docs/40` 的落地已被用户指示回退** ⇒ `docs/40` 现在仍是 2026-09-20 口径
> （**F7/F9 写「要做」**、总表仍含 `chord` 行、头条仍 44.31%），**与 R5 相悖**。本文件的排除面**以 R5 为准**，
> 不依赖 `docs/40`。R5 若要重新落进 `docs/40`/`docs/32`/`docs/map`，见 §7.4。

---

## 0 怎么读

| 档 | 含义 |
|---|---|
| **缺失** | pi 有、pi-java 完全没有 ⇒ 要做**新东西** |
| **需完善** | 两侧都有，但**行为/形状不同** ⇒ 要做**改造**，不是新建 |
| **死功能** | 代码在，但生产路径上不工作 ⇒ 要做**接线**，通常是改一行 |
| **权重** | 3 = 每轮对话都走 / 2 = 每次会话走 / 1 = 低频边缘（口径见 `docs/40 §0.3`） |

**排期原则**（`docs/40 §9`）：**先修权重 3 的缺失**，它们决定「用户今天会不会撞到」；
权重 1 的长尾排后面。**死功能优先** —— 投入最小、可见性最高。

---

## 1 `pi-java-ai`

**面貌**：缺失 47 条（已剔除 R5 的 24 个长尾 provider 端点）。**杠杆极集中** —— 权重 3 的未对齐共 10 条
（8 缺失 + 2 存疑），占全模块权重的 **43.7%**。

> ⚠️ **2026-09-22 更新**：上段数字是 **H1 之前**的快照。**H1（usage 域）已闭环**（`docs/42 §10`）⇒
> 划掉 8 条缺失（§1.1 三条权重 3 ＋ §1.2 五条权重 2）、§1.4 两条、§1.5 三条死功能救活 ＋ 新增一条
> `UsageInfo.from` 死码登记。权重与完成度**待下一次 `docs/40` 重算**，这里不发明新数。

### 1.1 缺失（权重 3 —— 全是大件，最高优先级）

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| ~~`usage.cacheRead` 生产者~~ | 3 | ✅ **H1 已闭环**（步 2–5，`docs/42 §10`；台账 B56）。原后果：开 prompt caching 时上下文占用被**低估** ⇒ 压缩时机晚于 pi（下游 `ContextOverflow`/`ContextUsageEstimator` **已在读**这个数） | B56 |
| ~~`usage.cacheWrite` / `cacheWrite1h` 生产者~~ | 3 | ✅ **H1 已闭环**（步 3–5；`cacheWrite1h` 只有 Anthropic 报、真 key 端到端预登记于 `docs/42 §8.5-3`）。原后果：`uncachedTokens` 把 cacheWrite 算进未缓存量 | B56 |
| ~~`usage.cost` 计算（`calculateCost`）~~ | 3 | ✅ **H1 已闭环**（步 1 P16 逐条 ＋ 各车道挂价；台账 B57）。原后果：**成本恒显示 0**（含 1h 缓存 2× 输入价、阶梯价 `tiers`） | B57 |
| ~~`transformMessages` 其余 4/5 条变换~~ | 3 | ✅ **图片切片已随 H2 落地**（`docs/44` 步1：`downgradeUnsupportedImages` ＋ `replaceImagesWithPlaceholder`，逐行照抄 `transform-messages.ts:12-57`）。**B14 收窄为三条**：跨模型剥离 `thoughtSignature` / 跨模型归一 toolCall id / 孤儿 toolCall 合成 toolResult —— 仍未做（原后果：跨模型切换时孤儿 toolCall ⇒ provider 400） | B14 |
| Anthropic `cache_control` 标记 | 3 | Anthropic 车道**永不提示缓存** ⇒ 每轮全价、延迟更高 | — |
| `openrouter` chat 面 | 3 | 主流 7 家之一整条不可用（现只有 images 面） | — |
| `SystemMessage`（transcript 系统消息模型） | 3 | 会话中途改系统提示/工具集**无法表达** | — |
| `normalizeContext` / `TranscriptContext` | 3 | 系统提示与消息列表的先后关系由各车道各自猜 | — |

### 1.2 缺失（权重 2）

| 功能 | 权重 | 后果 |
|---|---:|---|
| `xai` provider 端点 | 2 | 主流 7 家之一不可用 |
| ~~**`ImageContent` 收发**（anthropic ＋ openai-completions ＋ mistral 三条车道 **＋ toolResult 路径**）~~ | 2 | ✅ **H2 已闭环**（`docs/44`，七提交 `af5139e..9bbcd48`）—— 共享闸（非视觉降级）＋ 四车道九个落线点（Anthropic 2 ／ completions 2 ／ Mistral 2 ／ responses 1 ＋ 闸 1）。⚠️ **Google 不在内**：实测其工具结果**整条路径**不存在（不是缺图片），已拆出去另立一包（`docs/32` B84）。原后果：**ReadTool 读图模型完全看不到图**（比台账写的更严重） | B17 |
| 孤儿 toolCall 合成 `toolResult` | 2 | 中断/切换模型后历史缺 tool_result ⇒ provider 400 |
| ~~`Model.cost`（含 `cacheRead`/`cacheWrite`/`tiers`）~~ | 2 | ✅ **H1 已闭环**（步 1 `PricingInfo` 五组件 ＋ 步 6 models.json `cost` 扩键/半价→UNKNOWN/tier 拒载）。⚠️ 残留＝**数据面**：内置目录的 cache 价未编（裁决 B：−1 表「未知」，不编假价） |
| **`Model.compat` 缺 49/52 个字段** | 2 | 有行为后果的：`supportsStore` / `maxTokensField` / `supportsDeveloperRole` / `requiresToolResultName` / `thinkingFormat` / `cacheControlFormat` / `sessionAffinityFormat` / `supportsMidConvoSystemMessages` / `supportsMidConvoToolAdditions` / `supportsMidConvoToolChanges` |
| 内置模型目录数据规模（35 条手写 vs pi 41 provider 生成） | 2 | 大量模型查不到 ⇒ 退化为 `ModelInfo.minimal` |
| ~~**`ANTHROPIC_AUTH_TOKEN`（`Authorization: Bearer`）**~~ | 2 | ✅ **A0 步7/8 已闭环**（`ApiOptions.authKind` ＋ `Credentials` 带 kind ＋ 车道按 kind 分派；Bearer ／ OAuth（含两枚身份头）／ x-api-key 三分派，值里含 `sk-ant-oat` 也认）。⚠️ 残留：**anthropic OAuth 流仍未接进请求路径**（只在 `AuthCommand` 里用）＋ pi OAuth 分支的系统提示前缀/工具名两个面未移植（`docs/43 §6-10`） |
| ~~provider 凭证优先级链（stored → authToken → oauthToken → apiKey）~~ | 2 | ✅ **A0 步7 已闭环**（`Credentials.resolveCredential`：profile env → profile file → `ANTHROPIC_AUTH_TOKEN`(BEARER) → `ANTHROPIC_OAUTH_TOKEN`(OAUTH) → 默认 env → 默认 file；两个 token 仅 Anthropic、profile 层不出 `_AUTH_TOKEN_<PROFILE>` 变体）。原后果：多凭证来源时行为与 pi 不一致 |
| ~~`usage.reasoning` 生产者~~ | 2 | ✅ **H1 已闭环**（步 3 `output_tokens_details.thinking_tokens`／步 4 `completion_tokens_details.reasoning_tokens`／步 5 Responses·Google；Mistral 恒 null＝pi 同）。原后果：推理 token 用量不可见 |
| ~~`choice.usage` 回退（Moonshot 型 relay）~~ | 2 | ✅ **H1 步 4 已闭环**（位置照 pi 在 delta 处理之前；台账 B24）。原后果：只在 `choice.usage` 报量的 relay **用量全为 0** |
| ~~`parseChunkUsage` 归一（`prompt_tokens_details.cached_tokens` 等三路 + 减法语义）~~ | 2 | ✅ **H1 步 4 已闭环**（三路 `??` 链＋`Math.max` 减法；台账 B24）。原后果：即使 relay 报了缓存量也读不到 |
| ~~**`message_start` 首帧 usage 保留 ＋ `message_delta` 逐字段覆盖**~~ | 2 | ✅ **H1 步 3 已闭环**（`AnthropicUsageState`，T2/T3/T4 钉）。原后果：早断流时 **input token 被清零** |
| 请求侧 `compat.thinkingFormat`（10 种 thinking 开关形状） | 2 | **非 Anthropic 系的推理模型无法开思考** |
| `simple-options`（`clampMaxTokensToContext` / thinking budget） | 2 | maxTokens 不按 contextWindow 夹取 ⇒ 可能被 provider 拒绝 |
| 工具状态增量（`toolsAdded`/`toolsRemoved` ＋ 线格 `tool_addition`/`tool_removal`） | 2 | 中途增删工具无法表达（**取代已作废的 C11**） |
| 提示词分段 `sections` ＋ 渲染 | 2 | 无法按名替换单段提示词 |
| `ContextOverflow` 的两条新行为（z.ai 放宽正则 ＋ Cerebras 改按 provider 门控） | 2 | z.ai 的 `Prompt too long` 匹配不上 ⇒ 不触发溢出恢复；Cerebras 模式对**任意** provider 都命中 ⇒ **误判溢出** |
| ~~`RetryableError` 的两个新模式（`"currently experiencing high demand"` / `"520"`）~~ | 2 | ✅ **A0 步6 已闭环**（按 pi 逐字顺序插入，子串匹配语义照抄）。原后果：高需求错误与 HTTP 520 **不重试，直接失败** |

### 1.3 缺失（权重 1）

`bedrock-converse-stream` · `google-vertex` · `openai-codex-responses` · `cloudflare-ai-binding` 四条 wire ·
`ToolUseContent.thoughtSignature` / `.namespace` · `TextContent.textSignature` · 跨模型 toolCall id 归一 ·
图片降级为占位文本 · `Model.input` 能力位 · `ThinkingLevel` 词表含 `"max"` · `ImagesModel` 注册表 ·
`AssistantMessage.responseModel`/`responseId`/`providerThinkingLevel`/`diagnostics`/`endTurn` · `Model.promptCache` ·
`MistralConversationsCompat` · ~~`ANTHROPIC_OAUTH_TOKEN`~~ · ~~**`sanitizeSurrogates`（pi 57 处调用，java 零）**~~ ·
constrained sampling / grammar · `transport` 选择 · `session-resources` 清理注册表 · `JsonObject` 类型约束

> ~~**`sanitizeSurrogates` 单独点名**：孤对代理字符原样出站 ⇒ **provider 400**。权重虽 1，但它是**硬故障**。~~
> ✅ **A0 步1–5 已闭环**（`SanitizeUnicode` ＋ 24 个落线点：Anthropic 6 ／ completions 4 ／ responses 5 ／
> Google 3 ／ openrouter-images 1 ／ Mistral 请求 4 ＋ 响应 1）。差额见 `docs/43 §9-1`：grammar 2 处随 grammar 包、
> 4 处「面不存在」（Google thinking ／ Mistral 数组形态 content）、3 处**照缝**（pi 自己也不净化的路径）。
> ✅ `ANTHROPIC_OAUTH_TOKEN` 随 A0 步7/8 闭环（Bearer ＋ 身份头形态）。

### 1.4 需完善（存疑 —— 两侧都有、行为不同）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **`openai` provider 默认 wire** | 3 | pi 绑 **`openai-responses`** / java 绑 **`openai-completions`** ⇒ 同一 key 走**完全不同的协议族** |
| **`done` 事件载荷** | 3 | pi 带 `message: AssistantMessage` / java 带 `usage` + `partial` |
| `error` 事件载荷 | 2 | pi 带 `error: AssistantMessage` / java 带 `Throwable` |
| `provider-retry` | 2 | pi 读 `x-should-retry` 头 / java 用状态码集合 ⇒ 服务端显式要求重试时**不理会** |
| ~~`usage.totalTokens` 合成口径~~ | 2 | ✅ **H1 已对齐**——不是统一成一个公式，而是**逐车道照 pi**：Responses/Google 直取 provider 值（P11/P12）、completions 自算四分量和（P9）、Mistral 优先 provider `||` 自算（P14）、Anthropic 四分量求和。L5 由 S15 差分钉住（`docs/42 §10.1`） |
| ~~`usage.totalTokens` 之外的 usage 形状~~ | 2 | ✅ **H1 已闭环**：`UsageInfo.usage()` 生产非 null（步 2）、`UsageRecord` 传全量（步 6 A3）、`usageOf` 两侧键序/可选键由 S15 逐字节钉（步 7） |
| `Model.reasoning` → `ModelCapability.THINKING` | 2 | 形状不同（pi 是模型元数据布尔 / java 是能力集成员） |
| `models.json` 自定义 provider 形状 | 2 | — |

### 1.5 死功能（有代码、生产不工作）

| 构造 | 状态 |
|---|---|
| ~~`Usage` 的 `cacheRead`/`cacheWrite`/`cacheWrite1h`/`reasoning` **四分量零生产者**~~ | ✅ **H1 步 2–5 已救活**（B56）。原状态：`emitUsage` 只收 input/output ⇒ 生产恒 0/null |
| ~~`Usage.Cost` 恒零~~ | ✅ **H1 步 1＋各车道挂价已救活**（B57）。原状态：成本累加恒 0 |
| `StreamEvent.UsageInfo.from(...)` 零调用者 | **H1 步 7 时核对**（`docs/42 §10.1` A4）：加宽管线走的是 `emitUsage(Usage)`，这个静态工厂仍是死码；不删（R5 面），登记 |
| **`ThinkingLevelMap` 非空实例生产不可构造** | 生产构造点全部 `empty()` ⇒ `forLevel` 恒 `OFF` ⇒ 请求里**永无 `thinking.budgetTokens`**（**B15 剩的这半边**） |
| **`RetryPolicy` 五个预设零调用者** | 包 A0 步6 的发现（J5）：`anthropic()`/`openai()`/`google()`/`mistral()`/`deepseek()` 五预设**零调用者**，`PiHttpClient` 走 `defaultPolicy()`（`{408,409,429}` ∪ 任意 5xx；`Retry-After` 只读 429/503）。是否接线属 `x-should-retry`／传输重试那包的范围（A6） |
| `StreamSimple` | 主源码零调用（**别当接缝**） |
| `DeferredHandle` | 零生产者（两侧同状） |
| `ToolResultMessage.usage` / `details` | 两者皆无生产者 |
| ~~`StreamEvent.UsageInfo` 的 `usage` 分量~~ | ✅ **H1 步 2 已救活**（J1/J2/J4）。原状态：恒 null |

### 1.6 修法建议（报告已复核边际）

| P0 项 | 边际 |
|---|---:|
| Usage 四分量 | +3.76pp |
| `usage.cost` 计算 | +1.41pp |
| 图片三车道 ＋ toolResult | +0.94pp |
| `openai` 默认 wire | +0.70pp |
| **四条全修** | **+6.81pp** |

- **B15 与 B8 共用同一载体**（`StreamRequest` 已带整个 `ModelInfo`）⇒ 两者应合成**一次投送修复**。
- ~~**B17（图片）须先出设计包再改** —— `AnthropicMessagesApi.java:526-529` 源码注释已写明「行为变更须先过设计」。~~
  ✅ **H2 已闭环**（`docs/44`，2026-09-22）：先出设计（`af5139e`）再实施，七提交 `af5139e..9bbcd48`。
  那条注释已随实现删除。⚠️ **Google 车道被实测证伪**（工具结果整条路径不存在）⇒ 拆出去另立一包。

### 1.7 抽取时撞出的偏差（须登记）

1. **报告「`ModelCapability.THINKING` 不可达」不成立** —— 它在生产被读（`OpenAICompletionsApi.java:544` 做 deepseek `reasoning_content` 补空串的第二道门）。真死的只有 `ThinkingLevelMap` 非空构造与 `StreamSimple`。
2. **报告计数内部不一致**：`02-ai.md:88` 写「21 个长尾」但列 22 项；`:265` 写 22；缺失清单标题写「（14）」但列 15 项。
3. **报告漏收的两条 OpenAI 缓存缺口**：`api/openai-prompt-cache.ts`（`prompt_cache_key` / `prompt_cache_retention="24h"`）java 零命中；`cacheRetention` 只接进 `openai-responses` 一条车道。
4. **行号小漂移**：`ModelCapability` 的 `IMAGE_INPUT` 在 `:16`、`THINKING` 在 `:19`；`ModelInfo` 在 `catalog/` 而非 `model/`；`Protocol` 在 `provider/` 而非 `protocol/`。

**包 A0 逐行实读时新撞出的（`docs/43 §6` 详述）**：

5. **§1.3 的「`sanitizeSurrogates`（pi 57 处）」计数偏高**：逐行实读全仓是 **46 处 / 9 文件**
   （anthropic 11 · mistral 9 · completions 7 · responses 7 · google-shared 6 · bedrock 3 ·
   generative-ai 1 · vertex 1 · openrouter-images 1）；扣掉 java 未移植的两条车道（bedrock 3、vertex 1）
   ⇒ 范围内 **42 处**、java 落 **24 点**。
6. **pi 同族的三条缝**（pi 自己也不净化的路径，java 照缝）：`completions:1339` 签名驱动 reasoning 字段 ·
   `responses:322` `function_call.arguments` · `google-shared:274` `functionCall.args`。三条各配 guard 夹具。
7. **四个落点「面不存在」**：Google 的 thinking 两分支（java 一律丢 thinking）· Mistral 的数组形态
   `delta.content` 两处（java 只按 String 读 ⇒ **会抛 ClassCastException**，属该车道的形态缺口）。
8. **pi OAuth 分支的两个额外面**（`anthropic-messages.ts:1078-1090` 系统提示前缀 ＋ `toClaudeCodeName` 工具名）
   未移植；两枚浏览器头（`accept` / `anthropic-dangerous-direct-browser-access`）有意不移植。
9. **序列化器对孤对代理有三种口径**（线格实测）：SDK 的 UTF-8 字节生成器 ⇒ 转义 `\uD83D`（**孤对能出站**）·
   `com.google.genai` ⇒ 替换成 `?`（**静默错字**）· Jackson char 型 ⇒ 原样留在串里。

---

## 2 `pi-java-coding-agent`

**面貌**：缺失 ~105 条 · 需完善 67 条 · 死功能 13 条。
**最大三块**：① **扩展事件面 37 个单元 → 0**（pi 侧本轮还新增了退订与 `cache_warming_decision`）
② **包管理器 4,236 行 → 496 行且零对齐单元** ③ **上下文文件发现 ＋ JSON 主题系统 ＋ 可执行验收规格（15,809 行）整块不存在**。

⚠️ **本模块的权重 3 层（41.3%）低于权重 1 层（47.9%）** —— 欠账最重的恰是每轮都走的地方。

### 2.1 缺失（权重 3 —— 10 条，最该先修）

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| **上下文文件发现（`AGENTS.md`/`CLAUDE.md`）** | 3 | 项目的 `AGENTS.md`/`CLAUDE.md` **完全不进系统提示词**，**静默失效无报错**（5 个候选名 ＋ 向上遍历 ＋ `override` 语义 ＋ worktree shadow 全无） | — |
| **`before_agent_start` 扩展事件** | 3 | 扩展无法在 agent 启动前**改写系统提示词** | — |
| **`message_update` 扩展事件** | 3 | 扩展无法拦截/改写流式增量（打字机、增量审计、增量脱敏都做不到） | — |
| **`input` 扩展事件** | 3 | 扩展无法改写或吞掉用户输入 | — |
| **`context` 扩展事件**（引擎钩子**存在但未桥接**） | 3 | 扩展无法改写送模型的上下文 | — |
| **`before_provider_request`**（同上） | 3 | 扩展无法改写请求体 | — |
| **`tool_call`**（同上，可 block ＋ 改参 ＋ terminate） | 3 | 扩展无法拦截工具调用（权限门/脏仓护栏类扩展失效） | — |
| **`tool_result`**（同上，可覆盖结果） | 3 | 扩展无法改写工具结果 | — |
| **扩展示例库 `examples/`** | 3 | 101 个 `.ts` / 84 个扩展示例 —— 它同时是 **pi 扩展 API 的可执行定义**；缺了等于第三方扩展无从对齐 | — |
| **包生态层**（npm 包源 ＋ 版本/range/pinning ＋ 持久化到 `settings.packages` ＋ git 源 ＋ 资源优先级 ＋ 忽略文件） | 3 | 无法 `install npm:@scope/pkg@^1.2`；装的扩展**不落 settings** ⇒ 无声明式复现、无跨机迁移 | — |

> ⚠️ **共因**：前 8 条里 4 条（`context`/`before_provider_request`/`tool_call`/`tool_result`）的引擎钩子**已经在 `HookSystem` 里存在**（13 个钩子），
> 只是 `ExtensionContext` **不暴露 `hookSystem()`** ⇒ 扩展拿不到。**这是「接线」不是「新建」**。

### 2.2 缺失（权重 2）

**CLI 参数 / 子命令 / Slash（6 条）**

| 功能 | 权重 | 后果 |
|---|---:|---|
| `--models` | 2 | flag 解析存在、**零生产消费者** ⇒ `--models a,b` 静默无效；Ctrl+P 循环模型无实现 |
| `--extension`/`-e` | 2 | 零消费者 ⇒ 无法用显式路径加载扩展 |
| `--no-context-files`/`-nc` | 2 | 零消费者（且因上下文文件发现整块不存在，是**双重失效**） |
| `update [source\|self\|pi]` | 2 | **无 self-update、无扩展更新、无模型目录刷新** |
| `/thinking` | 2 | TUI 内无法切思考档位，必须退出重开 |
| `/skill:<name>` 命令族 | 2 | 技能无法被当命令调用（`enableSkillCommands` 字段有、**不产生任何命令**） |

**扩展事件（19 条，权重 2）** —— 全部无对应物；括号内是用户可观察后果：

`project_trust`（扩展无法参与信任决策）· `session_start` · `session_before_switch` · `session_before_fork` ·
`session_compact` · **`session_shutdown`（扩展无法释放资源 ⇒ 连接/线程/临时文件泄漏）** ·
**`cache_warming_decision`（本轮 pi 新增；省钱的钩子不存在）** · `agent_start` · `agent_end` · `agent_settled` ·
`turn_start` · `turn_end`（**B49**）· `message_start` · `message_end` · `tool_execution_start` ·
**`tool_execution_update`（宿主有投影面但生产无数据源 ⇒ 流式工具输出永不发射，B46）** ·
`tool_execution_end`（B43）· `model_select` · **`thinking_level_select`（宿主有事件但零生产者，B35）**

**扩展 UI 注入面（6 条，整域 0 对齐）**：通知/状态栏/标题 · widget（above/belowEditor）· 自定义 footer/header ·
自定义组件＋overlay＋键盘焦点 · 终端原始按键拦截 · 主题读写（get/set/load/all）

**其余能力单元（权重 2，节选）**

| 功能 | 权重 | 后果 |
|---|---:|---|
| **`-r` 交互式会话选择器**（~1,200 行） | 2 | **`-r` 不带 `--session` 直接崩**（`SessionPersistence.java:186` 走 `handle.find(null)` ⇒ 抛 `Session not found: null`），用户看到异常而非选择器 |
| **JSON 主题系统**（1,552 行） | 2 | 用户无法自定义主题（只能二选一内建），无热重载 |
| **提示缓存预热**（`core/cache-warmer.ts` 453 行 ＋ `settings.cacheWarming` 三档） | 2 | 无预热 ⇒ 每轮**首 token 延迟与成本更高** |
| **分支摘要** | 2 | 分叉时无「父分支摘要」注入，长会话分叉后上下文断裂（**B1**） |
| 系统提示词文件发现 ＋ **分节构建与差分** | 2 | 无法用文件管理 system prompt；无法观测/调试哪一节变了（pi 用于缓存命中诊断） |
| `pi manifest`（`package.json` 的 `pi` 字段） | 2 | 无法用 manifest 声明式描述一个包 |
| **`on()` 返回退订函数**（pi 37 条签名本轮由 `void` → `() => void`） | 2 | **扩展无法在运行时退订事件** ⇒ 长驻会话内存/副作用累积 |
| `exec` / `sendUserMessage` / `setModel` / `get·setThinkingLevel` / `get·setActiveTools` | 2 | 扩展无法执行外部命令 / 以用户身份注入消息 / 切模型 / 读写思考档 / 动态启停工具 |
| `registerShortcut` / `registerMessageRenderer` / `registerMarkdownTransformer` / `registerEntryRenderer` | 2 | 扩展无法注册快捷键 / 自定义渲染 |
| settings 缺字段：`modelThinkingLevels` / `branchSummary` / `npmCommand` / `packages` / `defaultTools` / `cacheWarming` | 2 | 对应的配置面**整块不存在** |

### 2.3 缺失（权重 1，节选）

`--use-theme` · `--offline`（零消费者）· 扩展注册 CLI flag（无 `registerFlag`/`getFlag`）· **`/bug`（706 行：收集＋脱敏＋上传）** ·
扩展事件 `session_info_changed`(B30) / `session_compact_failed`(F3) / `session_tree` / `ui_prompt_start`·`end` / `user_bash` ·
编辑器注入（5 方法）· 工具展开态读写 · **settings 冷键 14 个**（`lastChangelogVersion` / `showCacheMissNotices` / `thinkingBudgets` / `httpIdleTimeoutMs` / `fullscreen*` …）·
`unregisterProvider` / `appendEntry` / `setSessionName` / `setLabel` / `getCommands` / **`events`（扩展间 EventBus）** ·
崩溃日志（92 行）· WSL / zip 工具 · `update --models`

### 2.4 需完善（存疑，67 条 —— 节选要害）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **`compaction` 设置的 `modelOverrides`** | 3 | 无法按模型调压缩参数（**A9**） |
| **RPC 命令集缺 `clear_queue`** | 3 | 客户端**无法清空排队消息** |
| **RPC 事件线格式** | 3 | `B27`/`B28`/`B29`（可空键 / usage / toolcall_start 字段）⇒ 客户端解析失败或字段缺失 |
| **`--session` 三级阶梯** | 2 | pi：① header-only 精确匹配 → ② 项目内前缀 → ③ 跨项目 `listAll`；java **直接跳到第 ③ 级** ⇒ **O(全部会话文件) 且跨项目**，启动慢、可能命中别项目同名会话（**B53**，真差距是**次序与成本**不是「跨项目＝bug」） |
| **会话列表** | 2 | pi 有 `onProgress` ＋ **`AbortSignal`** ＋ **渐进发布部分结果**；java 同步全量、无回调、无取消 ⇒ 大会话目录**卡住 UI 且不可取消**（**B55**） |
| **`--no-session`（web 路径）** | 1 | `resolvePersistentWeb(session, args)` 的 `args` 形参**函数体零引用** ⇒ web 模式静默无效、会话仍落盘（**B54**） |
| **`/reload`** | 1 | pi 重载 keybindings＋extensions＋skills＋prompts＋themes＋**context files**；java 只 `settings().reload()` ⇒ 改扩展/技能/提示词后**必须重启** |
| **`/model`** | 2 | pi 支持 `/model provider/id` 直接设置；java `argumentHint` 为空且**忽略 args** |
| **`/export`** | 2 | pi 默认 HTML；java 无参**直接返回用法（不给默认）** |
| **`/login`** | 2 | pi 走 OAuth 选择器；java 只用 `readPassword` 收 API key ⇒ **无法 OAuth 登录** |
| **`/fork`** | 2 | pi 从**历史用户消息**分叉（选择器）；java 只能按 branch-name |
| **`/session`** | 1 | pi 含 **stats**；java 只打 4 行，**无 token/成本统计** |
| **`/changelog`** | 1 | java 是**硬编码字符串常量**（内容还写着 "Phase 3/4/6"） |
| **`/share`** | 1 | pi 主路径是 Radius 网关（gist 是 fallback）；java 只有私有 gist 且要求 `GITHUB_TOKEN` |
| **`sendMessage` 的 `triggerTurn`** | 2 | java `triggerTurn=true` **抛 UOE** ⇒ 扩展无法「发消息并触发一轮」 |
| **扩展 UI 通道（RPC，9 method）** | 1 | 形有，**生产不可达**（见 §2.5 C-9） |
| **`get_state` 三个字段** | 2 | **B31**（model 是字符串非对象）/ **B32**（sessionName 恒有默认值 `"session"`）/ **B33**（messageCount 取转录条数） |
| **`install`/`remove`/`list`/`config` 四个子命令** | 2 | 只支持本地 JAR/URL（无 npm/git）；无 `temporary` 作用域；`list` 不分组；`config` 是**非交互**（pi 是 TUI 942 行） |
| **`--export` 两个位置参数** | 1 | java 只取 1 个 ⇒ 无法指定导出目标路径 |
| **`--theme` 语义** | 1 | pi 收 **JSON 主题名或文件**；java 只收 `.tcss` 路径，且只取 `themes().get(0)` |
| **`auth.json` 无 revision/锁** | 2 | 多进程/并发写凭据可能互相覆盖 |
| **`models.json` 的 `baseUrl`** | 2 | **F6**：`ModelsJsonProvider` 钉死 baseUrl ⇒ **CLI `--base-url` 对它失效** |
| **settings 零消费键（8 个）** | 1–2 | `terminal.showImages`/`imageWidthCells`、`images.autoResize`/`blockImages`、`markdown.codeBlockIndent`/`mermaid`、`editorPaddingX`/`outputPad`/`autocompleteMaxVisible`/`httpProxy` ⇒ **改了毫无效果且无警告** |
| **`retry` 设置** | 2 | `ProviderRetry` 子对象保形但**不接线**（**A7**） |
| **`enableSkillCommands`** | 2 | 字段有、1 处消费，但**不产生任何命令** |
| **扩展来源追踪** | 1 | 无 `sourceInfo` 投影 ⇒ 无法显示扩展来源 |

> **严格口径**：§2.1 里 4 条「引擎钩子存在但未桥接」＋ §2.4 里 4 条同形（`session_before_compact` / `session_before_tree` /
> `before_provider_headers` / `after_provider_response`）报告记「存疑」，**严格口径应记「缺失」** ⇒ 模块完成度 44.0% → **41.9%**。

### 2.5 死功能（有代码、生产不工作）

| # | 死功能 | 死因 | 后果 | 台账号 |
|---|---|---|---|---|
| C-1~C-4 | `--models` · `--extension` · `--no-context-files` · `--offline` | flag 有、**零生产消费者** | 静默无效 | — |
| C-5 | `--no-session`（web 路径） | `args` 形参函数体零引用 | web 模式会话仍落盘 | B54 |
| C-6 | `AgentSessionEvent.SessionInfoChanged` | 零 emit | 事件恒不发射 | B30 |
| C-7 | `AgentSessionEvent.ThinkingLevelChanged` | 零 emit | 同上 | B35 |
| C-8 | `tool_execution_update` | `BashTool` **无 `onUpdate` 调用** | 流式工具输出永不发射 | B46 |
| **C-9** | **扩展 UI 通道（`extension_ui_request/response`）** | ⚠️ **报告说「无生产者」是错的** —— 实测 `RpcDispatcher.java:71` **确实注入**了真 producer（写 stdout ＋ 阻塞等响应），但 `AgentSession.extensionUI(ExtensionUI)`（`:695-699`）**只写私有字段**，getter（`:702-704`）**全仓零调用者** ⇒ **注入被吞**。已构造的 `DefaultExtensionContext` 永远持有 `ExtensionUI.noop()` | **扩展永远只拿到 noop** ⇒ `ctx.ui()` 全部立即返回 `confirm(false)` | — |
| C-10 | `enableSkillCommands` | 半接线 | 开了也没有 `/skill:name` | — |
| C-11 | 零消费的 settings 键（8 个） | 字段有、无消费者 | 改了毫无效果且无警告 | — |
| C-12 | `ExtensionManager.unload()` | 只从表移除，注释自认「扩展无 close 生命周期」 | 扩展无法释放资源 | — |
| C-13 | `AgentSession.extensionUI()` getter | 全仓零调用者 | 无直接后果，但是 C-9 的判定证据 | — |

> ⚠️ **C-9 是唯一一处机制级修正**：报告说「无生产者」，实测是「**有生产者但注入被吞**」。
> 用户可观察后果相同（永远 noop），但**修法不同** —— 前者要「加注入」，后者要「让注入回流到 `DefaultExtensionContext`」。

### 2.6 杠杆与修法建议（报告已给）

- **D 域（扩展 36 事件）单独把模块拉低 13.7pp**：若全对齐，模块 44.0% → **57.7%**。
- **8 个「半信用」的未桥接钩子本身值 2.1pp**（严格口径全判缺失则 44.0% → 41.9%）。
- **上下文文件发现在单元计数口径下杠杆很小**（+1.3pp）—— 报告随即自陈这是**口径盲点**：一个整块缺失的子系统在单元表里天然只占 1 行，真实分量应等价于 **10+ 个权重 3 单元**。
- **E6 包管理器是唯一「加权 > 未加权」却几乎没动的域**：非零全来自 5 个 2 权存疑各计 0.5，**没有任何一个单元是「对齐」**。

### 2.7 抽取时撞出的偏差（须登记）

1. **`HookSystem` 13 个钩子的行号整体 +2**（`:41/46/51/…` → 实测 `:43/48/53/…`）。
2. **`interactive-mode.ts` 的 skill 命令族在 `:745`**（报告写 `:713-718`，那是 login 补全块）。
3. **`resource-loader.ts` 的 5 候选名在 `:73`**（报告写 `:72`）。
4. **`settings-manager.ts` 的 `cacheWarming` 在 `:157`**（报告漂移表写的 `:162` 是错的 —— 报告内部不一致）。
5. **`B53` 后半句被证伪**：pi 的 `resolveSessionPath` 是**三级阶梯**、第 ③ 级就跨项目 ⇒ 真差距是**次序与成本**。
6. **`B46` 措辞**：「生产上永不发射」应限定为「**该事件无生产数据源**」而非「无投影面」。
7. **「全仓 grep `AGENTS` 零命中」**实测是 **2 命中**（都是帮助文本）⇒ 措辞应为「**零发现逻辑**」。
8. **`F6` 归属**：实现在 `pi-java-ai`，症状在本模块可见。

---

## 3 `pi-java-tui`（＋ `pi-java-web`）

**面貌**：缺失 79 条 · 需完善 24 条 · 死功能 3 条（含 2 条已点名）。
**最大短板是「渲染特性」面（15.3%）**，最高是 web 对齐面（78.8%）。
裁决 R3 把 TUI 排在最后 —— 但下面 §3.5 的两条死码**要先决定「接线还是删声明」**：留着会让人以为 Markdown 和高亮是能用的。

### 3.1 缺失（权重 3 —— 6 条，全是「每轮都看得见」的）

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| **R1 Markdown 渲染**（标题/粗斜体/列表/引用/代码块） | 3 | 每条助手消息都是**纯文本平铺**，标题/列表/引用/代码块全不可见 | — |
| **R11 逐工具渲染器**（read/write/edit/bash/grep/find/ls 各自形状） | 3 | 所有工具共用一张粗糙卡片，看不出哪种工具做了什么 | — |
| **K27 提示历史 ↑/↓ 导航**（draft 还原、100 条上限） | 3 | 按 ↑ **不能调回上一条输入** | H-9.2-② |
| **C5 loader / spinner**（braille 10 帧动画） | 3 | 等待时无任何动画 | — |
| **C6 working 指示器**（spinner ＋ 文案 ＋ 计时） | 3 | 运行中看不到耗时/进度 | — |
| **T2 主题 token 覆盖**（约 70 个语义色） | 3 | 每条消息的配色与 pi **系统性不一致** | — |

### 3.2 缺失（权重 2）

| 功能 | 权重 | 后果 |
|---|---:|---|
| **R9 代码块语法高亮** | 2 | 代码块无高亮（且路径是死码） |
| **R12 工具执行卡可折叠 ＋ `Ctrl+O`** | 2 | 长工具输出无法折叠 |
| **R13 bash 执行组件**（实时增量输出） | 2 | TUI 无实时增量命令输出 |
| **K6 `Ctrl+P` 模型循环 / K7 `Shift+Tab` thinking / K8 `Ctrl+O` 工具展开 / K9 `Ctrl+T` thinking 显隐** | 2 | **按这 4 个键无任何反应**（常量与默认绑定齐备，`PiTuiApp.java:437` 的 `default ->` 是死码） |
| **K16 `app.session.*` 家族**（new/tree/fork/resume ＋ 选择器内 11 键位） | 2 | new/tree/fork/resume **全无键位** |
| **K25 `@文件` 路径补全**（fuzzy ＋ `fd`） | 2 | 输入 `@` 无文件路径补全 |
| **S8 thinking level 选择器 / S17 OAuth 登录对话框 / S19 项目信任选择器 / S25 会话统计面** | 2 | 四个交互面缺失（登录只能粘 API key） |
| **C3 设置列表**（bool/enum/string/number 多类型编辑） | 2 | 设置项不能按类型编辑 |
| **T5 思考等级 → 边框/文字颜色映射**（8 档） | 2 | 思考等级无视觉区分 |
| **R5 流式未闭合围栏修剪** | 2 | 流式期间代码块闪烁/错乱 |

### 3.3 缺失（权重 1，节选 —— 渲染与交互的长尾）

**渲染**：R2 管道表格 · R3 删除线 · R4 链接（OSC 8）· R6 transformer 钩子 · R7 Mermaid · **R8 LaTeX（1506 行）** ·
**R14 图像渲染（Kitty/iTerm2 协议）** · R15 图片粘贴 · R18 分支摘要卡 · R19 skill 调用卡 · R22 启动公告/彩蛋 · R23 终端标题 ·
**R24 缓存预热用量行** · R25 thinking 块被丢弃提示

**全屏（alt-screen）**：S2a 转录搜索 · S2b 鼠标文本选择 ＋ copy-on-select · S2c flash 提示 · S2d 图像渲染

**选择器族（9 个未实现）**：S6 模型搜索 · S7 scoped-models · S9 theme · **S11 会话选择器（搜索/排序/改名/删除/进度）** ·
S13 树选择器（折叠/标签/过滤模式/搜索/复制）· S15 设置子菜单 · S16 `/config` · S18 首次设置向导 · S20 排队消息选择器 · S21 show-images

**键位**：K10 `Ctrl+G` 外部编辑器 · K11 `Alt+Up` 取回排队消息 · K12 `Ctrl+Z` 挂起 · K13 `Ctrl+X` 复制 · K14 粘贴图片 ·
K15 `Ctrl+N` 命名过滤 · **K17 用户键位覆盖（`keybindings.json`）** · K21 转录搜索 · K22 鼠标选择 · K26 大粘贴折叠 ·
K31 jump mode · K34 原生剪贴板 · K35 终端能力探测 · K37 消息卡鼠标点击展开

**组件/主题**：C4 单行 input · C7 bordered loader · C8 cancellable loader · C14 mouse-region · C15 dynamic-border ·
T4 主题热重载 · T7 导出专用主题色

**web**：W8 `queue_update`/`compaction_*`/`auto_retry_*` 推送前端 · W14 `--no-session` 在 web 路径生效 · W15 会话列表带 `onProgress` · W16 `find()` 范围

> **整块缺失的 LOC 量级**（pi 侧）：图像协议 823 · LaTeX 1506 · Markdown 组件 1015 · alt-screen 搜索+选择 ~830 ·
> 扩展 UI 族 ~700 · 选择器族 ~2900 · 逐工具渲染器 1057 · 工具执行卡 653 · 主题系统 ~1900 · 提示历史/大粘贴/jump ~400 ·
> loader/spinner ~250 · 原生剪贴板+能力探测 ~170 · `@文件` 补全 ~500 · 故障上报+崩溃日志 ~600。

> ⚠️ **`web` 前端零测试** —— `frontend/package.json` 无 `test` 脚本、无 vitest/jest/playwright 依赖、无 `*.test.*` 文件；
> Maven `frontend-maven-plugin` 只跑 `npm ci` ＋ `npm run build`。

### 3.4 需完善（存疑，节选要害）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **R10 Diff 渲染** | 3 | pi 有 hunk 头/行号/**词级 diff**；java 只**按行首字符给色** |
| **K32 Kitty 键盘协议 / `modifyOtherKeys`** | 3 | java 有 CSI-u，**无 `modifyOtherKeys`** ⇒ 部分终端的组合键不可用 |
| **C16 编辑组件** | 3 | pi 2470 行（含历史/大粘贴折叠/jump）；java 461 行，缺 K26/K27/K31 |
| **W10 `agent_end.messages` 整表替换** | 3 | java 在**空消息时不带 `messages` 键** ⇒ 载荷不同（**A8**） |
| **S12 会话树选择器** | 2 | pi 是 **entry 级真树**（1428 行）；java 列的是 **lane 名 ＋ leafId** —— 「树选择器」名下**不是同一对象** |
| **K5 `Alt+Enter` 排队 follow-up** | 2 | ⚠️ pi 在 **Windows 上 followUp 是 `ctrl+q`**、`alt+enter` 归 `tui.input.newLine`；java 绑 `alt+enter`、换行走 `shift+enter` ⇒ **Windows 上键位冲突** |
| **C17 模糊匹配** | 2 | pi 是**子序列**匹配 ＋ 按空白/斜杠**分词**；java 是**子串**匹配 ＋ **无分词**。query `abc` vs `a-b-c` —— **pi 命中、java 不命中**（本轮 对齐→存疑） |
| **R17 压缩摘要消息卡** | 2 | java 只一行 `Compacted context: …`，**无开销提示、无点击展开** |
| **C2 选择列表 / C13 truncated-text / T6 语法高亮色板 / K18 键位提示** | 2 | 能力面弱于 pi |
| **S2 全屏 alt-screen 模式** | 2 | java 用 TamboUI 通用 runner，**能力面差 S2a–d 四块** |
| **S27 `/share` / S28 `/export` / T3 自定义主题 / W9 消息 `timestamp` 上 wire** | 1 | `/share` 后端不同（pi 是 Radius 网关）；主题格式不兼容（`.tcss` vs JSON＋schema）；**`timestamp` 是有意偏离**（B50） |

### 3.5 死功能（3 条）

| # | 死功能 | 证据 | 用户感知 |
|---|---|---|---|
| 1 | **`MarkdownRenderer` 是生产死代码 ⇒ TUI 完全不渲染 Markdown** | 全仓 grep **只命中自身与测试**；助手消息实际走 `MessageBubble.java:78,:91` 的 `TextLayout.split(…escapeMarkup(text), false)`（纯文本）。连带 `renderTable`、mermaid 占位分支均无调用者 | 表格/代码高亮/mermaid/链接全不可见 |
| 2 | **`SyntaxHighlighter` 的代码块路径是死代码** | 只有 3 色 ＋ 40 个硬编码关键字；唯一生产可达调用点是 `EditorElement.java:123`（编辑器当前行）；代码块路径 `MarkdownRenderer.java:91` 无人调（因为 `MarkdownRenderer` 整体死） | 代码块无高亮 |
| 3 | **6 个已声明的键位一个都没接线** | `MODEL_CYCLE`/`THINKING_CYCLE`/`TOOLS_EXPAND`/`THINKING_TOGGLE`/`EXTERNAL_EDITOR`/`DEQUEUE`；默认绑定齐备（`:61-68`），`PiTuiApp.java:437` 的 `default ->` 是死码（对照 `:424` 的 `FOLLOW_UP` **是接了的**） | 按这 6 个键**无任何反应** |

### 3.6 收益与建议（报告已给）

| 修什么 | 模块 | 对应面 |
|---|---:|---:|
| `R1`（w3） | +1.3pp | R 面 15.3% → **23.6%** |
| `R1 + R9` | +2.1pp | R 面 → **29.2%** |
| **接线 6 个键位**（K6/K7/K8/K9 ＋ K10/K11） | **+4.3pp** | K 面 46.9% → **62.5%** |
| 两项合计 | **+6.4pp** | — |

> ⚠️ **口径提醒**：这两项只值 +6.4pp，但它们**在权重 3 子集里的分量远大于此** ——
> `R1` 是**权重 3 的单条最大缺失**（修好把权重 3 子集从 67.3% 拉到 **71.2%**）。
> 又一次印证：**加权完成度对「单条高频缺失」不敏感，看权重 3 子集才看得出。**

### 3.7 抽取时撞出的偏差（须登记）

1. **`H-9.2-⑦` 已过期** —— 包① 后 Anthropic 车道**已发** `ThinkingStart`/`ThinkingContent`。
2. **`H-9.2-⑧` 结论与设计稿相反** —— 落地的是**不给消息带 timestamp**（有意偏离）。
3. **`H-9.2-②` 比台账记的更严重** —— Windows 上 followUp 是 `ctrl+q`，java 绑 `alt+enter` ⇒ **键位冲突**。
4. **`B45` 已修**（`ChatScreen` 加 `volatile` ＋ `AtomicInteger`）。
5. **`A2` 部分已清** —— TUI 半可结案（TUI 不读 `transcript()`），web/RPC/SQLite 半仍开。
6. **`B47` 仍成立** —— 包⑦ 只换了 web 面，**刻意没动 TUI**。
7. **`B55` 措辞要更新** —— pi 的 `onProgress` 现在带 `partialSessions` ＋ `AbortSignal`。
8. **pi 新增 `utils/zip.ts`、`utils/wsl.ts` 需登记**。

---

## 4 `pi-java-session-backend-sqlite` ＋ `pi-java-evals`

**面貌**：缺失 39 条 · 需完善 13 条 · 死功能 6 条（其中 1 条是挂 1990 s 的 flake）。
**这里是「用户可见硬故障」最集中的模块** —— 换机/换工具后**会话直接读不出来**。

### 4.1 缺失（权重 3）

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| **JSONL 事务行 kind 集合兼容**（pi `entry`/`usage`/`value`/`list` vs java `entry`/`record`/`lane`/`fact`，**交集只有 `entry`**） | 3 | 对方文件里的事务行**直接不认** ⇒ 会话读不出来 | **B60** |
| **JSONL 头部线格式兼容**（pi 写 `{v:4, storageVersion:1}`；java 写 `{version:4}`） | 2 | **换机/换工具后会话直接读不出来（硬故障）** | **B60** |
| 批量提交 `commit(writes[])`（单事务原子性） | 3 | 崩溃时无法保证跨记录原子，**半写状态可见** | B59 |
| 按 id 批量读 `getEntries(ids[])` | 3 | 批量读退化成 N 次查询 | — |
| 标量值 `getValue`/`scanValues` | 3 | pi 的**通用值存储语义整体缺失** | B59 |
| 列表值 `readList`/`appendList`/`deleteList` | 3 | pending assistant frame 队列不可持久化 | B59 |
| **`scalar_values` 表**（承载 **13 个命名空间，含整个 durable operation 状态机**） | 3 | operation 状态机、pending 队列**无持久载体** ⇒ 恢复语义缺失 | B59 |
| **`list_values` 表**（`pi.pending.assistant_frame`） | 3 | 有序列表值不可持久化 | B59 |
| **`branch_meta` 表**（含 `base_branch_id`/`base_seq` **分支分段**） | 3 | **压缩边界行为不等价**；分支回溯到根不可得 | B59 |
| `entries` 表列集分叉（缺 `custom_type`；`timestamp` 类型 `INTEGER` vs java `TEXT`） | 3 | 自定义 entry 类型过滤不可用；**时间类型跨读失败** | B59 |
| **触发器 `trg_entries_validate`**（父 entry 必须已存在） | 3 | DB 层不再强制该不变量，应用层漏检即**脏数据** | B59 |
| **触发器 `trg_usage_ledger_validate`**（`entries.id` 与 `usage_ledger.id` **共享命名空间**） | 3 | id 冲突不再被 DB 拦截 | B59 |
| **评测：真 coding-agent harness**（临时目录起真会话、驱动 prompt、回读 transcript/usage） | 3 | 评测**跑不到真 coding-agent 路径**，只能测组件 | — |
| **评测：Docker eval 执行**（构建镜像/容器内跑/隔离 auth） | 3 | 无隔离执行环境，评测不可在干净容器内复现 | — |

### 4.2 缺失（权重 1–2）

`sessions` 表列集分叉（缺 `storage_version`/`message_count`/`usage_payload`/`next_seq`；`created_at` 类型 `INTEGER` vs `TEXT`）⇒ **无版本闸，新版文件被旧版静默误读** ·
`usage_ledger` 表（逐条用量台账）⇒ 无法审计/回溯 ·
`scanUsage` / `scanBranchStructure` ·
**JSONL v3 旧格式读取**（java 要求 `kind:"header"` ⇒ 打不开 pi 旧版文件，**B61**）·
JSONL `nextSeq` 高水位字段 ·
会话列表 4 项（进度回调 / 增量部分结果 / `AbortSignal` / 并发扫描）⇒ **B55** ·
评测侧：LLM-as-judge 打分 · A/B 对照表 ＋ 重复次数 · 对照报告汇总 · harness-run 落产物（`session.jsonl`）· 会话快照作为 test attachment · `toolCalls(...)` · docs 审计 eval · 模型作者 eval · 文档变体任务计划 · TUI 文档审计 eval

**防漂移脚本（pi 40 文件 / 8,325 行，8 个真漂移门）** —— **B63**：`check-pinned-deps`（java 侧 `pi-java-web` 前端 7 个依赖**全用 `^`**）· `check-runtime-deps`（TS 程序级裸模块名分析）·
`sync-versions` lockstep 断言（java 靠 Maven 单 parent 版本，**结构性保证但无校验脚本**）· `check-browser-smoke` · `check-entry-graphs`（**入口图/打包体积预算无门**）·
`check-lockfile-commit` · 两个 `--check` 重生成一致性门

### 4.3 需完善（存疑，13 条）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **分支 tip 读取** | 3 | pi 有「**权威**（标量值 `pi.branch.tip`）vs **缓存**（`branch_meta`）」二分；java 只有一张 `branch_tips` 表 ⇒ **缓存与权威分叉时行为不同** |
| **分支创建/移动** | 2 | pi 带 `base_branch_id`/`base_seq` **分段**；java `createLane`/`moveLane` **无 base 概念** ⇒ **压缩边界可能不等价** |
| **`branch_entries` 列差异** | 3 | java 多 `custom_type` 列 ＋ 多一个索引 |
| **JSONL 文件名编码** | 2 | pi `${ISO}_${encodeURIComponent(id)}.jsonl`；java **不 encode** ⇒ id 含特殊字符时**路径分叉** |
| **`session_stats` 摊平** | 2 | java 用 5 个 REAL 列代替 pi 的 `usage_payload` JSON ⇒ **可能丢 `cacheWrite1h`/`reasoning` 分量**（B56） |
| 会话名 / entry 标签载体 | 1–2 | pi 用通用标量值；java 用 `facts` 表 ⇒ 与 B59 同根 |
| 评测侧：eval 运行器 CLI（pi 双轨入口 ＋ 产物目录 vs java 纯内存）· 扩展作者 eval（pi 让模型**真写**扩展再判）· provider 集成 eval（pi 起**本地 HTTP mock server**）· smoke eval 断言强度（pi 断 5 项，java 只断收到 `StreamDone`） | 2–3 | 可复现性与断言强度不等 |

### 4.4 死功能 / 待删越界

| # | 项 | 说明 |
|---|---|---|
| C1 | pi 的 `SessionSearchService` **接口存在、全仓零实现** | pi 侧死接口。**java 反向反超**：`SqliteSessionSearch` 是 FTS5 真实实现 ⇒ 判定「对齐（java 反超）」 |
| C2 | java `EvalReporter` **零实现、零生产注入点** | 声明了扩展点却无人实现 |
| C3 | `pi-java-evals` **整模块无 `main()`、无 CLI 入口** | 生产零接线，只在测试里跑 |
| **C4** | **java `writer_leases` ＋ 心跳守护线程** | ⚠️ **机制在 java 是活的**（A17 证明真会抛 `writer lease was lost`），但 **pi 明文删除**（`repo.test.ts:368-371` 断言该表不存在）⇒ 按 R1 属**待删越界功能**（**C12**） |
| C5 | **9 处 javadoc 引用不存在的 pi 文件** | 逐个 `find -iname` 验证**全部 ABSENT**。**最严重是 `WriterLease.java:8`** —— 引用的 `writer-leases.ts` 是被 pi **明文删除**的文件（D9） |
| C6 | **`SqliteConformanceGroup1Test.concurrentLaneWritesSerializeWithDistinctSequences` 在全 reactor 下挂 1990 s** 后抛 `writer lease was lost` | 隔离复跑 **1.6 s 全绿（16/16）** ⇒ 负载敏感的锁等待。**未定位前不修**（同 A12 纪律）。线索：1990 s 不对应 `busy_timeout=5000` 也不对应 TTL 30 s ⇒ 更可能是 **surefire fork 等待/超时** |

### 4.5 修法与性价比（报告已给）

| 差距 | 权重 | 修好后 | 每点权重换 |
|---|---:|---:|---:|
| **SQLite schema 分叉** | **25** | 20.8% → **31.9%**（+11.1pp） | — |
| **JSONL v4 双向不可读** | **5** | — | **性价比最高**（每点 0.39pp，是 schema 分叉的 **1.6 倍**效率） |

- **SQLite schema 分叉是单点最大拖累**：权重 25、**一分不得**，一项吃掉 9.3pp。
- **JSONL v4 不可读权重只有 5，但它是用户可见的硬故障**（换机/换工具后会话直接读不出来），不是形状偏差 ⇒ **应先修**。
- **B59/B60/B61 应同包**（同根因：JSONL v4 线格式 ＋ 4-kind `Write` 模型）。
- **B59 已有裁决方向**：跟 pi 的 4-kind `Write` 模型（`lane`/`record`/`fact` 全部改道 `scalar_values`）—— 见 **F8**。

### 4.6 抽取时撞出的偏差（须登记）

1. **报告引用错误**：报告写「台账 `docs/32:443`『evals 完整测试矩阵』仍是 OPEN」—— 实际该条目在 **`docs/32:573`**，且位于 **§8 G 类「已结案（别重开）」** ⇒ **不是 OPEN**。
2. **`E6` 部分已解决**：`SqliteSessionStorage` 实测 463 行（已拆出 `storage/` 子包 10 文件）⇒ **E6 应改为「仅 `AgentHarness`」或与 E4 合并**。
3. **`E7` 条数少算**：实际是**三处**（JSONL/Memory/**SQLite**）。
4. **取证纪律**：pi 工作树**中途从 `3390bd936` 翻回 `71dca871b`**（`git rev-parse HEAD` 实测旧提交、`git status` 干净）⇒ **直接读工作树会静默拿到旧状态**，后续复测一律走 `git show 3390bd936:<path>`。

---

## 5 `pi-java-telemetry`

**面貌**：缺失 5 条 · 需完善 9 条 · 死功能 4 条（其中 1 条是**pi 自己的对照物就是死的**）。
**一句话结论**：**telemetry 缺的都是小件** —— 权重 3 的 7 条里**没有一条缺失**（2 对齐 ＋ 5 存疑）。

### 5.1 缺失（5 条，权重全在 1–2）

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| **T7 `setStatus` ＋ `SpanStatus{ok\|error}`**（显式置状态、后写胜、不被自动覆盖） | 2 | trace 行的 `status` 仍能正确报 error（由自动派生保住），但 pi 的两条语义**不可表达**：①「显式置 ok 覆盖自动 error」②「返回失败值而不抛」。**第三方按 pi 契约写的 adapter 编译不过** | — |
| T5 `addEvent(name, attributes)` | 1 | ⚠️ pi 生产源码**零调用点**、两份 schema **无 `events:` 声明** ⇒ 今天**无行为差异**，差异只在契约面 | H |
| T9 `InMemoryTelemetryContext` ＋ `RecordedTelemetrySpan/Event` | 1 | 没有进程内参考实现可当测试/宿主的跨度收集器 | H |
| T10 `createTelemetryAdapterConformance` 套件（9 用例/6 组） | 1 | 三个 adapter（其中 JSONL/OTel 是 pi **没有**的扩展）没有 runner 无关的契约套件。逐条分诊：pi 那 9 条 pi-java **只满足 2 条** | C7（**倾向不做**，触发条件＝出现 OTel 之外的真 adapter） |
| T11 `defineTelemetrySchema` ＋ `createTypedSpanStarter` | 1 | 跨度名/属性名无编译期约束。⚠️ **pi 侧这份词汇本身没有行为**：12 个 `pi.*` 跨度 **11 个零发射点**、`startAiSpan(` 零调用、生产**从不安装真 adapter** ⇒ 它是「被测试守住的文档」 | H |

### 5.2 需完善（9 条）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **T1 `startSpan(options, callback)`** | 3 | pi 返回 `Promise<T>`、回调可异步；java 是**同步** `Function` ⇒ **「回调异步完成后仍算在跨度内」的 pi 语义在 Java 结构上不可表达**（**结构性不可满足**，改签名才可能） |
| **T2 `TelemetrySpan`** | 3 | ① **结算归属相反**（pi 无 `end()`，结算由 `startSpan` 拥有；java `AutoCloseable.close()`）② 属性写入**逐键 vs 整包合并** ③ 事件/状态见 T5/T7 |
| **T12 `openSpan`** | 3 | pi **无对照物**，但 java 走**默认路径**（每次运行/每次 LLM 请求/每次工具调用/压缩两处）⇒ 跨度可跨 action/线程边界，代价是 `close()` 靠调用方 |
| **T14 `incrementCounter`/`recordTiming`** | 3 | pi **无对照物**；java trace 文件里恒有指标行 |
| **T16 `JsonlFileTelemetry`** | 3 | pi **无对照物**（pi 只有 noop ＋ 测试用 memory）。java **每个会话无条件接线** ⇒ 这是 pi-java 的**净增量，不是对齐面** |
| **T6 `setAttributes` 整包合并 vs `addAttribute` 单键** | 2 | pi 有**后写胜 ＋ `undefined` 删键 ＋ 失败原子回滚**；java **无批量 API**，因而也没有这些概念 |
| **T13 `recordEvent`/`recordsPayloads`/`pushCurrent`/`popCurrent`** | 2 | ⚠️ 与 pi 的 `addEvent` **不是同一个东西**（java 是整份负载的独立行，pi 是挂在 span 上的小属性）⇒ 可观察性受 `--trace-payloads` 门控（**默认关**） |
| **T15 `with(key, value)`** | 2 | pi 无对照物。**D1**：`JsonlFileTelemetry.with(...)` 返回**新实例**（新文件句柄/新锁/新 `ThreadLocal` 栈）⇒ 已写入后再 `with` 会**切到另一个文件** |
| **T4 `AttributeValue` 类型闭集** | 1 | java 是 `Map<String,Object>`、**无闭集** ⇒ 写错类型**静默丢弃且两侧不一致**：JSONL 照落、OTel 消失 |

> **T3 保留意见**：报告判「对齐」，但那**只覆盖 `{name, attributes}` 的形状**；属性值类型闭集缺失（＝T4）⇒ **类型层面的对齐不成立**。

### 5.3 死功能

| # | 死功能 | 说明 |
|---|---|---|
| 1 | **`OtelTelemetryContext`（192 行）** | 全仓**无生产消费者** ⇒ 宿主必须自己 new 一个 `OpenTelemetrySdk` 传入，否则 `getTracer/getMeter` 全是 no-op |
| 2 | **payload 事件通路**（`recordEvent`/`pushCurrent` 等） | 热路径**无条件**调用，但 `recordPayloads` **默认 false** ⇒ 默认配置下一次会话的 trace 文件里**没有一条 event 行** |
| 3 | 接口 `openSpan` 的 default 实现返回**已结束**的 span | 今天**不可达**（三实现全显式转发）；但任何只实现 `startSpan` 的新 adapter/装饰器会拿到一个已关闭的跨度（**javadoc 未写此约束**） |
| 4 | **pi 侧的对照物本身是死的** | 这决定了 T5/T10/T11 的权重，也是「**补完也没有观察者**」的论证 |

### 5.4 修法建议（报告已给，且已裁决）

- **只修第 6 条（settled 父 ⇒ 子跨度惰性）**，其余以「**显式差异声明**」结案。
- **不补 `setStatus`/`addEvent`/`setAttributes` 的三条理由**：① 它们服务 pi 的 **schema-typed** 跨度族，而那份 schema 在 pi 里 11/12 无发射点、生产是 noop ⇒ **补完也没有观察者**；② `addEvent` 与 java 的 `recordEvent` **不是同一个东西**（照搬会把整份消息列表塞进 span 行，文件结构与离线消费方式剧变）；③ 补完 `setStatus` 仍要声明两处**结构性**差异，属「半个对齐」。
- ⚠️ **差异声明必须写进 javadoc，而不只是文档** —— 否则「下一个拿 pi 契约来对表的人会**再次**把它们误判成缺口」（**已落地**）。

### 5.5 抽取时撞出的偏差（须登记）

1. **`D2` 行号更正**：原引 `JsonlSpan.startSpan:338-349`，实际 `JsonlFileTelemetry.java:340-358`。
2. **`D3` 行号更正**：原引 `TelemetryContext:32-39`，实际 `TelemetryContext.java:47-54`。
3. **`C5`（开/关顺序不对称）原文引用与当前文件不符**：实际是 `JsonlFileTelemetry.java:133`（popCurrent）→ `:134`（close），而 `PiLaneSink.java:210`（close）→ `:211`（popCurrent）。
4. **`phase1-pi-code-mapping:130` 记的 `addLink` 是错记** —— **pi 侧没有 `addLink`**。
5. **核对质量**：报告内 pi 侧 17 条引用**逐条命中、零漂移**；java 侧除上述三处外全部命中。

---

## 6 `pi-java-agent-core`

**面貌**：缺失 22 条 · 需完善 40 条 · 死功能 4 条。
**这是权重 3 层「零缺失」的模块** —— 默认路径上没有整块缺失；缺口集中在两类：**事件/载荷形状** 与 **格式代差**。

### 6.1 缺失

| 功能 | 权重 | 用户可观察后果 | 台账号 |
|---|---:|---|---|
| **手动 `/compact` 先 `abort()` 当前运行** | **2** | 运行中 `/compact` 与在飞请求**重叠**（pi 先中止）；压缩**无 per-operation 取消句柄** | **A4/F4** |
| **per-model 压缩设置** | **2** | 换模型后压缩阈值/reserve **不跟模型走** ⇒ 压缩时机与 pi 不同 | **A9** |
| **`queue_update` 生产者** | **2** | 队列变化**不通知任何宿主** ⇒ TUI/RPC/web 看不到排队 | **B34** |
| **`thinking_level_changed` 生产者** | **2** | `/thinking` 改了级别，**任何宿主都收不到** | **B35** |
| **`formatSkillsForSystemPrompt`** | **2** | 系统提示里**没有技能清单段** ⇒ 模型不知道有哪些技能可读 | — |
| **`formatSkillInvocation`** | **2** | 技能调用注入块缺失 | — |
| **扩展开启（extended thinking）目录→请求投送** | **2** | **扩展思考在 CLI 生产上不可达**（`forLevel(Enabled)` 恒 OFF ⇒ 永不发 `thinking`） | **B15** |
| **工具装配变更声明 `declareToolChanges`** | **2** | 会话中途增删工具时模型**看不到「哪些加了/删了」** | **B68** |
| **`prepareNextTurn` 可返回 `messages`** | **2** | 钩子无法向下一回合**追加消息** | **B69** |
| `powershell` 工具 | 1 | Windows 上模型没有 powershell 工具 | — |
| **branch summary 生成** ＋ **会话树导航 `navigateTree`** ＋ **branch summary 设置** | 1 | 回退到旧分支时**没有摘要可注入**；无法在会话树里跳转 | **B1** |
| **`session_info_changed` 生产者** | 1 | RPC `set_session_name` 改了名**客户端永远不知道** | **B30** |
| **`sanitizeSurrogates`** | 1 | 孤对代理字符 ⇒ 请求体 JSON 非法 ⇒ **provider 400** | **B16** |
| `abortCompaction` | 1 | **TUI 无法取消压缩** | **B36** |
| 会话列表 `onProgress` | 1 | `-r` 选择器大目录下无进度、**不可取消** | **B55** |
| `CompactionEntry.fromHook`/`BranchSummaryEntry.fromHook` · `AdaptivePublisher` · `streamProxy` · `setDefaultStreamFn` · 存储 conformance 套件（2,716 行） | 1 | 内部/测试基建 | A17 |

### 6.2 需完善（存疑，节选要害）

| 功能 | 权重 | 差异 |
|---|---:|---|
| **`bash` 工具超时语义** | **3** | pi：`timeout` 可选、**无默认值**、上限 `2147483` s；java：默认 **120** s、上限 **600** s（超限**抛异常**）⇒ 模型不传 timeout 时 **>120 s 命令被砍断** |
| **`grep` 工具** | **3** | pi 有 `ignoreCase`/`literal`/`context`/`limit` ＋ **尊重 gitignore** ＋ 截断；java 只有 `{pattern,path,glob}` ⇒ **会搜到 `.git`/`node_modules`、大结果不截断** |
| **`find`/`glob` 工具** | 2 | **名字不同**（pi 叫 `find`）；java 缺 limit、无 gitignore、全树 walk |
| **`ls` 工具** | 2 | **参数集不同**（java 多 `recursive`、少 `limit`）⇒ 按 pi schema 传 `limit` 被忽略 |
| **`agent_end` 载荷** | **3** | pi = **本 pass** 的 `newMessages`；java **会话层**发 `accumulatedMessages()` ⇒ 重试/多 pass 下**比 pi 多** | **A8** |
| **Entry union** | **3** | pi v4 **4 类**；java **8 类**（多 `model_change`/`thinking_level_change`/`active_tools_change`/`custom_message`）⇒ **格式代差：会话文件与 pi 互不可读** |
| **`convertToLlm`** | **3** | pi 新增 `case "system"`（三选一 → **四选一**） |
| **JSONL v4 存储** | **3** | java 是**平铺**实现，**无事务语义** |
| **`latest()`/`find()` 会话定位范围** | **3** | java 直接 `list(all())` ⇒ 扫**全部**会话文件且**跨项目**（实测 2135 文件时 1082/756/608 ms） | **B53** |
| **系统提示作为 transcript 的 system 消息** | **3** | **首条 prompt 等价、会话中变更不等价** | **B67** |
| **`AgentSessionEvent` 面** | **3** | pi **22 变体**；java **19**（缺的三个即 §6.1 的 B30/B34/B35） |
| **`prepareNextTurn` 的 `thinkingLevel` 通道** | **3** | **不出现在任何帧上**（差分结构上覆盖不到）⇒ 无守护 | **A3** |
| **`prompt()` 的 `streamingBehavior`** | 2 | pi：运行中 prompt **必须显式带** `'steer'\|'followUp'`，否则抛**带指引的**错误；压缩中**禁** prompt。java **无门**（门在 `PiLaneEngine`，抛裸 `IllegalStateException`）⇒ **错误文案与门位置不同** | **A5/F4** |
| **`turn_end` 颗粒度** | 2 | pi **每回合**一条；java `AgentSettled` 是**每次驱动**一条 ⇒ 末回合纯文本收尾时 `toolResults` **恒空** | **B49** |
| `QueueMode.All` 宿主语义 · `retry.provider.*` ↔ `RetryPolicy` · 工具线程安全契约 · 错误面 15 个 `TaggedError` · 四消息类型 · fork 策略 · 输出捕获形状 · skills/prompt-templates 归属 | 1–2 | 形状/归属不同 | F1/A7/A10/A11/A1 |

### 6.3 死功能

| # | 死功能 | 为什么生产不工作 |
|---|---|---|
| 1 | **`ThinkingLevelMap` 整条投送链** | 非空 map **生产上不可构造** ⇒ `forLevel(Enabled)` 恒 OFF（即 B15） |
| 2 | **`StreamSimple`** | 3 个调用点**全在测试** ⇒ **别把它当接缝** |
| 3 | **`Entry.ActiveToolsChange` 整族** | 类型与编解码在、**发射与写入都不工作**（生产零调用者） | **C1** |
| 4 | **三个会话事件的「定义 ＋ 映射」** | `QueueUpdate`/`SessionInfoChanged`/`ThinkingLevelChanged` **零 emit** ⇒ 定义与映射是死代码 | B30/B34/B35 |

### 6.4 R5 对本模块的影响（**已核算**）

排除面在本模块共占 **Σ权重 11 / 214**（缺失 9 ＋ 存疑 2）⇒ **在册分母应为 203**，
**在册加权完成度 65.3%，高于报告的 62.4%**。被排除的是：`pico3` · `durable` · `AgentHarness` 具名钩子 ·
`HarnessEvent` 28 类 · `run_suspend`/`DeferredHandle` · `runtime/drive/*` · `effect gate` · `reduceLaneSnapshot` · lane 概念。

> **两个权重 2 的缺失（`declareToolChanges`、`prepareNextTurn.messages`）是 pi 本轮 111 提交里真正的行为变化** ——
> 其余 99.3% 的 LOC 增量是未接线的 pico3 ⇒ **这次 pi 的前进对 pi-java 的实际对齐压力几乎为零**。

### 6.5 抽取时撞出的偏差（须登记）

1. **两处锚点误引到排除面**：`transformContext` / `transform_context` 被引到 `agent-harness.ts:443`（R5 排除面）。
   **主流 legacy 锚点**是 `agent/types.ts:207` ＋ `agent-loop.ts:348-349` ＋ `agent.ts:116/:488`。
   同理 `shouldStopAfterTurn` 的 legacy 锚点是 `agent/types.ts:230` ＋ `agent-loop.ts:258`。
2. **`B16` 计数**：pi 全仓 **57** 处（报告记 54）。
3. **`#77` 会话恢复的 pi 侧证据全在 R5 排除面**（`restore.ts` 只被 `runtime/harness.ts` import），
   仅因 java 侧有主流对应物而暂留 B 表。
4. **`#59 LaneConfiguration` / `#60 PendingEntry`·`InboxItem` 的消费者全在排除面内** ⇒ 若严格执行 R5，这 4 点权重应移入非目标。

---

## 7 整体计划

### 7.1 三条排期原则

1. **先修权重 3 的缺失** —— 它们决定「用户今天会不会撞到」（`docs/40 §2` 就是这份队列）。
2. **死功能与接线优先** —— 投入最小、可见性最高（改一行 vs 写一个新子系统）。
3. **硬故障插队** —— 权重低但用户直接撞上的（会话读不出来、`-r` 崩溃、flag 静默无效）不等排期。

### 7.2 补全包清单（建议次序）

> ⚠️ **2026-09-22 用户改判：ai 模块整体先行** —— 下面的 H1→H6→I1–I3→C1→D1–D5 次序**已被取代**。
> H1 之后不走 H2，而是先把 `pi-java-ai` 的剩余缺口清完（代号 **A0**…），H2–H6 与后续梯队**顺延**。
> A0（硬故障与凭证小件）**已闭环**：见 `docs/43`（设计 ＋ §8 提交清单 ＋ §9 实测校正 ＋ §10 逐步记录）。

**第一梯队 —— 权重 3 的缺失 ＋ 硬故障（用户今天会撞到）**

| 包 | 模块 | 内容 | 为什么排这 |
|---|---|---|---|
| ~~**H1**~~ | `ai` | ✅ **已闭环**（2026-09-22，七提交 `5b703a9..7c3db0d`，实施记录 `docs/42 §10`；范围比原两条宽——四分量生产者＋`calculateCost`＋全车道归一＋下游接线＋L5 S15 差分） | 原 **+5.50pp**，占四条 P0 的 **76%** |
| ~~**A0**~~ | `ai` | ✅ **已闭环**（2026-09-22，九提交 `34ca389..5f3833d`，设计/记录 `docs/43`）——`sanitizeSurrogates` 24 个落线点 ＋ `RetryableError` 两模式 ＋ Anthropic Bearer/OAuth 凭证链（`AuthKind`） | 三件事都是「今天会撞到且有硬后果」：provider 400 ／ 不重试直接失败 ／ Bearer 型网关连不上 |
| ~~**H2**~~ | `ai` | ✅ **已闭环**（2026-09-22，七提交 `af5139e..9bbcd48`，设计/记录 `docs/44`）—— 图片四车道（Anthropic ／ completions ／ Mistral ／ responses）＋ 共享非视觉降级闸 ＋ 拆两条超限车道。⚠️ **Google 拆出去另立一包**（实测其工具结果整条路径不存在，`docs/32` B84） | 原：`ReadTool` 读图模型**完全看不到图**（静默） |
| **H3** | `coding-agent` | **上下文文件发现**（`AGENTS.md`/`CLAUDE.md` ＋ `--no-context-files` 联动） | **每次会话都走**，现在**静默失效**；同时救活一个死 flag |
| **H4** | `coding-agent` | **扩展钩子桥接**（暴露 `hookSystem()`） | 8 个引擎钩子**已经在 `HookSystem` 里** ⇒ 这是**接线不是新建**，最省的一包 |
| **H5** | `ai` ＋ `agent-core` | **B15 扩展思考打通**（与 B8 共用载体 ⇒ 一次投送修复） | 把死功能救活；改一行 ＋ 生产侧构造 |
| **H6** | `session` | **JSONL v4 双向不可读**（B60/B61 同包） | **性价比最高**（每点 0.39pp）；**硬故障**：换机/换工具会话读不出来 |

**第二梯队 —— 接线与死功能（投入小、可见性高）**

| 包 | 模块 | 内容 |
|---|---|---|
| **I1** | `tui` | **6 个键位接线**（+4.3pp，改 `default ->` 分支）＋ 决定 Markdown/高亮两条死码「接线还是删声明」 |
| **I2** | `coding-agent` | **四个零消费者 flag 接线或删声明**（`--models`/`--extension`/`--no-context-files`/`--offline`） |
| **I3** | `coding-agent` | **`-r` 崩溃修复**（应开选择器）＋ **扩展 UI 注入被吞**（让注入回流到 `DefaultExtensionContext`）＋ 8 个零消费 settings 键 |

**第三梯队 —— 小裁决闭环（C 阶段，四条都小）**

| 包 | 内容 |
|---|---|
| **C1** | **F6 取证**（pi 侧 `baseUrl` 谁赢）＋ **F1** `QueueMode.All` 宿主语义 ＋ **F2** `recordEvent` 环境态 ＋ **F4** 两道门（`/compact` abort-first ＋ 并发 prompt 路由语义） |

**第四梯队 —— 大件（各自另立设计包）**

| 包 | 模块 | 规模 |
|---|---|---|
| **D1** | `coding-agent` | **扩展事件面 37 → 0**（pi 侧 D 域单独把模块拉低 13.7pp） |
| **D2** | `coding-agent` | **包生态层**（npm/git 源 ＋ 版本/range/pinning ＋ `settings.packages` 持久化） |
| **D3** | `session` | **SQLite schema 4-kind 重写**（G′；跟 pi 的 `Write` 模型） |
| **D4** | `agent-core` | **branch summary ＋ 会话树导航**（B1 整族） |
| **D5** | `tui` | **渲染面**（Markdown/高亮/主题 token/逐工具渲染器） |

**已取消**（R5）：~~`chord` 地基~~ · ~~RPC 三模块重建~~ · ~~多进程子系统~~ · ~~harness/durable drive~~

### 7.3 每个包怎么走（设计方案的形式）

**不是一份大设计，是每个包一份设计文档**，形状照 `docs/33`–`docs/39`（即 `docs/31 §8.x` 的「设计」小节）。
内容要求按 `docs/00 §3 步骤 2`：

1. 包结构 ＋ 类图
2. 关键接口/类的签名（完整 Java 代码）
3. 数据流（Mermaid 序列图）
4. **测试策略 —— 含变异探针**：先证明夹具会红，再写实现
5. 验收标准（可量化）

**流程**（你定的「设计文档先行」）：**设计文档 → 你审核 → 才许写代码 → 实施记录续在 §8.x**。

> ⚠️ **两条本清单暴露的夹具教训**（写设计时就要防）：
> ① **夹具写在实现之后 ⇒ 没有红灯可看**，只能靠变异探针，而**变异点由人挑**（`docs/31 §8.34.11`）；
> ② **夹具「第一版没牙」** 反复出现 —— 内层解绑用例从 span 对象进、`ArrayList` 收 worker 线程的帧、`activeTools` 为空不显式给 `Sequential`……
> ⇒ **判据：先问「这个夹具在什么情况下会红」，答不上来就是没牙。**

### 7.4 本清单**未覆盖**

- 未改任何生产代码、未动 `docs/32` 台账、未重算 `docs/40` 总表的分母。
- ⚠️ **R5 在 `docs/40`/`docs/32`/`docs/map` 的落地未做**（`docs/40` 的那份半成品已按用户指示**回退**）⇒
  那三处仍是 2026-09-20 口径，**与 R5 相悖**。要重新落地，按「细分口径」推：`docs/40`（总表重算、§1.3/§3.1/§3.4/§5.1/§6/§7/§8/§10）＋
  `docs/32`（F7/F9 翻「不做」、B62 结案、C12 注明终局）＋ `docs/map/01`·`03`·`06` 的注。
- 各模块报告的**行号漂移**（agent-core 2 处锚点、tui 8 条、session 4 条、telemetry 3 处、coding-agent 8 条、ai 4 条）**已就地登记在各自 §x.7**，未回改 `docs/map/*`。
- **R5 的 harness 细分口径**已按裁决执行（只踢 harness 独有）；`docs/map/03` 里引 harness 副本的单元（`compaction`/`messages`/`session`/`tools`）**锚点应改指主流副本**，**未改**，登记在 `docs/map/03`。
