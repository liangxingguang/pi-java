# 21 - L2 MCP 新锚点重测与对齐设计（structuredContent／连接／OAuth／CLI／接线）

> 【背景摘要】docs/20 已闭环，L1 AI 账面 232/232＝100%。本包启动 L2 mcp：以 `56cae7823` 全量重测 `docs/map/07-mcp.md` 的 89 个计分单元，把新锚点在 MCP 面的 17 处行为变化落成设计，并新增 2 个计分单元（`reachesTools` 合约、UTF-8 BOM）。
>
> 文档状态：**已实施并闭环（2026-11-04）**。W1–W12 全部落地；账面 126.0/131＝96.18%。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。
> pi-java 设计基线 HEAD：`d07def1e`（docs/20 闭环）。

## 0. 范围、基线与口径

### 0.1 基线与目标

| 项 | 值 |
|---|---|
| pi 锚点 | `56cae782341c339d3bb820081f0f9425d8c5b1ec`（2026-10-31） |
| pi-java 设计基线 | `d07def1e`（main，docs/20 闭环）；实施终态 `1d85bef8`（main，2026-11-04） |
| map/07 旧账面（ea448f454） | **121.0/129＝93.8%**（未加权 74/89＝83.1%） |
| 新锚点重测账面（未实施） | **96.0/131＝73.28%**（对齐 60／存疑 12／缺失 19，含 2 个新单元） |
| 本包目标（实施后） | **126.0/131＝96.18%**（对齐 81／存疑 10／缺失 0；未加权 81/91＝89.0%） |
| 可执行基线 | docs/20 终态：`mvn clean verify` 实测 **6,150 测试／0 失败／0 错误** |
| 依赖变更 | **无**（纯源码与测试，不新增 Maven 依赖） |
| 新编号 | **D217**（`/mcp` 视口捕获无文本级对位）；B217 在本包销号；新 B 本轮不产生 |

### 0.2 重测方法与取证纪律

1. 先用 diff 圈定新锚点 MCP 差异面：

   ```bash
   git -C D:/workplaceForai/pi diff --stat ea448f454..56cae7823 -- \
     packages/mcp packages/coding-agent/src/extensions/mcp \
     packages/coding-agent/src/core/mcp-servers.ts \
     packages/coding-agent/src/cli/args.ts
   ```

   结果：23 个文件、+430／−170。MCP 面全部差异都在该集合内；集合外的文件在本轮视为不变（`docs/map/07-mcp.md` 的 68 行据此复核）。
2. 每处差异读新锚点源码逐字确认，不凭 changelog。`packages/mcp/CHANGELOG.md` 只用于圈定候选（#10416／#10598／#9265）。
3. 计分口径沿用 map/07：对齐 1／存疑 0.5／缺失 0；权重 3＝每轮路径、2＝常用、1＝低频/内部；R5（codemode）不进分母。
4. 本包**不重新排期**旧 B 编号；只对新锚点新增差异登记动作。

### 0.3 术语

- **structuredContent**：MCP `tools/call` 的机器可读结果字段。新锚点允许任意 JSON（对象、数组、字符串、数字、布尔、`null`）。【模型可见：工具声明与回退文本】
- **clientRegistration**：MCP OAuth 的客户端识别策略。`auto`（默认）＝能托管 Client ID Metadata Document 就用，否则动态注册；`dcr`＝总是动态注册；`cimd`＝总是用托管文档，服务器不支持即失败。
- **CIMD**：Client ID Metadata Document。pi 把文档托管在 `https://pi.dev/oauth`，用一个 URL 充当 client_id。
- **reachesTools**：工具声明“一次调用可能触达哪些工具”的合约：`"all"` 或一组词；词命中“工具或命名空间名包含它”即算触达。MCP 扩展用它决定 `tool_call` 先等哪些服务器、以及要不要自动激活 `tool_search`。
- **namespace alias**：命名空间的别名，例如命名空间 `mcp__docs` 的别名 `docs`，让 `describeNamespace("docs")` 能命中。
- **send signal**：随一次 `transport.send()` 传递的中止信号。不中止已经发出的请求，只阻止“认证恢复后再次派发”。
- **BOM**：UTF-8 字节序标记 `U+FEFF`。JSON 文件带 BOM 时，直接 `JSON.parse` 会失败。

## 1. 新锚点重测结论

### 1.1 判定变化的 21 行

下表只列受到新锚点差异影响的行。“旧”是 map/07 旧锚点判定；“重测”是 pi-java 当前代码对新锚点的判定（实施前）；“本包”是实施后的目标。未列入的 68 行经 diff 空文件确认代码未变，行号按新锚点复核后沿用原判定（详见 §1.4）。

| # | 单元 | pi 新旧证据（新锚点） | pi-java 现状 | 旧 | 重测 | 本包动作 | 目标 |
|---|---|---|---|---|---|---|---|
| 12 | OAuth 子配置九条校验 | `core/mcp-servers.ts:179-215`：`clientRegistration` 接受 `"auto"｜"dcr"｜"cimd"`，错误文案改为 `must be "auto", "dcr", or "cimd"` | `config/McpServerConfigs.java:286-305` 只接受 dcr/cimd，`"auto"` 被拒绝 | 对齐 | **缺失** | W5 | 对齐 |
| 19 | 请求超时／progress 重置／abort | `client.ts:60-70,431,439-441,582`：请求落定后中止 `sendController`；`transport.send` 收 `signal` | `McpClient.java:199-213` 无按请求 send 信号，认证恢复后可再次派发 | 对齐 | **缺失** | W3 | 对齐 |
| 20 | in-memory transport | `src/testing/index.ts` 发布 `InMemoryTransport`／`createInMemoryTransportPair`；`in-memory.ts:15-43` 有 `started` 门、`close()` 级联关闭对端、`emitError` 暴露 | 类在 `src/test`，无产品发布面；`start()` 空操作、`close()` 不关对端 | 存疑 | **缺失** | W9 | 对齐 |
| 25 | Streamable HTTP | `streamable-http.ts:231-245,295-305`：`send(message,{signal})`，每次派发前 `if (signal?.aborted) throw new McpAbortError()` | `StreamableHttpTransport.java:87-127,129-146` 无 send 信号 | 对齐 | **缺失** | W3 | 对齐 |
| 29 | `connectOnce` 工具/资源并发 | 新锚点 **删除**连接期资源枚举：`runtime.ts:359-390` 只 `listTools()`，`hasResources` 取 capability | `McpConnector.java:79-94` 仍并发/顺序拉 tools＋resources；`McpServerConnection` 持 resources 列表 | 存疑 | **缺失** | W4 | 对齐 |
| 43 | `clientRegistration:"cimd"` | `core/mcp-servers.ts:112-125,136-146`；`oauth.ts:250-330,504-530`：默认 `auto`；`auto` 仅当无 clientId/clientName、未被改名、callback 符合托管文档时用 CIMD；不支持时返回 `undefined`，仅显式 `cimd` 抛错 | `McpOAuthSettings` 只有 DCR/CIMD；`McpClientMetadataDocuments.create` 不支持即抛；无 auto 决策树 | 存疑 | **存疑**（B192） | W5（补齐 auto 决策树） | 存疑（B192 维持） |
| 46 | login 端到端真授权服务器夹具 | `packages/mcp/test/oauth.test.ts:96-280`（“discovers, registers, authorizes with PKCE, and refreshes on 401”：真 HTTP AS＋授权端点重定向＋PKCE 交换） | `McpSignInTest` 只到连不上/非 OAuth/缺参/超时 | 存疑 | **存疑**（B217） | W2c：本地 AS 夹具＋完整 PKCE/交换 | 对齐 |
| 47 | `toLlmContent` 回退 | `protocol/content.ts:111-117`：`structuredContent !== undefined` 时回退；数组与 `null` 也回退（测试 `content.test.ts:24-35`） | `McpContents.java:46` 用 `!= null`，显式 `null` 不回退；字段类型只允许对象 | 对齐 | **缺失** | W1 | 对齐 |
| 49 | `CallToolResult.structuredContent` 线格形状 | `protocol/content.ts:64-70`：类型 `unknown`；`client.ts:144-150` 删除“必须对象”校验 | `CallToolResult.java:20` 是 `Map<String,Object>`；`McpClientValidators.java:94-98` 拒绝非对象 | 对齐 | **缺失** | W1 | 对齐 |
| 50 | structuredContent 主流消费链 | `tools.ts:266-290`：`convertMcpResult` 把去掉 `_meta` 的完整结果作为脚本/钩子载荷 | `McpResultConverter.java:67-75` 已传完整结果（after_tool 链在）；但非对象载荷在入口已被拒 | 缺失 | **缺失** | W1 | 对齐 |
| 59 | 每工具 outputSchema 包装 | `tools.ts:104-176`：`content` 用紧凑 CONTENT_BLOCK_SCHEMA；`structuredContent` 恒在（无 schema 时 `{}`）；`$ref` 重定位；无 `_meta`；`additionalProperties:true` | `McpResultSchema.java:27-40`：content items `{type:object}`、有 schema 才写 structuredContent、保留 `_meta`、无 `additionalProperties`、无 ref 重定位 | 对齐 | **缺失** | W10 | 对齐 |
| 62 | list_changed 刷新 | `runtime.ts:359-390`：只订阅 `tools/list_changed`；资源按需读取 | `McpConnector.java:71-73,168-181` 仍订阅并刷新 resources | 对齐 | **缺失** | W4 | 对齐 |
| 65 | `mcp add` | `cli.ts:303-420`：新增 `--oauth-client-registration <mode>`（http-only） | `McpAddRemoveCommand.java:29-41` 无该选项，传参报 Unknown option | 对齐 | **缺失** | W5 | 对齐 |
| 67 | `mcp list` | `cli.ts:100-108,465-545`：`resources?: true`；无 `resourceTemplates`；人类行 `  offers resources` | `McpListCommand.java:29-43,141-147,202-206,233-236` 输出数量与 templates | 对齐 | **缺失** | W4 | 对齐 |
| 68 | `mcp login/logout` | `cli.ts:220-265`：支持 `-l`；非 project 作用域给 `defined in …, not in the project; omit --local.`；拼错给 `Did you mean "x"?`（Levenshtein ≤2） | `McpCommand.java:142-155` 无 `-l`、无建议 | 对齐 | **缺失** | W6 | 对齐 |
| 71 | 文案命名 | `cli.ts:33-76`：HELP 组标题 `Options for add, remove, login, and logout`、usage 带 `[-l]`、`--timeout` 说明改 `Time limit for the whole sign-in`；`cli/args.ts:294`：`mcp <command> Manage MCP servers` | `McpCliHelp.java` 旧文案；`cli/HelpText.java:48-61` 主帮助**没有 mcp 命令行** | 存疑 | **存疑**（B213） | W6（补内容） | 存疑（改名方言维持） |
| 73 | 扩展工厂＋session_start／问题聚合 | `index.ts:234-252`：`connected · N tools · resources`（按 `hasResources` 布尔） | `McpExtensionState.java:159-164`／`McpServerPresentation.java:168-172` 仍按资源数量拼 `N resources` | 对齐 | **缺失** | W4 | 对齐 |
| 77 | `tool_call` 就绪等待 | `index.ts:224-232,1186-1207`：`tool.reachesTools?.(input)`；`reachesServer` 按词包含命名空间 | `McpExtension.java:303-314`：资源工具全等 + tool_search schema 判等 + 属主服务器 | 对齐 | **缺失** | W7 | 对齐 |
| 81 | 激活门 | `index.ts:501-529`：只认 `tool.name===tool_search && tool.reachesTools` | `McpLifecycle.java:282-285`：schema 对象引用判等 | 对齐 | **缺失** | W7 | 对齐 |
| 82 | 工具运行时注册／命名空间 | `index.ts:388-463`：namespace 加 `aliases:[server]` | `McpToolRegistrar.java:51-62` 只 name/description/instructions | 对齐 | **缺失** | W7 | 对齐 |
| 85 | `/mcp` 管理器视图 | `ui.ts:75-100,131-151`：`capturesViewportInput` 委托给当前输入处理器；TUI 层新增同名 Component 属性 | `McpManagerView.java:28-115` 文本级组件无该属性；TUI 适配层无对位 | 对齐 | **存疑**（D217） | 不实施，转 L7 | 存疑（D217） |

### 1.2 新增计分单元

| # | 单元 | pi 证据 | 权重 | 重测 | 本包动作 | 目标 |
|---|---|---|---:|---|---|---|
| 94 | `reachesTools` 工具合约（`AgentTool.reachesTools`＋`tool_search` 声明 `"all"`＋`getAllTools` 暴露） | `core/extensions/types.ts:635-648`；`tool-search/tool.ts:320-323`；`agent-session.ts:1539` | 1 | **缺失** | W7 | 对齐 |
| 95 | `mcp.json` UTF-8 BOM 剥离（读与写回两路） | `extensions/mcp/config.ts:92-100,229-232` 两处 `stripBom` | 1 | **缺失** | W8 | 对齐 |

### 1.3 旧 14 条存疑复核

| 行 | 新锚点结论 | 判定 | 台账 |
|---|---|---|---|
| A4 未知键保留 | `mcp-servers.ts:260-357` 返回对象副本，但**可观察的两条写路径与 java 相同**：`mcp add` 用全新对象整条覆盖（`cli.ts:340-394`），`/mcp` update 改原 JSON 树（`config.ts:229-242`） | **对齐** | B181 销号（口径修正，无代码） |
| A5 `piConfig.configDir` | `src/config.ts:578-606` 仍从 `package.json` 读 `APP_NAME`／`CONFIG_DIR_NAME`；java 全树硬编码 `.pi-java` | 存疑 | B182 维持（属 coding-agent 配置子系统／L5；对固定 rebrand 行为等价） |
| A6 解析失败文案 | BOM 部分由 #95 关闭；`JSON.parse` 与 Jackson 的错误正文仍不同（含位置格式） | 存疑 | B183 收窄（只剩正文） |
| A7 写出字面量 | `config.ts:228-242` 仍 JS 键序与 `JSON.stringify` 数字；java record 序＋Jackson 数字 | 存疑 | **W12b 对齐**，B184 销号 |
| B20 in-memory transport | `testing/index.ts` 仍是发布面；java 仅测试源集且语义缺 `started`/级联关闭 | **缺失 → W9** | 新锚点转缺失，本包对齐 |
| C27 `isTransientError` | `runtime.ts:70-75` 判据未变；java 的 `IOException` 仍宽于 `TypeError` | 存疑 | B190 维持 |
| C29 connect 并发 | 资源枚举删除 | **缺失 → W4** | 新锚点转缺失，本包对齐 |
| C30 WHATWG URL 归一 | `WebUrls` 覆盖主机小写/默认端口/尾斜杠；但非 ASCII 主机（IDN/punycode）与含空格路径需要完整 WHATWG 解析器，`URI` 做不到 | 存疑 | B191/B197 维持（清它＝写完整解析器，权重 1 不成比例） |
| D38 `mcp-auth.json` 字节形态 | `oauth.ts:116-130` 未变；pi 的 `JSON.stringify` 保留服务端原始键序与显式 `null`，java record 模型无法无损保留 | 存疑 | B193 维持（语义等价；字节不可无损对齐） |
| D40 回调页 HTML/CSS | `oauth/callback.ts` 未变；共享 `OAuthPage` 与 pi `ai/utils/oauth-page.ts` 仅 `<style>` 缩进/折行差，计算值同 | 存疑 | B198 维持（字节对齐要改写共享模板，波及 ai 侧） |
| D41 redirect TTY 判定 | `oauth.ts:396-470` 未变；`System.console()` 与 `isatty` 差异仍在 | 存疑 | B212 维持（纯 Java 无 isatty 对位） |
| D43 `cimd` 身份 | `oauth.ts:246-330` 新增 auto，但托管文档仍是 pi.dev；pi-java 的 `auto` 依改名规则恒回落 DCR，与 pi 的 rebrand 分支一致；显式 cimd 仍以 pi 身份申请 | 存疑 | B192 维持（清它＝pi-java 自建域名托管文档，非代码问题） |
| D46 真 AS 夹具 | `test/oauth.test.ts:96-280` 仍有真实交换；java 缺口不变 | **缺失夹具 → W2c 关闭** | B217 销号 |
| F71 文案命名 | `cli.ts` 全文仍 `pi`／`.pi`；java 刻意改名 | 存疑 | B213 维持（清它＝放弃 pi-java 品牌改名） |

### 1.4 未变行复核口径

其余 68 行引用的 pi 文件不在 §0.2 的 diff 集合内，或集合内文件的对应代码块未被 diff 触及。实施时将按新锚点行号回填 map/07 证据列；本设计不重复抄录。例外：`index.ts`／`runtime.ts`／`cli.ts` 因删改导致的**行号位移**在回填时统一按新行号写。

### 1.5 账面

| 口径 | 旧锚点 | 新锚点重测（未实施） | 本包后（实测） |
|---|---:|---:|---:|
| Σ权重 | 129 | 131（+94/#95） | 131 |
| Σ(w×c) | 121.0 | 96.0 | **126.0** |
| 加权完成度 | 93.8% | 73.28% | **96.18%** |
| 未加权 | 74/89＝83.1% | 60/91＝65.9% | **81/91＝89.0%** |
| 对齐／存疑／缺失 | 74／14／1 | 60／12／19 | **81／10／0** |

复算口径：`docs/map/07-mcp.md` 逐行判定列与权重列；本包实施后回填。

## 2. 工作包设计

### W1 — structuredContent 任意 JSON（#47／#49／#50）

#### pi 事实

`packages/mcp/src/protocol/content.ts:64-70`（逐字）：

```ts
export interface CallToolResult {
	content: ContentBlock[];
	/** Any JSON value, including arrays and `null` (MCP 2026-07-28; earlier revisions allowed only objects). */
	structuredContent?: unknown;
	isError?: boolean;
	_meta?: Record<string, unknown>;
}
```

`packages/mcp/src/client.ts:148-153`（逐字，新锚点删除了 structuredContent 的对象校验）：

```ts
function validateCallToolResult(value: unknown): CallToolResult {
	if (!isObject(value) || (value.content !== undefined && !Array.isArray(value.content))) {
		throw new McpError(JSON_RPC_ERROR_CODES.invalidRequest, "Invalid MCP tools/call result");
	}
	return (value.content === undefined ? { ...value, content: [] } : value) as unknown as CallToolResult;
}
```

`protocol/content.ts:111-117`：`if (content.length === 0 && result.structuredContent !== undefined)` 时回退为该值的 JSON。

测试锚点 `test/client.test.ts:283-291`：`[[{id:1},{id:2}], null, "text", 3, false]` 全部接受并原样返回；`test/content.test.ts:27-33`：数组回退 `"[\n  1,\n  2\n]"`，`null` 回退 `"null"`。

#### pi-java 现状

- `CallToolResult.java:20`：`@Nullable Map<String, Object> structuredContent` → 数组/字符串直接反序列化失败。
- `McpClientValidators.java:94-98`：显式拒绝非对象。
- `McpContents.java:46`：`structuredContent() != null` 不区分“缺席”与“显式 null”。

#### 改动

1. `CallToolResult.structuredContent` 改为 `@Nullable JsonNode`：
   - 缺席 ⇒ Java `null`；显式 JSON `null` ⇒ `NullNode`。Jackson 的 `convertValue(Map → record)` 天然保持该区别。
   - 对象仍可读为 `ObjectNode`，数组为 `ArrayNode`；`McpResultConverter.withoutMeta` 的 `valueToTree(result)` 序列化不变。
2. 删除 `McpClientValidators.java:97` 的 structuredContent 校验。
3. `McpContents.toLlmContent` 保持 `!= null` 判断（现在 `NullNode` 非 null，语义等于 pi 的 `!== undefined`），写出 pretty JSON。
4. 更新直接构造 `CallToolResult` 的测试（6 处）为 `McpJson.mapper().valueToTree(...)`；新增数组／`null`／标量夹具。

#### 关键签名

```java
public record CallToolResult(
        @Nullable List<McpContentBlock> content,
        @Nullable JsonNode structuredContent,
        @JsonProperty("isError") @Nullable Boolean error,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) { }
```

【风险】公开 SDK 记录类型换组件类型，`pi-java-coding-agent` 的 2 处测试与 1 处构造需同步；`McpContents` 由 `Map` 改 `JsonNode` 后 `McpJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(JsonNode)` 行为与 pi 的 `JSON.stringify(value, null, 2)` 一致（空对象 `{}`、空数组 `[]`）。

### W2 — OAuth URL 白名单与 issuer 校验（#32／#33／#34／#36／#42）

#### pi 事实

`packages/mcp/src/oauth/types.ts:126-136`（逐字）：

```ts
function safeUrl(value: unknown, name: string): string {
	const text = requiredString(value, name);
	// URL parsing throws a `TypeError`, which discovery reserves for network failures.
	if (!URL.canParse(text) || !isHttpUrl(new URL(text))) throw new Error(`Invalid ${name}`);
	return text;
}

export function isHttpUrl(url: URL): boolean {
	return url.protocol === "http:" || url.protocol === "https:";
}
```

`oauth/flow.ts:189-191`：

```ts
const url = new URL(metadata?.authorization_endpoint ?? new URL("/authorize", authorizationServerUrl));
// Callers open this URL with the platform URL handler, which also launches files and custom protocols.
if (!isHttpUrl(url)) throw new Error(`Invalid authorization endpoint ${url.href}`);
```

`oauth/flow.ts:385-392`（RFC 9207；无 metadata 时拒绝任何 `iss`）：

```ts
const iss = options.iss;
if (iss !== undefined || metadata?.authorization_response_iss_parameter_supported) {
	if (iss !== metadata?.issuer) throw new OAuthIssuerMismatchError(metadata?.issuer, iss);
}
```

`oauth/errors.ts:13-27`：`expected`／`received` 都允许 `undefined`，格式化时 `undefined → "none"`。

#### pi-java 现状

- `OAuthMetadataParsers.java:171-189`：`safeUrl` 只排除 `javascript:/data:/vbscript:`，接受 `file:`／`search-ms:`。
- `OAuthEndpoints.java:90-107`：`startAuthorization` 不对授权端点做协议检查。
- `OAuthFlow.java:168-179`：`metadata == null` 直接放行。
- `OAuthIssuerMismatchError.java:16`：`expected` 非空且格式化不对 `null` 用 `none`。

#### 改动

1. `OAuthMetadataParsers.safeUrl` 改为“可解析且 `http`/`https`”，并新增 `public static boolean isHttpUrl(URI)`；保护 `authorization_servers`／`resource`／`issuer`／`authorization_endpoint`／`token_endpoint`／`registration_endpoint` 全部字段。
2. `OAuthEndpoints.startAuthorization` 在解析 `base` 后：`if (!isHttpUrl(base)) throw new IllegalStateException("Invalid authorization endpoint " + base);`
3. `OAuthFlow.checkIssuer`：`metadata == null` 不再提前返回；`iss != null || (metadata != null && issSupported)` 分支内比较 `Objects.equals(iss, metadata == null ? null : metadata.issuer())`。
4. `OAuthIssuerMismatchError`：`expected` 改 `@Nullable String`；格式化 `expected == null → "none"`，其余 `quote`（与 `JSON.stringify` 的引号/转义等价）。

#### 测试

- 元数据：`file://attacker/share/x.exe`、`search-ms:query=x`、`ms-msdt:/id` 均报 `Invalid authorization endpoint`（解析期与 `startAuthorization` 两路）。
- 无 metadata 且有 `iss` ⇒ `OAuthIssuerMismatchError`，`expected()` 为 `null`，文案含 `expected none`。
- 反向变异：把 `isHttpUrl` 改回“只排除三 scheme” ⇒ 上述测试全红。

### W2c — 本地授权服务器端到端夹具（关闭 B217／#46）

#### 目标

用 JDK `com.sun.net.httpserver.HttpServer`（或既有测试 HTTP 夹具）启动一个**真实**授权服务器：

1. `GET /.well-known/oauth-protected-resource/mcp` 返回 `resource`／`authorization_servers`／`scopes_supported`；`GET /.well-known/oauth-authorization-server` 返回 RFC 8414 元数据（`issuer`、`authorization_endpoint`、`token_endpoint`、`registration_endpoint`、`response_types_supported:["code"]`、`token_endpoint_auth_methods_supported:["none"]`、`code_challenge_methods_supported:["S256"]`）。
2. `POST /register` 返回 DCR 客户端（echo `redirect_uris`，`client_id:"test-client"`）。
3. `GET /authorize` 记录 `code_challenge` 并把 `code`＋`state` 拼回 `redirect_uri` 后 302（等价于“用户在浏览器里授权”）。
4. `POST /token` 校验 `grant_type=authorization_code`、`code`、`code_verifier` 与 PKCE 挑战的 SHA-256 一致，返回 `access_token`／`refresh_token`；`refresh_token` 分支亦覆盖。
5. MCP 端点 `/mcp` 对无/错 token 回 401，对正确 token 正常应答。

#### 覆盖

- `McpSignIn.signIn` 全链路（discovery → DCR → PKCE 授权 → 回调 → 交换 → 保存 tokens）成功。
- 错误分支：错误 `state`、错误 `iss`（构造一个带 `iss` 的响应）、`code_verifier` 不匹配。
- `--timeout` 秒数文案（真实夹具下也可断言 `300 seconds`）。

【风险】loopback 端口占用与 Windows 防火墙：沿用 `OAuthCallbackServerTest` 的 free-port 手法；夹具失败即测试失败（不静默跳过）。

### W3 — transport send signal（#19／#25）

#### pi 事实

`transports/transport.ts:9-18`（逐字）：

```ts
export interface McpTransportSendOptions {
	/**
	 * Aborted once the sender no longer waits for a reply (the request was cancelled, timed out, answered,
	 * or the client closed). A transport must not dispatch the message after that, for example when
	 * retrying it after an authorization refresh. It does not undo a message already dispatched.
	 */
	signal?: AbortSignal;
}

export interface McpTransport {
	start(): Promise<void>;
	send(message: JsonRpcMessage, options?: McpTransportSendOptions): Promise<void>;
	close(): Promise<void>;
	...
```

`client.ts:582`：请求落定时 `entry.sendController.abort()`；`client.ts:439-441`：`transport.send(message, { signal: entry.sendController.signal })`。

`streamable-http.ts:295-305`：

```ts
private async authorizedFetch(method, init, signal?) {
	for (let attempt = 0; ; attempt++) {
		const { headers, token } = await this.headers(init.headers);
		if (signal?.aborted) throw new McpAbortError();
		...
	}
}
```

#### pi-java 现状

`McpClient.java:199-213` 在创建 Pending 后同步 `t.send(wire)`；`StreamableHttpTransport.authorizedFetch` 在 401 认证恢复后直接重试。

#### 改动

1. `McpTransport` 增加 Java 化重载：

   ```java
   /** Options of one send (pi McpTransportSendOptions). */
   public record McpTransportSendOptions(@Nullable AbortSignal signal) {}

   void send(Map<String, Object> message) throws Exception;

   default void send(Map<String, Object> message, McpTransportSendOptions options)
           throws Exception {
       send(message);
   }
   ```

   `StreamableHttpTransport` 覆写两参版本；一参版本委托 `new McpTransportSendOptions(null)`。
2. `McpClient.Pending` 增加 `AbortSignal sendController`；`requestInternal` 创建 `AbortSignal.create()`，请求时 `t.send(wire, new McpTransportSendOptions(entry.sendController()))`；`removePendingEntry` 里 `entry.sendController().abort()`（请求成功、取消、超时、连接关闭统一走该路径）。
3. `StreamableHttpTransport.authorizedFetch` 在每轮 `buildHeaders` 后检查 `options.signal().isAborted()` ⇒ 抛 `McpAbortError`；不把该信号传给 `fetch.execute`（`pi` 也只做派发前检查，不中止已发请求）。

#### 测试（照 pi `streamable-http.test.ts:345-405`）

- 三个并发 `tools/call` 同时触发一个共享认证恢复；恢复期间一个 abort、一个 200ms 超时、一个保留。恢复完成后：只有“保留”被派发（服务端执行记录只含 `kept`），刷新只发生 1 次。
- 变异探针：删掉 `authorizedFetch` 的 `signal` 检查 ⇒ aborted/timed-out 请求被派发，测试红。

【风险】`McpClient.java` 现 494 行，加组件/导入后逼近 500。若超过，按“一件一类”先例抽出 `McpClientSends`（send 控制器的创建/中止/派发）。实施时以 `awk END{NR}` 实测。

### W4 — 连接不再枚举资源；状态与 `mcp list` 形状（#29／#62／#67／#73）

#### pi 事实

`runtime.ts:139-160`（逐字）：

```ts
	/**
	 * Whether the server offers resources, which the resource tools list and read on demand. They are
	 * not listed at connect: listing every resource can take long (a large drive, say).
	 */
	hasResources = false;
```

`runtime.ts:359-390`（连接期只列工具）：

```ts
			client.onNotification("notifications/tools/list_changed", () => {
				void this.refreshTools(client);
			});
			...
			const tools = client.serverCapabilities?.tools ? await client.listTools() : [];
			...
			this.hasResources = client.serverCapabilities?.resources !== undefined;
```

`index.ts:234-252`（逐字状态文案）：

```ts
		case "connected": {
			const { tools, hasResources } = connection;
			return `connected · ${tools.length} tool${tools.length === 1 ? "" : "s"}${hasResources ? " · resources" : ""}`;
		}
```

`cli.ts:100-108`＋`465-545`：`resources?: true`；JSON 里没有 `resourceTemplates`；人类行 `  offers resources`。

#### pi-java 现状

- `McpConnector.java:79-94`：`fetchResources` 拉 resources＋templates；`McpConnector.java:71-73,168-181` 订阅并刷新。
- `McpServerConnection.java:50-52,116-124`：公开 `resources()`／`resourceTemplates()` 列表。
- `McpExtensionState.java:159-164`／`McpServerPresentation.java:168-172`：按数量拼文案。
- `McpListCommand.java`：报告记录带 `resources`／`resourceTemplates` 整数。

#### 改动

1. 删除 `McpConnectionSupport.fetchResources`／`ResourceLists`（仅连接器使用）。
2. `McpConnector.connectOnce` 只 `listTools`；删除 resources `list_changed` 订阅与 `refreshResources`。
3. `McpServerConnection` 删除 `resources`／`resourceTemplates` 字段与访问器；保留 `hasResources`（capability）。
4. 状态文案改为 `hasResources` 布尔：`connected · N tools[ · resources]`（`McpExtensionState`＋`McpServerPresentation`，后者签名由 `int resourceCount` 改 `boolean hasResources`）。
5. `McpListCommand.ServerReport`：`@Nullable Integer resources` → `@Nullable Boolean resources`（写 `true`），删除 `resourceTemplates`；JSON 用 `node.put("resources", true)`；人类行 `  offers resources`。
6. 资源工具面不变：`McpResourceTools` 仍按需分页读取，`McpServerConnection.resourcesPage/…` 保留。

#### 测试

- 连接夹具记录收到的方法：`initialize`／`tools/list`，**没有** `resources/list` 或 `resources/templates/list`。
- 服务器发 `notifications/resources/list_changed` ⇒ 不触发任何列表请求、不触发 onTools。
- `mcp list --json` 含 `"resources": true` 且无 `resourceTemplates`；人类输出含 `offers resources`。
- 状态文案断言 `connected · 2 tools · resources`。
- 变异探针：恢复连接期 `resources/list` ⇒ 方法记录断言红。

### W5 — `clientRegistration: auto` 决策树与 CLI 选项（#12／#43／#65）

#### pi 事实

`core/mcp-servers.ts:112-146`（逐字）：

```ts
	/**
	 * How pi identifies itself without `clientId`. `auto` (default): pi's Client ID Metadata Document on
	 * pi.dev when the authorization server supports it for public clients, otherwise dynamic client
	 * registration; with `clientName` or a `callbackUrl` other than `/callback` on localhost or 127.0.0.1,
	 * always dynamic client registration. `cimd`: always the document, and sign-in fails without support.
	 * `dcr`: always dynamic client registration.
	 */
	clientRegistration?: "auto" | "dcr" | "cimd";
```

```ts
export function isClientMetadataDocumentCallback(callbackUrl: string | undefined): boolean {
	if (callbackUrl === undefined) return true;
	const url = new URL(callbackUrl);
	return url.hostname !== "[::1]" && url.pathname === "/callback";
}
```

`mcp-servers.ts:203-212`（校验文案）：

```ts
	const registration = value.clientRegistration;
	if (registration !== undefined && registration !== "auto" && registration !== "dcr") {
		if (registration !== "cimd") return 'oauth.clientRegistration must be "auto", "dcr", or "cimd"';
		...
```

`oauth.ts:246-262`（逐字决策）：

```ts
function offersClientMetadataDocument(settings: McpOAuthSettings): boolean {
	const registration = settings.clientRegistration ?? "auto";
	if (registration !== "auto") return registration === "cimd";
	if (settings.clientId || settings.clientName || APP_NAME !== "pi") return false;
	return isClientMetadataDocumentCallback(settings.callbackUrl);
}
```

`oauth.ts:263-330`：`clientMetadataDocument` 不支持时返回 `undefined`；仅 `settings.clientRegistration === "cimd"` 时抛
`The authorization server does not support Client ID Metadata Documents for public clients; remove oauth.clientRegistration "cimd"`。

`oauth.ts:504-530`：`offersDocument` 取代 `cimd`；`prepare` 的 `keepClient` 改为
`stored.clientInformation ? registration !== "cimd" && registeredRedirectUrls(...).includes(redirectUrl) : offersDocument`。

`cli.ts:321-380`：`--oauth-client-registration <mode>` 加入 http-only 选项并写 `oauth.clientRegistration`。

#### pi-java 现状

`McpServerConfigs.java:286-305` 只接受 dcr/cimd；`McpOAuthSettings.Registration` 只有 DCR/CIMD；`McpClientMetadataDocuments.create` 不支持即抛；`McpSignIn` 用 `cimd` 布尔做回调附加路径与 `keepClient`；`McpAddRemoveCommand` 无该 CLI 选项。

#### 改动

1. `McpOAuthSettings.Registration` 加 `AUTO`；`registrationOf("auto") → AUTO`。
2. `McpServerConfigs.validateOAuth` 接受 `"auto"`；错误文案逐字改 `oauth.clientRegistration must be "auto", "dcr", or "cimd"`；cimd 的 callback 检查改用新增的 `isClientMetadataDocumentCallback(@Nullable String)`（放在 `McpServerConfigs`，`public static`）。
3. `McpClientMetadataDocuments.create` 返回 `@Nullable ClientMetadataDocument`（不支持 ⇒ `null`）。
4. `McpAuthProviders.createProvider` 的文档 lambda：`offersDocument(settings, clientName)` 为真才挂；lambda 内 `document == null && registration == CIMD` 时抛原文案。
5. 新增 `offersClientMetadataDocument(settings, appName)`（`runtime/McpOAuthSettings` 或 `McpAuthProviders` 静态）：
   - `null`/`AUTO`：`clientId` 或 `clientName` 非空 ⇒ false；`appName != "pi"` ⇒ false；否则回调检查。
   - `CIMD` ⇒ true；`DCR` ⇒ false。
6. `McpSignIn`：`cimd` 布尔替换为 `offersDocument`（回调附加路径与 `prepare` 的 `keepClient` 同形改）。
7. `McpAddRemoveCommand.add`：新增 `oauth-client-registration`（VALUE，http-only），写入 `oauth.clientRegistration`。
8. `McpCliHelp.HELP` 增加该选项行。

【重要】pi-java 的 `APP_NAME` 是 `pi-java`，所以 `auto` 在本仓库**恒回落 DCR**（与 pi 对 rebranded build 的规则一致）。这是忠实移植，不是“不实现 auto”。显式 `cimd` 仍保留（身份为 pi 的托管文档，见 B192）。

#### 测试

- 校验：`auto` 通过；`AUTO` 之外的非法值报新文案；`cimd + clientId` 报组合错误；`cimd + callbackUrl=/other` 报 callback 错误；`cimd + [::1]` 报错。
- 决策：`offersClientMetadataDocument` 四臂（auto＋appName=pi＋合法回调 ⇒ true；appName=pi-java ⇒ false；有 clientName ⇒ false；dcr ⇒ false；cimd ⇒ true）。
- `McpSignIn`：auto（默认）下不请求 metadata document，直接走 DCR；显式 cimd 且服务器不支持 ⇒ 原文案。
- CLI：`mcp add … --oauth-client-registration auto` 写入 `mcp.json`；stdio 服务器传该选项报错。

### W6 — `mcp login/logout -l`、拼错建议、帮助文案（#68／#71）

#### pi 事实

`cli.ts:220-265`（逐字关键分支）：

```ts
			const parsed = parseOptions(
				rest,
				command === "login" ? { local: "flag", timeout: "value" } : { local: "flag" },
				error,
			);
			...
			if (!name || extra.length > 0) {
				error(`Usage: ${APP_NAME} mcp ${command} <server> [-l]\n${HELP_HINT}`);
				return 1;
			}
			const entry = loaded.servers.find((server) => server.name === name);
			if (!entry) {
				const names = loaded.servers.map((server) => server.name);
				const suggestion = names.find((candidate) => editDistance(candidate, name) <= 2);
				error(
					`No MCP server named "${name}".${suggestion ? ` Did you mean "${suggestion}"?` : ""}${untrustedNote ? ` ${untrustedNote}` : ""} Configured: ${names.join(", ") || "none"}.`,
				);
				return 1;
			}
			if (parsed.values.has("local") && entry.scope !== "project") {
				error(`MCP server "${name}" is defined in ${entry.source}, not in the project; omit --local.`);
				return 1;
			}
```

`cli.ts:276-287`：Levenshtein 距离，取首个距离 ≤2 的已配置名。

`cli.ts:33-76`：usage 行 `login <server> [-l] [--timeout <seconds>]`、`logout <server> [-l]`；组标题 `Options for add, remove, login, and logout`；`--timeout` 说明 `Time limit for the whole sign-in (default: 300)`。

`cli/args.ts:294`：`${APP_NAME} mcp <command>             Manage MCP servers`。

#### 改动

1. `McpCommand.loginOrLogout`：known map 按命令带上 `local`（login 另有 `timeout`）；usage 错误加 `[-l]`；找不到名字时先算建议并插在 untrustedNote 之前；`-l` 且 `entry.scope() != PROJECT` 时报 `… is defined in <source>, not in the project; omit --local.`。
2. 新增 `private static int editDistance(String a, String b)`（与 pi 同算法）。
3. `McpCliHelp.HELP` 逐字更新（usage 行、组标题、`--timeout` 描述、新增 `--oauth-client-registration`）。
4. `cli/HelpText` 主帮助 Commands 段补 `  pi-java mcp <command>          Manage MCP servers`。其余主帮助文案（session 参数等）属 L5，不在本包。
5. `McpCliOptions` 的错误文案用用户输入的原词（`typed`）而非别名归一后的名字（如 `-x` 报 `Unknown option -x` 而不是 `--x`）。

#### 测试

- `mcp login docs -l`（docs 为 global）⇒ 精确文案；project 作用域通过。
- `mcp login docc`（配置有 `docs`）⇒ `Did you mean "docs"?`。
- `mcp logout docs -l` 同规则。
- HELP 文案逐字断言（含 `[-l]`、新选项、timeout 描述）。
- 主帮助含 mcp 行。

### W7 — `reachesTools` 与 namespace aliases（#77／#81／#82／#94）

#### pi 事实

`core/extensions/types.ts:635-648`（逐字）：

```ts
	/**
	 * For tools that reach other tools (such as `codemode`, which calls them, and `tool_search`, which
	 * loads them): which tools a call with `input` may reach. `"all"`, or the words of the input that
	 * may name them, where a word refers to a tool or namespace whose name it contains, so words may
	 * over-match. Extensions that register tools late, such as MCP servers that are still connecting,
	 * use it from their `tool_call` handler to finish registering those tools before the call runs.
	 */
	reachesTools?: (input: Record<string, unknown>) => "all" | readonly string[];
```

`tool-search/tool.ts:320-323`：`reachesTools: () => "all"`。

`index.ts:224-232`：

```ts
function reachesServer(reach: "all" | readonly string[], server: string): boolean {
	if (reach === "all") return true;
	const namespace = mcpNamespace(server);
	return reach.some((word) => word.includes(namespace));
}
```

`index.ts:505-515`：只认 `tool.name === CODEMODE_TOOL_NAME/TOOL_SEARCH_TOOL_NAME && tool.reachesTools`。

`index.ts:1186-1207`：`const reach = tool.reachesTools?.(event.input);` 非空 ⇒ 按 `reachesServer` 过滤；否则资源工具全等、直调等属主。

`index.ts:390-396`：namespace 加 `aliases: [server]`。

`core/extensions/types.ts:555-570`：`ToolNamespace.aliases?: readonly string[]`。

#### pi-java 现状

`AgentTool` 无 `reachesTools()`；`ToolNamespace` 无 `aliases`；`ToolSearchTool` 无 `reachesTools`；`McpExtension.onToolCall` 用资源工具名集＋tool_search schema 引用判等＋属主；`McpLifecycle.ensureDiscoveryActive` 同 schema 判等；`McpToolRegistrar.namespaceOf` 只建 name/description/instructions。

#### 改动

1. agent-core `ToolNamespace` 加组件 `@Nullable List<String> aliases`（记录序按 pi：`name, aliases, description, instructions`）。
2. agent-core 新增 sealed `ToolReach`：`record All()`／`record Words(List<String>)`；`AgentTool` 加默认方法（对应 pi 的**函数属性**，不是“按入参求值的方法”）：
   `default @Nullable Function<Map<String, Object>, ToolReach> reachesTools() { return null; }`。
3. `ToolSearchTool` 覆写 `reachesTools() → input -> new ToolReach.All()`。
4. `McpExtension.onToolCall`：
   - `var reachFn = called.reachesTools(); var reach = reachFn == null ? null : reachFn.apply(event.input())`；有 reach ⇒ `readyServers.filter(s -> reachesServer(reach, s.entry.name()))`；
   - 否则资源三工具 ⇒ 全部；否则属主服务器。
5. `McpLifecycle.ensureDiscoveryActive`：`hasToolSearch = registered != null && registered.reachesTools() != null`（只查函数存在性，不调用——与 pi 的 `tool.reachesTools` 属性判定逐字同形）。codemode 臂维持 R5 面外。
6. `McpToolRegistrar.namespaceOf` 传入 server 名并写 `aliases = List.of(server)`。
7. 更新 `ToolNamespace` 的 5 处构造/断言。

#### 关键签名

```java
public sealed interface ToolReach {
    record All() implements ToolReach { }
    record Words(List<String> words) implements ToolReach { }
}

default @Nullable Function<Map<String, Object>, ToolReach> reachesTools() { return null; }
```

#### 测试

- 第三方注册同名 `tool_search` 但无 `reachesTools` ⇒ 不自动激活；内置 `ToolSearchTool` ⇒ 激活（现有 `ToolSearchMcpActivationTest` 扩展）。
- `tool_call` 等待：`ToolReach.Words(["mcp__docs"])` ⇒ 等 docs；`All` ⇒ 等全部；无 reach 的资源工具 ⇒ 全部；直调 ⇒ 属主。
- namespace 断言 `aliases=["docs"]`（`McpToolDefinitionTest`／`McpManagerActionsTest` 相关）。
- 变异探针：把 `reachesServer` 的 `word.includes(namespace)` 改成 `equals` ⇒ 等待过滤测试红（覆盖“过匹配”语义）。

### W8 — `mcp.json` UTF-8 BOM（#95）

#### pi 事实

`extensions/mcp/config.ts:92-100` 与 `229-232`：

```ts
	parsed = JSON.parse(stripBom(readFileSync(path, "utf8")));
	...
	const text = existsSync(path) ? stripBom(readFileSync(path, "utf8")) : undefined;
```

`utils/text.ts` 的 `stripBom` 只剥开头的 `U+FEFF`。

#### 改动

`McpConfig.readConfigFile` 与 `editMcpServers` 读文件后剥 BOM（模块内私有 `stripBom`，常量 `"\uFEFF"`，理由同 `SettingsManager` 的注释：避免源码内嵌不可见字符）。写入路径不变（仍按探测缩进写，不写 BOM）。

#### 测试

- 带 BOM 的 `mcp.json` 可加载（global 与 project 两路）。
- 带 BOM 的文件经 `mcp add`／`update`／`remove` 写回后仍可加载，且 BOM 被剥（写出无 `U+FEFF`）。
- 变异探针：去掉 `stripBom` ⇒ 加载报错。

### W9 — in-memory transport 进入产品源集（#20）

#### pi 事实

`packages/mcp/src/testing/index.ts`（逐字）：

```ts
export { createInMemoryTransportPair, InMemoryTransport } from "../transports/in-memory.ts";
```

`in-memory.ts:15-43`：`start()` 置 `started`；`send` 要求双方已 `start`；`close()` 级联关闭对端；`emitError` 覆写公开。

#### 改动

1. 把 `InMemoryTransport`／`InMemoryTransports` 从 `src/test` 移到 `src/main` 的 `com.pijava.mcp.transport`（包名不变，测试引用不动）。
2. 补语义：
   - `started` 标志；`send` 要求自身与 peer 已 `start`，否则 `McpConnectionClosedError`（pi 对 peer 未连的文案 `In-memory MCP peer is not connected`）。
   - `close()` 先 `emitClose()` 再关闭 peer（幂等）。
   - `emitError` 改 `public`（测试模拟传输错误）。
3. Java 化：不提供独立 `testing` 模块，类放主源集即“发布面”；Javadoc 说明用途。

#### 测试

- 未 `start` 即 `send` ⇒ 连接关闭错误；只 `start` 一方 ⇒ peer 未连错误。
- `close()` 级联；重复 `close()` 幂等。
- 原有 `InMemoryTransportTest` 全部保留并补三例。

### W10 — 每工具 `CallToolResult` outputSchema 新形状（#59）

#### pi 事实

`tools.ts:104-176`（逐字核心）：

```ts
const CONTENT_BLOCK_SCHEMA = {
	type: "object",
	properties: {
		type: { enum: ["text", "image", "audio", "resource_link", "resource"] },
		text: { type: "string" },
		data: { type: "string" },
		mimeType: { type: "string" },
	},
	required: ["type"],
	additionalProperties: true,
};

/** Keywords whose values are data, so a `$ref` key in them is not a reference. */
const SCHEMA_DATA_KEYWORDS = new Set(["const", "enum", "default", "examples"]);
/** Keywords whose values map names to schemas, so their keys are names, not keywords. */
const SCHEMA_MAP_KEYWORDS = new Set([
	"properties", "patternProperties", "dependentSchemas",
	"dependencies", "$defs", "definitions",
]);

function relocateSchema(schema: unknown, pointer: string): unknown {
	if (Array.isArray(schema)) return schema.map((item) => relocateSchema(item, pointer));
	if (typeof schema !== "object" || schema === null) return schema;
	const relocated: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(schema)) {
		if (key === "$ref" && typeof value === "string" && (value === "#" || value.startsWith("#/"))) {
			relocated[key] = `${pointer}${value.slice(1)}`;
		} else if (SCHEMA_DATA_KEYWORDS.has(key)) {
			relocated[key] = value;
		} else if (SCHEMA_MAP_KEYWORDS.has(key) && typeof value === "object" && value !== null && !Array.isArray(value)) {
			relocated[key] = Object.fromEntries(
				Object.entries(value).map(([name, subschema]) => [name, relocateSchema(subschema, pointer)]),
			);
		} else {
			relocated[key] = relocateSchema(value, pointer);
		}
	}
	return relocated;
}

export function createMcpResultSchema(structuredContentSchema: Record<string, unknown> | undefined): TSchema {
	return {
		type: "object",
		properties: {
			content: { type: "array", items: CONTENT_BLOCK_SCHEMA },
			// Servers may send structured content without declaring a schema for it, and it may be any JSON value.
			structuredContent: structuredContentSchema
				? relocateSchema(structuredContentSchema, "#/properties/structuredContent")
				: {},
			isError: { type: "boolean" },
		},
		required: ["content"],
		// Fields newer protocol versions add.
		additionalProperties: true,
	} as unknown as TSchema;
}
```

#### 改动

`McpResultSchema.createMcpResultSchema` 按上式重写（LinkedHashMap 保序：content／structuredContent／isError）；新增私有 `relocateSchema(Object, String)` 与两个关键字集合；`content.items` 用 `CONTENT_BLOCK_SCHEMA`；`structuredContent` 恒存在；删 `_meta`；加 `additionalProperties: true`。

#### 测试

- 无 outputSchema 的工具：声明恰为 `{type:object, properties:{content:{…},structuredContent:{},isError:{type:boolean}}, required:[content], additionalProperties:true}`。
- 有 `$defs`＋`$ref:"#/$defs/Item"` 的 outputSchema：`structuredContent.$defs` 保留，`$ref` 改写为 `#/properties/structuredContent/$defs/Item`；`properties` 内的名字不被当作关键字。
- `enum`/`const`/`default` 里的 `$ref` 字符串不被改写。
- 变异探针：把 MAP_KEYWORDS 的 `properties` 当普通值递归 ⇒ “名字被误当关键字”断言红。

【模型可见】这些 schema 会进入模型工具声明，测试用 JSON 逐字断言。

### W11 — `/mcp` 视口捕获（#85）：登记 D217，转 L7

pi 的 `ui.ts:75-100` 让 `McpManagerView` 暴露 `capturesViewportInput`，TUI 层据此在 PageUp/PageDown 时不滚动转录。pi-java 的扩展组件是**文本级** `ExtensionComponent`（无该属性），TUI 适配层也没有“视口键路由”概念；且 `/mcp` 管理器本身挂在 overlay，按键已直达。

【决定】本包不实施，登记 **D217**：`/mcp` 管理器在 pi-java 无 `capturesViewportInput` 等价位，PageUp/PageDown 的菜单分页行为随 L7 TUI 的视口键路由一并设计。map/07 #85 记存疑 0.5。

【风险】L7 若改变 overlay 的按键路由，需要回来补 `/mcp` 菜单的分页选择；本包不预造 TUI 接口。

### W12 — 存疑清理（A4 口径修正＋A7 字节对齐）

> 复核后新增：这两条不需要新架构，属于“先前过于保守”的存疑，本包一并清掉。其余 10 条维持 0.5，理由见 §1.3 与 §2 末。

#### W12a — A4：未知键保留改判对齐（零代码）

pi 的两条可观察写路径：

- `mcp add`：`cli.ts:340-394` 用**全新** config 对象，`addMcpServerConfig` 整条覆盖 → 被替换条目的未知键**丢弃**。
- `/mcp` update：`config.ts:229-242` 用 `readFileSync` 的原始 JSON 树编辑 → 未知键**保留**。

pi-java 对应路径逐条同形：

- `McpAddRemoveCommand.add` → `McpConfig.add`（record 序列化，整条覆盖，丢未知键）。
- `McpManagerActions`／`McpServerOps` → `McpConfig.update`（原始树编辑，保留未知键）。

⇒ 行为等价，判定由「存疑」改为「对齐」，B181 销号。**无代码改动，只回填 map/07 与 docs/04。**

#### W12b — A7：`mcp.json` 写出字节对齐

pi 的写法（`cli.ts:340-394`）：

```ts
		config = {
			url,
			...(Object.keys(headers).length > 0 ? { headers } : {}),
			...(Object.keys(oauth).length > 0 ? { oauth } : {}),
		};
		...
	if (exposure !== undefined) config.exposure = exposure;
	...
	if (description !== undefined) config.description = description;
```

stdio 侧：`command`、`args`、`env`、`cwd`，然后 `exposure`、`description`。数字经 `JSON.stringify` 按 JS 数字规则输出（`1.0 → 1`，整数不带小数点）。

pi-java 现状：`McpConfig.add` 用 `valueToTree(McpServerConfig)`，键序是 record 组件序（`type, exposure, description, …, url, headers, oauth, auth`），数字是 Jackson 字面量（`1.0` 保持 `1.0`）。

改动：

1. `McpConfig.add` 增加一个按 pi 写出序构造 `ObjectNode` 的私有方法：
   - HTTP：`url, headers, oauth`，`oauth` 内按 `mcp-servers.ts:80-125` 的字段序（`clientId, clientSecret, callbackPort, callbackUrl, scope, clientName, clientRegistration, authServerMetadataUrl`），随后 `exposure, description`；其余已知键（`type/toolExposure/enabled/timeout/auth`）按 `type, toolExposure, enabled, timeout, auth` 追加（CLI `add` 不会产生它们，只为 Radius/未来调用保持确定序）。
   - stdio：`command, args, env, cwd`，随后 `exposure, description`。
2. `JsJson` 的标量写出：浮点节点若为有限整数（`1.0`、`2.0`）写 `longValue()`，与 `JSON.stringify` 对齐；其余浮点保持 Jackson 字面量。该修正同时作用于 `mcp-auth.json`（更接近 JS，不改变可解析性）。
3. 测试：`McpConfigWriterTest` 增加逐字节夹具（HTTP 带 headers/oauth/registration＋exposure/description；stdio 带 args/env/cwd），并断言 `"timeout": 1` 而不是 `1.0`。
4. 变异探针：把写序换回 `valueToTree` ⇒ 键序夹具红；去掉整数归一 ⇒ 数字夹具红。

⇒ A7 由存疑改对齐，B184 销号。

> 不把 `McpServerConfig` 改成携带原始 `JsonNode`：那会波及所有构造点与测试，只为 A4/A7 两个权重 1 的条目；上面的写序法在可观察路径上与 pi 同字节。

## 3. 测试计划（RED-first 汇总）

| 包 | 先红测试 | 变异探针 |
|---|---|---|
| W1 | `McpClientTest`：数组/`null`/字符串/数字/布尔 structuredContent 原样返回；`ToLlmContentTest`：数组与 `null` 回退 | 恢复对象校验 ⇒ 数组夹具红 |
| W2 | `OAuthMetadataParsersTest`／`OAuthEndpointsTest`：三非 HTTP scheme；无 metadata 的 `iss` | 恢复旧 `safeUrl` ⇒ 白名单测试红 |
| W2c | `McpSignInEndToEndTest`：本地 AS 全链路＋错误 state/iss/verifier | 跳过 PKCE 校验的 token 端点 ⇒ 成功用例红 |
| W3 | `StreamableHttpTransportTest`：共享刷新中取消/超时/保留 | 删派发前 signal 检查 ⇒ 执行记录含 aborted |
| W4 | `McpServerConnectionTest`：连接方法记录；`McpListCommandTest`：JSON/人类形状；状态文案 | 连接期 `resources/list` ⇒ 方法记录红 |
| W5 | `McpServerConfigsTest`／`McpSignInTest`／`McpAddRemoveCommandTest` | `auto` 走 CIMD 分支 ⇒ DCR 断言红 |
| W6 | `McpLoginCommandTest`／`McpCliHelpTest` | 去掉建议 ⇒ 文案断言红 |
| W7 | `ToolSearchMcpActivationTest`／`McpExtensionSessionTest`／`McpToolDefinitionTest` | `reachesServer` 用 equals ⇒ 过匹配用例红 |
| W8 | `McpConfigLoadTest`／`McpConfigWriterTest` | 去 BOM 剥离 ⇒ 加载红 |
| W9 | `InMemoryTransportTest` | 去 `started` 门 ⇒ 未启动发送用例红 |
| W10 | `McpToolDefinitionTest`／`McpResultSchema` 断言 | 不重定位 `$ref` ⇒ schema 断言红 |
| W12b | `McpConfigWriterTest`：键序＋整数字面量逐字节夹具 | 换回 `valueToTree` ⇒ 键序红；去整数归一 ⇒ 数字红 |

验证门槛（docs/08 §8）：

```bash
mvn test -pl pi-java-mcp,pi-java-agent-core,pi-java-coding-agent -am
mvn clean verify        # 收口：零失败、Checkstyle 零错误零警告、SpotBugs 零新增
```

日志落盘到仓库外或 `docs/` 临时文件，不放 `target/`。

## 4. 关键风险与控制

| 风险 | 控制 |
|---|---|
| `CallToolResult` 换组件类型波及上层 | 全仓 `grep structuredContent` 一次列清；`ToolResult.structuredContent` 仍是 `Object`，钩子链不变 |
| `McpClient.java` 触及 500 行闸门 | 实施时 `awk END{NR}` 实测；超限抽 `McpClientSends` |
| `auto` 在 pi-java 恒为 DCR，被误判“没实现” | 文档与 Javadoc 写明 rebrand 规则；用 `offersClientMetadataDocument` 四臂测试钉死 |
| 连接期不再列资源影响资源工具 | `McpResourceTools` 走 `resourcesPage/resourceTemplatesPage` 按需读取，连接器不再缓存；测试覆盖按需列表 |
| W7 改动跨 agent-core（L3 未开始） | 只加**默认方法**与记录组件，零行为变更；MCP 的消费在 coding-agent；批准即视为 L2→L3 的窄纵贯例外，并在 docs/08 §10 记一笔 |
| W2c 本地 AS 夹具在 Windows 上端口/防火墙不稳 | free-port＋重试一次；失败不静默跳过 |
| 旧 68 行“未变”证据过期 | 回填 map/07 时按新锚点行号逐行复核；行号位移处必须更新 |

## 5. 不在本包

- `/mcp` 的视口捕获与菜单分页（D217，转 L7）。
- `tool_search` 的 BM25／分词／排序新变化（`packages/coding-agent/src/extensions/tool-search/tool.ts` 在锚点间 +168/−?）：归 L5 map/04 G-8，map/07 #91 继续只作指针、权重 0。
- codemode 任何面（R5）。
- 主帮助中 session／export 等非 MCP 文案漂移（L5）。
- `PI_SELF_UPDATE_COMMAND`、`--export [output]`、`--continue` 文案（L5）。
- CIMD 自有托管（B192 维持；pi-java 无 pi.dev 域名）。
- 真实 AS 之外的 OAuth 行为（token 轮换、进程锁）不重写，只补测。
- **保留的 10 条存疑**（不清理的理由）：
  - 改名/品牌（A5/B182、F71/B213）：清理＝放弃 `pi-java` 品牌与 `.pi-java` 目录，属产品决策，不是代码对齐。
  - 托管/基础设施（D43/B192）：清理＝pi-java 自建域名托管 Client ID Metadata Document。
  - 原生能力（D41/B212）：纯 Java 无 `isatty` 对位。
  - 字节不可无损（D38/B193、D40/B198）：pi 保留服务端原始键序/显式 null；共享页与 ai 侧模板字节缩进不同、计算值相同。
  - 完整解析器（C30/B191/B197）：IDN/punycode 与含空格路径需要完整 WHATWG `URL`。
  - V8 版本相关文案（A6/B183）：`JSON.parse` 错误正文随 Node 版本变化，翻译表脆弱。
  - 异常分类口径（C27/B190）：TS `TypeError` 与 JDK `IOException` 无精确映射。
  - 跨层（D217）：TUI 视口键路由属 L7。

## 6. 审核要点（人只看这些）

1. **pi 事实是否取准**：§2 每处都有新锚点 `file:line` 与逐字代码；全部可用 `git show 56cae7823:<path>` 复核。
2. **范围是否合理**：W1–W12 是否都属 L2 mcp；W7 跨 agent-core 的默认方法是唯一跨层接口，是否批准这一“窄纵贯例外”。
3. **W2c 是否值得做**：关 B217 需要本地真实授权服务器夹具（~300 行测试），是否纳入本包。
4. **W12 存疑清理边界**：A4 只改判、A7 字节对齐；其余 10 条按 §5 列出的理由保留 0.5（改名/托管/原生能力/完整解析器/跨层）。是否接受“能清的清、不能清的不虚报”。
5. **W5 是否符合预期**：`auto` 在 pi-java 恒回落 DCR 是 pi 对重新命名构建的既定规则；确认这是“忠实对齐”而非缺功能。
6. **D217 处理**：视口捕获转 L7、#85 记存疑 0.5，是否接受。
7. **外部依赖**：无变更；不新增 Maven 依赖。
8. **账面口径**：重测 73.28% → 实施后 96.18% 的逐行复算是否认可（§1.5）。

> 批准后按 W1→W12 顺序实施（W2c 可与 W2 并行）；每完成一个可独立编译的单元即 commit，格式 `feat(mcp): …`／`feat(agent-core): …`／`feat(coding-agent): …`；收口跑全量 `mvn clean verify`，再回填 map/07、docs/04、docs/06 与 docs/08 §9。
