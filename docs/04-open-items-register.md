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
- 已闭环批次：ai 批 0–10（原 docs/08–19）、agent-core 批 1–3（原 docs/20–22）、telemetry 批 1（原 docs/23）。
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

### 3.2 请求选项 / Provider 协议面

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B116 | 协议面 | StreamDone 的 UsageInfo 嵌套包裹是 Java 方言 | pi 的 usage 只挂在消息上，proxy 线另发扁平 usage。2026-10-12 取证：线格式两处均已改成 pi 的扁平形状（`JsonEventMapper.java:320-327,351-373`），仅内部组件保留嵌套（原 docs/08 §2.2） |
| B156 | 内部 | ModelCompat 逐组件重建会静默丢字段 | 新增组件时所有构造点都要改，漏改就静默缺省。2026-10-12 评估：全仓 13 个构造点集中在 4 个文件且有逐字段注释，列入 backlog 不强制改（原 docs/08 §2.2） |

**compat 面细碎登记**（pi 有标注或消费、Java 没有的字段与数据位）：

| # | 面 | 条目 |
|---|---|---|
| B94 | 模型可见 | 8 个内置工具的 `promptSnippet`/`promptGuidelines` 声明 |
| B95 | 模型可见 | `docs` 段没有生产者（ContextAssembler 不传 DocumentationPaths） |

### 3.3 功能整块

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B79 | 用户可见 | `/bug` 故障上报 ＋ 崩溃记录未实现 | pi 的 slash 命令流程：同意提示 → 生成摘要 → 上报 |
| B218 | 内部 | `McpServerRegistry` 与 `McpScope.EXTENSION` 闭环前**没有生产者** | 注册表代码已落（原 docs/39），但 `pi.registerMcpServer` 没有挂载点；接线归 B221 |
| B219 | 模型可见 | `mcp_servers` 段的排序用 `String.compareTo` 代替 pi 的 `localeCompare` | 纯 ASCII 名字两者等价，非 ASCII 名字排序有差（原 docs/39 §3.2） |
| B220 | 模型可见 | `truncate` 的尾部裁剪字符集与 pi 不同 | Java 用 `trimEnd()`，pi 对应 `stripTrailing()` 语义；两者对窄角空格（U+00A0）处理不同（原 docs/39 §3.2） |
| B221 | 协议面 | **MCP 扩展接线面整体顺延** | 缺口清单：① 六个 `on(...)` 事件只接了 1 个（原 docs/39 §2.4）；② 激活门未暴露给扩展（机制在 `AgentHarness.getActiveTools:528`，扩展拿不到）；③ `ctx.ui.custom`（即 B214）；④ `registerToolRenderer`（即 B204）；⑤ 提示分节接线（`ContextAssembler.promptOptions:104-114` 从未调过 `.sections(...)`）。归属是扩展系统（D 域与 E5），不是 MCP 模块本身 |
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
| B244 | 用户可见 | **`skill:` slash 命令注册＋settings `skillCommands` 开关**（批 agent-core-4 新登记，docs/09 §13.5） | pi `agent-session.ts:3377` 注册 `skill:<name>` 命令；settings-schema.ts:315 有 `skillCommands` 开关。批 4 只做了 `/skill:` 文本展开（`SkillCommandExpansion`），命令注册面未做 |
| B245 | 混合 | **tree 扩展事件＋TUI 摘要流**（批 agent-core-4 新登记，docs/09 §13.5） | ① `session_before_tree`/`session_tree` 扩展事件（types.ts:817-846,1488-1501，含 `result.summary` 整体替代生成器的 fromHook=true 旁路）；② TUI 树选择器摘要三选对话框（interactive-mode.ts:5559-5706）＋摘要期 Esc 换绑 `abortBranchSummary`（:5637-5640）＋`skipPrompt` 弹窗门（:5590，设置项批 4 已备）。挂扩展事件（D 域）与 TUI 两族；`navigateTree`/`abortBranchSummary` 本体批 4 已落 |
| B246 | 混合 | **工具选择的 reload 臂＋SDK `tools` 选项抛错面**（批 coding-agent-1 新登记，docs/11 裁决④） | pi `usesDefaultTools`/`defaultToolModifiers`（`sdk.ts:472-473`）只在 `reload()` 有语义（`agent-session.ts:3660-3685`：设置重载后激活**新增**默认工具、移除的保持激活、`-name` 移除的保持移除），依附扩展运行时重载面；java 无 reload 面（设置改动走 accessors 即效）。SDK `tools` 选项的 `Invalid tools option` 抛错（`sdk.ts:280-281`）java 无 SDK 面；CLI 等价物＝解析期 error 诊断（批 1 已落）。触发条件＝java 出现 session reload/扩展运行时或 SDK 面 |

### 3.4 TUI（优先级最低，web UI 为主）

| # | 面 | 条目 | 现状与出处 |
|---|---|---|---|
| B38 | 用户可见 | compaction_end 后 TUI 不重建聊天区 | pi 整表 clear＋重渲染＋压缩摘要消息卡；Java 现只有压缩指示器、一行摘要、QueueUpdate（2026-10-12 重测更正措辞），整表重建与摘要卡仍缺 |
| B39 | 用户可见 | 压缩窗口 Esc 换绑 | ~~随 B36~~ B36 已销号（批 agent-core-4 落 `abortCompaction`/`abortBranchSummary`）；换绑本体未做，与 B245 的 TUI 摘要流同族 |
| B46 | 用户可见 | BashTool 的 onUpdate 没有 TUI 消费者 | 生产发射面已通（BashTool→BashUpdateEmitter→SessionRunner；2026-10-12 重测更正：旧记「生产永不发射」已过期）。web 有 bashOutput，TUI 渲染面仍缺 |
| B47 | 用户可见 | TUI tool_execution_* 语义取错源 | Java 按「模型吐完调用」计数，pi 按真实工具执行事件计数 |
| B65 | 用户可见 | MarkdownRenderer/SyntaxHighlighter 是死代码 | 助手消息走纯文本，表格/mermaid/高亮/链接全不可达；先定「接线还是删声明」 |
| B66 | 用户可见 | 6 个已声明键位未接线 | MODEL_CYCLE/THINKING_CYCLE/TOOLS_EXPAND 等，按键无反应 |
| B78 | 用户可见 | FuzzyMatcher 语义不同 | pi 是子序列匹配＋分词；Java 的实现不同 |
| B145 | 内部 | 启动/登录后定向目录刷新未接线 | 仅 RPC 模式有后台刷新，pi 另有三处刷新点 |
| B238 | 用户可见 | **新锚点**：TUI 四项新行为缺失（明细见 map/05） | ① K20 视口滚动键改 Ctrl+Home/Ctrl+End（裸 Home/End 归还编辑器）；② R29 安装变更每会话一次重启警告（`maybeShowInstallChangeWarning`）；③ K39 OSC 7501 程序状态上报（program-status）；④ C20 APC 标记假光标 |

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

---

## 5. E 类 —— 结构债（仓库规则：单文件 ≤ 500 行）

| # | 文件 | 行数 |
|---|---|---:|---:|
| E1 | coding-agent …/core/AgentSession.java | **1,605**（2026-10-18 批 coding-agent-1 实测；拆分宜搭下一 coding-agent 批） |
| E2 | coding-agent …/rpc/RpcDispatcher.java | 627 |
| E5 | ai …/catalog/ModelCompat.java | **762**（2026-10-11 B98 加 8 组件；converter 侧已拆出 CompletionAssistantMessage 保 500 内） |

（E3/E4 已销号——2026-10-18 批 agent-core-5 拆分：`PiLaneSink` 470＋`PiLaneRequestTelemetry` 131、`AgentHarness` 438＋`HarnessBootstrap` 231，行为零变化纯移动。）

均不与功能耦合，可随时拆，与功能包搭车推进。[全部内部，模型不可见]

---

## 6. F 类 —— 待你拍板

| # | 面 | 条目 | 要定的事 |
|---|---|---|---|
| B54 | 用户可见 | `--no-session` 在 web 路径被完全忽略 | `--mode web` 总是持久化，与 `--no-session` 语义冲突，谁赢未定义（非 web 路径正常生效） |
| B58 | 协议面 | openai provider 默认 wire 与 pi 不同 | pi 默认 openai-responses，Java 默认 openai-completions；「跟随 pi」还是「记为刻意偏差」 |

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
