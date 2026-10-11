# 41 pi-ai 层未对齐清单

> 这份文件只回答一个问题：**pi-java-ai 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：
> `git log -- docs/41-ai-alignment-gaps.md`）。
>
> - **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)（B 编号与裁决以此为准）；
>   **全量加权盘点**是 [`map/02-ai.md`](map/02-ai.md)（逐单元判定、权重、完成度）。
> - 本稿 = 两者的 ai 层索引：§1 台账 B 项，§2 地图判「缺失」但没有 B 号的能力单元，
>   §3 形状差（存疑、待裁决），§4 已裁决不做。
> - pi 锚点 `ea448f454`（2026-10-11）。地图基准为 2026-10-04（pi `200387122`），
>   六图逐条重测是台账 **A16**；A16 闭环前，地图行号按旧锚点读。
> - 整理：2026-10-11（旧版盘点过程稿已随此重写删除）。

---

## 1. 台账在册功能缺口

`docs/04` §3.2 全部 30 条 ＋ §3.3 中的 B222（同属 ai 协议面）：

| # | 差什么（一句话） |
|---|---|
| B80 | Mistral 数组形态 `delta.content` 无解析路径（pi 有 string/thinking/text 三支） |
| B81 | OAuth 分支两面未移植：系统提示 `"You are Claude Code…"` 前缀 ＋ 工具名 Claude Code 映射 |
| B85 | read.ts：图片自动缩放 ＋ 非视觉模型注记（全仓无 `javax.imageio`） |
| B94 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明缺失（补片段时须同时把 ContextAssembler 回落描述改回 pi 的稀疏规则） |
| B95 | `docs` 段无生产者（ContextAssembler 不传 DocumentationPaths） |
| B97 | 3 个内置 `(provider,modelId)` 与 pi 目录不匹配；另有 Java 有 pi 无的 `text-embedding-3-*` 一类 |
| B99 | `supportsMidConvoEffort` 半可移植：字段 ＋ 两个 beta ＋ `insertThinkingLevelMessages`（SDK 无 BlockBinding） |
| B100 | compat 探测的 baseUrl 来源不同（pi 只读模型对象；差异不可观察，建议随 A16 降为架构备注） |
| B110 | `providerThinkingLevel` pi 有 Java 无（`proxy.ts` 线字段 ＋ `anthropic-messages.ts` 消息字段） |
| B115 | 宿主线终局帧形状：pi 的 message_update 只发非终局增量（跨宿主，与 map/03 #52 同族） |
| B116 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言（pi usage 只在消息上，proxy 线另发扁平 usage） |
| B117 | 「谁定终局 reason」两个权威并存互相遮蔽（SessionRunner 逐事件写 ＋ 终局收口） |
| B118 | 「报 toolUse 却无工具块」stopReason 守卫缺失（pi 判 provider error） |
| B119 | 停因闭集无校验点，生产者各写自由字符串 |
| B121 | `AssistantMessage.stopReason()` 是 String 非闭集（pi 是 `StopReason` 联合类型） |
| B124 | `maxTokens: 0` 与缺席之别被 Java `int` ＋ −1 哨兵吞掉（pi 用 `??`/`!== undefined` 区分） |
| B125 | samplingParams 的 **per-request 半**无生产者（通道在 `ApiOptions.extra`；`ModelInfo.headers` 半已有消费者） |
| B126 | codex-responses 车道忽略 maxTokens |
| B127 | Responses reasoning effort 读 extra 平行通道，与主路 reasoning 并存 |
| B128 | `thinkingBudgets` 无选项通道（pi `SimpleStreamOptions.thinkingBudgets`，`adjustMaxTokensForThinking` 消费） |
| B137 | resolver 无 minimatch 通配与 scope 诊断；实际序＝精确→切 `:level`→provider 首条→modelName→任意 id 兜底；`--models` 生产零消费者 |
| B147 | Responses 回放 item 按类型分组，pi 同循环按块序交错（已注释为刻意偏差，保留在册） |
| B148 | responseModel/responseId/diagnostics 无真消费者（usage 分组、cache-warmer、fallback 成本归属、`pi_messages_response_failure` 信封、Anthropic 流内 fallback 块） |
| B156 | ModelCompat 逐组件规范构造 ＋ 12 个便捷构造各自补 null：加组件必须全改，否则静默缺省 |
| B158 | 输出截断字节预算 Java 100KB vs pi 主流 50KB（模型可见文案不同；对应 map/03 #23） |
| B159 | pi 已改 `finishTurn()` 返回 `{action:"continue"|"end"}`，Java 仍是 Boolean `shouldStopAfterTurn`，缺「强制再跑一轮」臂（对应 map/03 #6/#41） |
| B222 | responses 车道前导消息恒发 `system`，pi 对推理模型（`reasoning && supportsDeveloperRole !== false`）发 `developer`；字段有生产者但 responses 读点不存在 |
| B226 | Radius 内建 provider 缺失（只有 OAuth 设备码工厂）；注意 pi 新语义＝gateway catalog **整体替换** baseline |
| B230 | azure provider 改名 `azure-openai-responses`→`azure`（env 键随之）＋ 新增 Foundry **Chat Completions** 车道；Java 仍注册旧 id 且只有 responses |
| B231 | OAuth 刷新被调用方取消时，轮转后的 refresh token 仍须落盘（`refreshStoredOAuthCredential`）；Java 侧设计时取证确认 |
| B232 | 内置目录数据漂移（v1.1.0）：Haiku 5.5、Claude 5.5 系列改吃 models.dev、Kimi K3 定价/级别表等；与 B97 同族，属持续性数据漂移 |

---

## 2. 地图判「缺失」、但没有 B 号的能力单元（21 条，计分母）

编号是 `map/02-ai.md` 的单元号。除 #34 权重 2 外均权重 1。

**wire 适配器（4）**：#9 `bedrock-converse-stream` · #10 `google-vertex` · #11 `openai-codex-responses`
· #12 `cloudflare-ai-binding`。
> 口径待裁决：这四个端点本身是权重 0（R2 长尾、Java 无端点），地图把 **wire 协议能力**单独计了分母。
> #11 与 B126/B127/#74 同族（codex 车道整体缺失）。

**ContentBlock（1）**：#8 `ToolUseContent.namespace`。

**模型元数据（8）**：

| 单元# | 缺口 | 备注 |
|---|---|---|
| #34 | 内置目录规模：Java 手写约 45 条 vs pi 42 provider 生成目录 | 权重 **2**，边际最大单条；B232/B97 同族 |
| #33 | per-provider 图像模型目录/注册表（仅 openrouter 单家 8 条） | 与 #92/#94 同属统一模型设施 |
| #37 | `responseModel`/`responseId`/`thinkingLevel`/`diagnostics`/`endTurn` 字段全无 | 即 B148 |
| #86 | `Model.promptCache`（cache 寿命元数据，解析但忽略；CacheWarmer 未移植） | |
| #87 | `MistralConversationsCompat`（compat 第 5 个 per-api 接口） | |
| #90 | `Model.inputLimits`（图像输入上限，消费于缩放/注记） | 与 B85 同族 |
| #91 | `Model.samplingParamsByThinkingLevel`（per-thinking-level 采样参数） | 与 B128 相邻但独立 |
| #93 | `ToolResultMessage.nestedCalls` | codemode 族（B162） |

**类型基础设施 / 分类器（2）**：#92 统一模型类型系统（`BaseModel`/`Model`/`ImageModel`/`ClassifierModel` 联集）
· #94 结构化分类 API（3 个分类器 wire 适配器 ＋ `Models.classify()`；即 B164）。

**鉴权（3）**：#95 OAuth `openai-chatgpt`（OpenAI 备选登录）· #96 Anthropic OAuth copy-code 登录法
· #98 Anthropic workload identity federation（5 个 `ANTHROPIC_*` env）。

**横切（3）**：#74 `transport: "sse"|"websocket"|…`（依附 codex 车道，车道补时才可达）
· #78 `session-resources` 清理注册表（生命周期在 agent-core）
· #100 `onProviderStreamEvent` provider 流事件钩子（十车道调用点）。

---

## 3. 形状差（地图判「存疑」，计半分；19 条，未认账为缺口）

这些是「Java 有、pi 无」或「两侧形状不同、行为近似」。**不直接当缺口清**；收口任一条前先定
「形状差算不算偏差」，认账后转 §1 立 B 号，或正式记入 `docs/04` §4 裁决不做。

| 组 | 条目（map/02 单元号） |
|---|---|
| Provider / wire（2） | `ollama` provider 是 Java 独有扩展 · pi-messages wire 线格自造（**B18 已裁决不做**，04 §4，此条留作地图索引） |
| ContentBlock（2） | #4 `UrlImageContent`、#5 `DiffContent` 均为 Java 独有 |
| StreamEvent（3） | #20 `error` 载 `Throwable` 而非 `AssistantMessage` · #21 `UsageInfo` 事件 Java 独有 · #23 signature 多发一条 `ThinkingDelta`（台账 C8） |
| 元数据（4） | #24 `Model.input` 数组 vs `ModelCapability`＋`supportsImageInput` · #28 `Model.reasoning` vs `THINKING` 能力位 · #38 `ToolResultMessage.usage` 留形状无生产者（`details` 已接） · #88 `JsonObject` 以 `Map`＋Jackson 表达（裁决零代码） |
| 鉴权（3） | #46 多 profile 凭证（Java 扩展） · #47 models.json 自定义 provider 形状不同 · #97 共享 OAuth 回调服务器/登录页形状不同、行为近似 |
| 横切（5） | #68 `assistant-message-frame` 对应物在 web/protocol（跨模块） · #75 `DeferredHandle` 两侧同状无生产者 · #76 lazy `lazyApi`/`lazyStream` vs `ProviderFactory`/`Protocol` · #101 strict 工具默认翻转 ＋ Anthropic 不支持关键字拒绝（未核） |

---

## 4. 已裁决不做（不展开）

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
