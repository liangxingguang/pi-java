# 19 - L5 收口设计：coding-agent（目标锚点 `a00a5048d`）

> 【背景摘要】`docs/09` P0 预审计把 L5 判为 **439.5/462 ≈ 95.1%**（旧锚点正式读数 449.5/452＝99.45%），
> 是 L0–L4 收口后剩余的最大单层缺口（−4.3 个百分点／账面 −10.0 分）。本稿按 `56cae7823..a00a5048d`
> 的 coding-agent `src` 逐项取证，把 13 个重开单元与 7 个新单元拆成 **W1–W11**，目标是收口后
> **462/462＝100%**（llama.cpp 若按 R2 出分母则 **461/461＝100%**，见 §8 F1）。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前
> main（`97017106`）。分层判定只写 `docs/map/04-coding-agent.md`，本稿只做设计、实施与验收。
> 本稿**不改代码**，等用户审核通过后才实施。

术语：
- **重开单元**：旧锚点判「对齐」、新锚点因 pi 行为变化而需要重新判定的既有计分行。
- **新单元**：`56cae7823..a00a5048d` 新增的、旧分母里不存在的可观察承诺。
- **模型可见面**：模型读到的系统提示、工具声明/结果、报错文本；其余算【内部/UI 可见】。
- **capture 输出**：TUI 绘制终端期间，git/npm 的输出不直接写终端，而是被捕获进错误尾部。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | 13 个重开单元恢复「对齐」，7 个新单元落地；L5 **462/462＝100%**（llama 出分母则 461/461） |
| 行为不变量 | 工具名/参数集、slash 命令集合、RPC 33 命令集合、会话 JSONL 线形、扩展注册面不变；不引入新模块 |
| 完成判据 | ①`mvn test -pl pi-java-coding-agent -am` 全绿；②`mvn test -pl pi-java-tui -am` 全绿；③`mvn clean verify -Dfrontend.skip=true` 零 ERROR；④`map/04` 逐行改判＋`docs/05` §1.2／`docs/09` 进度行同步 |
| 不做项 | 工具语义（已 L3/P5）、MCP 传输/配置（已 L2/P9）、TUI 渲染长尾与全屏/SIGTSTP（L7）、examples（L8）、codemode 沙箱与分类器（R5） |

## 2. 范围与计分

### 2.1 重开单元（权重不变，判定从 1 → 0.5/0）

| 行 | 权重 | 新锚点变化 | 本稿归属 |
|---|---:|---|---|
| B-7 `auth <cmd>` | 2 | 新增 `pi auth login/logout <provider>`（`9b9cb30ae`） | W1 |
| C-1 `/settings` | 2 | `cacheWarming` 默认 idle＋选择器搜索键保留选中（`7bcc15c92`、`5bcf370a5`） | W2／W9 |
| C-5 `/scoped-models` | 1 | 搜索框中左右/Home/End 不重置选中（`5bcf370a5`、`37b74c546`） | W9 |
| C-10 `/copy` | 2 | 跳过无文本的 aborted/error/toolUse/length 回合（`ede360e4d`） | W7 |
| C-18 `/login` | 2 | 伙伴 Provider 置顶、最佳匹配、登录 URL 清洗、Radius MCP 复用（`683a7a868`、`37b74c546`、`11521`） | W9／W10 |
| C-22 `/resume` | 2 | 渐进加载保选中（`a635f3901`）＋恢复后模型回退上报（`c8c456c85`） | W4／W9 |
| E2-14 `showCacheMissNotices` | 1 | 新增 OpenAI/Anthropic reasoning 丢弃提示（`03de604b8`） | W7 |
| E2-49 `cacheWarming` | 2 | 默认 `idle`＋连续暖合并一行（`7bcc15c92`、`a013448fb`） | W2 |
| E5-9 `registerMarkdownTransformer` | 2 | 新增 `options.messageTypes` 六面（`eebd5d90c`） | W6 |
| E5-18 `getActiveTools/getAllTools/setActiveTools` | 2 | 恢复 loadout 只隐藏自身工具时不丢 MCP 工具（`3345c2925`） | W6 |
| E6-9 `onProgress` | 1 | 新增 `result` 事件；TUI capture 输出（`342514bdf`、`b513b64d8`） | W8 |
| E6-11 `update --extensions` | 2 | 逐包版本摘要、unchanged/warned/offline 分流、git clean 失败继续（`342514bdf`、`806cdd68e`、`e3da248ea`） | W8 |
| E7-2 `extension_ui_request/response` | 2 | 替换后重绑单次；空闲 `ctx.shutdown()`；信号只被重复信号强退（`beffcde92`、`1708b4788`、`ace560c30`） | W5 |

### 2.2 新单元（权重由预审计临时分配）

| 新单元 | 权重 | pi 证据 | 本稿归属 |
|---|---:|---|---|
| 会话 cwd 缺失 Continue/Fork/Cancel | 2 | `core/session-cwd.ts:40-57`；`main.ts:739-760,6840` | W3 |
| 每提示前记 thinking level＋模型回退上报 | 2 | `core/agent-session.ts:662-706,1979`；`core/sdk.ts:217-231`；`main.ts:927` | W4 |
| 会话替换诊断／空闲 shutdown／信号关停 | 2 | `core/agent-session-runtime.ts:127`；`modes/rpc/rpc-mode.ts:754`；`modes/print-mode.ts:56-70` | W5 |
| llama.cpp 默认端口 9931 | 1 | `extensions/llama/provider.ts:25` | W10／F1 |
| dropped-reasoning 转录提示 | 1 | `modes/interactive/interactive-mode.ts:497-523,4384` | W7 |
| `pi update` 逐包版本 | 1 | `core/package-manager.ts:1269-1330`；`package-manager-cli.ts:1058,1133` | W8 |
| 登录 URL 协议/控制字符清洗 | 1 | `utils/web-url.ts:8-20` | W10 |

### 2.3 不属 L5（本稿只登记指针）

| pi 变更 | 归属 |
|---|---|
| `render()` 只读 `string[]`、`blocked` overlay、SIGTSTP、Markdown 块缓存、Mermaid、全屏 O(viewport) | L7（`docs/map/05`） |
| examples 的 plan-mode／subagent 重写 | L8（`docs/07`） |
| `resolveSchemaRef`、schema 校验方言 | L1/L2/L3（已收口） |
| codemode 沙箱、llama 分类器图片 | R5 |

## 3. pi 证据（逐项）

### 3.1 `pi auth login/logout`（`9b9cb30ae`）

新增三个 CLI 文件：`cli/auth-login.ts`（277 行）、`cli/auth-logout.ts`（72 行）、`cli/auth-terminal.ts`（78 行）；
`main.ts` 在 `runAuthCommand` 前分派（`:130-170`）：

```ts
	if (args[0] === "auth" && args[1] === "login") {
		process.exitCode = await runAuthLoginCommand(args.slice(2).filter((arg) => arg !== "--offline"));
		return true;
	}
	if (args[0] === "auth" && args[1] === "logout") {
		process.exitCode = await runAuthLogoutCommand(args.slice(2).filter((arg) => arg !== "--offline"));
		return true;
	}
```

`auth-login.ts:24` 的用法串、`:36-90` 的分派顺序、`:92-106` 的参数解析：

```ts
const AUTH_LOGIN_USAGE = `${APP_NAME} auth login <provider> [--api-key] [--no-browser]`;
...
	const provider = findMatchingProviders(modelRuntime.getProviders(), providerRef)[0];
	if (!provider) { ... `Unknown provider "${replaceControlCharacters(providerRef)}".` ... }
	if (!terminal.interactive) {
		terminal.error(`"${AUTH_LOGIN_USAGE}" requires an interactive terminal`);
		return 1;
	}
	const authType = selectAuthType(provider, request.apiKey);
```

登录成功文案（`:84-89`）、模型目录刷新（`:246-278`）、Radius MCP 自动配置（`:283-303`）：

```ts
	terminal.write(
		`${authType.type === "oauth" ? `Logged in to ${provider.name}` : `Saved API key for ${provider.name}`}. Credentials saved to ${getAuthPath()}`,
	);
	await refreshProviderCatalog(provider, modelRuntime, terminal, signal);
	if (provider.id === RADIUS_PROVIDER_ID) configureRadiusMcpServer(provider, options);
```

`auth-logout.ts:21-70`：未知 provider 报 `Unknown provider "<ref>".`／`Stored credentials: ...`；无凭证
打印「No stored credentials for …」并 exit 0；成功按 `oauth`/`api_key` 两臂输出。

新增共享助手：

- `core/provider-matching.ts`：`findMatchingProviders`（id/name 精确、忽略大小写与首尾空白）。
- `core/preferred-providers.ts:9-27`：`PREFERRED_PROVIDER_IDS = [RADIUS_PROVIDER_ID, "openrouter"]`；
  `compareProviders` 先伙伴后按 `name.localeCompare`。
- `core/radius-mcp.ts:18-55`：`planRadiusMcpServer` 复用全局 `mcp.json` 里 URL 相同的服务器；缺则按
  `radius`／`radius-mcp`／`radius-mcp-N` 命名，写 `auth: {provider}` 并删除 `oauth`。
- `utils/web-url.ts:8-20`：`webUrl` 只放行 `http:`/`https:`；`displayUrl` 非法 URL 走
  `replaceControlCharacters`。

### 3.2 设置与缓存预热

`core/settings-defaults.ts:61`：

```ts
	cacheWarming: "idle",
```

`core/cache-warmer.ts:352-356`：格式化改为**累计成本**：

```ts
export function formatCacheWarmingUsage(totalCost: number, note?: string): string {
	const noteText = note ? ` (${note})` : "";
	const cost = totalCost.toFixed(6).replace(/(\.\d{3}\d*?)0+$/, "$1");
	return `Cache warmed${noteText}: $${cost}`;
}
```

`modes/interactive/interactive-mode.ts:4351-4370`：连续暖且仍是最后一行 ⇒ 原地累加，不再新起一行：

```ts
		const previous = this.cacheWarmingNotice;
		if (previous && this.chatContainer.children.at(-1) === previous.component && previous.note === entry.note) {
			previous.total.cost += entry.usage.cost.total;
			previous.component.invalidate();
			return;
		}
```

`utils/changelog.ts:181-190`：`lastVersion` 只取开头 `major.minor.patch`，预发布后缀不再每次启动重放。

### 3.3 会话 cwd 缺失（`4ea883792`）

`core/session-cwd.ts:40-57`：

```ts
export function formatMissingSessionCwdPrompt(issue: SessionCwdIssue): string {
	return `Session cwd does not exist: ${issue.sessionCwd}`;
}
export type MissingSessionCwdChoice = "continue" | "fork";
export function getMissingSessionCwdOptions(issue): Array<{ label: string; value: ... }> {
	return [
		{ label: `Continue in ${issue.fallbackCwd} (asks again next time)`, value: "continue" },
		{ label: `Fork into ${issue.fallbackCwd} (new session ID, original kept)`, value: "fork" },
		{ label: "Cancel", value: undefined },
	];
}
```

`main.ts:739-760` 交互臂：`continue` ⇒ `SessionManager.open(sessionFile, sessionDir, cwd)`；
`fork` ⇒ `forkSessionOrExit`（改写 cwd、生成新 ID、原会话保留）；`undefined` ⇒ exit 0。
非交互臂仍打印 `MissingSessionCwdError` exit 1。`interactive-mode.ts:6037-6055` 的
`switchToForkedSession` 在扩展否决切换时 `rmSync(forkPath)`，取消不留副本；`agent-session-runtime.ts:406-414`
的 `/import` 失败同样删除复制件。

### 3.4 选型记录与模型回退

`agent-session.ts:662-706`：`_recordSelection()` 同时记模型与 thinking level；`_recordThinkingLevel` 取
分支最后一条 `thinking_level_change`，**按当前模型 clamp 后**与实时档比较，不同才追加：

```ts
	private _recordThinkingLevel(branch: SessionEntry[]): void {
		const recorded = branch.findLast((entry) => entry.type === "thinking_level_change");
		if (!recorded) return;
		const recordedLevel = recorded.thinkingLevel as ThinkingLevel;
		const availableLevels = this.getAvailableThinkingLevels();
		const effective = availableLevels.includes(recordedLevel)
			? recordedLevel
			: this._clampThinkingLevel(recordedLevel, availableLevels);
		if (effective === this.thinkingLevel) return;
		this.sessionManager.appendThinkingLevelChange(this.thinkingLevel);
	}
```

`agent-session.ts:1979` 在每次 run 启动处调用 `_recordSelection()`。`sdk.ts:217-231` 恢复分支模型；
失败时（模型不存在/无鉴权）先生成 `Could not restore model <p>/<m>`，再由 `findInitialModel` 补
`. Using <p>/<m>`。`main.ts:927-932` 把它作为 `warning` 诊断，**所有模式在启动与每次替换后都上报**。

### 3.5 会话替换与生命周期

`agent-session-runtime.ts:127-136` 新增去重诊断出口：

```ts
	takeUnreportedDiagnostics(): AgentSessionRuntimeDiagnostic[] {
		return this._diagnostics.filter((diagnostic) => {
			const key = `${diagnostic.type}\0${diagnostic.message}`;
			if (this.reportedDiagnosticKeys.has(key)) return false;
			this.reportedDiagnosticKeys.add(key);
			return true;
		});
	}
```

`print-mode.ts:77-125`、`rpc-mode.ts:317-360` 都**先订阅再 `bindExtensions`**，使 `session_start` 处理器
追加的条目进入 JSON/事件流；替换后由 `setRebindSession` 统一重绑一次（删除 RPC 各命令里的显式
`rebindSession()`）。新文件 `modes/diagnostics-stderr.ts:1-12` 把诊断写 stderr（`Error:` 红、
`Warning:` 黄、info 暗色）。

`rpc-mode.ts:754-758`：空闲 shutdown 只在此刻处理：

```ts
	function checkShutdownRequested(): void {
		if (!shutdownRequested || shuttingDown || !inputReady || activeCommands > 0 || !session.isIdle) return;
		void shutdown();
	}
```

`shutdownHandler` 用 `setImmediate(checkShutdownRequested)`；`activeCommands` 在命令/`prompt()` 外包一层；
`inputReady` 在输入挂接后置 true。`print-mode.ts:56-70`、`rpc-mode.ts:380-402` 的信号：首个 SIGTERM/SIGHUP
走带清理的关闭，重复信号才 `process.exit(129/143)`；`agent-session-runtime.ts:426-445` 的 `dispose()`
先 `abort()`，再等 `settleCredentialWrites()`（刷新令牌不丢）。`agent-session.ts:3829-3835` 的
`reload()` 自身拒绝流式/压缩中（不再只靠命令层门）。

### 3.6 工具恢复与 Markdown 变形器

`agent-session.ts:1607-1617`：`setActiveToolsByName` 只在「被去掉的名属于**恢复 loadout**」时清 pending，
扩展隐藏自己的工具不再连带丢掉尚未注册的 MCP 工具；`:1907-1940` 维护 `_restoredToolNames`，reload
`:3860` 同步。

`extensions/types.ts:1574-1600`：`MarkdownMessageType` 扩为六值
（`user/assistant/assistant-thinking/custom/summary/changelog`）；`MarkdownTransformerOptions.messageTypes`
缺省三值；`loader.ts:253,367-372` 注册时包成集合；`runner.ts:797-805` 执行前按 `context.messageType`
过滤。TUI 侧 custom/summary/changelog 面接入（`branch-summary-message.ts`、`compaction-summary-message.ts`、
`skill-invocation-message.ts`、`mermaid.ts`、`interactive-mode.ts` 变更日志臂）。

### 3.7 `/copy` 与 reasoning 丢弃提示

`agent-session.ts:4526-4545`：

```ts
		if (msg.stopReason !== "stop" && !msg.content.some((c) => c.type === "text" && c.text.trim())) return false;
```

即跳过无文本的 aborted/error/toolUse/length 回合；正常结束的空回复仍是最后回复，最终 `text.trim() || undefined`。

`interactive-mode.ts:467-523`：`REASONING_DROP_DIAGNOSTICS` 覆盖
`anthropic_input_transformations`（`thinking_dropped`）与 `openai_input_transformations`（`reasoning`）；
`:4384-4420` 受 `showCacheMissNotices` 控制，只有计数比上一响应多才显示，并带原因：

```ts
			() => theme.fg("warning", `${provider} dropped ${dropped.count} ${noun}${reasons} (details in session)`),
```

### 3.8 `pi update` 与 capture 输出

`package-manager.ts:1269-1330`：`update()` 收集 npm/git 的 `from→to`，按配置序发 `result` 事件：

```ts
			this.emitProgress({
				type: "result", action: "update", source: pkg.source,
				message: `${pkg.source}${scope} ${format(pkg.from)} -> ${format(pkg.to)}`,
			});
```

`package-manager-cli.ts:1058-1160`：`updatedPackages`／`warningCount`；离线打印
`Skipped package updates (offline mode)`；无更新但有警告 ⇒ `... was not updated, see the warnings above`；
无更新无警告 ⇒ `... is already up to date`；有更新 ⇒ `Updated N package(s):` 逐行缩进。`package-manager.ts:2186-2218`
的 git clean 失败不再中断：装依赖、保留 marker、发黄色 `progress` 提示。`b513b64d8` 的 TUI capture：
`commandOutput="capture"`、`GIT_TERMINAL_PROMPT=0`、`GIT_SSH_COMMAND` 缺省 `ssh -o BatchMode=yes`，错误尾部 8,000 字符。

### 3.9 选择器 UX（跨层）

- `5bcf370a5`：搜索框里左右/Home/End 只移动光标，不重新过滤；`settings-list.ts`、
  `scoped-models-selector.ts`、`session-selector.ts`、`oauth-selector.ts`、`model-selector.ts`、
  `thinking-selector.ts` 同改。
- `37b74c546`：`/login`、`/logout` 的搜索 Enter 选**最佳匹配**，不是首项。
- `a635f3901`：`session-selector.ts:281,359-364,616,664` 用 `selectionTouched`＋pending path，渐进列表
  刷新时保住用户选中的行。
- `683a7a868`：Provider 列表用 `compareProviders`（Radius、OpenRouter 置顶），同一 provider 的账号项
  稳定排在 API key 项之前。

### 3.10 安全/平台与模型可见文本

- `utils/web-url.ts`：登录 URL 只打开 http/https，其余打印为控制字符替换后的文本。
- `core/auth-storage.ts:86-87,131-132`：锁加在 `realpath` 解析后的文件，符号链接别名共享一把锁。
- `utils/glob.ts`（`10947`）：glob 放大保护；R5 内部安全项，无计分行。
- 系统提示收紧（`705d047cb`，`core/system-prompt.ts:153-168`）：preamble 缩短、tools 追加句缩短、
  docs 段两条合并为一条；`skills.ts:372` 相对路径提示缩短。
- `prompt-templates.ts:28-70`：引号只在参数开头生效（词中撇号按字面）；反斜杠转义引号；未闭合引号
  整体回退为字面文本。
- `branch-summarization.ts:223-235`：摘要输入应用每个 target 的最新 `context_edit`，与转录投影一致。

## 4. Java 现状与逐项设计

### W1 `auth login/logout`

**现状**：`subcommand/AuthCommand.java:42-47` 只有 `print-api-key/check/oauth-login/print-bearer-token/profile`；
`SubcommandHandler.java:11` 将 `auth` 整体交给它。OAuth 流在 `pi-java-ai`（`OAuthProviders`、
`AnthropicOAuthFlow`、`ChatGptOAuthFlow`、`DeviceCodeFlow`、`RadiusOAuthFlow`），API key 在
`FileCredentialStore`，OAuth 凭证在 `OAuthCredentialStore`。没有 `login`/`logout` 子命令、没有统一
凭证列举/删除、没有 Radius MCP 规划助手。

**决定**：

1. `AuthCommand.run` 增加 `login`/`logout` 两臂，参数解析照 `auth-login.ts:92-106` 逐字：
   `<provider>` 位置参数、`--api-key`、`--no-browser`；`--api-key`＋`--no-browser` 给逐字错误。
2. 新建 `subcommand/AuthLoginCommand.java`、`AuthLogoutCommand.java`、`AuthTerminal.java`：
   `AuthTerminal` 是可注入的 line-based I/O（`interactive`／`write`／`error`／`question`／`secret`／
   `openUrl`）；生产实现用 `System.console()`，无 console 时 `interactive=false`；测试注入脚本化实现。
3. `selectAuthType` 三臂：非 `--api-key` 且有 OAuth ⇒ `oauth`；有 API key login ⇒ `api_key`；只有环境
   凭证 ⇒ 逐字 `"<name> is configured outside pi-java (<method>)."`；`--api-key` 但仅 OAuth ⇒
   `"<name> has no API key login. Omit --api-key to sign in."`。
4. 交互事件（`auth_url`/`device_code`/`info`）走 `AuthTerminal`；浏览器只在 `request.browser` 且 URL 是
   http/https 时打开（见 W10）。
5. 成功后刷新该 provider 目录（复用 `ModelCatalogRefresh` 的单 provider 臂）并打印目标文案；Radius 登录
   调 `RadiusMcpPlan`（W1 新增，见下）写全局 `mcp.json`。
6. `logout`：列出 `auth.json`＋`auth-oauth.json` 的已存 provider；未知且无凭证 ⇒ 两条错误；无凭证 ⇒
   `No stored credentials for <name>. Environment variables and models.json config are unchanged.` exit 0；
   删除成功按 `oauth`／`api_key` 两臂逐字输出。
7. 新增 `core/RadiusMcpPlan.java`，对位 `radius-mcp.ts`：读全局 `mcp.json`、URL 归一、命名冲突后缀、
   写 `auth.provider` 并删 `oauth`；`/login` 的生产者 `RadiusMcpOffer` 改为复用同一 helper。
8. `ProviderLoginCatalog` 的排序改为 `compareProviders`（新增 `core/PreferredProviders.java`）；匹配
   保持现有精确语义（pi 新 helper 行为相同，不再另写二分）。

**风险**：`System.console()` 在 Windows/IDE 下可能不可用 ⇒ `--api-key` 也无提示；必须把「非交互报错」
写成显式分支，并让测试覆盖无 console 臂。OAuth 的 `Signal` 中止在 Java 侧是 `AtomicBoolean`＋
`CancellationToken` 方言，登录中断需在 `AuthTerminal.question/secret` 里抛 `Login cancelled`。

### W2 设置默认与缓存暖合并

**现状**：`SettingsNetworkAccessors.java:61` 非法/缺省回落 **`"streaming"`**；`SettingsItems.java:83-84`
三值循环已存在。`CacheWarmingFormatting.formatUsage(Entry.Usage)`（`:104-109`）按单条目格式化；
`CacheNoticeRenderer.appendUsageNotice`（`:62-73`）每次新起气泡。

**决定**：

1. `SettingsNetworkAccessors.getCacheWarmingMode` 缺省改 `"idle"`；设置页描述无需改（三值已对齐）。
2. `CacheWarmingFormatting.formatUsage(long totalCostMicrosOrScaled, String note)` 改为累计成本签名；
   `Entry.Usage` 单条调用点改为读 `usage().cost().total()` 传入。
3. `CacheNoticeRenderer` 增加 `cacheWarmingNotice` 字段（组件引用＋累计成本＋note）；仅当上一条暖行仍是
   `chatPanel` 最后子项且 note 相同 ⇒ 原地更新并 `invalidate()`，否则新起（保持 Spacer 一条）。
4. `ChatMessage.cacheWarmNotice` 的持久化条目路径仍逐条渲染（pi 历史重放也是逐条）；合并只作用于实时臂。
5. `ChangelogSupport` 的 `getNewEntries` 改为只解析开头 `v?major.minor.patch`；无法解析 ⇒ 空（不重放）。

**风险**：金额是 `double`/`BigDecimal` 的精度口径；Java `Entry.Usage.cost().total()` 若为 `double`，
累加后再格式化与 pi 的逐条相加在 1e-9 级可能差最后一位。用 `BigDecimal` 累加可消除，但会改 JSON 模型。
判据以「两位小数以内一致」为准，极端末位差异登记 D。

### W3 会话 cwd 缺失（Continue/Fork/Cancel）

**现状**：Java 完全没有该流程：`SessionPersistence.attach` 只 `handle.open(metadata)`；`SessionSetup`
不检查 header cwd；`Main` 只检查**当前**工作目录存在。TUI `/resume` 直接 `switchTo`。

**决定**：

1. 新增 `core/SessionCwd.java`：`issueFor(SessionMetadata)`（stored cwd 存在 ⇒ null；否则
   `{sessionCwd, fallbackCwd=user.dir, sessionFile}`）、`formatPrompt`、`options()` 三臂，逐字。
2. `Module Main`／TUI 启动解析后：非交互模式打印 `Error: Session cwd does not exist: <cwd>` exit 1；
   交互模式在 TUI 首次绘制前用现有 `StartupSelector` 呈现三选项：
   - `continue` ⇒ 以 fallback cwd 打开会话（不写 header，下次仍提示）；
   - `fork` ⇒ `SessionDiscoveryFork` 以 fallback cwd 生成新 ID（原会话保留）并切换；
   - `cancel` ⇒ exit 0。
3. 会话内 `/resume` 与 `/import`：`switchTo`／`import` 捕获 `SessionCwdIssue`，走同一三臂；fork 分支在
   扩展 `before_switch` 取消时删除新文件（`agent-session-runtime.ts:406-414` 对位）。
4. 非交互 print/json/rpc：pi 的 `MissingSessionCwdError` 是 stderr + exit 1；RPC 走 `switch_session` 的
   `fail` 响应，不重试。

**风险**：目前 Java 的 `SessionMetadata.cwd()` 可能来自 SQLite 后端（R5 独立实现），JSONL 才有 header cwd。
本流程只对 JSONL 生效；SQLite 返回空 cwd 视为「不存在」会误伤。`issueFor` 必须先判 backend／空串，
SQLite 臂维持现状并登记 D（pi 无 SQLite 后端）。

### W4 选型记录、恢复与回退上报

**现状**：`SessionDataViews.lastAssistantText` 只找「有文本的 assistant」；`VirtualModelSelections` 注释
明确「`getBranchSelection` 不落：Java resume 不从分支恢复模型」。`AgentSessionFactory` 无 model restore；
`RunLifecycle.recordConfigChanged` 只在 setter 变更时写 thinking entry，run 起点不写。

**决定**：

1. 新增 `VirtualModelSelections.getBranchSelection(branch, resolver)`：从后往前取最新
   `model_change`（物理/虚拟均按 `getModel` 解析），与 pi `getBranchSelection` 同形。
2. `AgentSessionFactory` 在 `resumeLike(args)` 且无显式模型时：分支选型存在且可用 ⇒ 用它；不可用 ⇒
   记 `warning "Could not restore model <p>/<m>"`，再走现有初始模型；拿到后补
   `". Using <p>/<m>"`。该 warning 进入 `startupDiagnostics`（W5 的去重表）。
3. thinking 恢复：有 `thinking_level_change` ⇒ 分支值；否则默认档。恢复后按模型可用档 clamp。
4. run 起点（`PromptPipeline.startRun`／`RunStarter`）在 `recordModelChange` 之后调用
   `SessionSelectionRecorder.recordThinking(branch)`：取分支最后 thinking entry，按当前模型 clamp，
   与实时档不同才写新 entry。若分支没有 thinking entry：pi `_recordThinkingLevel` 直接 return
   （不补写）——Java 同。
5. 模型 `model_change` 的写入仍按 Java 既有「setModel 无条件写」；只在 run 起点补虚拟选型时按 pi
   `_recordModelSelection` 的条件写（记录模型与实际模型相同则跳过）。

**风险**：Java 的 `ModelId` 与 `ModelRuntime.getModel` 的注册表在「runtime 注册的 provider」上可能解析不到；
回退路径必须覆盖「会话模型不在目录」这一主场景，不能抛异常。`thinkingLevel` 的 clamp 表在 Java 是
`ThinkingLevels.availableLevels`，比 pi 的 `thinkingLevelMap` 多 `off` 语义，需按既有 `ThinkingCommands`
口径复用。

### W5 会话替换、诊断、RPC shutdown 与信号

**现状**：Java `AgentSession.startupDiagnostics()` 每次返回全量；`RpcDispatcher` 在 `new_session`／
`switch_session`／`fork`／`clone` 里各自重绑订阅（`:114-136,251-296`）；`RpcMode.run` 的循环捕获异常后
返回 1；TUI/PrintMode 各自打印启动诊断。没有 `takeUnreportedDiagnostics()`、没有空闲 shutdown 等待、
没有「重复信号才强退」。

**决定**：

1. `AgentSession` 增加 `unreportedDiagnostics()`（按 `type\0message` 去重）；启动时消费一次，替换后
   再消费；`AgentSessionFactory.createReplacing` 把上一会话的诊断集合传给新会话（或由宿主保存）。
2. `PrintMode`／`RpcMode`／TUI 在替换回调里打印/展示未上报诊断：print/rpc 走
   `DiagnosticsStderr.write(...)`；TUI 走 `showRuntimeDiagnostics`（error/warning/status 三臂）。
3. `RpcDispatcher` 的所有替换臂统一到 `rebindSession()`：先订阅（`session.subscribe`）再
   `bindExtensions`，并只重绑一次；删除各命令里的重复订阅。`new_session`／`switch_session`／`fork`／
   `clone` 返回后不手动再 rebind。
4. `RpcMode`：`shutdownRequested`＋`inputReady`＋`activeCommands`＋`session.isIdle` 四门；`extensionUI`
   的 shutdown handler 置 flag 并 `setImmediate(check)`；stdin EOF 也走 `check`。
5. 信号：Java 没有公开信号 API（`sun.misc.Signal` 不在 `--release` 的导出面），因此只做
   `Runtime.addShutdownHook` 的一次性清理；「重复信号才强退」保留为窄差 **D**（F2）。首个信号仍须
   完成 `dispose()` 再退出，不能像旧实现那样直接 `return 1`。
6. `AgentSession.reload()` 在会话层再次拒绝 `isRunning`／`isCompacting`（命令层已有一道，双门不冲突）；
   `SessionReload.reload` 入口同加，错误文案与命令层逐字一致。
7. `dispose`：先 `abort()`，扩展 shutdown，再等待 `CredentialSettlement`（若 ai 侧没有，新增
   `ModelCatalogRefresh.settleCredentialWrites` 或 `OAuthCredentialStore` 的写入锁结算）。

**风险**：`takeUnreportedDiagnostics` 的键是 `type\0message`，消息里若带可变路径/时间会导致每次替换都重报；
pi 同病，照抄。RPC 先订阅再 bind 可能让 `session_start` 处理器在扩展尚未 `bind` 前追加条目——pi 明确
接受，Java 需保证 `subscribe` 的线程安全（`SessionEventHub` 已是并发安全）。

### W6 工具恢复与 Markdown 变形器六面

**现状**：`ModelToolControl.java:206-211` 减员即清 pending（不看恢复 loadout）；
`restoreToolsFromTranscript`（`:245-286`）不维护 restored 集合；`SessionReload` 只把激活名塞 pending。
`MarkdownTransformer` 接口无 options；`MarkdownMessageType` 三值；TUI `MarkdownTransformers.apply` 三值。

**决定**：

1. `ModelToolControl` 增加 `restoredToolNames`（`LinkedHashSet`）：
   - `restoreToolsFromTranscript` 按 `getCurrentTools`＋modifiers 填 restored 集合；
   - `setActiveToolsByName` 只有被去掉的名 ∈ restored 集合才 `pending.clear()`；
   - `SessionReload` 重绑时把当前激活名写入 restored（pi `:3860`）。
2. `MarkdownMessageType` enum 扩为六值；`MarkdownTransformer` 接口加 `default Set<MarkdownMessageType>
   messageTypes()` 或注册参数；`ExtensionSurfaceRegistries.registerMarkdownTransformer(path,
   transformer, messageTypes)`；`markdownTransformers()` 返回带过滤的包装器（pi `runner.ts:797`）。
3. TUI 消费面：`custom-message`、`skill-invocation`、`branch-summary`、`compaction-summary`、`changelog`
   五处调用 `MarkdownTransformers.apply` 时传对应 `messageType`；`enabled` 集合过滤在链内完成。
4. `ExtensionContext.registerMarkdownTransformer` 保留旧一参重载（默认三面），新两参重载。

**风险**：Markdown 面扩展会把用户文本/摘要/变更日志交给第三方扩展；这是 pi 的刻意行为，但 Java 的
`MarkdownTransformers` 是静态全局，需确保 `clear()` 在 reload 时只清注册表、不残留包装器。

### W7 `/copy`、reasoning 提示、暖行合并（与 W2 共件）

**现状**：`SessionDataViews.lastAssistantText`（`:138-156`）取最后一条**有文本**的 assistant，未按
`stopReason` 过滤；`MiscCommands.java:96` 与 RPC `get_last_assistant_text` 直接用它。TUI 没有
reasoning-drop 提示；`CacheNoticeRenderer` 没有合并态。

**决定**：

1. `lastAssistantText`：从后往前找第一条 `role=assistant`；若 `stopReason != "stop"` 且无
   `text().trim()` ⇒ 继续；找到后拼文本，`trim()` 空返回 null。对位 `:4526-4545`。
2. 新增 `tui/util/ReasoningDropNotice`：按 `AssistantMessage.diagnostics()` 的
   `anthropic_input_transformations`／`openai_input_transformations` 统计 `thinking_dropped`／`reasoning`；
   provider 名/名词/原因集合逐字；门 `showCacheMissNotices`；与上一响应同类且计数不增 ⇒ 不显示。
3. 在 `SessionEventRouter` 的 `MessageEnd` 臂调用；重建 transcript 时按持久化条目重放（pi `:3999` 同）。
4. 暖行合并按 W2。

**风险**：Java 的 `AssistantMessageDiagnostic.details` 是 `JsonNode`；`transformations` 为数组时要防
`null` 元素。上一响应的判定用「分支最后一条 assistant」；Java 的 `accumulatedMessages` 可能含流式副本，
需用持久化条目（与 pi 同）。

### W8 `pi update` 逐包摘要与 capture 输出

**现状**：`PackageCommand.java:230` 恒打印 `Updated <source>`／`Updated packages`；
`PackageUpdater` 有 `ProgressEvent` 三型（start/progress/complete/error），无 `result`；
`PackageManager` 的 `git clean` 失败即抛错；TUI 运行期安装直接继承终端输出。

**决定**：

1. `ProgressEvent` 增加 `result` 型（action=update、source、message）；`PackageUpdater.update` 收集
   npm/git 的 `from→to`，按配置序发；git 前/后取 `git rev-parse HEAD`（7 位截断）。
2. `PackageCommand` 增加 `updatedPackages`／`warningCount`；离线/有警告无更新/无更新无警告/有更新 四臂，
   文案逐字（见 §3.8）。
3. `cleanAndInstallGitDependencies`：git clean 失败 ⇒ 发 `progress` 黄色提示、继续装依赖、保留 marker。
4. `PackageManager` 增加 `commandOutput`（`inherit|capture`）；capture 时 `GIT_TERMINAL_PROMPT=0`、
   `GIT_SSH_COMMAND` 缺省 `ssh -o BatchMode=yes`、stdout/stderr 合并保留尾 8,000 字符；失败消息带
   `GIT_CREDENTIALS_HINT`（仅 git 且命中 prompt-failure 正则）。
5. TUI 启动/替换安装传 `capture`，TUI 开始前用 stderr 打 `start` 消息；`ConfigCommand` 的安装臂同。

**风险**：Java 的 `GitPackageStore` 用 `ProcessBuilder`，没有 Node 的 `waitForChildProcess` 管道语义；
capture 必须读完 stdout/stderr 再 `waitFor`，防止子进程占管道导致死锁。Windows 上原生模块被占用时
`git clean` 失败是主场景，必须继续而非回滚。

### W9 选择器与 Provider 排序

**现状**：`ProviderLoginCatalog.java:48-50` 按 `name.toLowerCase` 排序；无伙伴置顶。
`ProviderLoginScreen`／`LoginFlow` 已有搜索；`ScopedModelsScreen`／`SettingsScreen`／`SessionListScreen`
已有搜索列表。`SessionSelectorComponent` 的渐进刷新选中逻辑需核对。

**决定**：

1. 新增 `core/PreferredProviders.java`：`[radius, openrouter]`＋`compareProviders`；`ProviderLoginCatalog`
   与登录选择器改用；稳定排序保证同 provider 账号项在前。
2. `LoginProviderScreen`／`LoginFlow` 的 Enter 选择「最佳匹配」：复用 pi 的评分（名称前缀/包含/模糊），
   无搜索词才选首项。
3. 搜索框 cursor 键：`SettingsScreen`、`ScopedModelsScreen`、`SessionListScreen`、`ModelSelectorScreen`、
   `ThinkingLevelScreen` 的搜索输入拦截 Left/Right/Home/End，只移动光标，不触发重新过滤/重置选中。
4. `/resume` 渐进列表：`SessionListScreen` 收到部分结果时，若用户已移动过选中（`selectionTouched`）且
   pending 路径仍存在 ⇒ 保持；否则重置到最佳匹配（pi `:359-364`）。

**风险（跨层）**：这些行为在 pi 的文件属 `modes/interactive`（coding-agent），在 Java 属 `pi-java-tui`。
按「map/04 计分行在 L5」的口径，本批在 TUI 实现但**不新增 map/05 计分单元**；L7 重测时只复核
`S7/S11` 证据，不重复扣分。见 F3。

### W10 安全、URL 与 llama.cpp

1. 新增 `cli/WebUrl.java`（`webUrl`／`displayUrl`）：登录事件打印前清洗；`openUrl` 只放行
   `http:`/`https:`；Java 的 `Desktop.browse` 失败不抛到终端。
2. `FileCredentialStore` 的 per-path 锁键改 `path.toRealPath()`（不存在时 `toAbsolutePath().normalize()`），
   使符号链接别名共享同一 `ReentrantLock`＋`FileChannel` 锁；`OAuthCredentialStore` 同改。
3. llama.cpp：Java **没有**内置 llama 扩展（`packages/coding-agent/src/extensions/llama/` 对位不存在），
   只有 R2 范围外的本地 provider 可能经 `models.json` 自定义。设计**建议**按 R2 出分母（F1），不新建
   整套扩展；若用户要求实现，则按 W10b 先做最小 `LlamaProvider`（默认 `http://127.0.0.1:9931`、
   `/v1/models`、`/v1/chat/completions`、连接失败错误文本），不做 HF 搜索/System One 分类器。
4. `/export` 的 OSC 8：Java `export/HtmlExporter` 不经过 ANSI 转换层，工具文本直接 `escape`；
   pi 的新行为发生在 `ansiToHtml`。**建议登记 D**（Java 无 SGR/OSC 导出面），不新增渲染器（F4）。

### W11 模型可见文本

1. `SystemPrompts.DEFAULT_PREAMBLE` 改为 pi 新 preamble 的 pi-java 版本：
   `"You are a coding assistant running inside pi-java, a coding agent harness. Help users inspect files, run commands, and modify code."`
   `toolsSection` 追加句改 `"Additional tools may be available."`；`docsSection` 合并两条为
   `"Before implementing harness changes, read the relevant docs and examples in full, following related cross-references."`
   （品牌/文档路径仍指向 pi-java 真实文件 ⇒ 品牌窄差 D77 族保留）。
2. `SkillsPrompt` 的第二条指引改为新短句（`:372` 逐字）。
3. `PromptTemplates.parseCommandArgs` 按 `prompt-templates.ts:28-70` 重写：引号只在 token 开头、
   反斜杠转义引号、未闭合引号回退字面。
4. `BranchSummarization.prepareBranchEntries`：先收集 `Entry.ContextEdit` 的最新 target 映射；第二遍
   对 `Entry.Message`／`Entry.CustomMessage` 应用 `ContextEntries.applyEdit` 后产消息（pi `:223-235`）。
5. `CompactionUtils.truncateForSummary` 使用 `sliceHead`（L3 已实现的代理对安全截断），不再裸 `slice`。

**风险**：系统提示文本会改现有测试的逐字断言；必须按 pi 新锚点逐字核对，只把品牌词替换为 pi-java。
`docs` 段的路径保持 Java 实际路径，不能用 pi 路径（否则模型被误导）。

## 5. Java 改动清单（按文件）

| 文件 | 改动 | 归属 |
|---|---|---|
| `subcommand/AuthCommand.java` | 新增 login/logout 分派与帮助 | W1 |
| `subcommand/AuthLoginCommand.java`（新） | OAuth/API key 登录、事件、刷新、Radius MCP | W1 |
| `subcommand/AuthLogoutCommand.java`（新） | 列举/删除凭证 | W1 |
| `subcommand/AuthTerminal.java`（新） | 可注入终端 I/O | W1 |
| `core/PreferredProviders.java`（新） | 伙伴排序 | W1/W9 |
| `core/RadiusMcpPlan.java`（新） | 全局 mcp.json 规划 | W1 |
| `core/SettingsNetworkAccessors.java` | 默认 idle | W2 |
| `core/CacheWarmingFormatting.java` | 累计成本签名 | W2/W7 |
| `tui/screen/CacheNoticeRenderer.java` | 暖行合并 | W2 |
| `core/ChangelogSupport.java` | 预发布版本解析 | W2 |
| `core/SessionCwd.java`（新） | cwd 缺失三臂 | W3 |
| `SessionSetup`／`Main`／TUI 启动 | 接入三臂 | W3 |
| `core/virtual/VirtualModelSelections.java` | `getBranchSelection` | W4 |
| `core/AgentSessionFactory.java` | 恢复模型/thinking＋回退 warning | W4 |
| `core/PromptPipeline.java`／`RunStarter` | run 起点记 thinking | W4 |
| `core/AgentSession*.java` | 诊断去重、reload 双门、dispose 结算 | W5 |
| `rpc/RpcDispatcher.java`／`RpcMode.java` | 统一 rebind、空闲 shutdown | W5 |
| `modes/PrintMode.java` | 先订阅后 bind、诊断 stderr | W5 |
| `tui/app/PiTuiApp` + `screen` | 替换诊断展示、选择器键位 | W5/W9 |
| `core/ModelToolControl.java` | restoredToolNames | W6 |
| `extension/MarkdownTransformer*.java` | 六面＋options | W6 |
| `tui/component/MarkdownTransformers.java` + 五组件 | 六面消费 | W6 |
| `core/SessionDataViews.java` | lastAssistantText 判定 | W7 |
| `tui/util/ReasoningDropNotice.java`（新） | 丢弃提示 | W7 |
| `pkg/ProgressEvent.java`／`PackageUpdater`／`PackageManager` | result＋capture＋git clean | W8 |
| `subcommand/PackageCommand.java` | 四臂摘要 | W8 |
| `cli/WebUrl.java`（新） | URL 清洗 | W10 |
| `pi-java-ai` auth stores | realpath 锁 | W10 |
| `agent-core/SystemPrompts.java`／`SkillsPrompt.java` | 文本收紧 | W11 |
| `core/PromptTemplates.java` | 引号解析 | W11 |
| `compaction/BranchSummarization.java` | context edits | W11 |
| `export` | 仅登记 D（无 ANSI 面） | F4 |

## 6. 计分变化（预计）

| 域 | 旧 Σ/Σ(w×c) | 收口后 | 说明 |
|---|---:|---:|---|
| A CLI 参数 | 62/62 | 62/62 | 不变 |
| B 子命令 | 15/15 | 15/15 | W1 后 B-7 恢复对齐 |
| C slash | 38/38 | 38/38 | W1/W2/W7/W9 后五行恢复 |
| D 主题/settings | 83/83 | 83/83 | 不变 |
| E1 包元数据 | 10/10 | 10/10 | 不变 |
| E2 settings 键 | 84.5/87 | 87/87 | E2-14、E2-49 恢复 |
| E3 会话 | 38/38 | 38/38 | W3/W4 证据更新（无新行） |
| E4 提示/资源 | 14/14 | 14/14 | 不变 |
| E5 扩展 API | 53/53 | 53/53 | E5-9、E5-18 恢复 |
| E6 包管理 | 34/34 | 34/34 | E6-9、E6-11 恢复 |
| E7 RPC | 13/13 | 13/13 | E7-2 证据更新 |
| G tool_search | 5/5 | 5/5 | 不变 |
| **模块** | **452/449.5** | **462/462** | 新增 10 权重（llama 出分母则 461/461） |

> E8（系统提示分节，不计入模块）随 W11 更新证据；`docs/05` §1.2 的 L5 行在收口后改为
> `462/462＝100%`，并在缺口列写「llama 出分母见 F1」。

## 7. 测试计划（RED-first）

| 用例 | 夹具 | 先红断言 |
|---|---|---|
| `auth login` 未知 provider／非交互／`--api-key --no-browser` | 脚本化 `AuthTerminal`＋假 `ProviderRegistry` | 当前无子命令 ⇒ 解析失败；实现后逐字文案 |
| `auth logout` 未知/无凭证/OAuth/API key 四臂 | `FileCredentialStore` 临时 home＋`OAuthCredentialStore` | 当前无子命令；四臂输出逐字 |
| Radius MCP 规划 | 临时 `mcp.json`：无/同 URL 无 auth/同 URL 有 auth/命名冲突 | 当前无 helper；断言 name/url/auth、删除 oauth |
| 缓存暖合并 | TUI `CacheNoticeRenderer` 直接追加三条同 note | 当前三条气泡；合并后一条且金额累加 |
| 默认 cacheWarming | 空 settings | 当前返回 `streaming`；改 `idle` |
| cwd 缺失三臂 | 临时 session header cwd 指向已删目录 | 当前直接打开；断言 continue/fork/cancel 与 fork 新 ID |
| thinking 记录 | 分支最后 thinking=xhigh、当前模型不支持 ⇒ clamp 为 high、实时 high ⇒ 不写 | 当前 run 起点不写；实现后 entry 数不变；改成 xhigh 实时 ⇒ 写一条 |
| 模型恢复/回退 | 分支 `model_change` 指向不存在模型；有可用模型 | 当前不回退；断言 warning 文案与选用模型 |
| 诊断去重 | 同一 warning 连续两次替换 | 当前重复打印；实现后只一次 |
| RPC 空闲 shutdown | `session_start` 里 `ctx.shutdown()`；随后发命令 | 当前永不退出；实现后命令处理完退出 |
| 工具恢复 | 恢复 loadout 含 mcp 工具（未注册），扩展隐藏自己的工具 | 当前 pending 被清 ⇒ MCP 工具不激活；实现后激活 |
| Markdown 六面 | 注册 `messageTypes=["summary"]` 的 transformer | 当前只三值、摘要不经过；实现后只摘要生效 |
| `/copy` 跳过 | 末条 assistant `stopReason=error` 无文本，前一条有文本 | 当前返回空/前一条；实现后返回前一条 |
| reasoning 提示 | 构造 `openai_input_transformations` 诊断，计数 1→1 | 当前无提示；实现后第一次显示、第二次不显示 |
| `pi update` 四臂 | 假 PackageUpdater 发 result/progress | 当前恒 `Updated`；实现后逐字四臂 |
| capture git 失败 | 假 git 输出 `terminal prompts disabled` | 当前无提示；实现后错误尾部＋凭据提示 |
| 引号解析 | `"say \"hi\""`、`it's`、未闭合 `"abc` | 当前丢引号/撇号；实现后逐字 |
| 分支摘要 context edit | 分支含 `context_edit` 隐藏一条消息 | 当前仍摘要隐藏消息；实现后不摘要 |
| URL 清洗 | `javascript:alert(1)`、带 `\x1b` 的 URL | 当前原样打印/打开；实现后替换控制字符、不打开 |
| 系统提示逐字 | `SystemPrompts.buildSections` | 当前旧 preamble；实现后新文本 |

**变异探针**（各自单跑）：删 `restoredToolNames` 判据、删 `_recordThinkingLevel`、把 cache 默认改回
`streaming`、把诊断去重键改成仅 `message`、把 `lastAssistantText` 的 `stopReason` 门删掉——均须转红。

## 8. 待用户裁决

| 编号 | 问题 | 建议与依据 |
|---|---|---|
| F1 | llama.cpp 默认端口 9931（w1） | **建议 R2 出分母（461/461）**：Java 无 `extensions/llama` 整套扩展；`models.json` 已可接本地 OpenAI 兼容服务，只为默认端口新建扩展收益过低。若用户要求，按 W10b 最小实现。 |
| F2 | SIGTERM/SIGHUP 重复信号强退 | **建议登记 D**：Java 无公开信号 API；`Runtime.addShutdownHook` 不能区分「第一次/重复」。清理仍做，重复强退不移植。 |
| F3 | 选择器键位/渐进选中跨层 | **建议 L5 实施（代码在 TUI）**：C-1/C-5/C-18/C-22 计分在 map/04；L7 只复核 S7/S11 证据、不重复计分。 |
| F4 | `/export` OSC 8→`<a>` | **建议登记 D**：Java 导出无 ANSI 层，工具文本是纯 escape；不新增 SGR/OSC 渲染器。 |
| F5 | 系统提示品牌差 | **建议对齐语气、保留 pi-java 品牌/文档路径**；品牌/路径差继续挂 D77 族，不新增 D 号。 |

## 9. 验收与台账回填

| 步骤 | 内容 |
|---|---|
| 自验证 | `mvn test -pl pi-java-coding-agent -am`、`mvn test -pl pi-java-tui -am`；最后全仓 `mvn clean verify -Dfrontend.skip=true` |
| 地图 | `map/04`：13 行判定回「对齐」并补新证据；新增 7 个新单元行（llama 按 F1）；整块视图 462/462（或 461/461） |
| 汇总 | `docs/05` §1.2 L5 行；`docs/09` 「当前」列与进度段；`docs/04` §0 增加 L5 收口说明 |
| 提交 | 按 200–500 行拆：`feat(coding-agent): add auth login/logout`、`feat(coding-agent): align session cwd and selection restore`、`feat(coding-agent): align replacement lifecycle and diagnostics`、`feat(coding-agent): align extension surfaces and cache notices`、`feat(coding-agent): align package update reporting`、`fix(tui): align selector search keys`、`docs: close L5` |

## 10. 风险与遗留

- **范围大**：W1 与 W3/W4 都是 L 级；建议按 W1 → W3/W4 → W5 → W6/W7 → W8 → W9/W10/W11 的顺序，每步可独立编译。
- **跨层重复计分**：W9 的 TUI 实现不能让 map/05 再记一次；L7 重测时只更新证据。
- **平台窄差**：信号强退（F2）、ANSI 导出（F4）、llama（F1）三项若按建议登记 D/R2，则不追 100% 的
  462 满分；读数以裁决为准。
- **既有注释行号**：Java 代码注释里的 pi 行号大多指向 `56cae7823` 或更早；本批改动点顺带重钉，未改动处
  不在验收范围（与 L4 同口径）。

## 11. 实施记录

**状态（2026-11-23）**：W1–W11 全部落地，`map/04` 记 L5 **461/461＝100%**（462 预期减 1，llama 按 F1／R2 出分母）。
11 笔提交，均在 `5a77fb48` 设计稿之后：

| 序 | 提交 | 工作包 | 内容 | 主要测试 |
|---|---|---|---|---|
| 1 | `d119681f` | W1 | ai：`AuthUrl`/`OAuthEvent` 结构化认证 URL 与凭证列举（`OAuthCredentialStore.IN_FLIGHT`＋`awaitSettled`） | ai 认证/凭证既有套件 |
| 2 | `ff71e4d3` | W1 | coding-agent：`auth login/logout`（`AuthLoginCommand`／`AuthLogoutCommand`／`AuthTerminal`／`RadiusMcpPlan`＋`AuthCommand` 分派；伙伴 Provider 排序；logout 四臂） | `AuthCommandsTest`／`RadiusMcpPlanTest` |
| 3 | `6f19b8d8` | W3 | 会话 cwd 缺失 Continue/Fork/Cancel：`SessionCwd`／`SessionCwdPrompter`／`SessionPersistence.attachOpened`＋tui `SessionCwdPromptRunner`；启动期三臂逐字 | `SessionCwdTest` |
| 4 | `8658dc6e` | W4 | 恢复分支模型/thinking（不可用模型回退 warning）；run 起点记 thinking（`SessionSelectionRestore`／`SessionSelectionRecorder`） | `SessionSelectionRestoreTest` 等 |
| 5 | `992ccdd5` | W5 | 替换臂统一 `rebindSession`；诊断按 `type\0message` 去重＋继承；RPC 空闲 shutdown（`activeCommands` 门）；会话层 reload 双门；`SessionShutdownHook` 首信号清理 | `RpcIdleShutdownTest`／`StartupDiagnosticsDedupTest`／`SessionReloadSessionGateTest` |
| 6 | `7859c0b2` | W2＋W7 | `cacheWarming` 默认 `idle`；暖提醒逐次合并（金额累加）；changelog 只解析版本行；`/copy` 按 `stopReason` 跳过；reasoning 丢弃提示（重复抑制） | `ChatScreenCacheWarmingTest`／`CacheWarmerTest`／`ChangelogSupportTest`／`LastAssistantTextTest`／`ReasoningDropNoticeTest` |
| 7 | `816bae95` | W8 | `pi update` 逐包 `result`（git from→to 7 位）；CLI 四臂汇总；capture 输出（非交互 env／尾部 8000／凭据提示）；git clean 失败非致命 | `PackageUpdateResultTest`／`PackageCommandRunTest` |
| 8 | `e2c8d097` | W6 | 恢复 loadout 只按 transcript 应用 modifier（`restoredToolNames`）；Markdown 六面（USER／ASSISTANT／THINKING／CUSTOM／SUMMARY／CHANGELOG）按注册类型过滤 | `RestoredToolLoadoutTest`／`MarkdownTransformerSurfaceTest` |
| 9 | `c2d6ae89` | W9＋W10 | `SelectList` 光标键（Left/Right/Home/End）只移光标不重过滤；登录 device-code 验证 URL 经 `cli/WebUrl` 清洗 | `SelectListTest` |
| 10 | `edaedc5f` | W11 | 系统提示 preamble／tools 尾句／docs 收句；技能路径提示；`parseCommandArgs` 引号只在词首开、反斜杠转义、未闭合回退；分支摘要应用 `context_edit`；摘要截断走 `sliceHead` | `SystemPromptsTest`／`PromptTemplatesTest`／`BranchSummarizationTest`／`PayloadRecordingStreamFnTest` |
| 11 | `0f7f6fb4` | W2 适配 | cache 相关既有测试按 `idle` 默认调整（测 settled 行为处显式置 `streaming`） | `AgentSessionCacheWarmingTest`／`SessionCommandsCacheWarmingTest`／`SettingsScreenCacheWarmingTest` |

**裁决落实（§8）**：F1 llama.cpp 出分母（461/461）；F2 重复信号强退登记 **D274**；F3 选择器 UX 在 TUI 实施、
map/05 不新增单元、不重复计分；F4 `/export` OSC 8 登记 **D275**；F5 系统提示保留 pi-java 品牌与真实文档路径。

**与设计的差异（登记性）**：

- **W3 会话内手臂**：pi 在 `/resume`／`/import` 命中 cwd 缺失时仍可交互三臂；Java 会话内只走非交互错误臂
  （`Error: Session cwd does not exist: <cwd>`），交互式选择仅在启动期提供 ⇒ **D277**。
  map/04 的 N-1 仍判对齐：计分单元是「启动期 Continue/Fork/Cancel」，会话内形态作窄差处理。
- **W8 capture 消费者**：`GitRunner` capture 面（非交互 env、尾部 8000、凭据提示）已实现并有测试，
  但 TUI 无安装触发点（包安装只在 CLI／包命令路径），故无 TUI 端可观察行为 ⇒ **D278**。
- **W2 行为变化**：`cacheWarming` 默认由 `streaming` 改为 `idle`（与 pi 一致），既有测试需显式置
  `streaming` 才观察 settled streaming 行为；设置页三值循环起点由 `off` 变为 `idle`。
- **W9 行为变化**：选择器搜索框 Left/Right/Home/End 只移动光标、不重跑过滤；输入/退格在光标处编辑。
- **W11 行为变化**：`parseCommandArgs` 只在词首识别开引号，`it's` 类撇号不再吞并后续参数；未闭合引号
  回退为字面引号。
- **未新开 B 号**；D 编号 D276 未用，故新窄差从 D279 起（见 `docs/04` §0）。

**计分与台账**：`map/04` 13 行改回「对齐」并补 L5 证据；新增 N-1..N-7（N-4 llama 出分母）；
`docs/05` §1.2 L5 行 → **461/461＝100%**、合计 **1,318.0/1,396＝94.4%**；`docs/09` §3「当前」列与 §4 进度同步；
`docs/04` §0 增 L5 收口段＋§6.4a 四条窄差。

**测试**：coding-agent 全量 **2,042** 测、TUI 全量 **695** 测绿（W5 后分模块全量；W11 后 TUI 1 例
`SettingsScreenCacheWarmingTest` 失败已由 `0f7f6fb4` 修复并单类复跑绿）。

> **全仓 verify（2026-11-23）**：`mvn clean verify -Dfrontend.skip=true` **BUILD SUCCESS**（16 模块全绿；
> **7,153 测**、0 失败 0 错误、39 跳过）。分模块：coding-agent 2,042／TUI 695／agent-core 998／AI 2,264／
> examples 401／MCP 317／evals 229／telemetry 82／web 72／sqlite 37／protocol 14／server 2。
> 首轮在 SpotBugs 抓到 `AuthLoginCommand.parseRequest` 的 `DLS_DEAD_LOCAL_STORE`（未使用的 `var request`
> 初值），删行后复跑通过。

