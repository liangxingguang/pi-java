# coding-agent 批 20 设计稿：RPC/协议尾——disposition 三态＋get_state 三字段＋new_session 修形＋attribution 头层

【背景摘要】本批是 coding-agent 对齐批次的第 20 批（总账 docs/20 §3 序 19，
「RPC/协议尾：B237＋B253＋E7 存疑，小项合包」）。收编五块：① **B237/E7-6**——
RPC prompt/steer/follow_up 的 disposition 三态（java 只产 `started/queued`，
pi 还有 `handled`：扩展命令即执行＋input 事件吞没两臂；java 的 prompt 路 input
臂批 15 已落，缺**扩展命令臂**与 **steer/followUp 全序**）；② **E7-3**——
get_state 三字段（B31 model 全对象／B32 sessionName 缺席化／B33 messageCount
工作副本口径）；③ **E7-2/E7-4 核销**——批 17 已落 extension_ui 生产者与按 id
路由、包④ 已闭 B27–B29，本批验证翻行；④ **新发现**：`new_session` 响应形状差
（java 发全量 state，pi 发 `{cancelled}`）＋`parentSession` 未收＋`before_switch`
取消臂缺（即登即销 B257）；⑤ **B253**——attribution 头层（pi
`provider-attribution.ts` 全件：openrouter/nvidia/cloudflare/opencode 四家头
＋安装遥测门）。批 19 闭环后模块账面 388.0/485 ≈ 80.0%。

术语（首现释义）：

- **disposition 三态**：pi RPC prompt 回执 `data.disposition` 的三种取值——
  `handled`（输入在预检阶段被处置：扩展命令已执行或 input 事件吞没，未进
  LLM）、`queued`（流式中排队 steer/followUp）、`started`（起了 run）。
- **扩展命令**：扩展经 `pi.registerCommand`（java：`ctx.slashCommands().register`）
  注册的斜杠命令。pi 在 `AgentSession.prompt()` 内**所有模式统一执行**
  （agent-session.ts:1978-1984），先于压缩门与 input 事件；内置命令
  （/model 等）是 TUI 本地件，不在此列。
- **工作副本**：`agent.state.messages`——引擎内存里的消息列表（压缩/树导航会
  就地重写），区别于**转录**（持久层条目序列，含 compaction/model_change 等
  非消息条目）。
- **attribution 头层**：pi 给特定 provider 线上请求注入的固定头集（来源标识
  用途），在 `before_provider_headers` 扩展钩**之前**并入，故扩展能看到合并
  后的全集。
- **安装遥测门**：`isInstallTelemetryEnabled`——`PI_TELEMETRY` 环境变量在场
  时按 truthy（`1`/`true`/`yes`，大小写不敏感）裁决，否则读 settings 的
  `enableInstallTelemetry`（默认 `true`）。

---

## §0 基线与预期翻动

| 指标 | 前（批 19 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485，去重） | 388.0 ≈ 80.0% | **392.0 ≈ 80.8%**（+4.0） |
| E7 域（Σ 12） | 8.0 ≈ 66.7%（对齐 2/存疑 4） | **12.0 = 100%**（对齐 6，**存疑清零**） |
| 全仓（Σ 1379） | 1,069.5 ≈ 77.6% | **1,073.5 ≈ 77.8%**（+4.0） |

翻动行（4 行，+4.0；存疑计半 c=0.5）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|---|
| E7-2 extension_ui 双向通道 | 2 | 存疑 | 对齐 | T3（核销：批 17 已落生产者＋按 id 路由，本批验证） |
| E7-3 get_state 状态字段 | 2 | 存疑 | 对齐 | T2（B31/B32/B33 三修） |
| E7-4 RPC 事件线格式 | 3 | 存疑 | 对齐 | T4（核销：B27–B29 包④ 已闭，补 parity 测试验证） |
| E7-6 prompt 回执 disposition | 1 | 存疑 | 对齐 | T1（handled 两臂＋steer/followUp 全序） |

台账销号预期：**B237、B253、B31、B32、B33** 销号；**B257 即登即销**
（new_session 响应形状＋parentSession＋before_switch 臂，本批取证新发现）。
B253 无 map 行（寄存器单项；D33 窄差随闭环改写为余两层）。

不动行：E7-1（对齐）/E7-5（对齐）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`）

### 1.1 disposition 三态全链（B237/E7-6）

**类型定义**（agent-session.ts:310-311）：

```ts
export type QueuedInputDisposition = "handled" | "queued";
export type PromptDisposition = QueuedInputDisposition | "started";
```

**prompt() 预检序**（agent-session.ts:1968-2110，逐字要点）：

```ts
async prompt(text: string, options?: PromptOptions): Promise<void> {
	...
	const expandPromptTemplates = options?.expandPromptTemplates ?? true;
	const preflightResult = options?.preflightResult;
	// Handle extension commands first (execute immediately, even during streaming)
	if (expandPromptTemplates && text.startsWith("/")) {
		const handled = await this._tryExecuteExtensionCommand(text);
		if (handled) {
			preflightResult?.("handled");        // :1981
			return;
		}
	}
	if (this._compactionAbortController !== undefined) {
		throw new Error("Cannot submit a prompt while compaction is in progress. ...");
	}
	// Emit input event for extension interception (before skill/template expansion)
	const processedInput = await this._runInputHandlers(text, options?.images, ...);
	if (!processedInput) {
		preflightResult?.("handled");            // :2000
		return;
	}
	// ... /skill: 与模板展开 → 流式排队（preflightResult?.("queued") :2024）
	// ... 模型/auth 校验 → before_agent_start → preflightResult?.("started") :2109
}
```

序：**扩展命令臂 → 压缩门 → input 事件 → 展开 → 流式排队门**。扩展命令臂在
压缩门**之前**（压缩中扩展命令照执行，对照 interactive-mode.ts:3362-3370
注释 "extension commands execute immediately"）。

**_tryExecuteExtensionCommand**（agent-session.ts:2116-2141 逐字）：

```ts
private async _tryExecuteExtensionCommand(text: string): Promise<boolean> {
	const spaceIndex = text.indexOf(" ");
	const commandName = spaceIndex === -1 ? text.slice(1) : text.slice(1, spaceIndex);
	const args = spaceIndex === -1 ? "" : text.slice(spaceIndex + 1);
	const command = this._extensionRunner.getCommand(commandName);
	if (!command) return false;
	const ctx = this._extensionRunner.createCommandContext();
	try {
		await command.handler(args, ctx);
		return true;
	} catch (err) {
		this._extensionRunner.emitError({
			extensionPath: `command:${commandName}`,
			event: "command",
			error: err instanceof Error ? err.message : String(err),
		});
		return true;                            // 出错也是 handled
	}
}
```

**steer/followUp 全序**（:2211-2234 → _queueUserInput :2173-2200 逐字）：

```ts
async steer(text, images?, options?): Promise<QueuedInputDisposition> {
	return this._queueUserInput(text, images, "steer", options?.source ?? "interactive");
}

private async _queueUserInput(text, images, behavior, source): Promise<QueuedInputDisposition> {
	if (text.startsWith("/")) {
		this._throwIfExtensionCommand(text);              // :2178
	}
	const processedInput = await this._runInputHandlers(
		text, images, source, this.isStreaming ? behavior : undefined);
	if (!processedInput) return "handled";              // :2189
	let expandedText = this._expandSkillCommand(processedInput.text);
	expandedText = expandPromptTemplate(expandedText, [...this.promptTemplates]);
	if (behavior === "steer") {
		await this._queueSteer(expandedText, processedInput.images);
	} else {
		await this._queueFollowUp(expandedText, processedInput.images);
	}
	return "queued";                                    // :2199
}
```

**_throwIfExtensionCommand**（:2268-2279 逐字，错文逐字）：

```ts
private _throwIfExtensionCommand(text: string): void {
	const spaceIndex = text.indexOf(" ");
	const commandName = spaceIndex === -1 ? text.slice(1) : text.slice(1, spaceIndex);
	const command = this._extensionRunner.getCommand(commandName);
	if (command) {
		throw new Error(
			`Extension command "/${commandName}" cannot be queued. Use prompt() or execute the command when not streaming.`,
		);
	}
}
```

**RPC 消费**（rpc-mode.ts:394-424 逐字要点）：prompt 的响应只在 preflight
回调里写（`output(success(id, "prompt", { disposition }))`），未预检成功
的异常写 fail；steer/follow_up `await` 后直接回 `{ disposition }`，
抛错（扩展命令入队）走通用 fail 响应。`source: "rpc"` 传入三者。

**TUI 提交序**（interactive-mode.ts:3362-3381）：压缩中 ⇒ 扩展命令
`session.prompt(text)` 即执行、否则缓冲 steer；流式中 ⇒ 一律
`session.prompt(text, { streamingBehavior: "steer" })`（扩展命令在 prompt
内即执行，普通文本排队）；`isExtensionCommand`（:4771-4779）只做名字查找
（`extensionRunner.getCommand`），不执行。

### 1.2 get_state 三字段（E7-3：B31/B32/B33）

**响应构建**（rpc-mode.ts:448-464 逐字）：

```ts
case "get_state": {
	const state: RpcSessionState = {
		model: session.model,                    // 整个 Model 对象；undefined ⇒ 键省略
		thinkingLevel: session.thinkingLevel,
		isStreaming: session.isStreaming,
		isCompacting: session.isCompacting,
		steeringMode: session.steeringMode,
		followUpMode: session.followUpMode,
		sessionFile: session.sessionFile,
		sessionId: session.sessionId,
		sessionName: session.sessionName,        // undefined ⇒ 键省略
		autoCompactionEnabled: session.autoCompactionEnabled,
		messageCount: session.messages.length,   // 工作副本条数
		pendingMessageCount: session.pendingMessageCount,
	};
	return success(id, "get_state", state);
}
```

**B31 model 线格**：pi 发的是运行时 `Model` 对象整体 JSON 化（`undefined`
字段被 `JSON.stringify` 省略）。Schema 见 model-schema.ts:3-96 与
types.ts:819-857：

```ts
// BaseModel（types.ts:819-831）
{ id, name, api, provider, baseUrl,            // 五必填 string
  input: ("text"|"image")[],                    // 必填
  inputLimits?: { maxRequestBytes?, images?{ resize?{maxWidth?,maxHeight?,maxBytes?,jpegQuality?}, maxPerMessage?, maxPerRequest? } },
  cost: { input, output, cacheRead, cacheWrite, // 四必填 number
          tiers?[{ inputTokensAbove, input, output, cacheRead, cacheWrite }] },
  headers?: Record<string,string> }
// Model（types.ts:834-857）追加
{ type?: "chat",                                // chat 是缺省 ⇒ 恒省略
  reasoning: boolean,
  thinkingLevelMap?: Partial<Record<ModelThinkingLevel, string|null>>,
  promptCache?: Partial<Record<"short"|"long", number>>,
  contextWindow: number, maxTokens: number,
  samplingParams?: Record<string,unknown>,
  samplingParamsByThinkingLevel?: Partial<Record<ModelThinkingLevel, SamplingParams>>,
  compat?: <per-api 稀疏对象> }                 // authored 原文，多为缺席
```

目录生成对未知价的回落是 `0`（pi generate-models 语义，java
`PricingInfo` javadoc 已记同源）。`reasoning` 是目录数据位（非能力推导）。

**B32 sessionName**：`session.sessionName`（agent-session.ts:1657-1660）
→ `sessionManager.getSessionName()`（session-manager.ts:1318-1327 逐字）：

```ts
getSessionName(): string | undefined {
	// Walk entries in reverse to find the latest session_info entry.
	for (let i = this.fileEntries.length - 1; i >= 0; i--) {
		const entry = this.fileEntries[i];
		if (entry.type === "session_info") {
			return entry.name?.trim() || undefined;
		}
	}
	return undefined;                            // 无 session_info ⇒ undefined
}
```

`--name` 启动写 session_info（main.ts:709-715 逐字）：

```ts
if (parsed.name !== undefined) {
	const name = normalizeSessionName(parsed.name);
	if (name === undefined) {
		console.error(chalk.red("Error: --name requires a non-empty value"));
		process.exit(1);
	}
	sessionManager.appendSessionInfo(name);
}
```

`normalizeSessionName`（cli/args.ts:66-69）**仅 trim**、空 ⇒ undefined
（与 /name 的换行消毒+trim 是**两个**规范化器，勿混）。resume 会话同走此段
（无条件臂）。

**B33 messageCount**：`session.messages`（agent-session.ts:1632-1634）
＝ `this.agent.state.messages`——工作副本（含 custom 消息，不含 compaction
等非消息条目；压缩后就地重写 ⇒ 条数与转录必然分叉）。

### 1.3 new_session 响应（B257 候选）

**pi 命令形**（rpc-types.ts:27）：
`{ id?: string; type: "new_session"; parentSession?: string }`；
**响应形**（rpc-types.ts:131 附近）：`data: { cancelled: boolean }`。

**rpc-mode.ts:432-441 逐字**：

```ts
case "new_session": {
	const options = command.parentSession ? { parentSession: command.parentSession } : undefined;
	const result = await runtimeHost.newSession(options);
	if (!result.cancelled) {
		await rebindSession();
	}
	return success(id, "new_session", result);
}
```

**runtimeHost.newSession**（agent-session-runtime.ts:226-258 逐字要点）：
`emitBeforeSwitch("new")` 取消 ⇒ `{cancelled:true}`（不替换）；否则新建
SessionManager（持久性镜像当前）；`parentSession` 在场 ⇒
`sessionManager.newSession({ parentSession })`——写进新会话 **header**
（session-manager.ts:1057-1070：`parentSession: options?.parentSession`，
与 session_start 事件的 `previousSessionFile` 是**两个**字段，前者是客户端
指定值、后者恒为被替换会话）；teardown → createRuntime
（session_start{new, previousSessionFile}）→ finishSessionReplacement →
`{cancelled:false}`。

### 1.4 attribution 头层＋安装遥测门（B253）

**provider-attribution.ts 全件**（:1-97；host 常量 :5-9、判定 :11-34、
两层头 :36-77、merge :79-97）逐字：

```ts
const OPENROUTER_HOST = "openrouter.ai";
const NVIDIA_NIM_HOST = "integrate.api.nvidia.com";
const CLOUDFLARE_API_HOST = "api.cloudflare.com";
const CLOUDFLARE_AI_GATEWAY_HOST = "gateway.ai.cloudflare.com";
const OPENCODE_HOST = "opencode.ai";

function matchesHost(baseUrl: string, expectedHost: string): boolean {
	try { return new URL(baseUrl).hostname === expectedHost; } catch { return false; }
}
function isOpenRouterModel(model) {
	return model.provider === "openrouter" || model.baseUrl.includes(OPENROUTER_HOST);
}
function isNvidiaNimModel(model) {
	return model.provider === "nvidia" || matchesHost(model.baseUrl, NVIDIA_NIM_HOST);
}
function isCloudflareModel(model) {
	return model.provider === "cloudflare-workers-ai" ||
		model.provider === "cloudflare-ai-gateway" ||
		matchesHost(model.baseUrl, CLOUDFLARE_API_HOST) ||
		matchesHost(model.baseUrl, CLOUDFLARE_AI_GATEWAY_HOST);
}

function getDefaultAttributionHeaders(model, settingsManager) {
	if (!isInstallTelemetryEnabled(settingsManager)) return undefined;   // :40 门
	if (isOpenRouterModel(model)) return {
		"HTTP-Referer": "https://pi.dev",
		"X-OpenRouter-Title": "pi",
		"X-OpenRouter-Categories": "cli-agent",
	};
	if (isNvidiaNimModel(model)) return { "X-BILLING-INVOKE-ORIGIN": "Pi" };
	if (isCloudflareModel(model)) return { "User-Agent": "pi-coding-agent" };
	return undefined;
}

function getSessionHeaders(model, sessionId) {                          // :67 不过门
	if (!sessionId) return undefined;
	if (model.provider !== "opencode" && model.provider !== "opencode-go" &&
		!matchesHost(model.baseUrl, OPENCODE_HOST)) return undefined;
	return { "x-opencode-session": sessionId, "x-opencode-client": "pi" };
}

export function mergeProviderAttributionHeaders(model, settingsManager, sessionId, ...headerSources) {
	const merged = {
		...getSessionHeaders(model, sessionId),
		...getDefaultAttributionHeaders(model, settingsManager),
	};
	for (const headers of headerSources) {
		if (headers) Object.assign(merged, headers);       // 请求头**压** attribution
	}
	return Object.keys(merged).length > 0 ? merged : undefined;
}
```

序：session 头 → attribution 头 → **请求头最后（赢）**。空集 ⇒ undefined。

**遥测门**（telemetry.ts:3-13 逐字）：

```ts
function isTruthyEnvFlag(value: string | undefined): boolean {
	if (!value) return false;
	return value === "1" || value.toLowerCase() === "true" || value.toLowerCase() === "yes";
}
export function isInstallTelemetryEnabled(settingsManager, telemetryEnv = process.env.PI_TELEMETRY) {
	return telemetryEnv !== undefined ? isTruthyEnvFlag(telemetryEnv) : settingsManager.getEnableInstallTelemetry();
}
```

settings 默认 `enableInstallTelemetry: true`（settings-defaults.ts:31）。

**接线点**（sdk.ts:339-365 buildRequestOptions 逐字要点）：

```ts
transformHeaders: async (requestHeaders) => {
	const headers = mergeProviderAttributionHeaders(
		requestModel, settingsManager, options.sessionId, requestHeaders);
	return headerRunner?.hasHandlers("before_provider_headers")
		? headerRunner.emitBeforeProviderHeaders(headers ?? {})
		: (headers ?? {});
},
```

**上游调用**（models.ts:865-866 applyAuth）：`headers = mergeHeaders(auth.headers,
options?.headers)` 后恒调 `options.transformHeaders(headers ?? {})`——
attribution 合并**恒执行**（不依赖扩展订阅），扩展钩看到的是合并后全集。
`requestModel.baseUrl` 是**目录值**（auth.baseUrl 覆盖发生在 applyAuth 内、
attribution 用的是 buildRequestOptions 闭包捕获的原 model）。

**reportInstallTelemetry**（interactive-mode.ts:1360-1376）：changelog 翻页时
向 `https://pi.dev/api/report-install?version=...` 发匿名 GET（5s 超时、
`PI_OFFLINE` 短路、过遥测门）——本批裁决⑤不做（§4/§6）。

### 1.5 E7-2/E7-4 核销证据

**E7-2**（extension_ui 双向通道，权重 2）：pi 线格 rpc-types.ts:253-297
（9 method request＋3 形 response）。java 侧批 17（docs/28）已落：
`RpcExtensionUI` 九方法 typed 实现（参数平铺＝pi spread）、
`pendingUiRequests` 按 id 路由（评审回补 I-1＝pi pendingExtensionRequests）、
new_session 重绑（I-2）。行注「`ui()` 无生产者」已过时——生产者是
`RpcExtensionUI`（经 `AgentSession.extensionUI` 注入 RPC 模式）。本批补
线格式对照测试即翻。

**E7-4**（RPC 事件线格式，权重 3）：行注挂的 **B27/B28/B29 已在包④（RPC
重试面，2026-09-19，8c724891）闭环**并从台账移除（258b29b5 重建时按「已决
不列」清出）；同注的 queue_update、压缩/重试事件亦早已上线。
pi 侧契约 json-event.ts 全 61 行：`message_update` 拍平
（`{type, usage, assistantMessageEvent}`）＋`toolcall_start` 剥 `partial`
补 `id`/`toolName`＋其余事件原样透传。java `JsonEventMapper` 三面俱在
（:49-66 剥 partial、:200-220 toolName/id、:66 message_update 拍平、
B116 扁平 usage）。本批补 parity 测试钉死即翻。

---

## §2 java 侧现状（取证 2026-10-24，main `eb9cab6c`）

**disposition 链**：

- `SessionResult.handled()`（SessionResult.java:81-87）已存在，且
  `AgentSession.processPrompt` 的 input 事件臂（AgentSession.java:1342-1351）
  已产 `handled`——**缺的只是扩展命令臂**（pi :1978-1984 对位缺席）。
- `AgentSession.steer`（:1736）/`followUp`（:1445-1451）：无 input 事件、
  无 throwIfExtensionCommand、无 source 参、返回排队 id；`RpcDispatcher`
  :130-141 恒回 `"queued"`（注释「无扩展 input 事件 ⇒ 恒 queued」已过时——
  input 事件批 15 已落地）。
- java 扩展命令与内置**同池**（`CommandRegistry` ConcurrentMap，
  CommandRegistry.java:44-63），无来源标签；执行件 `SlashCommand.execute
  (args, SlashContext)`；TUI 经 `PiTuiApp.submit → InteractiveMode.dispatch`
  全池分发（PiTuiApp.java:547-563），RPC/print 路径**无**任何命令分发——
  `/anything` 直送 LLM。
- `InteractiveMode.submit`（:94-107）：运行中直调 `session.steer`
  （非 pi 的 prompt{steerBehavior} 形）；压缩中缓冲。
- pi 的 `createCommandContext`（runner.ts:990-1024：newSession/fork/
  navigateTree/switchSession/reload/waitForIdle 全件）java 无对应面——
  属 B221①/E5 域，本批不造（裁决②给最低可行为形）。

**get_state**（RpcDispatcher.buildState :472-496）：

- `model`：`"provider/id"` 字符串（B31）；无模型时 `""`（pi 省略键）。
- `sessionName`：`session.sessionName()`——显示名，装配期默认 `"session"`
  （AgentSession.java:422）、resume 回落 `"session"`
  （SessionPersistence.java:413-419）。三存储的 `SessionStorage.getName()`
  均为 **null-when-absent**（sqlite :230-234／jsonl :243／memory :148）——
  pi 语义在存储层已就位，缺的是 RPC 层读 stored 而非 display。
- `messageCount`：`(int) session.entryCount()`——转录条数（B33）。
  工作副本读口已在：`AgentHarness.getMessages()`（AgentHarness.java:234，
  pi `agent.state.messages` 对位，批 13）。
- `--name`：仅置显示名（AgentSession.java:422），从不写 session_info。

**new_session**（RpcDispatcher.java:148-163）：响应发 `buildState()` 全量
state（pi 发 `{cancelled}`）；`RpcCommand.NewSession`（RpcCommand.java:95-97）
只有 `id`，无 `parentSession`；无 before_switch 取消臂（java 有
`SessionBeforeSwitchEvent`＋`extensionFirer.sessionBeforeSwitch`，批 5 落，
仅 `switchTo` 用）。

**attribution**：全仓零命中（无 host 判定、无头注入、无 PI_TELEMETRY 门）。
settings 访问器 `getEnableInstallTelemetry()`（`?? true`）批 11/12 已落
（SettingsInstallAccessors.java:53-57）。头变换缝批 7 已落：
`ProviderEventBridge.applyHeaderTransform`（:172-190）从 `extra["headers"]`
起步过 `before_provider_headers` 钩写回；**无订阅早退**（:173-176）——
attribution 合并不能挂在这个门后。`requestExtra` 已把 `sessionId` 放进
`extra`（DefaultProviders.java:246-258）；`withPerModelOverrides`（:214-222）
把 `modelInfo.headers()` 放进 `extra["headers"]`、`modelInfo.baseUrl()` 投影
为请求 baseUrl。暖路径（`warmStreamFnFor` :296-315）与主路径共用
`streamBlocking`（pi 暖请求同过 transformHeaders，cache-warmer 对位注释
:292-293）。

**ModelInfo 数据位**（批 3a 全落）：`api`（null≙provider 默认）、`baseUrl`
（null≙provider 默认）、`capabilities`（THINKING/IMAGE_INPUT…）、
`PricingInfo`（四价＋tiers，未知 −1）、`ThinkingLevelMap`（三态
`Optional<String>` 值表）、`PromptCacheLifetimes`（short/long 秒）、
`InputLimits`（maxRequestBytes/images.resize/maxPer*）、`headers`、
`samplingParams`/`samplingParamsByThinkingLevel`、`ModelCompat`（**解析后**
全记录，`NONE` 为缺席 ≙ pi undefined；authored 稀疏原文不留存）。
provider 默认 api/baseUrl：`ProviderConfig`（protocol＋baseUrl 字段）、
`Protocol.wireName()` kebab-case。

---

## §3 任务分解

依赖序：T1a（标签）→ T1b/T1c（执行臂）→ T1d/T1e（消费面）→ T1f（images）；
T2/T5/T6 独立；T3/T4 核销随时。

### T1a：CommandRegistry 来源标签（B237 地基，~60 行＋测试）

pi 的扩展命令表与内置命令是**两个**注册面（ExtensionRunner.commands vs TUI
本地件）；java 同池 ⇒ 打标区分（批 3 窄差 D5 的同源问题，本批只解执行面，
get_commands 扩展臂仍随 B221）。

```java
// CommandRegistry.java
public enum CommandSource { BUILTIN, EXTENSION }

public void register(SlashCommand command) { register(command, CommandSource.BUILTIN); }
public void register(SlashCommand command, CommandSource source) { ... }

/** pi isExtensionCommand（interactive-mode.ts:4771-4779）＋getCommand 对位：
 *  解析 "/name args" 的名字段，仅 EXTENSION 标签命中。 */
public SlashCommand extensionCommand(String text) { ... }

/** pi TUI 分发对位：EXTENSION 标签不入 TUI 分发池（走 processPrompt 臂）。 */
public CompletionStage<String> dispatch(String input, SlashContext context) {
    // 现状全池分发 ⇒ 改为跳过 EXTENSION 标签（返回 null 落到 submit 链）
}
```

`DefaultExtensionContext.slashCommands()`（:115-117）改为返回**打标视图**
（register 时钉 EXTENSION）；直接持池的内置注册点不变（默认 BUILTIN）。

### T1b：processPrompt 扩展命令臂（pi :1978-1984 对位，~120 行＋测试）

`AgentSession.processPrompt` 起手（input 事件**之前**，pi 序）：

```java
// pi agent-session.ts:1978-1984 逐字序：expandPromptTemplates 门（java 无此
// SDK 开关，恒真——窄差 D117）＋ startsWith("/") 门。
if (prompt.startsWith("/")) {
    var command = services.slashCommands().extensionCommand(prompt);
    if (command != null) {
        // pi :2118-2120：名字/参数解析（空格切两刀，args 不 trim——逐字）
        String args = ...;
        try {
            // 裁决②：SlashContext 由 mode 注入（TUI 真回调／RPC no-op）
            command.execute(args, slashContextSupplier.get()).toCompletableFuture().join();
        } catch (Throwable e) {
            // pi :2131-2139：出错也是 handled；extension_error 发射，
            // extensionPath 逐字 "command:<name>"、event "command"
            emitExtensionCommandError(command.name(), e);
        }
        return SessionResult.handled();
    }
}
```

- `slashContextSupplier`：`AgentSession` 持 `Supplier<SlashContext>`，默认
  `() -> SlashContext.of(this)`；TUI 装配期注入真回调（批 17 `extensionUI`
  晚绑定同手法）。
- `emitExtensionCommandError`：走批 4 的 extension_error 面（bus.emitError
  对位），载荷 path `command:<name>`、event `"command"`。
- 执行阻塞形：`SlashCommand.execute` 返 CompletionStage，processPrompt 是
  同步契约 ⇒ join（pi 是 await；TUI 选择器类命令在 RPC 路的死锁风险由
  命令自身负责——pi 同形，扩展命令管理自己的 UI）。

### T1c：steer/followUp 全序（pi :2173-2200/:2268-2279 对位，~130 行＋测试）

`AgentSession` 两方法改全序＋disposition 返回（现无调用方用旧返回 id——
全仓取证：InteractiveMode/RpcDispatcher/CompactionInputBuffer 均为语句位）：

```java
/** pi steer（:2211-2217）→ _queueUserInput 全序；source 缺省 "interactive"。 */
public String steer(String text) { return queueUserInput(text, null, StreamingBehavior.STEER, "interactive"); }
public String steer(String text, List<PromptImage> images, String source) {
    return queueUserInput(text, images, StreamingBehavior.STEER, source);
}

private String queueUserInput(String text, List<PromptImage> images,
                              StreamingBehavior behavior, String source) {
    if (text.startsWith("/")) {
        throwIfExtensionCommand(text);   // pi :2268-2279 错文逐字
    }
    String current = text;
    List<PromptImage> currentImages = images;
    if (extensionFirer != null) {
        // pi :2183-2188：streamingBehavior 仅流式中传（isRunning 读 harness）
        var outcome = extensionFirer.input(text, source,
            harness.isRunning(laneName()) ? behavior.wireName() : null);
        if (outcome.handled()) {
            return "handled";            // pi :2189
        }
        current = outcome.text();        // images 不换（D12 延伸，窄差 D120）
    }
    var expanded = expandUserPrompt(current);   // pi :2191-2192 先 skill 后模板
    if (behavior == StreamingBehavior.STEER) {
        harness.steer(laneName(), expanded, currentImages);
    } else {
        harness.followUp(laneName(), expanded, currentImages);
    }
    emitQueueUpdate();                   // pi _queueSteer/_queueFollowUp 内（:2243/:2259）
    return "queued";
}
```

- `throwIfExtensionCommand` 错文逐字：
  `Extension command "/<name>" cannot be queued. Use prompt() or execute the command when not streaming.`
  （`IllegalStateException`——RPC fail 响应与 TUI 错显复用同一消息）。
- 旧 `emitQueueUpdate()` 调用点（:1448-1449 等）随重构内聚。
- `CompactionInputBuffer.Replayer` 的 steer/followUp 臂（InteractiveMode
  :65-72）：缓冲重放不更名——pi 的 flushCompactionQueue 走
  `session.prompt(text, {streamingBehavior})` 还是 steer？取证：pi
  interactive-mode.ts:4805-4806 对扩展命令走 prompt，其余走
  steer/followUp 公开方法（同受 throwIfExtensionCommand 门）。java 重放器
  现状调 session.steer/followUp ⇒ 自动继承全序（input 事件对重放内容
  发射＝pi 同）。**行为变化**登记。

### T1d：RpcDispatcher disposition 透传＋images（~80 行＋测试）

```java
case RpcCommand.Steer s -> {
    // 批 20（B237，pi rpc-mode.ts:416-419）：disposition 透传＋images；
    // 扩展命令入队 ⇒ IllegalStateException → fail 响应（pi 通用错道）
    var disposition = session.steer(s.message(), toPromptImages(s.images()), "rpc");
    out.write(RpcResponse.ok(s.id(), "steer", new RpcPayloads.DispositionData(disposition)));
}
case RpcCommand.FollowUp f -> {
    var disposition = session.followUp(f.message(), toPromptImages(f.images()), "rpc");
    out.write(RpcResponse.ok(f.id(), "follow_up", new RpcPayloads.DispositionData(disposition)));
}
```

- `RpcCommand.FollowUp` 补 `images` 字段（pi rpc-types.ts:24 线格
  `images?: ImageContent[]`；Prompt/Steer 已有但**被丢**——本臂接通）。
- `toPromptImages`：`ContentBlock.ImageContent` → `PromptImage(mimeType, data)`
  （null/空 ⇒ null，缺席 ≙ pi undefined）。
- `handlePrompt`：`prompt.images()` 经 `PromptConfig` 新组件传入
  processPrompt → SessionRunner.drive → `harness.prompt(lane, text, images, …)`
  （重载已在，AgentHarness.java:95-109）。
- `PromptConfig` 加 `images` 组件（null≙缺席）；`PromptConfig.rpc(...)` 工厂
  相应加参。

### T1e：InteractiveMode 提交重路由（pi :3362-3381 对位，~60 行＋测试）

```java
public SessionResult submit(String prompt) {
    if (session.isCompacting()) {
        // pi :3362-3370：压缩中扩展命令即执行，其余缓冲
        if (session.services().slashCommands().extensionCommand(prompt) != null) {
            return session.processPrompt(prompt, PromptConfig.defaults(), streamObserver, entryObserver);
        }
        buffer.add(prompt, StreamingBehavior.STEER);
        ...
    }
    if (session.isRunning()) {
        // pi :3373-3381：流式中一律 prompt{steer}（扩展命令 prompt 内即执行）
        return session.processPrompt(prompt, PromptConfig.withStreamingBehavior(StreamingBehavior.STEER),
            streamObserver, entryObserver);
    }
    return session.processPrompt(prompt, PromptConfig.defaults(), streamObserver, entryObserver);
}
```

- 流式臂从 `session.steer` 改 `processPrompt{STEER}`：input 事件自此对
  TUI steer 发射（pi 同——prompt 流式臂先过 input 事件再排队）；扩展命令
  流式中即执行（pi 同，:1978 注释 "even during streaming"）。
  **行为变化**登记（TUI steer 现在触发扩展 input 钩）。
- `followUp(String)`（InteractiveMode :152-164）的运行中臂同理改
  `processPrompt{FOLLOW_UP}`？——pi Alt+Enter 路（:4481-4496）：扩展命令
  prompt 即执行、否则 `session.prompt(text, {streamingBehavior:"followUp"})`。
  同改。`steer(String)`（:167-169，「注入当前 run」编程口）保留直调
  session.steer（pi 的编程口 steer 公开方法同受全序——对齐后自然一致）。

### T2：get_state 三字段（E7-3，~380 行＋测试）

**T2a B31——`RpcModel` 线记录＋映射**（rpc 包新件）：

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcModel(
    String id, String name, String api, String provider, String baseUrl,
    List<String> input,
    JsonNode inputLimits,     // 见下：直接复用 wire 形 record 或 Jackson 树
    RpcModelCost cost,
    Map<String, String> headers,
    Boolean reasoning,
    Map<String, String> thinkingLevelMap,   // 值 null＝显式不支持（线格保真）
    Map<String, Integer> promptCache,
    Integer contextWindow, Integer maxTokens,
    Map<String, Object> samplingParams,
    Map<String, Object> samplingParamsByThinkingLevel,
    JsonNode compat           // 裁决③
) {}
```

映射 `RpcModel.of(ModelInfo info, ProviderConfig providerConfig)`：

- `api`＝`info.api()` ?? providerDef api ?? `providerConfig.protocol().wireName()`；
  `baseUrl`＝`info.baseUrl()` ?? `providerConfig.baseUrl()`（pi 目录恒有值 ⇒
  java 生效值；`ProviderRegistry` 取 config——若接口无访问器则加一个）。
- `input`：IMAGE_INPUT ∈ capabilities ⇒ `["text","image"]`，否则 `["text"]`。
- `reasoning`：THINKING ∈ capabilities。
- `cost`：四价 **−1 ⇒ 0**（裁决④，pi 目录生成回落语义）；tiers 空 ⇒ 省略，
  非空逐条（inputTokensAbove 保 double）。
- `thinkingLevelMap`：entries 非空 ⇒ 摊平 `{label: value|null}`（键在场＋
  Optional.empty ⇒ JSON null——**此处要穿过 NON_NULL**：逐键 ObjectNode 或
  `Map<String,String>` 含 null 值＋`@JsonSerialize` 定制；键缺席 ⇒ 整键省略）。
- `promptCache`：short/long 非 null 档成键；两档皆无 ⇒ 省略。
- `inputLimits`/`samplingParams`/`samplingParamsByThinkingLevel`：非空 ⇒ 透传。
- `headers`：非空 ⇒ 透传。
- `type`：恒省略（java 全 chat，pi chat 同省略——零差异）。
- `compat`：裁决③——`ModelCompat.NONE` ⇒ 省略；非 NONE ⇒ 发**解析后**全
  记录（窄差 D118：pi 线格是 authored 稀疏对象，形状差仅 models.json 手写
  compat 的模型可见）。
- `ModelInfo.minimal`（目录未命中）：capabilities 空 ⇒ input `["text"]`、
  reasoning false、cost 全 0、contextWindow/maxTokens 0（pi 无此态——
  pi getModel 查不到即抛；java 容忍路径的形状，窄差不另登：B31 行注记）。
- 无模型（harness.getModel()==null）⇒ `model` 键省略（pi undefined 同形；
  现状 `""` 修真）。

buildState 改为：

```java
var modelId = harness.getModel();
RpcModel wireModel = null;
if (modelId != null) {
    var info = ModelsJsonConfig.allModels().find(modelId)      // 查生效目录
        .orElseGet(() -> ModelInfo.minimal(modelId));
    wireModel = RpcModel.of(info, providerConfigOf(modelId.provider()));
}
```

**T2b B32——sessionName 缺席化＋--name 写回**：

- `AgentSession.storedSessionName()` 新访问器：`session == null ? null :
  session.getName()`（三存储已 null-when-absent，§2）；display
  `sessionName()` 不动（TUI/footer 不变）。
- buildState：`session.storedSessionName()`——null ⇒ 键省略（NON_NULL 已在）。
- `--name` 写回（pi main.ts:709-715 对位）：会话装配完成、storage attach 后
  （初始与 resume 同臂），`args.name() != null` ⇒ **trim-only** 规范化
  （args.ts:66-69 对位，非 /name 的换行消毒）→ 空 ⇒ stderr
  `Error: --name requires a non-empty value`＋exit 1；非空 ⇒
  `session.setName(name)`（FactName 落盘）。装配早期不发 SessionInfoChanged
  （pi 此点无监听者——appendSessionInfo 直写）。落点：`SessionPersistence`
  attach 链（持久臂）＋内存臂同处理。
- 显示名初始化链改读 stored：`args.name() ?? stored ?? "session"`——
  --name 落盘后 display 与 stored 同源（resume 无 session_info ⇒ 仍
  "session" 显示、RPC 省略，pi 同形）。

**T2c B33——messageCount 工作副本口径**：

```java
// buildState：session.entryCount() ⇒ session.harness().getMessages().size()
```

pi `agent.state.messages.length` 对位（含 custom 消息；压缩后分叉转录——
正是 B33 要义）。java RPC/web 消费者审计：web `/session` 段与 stats 展示
用的是 entryCount 语义的别处（`get_session_stats` 不动），get_state 的
messageCount 无 java 侧行为消费者（打印面）⇒ 切换安全。**行为变化**登记。

### T3：E7-2 核销（验证＋测试，~80 行测试）

对照矩阵钉死（pi rpc-types.ts:253-297 ↔ java 线格）：

1. 九方法 request 线格逐键（select/confirm/input/editor/notify/setStatus/
   setWidget/setTitle/set_editor_text；参数平铺顶层、timeout 键形、
   setWidget 的 placement 键名 `widgetPlacement`…逐一对拍）。
2. 三 response 形（value/confirmed/cancelled）入线 → 按 id 路由到挂起
   future；未知 id 静默弃（pi 同）。
3. new_session 后通道重绑（批 17 I-2 回归）。
4. 生产者存在性：扩展经 `ctx.ui` 调用 → stdout 出现 `extension_ui_request`
   （批 17 已有覆盖则改为对拍键名级断言）。

任何键名/键形差 ⇒ 即修（小修计入本批）；差面大 ⇒ 回评审。

### T4：E7-4 核销（parity 测试，~100 行测试）

json-event.ts:1-61 逐键对拍：

1. `message_update` 拍平：顶层 `{type, usage, assistantMessageEvent}`；
   非 assistant 的 message_update ⇒ 抛（pi json-event.ts:53-55 同形——java
   侧断言现行行为，差异即修）。
2. `toolcall_start`：剥 `partial`、补 `id`/`toolName`（从
   `partial.content[contentIndex]` 取）；content 非 toolCall ⇒ 抛
   （pi :24-27 错文对拍）。
3. 其余事件原样透传（抽样：message_start/message_end/agent_start/
   queue_update/compaction/retry 族）。
4. B27–B29 回归（包④ 重试面事件线格）——已有套件则点名引用。

### T5：new_session 修形（B257 即登即销，~120 行＋测试）

```java
// RpcCommand.java
@JsonTypeName("new_session")
record NewSession(String id, String parentSession) implements RpcCommand { ... }

// RpcDispatcher.java
case RpcCommand.NewSession n -> {
    // pi rpc-mode.ts:432-441＋agent-session-runtime.ts:226-258：
    // before_switch("new") 取消 ⇒ {cancelled:true} 不替换
    if (session.fireBeforeSwitch("new", null)) {
        out.write(RpcResponse.ok(n.id(), "new_session", new RpcPayloads.CancelledData(true)));
        return;
    }
    var old = session;
    var previousId = old.sessionId();
    unsubscribe();
    session = AgentSession.createReplacing(args, previousId, n.parentSession());
    session.extensionUI(extensionUI, "rpc");
    old.closeForReplacement("new", session.sessionId());
    eventSubscription = session.subscribe(this::emitEvent);
    out.write(RpcResponse.ok(n.id(), "new_session", new RpcPayloads.CancelledData(false)));
}
```

- `RpcPayloads.CancelledData(boolean cancelled)` 新件（pi `{cancelled}` 形）。
- `AgentSession.fireBeforeSwitch(reason, target)`：extensionFirer 包装
  （switchTo :2010-2012 同码上提共用）。
- `createReplacing(args, previousId, parentSession)`：parentSession 落会话
  创建选项 `parentSessionId`（jsonl 层通道已有——
  JsonlSessionCreateOptions.parentSessionId；sqlite/内存臂核实同落；
  null ⇒ 不写，pi `options?.parentSession` 缺席同形）。
  **注意**：session_start 事件载荷 previousSessionFile 恒为被替换会话 id
  （pi 语义），parentSession 只进 header——两字段不可串。
- pi 的 `setup`/`withSession` 回调与 `emitBeforeSwitch` 的 targetSessionFile
  （"new" 无 target ⇒ null）按 pi 形。

### T6：attribution 头层（B253，~260 行＋测试）

**T6a 遥测门**（coding-agent core 新件 `InstallTelemetry`，pi telemetry.ts
:3-13 逐字）：

```java
public final class InstallTelemetry {
    public static boolean isEnabled(SettingsManager settings) {
        String env = System.getenv("PI_TELEMETRY");   // pi 默认参逐调用求值对位
        return env != null ? isTruthy(env) : settings.accessors().getEnableInstallTelemetry();
    }
    private static boolean isTruthy(String v) {
        return !v.isEmpty() && (v.equals("1")
            || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }
}
```

**T6b `ProviderAttribution`**（coding-agent core 新件，pi
provider-attribution.ts:1-97 逐字对位）：

```java
public final class ProviderAttribution {
    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String NVIDIA_NIM_HOST = "integrate.api.nvidia.com";
    private static final String CLOUDFLARE_API_HOST = "api.cloudflare.com";
    private static final String CLOUDFLARE_AI_GATEWAY_HOST = "gateway.ai.cloudflare.com";
    private static final String OPENCODE_HOST = "opencode.ai";

    /** pi mergeProviderAttributionHeaders（:79-97）：session 头 → attribution 头
     *  → existing（赢）；两层皆空 ⇒ 返回 existing 原样（不造键）。 */
    public static Map<String, String> merge(ModelInfo model, String effectiveBaseUrl,
            boolean telemetryEnabled, String sessionId, Map<String, String> existing) { ... }
}
```

- `matchesHost`：`URI.create(baseUrl).getHost()` 等值比较，解析失败 ⇒ false
  （pi `new URL` try/catch 同形）。
- 三判定函数逐字（provider 名或 host 匹配；openrouter 用 `contains`、
  其余用 `matchesHost`——pi 原文不对称，照抄）。
- 头值逐字：`HTTP-Referer: https://pi.dev`、`X-OpenRouter-Title: pi`、
  `X-OpenRouter-Categories: cli-agent`、`X-BILLING-INVOKE-ORIGIN: Pi`、
  `User-Agent: pi-coding-agent`、`x-opencode-session/x-opencode-client: pi`。
- attribution 层过遥测门；**session 层不过门**（pi :67-77）。
- baseUrl 源（裁决⑥）：`modelInfo.baseUrl()` ?? provider 默认
  （ProviderConfig.baseUrl）——pi `model.baseUrl`＝目录值，**不取**
  CLI/settings 覆盖。

**T6c 接线**（DefaultProviders.streamBlocking :354-365 间）：

```java
var effectiveOptions = withPerModelOverrides(apiOptions, modelInfo);
// 批 20（B253，pi sdk.ts:355-361 对位）：attribution 并入 extra["headers"]——
// 恒执行（遥测门内建），不挂 before_provider_headers 订阅门；钩见合并全集。
effectiveOptions = ProviderAttribution.apply(effectiveOptions, modelInfo,
    providerDefaultBaseUrl, settings, sessionId);
if (bridge != null) {
    effectiveOptions = bridge.applyHeaderTransform(effectiveOptions);
    ...
}
```

- `apply`：读 `extra["headers"]`（可缺席）＋`extra["sessionId"]`（可缺席），
  merge 后非空 ⇒ 写回 `extra["headers"]`；两层皆空且无 existing ⇒ 不造键
  （pi undefined 对位）。
- 暖路径共用 streamBlocking ⇒ 自动覆盖（pi 暖请求过 transformHeaders 同）。
- `settings`/`sessionId` 在 streamBlocking 调用点已在 scope（streamFnFor/
  warmStreamFnFor 签名持证）。

---

## §4 不做项与重排

1. **reportInstallTelemetry**（pi.dev/api/report-install 报装）**不做**
   （裁决⑤）：端点是 pi 产品的安装统计，pi-java 是独立制品，向其报装语义
   错误；遥测门函数本身移植（attribution 是其消费点）。
2. **createCommandContext 全件**（newSession/fork/navigateTree/switchSession/
   reload/waitForIdle，runner.ts:990-1024）不造——B221①/E5 域；本批扩展命令
   执行件仍是 java 自有 `SlashCommand`/`SlashContext` 契约（窄差 D119）。
3. **get_commands 扩展臂**（pi rpc-mode.ts:691-697 第一臂）仍随 B221
   （窄差 D5 不动）——本批 T1a 的 EXTENSION 标签为其备好数据源，但不改
   `buildCommands`。
4. **D33 余两层**（auth.headers／显式请求头进变换缝）不动——B253 只收
   attribution 层；闭环改写 D33 注记。
5. **input 事件 images 臂**（pi transform 可换图）不扩——D12 已登，本批
   steer/followUp 继承同一限制（窄差 D120 延伸注记）。
6. **expandPromptTemplates=false SDK 开关**不造（窄差 D117）。

---

## §5 窄差登记（接续 D116；台账格式随闭环入 docs/04）

| # | 差 | 影响 |
|---|---|---|
| D117 | `PromptOptions.expandPromptTemplates` 无对应——java 全模式恒展开（pi SDK 可关，rpc/TUI 恒开） | [内部] |
| D118 | get_state.model 的 `compat` 发**解析后**全记录（非 NONE 时）；pi 线格是 authored 稀疏对象——仅 models.json 手写 compat 的模型形状不同 | [协议面/边缘] |
| D119 | 扩展命令执行件是 java `SlashContext`（RPC/print 模式 onQuit/onSwitchSession no-op），非 pi `ExtensionCommandContext` 全件（B221① 域） | [协议面/边缘] |
| D120 | steer/followUp 的 input 事件 images 臂同 D12——transform 不能换图、事件载荷 images 恒 null | [内部] |
| D121 | `ModelInfo.minimal`（目录未命中）的 get_state.model 形状（零值字段）为 java 容忍路径独有——pi getModel 查不到即抛、请求根本发不出 | [协议面/边缘] |
| D122 | in-memory 会话（`--no-session`/内存仓）无 Session storage：`/name` 只及显示名，get_state 的 sessionName 恒省略；pi 的 inMemory SessionManager 在内存里记 session_info、getSessionName 可读 | [协议面/边缘] |

---

## §6 裁决

1. **扩展命令执行点统一**（T1b+T1e）：processPrompt 内置扩展命令臂＋TUI
   重路由（pi 单执行点形），还是 TUI 分发不动、仅 RPC/print 加臂（双执行点
   长期并存）？**推荐前者**——pi `AgentSession.prompt` 是全模式统一执行点，
   TUI 双执行点会让 input 事件/disposition 语义在两种入口分叉。
2. **SlashContext 来源**：AgentSession 持 `Supplier<SlashContext>`，TUI 注入
   真回调、RPC/print 默认 `SlashContext.of(session)`（onQuit/onSwitchSession
   no-op）。**推荐**（批 17 extensionUI 晚绑定同手法）；备选：processPrompt
   恒 no-op（TUI 扩展命令失去 quit/switch 能力 ⇒ 回归）。
3. **RpcModel.compat**：(a) `NONE`⇒省略、非 NONE 发解析后全记录＋窄差
   D118（**推荐**）；(b) 恒省略 compat（丢 models.json authored 信息）；
   (c) ModelInfo 增留 authored 原文（跨模块改动、数据面双轨）。
4. **cost 未知价 −1 ⇒ wire 0**（pi 目录生成回落语义，`PricingInfo` javadoc
   同源记）。**推荐**；备选：发 −1（pi 线格永不出现 −1，客户端按非负处理
   会炸）。
5. **reportInstallTelemetry 不做**（§4-1）。**推荐不做**；备选：做（向
   pi.dev 报 pi-java 安装——语义错误且外发数据）。
6. **attribution baseUrl 源**：`modelInfo.baseUrl()` ?? provider 默认
   （ProviderConfig），**不取** CLI/settings 覆盖值（pi `model.baseUrl`
   ＝目录值语义）。**推荐**；若 ProviderRegistry 无 config 访问器则加一个
   （ai 模块小改），不做回落到请求生效值的近似。
7. **new_session 修形三件**（{cancelled} 响应＋parentSession＋before_switch
   取消臂）即登即销 B257。**推荐做**（java 无消费者依赖旧 state 载荷——
   取证：仅测试断言 command 名）；备选：只修响应形状留 parentSession。
8. **--name 写 session_info**（pi main.ts:709-715）：持久化＋空名报错
   exit 1 逐字。**推荐做**（B32 完整对位——pi 的 --name 本就落盘）；
   备选：仅 RPC 读 stored（--name 仍显示级，与 pi 持久语义分叉）。

---

## §7 测试计划

| 任务 | 测试 | 要点 |
|---|---|---|
| T1a | CommandRegistrySourceTest | 标签默认 BUILTIN；extensionCommand() 只中 EXTENSION；dispatch 跳过 EXTENSION；名字解析空格两刀逐字 |
| T1b | ProcessPromptExtensionCommandTest | "/ext args" ⇒ handled＋执行件收到 args；未注册 "/x" ⇒ 落 LLM 路（started）；执行抛错 ⇒ handled＋extension_error{command:name, command}；压缩中照执行（臂在门前） |
| T1c | SteerFollowUpDispositionTest | input handled ⇒ "handled" 不入队；transform ⇒ 排队变换文本；"/ext" ⇒ IllegalStateException 错文逐字；"/未注册" ⇒ 正常排队；source/流式位 streamingBehavior 传值；queue_update 发射 |
| T1d | RpcDispositionWireTest | prompt/steer/follow_up 三档 disposition 上线；images 透传到 harness（探针）；扩展命令 steer ⇒ fail 响应逐字错文 |
| T1e | InteractiveSubmitRoutingTest | 压缩中 "/ext" 即执行不缓冲；流式中普通文本走 prompt{STEER}（input 事件发射）；流式中 "/ext" 即执行 |
| T2a | RpcModelWireTest | 全字段映射（api/baseUrl 生效值、input 两臂、reasoning、cost −1→0、tiers、thinkingLevelMap 三态〔显式 null 键保真〕、promptCache、inputLimits、headers、samplingParams×2、compat NONE 省略）；无模型 ⇒ 键省略 |
| T2b | SessionNameWireTest | 未命名 ⇒ get_state 省略 sessionName；/name 后出现；清名后再省略；--name 落盘＋trim-only＋空名 exit 1 错文；resume 无 session_info ⇒ 省略 |
| T2c | MessageCountWireTest | 压缩后 messageCount 与转录条数分叉（钉 pi 口径） |
| T3 | ExtensionUiWireParityTest | §3-T3 矩阵 1–4 |
| T4 | JsonEventParityTest | §3-T4 矩阵 1–4 |
| T5 | RpcNewSessionWireTest | 响应 {cancelled:false} 形；before_switch 取消 ⇒ {cancelled:true}＋旧会话不动；parentSession 落 header（三后端）；session_start 载荷 previousSessionFile 不被 parentSession 串 |
| T6 | ProviderAttributionTest | 四 provider 判定（provider 名＋host 两臂、openrouter contains 形）；遥测门（PI_TELEMETRY 三 truthy/假值/缺席 ⇒ settings 默认 true）；session 头不过门；merge 序（existing 赢）；空集不造键；接线（streamBlocking 探针：无扩展订阅也注入；钩见合并集；暖路径同） |

验证：`mvn test -pl pi-java-coding-agent -am`＋收口全量 `mvn clean verify`
（日志落盘看退出码——批 17 假绿坑；不带 -am 旧构件坑；spotbugs 在 verify
阶段）。

---

## §8 对账表（判定列：前态→后态）

| 台账/行 | 权重 | 前态 | 后态 | 任务 |
|---|---:|---|---|---|
| B237（RPC prompt 缺 handled） | —（＝E7-6） | 开 | **销号** | T1 |
| B253（attribution 头层） | — | 开 | **销号** | T6 |
| B31（model 字符串） | —（E7-3 ⅓） | 开 | **销号** | T2a |
| B32（sessionName 恒有值） | —（E7-3 ⅓） | 开 | **销号** | T2b |
| B33（messageCount 口径） | —（E7-3 ⅓） | 开 | **销号** | T2c |
| B257（new_session 形状＋parentSession＋before_switch） | — | 即登 | **即销** | T5 |
| E7-2 extension_ui 通道 | 2 | 存疑 | **对齐** | T3 |
| E7-3 get_state 字段 | 2 | 存疑 | **对齐** | T2 |
| E7-4 RPC 事件线格式 | 3 | 存疑 | **对齐** | T4 |
| E7-6 prompt disposition | 1 | 存疑 | **对齐** | T1 |
| D33（头集少三层） | 窄差 | 开 | **改注**（余 auth.headers/显式头两层） | T6 |
| D5（get_commands 扩展臂） | 窄差 | 开 | 不动（随 B221） | — |
| D12（input 事件 images） | 窄差 | 开 | 不动（D120 延伸注记） | — |

账面预期：E7 域 8.0→**12.0/12＝100%**（存疑清零）；模块 388.0→**392.0/485
≈ 80.8%**；全仓 1,069.5→**1,073.5/1379 ≈ 77.8%**。

---

## §9 闭环回填

- **闭环日**：2026-10-25。commits：`f3899b56`（T1 全链）＋`580ee601`（T2+T5 RPC 面）＋
  `2e1765b1`（T6+T3/T4）＋`3c472204`（T1d 测试补漏）＋`04122720`（评审回补
  I-1..I-5）＋docs 回填 1 个。
- **实际账面**：map/04 **E7 域 8.0→12.0/12＝100%**（存疑清零，**第六个满分域**）；
  模块 388.0→**392.0/485 ≈ 80.8%**；全仓 1,069.5→**1,073.5/1379 ≈ 77.8%**；
  w3 87.0% 不动。**B237/B253/B31/B32/B33 销号＋B257 即登即销**；D33 改注（余两层）；
  窄差 D117–D122 入台账。
- **verify 史**：#1–#3 测试编译/断言修形（本批新测试三处编译错〔AssertJ `.as` 序/
  包名/Long〕＋存量三处旧形状断言〔RpcCommandTest 构造/`getStateReturnsPayload`
  model 串/cycleModel model 串/SessionTeardownTest 响应载荷〕）＋**#4 全量
  `mvn clean verify` 绿**（exit 0，BUILD SUCCESS 13:47min，5,041 测 0 失败，
  spotbugs 零钉）＋评审回补后 #5 定向 61/61 绿＋**#6 全量 clean verify 收口**
  （exit 0，BUILD SUCCESS，5,046 测 0 失败，spotbugs 零钉）。
- **排障留痕**：① 压缩后 lane transcript 与工作副本**等长**（Compaction 标记↔
  合成摘要 user 消息 1:1 对冲）——B33 分叉构造改用 excludeFromContext
  BashExecution（一次性探针 `CompactionCompositionProbe` 实证后删）；② jsonl
  header 写键 `parentSession`（PiV3Wire pi 键）/读键 `parentSessionId`
  （JsonlCodec v4）两层各自自洽（非 bug）；③ `Map.of` 键序不定撞断言 ⇒
  attribution 头集改 LinkedHashMap 保 pi 字面量序；④ `PiTuiAppDequeueTest`
  前存红时序窗再现一次（批 19 已登记，隔离 4/4 绿，非本批回归）；
  ⑤ `PersistentSessionRepositories` 包私有 ⇒ 持久仓线格夹具留 core 包
  （InitialNameWritebackTest），rpc 侧只钉 in-memory 缺席臂。
- **行数台账**：E1 涨到 2,911（+210）、E2 涨到 780（+74）、E6 涨到 1,049（+13）。
- **评审回补（/code-review eb9cab6c..HEAD，1 Critical＋4 Important 全实＋1 Minor
  挂起，commit 04122720）**：**I-1（Critical）** 扩展命令执行改独立虚拟线程
  （`SessionResult.handledBy`＋commandCompletion 信号；RPC 响应推迟到命令完成后
  写——同步 join 死锁 RPC 读线程/冻结 TUI 渲染线程）；**I-2** `--name` 写回限定
  启动装配链（createReplacing 不重放——首版旗标误带初始装配路，二轮修正）；
  **I-3** processPrompt 的 input 事件 streamingBehavior 仅流式中传（pi :1996
  逐字，批 15 遗留门缺收齐）；**I-4** get_state model 查找改读装配期目录快照
  （逐调用盘读/解析/损坏失败面三消）；**I-5** 压缩缓冲重放扩展命令即执行
  （pi :4805-4818）；**M-1** E1 拆分挂起（行注登记扩展命令簇为自然拆出件）。
  评审 pi 对拍清场项：attribution 四头/合并序、PI_TELEMETRY 门、扩展命令先于
  input 事件序、throwIf 错文、`{cancelled}` 形、stored sessionName、
  messageCount 工作副本——均确认逐字。回补后定向 61/61 绿＋全量 verify 收口。
