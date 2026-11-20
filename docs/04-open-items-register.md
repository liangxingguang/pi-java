# 04 - 未结项与有效裁决

> 【背景摘要】本文档只保留当前未结项、待用户决定事项和仍有效的范围裁决。已闭环批次、实施过程与旧账面沿革不在这里保存，历史在 git。
>
> 当前锚点为 pi `56cae7823`（2026-11-20 七层 L0–L8 重测闭环后切换，见 `docs/map/ANCHOR.md`）。下方条目均已随所属层重测；个别行仍引 `ea448f454` 作对照证据，冲突时以新锚点为准。

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
| B268 | 模板命令引号空参数＋JS 空白集合已于 2026-11-05 按 docs/23（W1）实施并闭环：`parseCommandArgs` 加 `hasToken`，`""`／`''` 保留为空参数；空白改用 JS `/\s/` 精确集合（含 U+FEFF，不含 U+0085），命令正则共用。 | L3 agent-core |
| B269 | 技能路径 Windows 大小写比较键于 2026-11-05 按 docs/23（W2）复核闭环：Java `WindowsPath` 的 `equals`／`startsWith` 本就大小写不敏感且按段比较，`toRealPath` 返回真实大小写，与 pi `getPathComparisonKey`／`isPathWithin` 等价；**无代码改动**，Windows 回归测试锁定。 | L3 agent-core |
| B270 | grep 孤立 CR 行切分已于 2026-11-05 按 docs/23（W3）实施并闭环：`fileLines` 只做 CRLF→LF，孤立 CR 留在行内；`--no-config` 契约由纯 Java 引擎天然成立。 | L3 agent-core |
| B271 | 恢复会话 active tools：`usesDefaultTools` 门、分支全部 system 消息并集、`toolsAdded`／`toolsRemoved`、晚注册扩展工具、`+name/-name`。已于 2026-11-05 按 docs/24（W1）实施并闭环。 | L4 session |
| B272 | compaction 摘要语义：去掉固定输出上限、thinking 不进 `serializeConversation`、split-turn 应用 `/compact` 指令、branch summary `thinkingLevel`；summary auth 复核为 Java streamFn 已自解析。已于 2026-11-05 按 docs/24（W2）实施并闭环。 | L4 session |
| B273 | 恢复省略成对：`_prepareRetry` 按 toolCallId 在投影中找工具结果并一起省略。已于 2026-11-05 按 docs/24（W3）实施并闭环。 | L4 session |
| B274 | 会话持久化细节：append 失败回滚（Java 先写文件后提交内存，复核等价＋回归锁）、fork 单 fd 写。已于 2026-11-05 按 docs/24（W4a/W4b）实施并闭环。 | L4 session |
| B278 | 显式 `--session-dir`／`settings.sessionDir` 按 pi 语义：文件平铺、cwd 过滤、`~` 展开；默认根布局不变。已于 2026-11-05 按 docs/24（W4c）实施并闭环。 | L4 session |
| B275 | run 启动重构归 L5：`_startRun`／`_prepareRun`、启动即 busy、`before_agent_start` 期间 abort 在 `agent_start` 生效、flush 次序、settled 延迟动作；`before_agent_start` 的 user／custom 联合。已于 2026-11-05 按 docs/25（W1）实施并闭环（`RunStartWindowTest`；W1 引入的树导航回归随 W4 修复）。 | L5 coding-agent |
| B276 | 扩展工具投影归 L5：`getAllTools()` 透传 `reachesTools`；active tools 重设同名集合时保持原顺序。已于 2026-11-05 按 docs/25（W2）实施并闭环；激活序来源差异登 **D219**。 | L5 coding-agent |
| B277 | 其余 L5 新锚面：`sessionShareDestination`／`extensionChangelogVersions`、classifier compat、exec、package-manager、model-resolver、export-html、KeybindingHints／`blocked`。**全量闭环（2026-11-05）**：B277a（`pi.exec` maxBuffer／truncated）、B277b（三个新 settings 键／`/share` destination／`update self` 配置命令／扩展 changelog 启动展示／manifest `changelogPath`）、B277c（package-manager：安装失败不阻塞启动、temporary 源刷新、`~` 模式展开、路径比较键）、B277d（model-resolver：`ModelReferenceResolver`／`CliModelResolution`／`CliModelWiring`，`ai` 与 `coding-agent` 两侧；Java 独有臂登 **D227**）、B277e（tool_search Unicode 分词＋分字段 BM25）、B277f（export-html 系统消息与 CJK 强调）全部实施；classifier compat 按 R5 出分母；`blocked`／KeybindingHints 按 D220 随 E5-31 归 L7。 | L5 coding-agent |
| B116 | 对外 JSON 的 usage 已改为 pi 扁平形状；Java 内部 `StreamDone` 仍保留嵌套方言，AI 重测时决定是否继续保留。 | L1 AI |
| B156 | `ModelCompat` 逐组件重建存在漏字段风险；新增组件时必须同时检查构造点和 `withPartial` 类重建点。 | L1 AI |
| B259 | RPC `cycle_thinking_level` 对不支持 thinking 的模型仍返级别；`get_available_thinking_levels` 也未按模型能力过滤。pi 对应结果为 `null` 或可用子集。已于 2026-11-05 按 docs/25（W9）实施并闭环（非 reasoning ⇒ `["off"]`／`null`；`thinkingLevelMap` 缺档过滤且轮转跳过；无模型 ⇒ 七值全集）。 | L5 coding-agent |
| E5-31 | 扩展 UI 仍有 working 指示器、终端输入、补全、编辑器替换等 TUI 成员未收口；与 D′ 旧行去重重测。 | L7 TUI |
| examples | L8 已按 `56cae7823` 重清点，并经用户两次裁决缩分母——先 8 件外部运行时（D232）、再 5 件范围面主动（D237）：新分母 **88**（102 − 8 − 1 R5 − 5）；W0–W8 全部实施，账面 **88/88＝100%**，设计见 docs/27（W2 docs/51，W3 docs/52，W4 docs/54，W7 docs/55，W8 docs/56，W5 docs/57，W6 docs/58）。W3/W4 各 10 件、W7 5 件、W8 17 件、W5 13 件、W6 4 件均于 2026-11-19 由用户裁决直接实施，模块 **401 测 0 失败**、注册表 70 项（W8 仅 rpc-demo/dynamic-resources 是扩展，余 15 件为独立脚本；W5/W6 共 17 件全部是扩展），SpotBugs 全模块 0 告警；窄差 D238/D239/D241–D254（W5 沿用 §3.4/D92/D100/D246，W6 新开 D253/D254）。剩余闭环动作：`docs/05` 总表在全仓 `mvn clean verify` 通过后替换（docs/27 §7 第 5 条）。旧 16/102 只作历史。 | L8 examples |
| B284 | `ExtensionContext` 余成员：**已实施并闭环（2026-11-09，docs/50 B284）**——新增 10 项（`getModel`/`modelRegistry`/`getContextUsage`/`compact`/`reload`/`shutdown`/`isIdle`/`getSystemPrompt`/`isProjectTrusted`/`newSession`；`thinkingLevel` 原已存在）。`getContextUsage` 按 `agent-session.ts:4318-4361` 逐字（无模型/窗口≤0 ⇒ empty；压缩后无可信用量 ⇒ 字段 null）；`modelRegistry.complete` 走 `ModelCompletion` 缝＋`AssistantResponseCollector`（`LlmSummaryGenerator` 同步改委托）；`newSession` 走 `session_before_switch("new")`→`createReplacing`→`closeForReplacement`→`SlashContext.onSwitchSession`。窄差 D233/D234。测试 `ExtensionContextGapTest` 4 项。 | L8 examples |
| B285 | `ExtensionSessionView` 余成员：**已实施并闭环（2026-11-09，docs/50 B285）**——新增 9 项（`getBranch`/`getEntry`/`getLabel`/`getLeafEntry`/`getSessionFile`/`getCwd`/`getSessionId`/`getLeafId`/`getTree`）；`getSessionName` 归根上下文，`getSessionDir`/`getHeader`/`buildContextEntries`/`buildSessionProjection` 按 docs/50 §3.1 裁决 A 不做（零消费/内部 API）。无持久面沿转录兜底。测试 `ExtensionSessionViewGapTest` 2 项。 | L8 examples |
| B286 | 沙箱执行后端接缝：**已实施并闭环（2026-11-19，docs/53）**——`BashTool.create(..., ShellExecutor)`／`PowerShellTool.create(..., ShellExecutor)` 暴露可空执行后端（pi `BashToolOptions.operations`）；`UserBashEventResult` 改 sealed 联合（`Operations(ShellExecutor)`／`Result(ShellResult)`），`SessionEventFirer.userBash` 返回联合、`SessionMessagingLifecycle.executeBash` 按臂执行并照常落会话。扩展注册同名 `bash` 即可接入 Docker/SSH 后端。测试 `BashToolExecutorOverrideTest`／`PowerShellToolExecutorOverrideTest`／`ExtensionBashOverrideTest`＋既有 `SessionEventFirerTest`／`SessionLifecycleEventsTest` 扩展；agent-core 与 coding-agent 全量测试通过。窄差 D240；文件工具 operations 另立 B287（仍开放）。 | L3 agent-core ＋ L5 coding-agent |
| B287 | 文件工具 operations 接缝未对位（B286 的姊妹缺口）：`read`/`write` 走 `ToolContext.fs()` 但工厂不可注入，`edit` 混用 NIO 与 `fs`，`grep`/`find`/`ls` 直接 NIO ⇒ Docker/SSH 类文件后端无法只替换文件工具。拟在 B286 闭环后单独立包，范围与 pi `ReadOperations`/`WriteOperations`/`EditOperations` 对位。 | L3 agent-core |

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

L4 session/SQLite 新锚点重测与对齐已于 2026-11-05 按 docs/24 实施并闭环：map/06 存储后端 20 行全部对齐（44.0/44＝100%）；W1（B271）恢复 active tools 改为按分支全部 system 消息重放＋修饰符＋晚注册 pending；W2（B272）摘要去掉固定输出上限、序列化不含 thinking、turn-prefix 接指令、branch summary 透传 thinkingLevel（摘要鉴权复核为 Java streamFn 已自解析）；W3（B273）auto-retry 省略按 toolCallId 与工具结果成对；W4a 复核 append 回滚为等价并补故障注入回归，W4b fork 改为单次写；W4c（B278）显式会话目录改为 pi 平铺布局＋cwd 过滤＋`~` 展开。map/06 #6 改判「对齐（形状不同）」并登记 D218（lane 为 pi 忽略的内部扩展键）。

L3 agent-core 新锚点重测与对齐已于 2026-11-05 按 docs/23 实施并闭环：90 个计分单元逐行重测后仅 3 行改判——W1 模板命令引号空参数＋JS `/\s/`、W3 grep 孤立 CR 行切分（实施），W2 技能路径比较键（复核后无改动，Java `WindowsPath` 语义已等价）；identity／`reachesTools`／AI 新类型适配经 docs/14／docs/21／B264 复核为对齐；#77（会话恢复）与 #24（branch summary）按层归属转出 L4（权重 0，行与证据保留）；#68 `streamProxy` 维持 R5。agent-core 账面 **177/177＝100%**（88 计分＋2 转出；对齐 88／存疑 0／缺失 0；未加权 88/88）。B268／B269／B270 随本包闭环；B271–B277 挂所属层。

L5 coding-agent 新锚点重测与对齐已于 2026-11-05 按 docs/25（W1–W10）全量实施并闭环：W1 run 启动窗＋`before_agent_start` user／custom 联合（B275）、W2 `reachesTools` 投影（B276）、W3 `pi.exec` maxBuffer／truncated／UTF-8 边界、W4 三个新 settings 键＋`/share` destination＋`update self` 配置命令＋扩展 changelog 启动展示＋manifest `changelogPath`、W5 包管理器容错与 temporary 刷新（B279／B280）、W6 模型引用解析统一策略（B277d：`ModelReferenceResolver`＋`CliModelResolution`＋`CliModelWiring`，含两处行为变化）、W7 tool_search Unicode 分词＋分字段 BM25、W8 HTML 导出系统消息＋迭代回溯＋CJK 强调＋markdown 上下文 `messageId`／`timestamp`、W9 思考档 RPC 过滤（B259）、W10 print 截断（B281）／trust 优先级／删除 cwd／帮助与 slash 文案／图片编码（B282）／shell 平台（B283）。**转出**：E3-3 会话选择器与 E5-31 扩展 UI → L7，E5-33 examples → L8（权重 0，行与证据保留）。B275／B276／B277（含 B277a–f）／B259 全销号；新登记 D219–D227（其中 D223 auth EPERM、D226 macOS 进程组 STOP 为平台面不适用）。map/04 计分口径见该图各域汇总；全仓 verify **6,397 测零失败**，Checkstyle 零错误、SpotBugs 零告警。

L6 evals 新锚点重测与对齐已于 2026-11-05 按 docs/26（W1–W7）全量实施并闭环。本层特点：`packages/evals/src` 两锚点间**净变 +1 行**（只有 `report.ts` 改 9 行），`harness.ts`／`cli.ts`／`docker.ts`／`plan.ts`／`entrypoint.ts`／`Dockerfile` 全部逐字节相同，故工作量在**重判**而非追新。逐行重测查出旧账面把 **#5 对照报告汇总记成「对齐」过宽**：pi 的四组配对标量（token／工具调用／延迟／成本）、按变体运行总量与 **pass-rate 定义的 lift** 在 Java 侧当时均不存在。W1 补齐报告（`PairedMetricSummary`／`EvalSetComparison`／`VariantTotals`／`OperationalMetricTotal`，`schemaVersion=3`）；W2 补按变体运行总量与覆盖度（不折零）；W3 给审计加取证断言（必须读过被审页面＋必须读过实现源，`AuditSourceIndex` 按页选集，D230）；W4 新增 prompt template 文档 eval（project／user 双 scope，`HarnessOptions.output` 观测面），并修好 agent 目录隔离缝（`pi.java.agentDir` 属性，D231）；W5 拆出 `--audit` 独立执行档（`EvalSuite.expensive()`）。评测域 **24 行 / Σ49 / 48.0＝97.96%**（对齐 23／存疑 1／缺失 0）；唯一存疑仍是 #20（TUI 审计 oracle 形态差，复核后维持）。补回文档重编时丢失的 **D182–D189**（八条复核后全部成立），新登记 **D228–D231**。全仓 verify **6,424 测 0 失败 0 错误 / 33 跳过**，Checkstyle 零错误、SpotBugs 零告警。

L7 TUI/web 新锚点重测与对齐已于 2026-11-19/20 按 docs/59（W1–W6）全部实施并闭环：W1b 内置工具渲染器 8 件＋工具行计时（docs/60，R11 缺失→**对齐**）；W2 选择器整页翻页＋模型别名 `(name)`＋补全弹层抢 PageUp/PageDown（docs/62）；W3 会话搜索分级＋`FuzzyMatch`/`FuzzyMatcher` 双引擎合一＋同会话比较键（docs/63）；W4 overlay 单槽改栈＋扩展编辑器 Ctrl+G 框内 warning＋外部编辑器逐字文案（docs/64）；W5 `[Skill warnings]`＋`Model scope:` 入 header＋终端标题控制字符清洗（docs/65，**D258 销号**）；W6 web 面四类事件转发＋消息 `timestamp` 上 wire＋`agent_end` 恒写 `messages`＋宿主并发契约＋`find()` 项目内断言（docs/66，R11 之外的 W10 存疑→**对齐**）。map/05 账面 **179.0/244＝73.4%**（对齐 88／存疑 21／缺失 38；权重 3 子集 64.5/78＝82.7%）；其中 14 个权重单位仍标「（旧基线）」待逐条复核（保守区间 70.5%）。新登记窄差 **D260–D273**；**D258 销号**（终端标题控制字符清洗）。全仓 `mvn clean verify -Dfrontend.skip=true` **BUILD SUCCESS**（16 模块），`docs/05` §1 总表按七层地图重算 **1,291.0/1,356＝95.2%**，`docs/map/ANCHOR.md` 当前锚点切至 `56cae7823`。

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
| D218 | 会话分支用 Java 的 lane（`createLane`／`moveLane`）表达 pi 的单 leaf 树（`branch`／`resetLeaf`／`branchWithSummary`）；`lane` 是 Java 内部扩展键，pi 解析条目时忽略（D190），用户可见的树导航/分支行为由 `TreeCompaction` 全链对齐。 |
| D219 | 扩展工具激活序的来源不同：pi 用 runner 侧的 `activationOrder` 列表，Java 按工具注册表的插入序（同名集合重设时保持原序）；用户可见的 loadout 顺序由注册序＋`reachesTools` 投影共同决定，未在本包改注册表序源。 |
| D220 | `KeybindingHints` 不进扩展渲染 API：Java 的 `ToolCallRenderer`／`ExtensionMessageRenderer`／`ExtensionEntryRenderer` 返回纯文本（D92 文本级方言），键位提示由宿主 TUI 自行展示；`blocked` 选项随 E5-31 转 L7。 |
| D221 | `selfUpdateCommand` 的 `{version}` 在 pi 是**目标版本**，在 pi-java 是**当前版本**（无发布通道取目标版本）；使用 `{version}` 的配置命令须自行处理「已是最新」场景。 |
| D222 | npm 特有的 CLI 行为不适用：`--no-audit`／`--no-fund`／`realpath` 前缀修正、`npm view` 的 `--json` 数组臂都由 HTTP registry 臂（批 18 裁决③）等价实现；`pi update` 的「进度批装」在 Java 逐包顺装（既有 D113），「版本没变」的 progress 消息已逐字对齐。 |
| D223 | `auth.json` 的 lockfile 目录 EPERM 重试面不适用：pi `auth-storage.ts:19-31` 为 `FS 锁文件`（目录不可写时按 EPEM 重试），Java 用 `FileChannel.lock()` 直接对目标文件加锁，无 lockfile 目录层，故无该重试分支。 |
| D224 | tool_search 分词用 JDK {@code BreakIterator}（UAX#29）代替 {@code Intl.Segmenter}：两者只在「无空格连写」的脚本上不同（浏览器词典切「搜索仓库问题」/泰文连写为多词，JDK 给整段）；空格/标点分隔的日文、韩文、西里尔、拉丁逐词一致。不引入 ICU4J。 |
| D225 | HTML 导出不引入 JS `marked-cjk-friendly`：Java 的 `MarkdownToHtml` 自带 CJK 强调放宽（收尾定界符内侧为 CJK 字符/全角标点或外侧非字母数字即闭合，纯拉丁文本仍守 CommonMark），导出 HTML 的字节形态与 pi 不同源，判定以渲染结果为准。 |
| D226 | macOS 进程组的 `SIGSTOP` 先手不移植：pi `utils/shell.ts` 先对进程组发 `SIGSTOP` 再杀（防子进程在被杀前 fork 新孙进程）；Java 的 `ShellProcessEngine` 走 `Process.destroyForcibly()`＋`descendants()` 后序遍历，无进程组 STOP 面。Windows/Linux 的可观察行为一致，macOS 极端竞态下可能残留孙进程。 |
| D227 | 模型引用解析的 Java 独有两条臂（pi 的 `models` 列表天然含运行时注册 provider 的模型，Java 的静态目录不含）：①`--provider` 指向**运行时已注册**的 provider（Faux／扩展 provider）而模型未编目 ⇒ 按自定义 id 接受；②模型串是 `provider/id` 形状、精确匹配不中且前缀 provider 既不在目录也未注册 ⇒ 落到该前缀 provider（虚拟模型 `jev/auto` 与用户自建 provider 靠这条走）。裸未知 `--model`（无 `/`）仍按 pi 报错；fork 携带的父会话模型直接采用不再解析。 |
| D182 | Docker 镜像的真实构建／执行不在 CI 验收链：`docker/{DockerContext,DockerImageSpec,DockerCommandBuilder,DockerProcess,DockerEvalRunner,JUnitXmlReader}.java` 以假进程钉住命令契约、target 名、cohort 校验与 JUnit XML 读取；真实构建留给有 Docker 与网络的环境。L6 复核：`src/docker.ts`、`docker/Dockerfile`、`docker/entrypoint.ts` 两锚点**逐字节相同**，边界未扩大。 |
| D183 | TUI 文档审计以 Java 文档问答替代 pi 的 context-footer oracle（`42.2%/272k` 状态行）：Java TUI 无该行，改用文档问答＋Levenshtein 0.75 判分，评测 #20 记存疑。L6 复核：新锚点只把 `autoevals` 的 `Levenshtein` 换成内联 `levenshteinSimilarity`（`tui.docs.eval.ts:23-37`），且其返回值仍只进 `retainedPercent` 元数据，判分核心 `valid ? 1 : 0` 未变 ⇒ 存疑判定维持。 |
| D184 | 扩展作者 eval 额外允许 `bash`：Java 扩展是编译产物（JAR），pi 的 docs eval 只给 `read/write/edit/grep/find/ls`，无法编译。其余 docs eval 仍只用六件。 |
| D185 | eval 发现用静态注册表（`cli/EvalCatalog`＋`contents/ContentsCatalog`，Java 无 `*.eval.ts` 文件发现机制），Docker 报告读 JUnit XML（非 Vitest JSON）。报告字段与语义对齐 pi，文本不追求字节相同。 |
| D186 | provider／model eval 用内联 `inspect_provider`／`inspect_added_model` 代写 `models.json` 并探针（pi 由模型直接写配置）：Java 的 read/write 只允许写 workspace，无法让模型编辑隔离 home 的 `models.json`。 |
| D187 | 扩展作者 eval 经 `sessionCustomizer`＋内联 `install_extension` 装载 JAR（pi 由 `/reload` 扫描扩展目录，D90 裁决③）。 |
| D188 | `SmokeEval` 在 CLI 全量 docs-lift 下也会被配对（CLI 无 per-suite 标记，lift 预期为零）。L6 W5 收窄：`EvalSuite.expensive()`＋`--audit` 使 `--host` 默认只跑非审计套件，与 pi 的 `eval:host` 语义一致。 |
| D189 | `RecordingFooterOracle` 复刻 footer 行为文本以避开对 `pi-java-tui` 的依赖，显示宽度按每 codepoint 一列近似（未实现宽字符占两列）。只用于期望问答文本。 |
| D228 | `report.json` 升 `schemaVersion=3`（L6 W1）：结构从「单份全局汇总」改为 per-eval-set `comparisons` 列表＋按变体 `operationalTotals`，并采用 pi 的 **pass-rate 定义 lift**（`treatmentPassRate - controlPassRate`，非逐对分数差均值）。历史 `.eval/` 产物不与新结构互读；审计产物不跨运行消费，故不做兼容层。 |
| D229 | `readTaskObservation` 的「先按 `fullName` 过滤再计数」（pi `report.ts:147-157`）在 Java 无对应场景：`cli/EvalArmMain` 每个臂只跑一个 case 并写只含一个 `<testcase>` 的 `junit.xml`，`docker/DockerEvalRunner` 直接按计数分类，不存在同报告含兄弟用例的情况。落产物行为等价。 |
| D230 | 审计取证范围是**种入 workspace 的实现源子集**，不是 pi 的全仓只读挂载：Java 的 read/grep/find/ls 受 `PathUtils` 约束只能在 workspace 内读，全仓约 900 文件／11 万行不适合逐用例种入。`contents/AuditSourceIndex` 按页面内容挑选相关源（类名词命中＋包路径命中＋锚点兜底）。子集只影响可读范围，不改变「必须读过页面＋必须读过实现源」的判分口径。 |
| D231 | agent 目录多一条 Java 独有的来源 `pi.java.agentDir` 系统属性（`FileSettingsStorage.AGENT_DIR_PROPERTY`），优先级：属性 > `PI_JAVA_CODING_AGENT_DIR` 环境变量 > `~/.pi-java/agent`。生产不设属性，行为不变；评测 harness 用它把 agent 目录指到隔离根（`System.getenv` 不可写，pi 侧只需设 `PI_CODING_AGENT_DIR`）。同族：评测 workspace 的项目配置目录是 `.pi-java`（pi 为 `.pi`）。 |
| D232 | examples 的 8 个外部运行时文件出分母（sandbox 1、gondolin 1、doom-overlay 5、with-deps 1）：纯 Java 无对位原生沙箱／micro-VM／WASM／JS 模块解析，按 docs/27 §2.4 不写降级假移植；R5 的 `sdk/14-codemode-mcp.ts` 维持既有口径。 |
| D233 | pi `ModelRegistry` 的 `findOfType`/`classify`（classifier 模型类型）不提供：Java AI 层无 classifier 面，仅 jev-router 消费且该扩展已在 W1 按显式模型 id 落地（docs/50 §3.2）。 |
| D234 | pi `NewSessionOptions.setup` 不提供：本锚点 examples 零消费，且其可写 SessionManager 语义与只读门面纪律冲突；`newSession` 只落 `parentSession`/`withSession`（docs/50 §3.2）。 |
| D237 | examples 的 5 件**范围面主动出分母**（用户裁决 2026-11-09）：`overlay-test.ts`／`overlay-qa-tests.ts`（D99 已裁决 overlay 定位/尺寸/堆叠不移植，验收只剩文本行，无法证明对齐；L7 未闭环时写下的断言必然返工）、`snake.ts`／`space-invaders.ts`（只消费 `appendEntry`＋`registerCommand`＋`ui.custom`，除游戏帧外不验证任何宿主语义）、`mac-system-theme.ts`（macOS `osascript` 应用外观专属，Windows/Linux 无对位产品行为）。不写降级假移植；分母 93→**88**（102 − 8 外部运行时 D232 − 1 R5 − 5 本项）。W5 余 13 件、W6 余 4 件、W7 余 5 件仍按文本级/序列级断言实施，`ExtensionUI` 槽位接通仍由这些件覆盖。 |
| D240 | `ShellExecutor` ≙ pi `BashOperations`/`PowerShellOperations`，但 Java 返回完整 `ShellResult`（output/exitCode/timedOut/truncated/outputLines/outputBytes），pi 只要 `{exitCode}`＋`onData`：外部后端须自行填 `timedOut`/`truncated` 与行列计数（可置 0/false），工具层截断/notice 仍由 `ShellToolSupport.formatOutput` 统一产出；`spawnHook`（command/cwd/env 改写）无对位，扩展改用 `tool_call` 的 `input` 原位改写。 |
| D235 | plan-mode 示例的 TUI 呈现降级为纯文本：pi 的 `theme.fg("accent"|"warning"|"success"|"muted")` 与 `strikethrough` 在 Java 只有主题名窄投影（无结构化 Theme），状态段写 `⏸ plan`／`📋 n/m`、widget 写 `☑`／`☐` 行；另 {@code context} 事件载荷是 LLM 投影（无 `customType`），pi 的 `customType === "plan-mode-context"` 剔除分支不实现，只保留「含 `[PLAN MODE ACTIVE]` 的用户消息剔除」文本等价判据（docs/51 §2.2.1）。 |
| D236 | handoff 示例的加载器以同步方言实现：pi 的 `BorderedLoader` 是异步组件（转圈动画＋Esc 取消＋`loader.signal`），Java 的 `ui.custom` 为阻塞方言、`modelRegistry.complete` 同步 ⇒ 工厂内生成完立即 `done(text)`，不再驱动动画；生成文本／取消／`null` 三结局与中止语义一致（docs/51 §2.8.1）。 |
| D238 | tic-tac-toe 的 TUI 呈现降级为纯文本：pi 的 `theme.fg` 颜色、Unicode 边框/字形、SGR 转义、居中留白、闪烁光标与 300ms `setTimeout` 延迟均不移植（`ExecutionMode.Sequential` 已保证工具串行，延迟仅为 pi 侧可观测性手段）；`messageRenderer` 返回纯文本行（docs/52 §2.10/§5）。 |
| D239 | qna/summarize 的 `BorderedLoader`（转圈＋Esc 取消＋`loader.signal`）降级为同步 `ui.custom` 方言（D236 同族扩展到这两件）：工厂内同步 `complete` 后立即 `done(text)`，无转圈/无 `loader.signal`；生成文本/取消/`null` 三结局与中止语义一致（docs/52 §2.6/§2.7/§5）。 |
| D241 | 工具渲染器收敛为 `ToolCallRenderer`（renderCall/renderResult 纯文本行，无 details/isPartial、有 expanded/isError），minimal-mode/built-in-tool-renderer/truncated-tool 的富 Component 渲染、`isPartial` 状态文本（Reading/Running/...）、颜色全部降级为纯文本行；沿用 D92 文本级方言（docs/54 §0/§5）。 |
| D242 | `ExtensionAutocompleteProvider` 是空接口、无 `AutocompleteItem`/`AutocompleteSuggestions`/`fuzzyFilter` 对位：github-issue-autocomplete 只验注册形状＋纯逻辑（token/repo 解析、issue 过滤用简单子序列匹配近似 fuzzyFilter），补全建议内容与触发行为不可观测（docs/54 §2.10/§5）。 |
| D243 | 文件工具 Operations 注入未闭环（B287，另立工单）：ssh 的 read/write/edit 远程后端无法注入（Java 无 ReadOperations/WriteOperations/EditOperations），降级为同名注册＋本地执行＋远程命令纯函数演示；bash 经 B286 executorOverride 完整对齐（docs/54 §2.8/§5）。 |
| D244 | subagent 真子进程（spawn pi --mode json、SIGTERM/SIGKILL、5s 定时器）不移植：用注入式 `SubagentRunner` 返回假结果验 single/parallel/chain 三模式编排；默认 runner 抛 UOE。真子进程方案另立包（docs/54 §2.2/§5）。 |
| D245 | TUI 侧无 OSC 777/OSC 99 终端通知写出、无窗口标题终端能力（`setTitle` 在 TUI 为空操作，RPC 臂已实现）：notify 三臂保留为纯函数产转义字符串逐字断言，实际通知降级为 `ui.notify` 聊天屏文本；spinner 标题经 `ui.setTitle` 由夹具录表断言。沿用 D92 文本级方言 + B238③（OSC 7501 未实现，docs/55 §2.1/§2.2）。 |
| D246 | 扩展无注入式定时器/fs.watch：spinner 用注入式 `TickDriver` 手动驱动（记录 80ms 间隔常量，不建真定时器、不验帧率）；file-trigger 用注入式 `FileSource` 手动触发回调（不建真实 watch）。沿用 D238 时序降级（docs/55 §2.2/§2.3）。 |
| D247 | SDK 入口名差：pi `createAgentSession`/builder/`onEvent`/`dispose`/`state.messages` 在 Java 为 `AgentSession.create`/`subscribe`/`close`/`accumulatedMessages`；`DefaultResourceLoader` 的各类 override 收敛为 Args/ResourcePaths/Skill 等既有入口，示例以等价投影纯函数＋真实 `AgentSession` faux 集成断言（docs/27:284/:324 裁决，docs/56 §0/§2）。 |
| D248 | RPC 无同名 `RpcClient`/`RpcServer` 封装类：Java 用 `RpcMode.run(in,out,args,providers,toolContext)` 测试缝与手写 JSONL/`RpcDispatcher` 对位；rpc-client/rpc-extension-ui 示例以请求形状/事件过滤/九方法表的纯函数断言，不真起子进程（docs/56 §2.3）。 |
| D249 | `dynamic.json` theme 结构化类型无对位：Java 侧仅 `ResourcePaths` 路径贡献，theme JSON 作为测试资源原文落地，断言 `$schema`/`name`/vars 15 键/colors 53 键/export 3 键（docs/56 §2.4/§5）。 |
| D250 | rpc-extension-ui 的 TUI ANSI 绘制（`\x1b` 颜色/BOLD/DIM、全屏 dialog、loading dots 动画、`OutputLog` 滚屏）不移植：事件顺序/九方法/三类回包/四个纯函数（dots/prefill/truncate/tail）断言，终端绘制降级为纯文本标记（docs/56 §2.3）。 |
| D251 | `AgentSessionRuntime`/runtime 工厂名差：Java 用 `AgentSession.reload`/`switchTo`/会话文件标识对位，13-session-runtime 以 bindSession 重绑步骤与 newSession/switchSession 文件标识投影断言（docs/56 §2.2）。 |
| D252 | Java `RpcExtensionUI` 处理 `setTitle`（`RpcExtensionUI.java:142`），而 pi 锚点的 rpc-extension-ui.ts TUI 未处理 `setTitle`（rpc-demo 发但静默忽略）——Java 侧为行为增强差，登记不改（docs/56 §2.3）。 |
| D253 | `SlashCommand` 无 pi 的 `getArgumentCompletions` 对位（Java 只有扩展级 autocomplete provider 空接口，见 D242）：commands 示例的来源参数补全降级为纯函数 `sourceCompletions(prefix)`，只做断言不入键盘面（docs/58 §1.2）。 |
| D254 | 工具渲染器无 theme／`result.details`（D241 同族）：question/questionnaire 的 `renderResult` 按 `textOutput` 前缀复原 `✓ …`／`Cancelled`，questionnaire 的逐题答案只能从内容行前缀推出（docs/58 §1.3/§1.4）。 |
| D260 | read 的紧凑分类行（`read docs <label>`／`[skill] <label>`，pi `renderers/read.ts:46-107`）不移植：判定依赖 pi 包根（`getReadmePath()`）与 `SKILL.md` 探测，属 pi 产品布局；内置渲染器的 call 行恒为 `read <path><:range>`（docs/60 §3.1）。 |
| D261 | 工具结果折叠按**逻辑行**计数：pi bash 用 `VisualLinePreview{maxVisualLines:5, keep:"end"}`（宽度感知视觉行，`renderers/bash.ts:58-68`），Java 的逻辑行在渲染期才折行，故"尾部 5 行"取逻辑行（docs/60 §3.2，D92 同族）。 |
| D262 | 渲染器侧的 `[Truncated: …]`／`[Full output: …]` 警告行（pi 由 `details.truncation`/`fullOutputPath` 生成）不移植，也不给 `ToolCallRenderer` 加 details 通道（D241 扩展）：同款文案已由工具层写进正文（`ReadTool:123-146`、`ShellToolSupport:236-248`、`GrepTool:194-207`、`FindTool:195-206`、`LsTool:141-149`），双重产出只会重复（docs/60 §3）。 |
| D263 | edit 的 call 行读盘 preview（`computeEditsDiff`＋`argsComplete` 异步 `invalidate`，pi `renderers/edit.ts:177-195`）不移植：Java 渲染器是纯函数、无 fs 访问；diff 仍由结果 `ContentBlock.DiffContent` 经 `DiffView` 上色（docs/60 §3.7）。 |
| D264 | 列表行无分段配色：pi 用 `theme.fg("muted", …)` 单独给模型别名 `(name)`／`[provider]` 上色，Java `SelectList` 标签是单个 `String`，整行同色；文案逐字，颜色不移植（docs/62 §3.3，D92 同族）。 |
| D265 | `capturesViewportInput` 没有通用组件级契约：Java 只在 `GlobalKeyRouter.completerTakesPageKeys` 为补全弹层开了一个门；扩展 overlay 的视口键语义沿用 D99/D256，不新增框架层拦截抽象（docs/62 §3.4）。 |
| D266 | 分页单位是「项」不是「可视行／终端高度」：pi 自身如此（`user-message-selector` 每项 3 行但 `maxVisible=10`），Java 同形不修；树/配置两处按高度算页大小的实现维持现状（docs/62 §3.5）。 |
| D267 | 排序文本比较：pi 在 `model-selector`/`theme`/`config-selector`/`footer`/`settings-selector` 用 `String.localeCompare`（ICU，随运行环境 locale 变，pi 自身输出不确定）；Java 用 `String.compareTo`（UTF-16 码元序），大小写/重音的相对顺序可能不同。不引入 `Collator`，以确定性优先（docs/63 §4）。 |
| D268 | 消息文本缓存：pi 用 `WeakMap` 按字符串身份缓存小写/折叠结果（`session-selector-search.ts:45-60`），Java 每次按键重算；会话数×消息体量的最坏情形有性能差，功能面无差异（docs/63 §3.3）。 |
| D269 | 路径键的平台边角：pi 走 Node `path.win32.resolve`＋`realpathSync`；Java 走 `Path.toRealPath`＋`PathComparisonKeys`。UNC 前缀、8.3 短名、`\\?\` 长路径前缀的规范化结果不保证逐字一致；符号链接语义相同（docs/63 §3.4）。 |
| D270 | `FuzzyMatch` 只返回分数、无命中下标：pi 同样不返回（`fuzzy.ts:7-10`），本包不额外造 indices（当前无高亮消费点）。 |
| D271 | `--session <id>` 保留跨项目阶梯：pi 只做项目内精确查找（未命中即报错/新建，`main.ts:243-249`），Java 的 `find()` 阶梯 ③ 会扫全库并在命中时打印提示＋确认 fork。项目内命中路径行为一致，差异只在跨项目时多给一次机会（docs/66 §3）。 |
| D272 | 扩展对话框 `blocked` 无对位：pi 有 program-status 区（`interactive-mode.ts:2812` `setBlocked("extension-dialog", top?.blocked)`），Java 无状态行、也无按 blocked 拦截输入的消费面；栈化已对齐"只挂栈顶/回退上一层"（docs/64 §3）。 |
| D273 | 技能 collision 的 winner/loser 行尾 ` (source)` 不移植：Java `ResourceDiagnostic.Collision` 只有路径，没有来源包字段；其余文案逐字（docs/65 §1）。另：`--skills` 的 `SessionSetup.discoverSkills` 为纯静态无会话句柄，其诊断仍未上抛，只有扩展贡献技能那条路进 `[Skill warnings]`。 |
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
6. 新文档号取「历史已用最大号 +1」，不得复用已闭环批次编号（历史已用到 `docs/66`）；下一新文档号为 **docs/67**。`docs/59` 为 L7 TUI/web 重测与对齐设计，`docs/60`–`docs/66` 为其六个实施包（W1b 内置渲染器、W2 选择器翻页、W3 搜索排名、W4 对话框栈、W5 启动头/标题、W6 web 协议），均于 2026-11-19/20 实施并闭环；`docs/61` 为 B161 codemode 机制设计。`docs/58` 为 L8 W6 交互/命令移植设计（4 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 **70** 项、模块 401 测 0 失败；零新增宿主成员，窄差 D253/D254＋沿用 D241/D246）；`docs/57` 为 L8 W5 UI 槽位/编辑器移植设计（13 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 66 项；零新增宿主成员，窄差沿用 docs/27 §3.4／D92／D100／D246）；`docs/56` 为 L8 W8 SDK/RPC/资源移植设计（17 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 53 项；窄差 D247–D252）；`docs/55` 为 L8 W7 平台/文件移植设计（5 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 51 项；窄差 D245/D246）；`docs/54` 为 L8 W4 工具语义/执行移植设计（10 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 46 项；窄差 D241–D244）；`docs/53` 为 B286 沙箱执行后端接缝设计（2026-11-19 审核通过并实施闭环）；`docs/52` 为 L8 W3 会话树/标签/持久化移植设计（10 件，2026-11-19 用户裁决直接实施，逐批测试全绿、注册表 37 项；窄差 D238/D239）；`docs/50` 为 L8 W2/W3 前置包（B284/B285，2026-11-09 已闭环）；`docs/51` 为 L8 W2 生命周期/压缩移植设计（2026-11-09 审核通过并实施闭环）；`docs/27` 为 L8 examples 重测与移植设计（2026-11-08 审核通过、2026-11-09 按 D237 把分母修正为 88，实施中）；`docs/21`–`docs/26` 已闭环（L2 mcp 2026-11-04；L3 agent-core、L4 session、L5 coding-agent、L6 evals 2026-11-05）；`docs/20` 及更早已闭环。
