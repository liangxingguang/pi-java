# 05 - 未结项台账（open items register）

> **这份文件解决一个问题**：「pi-java 还有什么没做完 / 没对齐」需要一处一眼看全的入口。
>
> **基准与口径**：
> - pi 锚点 `ea448f454`（**2026-10-11 迷你换锚**起，v1.1.0-22；此前 `200387122`）。
>   本次为均匀前进、无结构删除，换锚记录见 `docs/map/ANCHOR.md`；**七份地图（含新建 07-mcp）
>   已于 2026-10-12 对该锚点逐条重测完毕**（原 A16 销号，新差异登记为 B233–B238）。
> - **已闭环批次**（实施记录随过程文档清理删除，历史在 git）：ai 批 0–10（原 docs/08–19，
>   2026-10-12 批 0 裁决：B115/B117/B124 取证关单、#87 零代码对齐、11 单位出分母）、
>   agent-core 批 1–3（原 docs/20–22）、telemetry 批 1（原 docs/23，pi telemetry 系休眠契约、
>   map/01 100%）；换锚裁决依据在原 `docs/11-pi-reanchor-ruling.md`。
> - 判据是**行为**（`bin pi` 主流发布版可达的功能与表现），不是文档、API 形状或文件清单。
> - **R5 裁决**：只做 `bin pi` 主流发布版可达的功能；pi 的 experimental/ 内部子系统
>   （chord、pico3、durable、micro 等）不进对齐面，见 §4。
> - 本台账只留**未结项**与**有效裁决**。每条给「是什么 / 现状与出处」；详细论证、
>   探针与实施记录在 git 历史（已闭环的各包设计文档）。
> - **编号保持不变**：已结案/已作废的行从本文件移除（历史在 git：
>   `git log -- docs/04-open-items-register.md`），存活条目沿用原编号，使代码注释里的
>   「台账 Bnn」引用不断。编号不连续是正常的。

---

## 1. 汇总

| 类 | 含义 | 数量 | 谁能推进 |
|---|---|---:|---|
| **A** | 要**证据 / 清点**才能定案 | 见 §2 | 我（读 pi 源码 / 清点） |
| **B** | **功能缺口**（pi 有、pi-java 无） | 见 §3 | 我（先出设计文档） |
| **C** | 已裁决**不做 / 对齐面外**，带触发条件 | 见 §4 | 触发条件成立才动 |
| **E** | 结构债（>500 行文件） | 见 §5 | 我，与功能包搭车 |
| **F** | **待你拍板** | 见 §6 | **你** |

计数可复现：`grep -c '^| [AB][0-9]* |' docs/04-open-items-register.md`。

---

## 2. A 类 —— 要证据 / 清点才能定案

| # | 条目 | 挡在什么上 |
|---|---|---|
| A10 | **内置工具线程安全审计**（read/write/edit/bash/glob…） | pi 契约「工具必须可并发执行」；审计未开始 |
| A14 | shell 集成夹具无牙：多字节字符在 8 KiB 边界被切断 | 需字节精确的假 shell（当前 `.cmd`/sh 脚本做不到）；`Utf8ChunkStream` 只有单元级覆盖 |
| A15 | 错误路 `AgentSettled` 形状无夹具 | SessionRunner 错误路发非空 toolResults 时无断言守护 |

---

## 3. B 类 —— 功能缺口

### 3.1 会话 / RPC 事件面

| # | 条目 | 现状与出处 |
|---|---|---|
| B30 | `session_info_changed` 无生产者 | RPC set_session_name 改了名不发事件；pi 在 setSessionName 内发射 |
| B31 | `get_state.model` 发字符串，pi 发整个模型对象 | RpcDispatcher.buildState 发 `"provider/id"`；pi 字段是 `Model` |
| B32 | `get_state.sessionName` 恒有值，pi 可选 | 默认 `"session"`，客户端分不出用户命名与默认名；pi 在首次 session_info 前缺键 |
| B33 | `get_state.messageCount` 取转录条数，pi 取工作副本条数 | 压缩后必然分叉（transcript 含 compaction/model_change 等非消息条目） |
| B36 | 无 `abortCompaction` | TUI 无法取消压缩（Java 借 lane.abortSignal，无 per-operation 取消句柄）；B39 的 Esc 换绑被它挡着 |
| B49 | `turn_end` 颗粒度不同 | pi 每回合一条，Java 的 AgentSettled 每次驱动一条；真对齐要改事件时序 |
| B53 | `find()` 缺 pi 的分级查找 | pi 三级阶梯（header-only → 项目内 → 跨项目）；Java 直接走跨项目全量扫描 |
| B55 | 会话列表无进度/异步/取消维度 | pi list 带 onProgress、渐进发布、AbortSignal、并发扫描；Java 同步全量（仅交互选择器面） |
| B237 | RPC prompt disposition 缺 `handled` | pi 三态 `handled\|queued\|started`（`rpc-types.ts:118`、`agent-session.ts:310-311`）；Java `RpcDispatcher.java:117-130` 只产 started/queued |

### 3.2 请求选项 / Provider 协议面

| # | 条目 | 现状与出处 |
|---|---|---|
| B116 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言 | pi 的 usage 只在消息上，proxy 线另发扁平 usage；2026-10-12 取证：线格式两处均已扁成 pi 形状（`JsonEventMapper.java:320-327,351-373`），内部组件保留（原 docs/08 §2.2） |
| B156 | ModelCompat 逐组件重建会静默丢字段 | 新组件追加时的副作用面；2026-10-12 评估：全仓 13 构造点集中 4 文件且有逐字段注释，列 backlog 不强改（原 docs/08 §2.2） |

**compat 面细碎登记**（pi 有标注/消费、Java 无的字段与数据位）：

| # | 条目 |
|---|---|
| B94 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明 |
| B95 | `docs` 段无生产者（ContextAssembler 不传 DocumentationPaths） |

### 3.3 功能整块

| # | 条目 | 现状与出处 |
|---|---|---|
| B1 | **branch summary 无实现** | pi branch-summarization；重试路只保留了 source:"branchSummary" 形状 |
| B79 | `/bug` 故障上报 ＋ 崩溃记录 | pi slash 命令：同意提示 → 摘要 → 上报 |
| B218 | `McpServerRegistry` 与 `McpScope.EXTENSION` 闭环前**无生产者** | 包⑫a 落（`原 docs/39`），接线是 B221；`pi.registerMcpServer` 没有挂载点 |
| B219 | `mcp_servers` 段的排序用 `String.compareTo` 代替 `localeCompare` | ASCII 名等价，非 ASCII 名排序有差（`原 docs/39` §3.2） |
| B220 | `truncate` 的 `trimEnd()` ⇒ `stripTrailing()` | 后者不吃 ` `（窄角，`原 docs/39` §3.2） |
| B221 | **MCP 扩展接线面整体顺延** | 六个 `on(...)` 事件（**1/6**，见 `原 docs/39` §2.4）、激活门暴露给扩展（机制在 `AgentHarness.getActiveTools:528` 但扩展拿不到）、`ctx.ui.custom`（**B214**）、`registerToolRenderer`（**B204**）、提示分节接线（`ContextAssembler.promptOptions:104-114` 从没调过 `.sections(...)`）—— **归属是 D 域与 E5，不是 MCP** |
| B161 | **codemode 整块零对应** | quickjs-wasi 沙箱代码执行 |
| B162 | tool-search / virtual-models / nested-tool-calls 三块零对应 | deferred 工具发现；虚拟模型；嵌套工具调用 |
| B177 | MCP 默认 `codemode` exposure 暂不可达 | MCP 各包（包①–⑫a）的 wire/配置语义全保留，运行时激活路径依赖 B161 闭环（原 docs/28 §3 裁决） |
| B181 | MCP 配置 record **不保留未知键** | pi `validateMcpServerConfig` 返回原对象副本（`mcp-servers.ts:263,275`）；Java record 丢之。下游只读具名键、写路径重读文件 ⇒ 行为等价（原 docs/35 §2.1） |
| B182 | MCP 项目配置目录 `.pi-java` 硬编码 | pi 读 `package.json` 的 `piConfig.configDir`（`config.ts:542`）；pi-java 全树既有约定（`FileSettingsStorage:42` 先例，原 docs/35 §2.3） |
| B183 | mcp.json 解析错误**文本**与 V8 不同 | 结构同（`<路径>: <消息>`）；空文件在 pi 是语法错、在 Java 报 `expected an object with an "mcpServers" object`（原 docs/35 §4.2） |
| B184 | `McpConfigError` 是 Java 特有类型 ＋ 写出字面量偏差 | pi 抛裸 `Error`；`JSON.stringify` 的 `1.0`→`1`、`pi mcp add` 键序按 record 组件序（原 docs/35 §5.4） |
| B185 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除 | TRUE/FALSE/null 三态（null＝键缺席），与 B144 同形的误报；jspecify `@Nullable` 不被 SpotBugs 识别（原 docs/35 §10 发现 2） |
| B186 | **mcp 模块的 `verify` 在包 ⑥/⑦ 从未跑过** | `spotbugs:check` 绑在 `verify`；包 ⑥/⑦ 只跑 `test`＋`checkstyle` ⇒ 本包一跑即红两条（本包 NPE 已修 ＋ B185）。**包闭环验证口径须含 `verify`** |
| B187 | 包④ `InMemoryTransportTest` 断言**抢跑**（已修） | 旧夹具断言「`send` 返回时监听器尚未跑」（`delivered.isEmpty()`），那是在跟虚拟线程**抢调度**（实测 7 跑 1 红）；产品契约（`send` 不在调用栈上重入）由 `startVirtualThread` 结构性保证 ⇒ 改成确定性断言「监听器线程 ≠ 发送线程」（反向变异：改内联投送恰 1 红）。**教训：凡「某时刻还没发生」的断言都是运气** |
| B188 | MCP `!cmd` 的 `settings.shellPath` 层未接 | pi 两段式（先配置 shell、ENOENT 回落默认）；`CommandRunner` 可注入，第 12 包接线（原 docs/36 §2.4） |
| B189 | MCP 客户端身份由调用方注入 | pi 取 `config.ts:540,543`；Java 的 `Version.VERSION` 在 coding-agent，模块内零字面量，第 12 包传（原 docs/36 §2.3） |
| B190 | `isTransientError` 的判定范围宽于 pi | pi 判 `TypeError`（fetch 网络错）；Java 判 `IOException`，含 TLS/中断/重定向策略（原 docs/36 §5.1） |
| B191 | `WebUrls` 的 WHATWG 归一化是自建 | `String(new URL(url))` 的主机小写/补尾斜杠/去默认端口；逐字节等价待真 pi 互读（原 docs/36 §6.2） |
| B192 | cimd 模式不可达 | `CLIENT_METADATA_BASE_URL = https://pi.dev/oauth` 是 pi 的域名，pi-java 无托管文档 |
| B193 | `mcp-auth.json` 的字节形态偏差 | `clientInformation.extension` 写成嵌套键；record 的 `null` ＝ JS `undefined` ⇒ 省略（两侧都按 JSON 解析，行为等价） |
| B194 | OAuth 刷新的 15 s 超时靠外包一层 | `McpFetch.fetch(Request)` 无 signal 通道；未扩包⑥/⑦接口（原 docs/36 §6.4） |
| B195 | MCP 运行时新类暂无生产消费者 | 同 B177 族：第 10–12 包接线（第 12 包为末） |
| B196 | `connectOnce` 顺序拉取工具/资源 | pi 用 `Promise.all` 并发；差异仅时序 |
| B197 | `URI.create` 接受相对引用而 `new URL` 抛 | `responseFromRedirectUrl` 已显式挡 scheme；其余 URL 入口未逐个复核 |
| B198 | `OAuthPage` 的 CSS 折行 | 字节差仅 `<style>` 内空白，计算值相同（checkstyle 120 列） |
| B199 | 包③ 的 stdio `cwd` 未移植（**已修**） | 包⑨ 补：`StdioTransportOptions` 加 `cwd` ＋ `ProcessBuilder.directory`；此前 `mcp.json` 的 `cwd` 字段是死的 |
| B200 | 包⑧ 的 checkstyle 是「错误 0、警告 2」（**已修**） | 闭环记录称「checkstyle 0」未区分错误/警告；⇒ 闭环口径须写明各几个 |
| B201 | 包⑤ 的 HTTP transport 无公开构造面（**已修**） | 包⑨ 补：`StreamableHttpTransport`/`…Options` 包私有且无工厂 ⇒ 包外无法构造；pi 从 `@earendil-works/pi-mcp` 公开导出 |
| B202 | MCP 结果的 `structuredContent` 无消费者 | `ToolResult.structuredContent` 已落形状（包⑩）；消费者是 codemode（**B161**）与 `after_tool` 钩子改写（pi `types.ts:1439`），均未落 |
| B203 | MCP 的 exposure/namespace/outputSchema/annotations 未接到 `AgentTool` | pi 的 `ToolDefinition` 有这四项；Java 的 `AgentTool` 没有，包⑩ 让 `McpToolDefinition` 承载；激活路径是第 ⑫ 包与 **B161/B162** |
| B204 | MCP 工具与资源的 TUI 渲染器未移植 | pi `createMcpToolRenderers`（`tools.ts:301-339`）用 `Text`/`Container`/`VisualLinePreview`/`keyHint`；pi-java 的工具渲染另成一路（**B65/B47**） |
| B205 | MCP 工具适配器在 **`pi-java-coding-agent`**，不在 `pi-java-mcp` | `AgentTool` 在 agent-core，`pi-java-mcp` 看不见它（`ai ← mcp`）⇒ 模块分道；`原 docs/28` §2 的模块图早画了 `coding-agent ← mcp`，pom 直到包⑩ 才接上那条依赖 |
| B206 | `ToolResult.isError` 是新增的语义通道 | pi 的 `isError` 来自**结果对象**（`AgentToolResult.isError`），pi-java 此前只由「抛没抛」派生；`PiToolRunner` 改读结果字段，`after_tool` 钩子仍可改写 |
| B207 | `McpToolExposure` 是 pi 的 `ToolExposure`，暂居 mcp 包 | 5 值 `direct/model-only/codemode/deferred/hidden`；pi-java 扩展系统没有这个类型，唯一生产者是 MCP 适配器 —— 待 E5 面成型时上移 |
| B208 | `ContentBlock.ImageContent(mediaType, data)` 与 pi 的 `{data, mimeType}` **序相反** | mcp 侧 `LlmContent.Image(data, mimeType)` 又跟 pi 一致 ⇒ 同一条转换链上有两个相反的序；换序点唯一（`McpResultContent.toBlock`），夹具两侧钉住 |
| B209 | `TruncationUtils.formatSize` 用默认 locale（**已修**） | `%.1f` 在德语区渲染 `1,5KB`，pi 的 `toFixed` 恒用 `.`；包⑩ 加 `Locale.ROOT` |
| B210 | `pi mcp` 的选项解析是自建的 | pi 的 `parseOptions` 有「收够 N 个位置参数即停止」语义（`add` 传 2），让 `add <server> <command> --flag` 的 `--flag` 透传给命令；picocli 表达不了（原 docs/38 §2.4） |
| B211 | `openBrowser` 是 pi-java 新增件 | pi `utils/open-browser.ts`；Java 新增 `BrowserLauncher`（Desktop → 平台命令 → 静默失败）；headless 下只提示「visit the URL above」 |
| B212 | `waitForRedirectUrl` 的两处平台替代 | `process.stdin.isTTY` ⇒ `System.console() != null`（**不等价**，Java 无 isatty）；readline 的 `{ signal }` ⇒ 虚拟线程读 + abort 竞速（原 docs/38 §3.6） |
| B213 | `pi mcp` 的文本字面量按 pi-java 命名 | 帮助/提示里的 `pi`→`pi-java`、`.pi`→`.pi-java`（B182 同族）；`chalk.dim` 去掉（pi-java 不做 ANSI 着色） |
| B214 | `/mcp` 管理器视图（`ui.ts` 252）顺延第 ⑫ 包 | `McpManagerView` 是 pi `ctx.ui.custom(...)` 的载荷，而 pi-java 的扩展 UI 是 RPC 请求/应答、**没有组件挂载面**（E5 域）；菜单编排本来就在 `index.ts`（9 个 `ui.menu/status/redirectUrl` 调用点）⇒ 两半一起进 ⑫（原 docs/38 §2.1，R-A 裁决 A） |
| B215 | `FileAuthJsonBackend` 的整文件字节锁**挡不住自己的读**（**已修**） | 包⑨ 用 `tryLock()` 锁整个文件后另开句柄 `Files.readString` 读它；Windows 对同进程其它句柄同样执行字节区间锁 ⇒ ERROR_LOCK_VIOLATION（POSIX 不挡读，故只在 Windows 可见）。修法＝读走持锁 channel；⚠️ **文件后端在包⑨ 从未被实例化**（测试用内存后端）⇒ 包⑪ 补 `FileAuthJsonBackendTest` |
| B216 | 包⑪ 开的四处跨包小缝 | `McpServerConnection.oauthSettings()` 包私有 → public（pi 公开）；新增 `clearChallenge()`（pi 是直接赋 `undefined`）；`McpServerState.wire()`（CLI 要打 `needs-auth`）；`SettingsManager.load(agentDir, projectDir, override)`（原先只能为进程自己的目录解析信任） |
| B217 | MCP `login` 的端到端（真授权服务器）无夹具 | 夹具覆盖到「连不上 / 非 OAuth / 缺参数 / 超时校验」为止；`--timeout` 秒数文案与 `reconnect` 后的成功路径需真 AS（包⑨ 的 `McpSignIn` 另有夹具） |
| B163 | 主题/色彩系统差距 | pi 3,176 行（theme/system-theme/oklab…），Java 74 行 |
| B236 | **新锚点**：`--tools`/`--exclude-tools` 模式面缺失 | pi 走 minimatch 模式：`*` 全选、`+name/-name` 修饰符、`mcp__` 前缀、空名单保留 MCP 注册（`args.ts:151-162`、`agent-session.ts` createToolNameMatcher/applyToolModifiers）；Java `ArgsParser.java:87-90,244-251` 只切逗号裸名 |
| B243 | **powershell 工具实现＋默认激活集**（挂 B236 族；设计稿 原 docs/22（批 3 设计稿） 原编号 B241，与 telemetry 批 1 撞号改此） | pi `core/tools/powershell.ts:1-67`＝bash 的 `createShellToolDefinition` 变体（`name/label "powershell"`、prompt `PS>`、`tempFilePrefix "pi-powershell"`、UTF8 前缀、`shell.ts:126-133` 仅 Windows＋`pwsh.exe`→`powershell.exe` 解析）；但 pi **默认不激活**（`DEFAULT_TOOL_NAMES = ["read","bash","edit","write"]`，`settings-manager.ts:83`），激活走 `defaultTools` 修饰符/`--tools` 模式面——即 B236 的面 ⇒ 实现＋激活策略（含四件默认集差）整体随 B236 批落地（原 docs/22（批 3 设计稿） §5.2） |
| B242 | **bash 的 `PI_*` 会话环境变量面** | pi 默认向子进程暴露 `PI_SESSION_ID/PI_SESSION_FILE/PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL`（`resolveSpawnContext`，`bash.ts:185-213`；`exposeSessionEnvironment` 默认 true），bash 的 promptGuidelines 因此带 `You can inspect PI_* environment variables…`（`bash.ts:47`）；java `ToolContext` 拿不到会话身份（在 coding-agent 层），跨层接线（ToolContext 增会话身份供应器或 ToolOptions 注入）待做——在此之前 bash guideline 不加（加了是假话）（原 docs/22（批 3 设计稿） §5.3） |

### 3.4 TUI（优先级最低，web UI 为主）

| # | 条目 | 现状与出处 |
|---|---|---|
| B38 | compaction_end 后 TUI 不重建聊天区 | pi 整表 clear＋重渲染＋压缩摘要消息卡；Java 现只有压缩指示器/一行摘要/QueueUpdate（2026-10-12 重测更正措辞），整表重建与摘要卡仍缺 |
| B39 | 压缩窗口 Esc 换绑 | 随 B36 |
| B46 | BashTool 的 onUpdate 无 TUI 消费者 | 生产发射面已通（BashTool→BashUpdateEmitter→SessionRunner，2026-10-12 重测更正：旧记「生产永不发射」过期）；web 有 bashOutput，TUI 渲染面仍缺 |
| B47 | TUI tool_execution_* 语义取错源 | Java 按「模型吐完调用」计数，pi 按真实工具执行事件 |
| B65 | MarkdownRenderer/SyntaxHighlighter 是死代码 | 助手消息走纯文本，表格/mermaid/高亮/链接全不可达；先定「接线还是删声明」 |
| B66 | 6 个已声明键位未接线 | MODEL_CYCLE/THINKING_CYCLE/TOOLS_EXPAND 等 |
| B78 | FuzzyMatcher 语义不同 | pi 子序列匹配 ＋ 分词；Java 不同 |
| B145 | 启动/登录后定向目录刷新未接线 | 仅 RPC 模式有后台刷新，pi 另有三处 |
| B238 | **新锚点**：TUI 四项新行为缺失（明细见 map/05） | K20 视口滚动键改 Ctrl+Home/Ctrl+End（裸 Home/End 归还编辑器）；R29 安装变更每会话一次重启警告（`maybeShowInstallChangeWarning`）；K39 OSC 7501 程序状态上报（program-status）；C20 APC 标记假光标 |

---

## 4. C 类 —— 已裁决不做 / 对齐面外（带触发条件）

| 条目 | 裁决 | 触发条件 |
|---|---|---|
| B18（PiMessages 丢 thinking 签名/redacted） | PiMessages 是 Java 自有的 wire，pi 无对应物，不按 pi 改造 | 该 wire 成为对 pi 主协议的兼容面时重裁 |
| B83（pi 自身三处 sanitize 缝） | 照缝移植、不多净化（普通工具重放入参/签名字段/google args） | pi 补上其三处时同步 |
| B59（SQLite schema 与 pi 分叉） | pi 的 session-backends 已整包删除且从未进 `bin pi`；模块保留，分叉不再是缺口 | pi 重新推出主流存储后端时重裁 |
| B62（chord）＋ B71/B72/B73（pico3/durable/micro） | R5：非 `bin pi` 可达，权重 0，不进对齐面 | 这些子系统进入 pi 主流发布版 |
| B100（compat 探测 baseUrl 来源） | 2026-10-12 取证降架构备注：pi 只读模型对象，java 读 `--base-url` 覆盖后的有效值；内置模型两者同值，零行为差（原 docs/08 §2.2） | models.json baseUrl 与 `--base-url` 并存且被证实可观察时重裁 |
| B126（codex 车道忽略 maxTokens） | 随 openai-codex 车道整体出对齐面（原 docs/08 §2.1：transport/responseId/endTurn/session-resources 同族 w0） | codex 订阅车道纳入对齐面（§6 口径松动）时与车道整体一起做 |
| B147（Responses 回放 item 按类型分组） | 2026-10-12 裁决保留：java 三桶收集为刻意偏差（`ResponsesMessageConverter.java:426-428` 有注释），rs_ id 配对靠签名里的 id；改单循环 M 收益不值。**2026-10-13 批 1 补分组序夹具**（`ResponsesCompositeItemIdReplayTest`：交错块固定 reasoning⇒message⇒function_call 分组） | 发现 OpenAI 对交错序有配对校验差异时重裁 |
| B164（分类器/统一模型设施） | R5：`classify()` 消费者仅 codemode（默认关）/llama 扩展/示例；`/model` 选择器不可见（原 docs/08 §2.1，map/02 #92/#94/#33） | 分类器进 bin pi 主流非 codemode 路径 |
| B226（Radius 内建 provider） | 主流 7 家口径外，**2026-10-12 用户裁决维持 w0**。注：/login 技术上可达（无白名单）；java 已有 pi-messages 线＋OAuth 设备码工厂，边际成本 M，语义须按 gateway catalog 整体替换 baseline | 用户口径纳入 Radius（azure 同族，优先于 bedrock/vertex/codex） |
| B230（azure 改名＋Foundry 第二车道） | 主流 7 家口径外，**2026-10-12 用户裁决维持 w0**。java responses 协议已在（仍注册旧 id）；改名 S＋Foundry completions 复用现成车道，合计 S-M | 用户口径纳入 azure |
| B130（zai `supportsReasoningEffort` 数据驱动） | 2026-10-11 取证转 C：Java 只载 glm-4.7 族，pi 目录对其标 false、无级别表；Java 目录给 null＋探测（isZai）兜底 false ⇒ 有效值逐字节相同，今零行为差 | `ModelData` 引入任何带 thinkingLevelMap 的 glm-5.x（或任何 pi 标 sre=true 的 zai 模型）时：`CatalogCompatRules` 的 effort 位必须改成按级别表有无数据驱动（≙ pi 生成器按 reasoning_options 推导），守卫夹具 `carriedZaiModelsEffectivelyDontSupportReasoningEffort` 会先红 |
| B132（together/baseten/fireworks 目录常量） | 2026-10-11 取证转 C：Java 不携带三个内置 provider（R5）；`ThinkingFormatWriter` 的 TOGETHER/BASETEN 臂、`ChatTemplateKwargValue`、models.json 的 thinkingFormat/chatTemplateArgs 显式键与 URL/provider 探测全在 ⇒ 手搓 models.json 接三类后端与 pi 等效（fireworks 目录恒 openai 格式、无特殊常量） | 携带三者内置 provider/目录时：常量随 `providers/data/*.json` 生成值一起搬（together 的 thinkingFormat、baseten 的 `chatTemplateArgs:{enable_thinking:{$var:thinking.enabled}}`） |
| map/03-#54（Entry union 内存形状） | 2026-10-17 批 agent-core-2 裁决（原 docs/21（批 2 设计稿） §4.1）：**行为等价，不认账**。label/session_info 的全部消费者 pattern-match 的都是 `SessionMutation`/`LogItem`，**零 Entry 身份消费者**；wire 读写 pi 原生行（`PiV3Wire`）＋fact 链进树（parentId）⇒ 磁盘全互通、树位等价；pi 的 `SessionTreeNode.label`（`labelsById`+`getLabel`）与 java 的 `SessionState.labels`+`Session.getLabel` 是同构访问器，树节点本身两侧都不带 label。内存 union 不补 2 record（认了要牵 7 个生产文件的双表示归一，买的是不可观察的形状洁癖） | 出现按 Entry 身份（而非 `SessionMutation`/`LogItem`）消费 label/session_info 的消费者时重裁 |
| map/03-#58（convertToLlm 与 entry 投影的 case 合并部） | 2026-10-17 批 agent-core-2 裁决（原 docs/21（批 2 设计稿） §4.2）：**消解成立**。pi 的「消息级 convertToLlm」与「entry 级 buildSessionContext 投影」在 java 合并为一条 entry 投影管线，case 语义逐条可对上（custom→user、branchSummary/compactionSummary 加前后缀、四角色透传）；唯一真差 bashExecution 角色不在对齐范围（harness 层三角色，原 docs/31 §1.2）。stopReason 剔除部**不消解**——已按认账删除（`NON_PROJECTED_STOP_REASONS` 移植自已删除的 harness `isContextMessage`，R1；现行 pi 主流把 error/aborted 消息原样发给 provider） | bashExecution 角色纳入对齐面时重裁合并部；pi 重新引入 stopReason 过滤时重裁剔除部 |
| map/03-#78（latest/find 范围 ＋ listSessions 排序键） | 2026-10-17 批 agent-core-2 裁决（原 docs/21（批 2 设计稿） §4.3）：latest/resume **等价**（java `latest(cwd)` 单项目目录 ≙ pi `continueRecent`/`findMostRecentSession:757` 同源同键）；`find(idOrPrefix)` 跨项目扫描**裁决保留**（CLI `--resume <id>` 需要，java 多出面）。**排序键差留痕不认账**：pi `listSessions` 按最后活动时间 desc（`session-manager.ts:858-864`，max(message entry timestamp)，mtime 仅兜底），java 按文件 mtime desc（`JsonlSessionRepository.java:85-90`）——pi 的 lastActivity 需对每个会话文件全量扫 entry（正是 java 优化掉的成本，1082ms→5ms 实测），追加写下 mtime 序 ≈ 活动序 | 「改名/打标追加 fact 行后选择器序与 pi 不同」被证实可观察（分叉场景：fact 行只 bump mtime 不 bump 活动时间）时重裁 |
| map/03-#100（系统提示载体） | 2026-10-17 批 agent-core-2 裁决（原 docs/21（批 2 设计稿） §4.4）：**行为等价**。pi 主流**无会话中变更系统提示的路径**（`setSystemPrompt` 全仓零命中，写入点全在启动期）；pi 的请求上下文只有 messages，系统提示由 `normalizeContext`（`utils/transcript.ts:30-34`）折成首条 system 消息；java 在 provider 边界经 `ContextNormalizer` 于**同一点**做同一折叠（`DefaultProviders` 交出 `Context.systemPrompt`）⇒ 请求线行为等价。`AgentHarness.setSystemPrompt` 是超集能力（pi 无），留痕不删；`Context.java` 类注已按本锚重写 | pi 出现会话中系统提示变更路径（`setSystemPrompt` 类 API）时重裁 |

---

## 5. E 类 —— 结构债（仓库规则：文件 ≤ 500 行）

| # | 文件 | 行数 |
|---|---|---:|
| E1 | coding-agent …/core/AgentSession.java | 1,187 |
| E2 | coding-agent …/rpc/RpcDispatcher.java | 642 |
| E3 | agent-core …/harness/PiLaneSink.java | 562 |
| E4 | agent-core …/harness/AgentHarness.java | 595 |
| E5 | ai …/catalog/ModelCompat.java | **762**（2026-10-11 B98 加 8 组件；converter 侧已拆出 CompletionAssistantMessage 保 500 内） |

均不与功能耦合，可随时拆，与功能包搭车推进。

---

## 6. F 类 —— 待你拍板

| # | 条目 | 要定的事 |
|---|---|---|
| B54 | `--no-session` 在 web 路径被完全忽略 | `--mode web` 总是持久化 与 `--no-session` 语义冲突，谁赢未定义（非 web 路径正常生效） |
| B58 | openai provider 默认 wire 与 pi 不同 | pi 默认 openai-responses，Java 默认 openai-completions；「跟随 pi」还是「记为刻意偏差」 |

---

## 7. 维护规则

1. 每个包在**自己的文档**里记设计、取证与实施记录（命题表 → 逐条验证 → 实施稿，
   用户审核后才写代码）；本台账只留条目索引。
2. 新条目：先在包文档立项（带证据/反向实验），再在本台账加一行，编号顺延不复用。
3. 结案/作废：从本台账移除该行，commit message 写明结论；历史在 git 可查。
   存活条目**永不改号**（代码注释引用编号）。
4. 「不做」类进 §4，写清裁决与触发条件；分类变动在包文档记录。
5. §1 计数由命令实测，不手记。
6. 包设计稿闭环后随过程文档清理删除（历史在 git），**编号不复用**；下一包号 `docs/24`。
