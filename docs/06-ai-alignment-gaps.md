# 06 pi-ai 层未对齐清单

> 【背景摘要】本文档只回答一个问题：**pi-java-ai 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：`git log --follow -- docs/06-ai-alignment-gaps.md`）。

## 术语与定位

- 通用术语（pi / 锚点 / 对齐 / 闭环 / 工单编号 Bxxx / 裁决 / 模型可见面 / R5 / 分母 / 原 docs/NN）：见 [`04-open-items-register.md`](04-open-items-register.md) §0 与 [`05-module-alignment-map.md`](05-module-alignment-map.md)。
- **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)：B 编号与裁决以此为准。
- **全量加权盘点**是 [`map/02-ai.md`](map/02-ai.md)：逐单元判定、权重、完成度。
- 本稿＝两者的 ai 层索引：§1 台账 B 项，§2 地图判「缺失」但没有 B 号的能力单元，§3 形状差（存疑、待裁决），§4 已裁决不做。
- **形状差**：Java 有、pi 无，或两侧形状不同、行为近似的条目。不直接当缺口清。
- **map 单元号（#N）**：`map/02-ai.md` 里的能力单元编号。

## 基准与进度

- pi 锚点 `ea448f454`（2026-10-11）。七份地图（含新建 07-mcp）已于 2026-10-12 对该锚点逐条重测完毕（原 A16 销号）。
- **2026-10-12 ai 批 0 裁决**（原 docs/08）：B115/B117/B124 取证关单；B100/B126/B147/B226/B230 转 04 §4；map 单元 #87 零代码对齐；#9/#10/#11/#12/#33/#74/#78/#92/#93/#94/#100 移出分母（R5/权重 0/作废）。
- **ai 批 1–10 全部闭环**（2026-10-13 → 10-16，实施记录在原 docs/09–19，历史在 git）：加权 85.0% → **94.5%**（207.0/219）。计分母缺失仅剩 §2 的 #8。逐批账面见 map/02 头部。
- 整理：2026-10-17（旧版盘点过程稿已删；原 docs/41 重编号为 06）。

---

## 1. 台账在册功能缺口

`docs/04` §3.2 存活 ai 协议面 **4 条**（批 1–10 的销号记录在原 docs/09–19 与 git 历史）：

| # | 面 | 差什么（一句话） |
|---|---|---|
| B94 | 模型可见 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明缺失；补片段时须同时把 ContextAssembler 回落描述改回 pi 的稀疏规则 |
| B95 | 模型可见 | `docs` 段无生产者（ContextAssembler 不传 DocumentationPaths） |
| B116 | 协议面 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言（pi usage 只在消息上，proxy 线另发扁平 usage）；2026-10-12 取证线格式已扁化，内部组件保留 |
| B156 | 内部 | ModelCompat 逐组件规范构造 ＋ 12 个便捷构造各自补 null：加组件必须全改，否则静默缺省 |

**跨模块另案**（ai 侧的字段/面已落，消费者在别的模块；原 docs/08 §2.3 折入）：

- **B94/B95 落点**：agent-core 的 `ContextAssembler`/`SystemPrompts`＋coding-agent 工具声明（pi `system-prompt.ts:128-166`，消费者 `agent-session.ts:3533-3541`）。
- **CacheWarmer**（453 行，coding-agent 另案）：`cacheWarming:"streaming"` 默认开、只对直连 Anthropic。ai 侧 `promptCache` 字段批 3a 已补（字段单做无消费者）。
- **B148 消费侧**：usage-totals `/session` 费用分组、bug-report diagnostics.json（B79）、cache-warmer 归属。ai 侧字段＋流内触点批 5 已落。
- **B234 的 TUI「Took …」卡**：Java TUI 不订阅 ToolExecution 事件，随 tui bash 渲染器。
- **批 9 图片真空臂**（面已落、无生产者的路径）：@file/`PromptImage` 附件零生产者、剪贴板、TUI Kitty 转码器/设置 UI、`maxPer*`（pi 也零消费）、codemode structuredContent。

---

## 2. 地图判「缺失」、但没有 B 号的能力单元（1 条，计分母）

编号是 `map/02-ai.md` 的单元号。批 0 后原 21 条中 11 条移出分母（见 §4）、#87 转对齐；批 1–10 又有 #34/#86/#91/#37/#95/#96/#98/#90 相继转对齐出表（逐批账面见 map/02 头部）。**仅剩 1 条**：

- **ContentBlock（1 条）**：#8 `ToolUseContent.namespace`（随 MCP 包 B203；仅 Responses 车道 round-trip）。

---

## 3. 形状差（地图判「存疑」，计半分；15 条，未认账为缺口）

这些是「Java 有、pi 无」或「两侧形状不同、行为近似」。**不直接当缺口清**。收口任一条前先定「形状差算不算偏差」：认账后转 §1 立 B 号，或正式记入 `docs/04` §4 裁决不做。

残余加权 12.0 的构成（原 docs/08 §2.4）：

- 计分母缺失仅 §2 的 #8（权重 1）。
- 其余全是本表存疑半分（权重 1×10 ＋ 权重 2×6 ＝ 11.0）。
- 其中 Java **独有扩展**（#4/#5/#18/#46）与**行为等价形状**（#24/#28/#88/#75/#76）可逐条裁决翻对齐——它们不是「pi 有 java 无」的缺口。

**2026-10-12 批 0 注记**：#20（StreamError 载 Throwable）、#21（UsageInfo 独立事件）、#38（ToolResult.usage 无生产者）取证确认**出线形状与 pi 相同**（内部方言保留，不翻判定，仍计半分）；B115/B117 取证关单（终局帧与 reason 收口两侧同构）。详见原 docs/08 §2.2。

| 组 | 条目（map/02 单元号） |
|---|---|
| Provider / wire（2） | `ollama` provider 是 Java 独有扩展 · pi-messages wire 线格自造（**B18 已裁决不做**，04 §4，此条留作地图索引） |
| ContentBlock（2） | #4 `UrlImageContent`、#5 `DiffContent` 均为 Java 独有 |
| StreamEvent（2） | #20 `error` 载 `Throwable` 而非 `AssistantMessage` · #21 `UsageInfo` 事件 Java 独有 |
| 元数据（4） | #24 `Model.input` 数组 vs `ModelCapability`＋`supportsImageInput` · #28 `Model.reasoning` vs `THINKING` 能力位 · #38 `ToolResultMessage.usage` 留形状无生产者（`details` 已接） · #88 `JsonObject` 以 `Map`＋Jackson 表达（裁决零代码） |
| 鉴权（2） | #46 多 profile 凭证（Java 扩展） · #47 models.json 自定义 provider 形状不同 |
| 横切（3） | #68 `assistant-message-frame` 对应物在 web/protocol（跨模块） · #75 `DeferredHandle` 两侧同状无生产者 · #76 lazy `lazyApi`/`lazyStream` vs `ProviderFactory`/`Protocol` |

---

## 4. 已裁决不做 / 出分母（不展开）

**2026-10-12 批 0 新裁决**（论证见原 docs/08 §2）：

- **R5（codex/扩展面）**：#94 结构化分类 API＋4 分类器适配器；#92 统一模型类型（随 #33/#94，独立行为差≈0）；#33 per-provider 图像目录（无 CLI/TUI 入口，openrouter 单家已对齐）；#74 transport；#78 session-resources（唯一注册者是 codex WS）；#93 nestedCalls（仅扩展工具产生，B162 族）；#100 onProviderStreamEvent（唯一订阅者是示例扩展）。B164 转 04 §4。
- **权重 0（主流 7 家口径外，用户裁决维持）**：#9 bedrock、#10 vertex、#11 openai-codex（含 WebSocket；B126/responseId/endTurn 随它一起出分母）、B226 Radius、B230 azure。五家 /login 技术可达，口径松动时 azure/radius 边际成本最低。
- **作废（死代码）**：#12 cloudflare-ai-binding（pi 侧零生产引用、不导出、无 lazy）。
- **零代码对齐**：#87 MistralConversationsCompat（唯一字段五车道共享、mistral 模型不挂 compat，java 行为已在；同 #88 类型层先例）。
- **取证关单**：B115/B117（终局帧/reason 收口同构）、B124（pi 无 `maxTokens:0` 生产者、java 已 OptionalInt 门控）、B100（降架构备注）。B100/B126/B147/B164/B226/B230 在 `docs/04` §4 带触发条件。

**其余**：

- **25 个长尾 provider 端点 ＋ 4 条对应鉴权**：权重 0、R2/R5 排除出分母，完整清单见 `map/02-ai.md` 加权汇总「被排除的单位」。
- **B18**（pi-messages 不按 pi 改造）、**B130/B132**（zai sre、together/baseten/fireworks 携带集零差，带触发条件）：见 `docs/04` §4。
- **`ToolResultMessage.addedToolNames`**：pi 已删（R1 作废）。
- **B241**（pi ai `telemetryContext` 选项透传）：pi 全库零设置点的休眠公共 API 选项，R5 行为不可达 ⇒ 不做（telemetry 批 1 取证，原 docs/23 §7.5）；pi 长出设置点时重裁。
- 分类器三件套、`noRetryStatuses`、codex 调用方头优先、Bedrock haiku-5/reasoning-effort、compat schema 发布等：新锚点屏过不登记（理由见 04 历史批次记录）。

---

## 5. 取证口径

1. pi 侧一律 `git -C /d/workplaceForai/pi show ea448f454:<path>`，不读 pi 工作树。
2. pi-java 侧读当前 HEAD 工作树。
3. 行号会漂：引用优先给**文件 ＋ 符号/关键行**。
