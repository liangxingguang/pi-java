# 包 37：MCP 对齐（B160）——第 10 包：MCP 工具与资源适配

> **状态：✅ 已闭环（2026-10-08，R-A）。**
> 总包路线见 `docs/28` §3（第 10 行）；上一包 `docs/36`（连接运行时）。
> 本包 = pi `extensions/mcp/tools.ts` 339 ＋ `resources.ts` 342 ＝ **681 行 TS**。
> 实施记录见 §10（两个提交 10a/10b，主源 10 新文件，测试 66）。

---

## 1. 范围边界

**做**：

- MCP 工具名生成（`mcp__<server>__<tool>` 净化＋超长哈希后缀）；
- 结果转换：内容块 → 模型可见的 text/image、**Codex 式中段截断**（20 KiB）、
  完整文本落临时文件、二进制资源落临时文件、`resource_link` 文案；
- 三个资源工具（`list_mcp_resources` / `list_mcp_resource_templates` /
  `read_mcp_resource`）＋ 它们的 JSON 载荷与输出 schema；
- `createMcpToolDefinition`（把一台服务器的一个 MCP 工具包成 `AgentTool`）。

**不做**（各自归属已核）：

| 不做 | 出处 | 归属 |
|---|---|---|
| `createMcpToolRenderers`（TUI 渲染：`Text`/`Container`/`VisualLinePreview`/`keyHint`） | `tools.ts:301-339` | **TUI 面**（`pi-java-tui`）：pi-java 的工具渲染走自己的路（**B65** 马克down 渲染器是死代码、**B47** `tool_execution_*` 语义取错源），pi 的 `ToolRenderers` 无对应物。登记 **B-新** |
| `exposure` / `namespace` / `outputSchema` / `annotations` 接到 `AgentTool` | pi `core/extensions/types.ts:509/527/587/1238` | **第 ⑫ 包**（激活路径）＋ **B161**（codemode）：见 §2.3 |
| 会话生命周期、`mcp_servers` 系统提示段、`after_tool` 钩子接线 | `index.ts` 1,225 | 第 ⑫ 包 |
| `/mcp` TUI 管理器、`pi mcp` CLI | `ui.ts` 252 · `cli.ts` 614 | 第 ⑪ 包 |
| codemode 脚本读 `structuredContent` | `extensions/codemode/execute.ts:310` | **B161/B177** |

---

## 2. 裁决

### 2.1 落点：**`pi-java-coding-agent`**（`com.pijava.coding.agent.extension.mcp`）

包⑧/⑨ 把 `extensions/mcp/*` 放进了 `pi-java-mcp`，本包**必须**分道：

- 适配器产出 `AgentTool`，它在 **`pi-java-agent-core`**（`agent/tool/AgentTool.java:22`）；
- 依赖方向是 `ai ← mcp`，**`pi-java-mcp` 看不见 agent-core**（`docs/28` §2 的模块图）；
- 反向（`agent-core ← mcp`）会新增一条模块边，而 pi 自己的分层是
  `coding-agent → agent-core` ＋ `coding-agent → @earendil-works/pi-mcp`。

⇒ 本包落 **coding-agent**，包根 `com.pijava.coding.agent.extension.mcp`（对应 pi 的
`src/extensions/mcp/`）。**备选**（拆两处：内容转换进 mcp、`AgentTool` 进 coding-agent）**不取**
—— pi 的 `tools.ts` 是一个文件，拆开会让「一个 pi 文件 = 两个 Java 模块」，且内容转换
唯一的消费者就是适配器。

⚠️ **连带后果**：`docs/map/04` 的「MCP 面在 `pi-java-mcp`」从此要加一句「工具/资源适配在
`coding-agent`」。回填时一并改。

### 2.2 `ToolResult` 补两个分量：`isError` ＋ `structuredContent`（**带兼容构造器**）

pi 的 `AgentToolResult` 有 `content/details/structuredContent/isError`；pi-java 的
`ToolResult`（`agent/tool/ToolResult.java:19-25`）是
`content/details/usage/terminate/addedToolNames` —— **`isError` 与 `structuredContent` 都没有**。

**`isError` 今天就可达，必须补**。pi 的 MCP 适配器把 `result.isError` 原样带出：

```ts
	return {
		content,
		details: { server, tool, ...(fullOutputPath ? { fullOutputPath } : {}) },
		structuredContent: scriptResult as unknown as JsonValue,
		...(result.isError ? { isError: true } : {}),
	};                                                  // tools.ts:228-233
```

pi-java 的模型是「工具抛异常，harness 兜成错误结果」（`PiToolRunner:164-167`：
`executed = PiLoopTools.createErrorToolResult(messageOf(e)); isError = true;`）。
若 MCP 服务器返 `isError:true` 时 Java 侧**改成抛**，模型拿到的是
`createErrorToolResult(消息)` —— **单块纯文本、内容被换掉**；pi 给的是**服务器原来那些
内容块**（可能含图片、可能多块）。⇒ 形状不同，必须补字段。

**`structuredContent` 今天不可达**（唯一消费者是 codemode 的 `execute.ts:310` ＋
`after_tool` 钩子改写 `types.ts:1439`）⇒ **留形状不接线**，与 `DeferredHandle`、
`toolResult.usage` 同一先例。

**兼容做法**（现有 21 个 `new ToolResult<>(…)` 站点零改动）：

```java
public record ToolResult<TDetails>(
    List<ContentBlock> content,
    TDetails details,
    UsageInfo usage,
    boolean terminate,
    List<String> addedToolNames,
    boolean isError,                 // pi AgentToolResult.isError
    Object structuredContent         // pi AgentToolResult.structuredContent（消费者未落）
) {
    /** 旧形状（成功结果、无结构内容）—— 21 个既有站点继续编译。 */
    public ToolResult(List<ContentBlock> content, TDetails details, UsageInfo usage,
                      boolean terminate, List<String> addedToolNames) {
        this(content, details, usage, terminate, addedToolNames, false, null);
    }

    /** 一个错误结果（内容照带）。 */
    public static <T> ToolResult<T> error(List<ContentBlock> content, T details) {
        return new ToolResult<>(content, details, null, false, List.of(), true, null);
    }
}
```

**`PiToolRunner` 的一行改动**（`PiToolRunner.java:164`）：

```java
            isError = executed.isError();     // 此前恒 false
```

⚠️ **钩子仍然可以改写**：`after_tool` 拿到的 `AfterToolOutcome(executed, isError)` 与 pi 的
`hookResult?.isError ?? isError`（`agent-session.ts:693`）同义 ⇒ 本包**不动钩子**。

### 2.3 `AgentTool` 不加 `exposure` / `namespace` / `outputSchema` / `annotations`

pi 的 `ToolDefinition` 有这四个（`types.ts:509/527/587/598`），pi-java 的 `AgentTool` 没有。
它们的消费者**全部未落**：

| 字段 | pi 的消费者 | pi-java 现状 |
|---|---|---|
| `exposure` | `getActiveTools`/`setActiveTools` 的激活门（`types.ts:505-509`） | 未落（第 ⑫ 包） |
| `namespace` | codemode 的 `describeNamespace()` | 未落（B161/B177） |
| `outputSchema` | codemode `execute.ts:310` 取 `structuredContent` | 未落（B161） |
| `annotations` | 权限扩展的确认门 | pi-java 无权限扩展面 |

⇒ **本包不动 `AgentTool`**。适配器**照 pi 计算**这四项（含 `toToolExposure` 的
`codemode → deferred` 映射）并放进一个**本包自有的载体** `McpToolSpec`，
第 ⑫ 包（或 B161 闭环时）再把它们接到激活路径 —— **不在本包凭空给 `AgentTool` 加零生产者字段**。
登记 **B-新**。

⚠️ **今天的暴露语义**（必须在 ⑫ 包前说清，否则会对模型多发工具）：
在 codemode（B161）与 tool-search（B162）都不存在时，`bin pi` 里**只有 `direct` 暴露的
MCP 工具是模型可见的**；`codemode`/`deferred`/`hidden` 三态都不可达。
本包提供判定数据，**由 ⑫ 包据 `McpServerConfigs.getMcpToolExposure` 过滤**（该函数包⑧ 已落）。

### 2.4 `truncateMiddle` 落 `TruncationUtils`（agent-core）

pi 把中段截断放在共享的 `core/tools/truncate.ts`（与 `truncateHead`/`truncateTail` 同文件）。
pi-java 的对应物是 `agent/tool/TruncationUtils.java`（168 行，已有
`DEFAULT_MAX_BYTES = 100_000` / `DEFAULT_MAX_LINES = 2000` / `formatSize` / `truncateHead` / `truncateTail`），
**独缺 `truncateMiddle`**。⇒ 补进去，不新建文件。

⚠️ **B158 与本包无关**：`TruncationUtils.DEFAULT_MAX_BYTES`（100_000 vs pi 的 50 KiB）是**内置工具**
的口径偏差；MCP 的 20 KiB 是**另一条**独立上限（`MCP_OUTPUT_MAX_BYTES`），
两者不可互相"顺手统一"。

### 2.5 临时文件：`McpOutputSaver` 可注入，默认写系统临时目录、mode 0600

```ts
export async function saveToTempFile(data: string | Uint8Array, extension: string): Promise<string> {
	const path = join(tmpdir(), `pi-mcp-${randomBytes(8).toString("hex")}${extension}`);
	// Results can carry private data, so only the user may read the file.
	await writeFile(path, data, { mode: 0o600 });
	return path;
}                                                       // tools.ts:71-76
```

Java：`McpOutputSaver` 是 `@FunctionalInterface`，默认实现
`McpOutputSaver.tempFiles()` —— `Files.createTempFile(dir, "pi-mcp-", ext)` 然后
`Files.setPosixFilePermissions(rw-------)`（非 POSIX 上忽略，同包⑨ `FileAuthJsonBackend` 的做法）。
**可注入**是为了夹具不写真实临时目录，也是 pi 的 `options.saveOutput` seam。

---

## 3. `tools.ts` 设计

### 3.1 工具名（`tools.ts:88-97`）

```ts
export function createMcpToolName(
	server: string, tool: string,
	isTaken: (name: string) => boolean = () => false,
): string {
	const name = `mcp__${server}__${tool}`.replace(/[^A-Za-z0-9_]/g, "_");
	if (name.length <= MAX_TOOL_NAME_LENGTH && !isTaken(name)) return name;
	const hash = createHash("sha256").update(`${server}\0${tool}`).digest("hex").slice(0, 8);
	return `${name.slice(0, MAX_TOOL_NAME_LENGTH - hash.length - 1)}_${hash}`;
}
```

Java 逐条：
- `MAX_TOOL_NAME_LENGTH = 64`（`:50`）；
- 净化只保留 `[A-Za-z0-9_]` —— **`-` 也变成 `_`**（`runtime.ts` 的
  `mcpNamespace` 只把 `-` 换 `_`，**两个函数不是一回事，别统一**）；
- 哈希输入是 `` `${server}\0${tool}` ``（**NUL 分隔**，不是冒号）；
- 后缀是 `"_" + hash`，前缀截到 `64 - 8 - 1 = 55` 个字符；
- `isTaken` 默认恒 false（`pi-java` 用 `ToolRegistry.toolNames()` 提供）。

### 3.2 结果上限与截断（`tools.ts:124-149`）

```ts
export const MCP_OUTPUT_MAX_BYTES = 20 * 1024;

export async function limitMcpContent(content, saveOutput = saveToTempFile) {
	const combined = textOf(content);
	const truncation = truncateMiddle(combined, MCP_OUTPUT_MAX_BYTES);
	if (!truncation.truncated) return { content };
	let fullOutputPath: string | undefined;
	let where: string;
	try {
		fullOutputPath = await saveOutput(combined, ".txt");
		where = `[Full output: ${fullOutputPath} (read it with offset/limit)]`;
	} catch (error) {
		where = `[Could not save the full output: ${error instanceof Error ? error.message : String(error)}]`;
	}
	const tokens = Math.ceil(truncation.totalBytes / 4);
	const text = `Warning: truncated output (original token count: ${tokens})\nTotal output lines: ${truncation.totalLines}\n\n${truncation.content}\n\n${where}`;
	return { content: [{ type: "text", text }, ...content.filter((block) => block.type === "image")], ...(fullOutputPath ? { fullOutputPath } : {}) };
}
```

⚠️ 六条承重：
1. **`textOf` 只取 text 块**（`:99-104`），用 `"\n"` 连接 —— **图片不参与**截断判定；
2. **`truncateMiddle` 的字节**是 UTF-8 字节（`truncate.ts:293`）；
3. 截断后**先放一张 text 块，再放原内容里的 image 块**（顺序固定）；
4. `tokens` 是 `ceil(totalBytes / 4)` —— **按字节估**，不是真分词；
5. 落盘失败**也**继续，只是 `where` 换成 `[Could not save the full output: <消息>]`，
   且**不设** `fullOutputPath`；
6. `totalLines` 来自 `truncateMiddle`（`splitLinesForCounting`：**末尾换行不算一行**，
   `truncate.ts:52-58`）。

### 3.3 内容块转换（`tools.ts:158-211`）

`blockToContent` 三个分支，逐字（`:171-202`）：

| 分支 | 行为 |
|---|---|
| `resource_link` | 一行文本：`[Resource <uri> "<title 或 name>"（(<mimeType>, <size>)）<: description><. Read it with read_mcp_resource (server "x")>]`；`size` 走 `formatSize`；**`readableResources` 为假时不给读法提示** |
| `resource` 且 `blob` 且 mimeType **不以 `image/` 开头** | base64 解码 → 文本型 MIME ⇒ **直接当文本**；否则落临时文件，文案 `[Binary resource <uri> (<mimeType 或 "unknown type">, <size>) saved to <path>]`，失败 `... could not be saved: <原因>]` |
| 其余（`text`/`image`/`audio`/图片型 blob） | 交 `toLlmContent([block])` |

`isTextMimeType`（`:165-169`）：取 `;` 前一段、trim、小写，命中
`text/` 前缀、`application/json`、`+json` 后缀、`+xml` 后缀。⚠️ **`;` 分割只取第一段**
（`mimeType.split(";", 1)[0]`）。

`extensionOf`（`:159-162`）：能解析成 URL 就取 `pathname`，否则整串；
正则 `/\.[A-Za-z0-9]{1,8}$/` 取**结尾扩展名**，不命中 ⇒ `.bin`。
⚠️ **扩展名含点**（`".txt"`），且**大小写原样**（`.PNG` 就是 `.PNG`）。

### 3.4 `convertMcpResult`（`tools.ts:214-234`）

```ts
	const converted = result.content.length > 0 ? await toModelContent(server, result.content, options) : toLlmContent(result);
	if (result.isError && textOf(converted) === "") converted.push({ type: "text", text: `MCP tool ${server}/${tool} returned an error` });
	const { content, fullOutputPath } = await limitMcpContent(converted, options.saveOutput);
	const { _meta: _ignored, ...scriptResult } = result;
	return { content, details: { server, tool, ...(fullOutputPath ? { fullOutputPath } : {}) }, structuredContent: scriptResult, ...(result.isError ? { isError: true } : {}) };
```

⚠️ 四条：
1. **`result.content.length > 0` 才走块转换**；空内容 ⇒ 交 `toLlmContent(result)`
   （它会退回 `structuredContent` 的美化 JSON，包① 已落）；
2. **错误结果且转换后没有任何文本** ⇒ **补一句** `MCP tool <server>/<tool> returned an error`。
   ⚠️ 判据是 `textOf(converted) === ""`，**不是** `content.length === 0`
   —— 只有图片块时（`textOf` 为空）**也会**补这句；
3. `structuredContent` 是**去掉 `_meta` 的整个 `CallToolResult`**；
   ⚠️ JS 的展开只删 `_meta` 一层，`content`/`isError` 都在；
4. `isError` 只在**真**时才出现在对象里（JS 的可选字段）。

### 3.5 `createMcpToolDefinition`（`tools.ts:260-299`）

```ts
	const title = tool.title ?? tool.annotations?.title;
	const annotations = toToolAnnotations(tool);
	const label = `${server}/${tool.name}`;
	return {
		name: options.name, label,
		description: tool.description?.trim() || title || `MCP tool ${tool.name} from server ${server}`,
		parameters: toParameters(tool.inputSchema),
		outputSchema: createMcpResultSchema(tool.outputSchema),
		exposure: toToolExposure(options.exposure),
		namespace: options.namespace,
		...(annotations ? { annotations } : {}),
		...createMcpToolRenderers(label),
		async execute(_toolCallId, params, signal, onUpdate) { … },
	};
```

`toParameters`（`:240-246`）：`type` 缺省补 `"object"`；`properties` 缺省补 `{}`
（有些 provider 拒收没有 `properties` 的对象 schema）。
`toToolAnnotations`（`:251-258`）：只取四个布尔 hint，**一个都没有就整体缺席**。
`createMcpResultSchema`（`:111-122`）：`{type:"object", properties:{content:{…}, structuredContent?, isError:{…}, _meta:{…}}, required:["content"]}`
—— `structuredContent` 只在工具有 `outputSchema` 时才出现。

`execute`（`:285-297`）：
```ts
			const client = await options.getClient();
			const result = await client.callTool(tool.name, (params ?? {}) as Record<string, unknown>, {
				signal, timeoutMs: options.timeoutMs,
				onProgress: (progress) => {
					const total = progress.total === undefined ? "" : `/${progress.total}`;
					const text = progress.message ?? `Progress ${progress.progress}${total}`;
					onUpdate?.({ content: [{ type: "text", text }], details: { server, tool: tool.name } });
				},
			});
			return convertMcpResult(server, tool.name, result, { readableResources: options.readableResources?.() });
```

⚠️ **进度文案**：`message` 有就用它，否则 `Progress <progress>[/<total>]`；
`total` 缺席时**没有斜杠**。`details` 只有 `{server, tool}`，**没有** `fullOutputPath`。
`getClient()` 是**每次调用都问一次**（连接掉了会重连，`runtime.ts:250`）——
本包持有的是 `Supplier<McpToolCaller>`，**不能在建工具时就把 client 固定下来**。

---

## 4. `resources.ts` 设计

### 4.1 三个工具与载荷形状

| 工具 | 输入 | 输出 key |
|---|---|---|
| `list_mcp_resources` | `{server?, cursor?}`（`additionalProperties: false`） | `{server?, resources[], nextCursor?, errors?}` |
| `list_mcp_resource_templates` | 同上 | `{server?, resourceTemplates[], nextCursor?, errors?}` |
| `read_mcp_resource` | `{server, uri}`（**都必填**） | `{server, uri, contents[]}` |

`LISTING_ERRORS` 的每项是 `{server, error}`（**都必填**，`resources.ts:82-90`）。

### 4.2 `list` 的两条路（`resources.ts:213-254`）

```ts
		const serverName = stringArgument(params, "server");
		const cursor = stringArgument(params, "cursor");
		const visible = (item: T) => !isMcpAppResource(item as …);
		if (serverName) {
			const server = findServer(serverName);
			const result = await page(server, cursor, { signal, timeoutMs: server.timeoutMs });
			return { server: server.name, [key]: result.items.filter(visible).map((item) => listed(server.name, item)),
				...(result.nextCursor === undefined ? {} : { nextCursor: result.nextCursor }) };
		}
		if (cursor) throw new Error("cursor can only be used when a server is specified");
		const servers = [...options.servers()].sort((a, b) => a.name.localeCompare(b.name));
		const results = await Promise.allSettled(servers.map((server) => all(server, { signal, timeoutMs: server.timeoutMs })));
		…
```

⚠️ **八条**：
1. `stringArgument`（`:170-175`）：`null`/`undefined` ⇒ `undefined`；**非字符串 ⇒ 抛**
   `<key> must be a string`；**空白串 ⇒ `undefined`**（`value.trim() || undefined`）；
2. **有 `server` ⇒ 只列一页**（带 `cursor`）；**无 `server` ⇒ 所有页**，
   且**此时 `cursor` 非空就抛** `cursor can only be used when a server is specified`；
3. **无 `server` 时按服务器名 `localeCompare` 排序**（有序发布，不是注册序）；
4. **无 `server` 时每台服务器各自 `allSettled`** —— 一台失败**不影响**其它，
   失败进 `errors[]`（`{server, error}`，`error` 走 `errorMessage`）；
5. **`errors` 只在非空时出现**；
6. **`nextCursor` 只在非 `undefined` 时出现**；
7. **每条列表项经过 `listed(server, item)`**（`:56-59`）：
   **去掉 `_meta` 与 `icons`**，前面加 `server` —— JS 的展开序是
   `{server, ...rest}`，即 **`server` 排在原有键之前**；
8. **`findServer` 找不到时的文案**（`:203-211`）：
   `MCP server "<name>" has no resources` ＋ 有可用服务器时
   `. Servers with resources: <逗号连接的名字>`。

### 4.3 `read_mcp_resource`（`resources.ts:304-339`）

```ts
			const blocks: ContentBlock[] = result.contents.flatMap((contents) => [
				...(result.contents.length > 1 ? [{ type: "text" as const, text: `${contents.uri}:` }] : []),
				{ type: "resource" as const, resource: contents },
			]);
			const converted = await toModelContent(server.name, blocks);
			const { content, fullOutputPath } = await limitMcpContent(converted.length > 0 ? converted : [{ type: "text", text: `Resource ${uri} is empty.` }]);
			const contents = result.contents.map(({ _meta: _ignored, ...rest }) => rest);
			return { content, details: { server: server.name, tool: READ_MCP_RESOURCE_TOOL, ...(fullOutputPath ? { fullOutputPath } : {}) },
				structuredContent: { server: server.name, uri, contents } };
```

⚠️ 四条：
1. **多于一条内容时，每条前面加一行 `<uri>:`**（目录类资源）；
2. **空内容 ⇒ 补 `Resource <uri> is empty.`**（判据是 `converted.length === 0`，
   与 §3.4 的 `textOf` 判据**不同**）；
3. `structuredContent.contents` **逐条去掉 `_meta`**；
4. **`server`/`uri` 缺失各自抛**（`server must be provided` / `uri must be provided`）。

---

## 5. 新增的共享件（agent-core）

### 5.1 `TruncationUtils.truncateMiddle`（`truncate.ts:292-314` 逐字）

```ts
export function truncateMiddle(content: string, maxBytes: number): MiddleTruncationResult {
	const buf = Buffer.from(content, "utf-8");
	const totalLines = splitLinesForCounting(content).length;
	if (buf.length <= maxBytes) return { content, truncated: false, removedChars: 0, totalBytes: buf.length, totalLines };
	// Continuation bytes (10xxxxxx) are not character starts.
	const isBoundary = (index: number) => index >= buf.length || (buf[index] & 0xc0) !== 0x80;
	let headEnd = Math.floor(maxBytes / 2);
	while (headEnd > 0 && !isBoundary(headEnd)) headEnd--;
	let tailStart = buf.length - (maxBytes - Math.floor(maxBytes / 2));
	while (tailStart < buf.length && !isBoundary(tailStart)) tailStart++;
	const head = buf.subarray(0, headEnd).toString("utf-8");
	const tail = buf.subarray(tailStart).toString("utf-8");
	const removedChars = Array.from(buf.subarray(headEnd, tailStart).toString("utf-8")).length;
	return { content: `${head}…${removedChars} chars truncated…${tail}`, truncated: true, removedChars, totalBytes: buf.length, totalLines };
}
```

⚠️ 五条承重：
1. **`buf.length <= maxBytes` ⇒ 不截断**（`<=`，等号算不截断）；
2. 头段取 `floor(maxBytes/2)`，尾段取 `maxBytes - floor(maxBytes/2)` ⇒
   **奇数时尾段多一个字节**；
3. 两端都要**退到 UTF-8 字符边界**（续字节 `10xxxxxx` 不是起点）；
4. `removedChars` 是**码点数**（`Array.from` 数的是 code point，不是 UTF-16 长度）；
5. 中间标记是 `…<n> chars truncated…`（**U+2026**，不是三个点）。

### 5.2 `splitLinesForCounting`（`truncate.ts:52-58`）

`content === ""` ⇒ `[]`；`split("\n")`；**以 `\n` 结尾时丢掉最后那个空元素**。

---

## 6. 文件清单（预估）

`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/extension/mcp/`：

| 文件 | 对应 pi | 预估行 |
|---|---|---:|
| `McpToolNames.java` | `tools.ts:49-97` | 70 |
| `McpToolDetails.java` | `tools.ts:58-63` | 40 |
| `McpOutputSaver.java` | `tools.ts:65-76` | 70 |
| `McpResultContent.java` | `tools.ts:99-211` | 230 |
| `McpResultConverter.java` | `tools.ts:213-234` | 110 |
| `McpResultSchema.java` | `tools.ts:106-122,236-258` | 150 |
| `McpToolSpec.java` | `tools.ts:260-299`（定义数据） | 90 |
| `McpToolDefinitions.java` | `tools.ts:45-47,260-299`（执行体） | 160 |
| `McpResourceTools.java` | `resources.ts:34-341` | 330 |

`pi-java-agent-core/src/main/java/com/pijava/agent/tool/`（改动）：

| 文件 | 改动 |
|---|---|
| `TruncationUtils.java` | ＋ `truncateMiddle` ＋ `MiddleTruncationResult` ＋ `splitLinesForCounting` |
| `ToolResult.java` | ＋ `isError` ＋ `structuredContent` ＋ 5 参兼容构造器 ＋ `error(...)` 工厂 |
| `harness/PiToolRunner.java` | 一行：`isError = executed.isError();` |

预计主源 **≈ 1,300 行 / 9 新文件**、测试 **≈ 1,100 行 / 7 夹具**。

---

## 7. 测试计划

`pi-java-coding-agent/src/test/java/com/pijava/coding/agent/extension/mcp/`：

| 夹具 | 覆盖 |
|---|---|
| `McpToolNamesTest` | 净化（`-`→`_`）、64 字符边界（`==` 不哈希）、`isTaken`、哈希输入的 NUL、前缀截断长度 55 |
| `TruncateMiddleTest`（agent-core） | `<=` 不截断、奇偶字节分配、UTF-8 边界（多字节字符不被劈开）、`removedChars` 按码点、`splitLinesForCounting` 的尾换行 |
| `McpResultContentTest` | `textOf` 只并文本、`resource_link` 四段文案（title/name 优先、size、description、readableResources 开关）、文本型 MIME 直出、二进制落盘成功/失败、`extensionOf` 五例、`isTextMimeType` 六例 |
| `McpResultConverterTest` | 空 content 回落 `toLlmContent`、`isError` ＋ 无文本 ⇒ 补句、`isError` ＋ 只有图片 ⇒ **也**补句、20 KiB 前后、落盘失败仍返回内容、`structuredContent` 去 `_meta` |
| `McpToolDefinitionsTest` | 描述三级回落、`toParameters` 补 `type`/`properties`、`toToolAnnotations` 只取四个布尔、`createMcpResultSchema` 的 `structuredContent` 可选、进度文案三态、`getClient` 每次调用 |
| `McpResourceToolsTest` | 三工具的 schema、`stringArgument` 三态、有/无 `server` 两路、`cursor` 无 `server` 抛、按名排序、`allSettled` 部分失败进 `errors`、`listed` 去 `_meta`/`icons` 且 `server` 在前 |
| `McpReadResourceTest` | 多条内容加 URI 行、空内容补句、`contents` 去 `_meta`、两个必填参数 |
| `ToolResultIsErrorTest`（agent-core） | 工具返 `isError=true` ⇒ 结果消息 `isError`、`after_tool` 钩子翻转、异常路不变 |

---

## 8. 变异探针计划（12 个）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `toToolExposure` 的 `codemode → deferred` 映射删掉 | 1 |
| M2 | 名字净化正则留 `-` | 1 |
| M3 | 64 字符判断改 `<`（等号也走哈希） | 1 |
| M4 | 哈希前缀截断用 `64 - 8` 而不是 `64 - 8 - 1` | 1 |
| M5 | `limitMcpContent` 的 `!truncation.truncated` 提前返回删掉 | 1 |
| M6 | `truncateMiddle` 的 `<=` 改 `<` | 1 |
| M7 | UTF-8 边界回退删掉 | 1 |
| M8 | `removedChars` 改用 `String.length()` | 1 |
| M9 | `extensionOf` 的 `.bin` 兜底删掉 | 1 |
| M10 | `isTextMimeType` 的 `+json` 后缀删掉 | 1 |
| M11 | `convertMcpResult` 的补句判据从 `textOf(...) == ""` 改成 `content.isEmpty()` | 1（只有图片那条） |
| M12 | `list` 无 `server` 时去掉按名排序 | 1 |
| M13 | `listed` 里 `server` 放到 rest **之后** | 1 |

⚠️ 包⑦/⑧/⑨ 的教训已内化：**夹具先写**（否则只能靠探针，而探针点由人挑）；
探针落地必须 grep 复核（CRLF 已 6 次让 `$` 锚点静默失效）。

---

## 9. 台账影响（预计新增）

| # | 条目 |
|---|---|
| B202 | `ToolResult` 的 `structuredContent` 无消费者（codemode B161 / `after_tool` 改写未落）—— 留形状不接线 |
| B203 | `AgentTool` 无 `exposure`/`namespace`/`outputSchema`/`annotations`：MCP 的这四项数据由 `McpToolSpec` 承载，激活路径是第 ⑫ 包 |
| B204 | MCP 工具/资源的 **TUI 渲染器**未移植（pi `createMcpToolRenderers`；pi-java 的工具渲染另成一路，见 B65/B47） |
| B205 | MCP 适配器落在 **`pi-java-coding-agent`**（不是 `pi-java-mcp`）—— 包⑧/⑨ 的落点分道，理由见 §2.1 |
| B206 | `isError` 语义从「抛异常」扩到「结果自带标志」：`ToolResult.isError` 与 `PiToolRunner` 的一行改动 |

回填 `docs/map/04` 的 G-1/G-4/G-5 与「模块归属」注（MCP 面跨两个模块）在本包闭环后一并做。

---

## 10. 实施记录

（待填：commit / 文件数 / 测试数 / 探针命中 / 发现）
## 10. 实施记录

**提交**（全部可独立编译）：

| 提交 | 内容 |
|---|---|
| `838b6235` | 10a：`ToolResult.isError`/`structuredContent`、`TruncationUtils.truncateMiddle`、`PiToolRunner` 一行（12 测试） |
| `4465d58e` | 10b：`extension/mcp` 十个文件 ＋ 五个夹具 ＋ pom 接 `pi-java-mcp`（54 测试） |

**规模**（实测）：主源 **10 新文件**（`com.pijava.coding.agent.extension.mcp` 9 ＋ `McpResourceSchemas` 拆出）＋ agent-core 三处改动；测试 **5 夹具 ＋ 1（`TruncateMiddleTest`）**。
`pi-java-agent-core` 627 → **639**，`pi-java-coding-agent` 348 → **402**。

**验证**：`checkstyle:check` 对本包新文件 **0 警告**；`spotbugs:check` 两模块 `BugInstance size is 0`；
全 reactor `mvn clean verify` 见 §10.5。

### 10.1 探针（设计稿 §8 的 13 个 + 1）

| # | 变异 | 命中 |
|---|---|---|
| M1 | `McpToolExposure.of` 的 `CODEMODE → DEFERRED` 改 `→ CODEMODE` | 恰 1 |
| M2 | 名字净化正则留 `-`（`[^A-Za-z0-9_-]`） | 恰 1 |
| M3 | 64 字符判断 `<=` 改 `<` | 恰 1 |
| M4 | `HASH_LENGTH` 8 改 9（前缀长度随之变） | 2 |
| M5 | `limit` 的 `!truncation.truncated()` 早退删掉 | 恰 1 |
| M6 | `truncateMiddle` 的 `<=` 改 `<` | 恰 1 |
| M7 | UTF-8 边界回退删掉 | 2（头尾各一条） |
| M8 | `removedChars` 改用 `String.length()` | 恰 1 |
| M9 | `extensionOf` 的 `.bin` 兜底改空串 | 恰 1 |
| M10 | `isTextMimeType` 的 `+json` 后缀删掉 | 恰 1 |
| M11 | `convertMcpResult` 的补句判据 `textOf(...).isEmpty()` 改 `converted.isEmpty()` | 恰 1 |
| M12 | `list` 无 `server` 时去掉按名排序 | 恰 1 |
| M13 | `listed` 里 `server` 放到 rest **之后** | 恰 1 |
| **M14** | `PiToolRunner` 的 `isError = executed.isError()` 改回 `false` | 恰 1 |

**M1/M11/M12/M13 一次跑、四个测试各红一条** —— 四个语义不同的点，没有互相遮蔽。

### 10.2 实测推翻设计稿的两处

1. **`ContentBlock.ImageContent(mediaType, data)` 的参数序与 pi 相反。**
   pi 的 `ImageContent` 是 `{type:"image", data, mimeType}`，pi-java 的 record 是
   `ImageContent(mediaType, data)` —— 而 mcp 侧的 `LlmContent.Image(data, mimeType)`
   又跟 pi 一致。**同一条转换链上有两个相反的序**，`McpResultContent.toBlock` 是
   唯一的换序点，夹具 `anImageBlobGoesToTheModelAsAnImage` 两侧都钉住（登记 **B208**）。

2. **错误结果的补句是 `append` 的。** 设计稿写「内容为空 ⇒ 补一句」，我第一版夹具
   假设补在**首位** —— 实际 `converted.add(...)` 排在原块**之后**。
   夹具改成「只有图片 ⇒ [Image, 补句]」（`anErrorResultWithOnlyAnImageAlsoGetsTheSentence`）。

### 10.3 SpotBugs：**重构掉，不是排除**

`McpResultSchema.valueOf(annotations, hint)` 返回 `@Nullable Boolean` ⇒
`NP_BOOLEAN_RETURN_NULL`（与 **B185** 同形的误报）。这次**没有**加 `spotbugs-exclude.xml`
条目，而是把那套「字符串键 + switch 查表」的机械移植改成**直接读四个字段**
—— 更短、更快、并且假阳性消失。
**⇒ B185 的排除机制不是唯一解；能靠重构消掉的就别进排除表。**

### 10.4 「第一个消费者」再撞两处（承接包⑨ B199/B201）

| 出处 | 事实 | 处置 |
|---|---|---|
| `pi-java-coding-agent/pom.xml` | **没有 `pi-java-mcp` 依赖** —— `docs/28` §2 的模块图早就画了 `coding-agent ← mcp`，pom 里一直没接（前九包都落在 mcp 模块内，没人需要这条边） | 本包接上（**B205**） |
| `McpResourceTools.java` | 写完 **525 行**，超 500 行/文件 | 拆出 `McpResourceSchemas`（四个 schema 常量）⇒ 428 行 |
| `TruncationUtils.formatSize` | 用**默认 locale** 格式化小数 —— `%.1f` 在德语区渲染 `1,5KB`，而 pi 的 `toFixed` 恒用 `.`；内置工具的提示文案一直带着这个隐患 | 加 `Locale.ROOT`（**B209**） |

### 10.5 全 reactor

`mvn clean verify`：**15/15 模块 SUCCESS，7:14**；模块汇总行求和 **3,058**（上包 2,992 ＋ 66 ＝
`agent-core` +12 ＋ `coding-agent` +54）。⚠️ 只数 `^[INFO]` 得 2,802 —— 另 256 条来自两个
`[WARNING]` 前缀模块（memory 已登记的坑）。

### 10.6 Java 侧踩到的一处语言坑

夹具里写 `var long = ...` **编译不过** —— `long` 是基本类型关键字，不可能是标识符
（pi 的 TS 里 `const long = ...` 没问题）。改 `veryLong`。
此类「名字在两种语言里合法性不同」的还有：`switch`/`final`/`class` 等。
