# 04 - 未结项台账（open items register）

> 【背景摘要】本文档是「pi-java 还有什么没做完、没对齐」的唯一权威入口。它只收**未结项**与**仍有效的裁决**；已闭环批次的实施记录随过程文档清理删除，历史在 git。

## 0. 术语（首次出现释义，后文直接使用）

- **pi**：被对标的原项目（TypeScript 实现），代码在 `D:\workplaceForai\pi`。
- **锚点**：对齐测量时钉死的 pi commit。当前锚点 `ea448f454`（2026-10-11 定，v1.1.0-22；此前为 `200387122`）。换锚记录见 `docs/map/ANCHOR.md`。
- **对齐**：pi 有、pi-java 有、且行为相同。判据是 `bin pi` 主流发布版可达的**行为**，不是文档、API 形状或文件清单。
- **闭环**：一个对齐包从设计、实施到验收全部完成。
- **工单编号（A/B/C/E/F + 数字）**：本台账的条目编号。已结案的条目从本文件移除，存活条目编号**永不变更**（代码注释里有「台账 Bnn」引用），编号不连续是正常的。
- **裁决（ruling）**：架构或实现取舍的决定，附带风险评估与触发条件。已裁决不做的条目收在 §4。
- **模型可见面**：大模型能够读到的输出文本、报错文案、schema、模板行文。模型可见的契约文案优先**逐字对齐** pi（文字固定不可随意改动）。其余为模型不可见面，按下表细分：

| 面标记 | 含义 |
|---|---|
| 模型可见 | 系统提示、工具 prompt、注入上下文的文本 |
| 协议面 | RPC 事件、线格式，客户端可观察 |
| 用户可见 | CLI/TUI 文案、配置文件、页面 |
| 内部 | 外部不可观察的代码逻辑（遍历、IO、线程） |
| — | 整块缺失，面标记不适用 |

- **窄差表**：Java 实现与 pi 契约无法完全对齐的差异清单（D1–D8 等条目登记在 `map/03-agent-core.md`）。
- **R5 裁决**：只做 `bin pi` 主流发布版可达的功能。pi 的 experimental/ 内部子系统（chord、pico3、durable、micro 等）不进对齐面。
- **原 docs/NN**：已删除的过程文档编号。内容可在 git 历史查到（`git log -- docs/`）。

## 基准与口径

- 七份地图（含 2026-10-12 新建的 `map/07-mcp.md`）已于 2026-10-12 对当前锚点逐条重测完毕。重测销号 A16，新发现的差异登记为 B233–B238。
- 已闭环批次：ai 批 0–10（原 docs/08–19）、agent-core 批 1–3（原 docs/20–22）、telemetry 批 1（原 docs/23）、agent-core 批 4–5（docs/09–10）、coding-agent 批 1–12（docs/11–23）。
  - ai 批 0 裁决：B115/B117/B124 取证关单、#87 零代码对齐、11 个单元移出分母。
  - telemetry 批 1 结论：pi 的 telemetry 是休眠契约（没有实际调用点），`map/01` 完成度 100%。
  - 换锚裁决依据在原 `docs/11-pi-reanchor-ruling.md`。
- 每条给「是什么 / 现状与出处」。详细论证、探针与实施记录在 git 历史（已闭环的各包设计文档）。

---

## 1. 汇总

| 类 | 含义 | 谁能推进 |
|---|---|---|
| **A**（§2） | 要**证据 / 清点**才能定案 | 我（读 pi 源码 / 清点） |
| **B**（§3） | **功能缺口**（pi 有、pi-java 无） | 我（先出设计文档） |
| **C**（§4） | 已裁决**不做 / 对齐面外**，带触发条件 | 触发条件成立才动 |
| **E**（§5） | 结构债（单文件超过 500 行） | 我，与功能包搭车 |
| **F**（§6） | **待你拍板** | **你** |

计数可复现：`grep -c '^| [AB][0-9]* |' docs/04-open-items-register.md`。

---

## 2. A 类 —— 要证据 / 清点才能定案

**✅ 已清零（2026-10-18 批 agent-core-5，设计稿 docs/10）**：A10（并发审计＋`FileMutationQueue` 共享单例修真）、A14（`ShellByteBoundaryTest` 字节精确假 shell，泵审计无 bug）、A15（错误路 `TurnEnd.toolResults` 恒空夹具）全数销号。

---

## 3. B 类 —— 功能缺口

### 3.1 会话 / RPC 事件面

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B31 | 协议面 | `get_state.model` 字段形状不同 | Java 的 `RpcDispatcher.buildState` 发字符串 `"provider/id"`；pi 发的是整个 `Model` 对象 |
| B32 | 协议面 | `get_state.sessionName` 恒有值，pi 是可选 | Java 默认 `"session"`，客户端分不出用户命名与默认名；pi 在首次 session_info 事件之前不带这个键 |
| B33 | 协议面 | `get_state.messageCount` 计数口径不同 | Java 取转录条数，pi 取工作副本条数。压缩后两者必然分叉：转录含 compaction、model_change 等非消息条目 |
| B237 | 协议面 | RPC prompt 的处理结果缺 `handled` 状态 | pi 是三态 `handled\|queued\|started`（`rpc-types.ts:118`、`agent-session.ts:310-311`）；Java `RpcDispatcher.java:117-130` 只产生 started/queued |
| ~~B255~~ | 用户可见 | ~~**`-c` 空臂行为差：java 报错、pi 静默新建**~~（批 coding-agent-14 取证新登，docs/25 §1.3.7/裁决③） | **批 coding-agent-14 即登即销**（docs/25，2026-10-23 闭环，先例 B239）：pi `continueRecent`（session-manager.ts:1800）无会话时 `new SessionManager(...)` 静默新建；java 持久/内存两臂改 `orElseGet` 新建（web 臂早已同 pi）；夹具改钉新行为 |

### 3.2 请求选项 / Provider 协议面

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B116 | 协议面 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言 | pi 的 usage 只挂在消息上，proxy 线另发扁平 usage。2026-10-12 取证：线格式两处均已改成 pi 的扁平形状（`JsonEventMapper.java:320-327,351-373`），仅内部组件保留嵌套（原 docs/08 §2.2） |
| B253 | 协议面 | **attribution 头层**（pi `provider-attribution.ts:33-99`） | 批 coding-agent-7（docs/17）新登记。openrouter `HTTP-Referer`/`X-OpenRouter-Title`/`X-OpenRouter-Categories`、nvidia `X-BILLING-INVOKE-ORIGIN`、cloudflare User-Agent、opencode 会话头（`x-opencode-session`/`x-opencode-client`）——java 全仓零命中（安装遥测门 `isInstallTelemetryEnabled` 同层缺席）。pi 在 sdk.ts:355 的 `transformHeaders` 闭包里并入事件可见头集与线上请求；批 7 的头事件因此比 pi 少这一层（窄差 D33）。归属待定点：pi 在 coding-agent 包，java 落点在 ai provider/宿主缝 |
| B156 | 内部 | ModelCompat 逐组件重建会静默丢字段 | 新增组件时所有构造点都要改，漏改就静默缺省。2026-10-12 评估：全仓 13 个构造点集中在 4 个文件且有逐字段注释，列入 backlog 不强制改（原 docs/08 §2.2） |

**compat 面细碎登记**（pi 有标注或消费、Java 没有的字段与数据位）：

| # | 面 | 条目 |
|---|---|---|
| B94 | 模型可见 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明 |
| B95 | 模型可见 | `docs` 段没有生产者（ContextAssembler 不传 DocumentationPaths） |

### 3.3 功能整块

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| ~~B79~~ | 用户可见 | ~~`/bug` 故障上报 ＋ 崩溃记录未实现~~ | **批 coding-agent-15 已销号**（docs/26，2026-10-23 闭环）：crash-log 子系统（`CrashLog`/`CrashRecord`＋启动提示＋未捕获钩＋`/import` fatal＋`/bug` 建议）＋`/bug` 全链（三屏 `BugReportFlow`＋`BugReport` 核心＋上传/ZIP＋摘要缝）。原记录：pi 的 slash 命令流程：同意提示 → 生成摘要 → 上报 |
| ~~B256~~ | 用户可见 | ~~`/import` 失败语义与 pi 相悖（非 fatal）~~（批 coding-agent-15 登记，docs/26 §6 裁决⑤） | **批 coding-agent-15 即登即销**（B255/B239 先例）：pi `/import` 失败＝fatal（`recordCrash("fatal_error")`＋停 TUI exit 1）；java 旧返回 `"Import failed: …"` 继续会话——map/04 C-7 标「对齐」是账面错。修真：catch 臂记 crash＋`@ui:fatal-exit:` marker＋PiTuiApp 错误＋崩溃指引＋exit 1；file-not-found 保留 pi 非致命臂 |
| B218 | 内部 | `McpServerRegistry` 与 `McpScope.EXTENSION` 闭环前**没有生产者** | 注册表代码已落（原 docs/39），但 `pi.registerMcpServer` 没有挂载点；接线归 B221 |
| B219 | 模型可见 | `mcp_servers` 段的排序用 `String.compareTo` 代替 pi 的 `localeCompare` | 纯 ASCII 名字两者等价，非 ASCII 名字排序有差（原 docs/39 §3.2） |
| B220 | 模型可见 | `truncate` 的尾部裁剪字符集与 pi 不同 | Java 用 `trimEnd()`，pi 对应 `stripTrailing()` 语义；两者对窄角空格（U+00A0）处理不同（原 docs/39 §3.2） |
| B221 | 协议面 | **MCP 扩展接线面整体顺延** | 缺口清单：① 六个 `on(...)` 事件只接了 1 个（原 docs/39 §2.4）；② 激活门未暴露给扩展（机制在 `AgentHarness.getActiveTools:528`，扩展拿不到）；③ `ctx.ui.custom`（即 B214）；④ `registerToolRenderer`（即 B204）；⑤ 提示分节接线（`ContextAssembler.promptOptions:104-114` 从未调过 `.sections(...)`）；⑥ RPC `get_commands` 扩展臂（pi `rpc-mode.ts:684-690`：`source:"extension"`＋per-command sourceInfo——java 扩展命令与内置同池注册、无溯源，B247 裁决③挂此）。归属是扩展系统（D 域与 E5），不是 MCP 模块本身 |
| B161 | — | **codemode 整块零对应** | pi 用 quickjs-wasi 沙箱执行代码，Java 没有对应实现 |
| B162 | — | tool-search / virtual-models / nested-tool-calls 三块零对应 | pi 的 deferred 工具发现、虚拟模型、嵌套工具调用，Java 均未实现 |
| B177 | — | MCP 默认 `codemode` exposure 暂不可达 | MCP 各包（①–⑫a）的 wire/配置语义全部保留，但运行时激活路径依赖 B161 闭环（原 docs/28 §3 裁决） |
| B181 | 内部 | MCP 配置 record **不保留未知键** | pi 的 `validateMcpServerConfig` 返回原对象副本（`mcp-servers.ts:263,275`），Java 的 record 会丢弃未知键。下游只读具名键、写路径重读文件，所以行为等价（原 docs/35 §2.1） |
| B182 | 用户可见 | MCP 项目配置目录 `.pi-java` 是硬编码 | pi 读 `package.json` 的 `piConfig.configDir`（`config.ts:542`）；`.pi-java` 是 pi-java 全树既有约定（先例见 `FileSettingsStorage:42`，原 docs/35 §2.3） |
| B183 | 用户可见 | mcp.json 解析错误**文本**与 pi（V8 引擎）不同 | 结构相同（`<路径>: <消息>`）；空文件在 pi 是语法错，在 Java 报 `expected an object with an "mcpServers" object`（原 docs/35 §4.2） |
| B184 | 用户可见 | `McpConfigError` 是 Java 特有类型，写出字面量有偏差 | pi 抛裸 `Error`；`JSON.stringify` 把 `1.0` 写成 `1`；`pi mcp add` 的键序按 record 组件序（原 docs/35 §5.4） |
| B185 | 内部 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除 | TRUE/FALSE/null 三态（null 表示键缺席），与 B144 同形的误报；jspecify 的 `@Nullable` 不被 SpotBugs 识别（原 docs/35 §10 发现 2） |
| B186 | 内部 | **mcp 模块的 `verify` 在包⑥/⑦从未跑过** | `spotbugs:check` 绑在 `verify` 阶段；包⑥/⑦只跑了 `test`＋`checkstyle`，所以本包一跑 verify 就红两条（本包的 NPE 已修，另一条是 B185）。**教训：包闭环验证口径必须含 `verify`** |
| B188 | 内部 | MCP `!cmd` 的 `settings.shellPath` 层未接 | pi 是两段式（先配置 shell，ENOENT 错误时回落默认 shell）；`CommandRunner` 已可注入，第 12 包接线（原 docs/36 §2.4） |
| B189 | 协议面 | MCP 客户端身份由调用方注入 | pi 取 `config.ts:540,543`；Java 的 `Version.VERSION` 在 coding-agent 模块，mcp 模块内零字面量，第 12 包传入（原 docs/36 §2.3） |
| B190 | 内部 | `isTransientError` 的判定范围宽于 pi | pi 判 `TypeError`（fetch 网络错）；Java 判 `IOException`，包含 TLS、中断、重定向策略错误（原 docs/36 §5.1） |
| B191 | 内部 | `WebUrls` 的 WHATWG URL 归一化是自建实现 | pi 靠 `String(new URL(url))` 得到主机小写、补尾斜杠、去默认端口；Java 自建了同等逻辑，逐字节等价待真 pi 互读验证（原 docs/36 §6.2） |
| B192 | — | cimd 模式不可达 | `CLIENT_METADATA_BASE_URL = https://pi.dev/oauth` 是 pi 的域名，pi-java 没有托管文档 |
| B193 | 内部 | `mcp-auth.json` 的字节形态有偏差 | `clientInformation.extension` 写成嵌套键；record 的 `null` 等于 JS 的 `undefined`，序列化时省略。两侧都按 JSON 解析，行为等价 |
| B194 | 内部 | OAuth 刷新的 15 秒超时靠外包一层实现 | `McpFetch.fetch(Request)` 没有 signal 通道；未扩包⑥/⑦的接口（原 docs/36 §6.4） |
| B195 | 内部 | MCP 运行时新类暂无生产消费者 | 与 B177 同族：第 10–12 包接线（第 12 包为最后） |
| B196 | 内部 | `connectOnce` 顺序拉取工具/资源 | pi 用 `Promise.all` 并发拉取；差异仅在时序 |
| B197 | 内部 | `URI.create` 接受相对引用，而 pi 的 `new URL` 会抛异常 | `responseFromRedirectUrl` 已显式挡 scheme；其余 URL 入口未逐个复核 |
| B198 | 内部 | `OAuthPage` 的 CSS 折行 | 字节差仅在 `<style>` 内空白，计算值相同（受 checkstyle 120 列限制） |
| B202 | 内部 | MCP 结果的 `structuredContent` 没有消费者 | `ToolResult.structuredContent` 形状已落（包⑩）；消费者是 codemode（B161）与 `after_tool` 钩子改写（pi `types.ts:1439`），均未落 |
| B203 | 模型可见 | MCP 的 exposure/namespace/outputSchema/annotations 未接到 `AgentTool` | pi 的 `ToolDefinition` 有这四项；Java 的 `AgentTool` 没有，包⑩让 `McpToolDefinition` 承载；激活路径是第⑫包与 B161/B162 |
| B204 | 用户可见 | MCP 工具与资源的 TUI 渲染器未移植 | pi 的 `createMcpToolRenderers`（`tools.ts:301-339`）用 `Text`/`Container`/`VisualLinePreview`/`keyHint`；pi-java 的工具渲染另成一路（B65/B47） |
| B205 | 内部 | MCP 工具适配器在 `pi-java-coding-agent`，不在 `pi-java-mcp` | `AgentTool` 在 agent-core 模块，`pi-java-mcp` 看不见它（依赖方向 `ai ← mcp`），所以模块分道。原 docs/28 §2 的模块图早画了 `coding-agent ← mcp`，pom 直到包⑩才接上那条依赖 |
| B206 | 内部 | `ToolResult.isError` 是新增的语义通道 | pi 的 `isError` 来自结果对象（`AgentToolResult.isError`），pi-java 此前只由「抛没抛异常」派生。`PiToolRunner` 已改读结果字段，`after_tool` 钩子仍可改写 |
| B207 | 内部 | `McpToolExposure` 是 pi 的 `ToolExposure`，暂居 mcp 包 | 5 个取值 `direct/model-only/codemode/deferred/hidden`；pi-java 扩展系统没有这个类型，唯一生产者是 MCP 适配器，待 E5（扩展 API）面成型时上移 |
| B208 | 内部 | `ContentBlock.ImageContent(mediaType, data)` 与 pi 的 `{data, mimeType}` **参数序相反** | mcp 侧 `LlmContent.Image(data, mimeType)` 又跟 pi 一致，同一条转换链上有两个相反的序。换序点唯一（`McpResultContent.toBlock`），测试夹具两侧钉住 |
| B210 | 用户可见 | `pi mcp` 的选项解析是自建的 | pi 的 `parseOptions` 有「收够 N 个位置参数即停止」语义（`add` 传 2），让 `add <server> <command> --flag` 的 `--flag` 透传给命令；picocli 表达不了这个语义（原 docs/38 §2.4） |
| B211 | 用户可见 | `openBrowser` 是 pi-java 新增件 | 对应 pi `utils/open-browser.ts`；Java 新增 `BrowserLauncher`（Desktop → 平台命令 → 静默失败）；headless 环境只提示「visit the URL above」 |
| B212 | 用户可见 | `waitForRedirectUrl` 有两处平台替代 | `process.stdin.isTTY` 用 `System.console() != null` 替代（**不等价**，Java 没有 isatty）；readline 的 `{ signal }` 用虚拟线程读 ＋ abort 竞速替代（原 docs/38 §3.6） |
| B213 | 用户可见 | `pi mcp` 的文本字面量按 pi-java 命名 | 帮助/提示里的 `pi`→`pi-java`、`.pi`→`.pi-java`（与 B182 同族）；`chalk.dim` 去掉（pi-java 不做 ANSI 着色） |
| B214 | 用户可见 | `/mcp` 管理器视图（`ui.ts:252`）顺延第⑫包 | `McpManagerView` 是 pi `ctx.ui.custom(...)` 的载荷，而 pi-java 的扩展 UI 是 RPC 请求/应答，**没有组件挂载面**（E5 域）。菜单编排在 `index.ts`（9 个 `ui.menu/status/redirectUrl` 调用点），两半一起进⑫（原 docs/38 §2.1，R-A 裁决 A） |
| B216 | 内部 | 包⑪开的四处跨包小缝 | ① `McpServerConnection.oauthSettings()` 包私有改 public（pi 是公开）；② 新增 `clearChallenge()`（pi 是直接赋 `undefined`）；③ 新增 `McpServerState.wire()`（CLI 要打 `needs-auth`）；④ 新增 `SettingsManager.load(agentDir, projectDir, override)`（原先只能为进程自己的目录解析信任） |
| B217 | 内部 | MCP `login` 的端到端（真授权服务器）没有测试夹具 | 夹具覆盖到「连不上 / 非 OAuth / 缺参数 / 超时校验」为止；`--timeout` 秒数文案与 `reconnect` 后的成功路径需要真授权服务器（包⑨的 `McpSignIn` 另有夹具） |
| B163 | 用户可见 | 主题/色彩系统差距大 | pi 3,176 行（theme、system-theme、oklab 等），Java 74 行 |
| B246 | 混合 | **工具选择的 reload 臂＋SDK `tools` 选项抛错面**（批 coding-agent-1 新登记，docs/11 裁决④） | pi `usesDefaultTools`/`defaultToolModifiers`（`sdk.ts:472-473`）只在 `reload()` 有语义（`agent-session.ts:3660-3685`：设置重载后激活**新增**默认工具、移除的保持激活、`-name` 移除的保持移除），依附扩展运行时重载面；java 无 reload 面（设置改动走 accessors 即效）。SDK `tools` 选项的 `Invalid tools option` 抛错（`sdk.ts:280-281`）java 无 SDK 面；CLI 等价物＝解析期 error 诊断（批 1 已落）。触发条件＝java 出现 session reload/扩展运行时或 SDK 面 |
### 3.4 TUI（优先级最低，web UI 为主）

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B38 | 用户可见 | compaction_end 后 TUI 不重建聊天区 | pi 整表 clear＋重渲染＋压缩摘要消息卡；Java 现只有压缩指示器、一行摘要、QueueUpdate（2026-10-12 重测更正措辞），整表重建与摘要卡仍缺 |
| B39 | 用户可见 | 压缩窗口 Esc 换绑 | ~~随 B36~~ B36 已销号（批 agent-core-4 落 `abortCompaction`/`abortBranchSummary`）；换绑本体未做，与 B245 的 TUI 摘要流同族 |
| B46 | 用户可见 | BashTool 的 onUpdate 没有 TUI 消费者 | 生产发射面已通（BashTool→BashUpdateEmitter→SessionRunner；2026-10-12 重测更正：旧记「生产永不发射」已过期）。web 有 bashOutput，TUI 渲染面仍缺 |
| B47 | 用户可见 | TUI tool_execution_* 语义取错源 | Java 按「模型吐完调用」计数，pi 按真实工具执行事件计数 |
| B65 | 用户可见 | MarkdownRenderer/SyntaxHighlighter 是死代码 | 助手消息走纯文本，表格/mermaid/高亮/链接全不可达；先定「接线还是删声明」 |
| B66 | 用户可见 | ~~6~~ 3 个已声明键位未接线 | THINKING_CYCLE/TOOLS_EXPAND/THINKING_TOGGLE，按键无反应（2026-10-22 批 coding-agent-10 核账 6→4：MODEL_CYCLE 随 ai 批 3b（B137）已接、DEQUEUE 随批 10（docs/21）已接；批 12（docs/23 T3）核账 4→3：EXTERNAL_EDITOR 已接 Ctrl+G 外部编辑器往返） |
| B78 | 用户可见 | FuzzyMatcher 语义不同 | pi 是子序列匹配＋分词；Java 的实现不同 |
| B145 | 内部 | 启动/登录后定向目录刷新未接线 | 仅 RPC 模式有后台刷新，pi 另有三处刷新点 |
| B238 | 用户可见 | **新锚点**：TUI 四项新行为缺失（明细见 map/05） | ① K20 视口滚动键改 Ctrl+Home/Ctrl+End（裸 Home/End 归还编辑器）；② R29 安装变更每会话一次重启警告（`maybeShowInstallChangeWarning`）；③ K39 OSC 7501 程序状态上报（program-status）；④ C20 APC 标记假光标 |
| ~~B254~~ | 用户可见 | ~~**`/fork` 选择器本体＝pi user-message 选择器**~~（批 coding-agent-9 登记，docs/19 §1.7/裁决⑥） | **批 coding-agent-10 已销号**（docs/21，2026-10-22 闭环）：`UserMessageSelectorScreen` 落地＋`/fork` 改道＋`TreeSelectorScreen` 删除；顺带 S20 dequeue 链全接。原记录：pi `/fork` 走 `UserMessageSelectorComponent`（`user-message-selector.ts`），java 旧为 lane 平表 |

---

## 4. C 类 —— 已裁决不做 / 对齐面外

每条按【场景/决定/风险】三段式记录。风险等级用词固定：`无影响` / `边缘场景` / `[模型可见]文案差异` / `存量约束，非本批范围`。触发条件成立时才重新裁决。

### B18 — PiMessages 丢 thinking 签名/redacted 字段

- 【场景】PiMessages（pi-java 自有的线路格式）会丢 thinking 块的签名与 redacted 字段。
- 【决定】不按 pi 改造。PiMessages 是 Java 自有的 wire，pi 没有对应物。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：该 wire 成为对 pi 主协议的兼容面时重新裁决。

### B83 — pi 自身三处 sanitize 缝照缝移植

- 【场景】pi 自身在三个位置有净化缺口：普通工具重放入参、签名字段、google args。
- 【决定】照缝移植，不多做净化。
- 【风险/代价】边缘场景：安全性与 pi 持平，不优于 pi。[内部，模型不可见]
- 触发条件：pi 补上这三处时同步。

### B59 — SQLite schema 与 pi 分叉

- 【场景】pi-java 的 SQLite session 后端 schema 与 pi 曾经的 session-backends 分叉。
- 【决定】模块保留，分叉不再算缺口：pi 的 session-backends 已整包删除，且从未进 `bin pi`。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：pi 重新推出主流存储后端时重裁。

### B62（chord）＋ B71/B72/B73（pico3/durable/micro）— 内部子系统出对齐面

- 【场景】chord、pico3、durable、micro 是 pi 的内部子系统。
- 【决定】按 R5 裁决不进对齐面：非 `bin pi` 可达，权重 0。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：这些子系统进入 pi 主流发布版。

### B100 — compat 探测的 baseUrl 来源

- 【场景】compat 探测读的 baseUrl 来源不同：pi 只读模型对象，Java 读 `--base-url` 覆盖后的有效值。
- 【决定】2026-10-12 取证后降为架构备注：内置模型两者同值，零行为差。
- 【风险/代价】边缘场景。[内部，模型不可见]
- 触发条件：models.json 的 baseUrl 与 `--base-url` 并存且被证实可观察时重裁。出处：原 docs/08 §2.2。

### B126 — codex 车道忽略 maxTokens

- 【场景】openai-codex 车道忽略 maxTokens 参数。
- 【决定】随 codex 车道整体出对齐面。同族一并移出的还有 transport、responseId、endTurn、session-resources（权重均为 0）。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：codex 订阅车道纳入对齐面（§6 口径松动）时，与车道整体一起做。出处：原 docs/08 §2.1。

### B147 — Responses 回放 item 按类型分组

- 【场景】Responses 协议回放的 item 顺序：Java 用三桶收集（reasoning / message / function_call 分组），pi 是单循环原序。
- 【决定】保留为刻意偏差。rs_ id 配对靠签名里的 id；改成单循环的收益不值成本。代码注释在 `ResponsesMessageConverter.java:426-428`。2026-10-13 批 1 已补分组序夹具 `ResponsesCompositeItemIdReplayTest`（交错块固定 reasoning⇒message⇒function_call 分组）。
- 【风险/代价】边缘场景。[内部，模型不可见]
- 触发条件：发现 OpenAI 对交错序有配对校验差异时重裁。

### B164 — 分类器 / 统一模型设施

- 【场景】pi 有结构化分类 API（`classify()`）与统一模型设施。
- 【决定】按 R5 不做。`classify()` 的消费者只有 codemode（默认关）、llama 扩展和示例；`/model` 选择器里不可见。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：分类器进入 bin pi 主流的非 codemode 路径。出处：原 docs/08 §2.1，map/02 #92/#94/#33。

### B226 — Radius 内建 provider

- 【场景】Radius provider 在「主流 7 家」口径外。
- 【决定】2026-10-12 用户裁决维持权重 0。备注：`/login` 技术上可达（无白名单）；Java 已有 pi-messages 线与 OAuth 设备码工厂，边际成本 M；语义须按 gateway catalog 整体替换 baseline。
- 【风险/代价】存量约束，非本批范围。[内部，模型不可见]
- 触发条件：用户口径纳入 Radius（与 azure 同族，优先于 bedrock/vertex/codex）。

### B230 — azure 改名 ＋ Foundry 第二车道

- 【场景】azure provider 改名与 Foundry 第二车道在「主流 7 家」口径外。
- 【决定】2026-10-12 用户裁决维持权重 0。Java 的 responses 协议已在（仍注册旧 id）；改名工作量 S，Foundry completions 可复用现成车道，合计 S–M。
- 【风险/代价】存量约束，非本批范围。[内部，模型不可见]
- 触发条件：用户口径纳入 azure。

### B130 — zai `supportsReasoningEffort` 数据驱动

- 【场景】zai provider 的 `supportsReasoningEffort`（下文 sre）标记来源不同：pi 由生成器按 reasoning_options 推导，Java 目录给 null＋探测（isZai）兜底 false。
- 【决定】2026-10-11 取证转为「不做」：Java 只载 glm-4.7 族，pi 目录对其标 false、无级别表。两侧有效值逐字节相同，当前零行为差。
- 【风险/代价】边缘场景。[内部，模型不可见]
- 触发条件：`ModelData` 引入任何带 thinkingLevelMap 的 glm-5.x（或任何 pi 标 sre=true 的 zai 模型）时，`CatalogCompatRules` 的 effort 位必须改成按级别表有无数据驱动（≙ pi 生成器按 reasoning_options 推导）。守卫夹具 `carriedZaiModelsEffectivelyDontSupportReasoningEffort` 会先红。

### B132 — together/baseten/fireworks 目录常量

- 【场景】together、baseten、fireworks 三家的目录常量（thinkingFormat、chatTemplateArgs 等）pi 有、Java 未携带。
- 【决定】2026-10-11 取证转为「不做」：Java 不携带这三个内置 provider（R5）。`ThinkingFormatWriter` 的 TOGETHER/BASETEN 分支、`ChatTemplateKwargValue`、models.json 的 thinkingFormat/chatTemplateArgs 显式键与 URL/provider 探测全都在，手搓 models.json 接这三类后端与 pi 等效（fireworks 目录恒为 openai 格式、无特殊常量）。
- 【风险/代价】边缘场景。[内部，模型不可见]
- 触发条件：携带这三家内置 provider/目录时，常量随 `providers/data/*.json` 生成值一起搬（together 的 thinkingFormat；baseten 的 `chatTemplateArgs:{enable_thinking:{$var:thinking.enabled}}`）。

### map/03-#54 — Entry union 内存形状

- 【场景】pi 用 Entry union 在内存里表达 label/session_info 条目，Java 用 `SessionMutation`/`LogItem`。两侧内存表示不同。
- 【决定】2026-10-17 批 agent-core-2 裁决：**行为等价，不认账**。理由分三点：① label/session_info 的全部消费者 pattern-match 的都是 `SessionMutation`/`LogItem`，没有按 Entry 身份消费的；② wire 读写 pi 原生行（`PiV3Wire`），fact 链进树（parentId），磁盘全互通、树位等价；③ pi 的 `SessionTreeNode.label`（labelsById＋getLabel）与 Java 的 `SessionState.labels`＋`Session.getLabel` 是同构访问器，树节点本身两侧都不带 label。不补 2 个 record：补了要牵 7 个生产文件做双表示归一，买到的只是不可观察的形状洁癖。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：出现按 Entry 身份（而非 `SessionMutation`/`LogItem`）消费 label/session_info 的消费者时重裁。出处：原 docs/21 §4.1。

### map/03-#58 — convertToLlm 与 entry 投影的 case 合并部

- 【场景】pi 有两条管线：消息级 `convertToLlm` 与 entry 级 `buildSessionContext` 投影。Java 合并为一条 entry 投影管线。另外 Java 曾移植 pi 已删除的 harness `isContextMessage`，多了一段 stopReason 剔除逻辑。
- 【决定】分两部。合并部裁决**消解成立**：case 语义逐条可对上（custom→user、branchSummary/compactionSummary 加前后缀、四角色透传）；唯一真差是 bashExecution 角色不在对齐范围（harness 层只有三角色，原 docs/31 §1.2）。stopReason 剔除部**不消解**，已按认账删除：`NON_PROJECTED_STOP_REASONS` 移植自已删除的代码（R1），现行 pi 主流把 error/aborted 消息原样发给 provider。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：bashExecution 角色纳入对齐面时重裁合并部；pi 重新引入 stopReason 过滤时重裁剔除部。出处：原 docs/21 §4.2。

### map/03-#78 — latest/find 范围 ＋ listSessions 排序键

- 【场景】两处差异。① 查找范围：latest/find 的项目范围不同。② 排序键：pi 的 `listSessions` 按最后活动时间降序（取 max(message entry timestamp)，mtime 仅兜底，`session-manager.ts:858-864`），Java 按文件 mtime 降序（`JsonlSessionRepository.java:85-90`）。
- 【决定】latest/resume 判**等价**：Java `latest(cwd)` 单项目目录与 pi `continueRecent`/`findMostRecentSession:757` 同源同键。`find(idOrPrefix)` 跨项目扫描**裁决保留**（CLI `--resume <id>` 需要，Java 多出面）。排序键差**留痕不认账**：pi 的 lastActivity 需对每个会话文件全量扫 entry，这正是 Java 优化掉的成本（实测 1082ms→5ms）；追加写场景下 mtime 序 ≈ 活动序。
- 【风险/代价】边缘场景：改名/打标追加 fact 行时，fact 行只 bump mtime、不 bump 活动时间，选择器顺序可能与 pi 不同。[内部，模型不可见]
- 触发条件：上述分叉被证实可观察时重裁。出处：原 docs/21 §4.3。

### map/03-#100 — 系统提示载体

- 【场景】系统提示的载体不同。pi 的请求上下文只有 messages，系统提示由 `normalizeContext`（`utils/transcript.ts:30-34`）折成首条 system 消息。Java 有独立的 `Context.systemPrompt` 字段与 `AgentHarness.setSystemPrompt`。
- 【决定】2026-10-17 批 agent-core-2 裁决：**行为等价**。pi 主流没有会话中变更系统提示的路径（`setSystemPrompt` 全仓零命中，写入点全在启动期）；Java 在 provider 边界经 `ContextNormalizer` 在同一点做同一折叠（`DefaultProviders` 交出 `Context.systemPrompt`），请求线行为等价。`AgentHarness.setSystemPrompt` 是超集能力（pi 无），留痕不删；`Context.java` 类注已按本锚点重写。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：pi 出现会话中系统提示变更路径（`setSystemPrompt` 类 API）时重裁。出处：原 docs/21 §4.4。

### map/03-#68 — streamProxy 按 R5 出分母

- 【场景】pi 的 `agent/src/proxy.ts`（393 行）在锚点逐字存活（返型已改 pi-ai 工厂 `createAssistantMessageEventStream()`），pi-java 没有对应物；旧图据此判「缺失」计分母。
- 【决定】2026-10-18 批 agent-core-4 裁决①（docs/09 §13.1）：**按 R5 移出分母**（权重 1→0）。pi 侧虽存活但 **bin pi 主流不可达**：coding-agent 全包零 import；引用点只有 `agent/src/index.ts:3` 的 `export *` 再导出、单测、`scripts/browser-smoke-entry.ts:23`（仅 `typeof` 存在性断言的构建冒烟，从不调用）。它是面向「服务端托管鉴权代理」宿主的可选 StreamFn，等价「只被工具面/测试引用」。
- 【风险/代价】若日后做服务端托管鉴权代理车道，需补回并按新返型对齐。[内部，模型不可见]
- 触发条件：pi-java 做服务端托管鉴权代理车道（TeamoRouter 服务端化之类），或 pi 在 coding-agent 主流接入 streamProxy。

### map/03-#14 — QueueMode 宿主语义（设置同步非即效）

- 【场景】steer/followUp 队列的 drain 模式（`"all" | "one-at-a-time"`）在运行时与设置文件之间的同步路径两侧不同：pi 写入设置后靠 `syncQueueModesFromSettings` 在 `_buildRuntime`/`reload` 才同步进 agent（`agent-session.ts:2691-2693,3674`）；pi-java 没有 reload 面，装配时一次读入。
- 【决定】2026-10-18 批 agent-core-5 裁决：**对齐**。drain 语义（`QueueManager.java:144-160` ≡ pi `agent.ts:156-170`：All 全排、one-at-a-time 排最老一件）、默认值（pi `SETTINGS_DEFAULTS.steeringMode` = one-at-a-time = `QueueMode.defaultMode()`）、运行时 setter（pi 直写 agent ≡ `AgentHarness.steeringMode/followUpMode`＋`RpcDispatcher`）三面逐字等价；设置改动**两侧都非即效**（pi 等 reload，java 等下次装配）⇒ 净效果一致。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：pi-java 获得会话 reload/扩展运行时面（B221 族）时重裁同步时机。出处：docs/10 §12.1。

### map/03-#80/#84 — prompt 两道门与「压缩窗」

- 【场景】运行中/压缩中提交 prompt 的两道门：pi 的压缩与 run 单飞、门在 prompt 入口直接抛（`agent-session.ts:1988`）；pi-java 有「压缩窗」概念（包 25–27：B175 关窗、B176 worker），窗口内提交缓冲为 followUp。
- 【决定】2026-10-18 批 agent-core-5 裁决：**对齐**。① 两道抛文逐字（pi `:1988` "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry." 与 `:2016` "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message." ＝ `PromptRouter` 两常量）；② 顺序等价：展开（输入钩子/`/skill:`/模板）先于门（pi `:1997-2011`→`:2012`；java `AgentSession.java:808`→`:812`）；③ 排队等价：steer/followUp 入队＋`queue_update`（B34）。pi 无窗概念——窗口是 pi-java 的宿主方言，门外的可观察行为（何时抛、抛什么文、排队去向）两侧一致。
- 【风险/代价】压缩窗内提交在 pi-java 缓冲而 pi 直接抛：这是批 25–27 已裁决的窗口语义（B175/B176），不属于本门。[内部，模型不可见]
- 触发条件：pi 引入压缩窗/缓冲语义，或 pi-java 拆除压缩窗时重裁。出处：docs/10 §12.3。

### map/03-#82 — retry.provider 三键映射

- 【场景】provider 重试三键（`maxRetries`/`maxRetryDelayMs`/`timeoutMs`）从设置到 HTTP 重试策略的映射。
- 【决定】2026-10-18 批 agent-core-5 裁决：**对齐**。`maxRetries` 缺席两侧同为 0（pi `provider-retry.ts:106` `?? 0` ＝ `DefaultProviders.java:330-331`）；`maxRetryDelayMs ?? 60000`（pi `settings-manager.ts:925-931` ＝ `:332-333`）；`timeoutMs` 明给替换默认（pi `sdk.ts` buildRequestOptions ＝ `:345-346`）。`httpIdleTimeoutMs` 默认值差异属 map/04 E2-48，另案。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：pi 改三键默认值或新增键时重裁。出处：docs/10 §12.4。

### map/03-#90/#91 — transcript 可见性与 reset 的 runSpan

- 【场景】① 车道转录/工作副本的跨线程可见性；② `RunLifecycle.reset` 把 `lane.runSpan` 置 null 而不 `close()`。
- 【决定】2026-10-18 批 agent-core-5 裁决：**对齐**。① 写者唯一（驱动线程 `lane.appendEntry`，lane 锁内）；读者全走快照 COW（`LaneSnapshot`/`SessionSnapshot` 的 `List.copyOf`——records COW 裁决同族）或存储级新解码（`findEntries`）；无锁裸读面不存在（#83 清点表，docs/10 §10.2）。② `finishRun` 在 `PiLaneEngine.java:217-231` 的 finally 收口（error 路同走）⇒ 空闲车道 runSpan 必已关闭，reset 置 null 是防御性清场（注释已补强）。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：出现新的转录写入点或不走快照的读取面时重裁。出处：docs/10 §12.6/§12.7。

### map/03-#57 — bashExecution 分层与窄差包（含 #53/#75/#77 窄差）

- 【场景】pi 的 `BashExecutionMessage` 经 declaration merging 挂在 `AgentMessage` 超集上，`convertToLlm` 在请求前转译；pi-java 无 AgentMessage 层（批 2 #58 已裁决消解），且若干周边形状差。
- 【决定】2026-10-18 批 agent-core-5：本体**做**（`Entry.BashExecution` 变体＋编解码＋投影逐字＋`recordBashResult`＋pending flush，docs/10 §3——与 branchSummary/compactionSummary 同分层），窄差**登记不认账**：① `excludeFromContext`（!!）条目在 pi 留 `state.messages` 占工作副本位、仅 `convertToLlm` 跳过；java 投影层直接不产消息——下游唯一消费者是 LLM 请求装配，模型可见面等价；② token 估算 java 走投影后文本（比 pi 的 `command+output` 多 `Ran `/围栏包装 ~20 字符，阈值精度差忽略）；③ 会话级 bash 无 50KB 截断/`fullOutputPath` 落盘（`ShellResult.truncated` 恒 false——截断在工具层，会话级 bash 绕开）；④ #53：pi 会话事件的 `parentToolCallId` 包装（子代理上下文）java 无——无嵌套代理面，顶层会话恒 absent；⑤ #75 F-d：空/无头 fork 源文件的错误形是 load 期 DecodeError，非 pi 两条专属文案；⑥ #77：pi 的 `_pendingToolNames` 晚注册臂随 B221 ⑫b，未注册名现静默落。
- 【风险/代价】①②③ 均为模型不可见的内部近似；④⑤⑥ 在对应功能面（嵌套代理/SDK/扩展运行时）落地前不可观察。[内部，模型不可见]
- 触发条件：③ 会话级 bash 截断面立项时；④ 嵌套代理面落地时；⑤ fork 错误面投诉时；⑥ B221 ⑫b 落地时同批收编。出处：docs/10 §3.2/§5.2/§12.2。

### map/03-#60 — PendingEntry/InboxItem 按 R1 出分母

- 【场景】旧图把 pi `agent/src/types.ts:293,306` 的 `PendingEntry`/`InboxItem` 列为队列契约参照；现锚点全仓零命中。
- 【决定】2026-10-18 批 agent-core-5 裁决：**R1 作废出分母**（Σw 182→180、91→90）。二者是 Pico 实验 harness（`agent/src/harness/**`）的类型，随 pi `7fd478a2e feat(agent): remove the experimental harness from pi-agent-core` 整包删除——与「作废清单」19 单元同一条删除链。java 队列契约以 pi 存活的 `PendingMessageQueue`（`agent.ts:142-176`）为参照，#14 已核齐。
- 【风险/代价】无影响。[内部，模型不可见]
- 触发条件：pi 重新引入同形类型时重裁。出处：docs/10 §13。

### map/04-B236 — 工具选择三层语义（`--tools` pattern/modifier/`mcp__` ＋ `defaultTools`）

- 【场景】pi 的 `--tools` 不是逗号裸名过滤，而是三层语义：名单条目（裸名/`*` 模式/`mcp__` 前缀）→ 修饰符解析（`+name`/`-name` 叠在默认选择上）→ 注册面门（`_isAllowedTool`：不匹配的内置工具连注册都不注册，MCP 工具有保留条款）；settings `defaultTools` 键同套 modifier 语义并有分层 merge 特判（覆盖层全修饰符 ⇒ 追加）。java 原是逗号裸名切分＋七件全激活。
- 【决定】2026-10-18 批 coding-agent-1（docs/11）：三层语义＋`defaultTools` 键整体落地（`ToolNameMatcher`/`ToolSelection`/装配接线，map/04 A-20/A-21/E2-35 翻对齐）。**裁决①**：默认激活集 **7→4**（read/bash/edit/write——pi `DEFAULT_TOOL_NAMES` 恒四件，pi 自有测试实证裸跑激活面；本批最大用户可见变化，裸跑不再向模型声明 grep/find/ls，恢复途径 `--tools +grep,+find,+ls` 或 settings `defaultTools`）。**裁决②**：`ToolSetFactory.createCodingTools`（七件形）删除——pi 现锚点 `createCodingTools`＝四件，java 七件形无对位，唯一主码消费点改走 `createAllTools` 八件＋选择策略。**裁决③**：`mcp__` 保留语义**策略先行**（`isMcpToolName`/`allowlistFiltersMcp`/注册面门的 MCP 保留臂全量落地为纯函数＋测试）——java MCP 工具尚未进会话注册面（B221 ⑫b），本批无可观察 MCP 行为变化，MCP 工具进注册面时自动获得 pi 语义。**裁决④**：reload 臂（`usesDefaultTools`/`defaultToolModifiers`，`agent-session.ts:3660-3685`）与 SDK `tools` 抛错面登记 **B246** 不做。窄差登记（docs/11 §5）：D1 校验诊断前缀恒 `--tools`（picocli 不暴露实敲别名）；D2 SDK 面抛错无对位（CLI 等价物＝解析期 error 诊断）；D3 `defaultTools` 形状异常 pi 容忍、java 类型化读入即抛；D4 `_pendingToolNames` 晚注册臂随 B221 ⑫b，本批点名的未注册名静默丢弃。
- 【风险/代价】裁决①是**意图中的模型可见面收缩**（非回归）；裁决③在 MCP 工具进注册面前不可观察；D1–D4 均为边缘/内部近似。[① 用户可见；②③④＋D 族 内部，模型不可见]
- 触发条件：③④ MCP 工具进会话注册面（B221 ⑫b）时收编；java 出现 session reload/扩展运行时或 SDK 面时重裁（B246）。出处：docs/11 §5。

### map/04-B244 — `skill:` slash 命令注册面 ＋ `enableSkillCommands` 开关

- 【场景】pi 的 `skill:<name>` 不是真注册命令——只活在补全名单里（`interactive-mode.ts:803-815`，受 `enableSkillCommands` 门，默认 true），提交时靠「非内置命令 ⇒ 落普通 prompt ⇒ `_expandSkillCommand` 展开」生效；设置只门补全名单，展开恒开（`agent-session.ts:2003-2011` 的 gate 是 `expandPromptTemplates` 参数）。java 批 4 已把展开器装进 `AgentSession`，但 `CommandRegistry.dispatch` 把 `/skill:foo` 当未知命令吞掉，展开永远到不了。
- 【决定】2026-10-19 批 coding-agent-2（docs/12）：注册面三层整体落地——① settings accessor（缺省回落 true、写全局域）＋设置页 "Skill commands" 字段；② TUI 补全目录 skill 臂（`SkillCommandItems`，来源标签 u/p/t 三值子集）；③ `CommandRegistry.dispatch` 对 `/skill:` 前缀返回 null 透传为 prompt；④ RPC `get_commands` 追加 skill 臂（现形状）。**裁决①**：不注册真 `SlashCommand`（pi 同形，`SlashCommand` 返回语义无「转 prompt」通道）。**裁决②**：展开不受开关门（pi 实证）。**裁决⑤**：pi 的 `skillCommands` Map 只写不读，不移植。**裁决⑦**：overlay 关闭统一重建补全目录（pi 是 toggle 即时重建）。**不移植**（裁决③）：legacy 嵌套 `skills` 对象迁移。RPC 形状差（builtins/source/sourceInfo/模板臂）登记 **B247**。
- 【风险/代价】窄差（docs/12 §5）：D1 手抄 pi 旧格式 `skills` 对象进 `.pi-java` ⇒ Jackson 加载期报错（pi 静默迁移）；D2 来源标签仅 u/p/t（pi 另有 npm:/git: 复合标签，java 无包源加载面）；D3 未识别 slash 仅放行 `/skill:` 前缀，其余仍报 Unknown command（pi 任何 `/xxx` 都落 prompt，java 既有 UX；批 3 起放行面扩到已注册模板名，见 B247 条目）；D4 补全目录在设置屏关闭时重建（pi toggle 即时）。[D3 用户可见；D1/D2/D4 边缘/时机]
- 触发条件：java 包源加载落地时回收 D2。出处：docs/12 §5。

### map/04-B247 — prompt template 命令注册面 ＋ RPC `get_commands` 形状对齐

- 【场景】B244 把 `/skill:` 注册面补齐时，模板族还是老三样缺：补全目录无模板臂（pi **无条件**注册，`interactive-mode.ts:787-792`）、手敲 `/tpl` 被 `CommandRegistry` 当未知命令吞掉（批 4 的 `PromptTemplateExpansion` 触达不到）、RPC `get_commands` 是 java 自造形状（收内置 24 条、`(name, description, argumentHint)`、`data` 裸数组；pi＝扩展＋模板＋skill **无内置**、`(name, description?, source, sourceInfo)`、`data` 包 `{commands}` 键——rpc-types.ts:77-89,241／rpc-client.ts:459）。
- 【决定】2026-10-19 批 coding-agent-3（docs/13）：① 注册面两层落地——补全目录模板臂（`PromptTemplateItems`，无条件＋u/p/t 标签＋argumentHint 透传，序在内置后 skill 前）＋dispatch 对**已注册模板名**透传（`SlashContext.session()` 查注册表，null 守卫）；② 加载期 sourceInfo 打标——`PromptTemplate` 记录增 `sourceInfo`（新叶子包 `core.source.SourceInfo`，裁决⑦防 core↔prompt 包环），`loadDefaults` 拆 user/project 两路、显式路径 temporary＋pi baseDir 规则（目录⇒自身、文件⇒父目录）；③ RPC 形状一次收平——`RpcSlashCommand` 改四组件（删 `argumentHint`，裁决⑤）、去内置（裁决①，pi 语义＝可经 prompt 调用，内部消费者零命中复核）、`data` 包 `{commands}` 键（裁决②，批 2 偏差②回本）、skill 臂补五字段 sourceInfo（EXPLICIT→temporary）。**裁决③**：扩展臂随 B221（java 扩展命令与内置同池、无 sourceInfo 溯源），不新立 B 号、差入窄差 D5。**裁决④**：透传按「已注册模板名」判定，D3 保持不扩成任意 slash 透传。**裁决⑥**：sourceInfo 值域落 `"local"`/`"top-level"`＋scope 三值子集（npm:/git:/package 随包域，窄差 D2 同族）。
- 【风险/代价】线格式三变（去内置/改形/包键）——外部裸读旧形状脚本断，内部 client/server/web 零命中（2026-10-19 grep 复核）；窄差：D3（更新）未识别 slash 放行 `/skill:` 前缀＋已注册模板名，其余仍 Unknown command；D5（新）`get_commands` 无扩展臂（当前无触发者——java 扩展命令注册面无实际生产者）。[线格式用户可见；D5 当前空转]
- 触发条件：B221 落地时同批收编扩展臂（销 D5）；java 包源加载落地时回收 D2 族（npm:/git: 标签＋package origin）。出处：docs/13 §5。

### map/04-批 coding-agent-4（docs/14）— D 域地基：扩展事件订阅面＋双源桥接

- 【场景】pi 的扩展事件面＝`ExtensionAPI.on()` 41 重载＋loader 订阅表＋runner 发射语义。java 的引擎钩子（agent-core `HookSystem` 13 钩）与宿主事件（`AgentSessionEvent` 24 变体）早已各自生产，但 `ExtensionContext` 不暴露订阅面——扩展一个事件也订不到。D 域 Σ权重 83 是 map/04 最大域（单独拉低模块 14.6pp）。
- 【决定】2026-10-20 批 coding-agent-4（docs/14）：① 订阅面——`ExtensionContext.on(spec, handler)`（裁决① spec-token 单方法，非 41 便捷方法、非裸字符串）＋`ExtensionEventBus`（注册序触发、退订按实例移除空表删键、`emitError` 独立通道、`hasHandlers` 快走）＋`DefaultExtensionContext.forExtension` per-extension 委托（错误按扩展名归属，pi `extensionPath` 对位）；② 引擎钩子族 3 适配器恒装快走（裁决②）——`context` 两阶段（系统消息过滤、原地可变列表＋**引用相等**快照〔裁决④〕、`restoreSystemMessages` 逐字、阶段二丢头系统上报仍采用）、`tool_call`（block 短路、**异常向上传播**〔裁决③〕、input 原地可变链显式回流）、`tool_result`（逐字段合并＋structuredContent 丢弃规则逐字）；③ 宿主事件族 14 事件 `SessionEventBridge`（裁决⑤ 装配期订阅先于模式层；turnIndex 复位/递增、previousLevel 桥内跟踪、CompactionEnd 成功/失败两支）；④ 错误通道——`AgentSessionEvent.ExtensionError` 新变体（裁决⑥），RPC `extension_error` 线四键逐字（rpc-mode.ts:349），TUI 先忽略（窄差 D5）；⑤ 存量修真——`applyAfterToolPatch` 5 参重建洗 `isError`/`structuredContent` 的 bug（docs/14 §2.3），`ToolResultContext` 增 `arguments`（pi `prepared.args`）。
- 【风险/代价】窄差（map/04 D 域批 4 窄差表）：D1 引用相等快照（pi `===` 同构）；D2 无逐工具判别子类型；D3 message_update 从 partial 投影；D4 `session_compact` 无 compactionEntry（判存疑，挂 B249）；D5 extension_error TUI 不展示；D6 fromExtension 恒 false；D7 parentToolCallId 缺席（G-10）。（~~D8~~ 作废：闭环评审发现 pi `ToolExecutionEndEvent` 本无 `args`，误加组件已删。）新开放项：**B248**（provider 请求面四件——ai 注入口缺席）、**B249**（结果臂族）、**B250**（session_start/shutdown 发射点）。闭环评审两修已落：① `tool_execution_end` 扩展事件 `args` 组件删除；② 扩展处理器抛 `Error` 的捕获链（pi catch 一切——`tool_call` 包 `RuntimeException` 走错误结果链、其余族 `Throwable` 逐处理器上报继续）。[D4 模型可见；D5 用户可见边缘；余内部/无影响]
- 触发条件：B248＝ai 层开 `onPayload`/`transformHeaders`/`onResponse` 注入口；~~B249~~/~~B250~~ 批 5 已销号（docs/15）。出处：docs/14 §4–§6。

### map/04-批 coding-agent-5（docs/15）— 结果臂事件族＋会话生命周期（B249＋B250 合包）

- 【场景】批 4 把扩展事件的**观察面**桥完了（能订、能看），但 pi 事件面的另一半——**结果面**——扩展一个也用不了：改不了定案消息（message_end 替换）、改不了输入（input）、改不了系统提示（before_agent_start）、注不了条目也续不了跑（boundary）、否决不了切换/压缩（cancel 族）、接管不了 bash 与压缩生成；会话生命周期两事件（session_start/shutdown）连发射点都没有。D 域剩 17 缺失＋7 存疑里 12 行挂这一族。
- 【决定】2026-10-20 批 coding-agent-5（docs/15，T1 引擎扩形→T2 事件 ADT→T3 宿主发射器→T4 boundary 子系统）：① **裁决①**——message_end 替换走引擎新钩（`PiLaneSink` 前换引用：宿主事件在 flush 后、Message 不可变，这是唯一保语义的缝；宿主桥 case 删防双发，观察/结果同批一次）；② **裁决②**——boundary 落引擎钩子（turn_end 复用 `should_stop_after_turn` 并修真载荷〔旧喂 lane.partial＋空工具结果〕；agent_before_settle 新钩 firing 于 drive 循环 settle 前门，pi :1839 同位），`BoundaryBridge` 全落 emitBoundary 逐字（entries/continue 链、每处理器后预览重建、Invalid boundary entries 上报）、草稿四型 commit（compaction 草稿算 retainedTail＋tokensBefore，fromHook=true）、消息→entryId identity 映射、`<boundary>` 归属上报；③ **裁决③**——`SessionEventFirer` 合订宿主发射（同步执行即 pi await 时点）：input 变换/handled（展开**之前**，pi :1991 逐字）、user_bash 首发言者赢＋异常重抛不回落、before_agent_start 累积＋systemPrompt 链式覆盖、before_switch/fork cancel 短路；④ **裁决⑥**——before_compact 引擎钩子载荷/结果三臂扩形（cancel＞supplied＞keepEntries），prepare 前置补 preparation 载荷；`CompactionObserver.onEnd` 第 6 参穿透 compactionEntry（批 4 窄差 **D4 销号**），`fromExtension` 读 fromHook（**D6 销号**）；⑤ **B250**——session_start 装配期发射（初始恒 startup，CLI resume 也是——pi 逐字）、`createReplacing`（new+previous）、`switchTo` 集中 switchSession 序列（cancel→预检→shutdown→attach→start，start 在新侧 firer）、fork 两法集中 before_fork＋shutdown、close/closeForReplacement/fireSessionShutdown（quit/reason+target）；⑥ 调用点改签——SessionCommands/RpcDispatcher/WebDispatcher/TreeSelectorScreen；`ExtensionManager.loadAll` 改 TCCL（生产不变，测试注入用）。
- 【风险/代价】窄差 15 条（map/04 D 域批 5 窄差表 D9–D23）：D11 systemPrompt 覆盖是 harness 全局态（模型可见）、D10 user_bash operations 臂不移植、D12 input images 恒 null、D14 文件路径位装会话 id、D20 /new 臂 shutdown/start 序反，余皆内部/边缘。引擎钩子 13→15 个。新开放项：**B251**（forked 不加载扩展）、**B252**（旧会话 close 治理）。[D11 模型可见；余内部/边缘]
- 触发条件：~~B251~~/~~B252~~ 批 6 已销号（docs/16）。出处：docs/15 §4–§6。

### map/04-批 coding-agent-6（docs/16）— fork 扩展装配＋会话关闭治理（B251＋B252 合包）

- 【场景】批 5 把生命周期的「发射点」点着了，但 fork 后的会话是个没有扩展的空壳（`forkInMemory`/持久 fork 路直 new 私有构造、不过 `loadExtensions` ⇒ fork/clone 后扩展工具/命令/订阅/资源发现整体消失、`start{fork}` 无消费者）；被替换的旧会话从不真正关闭（/new、fork/clone、TUI 退出、RPC EOF 全摘订阅即弃，`session_shutdown{quit}` 在 TUI 永不发射）。同根新发现：`harness.fork()` 共享成品 config ⇒ 压缩/重试事件、重试中止旗、图片策略、PI_* 会话身份五组闭包经 sessionRef 全读父会话。
- 【决定】2026-10-21 批 coding-agent-6（docs/16，T1 fork 装配重建→T2 teardown 集中→T3 退出面）：① **T1**——assemble 的「harness＋会话绑定装配」抽为共用 `wireSession`（pi createRuntime 工厂型对位），fork 三路全走它（fresh ToolRegistry/CommandRegistry＋fresh 会话绑定闭包——五组串线同修），forked `loadExtensions` 全量重装并发射 `session_start{fork, previousSessionFile}`（pi 逐字序：teardown → 重建 → rebind）；**裁决①** model/thinkingLevel 携带父会话当前值（java 无 setModel persist 面，携带＝pi persist 臂实际效果），其余按 args/settings 重解析；**裁决②** 注册表 fresh、providers 共享（注入缝所限，窄差 D30）；② **T2**——`closeWith` 三参化（**裁决⑤** abort() 首步对位 pi settle 在途 turn、句柄开关、**裁决⑥** 幂等门）；fork 内部 `closeForFork`（保共享 repository 句柄）；/new＋RPC new_session 改 `closeForReplacement`（`closeSession()` 改名 `unsubscribe()`）；`attachOpened` 原地 attach 关旧 storage（先 persistPending 后关句柄，同对象/同 id 恒等跳过）；`AgentHarness.fork()` 删除（无引用）；③ **T3**——`PiTuiApp.exit()` 关**当前**会话（pi 交互 quit 臂：先停 UI 后 dispose）、`RpcDispatcher.close()` 关 dispatcher 当前会话（pi rpc-mode.ts:736）——`session_shutdown{quit}` 自此在 TUI 与 RPC 退出路径发射。
- 【风险/代价】窄差 7 条（map/04 D 域批 6 窄差表 D24–D30，均内部/边缘）：D24 fork 携带模型略宽于 pi 的 Ctrl+P 臂、D25 内存路 switchTo 旧对象不关（关即毁数据）、D26/D27 内存 CLI --fork 两测试路边、D28 内存注册表残留 closed 会话、D29 无 extensionRunner.invalidate 对位、D30 providers 共享。账面：行翻转 0（D-3/D-10 早已判对齐，摘「挂 B251/B252」帽）；D 域 66.0/83＝79.5% 与模块 283.5/496≈57.2% 均不变（缺陷闭环，同批 3 口径）；引擎钩子 15 不变。[均内部/边缘]
- 触发条件：无（本族已闭；D25/D28 随内存会话治理立项时重裁）。出处：docs/16 §3–§6。

### map/04-批 coding-agent-7（docs/17）— provider 请求面扩展事件四件（B248）

- 【场景】D 域最后一条 w3 事件线：pi 的扩展能看/改每一次出站 provider 请求（载荷、头）并观察响应与原始流事件（`on()` 四重载 types.ts:1604-1609）；java 的 ai 模块没有注入口（`SimpleOptions` 明文不移植）、引擎旧钩 `before_payload`/`after_response` 形状不符且从未 firing——要穿的是「ai 层先开口子 → 七车道 firing → 扩展桥」整条链。
- 【决定】2026-10-21 批 coding-agent-7（docs/17，T1 ai 缝→T2 自备 HTTP 面→T3 扩展桥）：① **裁决①**——缝的载体＝`ApiOptions.extra` 三键（`onPayload`/`onResponse`/`onProviderStreamEvent` 逐字对位 pi StreamOptions 键），车道构造期读取（`ProviderHooks`）；`transformHeaders` 不设 extra 键——在 `DefaultProviders` 的 createApi 前缝消费、钩本体剥离（pi models.ts:866-871/「without forwarding the transform」对位）；② **裁决②**——桥放 StreamFn 层（`ProviderEventBridge`，pi `extensionRunnerRef`＋四闭包对位），不动引擎 HookSystem（旧钩是 legacy 遗物，形状不符，维持不 firing），引擎钩子数 15 不变；③ **裁决③**——bus/ctx 晚绑定（`loadExtensions` 回填；`--no-extensions` ⇒ 全跳＝pi 空表同效；`hasHandlers` 调用时现查）；④ **裁决④**——`onResponse` 覆盖：SDK 四车道 `withRawResponse()`（成功获取后、流消费前）＋自备两车道 `SseResponseObserver` 终态面（含错误状态，pi fetch 车道同形）；**google 不发＝pi 同缺**（pi google-generative-ai.ts/google-vertex.ts 全文件零命中）；⑤ **裁决⑤**——payload 载体＝车道原生对象（SDK params 类/Map/ObjectNode/`GooglePayload` record），同型守卫不符 ⇒ 请求失败（pi 发垃圾形状收 400，同属失败、失败点提前）；⑥ **裁决⑥**——headers 事件＝可变 map 原位改＋null 删除（合并集内；SDK 默认头抑制不可达）；⑦ **裁决⑦**——Azure 补 `putExtraHeaders`、PiMessages 补 `extra["headers"]` 合并（小写规范化＋null 删除，pi providerHeadersToRecord 逐字）——不补则头事件对这两条车道是空操作。
- 【风险/代价】窄差 5 条（map/04 D 域批 7 窄差表 D31–D35，均内部/边缘）：D31 类型不符提前失败、D32 google 载体 record、D33 头集少 pi 的 auth.headers/显式头/attribution 三层（attribution 新登记 **B253**）、D34 null 抑制 SDK 默认头不可达、D35 响应头名大小写。账面：D-17（w3）/D-18/D-19/D-20 四行翻转 **+5.5**；D 域 66.0→71.5/83＝**86.1%**、模块 283.5→289.0/496≈**58.3%**、全仓 948.5→954.0/1381≈**69.1%**；w3 层 #5 翻（D 域 w3 欠账清零，map/04 权重 3 表 #6/#8 批 5 滞后一并修正）。[均内部/边缘]
- 触发条件：无（本族已闭）。出处：docs/17 §3–§6。

### map/04-批 coding-agent-8（docs/18）— tree 两扩展事件＋TUI 摘要导航流（B245）

- 【场景】D 域仅剩的 tree 两事件开放项：pi 的扩展能在树导航前取消/供摘要/覆盖指令（`session_before_tree` 结果臂，types.ts:832-838/1488-1501）、在导航后观察（`session_tree`）；TUI 侧 `/tree` 与 `/fork` 共用 marker，`/tree` 实为 fork，pi 的「选 entry 后原地导航＋可选摘要」流（interactive-mode.ts:5560-5690）从未接线。另：旧图把希望挂在引擎钩 `onBeforeNavigation` 上，该钩是 java 早期自造、零调用点、装不下 pi 的 preparation 载荷。
- 【决定】2026-10-21 批 coding-agent-8（docs/18，T1 事件三件套→T2 navigateTree 重构→T3 TUI 流）：① **T1**——`TreePreparation`（entriesToSummarize 是**活列表**，与默认生成器共引用，pi :3995/4056 同构）/`ExtensionTreeSummary`/`SessionBeforeTreeResult`＋两事件变体＋两 spec 令牌＋`SessionEventFirer` 两方法（通用 emit 逐字：cancel 短路、最后发言者赢、逐处理器 catch⇒emitError⇒继续、hasHandlers 快走）；② **T2**——navigateTree 逐 pi :3964-4156 全序：模型门（错文逐字）、无条件先收集、token 事件前建、before_tree 三臂（扩展摘要仅在用户本来要摘要时生效，三覆盖不受门限）、`fromHook`＝fromExtension 透传、session_tree 载荷（newLeafId=导航后当前叶）、`TreeNavigation` 加 `aborted` 字段（pi 区分扩展取消与摘要中止）；**裁决①**——死引擎钩三件套删除（pi 无引擎层 tree 钩，事件由 agent-session 直发）；③ **T3**——`/fork` 与 `/tree` marker 拆分（`UI_FORK_SELECTOR` 新增）；`TreeNavigationFlow` 编排（当前叶 no-op 文案、skipPrompt 门、三选⇄自定义指令编辑器 Esc 回退链、worker 虚拟线程上 abort＋waitForIdle＋重查 isCompacting、指示器置/清成对、结局四分）；`TreeSelectorScreen` 导航模式（initialSelectedId 恢复选中）；`ChatScreen` 三 public 面（showBranchSummaryIndicator/showError 提 public/rebuildTranscript 参数形）；PiTuiApp overlay **先清位再 apply**（链式重开必要修复）；**裁决③**——Esc 换绑不移植（java Esc⇒abort() 首步即 abortBranchSummary，已与 pi 换绑后等价）。**闭环评审三修**（均 RED→GREEN）：④ `isCompacting()` 含分支摘要在飞（pi getter :1622-1630——门错文「or tree navigation」的互斥语义承重墙；prompt 路由门同改道，摘要窗口期重入导航/prompt 起跑的 NPE＋撕转录消除）；⑤ `hasExtensionSummary` 生成器门＋pi truthiness（summary 对象非 null 即跳过默认生成器不看文本；空串文本不建 entry、空串 label 不贴，pi `if (summaryText)`/`if (label)`）；⑥ generate 前 abort 预检（token 已中止不发 LLM 调用）。
- 【风险/代价】窄差 7 条（docs/18 §5 D36–D40＋闭环新登 D41/D42）：D36 `_resolveIdleWaitIfIdle` 无对位（java waitForIdle 是车道 run 级，树导航不是 run）；D37 树选择器仍是 lane 列表（pi 1,435 行 entry 树本体＝map/05 S12 存疑项，存量约束）；D38 `flushCompactionQueue` 在 java 恒 no-op（缓冲由 CompactionEnd 重放，导航时必空）；D39 三选/编辑器 SelectList/EditorComponent 弱形状（行文案逐字）[模型可见 UI 形态]；D40 重开恢复选中＝lane 行定位（pi 恢复 entry 节点）；D41 `restoreQueuedMessagesToEditor` 未移植（运行中导航前被 abort 的排队消息不回填编辑器，无既有取用面）；D42 摘要窗口期 followUp 入压缩缓冲、CompactionEnd 才重放（`isCompacting` 语义修复④的连带——滞留至下次压缩；放行起跑新 run 撕转录的风险更大）。[D39 模型可见；D37 存量约束；D41/D42 边缘；余无影响]
- 触发条件：D37 随树选择器本体批次（S12）收编；D41/D42 随排队消息治理立项时重裁。出处：docs/18 §3–§6＋§9 闭环回填。

### map/04-批 coding-agent-9（docs/19）— 树选择器本体（D37 收编＝map/05 S12＋S13）

- 【场景】批 8 留下的窄差 D37：`/tree` 的选择器本体。java 列 lane 名＋leafId（112 行平表），pi 是 entry 树选择器（`tree-selector.ts` 1,435 行：树形缩进/连接符/gutter、折叠、五种过滤、搜索、标签编辑、复制、水平视口）。取证另撞出三处：① java 的 label 事实表**无时间戳**（pi `[+label time]` 开关的数据源）；② RPC/Web `get_tree` 建在 harness 单车道转录上且 label 恒 null（pi 数据源即 `sessionManager.getTree()` 全分支）；③ pi `/fork` 用的是**用户消息选择器**而非树选择器（本体差异，另立 B254）。
- 【决定】2026-10-21/22 批 coding-agent-9（docs/19，T1 数据面→T2 纯状态机→T3 渲染层→T4 overlay→T5 接线）：① **裁决①**——label/session_info 不进 `Entry` ADT（java 事实旁表 wire 与 pi 原生互通，「all」模式少两种行登记 D43）；② **裁决②**——`SessionState` 加 `labelTimestamps`（pi `_buildIndex` 最新一条赢、清除两表同删），全链转发到 `AgentSession.getTree()`；**顺带修真**分支复制重发保留原 label 时间戳（pi `createBranchedSession:1694-1712`——SessionState 与 sqlite 原生表拷贝**双点**同修，一致性测试先在 sqlite 抓红）；③ **裁决③**——`AgentSession.getTree()`（`SessionTreeBuilder` 逐字：两轮链接、孤儿/自指/父缺失 ⇒ 根、子表时间戳升序、迭代防爆栈）落成后 RPC/Web `get_tree` 改走它，私有构建器删除；④ **T2/T3/T4**——`TreeListModel`（pi TreeList 状态机逐字：flattenTree/recalculateVisualStructure、五过滤＋空白分词 AND 搜索、折叠传染、环绕导航、段跳；`TreeStructure` 拆出保 500 行）＋`TreeListRenderer`（span 行＋水平视口按列切片，裁决⑦ 不接 ANSI；语义色近似值常量）＋`TreeSelectorOverlay`（pi handleInput 分派序逐字；label 编辑臂换入 EditorComponent）；⑤ **T5**——`TreeNavigationFlow.openTree` 改道（空树不开、filterMode 初值读 settings 不回写、copy 三结局文案逐字）、`PiTuiApp` ResizeEvent 跟踪（裁决⑧ 初值 80×24）、`ClipboardSupport` 仅 AWT（裁决⑤，D44）、`KeybindingsManager` 多键＋12 tree 动作。**treeLeafId 折算**（本批撞出必裁）：java 每回合 usage 记账行链上车道（D6 方言），harness 叶恒为隐藏 usage 条目——不折算则 /tree 初始 Enter 弹三选而非 "Already at this point"（与 pi 主流 UX 分叉），语义叶取最近非 usage 条目。**闭环评审三修**（均 RED→GREEN）：⑥ **C1**——tree 键与全局同表致 hash 序污染 `resolve()`（ctrl+l/u/a 主屏失效、ctrl+d 靠运气；设计裁决④「resolve 任一命中即返」被证伪）⇒ 全局/tree 两表分离（resolve 只见 11 全局、matches 两表皆查，pi 全局 handler 按动作查同形）；⑦ **I1**——TamboUI 不报字母 SHIFT 修饰键，shift+l/t 真实输入永不可达 ⇒ `toStroke` 落 pi legacy 规则（大写字符即 shift+letter）；⑧ **I2**——`--no-session` 下 label 提交异常进渲染线程 ⇒ flow 回调 try/catch 转 showError。
- 【风险/代价】窄差 5 条新登（docs/19 §5 D43–D46＋闭环评审 M1 登 D48；D47 条件登记**作废**——sqlite facts 表自带 timestamp 列）：D43 「all」模式无 label/session_info 条目行（labeled-only 过滤/标签显示/编辑/时间戳均不受影响）；D44 剪贴板仅 AWT（无平台命令/OSC 52 降级链，headless 报错）；D45 tree 键用户覆写不支持（keybindings.json 未接线，既有全局约束）；D46 帮助行键名静态取默认（含回环键只画 ctrl+o，评审 M4 并入）；D48 RPC `get_tree` 节点缺 `labelTimestamp`（pi 线有；web 面已带，RPC 浅映射是设计 sanction）[D43/D44/D48 边缘；D45/D46 内部]。**D37/D40 销号**（本体落地；恢复选中粒度＝entry 节点，`findNearestVisibleIndex(initialSelectedId ?? currentLeafId)` 逐字）。**B254 新登**（§3.4）。账面：map/05 S12 存疑→对齐 +1.0（权重 2）、S13 缺失→对齐 +1.0、map/04 #3 `/tree` 存疑→对齐 +0.5 ⇒ 模块 283.5→**286.0/487 ≈ 58.7%**、全仓 955.5→**958.0/1381 ≈ 69.4%**；D 域不动（73.0/83 ＝ 88.0%）。
- 触发条件：B254 与 map/05 S20 合包（docs/20 §3 批 10）；M2（sqlite getTree N+1 查询）随 sqlite 性能立项时重裁。出处：docs/19 §3–§6＋§9 闭环回填。

### map/04-批 coding-agent-10（docs/21）— `/fork`＝user-message 选择器（B254）＋S20 dequeue 链

- 【场景】批 9 登记的 B254：pi `/fork` 不走树选择器，走 **user-message 选择器**（`user-message-selector.ts` 155 行——列全部 user 消息、初始选中最后一条、选中即 fork＋编辑器回填选中消息文本）；同文件的 **S20**（map/05）排队消息取回（dequeue：清空引擎 steer/followUp 两队列＋压缩缓冲，合并回填编辑器）在 java 只有键常量未接线。两处 RPC 顺带欠账：`fork` 的 `text` 载荷恒 `""`、`get_fork_messages` 数据源是单车道转录且文本 `"\n"` 分隔（pi 两处均空串分隔）。
- 【决定】2026-10-22 批 coding-agent-10（docs/21，T1 数据面→T2 选择器本体→T3 接线→T4 dequeue 链→T5 D41 重裁）：① **T1**——`getUserMessagesForForking` 收平为 `ForkMessage{entryId,text}`（裁决①），数据源改 `accumulatedEntries()`（持久会话**全分支**提交序＝pi `sessionManager.getEntries()` 对位）、text 块空串拼接、空文本滤除；RPC `fork` 成功臂带 `selectedText`（裁决②：fork 前查表，签名不动）、`get_fork_messages` 直用会话表，`forkMessageText` 删除；② **T2**——`UserMessageSelectorScreen`（pi `UserMessageList`＋容器层逐字：环绕导航、可视窗 10 条滑动公式、单行化＋列宽截断 `...`、序位行、滚动指示、DynamicBorder 行）；③ **T3**——`/fork` 改道（空表预检 "No messages to fork from"、选中 fork＋switchSession＋`setEditorText(selectedText)`＋"Forked to new session"）、**`TreeSelectorScreen` 删除**（裁决⑧：/tree、/fork 改道后零用户）；④ **T4**——`InteractiveMode.restoreQueuedMessages`（pi `clearAllQueues` 逐字序：引擎 steer、缓冲 steer、引擎 followUp、缓冲 followUp，`"\n\n"` 连接，返回 `RestoredQueue{text,count}`）＋DEQUEUE 臂（文案逐字＋复数形）＋`PendingQueueView` hint 行（`↳ {key} to edit all queued messages`，键名随绑定表取大写形——pi `getAppKeyDisplay` 对位）＋dequeue 键平台规则（裁决③：Windows/WSL `alt+q`、其余 `alt+up`，`useWindowsKeybindings` 逐字）；⑤ **T5（D41 重裁，裁决④）**——Esc 流式臂与树导航臂各 ~10 行落地（restore 先行于 abort；合并式收 `ChatScreen.restoreToEditor` 三臂共用，裁决⑦）；扩展 `ctx.abort()` 两臂 java 无挂点（E5 域），**D41 改述保留登记**（触发条件随 E5 批次）；D42 维持登记不动。
- 【风险/代价】窄差 3 条新登（docs/21 §5）：**D49** `app.message.followUp` 键平台分叉未落（pi Windows/WSL 是 `ctrl+q`，java 恒 `alt+enter`——换键动肌肉记忆，单独裁决）[用户可见，边缘]；**D50** `/fork <name>` 带参臂（`forkCopy`）是 java 独有便捷路（pi `/fork` 精确匹配无参）[用户可见，边缘]；**D51** RPC `fork` 对非 user 消息 entry 不报错（pi position "before" 校验抛 `"Invalid entry ID for forking"`）[边缘]。**B254 销号**；map/04 #15 `/fork` 存疑→对齐（+1.0）＋E3-8 同能力行同翻（+1.0，设计对账表漏列、闭环逐行实算补）；map/05 S20 缺失→对齐（+1.0）＋K11 同能力行同翻（+1.0，同前）；R28 不翻（hint 行＋取回流补齐，「流式 bash/工具组件挂该区」仍缺，证据列更新）。账面：模块 286.0→**288.0/487 ≈ 59.1%**（原始口径 290.5→292.5/496 ≈ 59.0%）、tui/web 110.5→**112.5/245 ≈ 45.9%**、全仓 958.0→**962.0/1381 ≈ 69.7%**；D 域不动（73.0/83＝88.0%）。**B66 核账**（死键 6→4：DEQUEUE 本批接、MODEL_CYCLE ai 批 3b 早接）。E1 涨到 2,083（+23）、E2 667（−7）、E6 615（+56，设计预估 +~50）。
- 触发条件：D41 残余（扩展 abortHandler 的 restore）随 E5 批次；D49 随键位平台规则统一裁决时。出处：docs/21 §3–§6＋§9 闭环回填。

### map/04-批 coding-agent-11（docs/22）— settings 键批量第一批（E2 域：核销×2＋消费链×5＋声明面×14）

- 【场景】docs/20 §3 批 11 行「settings 键批量（E2 域 22 缺失＋11 存疑）」的前半：核销更正＋coding-agent 模块内可闭环的消费链＋纯声明面；TUI 行为键与包管理器两键、`theme` 留「消费点批」。取证发现 map/04 两行账面滞后（B1/B128 早已落地未翻行）。
- 【决定】2026-10-22 批 coding-agent-11（docs/22，T1→T7）：① **T1 核销**（零代码）——E2-38 `thinkingBudgets`（字段 `Settings:91`＋访问器＋消费点 `AgentSession:614`，B128 已落）、E2-11 `branchSummary`（字段 `Settings:42`＋访问器＋消费点 `AgentSession:1231`＋`TreeNavigationFlow:169`，B1 已销）⇒ 翻对齐；② **T2 `modelThinkingLevels`**——字段＋四访问器（pi :768-793 对位）＋三消费点：`SessionSetup.thinkingLevelFor` 第四臂（**B15-残留-5 销号**，javadoc 自述同删）、`AgentSession.setModel` 包装＋`thinkingLevelForModelSwitch` 三臂（pi :2477-2496/:2669-2681；RPC/Web/TUI 切模型三点改道）、`cycleModelForward` 走解析链；③ **T3 `httpProxy`**——`HttpProxySupport`（pi applyHttpProxySettings 对位；JVM 不可写进程 env ⇒ 通道换 `http(s).proxyHost/Port` 系统属性，**裁决③**，`??=` 语义保持）＋应用点 `SettingsManager.load` 唯一漏斗（读全局域；`withStorage` 测试道不应用）；④ **T4 `httpIdleTimeoutMs`**——字段（number|"disabled" 联合边界）＋`TimeoutSettings` 解析（pi :5/20-36＋:147-157 逐字）＋`SettingsNetworkAccessors`＋消费点 `DefaultProviders.apiOptions`（pi sdk.ts:344-354 链序：providerRetry ?? 有效值，0⇒MAX_INT，缺席 300s——**java 自造 120s 兜底删除**）；⑤ **T5 `deviceId`**——`SettingsInstallAccessors.getOrCreateDeviceId`（只读写全局域／生成即 flush〔**裁决⑥**〕／幂等）＋`AuthCommand` ChatGPT 臂弃 `LoginHints.cli()`（每次随机）改持久安装 ID（pi interactive-mode.ts:6331 对位，**裁决⑤**）；⑥ **T6 `blockImages`**——`ImageBlockFilter`（pi sdk.ts:296-329 逐字：user/toolResult 两臂、占位文案、连续去重、URL 图片不滤）＋`EngineHookBridge.transformContext` 尾部殿后（动态现读；桥加 `BooleanSupplier` 参，4 参 install 重载默认 off 不动存量测试）＋`--no-extensions` 裸注册过滤钩（pi 过滤独立于扩展面——**执行裁决**：设计未虑及 no-extensions 早退）；压缩/分支摘要/bug 报告不滤（pi 同，非窄差）；⑦ **T7 声明面 14 键**——字段块（pi 行号逐键钉注）＋`Warnings` record＋三域访问器（**裁决⑧**拆分：`SettingsNetworkAccessors`/`SettingsInstallAccessors`/`SettingsUiPrefsAccessors`，`SettingsManager` 加 `network()`/`install()`/`uiPrefs()` 三入口）；**裁决②通过**：E2-45 `codemode` 出分母（R5 面外 B161）。
- 【风险/代价】窄差 3 条新登（docs/22 §5）：**D52** httpProxy 通道＝系统属性（pi 写进程 env 由 undici EnvHttpProxyAgent 全局生效；java 由 jdk 默认 ProxySelector 生效。URL 内嵌凭证不解析、`nonProxyHosts` 不支持、socks scheme 不支持；闭环评审补记两边缘：https scheme 缺省端口取 80（pi/Node URL 解析取 443）、env 优先级按组判定〔任一 HTTP_PROXY/HTTPS_PROXY 在场即整体跳过〕（pi 两变量独立 `??=`））[内部]；**D53** httpIdleTimeoutMs 单臂对齐（只接 request timeout 臂；pi 另有 `configureHttpDispatcher` undici 全局 dispatcher 面 java 无对位——jdk 无全局 dispatcher）[内部]；**D54** 切模型「全无级别配置」不写思考档 entry（pi 回落 DEFAULT 并 `setThinkingLevel` 写 entry；java 继承当前档不写，裁决⑦）[会话文件，边缘]。**B15-残留-5 销号**。账面：模块 288.0→**303.5/485 ≈ 62.6%**（原始口径 292.5→308.0/494 ≈ 62.3%；E2-45 出分母 487→485/496→494/1381→1379）、全仓 962.0→**977.5/1379 ≈ 70.9%**；E2 域 52.0→67.5/84＝**80.4%**；D 域不动（88.0%）。行为变化注记：provider 请求超时兜底 120s⇒300s（pi 对齐方向，changelog 级）。
- 触发条件：声明面 14 键的消费点（changelog 流／analytics／cache-warmer／websocket 传输／TUI 硬件光标·全屏面）随「settings 消费点批」落地后逐行翻对齐；`setModelThinkingLevel`/`removeModelThinkingLevel` 的 TUI 入口同批。出处：docs/22 §3–§6＋§9 闭环回填。

### map/04-批 coding-agent-12（docs/23）— settings 消费点批（E2 域核销×3＋TUI 行为键×5＋声明面消费点×7＋setter TUI 入口）

- 【场景】批 11 留了「消费点在 TUI 域」的键：TUI 行为键 8 个与批 11 声明面 14 键的消费点，外加 `setModelThinkingLevel` 的 TUI 入口（批 11 T2 留白）。E2 行的判定口径「TUI 模块消费者不计入本图」使这些键永远无法翻行——本批整体是跨模块接线，口径本身要修。
- 【决定】2026-10-22 批 coding-agent-12（docs/23，T1→T14）：① **裁决①（E2 判定口径修订）**——「非零消费者」计入 TUI 模块消费点（map/04 行与 map/05 行是不同单元，不重复计分；先例 E2-11 证据引 `TreeNavigationFlow:169`），map/04 E2 表头注记同步修订；② **核销×3（零代码）**——E2-37 `treeFilterMode`（裁决②：消费点批 9 已随树选择器落地，账面滞后）；E2-23/E2-24（**裁决③口径先例：pi 侧零消费 ⇒ 声明面即全量**——pi 运行时无 analytics 客户端/上报端点，docs/settings.md:168 自述仅首次设置向导用）；③ **TUI 行为键×5**——T2 `doubleEscapeAction`（accessor 默认回落 `"tree"`＋空编辑器双击 Esc 500ms 窗〔pi :3067-3097〕，流式中断不计双击）；T3 `externalEditor`（accessor 回落链逐字＋`ExternalEditorSupport`〔tmpdir/pi-editor-\*/prompt.md、split(" ") 简单拆、BOM＋尾换行规范化〕＋Ctrl+G alt 屏退出/重进接线；**B66 核账 4→3**）；T4 `editorPaddingX`（uiPrefs accessor clamp 0..3＋编辑器双侧留白 live-read）；T5 `autocompleteMaxVisible`（**裁决④默认 8⇒5**＋`SlashCompleter` maxVisible 注入）；T6 `outputPad`（accessor 逐字〔仅显式 0⇒0〕＋`ChatMessage.Padded` 烘焙变体〔append 时定值〕＋`MessageBubble` indent＋flatten 宽 −2×pad）；④ **声明面消费点×7**——T7 changelog 流（`ChangelogSupport` 逐字 parse/compareVersions/getNewEntries＋`forDisplay` 三臂＋内置 `resources/CHANGELOG.md`〔**裁决⑦** 数据各自维护非窄差〕＋启动两臂〔折叠一行逐字/What's New 全文〕＋`/changelog` 全量 reverse 不回写；`normalizeChangelogLinks` 不移植〔首发无相对链接〕）；T9 `showHardwareCursor`（`createRunner` 参数化 `hideCursor(!show)`）；T10 `fullscreenExitOutput`（退出两臂：transcript 默认 `TranscriptDump` 倾倒〔复用 ScrollbackTranscript 打印面〕／resume-hint 只留提示＋统一恢复提示〔仅已持久化会话〕）；T11 `fullscreenScrollbar`（三态：hidden 真实落地〔不让列/命中恒 false〕、auto/always 同形常驻〔**裁决⑧**〕）；T12 `fullscreenWheelScrollLines`（**裁决⑥**：数字臂接 `ScrollConfig` 固定行数、auto 维持既有 Codex 加速不新造）；T13 `warnings.anthropicExtraUsage`（`AnthropicExtraUsageWarning`＋四时机接线〔init/模型选择/Ctrl+P/登录〕＋每会话一次；种类判据退化为 stored-oauth source 标记＋`sk-ant-oat` 值前缀双判据）；⑤ **T14**——`ModelThinkingLevelScreen` 两级子菜单（pi settings-selector.ts:607-700 对位：模型步当前置顶＋override 摘要、级别步 reasoning 门＋clear 项；提交回调 pi :5009-5019 当前模型即时生效/回退全局默认 ?? "off"）＋`SettingsScreen` 实例化重构（getter/setter 闭包跨三访问器面）＋子菜单检出 `pollModelThinkingRequest`；⑥ **不做（§4 重排/窄差）**——E2-32 terminal 七键（D56）、E2-43 markdown（D57＋随 R1 激活批，计入拍板变量③）、E2-14/E2-49 cache 两键（**「cache-warmer 子系统批」** docs/20 §3 插行，pi cache-warmer.ts 449 行是子系统不是键接线）、E2-50（D58 无 WS 传输）、E2-22（**裁决⑤** install ping 不移植〔pi.dev 是 pi 自有端点，复刻发送会污染其遥测〕＝D55＋归因头随 B253）、E2-54（D59 挂 S2a-d）。
- 【风险/代价】窄差 10 条新登（docs/23 §5）：**D55** install telemetry ping 不移植（`PI_OFFLINE`/`PI_TELEMETRY` 门与归因头随 B253）[内部]；**D56** `terminal` 七键消费面无对位（能力覆盖/OSC 9;4/inline 图片/clearOnShrink；可移植子集随 TUI 屏域拍板）[用户可见·终端集成]；**D57** `markdown.mermaid` 无 grok-mermaid 对位（pi 渲染 Unicode 字符画；`codeBlockIndent` 随 R1 激活批）[用户可见]；**D58** `websocketConnectTimeoutMs` 无 WS 传输对位[内部]；**D59** `fullscreenCopyOnSelect` 依赖 alt-screen 选区子系统（S2a-d 未排期）[用户可见]；**D60** 滚轮 auto 臂＝既有 Codex 有界加速（非 pi 速度模型；数字臂对齐，同 K38）[用户可见·手感]；**D61** 滚动条 auto/always 同形常驻（pi auto＝滚动后短暂显示）[用户可见]；**D62** `outputPad` 改值不回溯已渲染条目（pi 遍历容器热更；java 新条目用新值）[内部]；**D63** changelog 渲染为纯文本（Markdown 组件随 R1 批）[用户可见]；**D64** resume-hint 文案按 java CLI 能力成形（`pi-java -r` 指引，`-r` 选择器随批 13）[用户可见]。行为变化注记（changelog 级）：**双击 Esc 默认开树选择器**（此前无行为）、**fullscreen 退出默认倾倒 transcript**（此前静默）、autocompleteMaxVisible 默认 8⇒5——均 pi 默认同向。账面：模块 303.5→**311.0/485 ≈ 64.1%**（原始口径 308.0→315.5/494 ≈ 63.9%；未加权 145→160/289 ≈ 55.4%）、全仓 977.5→**985.0/1379 ≈ 71.4%**；E2 域 67.5→**75.0/84 ≈ 89.3%**（对齐 29→44/存疑 23→8）；D 域不动（88.0%）；w3 73.9% 不动。E6 涨到 793（+178）。**B15-残留系无新增**。
- 触发条件：~~cache-warmer 两键（E2-14/E2-49＋R24＋5 通知门）随「cache-warmer 子系统批」~~（**批 13 已收**，2026-10-23）；markdown 激活（R1 联动 E2-43）计入拍板变量③；D49 随键位平台规则统一裁决时；外部编辑器的 TamboUI 状态错位风险（继承 IO 与 runner 渲染交错由 alt 屏序列隔离——实测未见异常，光标落位属 TamboUI 后端行为域）如出现实害再裁窄差。出处：docs/23 §3–§6＋§9 闭环回填。

### map/04-批 coding-agent-13（docs/24）— cache-warmer 子系统批（D 域 #16＋E2-49＋E2-14＋map/05 R24）

- 【场景】批 12 裁决⑨插行：pi 的缓存预热是**子系统**而非键接线——`core/cache-warmer.ts` 449 行状态机（定时 `maxTokens=1` 重放保 prompt 缓存不失效，TTL 到期前刷新；经济闸门 $0.05——期望节省＝续聊概率 × miss 代价 − 刷新成本，达不到不刷）＋`core/cache-stats.ts` 175 行 miss 统计＋sdk.ts 装配＋agent-session 四面接线＋扩展 decide 钩（每次刷新前问扩展，可覆盖 warm/stop）＋TUI 五处 `getShowCacheMissNotices()` 通知门。本批全量移植并接通 `cacheWarming`（E2-49）／`showCacheMissNotices`（E2-14）两键与 R24（缓存预热用量行）。
- 【决定】① **T1 本体逐字**：`CacheWarmer`（三纯函数/双安全窗/deadline/经济闸门/停止理由全集十四串）＋`CacheWarming` DTO 族＋`CacheWarmingFormatting`＋`WarmScheduler` 调度缝（裁决④：生产＝守护调度线程＋虚拟线程执行体，测试＝手动时钟队列翻译 pi `vi.useFakeTimers`）；② **T2 `CacheStats`**（噪声地板 1024/sticky reportedCache/compaction·branch_summary 复位/cache_warm 条目充 prev）；③ **T3 decide 钩**（D 域 #16：`CacheWarmingBridge` last-override-wins＋逐处理器异常 emitError 后继续〔pi runner.ts:1121-1142 逐字〕，裁决⑦晚绑定 install）；④ **T4/T5 harness 三缝**（`getMessages` 活视图〔捕获须快照——见评审 C1〕/`appendDeferredLaneEntry` 双态〔pi `_appendEntry` 对位〕/`addTokens` footer 计入）；⑤ **T6 装配**（**裁决①分包门**：pi 的「仅会话请求」sessionId 比较无法逐字抄〔java 车道 sessionId 恒空〕⇒ warming wrapper 只包 harness 注入点，摘要/压缩保持 recordingStreamFn，行为等价）＋**T7 暖路径请求面**（maxRetries 钉 0/跳三钩/保头变换，裁决③）＋**T8 四面接线**（settled 双发射点/dispose cancel/setCacheWarmingMode＋onModeChanged/cacheWarmingStatus）；⑥ **T9–T12 TUI**：设置页两行（Cache warming 走会话入口〔pi :5005-5008〕／Cache miss notices＋检出 rebuild）＋暖用量行（文案逐字〔原自造 "Cache warming: N tokens" 删〕＋门＋即时臂＋onEntry 去重）＋miss 通知双臂（`CacheMissTracker` 增量形〔裁决⑤〕＋**判定后推进 prev**〔评审 I1——pi 整表 scan 看得到本 run 已落 turn〕＋同实例去重）＋`/session` Cache Warming 段；⑦ **评审抓回三修**——**C1** isCurrent 活视图前缀比对恒真（`lane.messages` final＋就地 clear+addAll，设计稿「整表替换」前提错误；压缩/树导航后暖重放不停止、纯浪费计费）⇒ 捕获改 `List.copyOf` 快照＋三处 javadoc 修真＋装配测试；**I1** 即时臂 prev 冻结（多 turn run 的 idleMs/missedTokens 系统性偏小）⇒ 判定后推进＋身份集防 run 末 feed 重复；**I2** `CacheWarmer` 571 行破规 ⇒ 拆 `CacheWarmingEconomics`（490/113）。
- 【风险/代价】窄差 4 条新登（docs/24 §5）：**D65** warm 中止通道＝关 in-flight `StreamIterator`（pi 用 AbortController 断 fetch；java StreamFn 契约无 signal——observable 行为一致：暖请求被打断、不记账）[内部]；**D66** 暖重放选项现读（pi 冻结捕获时刻的 requestOptions；java 暖路径每请求现读 settings——排期内改 timeout/retry 设置时 java 用新值，方向更安全）[内部]；**D67** E2-14 五门之二无载体（:4121 压缩成本通知随 B38 压缩重建批、:4151 thinking-drop 通知随 diagnostics 面批；有载体三门本批落地即翻对齐，裁决⑥）[用户可见·两条通知种类缺席]；**D68** `/session` 结构差（pi Cache Re-billed 行挂 Cost 块内；java /session 无 Cost 块，挂 Cache Warming 段尾——/session 全量对齐是独立题）[内部·展示结构]。行为变化注记（changelog 级）：**`cacheWarming` 默认 "streaming" 生效**——Anthropic 系模型在 agent 运行期间自动发送 `maxTokens=1` 缓存刷新（每笔一笔 cache-read 计费并落 `{"type":"usage","kind":"cache_warm"}` 条目〔pi 同形可跨读〕，footer token 计数含暖条目；此前 java 从不刷新，TTL 到期后下一请求全价重写）；设置页新增两行；`showCacheMissNotices=true` 时聊天区出现 miss 警告行与暖用量行（默认 false 不可见），`/session` 新增 Cache Warming 状态段（无条件显示）。评审 deferred minor×8 记台账（M1 rebuild 不清 lastMissNotified／M2 rebuild 只喂分支／M4 非 EntryObserver 模式双发〔既有同构〕／M5 缺 showStatus／M6 广播落盘序／M7 注释误导／M8 refreshing 窄窗／ChatScreen 722 行存量超限续胀）。账面：模块 311.0→**315.0/485 ≈ 64.9%**（原始口径 315.5→319.5/494 ≈ 64.7%；未加权 160→164/289 ≈ 56.7%）、全仓 985.0→**989.0/1379 ≈ 71.7%**；**D 域 73.0→75.0/83 ≈ 90.4%**（#16 缺失→对齐，缺失 6→5）；**E2 域 75.0→76.5/84 ≈ 91.1%**（对齐 44→46）；tui/web 45.9→**46.1%**（R24 对齐）；w3 73.9% 不动。新文件 8＋测试 7（CacheWarmerTest 8〔pi 七用例全翻译〕/CacheStatsTest 10/CacheWarmingBridgeTest 4/AgentHarnessCacheWarmingSeamsTest 4/DefaultProvidersWarmTest 4/AgentSessionCacheWarmingTest 7/SessionCommandsCacheWarmingTest 5＋tui 三文件 12）；PiV3UsageEntryTest 补反向钉（java 写 cache_warm 行⇒pi 同形可跨读）。
- 触发条件：D67 两门随各自载体批次收（已挂 docs/20 §3 触发条件行）；M1–M8 deferred minor 如需收编入批由用户拍板。~~环境性既有红：`ModelsJsonOverrideWireTest`×2~~（2026-10-23 人工核 `~/.pi-java/auth.json` 后复测 3/3 绿——凭据修复，**已消除**，与本批无关的既有环境性问题关闭。**批 14 更正：该「凭据修复」系误因——真因是 auth 三存储静态冻结 user.home，批 14 闭环已修真，见批 14 段。**）。出处：docs/24 §3–§8＋§9 闭环回填。

### map/04-批 coding-agent-14（docs/25）— CLI 参数尾（A 域）＋`-r` 会话选择器

- 【场景】A 域（CLI 参数）剩 13 行收尾＋`-r` 交互会话选择器（pi 1,294 行域：`session-picker.ts`＋`session-selector.ts` 1,045 行＋`session-selector-search.ts`）。java 侧 `-r` 裸敲抛栈、会话解析序与 pi 不符（continue 最先、无校验两臂、错误面是栈 trace）、`-e` 零消费者、`--use-theme`/`--` 未落地、`--no-session` web 臂悬空（B54）。
- 【决定】2026-10-23 批 coding-agent-14（docs/25，裁决①–⑦全拍板）：① **裁决①**——选择器触发缝＝coding-agent SPI（`SessionSelectorProvider`）＋tui ServiceLoader 注册（pi `createSessionManager` 对全 mode 生效，java 反向依赖不可 ⇒ print/json/rpc/interactive 四模式同获）；② **裁决②**——错误面统一 `SessionResolutionExit`＋Main 收口（pi `console.error("Error: …") exit 1`／`console.log(…) exit 0` 对位，废 `IllegalStateException` 栈 trace）；③ **裁决③**——`-c` 空臂按 pi 静默新建（session-manager.ts:1800；持久/内存两臂，`B255` 即登即销）；④ **裁决④**——`--offline` 判对齐：java 启动网络操作全集＝模型目录刷新（已门控），pi 其余 `PI_OFFLINE` 消费点在 java 无对应子系统（批 12 裁决③「pi 零消费⇒声明面即全量」同族）；⑤ **裁决⑤**——web 路径 `--no-session` 赢（`B54` 销号；ephemeral 是更安全一侧，与 CLI 臂同语义）；⑥ **裁决⑥**——`tui.select.*` 不入键位表，沿批 10 KeyEvent-helper 先例（`app.session.*` 六键入 overlay 表，批 9 C1 同构）；⑦ **裁决⑦**——rename/当前会话守卫按 pi picker 语境留白（结构同形、UI 不移植，随交互选择器批收编 S11）。T1 `FuzzyMatch` port → T2 `SessionSelectorSearch` → T3 键位表（K16）→ T5 `RichSessionInfo.parentSessionPath` → T4 `RepositoryHandle` 四口＋sqlite 臂拆文件（E3-9）→ T6–T9 会话解析 pi 全序（fork→session→resume→continue→sessionId→create）＋校验两臂＋全局臂/session-id/fork 查重 → T10 选择器本体七件 → T11 SPI＋启动壳 → T12 `--` 预分流 → T13 `--export` 位置输出 → T16 `--use-theme` → T14 web 臂 → T15 `-e` 显式 JAR（`-ne` 旁路）→ T17 A-17 核销。
- 【风险/代价】窄差 10 条（docs/25 §5）：**D69** 删除只有 unlink 臂（pi 先试 trash CLI；"Session moved to trash" 文案不可达）[边缘·用户可见]；**D70** `-r` 经 SPI 提供，tui 不在 classpath 报 `--resume requires the pi-java-tui module on the classpath`（pi 恒打包 TUI）[边缘·用户可见]；**D71** 参数校验诊断出口沿用 `error: <msg>`＋exit 2（pi 是 `Error: <msg>`＋exit 1）[用户可见·文案]；**D72** rename 面板 UI 不移植（picker 臂 pi 无生产者）[内部]；**D73** `tui.select.*`/`tui.input.tab` 直配 KeyEvent helper 不入键位表（自定义键位落地前不可观察）[内部]；**D74** sqlite 后端选择器列表退化（无富扫描——java 自有后端面）[边缘·内部]；**D75** `re:` 非法正则错误文案是 `PatternSyntaxException` 形（pi V8 形；行为同）[用户可见·文案]；**D76** ctrl+backspace 在不上报该组合的终端是死键（pi 注释同病）[边缘·内部]；**D77** `--export` 默认输出名 `pi-java-session-*`（pi `pi-session-*`，B213 品牌族）[用户可见·文案]；**D78** `-e` 只收 JAR 路径（pi 另收 `builtin:<name>`，java 内置扩展恒 ServiceLoader）[边缘·用户可见]。行为变化注记（changelog 级）：**`-r` 裸敲弹全屏会话选择器**（此前抛 `Session not found: null` 栈 trace）、**`-c` 空库静默新建会话**（此前报错）、`--session` 跨项目命中需 `[y/N]` 确认 fork（此前直挂）、`--fork`+`--session-id` 目标撞 id 报错 exit 1、`--session-id` 缺 ⇒ warning＋同 id 新建（此前丢 id）、`-e` 显式扩展 JAR 加载（此前零消费）、`--` 后 `-foo` 进 messages（此前误判 Unknown option）。账面：map/04 **A 域 11 行翻动**（+10.5 ⇒ 60.5/62 ≈ **97.6%**，余 A-29 重排批 20、A-43 重排批 15）＋**E3 域 7 行翻动**（+8.5 ⇒ 37.0/40 ≈ **92.5%**：设计内 5 行＋**E3-19 对账表漏列同翻**〔B54，批 10 E3-8 先例〕＋**E3-24 批 13 漏列核销**〔cache-warmer 本体〕；余 E3-15/E3-23 各挂 B1/B30）；map/05 **K16 缺失→对齐**（+2.0）＋**K15 同翻**（+1.0，漏列同前）＋**S11 缺失→存疑**（+0.5，裁决⑦）。模块 315.0→**334.0/485 ≈ 68.9%**（未加权 56.7→60.7%；w3 73.9%/D 域 90.4%/E2 91.1% 不动）、tui/web 113.0→**116.5/245 ≈ 47.6%**、全仓 989.0→**1,011.5/1379 ≈ 73.3%**。**B54/B255 销号**；D69–D78 新登。**回归抓回两修**：`/hotkeys` 空绑定 NPE（pi `formatKeys([])`⇒`""` 对位——T3 四空绑定动作触发）、`-c` 旧夹具改钉 B255 新行为。**套件性红修真**：`ModelsJsonOverrideWireTest`×2 的批 13「环境性红」真因＝auth 三存储（`FileCredentialStore`/`OAuthCredentialStore`/`AuthProfileManager`）`static final STORE_PATH` 类初始化冻结 `user.home`——临时 home 夹具先触发类初始化即全 JVM 钉死（基线 291e002d 复跑证红、隔离跑证绿、修后 coding-agent 979/979 绿）。前存红新浮现：`TreeNavigationFlowTest.queuedMessagesRestoreToEditorBeforeAbort` 在本机确定性红（基线 291e002d 同点复跑同红 ⇒ 非本批回归；D41 队列恢复域，留待下批取证）。E1 涨到 2,341（+10）；`SessionSelectorComponent` 483／`PersistentSessionRepositories` 455／`SessionPersistence` 420（均近线未破）。
- 触发条件：裁决④——version-check/self-update/install-telemetry/package-update-check 任一子系统落地时必须同步接 offline 门；D72 rename 面板随交互选择器批（map/05 S10/S11 族）收编；D73 `tui.select.*` 在用户自定义键位面（K17）落地时回填键位表；D67 两门不变（随载体批次）。出处：docs/25 §3–§8＋§9 闭环回填。

### map/04-批 coding-agent-15（docs/26）— slash 命令尾（C 域）：`/bug` 故障上报＋crash-log＋存疑修

- 【场景】C 域（slash 命令）收尾三件事：① `/bug` 故障上报全链（pi 侧 ~670 行：TUI 流 298＋核心 375＋上传 37＋ZIP 71）；② crash-log 子系统（pi 170 行＋interactive-mode 集成点：崩溃落盘 `agentDir/crashes.json` 末 5 条 7 天有效、下次启动提示「Run /bug」、非重试错误 `/bug` 建议）；③ C 域存疑修——`/thinking` `/model` `/export` `/session` 四条补臂＋`/changelog` `/compact` 两条核销。
- 【决定】2026-10-23 批 coding-agent-15（docs/26，裁决①–⑫全拍板）：① **裁决①**——ZIP 用 JDK `ZipOutputStream` 不手卷 pi 的 71 行经典字节格式（消费方是解压器不是字节比较器）；② **裁决②**——扩展栈匹配降级为 JAR 文件名针（java 栈帧是类名非文件路径，pi 三分支不移植；命中低、零误报）；③ **裁决③**——死终端面（EIO/EPIPE/ENOTCONN/ENOTTY ⇒ exit 129）不做，JVM 无 tty errno；④ **裁决④**——R29 安装变更警告挂起（java 无 package.json 安装形态/自更新通道，`maybeSuggestBugReport` 的 install-change 门恒 false，挂 docs/20 §3 序 21）；⑤ **裁决⑤**——`/import` 失败修真为 pi fatal 形（`B256` 即登即销；file-not-found 保留 pi 非致命臂）；⑥ **裁决⑥**——`/thinking` 空参臂做 SelectList 简版选择器（persist 第二键/DEFAULT 标记属 S8 域不做）；⑦ **裁决⑦**——marker 不携载荷，`/bug` 的 hint 由 PiTuiApp 从原始输入行切（pi :3242-3245 对位）；⑧ **裁决⑧**——bug report 的 provider/model/extension 节给可得子集、不可得键省略（不造恒值）；⑨ **裁决⑨**——`redactUrl` 手写串解析（`java.net.URI` 不可变、无 WHATWG query 改写；嵌套 scheme 臂逐字）；⑩ **裁决⑩**——bug 上传恒匿名（java 无 Radius provider，token 缝预留）；⑪ **裁决⑪**——崩溃提示/`/bug` 建议走 dim 系统文本（不新增 Warning 色件）；⑫ **裁决⑫**——CrashLog 路径与环境每调现解析（批 14 静态冻结 `user.home` 教训，防回归）。T1 `CrashLog`/`CrashRecord` → T2 集成（启动提示/未捕获钩/`/import` fatal/`/bug` 建议＋`CrashIntegration` 文案件）→ T3 `BugReport` 核心（脱敏/收集/文件列表/尾选）＋T3b 摘要缝（`LlmSummaryGenerator` systemPrompt 参化＋`BugReportSummarizer` 四守卫逐字）→ T4 `SessionBranchSerializer`（pi.share trailing）→ T5 `ZipArchives` → T6 `BugReportUpload`（multipart/网关）→ T7 `/bug` TUI 流（三屏＋`BugReportFlow`＋命令注册）→ T8 `/thinking` → T9 `/model` 直设臂（`ModelExactMatch`＋15s 刷新回落）→ T10 `/export` 默认臂＋引号解析 → T11 `/session` 全段（`SessionStats`/`UsageCostBreakdown`，**D68 销号**——Cache Re-billed 搬入 Cost 段）→ T12 `/changelog` `/compact` 核销核验。
- 【风险/代价】窄差 10 条新登（docs/26 §5）：**D79** ZIP 字节格式差（JDK 产 vs pi 手卷——可解压等价，字节不可比）[内部]；**D80** 扩展栈匹配降级（JAR 文件名针 contains；pi 文件路径针三分支）[内部]；**D81** 死终端静默 exit 129 无 JVM 对位（SSH 断线时 java 按普通崩溃记 crash——多一条记录无害）[边缘]；**D82** bug report JSON schema 差（provider 节缺 authStatus/usingOAuth/registeredByExtension、model 节缺 input、extensionErrors 恒空——java 无 ModelRuntime 聚合面/扩展运行时元数据）[用户可见·上报内容]；**D83** 崩溃启动提示/`/bug` 建议走 dim 系统文本（pi 是 warning 色 ThemedText）[用户可见·色彩]；**D84** `/thinking` 空参选择器是 SelectList 简版（无 persist 第二键/DEFAULT 标记，S8 域）[用户可见]；**D85** bug 上传恒匿名（token 缝预留不接线）[内部]；**D86** `/model` 未命中开裸选择器（java ModelSelectorScreen 无搜索词预选缝）[用户可见·边缘]；**D87** 崩溃钩无 `killTrackedDetachedChildren` 对位（java bash 子进程清理由 JVM/工具层各自管）[内部]；**D88** `/bug` 输入屏无外部编辑器缝（pi ExtensionEditorComponent 带；java 组件级无）[用户可见·边缘]。行为变化注记（changelog 级）：**`/import` 失败从「报错继续」变「记 crash＋进程 exit 1」**（pi 语义本身，file-not-found 除外仍报错继续）、**`/export` 无参从报错改落 `session-<时间戳>.html`**（HTML 默认臂）、`/model <ref>` 直设生效（此前忽略参数恒开选择器）、**上次崩溃过 ⇒ 下次启动聊天区出现「Run /bug」提示**、**非重试 provider 错误后聊天区出现 `/bug` 建议行**（每进程一次）、`/session` 新增 Messages/Tokens/Cost 段（Cache Re-billed 移入 Cost 段）、`/thinking` 命令新增（空参选择器/有参直设）。**D68 销号**。账面：map/04 **C 域 7 行翻动**（+7.0 ⇒ 34.0/38 ≈ **89.5%**，余 C-1 设置选择器/C-5 scoped-models 选择器/C-8 Radius 分享/C-18 Radius 登录/C-23 reload〔批 16〕）；map/05 **S29/S30 缺失→对齐**（+2.0；R29 挂起注记）。模块 334.0→**341.0/485 ≈ 70.3%**、tui/web 116.5→**118.5/245 ≈ 48.4%**、全仓 1,011.5→**1,020.5/1379 ≈ 74.0%**；D 域 90.4%/E2 91.1%/w3 73.9% 不动。**回归回补三钉**：内置命令数 24⇒26、`/export` Usage 钉废、Cache Re-billed 位置钉（批 13 钉改 Cost 段内）。**排障注记**：真 home `settings.json` 的 `compaction.enabled=false` 会被不设 compaction 的测试继承（自动压缩不点火）——夹具种 settings 必须显式给 enabled 臂；`BugReportSummarizerTest` 的「No model selected」臂经公共装配够不到（harness 恒有模型解析结果），如实登记不造假态。新文件 15＋测试 14（CrashLogTest 15/CrashIntegrationTest 6/BugReportTest 14/BugReportSummarizerTest 6/SessionBranchSerializerTest 2/ZipArchivesTest 2/BugReportUploadTest 6/ImportFatalTest 3/ModelExactMatchTest 8/ThinkingCommandsTest 7/SessionStatsTest 5/SlashChangelogCompactTest 2/ExportCommandTest 6＋tui CrashNoticeTest 6/BugReportFlowTest 12/ThinkingLevelScreenTest 4；agent-core LlmHistorySummaryRequestTest +2）。
- 触发条件：D84 persist 第二键/DEFAULT 标记随 S8（thinking 选择器完整组件）批收编；D85 token 缝随 Radius 登录面（C-18 域）接线；R29 安装变更警告随自更新批（docs/20 §3 序 21）评估；D86 搜索词预选缝随 ModelSelectorScreen 增强时回填。出处：docs/26 §3–§8＋§9 闭环回填。

---

## 5. E 类 —— 结构债（仓库规则：单文件 ≤ 500 行）

| # | 文件 | 行数 |
|---|---|---:|---:|
| E1 | coding-agent …/core/AgentSession.java | **2,392**（2026-10-23 批 coding-agent-15 实测——批 15 +51：cwd()/summarizeForBugReport/appendCustomEntry/bugReportSummarizer 装配；批 14 +10；拆分宜搭下一 coding-agent 批） |
| E2 | coding-agent …/rpc/RpcDispatcher.java | 671（2026-10-22 批 coding-agent-11 实测——set_model 改道注释 +4） |
| E5 | ai …/catalog/ModelCompat.java | **762**（2026-10-11 B98 加 8 组件；converter 侧已拆出 CompletionAssistantMessage 保 500 内） |
| E6 | tui …/app/PiTuiApp.java | **912**（2026-10-23 批 coding-agent-15 实测——批 15 +101：崩溃启动提示/fatal 臂/bug 建议/thinking＋bug case/model auth 挂点；拆分宜搭下一 TUI 批） |
| E7 | tui …/screen/ChatScreen.java | **734**（2026-10-23 批 coding-agent-15 实测——批 15 +12：bugReportHook 缝；拆分宜搭下一 TUI 批，同 E6 一并发） |

（E3/E4 已销号——2026-10-18 批 agent-core-5 拆分：`PiLaneSink` 470＋`PiLaneRequestTelemetry` 131、`AgentHarness` 438＋`HarnessBootstrap` 231，行为零变化纯移动。）

均不与功能耦合，可随时拆，与功能包搭车推进。[全部内部，模型不可见]

---

## 6. F 类 —— 待你拍板

| # | 面 | 条目 | 要定的事 |
|---|---|---|---|
| B58 | 协议面 | openai provider 默认 wire 与 pi 不同 | pi 默认 openai-responses，Java 默认 openai-completions；「跟随 pi」还是「记为刻意偏差」 |

> ~~B54~~（`--no-session` 在 web 路径被完全忽略）：**2026-10-23 拍板销号**——`--no-session` 赢（ephemeral 是更安全一侧），批 coding-agent-14 落地（docs/25 裁决⑤，见 §4 批 14 段）。

---

## 7. 维护规则

1. 每个包在**自己的文档**里记设计、取证与实施记录（命题表 → 逐条验证 → 实施稿，用户审核后才写代码）。本台账只留条目索引。
2. 新条目：先在包文档立项（带证据/反向实验），再在本台账加一行，编号顺延不复用。
3. 结案/作废：从本台账移除该行，commit message 写明结论；历史在 git 可查。存活条目**永不改号**（代码注释引用编号）。
4. 「不做」类进 §4，按【场景/决定/风险】三段式写清裁决与触发条件；分类变动在包文档记录。
5. §1 计数由命令实测，不手记。
6. 包设计稿闭环后随过程文档清理删除（历史在 git），**编号不复用**；下一包号 `docs/12`。**2026-10-18 连续重编**：coding-agent 对齐清单 → `docs/08`、批 agent-core-4 → `docs/09`、批 agent-core-5 → `docs/10`、B236 coding-agent 批 1 → `docs/11`（重编前为 24/25/26，git 历史可查）。新号 08–11 与第五次清理删掉的旧稿（ai 批等）撞号：文中带「原」前缀的 `docs/NN` 引用一律指已删旧稿，不指现行文档。
7. 闭环口径的三条教训（来自 B186/B187/B200，已销号行的原文在 git 历史）：
   - 包闭环验证必须含 `mvn verify`（spotbugs 绑在 verify 阶段，只跑 test＋checkstyle 会漏）。
   - checkstyle 结果须写明错误、警告各几个，不接受一句「checkstyle 0」。
   - 「某时刻还没发生」的时序断言是运气（跟虚拟线程抢调度），要改确定性断言（如「监听器线程 ≠ 发送线程」）。
