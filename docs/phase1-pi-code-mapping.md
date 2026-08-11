# Phase 1 代码对照表（pi-java ↔ pi TypeScript）

> pi 源码位置：`D:\workplaceForai\pi\packages\ai\src\`
> pi-java 源码位置：`pi-java-ai\src\main\java\com\pijava\ai\`

## 1. API 接口层（`api/` ↔ `types.ts` + `compat.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `api/StreamApi.java` | `types.ts` `StreamFunction` 类型（L320） | ✅ 100% | Java 用 interface + `Flow.Publisher`；pi 用函数签名 `(model,context,options) => EventStream` |
| `api/SimpleApi.java` | `compat.ts` `streamSimple()` 包装 | ✅ 90% | pi 的 `streamSimple` 含 ThinkingLevel 自动翻译，Phase 1 未实现 |
| `api/ChatApi.java` | 无直接对应 | ✅ 100% | Java 特有：`StreamApi + SimpleApi` 组合接口，实现 `ProviderApi` 标记 |
| `api/StreamRequest.java` | `types.ts` `Context` + `Model` 参数组合 | ✅ 100% | pi 不打包成单个 request 对象，直接传三个参数 |
| `api/StreamIterator.java` | `utils/event-stream.ts` `EventStream<T,R>` | ✅ 90% | pi 用 async iterator + backpressure queue；Java 用 `Iterator` + 虚拟线程阻塞 |
| `api/ApiOptions.java` | `types.ts` options 泛型参数 `TOptions` | ✅ 80% | pi 的 options 是 per-API 的泛型（如 `AnthropicOptions`）；Java 用统一 record |
| `api/ToolDefinition.java` | `types.ts` `ToolDefinition`（函数声明 + JSON Schema） | ✅ 100% | 结构完全一致 |
| `api/ProviderApi.java` | `types.ts` `Api` 字符串字面量类型 | ✅ 100% | pi 用 tagged string；Java 用 `sealed interface` |

## 2. 协议适配器（`protocol/` ↔ `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `protocol/AnthropicMessagesApi.java` | `api/anthropic-messages.ts`（~1200 行）| ✅ 90% | **核心差异**：pi 用原始 SSE 解析（`PiHttpClient` 等价物），Java 用官方 SDK `anthropic-java`。消息转换逻辑一致 |
| `protocol/OpenAICompletionsApi.java` | `api/openai-completions.ts`（~600 行）| ✅ 90% | 都用 SDK。pi 的工具调用聚合在适配器内联；Java 有独立的 `ToolCallBuilder` |
| `protocol/GoogleGenerativeAiApi.java` | `api/google-generative-ai.ts`（~500 行）| ✅ 95% | 共用 `google-shared.ts` 的工具转换；Java 独自分装了 `toGoogleContents()`/`toGoogleParts()` |
| `protocol/MistralConversationsApi.java` | `api/mistral-conversations.ts`（~200 行）| ✅ 95% | 都用原始 HTTP + SSE。pi 的 JSON 构建和解析逻辑与 Java 基本一一对应 |
| `protocol/AiQueueStreamIterator.java` | `utils/event-stream.ts` `EventStream` | ✅ 80% | pi 的 `EventStream` 更完善：backpressure、abort signal、`EventStreamSignal` |
| `protocol/QueueStreamIterator.java` | 同上 | ✅ 80% | 从 Mistral/Google 适配器提取的共享实现 |

**pi 有、Phase 1 没有的适配器：**

| pi 适配器 | pi 文件名 | 状态 |
|-----------|----------|------|
| Bedrock Converse | `api/bedrock-converse-stream.ts`（~1173 行）| Phase 6 |
| Google Vertex AI | `api/google-vertex.ts`（~592 行）| Phase 6 |
| OpenAI Responses | `api/openai-responses.ts`（~800 行）| Phase 6（/v1/responses 新 API） |
| OpenAI Codex Responses | `api/openai-codex-responses.ts` | Phase 6 |
| Azure OpenAI Responses | `api/azure-openai-responses.ts` | Phase 6 |
| Pi Messages (Radius) | `api/pi-messages.ts` | Phase 6（wire protocol） |
| Cloudflare Workers AI | `api/cloudflare.ts` | Phase 6 |
| OpenRouter Images | `api/openrouter-images.ts` | Phase 6 |

## 3. 消息与流事件（`message/` + `stream/` ↔ `types.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `message/Message.java` | `types.ts` `Message = UserMessage \| AssistantMessage \| ToolResultMessage`（L455）| ✅ 100% | 同样的 sealed hierarchy：System/User/Assistant |
| `message/ContentBlock.java` | `types.ts` content block 类型（TextContent, ImageContent, ToolUseContent, ToolResultContent）| ✅ 100% | 字段名有小差异：pi 用 `toolUseId`，Java 用 `toolUseId` 一致 |
| `stream/StreamEvent.java` | `types.ts` `AssistantMessageEvent`（12 种子类型，L523）| ⚠️ 60% | **差距最大**。Java 7 种 → pi 12 种。缺 `start`、`text_start/end`、`thinking_start/end`、`partial` 字段。见 `memory/phase1-stream-event-gaps.md` |
| `stream/ToolCallBuilder.java` | 无独立文件 | ✅ 100% | pi 的 OpenAI/Mistral 适配器内联了同样的聚合逻辑，Java 提取为共享类 |

## 4. Provider 配置（`provider/` ↔ `providers/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `provider/Provider.java` | `models.ts` `Provider` interface（~15 字段）| ✅ 80% | pi 的 Provider 更复杂：含 `api`、`models`、`thinkingLevelMap`、`auth`、`baseUrl` 等 |
| `provider/ProviderFactory.java` | `models.ts` `createProvider()` 工厂函数 | ✅ 100% | pi 用函数，Java 用 SPI 接口 |
| `provider/ProviderRegistry.java` | `compat.ts` api-registry + `providers/all.ts` `builtinProviders()` | ✅ 90% | 都支持手动注册 + 全局查询 |
| `provider/AnthropicProvider.java` | `providers/anthropic.ts` | ✅ 100% | 名字、baseUrl、绑定适配器逻辑一致 |
| `provider/OpenAIProvider.java` | `providers/openai.ts` | ✅ 100% | |
| `provider/GoogleProvider.java` | `providers/google.ts` | ✅ 100% | |
| `provider/DeepSeekProvider.java` | `providers/deepseek.ts` | ✅ 100% | 都复用 OpenAI 适配器 |
| `provider/MistralProvider.java` | `providers/mistral.ts` | ✅ 100% | |
| `provider/FauxProvider.java` | `providers/faux.ts` | ✅ 100% | 三种回放模式完全对齐 |

## 5. 模型目录（`catalog/` ↔ `model-catalog.ts` + `providers/*.models.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `model/ModelId.java` | `model-catalog.ts` `ModelId` 类型（L5）| ✅ 100% | pi 用 `{provider, modelId}` 对象；Java 用 record |
| `model/ModelInfo.java` | `types.ts` `Model<TApi>` interface（L794）| ✅ 70% | pi 的 Model 更复杂：含 `thinkingLevelMap`、`compat`、`headers`、`samplingParams` 等。Phase 1 只实现了核心字段 |
| `model/ModelCapability.java` | `types.ts` capability tags | ✅ 100% | |
| `model/PricingInfo.java` | `types.ts` `ModelCost` + `ModelCostRates`（L776-791）| ✅ 90% | pi 分 input/output/cacheRead/cacheWrite 四种单价；Phase 1 只分了 input/output |
| `catalog/ModelCatalog.java` | `model-catalog.ts` `ModelCatalog` type | ✅ 90% | |
| `catalog/BuiltinCatalog.java` | `providers/*.models.ts`（每供应商一个，自动生成）| ✅ 80% | pi 的模型数据是自动生成的（`npm run generate-models`），含更多字段。Phase 1 手写了 5 个供应商的数据 |
| `catalog/ModelsStore.java` | `models-store.ts` `ModelsStoreEntry` | ✅ 80% | Phase 1 内存实现，Phase 4 切 SQLite |

## 6. 认证（`auth/` ↔ `auth/` + `env-api-keys.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `auth/CredentialStore.java` | `auth/credential-store.ts` `CredentialStore` interface | ✅ 100% | resolveApiKey / storeApiKey / deleteApiKey 三方法一致 |
| `auth/EnvApiKeyResolver.java` | `env-api-keys.ts` | ✅ 100% | 环境变量映射表一致 |
| `auth/FileCredentialStore.java` | `auth/credential-store.ts` `InMemoryCredentialStore` + `cli.ts`（auth.json 读写）| ✅ 90% | pi 用内存 store + Node fs 持久化；Java 用 `FileChannel.lock()` 支持跨进程并发 |
| — | `auth/resolve.ts` `resolveProviderAuth()` | Phase 2 | 完整认证解析管线（优先级、abort signal） |
| — | `auth/oauth/`（7 个 OAuth flow） | Phase 6 | 设计文档明确延后 |
| — | `auth/context.ts` `AuthContext` | Phase 2 | 注入式认证上下文 |

## 7. HTTP 传输（`http/` ↔ `utils/` + `api/`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `http/PiHttpClient.java` | `utils/event-stream.ts` SSE 解析 + `api/anthropic-messages.ts` 原始 SSE 处理 | ✅ 90% | pi 的 SSE 解析分散在各适配器内部；Java 集中封装 |
| `http/RetryPolicy.java` | `utils/retry.ts` + `utils/provider-retry.ts` | ✅ 90% | 指数退避、Retry-After 解析逻辑一致 |
| `http/PiHttpException.java` | `utils/error-body.ts` `formatProviderError()` | ✅ 100% | HTTP 错误码 → 异常 |

## 8. CLI（`cli/` ↔ `cli.ts`）

| pi-java | pi (TypeScript) | 对齐度 | 差异说明 |
|---------|-----------------|--------|---------|
| `cli/AiCli.java` | `cli.ts`（~150 行）| ✅ 90% | 都是独立命令行入口。命令集相同：list-models、auth、ping。pi 还支持 OAuth login |

## 9. pi 有、pi-java 总体规划但不在 Phase 1 的

| pi 文件 | 功能 | 计划阶段 |
|---------|------|---------|
| `compat.ts`（api-registry 部分）| 全局 API 注册表 | Phase 1 ✅（ProviderRegistry） |
| `compat.ts`（stream/complete）| 统一调用入口 | Phase 1 ✅（ChatApi） |
| `models.ts` `createModels()` | 模型发布管线（auth 解析 + 远程刷新） | Phase 2（auth）+ Phase 6（remote refresh） |
| `models.ts` `calculateCost()` | Token 成本计算 | Phase 2 |
| `models.ts` `clampThinkingLevel()` | 思考级别回退 | Phase 2 |
| `models.generated.ts` | 自动生成的模型数据 | Phase 1 ⚠️（手写代替） |
| `models-store.ts` | 远程目录持久化 | Phase 4（SQLite） |
| `api/lazy.ts` `lazyStream` | 懒加载翻译器 | Phase 6 |
| `api/google-shared.ts` | Google 共享工具转换 | Phase 1 ✅（内联在 GoogleGenerativeAiApi） |
| `api/constrained-sampling.ts` | 语法约束采样 | Phase 6 |
| `api/simple-options.ts` | 估计上下文 token 数 | Phase 2 |
| `utils/overflow.ts` `isContextOverflow()` | 上下文溢出检测 | Phase 2 |
| `utils/node-http-proxy.ts` | HTTP 代理 | Phase 2 |
| `utils/abort.ts` `operationSignal` | 可取消操作 | Phase 2 |
| `session-resources.ts` | 会话级资源清理 | Phase 4 |
| `images*.ts`（4 文件）| 图片生成 | Phase 6 |
| `bun-oauth.ts` | Bun OAuth 注册 | Phase 6 |
| `bedrock-provider.ts` | Bedrock 入口 | Phase 6 |
| `legacy-api-aliases.ts` | 旧 API 别名（兼容） | 不做 |

## 统计汇总

| 分类 | pi TS 文件数 | pi-java 文件数 | 对齐度 |
|------|-------------|--------------|--------|
| API 接口 | ~8（types.ts 类型） | 7 | 90% |
| 协议适配器 | 9 + 1 images | 4 + 共享类 | ✅ Phase 1 目标 4 个 |
| 消息/事件 | ~15 类型定义 | 3 | 60%（事件种类差 5 种） |
| Provider | 35+ 配置文件 | 7 | 100%（Phase 1 5 个） |
| 模型目录 | 87（含自动生成） | 4 | 80% |
| 认证 | 15 | 3 | 70%（OAuth 延后） |
| HTTP/工具 | 20 | 3 | 55%（4 项延后 Phase 2：溢出检测、token 估算、abort signal、provider 重试） |
| CLI | 1 | 1 | 90% |
| **总计** | **~173** | **~32** | — |

## 10. pi `utils/` 对照（Phase 2 补充）

| pi 工具 | 功能 | Phase 1 | Phase 2 |
|---------|------|---------|---------|
| `utils/event-stream.ts` | SSE backpressure queue | ✅ `PiHttpClient.SseIterator` | — |
| `utils/retry.ts` | 通用重试 | ✅ `RetryPolicy` | — |
| `utils/error-body.ts` | 错误体提取 | ✅ `PiHttpException` | — |
| `utils/headers.ts` | 请求头构造 | ✅ 内联 | — |
| `utils/provider-env.ts` | 环境变量读取 | ✅ `EnvApiKeyResolver` | — |
| `utils/sanitize-unicode.ts` | Unicode 清洗 | ✅ Java String 内置 | — |
| `utils/overflow.ts` | 上下文溢出检测 | ❌ | P2-13 |
| `utils/estimate.ts` | Token 估算 | ❌ | P2-13 |
| `utils/abort.ts` | 操作取消 | ❌ | P2-16 |
| `utils/provider-retry.ts` | Provider 特定重试 | ❌ | P2-17 |
| `utils/node-http-proxy.ts` | HTTP 代理 | ❌ | P2-16 |
| `utils/hash.ts` | 哈希 | ❌ | Phase 4 |
| `utils/uuid.ts` | UUID | ❌ | Phase 4 |
