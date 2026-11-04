# 07 MCP（pi-java-mcp ＋ coding-agent MCP 集成面）

> 【背景摘要】本图保留 MCP 的最终能力判定与源码证据。批次过程已移除；`56cae7823` 换锚后先按新基线全量重测，再以 docs/21（W1–W12）对齐 structuredContent、连接期资源枚举、OAuth 注册策略、取消传播与 `mcp.json` 字节形态；2026-11-04 闭环。

## 基准与口径

- 基准：pi-java @ **`30abb76c`**（main，2026-11-04，docs/21＋docs/22 代码终态）；pi @ **`56cae782341c339d3bb820081f0f9425d8c5b1ec`**（2026-10-31）。
- 判定三档：**对齐** = pi 有 ∧ pi-java 有 ∧ 行为相同（读代码确认语义）；**缺失** = pi 有 ∧ pi-java 无（或差异有行为后果）；**存疑** = 形状/字节形态未逐字核对，或差异是刻意设计/跨模块/台账在册方言（子标签 **（形状）/（扩展）/（台账）**）。
- 第四类记账 **转出（非本层）**：差异归属其它层或共享平台方言，行与证据保留在本图供追溯，但**权重 0、不进 MCP 分母**（docs/22 用户裁决）。
- 所有证据为 `file:line`，两侧均已打开源码确认。**pi 侧一律 `git -C D:/workplaceForai/pi show 56cae7823:<path>` 取证，未读工作树。**
- **范围**：pi `packages/mcp`（SDK 本体）＋ `packages/coding-agent/src/extensions/mcp/**` 与 `core/mcp-servers.ts`（集成面）↔ pi-java `pi-java-mcp` ＋ `pi-java-coding-agent` 的 `extension/mcp/**` 与 `subcommand/Mcp*`。
- **权重** = 用户可观察影响 × 频率（不含排期优先级）：**3** = 每轮对话都走/默认路径；**2** = 每次会话走/常用命令/常用配置；**1** = 低频/边缘/纯内部；**0** = 非目标，**排除出分母**。完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0。
- **R5 口径**：codemode 及其专用激活路径不做、不进分母；tool_search 和 deferred MCP 工具发现已有实现，属于主流复核面。wire、配置、CLI 和适配形状继续按行为计分。新锚点的 `structuredContent` 还需区分 codemode 消费与 `after_tool` 等主流消费。

---
## 规模

| 模块 | pi 路径 | pi LOC | java LOC | 文件 pi→java |
|---|---|---:|---:|---|
| SDK 主源码 | `packages/mcp/src` | **3,287** | `pi-java-mcp` **11,141** | 18 → 147 |
| SDK 测试 | `packages/mcp/test` | **1,690** | `pi-java-mcp/src/test` **6,980** | 8 → 46（`@Test` **286**） |
| 集成面主源码 | `coding-agent/src/extensions/mcp/**` ＋ `core/mcp-servers.ts` | **4,677** | coding-agent `extension/mcp/**` ＋ `subcommand/Mcp*`＋`BrowserLauncher` **5,875** | 12 → 34 |
| 集成面测试 | `coding-agent/test/mcp-*.ts`（5 个）＋`mcp-conformance`（4 件） | **2,533** | coding-agent `…/mcp`＋`subcommand/Mcp*` 测试 **3,964** | 9 → 23（`@Test` **226**） |

> pi 集成面 12 文件：`extensions/mcp/{index 1347, cli 625, oauth 579, runtime 454, tools 391, resources 339,
> config 243, ui 268, log 69, 两个 .lazy 各 2}` ＋ `core/mcp-servers.ts 358`（新锚点实测）。
> java 文件数远多是「一件一类」风格所致（pi 单文件多导出），非规模差。
> 本图只数 MCP 命名测试文件：SDK **286** ＋ 集成 **226** ＝ **512** 个 `@Test`，可 `grep -c @Test` 复算。

pi SDK 逐文件（`packages/mcp/src`）：`client.ts` 621 · `transports/streamable-http.ts` 522 ·
`oauth/flow.ts` 485 · `transports/stdio.ts` 216 · `oauth/discovery.ts` 215 · `oauth/types.ts` 215 ·
`oauth/callback.ts` 164 · `oauth/provider.ts` 168 · `protocol/content.ts` 118 · `protocol/jsonrpc.ts` 113 ·
`protocol/types.ts` 131 · `transports/in-memory.ts` 51 · `transports/transport.ts` 64 · 其余 5 文件 204。

---

## A. 配置（`mcp.json` ／ 注册校验）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | `mcpServers` 逐条校验（名/type/url/command 分派、`sse` 拒绝、headers/env/args 串记录） | 配置 | `core/mcp-servers.ts:221-345` | `config/McpServerConfigs.java:57` | 对齐 | 2 | — |
| 2 | global/project 作用域＋信任门＋project override 仅三键合并 | 配置 | `extensions/mcp/config.ts:145-156` | `config/McpConfig.java:50` | 对齐（目录名 `.pi`→`.pi-java` 刻意） | 2 | B182/B213 |
| 3 | extension 作用域＋`McpServerRegistry`（register/unregister 所有权/get/list 深拷贝/changeListener） | 配置 | `core/mcp-servers.ts:300-345` | `config/McpServerRegistry.java`；`config/McpScope.java:14` | 对齐（生产者见 87） | 1 | — |
| 4 | 校验返回**原对象副本**，未知键保留 | 配置 | `core/mcp-servers.ts:260-357` | 校验按具名键重建；`mcp add` 整条覆盖丢未知键、`/mcp` update 编辑原 JSON 树保留未知键——与 pi 两条可观察写路径同形 | 对齐（A4 口径修正：可观察路径等价） | 1 | ~~B181~~（docs/21 W12a 销） |
| 5 | 项目配置目录读 `package.json` 的 `piConfig.configDir` | 配置 | `config.ts:581` | `McpConfig.java:33` 硬编码 `.pi-java`，无覆盖机制 | 转出（非本层：L5 配置子系统） | 0 | B182（docs/22 裁决） |
| 6 | 解析失败错误文本（`<路径>: <消息>`；空文件） | 配置 | `config.ts:97-100` | `McpConfig.java:169`（空文件报 expected-object） | 转出（非本层：共享 JSON 解析方言） | 0 | B183（docs/22 裁决；BOM 部分已由 #95 关闭） |
| 7 | 写出字面量：裸 `Error`、JSON 数字/键序 | 配置 | `config.ts:228-242` 抛裸 Error；`cli.ts:340-394` 键序 | `config/McpConfigError.java`；`McpConfig.writeServerConfig:146` 按 pi 键序建对象；`JsJson.java:97` 整数字面量归整（`1.0`→`1`） | 对齐（A7 字节对齐） | 1 | ~~B184~~（docs/21 W12b 销） |
| 8 | exposure 四值＋`codemode-deferred` 别名＋`toolExposure` 精确/`*`模式＋默认 codemode | 配置 | `mcp-servers.ts:46-52,176-215` | `McpServerConfigs.java:211`；`config/McpExposure.java` | 对齐 | 2 | — |
| 9 | `mcpNamespace`（`-`→`_`）＋`-`/`_` 同名冲突检测 | 配置 | `mcp-servers.ts:120-123,265`；`config.ts:131` | `McpServerConfigs.java:200`；`McpConfig.java:201` | 对齐（排序用 `compareTo` 非 `localeCompare`，见 75/台账 B219） | 2 | B219 |
| 10 | add/remove/update 写回：缩进探测保留、默认值删键、override 保留默认 | 配置 | `config.ts:169-242` | `McpConfig.java:71-155`；`config/JsJson.java` | 对齐 | 2 | — |
| 11 | 顶层 `autoEnableCodemode` 键（默认 true，project 覆盖 global） | 配置 | `config.ts:101-103` | `McpConfig.java:173` | 对齐（消费者随 93 出分母） | 1 | — |
| 12 | `oauth` 子配置九条校验（loopback 主机/端口一致/cimd 组合/metadataUrl 协议/`clientRegistration` 三值） | 配置 | `core/mcp-servers.ts:179-215`（`"auto"｜"dcr"｜"cimd"`） | `config/McpServerConfigs.java:258-318`（`auto` 已接受；错误文案逐字） | 对齐（W5） | 1 | — |
| 13 | 配置值解析 `${NAME}`/`$NAME`/`!cmd`/`$$`/`$!`＋命令缓存＋resolveOrThrow 文案 | 配置 | `core/resolve-config-value.ts` | `runtime/ConfigValueResolver.java:56` | 对齐（shell 第一层见 24） | 2 | — |
| 95 | `mcp.json` UTF-8 BOM 剥离（读与写回两路） | 配置 | `extensions/mcp/config.ts:92-100,229-232` | `config/McpConfig.java:42,263,363,394`（两路 `stripBom`） | 对齐（W8） | 1 | — |

## B. 协议与客户端（`packages/mcp`）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 14 | JSON-RPC 线层（解析/校验、标准错误码、McpError/Timeout/Abort/ConnectionClosed） | 协议 | `protocol/jsonrpc.ts` | `protocol/jsonrpc/JsonRpcMessages.java` 等 7 件 | 对齐 | 2 | — |
| 15 | `initialize` 版本协商（LATEST/SUPPORTED）、capabilities 交换、serverInfo、instructions | 协议 | `client.ts:60-150` | `McpClient.java:131-145`；`protocol/McpVersion.java` | 对齐 | 3 | — |
| 16 | 列表分页（cursor、上限 1000 页、`null`/`""` cursor 归一） | 协议 | `client.ts:33,82-105` | `McpClientPages.java:15`；`McpClientValidators.java` | 对齐 | 2 | — |
| 17 | `roots/list` 提供方（cwd file URL ＋ basename） | 协议 | `extensions/mcp/runtime.ts:383` | `McpConnector.java:59`；`McpClient.java:72` | 对齐 | 1 | — |
| 18 | 通知订阅（`notifications/message`、progress、`$/cancel`、两个 list_changed） | 协议 | `client.ts`（onNotification） | `McpClient.java:249`；`McpConnector.java:64-75` | 对齐 | 2 | — |
| 19 | 请求超时（默认 30s、progress 通知重置、abort）＋请求落定中止 send 信号 | 协议 | `client.ts:60-70,431,439-441,582` | `McpClient.java:48,213,360`；`transport/McpTransportSendOptions.java`；`StreamableHttpTransport.java:154`（派发前判 `isAborted`） | 对齐（W3） | 2 | — |
| 20 | in-memory transport | 协议 | `src/testing/index.ts` 公开发布；`transports/in-memory.ts:15-43`（`started` 门/级联关闭/`emitError`） | `src/main/java/com/pijava/mcp/transport/InMemoryTransport.java:20`（已移入主源集；同三语义） | 对齐（W9） | 1 | — |

## C. 传输

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 21 | stdio：cross-spawn、换行帧、消息字节上限、stderr 64KB ring、不完整消息/退出报错 | 传输 | `transports/stdio.ts:78-200` | `transport/StdioTransport.java`；`StdioLineFrames.java`；`StdioProcess.java` | 对齐 | 2 | — |
| 22 | stdio 关闭序列（stdin end→500ms grace→SIGTERM→2s→SIGKILL）＋进程树（Win taskkill / 负 pid） | 传输 | `stdio.ts:33-44,153-176` | `StdioTransport.java:94-105`；`StdioProcess.java:64-73`（`descendants()`） | 对齐 | 1 | — |
| 23 | stdio `cwd`（相对路径相对会话目录解析） | 传输 | `stdio.ts:88`；`runtime.ts:97-121` | `StdioProcess.java:29`；`runtime/McpDefaultTransport.java:60` | 对齐 | 2 | B199（已修） |
| 24 | `!cmd` 两层壳：先 `settings.shellPath`、ENOENT 回落默认 | 传输 | `resolve-config-value.ts`（settings 壳注入） | coding `extension/mcp/ShellPathCommandRunner.java`（批 24：两段式——配置壳优先、spawn 失败回落 `JdkShellRunner`；经 `McpConnectionOptions.resolver` 注入会话内连接） | 对齐 | 1 | ~~B188~~（批 24） |
| 25 | Streamable HTTP：POST/GET、`Mcp-Session-Id`、404 session expired、405 无 GET、DELETE 1s、`send(message,{signal})` | 传输 | `transports/streamable-http.ts:231-245,295-305` | `transport/http/StreamableHttpTransport.java:154`（每次派发前 `if (signal.isAborted()) throw McpAbortError`） | 对齐（W3） | 2 | B201（已修） |
| 26 | SSE 流消费＋`Last-Event-ID` 断线续 GET 流＋408/429/5xx 退避重试 | 传输 | `streamable-http.ts:35-100,366-497` | `http/SseStreamConsumer.java`；`http/StreamableHttpStreams.java` | 对齐 | 2 | — |
| 27 | `isTransientError` 网络错分类 | 传输 | `extensions/mcp/runtime.ts:70-75`（HTTP 408/429/5xx≠501 或 `TypeError`） | `McpConnectionSupport.java:112-121`（状态判同；网络类是 `IOException`，宽于 `TypeError`） | 转出（非本层：平台异常分类方言） | 0 | B190（docs/22 裁决） |
| 28 | OAuth/AS 请求 15s 边界（unresponsive AS 不持刷新锁） | 传输 | `extensions/mcp/oauth.ts:51` | `runtime/TimedMcpFetch.java:13`（外包一层，未扩 Fetch 接口；W3 补 send signal 后整条对齐） | 对齐 | 1 | ~~B194~~（docs/21 销） |
| 29 | `connectOnce` 只列工具；`hasResources` 取 capability（连接期不枚举资源） | 传输 | `runtime.ts:359-390` | `McpConnector.java:80` 只 `listTools`；`McpServerConnection.java:50,113`（`hasResources` 布尔） | 对齐（W4） | 1 | ~~B196~~（docs/21 销） |
| 30 | WHATWG `new URL` 归一（主机小写/默认端口/补尾斜杠）＋相对引用拒绝 | 传输 | 各处 `new URL`/`URL.canParse` | `runtime/WebUrls.java`；`responseFromRedirectUrl` 已挡 scheme | 转出（非本层：平台 URL 归一方言） | 0 | B191/B197（docs/22 裁决） |
| 31 | `McpFetch` 抽象（可换 fetch）＋JDK http client 实现 | 传输 | `auth-provider.ts` | `McpFetch.java`；`JdkMcpFetch.java`；`transport/http/JdkMcpHttpFetch.java` | 对齐 | 1 | — |

## D. OAuth（MCP 10.x：PKCE 授权码＋DCR，无设备流）

> pi MCP OAuth **没有设备流**（`git grep device` 在 `packages/mcp` 与 `extensions/mcp` 零命中）；
> 设备流属 ai 模块的 provider OAuth。本族 = 受保护资源元数据→AS 元数据→PKCE→DCR/预置 client→loopback 回调。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 32 | 受保护资源元数据发现（`/.well-known/oauth-protected-resource`，路径后缀候选） | OAuth | `oauth/discovery.ts` | `oauth/OAuthDiscovery.java:64-84` | 对齐 | 2 | — |
| 33 | AS 元数据（RFC 8414 ＋ OIDC discovery 多候选）＋`authServerMetadataUrl` 受信覆盖 | OAuth | `discovery.ts` | `OAuthDiscovery.java:93-100,168` | 对齐 | 1 | — |
| 34 | PKCE S256（WebCrypto verifier/challenge；服务器不支持 S256 即拒） | OAuth | `oauth/flow.ts:163-199` | `oauth/OAuthPkce.java`；`OAuthFlow.java` | 对齐 | 2 | — |
| 35 | 动态客户端注册（DCR）＋`clientInformation` 持久化/读回 | OAuth | `flow.ts:246`；`oauth/provider.ts` | `oauth/OAuthProviders.java`；`OAuthClientProvider.java`；`OAuthTokenRequests.java:95` | 对齐 | 2 | — |
| 36 | 授权码交换、令牌刷新、`stepUpScope`（insufficient_scope 合并 scope，SEP-2350） | OAuth | `flow.ts:277-316,452-468` | `OAuthFlow.java:90-117`；`runtime/McpAuthProviders.java:179-254` | 对齐 | 2 | — |
| 37 | `mcp-auth.json` 存储（server+URL 键、legacy key、进程间刷新锁、stale 接管）＋整文件锁读修复 | OAuth | `extensions/mcp/oauth.ts:141-230` | `runtime/McpOAuthCredentialStore.java`；`runtime/FileAuthJsonBackend.java:74-88` | 对齐 | 1 | B215（已修） |
| 38 | `mcp-auth.json` 字节形态（`extension` 嵌套、null≡undefined 省略） | OAuth | `oauth.ts:116-130` | `oauth/OAuthClientInformation.java:24` | 转出（非本层：共享 JSON 序列化方言） | 0 | B193（docs/22 裁决） |
| 39 | loopback 回调服务器（free port/固定端口/固定 URI，RFC 8252） | OAuth | `oauth/callback.ts` | `oauth/OAuthCallbackServer.java`；`runtime/McpCallbackSettings.java` | 对齐 | 2 | — |
| 40 | 回调成功/失败页 HTML/CSS | OAuth | `oauth/callback.ts`（内嵌页） | `pi-java-ai/.../utils/OAuthPage.java`（共享件） | 转出（非本层：L1 ai 共享 OAuthPage） | 0 | B198（docs/22 裁决） |
| 41 | redirect 等待：TTY 判定＋readline 与 abort 竞速＋手工粘贴 URL | OAuth | `oauth.ts:396-470` | `subcommand/McpRedirectPrompt.java`；`runtime/McpSignIn.java:232-265` | 转出（非本层：JVM 平台方言） | 0 | B212（docs/22 裁决） |
| 42 | `signInMcpServer` 编排（challenge、300s 默认超时、取消即 cancelled） | OAuth | `oauth.ts:473-558` | `runtime/McpSignIn.java:77`；`McpSignInCancelledError.java` | 对齐 | 2 | — |
| 43 | `clientRegistration` 三值＋`auto` 决策树（pi.dev Client ID Metadata Document） | OAuth | `core/mcp-servers.ts:112-125,136-146`；`oauth.ts:250-330,504-530` | `runtime/McpOAuthSettings.java:62,84`（`auto` 决策树；改名 build 恒回落 DCR、显式 `cimd` 才申请文档）；`McpClientMetadataDocuments.java:25`（拉 pi.dev 托管文档） | 转出（非本层：OAuth 身份托管基础设施） | 0 | B192（W5 补齐 auto；docs/22 裁决） |
| 44 | `auth.provider`：用 pi provider token 代替 OAuth（ReadThroughProvider） | OAuth | `index.ts:307`（`modelRegistry.getApiKeyForProvider`） | `McpServerConnection.java:78-83`；批 24 会话臂注入生产源（`McpLifecycle` providerToken＝OAuth 凭证优先/回落 api key，每请求现读） | 对齐 | 1 | ~~B189~~（批 24 会话臂；CLI 臂先齐） |
| 45 | `WWW-Authenticate` challenge 解析（resource_metadata_url/scope/error） | OAuth | `flow.ts:452`；`oauth/errors.ts` | `oauth/WwwAuthenticate.java`；`McpAuthProviders.java:179` | 对齐 | 1 | — |
| 46 | login 端到端真授权服务器夹具 | OAuth | `packages/mcp/test/oauth.test.ts:96-280`（真 HTTP AS＋授权端点重定向＋PKCE 交换＋刷新） | `runtime/McpSignInEndToEndTest.java`＋`runtime/AuthorizationServerFixture.java`（发现→DCR→PKCE→回调→交换→刷新；错误 state/iss/verifier；`/mcp` 401/200） | 对齐（W2c） | 1 | ~~B217~~（docs/21 销） |

## E. 工具 / 资源 / 结果

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 47 | `toLlmContent`：五块型转换（audio/link/binary 占位）＋空 content 回退 structuredContent 任意 JSON（数组/`null`/标量） | 结果 | `protocol/content.ts:111-117` | `protocol/content/McpContents.java:46`（`JsJson.stringify` 回退）；`LlmContent.java` | 对齐（W1） | 2 | — |
| 48 | 图片序：mcp 侧 `Image(data,mimeType)` 与 ai `ImageContent(mediaType,data)` 相反，唯一换序点 | 结果 | `protocol/content.ts:18` | `LlmContent.java:14`；coding `extension/mcp/McpResultContent.java:196` | 对齐（两侧夹具钉死） | 1 | B208（已裁） |
| 49 | `CallToolResult.structuredContent`/`isError` 线格形状（`structuredContent: unknown`） | 结果 | `protocol/content.ts:64-70`；`client.ts:144-150`（不校验对象） | `protocol/content/CallToolResult.java:20`（`JsonNode`）；`McpClientValidators.java:90-108` | 对齐（W1） | 2 | — |
| 50 | structuredContent **主流消费链**（`after_tool`／结果全文载荷；codemode 消费按 R5） | 结果 | `extensions/mcp/tools.ts:266-290`（`convertMcpResult` 去 `_meta` 全量） | `McpResultConverter.java:67-75`（完整结果作为载荷） | 对齐（W1） | 1 | ~~B202~~（W1 拆分后销：codemode 臂随 R5 出分母） |
| 51 | 协议 `Tool` 四属性（title/inputSchema/outputSchema/annotations） | 结果 | `protocol/types.ts:64-82` | `protocol/McpTool.java:21-27`；`ToolAnnotations.java` | 对齐 | 2 | — |
| 52 | MCP→AgentTool 适配（label、description 三级回退、schema 补 `type`/`properties`、progress onUpdate、并行批） | 结果 | `tools.ts:260-300` | coding `extension/mcp/McpToolDefinition.java:103-209` | 对齐 | 2 | — |
| 53 | `AgentTool` 承载 exposure/namespace/outputSchema/annotations（＋defaultActive） | 结果 | `core/extensions/types.ts` `ToolDefinition` | agent-core `AgentTool` 五 default 方法＋`McpAgentTool`/`ResourceAgentTool` 覆写（批 30 收编，sidecar 删除） | 对齐 | 1 | B203（批 30 销） |
| 54 | `ToolExposure` 五值（direct/model-only/codemode/deferred/hidden）＋映射（codemode→deferred） | 结果 | `extensions/types.ts:516`；`tools.ts:45-47` | agent-core `tool/ToolExposure.java`；`McpToolExposure` 收窄为纯映射 | 对齐（批 30 上移落定） | 1 | B207（批 30 销） |
| 55 | `isError` 作为结果语义通道（runner 读结果字段而非只看抛错；after_tool 可改写） | 结果 | `tools.ts:228-233` | `McpResultConverter.java:19-51`；`ToolResult.isError()` | 对齐 | 2 | B206（已裁） |
| 56 | 工具名归一：`mcp__s__t`、非字母数字→`_`、64 字符上限、sha256-8 后缀、isTaken 冲突裁定 | 结果 | `tools.ts:73-97`；`index.ts:398-420` | coding `extension/mcp/McpToolNames.java:47-65` | 对齐 | 2 | — |
| 57 | 结果 20KB 中段截断＋token 估算＋全文落盘 `fullOutputPath`（locale 修复） | 结果 | `tools.ts:51,124-149` | `McpResultContent.java:33,106-129`；`McpOutputSaver.java` | 对齐 | 2 | B209（已修） |
| 58 | 二进制资源落盘＋文本 mime 直显＋`resource_link` 文案＋readableResources | 结果 | `tools.ts:160-202` | `McpResultContent.java:150-190` | 对齐 | 1 | — |
| 59 | 每工具 `CallToolResult` outputSchema 包装（content/structuredContent/isError；无 `_meta`；`$ref` 重定位） | 结果 | `tools.ts:104-176` | `extension/mcp/McpResultSchema.java:50`（`relocateSchema`＋`additionalProperties:true`） | 对齐（W10） | 1 | — |
| 60 | 三个资源工具定义（list/templates/read）＋MCP App 资源过滤 | 结果 | `extensions/mcp/resources.ts:47,194-339` | `McpResourceTools.java:39-428`；`mcp/runtime/McpAppResources.java:34-58` | 对齐（**无生产消费者**，随 83/85 激活） | 1 | — |
| 61 | 服务器 `timeout`（秒，默认 60，progress 重置） | 结果 | `core/mcp-servers.ts:74` | `runtime/McpServerConnection.java:40-145` | 对齐 | 2 | — |
| 62 | `tools/list_changed` 刷新（capability 门控；资源按需读取） | 结果 | `runtime.ts:359-390` | `McpConnector.java:71-75` | 对齐（W4） | 1 | — |
| 63 | `mcp.log` 服务器日志（`notifications/message` 追加文件） | 结果 | `extensions/mcp/log.ts:1-69` | `runtime/McpServerLog.java` | 对齐 | 1 | — |
| 64 | stdio 连接失败 stderr tail（2000 字符）拼进错误 | 结果 | `runtime.ts:421-430` | `McpConnector.java:111-133`；`transport/StderrTail.java` | 对齐 | 1 | — |
| 94 | `reachesTools` 工具合约（`AgentTool.reachesTools`＋`tool_search` 声明 `"all"`＋`getAllTools` 暴露） | 结果 | `core/extensions/types.ts:635-648`；`tool-search/tool.ts:320-323`；`agent-session.ts:1539` | agent-core `tool/ToolReach.java`；`AgentTool.java:92`；`ToolSearchTool` 覆写；`McpLifecycle.hasReachingTool:317` | 对齐（W7） | 1 | — |

## F. CLI `pi-java mcp`（进程外：add/remove/list/login/logout）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 65 | `add`（stdio/http、12 个已知选项含 `--oauth-client-registration`、`--` 透传、http/stdio 误配门、替换后检查提示） | CLI | `extensions/mcp/cli.ts:303-420` | `subcommand/McpAddRemoveCommand.java:40,59,96`（新选项 http-only） | 对齐（W5） | 2 | — |
| 66 | `remove [-l]`（存在性/作用域不符提示） | CLI | `cli.ts:396-435` | `McpAddRemoveCommand.java:161-201` | 对齐 | 2 | — |
| 67 | `list [--json]`（状态/工具数/错误聚合，失败 exit 1，JSON 两空格；`resources?: true`、人类行 `offers resources`） | CLI | `cli.ts:100-108,465-545` | `subcommand/McpListCommand.java:59-170`（无 templates/数量） | 对齐（W4） | 2 | — |
| 68 | `login <server> [-l] [--timeout 300]` ＋ `logout`（作用域提示、拼错建议、连接探测 OAuth、凭证删除） | CLI | `cli.ts:220-265` | `subcommand/McpCommand.java:143-220`（Levenshtein ≤2 建议） | 对齐（W6） | 2 | — |
| 69 | 自建选项解析「收够 N 个位置参数即停止」透传 | CLI | `cli.ts:144-182` | `subcommand/McpCliOptions.java:62-109` | 对齐（picocli 不能表达，手写复刻） | 1 | B210 |
| 70 | 开浏览器（Desktop→平台命令→静默失败；headless 提示访问 URL） | CLI | `utils/open-browser.ts` | `subcommand/BrowserLauncher.java` | 对齐（pi-java 新增件） | 1 | B211 |
| 71 | 文案命名（`pi`→`pi-java`、`.pi`→`.pi-java`、去 chalk 着色） | CLI | `cli.ts:33-76`；`cli/args.ts:294` | `subcommand/McpCliHelp.java`；`cli/HelpText.java`（W6 补 `mcp <command>` 行；内容同 pi、仅改名） | 转出（非本层：全仓品牌改名方言） | 0 | B213（docs/22 裁决） |
| 72 | MCP 客户端身份由调用方注入（`"pi"`/VERSION → `"pi-java"`/VERSION） | CLI | `runtime.ts:380-381` | `subcommand/SubcommandHandler.java:44-47`；`McpCommand.Options` | 对齐 | 1 | B189（已裁） |

## G. 生命周期与接线（`createMcpExtension` —— **B221 接线面**）

> pi 的集成是一个扩展工厂（`index.ts` 1344 行），靠扩展 API 六个事件＋工具/命令/渲染器挂载面活着。
> pi-java 有钩子系统但**扩展事件面是另一域**（D 域/E5）；MCP 运行时**唯一生产消费者是进程外 CLI**（F 组）。
> 下列除 75/88 两件纯件外**全部缺失**——缺的不是 MCP 语义，是挂载点。
> **批 23 回填**：81（激活门）/84（渲染器挂载面）批 16 已落（本批收平账面滞后）、87（registerMcpServer 挂载点）批 23 T1 落——缺失 15→12；余 12 行全随 B221① 内置扩展本体批（批 24 候选）。
> **批 24 回填（2026-10-27，docs/35）**：余 12 行＋#24（B188）＋#44（B189 会话臂）全数落齐——
> G 组 23.0/23＝**100%（满分域）**；缺失清零（计分母余 #50 structuredContent 消费者，挂 B202/codemode）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 73 | 扩展工厂＋`session_start`：读两源配置、后台连接、runtime 惰性加载（无服务器不付费）、问题聚合、状态文案按 `hasResources` 布尔 | 接线 | `index.ts:234-252,283-302,1111-1151,640-655` | `extension/mcp/McpExtension.java`（onSessionStart）＋`McpLifecycle`；`McpExtensionState/McpServerPresentation`（` · resources` 按布尔，W4） | 对齐（W4）；批 24 裁决⑦ 惰性加载不模拟——虚拟线程懒连即无服务器零成本 | 2 | ~~B221~~/~~B195~~（批 24） |
| 74 | `before_agent_start`：direct 服务器首提示等待 10s（超时放行＋通知） | 接线 | `index.ts:1156-1184` | `McpLifecycle.waitForDirectServers`（文案逐字；批 24） | 对齐 | 2 | ~~B221~~（批 24） |
| 75 | `mcp_servers` 提示段**渲染纯函数**（筛选/排序/4096 预算/裁剪/省略行） | 接线 | `index.ts:195-221` | coding `extension/mcp/McpServersSection.java:46-147`；`McpServerListing.java` | 对齐 | 1 | — |
| 76 | 提示段**接线**：每提示写入/删除 `systemPromptOptions.sections["mcp_servers"]` | 接线 | `index.ts:1178-1186` | `McpExtension.onBeforeAgentStart`（批 24；批 16 可变 sections 写回链上的生产者） | 对齐 | 2 | ~~B221~~（批 24） |
| 77 | `tool_call` 就绪等待（按 `tool.reachesTools(input)` 的 `"all"`/词包含命名空间；direct→只等属主服务器） | 接线 | `index.ts:224-232,1186-1207` | `McpExtension.java:303-314,412`（`reachesServer`：`All` 全等、`Words` 词包含 namespace；`ctx.signal` 无对位 ⇒ 窄差 D138） | 对齐（W7） | 1 | ~~B221~~（批 24） |
| 78 | `turn_start`：拾取进程外 `pi mcp login` 写入的新凭证并重连 | 接线 | `index.ts:1212-1215` | `McpExtension.onTurnStart`＋`McpLifecycle.reconnectSignedIn`（批 24；令牌快照 JSON 比对逐字；needs-auth 真 OAuth 路径已由 W2c 夹具覆盖） | 对齐 | 1 | ~~B221~~（批 24） |
| 79 | `mcp_servers_change`：扩展注册服务器热插拔（增连/删关/配置变重连） | 接线 | `index.ts:1217-1250` | `McpExtension.onMcpServersChange`（批 24；registeredConfig JSON 比对逐字） | 对齐 | 1 | ~~B221~~（批 24） |
| 80 | `session_shutdown`：abort＋关闭全部连接＋收敛后台动作 | 接线 | `index.ts:1252-1256` | `McpExtension.onSessionShutdown`＋`McpSessionScope`（批 24；abort＋close 全量＋track 集收尾） | 对齐 | 1 | ~~B221~~（批 24） |
| 81 | 激活门暴露给扩展：`getActiveTools/setActiveTools/getAllTools`＋按 `tool_search` 的 `reachesTools` 判定自动激活 | 接线 | `index.ts:501-529` | `AgentSession` 三件套＋`ExtensionContext` 面；`McpLifecycle.hasReachingTool:317`（W7：认 `name==tool_search && reachesTools!=null`） | 对齐（W7；批 16 T2） | 2 | ~~B221~~（批 16 已落；批 23 回填收平账面滞后） |
| 82 | 工具运行时注册/改名裁定/撤销重注 hidden/disable 隐藏＋命名空间别名 | 接线 | `index.ts:388-463` | `McpToolRegistrar.java:51,97`（`namespaceOf` 设 `aliases:[server]`）；其余批 24 语义不变（三表＋哈希后缀＋exposure 外挂表，D89/D91 维持） | 对齐（W7） | 2 | ~~B221~~（批 24） |
| 83 | 资源工具 exposure 同步（取可达服务器最宽 exposure；direct→active 集） | 接线 | `index.ts:466-491` | `McpToolRegistrar.syncResourceTools`（批 24；`McpResourceTools` 三定义自此有生产消费者） | 对齐 | 1 | ~~B221~~（批 24） |
| 84 | `pi.registerToolRenderer` ＋ `createMcpToolRenderers`（Text/Container/VisualLinePreview） | 接线 | `index.ts:382-386`；`tools.ts:301-339` | `ToolRendererRegistry`＋`McpToolCallRenderer`＋tui `ToolRenderers` 消费缝（批 16 T4，文本级责任链） | 对齐 | 1 | ~~B204~~（批 16 已销号；批 23 回填收平账面滞后） |
| 85 | `ctx.ui.custom` 组件挂载面 ＋ `/mcp` 管理器视图（菜单/状态/redirectUrl 屏/`capturesViewportInput`/PageUp·PageDown 分页） | 接线 | `extensions/mcp/ui.ts:75-101,131-151`；`tui/components/select-list.ts:41,148-164`；`tui/keybindings.ts:327-339` | `McpManagerView.java:108,256-259,282`（`capturesViewportInput`＋页大小钳制）；`ExtensionComponent.java:31`；`KeybindingsManager.java:83-84,193-194`；`ExtensionComponentOverlay.java:103`（适配层透传，路由无改） | 对齐（docs/22） | 1 | ~~B214~~（批 24 销号）＋~~D217~~（docs/22 销） |
| 86 | `/mcp` 斜杠命令（manager、login/logout/reconnect）＋管理动作（enable/disable/exposure/project override） | 接线 | `index.ts:1260-1347,743-778` | `McpSlashCommand`＋`McpManagerActions`＋`McpServerOps`＋`McpManagerMenus`（批 24：文案逐字；写回经 McpConfig.update；参数补全无 java 面 ⇒ 窄差 D142） | 对齐 | 2 | ~~B221~~（批 24） |
| 87 | `pi.registerMcpServer/getMcpServers` 扩展挂载点 | 接线 | `index.ts:345-367` | `ExtensionContext.registerMcpServer/unregisterMcpServer/getMcpServers`（批 23 T1，三守卫逐字；注册表安家 `DefaultExtensionContext`） | 对齐 | 1 | ~~B218~~（批 23 销号） |
| 88 | `SettingsManager.load(agentDir,projectDir,override)` 三参信任解析缝 | 接线 | `index.ts:1114`（默认 loadConfig） | coding-agent `core/SettingsManager.java:49` | 对齐 | 1 | B216（缝已开） |
| 89 | `--no-mcp` 开关（不连任何服务器、无 MCP 工具） | 接线 | `cli/args.ts:191,328` | `ArgsParser`/`Args`/`HelpText`＋装配按名过滤（批 24；pi main.ts:787 disabledBuiltinExtensions 对位）＋`-e builtin:mcp` 显式臂 | 对齐 | 1 | — |

## H. 权重 0 与共享主流面

| # | 能力单元 | 当前结论 | 旧权重 |
|---|---|---:|
| 90 | codemode 沙箱 | R5，不实现、不进分母 | 0 |
| 91 | `tool_search` deferred 工具发现 | 已有 Java 实现；新基线在 L2 重新确定计分归属 | 0（旧口径） |
| 92 | MCP 默认 `codemode` exposure 激活 | R5；direct/deferred 主流路径不受影响 | 0 |
| 93 | `ensureDiscoveryActive` | deferred/tool_search 臂已有实现；codemode 臂按 R5 排除 | 0（旧口径） |

---

## 加权汇总（新锚点 `56cae7823`）

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A 配置（12 计分＋2 转出） | 18 | **18.0** | **100%（满分域）** | 100%（12/12） |
| B 协议/客户端（7） | 13 | **13.0** | **100%（满分域）** | 100%（7/7） |
| C 传输（9 计分＋2 转出） | 13 | **13.0** | **100%（满分域）** | 100%（9/9） |
| D OAuth（11 计分＋4 转出） | 17 | **17.0** | **100%（满分域）** | 100%（11/11） |
| E 工具/资源/结果（19） | 27 | **27.0** | **100%（满分域）** | 100%（19/19） |
| F CLI（7 计分＋1 转出） | 11 | **11.0** | **100%（满分域）** | 100%（7/7） |
| G 接线（17） | 23 | **23.0** | **100%（满分域）** | 100%（17/17） |
| **模块合计（82 计分＋9 转出）** | **122** | **122.0** | **100%** | **100%（82/82）** |

**新锚点模块合计**：Σ权重 **122** ／ Σ(w×c) **122.0** ／ **加权完成度 = 100%**（未加权 82/82＝100%；对齐 82／存疑 0／缺失 0）。
数字可复算：`grep -E '^\| [0-9]+ \|' docs/map/07-mcp.md` 取判定列（7）与权重列（8），**只计权重 > 0 的行**（`转出（非本层）` 行权重 0，不进分母）。
历史读数：旧基线（`ea448f454`）93.8%（121.0/129）；docs/21 收口时的新锚点读数 96.18%（126.0/131）。docs/22（2026-11-04 用户裁决）把 9 条跨层/共享方言转出 MCP 分母，并在同一包内补齐 #85。

**权重 3 只有 1 条（#15 initialize 协商），已对齐**。计分母缺失 0 条；#94/#95 为 docs/21 新增的两条 w1 单元（W7／W8）。

### 缺失的权重分布（计分母 0 条）

无。

### 存疑（计分母 0 条）

无。

### 转出（非本层，权重 0，9 条）

A：#5（B182，L5 配置子系统）、#6（B183，共享 JSON 解析方言）· C：#27（B190，平台异常分类）、
#30（B191/B197，平台 URL 归一）· D：#38（B193，共享 JSON 序列化）、#40（B198，L1 ai 共享 OAuthPage）、
#41（B212，JVM 平台方言）、#43（B192，OAuth 身份托管基础设施）· F：#71（B213，全仓品牌改名）。

这些行保留在图中供追溯，但**不属于 MCP 层的计分单元**，归属由各层重测时确认。

---

## 当前复核项

MCP 层无未结计分项。9 条转出行随各自层（L1／L5／平台方言）的重测复核；本层不再维护。
