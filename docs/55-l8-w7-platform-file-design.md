# 55 - L8 W7 平台与文件示例移植设计（5 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W7 的详细设计：把 pi 锚点 `56cae7823`
> 的 5 个平台/文件示例逐件移植到 `pi-java-examples`，给出宿主 API 消费面、逐件实现
> 要点、验收断言与窄差登记。W7 的宿主前置已全数闭环（`setTitle`/`notify`/
> `shutdown`/`sendMessage(triggerTurn)`/`exec`+cwd/`GitExecutor` 接缝），本包
> **零新增宿主成员**。用户已裁决「设计文档落地之后直接实施」。
>
> **文档状态：2026-11-19 设计并实施完成。**

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.5 W7 分组（5 件）、§4 W7 断言、§5 文本级口径
- 前置闭环：`docs/50`（B284/B285，含 `shutdown`/`isIdle`/`getSystemPrompt`）、`docs/53`（B286）
- 用户既有裁决：文本级逐字断言；外部系统一律假进程/假 git/临时目录，禁止真实 home/联网

## 0. 取证口径与关键结论

1. pi 侧行为逐字取自锚点 `packages/coding-agent/examples/extensions/` 下 5 文件，逐字文本（OSC 转义、通知、git 报告、命令/工具描述）保留为 Java 断言依据。
2. **零新增宿主成员**：`ExtensionUI.setTitle(String)`（`ExtensionUI.java:80`，默认空操作）、`notify(String,NotifyType)`（`:64`）、`ExtensionContext.shutdown()`（`:458-461`，reason `"quit"` → `session_shutdown`）、`sendUserMessage(String,SendUserMessageOptions)`（`:180`，`StreamingBehavior.FOLLOW_UP`）、`exec(cmd,args,ExecOptions)`（`:303`，cwd 经 `ExecOptions.cwd`）、`GitExecutor` 假 git 接缝（`examples/GitExecutor.java:11`）均已存在。
3. 两处能力缺席按窄差处理：① TUI 侧无 OSC 777/0 构造与终端标题写出（`setTitle` 在 TUI 为空操作，RPC 已发请求）→ D245；② 无注入式定时器给扩展 → D246（spinner 用注入式 tick 驱动纯逻辑，不建真定时器）。
4. 测试夹具需补记录面（仅 test 源码）：`ScriptedUi` 加 `titles` 录表与 `setTitle` override；`W3Context` 加 `shutdownCount`、`execCwdLog`。
5. 每件一个独立测试类；OSC 转义序列、PowerShell toast 脚本、git 命令序列、冲突报告模板逐字断言。

## 1. 单元与对位

| # | pi 文件 | Java 类型 | 注册形状 | 主要宿主消费 |
|---|---|---|---|---|
| 1 | `notify.ts` | `NotifyExtension` | 1 event：`agent_settled` | `ui.notify`；OSC/powershell 纯函数 |
| 2 | `titlebar-spinner.ts` | `TitlebarSpinnerExtension` | 3 events：`agent_start`/`agent_settled`/`session_shutdown` | `getSessionName`、`ui.setTitle`、注入 tick |
| 3 | `file-trigger.ts` | `FileTriggerExtension` | 1 event：`session_start` | `sendMessage(triggerTurn=true)`、`ui.notify`、注入 fs |
| 4 | `git-merge-and-resolve.ts` | `GitMergeAndResolveExtension` | 1 event：`agent_end` | `exec`（GitExecutor 接缝）、`sendUserMessage(followUp)`、`ui.notify`、`cwd` |
| 5 | `shutdown-command.ts` | `ShutdownCommandExtension` | 1 command：`quit` + 2 tools：`finish_and_exit`/`deploy_and_exit` | `ctx.shutdown()`、`onUpdate` |

## 2. 逐件设计

### 2.1 `notify.ts` → `NotifyExtension`

注册 `agent_settled` event（整轮 run 完全结束后发通知；注释明确不用 `agent_end`，因其后可能仍有 retry/compact/queued follow-ups）。

分派 `dispatch(title, body)` 按环境变量优先级：`WT_SESSION` → windows；`KITTY_WINDOW_ID` → OSC 99；否则 OSC 777。三臂抽为纯函数便于逐字断言：
- `osc777(title,body)` → `"\u001b]777;notify;" + title + ";" + body + "\u0007"`（单条，BEL 终止）。
- `osc99(title,body)` → 两条：`"\u001b]99;i=1:d=0;" + title + "\u001b\\"` 与 `"\u001b]99;i=1:p=body;" + body + "\u001b\\"`（ST 终止）。
- `windowsToastScript(title,body)` → PowerShell 脚本文本（逐字片段见 §6 锚点），`"; "` 连接，`powershell.exe -NoProfile -Command <script>`。

Java 侧无终端 OSC 写出通道（D245）：三臂保留为纯函数产字符串并逐字断言；`agent_settled` 实际落 `ctx.ui().notify("Ready for input", INFO)`（terminal 通知降级为聊天屏文本）。通知文案 `"Pi"`/`"Ready for input"` 保留。

事件触发经 W3Context fire；纯函数 `dispatch` 接受注入的环境判定（传 `""/"WT_SESSION"/"KITTY_WINDOW_ID"`）断言路由。

### 2.2 `titlebar-spinner.ts` → `TitlebarSpinnerExtension`

注册 3 events：`agent_start`→start、`agent_settled`→stop、`session_shutdown`→stop。模块状态 `frameIndex`、动画开关；无真定时器（D246）。

纯逻辑：
- `BRAILLE_FRAMES` 10 帧逐字：`⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏`。
- `baseTitle(cwdName, session)` → `session==null ? "π - "+cwdName : "π - "+session+" - "+cwdName`。
- `spinnerTitle(frame, cwdName, session)` → `session==null ? frame+" π - "+cwdName : frame+" π - "+session+" - "+cwdName`。
- `tick()` = `frame[BRAILLE_FRAMES 循环]` + 标题写入；`stop()` = `frameIndex=0` + 写回 `baseTitle`。
- cwd basename：`Path.of(cwd).getFileName()`。

注入式 `TickDriver`（包内接口，`tick(Runnable)`/`stop()`）驱动；测试用手动 tick 断言：agent_start 后每 tick 帧循环与 80ms 语义（记录间隔常量 `FRAME_INTERVAL_MS=80`）、agent_settled 后标题恢复基础值且 frameIndex 归零。标题经 `ctx.ui().setTitle(...)` → ScriptedUi 录表断言。

### 2.3 `file-trigger.ts` → `FileTriggerExtension`

注册 `session_start` event。硬编码触发文件 `/tmp/agent-trigger.txt`（Java 用 `System.getProperty("java.io.tmpdir")` + `agent-trigger.txt` 对位，路径差登记 D247 或沿用平台差；本文用可注入 `triggerPath` 便于断言，默认 tmpdir）。

注入式 `FileSource`（`read(path)`/`write(path,text)`/`watch(path,callback)`）避开真实 fs.watch（D246 同类：无宿主 watch，测试手动触发回调）。核心回调纯逻辑：
- 读文件并 `trim()`；空 → 不发消息、不清文件。
- 非空 → `ctx.sendMessage("file-trigger", CustomMessageContent.of("External trigger: "+content), true, details, new SendMessageOptions(true))` + `write(path,"")` 清空。
- 读抛异常 → 静默吞掉。
- `hasUi` 门控：`ctx.ui().notify("Watching "+path, INFO)`。

测试注入假 fs：断言非空触发消息文本与 triggerTurn、清空写入、空/异常/非 UI 三臂。消息入 W3Context 的 sentMessages（只记录 customType，文本断言放纯函数 `triggerMessage(content)`）。

### 2.4 `git-merge-and-resolve.ts` → `GitMergeAndResolveExtension`

注册 `agent_end` event。git 命令经注入式 `GitExecutor`（复用 W3 接缝）断言序列；`sendUserMessage` 用 `new SendUserMessageOptions(StreamingBehavior.FOLLOW_UP, null)`。

纯函数（逐字断言）：
- `formatRange(start,end)`：`start>end`→`"empty"`；`start==end`→`""+start`；否则 `start+"-"+end`。
- `formatConflicts(ref, blocks)`：首行 `"Merged "+ref+" with conflicts:"`、`""`；每块 `"  "+file+":"+startLine+"-"+endLine+" (ours "+ours+", theirs "+theirs+")"`（ours=`formatRange(startLine+1,separatorLine-1)`，theirs=`formatRange(separatorLine+1,endLine-1)`）；结尾 `""`、`"Resolve these conflicts."`；`\n` join。
- `parseConflictBlocks(stdoutFileName, filesContent)`：逐 unmerged 文件按行解析 `<<<<<<<`/`=======`/`>>>>>>>` 状态机 → `ConflictBlock(file,startLine,separatorLine,endLine)`；`catch` 静默。

`agent_end` 编排（GitExecutor 假 git，逐条 argv 断言）：
1. `rev-parse --git-dir` code≠0 → return。
2. `ref="MERGE_HEAD"`；`rev-parse MERGE_HEAD` code≠0 → 尝试新 merge：`status --porcelain` 非空→return；`rev-parse --abbrev-ref --symbolic-full-name @{u}` code≠0→return；`ref=upstream.trim()`、`remote=ref.split("/")[0]`；notify `git-merge-and-resolve: fetching <remote>, merging <ref>`(INFO)；`fetch <remote>` 失败→notify `git-merge-and-resolve: fetch failed: <err>`(WARNING) return；`merge --no-ff <ref>` 成功→return。
3. 冲突分支：`diff --name-only --diff-filter=U` → 文件列表；读各文件解析冲突块；无冲突→return；`sendUserMessage(formatConflicts(ref,blocks), FOLLOW_UP)`。

### 2.5 `shutdown-command.ts` → `ShutdownCommandExtension`

注册 1 command `quit`（描述 `"Exit pi cleanly"`）+ 2 tools：
- `finish_and_exit`（label `"Finish and Exit"`，description `"Complete a task and exit pi"`，空参数）：execute 先 `ctx.shutdown()`，返回文本 `"Shutdown requested. Exiting after this response."`（注释语义：返回值在 shutdown 发生前发 LLM；shutdown 延迟到 agent idle）。
- `deploy_and_exit`（label `"Deploy and Exit"`，description `"Deploy the application and exit pi"`，参数 `environment` 描述 `"Target environment (e.g., production, staging)"`）：execute 内 `onUpdate` 两次（`"Deploying to <env>..."`、`"Deployment complete, exiting..."`）→ `ctx.shutdown()` → 返回 content `"Done! Shutdown requested."` + details `{environment:...}`。部署 exec 为注释示例，不执行。

`ctx.shutdown()` 经 W3Context 新增 `shutdownCount` 记录（宿主真实链已由 B284 覆盖）。测试断言：command handler 触发 shutdown；两 tool 的 name/label/description/schema、onUpdate 序列（注入 `ToolUpdateCallback` 记录）、shutdown 调用、return 文本/details。

## 3. 宿主 API 与缺口

结论：**零新增宿主成员**。逐项对位：
| 消费 | 宿主来源 |
|---|---|
| `setTitle`/`notify`/NotifyType | `ExtensionUI.java:80/64`、`NotifyType.java:11-17` |
| `shutdown`/`session_shutdown` | `ExtensionContext.java:458-461`、`ExtensionEvent.java:195-201`、`ExtensionEventSpec.java:154` |
| `getSessionName` | `ExtensionContext.java:270`（W3 已用） |
| `sendMessage(triggerTurn)`/`sendUserMessage(followUp)` | `ExtensionContext.java:162-215`、`StreamingBehavior.java:17-19` |
| `exec`+cwd / GitExecutor | `ExtensionContext.java:303`、`ExtensionExec.java:51/69`、`examples/GitExecutor.java:11` |
| command/tool 注册 | `ExtensionContext.java:27/115`、`CommandRegistry.java:137` |

夹具增强（仅 test 源码）：
- `ExampleFixtures.ScriptedUi`：加 `List<String> titles` + `titles()` + `setTitle` override。
- `W3Context`：加 `int shutdownCount` + `List<String> execCwdLog`；`invoke` 补 `shutdown` 分支（计数）与 exec 的 `ExecOptions.cwd()` 记录。

## 4. 验收测试

| 测试类 | 关键断言 |
|---|---|
| `NotifyExtensionTest` | `agent_settled` 触发 notify；osc777/osc99 逐字（含 `\u001b`/`\u0007`/`\u001b\\`）；windowsToastScript 片段；dispatch 三臂路由 |
| `TitlebarSpinnerExtensionTest` | 10 帧表逐字；base/spinner 标题两模板；tick 帧循环+间隔常量 80；stop 恢复基础标题+归零；3 事件 start/stop/stop |
| `FileTriggerExtensionTest` | 非空→file-trigger 消息+External trigger 文本+triggerTurn+清空；空/异常/非 UI 三臂；Watching 通知 |
| `GitMergeAndResolveExtensionTest` | formatRange 三分支；formatConflicts 整段逐字；冲突状态机；git 命令序列（git-dir/MERGE_HEAD/status/upstream/fetch/merge）；提前 return 各臂；followUp 发送 |
| `ShutdownCommandExtensionTest` | quit 描述+handler shutdown；两 tool 元数据/schema；onUpdate 序列；shutdown 调用；return 文本/details |

运行：`mvn test -pl pi-java-examples -am`。层闭环：`mvn clean verify`。

## 5. 窄差与登记

| 编号 | 内容 | 处置 |
|---|---|---|
| D245 | TUI 侧无 OSC 777/OSC 99 终端通知写出、无窗口标题终端能力（`setTitle` 在 TUI 为空操作）：notify 三臂保留为纯函数产转义字符串逐字断言，实际通知降级为 `ui.notify` 聊天屏文本；spinner 标题经 `ui.setTitle` 由夹具录表断言（RPC 臂已实现标题请求）。沿用 D92 文本级方言 + B238③（OSC 7501 未实现） | 本包写入 docs/04 |
| D246 | 扩展无注入式定时器/fs.watch：spinner 用注入 `TickDriver` 手动驱动（记录 80ms 间隔常量，不建真定时器、不验真实帧率）；file-trigger 用注入 `FileSource` 手动触发回调（不建真实 watch）。沿用 D238 时序降级 | 本包写入 docs/04 |
| 沿用 D240 | notify 的 `powershell.exe` toast 为外部进程：三臂保留脚本拼装纯函数逐字断言，不真起进程 | 引用 B286 |

不新增账面条目：W7 完成后 examples 账面 **54/88＝61.4%**（49→54）。分母 88 已定稿，W7 五件均在分母内。

## 6. 实施顺序与验证

1. 夹具增强：ScriptedUi.setTitle、W3Context.shutdown/execCwd 记录。
2. 纯逻辑件：`Notify`（转义纯函数）/`TitlebarSpinner`（帧/标题）/`GitMergeAndResolve`（range/conflicts）先行。
3. 回调件：`FileTrigger`（注入 fs）/`ShutdownCommand`（shutdown 计数）。
4. 逐批 `-am -Dtest=...` 验证；注册表 46→51；全量 examples 测试。
5. 回填 docs/04（D245/D246、账面、文档号 docs/56）、docs/06、docs/07。

逐件取证锚点：`notify.ts:13-58`（windowsToastScript 13-24、osc777 26-28、osc99 30-34、dispatch 41-49、handler 54-56）；`titlebar-spinner.ts:14-59`（帧 14、baseTitle 16-20、stop 26-33、start 35-45、events 47-57）；`file-trigger.ts:15-42`；`git-merge-and-resolve.ts:27-116`（findConflicts 27-54、formatRange 56-60、formatConflicts 62-71、handler 74-114）；`shutdown-command.ts:13-64`。

## 7. 实施记录（2026-11-19）

用户裁决「设计文档落地之后直接实施」，5 件全部落地，零新增宿主成员：

- **新增 5 个扩展类**（`com.pijava.examples`）：`NotifyExtension`、`TitlebarSpinnerExtension`、`FileTriggerExtension`、`GitMergeAndResolveExtension`、`ShutdownCommandExtension`。
- **夹具增强**（仅 test 源码）：`ExampleFixtures.ScriptedUi` 加 `titles` 录表与 `setTitle` override；`W3Context` 加 `shutdownCount`、`execCwdLog`，`invoke` 补 `shutdown`/`reload` 计数与 exec 的 `ExecOptions.cwd()` 记录。
- **测试**：5 个测试类逐批 `-am -Dtest=...` 全绿——第一批（notify/titlebar-spinner/file-trigger，14 项）、第二批（git-merge-and-resolve/shutdown-command，11 项）；`RegistryIntegrityTest` 51 项通过。
- **注册表**：services 46→51 项；`RegistryIntegrityTest` 三处清单同步扩容，`hasSize(51)`。
- **偏离说明**：① OSC 777/99、Windows toast 保留为纯函数产字符串逐字断言，实际通知走 `ui.notify`（D245，无 TUI 终端写出）；② spinner 用注入 `TickDriver`、file-trigger 用注入 `FileSource` 手动驱动，不建真定时器/watch（D246）；③ `formatRange` 单点返回 `""+start`（pi 语义，实测确认），测试按此断言；④ shutdown/reload 走 W3Context 计数，宿主真实链（`ctx.shutdown()` reason "quit" → `session_shutdown`）已由 B284 覆盖。
- **回填**：docs/04（examples 行账面 54/88、D245/D246、维护规则 docs/56）、docs/06（L8 行）、docs/07 §3（+5 件清单、账面 54/88）。`docs/map/04` E5 段未改（本包零新增宿主成员）。
