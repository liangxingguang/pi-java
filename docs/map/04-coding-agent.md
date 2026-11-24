# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 【背景摘要】范围：pi `packages/coding-agent` ↔ `pi-java-coding-agent`（CLI 参数、子命令、slash 命令、扩展系统、settings 键、会话管理、包管理器、RPC）。
> 当前读数：**461/461＝100%**（Σ权重 461）。
> 差异结论：无缺失/存疑的满分域已收敛为计数行；差异集中在 D 窄差 D1–D35、settings 5 条存疑＋1 条出分母、转出 3 条（E3-3／E5-31／E5-33）、E8 缺失行与 G-6/G-7 面外。开放缺口台账见 [`../04-open-items-register.md`](../04-open-items-register.md)。

## 基准与口径

- 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差，或台账未结）。
- **权重口径**：权重 = 用户可观察影响 × 频率，不含排期优先级。`3`＝每轮主路；`2`＝每次会话或常用；`1`＝低频/内部；`0`＝非目标。完成系数：对齐 1、存疑 0.5、缺失 0。
- **R5 可达性**：只算 `bin pi` 主流可达的；`coding-agent/src/experimental/**` 一律权重 0。
- **R1 作废**：`src/experimental/micro/**` 与 `src/experimental/mini/**` 已被 pi 删除 ⇒ 相关单元权重 0、移出分母。
- **MCP 面整体移出本图**：MCP 注册表 / `pi mcp` CLI / MCP 工具·渲染器接线由 [`07-mcp.md`](07-mcp.md) 覆盖；本图保留单元编号与判定，证据列只给指针，不展开。

---
## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| **产品层合计（src/main）** | `packages/coding-agent/src`（64 文件在两锚点间改动，其中 61 个 `.ts`） | **86,152** | **14,886**（非 MCP **11,931**） | **17.3%**（非 MCP 13.9%） |
| ├ 扩展系统 | `core/extensions` **5,005** + `src/extensions` **7,456** | 12,461 | 1,529（`extension/` 非 MCP 717 + agent-core `hook/` 812） | 12.3% |
| ├ 包管理器 | `package-manager.ts` 2,760 + `package-manager-cli.ts` 1,141 + `pi-manifest.ts` 35 + `utils/tools-manager.ts` 400 | 4,336 | 496 | 11.4% |
| ├ 主题系统 | `modes/interactive/theme/*`（2,261 `.ts` + 156 JSON） | 2,417 | 113（在 **pi-java-tui**：`PiTheme.java`） | 4.7% |
| ├ 会话管理 | `core/session-manager.ts` | 2,013 | 773（`SessionPersistence` 256 + `PersistentSessionRepositories` 399 + `core/session/` 118） | 38.4% |
| ├ 资源发现 | `skills.ts` 514 + `prompt-templates.ts` 320 + `resource-loader.ts` 1,287 | 2,121 | 896（`skill/` 613 + `prompt/` 283；`ContextFileDiscovery` 已移至 agent-core） | 42.2% |
| ├ RPC | `modes/rpc/*` | 1,797 | 1,331 | 74.1% |
| ├ CLI/TUI 交互 | `modes/interactive/*` 21,895 + `cli/*` 1,959 + `utils/*` 3,770 | 27,624 | ~2,800（`mode/` 450 + `modes/` 285 + `cli/` 607 + `subcommand/` 非 MCP 587 + `core/slash/` 747 + 顶层 126） | ~10.1% |
| ├ **MCP 面（→ map/07）** | `extensions/mcp` 4,240 + `core/mcp-servers.ts` 346 + 新包 `packages/mcp` 3,244 | 7,830 | 13,808（**pi-java-mcp** 主源 10,853 ＋ 本模块 `extension/mcp` 1,762 ＋ `subcommand/Mcp*`·`BrowserLauncher` 1,193） | 见 map/07 |
| ├ **codemode / tool-search / virtual-models / nested-tool-calls（R5 面外增量不进图，存量单元保留）** | `extensions/codemode` 1,360 + 新包 `packages/codemode` 2,597 + `extensions/tool-search` 265 + `core/virtual-models.ts` 238 + `core/nested-tool-calls.ts` 263 | 4,723 | **0** | **0%** |
| └ **可执行验收规格 examples/**（→ L8） | 新锚点 `examples/`（102 个 `.ts`，16,033 行；L8 分母 **88**＝102−8 外部运行时−1 R5−5 范围面主动） | 16,033 | **0** | **0%** |
| 测试 | `test/` | 71,535 | 12,413 | 17.4% |
| **（作废，R1）** `experimental/{micro,mini}/` | 已删除 | **0** | — | — |

> java 侧 14,886 行按包：`core` 6,399（含 `core/slash/` 747、`core/session/` 118）/ `rpc` 1,331 / `extension` 2,479（其中 MCP 1,762）/ `skill` 613 / `cli` 607 / `subcommand` 1,780（其中 MCP·Browser 1,193）/ `export` 489 / `mode` 450 / `prompt` 283 / `modes` 285 / `spi` 44 / 顶层 126。
> pi 侧 `core` 35,393；`modes` 23,938；`extensions` 7,456；`utils` 3,770；`cli` 1,959；`experimental` 9,950（**权 0**）；根 `.ts` 3,686。
> **`interactive-mode.ts` 7,109 行**。
> **扩展系统的 812 行在 `pi-java-agent-core/src/main/java/com/pijava/agent/hook/`**，不在本模块 —— 且**没有桥接到 `PiExtension`**（见 §扩展系统）。
> ⚠️ LOC 比只能作「大致铺满」的粗指标（Java record/javadoc/显式类型比 TS 冗长）；判完成度一律看正文判定列。MCP 面 java LOC 大于 pi 不代表「超额对齐」。

---

## A. CLI 参数对齐

pi `src/cli/args.ts`（479 行）共 **41 个 flag** + `--` + `@files` + 扩展注册 flag；新增 `--no-mcp` 属 MCP 面（见 `map/07`），不计入本图 43 个能力单元。本图计 **40 个主流 flag + `--` + `@files` + 扩展注册 flag = 43 单元**。
java `cli/ArgsParser.java` 共 **43 个 `@Option`**，其中 **4 个 java 独有**（`--base-url:36`、`--port:63`、`--debug:144`、`--trace-payloads:147`）。

已对齐 43 条（逐条证据见 git 历史）。**Σ权重 62 / Σ(w×c) 62.0 / 加权 100%**

### B. 子命令对齐

pi `main.ts:617`（`mcp`）＋ `handlePackageCommand`/`handleConfigCommand`/`runAuthCommand` 共 **8 个**；java `SubcommandHandler.java:11-12` 同为 8 个。

已对齐 8 条（逐条证据见 git 历史）。**Σ权重 15 / Σ(w×c) 15.0 / 加权 100%**

---

## C. Slash 命令对齐

pi `core/slash-commands.ts:18-43` 共 **24 条**；java `CommandRegistry.withBuiltins()` 注册 **26 条**（后 pi 24 条全覆盖＋独有 2 条）。
java 独有 **2 条**（`/help`、`/create-skill`），不计入 pi 侧单元。

已对齐 25 条（逐条证据见 git 历史）。**Σ权重 38 / Σ(w×c) 38.0 / 加权 100%**

---

## D. 扩展系统对齐（41 事件）

pi 的扩展事件面 = `ExtensionAPI.on()` 的 **41 个重载**（`core/extensions/types.ts:1569-1636`）。`ExtensionEvent` 联合在 `:1377-1409`。
`on()` 返回类型仍为 **`() => void`**（全部 41 条，可运行时退订，实现 `extensions/loader.ts`）。
pi-java 的**扩展 SPI** = `PiExtension.java`，只有 **2 个钩子**：`register(ExtensionContext)`（`:18`）与 `sessionStartResources()`（`:29`）。
pi-java 的**引擎钩子** = `pi-java-agent-core/.../agent/hook/HookSystem.java`，**13 个**（`onBeforeRun:43`…`onPrepareNextTurn:103`），**但 `ExtensionContext` 不暴露 `hookSystem()`** ⇒ 扩展拿不到。

判定口径：按「**扩展能否订阅该事件**」判；引擎侧有同形钩子的记 **存疑** 并在证据列写明「引擎钩子 X（未桥接）」。

已对齐 41 条（逐条证据见 git 历史）。**Σ权重 83 / Σ(w×c) 83.0 / 加权 100%**

### D 域窄差（D1–D8）

| # | 内容 | 等级 |
|---|---|---|
| D1 | `context` 快照用引用相等（pi `===` 同构）；java 无 `structuredClone`（Message 不可变，无需） | 无影响 |
| D2 | `tool_call`/`tool_result` 事件不做 pi 的逐工具判别子类型（`BashToolCallEvent` 等 9 支），`input` 恒 map 形 | [内部] |
| D3 | `message_update` 的 `message` 提取自 StreamEvent partial（V1 通过：`fromPartial` 投影；无快照帧不发射） | 边缘场景 |
| D5 | `extension_error` TUI 暂不展示（RPC 线已逐字）；TUI 通知面归 D′ 域 | [用户可见，边缘] |

### D 域窄差（D9–D23）

| # | 内容 | 等级 |
|---|---|---|
| D9 | `message_end` 观察处理器在 sink 时点与结果处理器同批火一次（看到链中值）；pi 观察与结果同序但后续观察看到链终值。替换对宿主监听者/TUI/RPC 的效果与 pi 原地改写一致 | [内部] |
| D10 | ~~`user_bash` 的 `{operations}` 臂不移植~~ **已闭环（B286）**：`UserBashEventResult` 改为 sealed 联合（`Operations(ShellExecutor)` / `Result(ShellResult)`），会话 bash 按臂执行并落会话；`spawnHook` 仍无对位（D240） | [内部] |
| D11 | `before_agent_start` 的 `systemPromptOptions` 可变对象不移植（java 无 BuildSystemPromptOptions 面；selectedTools 编辑臂随之不移植）；`systemPrompt` 覆盖是 harness **全局态**（粘性），非 pi 的 per-prompt options；事件的 `systemPrompt` 是配置原值（渲染拼装在引擎内） | [模型可见：覆盖持续生效] |
| D12 | `input` 事件 `images` 恒 null；扩展来源消息以 `source="extension"` 进入 processPrompt | [内部，边缘] |
| D14 | `previousSessionFile`/`targetSessionFile` 载荷装会话 **id**（裁决⑤：SQLite/JSONL 仓无文件路径面） | [内部] |
| D15 | boundary 预览的 `contextMessages`/`llmMessages` 同源（java 无 pi 的 AgentMessage→LLM 二次转换缝） | [内部] |
| D16 | `session_before_fork` 的 `skipConversationRestore` 结果臂收下不消费（java fork 无恢复跳过面） | [内部] |
| D17 | `forkCopy`（整枝拷贝）映射 `before_fork{entryId:当前叶, position:"at"}`；pi 的 fork 恒显式 entryId | [内部] |
| D18 | pi 的 emitUserBash 模式间不对称（interactive catch＋不回落、rpc 传播）；java 单发射点（executeBash），异常统一沿调用方错误链 | [内部] |
| D19 | `before_agent_start` 的 result.message 落盘序为「custom 条目先于用户消息」（java 在 drive 前 append；pi 插在本轮 user 之后） | [内部，边缘] |
| D20 | /new 臂的 shutdown 在 start 之后（java 需先建会话才知目标 id；pi 先 teardown 后 create） | [内部] |
| D21 | boundary 预览 `pendingMessages` 由队列**文本**重建 UserMessage（custom 队列项不进预览）；~~java 无 `_pendingCustomMessages` 面~~ （四 flush 点对位） | [内部] |
| D22 | `session_before_compact` 载荷 `preparation.fileOps` 不译（java 的 fileOps 在 compact() 内收集，prepare 时点缺席） | [内部] |
| D23 | 原地 resume 的 shutdown/start 在**同一条 bus** 上发射（pi 是旧/新两条 runner）；内存路 switchTo 的 start 在新侧 firer 发射 | [内部] |

### D 域窄差（D24–D30）

| # | 内容 | 等级 |
|---|---|---|
| D24 | fork 携带当前 model/thinkingLevel（pi 经 persist→settings 重解析，效果同；java 无 persist 面故直接携带）；systemPrompt/activeTools/skills 重解析＋扩展 `start{fork}` 重激活（pi createRuntime 语义） | [内部] |
| D25 | 内存路 `switchTo` 旧会话对象不关闭（内存注册表的会话对象即存储本体，关即毁数据；pi 内存 SessionManager 无切回面——`switchSession` 恒要文件路径） | [内部] |
| D26 | 内存 CLI `--fork` 经 `forkCopy` ⇒ 发射 `start{fork}`（pi CLI fork 恒 `startup`；持久路不过 forkCopy＝startup，与 pi 一致） | [内部，测试路] |
| D27 | 内存 CLI `--fork` 的初始会话空转 `start{startup}` 后被弃置不 close（两步装配的存量形状；持久路无此问题） | [内部，测试路] |
| D28 | 内存 fork/clone 后旧会话已关但仍列在内存注册表（`/resume` 选到 ⇒ 拿到 closed 会话）；pi 内存态无切回面 | [内部，边缘] |
| D29 | 无 `extensionRunner.invalidate` 对位（pi dispose :1405 的 stale ctx 防御）；java 会话关闭后扩展持有的旧 ctx 仍可调共享 services | [内部，边缘] |
| D30 | providers 注册表 fork 间共享（初始装配注入缝无复制面）；扩展在 fork 两侧重复注册 provider 会叠加 | [内部，边缘] |

### D 域窄差（D31–D35）

| # | 内容 | 等级 |
|---|---|---|
| D31 | 替换 payload 类型不符 ⇒ 车道抛 `IllegalStateException`（请求失败）；pi 会把垃圾形状发给 provider 收 400——同属「扩展弄坏请求 ⇒ 请求失败」，失败点提前（裁决⑤） | [内部，边缘] |
| D32 | google 车道 payload 载体是 `GooglePayload(model, contents, config)` record（pi 是单一 `GenerateContentParameters` 对象；java SDK 三分参调用所致） | [内部] |
| D33 | `before_provider_headers` 事件头集＝models.json 三层（provider⨁model⨁override）合并值；pi 另有 `auth.headers` 与调用方显式 `options.headers` 两层（java 无生产者）。~~及 attribution 层~~——（attribution 并入钩前头集，pi transformHeaders 闭包对位）；余两层不改 | [内部] |
| D34 | null 抑制 **SDK 默认头**不可达（通道只作用于 extra["headers"] 合并集；pi 的 null 还能压 provider 默认头如 User-Agent）；合并集内 null＝删除全落 | [内部，边缘] |
| D35 | 响应头摊平口径：名按 SDK/JDK 原样大小写（pi fetch `Headers.entries()` 小写化），同名多值逗号连接（≈fetch 行为） | [内部] |

**说明（非窄差）**：google 车道不发 `after_provider_response`＝pi 同缺（pi google-generative-ai.ts/google-vertex.ts 全文件零 `onResponse` 命中）；SDK 车道只在成功获取后发、fetch 车道含错误状态——pi 自身两族口径即如此（§1.4）。

### D′. 扩展 UI 注入点（非独立计分视图）

pi `ExtensionUIContext` 共 **28 个成员**（`types.ts:149-323`）。当前实现与余量统一记录在 **E5-31**：13 个成员已有 TUI 实现，15 个成员仍待复核或实现。

D′ 与 E5-31 描述同一能力集合，**不再独立赋予权重或计入模块分母**，只在 E5-31 计分。

---

## 能力单元清单（其余）

### E1 配置文件 / 清单（5）

已对齐 5 条（逐条证据见 git 历史）。

**E1 汇总：对齐 5 / 缺失 0 / 存疑 0 / 合计 5 = 100%**；**Σ权重 10 / Σ(w×c) 10.0 / 加权 100%**

### E2 settings.json 键（57 计分 + 1 出分母 = 58 行）

pi 键全部来自 `core/settings-schema.ts:206-430`（`SettingsSchema`；`interface Settings` 在 `:471`，Omit 掉 schema-only 的 `queueMode`/`websockets`）；java 来自 `core/Settings.java:26-119`。
**判定为「对齐」需字段存在且有非零消费者**（getter/setter 不算消费者；——map/04 行（settings 键）与 map/05 行（屏/组件单元）是不同单元，不构成重复计分；先例 E2-11 证据引 `TreeNavigationFlow:169`）。

已对齐 52 条（逐条证据见 git 历史）。

| # | 键 | pi 行 | java 行 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| E2-22 | `enableInstallTelemetry` | `:280` | `:101` | 1 | **存疑** | 字段＋Install 访问器（`?? true`）；pi 两消费——changelog 版本推进后 ping `pi.dev/api/report-install`（**不移植**：pi.dev 是 pi 自有端点，复刻发送会污染其遥测，窄差 D55）＋provider 归因头（随 B253 落地） |
| E2-32 | `terminal` | `:319`（schema `:74-100`，7 键） | `:49`（7 键，`Settings.java:155-166`） | 1 | **存疑** | 字段形状已齐（7/7）；pi 消费面＝能力覆盖（hyperlinks/trueColor/images 短路终端探测）＋OSC 9;4 进度＋工具卡 inline 图片＋clearOnShrink——全部依赖 TamboUI 后端能力面或 java 没有的终端协议面（窄差 D56；可移植子集 OSC 9;4/clearOnShrink 随 TUI 屏域拍板〔变量③〕再裁） |
| E2-43 | `markdown` | `:379` | `:57` | 1 | **存疑** | java 2 键；`codeBlockIndent` 消费落地（`SettingsAccessors.getMarkdownCodeBlockIndent` 逐字回落→`MarkdownTheme` 现读缝→代码块缩进）；`mermaid` accessor 补上不消费（`getMarkdownMermaid` 逐字 off/final⇒streaming）——本体挂 R7（map/05），**D57 维持**（mermaid 围栏按普通代码块渲染） |
| E2-45 | `codemode`（`CodemodeSettings`） | `:381`（schema `:154-167`） | — | 2 | **出分母** | java 无 codemode 子系统（B161），R5 面外——不进 `bin pi` 主流发布版的不做对齐、不进分母；未来立项再入列 |
| E2-50 | `websocketConnectTimeoutMs` | `:402` | `:77` | 1 | **存疑** | 字段＋Network 访问器（缺席无兜底，settings-manager.ts:935-937 对位）；pi 唯一消费者＝openai-codex-responses 的 WebSocket 传输（connect 握手超时，默认 15s、0 禁用）；java 无 WS 传输（codex 车道走 HTTP/SSE）⇒ **窄差 D58** |
| E2-54 | `fullscreenCopyOnSelect` | `:424` | `:117` | 1 | **存疑** | 字段＋UiPrefs 访问器（`?? true`）；消费依赖 alt-screen 选区/复制子系统（map/05 S2a-d 未排期）⇒ **窄差 D59** |

**E2 汇总：对齐 52 / 缺失 0 / 存疑 5 / 出分母 1 / 合计 58**；**Σ权重 87 / Σ(w×c) 84.5 / 加权 97.1%**
> `queueMode`（`:436`）/`websockets`（`:442`）只存在于 `SettingsSchema` 输入侧，`interface Settings`（`:471`）显式 Omit，非 settings.json 键，不列单元。

### E3 会话管理行为（24）

已对齐 23 条（逐条证据见 git 历史）。

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E3-3 | `-r` 交互式会话选择器 | `main.ts:417` `selectSession`（`cli/session-picker.ts` 55 行 + `session-selector.ts` 1,045 + `session-selector-search.ts` 194） | ✅ tui `component/session/`（`SessionSelectorComponent`/`SessionListModel`/`SessionListRenderer`/`SessionSelectorHeader`/`SessionTreeBuilder`/`SessionSelectorSearch`）＋`SessionSelectorRunner`＋coding-agent SPI | **0（转出 L7）** | **转出** | 新锚点 `session-selector-search.ts` 的分层评分（名称/正文/模糊三档）是 TUI 屏域重写，选择器本体与搜索排序都在 `pi-java-tui` ⇒ 按 docs/04 归属**转 L7**，L5 保留证据与指针、权重 0、不进本层分母。 |

**E3 汇总：对齐 23 / 缺失 0 / 存疑 0 / 转出 1 / 合计 24**；**Σ权重 38 / Σ(w×c) 38.0 / 加权 100%**

### E4 资源发现（7）

已对齐 7 条（逐条证据见 git 历史）。

**E4 汇总：对齐 7 / 缺失 0 / 存疑 0 / 合计 7 = 100%**；**Σ权重 14 / Σ(w×c) 14.0 / 加权 100%**

### E5 扩展 API 面（35）

pi 面 = `ExtensionAPI`（`core/extensions/types.ts:1564-1891`）；java 面 = `PiExtension` + `ExtensionContext`（`extension/*.java`）。

已对齐 33 条（逐条证据见 git 历史）。

| # | 能力单元 | pi 行 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E5-31 | 扩展 UI 注入（TUI） | `types.ts:149-323` | ⚠️ `TuiExtensionUI`（13 方法：对话四件套＋custom＋notify/setStatus/setWidget/setFooter/setHeader/setEditorText/getEditorText）＋widget/footer/header/状态段注入层；余 15 成员 default＝pi noOp 语义、TUI 臂挂账 | **0（转出 L7）** | **转出** | 新锚点新增 `blocked`、对话框堆叠、`KeybindingHints`，全部是 TUI 渲染/交互面 ⇒ 按 docs/04 归属**转 L7**；API 侧的 `KeybindingHints` 另见 **D220**。 |
| E5-33 | 扩展示例库（可执行验收规格） | 新锚点 `examples/`（102 个 `.ts`；L8 分母 88） | `pi-java-examples` 已移植 **88 件**，见 docs/07 | **0（转出 L8）** | **转出** | examples 验收规格归 **L8**，L5 不保留第二计分入口（避免同一工作计两次）；主流 examples 全做 Java 移植，Chord plugin 按 R5 排除。L8 分母 88＝102−8 外部运行时（D232）−1 R5−5 范围面主动（D237），**88/88＝100%**；D235／D244 保留 |

**E5 汇总：对齐 33 / 缺失 0 / 存疑 0 / 转出 2 / 合计 35**；**Σ权重 53 / Σ(w×c) 53.0 / 加权 100%**
> **L8 前置包**：本表 35 行不增不减。补落 19 个宿主成员，均为对齐：`ExtensionContext` 10 项、`ExtensionSessionView` 9 项。窄差 D233（无 `findOfType`/`classify`）、D234（无 `newSession.setup`）。
> **L8 分母修正**：`overlay-test`／`overlay-qa-tests`、`snake`／`space-invaders`、`mac-system-theme` 五件出分母——无可证明的宿主行为或平台无对位，且 overlay 全参受 D99 约束。examples 分母 93→**88**；本表 E5 段行数不变（examples 是转出行）。详情见 docs/07。

### E6 包管理器（18）

已对齐 18 条（逐条证据见 git 历史）。

**E6 汇总：对齐 18 / 存疑 0 / 缺失 0 / 合计 18 = 100%**；**Σ权重 34 / Σ(w×c) 34.0 / 加权 100%**

### E7 RPC 面（7）

已对齐 7 条（逐条证据见 git 历史）。

**E7 汇总：对齐 7 / 缺失 0 / 存疑 0 / 合计 7 = 100%**；**Σ权重 13 / Σ(w×c) 13.0 / 加权 100%**

---

## G. 主流新增子系统（MCP / codemode / tool-search / virtual-models / nested-tool-calls）

这些是旧基准**不存在**的 pi 侧子系统（新包＋新扩展），均在 `bin pi` 主流路径上（`src/extensions/index.ts` 注册为 builtin 扩展，每会话加载）。
**MCP 面（G-1..G-5）的判定、证据与 LOC 整体在 `map/07-mcp.md`**；本表保留行与判定作汇总指针。tool-search、virtual-models 和 nested-tool-calls 已有 Java 实现；codemode 按 R5 出分母。

已对齐 8 条（逐条证据见 git 历史）。

| # | 能力单元 | pi 侧证据 | 权重 | 判定 | 说明 |
|---|---|---|---|---|---|
| G-6 | codemode 沙箱运行时（quickjs-wasi host/worker/protocol/prelude） | `packages/codemode`＋`extensions/codemode/execute.ts` | **0（R5）** | **面外** | 不对齐、不进新分母 |
| G-7 | codemode 工具（执行 JS、描述/inlineBudget、`mode: on\|only`、renderer） | `extensions/codemode/tool.ts`、`renderer.ts`；`settings-schema.ts` | **0（R5）** | **面外** | 不对齐、不进新分母 |

**G 当前口径**：G-1..G-5 只作 map/07 指针，不重复计分；G-6/G-7 按 R5 权重 0；G-8/G-9/G-10 对齐。

> **模块归属**：MCP 面的 Java 代码主在**新模块 `pi-java-mcp`**（主源 10,853 行）；工具与资源的 `AgentTool` 适配在本模块 `extension/mcp`（1,762 行）—— `AgentTool` 在 agent-core，而 `pi-java-mcp` 看不见它（`ai ← mcp`）。映射裁决与 B205 见 map/07。
> 部分落地的行记「存疑」（形状/一半存在），**不拆行**（拆行会改权重层归属）。

### E8 边缘/面外子系统（与 E3/C/G 重叠，不计入模块合计）

已对齐 4 条（逐条证据见 git 历史）。

| # | 能力单元 | pi 侧证据 | 权重 | 判定 |
|---|---|---|---|---|
| E8-4 | 扩展加载器依赖延迟（jiti lazy / Bun·SEA static） | `core/extensions/jiti-loader.ts` 3 + `jiti-static-loader.ts` + `virtual-modules.ts` 38 | 1 | **缺失（不适用）** |
| E8-5 | ~~`experimental/micro`~~ | **整块被 `7fd478a2e` 删除 ⇒ R1 作废（权重 0，移出分母）** | ~~1~~ | **作废** |
| E8-6 | WSL / zip 工具 | `utils/wsl.ts` + `utils/zip.ts` | 1 | **缺失**（zip 已随T5 收编〔`core/util/ZipArchives`，裁决① JDK 形 D79〕；余 wsl——java 无 WSL 调用面） |

**E8 汇总（不计入模块）：对齐 3 / 缺失 3 / 存疑 0 / 作废 1**；**Σ权重 7 / Σ(w×c) 4.0 / 加权 57.1%**（E8-2/E8-3 对齐、E8-1 随 E3-24 已对齐——旧行漏算 E8-1，逐行实算更正）

### L5 新单元

已对齐 6 条（逐条证据见 git 历史）。

| # | 能力单元 | pi 证据 | 权重 | 判定 | 证据 |
|---|---|---|---:|---|---|
| N-4 | llama.cpp 默认端口 9931 | `extensions/llama/provider.ts:25` | 1 | **出分母（R2／F1）** | Java 无 llama 扩展（R2 范围外）；本地 OpenAI 兼容服务经 `models.json` 已可接 |

**新单元合计：Σ权重 10（计分 9；llama 1 按 F1／R2 出分母）／Σ(w×c) 9.0。**

---

## 整块视图（非独立计分）

**Σ权重 461 / Σ(w×c) 461.0 = 100%**
> 逐域实算：A 62.0/62、B 15.0/15、C 38.0/38、D 83.0/83、E1 10.0/10、E2 87.0/87、E3 38.0/38、E4 14.0/14、E5 53.0/53、E6 34.0/34、E7 13.0/13、G 5.0/5，合计 452.0；新单元 9.0（N-1..N-3、N-5..N-7；N-4 llama 出分母）。无缺口行；窄差 D274／D275／D277／D278 均已登记（D276 未用）。

整块视图不再重复计分。examples 只在 E5-33 保留一个计分入口；扩展 UI 只在 E5-31 计分。JSON 主题、包管理、会话选择器等已有对应细单元，不再保留“整块全缺”的旧结论。
