# 07 MCP（pi-java-mcp ＋ coding-agent MCP 集成面）

> 【背景摘要】当前读数：83 个计分单元全部对齐，账面 **123/123＝100%**（未加权 83/83）。
> 覆盖 pi `packages/mcp` SDK 本体与 coding-agent 集成面 ↔ pi-java `pi-java-mcp` ＋ `extension/mcp`／`subcommand/Mcp*`。
> 差异结论：只余 9 条「转出（非本层）」、4 条 R5，以及窄差 #77（D138）／#86（D142）、台账 #9（B219）、新增单元 #96。开放项见 [docs/04](../04-open-items-register.md)。

## 基准与口径

- 判定基准：pi **`a00a5048d`**（`CURRENT_PI_PIN`）；java 侧工作树。
- 判定三档：**对齐** = pi 有 ∧ pi-java 有 ∧ 行为相同（读代码确认语义）；**缺失** = pi 有 ∧ pi-java 无（或差异有行为后果）；**存疑** = 形状/字节形态未逐字核对，或差异是刻意设计/跨模块/台账在册方言（子标签 **（形状）/（扩展）/（台账）**）。
- 第四类记账 **转出（非本层）**：差异归属其它层或共享平台方言，行与证据保留在本图供追溯，但**权重 0、不进 MCP 分母**（用户裁决）。
- 所有证据为两侧 `file:line`。
- **范围**：pi `packages/mcp`（SDK 本体）＋ `packages/coding-agent/src/extensions/mcp/**` 与 `core/mcp-servers.ts`（集成面）↔ pi-java `pi-java-mcp` ＋ `pi-java-coding-agent` 的 `extension/mcp/**` 与 `subcommand/Mcp*`。
- **权重** = 用户可观察影响 × 频率（不含排期优先级）：**3** = 每轮对话都走/默认路径；**2** = 每次会话走/常用命令/常用配置；**1** = 低频/边缘/纯内部；**0** = 非目标，**排除出分母**。完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0。
- **R5 口径**：codemode 及其专用激活路径不做、不进分母；tool_search 和 deferred MCP 工具发现已有实现，属于主流复核面。wire、配置、CLI 和适配形状继续按行为计分。新锚点的 `structuredContent` 还需区分 codemode 消费与 `after_tool` 等主流消费。

---
## 规模

| 模块 | pi 路径 | pi LOC | java LOC | 文件 pi→java |
|---|---|---:|---:|---|
| SDK 主源码 | `packages/mcp/src` | **4,372** | `pi-java-mcp` **11,940** | 28 → 152 |
| SDK 测试 | `packages/mcp/test` | **3,181** | `pi-java-mcp/src/test` **7,906** | 19 → 55（`@Test` **317**） |
| 集成面主源码 | `coding-agent/src/extensions/mcp/**` ＋ `core/mcp-servers.ts` | **3,957** | coding-agent `extension/mcp/**` ＋ `subcommand/Mcp*`＋`BrowserLauncher` **5,844** | 12 → 34 |
| 集成面测试 | `coding-agent/test/mcp-*.ts`（5 个）＋`suite` 内 3 个＋`mcp-conformance`（4 件） | **4,075** | coding-agent `…/mcp`＋`subcommand/Mcp*` 测试 **3,911** | 13 → 23（`@Test` **229**） |

> 规模按目标锚点 `a00a5048d` 与工作树实测（pi 侧 `src/`；java 侧 `src/{main,test}` 全部 `.java`）。
> java 文件数多是「一件一类」风格所致（pi 单文件多导出），非规模差。
> 本图只数 MCP 命名测试文件：SDK **317** ＋ 集成 **229** ＝ **546** 个 `@Test`。

SDK 新增件：`transports/pipe.ts`（PipeTransport，见 #96）、`oauth/sign-in.ts` 与
`oauth/loopback.ts`（两段式 sign-in 拆分，见 #37／#42）。

---

## A. 配置（`mcp.json` ／ 注册校验）

已对齐 11 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 5 | 项目配置目录读 `package.json` 的 `piConfig.configDir` | 配置 | `config.ts:581` | `McpConfig.java:33` 硬编码 `.pi-java`，无覆盖机制 | 转出（非本层：L5 配置子系统） | 0 | B182（裁决） |
| 6 | 解析失败错误文本（`<路径>: <消息>`；空文件） | 配置 | `config.ts:97-100` | `McpConfig.java:169`（空文件报 expected-object） | 转出（非本层：共享 JSON 解析方言） | 0 | B183（裁决；BOM 部分已由 #95 关闭） |
| 9 | `mcpNamespace`（`-`→`_`）＋`-`/`_` 同名冲突检测 | 配置 | `mcp-servers.ts:120-123,265`；`config.ts:131` | `McpServerConfigs.java:200`；`McpConfig.java:201` | 对齐（排序用 `compareTo` 非 `localeCompare`，见 75/台账 B219） | 2 | B219 |

## B. 协议与客户端（`packages/mcp`）

已对齐 7 条（逐条证据见 git 历史）。

## C. 传输

已对齐 9 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 27 | `isTransientError` 网络错分类 | 传输 | `extensions/mcp/runtime.ts:70-75`（HTTP 408/429/5xx≠501 或 `TypeError`） | `McpConnectionSupport.java:112-121`（状态判同；网络类是 `IOException`，宽于 `TypeError`） | 转出（非本层：平台异常分类方言） | 0 | B190（裁决） |
| 30 | WHATWG `new URL` 归一（主机小写/默认端口/补尾斜杠）＋相对引用拒绝 | 传输 | 各处 `new URL`/`URL.canParse` | `runtime/WebUrls.java`；`responseFromRedirectUrl` 已挡 scheme | 转出（非本层：平台 URL 归一方言） | 0 | B191/B197（裁决） |
| 96 | `PipeTransport`：把调用方启动的程序当 MCP stdio（`McpPipe` 接缝、`close`＝关 stdin→等 `exited`→kill、`exited` 失败先报错再 close） | 传输 | `transports/pipe.ts:1-137` | `transport/PipeTransport.java`（`Options`／`McpPipe`／`PipeHandlers`／`PipeOpener`）；`StdioLineFrames.Framer` 与 stdio 共用拼帧 | 对齐（P9-S7 `e40eea1`） | 1 | — |

## D. OAuth（MCP 10.x：PKCE 授权码＋DCR，无设备流）

> pi MCP OAuth **没有设备流**（`git grep device` 在 `packages/mcp` 与 `extensions/mcp` 零命中）；
> 设备流属 ai 模块的 provider OAuth。本族 = 受保护资源元数据→AS 元数据→PKCE→DCR/预置 client→loopback 回调。

已对齐 11 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 38 | `mcp-auth.json` 字节形态（`extension` 嵌套、null≡undefined 省略） | OAuth | `oauth.ts:116-130` | `oauth/OAuthClientInformation.java:24` | 转出（非本层：共享 JSON 序列化方言） | 0 | B193（裁决） |
| 40 | 回调成功/失败页 HTML/CSS | OAuth | `oauth/callback.ts`（内嵌页） | `pi-java-ai/.../utils/OAuthPage.java`（共享件） | 转出（非本层：L1 ai 共享 OAuthPage） | 0 | B198（裁决） |
| 41 | redirect 等待：TTY 判定＋readline 与 abort 竞速＋手工粘贴 URL | OAuth | `oauth.ts:396-470` | `subcommand/McpRedirectPrompt.java`；`runtime/McpSignIn.java:232-265` | 转出（非本层：JVM 平台方言） | 0 | B212（裁决） |
| 43 | `clientRegistration` 三值＋`auto` 决策树（pi.dev Client ID Metadata Document） | OAuth | `core/mcp-servers.ts:112-125,136-146`；`oauth.ts:250-330,504-530` | `runtime/McpOAuthSettings.java:62,84`（`auto` 决策树；改名 build 恒回落 DCR、显式 `cimd` 才申请文档）；`McpClientMetadataDocuments.java:25`（拉 pi.dev 托管文档） | 转出（非本层：OAuth 身份托管基础设施） | 0 | B192（裁决；W5 补齐 auto） |

## E. 工具 / 资源 / 结果

已对齐 19 条（逐条证据见 git 历史）。

## F. CLI `pi-java mcp`（进程外：add/remove/list/login/logout）

已对齐 7 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 71 | 文案命名（`pi`→`pi-java`、`.pi`→`.pi-java`、去 chalk 着色） | CLI | `cli.ts:33-76`；`cli/args.ts:294` | `subcommand/McpCliHelp.java`；`cli/HelpText.java`（W6 补 `mcp <command>` 行；内容同 pi、仅改名） | 转出（非本层：全仓品牌改名方言） | 0 | B213（裁决） |

## G. 生命周期与接线（`createMcpExtension` —— **B221 接线面**）

> pi 的集成是一个扩展工厂（`index.ts` 1344 行），靠扩展 API 六个事件＋工具/命令/渲染器挂载面活着。
> pi-java 有钩子系统但**扩展事件面是另一域**（D 域/E5）；MCP 运行时**唯一生产消费者是进程外 CLI**（F 组）。
> G 组 23.0/23＝**100%（满分域）**；缺失清零（计分母余 #50 structuredContent 消费者，挂 B202/codemode）。

已对齐 15 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | pi 证据 | java 证据 | 判定 | 权重 | 台账 |
|---|---|---|---|---|---|---|---|
| 77 | `tool_call` 就绪等待（按 `tool.reachesTools(input)` 的 `"all"`/词包含命名空间；direct→只等属主服务器） | 接线 | `index.ts:224-232,1186-1207` | `McpExtension.java:303-314,412`（`reachesServer`：`All` 全等、`Words` 词包含 namespace；`ctx.signal` 无对位 ⇒ 窄差 D138） | 对齐（W7） | 1 | ~~B221~~ |
| 86 | `/mcp` 斜杠命令（manager、login/logout/reconnect）＋管理动作（enable/disable/exposure/project override） | 接线 | `index.ts:1260-1347,743-778` | `McpSlashCommand`＋`McpManagerActions`＋`McpServerOps`＋`McpManagerMenus`（文案逐字；写回经 McpConfig.update；参数补全无 java 面 ⇒ 窄差 D142） | 对齐 | 2 | ~~B221~~ |

## H. 权重 0 与共享主流面

| # | 能力单元 | 当前结论 | 旧权重 |
|---|---|---:|
| 90 | codemode 沙箱 | R5，不实现、不进分母 | 0 |
| 91 | `tool_search` deferred 工具发现 | 已有 Java 实现；新基线在 L2 重新确定计分归属 | 0（旧口径） |
| 92 | MCP 默认 `codemode` exposure 激活 | R5；direct/deferred 主流路径不受影响 | 0 |
| 93 | `ensureDiscoveryActive` | deferred/tool_search 臂已有实现；codemode 臂按 R5 排除 | 0（旧口径） |

---

## 加权汇总（目标锚点 `a00a5048d`）

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A 配置（12 计分＋2 转出） | 18 | **18.0** | **100%（满分域）** | 100%（12/12） |
| B 协议/客户端（7） | 13 | **13.0** | **100%（满分域）** | 100%（7/7） |
| C 传输（10 计分＋2 转出） | 14 | **14.0** | **100%（满分域）** | 100%（10/10） |
| D OAuth（11 计分＋4 转出） | 17 | **17.0** | **100%（满分域）** | 100%（11/11） |
| E 工具/资源/结果（19） | 27 | **27.0** | **100%（满分域）** | 100%（19/19） |
| F CLI（7 计分＋1 转出） | 11 | **11.0** | **100%（满分域）** | 100%（7/7） |
| G 接线（17） | 23 | **23.0** | **100%（满分域）** | 100%（17/17） |
| **模块合计（83 计分＋9 转出）** | **123** | **123.0** | **100%** | **100%（83/83）** |

**模块合计**：Σ权重 **123** ／ Σ(w×c) **123.0** ／ **加权完成度 = 100%**（未加权 83/83＝100%；对齐 83／存疑 0／缺失 0）。分母含新增单元 #96 `PipeTransport`（w1）。
**权重 3 只有 1 条（#15 initialize 协商），已对齐**。计分母缺失 0 条；#94/#95 为两条 w1 单元（W7／W8），#96 为新增单元。

### 缺失的权重分布（计分母 0 条）

无。

### 存疑（计分母 0 条）

无。

### 转出（非本层，权重 0，9 条）

A：#5（B182，L5 配置子系统）、#6（B183，共享 JSON 解析方言）· C：#27（B190，平台异常分类）、
#30（B191/B197，平台 URL 归一）· D：#38（B193，共享 JSON 序列化）、#40（B198，L1 ai 共享 OAuthPage）、
#41（B212，JVM 平台方言）、#43（B192，OAuth 身份托管基础设施）· F：#71（B213，全仓品牌改名）。

这些行保留在图中供追溯，但**不属于 MCP 层的计分单元**，归属由各层确认。

---

## 当前复核项

MCP 层无未结计分项。9 条转出行随各自层复核；本层不再维护。

## 目标锚点 `a00a5048d` 重判结论

- **L2 读数：123/123 ＝ 100%**。分母 +1 是新增单元 #96 `PipeTransport`（w1，计入分母），
  8 个原满分单元重判后全部回到对齐，无降级存疑项。
- 已等价的复核结论保留：回调 `closeAllConnections`（`HttpServer.stop(0)`）、CR-only/CRLF SSE、
  8 KiB 错误体长度、工具命名/输入 schema/20 KB 截断/配置校验。
- **锚点**：`CURRENT_PI_PIN=a00a5048d`；发行读数与 `mcp list` 复核已收口。
  发布视图见 [`../05-module-alignment-map.md` §1](../05-module-alignment-map.md)。
