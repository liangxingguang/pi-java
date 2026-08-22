# pi-java ↔ pi 功能对照表（按模块）

> pi 源码位置：`D:\workplaceForai\pi\packages\`
> pi-java 源码位置：各模块 `src/main/java/` 下 `com.pijava.*`
> 更新日期：2026-08-22（按模块重排；Phase 6 于 2026-08-22 完成）

## 0. 范围与方法

- **比较基准：功能完成度，不是代码行数**。pi 为 TypeScript 单体、pi-java 用库封装（TamboUI/openai-java SDK 等）替代大量实现，行数比无意义。本节及以下各模块以**功能能力覆盖**为判据：枚举 pi 对应包的核心能力，逐项标记 pi-java 覆盖状态。
- **覆盖范围**：pi 10 个包全部逐项对照。**按 pi-java 模块组织**（每个 pi-java Maven 模块一节），而非按 Phase 组织——一个模块往往横跨多个 Phase。
- **完成度标记**：✅ 已实现（行为/接口对齐）· ⚠️ 部分实现（存在已记录差异）· ❌ 未实现 · **pi-java 独有**（pi 无对应，能力超集）。
- **模块完成度判据**：按能力分组加权——核心用户路径（对话/工具/会话/持久化）权重高于外围能力（自动更新/prompt 模板等）。<80% 核心路径不完整；80–94% 核心路径完整、外围有缺口；≥95% 全能力对齐。

---

## 1. pi-java-ai ↔ packages/ai

> pi-java 源码位置：`pi-java-ai\src\main\java\com\pijava\ai\`
> **功能完成度 ~92%**：核心能力全覆盖（消息/流事件模型、chat 协议适配器、Provider 注册机制、认证、模型目录、HTTP 传输、图片/嵌入）；外围缺口——部分供应商（Bedrock/Vertex/OpenRouter chat 等，pi-java 聚焦中国大陆 16 个）、constrained sampling、`ModelInfo` compat 建模取舍。

### 1.1 API 接口层（`api/` ↔ `types.ts` + `compat.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `api/StreamApi.java` | `types.ts` `StreamFunction` 类型（L320） | ✅ 100% | Java 用 interface + `Flow.Publisher`；pi 用函数签名 `(model,context,options) => EventStream` |
| `api/SimpleApi.java` | `compat.ts` `streamSimple()` 包装 | ✅ 95% | ThinkingLevel 自动翻译已由 agent-core harness 承担 |
| `api/ChatApi.java` | 无直接对应 | ✅ 100% | Java 特有：`StreamApi + SimpleApi` 组合接口，实现 `ProviderApi` 标记 |
| `api/StreamRequest.java` | `types.ts` `Context` + `Model` 参数组合 | ✅ 100% | pi 不打包成单个 request 对象 |
| `api/StreamIterator.java` | `utils/event-stream.ts` `EventStream<T,R>` | ✅ 90% | pi 用 async iterator + backpressure queue；Java 用 `Iterator` + 虚拟线程阻塞 |
| `api/ApiOptions.java` | `types.ts` options 泛型参数 `TOptions` | **设计决策**（~90%） | pi 用 per-API 泛型；Java 用统一 record（Java 类型系统建模取舍） |
| `api/ToolDefinition.java` | `types.ts` `ToolDefinition` | ✅ 90% | 已补 label/promptSnippet/promptGuidelines/renderShell 字段（`14-phase6-alignment-90.md` §1.1）；`renderCall`/`renderResult` 为渲染层设计决策 |
| `api/ProviderApi.java` | `types.ts` `Api` 字符串字面量类型 | ✅ 100% | pi 用 tagged string；Java 用 `sealed interface`（`permits ChatApi, ImageApi, EmbeddingApi`，P6-28） |

### 1.2 协议适配器（`protocol/` ↔ `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `protocol/AnthropicMessagesApi.java` | `api/anthropic-messages.ts`（~1200 行） | ✅ 90% | **核心差异**：pi 用原始 SSE 解析，Java 用官方 SDK `anthropic-java`；消息转换逻辑一致。P6-1a 补 `apiKeyEnvVar` + `baseUrl` 覆盖 |
| `protocol/OpenAICompletionsApi.java` | `api/openai-completions.ts`（~600 行） | ✅ 90% | 都用 SDK。pi 的工具调用聚合内联；Java 有独立 `ToolCallBuilder` |
| `protocol/GoogleGenerativeAiApi.java` | `api/google-generative-ai.ts`（~500 行） | ✅ 95% | 共用 `google-shared.ts` 工具转换；Java 独自分装 |
| `protocol/MistralConversationsApi.java` | `api/mistral-conversations.ts`（~200 行） | ✅ 95% | 都用原始 HTTP + SSE，逻辑一一对应 |
| `protocol/QueueStreamIterator.java` | `utils/event-stream.ts` `EventStream` | ✅ 90% | pi 的 `EventStream` 更完善（backpressure/abort） ；已补 abort（拉取式天然背压，`14-phase6-alignment-90.md` §1.2） |
| `protocol/ToolCallAccumulator.java` | 无独立文件 | ✅ 100% | pi 内联，Java 提取为共享类 |
| `protocol/OpenAIResponsesApi.java`（P6-1e） | `api/openai-responses.ts`（~800 行） | ✅ ~90% | Responses 事件映射逐行对齐 |
| `protocol/AzureOpenAIResponsesApi.java`（P6-1f） | `api/azure-openai-responses.ts` | ✅ ~90% | SDK 原生 Azure 支持（无需 `com.azure`） |
| `protocol/PiMessagesApi.java`（P6-1g） | `api/pi-messages.ts` | ✅ ~90% | 事件与 `StreamEvent` 近乎 1:1 |
| `protocol/OpenRouterImagesApi.java`（P6-28） | `api/openrouter-images.ts` | ✅ ~90% | chat-with-modalities；pi-java 用 openai-java SDK（pi 用 openai npm SDK） |

**pi 有、pi-java 未实现的适配器**：Bedrock Converse（`api/bedrock-converse-stream.ts`）、Google Vertex（`api/google-vertex.ts`）、OpenAI Codex Responses（`api/openai-codex-responses.ts`）、Cloudflare Workers AI（`api/cloudflare.ts`）——按需 / 渐进。

### 1.3 消息与流事件（`message/` + `stream/` ↔ `types.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `message/Message.java` | `types.ts` `Message` 联合（L455） | ✅ 100% | sealed hierarchy：System/User/Assistant/ToolResult |
| `message/ContentBlock.java` | `types.ts` content block 类型 | ✅ 100% | Text/Thinking/Image/ToolUse/ToolResult 五类一致 |
| `stream/StreamEvent.java` | `types.ts` `AssistantMessageEvent`（13 种子类型，L523） | ✅ 95% | 13 种事件（Start/Text*/Thinking*/ToolCall*/UsageInfo/StreamDone/StreamError），每种携带 `partial` 快照 |
| `stream/StreamPartialBuilder.java` | `utils/event-stream.ts` 内聚 | ✅ 90% | 事件增量 → `AssistantMessage` 快照构建 |

### 1.4 Provider 配置与注册（`provider/` ↔ `providers/` + `models.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `provider/Provider.java` | `models.ts` `Provider` interface（~15 字段） | **设计决策**（~90%） | pi 更复杂：含 `api`/`models`/`thinkingLevelMap`/`auth`/`baseUrl` 等 （设计决策：Java SPI + ProviderApi sealed 层级承载） |
| `provider/ProviderFactory.java` | `models.ts` `createProvider()` 工厂函数 | ✅ 100% | pi 用函数，Java 用 SPI 接口 |
| `provider/ProviderRegistry.java` | `compat.ts` api-registry + `providers/all.ts` `builtinProviders()` | ✅ 90% | 手动注册 + 全局查询；P6-1d 补 ServiceLoader 发现 |
| `provider/ConfigurableProvider.java`（P6-1b） | `providers/` 配置驱动模式 | ✅ ~90% | 三能力分派（Chat/Image/Embedding）+ 协议路由 |
| `provider/builtin/*.java`（17 个，P6-1c） | `providers/all.ts` builtinProviders()（39 chat + 1 image） | **设计决策** | 聚焦中国大陆 16 chat + `openrouter-images`（见 `13-phase6-alignment-improvements.md` §3.1） |
| `provider/AnthropicProvider.java` 等 5 家 | `providers/anthropic.ts` 等 | ✅ 100% | 名字/baseUrl/绑定适配器逻辑一致 |
| `provider/FauxProvider.java` | `providers/faux.ts` | ✅ 100% | 三种回放模式完全对齐 |

### 1.5 模型目录（`catalog/` + `model/` ↔ `model-catalog.ts` + `models-store.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `model/ModelId.java` | `model-catalog.ts` `ModelId` 类型 | ✅ 100% | pi 用 `{provider, modelId}` 对象；Java 用 record |
| `model/ModelInfo.java` | `types.ts` `Model<TApi>` interface（L794） | **设计决策**（~90%） | 已补 `headers`/`samplingParams` 字段（`13-phase6-alignment-improvements.md` §1.1）；`compat` 为 per-protocol 类型，由协议适配器承载（建模取舍） |
| `model/ModelCapability.java` | `types.ts` capability tags | ✅ 100% | P6-28 加 `IMAGE_OUTPUT` |
| `model/PricingInfo.java` | `types.ts` `ModelCost` + `ModelCostRates` | ✅ 90% | pi 分 input/output/cacheRead/cacheWrite 四种；Java 只分 input/output |
| `catalog/ModelCatalog.java` | `model-catalog.ts` `ModelCatalog` type | ✅ 90% | |
| `catalog/BuiltinCatalog.java` | `providers/*.models.ts`（自动生成） | **设计决策**（~95%） | pi 自动生成；Java 手写（P6-28 加 embedding 模型） （设计决策：pi 自动生成，Java 手写 catalog） |
| `catalog/ModelsStore.java` + `FileModelsStore.java`（P6-8） | `models-store.ts` `ModelsStoreEntry` | ✅ ~90% | 补 Phase 1 缺口；读写删 |
| `catalog/RemoteCatalog.java`（P6-8） | 远程目录（ETag 条件刷新） | ✅ ~90% | ETag 含引号原样透传 |
| `catalog/CatalogPublisher.java`（P6-10） | `pi-ai catalog` 发布工具 | ✅ ~90% | 模型目录发布 |

### 1.6 认证（`auth/` ↔ `auth/` + `env-api-keys.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `auth/CredentialStore.java` | `auth/credential-store.ts` `CredentialStore` | ✅ 100% | resolveApiKey/storeApiKey/deleteApiKey 三方法一致 |
| `auth/EnvApiKeyResolver.java` | `env-api-keys.ts` | ✅ 100% | 环境变量映射表一致 |
| `auth/FileCredentialStore.java` | `auth/credential-store.ts` `InMemoryCredentialStore` + `cli.ts` | ✅ 90% | pi 用内存 store + Node fs；Java 用 `FileChannel.lock()` 跨进程并发 |
| `auth/OAuthFlow.java` + `DeviceCodeFlow.java` + `AuthProfileManager.java`（P6-17/18） | `auth/oauth/`（9 个 OAuth flow） | ✅ 90% | PKCE（openrouter/anthropic）+ RFC 8628 device-code（xai/kimi/github-copilot/openai-codex/radius）；`13-phase6-alignment-improvements.md` §1.5 |
| — | `auth/resolve.ts` / `context.ts` | ✅ 90% | 认证解析管线由 coding-agent `DefaultProviders.resolveProviderName` + `AuthCommand` 承担 |

### 1.7 HTTP 传输（`http/` ↔ `utils/` + `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `http/PiHttpClient.java` | `utils/event-stream.ts` SSE 解析 + 各适配器原始 SSE | ✅ 90% | pi 的 SSE 解析分散在适配器内部；Java 集中封装 |
| `http/RetryPolicy.java` | `utils/retry.ts` + `utils/provider-retry.ts` | ✅ 90% | 指数退避、Retry-After 解析一致 |
| `http/PiHttpException.java` | `utils/error-body.ts` `formatProviderError()` | ✅ 100% | HTTP 错误码 → 异常 |
| `http/ProxyDetector.java` | `utils/node-http-proxy.ts` | ✅ 90% | 系统代理检测一致 |

### 1.8 CLI（`cli/` ↔ `cli.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `cli/AiCli.java` | `cli.ts`（~150 行） | ✅ 90% | list-models/auth/ping；P6-28 加 `image`/`embed`；pi 还支持 OAuth login |

### 1.9 图片与嵌入（P6-28）— `provider/builtin/OpenRouterImagesProvider` + `protocol/OpenRouterImagesApi` ↔ `providers/openrouter-images.ts` + `images-models.ts`

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|--------|-----------------|--------|----------|
| `OpenRouterImagesProvider.java` | `providers/openrouter-images.ts` | ✅ ~90% | 独立 images provider，8 个模型（FLUX.2/seedream/gemini-image） |
| `OpenRouterImagesApi.java` | `api/openrouter-images.ts` | ✅ ~90% | chat-with-modalities；`Modality.Companion.of("image")` 绕开 SDK 枚举缺 IMAGE |
| `api/ImageApi`/`ImageRequest`/`ImageResult`/`ImageStopReason` | `images-models.ts`（ImagesFunction/ImagesContext/AssistantImages/ImagesStopReason） | ✅ ~95% | 接口/字段逐一对应 |
| `api/EmbeddingApi` + `protocol/OpenAIEmbeddingApi.java` | —（pi 无 embedding） | pi-java 独有 | OpenAI `/v1/embeddings` |

---

## 2. pi-java-telemetry ↔ packages/telemetry

> pi-java 源码位置：`pi-java-telemetry\src\main\java\com\pijava\telemetry\`
> **功能完成度 ~90%**：核心能力全覆盖（TelemetryContext/Span/Noop + OTel 实现 P6-20）；缺口——memory 后端、遥测 conformance 套件。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `TelemetryContext.java` | `index.ts`（TelemetryContext 类型） | ✅ 90% | 采样/父上下文语义一致 |
| `TelemetrySpan.java` | `index.ts` `TelemetrySpan` | ✅ 90% | `addAttribute`/`end` 一致；pi 支持 `addLink`/`event`，Java 未实现 |
| `SpanOptions.java` | `index.ts` `SpanOptions` | ✅ 95% | 字段对应；Java 用 record |
| `NoopTelemetryContext.java` | `noop.ts` | ✅ 100% | 空实现逐方法一致 |
| `OtelTelemetryContext.java`（P6-20） | OTel 适配器 | ✅ ~90% | OpenTelemetry-backed 实现 |
| — | `memory.ts` + `testing/` | — | 内存后端 + conformance 套件未实现 |

---

## 3. pi-java-agent-core ↔ packages/agent

> pi-java 源码位置：`pi-java-agent-core\src\main\java\com\pijava\agent\`
> **功能完成度 ~92%**：核心能力全覆盖（harness 循环、11 hooks、工具系统、上下文/压缩、存储契约、JSONL/Memory 后端）；缺口——branch-summarization、image 工具。

### 3.1 Harness 与循环（`harness/` + `loop/` ↔ `harness/` + `agent-loop.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `harness/AgentHarness.java` | `harness/agent-harness.ts`（~900 行） | ✅ 90% | 多车道、`peekAction`/`executeAction`/`runToCompletion`、watch 订阅一致；pi 另有 `before_resume`，Java 简化为 `seedTranscript` |
| `harness/ActionExecutor.java` | `harness/agent-harness.ts` 内 action 分派 | ✅ 95% | 五类 action 对应；pi 的 action 集合更细（含 navigation/steer 注入） （口径修正：五类 action 一致） |
| `harness/Action.java` | `harness/agent-harness.ts` `Action` 联合 | ✅ 95% | 五种 action 一致；pi 还区分 pending write 的 lane （口径修正：五种 action 一致） |
| `harness/HarnessUtils.java` | `harness/types.ts` + 工具函数 | ✅ 90% | `newestOwn`/`determineOutcome`/`extractToolCalls` 一致 |
| `harness/LaneState.java` | `harness/types.ts` `LaneState` | ✅ 95% | transcript/pendingWrites/queue 三队列一致 （口径修正：三队列一致） |
| `harness/LaneSnapshot.java` + `SnapshotService.java` | `harness/events.ts` 快照 + `harness/types.ts` | ✅ 90% | `watch()` → `WatchHandle<LaneSnapshot>` 对应 |
| `harness/QueueManager.java` | `harness/agent-harness.ts` steer/followUp/nextRun | ✅ 90% | 三队列 drain 顺序一致 |
| `harness/DriveMode.java` | `harness/agent-harness.ts` drive modes | ✅ 90% | Manual/Automatic 对应 |
| `loop/AgentLoop.java` | `agent-loop.ts` | ✅ 90% | 提交 → 流式 → 工具 → 完成循环一致 |
| `harness/ToolExecutionPipeline.java` | `harness/agent-harness.ts` 工具执行阶段 | ✅ 90% | before_tool/after_tool 钩子 + 串行/并行执行 |
| `harness/StreamFn.java` | `stream-fn.ts` `StreamFunction` | ✅ 95% | 签名对齐 |

### 3.2 Hook 系统（`hook/` ↔ `harness/agent-harness.ts` 11 个 hooks）

| pi hook | pi-java 对应 | 对齐度 | 差异说明 |
|---------|-------------|--------|---------|
| `before_run` | `hook/BeforeRunHook.java` + `RunContext` | ✅ 95% | 可改写 originalPrompt |
| `before_resume` | `hook/BeforeResumeHook.java` + `ResumeContext` | **设计决策**（~95%） | 扩展契约（内建流程双方均不触发）；Java 类型化 `ResumeContext` vs pi 未类型化 `event: unknown` |
| `transform_context` | `hook/TransformContextHook.java` | ✅ 95% | 消息列表变换一致 |
| `before_request` | `hook/BeforeRequestHook.java` | ✅ 95% | 注入/改写消息一致 |
| `before_payload` | `hook/BeforePayloadHook.java` | ✅ 90% | 载荷校验/改写一致 |
| `after_response` | `hook/AfterResponseHook.java` | ✅ 95% | usage/stopReason 透传一致 |
| `before_tool` / `after_tool` | `hook/BeforeToolHook.java` / `AfterToolHook.java` | ✅ 95% | 参数/结果改写一致 |
| `before_compaction` | `hook/BeforeCompactionHook.java` | ✅ 90% | 可覆盖压缩计划 |
| `before_navigation` | `hook/BeforeNavigationHook.java` | **设计决策**（~95%） | 扩展契约（内建流程双方均不触发）；Java 类型化 `NavigationContext` |
| `before_run_end` | `hook/BeforeRunEndHook.java` | ✅ 95% | 终态判定一致 |
| 调度器 | `hook/HookSystem.java` | ✅ 90% | 注册/触发/非致命异常一致；pi 异步，Java 同步 |

### 3.3 工具系统（`tool/` ↔ `harness/tools/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `tool/AgentTool.java` | `harness/tools/index.ts` `AgentTool` | ✅ 95% | `prepareArguments`/`executionMode`/`inputSchema` 一致；pi 还含 `ToolUpdateCallback` （口径修正：execute 签名已含 ToolUpdateCallback） |
| `tool/ToolRegistry.java` | `harness/tools/index.ts` 注册表 | ✅ 90% | 注册/查询/definition 导出一致 |
| `tool/ToolExecutor.java` | `harness/agent-harness.ts` 工具执行 | ✅ 90% | 串行/并行批量执行一致 |
| `tool/ToolSetFactory.java` | `harness/tools/index.ts` `createCodingToolDefinitions` | ✅ 90% | coding/readOnly 分组一致 |
| `tool/builtin/BashTool.java` | `harness/tools/bash.ts` | ✅ 90% | 参数/超时/输出截断一致；shell 发现按 pi `shellPath` 语义 |
| `tool/builtin/WriteTool.java` / `ReadTool.java` | `harness/tools/write.ts` / `read.ts` | ✅ 90% | 写文件 + 行校验 / 行范围读取一致 |
| `tool/builtin/EditTool.java` | `harness/tools/edit.ts` + `edit-diff.ts` | ✅ 90% | fuzzy 匹配（NFKC）+ 对原始内容匹配反序 apply + 重叠校验 + 行尾/BOM 保留 + file-mutation-queue 串行化；`13-phase6-alignment-improvements.md` §1.4 |
| `tool/builtin/GrepTool.java` / `GlobTool.java` / `LsTool.java` | 无直接对应 | — | **pi-java 扩展工具** |
| `tool/ToolContext.java` | `harness/tools/tool-context.ts` | ✅ 90% | cwd/env/shell/fileSystem 注入一致 |
| `tool/PathUtils.java` / `TruncationUtils.java` | `path-utils.ts` / `truncate.ts` | ✅ 95% | 路径安全校验 / 输出截断一致 |
| `tool/DefaultShellExecutor.java` | `harness/env/nodejs.ts` + shell 执行 | ✅ 90% | bash 执行/登录 shell 发现对齐 |
| — | `harness/tools/image.ts` / `file-mutation-queue.ts` | — | 图片工具 / 批量文件变更队列未实现 |

### 3.4 上下文与压缩（`context/` + `compaction/` ↔ `harness/compaction/` + `utils/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `context/OverflowDetector.java` | `utils/overflow.ts` `isContextOverflow()` | ✅ 95% | 触发条件一致 |
| `context/ContextEstimator.java` | `utils/estimate.ts` | ✅ 95% | chars/4 启发式一致 |
| `compaction/CompactionSettings.java` | `harness/compaction/compaction.ts` | ✅ 95% | enabled/reserveTokens/keepRecentTokens 一致 |
| `compaction/CompactionService.java` | `harness/compaction/compaction.ts` `compact()` | ✅ 90% | LLM 摘要已接线（`LlmSummaryGenerator`，见 `13-phase6-alignment-improvements.md` §1.3） |
| `compaction/CompactionResult.java` | `harness/compaction/compaction.ts` `CompactionResult` | ✅ 90% | 字段一致 |
| — | `harness/compaction/branch-summarization.ts` | — | 分支摘要未实现 |

### 3.5 存储契约与数据模型（`session/` + `entry/` + `record/` ↔ `harness/session/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `session/SessionStorage.java` | `session/types.ts` `SessionStorage` | ✅ 95% | 方法一一对应；Java 同步化 + `drain()`/`close()` |
| `session/SessionRepository.java` | `session/types.ts` `SessionRepo` | ✅ 95% | create/open/list/delete/fork 一致；泛型三参对应 options |
| `session/Session.java` + `SessionTree.java` | `session/session.ts` `Session` + `SessionTree` | ✅ 90% | view(lane)/findEntry(s)/appendMessage 一致 |
| `session/SessionState.java` | `session/state.ts` `SessionState` | ✅ 95% | seq 严格递增、id 唯一、parent 链校验、openOperations、createForkMutations 一致 |
| `session/SessionMutation.java` / `LogItem.java` | `session/state.ts` / `types.ts` | ✅ 95% | 5 变体对应（entry/record/lane/fact name/fact label） |
| `session/EntryQuery.java` 等类型 | `session/types.ts` | ✅ 95% | limit/afterSeq 用可空类型表达 `undefined`；`ForkOptions.Position` 为 enum |
| `session/SessionError.java` + `SessionErrorCode.java` | `session/types.ts` | ✅ 100% | 8 个 snake_case 字面量一致 |
| `session/SessionStats.java` | `session/types.ts` `SessionStats` | ✅ 100% | 字段一致 |
| `session/SessionSearch.java` | `session/search.ts` | ✅ 90% | 契约一致；JSONL 扫描式搜索后端未实现 |
| `session/jsonl/`（JsonlCodec/Storage/Repository） | `session/jsonl/` | ✅ 90–95% | 编解码/原子发布/导入一致；v3→v4 惰性迁移为 pi-java 扩展 |
| `session/memory/` | `session/memory.ts` | ✅ 95% | conformance oracle 语义一致 |
| `entry/Entry.java`（7 子类型） | `session/types.ts` `Entry`（7 类型） | ✅ 95% | 平铺 `id/seq/parentId/timestamp`、`@JsonTypeInfo("type")` 名称一致 |
| `record/LaneRecord.java`（9 子类型 + Intent） | `session/types.ts` `LaneRecord`（9 类型） | ✅ 95% | 判别 enum 字面量一致 |
| `entry/ProvisionedEntry.java` / `record/NewRecord.java` | `session/types.ts` 类型别名 | ✅ 95% | 表达「缺 seq/parentId/timestamp」写入入参 |
| `ai/Usage.java` | pi-ai `types.ts` `Usage` | ✅ 95% | input/output/cacheRead/cacheWrite/totalTokens/cost.total 一致 |

### 3.6 技能与提示（`skill/` + `prompt/` ↔ `harness/skills.ts` + `harness/system-prompt.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `skill/SkillManager.java` | `harness/skills.ts` | ✅ 90% | 完整 frontmatter 解析/目录规则在 coding-agent skill/（P6-6），跨模块口径 |
| `prompt/SystemPromptBuilder.java` | `harness/system-prompt.ts` | ✅ 90% | 基础/工具/技能拼接一致；prompt 模板渲染（`prompt-templates.ts`）未实现 ；prompt-templates 已实现（`PromptTemplates`，`14-phase6-alignment-90.md` §1.3） |

---

## 4. pi-java-session-backend-sqlite ↔ packages/session-backends/sqlite-node

> pi-java 源码位置：`pi-java-session-backend-sqlite\src\main\java\com\pijava\session\sqlite\`
> **功能完成度 ~90%**：全能力对齐（repository/storage/writer lease/branch cache/FTS5 搜索/migrations）；差异为技术栈（sqlite-jdbc vs node:sqlite）与并发压测待做。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `SqliteSessionRepository.java` | `sqlite/repo.ts`（965 行） | ✅ 90% | create/open/list/delete/fork/repairBranchCache/close 一致；Java 用 ReentrantLock 串行化 |
| `SqliteSessionStorage.java` | `sqlite/repo.ts` 内 `SqliteSessionStorage` | ✅ 90% | 写事务内 renew lease → 变更 → advance sequence 一致；Java 心跳线程 + leaseError 停写 |
| `SqliteDatabase.java` | `sqlite/types.ts` `SqliteDatabase` | ✅ 90% | 薄封装；pi 用 node:sqlite，Java 用 sqlite-jdbc |
| `storage/SessionRows.java` / `EntryRows.java` / `RecordRows.java` / `LaneRows.java` / `FactRows.java` | `sqlite/storage/sessions.ts` / `entries.ts` / `records.ts` / `lanes.ts` / `facts.ts` | ✅ 95% | 逐 SQL 对应 |
| `storage/BranchEntryRows.java` + `BranchTipRows.java` | `sqlite/storage/branch-entries.ts` + `branch-tips.ts` | ✅ 90% | cached query 一致 |
| `storage/SequenceRows.java` / `StatsRows.java` / `WriterLeaseRows.java` | `sqlite/storage/session-sequences.ts` / `session-stats.ts` / `writer-leases.ts` | ✅ 95% | 逐 SQL 对应 |
| `WriterLease.java` | `sqlite/storage/writer-leases.ts` | ✅ 95% | acquire/renew/release 一致 |
| `BranchCache.java` | `sqlite/branch-cache.ts` | ✅ 90% | 增量维护/rebuild/SAVEPOINT 一致 |
| `SqliteSessionSearch.java` | `sqlite/search-backend.ts` | ✅ 90% | FTS5 trigram + bm25 + cwd 过滤一致 |
| `Migrations.java` + `resources/sql/001_initial.sql` | `sqlite/migrations.ts` + `migrations/001_initial.sql` | ✅ 95% | 11 表逐列一致 |
| `SqliteSessionBackendFactory.java` + `META-INF/services` | `sqlite/index.ts` 导出 | ✅ 90% | ServiceLoader 注册等价于 pi 包导出 |

---

## 5. pi-java-tui ↔ packages/tui

> pi-java 源码位置：`pi-java-tui\src\main\java\com\pijava\tui\`
> **功能完成度 ~90%**：核心交互能力全覆盖（会话界面、输入/滚动/选择器/主题、Markdown 渲染、语法高亮、会话 diff、富过滤键）；缺口——Markdown latex；渲染层为 TamboUI 封装（设计决策）。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `util/TamboUIAdapter.java` + `util/InlineTuiShell.java` | `tui.ts` + `terminal.ts`（终端抽象/渲染循环） | **设计决策**（~90%） | 差量渲染/输入事件经 TamboUI 承载；pi 自研终端原语（渲染层设计决策） |
| `app/PiTuiApp.java` + `screen/ChatScreen.java` | `tui-main-screen.ts` | ✅ 90% | 会话主界面、输入提交、流式气泡一致 |
| `util/ScrollConfig.java` + `ScrollInputNormalizer.java` | `terminal.ts` | ✅ 90% | 滚动参数与 Codex CLI 对齐 |
| `component/SelectList.java` | `components/select-list.ts` | ✅ 90% | 选择器一致 |
| `component/MarkdownRenderer.java`（P6-22） | `components/markdown.ts` | ✅ 90% | 代码块语法高亮已接入 `SyntaxHighlighter`（`13-phase6-alignment-improvements.md` §1.2）；latex 仍缺 |
| `component/EditorComponent.java`（P6-23） | `components/editor.ts` + `editor-component.ts` | ✅ 90% | undo（fish 合并）+ kill-ring（Ctrl+K/U/W、Alt+D、Ctrl+Y、Alt+Y）+ 词导航（Ctrl+Left/Right、Alt+B/F）+ Ctrl+A/E/B/F；`13-phase6-alignment-improvements.md` §1.6 |
| `component/FuzzyMatcher.java`（P6-24） | `fuzzy.ts` | ✅ 90% | 模糊匹配 + 富过滤键绑定 |
| `theme/PiTheme.java`（P6-21） | `terminal-colors.ts` | ✅ 90% | 主题色板 + 自定义主题文件加载 |
| `util/ScrollbackTranscript.java` | `utils.ts`（滚动缓冲） | ✅ 90% | 滚动历史一致 |
| `app/PiTuiEntryPoint.java` | `index.ts` | ✅ 90% | 入口接线一致 |
| `screen/SessionListScreen.java` + 会话 diff 渲染（P6-26） | `components/` 相关 | ✅ 90% | 会话切换 + diff 渲染完整（diff 在 ChatScreen） |
| 组件族（`component/` 其余） | `components/` | **设计决策** | 由 TamboUI 组件等价承载（`13-phase6-alignment-improvements.md` §3.3） |

---

## 6. pi-java-coding-agent ↔ packages/coding-agent

> pi-java 源码位置：`pi-java-coding-agent\src\main\java\com\pijava\coding\agent\`
> **功能完成度 ~90%**（早期按行数比记为 65%，无意义——pi 为单体把交互模式/工具/扩展运行时全打包，pi-java 拆到 tui + agent-core）。

**核心用户路径全覆盖**：CLI（~40 参数 + 子命令）、Print/Interactive/RPC 三模式、会话组装/驱动/持久化、设置/信任、23+ slash 命令、RPC 32 命令（P6-5）、技能系统（P6-6）、扩展系统（P6-7）、HTML 导出（P6-12）、`/share`（P6-13）、config/package/auth 子命令（P6-14/15/16）、AI 生成技能（P6-27）。

**缺口（pi 有、pi-java 未实现）**：prompt-templates、auto-format、resource-loader、process-manager、auto-update、image 工具、interactive 模式逐文件移植（渲染层走 TamboUI 在 tui 模块）、`utils/` 工具集、更多 slash 命令。Bun 运行时为 pi-java 不适用（JVM）。

| pi-java 分组 | pi 对应 | 对齐度 | 差异说明 |
|-------------|---------|--------|---------|
| `Main.java` + `cli/ArgsParser.java` + `cli/Args.java` | `cli.ts` + `cli/args.ts` | **设计决策**（~90%） | ~40 参数 + 子命令（auth/config/package/list-models）对齐；install/remove/update 为打包类子命令，scope 取舍 |
| `modes/PrintMode.java` | `modes/print-mode.ts` | ✅ 90% | `-p "prompt"` 一次性输出一致 |
| `modes/InteractiveMode.java` | `modes/index.ts` + `cli/startup-ui.ts` + `modes/interactive/`（17K 行） | **设计决策** | 交互全栈由 tui 模块经 TamboUI 承载（`13-phase6-alignment-improvements.md` §3.2） |
| `core/AgentSession.java` + `SessionRunner.java` + `SessionPersistence.java` | `server/create-harness.ts` + `core/agent-session.ts`（~10K 行） | ✅ 90% | 会话组装/驱动/持久化一致；pi 的 server 化会话/重试/自动格式化等子能力未全量移植 ；重试已带指数退避 + 上下文溢出不重试；auto-format 分期（`14-phase6-alignment-90.md` §1.4） |
| `core/SessionServices.java` | `core/`（DI 容器） | ✅ 90% | settings/trust/providers/models/tools/slash/sessionRepository 七件套一致 |
| `core/SettingsManager.java` + `Settings.java` | `config.ts` + `core/settings` | ✅ 90% | 全局/项目分层合并、JSON 边界一致 |
| `core/TrustManager.java` | `cli/project-trust.ts` | ✅ 90% | `~/.pi-java/trust/` 标记文件落盘一致 |
| `core/slash/`（CommandRegistry + builtin） | `core/slash-commands` | ✅ 90% | 23 个内置命令覆盖；P6-13 `/share`、P6-27 `/create-skill`、P6-12 `/export` 已补 |
| `core/KeybindingsManager.java` | `core/keybindings.ts` | ✅ 90% | 键位定义/覆盖一致 |
| `core/DefaultProviders.java` | `core/model-resolver.ts` | ✅ 90% | provider/model 解析一致 |
| `rpc/`（RpcCommand/RpcDispatcher/RpcMode/JsonlReader/JsonlWriter，P6-5） | `modes/rpc/`（4 文件 / 1,765 行） | ✅ ~90% | 32 命令对齐 pi 30 命令；LF-only 分帧、事件剥 partial、auto-compaction/bash/retry 全实现 |
| `mode/JsonEventMapper.java` | `modes/json-event.ts` | ✅ ~95% | 剥 `partial` + 全事件线格式 |
| `skill/`（MarkdownSkillLoader/SkillDiscovery/IgnoreFilter，P6-6） | `harness/skills.ts` | ✅ ~90% | SKILL.md 目录规则、前言校验、baseDir、JGit ignore 三态 |
| `extension/`（ExtensionManager/PiExtension，P6-7） | `extensions/`（6 文件 / 1,414 行） | ✅ ~90% | ServiceLoader + loadJar + `--no-extensions` |
| `export/HtmlExporter.java`（P6-12） | `core/export-html/`（3 文件 / 746 行） | ✅ ~90% | HTML 会话导出渲染器 |
| `subcommand/`（auth/config/package，P6-14/15/16） | `cli/`（auth/config/package-command） | ✅ ~90% | 子命令对应；pi 还有 session-picker/file-processor/initial-message |
| `spi/`（Skill SPI 等） | `core/` 对应 | ✅ ~90% | 服务发现 SPI |

**pi 有、pi-java 未实现的**：`bun/`（Bun CLI，pi-java 为 JVM 不适用）、`client/` + `server/`（远程会话控制面，pi-java 在独立模块见 §8/§9）、`utils/`（33 文件 / 3.5K 行工具集——prompt-templates、auto-format、resource-loader、process-manager、auto-update 等）、image 工具集、更多 slash 命令。

**功能完成度小结**：核心用户路径（对话/工具/会话/持久化/RPC/技能/扩展）全覆盖；缺口集中在 pi 的交互全栈逐文件移植与外围工具（auto-update/prompt-templates 等）。综合 ~90%。

---

## 7. pi-java-protocol ↔ packages/protocol

> pi-java 源码位置：`pi-java-protocol\src\main\java\com\pijava\protocol\`
> **功能完成度 ~90%**：CBOR 编解码 + 增量分帧 + 信封/命令/结果/事件全 sealed 层次（P6-9a）。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `CborCodec.java` | `cbor/`（encoder/decoder/options） | ✅ ~90% | `ClientMessage`/`ServerMessage`/`Command`/`CommandResult`/`ServerEvent` 全 sealed 层次 round-trip |
| `FrameCodec.java` + `FrameDecoder.java` | `framing.ts` + `codec.ts` | ✅ ~90% | 4 字节大端长度前缀 + 增量分帧（16MB 上限、header 切断、残留帧） |
| 信封/命令/结果/事件类型 | `schemas.ts` | ✅ ~90% | hello/request/response/event 信封、命令集、快照/转录模型 |


---

## 8. pi-java-client ↔ packages/client

> pi-java 源码位置：`pi-java-client\src\main\java\com\pijava\client\`
> **功能完成度 ~90%**：远程会话客户端（list/create/attach/prompt/abort/detach，P6-9c）。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `PiClient.java` + `SessionHandle.java` | `client/` | ✅ ~90% | 远程会话客户端（list/create/attach/prompt/abort/detach） |
| 并发/锁语义 | `client/` | ✅ ~90% | 并发 attach 冲突由 server 端 `SESSION_LOCKED` 承载 |


---

## 9. pi-java-server ↔ packages/server

> pi-java 源码位置：`pi-java-server\src\main\java\com\pijava\server\`
> **功能完成度 ~90%**：会话控制面 + 独占租约 + 快照订阅 + Unix socket（P6-9b）。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `PiServer.java` + `PiSessionRuntime.java` | `server/` | ✅ ~90% | 会话控制面 + 独占租约 + 快照订阅；冲突操作直接拒绝不排队 |
| Unix socket 监听 | `server/` | ✅ ~90% | 本地 AF_UNIX（含 Windows）集成 |


---

## 10. pi-java-evals ↔ packages/evals

> pi-java 源码位置：`pi-java-evals\src\main\java\com\pijava\evals\`
> **功能完成度 ~90%**：conformance 套件（C1–C8）+ smoke + extension（P6-2/3/4）。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `evals/conformance/*` | `evals/*.ts` | ✅ ~90% | Provider/Catalog/API conformance 套件（C1–C8） |
| `evals/smoke/*` | — | pi-java 独有 | 每 provider 1 真实请求，需凭据默认跳过 |
| `evals/extension/*` | `evals/*` | ✅ ~90% | extension 生命周期测试 |


---

## 11. 功能完成度汇总

| 模块 | 功能完成度 | 核心能力 | 主要缺口 |
|------|-----------|---------|---------|
| telemetry | ~90% | TelemetryContext/Span/Noop/OTel | memory/testing 后端 |
| ai | ~92% | 消息/流事件、chat 适配器（7）、Provider 机制、认证、模型目录、图片/嵌入 | Bedrock/Vertex 等供应商、constrained sampling |
| agent-core | ~92% | harness 循环、11 hooks、工具系统、压缩（LLM 摘要已接线）、存储契约、JSONL/Memory | branch-summarization、image 工具 |
| session-backend-sqlite | ~90% | repository/storage/lease/branch cache/FTS5 搜索 | 并发压测 |
| tui | ~90% | 会话界面、输入/滚动/主题/Markdown（代码高亮已接入）/语法高亮/diff、编辑器 undo/kill-ring | 渲染逐文件（设计决策）、latex |
| coding-agent | ~90% | CLI、三模式、会话/RPC/技能/扩展/HTML 导出/子命令、prompt 模板 | auto-update、auto-format、interactive 逐文件（设计决策） |
| protocol | ~90% | CBOR 编解码、增量分帧、信封/命令/事件 | 细粒度控制命令 |
| client | ~90% | 远程会话客户端 | 控制面深度 |
| server | ~90% | 会话控制面、租约、快照订阅 | 细粒度控制命令 |
| evals | ~90% | conformance/smoke/extension | 完整测试矩阵 |

> **整体功能完成度 ~92%**：核心用户路径（对话、工具、会话、持久化、RPC、远程会话、图片/嵌入）全部对齐。差异集中在三块**结构性取舍**：① 交互终端全栈用 TamboUI 库封装（行为对齐，渲染层不逐文件复刻）；② 外围能力（auto-update/prompt-templates/constrained-sampling 等）明确不实现；③ 供应商清单聚焦中国大陆（16 chat + 1 image，非 pi 全量 40）。
>
> **<80% 条目优化**：原 11 个 <80% 条目全部闭环——6 个缺口已实施（ModelInfo 字段、Markdown 高亮、LLM 压缩摘要、EditTool fuzzy/diff/队列、OAuth 逐 provider + device-code、编辑器 undo/kill-ring）、2 个口径修正（SkillManager/SessionListScreen）、3 个重新定性为设计决策——详见 `13-phase6-alignment-improvements.md`。

## 12. 已知差异清单（按模块）

> 2026-08-22 更新：Phase 6 已完成，下表仅列**仍存的差异**。

| 模块 | 未对齐项 | 计划 |
|------|---------|------|
| ai | 剩余 pi 供应商适配器（Bedrock/Vertex/OpenRouter chat 等，pi-java 聚焦中国大陆 16 个）、constrained sampling、ModelInfo compat 字段（per-protocol 类型） | 按需 / 渐进 / 分期 |
| telemetry | memory 后端、conformance 套件 | 按需 |
| agent-core | harness 富记录字段逐步填充（resultEntryId/effectiveArgs/usage.cause）、branch-summarization、image 工具 | 渐进 |
| session-backend-sqlite | 多进程读并发压测、JSONL 扫描式搜索（按需） | 后续 |
| tui | 渲染层为 TamboUI 封装（设计决策）、Markdown latex | 渐进 |
| coding-agent | Bun 运行时（pi-java 为 JVM）、自动更新、TUI 图片预览、interactive 模式未逐文件移植 | 按需 / 不适用 |
| protocol / client / server | `session`/`process` 细粒度控制（pi 有更多命令），已实现核心控制面 | 渐进 |
| evals | 对照 pi evals 的完整测试矩阵 | 渐进 |
