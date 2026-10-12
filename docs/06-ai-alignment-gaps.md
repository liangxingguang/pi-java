# 06 pi-ai 层未对齐清单

> 这份文件只回答一个问题：**pi-java-ai 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：
> `git log --follow -- docs/06-ai-alignment-gaps.md`）。
>
> - **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)（B 编号与裁决以此为准）；
>   **全量加权盘点**是 [`map/02-ai.md`](map/02-ai.md)（逐单元判定、权重、完成度）。
> - 本稿 = 两者的 ai 层索引：§1 台账 B 项，§2 地图判「缺失」但没有 B 号的能力单元，
>   §3 形状差（存疑、待裁决），§4 已裁决不做。
> - pi 锚点 `ea448f454`（2026-10-11）。**七份地图（含新建 07-mcp）已于 2026-10-12
>   对该锚点逐条重测完毕**（原 A16 销号，新差异 B233/B234 已登记）。
> - **2026-10-12 ai 批 0 裁决**（[`docs/08`](08-ai-layer-alignment-roadmap.md)）：B115/B117/B124
>   取证关单；B100/B126/B147/B226/B230 转 04 §4；map 单元 #87 零代码对齐，#9/#10/#11/#12/#33/
>   #74/#78/#92/#93/#94/#100 出分母（R5/w0/作废）；§1 剩 24 条。
> - 整理：2026-10-12（旧版盘点过程稿已删；同日 docs/41 重编号为 06）。

---

## 1. 台账在册功能缺口

`docs/04` §3.2 存活 19 条（批 1 销号 4 条；原 §3.3 的 B222 同批销号，§3.3 已无 ai 协议面条目）：

| # | 差什么（一句话） |
|---|---|
| B80 | Mistral 数组形态 `delta.content` 无解析路径（pi 有 string/thinking/text 三支） |
| B81 | OAuth 分支两面未移植：系统提示 `"You are Claude Code…"` 前缀 ＋ 工具名 Claude Code 映射 |
| B85 | read.ts：图片自动缩放 ＋ 非视觉模型注记（全仓无 `javax.imageio`） |
| B94 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明缺失（补片段时须同时把 ContextAssembler 回落描述改回 pi 的稀疏规则） |
| B95 | `docs` 段无生产者（ContextAssembler 不传 DocumentationPaths） |
| B97 | 3 个内置 `(provider,modelId)` 与 pi 目录不匹配；另有 Java 有 pi 无的 `text-embedding-3-*` 一类 |
| B99 | `supportsMidConvoEffort` 半可移植：字段 ＋ 两个 beta ＋ `insertThinkingLevelMessages`（SDK 无 BlockBinding） |
| B110 | `providerThinkingLevel` pi 有 Java 无（`proxy.ts` 线字段 ＋ `anthropic-messages.ts` 消息字段；批 4 随 B99） |
| B116 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言（pi usage 只在消息上，proxy 线另发扁平 usage）——2026-10-12 取证线格式已扁化，内部组件保留 |
| B128 | `thinkingBudgets` 无选项通道（pi `SimpleStreamOptions.thinkingBudgets`，`adjustMaxTokensForThinking` 消费；批 4） |
| B137 | resolver 无 minimatch 通配/子串/alias 排序与 scope 诊断；实际序＝精确→切 `:level`→provider 首条→modelName→任意 id 兜底（批 3；pi 的 `--models` 有消费者，旧「零消费者」指 java） |
| B148 | responseModel/responseId/diagnostics 无真消费者（usage 分组、cache-warmer、fallback 成本归属、`pi_messages_response_failure` 信封、Anthropic 流内 fallback 块） |
| B156 | ModelCompat 逐组件规范构造 ＋ 12 个便捷构造各自补 null：加组件必须全改，否则静默缺省 |
| B158 | 输出截断字节预算 Java 100KB vs pi 主流 50KB（模型可见文案不同；对应 map/03 #23） |
| B159 | pi 已改 `finishTurn()` 返回 `{action:"continue"|"end"}`，Java 仍是 Boolean `shouldStopAfterTurn`，缺「强制再跑一轮」臂（对应 map/03 #6/#41） |
| B231 | OAuth 刷新被调用方取消时，轮转后的 refresh token 仍须落盘（`refreshStoredOAuthCredential`）；Java 侧刷新 HTTP 无超时（批 6） |
| B232 | 内置目录数据漂移（v1.1.0）：Haiku 5.5、Claude 5.5 系列改吃 models.dev、Kimi K3 定价/级别表等；与 B97 同族，属持续性数据漂移 |
| B233 | Mistral 开缓存时缺 `x-affinity: <sessionId>` 头（pi `mistral-conversations.ts:366-368`；B103 三车道不含 Mistral） |
| B234 | `durationMs` 计时字段全无：AssistantMessage/ToolResultMessage（及 agent 侧 tool_execution_end/AgentSettled，见 docs/07） |

---

## 2. 地图判「缺失」、但没有 B 号的能力单元（9 条，计分母）

编号是 `map/02-ai.md` 的单元号。2026-10-12 批 0 后原 21 条中 11 条移出分母（见 §4）、#87 转对齐。

**ContentBlock（1）**：#8 `ToolUseContent.namespace`（随 MCP 包 B203；仅 Responses 车道 round-trip）。

**模型元数据（5）**：

| 单元# | 缺口 | 备注 |
|---|---|---|
| #34 | 内置目录规模：Java 手写 73 条 vs pi 锚点全量（npm 1.1.0 数据 18 家即 ~590 条） | 权重 **2**，边际最大单条；批 3 资源目录；B232/B97 同族 |
| #37 | `responseModel`/`diagnostics` 等字段全无（responseId/endTurn 已随 codex 出分母） | 即 B148，批 5 |
| #86 | `Model.promptCache`（cache 寿命元数据，解析但忽略） | 批 3 落字段；CacheWarmer 在 coding-agent 另案 |
| #90 | `Model.inputLimits`（图像输入上限） | 批 3 落元数据；缩放/注记消费链（B85）在 coding tools 另案 |
| #91 | `Model.samplingParamsByThinkingLevel`（per-thinking-level 采样参数） | 批 3，三 OpenAI 车道 merge |

**鉴权（3）**：#95 OAuth `openai-chatgpt`（Sign in with ChatGPT，批 7）· #96 Anthropic OAuth copy-code 登录法
（批 7）· #98 Anthropic workload identity federation（5 个 `ANTHROPIC_*` env，**用户裁决后排**）。

---

## 3. 形状差（地图判「存疑」，计半分；19 条，未认账为缺口）

这些是「Java 有、pi 无」或「两侧形状不同、行为近似」。**不直接当缺口清**；收口任一条前先定
「形状差算不算偏差」，认账后转 §1 立 B 号，或正式记入 `docs/04` §4 裁决不做。

> **2026-10-12 批 0 注记**：#20（StreamError 载 Throwable）、#21（UsageInfo 独立事件）、
> #38（ToolResult.usage 无生产者）取证确认**出线形状与 pi 相同**（内部方言保留，不翻判定，
> 仍计半分）；B115/B117 取证关单（终局帧与 reason 收口两侧同构）。详见 docs/08 §2.2。

| 组 | 条目（map/02 单元号） |
|---|---|
| Provider / wire（2） | `ollama` provider 是 Java 独有扩展 · pi-messages wire 线格自造（**B18 已裁决不做**，04 §4，此条留作地图索引） |
| ContentBlock（2） | #4 `UrlImageContent`、#5 `DiffContent` 均为 Java 独有 |
| StreamEvent（2） | #20 `error` 载 `Throwable` 而非 `AssistantMessage` · #21 `UsageInfo` 事件 Java 独有 |
| 元数据（4） | #24 `Model.input` 数组 vs `ModelCapability`＋`supportsImageInput` · #28 `Model.reasoning` vs `THINKING` 能力位 · #38 `ToolResultMessage.usage` 留形状无生产者（`details` 已接） · #88 `JsonObject` 以 `Map`＋Jackson 表达（裁决零代码） |
| 鉴权（3） | #46 多 profile 凭证（Java 扩展） · #47 models.json 自定义 provider 形状不同 · #97 共享 OAuth 回调服务器/登录页形状不同、行为近似 |
| 横切（4） | #68 `assistant-message-frame` 对应物在 web/protocol（跨模块） · #75 `DeferredHandle` 两侧同状无生产者 · #76 lazy `lazyApi`/`lazyStream` vs `ProviderFactory`/`Protocol` |

---

## 4. 已裁决不做 / 出分母（不展开）

- **2026-10-12 批 0 新裁决**（论证见 [`docs/08`](08-ai-layer-alignment-roadmap.md) §2）：
  - **R5（codex/扩展面）**：#94 结构化分类 API＋4 分类器适配器、#92 统一模型类型（随 #33/#94，独立行为差≈0）、
    #33 per-provider 图像目录（无 CLI/TUI 入口，openrouter 单家已对齐）、#74 transport、
    #78 session-resources（唯一注册者是 codex WS）、#93 nestedCalls（仅扩展工具产生，B162 族）、
    #100 onProviderStreamEvent（唯一订阅者是示例扩展）；B164 转 04 §4。
  - **w0（主流 7 家口径外，用户裁决维持）**：#9 bedrock、#10 vertex、#11 openai-codex（含 WebSocket；
    B126/responseId/endTurn 随它一起出分母）、B226 Radius、B230 azure——五家 /login 技术可达，
    口径松动时 azure/radius 边际成本最低。
  - **作废（死代码）**：#12 cloudflare-ai-binding（pi 侧零生产引用、不导出、无 lazy）。
  - **零代码对齐**：#87 MistralConversationsCompat（唯一字段五车道共享、mistral 模型不挂 compat，
    java 行为已在，同 #88 类型层先例）。
  - **取证关单**：B115/B117（终局帧/reason 收口同构）、B124（pi 无 `maxTokens:0` 生产者、java 已
    OptionalInt 门控）、B100（降架构备注）——B100/B126/B147/B164/B226/B230 在 `docs/04` §4 带触发条件。
- **2026-10-13 批 1（流语义）闭环**（设计 [`docs/09`](09-ai-batch1-stream-semantics-design.md)）：
  map 单元 #23（signature 不再多发空 ThinkingDelta）、#101（strict 谓词探针＋Anthropic 11 关键字
  拒绝）转对齐；B118（toolUse 无工具块守卫）、B119/B121（`StopReason` 闭集枚举化：五车道、
  两个 AssistantMessage record、StreamEvent、Entry.Usage 全切，未知线格值容错 null；B109
  `"tool_use"` 垫片留 JsonlCodec 单一口）、B125（per-request samplingParams 通道）、
  B127（effort 优先级夹具）、B222（responses developer 角色）销号；B147 留 §4 并补分组序夹具。
- **25 个长尾 provider 端点 ＋ 4 条对应鉴权**：权重 0、R2/R5 排除出分母，完整清单见 `map/02-ai.md`
  加权汇总「被排除的单位」。
- **B18**（pi-messages 不按 pi 改造）、**B130/B132**（zai sre、together/baseten/fireworks 携带集零差，
  带触发条件）：见 `docs/04` §4。
- **`ToolResultMessage.addedToolNames`**：pi 已删（R1 作废）。
- 分类器三件套、`noRetryStatuses`、codex 调用方头优先、Bedrock haiku-5/reasoning-effort、
  compat schema 发布等：新锚点屏过不登记（理由见 04 历史批次记录）。

---

## 5. 取证口径

1. pi 侧一律 `git -C /d/workplaceForai/pi show ea448f454:<path>`，不读 pi 工作树。
2. pi-java 侧读当前 HEAD 工作树。
3. 行号会漂：引用优先给**文件 ＋ 符号/关键行**。
