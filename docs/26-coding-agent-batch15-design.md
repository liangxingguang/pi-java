# coding-agent 批 15 设计稿：slash 命令尾（C 域）——`/bug` 故障上报＋crash-log＋存疑修

> 【背景摘要】本稿是 coding-agent 对齐批 15（批次总账 docs/20 §3 序 14）的设计文档。
> 范围三件事：① `/bug` 故障上报全链（pi 侧 ~670 行是本批主体：TUI 流 298＋核心 375，
> 外加上传 37／ZIP 71）；② crash-log 子系统（pi 170 行＋interactive-mode 集成点：
> 崩溃落盘、下次启动提示、非重试错误 `/bug` 建议）；③ C 域（slash 命令，map/04 §C）
> 存疑修——`/thinking` `/model` `/export` `/session` 四条补臂＋`/changelog` `/compact`
> 两条核销。每批流程：设计稿 → 用户审核 → 写码 → 闭环回填。
>
> 术语（首现释义，后续直接用）：
> - **pi / 锚点**：被对标的原项目（TypeScript），取证只走 `git show ea448f454:<path>`，不读工作树。
> - **crash-log**：pi 的崩溃记录机制——进程将死时把崩溃写入 `agentDir/crashes.json`
>   （最多 5 条、7 天有效），下次启动提示用户「Run /bug」。
> - **bug report bundle**：`/bug` 产出的上报包——`report.json`（元数据）＋
>   `diagnostics.json`（失败诊断）＋可选 `session.jsonl`（转录）＋可选 `summary.md`
>   （模型代写摘要），上传 Radius 网关或导出 ZIP。
> - **脱敏（redact）**：上报前剥除凭证——敏感键名（api_key/secret/token/password 等）
>   值改 `<redacted>`，URL 剥 userinfo 与敏感 query 值。
> - **存疑/缺失/对齐**：map 行三档判定（0.5/0/1.0）。**核销**：map 行证据滞后、代码
>   其实已对齐，只翻判定不写码。
> - **窄差（D 编号）**：无法完全对齐、登记在册的差异。**工单（B 编号）**：docs/04 开放项。
> - **模型可见面**：大模型能读到的文本（本批触及两处：bug 摘要系统提示词与指令，
>   逐字对齐）。其余文案是【用户可见】（也逐字对齐 pi）。

---

## §0 基线与预期翻动

基线（批 14 闭环实算，2026-10-23）：模块 334.0/485 ≈ 68.9%；C 域 27.0/38 ≈ 71.1%；
map/05 tui/web 116.5/245 ≈ 47.6%；全仓 1,011.5/1379 ≈ 73.3%。

| 行 | 前态 | 后态 | Δ（加权） | 手段 |
|---|---|---|---:|---|
| map/04 **C-9** `/bug`（w1） | 缺失 | 对齐 | +1.0 | T1–T7（本批主体） |
| **C-4** `/thinking`（w2） | 缺失 | 对齐 | +2.0 | T8（窄差 D84） |
| **C-2** `/model`（w2） | 存疑 | 对齐 | +1.0 | T9 直设臂 |
| **C-6** `/export`（w2） | 存疑 | 对齐 | +1.0 | T10 默认臂＋引号解析 |
| **C-12** `/session`（w1） | 存疑 | 对齐 | +0.5 | T11 全段（D68 销号） |
| **C-13** `/changelog`（w1） | 存疑 | 对齐 | +0.5 | **核销**（T12，批 12 已落） |
| **C-21** `/compact`（w2） | 存疑 | 对齐 | +1.0 | **核销**（T12，abort-first 已落） |
| **C-23** `/reload`（w1） | 存疑 | **不动** | 0 | 重排批 16/E5（§4） |
| map/05 **S29** `/bug` 流程（w1） | 缺失 | 对齐 | +1.0 | T7 |
| map/05 **S30** 崩溃记录＋启动提示（w1） | 缺失 | 对齐 | +1.0 | T1/T2 |
| map/05 **R29** 安装变更警告（w1） | 缺失 | **不动** | 0 | 裁决④挂起 |
| E8-2 bug 上报（w1，不计入分母） | 缺失 | 对齐 | 注记 | T1–T7 |
| E8-3 crash-log（w1，不计入分母） | 缺失 | 对齐 | 注记 | T1/T2 |

**预期账面**：C 域 27.0→**34.0/38 ≈ 89.5%**（余 4：C-1 设置选择器、C-5 scoped-models
选择器、C-8 Radius 分享、C-18 Radius 登录、C-23 reload——全在选择器/Radius/E5 域）；
模块 334.0→**341.0/485 ≈ 70.3%**（+7.0）；map/05 116.5→**118.5/245 ≈ 48.4%**（+2.0）；
全仓 1,011.5→**1,020.5/1379 ≈ 74.0%**（+9.0）。

**工单**：**B79 销号**（`/bug`＋崩溃记录）；**D68 销号**（/session 结构差）；**B256 即登
即销**（裁决⑤的 /import fatal 差，先例：B255/B239）；R29 挂起注记（裁决④）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 crash-log 本体：`core/crash-log.ts`（170 行逐字全贴）

```ts
export interface CrashRecord {
	timestamp: string;
	version: string;
	kind: "uncaught_exception" | "fatal_error";
	message: string;
	stack: string | null;
	sessionFile: string | null;
	cwd: string;
	notified?: boolean;
}

const MAX_CRASH_RECORDS = 5;
const MAX_AGE = 7 * 24 * 60 * 60 * 1000;

function crashLogPath(agentDir = getAgentDir()): string {
	return join(agentDir, "crashes.json");
}

export function readCrashLog(path = crashLogPath()): CrashRecord[] {
	try {
		const records: unknown = JSON.parse(readFileSync(path, "utf8"));
		return Array.isArray(records)
			? records.filter(
					(record): record is CrashRecord =>
						typeof record === "object" &&
						record !== null &&
						typeof (record as CrashRecord).timestamp === "string" &&
						typeof (record as CrashRecord).message === "string",
				)
			: [];
	} catch {
		return [];
	}
}

function writeCrashLog(records: readonly CrashRecord[], path: string): void {
	mkdirSync(dirname(path), { recursive: true });
	writeFileSync(path, `${JSON.stringify(records, null, 2)}\n`);
}

type ExtensionStackMetadata = Pick<Extension, "path" | "resolvedPath" | "sourceInfo">;

function normalizeStackPath(value: string): string {
	return value.replace(/\\/g, "/").replace(/\/+$/u, "");
}

function stackContainsPath(stack: string, targetPath: string, includeDescendants: boolean): boolean {
	const target = normalizeStackPath(targetPath);
	if (!target || isSyntheticPath(target)) return false;
	const caseInsensitive = /^[a-z]:\//iu.test(target);
	const haystack = caseInsensitive ? stack.toLowerCase() : stack;
	const needle = caseInsensitive ? target.toLowerCase() : target;
	if (includeDescendants) return haystack.includes(`${needle}/`);

	let index = haystack.indexOf(needle);
	while (index !== -1) {
		const next = haystack[index + needle.length];
		if (next === undefined || next === ":" || next === ")" || /\s/u.test(next)) return true;
		index = haystack.indexOf(needle, index + needle.length);
	}
	return false;
}

/** Find loaded extensions with source files in a stack trace. */
export function findExtensionStackMatches(
	stack: string | undefined,
	extensions: readonly ExtensionStackMetadata[],
): string[] {
	if (!stack) return [];
	const normalizedStack = stack
		.split("\n")
		.slice(1)
		.filter((line) => /^\s+at\s/u.test(line))
		.map((line) => {
			try {
				return decodeURI(line);
			} catch {
				return line;
			}
		})
		.join("\n")
		.replace(/\\/g, "/");
	const matches: string[] = [];
	const seen = new Set<string>();

	for (const extension of extensions) {
		const resolvedPath = normalizeStackPath(extension.resolvedPath);
		const singleFilePackage =
			extension.sourceInfo.origin === "package" &&
			!/^(?:npm:|git:|https?:\/\/|ssh:\/\/)/u.test(extension.sourceInfo.source) &&
			/\.[cm]?[jt]s$/u.test(extension.sourceInfo.source);
		const packageRoot =
			extension.sourceInfo.origin === "package" && !singleFilePackage && extension.sourceInfo.baseDir
				? extension.sourceInfo.baseDir
				: undefined;
		const slashIndex = resolvedPath.lastIndexOf("/");
		const directoryEntry = /\/index\.[cm]?[jt]s$/u.test(resolvedPath);
		const matched = packageRoot
			? stackContainsPath(normalizedStack, packageRoot, true)
			: directoryEntry && slashIndex !== -1
				? stackContainsPath(normalizedStack, resolvedPath.slice(0, slashIndex), true)
				: stackContainsPath(normalizedStack, resolvedPath, false);
		if (!matched) continue;

		const label =
			extension.sourceInfo.origin === "package" && extension.sourceInfo.source
				? extension.sourceInfo.source
				: extension.path;
		if (!seen.has(label)) {
			seen.add(label);
			matches.push(label);
		}
	}
	return matches;
}

/** Best-effort persistence for callers that are already crashing. */
export function recordCrash(
	crash: { kind: CrashRecord["kind"]; error: unknown; sessionFile?: string; cwd: string },
	path = crashLogPath(),
): CrashRecord | undefined {
	try {
		const { error } = crash;
		const record: CrashRecord = {
			timestamp: new Date().toISOString(),
			version: VERSION,
			kind: crash.kind,
			message: error instanceof Error ? error.message || error.name : String(error),
			stack: error instanceof Error && error.stack ? error.stack : null,
			sessionFile: crash.sessionFile ?? null,
			cwd: crash.cwd,
		};
		writeCrashLog([...readCrashLog(path), record].slice(-MAX_CRASH_RECORDS), path);
		return record;
	} catch {
		return undefined;
	}
}

/** Return the newest recent crash, marking pending records as announced. */
export function takeUnnotifiedCrash(path = crashLogPath(), now = Date.now()): CrashRecord | undefined {
	const records = readCrashLog(path);
	const crash = [...records]
		.reverse()
		.find((record) => !record.notified && now - Date.parse(record.timestamp) <= MAX_AGE);
	if (!crash) return undefined;
	try {
		writeCrashLog(
			records.map((record) => (record.notified ? record : { ...record, notified: true })),
			path,
		);
	} catch {
		// Showing the notice again is harmless.
	}
	return crash;
}

export function clearCrashLog(path = crashLogPath()): void {
	try {
		rmSync(path, { force: true });
	} catch {
		// The records can be attached again if cleanup fails.
	}
}
```

要点钉注：
- 读失败/非数组/缺 `timestamp`/`message` 字段 ⇒ 静默过滤或空表（崩了不能再崩）。
- `recordCrash`：message 取 `error.message || error.name`（空 message 回落类名）；
  追加后**只留末 5 条**；整体 try/catch 返回 `undefined`。
- `takeUnnotifiedCrash`：取**最新一条**未通知且 ≤7 天的；把**所有**未通知记录标记
  `notified: true` 回写（不只取出的那条）；回写失败静默（下次再提示无害）。
- `kind` 闭集两值：`uncaught_exception`／`fatal_error` ⇒ java 侧 `enum`（纯常量闭集，
  `@JsonValue` snake_case）。

### 1.2 崩溃集成点：`modes/interactive/interactive-mode.ts`

**启动提示**（:1220-1226，init 流程内，changelog/models.json 警告之后）：

```ts
const crash = takeUnnotifiedCrash();
if (crash) {
	const when = new Date(crash.timestamp).toLocaleString();
	this.showWarning(
		`${APP_NAME} crashed on ${when} (${crash.message}). Run /bug to report it; the crash details are attached automatically.`,
	);
}
```

**fatal 运行时错误**（:2113-2126 `handleFatalRuntimeError`）：showError → 扩展提示 →
`recordCrash("fatal_error")` 成功 ⇒ chat 区打 `crashReportInstructions()` → 停 TUI →
`process.exit(1)`。调用点：交互模式 `/import` 失败（:6607）。

**recordCrash 包装／提示文案**（:2148-2166）：

```ts
private recordCrash(kind: "uncaught_exception" | "fatal_error", error: unknown): boolean {
	try {
		return (
			recordCrash({
				kind,
				error,
				sessionFile: this.session.sessionFile,
				cwd: this.session.sessionManager.getCwd(),
			}) !== undefined
		);
	} catch {
		return false;
	}
}

private crashReportInstructions(): string {
	const resume = this.session.sessionFile ? `run \`${APP_NAME} -r\` to resume the session, then` : "start pi and";
	return `To report this crash: ${resume} run /bug. The crash details are attached automatically.`;
}
```

**`/bug` 建议**（:2168-2190＋调用点 :3586，message_end 流式收尾处）：

```ts
private suggestBugReport(): void {
	if (this.bugReportHintShown) return;
	this.bugReportHintShown = true;
	this.chatContainer.addChild(
		new ThemedText(
			() => theme.fg("muted", `If this looks like a ${APP_NAME} bug, /bug sends a report to the developers.`),
			this.outputPad,
			0,
		),
	);
	this.ui.requestRender();
}

private maybeSuggestBugReport(message: AssistantMessage): void {
	if (message.stopReason !== "error" || isRetryableAssistantError(message)) return;
	if (/\b(?:abort(?:ed)?|cancel(?:l?ed)?)\b/i.test(message.errorMessage ?? "")) return;
	if (this.maybeShowInstallChangeWarning()) return;
	this.suggestBugReport();
}
```

- 门序：stopReason 非 error ⇒ 不提示；**可重试错误**（`isRetryableAssistantError`，
  pi-ai retry.ts:252，java 对位 `agent-core RetryableError.isRetryableAssistantError`
  已存在）⇒ 不提示；errorMessage 含 abort/cancel 词 ⇒ 不提示；安装变更警告已展示
  ⇒ 不提示（R29，裁决④挂起 ⇒ java 门恒 false）。每会话最多一次。
- pi 另有 `tool_execution_end` isError ⇒ `maybeShowInstallChangeWarning`（:3643）——
  同 R29 挂起。

**未捕获异常**（:4363-4390 `uncaughtCrash`）：

```ts
private uncaughtCrash(error: Error): never {
	// A dead terminal is not a pi crash. Do not try to restore it or record it.
	if (isDeadTerminalError(error)) {
		this.emergencyTerminalExit();
	}
	if (this.isShuttingDown) {
		process.exit(1);
	}
	this.isShuttingDown = true;
	try { this.unregisterSignalHandlers(); } catch {}
	try { killTrackedDetachedChildren(); } catch {}
	try { this.ui.stop(); } catch {}
	console.error(`${APP_NAME} exiting due to uncaughtException:`);
	console.error(error);
	const extensionHint = this.getCrashExtensionHint(error);
	if (extensionHint) console.error(`\n${extensionHint}`);
	if (this.recordCrash("uncaught_exception", error)) {
		console.error(`\n${this.crashReportInstructions()}`);
	}
	process.exit(1);
}
```

**死终端**（:292-301＋:4332-4338＋三流监听 :4396-4413）：

```ts
// EIO: tty reads/ioctls from an orphaned background process group, or writes after hangup.
// ENOTTY: the tty was revoked (macOS) and stdin is no longer a terminal.
const DEAD_TERMINAL_ERROR_CODES = new Set(["EIO", "EPIPE", "ENOTCONN", "ENOTTY"]);

function isDeadTerminalError(error: unknown): boolean {
	if (!error || typeof error !== "object" || !("code" in error)) return false;
	const code = (error as NodeJS.ErrnoException).code;
	return code !== undefined && DEAD_TERMINAL_ERROR_CODES.has(code);
}

private emergencyTerminalExit(): never {
	this.isShuttingDown = true;
	this.unregisterSignalHandlers();
	killTrackedDetachedChildren();
	// The terminal is gone. Do not run normal shutdown because TUI and
	// extension cleanup can write restore sequences and re-trigger EIO.
	process.exit(129);
}
```

⇒ JVM 无 errno 面，**裁决③**：不做，登记窄差 D81。

**扩展提示成形**（:304-319 `formatCrashExtensionHint`）：

```ts
export function formatCrashExtensionHint(extensionMatches: readonly string[] | undefined): string | undefined {
	const matches = Array.isArray(extensionMatches)
		? extensionMatches.filter((match): match is string => typeof match === "string" && match.length > 0)
		: [];
	if (matches.length === 0) return undefined;
	const quoted = matches.map((match) => `\`${match}\``);
	const labels =
		quoted.length === 1
			? quoted[0]
			: quoted.length === 2
				? quoted.join(" and ")
				: `${quoted.slice(0, -1).join(", ")}, and ${quoted[quoted.length - 1]}`;
	const noun = matches.length === 1 ? "extension" : "extensions";
	const pronoun = matches.length === 1 ? "it" : "them";
	return `A stack frame came from loaded ${noun} ${labels}, which may be involved. Try disabling ${pronoun} with \`${APP_NAME} config\`, or run \`${APP_NAME} -ne\` to confirm.`;
}
```

**R29 安装变更警告**（:2193-2209 `maybeShowInstallWarning`＋`config.ts:538
detectInstallChange`：重读启动时记下的 package.json 路径，ENOENT ⇒ removed、version
变了 ⇒ updated）——java 无 package.json 安装形态，**裁决④**挂起。

### 1.3 bug report 核心：`core/bug-report.ts`（375 行逐字全贴）

```ts
export const BUG_REPORT_CUSTOM_ENTRY_TYPE = "pi.bug-report";
const BUG_REPORT_SCHEMA_VERSION = 1;
const REDACTED = "<redacted>";
const SENSITIVE_KEY = /(?:^|[-_])(api[-_]?key|secret|token|password|passwd|credential|authorization|cookie)(?:$|[-_])/i;

function isSensitiveKey(key: string): boolean {
	return SENSITIVE_KEY.test(key.replace(/([a-z0-9])([A-Z])/g, "$1_$2"));
}

/** Strip credentials and secret-looking query parameters from a URL. */
export function redactUrl(value: string): string {
	const nested = /^([a-z][a-z0-9+.-]*:)([a-z][a-z0-9+.-]*:\/\/.*)$/i.exec(value);
	if (nested) return `${nested[1]}${redactUrl(nested[2])}`;
	try {
		const url = new URL(value);
		let changed = false;
		if (url.username || url.password) {
			url.username = "";
			url.password = "";
			changed = true;
		}
		for (const key of url.searchParams.keys()) {
			if (isSensitiveKey(key)) {
				url.searchParams.set(key, REDACTED);
				changed = true;
			}
		}
		return changed ? url.toString() : value;
	} catch {
		return value;
	}
}

/** Copy a JSON value while removing values that may contain credentials. */
export function redactJsonValue(value: unknown): unknown {
	if (value === undefined) return undefined;
	return JSON.parse(
		JSON.stringify(value, (key, child: unknown) => {
			if (child !== null && child !== undefined && isSensitiveKey(key)) return REDACTED;
			return typeof child === "string" ? redactUrl(child) : child;
		}),
	);
}

function redactSettings(settings: Settings): Settings {
	const { trackingId: _trackingId, deviceId: _deviceId, ...rest } = settings;
	return redactJsonValue(rest) as Settings;
}

function collectEnvironment() {
	const env = (name: string): string | null => process.env[name] || null;
	return {
		version: VERSION,
		userAgent: getPiUserAgent(VERSION),
		runtime: process.versions.bun ? `bun/${process.versions.bun}` : `node/${process.version}`,
		platform: process.platform,
		arch: process.arch,
		osRelease: os.release(),
		osVersion: os.version(),
		shell: process.env.SHELL?.split(/[\\/]/).pop() || null,
		terminal: {
			term: env("TERM"),
			program: env("TERM_PROGRAM"),
			programVersion: env("TERM_PROGRAM_VERSION"),
			colorterm: env("COLORTERM"),
			tmux: Boolean(process.env.TMUX),
			ssh: Boolean(process.env.SSH_CONNECTION || process.env.SSH_CLIENT || process.env.SSH_TTY),
			ci: Boolean(process.env.CI),
		},
		// Names help diagnose configuration; values never leave the machine.
		piEnvironmentVariables: Object.keys(process.env)
			.filter((name) => name.startsWith("PI_"))
			.sort(),
	};
}

function describeModel(model: Model<Api>) {
	return {
		provider: model.provider,
		id: model.id,
		name: model.name,
		api: model.api,
		baseUrl: redactUrl(model.baseUrl),
		reasoning: model.reasoning,
		input: model.input,
		contextWindow: model.contextWindow,
		maxTokens: model.maxTokens,
		samplingParams: model.samplingParams ? redactJsonValue(model.samplingParams) : null,
		compat: model.compat ? redactJsonValue(model.compat) : null,
		thinkingLevelMap: model.thinkingLevelMap ?? null,
		headerNames: Object.keys(model.headers ?? {}).sort(),
	};
}

function describeProvider(modelRuntime: ModelRuntime, provider: Provider) {
	const authTypes: Array<"api_key" | "oauth"> = [];
	if (provider.auth.apiKey) authTypes.push("api_key");
	if (provider.auth.oauth) authTypes.push("oauth");
	return {
		id: provider.id,
		name: provider.name,
		baseUrl: provider.baseUrl ? redactUrl(provider.baseUrl) : null,
		headerNames: Object.keys(provider.headers ?? {}).sort(),
		authTypes,
		authStatus: modelRuntime.getProviderAuthStatus(provider.id),
		usingOAuth: modelRuntime.isUsingOAuth(provider.id),
		registeredByExtension: modelRuntime.getRegisteredProviderIds().includes(provider.id),
	};
}

function describeExtension(extension: Extension) {
	return {
		path: extension.path,
		source: redactUrl(extension.sourceInfo.source),
		scope: extension.sourceInfo.scope,
		origin: extension.sourceInfo.origin,
		hidden: extension.hidden === true,
	};
}

export function collectBugReportMetadata(options: CollectBugReportMetadataOptions) {
	const hint = options.hint?.trim() || null;
	const provider = options.model ? options.modelRuntime.getProvider(options.model.provider) : undefined;
	return {
		schemaVersion: BUG_REPORT_SCHEMA_VERSION,
		id: options.id ?? uuidv7(),
		createdAt: new Date().toISOString(),
		hint,
		environment: collectEnvironment(),
		session: {
			id: options.sessionId,
			included: options.includeSession,
			summaryIncluded: options.includeSummary,
			messageCount: options.messageCount,
			...(options.includeSession ? { cwd: options.cwd } : {}),
		},
		model: options.model ? describeModel(options.model) : null,
		provider: provider ? describeProvider(options.modelRuntime, provider) : null,
		thinkingLevel: options.thinkingLevel,
		extensions: options.extensions.map(describeExtension),
		extensionErrors: options.extensionErrors.map(({ path, error }) => ({ path, error })),
		settings: {
			global: redactSettings(options.globalSettings),
			project: redactSettings(options.projectSettings),
		},
	};
}

/** Collect failed assistant turns without collecting conversation content. */
export function collectBugReportDiagnostics(
	sessionManager: ReadonlySessionManager,
	crashes: readonly CrashRecord[] = [],
) {
	const entries = sessionManager.getEntries();
	const assistant = [];
	let assistantMessageCount = 0;
	for (const entry of entries) {
		if (entry.type !== "message" || entry.message.role !== "assistant") continue;
		assistantMessageCount++;
		const message = entry.message;
		const diagnostics = message.diagnostics ?? [];
		if (
			diagnostics.length === 0 &&
			message.stopReason !== "error" &&
			message.stopReason !== "aborted" &&
			!message.errorMessage
		) {
			continue;
		}
		assistant.push({
			entryId: entry.id,
			timestamp: entry.timestamp,
			provider: message.provider,
			model: message.model,
			api: message.api,
			stopReason: message.stopReason,
			...(message.rawStopReason === undefined ? {} : { rawStopReason: message.rawStopReason }),
			...(message.errorMessage === undefined ? {} : { errorMessage: message.errorMessage }),
			diagnostics,
		});
	}
	return {
		schemaVersion: BUG_REPORT_SCHEMA_VERSION,
		sessionId: sessionManager.getSessionId(),
		entryCount: entries.length,
		assistantMessageCount,
		assistant,
		crashes: crashes.map(({ notified: _notified, ...record }) => record),
	};
}

export interface BugReportBundle {
	metadata: BugReportMetadata;
	diagnostics: BugReportDiagnostics;
	sessionJsonl?: string;
	summary?: string;
}

export interface BugReportSessionEntryData {
	id: string;
	createdAt: string;
	hint: string | null;
	sessionIncluded: boolean;
	summaryIncluded: boolean;
	delivery: "zip" | "upload";
	path?: string;
}

/** Files shared by upload and zip export. */
export function bugReportFiles(bundle: BugReportBundle): BugReportFile[] {
	const files: BugReportFile[] = [
		{ name: "report.json", contentType: "application/json", data: `${JSON.stringify(bundle.metadata, null, 2)}\n` },
		{
			name: "diagnostics.json",
			contentType: "application/json",
			data: `${JSON.stringify(bundle.diagnostics, null, 2)}\n`,
		},
	];
	if (bundle.sessionJsonl !== undefined) {
		files.push({ name: "session.jsonl", contentType: "application/x-ndjson", data: bundle.sessionJsonl });
	}
	if (bundle.summary !== undefined) {
		files.push({
			name: "summary.md",
			contentType: "text/markdown",
			data: bundle.summary.endsWith("\n") ? bundle.summary : `${bundle.summary}\n`,
		});
	}
	return files;
}

export function writeBugReportArchive(bundle: BugReportBundle, filePath: string): Promise<void> {
	return writeZipArchive(filePath, bugReportFiles(bundle));
}

export function bugReportArchiveFileName(id: string): string {
	return `pi-bug-report-${id}.zip`;
}

const BUG_SUMMARY_SYSTEM_PROMPT = `You are helping a user file a bug report about pi, the coding agent they are talking to. You will be shown the conversation transcript. Write a report for the pi developers describing what the user was doing and what went wrong.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the report.`;

const BUG_SUMMARY_INSTRUCTIONS = `Write the bug report in Markdown with these sections:

## What the user was doing
One short paragraph.

## What went wrong
Concrete description of the failure: wrong output, errors, hangs, tool failures, unexpected behavior. Quote error messages and tool output verbatim where they exist.

## Steps to reproduce
Numbered list, as specific as the transcript allows.

## Relevant details
Tool calls involved, files touched, model behavior, anything else that helps a developer reproduce or locate the problem.

Do not include file contents, secrets, or credentials from the transcript; refer to files by path only. Keep the report factual and concise.`;

function selectMessages(messages: readonly AgentMessage[], tokenBudget: number): AgentMessage[] {
	const selected: AgentMessage[] = [];
	let tokens = 0;
	for (let index = messages.length - 1; index >= 0; index--) {
		const message = messages[index];
		const next = estimateTokens(message);
		if (selected.length > 0 && tokens + next > tokenBudget) break;
		selected.push(message);
		tokens += next;
	}
	return selected.reverse();
}

/** Ask the session model for a report when the user does not share the transcript. */
export async function generateBugReportSummary(options: GenerateBugReportSummaryOptions): Promise<string> {
	const { model } = options;
	const contextWindow = model.contextWindow > 0 ? model.contextWindow : 128_000;
	const messages = selectMessages(options.messages, Math.floor(contextWindow * 0.6));
	const hint = options.hint?.trim();
	const prompt = [
		messages.length < options.messages.length
			? `Note: only the last ${messages.length} of ${options.messages.length} messages are shown.`
			: undefined,
		`<conversation>\n${serializeConversation(convertToLlm(messages))}\n</conversation>`,
		hint ? `<user-report>\n${hint}\n</user-report>` : undefined,
		BUG_SUMMARY_INSTRUCTIONS,
	]
		.filter((part) => part !== undefined)
		.join("\n\n");
	const requestOptions: SimpleStreamOptions = {
		maxTokens: Math.min(4096, model.maxTokens > 0 ? model.maxTokens : Number.POSITIVE_INFINITY),
		signal: options.signal,
		apiKey: options.apiKey,
		headers: options.headers,
		env: options.env,
		sessionId: options.sessionId,
		...(model.reasoning && options.thinkingLevel && options.thinkingLevel !== "off"
			? { reasoning: options.thinkingLevel }
			: {}),
	};
	const response = await completeSummarization(
		model,
		normalizeContext({
			systemPrompt: BUG_SUMMARY_SYSTEM_PROMPT,
			messages: [{ role: "user", content: [{ type: "text", text: prompt }], timestamp: Date.now() }],
		}),
		requestOptions,
		options.streamFn,
		options.retry,
	);
	if (response.stopReason === "aborted") throw new Error("Bug report summary was cancelled");
	const failure = getSummarizationFailure(response, "Bug report summary");
	if (failure) throw new Error(failure);
	if (response.content.some((block) => block.type === "toolCall")) {
		throw new Error("Bug report summary attempted to call a tool");
	}
	const text = contentText(response.content).trim();
	if (!text) throw new Error("Bug report summary was empty");
	return text;
}
```

要点钉注：
- `SENSITIVE_KEY`：键名先做 camelCase→snake_case（`apiKey`→`api_Key`→小写比较），
  边界是 `^`/`$`/`-`/`_`。**【模型不可见】【用户数据面】**，逐字。
- `redactUrl` 嵌套 scheme 臂：`https://proxy.example/http://inner?token=x` 先剥外层
  scheme 再递归内层。解析失败原样返回。
- `redactSettings`：剥 `trackingId`/`deviceId` 两键后再走全树脱敏（java `Settings`
  只有 `deviceId`，批 11 已落 ⇒ 只剥它，无 trackingId 字段可剥）。
- 诊断收集：**只收失败/异常臂**（diagnostics 非空｜stopReason error/aborted｜
  errorMessage 非空），正常 assistant 消息只计入 `assistantMessageCount`。
- `selectMessages`：从尾往前装预算；**第一条必进**（`selected.length > 0 &&` 的短路），
  超预算即停。
- 摘要四守卫逐字：aborted ⇒ `Bug report summary was cancelled`；failure ⇒
  `getSummarizationFailure(response, "Bug report summary")`（java 对位
  `LlmSummaryGenerator.summarizationFailure`，已存在）；toolCall ⇒
  `Bug report summary attempted to call a tool`；空文本（trim 后）⇒
  `Bug report summary was empty`。
- 摘要系统提示词与指令是**【模型可见面】**，逐字不可动。

### 1.4 上传：`core/bug-report-upload.ts`（37 行逐字全贴）

```ts
interface UploadBugReportOptions {
	token?: string;
	signal?: AbortSignal;
	gatewayUrl?: string;
}

/** Upload a report as multipart form data, anonymously or attributed to a Radius account. */
export async function uploadBugReport(
	bundle: BugReportBundle,
	options: UploadBugReportOptions = {},
): Promise<{ id: string }> {
	const body = new FormData();
	for (const file of bugReportFiles(bundle)) {
		body.append(file.name, new Blob([file.data], { type: file.contentType }), file.name);
	}
	const response = await fetch(new URL("/v1/bug-reports", options.gatewayUrl ?? getRadiusGatewayUrl()), {
		method: "POST",
		headers: options.token ? { Authorization: `Bearer ${options.token}` } : undefined,
		body,
		signal: options.signal,
	});
	const json = (await response.json().catch(() => null)) as {
		ok?: boolean;
		bug_report?: { id?: string };
		error?: string;
		description?: string;
	} | null;
	if (response.ok && json?.ok === true && typeof json.bug_report?.id === "string") {
		return { id: json.bug_report.id };
	}
	const detail = json && !json.ok ? json.description || json.error : undefined;
	throw new Error(`Bug report upload failed: ${detail || response.statusText || response.status}`);
}
```

- 网关：`getRadiusGatewayUrl()`（`core/radius.ts:12-14`）＝ `PI_RADIUS_GATEWAY` 环境
  变量 ?? `https://radius.pi.dev`（`pi-ai/providers/radius-config.ts:4`），normalize
  去尾斜杠。
- token：Radius provider 的凭证（匿名也可上传）；java 无 Radius provider ⇒ **裁决⑩**
  恒匿名，缝预留。
- 错误文案【用户可见】逐字：`Bug report upload failed: <detail||statusText||status>`。

### 1.5 ZIP：`utils/zip.ts`（71 行逐字全贴）

pi 手卷经典 ZIP（local header 30B＋central 46B＋end 22B；deflateRaw；crc32；DOS 时间；
UTF-8 标志 `0x0800`；版本 20）：

```ts
function dosDateTime(date: Date): { time: number; day: number } {
	return {
		time: (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
		day: ((Math.max(1980, date.getFullYear()) - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
	};
}

/** Create the small, classic ZIP archives used by bug reports. */
function createZipArchive(entries: readonly ZipEntry[]): Buffer {
	const files: Buffer[] = [];
	const directory: Buffer[] = [];
	const { time, day } = dosDateTime(new Date());
	let offset = 0;

	for (const entry of entries) {
		const name = Buffer.from(entry.name);
		const data = typeof entry.data === "string" ? Buffer.from(entry.data) : Buffer.from(entry.data);
		const compressed = deflateRawSync(data);
		const checksum = crc32(data);

		const local = Buffer.alloc(30);
		local.writeUInt32LE(0x04034b50, 0);
		local.writeUInt16LE(20, 4);
		local.writeUInt16LE(0x0800, 6);
		local.writeUInt16LE(8, 8);
		local.writeUInt16LE(time, 10);
		local.writeUInt16LE(day, 12);
		local.writeUInt32LE(checksum, 14);
		local.writeUInt32LE(compressed.length, 18);
		local.writeUInt32LE(data.length, 22);
		local.writeUInt16LE(name.length, 26);

		const central = Buffer.alloc(46);
		central.writeUInt32LE(0x02014b50, 0);
		central.writeUInt16LE(20, 4);
		central.writeUInt16LE(20, 6);
		central.writeUInt16LE(0x0800, 8);
		central.writeUInt16LE(8, 10);
		central.writeUInt16LE(time, 12);
		central.writeUInt16LE(day, 14);
		central.writeUInt32LE(checksum, 16);
		central.writeUInt32LE(compressed.length, 20);
		central.writeUInt32LE(data.length, 24);
		central.writeUInt16LE(name.length, 28);
		central.writeUInt32LE(offset, 42);

		files.push(local, name, compressed);
		directory.push(central, name);
		offset += local.length + name.length + compressed.length;
	}

	const centralDirectory = Buffer.concat(directory);
	const end = Buffer.alloc(22);
	end.writeUInt32LE(0x06054b50, 0);
	end.writeUInt16LE(entries.length, 8);
	end.writeUInt16LE(entries.length, 10);
	end.writeUInt32LE(centralDirectory.length, 12);
	end.writeUInt32LE(offset, 16);
	return Buffer.concat([...files, centralDirectory, end]);
}

export function writeZipArchive(filePath: string, entries: readonly ZipEntry[]): Promise<void> {
	return writeFile(filePath, createZipArchive(entries));
}
```

⇒ **裁决①**：java 用 JDK `java.util.zip.ZipOutputStream`（DEFLATED），不手卷字节。
窄差 D79 登记字节级差。

### 1.6 `/bug` TUI 流：`modes/interactive/bug-report.ts`（298 行逐字全贴）

```ts
const DISCLAIMER =
	"This report goes to the Pi developers (Earendil) and is not shared publicly. It includes your pi version, operating system, the current model and provider configuration (without API keys), loaded extensions, settings, and provider error diagnostics from this session.";
const TRANSCRIPT_NOTE =
	"The transcript contains your messages, model output, tool calls and their results, including file contents and command output read during this session.";

/** Run the `/bug` flow: consent, optional summary, then upload or export. */
export async function reportBug(context: BugReportContext, initialHint?: string): Promise<void> {
	const options = await promptForOptions(context, initialHint);
	if (!options) {
		context.showStatus("Bug report cancelled");
		return;
	}
	if (options.delivery === "upload" && process.env.PI_OFFLINE) {
		context.showError("Uploading bug reports requires online mode. Use Export as Zip instead.");
		return;
	}

	let summary: string | undefined;
	if (options.includeSummary) {
		const loader = showLoader(
			context,
			`Writing summary with ${context.session.model?.name ?? "the current model"}...`,
		);
		try {
			summary = await context.session.summarizeForBugReport({ hint: options.hint, signal: loader.signal });
		} catch (error: unknown) {
			restoreEditor(context, loader);
			if (loader.signal.aborted) context.showStatus("Bug report cancelled");
			else context.showError(`Failed to write bug report summary: ${errorMessage(error)}`);
			return;
		}
		restoreEditor(context, loader);
		if (loader.signal.aborted) {
			context.showStatus("Bug report cancelled");
			return;
		}
	}

	let bundle: BugReportBundle;
	try {
		bundle = buildBundle(context.session, options, summary);
	} catch (error: unknown) {
		context.showError(`Failed to build bug report: ${errorMessage(error)}`);
		return;
	}

	if (options.delivery === "upload") {
		const failure = await upload(context, bundle);
		if (failure === undefined) return;
		const fallback = await choose(
			context,
			"Upload failed",
			["Export as Zip", "Cancel"],
			`${failure}\n\nExport the report as a zip archive instead?`,
		);
		if (fallback !== "Export as Zip") {
			context.showStatus("Bug report cancelled");
			return;
		}
	}
	await exportZip(context, bundle);
}

async function promptForOptions(
	context: BugReportContext,
	initialHint: string | undefined,
): Promise<BugReportOptions | undefined> {
	const hint = await input(context, "Report a bug", `${DISCLAIMER}\n\nWhat went wrong? (optional)`, initialHint);
	if (hint === null) return undefined;
	const transcript = await choose(
		context,
		"Include the session transcript?",
		["Yes, include the transcript", "No"],
		TRANSCRIPT_NOTE,
	);
	if (!transcript) return undefined;
	const includeSession = transcript !== "No";
	let includeSummary = false;
	if (!includeSession) {
		const model = context.session.model;
		const summary = await choose(
			context,
			`Attach a summary written by ${model?.name ?? "the current model"} instead?`,
			["Yes, generate a summary", "No"],
			`The transcript is sent to ${model?.provider ?? "your provider"} with your credentials and tokens. Only the generated summary is attached; the transcript stays on your machine.`,
		);
		if (!summary) return undefined;
		includeSummary = summary !== "No";
	}
	const description = hint.trim();
	const delivery = await choose(
		context,
		"Bug report",
		["Upload Report", "Export as Zip", "Cancel"],
		`Description: ${description || "none"}\nTranscript: ${includeSession ? "included" : "not included"}\nSummary: ${includeSummary ? `written by ${context.session.model?.name ?? "the current model"}` : "none"}\n\nUpload sends the report to ${new URL(getRadiusGatewayUrl()).host}. Export writes a zip archive to the current directory instead.`,
	);
	if (!delivery || delivery === "Cancel") return undefined;
	return {
		hint: description || undefined,
		includeSession,
		includeSummary,
		delivery: delivery === "Upload Report" ? "upload" : "zip",
	};
}

function buildBundle(session: AgentSession, options: BugReportOptions, summary: string | undefined): BugReportBundle {
	const extensions = session.resourceLoader.getExtensions();
	return {
		metadata: collectBugReportMetadata({
			hint: options.hint,
			sessionId: session.sessionId,
			cwd: session.sessionManager.getCwd(),
			includeSession: options.includeSession,
			includeSummary: summary !== undefined,
			messageCount: session.messages.length,
			model: session.model,
			modelRuntime: session.modelRuntime,
			thinkingLevel: session.thinkingLevel,
			extensions: extensions.extensions,
			extensionErrors: extensions.errors,
			globalSettings: session.settingsManager.getGlobalSettings(),
			projectSettings: session.settingsManager.getProjectSettings(),
		}),
		diagnostics: collectBugReportDiagnostics(session.sessionManager, readCrashLog()),
		summary,
		sessionJsonl: options.includeSession
			? serializeSessionBranch(session.sessionManager, (parentId, timestamp) =>
					createShareTrailingEntries(session, parentId, timestamp),
				)
			: undefined,
	};
}

async function upload(context: BugReportContext, bundle: BugReportBundle): Promise<string | undefined> {
	const loader = showLoader(context, "Uploading bug report...");
	try {
		const provider = context.session.modelRuntime.getProvider(RADIUS_PROVIDER_ID);
		const token = provider
			? getAuthCredential(
					await context.session.modelRuntime.getAuth(RADIUS_PROVIDER_ID, { minOAuthValidityMs: 5 * 60_000 }),
				)
			: undefined;
		const result = await uploadBugReport(bundle, { token, signal: loader.signal });
		restoreEditor(context, loader);
		recordInSession(context.session, bundle, { delivery: "upload" });
		context.showStatus(`Bug report uploaded. Report ID: ${result.id}`);
		return undefined;
	} catch (error: unknown) {
		restoreEditor(context, loader);
		if (loader.signal.aborted) {
			context.showStatus("Bug report cancelled");
			return undefined;
		}
		return errorMessage(error);
	}
}

async function exportZip(context: BugReportContext, bundle: BugReportBundle): Promise<void> {
	const archivePath = path.join(process.cwd(), bugReportArchiveFileName(bundle.metadata.id));
	try {
		await writeBugReportArchive(bundle, archivePath);
	} catch (error: unknown) {
		context.showError(`Failed to write bug report: ${errorMessage(error)}`);
		return;
	}
	recordInSession(context.session, bundle, { delivery: "zip", path: archivePath });
	context.showStatus(`Bug report exported to: ${archivePath}\nReport ID: ${bundle.metadata.id}`);
}

function recordInSession(
	session: AgentSession,
	bundle: BugReportBundle,
	delivery: Pick<BugReportSessionEntryData, "delivery" | "path">,
): void {
	session.sessionManager.appendCustomEntry(BUG_REPORT_CUSTOM_ENTRY_TYPE, {
		id: bundle.metadata.id,
		createdAt: bundle.metadata.createdAt,
		hint: bundle.metadata.hint,
		sessionIncluded: bundle.metadata.session.included,
		summaryIncluded: bundle.metadata.session.summaryIncluded,
		...delivery,
	} satisfies BugReportSessionEntryData);
	if (bundle.diagnostics.crashes.length > 0) clearCrashLog();
}
```

overlay 三件套（`input`/`choose`/`showLoader`＋`showOverlay`/`restoreEditor`）：
编辑器容器清空 → 挂组件 → 聚焦；收尾 `dispose` → 恢复主编辑器。`input` 用
`ExtensionEditorComponent`（标题/初始值/描述/外部编辑器命令），`choose` 用
`ExtensionSelectorComponent`（标题/选项/描述），loader 用 `BorderedLoader`（Esc ⇒
`signal` 中止）。

### 1.7 `/bug` 命令注册与 dispatch

- 注册（`core/slash-commands.ts:28`）：
  `{ name: "bug", description: "Report a bug to the Pi developers", argumentHint: "<description>" }`。
- dispatch（`interactive-mode.ts:3242-3247`）：

```ts
if (text === "/bug" || text.startsWith("/bug ")) {
	const hint = text.slice("/bug".length).trim();
	this.editor.setText("");
	await this.handleBugCommand(hint ? hint : undefined);
	return;
}
```

- `AgentSession.summarizeForBugReport`（`agent-session.ts:4322-4336`）：model 缺 ⇒
  `throw new Error("No model selected")`；否则 `generateBugReportSummary({
  ..._getSummarizationRequestAuth(model, signal), messages, hint, signal,
  streamFn, retry: getRetrySettings(), sessionId })`。
- 会话尾 entry：`createShareTrailingEntries`（`session-share.ts:25-45`）——
  `customType: "pi.share"`，`id` 8 位随机，`data: { systemPrompt, tools: [{name,
  description, parameters}] }`。
- `serializeSessionBranch`（`session-export.ts:9-31`）：header（`type:"session"`,
  version, id, timestamp, cwd）＋ branch 逐条 **`parentId` 重链**（覆盖原 parentId，
  首条 null）＋ trailing entries；逐行 JSON＋尾 `\n`。

### 1.8 存疑修取证

**`/thinking`**（`interactive-mode.ts:5145-5161`＋`agent-session.ts:2657-2660`）：

```ts
private handleThinkingCommand(searchTerm?: string): void {
	const availableLevels = this.session.getAvailableThinkingLevels();
	if (!searchTerm) {
		this.showThinkingSelector();
		return;
	}
	const normalized = searchTerm.trim().toLowerCase();
	const level = availableLevels.find((candidate) => candidate.toLowerCase() === normalized);
	if (!level) {
		this.showError(`Unknown thinking level "${searchTerm}". Available levels: ${availableLevels.join(", ")}.`);
		return;
	}
	this.selectThinkingLevel(level, false);
}
// selectThinkingLevel(level, persist=false)：setThinkingLevel ⇒ showStatus(`Thinking level: ${level}`)
```

`getAvailableThinkingLevels()`：model 缺 ⇒ `THINKING_LEVEL_OPTIONS` 全集（
`core/defaults.ts:4-13`：`off minimal low medium high xhigh max` 七值）；否则
`getSupportedThinkingLevels(model)`（`pi-ai models.ts:1224-1233`：非 reasoning ⇒
`["off"]`；否则按 `thinkingLevelMap` 过滤——映射为 `null` 剔除，`xhigh`/`max` 要
映射有定义）。

**`/model <ref>`**（`interactive-mode.ts:5194-5216`＋`model-resolver.ts:88-130`）：
无参 ⇒ 选择器；有参 ⇒ `findExactModelReferenceMatch`（全小写比较：
`provider/id` 全形唯一命中 ⇒ 中；含 `/` 拆 provider+id 双侧精确唯一 ⇒ 中；
裸 id 唯一 ⇒ 中；多命中/零命中 ⇒ undefined）——候选集＝scoped 非空 ? scoped
模型 : 可用快照；scoped 臂**未命中也收口**不开刷新；无 scope 未命中 ⇒
`refreshModelCatalogs`（15s 超时，警告文案见 §1.8 尾）后再找一次。命中 ⇒
`setModel({persist:false})` ⇒ `showStatus("Model: <id>")` ⇒ 订阅 auth 警告；
未命中 ⇒ `showModelSelector(searchTerm)`（带搜索词预选）。

**`/export`**（`interactive-mode.ts:6520-6562`）：路径参经 `getPathCommandArgument`
（引号臂逐字——首字符 `'`/`"` ⇒ 取到配对引号为止，无配对 ⇒ undefined 按缺省；
否则取到首个空白）；`.jsonl` 尾 ⇒ `exportToJsonl(path)`；**其余（含无参）⇒
`exportToHtml(path ?? undefined)`**——无参时 `session-<ISO 时间戳 :.→->.html`
落 cwd。状态文案 `Session exported to: <path>`；失败
`Failed to export session: <msg>`。

**`/session`**（`interactive-mode.ts:6693-6772`）：段序 Session Info
（Name?/File/ID）→ Messages（Total/User/Assistant/Tools calls,results）→ Tokens
（Input＝input+cacheRead+cacheWrite；Cached 行带命中率 `（x.x%)`；Uncached 行带
`（n written to cache)`；Output；Total）→ Cache Warming（批 13 已落）→ Cost
（Total `$x.xxx`；usage breakdown 多桶或非当前模型桶时逐行
`  <key>: $x.xxx (tk tokens)`——`formatTokens` 紧凑形 `<1000` 原样／`<10000`
`x.xk`／`<1M` `xk`／`<10M` `x.xM`／否则 `xM`；Cache Re-billed 行在 Cost 段内）。
数据源 `getSessionStats()`（`agent-session.ts:4185-4230`：usage 条目＋
branch_summary/compaction usage＋assistant message usage 全计入；toolResult
message.usage 也计）＋`getUsageCostBreakdown`（`usage-totals.ts:61-96`：assistant
按 `provider/responseModel ?? model` 分组、usage 条目按 `provider/model`、
toolResult/branch_summary/compaction 归 `Tools/summaries`；滤零桶；cost 降序）。

**`/changelog`**（`interactive-mode.ts:6774-6787`）：读 changelog 文件 ⇒
`parseChangelog` ⇒ reverse ⇒ `normalizeChangelogLinks` 拼接 ⇒ Markdown 渲染＋
`What's New` 头。java 批 12 已落：bundled CHANGELOG.md 读取＋parse＋reverse＋
join＋`What's New` 头；纯文本无 Markdown 渲染（D63 已在册）。⇒ **核销**。

**`/compact`**（pi `agent-session.ts:2764-2767` `compact()` 首句
`await this.abort()`）：java `AgentSession.compact(...)` 三入口首句
`abortAndWaitForIdle()`（:1227/:1244，agent-core 批 1 #17 落）⇒ **核销**。

`/model` 刷新臂警告文案【用户可见】逐字：
`Refreshing model catalogs…`／`Model refresh timed out; searching cached models.`／
`Could not refresh <providers>; searching cached models.`。

### 1.9 pi 测试蓝本

pi 仓 `packages/coding-agent/test` 下有 `bug-report.test.ts`（redactUrl/redactJsonValue/
collectBugReportMetadata/collectBugReportDiagnostics/bugReportFiles/selectMessages）、
`crash-log.test.ts`（record/take/clear/age 窗口/5 条截断/stack 匹配）、`zip.test.ts`
（字节头/解压回读）。闭环时按 §7 对拍手法（agent-core 批 4 先例：拷贝进 pi 仓跑
vitest 或逐案翻译）抽案。

---

## §2 java 侧现状（取证 2026-10-23）

| 面 | 现状 |
|---|---|
| crash-log | **全仓零命中**——无 CrashLog、无 recordCrash、无未捕获异常钩、无启动提示 |
| `/bug` | `CommandRegistry.withBuiltins()` 24 条无 bug；map/04 C-9 缺失 |
| slash 注册面 | marker 机制（`@ui:*` 纯字符串 switch，PiTuiApp.handleCommandResult:534-547）；`/fork` 空参 marker 先例（批 10） |
| overlay 先例 | `SummaryChoiceScreen`（SelectList 三选）／`CustomInstructionsScreen`（EditorComponent＋Esc/Enter）／`UserMessageSelectorScreen`；`ScreenOverlay` 接口（isDone/apply/render）＋`PiTuiApp.openOverlay` |
| flow 先例 | `TreeNavigationFlow`（多屏流转＋异步 LLM 调用＋Esc 中止，`PiTuiApp` 注入 openOverlay/dispatcher/keys） |
| loader | 无 `BorderedLoader` 对位；`StatusIndicator`（ChatScreen 指示器，非 overlay）；`TreeNavigationFlow` 的摘要等待走 `showBranchSummaryIndicator` |
| 摘要缝 | `LlmSummaryGenerator.callBranch(prompt, maxTokens, reason)`（系统提示词**硬编码** `SummaryPrompts.SYSTEM`）⇒ 需加 systemPrompt 参重载（T3b） |
| `RetryableError.isRetryableAssistantError` | agent-core 已存在（PostRunRetry 用）⇒ T2d 直接复用 |
| exportJsonl | `SessionPersistence.exportJsonl(session, target)`——无 trailing entries 缝、无「序列化为字符串」口 |
| systemPrompt/tools | `harness` 的 `Context(String systemPrompt, …)`／`AgentTool.name()/description()` 都在；AgentSession 暴露 `harness()`、`services().tools()` |
| `UuidV7` | agent-core `UuidV7` 已存在（session id 同源） |
| VERSION | `cli/Version.VERSION`（`0.1.0-SNAPSHOT`） |
| agentDir | `FileSettingsStorage.defaultAgentDir()`（`PI_CODING_AGENT_DIR`，pi config.ts:495 对位）；**批 14 教训：不得静态冻结 `user.home`**——每调现解析 |
| Settings | `SettingsManager.global()/project()`（包私有，批 11 起）；`Settings` 记录含 `deviceId`；Jackson 可序列化 |
| 扩展面 | `ExtensionManager`（discover/loadAll/unload/loadedNames）＋`InstalledExtension`（JAR 展示记录）；**无** pi 的 `Extension{path,resolvedPath,sourceInfo,hidden}` 运行时元数据，无 errors 聚合 ⇒ 裁决⑧ |
| provider 面 | `ProviderRegistry`（pi-java-ai）；无 ModelRuntime 的 `getProviderAuthStatus/isUsingOAuth/getRegisteredProviderIds` ⇒ 裁决⑧ |
| offline | `ModelCatalogRefresh.offlineMode(args, env)`（批 14 裁决④）⇒ /bug upload 门复用 |
| `/session` | 批 13 已落 Cache Warming＋Cache Re-billed（挂 Cache Warming 段尾，D68 在册）；缺 Info/Messages/Tokens/Cost 段 |
| `/compact` | `compact()` 三入口首句 `abortAndWaitForIdle()`（:1227/:1244）⇒ C-21 核销 |
| `/changelog` | 批 12 已落 bundled 读＋reverse＋join（D63 纯文本在册）⇒ C-13 核销 |
| `/model` `/thinking` | `/model` 忽略 args 恒开选择器；`/thinking` 命令不存在；`ModelThinkingLevels`（ai catalog）/`ThinkingLevel.ordered()`（6 级无 off）/`ModelThinkingLevel`（含 Off）在 |
| `/export` | 空参返回用法串；无引号解析；HTML 臂走 tmp jsonl＋`HtmlExporter` |
| `SessionShare` | gist 上传的 HttpClient 注入测试先例（`SessionShare(HttpClient, url)`）⇒ T6 同形 |
| 启动装配 | `PiTuiLauncher.runInteractive`：`setStartupChangelog` 先例（计算在 launcher、展示在 app welcome 后）⇒ 崩溃提示同形 |
| 退出面 | `runFullscreen`/`runRegular` 的 catch 已做 alt 屏恢复（`\033[?1049l\033[?25h\033[0m`）⇒ T2b 钩复用同序列 |

---

## §3 任务分解

### T1：`CrashLog`＋`CrashRecord`（coding-agent 新包 `core/crash/`，2 文件 ~210 行）

`CrashRecord.java`（record，Jackson 注面；pi §1.1 interface 对位）：

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrashRecord(
    String timestamp,            // ISO-8601（Instant.now().toString()）
    String version,              // Version.VERSION
    Kind kind,
    String message,
    @Nullable String stack,
    @Nullable String sessionFile,
    String cwd,
    @Nullable Boolean notified   // pi 的 notified?——缺席即未通知
) {
    /** pi CrashRecord["kind"] 闭集两值（纯常量 ⇒ enum，@JsonValue snake_case）。 */
    public enum Kind {
        UNCAUGHT_EXCEPTION("uncaught_exception"), FATAL_ERROR("fatal_error");
        // @JsonValue wire() …
    }
}
```

`CrashLog.java`（pi §1.1 逐函数对位；**路径每调现解析**，批 14 静态冻结教训）：

```java
public final class CrashLog {
    public static final int MAX_CRASH_RECORDS = 5;
    public static final long MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public static Path crashLogPath() {           // pi crashLogPath(agentDir=getAgentDir())
        return FileSettingsStorage.defaultAgentDir().resolve("crashes.json");
    }
    public static List<CrashRecord> read(Path path) { … }          // 读失败/非数组/缺字段 ⇒ §1.1 过滤
    public static CrashRecord record(Kind kind, Throwable error,   // pi recordCrash
            @Nullable String sessionFile, String cwd, Path path) { … }   // 全 try/catch ⇒ null
    public static CrashRecord takeUnnotified(Path path, long nowMillis) { … }  // 最新未通知≤7天＋全量标 notified 回写
    public static void clear(Path path) { … }                       // rmSync force 对位：Files.deleteIfExists＋catch 静默

    /** pi findExtensionStackMatches（裁决②降级形）：针＝扩展 JAR 文件名（去 .jar）。 */
    public static List<String> findExtensionStackMatches(@Nullable String stack,
            List<InstalledExtension> extensions) { … }
}
```

- `record`：`message`＝`error.getMessage()` 空则 `error.getClass().getSimpleName()`
  （pi `error.message || error.name` 对位）；`stack`＝`printStackTrace` 成串（null 安全）；
  追加后 `subList(size-5, size)`；写 JSON 两位缩进＋尾 `\n`（pi 逐字格式）。
- `takeUnnotified`：倒序找首条 `notified != TRUE && now - parse(timestamp) <= MAX_AGE`；
  命中后**全表**未通知项置 true 回写；回写失败静默仍返回命中条。
- **裁决②（findExtensionStackMatches）**：java 栈帧是类名非文件路径，pi 的三分支
  （packageRoot／directoryEntry／单文件）无对位。降级针＝JAR 文件名去 `.jar`
  （`InstalledExtension.of` 同名规则），`stack.toLowerCase().contains(needle.toLowerCase())`，
  label＝`name`（清单名）。命中低、零误报。窄差 D80。
- 测试：读写往返、坏 JSON/非数组/缺字段过滤、5 条截断、7 天窗（now 注入）、
  take 的全量 notified 回写、clear 静默、stack 匹配（含无 stack、去重、大小写）。

### T2：崩溃集成（coding-agent＋tui，~210 行）

**T2a 启动提示**（tui `PiTuiLauncher`＋`PiTuiApp`，pi :1220-1226 对位）：
launcher 在 `AgentSession.create` 后 `CrashLog.takeUnnotified(crashLogPath(), now)`，
命中 ⇒ 仿 `setStartupChangelog` 模式 `app.setStartupCrashNotice(text)`，welcome 后
`chatScreen.appendSystemText(text)`。文案【用户可见】逐字（`APP_NAME`＝`pi-java`，
时间戳 `DateTimeFormatter` 本地化对位 `toLocaleString()`）：

```
pi-java crashed on <when> (<message>). Run /bug to report it; the crash details are attached automatically.
```

**T2b 未捕获异常钩**（tui `PiTuiLauncher`，pi :4363-4390 对位）：
`runInteractive` 进入时安装 `Thread.setDefaultUncaughtExceptionHandler`（保留旧
handler 链尾调用？——pi 是 prepend 独占，java 亦独占，退出时恢复旧 handler）：

```java
Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
    // pi isShuttingDown 臂对位：退出中直接 exit 1
    restoreTerminal();                          // fullscreen 同 catch 块的 \033[?1049l\033[?25h\033[0m
    System.err.println("pi-java exiting due to uncaughtException:");
    error.printStackTrace(System.err);
    var hint = CrashIntegration.crashExtensionHint(error, extensionSource);  // 可空
    if (hint != null) System.err.println("\n" + hint);
    if (CrashLog.record(Kind.UNCAUGHT_EXCEPTION, error, sessionFile, cwd, crashLogPath()) != null) {
        System.err.println("\n" + CrashIntegration.crashReportInstructions(sessionFile));
    }
    System.exit(1);
});
```

- `killTrackedDetachedChildren` 无对位（bash 子进程归 JVM 钩）⇒ 窄差 D87 注记。
- `isDeadTerminalError`/`emergencyTerminalExit` 不做（裁决③，D81）。
- handler 需读 `sessionFile/cwd/扩展表`——launcher 装配闭包捕获 session；
  `AgentSession` 加 `cwd()` 口（`sessionManager.getCwd()` 对位；若现无则 +
  `services()`/persistence 取，实现时钉）。

**T2c fatal_error 生产点：/import 修真**（**裁决⑤**，coding-agent `MiscCommands`
＋ tui `PiTuiApp`）：pi `/import` 失败＝fatal（:6607 → handleFatalRuntimeError）。
java 现返回 `"Import failed: …"` 继续会话——与 pi 语义相悖且 C-7 标「对齐」是账面
错（新登 B256，本批即销）。修真：catch 臂

```java
} catch (Exception e) {
    CrashLog.record(Kind.FATAL_ERROR, e, ctx.session().sessionFile(), cwd, crashLogPath());
    return CompletableFuture.completedFuture("@ui:fatal-exit:Failed to import session: " + e.getMessage());
}
```

PiTuiApp `handleCommandResult` 加 `startsWith("@ui:fatal-exit:")` 臂：showError
（去前缀）→ `appendSystemText(crashReportInstructions)` → 停 TUI exit 1
（`exit()` 现正常退出码 0——加 `exitFatal()` 缝或在 marker 臂直接
`System.exit(1)` 前跑 alt 屏恢复；实现时钉最简形）。

**T2d `/bug` 建议**（tui，pi :2168-2190＋:3586 对位）：`ChatScreen.onEntry` 的
assistant message 收尾处（与 cache-miss 通知同段，批 13 已有 miss 通知点）加：

```java
// maybeSuggestBugReport(message)：stopReason==ERROR && !RetryableError.isRetryableAssistantError(m)
//   && !ABORT_CANCEL_PATTERN.matcher(errorMessage or "").find()
//   && !bugReportHintShown（会话级一次，PiTuiApp 持有旗）
// ⇒ appendSystemText("If this looks like a pi-java bug, /bug sends a report to the developers.")
```

正则逐字：`\b(?:abort(?:ed)?|cancel(?:l?ed)?)\b`（i）。R29 门恒 false（裁决④）。
`RetryableError` 在 agent-core，tui 依赖 coding-agent⇒agent 已有，直用。

**T2e 文案件**（coding-agent 新类 `core/crash/CrashIntegration`，~60 行）：
`crashReportInstructions(sessionFile)`（pi :2160-2162 逐字：`run \`pi-java -r\` to
resume the session, then` ｜ 无 sessionFile ⇒ `start pi and` ⇒ java `start pi-java and`）
＋`formatCrashExtensionHint(matches)`（pi :304-319 逐字，`pi config`→`pi-java config`、
`pi -ne`→`pi-java -ne`）。

测试：钩的 record+输出序（fake sessionFile/cwd）、fatal marker 臂、建议门四臂
（可重试/abort 词/已示过/正常错误⇒示一次）、文案逐字。

### T3：`BugReports` 核心（coding-agent 新包 `core/bugreport/`，~340 行）

`BugReport.java`（pi §1.3 逐函数对位；Jackson 树＋`ObjectNode` 组装，键序保持 pi
声明序——`ObjectMapper.getNodeFactory()` 默认 LinkedHashMap 保序 ✓）：

```java
public final class BugReport {
    public static final String CUSTOM_ENTRY_TYPE = "pi.bug-report";
    static final int SCHEMA_VERSION = 1;
    static final String REDACTED = "<redacted>";
    // pi SENSITIVE_KEY 逐字（Java Pattern，(?:^|[-_])(api[-_]?key|secret|token|password|passwd|credential|authorization|cookie)(?:$|[-_])，CASE_INSENSITIVE）
    public static boolean isSensitiveKey(String key) { … }          // camelCase→snake 先置（逐字正则）
    public static String redactUrl(String value) { … }              // 裁决⑨手写解析
    public static JsonNode redactJsonValue(JsonNode value) { … }    // 对象键脱敏＋字符串值 redactUrl 递归
    public static ObjectNode collectMetadata(CollectOptions o) { … }
    public static ObjectNode collectDiagnostics(List<Entry> entries, String sessionId,
            List<CrashRecord> crashes) { … }
    public record Bundle(ObjectNode metadata, ObjectNode diagnostics,
            @Nullable String sessionJsonl, @Nullable String summary) {}
    public record ReportFile(String name, String contentType, String data) {}
    public static List<ReportFile> files(Bundle bundle) { … }       // report/diagnostics 2 空格缩进+尾\n；summary 补尾\n
    public static String archiveFileName(String id) { return "pi-bug-report-" + id + ".zip"; }
    // BUG_SUMMARY_SYSTEM_PROMPT / BUG_SUMMARY_INSTRUCTIONS【模型可见】逐字常量
    static List<Message> selectMessages(List<Message> messages, long tokenBudget) { … }  // ContextUsageEstimator.estimateTokens
}
```

- **裁决⑨（redactUrl）**：`java.net.URI` 不可变且无 query 改写 ⇒ 手写串解析：
  嵌套 scheme 正则臂逐字；`scheme://[user[:pass]@]host[:port][/path][?query][#frag]`
  分组解析——userinfo 非空 ⇒ 剥；query 按 `&` 切、键 URL-decode 后过
  `isSensitiveKey` ⇒ 值换 `<redacted>`；任一改动 ⇒ 重组；解析不合法 ⇒ 原样返回。
- `redactSettings`：`Json.valueToTree(settings)` → `remove("deviceId")` →
  `redactJsonValue`（java Settings 无 trackingId 字段）。
- `collectMetadata`：`id`＝`UuidV7`；`environment` 节：runtime＝`java/<version>`、
  platform＝`os.name`、arch＝`os.arch`、osRelease＝`os.version`、osVersion⇒null、
  shell＝`SHELL`/`ComSpec` 尾段、terminal 四 env＋tmux/ssh/ci、`PI_` 键名排序
  （**键名不带值**，pi 同）；userAgent＝`pi-java/<version> (<os.name>; java/<ver>; <arch>)`
  （pi `getPiUserAgent` 形，品牌差随 D77 族）。
- `describeModel`：`ModelInfo` 映射——provider/id（`ModelId`）、name⇐`displayName`、
  api、baseUrl（redact）、reasoning⇐`capabilities` 含 REASONING、contextWindow⇐
  `maxInputTokens`、maxTokens⇐`maxOutputTokens`、samplingParams/compat/thinkingLevelMap
  过 `redactJsonValue`、headerNames⇐`headers` 键排序；`input` 无对位 ⇒ 裁决⑧省
  字段（D82）。
- `describeProvider`：id/name/baseUrl/headerNames 从 `ProviderRegistry` 可得；
  `authTypes` 从凭证存储（`FileCredentialStore` 有 api_key ⇒ `"api_key"`；OAuth 凭证
  ⇒ `"oauth"`）；`authStatus/usingOAuth/registeredByExtension` 无面 ⇒ 裁决⑧省字段
  （D82）。
- `describeExtension`：`ExtensionManager` + `InstalledExtension`——path（JAR 路径
  串）、source⇐JAR 路径（redactUrl）、scope/origin⇐`SourceInfo`（现恒
  temporary/top-level）、hidden⇐false；`extensionErrors`：java 无聚合 ⇒ 空表
  （D82 注记）。
- `collectDiagnostics`：`accumulatedEntries()` 逐条——`Entry.Message` 且 role
  assistant ⇒ 计数＋失败臂收集（diagnostics 非空｜stopReason ERROR/ABORTED｜
  errorMessage 非空），字段条件省略（rawStopReason/errorMessage 缺席不产键，
  `ObjectNode` 条件 put 对位）；crashes 剥 `notified` 键。
- 测试：isSensitiveKey 边界表（apiKey/API-KEY/x_token/count/monkey 不误伤）、
  redactUrl 六案（userinfo、敏感 query、嵌套 scheme、非法 URL 原样、无改动原样、
  相对串原样）、redactJsonValue 嵌套、metadata 形状（includeSession 两臂的 cwd 键
  有无）、diagnostics 过滤臂、files 尾换行、selectMessages 预算（首条必进/超预算停）。

### T3b：摘要缝（agent-core `LlmSummaryGenerator` +~18 行；coding-agent `BugReportSummarizer` ~90 行）

`LlmSummaryGenerator` 加 systemPrompt 参重载（旧三参委托，行为不变）：

```java
/** pi completeSummarization 的 systemPrompt 参化缝（bug 摘要用自己的系统提示词）。 */
public Message.AssistantMessage callBranch(String promptText, int maxTokens, String reason,
        String systemPrompt) { return callWithRetries(() -> request(promptText,
        OptionalInt.of(maxTokens), systemPrompt), reason); }
public Message.AssistantMessage callBranch(String promptText, int maxTokens, String reason) {
    return callBranch(promptText, maxTokens, reason, SummaryPrompts.SYSTEM);
}
// request(...) 的 Context 首参由常量改为参（private 签名加 systemPrompt）
```

coding-agent `core/bugreport/BugReportSummarizer.java`（pi §1.3
`generateBugReportSummary`＋`agent-session.ts:4322` 对位）：

```java
public final class BugReportSummarizer {
    public static String summarize(AgentSession session, @Nullable String hint,
            BooleanSupplier aborted) {
        var model = session.harness().getModel();
        if (model == null) throw new IllegalStateException("No model selected");
        // contextWindow：目录 ModelInfo.maxInputTokens > 0 ? 它 : 128_000（pi 逐字形）
        // selectMessages(accumulatedMessages(), floor(contextWindow * 0.6))
        // prompt 四段拼装（截断注记/<conversation>/…/<user-report>/INSTRUCTIONS，\n\n 连）
        // maxTokens = min(4096, maxOutputTokens > 0 ? maxOutputTokens : ∞)
        // reasoning 门：模型支持 && 当前级非 off ⇒ 透传（缝内 reasoningOption 已含，免重复）
        var response = session 摘要生成器.callBranch(prompt, maxTokens, "bugReport",
                BugReport.BUG_SUMMARY_SYSTEM_PROMPT);
        if (response.stopReason() == StopReason.ABORTED)
            throw new IllegalStateException("Bug report summary was cancelled");
        var failure = LlmSummaryGenerator.summarizationFailure(response, "Bug report summary");
        if (failure != null) throw new IllegalStateException(failure);
        // toolCall 守卫 / 空文本守卫（trim 后）逐字
        return text.trim();
    }
}
```

- `AgentSession` 需暴露/装配一个带会话 streamFn＋retry 的 `LlmSummaryGenerator`
  （压缩装配已有同款——复用其字段或加 `summarizeForBugReport(hint, aborted)` 会话
  方法直接藏缝，实现时钉最薄形）。reason＝`"bugReport"`（retry 事件标签）。
- pi 的 auth 参（apiKey/headers/env）在 java 由 streamFn 车道自取（ProviderRouting
  已修真）⇒ 无额外参。
- 测试：fake streamFn 四守卫各臂、prompt 拼装（截断注记行有无、hint 包裹、
  conversation 包裹）、maxTokens 两臂、无模型抛 `No model selected`。

### T4：`SessionBranchSerializer`（coding-agent `core/`，~90 行）

pi `serializeSessionBranch`＋`createShareTrailingEntries` 对位：

```java
public final class SessionBranchSerializer {
    /** pi session-export.ts:9-31：header + branch 逐条 parentId 重链 + trailing + 尾 \n。 */
    public static String serialize(AgentSession session) {
        // header：type=session / version=CURRENT_SESSION_VERSION / id / timestamp / cwd
        // branch：SessionPersistence 现有 branch 序列化（逐条 ObjectNode put("parentId", prev)）
        // trailing：createShareTrailingEntries —— custom 条目：
        //   { type:"custom", customType:"pi.share", id:<8位随机>, parentId, timestamp,
        //     data:{ systemPrompt, tools:[{name, description, parameters}] } }
    }
}
```

- `SessionPersistence.exportJsonl` 复用其 header/entry 序列化件（抽 package-private
  共享或同类重写，实现时钉；**行为不变**）。
- systemPrompt：`session.harness()` 快照取（`Context.systemPrompt`）；tools：
  `services().tools()` 注册表 name/description/parameters——parameters 取工具的
  JSON schema 面（`AgentTool` 若无 schema 访问器 ⇒ `ObjectMapper.valueToTree(params
  Class)` 或空对象，实现时钉并注记 D82）。
- id 8 位：`UUID.randomUUID().toString().substring(0, 8)`（pi `randomUUID().slice(0,8)`）。
- 测试：header 键序、parentId 重链（首条 null、逐条前驱）、trailing 形状
  （customType/id 长 8/data 三键）、尾 `\n`、与 `exportJsonl` 同会话前缀一致。

### T5：`ZipArchives`（coding-agent `core/util/`，~45 行；**裁决①**）

```java
public final class ZipArchives {
    /** 写经典 ZIP（JDK ZipOutputStream，DEFLATED）。pi utils/zip.ts 语义对位，字节差 D79。 */
    public static void write(Path file, List<BugReport.ReportFile> entries) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.data().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}
```

测试：`ZipInputStream` 回读四名四文（report/diagnostics/session/summary 两臂）。

### T6：`BugReportUpload`（coding-agent `core/bugreport/`，~100 行）

pi §1.4 对位（`SessionShare` 的注入先例）：

```java
public final class BugReportUpload {
    static final String DEFAULT_GATEWAY = "https://radius.pi.dev";   // pi-ai radius-config.ts:4
    public static String gatewayUrl() {                               // getRadiusGatewayUrl 对位
        var env = System.getenv("PI_RADIUS_GATEWAY");                 // 每调现读（批 14 教训）
        return normalize(env != null && !env.isBlank() ? env : DEFAULT_GATEWAY);  // 去尾 /
    }
    public record Result(String id) {}
    private final HttpClient http; private final String gateway;      // 测试注入（SessionShare 先例）
    public Result upload(BugReport.Bundle bundle, @Nullable String token) throws IOException {
        // multipart/form-data 手卷 BodyPublisher：边界随机；每 file 一段
        //   Content-Disposition: form-data; name="<name>"; filename="<name>"
        //   Content-Type: <contentType>
        // Authorization: Bearer <token> 仅 token 非空（裁决⑩ 现恒 null）
        // 响应：2xx && json.ok==true && bug_report.id 是串 ⇒ Result
        // 否则 throw IOException("Bug report upload failed: " + (description||error||statusText||status))  // 逐字
    }
}
```

- `signal` 对位：上传在 flow 的 worker 线程跑，中止＝线程中断（`InterruptedException`
  ⇒ IOException 包装，flow 判 aborted 旗——`TreeNavigationFlow` 同手法）。
- 测试：fake HttpClient（status 200 ok/缺 id/500/非 JSON 体四案＋multipart 体形状
  断言＋token 头有无两案）；gateway env 归一。

### T7：`/bug` TUI 流（tui 4 新文件 ~620 行＋coding-agent/tui 接线 ~40 行）

**命令注册**（`MiscCommands`；pi `slash-commands.ts:28` 逐字）：

```java
registry.register(simple("bug", "Report a bug to the Pi developers", "<description>",
    (args, ctx) -> CommandRegistry.UI_BUG_REPORT));   // 新 marker 常量 "@ui:bug-report"
```

**hint 传递（裁决⑦）**：marker 无载荷；`PiTuiApp.handleCommandResult` 的
`case UI_BUG_REPORT` 从原始输入行切 hint（`submit()` 把 text 传入
`handleCommandResult(result, text)`，pi :3242-3245 对位：`text.length()>4 ?
text.substring(4).trim() : null`，空串 ⇒ null）。

**`BugReportFlow`**（仿 `TreeNavigationFlow` 构造注入：session/chatScreen/
dispatcher/openOverlay/keys；pi §1.6 `reportBug` 逐段对位）：

```
begin(hint)
 → input("Report a bug", DISCLAIMER + "\n\nWhat went wrong? (optional)", hint)   [Esc ⇒ cancelled]
 → choose("Include the session transcript?", [Yes, include the transcript / No], TRANSCRIPT_NOTE)
 → (No 臂) choose("Attach a summary written by <model> instead?", [Yes, generate a summary / No], 说明文)
 → choose("Bug report", [Upload Report / Export as Zip / Cancel], 三段汇总说明)
 → includeSummary ⇒ loader("Writing summary with <model>...") + worker 线程 BugReportSummarizer
       [Esc ⇒ aborted 旗；catch 臂 aborted⇒cancelled 文案 / 否则 "Failed to write bug report summary: <e>"]
 → buildBundle（T3 全件；readCrashLog 进 diagnostics.crashes）
 → upload 臂：PI_OFFLINE/offlineMode 门前检【逐字】"Uploading bug reports requires online mode. Use Export as Zip instead."
       → loader("Uploading bug report...") → 成功："Bug report uploaded. Report ID: <id>"
       → 失败（非中止）⇒ choose("Upload failed", [Export as Zip / Cancel], failure+"\n\nExport…instead?")
 → exportZip：cwd/pi-bug-report-<id>.zip ⇒ "Bug report exported to: <path>\nReport ID: <id>"
       （写失败 ⇒ "Failed to write bug report: <e>"）
 → recordInSession：appendCustomEntry("pi.bug-report", {id, createdAt, hint, sessionIncluded,
       summaryIncluded, delivery[, path]})；diagnostics.crashes 非空 ⇒ CrashLog.clear
 → 任一取消 ⇒ "Bug report cancelled"
```

**三屏**（全部文案【用户可见】逐字）：
- `BugReportInputScreen`（~150 行）：`CustomInstructionsScreen` 同构＋**描述区**
  （DISCLAIMER 多行，渲染在标题下、编辑框上）＋**初始值**（EditorComponent 预置
  text，需加 setter——`ChatScreen.setEditorText` 有先例，组件级加 `setText`）。
  外部编辑器缝不做（pi ExtensionEditorComponent 带；java 组件级无——D84 同族注记）。
- `BugReportChoiceScreen`（~120 行）：`SummaryChoiceScreen` 同构＋描述区（多行
  muted 文本）；Esc ⇒ null。
- `BugReportLoaderScreen`（~130 行）：BorderedLoader 弱形状——边框文＋消息＋
  `StatusIndicator` 旋轉件复用（或无 spinner 纯文本，窄差注记）；Esc ⇒ aborted
  旗置位＋worker 线程 interrupt；flow 轮 aborted 旗收尾。
- flow 的 overlay 序列：`openOverlay` 一屏一换（pi showOverlay/restoreEditor 对位）。

测试（flow 驱动假 overlay——`TreeNavigationFlowTest` 同手法）：全取消臂 5 案、
transcript No→summary Yes→zip 全链（zip 落 tmp 目录、report.json 三键、
appendCustomEntry 载荷、crashes 清空）、upload 成功/失败→fallback zip/失败→cancel、
offline 门、summary 失败两臂、hint 预填进 metadata。

### T8：`/thinking`（C-4；coding-agent `ThinkingCommands` 新件 ~60 行＋tui `ThinkingLevelScreen` ~120 行）

```java
// dispatch：/thinking [level]（ModelCommands 同包新件 ThinkingCommands.registerAll）
if (args.isBlank()) → return UI_THINKING_SELECTOR（新 marker "@ui:thinking-selector"）
var available = ThinkingCommands.availableLevels(session);   // pi getAvailableThinkingLevels 对位：
   //   model 缺 ⇒ 七值全集 [off,minimal,low,medium,high,xhigh,max]（THINKING_LEVEL_OPTIONS 逐字序）
   //   否则按 ModelThinkingLevels 目录映射过滤（非 reasoning ⇒ ["off"]；map null 剔除；
   //   xhigh/max 要映射有定义——pi models.ts:1224-1233）
var normalized = args.trim().toLowerCase(Locale.ROOT);
var hit = available.stream().filter(l -> l.equalsIgnoreCase(normalized)).findFirst();
if (hit.isEmpty()) → "Unknown thinking level \"<arg>\". Available levels: <join ", ">"."   【逐字】
session.setThinkingLevel(<ModelThinkingLevel 解析>);  → "Thinking level: <level>"             【逐字】
```

- **裁决⑥**：空参臂开 `ThinkingLevelScreen`——`SelectList` 简版（当前级预选、
  Enter ⇒ `setThinkingLevel`＋`Thinking level: <level>`、Esc 取消）；pi 组件的
  persist 第二键（存默认）与 DEFAULT 标记属 S8 域不做 ⇒ 窄差 D84。S8 行不动。
- 级别解析：`ModelThinkingLevel`（含 Off）的 label⇔实例映射（`ThinkingLevels.parse`
  已有，复用）。
- `availableLevels` 放 coding-agent core（RPC `availableThinkingLevels()` 现返全集——
  可对拍后收编，不强制）。
- 测试：七值全集臂（无模型）、off 臂（非 reasoning）、映射过滤（null 剔除、
  xhigh 未映射剔除）、大小写不敏感命中、未知级文案、空参 marker。

### T9：`/model <ref>` 直设臂（C-2；coding-agent `ModelExactMatch` ~70 行＋`ModelCommands` 改写）

`ModelExactMatch`（pi `model-resolver.ts:88-130` 逐字对位）：

```java
public static ModelInfo findExact(String ref, List<ModelInfo> available) {
    // trim⇒空 ⇒ null；全小写 "provider/id" 全形唯一 ⇒ 中（多命中 ⇒ null）
    // 含 "/" 拆两侧 trim 精确（小写）唯一 ⇒ 中；裸 id 唯一 ⇒ 中；否则 null
}
```

`/model` 命令：空参 ⇒ `UI_MODEL_SELECTOR`（现状）；有参 ⇒ 候选集（
`scopedModels()` 非空 ⇒ scoped 的模型集，**未命中也直接开选择器**〔pi :5220〕；
否则可用快照 `ModelsJsonConfig.allModels().listModels()`）⇒ 命中：
`session.setModel(ModelId.of(...))` ⇒ `"Model: <id>"` ⇒ 订阅 auth 警告缝
（PiTuiApp `maybeWarnSubscriptionAuth` 已有，命令结果后挂——/login 先例 :515-522）；
未命中（无 scope）⇒ 刷新臂：`CatalogRefreshCoordinator`（批落的刷新面）带 15s
超时刷新后重找，警告文案【逐字】§1.8 尾；仍未 ⇒ `UI_MODEL_SELECTOR`。
- selector 预选缝：java `ModelSelectorScreen` 无 searchTerm 参 ⇒ 未命中开裸选择器，
  窄差 D86（pi 带搜索词预选）。
- 测试：全形/拆形/裸 id/歧义（两 provider 同 id ⇒ null）、空串、scope 臂收口、
  命中 setModel＋文案。

### T10：`/export` 默认臂＋引号解析（C-6；`MiscCommands` 改写 ~45 行）

`getPathCommandArgument` 逐字（pi :6534-6559：命令精确相等 ⇒ 缺省；`"/export "`
前缀否则 ⇒ 缺省；trimStart 空 ⇒ 缺省；首字符引号 ⇒ 配对引号内（无配对 ⇒ 缺省）；
否则到首个空白）：

```java
registry.register(async("export", "Export session (HTML default, or specify path: .html/.jsonl)", "[path]",
    (args, ctx) -> {
        var path = pathCommandArgument(args);            // null ⇒ 默认臂
        if (path != null && path.endsWith(".jsonl")) { ctx.session().exportJsonl(Path.of(path)); }
        else {
            var target = path != null ? Path.of(path)
                : Path.of("session-" + Instant.now().toString().replace(":", "-").replace(".", "-") + ".html");
            // HTML 臂走 tmp jsonl + HtmlExporter（现状搬）
        }
        return "Session exported to: " + out;             // pi :6527/:6531【逐字】（旧 "Exported session to" 废）
    }));
```

- 描述改 pi 逐字（`slash-commands.ts:25`）；失败 `Failed to export session: <msg>`。
- 测试：引号两臂（配对/无配对）、空白截断、缺省默认名（正则 `session-.*\.html`）、
  `.jsonl` 臂、文案。

### T11：`/session` 全段（C-12；coding-agent `SessionStats`＋`UsageCostBreakdown` ~150 行＋`SessionCommands` 重排）

`SessionStats`（pi `getSessionStats` :4185-4230 对位；数据源 `accumulatedEntries()`）：

```java
public record SessionStats(@Nullable String sessionFile, String sessionId,
        long totalMessages, long userMessages, long assistantMessages,
        long toolCalls, long toolResults, UsageTokens tokens, double cost) {
    public record UsageTokens(double input, double output, double cacheRead,
            double cacheWrite, double total) {}
    public static SessionStats of(AgentSession s) {
        // 逐条：Entry.Usage ⇒ 加总；branch_summary/compaction 带 usage ⇒ 加总；
        // message：user⇒userMessages；toolResult⇒toolResults(+usage 若带)；
        // assistant⇒assistantMessages + content ToolUse 计数 + usage 加总
    }
}
```

`UsageCostBreakdown`（pi `usage-totals.ts:61-96` 对位）：assistant ⇒
`provider/responseModel ?? model`（java `AssistantMessage.responseModel` 批 5 已落 ✓）、
usage 条目 ⇒ `provider/model`、toolResult/branch_summary/compaction ⇒
`Tools/summaries`；滤零桶；cost 降序。tokens＝四件之和。

`SessionCommands.sessionInfo` 重排 pi 段序（批 13 段搬位，D68 销号）：

```
Session Info\n\n[Name: x\n]File: <file|In-memory>\nID: <id>\n
\nMessages\nTotal/User/Assistant/Tools: n calls, m results
\nTokens\nInput: <input+cacheRead+cacheWrite 千分位>
  [Cached: <n> (x.x%)\n  Uncached: <n>[ (m written to cache)]\n]   // promptTokens>0 且有缓存活动
Output: n\nTotal: n
\nCache Warming\n…（批 13 段原样）
[\nCost\nTotal: $x.xxx[  \n  <key>: $x.xxx (<formatTokens> tokens)…][\n  Cache Re-billed: …]]
```

- 千分位 `String.format(Locale.US, "%,d", …)`（pi `toLocaleString()` 对位）；
  命中率 `%.1f%%`；金额 `%.3f`（`fmt3` 已有）；`formatTokens` 五档（§1.8）。
- Cost 段门：`cost > 0 || missedTokens > 0`；breakdown 行门：多桶｜单桶非当前
  `provider/model` 键。Cache Re-billed 从 Cache Warming 段尾**搬入 Cost 段**。
- 测试：空会话（无 Tokens 缓存行/无 Cost 段）、纯 usage 聚合、assistant usage、
  缓存行命中率、breakdown 分组（responseModel 优先/Tools 桶/降序/滤零）、
  Re-billed 搬位断言。

### T12：核销两翻（C-13／C-21，零代码＋核验测试）

- **C-13 `/changelog`**：批 12 已落 bundled 读＋parse＋reverse＋join＋`What's New`
  （`MiscCommands.java:115-127`＋`ChangelogSupport`）；与 pi 差仅 Markdown 渲染
  （D63 在册）。补一条命令级测试钉形状（头＋倒序拼接）⇒ 翻对齐。
- **C-21 `/compact`**：`AgentSession.compact` 三入口首句 `abortAndWaitForIdle()`
  （pi `compact()` 首句 `await this.abort()` 对位；agent-core 批 1 #17）。补一条
  「运行中 /compact ⇒ 先 abort」的序列测试 ⇒ 翻对齐。

---

## §4 不做项与重排（docs/20 §3 联动）

| 项 | 去向 | 理由 |
|---|---|---|
| C-23 `/reload`（w1 存疑）＋B246 reload 臂 | 批 16（docs/20 §3 序 15，E5 扩展 API 面第一批） | pi `reload()`（agent-session.ts:3660-3697）＝扩展运行时重建（runner invalidate＋`session_shutdown/start{reload}`＋`resetApiProviders`＋资源重扫＋defaultTools diff 激活），依附扩展运行时面；java 扩展是 SPI 静态注册，重建面是 E5 批的前置。本批做只能造半截面。B246 触发条件（出现 reload 面）随该批达成 |
| C-1 `/settings`（995 行选择器）、C-5 `/scoped-models`（401 行）、C-8 `/share` Radius、C-18 `/login` Radius/OAuth | docs/20 §3.3 变量③（S15/S7/S17 屏域）／后续批 | 选择器与 Radius 子系统，非「存疑修」量级 |
| R29 安装变更警告 | 裁决④挂起，docs/20 §3 序 21（自更新）注记 | java 无 package.json 安装形态与自更新通道 |
| S8 thinking 选择器完整组件 | 变量③ | T8 只做简版（裁决⑥，D84） |
| `detectInstallChange`／`formatResumeCommand`／`maybeShowInstallChangeWarning` 三件套 | 随 R29 | 无生产点 |
| E8-6 WSL 工具 | 不做 | java 无 WSL 调用面（zip 已随 T5 收编本批；E8-6 余 wsl 单列注记） |

---

## §5 窄差登记（接续 D78；台账格式【场景/决定/风险】随闭环入 docs/04）

| # | 窄差 | 源 |
|---|---|---|
| D79 | ZIP 字节格式差：JDK `ZipOutputStream` 产（UTF-8 flag、JDK 时间戳语义、可带 data descriptor）vs pi 手卷经典 ZIP——可解压等价，字节不可比 | 裁决① |
| D80 | 扩展栈匹配降级：针＝JAR 文件名（去 .jar）contains；pi 是文件路径针＋packageRoot/directoryEntry/index 三分支。命中低、零误报 | 裁决② |
| D81 | 死终端（EIO/EPIPE/ENOTCONN/ENOTTY）静默 exit 129 无 JVM 对位：JVM 不暴露 tty errno；java 终端死亡走普通异常路径 | 裁决③ |
| D82 | bug report JSON schema 差：provider 节缺 `authStatus/usingOAuth/registeredByExtension`、model 节缺 `input`、tools 的 `parameters` 形状、extensionErrors 恒空——java 无 ModelRuntime 聚合面/扩展运行时元数据 | 裁决⑧ |
| D83 | warning 无色面：崩溃启动提示／`/bug` 建议走 `ChatMessage.System`（dim），pi 是 warning 色 ThemedText | T2a/T2d |
| D84 | `/thinking` 空参选择器是 SelectList 简版：无 persist 第二键（存默认）、无 DEFAULT 级标记（pi `ThinkingSelectorComponent`，S8 域） | 裁决⑥ |
| D85 | bug 上传恒匿名：java 无 Radius provider/登录面，token 缝预留不接线 | 裁决⑩ |
| D86 | `/model` 未命中开裸选择器：java `ModelSelectorScreen` 无搜索词预选缝（pi `showModelSelector(searchTerm)`） | T9 |
| D87 | 崩溃钩无 `killTrackedDetachedChildren` 对位：java bash 子进程清理由 JVM/工具层各自管 | T2b |
| D88 | `/bug` 输入屏无外部编辑器缝（pi `ExtensionEditorComponent` 带 settings 外部编辑器命令；java 组件级无） | T7 |

---

## §6 裁决

**裁决①：ZIP 用 JDK 标准库，不手卷字节格式。**
> 【场景】pi `utils/zip.ts` 手卷 71 行经典 ZIP（local/central/end 三段、deflateRaw、
> crc32、DOS 时间）。java 侧手卷等价物是纯体力活且易错。
> 【决定】`ZipArchives` 用 `java.util.zip.ZipOutputStream`（DEFLATED，UTF-8 名）。
> 【风险/代价】字节级不可比（时间戳、flag 位、descriptor）——上报包的消费方是
> 解压器不是字节比较器，[内部，模型不可见]；登记 D79。

**裁决②：扩展栈匹配降级为 JAR 文件名针。**
> 【场景】pi `findExtensionStackMatches` 按扩展**文件路径**在栈文本里做三段匹配；
> java 扩展是 JAR SPI，栈帧是类名（`com.foo.ext.Bar.baz`），不含 JAR 路径。
> 【决定】针＝JAR 文件名去 `.jar`（与 `InstalledExtension.of` 命名同规则），栈文本
> 小写 contains；label＝清单 name。pi 的 packageRoot/directoryEntry 分支不移植。
> 【风险/代价】命中率低（栈帧含 jar 名的概率小）但零误报，提示本是 best-effort；
> [用户可见]提示文案本身逐字不动；登记 D80。

**裁决③：死终端面不做。**
> 【场景】pi 监听 stdin/stdout/stderr 三流 error，EIO/EPIPE/ENOTCONN/ENOTTY ⇒
> `emergencyTerminalExit`（exit 129，不记 crash）。JVM 的 tty IO 失败只抛
> 通用 `IOException`/`Error`，无 errno。
> 【决定】不移植；`uncaughtCrash` 钩对一切未捕获异常生效。
> 【风险/代价】[边缘场景] SSH 断线时 java 会按普通崩溃记 crash（pi 不记）——
> 多一条 crash 记录无害（启动提示一次即 notified）；登记 D81。

**裁决④：R29 安装变更警告挂起。**
> 【场景】pi `detectInstallChange` 重读启动时的 package.json 判「运行中被更新/删除」，
> 并门 `suggestBugReport`（已示安装警告则不示 /bug 建议）。
> 【决定】java 无 package.json 安装形态（jar/classes 运行）也无自更新通道
> （docs/20 §3 序 21），三件套不做；`maybeSuggestBugReport` 的 install-change 门
> 恒 false（≡ pi 的 `detectInstallChange()` 恒 undefined 臂）。R29 行挂起到自更新批，
> docs/20 序 21 加注记。
> 【风险/代价】无影响——该门在 pi 也几乎恒 false（安装变更稀少）；[内部]。

**裁决⑤：/import 失败修真为 pi fatal 形（行为变化）。**
> 【场景】pi `/import` 失败 ⇒ showError＋`recordCrash("fatal_error")`＋停 TUI
> `exit(1)`（:6607→:2113）；java 现返回 `"Import failed: …"` 继续会话。map/04
> C-7 标「对齐」是账面错（fatal 臂未见）。
> 【决定】修真：catch 臂 `recordCrash(FATAL_ERROR)`＋`@ui:fatal-exit:<msg>` marker，
> PiTuiApp 打错误＋崩溃指引＋exit 1。新登 **B256** 本批即销（B255/B239 先例）。
> 同时给 `fatal_error` 一个真实生产点（否则 T1 的 kind 半身是死码）。
> 【风险/代价】[用户可见]行为变化：import 失败从「报错继续」变「进程退出」——
> 这是 pi 语义本身；[边缘场景] 误退出的代价由 crash 记录与 `-r` 恢复覆盖。

**裁决⑥：/thinking 空参臂做 SelectList 简版选择器。**
> 【场景】pi 空参开 `ThinkingSelectorComponent`（含 persist 第二键与 DEFAULT 标记，
> map/05 S8，401 行级组件，不在本批）。
> 【决定】`ThinkingLevelScreen`：SelectList＋当前级预选＋Enter 即设（session 级，
> persist=false）＋Esc 取消。S8 行不动。
> 【风险/代价】[用户可见]少「存默认」入口——settings 页已有 per-model 默认面
> （批 12 T14）；登记 D84。

**裁决⑦：marker 不携载荷，hint 由 PiTuiApp 从原始输入行切。**
> 【场景】`/bug <description>` 的 hint 要过 slash→TUI 边界；java marker 是纯字符串
> switch，`SlashContext` 是 record（加字段牵动全构造点）。
> 【决定】命令只回 `@ui:bug-report`；`PiTuiApp.submit()` 本持有原始 text，
> `handleCommandResult(result, text)` 加参，case 臂内 `text.substring(4).trim()`
> （pi :3242-3245 对位）。
> 【风险/代价】无影响；marker 机制不动。

**裁决⑧：bug report 的 provider/model/extension 节给可得子集，缺字段省略。**
> 【场景】pi 的 `describeProvider` 依赖 ModelRuntime 三面（authStatus/usingOAuth/
> registeredByExtension）、`describeModel.input` 依赖模态数组、`extensionErrors`
> 依赖加载错误聚合——java 均无对位面。
> 【决定】JSON 里**省略**不可得键（不造恒值）；可得面全给（id/name/baseUrl 脱敏/
> headerNames/authTypes 从凭证存储）。tools 的 `parameters` 尽力给 schema，
> 无访问器则空对象。
> 【风险/代价】[用户可见]上报 JSON schema 差——消费方是开发者人工读，字段缺席
> 语义明确；登记 D82。为凑 schema 造假值更糟。

**裁决⑨：redactUrl 手写串解析。**
> 【场景】pi 用 WHATWG `URL`（可变 username/password/searchParams）；`java.net.URI`
> 不可变、`java.net.URL` 不支持任意 scheme 的 query 改写。
> 【决定】按 pi 语义手写：嵌套 scheme 正则臂逐字；userinfo/query 分组解析改写重组；
> 解析失败原样返回。
> 【风险/代价】[边缘场景]非标准 URL 的解析边界与 WHATWG 有微差——脱敏宁宽勿窄
> （敏感键命中即改值），测试钉六案。

**裁决⑩：upload token 恒匿名，缝预留。**
> 【场景】pi 从 Radius provider 取 OAuth token 归因上报；java 无 Radius provider。
> 【决定】`upload(bundle, null)`；`BugReportUpload` 保留 token 参（`Authorization:
> Bearer` 臂有测试），Radius 登录面落地时接线。
> 【风险/代价】无影响——pi 匿名上传本合法；登记 D85。

**裁决⑪：警告文案走 dim 系统文本，不新增 Warning 色件。**
> 【场景】pi 崩溃提示/`/bug` 建议用 warning/muted 色 ThemedText；java ChatMessage
> 只有 Error（红）/System（dim）。
> 【决定】复用 `appendSystemText`（GENERIC dim）；不为一处提示加色件。
> 【风险/代价】[用户可见]色彩差，文案逐字不动；登记 D83。

**裁决⑫：CrashLog 路径与环境每调现解析。**
> 【场景】批 14 抓回的真修：auth 三存储静态冻结 `user.home` 造套件性红。
> 【决定】`crashLogPath()`/`gatewayUrl()`/环境读取全部每调现求值，禁静态缓存；
> 测试经 `PI_CODING_AGENT_DIR`/`PI_RADIUS_GATEWAY` 注入临时 home。
> 【风险/代价】无影响；防回归。

---

## §7 测试计划

| 层 | 套件 | 案 |
|---|---|---|
| coding-agent unit | `CrashLogTest` | 读写往返/坏 JSON/非数组/缺字段过滤/5 条截断/7 天窗/take 全量 notified 回写/clear 静默/stack 匹配四案（§T1） |
| coding-agent unit | `CrashIntegrationTest` | instructions 两臂（有/无 sessionFile）、hint 成形 1/2/3+ 匹配（§T2e） |
| coding-agent unit | `BugReportTest` | isSensitiveKey 边界/redactUrl 六案/redactJsonValue/metadata 两臂/diagnostics 过滤/files 尾换行/selectMessages（§T3） |
| coding-agent unit | `BugReportSummarizerTest` | 四守卫/prompt 拼装/maxTokens/无模型（fake streamFn）（§T3b） |
| agent-core unit | `LlmSummaryGeneratorTest` 加案 | systemPrompt 重载透传＋旧三参不变（§T3b） |
| coding-agent unit | `SessionBranchSerializerTest` | header/parentId 重链/trailing 形状/尾 `\n`（§T4） |
| coding-agent unit | `ZipArchivesTest` | 回读四名四文两臂（§T5） |
| coding-agent unit | `BugReportUploadTest` | 成功/缺 id/500/非 JSON/multipart 形状/token 头两案（fake HttpClient）（§T6） |
| coding-agent unit | `MiscCommandsTest` 加案 | /bug 注册（描述/hint 逐字）、/import fatal marker、/export 引号与默认臂（§T2c/T10） |
| coding-agent unit | `ThinkingCommandsTest` | 七值/off/映射过滤/未知级文案/大小写（§T8） |
| coding-agent unit | `ModelExactMatchTest` | 全形/拆形/裸 id/歧义/空/scope 收口（§T9） |
| coding-agent unit | `SessionStatsTest` | 聚合四源/breakdown 分组/滤零/降序（§T11） |
| coding-agent unit | `SlashChangelogCompactTest` | /changelog 形状钉、/compact abort-first 序列（§T12 核销核验） |
| tui unit | `BugReportFlowTest` | 取消五臂/zip 全链/upload 成功与 fallback/offline 门/summary 失败两臂（§T7） |
| tui unit | `ThinkingLevelScreenTest` | 预选/Enter 设级/Esc（§T8） |
| tui unit | `CrashNoticeTest` | 启动提示装配（有 crash⇒文案逐字、无⇒无）、建议门四臂（§T2a/T2d） |
| 回归 | 全量 | `mvn clean verify` 零错误零警告；**底层改动带 `-am`**（LlmSummaryGenerator 动 agent-core） |

pi 对拍：`bug-report.test.ts`/`crash-log.test.ts` 的 redact/crash 窗口/5 条案抽译进
上表（agent-core 批 4 手法）；zip 不对拍字节（D79）。

---

## §8 对账表（判定列：前态→后态）

| 行 | 权重 | 前态 | 后态 | 手段/注 |
|---|---:|---|---|---|
| map/04 C-2 `/model` | 2 | 存疑 | 对齐 | T9；D86 |
| map/04 C-4 `/thinking` | 2 | 缺失 | 对齐 | T8；D84 |
| map/04 C-6 `/export` | 2 | 存疑 | 对齐 | T10 |
| map/04 C-9 `/bug` | 1 | 缺失 | 对齐 | T1–T7；B79 销号 |
| map/04 C-12 `/session` | 1 | 存疑 | 对齐 | T11；D68 销号 |
| map/04 C-13 `/changelog` | 1 | 存疑 | 对齐 | T12 核销（批 12 代码＋D63） |
| map/04 C-21 `/compact` | 2 | 存疑 | 对齐 | T12 核销（abortAndWaitForIdle） |
| map/04 C-23 `/reload` | 1 | 存疑 | 存疑（不动） | §4 重排批 16 |
| map/05 S29 `/bug` 流程 | 1 | 缺失 | 对齐 | T7 |
| map/05 S30 崩溃记录＋启动提示 | 1 | 缺失 | 对齐 | T1/T2；D81/D83 |
| map/05 R29 安装变更警告 | 1 | 缺失 | 缺失（挂起） | 裁决④，docs/20 序 21 注记 |
| map/04 E8-2/E8-3（不计入） | 1/1 | 缺失 | 对齐 | 注记行 |

---

## §9 闭环回填（2026-10-23 闭环实填）

- [x] docs/04：B79 销号、B256 即登即销、D68 销号、D79–D88 登记、R29 挂起注记、
      本批【决定】行（批 15 段，含触发条件行）
- [x] docs/05＋map/04：C 域 7 行翻动＋汇总行实算（34.0/38 ≈ 89.5%；模块
      341.0/485 ≈ 70.3%）、E8-2/E8-3 注记（E8 汇总逐行实算更正——E8-1 旧行
      漏算）；map/05：S29/S30 翻动＋R29 挂起注记＋汇总实算（118.5/245 ≈ 48.4%）
- [x] docs/20：§2 追加批 15 行、§3 序 14 移除（序 15 并 `/reload`＋B246 注记、
      序 21 加 R29 注记）、§1 四项读数重填（下一包号 docs/27）
- [x] 记忆：批 15 闭环要点＋下一包号 docs/27

### 闭环验证记录

- 全量 `mvn clean verify`（JDK 25）：**全绿**——coding-agent 1,061/1,061、
  tui 434/434（1 skipped 前存）、其余模块全绿；checkstyle/spotbugs 零告警。
- **回归回补三钉**（批内行为变化的既有钉同步，commit `108b1204`）：
  ① 内置命令数 24⇒26（/bug＋/thinking）；② `/export` 无参改默认臂后
  Usage 钉废；③ Cache Re-billed 行位置钉随 D68 销号改 Cost 段内。
- **spotbugs 回补**（commit 随 T7 后）：`BugReportLoaderScreen.done` 加
  volatile（AT_STALE_THREAD_WRITE_OF_PRIMITIVE）。
- **前存红修真**（批 14 登记的 `TreeNavigationFlowTest
  .queuedMessagesRestoreToEditorBeforeAbort` 确定性红）：根因＝测试时序缺陷
  （steer 提交早于 run 首个 provider 调用入口，引擎 turn 装配边界 drain 转向
  队列⇒restore 门恒 false），非生产 bug；`ScriptedChatProvider` 加 callCount
  观测口＋基线差值门修。tui 434/434 绿，前存红清零。
- **排障注记（套件性环境差）**：真 home `~/.pi-java/agent/settings.json` 的
  `compaction.enabled=false` 被所有不重定向 home 且不设 compaction 的测试
  继承——夹具种 settings 必须显式给 enabled 臂（T12 排障所得，已入记忆）。
- **设计稿滞后两处在实施期更正**：① §T3 `redactSettings`「java Settings 只有
  deviceId」——实查 `Settings` 两键俱在（`trackingId:105`/`deviceId:80`），
  按 pi 语义剥两键；② T3b 测试的「No model selected」臂经公共装配够不到
  （harness 恒有模型解析结果），如实登记不造假态（测试内注记）。
- pi 对拍：crash-log（窗口/截断/notified 回写）、bug-report（redact 边界表/
  六案/metadata 两臂/diagnostics 过滤/selectMessages 预算）、zip（回读四名
  四文，字节不对拍 D79）、reportBug 流（取消五臂/zip 全链/upload 两臂＋
  fallback/offline 门/summary 失败两臂）全部抽译落地。

### commit 序（每步可独立编译；`git add <path>` 显式路径）

1. `feat(coding-agent): batch-15 T1 — CrashLog/CrashRecord（崩溃记录本体）`
2. `feat(agent-core): batch-15 T3b — LlmSummaryGenerator systemPrompt 参化缝`
3. `feat(coding-agent): batch-15 T3+T5 — BugReports 核心＋ZipArchives`
4. `feat(coding-agent): batch-15 T4 — SessionBranchSerializer（pi.share trailing）`
5. `feat(coding-agent): batch-15 T6 — BugReportUpload（multipart/网关/离线门件）`
6. `feat(coding-agent): batch-15 T2c+T2e — CrashIntegration 文案件＋/import fatal 修真（B256）`
7. `feat(tui): batch-15 T2a/T2b/T2d — 崩溃启动提示＋未捕获钩＋/bug 建议`
8. `feat(tui): batch-15 T7 — /bug 故障上报流（三屏＋BugReportFlow）＋命令注册`
9. `feat(coding-agent): batch-15 T8 — /thinking 命令＋tui 简版选择器`
10. `feat(coding-agent): batch-15 T9 — /model 直设臂（ModelExactMatch＋刷新回落）`
11. `feat(coding-agent): batch-15 T10 — /export 默认臂＋引号解析`
12. `feat(coding-agent): batch-15 T11 — /session 全段（SessionStats/UsageCostBreakdown，D68 销号）`
13. `feat(coding-agent): batch-15 T12 — /changelog /compact 核销核验`
14. `docs(coding-agent): batch-15 closure — 台账/地图/总账回填`
