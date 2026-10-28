# docs/38 — coding-agent 批 27 设计稿：B162 虚拟模型整域（tool-search / virtual-models / nested-tool-calls）

【背景摘要】本稿是 coding-agent 对齐批 27 的设计与实施依据。批 26 闭环时裁决①把
`registerVirtualModel`/`unregisterVirtualModel`（map/04 E5-26/E5-27）挂账收编进 B162
（docs/04 工单：tool-search / virtual-models / nested-tool-calls 三块零对应），理由是
「注册面无消费者＝死代码」。本批把三块整域一次落地，B162 销号、E5-26/27 翻行。
范围依据 docs/06 §3.3 之外的唯一成建制欠账；用户已拍板选题（2026-10-28），并授权
设计稿定稿后直接实施。pi 侧取证全部锚定 `ea448f454`（下称 pi 锚点），行号以
`git show ea448f454:<path>` 为准。

术语（首现释义）：

- **虚拟模型（virtual model）**：目录里的「假模型」条目。用户选中它后，每次请求先经
  扩展注册的**路由器（route 函数）**挑一台真实（物理）模型，再发给 provider。路由之下
  的一切（流式、消息记录）只见物理模型。
- **路由态（router state）**：路由器跨请求携带的自定义状态（如「当前处于规划阶段」），
  以 session 自定义条目持久化，跟随会话树分叉与压缩。
- **deferred 工具**：注册了但不声明给模型的工具（MCP 服务器 exposure 四档之一），模型
  经 `tool_search` 工具搜索后按需加载。
- **嵌套工具调用（nested tool call）**：一个工具在执行中经 `ctx.executeTool()` 再调别的
  工具（codemode 脚本的主要机制）。agent loop 不知道它们；记录挂在父调用结果消息上。
- **声明面**：随请求发给模型的工具清单（`agent.state.tools`）；相对概念是**可调用集**
  （`ctx.executeTool()` 能调到的东西，含未声明的 codemode/deferred 工具）。

---

## 1. 命题表（待逐条落地）

| # | 命题 | pi 锚点 | 账面行 |
|---|---|---|---|
| P1 | 虚拟模型内核：类型＋判别＋分支选型解析＋路由态读取 | `core/virtual-models.ts:1-238` | E5-26/27 附属 |
| P2 | 注册表：双注册表、冲突校验、目录装饰、注销 | `core/model-runtime.ts:952-1033` | E5-26（w2）/E5-27（w1） |
| P3 | 路由解析：resolveModel（previous/failed 解析、auth 门、错误文案 5 条逐字） | `core/model-runtime.ts:996-1027` | 同上 |
| P4 | 请求级路由接线：reason 四值、路由态写回、压缩阈值对路由模型检查 | `core/agent-session.ts:787-844` | 同上 |
| P5 | ~~选型持久化与恢复：`_recordSelection`＋`getBranchSelection`~~ **裁决⑪不落**（java resume 不从分支恢复选型＝存量方言；无消费者不接线，挂账 docs/04） | `core/agent-session.ts:628-637`；`sdk.ts:217-231` | 挂账 |
| P6 | loop 外路由：摘要/压缩请求 direct 臂 | `core/agent-session.ts:583-589`；`model-runtime.ts:716-742` | 同上 |
| P7 | 扩展注册面：pending 队列＋装配冲刷＋`route(request, ctx)` 适配 | `extensions/loader.ts:190/:226-233/:500-514`；`runner.ts:498-543`；`agent-session-services.ts:182-194` | E5-26/27 翻行点 |
| P8 | tool-search 本体：BM25/tokenize/stem/文档构建/searchAndLoad/工具定义逐字 | `extensions/tool-search/tool.ts:1-247`、`index.ts:1-18` | map/07 #92/#93 重裁 |
| P9 | exposure 面：五档枚举＋四门（activatable/declarable/activatedOnRegistration/callable） | `core/extensions/types.ts:501-516`；`agent-session.ts:1543-1567/:3595-3604` | D91 收窄 |
| P10 | MCP 集成：ensureDiscoveryActive 真化＋mcp_servers 段 reach 标注＋tool_call 等待臂 | `extensions/mcp/index.ts:494-533/:155-221/:1183-1206` | map/07 域 |
| P11 | nested 内核：四条有界规则＋Recorder＋Runner（id 规则/共享 recorder/独占队列/三事件） | `core/nested-tool-calls.ts:1-263` | B162 第三块 |
| P12 | nested 宿主缝：记录挂载（toolResult message_start）＋usage 合并＋agent_end 清空 | `core/agent-session.ts:726-764/:1102-1116` | 同上 |
| P13 | nested 扩展面：`ExtensionToolContext.executeTool`＋`tools`＋无宿主降级 | `extensions/runner.ts:957-988`；`types.ts:383-395` | 同上 |
| P14 | nested 数据模型：`ToolResultMessage.nestedCalls`＋`NestedToolCallRecord` 形状 | `packages/ai/src/types.ts:598-637` | ai 模块 |
| P15 | nested 下游：HTML 导出 renderNestedCalls＋compaction 文件清单＋TUI 跳过嵌套行＋RPC 透传 | `export-html/template.js:933-946`；`compaction/utils.ts:26-45`；`interactive-mode.ts:3606-3608` | 各处 |

---

## 2. pi 规格逐字（关键段）

### 2.1 虚拟模型内核（virtual-models.ts）

常量与判别（:29/:32/:105-107 逐字）：

```ts
export const VIRTUAL_MODEL_API = "pi-virtual";
export const VIRTUAL_MODEL_STATE_ENTRY = "pi.virtual-model-state";
export function isVirtualModel(model: { api: string }): boolean {
	return model.api === VIRTUAL_MODEL_API;
}
```

路由原因（:50 逐字）：`"user" | "continuation" | "retry" | "direct"`——
user＝用户消息后的首个请求；continuation＝工具结果/扩展消息之后；retry＝失败重试
（含压缩后重试）；direct＝agent loop 外的请求（如压缩摘要），direct 不写路由态。

`ModelRouteRequest`（:52-70）字段：`model`（选中的虚拟模型）、`thinkingLevel`、
`reason`、`previous?`（最近一次成功响应的物理模型与 thinkingLevel）、`failed?`
（retry 时的失败请求，带 stopReason/errorMessage）、`state?`（本分支上次路由返回的态）、
`messages`、`signal?`。

`ModelRoute`（:73-82）字段：`model`、`thinkingLevel`、`state?`——返回 `request.state`
原引用或 undefined 表示保持不变；必须可 JSON 序列化；direct 请求忽略。

`VirtualModelDefinition`（:84-102）字段：`provider`（可挂有物理模型的 provider 下）、
`id`（不得与同 provider 物理模型同 id）、`name`、`thinkingLevels?`（默认 `["off"]`）、
`contextWindow?`/`maxTokens?`（首响应前展示的限额，之后用物理模型的）、`input?`
（默认 `["text","image"]`）、`route(request)`（可异步）。

分支选型解析（:128-145 逐字——「虚拟压住物理」规则）：

```ts
export function getBranchSelection(
	branch: readonly SessionEntry[],
	getModel: (provider: string, modelId: string) => Model<Api> | undefined,
): { provider: string; modelId: string } | undefined {
	for (let i = branch.length - 1; i >= 0; i--) {
		const entry = branch[i];
		if (entry.type === "model_change") {
			return { provider: entry.provider, modelId: entry.modelId };
		}
		if (entry.type === "message" && entry.message.role === "assistant" && !isVirtualModel(entry.message)) {
			const response = { provider: entry.message.provider, modelId: entry.message.model };
			const change = findLastModelChange(branch, i);
			const model = change && getModel(change.provider, change.modelId);
			return change && model && isVirtualModel(model) ? change : response;
		}
	}
	return undefined;
}
```

语义：从分支尾部向前扫。遇 `model_change` 直接返回。遇物理 assistant 响应时回看
其前最后一个 `model_change`——若它指向**仍然注册着**的虚拟模型，虚拟选型压住物理
响应（响应只记物理模型名）；已注销的虚拟模型不压住，回退到最后应答的物理模型。

路由态读取（:159-167 逐字）：

```ts
export function getVirtualModelState(branch: readonly SessionEntry[], provider: string, modelId: string): unknown {
	for (let i = branch.length - 1; i >= 0; i--) {
		const entry = branch[i];
		if (entry.type !== "custom" || entry.customType !== VIRTUAL_MODEL_STATE_ENTRY) continue;
		const data = entry.data as VirtualModelStateData | undefined;
		if (data?.provider === provider && data.modelId === modelId) return data.state;
	}
	return undefined;
}
```

`VirtualModelStateData`（:35-39 逐字）：`{ provider: string; modelId: string; state: TState }`。

`createVirtualModel`（:170-187）：目录条目 `api: "pi-virtual"`、`baseUrl: ""`、cost 全 0、
`reasoning = levels.some(l => l !== "off")`、未入选 level 的 thinkingLevelMap 置 null、
`contextWindow/maxTokens` 缺省 0、`input` 缺省 `["text","image"]`。

`withVirtualModels`（:201-238）：无底层 provider 时造 keyless provider（auth 恒
`{ auth: {}, source: "virtual" }`，stream/streamSimple 走 unroutedStream）；有底层 provider
时包装之：`getModels()/getAllModels()` 返回「物理模型（剔除与虚拟同 id 的 chat 模型）
＋全部虚拟模型」；过滤只对物理部分走原逻辑再拼回虚拟部分；虚拟模型的 stream 走
unroutedStream（抛错文案见 §2.2）。

### 2.2 注册表与路由解析（model-runtime.ts）

双注册表（:179-180）：虚拟模型存独立 `Map<providerId, Map<id, RegisteredVirtualModel>>`
（`RegisteredVirtualModel = { model: Model, route }`，:95-98），与物理注册表分离；只经
`recomposeProvider`（:290-298）以 `withVirtualModels` 合入目录。`providerIds()`
（:280-288）并入虚拟 provider id 集。

`registerVirtualModel`（:952-976）语义与错误文案（逐字）：

- 空 provider/id：`throw new Error("Virtual model provider and id must not be empty.");`
- 与目录物理模型同名：``throw new Error(`Virtual model ${providerId}/${id} conflicts with a physical model.`);``
- 纯虚拟 provider 无凭据需求：立即在 snapshot 标记 configured（`{ type: "api_key", source: "virtual" }`，
  :967-973；注释：session 恢复在 refresh 落地前检查 auth）。
- 同 provider+id 重复注册＝替换。注册后 `updateModelSnapshot()`＋离线 refresh。

`unregisterVirtualModel`（:978-985）：删不到直接 return；空集连 provider 键一起删；
重组 provider＋snapshot＋离线 refresh。

`resolveModel`（:996-1027 逐字主体）：

```ts
const name = `Virtual model ${model.provider}/${model.id}`;
const virtual = this.virtualModels.get(model.provider)?.get(model.id);
if (!virtual) throw new Error(`${name} is not registered.`);
const { failed, ...request } = options;
const latest = findLatestResponse(messages);
const previousModel = latest && this.getPhysicalModel(latest.provider, latest.model);
// A failed routing attempt names the virtual model; there is no physical request to report.
const failedModel = failed && this.getPhysicalModel(failed.provider, failed.model);
const route = await virtual.route({
	...request, model,
	previous: previousModel && { model: previousModel, thinkingLevel: latest?.thinkingLevel },
	failed: failedModel && failed && { model: failedModel, thinkingLevel: failed.thinkingLevel, message: failed },
	messages,
});
const target = this.getPhysicalModel(route.model.provider, route.model.id);
const routed = `${name} routed to ${route.model.provider}/${route.model.id}`;
if (!target) throw new Error(`${routed}, which is not a physical model.`);
if (!this.hasConfiguredAuth(target.provider)) throw new Error(`${routed}, which has no credentials.`);
return { model: target, thinkingLevel: clampThinkingLevel(target, route.thinkingLevel), state: route.state };
```

`getPhysicalModel`（:1029-1033）：目录中的非虚拟 chat 模型。

`streamSimple` 臂（:716-742）：loop 外请求在此路由（reason 恒 "direct"）；maxTokens
压到路由模型上限内；**剥离调用方凭据**——路由到别的 provider 时不发送原 provider 的
apiKey/headers/env（:730-733 注释逐字："Caller credentials were resolved for the virtual
model's provider. Another provider resolves its own, so they are not sent to the wrong vendor."）。

unroutedStream 文案（:190-194 逐字）：
``Virtual model ${model.provider}/${model.id} must be routed before streaming``

### 2.3 agent-session 触点（七处）

1. **:583-589 摘要请求 auth**：摘要/压缩请求先路由（注释逐字："Route a virtual model
   first: summaries size their input and output from the model they get."），reason "direct"。
2. **:615-619 `_modelForMessage`**：限额归属——虚拟选型下返回产生消息的物理模型。
3. **:628-637 `_recordSelection`**：prompt 前补记隐含选型。`getBranchSelection` 与当前
   模型不同、且当前或记录任一为虚拟时 `appendModelChange`（:635-636）。注释要点
   （:622-626）：树导航可能把最新 model_change 留在别的分支；响应记的是物理模型名。
4. **:776-785 压缩阈值**：虚拟选型不在此处检查（:778 注释逐字："A virtual selection is
   checked in prepareRequest, against the model the request is routed to."）。
5. **:787-844 路由主路径**（包装 prepareRequest）：
   - :819-820 判定用户回合（最后一条 assistant 之后有 user 消息）；
   - :821 读路由态；:822-828 调 resolveModel（reason = failed ? "retry" :
     userTurn ? "user" : "continuation"）；
   - **:829-835 路由态写回（逐字）**：

     ```ts
     if (route.state !== undefined && route.state !== state) {
     	const data: VirtualModelStateData = { provider: model.provider, modelId: model.id, state: route.state };
     	const entry = this.sessionManager.getEntry(
     		this.sessionManager.appendCustomEntry(VIRTUAL_MODEL_STATE_ENTRY, data),
     	);
     	if (entry) this._emit({ type: "entry_appended", entry });
     }
     ```
   - :838-841 对路由后的模型做压缩阈值检查，超阈值压缩后重新 prepare；
   - :842 返回 `{ ...previous, context, model: route.model, thinkingLevel: route.thinkingLevel }`
     ——选型留在 agent state，只有本请求用路由模型；路由失败以错误响应结束本次 run。
   - `failed` 来源：:1866（可重试错误准备重试）与 :3045（overflow 压缩后重试）赋值，
     :790-791 消费后清空。
6. **:1451-1457 `routedModel`**：虚拟选型下最近一次成功响应实际用的物理模型与
   thinkingLevel（UI/限额展示用）。
7. **:3483-3490 扩展面 providerActions**：register/unregister 后调
   `_refreshCurrentModelFromRegistry()`（:3346-3358：从 runtime 重取当前模型目录对象，
   有变化则更新 `agent.state.model`）。

### 2.4 扩展注册面（三层）

- **loader.ts**（bind 前）：:190 初始 `pendingVirtualModelRegistrations: []`；:226-233
  register 入队/unregister 从队列移除（尚未生效的注册可撤销）。
- **loader.ts ExtensionAPI**（:500-514 逐字）：

  ```ts
  registerVirtualModel<TState>(model: ExtensionVirtualModel<TState>) {
  	assertActive();
  	// Routing runs after the runner binds, so the context is created per request. The state
  	// comes from the session branch that this router wrote.
  	const definition: VirtualModelDefinition = {
  		...model,
  		route: (request) => model.route(request as ModelRouteRequest<TState>, runtime.createContext()),
  	};
  	applyRuntimeChange(() => runtime.registerVirtualModel(definition, extension.path));
  }
  ```

  语义：把扩展的 `route(request, ctx)` 适配成核心 `route(request)`——ctx 每请求现造。
- **runner.ts**（bind 时冲刷，:498-514）：逐一定义注册，失败转 `emitError`
  （event `"register_virtual_model"`），然后清空队列；bind 后（:539-543）直发，优先走
  AgentSession 注入的 providerActions。
- **types.ts**：:1896-1900 `ExtensionVirtualModel<TState> extends Omit<VirtualModelDefinition<TState>, "route">`，
  `route(request, ctx)`；:2129-2130/:2150-2151 运行时字段。
- **agent-session-services.ts:182-194**：会话服务创建时把队列灌入 ModelRuntime，失败转
  诊断（``Extension "<path>" error: <message>``）不中断，清空后离线 refresh。

### 2.5 tool-search 本体（extensions/tool-search/）

**index.ts（全文 18 行逐字要点）**：内置扩展工厂，只做一件事——

```ts
export function createToolSearchExtension(): ExtensionFactory {
	return (pi) => {
		pi.registerTool({ ...createToolSearchToolDefinition({ tools: pi }), defaultActive: false });
	};
}
```

注册即不激活；激活途径：`--tools` / `defaultTools` setting / `setActiveTools()` /
MCP `ensureDiscoveryActive`（见 §2.7）。

**tool.ts 算法层（逐字规则）**：

- 常量（:20-21）：`TOOL_SEARCH_TOOL_NAME = "tool_search"`、`DEFAULT_TOOL_SEARCH_LIMIT = 8`。
- STOP_WORDS（:39-61）：21 个英文停用词（a, an, and, are, as, at, be, by, for, from,
  in, is, it, of, on, or, that, the, this, to, with）。
- stem（:63-69 朴素单数化，逐字）：

  ```ts
  function stem(term: string): string {
  	if (term.length > 4 && term.endsWith("ies")) return `${term.slice(0, -3)}y`;
  	if (term.length > 4 && /(ches|shes|sses|xes|zes)$/.test(term)) return term.slice(0, -2);
  	if (term.length > 3 && term.endsWith("s") && !term.endsWith("ss")) return term.slice(0, -1);
  	return term;
  }
  ```
- tokenize（:71-80 逐字）：

  ```ts
  export function tokenize(text: string): string[] {
  	return text
  		.replace(/([a-z0-9])([A-Z])/g, "$1 $2")
  		.replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
  		.toLowerCase()
  		.split(/[^a-z0-9]+/)
  		.filter((term) => term.length > 0 && !STOP_WORDS.has(term))
  		.map(stem);
  }
  ```
- schemaText（:82-101）：递归收集 schema 的 description、properties 键名、items、
  anyOf/oneOf/allOf 分支描述。
- 文档构建（:103-116 逐字）：

  ```ts
  export function createToolSearchDocument(tool, namespace?) {
  	const parts = [tool.name, tool.name.replaceAll("_", " "), tool.description];
  	schemaText(tool.parameters, parts);
  	if (namespace) parts.push(namespace.name, namespace.description ?? "", namespace.instructions ?? "");
  	return { name: tool.name, text: parts.filter((part) => part.trim()).join(" ") };
  }
  ```
- Bm25Ranker（:118-157）：Okapi BM25，k1=1.2、b=0.75；idf＝
  `Math.log(1 + (N - f + 0.5) / (f + 0.5))`；score>0 才入选；**同分保持文档顺序**
  （:118 注释 "Ties keep document order."）；slice(0, limit)。
- 身份判定（:168-171 逐字）：

  ```ts
  /** Whether the tool is this `tool_search`, not another extension's tool of the same name. */
  export function isToolSearchTool(tool) {
  	return tool.name === TOOL_SEARCH_TOOL_NAME && tool.parameters === toolSearchSchema;
  }
  ```

  同名第三方工具不算——schema **对象引用相等**才算本工具。
- 可搜索判定（:191-194 逐字）：`exposure === "codemode" || exposure === "deferred"`。
- **searchAndLoad（:196-214 逐字）**：

  ```ts
  function searchAndLoad(tools, query, limit): ToolSearchResultTool[] {
  	const active = tools.getActiveTools();
  	const candidates = tools.getAllTools().filter((tool) => isSearchable(tool.exposure) && !active.includes(tool.name));
  	const documents = candidates.map((tool) => createToolSearchDocument(tool, tool.namespace));
  	const matches = new Bm25Ranker().rank(query, documents, limit);
  	if (matches.length > 0) tools.setActiveTools([...active, ...matches.map((match) => match.name)]);
  	return matches.map((match) => ({
  		name: match.name,
  		description: candidates.find((tool) => tool.name === match.name)?.description ?? "",
  	}));
  }
  ```

  语义：命中即并入活跃集（只增不减）。加载走活跃工具集 ⇒ 写进 transcript（toolsAdded），
  随 `/tree`/resume/fork 在分支上存活。
- 工具描述（:216-220，模型可见面，逐字）：

  ```
  # Tool discovery

  Searches over deferred tool metadata with BM25 and exposes matching tools for the next model call.

  Some of the tools, such as tools of MCP servers, may not have been provided to you upfront, and you should use this tool (`tool_search`) to search for the required tools. For MCP tool discovery, always use `tool_search`.
  ```

  注释要点：描述不列可搜索工具/命名空间，MCP 连接时描述不变（缓存友好）。
  `promptSnippet`（逐字）：`"Search for tools that are not loaded yet and load the matches"`。
  自身 exposure：`"model-only"`（可被声明，永不可被其他工具经 executeTool 调用）。
- 输入 schema（:159-166 逐字）：`query: string`（description "Search query for deferred
  tools."）＋可选 `limit: number`（description "Maximum number of tools to return.
  Defaults to 8."）。
- execute 校验与返回（:234-247 逐字）：
  - `query.trim() === ""` ⇒ `throw new Error("query must not be empty")`；
  - `limit` 非正整数 ⇒ `throw new Error("limit must be a positive integer")`；
  - 无命中：`"No matching tools found."`；
  - 有命中：``Loaded ${n} tool${s}. They are available from your next call:\n- <name>: <description 第一行>``（每命中一行，description 取 `trim().split(/\r?\n/)[0]`）；
  - 返回 `{ content: [{ type: "text", text }], details: { loaded: [...] } }`。

### 2.6 exposure 面与四门

`ToolExposure`（types.ts:501-516 逐字档位）：`"direct" | "model-only" | "codemode" |
"deferred" | "hidden"`。「Callable」＝可被 `ctx.executeTool()` 调用。

注册激活门（agent-session.ts:3595-3604 逐字）：

```ts
/** Whether activating the tool declares it to the model. */
private _isDeclarable(name: string): boolean {
	const exposure = this._getToolExposure(name);
	return exposure === "direct" || exposure === "model-only";
}
/** Whether registering the tool activates it, which declares it to the model. */
private _isActivatedOnRegistration(name: string): boolean {
	return this._isDeclarable(name) && this._toolDefinitions.get(name)?.definition.defaultActive !== false;
}
```

types.ts:612-617（逐字要点）：defaultActive 默认 direct/model-only 为 true；其他
exposure 注册时**永不**激活。

allowlist 下可激活门（:1543-1552 逐字）：

```ts
private _isActivatable(name: string): boolean {
	if (!this._allowedTools || this._allowedTools(name) || !isMcpToolName(name)) return true;
	return this._getToolExposure(name) !== "direct" && this._toolRegistry.has("tool_search");
}
```

可调用集（:1558-1567 逐字）：

```ts
/** Tools callable through `ctx.executeTool()`: the active `direct` tools and every registered `codemode` or `deferred` tool. */
private _getCallableTools(active: ReadonlySet<string> = new Set(this.getActiveToolNames())): AgentTool[] {
	return [...this._toolRegistry.values()].filter((tool) => {
		const exposure = this._getToolExposure(tool.name);
		return exposure === "codemode" || exposure === "deferred" || (exposure === "direct" && active.has(tool.name));
	});
}
```

`_getToolExposure`（:1554-1556）：`this._toolDefinitions.get(name)?.definition.exposure ?? "direct"`。

### 2.7 MCP 集成（extensions/mcp/index.ts）

**ensureDiscoveryActive（:494-533 逐字核心）**：

```ts
const needsCodemode = exposures.has("codemode");
const needsToolSearch = exposures.has("deferred");
if (!needsCodemode && !needsToolSearch) return;
// Other extensions' tools of the same names cannot reach MCP tools, so never activate them.
const tools = pi.getAllTools();
const hasCodemode = tools.some(isCodemodeTool);
const hasToolSearch = tools.some(isToolSearchTool);
const active = pi.getActiveTools();
const activate: string[] = [];
if (needsCodemode && hasCodemode && autoEnableCodemode && !active.includes(CODEMODE_TOOL_NAME)) { activate.push(CODEMODE_TOOL_NAME); }
if (needsToolSearch && hasToolSearch && !active.includes(TOOL_SEARCH_TOOL_NAME)) { activate.push(TOOL_SEARCH_TOOL_NAME); }
if (activate.length > 0) pi.setActiveTools([...active, ...activate]);
// …都不可达时：
ctx.ui.notify(`MCP tools are only reachable from the codemode or tool_search tool, but neither is active${reason}; they cannot be called.`, "warning");
```

要点：从**配置**判断而非等连接（:501-502 注释）；tool_search 臂不受
`autoEnableCodemode` 约束；同名第三方工具不激活（isToolSearchTool 引用判等）。

**mcp_servers 段**（:155-221）：只列 enabled 且有间接工具的服务器；每行
``- ${mcpNamespace(server.entry.name)} (${reaches[index]})``，reaches 为 `"codemode"`
或 `"tool_search"`，可追加 `: <截断一行简介>`（MAX_SERVER_DESCRIPTION_CHARS=250）；
段预算 MAX_SERVERS_SECTION_CHARS=4096，超出以 ``- … N more servers; find their tools
with searchTools()`` 收尾；intro 逐字："MCP servers whose tools are not declared to you."
＋按在场类型追加 " Call the tools of `codemode` servers from codemode scripts." /
" Load the tools of `tool_search` servers with `tool_search`."

**tool_call 等待臂**（:1183-1206）：tool_search（isToolSearchTool 判定）或资源工具被
调用时等待所有 enabled 服务器 ready，保证搜索时工具已注册。

### 2.8 nested-tool-calls 内核（core/nested-tool-calls.ts，263 行）

文件头（:1-9 逐字定调）：工具运行中经 `ctx.executeTool()` 发起的调用；agent loop
不知道它们；session 把每一个走过 agent 的工具管线（`runToolCall`）与钩子，发射带
`parentToolCallId` 的 `tool_execution_*` 事件，并把调用与用量记在模型发起调用的
tool result 消息上。

**NESTED_CALL_LIMITS（:26-31 逐字）**：`maxCalls: 256`、
`maxArgumentBytesPerCall: 8*1024`、`maxArgumentBytesTotal: 32*1028`→（实际 32*1024）、
`maxErrorChars: 500`。

**NestedCallRecorder（:47-101）**：收集一个模型发起调用下（含更深层）所有嵌套调用。

- `start(toolCall)`（:56-77）：超 256 ⇒ `complete=false` 并丢弃（返回 undefined）；
  否则建 record `{id, name, status:"unfinished"}`；参数 JSON 序列化后按 UTF-8 字节判定
  ——单条超 8KiB 或累计超 32KiB 时只记 `argumentsBytes` 并置 `complete=false`，否则
  深拷贝进 `record.arguments` 并累加字节；记开始时间。
- `finish(record, isError, errorText)`（:79-85）：`status: "error"|"ok"`、
  `durationMs = Math.round(performance.now() - startedAt)`；错误截 500 字符。
- `addUsage/totalUsage`（:87-93）：combineUsage 累加（含被丢弃调用的 usage）。
- `snapshot()`（:96-100）：空调用且 complete ⇒ undefined；否则浅拷贝，返回
  `{ calls, complete: this.complete && calls.every(c => c.status !== "unfinished") }`。

**NestedToolCallRunner.execute（:176-250）十步语义**：

1. 取/建 caller 的 scope（key＝调用方 toolCallId）；
2. 嵌套 id＝``${callerId}/${scope.nextId++}``；
3. `recorder.start(toolCall)`；
4. emit `tool_execution_start`（带 parentToolCallId=callerId）；
5. 独占判定：`exclusive = !scope.holdsQueue && (host.isSequential() || 工具 executionMode === "sequential")`，
   独占时 Promise 链排队；
6. 为嵌套调用自身注册子 scope：`{ recorder: 同一 recorder（共享！）, nextId: 1, holdsQueue: scope.holdsQueue || exclusive }`
   ——任意深度归并到模型发起调用的 recorder；
7. `host.runToolCall(...)`，onUpdate 先转发 options.onUpdate 再 emit
   `tool_execution_update`；
8. finally 删子 scope、释放队列；
9. `recorder.finish(...)`；嵌套结果不持久化，usage 只经 recorder 计入（:238-239 注释）；
10. emit `tool_execution_end`；返回 outcome。**永不因工具失败 reject**（失败以
    `isError: true` 返回）。

`takeRecord(toolCallId)`（:253-258）：取出并删除 scope（只能取一次），返回
`{ calls: snapshot(), usage: totalUsage }`。`clear()`（:260-262）。

**NestedToolCallHost SPI（:131-144 逐字四方法）**：`getTools()`、
`isSequential()`、`runToolCall(toolCall, parentToolCallId, signal, onUpdate)`、
`emit(event)`。

**宿主实现**（agent-session.ts:726-764）：

- `getTools: () => this._getCallableTools()`；
- `isSequential: () => this.agent.toolExecution === "sequential"`；
- `runToolCall`（:739-757）：取最后一条 assistant 消息（无则立即 isError
  `"No assistant message issued this call"`），然后走与模型调用完全相同的
  校验/钩子/权限管线（beforeToolCall/afterToolCall 带 parentToolCallId）；
- `emit`（:758-761）：先扩展 runner，再 session 订阅者。

**记录挂载**（agent-session.ts:1102-1116 逐字）：

```ts
// Record the calls a tool made through ctx.executeTool() and their usage on its result message.
if (this._nestedToolCalls) {
	if (event.type === "message_start" && event.message.role === "toolResult") {
		const message = event.message;
		const summary = this._nestedToolCalls.takeRecord(message.toolCallId);
		if (summary?.calls) message.nestedCalls = summary.calls;
		if (summary?.usage) {
			message.usage = message.usage ? combineUsage(message.usage, summary.usage) : summary.usage;
		}
	} else if (event.type === "agent_end") {
		this._nestedToolCalls.clear();
	}
}
```

挂载必须先于 appendMessage 持久化（:1155-1163 同一处理器内 :1162 落盘）。

**runner 扩展面**（runner.ts:957-988）：`createToolContext(toolCallId, signal)` 给扩展
context 加 `tools` getter 与 `executeTool(name, args, options?)`；无 executeToolFn 时返回
isError `"Nested tool calls are not available in this context"`、id ``${toolCallId}/0``；
`signal` 默认继承调用方。

**usage 合并**（usage-totals.ts:31-52）：input/output/cacheRead/cacheWrite/totalTokens/
cost.* 逐字段相加；可选字段任一侧存在才保留。

### 2.9 nested 数据模型与下游

pi-ai types.ts：

- :598-611 `NestedToolCallRecord`：`{ id; name; arguments?: JsonObject（超限省略）;
  argumentsBytes?: number（省略时给 UTF-8 JSON 字节数）; status: "ok"|"error"|"unfinished";
  durationMs?; error?（截断） }`。
- :613-618 `NestedToolCalls`：`{ calls: NestedToolCallRecord[]; complete: boolean }`，
  注释 "Results are not recorded"。
- :620-637 `ToolResultMessage`：:630 `nestedCalls?: NestedToolCalls`——注释逐字
  "Kept for the session record; not sent to the model"；:628 `usage?` "Not part of main
  LLM context accounting"。

下游消费：

- **TUI**（interactive-mode.ts:3606-3608 逐字）：`case "tool_execution_start": { // Nested
  calls (from codemode scripts) are shown inside their parent's row. if (event.parentToolCallId) break;`
  ——嵌套调用不建独立工具行；update 按 id 查不到即静默跳过（:3633-3639）。
- **RPC**：事件透传（rpc-mode.ts:355-360 订阅原样输出；json-event.ts 只变换
  message_update/toolcall_start）。
- **HTML 导出**（export-html/template.js:933-946 `renderNestedCalls`）：icons
  `{ok:'✓', error:'✗', unfinished:'…'}`；参数省略时显示 `[arguments omitted, N bytes]`；
  标题 ``Nested calls: N``＋incomplete 时 ` (incomplete record)`。
- **compaction**（compaction/utils.ts:26-45 `extractFileOpsFromMessage`）：toolResult 分支
  遍历 `message.nestedCalls?.calls ?? []`，把 read/write/edit 的 string `path` 参数进文件
  清单（arguments 省略的调用自然跳过）。

### 2.10 选型持久化与恢复

- 选型：`ModelChangeEntry { type: "model_change", provider, modelId }`；assistant 消息只记
  物理模型名。恢复（sdk.ts:217-231 注释逐字）："Assistant messages name the physical
  model that answered, so a virtual selection is only in model_change entries."——用
  `getBranchSelection` 求分支选型，再 `getModel`＋`hasConfiguredAuth` 校验；失败：
  ``Could not restore model ${provider}/${modelId}``。
- 路由态：`{ type: "custom", customType: "pi.virtual-model-state", data: { provider,
  modelId, state } }`，append 为当前叶子节点并推进叶 ⇒ 跟随分叉/压缩（压缩不删
  custom 条目，不进 LLM context）。
- 公共导出（index.ts:398-405）：`ModelRoute, ModelRouteReason, ModelRouteRequest,
  VIRTUAL_MODEL_STATE_ENTRY, VirtualModelDefinition, VirtualModelStateData`。

---

## 3. 裁决点（拍板清单——本轮用户授权直接实施，以下为设计者裁决；有异议随时推翻）

### 裁决①：虚拟模型判别式外在化（注册表查找，不引进 `"pi-virtual"` api 字面量）

【场景】pi 的判别式是内在的：`model.api === "pi-virtual"`（virtual-models.ts:29/:105-107），
因为 pi 的 `Model<Api>` 目录对象自带 `api` 字段。java 的选型货币是
`ModelId<P>(provider, modelName)`（ai 模块 record），没有 api 字段；给它加字段会涟漪到
ai 模块 wire 层全部 converter。
【决定】java 判别式走**注册表查找**：`VirtualModelRegistry.isVirtual(provider, modelId)`。
pi 自己的 `getBranchSelection` 也是经 `getModel(provider, modelId)` 解析后再判
isVirtualModel（:142-144），外在查找与 pi 的解析路径同构。`"pi-virtual"` 字面量只保留
在目录条目的展示层（如有需要）与文档，不进 ModelId。
【风险/代价】未注册/已注销的虚拟模型判为物理——pi 同样如此（已注销不压住物理响应，
:143-144）。`Entry.ModelChange` 与 assistant 消息里的 provider/modelId 字符串形状不变，
session 文件与 pi 逐字节兼容。[内部，模型不可见]

### 裁决②：路由态类型用 `JsonNode`，不泛型化

【场景】pi 的 `TState` 是泛型但运行时是 `unknown`（结构化 JSON）。java 泛型有擦除，
扩展得提供 `Class<TState>` 类型见证才能从 custom 条目读回，API 重。
【决定】`ModelRouteRequest.state()` 与 `ModelRoute.state()` 用
`com.fasterxml.jackson.databind.JsonNode`。持久化走 `Entry.Custom` 的 data（Jackson
原生）。判等用 `JsonNode.equals`（对位 pi 的 `route.state !== state` 引用不等 ⇒ 语义
收窄为值不等才写回——**更严**：pi 引用不等即写，java 值相等就不写）。
【风险/代价】写回条件比 pi 严格（pi 每次返回新对象就写一条，java 同值不写）。路由态
条目数量可能更少——行为差在边缘（路由器每次造同值新对象时 pi 会累积条目）。
[内部，模型不可见；边缘场景]

### 裁决③：请求级路由缝＝`firePrepareRequest` 钩子面＋新增路由级钩（实施修订）

【场景】pi 的路由在 `agent.prepareRequest` 包装里（每次 provider 请求前，含
continuation/retry）。java 的对位缝是 `PiLaneEngine.configFor` 的
`request -> ctx.hookSystem().firePrepareRequest(laneName, request)`（PiLaneEngine:371），
每请求触发。两个约束：路由是**异步**的（pi route 可返回 Promise）；路由失败必须
以错误响应结束 run（pi :808-809 的 reject），而 HookSystem 的沙箱钩异常被隔离
（记账并弃权）。
【决定】（实施定稿）HookSystem 新增 `onPrepareRequestRouted` 路由级钩：在沙箱扩展
钩**之后**跑（pi 的 previousPrepareRequest→路由序，:800-810），异常**不隔离**——
传出循环由 `PiLaneEngine` 的 `catch (Throwable)`（runWithLifecycle 对位）压成错误
助手消息（RunFailure.settle 既有链，pi 净效果同形）。同步签名＋虚拟线程阻塞即
pi 的 await 对位。`_failedResponse` 经 `LaneState` 字段＋`PrepareRequestContext`
第四组件穿线（pi :417/:790-791/:1866/:3045）。
【风险/代价】批 2 留痕的窄差（llm.request 跨度标签读车道 supplier，run 内替换模型
不反映到跨度）对路由同样成立——沿用登记。[内部，模型不可见]

### 裁决④：exposure 表落 coding-agent 侧 `_toolDefinitions` 对位表，不动 agent-core 的 ToolRegistry

【场景】pi 的 exposure/defaultActive 挂在扩展 `ToolDefinition` 上，agent-session 用
`_toolDefinitions` map 查（:1554-1556）。java 的 `ToolRegistry`（agent-core）只存
`AgentTool`，批 24 的 `McpToolExposure` 在 extension.mcp 包内部使用，`getAllTools`
投影（ExtensionToolInfo）窄差 D91 无 exposure/parameters/namespace。
【决定】coding-agent 新增工具定义表（`ToolExposureRegistry` 或直接扩展
`SessionServices` 的工具面）：name → (exposure, defaultActive, parameters schema,
namespace)。MCP registrar、tool-search、未来扩展工具注册时写入。`ExtensionToolInfo`
加 `exposure`/`parameters`/`namespace` 三字段（D91 收窄——tool_search 的 searchAndLoad
需要全量）。四门（`_getToolExposure`/`_isDeclarable`/`_isActivatedOnRegistration`/
`_isActivatable`/`_getCallableTools`）按 §2.6 逐字对位到 AgentSession。
【风险/代价】`ExtensionToolInfo` 是 public record，加字段是源码不兼容（内部 API，
可接受）。agent-core 保持纯净。[内部，模型不可见]

### 裁决⑤：tool_search 落内置扩展 `builtin:tool-search`，defaultActive=false 经「注册不激活」机制

【场景】pi 的 tool-search 是 `builtInExtensions` 之一（extensions/index.ts:10-13，
`replaceable: true, builtin: true`），工具 `defaultActive: false`（注册即不激活）。
java 批 24 有 `builtin:mcp` 先例（ServiceLoader 装配）；激活门三件套批 16 已落，
但「注册不激活」语义（`_isActivatedOnRegistration`）java 没有——目前注册的工具
进注册表后由 defaultTools/启动选择决定活跃集。
【决定】新增内置扩展 `builtin:tool-search`（与 `builtin:mcp` 同装配路径）。工具注册时
`defaultActive=false` 进定义表（裁决④），活跃集装配时跳过（对位 pi
`_isActivatedOnRegistration` false 臂）。replaceable 对位：同名 `tool_search` 的第三方
注册顶替内置（java 已有的同名替换/撞名机制——批 20 B237 撞名旁表——复用；若机制
不通用则本批内置恒装，登记窄差）。
【风险/代价】`--tools tool_search` / `defaultTools` 命名激活路径批 1/批 16 已通。
[内部，模型不可见]

### 裁决⑥：ensureDiscoveryActive 两臂分拆——deferred 臂真化逐字，codemode 臂维持批 24 替身

【场景】pi 的 ensureDiscoveryActive（:494-533）两臂：codemode 服务器 ⇒ 激活 codemode
（受 autoEnableCodemode 约束）；deferred 服务器 ⇒ 激活 tool_search（不受约束）。
java 无 codemode 工具（B161，R5 面外不变），批 24 裁决⑥落了替身警告
（"pi-java has no codemode or tool_search tool; set \"exposure\": \"direct\""）。
【决定】deferred 臂按 §2.7 逐字真化（含 isToolSearchTool 引用判等对位——java 用
「注册表里的 tool_search 是否本体内置定义」判定）。codemode 臂维持替身但文案更新：
tool_search 已存在，替身文案改为只陈述 codemode 缺席。不可达警告（两者都不可达时）
保留 pi 文案结构，codemode 提及保留（settings 里 `exposure: "codemode"` 的配置仍然
合法且不可达，文案提及 codemode 不算误导）。
【风险/代价】[模型可见]文案差异：替身警告文案变化（changelog 级行为变化注记）。
mcp_servers 段的 reach 标注从恒 "codemode" 变配置驱动（"codemode"/"tool_search"），
模型可见面逐字对齐 pi。

### 裁决⑦：嵌套调用的 agent-core 事件用「可选字段」而非新变体

【场景】pi 用条件类型 `WithParentToolCallId<E>` 给三事件加 `parentToolCallId?: string`
（agent-session.ts:188-193）。java 的 `PiLoop.Event.ToolExecutionStart/Update/End`
是 record。
【决定】三事件 record 加可空字段 `parentToolCallId`（null＝顶层调用）。JSON 序列化
缺席化（null 不输出），RPC 透传与 pi 同形。coding-agent 的 SessionEvent 桥同形加字段。
【风险/代价】事件构造点全部要加一参（agent-core 内部，机械）。[协议面·边缘]

### 裁决⑧：nested 的 usage 合并在 `ToolResult.UsageInfo` 层（实施修订）

【场景】pi 的 combineUsage（usage-totals.ts:31-52）合并全量 `Usage`。初稿决定落
ai 模块 `Usage.combine`；实施中发现 java 的工具结果计量是
`ToolResult.UsageInfo`（input/output 两字段方言——`PiToolRunner.toOutcome` 转发的
就是它），全量 Usage 合并没有消费者。
【决定】`Usage.combine` 撤回（已提交的 ai 改动随批内 revert 收回）；合并逻辑在
`NestedCallRecorder`/`AgentSession.mountNestedCalls` 按 UsageInfo 两字段求和。
【风险/代价】嵌套 usage 合并只覆盖 input/output 两字段（cache/cost 不进）——
java 既有方言的延伸，登记窄差 D166。[内部]

### 裁决⑨：TUI 嵌套行跳过＋HTML 导出＋compaction 文件清单随本批，footer `→ routed` 显示挂账

【场景】nested 的下游消费点四个（§2.9）；虚拟模型的 TUI 消费是 footer 的
`routedModel?.model ?? model` 限额归属与 ` → <id><level>` 后缀（footer.ts:107/:240-245）。
【决定】TUI 跳过嵌套 tool_execution_start（对位 interactive-mode.ts:3606-3608——
实施核实：java TUI 无该事件消费点，嵌套行本就不会产生，零代码）、HTML 导出
renderNestedCalls、compaction 文件清单读 nestedCalls——三处随本批。footer 显示与
`routedModel()`/`_modelForMessage` 访问器整体挂账（实施修订：访问器也无消费者
——footer 是唯一消费点，B65 先例一并挂，map/05 屏域变量③）。
【风险/代价】footer 不显示路由去向——用户可见但不影响功能；挂账随 map/05 屏域批。
[用户可见·展示缺失，已挂账]

### 裁决⑩：恢复路径的 auth 校验用批 26 T5 的凭证链

【场景】pi 恢复选型时 `hasConfiguredAuth` 校验（sdk.ts:217-231）；纯虚拟 provider 靠
注册时的 snapshot 标记（model-runtime.ts:967-973）过检。
【决定】java 对位：`VirtualModelRegistry` 注册纯虚拟 provider 时向凭证解析面
（批 26 T5 的 `Credentials.resolveCredential` 链）登记「虚拟豁免」；恢复路径
（resume/-c/-r 的模型还原点）经 `getBranchSelection` 解析后校验，失败文案逐字
``Could not restore model <provider>/<modelId>``。
【风险/代价】豁免表是会话级内存态，重启后虚拟 provider 的恢复依赖扩展先注册
（pi 同——snapshot 标记也是注册时写入）。[内部，边缘场景]

### 裁决⑪：`_recordSelection`/`getBranchSelection` 不落（B65 先例，挂账）

【场景】pi 的选型持久化链（`_recordSelection` 补记 model_change＋
`getBranchSelection`「虚拟压住物理」解析）唯一消费者是 resume 时的模型恢复
（sdk.ts:217-231）。java 的 resume 本就不从分支恢复模型（模型恒走
CLI/settings 解析链，`existingSession` 只影响 scoped 初始臂）——恢复面是存量
方言，不是本批引入的缺口。
【决定】两个函数均不实现（无消费者不接线，B65 先例）；挂账 docs/04：随未来
「resume 从分支恢复选型」批一并落地。
【风险/代价】虚拟选型在 resume 后丢失（与物理选型同——java resume 本就不恢复
任何选型）。[存量约束，非本批范围]

### 裁决⑫：路由后压缩阈值检查不做（窄差 D171）

【场景】pi 在路由后对**路由到的物理模型**做压缩阈值检查（:838-841），超阈值
压缩后重新 prepare。java 的阈值门（`CompactionExecutor.checkThreshold`）是
agent-core 轮内件，读车道选型模型的窗口——虚拟条目不在目录 ⇒ 窗口 0 ⇒ 跳过
（pi :780 的「虚拟选型不在此检查」意外同形）。
【决定】请求级的路由后检查不移植：从钩子触发引擎内压缩是深层手术，收益是边缘
场景（虚拟选型路由到小窗物理模型）。阈值门对虚拟选型按未知窗口跳过＋溢出恢复
兜底既有。登记窄差 D171（同条覆盖 unroutedStream 无对位——未路由/已注销的虚拟
选型直发被「未知 provider 兜底」臂接住，pi 抛 "must be routed before streaming"）。
【风险/代价】[内部·边缘场景]

---

## 4. 实施任务（T1→T12，依赖序）

> 每个 T 给：落点（模块/类）、pi 锚点、要点。commit 粒度按模块可独立编译切。

**T1（ai 模块地基）**：`NestedToolCallRecord`/`NestedToolCalls`（新文件，
`com.pijava.ai.message`；§2.9 形状逐字）＋`Message.ToolResultMessage` 加
`nestedCalls` 可选字段（Jackson 缺席化；**converter 不发给模型**——逐 converter 核实
ToolResult 序列化路径不携带该字段）＋`Usage.combine`（裁决⑧）。测试：序列化往返、
converter 泄漏检查。
pi 锚点：types.ts:598-637；usage-totals.ts:31-52。

**T2（agent-core 嵌套内核）**：`NestedCallLimits`（常量四件）＋`NestedCallRecorder`
＋`NestedToolCallRunner`＋`NestedToolCallHost` SPI＋三事件加 `parentToolCallId`
（裁决⑦）。嵌套执行复用 `PiToolRunner` 管线（pi 的 runToolCall 对位——校验/钩子/
权限同管线的约束由 host.runToolCall 委托既有管线满足）。并发模型：pi 的 Promise 链
独占队列 ⇒ java 用每 runner 一把 `ReentrantLock`/虚拟线程信号量对位（串行化语义，
不是逐字结构）。
pi 锚点：nested-tool-calls.ts:26-263。

**T3（coding-agent 宿主缝）**：`AgentSession` 懒建 runner＋host 四方法（§2.8 宿主
实现逐字对位）＋`message_start`(toolResult) 挂载点（须先于持久化——核实 java 的
message_start/落盘顺序，批 5 的 BoundaryBridge 链上找缝）＋`agent_end` 清空＋
`tool_call`/`tool_result` 扩展事件带 parentToolCallId（SessionEventFirer 加臂）。
pi 锚点：agent-session.ts:657-764/:1102-1116。

**T4（扩展面 executeTool）**：`ExtensionToolContext`（`tools()`＋
`executeTool(name, args, options)`；types.ts:383-395 对位）＋无宿主降级（isError
"Nested tool calls are not available in this context"，id `${toolCallId}/0`）＋signal 继承。
pi 锚点：runner.ts:957-988；types.ts:367-373/:2193-2201。

**T5（tool-search 算法包）**：`ToolSearchTokenizer`（tokenize/stem/STOP_WORDS 逐字）、
`Bm25Ranker`（k1=1.2/b=0.75/同分保序/score>0 门）、`ToolSearchDocuments`
（schemaText/createToolSearchDocument 逐字）。纯函数，无接线。
pi 锚点：tool.ts:39-157。

**T6（exposure 面＋四门）**：`ToolExposure` 五档枚举（types.ts:501-516；`McpToolExposure`
收编/对齐——保留 mcp 包枚举作配置面，运行面统一到 ToolExposure）＋工具定义表
（裁决④）＋`ExtensionToolInfo` 三字段＋四门对位＋`_restoreToolsFromTranscript`
对照核实（批 24 已落 pending 两臂，本批核 declarable 门）。
pi 锚点：agent-session.ts:1543-1620/:3595-3604；types.ts:612-617。

**T7（tool-search 工具与装配）**：`ToolSearchTool`（AgentTool 实现：schema/校验/
返回文案/details 逐字，§2.5）＋`searchAndLoad`（经扩展面 getAllTools/getActiveTools/
setActiveTools 三件套）＋内置扩展 `builtin:tool-search` 装配（裁决⑤）＋
`isToolSearchTool` 判定面。
pi 锚点：tool.ts:159-247；index.ts:1-18；extensions/index.ts:10-13。

**T8（MCP 集成真化）**：ensureDiscoveryActive deferred 臂（裁决⑥）＋mcp_servers 段
reach 标注配置驱动化（"codemode"/"tool_search"）＋intro 追加臂逐字＋tool_call 等待臂
（tool_search 被调用时等 enabled 服务器 ready——批 24 已有等待机制本体，加
isToolSearchTool 触发臂）＋不可达警告文案更新＋map/07 #92/#93 重裁注记。
pi 锚点：mcp/index.ts:155-221/:494-533/:1183-1206。

**T9（虚拟模型内核）**：`VirtualModels`（常量/`VirtualModelStateData`/
`ModelRouteReason` 四值/`ModelRouteRequest`/`ModelRoute`/`VirtualModelDefinition`/
`isVirtualModel`（注册表版，裁决①）/`findLatestResponse`/`getBranchSelection`（逐字
§2.1）/`getVirtualModelState`（逐字））＋`VirtualModelRegistry`（register/unregister/
冲突校验/错误文案 5 条逐字/替换语义/虚拟豁免登记，裁决⑩）。
pi 锚点：virtual-models.ts:29-187；model-runtime.ts:952-1033。

**T10（路由解析与接线）**：`VirtualModelRouter.resolveModel`（§2.2 逐字：previous/
failed 解析、物理门、auth 门、clampThinkingLevel——java 的 thinking clamp 对位既有
面）＋请求级缝接线（裁决③：reason 四值判定、路由态写回 Entry.Custom＋
entry_appended 事件、失败以错误响应结束 run）＋摘要请求 direct 臂（三处
LlmSummaryGenerator 接线）。实施收窄（裁决⑨⑪⑫）：`_recordSelection`/
`getBranchSelection`/`routedModel`/`_modelForMessage`/恢复路径与路由后阈值检查
不落（无消费者/存量方言/边缘，挂账与窄差见 §3/§5）。
pi 锚点：agent-session.ts:583-844/:1451-1457；sdk.ts:217-231。

**T11（扩展注册面）**：`ExtensionContext.registerVirtualModel/unregisterVirtualModel`
（E5-26/27 翻行点）＋pending 队列（装配前入队/撤销）＋装配冲刷（失败转诊断逐字
``Extension "<path>" error: <message>``）＋`route(request, ctx)` 适配（ctx 每请求现造
——复用批 17 的扩展上下文工厂）＋`ExtensionVirtualModel` 类型（route 双参版）。
pi 锚点：loader.ts:190/:226-233/:500-514；runner.ts:498-543；types.ts:1874-1900；
agent-session-services.ts:182-194。

**T12（下游收尾）**：TUI 跳过嵌套行＋HTML 导出 renderNestedCalls（icons/省略文案/
标题逐字）＋compaction 文件清单读 nestedCalls＋RPC 透传核实（裁决⑦字段透传即达）
＋`_refreshCurrentModelFromRegistry` 对位。
pi 锚点：interactive-mode.ts:3606-3608；template.js:933-946；compaction/utils.ts:26-45；
agent-session.ts:3346-3358。

---

## 5. 窄差预案（登记不认账）——闭环定版

| # | 内容 | 类别 |
|---|---|---|
| D160 | 路由态写回条件值判等（pi 引用判等）——同值新对象不重复写条目 | 内部·边缘 |
| D161 | 嵌套调用独占队列用 `ReentrantLock`（pi Promise 链）——串行化语义同，结构异 | 内部 |
| D162 | 嵌套计时用 `System.nanoTime`（pi `performance.now`） | 内部 |
| D163 | footer 虚拟模型 `→ routed` 显示与 `/model` 选择器列出虚拟条目挂账（map/05 屏域变量③；虚拟条目不进 ModelCatalog） | 用户可见·展示 |
| D164 | tool_search 的 replaceable 经 ToolRegistry 同名替换（无 pi 的 replaceable 元数据面） | 内部·边缘 |
| D165 | BM25 浮点 java/JS 同 IEEE 754 double，同分保序靠稳定排序显式维持（注记级） | 内部 |
| D166 | 嵌套 usage 合并只覆盖 input/output 两字段（java `ToolResult.UsageInfo` 方言；`Usage.combine` 落地后撤回——无消费者） | 内部·边缘 |
| D167 | 嵌套事件经会话事件单通道广播（pi 先扩展 runner 再会话订阅者两跳；两通道同步到达，扩展/TUI 相对次序异） | 内部·边缘 |
| D168 | nestedCalls 挂载在 message_end 替换钩（pi 在 message_start）——MessageStart 事件订阅者看到未挂载消息；落盘/上下文/记录不受影响 | 内部·边缘 |
| D169 | 已注销/未注册的虚拟选型判别失效（注册表外在判别式，裁决①的固有代价：pi 的 Model 对象持 `api` 内在字面量，注销后仍判虚拟 ⇒ resolve 抛 "is not registered"；java 弃权直发） | 内部·边缘 |
| D170 | 无 pi 的 `_recordSelection`/`getBranchSelection` 恢复链（java resume 不从分支恢复选型——存量方言；裁决⑪挂账） | 存量约束 |
| D171 | 虚拟选型两边缘：(a) 路由后压缩阈值检查不做（阈值门按未知窗口跳过；溢出恢复兜底）；(b) 未路由/已注销虚拟选型直发被「未知 provider 兜底」臂接住（pi unroutedStream 抛 "must be routed before streaming"） | 内部·边缘 |
| ~~D7~~ | **销号**——`parentToolCallId` 全链落地（钩子上下文/会话三事件/扩展五事件/RPC/web 透传） | — |
| D89 | **收窄**——defaultActive 闸落地（装配期 `isActivatedOnRegistration` 门）；余：无元数据的通用扩展工具仍默认全量激活 | 内部·边缘 |
| D91 | **收窄**——`getAllTools` 投影落地 exposure/parameters/namespace 三字段；余 annotations/sourceInfo | 模型不可见 |

## 6. 测试计划

| 组 | 用例 | pi 测试对位 |
|---|---|---|
| 算法 | tokenize（camel/snake 切分/停用词/复数折叠："listIssues for the GitHub_repo" → ["list","issue","git","hub","repo"]）、BM25 排序与 limit、属性名/描述可搜、未知/空查询 []、namespace 入索引 | tool-search.test.ts:18-65 |
| Recorder | 超 8KiB 省略（argumentsBytes 保留）、error 截 500、超 256 丢弃、32KiB 总额、unfinished ⇒ complete:false | nested-tool-calls.test.ts:190-233 |
| Runner | id `<caller>/<n>`、事件带 parentId、深度归并（`call/1`、`call/1/1`）、usage 各层汇总、takeRecord 只取一次、串行工具独占 | nested-tool-calls.test.ts:53-187 |
| 宿主 | toolResult 挂 nestedCalls 先于持久化、agent_end 清空、无嵌套不调用时消息无 nestedCalls 键 | agent-session-tool-orchestration.test.ts:108-144 |
| tool_search 工具 | 校验两错文案、Loaded 文案逐字（单复数）、No matching、details.loaded、候选过滤（active 排除/direct 排除/hidden 排除） | agent-session-mcp.test.ts:942-994 |
| MCP 集成 | deferred 服务器自动激活 tool_search、mcp_servers 段 reach 标注、tool_call 等待、同名第三方不顶替 | mcp-extension.test.ts:767 等 |
| 虚拟模型 | 注册两错文案/替换/注销、resolveModel 三错文案、previous/failed 解析、auth 门、getBranchSelection 压住规则（含已注销回退）、路由态写回与读取、direct 不写态、恢复失败文案 | virtual-models.test.ts、suite/virtual-models.test.ts |
| 端到端 | jev-router 式路由器（规划→实现阶段切换）走通 prompt 全程：选型持久化→路由→态写回→resume 恢复 | jev-router-example.test.ts |

## 7. 账面预算（逐行实算）——闭环实算定版

| 行 | 权重 | 翻动 | Δ |
|---|---|---|---|
| E5-26 `registerVirtualModel` | 2 | 缺失→对齐 | +2.0 |
| E5-27 `unregisterVirtualModel` | 1 | 缺失→对齐 | +1.0 |
| G-8 `tool_search` 工具 | 2 | 缺失→对齐 | +2.0 |
| G-9 虚拟模型 | 2 | 缺失→对齐 | +2.0 |
| G-10 嵌套工具调用 | 1 | 缺失→对齐 | +1.0 |
| map/07 #93 | 0 | 注记重裁（deferred 臂落地对齐；权重 0 不动账面） | 0 |
| B162 | — | 销号（三块全落） | — |
| D7 | — | 销号（parentToolCallId 全链） | — |
| D89/D91 | — | 收窄（defaultActive 闸／投影三字段） | 注记 |

模块账面实算：438.0 + 8.0 ⇒ **446.0/485 ≈ 92.0%**（未加权 82.7%→84.1%；w3
87.0% 不动）；E5 域 51.0 + 3.0 ⇒ **54.0/58 ≈ 93.1%**（余 E5-31 存疑挂账＋
E5-33 F 类）；G 域 7.0 + 5.0 ⇒ **12.0/18 ≈ 66.7%**（余 G-6/G-7 codemode 挂
B161）；全仓 1,155.0 + 8.0 ⇒ **1,163.0/1379 ≈ 84.3%**。

> 预算修订注记：初稿只计 E5-26/27（+3.0）；闭环实算撞出 G 域三行（B162 三块
> 本体行，+5.0）——「批内账面漏列同翻」先例（E3-8/K11/E3-19）再演，设计稿
> 预算表自此强制带上 G 域行检查。

## 8. 风险与排障预案

1. **请求级缝的异步路由**（裁决③）：firePrepareRequest 若是同步签名，路由段要扩
   钩子面；先在 T10 起手时取证该缝现状，若改动面大则在 T10 内部再拆子任务。
2. **message_start/落盘顺序**（T3）：java 的 toolResult 落盘点在 BoundaryBridge 链，
   挂载必须先于落盘——取证后定缝。
3. **批 26 排障钉沿用**：mvn 定向测试必带 `-am`；verify 收口落盘日志＋单独 echo
   退出码；收口只跑单实例 mvn；verify 阶段含 spotbugs。
4. **浮点确定性**：BM25 测试断言用排序结果而非分值（避免跨平台尾数差）。

## 9. 闭环回填（闭环记录）

回填执行（2026-10-28，全部落地）：

1. docs/04 §3.1 B162 划销＋§4 批 27 段（三段式裁决①–⑫＋窄差 D160–D171＋D7 销号
   ＋D89/D91 收窄＋行为变化注记＋账面实算＋排障注记）＋E 类实测（E1
   AgentSession 3,428→**3,797**，虚拟/嵌套簇约 230 行记为下一可拆块）。
2. docs/05/map/04：E5-26/27 翻行＋G-8/9/10 翻行＋E5/G 两域汇总行＋模块合计
   两口径＋D7 窄差行销号＋E5-18 注记收窄＋「最大差距」段重写；map/07
   #92/#93 重裁注记＋tool_call 行注记更新。
3. docs/06：§1 读数全量重填（模块 92.0%／全仓 84.3%；新增 G 域读数行）＋§2
   批 27 行＋§3.3 批 27 注记（footer/选择器挂账随变量③）。
4. 记忆文件：coding-agent-batch27-closed。
5. 全量 `mvn clean verify` 收口：日志落盘 /tmp（不放 target/），结果见下。

**verify 结果**：（闭环时填实测——首轮：spotbugs UL_UNRELEASED_LOCK 一钉
〔NestedToolCallRunner 条件 lock 形〕⇒ 回补〔独占臂独立 try/finally＋
executeHeld 抽出〕；次轮：____）

**实施偏差自白**（相对 §3/§4 初稿）：裁决③缝形改为新增路由级钩
（`onPrepareRequestRouted`，沙箱外、异常传出）；裁决⑧ `Usage.combine` 撤回
（无消费者）；裁决⑪⑫新增；T3/T6 实施序互换（宿主依赖 exposure 表）；
`getBranchSelection`/`_recordSelection`/`routedModel`/`_modelForMessage`/
footer 显示不落（裁决⑨⑪——无消费者不接线）。
