# 包 36：MCP 对齐（B160）——第 9 包：连接运行时（log / runtime / oauth 胶水）

> **状态：✅ 已闭环（2026-10-08，R-A）。**
> 总包路线见 `docs/28` §3（第 9 行）；上一包 `docs/35`（mcp.json 配置）。
> 本包 = pi `extensions/mcp/log.ts` 69 ＋ `runtime.ts` 474 ＋ `oauth.ts` 532
> ＝ **1,075 行 TS**，外加它带出的三个**依赖移植**（`core/resolve-config-value.ts`
> 287、`core/auth-storage.ts` 的锁后端子集、`ai/utils/oauth-page.ts` 109）。
> 实施记录见 §11（四个提交 9a–9d，主源 20 文件、测试 8 夹具）。

---

## 1. 范围边界

**做**：把「配置」变成「活连接」。

- `McpServerLog`——`notifications/message` 落 `mcp.log`，5 MiB 轮转；
- `McpServerConnection`——连接状态机、lazy 重连、传输工厂、超时、根目录（roots）、
  工具/资源列表刷新、`stderr` 尾部错误、瞬态错误重试、`session expired` 重放；
- OAuth 胶水——`mcp-auth.json` 凭据 store（按 `name|url` 键 + legacy 键接管）、
  带刷新锁的 `AuthProvider`、`/mcp` 登录编排（回调服务器 + PKCE + 手动粘贴回退）；
- `resolve-config-value` 的 `${NAME}` / `!cmd` 值解析（env / headers / clientSecret）。

**不做**（归属已核）：

| 不做 | 出处 | 归属 |
|---|---|---|
| `tools.ts` / `resources.ts`（`McpToolCaller`、`McpResourceServer`、`isMcpAppResource`、三工具） | `extensions/mcp/tools.ts` 339 · `resources.ts` 342 | **第 10 包**（见 §2.6 的接口前移裁决） |
| `/mcp` 管理器 TUI、`pi mcp` CLI | `ui.ts` 252 · `cli.ts` 614 | 第 11 包 |
| `ExtensionFactory` 接线、`mcp_servers` 提示段、会话生命周期 | `index.ts` 1,225 | 第 12 包 |
| `McpServerRegistry` / `pi.registerMcpServer()` | `mcp-servers.ts:281-319` | 第 12 包（`docs/35` §2.4 裁决 A） |
| codemode 激活路径 | — | B161 闭环前不可达（**B177**） |
| `auth.json` / `auth-oauth.json` 自身的 `${VAR}` 解析 | `core/auth-storage.ts:263,446` | 非 MCP 面，另案 |

⚠️ 与包⑧同：本包**仍无生产消费者**（第 10–12 包接线）。这是路线的固有次序。

---

## 2. 裁决

### 2.1 落点：新子包 `com.pijava.mcp.runtime`

`pi-java-mcp` 沿用包⑧先例（`extensions/mcp/config.ts` ＋ `core/mcp-servers.ts`
→ `com.pijava.mcp.config`）：pi 的 `extensions/mcp/{log,runtime,oauth}.ts` 三个文件
一起落 **`com.pijava.mcp.runtime`**。

为什么不落 coding-agent：`McpServerConnection` 只依赖 `McpClient`＋传输＋oauth
（全在 `pi-java-mcp` 内），落 coding-agent 会让「MCP 运行时」与「MCP 库」跨模块
拆开，而 coding-agent 又已反向依赖 mcp。`pi-java-mcp` 仍只依赖 `pi-java-ai`＋JDK＋Jackson。

### 2.2 `stepUpScope` 提升为 public（**忠实**，非放宽）

`oauth.ts:32` 从 `@earendil-works/pi-mcp/oauth` **公开导出**里 import `stepUpScope`；
pi-java 侧它是 `OAuthEndpoints` 的包私有静态方法（`OAuthEndpoints.java:156`）。
Java 包与 TS 目录不是一回事：把它改成 `public` 是**还原 pi 的公开面**。
本包只动这一个方法的可见性（`OAuthProviders` 同包调用处不变）。

### 2.3 客户端身份（`clientName` / `clientVersion`）由调用方注入，模块内零字面量

pi：`clientInfo = { name: "pi", version: VERSION }`（`runtime.ts:373-378`），
`VERSION`/`APP_NAME` 来自 `config.ts:540,543`。pi-java 的 `Version.VERSION`
在 **coding-agent**（`cli/Version.java:10`），`pi-java-mcp` 看不见它。

**裁决**：`McpServerConnection` 与 `McpAuthProviders` 都把 `clientName` /
`clientVersion` 作为**必填构造输入**，模块内**不写任何默认字面量**。第 12 包接线时
传 `"pi-java"` / `Version.VERSION`；夹具各自传 `"test"` / `"0"`。
好处：无「同版本号两处字面量」的漂移面（`Version.java` 是唯一真相）。
登记 **B190**。

### 2.4 命令执行：可注入 `CommandRunner`，默认走 JDK shell

pi 的 `!cmd` 解析在 win32 上是两段式（`resolve-config-value.ts:198-206`）：
先按 settings 的 `shellPath` 跑（`executeWithConfiguredShell`），
`ENOENT` 才回落默认 shell（`executeWithDefaultShell`，即 Node `execSync`：
POSIX `/bin/sh -c`、Windows `cmd.exe /d /s /c`）。

pi-java 的等价物是 agent-core 的 `DefaultShellExecutor`
（`agent/tool/DefaultShellExecutor.java:1-30`，注释明写它对齐 `getShellConfig`），
但 **mcp 模块不能依赖 agent-core**（依赖方向所限）。

**裁决**：`ConfigValueResolver` 接一个 `@FunctionalInterface CommandRunner`
（`String run(String command) throws Exception`），默认实现 `JdkShellRunner`
＝ POSIX `/bin/sh -c`、Windows `cmd.exe /d /s /c`（**Node `execSync` 的默认**）。
第 12 包接线时可注入包 `DefaultShellExecutor` 的适配器，从而认 `settings.shellPath`。
本包的行为等价于「未配置 shellPath」的 pi。登记 **B188**。

### 2.5 `mcp-auth.json` 后端：自带锁，不复用 ai 模块的私有锁

pi 的 store 建立在 `AuthStorageBackend`（`auth-storage.ts:41-47`）上，其
`FileAuthStorageBackend.withLock` 用 `proper-lockfile`（`auth-storage.ts:96-114`）。
pi-java 的 `FileCredentialStore`/`OAuthCredentialStore`（`pi-java-ai`）各有**私有**
文件锁实现，未抽公共件。

**裁决**：本包自带一个最小后端 `AuthJsonBackend`，语义对齐 pi 的
`LockResult`（`{result, next}`，`next === undefined` ⇒ 不写）：
- `FileChannel.lock()` ＋ **进程内 per-path 锁**（`FileChannel` 的锁是 JVM 级的，
  同 JVM 第二次加锁抛 `OverlappingFileLockException` 而非阻塞——`OAuthCredentialStore.java:123`
  已踩过，必须照做）；
- 文件创建 mode `0600`（pi `AUTH_FILE_WRITE_OPTIONS`，`auth-storage.ts:25`）；
- 另有 `InMemoryBackend`（fixture 用，对应 pi `InMemoryAuthStorageBackend`）。
不抽公共件、不动 `pi-java-ai`（避免为两行锁引入跨模块重构）。

### 2.6 `McpToolCaller` / `McpResourceServer` 前移到本包

pi 里 `McpServerConnection implements McpToolCaller, McpResourceServer`
（`runtime.ts:155`），两个接口却在第 10 包的文件里（`tools.ts:78-80`、
`resources.ts:39-47`），且都是**只有方法签名的窄接口**。
本包必须实现它们，否则 `implements` 子句无处落脚。

**裁决**：两个接口落本包（`McpToolCaller.java`、`McpResourceServer.java`，
逐字对齐 pi 的成员），第 10 包只落**适配器与工具定义**，不重复定义接口。
`isMcpAppResource`（`resources.ts:50-53`）是 `fetchResources` 的滤网
（`runtime.ts:149-150`）⇒ 一并落本包。

### 2.7 网页渲染落 `pi-java-ai/utils/OAuthPage`

`oauth.ts:439-443` 的 `renderPage` 调 `oauthSuccessHtml`/`oauthErrorHtml`
（`@earendil-works/pi-ai/utils/oauth-page`）。pi 的家在
`packages/ai/src/utils/oauth-page.ts`（109 行）⇒ 落到
`pi-java-ai/src/main/java/com/pijava/ai/utils/OAuthPage.java`（pi-java-mcp 依赖 ai，可用）。
当前 `pi-java-ai` 的 OAuth 面**没有**这个文件（全树 grep 零命中），是新增。
登记 **B191**（pi-java 侧同样有 `ai/auth/OAuthFlow` 等登录流，本包只落 HTML，不接线）。

### 2.8 日志/错误文案：逐字对齐，含缩进与截断

- `formatMcpLogMessage` 的换行缩进是 **4 个空格**（`log.ts:30` `"\n    "`）；
- `stderr` 尾部截断是 **2,000 字符**（`runtime.ts:48`），取**尾**；
- 轮转阈值 **5 MiB**（`log.ts:10`），且轮转前**再查一次**当前大小
  （`log.ts:52`，多进程可能已轮转）——这一步是承重的，见 §5.2 探针 M3。

---

## 3. 文件清单（预估）

`pi-java-mcp/src/main/java/com/pijava/mcp/runtime/`：

| 文件 | 对应 pi | 预估行 |
|---|---|---:|
| `McpServerLog.java` | `log.ts` 全文（69） | 140 |
| `McpServerState.java` | `runtime.ts:56` | 25 |
| `McpTransportFactory.java` | `runtime.ts:58-62` | 25 |
| `McpServerConnection.java` | `runtime.ts:155-474` | 470 |
| `McpConnectionOptions.java` | `runtime.ts:183-195` | 90 |
| `McpDefaultTransport.java` | `runtime.ts:89-121` | 110 |
| `McpToolCaller.java` | `tools.ts:78-80` | 25 |
| `McpResourceServer.java` | `resources.ts:39-47` | 45 |
| `McpAppResources.java` | `resources.ts:50-53` | 30 |
| `ConfigValueResolver.java` | `resolve-config-value.ts` 全文（287） | 300 |
| `CommandRunner.java` ＋ `JdkShellRunner.java` | `utils/shell.ts` 子集 | 90 |
| `AuthJsonBackend.java` | `auth-storage.ts:19-114` 子集 | 170 |
| `McpOAuthSettings.java` | `oauth.ts:55-70` | 60 |
| `McpOAuthCredentialStore.java` | `oauth.ts:111-221` | 190 |
| `McpAuthProvider.java`（接口） | `oauth.ts:284-287` | 30 |
| `McpAuthProviders.java`（工厂） | `oauth.ts:302-363` | 130 |
| `McpSignIn.java` ＋ `McpSignInPrompt.java` ＋ `McpSignInCancelledError.java` | `oauth.ts:365-381, 456-532` | 260 |
| `OAuthCallbackSettings.java` ＋ `OAuthRedirects.java` | `oauth.ts:72-103, 223-260, 385-450` | 250 |

`pi-java-ai/src/main/java/com/pijava/ai/utils/OAuthPage.java`（新，120 行）。
预计主源 **≈ 2,500 行 / 20 文件**、测试 **≈ 1,300 行 / 8 文件**。

---

## 4. `log.ts` 全文设计（69 → `McpServerLog`）

### 4.1 pi 逐字

```ts
const MAX_LOG_BYTES = 5 * 1024 * 1024;                       // log.ts:10

export function formatMcpLogMessage(server: string, params: unknown, now: Date = new Date()): string {
	const message = isRecord(params) ? params : { data: params };
	const level = typeof message.level === "string" ? message.level : "info";
	const logger = typeof message.logger === "string" && message.logger ? ` ${message.logger}:` : "";
	const text = formatData(message.data).replace(/\r?\n/g, "\n    ");
	return `${now.toISOString()} [${server}] ${level}${logger} ${text}\n`;
}                                                            // log.ts:26-32
```

```ts
	write(server: string, params: unknown): void {
		const line = formatMcpLogMessage(server, params);
		try {
			if (this.size === undefined) {
				mkdirSync(dirname(this.path), { recursive: true });
				this.size = this.currentSize();
			}
			if (this.size > MAX_LOG_BYTES) {
				// Another process may have rotated it already; check before renaming.
				if (this.currentSize() > MAX_LOG_BYTES) renameSync(this.path, `${this.path}.1`);
				this.size = this.currentSize();
			}
			appendFileSync(this.path, line);
			this.size += Buffer.byteLength(line);
		} catch {
			// Ignore: the log is best effort.
		}
	}                                                        // log.ts:43-60
```

### 4.2 要点（逐条对齐，别合并）

1. `params` 非 record ⇒ 包成 `{data: params}`（`log.ts:27`）。**JSON 的数组也是
   `typeof "object"`，但 `Array.isArray` 排除** ⇒ Java 判「`ObjectNode`」即可。
2. `level` 只认 **string**，缺省 `"info"`；`logger` 要求**非空串**才加
   `" <logger>:"`（空串不加，`log.ts:29`——`&& message.logger` 是承重的）。
3. `formatData`：string 原样；否则 `JSON.stringify(data) ?? String(data)`，
   **抛异常时**回落 `String(data)`（`log.ts:16-23`；JS 的循环引用会抛）。
4. 换行替换 `/\r?\n/g` → `"\n    "`（4 空格）。⚠️ CRLF 被吞成 `\n`，
   **只缩进不补齐**。
5. `toISOString()` 恒为 UTC `YYYY-MM-DDTHH:mm:ss.sssZ` **三位毫秒**——
   `Instant.toString()` 会在毫秒为 0 时丢掉 `.000` ⇒ 必须自建格式化器。
6. 轮转：`this.size > MAX`（**严格大于**，不是 `>=`）；`rename` 前**再查**一次
   当前大小（`log.ts:52`）；重命名为 `<path>.1`（**覆盖**已存在的 `.1`）。
7. `size` 是**内存缓存**：首次 `write` 时惰性 stat；此后只 `+=` 新行字节数。
8. 全程 `catch {}` 吞掉——**日志绝不能打断工具**（`log.ts:57`）。
   唯一例外：**首次** `mkdirSync` 的异常也被吞（在内层 try 里）。

### 4.3 Java 待写

```java
package com.pijava.mcp.runtime;

/** `notifications/message` 落盘（log.ts 全文）。写失败一律吞掉。 */
public final class McpServerLog {

    private static final long MAX_LOG_BYTES = 5L * 1024 * 1024;

    /** `YYYY-MM-DDTHH:mm:ss.SSSZ`，恒三位毫秒（`Date.toISOString`）。 */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private final Path path;
    private @Nullable Long size;   // 未初始化 = 首次 write 时 stat

    /** @param path 日志文件（`<agentDir>/mcp.log`） */
    public McpServerLog(Path path) {
        this.path = path;
    }

    /** 一行日志文本（log.ts:26-32）。 */
    public static String formatMessage(String server, Object params, Instant now) {
        ...
    }

    /** 追加一行；任何失败都忽略（log.ts:43-60）。 */
    public void write(String server, Object params) {
        var line = formatMessage(server, params, Instant.now());
        try {
            if (size == null) {
                var parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);   // ⚠️ B185 同款 NPE 面
                size = currentSize();
            }
            if (size > MAX_LOG_BYTES) {
                if (currentSize() > MAX_LOG_BYTES) {
                    Files.move(path, Path.of(path + ".1"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                size = currentSize();
            }
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size += line.getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private long currentSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
```

⚠️ 两条 Java 侧必须注意：
- `formatMessage` 的 `params` 走 Jackson：`ObjectNode` ⇒ 用 `get("level")`
  且**必须是 `isTextual`**；`isArray()`/`isTextual()` 之外的标量包成 `{"data": …}`。
  `data` 的 `JSON.stringify` 用**紧凑写法**（`JsonNode.toString()`），
  **不能用** `JsJson`（那是缩进写法）。
- 对**非 JSON** 的 `params`（Java 侧 `McpClient.onNotification` 给的是 Jackson 树），
  实际只有 `ObjectNode` 一种，`formatData` 的字符串分支只在 `data` 是 `TextNode` 时命中。

---

## 5. `runtime.ts` 设计（474 → `McpServerConnection` 等）

### 5.1 常量与自由函数（逐字）

```ts
const DEFAULT_TIMEOUT_SECONDS = 60;                          // runtime.ts:47
const STDERR_TAIL_CHARS = 2_000;                             // runtime.ts:48
/** Delays between attempts to connect to an HTTP server that failed with a transient error. */
const CONNECT_RETRY_DELAYS_MS = [250, 1_000];                // runtime.ts:50

type ServerState = "connecting" | "connected" | "disconnected" | "needs-auth" | "failed" | "closed";  // :56

/** Network failures and overloaded or restarting servers, which are worth another attempt. */
function isTransientError(error: unknown): boolean {
	if (error instanceof McpHttpError) {
		return error.status === 408 || error.status === 429 || (error.status >= 500 && error.status !== 501);
	}
	return error instanceof TypeError;
}                                                            // :69-74

/** HTTP servers authenticate with OAuth unless the config supplies an `Authorization` header or `auth`. */
function usesOAuth(entry: McpServerEntry): boolean {
	const { config } = entry;
	if (!("url" in config) || config.auth) return false;
	return !Object.keys(config.headers ?? {}).some((header) => header.toLowerCase() === "authorization");
}                                                            // :82-86

/** `~` and `~/…` (also `~\…` on Windows) name the home directory, like in a shell. */
function expandHome(value: string): string {
	if (value === "~") return homedir();
	if (value.startsWith("~/") || (process.platform === "win32" && value.startsWith("~\\"))) {
		return join(homedir(), value.slice(2));
	}
	return value;
}                                                            // :89-95
```

**Java 侧陷阱（逐条）**：

1. `ServerState` 六态是纯常量闭集 ⇒ `enum McpServerState`
   （CONNECTING / CONNECTED / DISCONNECTED / NEEDS_AUTH / FAILED / CLOSED）。
2. `isTransientError` 的 `error instanceof TypeError` 分支 **Java 无对应物**：
   JS 的 `fetch` 网络失败抛 `TypeError`。JDK `HttpClient.send` 抛的是
   `IOException`/`ConnectException`/`HttpConnectTimeoutException`（都是
   `IOException` 子类）。⇒ Java 判 **`error instanceof IOException`**，
   但**必须排除** `McpHttpError`（它已在上支处理，且**不是** `IOException`，
   是 `RuntimeException`）与非瞬态的 `IOException` 子类。
   ⚠️ 这一条是**行为等价性缺口**，登记 **B192**：
   pi 的 `TypeError` 只覆盖 fetch 层网络错，Java 的 `IOException` 范围更宽
   （含 TLS/重定向策略/中断）。已知窄角。
3. `usesOAuth` 的 `header.toLowerCase()` ⇒ Java `toLowerCase(Locale.ROOT)`
   （土语问题，包⑧已踩过）。
4. `expandHome` 的 win32 分支（`~\`）在 Java 侧**恒开**（`process.platform === "win32"`
   是运行期常量，pi-java 就活在 Windows 上；但为跨平台正确，按
   `System.getProperty("os.name")` 判——**且要认 `~\\` 与 `~/` 两者**）。

### 5.2 传输工厂（`runtime.ts:97-121`）

```ts
export function createDefaultTransport(entry, cwd, authProvider): McpTransport {
	const { config, name } = entry;
	if ("url" in config) {
		return new StreamableHttpTransport({
			url: config.url,
			headers: resolveHeadersOrThrow(config.headers, `MCP server "${name}"`),
			authProvider,
		});
	}
	const env: Record<string, string> = {};
	for (const [key, value] of Object.entries(config.env ?? {})) {
		env[key] = resolveConfigValueOrThrow(value, `MCP server "${name}" env "${key}"`);
	}
	return new StdioTransport({
		command: expandHome(config.command),
		args: config.args?.map(expandHome),
		cwd: resolve(cwd, expandHome(config.cwd ?? ".")),
		env,
		stderr: "pipe",
	});
}
```

⚠️ Java 侧两个承重点：
- **`args?.map`**：`args` 缺省时**不传** `args`（pi 的 `StdioTransportOptions.args`
  可选）。Java 的 `StdioTransportOptions.args` 是 `List<String>`（不可空）⇒ 传
  `List.of()` 等价（包③已确认 `null` 与空表行为一致——**需在实施时复核**）。
- **`cwd` 恒有值**：`resolve(cwd, expandHome(config.cwd ?? "."))` ⇒ Java
  `Path.of(cwd).resolve(expanded).normalize()`。⚠️ JS `resolve` 是
  **绝对化 + 归一化**，`Path.resolve` 只是拼接 ⇒ 必须 `.toAbsolutePath().normalize()`。
  这是本包最容易漏的一处（登记 **B193**）。
- **`env` 的值恒解析**（`resolveConfigValueOrThrow`），键不动。
- `stderr: "pipe"` ⇒ Java `StdioTransportOptions.Stderr.PIPE`（默认值即此）。

### 5.3 `McpServerConnection` 状态机

pi 的字段/方法清单（`runtime.ts:155-474`），Java 1:1：

| pi | Java | 备注 |
|---|---|---|
| `entry/state/error/tools/hasResources/resources/resourceTemplates/instructions/challenge` | 同名字段 | `error`/`challenge` 可空 |
| `client/opening/closed/stderrTail` | 私有字段 | `opening` ⇒ `CompletableFuture<McpClient>` |
| `getClient()` `:250-257` | `CompletableFuture<McpClient> getClient()` | `opening ??= open().finally(...)` ⇒ Java 用 `synchronized`＋`compareAndSet` 语义 |
| `withClient(run, readOnly)` `:291-314` | 同 | 见下 |
| `reconnect()` `:317-321` / `signOut()` `:324-328` | 同 | |
| `open()` `:353-370` / `connectOnce()` `:372-419` | 同 | |
| `connectFailed()` `:421-430` / `handleClientClose()` `:433-440` | 同 | |
| `refreshTools()` `:442-452` / `refreshResources()` `:454-461` | 同 | |

**`withClient` 的循环结构必须逐字保留**（`runtime.ts:291-314`）：

```ts
private async withClient<T>(run, readOnly = false): Promise<T> {
	for (let attempt = 1; ; attempt++) {
		const client = await this.getClient();
		try {
			return await run(client);
		} catch (error) {
			if (readOnly && attempt === 1 && error instanceof McpHttpError && isTransientError(error)) {
				await new Promise((resolve) => setTimeout(resolve, CONNECT_RETRY_DELAYS_MS[0]));
				continue;
			}
			if (error instanceof McpSessionExpiredError && attempt === 1) {
				if (this.client === client) this.client = undefined;
				continue;
			}
			if (!this.needsSignIn(error)) throw error;
			await this.dropClient(client);
			this.markNeedsAuth();
			throw new Error(signInRequiredMessage(this.entry));
		}
	}
}
```

⚠️ **承重细节**：
1. **`attempt === 1`** 两处都是**只重试一次**的门，且**两次 `continue` 累计**：
   第一次因瞬态 408/429/5xx 重试，第二次因 session expired 重试——
   第三条路才抛。Java 循环体照抄，`attempt` 从 1 起。
2. **`readOnly` 的两档语义**：只读请求（`readResource` / 三个 list）可重试；
   **`callTool` 不可**（工具可能已经跑了）——`runtime.ts:257-260` 传 `false`。
   这是承重的「结果 vs 副作用」区分，探针 M5 打它。
3. `McpSessionExpiredError` 分支**故意不 close 旧 client**（`runtime.ts:302-304`
   注释明说：close 会打断它其它在飞请求）。
4. `await new Promise(setTimeout, 250)` ⇒ Java `Thread.sleep(250)`
   （虚拟线程上可中断）。
5. **`signInRequiredMessage`（`:76-79`）** 的文案里 `${provider ? `/login ${provider}` : "/mcp"}`——
   `/login` 是 pi 的 slash 命令，pi-java 有（`slash/builtin/`）；文案逐字保留。

**`connectOnce`（`:372-419`）的六步**——这是最密的一处：

```ts
const client = new McpClient({ name: "pi", version: VERSION, requestTimeoutMs: this.timeoutMs,
	roots: [{ uri: pathToFileURL(this.cwd).href, name: basename(this.cwd) }] });
const log = this.log;
if (log) client.onNotification("notifications/message", (params) => log.write(this.entry.name, params));
...
await client.connect(transport);
client.onNotification("notifications/tools/list_changed", () => { void this.refreshTools(client); });
client.onNotification("notifications/resources/list_changed", () => { void this.refreshResources(client); });
const stdio = transport instanceof StdioTransport ? transport : undefined;
client.onClose(() => this.handleClientClose(client, stdio));
const hasResources = client.serverCapabilities?.resources !== undefined;
const [tools, resources] = await Promise.all([
	client.serverCapabilities?.tools ? client.listTools() : [],
	hasResources ? fetchResources(client) : { resources: [], resourceTemplates: [] },
]);
if (this.closed) throw new Error("shut down while connecting");
if (client.connectionState !== "connected") throw new Error("connection closed during setup");
```

Java 逐条：
- `roots` 的 URI 是 `pathToFileURL(cwd).href`。⚠️ **实施修正**：设计稿原写
  「`Path.toUri()` 也带尾斜杠」——**实测两者不同**：`Path.of(dir).toUri()` 给
  `file:///D:/x/`（带尾 `/`），Node 的 `pathToFileURL` 给 `file:///D:/x`（**不带**）。
  故 `McpConnectionSupport.fileUrl` 显式去掉尾斜杠（`file:///` 根本身除外）。
  另：roots **不在** `initialize` 的 params 里——`McpClientWires.withRootsCapability`
  只声明 `capabilities.roots`，URI 值由客户端应答服务器的 `roots/list` 请求时给出
  （夹具 `McpServerConnectionTest.offersTheSessionDirectoryAsARoot` 据此钉）。
  `name` 是 `basename(cwd)`（末段，根目录时 Node 返回空串）。
- ⚠️ **`this.log` 在 `open()` 与 `connectOnce()` 之间被读成局部**（`runtime.ts:379`），
  且 `onNotification` 的注册在 `client.connect()` **之前**。
- **`hasResources`** 判的是 `serverCapabilities?.resources !== undefined`——
  **不是** `?.resources`（`{}` 也算有），Java 用 `capabilities().resources() != null`。
- **`Promise.all` 两支**：tools 只在 `capabilities.tools` 有值时拉；
  resources 只在 `hasResources` 时拉。⚠️ 两支**都**是「条件为假 ⇒ 恒等空值」，
  **不是**「调用后 catch」。`fetchResources` 自己吞异常（`runtime.ts:144-147`）。
  ⚠️ **实施偏差**：Java 改**顺序**拉取（pi 并发）。可观察差异仅时序（同一 client 上
  两个在飞请求 vs 一前一后）⇒ 登记 **B196**。
- 失败路径**必须**关 client 并把 `stderrTail` 从 stdio 传输上取尾 2,000 字符
  （**先 close 再取**，`runtime.ts:413-416`）。

**`close()`（`:463-473`）的收尾**：

```ts
this.closed = true; this.state = "closed"; this.changed();
const client = this.client; this.client = undefined;
await client?.close().catch(() => undefined);
// A refresh the server already answered may have rotated the refresh token; exiting before the
// new tokens are saved would lose the grant.
await this.authProvider?.settled();
```

⇒ Java `CompletableFuture<Void> close()`；`settled()` 是 pi 在 `AuthProvider` 之外
**另加**的一环（`oauth.ts:284-287`）——见 §6.4。

### 5.4 `fetchResources` / `withoutTemplates`（`:124-152`）

```ts
async function withoutTemplates<T>(list: () => Promise<T>, empty: T): Promise<T> {
	try { return await list(); }
	catch (error) {
		if (error instanceof McpError && error.code === JSON_RPC_ERROR_CODES.methodNotFound) return empty;
		throw error;
	}
}

async function fetchResources(client) {
	const [resources, resourceTemplates] = await Promise.all([
		client.listResources().catch(() => []),
		listTemplates(client).catch(() => []),
	]);
	return { resources: resources.filter((r) => !isMcpAppResource(r)),
	         resourceTemplates: resourceTemplates.filter((t) => !isMcpAppResource(t)) };
}
```

⚠️ 三层不同：
1. **`withoutTemplates`** 只吞 `-32601 methodNotFound`，其余**重抛**；
2. **`listTemplates` 的外层**在 `Promise.all` 里 `.catch(() => [])` ⇒ 连重抛也没了；
3. **`fetchResources` 的调用者**（`connectOnce:397`）**不**再 catch
   ⇒ `Promise.all` 两支都已吞异常 ⇒ 恒不抛。

Java：`McpError` 是 `com.pijava.mcp.protocol.jsonrpc.McpError`，
`code()` 是 `JsonRpcErrorCode`（enum）。⚠️ 需确认 `McpError.code()` 的类型与
`METHOD_NOT_FOUND` 常量名（包①已落）。

---

## 6. `oauth.ts` 设计（532 → `runtime` 包 6 个文件）

### 6.1 常量（`:39-53`，逐字）

```ts
const CALLBACK_HOST = "127.0.0.1";
const CALLBACK_PATH = "/callback";
const CLIENT_METADATA_BASE_URL = "https://pi.dev/oauth";
const FALLBACK_REDIRECT_URL = `http://${CALLBACK_HOST}${CALLBACK_PATH}`;
const REFRESH_SKEW_MS = 30_000;
const REFRESH_REQUEST_TIMEOUT_MS = 15_000;
const REFRESH_LOCK_STALE_MS = 20_000;
const REFRESH_LOCK_WAIT_MS = 25_000;
const REFRESH_LOCK_RETRY_MS = 100;
```

⚠️ **`CLIENT_METADATA_BASE_URL` 指向 `pi.dev`**——cimd 的 Client ID Metadata
Document 托管在 pi 的域名下。pi-java **没有**这个域名 ⇒ **cimd 分支不可达**。
裁决：**保留实现与文案**（`oauth.ts:240-260` 逐字），但登记 **B194**
（pi-java 无 `pi.dev/oauth` 文档 ⇒ `oauth.clientRegistration: "cimd"` 时
授权服务器会拒；本地/loopback 反代可自测）。用户显式配 cimd 时才可达。

### 6.2 凭据 store（`:111-221`）

键规则（`:127-130`）：

```ts
function storeKeys(name: string, serverUrl: string): { key: string; legacyKey: string } {
	const legacyKey = String(new URL(serverUrl));
	return { key: `${mcpNamespace(name)}|${legacyKey}`, legacyKey };
}
```

⚠️ `String(new URL(url))` 是**归一化后的 href**（`new URL` 会补尾斜杠、
小写 scheme/host、去默认端口）——Java 用 `URI.create(url).normalize()` **不够**：
`URI` 不做主机小写、不补根路径斜杠。⇒ Java 侧需要一个 `normalizeUrl` 助手，
**语义对齐 WHATWG URL**（包⑤已在别处踩过 JDK/WHATWG 差异）。
最小可用集合：scheme 小写、host 小写、去掉默认端口（80/443）、
path 为空时补 `/`、去掉 fragment。登记 **B195**（逐字节等价待真 pi 互读复核）。

其余逐字要点：
- `load()`（`:152-159`）：**legacy 键接管**——仅当目标键不存在**且** legacy 存在时，
  把 legacy 迁到 key 并**删除** legacy（返回 `next`）。第一个加载的 server 赢。
- `save()`（`:160-163`）走 `write`（读-改-写，整文件重写）。
- `tokens()`（`:196-200`）**只看不接管**（不写 `next`）。
- `remove()`（`:203-212`）：目标键优先，否则 legacy；没命中 ⇒ `result: false` 且不写。
- `withRefreshLock`（`:172-193`）：**lockDir 为空 ⇒ 直接 `fn()`**（进程内不串行化——
  由 `createMcpAuthProvider` 的 `refreshing` 单飞兜住）。
  hash 是 `sha256(key).hex[0..16]`，锁文件名 `mcp-auth-refresh-<hash>`。
  `onCompromised: () => {}`（`:186`，默认会从 timer 抛）。

**Java 待写骨架**：

```java
/** `mcp-auth.json` 的按 server 视图（oauth.ts:132-221）。 */
public final class McpOAuthCredentialStore {

    private final AuthJsonBackend backend;
    private final @Nullable Path lockDir;

    public McpOAuthCredentialStore(Path agentDir) { ... }                       // 默认文件后端
    public McpOAuthCredentialStore(AuthJsonBackend backend, @Nullable Path lockDir) { ... }

    /** 按 name+url 的键（含 legacy 接管）。 */
    public McpOAuthServerStore forServer(String name, String serverUrl) { ... }

    /** 只读快照，不接管 legacy（oauth.ts:196-200）。 */
    public @Nullable OAuthTokens tokens(String name, String serverUrl) { ... }

    /** 删除凭据；返回是否存在（oauth.ts:203-212）。 */
    public boolean remove(String name, String serverUrl) { ... }
}
```

⚠️ **`McpOAuthServerStore extends OAuthStateStore` ＋ `withRefreshLock`**（`:132-135`）：
Java 用 `sealed`? 不——它是一个**接口**（pi 是 interface），实现是 store 里的匿名对象
⇒ Java 用 `record`＋静态工厂更好（显式、无匿名类）。
⚠️ `OAuthStateStore.load()` 返回 `@Nullable McpOAuthState`，`save` 是 **void**
⇒ `withRefreshLock` 是**新增方法**，不污染 `OAuthStateStore` 接口
（`McpOAuthProviderOptions.store` 只认 `OAuthStateStore`，天然兼容）。

### 6.3 序列化（`:113-121`，⚠️ 一个 B184 同款偏差）

```ts
function serializeStates(states: StoredStates): string { return `${JSON.stringify(states, null, 2)}\n`; }
```

`JSON.stringify(x, null, 2)`：**2 空格缩进 + 尾换行**、`": "` 分隔、
数组内联换行。⇒ Java 复用包⑧的 `JsJson`（需 `JsJson` 与 `detectIndent` 从
`com.pijava.mcp.config` 提到 **public**，或复制；**裁决：提升为 public**，
不复制——同一份 JS 写出器两处实现必然漂移）。
⚠️ **`null` vs 缺席**：JS 的 `undefined` 被省略、显式 `null` 保留。
Java record 的 `null` 组件 ＝ JS 的 **`undefined`**（记录没写就是没写）
⇒ 序列化必须 **NON_NULL 省略**，且**不能**把 record 的 `null` 写成 `null`。
登记 **B196**（`mcp-auth.json` 的字节形态与 pi 可能有 `null`/省略之别；两侧都按 JSON 解析，
行为等价）。

### 6.4 Auth provider（`:284-363`）

```ts
export interface McpAuthProvider extends AuthProvider { settled(): Promise<void>; }

export function createMcpAuthProvider(options): McpAuthProvider {
	const { serverUrl, store } = options;
	let refreshing: Promise<void> | undefined;

	const refresh = (staleToken, fetch = globalThis.fetch, challenge?) => {
		refreshing ??= store.withRefreshLock(async () => {
			const state = await store.load();
			if (state?.tokens?.access_token !== staleToken) return;          // ← 承重：陈旧即弃
			if (!state?.tokens?.refresh_token) throw new McpOAuthAuthorizationRequiredError();
			const settings = options.settings();                            // ← 惰性求值
			const redirectUrl = callbackSettings(settings).fixedRedirectUrl
				?? registeredRedirectUrls(state.clientInformation)[0]
				?? FALLBACK_REDIRECT_URL;
			const provider = createProvider(serverUrl, store, settings, redirectUrl, () => {});
			const result = await authorizeMcp(provider, {
				serverUrl, resourceMetadataUrl: challenge?.resourceMetadataUrl,
				authorizationServerMetadataUrl: settings.authServerMetadataUrl,
				scope: challenge?.scope,
				fetch: (input, init) => fetch(input, { ...init, signal: AbortSignal.timeout(REFRESH_REQUEST_TIMEOUT_MS) }),
			});
			if (result === "REDIRECT") throw new McpOAuthAuthorizationRequiredError();
		}).finally(() => { refreshing = undefined; });
		return refreshing;
	};

	return {
		token: async () => {
			await refreshing?.catch(() => undefined);
			const state = await store.load();
			const token = state?.tokens?.access_token;
			const expired = state?.tokensExpireAt !== undefined && state.tokensExpireAt - REFRESH_SKEW_MS <= Date.now();
			if (!expired || !state?.tokens?.refresh_token) return token;
			await refresh(token).catch(() => undefined);                 // ← 失败静默
			return (await store.load())?.tokens?.access_token;
		},
		onUnauthorized: async (context) => {
			const challenge = parseWwwAuthenticate(context.response.headers.get("www-authenticate"));
			options.onChallenge(challenge);
			if (challenge.error === "insufficient_scope") throw new McpOAuthAuthorizationRequiredError();
			await refresh(context.token, context.fetch, challenge);
		},
		settled: async () => { await refreshing?.catch(() => undefined); },
	};
}
```

⚠️ **Java 侧的六条承重差异**（每条都要夹具）：

1. **`refreshing ??=` 是进程内单飞**（同 JVM 多 server 共用一个 provider 实例）：
   Java 用 `synchronized` + `@Nullable CompletableFuture<Void> refreshing`，
   语义＝「已有在飞 ⇒ 加入等待，不新开」。⚠️ `??=` 的赋值发生在**同步段内**；
   Java 的 `if (refreshing == null) refreshing = ...` 必须与 `settled()` 的读
   **同锁**（否则可见性）。包⑦的 M4 血案（「共享语义断言钉临界区入口，不钉事后计数」）
   直接适用。
2. **`state?.tokens?.access_token !== staleToken → return`**：陈旧令牌 ⇒ 静默返回
   （别人已经刷过了）。**不是**错误。这是并发护栏的活命条件。
3. **`options.settings()` 惰性求值**：`runtime.ts:232-248` 的 `oauthSettings()` 会
   **解析 `clientSecret`**（可能抛 `Failed to resolve …`）⇒ 只在需要刷新时才抛，
   不拖垮连接建立（`oauth.ts:294-295` 注释明说）。Java 的 `settings` 必须是
   `Supplier<McpOAuthSettings>`，**不能**提前求值。
4. **`fetch` 的默认值 `globalThis.fetch` ＋ 每次刷新套 `AbortSignal.timeout(15_000)`**：
   Java 的 `McpFetch` 是 `fetch(Request)` **无 signal 参数**（`McpFetch.java:22`）
   ⇒ **15 秒超时无处可挂**。⚠️ 这是**真缺口**：需要
   `OAuthFlowOptions.fetch` 之外的超时通道。**裁决**：本包在
   `McpFetch` 外**包一层**实现（拦截 `McpFetch` 调用并交给一个带超时的执行器，
   超时抛 `IOException`）；不扩 `McpFetch` 接口（避免动包⑥/⑦的稳定面）。
   登记 **B197**（与 pi 的 `AbortSignal.timeout` 语义等价性需夹具钉）。
5. **`onUnauthorized` 的 `context.fetch`**：Java 的 `AuthProvider.Context.fetch()`
   是 `@Nullable McpFetch`（传输层可能给不出）⇒ 需要
   `fetch != null ? refresh(token, fetch, challenge) : refresh(token, null, challenge)`，
   后者落到默认 fetch。⚠️ 需在实施时读传输层桥（`OAuthTransportBridgeTest` 覆盖的点）
   确认可空性到底是否可达；若**恒非空**则简化为断言。
6. **`onChallenge` 无条件调用**（`:354`）——即使随后抛 `insufficient_scope`，
   `challenge` 也已落到 `McpServerConnection.challenge`（`runtime.ts:209-211`），
   `/mcp` 用它显示 step-up 的 resource metadata URL。

**`McpAuthProvider extends AuthProvider` ＋ `settled()`**：Java 落
`com.pijava.mcp.runtime.McpAuthProvider`（接口），**不动** `com.pijava.mcp.AuthProvider`
（包③/⑦的稳定面）。

### 6.5 登录编排（`:365-532`）

**`signInMcpServer` 的骨架（逐字要点）**：

```ts
const stored = await store.load();
const stepUp = options.challenge?.error === "insufficient_scope";
const callbackOptions = callbackSettings(settings);
const registered = registeredRedirectUrls(stored?.clientInformation)[0];
const preferredPort = callbackOptions.port ?? (registered ? Number(new URL(registered).port) || undefined : undefined);
const cimd = settings.clientRegistration === "cimd";
const callback = await listenForCallback(callbackOptions,
	cimd ? [`${CALLBACK_PATH}/${callbackId(serverUrl)}`] : [],
	preferredPort, callbackOptions.port !== undefined);
const redirectUrl = callbackOptions.fixedRedirectUrl ?? callback.redirectUrl;
try {
	if (stored) {
		const next = { ...stored };
		delete next.oauthState;                                     // 每次登录换 state
		const keepClient = settings.clientId
			|| (cimd ? !stored.clientInformation
			         : registeredRedirectUrls(stored.clientInformation).includes(redirectUrl));
		if (!keepClient) { delete next.clientInformation; delete next.tokens; delete next.tokensExpireAt; }
		await store.save(next);
	}
	let authorizationUrl;
	const provider = createProvider(serverUrl, store, settings, redirectUrl, (url) => { authorizationUrl = url; });
	const flow = { serverUrl, resourceMetadataUrl: options.challenge?.resourceMetadataUrl,
		authorizationServerMetadataUrl: settings.authServerMetadataUrl,
		scope: mergeScopes(settings.scope,
			stepUp ? stepUpScope(stored?.tokens?.scope, options.challenge?.scope) : options.challenge?.scope) };
	if ((await authorizeMcp(provider, { ...flow, skipRefresh: stepUp })) === "AUTHORIZED") return;
	if (!authorizationUrl) throw new Error("OAuth flow did not produce an authorization URL");
	const state = await provider.state();
	const authorizationRedirectUrl = new URL(authorizationUrl.searchParams.get("redirect_uri") ?? redirectUrl);
	options.prompt.showAuthorizationUrl(authorizationUrl);
	const { code, iss } = await waitForAuthorizationResponse(callback, state, authorizationRedirectUrl, options.prompt);
	await authorizeMcp(provider, { ...flow, authorizationCode: code, iss });
} finally { await callback.close(); }
```

⚠️ Java 侧：
- **`callbackSettings`（`:84-103`）的 `fixedRedirectUrl` 三分支**是承重的：
  `url.port` 有值 ⇒ `fixedRedirectUrl = settings.callbackUrl`（**原字符串**，
  因为服务器按字符串比对）；`url.port` 空但 `callbackPort` 有值 ⇒ 把 port 填进
  URL 后取 `url.href`（**归一化形态**）；否则 `undefined`。
  ⚠️ 且 `url.port ? Number(url.port) : settings.callbackPort`——JS 的 `URL.port`
  缺省是 `""`（falsy），Java `URI.getPort()` 缺省是 `-1`。
  **`address === "localhost" → 监听 127.0.0.1`，但 `redirectHost` 保留 `localhost`**
  （`:96-98`，浏览器对 `::1` 拒连时回落）。
  `address` 用 `url.hostname.replace(/^\[|\]$/g, "")` 去 IPv6 方括号
  （⚠️ 包⑧已踩：JDK `URI.getHost()` **保留**方括号且**不**小写化）。
- **`listenForCallback`（`:428-450`）**：`required = callbackOptions.port !== undefined`，
  首次 `listen({...options, port: port ?? 0})`；失败且 `!required && port !== undefined`
  才重试 `listen(options)`（无 port ⇒ 临时端口）。
  Java 的 `OAuthCallbackServerOptions.port` 是 `int`（`0` ＝ 临时）⇒ 重试分支
  传 `port = 0`，等价。
- **`waitForAuthorizationResponse`（`:405-425`）**：`Promise.race([fromBrowser, fromUser])`
  ＋ `finally { controller.abort(); both.catch(()=>undefined) }`。
  Java：两个 `CompletableFuture` ＋ `anyOf`，`finally` 里 `controller.abort()`
  并吞掉败方的异常（**两个分支都要 `.exceptionally(ignored -> null)`**，
  否则败方的失败会变成未观察异常）。⚠️ 夹具必须证明「输的那一侧的拒绝被吞」。
- **`responseFromRedirectUrl`（`:385-402`）**：先 `new URL(input.trim())`（失败 ⇒
  「Expected the full redirect URL from the browser address bar」）；再比
  **`origin` + `pathname`**（**不**比 search/hash）；再 `error` → 抛
  `error_description ?? error`；再比 `state`；再要 `code`；最后 `iss`。
  顺序**逐条不可换**（错误优先级，探针 M8）。
- **`clientMetadataDocument`（`:240-260`）**：两处抛/降级——
  服务器不支持 ⇒ **抛**（文案逐字）；支持 `iss` ⇒ 用**通用**文档 URL；
  否则用 `<base>/<callbackId>/client.json` ＋ 把 redirect 的 pathname
  改成 `/callback/<id>`（**注意这里多了个斜杠**，`${CALLBACK_PATH}/${id}`
  而 callback 的 path 是 `/callback`，且 `listenForCallback` 的 `extraPaths`
  也注册 `/callback/<id>`）。
- **`callbackId`（`:228-232`）**：`sha256(href without hash)` 的**前 9 字节**
  的 **base64url**（12 字符）。Java：
  `Base64.getUrlEncoder().withoutPadding()` ＋ 前 9 字节。
  ⚠️ `url.hash = ""` 后取 `href`——若原 URL 带 `#` 会保留尾…… 需实施时逐字节比对；
  登记 **B195** 同族。

---

## 7. 依赖移植

### 7.1 `ConfigValueResolver`（`resolve-config-value.ts` 287 行，全文）

语义清单（`file:line` 见括号），Java 逐条对齐：

| # | 规则 | 出处 |
|---|---|---|
| 1 | 以 `!` 开头 ⇒ shell 命令；其余 ⇒ 模板 | `:80-86` |
| 2 | 模板里 `$$` ⇒ 字面 `$`；`$!` ⇒ 字面 `!` | `:42-46` |
| 3 | `${NAME}` 且 NAME 匹配 `^[A-Za-z_][A-Za-z0-9_]*$` ⇒ env；否则**原样保留整段**（含花括号） | `:48-64` |
| 4 | `${` 无 `}` ⇒ 输出 `$` 并**只前进 1 格**（后续再解析） | `:49-53` |
| 5 | 裸 `$NAME` 取最长 `^[A-Za-z_][A-Za-z0-9_]*` 前缀 | `:66-71` |
| 6 | `$` 后不匹配任何规则 ⇒ 输出 `$`，前进 1 | `:73-74` |
| 7 | 相邻字面量**合并**（`appendLiteral`，`:18-26`）——影响 `getConfigValueEnvVarName` 的「单 part」判定 | `:18-26,115-119` |
| 8 | env 取值 `env?.[name] \|\| process.env[name] \|\| undefined` —— **空串视为未定义** | `:88-90` |
| 9 | 任一 env 未解析 ⇒ 整个模板 `undefined` | `:101-113` |
| 10 | 命令结果**进程内缓存**（`Map`，含 `undefined` 缓存）；`resolveConfigValueUncached` 绕过 | `:10,208-216,221-227` |
| 11 | 命令：`trim()`；**空输出 ⇒ undefined** | `:178-179` |
| 12 | 命令：非 0 退出码 ⇒ undefined（**不抛**） | `:174-176` |
| 13 | 超时 **10,000 ms**，stderr `ignore` | `:160,190` |
| 14 | `resolveConfigValueOrThrow` 的四段文案：`from shell command: <cmd>` / `from environment variable: X` / `from environment variables: X, Y` / 裸 `Failed to resolve <desc>` | `:229-251` |
| 15 | `resolveHeaders*` 只保留解析出的**真值**（`if (resolvedValue)` ⇒ 空串丢弃），全空 ⇒ `undefined` | `:256-282` |

⚠️ Java 侧：
- `getConfigValueEnvVarNames` 的**去重保持首次出现序**（`:92-99`）。
- 缓存的键是**整段配置串**（含 `!`），不是命令。
- 全局静态缓存 ⇒ 夹具必须 `clearConfigValueCache()`（对应 `:285-287`）。

### 7.2 `OAuthPage`（`packages/ai/src/utils/oauth-page.ts` 109 行）

`oauthSuccessHtml(message)` / `oauthErrorHtml(message, details?)`。逐字 HTML
（含内联 `LOGO_SVG` 与 CSS）。⚠️ `escapeHtml` 五条替换的**顺序**承重
（`&` 必须先，`:4-10`）；`details` 缺省 ⇒ 不渲染该元素。
落 `pi-java-ai/.../ai/utils/OAuthPage.java`，`public final`，两个静态方法。

---

## 8. 测试计划

`pi-java-mcp/src/test/java/com/pijava/mcp/runtime/`（新 8 个夹具）：

| 夹具 | 覆盖 |
|---|---|
| `McpServerLogTest` | 六条：正常行、非 record 包 `{data}`、`level`/`logger` 缺省与空串、CRLF→`\n    `、ISO 三位毫秒、**轮转**（阈值上/下、`.1` 覆盖、重查分支）、写失败吞掉（只读目录） |
| `ConfigValueResolverTest` | 15 条规则表逐条（含 `$$`/`$!`/`${}` 无右括号/裸 `$`/多 env 缺失文案/缓存） |
| `McpDefaultTransportTest` | url 分支（headers 解析/抛）、stdio 分支（env 解析、`expandHome` 四种、`cwd` **绝对化归一化**、args 映射） |
| `McpServerConnectionTest` | `ScriptedTransport` fixture：连接成功、`needs-auth`、`failed`＋`stderrTail`、瞬态重试（408/429/500/501 反例）、session expired 重放、`callTool` **不**重试、`list_changed` 刷新、`close()` 的 `settled()` 等待 |
| `McpOAuthCredentialStoreTest` | 键规则、legacy 接管（首个赢/已存在不接管）、`tokens()` 不接管、`remove()` 三分支、轮换锁（跨线程串行化断言钉**临界区入口**） |
| `McpAuthProvidersTest` | 单飞（并发 8 个 `token()`）、陈旧令牌静默返回、`insufficient_scope` 直抛、刷新失败静默、`settings()` 惰性（抛才对）、`settled()` |
| `McpSignInTest` | `callbackSettings` 三分支、`mergeScopes`、`responseFromRedirectUrl` 六段错误序、`waitForAuthorizationResponse` 的 race＋败方吞异常、`keepClient` 三分支、`try/finally` 关回调 |
| `OAuthPageTest`（放 `pi-java-ai`） | `escapeHtml` 五替换顺序、`details` 缺省 |

外加 `InMemoryAuthBackend` / `ScriptedMcpFetch`（已有）/ `ScriptedHttpServer`（已有）。

**端到端**：`McpServerConnection` × `InMemoryTransport`（包④）跑一次
「连接 → listTools → callTool → 断开 → 重连」。

---

## 9. 变异探针计划（10 个，每个必须 grep 确认落地）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | `MAX_LOG_BYTES` 的 `>` 改 `>=` | 恰 1（阈值相等那条） |
| M2 | `log.ts:52` 的**第二次** `currentSize()` 检查删掉 | 1（已轮转夹具） |
| M3 | `expandHome` 的 `~\` 分支删掉 | 1（Windows 路径夹具） |
| M4 | `cwd` 的 `.toAbsolutePath()` 去掉 | 1 |
| M5 | `withClient` 的 `readOnly` 对 `callTool` 传 `true` | 1（副作用不重试夹具） |
| M6 | `needsSignIn` 的 `error instanceof McpAuthRequiredError` 去掉 authProvider 非空前提 | 1 |
| M7 | 刷新单飞去掉（每次并发都开新刷新） | 1（临界区入口夹具） |
| M8 | `responseFromRedirectUrl` 的 `error` 检查挪到 `state` 之后 | 1（错误优先级夹具） |
| M9 | `serializeStates` 的 NON_NULL 关掉 | 1（`null` 组件不进文件） |
| M10 | `mergeScopes` 的 `Set` 去重去掉 | 1（重复 scope） |

⚠️ **包⑦/⑧的教训已内化**：M7 的夹具必须钉「第二个线程进没进临界区」
（第二闩锁＋上界），**不钉事后计数**；M5/M8 的夹具要先写（红）再实现——
**「夹具写在实现之后」是零红的第二大成因**。

---

## 10. 台账影响（新增 B188–B201）

| # | 条目 | 状态 |
|---|---|---|
| B188 | `!cmd` 的 settings.shellPath 层未接（`CommandRunner` 可注入，第 12 包接线） | 未结 |
| B189 | MCP 客户端身份（name/version）由调用方注入，模块内零字面量 | 第 12 包接线前未结 |
| B190 | `isTransientError`：pi 判 `TypeError`，Java 判 `IOException`，**范围更宽** | 未结（窄角） |
| B191 | `WebUrls` 的 WHATWG 归一化是自建；逐字节等价待真 pi 互读 | 未结 |
| B192 | cimd 的 `https://pi.dev/oauth` 在 pi-java 无托管文档 ⇒ 该模式不可达 | 未结 |
| B193 | `mcp-auth.json`：`clientInformation.extension` 写成嵌套键 ＋ null/省略之别 | 未结（cosmetic） |
| B194 | OAuth 刷新的 15 s 超时靠外包一层（`McpFetch` 无 signal 通道） | 未结 |
| B195 | 本包新类暂无生产消费者（第 10–12 包接线） | 第 12 包接线前未结 |
| B196 | `connectOnce` 顺序拉取工具/资源（pi 用 `Promise.all`） | 未结（仅时序） |
| B197 | `URI.create` 接受相对引用而 `new URL` 抛；已挡一处，其余 URL 入口未逐个复核 | 未结 |
| B198 | `OAuthPage` 的 CSS 折行（字节差仅 `<style>` 内空白） | 未结（cosmetic） |
| B199 | **包③ 的 stdio `cwd` 未移植** ⇒ `mcp.json` 的 `cwd` 是死的 | **已修（本包）** |
| B200 | **包⑧ 的 checkstyle 是「错误 0、警告 2」**，闭环记录称「checkstyle 0」 | **已修（本包）** |
| B201 | **包⑤ 的 HTTP transport 无公开构造面**（包私有且无工厂） | **已修（本包）** |

---

## 11. 实施记录

**提交**（全部在 main 上，逐个可独立编译）：

| 提交 | 内容 |
|---|---|
| `75f43e92` | 9a：`McpServerLog`、`ConfigValueResolver`、`CommandRunner`/`JdkShellRunner`（37 测试） |
| `6265eca3` | 9b：`AuthJsonBackend` 两实现、`McpOAuthCredentialStore`、`McpAuthProviders`（33 测试） |
| `a32576eb` | 9c：`McpServerConnection`/`McpConnector`/`McpConnectionSupport`、`McpDefaultTransport`（18 测试） |
| `17eda31a` | 9d：`McpSignIn`、`McpSignInPrompt`、`OAuthPage`（19 测试，5 个在 `pi-java-ai`） |

**规模**（实测）：主源 20 文件（`pi-java-mcp/.../runtime` 19 ＋ `pi-java-ai/.../utils/OAuthPage`），
测试 8 夹具（runtime 7 ＋ ai 1）。`pi-java-mcp` 测试 **232 → 246**，`pi-java-ai` 1377 → 1382。

**验证**：两模块 `checkstyle:check` 对本包新文件 **0 警告**；
`spotbugs:check` 两模块 `BugInstance size is 0`；全 reactor `mvn clean verify` 见 §11.4。

### 11.1 探针（10 个，全部有牙）

| # | 变异 | 命中 |
|---|---|---|
| M1 | `MAX_LOG_BYTES` 的 `>` 改 `>=` | **0 红 —— 语义等价**（见 11.2） |
| M2 | 删掉轮转前的第二次 `currentSize()` 检查 | 恰 1（`doesNotRotateWhatAnotherProcessAlreadyMoved`） |
| M3 | 命令输出去掉 `strip()` | 2 |
| M4 | 单飞：删掉 `if (refreshing != null) return refreshing;` | 恰 1（`concurrentChallengesShareOneRefresh`，`2 but was 1`） |
| M5 | 删掉「陈旧令牌即弃」护栏 | 恰 1（`aTokenAnotherProcessAlreadyReplacedIsNotRefreshed`） |
| M6 | 删掉 legacy 键接管的「目标键已存在」前提 | 恰 1（`aLaterLoadDoesNotTakeLegacyStateOverAgain`） |
| M7 | `callTool` 传 `readOnly = true` | 恰 1（`aToolCallIsNeverRetried`） |
| M8 | `hasResources` 不再看 `capabilities.resources` | 恰 1（`aServerWithoutTheResourcesCapabilityIsNeverAsked`） |
| M9 | 去掉 MCP App 过滤 | 恰 1（`aServerWithResourcesListsThemWithoutTheApps`） |
| M10a | 交换 `error` 与 `state` 的检查次序 | 恰 1（`reportsTheServersOwnErrorBeforeAnythingElse`） |
| M10b | cimd 下仍保留已存 client | 恰 1（`aClientIdMetadataDocumentReplacesAnyStoredClient`） |
| M10c | `escape` 去掉 `&`→`&amp;`（顺序破坏） | 恰 1（`escapesEveryValueInTheOrderThatKeepsEntitiesIntact`） |
| M10d | `mergeScopes` 的 `LinkedHashSet` 换成 `ArrayList` | 恰 1（`mergesScopesEachOnceInOrder`） |

**M5 是包⑦教训的直接应用**：夹具钉的是「第二次进入刷新**临界区**的计数」，
不是事后计数——`entered` 闩锁保证第一次调用已在锁内，`seen` 闩锁保证第二次已走过
`onChallenge` 钩子（`refresh(...)` 的**前一条语句**），所以 `lockEntries == 1` 是
无竞态的断言。

### 11.2 「零红」的第六种成因：**变异体语义等价（第二次命中）**

M1 零红，但**不是夹具没牙**。轮转块的两条路径都以 `size = currentSize()` 收尾，
而 `currentSize()` 是块内唯一有副作用的调用；`>` 与 `>=` 只在「缓存恰好等于阈值」
时分叉，那个分支一进去就把缓存重设成磁盘真值 ⇒ **两版在任何人再读缓存之前就收敛**。
（`test_mutation_equivalence` 的判断口径：把两版对同一状态序列的输出逐一比较，
差集为空。）

留给后人的判据：**想让 `>` vs `>=` 有牙，阈值两侧的状态必须都由夹具造成**，
而这里缓存只由 `+=` 增长、永远不可能恰好落在 5 MiB。

### 11.3 「`+` 不是分隔符」的第二次命中

第一轮跑 9a 夹具时我用了 `-Dtest='McpServerLogTest+ConfigValueResolverTest'`：
**BUILD SUCCESS、零 `Tests run` 行、一个用例都没跑**。包⑧ 已经登记过这个坑
（memory `package28-mcp-package1`），我仍然踩了。改用 `,` 后 37/37。
**⇒ 记忆里的坑不等于免疫；`-Dtest` 一律用 `,`，且看到「BUILD SUCCESS 却没有
`Tests run` 行」必须当作失败处理。**

### 11.4 五处**顺带修掉的上游遗留**（都是「第一个消费者」才暴露的）

| 出处 | 事实 | 后果 | 处置 |
|---|---|---|---|
| 包③ `StdioTransportOptions` | 没有 `cwd` 组件（pi 的 `stdio.ts:58` 有，`:96` 传给 spawn） | MCP server 恒在 pi-java 的工作目录里跑，**`mcp.json` 的 `cwd` 字段是死的** | 加组件 ＋ `ProcessBuilder.directory(...)`（**B199**） |
| 包⑤ `StreamableHttpTransport` | 类与选项都是**包私有**、无工厂 | 包外**无法**构造 HTTP transport ⇒ 本包是第一个消费者，一编译就撞墙 | 两者改 `public`（pi 的 `packages/mcp/src/index.ts:59-64` 公开导出，是**还原**而非放宽，**B201**） |
| 包⑥/⑦ `OAuthEndpoints` | 类本身包私有 | 同上；pi 公开导出 `stepUpScope` | 类改 `public`（**B201** 同族） |
| 包⑧ `JsJson` | 包私有 | `mcp-auth.json` 需要**同一份** JS 写出器 | 改 `public`（复制会必然漂移），未新增第二份实现 |
| 包⑧ `McpConfig.java` | 两行 javadoc 超 120 列 | 包⑧ 闭环记录写「checkstyle 0」，**实测是「错误 0、警告 2」** | 折行修掉（**B200**）⇒ **闭环口径要写明「错误/警告」各几个** |

### 11.5 登记为**不可达**而非缺陷的两处

1. **`resolveConfigValueOrThrow` 的裸 `Failed to resolve <desc>` 兜底不可达**：
   `${1BAD}` 不是引用而是字面量、解析成它自己，所以模板分支只要走到
   `unresolvedTemplate` 就一定有 ≥1 个缺失的环境变量。**保留**（pi 保真）并加注释；
   夹具改成断言「非法花括号引用被**原样返回**」。
2. **`appendLiteral` 的字面量合并是行为惰性的**：没有任何公开 API 暴露 `parts` 的
   **数量**，除了 `envVarName` 的「恰好一个 `Env` 分量」判定，而字面量分量永远
   不满足它 ⇒ 合并只是分配优化，对它的变异是**等价变异**（同 11.2）。
   **未跑该探针**——按 11.2 的口径直接判定，如实记录在此。

### 11.6 实测推翻设计稿的一处

设计稿 §5.2 写「`Path.toUri()` 给目录带尾斜杠，Node `pathToFileURL` **也**带」——
**错的**。实测：`Path.of(dir).toUri()` → `file:///D:/x/`；Node
`pathToFileURL('D:/x').href` → `file:///D:/x`。已按实测实现 `fileUrl` 并修正 §5.2。
