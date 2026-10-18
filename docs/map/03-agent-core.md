# 03 Agent 运行时核心（pi-java-agent-core）

> 【背景摘要】本图是 pi-java-agent-core 层的全量加权盘点：104 个能力单元逐条判定、权重、两侧 `file:line` 证据。「还有什么要对齐」的可读索引见 [`../07-agent-core-alignment-gaps.md`](../07-agent-core-alignment-gaps.md)；台账是 [`../04-open-items-register.md`](../04-open-items-register.md)，锚点事实见 [`ANCHOR.md`](ANCHOR.md)。

## 基准

- pi @ **`ea448f454`**（2026-10-11）；pi-java @ **`edc8749d`**（main，2026-10-11）。
- 所有 pi 侧 `file:line` 均由 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，**未读工作树**。

---

## 口径

**权重**（用户 2026-09-20 定）：权重 = 用户可观察影响 × 频率，不掺排期优先级。
`3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（移出分母）。
**完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。

### ★ 锚到两个 pi 面

- 【场景】pi 在 `7fd478a2e`（2026-10-01）**删除了 `packages/agent/src/harness/**`（108 文件）**，连同 `agent/src/search/`、`agent/src/node.ts`。旧图 43 处引用 `harness/*` 的参照物全部消失。
- 【决定】`harness/compaction`、`harness/messages`、`harness/session`、`harness/tools` 在主流 `coding-agent/src/core/*` 有**独立副本**。本图锚到两个面（均为 `bin pi` 主流可达）：

| 面 | pi 路径 | 规模 | 承担 |
|---|---|---:|---|
| **agent loop 本体** | `packages/agent/src/{agent-loop,agent,types,proxy,stream-fn,index}.ts` | **2,519 行** | 循环、事件、工具两相、proxy、stream-fn |
| **core 主流副本** | `packages/coding-agent/src/core/*` | 见规模节 | `agent-session` / `session-manager` / `compaction` / `messages` / `system-prompt` / `skills` / `prompt-templates` / `tools` / `settings-manager` / `model-config` / `provider-composer` |

实测支持（`git ls-tree -r ea448f454:packages/coding-agent/src/core/`）：上述文件**独立存在**；唯一一处 "harness" 字样在 `core/system-prompt.ts:156`，**只是提示词里的人话**，非依赖。

### R1 应用（删掉的参照物 ⇒ 单元作废，移出分母）

- **harness 专有、主流无副本**的行为一律作废（见「作废清单」23 条）。判据是「和 pi 表现一样」：行为在 pi 主流已不存在（删或只活在扩展事件里）⇒ 无从对齐，移出分母。
- `packages/durable`（61 文件 / 17,716 行）与 `pico3` 按 **R5 不可达**（import 者全在 `coding-agent/src/experimental/**`）⇒ 权重 0。

### 判定三档

**对齐** / **缺失** / **存疑**（`（形状）`/`（旧 pi）`/`（扩展）`/`（台账）`）。**pi-java 侧已闭环的旧「缺失/存疑」按现状改判为对齐**。

---
## 规模（本锚实测）

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| agent loop 本体 | `packages/agent/src` | **2,519**（6 文件） | —— | —— |
| core 主流副本 | `packages/coding-agent/src/core` | —— | `pi-java-agent-core/src/main` **19,795**（185 文件） | —— |
| 测试 | `packages/agent/test` | **4,058**（7 文件） | `src/test` **19,469**（119 文件） | —— |

**agent loop 本体逐文件**（`git show ea448f454:... | wc -l`）：

| 文件 | 行数 |
|---|---:|
| `agent/src/agent-loop.ts` | 949 |
| `agent/src/agent.ts` | 613 |
| `agent/src/types.ts` | 539 |
| `agent/src/proxy.ts` | 393 |
| `agent/src/stream-fn.ts` | 20 |
| `agent/src/index.ts` | 5 |
| **合计** | **2,519** |

**core 主流副本关键文件**（同法实测）：`agent-session.ts` **4,398** · `session-manager.ts` 2,013 ·
`settings-manager.ts` 1,428 · `compaction/compaction.ts` 1,119 · `provider-composer.ts` 732 ·
`skills.ts` 514 · `tools/bash.ts` 442 · `system-prompt.ts` 229 · `prompt-templates.ts` 320 · `messages.ts` 196。

> pi 测试 4,058 行含 `test/utils/` 3 个支持文件；仅 4 个 `*.test.ts` 为 3,969 行。

---

## 内置工具对齐

pi 工具定义现在**只有一处**：`packages/coding-agent/src/core/tools/`（`harness/tools/` 已删）。
`allToolNames`（8 个）在 `core/tools/index.ts:96`。pi-java 的 8 个工具在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java` 组装（`createAllTools` 8 件／`createCodingTools` 7 件，批 4 起）。

| # | 工具 | pi 有 | java 有 | 判定 | 权重 | 证据（**新锚实读**） |
|---|---|---|---|---|---|---|
| 1 | `bash` | ✅ | ✅ | **对齐** | **3** | pi `{command, timeout?}`：无默认超时、上限 `2147483.647 s`、小数秒、三分支截断 notice（`Full output:` 行文）、非零退出 resolve `isError`（`core/tools/bash.ts:22,27-37,40-43,261-263,317-366`）。java **批 3（2026-10-17，原 docs/22（批 3 设计稿））**逐字：`BashTool.java`＋`ShellOptions.timeoutMillis` 毫秒粒度＋描述/schema/snippet 常量现算＋`pi-bash-*.log` 落盘 |
| 2 | `read` | ✅ | ✅ | **对齐** | **3** | pi `{path, offset?, limit?}`（`core/tools/read.ts:15-17`）；java 同（`ReadTool.java:31`）。图片经批 9 缩放管线（`ReadTool.java` readImage：归一/转换/省略/非视觉注记，vs pi `core/tools/read.ts:88-153`） |
| 3 | `write` | ✅ | ✅ | **对齐** | **3** | pi `{path, content}`（`core/tools/write.ts`）；java 同（`WriteTool.java:31`）＋ 同形状 `FileMutationQueue`（`WriteTool.java:29`） |
| 4 | `edit` | ✅ | ✅ | **对齐** | **3** | pi `{path, edits:[{oldText,newText}]}`（`core/tools/edit.ts`）；java 同（`EditTool.java:39`） |
| 5 | `grep` | ✅ | ✅ | **对齐** | **3** | pi 七参＋gitignore＋默认 100 匹配/50KB＋500 字行截＋`path:N:`/`path-N-` 两形＋告示序（`core/tools/grep.ts:22-32,78,86-318`）。java **批 3** 逐字：`GrepTool.java` 纯 Java 引擎（**裁决①**：不移植 rg 外壳/tools-manager 下载子系统）＋`ToolIgnoreFilter`（JGit 全语义，含 `**/` 零段 glob 翻译器）；窄差 D1–D3/D8 |
| 6 | `find` | ✅ | ✅（批 3 自 `glob` 改名） | **对齐** | **2** | pi `{pattern, path?, limit?}` 默认 1000，fd 语义：basename vs `--full-path`＋`**/` 前缀适配、目录入选、gitignore、`No files found matching pattern`（`core/tools/find.ts:26-41,201-213,261-299`）。java **批 3**：`FindTool.java`（full-path 模式对**绝对候选路径**匹配——java `**` 不吃零段，fd 本就匹配绝对路径）＋`ToolSetFactory` 注册序对齐 `createAllTools`；窄差 D5/D8 |
| 7 | `ls` | ✅ | ✅ | **对齐** | **2** | pi `{path?, limit?}` 默认 500、裸名大小写不敏感排序＋`/` 目录后缀＋dotfiles＋三错误文本＋`(empty directory)`（`core/tools/ls.ts:11-15,62,86-172`）。java **批 3** 参数集换血（`LsTool.java`：`recursive`→`limit`，`[DIR]/[FILE]`＋size 列全删）；窄差 D4 |
| 8 | `powershell` | ✅ | ✅ | **对齐** | **1** | pi `core/tools/powershell.ts`，工具名进 `allToolNames`（`core/tools/index.ts:96-104`）。java **批 4（2026-10-18，docs/24）**：`PowerShellTool.java` 六面逐字（UTF8 前缀/包装命令/pwsh→powershell 解析/门禁错文）＋`PowerShellShellExecutor`＋进 `createAllTools`（8 件）；**默认不激活**（pi `DEFAULT_TOOL_NAMES` 恒四件同形），激活面随 **B236**（裁决③，docs/24 §13.3）——~~B243~~ 销号 |
| 9 | `image`（读图处理器） | ✅ | ✅ | **对齐** | **1** | pi `core/tools/read.ts` + `utils/image-process.ts` + `utils/image-resize-core.ts` + `utils/mime.ts`；java **批 9（2026-10-16）**：`tool/image/{ImageProcessor,ImageResizer,ExifOrientation,WebpDimensions,ToolResultImages}.java`（ImageIO 替代 photon）＋ `PathUtils.detectImageMimeType` 加严（0xF7/aPNG/BMP 校验）＋ `PiToolRunner` after_tool 后统一归一；窄差见 原 docs/18 §5 |

**Σ权重 21**（对齐 21.0 ／ 存疑 0 ／ 缺失 0 ＝ 21.0，**100%**；2026-10-18 批 4 powershell 对齐收尾，激活面随 B236）。

> **批 3 窄差登记**（触发重裁条件随各条；原 docs/22（批 3 设计稿） §5.4＋评审补登）：
> - **D1** grep 正则方言：Java `Pattern` vs Rust regex——lookaround/反向引用 java 多出面；字面/字符类/量词主流一致（触发：模型产出 Rust 方言失配 pattern 的案例）
> - **D2** gitignore 三件全读（grep 侧 rg 不读 `.fdignore`）；嵌套 git 仓库边界不停（pi `--no-require-git` 取舍 `find.ts:171-181`）；**搜索根之上的父 .gitignore 不读**（rg 上溯仓库根）（触发：嵌套仓库/父规则场景的可见误报）
> - **D3** 二进制 NUL 嗅探（首 8 KiB 含 0x00 即跳过）近似 rg 默认；**grep 整文件入内存**（rg 流式，超大文本文件内存画像有别）（触发：二进制误中/超大文件案例）
> - **D4** ls 排序：`toLowerCase(ROOT)` 码点序 vs pi `localeCompare`（ASCII 全等）（触发：非 ASCII 文件名排序差异被踩）
> - **D5** find 绝对 glob（`/` 开头）的 Windows 分隔符不逐字 `[/\\]`；path 不存在错文采 custom-ops 形；**普通文件作 find 根 ⇒ 报错**（fd 会把该文件自身列入候选）（触发：Windows 绝对 pattern/文件根实测差异）
> - **D6** bash `Command terminated without an exit code`（`bash.ts:354`）java 不可达（`ShellResult.exitCode` 恒 int）（结构性）
> - **D7** fd/rg 遍历序 vs `Files.walkFileTree` 序——两侧都不保证顺序
> - **D8** find 的 glob 在 Windows 大小写**不敏感**（NIO PathMatcher；fd smart-case）⇒ 过匹配；grep 走 globToRegex 大小写敏感不受影响（触发：大小写混排文件名场景）

---

## 能力单元清单（除工具）

类别：**L**=agent loop · **H**=钩子 · **E**=事件 · **N**=entry/消息 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面。
pi 侧证据锚定 `agent/src/*` 或 `coding-agent/src/core/*`。

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据（`ea448f454`） | pi-java 侧证据（`edc8749d`） | 台账 |
|---|---|---|---|---|---|---|---|
| 1 | 10 个 `AgentEvent` 变体 | E | **对齐** | **3** | `agent/src/types.ts:516-539` | `PiLoop.java:45-77`（L5 14 剧本） | —— |
| 2 | 双循环（内层工具+steering / 外层 follow-up） | L | **对齐** | **3** | `agent/src/agent-loop.ts:163-320` | `PiLoopRunner.java:45-160` | —— |
| 3 | `runAgentLoop`/`runAgentLoopContinue` 前置条件 | L | **对齐** | **3** | `agent/src/agent-loop.ts:135-150` | `PiLoop.java:266+`；`PiLaneEngine.java:87,122` | —— |
| 4 | 工具批次 `terminate` 取**全部**（`every`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:690-692` | `PiLoopTools.java:51-52` | —— |
| 5 | `length` 截断 ⇒ 本回合**全部**工具调用判失败 | L | **对齐** | **2** | `agent/src/agent-loop.ts:264-272,470-503` | `PiLoopRunner.java:212+` | —— |
| 6 | **`finishTurn`（原 `shouldStopAfterTurn`）** | L | **对齐** | **3** | `agent/src/types.ts:146-158`；调用 `agent-loop.ts:252,286`——返 `{action:"continue"\|"end"}`，能强制多跑一轮 | `PiLoop.java`（`TurnDecision`＋`FinishTurnHook`、`NextTurnContext.newMessages`）；`PiLoopRunner`（turn_end 前调用、错误路照调、explicitContinuation 三段清零）；`HookSystem.fireFinishTurn`（END 先赢否则 CONTINUE）；`PiLaneEngine` | ~~B159~~ 销号（2026-10-17 批 agent-core-1） |
| 7 | `prepareNextTurn` + `AgentLoopTurnUpdate` | L | **对齐** | **3** | `agent/src/agent-loop.ts:186`；`types.ts:159-169,278-283` | `PiLoop.java:198-224`；`PiLoopRunner.java:169` | —— |
| 8 | `transformContext` | L | **对齐** | **3** | `agent/src/agent-loop.ts:390-391`；`types.ts:244` | `HookSystem.java:53,249` | —— |
| 9 | 工具两相端口（immediate / prepared）与 end 次序 | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-660,707-945` | `PiLoop.java`（两相）；`PiLoopTools.java:90-186` | —— |
| 10 | 并行/顺序批次选择（Sequential ⇒ 整批降级） | L | **对齐** | **3** | `agent/src/agent-loop.ts:508-524` | `PiLoopTools.java:76-88` | —— |
| 11 | 重试环 A（post-run assistant 重试） | L | **对齐** | **2** | `core/agent-session.ts:1198-1213,1864-1876,3762+`（`_prepareRetry`/`_willRetryAfterAgentEnd`） | `PostRunRetry.java` | —— |
| 12 | 重试环 B（摘要重试） | L | **对齐** | **1** | `core/agent-session.ts:224-236`（`summarization_retry_*` 事件）；回调 `:3721` | `LlmSummaryGenerator.java` + `PostRunRetry.java` | —— |
| 13 | 队列：steer / followUp / nextRun | L | **对齐** | **2** | `agent/src/types.ts:293,306`（`getSteeringMessages`/`getFollowUpMessages`） | `QueueManager.java:40-62,120-159` | —— |
| 14 | `QueueMode.All` 的宿主层语义 | L | **对齐（裁决）** | **2** | `agent/src/types.ts:52-55`（`"all" \| "one-at-a-time"`） | `QueueMode.java`；drain `QueueManager.java:144-160` ≡ pi `peek/drain`；默认值 one-at-a-time ≡；setter 直写 ≡；设置改动两侧都非即效（pi 靠 reload，java 无 reload 面） | ~~F1~~ 裁决（2026-10-18 批 5，docs/04 §4，docs/25 §12.1） |
| 15 | 溢出恢复（compact-and-retry 一次） | L | **对齐** | **2** | `core/agent-session.ts:405,2945-3041`（`_overflowRecoveryAttempted`） | `PostRunCompactionCheck.java` | —— |
| 16 | 轮内阈值压缩门 | L | **对齐** | **3** | `core/agent-session.ts:776-905`（`_compactBeforeNextAssistantResponse`） | `ContextUsageEstimator.java:200-201`（`shouldCompact`） | —— |
| 17 | **手动 `/compact` 先 `abort()` 当前运行** | L | **对齐** | **2** | `core/agent-session.ts:2764-2767`（`compact()` 开头 `await this.abort()`，随后才 `compaction_start`） | coding-agent `AgentSession.compact` 三手动入口 abort-first＋`harness.waitForIdle`；pi abort 还中止在飞压缩 ⇒ 批 4 `CompactionAbortToken` 补齐（~~B36~~ 销号） | ~~A4/F4~~ 销号（2026-10-17 批 agent-core-1） |
| 18 | 压缩后重建工作副本（`NextTurnUpdate.context`） | L | **对齐** | **3** | `core/agent-session.ts`（compact → next turn context） | `PiLoop.java:198-224` | —— |
| 19 | 摘要 prompt 前缀/后缀逐字节 | C | **对齐** | **2** | `core/messages.ts:11-24`（`COMPACTION_SUMMARY_*` / `BRANCH_SUMMARY_*`） | `ContextEntries.java:33-38` | —— |
| 20 | 上下文计量（用量优先 + 尾字符估算） | C | **对齐** | **3** | `core/compaction/compaction.ts:196-225`（`estimateProjectedContextTokens :227`） | `ContextUsageEstimator.java:129-134` | —— |
| 21 | `getLastAssistantUsage` | C | **对齐** | **2** | `core/compaction/compaction.ts:166` | `ContextUsageEstimator.java:87`（`assistantUsageOf`） | —— |
| 22 | `findCutPoint` / `findTurnStartIndex` | C | **对齐** | **2** | `core/compaction/compaction.ts:446,412` | `CompactionService.java:211,268-269`（调用 `:83`） | —— |
| 23 | 输出截断（2000 行 / 头/尾两种） | C | **对齐** | **3** | pi `core/tools/truncate.ts:11-12` = **2000 行 / 50KB（51,200）**；read 描述 `core/tools/read.ts:96` | `TruncationUtils.java:18` = 2000 行 / **51,200**；BashTool/ReadTool 描述与四处注记全由常量现算，`BashUpdateEmitter` 快照同值（夹具钉两口径合一） | ~~B158~~ 销号（2026-10-17 批 agent-core-1） |
| 24 | **branch summary 生成** | C | **对齐** | **1** | `core/compaction/branch-summarization.ts:108,195`（`collectEntriesForBranchSummary`/`prepareBranchEntries`） | `BranchSummarization.java`（coding-agent/compaction，批 4）：公共祖先回溯收集、两遍扫（非 fromHook details 先累计＋新到旧预算装填＋摘要 0.9× 破格）、三态 Outcome、PREAMBLE/PROMPT 逐字、文件清单与压缩共用 `FileOperationLists` | ~~B1~~ 销号（2026-10-18 批 4） |
| 25 | **会话树导航 `navigateTree`** | L | **对齐** | **1** | `core/agent-session.ts:3964-4156` | `AgentSession.navigateTree`（批 4）：双门错文逐字、目标=叶不动、user/custom 目标 叶=父＋editorText 回填、branchWithSummary/resetLeaf/moveLane 三分支、label 张贴、`replaceTranscript` 整体换上下文（pi `_refreshFinalizedContext`）；tree 扩展事件＋TUI 摘要流挂 **B245** | ~~B1 同族~~ 销号（2026-10-18 批 4） |
| 26 | **per-model 压缩设置** | C | **对齐** | **3** | `core/settings-manager.ts:831-866`（`modelOverrides`，`getCompactionSettings(model)`） | `AgentSession.java:420`（`compactionResolver`）；`AgentHarness.java:136-140` 按车道当前模型现解析 | —— |
| 27 | **branch summary 设置**（`reserveTokens`/`skipPrompt`） | C | **对齐** | **1** | `core/settings-manager.ts:867-875`（`getBranchSummarySettings`） | `Settings.BranchSummary` record＋`SettingsAccessors.getBranchSummaryReserveTokens`（16384）/`.SkipPrompt`（false）（批 4）；skipPrompt 弹窗门随 B245 | ~~B1 同族~~ 销号（2026-10-18 批 4） |
| 28 | bash 流式 `tool_execution_update` | L | **对齐** | **2** | `core/tools/bash.ts:265,283-319`（`onUpdate`） | `BashTool.java` + `BashUpdateEmitter.java:37` | —— |
| 29 | bash 全量输出落盘 `fullOutputPath` | L | **对齐** | **1** | `core/tools/bash.ts:68` | `BashTool.java:44` | —— |
| 30 | ~~钩子 `before_run`~~ | H | **作废（R1）** | 0 | `harness/agent-harness.ts` 已删 | `HookSystem.java:43,112` | 见作废清单 |
| 31 | ~~钩子 `before_drive`~~ | H | **作废（R1）** | 0 | drive 运行时已删 | 零命中 | —— |
| 32 | 钩子 `before_run_end`（`followUp`）⇒ **`getFollowUpMessages`** | H | **对齐** | **2** | `agent/src/types.ts:306` | `HookSystem.java:93,240` | —— |
| 33 | 钩子 `transform_context` | H | **对齐** | **2** | 见 #8（`agent/src/types.ts:244`） | `HookSystem.java:53,249` | —— |
| 34 | 钩子 `before_request` ⇒ **`prepareRequest`** | H | **对齐** | **2** | `agent/src/types.ts:159-190`（返回 context/model/thinking 替换，**不含 messages**）；调用 `agent-loop.ts:219-239`（每次请求前，pending 已 append） | `PiLoop.PrepareRequestContext/RequestUpdate/PrepareRequestHook`；`PiLoopRunner` 调用点（context 整体替换＋config 写回，本 run 生效不跨 run）；`HookSystem.firePrepareRequest` 链式合并（注册键 `before_request` 保留）；老 void 钩与 `PiLaneSink.systemPrompt` 字段删除 | —— |
| 35 | ~~钩子 `before_payload`~~ | H | **作废（R1）** | 0 | 载荷改写改在扩展事件 `before_provider_request`（`core/extensions/types.ts:888`）→ 属 04 | `HookSystem.java:63,134` | —— |
| 36 | ~~钩子 `after_response`~~ | H | **作废（R1）** | 0 | 扩展事件 `after_provider_response`（`core/extensions/types.ts:904`）→ 属 04 | `HookSystem.java:68,148` | —— |
| 37 | 钩子 `before_tool` ⇒ **`beforeToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:123`；`types.ts:326` | `HookSystem.java:73,158` | —— |
| 38 | 钩子 `after_tool` ⇒ **`afterToolCall`** | H | **对齐** | **2** | `agent/src/agent.ts:124`；`types.ts:341` | `HookSystem.java:78,185` | —— |
| 39 | ~~钩子 `before_compaction`~~ | H | **作废（R1）** | 0 | 扩展事件 `session_before_compact`（`core/extensions/types.ts:772`）→ 属 04 | `HookSystem.java:83,221` | —— |
| 40 | ~~钩子 `before_navigation`~~ | H | **作废（R1）** | 0 | 导航 hook 已删（导航本体见 #25） | `HookSystem.java:88,234` | —— |
| 41 | 钩子 `should_stop_after_turn`/`prepare_next_turn` ⇒ **`finishTurn`/`prepareNextTurn`** | H | **对齐** | **3** | `agent/src/types.ts:149-158`；合并 `agent-session.ts:891-895` | `HookSystem.onFinishTurn`/`fireFinishTurn`（END 短路即赢、否则 CONTINUE、全弃权 null；注册键 `should_stop_after_turn` 保留）；`ShouldStopAfterTurnHook` 返 `TurnDecision`（`FALSE`「不许停」方言删除） | ~~#6 同根 B159~~ 销号（2026-10-17 批 agent-core-1） |
| 42 | ~~钩子 `before_resume`（java 独有）~~ | H | **作废（R1）** | 0 | pi 无对应（旧图即标 java 多） | `HookSystem.java:48,118` | —— |
| 43 | ~~effect gate（`gate.admit`）~~ | A | **作废（R1）** | 0 | `git grep effect-gate ea448f454 -- packages/*/src` **零命中** | 零命中 | —— |
| 44 | ~~HarnessEvent 28 类~~ | E | **作废（R1）** | 0 | `lane_created`/`run_suspend`/`value_update` **零命中** | `HarnessEventBus.java` | A6 |
| 45 | ~~`run_suspend` + `DeferredHandle` 挂起驱动~~ | L | **作废（R1）** | 0 | `run_suspend` 零命中 | 零生产者 | —— |
| 46 | `AgentSessionEvent` 面（pi **24** 变体） | E | **对齐** | **3** | `core/agent-session.ts:193-234`（9 个被包装的 AgentEvent 变体 + 15 个会话变体）；单一漏斗全量转发 `:1139-1142`（仅 agent_end 装饰 willRetry） | `AgentSessionEvent.java`（**24** record：补 agent_start/turn_start/turn_end/message_start/message_end 五帧，`AgentSettled{aborted}` 重塑为 pi 真形状 `:203`，`UserMessageReceived` 删——回显走真 message_end(user)）；`SessionRunner.passEvents` 漏斗化（MessageUpdate 仍走 :92 广播链防双发）；RPC `JsonEventMapper` 五新帧；web 直译化 | B30 族 |
| 47 | `queue_update` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:1045-1051`（`_emitQueueUpdate`），入队/出队点多处 | `AgentSession.java:655,660,706,739` 发射 + 公共 flush `:810,:828` | 已闭环 |
| 48 | **`session_info_changed` 生产者** | E | **对齐** | **1** | `core/agent-session.ts:3942-3948`（`setSessionName` 内 emit + 扩展 emit） | `AgentSession.setSessionName`（批 4）：归一化（blank→null）＋改名才发 `SessionInfoChanged`（pi `:3686/:5996` 两调用点同形）；扩展 emit 属 04 域 | ~~B30~~ 销号（2026-10-18 批 4） |
| 49 | `thinking_level_changed` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:2628`（isChanging 门内） | `AgentSession.java:509-516`（门同，`:513` 发射） | 已闭环 |
| 50 | `bash_execution_update` 生产者 | E | **对齐** | **2** | `core/agent-session.ts:3862` | `AgentSessionEvent.BashExecutionUpdate` | —— |
| 51 | `compaction_start`/`compaction_end` 事件 | E | **对齐** | **3** | 定义 `core/agent-session.ts:209-222`；手动发射 `:2767,2878-2902`；自动 `:3119,3205` | `CompactionObserver`（接线 `AgentSession.java:290-306`） | —— |
| 52 | `agent_end` 载荷 = **本 pass** `newMessages` | E | **对齐** | **3** | 引擎 `agent/src/agent-loop.ts:254,290,320`；会话层**透传引擎载荷** `core/agent-session.ts:1141,1288-1289` | 会话层透传本 pass `newMessages`＋`willRetry` 装饰（`SessionRunner.passEvents`）；web 的整表替换由 `AgentEventTranslator` 注入的 `transcriptSource`（`session::accumulatedMessages`）吸收 | ~~A8~~ 销号（2026-10-17 批 agent-core-2） |
| 53 | `turn_end` 颗粒度 | E | **对齐** | **2** | pi 会话事件 union 含引擎逐回合 `turn_end{message,toolResults}`（`core/agent-session.ts:196-240`；`agent/src/agent-loop.ts:253,287`） | 批 2 起每**回合**一条真 `TurnEnd`（`PiLoopRunner.java:134,162` 引擎直供、错误路 `RunFailure.java:62` 同形空 toolResults）→ `SessionRunner` 透传 → `JsonEventMapper` 线形逐字；窄差：pi 的 `parentToolCallId` 包装（子代理上下文）java 无——无嵌套代理面，顶层恒 absent | ~~B49~~ 销号（2026-10-18 批 5：批 2 前提已消，docs/25 §12.2） |
| 54 | Entry union | N | **对齐（裁决）** | **3** | **11 类**（`core/session-manager.ts:65-176`）；v3 平铺（`:41` `CURRENT_SESSION_VERSION=3`） | **裁决：行为等价，不认账**（docs/04 §4 map/03-#54）——label/session_info 零 Entry 身份消费者（消费者全 pattern-match `SessionMutation`/`LogItem`）；wire 读写 pi 原生行＋fact 链进树 ⇒ 磁盘全互通、树位等价；`SessionTreeNode.label` 两侧都是同构访问器。内存 union 不补 2 record | —— |
| 55 | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | N | **对齐** | **1** | `core/session-manager.ts:101,115`（`fromHook?: boolean` 兼容字段） | `Entry.Compaction`/`BranchSummary` +`Boolean fromHook` 末位分量（批 4）：可空＋NON_NULL 省略 ≙ pi undefined；`EntryJsonCodec.optionalFromHook`；`CompactionFiles` 守卫（`TRUE` 即 return，pi `compaction.ts:70` 同形） | —— |
| 56 | `EntryProjector`（`ProjectedSessionEntry` / `buildSessionProjection`） | N | **对齐** | **1** | `core/session-manager.ts:209,543` | `ContextEntries.java:152-199`（`ProjectedEntry`/`projectEntries` ≡，含「仅下标 0 的 compaction 贡献摘要」同守卫）；投影的 thinkingLevel/model 便捷返回 java 无——无消费者，免登 | ~~A1~~ 销号（2026-10-18 批 5，docs/25 §12.5） |
| 57 | 四个消息类型 | N | **对齐** | **2** | `core/messages.ts:29-66`（bashExecution/custom/branchSummary/compactionSummary） | bashExecution 全链（批 5）：`Entry.BashExecution` 变体（线形 `type:"message"`＋role 判别，codec 特判）＋投影 `bashExecutionText` 逐字＋`excludeFromContext`（!!）跳过＋`recordBashResult`＋`_pendingBashMessages` settled flush＋`AgentHarness.appendLaneEntry`；pi 会话文件互读修复（G2 断读消）；窄差 D-注：exclude 条目投影层不产消息（不占工作副本位）、会话级 bash 无 50KB 截断/落盘（`ShellResult.truncated` 恒 false） | ~~A1~~ 销号（2026-10-18 批 5，docs/25 §3） |
| 58 | `convertToLlm` | N | **对齐（裁决＋剔除已删）** | **3** | `core/messages.ts:148-196`；默认 `agent/src/agent.ts:38-46`（只按 role）；session-manager 投影侧零 stopReason 处理（`:440` 起 `return [message]`） | 合并管线消解（docs/04 §4 map/03-#58：case 语义逐条对上；bashExecution 角色不在对齐范围）；**stopReason 剔除已删**——`NON_PROJECTED_STOP_REASONS` 移植自已删除的 harness `isContextMessage`（R1 作废），error/aborted 消息恒投影（现行 pi 主流行为） | **A1** |
| 59 | ~~`LaneConfiguration`~~ | N | **作废（R1）** | 0 | `harness/session/types.ts` 已删 | entry 化 | #54 同根 |
| 60 | ~~`PendingEntry` / `InboxItem` ⇒ 队列契约~~ | N | **作废（R1）** | 0 | 现锚点全仓零命中——Pico 实验 harness 类型，随 pi `7fd478a2e`（remove the experimental harness）整包删除 | `QueueManager.java`（队列契约以 pi `PendingMessageQueue` 为参照，#14 已核齐） | 见作废清单（2026-10-18 批 5，docs/25 §13） |
| 61 | **`formatSkillsForSystemPrompt`** | P | **对齐** | **2** | `core/skills.ts:358`（`formatSkillsForPrompt`）；调用 `core/system-prompt.ts:180` | `SkillsPrompt.java:30-58`（三行指引 + XML 逐字） | 已闭环 |
| 62 | **技能调用展开（`/skill:` → `<skill>` 块）** | P | **对齐** | **2** | 主流无独立函数（harness 旧 `formatSkillInvocation` 已随删）；现行为内联在 `core/agent-session.ts:2149-2162`（`_expandSkillCommand`：读 SKILL.md、剥 frontmatter、组 `<skill name location>…</skill>`） | `SkillCommandExpansion.java`（批 4，裁决④按内联形状落地不复活旧函数名）＋`AgentSession` processPrompt/steer/followUp 三接线＋`MarkdownSkill.filePath`；`skill:` slash 命令注册挂 **B244** | —— |
| 63 | `loadSkills` / `loadSourcedSkills` | P | **对齐** | **2** | `core/skills.ts:414,168`（`loadSkillsFromDir`） | `SkillDiscovery`（批 4，归属维持 coding-agent——裁决②与 pi 同构）：同名先到赢（pi 序）＋realPath 去重＋来源推断＋collision/警告诊断；`LoadSkillsResult` 面 | —— |
| 64 | `loadPromptTemplates` 等四函数 | P | **对齐** | **2** | `core/prompt-templates.ts:222,304` | `PromptTemplates.java`（批 4，归属同裁决②）：`argumentHint`/`filePath` 字段、`loadDefaults` 两默认目录、单正则五支 `substituteArgs`（JS 空串 falsy 语义）、缺失模板静默；`PromptTemplateRegistry.find` | —— |
| 65 | `sanitizeSurrogates` | A | **对齐** | **1** | pi `utils/sanitize-unicode.ts:21` | ✅ pi-java-ai 的 `SanitizeUnicode`，生产调用如 `AnthropicMessageConverter.java:55,135` —— **跨模块** | 已闭环 |
| 66 | ~~`AdaptivePublisher`~~ | A | **作废（R1）** | 0 | `harness/utils/adaptive-publisher.ts` 已删 | 无 | —— |
| 67 | ~~`applyShellOutputUpdate` / `ShellOutputView`~~ | A | **作废（R1）** | 0 | `harness/utils/output-capture.ts`/`shell-output.ts` 已删（主流量见 #28） | `ShellOutputSink.java` | —— |
| 68 | ~~`streamProxy`~~ | A | **作废（R5）** | 0 | `agent/src/proxy.ts:107-111`（存活但 **bin pi 主流不可达**：coding-agent 全包零 import，引用点仅 `index.ts` 再导出/单测/构建冒烟 typeof 断言） | 无 | 见作废清单（批 4 裁决①，docs/24 §13.1） |
| 69 | **`setDefaultStreamFn`** | A | **对齐** | **1** | `agent/src/stream-fn.ts:11`（**存活**）；`index.ts:4` 再导出；装载点 `sdk.ts:43-46` | `DefaultStreamFn.java`（批 4：volatile 静态持有＋未装逐字抛）＋`HarnessConfig.build()` 回落＋`AgentSession.assemble` 安装点（pi sdk.ts 模块顶层副作用对位） | —— |
| 70 | ~~`SessionSearchService`（6 方法）~~ | A | **作废（R1）** | 0 | `agent/src/search/` 已删；`SessionSearch` 无主流命中 | `SessionSearch.java` | —— |
| 71 | ~~`reduceLaneSnapshot`~~ | A | **作废（R1）** | 0 | `harness/runtime/reducer.ts` 已删 | 无 | —— |
| 72 | ~~错误面 15 个 `TaggedError`~~ | A | **作废（R1）** | 0 | `TaggedError` 零命中 | 4 个异常类 | —— |
| 73 | JSONL 存储（v3 平铺事务追加） | S | **对齐** | **3** | `core/session-manager.ts:41,1064-1072`（`appendFileSync`＋`{type:"session",version:3}`）；标签/名行 `:136,:143` | `PiV3Wire.java:38-45`（v3 头）、`RecordJsonCodec`（label/session_info 原生拼写）、`SessionWireShape`（消息原生拼写）、legacy 头惰性迁移 `JsonlSessionStorage.java:295-302`；双向夹具 `PiV3WireFormatTest`/`PiNativeSpelling*Test` | 已闭环（包 12–14） |
| 74 | ~~存储 conformance / benchmark 套件~~ | S | **作废（R1）** | 0 | `harness/session/testing/*` 已删 | agent-core 无 | A17 |
| 75 | session fork | S | **对齐** | **2** | `core/session-manager.ts:1815`（`SessionManager.forkFrom`）；`main.ts:380-386`（fork 支配并消费 `--session-id`） | `ForkOptions.java`＋调用面（批 5）：fork 分支提前于 sessionId 分支并消费之（`RepositoryHandle.fork` 4 参，jsonl/sqlite 双后端）；java 私加的 `" (fork)"` 名后缀删除（pi 不改名）；窄差 F-d：空/无头源文件的错误形是 load 期 DecodeError，非 pi 两条专属文案 | ——（2026-10-18 批 5，docs/25 §5） |
| 76 | `session_start` / `resources_discover` | S | **对齐** | **2** | `core/agent-session.ts:509,3695`；resources `:3285`；扩展侧 `core/extensions/runner.ts:1487-1490` | `AgentSession.java:104,453-460,1156` | —— |
| 77 | 会话恢复（resume seed + record replay） | S | **对齐** | **2** | `core/agent-session.ts:525,1809,4138`（`_restoreToolsFromTranscript`） | `RunLifecycle.java:284,330`（seed/restore）＋批 5 `restoreToolsFromTranscript`（navigateTree 后按新叶系统消息 `toolsAdded` 恢复激活装配，pi `:4138` 同序；未注册名静默落——`_pendingToolNames` 晚注册臂随 B221 ⑫b；CLI resume 臂 pi 自己也够不到：sdk 恒给 `initialActiveToolNames`） | ~~A2~~ 销号（2026-10-18 批 5，docs/25 §4） |
| 78 | `latest()` / `find()` 会话定位范围 | S | **对齐（裁决）** | **3** | `core/session-manager.ts:749`（`findMostRecentSession` 只读一个项目目录）；`listSessions` 按**最后活动时间** desc（`:858-864`，mtime 仅兜底） | 裁决（docs/04 §4 map/03-#78）：latest/resume 等价；`find(idOrPrefix)` 跨项目扫描保留（CLI `--resume <id>` 需要）；**排序键差留痕**——java list 按 mtime desc（`JsonlSessionRepository.java:85-90`），pi 的 lastActivity 需全量扫 entry（正是 java 优化掉的成本），追加写下 mtime 序 ≈ 活动序；**分级阶梯批 4 落地**（`SessionPersistence` 路径快车道＋`findById` header-only 精确＋项目前缀＋跨项目精确胜前缀，docs/24 §9） | ~~B53~~ 销号（2026-10-18 批 4） |
| 79 | 会话列表 `onProgress` | S | **对齐** | **1** | `core/session-manager.ts:943-965` | `RichSessionLister.java`（批 4）：`listRich` onProgress 回调＋AbortSignal＋`mapWithConcurrency` 虚拟线程并发；`AgentSession.listSessionsSummary` 已迁移 | ~~B55~~ 销号（2026-10-18 批 4） |
| 80 | 宿主 `prompt()` 的 `streamingBehavior` | L | **对齐（裁决）** | **2** | `core/agent-session.ts:320,1921,1997-2019`（steer/followUp 两门） | `PromptRouter` 两门抛文逐字（`:1988`/`:2016` 同文）＋展开先于门（`AgentSession.java:808`→`:812` 同序）＋steer/followUp 入队＋`queue_update`（B34）；引擎门 `PiLaneEngine.java:87-91` | ~~A5/F4~~ 裁决（2026-10-18 批 5，docs/04 §4，docs/25 §12.3） |
| 81 | 工具线程安全契约 | A | **对齐** | **1** | `agent/src/agent-loop.ts:508-660`（契约：工具必须可并发） | `AgentTool` 类注钉并发契约（指向 `PiLoopTools` 真并发）＋审计表（docs/25 §10.1）：read/grep/find/ls 无共享态、bash/powershell 每调用独立进程（无持久 pwsh 会话）、`BashUpdateEmitter` 每调用新建；**审计挖出修真**：edit/write 各自静态队列 ⇒ pi 模块级单例，`FileMutationQueue.shared()` 统一（同文件 edit↔write 也互斥） | ~~A10/A11~~ 销号（2026-10-18 批 5） |
| 82 | `retry.provider.*` ↔ `HTTP RetryPolicy` | A | **对齐（裁决）** | **2** | `core/settings-manager.ts:925-931`（`maxRetryDelayMs ?? 60000`）；`provider-retry.ts:106`（`maxRetries ?? 0`） | `DefaultProviders.java:330-346` 三键映射 ≡——`maxRetries` 缺席 ⇒ 0（两侧同）、`maxRetryDelayMs ?? 60000`、`timeoutMs` 明给替换默认（`httpIdleTimeoutMs` 默认值差属 map/04 E2-48 另案） | ~~A7~~ 裁决（2026-10-18 批 5，docs/25 §12.4） |
| 83 | `transcript()` 下游消费者清点 | A | **对齐** | **1** | —— | 清点表（docs/25 §10.2）：`AgentSession.entries()`/`accumulatedEntries()`、navigateTree、fork（`createForkMutations`）、export、压缩、RPC `get_state`、web 快照——全走存储级新解码（`findEntries`）或快照 COW（`LaneSnapshot` 的 `List.copyOf`），无未核裸读 | ~~A2~~ 销号（2026-10-18 批 5） |
| 84 | 两道门的行为裁决 | L | **对齐（裁决）** | **2** | 见 #17/#80 | 同 #80——pi 无「压缩窗」概念：压缩与 run 单飞、门在 prompt 入口抛（`:1988`），与 java 关窗抛同效（窗来自包 25–27 B175/B176） | ~~F4~~ 裁决（2026-10-18 批 5，docs/04 §4，docs/25 §12.3） |
| 85 | `recordEvent` 无宿主跨度时的兜底 | A | **对齐** | **1** | —— | `JsonlFileTelemetry.recordEvent`：javadoc 写明兜底＋夹具钉「无跨度 ⇒ `name`/`payload` 在、`spanId`/`traceId` 两键缺席」（java 自有面，pi telemetry 休眠契约无对位，行为自定钉住） | ~~F2~~ 销号（2026-10-18 批 5，docs/25 §11.1） |
| 86 | ~~`_emitSessionCompactFailed`~~ | E | **作废（R1）** | 0 | `core/agent-session.ts:1053-1057`：**只**向扩展 runner 发 `session_compact_failed`（`core/extensions/types.ts:796`），无公开会话事件 ⇒ 属 04；失败路公开事件是 `compaction_end{errorMessage}`（见 #51，java 已齐） | 注释同裁决（`PostRunCompactionCheck.java:168`，`CompactionExecutor.java:189`） | 见作废清单 |
| 87 | L5 夹具对 thinking 块不对称 | A | **对齐** | **1** | `conformance/pi/run.test.ts`（在 pi-java 仓） | 修两侧（批 5 §6）：pi twin `ScriptedStream` 补 thinking 三分支（此前静默丢弃）、java `ScriptedStreams` 补 `ThinkingDelta` 帧；验收：S16 剧本（thinking＋text）双 runner 对拍**零 diff**（vitest 16/16＋`ConformanceTest` 16/16，S1–S15 重生成逐字节不变） | ~~E8~~ 销号（2026-10-18 批 5） |
| 88 | ~~`ActiveToolsChange` 发射~~ | E | **作废（R1）** | 0 | `git grep active_tools_change ea448f454 -- packages/` **零命中**（旧锚同样零命中）—— pi 主流从不发射 | 不发射；裁决已落 `Entry.java:23-24`（R2：变体删除） | 见作废清单 |
| 89 | 批内 join 源序 vs `Promise.all` | L | **对齐** | **1** | `agent/src/agent-loop.ts:646`（`Promise.all` 收束保源序） | `PiLoopTools.java:144-186`＋`ToolBatchConcurrencyTest`（批 5 §7.1）：三调用闩锁完成序 3→1→2 钉「end 帧完成序／结果消息源序／terminate 随源序 outcome（every，全 terminate 批不再拉下一段剧本）」 | ~~C2~~ 销号（2026-10-18 批 5） |
| 90 | `transcript`/`messages`/`partial` 可见性 | S | **对齐（裁决）** | **1** | —— | 写者唯一（驱动线程 `lane.appendEntry`，lane 锁内）；读者全走快照 COW（`LaneSnapshot`/`SessionSnapshot` 的 `List.copyOf`——records COW 裁决同族，docs/04 §4 在册）；无锁裸读面不存在 | ~~C3~~ 裁决（2026-10-18 批 5，docs/25 §12.6） |
| 91 | `RunLifecycle.reset` 不 `close()` runSpan | S | **对齐（裁决）** | **1** | —— | `finishRun` 于 `PiLaneEngine.java:217-231` 的 finally 收口（error 路同走）⇒ 空闲车道 runSpan 必已关；reset 置 null 是防御性清场（注释已补强，docs/25 §11.4） | ~~C4~~ 裁决（2026-10-18 批 5，docs/25 §12.7） |
| 92 | 遥测 `startSpan`/`openSpan` javadoc | A | **对齐** | **1** | —— | `TelemetryContext` 三处按现状重写（批 5 §11.2）：`startSpan`（回调作用域绑定＋对称解绑＋异常自动 error 态＋必然关闭）、`openSpan`（不绑定不自动关闭，调用方持柄）、push/popCurrent（ThreadLocal 线程归属＋finally 配对＋只 pop 栈顶） | ~~D2/D3~~ 销号（2026-10-18 批 5） |
| 93 | `SummaryGenerator.java:9-12` javadoc 过期 | A | **对齐** | **1** | —— | `truncating()` 消费者清点：`HarnessConfig` 三处默认＋测试夹具 ⇒ **保留**，javadoc 重写为「未接 LLM 生成器时的确定性兜底默认」，「Phase 6 前占位」过期表述删除 | ~~D6~~ 销号（2026-10-18 批 5，docs/25 §11.3） |
| 94 | 文件超限：`PiLaneSink`/`AgentHarness` 行数 | A | **对齐** | **1** | —— | 批 5 §8 拆分（行为零变化纯移动）：`PiLaneSink` 562→**470**（请求遥测族 → `PiLaneRequestTelemetry` 131）；`AgentHarness` 643→**438**（字段/构造装配/队列委托族 → 包私有基类 `HarnessBootstrap` 231，继承保 API 面） | ~~E3/E4~~ 销号（2026-10-18 批 5） |
| 95 | 夹具在集成层无牙 | A | **对齐** | **1** | —— | `ShellByteBoundaryTest`（批 5 §9）：字节级假 shell——3 字节 UTF-8 字符第 2 字节钉在 8192 泵边界（stdout＋stderr 双路，无 U+FFFD/无丢字）；泵审计结论：终局一次解码＋增量字符边界（`Utf8ChunkStream`），**无 bug** | ~~A14~~ 销号（2026-10-18 批 5） |
| 96 | 错误路 `AgentSettled` 无夹具 | A | **对齐** | **1** | —— | `SessionFailurePathTest` 增补（批 5 §7.2）：工具结果已产出、回合随后失败 ⇒ 错误路 `TurnEnd.toolResults` **恒空**（pi `agent-loop.ts:253` 同形），`AgentSettled{aborted}` 不被错误路改写 | ~~A15~~ 销号（2026-10-18 批 5） |
| 97 | **扩展开启（extended thinking）目录→请求投送** | C | **对齐** | **2** | `models.json thinkingLevelMap` → `core/provider-composer.ts` → `provider-composer`/`anthropic-messages` | `AgentHarness.java:136-145`；`PiLaneEngine.java:329-330`；`PiLoopRunner.java:173`；`DefaultProviders.java:255-261`（coding-agent） | 已闭环 |
| 98 | **`abortCompaction`** | A | **对齐** | **1** | `core/agent-session.ts:2914-2917`（另 `abortBranchSummary :2921`）；TUI Esc 换绑依赖 | `CompactionAbortToken`（批 4：per-op 双槽 manual/auto，`CompactionExecutor` 窗口挂/摘＋生成后同点判）＋`AgentHarness.abortCompaction`＋`AgentSession.abortBranchSummary`/abort() 集成；TUI Esc 换绑随 B245 | ~~B36~~ 销号（2026-10-18 批 4） |
| 99 | **工具装配变更声明**（`declareToolChanges`） | L | **对齐** | **2** | `agent/src/agent-loop.ts:333-374`（含 `ToolStateChanges`/`NO_CHANGES`，types 侧无独立声明） | `ToolChangeDeclaration.java` | 已闭环 |
| 100 | **系统提示作为 transcript 的 system 消息** | N | **对齐（裁决）** | **3** | `packages/ai/src/types.ts:780-783`（`TranscriptContext` **只有 messages**）；系统提示由 `normalizeContext`（`utils/transcript.ts:30-34`）折成**首条 system 消息**；主流**无会话中变更路径**（`setSystemPrompt` 全仓零命中） | 裁决（docs/04 §4 map/03-#100）：provider 边界 `DefaultProviders` 交 `Context.systemPrompt` 经 `ContextNormalizer` 于**同一点**折叠 ⇒ 请求线行为等价；`Context.java` 类注按本锚重写；`AgentHarness.setSystemPrompt` 是超集能力（pi 无），留痕不删 | —— |
| 101 | **`prepareNextTurn` 可返回 `messages`** | L | **对齐** | **2** | `agent/src/types.ts:163-169`；`agent-loop.ts:186,211` | `PiLoop.java:210,222-224`（已有 `messages`） | 已闭环 |
| 102 | `Agent.reset()` **保留基线 system 消息** | L | **对齐** | **1** | `agent/src/agent.ts:354-368`（`getCurrentSystemMessage` 保留首条） | `RunLifecycle.java:235-254` 清空 transcript 与工作副本；**系统提示载体是 `Context.systemPrompt` 字段而非首条消息，清空不丢基线、下次请求重新注入 ⇒ 行为一致** | **B70 已取证消解** |
| 103 | ~~`experimental/pico3` kernel~~ | S | **作废（R5）** | 0 | 已随 harness 删除 | 无 | —— |
| 104 | ~~`packages/durable`~~ | S | **作废（R5）** | 0 | 61 文件但 import 者全在 `experimental/` | 无 | —— |

> **不在本模块范围的台账条目**：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式）、B38/B39/B47（TUI）、B52/A13/A16（已结案）、B54（web 路径）。

---

## 作废清单（R1／R5 —— 权重 0，移出分母）

**23 条能力单元**（旧编号保留，便于对照）：#30 `before_run` · #31 `before_drive` · #35 `before_payload` ·
#36 `after_response` · #39 `before_compaction` · #40 `before_navigation` · #42 `before_resume` ·
#43 effect gate · #44 HarnessEvent · #45 `run_suspend` · #59 `LaneConfiguration` ·
**#60 `PendingEntry`/`InboxItem`（批 5 新作废，R1：Pico 实验 harness 类型，随 pi `7fd478a2e` 整包删除；docs/25 §13）** ·
#66 `AdaptivePublisher` ·
#67 `output-capture`/`shell-output` · **#68 `streamProxy`（批 4 新作废，R5：pi 侧存活但 bin pi 主流不可达——coding-agent 全包零 import，仅 `index.ts` 再导出/单测/构建冒烟 typeof 断言；裁决① docs/24 §13.1，触发条件：服务端托管鉴权代理车道或 pi 主流接入）** · #70 `SessionSearchService` · #71 `reduceLaneSnapshot` · #72 `TaggedError` ·
#74 存储 conformance 套件 · **#86 `_emitSessionCompactFailed`（本锚新作废：仅扩展事件，归 04）** ·
**#88 `ActiveToolsChange`（本锚新作废：pi 两锚全树零发射）** · #103 pico3 · #104 durable。

**一条整块缺失**：① drive 运行时（`harness/runtime/drive/` 12 文件）—— 参照物被删。

> `before_payload`/`after_response`/`before_compaction`/`session_compact_failed` 的**替身是扩展事件**
> （`before_provider_request`/`after_provider_response`/`session_before_compact`/`session_compact_failed`），
> 属 `04-coding-agent` 的域。

---

## 整块缺失

**本轮无独立计权项** —— 旧表 11 条中：① drive（作废）、③ effect gate（作废）、④ 存储套件（作废）、
⑥ `AdaptivePublisher`（作废）、⑦ values.ts（作废）、⑩ pico3（作废）、⑪ durable（作废）；
② branch summary+导航、⑤ proxy、⑧ skills、⑨ sanitize 仍作**能力单元**（#24/#25、#68、#61/#62、#65）逐条计权。

---

## 汇总（未加权）

| 表 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| 内置工具 | 9 | 0 | 0 | 9 | **100%** |
| 能力单元（除工具） | 81 | 0 | 0 | 81 | **100%** |
| 整块缺失（无独立计权项） | 0 | 0 | 0 | 0 | —— |
| **合计** | **90** | **0** | **0** | **90** | **100%** |

**未加权完成度 = 90 / 90 = 100%**（2026-10-18 批 5：**存疑清零**——21 存疑翻对齐、#60 按 R1 出分母（参照物随 pi `7fd478a2e` 删除，91→90）；设计稿 docs/25）。

> 2026-10-17 批 2 复核更正：批 1 的未加权计数有误（45/92；实测表内逐条点算为 46/92 ——
> 能力单元 41→42），本表按**点算值**记（53/92；加权账不受影响，权重与系数全对得上）。

---

## 加权汇总

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| 内置工具（9） | 21 | 21.0 | **100%** | 100% |
| 能力单元（除工具，81） | 159 | 159.0 | **100%** | 100% |
| **模块合计（90）** | **180** | **180.0** | **100%** | **100%** |

**模块合计**：Σ权重 180 ／ Σ(w×c) 180.0 ／ **加权完成度 = 180/180 = 100%**（未加权 100%；2026-10-18 批 5：+14.5，#60 出分母 Σw −2——166.5−1.0+14.5 = 180.0；设计稿 docs/25）。

按权重分层（能力单元表，81 条）：

| 权重 | 条数 | Σ权重 | 对齐 | 存疑 | 缺失 | Σ(w×c) | 层完成度 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 3 | 22 | 66 | 22 | 0 | 0 | 66.0 | **100%** |
| 2 | 34 | 68 | 34 | 0 | 0 | 68.0 | **100%** |
| 1 | 25 | 25 | 25 | 0 | 0 | 25.0 | **100%** |
| 合计 | 81 | 159 | 81 | 0 | 0 | 159.0 | 100% |

### 权重 3 的单元清单（22 条能力单元 + 5 条工具 = 27 条，批 3 起 **27/27 = 100%**）

| 单元 | 判定 |
|---|---|
| `bash` 工具 | 对齐（批 3） |
| `read` / `write` / `edit` 工具 | 对齐（3 条） |
| `grep` 工具 | 对齐（批 3） |
| #1 `AgentEvent` 变体 | 对齐 |
| #2 双循环 | 对齐 |
| #3 `runAgentLoop`/`continue` 前置条件 | 对齐 |
| #6 `finishTurn`（原 `shouldStopAfterTurn`） | 对齐 |
| #7 `prepareNextTurn` + `AgentLoopTurnUpdate` | 对齐 |
| #8 `transformContext` | 对齐 |
| #9 工具两相端口与 end 次序 | 对齐 |
| #10 并行/顺序批次选择 | 对齐 |
| #16 轮内阈值压缩门 | 对齐 |
| #18 压缩后重建工作副本 | 对齐 |
| #20 上下文计量 | 对齐 |
| #23 输出截断 | 对齐 |
| #26 per-model 压缩设置 | 对齐 |
| #41 `should_stop`/`prepare_next_turn` 钩子 | 对齐 |
| #46 `AgentSessionEvent` 面 | 对齐（批 2） |
| #51 `compaction_start`/`compaction_end` 事件 | 对齐 |
| #52 `agent_end` 载荷 | 对齐（批 2） |
| #54 Entry union | 对齐（批 2 裁决） |
| #58 `convertToLlm` | 对齐（批 2，剔除已删） |
| #73 JSONL 存储（v3 平铺） | 对齐 |
| #78 `latest()`/`find()` 会话定位范围 | 对齐（批 2 裁决） |
| #100 系统提示作为 system 消息 | 对齐（批 2 裁决） |

**权重 3 层 = 22 对齐 / 0 存疑 / 0 缺失 = 100%**（2026-10-17 批 agent-core-2 清零）。

### 未加权 vs 加权的差（~~75.8% → 91.5%~~ ⇒ 批 5 后 **100% = 100%，差 0**）

✅ **2026-10-18 批 5：存疑清零** —— 未得分池（Σw−Σ(w×c)）= **0**。批 4 后的 15.5 分布
（w2 9.0／w1 6.5）全部来自存疑半分，批 5 逐条收编：13 条做（#57 族 3 commit、#77、#75、#87、
#89/#96、#94、#95、#81/#83/#85/#92/#93）、8 条裁决对齐（#14/#53/#56/#80/#82/#84/#90/#91）、
1 条 R1 出分母（#60，Σw 182→180）。**权重 2 层与权重 1 层同批清到 100%。**

### 七条点名项（✅ 2026-10-17 批 agent-core-2 全数闭环；基线 138.5 → 148.5/183 ≈ **81.1%**，+10.0）

| 条目 | 权重 | 闭环内容 |
|---|---:|---|
| **#34 `prepareRequest` 写钩子**（存疑→对齐，+1.0） | 2 | `PiLoop` 三类型＋Config 槽（每次请求前调、context 整体替换、config 写回本 run 生效）；`HookSystem.firePrepareRequest` 链式合并；老 void `before_request` 与 `PiLaneSink.systemPrompt` 字段删除 |
| **#46 `AgentSessionEvent` 面**（存疑→对齐，+1.5） | 3 | 补五帧（agent_start/turn_start/turn_end/message_start/message_end）＋`AgentSettled{aborted}` 真形状＋`UserMessageReceived` 删；`SessionRunner` 单一漏斗全量转发（pi `:1139-1142` 同形）；RPC 五新帧；web 直译化＋`transcriptSource` 吸收整表替换 |
| **#52 `agent_end` 本 pass 载荷**（存疑→对齐，+1.5） | 3 | 会话层透传引擎 `newMessages`（不再发 `accumulatedMessages()` 全历史）；A8 销号 |
| **#54 Entry union**（存疑→对齐·裁决，+1.5） | 3 | 消解：零 Entry 身份消费者＋线格式原生读写＋fact 链进树 ⇒ 行为等价；裁决进 docs/04 §4 |
| **#58 `convertToLlm`**（存疑→对齐，+1.5） | 3 | case 合并部消解（裁决）；**stopReason 剔除删除**（参照物 `isContextMessage` 已删 ⇒ R1，error/aborted 恒投影） |
| **#78 `latest()`/`find()` 范围**（存疑→对齐·裁决，+1.5） | 3 | latest/resume 等价；find 跨项目裁决保留；排序键差（lastActivity vs mtime）留痕进 docs/04 §4 |
| **#100 系统提示载体**（存疑→对齐·裁决，+1.5） | 3 | pi 无会话中变更路径（`setSystemPrompt` 零命中）；provider 边界 `ContextNormalizer` 同点折叠 ⇒ 请求线等价；`Context.java` 类注重写；裁决进 docs/04 §4 |

### 十一条点名项（✅ 2026-10-18 批 agent-core-4 全数闭环；基线 153.5 → 166.5/182 ≈ **91.5%**，+13.0；设计稿 docs/24）

| 条目 | 权重 | 闭环内容 |
|---|---:|---|
| **#62 `/skill:` 展开**（缺失→对齐，+2.0） | 2 | `SkillCommandExpansion`（裁决④按 `_expandSkillCommand` 内联形状落地）＋processPrompt/steer/followUp 三接线＋`MarkdownSkill.filePath`；slash 命令注册挂 B244 |
| **#63 `loadSkills` 面**（存疑→对齐，+1.0） | 2 | 同名先到赢（pi 序，语义反转留痕 docs/24 §14）＋realPath 去重＋来源推断＋collision/警告诊断；归属维持 coding-agent（裁决②） |
| **#64 `loadPromptTemplates` 面**（存疑→对齐，+1.0） | 2 | `argumentHint`/`filePath`、`loadDefaults` 两默认目录、单正则五支 `substituteArgs`（JS 空串 falsy）、缺失模板静默；归属同裁决② |
| **#24/#25/#27 branch summary 族**（缺失→对齐，+3.0） | 1×3 | `BranchSummarization`（收集/两遍扫/三态，PROMPT 逐字）＋`navigateTree`（`replaceTranscript` 整体换上下文）＋`Settings.BranchSummary`；B1 销号；扩展事件＋TUI 摘要流挂 B245 |
| **#48 `session_info_changed`**（缺失→对齐，+1.0） | 1 | setSessionName 归一化＋改名才发；B30 销号 |
| **#55 `fromHook` 字段**（缺失→对齐，+1.0） | 1 | Entry 两 record +`Boolean fromHook`（NON_NULL ≙ undefined）＋codec＋`CompactionFiles` 守卫；`FileOperationLists` 抽出（两侧共用） |
| **#69 `setDefaultStreamFn`**（缺失→对齐，+1.0） | 1 | `DefaultStreamFn` 持有器＋`HarnessConfig.build()` 回落＋assemble 安装点 |
| **#79 列表 `onProgress`**（缺失→对齐，+1.0） | 1 | `RichSessionLister`：progress/AbortSignal/虚拟线程并发；B55 销号 |
| **#98 `abortCompaction`**（缺失→对齐，+1.0） | 1 | `CompactionAbortToken` per-op 双槽＋`abortBranchSummary`；B36 销号 |
| **工具 #8 powershell**（缺失→对齐，+1.0） | 1 | `PowerShellTool` 六面逐字＋`createAllTools` 注册＋默认不激活（裁决③，激活面随 B236）；B243 销号 ⇒ 工具表 **21/21 = 100%** |
| **#68 `streamProxy`**（缺失→权重 0，±0） | 1→0 | 裁决① R5：pi 侧存活但 bin pi 主流不可达 ⇒ 出分母（Σw 183→182、92→91）；触发条件 docs/24 §13.1 |
| **B242 PI_\* 环境注入**（工具族随批，不在单元表） | —— | `SessionEnvironment` 晚绑定通道＋先删后设注入＋`exposeSessionEnvironment` 闸＋guideline 段 |
| **B53 find 阶梯**（工具族随批，挂在 #78 行） | —— | 路径快车道＋header-only findById＋项目前缀＋跨项目精确胜前缀 |

### 二十二条点名项（✅ 2026-10-18 批 agent-core-5 全数闭环；基线 166.5 → **180/180 = 100%**，+14.5、#60 出分母 Σw 182→180；设计稿 docs/25）—— **存疑清零，模块 100%**

| 条目 | 权重 | 闭环内容 |
|---|---:|---|
| **#57 bashExecution 面**（存疑→对齐，+1.0） | 2 | 最大件：`Entry.BashExecution` 变体（线形 `type:"message"`＋role 判别）＋jsonl 编解码（pi 文件互读断修复）＋投影 `bashExecutionText` 逐字（!! 跳过）＋`recordBashResult`＋pending settled flush＋`appendLaneEntry`；窄差 D-注（exclude 不占工作副本位、会话级 bash 无截断落盘） |
| **#77 navigateTree 工具恢复**（存疑→对齐，+1.0） | 2 | `RunLifecycle.restoreToolsFromTranscript`（新叶系统消息 `toolsAdded` → 注册表过滤恢复激活装配）＋navigateTree 接线（pi `:4138` 同序）；CLI resume 臂 pi 也不可达（sdk 恒给 `initialActiveToolNames`） |
| **#75 fork 调用面**（存疑→对齐，+1.0） | 2 | fork 支配并消费 `--session-id`（pi `main.ts:380-386`）；删 java 私加 `" (fork)"` 名后缀；F-d 错误形窄差登记 |
| **#87 L5 thinking 帧对称**（存疑→对齐，+0.5） | 1 | 修双 twin（pi 侧补 thinking 分支、java 补 delta）＋S16 剧本对拍零 diff（vitest 16/16、ConformanceTest 16/16） |
| **#89 join 源序夹具**（存疑→对齐，+0.5） | 1 | 三调用闩锁完成序 3→1→2：end 完成序／结果源序／terminate 随源序（every） |
| **#96 错误路 AgentSettled 夹具**（存疑→对齐，+0.5） | 1 | 错误路 `TurnEnd.toolResults` 恒空（pi `:253`）＋`AgentSettled{aborted}` 不改写 |
| **#94 拆超限文件**（存疑→对齐，+0.5） | 1 | `PiLaneSink` 562→470（`PiLaneRequestTelemetry`）；`AgentHarness` 643→438（`HarnessBootstrap` 基类）；行为零变化 |
| **#95 字节精确假 shell**（存疑→对齐，+0.5） | 1 | 3 字节字符第 2 字节钉 8192 泵边界（stdout/stderr）；泵审计无 bug |
| **#81 工具并发审计**（存疑→对齐，+0.5） | 1 | 审计表＋`AgentTool` 契约注释；**挖出修真**：edit/write 队列统一为 `FileMutationQueue.shared()`（pi 模块级单例） |
| **#83 transcript 消费者清点**（存疑→对齐，+0.5） | 1 | 全走存储级新解码或快照 COW，无未核裸读 ⇒ 销号 |
| **#85 recordEvent 兜底**（存疑→对齐，+0.5） | 1 | javadoc＋夹具（name/payload 在、spanId/traceId 键缺席） |
| **#92/#93 javadoc 两枚**（存疑→对齐，+1.0） | 1×2 | `TelemetryContext` 三处按现状重写；`truncating()` 是有消费者的兜底默认（保留＋javadoc 重写） |
| **#14 QueueMode 宿主语义**（存疑→对齐·裁决，+1.0） | 2 | drain/默认/setter/设置同步四面 ≡（docs/04 §4） |
| **#53 turn_end 颗粒度**（存疑→对齐，+1.0） | 2 | **B49 销号**：pi 会话事件 union 本就含逐回合 turn_end，批 2 已对齐；窄差 `parentToolCallId` 包装（无嵌套代理面） |
| **#80/#84 两道门**（存疑→对齐·裁决，+2.0） | 2×2 | 抛文逐字＋展开先于门＋queue_update；pi 无窗概念、入口抛与关窗抛同效（docs/04 §4） |
| **#82 retry.provider 三键**（存疑→对齐·裁决，+1.0） | 2 | `maxRetries ?? 0` 两侧同；`maxRetryDelayMs ?? 60000`；`timeoutMs` 明给替换（`httpIdleTimeoutMs` 属 E2-48 另案） |
| **#56 EntryProjector**（存疑→对齐，+0.5） | 1 | `ProjectedEntry` ≡（含同款 compaction 守卫）；便捷返回无消费者免登 |
| **#90 transcript 可见性**（存疑→对齐·裁决，+0.5） | 1 | 写者唯一＋快照 COW；裸读面不存在 |
| **#91 reset/runSpan**（存疑→对齐·裁决，+0.5） | 1 | `finishRun` finally 必关；reset 置 null 是防御（注释补强） |
| **#60 PendingEntry/InboxItem**（存疑→权重 0，±0） | 2→0 | **R1 作废**：参照物随 pi `7fd478a2e` 删除 ⇒ 出分母（Σw 182→180、91→90） |

### 三条点名项（✅ 2026-10-17 批 agent-core-1 全数闭环；基线 132.0 → 138.5/183 ≈ **75.7%**）

| 条目 | 权重 | 闭环内容 |
|---|---:|---|
| **A4 `/compact` 缺 abort-first**（#17，缺失→对齐，+2.0） | 2 | coding-agent `AgentSession.compact` 三手动入口 abort-first＋`harness.waitForIdle`；harness 零改动（abortCompaction 仍 B36） |
| **#23 截断字节预算 →50KB**（B158，存疑→对齐，+1.5） | 3 | `TruncationUtils.DEFAULT_MAX_BYTES` 100_000→51_200，描述/注记全链现算；快照与终局两口径合一 |
| **#6 `finishTurn` 形状对齐**（B159，存疑→对齐；含 #41 共 +3.0） | 3×2 | `TurnDecision`＋`FinishTurnHook`（turn_end 前调用、错误路照调）、`explicitContinuation` 三段清零、`NextTurnContext.newMessages`、`HookSystem.fireFinishTurn` 合并（END 先赢否则 CONTINUE，FALSE 方言删） |

---

## 本锚判定翻转（相对旧图）

| # | 旧判定 | 新判定 | 依据 |
|---|---|---|---|
| **#73** JSONL 存储 | 存疑（形状，v4 vs v3） | **对齐** | pi-java 落 pi v3 原生线：`PiV3Wire` 头、label/session_info/usage/context_edit 原生条目、消息原生拼写、方言行挂 custom 并链接入树、legacy 头惰性迁移；`PiV3WireFormatTest`/`PiNativeSpelling*Test` 双向夹具（2026-10-04/05，包 12–14） |
| **#102** reset 基线 system | 存疑 | **对齐** | **B70 已取证消解**：载体不同（pi 首条消息 vs java `Context.systemPrompt` 字段）、行为一致（清空工作副本不丢基线，下次请求重注入） |
| **#86** `_emitSessionCompactFailed` | 存疑（F3） | **作废（R1）** | pi 该方法**只**发扩展事件 `session_compact_failed`（归 04）；公开失败事件是 java 已齐的 `compaction_end{errorMessage}`；java 源码注释同裁决 |
| **#88** `ActiveToolsChange` | 存疑（C1） | **作废（R1）** | `active_tools_change` 在 pi 两锚全树零命中；java 早已据 R2 删变体（`Entry.java:23-24`） |
| #54 计数 | 11 vs **7** | 11 vs **9**（批 2 裁决：行为等价） | java 内存 union 9 record；label/session_info 以 fact 存活、线格式原生读写 ⇒ 磁盘全互通（docs/04 §4） |
| #46 计数 | 24 vs 20 | **24 vs 24**（批 2 对齐） | 补五帧＋`agent_settled{aborted}` 真形状；java 另有 `FlashStatus` 方言（TUI 专用，不上 RPC 线） |

### 新缺口（只报告，本图不登记编号）

1. ~~**工具耗时 `durationMs`**~~ —— ✅ **销号（2026-10-17 批 agent-core-2 复核）**：事件（`PiLoop.java:74-88`／`AgentSessionEvent.java:148-161`）、消息（`Message.java:386-395`）、单调钟（`ToolRegistry.java:150-164`，`System.nanoTime` 只围本体）、生产链（`PiToolRunner`→`PiLoopTools`→事件）与两条消费链（`JsonEventMapper`、`AgentEventTranslator`＋`WebWireJson`）全部在案；null⇒键省略一致。（B234 本体已于 ai 批 5 落地。）
2. ~~**`agent_settled.aborted`**~~ —— ✅ **销号（批 agent-core-2，随 #46 收编）**：`AgentSettled{aborted}` 真形状（pi `agent-session.ts:203`），旗源 `agentRunAbortRequested`（abort() 置位、驱动起手清零、收尾读值）；B235 同步销号。
3. **`--tools` 模式匹配与 MCP 保留**：pi allow/deny 名单改 glob 模式匹配（`createToolNameMatcher`）、新增 `defaultToolModifiers`（`+name/-name`）、空名单/`mcp__*` 名单下 MCP 工具仍注册（`core/agent-session.ts:271-289,501-510,1533-1557,3662-3690`）；java 侧为精确名集合，未核 —— **在册 B236**（docs/04 §3.3），归 coding-agent 批。
4. ~~`streamProxy` 返型改为 pi-ai 工厂 `createAssistantMessageEventStream()`~~ —— ✅ **销号（2026-10-18 批 4 裁决①）**：#68 按 R5 出分母（bin pi 主流不可达）；若触发条件成立重裁，对齐时照新返型。

---

## 口径备注（可被推翻）

- **钩子作废只作废「harness 专有」的**：凡行为在 `agent/src/*` 存活（`beforeToolCall`/`afterToolCall`/
  `transformContext`/`prepareNextTurn`/`finishTurn`/`getFollowUpMessages`）的一律改指保留；
  harness 有、主流只在**扩展事件**里重生的作废并注记去向 04。
- **`setDefaultStreamFn`（#69）已对齐**（批 4：`DefaultStreamFn`＋`HarnessConfig` 回落＋assemble 安装点）；
  **`streamProxy`（#68）批 4 改判作废（R5）**——pi 侧虽逐字存活，但 bin pi 主流不可达（裁决①，docs/24 §13.1）。
- **#62 已对齐**（批 4）：pi 参照改指 `agent-session.ts:2149-2162` 的 `_expandSkillCommand`（harness 旧
  `formatSkillInvocation` 已删）；java 按内联形状落 `SkillCommandExpansion`（裁决④）。
- **#73 的对齐强度**：双向可读由合成夹具（`PiV3WireFormatTest` 等）保证，未拿真实 pi 进程跑往返；
  lane/record 方言行以 pi `custom` 条目挂载并链入树。
