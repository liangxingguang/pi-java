# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 范围：`D:\workplaceForai\pi-java\pi-java-coding-agent` ↔ `D:\workplaceForai\pi\packages\coding-agent`。
> 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差，或台账未结）。
> 所有证据为 `file:line`；pi 侧路径省略 `D:\workplaceForai\pi\packages\coding-agent\` 前缀，java 侧省略 `D:\workplaceForai\pi-java\pi-java-coding-agent\src\main\java\com\pijava\coding\agent\` 前缀。
> 基准：main @ `34849a2`（2026-09-20）。

---

## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| **产品层合计（src/main）** | `packages/coding-agent/src` | **70052** | **10325** | **14.7%** |
| ├ 扩展系统 | `src/core/extensions` (4130) + `src/extensions` (1457) | 5587 | 1516 (`extension/` 717 + `agent-core/hook/` 799) | 27.1% |
| ├ 包管理器 | `core/package-manager.ts` 2699 + `package-manager-cli.ts` 1102 + `core/pi-manifest.ts` 35 + `utils/tools-manager.ts` 400 | 4236 | 496 (`ExtensionPackageManager` 200 + `PackageCommand` 164 + `ConfigCommand` 132) | 11.7% |
| ├ 主题系统 | `modes/interactive/theme/*` | 1552 | 113 (`tui/theme/PiTheme.java`) | 7.3% |
| ├ 会话管理 | `core/session-manager.ts` | 1746 | 771 (`SessionPersistence` 256 + `PersistentSessionRepositories` 397 + `session/*` 118) | 44.2% |
| ├ 资源发现 | `core/skills.ts` 509 + `core/prompt-templates.ts` 285 + `core/resource-loader.ts` 1097 | 1891 | 896 (`skill/` 613 + `prompt/` 283) | 47.4% |
| ├ RPC | `modes/rpc/rpc-client.ts` 609 + `rpc-mode.ts` 821 + `rpc-types.ts` 297 + `jsonl.ts` 58 | 1785 | 1248 (`rpc/*`) | 69.9% |
| ├ CLI/TUI 交互 | `modes/interactive/*` 20662 + `cli/*` 1920 + `utils/*` 3544 | 26126 | ~3000 (`cli/` 612 + `modes/` 192 + `mode/` 376 + 其余宿主) | ~11.5% |
| └ **可执行验收规格 examples/** | `examples/`（102 个 .ts，79 个扩展示例） | **15912** | **0** | **0%** |
| 测试 | `test/` | 57036 | 6844 | 12.0% |

> java 侧 10325 行分布：`core` 5010 / `rpc` 1248 / `extension` 717 / `subcommand` 615 / `skill` 613 / `cli` 612 / `export` 489 / `mode` 376 / `prompt` 283 / `modes` 192 / `spi` 44。
> **扩展系统的 799 行在 `pi-java-agent-core/src/main/java/com/pijava/agent/hook/`**，不在本模块 —— 且**没有桥接到 `PiExtension`**（见 §扩展系统）。

---

## CLI 参数对齐

逐条数：pi `src/cli/args.ts:82-226` 共 **40 个 flag** + `--` + `@files` + 扩展注册 flag = **43 个能力单元**。
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
| 24 | `--export` | ✅ `args.ts:164` | ⚠️ `ArgsParser.java:129` | 1 | **存疑** | pi 支持 **2 个位置参数**（`--export in.jsonl out.html`，`args.ts:427`）；java 只取 1 个（`Main.java:99`），输出路径由 `HtmlExporter` 决定 |
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

pi `core/slash-commands.ts:20-42` 共 **23 条**；java `CommandRegistry.withBuiltins()` 注册 **24 条**（javadoc 写 "22" 是陈旧值）。
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

**汇总：对齐 11 / 缺失 2 / 存疑 11 / 合计 24 = 45.8%**；**Σ权重 37 / Σ(w×c) 25 / 加权 67.6%**

---

## 扩展系统对齐（36 事件）

pi 的扩展事件面 = `ExtensionAPI.on()` 的 **36 个重载**（`core/extensions/types.ts:1257-1301`，`ExtensionEvent` 联合在 `:1086-1113`）。
pi-java 的**扩展 SPI** = `PiExtension.java:11-31`，只有 **2 个钩子**：`register(ExtensionContext)` 与 `sessionStartResources()`。
pi-java 的**引擎钩子** = `pi-java-agent-core/.../agent/hook/HookSystem.java`，**13 个**（`onBeforeRun:41`、`onBeforeResume:46`、`onTransformContext:51`、`onBeforeRequest:56`、`onBeforePayload:61`、`onAfterResponse:66`、`onBeforeTool:71`、`onAfterTool:76`、`onBeforeCompaction:81`、`onBeforeNavigation:86`、`onBeforeRunEnd:91`、`onShouldStopAfterTurn:96`、`onPrepareNextTurn:101`），**但 `ExtensionContext` 不暴露 `hookSystem()`**（`extension/ExtensionContext.java:17-65`，`docs/24 §2` 已记）⇒ **扩展拿不到**。

判定口径：本表按「**扩展能否订阅该事件**」判；引擎侧有同形钩子的记 **存疑** 并在证据列写明「引擎钩子 X（未桥接）」。

| # | 事件/hook | pi 有 | java 有 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | `project_trust` | ✅ `types.ts:1257` | ❌ | 2 | **缺失** | java 有 `TrustManager.java:15`（标记文件），但**无 handler 注册面** |
| 2 | `resources_discover` | ✅ `types.ts:1258` | ✅ `PiExtension.java:29` | 2 | **对齐** | `ExtensionManager.java:81-100` 合并保序 + 异常隔离；`ResourcePaths` 三类路径 |
| 3 | `session_start` | ✅ `types.ts:1259` | ❌ | 2 | **缺失** | 无钩子；`SessionStartEvent` 形状（reason / previousSessionFile）在 java 无对应 |
| 4 | `session_info_changed` | ✅ `types.ts:1260` | ❌ | 1 | **缺失** | 宿主侧有 `AgentSessionEvent.SessionInfoChanged`（`AgentSessionEvent.java:71`），但**零生产者** —— 台账 **B30** |
| 5 | `session_before_switch` | ✅ `types.ts:1261` | ❌ | 2 | **缺失** | 可取消的会话切换钩子不存在 |
| 6 | `session_before_fork` | ✅ `types.ts:1265` | ❌ | 2 | **缺失** | 同上 |
| 7 | `session_before_compact` | ✅ `types.ts:1266` | ⚠️ | 2 | **存疑** | 引擎钩子 `BeforeCompactionHook`（`HookSystem.java:81`，可改 `CompactionPlan`）**未桥接**；扩展不可达 |
| 8 | `session_compact` | ✅ `types.ts:1270` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.CompactionEnd`（`:79`），非扩展面 |
| 9 | `session_compact_failed` | ✅ `types.ts:1271` | ❌ | 1 | **缺失** | 台账 **F3** 单列（`_emitSessionCompactFailed` 扩展层事件） |
| 10 | `session_shutdown` | ✅ `types.ts:1272` | ❌ | 2 | **缺失** | 无 shutdown 钩子；`ExtensionManager.java:76` `unload()` 只从表移除，注释自认「扩展无 close 生命周期」 |
| 11 | `session_before_tree` | ✅ `types.ts:1273` | ⚠️ | 1 | **存疑** | 引擎钩子 `BeforeNavigationHook`（`HookSystem.java:86`，可拒绝导航）**未桥接** |
| 12 | `session_tree` | ✅ `types.ts:1274` | ❌ | 1 | **缺失** | 无导航完成事件 |
| 13 | `context` | ✅ `types.ts:1275` | ⚠️ | **3** | **存疑** | 引擎钩子 `TransformContextHook`（`HookSystem.java:51`；`ContextAssembler.java:64` 调用）**未桥接** |
| 14 | `before_provider_request` | ✅ `types.ts:1276` | ⚠️ | **3** | **存疑** | 引擎钩子 `BeforePayloadHook`（`HookSystem.java:61`，可替换 payload）**未桥接** |
| 15 | `before_provider_headers` | ✅ `types.ts:1280` | ⚠️ | 2 | **存疑** | 引擎钩子 `BeforeRequestHook`（`HookSystem.java:56`）**只读、不能改 header**，且未桥接 ⇒ 语义也不同 |
| 16 | `after_provider_response` | ✅ `types.ts:1281` | ⚠️ | 2 | **存疑** | 引擎钩子 `AfterResponseHook`（`HookSystem.java:66`）**未桥接** |
| 17 | `before_agent_start` | ✅ `types.ts:1282` | ❌ | **3** | **缺失** | pi 可**改 systemPrompt**（`BeforeAgentStartEventResult`）；java `BeforeRunHook`（`HookSystem.java:41`）是 run 起点、且**不能改提示词** |
| 18 | `agent_start` | ✅ `types.ts:1283` | ❌ | 2 | **缺失** | 无钩子（宿主 `PiLoop` 有 start 帧，但非扩展面） |
| 19 | `agent_end` | ✅ `types.ts:1284` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.AgentEnd`（`AgentSessionEvent.java:27`），非扩展面 |
| 20 | `agent_settled` | ✅ `types.ts:1285` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.AgentSettled`（`:47`），非扩展面 |
| 21 | `ui_prompt_start` | ✅ `types.ts:1286` | ❌ | 1 | **缺失** | 无对应 |
| 22 | `ui_prompt_end` | ✅ `types.ts:1287` | ❌ | 1 | **缺失** | 无对应 |
| 23 | `turn_start` | ✅ `types.ts:1288` | ❌ | 2 | **缺失** | 无对应 |
| 24 | `turn_end` | ✅ `types.ts:1289` | ❌ | 2 | **缺失** | 宿主 `AgentSettled` 是**每次驱动**一条、非每回合 —— 台账 **B49** |
| 25 | `message_start` | ✅ `types.ts:1290` | ❌ | 2 | **缺失** | 无对应 |
| 26 | `message_update` | ✅ `types.ts:1291` | ❌ | **3** | **缺失** | 宿主有 `AgentSessionEvent.MessageUpdate`（`:23`），非扩展面 |
| 27 | `message_end` | ✅ `types.ts:1292` | ❌ | 2 | **缺失** | 宿主有 `UserMessageReceived`（`:26`），非扩展面 |
| 28 | `tool_execution_start` | ✅ `types.ts:1293` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.ToolExecutionStart`（`:117`），非扩展面 |
| 29 | `tool_execution_update` | ✅ `types.ts:1294` | ❌ | 2 | **缺失** | 宿主有（`:121`）但**生产无数据源**（`BashTool` 不调 `onUpdate`）—— 台账 **B46** |
| 30 | `tool_execution_end` | ✅ `types.ts:1295` | ❌ | 2 | **缺失** | 宿主有（`:125`），非扩展面；台账 **B43** |
| 31 | `model_select` | ✅ `types.ts:1296` | ❌ | 2 | **缺失** | 无对应（含 `source: set\|cycle\|restore`） |
| 32 | `thinking_level_select` | ✅ `types.ts:1297` | ❌ | 2 | **缺失** | 宿主有 `AgentSessionEvent.ThinkingLevelChanged`（`:74`）但**零生产者** —— 台账 **B35** |
| 33 | `user_bash` | ✅ `types.ts:1300` | ❌ | 1 | **缺失** | java 的 `!`/`!!` 前缀无扩展拦截面（宿主有 `BashExecutionUpdate`，非扩展面） |
| 34 | `input` | ✅ `types.ts:1301` | ❌ | **3** | **缺失** | pi 可 transform/handled 用户输入（`InputEventResult`）；java 无输入处理链 |
| 35 | `tool_call`（可 block + 改参 + terminate） | ✅ `types.ts:1298` | ⚠️ | **3** | **存疑** | 引擎钩子 `BeforeToolHook`（`HookSystem.java:71`；`PiToolRunner.java:158` 调用，可改参/拒绝）**未桥接** |
| 36 | `tool_result`（可覆盖结果） | ✅ `types.ts:1299` | ⚠️ | **3** | **存疑** | 引擎钩子 `AfterToolHook`（`HookSystem.java:76`，字段级 patch）**未桥接** |

**汇总（宽口径，引擎同形钩子记存疑）：对齐 1 / 缺失 27 / 存疑 8 / 合计 36 = 2.8%**；**Σ权重 72 / Σ(w×c) 11.5 / 加权 16.0%**
**汇总（严格口径，未桥接的引擎钩子也算缺失）：对齐 1 / 缺失 35 / 存疑 0 = 2.8%**；**加权 2/72 = 2.8%**

### 扩展 UI 注入点（单列）

pi `ExtensionUIContext` 共 **27 个方法**（`types.ts:133-289`）：`select` / `confirm` / `input` / `notify` / `editor` / `custom` / `onTerminalInput` / `setStatus` / `setWorkingMessage` / `setWorkingVisible` / `setWorkingIndicator` / `setHiddenThinkingLabel` / `setWidget` / `setFooter` / `setHeader` / `setTitle` / `pasteToEditor` / `setEditorText` / `getEditorText` / `addAutocompleteProvider` / `getEditorComponent` / `setEditorComponent` / `getTheme` / `getAllThemes` / `loadTheme` / `setTheme` / `getToolsExpanded` / `setToolsExpanded`（+ `getAllThemes` 等 28 项，去重后 27）。

| 注入面 | pi | java | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|
| 阻塞式对话（select/confirm/input/editor） | ✅ 4 方法 | ⚠️ 1 方法 `request(RpcExtensionUIRequest)` | 2 | **存疑** | `ExtensionUI.java:13`；RPC 线支持 9 个 method 字符串（`RpcExtensionUIRequest.java:13`），但**无任何生产者**（`ui()` 只在 `AgentSession.java:1007` 装配，`RpcMode` 未注入） |
| 通知 / 状态栏 / 标题 | ✅ `notify`/`setStatus`/`setTitle` | ❌ | 2 | **缺失** | 无对应方法 |
| widget（aboveEditor/belowEditor） | ✅ `setWidget` + `WidgetPlacement`（`types.ts:106`） | ❌ | 2 | **缺失** | 无对应 |
| 自定义 footer / header | ✅ `setFooter`/`setHeader` | ❌ | 2 | **缺失** | 无对应 |
| 自定义组件 + overlay + 键盘焦点 | ✅ `custom()`（`types.ts:197-213`，带 overlay 定位） | ❌ | 2 | **缺失** | 无对应 |
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

### E2 settings.json 键（51）

pi 键全部来自 `core/settings-manager.ts:106-157`（`interface Settings`）；java 来自 `core/Settings.java:23-61`。
**判定为「对齐」需字段存在且有非零消费者**（消费证据由 grep 得出，`SettingsAccessors` 的 getter/setter 不算消费者）。

| # | 键 | pi 行 | java 行 | 权重 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| E2-1 | `lastChangelogVersion` | `:107` | — | 1 | **缺失** | 无字段（仅 `unknown` 透传） |
| E2-2 | `defaultProvider` | `:108` | `:23` | **3** | 对齐 | `SettingsAccessors.java:28-35` |
| E2-3 | `defaultModel` | `:109` | `:24` | **3** | 对齐 | `SettingsAccessors.java:37-44` |
| E2-4 | `defaultThinkingLevel` | `:110` | `:25` | 2 | 对齐 | `SettingsAccessors.java:46-53` |
| E2-5 | `modelThinkingLevels` | `:111` | — | 2 | **缺失** | 无字段 |
| E2-6 | `transport` | `:112` | `:30` | 2 | 对齐 | 4 处消费 |
| E2-7 | `steeringMode` | `:113` | `:31` | 2 | 对齐 | `SettingsAccessors.java:73-80` |
| E2-8 | `followUpMode` | `:114` | `:32` | 2 | 对齐 | `SettingsAccessors.java:82-89` |
| E2-9 | `theme` | `:115` | `:33` | 2 | **存疑** | java 值是 `.tcss` 路径（`PiTheme.java:97`）；pi 是 JSON 主题名（`theme-selector.ts`） |
| E2-10 | `compaction` | `:116` | `:34` | **3** | **存疑** | java `Compaction` 只有 `enabled/reserveTokens/keepRecentTokens`（`Settings.java:121-125`）；pi 另有 **`modelOverrides`**（`settings-manager.ts:13-17/27`）—— 台账 **A9** |
| E2-11 | `branchSummary` | `:117` | — | 2 | **缺失** | 无字段；分支摘要无实现 —— 台账 **B1** |
| E2-12 | `retry` | `:118` | `:61` | 2 | **存疑** | `ProviderRetry` 子对象保形但不接线（`Settings.java:114-118` 自注）—— 台账 **A7** |
| E2-13 | `hideThinkingBlock` | `:119` | `:35` | 2 | 对齐 | 2 处消费 |
| E2-14 | `showCacheMissNotices` | `:120` | — | 1 | **缺失** | 无字段 |
| E2-15 | `externalEditor` | `:121` | `:36` | 1 | **存疑** | accessor 有（`SettingsAccessors.java:143-150`），2 处消费，TUI 外部编辑器行为未核 |
| E2-16 | `shellPath` | `:122` | `:37` | 2 | 对齐 | 5 处消费 |
| E2-17 | `quietStartup` | `:123` | `:39` | 1 | 对齐 | `SettingsAccessors.java:91-98` |
| E2-18 | `defaultProjectTrust` | `:124` | `:40` | 2 | 对齐 | `SettingsAccessors.java:100-107` |
| E2-19 | `shellCommandPrefix` | `:125` | `:38` | 2 | 对齐 | 4 处消费 |
| E2-20 | `npmCommand` | `:126` | — | 2 | **缺失** | 无字段（无 npm 支持） |
| E2-21 | `collapseChangelog` | `:127` | — | 1 | **缺失** | 无字段 |
| E2-22 | `enableInstallTelemetry` | `:128` | — | 1 | **缺失** | 无字段 |
| E2-23 | `enableAnalytics` | `:129` | — | 1 | **缺失** | 无字段 |
| E2-24 | `trackingId` | `:130` | — | 1 | **缺失** | 无字段 |
| E2-25 | `packages` | `:131` | — | 2 | **缺失** | 无字段（包管理器不持久化到 settings） |
| E2-26 | `extensions` | `:132` | `:41` | 2 | 对齐 | 2 处消费 |
| E2-27 | `skills` | `:133` | `:42` | 2 | 对齐 | 13 处消费 |
| E2-28 | `prompts` | `:134` | `:43` | 2 | 对齐 | 5 处消费 |
| E2-29 | `themes` | `:135` | `:44` | 2 | 对齐 | 4 处消费 |
| E2-30 | `enableSkillCommands` | `:136` | `:45` | 2 | **存疑** | 字段有、1 处消费，但 `/skill:name` 命令族**未实现** |
| E2-31 | `terminal` | `:137` | `:46` | 1 | **存疑** | java `Terminal` 只有 2 键（`Settings.java:128-131`），pi 有 6 键（`:52-60`）；且 `showImages`/`imageWidthCells` **零消费** |
| E2-32 | `images` | `:138` | `:47` | 1 | **存疑** | java `Image` 2 键与 pi 同（`:62-65`），但 `autoResize`/`blockImages` **零消费** |
| E2-33 | `enabledModels` | `:139` | `:48` | 2 | 对齐 | `SettingsAccessors.java:109-120` + `ModelCommands.java:37-57` |
| E2-34 | `defaultTools` | `:140` | — | 2 | **缺失** | 无字段 |
| E2-35 | `doubleEscapeAction` | `:141` | `:49` | 1 | **存疑** | accessor 有（`SettingsAccessors.java:168-175`），2 处消费，行为未核 |
| E2-36 | `treeFilterMode` | `:142` | `:50` | 1 | **存疑** | 同上（`SettingsAccessors.java:160-167`） |
| E2-37 | `thinkingBudgets` | `:143` | — | 1 | **缺失** | 无字段 |
| E2-38 | `editorPaddingX` | `:144` | `:51` | 1 | **存疑** | 字段有，**零消费** |
| E2-39 | `outputPad` | `:145` | `:52` | 1 | **存疑** | 字段有，**零消费** |
| E2-40 | `autocompleteMaxVisible` | `:146` | `:53` | 1 | **存疑** | 字段有，**零消费** |
| E2-41 | `showHardwareCursor` | `:147` | — | 1 | **缺失** | 无字段 |
| E2-42 | `markdown` | `:148` | `:54` | 1 | **存疑** | java `Markdown` 2 键（`Settings.java:140-143`）但 `codeBlockIndent`/`mermaid` **零消费**；pi 有 `MermaidRenderingMode` 三档 |
| E2-43 | `warnings` | `:149` | — | 1 | **缺失** | 无字段 |
| E2-44 | `sessionDir` | `:150` | `:55` | 2 | 对齐 | 7 处消费 |
| E2-45 | `httpProxy` | `:151` | `:57` | 1 | **存疑** | 字段有，**零消费** |
| E2-46 | `httpIdleTimeoutMs` | `:152` | — | 1 | **缺失** | 无字段 |
| E2-47 | `websocketConnectTimeoutMs` | `:153` | — | 1 | **缺失** | 无字段 |
| E2-48 | `tuiMode` | `:154` | `:58` | 2 | 对齐 | `SettingsAccessors.java:122-129` |
| E2-49 | `fullscreenExitOutput` | `:155` | — | 1 | **缺失** | 无字段 |
| E2-50 | `fullscreenScrollbar` | `:156` | — | 1 | **缺失** | 无字段 |
| E2-51 | `fullscreenCopyOnSelect` | `:157` | — | 1 | **缺失** | 无字段 |

**E2 汇总：对齐 18 / 缺失 19 / 存疑 14 / 合计 51 = 35.3%**；**Σ权重 80 / Σ(w×c) 46.5 / 加权 58.1%**

### E3 会话管理行为（23）

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E3-1 | 新建会话（无参） | `session-manager.ts:926` `newSession` | `SessionPersistence.java:201` `handle.create` | **3** | 对齐 | — |
| E3-2 | 续最近会话（`-c`，项目内） | `main.ts:428` `continueRecent` → `findMostRecentSession`（`session-manager.ts:636`） | `SessionPersistence.java:170` `handle.latest(cwd)` | 2 | 对齐 | A12 已结案 |
| E3-3 | `-r` 交互式会话选择器 | `main.ts:415`（`session-picker.ts` + `session-selector.ts` 1031 行） | ❌ `SessionPersistence.java:186` → `find(null)` 抛异常 | 2 | **缺失** | — |
| E3-4 | `--session` 精确/前缀查找 | `findLocalSessionByExactId`（`main.ts:242`，**项目内**） | `PersistentSessionRepositories.java:52` 走 `all()`（**跨项目 O(全部文件)**） | 2 | **存疑** | **B53** |
| E3-5 | `--session-id` 精确 ID（缺失则建） | `main.ts:432-441` | `SessionPersistence.java:175-185` | 2 | **存疑** | **B53** |
| E3-6 | `--fork` 分叉 | `main.ts`（`--fork`） | `SessionPersistence.java:192-199` | 2 | **存疑** | **B53** |
| E3-7 | `/clone` 原位复制 | `session-manager.ts:1427` `createBranchedSession` | `SessionCommands.java:48` `forkCopy` | 1 | 对齐 | — |
| E3-8 | `/fork` 从历史用户消息分叉 | `user-message-selector.ts` 155 行 | `SessionCommands.java:33` `forkCopy(name)` | 2 | **存疑** | — |
| E3-9 | 会话列表 | `listSessionsFromDir(dir, onProgress, …)`（`session-manager.ts:812`）、`SessionListProgress`（`:768`） | `PersistentSessionRepositories.java:74` `list(cwd)` **同步全量、无回调** | 2 | **存疑** | **B55** |
| E3-10 | 导出 JSONL | `session-export.ts` | `PersistentSessionRepositories.java:290` `exportJsonl` | 2 | 对齐 | — |
| E3-11 | 导出 HTML | `core/export-html/` | `export/HtmlExporter.java` + `MarkdownToHtml.java`（489 行） | 2 | 对齐 | — |
| E3-12 | 导入 JSONL | `session-manager.ts`（`/import`） | `PersistentSessionRepositories.java:307` `importJsonl` | 1 | 对齐 | — |
| E3-13 | 会话命名 | `session-manager.ts:1150` `appendSessionInfo` | `SessionCommands.java:22` + `AgentSession.java:416` | 1 | 对齐 | **B32**（get_state.sessionName 恒有默认值 `"session"`） |
| E3-14 | 会话树导航 | `session-manager.ts:1374` `branch` / `:1324` `getTree` | `SessionCommands.java:56` + `RpcDispatcher.java:563` `buildTree` | 1 | 对齐 | — |
| E3-15 | 分支摘要（branch summary） | `branch-summarization.ts:349-353` | ❌ | 2 | **缺失** | **B1** |
| E3-16 | 条目标签（labels） | `session-manager.ts:1237/1246` | `JsonlSessionStorage.java:255/260`、`LaneView.java:61` | 1 | 对齐 | — |
| E3-17 | 自定义条目（custom entry） | `session-manager.ts:1136` `appendCustomEntry` | `Session.java:183`、`LaneView.java:100` | 2 | 对齐 | — |
| E3-18 | bash 执行条目 | `session-manager.ts:1071`（`BashExecutionMessage`） | `AgentSession.java:899-908` + `AgentSessionEvent.BashExecutionUpdate:104` | 1 | 对齐 | — |
| E3-19 | `--no-session` 语义 | `main.ts`（ephemeral） | 非 web 生效（`AgentSessionTest.noSessionDoesNotRegister`）；**web 路径忽略** | 1 | **存疑** | **B54** |
| E3-20 | `--session-dir` | `session-manager.ts:483` `getDefaultSessionDir` | `AgentSession.java:142/174/190` | 1 | 对齐 | — |
| E3-21 | 按 cwd 分目录 | `session-manager.ts:483` | `AgentSession.java:142`（`--path--` 编码目录） | 2 | 对齐 | — |
| E3-22 | 崩溃恢复（settle 未结操作） | `session-manager.ts:294` `migrateSessionEntries` 等 | `SessionPersistence.java:83` `settleOpenOperation` | 2 | 对齐 | — |
| E3-23 | 会话元数据变更事件 | `session_info_changed`（`types.ts:1260`） | `AgentSessionEvent.SessionInfoChanged` **零生产者** | 1 | **缺失** | **B30** |

**E3 汇总：对齐 14 / 缺失 3 / 存疑 6 / 合计 23 = 60.9%**；**Σ权重 38 / Σ(w×c) 27.5 / 加权 72.4%**

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

### E5 扩展 API 面（27）

pi 面 = `ExtensionAPI`（`core/extensions/types.ts:1253-1510`）；java 面 = `PiExtension` + `ExtensionContext`（`extension/*.java`）。

| # | 能力单元 | pi 侧证据 | pi-java 侧证据 | 权重 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|
| E5-1 | `registerTool` | `types.ts:1311` | `ExtensionContext.java:20` `tools()` → `ToolRegistry.register` | **3** | 对齐 | — |
| E5-2 | `registerCommand` | `types.ts:1318` | `ExtensionContext.java:23` `slashCommands()` | 2 | 对齐 | — |
| E5-3 | `registerProvider` | `types.ts:1455-1470` | `ExtensionContext.java:26` `providers()` | 2 | 对齐 | — |
| E5-4 | 注册技能 | （pi 无独立 API，靠 skill 目录） | `ExtensionContext.java:29` `skills()` | 2 | 对齐 | — |
| E5-5 | `unregisterProvider` | `types.ts:1490` | ❌ | 1 | **缺失** | — |
| E5-6 | `registerShortcut`（19 保留键冲突检测） | `types.ts:1321-1328` | ❌ | 2 | **缺失** | — |
| E5-7 | `registerFlag` / `getFlag` | `types.ts:1330-1348` | ❌ | 1 | **缺失** | — |
| E5-8 | `registerMessageRenderer` | `types.ts:1354` | ❌ | 2 | **缺失** | — |
| E5-9 | `registerMarkdownTransformer` | `types.ts:1357` | ❌ | 2 | **缺失** | — |
| E5-10 | `registerEntryRenderer` | `types.ts:1360` | ❌ | 2 | **缺失** | — |
| E5-11 | `sendMessage` | `types.ts:1367-1370`（含 `triggerTurn` / `deliverAs`） | `ExtensionContext.java:52-58` 仅**落盘**；`triggerTurn=true` **抛 UOE**（`DefaultExtensionContext.java:80`） | 2 | **存疑** | — |
| E5-12 | `sendUserMessage` | `types.ts:1378-1381` | ❌ | 2 | **缺失** | — |
| E5-13 | `appendEntry` | `types.ts:1384` | ❌（`appendCustomEntry` 在 agent-core，未暴露给扩展） | 1 | **缺失** | — |
| E5-14 | `setSessionName` / `getSessionName` | `types.ts:1390/1393` | ❌ | 1 | **缺失** | — |
| E5-15 | `setLabel` | `types.ts:1396` | ❌（存储层有，扩展面无） | 1 | **缺失** | — |
| E5-16 | `exec` | `types.ts:1399` | ❌ | 2 | **缺失** | — |
| E5-17 | `getActiveTools` / `getAllTools` / `setActiveTools` | `types.ts:1402/1405/1408` | ❌ | 2 | **缺失** | — |
| E5-18 | `getCommands` | `types.ts:1411` | ❌（`CommandRegistry.names()` 存在但未暴露） | 1 | **缺失** | — |
| E5-19 | `setModel` | `types.ts:1420` | ❌ | 2 | **缺失** | — |
| E5-20 | `getThinkingLevel` / `setThinkingLevel` | `types.ts:1423/1430` | ❌ | 2 | **缺失** | — |
| E5-21 | `events`（扩展间 EventBus） | `types.ts:1505` | ❌（`HarnessEventBus` 是包私有 `final class`，非扩展面） | 1 | **缺失** | — |
| E5-22 | 热重载（`/reload` 重载扩展/skills/prompts/themes） | `agent-session.ts:2610-2635` | ⚠️ `SettingsCommands.java:53` 只重载 settings | 2 | **存疑** | — |
| E5-23 | 扩展注册 CLI flag | `types.ts:1330-1348` + `args.ts:205-212` | ❌ | 1 | **缺失** | — |
| E5-24 | 扩展 UI 注入（TUI） | `types.ts:133-289`（27 方法） | ❌ 只回落 `ExtensionUI.noop()`（`ExtensionUI.java:16`） | 2 | **缺失** | — |
| E5-25 | 扩展 UI 通道（RPC，9 method） | `rpc-types.ts:247-281` | ⚠️ `RpcExtensionUIRequest.java:13` 形有，`ui()` **无生产者**（`RpcMode` 未注入） | 1 | **存疑** | — |
| E5-26 | 扩展示例库（可执行验收规格） | `examples/extensions/` 79 个 + 9 个子目录（15912 行） | ❌ 只有 5 个测试夹具（`extension/TestExtension.java` 等） | **3** | **缺失** | — |
| E5-27 | 扩展来源追踪（`SourceInfo` / `<inline:name>`） | `types.ts:1547-1557`（`RegisteredTool.sourceInfo`、`ExtensionFlag.extensionPath`） | ⚠️ `InstalledExtension.java` 有路径，但无 `sourceInfo` 投影 | 1 | **存疑** | — |

**E5 汇总：对齐 4 / 缺失 19 / 存疑 4 / 合计 27 = 14.8%**；**Σ权重 46 / Σ(w×c) 12 / 加权 26.1%**

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

---

## 整块缺失

| 子系统 | pi LOC | 权重 | 说明 |
|---|---:|---:|---|
| **扩展示例库 `examples/`（可执行验收规格）** | **15912** | **3** | 102 个 `.ts`，79 个扩展示例（`hello.ts`、`plan-mode/`、`subagent/`、`permission-gate.ts`、`snake.ts`、`space-invaders.ts`…）+ 9 个子目录（`custom-provider-anthropic/`、`gondolin/`、`sandbox/`、`with-deps/`）+ `sdk/` 730 行 + `rpc-extension-ui.ts`。**pi-java 零对应物**（只有 5 个测试夹具）。这些示例同时是 pi 扩展 API 的**行为规格** ⇒ 缺失它们等于缺失「扩展 API 该长什么样」的可执行定义 |
| **上下文文件发现（AGENTS.md / CLAUDE.md）** | ~120（`resource-loader.ts:119-158` `loadProjectContextFiles`） | **3** | pi 按 5 个候选名（`AGENTS.override.md`/`AGENTS.md`/`AGENTS.MD`/`CLAUDE.md`/`CLAUDE.MD`，`resource-loader.ts:72`）从 cwd 向上发现并注入系统提示词。pi-java **整块不存在**：`--no-context-files` flag 被解析但零消费者，全仓 grep `"AGENTS` 零命中 |
| **JSON 主题系统** | **1552** | 2 | `theme.ts` 1234 + `theme-schema.json` 356 + `theme-controller.ts` 172 + `theme-json.ts` 146；含 `/theme` 选择器（`theme-selector.ts` 67 行）、`getAllThemes`/`loadTheme`/`setTheme` 扩展 API、文件 watch 热重载。pi-java 只有 113 行 `PiTheme.java` + 2 个内建 `.tcss` |
| **npm / git 包源 + 资源优先级 + 忽略文件** | ~2200（`package-manager.ts:139-215` 解析/优先级/忽略 + `DefaultPackageManager:806-2699`） | **3** | pi 的包管理器是完整的包生态层；pi-java 是 200 行的「下 JAR 到目录」 |
| **自更新（self-update）** | ~300（`utils/windows-self-update.ts` + `package-manager-cli.ts:1006-1100`） | 1 | pi `update self` 换二进制；pi-java 只打印「走 Maven Central」 |
| **TUI 扩展 UI 注入层** | ~700（`components/extension-editor.ts` 132 + `extension-input.ts` 87 + `extension-selector.ts` 112 + `custom-entry.ts` 62 + `custom-message.ts` 113 + `widget-placement` 相关） | 2 | widget / overlay / 自定义 footer·header / 按键拦截 / 编辑器注入全部缺失 |
| **交互式会话选择器（`-r`）** | ~1200（`session-selector.ts` 1031 + `session-selector-search.ts` 194） | 2 | pi-java 无 `-r` 无参路径（抛异常） |

**整块缺失汇总：对齐 0 / 缺失 7 / 存疑 0 / 合计 7 = 0%**；**Σ权重 16 / Σ(w×c) 0 / 加权 0%**
> ⚠️ 本表与 E4/E6 有重叠（「上下文文件发现」↔ E4-5、「JSON 主题系统」↔ E4-4、「npm/git 包源…」↔ E6-3/4/6/7/8）。**模块合计按含重叠计**，另给去重口径见 §加权汇总 脚注。

---

## 台账纠错

逐条核对了 `docs/32-open-items-register.md` 中属于本模块的条目。

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **B53** | 「`find()` 与 `latest()` 同病：`PersistentSessionRepositories.find()`（`:52`）也走 `all()`」 | ✅ **属实**。`find()` 确在 `:52`，确走 `repo.list(JsonlSessionListOptions.all())`（`:59`），确跨项目。pi 侧 `findLocalSessionByExactId` 确在 `main.ts:242` 且项目内 | `PersistentSessionRepositories.java:52-61`；`main.ts:242` |
| **B54** | 「`--no-session` 在 web 路径被完全忽略：`resolvePersistentWeb(session, args)` 的 `args` 形参一句话都没用」 | ✅ **属实**。`resolvePersistentWeb` 在 `:149`，函数体（`:149-164`）确实一次都没引用 `args` | `SessionPersistence.java:149-164` |
| **B55** | 「pi 的会话列表 API 带 `onProgress`（`session-manager.ts:812`、`:772`）」 | ✅ **属实但行号有 1 处偏移**：`SessionListProgress` 类型定义在 **`:768`**（台账写 `:772`），`listSessionsFromDir(dir, onProgress, …)` 在 **`:812`**（台账对） | `session-manager.ts:768`、`:812` |
| **B30** | 「`session_info_changed` 在 pi-java 没有生产者」 | ✅ **属实**。`AgentSessionEvent.SessionInfoChanged`（`AgentSessionEvent.java:71`）在 `EventHub` 的发射点清单里不存在 | `AgentSessionEvent.java:71`；`SessionEventHub.java` 无对应 emit |
| **B35** | 「`thinking_level_changed` 同样没有生产者」 | ✅ **属实**。`AgentSessionEvent.ThinkingLevelChanged`（`:74`）同样零 emit | 同上 |
| **B1** | 「branch summary 无实现」 | ✅ **属实**。`Settings.java` 无 `branchSummary` 字段，全模块无分支摘要代码 | `Settings.java:23-61`（51 个 pi 键里无此键） |
| **B32** | 「`get_state.sessionName` 恒有值（默认 `"session"`），pi 是可选」 | ✅ **属实**。`SessionPersistence.java:246-252` `sessionNameOf` 兜底 `"session"`；`AgentSession.java:416` 同样兜底 | `SessionPersistence.java:246-252` |
| **F3** | 「`_emitSessionCompactFailed`（扩展层事件）」 | ✅ **属实**（本模块无该事件的任何投影面） | `AgentSessionEvent.java:79` `CompactionEnd` 有 `aborted`/`errorMessage` 字段，但无独立 `compact_failed` 事件、更无扩展面 |
| **F6** | 「`ModelsJsonProvider` 钉死 baseUrl ⇒ CLI `--base-url` 对它失效」 | ⚠️ **本模块范围外**（实现在 `pi-java-ai`），但**症状可在本模块观察到**：`Args.baseUrl`（`Args.java:20`）在 coding-agent 侧有解析、`ArgsParser.java:36` 有 flag，`Main.java` 不消费 —— 由 `DefaultProviders.java` 转交 ai 层 | `Args.java:20`；`ArgsParser.java:36` |
| **A4** | 「运行中手动 `/compact` 有没有门 —— 先取证 pi」 | ⚠️ **本模块侧可补一半证据**：java `/compact` 走 `MiscCommands.java:124` → `ctx.session().compact(...)`，`SlashContext`/`AgentSession` 路径上**无 `isRunning` 门**（`AgentSession.compact` 直接执行） | `MiscCommands.java:124-131`；与 `docs/31 §8.25.5-6` 的结论一致（`RunLifecycle.compact:237-239` 无门） |
| **A5** | 「并发 prompt 有没有门」 | ⚠️ **本模块侧**：`SessionCommands`/`PrintMode` 无会话级串行保证；与台账一致 | `SessionCommands.java`、`modes/PrintMode.java` |
| **A9** | 「per-model 压缩设置（pi `getCompactionSettings(model)`）」 | ✅ **属实**。java `Settings.Compaction`（`Settings.java:121-125`）只有 3 键、**无 `modelOverrides`**；`SettingsAccessors` 无模型参数 | `Settings.java:121-125`；pi `settings-manager.ts:13-17/27` |
| **B46** | 「pi-java 的 `BashTool` 从不调 `onUpdate` ⇒ `tool_execution_update` 在生产上永不发射」 | ⚠️ **需修正范围**：`tool_execution_update` 在**会话层**有出口（`AgentSessionEvent.ToolExecutionUpdate:121` + `JsonEventMapper`），B46 说的是**工具层不产**该事件 —— 两侧都成立，但「永不发射」应限定为「**该事件无生产数据源**」而非「无投影面」 | `AgentSessionEvent.java:121`；`BashTool`（`pi-java-agent-core/.../tools/`）无 `onUpdate` 调用 |

**未发现反向误报**（即台账记为「缺」而实际已有）。上表 2 处为「范围/行号」级修正（B55 行号、B46 措辞、F6 归属），非真伪反转。

---

## 未加权汇总

| 分区 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| A. CLI 参数 | 29 | 6 | 8 | 43 | 67.4% |
| B. 子命令 | 2 | 1 | 4 | 7 | 28.6% |
| C. Slash 命令 | 11 | 2 | 11 | 24 | 45.8% |
| D. 扩展系统 36 事件 | 1 | 27 | 8 | 36 | 2.8% |
| D′. 扩展 UI 注入面 | 0 | 8 | 1 | 9 | 0% |
| E. 其余能力单元 | 42 | 57 | 35 | 134 | 31.3% |
| ├ E1 配置文件 | 2 | 1 | 2 | 5 | 40.0% |
| ├ E2 settings 键 | 18 | 19 | 14 | 51 | 35.3% |
| ├ E3 会话管理 | 14 | 3 | 6 | 23 | 60.9% |
| ├ E4 资源发现 | 3 | 4 | 0 | 7 | 42.9% |
| ├ E5 扩展 API | 4 | 19 | 4 | 27 | 14.8% |
| ├ E6 包管理器 | 0 | 11 | 5 | 16 | 0% |
| └ E7 RPC 面 | 1 | 0 | 4 | 5 | 20.0% |
| F. 整块缺失（子系统） | 0 | 7 | 0 | 7 | 0% |
| **合计** | **85** | **108** | **67** | **260** | **32.7%** |

**严格口径**（把「引擎钩子存在但未桥接到扩展」的 8 条也算缺失）：对齐 **85** / 缺失 **116** / 存疑 **59** / 合计 **260** = **32.7%**。

**扩展系统单独口径**：36 事件 = 1 对齐；UI 注入 9 面 = 0 对齐；扩展 API 27 项 = 4 对齐 ⇒ 扩展系统 72 个单元中 **5 个对齐 = 6.9%**。

**java 独有（不计入分母）**：`--base-url`、`--port`、`--debug`、`--trace-payloads`（CLI）；`/help`、`/create-skill`（slash）；`auth oauth-login`、`auth profile set|unset|list|set-key`（子命令）；`ExtensionContext.settings()`（pi 扩展 API 无设置读写，`docs/24 §2` 已记）。

**最大三处差距**：① 扩展事件面 36→0（可订阅）；② 包管理器 4236→496 行且**零对齐单元**；③ 上下文文件发现 + JSON 主题系统 + 可执行验收规格（15912 行）**整块不存在**。

---

## 加权汇总

> 口径：权重 = 用户可观察影响 × 频率（**不含排期优先级**）；`3` = 每轮对话都走 / 默认路径，`2` = 每次会话走 / 常用命令·参数·配置键，`1` = 低频·边缘·纯内部，`0` = 非目标（本模块无 0 权重单元）。
> 完成系数：**对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0**。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| A. CLI 参数 | 62 | 47.5 | 76.6% | 67.4% |
| B. 子命令 | 13 | 7.0 | 53.8% | 28.6% |
| C. Slash 命令 | 37 | 25.0 | 67.6% | 45.8% |
| D. 扩展系统 36 事件 | 72 | 11.5 | 16.0% | 2.8% |
| D′. 扩展 UI 注入面 | 16 | 1.0 | 6.3% | 0% |
| E1. 配置文件 / 清单 | 10 | 6.0 | 60.0% | 40.0% |
| E2. settings.json 键 | 80 | 46.5 | 58.1% | 35.3% |
| E3. 会话管理行为 | 38 | 27.5 | 72.4% | 60.9% |
| E4. 资源发现 | 14 | 5.0 | 35.7% | 42.9% |
| E5. 扩展 API 面 | 46 | 12.0 | 26.1% | 14.8% |
| E6. 包管理器 | 31 | 5.0 | 16.1% | 0% |
| E7. RPC 面 | 11 | 6.0 | 54.5% | 20.0% |
| F. 整块缺失（子系统） | 16 | 0.0 | 0% | 0% |
| **模块合计** | **446** | **200.0** | **44.8%** | **32.7%** |

**模块合计**：Σ权重 446 ／ Σ(w×c) 200.0 ／ **加权完成度 = 200/446 = 44.8%**（未加权 85/260 = 32.7%）

**去重口径**（F 域有 3 行与 E4/E6 重叠：上下文文件发现 ↔ E4-5、JSON 主题系统 ↔ E4-4、npm/git 包源… ↔ E6-3/4/6/7/8）：
剔除 F 域 ⇒ Σ权重 **430** ／ Σ(w×c) **200.0** ／ **加权完成度 = 200/430 = 46.5%**（未加权 85/253 = 33.6%）。

**扩展系统单独口径**（D + D′ + E5 三域合并）：Σ权重 134 ／ Σ(w×c) 24.5 ／ **加权 18.3%**（未加权 5/72 = 6.9%）。

**权重分层**：

| 权重层 | 单元数 | Σ权重 | Σ(w×c) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|---:|
| 权重 3（每轮 / 默认路径） | 23 | 69 | 28.5 | **41.3%** | 6/23 = 26.1% |
| 权重 2（每次会话 / 常用） | 140 | 280 | 125.0 | 44.6% | 45/140 = 32.1% |
| 权重 1（低频 / 边缘 / 内部） | 97 | 97 | 46.5 | 47.9% | 34/97 = 35.1% |
| **合计** | **260** | **446** | **200.0** | **44.8%** | **85/260 = 32.7%** |

> 读数：**权重 3 层的加权完成度（41.3%）低于权重 1 层（47.9%）** —— 即 pi-java 在「每轮都走」的地方欠账最重，而它补齐的是低频长尾。这正是加权口径要暴露的东西。

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

### 未加权 vs 加权的差

| 指标 | 数值 |
|---|---|
| 未加权完成度 | 32.7%（85/260） |
| 加权完成度（含 F 域） | **44.8%**（200/446） |
| 差 | **+12.1 pp** |
| 加权完成度（去重，剔 F 域） | 46.5%（200/430）＝ +12.9 pp |

**差在哪**：加权把完成度**抬高** 12.1 pp，因为 pi-java 的对齐单元**密集落在高权重的默认路径上**，而缺失质量集中在低权重长尾（settings 冷键、罕见 flag、内部契约形状）。

- **抬高项**：A 域（CLI）未加权 67.4% → 加权 76.6%（对齐的 `--model`/`--print`/`--provider` 都是 3/2 权，缺失的 `--offline`/`--use-theme`/`--` 都是 1 权）；C 域（slash）45.8% → 67.6%（对齐的 `/new` `/compact` `/resume` `/copy` `/quit` 都是 2 权，存疑的 `/tree` `/session` `/changelog` `/share` 都是 1 权）；E3（会话）60.9% → 72.4%；B 域 28.6% → 53.8%（4 个 2 权单元只是「存疑」而非缺失，各计 0.5）。
- **压低项**：E4（资源发现）42.9% → **35.7%**（唯一的高权单元 `上下文文件发现` w3 全缺，而对齐的 `资源冲突诊断` 只有 1 权）；E5（扩展 API）14.8% → 26.1% 是抬高，但 D/D′ 两域把它压回去；D 域 2.8% → **16.0%**（8 个未桥接的引擎钩子含 4 个 w3 各计 0.5，把 2.8% 抬到 16.0%，但仍在 41.3% 的权重 3 层里垫底）。
- **注意 E6 是唯一「加权 > 未加权」却几乎没动**的域：未加权 0% → 加权 16.1%，非零全部来自 5 个 2 权存疑单元各计 0.5（1+1+1+1+1=5）；**没有任何一个单元是「对齐」**，所以它的加权分完全建立在「形状存在」的半个信用上。

- **两个点名项**：
  - **扩展事件面**：D 域 Σ权重 **72** = 模块 446 的 **16.1%**，是全部 13 个域里**最大的一块**（第二名 E2 的 80 权重含 51 个单元，D 域只用 36 个单元就拿到 72）。Σ(w×c) 仅 11.5 ⇒ 若 D 域全对齐，模块 Σ(w×c) 200 → **260.5**、完成度 44.8% → **58.4%**，即**这一域单独把模块拉低 13.6 pp**。把 8 个未桥接的引擎钩子按严格口径全判缺失（11.5 → 2.0），模块降到 190.5/446 = **42.7%** ⇒ 这 8 个「半信用」本身就值 2.1 pp。
  - **上下文文件发现整块缺失**：作为**单个能力单元**只占 Σ权重 6（E4-5 w3 + F 域子系统视图 w3）= 模块的 **1.3%**；两项全对齐只把完成度从 44.8% 抬到 **46.2%（+1.4 pp）** —— 在单元计数口径下它的杠杆**很小**。⚠️ 这个数字**严重低估了它的实际影响**：它是一条**每轮 prompt 都走的默认路径**（AGENTS.md/CLAUDE.md 注入系统提示词），却只被记为 1–2 个单元。**这是加权口径的结构性盲点：单元粒度封顶了权重上限，而整块缺失的子系统在单元表里天然只占 1 行。** 对照 `--model` 这种「一个 flag 一个单元」的写法，`上下文文件发现` 的真实分量应等价于 10+ 个权重 3 单元（发现顺序、5 个候选名、向上遍历、`override` 语义、注入位置、与 `--no-context-files` 的联动、与 system prompt 源的合并顺序…）。
  - **口径修正建议**：若要更真实，整块缺失的子系统应按「暴露的行为面个数」而不是 1 计入分母（`examples/` 79 个示例、上下文文件 5 候选名 + 遍历语义、npm/git 双源 × 版本/range/pinning）。本表按用户给定规则（1 个子系统 = 1 个单元）计。
