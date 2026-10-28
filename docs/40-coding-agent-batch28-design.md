# docs/40 — 批 28 设计稿：examples 移植批 1（组 1：事件守卫与输入改写，16 件）

## §0 背景与范围

docs/39（2026-10-28 审核通过）拍板：examples/ 105 件**全做**，落地形态＝**Java 移植＋验收测试**，新模块 `pi-java-examples`。本批是§7 分批表的**批 28**：组 1「事件守卫与输入改写」16 件，pi 侧合计 ~880 行，全部 headless 可测，依赖的事件面（tool_call/input/before_agent_start/session_before_*/project_trust/provider 请求面）已全部落地（D 域满分）。

**术语**（首现释义）：

- **锚点**：pi 对齐基准提交 `ea448f454`（2026-10-11）。本稿全部 `file:line` 与逐字代码取自该提交的工作树（`D:\workplaceForai\pi`，已核实无漂移）。行号路径前缀 `examples/` ≙ `packages/coding-agent/examples/`。
- **假模型夹具**：`FauxProvider`（pi-java-ai main 源码集，`com.pijava.ai.provider.FauxProvider`），验收测试不用活 LLM。
- **TCCL 夹具**：测试用临时目录生成单件 `META-INF/services/...PiExtension` 文件＋`URLClassLoader` 换线程上下文类加载器，让 `ExtensionManager` 经真 ServiceLoader 只装上被测件（批 27 `NestedToolCallE2eTest` 定型；两钉：classpath 根＝META-INF 祖父、扩展类顶层 public）。
- **ReadonlySessionManager**：pi 给扩展的会话只读面（`session-manager.ts:245-261`，15 成员 Pick）。

**范围外**：组 2–10（批 29–37）；`pi-java-examples` 的 CLI 发行携带（裁决③已拍不携带，本批核实 dist 不含即可）；E5-33/F#22 翻行（条件＝102 件处置完毕，本批只走 16 件）。

---

## §1 命题表（16 件验收断言）

| # | 示例 | pi 源 | 核心命题（验收测试断言） |
|---|---|---|---|
| P1 | hello | `extensions/hello.ts`（26 行） | 注册 `hello` 工具（name/label/description/参数 schema）；执行返回 content 文本 `Hello, <name>!`＋details `{greeted}` |
| P2 | permission-gate | `extensions/permission-gate.ts`（34 行） | tool_call 拦 `rm -rf`/`sudo`/`chmod 777`；无 UI 默认 block（reason 逐字）；有 UI 选 No ⇒ block「Blocked by user」，选 Yes ⇒ 放行；非 bash 工具不发言 |
| P3 | protected-paths | `extensions/protected-paths.ts`（30 行） | write/edit 写 `.env`/`.git/`/`node_modules/` ⇒ block（reason 逐字含路径）；有 UI 时先发 warning 通知；其他工具放行 |
| P4 | confirm-destructive | `extensions/confirm-destructive.ts`（59 行） | session_before_switch reason=new：无 UI 直接放行；有 UI 取消 ⇒ `{cancel}`＋「Clear cancelled」通知；reason=resume 且存在 user 消息条目 ⇒ 询问，取消 ⇒ `{cancel}`；session_before_fork 同构 |
| P5 | dirty-repo-guard | `extensions/dirty-repo-guard.ts`（56 行） | `git status --porcelain` 非零（非 git 仓库）⇒ 放行；干净 ⇒ 放行；脏＋无 UI ⇒ `{cancel:true}`；脏＋有 UI 选 No ⇒ cancel＋warning 通知 |
| P6 | project-trust | `extensions/project-trust.ts`（64 行） | project_trust 五选项映射 yes+remember/yes+remember/yes/no/undecided；无 UI ⇒ undecided；session_start 通知含 ctx.cwd 与 load 计数 |
| P7 | input-transform | `extensions/input-transform.ts`（43 行） | source=extension ⇒ continue（防递归）；`?quick <q>` ⇒ transform「Respond briefly in 1-2 sentences: <q>」；空 query ⇒ handled＋Usage 警告；`ping`/`time` ⇒ handled＋通知；其余 continue |
| P8 | input-transform-streaming | `extensions/input-transform-streaming.ts`（39 行） | streamingBehavior=steer ⇒ continue（跳过 exec）；文本命中触发词且 `git diff --stat` 有输出 ⇒ transform 拼接；无输出 ⇒ continue |
| P9 | inline-bash | `extensions/inline-bash.ts`（94 行） | `!{cmd}` 展开替换；整行 `!cmd` 不碰；30s 超时；非零退出＋stderr ⇒ 替换为 stderr 文本；exec 异常 ⇒ `[error: <msg>]`；有 UI 发汇总通知；返回 transform 带原 images |
| P10 | pirate | `extensions/pirate.ts`（47 行） | /pirate 切换开关（通知文案逐字）；开启后 before_agent_start 返回 systemPrompt＋海盗段（逐字）；关闭返回 null（≙undefined） |
| P11 | claude-rules | `extensions/claude-rules.ts`（86 行） | session_start 扫 `<cwd>/.claude/rules` 递归 .md；无规则 ⇒ before_agent_start 不改 prompt；有规则 ⇒ 追加「## Project Rules」段（逐字模板）＋通知计数 |
| P12 | prompt-customizer | `extensions/prompt-customizer.ts`（49 行） | before_agent_start 按 selectedTools/skills 生成 tool_guidance 节；有内容 ⇒ 写入 sections，无 ⇒ 删除该键 |
| P13 | event-bus | `extensions/event-bus.ts`（43 行） | /emit ⇒ 总线广播，监听者收通知（`Event from /emit command: <msg>`）；session_start 自动 emit 一次；扩展间可跨件收（两扩展互发断言） |
| P14 | provider-payload | `extensions/provider-payload.ts`（18 行） | before_provider_request/after_provider_response 落 `<cwd>/<CONFIG_DIR>/provider-payload.log`；请求臂 JSON 美化（2 空格缩进）＋双换行；响应臂 `[status] {headers}` 形 |
| P15 | structured-output | `extensions/structured-output.ts`（65 行） | 工具带 promptSnippet/promptGuidelines/terminate:true；执行返回 details 三字段；回合在工具结果后直接结束（不再要一次 LLM 回合）；renderResult 两臂（有 details 渲染三行＋编号列表；无 details 回落 content 首文本） |
| P16 | dynamic-tools | `extensions/dynamic-tools.ts`（74 行） | session_start 注册 echo_session；/add-echo-tool 运行时注册；非法名（空/非 `[a-z0-9_]`）⇒ Usage 警告；同名去重 ⇒「already registered」警告；执行返回 `[<prefix>]<message>` |

---

## §2 pi 规格逐字（锚点 `ea448f454`）

### 2.1 组 1 依赖的宿主面（已落，引对位坐标）

| pi 面 | pi 坐标 | pi-java 对位 |
|---|---|---|
| `pi.on("tool_call", …)` 结果 `{block,reason}` | `types.ts:1426`；短路语义 `agent-loop.ts:640-647` | `ExtensionEventSpec.TOOL_CALL`＋`ExtensionResults.ToolCallEventResult(block, reason, terminate)` |
| `pi.on("input", …)` 三态 | `types.ts:1156-1160` | `INPUT`＋`InputEventResult.{Continue,Transform,Handled}`（sealed） |
| `InputEvent{source,streamingBehavior}` | `types.ts:1143-1154` | `ExtensionEvent.InputEvent`（images 恒 null＝窄差 D12 在册） |
| `before_agent_start{systemPrompt}` | `types.ts:1467-1471` | `BEFORE_AGENT_START`＋`BeforeAgentStartEventResult(message, systemPrompt)`；`sections` 可变 LinkedHashMap 同一对象贯穿处理器（批 16 裁决⑪） |
| `session_before_switch/fork{cancel}` | `types.ts:1473-1479` | `SESSION_BEFORE_SWITCH/FORK`＋结果 record（cancel） |
| `project_trust` first-wins | `runner.ts:292-320` | `PROJECT_TRUST`＋`ProjectTrustEventResult(TrustDecision, remember)`（批 23） |
| `before/after_provider_*` | `types.ts:1604-1608` | 批 7 四事件（B248） |
| `pi.events` 总线 | `core/event-bus.ts:1-33` | `ExtensionContext.events()`＝`ExtensionChannelBus`（批 26 E5-28，失效退订三路径） |
| `pi.exec` | `types.ts:1735`→`core/exec.ts` | `ExtensionContext.exec`（批 26 E5-17；**阻塞**调用，timeoutMillis/signal 两臂） |
| `registerCommand` | `loader.ts:303-317` 族 | `ctx.slashCommands().register(SlashCommand)`（EXTENSION 标签＋撞名编号，批 20） |
| `defineTool`+`registerTool` | `types.ts` ToolDefinition 族 | `AgentTool` 实现类＋`ctx.tools()`（ToolRegistry）；`ToolResult.terminate` 已在 record 组件 |
| `ctx.hasUI` / `ctx.ui.select/confirm/input/notify` | `types.ts:332`；`types.ts:161-170` 族 | `ExtensionContext.hasUi()`＋`ExtensionUI`（批 17 全面化 28 成员） |
| `ctx.mode` | `types.ts:330` | `ExtensionContext.mode()`（批 17） |

### 2.2 撞出缺口（本批须补的宿主面）

取证发现 pi `ExtensionContext` 两成员在 java 公开面**缺席**（批 24 只落了 MCP 包内部通道 `hostSessionOrNull()`）：

```ts
// pi packages/coding-agent/src/core/extensions/types.ts:331-335（逐字）
	/** Whether dialog-capable UI is available (true in TUI and RPC modes) */
	hasUI: boolean;
	/** Current working directory */
	cwd: string;
	/** Session manager (read-only) */
	sessionManager: ReadonlySessionManager;
```

```ts
// pi packages/coding-agent/src/core/session-manager.ts:245-261（逐字）
export type ReadonlySessionManager = Pick<
	SessionManager,
	| "getCwd"
	| "getSessionDir"
	| "getSessionId"
	| "getSessionFile"
	| "getLeafId"
	| "getLeafEntry"
	| "getEntry"
	| "getLabel"
	| "getBranch"
	| "buildContextEntries"
	| "buildSessionProjection"
	| "getHeader"
	| "getEntries"
	| "getTree"
	| "getSessionName"
>;
```

组 1 消费点：`confirm-destructive:28` 用 `ctx.sessionManager.getEntries()`；`project-trust:27/62`、`claude-rules:55`、`provider-payload:7/15` 用 `ctx.cwd`。**docs/39 §4-③ 的「✅ 已落」口径修正**：写面（appendEntry/setLabel）已落，读面本批补。

本批最小补面（裁决③）：

- `ExtensionContext.cwd()`：委托宿主会话 cwd；无宿主（--help 轻量道）⇒ 抛 `IllegalStateException`（`requireToolControl` 同文先例——**不**落 process.cwd()，批 24 教训）。
- `ExtensionContext.sessionManager()` 返回新接口 `ExtensionSessionView`（pi `ReadonlySessionManager` 对位），本批只落 **`List<Entry> getEntries()`**（委托宿主 `accumulatedEntries()` 快照；--no-session 无持久面时的行为＝实施取证点 F1，见 §8）；余 14 成员挂账，随后续批消费点逐个补（组 2/4/5 会拉动 getBranch/getEntry/getLabel），E5 域不翻行（已翻行的余量补齐）。

### 2.3 逐件逐字源码与移植要点

#### P1 hello（`examples/extensions/hello.ts:1-26`）

```ts
import { Type } from "@earendil-works/pi-ai";
import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";

const helloTool = defineTool({
	name: "hello",
	label: "Hello",
	description: "A simple greeting tool",
	parameters: Type.Object({
		name: Type.String({ description: "Name to greet" }),
	}),

	async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
		return {
			content: [{ type: "text", text: `Hello, ${params.name}!` }],
			details: { greeted: params.name },
		};
	},
});

export default function (pi: ExtensionAPI) {
	pi.registerTool(helloTool);
}
```

移植：`HelloExtension implements PiExtension`（`name()="hello"`）；工具为 `AgentTool<Map<String,Object>, Map<String,Object>>` 匿名/嵌套实现；parameters ⇒ 手写 schema map（裁决②）：`{"type":"object","properties":{"name":{"type":"string","description":"Name to greet"}},"required":["name"]}`——TypeBox `Type.Object` 默认全键 required，逐键对位。

#### P2 permission-gate（`examples/extensions/permission-gate.ts:10-34`）

```ts
export default function (pi: ExtensionAPI) {
	const dangerousPatterns = [/\brm\s+(-rf?|--recursive)/i, /\bsudo\b/i, /\b(chmod|chown)\b.*777/i];

	pi.on("tool_call", async (event, ctx) => {
		if (event.toolName !== "bash") return undefined;

		const command = event.input.command as string;
		const isDangerous = dangerousPatterns.some((p) => p.test(command));

		if (isDangerous) {
			if (!ctx.hasUI) {
				// In non-interactive mode, block by default
				return { block: true, reason: "Dangerous command blocked (no UI for confirmation)" };
			}

			const choice = await ctx.ui.select(`⚠️ Dangerous command:\n\n  ${command}\n\nAllow?`, ["Yes", "No"]);

			if (choice !== "Yes") {
				return { block: true, reason: "Blocked by user" };
			}
		}

		return undefined;
	});
}
```

移植要点：Java `Pattern` 三个正则逐字（CASE_INSENSITIVE）；`event.input().get("command")`；`null ≙ undefined`；select 的 `null`（取消）≠"Yes" ⇒ block（pi 同形：取消即非 Yes）。

#### P3 protected-paths（`examples/extensions/protected-paths.ts:10-30`）

```ts
export default function (pi: ExtensionAPI) {
	const protectedPaths = [".env", ".git/", "node_modules/"];

	pi.on("tool_call", async (event, ctx) => {
		if (event.toolName !== "write" && event.toolName !== "edit") {
			return undefined;
		}

		const path = event.input.path as string;
		const isProtected = protectedPaths.some((p) => path.includes(p));

		if (isProtected) {
			if (ctx.hasUI) {
				ctx.ui.notify(`Blocked write to protected path: ${path}`, "warning");
			}
			return { block: true, reason: `Path "${path}" is protected` };
		}

		return undefined;
	});
}
```

#### P4 confirm-destructive（`examples/extensions/confirm-destructive.ts:10-59`）

```ts
export default function (pi: ExtensionAPI) {
	pi.on("session_before_switch", async (event: SessionBeforeSwitchEvent, ctx) => {
		if (!ctx.hasUI) return;

		if (event.reason === "new") {
			const confirmed = await ctx.ui.confirm(
				"Clear session?",
				"This will delete all messages in the current session.",
			);

			if (!confirmed) {
				ctx.ui.notify("Clear cancelled", "info");
				return { cancel: true };
			}
			return;
		}

		// reason === "resume" - check if there are unsaved changes (messages since last assistant response)
		const entries = ctx.sessionManager.getEntries();
		const hasUnsavedWork = entries.some(
			(e): e is SessionMessageEntry => e.type === "message" && e.message.role === "user",
		);

		if (hasUnsavedWork) {
			const confirmed = await ctx.ui.confirm(
				"Switch session?",
				"You have messages in the current session. Switch anyway?",
			);

			if (!confirmed) {
				ctx.ui.notify("Switch cancelled", "info");
				return { cancel: true };
			}
		}
	});

	pi.on("session_before_fork", async (event, ctx) => {
		if (!ctx.hasUI) return;

		const choice = await ctx.ui.select(`Fork from entry ${event.entryId.slice(0, 8)}?`, [
			"Yes, create fork",
			"No, stay in current session",
		]);

		if (choice !== "Yes, create fork") {
			ctx.ui.notify("Fork cancelled", "info");
			return { cancel: true };
		}
	});
}
```

移植要点：`getEntries()` ⇒ T2 新面；条目判型 `entry instanceof Entry.Message m && "user".equals(m.message().role())`；`entryId.slice(0,8)` ⇒ `substring(0, Math.min(8, len))`（java substring 越界抛 vs JS 截断——防御写法，语义同）。

#### P5 dirty-repo-guard（`examples/extensions/dirty-repo-guard.ts:10-56`）

```ts
async function checkDirtyRepo(
	pi: ExtensionAPI,
	ctx: ExtensionContext,
	action: string,
): Promise<{ cancel: boolean } | undefined> {
	// Check for uncommitted changes
	const { stdout, code } = await pi.exec("git", ["status", "--porcelain"]);

	if (code !== 0) {
		// Not a git repo, allow the action
		return;
	}

	const hasChanges = stdout.trim().length > 0;
	if (!hasChanges) {
		return;
	}

	if (!ctx.hasUI) {
		// In non-interactive mode, block by default
		return { cancel: true };
	}

	// Count changed files
	const changedFiles = stdout.trim().split("\n").filter(Boolean).length;

	const choice = await ctx.ui.select(`You have ${changedFiles} uncommitted file(s). ${action} anyway?`, [
		"Yes, proceed anyway",
		"No, let me commit first",
	]);

	if (choice !== "Yes, proceed anyway") {
		ctx.ui.notify("Commit your changes first", "warning");
		return { cancel: true };
	}
}

export default function (pi: ExtensionAPI) {
	pi.on("session_before_switch", async (event, ctx) => {
		const action = event.reason === "new" ? "new session" : "switch session";
		return checkDirtyRepo(pi, ctx, action);
	});

	pi.on("session_before_fork", async (_event, ctx) => {
		return checkDirtyRepo(pi, ctx, "fork");
	});
}
```

移植要点：`pi.exec` 对位 `ctx.exec`（阻塞——handler 本身就在会话事件线程上同步跑，pi 的 await 语义直译）；`filter(Boolean)` ⇒ 滤空串。

#### P6 project-trust（`examples/extensions/project-trust.ts:18-64`）

```ts
export default function (pi: ExtensionAPI) {
	let loadCount = 0;
	loadCount++;

	// Multiple handlers in one extension are allowed. The first handler that returns
	// { trusted: "yes" } or { trusted: "no" } wins and suppresses the built-in
	// trust prompt. Return { trusted: "undecided" } to let another handler or the
	// built-in flow decide.
	pi.on("project_trust", async (event, ctx): Promise<ProjectTrustEventResult> => {
		ctx.ui.notify(`project_trust fired for ${event.cwd} (mode: ${ctx.mode}, load: ${loadCount})`, "info");

		if (!ctx.hasUI) {
			return { trusted: "undecided" };
		}

		const choice = await ctx.ui.select(`Project trust for:\n${event.cwd}`, [
			"Trust and remember",
			"Trust with note and remember",
			"Trust this session",
			"Do not trust this session",
			"Let built-in prompt decide",
		]);

		if (choice === "Trust with note and remember") {
			const note = await ctx.ui.input("Project trust note", "Optional note for this demo");
			ctx.ui.notify(note ? `Recorded demo note: ${note}` : "No demo note entered", "info");
			return { trusted: "yes", remember: true };
		}
		if (choice === "Trust and remember") {
			return { trusted: "yes", remember: true };
		}
		if (choice === "Trust this session") {
			return { trusted: "yes" };
		}
		if (choice === "Do not trust this session") {
			return { trusted: "no" };
		}
		if (choice === "Let built-in prompt decide") {
			return { trusted: "undecided" };
		}
		return { trusted: "undecided" };
	});

	pi.on("session_start", (_event, ctx) => {
		ctx.ui.notify(`project-trust example loaded after trust resolution in ${ctx.cwd}`, "info");
	});
}
```

移植要点：`loadCount` 是 pi 模块级（每次模块求值 ++）⇒ java 对位**静态计数在构造器 ++**（每次实例化≈每次加载；ServiceLoader 每会话装配新建实例——语义对位注记，D175 候选不登：行为可见差异为零）。`trusted:"yes"` ⇒ `TrustDecision.YES`；`remember` 未给 ⇒ `null`（≙ undefined）。`ctx.mode`/`ctx.cwd` 均有面对位（cwd 为本批新面 T2）。

#### P7 input-transform（`examples/extensions/input-transform.ts:14-43`）

```ts
export default function (pi: ExtensionAPI) {
	pi.on("input", async (event, ctx) => {
		// Source-based logic: skip processing for extension-injected messages
		if (event.source === "extension") {
			return { action: "continue" };
		}

		// Transform: ?quick prefix for brief responses
		if (event.text.startsWith("?quick ")) {
			const query = event.text.slice(7).trim();
			if (!query) {
				ctx.ui.notify("Usage: ?quick <question>", "warning");
				return { action: "handled" };
			}
			return { action: "transform", text: `Respond briefly in 1-2 sentences: ${query}` };
		}

		// Handle: instant responses without LLM (extension shows its own feedback)
		if (event.text.toLowerCase() === "ping") {
			ctx.ui.notify("pong", "info");
			return { action: "handled" };
		}
		if (event.text.toLowerCase() === "time") {
			ctx.ui.notify(new Date().toLocaleString(), "info");
			return { action: "handled" };
		}

		return { action: "continue" };
	});
}
```

#### P8 input-transform-streaming（`examples/extensions/input-transform-streaming.ts:16-39`）

```ts
const TRIGGER = /\b(changes?|diff|modified)\b/i;

export default function (pi: ExtensionAPI) {
	pi.on("input", async (event) => {
		// During steering, skip the exec call — corrections should be fast
		if (event.streamingBehavior === "steer") {
			return { action: "continue" };
		}

		if (!TRIGGER.test(event.text)) {
			return { action: "continue" };
		}

		const { stdout, code } = await pi.exec("git", ["diff", "--stat"]);
		if (code !== 0 || !stdout.trim()) {
			return { action: "continue" };
		}

		return {
			action: "transform",
			text: `${event.text}\n\nCurrent uncommitted changes:\n\`\`\`\n${stdout.trim()}\n\`\`\``,
		};
	});
}
```

#### P9 inline-bash（`examples/extensions/inline-bash.ts:19-94`）

```ts
export default function (pi: ExtensionAPI) {
	const PATTERN = /!\{([^}]+)\}/g;
	const TIMEOUT_MS = 30000;

	pi.on("input", async (event, ctx) => {
		const text = event.text;

		// Don't process if it's a whole-line bash command (starts with !)
		// This preserves the existing !command behavior
		if (text.trimStart().startsWith("!") && !text.trimStart().startsWith("!{")) {
			return { action: "continue" };
		}

		// Check if there are any inline bash patterns
		if (!PATTERN.test(text)) {
			return { action: "continue" };
		}

		// Reset regex state after test()
		PATTERN.lastIndex = 0;

		let result = text;
		const expansions: Array<{ command: string; output: string; error?: string }> = [];

		// Find all matches first (to avoid issues with replacing while iterating)
		const matches: Array<{ full: string; command: string }> = [];
		let match = PATTERN.exec(text);
		while (match) {
			matches.push({ full: match[0], command: match[1] });
			match = PATTERN.exec(text);
		}

		// Execute each command and collect results
		for (const { full, command } of matches) {
			try {
				const bashResult = await pi.exec("bash", ["-c", command], {
					timeout: TIMEOUT_MS,
				});

				const output = bashResult.stdout || bashResult.stderr || "";
				const trimmed = output.trim();

				if (bashResult.code !== 0 && bashResult.stderr) {
					expansions.push({
						command,
						output: trimmed,
						error: `exit code ${bashResult.code}`,
					});
				} else {
					expansions.push({ command, output: trimmed });
				}

				result = result.replace(full, trimmed);
			} catch (err) {
				const errorMsg = err instanceof Error ? err.message : String(err);
				expansions.push({ command, output: "", error: errorMsg });
				result = result.replace(full, `[error: ${errorMsg}]`);
			}
		}

		// Show what was expanded (if UI available)
		if (ctx.hasUI && expansions.length > 0) {
			const summary = expansions
				.map((e) => {
					const status = e.error ? ` (${e.error})` : "";
					const preview = e.output.length > 50 ? `${e.output.slice(0, 50)}...` : e.output;
					return `!{${e.command}}${status} -> "${preview}"`;
				})
				.join("\n");

			ctx.ui.notify(`Expanded ${expansions.length} inline command(s):\n${summary}`, "info");
		}

		return { action: "transform", text: result, images: event.images };
	});
}
```

移植要点：JS 正则 `g` 全局态（test/exec 推进 lastIndex）⇒ java `Matcher` 无全局态，语义等价（先全量 find 再逐个执行）；`result.replace(full, trimmed)` 是 JS **首个匹配**字面替换 ⇒ java `String.replaceFirst(Pattern.quote(full), Matcher.quoteReplacement(trimmed))`——注意 JS replace 对 `$` 特殊，java quoteReplacement 已规避；`bash -c` 平台依赖登记 D174。`stdout || stderr` 的 JS 空串假值语义 ⇒ `!stdout.isEmpty() ? stdout : stderr`。

#### P10 pirate（`examples/extensions/pirate.ts:15-47`）

```ts
export default function pirateExtension(pi: ExtensionAPI) {
	let pirateMode = false;

	// Register /pirate command to toggle pirate mode
	pi.registerCommand("pirate", {
		description: "Toggle pirate mode (agent speaks like a pirate)",
		handler: async (_args, ctx) => {
			pirateMode = !pirateMode;
			ctx.ui.notify(pirateMode ? "Arrr! Pirate mode enabled!" : "Pirate mode disabled", "info");
		},
	});

	// Append to system prompt when pirate mode is enabled
	pi.on("before_agent_start", async (event) => {
		if (pirateMode) {
			return {
				systemPrompt:
					event.systemPrompt +
					`

IMPORTANT: You are now in PIRATE MODE. You must:
- Speak like a stereotypical pirate in all responses
- Use phrases like "Arrr!", "Ahoy!", "Shiver me timbers!", "Avast!", "Ye scurvy dog!"
- Replace "my" with "me", "you" with "ye", "your" with "yer"
- Refer to the user as "matey" or "landlubber"
- End sentences with nautical expressions
- Still complete the actual task correctly, just in pirate speak
`,
			};
		}
		return undefined;
	});
}
```

移植要点：追加段是模板字面量——**前导 `\n\n` 与尾随 `\n` 逐字保留**（java 文本块会剥首行换行，用显式 `"\n\nIMPORTANT: …"` 拼接，不用 text block 或 text block 内首行留空并核字节）。

#### P11 claude-rules（`examples/extensions/claude-rules.ts:27-86`）

```ts
function findMarkdownFiles(dir: string, basePath: string = ""): string[] {
	const results: string[] = [];

	if (!fs.existsSync(dir)) {
		return results;
	}

	const entries = fs.readdirSync(dir, { withFileTypes: true });

	for (const entry of entries) {
		const relativePath = basePath ? `${basePath}/${entry.name}` : entry.name;

		if (entry.isDirectory()) {
			results.push(...findMarkdownFiles(path.join(dir, entry.name), relativePath));
		} else if (entry.isFile() && entry.name.endsWith(".md")) {
			results.push(relativePath);
		}
	}

	return results;
}

export default function claudeRulesExtension(pi: ExtensionAPI) {
	let ruleFiles: string[] = [];
	let rulesDir: string = "";

	// Scan for rules on session start
	pi.on("session_start", async (_event, ctx) => {
		rulesDir = path.join(ctx.cwd, ".claude", "rules");
		ruleFiles = findMarkdownFiles(rulesDir);

		if (ruleFiles.length > 0) {
			ctx.ui.notify(`Found ${ruleFiles.length} rule(s) in .claude/rules/`, "info");
		}
	});

	// Append available rules to system prompt
	pi.on("before_agent_start", async (event) => {
		if (ruleFiles.length === 0) {
			return;
		}

		const rulesList = ruleFiles.map((f) => `- .claude/rules/${f}`).join("\n");

		return {
			systemPrompt:
				event.systemPrompt +
				`

## Project Rules

The following project rules are available in .claude/rules/:

${rulesList}

When working on tasks related to these rules, use the read tool to load the relevant rule files for guidance.
`,
		};
	});
}
```

移植要点：`readdirSync` 序＝OS 序（不排序）⇒ java `Files.list`/`DirectoryStream` 同为 OS 序（注记：测试不依赖序或建单文件）；相对路径拼 `/` 分隔（逐字，不用 File.separator）。`ctx.cwd` 用 T2 新面。

#### P12 prompt-customizer（`examples/extensions/prompt-customizer.ts:14-48`）

```ts
function buildToolGuidance(options: BuildSystemPromptOptions): string {
	const hasTool = (name: string) => options.selectedTools?.includes(name) ?? false;
	const rules: string[] = [];

	if (hasTool("read")) {
		rules.push(
			"- Use `read` for file contents; it supports text and images.",
			"- For large files, use `offset` and `limit` to read in chunks.",
		);
	}
	if (hasTool("bash")) {
		rules.push("- Use `bash` for file operations such as `ls`, `find`, and `grep`.");
	}
	if (hasTool("edit")) {
		rules.push("- Use `edit` for precise text replacements that match existing content exactly.");
	}
	if (hasTool("write")) {
		rules.push("- Use `write` to create new files or replace existing files completely.");
	}
	if (options.skills && options.skills.length > 0) {
		rules.push(`- Available skills: ${options.skills.map((skill) => skill.name).join(", ")}.`);
	}

	return rules.join("\n");
}

export default function promptCustomizer(pi: ExtensionAPI) {
	pi.on("before_agent_start", (event) => {
		const guidance = buildToolGuidance(event.systemPromptOptions);
		if (guidance) {
			event.systemPromptOptions.sections.tool_guidance = guidance;
		} else {
			delete event.systemPromptOptions.sections.tool_guidance;
		}
	});
}
```

移植要点：java `BeforeAgentStartEvent` 无 `systemPromptOptions.selectedTools/skills` 组件（批 16 裁决⑪ 只落 sections——窄差 D11 在册）。selectedTools ⇒ `ctx.getActiveTools()`（批 16 已落，语义同源：声明给模型的工具集）；skills ⇒ `ctx.skills()`（SkillManager）取名单。**语义对位注记**：pi 读的是构建期 options 快照，java 读事件时的活跃集——同一会话同一时刻两者一致（D11 在册范畴，不新登）。sections 写删 ⇒ 可变 map `put/remove("tool_guidance")`。

#### P13 event-bus（`examples/extensions/event-bus.ts:12-43`）

```ts
export default function (pi: ExtensionAPI) {
	// Store ctx for use in event handler
	let currentCtx: ExtensionContext | undefined;

	pi.on("session_start", async (_event, ctx) => {
		currentCtx = ctx;
	});

	// Listen for events from other extensions
	pi.events.on("my:notification", (data) => {
		const { message, from } = data as { message: string; from: string };
		currentCtx?.ui.notify(`Event from ${from}: ${message}`, "info");
	});

	// Command to emit events (emits "my:notification" which the listener above receives)
	pi.registerCommand("emit", {
		description: "Emit my:notification event (usage: /emit message)",
		handler: async (args, _ctx) => {
			const message = args.trim() || "hello";
			pi.events.emit("my:notification", { message, from: "/emit command" });
			// Listener above will show the notification
		},
	});

	// Example: emit on session start
	pi.on("session_start", async () => {
		pi.events.emit("my:notification", {
			message: "Session started",
			from: "event-bus-example",
		});
	});
}
```

移植要点：`pi.events.on/emit` ⇒ `ExtensionChannelBus`（批 26 落，订阅被跟踪、会话失效统一退订——验收断言含「会话替换后旧订阅不收」）；data 载荷 ⇒ `Map<String,Object>`。

#### P14 provider-payload（`examples/extensions/provider-payload.ts:1-18`）

```ts
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { CONFIG_DIR_NAME, type ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
	pi.on("before_provider_request", (event, ctx) => {
		const logFile = join(ctx.cwd, CONFIG_DIR_NAME, "provider-payload.log");
		appendFileSync(logFile, `${JSON.stringify(event.payload, null, 2)}\n\n`, "utf8");

		// Optional: replace the payload instead of only logging it.
		// return { ...event.payload, temperature: 0 };
	});

	pi.on("after_provider_response", (event, ctx) => {
		const logFile = join(ctx.cwd, CONFIG_DIR_NAME, "provider-payload.log");
		appendFileSync(logFile, `[${event.status}] ${JSON.stringify(event.headers)}\n\n`, "utf8");
	});
}
```

移植要点：`CONFIG_DIR_NAME` ⇒ `SourceResolver.CONFIG_DIR_NAME`（`.pi-java`——pi 的 `.pi` 目录名差异是**存量方言**，早期已登记在册，本批引用不新登）；`JSON.stringify(x,null,2)` ⇒ Jackson `writerWithDefaultPrettyPrinter()`（缩进 2 空格对位；逐字节差异注记：Jackson pretty 的空数组/对象渲染与 JS 相同 `[ ]`/`{ }`？——实施取证点 F2）；payload 是车道原生对象（批 7 裁决⑤），序列化走 Jackson `ObjectMapper.valueToTree`/直接写。

#### P15 structured-output（`examples/extensions/structured-output.ts:18-65`）

```ts
const structuredOutputTool = defineTool({
	name: "structured_output",
	label: "Structured Output",
	description:
		"Return a final structured answer. Use this as your last action when the user asks for structured output or a machine-readable summary.",
	promptSnippet: "Emit a final structured answer as a terminating tool result",
	promptGuidelines: [
		"Use structured_output as your final action when the user asks for structured output, JSON-like output, or a machine-readable summary.",
		"After calling structured_output, do not emit another assistant response in the same turn.",
	],
	parameters: Type.Object({
		headline: Type.String({ description: "Short title for the result" }),
		summary: Type.String({ description: "One-paragraph summary" }),
		actionItems: Type.Array(Type.String(), { description: "Concrete next steps or key bullets" }),
	}),

	async execute(_toolCallId, params) {
		return {
			content: [{ type: "text", text: `Saved structured output: ${params.headline}` }],
			details: {
				headline: params.headline,
				summary: params.summary,
				actionItems: params.actionItems,
			} satisfies StructuredOutputDetails,
			terminate: true,
		};
	},

	renderResult(result, _options, theme) {
		const details = result.details as StructuredOutputDetails | undefined;
		if (!details) {
			const text = result.content[0];
			return new Text(text?.type === "text" ? text.text : "", 0, 0);
		}

		const lines = [
			theme.fg("toolTitle", theme.bold(details.headline)),
			theme.fg("text", details.summary),
			"",
			...details.actionItems.map((item, index) => theme.fg("muted", `${index + 1}. ${item}`)),
		];
		return new Text(lines.join("\n"), 0, 0);
	},
});

export default function (pi: ExtensionAPI) {
	pi.registerTool(structuredOutputTool);
}
```

移植要点（裁决④）：pi 的 renderResult 挂在**工具定义**上；java 的 AgentTool 无内嵌渲染面，渲染走 `registerToolRenderer` 责任链（批 16，文本级——着色由 tui 适配层补，窄差 D92 先例）。本件在 `register()` 里同时注册一个 name=="structured_output" 的 `ToolCallRenderer`：`renderResult` 两臂逐字（无 details ⇒ content 首文本；有 ⇒ headline/summary/空行/编号列表四段），`renderCall` 交 `next`（不管）。挂载点差异登记 **D173**（注记级；展示文案逐字）。terminate 行为断言走进程内 e2e：假模型发 structured_output 调用 ⇒ 回合结束、无第二次 LLM 请求。

#### P16 dynamic-tools（`examples/extensions/dynamic-tools.ts:13-74`）

```ts
const ECHO_PARAMS = Type.Object({
	message: Type.String({ description: "Message to echo" }),
});

function normalizeToolName(input: string): string | undefined {
	const trimmed = input.trim().toLowerCase();
	if (!trimmed) return undefined;
	if (!/^[a-z0-9_]+$/.test(trimmed)) return undefined;
	return trimmed;
}

export default function dynamicToolsExtension(pi: ExtensionAPI) {
	const registeredToolNames = new Set<string>();

	const registerEchoTool = (name: string, label: string, prefix: string): boolean => {
		if (registeredToolNames.has(name)) {
			return false;
		}

		registeredToolNames.add(name);
		pi.registerTool({
			name,
			label,
			description: `Echo a message with prefix: ${prefix}`,
			promptSnippet: `Echo back user-provided text with ${prefix.trim()} prefix`,
			promptGuidelines: ["Use echo_session when the user asks for exact echo output."],
			parameters: ECHO_PARAMS,
			async execute(_toolCallId, params) {
				return {
					content: [{ type: "text", text: `${prefix}${params.message}` }],
					details: { tool: name, prefix },
				};
			},
		});

		return true;
	};

	pi.on("session_start", (_event, ctx) => {
		registerEchoTool("echo_session", "Echo Session", "[session] ");
		ctx.ui.notify("Registered dynamic tool: echo_session", "info");
	});

	pi.registerCommand("add-echo-tool", {
		description: "Register a new echo tool dynamically: /add-echo-tool <tool_name>",
		handler: async (args, ctx) => {
			const toolName = normalizeToolName(args);
			if (!toolName) {
				ctx.ui.notify("Usage: /add-echo-tool <tool_name> (lowercase, numbers, underscores)", "warning");
				return;
			}

			const created = registerEchoTool(toolName, `Echo ${toolName}`, `[${toolName}] `);
			if (!created) {
				ctx.ui.notify(`Tool already registered: ${toolName}`, "warning");
				return;
			}

			ctx.ui.notify(`Registered dynamic tool: ${toolName}`, "info");
		},
	});
}
```

移植要点：运行时 `registerTool` ⇒ `ctx.tools()`（ToolRegistry）注册——pi-java 的工具注册表运行期可写（批 27 exposure 面已证实）；事件句柄内拿到的 per-extension ctx 存字段供命令处理器复用（pi 闭包语义对位——`pi` 对象在 java 即 register 入参 ctx，命令处理器内用**捕获的 register 期 ctx** 还是 SlashContext？裁决⑤）。

---

## §3 裁决点（设计稿拍板清单；用户审核时逐条可推翻）

### 裁决①：扩展类形态——一件一个顶层 public 类，`name()`＝pi 文件名 stem

【场景】pi 示例是单文件 default-export 函数；java SPI 是 `PiExtension` 接口（`name()`＋`register(ExtensionContext)`）。
【决定】每件 ⇒ `com.pijava.examples.<Name>Extension` 顶层 public 类（批 27 钉：嵌套类被 ServiceLoader 静默吞）；`name()` 返回 pi 文件名去 `.ts`（`hello`/`permission-gate`/…——pi 侧扩展名即文件名）；default export 函数体 ⇒ `register(ctx)` 体；模块级 `let` 状态 ⇒ 实例字段（pi 模块每会话重新求值≈java 每会话新实例，语义对位）；模块级常量 ⇒ `private static final`。
【风险】pi 的「同一文件两次 session_start 闭包共享模块态」与 java 实例字段在**同会话内**等价；跨会话等价性已由 ServiceLoader 每装配新建保证。[内部]

### 裁决②：TypeBox ⇒ 手写 JSON Schema Map，逐键对位

【场景】pi 用 `Type.Object/Type.String` 建参数 schema；java 侧 `AgentTool.inputSchema()` 就是 `Map<String,Object>`，无 typebox 对应物。
【决定】手写 schema map，键序与 TypeBox 产出一一致（`type/properties/required/additionalProperties`——TypeBox 默认产 `additionalProperties:false`？实施取证点 F3：跑 `Type.Object({name:Type.String()})` 的 JSON 输出钉死键集，schema 逐字节对位其 JSON）。
【风险】schema 是**模型可见**面——键集差异会改变发给 LLM 的 JSON。F3 取证先行，差异以 pi 运行时产出为准。[模型可见]

### 裁决③：`ExtensionContext` 补 `cwd()`＋`sessionManager()` 最小读面

【场景】§2.2 撞出：pi `ExtensionContext.cwd`（types.ts:333）与 `sessionManager`（:335）在 java 公开面缺席；组 1 四件直接用。
【决定】接口加两默认方法：`cwd()` 委托宿主会话 cwd，无宿主抛 `IllegalStateException`（`requireToolControl` 同文）；`sessionManager()` 返回新接口 `ExtensionSessionView`（pi ReadonlySessionManager 对位），本批只落 `List<Entry> getEntries()`（快照拷贝；`--no-session` 行为＝取证点 F1）；余 14 成员挂账随批补。
【风险】新接口进编码规范（≤500 行/ADT 习惯）无压力；挂账项须在 docs/04 批 28 段登记「ReadonlySessionManager 余 14 成员」。[模型不可见]

### 裁决④：structured-output 的 renderResult ⇒ `registerToolRenderer` 文本级挂载

【场景】pi 把 renderResult 挂在工具定义内；java AgentTool 无内嵌渲染面（批 16 裁决⑦ 定调：java 渲染走责任链、文本级，着色窄差 D92）。
【决定】扩展 `register()` 内同时 `registerToolRenderer(name=="structured_output" ⇒ renderer)`，两臂文案逐字；`renderCall` 返 null 交后续 resolver。
【风险】挂载点差异登记窄差 D173；行为（展示文本）一致。[内部·展示]

### 裁决⑤：命令处理器内的 `ctx`——捕获 register 期 per-extension 上下文

【场景】pi 的 handler 第二参 `ctx` 是会话 ExtensionContext；java `SlashCommand.execute(args, SlashContext)` 的 SlashContext 是会话/设置/信任/退出引用，**不是** ExtensionContext。event-bus/dynamic-tools/pirate 的 handler 用 `ctx.ui.notify`、`pi.events.emit`、`pi.registerTool`。
【决定】扩展把 `register(ExtensionContext)` 入参存实例字段（pi 的闭包捕获 `pi` 对位），命令处理器内经它调 `ui()`/`events()`/`tools()`；`SlashContext` 只用于命令自身语义（本组三件不需要）。晚绑定语义由 DefaultExtensionContext 既有机制保证（批 17 裁决②）。
【风险】无宿主轻量道（--help）下命令不执行，无碍。[内部]

### 裁决⑥：services 资源全量登记＋测试 TCCL 单件夹具

【场景】`pi-java-examples` main 资源放 `META-INF/services/com.pijava.coding.agent.extension.PiExtension` 列全部移植件——classpath 上有该模块即全量装上（示例库语义：装上即启用全部）。验收测试要逐件隔离。
【决定】main 资源全量登记（随批追加）；测试逐件走 TCCL 夹具（tmp 目录生成单件 services 文件——既有 `NestedToolCallE2eTest` 模式），直实例化单测补充纯逻辑臂（正则/normalize/schema）。另加一件「注册表完整性」测试：ServiceLoader 全量发现名集 == 本批 16 件名单。
【风险】pi-java-examples 不进 dist（裁决③ docs/39），`mvn verify` 时它的 main 资源不会污染其他模块测试（模块隔离）。[内部]

### 裁决⑦：UI 交互臂用假 ExtensionUI 桩测双臂

【场景】守卫类示例的有 UI 臂（select/confirm/input）需要 UI；批 28 全 headless。
【决定】测试桩实现 `ExtensionUI` 覆写 select/confirm/input/notify（脚本化应答＋通知录表），`hasUi()` 由会话装配模式控制；双臂断言（无 UI 默认阻断臂＋有 UI 交互臂）。TamboUI 真渲染不进本批（组 3 批 31 才上测试壳）。
【风险】桩不验证真 TUI 展示——展示文案的逐字性靠桩录表断言 message 串。[内部]

### 裁决⑧：`pi.exec` 阻塞语义沿用＋inline-bash 的 `bash` 平台依赖登记

【场景】批 26 已裁 java exec 为阻塞调用；inline-bash 固定 `bash -c`。
【决定】handler 内直接同步调用（pi await 直译）；`bash` 在 Windows 验证环境依赖 Git Bash 在 PATH（开发机满足），登记窄差 D174；验收测试的展开命令用可移植命令（`echo`）但扩展本体保持 `bash -c` 逐字。
【风险】无 Git Bash 的环境跑不了 P9 的 e2e 臂——CI/开发机均有；纯逻辑臂（正则/替换）单测不依赖。[环境]

---

## §4 实施任务（依赖序）

| T | 内容 | 依赖 |
|---|---|---|
| T1 | **模块地基**：`pi-java-examples/pom.xml`（parent 挂载＋依赖 coding-agent/tui；junit/assertj test）；`com.pijava.examples` 包；main 资源 services 文件（空壳起步随批追加）；核实 `pi-java-dist` 不含本模块；README 一段（移植自 pi examples README 的组织说明） | — |
| T2 | **宿主面补缺**：`ExtensionContext.cwd()`＋`ExtensionSessionView`（`getEntries()`）＋DefaultExtensionContext/宿主接线＋F1 取证（--no-session 的 accumulatedEntries 行为）；单测（有宿主/无宿主两臂） | T1 |
| T3 | **工具族 3 件**：hello／structured-output（含渲染器裁决④）／dynamic-tools；F3 schema 取证先行 | T1 |
| T4 | **tool_call 守卫 2 件**：permission-gate／protected-paths | T1 |
| T5 | **会话守卫 3 件**：confirm-destructive（用 T2 getEntries）／dirty-repo-guard／project-trust | T2 |
| T6 | **input 3 件**：input-transform／input-transform-streaming／inline-bash | T1 |
| T7 | **systemPrompt 3 件**：pirate／claude-rules（用 T2 cwd）／prompt-customizer | T2 |
| T8 | **总线/日志 2 件**：event-bus／provider-payload（用 T2 cwd；F2 取证） | T2 |
| T9 | **闭环**：全量 `mvn clean verify`（零错误零警告、checkstyle 写明错误/警告数、spotbugs 零钉）＋账面实算＋三处回填（docs/04＋docs/05/map＋docs/06）＋services 名单终核 | T3–T8 |

各件内部结构统一：扩展类（main）＋`<Name>ExtensionTest`（test，TCCL e2e＋纯逻辑单测）。

## §5 窄差预案（登记不认账；编号接续批 27 的 D171）

| # | 窄差 | 类别 |
|---|---|---|
| D172 | TypeBox ⇒ 手写 JSON Schema map（语义逐键对位，无类型推导层） | [内部] |
| D173 | structured-output 渲染挂载点：pi 工具定义内嵌 renderResult ⇒ java 责任链文本级渲染器（着色随 D92） | [内部·展示] |
| D174 | inline-bash 固定 `bash -c`（Windows 需 Git Bash 在 PATH；pi 同依赖，java 验证环境同形） | [环境] |
| D175 | `-e` 参数路径形态：pi 接受 `.ts` 文件/目录 ⇒ java 接受 JAR（语义一致：指定路径加载扩展；docs/39 裁决③ 预告，本批正式登记） | [用户可见·边缘] |

沿用不新登：`.pi` ⇒ `.pi-java` 目录名（存量方言）；InputEvent.images 恒 null（D12）；prompt-customizer 读活跃集而非构建期快照（D11 范畴）。

## §6 测试计划

- **逐件验收**：§1 命题表 P1–P16 每件至少一条 e2e（TCCL 夹具＋FauxProvider＋`--no-session --no-mcp` 防真 home 继承〔批 14 教训〕）＋纯逻辑单测（正则、normalizeToolName、schema 键集、展开替换、rules 扫描）。
- **守卫类双臂**：无 UI（hasUi=false ⇒ 默认阻断/放行）＋假 UI 桩（§3 裁决⑦）两臂都断言。
- **事件真实注册**：P2/P3 经真会话发 tool_call（假模型发 bash/write 调用）断言阻断文案进工具结果；不只测 handler 函数。
- **terminate（P15）**：假模型脚本化「调用 structured_output ⇒ 断言无第二次 provider 请求、回合成」。
- **动态注册（P16）**：session_start 后 `getAllTools()` 含 echo_session；命令执行后再含新件；同名去重警告文案逐字。
- **总线（P13）**：两扩展互发（本件＋测试夹具第二扩展）断言跨件可达＋会话失效退订。
- **注册表完整性**：services 名单 == 16 件（防漏登）。
- **文件 ≤500 行**：测试类随件拆分，天然满足。

## §7 账面预算（逐行实算口径）

- **E5-33／F#22 不翻行**（docs/39 拍板：翻对齐以 102 件处置完毕为条件；本批走 16/102）。
- **map/04 无行翻动、模块账面不变**（446.0/485 ≈ 92.0% 保持）；T2 补的两成员属已翻行的余量补齐，不计 Δ。
- docs/06 §2 表本批行记「账面不变（examples 批 1/8–10）」。
- 进度注记口径：批闭环时docs/04 批 28 段记「examples 处置 16/102」。

## §8 风险与排障预案

| 风险 | 预案 |
|---|---|
| F1：`--no-session` 下 `accumulatedEntries()` 行为未核（getUserMessagesForForking 注释提示有 harness 转录兜底） | T2 实施先读 `AgentSession.accumulatedEntries()` 实现钉死两臂；view 语义逐字 pi（getEntries 全分支提交序） |
| F2：Jackson pretty printer 与 `JSON.stringify(x,null,2)` 字节差（尾换行/空容器） | T8 先跑对照（nodejs 一行脚本产锚点串），差则以 pi 为准手写序列化臂 |
| F3：TypeBox `Type.Object` 产出键集未核（additionalProperties/$id 等） | T3 前跑锚点 tsx 一行打印 JSON.stringify(schema)，Java map 逐键对位（批 21 golden 先例：锚点实跑钉死） |
| ServiceLoader 夹具两钉（classpath 根＝META-INF 祖父／顶层 public） | 沿用批 27 夹具骨架照抄 |
| 真 home settings/mcp.json 继承污染环境性红 | 夹具全行为相关键显式给全（批 12 教训）＋`--no-mcp` |
| `-am` 遗漏拾旧 jar | 模块新增后首跑 `mvn test -pl pi-java-examples -am`；收口全量 clean verify（spotbugs 在 verify） |
| mvn 输出 GBK 乱码 | `MAVEN_OPTS="-Duser.language=en -Dfile.encoding=UTF-8"` |
| 并发 mvn 互踩 target / 管道吞退出码 / verify 日志落 target 被 clean wipe | 批 26/6 教训沿用：串行跑、看退出码、日志落 docs 外临时目录 |

## §9 闭环回填（2026-10-28 闭环）

**命题表验证**：P1–P16 全过（69 用例；逐件 e2e＋纯逻辑臂；P13 跨件互发经 BusProbeExtension 夹具；P14 经 HookLaneProvider 钩消费桩车道；P15 terminate 经单响应序列＋助手消息计数）。

**取证点结局**：
- F1（`--no-session` 的 `accumulatedEntries()`）：harness 转录兜底既有语义，`ExtensionSessionView.getEntries()` 直委托（`ExtensionContextCwdSessionViewTest` 两臂钉死）。
- F2（Jackson pretty vs `JSON.stringify(x,null,2)`）：**有字节差**（空容器 `{ }`/`[ ]`、整型键重排、数字形 `1.0`/`1e+21`/`0.000001` 区间规则）⇒ 手写 `JsJson` 序列化臂（node 实跑锚点单测钉死）。
- F3（TypeBox 键集）：TypeBox 1.3.27 源读取证（`object.mjs`/`create.mjs`）——`~kind` 非枚举不进 JSON；产出键序 `type/required/properties`，无 `additionalProperties`/`$id`；手写 schema map 逐键对位（`JsonSchemas.obj`）。

**裁决实施注记**：裁决⑦ ScriptedUi 的 null 应答经哨兵（ArrayDeque 拒 null）；T5 dirty-repo-guard 核心返回型由设计稿的 `Boolean` 改 `boolean`（spotbugs NP_BOOLEAN_RETURN_NULL，批 agent-core-4 教训同族；pi 语义两态无损）。

**verify 实测**：`mvn clean verify` exit **0**（17:10 min，2026-10-28）——**5,441 测 0 失败 0 错 21 跳过**（批 27 的 5,370＋批 28 新增 71＝examples 69＋T2 两臂 2）；`[ERROR]` 零条；checkstyle「0 violations」全模块；spotbugs BugInstance 0×13 模块。

**账面实算**：map/04 无行翻动——模块 446.0/485 ≈ **92.0%**、E5 域 54.0/58 ≈ **93.1%**、G 域 12.0/18 ≈ **66.7%**、全仓 1,163.0/1379 ≈ **84.3%** 全保持（T2 两成员为已翻行 E5 的余量补齐，不计 Δ）；examples 处置 **16/102**（E5-33/F#22 翻行条件＝102 件完毕，docs/39 §4）。

**三处回填**：docs/04（§4 批 28 段〔含 D172–D175 登记＋ReadonlySessionManager 余 14 成员挂账〕＋§7 下一包号 docs/41）＋docs/05（总表头/合计行/读数行批注＋批 28 条目）＋docs/06（§1 五读数＋§2 批 28 行）。T1 核实：pi-java-dist pom 无 examples 引用（发行不携带）。

**排障钉（批 28 新增）**：TCCL 夹具第三钉——examples 主资源 services 全量名单在父 classpath 上，作用域 loader 须按 target/classes 根前缀**过滤父链资源**（否则每测试装上全部 16 件，逐件隔离失效）；`Map.of` 不保单序（JSON 键序＝插入序，夹具与锚点一律 LinkedHashMap 逐 put）；`List.of` 拒 null（`Arrays.asList`）；测试提示词须真命中 pi 正则（"what changed?" 不命中 `\bchanges?\b`——`changes?` 不含 d）；Jackson 2.x 的 JsonNode 是 `isTextual()`（`isString()` 是 Jackson 3 名）。
