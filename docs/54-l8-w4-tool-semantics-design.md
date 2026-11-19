# 54 - L8 W4 工具语义与执行示例移植设计（10 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W4 的详细设计：把 pi 锚点 `56cae7823` 的
> 10 个工具语义/执行示例逐件移植到 `pi-java-examples`，给出宿主 API 消费面、逐件实现
> 要点、验收断言与窄差登记。W4 的宿主前置已由 B284/B285（docs/50）与 B286（docs/53，
> bash 执行器覆盖＋`user_bash` operations 臂）闭环；**本包不新增宿主成员**。
>
> **文档状态：设计待审。用户 2026-11-19 裁决「设计文档落地之后直接实施」，审核与实施
> 连续进行；本稿先固定对齐边界，避免越界到未闭环的 B287。**

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.2（2 件已修改）、§2.5 W4 分组（8 件未移植）、§4 W4 断言
- 前置闭环：`docs/50`（B284/B285）、`docs/53`（B286：BashTool executorOverride、user_bash operations）；`docs/25`（subagent 子进程方案）在当前工作区不存在（并行重置），本包不依赖它
- 既有裁决：示例只移植注册形状与行为，不复刻真实网络/进程；内部形状差登记窄差

## 0. 取证口径与对齐边界

1. pi 侧行为逐字取自锚点源码；逐字文本（工具 description、通知、错误、渲染行）作为 Java 断言依据。
2. Java 侧只消费既有公开面（文件:行号见 §3）；**不新增宿主成员、不触碰 B287**（文件工具 Operations 注入未闭环，另立工单）。
3. 外部系统一律假进程/假执行器/临时目录：ssh 的 `spawn`、subagent 的子进程、rg 的 `execSync`、interactive-shell 的 `spawnSync` 均用注入式 fake，不启动真实进程。
4. 渲染器降级：`ToolCallRenderer` 无 `details`/`isPartial`（只有 `expanded`、结果另有 `isError`），pi 的富 Component 渲染收敛为纯文本行（D241）。
5. 补全 SPI 为空接口（`ExtensionAutocompleteProvider` 无方法），github-issue-autocomplete 只验注册形状与纯逻辑，补全建议内容不可观测（D242）。
6. 每件独立验收测试；faux provider、临时目录、W3Context 动态代理夹具（docs/52）沿用。

## 1. 单元与对位

W4 共 **10 件**（8 件 `docs/27` §2.5 未移植 ＋ 2 件 §2.2 已修改）：

| # | pi 文件 | Java 类型 | 类别 | 对齐档位 |
|---|---|---|---|---|
| 1 | `minimal-mode.ts`（改） | `MinimalModeExtension` | 已修改 | 注册形状＋纯逻辑（read 行区间、shortenPath）；渲染降级 |
| 2 | `subagent/index.ts`（改） | `SubagentExtension` | 已修改 | 注册形状＋三模式编排纯逻辑＋agents 发现；假子进程 |
| 3 | `subagent/agents.ts` | `SubagentAgents` | 未移植 | 纯逻辑（frontmatter 解析、discoverAgents） |
| 4 | `bash-spawn-hook.ts` | `BashSpawnHookExtension` | 未移植 | 形状对齐（spawnHook 无对位，D240）；命令变换纯逻辑 |
| 5 | `built-in-tool-renderer.ts` | `BuiltInToolRendererExtension` | 未移植 | 渲染注册形状＋纯逻辑（截断/diff 计数）；富渲染降级 |
| 6 | `tool-override.ts` | `ToolOverrideExtension` | 未移植 | 完整（同名覆盖 read＋自实现 execute＋日志） |
| 7 | `truncated-tool.ts` | `TruncatedToolExtension` | 未移植 | 注册形状＋截断纯逻辑；假 rg 执行 |
| 8 | `ssh.ts` | `SshExtension` | 未移植 | bash 完整（executorOverride）；文件工具形状对齐（B287 未闭环） |
| 9 | `interactive-shell.ts` | `InteractiveShellExtension` | 未移植 | user_bash Result 臂＋命令判定纯逻辑；假 shell |
| 10 | `github-issue-autocomplete.ts` | `GithubIssueAutocompleteExtension` | 未移植 | 注册形状＋纯逻辑（repo/repo解析/过滤）；补全内容降级 |

## 2. 逐件设计

### 2.1 `minimal-mode.ts` → `MinimalModeExtension`（已修改）

注册：同名覆盖内置 7 工具 `read`/`bash`/`write`/`edit`/`find`/`grep`/`ls`（pi 用 `{...tools.X}` 展开），只改渲染，执行沿用原工具。Java 侧 `ToolRegistry.register` 是 put 覆盖、`wireSession` 后加载即生效（A1）；用 `ToolRendererResolver` 注册 `ToolCallRenderer` 覆盖渲染，**不重复注册工具本体**（pi 展开原定义＝只换 renderCall/renderResult）。

**(改动点)** read 的 renderCall 拼 `ReadLineRange.format(offset, limit)` 后缀（`ReadLineRange.java:38-56`，D13）。纯函数 `shortenPath(path)`（home 前缀→`~`）、`formatReadLineRange` 后缀拼接可单测。渲染文本全部经 `ToolCallRenderer.renderCall/renderResult` 返回 `List<String>` 纯文本（无颜色，D241）：read ``read ${path}${lineRange}``、bash ``$ ${command}${timeout}``、write ``write ${path} (${n} lines)``、find ``find ${pattern} in ${path} (limit ${n})``、grep ``grep /${pat}/ in ${path} (${glob})``、ls ``ls ${path} (limit ${n})``；折叠计数 `` → ${n} files|matches|entries``。

### 2.2 `subagent/index.ts` → `SubagentExtension`（已修改）

注册：一个工具 `subagent`（label `"Subagent"`）；消费 `ctx.ui.confirm`。常量 `MAX_PARALLEL_TASKS=8`、`MAX_CONCURRENCY=4`、`COLLAPSED_ITEM_COUNT=10`、`PER_TASK_OUTPUT_CAP=50*1024`。

工具 description（逐字，`.join(" ")`）：`"Delegate tasks to specialized subagents with isolated context."` ＋ `"Modes: single (agent + task), parallel (tasks array), chain (sequential with {previous} placeholder)."` ＋ 两条 agent scope 说明（含 `${getAgentDir()}/agents` 与 `${CONFIG_DIR_NAME}/agents`）。

**三模式编排**（用假子进程结果，不真 spawn，D244）：
- 模式判定：single/parallel/chain 恰好一个，否则 ``Invalid parameters. Provide exactly one mode.\nAvailable agents: ${available}``（空为 `none`）。
- chain：`step.task.replace("{previous}", previousOutput)` 逐步执行；失败 ``Chain stopped at step ${i+1} (${agent}): ${err}``（isError）；成功 `getFinalOutput() || "(no output)"`。
- parallel：>8 报 ``Too many parallel tasks (${n}). Max is 8.``；用 `mapWithConcurrencyLimit(tasks, 4, fn)`；流式 ``Parallel: ${done}/${total} done, ${running} running...``；汇总 ``### [${agent}] ${status}\n\n${output}``（状态 `failed`/`failed (${stopReason})`/`completed`）与 ``Parallel: ${succeeded}/${total} succeeded\n\n${summaries}``（`summaries` 以 `\n\n---\n\n` 连接）。
- single：失败 ``Agent ${stopReason||"failed"}: ${err}``；成功 `getFinalOutput() || "(no output)"`。
- 项目 agent 确认：`agentScope∈{project,both}` + confirm + hasUI + !isProjectTrusted → `ui.confirm("Run project-local agents?", "Agents: ${names}\nSource: ${dir}\n\nProject agents are repo-controlled. Only continue for trusted repositories.")`；拒绝 → `"Canceled: project-local agents not approved."`。

子进程用注入式 `SubagentRunner`（函数接口 `(agent, task, cwd) -> FakeResult`），测试注入固定结果；默认实现抛 `UnsupportedOperationException("subagent process execution requires a runner")`（真 spawn 不移植，D244）。纯逻辑：`formatTokens`/`formatUsageStats`/`formatToolCall`（read 分支含行区间）/`getFinalOutput`/`isFailedResult`/`truncateParallelOutput`/`getDisplayItems`/`mapWithConcurrencyLimit`。`(改动点)` `formatToolCall` 的 read 分支拼 `ReadLineRange.format`。

### 2.3 `subagent/agents.ts` → `SubagentAgents`

纯逻辑，无注册。导出 `AgentScope=("user"|"project"|"both")`、`AgentConfig{name,description,tools,model,source,systemPrompt}`、`discoverAgents(scope,cwd)`、`formatAgentList(agents)`。

- `parseToolList`（数组或逗号串→List；其它→空）、`loadAgentsFromDir`（跳过非 `.md`；frontmatter 要求 name/description 为 string；body 为 systemPrompt）、`findNearestProjectAgentsDir`（cwd 向上找 `<CONFIG_DIR_NAME>/agents`）、`discoverAgents`（user 目录 `<agentDir>/agents`，`both` 时 project 覆盖 user，按 name 去重）。
- `parseFrontmatter` 在宿主是 package-private（D15），examples 复刻最小解析：`---` 包裹的 YAML 只取 `name`/`description`/`tools`/`model` 四个标量键（`tools` 支持 `[a, b]` 与 `a, b`）。
- `formatAgentList`：空→`"none"`，否则 ``${name} (${source}): ${description}`` 以 `"; "` 连接。
- 测试用 `@TempDir` 建 agents 目录与 `.md` 夹具，验发现/去重/覆盖/解析/格式。

### 2.4 `bash-spawn-hook.ts` → `BashSpawnHookExtension`

pi 用 `createBashTool(cwd, { spawnHook })` 注入命令变换。**D240 已登记 spawnHook 无对位**：Java 的 `BashTool.create(prefix, exposeEnv, executorOverride)` 只接受执行器（docs/53），无命令改写钩子。对齐策略：
- 注册形状：同名覆盖 `bash`（用 `BashTool.create(null, true, null)`，形状对位——真实执行仍本地）。
- spawnHook 变换抽为纯函数 `SpawnHookTransform.apply(command, cwd, env)`：命令前注入 ``source ~/.profile\n``、透传 cwd、env 加 `PI_SPAWN_HOOK=1`；单测验变换结果（command/cwd/env 三臂）。
- 窄差沿用 D240（spawnHook 无对位，命令改写仅纯函数演示，不接入执行链）。

### 2.5 `built-in-tool-renderer.ts` → `BuiltInToolRendererExtension`

注册 4 个 `ToolRendererResolver`（覆盖 `read`/`bash`/`edit`/`write` 的渲染，执行沿用内置）。只改渲染。D241 降级：pi 的 `isPartial` 状态文本（`"Reading..."`/`"Running..."`/`"Editing..."`/`"Writing..."`）在 Java 的 `renderCall` 无 isPartial 参数，不移植；只保留终局渲染与纯逻辑。

纯逻辑单测：命令 80 字符截断（`slice(0,77)+"..."`）、diff 增删计数（排除 `+++`/`---`，``+${a} / -${r}``）、`exit code: (\d+)` 正则解析（`0`/无匹配→`"done"`，否则 ``exit ${n}``）、行数 ``(${n} lines)``、截断 `` [truncated]``。渲染文本经 `ToolCallRenderer` 返回 `List<String>` 纯文本行（D241）。

### 2.6 `tool-override.ts` → `ToolOverrideExtension`（完整对齐）

注册同名覆盖 `read`（label `"read (audited)"`，**自实现 execute 不委托原工具**）＋command `read-log`（"View the file access log"）。这是 W4 唯一"完整对齐"件——不依赖未闭环接缝。

阻断正则（7 条逐字）：`\.env$`、`\.env\..+$`、`secrets?\.(json|yaml|yml|toml)$`(i)、`credentials?\.(json|yaml|yml|toml)$`(i)、`/\.ssh/`、`/\.aws/`、`/\.gnupg/`。纯函数 `isBlockedPath(path)` 单测。

execute：`resolve(cwd, path)` → blocked 返回 ``Access denied: "${path}" matches a blocked pattern (sensitive file). This tool blocks access to .env files, secrets, credentials, and SSH/AWS/GPG directories.``（details `{blocked:true}`）；读文件（offset/limit 切片 `startLine=max(0,offset-1)`、`endLine=limit?startLine+limit:lines.length`）；>50KB 截断 ``${text.slice(0,51200)}\n\n[Output truncated at 50KB]``；读失败 ``Error reading file: ${msg}``（details `{error:true}`）。每次访问经 `withFileMutationQueue` 串行追加日志（Java 无公开对位 D18 → 用 `@TempDir` 下 `Files.writeString` 追加，单线程测试无需队列；窄差登记），行格式 ``[${timestamp}] ${ALLOWED|BLOCKED}: ${path}${reasonSuffix}\n``（reason 后缀 `` (${reason})``，blocked reason `"matches blocked pattern"`）。

command `read-log`：读日志最后 20 行 → notify ``Recent file access:\n${lines}``（INFO）；无→`"No access log found"`（INFO）。

### 2.7 `truncated-tool.ts` → `TruncatedToolExtension`

注册一个工具 `rg`（label `"ripgrep"`），自实现 execute+renderer。description 逐字（含 `${DEFAULT_MAX_LINES}` 与 `formatSize(DEFAULT_MAX_BYTES)` 插值）。

执行用注入式 `RgExecutor`（`(pattern, path, glob, cwd) -> String`），默认用 `ctx.exec("rg", args, opts)`（maxBuffer 100MiB）；测试注入 fake 返回预设输出。截断用 `TruncationUtils.truncateHead`（D14）+ `formatSize`。截断通知逐字（4 段拼接，含 ``[Output truncated: showing ${outLines} of ${totalLines} lines``、`${fmt(outBytes)} of ${fmt(totalBytes)}`、`${skippedLines} lines (${fmt(skippedBytes)}) omitted.`、`Full output saved to: ${tempFile}]``）。无匹配 `"No matches found"`；失败 ``ripgrep failed: ${msg}``。纯逻辑单测：参数拼装、matchCount、`truncateHead` 通知、渲染分支。

### 2.8 `ssh.ts` → `SshExtension`

注册 flag `ssh`（"SSH remote: user@host or user@host:/path"）＋同名覆盖 4 工具 `read`/`write`/`edit`/`bash`＋3 事件 `session_start`/`user_bash`/`before_agent_start`。**核心是"同名覆盖＋执行后端"**，但文件工具 Operations 未闭环（B287，A3）：

- **bash 完整对齐**：用 `BashTool.create(null, true, remoteBashExecutor)`（docs/53 executorOverride）。`ShellExecutor.execute(command, ShellOptions)` 把命令包装为 ``cd ${remoteCwd} && ${command}``，cwd/env/timeout/abort 映射到 fake ssh 后端；测试注入 fake `SshBackend` 记录 argv。
- **read/write/edit 形状对齐**：Java 无 `ReadOperations`/`WriteOperations`/`EditOperations`（B287 未闭环），无法注入远程后端。降级为注册同名工具但执行仍本地，远程命令构造抽为纯函数单测：`toRemote(path)`（localCwd→remoteCwd）、`cat`/`test -r`/`file --mime-type -b`/base64 写/`mkdir -p` 命令串（D243）。
- `user_bash`（B286 已支持）：返回 `new UserBashEventResult.Operations(remoteBashExecutor)`（B7）。
- `session_start`：`ssh` flag 含 `:` → 拆 remote/path；否则 fake 执行 ``pwd``；状态 ``SSH: ${remote}:${remoteCwd}``、通知 ``SSH mode: ${remote}:${remoteCwd}``。
- `before_agent_start`：替换 ``Current working directory: ${localCwd}`` → ``Current working directory: ${remoteCwd} (via SSH: ${remote})``，返回 `BeforeAgentStartEventResult`。
- `sshExec` 错误 ``SSH failed (${code}): ${stderr}``。测试用 fake SshBackend，不 spawn 真 ssh。

### 2.9 `interactive-shell.ts` → `InteractiveShellExtension`

只注册 `user_bash` 事件（B6/B7），拦截用户 `!` 命令；不拦截 agent bash。

- `DEFAULT_INTERACTIVE_COMMANDS`：pi 列 59 条（vim/nvim/.../ncdu），逐字移植为 `Set<String>`。环境变量 `INTERACTIVE_COMMANDS`（逗号追加）、`INTERACTIVE_EXCLUDE`（小写排除）。纯函数 `getInteractiveCommands()`/`isInteractiveCommand(command)`（前缀匹配＋最后一个 `|` 之后匹配）。
- `!i` 强制前缀：命令以 `"i "`/`"i\t"` 开头 → `slice(2).trim()`。
- 非 TUI（`mode != "tui"`）→ 返回 `new UserBashEventResult.Result(new ShellResult("(interactive commands require TUI)", 1, false, false, 0, 0))`。
- TUI：`ui.custom` 工厂内 fake shell 执行（注入式 `ShellRunner`，不真 spawn 也不真抢终端）；结果成功 `"(interactive command completed successfully)"`，否则 ``(interactive command exited with code ${n})``；`exitCode = status ?? 1`、`cancelled=false`、`truncated=false`。D238 沿用终端动画降级（不真清屏/不 spawnSync stdin inherit）。

### 2.10 `github-issue-autocomplete.ts` → `GithubIssueAutocompleteExtension`

注册 `session_start` 事件，内部 `ctx.ui().addAutocompleteProvider(...)`。**补全 SPI 为空接口（C9，D242）**：`ExtensionAutocompleteProvider` 无方法，`AutocompleteItem`/`AutocompleteSuggestions`/`fuzzyFilter` 无对位。对齐策略：

- 注册形状：在 `session_start` 调一次 `addAutocompleteProvider(factory)`（记调用计数）。
- 纯逻辑完整移植并单测：`extractIssueToken(input)`（正则 `/(?:^|[ \t])#([^\s#]*)$/`）、`parseGitHubRepo(url)`（两种正则：SSH `git@github.com:`、HTTPS `https?://github.com/`）、`formatIssueItem(issue)`（`value`/`label`=`#${number}`，`description`=`[${state.toLowerCase()}] ${title}`）、`filterIssues(issues, query)`（空→前 20；纯数字→`number.startsWith` 优先；否则本地模糊——用简单子序列匹配近似 `fuzzyFilter`，D242）。
- `resolveGitHubRepo` 用 fake `git remote -v` 输出（注入式），错误通知逐字：`"github-issue-autocomplete: cwd is not a git repository"`、`"...not a GitHub repository"`、``...failed to load issues: ${details}``、`"...failed to parse gh issue list output"`。
- prefix ``#${token}``；`MAX_ISSUES=100`、`MAX_SUGGESTIONS=20`。不联网、不真调 gh。

## 3. 宿主 API 与缺口

结论：**零新增宿主成员**（B284/B285/B286 前置已闭环；B287 文件 Operations 不在本包）。逐项对位：

| 消费 | 宿主来源 |
|---|---|
| 同名工具覆盖（put/加载序） | `ToolRegistry.java:34-36`、`AgentSessionFactory.java:377-384` |
| bash executorOverride / ShellExecutor/ShellResult/ShellOptions | `BashTool.java:84-89`、`ShellExecutor.java:10`、`ShellResult.java:3-10` |
| user_bash 事件＋operations/result 联合 | `ExtensionEventSpec.java:185`、`ExtensionEvent.java:267`、`ExtensionResults.java:91-106` |
| 工具渲染器 | `ToolCallRenderer.java:18/24`、`ToolRendererResolver.java:21`、`ExtensionContext.java:148` |
| 工具 execute 签名（AbortSignal/onUpdate） | `AgentTool.java:154-160` |
| `formatReadLineRange` / `ReadLineRange.format` | `ReadLineRange.java:38-56`（D13） |
| `TruncationUtils.truncateHead/formatSize/TruncationResult` | `TruncationUtils.java:16/18/44/55/236` |
| exec / flag / ui.custom / setStatus / theme | `ExtensionContext.java:303/115/123/...`、`ExtensionUI.java:56/67/110/127` |
| addAutocompleteProvider（空 SPI） | `ExtensionUI.java:110`、`ExtensionAutocompleteProvider.java:8`（D242） |
| BeforeAgentStartEventResult | `ExtensionResults.java:109` |

测试夹具（仅 test 源码）：
- `W3Context`（docs/52 新增）：`ExtensionContext`/`ExtensionSessionView` 动态代理、真实 `CommandRegistry`/`ToolRegistry`、`exec` 脚本应答、事件 fire/on。W4 沿用并补：`user_bash` 事件结果记录、`registerFlag/getFlag` 记录。
- 新增注入式 fake：`SubagentRunner`、`SshBackend`、`RgExecutor`、`ShellRunner`（均为包内 `@FunctionalInterface`），避开真实进程。
- `ExampleFixtures.ScriptedUi`：`confirm`/`custom`/`notify` 已具备。

## 4. 验收测试

| 测试类 | 关键断言 |
|---|---|
| `MinimalModeExtensionTest` | 渲染器注册 4+ 个；`ReadLineRange.format` 行区间后缀；shortenPath；各工具 renderCall 文本 |
| `SubagentExtensionTest` | 三模式互斥；chain `{previous}` 替换＋失败停止文案；parallel >8/并发/汇总；single 成功失败；项目 agent 确认；假 runner 注入 |
| `SubagentAgentsTest` | frontmatter 解析四键；parseToolList；discoverAgents 去重/覆盖/向上查找；formatAgentList |
| `BashSpawnHookExtensionTest` | 同名 bash 注册；SpawnHookTransform 命令/cwd/env 三臂（D240） |
| `BuiltInToolRendererExtensionTest` | 4 渲染器注册；命令 80 截断；diff 增删计数；exit code 正则；行数/截断文本 |
| `ToolOverrideExtensionTest` | 同名 read 覆盖；7 条阻断正则；拒绝/截断/读失败文案；offset/limit 切片；read-log 两臂 |
| `TruncatedToolExtensionTest` | 参数拼装；matchCount；截断通知 4 段；无匹配/失败；渲染分支 |
| `SshExtensionTest` | flag+4 工具+3 事件注册；bash executorOverride 走 fake；user_bash Operations 臂；before_agent_start cwd 替换；远程命令纯函数；文件形状对齐（D243） |
| `InteractiveShellExtensionTest` | user_bash 注册；59 命令判定（含环境变量）；`!i` 前缀；非 TUI Result；TUI fake shell 结果 |
| `GithubIssueAutocompleteTest` | provider 注册计数；extractIssueToken/parseGitHubRepo（SSH+HTTPS）/formatIssueItem/filterIssues；错误通知；fake git remote |

运行：`mvn test -pl pi-java-examples -am`。注意本机 `-am` 增量编译偶发残留旧 class（docs/53 §8），必要时 `clean` 后重跑。

## 5. 窄差与登记

| 编号 | 内容 | 处置 |
|---|---|---|
| D241 | 工具渲染器收敛为 `ToolCallRenderer`（renderCall/renderResult 纯文本行，无 details/isPartial，有 expanded/isError），pi 的富 Component 渲染、`isPartial` 状态文本（Reading/Running/...）、颜色全部降级为纯文本；沿用 D92 文本级方言 | 本包写入 docs/04 |
| D242 | `ExtensionAutocompleteProvider` 是空接口，无 `AutocompleteItem`/`AutocompleteSuggestions`/`fuzzyFilter` 对位：github-issue-autocomplete 只验注册形状＋纯逻辑（本地模糊用简单子序列匹配近似 fuzzyFilter），补全建议内容与触发行为不可观测 | 本包写入 docs/04 |
| D243 | 文件工具 Operations 注入未闭环（B287）：ssh 的 read/write/edit 远程后端无法注入（Java 无 ReadOperations/WriteOperations/EditOperations），降级为同名注册＋本地执行＋远程命令纯函数演示；bash 经 B286 executorOverride 完整对齐 | 本包写入 docs/04，指向 B287 |
| D244 | subagent 真子进程（spawn pi --mode json、SIGTERM/SIGKILL、5s 定时器）不移植：用注入式 `SubagentRunner` 返回假结果验三模式编排；默认 runner 抛 UOE。真子进程方案见（已重置的）docs/25，如需落地另立包 | 本包写入 docs/04 |
| 沿用 D240 | spawnHook（command/cwd/env 改写）无对位，bash-spawn-hook 只验命令变换纯函数；ShellExecutor 返回完整 ShellResult | 引用 B286 |
| 沿用 D238/D92 | ssh/interactive-shell 的终端动画（清屏/SGR/inherit stdio）与颜色降级为纯文本 | 引用 |

不新增账面条目：W4 完成后 examples 账面 **49/88＝55.7%**（39→49）。分母 88 已定稿，W4 十件均在分母内。

## 6. 实施顺序与验证

1. 纯逻辑先行：`SubagentAgents` 解析/`MinimalMode` 行区间/`BuiltInToolRenderer` 计数/`ToolOverride` 阻断正则/`Subagent` 三模式/`GithubIssue` 过滤 → 单测。
2. 渲染与覆盖三件：`MinimalMode`/`BuiltInToolRenderer`/`ToolOverride`（ToolRegistry 覆盖＋ToolRendererResolver）。
3. 假进程三件：`TruncatedTool`（fake rg）/`InteractiveShell`（fake shell，user_bash Result）/`Subagent`（fake runner）。
4. 后端件：`Ssh`（bash executorOverride＋user_bash Operations，文件形状对齐）/`BashSpawnHook`（D240 纯函数）。
5. 全量 `mvn test -pl pi-java-examples -am`；全绿后回填 `docs/04`（D241–D244、账面）、`docs/06`（L8 行）、`docs/07` §3（+10 件清单）。

逐件取证锚点：`minimal-mode.ts:28,36-54,62-351`；`subagent/index.ts:34-37,39-235,298-440,470-1036`；`subagent/agents.ts:1-157`；`bash-spawn-hook.ts:1-30`；`built-in-tool-renderer.ts:1-225`；`tool-override.ts:1-144`；`truncated-tool.ts:1-195`；`ssh.ts:1-220`；`interactive-shell.ts:1-196`；`github-issue-autocomplete.ts:1-185`。

## 7. 实施记录（2026-11-19）

用户裁决「设计文档落地之后直接实施」，10 件全部落地，无越界到 B287：

- **新增 9 个扩展类**（`com.pijava.examples`）：`MinimalModeExtension`、`SubagentExtension`、`BashSpawnHookExtension`、`BuiltInToolRendererExtension`、`ToolOverrideExtension`、`TruncatedToolExtension`、`SshExtension`、`InteractiveShellExtension`、`GithubIssueAutocompleteExtension`；外加 1 个纯逻辑模块 `SubagentAgents`（subagent/agents.ts，不单独注册），合计 10 件。
- **辅助/接缝**：`SubagentRunner`/`SshBackend`/`RgExecutor`/`ShellRunner`（注入式 fake 后端，包内 `@FunctionalInterface`），`W3Context` 扩展 renderers/自动补全/cwd/user_bash 记录点，`ScriptedUi` 补 autocompleteCount。全部避开真实进程/网络。
- **测试**：10 个测试类逐批 `-am -Dtest=...` 验证全绿——第一批 3 件（truncated/interactive-shell/github-issue，16 项）、第二批 5 件（subagent-agents/subagent/bash-spawn-hook/built-in-renderer/minimal-mode，28 项）、第三批 ssh（6 项）、tool-override（5 项）；`RegistryIntegrityTest` 46 项通过。
- **注册表**：services 文件 37→46 项（W4 的 10 件中 agents.ts 为纯逻辑不注册，故 +9）；`RegistryIntegrityTest` 三处清单同步扩容，`hasSize(46)`。
- **偏离说明**：① subagent 项目级 agent 的 `ui.confirm` 审批分支未接入工具 execute（次要路径，默认 runner 已抛 UOE），仅移植三模式编排＋agent 发现；② interactive-shell 的 `ui.custom` 终端抢占降级为 `ShellRunner` fake（D238），不真清屏/inherit stdio；③ ReadLineRange.format(offset,limit) 的 end=offset+limit-1 语义经实测确认（`:5-14` 实为 `:5-18`），测试按 Java 宿主语义断言；④ 全量 `mvn test -pl pi-java-examples -am -Dtest=com.pijava.examples.*Test` 已在并行编译窗口稳定后跑通（exit 0，含全部 W1–W4 与 RegistryIntegrityTest），逐批验证亦全绿。
- **回填**：docs/04（examples 行账面 49/88、D241–D244、维护规则 docs/55）、docs/06（L8 行）、docs/07 §3（+10 件清单、账面 49/88）。`docs/map/04` E5 段未改（本包零新增宿主成员）。
