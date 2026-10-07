# 包 38：MCP 对齐（B160）——第 11 包：`pi mcp` 非会话 CLI

> **状态：✅ 已闭环（2026-10-08，R-A）。**
> 总包路线见 `docs/28` §3（第 11 行）；上一包 `docs/37`（工具与资源适配）。
> 本包 = pi `extensions/mcp/cli.ts` **614 行**。
> ⚠️ **路线图第 11 行原写「`ui.ts` 252 · `cli.ts` 614」，本文把 `ui.ts` 顺延到第 ⑫ 包（已获准，裁决 A）**
> —— 理由见 §2.1，这是本包**唯一**要你裁决的结构性改动。

---

## 1. 范围边界

**做**：`pi mcp` 的五个子命令（`add` / `remove` / `list` / `login` / `logout`）、
帮助文本、选项解析、退出码，以及它们要的 `openBrowser`。

**不做**（各自归属已核）：

| 不做 | 出处 | 归属 |
|---|---|---|
| **`/mcp` 管理器视图**（`McpManagerView`、`McpUi`、`showMcpManager`） | `ui.ts` 252 | **第 ⑫ 包**（裁决 §2.1） |
| `/mcp` 的**菜单编排**（`manage`、`showTools`、`chooseExposure`、`signInWithUi`、`runAction`） | `index.ts:791-1168` | 第 ⑫ 包（**本来就在 index.ts，不在 ui.ts**） |
| 会话内的 `/mcp` 斜杠命令注册、`mcp_servers` 系统提示段、生命周期 | `index.ts` 其余 | 第 ⑫ 包 |
| `McpServerRegistry` / `pi.registerMcpServer()` | `mcp-servers.ts:281-319` | 第 ⑫ 包（包⑧ 裁决 A） |

---

## 2. 裁决

### 2.1 `ui.ts` 顺延第 ⑫ 包（**请裁决**）

`ui.ts` 的 `McpManagerView` **不是一个独立组件**，它是 pi 扩展 API
`ctx.ui.custom(...)` 的载荷：

```ts
export async function showMcpManager(
	ctx: ExtensionCommandContext,
	manage: (ui: McpUi) => Promise<void>,
): Promise<void> {
	await ctx.ui.custom<void>((tui, theme, keybindings, done) => {
		const view = new McpManagerView(tui, theme, keybindings);
		...
		return view;
	});
}                                                     // ui.ts:237-252
```

**pi-java 没有 `ui.custom` 这个面。** 扩展的 UI 通道是
`ExtensionUI.request(RpcExtensionUIRequest) → RpcExtensionUIResponse`
（`coding-agent/.../extension/ExtensionUI.java:11-14`）—— 一个 **RPC 请求/应答**，
只能 confirm/select/input，**没有「挂载一个自定义组件并接管键盘焦点」**。
pi 的对应物是 `ExtensionUIContext.custom<T>(factory, options?)`
（`core/extensions/types.ts:213-214`），属 **E5 扩展系统域**，pi-java 整块没有。

⇒ 在第 ⑫ 包之前移植 `ui.ts`，等于**在一个跟 MCP 无关的包里凭空发明组件挂载点**，
而且它没有驱动者 —— 菜单编排（`ui.menu/status/redirectUrl` 的 **9 个调用点**）
在 `index.ts`，属第 ⑫ 包。

**三个可选做法**：

| | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | 本包只做 `cli.ts`；`ui.ts` 随 `index.ts` 进第 ⑫ 包 | 第 ⑫ 包要多做 252 行视图 ＋ `ui.custom` 面；但那本来就是它的两半 |
| B | 本包做 `ui.ts` 的视图，落在 `pi-java-tui` 的 `ScreenOverlay` 上（不是 `ui.custom`） | 视图与 pi 的形状**不同源**（`ScreenOverlay` 是 PiTuiApp 驱动的模态屏，`ui.custom` 是任意组件）；将来要么重写要么保留两套 |
| C | 本包做 `ui.ts` 为纯接口（`McpUi`）＋ 内存实现，视图顺延 | 接口有牙（夹具能驱动 `manage` 的假实现），但用户可见的收益为零 |

**推荐 A**。B 的问题不是工作量，是**形状**：`ScreenOverlay` 的
`apply(session, switcher)` 对 `/mcp` 无意义（它不换会话），而 `ui.custom` 的
`done()` 才是它的收尾方式 —— 硬套会把两套模型混在一起。

⚠️ **若你选 B**：本包规模从 614 行变成 866 行，且要新增
`pi-java-tui` → `pi-java-mcp` 依赖（见 §2.2）。

### 2.2 落点：`pi-java-coding-agent` 的 `subcommand/`（与包⑩ 同侧）

`cli.ts` 用 `loadMcpConfig` / `addMcpServerConfig` / `removeMcpServerConfig`
（包⑧）、`McpServerConnection` / `McpSignIn` / `McpOAuthCredentialStore`
（包⑨）、`ProjectTrustStore`（pi-java 的 `SettingsManager.isProjectTrusted`）
—— 全部在 `pi-java-mcp` ＋ agent-core 里，而 `pi-java-mcp` 看不见 agent-core。

⇒ 与包⑩ 同侧：**`com.pijava.coding.agent.subcommand.McpCommand`**。
`SubcommandHandler` 的 `SUBCOMMANDS` 集合加 `"mcp"`、`dispatch` 加一个分支
（`SubcommandHandler.java:12-15,33-40`）。

### 2.3 `openBrowser` 是新增件（pi-java 全树没有）

pi：`utils/open-browser.ts`，`options.openUrl ?? openBrowser`（`cli.ts:544`）。
pi-java 全树 grep **零命中**（无 `Desktop`、无 `xdg-open`）。

⇒ 新增 `com.pijava.coding.agent.subcommand.BrowserLauncher`：
`Desktop.getDesktop().browse(uri)`，headless 或不受支持时回落
`rundll32 url.dll,FileProtocolHandler <url>`（Windows）/
`open <url>`（macOS）/ `xdg-open <url>`（Linux），都失败就只记一行日志
（**不抛** —— pi 的 `openBrowser` 失败也不打断登录，浏览器打不开时用户还能手点链接）。
登记 **B-新**。

### 2.4 选项解析：**逐字移植 pi 的 `parseOptions`，不用 picocli**

pi 的解析器有两条承重语义（`cli.ts:139-182`）：

1. **`--` 结束选项**，其后的**全部**进 positional；
2. **`positional.length >= maxPositionals` 也结束选项** —— 这样
   `pi mcp add <server> <command> --flag` 里 command 自己的 `--flag`
   **原样透传**（`add` 传 `maxPositionals = 2`）。

picocli 表达不了第 2 条（它没有「收够 N 个位置参数就停止解析」的模式）。
⇒ 自建一个 `McpCliOptions`（~90 行），与 pi 的 `ParsedOptions`
（`positional` / `values` / `lists`）同形。登记 **B-新**。

### 2.5 退出码与错误通道

`runMcpCommand` 返回 **0 成功 / 1 失败**（`cli.ts:184-260`）；`log` 走 stdout、
`error` 走 stderr，**两者都可注入**（`McpCommandOptions.log/error`）——
夹具据此断言，不必捕 `System.out`。Java 侧保留这两个注入口。

⚠️ **`list` 的失败判据**（`cli.ts:478`）：
`loaded.errors.length > 0 || reports.some(r => r.enabled && r.state !== "connected")`
——「配置有错」**或**「某个启用的服务器没连上」。JSON 与非 JSON 两条路**都**返回它。

---

## 3. `cli.ts` 逐节设计

### 3.1 帮助文本（`cli.ts:33-75`，逐字）

⚠️ 三处**必须改**的 pi 字面量（pi-java 的命名约定，B182 同族）：

| pi | pi-java |
|---|---|
| `${APP_NAME} mcp …` | `pi-java mcp …` |
| `~/${CONFIG_DIR_NAME}/agent/mcp.json` | `~/.pi-java/agent/mcp.json` |
| `${CONFIG_DIR_NAME}/mcp.json`（项目） | `.pi-java/mcp.json` |

`HELP_HINT = chalk.dim('Use "pi mcp --help" for usage.')` —— Java 无 chalk，
**纯文本**（pi-java 全树不用 ANSI 着色，登记 **B-新**）。

⚠️ 还有一个**藏在输出里**的 pi 字面量：`list` 的空配置提示
（`cli.ts:491`）写死 `.pi/mcp.json` —— pi 自己的 `CONFIG_DIR_NAME` 没走变量插值。
Java 用 `McpConfig.PROJECT_DIR_NAME`。

### 3.2 `add`（`cli.ts:277-394`）

**位置参数与 URL 的二选一**（`cli.ts:308`）：

```ts
	if (!name || (url === undefined) === (command.length === 0)) { error(usage); return 1; }
```

⚠️ `(url === undefined) === (command.length === 0)` 是**同真同假即错**：
「有 url 且无命令」✓、「无 url 且有命令」✓，**恰好一个**才通过。
Java 直写同一个布尔等式。

**HTTP/stdio 专属选项的错位检查**（`cli.ts:317-332`）：

```ts
	const httpOnly = ["header","bearer-token-env-var","oauth-client-id","oauth-client-secret","oauth-callback-port","oauth-client-name"];
	const stdioOnly = ["env", "cwd"];
	const misplaced = (url === undefined ? httpOnly : stdioOnly).find((option) => values.has(option) || lists.has(option));
	if (misplaced) { error(`--${misplaced} only applies to ${url === undefined ? "HTTP servers (--url)" : "stdio servers"}.`); return 1; }
```

⚠️ 文案里的**方向**：给 stdio 传了 HTTP 选项 ⇒ 提示 `only applies to HTTP servers (--url)`；
反之提示 `only applies to stdio servers`。

**HTTP 配置的装配**（`cli.ts:335-351`）：

- `headers` 来自 `--header KEY=VALUE`（可重复，后者覆盖前者）；
- `--bearer-token-env-var NAME` ⇒ `headers.Authorization = "Bearer ${NAME}"`
  —— ⚠️ **写进文件的是字面的 `${NAME}`**（包⑨ 的 `ConfigValueResolver` 在连接时才解析），
  Java 侧要写成 `"Bearer ${" + name + "}"`，**不能**先解析；
- `oauth` 的四个键**逐个按需出现**（`...(x === undefined ? {} : {...})`），
  空对象则整个 `oauth` 键不出现（`Object.keys(oauth).length > 0`）；
- `--oauth-callback-port` 走 `Number(port)` —— ⚠️ JS 的 `Number("abc")` 是 **NaN**，
  会被包⑧ 的校验挡下（`callbackPort must be …`）；Java 的 `Integer.parseInt` 会**抛**，
  必须捕获后按同样的文案拒绝。

**stdio 配置的装配**（`cli.ts:352-362`）：`command` 取 positional[1]，
其余进 `args`；`args` 为空则**不出现**该键；`env` 同理。

**校验**（`cli.ts:366-370`）：`validateMcpServerConfig(name, config)` 返回字符串即错
⇒ Java 的 `McpServerConfigs.validate(name, node)` 返回 `McpConfigValidation`，
`Invalid(message)` 直接 `error(message)`。

**落盘与提示**（`cli.ts:372-393`）：

```
Added|Replaced <scope> MCP server "<name>" in <path>.
[项目未受信时] The project is not trusted, so <path> is ignored until you start pi-java in the project and trust it.
Check it with: pi-java mcp list[. If it requires sign-in: pi-java mcp login <name>]
```

⚠️ **最后一句的条件**（`cli.ts:386-389`）：`"url" in validated` **且** headers 里
没有（大小写不敏感的）`authorization` —— 与包⑨ 的 `usesOAuth` 同判据，
但**这里是另一份实现**（pi 也是两处）。**别合并**（合并会把「有 auth 字段」也算进来，
而这里判的是 headers）。

### 3.3 `remove`（`cli.ts:396-432`）

- 位置参数恰好一个，否则 usage + 1；
- `removeMcpServerConfig(path, name)` 返回 false ⇒ **再看另一个 scope 有没有同名的**
  （`projectTrusted: true` 加载一次），有则追加
  ` It is defined in <source>; use --local` / `; omit --local`；
- ⚠️ 那句后缀按 `other.scope === "project"` 分岔。

### 3.4 `list`（`cli.ts:434-519`）

**`ServerReport` 的十一个字段**（`cli.ts:91-107`）——`override`、`toolExposure`、
`resources`、`error` 都是**按需出现**（`undefined` 不是 `null`）。

**逐服务器流程**（`cli.ts:443-476`）：

1. `enabled === false` ⇒ `state = "disabled"`，**不连接**，直接返回；
2. 否则建连接、`await connection.getClient()`，**catch 住**（状态与错误记在连接上）；
3. `state` 取 `connection.state`；`tools` 取名字列表；
4. **per-tool exposure 的差集**：`getMcpToolExposure(entry.config, tool.name)` 与
   `report.exposure` **不同**才进 `toolExposure`；
5. `hasResources` 为真才填 `resources`/`resourceTemplates`；
6. `state !== "connected"` 且有 `error` 才填 `error`；
7. **每个都 `close()`**。

⚠️ **`Promise.all` 是并发**的（`cli.ts:442`）—— 与包⑨ 的
`connectOnce` 顺序拉取不同，这里所有服务器**同时**连。Java 用虚拟线程并发。

**JSON 输出**（`cli.ts:480-489`）：
`{ servers, errors, note? }`，`JSON.stringify(..., null, 2)` ⇒ 用包⑧ 的 `JsJson`
（**不是** Jackson 的 pretty printer，`" : "` 对不上）。`note` 只在未受信时出现。

**人类输出**（`cli.ts:493-518`）逐行：

```
<name>: <state> (<exposure>, <scope>)
  <transport>
  [project override: <path>]
  [sign in with: pi-java mcp login <name>]
  [tools: a, b [deferred], c]
  [resources: <n>, URI templates: <m>]
  [<error，多行时逐行缩进两格>]
config error: <...>        （每条一个）
<untrustedNote>
```

- `state` 的三种改写（`cli.ts:494-499`）：`connected` ⇒
  `connected, N tool`/`connected, N tools`（**单数 = 1 时**）；
  `needs-auth` ⇒ `needs sign-in`；其余原样；
- `tools` 行里 exposure 与服务器不同的工具加 ` [<exposure>]`；
- `transport` 是 URL 或 `[command, ...args].join(" ")`。

### 3.5 `login` / `logout`（`cli.ts:218-255` 与 `521-614`）

**`logout`**：`credentials.remove(name, url)` ⇒
`Signed out of MCP server "<name>".` / `No stored credentials for MCP server "<name>".`
**两条都返回 0**。

**非 OAuth 服务器**（`cli.ts:235-239`）：`connection.oauthUrl` 为空 ⇒
`MCP server "<name>" does not use OAuth. Only HTTP servers without an Authorization header do.` + 1。

**`login` 的三步**：

1. **先连一次**（`cli.ts:533-542`）：连上 ⇒ `Already signed in to MCP server "<name>" (N tools).` + 0；
   连不上且 `state !== "needs-auth"` ⇒
   `MCP server "<name>" failed to connect: <error ?? "unknown error">` + 1；
2. `signInMcpServer`（包⑨ 的 `McpSignIn`），prompt 的两件事：
   - `showAuthorizationUrl` ⇒ **打印一行**（`Sign in to MCP server "<name>" in your browser:\n<url>`）
     ＋ `openUrl(url)`；
   - `promptForRedirectUrl` ⇒ `waitForRedirectUrl(...)`（§3.6）；
3. 失败 ⇒ `McpSignInCancelledError` 时
   `Sign-in to MCP server "<name>" was cancelled or not completed within <秒> seconds.`，
   否则 `Sign-in to MCP server "<name>" failed: <消息>`；**都返回 1**；
4. 成功后清 `challenge`、`reconnect()`，失败 ⇒ `Signed in, but <消息>` + 1；
5. 成功 ⇒ `Signed in to MCP server "<name>" (N tools).` + 0。

⚠️ **`finally { await connection.close(); }`**（`cli.ts:252-254`）——login/logout 两条路都关连接。

### 3.6 `waitForRedirectUrl`（`cli.ts:579-614`）

```ts
	const interactive = process.stdin.isTTY === true && options.openUrl === undefined;
	...
	if (!interactive) {
		await new Promise<void>((resolve) => controller.signal.addEventListener("abort", () => resolve(), { once: true }));
		return undefined;
	}
	const readline = createInterface({ input: process.stdin, output: process.stderr });
	try { return await readline.question("If the browser cannot reach this machine, paste the URL it was redirected to: ", { signal: controller.signal }); }
	catch { return undefined; } finally { readline.close(); }
```

⚠️ **四条承重**：
1. `interactive` 判据是 **`stdin.isTTY === true` 且 没人注入 `openUrl`**
   —— 注入了 `openUrl`（测试、宿主）就**不读 stdin**；
2. 非交互 ⇒ **只等 abort**（浏览器回调到了 / 超时），返回 `undefined`；
3. 超时是 `setTimeout(abort, timeoutMs)`，**默认 300 秒**（`DEFAULT_LOGIN_TIMEOUT_SECONDS`），
   由 `--timeout` 覆盖，且 `!Number.isFinite(timeout) || timeout <= 0` 时先拒；
4. 提示语写到 **stderr**（不是 stdout）。

**Java 侧的三处改动**（登记 **B-新**）：
- `process.stdin.isTTY` ⇒ `System.console() != null`（⚠️ 与「stdin 重定向」的语义**不完全等价**，
  Java 没有 `isatty`）；
- readline 的 `{ signal }` ⇒ Java 无对应物：用虚拟线程读 `BufferedReader`，
  主线程等待 `abort` 或读到一行，**谁先到算谁**，超时/中止时 `close()` 输入流；
- `Number.isFinite` ⇒ `Double.isFinite(Double.parseDouble(...))`（catch 掉 `NumberFormatException`）。

---

## 4. 文件清单（预估）

`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/subcommand/`：

| 文件 | 对应 pi | 预估行 |
|---|---|---:|
| `McpCommand.java` | `runMcpCommand` `add` `remove` `login` `logout`（`cli.ts:184-260,277-432,521-577`） | 380 |
| `McpListCommand.java` | `list` ＋ `ServerReport`（`cli.ts:91-107,434-519`） | 220 |
| `McpCliOptions.java` | `parseOptions` ＋ `parsePairs`（`cli.ts:129-182,262-275`） | 130 |
| `McpCliHelp.java` | `HELP` ＋ `HELP_HINT`（`cli.ts:33-75`） | 70 |
| `BrowserLauncher.java` | `utils/open-browser.ts`（pi-java 新增件） | 70 |
| `McpRedirectPrompt.java` | `waitForRedirectUrl`（`cli.ts:579-614`） | 110 |

改动：`SubcommandHandler.java`（`SUBCOMMANDS` ＋ `dispatch` 两处各一行）。

`pi-java-coding-agent/src/test/java/.../subcommand/`：6 个夹具。
预计主源 **≈ 980 行 / 6 文件**、测试 **≈ 900 行 / 6 夹具**。

---

## 5. 测试计划

| 夹具 | 覆盖 |
|---|---|
| `McpCliOptionsTest` | `--` 结束选项、`maxPositionals` 停止解析、别名 `-l`、未知选项文案、缺值文案、`list` 重复累加、`flag` vs `value`、`parsePairs` 的 `=abc`/`abc` 两拒 |
| `McpAddCommandTest` | 二选一判据的三态、HTTP/stdio 错位文案两向、`--bearer-token-env-var` 写成 `${NAME}`、`oauth` 逐键出现、`--oauth-callback-port` 非数字、`env`/`args` 为空不出现键、校验失败原样透出、`Added`/`Replaced`、未受信提示、结尾提示的两态 |
| `McpRemoveCommandTest` | usage、`Removed`、没找到时的「另一个 scope」两向提示 |
| `McpListCommandTest` | `disabled` 不连接、`connected, 1 tool` 与 `N tools`、`needs sign-in` 与 login 提示、per-tool exposure 差集、resources 两行、error 多行缩进、`config error:`、JSON 形状与 `note`、失败判据与退出码（**两条路都测**） |
| `McpLoginCommandTest` | 已登录、连不上且非 needs-auth、非 OAuth 服务器、取消文案（含秒数）、成功后的 `reconnect`、`finally` 关连接 |
| `McpCliHelpTest` | 帮助文本含五个子命令、`--help`/`-h`/`help`/无参四条路都打帮助并返回 0 |
| `McpRedirectPromptTest` | 非交互只等 abort；交互读到一行即返回；超时返回 undefined；中止返回 undefined |

---

## 6. 变异探针计划（10 个）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `parseOptions` 的 `positional.length >= maxPositionals` 删掉 | 1 |
| M2 | `--` 分支的 `index + 1` 改成 `index` | 1 |
| M3 | `add` 的二选一判据改成 `!name \|\| url === undefined \|\| command.length === 0` | 1 |
| M4 | `bearer-token-env-var` 直接写 `Bearer <NAME>`（先解析） | 1 |
| M5 | `httpOnly`/`stdioOnly` 的错位文案方向对调 | 1 |
| M6 | `list` 的 `failed` 去掉 `r.enabled &&` | 1 |
| M7 | `tools` 的单复数判断 `=== 1` 改 `<= 1` | 1 |
| M8 | per-tool exposure 差集改成全部列出 | 1 |
| M9 | `logout` 没凭据时返回 1 | 1 |
| M10 | `waitForRedirectUrl` 的 `interactive` 去掉 `&& options.openUrl === undefined` | 1 |

---

## 7. 台账影响（预计新增）

| # | 条目 |
|---|---|
| B210 | `pi mcp` 的选项解析是自建的（pi 的 `parseOptions` 有「收够 N 个位置参数即停止」语义，picocli 表达不了） |
| B211 | `openBrowser` 是 pi-java 新增件（pi `utils/open-browser.ts`），headless 下静默失败 |
| B212 | `waitForRedirectUrl` 的 `isTTY` 用 `System.console() != null` 替代；readline 的 `signal` 用虚拟线程 + abort 替代 |
| B213 | 帮助与提示文本里的 `pi`/`.pi` 字面量改成 `pi-java`/`.pi-java`（B182 同族）；`chalk.dim` 去掉（pi-java 不做 ANSI 着色） |
| B214 | `ui.ts` 的 `/mcp` 管理器视图顺延第 ⑫ 包（`ui.custom` 面不存在；菜单编排本来就在 `index.ts`） |

回填 `docs/map/04` 的 G-4（`/mcp` 子命令落地后从「存疑」再前进一格）在本包闭环后做。

---

## 8. 实施记录

（待填：commit / 文件数 / 测试数 / 探针命中 / 发现）
## 8. 实施记录

**提交**：`677b04c4`（11 一次落完：主源 8 文件 ＋ 3 处跨包小缝 ＋ 7 夹具）。

**规模**（实测）：`subcommand/` 8 个新文件（`McpCommand` 269、`McpListCommand` 255、
`McpAddRemoveCommand` 200、`McpCliOptions` 138、`McpCommandSupport` 95、
`McpRedirectPrompt` 94、`BrowserLauncher` 77、`McpCliHelp` 73）；测试 7 夹具
（含一个**真子进程**的 `StdioMcpFixture`）。`pi-java-coding-agent` 402 → **474**、
`pi-java-mcp` 246 → **252**。

**验证**：新文件 checkstyle **0 警告**；`spotbugs:check` 两模块 0；全 reactor 见 §8.6。

### 8.1 探针（10/10 有牙）

| # | 变异 | 命中 |
|---|---|---|
| M1 | `parseOptions` 的 `positional.size() >= maxPositionals` 删掉 | 恰 1 |
| M2 | `--` 分支的 `index + 1` 改 `index` | 恰 1 |
| M3 | `add` 的二选一判据削成 `name == null` | 5 |
| M4 | `--bearer-token-env-var` 写解析后的值 | 恰 1 |
| M5 | 错位提示的两个方向对调 | 2 |
| M6 | `list` 的 `failed` 去掉 `report.enabled() &&` | 恰 1 |
| M7 | 工具数单复数 `== 1` 改 `<= 1` | **首轮 0 红** → 见 §8.2 |
| M8 | per-tool exposure 差集改成全部列出 | 恰 1 |
| M9 | `logout` 没凭据时返回 1 | 恰 1 |
| M10 | `interactive` 去掉 `&& options.openUrl() == null` | **首轮 0 红** → 见 §8.3 |

### 8.2 M7 的零红**不是**等价变异 —— 是夹具缺口

`connected` 状态下 `count` 至少是 1 吗？不是：一台服务器可以**连上但零工具**。
我的 `StdioMcpFixture` 恒返一个工具 ⇒ `== 1` 与 `<= 1` 在**可达状态集内**同值。
⇒ 给 fixture 加一个 `no-tools` 参数，新增
`aConnectedServerWithNoToolsUsesThePlural`（断言 `connected, 0 tools`）⇒ 变异立刻恰 1 红。
**教训：零红要先问「这个状态夹具造得出来吗」，再问「是不是等价」。**

### 8.3 M10 的零红是**探针打错了地方**

`interactive` 原本是 `McpCommand.login` 里的一行表达式
（`options.terminal() && options.openUrl() == null`），`McpRedirectPromptTest` 够不到它。
⇒ 提成纯谓词 `McpCommandSupport.interactive(options)` 并直接钉四态
（终端与否 × 注入 openUrl 与否）⇒ 变异恰 1 红。
**教训（第四次同形）：探针红不了时先确认它打的对象真的是生产代码的那一处。**

### 8.4 包⑨ 的 `FileAuthJsonBackend`：**文件后端从没被跑过**

本包是 `mcp-auth.json` 的**第一个真消费者**，一跑就撞：

```
java.io.IOException: 另一个程序已锁定文件的一部分，进程无法访问。
    at FileAuthJsonBackend.withLock(FileAuthJsonBackend.java:76)
```

根因：`withLock` 用 `FileChannel.tryLock()` 锁住**整个文件**（`[0, MAX]`），
随后用 `Files.readString(path, …)` 读它 —— 那是**另一个句柄**。
Windows 的字节区间锁对**同进程的其它句柄同样生效**，读被锁区间直接 ERROR_LOCK_VIOLATION
（POSIX 的咨询锁不挡读，所以这个缺陷只在 Windows 可见，而 pi-java 就活在 Windows 上）。

修法：读走**持锁的那个 channel**（`readAll(channel)`），不另开句柄。

⚠️ **为什么包⑨ 没抓到**：它的 `McpOAuthCredentialStoreTest` 用的是
`InMemoryAuthJsonBackend`，文件后端**一次都没被实例化过**。⇒ 补
`FileAuthJsonBackendTest`（6 条：建文件/读写往返/不写回时不截断/短内容不留旧尾/8 线程串行/整 store 落盘往返）。
登记 **B215**。

### 8.5 跨包开的三处小缝（都是「pi 有、Java 藏起来了」）

| 出处 | 事实 | 处置 |
|---|---|---|
| `McpServerConnection.oauthSettings()` | 包⑨ 写成包私有；pi 是 `public`（`runtime.ts:232`） | 改 public |
| `McpServerConnection.challenge` | pi 直接 `connection.challenge = undefined`（`cli.ts:568`）；Java 无写入口 | 新增 `clearChallenge()` |
| `McpServerState` | 没有 wire 词，而 CLI 要打 `needs-auth` | 新增 `wire()`（`name()` 小写 + `_`→`-`） |
| `SettingsManager` | 只能为**进程自己**的目录解析项目信任（`load(Boolean)` 用默认 storage） | 新增 `load(agentDir, projectDir, override)` 重载 |

登记 **B216**。

### 8.6 全 reactor

`mvn clean verify`：**15/15 模块 SUCCESS，6:46**；模块汇总行求和 **3,136**（上包 3,058 ＋ 78 ＝ coding-agent +72 ＋ mcp +6）。

### 8.7 另外三处实测/语言坑

1. **`var name = cond ? null : x;` 编译不过** —— javac 报「无法推断本地变量 name 的类型」
   （含 `null` 字面量的三态表达式配 `var` 推不出）⇒ 显式写 `String name`。
   ⚠️ 从 TS 直译过来的三元式要留意这一条。
2. **`--` 之后的选项是「命令自己的」**：`add docs -- npx --env A=1` 里 `--env` 是
   positional（命令的参数），**不参与** HTTP/stdio 错位检查。我第一版夹具写反了方向，
   改成 `add --header A=1 docs npx`（选项在位置参数之前）才测到错位分支。
3. **SpotBugs `RV_RETURN_VALUE_IGNORED`**（`CountDownLatch.await` 的返回值被忽略）
   ⇒ 又一次**重构掉而不是排除**：去掉 latch，非交互路直接 `answer.join()` —— 更短，且
   与交互路同一个「谁先到算谁」的 future。
