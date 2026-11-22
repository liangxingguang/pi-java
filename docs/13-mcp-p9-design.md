# 13 - L2（MCP）收口设计稿（P9 整包）

> 【背景摘要】按 2026-11-22 裁决「由底往上、逐层对齐」，L1 已收口（`docs/12`），**下一个是 L2**。
> `map/07` 在目标锚点 `a00a5048d` 下的投影是 **115.0/123 ≈ 93.5%**，唯一缺口就是 P9 整包：
> 1 个新增单元（`PipeTransport`）＋ 8 个原满分单元重判，扣 **−6.5**。
> 本稿把这 9 项拆成 7 个可独立实施的 S 阶段，给出 pi 证据（只读 `a00a5048d`）、Java 落点、夹具与提交切分。
> **本稿不改读数**；收口后由 `map/07` 先记、`docs/05` §1.2 与 `docs/09` §3 同步（三处一起改，见 `docs/04` §0）。
>
> 证据取法：`git -C D:/workplaceForai/pi show a00a5048d:<path>`。

## 1. 缺口清单与权重

| # | 单元 | 权重 | 投影损失 | 现状（`map/07:229`） | 本稿阶段 |
|---|---|---:|---:|---|---|
| 22 | stdio 关闭序列／进程树 | w1 | 部分 | 关 stdin→等 2s→杀树；缺「500ms 宽限后 SIGTERM」「按 close 而非 exit 清组」「大消息线性读」 | S1／S2 |
| 25 | Streamable HTTP POST/GET JSON 分支 | w2 | 部分 | JSON 分支无 `maxMessageBytes`、超限不取消 body；错误体只截 8 KiB 但读完整 | S3 |
| 26 | SSE 消费／续接 | w2 | 部分 | 续接（`Last-Event-ID`）已在；差在 body 取消与限长口径统一 | S3 |
| 36 | OAuth 刷新 | w2 | 部分 | 有单飞＋文件锁＋stale 比对；缺 `refreshOnly`（刷新不注册 client）、刷新锁不可中止、`settled()` | S4 |
| 37 | OAuth 回调 | w1 | 部分 | `HttpServer.stop(0)` 等价已具；差在 loopback 监听被拆成独立件、重定向 URI 校验口径 | S5 |
| 42 | sign-in 拆分 | w2 | 部分 | 单调用 `McpSignIn.signIn`；缺 `start`／`complete` 两段式与 `McpSignInRedirect` 数据 | S5 |
| 67 | `mcp list` | w2 | 部分 | 不传 provider token；`needs-auth` 只提示 `mcp login <name>` | S6 |
| 68 | login／logout | w2 | 部分 | 同上；缺 `/login <provider>` 提示 | S6 |
| — | `PipeTransport` | w1 | 全新 | 无等价实现 | S7 |

> 分母口径：`PipeTransport` 若判「仅被 R5 的 env／apps/p2 消费」则出分母（122），本稿**建议做**（理由见 D1）。

## 2. 两侧对位（pi `a00a5048d` ↔ Java 现状）

### 2.1 stdio（pi `transports/stdio.ts` + `transports/lines.ts`）

pi 的关闭序列（**按 `close` 事件，不按 `exit`**）：

```
stdin.end()
  → min(500ms, closeTimeoutMs) 后 killProcessTree(SIGTERM)
  → grace + closeTimeoutMs 后 killProcessTree(SIGKILL)
child.once("close") → 再 killProcessTree(SIGTERM)（子进程可能仍握着管道）
```

另有：POSIX 用**进程组**（`detached:true` + `kill(-pid)`），Windows 走 `taskkill /pid <pid> /T /F`；
进程组号进 `liveProcessGroups`，`process.once("exit")` 兜底 SIGTERM；非 POSIX 且已退出 ⇒ 只 `stdin.end()`。

Java（`transport/StdioTransport.java` `close()` L78-106 + `StdioProcess.terminateTree/killTree`）：
`stdin.close()` → `waitFor(2_000ms)` → `terminateTree()`（`descendants().forEach(destroy)` + `destroy()`）→
`killTree()` → 轮询到 `process == null`（≤10s）。**差异三条**：①没有 500ms 宽限（合作型服务器要白等 2s）；
②进程已退出时不再清后代（`waitFor` 成功就 return，握管道的孙进程会漏）；③没有 JVM 退出兜底。

行读取（pi `lines.ts`）：按块扫描 `\n`，未完成的尾巴按**块**留着、换行到达时只 join 一次；累计超过
`maxMessageBytes` 立即 `onError`；stderr 尾巴 64 KiB。
Java（`transport/StdioLineFrames.pump` L21-45）：**逐字节** `stream.read()` 累到 `ByteArrayOutputStream`，
换行时 `toByteArray()`；`maxMessageBytes` 校验已在。语义等价，但大消息（MB 级）是逐字节循环。

### 2.2 Streamable HTTP（pi `transports/streamable-http.ts`）

```ts
if (type === "application/json") {
    const maxBytes = this.options.maxMessageBytes ?? DEFAULT_MAX_MESSAGE_BYTES;
    const { text, truncated } = await readBodyText(response, maxBytes);
    if (truncated) throw new Error(`MCP JSON response exceeds ${maxBytes} bytes`);
    ...
}
// 错误体：只读保留的摘录长度，然后 cancel 掉剩下的
const body = (await readBodyText(response, MAX_ERROR_BODY_BYTES).catch(() => ({ text: "" }))).text;
```

Java（`transport/http/StreamableHttpTransport.java`）：JSON 分支 `readValue` **整包读、无上限**；
SSE 分支已有 `maxMessageBytes`（`SseStreamConsumer`）；错误体 `readErrorBody` 读满 8 KiB 但**不取消**剩余 body。

### 2.3 OAuth 刷新（pi `oauth/auth-provider.ts`）

- `refreshStoredTokens(...)` 调 `authorizeMcp(..., { refreshOnly: true })`：**永不注册 client、不 prepare
  授权请求**（否则会把用户正在进行的登录作废）。
- 刷新用的 redirect URI 候选：`options.redirectUrl` → loopback 固定值 → 已注册 client 的第一个；
  逐个问 `clientMetadataDocuments.listsRedirectUrl(url)`，全不命中回落 `DEFAULT_REDIRECT_URL`。
- `settled()`：等进程内单飞结束，关闭时不会丢轮换后的 token。
- 文档明确「刷新**不可取消**」：服务端可能已轮换 refresh token，答案必须落盘；可取消的只有
  **等刷新锁**那段。

Java（`runtime/McpAuthProviders.java`）：`token()`／`onUnauthorized()`／单飞 `refreshing`／
`store.withRefreshLock`（文件锁）／stale 比对／`TimedMcpFetch(15s)` 都有；**缺** `refreshOnly` 语义、
redirect 候选链、`settled()`，且 `withRefreshLock` 不接受中止源。

### 2.4 sign-in 两段式（pi `oauth/sign-in.ts` + `oauth/loopback.ts`）

```ts
startMcpSignIn(options): Promise<{kind:"authorized"} | McpSignInRedirect>
completeMcpSignIn(options, response): Promise<void>
// McpSignInRedirect = { kind:"redirect", authorizationUrl, redirectUrl, state, scope? }  ← 纯数据，可持久化
```

- `start` 先试「用已存 client 刷新」（`insufficient_scope` 除外）→ 直接 `authorized`；
  否则准备浏览器步：新 `state`，**不覆盖 redirectUrl 的 client 连同 token 一起丢掉**。
- `signal` 中止 ⇒ `McpSignInCancelledError`；**等刷新锁时也可中止**；刷新已发出则不取消（同上）。
- loopback 监听拆成 `listenForLoopbackSignIn` / `waitForLoopbackSignIn` / `responseFromRedirectUrl`，
  由调用方给 redirect URI（https 回调路由或 loopback 皆可）。

Java（`runtime/McpSignIn.java`）：单调用 `signIn(Options)`，内部串「监听→prepare→授权 URL→提示→
等回调（浏览器与粘贴竞速）→换 token」；`responseFromRedirectUrl` 已做 scheme＋origin＋pathname 全等＋state 精确匹配。

### 2.5 CLI（pi `extensions/mcp/cli.ts`）

- `McpCommandOptions.providerToken?: (provider) => Promise<string | undefined>`（缺省读 agentDir 的 `auth.json`），
  传给 `createConnection(...)` → `mcp list` 对 `auth.provider` 型服务器也能列出工具。
- `ServerReport.authProvider`；`needs-auth` 时打 `sign in with: /login <provider> in pi`，否则打 `pi mcp login <name>`。

Java（`coding-agent/subcommand/McpCommand.java` + `McpListCommand.java`）：`McpConnectionOptions.providerToken`
恒传 `null`；`/login <provider>` 提示只存在于运行时的 `McpConnectionSupport.signInRequiredMessage`。

## 3. 待拍板（实施前需要用户点头）

| 编号 | 事项 | 建议 | 影响 |
|---|---|---|---|
| D1 | `PipeTransport` 做还是判「仅被 R5 消费」出分母？ | **做**。~130 行，只依赖一个 `McpPipe` 接缝（`write/closeStdin/close/exited`），不引入 env；分母保持 123 更稳，也避免将来接远程执行时返工 | 分母 123 vs 122 |
| D2 | Windows 上「500ms 宽限 → SIGTERM → SIGKILL」怎么落？ | Windows 无信号：第一段用 `destroy()`（TerminateProcess）同时杀后代，第二段 `destroyForcibly()`；POSIX 保持「grace 后 SIGTERM，再 kill」两段。**两段存在**，观察到的行为对齐（先礼后兵） | `#22` 判据 |
| D3 | 两段式 sign-in 是否作为 `pi-java-mcp` 的公开 API？ | **公开**：pi 是 SDK 面，且 coding-agent 的 `mcp login` 本来就要「先拿 URL 再等回调」；单调用 `signIn` 保留为便捷封装 | 公开面 |
| D4 | `mcp list` 的 provider token 来源 | agentDir 的 `auth.json`（与运行时一致），`env` 兜底；`McpCommand.Options` 允许注入（测试用） | `#67` 判据 |
| D5 | `#26` 是否还有代码要改 | 实施 S3 时逐条比；若 `SseStreamConsumer` 已等价，只登记复核结论（`map/07` 行改判），不动代码 | 是否含代码 |

**默认口径（未回复则按建议执行）**：D1 做、D2 两段、D3 公开、D4 auth.json、D5 按实测。

## 4. 实施阶段

### S1 `#22` stdio 关闭序列（w1）

- `StdioTransport.close()` 重写为 pi 的三段式：`stdin.close()` → 等 `close`／`process.onExit()` 最多
  `min(500ms, closeTimeoutMs)` → `terminateTree()` → 再等 `closeTimeoutMs` → `killTree()`。
- `StdioProcess` 增 `terminateTreeIfExited`：进程已退出但仍需清后代（POSIX 组；Windows `taskkill /T`）。
- JVM 兜底：`liveProcesses` 静态集合 + `Runtime.addShutdownHook`（幂等、只装一次）杀残留树。
- 夹具：`StubbornFixtureServer`（忽略 stdin 关闭且派生孙进程）＋ 新断言「`close()` 在
  `500ms + closeTimeout` 内返回」「孙进程存活数归零」。
- 不追求毫秒级断言：只断言**顺序**（先请求退出、超时后才强杀）与**总时长上界**。

### S2 `#22b` 行读取改为按块扫描（w1 同上单元）

- `StdioLineFrames.pump` 改为：`byte[8192]` 读块 → `indexOf('\n')` 扫描 → 未完成尾巴按块缓存、
  换行时 join 一次；`\r` 末尾裁剪与 `maxMessageBytes` 语义不变（超限报
  `MCP message exceeds N bytes` 并停止 pump）。
- 夹具：一条 2 MiB 单行 JSON（`StdioFixtureServer` 加 `emitLarge`）断言能完整解析、且 4 MiB 上限触发错误。

### S3 `#25`／`#26` HTTP JSON 限长与 body 取消（w2）

- `JdkMcpHttpFetch` 增 `readBodyText(response, maxBytes)`：分块读、累计超限即停并返回 `truncated`
  （与 pi 同形），**同时 cancel 剩余 body**。
- `StreamableHttpTransport` JSON 分支：`maxMessageBytes` 限长，超限抛
  `MCP JSON response exceeds N bytes`；错误体改用同一函数（保留 8 KiB 摘录、取消剩余）。
- SSE 侧：确认 `SseStreamConsumer` 已有等价限长；若有差则同批修。
- 夹具：`ScriptedHttpServer` 加「4 MiB JSON」「无限流 body」两条，断言错误文案与「未把整个 body 读进内存」。

### S4 `#36` OAuth 刷新语义（w2）

- `OAuthFlow.authorizeMcp` 增 `refreshOnly` 开关：为 true 时**跳过**动态客户端注册与授权请求准备。
- `McpAuthProviders.refresh` 改用 `refreshOnly=true`；redirect URI 候选链按 pi 三段式；
  增 `settled()`（等单飞结束）。
- `McpOAuthCredentialStore.withRefreshLock` 增可选中止源：等锁期间可中止（抛
  `McpSignInCancelledError` 的等价物），**已发出的刷新不取消**。
- 夹具：`ScriptedMcpFetch` 加「刷新期间不得调用 register」断言；锁竞争用例断言可中止。

### S5 `#37`／`#42` 两段式 sign-in ＋ loopback 拆分（w2）

- `McpSignIn` 拆为 `start(Options) → McpSignInStart`（`authorized` ／ `redirect` 数据记录
  `{authorizationUrl, redirectUrl, state, scope}`）与 `complete(Options, McpAuthorizationResponse)`；
  原 `signIn(...)` 保留为两步的便捷封装（CLI 与扩展继续用）。
- `McpLoopbackSignIn`（新）：`listen(options)` / `await(options)` / `responseFromRedirectUrl` 迁出
  `McpSignIn`（公开面，供 https 回调路由复用）。
- `start` 内先试刷新（`insufficient_scope` 除外）；`clientRegistration=cimd` 且无 `clientId` ⇒ 直接走浏览器步。
- 夹具：现有 `McpSignInTest` 拆成 start／complete 两组；新增「start 返回纯数据、可跨进程持久化」用例。

### S6 `#67`／`#68` CLI provider token 与提示（w2）

- `McpCommand.Options` 增 `providerToken`（缺省 reader：agentDir `auth.json` → env 兜底）；
  `McpCommandSupport.connection(...)` 传给 `McpConnectionOptions.providerToken`。
- `McpListCommand` 的 `needs-auth` 报告带 `authProvider`：有则打
  `sign in with: /login <provider> in pi-java`，无则维持 `pi-java mcp login <name>`。
- 夹具：`McpListCommandTest` 加「`auth.provider` 型服务器用 provider token 连上」与「提示文案切换」两条。

### S7 `PipeTransport`（新 w1，D1 通过后做）

- 新 `transport/PipeTransport`：`McpPipe` 接缝（`write`／`closeStdin`／`close`／`exited`）＋
  `open(handlers)` 工厂；`start/send/close` 与 pi 同序（`close` = 关 stdin → `race(exited, closeTimeout)`
  → `pipe.close()`）；复用 S2 的行读取。
- 夹具：`test/fixture/PipeFixture`（内存管道 + 可控 `exited`）断言「启动即关闭」「退出先报错再 close」。

## 5. 风险与回退

- **Windows 无 SIGTERM**：S1 的两段在 Windows 上退化为「destroy → destroyForcibly」，仍是先礼后兵；
  测试只断言顺序与上界，避免平台相关抖动。
- **两段式是公开面变更**：`signIn` 保留封装，扩展与 CLI 不必改调用点；新增 API 只加不改。
- **`refreshOnly` 的副作用**：关闭「刷新时注册 client」可能让个别服务器的 CIMD 刷新失败 →
  失败时仍回落 `McpOAuthAuthorizationRequiredError`（要求重登），不会静默用错 token。
- **每阶段独立可编译**；做完一层再按 `docs/04` §0 的「三处同步」更新读数。

## 6. 测试总表

| 阶段 | 新增／改动夹具 | 断言要点 |
|---|---|---|
| S1 | `StubbornFixtureServer`（含孙进程） | 关闭顺序、时长上界、后代归零 |
| S2 | `StdioFixtureServer.emitLarge` | 2 MiB 单行可解析；超限报错并停泵 |
| S3 | `ScriptedHttpServer`（4 MiB JSON／无限流） | 超限文案、body 被取消、错误体只读摘录 |
| S4 | `ScriptedMcpFetch`（禁注册）＋ 锁竞争 | 刷新不注册 client；等锁可中止；`settled()` |
| S5 | `McpSignInTest` 拆两组 ＋ `AuthorizationServerFixture` | 两段数据可持久化；state／redirect 校验不变 |
| S6 | `McpListCommandTest` | provider token 生效；`/login <provider>` 文案 |
| S7 | `PipeFixture` | 启动即关闭、退出先报错、close 超时强杀 |

## 7. 提交切分（建议 7 个）

`feat(mcp): close stdio servers like pi` → `perf(mcp): scan stdio frames by chunk` →
`fix(mcp): bound streamable-http JSON responses` → `fix(mcp): refresh oauth tokens without registering` →
`feat(mcp): split sign-in into start and complete` → `feat(cli): pass provider tokens to mcp list` →
`feat(mcp): add a pipe transport for caller-run programs`

## 8. 实施记录

| 阶段 | 日期 | 提交 | 落地要点 | 测试 |
|---|---|---|---|---|
| S1 `#22` | 2026-11-22 | `02b6bf59` | 关闭三段式：关 stdin → `min(500ms, closeTimeout)` 宽限 → SIGTERM 等价 → 再 `closeTimeout` → SIGKILL；**关 stdin 前抓进程树快照**（父进程一退 `descendants()` 就空），父已退也给后代补一刀；`liveProcessGroups` 等价（JVM shutdown hook） | `McpStdioTest` +`aServerIgnoringStdinIsTerminatedAfterTheGracePeriod`（旧实现必 >2000ms，现 ~520ms）；既有 stubborn 用例仍绿 |
| S2 `#22b` | 2026-11-22 | `db9f8646` | `StdioLineFrames.pump` 改按 8 KiB 块扫描 `\n`，未完成尾巴按块留存、换行时只 join 一次；`maxMessageBytes` 与 CRLF 语义不变 | 新增 `StdioLineFramesTest` 5 例（跨块重组、多帧＋CRLF、2 MiB 单行、超限、EOF 残帧） |
| S3 `#25`/`#26` | 2026-11-22 | `d80b326f` | JSON 分支读 `maxMessageBytes`（超限抛 `MCP JSON response exceeds N bytes`）；错误体改 `readBodyText`：只读摘录并**关流取消剩余**（原实现读 8 KiB 后不关流） | 新增 `StreamableHttpBodyLimitsTest` 3 例（超限失败＋流关闭、限内投递、20 KB 错误体截断＋流关闭） |
| S4 `#36` | 2026-11-22 | `cd942ef` | `OAuthFlow` 增 `refreshOnly`：无 client 时直接 `REDIRECT`（不注册）、刷新失败后也 `REDIRECT`（不覆盖待完成登录的 `state`／PKCE verifier）；`invalid_client`／`unauthorized_client` 在 refreshOnly 下只在「存的就是这次用的 client」时才作废 client／tokens／discovery，非 refreshOnly 仍 `all`；换码前先校验 redirect URI 与授权请求一致（`flow.ts:386-400`）。`McpAuthProviders` 刷新传 `refreshOnly=true`；`settled()` 复核为已等价 | 新增 `McpOAuthRefreshTest` 3 例（无 client 不注册、`invalid_client` 只丢 client+tokens 而保住 `state`/verifier、`invalid_grant` 只丢 tokens）；`OAuthFlowTest` +3 例（refreshOnly 不注册、刷新失败不强推授权、换码 redirect URI 不一致先报错）；`AuthorizationServerFixture` 增 `REJECT_CLIENT`／`REJECT_REFRESH` 两模式 |

> S4 的两处**有意偏离**（`#36` 判据以外，不引入无用面）：
> 1. 未加 pi `createMcpAuthProvider({redirectUrl})` 的应用侧 redirect 入口——production 调用方
>    （`runtime.ts:190-197`、`oauth.ts:175-182`）都不传；Java 候选链 `[fixed, registered[0], FALLBACK]`
>    与 pi `[app, fixed, registered[0]] → documents 过滤 → DEFAULT` 在合法配置下等价（documents 过滤只对
>    非 `/callback` 路径生效，而那种配置本来就不出文档）。
> 2. 「等刷新锁时可中止」随 S5 一起做：pi 的 `signal` 挂在 `startMcpSignIn`，Java 的 `McpSignIn.Options`
>    目前没有取消入口，单调用 `signIn` 的取消只在粘贴回调那一步。

> 模块测试：`pi-java-mcp` 289 → 292（S1–S3）→ **298**（S4）全绿；`pi-java-coding-agent` 的 MCP 相关
> 241 例全绿（S1 改动经 `McpServerConnection` 的关闭路径覆盖）。**读数暂不动**：按 `docs/04` §0，
> 逐层收口时才三处同步，现仍记 L2 = 115.0/123（投影）；S5–S7 落地后统一重算。
