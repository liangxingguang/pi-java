# 07 MCP（pi-java-mcp ＋ coding-agent MCP 集成面）

> 【背景摘要】本图是 MCP 模块的全量加权盘点，2026-10-12 新建。MCP 在旧基准之后才成为 pi 主流模块，六张旧图从未覆盖；pi-java 侧为 2026-10 对齐批次（12 个对齐包）新建。核心结论（批 24 后）：会话内接线全通（G 组满分），计分母缺口仅剩 #50（structuredContent 消费者，codemode 族）。

## 基准与口径

- 基准：pi-java @ **`edc8749d`**（main，2026-10-12）；pi @ **`ea448f454`**（2026-10-11，v1.1.0-22）。
- 判定三档：**对齐** = pi 有 ∧ pi-java 有 ∧ 行为相同（读代码确认语义）；**缺失** = pi 有 ∧ pi-java 无（或差异有行为后果）；**存疑** = 形状/字节形态未逐字核对，或差异是刻意设计/跨模块/台账在册方言（子标签 **（形状）/（扩展）/（台账）**）。
- 所有证据为 `file:line`，两侧均已打开源码确认。**pi 侧一律 `git -C D:/workplaceForai/pi show ea448f454:<path>` 取证，未读工作树。**
- **范围**：pi `packages/mcp`（SDK 本体）＋ `packages/coding-agent/src/extensions/mcp/**` 与 `core/mcp-servers.ts`（集成面）↔ pi-java `pi-java-mcp` ＋ `pi-java-coding-agent` 的 `extension/mcp/**` 与 `subcommand/Mcp*`。
- **权重** = 用户可观察影响 × 频率（不含排期优先级）：**3** = 每轮对话都走/默认路径；**2** = 每次会话走/常用命令/常用配置；**1** = 低频/边缘/纯内部；**0** = 非目标，**排除出分母**。完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0。
- **R5 口径（关键）**：只计 `bin pi` 主流可达面。MCP 在 pi 的**默认 exposure 是 `codemode`**（`index.ts:123`），默认配置下模型根本看不到 MCP 工具——整条 codemode/tool-search 激活链依赖 quickjs-wasi 沙箱（**B161**）与 deferred 工具发现（**B162**），二者本仓零对应。codemode-only 能力（90–93）记**权重 0 出分母**，**B161/B162 闭环时重裁**。**已落的 wire/配置/CLI/适配形状全部保留计分**（B177 裁决：语义不废，只是运行时激活门未通）。

---
## 规模

| 模块 | pi 路径 | pi LOC | java LOC | 文件 pi→java |
|---|---|---:|---:|---|
| SDK 主源码 | `packages/mcp/src` | **3,244** | `pi-java-mcp` **10,853** | 18 → 144 |
| SDK 测试 | `packages/mcp/test` | **1,532** | `pi-java-mcp/src/test` **6,131** | 6 → 45（`@Test` **260**） |
| 集成面主源码 | `coding-agent/src/extensions/mcp/**` ＋ `core/mcp-servers.ts` | **4,586** | coding-agent `extension/mcp/**` ＋ `subcommand/Mcp*`＋`BrowserLauncher` **2,955** | 13 → 21 |
| 集成面测试 | `coding-agent/test/mcp-*`（10 个 `.ts`＋conformance 3 件） | **3,678** | coding-agent `…/mcp`＋`subcommand/Mcp*` 测试 **2,312** | 13 → 14（`@Test` **159**） |

> pi 集成面 12 文件：`extensions/mcp/{index 1344, cli 599, oauth 558, runtime 491, tools 335, resources 339,
> config 242, ui 259, log 69, 两个 .lazy 各 2}` ＋ `core/mcp-servers.ts 346`。
> java 文件数远多是「一件一类」风格所致（pi 单文件多导出），非规模差。
> 历史闭环稿称「coding 507 测」为 12 包跨共享件（settings/truncation/共享 OAuthPage 等）的并集；
> 本图只数 MCP 命名测试文件：mcp **260** ＋ 集成 **159** ＝ **419** 个 `@Test`，可 `grep -c @Test` 复算。

pi SDK 逐文件（`packages/mcp/src`）：`client.ts` 615 · `transports/streamable-http.ts` 505 ·
`oauth/flow.ts` 482 · `transports/stdio.ts` 216 · `oauth/discovery.ts` 215 · `oauth/types.ts` 209 ·
`oauth/callback.ts` 164 · `oauth/provider.ts` 168 · `protocol/content.ts` 117 · `protocol/jsonrpc.ts` 113 ·
`protocol/types.ts` 131 · `transports/in-memory.ts` 51 · `transports/transport.ts` 55 · 其余 4 文件 148。

---

## A. 配置（`mcp.json` ／ 注册校验）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | `mcpServers` 逐条校验（名/type/url/command 分派、`sse` 拒绝、headers/env/args 串记录） | 配置 | `core/mcp-servers.ts:221-345` | `config/McpServerConfigs.java:57` | 对齐 | 2 | — |
| 2 | global/project 作用域＋信任门＋project override 仅三键合并 | 配置 | `extensions/mcp/config.ts:145-156` | `config/McpConfig.java:50` | 对齐（目录名 `.pi`→`.pi-java` 刻意） | 2 | B182/B213 |
| 3 | extension 作用域＋`McpServerRegistry`（register/unregister 所有权/get/list 深拷贝/changeListener） | 配置 | `core/mcp-servers.ts:300-345` | `config/McpServerRegistry.java`；`config/McpScope.java:14` | 对齐（生产者见 87） | 1 | — |
| 4 | 校验返回**原对象副本**，未知键保留 | 配置 | `core/mcp-servers.ts:263,275` | record 丢未知键；写路径重读文件 JSON 树 | 存疑（台账：下游只读具名键，行为等价） | 1 | B181 |
| 5 | 项目配置目录读 `package.json` 的 `piConfig.configDir` | 配置 | `config.ts:581` | `McpConfig.java:33` 硬编码 `.pi-java`，无覆盖机制 | 存疑（台账：全树既有约定） | 1 | B182 |
| 6 | 解析失败错误文本（`<路径>: <消息>`；空文件） | 配置 | `config.ts:97-100` | `McpConfig.java:169`（空文件报 expected-object） | 存疑（台账：结构同、空文件文案分叉） | 1 | B183 |
| 7 | 写出字面量：裸 `Error`、JSON 数字/键序 | 配置 | `config.ts:228` 抛裸 Error | `config/McpConfigError.java`；`McpConfig.add` 经 record 序列化（`1.0`→`1`、组件序） | 存疑（台账） | 1 | B184 |
| 8 | exposure 四值＋`codemode-deferred` 别名＋`toolExposure` 精确/`*`模式＋默认 codemode | 配置 | `mcp-servers.ts:46-52,176-215` | `McpServerConfigs.java:211`；`config/McpExposure.java` | 对齐 | 2 | — |
| 9 | `mcpNamespace`（`-`→`_`）＋`-`/`_` 同名冲突检测 | 配置 | `mcp-servers.ts:120-123,265`；`config.ts:131` | `McpServerConfigs.java:200`；`McpConfig.java:201` | 对齐（排序用 `compareTo` 非 `localeCompare`，见 75/台账 B219） | 2 | B219 |
| 10 | add/remove/update 写回：缩进探测保留、默认值删键、override 保留默认 | 配置 | `config.ts:169-242` | `McpConfig.java:71-155`；`config/JsJson.java` | 对齐 | 2 | — |
| 11 | 顶层 `autoEnableCodemode` 键（默认 true，project 覆盖 global） | 配置 | `config.ts:101-103` | `McpConfig.java:173` | 对齐（消费者随 93 出分母） | 1 | — |
| 12 | `oauth` 子配置九条校验（loopback 主机/端口一致/cimd 组合/metadataUrl 协议） | 配置 | `mcp-servers.ts:132-174` | `McpServerConfigs.java:248-316` | 对齐 | 1 | — |
| 13 | 配置值解析 `${NAME}`/`$NAME`/`!cmd`/`$$`/`$!`＋命令缓存＋resolveOrThrow 文案 | 配置 | `core/resolve-config-value.ts` | `runtime/ConfigValueResolver.java:56` | 对齐（shell 第一层见 24） | 2 | — |

## B. 协议与客户端（`packages/mcp`）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 14 | JSON-RPC 线层（解析/校验、标准错误码、McpError/Timeout/Abort/ConnectionClosed） | 协议 | `protocol/jsonrpc.ts` | `protocol/jsonrpc/JsonRpcMessages.java` 等 7 件 | 对齐 | 2 | — |
| 15 | `initialize` 版本协商（LATEST/SUPPORTED）、capabilities 交换、serverInfo、instructions | 协议 | `client.ts:60-150` | `McpClient.java:131-145`；`protocol/McpVersion.java` | 对齐 | 3 | — |
| 16 | 列表分页（cursor、上限 1000 页、`null`/`""` cursor 归一） | 协议 | `client.ts:33,82-105` | `McpClientPages.java:15`；`McpClientValidators.java` | 对齐 | 2 | — |
| 17 | `roots/list` 提供方（cwd file URL ＋ basename） | 协议 | `extensions/mcp/runtime.ts:383` | `McpConnector.java:59`；`McpClient.java:72` | 对齐 | 1 | — |
| 18 | 通知订阅（`notifications/message`、progress、`$/cancel`、两个 list_changed） | 协议 | `client.ts`（onNotification） | `McpClient.java:249`；`McpConnector.java:64-75` | 对齐 | 2 | — |
| 19 | 请求超时（默认 30s、progress 通知重置、abort） | 协议 | `client.ts:31` | `McpClient.java`（`McpRequestOptions`）；`McpClientSchedules.java` | 对齐 | 2 | — |
| 20 | in-memory transport | 协议 | `transports/in-memory.ts:1`（**src 公开发布**） | `src/test/.../transport/InMemoryTransport.java`（**测试源集**） | 存疑（形状：产品无发布面，仅测试/夹具用） | 1 | — |

## C. 传输

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 21 | stdio：cross-spawn、换行帧、消息字节上限、stderr 64KB ring、不完整消息/退出报错 | 传输 | `transports/stdio.ts:78-200` | `transport/StdioTransport.java`；`StdioLineFrames.java`；`StdioProcess.java` | 对齐 | 2 | — |
| 22 | stdio 关闭序列（stdin end→500ms grace→SIGTERM→2s→SIGKILL）＋进程树（Win taskkill / 负 pid） | 传输 | `stdio.ts:33-44,153-176` | `StdioTransport.java:94-105`；`StdioProcess.java:64-73`（`descendants()`） | 对齐 | 1 | — |
| 23 | stdio `cwd`（相对路径相对会话目录解析） | 传输 | `stdio.ts:88`；`runtime.ts:97-121` | `StdioProcess.java:29`；`runtime/McpDefaultTransport.java:60` | 对齐 | 2 | B199（已修） |
| 24 | `!cmd` 两层壳：先 `settings.shellPath`、ENOENT 回落默认 | 传输 | `resolve-config-value.ts`（settings 壳注入） | coding `extension/mcp/ShellPathCommandRunner.java`（批 24：两段式——配置壳优先、spawn 失败回落 `JdkShellRunner`；经 `McpConnectionOptions.resolver` 注入会话内连接） | 对齐 | 1 | ~~B188~~（批 24） |
| 25 | Streamable HTTP：POST/GET、`Mcp-Session-Id`、404 session expired、405 无 GET、DELETE 1s | 传输 | `transports/streamable-http.ts:188-330` | `transport/http/StreamableHttpTransport.java` | 对齐 | 2 | B201（已修） |
| 26 | SSE 流消费＋`Last-Event-ID` 断线续 GET 流＋408/429/5xx 退避重试 | 传输 | `streamable-http.ts:35-100,366-497` | `http/SseStreamConsumer.java`；`http/StreamableHttpStreams.java` | 对齐 | 2 | — |
| 27 | `isTransientError` 网络错分类 | 传输 | `extensions/mcp/runtime.ts:70-75`（HTTP 408/429/5xx≠501 或 `TypeError`） | `McpConnectionSupport.java:112-121`（状态判同；网络类是 `IOException`，宽于 `TypeError`） | 存疑（台账：TLS/中断/重定向策略也重试） | 1 | B190 |
| 28 | OAuth/AS 请求 15s 边界（unresponsive AS 不持刷新锁） | 传输 | `extensions/mcp/oauth.ts:51` | `runtime/TimedMcpFetch.java:13`（外包一层，未扩 Fetch 接口） | 对齐 | 1 | B194 |
| 29 | `connectOnce` 工具/资源拉取并发（`Promise.all`） | 传输 | `runtime.ts:409-414` | `McpConnector.java:80-83` **顺序**（先 tools 后 resources） | 存疑（台账：仅时序差） | 1 | B196 |
| 30 | WHATWG `new URL` 归一（主机小写/默认端口/补尾斜杠）＋相对引用拒绝 | 传输 | 各处 `new URL`/`URL.canParse` | `runtime/WebUrls.java`；`responseFromRedirectUrl` 已挡 scheme | 存疑（台账：自建等价，非 ASCII 主机未逐字互读） | 1 | B191/B197 |
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
| 38 | `mcp-auth.json` 字节形态（`extension` 嵌套、null≡undefined 省略） | OAuth | `oauth.ts:116-130` | `oauth/OAuthClientInformation.java:24` | 存疑（台账：两侧按 JSON 解析，行为等价） | 1 | B193 |
| 39 | loopback 回调服务器（free port/固定端口/固定 URI，RFC 8252） | OAuth | `oauth/callback.ts` | `oauth/OAuthCallbackServer.java`；`runtime/McpCallbackSettings.java` | 对齐 | 2 | — |
| 40 | 回调成功/失败页 HTML/CSS | OAuth | `oauth/callback.ts`（内嵌页） | `pi-java-ai/.../utils/OAuthPage.java`（共享件） | 存疑（台账：`<style>` 内空白折行差，计算值同） | 1 | B198 |
| 41 | redirect 等待：TTY 判定＋readline 与 abort 竞速＋手工粘贴 URL | OAuth | `oauth.ts:396-470` | `subcommand/McpRedirectPrompt.java`；`runtime/McpSignIn.java:232-265` | 存疑（台账：`System.console()!=null`≠isatty） | 1 | B212 |
| 42 | `signInMcpServer` 编排（challenge、300s 默认超时、取消即 cancelled） | OAuth | `oauth.ts:473-558` | `runtime/McpSignIn.java:77`；`McpSignInCancelledError.java` | 对齐 | 2 | — |
| 43 | `clientRegistration:"cimd"`（pi.dev Client ID Metadata Document） | OAuth | `core/mcp-servers.ts:104-113`；`oauth.ts:251` | `runtime/McpClientMetadataDocuments.java:25`（拉 pi.dev 托管文档） | 存疑（台账：文档在 pi 域名，pi-java 无自有托管） | 1 | B192 |
| 44 | `auth.provider`：用 pi provider token 代替 OAuth（ReadThroughProvider） | OAuth | `index.ts:307`（`modelRegistry.getApiKeyForProvider`） | `McpServerConnection.java:78-83`；批 24 会话臂注入生产源（`McpLifecycle` providerToken＝OAuth 凭证优先/回落 api key，每请求现读） | 对齐 | 1 | ~~B189~~（批 24 会话臂；CLI 臂先齐） |
| 45 | `WWW-Authenticate` challenge 解析（resource_metadata_url/scope/error） | OAuth | `flow.ts:452`；`oauth/errors.ts` | `oauth/WwwAuthenticate.java`；`McpAuthProviders.java:179` | 对齐 | 1 | — |
| 46 | login 端到端真授权服务器夹具 | OAuth | `test/oauth.test.ts:639`（含真交换脚本） | `McpSignInTest` 只到连不上/非 OAuth/缺参/超时 | 存疑（台账：真 AS 路径无夹具） | 1 | B217 |

## E. 工具 / 资源 / 结果

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 47 | `toLlmContent`：五块型转换（audio/link/binary 占位）＋空 content 回退 structuredContent JSON | 结果 | `protocol/content.ts:71-117` | `protocol/content/McpContents.java`；`LlmContent.java` | 对齐 | 2 | — |
| 48 | 图片序：mcp 侧 `Image(data,mimeType)` 与 ai `ImageContent(mediaType,data)` 相反，唯一换序点 | 结果 | `protocol/content.ts:18` | `LlmContent.java:14`；coding `extension/mcp/McpResultContent.java:196` | 对齐（两侧夹具钉死） | 1 | B208（已裁） |
| 49 | `CallToolResult.structuredContent`/`isError` 线格形状 | 结果 | `protocol/content.ts:55-58` | `protocol/content/CallToolResult.java:18-21` | 对齐 | 2 | — |
| 50 | structuredContent **消费者**（codemode 全量结果；`after_tool` 钩子改写） | 结果 | `extensions/mcp/tools.ts:11`；ai `types.ts:1439` | 形状在、无消费者 | **缺失** | 1 | B202 |
| 51 | 协议 `Tool` 四属性（title/inputSchema/outputSchema/annotations） | 结果 | `protocol/types.ts:64-82` | `protocol/McpTool.java:21-27`；`ToolAnnotations.java` | 对齐 | 2 | — |
| 52 | MCP→AgentTool 适配（label、description 三级回退、schema 补 `type`/`properties`、progress onUpdate、并行批） | 结果 | `tools.ts:260-300` | coding `extension/mcp/McpToolDefinition.java:103-209` | 对齐 | 2 | — |
| 53 | `AgentTool` 承载 exposure/namespace/outputSchema/annotations | 结果 | `core/extensions/types.ts` `ToolDefinition` | `AgentTool` 无此四属性；`McpToolDefinition.java:41-50` 外挂承载 | 存疑（台账：激活依赖 81/90/91） | 1 | B203 |
| 54 | `ToolExposure` 五值（direct/model-only/codemode/deferred/hidden）＋映射（codemode→deferred） | 结果 | `extensions/types.ts:516`；`tools.ts:45-47` | coding `extension/mcp/McpToolExposure.java` | 对齐（暂居 coding 包，待扩展系统成型上移） | 1 | B207 |
| 55 | `isError` 作为结果语义通道（runner 读结果字段而非只看抛错；after_tool 可改写） | 结果 | `tools.ts:228-233` | `McpResultConverter.java:19-51`；`ToolResult.isError()` | 对齐 | 2 | B206（已裁） |
| 56 | 工具名归一：`mcp__s__t`、非字母数字→`_`、64 字符上限、sha256-8 后缀、isTaken 冲突裁定 | 结果 | `tools.ts:73-97`；`index.ts:398-420` | coding `extension/mcp/McpToolNames.java:47-65` | 对齐 | 2 | — |
| 57 | 结果 20KB 中段截断＋token 估算＋全文落盘 `fullOutputPath`（locale 修复） | 结果 | `tools.ts:51,124-149` | `McpResultContent.java:33,106-129`；`McpOutputSaver.java` | 对齐 | 2 | B209（已修） |
| 58 | 二进制资源落盘＋文本 mime 直显＋`resource_link` 文案＋readableResources | 结果 | `tools.ts:160-202` | `McpResultContent.java:150-190` | 对齐 | 1 | — |
| 59 | 每工具 `CallToolResult` outputSchema 包装（content/structuredContent/isError/_meta） | 结果 | `tools.ts:104-115` | `extension/mcp/McpResultSchema.java` | 对齐 | 1 | — |
| 60 | 三个资源工具定义（list/templates/read）＋MCP App 资源过滤 | 结果 | `extensions/mcp/resources.ts:47,194-339` | `McpResourceTools.java:39-428`；`mcp/runtime/McpAppResources.java:34-58` | 对齐（**无生产消费者**，随 83/85 激活） | 1 | — |
| 61 | 服务器 `timeout`（秒，默认 60，progress 重置） | 结果 | `core/mcp-servers.ts:74` | `runtime/McpServerConnection.java:40-145` | 对齐 | 2 | — |
| 62 | `tools/list_changed`/`resources/list_changed` 刷新（capability 门控） | 结果 | `runtime.ts:462-476` | `McpConnector.java:71-75,149-181` | 对齐（重新注册半随 82 缺） | 1 | — |
| 63 | `mcp.log` 服务器日志（`notifications/message` 追加文件） | 结果 | `extensions/mcp/log.ts:1-69` | `runtime/McpServerLog.java` | 对齐 | 1 | — |
| 64 | stdio 连接失败 stderr tail（2000 字符）拼进错误 | 结果 | `runtime.ts:421-430` | `McpConnector.java:111-133`；`transport/StderrTail.java` | 对齐 | 1 | — |

## F. CLI `pi-java mcp`（进程外：add/remove/list/login/logout）

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 65 | `add`（stdio/http、11 个已知选项、`--` 透传、http/stdio 误配门、替换后检查提示） | CLI | `extensions/mcp/cli.ts:277-394` | `subcommand/McpAddRemoveCommand.java:23-159` | 对齐 | 2 | — |
| 66 | `remove [-l]`（存在性/作用域不符提示） | CLI | `cli.ts:396-435` | `McpAddRemoveCommand.java:161-201` | 对齐 | 2 | — |
| 67 | `list [--json]`（状态/工具数/错误聚合，失败 exit 1，JSON 两空格） | CLI | `cli.ts:209-216,440-489` | `subcommand/McpListCommand.java:59-170` | 对齐 | 2 | — |
| 68 | `login [--timeout 300]` ＋ `logout`（连接探测 OAuth、凭证删除） | CLI | `cli.ts:218-261` | `subcommand/McpCommand.java:137-247` | 对齐 | 2 | — |
| 69 | 自建选项解析「收够 N 个位置参数即停止」透传 | CLI | `cli.ts:144-182` | `subcommand/McpCliOptions.java:62-109` | 对齐（picocli 不能表达，手写复刻） | 1 | B210 |
| 70 | 开浏览器（Desktop→平台命令→静默失败；headless 提示访问 URL） | CLI | `utils/open-browser.ts` | `subcommand/BrowserLauncher.java` | 对齐（pi-java 新增件） | 1 | B211 |
| 71 | 文案命名（`pi`→`pi-java`、`.pi`→`.pi-java`、去 chalk 着色） | CLI | `cli.ts` 全文 | `subcommand/McpCliHelp.java` | 存疑（台账：刻意改名） | 1 | B213 |
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
| 73 | 扩展工厂＋`session_start`：读两源配置、后台连接、runtime 惰性加载（无服务器不付费）、问题聚合 | 接线 | `index.ts:283-302,1111-1151,640-655` | `extension/mcp/McpExtension.java`（onSessionStart）＋`McpLifecycle`（批 24；裁决⑦ 惰性加载不模拟——虚拟线程懒连即无服务器零成本） | 对齐 | 2 | ~~B221~~/~~B195~~（批 24） |
| 74 | `before_agent_start`：direct 服务器首提示等待 10s（超时放行＋通知） | 接线 | `index.ts:1153-1185` | `McpLifecycle.waitForDirectServers`（文案逐字；批 24） | 对齐 | 2 | ~~B221~~（批 24） |
| 75 | `mcp_servers` 提示段**渲染纯函数**（筛选/排序/4096 预算/裁剪/省略行） | 接线 | `index.ts:156-222` | coding `extension/mcp/McpServersSection.java:46-147`；`McpServerListing.java` | 对齐 | 1 | — |
| 76 | 提示段**接线**：每提示写入/删除 `systemPromptOptions.sections["mcp_servers"]` | 接线 | `index.ts:1175-1184` | `McpExtension.onBeforeAgentStart`（批 24；批 16 可变 sections 写回链上的生产者） | 对齐 | 2 | ~~B221~~（批 24） |
| 77 | `tool_call` 就绪等待（codemode 脚本/tool_search/资源→全等；direct→只等属主服务器） | 接线 | `index.ts:1187-1207` | `McpExtension.onToolCall`（批 24：资源三工具全等/属主等待逐字；codemode/tool_search 两臂 R5 不移植——#90/#91 出分母同族；`ctx.signal` 无对位 ⇒ 窄差 D138） | 对齐 | 1 | ~~B221~~（批 24） |
| 78 | `turn_start`：拾取进程外 `pi mcp login` 写入的新凭证并重连 | 接线 | `index.ts:1209-1212,536-545` | `McpExtension.onTurnStart`＋`McpLifecycle.reconnectSignedIn`（批 24；令牌快照 JSON 比对逐字；needs-auth 真 OAuth 路径随 B217 夹具缺口只结构性覆盖） | 对齐 | 1 | ~~B221~~（批 24） |
| 79 | `mcp_servers_change`：扩展注册服务器热插拔（增连/删关/配置变重连） | 接线 | `index.ts:1214-1247` | `McpExtension.onMcpServersChange`（批 24；registeredConfig JSON 比对逐字） | 对齐 | 1 | ~~B221~~（批 24） |
| 80 | `session_shutdown`：abort＋关闭全部连接＋收敛后台动作 | 接线 | `index.ts:1249-1255` | `McpExtension.onSessionShutdown`＋`McpSessionScope`（批 24；abort＋close 全量＋track 集收尾） | 对齐 | 1 | ~~B221~~（批 24） |
| 81 | 激活门暴露给扩展：`getActiveTools/setActiveTools/getAllTools` | 接线 | `index.ts:490-490,522,778` | `AgentSession` 三件套＋`ExtensionContext` 面（批 16 T2） | 对齐 | 2 | ~~B221~~（批 16 已落；批 23 回填收平账面滞后） |
| 82 | 工具运行时注册/改名裁定/撤销重注 hidden/disable 隐藏 | 接线 | `index.ts:388-463` | `McpToolRegistrar`（批 24：三表＋Codex 序无关哈希后缀＋getClient 三错文逐字；exposure 外挂表＋激活门表达——裁决③，D89/D91 维持；pending 晚注册臂随 D4 销号） | 对齐 | 2 | ~~B221~~（批 24） |
| 83 | 资源工具 exposure 同步（取可达服务器最宽 exposure；direct→active 集） | 接线 | `index.ts:466-491` | `McpToolRegistrar.syncResourceTools`（批 24；`McpResourceTools` 三定义自此有生产消费者） | 对齐 | 1 | ~~B221~~（批 24） |
| 84 | `pi.registerToolRenderer` ＋ `createMcpToolRenderers`（Text/Container/VisualLinePreview） | 接线 | `index.ts:383-386`；`tools.ts:301-339` | `ToolRendererRegistry`＋`McpToolCallRenderer`＋tui `ToolRenderers` 消费缝（批 16 T4，文本级责任链） | 对齐 | 1 | ~~B204~~（批 16 已销号；批 23 回填收平账面滞后） |
| 85 | `ctx.ui.custom` 组件挂载面 ＋ `/mcp` 管理器视图（菜单/状态/redirectUrl 屏） | 接线 | `extensions/mcp/ui.ts:73,244-258` | `McpManagerView`＋`McpManagerUi`（批 24：ExtensionComponent 文本级复刻——光标/12 行窗口/订阅重建保选中；redirect 屏文本行化 ⇒ 窄差 D139） | 对齐 | 1 | ~~B214~~（批 24 销号：挂载面批 17＋载荷批 24） |
| 86 | `/mcp` 斜杠命令（manager、login/logout/reconnect）＋管理动作（enable/disable/exposure/project override） | 接线 | `index.ts:1257-1344,743-778` | `McpSlashCommand`＋`McpManagerActions`＋`McpServerOps`＋`McpManagerMenus`（批 24：文案逐字；写回经 McpConfig.update；参数补全无 java 面 ⇒ 窄差 D142） | 对齐 | 2 | ~~B221~~（批 24） |
| 87 | `pi.registerMcpServer/getMcpServers` 扩展挂载点 | 接线 | `index.ts:349-364` | `ExtensionContext.registerMcpServer/unregisterMcpServer/getMcpServers`（批 23 T1，三守卫逐字；注册表安家 `DefaultExtensionContext`） | 对齐 | 1 | ~~B218~~（批 23 销号） |
| 88 | `SettingsManager.load(agentDir,projectDir,override)` 三参信任解析缝 | 接线 | `index.ts:1112`（默认 loadConfig） | coding-agent `core/SettingsManager.java:49` | 对齐 | 1 | B216（缝已开） |
| 89 | `--no-mcp` 开关（不连任何服务器、无 MCP 工具） | 接线 | `cli/args.ts:191,328` | `ArgsParser`/`Args`/`HelpText`＋装配按名过滤（批 24；pi main.ts:787 disabledBuiltinExtensions 对位）＋`-e builtin:mcp` 显式臂 | 对齐 | 1 | — |

## H. 权重 0（R5 不可达，**排除出分母**；B161/B162 闭环时重裁）

| # | 能力单元 | pi 证据 | java | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|
| 90 | codemode 沙箱（quickjs-wasi）本身 | `extensions/codemode/**` | 零对应 | 缺失 | **0** | B161 |
| 91 | `tool_search` deferred 工具发现 | `extensions/tool-search/**` | 零对应 | 缺失 | **0** | B162 |
| 92 | MCP 默认 `codemode` exposure 的运行时激活（默认配置下模型不可见 MCP 工具） | `index.ts:123` | wire/配置语义全保留，激活路径不通 | 缺失 | **0** | B177 |
| 93 | `ensureDiscoveryActive`（自动激活 codemode/tool_search＋不可达警告） | `index.ts:500-534` | 无 | 缺失 | **0** | B177 |

> 触发条件：仅当用户配置 `exposure:"direct"` （或 `toolExposure` 个别提升）时 MCP 工具才属于主流
> 模型可见面；该路径仍被 73–89 的接线缺失整体挡住。90–93 随 **B161/B162**（codemode/tool-search 落地）重裁。

---

## 汇总（未加权）

按 **93 个能力单元**（A13 ＋ B7 ＋ C11 ＋ D15 ＋ E18 ＋ F8 ＋ G17 ＋ H4）：

| 档 | 数 | 占比 |
|---|---:|---:|
| 对齐 | **73** | 78.5% |
| 缺失 | **5**（计分母 1〔#50〕＋ 权重 0 的 4） | 5.4% |
| 存疑 | **15** | 16.1% |
| **合计** | **93** | 100% |

**非目标口径未加权完成度 = 73/89 = 82.0%**（分母 93 − 4 个权重 0）；
对齐＋存疑视为「有件」：88/89 = **98.9%**。（批 24：13 缺失翻对齐＋#44 存疑翻对齐）

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A 配置（13） | 19 | 17.0 | **89.5%** | 69.2%（9/13） |
| B 协议/客户端（7） | 13 | 12.5 | **96.2%** | 85.7%（6/7） |
| C 传输（11） | 15 | 13.5 | **90.0%** | 72.7%（8/11） |
| D OAuth（15） | 21 | 18.5 | **88.1%** | 66.7%（10/15） |
| E 工具/资源/结果（18） | 26 | 24.5 | **94.2%** | 88.9%（16/18） |
| F CLI（8） | 12 | 11.5 | **95.8%** | 87.5%（7/8） |
| G 接线（17） | 23 | **23.0** | **100%（满分域）** | 100%（17/17） |
| **模块合计（89 单位）** | **129** | **120.5** | **93.4%** | **82.0%（73/89）** |

**模块合计**：Σ权重 **129** ／ Σ(w×c) **120.5** ／ **加权完成度 = 120.5/129 = 93.4%**（未加权 82.0%）。
数字可复算：`grep -E '^\| [0-9]+ \|' docs/map/07-mcp.md` 取判定列（7）与权重列（8）。

**权重 3 只有 1 条（#15 initialize 协商），对齐**。批 24 后计分母缺失仅剩 #50
（structuredContent 消费者，挂 B202/codemode 族）；存疑 15 条均 w1 方言/字节形态/测试缺口。

> 批 23（2026-10-25，coding-agent docs/34）：**3 行翻动 +4.0**——#87 缺失→对齐（+1.0，扩展 MCP 注册三件，B218 销号）＋#81（+2.0）/#84（+1.0）批 16 已落、本批回填收平账面滞后（G 组 2.0→6.0/23 ≈ 26.1%）。
> 批 24（2026-10-27，coding-agent docs/35）：**14 行翻动 +18.5**——G 组 12 缺失全翻对齐
> （+17.0，B221① 销号〔内置 MCP 扩展本体：六事件订阅＋提示段生产＋运行时注册三表＋
> /mcp 命令＋管理器视图载荷＋--no-mcp〕＋B214 销号＋B195 销号）＋#24（+1.0，B188 销号）
> ＋#44（+0.5，B189 会话臂销号）；docs/11 窄差 D3/D4 收编销号；新窄差 D138–D142。

### 缺失的权重分布（计分母 1 条）

- w1：1 条 —— 50（structuredContent 消费者，挂 B202/codemode 族）。（批 24 清掉其余 13 条。）

### 存疑 15 条（均 w1，方言/字节形态/测试缺口）

A：4/5/6/7（B181–B184）· B：20（in-memory 发布面）· C：27/29/30（B190/B196/B191/B197）·
D：38/40/41/43/46（B193/B198/B212/B192/B217）· E：53（B203）· F：71（B213）。

---

## B221 接线面判定摘要（六个 on() 事件 ＋ 挂载点）

| pi 挂载点 | 行 | pi-java 现状 | 判定 |
|---|---|---|---|
| `on("session_start")` | `index.ts:1111` | **批 24 已落**（`McpExtension.onSessionStart`） | 对齐 |
| `on("before_agent_start")` | `index.ts:1175` | **批 24 已落**（提示段生产＋首提示有界等待） | 对齐 |
| `on("tool_call")` | `index.ts:1187` | **批 24 已落**（资源三工具全等/属主等待）；**批 27** tool_search 等待臂落地（codemode 臂维持 R5） | 对齐 |
| `on("turn_start")` | `index.ts:1209` | **批 24 已落**（SessionEventBridge 转发＋凭证拾取） | 对齐 |
| `on("mcp_servers_change")` | `index.ts:1214` | **批 24 已落**（批 23 广播面＋批 24 热插拔本体） | 对齐 |
| `on("session_shutdown")` | `index.ts:1249` | **批 24 已落**（closeWith 链发射＋收敛） | 对齐 |
| 激活门 `get/setActiveTools` | `index.ts:490,522` | 机制在 `AgentHarness:528/533`＋**批 16 已暴露 `ExtensionContext` 面** | 对齐（批 16） |
| `ctx.ui.custom` | `ui.ts:248` | **批 17 挂载面已落**（`TuiExtensionUI.custom`＋`ExtensionComponentOverlay`）；载荷 `/mcp` 管理器视图随 B221① | 面落/载荷缺（B214） |
| `registerToolRenderer` | `index.ts:383` | **批 16 已落**（`ToolRendererRegistry`＋`McpToolCallRenderer`＋tui 消费缝） | 对齐（批 16，B204 销号） |
| 提示分节 `.sections(...)` | `index.ts:1178` | 管道齐（`SystemPromptOptions.sections`/`SystemPrompts.buildSections`）＋批 16 `before_agent_start` 可变 sections 写回链，`ContextAssembler:104-148` 不产 mcp 段 | 缺失（无 MCP 生产者） |
| `registerMcpServer` | `index.ts:349` | **批 23 已落**（`ExtensionContext` MCP 三件，三守卫逐字；注册表安家 `DefaultExtensionContext`） | 对齐（批 23，B218 销号） |

**结论**（批 24 后）：MCP 全面对齐——SDK/配置/OAuth/工具适配/进程外 CLI（A–F）＋会话内接线（G 组满分）；
计分母缺失仅剩 #50（structuredContent 消费者，随 B202/codemode 族）；台账 B221 六臂全收（②③④⑤⑥ 批 16/17/23、① 批 24），B214/B188/B189/B195 同批销号。
codemode 默认 exposure 的运行时激活（#92/#93）曾 R5 出分母——**批 27（docs/38）重裁**：#93（deferred ⇒ `tool_search` 自动激活与可达链）已落地对齐（ensureDiscoveryActive 真化＋tool_call 等待臂；权重 0 不动账面）；#92（codemode 激活）维持 R5 出分母，随 B161 重裁。

## 不确定项

1. **#20 in-memory transport**：pi 在 `src/` 公开发布，java 只存在于测试源集——若有第三方扩展要用即缺公开面。
2. **#30 URL 归一**：`WebUrls` 自建 WHATWG 等价，非 ASCII 主机/IPv6 细节未经真 pi 互读夹具验证。
3. **#44 auth.provider**：ReadThroughProvider 件齐，但 provider token 的生产源（modelRegistry）只在会话接线后存在。
4. **#46 真 AS 夹具**：成功登录路径与 `--timeout` 秒数文案只在真授权服务器下可验（B217）。
5. **测试计数口径**：历史稿「coding 507 测」含跨模块共享件；本图只数 MCP 命名文件（419 个 `@Test`），
   共享 OAuthPage/settings/truncation 的测试计入各自模块图。
6. **#43 cimd**：java 代码实际拉取 pi.dev 托管文档，机械上可跑，但向 AS 表明的身份是 pi 而非 pi-java。
