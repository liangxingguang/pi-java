# Phase 1-4 代码对照表（pi-java ↔ pi TypeScript）

> pi 源码位置：`D:\workplaceForai\pi\packages\`
> pi-java 源码位置：各模块 `src/main/java/` 下 `com.pijava.*`
> 更新日期：2026-08-16（由原 Phase 1 对照表扩展至 Phase 0-4 全模块）

## 0. 范围与方法

- **覆盖范围**：pi 10 个包中已实现的 6 个（`telemetry`、`ai`、`agent`、`session-backends/sqlite-node`、`tui`、`coding-agent`）逐项对照；未实现的 4 个（`protocol`、`client`、`server`、`evals`）以「Phase 6 存根」形式列入。
- **粒度约定（混合）**：小模块（telemetry、sqlite-node、存储契约）逐文件；`agent-core` 按 harness/tool/session/hook/compaction 分组逐文件；大模块（tui、coding-agent）包级汇总 + 关键文件单列。
- **对齐度判据**：≥95% 完全对齐（接口/行为逐项一致）；80–94% 主要对齐（结构一致，存在已记录的差异）；<80% 部分对齐（核心流程对齐但能力面缺失）。
- **旧表修正标注**：原 Phase 1 表中结论若已被后续阶段更新，保留原表并以「⚠️ 已于 Phase N 更新」标注修正。

---

## 1. Phase 1 — pi-java-ai ↔ packages/ai

> pi-java 源码位置：`pi-java-ai\src\main\java\com\pijava\ai\`

### 1.1 API 接口层（`api/` ↔ `types.ts` + `compat.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `api/StreamApi.java` | `types.ts` `StreamFunction` 类型（L320） | ✅ 100% | Java 用 interface + `Flow.Publisher`；pi 用函数签名 `(model,context,options) => EventStream` |
| `api/SimpleApi.java` | `compat.ts` `streamSimple()` 包装 | ✅ 95% | ⚠️ 已于 Phase 2a 更新：ThinkingLevel 自动翻译已由 agent-core harness 承担 |
| `api/ChatApi.java` | 无直接对应 | ✅ 100% | Java 特有：`StreamApi + SimpleApi` 组合接口，实现 `ProviderApi` 标记 |
| `api/StreamRequest.java` | `types.ts` `Context` + `Model` 参数组合 | ✅ 100% | pi 不打包成单个 request 对象，直接传三个参数 |
| `api/StreamIterator.java` | `utils/event-stream.ts` `EventStream<T,R>` | ✅ 90% | pi 用 async iterator + backpressure queue；Java 用 `Iterator` + 虚拟线程阻塞 |
| `api/ApiOptions.java` | `types.ts` options 泛型参数 `TOptions` | ✅ 80% | pi 的 options 是 per-API 的泛型（如 `AnthropicOptions`）；Java 用统一 record |
| `api/ToolDefinition.java` | `types.ts` `ToolDefinition`（函数声明 + JSON Schema） | ✅ 80% | pi 含 `renderCall`/`renderResult`/`promptSnippet` 渲染字段；Java 仅 name/description/inputSchema（渲染字段待补） |
| `api/ProviderApi.java` | `types.ts` `Api` 字符串字面量类型 | ✅ 100% | pi 用 tagged string；Java 用 `sealed interface` |

### 1.2 协议适配器（`protocol/` ↔ `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `protocol/AnthropicMessagesApi.java` | `api/anthropic-messages.ts`（~1200 行） | ✅ 90% | **核心差异**：pi 用原始 SSE 解析，Java 用官方 SDK `anthropic-java`；消息转换逻辑一致 |
| `protocol/OpenAICompletionsApi.java` | `api/openai-completions.ts`（~600 行） | ✅ 90% | 都用 SDK。pi 的工具调用聚合在适配器内联；Java 有独立的 `ToolCallBuilder` |
| `protocol/GoogleGenerativeAiApi.java` | `api/google-generative-ai.ts`（~500 行） | ✅ 95% | 共用 `google-shared.ts` 的工具转换；Java 独自分装了 `toGoogleContents()`/`toGoogleParts()` |
| `protocol/MistralConversationsApi.java` | `api/mistral-conversations.ts`（~200 行） | ✅ 95% | 都用原始 HTTP + SSE。JSON 构建和解析逻辑一一对应 |
| `protocol/QueueStreamIterator.java` | `utils/event-stream.ts` `EventStream` | ✅ 80% | pi 的 `EventStream` 更完善：backpressure、abort signal、`EventStreamSignal` |
| `protocol/ToolCallAccumulator.java` | 无独立文件 | ✅ 100% | pi 的 OpenAI/Mistral 适配器内联了同样的聚合逻辑，Java 提取为共享类 |

**pi 有、Phase 1-4 没有的适配器：**

| pi 适配器 | pi 文件名 | 状态 |
|-----------|----------|------|
| Bedrock Converse | `api/bedrock-converse-stream.ts`（~1173 行） | Phase 6 |
| Google Vertex AI | `api/google-vertex.ts`（~592 行） | Phase 6 |
| OpenAI Responses | `api/openai-responses.ts`（~800 行） | Phase 6（/v1/responses 新 API） |
| OpenAI Codex Responses | `api/openai-codex-responses.ts` | Phase 6 |
| Azure OpenAI Responses | `api/azure-openai-responses.ts` | Phase 6 |
| Pi Messages (Radius) | `api/pi-messages.ts` | Phase 6（wire protocol） |
| Cloudflare Workers AI | `api/cloudflare.ts` | Phase 6 |
| OpenRouter Images | `api/openrouter-images.ts` | Phase 6 |

### 1.3 消息与流事件（`message/` + `stream/` ↔ `types.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `message/Message.java` | `types.ts` `Message` 联合（L455） | ✅ 100% | sealed hierarchy：System/User/Assistant/ToolResult |
| `message/ContentBlock.java` | `types.ts` content block 类型 | ✅ 100% | Text/Thinking/Image/ToolUse/ToolResult 五类一致 |
| `stream/StreamEvent.java` | `types.ts` `AssistantMessageEvent`（13 种子类型，L523） | ✅ 95% | ⚠️ 已于 Phase 2a 更新：原表「缺 start/text_start/thinking_start/partial」已补齐为 13 种事件（Start/Text*/Thinking*/ToolCall*/UsageInfo/StreamDone/StreamError），每种携带 `partial` 快照 |
| `stream/StreamPartialBuilder.java` | `utils/event-stream.ts` 内聚 | ✅ 90% | 事件增量 → `AssistantMessage` 快照构建 |

### 1.4 Provider 配置（`provider/` ↔ `providers/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `provider/Provider.java` | `models.ts` `Provider` interface（~15 字段） | ✅ 80% | pi 更复杂：含 `api`、`models`、`thinkingLevelMap`、`auth`、`baseUrl` 等 |
| `provider/ProviderFactory.java` | `models.ts` `createProvider()` 工厂函数 | ✅ 100% | pi 用函数，Java 用 SPI 接口 |
| `provider/ProviderRegistry.java` | `compat.ts` api-registry + `providers/all.ts` `builtinProviders()` | ✅ 90% | 都支持手动注册 + 全局查询 |
| `provider/AnthropicProvider.java` | `providers/anthropic.ts` | ✅ 100% | 名字、baseUrl、绑定适配器逻辑一致 |
| `provider/OpenAIProvider.java` | `providers/openai.ts` | ✅ 100% | |
| `provider/GoogleProvider.java` | `providers/google.ts` | ✅ 100% | |
| `provider/DeepSeekProvider.java` | `providers/deepseek.ts` | ✅ 100% | 都复用 OpenAI 适配器 |
| `provider/MistralProvider.java` | `providers/mistral.ts` | ✅ 100% | |
| `provider/FauxProvider.java` | `providers/faux.ts` | ✅ 100% | 三种回放模式完全对齐 |

### 1.5 模型目录（`catalog/` + `model/` ↔ `model-catalog.ts` + `providers/*.models.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `model/ModelId.java` | `model-catalog.ts` `ModelId` 类型 | ✅ 100% | pi 用 `{provider, modelId}` 对象；Java 用 record |
| `model/ModelInfo.java` | `types.ts` `Model<TApi>` interface（L794） | ✅ 70% | pi 含 `thinkingLevelMap`、`compat`、`headers`、`samplingParams` 等，Java 只实现核心字段 |
| `model/ModelCapability.java` | `types.ts` capability tags | ✅ 100% | |
| `model/PricingInfo.java` | `types.ts` `ModelCost` + `ModelCostRates` | ✅ 90% | pi 分 input/output/cacheRead/cacheWrite 四种单价；Java 只分 input/output |
| `catalog/ModelCatalog.java` | `model-catalog.ts` `ModelCatalog` type | ✅ 90% | |
| `catalog/BuiltinCatalog.java` | `providers/*.models.ts`（自动生成） | ✅ 80% | pi 模型数据自动生成（`npm run generate-models`）；Java 手写 5 个供应商数据 |
| ~~`catalog/ModelsStore.java`~~ | `models-store.ts` `ModelsStoreEntry` | — | ⚠️ 已于 Phase 4 更新：该行已移除——远程目录持久化未采用，会话存储改由 JSONL/SQLite 双轨承担 |

### 1.6 认证（`auth/` ↔ `auth/` + `env-api-keys.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `auth/CredentialStore.java` | `auth/credential-store.ts` `CredentialStore` interface | ✅ 100% | resolveApiKey / storeApiKey / deleteApiKey 三方法一致 |
| `auth/EnvApiKeyResolver.java` | `env-api-keys.ts` | ✅ 100% | 环境变量映射表一致 |
| `auth/FileCredentialStore.java` | `auth/credential-store.ts` `InMemoryCredentialStore` + `cli.ts`（auth.json 读写） | ✅ 90% | pi 用内存 store + Node fs；Java 用 `FileChannel.lock()` 支持跨进程并发 |
| — | `auth/resolve.ts` `resolveProviderAuth()` | ✅ 90% | ⚠️ 已于 Phase 3 更新：完整认证解析管线由 coding-agent `DefaultProviders.resolveProviderName` + `AuthCommand` 承担 |
| — | `auth/context.ts` `AuthContext` | ✅ 90% | ⚠️ 已于 Phase 3 更新：`ToolContext` + `ProviderRegistry` 承载注入式配置 |
| — | `auth/oauth/`（7 个 OAuth flow） | Phase 6 | 设计文档明确延后 |

### 1.7 HTTP 传输（`http/` ↔ `utils/` + `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `http/PiHttpClient.java` | `utils/event-stream.ts` SSE 解析 + 各适配器原始 SSE 处理 | ✅ 90% | pi 的 SSE 解析分散在适配器内部；Java 集中封装 |
| `http/RetryPolicy.java` | `utils/retry.ts` + `utils/provider-retry.ts` | ✅ 90% | 指数退避、Retry-After 解析一致 |
| `http/PiHttpException.java` | `utils/error-body.ts` `formatProviderError()` | ✅ 100% | HTTP 错误码 → 异常 |
| `http/ProxyDetector.java` | `utils/node-http-proxy.ts` | ✅ 90% | ⚠️ 已于 Phase 2 更新：系统代理检测（HTTP_PROXY/HTTPS_PROXY）已落地 |

### 1.8 CLI（`cli/` ↔ `cli.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `cli/AiCli.java` | `cli.ts`（~150 行） | ✅ 90% | 独立命令行入口；命令集相同：list-models、auth、ping；pi 还支持 OAuth login |

### 1.9 Phase 1 未落地项的状态更新

| pi 文件 | 功能 | 状态（更新后） |
|---------|------|---------|
| `compat.ts`（api-registry 部分） | 全局 API 注册表 | ✅ Phase 1（ProviderRegistry） |
| `compat.ts`（stream/complete） | 统一调用入口 | ✅ Phase 1（ChatApi） |
| `models.ts` `createModels()` | 模型发布管线 | ✅ 已由 coding-agent `DefaultModelResolver` + `BuiltinCatalog` 承担（Phase 3） |
| `models.ts` `calculateCost()` | Token 成本计算 | ✅ 已由 `PricingInfo` + usage 统计承担（Phase 4 累计 costTotal） |
| `models.ts` `clampThinkingLevel()` | 思考级别回退 | ✅ 已由 `ThinkingLevels.parse` + `ModelThinkingLevel` 承担（Phase 3） |
| `models.generated.ts` | 自动生成的模型数据 | ⚠️ 手写代替（Phase 1 结论保留） |
| `models-store.ts` | 远程目录持久化 | ⚠️ 已于 Phase 4 更新：未采用（见 1.5 ModelsStore 行） |
| `api/lazy.ts` `lazyStream` | 懒加载翻译器 | Phase 6 |
| `api/constrained-sampling.ts` | 语法约束采样 | Phase 6 |
| `api/simple-options.ts` | 估计上下文 token 数 | ✅ 已由 agent-core `ContextEstimator` 承担（Phase 2c） |
| `utils/overflow.ts` `isContextOverflow()` | 上下文溢出检测 | ✅ 已由 agent-core `OverflowDetector` 承担（Phase 2c） |
| `utils/abort.ts` `operationSignal` | 可取消操作 | ✅ 已由 ai `AbortSignal` 承担（Phase 2a） |
| `session-resources.ts` | 会话级资源清理 | ✅ 已由 coding-agent `AgentSession.close`/`SessionPersistence` 承担（Phase 4） |
| `images*.ts`（4 文件） | 图片生成 | Phase 6 |
| `bun-oauth.ts` | Bun OAuth 注册 | Phase 6 |
| `bedrock-provider.ts` | Bedrock 入口 | Phase 6 |
| `legacy-api-aliases.ts` | 旧 API 别名（兼容） | 不做 |

## 2. Phase 0 — pi-java-telemetry ↔ packages/telemetry

> pi-java 源码位置：`pi-java-telemetry\src\main\java\com\pijava\telemetry\`（4 文件）；pi：6 文件 / 826 行。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `TelemetryContext.java` | `index.ts`（TelemetryContext 类型） | ✅ 90% | 采样/父上下文语义一致；pi 还有 in-memory 与 testing 实现，Java 只有 Noop |
| `TelemetrySpan.java` | `index.ts` `TelemetrySpan` | ✅ 90% | `addAttribute`/`end` 一致；pi 支持 `addLink`/`event`，Java 未实现 |
| `SpanOptions.java` | `index.ts` `SpanOptions` | ✅ 95% | 字段对应（name/type/attributes/severity）；Java 用 record |
| `NoopTelemetryContext.java` | `noop.ts` | ✅ 100% | 空实现逐方法一致 |
| — | `memory.ts` | Phase 6 | 内存遥测后端（测试用） |
| — | `testing/`（types/index/conformance） | Phase 6 | 遥测 conformance 套件 |
| — | OTel 适配器 | Phase 6 | F18（`01-requirements-analysis.md`），当前由 Noop 占位 |

**统计**：pi 6 文件 / 826 行 ↔ pi-java 4 文件 / 91 行，整体对齐度 ~85%（接口对齐，能力面缺 memory/testing/OTel）。

---

## 3. Phase 2a/2b/2c — pi-java-agent-core ↔ packages/agent

> pi-java 源码位置：`pi-java-agent-core\src\main\java\com\pijava\agent\`；pi：49 文件 / 11332 行。

### 3.1 Harness 与循环（`harness/` + `loop/` ↔ `harness/` + `agent-loop.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `harness/AgentHarness.java` | `harness/agent-harness.ts`（~900 行） | ✅ 90% | 多车道、`peekAction`/`executeAction`/`runToCompletion`、watch 订阅一致；pi 另有 `before_resume` 恢复语义，Java 简化为 `seedTranscript` |
| `harness/ActionExecutor.java` | `harness/agent-harness.ts` 内 action 分派 | ✅ 85% | StreamAssistant/AppendEntry/TryFinishRun/ExecuteTool(Batch) 五类 action 对应；pi 的 action 集合更细（含 navigation/steer 注入） |
| `harness/Action.java` | `harness/agent-harness.ts` `Action` 联合 | ✅ 85% | 五种 action 一致；pi 还区分 pending write 的 lane |
| `harness/HarnessUtils.java` | `harness/types.ts` + 工具函数 | ✅ 90% | `newestOwn`/`determineOutcome`/`extractToolCalls` 与 pi 语义一致 |
| `harness/LaneState.java` | `harness/types.ts` `LaneState` | ✅ 85% | transcript/pendingWrites/queue 三队列一致；Java 保留 Phase 2 简化的部分字段 |
| `harness/LaneSnapshot.java` + `SnapshotService.java` | `harness/events.ts` 快照 + `harness/types.ts` | ✅ 90% | `watch()` → `WatchHandle<LaneSnapshot>` 对应 pi `watch()`；Java 同步回调 vs pi 异步迭代 |
| `harness/QueueManager.java` | `harness/agent-harness.ts` steer/followUp/nextRun | ✅ 90% | 三队列 drain 顺序（steer → nextRun → followUp）与 pi 一致 |
| `harness/DriveMode.java` | `harness/agent-harness.ts` drive modes | ✅ 90% | Manual/Automatic 对应 pi 手动驱动与自动完成 |
| `loop/AgentLoop.java` | `agent-loop.ts` | ✅ 90% | 提交 → 流式 → 工具 → 完成循环一致 |
| `harness/ToolExecutionPipeline.java` | `harness/agent-harness.ts` 工具执行阶段 | ✅ 80% | before_tool/after_tool 钩子 + 串行/并行执行；pi 的 `ToolExecution` 语义更细 |
| `harness/StreamFn.java` | `stream-fn.ts` `StreamFunction` | ✅ 95% | 签名对齐（messages/model/options → iterator） |

### 3.2 Hook 系统（`hook/` ↔ `harness/agent-harness.ts` 11 个 hooks）

| pi hook | pi-java 对应 | 对齐度 | 差异说明 |
|---------|-------------|--------|---------|
| `before_run` | `hook/BeforeRunHook.java` + `RunContext` | ✅ 95% | 可改写 originalPrompt |
| `before_resume` | `hook/BeforeResumeHook.java` + `ResumeContext` | ✅ 85% | Java 由恢复流程触发，语义简化 |
| `transform_context` | `hook/TransformContextHook.java` | ✅ 95% | 消息列表变换一致 |
| `before_request` | `hook/BeforeRequestHook.java` + `RequestContext` | ✅ 95% | 注入/改写消息一致 |
| `before_payload` | `hook/BeforePayloadHook.java` | ✅ 90% | 载荷校验/改写一致 |
| `after_response` | `hook/AfterResponseHook.java` + `ResponseContext` | ✅ 95% | usage/stopReason 透传一致 |
| `before_tool` / `after_tool` | `hook/BeforeToolHook.java` / `AfterToolHook.java` | ✅ 95% | 参数改写、结果改写一致 |
| `before_compaction` | `hook/BeforeCompactionHook.java` + `CompactionContext`/`CompactionPlan` | ✅ 90% | 可覆盖压缩计划一致 |
| `before_navigation` | `hook/BeforeNavigationHook.java` + `NavigationContext` | ✅ 85% | 导航钩子已定义，harness 触发路径简化 |
| `before_run_end` | `hook/BeforeRunEndHook.java` + `RunEndContext` | ✅ 95% | 终态判定一致 |
| 调度器 | `hook/HookSystem.java` | ✅ 90% | 注册/触发/非致命异常处理一致；pi 支持异步 hook，Java 同步 |

### 3.3 工具系统（`tool/` ↔ `harness/tools/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `tool/AgentTool.java` | `harness/tools/index.ts` `AgentTool` | ✅ 85% | `prepareArguments`/`executionMode`/`inputSchema` 一致；pi 还含 `ToolUpdateCallback` 增量 |
| `tool/ToolRegistry.java` | `harness/tools/index.ts` 注册表 | ✅ 90% | 注册/查询/definition 导出一致 |
| `tool/ToolExecutor.java` | `harness/agent-harness.ts` 工具执行 | ✅ 80% | 串行/并行批量执行一致；pi 的批量并行走 harness 内 |
| `tool/ToolSetFactory.java` | `harness/tools/index.ts` `createCodingToolDefinitions` | ✅ 85% | coding/readOnly 分组一致（pi 还有 readOnly 工具集定义） |
| `tool/builtin/BashTool.java` | `harness/tools/bash.ts` | ✅ 85% | 参数、超时、输出截断一致；Windows 下 shell 发现逻辑按 pi `shellPath` 语义实现 |
| `tool/builtin/WriteTool.java` | `harness/tools/write.ts` | ✅ 90% | 写文件 + 行校验一致 |
| `tool/builtin/ReadTool.java` | `harness/tools/read.ts` | ✅ 90% | 行范围读取一致 |
| `tool/builtin/EditTool.java` | `harness/tools/edit.ts` + `edit-diff.ts` | ✅ 70% | pi 有完整 edit-diff + `file-mutation-queue`（原子批量变更），Java 为简化实现 |
| `tool/builtin/GrepTool.java` / `GlobTool.java` / `LsTool.java` | 无直接对应（pi 用 grep/find 内联） | — | **pi-java 扩展工具**，非 pi 文件对应 |
| — | `harness/tools/image.ts` | Phase 6 | 图片工具未实现 |
| — | `harness/tools/file-mutation-queue.ts` | Phase 6 | 批量文件变更队列未实现 |
| `tool/ToolContext.java` | `harness/tools/tool-context.ts` | ✅ 90% | cwd/env/shell/fileSystem 注入一致 |
| `tool/PathUtils.java` | `harness/tools/path-utils.ts` | ✅ 95% | 路径安全校验一致 |
| `tool/TruncationUtils.java` | `harness/utils/truncate.ts` | ✅ 95% | 输出截断一致 |
| `tool/DefaultShellExecutor.java` | `harness/env/nodejs.ts` + shell 执行 | ✅ 85% | bash 执行/登录 shell 发现对齐 pi `shellPath` 语义 |

### 3.4 上下文与压缩（`context/` + `compaction/` ↔ `harness/compaction/` + `utils/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `context/OverflowDetector.java` | `utils/overflow.ts` `isContextOverflow()` | ✅ 95% | 触发条件（error/length/token）一致 |
| `context/ContextEstimator.java` | `utils/estimate.ts` | ✅ 85% | chars/4 启发式一致；pi 支持模型特定估算 |
| `compaction/CompactionSettings.java` | `harness/compaction/compaction.ts` `CompactionSettings` | ✅ 95% | enabled/reserveTokens/keepRecentTokens 一致 |
| `compaction/CompactionService.java` | `harness/compaction/compaction.ts` `compact()` | ✅ 75% | cut point（不切 toolResult）一致；`SummaryGenerator` 目前为截断占位，LLM 摘要生成待 Phase 6 |
| `compaction/CompactionResult.java` | `harness/compaction/compaction.ts` `CompactionResult` | ✅ 90% | summary/firstKeptEntryId/tokensBefore/usage 字段一致 |
| — | `harness/compaction/branch-summarization.ts` | Phase 6 | 分支摘要操作未实现 |

### 3.5 技能与提示（`skill/` + `prompt/` ↔ `harness/skills.ts` + `harness/system-prompt.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `skill/SkillManager.java` | `harness/skills.ts` | ✅ 60% | 简化：支持注册/查询，pi 的 Markdown frontmatter 解析与 prompt 模板未完整对齐 |
| `prompt/SystemPromptBuilder.java` | `harness/system-prompt.ts` | ✅ 80% | 基础/工具/技能拼接一致；pi 支持 prompt 模板渲染（`prompt-templates.ts` 未实现） |

**统计**：pi `agent` 包 49 文件 / 11332 行 ↔ pi-java agent-core 146 文件 / 8640 行，整体对齐度 ~85%。

## 4. Phase 4 — 存储契约与 JSONL/Memory 后端（agent-core ↔ packages/agent/src/harness/session/）

> pi-java 源码位置：`pi-java-agent-core\src\main\java\com\pijava\agent\session\`；pi：`packages/agent/src/harness/session/`（13 文件）。

### 4.1 契约层（`session/` ↔ `session/types.ts` + `session.ts` + `state.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `session/SessionStorage.java` | `session/types.ts` `SessionStorage` | ✅ 95% | 方法一一对应；Java 同步化 + `drain()`/`close()`（pi 生命周期表达不同） |
| `session/SessionRepository.java` | `session/types.ts` `SessionRepo` | ✅ 95% | create/open/list/delete/fork 一致；泛型三参对应 options 类型 |
| `session/Session.java` + `SessionTree.java` | `session/session.ts` `Session` + `SessionTree` | ✅ 90% | view(lane)/findEntry(s)/appendMessage 一致 |
| `session/SessionState.java` | `session/state.ts` `SessionState` | ✅ 95% | seq 严格递增、id 唯一、parent 链校验、openOperations、createForkMutations 语义一致 |
| `session/SessionMutation.java` / `LogItem.java` | `session/state.ts` `SessionMutation` / `types.ts` `LogItem` | ✅ 95% | 5 变体对应（entry/record/lane/fact name/fact label） |
| `session/EntryQuery.java` / `RecordQuery.java` / `BranchBounds.java` / `ForkOptions.java` | `session/types.ts` 对应类型 | ✅ 95% | limit/afterSeq 用可空类型表达 pi 的 `undefined`；`ForkOptions.Position` 为 enum（AT/BEFORE） |
| `session/SessionError.java` + `SessionErrorCode.java` | `session/types.ts` `SessionError` + 8 个 code | ✅ 100% | 8 个 snake_case 字面量一致 |
| `session/SessionStats.java` | `session/types.ts` `SessionStats` | ✅ 100% | messageCount/cached/uncached/total/costTotal 一致 |
| `session/SessionSearch.java` + 选项/命中 | `session/search.ts` | ✅ 90% | 契约一致；JSONL 扫描式搜索后端未实现（按需） |

### 4.2 JSONL 后端（`session/jsonl/` ↔ `session/jsonl/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `session/jsonl/JsonlCodec.java` | `session/jsonl/codec.ts`（240 行） | ✅ 95% | header/entry/record/lane/fact 编解码 + syntax/schema 错误分类一致；`parentId` 恒输出 null 键保证字节级兼容 |
| `session/jsonl/JsonlSessionStorage.java` | `session/jsonl/storage.ts` | ✅ 90% | tail 串行写（Java synchronized）、torn-tail 修复、fork 原子发布一致；v3→v4 惰性迁移为 pi-java 扩展 |
| `session/jsonl/JsonlSessionRepository.java` | `session/jsonl/repo.ts` | ✅ 90% | cwd 编码目录、`<ISO>_<id>.jsonl`、activeCreate 防重、导入一致 |
| `session/jsonl/JsonlSessionMetadata.java` 等类型 | `session/jsonl/types.ts` | ✅ 95% | sourceFormat/legacyParentSessionPath 字段一致 |
| `session/jsonl/JsonlSessionRepoFileSystem.java` + `DefaultJsonlFileSystem.java` | `session/jsonl/types.ts` `JsonlSessionRepoFileSystem` | ✅ 90% | 文件系统抽象一致；Java 默认实现用 java.nio |
| `session/memory/MemorySessionStorage.java` + `MemorySessionRepository.java` | `session/memory.ts` | ✅ 95% | conformance oracle 语义一致（同步化） |

### 4.3 数据模型（`entry/` + `record/` ↔ `session/types.ts` Entry/Record 联合）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `entry/Entry.java`（7 子类型） | `session/types.ts` `Entry`（7 类型） | ✅ 95% | 平铺 `id/seq/parentId/timestamp`、`@JsonTypeInfo("type")` 名称一致；`parentId` 根为 null |
| `record/LaneRecord.java`（9 子类型 + Intent） | `session/types.ts` `LaneRecord`（9 类型） | ✅ 95% | OperationStarted.Intent sealed 联合、判别 enum（Outcome/StepKind/UsageCause/ReplayKind/QueueKind）字面量一致 |
| `entry/ProvisionedEntry.java` / `record/NewRecord.java` | `session/types.ts` 类型别名 | ✅ 95% | 表达「缺 seq/parentId/timestamp」写入入参；`@JsonValue` 序列化为内层对象 |
| `ai/Usage.java` | pi-ai `types.ts` `Usage` | ✅ 95% | input/output/cacheRead/cacheWrite/totalTokens/cost.total 一致 |

---

## 5. Phase 4 — pi-java-session-backend-sqlite ↔ packages/session-backends/sqlite-node

> pi-java 源码位置：`pi-java-session-backend-sqlite\src\main\java\com\pijava\session\sqlite\`；pi：18 文件 / 2112 行。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `SqliteSessionRepository.java` | `sqlite/repo.ts`（965 行） | ✅ 85% | create/open/list/delete/fork/repairBranchCache/close 一致；Java 用 ReentrantLock 串行化 + 活跃 storage 集合 |
| `SqliteSessionStorage.java` | `sqlite/repo.ts` 内 `SqliteSessionStorage` | ✅ 85% | 写事务内 renew lease → 变更 → advance sequence 一致；Java 心跳线程 + leaseError 停写 |
| `SqliteDatabase.java` | `sqlite/types.ts` `SqliteDatabase` | ✅ 90% | 薄封装（参数绑定/事务/SAVEPOINT 可重入）；pi 用 node:sqlite，Java 用 sqlite-jdbc |
| `storage/SessionRows.java` | `sqlite/storage/sessions.ts` | ✅ 95% | sessions 行 + LEFT JOIN 最新 name fact 一致 |
| `storage/EntryRows.java` | `sqlite/storage/entries.ts` | ✅ 95% | payload 剥 type/id/seq/parentId/timestamp 一致 |
| `storage/RecordRows.java` | `sqlite/storage/records.ts` | ✅ 95% | run_id/op_kind 列投影、open operation 校验一致 |
| `storage/LaneRows.java` | `sqlite/storage/lanes.ts` | ✅ 95% | leaf 存在性校验、start/finishLaneOperation 一致 |
| `storage/FactRows.java` | `sqlite/storage/facts.ts` | ✅ 95% | latest-wins 索引提示一致 |
| `storage/BranchEntryRows.java` + `BranchTipRows.java` | `sqlite/storage/branch-entries.ts` + `branch-tips.ts` | ✅ 90% | cached query（stop/cursor/type/limit）一致；动态 SQL 为固定片段 + 绑定参数 |
| `storage/SequenceRows.java` / `StatsRows.java` / `WriterLeaseRows.java` | `sqlite/storage/session-sequences.ts` / `session-stats.ts` / `writer-leases.ts` | ✅ 95% | 逐 SQL 对应 |
| `WriterLease.java` | `sqlite/storage/writer-leases.ts` | ✅ 95% | acquire（fence+1 抢占）/renew（三重校验）/release 一致 |
| `BranchCache.java` | `sqlite/branch-cache.ts` | ✅ 90% | 增量维护（新分支/延长/分叉复制）、rebuild、SAVEPOINT 一致 |
| `SqliteSessionSearch.java` | `sqlite/search-backend.ts` | ✅ 90% | FTS5 trigram + bm25 + cwd 过滤一致；snippet 未生成（pi 同样可为空） |
| `Migrations.java` + `resources/sql/001_initial.sql` | `sqlite/migrations.ts` + `migrations/001_initial.sql` | ✅ 95% | 11 表 + migrations 表逐列一致 |
| `SqliteSessionBackendFactory.java` + `META-INF/services` | `sqlite/index.ts` 导出 | ✅ 90% | ServiceLoader 注册等价于 pi 包导出 |

**统计**：pi 18 文件 / 2112 行 ↔ pi-java 22 文件 / 2422 行，整体对齐度 ~90%。

---

## 6. Phase 3 — pi-java-tui ↔ packages/tui

> pi-java 源码位置：`pi-java-tui\src\main\java\com\pijava\tui\`（38 文件）；pi：38 文件 / 14317 行。**渲染层采用 TamboUI 封装而非逐文件复刻 pi 终端原语**，按行为对齐。

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `util/TamboUIAdapter.java` + `util/InlineTuiShell.java` | `tui.ts` + `terminal.ts`（终端抽象/渲染循环） | ✅ 80% | 差量渲染/输入事件经 TamboUI 承载；pi 自研 terminal 原语与 alt-screen 管理 |
| `app/PiTuiApp.java` + `screen/ChatScreen.java` | `tui-main-screen.ts` | ✅ 80% | 会话主界面、输入提交、流式气泡一致；pi 的 buffered/inline 双模式由 Java InlineTuiShell 简化 |
| `util/ScrollConfig.java` + `ScrollInputNormalizer.java` | `terminal.ts`（滚轮/触摸事件归一化） | ✅ 90% | 滚动参数与 Codex CLI 对齐（TUI2 scroll_*） |
| `component/SelectList.java` | `components/select-list.ts` | ✅ 85% | 选择器（会话/模型/设置）一致 |
| `component/MarkdownRenderer.java` | `components/markdown.ts` | ✅ 70% | 基础 Markdown/Mermaid 渲染；pi 支持 latex/代码高亮，Java 简化 |
| `component/EditorComponent.java` + `util/EditorElement.java` | `components/editor.ts` + `editor-component.ts` | ✅ 70% | 多行编辑器/undo/kill-ring 等 pi 编辑器能力未完整复刻 |
| `component/FuzzyMatcher.java` | `fuzzy.ts` | ✅ 85% | 模糊匹配语义一致 |
| `theme/PiTheme.java` | `terminal-colors.ts` | ✅ 85% | 主题色板一致 |
| `util/ScrollbackTranscript.java` | `utils.ts`（滚动缓冲） | ✅ 80% | 滚动历史一致 |
| `app/PiTuiEntryPoint.java` | `index.ts` | ✅ 85% | 入口接线一致 |
| 组件族（`component/` 其余） | `components/`（box/stack/spacer/text/loader 等） | ✅ 70% | 由 TamboUI 组件等价承载，非逐文件 |

**统计**：文件数 38↔38，整体对齐度 ~75%（行为对齐，渲染技术栈不同）。关键差异详解：pi 自研终端渲染/组件库（~1.4 万行），pi-java 选择 TamboUI 封装（~4400 行），功能等价但实现面显著更薄。

---

## 7. Phase 3/4 — pi-java-coding-agent ↔ packages/coding-agent

> pi-java 源码位置：`pi-java-coding-agent\src\main\java\com\pijava\coding\agent\`（50 文件）；pi：199 文件 / 52418 行（含 Bun 环境、extensions、RPC/client/server 等 Phase 6 范围）。**包级汇总 + 关键文件**。

| pi-java 分组 | pi 对应 | 对齐度 | 差异说明 |
|-------------|---------|--------|---------|
| `Main.java` + `cli/ArgsParser.java` + `cli/Args.java` | `cli.ts` + `cli/args.ts` | ✅ 85% | ~40 个参数 + 子命令（auth/config/package/list-models）对齐；pi 另有 install/remove/update、server/client 子命令（Phase 6） |
| `modes/PrintMode.java` | `modes/print-mode.ts` | ✅ 90% | `-p "prompt"` 一次性输出一致 |
| `modes/InteractiveMode.java` | `modes/index.ts` + `cli/startup-ui.ts` | ✅ 80% | 交互循环 + 启动 UI 一致；pi 的 RPC 模式（`modes/rpc/`）未实现（Phase 6） |
| `core/AgentSession.java` + `SessionRunner.java` + `SessionPersistence.java` | `server/create-harness.ts` + `core/` | ✅ 80% | 会话组装/驱动/持久化落盘一致；pi 的 server 化会话（remote-session）未实现 |
| `core/SessionServices.java` | `core/`（DI 容器） | ✅ 80% | settings/trust/providers/models/tools/slash/sessionRepository 七件套一致 |
| `core/SettingsManager.java` + `Settings.java` + 存储 | `config.ts` + `core/settings` | ✅ 85% | 全局/项目分层合并、JSON 边界一致 |
| `core/TrustManager.java` | `cli/project-trust.ts` | ✅ 85% | `~/.pi-java/trust/` 标记文件落盘一致 |
| `core/slash/`（CommandRegistry + 5 个 builtin） | `core/slash-commands` | ✅ 85% | 23 个内置命令覆盖（name/session/fork/clone/tree/new/resume/export/import/trust 等）；pi 的 HTML 导出由 Phase 6 承接 |
| `core/KeybindingsManager.java` | `core/keybindings.ts` | ✅ 90% | 键位定义/覆盖一致 |
| `core/DefaultProviders.java` | `core/model-resolver.ts` | ✅ 85% | provider/model 解析一致 |
| `core/InMemorySessionRepository.java`（测试用） | — | — | Phase 4 后仅测试路径使用；生产走持久化仓库 |
| `subcommand/`（auth/config/package） | `cli/auth-command.ts` 等 | ✅ 80% | 子命令对应；pi 还有 `cli/` 下更多工具（session-picker/file-processor/initial-message 等） |

**pi 有、Phase 1-4 没有的（Phase 6）**：`extensions/`（插件）、`client/`（remote-session/transcript）、`server/`（http-dispatcher/management-http）、`modes/rpc/`（jsonl RPC）、`bun/`（Bun CLI/restore-sandbox）、图片处理工具集（image-*）、HTML 导出（`utils/html.ts`）、自动更新/版本检查。

**统计**：pi 199 文件 / 52418 行 ↔ pi-java 50 文件 / 3585 行，整体对齐度 ~65%（核心用户路径对齐，外围能力面留待 Phase 6）。

---

## 8. Phase 6 存根 — protocol / client / server / evals

| pi-java 模块 | pi 包 | pi 规模 | 状态 |
|-------------|-------|---------|------|
| `pi-java-protocol`（package-info） | `packages/protocol`（8 文件 / 1138 行） | schemas.ts + framing.ts + codec.ts + cbor/（encoder/decoder/options） | Phase 6：消息词汇（ClientHello/Request/Response/EventEnvelope）、命令集、快照/转录模型、CBOR 帧编解码 |
| `pi-java-client`（package-info） | `packages/client`（10 文件 / 1094 行） | 远程客户端 | Phase 6：F22 RPC / F32 Unix Socket |
| `pi-java-server`（package-info） | `packages/server`（17 文件 / 2110 行） | 远程服务端 | Phase 6：RPC 服务端 + 远程会话 |
| `pi-java-evals`（package-info） | `packages/evals`（8 文件 / 1179 行） | 评估框架 | Phase 6：F35 评估/conformance 框架（当前 conformance 套件内嵌于各模块测试） |

---

## 9. 统计汇总

| 模块 | pi 文件 | pi 行数 | pi-java 文件 | pi-java 行数 | 对齐度 |
|------|--------|---------|-------------|-------------|--------|
| telemetry | 6 | 826 | 4 | 91 | ~85% |
| ai | 174 | 20,595 | 52 | 3,696 | ~90% |
| agent | 49 | 11,332 | 146 | 8,640 | ~85% |
| session-backends/sqlite-node | 18 | 2,112 | 22 | 2,422 | ~90% |
| tui | 38 | 14,317 | 38 | 4,429 | ~75% |
| coding-agent | 199 | 52,418 | 50 | 3,585 | ~65% |
| protocol / client / server / evals | 43 | 5,521 | 4（package-info） | ~30 | Phase 6 存根 |
| **总计（已实现）** | **484** | **~101,600** | **312** | **~22,900** | — |

## 10. 已知差异清单（按模块）

| 模块 | 未对齐项 | 计划 |
|------|---------|------|
| ai | 35+ 供应商适配器、OAuth 7 流程、OpenAI Responses、constrained sampling、images | Phase 6 |
| agent-core | harness 富记录字段逐步填充（resultEntryId/effectiveArgs/usage.cause）、branch-summarization、file-mutation-queue/edit-diff 完整语义、skills/prompt 模板、image 工具 | Phase 6 / 渐进 |
| tui | 渲染层为 TamboUI 封装（非逐文件）、编辑器/undo/kill-ring、Markdown 增强（latex/高亮） | Phase 6 / 渐进 |
| coding-agent | HTML 导出渲染器、RPC/client/server、extensions、Bun 环境、图片处理、自动更新 | Phase 6 |
| session-backend-sqlite | 多进程读并发压测、JSONL 扫描式搜索（按需） | 后续 |
| protocol / client / server / evals | 全部 | Phase 6 |