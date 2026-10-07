# 包 28：MCP 对齐（B160）——第 1 包：协议层（jsonrpc / types / content）

> **状态：🚧 推进中（2026-10-07，R-A）——第 1–2 包已闭环，第 3–12 包待续。**
> 本台账条目 **B160「MCP 整块零对应」** 的对齐总包。本文是 **12 包路线图**
> （§3）＋各包详细设计；每包用户审核通过后才许写代码。B160 跨全包，全部
> 闭环才销号。第 1 包实施记录见 §8。

## 1. 范围 census（锚点 200387122）

pi 侧 MCP 分两层，本地 pi clone 已在新锚点（`git rev-parse HEAD` 实测）：

| 层 | pi 路径 | src | test |
|---|---|---|---|
| 独立客户端库 | `packages/mcp` | 3,167 | 1,438 |
| coding-agent 内置扩展 | `packages/coding-agent/src/extensions/mcp` | 4,093 | ~2,027 |

合计 **src 7,260 行**。pi-java 侧实测 **零 MCP 代码**（全树 grep 复核）。
已有的承重接口缝：

- `AgentTool<TParams,TDetails>`（`pi-java-agent-core/.../agent/tool/AgentTool.java:21`）
  与 pi 的 `ToolDefinition` 同构；`ToolResult.addedToolNames`（同包 `ToolResult.java:20`）
  注释已预留「Phase 2c — MCP tools」。
- `ToolRegistry`（同包 `ToolRegistry.java:20`）；装配点
  `AgentSession.assemble`（`pi-java-coding-agent/.../core/AgentSession.java:239`）。
- `AbortSignal`（`pi-java-ai/.../ai/AbortSignal.java`）；JDK
  `java.net.http.HttpClient`；Jackson 2.19.2。
- JSONL RPC 的 pending-future 关联模式：`PiClient`
  （`pi-java-client/.../client/PiClient.java:41`）。

## 2. 模块裁决：新建 `pi-java-mcp`

pi 的 `packages/mcp` 是**独立包**（只依赖 Node 内置），由 coding-agent 扩展与
agent 示例共同消费。1:1 落地为新 Maven 模块，而不是塞进 agent-core：

```
telemetry ← ai ← mcp
ai ← agent-core ← coding-agent   （coding-agent 同时依赖 mcp）
```

- artifactId `pi-java-mcp`，包根 `com.pijava.mcp`；root pom 模块条目
  13 → 14，BOM `dependencyManagement` 同步加一条。
- 依赖：仅 `pi-java-ai`（用 `AbortSignal`；第 2 包起）＋ JDK＋Jackson。
- native 友好：无反射重负载（wire record 由 Jackson 显式绑定，见 §4）。

## 3. 12 包路线图

| # | pi 出处（src 行） | 内容 |
|---|---|---|
| **1** | jsonrpc.ts 113 · types.ts 131 · content.ts 117 | **本包**：JSON-RPC ADT、协议 wire records、内容块、`toLlmContent`、异常 |
| 2 | client.ts 615 | `McpClient` 状态机、pending/分页/进度 |
| 3 | transports/transport.ts 55 · stdio.ts 216 | `TransportEvents` ＋ `StdioTransport`（进程树/Windows taskkill） |
| 4 | transports/in-memory.ts 51 | InMemory 测试 transport |
| 5 | transports/streamable-http.ts 500 | SSE 消费器、HTTP transport、响应流/GET 流重连 |
| 6 | oauth/errors 55 · types 204 · discovery 185 | OAuth 元数据结构解析、`.well-known` 发现、WWW-Authenticate |
| 7 | oauth/callback 164 · provider 168 · flow 449 · index 56 | loopback 回调服务器、有状态 provider、授权/刷新/注册/DCR/cimd |
| 8 | core/mcp-servers.ts 319 · extensions/mcp/config.ts 242 | 配置类型＋校验；`mcp.json` 全局/项目加载、override、增删改 |
| 9 | extensions/mcp/log.ts 69 · runtime.ts 474 · oauth.ts 532 | `McpServerConnection`、`mcp-auth.json` 凭据 store、登录编排 |
| 10 | extensions/mcp/tools.ts 339 · resources.ts 342 | MCP→`AgentTool` 适配（截断/临时文件/进度）、资源三工具 |
| 11 | extensions/mcp/ui.ts 252 · cli.ts 614 | `/mcp` 管理器 TUI、`pi mcp` 非会话 CLI |
| 12 | extensions/mcp/index.ts 1,225 | ExtensionFactory 接线、`mcp_servers` 系统提示段、会话生命周期 |

**前置依赖裁决（第 12 包时处理，此处登记）**：默认 `exposure:"codemode"` 的
codemode 激活依赖 **B161（codemode 零对应）**；`deferred` 依赖 **B162 的
tool-search**。建议：wire/配置语义全量保留 codemode，**运行时激活路径**在
B161 闭环前 stub（direct/deferred/hidden 三包先全功能），拟新登记
**B177（codemode exposure 在 B161 闭环前不可达）**。

## 4. 第 1 包详细设计

新文件均在 `pi-java-mcp/src/main/java/com/pijava/mcp/`。

### 4.1 模块 JSON 绑定 `McpJson`

```java
package com.pijava.mcp;
/** Jackson 绑定：pi-mcp 线格式（snake_case，未知 _meta/扩展字段放行）。 */
public final class McpJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private McpJson() {}
    public static ObjectMapper mapper() { return MAPPER; }
}
```

枚举字段（第 6/8 包加）用 `@JsonValue`/`@JsonCreator`；未知枚举值按
`READ_UNKNOWN_ENUM_VALUES_AS_NULL`（在对应包落地时开，避免本包过度配置）。

### 4.2 JSON-RPC 身份与消息（`protocol/jsonrpc/`）

**身份**——TS `JsonRpcId = string | number`（jsonrpc.ts:1），pi 校验接受任意
有限数（含小数）。关键约束：**我方发出的 id 是整数计数器，线字节必须是 `1`
不是 `1.0`**。裁决：ADT 仅用于 pending-map 关联；wire 消息一律用
`Map<String,Object>`（id 放原始 `Long`，Jackson 逐字输出 `1`）。

```java
// JsonRpcId.java
package com.pijava.mcp.protocol.jsonrpc;

import org.jspecify.annotations.Nullable;

/** JSON-RPC id：字符串或有限数（jsonrpc.ts:1,89-91）。仅用于请求关联，不直接上线。 */
public sealed interface JsonRpcId permits JsonRpcId.Text, JsonRpcId.Number {
    record Text(String value) implements JsonRpcId {}
    record Number(double value) implements JsonRpcId {
        public static @Nullable JsonRpcId of(@org.jspecify.annotations.Nullable Object raw) {
            return switch (raw) {
                case String s -> new Text(s);
                case java.lang.Number n -> Double.isFinite(n.doubleValue()) ? new Number(n.doubleValue()) : null;
                default -> null;
            };
        }
    }
}
```

**消息 ADT**（jsonrpc.ts:3-35）：

```java
// JsonRpcMessage.java
public sealed interface JsonRpcMessage
        permits JsonRpcMessage.Request, JsonRpcMessage.Notification, JsonRpcMessage.Response {
    record Request(JsonRpcId id, String method, @Nullable Object params) implements JsonRpcMessage {}
    record Notification(String method, @Nullable Object params) implements JsonRpcMessage {}
    sealed interface Response extends JsonRpcMessage permits Response.Success, Response.Error {
        record Success(JsonRpcId id, Object result) implements Response {}
        record Error(JsonRpcId id, JsonRpcError error) implements Response {}
    }
}
```

```java
// JsonRpcError.java —— jsonrpc.ts:16-20
public record JsonRpcError(int code, String message, @Nullable Object data) {}
```

**标准错误码**（jsonrpc.ts:37-43）：纯常量闭集 → enum（数值上线）：

```java
public enum JsonRpcErrorCode {
    PARSE_ERROR(-32700), INVALID_REQUEST(-32600), METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602), INTERNAL_ERROR(-32603);
    @JsonValue public int code() { return code; }
    private final int code;
    JsonRpcErrorCode(int code) { this.code = code; }
    @JsonCreator public static @Nullable JsonRpcErrorCode fromCode(int code) {
        for (var v : values()) if (v.code == code) return v;
        return null;
    }
}
```

`McpError.code` 仍收 int（服务器可回任意码），构造器同时收 enum：

```java
// McpError.java —— jsonrpc.ts:45-55
public class McpError extends RuntimeException {
    private final int code;
    private final @Nullable Object data;
    public McpError(int code, String message, @Nullable Object data) {
        super(message); this.code = code; this.data = data;
    }
    public McpError(JsonRpcErrorCode c, String message) { this(c.code(), message, null); }
    public int code() { return code; }
    public @Nullable Object data() { return data; }
}
```

同目录另三个异常（均 `extends RuntimeException`，同 PiHttpException 风格）：

| 文件 | 对齐 | 要点 |
|---|---|---|
| `McpConnectionClosedError.java` | jsonrpc.ts:57-62 | 默认 message `"MCP connection closed"`；另收 `String` 构造 |
| `McpTimeoutError.java` | jsonrpc.ts:64-72 | `long timeoutMs()`；message 模板 `MCP request timed out after %sms` |
| `McpAbortError.java` | jsonrpc.ts:74-79 | message 名 `"AbortError"`，默认 `"MCP request aborted"` |

**分类/解析**（jsonrpc.ts:81-113），入参为 Jackson 解析后的 `Object`：

```java
// JsonRpcMessages.java
public final class JsonRpcMessages {
    public static boolean isObject(@Nullable Object v) {
        return v instanceof Map<?, ?>;
    }

    /** jsonrpc.ts:110-113 */
    public static JsonRpcMessage parse(@Nullable Object value) {
        if (isRequest(value)) return toRequest((Map<?, ?>) value);
        if (isNotification(value)) return toNotification((Map<?, ?>) value);
        if (isResponse(value)) return toResponse((Map<?, ?>) value);
        throw new McpError(JsonRpcErrorCode.INVALID_REQUEST, "Invalid JSON-RPC message");
    }
    // isJsonRpcId：String 非空 / Number 有限（JsonRpcId.of 非 null）
    // isRequest   ：isObject && jsonrpc=="2.0" && id 合法 && method 为 String   （:93-97）
    // isNotification：同但无 id 键                                              （:99-101）
    // isResponse  ：id 合法；有 result 则无 error；error 须 code:number,message:String（:103-108）
}
```

`toRequest` 等：`params` 取 `map.get("params")`（可缺、原样透传，不绑定类型）；
Request.id 经 `JsonRpcId.of`。

### 4.3 协议 wire records（`protocol/`，对齐 types.ts）

版本常量（types.ts:4-9）：

```java
public final class McpVersion {
    public static final String LATEST = "2025-11-25";
    public static final List<String> SUPPORTED =
            List.of(LATEST, "2025-06-18", "2025-03-26", "2024-11-05");
    private McpVersion() {}
}
```

wire records（全部 Jackson 绑定、组件 snake_case 用 `@JsonProperty`，未知字段
放行）。机械移植，清单：

| 文件 | types.ts | record 组件（nullable 一律包装类型） |
|---|---|---|
| `Implementation.java` | :12-16 | `String name, version, title` |
| `Root.java` | :18-21 | `String uri, name` |
| `ClientCapabilities.java` | :23-28 | `Map<String,Object> experimental`；`Roots roots`（`Boolean listChanged`）；`Map sampling, elicitation` |
| `ServerCapabilities.java` | :30-37 | `Map experimental, logging, completions`；`Prompts prompts`；`Resources resources`；`Tools tools` |
| `InitializeParams.java` | :39-43 | `String protocolVersion`；`ClientCapabilities capabilities`；`Implementation clientInfo` |
| `InitializeResult.java` | :45-50 | `String protocolVersion`；`ServerCapabilities capabilities`；`Implementation serverInfo`；`String instructions` |
| `ProgressNotification.java` | :52-57 | `JsonRpcId progressToken`→`Object`；`double progress`；`Double total`；`String message` |
| `CancelledNotification.java` | :59-62 | `Object requestId`；`String reason` |
| `ToolAnnotations.java` | :64-70 | `String title`；`Boolean readOnlyHint, destructiveHint, idempotentHint, openWorldHint` |
| `ToolExecution.java` | :72-74 | `TaskSupport taskSupport`（enum：forbidden/optional/required，`@JsonValue` 小写） |
| `McpTool.java` | :76-85 | `String name, title, description`；`Map<String,Object> inputSchema, outputSchema, _meta`；`ToolAnnotations annotations`；`ToolExecution execution` |
| `ListToolsResult.java` | :87-91 | `List<McpTool> tools`；`String nextCursor`；`Map _meta` |
| `Resource.java` | :94-103 | `String uri, name, title, description, mimeType`；`Double size`；`ContentAnnotations annotations`；`Map _meta` |
| `ResourceTemplate.java` | :106-114 | `String uriTemplate, name, title, description, mimeType`；`ContentAnnotations annotations`；`Map _meta` |
| `ListResourcesResult.java` | :116-120 | `List<Resource> resources`；`String nextCursor`；`Map _meta` |
| `ListResourceTemplatesResult.java` | :122-126 | `List<ResourceTemplate> resourceTemplates`；`String nextCursor`；`Map _meta` |
| `ReadResourceResult.java` | :128-131 | `List<ResourceContents> contents`；`Map _meta` |

所有 record 加紧凑构造器把无键集合归一为空 map（与 pi `?? {}` 读取口径一致处
由第 2 包校验器负责；本包只保形）。

### 4.4 内容块与 `toLlmContent`（`protocol/content/`）

```java
// ContentAnnotations.java —— content.ts:1-5
public record ContentAnnotations(List<String> audience, Double priority, String lastModified) {}
```

```java
// ResourceContents.java —— content.ts:42-54
public sealed interface ResourceContents permits ResourceContents.Text, ResourceContents.Blob {
    record Text(String uri, @Nullable String mimeType, String text,
                @Nullable Map<String, Object> meta) implements ResourceContents {}
    record Blob(String uri, @Nullable String mimeType, String blob,
                @Nullable Map<String, Object> meta) implements ResourceContents {}
}
```

```java
// McpContentBlock.java —— content.ts:7-63
public sealed interface McpContentBlock permits McpContentBlock.Text, McpContentBlock.Image,
        McpContentBlock.Audio, McpContentBlock.ResourceLink, McpContentBlock.Embedded {
    record Text(String text, @Nullable ContentAnnotations annotations,
                @Nullable Map<String, Object> meta) implements McpContentBlock {}
    record Image(String data, String mimeType, @Nullable ContentAnnotations annotations,
                 @Nullable Map<String, Object> meta) implements McpContentBlock {}
    record Audio(String data, String mimeType, @Nullable ContentAnnotations annotations,
                 @Nullable Map<String, Object> meta) implements McpContentBlock {}
    record ResourceLink(String uri, String name, @Nullable String title, @Nullable String description,
                        @Nullable String mimeType, @Nullable Double size,
                        @Nullable ContentAnnotations annotations,
                        @Nullable Map<String, Object> meta) implements McpContentBlock {}
    record Embedded(ResourceContents resource, @Nullable ContentAnnotations annotations,
                    @Nullable Map<String, Object> meta) implements McpContentBlock {}
}
```

```java
// CallToolResult.java —— content.ts:65-70
public record CallToolResult(List<McpContentBlock> content,
                             @Nullable Map<String, Object> structuredContent,
                             @Nullable Boolean error,
                             @Nullable Map<String, Object> meta) {}
```

```java
// LlmContent.java —— content.ts:76（形状＝pi-ai Text/Image；mcp 本地定义，
// 第 10 包再映射到 com.pijava.ai.message.ContentBlock）
public sealed interface LlmContent permits LlmContent.Text, LlmContent.Image {
    record Text(String text) implements LlmContent {}
    record Image(String data, String mimeType) implements LlmContent {}
}
```

`toLlmContent`——content.ts:78-117 逐字移植：

```java
// McpContents.java
public final class McpContents {
    /** content.ts:78-102 */
    private static LlmContent blockToLlmContent(McpContentBlock block) {
        return switch (block) {
            case McpContentBlock.Text b -> new LlmContent.Text(b.text());
            case McpContentBlock.Image b -> new LlmContent.Image(b.data(), b.mimeType());
            case McpContentBlock.Audio b ->
                    new LlmContent.Text("[audio " + b.mimeType() + " omitted]");
            case McpContentBlock.ResourceLink b ->
                    new LlmContent.Text(b.name() + ": " + b.uri());
            case McpContentBlock.Embedded b -> switch (b.resource()) {
                case ResourceContents.Text r -> new LlmContent.Text(r.text());
                case ResourceContents.Blob r when r.mimeType() != null && r.mimeType().startsWith("image/") ->
                        new LlmContent.Image(r.blob(), r.mimeType());
                case ResourceContents.Blob r -> new LlmContent.Text(
                        "[binary resource " + r.uri() + " (" +
                                (r.mimeType() == null ? "unknown type" : r.mimeType()) + ") omitted]");
            };
        };
    }

    /** content.ts:111-117：空块但有 structuredContent ⇒ 其美化 JSON。 */
    public static List<LlmContent> toLlmContent(CallToolResult result) {
        var blocks = result.content() == null ? List.<McpContentBlock>of() : result.content();
        var out = blocks.stream().map(McpContents::blockToLlmContent).collect(Collectors.toList());
        if (out.isEmpty() && result.structuredContent() != null) {
            out.add(new LlmContent.Text(McpJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(result.structuredContent())));
        }
        return out;
    }
}
```

`writeValueAsString` 的 IOException 包 `UncheckedIOException`；输出美化 JSON 用
模块 mapper（无参 `writerWithDefaultPrettyPrinter`）。

## 5. 测试计划（L5，RED-first）

pi 侧 content.ts 只有 content.test.ts（34 行 2 用例）；jsonrpc 解析器在 pi
无独立单测（由 client.test.ts 间接覆盖），Java 侧补直接用例：

1. `JsonRpcMessagesTest`（NEW）：
   - request/notification/success/error 四类正例（params 缺省/`null`/对象三态）；
   - id：字符串、整数、有限小数接受；`NaN`/`Infinity`/`null`/布尔拒绝；
   - response 规则：result 与 error 互斥；error 缺 code/message 拒绝；
   - 非对象、`jsonrpc!="2.0"`、method 非字符串 ⇒ `parse` 抛
     `McpError(INVALID_REQUEST)`；
   - notification 带 id、request 缺 id 互斥分类正确。
2. `McpContentBlocksTest`（NEW）：五类块＋两 resource 内容 Jackson 往返；
   `_meta`/annotations 透传与缺省。
3. `ToLlmContentTest`（NEW，映射 content.test.ts 两条款并扩全分支）：
   - 文本/图片直通；audio→`[audio <mime> omitted]`；
   - resource_link→`<name>: <uri>`；
   - embedded text→文本；embedded image blob（mime `image/*`）→Image；
   - embedded 非图 blob→binary omitted 占位（含/不含 mime 两文案）；
   - 空 content＋structuredContent→美化 JSON 文本；空 content 且无
     structuredContent→空列表。
4. `McpErrorsTest`（NEW）：code/data 存取、timeout 携带 ms、默认消息逐字。

## 6. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `Number(n)` 去掉 `Double.isFinite` 守卫 | NaN/Infinity id 拒绝条款红 |
| M2 | response 校验放宽 result/error 互斥（删 `!("error" in message)`） | 互斥条款红 |
| M3 | embedded image blob 的 `image/` 前缀改 `audio/` | ToLlmContent image 分支红 |
| M4 | 空块 structuredContent fallback 删除 | content.test 第 2 条款红 |
| M5 | 枚举 `@JsonValue` 删除（发枚举名） | wire records 若经 mapper 序列化则红（第 2 包接线后生效；本包以 `writeValueAsString(TaskSupport)` 直接钉） |

每次变异后 grep 复核落地（CRLF 教训）。

## 7. 台账影响

- 不销号（B160 跨 12 包，全部闭环才销）。
- 已登记 **B177**（codemode exposure 依赖 B161，见 §3）。
- 每包闭环时回填本文 banner 与 docs/04。

## 8. 第 1 包实施记录（2026-10-07）

**落地（1 feat＋1 docs）**：

| commit | 内容 |
|---|---|
| feat | 新模块 `pi-java-mcp`（root pom/BOM 接线）；`McpJson`；jsonrpc（id/消息 ADT/错误码 enum/四异常/解析器）；16 个协议 wire records；content（annotations/resource 内容/五类块/CallToolResult/LlmContent/`toLlmContent`） |
| docs | 台账登记 B177 与本文闭环 |

**测试 15/15 绿**：`JsonRpcMessagesTest` ×4、`McpContentBlocksTest` ×4、
`ToLlmContentTest` ×3、`McpErrorsTest` ×4；checkstyle 0 违规。

**变异探针 5/5 红**：M1 去 `Double.isFinite` 守卫 ⇒ NaN/Infinity id 拒绝条款红；
M2 放宽 result/error 互斥 ⇒ 互斥条款红；M3 embedded blob 前缀 `image/`→`audio/`
⇒ 图片分支红；M4 删 structuredContent fallback ⇒ fallback 条款红；M5 枚举去
`@JsonValue` ⇒ wire 值红。

**两处设计外发现**：

1. Jackson 的 JSON-RPC Id 常量是 `JsonTypeInfo.Id.DEDUCTION`（非 DEDUCED）。
2. 增强 switch 的 `default` **不接 null**（null selector 无 `case null` 即 NPE，
   编译器在 switch 点生成 `Objects.requireNonNull`）⇒ `JsonRpcId.of` 补
   `case null -> null`。
