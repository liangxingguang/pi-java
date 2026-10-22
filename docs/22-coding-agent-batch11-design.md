# coding-agent 批 11 设计稿：settings 键批量第一批（E2 域：核销×2＋消费链×5＋声明面×14）

【背景摘要】本稿是 coding-agent 对齐批 11 的设计，收 docs/20 §3 批 11 行
「settings 键批量（E2 域 22 缺失＋11 存疑，34.0 加权点）」的**前半**。批 11
按总账建议拆两批：本批＝**核销更正＋coding-agent 模块内可闭环的消费链＋纯声明
面**；TUI 行为键（`externalEditor`/`terminal`/`doubleEscapeAction`/
`treeFilterMode`/`editorPaddingX`/`outputPad`/`autocompleteMaxVisible`/
`markdown`）与包管理器两键（`npmCommand`/`packages`，随批 16/17）、`theme`
（随批 19 JSON 主题）留给「消费点批」。取证发现 map/04 E2 域两行**账面滞后**
（字段与消费点早已落地未翻行），本批一并核销。

术语（首现释义）：

- **E2 域**：`docs/map/04-coding-agent.md` 的 settings.json 键清单（55 行）。
  判定口径：**对齐＝字段存在且本模块内有非零消费者**（getter/setter 不算消费者；
  TUI 模块的消费者不计入本图）；存疑＝字段有、消费未核或为零；缺失＝无字段。
- **声明面**：键的 JSON 字段＋访问器（读写方法）。java 的 `Settings` 有
  `@JsonAnySetter` 未知键兜底，未声明的键也能 JSON 往返不丢——声明面补的是
  **具名字段＋pi 同形访问器**。
- **消费链**：从设置键到实际生效点的完整接线（accessor → 消费点）。
- **B1 / B128**：已闭环工单（agent-core 批 4／ai 批 4），本批核销其 map 行。
- **B15-残留-5**：早期登记的欠账——「pi 的 per-model 思考档设置键 java 没有」，
  写死在 `SessionSetup.thinkingLevelFor` 的 javadoc 里，本批 T2 销号。
- **窄差（D 编号）**：java 与 pi 无法完全对齐、登记在册的差异。本批接续批 10
  的 D49–D51，新登 D52 起。

---

## §0 基线与预期翻动

| 口径 | 批 10 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| 模块（Σ 487，去重） | 288.0（59.1%） | **303.5（62.3%）** | §8 对账表，+15.5 |
| E2 域（Σ权重 86） | 52.0（60.5%） | **67.5（78.5%）** | 同上 |
| 全仓（Σ 1381） | 962.0（69.7%） | **977.5（70.8%）** | 同上 +15.5 |

**裁决②（codemode 出分母）若通过**：三个分母 487→485／86→84／1381→1379，
读数变为 62.6%／80.4%／70.9%（分子不变）。

翻动构成（+15.5）：

- 核销（零代码，字段＋访问器＋消费点已在）：E2-38 `thinkingBudgets` +1.0（w1）、
  E2-11 `branchSummary` +2.0（w2）。
- 消费链（本批接线）：E2-5 `modelThinkingLevels` +2.0（w2）、E2-47 `httpProxy`
  +0.5（w1，存疑→对齐）、E2-48 `httpIdleTimeoutMs` +1.0（w1）、E2-25 `deviceId`
  +1.0（w1）、E2-33 `images.blockImages` +0.5（w1，存疑→对齐）。
- 声明面（缺失→存疑，0→0.5×权重）：E2-1/14/21/22/23/24/44/50/42/52/53/54/55
  共 13 键 w1＝+6.5，E2-49 `cacheWarming` w2＝+1.0；小计 +7.5。

**不做**：E2-20 `npmCommand`/E2-26 `packages`（批 16/17 包管理器）、E2-9 `theme`
（批 19）、E2-15/32/36/37/39/40/41/43（消费点批，消费者在 TUI 域或扩展渲染面）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 本批 21 键的 schema 形状（`core/settings-schema.ts`）

```ts
// :209-217
lastChangelogVersion: Type.Optional(Type.String()),
...
modelThinkingLevels: Type.Optional(
	Type.Record(Type.String(), ModelThinkingLevelSchema, {
		description: 'Per-model default thinking level overrides keyed by "provider/modelId".',
	}),
),
// :239-244
showCacheMissNotices: Type.Optional(
	Type.Boolean({
		description: "Show cache cost and provider recovery notices.",
		default: SETTINGS_DEFAULTS.showCacheMissNotices,
	}),
),
// :274-294
collapseChangelog: Type.Optional(Type.Boolean({ ... default: SETTINGS_DEFAULTS.collapseChangelog })),
enableInstallTelemetry: Type.Optional(Type.Boolean({ ... default: SETTINGS_DEFAULTS.enableInstallTelemetry })),
enableAnalytics: Type.Optional(Type.Boolean({ ... default: SETTINGS_DEFAULTS.enableAnalytics })),
trackingId: Type.Optional(
	Type.String({ description: "Analytics tracking identifier, generated when analytics is enabled." }),
),
// :302-307（packages/extensions 之间）
deviceId: Type.Optional(
	Type.String({
		description: "Stable installation UUID, generated when authentication first needs it. Global only.",
	}),
),
// :376-378
showHardwareCursor: Type.Optional(
	Type.Boolean({ description: "Show the terminal cursor while still positioning it for IME." }),
),
// :380 + :144-147
warnings: Type.Optional(WarningSettingsSchema),
//   const WarningSettingsSchema = Type.Object({
//       anthropicExtraUsage: Type.Optional(Type.Boolean({ default: SETTINGS_DEFAULTS.warnings.anthropicExtraUsage })),
//   });
// :387-394
httpProxy: Type.Optional(
	Type.String({ description: "Proxy URL applied as HTTP_PROXY and HTTPS_PROXY for Pi-managed HTTP clients." }),
),
httpIdleTimeoutMs: Type.Optional(
	timeoutSetting({ description: 'HTTP header or body idle timeout in milliseconds; 0 or "disabled" disables it.' }),
),
//   :10-12  function timeoutSetting(options?) {
//       return Type.Union([Type.Number({ minimum: 0 }), Type.Literal("disabled")], options); }
// :395-406
cacheWarming: Type.Optional(
	Type.Union([Type.Literal("off"), Type.Literal("streaming"), Type.Literal("idle")], {
		description: 'Cache-warming profile. ... Global only because each refresh costs money.',
		default: SETTINGS_DEFAULTS.cacheWarming,
	}),
),
websocketConnectTimeoutMs: Type.Optional(
	timeoutSetting({ description: 'WebSocket connect or open handshake timeout in milliseconds; 0 or "disabled" disables it.' }),
),
// :412-436
fullscreenExitOutput: Type.Optional(
	Type.Union([Type.Literal("transcript"), Type.Literal("resume-hint")], { default: SETTINGS_DEFAULTS.fullscreenExitOutput })),
fullscreenScrollbar: Type.Optional(
	Type.Union([Type.Literal("auto"), Type.Literal("always"), Type.Literal("hidden")], { default: SETTINGS_DEFAULTS.fullscreenScrollbar })),
fullscreenCopyOnSelect: Type.Optional(Type.Boolean({ default: SETTINGS_DEFAULTS.fullscreenCopyOnSelect })),
fullscreenWheelScrollLines: Type.Optional(
	Type.Union([Type.Number(), Type.Literal("auto")], {
		description: "Lines scrolled per wheel event in fullscreen mode; numeric values are clamped from 1 to 100.",
		default: SETTINGS_DEFAULTS.fullscreenWheelScrollLines,
	})),
```

另有 `blockImages`（:113-118，`ImageSettingsSchema` 内，default false）与
`modelThinkingLevels` 的键形 `"provider/modelId"`（:215 description 逐字）。

### 1.2 `SETTINGS_DEFAULTS` 相关行（`core/settings-defaults.ts`）

```ts
showCacheMissNotices: false,          // :27
collapseChangelog: false,             // :31
enableInstallTelemetry: true,         // :32
enableAnalytics: false,               // :33
images: { autoResize: true, blockImages: false },   // :41-43
warnings: { anthropicExtraUsage: true },            // :57-59
cacheWarming: "streaming",            // :65
fullscreenExitOutput: "transcript",   // :68
fullscreenScrollbar: "auto",          // :69
fullscreenCopyOnSelect: true,         // :70
fullscreenWheelScrollLines: "auto",   // :71
```

`lastChangelogVersion`／`trackingId`／`deviceId`／`httpProxy`／`httpIdleTimeoutMs`／
`websocketConnectTimeoutMs`／`showHardwareCursor`／`modelThinkingLevels` **无默认值**
（缺席语义）。

### 1.3 访问器逐字（`core/settings-manager.ts`）

#### 1.3.1 `modelThinkingLevels` 四件（:768-793）

```ts
getModelThinkingLevel(provider: string, modelId: string): ThinkingLevel | undefined {
	return this.settings.modelThinkingLevels?.[`${provider}/${modelId}`];
}

getAllModelThinkingLevels(): Record<string, ThinkingLevel> {
	return { ...(this.settings.modelThinkingLevels ?? {}) };
}

setModelThinkingLevel(provider: string, modelId: string, level: ThinkingLevel): void {
	if (!this.globalSettings.modelThinkingLevels) {
		this.globalSettings.modelThinkingLevels = {};
	}
	this.globalSettings.modelThinkingLevels[`${provider}/${modelId}`] = level;
	this.markModified("modelThinkingLevels");
	this.save();
}

removeModelThinkingLevel(provider: string, modelId: string): void {
	if (!this.globalSettings.modelThinkingLevels) return;
	delete this.globalSettings.modelThinkingLevels[`${provider}/${modelId}`];
	if (Object.keys(this.globalSettings.modelThinkingLevels).length === 0) {
		delete this.globalSettings.modelThinkingLevels;
	}
	this.markModified("modelThinkingLevels");
	this.save();
}
```

#### 1.3.2 超时两键＋解析（`settings-manager.ts:900-912/935-937`，`:147-157`；`http-dispatcher.ts:5/20-36`）

```ts
// http-dispatcher.ts:5
export const DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000;

// http-dispatcher.ts:20-36
export function parseHttpIdleTimeoutMs(value: unknown): number | undefined {
	if (typeof value === "string") {
		const trimmed = value.trim();
		if (trimmed.toLowerCase() === "disabled") {
			return 0;
		}
		if (trimmed.length === 0) {
			return undefined;
		}
		return parseHttpIdleTimeoutMs(Number(trimmed));
	}
	if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
		return undefined;
	}
	return Math.floor(value);
}

// settings-manager.ts:147-157
function parseTimeoutSetting(value: unknown, settingName: string): number | undefined {
	const timeoutMs = parseHttpIdleTimeoutMs(value);
	if (timeoutMs !== undefined) {
		return timeoutMs;
	}
	if (value !== undefined) {
		throw new Error(`Invalid ${settingName} setting: ${String(value)}`);
	}
	return undefined;
}

// settings-manager.ts:900-912
getHttpIdleTimeoutMs(): number {
	return parseTimeoutSetting(this.settings.httpIdleTimeoutMs, "httpIdleTimeoutMs") ?? DEFAULT_HTTP_IDLE_TIMEOUT_MS;
}
setHttpIdleTimeoutMs(timeoutMs: number): void {
	if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
		throw new Error(`Invalid httpIdleTimeoutMs setting: ${String(timeoutMs)}`);
	}
	this.globalSettings.httpIdleTimeoutMs = Math.floor(timeoutMs);
	this.markModified("httpIdleTimeoutMs");
	this.save();
}

// settings-manager.ts:935-937（无 setter）
getWebSocketConnectTimeoutMs(): number | undefined {
	return parseTimeoutSetting(this.settings.websocketConnectTimeoutMs, "websocketConnectTimeoutMs");
}
```

#### 1.3.3 声明面 14 键访问器（逐字摘，行号见表）

| 键 | 访问器逐字要点（settings-manager.ts） |
|---|---|
| `lastChangelogVersion` | :678-685 `get`＝直读；`set`＝写全局＋markModified＋save |
| `showCacheMissNotices` | :942-946 `?? false`；:964-967 set 同上 |
| `collapseChangelog` | :1023-1031 `?? false` |
| `enableInstallTelemetry` | :1033-1041 `?? true` |
| `enableAnalytics` | :1043-1045 `?? false`（**无独立 setEnableAnalytics 外的读取者**） |
| `trackingId` | :1047-1049 直读（可 undefined） |
| `setEnableAnalytics` | :1051-1062：**opt-in 且无 trackingId 时生成 `randomUUID()`**，两键 markModified，save |
| `deviceId` | :1066-1074 `getOrCreateDeviceId()`：**只读写全局域**（注释：项目域忽略，防止 clone 同 ID）；空则生成 UUID＋markModified＋save，幂等 |
| `warnings` | :1419-1428 `get`＝浅拷贝 `{...(this.settings.warnings ?? {})}`；`set`＝浅拷贝写全局 |
| `cacheWarming` | :914-923：**只读全局域**（注释：warming costs money），值须 ∈ `CACHE_WARMING_MODES`（:52 `["off","streaming","idle"]`）否则回落 `"streaming"` |
| `websocketConnectTimeoutMs` | :935-937（§1.3.2；缺席⇒undefined，**无 300s 兜底**） |
| `showHardwareCursor` | :1363-1371：`this.settings.showHardwareCursor ?? process.env.PI_HARDWARE_CURSOR === "1"` |
| `fullscreenExitOutput` | :1251-1260：`=== "resume-hint" ? "resume-hint" : "transcript"`（非法值吞掉回落默认） |
| `fullscreenScrollbar` | :1263-1272：`"always"/"hidden"` 否则回落 `"auto"` |
| `fullscreenCopyOnSelect` | :1274-1282 `?? true` |
| `fullscreenWheelScrollLines` | :1284-1296：number 且 finite ⇒ **clamp 1–100 后 floor**；否则 `"auto"`；setter 同 clamp |

pi 的 setter 全部即时 `save()`。java 惯例（B244 起）：UI 驱动 setter 只
`markModified`，会话关闭统一 `flush`；**id 生成两例**（`getOrCreateDeviceId`、
`setEnableAnalytics` 生成 trackingId）按 `setRetryEnabled` 先例即时 `flush()`
（崩溃前必须落盘，pi 的 save 语义承重）。裁决⑥。

### 1.4 五条消费链（逐字）

#### 1.4.1 `modelThinkingLevels`：三处消费（T2）

```ts
// sdk.ts:236-244（findInitialModel 入参）
const result = await findInitialModel({
	scopedModels: [],
	isContinuing: hasExistingSession,
	defaultProvider: settingsManager.getDefaultProvider(),
	defaultModelId: settingsManager.getDefaultModel(),
	defaultThinkingLevel: settingsManager.getDefaultThinkingLevel(),
	modelThinkingLevels: settingsManager.getAllModelThinkingLevels(),
	modelRuntime,
});

// model-resolver.ts:666-672（findInitialModel scope 臂）
const scopedModel = scopedModels[0];
const perModel = modelThinkingLevels?.[`${scopedModel.model.provider}/${scopedModel.model.id}`];
return {
	model: scopedModel.model,
	thinkingLevel: scopedModel.thinkingLevel ?? perModel ?? defaultThinkingLevel ?? DEFAULT_THINKING_LEVEL,
	...
// model-resolver.ts:677-685（saved 默认臂）同形：perModel ?? defaultThinkingLevel。

// sdk.ts:262-270（会话创建，resume 之外的回落）
if (thinkingLevel === undefined && model) {
	const perModel = settingsManager.getModelThinkingLevel(model.provider, model.id);
	if (perModel) { thinkingLevel = perModel; }
}
if (thinkingLevel === undefined) {
	thinkingLevel = settingsManager.getDefaultThinkingLevel() ?? DEFAULT_THINKING_LEVEL;
}

// agent-session.ts:2669-2681（切模型思考档解析）
private _getThinkingLevelForModelSwitch(targetModel?: Model<any>, explicitLevel?: ThinkingLevel): ThinkingLevel {
	if (explicitLevel !== undefined) { return explicitLevel; }
	// Per-model default takes priority when switching to a model that has one
	if (targetModel) {
		const perModel = this.settingsManager.getModelThinkingLevel(targetModel.provider, targetModel.id);
		if (perModel !== undefined) { return perModel; }
	}
	return this.settingsManager.getDefaultThinkingLevel() ?? this.thinkingLevel ?? DEFAULT_THINKING_LEVEL;
}
// 调用点：:2483（setModel）、:2548（cycleModelForward，explicit=scope 级别）、:2585（cycleModelBackward）。
// pi setModel :2477-2496：thinkingLevel = _getThinkingLevelForModelSwitch(model)
//   → state.model = model → appendModelChange → [persist] → setThinkingLevel(thinkingLevel)。
// 优先级汇总：显式（CLI/scope）> per-model > 全局默认 > 当前档 > DEFAULT("off")。
```

setter 双侧的调用方在 TUI（interactive-mode.ts:5010/5020，设置选择器）——本批
只建访问器，TUI 入口随消费点批。

#### 1.4.2 `httpProxy`（T3）

```ts
// http-dispatcher.ts:45-49（唯一核心件）
export function applyHttpProxySettings(httpProxy: string | undefined): void {
	const proxy = httpProxy?.trim();
	if (!proxy) return;
	process.env.HTTP_PROXY ??= proxy;
	process.env.HTTPS_PROXY ??= proxy;
}
// 调用点：main.ts:596（bootstrapSettingsManager）、main.ts:874（settingsManager）——
// 进程启动期应用一次；env 已存在 ⇒ 设置不覆盖（??=）。
```

#### 1.4.3 `httpIdleTimeoutMs`（T4）

```ts
// sdk.ts:344-354（buildRequestOptions，每个 provider 请求决策点现读）
const providerRetrySettings = settingsManager.getProviderRetrySettings();
const httpIdleTimeoutMs = settingsManager.getHttpIdleTimeoutMs();
const effectiveTimeoutMs = httpIdleTimeoutMs === 0 ? 2147483647 : httpIdleTimeoutMs;
return {
	...options,
	timeoutMs: options.timeoutMs ?? providerRetrySettings.timeoutMs ?? effectiveTimeoutMs,
	websocketConnectTimeoutMs: options.websocketConnectTimeoutMs ?? settingsManager.getWebSocketConnectTimeoutMs(),
	maxRetries: options.maxRetries ?? providerRetrySettings.maxRetries,
	maxRetryDelayMs: options.maxRetryDelayMs ?? providerRetrySettings.maxRetryDelayMs,
	...
```

链序：**per-call 显式 > provider retry timeoutMs > httpIdleTimeoutMs 有效值**
（0⇒2147483647；缺席⇒300_000）。

#### 1.4.4 `deviceId`（T5）

```ts
// settings-manager.ts:1066-1074（逐字见 §1.3.3 表）
// 唯一消费者：interactive-mode.ts:6324-6336（TUI 登录对话框 → modelRuntime.login hints）
{ getDeviceId: () => settingsManager.getOrCreateDeviceId() },
// ai 侧消费：packages/ai/src/auth/oauth/openai-chatgpt.ts:226-230
function agentHostId(deviceId: string | undefined): string {
	... // 非法 ⇒ 每次登录随机 UUID
	return `urn:uuid:${deviceId.toLowerCase()}`;
}
```

#### 1.4.5 `blockImages`（T6）

```ts
// sdk.ts:296-329（convertToLlm 包装，defense-in-depth）
const convertToLlmWithBlockImages = (messages: AgentMessage[]): Message[] => {
	const converted = convertToLlm(messages);
	// Check setting dynamically so mid-session changes take effect
	if (!settingsManager.getBlockImages()) {
		return converted;
	}
	// Filter out ImageContent from all messages, replacing with text placeholder
	return converted.map((msg) => {
		if (msg.role === "user" || msg.role === "toolResult") {
			const content = msg.content;
			if (Array.isArray(content)) {
				const hasImages = content.some((c) => c.type === "image");
				if (hasImages) {
					const filteredContent = content
						.map((c) =>
							c.type === "image" ? { type: "text" as const, text: "Image reading is disabled." } : c,
						)
						.filter(
							(c, i, arr) =>
								// Dedupe consecutive "Image reading is disabled." texts
								!(
									c.type === "text" &&
									c.text === "Image reading is disabled." &&
									i > 0 &&
									arr[i - 1].type === "text" &&
									(arr[i - 1] as { type: "text"; text: string }).text === "Image reading is disabled."
								),
						);
					return { ...msg, content: filteredContent };
				}
			}
		}
		return msg;
	});
};
```

**过滤触及面关键事实**：该包装只替换 **Agent 主循环**的 `convertToLlm` 选项
（agent.ts:116/234 → agent-loop.ts:395，每轮一次）。compaction
（compaction.ts:725/1094）、branch-summarization（:323）、bug-report（:339）
都**直引 messages.ts 的裸 `convertToLlm`，不过滤**。pi 序：transformContext
（扩展钩）在先、convertToLlm（＝本过滤器）殿后。

---

## §2 java 侧现状与缺口（实读）

### 2.1 两行账面滞后（T1 核销，零代码）

- **E2-38 `thinkingBudgets`**：map 记「缺失／无字段」。实读：字段
  `Settings.java:79`（B128 落地）、访问器 `SettingsAccessors.getThinkingBudgets`
  （:271-281）、消费点 `AgentSession.java:610`（`.thinkingBudgets(...)` 进
  harness 配置）。三件俱齐 ⇒ 应判**对齐**。
- **E2-11 `branchSummary`**：map 记「缺失／无字段——台账 B1」。实读：B1 已在
  agent-core 批 4 销号；字段 `Settings.java:39`、访问器
  `getBranchSummaryReserveTokens`（:327-331）／`getBranchSummarySkipPrompt`
  （:334-337）、消费点 `AgentSession.java:1191`（reserve 进分支摘要选项，本模块
  内）＋`TreeNavigationFlow.java:169`（skipPrompt 门，TUI）。本模块内有非零
  消费者 ⇒ 应判**对齐**。

### 2.2 `modelThinkingLevels`：整体缺失

java 无任何对应物（`ModelThinkingLevels` 是 ai 目录钳制工具类，同名不同物）。
既有缺口三处：

1. `SessionSetup.thinkingLevelFor`（:59-72）javadoc 自述：「pi 的 per-model
   设置键 java 没有 ⇒ 登记不做（原 docs/46 §7 B15-残留-5）」。现链：CLI >
   model 模式后缀 > scope 显式 > 全局默认。
2. `AgentSession.cycleModelForward`（:751-761）：scope 显式级别则应用，否则
   **无条件继承当前档**（无 per-model、无全局默认回落）。
3. 切模型两点直调 harness、不过思考档：`RpcDispatcher.java:151`
   （`session.harness().setModel(...)`）、`ModelSelectorScreen.java:50`（同）。
   java 的 `AgentSession` **没有** pi `setModel`（:2477-2496）的对位包装。

### 2.3 `httpProxy`：字段有、通道死

字段 `Settings.java:68`。java 有 `ProxyDetector`（ai/http，读 env/系统属性）
与 `PiHttpClient.Builder.proxy(...)` 通道，但 **`ProxyDetector` 全仓零调用**，
`.proxy(...)` 零喂值。无启动期应用点。pi 语义（改进程 env）java 不可逐字
（JVM 进程 env 运行期不可写）⇒ 需要通道裁决（裁决③）。

### 2.4 `httpIdleTimeoutMs`：缺失＋默认值漂移

无字段。消费点 `DefaultProviders.apiOptions`（:366-368）现为
`providerRetry.timeoutMs ?? 120s`——pi 的兜底是 `httpIdleTimeoutMs` 有效值
（缺席 300s），**120s 是 java 自造值**。`SettingsAccessors.getProviderRetrySettings`
（:252-264）已在，链序第一、二节齐备，缺第三节。

### 2.5 `deviceId`：缺失；登录臂每次随机

无字段。`ChatGptOAuthFlow.LoginHints`（ai/auth，`record LoginHints(
Supplier<String> deviceId, String agentName)`）有 deviceId 通道，但唯一调用方
`AuthCommand.java:143-144` 用 `LoginHints.cli()`（注释自述「pi-ai CLI 每次登录
随机 UUID，不持久化安装 ID」）。pi **coding-agent** 的语义是持久化安装 ID
（§1.4.4）；java 没有 TUI 登录流，CLI 登录是唯一路径 ⇒ 缝在 AuthCommand。
注意：`AuthCommand` 全静态、在 `subcommand` 包，`Settings.global()` 包私有不可达
⇒ 访问器必须挂 `SettingsAccessors`（public）。

### 2.6 `blockImages`：字段有、零消费

字段 `Settings.java:197`（`Image` record 内）。pi 唯一消费是主循环
convertToLlm 包装（§1.4.5）。java 的对位缝：`PiLoopRunner.java:282-283` 的
`config.transformContext()` → `ContextAssembler.transformContext`（:179-183）
→ `HookSystem.fireTransformContext` → **唯一常驻处理器**是
`EngineHookBridge.transformContext`（:73 注册、:111 本体，批 4 落地，coding-agent
层、拿得到 settings）。`ImagePolicies`（autoResize 晚绑定）是工具层策略，与
本过滤不同层，不动。

### 2.7 声明面 14 键：全部无字段

`lastChangelogVersion`／`showCacheMissNotices`／`collapseChangelog`／
`enableInstallTelemetry`／`enableAnalytics`／`trackingId`／`deviceId`（T5 另立）／
`warnings`／`cacheWarming`／`websocketConnectTimeoutMs`／`showHardwareCursor`／
`fullscreenExitOutput`／`fullscreenScrollbar`／`fullscreenCopyOnSelect`／
`fullscreenWheelScrollLines`。现状靠 `unknown` 兜底往返（不丢），缺具名字段与
访问器。各键的 java 消费子系统现状：changelog 流无、analytics 无（pi 自身也是
纯声明，§1.3.3）、cache-warmer 无、websocket 传输无、TUI 硬件光标/全屏面随
消费点批 ⇒ 本批全部只到声明面（判**存疑**）。

### 2.8 文件行数预算

`SettingsAccessors.java` 现 387 行。本批新增访问器约 250 行 ⇒ 必须拆分
（≤500 行规）。拆分方案见裁决⑧。

---

## §3 设计

### T1：核销两行（map 更正，零代码）

map/04 E2-38、E2-11 判定改**对齐**，证据列按 §2.1 实读更新；E2 汇总行与
「本锚点改判」注记追加。台账侧无工单（B1/B128 已销）。

### T2：`modelThinkingLevels`（E2-5，缺失→对齐，w2）

#### 3.1 字段＋四访问器（coding-agent）

```java
// Settings.java（top-level 字段区，defaultThinkingLevel 之后）
/** pi {@code modelThinkingLevels}（settings-schema.ts:213-217）：per-model 默认
 *  思考档覆盖，键形 {@code "provider/modelId"}。 */
public Map<String, String> modelThinkingLevels;
```

深合并：现有 `deepMerge` 对 ObjectNode 递归合并且项目域覆盖全局域——pi 的
`getModelThinkingLevel` 读合并视图（`this.settings`）⇒ 语义自然成立，无特判。

```java
// SettingsAccessors.java（getDefaultThinkingLevel 之后；pi :768-793 对位）
/** pi {@code getModelThinkingLevel}（:768-770）：合并视图查 {@code provider/modelId}。 */
public String getModelThinkingLevel(String provider, String modelId) {
    var levels = manager.effective().modelThinkingLevels;
    return levels == null ? null : levels.get(provider + "/" + modelId);
}

/** pi {@code getAllModelThinkingLevels}（:772-774）：浅拷贝，缺席 ⇒ 空表。 */
public Map<String, String> getAllModelThinkingLevels() {
    var levels = manager.effective().modelThinkingLevels;
    return levels == null ? Map.of() : Map.copyOf(levels);
}

/** pi {@code setModelThinkingLevel}（:776-783）：写全局域。java 惯例 markModified
 *  （TUI 调用方统一 flush；pi 的 save 对位在调用方，同 B244）。 */
public void setModelThinkingLevel(String provider, String modelId, String level) {
    var global = manager.global();
    if (global.modelThinkingLevels == null) {
        global.modelThinkingLevels = new java.util.LinkedHashMap<>();
    }
    global.modelThinkingLevels.put(provider + "/" + modelId, level);
    manager.markModified("modelThinkingLevels");
}

/** pi {@code removeModelThinkingLevel}（:785-793）：删空后整键抹除（JSON 不出空表）。 */
public void removeModelThinkingLevel(String provider, String modelId) {
    var global = manager.global();
    if (global.modelThinkingLevels == null) {
        return;
    }
    global.modelThinkingLevels.remove(provider + "/" + modelId);
    if (global.modelThinkingLevels.isEmpty()) {
        global.modelThinkingLevels = null;
    }
    manager.markModified("modelThinkingLevels");
}
```

#### 3.2 消费点①：会话创建（`SessionSetup.thinkingLevelFor`）

签名加 `String perModelLevel`（调用方已解析初始模型后查表传入，保持
SessionSetup 无 settings 依赖的纯函数形）：

```java
// pi 优先级（model-resolver.ts:670 + sdk.ts:262-270）：
//   CLI > model 模式后缀 > scope 显式 > per-model > 全局默认(> DEFAULT)。
static ModelThinkingLevel thinkingLevelFor(Args args, String defaultThinkingLevel,
                                            String scopedLevel, String perModelLevel) {
    if (args.thinking() != null) {
        return ThinkingLevels.parse(args.thinking());
    }
    var fromModel = ThinkingLevels.parseFromModelPattern(args.model());
    if (fromModel != null) {
        return fromModel;
    }
    if (scopedLevel != null) {
        return ThinkingLevels.parse(scopedLevel);
    }
    if (perModelLevel != null) {                    // 新增臂（B15-残留-5 销号）
        return ThinkingLevels.parse(perModelLevel);
    }
    return ThinkingLevels.parse(defaultThinkingLevel);
}
```

`AgentSession.create` 调用点（:580-582）：初始模型 `model`（:442-446 已解析）
查 `accessors().getModelThinkingLevel(model.provider(), model.modelName())` 传入；
javadoc 的「登记不做」自述删除。

#### 3.3 消费点②：`AgentSession.setModel` 包装＋两点改道

```java
// AgentSession（pi setModel :2477-2496 的思考档面 + _getThinkingLevelForModelSwitch
// :2669-2681 对位；java 无 persist/auth 面——既有登记，docs/16 裁决①）。
/**
 * pi {@code setModel}（agent-session.ts:2477-2496）：换模型并解析新思考档——
 * per-model 覆盖 > 全局默认 > 继承当前（pi 的 {@code ?? this.thinkingLevel ?? DEFAULT}
 * 两臂：java .harness 恒有当前档，解析不出就不动，语义等价）。
 */
public void setModel(ModelId<?> model) {
    var level = thinkingLevelForModelSwitch(model, null);
    harness.setModel(model);
    if (level != null) {
        setThinkingLevel(ThinkingLevels.parse(level));
    }
}

/** pi {@code _getThinkingLevelForModelSwitch}（:2669-2681）逐字三臂。 */
String thinkingLevelForModelSwitch(ModelId<?> target, String explicitLevel) {
    if (explicitLevel != null) {
        return explicitLevel;
    }
    if (target != null) {
        var perModel = services.settings().accessors()
            .getModelThinkingLevel(target.provider(), target.modelName());
        if (perModel != null) {
            return perModel;
        }
    }
    return services.settings().accessors().getDefaultThinkingLevel();
}
```

- `cycleModelForward`（:751-761）改走：
  ```java
  harness.setModel(next);
  var level = thinkingLevelForModelSwitch(next,
      ScopedModelCycler.levelFor(next, scopedModels).orElse(null));
  if (level != null) {
      setThinkingLevel(ThinkingLevels.parse(level));
  }
  ```
  （pi :2548：`explicitLevel`=scope 显式；解析不出 ⇒ 不动＝继承当前，pi 同效。）
- `RpcDispatcher.java:151`：`session.harness().setModel(...)` ⇒ `session.setModel(...)`。
- `ModelSelectorScreen.java:50`：同改道。

#### 3.4 不动项

pi `setModel` 的 persist（写默认模型）与 auth 预检面 java 本无（既有登记）；
setter 的 TUI 入口（设置选择器 per-model 行）随消费点批。

### T3：`httpProxy` 消费链（E2-47，存疑→对齐，w1）

#### 3.5 `HttpProxySupport`（coding-agent core，新文件 ~60 行）

pi 改进程 env（`??=`，env 优先）；JVM 运行期不可写 env ⇒ **通道换系统属性**
（裁决③）。jdk `HttpClient` 未显式 `.proxy()` 时走
`ProxySelector.getDefault()`，其默认实现读 `http(s).proxyHost/Port` 系统属性——
`PiHttpClient`（:43-51）、四个 OAuth flow、`RemoteCatalogProvider` 等全部构造点
都未显式设代理 ⇒ 一处设置、全部生效，与 pi「全局 dispatcher」的触及面等价。

```java
package com.pijava.coding.agent.core;

/**
 * pi {@code applyHttpProxySettings}（http-dispatcher.ts:45-49）对位：把
 * {@code settings.httpProxy} 应用为全进程代理。pi 写进程 env（HTTP_PROXY/HTTPS_PROXY
 * 的 {@code ??=}——env 已有值则不覆盖）；JVM 不可写进程 env ⇒ 写系统属性
 * {@code http.proxyHost/http.proxyPort/https.proxyHost/https.proxyPort}（同 {@code ??=}
 * 语义：env 或属性已有值则不覆盖）。
 */
public final class HttpProxySupport {

    private HttpProxySupport() {}

    /** Apply the configured proxy process-wide; no-op when blank or already configured. */
    public static void apply(String httpProxy) {
        var proxy = httpProxy == null ? null : httpProxy.trim();
        if (proxy == null || proxy.isEmpty()) {
            return;
        }
        if (System.getenv("HTTP_PROXY") != null || System.getenv("HTTPS_PROXY") != null) {
            return; // pi ??= 语义：env 优先
        }
        var uri = java.net.URI.create(proxy);
        var host = uri.getHost();
        if (host == null) {
            return; // 非法 URL：静默跳过（pi 对非法值也不报错——env 照写但 undici 才解析）
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        setIfAbsent("http.proxyHost", host);
        setIfAbsent("http.proxyPort", Integer.toString(port));
        setIfAbsent("https.proxyHost", host);
        setIfAbsent("https.proxyPort", Integer.toString(port));
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
```

#### 3.6 应用点：`SettingsManager.load`

`SettingsManager.load(Path, Path, Boolean)`（:49-59，全部 `load` 重载的唯一漏斗）
在 `reload()` 后加一行：

```java
manager.reload();
HttpProxySupport.apply(manager.global().httpProxy);   // pi main.ts:596/874 启动期应用对位
manager.setProjectTrusted(...);
```

读**全局域**（pi main.ts 用 `getGlobalSettings().httpProxy`——`applyHttpProxySettings`
的两处调用都传 global）。`withStorage`（测试道）**不应用**（不改 JVM 全局态）。

### T4：`httpIdleTimeoutMs`（E2-48，缺失→对齐，w1）

#### 3.7 字段＋解析＋访问器

```java
// Settings.java
/** pi {@code httpIdleTimeoutMs}（settings-schema.ts:390-394）：number | "disabled"
 *  联合 ⇒ 边界原样保类型（同 {@link Terminal#hyperlinks} 先例）。 */
public Object httpIdleTimeoutMs;
```

```java
// 新文件 TimeoutSettings.java（coding-agent core；pi http-dispatcher.ts:5/20-36
//   ＋ settings-manager.ts:147-157 对位，~60 行）
public final class TimeoutSettings {

    private TimeoutSettings() {}

    /** pi {@code DEFAULT_HTTP_IDLE_TIMEOUT_MS}（http-dispatcher.ts:5）。 */
    public static final long DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000;

    /** pi {@code parseHttpIdleTimeoutMs}（:20-36）："disabled"⇒0；空串/非法/负 ⇒ 空。 */
    public static java.util.OptionalLong parseHttpIdleTimeoutMs(Object value) {
        if (value instanceof String s) {
            var trimmed = s.trim();
            if (trimmed.equalsIgnoreCase("disabled")) {
                return java.util.OptionalLong.of(0);
            }
            if (trimmed.isEmpty()) {
                return java.util.OptionalLong.empty();
            }
            try {
                return parseHttpIdleTimeoutMs(Double.parseDouble(trimmed));
            } catch (NumberFormatException e) {
                return java.util.OptionalLong.empty();
            }
        }
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < 0) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of((long) Math.floor(n.doubleValue()));
    }

    /** pi {@code parseTimeoutSetting}（settings-manager.ts:147-157）：非法值抛逐字。 */
    public static java.util.OptionalLong parseTimeoutSetting(Object value, String settingName) {
        var parsed = parseHttpIdleTimeoutMs(value);
        if (parsed.isPresent()) {
            return parsed;
        }
        if (value != null) {
            throw new IllegalArgumentException(
                "Invalid " + settingName + " setting: " + value);
        }
        return java.util.OptionalLong.empty();
    }
}
```

```java
// SettingsNetworkAccessors（新文件，裁决⑧；pi :900-912/:935-937 对位）
/** pi {@code getHttpIdleTimeoutMs}（:900-902）。 */
public long getHttpIdleTimeoutMs() {
    return TimeoutSettings.parseTimeoutSetting(
        manager.effective().httpIdleTimeoutMs, "httpIdleTimeoutMs")
        .orElse(TimeoutSettings.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
}

/** pi {@code setHttpIdleTimeoutMs}（:904-912）：非法抛逐字；java 惯例 markModified。 */
public void setHttpIdleTimeoutMs(long timeoutMs) {
    if (timeoutMs < 0) {
        throw new IllegalArgumentException(
            "Invalid httpIdleTimeoutMs setting: " + timeoutMs);
    }
    manager.global().httpIdleTimeoutMs = timeoutMs;
    manager.markModified("httpIdleTimeoutMs");
}

/** pi {@code getWebSocketConnectTimeoutMs}（:935-937）：缺席 ⇒ 空（无默认）。 */
public java.util.OptionalLong getWebSocketConnectTimeoutMs() {
    return TimeoutSettings.parseTimeoutSetting(
        manager.effective().websocketConnectTimeoutMs, "websocketConnectTimeoutMs");
}
```

#### 3.8 消费点：`DefaultProviders.apiOptions`（:366-368 改写）

```java
// pi sdk.ts:344-354 链序：provider retry timeoutMs ?? httpIdleTimeoutMs 有效值
// （0 ⇒ 2147483647；缺席 ⇒ 300_000）。java 原 120s 自造兜底删除。
var timeout = providerRetry != null && providerRetry.timeoutMs() != null
    ? java.time.Duration.ofMillis(providerRetry.timeoutMs())
    : effectiveHttpIdleTimeout(settings);
```

```java
/** pi sdk.ts:345-346：0/"disabled" ⇒ 2147483647；缺席 ⇒ 300s（非法值随访问器抛）。 */
private static java.time.Duration effectiveHttpIdleTimeout(Settings settings) {
    long ms = TimeoutSettings.parseTimeoutSetting(
        settings == null ? null : settings.httpIdleTimeoutMs, "httpIdleTimeoutMs")
        .orElse(TimeoutSettings.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
    return java.time.Duration.ofMillis(ms == 0 ? Integer.MAX_VALUE : ms);
}
```

### T5：`deviceId`（E2-25，缺失→对齐，w1）

#### 3.9 字段＋访问器（`SettingsInstallAccessors`，裁决⑧）

```java
// Settings.java
/** pi {@code deviceId}（settings-schema.ts:302-307）：稳定安装 UUID，首次认证需要
 *  时生成；仅全局域。 */
public String deviceId;
```

```java
/**
 * pi {@code getOrCreateDeviceId}（settings-manager.ts:1066-1074）：只读写全局域
 * （pi 注释：项目域忽略，防 clone 同 ID）；空则生成 UUID 落盘（即时 flush——
 * 崩溃前必须持久，pi 的 save 语义承重，裁决⑥），幂等。
 */
public String getOrCreateDeviceId() {
    var global = manager.global();
    if (global.deviceId == null || global.deviceId.isEmpty()) {
        global.deviceId = java.util.UUID.randomUUID().toString();
        manager.markModified("deviceId");
        manager.flush();
    }
    return global.deviceId;
}
```

#### 3.10 消费点：`AuthCommand` ChatGPT 臂（:143-144 改写）

java 无 TUI 登录流，CLI 登录是唯一路径（裁决⑤）。`LoginHints.cli()` 是
「每次随机」工厂；改用设置持久 ID：

```java
case OAuthProvider.ChatGpt ignored -> new ChatGptOAuthFlow()
    .login(CONSOLE, chatGptHints());
```

```java
/**
 * pi interactive-mode.ts:6331 对位：deviceId 来自设置的持久安装 ID
 * （{@code getOrCreateDeviceId}）。信任覆盖 {@code TRUE} 加载——登录命令不执行
 * 项目代码，且避免「defaultProjectTrust=never ⇒ 项目域被清空」的加载副作用。
 */
private static ChatGptOAuthFlow.LoginHints chatGptHints() {
    var settings = com.pijava.coding.agent.core.SettingsManager.load(null, null, Boolean.TRUE);
    return new ChatGptOAuthFlow.LoginHints(
        () -> settings.install().getOrCreateDeviceId(), "Pi");
}
```

（`LoginHints` 已有 `record(Supplier<String> deviceId, String agentName)` 形状，
无需改 ai 侧；agentName 沿用 `cli()` 的 `"Pi"`。）

### T6：`blockImages` 消费链（E2-33，存疑→对齐，w1）

#### 3.11 `ImageBlockFilter`（coding-agent core，新文件 ~70 行）

pi sdk.ts:296-329 逐字。java 的消息在 `Message`  sealed 层（user/toolResult
两变体），内容块 `ContentBlock.ImageContent`/`UrlImageContent`——pi 只匹
`c.type === "image"`（base64 形），**URL 形不过滤**（pi 的 AgentMessage 内容无
URL 图片变体；java 的 UrlImageContent 是 JSONL 解码方言，主循环不产）⇒ 同样只
滤 `ImageContent`，注释钉死。

```java
package com.pijava.coding.agent.core;

/**
 * pi sdk.ts:296-329 的 blockImages 过滤（defense-in-depth）逐字对位：user 与
 * toolResult 两角色的 {@code ImageContent} 换 "Image reading is disabled." 文本块，
 * 连续占位文本去重；assistant 等其余角色不动；URL 图片不过滤（pi 的转换层无此变体）。
 */
public final class ImageBlockFilter {

    /** pi 占位文案逐字（sdk.ts:310）。 */
    public static final String PLACEHOLDER = "Image reading is disabled.";

    private ImageBlockFilter() {}

    /** Filter one message list (returns the input untouched when nothing blocks). */
    public static java.util.List<com.pijava.ai.model.Message> apply(
            java.util.List<com.pijava.ai.model.Message> messages) {
        // user/toolResult 两变体逐 pi 两臂：hasImages 预检 → map 替换 → 连续去重。
        // （实现按 §1.4.5 逐字翻译；消息不可变 ⇒ 命中时重建该条。）
        ...
    }
}
```

#### 3.12 缝：`EngineHookBridge.transformContext` 尾部

```java
// EngineHookBridge.java:111 本体尾部追加（桥注册于 :73，常驻链上——无扩展也走）：
List<Message> transformContext(List<Message> messages) {
    var out = ...既有扩展链...;
    // pi 序：transformContext（扩展）在先、convertToLlm 包装（本过滤）殿后；
    // 动态现读（pi 注释：mid-session changes take effect）。
    return blockImages.getAsBoolean() ? ImageBlockFilter.apply(out) : out;
}
```

`EngineHookBridge` 构造加 `BooleanSupplier blockImages` 参，`AgentSession` 装配点
（wireSession 五组闭包串线处，批 6 形状）喂
`() -> services.settings().accessors().getBlockImages()`。

```java
// SettingsAccessors（pi :1311-1313 对位）
/** pi {@code getBlockImages}：{@code images?.blockImages ?? false}。 */
public boolean getBlockImages() {
    var images = manager.effective().images;
    return images != null && Boolean.TRUE.equals(images.blockImages());
}
```

**不滤面**（pi 同，§1.4.5）：压缩、分支摘要、bug 报告直引裸转换 ⇒ java 的
`ContextEntries` 投影各消费点（CompactionService、RecoveryOmissions 等）**不加**
过滤。这是对齐事实，不作窄差。

### T7：声明面 14 键（缺失→存疑）

#### 3.13 `Settings.java` 字段块（top-level 字段区追加）

```java
// ── 批 11 声明面（pi settings-schema.ts 行号逐键钉注）──────────────
/** pi :209。changelog 流 java 暂无（声明面）。 */
public String lastChangelogVersion;
/** pi :239-244。 */
public Boolean showCacheMissNotices;
/** pi :274-279。 */
public Boolean collapseChangelog;
/** pi :280-285。 */
public Boolean enableInstallTelemetry;
/** pi :286-291。 */
public Boolean enableAnalytics;
/** pi :292-294。 */
public String trackingId;
/** pi :380（WarningSettingsSchema :144-147）。 */
public Warnings warnings;
/** pi :395-401（off|streaming|idle；仅全局域生效——accessor 只读全局）。 */
public String cacheWarming;
/** pi :402-406（number | "disabled" 联合，同 httpIdleTimeoutMs 边界处理）。 */
public Object websocketConnectTimeoutMs;
/** pi :376-378。 */
public Boolean showHardwareCursor;
/** pi :412-417（transcript|resume-hint）。 */
public String fullscreenExitOutput;
/** pi :418-423（auto|always|hidden）。 */
public String fullscreenScrollbar;
/** pi :424-429。 */
public Boolean fullscreenCopyOnSelect;
/** pi :430-436（number | "auto" 联合）。 */
public Object fullscreenWheelScrollLines;

/** pi {@code WarningSettingsSchema}（:144-147）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Warnings(
    @JsonProperty("anthropicExtraUsage") Boolean anthropicExtraUsage
) {}
```

（`deviceId`/`httpIdleTimeoutMs`/`modelThinkingLevels` 字段见 T5/T4/T2。）

#### 3.14 访问器分布（裁决⑧的拆分）

| 访问器（pi 行） | 落点 | 要点 |
|---|---|---|
| `getLastChangelogVersion`/`setLastChangelogVersion`（:678-685） | Install | 直读；set＝写全局＋markModified |
| `getCollapseChangelog`/`set`（:1023-1031） | Install | `?? false` |
| `getEnableInstallTelemetry`/`set`（:1033-1041） | Install | `?? true` |
| `getEnableAnalytics`（:1043-1045） | Install | `?? false` |
| `getTrackingId`（:1047-1049） | Install | 直读可 null |
| `setEnableAnalytics`（:1051-1062） | Install | opt-in 且无 trackingId ⇒ 生成 UUID，两键 markModified＋**即时 flush**（裁决⑥） |
| `getOrCreateDeviceId`（:1066-1074） | Install | T5（§3.9） |
| `getShowCacheMissNotices`/`set`（:942-946/:964-967） | UiPrefs | `?? false` |
| `getWarnings`/`setWarnings`（:1419-1428） | UiPrefs | 浅拷贝语义 ⇒ java 返回/接收 record 值（不可变即拷贝语义） |
| `getShowHardwareCursor`/`set`（:1363-1371） | UiPrefs | `?? "1".equals(System.getenv("PI_HARDWARE_CURSOR"))` |
| `getFullscreenExitOutput`/`set`（:1251-1260） | UiPrefs | 非 "resume-hint" ⇒ "transcript" |
| `getFullscreenScrollbar`/`set`（:1263-1272） | UiPrefs | "always"/"hidden" 否则 "auto" |
| `getFullscreenCopyOnSelect`/`set`（:1274-1282） | UiPrefs | `?? true` |
| `getFullscreenWheelScrollLines`/`set`（:1284-1296） | UiPrefs | number⇒clamp 1–100 floor；否则 "auto"；返回类型 `Object`（String "auto" 或 Integer） |
| `getHttpIdleTimeoutMs`/`set`＋`getWebSocketConnectTimeoutMs` | Network | T4（§3.7） |
| `getCacheWarmingMode`/`set`（:914-923） | Network | **只读全局域**；值 ∉ {off,streaming,idle} ⇒ "streaming" |

setter 统一 java 惯例：写全局域＋`markModified`（flush 在会话关闭；TUI 消费点批
的 `SettingsScreen` 统一 flush 对位 pi 的 save——B244 先例）。例外两个 id 生成点
即时 flush（裁决⑥）。

---

## §4 裁决条目

> 【场景】：E2-38/E2-11 map 记「缺失／无字段」，实读三件俱齐（§2.1）。
> 【决定】：零代码核销，map 判定直改「对齐」，证据列写实读行号。
> 【风险/代价】：无影响。[内部，模型不可见]

> 【场景】裁决②：E2-45 `codemode`（w2）键在 pi 存在，但 java 无 codemode
> 子系统（B161，R5 面外——不进 `bin pi` 主流发布版的不做对齐、不进分母）。
> 【决定】：E2-45 移出分母（E2 域 55→54 行，Σ权重 86→84；模块 Σ 487→485；
> 全仓 Σ 1381→1379），证据列注「R5 面外出分母（B161）」。
> 【风险/代价】：无影响；若未来 codemode 立项再入列。[内部，模型不可见]

> 【场景】裁决③：pi 的 `applyHttpProxySettings` 写进程 env（`??=`），JVM 运行期
> 不可写 env。
> 【决定】：通道换 JVM 系统属性（`http(s).proxyHost/Port`），jdk 默认
> `ProxySelector` 读取即全 `HttpClient` 生效，触及面与 pi 的全局 dispatcher 等价；
> env 或属性已有值则不覆盖（保 `??=` 优先级）。应用点挂 `SettingsManager.load`
> 唯一漏斗（读全局域，pi main.ts:596/874 启动期语义）；测试道 `withStorage` 不应用。
> 【风险/代价】：边缘场景——URL 内嵌凭证、`nonProxyHosts`、socks scheme 不解析
> （jdk 默认选择器能力面），登记 D52。[内部，模型不可见]

> 【场景】裁决④：`blockImages` 过滤的挂点——java 的转换层（`ContextEntries` 投影）
> 是静态方法且被压缩/恢复多处共用，pi 的包装只罩主循环。
> 【决定】：挂在 `EngineHookBridge.transformContext` 尾部（扩展链之后、请求装配
> 之前，pi 的 transformContext→convertToLlm 序对位）；桥常驻注册，无扩展也生效；
> 动态现读设置（pi 注释逐字语义）。压缩/分支摘要/bug 报告不加（pi 同）。
> 【风险/代价】：无影响；过滤逻辑独立静态类可单测。[内部，模型不可见]

> 【场景】裁决⑤：pi 的 `deviceId` 消费在 TUI 登录对话框，java 无 TUI 登录流。
> 【决定】：消费点落 `AuthCommand`（java 唯一登录路径）；加载用信任覆盖
> `Boolean.TRUE` 避免项目域清空副作用。
> 【风险/代价】：边缘场景——`pi-java auth oauth-login` 首次运行即写全局
> settings.json（生成 deviceId），与 pi 首次登录生成并持久语义一致。
> [内部，模型不可见]

> 【场景】裁决⑥：pi 的全部 setter 即时 `save()`；java 惯例（B244 起）是
> markModified＋关闭统一 flush。
> 【决定】：值 setter 维持 java 惯例；**id 生成两例**
> （`getOrCreateDeviceId`、`setEnableAnalytics` 生成 trackingId）即时 `flush()`，
> 对齐 `setRetryEnabled` 先例——崩溃前必须落盘，pi 的 save 语义在此处承重。
> 【风险/代价】：无影响。[内部，模型不可见]

> 【场景】裁决⑦：`thinkingLevelForModelSwitch` 在三臂皆空（无 per-model、无全局
> 默认）时，pi 显式置 `this.thinkingLevel ?? DEFAULT`。
> 【决定】：java 解析不出就不调 `setThinkingLevel`（继承当前档）。java harness
> 恒有当前档 ⇒ 与 pi 的 `?? this.thinkingLevel` 臂语义等价；`?? DEFAULT` 臂仅在
> pi 当前档为 undefined 时触发，java 无此态。
> 【风险/代价】：边缘场景——pi 在「当前档 undefined＋无配置」时会写一条
> thinking-level 变更 entry，java 不写；会话文件差异，登记 D54。[内部，模型不可见]

> 【场景】裁决⑧：`SettingsAccessors.java` 本批后超 500 行规。
> 【决定】：按内聚拆三个新文件，均 `SettingsManager` 包内构造、public 入口：
> `SettingsNetworkAccessors`（httpIdleTimeoutMs/websocketConnectTimeoutMs/
> cacheWarming）、`SettingsInstallAccessors`（lastChangelogVersion/collapseChangelog/
> enableInstallTelemetry/enableAnalytics/trackingId/deviceId）、
> `SettingsUiPrefsAccessors`（showCacheMissNotices/warnings/showHardwareCursor/
> fullscreen×4）；`SettingsManager` 加 `network()`/`install()`/`uiPrefs()` 三入口
> （同 `accessors()` 形状）。`modelThinkingLevels` 四件与 `getBlockImages` 留
> `SettingsAccessors`（model 域邻 `getDefaultThinkingLevel`；387+~90<500）。
> 【风险/代价】：无影响；入口增多是既有拆分先例（`accessors()` 本身即
> SettingsManager 的拆分）。[内部，模型不可见]

---

## §5 窄差表（本批新增，接续批 10 的 D49–D51）

| # | 窄差 | 说明 | 触及面 |
|---|---|---|---|
| D52 | httpProxy 通道＝系统属性 | pi 写进程 env 由 undici EnvHttpProxyAgent 全局生效；java 写 `http(s).proxyHost/Port` 系统属性由 jdk 默认 ProxySelector 生效。URL 内嵌凭证不解析、`nonProxyHosts` 不支持（jdk 能力面）、socks scheme 不支持 | 内部 |
| D53 | httpIdleTimeoutMs 单臂对齐 | 只接 request timeout 臂（pi sdk.ts:351 `timeoutMs` 链）。pi 另有 `configureHttpDispatcher`（http-dispatcher.ts:84-113：undici 全局 dispatcher 的 headersTimeout/bodyTimeout＋H2 关＋family 尝试 2s）java 无对位——jdk 无全局 dispatcher；OAuth/catalog/share 等直连构造点不吃该超时 | 内部 |
| D54 | 切模型「全无级别配置」不写思考档 entry | pi `_getThinkingLevelForModelSwitch` 在当前档 undefined 时回落 DEFAULT 并 `setThinkingLevel`（写 entry）；java 继承当前档不写（裁决⑦） | 会话文件，边缘 |

---

## §6 测试计划

新增（coding-agent `core` 包测试惯例，临时 home＋`SettingsManager.withStorage`）：

1. `ModelThinkingLevelsSettingsTest`：四访问器（get/getAll 缺席空表/set 建表/
   remove 删空整键抹除）；合并视图项目域覆盖全局域单键。
2. `ThinkingLevelForModelSwitchTest`：`thinkingLevelFor` 五臂优先级（CLI>后缀>
   scope>per-model>默认）；`AgentSession.setModel` 应用 per-model；无配置时继承
   当前档（不写 entry——D54 钉死）；`cycleModelForward` scope 显式>per-model。
3. `TimeoutSettingsTest`：parse 各形（number/floor/"disabled"⇒0/空串⇒空/非法串
   ⇒空/负⇒空/非法值经 `parseTimeoutSetting` 抛 `Invalid httpIdleTimeoutMs
   setting: X` 逐字）；`DefaultProviders.apiOptions` 链（providerRetry 优先；
   0⇒`Integer.MAX_VALUE` ms；缺席⇒300s——**120s 旧值防回归**）。
4. `HttpProxySupportTest`：blank⇒no-op；env 在⇒不设（环境变量无法在单测 mock
   ⇒ 拆 `apply(String, Function<String,String> env, BiConsumer<String,String> setter)`
   包私有重载注入）；属性已在⇒不覆盖；URL 解析 host/port、缺端口⇒80、非法⇒跳过。
5. `DeviceIdSettingsTest`：`getOrCreateDeviceId` 生成→落盘（storage 重读得见）→
   幂等；项目域有 deviceId 时仍读写全局域。
6. `ImageBlockFilterTest`：user 与 toolResult 两臂；image→PLACEHOLDER 逐字；
   连续占位去重（含跨原 image/已有文本相邻）；assistant 不动；`UrlImageContent`
   不动；`getBlockImages` 默认 false；bridge 尾部动态现读（关→开中途生效）。
7. `DeclaredSettingsAccessorsTest`：14 键字段 JSON 往返（写→读→写不丢、不再落
   `unknown`）；quirk 逐键：cacheWarming 非法值回落 streaming＋只读全局域、
   fullscreenWheelScrollLines clamp 1–100/"auto"、fullscreenExitOutput 非法回落
   transcript、fullscreenScrollbar 非法回落 auto、showHardwareCursor env 回落、
   `setEnableAnalytics` 生成 trackingId＋幂等、`warnings` 默认空形。

回归：`mvn test -pl pi-java-coding-agent -am`（本批触 agent-core？——T6 只动
coding-agent 的 EngineHookBridge，agent-core 零改动；仍按铁律带 `-am`），收口
`mvn clean verify` 全量。

## §7 风险与开放点

- **默认值漂移修正的行为面**：T4 把 provider 请求超时兜底 120s⇒300s（pi 对齐）。
  长请求（大上下文非流式）更少超时——对齐方向，但属行为变化，changelog 级注记。
- **T3 全局副作用**：`SettingsManager.load` 现在可能写系统属性。集成测试若配
  `httpProxy` 会污染同 JVM 其他测试⇒测试用 `withStorage`（不应用）已隔离。
- **T6 桥构造参扩散**：`EngineHookBridge` 加构造参 ⇒ wireSession 五组串线点
  全改（批 6 形状），遗漏即 NPE——编译期可兜。
- 开放点（不阻断）：`setModelThinkingLevel`/`removeModelThinkingLevel` 的 TUI
  入口、`outputPad`/TUI 行为键、`npmCommand`/`packages` 随各自后续批。

## §8 对账表（预期）

map/04 E2 域逐行（只列翻动行）：

| 行 | 键 | w | 前 | 后 | 任务 |
|---|---|---|---|---|---|
| E2-38 | `thinkingBudgets` | 1 | 缺失 0 | **对齐 1.0** | T1 核销 |
| E2-11 | `branchSummary` | 2 | 缺失 0 | **对齐 2.0** | T1 核销 |
| E2-5 | `modelThinkingLevels` | 2 | 缺失 0 | **对齐 2.0** | T2 |
| E2-47 | `httpProxy` | 1 | 存疑 0.5 | **对齐 1.0** | T3 |
| E2-48 | `httpIdleTimeoutMs` | 1 | 缺失 0 | **对齐 1.0** | T4 |
| E2-25 | `deviceId` | 1 | 缺失 0 | **对齐 1.0** | T5 |
| E2-33 | `images`（blockImages） | 1 | 存疑 0.5 | **对齐 1.0** | T6 |
| E2-1 | `lastChangelogVersion` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-14 | `showCacheMissNotices` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-21 | `collapseChangelog` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-22 | `enableInstallTelemetry` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-23 | `enableAnalytics` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-24 | `trackingId` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-44 | `warnings` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-49 | `cacheWarming` | 2 | 缺失 0 | 存疑 1.0 | T7 |
| E2-50 | `websocketConnectTimeoutMs` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-42 | `showHardwareCursor` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-52 | `fullscreenExitOutput` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-53 | `fullscreenScrollbar` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-54 | `fullscreenCopyOnSelect` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-55 | `fullscreenWheelScrollLines` | 1 | 缺失 0 | 存疑 0.5 | T7 |
| E2-45 | `codemode` | 2 | 缺失 0 | **出分母** | 裁决② |

E2 域后态：对齐 29／缺失 2（`npmCommand`/`packages`）／存疑 23／出分母 1；
Σ(w×c) 52.0→67.5，加权 60.5%→78.5%（出分母后 67.5/84＝80.4%）。

模块：288.0＋15.5＝303.5/487 ≈ 62.3%（出分母后 /485 ≈ 62.6%）。
全仓：962.0＋15.5＝977.5/1381 ≈ 70.8%（出分母后 /1379 ≈ 70.9%）。

## §9 闭环回填

闭环时（按 docs/20 §4 三处）：

1. `docs/04-open-items-register.md`：B15-残留-5 相关记述核销（SessionSetup
   javadoc 自述同步删除）；窄差 D52–D54 入表；codemode 出分母注记（若裁决②过）。
2. `docs/05-module-alignment-map.md`＋`docs/map/04-coding-agent.md`：E2 域 22
   行翻动＋汇总行＋改判注记；模块/全仓账面。
3. `docs/20-coding-agent-batch-plan.md`：§2 追加批 11 行；§1 四项读数重填；
   §3 批 11 行移除、「settings 消费点批」按剩余欠账重述（TUI 行为键＋
   `outputPad`＋setter TUI 入口）；**下一包号 docs/23**。
4. 记忆：批 11 闭环节点（含下一包号）。
