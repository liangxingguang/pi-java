# 06 pi-ai 层未对齐清单

> 这份文件只回答一个问题：**pi-java-ai 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：
> `git log --follow -- docs/06-ai-alignment-gaps.md`）。
>
> - **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)（B 编号与裁决以此为准）；
>   **全量加权盘点**是 [`map/02-ai.md`](map/02-ai.md)（逐单元判定、权重、完成度）。
> - 本稿 = 两者的 ai 层索引：§1 台账 B 项，§2 地图判「缺失」但没有 B 号的能力单元，
>   §3 形状差（存疑、待裁决），§4 已裁决不做。
> - pi 锚点 `ea448f454`（2026-10-11）。**七份地图（含新建 07-mcp）已于 2026-10-12
>   对该锚点逐条重测完毕**（原 A16 销号，新差异 B233/B234 已登记；**B233 批 2 已销**）。
> - **批 2（Mistral，2026-10-13 闭环，[`docs/10`](10-ai-batch2-mistral-design.md)）**：
>   B80（数组 content 三支＋assistant thinking/tool_calls 重放）、B233（x-affinity＋
>   prompt_cache_key）销号；B239 即登即销。
> - **批 3a（模型目录数据面，2026-10-14 闭环，[`docs/11`](11-ai-batch3a-catalog-data-design.md)）**：
>   17 个 npm `pi-ai@1.1.0` 分片（588 chat）逐字节入资源＋manifest sha256 信任锚；
>   B97（3 个漂移 id＋Java 独有的 text-embedding 类）、B232（v1.1.0 数据漂移）销号；
>   map 单元 #34/#86/#91 转对齐、#90 转存疑（消费链 B85 另案）；B137 只落默认模型表
>   防回归切片，glob/scope 主体留批 3b。
> - **批 3b（resolver glob/scope，2026-10-14 闭环，[`docs/12`](12-ai-batch3b-model-scope-design.md)）**：
>   minimatch 子集＋子串＋alias/dated 排序＋两码 scope 诊断＋`--models` 消费链接通；
>   B137 销号（行为台账项，加权不变 92.2%）；残留窄差见 04 B137 行（歧义硬错误/localeCompare/
>   brace/反向 cycling/auth 过滤/resume 门近似，均经裁决）。
> - **批 4（thinking/effort，2026-10-14 闭环，[`docs/13`](13-ai-batch4-thinking-effort-design.md)）**：
>   B128（thinkingBudgets 通道）、B99（managed effort：block_binding/两 beta/温度门/
>   effort marker 重建）、B110（providerThinkingLevel）销号，加权不变 92.2%。探针附带修正：
>   beta 协商改走 anthropic-beta **请求头**（body 的 betas 键从不上头，四个旧 beta 从未生效）。
>   残留窄差：bedrock/vertex budget 支、agent proxy HTTP 透传（04 B99 行）。
> - **批 5（消息字段与终局计量，2026-10-15 闭环，[`docs/14`](14-ai-batch5-message-fields-design.md)）**：
>   B148 流内半（responseModel＋fallback 成本归属／mid-output fallback 硬错／
>   input_transformations 诊断／pi-messages 失败信封＋rewrite）、B234（响应 durationMs
>   身份缝加盖；工具 durationMs 只围 execute() 本体贯通事件与 JSONL/双 wire）、消息级
>   thinkingLevel（agent loop 终局盖，含 off）销号；map 单元 #37 转对齐（+1），
>   **加权 92.2%→92.7%**。探针附带 settle：SDK 2.66.0 未知块经 `_json()` 逃生槽可达；
>   web 面补回批 4 漏同步的 providerThinkingLevel。残留：B148 消费侧（usage-totals/
>   cache-warmer/bug-report）、B234 的 TUI「Took」卡另案。
> - **2026-10-12 ai 批 0 裁决**（[`docs/08`](08-ai-layer-alignment-roadmap.md)）：B115/B117/B124
>   取证关单；B100/B126/B147/B226/B230 转 04 §4；map 单元 #87 零代码对齐，#9/#10/#11/#12/#33/
>   #74/#78/#92/#93/#94/#100 出分母（R5/w0/作废）；§1 剩 24 条。
> - 整理：2026-10-12（旧版盘点过程稿已删；同日 docs/41 重编号为 06）。

---

## 1. 台账在册功能缺口

`docs/04` §3.2 存活 10 条（批 1 销号 4 条；批 2 销号 B80/B233，新登即销 B239；
批 3a 销号 B97/B232；批 3b 销号 B137；**批 5 销号 B148 流内半/B234**，消费侧另案；
**批 6 销号 B81/B231**；**批 7 无 B 号可销——#95/#96/#97 是地图单元**（见 §2/§3 出表），
取证另登 B240；**批 8 销号 B240**（ChatGPT 请求时特判，重试臂取证时已在、classifier 臂真空）；
原 §3.3 的 B222 随批 1 销号，§3.3 已无 ai 协议面条目）：

| # | 差什么（一句话） |
|---|---|
| ~~B81~~ | ✅ **批 6 已销（2026-10-15，[`docs/15`](15-ai-batch6-oauth-identity-design.md)）**：~~OAuth 分支两面未移植~~ 系统前缀块＋工具名双向映射＋身份 beta 对＋伪装版本 2.1.280 全落 |
| B85 | read.ts：图片自动缩放 ＋ 非视觉模型注记（全仓无 `javax.imageio`） |
| B94 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明缺失（补片段时须同时把 ContextAssembler 回落描述改回 pi 的稀疏规则） |
| B95 | `docs` 段无生产者（ContextAssembler 不传 DocumentationPaths） |
| B99 | `supportsMidConvoEffort` 半可移植：字段 ＋ 两个 beta ＋ `insertThinkingLevelMessages`（SDK 无 BlockBinding） |
| B110 | `providerThinkingLevel` pi 有 Java 无（`proxy.ts` 线字段 ＋ `anthropic-messages.ts` 消息字段；批 4 随 B99） |
| B116 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言（pi usage 只在消息上，proxy 线另发扁平 usage）——2026-10-12 取证线格式已扁化，内部组件保留 |
| B128 | `thinkingBudgets` 无选项通道（pi `SimpleStreamOptions.thinkingBudgets`，`adjustMaxTokensForThinking` 消费；批 4） |
| ~~B148~~ | ✅ **批 5 已销（流内半，2026-10-15，[`docs/14`](14-ai-batch5-message-fields-design.md)）**：responseModel＋fallback 成本归属、mid-output fallback 硬错、input_transformations 诊断、pi-messages 失败信封/rewrite；~~responseModel/responseId/diagnostics 无真消费者~~（消费侧 usage 分组/cache-warmer/bug-report 仍另案，responseId 随 codex 出分母） |
| B156 | ModelCompat 逐组件规范构造 ＋ 12 个便捷构造各自补 null：加组件必须全改，否则静默缺省 |
| B158 | 输出截断字节预算 Java 100KB vs pi 主流 50KB（模型可见文案不同；对应 map/03 #23） |
| B159 | pi 已改 `finishTurn()` 返回 `{action:"continue"|"end"}`，Java 仍是 Boolean `shouldStopAfterTurn`，缺「强制再跑一轮」臂（对应 map/03 #6/#41） |
| ~~B231~~ | ✅ **批 6 已销（2026-10-15，[`docs/15`](15-ai-batch6-oauth-identity-design.md)）**：~~刷新取消丢轮转 token／HTTP 无超时~~ 刷新整体挪到脱离调用方中断的虚拟线程、刷新 POST 15s 硬超时（PKCE/device 两流） |
| ~~B240~~ | ✅ **批 8 已销（2026-10-16，[`docs/17`](17-ai-b240-chatgpt-request-time-design.md)，commit `efa66016`）**：~~非 `sk-` 前缀订阅 token 裁字段、分类器过滤、共享用量码重试~~ `isChatGPTSignIn`（provider=openai＋官方 baseUrl 严格相等＋非 `sk-`）为真裁 `prompt_cache_retention`/`prompt_cache_options`/`max_output_tokens`/`temperature`（`prompt_cache_key` 锚点确认不裁，旧文有误）；重试三码取证时已在 `RetryableError`；`filterAllModels` 真空（Java 无 classifier 模型面，随 R5） |
| ~~B234~~ | ✅ **批 5 已销（2026-10-15，[`docs/14`](14-ai-batch5-message-fields-design.md)）**：响应 durationMs 身份缝加盖；工具 durationMs 只围 execute() 本体，贯通 tool_execution_end/ToolResultMessage/JSONL/双 wire；~~`durationMs` 计时字段全无~~（TUI「Took」卡仍另案） |

---

## 2. 地图判「缺失」、但没有 B 号的能力单元（2 条，计分母）

编号是 `map/02-ai.md` 的单元号。2026-10-12 批 0 后原 21 条中 11 条移出分母（见 §4）、#87 转对齐；
**批 3a（2026-10-14）#34/#86/#91 转对齐出表、#90 转存疑（见 §3）**；
**批 5（2026-10-15）#37 转对齐出表（responseModel/diagnostics/durationMs/thinkingLevel，+1）**；
**批 7（2026-10-16）#95/#96 转对齐出表（ChatGPT 订阅登录、copy-code，+2）**。

**ContentBlock（1）**：#8 `ToolUseContent.namespace`（随 MCP 包 B203；仅 Responses 车道 round-trip）。

**鉴权（1）**：#98 Anthropic workload identity federation（5 个 `ANTHROPIC_*` env，**用户裁决后排**）。

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
| 元数据（5） | #24 `Model.input` 数组 vs `ModelCapability`＋`supportsImageInput` · #28 `Model.reasoning` vs `THINKING` 能力位 · #38 `ToolResultMessage.usage` 留形状无生产者（`details` 已接） · #88 `JsonObject` 以 `Map`＋Jackson 表达（裁决零代码） · **#90（批 3a）** `InputLimits` 三层元数据与 models.json 深合并已落，但图片缩放/注记消费链（B85）在 coding tools 另案，ai 层字段零消费者 |
| 鉴权（2） | #46 多 profile 凭证（Java 扩展） · #47 models.json 自定义 provider 形状不同（~~#97 共享回调服务器/登录页批 7 已转对齐~~：`LoopbackCallbackServer`/`OAuthPages` 落地） |
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
- **2026-10-14 批 3a（模型目录数据面）闭环**（设计 [`docs/11`](11-ai-batch3a-catalog-data-design.md)）：
  npm `@earendil-works/pi-ai@1.1.0` 的 17 个分片（588 chat，391KB）逐字节复制进
  `pi-java-ai/src/main/resources/catalog/`，`.manifest.json` 的 sha256 逐文件夹具校验
  （`.gitattributes` 钉二进制防 autocrlf），ATTRIBUTION 履 MIT；`BuiltinModelCatalogs`
  按 `flattenChatModelCatalog` 拍平（只收 `type:"chat"`，image 61/classifier 15 不收）、
  复用 models.json 的 compat/pricing/tlm 链；删手写 `OpenRouterModels`/`XaiModels`/
  `CatalogCompatRules` 与 5 个 BuiltinCatalog 工厂（−640 LOC）。map #34/#86/#91 转对齐、
  #90 转存疑（见 §3）；B97（deepseek-flash/mistral/kimi/mimo/qwen/ling/embedding 等漂移 id）、
  B232（haiku-5-5/fable-5 1M/128K、kimi-k3 钉价、prompt-length tiers）销号；
  `DefaultModelIds` 落 pi `defaultModelPerProvider` 17 家切片（B137 防回归半，glob/scope
  主体留批 3b）。sptl 三层合并接两 OpenAI 车道（responses 车道补循环级 request.reasoning）。
- **2026-10-14 批 3b（resolver glob/scope）闭环**（设计 [`docs/12`](12-ai-batch3b-model-scope-design.md)）：
  ai 模块落 `ModelGlob`（minimatch 10.2.6 子集，语义靠 npm tarball **静态阅读**取证）、
  `ModelPatterns`、`ModelScopeResolver`/`ScopedModel`/两码诊断；coding-agent 接通
  `--models ?? enabledModels` → 初始模型 → Ctrl+P/RPC scope 轮转；B137 销号，加权不变。
  六条窄差经用户裁决（歧义不硬错/码点序/无 brace/仅正向/无 auth 门/resume 门近似），见 04。
- **2026-10-16 批 7（共享回调／ChatGPT 订阅／copy-code）闭环**（设计 [`docs/16`](16-ai-batch7-shared-callback-chatgpt-design.md)）：
  `LoopbackCallbackServer`（精确路径/state 门、claim-once 409、complete 内交换失败 502、
  cancel/超时、BindException 上抛、回调-手工竞速）＋`OAuthPages`（pi 品牌页逐字）＋`Pkce`
  抽出；通用 `OAuthFlow` 只剩 openrouter 形状（随机 UUID 路径、无 state、5min、30s 交换）；
  map **#95 缺失→对齐**（`ChatGptOAuthFlow`：dynamic_agent_client＋urn:uuid host、签发
  client_id 随回调返回、1455 占用硬失败、直连 scope 门、−3min、刷新带 client_id/resource；
  凭证加 clientId/scopes 两字段，旧文件兼容；注册键 `openai`）、**#96 缺失→对齐**
  （`AnthropicOAuthFlow`：browser 53692→0→无服务器回落＋copy-code 两法 select、JSON 交换/
  刷新、−5min）、**#97 存疑→对齐**（共享服务器/品牌页/竞速）。加权 203.0→205.5（+2.5）。
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
