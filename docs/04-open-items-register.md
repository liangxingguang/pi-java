# 04 - 未结项与有效裁决

> 【背景摘要】本文档只保留当前未结项、待用户决定事项和仍有效的范围裁决。已闭环批次、实施过程与旧账面沿革不在这里保存，历史在 git。
>
> 当前目标锚点为 pi `56cae7823`。下列旧锚点开放项必须随 docs/08 的所属层重新取证；未经重测，不自动翻成新基线结论。

## 0. 口径

- **B**：功能或行为缺口。新工单从 B260 起。
- **D**：已登记的窄差。新窄差从 D201 起，明细存于对应 `docs/map/`。
- **F**：需要用户拍板的事项。
- **R1**：pi 已删除的面，不为旧参照物补功能。
- **R2**：Provider 只覆盖已批准的主流范围。
- **R5**：codemode、durable、chord、pico3、micro 等面外子系统不做对齐、不进分母。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本，优先逐字对齐。

## 1. 待用户拍板

| 编号 | 问题 | 待决定事项 |
|---|---|---|
| B58 | 固定 pi 与当前 pi-java 的 OpenAI 默认 wire 均已是 `openai-responses`；旧“Java 默认 completions”的描述已过期 | 是否确认按现状对齐并销号，还是登记刻意偏差。AI 层设计时单独提交裁决，不擅自修改。 |

Chord plugin 已按 R5 决定不做，不再是待拍板项。

## 2. 主流功能与协议开放项

| 编号 | 当前结论 | 所属层 |
|---|---|---|
| B267 | usage／billing 已按 docs/15 闭环；15 个存疑行已按 docs/16 裁决并闭环；Provider wire 已按 docs/17 闭环；retry/overflow 已按 docs/18 闭环；OAuth/device/env 与 pi-ai CLI 权限已按 docs/19 闭环；Faux／deferred 余量已按 docs/20 闭环（2026-11-04）。B267 全闭环，L1 账面 232/232＝100%。 | L1 AI |
| B116 | 对外 JSON 的 usage 已改为 pi 扁平形状；Java 内部 `StreamDone` 仍保留嵌套方言，AI 重测时决定是否继续保留。 | L1 AI |
| B156 | `ModelCompat` 逐组件重建存在漏字段风险；新增组件时必须同时检查构造点和 `withPartial` 类重建点。 | L1 AI |
| B259 | RPC `cycle_thinking_level` 对不支持 thinking 的模型仍返级别；`get_available_thinking_levels` 也未按模型能力过滤。pi 对应结果为 `null` 或可用子集。 | L5 coding-agent |
| E5-31 | 扩展 UI 仍有 working 指示器、终端输入、补全、编辑器替换等 TUI 成员未收口；与 D′ 旧行去重重测。 | L7 TUI |
| examples | 旧 16/102 不是新基线分母。必须在 L8 按 `56cae7823` 重新清点，主流示例继续全部做 Java 移植与验收测试。 | L8 examples |

B264 已于 2026-11-02 按 docs/10 实施并闭环：流式工具参数、malformed final、grammar、responseId、消息形状、`argumentsError` 传播与执行前拒绝均有 RED→GREEN 和全仓验证证据；L1 账面见 `docs/map/02-ai.md`。

B265 行为面（#31/#61/#83/#84）已于 2026-11-02 按 docs/11 实施并闭环：跨模型 thinking 丢弃与 textSignature 剥离、`normalizeContext` 无条件前置、transcript tools 三态＋`foldedSystemMessages`、`ModelsStore` operation signal。

B265b 目录数据与运行时（#16/#21/#33/#34）已于 2026-11-02 按 docs/12 分阶段闭环：阶段 A 落仓内 vendor 锚点生成器＋提交快照，重生成 18 分片（600 chat／64 image／Radius 28 基线）；阶段 B 落 OpenRouter `/models/user` 个性化、`RefreshModelsContext` 的 credential/baseUrl/signal、Radius legacy `gatewayConfig` 导入与 coding-agent 包装器。

B263（#106）已于 2026-11-03 按 docs/13 实施并闭环：`AI_TELEMETRY_SCHEMA`、`pi.ai.request`/`pi.ai.poll` 观察者、content-free block events、request/first-chunk histogram、四类 token／cost counter、`provider_error` 500 UTF-16 上限与 abort 保持 ok 均已落地；`ApiOptions` 增加 `telemetry` 组件并逐点保真 11 个重建点。

B266（#105）已于 2026-11-03 按 docs/14 实施并闭环：新增 `AI/identity/` 包（`ClientIdentity` tri-state merge、`AttributionSetting`、三种 UA、OpenRouter／NVIDIA／OpenCode 归因头、OpenCode session 头、大小写不敏感头合并＋`null` 删除）；`ApiOptions` 增加 `identity` 组件并由 `ConfigurableProvider.createApi` 预计算 `extra["identityHeaders"]`；agent-core 的 `StreamOptions`／`HarnessConfig`／`PiLoop.Config`／`ExecutionContext` 逐点携带 identity，`AgentIdentity.DEFAULT` 提供 agent 默认归因；coding-agent 新增 `ClientIdentityProvider`（settings＋`PI_TELEMETRY` 门＋版本），删除旧锚点 `ProviderAttribution`。

B267 第一批 usage 与 OpenRouter 账单（#53／#59／#107）已于 2026-11-03 按 docs/15 实施并闭环：`Usage.Cost` 增加可空 `totalFromProvider`；`CostCalculator` 显式写 `false`；Anthropic delta 覆盖 `cache_creation.ephemeral_1h_input_tokens`（含 `0` 值）；`OpenRouterBilling` 按 pi `openrouter-cost.ts` 处理非 BYOK／BYOK／上游总额缺席与显式 null；`ConfigurableProvider` 预计算 `extra["openRouterModel"]`；`UsageCombiner` 与 JSONL 解码传播三态。

B267 第二批存疑复核（15 行）已于 2026-11-03 按 docs/16 裁决并闭环：6 个 Java 特有扩展（#4/#5/#18/#21/#46/#88）转作废并退出分母，9 行（#7/#20/#24/#28/#38/#47/#63/#75/#76）转对齐并登记 D206–D212；B18 改写后销号。

B267 第三批 provider wire（#1／#2／#108／#101／#110／#111）已于 2026-11-03 按 docs/17 实施并闭环：`openai-java` 升级到 4.96.0（4.96 的 `EasyInputMessage` 默认多写 `type:"message"`，已用公开 JSON 往返还原 pi 线格）；Anthropic managed effort 顶层固定 `low`、off 可表达时发 `{type:"disabled"}` 并抑制 marker；completions 同 chunk reasoning 先于 content；Responses 发 `configuration_update`（顶层固定 `medium`，ChatGPT 登录排除）；pi-messages `contentIndex` 整数/范围/缺键校验；Gemini `const`→单值 `enum`；Anthropic strict prefer 工具 400 一次性降级（start 移至请求成功后）。

B267 第四批 retry／overflow（#70／#71）已于 2026-11-03 按 docs/18 实施并闭环：`ContextOverflow` 补 LiteLLM `context window exceeded` 预检文案与厂商无关的 HTTP 413 模式（`^(?:[\w -]+ API error \(413\):|413(?:\s|:|$))`，body 内提及 413 与 `4130` 不命中）；`RetryableError` 排除表补 ChatGPT Business 工作区限额与 z.ai `1308` 正则，白名单补 7 条网关／node-fetch／Codex 文案，状态码由裸子串收紧为 `(?<![a-z0-9])…(?![a-z0-9])` 独立 token。

B267 第五批 OAuth／device／env 与 pi-ai CLI 权限（#43／#44／#45／#50／#112）已于 2026-11-03 按 docs/19 实施并闭环：新增 `TrustedHttpUrl`（HTTP(S) 归一/白名单，xAI `HTTPS_ONLY`、Kimi complete `REQUIRE`）并接入 `DeviceCodeFlow`／`RadiusOAuthHttp`；`OAuthEvent` 结构化设备码事件（两确认开关默认 true），TUI 登录对话框一次消费；`ApiOptions` 增加请求级 `env`（第九组件）与 `ProviderEnv`，15 个重建点保真，`DefaultProviders` 投影凭证 env；两个凭证存储在建文件/覆写前收紧到 `0600`（非 POSIX no-op）。L1 账面 **231.0/232＝99.57%**（对齐 124／缺失 1，仅剩 #80）。B267 仅剩 Faux 余量待 docs/20。

B267 第六批 Faux 可编程核心与 deferred（#80）已于 2026-11-04 按 docs/20 实施并闭环：新增 `AI/provider/faux/`（`FauxProviderOptions`、模型定义、响应步/工厂、`FauxMessages`、`FauxUsageEstimator`、`FauxStreamSynthesis`、`FauxCore`、`FauxProgrammable` 与 `FauxChatApi`）；usage 估算与 prompt-cache 模拟逐字对位 `faux.ts:246-287`（`cacheWrite = promptTokens - cacheRead`、`input = 0`、工具 JSON 只投影 pi `Tool` 形状）；工具参数按 token 大小分块并经 `ToolCallArgumentsStream` 增量解析；`AssistantMessage` 追加 `deferred` 组件并逐点传播（11 个 wither、`empty`、`StreamPartialBuilder.snapshot`、`Message.fromPartial`）；`StreamApi` 新增 `fetchDeferred`/`fetchDeferredBlocking`/`cancelDeferred` 默认方法；`ApiOptions.withExtra` 成为唯一 extra 重建入口；`AiRequestTelemetry.attachPoll` 经 `AbstractChatApi.attachPollTelemetry` 成为 `pi.ai.poll` 的第一条生产入口。L1 账面 **232.0/232＝100%**（对齐 125／缺失 0）；#80 对齐。

L2 MCP 新锚点重测与对齐（W1–W12）已于 2026-11-04 按 docs/21 实施并闭环：`structuredContent` 改为任意 JSON（含数组/`null`）；`reachesTools` 工具合约与命名空间别名；连接期不再枚举资源（`hasResources` 取 capability）；OAuth `clientRegistration` 三值决策树与 `--oauth-client-registration`；`mcp login/logout -l` 与拼错建议；`mcp.json` BOM 剥离、pi 键序与 `JSON.stringify` 数字；in-memory transport 发布面；每工具 outputSchema 的 `$ref` 重定位；transport send signal；本地真授权服务器端到端夹具。MCP 账面 **126.0/131＝96.18%**（对齐 81／存疑 10／缺失 0；未加权 81/91＝89.0%）；B181/B184/B194/B196/B202/B217 销号。

L2 MCP 视口捕获收尾与跨层口径裁决已于 2026-11-04 按 docs/22 实施并闭环：`ExtensionComponent` 增加 `capturesViewportInput()`（默认 false）；`/mcp` 菜单非空时接管视口键，PageUp/PageDown 按可见页（`min(条目数, 12)`）移动选中；`tui.select.pageUp/pageDown` 只进 overlay 键位表；`ExtensionComponentOverlay` 透传属性、路由不改。用户裁决其余 9 条不属 MCP 层，从 MCP 分母转出（map/07 权重 0，行与证据保留）。MCP 账面 **122.0/122＝100%**（对齐 82／存疑 0／缺失 0；未加权 82/82；9 条转出）；D217 销号。

## 3. MCP 开放项与复核项

以下条目已按新锚点（`56cae7823`）在 L2（docs/21）重测；未关闭项保留为当前结论。

| 编号 | 当前待复核结论 |
|---|---|
| B185 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除仍需保留或有代码级替代。 |
| B205 | MCP 的 AgentTool 适配位于 coding-agent，这是模块依赖方向决定的现状，不强行搬入 mcp 模块。 |
| B208 | `ImageContent(mediaType, data)` 与 MCP 转换链的参数序不同，必须维持换序点测试。 |
| B210 | `pi mcp` 选项解析为自建实现，需核对位置参数后 flag 透传语义。 |
| B211 | `BrowserLauncher` 是 pi-java 平台替代件，headless 环境只展示 URL。 |
| B216 | 跨包公开面包括 OAuth settings、challenge 清理、状态 wire 和指定目录 settings 装载；重测后仍是最小面。 |
| B219 | `mcp_servers` 排序使用 `String.compareTo`，非 ASCII 名称可能与 `localeCompare` 不同。 |
| B220 | 尾部裁剪字符集与 pi 不同，窄角空格等边界需逐字测试。 |

**跨层转出（docs/22，2026-11-04 用户裁决）**：以下条目不属 MCP 层，保留在 `map/07` 供追溯但权重记 0、不进 MCP 分母；归属由各层重测时确认。

- `#5/B182` → L5 配置子系统（`piConfig.configDir`）
- `#6/B183` → 共享 JSON 解析方言（V8 与 Jackson 错误正文）
- `#27/B190` → 平台异常分类方言（`TypeError` 与 `IOException`）
- `#30/B191/B197` → 平台 URL 归一（WHATWG 与 `java.net.URI`）
- `#38/B193` → 共享 JSON 序列化方言（键序／显式 `null`）
- `#40/B198` → L1 ai 共享 `OAuthPage` 模板
- `#41/B212` → JVM 平台方言（`isatty` 无对位）
- `#43/B192` → OAuth 身份托管基础设施（pi.dev 域名）
- `#71/B213` → 全仓品牌改名方言（`pi-java`／`.pi-java`）

## 4. TUI 开放项

TUI 在 L7 统一重测，不沿用旧批次顺序。

| 编号 | 当前缺口 |
|---|---|
| B38 | compaction 后缺少整表重建和完整压缩摘要消息卡。 |
| B39 | 压缩窗口的 Esc 行为尚未完成换绑。 |
| B46 | Bash `onUpdate` 已有生产链和 web 消费，TUI 实时输出消费者仍缺。 |
| B47 | TUI `tool_execution_*` 计数来源仍应按真实工具执行事件修正。 |
| B78 | 模糊匹配的子序列、分词和排序语义需与新锚点重对齐。 |
| B145 | 启动、登录后等定向模型目录刷新点未全部接线。 |
| B238③ | OSC 7501 程序状态上报未实现。 |
| B238④ | APC 标记假光标未实现。 |

R29 安装变更重启警告维持不做；若新的 `selfUpdateCommand` 形成真实安装变更通道，再重新裁决。

## 5. 面外与删除面结论

### 5.1 R5：不做且不进分母

- codemode 沙箱与工具，包含 B161 和 MCP 默认 codemode 激活 B177。
- durable、chord、pico3、micro。
- 新锚点 telemetry 的 links/propagation、TelemetryStore 与 renderTrace（map/01 T18–T20）：T18 生产消费者为 Chord＋Durable，T19 为 Chord，T20 未见主流生产消费者，均属既定 R5 面；AI 所需 carrier/exemplar 不在此排除项内。主流消费者出现时原位重开。
- 分类器及统一模型设施，除非未来进入不依赖 codemode 的主流路径。
- R2 范围外的长尾 Provider 和对应鉴权。
- `streamProxy`，除非 pi-java 增加服务端托管鉴权代理车道。

共享主流行为不能随 R5 跳过。例如 tool_search、MCP 数据形状和 OAuth 仍按消费链对齐。扩展/API 可达本身也不等于 R5；AI #33/#93/#100 已在 L1 回到分母。

### 5.2 R1：不为已删除参照物补功能

- pi 已删除的 experimental harness、session-backends、旧 server/client/plugin 发布面。
- `PendingEntry`、`InboxItem` 等已删除实验类型。
- 其他删除项必须给 pi 删除提交或新锚点缺席证据后，才允许出分母。

SQLite 后端是 pi-java 独立实现，保留模块，不对齐已删除 schema。

## 6. 仍有效的实现边界

| 条目 | 最终结论 |
|---|---|
| B18（已销号） | 2026-11-03 销号：pi 固定锚点 `packages/ai/src/api/pi-messages.ts` 已存在同一 `POST /messages` SSE 协议，旧「pi-java 自有 wire」描述失效；细差归 #110。 |
| D201 | telemetry typed schema 保留运行时 identity、parent binding 与 metadata 合并；Java 无法复刻 TS 条件类型对 exact attributes、required keys、schema 名与 instrument kind 的编译期约束，不新增 pi 没有的运行时校验。 |
| D202 | malformed 工具参数沿用 Jackson syntax-error 前缀；非对象文案、`{}`＋raw suffix 与 raw 原文逐字保持，不引入 V8 文案兼容层。 |
| D203（已关闭，2026-11-04） | `pi.ai.poll` 观察者、schema 与单测此前已落地但无生产入口。docs/20 关闭：`StreamApi.fetchDeferred`/`cancelDeferred` 成为入口，`FauxChatApi.fetchDeferred` 在生产者启动前以 `poll=true` 挂观察者；Faux 是唯一实现，其它 provider 保持 pi 的「方法不存在」语义（默认抛 `UnsupportedOperationException`）。 |
| D204 | pi 的 `withClientIdentity(models, identity)` 包装器没有 Java 对应物：Java 无 `Models`／`MutableModels` 门面，identity 合并落在 `DefaultProviders`／`ConfigurableProvider.createApi` 两个请求组装点；行为面（请求头）已对齐。 |
| D205 | detailed `User-Agent` 的品牌与运行时 token 不同：pi 为 `pi/<ver> (platform; node/<ver>; arch)`，pi-java 为 `pi-java/<ver> (os.name; java/<java.version>; os.arch)`；归因头仍用 pi 品牌值。属 D77 族工具自述差异。 |
| D206 | `StreamError` 的 `Throwable` 是内部扩展，不得进入任何序列化投影；所有线投影走 `StreamError.textOf`（`JsonEventMapper`／`PrintMode`／web translator／payload recorder）。模型可见错误体与 pi 的 `AssistantMessage` 同形；新增投影若直接序列化 `StreamError` 即回归。 |
| D207 | 目录 `Model.input`／`reasoning` 投影为 `ModelCapability` 能力位（`BuiltinModelCatalogs`）；`ModelInfo.minimal` 能力集为空，`supportsImageInput()` 对未知按支持（`ModelInfo.java:240`）。pi 无「未知模型」状态，此方向性选择仅作用于 Java 独有状态。 |
| D208 | `ToolResultMessage` 的 `details`／`usage` 取 `Object`，因为 pi 的 `Usage` 类型在 Java 侧属于 agent 模块，ai 不能反向依赖 agent；字段值须保持 JSON 兼容，provider 投影不读取这三项（与 pi 同）。 |
| D209 | Java 以静态协议注册表从 models.json 建 provider，按模型 `api` 派发；不能加载任意 JS api 模块。新增 wire 协议须先在 Java 实现适配器并注册，未跟进时按缺失行登记，不藏在本行。 |
| D210 | Java 无动态模块加载；setup 失败在 `createApi` 同步抛出，错误文本／时序与 pi 的 `error` 事件不完全同形；`fetchDeferred`／`cancelDeferred` 懒包装无对应物（与 D203 同族）。宿主异常处理变更时须复核。 |
| D211 | `PiMessagesEvent` 是 Java record 方言，字段与 pi TS 联合逐项对应但不共享代码；#110 若扩面到请求／响应外壳，须回头复核 #7／#63。 |
| D212 | `DeferredHandle.data` 用 `Map<String,Object>` 承载 pi 的 `JsonValue`；若需顶层数组或标量须复核。docs/20 已补生产（faux 提交）→ 取回 → 持久化 `Message.AssistantMessage` 重建的纵贯测试（`FauxDeferredTest#d212ProductionToFetchToRebuildRoundTrips`）。 |
| D213 | pi-messages `contentIndex` 校验比 pi 更严：pi 的 `"contentIndex" in event` 对缺键直接跳过，Java 对九个带下标事件统一要求键在场（缺键 ⇒ `Invalid contentIndex null …`）。这是 docs/17 §6 的刻意裁决：Jackson 缺键填 `null`，不显式拒绝会把「缺键」误当合法下标。 |
| D214 | Faux 响应工厂只支持同步函数：pi 的 `FauxResponseFactory` 可返回 `Promise<AssistantMessage>`，`resolveResponse` 会 `await`；Java 的工厂返回 `AssistantMessage`，等价性依赖测试在虚拟线程上自行阻塞。出现必须真异步的夹具时复核。 |
| D215 | Faux usage 估算的文本投影与 pi 的 `Tool` 形状对齐：工具 JSON 只含 `name／description／parameters／constrainedSampling`，Java `ToolDefinition` 的 `label／promptSnippet／promptGuidelines／renderShell` 不进估算文本；`UrlImageContent` 用 `[image-url:<url 长度>]` 稳定占位。差异只影响测试断言的 token 数字，不影响任何请求体。 |
| D216 | Java 的 `StreamApi` 没有 pi 的 `AbortSignal` 通道：faux 的 deferred 提交/取回不移植「起始或块间中止 ⇒ aborted」分支；aborted 终局仍可由预置消息显式声明（`stopReason = ABORTED`）。与全仓既有取消模型一致。 |
| B83 | pi 自身三处 sanitize 缺口保持同样行为；pi 修补时同步。 |
| B100 | compat 探测的 baseUrl 在内置模型上行为等价；出现可观察差异再重裁。 |
| B126 | Codex 车道及其 transport 等随 R2/R5 出分母。 |
| B147 | Responses 回放保留分组顺序；发现 provider 配对校验差异再重裁。 |
| B130 | 当前携带的 zai 模型有效 reasoning effort 与 pi 相同；引入新模型时由数据重新判定。 |
| B132 | together/baseten/fireworks 不内置；自定义 models.json 的显式配置能力保留。 |
| map/03 #54/#58/#78/#100 | Entry 表示、投影合并、会话查找/排序和系统提示载体按已登记边界视为行为等价或刻意差异。 |
| map/03 #14/#80/#84/#82/#90/#91 | 队列、prompt 门、重试映射和转录可见性维持已验证结论；新锚点重测时复查。 |
| map/03 #57/#60 | bashExecution 窄差按消费面保留；已删除 PendingEntry/InboxItem 永久出分母。 |

这些结论不是新锚点免检证明。若对应源码已变化，必须在所属层重新判定。

## 7. 维护规则

1. 本文只留当前结论，不追加批次实施过程。
2. 新缺口先给新锚点源码证据，再登记 B；新差异无法消除时登记 D。
3. 条目关闭后从开放表移除，历史由 git 保存，编号不复用。
4. 每层重测后同步更新对应地图；全部层完成前不发布新全仓百分比。
5. 每个实施工作包仍需独立设计稿和用户审核。
6. 下一新文档号为 **docs/23**（L3 agent-core 首个设计）；`docs/22` 已闭环（2026-11-04，L2 mcp 视口捕获与跨层口径）；`docs/21` 已闭环（2026-11-04，L2 mcp 主体）；`docs/20` 已闭环（2026-11-04，L1 完成）；`docs/19` 已闭环（2026-11-03）；`docs/18`、`docs/17`、`docs/16` 已闭环（2026-11-03）。
