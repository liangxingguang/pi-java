# docs/35 — coding-agent 批 24 设计稿：内置 MCP 扩展本体（B221①）

> 【背景摘要】本批落地 pi 的 `createMcpExtension`（`extensions/mcp/index.ts`，1,344 行）——
> 内置 MCP 扩展本体。MCP 的 SDK/配置/OAuth/工具适配/进程外 CLI 已对齐（map/07 A–F 加权
> 90.6%），缺的是**会话内接线**：六个 `on()` 事件的订阅者、提示段生产者、运行时工具注册、
> `/mcp` 命令与管理器视图。批 16/17/23 已把挂载面全部铺好（激活门/sections 写回/`ui.custom`/
> 渲染器注册/MCP 注册三件套/`mcp_servers_change` 事件），本批只写本体。
> 收编：map/07 G 组 12 缺失行（73/74/76/77/78/79/80/82/83/85/86/89）＋#24（B188 shellPath
> 两层壳）＋#44 翻正（B189 会话臂身份注入有生产源）＋B195（运行时类获得生产消费者）＋
> docs/11 窄差 D3/D4（`_pendingToolNames` 晚注册臂＋defaultTools 容忍）。
>
> pi 取证一律 `git -C /d/workplaceForai/pi show ea448f454:<path>`；java 现状取证 2026-10-26 HEAD。

---

## 1. 命题表（待逐条取证 → 实施）

| # | 命题 | pi 证据 | java 现状 |
|---|---|---|---|
| P1 | 内置扩展以 `builtin:mcp` 身份进装配链，`--no-mcp` 可禁 | `extensions/index.ts:13`（`{name:"mcp", replaceable:true, builtin:true}`）；`main.ts:787`（`disabledBuiltinExtensions:["mcp"]`）；`resource-loader.ts:582`（过滤） | `ExtensionManager.load` 自动合成 `builtin:<name>`（ExtensionManager.java:179）；**无 `--no-mcp`**（ArgsParser.java:40-172 无）；`-e builtin:mcp` 在 `AgentSession.loadResolvedExtension:1028-1030` 被直接跳过 |
| P2 | `session_start`：读两源配置＋注册表，后台连接，问题聚合一次报告 | `index.ts:1111-1151` | `SessionEventFirer.sessionStart` 已在装配尾发射（AgentSession.java:3015）；`McpConfig.load(agentDir,cwd,projectTrusted)` 返回 `LoadedMcpConfig` 四件齐（mcp 模块 config/LoadedMcpConfig.java:18）；运行时零会话内消费者（B195） |
| P3 | `before_agent_start`：direct 服务器首提示等 10s＋写/删 `mcp_servers` 提示段 | `index.ts:1153-1185` | sections 可变写回链批 16 已落（SessionEventFirer.java:340-364 → AgentSession.java:1591）；渲染纯函数 `McpServersSection` 对齐（map/07 #75） |
| P4 | `tool_call`：就绪等待（direct→属主；资源工具→全部；codemode/tool_search 两臂 R5 不做） | `index.ts:1187-1207` | TOOL_CALL 适配面批 4 已落；`toolOwners` 名归一表随 P6 建 |
| P5 | `turn_start`：拾取进程外 `pi-java mcp login` 新凭证并重连 | `index.ts:1209-1212,536-545` | TURN_START 经 SessionEventBridge 发射（SessionEventBridge.java:97）；`McpOAuthCredentialStore.tokens(name,url)` 可读 |
| P6 | 工具运行时注册：名归一/冲突裁定/撤销重注 hidden/disable 隐藏 | `index.ts:388-491` | `McpToolDefinition`/`McpToolNames` 对齐（map/07 #52/#56）；`ToolRegistry.register` 同名静默替换（ToolRegistry.java:34-36）；激活门三件套批 16 已落 |
| P7 | `mcp_servers_change`：扩展注册热插拔（增连/删关/配置变重连） | `index.ts:1214-1247` | 批 23 已接线广播（AgentSession.java:3010-3014）；事件载荷＝全量注册列表 |
| P8 | `session_shutdown`：abort＋关全部连接＋收敛背景动作 | `index.ts:1249-1255` | SESSION_SHUTDOWN 在 `closeWith` 链发射（AgentSession.java:2847） |
| P9 | 资源工具 exposure 同步（最宽可达 exposure；direct 撤离激活集） | `index.ts:466-491` | `McpResourceTools` 三工具已对齐、无生产消费者（map/07 #60） |
| P10 | `/mcp` 斜杠命令（manager/login/logout/reconnect）＋补全＋非 TUI `formatStatus` 臂 | `index.ts:1257-1344,743-778,1014-1109` | 扩展命令面批 20/23 已落（processPrompt 统一执行＋登记目）；`ExtensionUI.select/input/notify/custom` 批 17 在 |
| P11 | `/mcp` 管理器视图载荷（菜单/状态/redirectUrl 屏，订阅重建） | `ui.ts:73-258` | `ExtensionUI.custom`＋`ExtensionComponent`（文本级 SPI，coding-agent 可实现）＋`ExtensionComponentOverlay`（tui 宿主）批 17 全落 |
| P12 | `--no-mcp` CLI flag | `args.ts:191-192,328`（帮助文案逐字见 §2.8） | 无；`--no-session`/`--no-extensions` 接线样例在 ArgsParser.java:76-77,114-115 |
| P13 | B188：`!cmd` 两段式壳（settings.shellPath 优先，ENOENT 回落默认） | `resolve-config-value.ts`（pi 逐字见 §2.9） | `ConfigValueResolver(CommandRunner)` 注入点已开（ConfigValueResolver.java:45）；`JdkShellRunner` 恒默认壳（JdkShellRunner.java:20-22 注释自认）；settings.shellPath 无消费点 |
| P14 | B189 会话臂：MCP 客户端身份 `"pi-java"/VERSION` 注入运行时连接 | pi `runtime.ts:380-381` | CLI 臂已对齐（SubcommandHandler.java:44-47）；`McpConnectionOptions.clientName/clientVersion` 字段在（McpConnectionOptions.java:25-36） |
| P15 | D4：`_pendingToolNames` 晚注册臂（五臂逐字） | `agent-session.ts:447,1524,1529,1810-1830,3590,3681`（§2.7 逐字） | 无对应物（AgentSession.java:1190-1191 注记自认「随 B221⑫b」） |
| P16 | D3：defaultTools 形状异常 pi 容忍、java 类型化读入即抛 | pi settings 读取容忍非数组/杂项成员 | java `SettingsAccessors` 类型化读入即抛（批 1 窄差 D3 登记） |

---

## 2. pi 规格逐字（关键段）

### 2.1 工厂头与状态（index.ts:283-330 摘）

```ts
export function createMcpExtension(options: McpExtensionOptions = {}): ExtensionFactory {
	return (pi: ExtensionAPI) => {
		let servers: McpServer[] = [];
		let configuredEntries: McpServerEntry[] = [];
		let configErrors: string[] = [];
		let projectConfig: string | undefined;
		let overridden: string[] = [];
		let autoEnableCodemode = true;
		let warnedUnreachable = false;
		let pending: Promise<unknown> | undefined;
		let waitedForStartup = false;
		const startupWaitMs = options.startupWaitMs ?? DEFAULT_STARTUP_WAIT_MS; // 10_000
		let sessionCwd = process.cwd();
		let credentials = options.credentials;
		let modelRegistry: ModelRegistry | undefined;
		// session AbortController：session_shutdown 时 abort；首个 session_start 前 abort 态
		let session = new AbortController();
		session.abort();
		const backgroundActions = new Set<Promise<void>>();   // track() 登记
		// listeners/emitChange/subscribe：/mcp 菜单重建的订阅面
```

`McpServer` 内部件（index.ts:112-128）：`{entry, connection?, registeredConfig?, message?, attempt?: symbol, ready?: Promise<void>, closing?: Promise<void>}`。
谓词（:132-154 逐字语义）：`isEnabled`（`config.enabled !== false`）、`exposureOf`（`?? "codemode"`）、
`configuredExposures`（exposure＋toolExposure 值集）、`hasDirectTools`／`hasIndirectTools`。
`MCP_SERVERS_SECTION = "mcp_servers"`；`MAX_SERVERS_SECTION_CHARS = 4096`；`MAX_SERVER_DESCRIPTION_CHARS = 250`。

### 2.2 session_start（index.ts:1111-1151 逐字）

```ts
pi.on("session_start", (_event, ctx) => {
	const loaded = (options.loadConfig ?? defaultLoadConfig)(ctx);
	configErrors = loaded.errors;
	projectConfig = loaded.projectConfig;
	autoEnableCodemode = loaded.autoEnableCodemode ?? true;
	warnedUnreachable = false;
	waitedForStartup = false;
	sessionCwd = ctx.cwd;
	modelRegistry = ctx.modelRegistry;
	session = new AbortController();
	const { signal } = session;
	configuredEntries = loaded.servers;
	const registered = registeredServers();
	overridden = registered.overridden;
	servers = [...loaded.servers.map((entry) => ({ entry })), ...registered.servers];
	emitChange();
	ensureDiscoveryActive(ctx);          // 见裁决⑥：本批不做（#93 权重 0），注记见 §5
	const enabled = servers.filter(isEnabled);
	if (enabled.length === 0) {
		reportProblems(ctx);
		return;
	}
	// 运行时惰性加载＋让出一个事件循环节拍，首渲染不被连接阻塞
	const runtime = new Promise((resolve) => setImmediate(resolve)).then(() => loadMcpRuntime());
	pending = Promise.all(enabled.map((server) => startConnection(server, runtime)))
		.then(() => {
			if (!signal.aborted) reportProblems(ctx);
		})
	.catch((error: unknown) => {
			try { ctx.ui.notify(`MCP failed to load: ${errorMessage(error)}`, "error"); } catch {}
		});
});
```

`defaultLoadConfig`（index.ts:1341-1343 逐字）：

```ts
function defaultLoadConfig(ctx: ExtensionContext): LoadedMcpConfig {
	return loadMcpConfig({ agentDir: getAgentDir(), cwd: ctx.cwd, projectTrusted: ctx.isProjectTrusted() });
}
```

`registeredServers()`（index.ts:352-373 逐字语义）：`pi.getMcpServers()` 逐个与
`configuredEntries` 按 `mcpNamespace` 归一名比对——命中 ⇒ 进 `overridden` 名单
（文案 `"<name>" registered by <extensionPath> is overridden by "<configured>" in <source>`），
否则造 `{entry:{name, config, source: extensionPath, scope:"extension"},
registeredConfig: JSON.stringify(config)}`。

`reportProblems`（index.ts:640-655 逐字文案）：

```ts
ctx.ui.notify(
	`MCP servers need attention:\n${lines.map((line) => `  ${line}`).join("\n")}\nRun /mcp to fix.`,
	"warning",
);
```
其中 `lines` = `config: <error>`×N（仅全量报告时）＋每个 `needs-auth`/`failed` 服务器的
`"<name>: <describeState>"`。

### 2.3 before_agent_start（index.ts:1153-1185 逐字）

```ts
const waitForDirectServers = async (ctx: ExtensionContext) => {
	if (waitedForStartup) return;
	waitedForStartup = true;
	const ready = servers.flatMap((server) =>
		isEnabled(server) && hasDirectTools(server.entry) && server.ready ? [server.ready] : [],
	);
	if (ready.length === 0) return;
	const finished = await Promise.race([
		Promise.all(ready).then(() => true),
		new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), startupWaitMs); }),
	]);
	if (!finished) {
		ctx.ui.notify("MCP servers are still connecting; their tools become available once connected.", "info");
	}
};

pi.on("before_agent_start", async (event, ctx) => {
	await waitForDirectServers(ctx);
	const { sections } = event.systemPromptOptions;
	const section = renderServersSection(servers);
	if (section) sections[MCP_SERVERS_SECTION] = section;
	else delete sections[MCP_SERVERS_SECTION];
});
```

`renderServersSection` ＝ java `McpServersSection`（#75 已对齐，含 intro/排序/4096 预算/裁剪/省略行）——
本批只接**生产者**（servers 列表来自扩展状态；`McpServerListing` 包装 connection.instructions）。

### 2.4 tool_call 就绪等待（index.ts:1187-1207 逐字）

```ts
pi.on("tool_call", async (event, ctx) => {
	const tool = pi.getAllTools().find((candidate) => candidate.name === event.toolName);
	if (!tool) return;
	const readyServers = servers.filter((server) => isEnabled(server) && server.ready);
	if (readyServers.length === 0) return;
	let waiting: McpServer[] = [];
	if (isCodemodeTool(tool)) {                                    // R5：java 无 codemode，臂不做
		const { code } = event.input as { code?: unknown };
		waiting = readyServers.filter((s) => scriptNeedsServer(typeof code === "string" ? code : "", s.entry.name));
	} else if (isToolSearchTool(tool) || RESOURCE_TOOL_NAMES.has(tool.name)) {  // tool_search R5；资源三工具做
		waiting = readyServers;
	} else {
		const owner = toolOwners.get(event.toolName)?.split("\0", 1)[0];
		waiting = readyServers.filter((server) => server.entry.name === owner);
	}
	await waitForServers(waiting, ctx.signal);
});
```

`waitForServers`（index.ts:614-637）：循环等最新 `ready`（等待期间新attempt入列继续等），abort 即返。
java 对位：handler 内同步阻塞 `CompletableFuture` 链（虚拟线程，pi await 对位）；`ctx.signal`
无对位 ⇒ 窄差登记（等待不可被单次调用取消，只随会话 shutdown 解封）。

### 2.5 turn_start 拾取（index.ts:536-560, 1209-1212 逐字）

```ts
const tokensAtSignIn = new Map<McpServerConnection, string>();
const storedTokens = (connection: McpServerConnection): string => {
	const url = connection.oauthUrl;
	return url && credentials ? JSON.stringify(credentials.tokens(connection.name, url) ?? null) : "null";
};
const onConnectionChange = (connection: McpServerConnection) => {
	if (connection.state !== "needs-auth") tokensAtSignIn.delete(connection);
	else if (!tokensAtSignIn.has(connection)) tokensAtSignIn.set(connection, storedTokens(connection));
	emitChange();
};
const reconnectSignedIn = async (ctx: ExtensionContext) => {
	const signedIn = [...tokensAtSignIn].filter(([connection, tokens]) => storedTokens(connection) !== tokens);
	if (signedIn.length === 0) return;
	for (const [connection] of signedIn) tokensAtSignIn.delete(connection);
	await Promise.allSettled(signedIn.map(([connection]) => connection.reconnect()));
	ensureDiscoveryActive(ctx);
};

pi.on("turn_start", async (_event, ctx) => {
	if (tokensAtSignIn.size > 0) await reconnectSignedIn(ctx);
});
```

java 对位：`tokens` 快照串用 `Objects.toString` 级 JSON（jackson 序列化 `OAuthTokens?null`）；
`Promise.allSettled` ⇒ `CompletableFuture` 全异常吞（`handle`）。

### 2.6 工具注册三表＋隐藏重注（index.ts:376-463 逐字摘）

```ts
const toolOwners = new Map<string, string>();          // 工具名 → "<server>\0<tool>"
const serverTools = new Map<string, Set<string>>();    // 服务器 → 当前提供的工具名集
const definitions = new Map<string, ToolDefinition<…>>(); // 工具名 → 最近注册的定义

const registerTools = (connection: McpServerConnection) => {
	// …namespace 组（name/description?/instructions?）；plain 重名全加哈希后缀（Codex 序无关）
	// assignName：isTaken = 他主占用 || 本轮已占 || plain 重名
	for (const tool of connection.tools) {
		const definition = createMcpToolDefinition({ server, tool, name: assignName(...),
			exposure: getMcpToolExposure(entry.config, tool.name), namespace, timeoutMs,
			getClient: async () => { /* 执行时晚解析替换连接；disabled/still starting/
				no longer available 三错文逐字 */ },
			readableResources: () => resourceServers().some((c) => c.name === server) });
		definitions.set(definition.name, definition);
		pi.registerTool(definition);
	}
	serverTools.set(server, current);
	// 撤销的工具重注为 hidden（工具不能注销）：
	for (const name of previous) {
		const definition = definitions.get(name);
		if (!current.has(name) && definition) pi.registerTool({ ...definition, exposure: "hidden" });
	}
	syncResourceTools();
};

const hideTools = (server: string) => { /* 全部重注 hidden ＋ serverTools 清空 ＋ syncResourceTools */ };
```

**java 表达（裁决③）**：`AgentTool` 无 `exposure` 字段（窄差 D89/D91 维持）——
exposure 表由 MCP 扩展自持（`Map<工具名, McpExposure>`，随 `definitions` 同驻）：
- `hidden`/撤销重注 ⇒ `registry.register(替换件)`＋`setActiveTools(移除该名)`（ToolRegistry 同名替换语义）；
- `direct` ⇒ 注册后 `activateTools`（批 16 装配后注册即激活臂的运行时对位，本批新增运行时激活调用）；
- `codemode`/`deferred` ⇒ 注册但不激活（java 无 codemode/tool_search ⇒ 永不可达，仅提示段与 `/mcp` 可见）；
- 执行时 `getClient` 三错文逐字（"is disabled." / "is still starting." / "is no longer available."）。

`syncResourceTools`（index.ts:473-491 逐字语义）：可达服务器（enabled ∧ hasResources ∧
exposure≠hidden）的最宽 exposure（direct > codemode > deferred > hidden）；与上次相同或
（从未注册 ∧ hidden）⇒ 早退；`wasDirect` 时把资源三工具名从激活集滤掉。java：
`McpResourceTools` 三定义（#60 对齐）按同规则注册/重注。

### 2.7 `_pendingToolNames` 五臂（agent-session.ts 逐字，D4 收编）

```ts
private _pendingToolNames = new Set<string>();                                  // :447

setActiveToolsByName(toolNames: string[]): void {                               // :1517-1525
	const previous = this.getActiveToolNames();
	this._setActiveTools(toolNames);
	const active = new Set(this.getActiveToolNames());
	if (previous.some((name) => !active.has(name))) this._pendingToolNames.clear();  // 减员 ⇒ 清 pending
}
private _setActiveTools(toolNames: string[]): void {                            // :1527-1531
	const tools = this._applyToolLoadout(toolNames);
	for (const tool of tools) this._pendingToolNames.delete(tool.name);          // 已注册 ⇒ 出 pending
	this._rebuildSystemPrompt(tools.map((tool) => tool.name));
}
// _restoreToolsFromTranscript :1810-1814：pending = 转录 toolsAdded 过滤 _isAllowedTool
// _runAgentPrompt :3590 注释行（run 记 loadout 后仍未注册的 ⇒ 清 pending 不再等）
// _buildRuntime :3681 臂（reload 前）：for (const name of this.getActiveToolNames()) this._pendingToolNames.add(name);
// 装配激活合并点（:3590 所在方法）：nextActiveToolNames.push(...this._pendingToolNames);
```

java 对位：`AgentSession` 加 `pendingToolNames`（LinkedHashSet）＋五臂；
激活合并点在「装配后注册即激活」臂（AgentSession.java:2965-2975）与 MCP 运行时注册路径
（P6 direct 激活调用 `activateTools` 时消费 pending：名在 pending ⇒ 激活）。

### 2.8 `--no-mcp`（args.ts 逐字）

```ts
} else if (arg === "--no-mcp") {
	result.noMcp = true;
}
// 帮助文案（:328）：
//   --no-mcp                       Disable built-in MCP support: no servers connect and no MCP tools
// 消费（main.ts:787）：disabledBuiltinExtensions: parsed.noMcp ? ["mcp"] : undefined
// 过滤（resource-loader.ts:582）：path 去 "builtin:" 前缀后查 disabledBuiltinExtensions 集
```

### 2.9 B188 两段式壳（pi `core/resolve-config-value.ts` 逐字语义）

```
!cmd 解析：先 settings.shellPath 配置的壳跑；ENOENT（壳不存在）⇒ 回落默认壳。
java 现状：JdkShellRunner 恒默认壳（cmd.exe /d /s /c 或 /bin/sh -c，10s 超时，非零 ⇒ null）。
本批：MCP 扩展创建连接时注入两段式 CommandRunner（settings.shellPath 非空 ⇒ 先试，
IOException/ENOENT ⇒ JdkShellRunner 回落）；挂点＝McpConnectionOptions.resolver。
```

### 2.10 `/mcp` 命令（index.ts:1257-1344 骨架逐字）

- `description`：`"Manage MCP servers: sign in, reconnect, enable or disable, and change exposure"`。
- `getArgumentCompletions`：三段以上 ⇒ null；首段补 `login/logout/reconnect`；二段只在三动作下
  补服务器名（reconnect⇒有连接的；login/logout⇒OAuth 的），条目带 `describeState`。
- handler：`/mcp` 无参 ⇒ TUI 开管理器（`showMcpManager(ctx, manage)`），否则
  `await pending; ctx.ui.notify(formatStatus(), "info")`；多余参数 ⇒ `MCP_USAGE` warning
  （`"Usage: /mcp, /mcp login [server], /mcp logout [server], /mcp reconnect [server]"`）；
  login/logout/reconnect 三臂先 `await pending` 再 `pickServer`。
- `formatStatus`（:1014-1036 逐字文案见实施稿）；`pickServer`（:1039-1064：点名查无 ⇒
  `No MCP server named "<name>".` error；无候选 ⇒ `options.none` info；单候选/单优选直返；
  否则 `ctx.ui.select("MCP server", names)`）；`oauthPick.none` 逐字：
  `"No enabled MCP server uses OAuth. Only HTTP servers without an Authorization header do."`
- `loginCommand`（:1086-1109）：`!ctx.hasUI` ⇒
  `"Signing in to MCP server "<name>" requires interactive mode."` error；TUI ⇒ 管理器内
  signInWithUi；非 TUI ⇒ notify 浏览器 URL＋`ctx.ui.input("Waiting for sign-in …", "http://127.0.0.1:.../callback?code=...", {signal})`；
  成功 ⇒ `ensureDiscoveryActive`＋`Signed in to MCP server "<name>" (<n> tools).` info。
- logout：`signOut` ⇒ `Signed out of …` / `No stored credentials for …` info。
- reconnect：失败 ⇒ error；成功 ⇒ `Reconnected to MCP server "<name>" (<describeState>).` info。

### 2.11 管理器（index.ts:743-1010＋ui.ts 全件）

- `serversMenu`（:787-799）：按 `attentionRank`（needs-auth 0 < failed 1 < disconnected 2 <
  connecting 3 < connected 4 < disabled 5）＋名排序；description ＝
  `<state> · <exposure> · <override?"global, project override":(scope ?? source)>`；
  空文案 `No MCP servers configured. Add them to <agentDir>/mcp.json or .pi/mcp.json.`
  （java 侧 `.pi-java/mcp.json`——B213 改名族，逐字结构保留）；confirm `"manage"` / cancel `"close"`。
- `serverMenu`（:801-860）：动作集按状态（enable/enable-project/signin/tools/reconnect/signout/
  exposure/disable/disable-project）；details 四行（transport 描述/scope: source/override?/State）；
  error＝`server.message`＋非 connected 的 `connection.error`。
- `showTools`/`chooseExposure`/`signInWithUi`（:862-935）；`runInBackground`（finish 门：
  会话 abort 或 attempt 换代或服务器移除 ⇒ 丢结果）；`runAction`/`manage` 双循环
  （:960-1010）。
- `ui.ts`：`McpManagerView`（Component＋Focusable；`MAX_VISIBLE_ITEMS = 12`；menu 订阅重建
  保持选中；status 屏；redirectUrl 屏＝AuthUrlComponent＋Input＋copy 键）；`showMcpManager`
  经 `ctx.ui.custom((tui, theme, keybindings, done) => view)`。

java 对位（裁决⑨）：`McpManagerView` 落 **coding-agent**（`ExtensionComponent` 文本级 SPI：
`render(width): List<String>`＋`handleInput(KeyStroke)`——SelectList 文本级复刻：光标/
12 行窗口/选中保持/订阅重建；主题色经 `ExtensionUiHostEnv.colorize(slot, text)`；
`showMcpManager` ⇒ `ui.custom(factory, ExtensionCustomOptions.DEFAULT)`，factory 产视图、
`done` 为唯一出口）。redirectUrl 屏：无 AuthUrlComponent/K34 原生剪贴板 ⇒ 文本行＋
`app.message.copy` 无 overlay 消费（D97 同族）——窄差登记。

---

## 3. 裁决点（拍板清单）

| # | 裁决 | 建议 |
|---|---|---|
| ① | **内置扩展挂法**：`McpExtension implements PiExtension`（`name()="mcp"`）放 coding-agent 主源码集 `META-INF/services`，ServiceLoader 自动发现 ⇒ 自动获 `builtin:mcp` 身份（ExtensionManager.java:179 既有合成）；`--no-mcp` 在 `AgentSession.loadExtensions` 的 loadAll 处按名过滤（pi disabledBuiltinExtensions 对位，主码唯一过滤点）；`-e builtin:mcp` 显式加载臂（loadResolvedExtension:1028 现直接 return）改查 ServiceLoader 发现集按名实例化 | 做（三件套同批） |
| ② | **replaceable 语义不做**：pi 允许第三方扩展注册 `codemode`/`tool_search`/`/mcp` 接管内置；java 无前两者、第三方 MCP 扩展生态不存在（R5/零消费者） | 不做，记 docs/04 §4 |
| ③ | **exposure 表达**：不给 `AgentTool` 加字段；MCP 扩展自持 exposure 表；declarable⇒激活门（direct 激活／其余注册不激活／hidden＋撤销重注＋激活集移除）；`_getCallableTools` 不做（无 `ctx.executeTool` 面） | 维持 D89/D91 窄差，注记更新 |
| ④ | **D4 `_pendingToolNames`**：AgentSession 加 pending 集＋五臂逐字（§2.7）；消费点＝运行时注册激活 | 做 |
| ⑤ | **D3 defaultTools 容忍**：pi 容忍非数组/杂项成员（跳过），java 改同（`SettingsAccessors` 读入臂放宽＋夹具） | 做（小件） |
| ⑥ | **ensureDiscoveryActive（#93，权重 0）不做**；但 java 永无 codemode/tool_search ⇒ codemode/deferred 服务器的工具**永不可达**。pi 的不可达一次性 warning 给 java 化替身：session_start 检测到间接 exposure 服务器时一次性 `notify("…only reachable from codemode/tool_search, which pi-java does not have; set \"exposure\": \"direct\" …", "warning")`（文案新造 ⇒ 窄差） | 做替身 warning |
| ⑦ | **惰性加载不模拟**：java 无模块加载开销；连接本就在虚拟线程懒连（McpServerConnection.getClient）。`setImmediate` 让拍不移植 | 不做，行为等价注记 |
| ⑧ | **提示段生产者**：before_agent_start 订阅内写/删 `mcp_servers` 段；渲染走既有 `McpServersSection`；intro 文案**逐字保留 codemode/tool_search 提及**（模型可见契约优先逐字，#75 已对齐不翻） | 做（文案不动） |
| ⑨ | **管理器视图落 coding-agent**：`ExtensionComponent` 文本级实现（批 16 裁决⑦/批 17 裁决③先例；coding-agent 不依赖 TamboUI）；SelectList 文本级复刻（光标/窗口/选中保持）；AuthUrlComponent 无对位 ⇒ redirectUrl 屏文本行化（窄差） | 做 |
| ⑩ | **异步模型**：pi Promise/事件循环 ⇒ 虚拟线程＋`CompletableFuture`；事件 handler 内同步 join（pi await 对位；before_agent_start/tool_call/session_shutdown 三处）；`ctx.signal` 无对位 ⇒ 等待只随会话 shutdown 解封（窄差）；后台动作用扩展自有任务集（track 对位） | 定调 |
| ⑪ | **session_start 三件套取值**：`ctx.cwd`/`isProjectTrusted`/`modelRegistry`——java 经 `ExtensionContext.services()` 取 cwd 与信任（批 23 ProjectTrustResolver 链），`CredentialStore.resolveApiKey(provider)` 对位 `modelRegistry.getApiKeyForProvider`（ai 模块 CredentialStore.java:14）。若 SessionServices 面无 CredentialStore ⇒ 开小缝（装配期注入） | 做，缝入实施稿 |
| ⑫ | **B188/B189 接线**：连接创建处注入两段式 CommandRunner（shellPath→ENOENT 回落）与 `clientName/clientVersion="pi-java"/Version.VERSION`（CLI 臂同值，SubcommandHandler.java:44-47） | 做 |
| ⑬ | **非 TUI 臂收窄**：`ctx.ui.input` 无 signal 参数（ExtensionUiDialogOptions 无取消槽）⇒ redirect 粘贴不可被外部 abort，窄差登记；RPC/print 模式 `/mcp` 走 formatStatus notify 臂（pi 同形） | 窄差 |

---

## 4. 实施任务（T1→T10，依赖序）

- **T1 骨架＋装配**：`extension/mcp/McpExtension`（PiExtension "mcp"）＋`META-INF/services` 注册＋
  状态件（servers/configuredEntries/configErrors/projectConfig/overridden/sessionAbort/track 集/
  listeners）＋`McpServer` 内部类＋谓词群（isEnabled/exposureOf/configuredExposures/hasDirectTools/
  hasIndirectTools）＋`describeState`/`attentionRank`/`describeTransport`/`firstLine` 纯件。
- **T2 `--no-mcp`**：ArgsParser/Args/HelpText 三件＋loadExtensions 过滤＋`-e builtin:mcp` 显式臂。
- **T3 session_start 臂**：loadConfig（McpConfig.load 四件）＋registeredServers/覆盖检测＋
  后台 startConnection（虚拟线程）＋reportProblems＋替身 unreachable warning（裁决⑥）。
- **T4 连接生命周期件**：startConnection/createConnection（isCurrent 四代闸：abort/attempt/
  enabled/in-list）＋waitForServers＋onConnectionChange/tokensAtSignIn＋`McpServerListing` 投影。
  B188/B189 注入在此（McpConnectionOptions.resolver/clientName/clientVersion）。
- **T5 before_agent_start 臂**：waitForDirectServers（10s 超时＋通知文案逐字）＋提示段写/删。
- **T6 工具注册（P6/P9）**：toolOwners/serverTools/definitions 三表＋registerTools（名归一重用
  `McpToolNames`、定义重用 `McpToolDefinition`、exposure 表自持、getClient 三错文）＋hideTools＋
  syncResourceTools＋direct 激活/pending 消费。
- **T7 事件余臂**：tool_call 等待（资源三工具名集＋属主臂）＋turn_start 拾取＋
  mcp_servers_change 热插拔（registeredConfig JSON 比对）＋session_shutdown 收敛。
- **T8 D3/D4**：AgentSession pendingToolNames 五臂＋settings defaultTools 容忍臂。
- **T9 `/mcp` 命令**：registerCommand（描述/补全/handler 逐字）＋formatStatus/pickServer/
  loginCommand/logout/reconnect＋signIn/signOut/setEnabled/setExposure/saveConfig 动作件。
- **T10 管理器视图**：`McpManagerView`（ExtensionComponent）＋menu/status/redirectUrl 三屏＋
  manage 双循环＋runInBackground＋serversMenu/serverMenu/showTools/chooseExposure 逐字。

## 5. 窄差预案（登记不认账）

- D-new1：tool_call 等待无 `ctx.signal` 对位（只随 shutdown 解封）[内部·边缘]
- D-new2：redirectUrl 屏无 AuthUrlComponent/copy 键（文本行化；K34/D44 同族）[用户可见·外观]
- D-new3：`ctx.ui.input` 无 abort 槽（redirect 粘贴不可外部取消）[内部·边缘]
- D-new4：替身 unreachable warning 文案 java 化（pi 文案指 codemode/tool_search）[用户可见·文案]
- D89/D91 注记更新（exposure 表外挂常态化）；D117/D119/D128 族不动

## 6. 测试计划

- 纯件：谓词群/describeState/attentionRank/registeredServers 覆盖检测/formatStatus/
  serversMenu·serverMenu 条目集与排序（逐字文案夹具）。
- 生命周期：session_start 装配（两源＋注册表合并＋后台连接＋问题聚合一次）、before_agent_start
  （10s 超时放行＋提示段写/删/diff）、tool_call 三臂等待、turn_start 拾取（tokens 快照比对）、
  mcp_servers_change（增/删/改/override 优先级）、session_shutdown（abort＋close 全量＋幂等）。
- 工具面：注册/改名哈希后缀/撤销重注 hidden/disable 隐藏/direct 激活/pending 晚注册五臂。
- 命令面：`/mcp` 无参两模式/usage warning/pickServer 各臂/login 非交互拒绝文案/logout 两臂/
  reconnect 成败两臂；补全三段规则。
- 视图：McpManagerView 文本级渲染 golden（菜单重建保选中、12 行窗口、空态、error 行）。
- `--no-mcp`：flag 解析/装配过滤/`-e builtin:mcp` 显式臂。
- B188：两段式 runner（shellPath 命中/ENOENT 回落/非零 ⇒ null）；B189：initialize 身份夹具。

## 7. 账面预算（map/07 逐行实算）

| 行 | 判定翻动 | Δ |
|---|---|---:|
| G 组 12 行（73/74/76/77/78/79/80/82/83/85/86/89） | 缺失→对齐 | +17.0（w2×5＋w1×7） |
| #24（B188） | 缺失→对齐 | +1.0 |
| #44（auth.provider） | 存疑→对齐（生产源接通） | +0.5 |
| **map/07 合计** | 102.0 → **120.5/129 ≈ 93.4%** | **+18.5** |
| 全仓 | 1,103.5 → **1,122.0/1379 ≈ 81.4%** | +18.5 |

销号：B221（①落地，全销）/B188/B189/B195/B214（载荷落）；docs/11 窄差 D3/D4 收编。
G 组 23/23 ⇒ 满分域。map/04 账面以逐行实算为准（E5/G 域注记随收）。

## 8. verify 计划

定向测试全绿 → `mvn clean verify` 全量（spotbugs 在 verify 阶段）→ 收口复跑；
日志落盘看退出码（管道接 head 假绿坑）；定向测试带 `-am`（旧 jar 假绿坑）。

## 9. 闭环回填（2026-10-27 实施完成）

**回填三处已落**：docs/04（B221①/B188/B189/B195/B214 销号行＋§4 批 24 段〔裁决①–⑬＋
窄差 D138–D142＋行为变化注记〕＋E1 3,067→3,129）＋docs/05（§1 总表 mcp 行/合计行＋
§2.2 mcp 段＋§2.4 批 24 条目＋原始口径注 420.5→422.5）＋map/07（G 组 12 行＋#24/#44
逐行翻正＋G 组满分域注记＋未加权/加权汇总＋缺失分布＋B221 摘要表＋结论）＋map/04
（G-1/G-4 翻对齐）＋docs/06（§1 四读数＋§2 批 24 行＋§3 候选行收销）。

**实施对稿差**（设计稿审核后实施期的偏差，均已按稿内裁决口径处理）：
- 六事件返回值族修形：`before_agent_start`/`tool_call` 的结果类型是
  `BeforeAgentStartEventResult`/`ToolCallEventResult`（非 Void）——handlers 返回 null
  （pi 不发言）＋sections 经共享可变图原地改写（批 16 链）。
- `onToolCall` 的工具存在性判定从 `ctx.getAllTools()`（需宿主会话缝）改注册表直查
  （`ctx.tools().get`）——pi 语义同（注册表存在性），无宿主路径不炸。
- 无事件总线的退化宿主（`--help` 轻量道/旧夹具）：六订阅整体 try/catch 退化命令面
  （pi assertActive 同形守卫不升级为装配失败）。
- `McpManagerActions` 超 500 行 ⇒ 拆出 `McpServerOps`（连接/写回动作）。
- ScriptedMcpServer 夹具＝pi-java-mcp 测试源集 ScriptedServer/ScriptedTransport 的
  自包含翻版（跨模块不可见）。

**排障钉**（进 docs/04 批 24 段）：
1. **无宿主装配路的 sessionCwd 必须落 settings 存储面 projectDir**——落
   `process.cwd()` 会把测试污染写进调用方工作区（调试期 `.pi-java/mcp.json` 被写进
   模块目录，已清）。
2. **显式 JSON `null` 经 Jackson 进 `JsonNode` 字段变 `NullNode` 非 Java null**——
   D3 首版把真 home 的 `defaultTools:null` 读成空选择 ⇒ 三处存量测试红（修真：缺席
   与 NullNode 同视）。
3. pi `renderServersSection` 的条目名是**命名空间形**（`mcp__docs`）非裸名。
4. `ArrayDeque` 不收 null（脚本化 UI 答案队列用 `LinkedList`）。
5. 定向测试恒带 `-am`（agent-core 新方法的旧 jar 假绿坑再现一次）。

**verify 史**：定向测试全绿（coding-agent 1,648＋agent-core 872）→ 全量
`mvn clean verify` 收口 **exit 0 BUILD SUCCESS，5,231 测 0 失败 0 错，checkstyle 0
违规，spotbugs 零钉**（一次通过；日志落盘 /tmp 看退出码）。
