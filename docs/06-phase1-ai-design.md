# Phase 1: LLM API 层 — 阶段设计文档

> **目标**：提供统一的 LLM 调用接口，首批支持 5 个主流 Provider。Protocol-center（协议中心）架构，一个协议一个适配器，供应商差异用配置消除。
> **工时**：2.5 周（含本文档编写 0.5d）
> **输入文档**：`03-detailed-design.md` §1、`04-implementation-plan.md` §3
> **前置阶段**：Phase 0（基础设施就绪，核心接口已定义）

---

## 1. 架构决策：Protocol-Center vs Provider-Center

对齐 pi 原版的协议中心架构：

```
Provider（配置） → Protocol Adapter（实现 ChatApi）→ Vendor SDK / HttpClient
       ↑                    ↑
   只需改 baseUrl      每个协议一个适配器
   和 API key          所有供应商复用
```

### 1.1 Java SDK 选型

| Provider | SDK | Maven 坐标 | 备注 |
|----------|-----|-----------|------|
| Anthropic | 官方 SDK | `com.anthropic:anthropic-java:2.52.0` | 支持流式、工具调用、thinking |
| OpenAI | 官方 SDK | `com.openai:openai-java:4.42.0` | GPT-5、流式 |
| Google Gemini | 官方 SDK | `com.google.genai:google-genai:1.15.0` | 2025-05 GA |
| DeepSeek | 无 | 复用 OpenAI 适配器 | API 兼容，改 baseUrl |
| Mistral | 无独立 SDK | `java.net.http.HttpClient` 直连 | REST API 简洁，标准 SSE |

### 1.2 包结构

```
com.pijava.ai/
├── api/                          ← Phase 0 已有
│   ├── StreamApi.java
│   ├── SimpleApi.java
│   ├── ChatApi.java
│   ├── StreamIterator.java
│   ├── StreamRequest.java
│   ├── ToolDefinition.java
│   └── ApiOptions.java
│
├── protocol/                     ← 🆕 协议适配器
│   ├── AnthropicMessagesApi.java
│   ├── OpenAICompletionsApi.java
│   ├── GoogleGenerativeAiApi.java
│   └── MistralConversationsApi.java
│
├── model/                        ← Phase 0 已有, 追加 PricingInfo
│   ├── ModelId.java
│   ├── ModelInfo.java
│   ├── ModelCapability.java
│   └── PricingInfo.java           ← 🆕
│
├── message/                      ← Phase 0 已有
│   ├── Message.java
│   └── ContentBlock.java
│
├── stream/                       ← Phase 0 已有
│   └── StreamEvent.java
│
├── provider/
│   ├── Provider.java             ← Phase 0 已有
│   ├── ProviderFactory.java      ← 🆕 SPI 注册
│   ├── ProviderRegistry.java     ← 🆕 ServiceLoader 发现
│   ├── AnthropicProvider.java
│   ├── OpenAIProvider.java
│   ├── GoogleProvider.java
│   ├── DeepSeekProvider.java     ← 继承 OpenAI 适配器，改 baseUrl
│   ├── MistralProvider.java
│   └── FauxProvider.java         ← 🆕 可编程假 Provider
│
├── catalog/
│   ├── ModelCatalog.java         ← Phase 0 已有接口
│   ├── BuiltinCatalog.java       ← 🆕 内置目录实现
│   └── ModelsStore.java          ← 🆕 目录持久化抽象
│
├── auth/
│   ├── CredentialStore.java      ← Phase 0 已有接口
│   ├── EnvApiKeyResolver.java    ← 🆕 环境变量解析
│   └── FileCredentialStore.java  ← 🆕 文件存储 ~/.pi-java/auth.json
│
├── http/
│   └── PiHttpClient.java         ← HttpClient 封装（重试、SSE 解析）
│
└── cli/
    └── AiCli.java                ← picocli: list-models, auth, ping
```

### 1.3 BOM 新增依赖

```xml
<anthropic-java.version>2.52.0</anthropic-java.version>
<openai-java.version>4.42.0</openai-java.version>
<google-genai.version>1.15.0</google-genai.version>
```

---

## 2. 协议适配器设计

四个协议适配器实现 `ChatApi` 接口（`StreamApi` + `SimpleApi`）。每个适配器内部用各自的 SDK 调用，对外输出统一的 `StreamEvent`。

### 2.1 AnthropicMessagesApi

**依赖**: `com.anthropic:anthropic-java`

**请求转换 — StreamRequest → SDK MessageCreateParams**:

```
StreamRequest                          SDK MessageCreateParams
══════════════════════════════════     ══════════════════════════════
Message.SystemMessage → system 字段
Message.UserMessage   → user role
  ContentBlock.TextContent       → text block
  ContentBlock.ImageContent      → image block (base64)
  ContentBlock.ToolResultContent → tool_result block
Message.AssistantMessage → assistant role
  ContentBlock.ToolUseContent    → tool_use block

ToolDefinition → SDK Tool
StreamRequest.maxTokens → maxTokens
StreamRequest.temperature → temperature
```

**响应转换 — SDK StreamingMessage 事件 → StreamEvent**:

| SDK 事件 | StreamEvent |
|----------|-------------|
| `content_block_start` (text) | 内部缓冲，不产生事件 |
| `content_block_delta` (text_delta) | `TextDelta(text)` |
| `content_block_start` (tool_use) | `ToolCallStart(id, name)` |
| `input_json_delta` | `ToolCallDelta(id, jsonDelta)` |
| `content_block_stop` | `ToolCallEnd(id, name, arguments)` |
| `message_delta.usage` | `UsageInfo(inputTokens, outputTokens)` |
| `message_stop` | `StreamDone(stopReason, usage)` |
| API/HTTP 异常 | `StreamError(throwable)` |

**关键细节**:
- thinking block 作为 `TextDelta` 发出，标记类型为 thinking
- SDK `createStreaming()` 返回的异步流直接映射到 JDK `Flow.Publisher`
- `streamBlocking()` 内部用 `LinkedBlockingQueue<StreamEvent>` + 虚拟线程桥接

### 2.2 OpenAICompletionsApi

**依赖**: `com.openai:openai-java`

**请求转换**:

| StreamRequest | SDK ChatCompletionCreateParams |
|--------------|-------------------------------|
| `Message.SystemMessage` | `"system"` role |
| `Message.UserMessage` | `"user"` role |
| `Message.AssistantMessage` | `"assistant"` role |
| `ContentBlock.ToolUseContent` | assistant `tool_calls` |
| `ContentBlock.ToolResultContent` | `"tool"` role message |
| `ToolDefinition` | SDK `FunctionDefinition` |

**响应转换（关键：tool_calls delta 聚合）**:

| SDK ChatCompletionChunk | StreamEvent |
|------------------------|-------------|
| `choices[0].delta.content` | `TextDelta(text)` |
| `choices[0].delta.tool_calls[i]` 首次出现 | `ToolCallStart(id, name)` |
| `choices[0].delta.tool_calls[i]` 增量参数 | `ToolCallDelta(id, args)` |
| `finish_reason="tool_calls"` | `ToolCallEnd(id, name, args)` ← 聚合完成 |
| `finish_reason="stop"` | `StreamDone("stop", usage)` |
| 最后一个 chunk 的 usage | `UsageInfo(inputTokens, outputTokens)` |

**聚合状态机**:

```
ToolCallBuilder (per tool_call_id):
   首次 delta → 记录 id + name, 开始累积 arguments (StringBuilder)
   后续 delta → 追加 arguments
   finish_reason="tool_calls" → 构建 ToolCallEnd
```

**DeepSeek 适配**: `DeepSeekProvider` 继承此适配器，仅覆盖 `baseUrl` → `https://api.deepseek.com/v1` 和 API key 环境变量名。

### 2.3 GoogleGenerativeAiApi

**依赖**: `com.google.genai:google-genai`

**请求转换**:

| StreamRequest | SDK GenerateContentParameters |
|--------------|------------------------------|
| `Message.SystemMessage` | `systemInstruction` |
| `Message.UserMessage` | `contents[].role="user"` |
| `Message.AssistantMessage` | `contents[].role="model"` |
| `ContentBlock.TextContent` | `Part(text=...)` |
| `ContentBlock.ImageContent` | `Part(inline_data=...)` |
| `ContentBlock.ToolUseContent` | `Part(function_call=...)` |
| `ContentBlock.ToolResultContent` | `Part(function_response=...)` |
| `ToolDefinition` | Tool `functionDeclarations` |

**响应转换**:

| SDK GenerateContentResponse | StreamEvent |
|----------------------------|-------------|
| `candidates[0].content.parts[i]` (text) | `TextDelta(text)` |
| `candidates[0].content.parts[i]` (function_call) | `ToolCallStart(id, name)` + `ToolCallEnd(id, name, args)` |
| `usageMetadata` | `UsageInfo` |
| `finishReason` | `StreamDone(reason, usage)` |
| `promptFeedback.blockReason != null` | `StreamError(...)` |

**关键细节**: Google 的 functionCall 一次性返回完整参数，不需要聚合——`ToolCallStart` 和 `ToolCallEnd` 可连续发出。`promptFeedback.blockReason` 安全过滤视为错误。

### 2.4 MistralConversationsApi

**依赖**: 无外部 SDK，使用 `PiHttpClient`

**协议**: [Mistral Chat Completions API](https://docs.mistral.ai/api/) — 与 OpenAI 高度相似的标准 SSE

```
请求: POST https://api.mistral.ai/v1/chat/completions
       { "model": "...", "messages": [...], "stream": true, "tools": [...] }

响应: data: {"id":"...","choices":[{"delta":{"content":"..."}}]}\n\n
      data: {"id":"...","choices":[{"delta":{"tool_calls":[...]}}]}\n\n
      data: [DONE]
```

消息和响应转换逻辑与 `OpenAICompletionsApi` 共享，但需要：
1. 手动构建 JSON 请求体（Jackson）
2. 手动解析 SSE 事件流（`PiHttpClient` 提供 SSE 迭代器）
3. tool_calls delta 聚合复用与 OpenAI 相同的逻辑

### 2.5 PiHttpClient

仅 Mistral 直接使用，但设计为可复用于未来无 SDK 的 Provider。

**职责**:
- 携带 `User-Agent: pi-java/<version>` 请求头
- 自动重试 408/409/429/5xx，解析 `Retry-After` 头
- SSE 解析：将 `text/event-stream` 响应体转换为 `Iterator<ServerSentEvent>`
- 通过 `StructuredTaskScope` 支持 AbortSignal 可中断退避

---

## 3. Provider 注册机制

### 3.1 设计

pi 原版通过 `providers/all.ts` 的 `builtinProviders()` 聚合。Java 版使用手动注册 + ServiceLoader 双通道：

```java
// 手动注册（Phase 1 主力）
var registry = new ProviderRegistry();
registry.register(new AnthropicProvider());
registry.register(new OpenAIProvider());
registry.register(new GoogleProvider());
registry.register(new DeepSeekProvider());
registry.register(new MistralProvider());

// ServiceLoader 发现（Phase 6 扩展）
ServiceLoader.load(ProviderFactory.class).forEach(registry::register);
```

### 3.2 Provider 实现规则

每个 Provider 是**不可变配置对象**，创建时绑定协议适配器。`Provider.name()` 对应 SDK/API 的 provider 标识。

### 3.3 ProviderFactory 接口

SPI 扩展点，第三方 jar 可通过 `META-INF/services/com.pijava.ai.provider.ProviderFactory` 注册。

---

## 4. 模型目录系统

### 4.1 BuiltinCatalog

内置 5 个供应商的模型数据，用 `Map.of()` 静态初始化，对齐 pi 的 `providers/*.models.ts` 生成的数据。

模型数据字段（`ModelInfo`）:
- `id`（`ModelId`）、`displayName`、`capabilities`（`Set<ModelCapability>`）
- `maxInputTokens`、`maxOutputTokens`、`deprecated`
- `pricing`（`PricingInfo` — 新增字段）：input/output 每百万 token 价格

### 4.2 模糊搜索

`ModelCatalog.search(query)` 按 model 名称进行大小写不敏感的包含匹配。

### 4.3 ModelsStore

目录持久化抽象（Phase 1 仅 InMemoryModelStore，Phase 4 切换为 SQLite）：

```java
public interface ModelsStore {
    Map<String, ModelInfo> load(String providerId);
    void save(String providerId, Map<String, ModelInfo> models);
}
```

---

## 5. 认证系统

### 5.1 认证优先级

1. `ApiOptions.apiKey` — 代码显式传入（最高优先级）
2. `~/.pi-java/auth.json` — `pi-ai auth` 写入的文件存储
3. 环境变量 — `ANTHROPIC_API_KEY` 等

### 5.2 环境变量映射（EnvApiKeyResolver）

对齐 pi 的 `env-api-keys.ts`：

| Provider | 环境变量 |
|----------|---------|
| anthropic | `ANTHROPIC_API_KEY` |
| openai | `OPENAI_API_KEY` |
| google | `GEMINI_API_KEY` |
| deepseek | `DEEPSEEK_API_KEY` |
| mistral | `MISTRAL_API_KEY` |

### 5.3 FileCredentialStore

文件存储实现 `CredentialStore`：
- 路径：`~/.pi-java/auth.json`
- 格式：`{"provider": "api-key-value", ...}`
- 文件锁保证跨进程并发安全（`FileChannel.lock()`）

---

## 6. FauxProvider — 测试基础设施

对齐 pi 的 faux provider。允许测试中预设 `StreamEvent` 列表作为假 LLM 响应回放。

### 6.1 基础用法

构造时传入一组 `StreamEvent`，`ChatApi` 调用时按顺序回放。

### 6.2 能力

- 文本响应模式：`TextDelta → StreamDone`
- 工具调用模式：`ToolCallStart → ToolCallDelta → ToolCallEnd → StreamDone`
- 错误模式：任意位置插入 `StreamError`
- 延迟模拟：可配置每事件间隔

### 6.3 用途

- 下游模块（agent-core）的单元测试基础
- Provider 适配器的流转换验证
- `ChatApi` 接口的 conformance 测试

---

## 7. pi-ai CLI

基于 picocli 的独立命令行工具，入口点 `AiCli.main()`。

### 7.1 命令

| 命令 | 功能 |
|------|------|
| `pi-ai list-models [provider]` | 列出全部模型或按 provider 过滤 |
| `pi-ai auth <provider>` | 交互式输入 API key，存入 `~/.pi-java/auth.json` |
| `pi-ai ping <provider> [model]` | 发一条简单请求验证连接 |

### 7.2 输出格式

`list-models` 输出表格：

```
Provider    Model ID                    Context    Input/$    Output/$
──────────  ─────────────────────────   ────────   ────────   ────────
anthropic   claude-fable-5              200K       $3.00      $15.00
anthropic   claude-sonnet-4-6           200K       $3.00      $15.00
openai      gpt-5                       128K       $2.50      $10.00
...
```

---

## 8. 测试策略

### 8.1 测试分层

| 层级 | 内容 | 依赖 |
|------|------|------|
| 消息转换测试 | `Message` → SDK params 的字段对应 | 无 |
| 流事件转换测试 | SDK 事件 → `StreamEvent` | FauxProvider 回放 |
| 工具调用聚合测试 | OpenAI tool_calls delta → `ToolCallEnd` | 无 |
| 重试逻辑测试 | HTTP 状态码 → 重试/不重试 | PiHttpClient |
| 认证解析测试 | 环境变量/文件/显式 apiKey 优先级 | 假环境变量 |
| 冒烟测试 | 每个 Provider 1 个真实 API 请求 | 真实 API key（手动触发） |

### 8.2 覆盖率目标

- 协议适配器：≥ 85% 行覆盖
- Provider 配置：不需要测试（纯配置代码）
- 认证系统：≥ 80%
- FauxProvider：≥ 90%

---

## 9. 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P1-0 | **编写阶段设计文档** | `06-phase1-ai-design.md` | 0.5d |
| P1-1 | 审查/补充 Phase 0 接口 | 补充 `PricingInfo`、调整 `ProviderApi` 标记接口 | 0.5d |
| P1-2 | `PiHttpClient` + SSE 解析器 | HttpClient 封装：重试、`User-Agent`、SSE 迭代器 | 1d |
| P1-3 | `AnthropicMessagesApi` | 消息转换 + 流事件映射 + thinking block 处理 | 1.5d |
| P1-4 | `OpenAICompletionsApi` | 消息转换 + tool_calls delta 聚合 | 1.5d |
| P1-5 | `GoogleGenerativeAiApi` | 消息转换 + promptFeedback 安全拦截 | 1.5d |
| P1-6 | `MistralConversationsApi` | JSON 请求构建 + SSE 响应解析 | 1d |
| P1-7 | 5 个 Provider 配置 | 配置类 + ProviderRegistry 注册 | 0.5d |
| P1-8 | `ProviderRegistry` + ServiceLoader | SPI 发现 + 手动注册 | 0.5d |
| P1-9 | `FauxProvider` | 可编程假 Provider | 0.5d |
| P1-10 | 模型目录 + `BuiltinCatalog` | 5 供应商模型数据 + 模糊搜索 | 1d |
| P1-11 | 认证系统 | `EnvApiKeyResolver` + `FileCredentialStore` | 1d |
| P1-12 | `pi-ai` CLI | picocli：`list-models`、`auth`、`ping` | 0.5d |
| P1-13 | 单元测试 | 覆盖率 > 80% | 2.5d |
| P1-14 | 冒烟测试 | 每 Provider 1 个真实 API 请求（手动触发） | 0.5d |

**总工时**：约 2.5 周（P1-3 到 P1-6 四个适配器可并行开发，各适配器独立）

---

## 10. 里程碑

- [ ] `mvn clean verify` 通过（零错误、零警告）
- [ ] FauxProvider 可用于下游模块测试（不需要真实 API key）
- [ ] 5 个 Provider 至少各有一个单元测试通过
- [ ] `pi-ai list-models` 输出 5 个供应商的所有模型
- [ ] `pi-ai auth <provider>` 交互式配置 API key

---

## 11. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| anthropic-java SDK 不兼容 JDK 26 | 中 | SDK 声明 JDK 8+，基本兼容；JPMS 问题可通过 `--add-opens` 处理 |
| google-genai 版本快速迭代 | 低 | 锁定精确版本，升版本单独 PR |
| Mistral API 变更 | 低 | 协议简单（标准 SSE + JSON），影响可控 |
| DeepSeek API 与 OpenAI 差异 | 中 | 已知差异用 compat 配置处理 |
| 多个 SDK 传递依赖冲突 | 中 | Maven enforcer `dependencyConvergence` 已配置，CI 自动拦截 |

---

## 12. Phase 1 不做（延后到 Phase 6 实现）

以下功能 pi 原版均已实现，pi-java 延后到 Phase 6（生态扩展）：

| 功能 | pi 现状 | 延后原因 |
|------|---------|---------|
| OAuth 登录流程（`pi-ai login` Anthropic / GitHub Copilot / OpenRouter） | ✅ 已实现 | Phase 1 以 API key 优先，降低认证复杂度 |
| 远程模型目录更新（ETag 条件请求 + `refreshModels()`） | ✅ 已实现 | Phase 1 内置静态目录足够 |
| Bedrock 适配器（`bedrock-converse-stream.ts` ~1173行） | ✅ 已实现 | 非核心路径，AWS 依赖重 |
| Vertex AI 适配器（`google-vertex.ts` ~592行） | ✅ 已实现 | 非核心路径 |
| 图像生成模型（`image-models*.ts` + `images*.ts`） | ✅ 已实现 | Phase 1 聚焦文本生成 |
| CBOR 协议（远程会话编解码） | ✅ 已实现 | 依赖 protocol 模块（Phase 6） |
| 模型目录 CLI 发布工具 | ✅ 已实现 | 开发辅助工具，非运行时必需 |

Phase 1 聚焦**可跑通端到端的最小 LLM 调用层**：5 个 Provider + API key 认证 + 静态模型目录。

---

## 13. 自验证清单

Phase 1 完成的标准：

```bash
# 1. 全量编译
./mvnw clean verify
# → BUILD SUCCESS

# 2. 测试覆盖率
./mvnw jacoco:report
# → 协议适配器 ≥ 85%

# 3. CLI 可用
./mvnw -pl pi-java-ai exec:java -Dexec.mainClass="com.pijava.ai.cli.AiCli"
# → 显示 help 信息

# 4. FauxProvider 可独立使用
# → 下游模块可 import FauxProvider 并创建测试
```
