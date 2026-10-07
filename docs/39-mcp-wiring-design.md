# 包 39：MCP 对齐（B160）——第 12 包：扩展接线（**可移植面**）

> **状态：✅ ⑫a 已闭环（2026-10-08，R-A）；⑫b 顺延（B221）。**
> 总包路线见 `docs/28` §3（第 12 行，末包）；上一包 `docs/38`（`pi mcp` CLI）。
> 本包 = pi `extensions/mcp/index.ts` **1,225 行** ＋ 顺延来的 `ui.ts` 252 行。
>
> ⚠️ **本包提出把 ⑫ 一分为二**：**⑫a 可移植面**（本包做）＋ **⑫b 接线面**（**不做**，
> 因为它卡的**不是 MCP，是扩展系统**）。这是本包唯一要你裁决的结构性改动，见 §2.1。
> **若裁决通过，B160 在 ⑫a 闭环时销号。**

---

## 1. 范围边界

**⑫a 做**（三件纯件，都不依赖扩展 API）：

1. `McpServerRegistry` ＋ `RegisteredMcpServer` ＋ `McpScope.EXTENSION`（包⑧ 顺延至今）；
2. **`mcp_servers` 系统提示段**（`renderServersSection` 全算法 —— 路线图点名的「系统提示段」）；
3. 服务器展示层（`describeState`/`attentionRank`/`describeTransport` 一族），
   `/mcp` 与启动报告共用。

**⑫b 不做**（逐条见表 §4）：工厂本体（`createMcpExtension` 943 行）、`/mcp` 命令与
`ui.ts` 的视图、工具注册与激活、生命周期六事件。

---

## 2. 裁决

### 2.1 拆包：⑫b 卡的**不是 MCP，是扩展系统**（**请裁决**）

`createMcpExtension` 是**照着 pi 的扩展 API 写**的。实测 pi-java 侧的家底（`file:line` 已逐条核过）：

| pi 的 API | pi-java 现状 | 出处 |
|---|---|---|
| `pi.registerCommand(name, def)` | **有** | `ExtensionContext.slashCommands()` → `CommandRegistry.register(SlashCommand)`（`CommandRegistry.java:35`） |
| `pi.registerTool(def)` | **一半**：有 `ToolRegistry.register(AgentTool)`，**没有** `exposure`/`namespace`/`outputSchema`/`annotations`（B203） | `ExtensionContext.java:20` |
| `pi.getActiveTools/setActiveTools/getAllTools` | **机制有、接口无** | `AgentHarness.getActiveTools():528`／`setActiveTools():533`／`ToolRegistry.activeOf():70`；⚠️ **扩展拿不到**（`ExtensionContext` 只给 `ToolRegistry`） |
| `pi.registerMcpServer/getMcpServers` | **无** | 只有 `McpScope.java:6` 的前瞻注释 |
| `pi.registerToolRenderer` | **无**（B204） | — |
| `pi.on(6 事件)` | **1/6** | 见 §4 表 |
| `ctx.ui.custom` | **无**（B214） | 扩展 UI 是 RPC 请求/应答（`ExtensionUI.java:11-14`） |
| 系统提示分节 `sections[...]` | **管道有、未接** | `SystemPromptOptions.sections`（`:33`）＋ `SystemPrompts.buildSections():66` 会渲染，但 `ContextAssembler.promptOptions():104-114` **从没调过 `.sections(...)`** |

⇒ ⑫b 要动的是 **D 域（扩展 41 事件，现 1/41 对齐）**、**E5（扩展 API 面，5/35）**、
**系统提示分节**、**扩展 UI 挂载面** —— 每一样都有自己的台账条目，而**没有一样是 MCP 的语义**。

**三个可选做法**：

| | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | ⑫a 落三件纯件并**销号 B160**；⑫b 作为「扩展系统接线」另立条目（并入 D/E5 的既有缺口） | B160 的销号理由是「MCP 侧全落、剩余是扩展系统的活」——需要在台账里把这句写清楚 |
| B | 先把扩展 API 补到能跑 `createMcpExtension`，再落 ⑫b | 那是**另一个量级**的包（41 事件 + E5 + UI 挂载 + 分节接线），且它会成为 B160 的第 13 包，**把 MCP 的收尾绑在扩展系统上** |
| C | ⑫b 照 pi 的形状硬写，桩掉缺的面 | 与包⑪ 否掉方案 B 同理：形状不同源，将来要么重写要么留两套 |

**推荐 A**。理由不是工作量，是**归因**：`createMcpExtension` 的失败面**全部**落在
「pi-java 没有扩展系统」上，落一件 MCP 的活不会让它前进一格；
而三件纯件（注册表、提示段、展示层）**是 MCP 自己的语义**，且**今天就有测试价值**。

⚠️ **若你选 B**：本包变成「先补扩展 API」，规模 3–4 倍于本稿，且建议另开设计稿。

### 2.2 `McpServerRegistry` 落 `pi-java-mcp/config`

它只依赖 `McpServerConfig`（`:281-319`，39 行），与包⑧ 的其余 config 面同包。
包⑧ 的裁决 A 说「现在建＝零生产者代码」——**仍然成立**（`pi.registerMcpServer` 在 ⑫b），
但它是 MCP 自己的语义、有确定的行为可测（注册/替换/所有权/深拷贝/变更回调），
且 `McpScope.EXTENSION` 是它带出来的第三个 scope。

**登记**：它闭环前仍无生产者（并入 B195 一族）。

### 2.3 `mcp_servers` 系统提示段：**纯函数**，落 `extension/mcp`

`renderServersSection`（`index.ts:191-217`）的输入是
`readonly McpServerListing[]`（`entry` + 可选 `connection.instructions`），
输出是字符串或 `undefined` —— **不碰扩展 API**，今天就能落、就能测。

⚠️ 它的**落点**：`pi-java-coding-agent/.../extension/mcp`（包⑩ 那个包）。
⑫b 接线时由 `ContextAssembler.promptOptions` 取值填进 `SystemPromptOptions.sections`
（`MCP_SERVERS_SECTION = "mcp_servers"` 已满足 `SystemPrompts` 的命名正则
`^[a-z][a-z0-9_-]*$`，`:292-298`）。

### 2.4 ⑫b 的六事件逐条（**都不落，登记归属**）

| pi 事件 | pi-java 最近的东西 | 归属 |
|---|---|---|
| `session_start` | `PiExtension.sessionStartResources()`（`PiExtension.java:29`）——装配期一次性回调，**不是事件**；且它在扩展**加载期**跑，早于任何连接 | D 域 |
| `before_agent_start` | `before_run` 钩子（`HookSystem.onBeforeRun:43`，`RunLifecycle.java:80` 触发）——⚠️ 但 `SystemPromptOptions.java:23-25` 自己记着「pi 的 `forceSystemPrompt` 写入口在 Java 不可达」 | D 域 |
| `tool_call` | **有**：`before_tool`（`HookSystem.onBeforeTool:73`，`PiToolRunner.java:111`） | ⑫b 唯一可用的一条 |
| `turn_start` | `PiLoop.Event.TurnStart`（`PiLoop.java:51`）**存在但 `SessionRunner` 不转发**（`SessionRunner.java:230-269` 只转 ToolExecution*/AgentStart/AgentEnd），`AgentSessionEvent` 无对应变体 | D 域 |
| `mcp_servers_change` | **无**任何 MCP 配置变更观察点 | 随 §2.2 的注册表一起 |
| `session_shutdown` | `AgentSession.close():1104`；`ExtensionManager.unload():70` **只删一个 map**（类注释明写「扩展无 close 生命周期」） | D 域 |

⚠️ **连既有钩子都够不着**：`HookSystem` 只从 `AgentHarness.hookSystem():443` 暴露，
而 `ExtensionContext`（`:17-65`）只给 `tools/slashCommands/providers/skills/settings/ui/services`，
`SessionServices`（`:28-37`）里**没有 HookSystem**。⇒ ⑫b 的第一件事是**先把钩子暴露给扩展**。

---

## 3. ⑫a 详细设计

### 3.1 `McpServerRegistry`（`mcp-servers.ts:281-319` 逐字）

```ts
export interface RegisteredMcpServer {
	name: string;
	config: McpServerConfig;
	/** Path of the extension that registered the server. */
	extensionPath: string;
}

export class McpServerRegistry {
	private readonly servers = new Map<string, RegisteredMcpServer>();
	private changeListener: (() => void) | undefined;

	register(server: RegisteredMcpServer): void { this.servers.set(server.name, server); this.changeListener?.(); }
	unregister(name: string, extensionPath: string): void {
		if (this.servers.get(name)?.extensionPath !== extensionPath) return;
		this.servers.delete(name);
		this.changeListener?.();
	}
	get(name: string): RegisteredMcpServer | undefined { return this.servers.get(name); }
	list(): RegisteredMcpServer[] { return [...this.servers.values()].map((server) => ({ ...server, config: structuredClone(server.config) })); }
	setChangeListener(listener: (() => void) | undefined): void { this.changeListener = listener; }
}
```

⚠️ **四条承重**：
1. `unregister` 的**所有权检查**：`extensionPath` 不符**静默返回**（别的扩展的服务器动不了）；
2. `register` **替换同名的**（`Map.set` 保持**首次插入的位置**——JS 的 `Map` 语义，
   Java 用 `LinkedHashMap` 同义）；
3. `list()` 返回**拷贝**：`{...server}` 浅拷 + `structuredClone(config)` **深拷 config**
   ⇒ Java 用 `McpJson.mapper().convertValue(config, McpServerConfig.class)`（记录不可变，深拷即重建）；
4. **`changeListener` 在每次变更后调用**，且在 `setChangeListener` 时可以传 `undefined` 摘掉它。

Java：`pi-java-mcp/src/main/java/com/pijava/mcp/config/McpServerRegistry.java`
＋ `RegisteredMcpServer(name, config, extensionPath)`；`McpScope` 加 `EXTENSION`。

### 3.2 `renderServersSection`（`index.ts:191-217` 逐字）

```ts
export function renderServersSection(servers: readonly McpServerListing[]): string | undefined {
	const listed = servers
		.filter((server) => isEnabled(server) && hasIndirectTools(server.entry))
		.sort((a, b) => a.entry.name.localeCompare(b.entry.name));
	if (listed.length === 0) return undefined;
	const reaches = listed.map((server) =>
		configuredExposures(server.entry).has("codemode") ? "codemode" : "tool_search",
	);
	const intro = serversSectionIntro(new Set(reaches));
	const heads = listed.map((server, index) => `- ${mcpNamespace(server.entry.name)} (${reaches[index]})`);
	const omitted = (count: number) =>
		count > 0 ? [`- … ${count} more server${count === 1 ? "" : "s"}; find their tools with searchTools()`] : [];
	const size = (kept: number) => [intro, ...heads.slice(0, kept), ...omitted(listed.length - kept)].join("\n").length;
	let kept = listed.length;
	while (kept > 0 && size(kept) > MAX_SERVERS_SECTION_CHARS) kept--;
	const perServer =
		kept === 0 ? 0
		: Math.min(MAX_SERVER_DESCRIPTION_CHARS, Math.floor((MAX_SERVERS_SECTION_CHARS - size(kept)) / kept) - 2);
	const lines = listed.slice(0, kept).map((server, index) => {
		const summary = perServer > 0 ? truncate(serverSummary(server), perServer) : "";
		return summary ? `${heads[index]}: ${summary}` : heads[index];
	});
	return [intro, ...lines, ...omitted(listed.length - kept)].join("\n");
}
```

⚠️ **九条承重**（逐条要夹具）：

1. **筛选**：`enabled !== false` **且** 有间接暴露（`codemode` 或 `deferred`）——
   `direct` 的服务器**不进这段**（它们的工具直接声明给模型）；
2. **按名 `localeCompare` 排序**（Java `String.compareTo` **不等价**于 `localeCompare`——
   登记 **B-新**，夹具只用 ASCII 名）；
3. `reaches` **逐服务器**取：有 `codemode` 就说 `codemode`，否则 `tool_search`
   （`deferred` 或两者都没有的**间接**服务器落在 `tool_search` 侧）；
4. **`intro` 按 `reaches` 的并集**决定加不加两句（见 `serversSectionIntro`）；
5. `size(kept)` 是**行数裁剪后**的长度，且**只算 head 行、不算描述**；
6. **`kept` 从全量往下退**，退到 0 就全裁；
7. `perServer` 的 `- 2` 是**每个 ": " 的 2 个字符**；
8. `perServer` 为 0 或摘要为空时**整行只有 head**（`: ` 也不写）；
9. `truncate` 的**三条**：不超原样；`max <= 1` 返回**空串**；
   否则 `slice(0, max-1).trimEnd() + "…"`。

⚠️ **`truncate` 的字节语义**：JS 的 `.length`／`.slice` 是 **UTF-16 单元**，
`slice(0, max-1)` 可能**劈开代理对**。Java 的 `String.length()`／`substring` 同语义
⇒ 逐字移植即逐字等价（**别改成码点**，那会与 pi 的字节预算分叉）。
`trimEnd()` 在 JS 里还吃 ` `，Java `stripTrailing()` 不吃 —— 登记 **B-新**（窄角）。

### 3.3 展示层（`index.ts:113-273`）

| pi | 规则 |
|---|---|
| `EXPOSURE_DESCRIPTIONS` | 三句话，`hidden` 除外（`codemode`/`deferred`/`direct` 各一句，逐字） |
| `firstLine(text)` | `text.split("\n", 1)[0] ?? ""` ⇒ Java `text.split("\n", 2)[0]` |
| `isEnabled` | `config.enabled !== false`（**只有显式 false 才算禁用**） |
| `exposureOf` | `config.exposure ?? "codemode"` |
| `configuredExposures` | `{exposureOf(entry)} ∪ values(toolExposure ?? {})` |
| `hasDirectTools` | 上集合含 `direct` |
| `hasIndirectTools` | 含 `codemode` 或 `deferred` |
| `scriptNeedsServer(code, server)` | 正则 `\b(searchTools\|describeNamespace\|describeTool\|ALL_TOOLS)\b` **命中即 true**，否则 `code.includes(mcpNamespace(server))`；⚠️ 消费者是 codemode（**B161**） |
| `describeState(server, withError=true)` | 见下 |
| `attentionRank(server)` | 禁用 5；`needs-auth` 0；`failed` 1；`disconnected` 2；`connected` 4；其余 3 |
| `describeTransport(entry)` | url 或 `[command, ...args].join(" ")` |
| `MCP_USAGE` | 逐字 |

`describeState` 的**四态改写**（`index.ts:229-249`）：

```
disabled                                   （!isEnabled）
starting                                   （connection 尚未建立）
needs sign-in                              （needs-auth）
failed: <error 的第一行>                    （failed，withError 为真时）
failed                                     （failed，withError 为假时）
connected · N tool(s)[ · M resource(s)]     （connected；单复数与资源段按需）
connecting…                                （connecting）
<原样>                                     （其余）
```

⚠️ **`connected` 那行的三个条件段**：工具数**恒有**（`0 tools` 也写）、
资源段**只在 count > 0 时**出现、单复数各自判断（`tool`/`tools`、`resource`/`resources`）。
⚠️ 与包⑪ 的 `list` 输出**不是同一套文案**（那边是 `connected, 1 tool`，逗号；
这边是 `connected · 1 tool`，中点分隔符）—— **别统一**。
⚠️ `attentionRank` 的 `connected` 是 **4** 而不是 0（`needs-auth` 才是 0），
即「最不需要注意的排最后」。

---

## 4. ⑫b 阻塞清单（登记用，本包不做）

| pi 的面 | 卡在 | 台账 |
|---|---|---|
| 六个 `on(...)` 事件 | 扩展拿不到 `HookSystem`／事件面缺 5 条 | **D 域**（41 事件 1/41） |
| `getActiveTools/setActiveTools` 暴露给扩展 | 机制在 `AgentHarness`，扩展 API 只给 `ToolRegistry` | **E5** |
| `AgentTool` 的 `exposure/namespace/outputSchema/annotations` | 包⑩ 的 B203 | **E5** ＋ B203 |
| `registerToolRenderer` | 无 | **B204** |
| `ctx.ui.custom` ＋ `/mcp` 管理器视图（`ui.ts` 252） | 扩展 UI 是 RPC 请求/应答，无组件挂载 | **B214** |
| `pi.registerMcpServer/getMcpServers` | 注册表本包落（§2.2），**接线**没有挂载点 | 随 D/E5 |
| `mcp_servers` 分段注入 | `ContextAssembler.promptOptions` 从没调过 `.sections(...)` | 随 D 域（提示分节） |
| 生命周期（连上/关闭、首轮等待、按需等待 codemode/tool_search） | 六事件 + 激活门 | 随上列 |

⚠️ **`startupWaitMs`（默认 10s）**：首轮等待 `direct` 工具服务器。
它依赖 `before_agent_start`，且 `direct` 的语义是「声明给模型」——
今天 pi-java 没有激活门，**这个等待没有对象**。并入 D 域。

---

## 5. 文件清单（预估）

`pi-java-mcp/src/main/java/com/pijava/mcp/config/`：

| 文件 | 对应 pi | 预估行 |
|---|---|---:|
| `RegisteredMcpServer.java` | `mcp-servers.ts:281-286` | 40 |
| `McpServerRegistry.java` | `mcp-servers.ts:288-319` | 120 |
| `McpScope.java`（改） | 加 `EXTENSION` | +6 |

`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/extension/mcp/`：

| 文件 | 对应 pi | 预估行 |
|---|---|---:|
| `McpServersSection.java` | `index.ts:95-217`（段渲染 ＋ 筛选/暴露判定） | 260 |
| `McpServerState.java` → 名冲突，改 `McpServerPresentation.java` | `index.ts:113-272`（文案/排序/传输描述） | 230 |
| `McpServerListing.java` | `index.ts:175-184` | 45 |

预计主源 **≈ 700 行 / 5 新文件 ＋ 1 改**、测试 **≈ 550 行 / 4 夹具**。

⚠️ **命名冲突**：`com.pijava.mcp.runtime.McpServerState` 已存在（包⑨ 的六态枚举），
本包的展示层**不能**同名 ⇒ 用 `McpServerPresentation`。

---

## 6. 测试计划

| 夹具 | 覆盖 |
|---|---|
| `McpServerRegistryTest` | 注册/替换保位次、`unregister` 的所有权静默返回、`list()` 的深拷贝（改返回值不改内部）、变更监听（注册/注销各一次、`setChangeListener(null)` 后不再回调） |
| `McpServersSectionTest` | 空/全 direct/全 disabled ⇒ `undefined`；按名排序；`reaches` 逐服务器；`intro` 四态（都不加/只 codemode/只 tool_search/都有）；描述截断（250 上限）；`perServer` 的 `-2`；整段 4096 上限与 `kept` 退让；省略行单复数；`truncate` 三条 |
| `McpServerPresentationTest` | `describeState` 七态（含 `failed:` 的 withError 两态、`connected · 0 tools`）；`attentionRank` 六值；`describeTransport` 两形；`isEnabled` 三态；`configuredExposures`/`hasDirectTools`/`hasIndirectTools`；`EXPOSURE_DESCRIPTIONS`/`MCP_USAGE` 逐字 |
| `McpScriptNeedsServerTest` | 四个函数名命中、`ALL_TOOLS` 命中、命名空间子串命中、都不命中 |

---

## 7. 变异探针计划（10 个）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `unregister` 的所有权检查删掉 | 1 |
| M2 | `list()` 返回内部对象（不深拷） | 1 |
| M3 | 段筛选去掉 `hasIndirectTools` | 1 |
| M4 | 排序去掉 | 1 |
| M5 | `reaches` 恒取 `codemode` | 1 |
| M6 | `perServer` 的 `- 2` 删掉 | 1 |
| M7 | `kept` 的 `while` 改成 `if` | 1 |
| M8 | 省略行的单复数删掉 | 1 |
| M9 | `truncate` 的 `max <= 1` 分支删掉 | 1 |
| M10 | `attentionRank` 的 `connected` 由 4 改 0 | 1 |

---

## 8. 台账影响

| # | 条目 |
|---|---|
| B218 | `McpServerRegistry` 与 `McpScope.EXTENSION` 闭环前**无生产者**（接线在 ⑫b） |
| B219 | `renderServersSection` 的排序用 `String.compareTo` 代替 `localeCompare`（ASCII 等价，非 ASCII 名有差） |
| B220 | `truncate` 的 `trimEnd()` ⇒ `stripTrailing()`：后者不吃 ` `（窄角） |
| B221 | **扩展接线面整体顺延**：六事件（D 域）＋ 激活门暴露（E5）＋ `ui.custom`（B214）＋ 提示分节接线 —— **B160 的剩余面不是 MCP 语义** |
| —— | **B160 销号**（⑫a 闭环时）：MCP 的协议/传输/OAuth/配置/运行时/工具适配/CLI **全落**（包①–⑪），本包补注册表与提示段；剩余见 B221 |
## 9. 实施记录

**提交**：`2ae18254`（⑫a 一次落完：主源 5 新文件 ＋ 1 改 ＋ 4 夹具）。

**规模**（实测）：`pi-java-mcp/config` 两新文件（`McpServerRegistry` 87、`RegisteredMcpServer` 11）
＋ `McpScope` 加 `EXTENSION`；`pi-java-coding-agent/extension/mcp` 三新文件
（`McpServerPresentation` 208、`McpServersSection` 147、`McpServerListing` 20）。
测试 4 夹具（registry 8、section 18、presentation 15）。`pi-java-mcp` 252 → **260**、
`pi-java-coding-agent` 474 → **507**。

**验证**：新文件 checkstyle **0 警告**；`spotbugs:check` 两模块 0；全 reactor 见 §9.4。

### 9.1 探针（10/10 有牙）

| # | 变异 | 命中 |
|---|---|---|
| M1 | `unregister` 的所有权检查删掉 | 恰 1 |
| M2 | `list()` 返回内部对象 | 恰 1 |
| M3 | 段筛选去掉 `hasIndirectTools` | 2（`aServerWithOnlyDirectToolsIsNotListed`、`aHiddenServerIsNotListed` —— 都是这条筛的夹具） |
| M4 | 排序去掉 | 恰 1 |
| M5 | `reaches` 恒取 `codemode` | 恰 1 |
| M6 | `perServer` 的 `- 2` 删掉 | 恰 1 |
| M7 | `kept` 的 `while` 改 `if` | 恰 1 |
| M8 | 省略行的单复数删掉 | 恰 1 |
| M9 | `truncate` 的 `max <= 1` 分支删掉 | 恰 1 |
| M10 | `attentionRank` 的 `connected` 由 4 改 0 | 恰 1 |

### 9.2 两处实现发现

**① `McpServerConfig` 是 sealed interface 且无判别符 ⇒ Jackson 不能多态拷贝。**
`list()` 要交「拷贝」，第一版写的是
`convertValue(config, McpServerConfig.class)` —— 直接抛
*Cannot construct instance of `McpServerConfig` (no Creators …)*。
根因是**包⑧ 的一个刻意裁决**：`type` 是配置自己的字段，
加 `@JsonTypeInfo` 会多发一个键（`docs/35` §2.1）。
⇒ 拷贝按**变体分派**（`switch` 两条），并把这个理由写进方法的 javadoc
—— 这是**两个包的裁决互相咬合**的一处，后人不该把它当冗余。

**② 我对段预算的直觉是错的（实测纠正）。**
设计稿里我按「40 台服务器 × 250 字符描述 ≈ 10000 > 4096」预判会出现省略行。
**实测不会**：`perServer` 先把每段描述压到
`(4096 - 头部行长度) / kept - 2`（约 71 字符），40 台**全部排得下**、长度 4065。
只有**头部行本身就装不下**时才丢服务器（200 台长名服务器那条夹具）。
⇒ 夹具改成两条：`descriptionsShrinkBeforeAnyServerIsLeftOut`（40 台全在、描述被压缩）
与 `serversThatDoNotFitAreCountedInstead`（省略行）。
**教训：凡「预算/裁剪」算法，先问「它先裁哪一维」，再断言。**

### 9.3 一处我自己重复踩的坑

`var long = …` **又一次**编译不过（`long` 是 Java 关键字）——**包⑩ 已经踩过并写进了记录**
（`docs/37` §10.6），我还是写了。⇒ 这条从「教训」升级为**检查项**：
从 TS 直译局部变量名时，先扫一遍 Java 关键字表（`long`/`switch`/`final`/`class`…）。

### 9.4 全 reactor

`mvn clean verify`：**15/15 模块 SUCCESS，7:56**；模块汇总行求和 **3,177**（上包 3,136 ＋ 41 ＝ mcp +8 ＋ coding-agent +33）。

### 9.5 B160 收尾

⑫a 落完后，MCP 的**协议/传输/OAuth/配置/运行时/工具与资源适配/CLI/注册表/提示段**全部落地。
**B160 销号** ⇒ 从 `docs/04` 移除该行，结论写在收尾提交的 message 里；
剩余面转入 **B221**（扩展接线：六事件＋激活门暴露＋`ui.custom`＋提示分节接线），
它们各自的归属是 **D 域**与 **E5**，不是 MCP。
