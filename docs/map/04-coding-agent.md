# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 【背景摘要】本图是 coding-agent（CLI 宿主 / 产品层）的全量加权盘点：CLI 参数、子命令、slash 命令、扩展系统、settings 键、会话管理、包管理器等逐单元判定与证据。开放缺口的权威台账是 [`../04-open-items-register.md`](../04-open-items-register.md)。

## 基准与口径

- 范围：`D:\workplaceForai\pi-java\pi-java-coding-agent` ↔ `D:\workplaceForai\pi\packages\coding-agent`。
- 基准：pi @ `ea448f454`（2026-10-11，v1.1.0-22）；pi-java @ `edc8749d`（2026-10-11，main）。
- 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差，或台账未结）。
- 所有证据为 `file:line`；pi 侧路径省略 `D:\workplaceForai\pi\packages\coding-agent\` 前缀，java 侧省略 `D:\workplaceForai\pi-java\pi-java-coding-agent\src\main\java\com\pijava\coding\agent\` 前缀（跨模块证据会写明模块）。
- **权重口径**（用户已认可）：权重 = 用户可观察影响 × 频率，**不含排期优先级**。`3` = 每轮对话都走 / 默认路径；`2` = 每次会话走 / 常用命令·参数·配置键；`1` = 低频·边缘·纯内部；`0` = 非目标（排除出分母）。完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。加权汇总见文末。
- **R5 可达性**：只算 `bin pi` 主流可达的；`coding-agent/src/experimental/**` 一律权重 0。
- **R1 作废**：`src/experimental/micro/**` 与 `src/experimental/mini/**` 已被 pi 删除（`7fd478a2e`）⇒ 相关单元权重 0、移出分母。
- **MCP 面整体移出本图**：MCP 注册表 / `pi mcp` CLI / MCP 工具·渲染器接线（含 B161/B162/B203/B214/B218/B221）由 [`07-mcp.md`](07-mcp.md) 覆盖；本图保留单元编号与判定，证据列只给指针，不展开。

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
| └ **可执行验收规格 examples/** | `examples/`（105 个 `.ts`） | 16,033 | **0** | **0%** |
| 测试 | `test/` | 71,535 | 12,413 | 17.4% |
| **（作废，R1）** `experimental/{micro,mini}/` | 已删除 | **0** | — | — |

> java 侧 14,886 行按包：`core` 6,399（含 `core/slash/` 747、`core/session/` 118）/ `rpc` 1,331 / `extension` 2,479（其中 MCP 1,762）/ `skill` 613 / `cli` 607 / `subcommand` 1,780（其中 MCP·Browser 1,193）/ `export` 489 / `mode` 450 / `prompt` 283 / `modes` 285 / `spi` 44 / 顶层 126。
> pi 侧 `core` 35,393；`modes` 23,938；`extensions` 7,456；`utils` 3,770；`cli` 1,959；`experimental` 9,950（**权 0**）；根 `.ts` 3,686。
> **`interactive-mode.ts` 7,109 行**。
> **扩展系统的 812 行在 `pi-java-agent-core/src/main/java/com/pijava/agent/hook/`**，不在本模块 —— 且**没有桥接到 `PiExtension`**（见 §扩展系统）。
> ⚠️ LOC 比只能作「大致铺满」的粗指标（Java record/javadoc/显式类型比 TS 冗长）；判完成度一律看正文判定列。MCP 面 java LOC 大于 pi 不代表「超额对齐」。

---

## A. CLI 参数对齐

逐条数：pi `src/cli/args.ts`（479 行）共 **41 个 flag** + `--` + `@files` + 扩展注册 flag。新增 **`--no-mcp`**（`:191`）属 MCP 面，见 `map/07`，**不计入本图 43 个能力单元**。本图计 **40 个主流 flag + `--` + `@files` + 扩展注册 flag = 43 单元**。
java `cli/ArgsParser.java` 共 **43 个 `@Option`**，其中 **4 个 java 独有**（`--base-url:36`、`--port:63`、`--debug:144`、`--trace-payloads:147`）。

| # | 参数/子命令 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `--help`/`-h` | `args.ts:92` | ✅ `ArgsParser.java:54` | 2 | 对齐 | `Main.java:57` exit 0 |
| 2 | `--version`/`-v` | `args.ts:94` | ✅ `ArgsParser.java:57` | 1 | 对齐 | `Main.java:61` |
| 3 | `--mode` | `args.ts:96` | ✅ `ArgsParser.java:60` | 2 | 对齐 | java 多 `web`（`Args.java:26`） |
| 4 | `--continue`/`-c` | `args.ts:111` | ✅ `ArgsParser.java:48` | 2 | 对齐 | `SessionPersistence.java:169` |
| 5 | `--resume`/`-r` | `args.ts:113` | ✅ `ArgsParser.java:51` | 2 | **对齐** | **批 14（2026-10-23，docs/25）**：选择器全链——`SessionSelectorProvider` SPI（裁决①）＋tui `SessionSelectorComponent`（pi 1,045 行域）＋`SessionSelectorRunner` 启动壳；取消 ⇒ `No session selected` exit 0；SPI 缺席报 D70 干净错误 |
| 6 | `--provider` | `args.ts:115` | ✅ `ArgsParser.java:27` | 2 | 对齐 | |
| 7 | `--model` | `args.ts:117` | ✅ `ArgsParser.java:30` | **3** | 对齐 | 均支持 `provider/id` 与 `:thinking` |
| 8 | `--api-key` | `args.ts:119` | ✅ `ArgsParser.java:33` | 2 | 对齐 | |
| 9 | `--system-prompt` | `args.ts:121` | ✅ `ArgsParser.java:39` | 1 | 对齐 | |
| 10 | `--append-system-prompt` | `args.ts:123` | ✅ `ArgsParser.java:42` | 1 | 对齐 | 均 repeatable |
| 11 | `--name`/`-n` | `args.ts:126` | ✅ `ArgsParser.java:66` | 1 | 对齐 | `AgentSession.java:529` |
| 12 | `--no-session` | `args.ts:132` | ✅ `ArgsParser.java:69` | 1 | **对齐** | **批 14（docs/25 T14）**：web 臂 `--no-session` 赢（裁决⑤，**B54 销号**）——`resolvePersistentWeb` ephemeral 早退，`WebNoSessionTest` 钉无持久仓写入 |
| 13 | `--session` | `args.ts:134` | ✅ `ArgsParser.java:72` | 2 | **对齐** | **批 14（docs/25 T6/T7）**：pi `main.ts:391-413` 逐字——⓪ 路径快道→①② local（B53 阶梯）直开→③ global 提示 `Session found in different project`＋`promptConfirm` fork（EOF/n ⇒ `Aborted.` exit 0）；not_found 文案逐字＋`SessionResolutionExit` 干净错误面（裁决②） |
| 14 | `--session-id` | `args.ts:136` | ✅ `ArgsParser.java:75` | 2 | **对齐** | **批 14（docs/25 T6/T8）**：pi `main.ts:436-448` 逐字——只项目内精确（`findLocalById` header-only）；缺 ⇒ `Warning: No project session found with id ...`＋`createWithId` 带 id 新建（修丢 id 现状） |
| 15 | `--fork` | `args.ts:138` | ✅ `ArgsParser.java:78` | 1 | **对齐** | **批 14（docs/25 T6/T9）**：pi `main.ts:368-390` 逐字——`--session-id` 目标查重（`Session already exists with id` exit 1）＋`forkFrom {id}`；`validateForkFlags` 互斥诊断 |
| 16 | `--session-dir` | `args.ts:140` | ✅ `ArgsParser.java:81` | 1 | 对齐 | `AgentSession.java:131/164/180` |
| 17 | `--models` | `args.ts:142` | ✅ `ArgsParser.java:84` | 2 | **对齐** | **批 14（docs/25 T17）核销**：消费链早已通（账面滞后）——`AgentSession.java:374` `ModelScoping.resolve` 吃 `args.models()` → `:677 setScopedModels` → `PiTuiApp.java:665` Ctrl+P 轮转＋`:423-428` 初始模型（pi `main.ts:499-535` 对位） |
| 18 | `--no-tools`/`-nt` | `args.ts:147` | ✅ `ArgsParser.java:93` | 1 | 对齐 | `-nt` 由 `:258` 展开；**批 1 补注**（B236/docs/11）：注册面语义对齐——空 allowlist ⇒ 注册面空（pi `noTools:"all"` ⇒ `getAllTools()==[]`，`DefaultToolsSelectionTest.noToolsEmptiesTheRegistry`） |
| 19 | `--no-builtin-tools`/`-nbt` | `args.ts:149` | ✅ `ArgsParser.java:96` | 1 | 对齐 | **批 1 补注**（B236/docs/11）：注册面保留八件、激活面空（pi `noTools:"builtin"` 语义；扩展工具默认激活臂 java 无扩展面，免登） |
| 20 | `--tools`/`-t` | `args.ts:151` | ✅ `ArgsParser.java:87` | 2 | **对齐** | **批 1（2026-10-18，B236/docs/11）**：`*` 通配（`ToolNameMatcher`，mcp-servers.ts:11-42 对位）、`+name`/`-name` 修饰符（`ToolSelection`，settings-manager.ts:83-137＋sdk.ts:280-294 对位）、`mcp__` 保留策略（裁决③）、解析期 `getToolListError` 逐字校验；窄差 D1：诊断前缀恒 `--tools`（picocli 不暴露实敲别名） |
| 21 | `--exclude-tools`/`-xt` | `args.ts:162` | ✅ `ArgsParser.java:90` | 1 | **对齐** | **批 1（2026-10-18，B236/docs/11）**：`*` 通配 denylist（同一 `ToolNameMatcher`），注册面剔除（`_isAllowedTool` 对位 `Resolved.isAllowed`），excluded 优先于 allowlisted |
| 22 | `--thinking` | `args.ts:167` | ✅ `ArgsParser.java:45` | 2 | 对齐 | 两侧都是 7 档 `off..max`（`ThinkingLevels.java:33`） |
| 23 | `--print`/`-p` | `args.ts:177` | ✅ `ArgsParser.java:126` | **3** | 对齐 | pi 吞下紧随的非 `@`/`-` 参数（`:179-181`） |
| 24 | `--export` | `args.ts:184` | ✅ `ArgsParser.java:129` | 1 | **对齐** | **批 14（docs/25 T13）**：第二位置参数＝输出路径（`Main.java` `parsed.messages().getFirst()`）；文案逐字 `Exported to: <path>`／`Error: <msg>` exit 1；窄差 D77（默认名 `pi-java-session-*` 品牌族） |
| 25 | `--extension`/`-e` | `args.ts:186` | ✅ `ArgsParser.java:99` | 2 | **对齐** | **批 14（docs/25 T15）**：`AgentSession.loadExtensions` 显式 JAR 恒加载（`-ne` 旁路，pi `resource-loader.ts:578/690` 对位）；`-ne` 裸早退不变；窄差 D78（不收 `builtin:<name>`） |
| 26 | `--no-extensions` | `args.ts:189` | ✅ `ArgsParser.java:102` | 1 | 对齐 | `AgentSession.java:1142` |
| 27 | `--skill` | `args.ts:193` | ✅ `ArgsParser.java:105` | 1 | 对齐 | `SkillDiscovery.java:36` |
| 28 | `--prompt-template` | `args.ts:196` | ✅ `ArgsParser.java:111` | 1 | 对齐 | `PromptTemplates.java:47` |
| 29 | `--theme` | `args.ts:199` | ⚠️ `ArgsParser.java:117` | 1 | **存疑** | pi 收 **JSON 主题名或文件**；java 只收 `.tcss` 路径（pi-java-tui `PiTheme.java:97`）——**批 14（docs/25 §4）重排批 20**（依赖 JSON 主题系统 E4-4/B163，现做只能是空壳） |
| 30 | `--use-theme` | `args.ts:202` | ✅ `ArgsParser.java:126` | 1 | **对齐** | **批 14（docs/25 T16）**：flag＋缺值/`-` 起头两诊断（`--use-theme requires a theme name` 逐字）＋消费 `PiTuiLauncher.themeFrom`（优先于 `--theme`）＋`-r` 选择器 SPI `useThemeOverride`（pi `main.ts:681-683` 对位） |
| 31 | `--no-skills`/`-ns` | `args.ts:210` | ✅ `ArgsParser.java:108` | 1 | 对齐 | |
| 32 | `--no-prompt-templates`/`-np` | `args.ts:212` | ✅ `ArgsParser.java:114` | 1 | 对齐 | |
| 33 | `--no-themes` | `args.ts:214` | ✅ `ArgsParser.java:120` | 1 | 对齐 | |
| 34 | `--no-context-files`/`-nc` | `args.ts:216` | ✅ `ArgsParser.java:123` | 2 | 对齐 | 消费者 `AgentSession.java:423-425`（`discover` 由此门控） |
| 35 | `--list-models` | `args.ts:218` | ✅ `ArgsParser.java:132` | 1 | 对齐 | 均可选搜索词（`cli/list-models.ts:29`） |
| 36 | `--tui-mode` | `args.ts:225` | ✅ `ArgsParser.java:138` | 1 | 对齐 | 默认均为 fullscreen |
| 37 | `--verbose` | `args.ts:239` | ✅ `ArgsParser.java:141` | 1 | 对齐 | |
| 38 | `--approve`/`-a` | `args.ts:241` | ✅ `ArgsParser.java:150` | 2 | 对齐 | |
| 39 | `--no-approve`/`-na` | `args.ts:243` | ✅ `ArgsParser.java:153` | 1 | 对齐 | |
| 40 | `--offline` | `args.ts:245` | ✅ `ArgsParser.java:135` | 1 | **对齐** | **批 14（docs/25 裁决④）**：java 启动网络操作全集＝模型目录刷新，已被 `ModelCatalogRefresh.java:77` 门控；pi 其余 `PI_OFFLINE` 消费点在 java 无对应子系统（version-check/self-update/install-telemetry/package-update-check 均不存在）——按「pi 零消费⇒声明面即全量」同族口径（批 12 裁决③）；触发条件入 docs/04 §4 |
| 41 | `--`（选项终止） | `args.ts:83-90` | ✅ `ArgsParser.java:168-176` | 1 | **对齐** | **批 14（docs/25 T12）**：首个 `--` 预分流——后段 `@x` ⇒ fileArgs、其余 ⇒ messages（含 `-`/`--` 起头）；修 `-- -foo` 误判 `Unknown option`；夹具×3 |
| 42 | `@files` 位置参数 | `args.ts:248`（另 `:85-86`） | ✅ `ArgsParser.java:204-205` | 2 | 对齐 | 两侧都剥离 `@` 前缀进 `fileArgs` |
| 43 | 扩展注册的 CLI flag | `args.ts:250-259` + `registerFlag`/`getFlag`（`extensions/types.ts:1664/1680`） | ❌ | 1 | **缺失** | java `ArgsParser.java:206-213` 只把未知 flag 收进 `unmatched`，**无 `registerFlag` API**——**批 14（docs/25 §4）重排批 15/E5**（是扩展 API 面工作，`parsedUnmatched` 已留料） |

**汇总：对齐 41 / 缺失 1 / 存疑 1 / 合计 43 = 95.3%**；**Σ权重 62 / Σ(w×c) 60.5 / 加权 97.6%**
> 批 14（2026-10-23，docs/25）：**11 行翻动**——A-5/A-12/A-13/A-14/A-15/A-24/A-30/A-40/A-41 存疑⇒对齐、A-17/A-25 缺失⇒对齐（A-17 核销）；A-29 重排批 20、A-43 重排批 15（判定不动，证据改注）。Σ 50.0→**60.5/62 ≈ 97.6%**。
> pi 新增 `--no-mcp`（`args.ts:191` → `disabledBuiltinExtensions:["mcp"]`，`main.ts:787`）属 MCP 面，判定归 map/07。
> 本锚点改判：`--tools`（A-20）/ `--exclude-tools`（A-21）对齐 → **存疑**（pi 现收 pattern `*`；`--tools` 另收 `+name`/`-name`/`mcp__`，java 仅裸名切分）。
> 批 1（2026-10-18，B236/docs/11）：A-20/A-21 存疑 → **对齐**（三层语义全落，窄差 D1 登记）。

### B. 子命令对齐

pi `main.ts:617`（`mcp`）＋ `handlePackageCommand`/`handleConfigCommand`/`runAuthCommand` 共 **8 个**；java `SubcommandHandler.java:11-12` 同为 8 个。

| # | 子命令 | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `install <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:54` | 2 | **存疑** | java 只支持**本地 JAR 路径或 http(s) URL**（`ExtensionPackageManager.java:76/93`）；pi 支持 npm / git / local 三源 |
| 2 | `remove <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:74` | 2 | **存疑** | 同上 |
| 3 | `uninstall <source>`（别名） | ✅ | ✅ `SubcommandHandler.java:41` | 1 | 对齐 | |
| 4 | `update [source\|self\|pi]` | ✅ `package-manager-cli.ts:449/458` | ❌ `PackageCommand.java:20` | 2 | **缺失** | java 只打印 `SELF_UPDATE_NOTE`，**无 self-update、无扩展更新、无模型目录刷新子命令**（⚠️ 目录刷新机制本身已在核心自动跑，见 E6-12） |
| 5 | `list` | ✅ `package-manager-cli.ts:996` | ⚠️ `PackageCommand.java:109` | 2 | **存疑** | pi 按 user/project 分组 + `installedPath`（`:1009-1010`）；java 平铺 |
| 6 | `config [-l]` | ✅ `package-manager-cli.ts:813`（**TUI**） | ⚠️ `ConfigCommand.java:15` | 2 | **存疑** | java 是**非交互** `config enable\|disable` |
| 7 | `auth <cmd>` | ✅ check / print-api-key / print-bearer-token（`auth-command.ts:52-60`） | ✅ `AuthCommand.java:42-47` | 2 | 对齐 | java 是**超集**：多 `oauth-login`、`profile set/unset/list/set-key` |
| 8 | `mcp` | ✅ `main.ts:617`（`extensions/mcp/cli.ts` 599 行） | ✅ `SubcommandHandler.java:44-47` | 2 | 对齐 | java 有 `pi mcp`（`subcommand/Mcp*`，1,193 行）；**子命令逐项判定与明细整体见 `map/07-mcp.md`，本图不展开** |

**汇总：对齐 3 / 缺失 1 / 存疑 4 / 合计 8 = 37.5%**；**Σ权重 15 / Σ(w×c) 9.0 / 加权 60.0%**

---

## C. Slash 命令对齐

pi `core/slash-commands.ts:18-43` 共 **24 条**（两锚点间零变动，`/bug` 保留）；java `CommandRegistry.withBuiltins()`（`core/slash/CommandRegistry.java:95-101`）注册 **24 条**。
java 独有 **2 条**（`/help`、`/create-skill`，`MiscCommands.java:34`、`SkillsCommands.java:30`），不计入 pi 侧单元。
pi 另有 **`/skill:name` 命令族**（`enableSkillCommands` 默认 true，`interactive-mode.ts:805-815`）—— 单列（下表 #25，批 2 已对齐：注册面＝补全目录＋提交透传，非真命令）。

| # | 命令 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `/settings` | `:20` | ⚠️ `SettingsCommands.java:22` | 2 | **存疑** | java 只返回 `UI_SETTINGS` marker（`:26`）；pi `settings-selector.ts`（995 行） |
| 2 | `/model` | `:21` | ⚠️ `ModelCommands.java:21` | 2 | **存疑** | pi 支持 `/model <provider/model>` 直设；java `argumentHint()` 空且忽略 args |
| 3 | `/tree` | `:22` | ⚠️ `SessionCommands.java:61` | 1 | **对齐** | 批 8（docs/18）：marker 拆分＋原地导航＋摘要三选流；**批 9（docs/19）**：选择器本体＝entry 树落地（`TreeSelectorOverlay`＋`TreeListModel`＋`TreeListRenderer`，map/05 S12/S13 对齐，D37/D40 销号，窄差 D43–D46/D48） |
| 4 | `/thinking` | `:23` | ❌ — | 2 | **缺失** | java 无此命令（CLI/RPC 有，slash 面无） |
| 5 | `/scoped-models` | `:24` | ⚠️ `ModelCommands.java:29` | 1 | **存疑** | pi `scoped-models-selector.ts` 401 行。**批 3b（2026-10-14）**：写入的 patterns 已被消费——`ModelScoping` 读 `--models ?? enabledModels` 解析 scope，驱动初始模型与 Ctrl+P/RPC 轮转（B137 销号）；仍缺 pi 的选择器 UI（＋/-/裸加 CLI 已齐） |
| 6 | `/export` | `:25` | ⚠️ `MiscCommands.java:38` | 2 | **存疑** | pi 默认 HTML、路径可选；java `isBlank()` 时直接返回用法（`:40-41`） |
| 7 | `/import` | `:26` | ✅ `MiscCommands.java:61` | 1 | 对齐 | |
| 8 | `/share` | `:27` | ⚠️ `MiscCommands.java:76` | 1 | **存疑** | pi 主路径 **Radius 网关**（`session-share.ts`），gist fallback；java 只有 gist（`SessionShare.java`） |
| 9 | `/bug` | `:28` | ❌ | 1 | **缺失** | pi `core/bug-report.ts` 375 + `bug-report-upload.ts` 37 + `modes/interactive/bug-report.ts`；java 无对应物 |
| 10 | `/copy` | `:29` | ✅ `MiscCommands.java:96` | 2 | 对齐 | |
| 11 | `/name` | `:30` | ✅ `SessionCommands.java:22` | 1 | 对齐 | `AgentSession.java:529` |
| 12 | `/session` | `:31` | ⚠️ `SessionCommands.java:30` | 1 | **存疑** | pi "info **and stats**"；java 只打 4 行（`:89-98`） |
| 13 | `/changelog` | `:32` | ⚠️ `MiscCommands.java:120` | 1 | **存疑** | pi 读文件；java 硬编码字符串（`:24-30`） |
| 14 | `/hotkeys` | `:33` | ✅ `MiscCommands.java:122` | 1 | 对齐 | |
| 15 | `/fork` | `:34` | ✅ `SessionCommands.java:33` | 2 | **对齐** | **批 10（2026-10-22，B254/docs/21）**：user-message 选择器落地（`UserMessageSelectorScreen`，pi `user-message-selector.ts` 155 行对位——全量 user 消息、初始选中最后一条、环绕导航、选中 fork＋编辑器回填选中消息文本＋"Forked to new session"）；描述改 pi 逐字；窄差 D50（带参 `forkCopy` 臂 java 独有） |
| 16 | `/clone` | `:35` | ✅ `SessionCommands.java:48` | 1 | 对齐 | |
| 17 | `/trust` | `:36` | ✅ `SettingsCommands.java:30` | 2 | 对齐 | |
| 18 | `/login` | `:37` | ⚠️ `MiscCommands.java:140` | 2 | **存疑** | pi `login-dialog.ts` + OAuth 选择器 **＋ 顶层直供 Radius**（`interactive-mode.ts:5887-5952`）**＋ 登录后提供把 Radius 写进全局 `mcp.json`**（`offerRadiusMcpServer:6377`，调用点 `:6357`）；java 只用 `System.console().readPassword`（`:154`）收 API key |
| 19 | `/logout` | `:38` | ✅ `MiscCommands.java:164` | 1 | 对齐 | |
| 20 | `/new` | `:39` | ✅ `SessionCommands.java:63` | 2 | 对齐 | |
| 21 | `/compact` | `:40` | ✅ `MiscCommands.java:126` | 2 | **存疑** | 命令/customInstructions/worker 线程已对齐；但 **abort-first 缺**：pi `compact()` 第一句 `await this.abort()`（`agent-session.ts:2764-2767`，被打断回合不重试不继续），java 入口（`MiscCommands.java:126-138`、`AgentSession.java:716`）无 isRunning 守卫也不调 abort，运行中压缩不中止在飞回合。压缩窗口＋中止信号是「压缩自身可取消」，另见 map/03 #17 与 B36 |
| 22 | `/resume` | `:41` | ✅ `SessionCommands.java:70` | 2 | 对齐 | |
| 23 | `/reload` | `:42` | ⚠️ `SettingsCommands.java:53` | 1 | **存疑** | pi `agent-session.ts:3660` `reload()` 重载 extensions + skills + prompts + themes + context files，**并激活新加入 `defaultTools` 的工具**（含 `+/-` modifier，`:3662-3680`）；java 只 `settings().reload()`（`:59`） |
| 24 | `/quit` | `:43` | ✅ `MiscCommands.java:173` | 2 | 对齐 | |
| 25 | `/skill:<name>` 命令族 | `interactive-mode.ts:805-815` | ✅ `CommandRegistry.java` dispatch 透传＋`SkillCommandItems.java` | 2 | **对齐** | **批 2（2026-10-19，B244/docs/12）**：注册面＝补全目录（`PiTuiApp.loadSlashItems` skill 臂，受开关门）＋提交透传（`/skill:` 前缀 dispatch 返回 null 落 prompt，批 4 展开器接手）＋RPC `get_commands` skill 臂（`RpcDispatcher.buildCommands`）；裁决②展开不受开关门；窄差 D3 仅放行 `/skill:` 前缀（pi 任何未识别 slash 都落 prompt） |

**汇总：对齐 12 / 缺失 2 / 存疑 11 / 合计 25 = 48.0%**；**Σ权重 38 / Σ(w×c) 27.0 / 加权 71.1%**
> 批 2（2026-10-19，B244/docs/12）：#25 缺失 → **对齐**。同批更正本行旧账（旧行「对齐 11/Σwc 25.0」与逐行判定不符；逐行实算为对齐 10/Σwc 24.0，与下方汇总表 C 域 24.0 一致）。
> 批 10（2026-10-22，B254/docs/21）：#15 `/fork` 存疑 → **对齐**（user-message 选择器，窄差 D50 登记）。

---

## D. 扩展系统对齐（41 事件）

pi 的扩展事件面 = `ExtensionAPI.on()` 的 **41 个重载**（`core/extensions/types.ts:1569-1636`）。`ExtensionEvent` 联合在 `:1377-1409`。
`on()` 返回类型仍为 **`() => void`**（全部 41 条，可运行时退订，实现 `extensions/loader.ts`）。两锚点间**事件集零变动**（仅 `agent_settled` 加 `aborted` 字段、`tool_execution_end` 加 `durationMs`）。
pi-java 的**扩展 SPI** = `PiExtension.java`，只有 **2 个钩子**：`register(ExtensionContext)`（`:18`）与 `sessionStartResources()`（`:29`）。
pi-java 的**引擎钩子** = `pi-java-agent-core/.../agent/hook/HookSystem.java`，**13 个**（`onBeforeRun:43`…`onPrepareNextTurn:103`），**但 `ExtensionContext` 不暴露 `hookSystem()`** ⇒ 扩展拿不到。

判定口径：按「**扩展能否订阅该事件**」判；引擎侧有同形钩子的记 **存疑** 并在证据列写明「引擎钩子 X（未桥接）」。

| # | 事件 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `project_trust` | `:1569` | ❌ | 2 | **缺失** | java 有 `TrustManager.java` 标记文件，但无注册面 |
| 2 | `resources_discover` | `:1571` | ✅ `PiExtension.java:29` | 2 | **对齐** | `ExtensionManager.java:85-100` 合并保序 + 异常隔离 |
| 3 | `session_start` | `:1574` | ✅ | 2 | **对齐** | 批 coding-agent-5（docs/15）：装配期发射（初始恒 startup，CLI resume 也是——pi 逐字）；`createReplacing` 带 new+previous、`switchTo` 带 resume+previous；fork 臂批 6 落（docs/16：forked 全量 `loadExtensions`＋`start{fork, previous}`，pi rebind→bindExtensions 对位） |
| 4 | `session_info_changed` | `:1575` | ✅ | 1 | **对齐** | 宿主生产者已有（`AgentSession.java:706`——旧证据「零生产者/B30」已过期）；批 coding-agent-4（docs/14）`SessionEventBridge` 桥接 |
| 5 | `session_before_switch` | `:1577` | ✅ | 2 | **对齐** | 批 5 `AgentSession.switchTo` 集中（cancel ⇒ 旧会话不动，pi runtime :201-203 逐字）；预检目标存在再 teardown（pi 序） |
| 6 | `session_before_fork` | `:1581` | ✅ | 2 | **对齐** | 批 5 `forkCopy`/`forkFromEntry` 内发射（cancel ⇒ `Optional.empty`）；`forkCopy` 映射 `{当前叶,"at"}`（窄差 D17） |
| 7 | `session_before_compact` | `:1585` | ✅ | 2 | **对齐** | 批 5 `EngineHookBridge` 第 5 适配器：preparation（Plan 视图；无 fileOps=窄差 D22）＋cancel 短路＋supplied 接管（fromHook=true）；signal=abortToken |
| 8 | `session_compact` | `:1588` | ✅ | 2 | **对齐** | 批 5：`compactionEntry` 落（D4 销号——`CompactionObserver.onEnd` 第 6 参穿透）；`fromExtension` 读条目 `fromHook`（D6 销号） |
| 9 | `session_compact_failed` | `:1589` | ✅ | 1 | **对齐** | 批 4 桥接（`CompactionEnd` 失败/中止臂）；pi `aborted` 判定为**信号权威**（`agent-session.ts:2887`） |
| 10 | `session_shutdown` | `:1590` | ✅ | 2 | **对齐** | 批 5：`close()`=quit、`closeForReplacement`/`fireSessionShutdown` 带 reason+target（hasHandlers 门同 pi :258-267）；TUI 退出/RPC EOF 批 6 落（docs/16：`PiTuiApp.exit()` 关当前会话、`RpcDispatcher.close()` 关 dispatcher 当前会话，quit 在两退出路径发射） |
| 11 | `mcp_servers_change` | `:1591` | ❌ | 2 | **缺失** | 扩展订阅面不存在；java MCP 注册表本身见 map/07（B221） |
| 12 | `session_before_tree` | `:1593` | ✅ | 1 | **对齐** | 批 coding-agent-8（docs/18）：`SessionEventFirer.sessionBeforeTree`（通用 emit 逐字——cancel 短路＋最后发言者赢）＋`navigateTree` 发射（preparation 活列表＋signal；cancel/摘要/三覆盖臂）；死引擎钩 `onBeforeNavigation` 三件套删除（裁决①）；**互斥语义**：`isCompacting()` 含分支摘要在飞（pi getter :1622-1630，终审①修真） |
| 13 | `session_tree` | `:1596` | ✅ | 1 | **对齐** | 批 8（docs/18）：导航后发射（newLeafId=导航后当前叶、summaryEntry、fromExtension 仅在有摘要时有值，pi :4140-4147 逐字） |
| 14 | `context` | `:1597` | ✅ | **3** | **对齐** | 批 4 `EngineHookBridge`（`transform_context` 适配器）：两阶段逐字、系统消息过滤＋restore、引用相等快照（docs/14 §3.4.1） |
| 15 | `context_with_system` | `:1598` | ✅ | **3** | **对齐** | 批 4 阶段二：全量可见、返回即采用、弄丢头系统 `emitError` 上报仍采用（错误文本逐字） |
| 16 | `cache_warming_decision` | `:1600` | ✅ | 2 | **对齐** | 批 13（docs/24）：`CacheWarmingBridge`（pi runner.ts:1121-1142 逐字——last-override-wins＋逐处理器异常 emitError 后继续；未安装/无订阅 ⇒ 事件自带 action）＋`CacheWarmer` decide 消费（双层回落 :315-317 同保留）＋事件/结果/spec 三件套 |
| 17 | `before_provider_request` | `:1604` | ✅ | **3** | **对齐** | 批 coding-agent-7（docs/17）：ai 缝 `ProviderHooks`＋`ProviderEventBridge`——七车道 `onPayload`（车道原生载荷，同型守卫裁决⑤），链式替换＋逐处理器异常隔离（pi runner.ts:1360-1392 逐字） |
| 18 | `before_provider_headers` | `:1607` | ✅ | 2 | **对齐** | 批 7：`transformHeaders` 在 createApi 前缝消费（pi models.ts:866-871 对位，钩本体剥离）——原位改写＋null 删除（合并集内；SDK 默认头抑制不可达＝窄差 D34） |
| 19 | `after_provider_response` | `:1608` | ✅ | 2 | **对齐** | 批 7：SDK 四车道 `withRawResponse`（成功获取后、流消费前）＋自备两车道 `SseResponseObserver` 终态面（含错误状态，pi fetch 车道同形）；**google 不发＝pi 同缺**（pi google-generative-ai.ts 全文件零命中） |
| 20 | `provider_stream_event` | `:1609` | ✅ | 2 | **对齐** | 批 7：七车道迭代点首行（原始事件、归一化前）；事件五键 {data,provider,api,model}，`api` 目录未命中时 null |
| 21 | `before_agent_start` | `:1611` | ✅ | **3** | **对齐** | 批 5 `SessionEventFirer`（processPrompt 内、覆盖应用后）：systemPrompt 链式覆盖＋result.message 累积；options 可变对象不移植、覆盖是 harness 全局态（窄差 D11/D19） |
| 22 | `agent_start` | `:1614` | ✅ | 2 | **对齐** | 批 4 桥接（顺带 `turnIndex = 0`，pi `:1286` 同） |
| 23 | `agent_end` | `:1615` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `AgentEnd`，`AgentSessionEvent.java:28`） |
| 24 | `agent_before_settle` | `:1617` | ✅ | 2 | **对齐** | 批 5 `BoundaryBridge`（引擎新钩 `before_settle`，firing 于 drive 循环 settle 前门——pi :1839 同位）；草稿四型＋预览＋continue 校验全落 |
| 25 | `agent_settled` | `:1620` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `AgentSettled`，`:46`；`{aborted}` 直译） |
| 26 | `ui_prompt_start` | `:1621` | ❌ | 1 | **缺失** | 无对应 |
| 27 | `ui_prompt_end` | `:1622` | ❌ | 1 | **缺失** | 无对应 |
| 28 | `turn_start` | `:1623` | ✅ | 2 | **对齐** | 批 4 桥接（`{turnIndex, timestamp}`；turnIndex 桥内跟踪，pi `_turnIndex` 同构） |
| 29 | `turn_end` | `:1624` | ✅ | 2 | **对齐** | 批 5 `BoundaryBridge`（`should_stop_after_turn` 钩，位置与 pi finishTurn 包裹同位；顺手修真钩子载荷——旧喂 lane.partial＋空工具结果）；`turnIndex` 与宿主桥共享 |
| 30 | `message_start` | `:1625` | ✅ | 2 | **对齐** | 批 4 桥接 |
| 31 | `message_update` | `:1626` | ✅ | **3** | **对齐** | 批 4 桥接：`message` 从 `StreamEvent.partial()` 投影（`Message.AssistantMessage.fromPartial`，验证任务 V1 通过）；无订阅者零投影快走 |
| 32 | `message_end` | `:1627` | ✅ | 2 | **对齐** | 批 5 裁决①：引擎新钩 `message_end` 在 `PiLaneSink` 前替换（宿主事件在 flush 后、Message 不可变——唯一保语义的缝）；观察/结果同批火一次（宿主桥 case 已删防双发） |
| 33 | `tool_execution_start` | `:1628` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `SessionRunner.java:231`），字段直译 |
| 34 | `tool_execution_update` | `:1629` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `SessionRunner.java:235`）—— 台账 **B46** 的扩展面已补 |
| 35 | `tool_execution_end` | `:1630` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `SessionRunner.java:239`）；载荷与 pi types.ts:1086-1097 逐字段一致（`durationMs` 可空直译；pi 的 end 本就无 `args`） |
| 36 | `model_select` | `:1631` | ❌ | 2 | **缺失** | 无对应 |
| 37 | `thinking_level_select` | `:1632` | ✅ | 2 | **对齐** | 批 4 桥接（宿主 `AgentSession.java:513`）；`previousLevel` 桥内跟踪（种子＝装配时当前等级）—— 台账 **B35** 的扩展面已补 |
| 38 | `tool_call` | `:1633` | ✅ | **3** | **对齐** | 批 4 `EngineHookBridge`（`before_tool` 适配器）：block 短路、异常向上传播（pi 逐字）、input 原地可变链（docs/14 §3.4.2） |
| 39 | `tool_result` | `:1634` | ✅ | **3** | **对齐** | 批 4（`after_tool` 适配器）：逐字段合并＋structuredContent 丢弃规则（pi :1194-1214 逐字）；顺带修真 `applyAfterToolPatch` 洗 `isError`/`structuredContent` 的存量 bug（docs/14 §2.3） |
| 40 | `user_bash` | `:1635` | ✅ | 1 | **对齐** | 批 5 `executeBash` chokepoint 拦截（{result} 替换臂＋异常重抛不回落，pi :1262-1296 逐字）；{operations} 臂不移植（窄差 D10） |
| 41 | `input` | `:1636` | ✅ | **3** | **对齐** | 批 5 `processPrompt` 内、展开**之前**（pi :1991 逐字）；transform 链＋handled（SessionResult "handled"）；images 恒 null（窄差 D12） |

**汇总（宽口径，批 coding-agent-13 后）：对齐 36 / 缺失 5 / 存疑 0 / 合计 41 = 87.8%**；**Σ权重 83 / Σ(w×c) 75.0 / 加权 90.4%**
**汇总（严格口径）**：批 8 起与宽口径**合一**——最后一个未桥接引擎钩子（#12 `before_navigation`）随死钩删除出账，D 域不再有死 firing 点。
> ⚠️ D 域 Σ权重 **83** = 模块 496 的 **16.7%**。批 coding-agent-13（docs/24）翻 1 行（#16 缺失→对齐 +2.0）；缺失 5 行＝#1 project_trust／#11 mcp_servers_change／#26/#27 ui_prompt×2／#36 model_select（各随子系统：B221/E5）。批 8 翻 2 行（+1.5）：存疑**清零**。
> ⚠️ **基线纪律**（批 4 起）：D 域与模块合计按逐行实算（批 4 发现汇总行与逐行不符同族教训——批 4 基线实算 11.5≠旧汇总行 10.5）。

### 批 coding-agent-4（docs/14，2026-10-20 闭环）窄差表

| # | 内容 | 等级 |
|---|---|---|
| D1 | `context` 快照用引用相等（pi `===` 同构）；java 无 `structuredClone`（Message 不可变，无需） | 无影响 |
| D2 | `tool_call`/`tool_result` 事件不做 pi 的逐工具判别子类型（`BashToolCallEvent` 等 9 支），`input` 恒 map 形 | [内部] |
| D3 | `message_update` 的 `message` 提取自 StreamEvent partial（V1 通过：`fromPartial` 投影；无快照帧不发射） | 边缘场景 |
| D4 | `session_compact` 无 `compactionEntry` 载荷（CompactionResult 无条目本体） ⇒ D-8 判存疑，挂 B249 | [模型可见：扩展读不到该字段] |
| D5 | `extension_error` TUI 暂不展示（RPC 线已逐字）；TUI 通知面归 D′ 域 | [用户可见，边缘] |
| D6 | `fromExtension` 恒 false——java 无扩展触发压缩的路径（`sendMessage(triggerTurn)` 未实现），与当前全部可达行为一致 | 无影响 |
| D7 | pi 的 `parentToolCallId` 不带（嵌套工具调用 G-10 零对应，落地时补） | 存量约束，非本批范围 |
| ~~D8~~ | ~~宿主 `ToolExecutionEnd` 不带 `args`~~ | **作废**（闭环评审发现）：设计 §1.5 的「上＋」系误读——pi `ToolExecutionEndEvent`（types.ts:1086-1097）是独立 interface、**本就没有 `args`**；扩展事件的 `args` 组件已删除，事件载荷与 pi 逐字段一致，无差可登记 |

**新登记**：**B248**（provider 请求面三件套＋流事件，D-17/18/19/20 挂此）、**B249**（结果臂事件族）、**B250**（`session_start`/`session_shutdown` 发射点）——明细在 docs/04。~~B249/B250~~ **批 5 已销号**（docs/15，2026-10-20 闭环）。

### 批 coding-agent-5（docs/15，2026-10-20 闭环）窄差表

| # | 内容 | 等级 |
|---|---|---|
| D9 | `message_end` 观察处理器在 sink 时点与结果处理器同批火一次（看到链中值）；pi 观察与结果同序但后续观察看到链终值。替换对宿主监听者/TUI/RPC 的效果与 pi 原地改写一致 | [内部] |
| D10 | `user_bash` 的 `{operations}` 臂不移植（java 无 per-call BashOperations 缝；ToolContext 级 ShellExecutor 注入是既有等价物）；`{result}` 替换臂全落 | [内部] |
| D11 | `before_agent_start` 的 `systemPromptOptions` 可变对象不移植（java 无 BuildSystemPromptOptions 面；selectedTools 编辑臂随之不移植）；`systemPrompt` 覆盖是 harness **全局态**（粘性），非 pi 的 per-prompt options；事件的 `systemPrompt` 是配置原值（渲染拼装在引擎内） | [模型可见：覆盖持续生效] |
| D12 | `input` 事件 `images` 恒 null（java prompt 无图片面）；`source` 无 "extension" 生产者（E5-12 在册） | [内部，边缘] |
| D14 | `previousSessionFile`/`targetSessionFile` 载荷装会话 **id**（裁决⑤：SQLite/JSONL 仓无文件路径面） | [内部] |
| D15 | boundary 预览的 `contextMessages`/`llmMessages` 同源（java 无 pi 的 AgentMessage→LLM 二次转换缝） | [内部] |
| D16 | `session_before_fork` 的 `skipConversationRestore` 结果臂收下不消费（java fork 无恢复跳过面） | [内部] |
| D17 | `forkCopy`（整枝拷贝）映射 `before_fork{entryId:当前叶, position:"at"}`；pi 的 fork 恒显式 entryId | [内部] |
| D18 | pi 的 emitUserBash 模式间不对称（interactive catch＋不回落、rpc 传播）；java 单发射点（executeBash），异常统一沿调用方错误链 | [内部] |
| D19 | `before_agent_start` 的 result.message 落盘序为「custom 条目先于用户消息」（java 在 drive 前 append；pi 插在本轮 user 之后） | [内部，边缘] |
| D20 | /new 臂的 shutdown 在 start 之后（java 需先建会话才知目标 id；pi 先 teardown 后 create） | [内部] |
| D21 | boundary 预览 `pendingMessages` 由队列**文本**重建 UserMessage（java QueueManager 只读文本）；java 无 `_pendingCustomMessages` 面（sendMessage 即写） | [内部] |
| D22 | `session_before_compact` 载荷 `preparation.fileOps` 不译（java 的 fileOps 在 compact() 内收集，prepare 时点缺席） | [内部] |
| D23 | 原地 resume 的 shutdown/start 在**同一条 bus** 上发射（pi 是旧/新两条 runner）；内存路 switchTo 的 start 在新侧 firer 发射 | [内部] |

**销号**：**B249**/**B250**（D-3/5/6/7/8/10/21/24/29/32/40/41 十二行翻转）；批 4 窄差 **D4**（compactionEntry 落）、**D6**（fromExtension 读 fromHook）同步销号。
**新登记**：**B251**（forked 会话不加载扩展——forkInMemory/持久 fork 路不过 loadExtensions，fork 后扩展机制整体消失；`session_start{fork}` 臂随之无消费者）、**B252**（TUI/RPC 切换路径旧会话不 close、TUI 退出不 close ⇒ `session_shutdown{quit}` 在 TUI 永不发射）——明细在 docs/04。~~B251/B252~~ **批 6 已销号**（docs/16，2026-10-21 闭环）。

### 批 coding-agent-6（docs/16，2026-10-21 闭环）窄差表

| # | 内容 | 等级 |
|---|---|---|
| D24 | fork 携带当前 model/thinkingLevel（pi 经 persist→settings 重解析，效果同；java 无 persist 面故直接携带）；systemPrompt/activeTools/skills 重解析＋扩展 `start{fork}` 重激活（pi createRuntime 语义） | [内部] |
| D25 | 内存路 `switchTo` 旧会话对象不关闭（内存注册表的会话对象即存储本体，关即毁数据；pi 内存 SessionManager 无切回面——`switchSession` 恒要文件路径） | [内部] |
| D26 | 内存 CLI `--fork` 经 `forkCopy` ⇒ 发射 `start{fork}`（pi CLI fork 恒 `startup`；持久路不过 forkCopy＝startup，与 pi 一致） | [内部，测试路] |
| D27 | 内存 CLI `--fork` 的初始会话空转 `start{startup}` 后被弃置不 close（两步装配的存量形状；持久路无此问题） | [内部，测试路] |
| D28 | 内存 fork/clone 后旧会话已关但仍列在内存注册表（`/resume` 选到 ⇒ 拿到 closed 会话）；pi 内存态无切回面 | [内部，边缘] |
| D29 | 无 `extensionRunner.invalidate` 对位（pi dispose :1405 的 stale ctx 防御）；java 会话关闭后扩展持有的旧 ctx 仍可调共享 services | [内部，边缘] |
| D30 | providers 注册表 fork 间共享（初始装配注入缝无复制面）；扩展在 fork 两侧重复注册 provider 会叠加 | [内部，边缘] |

**销号**：**B251**/**B252**（缺陷闭环、无行翻转——D-3/D-10 早已判对齐，本批摘证据列「挂 B251/B252」帽；D 域 66.0/83＝79.5% 与模块 283.5/496≈57.2% 均不变，同批 3 口径）。

### 批 coding-agent-7（docs/17，2026-10-21 闭环）窄差表

| # | 内容 | 等级 |
|---|---|---|
| D31 | 替换 payload 类型不符 ⇒ 车道抛 `IllegalStateException`（请求失败）；pi 会把垃圾形状发给 provider 收 400——同属「扩展弄坏请求 ⇒ 请求失败」，失败点提前（裁决⑤） | [内部，边缘] |
| D32 | google 车道 payload 载体是 `GooglePayload(model, contents, config)` record（pi 是单一 `GenerateContentParameters` 对象；java SDK 三分参调用所致） | [内部] |
| D33 | `before_provider_headers` 事件头集＝models.json 三层（provider⨁model⨁override）合并值；pi 另有 `auth.headers` 与调用方显式 `options.headers` 两层（java 无生产者）及 attribution 层（**B253** 新登记） | [内部] |
| D34 | null 抑制 **SDK 默认头**不可达（通道只作用于 extra["headers"] 合并集；pi 的 null 还能压 provider 默认头如 User-Agent）；合并集内 null＝删除全落 | [内部，边缘] |
| D35 | 响应头摊平口径：名按 SDK/JDK 原样大小写（pi fetch `Headers.entries()` 小写化），同名多值逗号连接（≈fetch 行为） | [内部] |

**销号**：**B248**（D-17/18/19/20 四行翻转：ai 缝 `ProviderHooks` 三键＋七车道 firing＋`ProviderEventBridge`＋createApi 前头变换缝；D 域 66.0→71.5/83＝86.1%、模块 283.5→289.0/496≈58.3%）。
**新登记**：**B253**（attribution 头层——openrouter `HTTP-Referer`/`X-OpenRouter-*`、nvidia、cloudflare User-Agent、opencode 会话头，`provider-attribution.ts:33-99` 整体未移植，java 全仓零命中）。
**说明（非窄差）**：google 车道不发 `after_provider_response`＝pi 同缺（pi google-generative-ai.ts/google-vertex.ts 全文件零 `onResponse` 命中）；SDK 车道只在成功获取后发、fetch 车道含错误状态——pi 自身两族口径即如此（§1.4）。

### D′. 扩展 UI 注入点（单列）

pi `ExtensionUIContext` 共 **28 个成员**（`types.ts:149-323`，两锚点间零变动）：`select`/`confirm`/`input`/`notify`/`onTerminalInput`/`setStatus`/`setWorkingMessage`/`setWorkingVisible`/`setWorkingIndicator`/`setHiddenThinkingLabel`/`setWidget`/`setFooter`/`setHeader`/`setTitle`/`custom`/`pasteToEditor`/`setEditorText`/`getEditorText`/`editor`/`addAutocompleteProvider`/`setEditorComponent`/`getEditorComponent`/`theme`(readonly)/`getAllThemes`/`getTheme`/`setTheme`/`getToolsExpanded`/`setToolsExpanded`。

| 注入面 | pi | java | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|
| 阻塞式对话（select/confirm/input/editor） | ✅ 4 | ⚠️ 1 `request(RpcExtensionUIRequest)` | 2 | **存疑** | `ExtensionUI.java:13`；RPC 9 method 有形，但 `ui()` 无生产者（`DefaultExtensionContext.java:75` 回落 noop） |
| 通知 / 状态栏 / 标题 | ✅ | ❌ | 2 | **缺失** | 无对应 |
| widget（aboveEditor/belowEditor） | ✅ `setWidget` | ❌ | 2 | **缺失** | 无对应 |
| 自定义 footer / header | ✅ | ❌ | 2 | **缺失** | 无对应 |
| 自定义组件 + overlay + 键盘焦点 | ✅ `custom()` | ❌ | 2 | **缺失** | 无对应（`custom` 的 MCP 侧用法同样卡在这，见 map/07 B221） |
| 终端原始按键拦截 | ✅ `onTerminalInput` | ❌ | 2 | **缺失** | 无对应 |
| 编辑器注入（factory/autocomplete/paste/text） | ✅ 5 方法 | ❌ | 1 | **缺失** | 无对应 |
| 主题读写（get/set/getAll/theme） | ✅ 4 方法 | ❌ | 2 | **缺失** | 无对应 |
| 工具展开态读写 | ✅ | ❌ | 1 | **缺失** | 无对应 |

**D′ 汇总：对齐 0 / 缺失 8 / 存疑 1 = 0%**；**Σ权重 16 / Σ(w×c) 1.0 / 加权 6.3%**

---

## 能力单元清单（其余）

### E1 配置文件 / 清单（5）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|---|
| E1-1 | `settings.json`（全局） | 配置 | `config.ts:628-630` → `~/.pi/agent/settings.json` | `FileSettingsStorage.java:41/52` | 2 | 对齐 | — |
| E1-2 | `settings.json`（项目级） | 配置 | `.pi/settings.json`（`settings-manager.ts:192`，另 `:318`） | `FileSettingsStorage.java:42` | 2 | 对齐 | — |
| E1-3 | `auth.json` | 配置 | `auth-storage.ts:497-498`（revision + 文件锁 + stale 检测） | `ai/auth/FileCredentialStore.java`、`OAuthCredentialStore.java`（＋ AuthKind 分派） | 2 | **存疑** | — |
| E1-4 | `models.json` | 配置 | `core/models-store.ts` 147 + `model-config.ts:84`（`ModelsConfigSchema`）/`:115`（`ModelConfig`）+ `core/remote-catalog-provider.ts` 158 | `ai/provider/ModelsJsonProvider.java` + **`core/RemoteCatalogProvider.java`（326 行）** + `ModelCatalogRefresh.java` | 2 | **存疑** | **F6**（`ModelsJsonProvider` 钉死 baseUrl） |
| E1-5 | pi manifest（`package.json` 的 `pi` 字段） | 配置 | `core/pi-manifest.ts`（35 行） | ❌ 无对应物 | 2 | **缺失** | — |

**E1 汇总：对齐 2 / 缺失 1 / 存疑 2 / 合计 5 = 40.0%**；**Σ权重 10 / Σ(w×c) 6.0 / 加权 60.0%**

### E2 settings.json 键（54）

pi 键全部来自 `core/settings-schema.ts:206-430`（`SettingsSchema`；`interface Settings` 在 `:471`，Omit 掉 schema-only 的 `queueMode`/`websockets`）；java 来自 `core/Settings.java:26-119`。
**判定为「对齐」需字段存在且有非零消费者**（getter/setter 不算消费者；**批 12（docs/23 裁决①）起 TUI 模块的消费点计入本图**——map/04 行（settings 键）与 map/05 行（屏/组件单元）是不同单元，不构成重复计分；先例 E2-11 证据引 `TreeNavigationFlow:169`）。

| # | 键 | pi 行 | java 行 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| E2-1 | `lastChangelogVersion` | `:209` | `:95` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T7）**：changelog 流全链——`ChangelogSupport`（parse/compareVersions/getNewEntries 逐字对位 utils/changelog.ts:111/173/182-192）＋`forDisplay` 三臂（interactive-mode.ts:1332-1357：有消息⇒不显示／全新安装只写版本／有更新写回＋返回拼接）＋启动装配 `PiTuiLauncher.runInteractive`＋`/changelog` 全量 reverse 不回写（pi :6774-6793）；内置 `resources/CHANGELOG.md`（裁决⑦ 数据各自维护）；窄差 D63（纯文本渲染随 R1 批） |
| E2-2 | `defaultProvider` | `:210` | `:26` | **3** | 对齐 | `SettingsAccessors.java:20` |
| E2-3 | `defaultModel` | `:211` | `:27` | **3** | 对齐 | `SettingsAccessors.java:30` |
| E2-4 | `defaultThinkingLevel` | `:212` | `:28` | 2 | 对齐 | `SettingsAccessors.java:40` |
| E2-5 | `modelThinkingLevels` | `:213` | `:31` | 2 | **对齐** | **批 11（2026-10-22，docs/22 T2）**：字段＋四访问器（`SettingsAccessors:52`，settings-manager.ts:768-793 对位）＋三消费点（`SessionSetup.thinkingLevelFor` 第四臂／`AgentSession.setModel:780`＋`thinkingLevelForModelSwitch:789`／`cycleModelForward:764`；RPC/Web/TUI 切模型全改道）；**B15-残留-5 销号**；窄差 D54 |
| E2-6 | `transport` | `:218` | `:33` | 2 | 对齐 | 4 处消费 |
| E2-7 | `steeringMode` | `:224` | `:34` | 2 | 对齐 | `SettingsAccessors.java:60` |
| E2-8 | `followUpMode` | `:229` | `:35` | 2 | 对齐 | `SettingsAccessors.java:70` |
| E2-9 | `theme` | `:234` | `:36` | 2 | **存疑** | java 值是 `.tcss` 路径；pi 是 JSON 主题名 |
| E2-10 | `compaction` | `:235` | `:37` | **3** | 对齐 | java `Compaction` 含 `enabled/reserveTokens/keepRecentTokens/modelOverrides`（`Settings.java:134-148`）；per-model resolver `getCompactionSettings(ModelId)` 三级回退（`SettingsAccessors.java:260-267`），消费点 `AgentSession.java:420/717-718` —— A9 闭环 |
| E2-11 | `branchSummary` | `:236` | `:42` | 2 | **对齐** | **批 11（2026-10-22，docs/22 T1）核销**（账面滞后）：B1 已在 agent-core 批 4 销号——字段＋访问器（`getBranchSummaryReserveTokens`/`getBranchSummarySkipPrompt`）＋消费点 `AgentSession.java:1231`（reserve 进分支摘要选项）＋`TreeNavigationFlow.java:169`（skipPrompt 门，TUI） |
| E2-12 | `retry` | `:237` | `:66` | 2 | 对齐 | `SettingsAccessors.java:197` `getRetrySettings()` → `AgentSession.java:317`；`getProviderRetrySettings():218` → api options |
| E2-13 | `hideThinkingBlock` | `:238` | `:38` | 2 | 对齐 | 2 处消费 |
| E2-14 | `showCacheMissNotices` | `:239` | `:97` | 1 | **对齐** | 批 11：字段＋UiPrefs 访问器；**批 13（docs/24，裁决⑥）**：有载体三门落地——:4110 暖用量行（`ChatScreen.appendUsageNotice` 门控收口＋:3449 即时臂 `EntryAppended`＋onEntry 去重）、:4185 即时臂（`CacheMissTracker.detectOnMessageEnd`，判定后推进 prev 对位 pi scan 本 run 可见性）、:3999 重建臂（rebuildTranscript 逐条 feed＋气泡后注入，pi :4062 序）；设置页行（SettingsScreen "Cache miss notices"＋改动 rebuild，pi :5045-5048）；**两门无载体登 D67**（:4121 压缩成本通知挂 B38／:4151 thinking-drop 挂 diagnostics 面） |
| E2-15 | `externalEditor` | `:245` | `:39` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T3）**：accessor 回落链逐字（`SettingsAccessors.getExternalEditorCommand`：configured⇒VISUAL⇒EDITOR⇒平台默认，settings-manager.ts:946-956）＋`ExternalEditorSupport`（pi external-editor.ts:14-44：tmpdir/pi-editor-\*/prompt.md、split(" ") 简单拆、BOM＋尾换行规范化）＋Ctrl+G 接线 `PiTuiApp.openExternalEditor`（pi :4596-4611；alt 屏退出/重进） |
| E2-16 | `shellPath` | `:248` | `:40` | 2 | 对齐 | 5 处消费 |
| E2-17 | `quietStartup` | `:253` | `:42` | 1 | 对齐 | `SettingsAccessors.java:80` |
| E2-18 | `defaultProjectTrust` | `:259` | `:43` | 2 | 对齐 | `SettingsAccessors.java:90` |
| E2-19 | `shellCommandPrefix` | `:265` | `:41` | 2 | 对齐 | 4 处消费 |
| E2-20 | `npmCommand` | `:268` | — | 2 | **缺失** | 无字段 |
| E2-21 | `collapseChangelog` | `:274` | `:99` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T7）**：折叠门两臂（pi :843-872）——折叠 ⇒ 一行文案逐字（"Updated to vX.Y.Z. Use /changelog to view full changelog."）、非折叠 ⇒ "What's New"＋全文；设置页行 `SettingsScreen` "Collapse changelog"（install 域）；窄差 D63 |
| E2-22 | `enableInstallTelemetry` | `:280` | `:101` | 1 | **存疑** | 批 11（docs/22 T7）：字段＋Install 访问器（`?? true`）；**批 12（docs/23 §4/裁决⑤）**：pi 两消费——changelog 版本推进后 ping `pi.dev/api/report-install`（**不移植**：pi.dev 是 pi 自有端点，复刻发送会污染其遥测，窄差 D55）＋provider 归因头（随 B253 批 19） |
| E2-23 | `enableAnalytics` | `:286` | `:103` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T8）核销**（裁决③口径先例）：pi 运行时**零消费者**（全锚点 grep：除 UUID 生成/首次设置向导/bug-report 脱敏剔除外无 analytics 客户端、无上报端点；docs/settings.md:168 自述 "Currently used only by the experimental first-run setup."）⇒ pi 侧零消费＝声明面即全量（批 11 已落字段＋访问器＋UUID 生成） |
| E2-24 | `trackingId` | `:292` | `:105` | 1 | **对齐** | 同 E2-23（批 12 核销，裁决③）；bug-report 的 trackingId/deviceId 脱敏（pi bug-report.ts:62）随批 14（/bug＋crash-log）带上 |
| E2-25 | `deviceId` | `:303` | `:80` | 1 | **对齐** | **批 11（2026-10-22，docs/22 T5）**：字段＋`SettingsInstallAccessors.getOrCreateDeviceId`（只读写全局域／生成即 flush／幂等，settings-manager.ts:1066-1074 对位）＋消费点 `AuthCommand` ChatGPT 臂（持久安装 ID，pi interactive-mode.ts:6331 对位） |
| E2-26 | `packages` | `:295` | — | 2 | **缺失** | 无字段 |
| E2-27 | `extensions` | `:300` | `:44` | 2 | 对齐 | 2 处消费 |
| E2-28 | `skills` | `:308` | `:45` | 2 | 对齐 | 13 处消费 |
| E2-29 | `prompts` | `:309` | `:46` | 2 | 对齐 | 5 处消费 |
| E2-30 | `themes` | `:312` | `:47` | 2 | 对齐 | 4 处消费 |
| E2-31 | `enableSkillCommands` | `:313` | `:50` | 2 | **对齐** | **批 2（2026-10-19，B244/docs/12）**：accessor（`SettingsAccessors.getEnableSkillCommands`，缺省回落 true，settings-manager.ts:1156-1164 对位）＋设置页字段（`SettingsScreen` "Skill commands"）＋补全目录门（`PiTuiApp.loadSlashItems`）；裁决③ legacy 对象形 `skills` 迁移不移植（窄差 D1） |
| E2-32 | `terminal` | `:319`（schema `:74-100`，7 键） | `:49`（7 键，`Settings.java:155-166`） | 1 | **存疑** | 字段形状已齐（7/7）；**批 12（docs/23 §4）**：pi 消费面＝能力覆盖（hyperlinks/trueColor/images 短路终端探测）＋OSC 9;4 进度＋工具卡 inline 图片＋clearOnShrink——全部依赖 TamboUI 后端能力面或 java 没有的终端协议面（窄差 D56；可移植子集 OSC 9;4/clearOnShrink 随 TUI 屏域拍板〔变量③〕再裁） |
| E2-33 | `images` | `:320` | `:55` | 1 | **对齐** | java 2 键与 pi 同；**批 9（2026-10-16）`autoResize` 已消费**（`ImagePolicies.resolve` 经 ToolContext 晚绑定，原 docs/18）；**批 11（2026-10-22，docs/22 T6）`blockImages` 消费链接通**：`ImageBlockFilter`（pi sdk.ts:296-329 逐字）＋`EngineHookBridge.transformContext` 尾部殿后＋`--no-extensions` 裸注册；压缩/分支摘要不滤（pi 同） |
| E2-34 | `enabledModels` | `:321` | `:51` | 2 | 对齐 | `SettingsAccessors.java:101` |
| E2-35 | `defaultTools`（支持 `+name`/`-name`） | `:326` | `Settings.java:59` | 2 | **对齐** | **批 1（2026-10-18，B236/docs/11）**：字段＋`mergeDefaultTools` 分层特判（`SettingsManager.merge`，settings-manager.ts:124-129/139-145 对位）＋`resolveDefaultTools`（`SettingsAccessors.getDefaultTools`，:1328-1333 对位）；消费点 `SessionSetup.resolveToolSelection`；窄差 D3：形状异常 pi 容忍、java 类型化读入即抛 |
| E2-36 | `doubleEscapeAction` | `:332` | `:52` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T2）**：accessor 默认回落 `"tree"`（settings-manager.ts:1341-1343）＋空编辑器双击 Esc 行为（`PiTuiApp` INTERRUPT 臂，pi interactive-mode.ts:3067-3097：500ms 窗、流式中断不计双击、tree/fork 两选择器）＋设置页行已有 |
| E2-37 | `treeFilterMode` | `:338` | `:53` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T1）核销**（裁决②，账面滞后）：消费点批 9 已随树选择器落地——`TreeNavigationFlow.java:97-98` 初值读 accessor（pi :5562 对位）＋`TreeSelectorOverlay.java:141-149` 选择器内切换**不回写**（`TreeNavigationFlowTest.java:579-592` 钉死；pi tree-selector.ts:1049-1083 同形） |
| E2-38 | `thinkingBudgets` | `:353` | `:91` | 1 | **对齐** | **批 11（2026-10-22，docs/22 T1）核销**（账面滞后）：B128 三件俱齐——字段＋访问器 `SettingsAccessors.getThinkingBudgets`＋消费点 `AgentSession.java:614`（进 harness 配置） |
| E2-39 | `editorPaddingX` | `:358` | `:54` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T4）**：uiPrefs accessor（缺席 0、clamp 0..3，settings-manager.ts:1373-1380 对位）＋`EditorComponent` 双侧留白 live-read（pi editor.ts:523-524/560；java 无鼠标命中面仅渲染留白） |
| E2-40 | `outputPad` | `:364` | `:55` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T6）**：uiPrefs accessor（pi :1383-1385 逐字：仅显式 0⇒0 否则 1）＋消费链 `ChatScreen.onEntry`/`rebuildTranscript` ⇒ `ChatMessage.from(entry, pad)` ⇒ `Padded` 烘焙变体（append 时定值）⇒ `MessageBubble` indent＋`ChatViewportElement.flatten` 内容宽 −2×pad；设置页行 "Output pad"；窄差 D62（改值不回溯已渲染条目）；扩展渲染器 `ToolRenderContext.outputPad` 面 java 无（B204 域） |
| E2-41 | `autocompleteMaxVisible` | `:370` | `:56` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T5，裁决④）**：uiPrefs accessor（缺席 **5**〔java 旧硬编码 8 改齐 pi〕、clamp 3..20）＋`SlashCompleter` maxVisible live-read（pi editor.ts:2241 `new SelectList(items, maxVisible, ...)` 对位；overlay 关闭目录重建热更） |
| E2-42 | `showHardwareCursor` | `:376` | `:111` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T9）**：`TamboUIAdapter.createRunner(showHardwareCursor)` 参数化（`hideCursor(!show)`，pi tui.ts:1477-1484 resolveFakeCursors 对位：开＝真终端光标、关＝自绘假光标）＋`PiTuiLauncher.runFullscreen` 注入＋设置页行 "Hardware cursor" |
| E2-43 | `markdown` | `:379` | `:57` | 1 | **存疑** | java 2 键；**批 12（docs/23 §4）**：`mermaid` 无 grok-mermaid 对位库（pi 渲染 Unicode 字符画；java 硬编码 panel 且 MarkdownRenderer 生产零调用）⇒ **窄差 D57**；`codeBlockIndent` 消费前提是 MarkdownRenderer 接入 MessageBubble——随 R1 激活批（map/05 R1，计入拍板变量③） |
| E2-44 | `warnings` | `:380`（`WarningSettingsSchema:144`） | `:107` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T13）**：`AnthropicExtraUsageWarning`（pi :5261-5286 门控＋:321-323 文案逐字＋:325-327 判据——种类判据退化为 stored-oauth source 标记＋`sk-ant-oat` 值前缀双判据）＋四时机接线 `PiTuiApp`（init/模型选择/Ctrl+P/登录，pi :1228/:4550/:6120/:5352 对位）＋每会话一次（switchSession 重建）＋设置页行 "Warn Anthropic extra usage" |
| E2-45 | `codemode`（`CodemodeSettings`） | `:381`（schema `:154-167`） | — | 2 | **出分母** | **批 11（docs/22 裁决②）**：java 无 codemode 子系统（B161），R5 面外——不进 `bin pi` 主流发布版的不做对齐、不进分母；未来立项再入列 |
| E2-46 | `sessionDir` | `:382` | `:58` | 2 | 对齐 | 7 处消费 |
| E2-47 | `httpProxy` | `:387` | `:71` | 1 | **对齐** | **批 11（2026-10-22，docs/22 T3）**：`HttpProxySupport`（pi applyHttpProxySettings 对位，通道换 JVM 系统属性，裁决③）＋应用点 `SettingsManager.load:61`（读全局域）；窄差 D52（凭证/nonProxyHosts/socks 不解析） |
| E2-48 | `httpIdleTimeoutMs` | `:390` | `:74` | 1 | **对齐** | **批 11（2026-10-22，docs/22 T4）**：字段（number\|"disabled" 联合）＋`TimeoutSettings` 解析＋`SettingsNetworkAccessors.getHttpIdleTimeoutMs`＋消费点 `DefaultProviders.apiOptions:372`（pi sdk.ts:344-354 链序；120s 自造兜底删除⇒300s）；窄差 D53（单臂对齐） |
| E2-49 | `cacheWarming` | `:395` | `:109` | 2 | **对齐** | 批 11：字段＋Network 访问器；**批 13（docs/24）子系统全链**：`CacheWarmer` 状态机（pi cache-warmer.ts:162-401 逐字——三纯函数/双安全窗/deadline/经济闸门 $0.05/停止理由全集）＋装配（裁决①分包门：warming wrapper 只包 harness 注入点，摘要/压缩保持 recordingStreamFn；暖路径 maxRetries 钉 0/跳三钩/保头变换）＋四面接线（settled 双发射点/dispose cancel/setCacheWarmingMode＋onModeChanged/cacheWarmingStatus）＋设置页行（SettingsScreen "Cache warming" 三值循环走会话入口，pi :5005-5008）；评审 C1：isCurrent 捕获改快照（lane.messages 就地重写⇒活视图比对恒真） |
| E2-50 | `websocketConnectTimeoutMs` | `:402` | `:77` | 1 | **存疑** | 批 11（docs/22 T4/T7）：字段＋Network 访问器（缺席无兜底，settings-manager.ts:935-937 对位）；**批 12（docs/23 §4）**：pi 唯一消费者＝openai-codex-responses 的 WebSocket 传输（connect 握手超时，默认 15s、0 禁用）；java 无 WS 传输（codex 车道走 HTTP/SSE）⇒ **窄差 D58** |
| E2-51 | `tuiMode` | `:407` | `:63` | 2 | 对齐 | `SettingsAccessors.java:112`；默认均 fullscreen |
| E2-52 | `fullscreenExitOutput` | `:412` | `:113` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T10）**：退出两臂（pi :881-887＋:4322-4328）——resume-hint 只留提示行、transcript（默认）`TranscriptDump` 倾倒对话滚动回退进主屏＋两臂统一恢复提示（仅已持久化会话）；窄差 D64（文案 `pi-java -r` 指引，`-r` 选择器随批 13） |
| E2-53 | `fullscreenScrollbar` | `:418` | `:115` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T11，裁决⑧）**：`ChatViewportElement` 三态——HIDDEN 不渲染/命中恒 false/内容区不让列；AUTO/ALWAYS 同形常驻（窄差 D61：pi auto＝滚动后短暂显示 transient，java 无计时面）；渲染期现读热更＋设置页行 "Fullscreen scrollbar" |
| E2-54 | `fullscreenCopyOnSelect` | `:424` | `:117` | 1 | **存疑** | 批 11（docs/22 T7）：字段＋UiPrefs 访问器（`?? true`）；**批 12（docs/23 §4）**：消费依赖 alt-screen 选区/复制子系统（map/05 S2a-d 未排期）⇒ **窄差 D59** |
| E2-55 | `fullscreenWheelScrollLines` | `:430` | `:119` | 1 | **对齐** | **批 12（2026-10-22，docs/23 T12，裁决⑥）**：数字臂接 `ScrollConfig.from` 第三参（固定行数 `withWheelLines`，accessor 已 clamp 1–100）；auto 臂维持既有 Codex 有界加速模型（窄差 D60＝K38：pi auto 是 mac/SSH 判定＋速率加速 ≤6＋Alt×5 的速度模型，不新造） |

**E2 汇总：对齐 46 / 缺失 2 / 存疑 6 / 出分母 1 / 合计 54**；**Σ权重 84 / Σ(w×c) 76.5 / 加权 91.1%**
> `queueMode`（`:436`）/`websockets`（`:442`）只存在于 `SettingsSchema` 输入侧，`interface Settings`（`:471`）显式 Omit，非 settings.json 键，不列单元。
> 本锚点改判：`compaction`（E2-10）存疑 → **对齐**（modelOverrides 字段＋per-model resolver 接线，A9 闭环）。
> 批 1（2026-10-18，B236/docs/11）：`defaultTools`（E2-35）缺失 → **对齐**（键＋分层 merge＋解析＋消费点全落，窄差 D3 登记）。
> 批 2（2026-10-19，B244/docs/12）：`enableSkillCommands`（E2-31）存疑 → **对齐**（accessor＋设置页＋补全门全落，窄差 D1 登记）。
> 批 11（2026-10-22，docs/22）：**22 行翻动**——核销 2（E2-38/E2-11 账面滞后⇒对齐）；消费链 5（E2-5/E2-47/E2-48/E2-25/E2-33⇒对齐，B15-残留-5 销号，窄差 D52–D54）；声明面 14（缺失⇒存疑：E2-1/14/21/22/23/24/42/44/49/50/52/53/54/55）；E2-45 `codemode` **出分母**（裁决②，R5 面外 B161）。Σ 52.0→67.5（出分母后 67.5/84＝80.4%）。
> 批 12（2026-10-22，docs/23）：**15 行翻动**（全部存疑⇒对齐）＋**表头口径修订（裁决①：TUI 消费点计入）**——核销 3（E2-37 批 9 账面滞后〔裁决②〕；E2-23/E2-24 pi 侧零消费⇒声明面即全量〔裁决③口径先例〕）；TUI 行为键 5（E2-36 双击 Esc／E2-15 外部编辑器／E2-39 编辑器留白／E2-41 补全行数〔裁决④默认 8⇒5〕／E2-40 outputPad 烘焙）；声明面消费点 7（E2-1＋E2-21 changelog 流、E2-42 硬件光标、E2-52 退出两臂、E2-53 滚动条三态、E2-55 滚轮数字臂、E2-44 订阅警告）；窄差 D55–D64（D56 terminal／D57 mermaid／D58 无 WS 传输／D59 选区子系统／D60 auto 加速模型／D61 auto/always 同形／D62 不回溯／D63 纯文本／D64 提示文案）；不动 6 行证据更新（E2-14/E2-49 随 cache-warmer 子系统批）。Σ 67.5→**75.0/84＝89.3%**。另 `setModelThinkingLevel` TUI 入口落地（E2-5 批 11 已对齐，无账面）。
> 批 13（2026-10-23，docs/24）：**2 行翻动**（存疑⇒对齐）——E2-49 `cacheWarming`（+1.0：cache-warmer 子系统全链＋设置页行）、E2-14 `showCacheMissNotices`（+0.5：有载体三门落地，两门无载体登 D67 随载体批次收，裁决⑥）；窄差 D65–D68（abort＝iterator.close／暖选项现读／E2-14 两门无载体／/session 结构差）。Σ 75.0→**76.5/84 ≈ 91.1%**。

### E3 会话管理行为（24）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E3-1 | 新建会话（无参） | `session-manager.ts:1057` `newSession` | `SessionPersistence.java:201` `handle.create` | **3** | 对齐 | — |
| E3-2 | 续最近会话（`-c`） | `main.ts:432-433` → `SessionManager.continueRecent`（`session-manager.ts:1793` → `findMostRecentSession:749`） | `SessionPersistence.java:170` `handle.latest(cwd)` | 2 | 对齐 | A12 已结案 |
| E3-3 | `-r` 交互式会话选择器 | `main.ts:417` `selectSession`（`cli/session-picker.ts` 55 行 + `session-selector.ts` 1,045 + `session-selector-search.ts` 194） | ✅ tui `component/session/`（`SessionSelectorComponent`/`SessionListModel`/`SessionListRenderer`/`SessionSelectorHeader`/`SessionTreeBuilder`/`SessionSelectorSearch`）＋`SessionSelectorRunner`＋coding-agent SPI（批 14，docs/25） | 2 | **对齐** | **批 14**：搜索（re:/引号/未闭合回落/fuzzy 子序列）/三档排序/命名过滤/路径开关/双 scope 懒载/删除流/进度全落；窄差 D69–D76（trash 臂无/SPI 缺席/键位 helper 等）；rename 面板留白随交互选择器批（裁决⑦） |
| E3-4 | `--session` 精确/前缀查找 | **路径形快道＋三级阶梯**（`main.ts:252-283`）：路径形（`:253-256`）→ ① `findLocalSessionByExactId`（`:243`/`:260`）→ ② 项目内前缀 `SessionManager.list`（`:263-268`）→ ③ `SessionManager.listAll`（`:273`，跨项目） | `PersistentSessionRepositories.java:59-93` 阶梯（B53）＋`findWithScope` scope 标＋`SessionPersistence.resolveSessionArg`（批 14） | 2 | **对齐** | **批 14（docs/25 T6/T7）**：pi 全序重排＋③ global 臂（提示＋`promptConfirm` fork／`Aborted.` exit 0）＋`SessionResolutionExit` 干净错误面 |
| E3-5 | `--session-id` 精确 ID（缺则建） | `main.ts:437-447` | `SessionPersistence.resolveSessionId`（批 14）＋`RepositoryHandle.findLocalById/createWithId`（T4） | 2 | **对齐** | **批 14（docs/25 T8）**：只项目内精确（pi `:437`）；缺 ⇒ warning 逐字＋带 id 新建（修丢 id）；`assertValidSessionId` 正则逐字入 ArgsParser |
| E3-6 | `--fork` 分叉 | `main.ts:369-381` | `SessionPersistence.resolveFork`（批 14） | 2 | **对齐** | **批 14（docs/25 T9）**：`--session-id` 目标查重（`Session already exists with id '<id>'` exit 1）＋not_found 文案逐字；`validateForkFlags` 互斥 |
| E3-7 | `/clone` 原位复制 | `session-manager.ts:1632` `createBranchedSession` | `SessionCommands.java:48` `forkCopy` | 1 | 对齐 | — |
| E3-8 | `/fork` 从历史用户消息分叉 | `user-message-selector.ts`（155 行） | `PiTuiApp.java`（forkFromMessage）＋`UserMessageSelectorScreen.java` | 2 | **对齐** | **批 10（2026-10-22，B254/docs/21）**：选择器本体＋fork-before＋编辑器回填全落；窄差 D50/D51 登记（同 C 域 #15——设计对账表漏列本行，闭环逐行实算同翻） |
| E3-9 | 会话列表（加强） | `listSessionsFromDir`（`:941`）、`SessionListProgress`（3 参）、`AbortSignal`、`publishPartial`、`listAll` | `RepositoryHandle.listRich/listAllRich`（批 14 T4）→ `JsonlSessionRepository.listRich/listAllRich`（B55 已落：进度＋取消＋并发） | 2 | **对齐** | **批 14（docs/25 T4）**：repo 层能力暴露到句柄面（选择器消费）；sqlite 臂退化（窄差 D74） |
| E3-10 | 导出 JSONL | `core/session-export.ts` | `PersistentSessionRepositories.java:95` | 2 | 对齐 | — |
| E3-11 | 导出 HTML | `core/export-html/`（748 行） | `export/HtmlExporter.java` | 2 | 对齐 | — |
| E3-12 | 导入 JSONL | `session-manager.ts` | `PersistentSessionRepositories.java:109` | 1 | 对齐 | — |
| E3-13 | 会话命名 | `session-manager.ts:1304` `appendSessionInfo` | `SessionCommands.java:22` + `AgentSession.java:529` | 1 | 对齐 | **B32** |
| E3-14 | 会话树导航 | `session-manager.ts:1529` `getTree` / `:1579` `branch` | `SessionCommands.java:56` + `RpcDispatcher.java:295` | 1 | 对齐 | — |
| E3-15 | 分支摘要（branch summary） | `core/compaction/branch-summarization.ts:349-353` | ❌ | 2 | **缺失** | **B1** |
| E3-16 | 条目标签（labels） | `session-manager.ts:1237/1246` | agent-core `JsonlSessionStorage.java:263/267` | 1 | 对齐 | — |
| E3-17 | 自定义条目（custom entry） | `session-manager.ts:1290` | agent-core `Session.java:183` | 2 | 对齐 | — |
| E3-18 | bash 执行条目 | `session-manager.ts:1071` | `AgentSession.java:1036-1045` + `AgentSessionEvent.BashExecutionUpdate:111` | 1 | 对齐 | — |
| E3-19 | `--no-session` 语义 | `main.ts`（ephemeral） | `SessionSetup.resolveSession:122`（CLI 臂）＋`SessionPersistence.resolvePersistentWeb` no-session 早退（批 14 T14） | 1 | **对齐** | **批 14（docs/25 裁决⑤，B54 销号）**：web 路径 `--no-session` 赢（ephemeral 是更安全一侧）——**设计对账表漏列本行，闭环逐行实算同翻**（批 10 E3-8 先例） |
| E3-20 | `--session-dir` | `session-manager.ts:596` `getDefaultSessionDir` | `AgentSession.java:131/164/180` | 1 | 对齐 | — |
| E3-21 | 按 cwd 分目录 | `session-manager.ts:589/596` | `AgentSession.java:131`（`--path--` 编码） | 2 | 对齐 | — |
| E3-22 | 崩溃恢复（settle 未结操作） | `session-manager.ts:341` `migrateSessionEntries` | `SessionPersistence.java:114` `settleOpenOperation` | 2 | 对齐 | — |
| E3-23 | 会话元数据变更事件 | `session_info_changed`（`types.ts:1575`） | `AgentSessionEvent.SessionInfoChanged` **零生产者** | 1 | **缺失** | **B30** |
| E3-24 | 提示缓存预热 | `core/cache-warmer.ts`（453 行）+ `settings-schema.ts:395` | `CacheWarmer`＋`CacheWarmingEconomics`＋`CacheStats`＋`CacheWarmingBridge`＋装配四面（批 13，docs/24） | 2 | **对齐** | **批 14 核销**：批 13 对账表漏列本行（cache-warmer 子系统即本行内容），闭环逐行实算补翻 |

**E3 汇总：对齐 22 / 缺失 2 / 存疑 0 / 合计 24 = 91.7%**；**Σ权重 40 / Σ(w×c) 37.0 / 加权 92.5%**
> 批 10（2026-10-22，B254/docs/21）：E3-8 `/fork` 存疑 → **对齐**。
> 批 14（2026-10-23，docs/25）：**7 行翻动**——E3-3 缺失⇒对齐（+2.0，选择器本体）、E3-4/E3-5/E3-6/E3-9 存疑⇒对齐（+4.0）、**E3-19 同翻**（+0.5，B54 销号——设计对账表漏列，批 10 E3-8 先例）、**E3-24 核销**（+2.0，批 13 对账表漏列，cache-warmer 本体已落）。Σ 28.5→**37.0/40 ≈ 92.5%**（余 3.0：E3-15 挂 B1、E3-23 挂 B30）。

### E4 资源发现（7）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E4-1 | skill 发现（目录 + `SKILL.md` + 忽略文件） | `core/skills.ts:168/414`、`resource-loader.ts` | `skill/SkillDiscovery.java:36/51` + `IgnoreFilter.java` | 2 | 对齐 | — |
| E4-2 | skill 注册为 `/skill:<name>` 命令 | `interactive-mode.ts:805-815` | ✅ `SkillCommandItems.java`＋`PiTuiApp.loadSlashItems` | 2 | **对齐** | **批 2（2026-10-19，B244/docs/12）**：补全目录条目＋来源标签 u/p/t 三值子集（裁决⑥，npm/git 复合标签窄差 D2）；pi 从不注册真命令——注册面＝补全＋透传（裁决①），同 C 域 #25 |
| E4-3 | prompt template 发现 + 参数替换 | `core/prompt-templates.ts:222` | `prompt/PromptTemplates.java:47/186` | 2 | 对齐 | **批 3（2026-10-19，B247/docs/13）**：注册面补齐（折叠进本单元，不另计分母）——补全目录模板臂（`PromptTemplateItems`，pi 无门＋u/p/t 标签＋argumentHint 透传）＋dispatch 已注册模板名透传（`CommandRegistry.java`）；记录增 `sourceInfo` 加载期打标（`loadDefaults` user/project、显式 temporary，pi :235-261） |
| E4-4 | theme 发现（JSON + schema + 目录扫描） | `theme/`：`theme.ts` 1,086 + `theme-controller.ts` 253 + `system-theme.ts` 655 + `theme-schema.ts` 146（typebox）+ `theme-tokens.ts` 121 + dark/light.json 156（旧 JSON-schema 文件已删） | ❌ 只有 2 个内建 `.tcss`（pi-java-tui `PiTheme.java:23-24`） | 2 | **缺失** | — |
| E4-5 | 上下文文件发现（`AGENTS.md`/`CLAUDE.md`） | `resource-loader.ts:185`（5 候选名）+ `loadProjectContextFiles:232` + `findShadowedContextFile:214` + worktree 影子判定（`:252-262`）+ `findGitPaths`（`footer-data-provider.ts:16-48`） | agent-core `prompt/ContextFileDiscovery.java`（5 候选名 / 向上遍历 / canonical 去重 / linked-worktree 影子去重）+ 接线 `AgentSession.java:423-425`（`--no-context-files` 有消费者） | **3** | 对齐 | — |
| E4-6 | 系统提示词文件发现 | `resource-loader.ts:1213` `discoverSystemPromptFile` | ❌ | 2 | **缺失** | — |
| E4-7 | 资源冲突诊断 | `diagnostics.ts`（`ResourceDiagnostic`/`ResourceCollision`） | `skill/ResourceDiagnostic.java`、`PromptTemplates.Diagnostic` | 1 | 对齐 | — |

**E4 汇总：对齐 5 / 缺失 2 / 存疑 0 / 合计 7 = 71.4%**；**Σ权重 14 / Σ(w×c) 10.0 / 加权 71.4%**

### E5 扩展 API 面（35）

pi 面 = `ExtensionAPI`（`core/extensions/types.ts:1564-1891`）；java 面 = `PiExtension` + `ExtensionContext`（`extension/*.java`）。

| # | 能力单元 | pi 行 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E5-1 | `registerTool` | `:1643` | `ExtensionContext.java:20` `tools()` | **3** | 对齐 | — |
| E5-2 | `registerCommand` | `:1652` | `ExtensionContext.java:23` `slashCommands()` | 2 | 对齐 | — |
| E5-3 | `registerProvider` | `:1830-1831` | `ExtensionContext.java:26` `providers()` | 2 | 对齐 | — |
| E5-4 | 注册技能 | （无独立 API，靠目录） | `ExtensionContext.java:29` `skills()` | 2 | 对齐 | — |
| E5-5 | `unregisterProvider` | `:1846` | ❌ | 1 | **缺失** | — |
| E5-6 | `registerShortcut` | `:1655` | ❌ | 2 | **缺失** | — |
| E5-7 | `registerFlag` / `getFlag` | `:1664` / `:1680` | ❌ | 1 | **缺失** | — |
| E5-8 | `registerMessageRenderer` | `:1687` | ❌ | 2 | **缺失** | — |
| E5-9 | `registerMarkdownTransformer` | `:1690` | ❌ | 2 | **缺失** | — |
| E5-10 | `registerEntryRenderer` | `:1693` | ❌ | 2 | **缺失** | — |
| E5-11 | `registerToolRenderer` | `:1696` | ❌ | 1 | **缺失** | — |
| E5-12 | `sendMessage`（含 `triggerTurn`/`deliverAs`） | `:1703` | `ExtensionContext.java:48-55` 仅**落盘**；`triggerTurn=true` 抛 UOE（`:60-62`） | 2 | **存疑** | — |
| E5-13 | `sendUserMessage` | `:1713` | ❌ | 2 | **缺失** | — |
| E5-14 | `appendEntry` | `:1719` | ❌ | 1 | **缺失** | — |
| E5-15 | `setSessionName` / `getSessionName` | `:1726` / `:1729` | ❌ | 1 | **缺失** | — |
| E5-16 | `setLabel` | `:1732` | ❌ | 1 | **缺失** | — |
| E5-17 | `exec` | `:1735` | ❌ | 2 | **缺失** | — |
| E5-18 | `getActiveTools`/`getAllTools`/`setActiveTools` | `:1738`/`:1741`/`:1750` | ❌ | 2 | **缺失** | — |
| E5-19 | `getSettings` | `:1744` | `ExtensionContext.java:32` `settings()` | 2 | 对齐 | — |
| E5-20 | `getCommands` | `:1753` | ❌ | 1 | **缺失** | — |
| E5-21 | `setModel` | `:1763` | ❌ | 2 | **缺失** | — |
| E5-22 | `getThinkingLevel`/`setThinkingLevel` | `:1766`/`:1772` | ❌ | 2 | **缺失** | — |
| E5-23 | `registerMcpServer` | `:1866` | ❌（`ExtensionContext` 无此面） | 2 | **缺失** | MCP 面见 map/07 |
| E5-24 | `unregisterMcpServer` | `:1869` | ❌ | 1 | **缺失** | MCP 面见 map/07 |
| E5-25 | `getMcpServers` | `:1872` | ❌ | 1 | **缺失** | MCP 面见 map/07 |
| E5-26 | `registerVirtualModel` | `:1883` | ❌ | 2 | **缺失** | — |
| E5-27 | `unregisterVirtualModel` | `:1886` | ❌ | 1 | **缺失** | — |
| E5-28 | `events`（扩展间 EventBus） | `:1890` | ❌ | 1 | **缺失** | — |
| E5-29 | 热重载（`/reload` 重载 extensions/skills/prompts/themes + 激活新 defaultTools） | `agent-session.ts:3660`（modifier 合并 `:3662-3680`） | ⚠️ `SettingsCommands.java:53` 只重载 settings | 2 | **存疑** | — |
| E5-30 | 扩展注册 CLI flag | `:1664` + `args.ts:186` | ❌ | 1 | **缺失** | — |
| E5-31 | 扩展 UI 注入（TUI） | `types.ts:149-323` | ❌ 只回落 `ExtensionUI.noop()`（`ExtensionContext.java:35-37`） | 2 | **缺失** | — |
| E5-32 | 扩展 UI 通道（RPC，9 method） | `rpc-types.ts:253-297` | ⚠️ `RpcExtensionUIRequest.java` 形有，`ui()` **无生产者**（`RpcDispatcher.java:337-348`，队列 `:57`） | 1 | **存疑** | — |
| E5-33 | 扩展示例库（可执行验收规格） | `examples/extensions/`（105 个 `.ts`，16,033 行） | ❌ 只有 5 个测试夹具 | **3** | **缺失** | — |
| E5-34 | 扩展来源追踪（`SourceInfo`） | `types.ts:1543/2055/2098` | ⚠️ `InstalledExtension.java:15` 有路径，无 `sourceInfo` 投影 | 1 | **存疑** | — |
| E5-35 | `on()` 返回退订函数 | `types.ts:1569-1636`（41 条 `() => void`） | ✅ `ExtensionContext.on(spec, handler)`（批 4，docs/14 §3.2）→ `AutoCloseable` 退订：按实例移除、空表删键（loader.ts:272-288 逐字语义） | 2 | **对齐** | — |

**E5 汇总（批 4 后）：对齐 6 / 缺失 25 / 存疑 4 / 合计 35 = 17.1%**；**Σ权重 58 / Σ(w×c) 16.0 / 加权 27.6%**
> E5-23..25 的 MCP 注册 API 判定随 MCP 整体面在 `map/07` 复核；本表按「扩展 SPI 是否暴露」判，仍为缺失。

### E6 包管理器（16）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E6-1 | `install` 本地源 | `package-manager.ts:112` | `PackageCommand.java:54-65` | 2 | **存疑** | — |
| E6-2 | `install` http(s) URL | `package-manager.ts` | `ExtensionPackageManager.java:93` | 2 | **存疑** | — |
| E6-3 | npm 源（版本/range/pinning） | `package-manager.ts:139-147` | ❌ | **3** | **缺失** | — |
| E6-4 | git 源 | `package-manager.ts` | ❌ | 2 | **缺失** | — |
| E6-5 | 作用域 user/project/temporary | `package-manager.ts:135` | ⚠️ 只有 global/project | 2 | **存疑** | — |
| E6-6 | 资源过滤 | `package-manager.ts:187-194` | ❌ | 2 | **缺失** | — |
| E6-7 | 资源优先级（5 档） | `package-manager.ts:171-184` | ❌ | 2 | **缺失** | — |
| E6-8 | 忽略文件 | `package-manager.ts:206` | ❌ | 1 | **缺失** | — |
| E6-9 | 进度回调 `onProgress` | `package-manager.ts:96` | ❌ | 1 | **缺失** | — |
| E6-10 | `update self` / `update pi` | `package-manager-cli.ts:449`（self-update 执行段 `:218`）+ `utils/windows-self-update.ts`；本锚点新增旧版本清理 `pruneManagedReleases:137` | ❌ 只打印说明（`PackageCommand.java:20-22`） | 2 | **缺失** | — |
| E6-11 | `update --extensions` | `package-manager-cli.ts:449` | ❌ | 2 | **缺失** | — |
| E6-12 | `update --models`（模型目录刷新） | `package-manager-cli.ts:458` + `core/remote-catalog-provider.ts` | ❌ 子命令无；⚠️ **机制已在核心自动跑**（`ModelCatalogRefresh.java` + `RemoteCatalogProvider.java`，RPC 启动后台刷新） | 1 | **缺失** | — |
| E6-13 | `config` TUI（Tab 切作用域、逐资源开关） | `package-manager-cli.ts:813` + `config-selector.ts` | ⚠️ 非交互 `enable/disable`（`ConfigCommand.java:15`） | 2 | **存疑** | — |
| E6-14 | 持久化到 `settings.packages` | `package-manager.ts:112` | ❌ 无 `packages` 键 | **3** | **缺失** | — |
| E6-15 | pi manifest 读取 | `core/pi-manifest.ts` | ❌ | 2 | **缺失** | — |
| E6-16 | `remove` / `uninstall` | `package-manager.ts:118` | ⚠️ `PackageCommand.java:74-85` | 2 | **存疑** | — |

**E6 汇总：对齐 0 / 缺失 11 / 存疑 5 / 合计 16 = 0%**；**Σ权重 31 / Σ(w×c) 5.0 / 加权 16.1%**

### E7 RPC 面（6）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E7-1 | RPC 命令集（pi 33 / java 33） | `rpc-types.ts:22-74` 33 个 `type` | `RpcCommand.java:23-55` 33 个 `@JsonSubTypes`（含 `ClearQueue:236`，`:298` 分发） | **3** | 对齐 | B34 闭环，两边集合逐一对上；**批 3（2026-10-19，B247/docs/13）**：`get_commands` 线格式对齐——`data` 包 `{commands}` 键（rpc-types.ts:241）、条目 `(name, description?, source, sourceInfo)`、去内置（pi 语义＝可经 prompt 调用）；扩展臂随 B221（窄差 D5） |
| E7-2 | `extension_ui_request/response` 双向通道 | `rpc-types.ts:253-297`（9 method） | `RpcDispatcher.java:337-348` + `:57` `uiQueue` | 2 | **存疑** | 形有；`ui()` 无生产者 |
| E7-3 | `get_state` 状态字段 | `rpc-types.ts:96-109`（`model?: Model` 对象） | `RpcDispatcher.java:390` `buildState` / `RpcSessionState.java:20-32` | 2 | **存疑** | java 已补 `isCompacting`/`sessionFile`/`sessionId`/`pendingMessageCount`；**B31**（model 是字符串非对象，`RpcSessionState:21`）/ **B32**（sessionName 恒有默认值，`SessionPersistence:249-255`）/ **B33**（messageCount 取 entryCount） |
| E7-4 | RPC 事件线格式 | `modes/json-event.ts` | `mode/JsonEventMapper.java` | **3** | **存疑** | **B27/B28/B29**（另 `queue_update:98-99`、压缩/重试事件已上线） |
| E7-5 | RPC 命令线的 `Instant` 序列化 | pi 无此问题 | `rpc/JsonlWriter.java` | 1 | 对齐 | **B52 已修** |
| E7-6 | RPC `prompt` 回执 `disposition` | `rpc-types.ts:118` `PromptDisposition`（＝`"handled"\|"queued"\|"started"`，agent-session `:310-311`） | ✅ `RpcDispatcher.java:117-130`（steer/follow_up 恒 `"queued"`，prompt 透传 `SessionResult.disposition`，`SessionResult.java:59-61`） | 1 | **存疑** | 形状已落；java 只产 `"started"/"queued"`，**不产 `"handled"`**（命令在 prompt 内被处理的回执） |

**E7 汇总：对齐 2 / 缺失 0 / 存疑 4 / 合计 6 = 33.3%**；**Σ权重 12 / Σ(w×c) 8.0 / 加权 66.7%**

---

## G. 主流新增子系统（MCP / codemode / tool-search / virtual-models / nested-tool-calls）

这些是旧基准**不存在**的 pi 侧子系统（新包＋新扩展），均在 `bin pi` 主流路径上（`src/extensions/index.ts` 注册为 builtin 扩展，每会话加载）。
**MCP 面（G-1..G-5）的判定、证据与 LOC 整体在 `map/07-mcp.md`**；本表保留行与判定作汇总指针。**codemode / tool-search / virtual-models / nested（G-6..G-10）java 仍为零对应**；codemode 在两锚点间的增量（execute 630→733 等）按 R5 不展开。

| # | 能力单元 | pi 侧证据 | 权重 | 判定 | 说明 |
|---|---|---|---|---|---|
| G-1 | MCP 客户端 + 协议 + 连接运行时 | `packages/mcp`（3,244）＋ `extensions/mcp/runtime.ts`·`index.ts`（1,344） | 2 | **存疑** | **明细见 map/07**：客户端/协议/运行时已落；builtin 工厂接线卡在扩展系统六事件/激活门/`ui.custom`（B221，归属 D 域/E5） |
| G-2 | MCP 传输（stdio / streamable-http / in-memory） | `packages/mcp/src/transports/*` | 2 | **对齐** | **明细见 map/07**（三条传输全落） |
| G-3 | MCP OAuth（discovery + RFC 9207 + client ID metadata + callback server） | `packages/mcp/src/oauth/*` ＋ `extensions/mcp/oauth.ts` | 1 | **对齐** | **明细见 map/07**（两半全落；两侧均未端到端实测） |
| G-4 | `mcp.json` 配置 + `pi mcp` 子命令 + 审批 UI | `extensions/mcp/config.ts` 242、`cli.ts` 599、`ui.ts` 259；`main.ts:617` | 2 | **存疑** | **明细见 map/07**：配置面与 `pi mcp` 五个子命令已落（`subcommand/Mcp*`）；管理器 UI 未落（B214，随 B221 顺延） |
| G-5 | MCP 工具暴露（`codemode`/`deferred`/`direct`/`hidden`）+ 工具/资源接入模型 | `core/mcp-servers.ts`（346）、`extensions/mcp/tools.ts`、`resources.ts` | 2 | **对齐** | **明细见 map/07**：exposure ADT 与 `AgentTool` 适配全落；激活路径依赖 B161/B162（B203） |
| G-6 | codemode 沙箱运行时（quickjs-wasi host/worker/protocol/prelude） | `packages/codemode`（2,597）＋ `extensions/codemode/execute.ts`（733） | 2 | **缺失** | builtin 扩展；R5 面外增量不展开 |
| G-7 | codemode 工具（执行 JS、描述/inlineBudget、`mode: on\|only`、renderer） | `extensions/codemode/tool.ts`（420）、`renderer.ts`（150）；`settings-schema.ts:154-167` | 2 | **缺失** | |
| G-8 | `tool_search` 工具（BM25 排序 + 按需加载 deferred 工具） | `extensions/tool-search/`（265 行） | 2 | **缺失** | builtin 扩展 |
| G-9 | 虚拟模型（registry + route + 状态条目） | `core/virtual-models.ts`（238 行）＋ `ExtensionAPI.registerVirtualModel:1883` | 2 | **缺失** | 配置后走 |
| G-10 | 嵌套工具调用（`ctx.executeTool()`、`nestedCalls`、大小上限） | `core/nested-tool-calls.ts`（263 行） | 1 | **缺失** | 内部支撑 |

**G 汇总：对齐 3 / 缺失 5 / 存疑 2 / 合计 10 = 30.0%**；**Σ权重 18 / Σ(w×c) 7.0 / 加权 38.9%**

> **模块归属**：MCP 面的 Java 代码主在**新模块 `pi-java-mcp`**（主源 10,853 行）；工具与资源的 `AgentTool` 适配在本模块 `extension/mcp`（1,762 行）—— `AgentTool` 在 agent-core，而 `pi-java-mcp` 看不见它（`ai ← mcp`）。映射裁决与 B205 见 map/07。
> 部分落地的行记「存疑」（形状/一半存在），**不拆行**（拆行会改权重层归属）。
> 其余「其它」新增面**已折叠进既有单元**，不另计：`defaultTools` 的 `+name/-name` → **E2-35/A-20**；`/reload` 激活新加入工具 → **E5-29**；RPC `prompt` disposition → **E7-6**；`/login` 提供 Radius 与 MCP 设置 → **C-18**；fullscreen 默认 → **E2-51**；`codemode` 设置键 → **E2-45**；`--no-mcp` → map/07。

### E8 边缘/面外子系统（与 E3/C/G 重叠，不计入模块合计）

| # | 能力单元 | pi 侧证据 | 权重 | 判定 |
|---|---|---|---|---|
| E8-1 | 提示缓存预热 | `core/cache-warmer.ts` 453 行（已计入 E3-24） | 2 | **对齐**（随 E3-24，批 14 核销） |
| E8-2 | bug 上报（`/bug`） | `core/bug-report.ts` 375 + `bug-report-upload.ts` 37 + `modes/interactive/bug-report.ts`（已计入 C-9） | 1 | **缺失** |
| E8-3 | 崩溃日志（crash log） | `core/crash-log.ts` **170 行** | 1 | **缺失** |
| E8-4 | 扩展加载器依赖延迟（jiti lazy / Bun·SEA static） | `core/extensions/jiti-loader.ts` 3 + `jiti-static-loader.ts` + `virtual-modules.ts` 38 | 1 | **缺失（不适用）** |
| E8-5 | ~~`experimental/micro`~~ | **整块被 `7fd478a2e` 删除 ⇒ R1 作废（权重 0，移出分母）** | ~~1~~ | **作废** |
| E8-6 | WSL / zip 工具 | `utils/wsl.ts` + `utils/zip.ts` | 1 | **缺失** |
| E8-7 | 系统提示词分节构建与差分 | `core/system-prompt.ts`（229 行） | 1 | **缺失** |

**E8 汇总（不计入模块）：对齐 0 / 缺失 6 / 存疑 0 / 作废 1**；**Σ权重 7 / Σ(w×c) 0 / 加权 0%**

---

## 整块缺失

| 子系统 | pi LOC | 权重 | 说明 |
|---|---:|---:|---|
| **扩展示例库 `examples/`（可执行验收规格）** | **16,033** | **3** | 105 个 `.ts`。**pi-java 零对应物**（只有 5 个测试夹具）。这些示例同时是 pi 扩展 API 的**行为规格** |
| **JSON 主题系统** | **2,417**（`.ts` 2,261 + JSON 156） | 2 | `theme.ts` + `theme-controller.ts` + `system-theme.ts` + typebox `theme-schema.ts` + `theme-tokens.ts` + dark/light.json；pi-java 只有 113 行 `PiTheme.java`（pi-java-tui）+ 2 个内建 `.tcss` |
| **npm / git 包源 + 资源优先级 + 忽略文件** | 2,760（`package-manager.ts`） | **3** | pi 是完整包生态层；pi-java 是「下 JAR 到目录」 |
| **自更新（self-update）** | ~300 | 1 | pi `update self` 换二进制（含 managed release 清理）；pi-java 只打印「走 Maven Central」 |
| **TUI 扩展 UI 注入层** | ~700 | 2 | widget / overlay / footer·header / 按键拦截 / 编辑器注入全缺 |
| **交互式会话选择器（`-r`）** | 1,294（`session-selector.ts` 1,045 + `session-selector-search.ts` 194 + `cli/session-picker.ts` 55） | 2 | pi-java 无 `-r` 无参路径（抛异常） |

**整块缺失汇总：对齐 0 / 缺失 6 / 存疑 0 / 合计 6 = 0%**；**Σ权重 13 / Σ(w×c) 0 / 加权 0%**
> ⚠️ 与 E4/E6 有重叠（「JSON 主题系统」↔ E4-4、「npm/git 包源…」↔ E6-3/4/6/7/8）。**模块合计按含重叠计**，另给去重口径见 §加权汇总。

---

## 台账纠错

逐条核对了 `docs/04-open-items-register.md` 中属于本模块的条目。

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **B53** | 「`find()` 走 `all()` 越界」 | ✅ **属实**。jsonl `PersistentSessionRepositories.java:59-66` 与 sqlite `:139-146` 仍走 `all()`/`list(null)`。pi `resolveSessionPath`（`main.ts:252-283`）多了**路径形快道**（`:253-256`），三级阶梯不变。⇒ 真差距仍是**次序与成本**（缺 header-only ①②） | `PersistentSessionRepositories.java:63`；`main.ts:243-283` |
| **B54** | 「`--no-session` 在 web 路径被忽略」 | ✅ **属实**（java 侧未变）。`resolvePersistentWeb` 在 `:149`，`:149-164` 不引用 `args` | `SessionPersistence.java:149-164` |
| **B55** | 「pi 会话列表 API 带 `onProgress`」 | ✅ **属实**。`listSessionsFromDir`（`:941`）＋ `AbortSignal` ＋ `publishPartial`；java `list`（`:74-76`）同步全量 | `session-manager.ts:941`；`PersistentSessionRepositories.java:74-76` |
| **B30** | 「`session_info_changed` 无生产者」 | ❌ **已过期 ⇒ 销号**：生产者早已在（`AgentSession.java:706` emit）；批 4 扩展面桥接（D-4 翻对齐） | `AgentSession.java:706` |
| **B35** | 「`thinking_level_changed` 无生产者」 | ❌ **已闭环 ⇒ 销号**：宿主（`AgentSession.java:513`）与扩展面（批 4，D-37 翻对齐，`previousLevel` 桥内跟踪）双补 | `AgentSession.java:513` |
| **B1** | 「branch summary 无实现」 | ✅ **属实**。`Settings.java` 无 `branchSummary`，全模块无分支摘要代码 | `Settings.java:26-66` |
| **B32** | 「`get_state.sessionName` 恒有值」 | ✅ **属实**。`SessionPersistence.java:249-255` `sessionNameOf` 兜底 `"session"` | `SessionPersistence.java:249-255` |
| **F3** | 「`_emitSessionCompactFailed`」 | ❌ **已闭环 ⇒ 销号**：批 4 桥接（`CompactionEnd` 失败/中止臂 ⇒ `session_compact_failed`，D-9 翻对齐）。pi 侧 `aborted` 判定为**信号权威**（`agent-session.ts:2887`） | `AgentSessionEvent.java:87` |
| **F6** | 「`ModelsJsonProvider` 钉死 baseUrl」 | ⚠️ **本模块范围外**；症状仍在（`Args.baseUrl` 在 coding-agent 侧不消费） | `Args.java:60` |
| **A4** | 「运行中手动 `/compact` 有没有门」 | ❌ **仍缺（abort-first 半）**。pi 语义是 **abort-first**：`compact()`（`agent-session.ts:2764`）第一句 `await this.abort()`（`:2765`），自建 `_compactionAbortController`（`:2766`）；`_getSummarizationRequestAuth(model, signal)`（`:2727`）；`aborted = signal.aborted \|\| cancelledByExtension`（`:2887`）；`abortCompaction()`（`:2914`）。java 侧落的是**另一件事**——「压缩自身可取消」：压缩窗口（`LaneState.enterCompaction` 仅计数，不中止在飞运行）＋信号检查＋「Compaction cancelled」中止路（agent-core `CompactionExecutor.java:80-121`，B175/B176）；宿主入口 `MiscCommands.java:126-138`/`AgentSession.java:716` 无 isRunning 守卫、不调 `abort()` ⇒ 运行中压缩不中止回合。开放项另见 map/03 #17 与 B36 | pi `agent-session.ts:2727/2764-2766/2887/2914`；java `CompactionExecutor.java:80-121`、`LaneState.java:181`、`MiscCommands.java:126` |
| **A7** | 「`retry` 子对象保形不接线」 | ✅ **已闭环**：`getRetrySettings()`（`SettingsAccessors.java:197`，消费 `AgentSession.java:317`）+ `getProviderRetrySettings()`（`:218`） | 同证据 |
| **A9** | 「per-model 压缩设置」 | ✅ **已闭环**：`Settings.Compaction.modelOverrides`（`Settings.java:140`，`ModelOverride:144-147`）+ `getCompactionSettings(ModelId)` 精确键三级回退（`SettingsAccessors.java:260-300`），接线 `AgentSession.java:420/717-718` | 同证据 |
| **B46** | 「`tool_execution_update` 生产无数据源」 | ❌ **扩展面已闭环 ⇒ 销号**：宿主转发（`SessionRunner.java:234-237`）＋批 4 桥接（D-34 翻对齐）。是否有工具实际发 update 事件取决于 agent-core 工具实现（观察面语义不受影响） | `SessionRunner.java:235` |

**未发现反向误报**。

---

## 未加权汇总

| 分区 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| A. CLI 参数 | 41 | 1 | 1 | 43 | 95.3% |
| B. 子命令 | 3 | 1 | 4 | 8 | 37.5% |
| C. Slash 命令 | 11 | 2 | 12 | 25 | 44.0% |
| D. 扩展系统 41 事件 | 17 | 17 | 7 | 41 | 41.5% |
| D′. 扩展 UI 注入面 | 0 | 8 | 1 | 9 | 0% |
| E. 其余能力单元 | 51 | 65 | 32 | 148 | 34.5% |
| ├ E1 配置文件 | 2 | 1 | 2 | 5 | 40.0% |
| ├ E2 settings 键 | 46 | 1 | 6 | 53 | 86.8% |
| ├ E3 会话管理 | 22 | 2 | 0 | 24 | 91.7% |
| ├ E4 资源发现 | 5 | 2 | 0 | 7 | 71.4% |
| ├ E5 扩展 API | 6 | 25 | 4 | 35 | 17.1% |
| ├ E6 包管理器 | 0 | 11 | 5 | 16 | 0% |
| └ E7 RPC 面 | 2 | 0 | 4 | 6 | 33.3% |
| G. 主流新增子系统（MCP/codemode/…） | 3 | 5 | 2 | 10 | 30.0% |
| F. 整块缺失（子系统） | 0 | 6 | 0 | 6 | 0% |
| **合计（模块，不含 E8）** | **158** | **82** | **48** | **288** | **54.9%** |
| E8. 边缘/面外子系统（**与 E3/C/G 重叠，不计入**） | 0 | 6 | 0 | 6 | 0% |

**严格口径**（把「引擎钩子存在但未桥接」的 5 条——#7/#12/#17/#18/#19——也算缺失）：对齐 **153** / 缺失 **87** / 存疑 **48** / 合计 **288** = **53.1%**。
> 批 14（2026-10-23，docs/25）：本表逐行实算收平——A 域 11 行＋E3 域 7 行翻动；E2 行同步批 13 台账文本（对齐 44→46、E2-45 出分母 54→53）；分母 290→288 为逐行实算值（含往批汇总滞后吸收）。

**扩展系统单独口径**（D + D′ + E5）：41 事件 = 17 对齐；UI 注入 9 面 = 0 对齐；扩展 API 35 项 = 6 对齐 ⇒ **85 个单元中 23 个对齐 = 27.1%**。

**java 独有（不计入分母）**：`--base-url`、`--port`、`--debug`、`--trace-payloads`（CLI）；`/help`、`/create-skill`（slash）；`auth oauth-login`、`auth profile…`（子命令）。

**最大三处差距**（批 4 后）：① **E6 包管理器**且**零对齐单元**；② **扩展系统长尾**——MCP 面主体已落（明细见 map/07），但 **codemode（3,957 行）＋ tool-search/virtual-models/nested（766 行）共 4,723 行仍为 0**，且 MCP 的 builtin 工厂接线卡在 D/E5 扩展面（B221）；③ **D 域剩余 24 个未对齐事件**（provider 请求面 B248、结果臂族 B249、生命周期 B250、tree 两事件 B245、D′ UI 注入面）。
**其余长期缺口**：④ `cache_warming` 设置＋`/bug`＋RPC `"handled"` disposition（已分别计入 E2/C/E7；`defaultTools` 与 `--tools` pattern/modifier 批 1 已对齐）。

---

## 加权汇总

> 口径：权重 = 用户可观察影响 × 频率（**不含排期优先级**）；`3` = 每轮对话都走 / 默认路径，`2` = 每次会话走 / 常用命令·参数·配置键，`1` = 低频·边缘·纯内部，`0` = 非目标。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A. CLI 参数 | 62 | 60.5 | 97.6% | 95.3% |
| B. 子命令 | 15 | 9.0 | 60.0% | 37.5% |
| C. Slash 命令 | 38 | 26.0 | 68.4% | 44.0% |
| D. 扩展系统 41 事件 | 83 | 75.0 | 90.4% | 87.8% |
| D′. 扩展 UI 注入面 | 16 | 1.0 | 6.3% | 0% |
| E1. 配置文件 / 清单 | 10 | 6.0 | 60.0% | 40.0% |
| E2. settings.json 键 | 84 | 76.5 | 91.1% | 85.2% |
| E3. 会话管理行为 | 40 | 37.0 | 92.5% | 91.7% |
| E4. 资源发现 | 14 | 10.0 | 71.4% | 71.4% |
| E5. 扩展 API 面 | 58 | 16.0 | 27.6% | 17.1% |
| E6. 包管理器 | 31 | 5.0 | 16.1% | 0% |
| E7. RPC 面 | 12 | 8.0 | 66.7% | 33.3% |
| G. 主流新增子系统 | 18 | 7.0 | 38.9% | 30.0% |
| F. 整块缺失（子系统） | 13 | 0.0 | 0% | 0% |
| **模块合计（不含 E8）** | **494** | **338.5** | **68.5%** | **60.7%** |
| E8. 边缘/面外子系统（与 E3/C/G 重叠，不计入） | 7 | 0.0 | 0% | 0% |

**模块合计**：Σ权重 494 ／ Σ(w×c) 338.5 ／ **加权完成度 = 338.5/494 ≈ 68.5%**（未加权 175.5/289 ≈ 60.7%；批 14 后值——按 Δ 续列：批 13 的 319.5＋本批 19.0）。

**去重口径**（F 域有 2 行与 E4/E6 重叠＋G-1..G-5 归 map/07）：Σ权重 **485** ／ Σ(w×c) **334.0** ／ **加权完成度 = 334.0/485 ≈ 68.9%**（未加权 175.5/289 ≈ 60.7%）。
> 口径注记（批 14）：A/E3 域行与未加权汇总表已逐行实算（含往批滞后收平：E2 对齐 44→46、E3 段汇总 27.5→28.5 的批 10 滞后）；模块加权合计按 Δ 续列，与逐行域和的 ±1.5 存量口径差随下次全量重测一并收平（批 4 同例）。

**扩展系统单独口径**（D + D′ + E5）：Σ权重 157 ／ Σ(w×c) 63.0 ／ **加权 40.1%**（未加权 24/85 ≈ 28.2%）。

**权重分层**：

| 权重层 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|---:|
| 权重 3（每轮 / 默认路径） | 23 | 69 | 45.0 | **65.2%** | 14/23 = 60.9% |
| 权重 2（每次会话 / 常用） | 160 | 320 | 165.5 | 51.7% | 67/160 = 41.9% |
| 权重 1（低频 / 边缘 / 内部） | 107 | 107 | 50.5 | 47.2% | 37/107 = 34.6% |
| **合计** | **290** | **496** | **261.0** | **52.6%** | **115/290 = 39.7%** |

> ⚠️ 分层合计（261.0）与模块逐行实算（261.5）差 0.5，为存量基线口径差（旧账分层 226.5 vs 域汇总 226.0 同源），随下次全量重测一并收平。

> 读数：**权重 3 层（65.2%）继续领跑**。批 coding-agent-4（docs/14）五条 w3 翻对齐（`context`/`context_with_system`/`message_update`/`tool_call`/`tool_result`，+10.5），w3 对齐数 9 → 14。
> ⚠️ w3 层剩余欠账（批 7 后）：扩展系统 1 个（#17 扩展示例库）＋包管理器 3 个（#18/#19/#23＝E6 域）＋RPC #21 存疑＋整块 #22。~~#5~~（批 7）、~~#6/#8~~（批 5）。

### 权重 3 单元（23 个 —— 最该先修）

| # | 域 | 单元 | 权重 | 判定 |
|---|---|---|---|---|
| 1 | A | `--model` | 3 | 对齐 |
| 2 | A | `--print`/`-p` | 3 | 对齐 |
| 3 | D | `context` | 3 | 对齐（批 4） |
| 4 | D | `context_with_system` | 3 | 对齐（批 4） |
| 5 | D | `before_provider_request` | 3 | 对齐（批 7） |
| 6 | D | `before_agent_start` | 3 | 对齐（批 5） |
| 7 | D | `message_update` | 3 | 对齐（批 4） |
| 8 | D | `input` | 3 | 对齐（批 5） |
| 9 | D | `tool_call` | 3 | 对齐（批 4） |
| 10 | D | `tool_result` | 3 | 对齐（批 4） |
| 11 | E2 | `defaultProvider` | 3 | 对齐 |
| 12 | E2 | `defaultModel` | 3 | 对齐 |
| 13 | E2 | `compaction`（含 modelOverrides） | 3 | 对齐 |
| 14 | E3 | 新建会话（无参） | 3 | 对齐 |
| 15 | E4 | 上下文文件发现（AGENTS.md/CLAUDE.md） | 3 | 对齐 |
| 16 | E5 | `registerTool` | 3 | 对齐 |
| 17 | E5 | 扩展示例库 | 3 | **缺失** |
| 18 | E6 | npm 源 | 3 | **缺失** |
| 19 | E6 | 持久化到 `settings.packages` | 3 | **缺失** |
| 20 | E7 | RPC 命令集（33/33） | 3 | 对齐 |
| 21 | E7 | RPC 事件线格式 | 3 | **存疑** |
| 22 | F | `examples/` 可执行验收规格 | 3 | **缺失** |
| 23 | F | npm/git 包源 + 优先级 + 忽略文件 | 3 | **缺失** |

> 权重 3 层 23 个单元中（批 7 后实算）：**对齐 17 / 存疑 1 / 缺失 5**（Σ(w×c) 52.5 / 69 = 76.1%）。
> 归属：扩展系统仅剩 **1 个**（#17 扩展示例库——#5 批 7 翻、#6/#8 批 5 翻〔本表批 5 滞后，批 7 一并修正〕）；包管理器 **3 个**（#18/#19/#23）；其余散在 RPC（#21 存疑）与整块缺失（#22）。

### 未加权 vs 加权的差

| 指标 | 数值 |
|---|---|
| 未加权完成度 | 39.7%（115/290） |
| 加权完成度（含 F 域） | **52.7%**（261.5/496） |
| 差 | **+13.0 pp** |
| 加权完成度（去重，剔 F 域） | 54.1%（261.5/483）＝ +14.4 pp |

**差在哪**：加权把完成度**抬高** 13.0 pp，因为 pi-java 的对齐单元**密集落在高权重的默认路径上**，而缺失质量集中在低权重长尾。
- **抬高项**：A 域（CLI）未加权 69.8% → 加权 80.6%；B 域 37.5% → 60.0%；C 域 44.0% → 68.4%；E3 58.3% → 68.8%；E7 33.3% → 66.7%；**G 域 30.0% → 38.9%**；**D 域 41.5% → 53.0%**。
- **压低项**：E5 17.1% → 27.6%；E6 0% → 16.1%。E4 持平（71.4% ＝ 71.4%，无 w3 缺口）。

- **两个点名项**：
  - **扩展事件面**：D 域 Σ权重 **83** = 模块 496 的 **16.7%**，是全部 15 个域里**最大的一块**。批 4 后 Σ(w×c) 44.0 ⇒ 若 D 域全对齐，模块 Σ(w×c) 261.5 → **300.5**、完成度 52.7% → **60.6%**，即**这一域单独把模块拉低 7.9 pp**（批 4 前 14.6 pp）。
  - **口径修正建议**（沿用）：整块缺失的子系统应按「暴露的行为面个数」而非 1 计入分母。本表按用户给定规则计。
