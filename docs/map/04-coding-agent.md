# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 范围：`D:\workplaceForai\pi-java\pi-java-coding-agent` ↔ `D:\workplaceForai\pi\packages\coding-agent`。
> 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差，或台账未结）。
> 所有证据为 `file:line`；pi 侧路径省略 `D:\workplaceForai\pi\packages\coding-agent\` 前缀，java 侧省略 `D:\workplaceForai\pi-java\pi-java-coding-agent\src\main\java\com\pijava\coding\agent\` 前缀。
> 基准：pi-java @ 351d199（main，2026-10-04）；pi @ 200387122（2026-10-04）。
> 上一版基准：pi `3390bd936`（2026-09-20）＋ pi-java `34849a2`。**本轮是换锚，不是刷新**，删除面裁决见 `../11-pi-reanchor-ruling.md`。
> pi 侧 `coding-agent/src` **73,781 → 84,990 行（+15.2%）**，**277 个改动的 `.ts`**；pi-java 侧未静止（23 个提交触达本模块）。
> **R1 作废**：`src/experimental/micro/**`（8）与 `src/experimental/mini/**`（15）整块被 `7fd478a2e` 删除 ⇒ 本图相关单元**作废（权重 0，移出分母）**。
>
> **权重口径**（用户已认可）：权重 = 用户可观察影响 × 频率，**不含排期优先级**。
> `3` = 每轮对话都走 / 默认路径；`2` = 每次会话走 / 常用命令·参数·配置键；`1` = 低频·边缘·纯内部；`0` = 非目标（排除出分母）。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。加权汇总见文末 §加权汇总。
> **R5 可达性**：只算 `bin pi` 主流可达的；`coding-agent/src/experimental/**` 一律权 0。

---

## 规模

| 模块 | pi 包 | pi LOC（旧 → 新） | java LOC | 比例 |
|---|---|---:|---:|---:|
| **产品层合计（src/main）** | `packages/coding-agent/src` | **73,781 → 84,990** | **11,188** | **13.2%** |
| ├ 扩展系统 | `core/extensions` 4,210 → **4,992** + `src/extensions` 1,483 → **7,154** | 12,146 | 1,516 (`extension/` 717 + `agent-core/hook/` 799) | 12.5% |
| ├ 包管理器 | `package-manager.ts` 2,699 → **2,760** + `package-manager-cli.ts` 1,102 → **1,119** + `pi-manifest.ts` 35 + `tools-manager.ts` 400 | 4,314 | 496 | 11.5% |
| ├ 主题系统 | `modes/interactive/theme/*`（`.ts`） | 1,552 → **2,215** | 113 | 5.1% |
| ├ 会话管理 | `core/session-manager.ts` | 1,871 → **2,013** | 771 | 38.3% |
| ├ 资源发现 | `skills.ts` 509 + `prompt-templates.ts` 285 → **320** + `resource-loader.ts` 1,097 → **1,275** | 2,104 | 896 | 42.6% |
| ├ RPC | `modes/rpc/*` | 1,785 → **1,797** | 1,251 | 69.6% |
| ├ CLI/TUI 交互 | `modes/interactive/*` 21,218 → **21,626** + `cli/*` 1,921 → **1,942** + `utils/*` 3,680 → **3,698** | 27,266 | ~3,000 | ~11.0% |
| ├ **主流新增子系统**（MCP / codemode / tool-search / virtual-models / nested-tool-calls） | `src/extensions/mcp` 4,093 + `src/extensions/codemode` 1,238 + `core/mcp-servers.ts` 319 + `core/virtual-models.ts` 238 + `core/nested-tool-calls.ts` 261 +（新包）`packages/mcp` 3,167 + `packages/codemode` 1,678 | **10,994** | 10,700 **ᵇ** | **97.3%** |
| └ **可执行验收规格 examples/** | `examples/`（105 个 `.ts`） | 15,809 → **16,033** | **0** | **0%** |
| 测试 | `test/` | 59,519 → **72,460** | 6,844 | 9.4% |
| **（作废，R1）** `experimental/{micro,mini}/` | 1,485 + （mini） | **0（删除）** | — | — |

> java 侧 11,188 行（旧 10,325）：`core` 5,830 / `rpc` 1,251 / `extension` 717 / `skill` 613 / `cli` 607 / `subcommand` 583 / `export` 489 / `mode` 450 / `prompt` 283 / `modes` 195 / `spi` 44 / 顶层 126。
> pi 侧 `core` 29,802 → **34,953**；`modes` 23,669；`extensions` 7,154；`utils` 3,698；`cli` 1,942；`experimental` 9,950（**权 0**）；根 `.ts` 3,562。
> **`interactive-mode.ts` 6,779 → 7,037**（+258）。
> **扩展系统的 799 行在 `pi-java-agent-core/src/main/java/com/pijava/agent/hook/`**，不在本模块 —— 且**没有桥接到 `PiExtension`**（见 §扩展系统）。
> **ᵇ 主流新增子系统的 10,700 行在 `pi-java-mcp/src/main/java/com/pijava/mcp/`**（新模块，MCP 面：协议/McpClient/传输/OAuth/配置/连接运行时，包①–⑨已闭环，142 文件）；该行的 pi 分母还含 **codemode（2,916）＋ tool-search/virtual-models/nested（499）** 三段，java 侧仍为 0 ⇒ 97.3% 是**混合行**的比例，不是 MCP 面的完成度（MCP 面单独看：java 10,700 / pi 7,579 = **141.2%**）。
> ⚠️ 该比例**超过 100% 不代表「超额对齐」**——Java 的 record/javadoc/显式类型比 TS 冗长（包⑨ 单行 javadoc 与 `Objects.equals` 这类显式写法即是），LOC 比在这里只能作「大致铺满」的粗指标；判完成度一律看 §G 表的判定列。

---

## A. CLI 参数对齐

逐条数：pi `src/cli/args.ts` 共 **40 个 flag** + `--` + `@files` + 扩展注册 flag = **43 个能力单元**（**flag 集本轮零变动**；`args.ts` 448 → 462 行，仅帮助文本与 `--print` 后续参数判据微调）。
java `cli/ArgsParser.java` 共 **43 个 `@Option`**，其中 **4 个 java 独有**（`--base-url:36`、`--port:63`、`--debug:144`、`--trace-payloads:147`）。

| # | 参数/子命令 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `--help`/`-h` | `args.ts:91` | ✅ `ArgsParser.java:54` | 2 | 对齐 | `Main.java:57` exit 0 |
| 2 | `--version`/`-v` | `args.ts:93` | ✅ `ArgsParser.java:57` | 1 | 对齐 | `Main.java:61` |
| 3 | `--mode` | `args.ts:95` | ✅ `ArgsParser.java:60` | 2 | 对齐 | java 多 `web`（`Args.java:26`） |
| 4 | `--continue`/`-c` | `args.ts:110` | ✅ `ArgsParser.java:48` | 2 | 对齐 | `SessionPersistence.java:169` |
| 5 | `--resume`/`-r` | `args.ts:112` | ⚠️ `ArgsParser.java:51` | 2 | **存疑** | pi 无 `--session` 时**开选择器**（`main.ts:417`）；java `SessionPersistence.java:187` 走 `find(null)` ⇒ 抛 `Session not found: null` |
| 6 | `--provider` | `args.ts:114` | ✅ `ArgsParser.java:27` | 2 | 对齐 | |
| 7 | `--model` | `args.ts:116` | ✅ `ArgsParser.java:30` | **3** | 对齐 | 均支持 `provider/id` 与 `:thinking` |
| 8 | `--api-key` | `args.ts:118` | ✅ `ArgsParser.java:33` | 2 | 对齐 | |
| 9 | `--system-prompt` | `args.ts:120` | ✅ `ArgsParser.java:39` | 1 | 对齐 | |
| 10 | `--append-system-prompt` | `args.ts:122` | ✅ `ArgsParser.java:42` | 1 | 对齐 | 均 repeatable |
| 11 | `--name`/`-n` | `args.ts:125` | ✅ `ArgsParser.java:66` | 1 | 对齐 | `AgentSession.java:503` |
| 12 | `--no-session` | `args.ts:131` | ⚠️ `ArgsParser.java:69` | 1 | **存疑** | 非 web 生效；**web 路径被忽略**（`SessionPersistence.java:149` 的 `args` 零使用）—— 台账 **B54** |
| 13 | `--session` | `args.ts:133` | ⚠️ `ArgsParser.java:72` | 2 | **存疑** | 行为有差：java `find()` 走 `repo.list(all())`（`PersistentSessionRepositories.java:63`）⇒ **O(全部) 且跨项目**；pi `main.ts:252-281` 三级阶梯 —— 台账 **B53** |
| 14 | `--session-id` | `args.ts:135` | ⚠️ `ArgsParser.java:75` | 2 | **存疑** | 同上（B53）；缺失时 java 创建（`SessionPersistence.java:180`）↔ pi `main.ts:436-445` |
| 15 | `--fork` | `args.ts:137` | ⚠️ `ArgsParser.java:78` | 1 | **存疑** | 同上（B53）；`SessionPersistence.java:192` |
| 16 | `--session-dir` | `args.ts:139` | ✅ `ArgsParser.java:81` | 1 | 对齐 | `AgentSession.java:126/159/175` |
| 17 | `--models` | `args.ts:141` | ❌ `ArgsParser.java:84` | 2 | **缺失** | `args.models()` **零消费者** ⇒ Ctrl+P 循环列表无实现 |
| 18 | `--no-tools`/`-nt` | `args.ts:146` | ✅ `ArgsParser.java:93` | 1 | 对齐 | `-nt` 由 `:257` 展开 |
| 19 | `--no-builtin-tools`/`-nbt` | `args.ts:148` | ✅ `ArgsParser.java:96` | 1 | 对齐 | |
| 20 | `--tools`/`-t` | `args.ts:150` | ✅ `ArgsParser.java:87` | 2 | 对齐 | |
| 21 | `--exclude-tools`/`-xt` | `args.ts:155` | ✅ `ArgsParser.java:90` | 1 | 对齐 | |
| 22 | `--thinking` | `args.ts:160` | ✅ `ArgsParser.java:45` | 2 | 对齐 | 两侧都是 7 档 `off..max`（`ThinkingLevels.java:33`） |
| 23 | `--print`/`-p` | `args.ts:170` | ✅ `ArgsParser.java:126` | **3** | 对齐 | pi 吞下紧随的非 `@`/`-` 参数（`:173`） |
| 24 | `--export` | `args.ts:177` | ⚠️ `ArgsParser.java:129` | 1 | **存疑** | pi 支持 **2 个位置参数**（`args.ts:385`）；java 只取 1 个（`Main.java:99`） |
| 25 | `--extension`/`-e` | `args.ts:179` | ❌ `ArgsParser.java:99` | 2 | **缺失** | `args.extensions()` **零消费者** ⇒ 显式扩展文件路径无实现 |
| 26 | `--no-extensions`/`-ne` | `args.ts:182` | ✅ `ArgsParser.java:102` | 1 | 对齐 | `AgentSession.java:406` |
| 27 | `--skill` | `args.ts:184` | ✅ `ArgsParser.java:105` | 1 | 对齐 | `SkillDiscovery.java:36` |
| 28 | `--prompt-template` | `args.ts:187` | ✅ `ArgsParser.java:111` | 1 | 对齐 | `PromptTemplates.java:47` |
| 29 | `--theme` | `args.ts:190` | ⚠️ `ArgsParser.java:117` | 1 | **存疑** | pi 收 **JSON 主题名或文件**；java 只收 `.tcss` 路径（`PiTheme.java:97`） |
| 30 | `--use-theme` | `args.ts:193` | ❌ — | 1 | **缺失** | java 无此 flag |
| 31 | `--no-skills`/`-ns` | `args.ts:201` | ✅ `ArgsParser.java:108` | 1 | 对齐 | |
| 32 | `--no-prompt-templates`/`-np` | `args.ts:203` | ✅ `ArgsParser.java:114` | 1 | 对齐 | |
| 33 | `--no-themes` | `args.ts:205` | ✅ `ArgsParser.java:120` | 1 | 对齐 | |
| 34 | `--no-context-files`/`-nc` | `args.ts:207` | ❌ `ArgsParser.java:123` | 2 | **缺失** | `args.noContextFiles()` **零消费者**；**AGENTS.md/CLAUDE.md 发现子系统整体不存在** |
| 35 | `--list-models` | `args.ts:209` | ✅ `ArgsParser.java:132` | 1 | 对齐 | 均可选搜索词（`cli/list-models.ts:29`） |
| 36 | `--tui-mode` | `args.ts:216` | ✅ `ArgsParser.java:138` | 1 | 对齐 | 默认均为 fullscreen |
| 37 | `--verbose` | `args.ts:230` | ✅ `ArgsParser.java:141` | 1 | 对齐 | |
| 38 | `--approve`/`-a` | `args.ts:232` | ✅ `ArgsParser.java:150` | 2 | 对齐 | |
| 39 | `--no-approve`/`-na` | `args.ts:234` | ✅ `ArgsParser.java:153` | 1 | 对齐 | |
| 40 | `--offline` | `args.ts:236` | ⚠️ `ArgsParser.java:135` | 1 | **存疑**（本轮改判） | java 现在**有消费者**：`ModelCatalogRefresh.java:78` 用它跳过联网目录刷新（`PI_OFFLINE` 亦读）—— 但只覆盖目录刷新，pi 的 offline 面更广 |
| 41 | `--`（选项终止） | `args.ts:82-89` | ⚠️ picocli 内建 | 1 | **存疑** | java 未显式处理（无夹具） |
| 42 | `@files` 位置参数 | `args.ts:238` | ✅ `ArgsParser.java:212` | 2 | 对齐 | 两侧都剥离 `@` 前缀进 `fileArgs` |
| 43 | 扩展注册的 CLI flag | `args.ts:247` + `registerFlag`/`getFlag`（`types.ts:1651/1667`） | ❌ | 1 | **缺失** | java `ArgsParser.java:160` 只把未知 flag 收进 `unmatched`，**无 `registerFlag` API** |

**汇总：对齐 29 / 缺失 5 / 存疑 9 / 合计 43 = 67.4%**；**Σ权重 62 / Σ(w×c) 48.0 / 加权 77.4%**
> 本轮改判：`--offline`（A-40）缺失 → **存疑**（java 侧新增目录刷新消费者）。

### B. 子命令对齐

pi `main.ts:614`（`mcp`）＋ `handlePackageCommand`/`handleConfigCommand`/`runAuthCommand` 共 **8 个**；java `SubcommandHandler.java:12` 同 7 个 **＋ `mcp` 缺失**。

| # | 子命令 | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `install <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:44` | 2 | **存疑** | java 只支持**本地 JAR 路径或 http(s) URL**；pi 支持 npm / git / local 三源 |
| 2 | `remove <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:55` | 2 | **存疑** | 同上 |
| 3 | `uninstall <source>`（别名） | ✅ | ✅ `SubcommandHandler.java:36` | 1 | 对齐 | |
| 4 | `update [source\|self\|pi]` | ✅ `package-manager-cli.ts:336/436` | ❌ `PackageCommand.java:20` | 2 | **缺失** | java 只打印 `SELF_UPDATE_NOTE`，**无 self-update、无扩展更新、无模型目录刷新子命令**（⚠️ 目录刷新机制本身已在核心自动跑，见 E6-12） |
| 5 | `list` | ✅ `package-manager-cli.ts:974` | ⚠️ `PackageCommand.java:63` | 2 | **存疑** | pi 按 user/project 分组 + `installedPath`；java 平铺 |
| 6 | `config [-l]` | ✅ `package-manager-cli.ts:796`（**TUI**） | ⚠️ `ConfigCommand.java:12` | 2 | **存疑** | java 是**非交互** `config enable\|disable` |
| 7 | `auth <cmd>` | ✅ check / print-api-key / print-bearer-token（`auth-command.ts:52-61`） | ✅ `AuthCommand.java:43-47` | 2 | 对齐 | java 是**超集**：多 `oauth-login`、`profile set/unset/list/set-key` |
| 8 | `mcp`（**本轮新增**，`main.ts:614`） | ✅ `runMcpCommand`（`extensions/mcp/cli.ts`） | ❌ | 2 | **缺失** | java 无 MCP 子系统 |

**汇总：对齐 2 / 缺失 2 / 存疑 4 / 合计 8 = 25.0%**；**Σ权重 15 / Σ(w×c) 7.0 / 加权 46.7%**

---

## C. Slash 命令对齐

pi `core/slash-commands.ts:18-43` 共 **24 条**（顺序重组，`/bug` 保留）；java `CommandRegistry.withBuiltins()`（`core/slash/CommandRegistry.java:95-101`）注册 **24 条**。
java 独有 **2 条**（`/help`、`/create-skill`，`SkillsCommands.java:30`），不计入 pi 侧单元。
pi 另有 **`/skill:name` 命令族**（`enableSkillCommands` 默认 true，`interactive-mode.ts:794-799`）—— 单列。

| # | 命令 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `/settings` | `:20` | ⚠️ `SettingsCommands.java:22` | 2 | **存疑** | java 只返回 `UI_SETTINGS` marker（`CommandRegistry.java:33`）；pi `settings-selector.ts` |
| 2 | `/model` | `:21` | ⚠️ `ModelCommands.java:21` | 2 | **存疑** | pi 支持 `/model <provider/model>` 直设；java `argumentHint()` 空且忽略 args |
| 3 | `/tree` | `:22` | ⚠️ `SessionCommands.java:56` | 1 | **存疑** | pi `tree-selector.ts` 1427 行 |
| 4 | `/thinking` | `:23` | ❌ — | 2 | **缺失** | java 无此命令（CLI/RPC 有，slash 面无） |
| 5 | `/scoped-models` | `:24` | ⚠️ `ModelCommands.java:29` | 1 | **存疑** | pi `scoped-models-selector.ts` |
| 6 | `/export` | `:25` | ⚠️ `MiscCommands.java:38` | 2 | **存疑** | pi 默认 HTML、路径可选；java `isBlank()` 时直接返回用法 |
| 7 | `/import` | `:26` | ✅ `MiscCommands.java:61` | 1 | 对齐 | |
| 8 | `/share` | `:27` | ⚠️ `MiscCommands.java:76` | 1 | **存疑** | pi 主路径 **Radius 网关**（`session-share.ts:69`），gist fallback；java 只有 gist |
| 9 | `/bug` | `:28` | ❌ | 1 | **缺失** | pi `core/bug-report.ts` 375 + `bug-report-upload.ts` 37 + `modes/interactive/bug-report.ts`；java 无对应物 |
| 10 | `/copy` | `:29` | ✅ `MiscCommands.java:96` | 2 | 对齐 | |
| 11 | `/name` | `:30` | ✅ `SessionCommands.java:22` | 1 | 对齐 | |
| 12 | `/session` | `:31` | ⚠️ `SessionCommands.java:30` | 1 | **存疑** | pi "info **and stats**"；java 只打 4 行 |
| 13 | `/changelog` | `:32` | ⚠️ `MiscCommands.java:120` | 1 | **存疑** | pi 读文件；java 硬编码字符串 |
| 14 | `/hotkeys` | `:33` | ✅ `MiscCommands.java:122` | 1 | 对齐 | |
| 15 | `/fork` | `:34` | ⚠️ `SessionCommands.java:33` | 2 | **存疑** | pi 从历史用户消息分叉 |
| 16 | `/clone` | `:35` | ✅ `SessionCommands.java:48` | 1 | 对齐 | |
| 17 | `/trust` | `:36` | ✅ `SettingsCommands.java:30` | 2 | 对齐 | |
| 18 | `/login` | `:37` | ⚠️ `MiscCommands.java:134` | 2 | **存疑**（差距扩大） | pi `login-dialog.ts` + OAuth 选择器 **＋ 顶层直供 Radius**（`interactive-mode.ts:5820-5886`）**＋ 登录后提供把 Radius 写进全局 `mcp.json`**（`offerRadiusMcpServer:6305`）；java 只用 `System.console().readPassword` 收 API key |
| 19 | `/logout` | `:38` | ✅ `MiscCommands.java:158` | 1 | 对齐 | |
| 20 | `/new` | `:39` | ✅ `SessionCommands.java:63` | 2 | 对齐 | |
| 21 | `/compact` | `:40` | ✅ `MiscCommands.java:124` | 2 | 对齐 | ⚠️ 取消语义差距见台账 A4 |
| 22 | `/resume` | `:41` | ✅ `SessionCommands.java:70` | 2 | 对齐 | |
| 23 | `/reload` | `:42` | ⚠️ `SettingsCommands.java:53` | 1 | **存疑** | pi `agent-session.ts:3612` `reload()` 重载 extensions + skills + prompts + themes + context files，**并激活新加入 `defaultTools` 的工具**；java 只 `settings().reload()` |
| 24 | `/quit` | `:43` | ✅ `MiscCommands.java:167` | 2 | 对齐 | |
| 25 | `/skill:<name>` 命令族 | `interactive-mode.ts:794-799` | ❌ | 2 | **缺失** | java `Settings.java:46` 有字段但**不产生命令** |

**汇总：对齐 11 / 缺失 3 / 存疑 11 / 合计 25 = 44.0%**；**Σ权重 38 / Σ(w×c) 25.0 / 加权 65.8%**

---

## D. 扩展系统对齐（41 事件）

pi 的扩展事件面 = `ExtensionAPI.on()` 的 **41 个重载**（`core/extensions/types.ts:1556-1623`；旧基准 37 个）。`ExtensionEvent` 联合在 `:1364-1396`。
**本轮 +4 事件**：`mcp_servers_change`（`:1578`）、`context_with_system`（`:1585`）、`provider_stream_event`（`:1596`）、`agent_before_settle`（`:1604`）。`on()` 返回类型仍为 **`() => void`**（全部 41 条，可运行时退订，实现 `extensions/loader.ts`）。
pi-java 的**扩展 SPI** = `PiExtension.java:9-31`，只有 **2 个钩子**：`register(ExtensionContext)` 与 `sessionStartResources()`。
pi-java 的**引擎钩子** = `pi-java-agent-core/.../agent/hook/HookSystem.java`，**13 个**（`onBeforeRun:43`…`onPrepareNextTurn:103`），**但 `ExtensionContext` 不暴露 `hookSystem()`** ⇒ 扩展拿不到。

判定口径：按「**扩展能否订阅该事件**」判；引擎侧有同形钩子的记 **存疑** 并在证据列写明「引擎钩子 X（未桥接）」。

| # | 事件 | pi 行 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `project_trust` | `:1556` | ❌ | 2 | **缺失** | java 有 `TrustManager.java` 标记文件，但无注册面 |
| 2 | `resources_discover` | `:1559` | ✅ `PiExtension.java:29` | 2 | **对齐** | `ExtensionManager.java:86-99` 合并保序 + 异常隔离 |
| 3 | `session_start` | `:1561` | ❌ | 2 | **缺失** | 无钩子 |
| 4 | `session_info_changed` | `:1562` | ❌ | 1 | **缺失** | 宿主 `AgentSessionEvent.SessionInfoChanged`（`:73`）**仍零生产者**（`JsonEventMapper.java:103` 只是消费）—— 台账 **B30** |
| 5 | `session_before_switch` | `:1563` | ❌ | 2 | **缺失** | 可取消的切换钩子不存在 |
| 6 | `session_before_fork` | `:1567` | ❌ | 2 | **缺失** | 同上 |
| 7 | `session_before_compact` | `:1571` | ⚠️ | 2 | **存疑** | 引擎钩子 `onBeforeCompaction:83`**未桥接**；pi 事件带 `signal: AbortSignal` |
| 8 | `session_compact` | `:1575` | ❌ | 2 | **缺失** | 宿主有 `CompactionEnd`（`:87`），非扩展面 |
| 9 | `session_compact_failed` | `:1576` | ❌ | 1 | **缺失** | 台账 **F3**；pi `aborted` 判定已改**信号权威**（`agent-session.ts:2840`） |
| 10 | `session_shutdown` | `:1577` | ❌ | 2 | **缺失** | `ExtensionManager.java:70` `unload()` 只从表移除 |
| 11 | `mcp_servers_change`（**新增**） | `:1578` | ❌ | 2 | **缺失** | MCP 服务器注册/变更事件；java 无 MCP |
| 12 | `session_before_tree` | `:1579` | ⚠️ | 1 | **存疑** | 引擎钩子 `onBeforeNavigation:88`**未桥接** |
| 13 | `session_tree` | `:1583` | ❌ | 1 | **缺失** | 无导航完成事件 |
| 14 | `context` | `:1584` | ⚠️ | **3** | **存疑** | 引擎钩子 `onTransformContext:53`**未桥接** |
| 15 | `context_with_system`（**新增**） | `:1585` | ❌ | **3** | **缺失** | 可改 context 与 system prompt 的新事件；java 无对应面 |
| 16 | `cache_warming_decision` | `:1588` | ❌ | 2 | **缺失** | `core/cache-warmer.ts` 453 行；java 无缓存预热子系统 |
| 17 | `before_provider_request` | `:1591` | ⚠️ | **3** | **存疑** | 引擎钩子 `onBeforePayload:63`**未桥接** |
| 18 | `before_provider_headers` | `:1594` | ⚠️ | 2 | **存疑** | 引擎钩子 `onBeforeRequest:58`**只读、不能改 header**，未桥接 |
| 19 | `after_provider_response` | `:1595` | ⚠️ | 2 | **存疑** | 引擎钩子 `onAfterResponse:68`**未桥接** |
| 20 | `provider_stream_event`（**新增**） | `:1596` | ❌ | 2 | **缺失** | 流式事件级扩展观察面；java 无 |
| 21 | `before_agent_start` | `:1597` | ❌ | **3** | **缺失** | pi 可**改 systemPrompt**（`:1450` `BeforeAgentStartEventResult.systemPrompt`）；java `onBeforeRun:43` **不能改提示词** |
| 22 | `agent_start` | `:1601` | ❌ | 2 | **缺失** | 无钩子（宿主 `PiLoop` 有 start 帧，非扩展面） |
| 23 | `agent_end` | `:1602` | ❌ | 2 | **缺失** | 宿主 `AgentEnd`（`:28`），非扩展面 |
| 24 | `agent_before_settle`（**新增**） | `:1604` | ❌ | 2 | **缺失** | 可边界结果；java 无 |
| 25 | `agent_settled` | `:1607` | ❌ | 2 | **缺失** | 宿主 `AgentSettled`（`:46`），非扩展面 |
| 26 | `ui_prompt_start` | `:1608` | ❌ | 1 | **缺失** | 无对应 |
| 27 | `ui_prompt_end` | `:1609` | ❌ | 1 | **缺失** | 无对应 |
| 28 | `turn_start` | `:1610` | ❌ | 2 | **缺失** | 无对应 |
| 29 | `turn_end` | `:1611` | ❌ | 2 | **缺失** | 台账 **B49** |
| 30 | `message_start` | `:1612` | ❌ | 2 | **缺失** | 无对应 |
| 31 | `message_update` | `:1613` | ❌ | **3** | **缺失** | 宿主有 `MessageUpdate`（`:22`），非扩展面 |
| 32 | `message_end` | `:1614` | ❌ | 2 | **缺失** | 非扩展面 |
| 33 | `tool_execution_start` | `:1615` | ❌ | 2 | **缺失** | 宿主有 `ToolExecutionStart`（`:129`），非扩展面 |
| 34 | `tool_execution_update` | `:1616` | ❌ | 2 | **缺失** | 宿主有（`:133`）但**生产无数据源** —— 台账 **B46** |
| 35 | `tool_execution_end` | `:1617` | ❌ | 2 | **缺失** | 宿主有（`:138`），非扩展面；台账 **B43** |
| 36 | `model_select` | `:1618` | ❌ | 2 | **缺失** | 无对应 |
| 37 | `thinking_level_select` | `:1619` | ❌ | 2 | **缺失** | 宿主 `ThinkingLevelChanged`（`:83`）**现已生产**（`AgentSession.java:484`），但**非扩展面** —— 台账 **B35**（宿主侧生产者已补，扩展事件仍缺） |
| 38 | `tool_call` | `:1620` | ⚠️ | **3** | **存疑** | 引擎钩子 `onBeforeTool:73`**未桥接** |
| 39 | `tool_result` | `:1621` | ⚠️ | **3** | **存疑** | 引擎钩子 `onAfterTool:78`**未桥接** |
| 40 | `user_bash` | `:1622` | ❌ | 1 | **缺失** | java `!`/`!!` 无扩展拦截面 |
| 41 | `input` | `:1623` | ❌ | **3** | **缺失** | java 无输入处理链 |

**汇总（宽口径）：对齐 1 / 缺失 32 / 存疑 8 / 合计 41 = 2.4%**；**Σ权重 83 / Σ(w×c) 11.5 / 加权 13.9%**
**汇总（严格口径，未桥接的引擎钩子也算缺失）：对齐 1 / 缺失 40 / 存疑 0 = 2.4%**；**加权 2/83 = 2.4%**
> ⚠️ D 域 Σ权重 **83** = 模块 499 的 **16.6%**，仍是模块最大域。

### D′. 扩展 UI 注入点（单列）

pi `ExtensionUIContext` 共 **28 个成员**（`types.ts:149-323`）：`select`/`confirm`/`input`/`notify`/`onTerminalInput`/`setStatus`/`setWorkingMessage`/`setWorkingVisible`/`setWorkingIndicator`/`setHiddenThinkingLabel`/`setWidget`/`setFooter`/`setHeader`/`setTitle`/`custom`/`pasteToEditor`/`setEditorText`/`getEditorText`/`editor`/`addAutocompleteProvider`/`setEditorComponent`/`getEditorComponent`/`theme`(readonly)/`getAllThemes`/`getTheme`/`setTheme`/`getToolsExpanded`/`setToolsExpanded`（⚠️ `loadTheme` 已改名 `getTheme`，新增 `theme` 只读属性）。

| 注入面 | pi | java | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|
| 阻塞式对话（select/confirm/input/editor） | ✅ 4 | ⚠️ 1 `request(RpcExtensionUIRequest)` | 2 | **存疑** | `ExtensionUI.java:13`；RPC 9 method 有，但 `ui()` 无生产者 |
| 通知 / 状态栏 / 标题 | ✅ | ❌ | 2 | **缺失** | 无对应 |
| widget（aboveEditor/belowEditor） | ✅ `setWidget` | ❌ | 2 | **缺失** | 无对应 |
| 自定义 footer / header | ✅ | ❌ | 2 | **缺失** | 无对应 |
| 自定义组件 + overlay + 键盘焦点 | ✅ `custom()` | ❌ | 2 | **缺失** | 无对应 |
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
| E1-1 | `settings.json`（全局） | 配置 | `config.ts:529-533` → `~/.pi/agent/settings.json` | `FileSettingsStorage.java:41/52` | 2 | 对齐 | — |
| E1-2 | `settings.json`（项目级） | 配置 | `.pi/settings.json`（`config.ts:504`） | `FileSettingsStorage.java:42` | 2 | 对齐 | — |
| E1-3 | `auth.json` | 配置 | `auth-storage.ts:497-498`（revision + 文件锁 + stale 检测） | `ai/auth/FileCredentialStore.java`、`OAuthCredentialStore.java`（＋ AuthKind 分派） | 2 | **存疑** | — |
| E1-4 | `models.json` | 配置 | `core/models-store.ts` + `model-config.ts:303` + **`core/remote-catalog-provider.ts`** | `ai/provider/ModelsJsonProvider.java` + **`core/RemoteCatalogProvider.java`（326 行）** + `ModelCatalogRefresh.java` | 2 | **存疑** | **F6**（`ModelsJsonProvider` 钉死 baseUrl） |
| E1-5 | pi manifest（`package.json` 的 `pi` 字段） | 配置 | `core/pi-manifest.ts`（35 行） | ❌ 无对应物 | 2 | **缺失** | — |

**E1 汇总：对齐 2 / 缺失 1 / 存疑 2 / 合计 5 = 40.0%**；**Σ权重 10 / Σ(w×c) 6.0 / 加权 60.0%**

### E2 settings.json 键（52 → **55**）

pi 键全部来自 `core/settings-manager.ts:133-183`（`interface Settings`）；java 来自 `core/Settings.java:24-64`。
**判定为「对齐」需字段存在且有非零消费者**（getter/setter 不算消费者）。

| # | 键 | pi 行 | java 行 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| E2-1 | `lastChangelogVersion` | `:134` | — | 1 | **缺失** | 无字段 |
| E2-2 | `defaultProvider` | `:135` | `:24` | **3** | 对齐 | `SettingsAccessors.java:20` |
| E2-3 | `defaultModel` | `:136` | `:25` | **3** | 对齐 | `SettingsAccessors.java:30` |
| E2-4 | `defaultThinkingLevel` | `:137` | `:26` | 2 | 对齐 | `SettingsAccessors.java:40` |
| E2-5 | `modelThinkingLevels` | `:138` | — | 2 | **缺失** | 无字段 |
| E2-6 | `transport` | `:139` | `:31` | 2 | 对齐 | 4 处消费 |
| E2-7 | `steeringMode` | `:140` | `:32` | 2 | 对齐 | `SettingsAccessors.java:60` |
| E2-8 | `followUpMode` | `:141` | `:33` | 2 | 对齐 | `SettingsAccessors.java:70` |
| E2-9 | `theme` | `:142` | `:34` | 2 | **存疑** | java 值是 `.tcss` 路径；pi 是 JSON 主题名 |
| E2-10 | `compaction` | `:143` | `:35` | **3** | **存疑** | java `Compaction` 只有 `enabled/reserveTokens/keepRecentTokens`（`Settings.java:126`）；pi 另有 **`modelOverrides`**（`:32`）—— 台账 **A9** |
| E2-11 | `branchSummary` | `:144` | — | 2 | **缺失** | 无字段 —— 台账 **B1** |
| E2-12 | `retry` | `:145` | `:64` | 2 | 对齐（**本轮改判**） | java 现已接线：`SettingsAccessors.java:197` `getRetrySettings()` → `AgentSession.java:312/397`；`getProviderRetrySettings():218` → api options（包 A-14 已闭环） |
| E2-13 | `hideThinkingBlock` | `:146` | `:36` | 2 | 对齐 | 2 处消费 |
| E2-14 | `showCacheMissNotices` | `:147` | — | 1 | **缺失** | 无字段 |
| E2-15 | `externalEditor` | `:148` | `:37` | 1 | **存疑** | accessor 有（`:142`），TUI 行为未核 |
| E2-16 | `shellPath` | `:149` | `:38` | 2 | 对齐 | 5 处消费 |
| E2-17 | `quietStartup` | `:150` | `:40` | 1 | 对齐 | `SettingsAccessors.java:80` |
| E2-18 | `defaultProjectTrust` | `:151` | `:41` | 2 | 对齐 | `SettingsAccessors.java:90` |
| E2-19 | `shellCommandPrefix` | `:152` | `:39` | 2 | 对齐 | 4 处消费 |
| E2-20 | `npmCommand` | `:153` | — | 2 | **缺失** | 无字段 |
| E2-21 | `collapseChangelog` | `:154` | — | 1 | **缺失** | 无字段 |
| E2-22 | `enableInstallTelemetry` | `:155` | — | 1 | **缺失** | 无字段 |
| E2-23 | `enableAnalytics` | `:156` | — | 1 | **缺失** | 无字段 |
| E2-24 | `trackingId` | `:157` | — | 1 | **缺失** | 无字段 |
| E2-25 | `deviceId`（**新增**） | `:158` | — | 1 | **缺失** | 无字段 |
| E2-26 | `packages` | `:159` | — | 2 | **缺失** | 无字段 |
| E2-27 | `extensions` | `:160` | `:42` | 2 | 对齐 | 2 处消费 |
| E2-28 | `skills` | `:161` | `:43` | 2 | 对齐 | 13 处消费 |
| E2-29 | `prompts` | `:162` | `:44` | 2 | 对齐 | 5 处消费 |
| E2-30 | `themes` | `:163` | `:45` | 2 | 对齐 | 4 处消费 |
| E2-31 | `enableSkillCommands` | `:164` | `:46` | 2 | **存疑** | 字段有、1 处消费，但 `/skill:name` 命令族**未实现** |
| E2-32 | `terminal` | `:165` | `:47` | 1 | **存疑** | java 只有 2 键（`Settings.java:133`），pi 有 6 键（`:57-64`） |
| E2-33 | `images` | `:166` | `:48` | 1 | **存疑** | java 2 键与 pi 同，但 `autoResize`/`blockImages` 零消费 |
| E2-34 | `enabledModels` | `:167` | `:49` | 2 | 对齐 | `SettingsAccessors.java:101` |
| E2-35 | `defaultTools`（支持 `+name`/`-name`） | `:168` | — | 2 | **缺失** | 无字段；pi `mergeDefaultTools`/`resolveDefaultTools`（`:210-235`） |
| E2-36 | `doubleEscapeAction` | `:169` | `:50` | 1 | **存疑** | accessor 有（`:172`），行为未核 |
| E2-37 | `treeFilterMode` | `:170` | `:51` | 1 | **存疑** | 同上（`:162`） |
| E2-38 | `thinkingBudgets` | `:171` | — | 1 | **缺失** | 无字段 |
| E2-39 | `editorPaddingX` | `:172` | `:52` | 1 | **存疑** | 字段有，**零消费** |
| E2-40 | `outputPad` | `:173` | `:53` | 1 | **存疑** | 字段有，**零消费** |
| E2-41 | `autocompleteMaxVisible` | `:174` | `:54` | 1 | **存疑** | 字段有，**零消费** |
| E2-42 | `showHardwareCursor` | `:175` | — | 1 | **缺失** | 无字段 |
| E2-43 | `markdown` | `:176` | `:55` | 1 | **存疑** | java 2 键但 `codeBlockIndent`/`mermaid` 零消费 |
| E2-44 | `warnings` | `:177` | — | 1 | **缺失** | 无字段 |
| E2-45 | `codemode`（**新增**，`CodemodeSettings`） | `:178` | — | 2 | **缺失** | `mode: on\|only` + `inlineBudget`（`:95-108`）；java 无 codemode |
| E2-46 | `sessionDir` | `:179` | `:56` | 2 | 对齐 | 7 处消费 |
| E2-47 | `httpProxy` | `:180` | `:58` | 1 | **存疑** | 字段有，**零消费** |
| E2-48 | `httpIdleTimeoutMs` | `:181` | — | 1 | **缺失** | 无字段 |
| E2-49 | `cacheWarming` | `:182` | — | 2 | **缺失** | `CacheWarmingMode = off\|streaming\|idle`，默认 streaming，仅全局 |
| E2-50 | `websocketConnectTimeoutMs` | `:183` | — | 1 | **缺失** | 无字段 |
| E2-51 | `tuiMode` | `:184` | `:61` | 2 | 对齐 | `SettingsAccessors.java:112`；默认均 fullscreen |
| E2-52 | `fullscreenExitOutput` | `:185` | — | 1 | **缺失** | 无字段 |
| E2-53 | `fullscreenScrollbar` | `:186` | — | 1 | **缺失** | 无字段 |
| E2-54 | `fullscreenCopyOnSelect` | `:187` | — | 1 | **缺失** | 无字段 |
| E2-55 | `fullscreenWheelScrollLines`（**新增**） | `:188` | — | 1 | **缺失** | 无字段 |

**E2 汇总：对齐 19 / 缺失 23 / 存疑 13 / 合计 55 = 34.5%**；**Σ权重 86 / Σ(w×c) 47.5 / 加权 55.2%**
> 本轮变化：`retry` 存疑 → **对齐**（A-14 闭环）；新增 `deviceId` / `codemode` / `fullscreenWheelScrollLines` 三键。

### E3 会话管理行为（24）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E3-1 | 新建会话（无参） | `session-manager.ts:1057` `newSession` | `SessionPersistence.java:201` `handle.create` | **3** | 对齐 | — |
| E3-2 | 续最近会话（`-c`） | `main.ts:433` → `findMostRecentSession`（`:749`） | `SessionPersistence.java:170` `handle.latest(cwd)` | 2 | 对齐 | A12 已结案 |
| E3-3 | `-r` 交互式会话选择器 | `main.ts:417` `selectSession`（`cli/session-picker.ts`） | ❌ `SessionPersistence.java:187` → `find(null)` 抛异常 | 2 | **缺失** | — |
| E3-4 | `--session` 精确/前缀查找 | **三级阶梯**（`main.ts:252-281`）：① `findLocalSessionByExactId`（`:243`）→ `SessionManager.findById`（`:248`，**header-only**）→ ② 项目内前缀（`:264`）→ ③ `SessionManager.listAll`（`:273`，跨项目） | `PersistentSessionRepositories.java:63` 走 `all()`（**直接跳到第 ③ 级**） | 2 | **存疑** | **B53** |
| E3-5 | `--session-id` 精确 ID（缺则建） | `main.ts:436-445` | `SessionPersistence.java:175-180` | 2 | **存疑** | **B53** |
| E3-6 | `--fork` 分叉 | `main.ts:369` | `SessionPersistence.java:192-199` | 2 | **存疑** | **B53** |
| E3-7 | `/clone` 原位复制 | `session-manager.ts:1632` `createBranchedSession` | `SessionCommands.java:48` `forkCopy` | 1 | 对齐 | — |
| E3-8 | `/fork` 从历史用户消息分叉 | `user-message-selector.ts` | `SessionCommands.java:33` `forkCopy(name)` | 2 | **存疑** | — |
| E3-9 | 会话列表（加强） | `listSessionsFromDir`（`:941`）、`SessionListProgress`（3 参）、`AbortSignal`、`publishPartial`、`listAll` | `PersistentSessionRepositories.java:74-75` `list(cwd)` **同步全量、无回调、无取消** | 2 | **存疑** | **B55** |
| E3-10 | 导出 JSONL | `session-export.ts` | `PersistentSessionRepositories.java:95` | 2 | 对齐 | — |
| E3-11 | 导出 HTML | `core/export-html/` | `export/HtmlExporter.java` | 2 | 对齐 | — |
| E3-12 | 导入 JSONL | `session-manager.ts` | `PersistentSessionRepositories.java:109` | 1 | 对齐 | — |
| E3-13 | 会话命名 | `session-manager.ts:1304` `appendSessionInfo` | `SessionCommands.java:22` + `AgentSession.java:503` | 1 | 对齐 | **B32** |
| E3-14 | 会话树导航 | `session-manager.ts:1529` `getTree` / `:1579` `branch` | `SessionCommands.java:56` + `RpcDispatcher.java:563` | 1 | 对齐 | — |
| E3-15 | 分支摘要（branch summary） | `core/compaction/branch-summarization.ts:349-353` | ❌ | 2 | **缺失** | **B1** |
| E3-16 | 条目标签（labels） | `session-manager.ts:1237/1246` | `JsonlSessionStorage.java:255/260` | 1 | 对齐 | — |
| E3-17 | 自定义条目（custom entry） | `session-manager.ts:1290` | `Session.java:183` | 2 | 对齐 | — |
| E3-18 | bash 执行条目 | `session-manager.ts:1071` | `AgentSession.java:919` + `AgentSessionEvent.BashExecutionUpdate:111` | 1 | 对齐 | — |
| E3-19 | `--no-session` 语义 | `main.ts`（ephemeral） | 非 web 生效；**web 路径忽略** | 1 | **存疑** | **B54** |
| E3-20 | `--session-dir` | `session-manager.ts:596` `getDefaultSessionDir` | `AgentSession.java:126/159/175` | 1 | 对齐 | — |
| E3-21 | 按 cwd 分目录 | `session-manager.ts:589/596` | `AgentSession.java:126`（`--path--` 编码） | 2 | 对齐 | — |
| E3-22 | 崩溃恢复（settle 未结操作） | `session-manager.ts:341` `migrateSessionEntries` | `SessionPersistence.java:83` `settleOpenOperation` | 2 | 对齐 | — |
| E3-23 | 会话元数据变更事件 | `session_info_changed`（`types.ts:1562`） | `AgentSessionEvent.SessionInfoChanged` **零生产者** | 1 | **缺失** | **B30** |
| E3-24 | 提示缓存预热 | `core/cache-warmer.ts`（453 行）+ `settings-manager.ts:182` | ❌ | 2 | **缺失** | — |

**E3 汇总：对齐 14 / 缺失 4 / 存疑 6 / 合计 24 = 58.3%**；**Σ权重 40 / Σ(w×c) 27.5 / 加权 68.8%**

### E4 资源发现（7）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E4-1 | skill 发现（目录 + `SKILL.md` + 忽略文件） | `core/skills.ts:168/409`、`resource-loader.ts` | `skill/SkillDiscovery.java:36/51` + `IgnoreFilter.java` | 2 | 对齐 | — |
| E4-2 | skill 注册为 `/skill:<name>` 命令 | `interactive-mode.ts:794-799` | ❌ | 2 | **缺失** | — |
| E4-3 | prompt template 发现 + 参数替换 | `core/prompt-templates.ts:222` | `prompt/PromptTemplates.java:47/186` | 2 | 对齐 | — |
| E4-4 | theme 发现（JSON + schema + 目录扫描） | `theme.ts` + `theme-json.ts` + `theme-schema.json` + `theme-controller.ts`（≈2,215 行 `.ts`） | ❌ 只有 2 个内建 `.tcss`（`PiTheme.java:23-24`） | 2 | **缺失** | — |
| E4-5 | 上下文文件发现（`AGENTS.md`/`CLAUDE.md`） | `resource-loader.ts:185`（5 候选名）+ `loadProjectContextFiles:232` | ❌ **整块不存在**（`--no-context-files` 零消费者） | **3** | **缺失** | — |
| E4-6 | 系统提示词文件发现 | `resource-loader.ts:1201` `discoverSystemPromptFile` | ❌ | 2 | **缺失** | — |
| E4-7 | 资源冲突诊断 | `diagnostics.ts`（`ResourceDiagnostic`/`ResourceCollision`） | `skill/ResourceDiagnostic.java`、`PromptTemplates.Diagnostic` | 1 | 对齐 | — |

**E4 汇总：对齐 3 / 缺失 4 / 存疑 0 / 合计 7 = 42.9%**；**Σ权重 14 / Σ(w×c) 5.0 / 加权 35.7%**

### E5 扩展 API 面（28 → **35**）

pi 面 = `ExtensionAPI`（`core/extensions/types.ts:1551-1877`）；java 面 = `PiExtension` + `ExtensionContext`（`extension/*.java`）。
**本轮 +7 成员**：`registerToolRenderer`、`getSettings`、`registerMcpServer`、`unregisterMcpServer`、`getMcpServers`、`registerVirtualModel`、`unregisterVirtualModel`。

| # | 能力单元 | pi 行 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E5-1 | `registerTool` | `:1630` | `ExtensionContext.java:20` `tools()` | **3** | 对齐 | — |
| E5-2 | `registerCommand` | `:1639` | `ExtensionContext.java:23` `slashCommands()` | 2 | 对齐 | — |
| E5-3 | `registerProvider` | `:1817-1818` | `ExtensionContext.java:26` `providers()` | 2 | 对齐 | — |
| E5-4 | 注册技能 | （无独立 API，靠目录） | `ExtensionContext.java:29` `skills()` | 2 | 对齐 | — |
| E5-5 | `unregisterProvider` | `:1833` | ❌ | 1 | **缺失** | — |
| E5-6 | `registerShortcut` | `:1642` | ❌ | 2 | **缺失** | — |
| E5-7 | `registerFlag` / `getFlag` | `:1651` / `:1667` | ❌ | 1 | **缺失** | — |
| E5-8 | `registerMessageRenderer` | `:1674` | ❌ | 2 | **缺失** | — |
| E5-9 | `registerMarkdownTransformer` | `:1677` | ❌ | 2 | **缺失** | — |
| E5-10 | `registerEntryRenderer` | `:1680` | ❌ | 2 | **缺失** | — |
| E5-11 | `registerToolRenderer`（**新增**） | `:1683` | ❌ | 1 | **缺失** | — |
| E5-12 | `sendMessage`（含 `triggerTurn`/`deliverAs`） | `:1690` | `ExtensionContext.java:48-58` 仅**落盘**；`triggerTurn=true` 抛 UOE | 2 | **存疑** | — |
| E5-13 | `sendUserMessage` | `:1700` | ❌ | 2 | **缺失** | — |
| E5-14 | `appendEntry` | `:1706` | ❌ | 1 | **缺失** | — |
| E5-15 | `setSessionName` / `getSessionName` | `:1713` / `:1716` | ❌ | 1 | **缺失** | — |
| E5-16 | `setLabel` | `:1719` | ❌ | 1 | **缺失** | — |
| E5-17 | `exec` | `:1722` | ❌ | 2 | **缺失** | — |
| E5-18 | `getActiveTools`/`getAllTools`/`setActiveTools` | `:1725`/`:1728`/`:1737` | ❌ | 2 | **缺失** | — |
| E5-19 | `getSettings`（**新增**） | `:1731` | `ExtensionContext.java:32` `settings()` | 2 | 对齐 | — |
| E5-20 | `getCommands` | `:1740` | ❌ | 1 | **缺失** | — |
| E5-21 | `setModel` | `:1750` | ❌ | 2 | **缺失** | — |
| E5-22 | `getThinkingLevel`/`setThinkingLevel` | `:1753`/`:1759` | ❌ | 2 | **缺失** | — |
| E5-23 | `registerMcpServer`（**新增**） | `:1853` | ❌ | 2 | **缺失** | — |
| E5-24 | `unregisterMcpServer`（**新增**） | `:1856` | ❌ | 1 | **缺失** | — |
| E5-25 | `getMcpServers`（**新增**） | `:1859` | ❌ | 1 | **缺失** | — |
| E5-26 | `registerVirtualModel`（**新增**） | `:1870` | ❌ | 2 | **缺失** | — |
| E5-27 | `unregisterVirtualModel`（**新增**） | `:1872` | ❌ | 1 | **缺失** | — |
| E5-28 | `events`（扩展间 EventBus） | `:1876` | ❌ | 1 | **缺失** | — |
| E5-29 | 热重载（`/reload` 重载 extensions/skills/prompts/themes + 激活新 defaultTools） | `agent-session.ts:3612` | ⚠️ `SettingsCommands.java:53` 只重载 settings | 2 | **存疑** | — |
| E5-30 | 扩展注册 CLI flag | `:1651` + `args.ts:247` | ❌ | 1 | **缺失** | — |
| E5-31 | 扩展 UI 注入（TUI） | `types.ts:149-323` | ❌ 只回落 `ExtensionUI.noop()` | 2 | **缺失** | — |
| E5-32 | 扩展 UI 通道（RPC，9 method） | `rpc-types.ts:253-297` | ⚠️ `RpcExtensionUIRequest.java` 形有，`ui()` **无生产者** | 1 | **存疑** | — |
| E5-33 | 扩展示例库（可执行验收规格） | `examples/extensions/`（105 个 `.ts`，16,033 行） | ❌ 只有 5 个测试夹具 | **3** | **缺失** | — |
| E5-34 | 扩展来源追踪（`SourceInfo`） | `types.ts:1530/2042/2085` | ⚠️ `InstalledExtension.java` 有路径，无 `sourceInfo` 投影 | 1 | **存疑** | — |
| E5-35 | `on()` 返回退订函数 | `types.ts:1556-1623`（41 条 `() => void`） | ❌ `PiExtension` 是装配期一次性注册 | 2 | **缺失** | — |

**E5 汇总：对齐 5 / 缺失 26 / 存疑 4 / 合计 35 = 14.3%**；**Σ权重 58 / Σ(w×c) 14.0 / 加权 24.1%**
> 本轮改判：`getSettings`（E5-19）判**对齐**（java `ExtensionContext.settings()` 已存在，即旧图记为「java 独有」的那个）。

### E6 包管理器（16）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E6-1 | `install` 本地源 | `package-manager.ts:112` | `PackageCommand.java:44` | 2 | **存疑** | — |
| E6-2 | `install` http(s) URL | `package-manager.ts` | `ExtensionPackageManager.java:17` | 2 | **存疑** | — |
| E6-3 | npm 源（版本/range/pinning） | `package-manager.ts:139-147` | ❌ | **3** | **缺失** | — |
| E6-4 | git 源 | `package-manager.ts` | ❌ | 2 | **缺失** | — |
| E6-5 | 作用域 user/project/temporary | `package-manager.ts:135` | ⚠️ 只有 global/project | 2 | **存疑** | — |
| E6-6 | 资源过滤 | `package-manager.ts:187-194` | ❌ | 2 | **缺失** | — |
| E6-7 | 资源优先级（5 档） | `package-manager.ts:171-184` | ❌ | 2 | **缺失** | — |
| E6-8 | 忽略文件 | `package-manager.ts:206` | ❌ | 1 | **缺失** | — |
| E6-9 | 进度回调 `onProgress` | `package-manager.ts:96` | ❌ | 1 | **缺失** | — |
| E6-10 | `update self` / `update pi` | `package-manager-cli.ts:336-357` + `utils/windows-self-update.ts` | ❌ 只打印说明（`PackageCommand.java:20`） | 2 | **缺失** | — |
| E6-11 | `update --extensions` | `package-manager-cli.ts:436` | ❌ | 2 | **缺失** | — |
| E6-12 | `update --models`（模型目录刷新） | `package-manager-cli.ts:436` + `core/remote-catalog-provider.ts` | ❌ 子命令无；⚠️ **机制已在核心自动跑**（`ModelCatalogRefresh.java` + `RemoteCatalogProvider.java`，RPC 启动后台刷新） | 1 | **缺失** | — |
| E6-13 | `config` TUI（Tab 切作用域、逐资源开关） | `package-manager-cli.ts:796` + `config-selector.ts` | ⚠️ 非交互 `enable/disable` | 2 | **存疑** | — |
| E6-14 | 持久化到 `settings.packages` | `package-manager.ts:112` | ❌ 无 `packages` 键 | **3** | **缺失** | — |
| E6-15 | pi manifest 读取 | `core/pi-manifest.ts` | ❌ | 2 | **缺失** | — |
| E6-16 | `remove` / `uninstall` | `package-manager.ts:118` | ⚠️ `PackageCommand.java:55` | 2 | **存疑** | — |

**E6 汇总：对齐 0 / 缺失 11 / 存疑 5 / 合计 16 = 0%**；**Σ权重 31 / Σ(w×c) 5.0 / 加权 16.1%**

### E7 RPC 面（5 → **6**）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| E7-1 | RPC 命令集（pi 33 / java 32） | `rpc-types.ts:22-74` 33 个 `type` | `RpcCommand.java:23-54` 32 个 `@JsonSubTypes` | **3** | **存疑** | 缺 `clear_queue`（pi `:26`；java 零命中） |
| E7-2 | `extension_ui_request/response` 双向通道 | `rpc-types.ts:253-297`（9 method） | `RpcDispatcher.java:296` + `:58` `uiQueue` | 2 | **存疑** | 形有；`ui()` 无生产者 |
| E7-3 | `get_state` 状态字段 | `rpc-types.ts:96-109` | `RpcDispatcher.java:344` `buildState` / `RpcSessionState.java` | 2 | **存疑** | java 已补 `isCompacting`/`sessionFile`/`sessionId`/`pendingMessageCount`；**B31**（model 是字符串非对象）/ **B32**（sessionName 恒有默认值）/ **B33**（messageCount 取 entryCount） |
| E7-4 | RPC 事件线格式 | `modes/json-event.ts` | `mode/JsonEventMapper.java` | **3** | **存疑** | **B27/B28/B29** |
| E7-5 | RPC 命令线的 `Instant` 序列化 | pi 无此问题 | `rpc/JsonlWriter.java` | 1 | 对齐 | **B52 已修** |
| E7-6 | RPC `prompt` 回执 `disposition`（**新增**） | `rpc-types.ts:118` `PromptDisposition` | ❌（java 全仓零命中） | 1 | **缺失** | — |

**E7 汇总：对齐 1 / 缺失 1 / 存疑 4 / 合计 6 = 16.7%**；**Σ权重 12 / Σ(w×c) 6.0 / 加权 50.0%**

---

## G. 主流新增子系统（MCP / codemode / tool-search / virtual-models / nested-tool-calls）

这些是旧基准**不存在**的 pi 侧子系统（新包＋新扩展），**均在 `bin pi` 主流路径上**（`src/extensions/index.ts` 把它们注册为 builtin 扩展，每会话加载）。**MCP 面（G-1..G-5）已有对应**（`pi-java-mcp`，包①–⑨）；**codemode / tool-search / virtual-models / nested（G-6..G-10）仍为零对应**。

| # | 能力单元 | pi 侧证据 | 权重 | 判定 | 说明 |
|---|---|---|---|---|---|
| G-1 | MCP 客户端 + 协议 + 连接运行时（JSON-RPC、initialize、tools/resources/prompts、capabilities、连接状态机） | `packages/mcp/src/client.ts`、`protocol/*`（3,167 行）＋ `src/extensions/mcp/{index,runtime,log}.ts` | 2 | **存疑** | **客户端/协议/运行时已落**（包①②⑨，`docs/29`/`36`）：`McpClient` 状态机＋协议 wire records＋`McpServerConnection`（lazy 重连/瞬态重试/session 重放/`mcp.log`）；**`extensions/mcp/index.ts`（1,225 行）未落**（第 ⑫ 包） |
| G-2 | MCP 传输（stdio / streamable-http / in-memory） | `packages/mcp/src/transports/{stdio,streamable-http,in-memory,transport}.ts` | 2 | **对齐** | 三条传输全落（包③④⑤，`docs/30`/`31`/`32`）；`in-memory` 在 test 源，对齐 pi 的 `@earendil-works/pi-mcp/testing` 导出；⚠️ 包⑤ 的 HTTP transport 此前**包外不可构造**（B201，包⑨ 补为 public） |
| G-3 | MCP OAuth（discovery + RFC 9207 + client ID metadata + callback server） | `packages/mcp/src/oauth/*`（7 文件）＋ `src/extensions/mcp/oauth.ts`（532 行） | 1 | **对齐** | 两半全落（包⑥⑦⑨，`docs/33`/`34`/`36`）：`.well-known` 发现＋PR 回退、`WWW-Authenticate`、RFC 9207 `iss`、PKCE、6 分支客户端认证、`mcp-auth.json` 凭据 store（name\|url 键＋legacy 接管＋刷新锁）、登录编排＋回调服务器＋粘贴回退；⚠️ cimd 的文档托管在 `pi.dev`（**B192**）—— 两侧均未实测，见说明 |
| G-4 | `mcp.json` 配置 + `/mcp` 子命令 + 审批 UI | `src/extensions/mcp/config.ts` 242、`cli.ts` 614、`ui.ts` 252；`main.ts:614` | 2 | **存疑** | **配置面与 CLI 已落**：配置（包⑧，`docs/35`，`362e2fb4`）＋ **`pi mcp` 五个子命令**（包⑪，`docs/38`，`677b04c4`，`com.pijava.coding.agent.subcommand.Mcp*` 8 文件＋7 夹具）；**`/mcp` 管理器 UI 未落**（顺延第 ⑫ 包，**B214**） |
| G-5 | MCP 工具暴露（`codemode`/`deferred`/`direct`/`hidden`）+ 工具/资源接入模型 | `src/core/mcp-servers.ts`、`src/extensions/mcp/tools.ts`、`resources.ts` | 2 | **对齐** | 三处全落：exposure ADT 与 per-tool 判定（包⑧，`docs/35`）＋ `AgentTool` 适配与三个资源工具（包⑩，`docs/37`，`4465d58e`）。⚠️ **激活路径未落** —— 今天只有 `direct` 的 MCP 工具是模型可见的，`codemode`/`deferred`/`hidden` 依赖 **B161/B162**（登记 B203） |
| G-6 | codemode 沙箱运行时（quickjs-wasi host/worker/protocol/prelude） | `packages/codemode/src/runtime/*`、`wasm.ts`（1,678 行）＋ `src/extensions/codemode/execute.ts`（630 行） | 2 | **缺失** | builtin 扩展 |
| G-7 | codemode 工具（执行 JS、描述/inlineBudget、`mode: on\|only`、renderer） | `src/extensions/codemode/tool.ts`（404）、`renderer.ts`；`settings-manager.ts:95-108` | 2 | **缺失** | |
| G-8 | `tool_search` 工具（BM25 排序 + 按需加载 deferred 工具） | `src/extensions/tool-search/{index,tool}.ts`（265 行） | 2 | **缺失** | builtin 扩展 |
| G-9 | 虚拟模型（registry + route + 状态条目） | `core/virtual-models.ts`（238 行）＋ `ExtensionAPI.registerVirtualModel:1870` | 2 | **缺失** | 配置后走 |
| G-10 | 嵌套工具调用（`ctx.executeTool()`、`nestedCalls`、大小上限） | `core/nested-tool-calls.ts`（261 行） | 1 | **缺失** | 内部支撑 |

**G 汇总：对齐 3 / 缺失 5 / 存疑 2 / 合计 10 = 30.0%**；**Σ权重 18 / Σ(w×c) 7.0 / 加权 38.9%**（存疑 0.5×w：G-1 1.0 ＋ G-4 1.0；对齐 G-2 2.0 ＋ G-3 1.0 ＋ G-5 2.0）

> **模块归属**：MCP 面的 Java 代码主在**新模块 `pi-java-mcp`**（`com.pijava.mcp.*`，
> 主源 **10,700 行**）；**工具与资源的 `AgentTool` 适配在 `pi-java-coding-agent`**
> 的 `com.pijava.coding.agent.extension.mcp`（包⑩，约 1,400 行）—— `AgentTool` 在
> agent-core，而 `pi-java-mcp` 看不见它（`ai ← mcp`）。映射裁决见 `docs/28` §2
> 与 **B205**。
> 部分落地的行按 **E7-3 同一惯例**记「存疑」（形状/一半存在），**不拆行**（拆行会改权重层归属，
> 动到「权重 3 层 < 权重 1 层」那条读数）。每包闭环时回填对应行的判定与说明。
> ⚠️ 其余「其它」新增面**已折叠进既有单元**，不另计：`defaultTools` 的 `+name/-name` → **E2-35**；`/reload` 激活新加入工具 → **E5-26**；RPC `prompt` disposition → **E7-6**；`/login` 提供 Radius 与 MCP 设置 → **C-18**；fullscreen 默认 → **E2-51**；`codemode` 设置键 → **E2-45**。

### E8 本轮新增子系统（与 E3/C/G 重叠，不计入模块合计）

| # | 能力单元 | pi 侧证据 | 权重 | 判定 |
|---|---|---|---|---|
| E8-1 | 提示缓存预热 | `core/cache-warmer.ts` 453 行（已计入 E3-24） | 2 | **缺失** |
| E8-2 | bug 上报（`/bug`） | `core/bug-report.ts` 375 + `bug-report-upload.ts` 37 + `modes/interactive/bug-report.ts`（已计入 C-9） | 1 | **缺失** |
| E8-3 | 崩溃日志（crash log） | `core/crash-log.ts` **170 行**（旧 92） | 1 | **缺失** |
| E8-4 | 扩展加载器依赖延迟（jiti lazy / Bun·SEA static） | `core/extensions/jiti-loader.ts` 3 + `jiti-static-loader.ts` + `virtual-modules.ts` 38 | 1 | **缺失（不适用）** |
| E8-5 | ~~`experimental/micro`~~ | **整块被 `7fd478a2e` 删除 ⇒ R1 作废（权重 0，移出分母）** | ~~1~~ | **作废** |
| E8-6 | WSL / zip 工具 | `utils/wsl.ts` + `utils/zip.ts` | 1 | **缺失** |
| E8-7 | 系统提示词分节构建与差分 | `core/system-prompt.ts`（216 行） | 1 | **缺失** |

**E8 汇总（不计入模块）：对齐 0 / 缺失 5 / 存疑 0 / 作废 1**；**Σ权重 7 / Σ(w×c) 0 / 加权 0%**

---

## 整块缺失

| 子系统 | pi LOC | 权重 | 说明 |
|---|---:|---:|---|
| **扩展示例库 `examples/`（可执行验收规格）** | **16,033**（旧 15,809） | **3** | 105 个 `.ts`。**pi-java 零对应物**（只有 5 个测试夹具）。这些示例同时是 pi 扩展 API 的**行为规格** |
| **上下文文件发现（AGENTS.md / CLAUDE.md）** | ~150（`resource-loader.ts:185/232`） | **3** | pi 按 5 个候选名从 cwd 向上发现并注入系统提示词。pi-java **整块不存在** |
| **JSON 主题系统** | **2,215**（旧 1,552） | 2 | `theme.ts` + `theme-schema.json` + `theme-controller.ts` + `system-theme.ts`；pi-java 只有 113 行 `PiTheme.java` + 2 个内建 `.tcss` |
| **npm / git 包源 + 资源优先级 + 忽略文件** | ~2,300（`package-manager.ts`，**本轮有改动**） | **3** | pi 是完整包生态层；pi-java 是「下 JAR 到目录」 |
| **自更新（self-update）** | ~300 | 1 | pi `update self` 换二进制；pi-java 只打印「走 Maven Central」 |
| **TUI 扩展 UI 注入层** | ~700 | 2 | widget / overlay / footer·header / 按键拦截 / 编辑器注入全缺 |
| **交互式会话选择器（`-r`）** | ~1,200（`session-selector.ts` + `session-selector-search.ts`；`cli/session-picker.ts`） | 2 | pi-java 无 `-r` 无参路径（抛异常） |

**整块缺失汇总：对齐 0 / 缺失 7 / 存疑 0 / 合计 7 = 0%**；**Σ权重 16 / Σ(w×c) 0 / 加权 0%**
> ⚠️ 与 E4/E6 有重叠（「上下文文件发现」↔ E4-5、「JSON 主题系统」↔ E4-4、「npm/git 包源…」↔ E6-3/4/6/7/8）。**模块合计按含重叠计**，另给去重口径见 §加权汇总。

---

## 台账纠错

逐条核对了 `docs/05-open-items-register.md` 中属于本模块的条目（含本轮 pi-java 前进 23 提交带来的变化）。

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **B53** | 「`find()` 走 `all()`（`:52`）越界」 | ⚠️ **行号已变**：`PersistentSessionRepositories.java:63`（jsonl）与 `:143`（sqlite）仍走 `all()`/`list(null)`。pi 侧 `resolveSessionPath`（`main.ts:252-281`）三级阶梯不变。⇒ 真差距仍是**次序与成本**（缺 header-only ①②） | `PersistentSessionRepositories.java:59-63`；`main.ts:243-281` |
| **B54** | 「`--no-session` 在 web 路径被忽略」 | ✅ **属实**（java 侧未变）。`resolvePersistentWeb` 在 `:149`，`:149-164` 不引用 `args` | `SessionPersistence.java:149-164` |
| **B55** | 「pi 会话列表 API 带 `onProgress`」 | ✅ **属实**。`listSessionsFromDir`（`:941`）＋ `AbortSignal` ＋ `publishPartial`；java `list`（`:74`）同步全量 | `session-manager.ts:941`；`PersistentSessionRepositories.java:74-75` |
| **B30** | 「`session_info_changed` 无生产者」 | ✅ **仍属实**。`AgentSessionEvent.SessionInfoChanged`（`:73`）有消费（`JsonEventMapper.java:103`）但**无 emit** | `AgentSessionEvent.java:73` |
| **B35** | 「`thinking_level_changed` 无生产者」 | ⚠️ **宿主侧已补**：`AgentSession.java:484` 现 emit `ThinkingLevelChanged`；但**扩展事件 `thinking_level_select` 仍无面** ⇒ D-37 判定不变 | `AgentSession.java:484` |
| **B1** | 「branch summary 无实现」 | ✅ **属实**。`Settings.java` 无 `branchSummary`，全模块无分支摘要代码 | `Settings.java:24-64` |
| **B32** | 「`get_state.sessionName` 恒有值」 | ✅ **属实**。`SessionPersistence.java:249` `sessionNameOf` 兜底 | `SessionPersistence.java:249` |
| **F3** | 「`_emitSessionCompactFailed`」 | ✅ **属实**（无该扩展面）。pi 侧 `aborted` 判定已是**信号权威**（`agent-session.ts:2840`） | `AgentSessionEvent.java:87` |
| **F6** | 「`ModelsJsonProvider` 钉死 baseUrl」 | ⚠️ **本模块范围外**；症状仍在（`Args.baseUrl` 在 coding-agent 侧不消费） | `Args.java:20` |
| **A4** | 「运行中手动 `/compact` 有没有门」 | ✅ **取证完毕**（pi 语义是 **abort-first**）。pi `compact()`（`agent-session.ts:2717`）第一句 `await this.abort()`，自建 `_compactionAbortController`（`:2719`）；`_getSummarizationRequestAuth(model, signal)`（`:2680`）；`aborted = signal.aborted \|\| cancelledByExtension`（`:2840`）；`abortCompaction()`（`:2867`）。**pi-java 真缺**：`MiscCommands.java:124` → `AgentSession.compact:652` → `harness.compact(lane, settings)` —— **无 abort-first、无 `abortCompaction`、无取消语义** | pi `agent-session.ts:2680/2717-2719/2840/2867`；java `AgentSession.java:652-653` |
| **A7** | 「`retry` 子对象保形不接线」 | ❌ **本轮翻案**：java 已接线 `getRetrySettings()`（`SettingsAccessors.java:197`）+ `getProviderRetrySettings()`（`:218`）→ `AgentSession.java:312/397` + api options（包 A-14） | 同证据 |
| **A9** | 「per-model 压缩设置」 | ✅ **属实**。java `Settings.Compaction`（`:126`）只有 3 键、无 `modelOverrides` | `Settings.java:126` |
| **B46** | 「`tool_execution_update` 生产无数据源」 | ✅ **属实**（措辞限定为「无生产数据源」） | `AgentSessionEvent.java:133` |

**未发现反向误报**。上表 4 处为「行号/定性」级修正（B53/B35 行号＋状态、A7 翻案、A4 行号），非「缺↔有」反转。

---

## 基准漂移（旧 `3390bd936` → 新 `200387122`；java `34849a2` → `351d199`）

**pi 侧**：`coding-agent/src` 73,781 → **84,990 行**；277 个改动的 `.ts`。删除面：`experimental/{micro,mini}/`（23 文件）＋ `modes/interactive/components/daxnuts.ts`（彩蛋）。
**本轮实质变化（必须改判定而非只改行号）**：
- `core/extensions/types.ts`：`on()` **37 → 41 条**（+`mcp_servers_change`/`context_with_system`/`provider_stream_event`/`agent_before_settle`）；`ExtensionAPI` **+7 成员**；`ExtensionUIContext` `loadTheme` → `getTheme`，+`theme` 只读属性。
- `core/settings-manager.ts`：`interface Settings` **52 → 55 键**（+`deviceId`/`codemode`/`fullscreenWheelScrollLines`）。
- `core/session-manager.ts`：函数整体下移/上移（`newSession:1057`、`findMostRecentSession:749`、`listSessionsFromDir:941`、`getTree:1529`、`branch:1579`、`createBranchedSession:1632`）；`CURRENT_SESSION_VERSION=3`（`:41`）。
- `core/agent-session.ts`：`compact():2717`、`abortCompaction():2867`、`reload():3612`。
- `cli/args.ts`：flag 集**零变**，仅行号下移（`:82-262`）。
- `core/slash-commands.ts`：**+`/bug`**，`:18-43`。
- `core/package-manager.ts` **不再是零改动**（2,699 → 2,760）；`package-manager-cli.ts` 1,102 → 1,119。
- `core/system-prompt.ts` 收缩为 216 行。
- **新增文件**：`core/mcp-servers.ts`、`core/virtual-models.ts`、`core/nested-tool-calls.ts`、`src/extensions/{mcp,codemode,tool-search}/**`、新包 `packages/{mcp,codemode}`、`modes/interactive/components/{radius-login-selector,easter-egg-3d,pi-logo,themed-text,auth-url}.ts`、`theme/system-theme.ts`。

**pi-java 侧行号更正 N = 21 处**（`34849a2` → `351d199` 的 23 提交改了 22 个 `src/main` 文件）：
- `core/SessionPersistence.java`：`:179→:180`、`:246→:249`（另 `:149/:169/:175/:186/:192/:201` 不变）。
- `core/PersistentSessionRepositories.java`：`:52→:59/:63`、`:74→:74/:75`、`:290→:95`、`:307→:109`（JSONL repo 整体移位；sqlite 在 `:139`）。
- `core/AgentSession.java`：`:142/174/190→:126/159/175`、`:416→:503`、`:641→:652/:653`、`:899→:919`、`:1007→:707/:714`。
- `core/AgentSessionEvent.java`：`:23→:22`、`:26→:25`、`:27→:28`、`:47→:46`、`:71→:73`、`:74→:83`、`:79→:87`、`:104→:111`、`:117→:129`、`:121→:133`、`:125→:138`（另 +`AutoRetryStart:91`/`SummarizationRetry*:98/105/109`）。
- `core/Settings.java`：字段整体下移（`defaultProvider:23→:24`…`retry:61→:64`；+`defaultBaseUrl/defaultApiKey/sessionBackend/catalogBaseUrl/tui`）。
- `core/SettingsAccessors.java`：`getDefaultProvider:28→:20`…（整体上移；`getRetrySettings:197`、`getProviderRetrySettings:218` 新增）。
- `mode/JsonEventMapper.java`：case 块整体移位（`:64-198`）。
- `cli/ThinkingLevels.java`：7 档（`validValues:33`）。

---

## 未加权汇总

| 分区 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| A. CLI 参数 | 29 | 5 | 9 | 43 | 67.4% |
| B. 子命令 | 2 | 2 | 4 | 8 | 25.0% |
| C. Slash 命令 | 11 | 3 | 11 | 25 | 44.0% |
| D. 扩展系统 41 事件 | 1 | 32 | 8 | 41 | 2.4% |
| D′. 扩展 UI 注入面 | 0 | 8 | 1 | 9 | 0% |
| E. 其余能力单元 | 44 | 70 | 34 | 148 | 29.7% |
| ├ E1 配置文件 | 2 | 1 | 2 | 5 | 40.0% |
| ├ E2 settings 键 | 19 | 23 | 13 | 55 | 34.5% |
| ├ E3 会话管理 | 14 | 4 | 6 | 24 | 58.3% |
| ├ E4 资源发现 | 3 | 4 | 0 | 7 | 42.9% |
| ├ E5 扩展 API | 5 | 26 | 4 | 35 | 14.3% |
| ├ E6 包管理器 | 0 | 11 | 5 | 16 | 0% |
| └ E7 RPC 面 | 1 | 1 | 4 | 6 | 16.7% |
| G. 主流新增子系统（MCP/codemode/…） | 3 | 5 | 2 | 10 | 30.0% |
| F. 整块缺失（子系统） | 0 | 7 | 0 | 7 | 0% |
| **合计（模块，不含 E8）** | **90** | **132** | **69** | **291** | **30.9%** |
| E8. 新增子系统（**与 E3/C/G 重叠，不计入**） | 0 | 5 | 0 | 5 | 0% |

**严格口径**（把「引擎钩子存在但未桥接」的 8 条也算缺失）：对齐 **90** / 缺失 **140** / 存疑 **61** / 合计 **291** = **30.9%**。

**扩展系统单独口径**（D + D′ + E5）：41 事件 = 1 对齐；UI 注入 9 面 = 0 对齐；扩展 API 35 项 = 5 对齐 ⇒ **85 个单元中 6 个对齐 = 7.1%**。

**java 独有（不计入分母）**：`--base-url`、`--port`、`--debug`、`--trace-payloads`（CLI）；`/help`、`/create-skill`（slash）；`auth oauth-login`、`auth profile…`（子命令）。

**最大三处差距**：① **D 域扩展事件面 41→0（可订阅）**；② **E6 包管理器**且**零对齐单元**；③ **G 域 5 个新子系统（10,994 行）基本不存在**（MCP / codemode / tool-search / virtual-models / nested-tool-calls）—— MCP 面已落（包①–⑩：`pi-java-mcp` **10,700 行**主源＋5,904 测试，加上 `coding-agent` 的 `extension/mcp` **约 1,400 行**＋其夹具），G-2/G-3/G-5 因此记**对齐**、G-1/G-4 记**存疑**（G-1 缺 `index.ts` 接线、G-4 缺 `/mcp` 与 UI）；**codemode（2,916）＋ tool-search/virtual-models/nested（499）共约 3,415 行仍为 0**。
**本轮新增差距**：④ `cache_warming` 设置＋`/bug`（保留）＋ `defaultTools +name/-name` ＋ RPC prompt disposition（已分别计入 E2/C/E7/G）。

---

## 加权汇总

> 口径：权重 = 用户可观察影响 × 频率（**不含排期优先级**）；`3` = 每轮对话都走 / 默认路径，`2` = 每次会话走 / 常用命令·参数·配置键，`1` = 低频·边缘·纯内部，`0` = 非目标。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A. CLI 参数 | 62 | 48.0 | 77.4% | 67.4% |
| B. 子命令 | 15 | 7.0 | 46.7% | 25.0% |
| C. Slash 命令 | 38 | 25.0 | 65.8% | 44.0% |
| D. 扩展系统 41 事件 | 83 | 11.5 | 13.9% | 2.4% |
| D′. 扩展 UI 注入面 | 16 | 1.0 | 6.3% | 0% |
| E1. 配置文件 / 清单 | 10 | 6.0 | 60.0% | 40.0% |
| E2. settings.json 键 | 86 | 47.5 | 55.2% | 34.5% |
| E3. 会话管理行为 | 40 | 27.5 | 68.8% | 58.3% |
| E4. 资源发现 | 14 | 5.0 | 35.7% | 42.9% |
| E5. 扩展 API 面 | 58 | 14.0 | 24.1% | 14.3% |
| E6. 包管理器 | 31 | 5.0 | 16.1% | 0% |
| E7. RPC 面 | 12 | 6.0 | 50.0% | 16.7% |
| G. 主流新增子系统 | 18 | 7.0 | 38.9% | 30.0% |
| F. 整块缺失（子系统） | 16 | 0.0 | 0% | 0% |
| **模块合计（不含 E8）** | **499** | **210.5** | **42.2%** | **30.9%** |
| E8. 新增子系统（与 E3/C/G 重叠，不计入） | 7 | 0.0 | 0% | 0% |

**模块合计**：Σ权重 499 ／ Σ(w×c) 210.5 ／ **加权完成度 = 210.5/499 = 42.2%**（未加权 90/291 = 30.9%）
> 旧基准对照：Σ权重 455 ／ Σ(w×c) 200.0 ／ 44.0% ⇒ **换锚后加权完成度 −1.8 pp**（分母 +44，分子 +10.5）。
> ⚠️ 该 −1.8 pp 里，**−3.2 pp 是换锚本身**（新基准把 MCP/codemode/… 整块加进分母、当时全记缺失 ⇒ 40.8%），**+1.4 pp 是包⑧/⑨/⑩ 三轮**回填**（40.8% → 41.0% → 42.0% → 42.2%）——回填只改**判定**不改分母，读「pi 前进、pi-java 静止」那句话时要分开看。
> 权重增量的构成（**换锚当刻**）：**6 块新主流面共 +31 权重**（MCP 9 / codemode 4 / tool-search 2 / virtual-models 2 / nested-tool-calls 1 / 其它分散 13）**当时全部记缺失**，加上 4 个新扩展事件（+9 权重）、3 个新 settings 键（+4）、RPC disposition（+1）、`mcp` 子命令（+2）。

**去重口径**（F 域有 3 行与 E4/E6 重叠）：剔除 F 域 ⇒ Σ权重 **483** ／ Σ(w×c) **210.5** ／ **加权完成度 = 210.5/483 = 43.6%**（未加权 90/284 = 31.7%）。

**扩展系统单独口径**（D + D′ + E5）：Σ权重 157 ／ Σ(w×c) 26.5 ／ **加权 16.9%**（未加权 6/85 = 7.1%）。

**权重分层**：

| 权重层 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|---:|
| 权重 3（每轮 / 默认路径） | 24 | 72 | 28.5 | **39.6%** | 6/24 = 25.0% |
| 权重 2（每次会话 / 常用） | 160 | 320 | 134.0 | 41.9% | 49/160 = 30.6% |
| 权重 1（低频 / 边缘 / 内部） | 107 | 107 | 48.0 | 44.9% | 35/107 = 32.7% |
| **合计** | **291** | **499** | **210.5** | **42.2%** | **90/291 = 30.9%** |

> 读数：**权重 3 层的加权完成度（39.6%）仍低于权重 1 层（44.9%）** —— pi-java 在「每轮都走」的地方欠账最重。新加入的 **`context_with_system`（w3，缺失）** 把权重 3 层从 23 个单元扩到 24 个，权重层完成度从旧基准的 ~41% 微降到 39.6%。
> 包⑧/⑨/⑩ 三轮回填只动 **G 域**（w2 的 G-1/G-2/G-5、w1 的 G-3）：权重 2 层 40.3% → **41.9%**、权重 1 层 43.9% → **44.9%**，**权重 3 层不动** ⇒ 「权重 3 层最低」这条读数**更强**（39.6% vs 41.9% vs 44.9%，单调）。

### 权重 3 单元（24 个 —— 最该先修）

| # | 域 | 单元 | 权重 | 判定 |
|---|---|---|---|---|
| 1 | A | `--model` | 3 | 对齐 |
| 2 | A | `--print`/`-p` | 3 | 对齐 |
| 3 | D | `context` | 3 | **存疑** |
| 4 | D | `context_with_system`（**新增**） | 3 | **缺失** |
| 5 | D | `before_provider_request` | 3 | **存疑** |
| 6 | D | `before_agent_start` | 3 | **缺失** |
| 7 | D | `message_update` | 3 | **缺失** |
| 8 | D | `input` | 3 | **缺失** |
| 9 | D | `tool_call` | 3 | **存疑** |
| 10 | D | `tool_result` | 3 | **存疑** |
| 11 | E2 | `defaultProvider` | 3 | 对齐 |
| 12 | E2 | `defaultModel` | 3 | 对齐 |
| 13 | E2 | `compaction` | 3 | **存疑** |
| 14 | E3 | 新建会话（无参） | 3 | 对齐 |
| 15 | E4 | 上下文文件发现（AGENTS.md/CLAUDE.md） | 3 | **缺失** |
| 16 | E5 | `registerTool` | 3 | 对齐 |
| 17 | E5 | 扩展示例库 | 3 | **缺失** |
| 18 | E6 | npm 源 | 3 | **缺失** |
| 19 | E6 | 持久化到 `settings.packages` | 3 | **缺失** |
| 20 | E7 | RPC 命令集 | 3 | **存疑** |
| 21 | E7 | RPC 事件线格式 | 3 | **存疑** |
| 22 | F | `examples/` 可执行验收规格 | 3 | **缺失** |
| 23 | F | 上下文文件发现（子系统视图） | 3 | **缺失** |
| 24 | F | npm/git 包源 + 优先级 + 忽略文件 | 3 | **缺失** |

> 权重 3 层 24 个单元中：**对齐 6 / 存疑 7 / 缺失 11**（Σ(w×c) 28.5 / 72 = 39.6%）。
> 归属：扩展系统 **9 个**（#3–#10 事件面 + #17 扩展示例库）；包管理器 **3 个**（#18/#19/#24）；其余 12 个散在 CLI（2）、settings（3）、会话（1）、资源发现（1）、RPC（2）、整块缺失（3，其中 2 个与前者重叠）。
> **本轮新增权重 3 单元 1 个**（D-`context_with_system`）。

### 未加权 vs 加权的差

| 指标 | 数值 |
|---|---|
| 未加权完成度 | 30.9%（90/291） |
| 加权完成度（含 F 域） | **42.2%**（210.5/499） |
| 差 | **+11.3 pp** |
| 加权完成度（去重，剔 F 域） | 43.6%（210.5/483）＝ +12.7 pp |
| 旧基准同口径（对照） | 44.0%（200.0/455）⇒ **−1.8 pp** |

**差在哪**：加权把完成度**抬高** 11.3 pp，因为 pi-java 的对齐单元**密集落在高权重的默认路径上**，而缺失质量集中在低权重长尾。
- **抬高项**：A 域（CLI）未加权 67.4% → 加权 77.4%；C 域 44.0% → 65.8%；E3 58.3% → 68.8%；E1/E2 也抬高；**G 域 30.0% → 38.9%**（G-2 w2＋G-3 w1＋G-5 w2 全对齐）。
- **压低项**：E4（资源发现）42.9% → **35.7%**（唯一的高权单元 `上下文文件发现` w3 全缺）；D 域 2.4% → **13.9%**。
- **换锚那一刻新增的权重全部落在缺失**：G 域 18 权重（+D 9 +E2 4 +B 2 +E7 1 +E5 8 等），当时**加权完成度只降不升** —— 那是「pi 前进、pi-java 静止」在加权口径下的直接读数。包⑧/⑨ 的回填是**同一基准下重记判定**，方向相反。

- **两个点名项**：
  - **扩展事件面**：D 域 Σ权重 **83** = 模块 499 的 **16.6%**，是全部 15 个域里**最大的一块**。Σ(w×c) 仅 11.5 ⇒ 若 D 域全对齐，模块 Σ(w×c) 210.5 → **282.0**、完成度 42.2% → **56.5%**，即**这一域单独把模块拉低 14.3 pp**。
  - **上下文文件发现整块缺失**：作为单个能力单元只占 Σ权重 6（E4-5 w3 + F 域子系统视图 w3）= 模块的 1.2%；**但它是每轮 prompt 都走的默认路径**，单元粒度封顶了权重上限 —— 同 `G` 域 5 个新子系统（10,994 行）一样，被「1 子系统 = 1 单元」压缩到 18 权重（真实行为面展开应在 40+）。
  - **口径修正建议**（沿用）：整块缺失的子系统应按「暴露的行为面个数」而非 1 计入分母。本表按用户给定规则计。
