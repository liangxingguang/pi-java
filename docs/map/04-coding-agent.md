# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 范围：`D:\workplaceForai\pi-java\pi-java-coding-agent` ↔ `D:\workplaceForai\pi\packages\coding-agent`。
> 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差，或台账未结）。
> 所有证据为 `file:line`；pi 侧路径省略 `D:\workplaceForai\pi\packages\coding-agent\` 前缀，java 侧省略 `D:\workplaceForai\pi-java\pi-java-coding-agent\src\main\java\com\pijava\coding\agent\` 前缀。
> 基准：pi `3390bd936`（2026-09-20，较上一版基准 `71dca871b` 前进 **111 个提交**；`coding-agent` **149 个文件**被改，+7,745 / −1,366 行）。
> java 侧未动，仍为 main @ `34849a2`。**旧基准行号已逐条重测**，漂移见 §基准漂移。
>
> **权重口径**（用户已认可）：权重 = 用户可观察影响 × 频率，**不含排期优先级**。
> `3` = 每轮对话都走 / 默认路径；`2` = 每次会话走 / 常用命令·参数·配置键；`1` = 低频·边缘·纯内部；`0` = 非目标（排除出分母，本模块无 0 权重单元）。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。加权汇总见文末 §加权汇总。

---

## 规模

| 模块 | pi 包 | pi LOC（旧 → 新） | java LOC | 比例 |
|---|---|---:|---:|---:|
| **产品层合计（src/main）** | `packages/coding-agent/src` | **70,052 → 73,781** | **10,325** | **14.0%** |
| ├ 扩展系统 | `core/extensions` 4,130 → **4,210** + `src/extensions` 1,457 → **1,483** | 5,693 | 1,516 (`extension/` 717 + `agent-core/hook/` 799) | 26.6% |
| ├ 包管理器 | `package-manager.ts` 2699 + `package-manager-cli.ts` 1102 + `pi-manifest.ts` 35 + `tools-manager.ts` 400 | 4,236（**未变**） | 496 | 11.7% |
| ├ 主题系统 | `modes/interactive/theme/*` | 1,552（**未变**） | 113 | 7.3% |
| ├ 会话管理 | `core/session-manager.ts` | 1,746 → **1,871** | 771 | 41.2% |
| ├ 资源发现 | `skills.ts` 509 + `prompt-templates.ts` 285 + `resource-loader.ts` 1097 | 1,891（**未变**） | 896 | 47.4% |
| ├ RPC | `modes/rpc/*` | 1,785（**未变**） | 1,248 | 69.9% |
| ├ CLI/TUI 交互 | `modes/interactive/*` 20,662 → **21,218** + `cli/*` 1,920 → **1,921** + `utils/*` 3,544 → **3,680** | 26,819 | ~3,000 | ~11.2% |
| ├ **本轮新增子系统** | `cache-warmer` 453 + `bug-report` 706 + `crash-log` 92 + `extensions/jiti*` 44 + `experimental/micro` 1,485 + `utils/wsl·zip` 87 | **2,867** | 0 | **0%** |
| └ **可执行验收规格 examples/** | `examples/`（101 个 .ts，78 个扩展示例） | 15,912 → **15,809** | **0** | **0%** |
| 测试 | `test/` | 57,036 → **59,519** | 6,844 | 11.5% |

> java 侧 10,325 行未变：`core` 5010 / `rpc` 1248 / `extension` 717 / `subcommand` 615 / `skill` 613 / `cli` 612 / `export` 489 / `mode` 376 / `prompt` 283 / `modes` 192 / `spi` 44。
> pi 侧增量分布：`core` 29,802 → **31,288**（+1,486）、`experimental` 9,192 → **10,693**（+1,501）、`modes` +556、`utils` +136、`extensions` +26、`cli` +1、`src/*.ts` 3,421 → **3,444**。
> **`interactive-mode.ts` 6,620 → 6,779**（+159）。
> **扩展系统的 799 行在 `pi-java-agent-core/src/main/java/com/pijava/agent/hook/`**，不在本模块 —— 且**没有桥接到 `PiExtension`**（见 §扩展系统）。

---

## CLI 参数对齐

逐条数：pi `src/cli/args.ts:82-226` 共 **40 个 flag** + `--` + `@files` + 扩展注册 flag = **43 个能力单元**。**本轮 pi 侧零变动**（`args.ts` 447 → 448 行，仅帮助文本 +1；`parseArgs` 分支逐字相同，`diff` 无输出）。
java `cli/ArgsParser.java` 共 **43 个 `@Option`**，其中 **4 个 java 独有**（`--base-url:36`、`--port:63`、`--debug:144`、`--trace-payloads:147`）。

| # | 参数/子命令 | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `--help`/`-h` | ✅ `args.ts:91` | ✅ `ArgsParser.java:54` | 2 | 对齐 | 两侧均打印帮助后 exit 0（`Main.java:57`） |
| 2 | `--version`/`-v` | ✅ `args.ts:93` | ✅ `ArgsParser.java:57` | 1 | 对齐 | `Main.java:61` |
| 3 | `--mode` | ✅ `args.ts:95` | ✅ `ArgsParser.java:60` | 2 | 对齐 | java 多 `web`（`Args.java:26`）；pi 只有 text/json/rpc |
| 4 | `--continue`/`-c` | ✅ `args.ts:100` | ✅ `ArgsParser.java:48` | 2 | 对齐 | `SessionPersistence.java:169` ↔ pi `main.ts:428` `continueRecent` |
| 5 | `--resume`/`-r` | ✅ `args.ts:102` | ⚠️ `ArgsParser.java:51` | 2 | **存疑** | pi `-r` 无 `--session` 时**打开会话选择器**（`main.ts:415` sessionPicker）；java `SessionPersistence.java:186` 走 `handle.find(null)` ⇒ **抛 `Session not found: null`** |
| 6 | `--provider` | ✅ `args.ts:104` | ✅ `ArgsParser.java:27` | 2 | 对齐 | |
| 7 | `--model` | ✅ `args.ts:106` | ✅ `ArgsParser.java:30` | **3** | 对齐 | 均支持 `provider/id` 与 `:thinking` |
| 8 | `--api-key` | ✅ `args.ts:108` | ✅ `ArgsParser.java:33` | 2 | 对齐 | |
| 9 | `--system-prompt` | ✅ `args.ts:110` | ✅ `ArgsParser.java:39` | 1 | 对齐 | |
| 10 | `--append-system-prompt` | ✅ `args.ts:112` | ✅ `ArgsParser.java:42` | 1 | 对齐 | 均 repeatable |
| 11 | `--name`/`-n` | ✅ `args.ts:115` | ✅ `ArgsParser.java:66` | 1 | 对齐 | `AgentSession.java:416` |
| 12 | `--no-session` | ✅ `args.ts:121` | ⚠️ `ArgsParser.java:69` | 1 | **存疑** | 非 web 路径生效；**web 路径被忽略**（`SessionPersistence.java:149` 的 `args` 形参零使用）—— 台账 **B54** |
| 13 | `--session` | ✅ `args.ts:123` | ⚠️ `ArgsParser.java:72` | 2 | **存疑** | 行为有差：java `find()` 走 `repo.list(JsonlSessionListOptions.all())`（`PersistentSessionRepositories.java:52`）⇒ **O(全部会话文件) 且跨项目**；pi `findLocalSessionByExactId(id, cwd, sessionDir)`（`main.ts:242`）是**项目内** —— 台账 **B53** |
| 14 | `--session-id` | ✅ `args.ts:125` | ⚠️ `ArgsParser.java:75` | 2 | **存疑** | 同上（B53）；缺失时 java **创建**（`SessionPersistence.java:179`）↔ pi `main.ts:432-441` 同 |
| 15 | `--fork` | ✅ `args.ts:127` | ⚠️ `ArgsParser.java:78` | 1 | **存疑** | 同上（B53）；`SessionPersistence.java:192` |
| 16 | `--session-dir` | ✅ `args.ts:129` | ✅ `ArgsParser.java:81` | 1 | 对齐 | `AgentSession.java:142/174/190` |
| 17 | `--models` | ✅ `args.ts:131` | ❌ `ArgsParser.java:84` | 2 | **缺失** | flag 解析存在，但 `args.models()` **全仓零消费者**（grep `.models()` = 0 命中）⇒ Ctrl+P 循环列表无实现 |
| 18 | `--no-tools`/`-nt` | ✅ `args.ts:133` | ✅ `ArgsParser.java:93` | 1 | 对齐 | `-nt` 由 `ArgsParser.java:257` 展开 |
| 19 | `--no-builtin-tools`/`-nbt` | ✅ `args.ts:135` | ✅ `ArgsParser.java:96` | 1 | 对齐 | |
| 20 | `--tools`/`-t` | ✅ `args.ts:137` | ✅ `ArgsParser.java:87` | 2 | 对齐 | |
| 21 | `--exclude-tools`/`-xt` | ✅ `args.ts:142` | ✅ `ArgsParser.java:90` | 1 | 对齐 | |
| 22 | `--thinking` | ✅ `args.ts:147` | ✅ `ArgsParser.java:45` | 2 | 对齐 | 两侧都是 7 档 `off..max`（`ThinkingLevels.java`） |
| 23 | `--print`/`-p` | ✅ `args.ts:157` | ✅ `ArgsParser.java:126` | **3** | 对齐 | pi 会吞下紧随的非 flag 作为 message（`args.ts:159-162`）；java 走 picocli unmatched（`ArgsParser.java:212`） |
| 24 | `--export` | ✅ `args.ts:164` | ⚠️ `ArgsParser.java:129` | 1 | **存疑** | pi 支持 **2 个位置参数**（`--export in.jsonl out.html`，`args.ts:385`）；java 只取 1 个（`Main.java:99`），输出路径由 `HtmlExporter` 决定 |
| 25 | `--extension`/`-e` | ✅ `args.ts:166` | ❌ `ArgsParser.java:99` | 2 | **缺失** | flag 解析存在，`args.extensions()` **零消费者**（grep = 0）⇒ 显式扩展文件路径无实现 |
| 26 | `--no-extensions`/`-ne` | ✅ `args.ts:169` | ✅ `ArgsParser.java:102` | 1 | 对齐 | `AgentSession.java:1005` |
| 27 | `--skill` | ✅ `args.ts:171` | ✅ `ArgsParser.java:105` | 1 | 对齐 | `SkillDiscovery.java:36` |
| 28 | `--prompt-template` | ✅ `args.ts:174` | ✅ `ArgsParser.java:111` | 1 | 对齐 | `PromptTemplates.java:47` |
| 29 | `--theme` | ✅ `args.ts:177` | ⚠️ `ArgsParser.java:117` | 1 | **存疑** | 语义不同：pi 收 **JSON 主题名或文件**（`theme-json.ts`）；java 只收 `.tcss` 路径（`PiTheme.java:97`），且只取 `themes().get(0)`（`PiTuiLauncher.java:115`） |
| 30 | `--use-theme` | ✅ `args.ts:180` | ❌ — | 1 | **缺失** | java 无此 flag |
| 31 | `--no-skills`/`-ns` | ✅ `args.ts:188` | ✅ `ArgsParser.java:108` | 1 | 对齐 | |
| 32 | `--no-prompt-templates`/`-np` | ✅ `args.ts:190` | ✅ `ArgsParser.java:114` | 1 | 对齐 | |
| 33 | `--no-themes` | ✅ `args.ts:192` | ✅ `ArgsParser.java:120` | 1 | 对齐 | |
| 34 | `--no-context-files`/`-nc` | ✅ `args.ts:194` | ❌ `ArgsParser.java:123` | 2 | **缺失** | flag 解析存在，`args.noContextFiles()` **零消费者**；且 **AGENTS.md/CLAUDE.md 发现子系统整体不存在**（见 §整块缺失） |
| 35 | `--list-models` | ✅ `args.ts:196` | ✅ `ArgsParser.java:132` | 1 | 对齐 | 均可选搜索词（pi 模糊搜索 `cli/list-models.ts:29`） |
| 36 | `--tui-mode` | ✅ `args.ts:203` | ✅ `ArgsParser.java:138` | 1 | 对齐 | regular/fullscreen |
| 37 | `--verbose` | ✅ `args.ts:217` | ✅ `ArgsParser.java:141` | 1 | 对齐 | |
| 38 | `--approve`/`-a` | ✅ `args.ts:219` | ✅ `ArgsParser.java:150` | 2 | 对齐 | |
| 39 | `--no-approve`/`-na` | ✅ `args.ts:221` | ✅ `ArgsParser.java:153` | 1 | 对齐 | |
| 40 | `--offline` | ✅ `args.ts:223` | ❌ `ArgsParser.java:135` | 1 | **缺失** | flag 解析存在，`args.offline()` **零消费者**（grep = 0）⇒ `PI_OFFLINE` 语义无实现 |
| 41 | `--`（选项终止） | ✅ `args.ts:82-89` | ⚠️ picocli 内建 | 1 | **存疑** | pi 显式处理并区分 `@file`/message；java 未显式处理，行为未核（无夹具） |
| 42 | `@files` 位置参数 | ✅ `args.ts:226` | ✅ `ArgsParser.java:212` | 2 | 对齐 | 两侧都剥离 `@` 前缀进 `fileArgs` |
| 43 | 扩展注册的 CLI flag | ✅ `args.ts:205-212` + `registerFlag`/`getFlag`（`extensions/types.ts:1330-1348`） | ❌ | 1 | **缺失** | java `ArgsParser.java:160` 只把未知 flag 收进 `unmatched`，**无 `registerFlag` API、无 `getFlag`** |

**汇总：对齐 29 / 缺失 6 / 存疑 8 / 合计 43 = 67.4%**；**Σ权重 62 / Σ(w×c) 47.5 / 加权 76.6%**

### 子命令对齐

pi `main.ts:586/599` + `package-manager-cli.ts` 共 **7 个**；java `SubcommandHandler.java:14` 同 7 个。

| # | 子命令 | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `install <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:44` | 2 | **存疑** | java 只支持**本地 JAR 路径或 http(s) URL**（`ExtensionPackageManager.java:17`）；pi 支持 **npm / git / local** 三源 |
| 2 | `remove <source> [-l]` | ✅ | ⚠️ `PackageCommand.java:55` | 2 | **存疑** | 同上 |
| 3 | `uninstall <source>`（remove 别名） | ✅ | ✅ `SubcommandHandler.java:36` | 1 | 对齐 | |
| 4 | `update [source\|self\|pi]` | ✅ `package-manager-cli.ts:1006` | ❌ `PackageCommand.java:20` | 2 | **缺失** | java 只打印 `SELF_UPDATE_NOTE`（"handled by the Maven Central release pipeline"），**无 self-update、无扩展更新、无模型目录刷新** |
| 5 | `list` | ✅ `package-manager-cli.ts:970` | ⚠️ `PackageCommand.java:63` | 2 | **存疑** | pi 按 user/project 分组并显示 `installedPath` + `(filtered)`；java 平铺 |
| 6 | `config [-l]` | ✅ `package-manager-cli.ts:791`（**TUI**，Tab 切作用域） | ⚠️ `ConfigCommand.java:12` | 2 | **存疑** | java 是**非交互** `config enable\|disable <resource> <value> [-l]` |
| 7 | `auth <cmd>` | ✅ check / print-api-key / print-bearer-token（`auth-command.ts:52-61`） | ✅ `AuthCommand.java:43-47` | 2 | 对齐 | java 是**超集**：多 `oauth-login`、`profile set/unset/list/set-key` |

**汇总：对齐 2 / 缺失 1 / 存疑 4 / 合计 7 = 28.6%**；**Σ权重 13 / Σ(w×c) 7.0 / 加权 53.8%**

---

## Slash 命令对齐

pi `core/slash-commands.ts:20-43` 共 **24 条**（旧基准 23 条；本轮 **+`/bug`**，`slash-commands.ts:28`）；java `CommandRegistry.withBuiltins()` 注册 **24 条**（javadoc 写 "22" 是陈旧值）。
java 独有 **2 条**（`/help`、`/create-skill`），不计入 pi 侧单元。
pi 另有 **`/skill:name` 命令族**（`enableSkillCommands` 默认 true，`interactive-mode.ts:713-718`）—— 单列。

| # | 命令 | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `/settings` | ✅ `slash-commands.ts:20` | ⚠️ `SettingsCommands.java:22` | 2 | **存疑** | java 只返回 `UI_SETTINGS` marker（`CommandRegistry.java:33`），TUI 侧 `PiTuiApp.java:411` 接；pi 的 `settings-selector.ts` 945 行 + `settings-submenu.ts` 258 行 |
| 2 | `/model` | ✅ `:21` | ⚠️ `ModelCommands.java:21` | 2 | **存疑** | pi 支持 `/model <provider/model>` **直接设置**（argumentHint 非空）；java `argumentHint()` 为空且**忽略 args**，只返回 marker |
| 3 | `/tree` | ✅ `:22` | ⚠️ `SessionCommands.java:56` | 1 | **存疑** | 两侧都是 marker/选择器；pi `tree-selector.ts` 1427 行（含过滤模式 `treeFilterMode` 5 档） |
| 4 | `/thinking` | ✅ `:23` | ❌ — | 2 | **缺失** | java 无此命令（CLI `--thinking` 与 RPC `set_thinking_level` 有，slash 面无） |
| 5 | `/scoped-models` | ✅ `:24` | ⚠️ `ModelCommands.java:29` | 1 | **存疑** | java 支持 `+/-model` 直接改 + marker；pi `scoped-models-selector.ts` 401 行 |
| 6 | `/export` | ✅ `:25` | ⚠️ `MiscCommands.java:38` | 2 | **存疑** | pi **默认 HTML**、路径可选；java `args.isBlank()` 时直接返回用法（**不给默认**） |
| 7 | `/import` | ✅ `:26` | ✅ `MiscCommands.java:61` | 1 | 对齐 | 两侧都是 JSONL → 新会话并切换 |
| 8 | `/share` | ✅ `:27` | ⚠️ `MiscCommands.java:76` | 1 | **存疑** | pi 主路径是 **Radius 网关上传**，gist 只是 fallback（`session-share.ts:57` `tryShareViaRadius`）；java 只有私有 gist 且要求 `GITHUB_TOKEN`（`SessionShare.java:44`） |
| 9 | `/copy` | ✅ `:28` | ✅ `MiscCommands.java:96` | 2 | 对齐 | java 用 AWT 剪贴板，headless 回落打印 |
| 10 | `/name` | ✅ `:29` | ✅ `SessionCommands.java:22` | 1 | 对齐 | |
| 11 | `/session` | ✅ `:30` | ⚠️ `SessionCommands.java:30` | 1 | **存疑** | pi "Show session info **and stats**"；java `sessionInfo()` 只打 4 行（名/lane/条数/进程内会话数），**无 token/成本统计** |
| 12 | `/changelog` | ✅ `:31` | ⚠️ `MiscCommands.java:120` | 1 | **存疑** | pi 读 `CHANGELOG.md`（`utils/changelog.ts`）；java 是**硬编码字符串常量**（`MiscCommands.java:24-29`，内容还写着 "Phase 3/4/6"） |
| 13 | `/hotkeys` | ✅ `:32` | ✅ `MiscCommands.java:122` | 1 | 对齐 | 两侧都从 keybindings 生成 |
| 14 | `/fork` | ✅ `:33` | ⚠️ `SessionCommands.java:33` | 2 | **存疑** | pi 从**历史用户消息**分叉（选择器）；java `[branch-name]` 走 `forkCopy(name)`，无参数时开 tree selector |
| 15 | `/clone` | ✅ `:34` | ✅ `SessionCommands.java:48` | 1 | 对齐 | |
| 16 | `/trust` | ✅ `:35` | ✅ `SettingsCommands.java:30` | 2 | 对齐 | 两侧都写 `defaultProjectTrust` + 信任标记 |
| 17 | `/login` | ✅ `:36` | ⚠️ `MiscCommands.java:134` | 2 | **存疑** | pi 走 `login-dialog.ts` 233 行 + OAuth 选择器（`oauth-selector.ts` 214 行）；java 只用 `System.console().readPassword` **收 API key**，无 OAuth |
| 18 | `/logout` | ✅ `:37` | ✅ `MiscCommands.java:158` | 1 | 对齐 | |
| 19 | `/new` | ✅ `:38` | ✅ `SessionCommands.java:63` | 2 | 对齐 | |
| 20 | `/compact` | ✅ `:39` | ✅ `MiscCommands.java:124` | 2 | 对齐 | |
| 21 | `/resume` | ✅ `:40` | ✅ `SessionCommands.java:70` | 2 | 对齐 | 两侧都是「无参开选择器、有参直连」 |
| 22 | `/reload` | ✅ `:41` | ⚠️ `SettingsCommands.java:53` | 1 | **存疑** | pi 重载 **keybindings + extensions + skills + prompts + themes + context files**（`agent-session.ts:2610-2635`）；java 只 `settings().reload()`，注释自认 "extensions/skills/prompts in Phase 6" |
| 23 | `/quit` | ✅ `:42` | ✅ `MiscCommands.java:167` | 2 | 对齐 | |
| 24 | `/skill:<name>` 命令族 | ✅ `interactive-mode.ts:713-718`（`enableSkillCommands` 默认 true） | ❌ | 2 | **缺失** | java 无 skill→command 注册；`enableSkillCommands` 字段有（`Settings.java:45`）但**不产生任何命令** |
| 25 | `/bug`（**本轮新增**，`slash-commands.ts:28`） | ✅ "Report a bug to the Pi developers"，`<description>` | ❌ | 1 | **缺失** | pi 侧实现 = `core/bug-report.ts` 375 + `core/bug-report-upload.ts` 37 + `modes/interactive/bug-report.ts` 294（共 **706 行**）；java 无任何对应物 |

**汇总：对齐 11 / 缺失 3 / 存疑 11 / 合计 25 = 44.0%**；**Σ权重 38 / Σ(w×c) 25.0 / 加权 65.8%**

---

## 扩展系统对齐（36 → **37** 事件）

pi 的扩展事件面 = `ExtensionAPI.on()` 的 **37 个重载**（`core/extensions/types.ts:1266-1326`，旧基准 36 个；`ExtensionEvent` 联合在 `:1086-1113` → 现 **`:1092`**，本轮 **+`cache_warming_decision`**）。
⚠️ **本轮另一个实质变化**：`on()` 的返回类型由 **`void` → `() => void`**（`types.ts:1266-1326` 全部 37 条），实现见 `core/extensions/loader.ts:256-270`（返回一个把 handler 从 `extension.handlers` 摘除的闭包）⇒ **pi 扩展现在可以在运行时退订事件**；pi-java 无此能力（见 E5-28）。
pi-java 的**扩展 SPI** = `PiExtension.java:11-31`，只有 **2 个钩子**：`register(ExtensionContext)` 与 `sessionStartResources()`。
pi-java 的**引擎钩子** = `pi-java-agent-core/.../agent/hook/HookSystem.java`，**13 个**（`onBeforeRun:41`、`onBeforeResume:46`、`onTransformContext:51`、`onBeforeRequest:56`、`onBeforePayload:61`、`onAfterResponse:66`、`onBeforeTool:71`、`onAfterTool:76`、`onBeforeCompaction:81`、`onBeforeNavigation:86`、`onBeforeRunEnd:91`、`onShouldStopAfterTurn:96`、`onPrepareNextTurn:101`），**但 `ExtensionContext` 不暴露 `hookSystem()`**（`extension/ExtensionContext.java:17-65`，`docs/24 §2` 已记）⇒ **扩展拿不到**。

判定口径：本表按「**扩展能否订阅该事件**」判；引擎侧有同形钩子的记 **存疑** 并在证据列写明「引擎钩子 X（未桥接）」。

| # | 事件/hook | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `project_trust` | ✅ `types.ts:1266` | ❌ | 2 | **缺失** | java 有 `TrustManager.java:15`（标记文件），但**无 handler 注册面** |
| 2 | `resources_discover` | ✅ `types.ts:1267` | ✅ `PiExtension.java:29` | 2 | **对齐** | `ExtensionManager.java:81-100` 合并保序 + 异常隔离；`ResourcePaths` 三类路径 |
| 3 | `session_start` | ✅ `types.ts:1271` | ❌ | 2 | **缺失** | 无钩子；`SessionStartEvent` 形状（reason / previousSessionFile）在 java 无对应 |
| 4 | `session_info_changed` | ✅ `types.ts:1272` | ❌ | 1 | **缺失** | 宿主侧有 `AgentSessionEvent.SessionInfoChanged`（`AgentSessionEvent.java:71`），但**零生产者** —— 台账 **B30** |
| 5 | `session_before_switch` | ✅ `types.ts:1273` | ❌ | 2 | **缺失** | 可取消的会话切换钩子不存在 |
| 6 | `session_before_fork` | ✅ `types.ts:1277` | ❌ | 2 | **缺失** | 同上 |
| 7 | `session_before_compact` | ✅ `types.ts:1281` | ⚠️ | 2 | **存疑** | 引擎钩子 `BeforeCompactionHook`（`HookSystem.java:81`，可改 `CompactionPlan`）**未桥接**；扩展不可达。⚠️ pi 侧本轮**给该事件加了 `signal: AbortSignal`**（`agent-session.ts:2130`） |
| 8 | `session_compact` | ✅ `types.ts:1285` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.CompactionEnd`（`:79`），非扩展面 |
| 9 | `session_compact_failed` | ✅ `types.ts:1286` | ❌ | 1 | **缺失** | 台账 **F3**；⚠️ pi 本轮**改了判定来源**：`aborted` 由字符串匹配（`message === "Compaction cancelled" \|\| error.name === "AbortError"`）改为 **信号权威**（`this._compactionAbortController.signal.aborted \|\| cancelledByExtension`，`agent-session.ts:2223`） |
| 10 | `session_shutdown` | ✅ `types.ts:1287` | ❌ | 2 | **缺失** | 无 shutdown 钩子；`ExtensionManager.java:76` `unload()` 只从表移除，注释自认「扩展无 close 生命周期」 |
| 11 | `session_before_tree` | ✅ `types.ts:1288` | ⚠️ | 1 | **存疑** | 引擎钩子 `BeforeNavigationHook`（`HookSystem.java:86`，可拒绝导航）**未桥接** |
| 12 | `session_tree` | ✅ `types.ts:1292` | ❌ | 1 | **缺失** | 无导航完成事件 |
| 13 | `context` | ✅ `types.ts:1293` | ⚠️ | **3** | **存疑** | 引擎钩子 `TransformContextHook`（`HookSystem.java:51`；`ContextAssembler.java:64` 调用）**未桥接** |
| 14 | `cache_warming_decision`（**本轮新增**） | ✅ `types.ts:1294` | ❌ | 2 | **缺失** | 定义在 `core/cache-warmer.ts`（453 行，新文件）；可覆盖 `event.action`，`runner.ts:921` `emitCacheWarmingDecision` 取「最后一个 override 胜」。pi-java 无缓存预热子系统 |
| 15 | `before_provider_request` | ✅ `types.ts:1298` | ⚠️ | **3** | **存疑** | 引擎钩子 `BeforePayloadHook`（`HookSystem.java:61`，可替换 payload）**未桥接** |
| 16 | `before_provider_headers` | ✅ `types.ts:1302` | ⚠️ | 2 | **存疑** | 引擎钩子 `BeforeRequestHook`（`HookSystem.java:56`）**只读、不能改 header**，且未桥接 ⇒ 语义也不同 |
| 17 | `after_provider_response` | ✅ `types.ts:1303` | ⚠️ | 2 | **存疑** | 引擎钩子 `AfterResponseHook`（`HookSystem.java:66`）**未桥接** |
| 18 | `before_agent_start` | ✅ `types.ts:1304` | ❌ | **3** | **缺失** | pi 可**改 systemPrompt**（`BeforeAgentStartEventResult`）；java `BeforeRunHook`（`HookSystem.java:41`）是 run 起点、且**不能改提示词** |
| 19 | `agent_start` | ✅ `types.ts:1308` | ❌ | 2 | **缺失** | 无钩子（宿主 `PiLoop` 有 start 帧，但非扩展面） |
| 20 | `agent_end` | ✅ `types.ts:1309` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.AgentEnd`（`AgentSessionEvent.java:27`），非扩展面 |
| 21 | `agent_settled` | ✅ `types.ts:1310` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.AgentSettled`（`:47`），非扩展面 |
| 22 | `ui_prompt_start` | ✅ `types.ts:1311` | ❌ | 1 | **缺失** | 无对应 |
| 23 | `ui_prompt_end` | ✅ `types.ts:1312` | ❌ | 1 | **缺失** | 无对应 |
| 24 | `turn_start` | ✅ `types.ts:1313` | ❌ | 2 | **缺失** | 无对应 |
| 25 | `turn_end` | ✅ `types.ts:1314` | ❌ | 2 | **缺失** | 宿主 `AgentSettled` 是**每次驱动**一条、非每回合 —— 台账 **B49** |
| 26 | `message_start` | ✅ `types.ts:1315` | ❌ | 2 | **缺失** | 无对应 |
| 27 | `message_update` | ✅ `types.ts:1316` | ❌ | **3** | **缺失** | 宿主有 `AgentSessionEvent.MessageUpdate`（`:23`），非扩展面 |
| 28 | `message_end` | ✅ `types.ts:1317` | ❌ | 2 | **缺失** | 宿主有 `UserMessageReceived`（`:26`），非扩展面 |
| 29 | `tool_execution_start` | ✅ `types.ts:1318` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.ToolExecutionStart`（`:117`），非扩展面 |
| 30 | `tool_execution_update` | ✅ `types.ts:1319` | ❌ | 2 | **缺失** | 宿主有（`:121`）但**生产无数据源**（`BashTool` 不调 `onUpdate`）—— 台账 **B46** |
| 31 | `tool_execution_end` | ✅ `types.ts:1320` | ❌ | 2 | **缺失** | 宿主有（`:125`），非扩展面；台账 **B43** |
| 32 | `model_select` | ✅ `types.ts:1321` | ❌ | 2 | **缺失** | 无对应（含 `source: set\|cycle\|restore`） |
| 33 | `thinking_level_select` | ✅ `types.ts:1322` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.ThinkingLevelChanged`（`:74`）但**零生产者** —— 台账 **B35** |
| 34 | `user_bash` | ✅ `types.ts:1325` | ❌ | 1 | **缺失** | java 的 `!`/`!!` 前缀无扩展拦截面（宿主有 `BashExecutionUpdate`，非扩展面） |
| 35 | `input` | ✅ `types.ts:1326` | ❌ | **3** | **缺失** | pi 可 transform/handled 用户输入（`InputEventResult`）；java 无输入处理链 |
| 36 | `tool_call`（可 block + 改参 + terminate） | ✅ `types.ts:1323` | ⚠️ | **3** | **存疑** | 引擎钩子 `BeforeToolHook`（`HookSystem.java:71`；`PiToolRunner.java:158` 调用，可改参/拒绝）**未桥接** |
| 37 | `tool_result`（可覆盖结果） | ✅ `types.ts:1324` | ⚠️ | **3** | **存疑** | 引擎钩子 `AfterToolHook`（`HookSystem.java:76`，字段级 patch）**未桥接** |

**汇总（宽口径，引擎同形钩子记存疑）：对齐 1 / 缺失 28 / 存疑 8 / 合计 37 = 2.7%**；**Σ权重 74 / Σ(w×c) 11.5 / 加权 15.5%**
**汇总（严格口径，未桥接的引擎钩子也算缺失）：对齐 1 / 缺失 36 / 存疑 0 = 2.7%**；**加权 2/74 = 2.7%**
> ⚠️ 权重 3 层里 D 域仍是最大拖累（37 个单元 / Σ权重 74 = 模块最大域）。

### 扩展 UI 注入点（单列）

pi `ExtensionUIContext` 共 **26 个方法**（`types.ts:134-285`，旧基准同为 26 个、范围 `:133-284` ⇒ **本轮无变化**；⚠️ 上一版本文档写「27」是**我方计数错误**，已更正）：`select` / `confirm` / `input` / `notify` / `editor` / `custom` / `onTerminalInput` / `setStatus` / `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` / `setHiddenThinkingLabel` / `setWidget` / `setFooter` / `setHeader` / `setTitle` / `pasteToEditor` / `setEditorText` / `getEditorText` / `addAutocompleteProvider` / `getEditorComponent` / `setEditorComponent` / `getTheme` / `getAllThemes` / `loadTheme` / `setTheme` / `getToolsExpanded` / `setToolsExpanded`。

| 注入面 | pi | java | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|
| 阻塞式对话（select/confirm/input/editor） | ✅ 4 方法 | ⚠️ 1 方法 `request(RpcExtensionUIRequest)` | 2 | **存疑** | `ExtensionUI.java:13`；RPC 线支持 9 个 method 字符串（`RpcExtensionUIRequest.java:13`），但**无任何生产者**（`ui()` 只在 `AgentSession.java:1007` 装配，`RpcMode` 未注入） |
| 通知 / 状态栏 / 标题 | ✅ `notify`/`setStatus`/`setTitle` | ❌ | 2 | **缺失** | 无对应方法 |
| widget（aboveEditor/belowEditor） | ✅ `setWidget` + `WidgetPlacement`（`types.ts:107`） | ❌ | 2 | **缺失** | 无对应 |
| 自定义 footer / header | ✅ `setFooter`/`setHeader` | ❌ | 2 | **缺失** | 无对应 |
| 自定义组件 + overlay + 键盘焦点 | ✅ `custom()`（`types.ts:199`，带 overlay 定位） | ❌ | 2 | **缺失** | 无对应 |
| 终端原始按键拦截 | ✅ `onTerminalInput`（`types.ts:115` `TerminalInputHandler`） | ❌ | 2 | **缺失** | 无对应 |
| 编辑器注入（factory / autocomplete / paste / text） | ✅ 5 方法 | ❌ | 1 | **缺失** | 无对应 |
| 主题读写（get/set/load/all） | ✅ 4 方法 | ❌ | 2 | **缺失** | 无对应 |
| 工具展开态读写 | ✅ `getToolsExpanded`/`setToolsExpanded` | ❌ | 1 | **缺失** | 无对应 |

**扩展 UI：对齐 0 / 缺失 8 / 存疑 1 = 0%**；**Σ权重 16 / Σ(w×c) 1 / 加权 6.3%**

---

## 能力单元清单（其余）

### E1 配置文件 / 清单（5）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|---|
| E1-1 | `settings.json`（全局） | 配置 | `config.ts:529-533` → `~/.pi/agent/settings.json` | `FileSettingsStorage.java:41/52` → `~/.pi-java/agent/settings.json` | 2 | 对齐 | — |
| E1-2 | `settings.json`（项目级） | 配置 | `.pi/settings.json`（`CONFIG_DIR_NAME=".pi"`，`config.ts:504`） | `FileSettingsStorage.java:42` → `<cwd>/.pi-java/settings.json` | 2 | 对齐 | — |
| E1-3 | `auth.json` | 配置 | `auth-storage.ts:497-498`（`~/.pi/agent/auth.json`，带 revision + 文件锁 + stale 检测） | `ai/auth/FileCredentialStore.java`、`OAuthCredentialStore.java` | 2 | **存疑** | — |
| E1-4 | `models.json` | 配置 | `core/models-store.ts`（147 行，revision/reload）+ `model-config.ts`（303 行） | `ai/provider/ModelsJsonProvider.java`、`ModelsJsonSchema.java` | 2 | **存疑** | **F6**（`ModelsJsonProvider` 钉死 baseUrl ⇒ CLI `--base-url` 对它失效） |
| E1-5 | pi manifest（`package.json` 的 `pi` 字段） | 配置 | `core/pi-manifest.ts`（35 行，`extensions/skills/prompts/themes` 四字段） | ❌ 无对应物 | 2 | **缺失** | — |

**E1 汇总：对齐 2 / 缺失 1 / 存疑 2 / 合计 5 = 40.0%**；**Σ权重 10 / Σ(w×c) 6 / 加权 60.0%**

### E2 settings.json 键（51 → **52**）

pi 键全部来自 `core/settings-manager.ts:110-163`（`interface Settings`，旧基准 `:106-157`）；java 来自 `core/Settings.java:23-61`。
**判定为「对齐」需字段存在且有非零消费者**（消费证据由 grep 得出，`SettingsAccessors` 的 getter/setter 不算消费者）。

| # | 键 | pi 行 | java 行 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| E2-1 | `lastChangelogVersion` | `:111` | — | 1 | **缺失** | 无字段（仅 `unknown` 透传） |
| E2-2 | `defaultProvider` | `:112` | `:23` | **3** | 对齐 | `SettingsAccessors.java:28-35` |
| E2-3 | `defaultModel` | `:113` | `:24` | **3** | 对齐 | `SettingsAccessors.java:37-44` |
| E2-4 | `defaultThinkingLevel` | `:114` | `:25` | 2 | 对齐 | `SettingsAccessors.java:46-53` |
| E2-5 | `modelThinkingLevels` | `:115` | — | 2 | **缺失** | 无字段 |
| E2-6 | `transport` | `:116` | `:30` | 2 | 对齐 | 4 处消费 |
| E2-7 | `steeringMode` | `:117` | `:31` | 2 | 对齐 | `SettingsAccessors.java:73-80` |
| E2-8 | `followUpMode` | `:118` | `:32` | 2 | 对齐 | `SettingsAccessors.java:82-89` |
| E2-9 | `theme` | `:119` | `:33` | 2 | **存疑** | java 值是 `.tcss` 路径（`PiTheme.java:97`）；pi 是 JSON 主题名（`theme-selector.ts`） |
| E2-10 | `compaction` | `:120` | `:34` | **3** | **存疑** | java `Compaction` 只有 `enabled/reserveTokens/keepRecentTokens`（`Settings.java:121-125`）；pi 另有 **`modelOverrides`**（`settings-manager.ts:13-17/27`）—— 台账 **A9** |
| E2-11 | `branchSummary` | `:121` | — | 2 | **缺失** | 无字段；分支摘要无实现 —— 台账 **B1** |
| E2-12 | `retry` | `:122` | `:61` | 2 | **存疑** | `ProviderRetry` 子对象保形但不接线（`Settings.java:114-118` 自注）—— 台账 **A7** |
| E2-13 | `hideThinkingBlock` | `:123` | `:35` | 2 | 对齐 | 2 处消费 |
| E2-14 | `showCacheMissNotices` | `:124` | — | 1 | **缺失** | 无字段 |
| E2-15 | `externalEditor` | `:125` | `:36` | 1 | **存疑** | accessor 有（`SettingsAccessors.java:143-150`），2 处消费，TUI 外部编辑器行为未核 |
| E2-16 | `shellPath` | `:126` | `:37` | 2 | 对齐 | 5 处消费 |
| E2-17 | `quietStartup` | `:127` | `:39` | 1 | 对齐 | `SettingsAccessors.java:91-98` |
| E2-18 | `defaultProjectTrust` | `:128` | `:40` | 2 | 对齐 | `SettingsAccessors.java:100-107` |
| E2-19 | `shellCommandPrefix` | `:129` | `:38` | 2 | 对齐 | 4 处消费 |
| E2-20 | `npmCommand` | `:130` | — | 2 | **缺失** | 无字段（无 npm 支持） |
| E2-21 | `collapseChangelog` | `:131` | — | 1 | **缺失** | 无字段 |
| E2-22 | `enableInstallTelemetry` | `:132` | — | 1 | **缺失** | 无字段 |
| E2-23 | `enableAnalytics` | `:133` | — | 1 | **缺失** | 无字段 |
| E2-24 | `trackingId` | `:134` | — | 1 | **缺失** | 无字段 |
| E2-25 | `packages` | `:135` | — | 2 | **缺失** | 无字段（包管理器不持久化到 settings） |
| E2-26 | `extensions` | `:136` | `:41` | 2 | 对齐 | 2 处消费 |
| E2-27 | `skills` | `:137` | `:42` | 2 | 对齐 | 13 处消费 |
| E2-28 | `prompts` | `:138` | `:43` | 2 | 对齐 | 5 处消费 |
| E2-29 | `themes` | `:139` | `:44` | 2 | 对齐 | 4 处消费 |
| E2-30 | `enableSkillCommands` | `:140` | `:45` | 2 | **存疑** | 字段有、1 处消费，但 `/skill:name` 命令族**未实现** |
| E2-31 | `terminal` | `:141` | `:46` | 1 | **存疑** | java `Terminal` 只有 2 键（`Settings.java:128-131`），pi 有 6 键（`:56-64`）；且 `showImages`/`imageWidthCells` **零消费** |
| E2-32 | `images` | `:142` | `:47` | 1 | **存疑** | java `Image` 2 键与 pi 同（`:66-69`），但 `autoResize`/`blockImages` **零消费** |
| E2-33 | `enabledModels` | `:143` | `:48` | 2 | 对齐 | `SettingsAccessors.java:109-120` + `ModelCommands.java:37-57` |
| E2-34 | `defaultTools` | `:144` | — | 2 | **缺失** | 无字段 |
| E2-35 | `doubleEscapeAction` | `:145` | `:49` | 1 | **存疑** | accessor 有（`SettingsAccessors.java:168-175`），2 处消费，行为未核 |
| E2-36 | `treeFilterMode` | `:146` | `:50` | 1 | **存疑** | 同上（`SettingsAccessors.java:160-167`） |
| E2-37 | `thinkingBudgets` | `:147` | — | 1 | **缺失** | 无字段 |
| E2-38 | `editorPaddingX` | `:148` | `:51` | 1 | **存疑** | 字段有，**零消费** |
| E2-39 | `outputPad` | `:149` | `:52` | 1 | **存疑** | 字段有，**零消费** |
| E2-40 | `autocompleteMaxVisible` | `:150` | `:53` | 1 | **存疑** | 字段有，**零消费** |
| E2-41 | `showHardwareCursor` | `:151` | — | 1 | **缺失** | 无字段 |
| E2-42 | `markdown` | `:152` | `:54` | 1 | **存疑** | java `Markdown` 2 键（`Settings.java:140-143`）但 `codeBlockIndent`/`mermaid` **零消费**；pi 有 `MermaidRenderingMode` 三档 |
| E2-43 | `warnings` | `:153` | — | 1 | **缺失** | 无字段 |
| E2-44 | `sessionDir` | `:154` | `:55` | 2 | 对齐 | 7 处消费 |
| E2-45 | `httpProxy` | `:155` | `:57` | 1 | **存疑** | 字段有，**零消费** |
| E2-46 | `httpIdleTimeoutMs` | `:156` | — | 1 | **缺失** | 无字段 |
| E2-52 | `cacheWarming`（**本轮新增**） | `:157` | — | 2 | **缺失** | `CacheWarmingMode = "off" \| "streaming" \| "idle"`（`settings-manager.ts:77`），默认 `"streaming"`，**仅全局**（"global only because each refresh costs money"）；读写器 `:956-962`。pi-java 无缓存预热子系统 |
| E2-47 | `websocketConnectTimeoutMs` | `:158` | — | 1 | **缺失** | 无字段 |
| E2-48 | `tuiMode` | `:159` | `:58` | 2 | 对齐 | `SettingsAccessors.java:122-129` |
| E2-49 | `fullscreenExitOutput` | `:160` | — | 1 | **缺失** | 无字段 |
| E2-50 | `fullscreenScrollbar` | `:161` | — | 1 | **缺失** | 无字段 |
| E2-51 | `fullscreenCopyOnSelect` | `:162` | — | 1 | **缺失** | 无字段 |

**E2 汇总：对齐 18 / 缺失 20 / 存疑 14 / 合计 52 = 34.6%**；**Σ权重 82 / Σ(w×c) 46.5 / 加权 56.7%**

### E3 会话管理行为（23 → **24**）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E3-1 | 新建会话（无参） | `session-manager.ts:926` `newSession` | `SessionPersistence.java:201` `handle.create` | **3** | 对齐 | — |
| E3-2 | 续最近会话（`-c`，项目内） | `main.ts:432` `continueRecent` → `findMostRecentSession`（`session-manager.ts:660`） | `SessionPersistence.java:170` `handle.latest(cwd)` | 2 | 对齐 | A12 已结案 |
| E3-3 | `-r` 交互式会话选择器 | `main.ts:416` `selectSession`（`session-picker.ts` + `session-selector.ts`） | ❌ `SessionPersistence.java:186` → `find(null)` 抛异常 | 2 | **缺失** | — |
| E3-4 | `--session` 精确/前缀查找 | **三级阶梯**（`resolveSessionPath`，`main.ts:251-281`）：① `findLocalSessionByExactId` → `SessionManager.findById(cwd,id,dir)`（`main.ts:259` → `session-manager.ts:1732`，**只读 session header**，本轮新增）→ ② `SessionManager.list(cwd,…)` 项目内前缀（`main.ts:264`）→ ③ **`SessionManager.listAll(dir)` 跨项目**（`main.ts:272`，注释 "Try global search across all projects"，**旧基准已存在**） | `PersistentSessionRepositories.java:52` 走 `all()`（**直接跳到第 ③ 级**，无 ①②） | 2 | **存疑** | **B53**（本轮重新定性） |
| E3-5 | `--session-id` 精确 ID（缺失则建） | `main.ts:436-445` | `SessionPersistence.java:175-185` | 2 | **存疑** | **B53** |
| E3-6 | `--fork` 分叉 | `main.ts:369`（`--fork` + `--session-id` 冲突检测） | `SessionPersistence.java:192-199` | 2 | **存疑** | **B53** |
| E3-7 | `/clone` 原位复制 | `session-manager.ts:1427` `createBranchedSession` | `SessionCommands.java:48` `forkCopy` | 1 | 对齐 | — |
| E3-8 | `/fork` 从历史用户消息分叉 | `user-message-selector.ts` | `SessionCommands.java:33` `forkCopy(name)` | 2 | **存疑** | — |
| E3-9 | 会话列表（**本轮实质加强**） | `listSessionsFromDir(dir, onProgress, signal)`（`session-manager.ts:852`）、`SessionListProgress` 现为 **`(loaded, total, partialSessions?)`**（`:795`）、**`AbortSignal`**、`publishPartial` 渐进发布（`:874-876`）；`listAll(onProgress?, signal?)`（`:1779`） | `PersistentSessionRepositories.java:74` `list(cwd)` **同步全量、无回调、无取消** | 2 | **存疑** | **B55**（本轮重新取证） |
| E3-10 | 导出 JSONL | `session-export.ts` | `PersistentSessionRepositories.java:290` `exportJsonl` | 2 | 对齐 | — |
| E3-11 | 导出 HTML | `core/export-html/` | `export/HtmlExporter.java` + `MarkdownToHtml.java`（489 行） | 2 | 对齐 | — |
| E3-12 | 导入 JSONL | `session-manager.ts`（`/import`） | `PersistentSessionRepositories.java:307` `importJsonl` | 1 | 对齐 | — |
| E3-13 | 会话命名 | `session-manager.ts:1150` `appendSessionInfo` | `SessionCommands.java:22` + `AgentSession.java:416` | 1 | 对齐 | **B32**（get_state.sessionName 恒有默认值 `"session"`） |
| E3-14 | 会话树导航 | `session-manager.ts:1374` `branch` / `:1324` `getTree` | `SessionCommands.java:56` + `RpcDispatcher.java:563` `buildTree` | 1 | 对齐 | — |
| E3-15 | 分支摘要（branch summary） | `core/compaction/branch-summarization.ts:349-353` | ❌ | 2 | **缺失** | **B1** |
| E3-16 | 条目标签（labels） | `session-manager.ts:1237/1246` | `JsonlSessionStorage.java:255/260`、`LaneView.java:61` | 1 | 对齐 | — |
| E3-17 | 自定义条目（custom entry） | `session-manager.ts:1136` `appendCustomEntry` | `Session.java:183`、`LaneView.java:100` | 2 | 对齐 | — |
| E3-18 | bash 执行条目 | `session-manager.ts:1071`（`BashExecutionMessage`） | `AgentSession.java:899-908` + `AgentSessionEvent.BashExecutionUpdate:104` | 1 | 对齐 | — |
| E3-19 | `--no-session` 语义 | `main.ts`（ephemeral） | 非 web 生效（`AgentSessionTest.noSessionDoesNotRegister`）；**web 路径忽略** | 1 | **存疑** | **B54** |
| E3-20 | `--session-dir` | `session-manager.ts:507` `getDefaultSessionDir` | `AgentSession.java:142/174/190` | 1 | 对齐 | — |
| E3-21 | 按 cwd 分目录 | `session-manager.ts:500/507` | `AgentSession.java:142`（`--path--` 编码目录） | 2 | 对齐 | — |
| E3-22 | 崩溃恢复（settle 未结操作） | `session-manager.ts:294` `migrateSessionEntries` 等 | `SessionPersistence.java:83` `settleOpenOperation` | 2 | 对齐 | — |
| E3-23 | 会话元数据变更事件 | `session_info_changed`（`types.ts:1272`） | `AgentSessionEvent.SessionInfoChanged` **零生产者** | 1 | **缺失** | **B30** |
| E3-24 | 提示缓存预热（**本轮新增子系统**） | `core/cache-warmer.ts`（453 行）+ `settings-manager.ts:162` `cacheWarming` + `agent-session.ts` `_cacheWarmer.onAgentSettled/onModeChanged/onWarmed` | ❌ | 2 | **缺失** | — |

**E3 汇总：对齐 14 / 缺失 4 / 存疑 6 / 合计 24 = 58.3%**；**Σ权重 40 / Σ(w×c) 27.5 / 加权 68.8%**

### E4 资源发现（7）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E4-1 | skill 发现（目录 + `SKILL.md` + 忽略文件） | `core/skills.ts:168/409`、`resource-loader.ts` | `skill/SkillDiscovery.java:36/51` + `IgnoreFilter.java`（110 行） | 2 | 对齐 | — |
| E4-2 | skill 注册为 `/skill:<name>` 命令 | `interactive-mode.ts:713-718` | ❌ | 2 | **缺失** | — |
| E4-3 | prompt template 发现 + 参数替换 | `core/prompt-templates.ts:270` | `prompt/PromptTemplates.java:47/186` | 2 | 对齐 | — |
| E4-4 | theme 发现（JSON + schema + 目录扫描） | `theme.ts` 1234 + `theme-json.ts` 146 + `theme-schema.json` 356 + `theme-controller.ts` 172 | ❌ 只有 2 个内建 `.tcss`（`PiTheme.java:23-24`） | 2 | **缺失** | — |
| E4-5 | 上下文文件发现（`AGENTS.md`/`CLAUDE.md`） | `resource-loader.ts:72`（5 个候选名） | ❌ **整块不存在**（`--no-context-files` flag 零消费者） | **3** | **缺失** | — |
| E4-6 | 系统提示词文件发现（system prompt source） | `resource-loader.ts:526` `discoverSystemPromptFile`、`:534` append | ❌ | 2 | **缺失** | — |
| E4-7 | 资源冲突诊断 | `diagnostics.ts`（`ResourceDiagnostic` / `ResourceCollision`） | `skill/ResourceDiagnostic.java`、`PromptTemplates.Diagnostic` | 1 | 对齐 | — |

**E4 汇总：对齐 3 / 缺失 4 / 存疑 0 / 合计 7 = 42.9%**；**Σ权重 14 / Σ(w×c) 5 / 加权 35.7%**

### E5 扩展 API 面（27 → **28**）

pi 面 = `ExtensionAPI`（`core/extensions/types.ts:1261-1531`，旧 `:1253-1510`）；java 面 = `PiExtension` + `ExtensionContext`（`extension/*.java`）。

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E5-1 | `registerTool` | `types.ts:1333` | `ExtensionContext.java:20` `tools()` → `ToolRegistry.register` | **3** | 对齐 | — |
| E5-2 | `registerCommand` | `types.ts:1342` | `ExtensionContext.java:23` `slashCommands()` | 2 | 对齐 | — |
| E5-3 | `registerProvider` | `types.ts:1511-1512` | `ExtensionContext.java:26` `providers()` | 2 | 对齐 | — |
| E5-4 | 注册技能 | （pi 无独立 API，靠 skill 目录） | `ExtensionContext.java:29` `skills()` | 2 | 对齐 | — |
| E5-5 | `unregisterProvider` | `types.ts:1527` | ❌ | 1 | **缺失** | — |
| E5-6 | `registerShortcut`（19 保留键冲突检测） | `types.ts:1345` | ❌ | 2 | **缺失** | — |
| E5-7 | `registerFlag` / `getFlag` | `types.ts:1354` / `:1370` | ❌ | 1 | **缺失** | — |
| E5-8 | `registerMessageRenderer` | `types.ts:1377` | ❌ | 2 | **缺失** | — |
| E5-9 | `registerMarkdownTransformer` | `types.ts:1380` | ❌ | 2 | **缺失** | — |
| E5-10 | `registerEntryRenderer` | `types.ts:1383` | ❌ | 2 | **缺失** | — |
| E5-11 | `sendMessage` | `types.ts:1390`（含 `triggerTurn` / `deliverAs`） | `ExtensionContext.java:52-58` 仅**落盘**；`triggerTurn=true` **抛 UOE**（`DefaultExtensionContext.java:80`） | 2 | **存疑** | — |
| E5-12 | `sendUserMessage` | `types.ts:1400` | ❌ | 2 | **缺失** | — |
| E5-13 | `appendEntry` | `types.ts:1406` | ❌（`appendCustomEntry` 在 agent-core，未暴露给扩展） | 1 | **缺失** | — |
| E5-14 | `setSessionName` / `getSessionName` | `types.ts:1413` / `:1416` | ❌ | 1 | **缺失** | — |
| E5-15 | `setLabel` | `types.ts:1419` | ❌（存储层有，扩展面无） | 1 | **缺失** | — |
| E5-16 | `exec` | `types.ts:1422` | ❌ | 2 | **缺失** | — |
| E5-17 | `getActiveTools` / `getAllTools` / `setActiveTools` | `types.ts:1425` / `:1428` / `:1431` | ❌ | 2 | **缺失** | — |
| E5-18 | `getCommands` | `types.ts:1434` | ❌（`CommandRegistry.names()` 存在但未暴露） | 1 | **缺失** | — |
| E5-19 | `setModel` | `types.ts:1444` | ❌ | 2 | **缺失** | — |
| E5-20 | `getThinkingLevel` / `setThinkingLevel` | `types.ts:1447` / `:1453` | ❌ | 2 | **缺失** | — |
| E5-21 | `events`（扩展间 EventBus） | `types.ts:1530` | ❌（`HarnessEventBus` 是包私有 `final class`，非扩展面） | 1 | **缺失** | — |
| E5-22 | 热重载（`/reload` 重载扩展/skills/prompts/themes） | `agent-session.ts:2955` `reload()`（旧 `:2610-2635`）；`emitSessionShutdownEvent(…, {reason:"reload"})` → `settingsManager.reload()` → `resourceLoader.reload()` → `session_start{reason:"reload"}` | ⚠️ `SettingsCommands.java:53` 只重载 settings | 2 | **存疑** | — |
| E5-23 | 扩展注册 CLI flag | `types.ts:1354` + `args.ts:205-212` | ❌ | 1 | **缺失** | — |
| E5-24 | 扩展 UI 注入（TUI） | `types.ts:134-285`（旧 `:133-289`） | ❌ 只回落 `ExtensionUI.noop()`（`ExtensionUI.java:16`） | 2 | **缺失** | — |
| E5-25 | 扩展 UI 通道（RPC，9 method） | `rpc-types.ts:247-281`（**未变**） | ⚠️ `RpcExtensionUIRequest.java:13` 形有，`ui()` **无生产者**（`RpcMode` 未注入） | 1 | **存疑** | — |
| E5-26 | 扩展示例库（可执行验收规格） | `examples/extensions/` **78 个** + 9 个子目录（15,809 行，旧 79 个 / 15,912 行） | ❌ 只有 5 个测试夹具（`extension/TestExtension.java` 等） | **3** | **缺失** | — |
| E5-27 | 扩展来源追踪（`SourceInfo` / `<inline:name>`） | `types.ts:1637-1639`（旧 `:1547-1557`，`RegisteredTool.sourceInfo`） | ⚠️ `InstalledExtension.java` 有路径，但无 `sourceInfo` 投影 | 1 | **存疑** | — |
| E5-28 | **`on()` 返回退订函数（本轮新增）** | `types.ts:1266-1326` 全部 37 条签名由 `void` 改为 **`() => void`**；实现 `extensions/loader.ts:256-270`（闭包把 handler 从 `extension.handlers` 摘除） | ❌ `PiExtension` 是装配期一次性注册，无退订面 | 2 | **缺失** | — |

**E5 汇总：对齐 4 / 缺失 20 / 存疑 4 / 合计 28 = 14.3%**；**Σ权重 48 / Σ(w×c) 12.0 / 加权 25.0%**

### E6 包管理器（16）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E6-1 | `install` 本地源 | `package-manager.ts:112` `install` | `PackageCommand.java:44` | 2 | **存疑** | — |
| E6-2 | `install` http(s) URL | `package-manager.ts`（`ParsedSource`） | `ExtensionPackageManager.java:17`（JDK `HttpClient` 下载） | 2 | **存疑** | — |
| E6-3 | npm 源（版本/range/pinning） | `package-manager.ts:139-147` `NpmSource` | ❌ | **3** | **缺失** | — |
| E6-4 | git 源 | `package-manager.ts` `GitSource` | ❌ | 2 | **缺失** | — |
| E6-5 | 作用域 user/project/temporary | `package-manager.ts:135` `SourceScope` | ⚠️ 只有 global/project（`ExtensionPackageManager.java:36/43`） | 2 | **存疑** | — |
| E6-6 | 资源过滤（`autoload`/`extensions`/`skills`/`prompts`/`themes`） | `package-manager.ts:187-194` `PackageFilter` | ❌ | 2 | **缺失** | — |
| E6-7 | 资源优先级（`resourcePrecedenceRank` 5 档） | `package-manager.ts:171-184` | ❌ | 2 | **缺失** | — |
| E6-8 | 忽略文件（`.gitignore`/`.ignore`/`.fdignore`） | `package-manager.ts:206` `IGNORE_FILE_NAMES` | ❌ | 1 | **缺失** | — |
| E6-9 | 进度回调 `onProgress` | `package-manager.ts:96` `ProgressCallback` | ❌ | 1 | **缺失** | — |
| E6-10 | `update self` / `update pi`（自更新） | `package-manager-cli.ts:1006-1030` + `utils/windows-self-update.ts` | ❌ 只打印说明（`PackageCommand.java:20`） | 2 | **缺失** | — |
| E6-11 | `update --extensions` | `package-manager-cli.ts:1015` | ❌ | 2 | **缺失** | — |
| E6-12 | `update --models`（模型目录刷新） | `package-manager-cli.ts` + `core/remote-catalog-provider.ts` | ❌ | 1 | **缺失** | — |
| E6-13 | `config` TUI（Tab 切作用域、逐资源开关） | `package-manager-cli.ts:791` + `config-selector.ts` 942 行 | ⚠️ 非交互 `enable/disable`（`ConfigCommand.java:12`） | 2 | **存疑** | — |
| E6-14 | 持久化到 `settings.packages` | `package-manager.ts:112` `installAndPersist` | ❌ 无 `packages` 键（`Settings.java` 无该字段） | **3** | **缺失** | — |
| E6-15 | pi manifest 读取 | `core/pi-manifest.ts` | ❌ | 2 | **缺失** | — |
| E6-16 | `remove` / `uninstall` | `package-manager.ts:118` `removeAndPersist` | ⚠️ `PackageCommand.java:55` | 2 | **存疑** | — |

**E6 汇总：对齐 0 / 缺失 11 / 存疑 5 / 合计 16 = 0%**；**Σ权重 31 / Σ(w×c) 5 / 加权 16.1%**

### E7 RPC 面（5）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E7-1 | RPC 命令集（pi 33 / java 32） | `rpc-types.ts` 33 个 `type` 值 | `RpcCommand.java:23-55` 32 个 `@JsonSubTypes` | **3** | **存疑** | 缺 `clear_queue`（pi `rpc-mode.ts:433`；java 全仓 grep 零命中） |
| E7-2 | `extension_ui_request/response` 双向通道 | `rpc-types.ts:247-291`（9 method） | `RpcDispatcher.java:296` `extensionUiRequest` + `:58` `uiQueue` | 2 | **存疑** | 形有；**`ui()` 无生产者**（`AgentSession.java:1007` 传 noop） |
| E7-3 | `get_state` 状态字段 | `rpc-mode.ts:450` | `RpcDispatcher.java:344` `buildState` | 2 | **存疑** | **B31**（model 是字符串非对象）/ **B32**（sessionName 恒有默认值）/ **B33**（messageCount 取转录条数） |
| E7-4 | RPC 事件线格式 | `modes/json-event.ts` | `mode/JsonEventMapper.java` | **3** | **存疑** | **B27/B28/B29**（可空键/usage/toolcall_start 字段） |
| E7-5 | RPC 命令线的 `Instant` 序列化 | pi 无此问题 | `rpc/JsonlWriter.java` | 1 | 对齐 | **B52 已修**（`a997cb5`，登记即修） |

**E7 汇总：对齐 1 / 缺失 0 / 存疑 4 / 合计 5 = 20%**；**Σ权重 11 / Σ(w×c) 6 / 加权 54.5%**

### E8 本轮新增子系统（pi 前进 111 提交带来的新能力单元）

这些是旧基准**不存在**的 pi 侧能力（新文件），pi-java 全部为 **缺失**。

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E8-1 | 提示缓存预热（prompt cache warming） | `core/cache-warmer.ts` **453 行**（新文件，`c596d09d9` #9668）+ `cache-stats.ts` +12 + `CacheWarmingMode` 三档（`off/streaming/idle`） | ❌ | 2 | **缺失** | — （已计入 E3-24；此处为子系统视图，**不计入模块合计**，见脚注） |
| E8-2 | bug 上报（`/bug` + bundle 生成 + 上传 + 脱敏） | `core/bug-report.ts` **375** + `core/bug-report-upload.ts` **37** + `modes/interactive/bug-report.ts` **294** = **706 行**（`d875512cc` / `d1230ea20`）；含 `redactUrl`/`redactJsonValue` 脱敏、`BUG_REPORT_CUSTOM_ENTRY_TYPE = "pi.bug-report"` | ❌ | 1 | **缺失** | — （slash 面已计入 C-25） |
| E8-3 | 崩溃日志（crash log） | `core/crash-log.ts` **92 行**（新文件） | ❌ | 1 | **缺失** | — |
| E8-4 | 扩展加载器依赖延迟（jiti lazy / Bun·SEA 保留 static） | `core/extensions/jiti-loader.ts` 3 + `jiti-static-loader.ts` 3 + `virtual-modules.ts` **38**（`40c256ccc`，closes #9540）；`loader.ts` 净 −53 行 | ❌ java 用 `URLClassLoader`，无此问题域 | 1 | **缺失**（不适用） | — |
| E8-5 | `experimental/micro` 微型运行时 | `src/experimental/micro/*`（api/main/models/runtime/sessions/tools/tui，**1,485 行**） | ❌ | 1 | **缺失** | — |
| E8-6 | WSL / zip 工具 | `utils/wsl.ts` 15 + `utils/zip.ts` 72 | ❌ | 1 | **缺失** | — |
| E8-7 | 系统提示词分节构建与差分 | `core/system-prompt.ts` **+282 行**（`buildSystemPromptSections` / `diffSystemPromptSections` / `normalizeBuildSystemPromptOptions`） | ❌ java 无分节/差分构造 | 2 | **缺失** | — |

**E8 汇总：对齐 0 / 缺失 7 / 存疑 0 / 合计 7 = 0%**；**Σ权重 9 / Σ(w×c) 0 / 加权 0%**
> ⚠️ **E8 与 E3/C 有重叠**（E8-1 ↔ E3-24、E8-2 ↔ C-25）⇒ 与 F 域同处理：**不计入模块合计**，另给含 E8 的口径见 §加权汇总 脚注。

---

## 整块缺失

| 子系统 | pi LOC | 权重 | 说明 |
|---|---:|---:|---|
| **扩展示例库 `examples/`（可执行验收规格）** | **15,809**（旧 15,912） | **3** | 101 个 `.ts`，**78 个扩展示例**（旧 79；`hello.ts`、`plan-mode/`、`subagent/`、`permission-gate.ts`、`snake.ts`、`space-invaders.ts`…）+ 9 个子目录（`custom-provider-anthropic/`、`gondolin/`、`sandbox/`、`with-deps/`）+ `sdk/` + `rpc-extension-ui.ts`。**pi-java 零对应物**（只有 5 个测试夹具）。这些示例同时是 pi 扩展 API 的**行为规格** ⇒ 缺失它们等于缺失「扩展 API 该长什么样」的可执行定义 |
| **上下文文件发现（AGENTS.md / CLAUDE.md）** | ~120（`resource-loader.ts:119-158` `loadProjectContextFiles`，**未变**） | **3** | pi 按 5 个候选名（`AGENTS.override.md`/`AGENTS.md`/`AGENTS.MD`/`CLAUDE.md`/`CLAUDE.MD`，`resource-loader.ts:72`）从 cwd 向上发现并注入系统提示词。pi-java **整块不存在**：`--no-context-files` flag 被解析但零消费者，全仓 grep `"AGENTS` 零命中 |
| **JSON 主题系统** | **1,552**（**未变**） | 2 | `theme.ts` 1234 + `theme-schema.json` 356 + `theme-controller.ts` 172 + `theme-json.ts` 146；含 `/theme` 选择器、`getAllThemes`/`loadTheme`/`setTheme` 扩展 API、文件 watch 热重载。pi-java 只有 113 行 `PiTheme.java` + 2 个内建 `.tcss` |
| **npm / git 包源 + 资源优先级 + 忽略文件** | ~2,200（`package-manager.ts:139-215`，**未变**） | **3** | pi 的包管理器是完整的包生态层；pi-java 是 200 行的「下 JAR 到目录」。⚠️ 本轮 `package-manager.ts` / `package-manager-cli.ts` **零改动** ⇒ 这一差距**没有随 pi 前进而扩大** |
| **自更新（self-update）** | ~300（**未变**） | 1 | pi `update self` 换二进制；pi-java 只打印「走 Maven Central」 |
| **TUI 扩展 UI 注入层** | ~700（**未变**） | 2 | widget / overlay / 自定义 footer·header / 按键拦截 / 编辑器注入全部缺失 |
| **交互式会话选择器（`-r`）** | ~1,200（`session-selector.ts` + `session-selector-search.ts`；本轮 `session-picker.ts` 改为 `(onProgress, signal)` 回调） | 2 | pi-java 无 `-r` 无参路径（抛异常） |

**整块缺失汇总：对齐 0 / 缺失 7 / 存疑 0 / 合计 7 = 0%**；**Σ权重 16 / Σ(w×c) 0 / 加权 0%**
> ⚠️ 本表与 E4/E6 有重叠（「上下文文件发现」↔ E4-5、「JSON 主题系统」↔ E4-4、「npm/git 包源…」↔ E6-3/4/6/7/8）。**模块合计按含重叠计**，另给去重口径见 §加权汇总 脚注。

---

## 台账纠错

逐条核对了 `docs/32-open-items-register.md` 中属于本模块的条目。

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **B53** | 「`find()` 与 `latest()` 同病：`PersistentSessionRepositories.find()`（`:52`）也走 `all()`」＋「pi 的对应物 `findLocalSessionByExactId(id, cwd, sessionDir)` 是**项目内**」 | ⚠️ **后半句本轮被证伪**。`PersistentSessionRepositories.find()` 在 `:52` 走 `all()` 属实；但 pi 的 `resolveSessionPath`（`main.ts:251-281`）是**三级阶梯**：① `findLocalSessionByExactId` → `SessionManager.findById` 只读 header（`main.ts:259` → `session-manager.ts:1732`，**本轮新增**）→ ② 项目内前缀（`main.ts:264`）→ ③ **`SessionManager.listAll(dir)` 跨项目**（`main.ts:272`，**旧基准就已存在**）。⇒ **pi 自己就有跨项目查找**，pi-java 的「越界」不是「pi 没有的形状」，而是**直接跳到第 ③ 级、缺 ①②**。真差距是**次序与成本**（header-only 精确匹配优先），不是「跨项目＝bug」 | `PersistentSessionRepositories.java:52-61`；`main.ts:251-281`、`session-manager.ts:1732-1750` |
| **B54** | 「`--no-session` 在 web 路径被完全忽略：`resolvePersistentWeb(session, args)` 的 `args` 形参一句话都没用」 | ✅ **属实**（java 侧未变）。`resolvePersistentWeb` 在 `:149`，函数体（`:149-164`）确实一次都没引用 `args` | `SessionPersistence.java:149-164` |
| **B55** | 「pi 的会话列表 API 带 `onProgress`（`session-manager.ts:812`、`:772`）」 | ✅ **属实，且本轮加强**。`SessionListProgress` 现为 **`(loaded, total, partialSessions?)`**（`:795`，旧 `:768` 只有 2 参）；`listSessionsFromDir(dir, onProgress, signal)` 在 **`:852`**（旧 `:812`）；新增 **`AbortSignal`**（`:795`/`:816`/`:838`/`:855`）、**`publishPartial` 渐进发布**（`:874-876`：首条 / 每 `CURRENT_SESSION_LIST_PUBLISH_INTERVAL` / 收尾各发一次）、`listAll(onProgress?, signal?)`（`:1779`）、选择器回调改为 `(onProgress, signal)`（`main.ts:416-418`）。⇒ pi-java 的同步全量 `list` 差距**比上一版记录更大**（多了取消 + 渐进两维） | `session-manager.ts:795-800/852-884/1779`；`main.ts:416-418` |
| **B30** | 「`session_info_changed` 在 pi-java 没有生产者」 | ✅ **属实**。`AgentSessionEvent.SessionInfoChanged`（`AgentSessionEvent.java:71`）在 `EventHub` 的发射点清单里不存在 | `AgentSessionEvent.java:71`；`SessionEventHub.java` 无对应 emit |
| **B35** | 「`thinking_level_changed` 同样没有生产者」 | ✅ **属实**。`AgentSessionEvent.ThinkingLevelChanged`（`:74`）同样零 emit | 同上 |
| **B1** | 「branch summary 无实现」 | ✅ **属实**。`Settings.java` 无 `branchSummary` 字段，全模块无分支摘要代码 | `Settings.java:23-61`（51 个 pi 键里无此键） |
| **B32** | 「`get_state.sessionName` 恒有值（默认 `"session"`），pi 是可选」 | ✅ **属实**。`SessionPersistence.java:246-252` `sessionNameOf` 兜底 `"session"`；`AgentSession.java:416` 同样兜底 | `SessionPersistence.java:246-252` |
| **F3** | 「`_emitSessionCompactFailed`（扩展层事件）」 | ✅ **属实**（本模块无该事件的任何投影面）。⚠️ 补充：pi 侧该发射**旧基准就已存在**，本轮 `de2de549b` 改的是它的 **`aborted` 判定来源**（字符串匹配 → 信号权威，`agent-session.ts:2223`）⇒ F3 的缺口在 pi-java 侧**不变**，但「pi 侧形状」的基准要按信号版对齐 | `AgentSessionEvent.java:79` `CompactionEnd` 有 `aborted`/`errorMessage` 字段，但无独立 `compact_failed` 事件、更无扩展面 |
| **F6** | 「`ModelsJsonProvider` 钉死 baseUrl ⇒ CLI `--base-url` 对它失效」 | ⚠️ **本模块范围外**（实现在 `pi-java-ai`），但**症状可在本模块观察到**：`Args.baseUrl`（`Args.java:20`）在 coding-agent 侧有解析、`ArgsParser.java:36` 有 flag，`Main.java` 不消费 —— 由 `DefaultProviders.java` 转交 ai 层 | `Args.java:20`；`ArgsParser.java:36` |
| **A4** | 「运行中手动 `/compact` 有没有门 —— 先取证 pi」 | ✅ **本轮取证完毕（`de2de549b`「close compaction cancellation races」，改 `agent-session.ts` 156 行）**。pi 的语义**不是「门」而是「abort-first」**：`compact()`（`agent-session.ts:2089`）第一句就是 **`await this.abort()`**（`:2090`，**旧基准 `:1968` 已有**），随后自建 `_compactionAbortController`（`:2091`）；`abortCompaction()`（`:2250`）同时 abort 手动与自动两个 controller。**本轮新增的取消语义**：① abort 信号**打进鉴权取件**（`_getSummarizationRequestAuth(model, signal)`，`:2099`，旧版不带 signal ⇒ 取消后仍可能挂在鉴权上）；② `session_before_compact` 事件**新增 `signal` 字段**（`:2130`）；③ `aborted` 的判定由**字符串匹配**（`message === "Compaction cancelled" \|\| error.name === "AbortError"`）改为**信号权威**（`signal.aborted \|\| cancelledByExtension`，`:2223`）—— 这才是那条 race 的实质；④ `_clearManualCompactionState()` 在 `compaction_end` **之前**调用（`:2214`，注释「compaction_end listeners may submit queued prompts, so expose idle state before notifying them」）。<br>**pi-java 真缺什么**：`MiscCommands.java:124` → `AgentSession.compact:641` → `harness.compact(laneName, settings)` —— **既无 abort-first、也无 `abortCompaction`、更无取消语义**（无法中止进行中的压缩，也不先中止在飞的 run） | pi `agent-session.ts:2089-2091/2099/2130/2214/2223/2250-2253`；java `MiscCommands.java:124-131`、`AgentSession.java:641-643`、与 `docs/31 §8.25.5-6` 一致（`RunLifecycle.compact:237-239` 无门） |
| **A5** | 「并发 prompt 有没有门」 | ⚠️ **本模块侧**：`SessionCommands`/`PrintMode` 无会话级串行保证；与台账一致 | `SessionCommands.java`、`modes/PrintMode.java` |
| **A9** | 「per-model 压缩设置（pi `getCompactionSettings(model)`）」 | ✅ **属实**。java `Settings.Compaction`（`Settings.java:121-125`）只有 3 键、**无 `modelOverrides`**；`SettingsAccessors` 无模型参数 | `Settings.java:121-125`；pi `settings-manager.ts:13-17/27` |
| **B46** | 「pi-java 的 `BashTool` 从不调 `onUpdate` ⇒ `tool_execution_update` 在生产上永不发射」 | ⚠️ **需修正范围**：`tool_execution_update` 在**会话层**有出口（`AgentSessionEvent.ToolExecutionUpdate:121` + `JsonEventMapper`），B46 说的是**工具层不产**该事件 —— 两侧都成立，但「永不发射」应限定为「**该事件无生产数据源**」而非「无投影面」 | `AgentSessionEvent.java:121`；`BashTool`（`pi-java-agent-core/.../tools/`）无 `onUpdate` 调用 |

**未发现反向误报**（即台账记为「缺」而实际已有）。上表 3 处为「范围/行号/定性」级修正（**B53 后半句证伪**、B55 行号 + 加强、B46 措辞、F6 归属），非「缺↔有」反转。

---

## 基准漂移（旧 `71dca871b` → 新 `3390bd936`）

**结论（逐条实测）**：本文件共有 **180 条去重后的 pi 侧 `file:line` 引用**（其中 `core/extensions/types.ts` **65 条**、其余文件 **115 条**）。
- **未漂移 104 条**（58%）：`cli/args.ts` 全部 42 条、`package-manager.ts` 9 条、`package-manager-cli.ts` 6 条、`pi-manifest.ts`、`rpc-types.ts`、`rpc-mode.ts`、`resource-loader.ts`、`skills.ts`、`prompt-templates.ts`、`interactive-mode.ts`、`auth-*.ts`、`list-models.ts`、`config.ts`、`branch-summarization.ts`、`session-manager.ts` 的 10 处、`types.ts:115`。
- **行号漂移 76 条**（42%）：其中 **`types.ts` 的 64 条全是整体平移**（`on()` 块 +1 事件后下移 9 行，API 成员块下移 22–26 行，`SourceInfo` 下移 90 行）；**其余 12 条**逐条见下表。
- **实质变化 6 条**（含在漂移里，必须改判定而非只改行号）：`types.ts` 的 37 条签名返回类型、`SessionListProgress` 三参、`listSessionsFromDir` +`signal`、`listAll` +`signal`、`findLocalSessionByExactId` 改同步、`compact()` 取消语义。
- **pi 删除的引用：0 条** ⇒ 无判定因「pi 已删除 ⇒ 作废」而翻转。

| 文件 | 旧行号 | 新行号 | 性质 |
|---|---|---|---|
| `cli/args.ts`（全部 42 条） | `:82-226` 各条 | **不变** | 该文件仅帮助文本 +1 行，`parseArgs` 分支零变动 |
| `core/slash-commands.ts` | `:20-42` | **`:20-43`** | 实质：插入 `/bug`（`:28`） |
| `core/settings-manager.ts` | `interface Settings` `:106-157` | **`:110-163`** | 实质：+`cacheWarming`（`:162`） |
| `core/extensions/types.ts` | `on()` `:1257-1301` | **`:1266-1326`** | 实质：+1 事件（`cache_warming_decision`）；**37 条签名返回类型 `void`→`() => void`** |
| `core/extensions/types.ts` | `ExtensionAPI` `:1253-1510` | **`:1261-1531`** | 行号平移 |
| `core/extensions/types.ts` | `ExtensionUIContext` `:133-289` | **`:134-285`** | 行号平移（方法集**未变**，26 个） |
| `core/extensions/types.ts` | `WidgetPlacement` `:106` | **`:107`** | 平移 |
| `core/extensions/types.ts` | `custom()` `:197-213` | **`:199`** | 平移 |
| `core/extensions/types.ts` | API 成员 26 处（`registerTool:1311` … `events:1505`） | **`:1333` … `:1530`**（逐条见 E5 表） | 平移 |
| `core/extensions/types.ts` | `SourceInfo` `:1547-1557` | **`:1637-1639`** | 平移 |
| `core/session-manager.ts` | `SessionListProgress` `:768` | **`:795`** | **实质**：2 参 → 3 参（+`partialSessions`） |
| `core/session-manager.ts` | `listSessionsFromDir` `:812` | **`:852`** | **实质**：+`signal: AbortSignal` |
| `core/session-manager.ts` | `getDefaultSessionDir` `:483` | **`:507`** | 平移（另新增 `getDefaultSessionDirPath:500`） |
| `core/session-manager.ts` | `findMostRecentSession` `:636` | **`:660`** | 平移 |
| `core/session-manager.ts` | `listAll` `:1685` | **`:1779`** | **实质**：+`signal` |
| `core/session-manager.ts` | 其余 10 处（`:294/926/1071/1136/1150/1324/1374/1427/1237/1246`） | **不变** | — |
| `main.ts` | `findLocalSessionByExactId` `:242` | **`:242`** | **实质**：`async`→同步，内部改为 `SessionManager.findById`（header-only） |
| `main.ts` | `selectSession`（旧 `:415`） | **`:416`** | 平移（且回调签名 +`signal`） |
| `main.ts` | `continueRecent` `:428` | **`:432`** | 平移 |
| `main.ts` | `existingSession` `:432-441` | **`:436-445`** | 平移 |
| `core/agent-session.ts` | `compact()` `:1967` | **`:2089`** | **实质**：取消语义重写（A4） |
| `core/agent-session.ts` | `abortCompaction()` `:2121` | **`:2250`** | 平移 |
| `core/agent-session.ts` | `reload()` `:2610-2635` | **`:2955`** | 平移 |
| `modes/interactive/session-share.ts` | `tryShareViaRadius` `:57` | **`:69`（调用）/ `:98`（定义）** | 平移 |
| `core/compaction/branch-summarization.ts` | `:349-353` | **不变** | 内容微调（+`normalizeContext` 包裹），同区域 |
| `cli/args.ts` | `--export` 2 参数示例 `:427` | **`:385`** | 平移 |
| `core/resource-loader.ts` | `:72` / `:119-158` / `:526` | **不变** | — |
| `core/skills.ts` | `:168` / `:409` | **不变** | — |
| `core/prompt-templates.ts` | `:270` | **不变** | — |
| `modes/interactive/interactive-mode.ts` | `:713-718` | **不变** | — |
| `modes/rpc/rpc-mode.ts` | `:433` / `:450` | **不变** | — |
| `modes/rpc/rpc-types.ts` | `:247-281` / `:247-291` | **不变** | — |
| `core/package-manager.ts` | 9 处（`:96/112/118/135/139-147/139-215/171-184/187-194/206`） | **不变** | 该文件**零改动** |
| `package-manager-cli.ts` | 6 处（`:791/970/1006/1015/1006-1030/1006-1100`） | **不变** | 该文件**零改动** |
| `core/pi-manifest.ts` | — | **不变** | 该文件**零改动** |
| `core/auth-storage.ts` | `:497-498` | **不变** | — |
| `cli/auth-command.ts` | `:52-61` | **不变** | — |
| `cli/list-models.ts` | `:29` | **不变** | — |
| `config.ts` | `:504` / `:529-533` | **不变** | — |
| `core/extensions/types.ts` | `:106` / `:115` | `:107` / `:115` | 平移 / 不变 |

---

## 未加权汇总

| 分区 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| A. CLI 参数 | 29 | 6 | 8 | 43 | 67.4% |
| B. 子命令 | 2 | 1 | 4 | 7 | 28.6% |
| C. Slash 命令 | 11 | 3 | 11 | 25 | 44.0% |
| D. 扩展系统 37 事件 | 1 | 28 | 8 | 37 | 2.7% |
| D′. 扩展 UI 注入面 | 0 | 8 | 1 | 9 | 0% |
| E. 其余能力单元 | 42 | 60 | 35 | 137 | 30.7% |
| ├ E1 配置文件 | 2 | 1 | 2 | 5 | 40.0% |
| ├ E2 settings 键 | 18 | 20 | 14 | 52 | 34.6% |
| ├ E3 会话管理 | 14 | 4 | 6 | 24 | 58.3% |
| ├ E4 资源发现 | 3 | 4 | 0 | 7 | 42.9% |
| ├ E5 扩展 API | 4 | 20 | 4 | 28 | 14.3% |
| ├ E6 包管理器 | 0 | 11 | 5 | 16 | 0% |
| └ E7 RPC 面 | 1 | 0 | 4 | 5 | 20.0% |
| F. 整块缺失（子系统） | 0 | 7 | 0 | 7 | 0% |
| **合计（模块，不含 E8）** | **85** | **113** | **67** | **265** | **32.1%** |
| E8. 本轮新增子系统（**与 E3/C 重叠，不计入**） | 0 | 7 | 0 | 7 | 0% |

**严格口径**（把「引擎钩子存在但未桥接到扩展」的 8 条也算缺失）：对齐 **85** / 缺失 **121** / 存疑 **59** / 合计 **265** = **32.1%**。

**扩展系统单独口径**（D + D′ + E5）：37 事件 = 1 对齐；UI 注入 9 面 = 0 对齐；扩展 API 28 项 = 4 对齐 ⇒ 扩展系统 **74 个单元中 5 个对齐 = 6.8%**。

**java 独有（不计入分母）**：`--base-url`、`--port`、`--debug`、`--trace-payloads`（CLI）；`/help`、`/create-skill`（slash）；`auth oauth-login`、`auth profile set|unset|list|set-key`（子命令）；`ExtensionContext.settings()`（pi 扩展 API 无设置读写，`docs/24 §2` 已记）。

**最大三处差距**：① 扩展事件面 37→0（可订阅，且本轮 pi 又加了退订与 `cache_warming_decision`）；② 包管理器 4,236→496 行且**零对齐单元**；③ 上下文文件发现 + JSON 主题系统 + 可执行验收规格（15,809 行）**整块不存在**。
**本轮新增差距**：④ pi 前进 111 提交带来 **7 个新子系统（2,867 行）**，pi-java 全部为 0 —— 缓存预热、`/bug` 上报、崩溃日志、系统提示词分节、`experimental/micro`。

---

## 加权汇总

> 口径：权重 = 用户可观察影响 × 频率（**不含排期优先级**）；`3` = 每轮对话都走 / 默认路径，`2` = 每次会话走 / 常用命令·参数·配置键，`1` = 低频·边缘·纯内部，`0` = 非目标（本模块无 0 权重单元）。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A. CLI 参数 | 62 | 47.5 | 76.6% | 67.4% |
| B. 子命令 | 13 | 7.0 | 53.8% | 28.6% |
| C. Slash 命令 | 38 | 25.0 | 65.8% | 44.0% |
| D. 扩展系统 37 事件 | 74 | 11.5 | 15.5% | 2.7% |
| D′. 扩展 UI 注入面 | 16 | 1.0 | 6.3% | 0% |
| E1. 配置文件 / 清单 | 10 | 6.0 | 60.0% | 40.0% |
| E2. settings.json 键 | 82 | 46.5 | 56.7% | 34.6% |
| E3. 会话管理行为 | 40 | 27.5 | 68.8% | 58.3% |
| E4. 资源发现 | 14 | 5.0 | 35.7% | 42.9% |
| E5. 扩展 API 面 | 48 | 12.0 | 25.0% | 14.3% |
| E6. 包管理器 | 31 | 5.0 | 16.1% | 0% |
| E7. RPC 面 | 11 | 6.0 | 54.5% | 20.0% |
| F. 整块缺失（子系统） | 16 | 0.0 | 0% | 0% |
| **模块合计（不含 E8）** | **455** | **200.0** | **44.0%** | **32.1%** |
| E8. 本轮新增子系统（与 E3/C 重叠，不计入） | 9 | 0.0 | 0% | 0% |

**模块合计**：Σ权重 455 ／ Σ(w×c) 200.0 ／ **加权完成度 = 200/455 = 44.0%**（未加权 85/265 = 32.1%）
> 旧基准对照：Σ权重 446 ／ Σ(w×c) 200.0 ／ 44.8% ⇒ **pi 前进 111 提交后加权完成度 −0.8 pp**（分母 +9，分子 +0：新增的 9 个单元权重全部落在「缺失」）。
> 若把 E8 计入：Σ权重 **464** ／ Σ(w×c) 200.0 ／ **43.1%**（未加权 85/272 = 31.3%）。

**去重口径**（F 域有 3 行与 E4/E6 重叠：上下文文件发现 ↔ E4-5、JSON 主题系统 ↔ E4-4、npm/git 包源… ↔ E6-3/4/6/7/8）：
剔除 F 域 ⇒ Σ权重 **439** ／ Σ(w×c) **200.0** ／ **加权完成度 = 200/439 = 45.6%**（未加权 85/258 = 32.9%）。

**扩展系统单独口径**（D + D′ + E5 三域合并）：Σ权重 138 ／ Σ(w×c) 24.5 ／ **加权 17.8%**（未加权 5/74 = 6.8%）。

**权重分层**：

| 权重层 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|---:|
| 权重 3（每轮 / 默认路径） | 23 | 69 | 28.5 | **41.3%** | 6/23 = 26.1% |
| 权重 2（每次会话 / 常用） | 144 | 288 | 125.0 | 43.4% | 45/144 = 31.3% |
| 权重 1（低频 / 边缘 / 内部） | 98 | 98 | 46.5 | 47.4% | 34/98 = 34.7% |
| **合计** | **265** | **455** | **200.0** | **44.0%** | **85/265 = 32.1%** |

> 读数：**权重 3 层的加权完成度（41.3%）仍低于权重 1 层（47.4%）** —— pi-java 在「每轮都走」的地方欠账最重。本轮新增的 5 个单元（`/bug` w1、`cache_warming_decision` w2、`cacheWarming` w2、提示缓存预热 w2、`on()` 退订 w2）**全部落在缺失**，把权重 2 层从 44.6% 拉到 43.4%。

### 权重 3 单元（23 个 —— 最该先修）

| # | 域 | 单元 | 权重 | 判定 |
|---|---|---|---|---|
| 1 | A | `--model` | 3 | 对齐 |
| 2 | A | `--print`/`-p` | 3 | 对齐 |
| 3 | D | `context` | 3 | **存疑** |
| 4 | D | `before_provider_request` | 3 | **存疑** |
| 5 | D | `before_agent_start` | 3 | **缺失** |
| 6 | D | `message_update` | 3 | **缺失** |
| 7 | D | `input` | 3 | **缺失** |
| 8 | D | `tool_call` | 3 | **存疑** |
| 9 | D | `tool_result` | 3 | **存疑** |
| 10 | E2 | `defaultProvider` | 3 | 对齐 |
| 11 | E2 | `defaultModel` | 3 | 对齐 |
| 12 | E2 | `compaction` | 3 | **存疑** |
| 13 | E3 | 新建会话（无参） | 3 | 对齐 |
| 14 | E4 | 上下文文件发现（AGENTS.md/CLAUDE.md） | 3 | **缺失** |
| 15 | E5 | `registerTool` | 3 | 对齐 |
| 16 | E5 | 扩展示例库 | 3 | **缺失** |
| 17 | E6 | npm 源 | 3 | **缺失** |
| 18 | E6 | 持久化到 `settings.packages` | 3 | **缺失** |
| 19 | E7 | RPC 命令集 | 3 | **存疑** |
| 20 | E7 | RPC 事件线格式 | 3 | **存疑** |
| 21 | F | `examples/` 可执行验收规格 | 3 | **缺失** |
| 22 | F | 上下文文件发现（子系统视图） | 3 | **缺失** |
| 23 | F | npm/git 包源 + 优先级 + 忽略文件 | 3 | **缺失** |

> 权重 3 层 23 个单元中：**对齐 6 / 存疑 7 / 缺失 10**（Σ(w×c) 28.5 / 69 = 41.3%）。
> 归属：扩展系统 **8 个**（#3–#9 事件面 + #16 扩展示例库）；包管理器 **3 个**（#17 npm 源 / #18 `settings.packages` / #23 npm·git 包源子系统）；其余 12 个散在 CLI（2）、settings（3）、会话（1）、资源发现（1）、RPC（2）、整块缺失（3，其中 2 个与前者重叠）。
> **本轮无新增权重 3 单元**（新增的 9 个单元权重为 1×1 + 2×4），⇒ 权重 3 层与上一版完全一致。

### 未加权 vs 加权的差

| 指标 | 数值 |
|---|---|
| 未加权完成度 | 32.1%（85/265） |
| 加权完成度（含 F 域） | **44.0%**（200/455） |
| 差 | **+11.9 pp** |
| 加权完成度（去重，剔 F 域） | 45.6%（200/439）＝ +12.7 pp |
| 旧基准同口径（对照） | 44.8%（200/446）⇒ **−0.8 pp** |

**差在哪**：加权把完成度**抬高** 11.9 pp，因为 pi-java 的对齐单元**密集落在高权重的默认路径上**，而缺失质量集中在低权重长尾（settings 冷键、罕见 flag、内部契约形状）。

- **抬高项**：A 域（CLI）未加权 67.4% → 加权 76.6%（对齐的 `--model`/`--print`/`--provider` 都是 3/2 权，缺失的 `--offline`/`--use-theme`/`--` 都是 1 权）；C 域（slash）44.0% → 65.8%（对齐的 `/new` `/compact` `/resume` `/copy` `/quit` 都是 2 权，存疑的 `/tree` `/session` `/changelog` `/share` 都是 1 权，新增的 `/bug` 也是 1 权）；E3（会话）58.3% → 68.8%；B 域 28.6% → 53.8%（4 个 2 权单元只是「存疑」而非缺失，各计 0.5）。
- **压低项**：E4（资源发现）42.9% → **35.7%**（唯一的高权单元 `上下文文件发现` w3 全缺，而对齐的 `资源冲突诊断` 只有 1 权）；E5（扩展 API）14.3% → 25.0% 是抬高，但 D/D′ 两域把它压回去；D 域 2.7% → **15.5%**（8 个未桥接的引擎钩子含 4 个 w3 各计 0.5，把 2.7% 抬到 15.5%，但仍在 41.3% 的权重 3 层里垫底）。
- **注意 E6 是唯一「加权 > 未加权」却几乎没动**的域：未加权 0% → 加权 16.1%，非零全部来自 5 个 2 权存疑单元各计 0.5；**没有任何一个单元是「对齐」**，所以它的加权分完全建立在「形状存在」的半个信用上。
- **本轮新增的 9 个单元权重全部落在缺失**（`/bug` 1 + `cache_warming_decision` 2 + `cacheWarming` 2 + 提示缓存预热 2 + `on()` 退订 2 = 9，其余 E8 项另计 9），所以**加权完成度只降不升** —— 这是「pi 前进、pi-java 静止」在加权口径下的直接读数。

- **两个点名项**：
  - **扩展事件面**：D 域 Σ权重 **74** = 模块 455 的 **16.3%**，是全部 13 个域里**最大的一块**（第二名 E2 的 82 权重含 52 个单元，D 域只用 37 个单元就拿到 74）。Σ(w×c) 仅 11.5 ⇒ 若 D 域全对齐，模块 Σ(w×c) 200 → **262.5**、完成度 44.0% → **57.7%**，即**这一域单独把模块拉低 13.7 pp**。把 8 个未桥接的引擎钩子按严格口径全判缺失（11.5 → 2.0），模块降到 190.5/455 = **41.9%** ⇒ 这 8 个「半信用」本身就值 2.1 pp。
  - **上下文文件发现整块缺失**：作为**单个能力单元**只占 Σ权重 6（E4-5 w3 + F 域子系统视图 w3）= 模块的 **1.3%**；两项全对齐只把完成度从 44.0% 抬到 **45.3%（+1.3 pp）** —— 在单元计数口径下它的杠杆**很小**。⚠️ 这个数字**严重低估了它的实际影响**：它是一条**每轮 prompt 都走的默认路径**（AGENTS.md/CLAUDE.md 注入系统提示词），却只被记为 1–2 个单元。**这是加权口径的结构性盲点：单元粒度封顶了权重上限，而整块缺失的子系统在单元表里天然只占 1 行。** 对照 `--model` 这种「一个 flag 一个单元」的写法，`上下文文件发现` 的真实分量应等价于 10+ 个权重 3 单元（发现顺序、5 个候选名、向上遍历、`override` 语义、注入位置、与 `--no-context-files` 的联动、与 system prompt 源的合并顺序…）。
  - **口径修正建议**：若要更真实，整块缺失的子系统应按「暴露的行为面个数」而不是 1 计入分母（`examples/` 78 个示例、上下文文件 5 候选名 + 遍历语义、npm/git 双源 × 版本/range/pinning）。本表按用户给定规则（1 个子系统 = 1 个单元）计。
  - **本轮新增的口径问题**：E8 的 7 个新子系统（2,867 行）同样是「1 个子系统 = 1 个单元」，权重只拿到 9 —— 与 `上下文文件发现` 同病。若按行为面展开（缓存预热的三档模式 × 触发时机 × 计费口径、`/bug` 的收集/脱敏/上传三段、`micro` 的 7 个模块），E8 的真实权重应在 30+。
