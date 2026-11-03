# 16 - L1 AI 存疑复核与裁决设计（B267 第二批：15 个存疑行）

> 【背景摘要】docs/15 已把 usage 与 OpenRouter 账单面闭环；本包处理 `docs/map/02-ai.md` 剩余的 **15 个「存疑」计分单元**，逐行给出「作废／对齐／延后」裁决。本包只改判定与台账，不改 Java 代码。
>
> 文档状态：**已实施并闭环（2026-11-03）**。用户已批准本稿；地图判定与台账已按 §7 回填。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。

## 0. 范围、基线与口径

### 0.1 锚点与基线

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | docs/15 实施并闭环（HEAD `18cef3da`；usage 与 OpenRouter 账单） |
| L1 当前账面 | **206.0/240＝85.83%**；对齐／存疑／缺失＝102／15／14；计分单位 131 |
| 本包目标 | 15 个存疑逐行清零；账面到 **208.0/232＝89.66%**（分母因 6 个 Java 扩展转作废而缩小） |

### 0.2 为什么单独立「存疑复核包」

【场景】地图的 15 个存疑里，只有 6 行是「两侧形状都在、只是判定没拍板」；其余 6 行是 **Java 自有扩展**（pi 根本没有对应功能），3 行卡在别的开放项（pi-messages 细线、deferred 生产入口）。

【决定】本包一次过 15 行，按三种动作处置：

1. **作废**：Java 扩展不属于 pi 对齐分母 ⇒ 权重转 0，退出分母。6 行。
2. **对齐**：两侧形状与行为已可判等，残余差异登记 D 编号。9 行。
3. **延后**：不存在——本包把 15 行全部清零；卡在别包的行改为「对齐＋D 编号＋旁挂开放项」，不重复扣分。

【风险/代价】[内部，模型不可见] 转作废会改变分母（240→232），历史百分比不可直接跨包比较。所有新数字必须与「计分单位 125、缺失 14、存疑 0」一起读。

### 0.3 15 行处置总表

| 地图行 | 能力 | 权重 | 现判定 | 本包动作 | 依据 |
|---|---|---:|---|---|---|
| #4 | `UrlImageContent` | 1 | 存疑 | **作废** | pi 只有 base64 `ImageContent`（`types.ts:438-442`）；Java 为 Responses/Gemini 加的 URL 变体 |
| #5 | `DiffContent` | 1 | 存疑 | **作废** | pi 无 diff 内容块；Java 纯 UI 产物，provider 投影丢弃 |
| #7 | `pi-messages` wire | 1 | 存疑 | **对齐＋D211** | 两侧同一 `POST /messages` SSE 协议；细差由 #103/#110 承接；B18 在本锚点失效 |
| #18 | `ollama` provider | 2 | 存疑 | **作废** | pi `models.generated.ts` 无 ollama 分片；Java 自建本地 provider |
| #20 | `error` 载荷 | 2 | 存疑 | **对齐＋D206** | 两侧错误消息体同为 `AssistantMessage`；Java `Throwable` 仅内部 |
| #21 | `UsageInfo` 事件 | 2 | 存疑 | **作废** | pi 事件表无独立 usage 事件（`types.ts:815-837`）；Java 自有进度事件 |
| #24 | `Model.input` | 1 | 存疑 | **对齐＋D207** | pi 模态 `["text","image"]`；Java 目录同映射 |
| #28 | `Model.reasoning` | 2 | 存疑 | **对齐＋D207** | pi `reasoning: boolean`；Java 映射 `ModelCapability.THINKING` |
| #38 | ToolResult usage／details／duration | 1 | 存疑 | **对齐＋D208** | 三字段均在；`usage` 为防模块反向依赖取宽类型 |
| #46 | 多 profile 凭证 | 1 | 存疑 | **作废** | pi 无多 profile 凭证管理；Java 扩展 |
| #47 | 自定义 provider | 2 | 存疑 | **对齐＋D209** | 两侧都从 models.json 建 provider；Java 用静态协议注册表 |
| #63 | PiMessages thinking 线格 | 1 | 存疑 | **对齐＋D211** | 两侧 `thinking_end` 同带 signature/redacted；细差归 #110 |
| #75 | `DeferredHandle` | 1 | 存疑 | **对齐＋D212** | 两侧类型同形，真实生产者两侧都休眠；Java poll 入口挂 D203 |
| #76 | `lazyApi`／`lazyStream` | 1 | 存疑 | **对齐＋D210** | Java 无动态模块加载；同步构造的失败由宿主转 error |
| #88 | `JsonObject` 静态约束 | 1 | 存疑 | **作废** | 刻意不移植 TS 类型系统；Java 用 `Map`＋Jackson |

合计：作废 6 行（权重 8），对齐 9 行（权重 12）。

### 0.4 账面影响

| 口径 | docs/15 后 | 本包后（预计） |
|---|---:|---:|
| 加权 | 206.0/240＝85.83% | **208.0/232＝89.66%** |
| 未加权 | 109.5/131＝83.6% | **111/125＝88.8%** |
| 对齐／存疑／缺失 | 102／15／14 | **111／0／14** |
| 计分单位 | 131 | **125** |

算式：

- 作废 6 行权重 8：分母 240−8＝232；这些行原贡献 8×0.5＝4.0，分子 206.0−4.0＝202.0。
- 对齐 9 行权重 12：每行从 0.5 升 1.0，分子 +12×0.5＝+6.0 ⇒ **208.0**。
- 对齐 111 行、缺失 14 行（权重 24）、存疑 0：208.0 ＝ 111×1.0 ＋ 24×0（按权重口径复算为 208/232）。

### 0.5 不在本包

- 不改任何 Java 代码；不改 `docs/15` 已闭环的 usage 实现。
- 不处理 #103（流式工具参数）与 #110（provider wire 语义）本身；它们仍是 B267 后续面。
- 不实现 Faux 的 cache/deferred 余量（#80）；#75 的对齐以 D203 旁挂。
- 不动 R2/R5 的既有作废行；不动已闭环的 102 行。

### 0.6 术语

- **存疑**：两侧都有形状或行为，但判定尚未拍板的计分单元；地图按 0.5 计。
- **作废**：pi 无对应功能，或该行为不参与对齐分母；权重 0，退出分母。
- **对齐**：行为与形状可判等；不可消除的材质差异登记为 **D 窄差**，仍按 1.0 计。
- **旁挂开放项**：本行判定不为该开放项让路；开放项在自己的包里闭环。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本。本包全部为【内部，模型不可见】。

## 1. 口径与判据

【场景】地图同时存在「对齐 1.0／存疑 0.5／缺失 0／作废 0」和「粗 wire 行不重复承接细行缺口」两条规则。

【决定】本包按以下判据处置：

1. **pi 无对应功能且 Java 有** ⇒ 作废，不按 0.5 长期挂账。
2. **两侧功能都在、只是材质不同** ⇒ 对齐＋D；材质差异不得隐藏模型可见行为差。
3. **缺口已在另一计分行登记** ⇒ 本行可按对齐判，不重复扣分。
4. **两侧都休眠的生产入口** ⇒ 类型/观察者对齐，生产入口按既有开放项跟踪。

【风险/代价】[内部，模型不可见] 第 1 条是**计分口径变更**，不是行为变更；批准后历史百分比段落必须加注分母变化。第 3 条依赖地图的去重纪律；若 #103/#110 未来扩面，必须回头复核 #7/#63。

## 2. 作废组：6 个 Java 自有扩展

### 2.1 #4 `UrlImageContent`（权重 1）

**pi 证据**（`packages/ai/src/types.ts:438-442`，逐字）：

```ts
export interface ImageContent {
	type: "image";
	data: string; // base64 encoded image data
	mimeType: string; // e.g., "image/jpeg", "image/png"
}
```

pi 的内容块只有 `text`／`thinking`／`image`（base64）／`toolCall`；**没有 URL 图片块**。

**Java 证据**：`AI/message/ContentBlock.java:98` 的 `UrlImageContent(String url)`，javadoc 明说「P6-19，非 base64 内联」，由 Responses/Gemini 适配器下发。

【决定】作废。Java 的 URL 变体是对 OpenAI Responses／Gemini 线格能力的补充，不是 pi 的对齐缺口；provider 投影不会把它当 pi 的内容块消费。

【风险/代价】[内部，模型不可见] 若未来 pi 增加 URL 内容块，必须重新开行，不得直接沿用本裁决。

### 2.2 #5 `DiffContent`（权重 1）

**pi 证据**：`types.ts` 的 `ContentBlock` 联合（`TextContent`／`ThinkingContent`／`ImageContent`／`ToolCall`）无 diff 块；工具结果内容也只有 `TextContent | ImageContent`（`:645`）。

**Java 证据**：`ContentBlock.java:169` 的 `DiffContent(String diffText)`，javadoc 逐字「unified diff fragment for UI rendering；Providers drop this block」。

【决定】作废。纯 UI 展现产物，不入模型上下文，也不参与 provider 请求构造。

【风险/代价】[内部，模型不可见] TUI/web 仍可继续使用该块；作废只影响地图计分。

### 2.3 #18 `ollama` provider（权重 2）

**pi 证据**：`packages/ai/src/models.generated.ts` 的 provider 联合（`:47-91` 与其后的 `MODELS` 表）**没有** `ollama`；`git grep ollama` 在锚点 `packages/ai/src` 零命中。

**Java 证据**：`AI/provider/builtin/OllamaProvider.java:10`，`OpenAiCompatibleProvider` 的本地无鉴权分支。

【决定】作废。Java 为本地推理补的 provider，不属于 pi 对齐面；其协议行为仍由 OpenAI-completions 车道行计分。

【风险/代价】[内部，模型不可见] Ollama 自身行为若有缺陷，按普通 provider bug 处理，不占对齐账面。

### 2.4 #21 `UsageInfo` 事件（权重 2）

**pi 证据**：`packages/ai/src/types.ts:815-837` 的 `AssistantMessageEvent` 联合有 `start`／`text_*`／`thinking_*`／`toolcall_*`／`done`／`error`，**没有独立 usage 事件**。usage 只作为 `done`／`error` 载荷的字段出现。

**Java 证据**：`AI/stream/StreamEvent.java:253` 的 `UsageInfo(long inputTokens, long outputTokens, AssistantMessage partial, Usage usage)`，是逐帧进度用的扩展事件。

【决定】作废。pi 的消费面按终局 `done` 的 `usage` 计；Java 的逐帧事件是内部/UI 扩展，不改变终局计量。

【风险/代价】[内部，模型不可见] 现有消费方（TUI 进度、telemetry 观察者）继续可用；不影响 `StreamDone` 的终局 usage。

### 2.5 #46 多 profile 凭证（权重 1）

**pi 证据**：`packages/ai/src/auth/` 无 profile 管理器；`auth/types.ts:172` 只说明 ambient 凭证来源。`git grep "AuthProfile"` 在锚点零命中。

**Java 证据**：`AI/auth/AuthProfileManager.java:24`。

【决定】作废。Java 的 profile 存储/切换是宿主扩展，不是 pi 的凭证链对齐项；凭证优先级链仍由 #42 计分。

【风险/代价】[内部，模型不可见] profile 行为缺陷按普通 auth bug 记账。

### 2.6 #88 `JsonObject` 静态约束（权重 1）

**pi 证据**（`types.ts:491-537`）：`JsonValue`／`JsonObject` 及 `IsJsonCompatible` 是 TypeScript 编译期约束，运行时无行为。

**Java 证据**：Java 用 `Map<String,Object>`＋Jackson，没有等价的编译期 JSON 约束。

【决定】作废。这是类型系统材质差异，没有运行时或模型可见行为差；不按 0.5 长期挂账。

【风险/代价】[内部，模型不可见] 若未来需要运行时 JSON 合法性校验，另开行为行，不用本行复活。

## 3. 对齐组：6 个形状差异

### 3.1 #20 `error` 载荷（权重 2）→ 对齐＋D206

**pi 证据**（`types.ts:831-837`，逐字）：

```ts
	| { type: "error"; reason: Extract<StopReason, "aborted" | "error">; error: AssistantMessage };
```

pi 的 `error` 载荷就是一条 `AssistantMessage`（含 `content`、`stopReason`、`errorMessage`、`usage`）。

**Java 证据**：`AI/stream/StreamEvent.java:335` 的 `StreamError(StopReason reason, Throwable error, AssistantMessage partial)`；其中 `partial()` **就是** pi 的 `error.error`（类注释 `:320-333` 逐字说明）。`Throwable` 只服务日志与 Java 栈，所有线投影都走 `StreamError.textOf`：

- `coding-agent/mode/JsonEventMapper.java:314-318`
- `coding-agent/modes/PrintMode.java:110-113`
- `web/AgentEventTranslator.java:112,241`
- `coding-agent/core/PayloadRecordingStreamFn.java:200-203`

【决定】对齐。模型可见/线可见的错误消息体与 pi 同形；`Throwable` 是【内部，模型不可见】的 Java 扩展。

【风险/代价】[内部，模型不可见] D206：`Throwable` 不得出现在任何序列化投影；新增投影必须走 `textOf`。若某处直接 `valueToTree(StreamError)`，栈会泄漏到线格——这是回归，不是本裁决允许的差异。

### 3.2 #24 `Model.input`（权重 1）→ 对齐＋D207

**pi 证据**：

- `packages/ai/src/providers/model-schema.ts:8`：`MODEL_INPUT_MODALITIES = ["text", "image"]`。
- `types.ts:846`：`input: ModelInputModality[]`。

**Java 证据**：`AI/provider/BuiltinModelCatalogs.java:356` 恒加 `TEXT`；`:362-363` 在 `def.input().contains("image")` 时加 `IMAGE_INPUT`。`ModelInfo.supportsImageInput()`（`:240`）以 `IMAGE_INPUT` 为门。

【决定】对齐。目录数据的 `input` 数组被逐项映射为 Java 能力位；`text` 是聊天模型的恒有能力。

【风险/代价】[内部，模型不可见] D207：`ModelInfo.minimal` 的能力集为空，`supportsImageInput()` 对「未知」按「支持」处理（`ModelInfo.java:240` 的刻意选择）。pi 没有「未知模型」状态（查不到就抛），所以这是 Java 独有状态下的方向性选择，不是输入模态映射的缺口。若未来 `minimal` 被用在模型可见的降级路径，必须复核该方向。

### 3.3 #28 `Model.reasoning`（权重 2）→ 对齐＋D207

**pi 证据**：

- `types.ts:854-862`：`Model.reasoning: boolean`。
- `types.ts:862-866`：`thinkingLevelMap` 为可选映射。

**Java 证据**：`BuiltinModelCatalogs.java:359-361`，`def.reasoning() == true` ⇒ `ModelCapability.THINKING`；`ThinkingLevelMap` 由 `ModelInfo` 独立组件承载（`AI/catalog/ModelInfo.java:51-52`）。

【决定】对齐。布尔值到能力位是一一映射；`thinkingLevelMap` 已在 #29 单独计分。

【风险/代价】[内部，模型不可见] D207 同时覆盖 `Model.input` 与 `Model.reasoning` 的「目录→能力位」投影：它们是同一套投影设施的两个面，不再拆两个 D 编号。

### 3.4 #38 ToolResult usage／details／duration（权重 1）→ 对齐＋D208

**pi 证据**（`types.ts:640-655`，逐字要点）：

```ts
export type ToolResultMessage<TDetails = JsonValue> = ... {
	details?: JsonRepresentation<TDetails>;
	/** Usage from the tool execution itself, if available. ... */
	usage?: Usage;
	...
	/** Milliseconds the tool's execution took ... */
	durationMs?: number;
}
```

**Java 证据**：`AI/message/Message.java:398-415` 的 `ToolResultMessage` 已有 `details`／`usage`／`durationMs` 三组件；javadoc（`:378-391`）逐字说明 `details`/`usage` 原样转发、`durationMs` 为单调钟毫秒。

【决定】对齐。三字段形状与缺席语义（`null` ≙ `undefined`，写侧省键）都在。

【风险/代价】[内部，模型不可见] D208：`details` 与 `usage` 在 Java 取 `Object`，因为 pi 的 `Usage` 类型在 Java 侧的工具执行结果属于 agent 模块，ai 不能反向依赖 agent。字段的**值**形状仍需保持 JSON 兼容；provider 投影不读这三项（与 pi 同）。

### 3.5 #47 自定义 provider（权重 2）→ 对齐＋D209

**pi 证据**：`packages/ai/src/models.ts:1077-1160` 的 `createProvider`：接受 `api`／`images`／`classifiers` 的模块映射、按 `model.api` 派发；`models-store.ts:17-44` 只负责持久化目录条目。

**Java 证据**：`AI/provider/ModelsJsonProvider.java:21` 从 `models.json` 的 `ProviderDef` 建 provider；`createChatApi`（`:87-93`）按 `OPENAI_COMPLETIONS`／`ANTHROPIC_MESSAGES`／`PI_MESSAGES` 三协议派发；模型级 `api` 在构造时并入可服务协议集（`:34-44`）。

【决定】对齐。pi 的 models.json 自定义 provider 语义（用模型声明的 `api` 选车道）在 Java 用静态协议注册表实现；可服务协议的**集合**与 pi 当前主流协议一致。

【风险/代价】[内部，模型不可见] D209：Java 不能加载任意 JS api 模块——新增 wire 协议必须先在 Java 侧实现适配器并注册，不能靠 models.json 动态扩展。若 pi 新增协议而 Java 未跟进，该差距按缺失行登记，不藏在本行。

### 3.6 #76 `lazyApi`／`lazyStream`（权重 1）→ 对齐＋D210

**pi 证据**（`packages/ai/src/api/lazy.ts:60-122`）：

- `lazyStream` 同步返回外层流，异步 setup 失败时发 `error` 事件并以 `createSetupErrorMessage` 收尾；
- `lazyApi` 首次调用时动态加载实现模块，`fetchDeferred`／`cancelDeferred` 按能力位可选包装。

**Java 证据**：`AI/provider/ProviderFactory.java:13-19` 是 ServiceLoader SPI；`ProviderRegistry.java:80-86` 在发现时同步构造 provider；`coding-agent/core/DefaultProviders.java:387` 在每次请求时 `provider.createApi(...)`，异常由宿主循环捕获并转错误消息。

【决定】对齐。Java 没有「动态模块加载」这一材质，但**行为契约**（setup 失败最终成为 assistant error 消息而非吞掉）由宿主层承担。

【风险/代价】[内部，模型不可见] D210：Java 的 setup 失败发生在 `createApi` 同步抛出，错误文本/时序与 pi 的 `error` 事件不完全同形；`fetchDeferred`／`cancelDeferred` 的懒包装没有对应物（与 #75/D203 同族）。若宿主层未来改变异常处理，本行必须复核。

## 4. 旁挂组：3 个依赖别包的行

### 4.1 #7 `pi-messages` wire（权重 1）→ 对齐＋D211

**pi 证据**：`packages/ai/src/api/pi-messages.ts:1-10`（文件头逐字）：单次 `POST {baseUrl}/messages`，body `{ model, context, options }`，响应是 assistant-message 事件的 SSE 流，以 `done`／`error` 终止。

**Java 证据**：`AI/protocol/PiMessagesApi.java:31-36`（类注释同形）；请求装配 `:345+` 的 `buildBody` 产 `{model, context, options}`，`:146` POST `baseUrl + "/messages"`。

【决定】对齐。粗 wire 行的**协议形状**已对齐；事件字段细差已由 #103（流式工具参数）与 #110（provider wire 语义）单独计分，按地图「粗 wire 行不重复承接细行缺口」的纪律不再扣本行。同时把 `docs/04` 的 **B18 改写**：其原文「PiMessages 是 pi-java 自有 wire，不按 pi 不存在的对应面改造」在固定锚点已不成立——pi 的 `packages/ai/src/api/pi-messages.ts` 就是同一协议；B18 改为「协议已存在；细差归 #110」后销号。

【风险/代价】[内部，模型不可见] D211：Java 的 `PiMessagesEvent` 是本仓的 record 方言，字段名与 pi 的 TS 联合逐项对应但不共享代码。若 #110 扩面到请求/响应外壳，必须回头复核本行。

### 4.2 #63 PiMessages thinking 线格（权重 1）→ 对齐＋D211

**pi 证据**（`pi-messages.ts:60-71` 与 `:236-240`，逐字要点）：

```ts
	| {
			type: "thinking_end";
			contentIndex: number;
			content: string;
			contentSignature?: string;
			redacted?: boolean;
	  }
```

`thinking_end` 把 `thinkingSignature`／`redacted` 装配回块。

**Java 证据**：`PiMessagesApi.java:171-181` 的 `ThinkingEnd` 分支调用 `builder.applyThinkingSignature(contentSignature, redacted)`，注释逐字引用 pi `:236-240`；事件 record 早声明四字段（类注释与 `PiMessagesEvent`）。

【决定】对齐。thinking 线格的签名/redacted 装配已在 Java 实现；其余 pi-messages 细差归 #110。

【风险/代价】[内部，模型不可见] 与 #7 共用 D211；两条同源，不重复扣分。

### 4.3 #75 `DeferredHandle`（权重 1）→ 对齐＋D212

**pi 证据**（`types.ts:539-548`，逐字）：

```ts
export interface DeferredHandle {
	provider: string;
	modelId: string;
	api: string;
	/** Provider token, such as a response id or batch id plus row id. */
	id: string;
	expiresAt?: number;
	pollAfterMs?: number;
	/** Provider conversion data required to reconstruct the final assistant message. */
	data?: JsonValue;
}
```

**Java 证据**：`AI/message/DeferredHandle.java:28-40` 七组件同名；`data` 为 `Map<String,Object>`（`JsonValue` 的 Java 载体）；javadoc `:13-18` 逐字：两侧都无真实生产者，pi 只有 faux 测试 provider 会返回。

【决定】对齐。类型与缺席语义同形；真实生产入口两侧都休眠，Java 的 `pi.ai.poll` 观察者已落地（docs/13），生产入口缺席继续由 **D203** 与 B267 的 Faux 面跟踪。旁挂开放项不为本行让路。

【风险/代价】[内部，模型不可见] D212：`JsonValue`→`Map` 的承载差异；若 `data` 需要数组或标量顶层值，`Map` 承载不了，必须复核。Faux 的 deferred 余量（#80）落地时，补一条生产→poll→重建的集成测试。

## 5. 测试与验证

本包不改代码，验证以**文档一致性与账面复算**为主：

1. `docs/map/02-ai.md` 逐行复核：15 行的判定、备注、权重与总表一致。
2. 对照 `docs/04`：D206–D212 七条登记齐全；D203 的旁挂说明更新。
3. 复算：`125` 计分单位＝对齐 111＋缺失 14；加权 `208.0/232`；未加权 `111/125`。
4. `docs/06` 状态只允许用「设计待审／实施中／已闭环」；本包闭环后写「已闭环」。
5. 不跑 `mvn clean verify`（无代码改动）；若同日有代码包收口，沿用其全量验证。

## 6. 风险与裁决

1. **作废改变分母**
   【场景】6 个 Java 扩展从存疑转作废，分母 240→232。
   【决定】接受；这是计分口径修正，不是行为变化。所有历史百分比加注分母。
   【风险/代价】[内部，模型不可见] 跨包比较失真；后续新包一律以 232 为基线。

2. **对齐行是否掩盖真实缺口**
   【场景】#47/#76 是材质差异，容易被误读为「完全一样」。
   【决定】对齐＋D 编号；D 文本逐条写明不可消除的材质与未来复核触发条件。
   【风险/代价】[内部，模型不可见] 若 D 文本过宽，后续会漏掉行为回归；D206–D212 必须逐条可验证。

3. **#7/#63 依赖去重纪律**
   【场景】#103/#110 仍是缺失行。
   【决定】本行按协议形状对齐，细差不重复扣分。
   【风险/代价】[内部，模型不可见] 若 #110 的语义扩到请求外壳，本行可能回退为存疑；触发条件写在 D211。

4. **#75 的生产入口仍缺席**
   【场景】两侧类型对齐，但真实 deferred 流没有生产者。
   【决定】类型/观察者对齐；生产入口挂 D203 与 #80。
   【风险/代价】[内部，模型不可见] 账面清零不等于功能可用；`pi.ai.poll` 的端到端测试仍须在 Faux 包补齐。

5. **不改代码的包也不能绕过审核**
   【场景】本包会让账面 +3.83 个百分点（85.83→89.66）。
   【决定】用户于 2026-11-03 批准后才改地图判定。
   【风险/代价】[内部，模型不可见] 批准前 docs/16 只是提案；现已闭环，docs/06 已写「已闭环」。

## 7. 地图与账面影响

实施并验证通过后：

1. `docs/map/02-ai.md` 15 行改判定：
   - 作废 6 行：#4、#5、#18、#21、#46、#88（权重 0，备注写「Java 扩展／类型系统材质」）。
   - 对齐 9 行：#7、#20、#24、#28、#38、#47、#63、#75、#76（备注写 D206–D212）。
2. 地图头部状态追加：`B267 第二批（15 存疑行）已按 docs/16 闭环（日期）`。
3. 汇总口径：见 §0.4；计分单位 125，对齐 111／存疑 0／缺失 14。
4. `docs/04`：新增 D206–D212；改写并销号 B18（协议已存在，细差归 #110）；B267 描述改为「usage／billing 与存疑复核已闭环；provider wire、retry／overflow、OAuth／device／env、pi-ai CLI 权限、Faux 余量与 deferred fetch 未对齐」。
5. `docs/06`：L1 行记录本包闭环，下一文档号 **docs/17**。
6. `docs/08` §9 L1 行同步。

## 8. 实施步骤（获批后）

1. 改 `docs/map/02-ai.md` 15 行与表头状态；先只改判定，不动正文证据列。
2. 在 `docs/04` 新增 D206–D212 七条，改写并销号 B18，并更新 D203 的旁挂说明。
3. 复算总表与域表；确认「计分单位 125／对齐 111／缺失 14」。
4. 更新 `docs/06`、`docs/08` 的状态与账面。
5. 回填本稿 §9 闭环记录。
6. Commit 建议：
   - `docs(ai): resolve L1 uncertain rows with rulings`

## 9. 闭环记录（实施后回填）

- 实施 HEAD：本提交（docs/16 闭环）
- 地图翻行：15 行已改（作废 6：#4／#5／#18／#21／#46／#88；对齐 9：#7／#20／#24／#28／#38／#47／#63／#75／#76）
- 新增 D 编号：D206–D212（七条已登记于 `docs/04` §6）；B18 已改写并销号
- 复算结果：计分单位 **125**（对齐 111／存疑 0／缺失 14）；加权 **208.0/232＝89.66%**；未加权 **111/125＝88.8%**；权重 1／2／3 ＝ 47/53＝88.7%／62/74＝83.8%／99/105＝94.3%；权重 0 计名单位 43（含 6 个转作废行）
- 与计划的偏差：① 旧地图「权重 2＝59/78」与逐行复算不符（真实为 61/78）；本包按逐行解析复算，w2 基线改为 61/78，翻行后 62/74。② 无代码改动，未重跑 `mvn clean verify`；沿用 docs/15 终态 5,987 测试／0 失败／0 错误／29 跳过。③ 本包只改判定与台账，`docs/06`／`docs/08`／`docs/04` 状态与账面已同步。
