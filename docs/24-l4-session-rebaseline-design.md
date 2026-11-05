# 24 - L4 session / SQLite 新锚点重测与对齐设计（W1–W4）

> 【背景摘要】按 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md) §5 的 L4 范围，对会话持久化面（map/06 的存储后端 20 行 + JSONL 会话语义）按新锚点 `56cae7823` 重新取证。重测发现四组差异：恢复会话的 active tools 装配、compaction 摘要的输出上限与 thinking 省略、auto-retry 省略未与工具结果成对、以及显式 `--session-dir` 的目录布局。其中前三组由 L3 的 #77／#24 和本层重测直接落为 B271–B274；第四组是重测中新发现的用户可见差异，拟登记 B278。
>
> 文档状态：**已批准（2026-11-05）**。用户裁决：W4c（B278）纳入本包；map/06 #6 改判对齐并登记 D218。设计通过后进入实施。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。pi-java 代码提交：`9966a1f5`（main，2026-11-05；L3 docs/23 关闭后）。旧基线：pi `ea448f454`，pi-java `edc8749d`（map/06 旧读数）。

## 0. 范围、口径与术语

- **层范围**：`pi-java-agent-core` 的 JSONL/Memory 会话存储与压缩语义、`pi-java-coding-agent` 的会话恢复/分支/摘要装配，以及 `pi-java-session-backend-sqlite` 的**行为语义**回归。map/06 的评测域归 L6，chord 与多进程子系统按 R5 不进分母，防漂移脚本口径外。
- **计分口径**：权重＝用户可观察影响 × 频率（3/2/1/0）；完成系数 对齐 1.0／存疑 0.5／缺失 0。R1（pi 已删参照物）与 R5（`bin pi` 不可达）权重 0、不进分母。转出行保留证据、权重 0，也不进未加权分母（docs/22 先例）。
- **判定三档**：对齐／存疑／缺失。旧锚点已对齐的行必须重新取证。
- **术语**：
  - **entry 日志（transcript）**：会话文件里按 parentId 串成的原始条目链，含压缩条目与被 context_edit 省略的原文。
  - **投影（projection）**：按最新叶与 context_edit／compaction 规则算出的「模型实际看到的上下文」。
  - **active tools（激活工具集）**：当前声明给模型的工具；未激活的工具模型看不到。
  - **pending tools（待注册工具名）**：已被会话记录为激活、但注册表里尚未出现的工具名；工具稍后注册（如 MCP 服务器连上）时自动激活。
  - **摘要（summary）**：compaction 时由 LLM 生成的旧上下文压缩文本；它本身会作为上下文进入后续请求。
  - **reserveTokens**：为输出预留的 token 预算；本包只保留它参与「可用输入预算」计算，不再参与摘要输出上限。
- **模型可见面**：工具声明、摘要请求中的会话文本、摘要结果、下一轮请求的工具结果集合。内部代码路径标【内部，模型不可见】。

## 1. 新锚点重测结论

### 1.1 方法

1. 用 `git diff --stat ea448f454..56cae7823` 枚举 L4 归属路径的全部变化。
2. 逐文件读新锚点源码，把每个变化按 docs/08 的层归属分类：L4 实施／已由前包对齐／L5 或 L7／R5。
3. map/06 存储后端 20 行逐行重判；只改由新锚点证据真正影响的判定。
4. 对旧裁决（B59、D190–D194、#6 lane 粒度）与 docs/08 §5 L4 点名的惰性创建、v1/v2 迁移、compaction `systemMessage` 单独复核。

### 1.2 变化清单与归属

`ea448f454..56cae7823` 中落在 L4 归属面的文件：

| pi 文件 | 变化 | 归属 | 结论 |
|---|---|---|---|
| `packages/coding-agent/src/core/session-manager.ts` | 41 行：`pathsEqual` 五处比较、`_appendEntry` 失败回滚、fork 单 fd 写 | **L4（W4）** | 见 §5 |
| `packages/coding-agent/src/core/compaction/compaction.ts` | 47 行：摘要去掉 `maxTokens`、turn-prefix 接 `/compact` 指令 | **L4（W2）** | 见 §3 |
| `packages/coding-agent/src/core/compaction/utils.ts` | 14 行：`serializeConversation` 不再输出 thinking | **L4（W2）** | 见 §3 |
| `packages/coding-agent/src/core/compaction/branch-summarization.ts` | 12 行：去掉 4096 上限、新增 `thinkingLevel` | **L4（W2）** | 见 §3 |
| `packages/coding-agent/src/core/agent-session.ts` | 257 行：恢复工具、省略成对、摘要鉴权、run 启动、loadout 顺序 | **L4（W1/W3/摘要鉴权）**；`_startRun`／`_prepareRun`／`sendCustomMessage` 归 **L5**；`_applyToolLoadout` 顺序与 `getAllTools().reachesTools` 归 **L5（B276）** | 见 §2、§4 |
| `packages/coding-agent/src/core/sdk.ts` | 30 行：identity 已是 docs/14；`markRuntimeStreamFunction` 归 **L4 摘要鉴权复核**；`buildRequestOptions` 去掉未用参数为内部整理 | 复核 | 鉴权已由 Java streamFn 自取，无改动（§3.2） |
| `packages/coding-agent/src/core/settings-manager.ts` | 28 行：`sessionShareDestination`、`extensionChangelogVersions` | **L5（B277）** | 不属 L4 |

> `packages/ai/src/utils/transcript.ts` 的 `getCurrentTools` 在区间内无变化，但它是 B271 的复用点（§2）。

### 1.3 map/06 存储后端 20 行逐行重判

判定基准：pi `session-manager.ts`（新锚点）与 Java `pi-java-agent-core/.../session/`、`pi-java-session-backend-sqlite/`。SQLite 仍按 B59 结论：参照物不存在（R1＋R5），不进分母。

| # | 能力 | 新锚点证据 | Java 现状 | 新判定 |
|---:|---|---|---|---|
| 1 | create 建会话 | `newSession:1057`（写头 `:1063-1070`）；`create:1765` | `SessionRepository.create:18`；`JsonlSessionRepository.create:47-65` | 对齐 |
| 2 | list 列会话 | `static list:1915`；`listAll:1936-1942` | `SessionRepository.list:27`；`JsonlSessionRepository.list:72-95` | 对齐（显式目录的 cwd 过滤见 W4c） |
| 3 | list 进度回调＋节流＋中止＋并发 | `SessionListProgress:884-889`；节流 `:893`；`listSessionsFromDir:941-971`；信号 `:879/:911/:946/:971` | `RichSessionLister`（B55 批 4 已闭环） | 对齐 |
| 4 | open 打开会话 | `static open:1776` | `JsonlSessionRepository.open:67-70` | 对齐 |
| 5 | fork 分支会话 | `forkFrom:1825`；`createBranchedSession:1642`；新锚点改为单 fd 写 `:1868-1877` | `JsonlSessionRepository.fork:148-172`；`JsonlSessionStorage.fork:139-154` | 对齐；单 fd 为 W4b |
| 6 | 分支创建／移动 | `branch:1589`／`resetLeaf:1601`／`branchWithSummary:1610` | `SessionStorage.createLane:30`／`moveLane:33`；`TreeCompaction` 走 `navigateTree` 同序 | **对齐（形状不同）**，拟登记 D218（§1.5） |
| 7 | 追加 entry | `appendMessage:1214` 等六方法；`_persist:1172`；新锚点 `_appendEntry:1191-1206` 失败回滚 | `SessionStorage.appendEntry:41`；`JsonlSessionStorage.appendEntry:204-220`（先写文件后改内存） | 对齐；回滚语义见 W4a |
| 8 | compaction 条目 | `appendCompaction:1271`；`CompactionEntry:91-104`（含 `systemMessage`） | `Entry.Compaction` 十二组件齐；`CompactionSystemMessageTest` | 对齐 |
| 9 | 分支摘要条目 | `branchWithSummary:1610`；`BranchSummaryEntry:106-118` | `Entry.BranchSummary:170-179` | 对齐 |
| 10 | compaction-aware 投影＋provenance | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `ContextEntries.projectEntries:164-214`＋`ProjectedEntry` | 对齐（形状不同） |
| 11 | `context_edit` 条目 | `ContextEditEntry:175-182`；`appendContextEdit:1370`；`projectContextEntry:519` | `Entry.ContextEdit`；`EntryJsonCodec`；`ContextEntries.applyEdit`；`RecoveryOmissions` | 对齐（覆盖语义见 W3） |
| 12 | 会话名 | `appendSessionInfo:1314`／`getSessionName:1328`；`SessionInfoEntry:142-147` | `SessionStorage.getName/setName`；JSONL 原生 `session_info`（`PiV3Wire`） | 对齐 |
| 13 | entry 标签 | `getLabel:1442`／`appendLabelChange:1451`；`LabelEntry:135-141` | `SessionStorage.getLabel/setLabel`；JSONL 原生 `label` | 对齐 |
| 14 | usage 条目＋汇总 | `UsageEntry:80-90`；`appendUsage:1254` | `Entry.Usage` 九键＋审计扩展键；`SessionStats` | 对齐 |
| 15 | JSONL 头部线 | `SessionHeader:43-56`；`CURRENT_SESSION_VERSION:41`；写点 `:1063-1070`；找头 `:1086` | `PiV3Wire.encodeHeader:75-94`；读 `:97-121` | 对齐 |
| 16 | JSONL 条目线 | `SessionEntryBase:57-63`（无 `seq`）；`SessionEntry:183-197` | `PiV3Wire.encodeEntryLine:139-158`；`lane`／`retainedTail` 为 pi 忽略键（D190） | 对齐 |
| 17 | v1→v2→v3 迁移 | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`；读后 `_rewriteFile:1124-1133` | `LegacyPiVersionMigrator`＋`JsonlSessionStorage.load:77-136` | 对齐（D192–D194） |
| 18 | 文件名／目录名 | `fileTimestamp:1079`；`getDefaultSessionDirPath:589-594` | `JsonlSessionRepository.sessionFileName:300-303`；`sessionDirectoryName:295-298` | 对齐（默认布局）；显式目录见 W4c |
| 19 | 会话文件惰性创建 | `_hasConversation:1166`；`_persist:1172-1190` | `JsonlSessionStorage.create`＋`pending`＋`flushIfConversationStarted:339-349` | 对齐（D191） |
| 20 | 内存后端 | `inMemory:1814` | `MemorySessionStorage:31`＋coding-agent `InMemorySessionRepository` | 对齐 |

**重测读数（实施前）**：对齐 19 行、存疑 1 行（#6）、缺失 0；Σ权重 44，Σ(w×c)＝43.0 ⇒ **97.7%**。若 #6 按 §1.5 改判，则 **44.0/44＝100%**。

### 1.4 L4 新发现：显式会话目录布局分叉（拟 B278）

- 【场景】pi 的 `--session-dir`／`settings.sessionDir` 给的是**会话文件直接所在目录**（`SessionManager.create(cwd, dir)` 把文件直接写进 `dir`）；默认不指定时才用 `~/.pi/agent/sessions/--<编码后的 cwd>--/`。Java 把 `--session-dir` 当作**根目录**，始终再拼一层 `--<编码后的 cwd>--/`。
- 【决定】拟在本包 W4c 对齐：显式目录 ⇒ 平铺写文件，并在 `list`／`findById`／`continueRecent` 按 cwd 过滤（pi 的 `pathsEqual` 比较，§5）。默认路径模式不变。
- 【风险/代价】[模型可见] 用户把 pi 与 pi-java 指向同一 `--session-dir` 时，此前二者互不可见；对齐后可互读。代价是约 25 处测试夹具的 handle 构造需要改用显式目录形态（§5.4），且旧版 Java 落在 `dir/--cwd--/` 的历史文件不会被新布局自动发现。
- 归属：**新 B278，待用户在本稿审核时批准或驳回**。若驳回，本包只做 W4a／W4b，B274 的 `pathsEqual` 子项记录为「Java 当前架构无对应比较点」并挂账。

### 1.5 旧裁决复核

| 裁决 | 新锚点复核 | 结论 |
|---|---|---|
| B59 SQLite 不进分母 | `packages/session-backends` 仍不存在；`bin pi` 无 SQLite | 继续有效 |
| #6 lane 粒度（旧「存疑」） | pi 的 `branch`／`resetLeaf`／`branchWithSummary` 语义由 Java `moveLane`／`resetLeaf`／`branchWithSummary`＋`TreeCompaction` 全链覆盖；`lane` 键是 pi 忽略的扩展键（D190） | **改判对齐（形状不同）**，拟登记 **D218** |
| D190 条目扩展键 | 新锚点 `parseSessionEntries` 仍只 `JSON.parse`，`lane`／`retainedTail` 被忽略 | 继续有效 |
| D191 惰性创建程序化面 | `_persist:1172-1190` 结构未变；首条 user/assistant 才落盘 | 继续有效 |
| D192–D194 迁移边界 | `migrateV1ToV2`／`migrateV2ToV3` 未改；Java 的显式 >3 报错保留 | 继续有效 |
| compaction `systemMessage` | `appendCompaction:1280` 仍取 `getCurrentSystemMessage` 快照 | 继续有效 |
| docs/08 L4 点名的「惰性创建、v1/v2 迁移」 | 行 #17／#19 证据未变，仅行号平移 | 对齐维持 |

### 1.6 L3 转入的两行（权重 0）

| 行 | 单元 | 权重 | 新锚点变化 | L4 处理 |
|---|---|---:|---|---|
| map/03 #77 | 会话恢复（active tools） | 0（转出） | 构造器门 `:538`；恢复方法 `:1851-1882` 重写 | **W1（B271）** |
| map/03 #24 | branch summary 生成 | 0（转出） | `thinkingLevel` 入参＋去 4096 上限（`branch-summarization.ts:79,305,353-356`） | **W2（B272）** |

这两行在 map/03 保留证据、判定「转出（L4）」，不重新计入任何分母；map/06 只做指针与状态记录。

---

## 2. W1（B271）：恢复会话的 active tools

### 2.1 pi 事实（`core/agent-session.ts`，逐字）

构造器门（`:538`）：

```ts
		if (this._usesDefaultTools) this._restoreToolsFromTranscript(this._defaultToolModifiers);
```

恢复方法（`:1851-1882`）：

```ts
	private _restoreToolsFromTranscript(modifiers: readonly string[] = []): void {
		this._pendingToolNames.clear();
		// All system messages on the branch, including those before a compaction. Compaction snapshots
		// are derived from these, so replaying them gives the current loadout.
		const systemMessages = this.sessionManager
			.getBranch()
			.flatMap((entry) => (entry.type === "message" && entry.message.role === "system" ? [entry.message] : []));
		// No recorded loadout: keep the current tools.
		if (systemMessages.length === 0) return;
		// Tools the branch ever added or removed. Removed tools stay inactive.
		const mentioned = new Set(
			systemMessages
				.flatMap((message) => [...(message.toolsAdded ?? []), ...(message.toolsRemoved ?? [])])
				.map((tool) => tool.name),
		);
		// Extension tools the branch never mentioned were registered after it recorded its loadout, so
		// they are activated like newly registered tools. Built-in tools follow `defaultTools` instead.
		const newTools = [...this._toolRegistry.keys()].filter(
			(name) =>
				!mentioned.has(name) && !this._baseToolDefinitions.has(name) && this._isActivatedOnRegistration(name),
		);
		// The recorded loadout plus the new tools, with `--tools +name`/`-name` applied.
		const names = applyToolModifiers(
			[...getCurrentTools(systemMessages).map((tool) => tool.name), ...newTools],
			modifiers,
		);
		// Recorded tools that are not registered yet, such as MCP tools of servers still connecting, are
		// activated when they register. Excluded tools never register, so they are not kept pending.
		this._pendingToolNames = new Set(names.filter((name) => this._isAllowedTool(name)));
		// Ignores names that are not registered.
		this._setActiveTools(names);
	}
```

`getCurrentTools`（`packages/ai/src/utils/transcript.ts:58-67`）：按 branch 顺序重放所有 system 消息，先删 `toolsRemoved` 再按名覆盖 `toolsAdded`，返回保序的当前工具表。

`_setActiveTools`（`:1563-1567`）：对 `_applyToolLoadout(names)` 的结果逐件从 `_pendingToolNames` 删除，然后重建提示。

### 2.2 Java 现状

- `RunLifecycle.restoreToolsFromTranscript`（`pi-java-agent-core/.../harness/RunLifecycle.java:346-364`）只读**最新一条** system 消息的 `toolsAdded`；忽略 `toolsRemoved`、历史 system 消息、修饰符与晚注册扩展工具。
- 调用点只有树导航 `TreeCompaction.java:276`；**会话恢复（`-c`／`-r`／`/resume`）从不恢复工具**。`SessionPersistence.attachOpened:100-106` 只播种 transcript。
- 车道激活集是 `Set`（`HarnessPromptAndToolAccessors.java:80-113`），没有 pending 的「允许名」口径。
- 已有可复用件：`Transcripts.getCurrentTools`（`pi-java-ai/.../api/Transcripts.java:109-123`）逐字对齐 pi；`ModelToolControl.isActivatedOnRegistration`（`:292-298`）；`ToolSelection.applyToolModifiers`（`:46-61`）；`ToolSelection.Resolved.usesDefaultTools/defaultToolModifiers`（`:137-144`）。

### 2.3 改动

1. 在 `ModelToolControl` 新增 `restoreToolsFromTranscript(List<Entry> branchEntries, List<String> modifiers)`：
   - 清空 `pendingToolNames()`。
   - 从 `branchEntries` 取全部 `Entry.Message` 里的 `Message.SystemMessage`（**原始分支路径**，含压缩前的 system 消息；不能读 `lane.messages` 投影，投影已裁掉压缩前缀）。
   - 无 system 消息 ⇒ 直接返回（保留当前工具）。
   - `mentioned` ＝所有 system 消息的 `toolsAdded`＋`toolsRemoved` 名并集。
   - `newTools` ＝注册表名 − mentioned − 内置名 − `isActivatedOnRegistration` 为假者。
   - `names` ＝ `applyToolModifiers(getCurrentTools(systemMessages) 的名字序列 ＋ newTools, modifiers)`。
   - `pendingToolNames().addAll(names.filter(selection.isAllowed))`；随后对 loadout 实际激活的工具名做 `removeIf`（**不能**直接清掉所有已注册名：隐藏但已注册工具在 pi 里保留 pending，等 exposure 变更后再激活）。
   - 走新抽出的 `applyToolLoadout(names)`（从 `setActiveToolsByName:185-208` 提取「exposure≠hidden 且 isActivatable」的过滤＋`setActiveTools`）。`setActiveToolsByName` 的「减员清 pending」规则只留给扩展 `setActiveTools` 路径，恢复路径不继承。
2. 新增 `ModelToolControl.baseToolNames(Set<String>)`：由 `SessionWiring` 用内置 `toolList` 名集回填；fork 重装走同一 `wireSession`，自动覆盖。内置名是「跟随 defaultTools、不因未提及而自动激活」的判据。
3. 调用点：
   - `SessionPersistence.attachOpened`：seedTranscript 后，若 `toolSelection != null && usesDefaultTools()`，用 `ContextEntries.pathToLeaf(entries, leafId)` 的**原始路径**恢复，modifiers＝`defaultToolModifiers()`。
   - `TreeCompaction`：把 `entries` 的 `pathToLeaf` 原始路径传入，modifiers 为空（pi `navigateTree:4219` 无参调用）。
   - `SessionDiscoveryFork.forkInMemory:393-397`：播种后同样按 usesDefaultTools 门恢复。
   - 删除 `RunLifecycle.restoreToolsFromTranscript` 与 `AgentHarness` 的转发（唯一调用点已迁走），避免两套语义并存。
4. `AgentSession` 增加包内转发 `restoreToolsFromTranscript(List<Entry>, List<String>)` 与 `usesDefaultTools()`，供同包调用者使用。

### 2.4 测试（RED-first）

`RecoverToolsOnResumeTest`（新建，coding-agent）：

- 旧系统消息在压缩前：resume 后即便最新 system 消息只声明 `edit`，激活集仍是历史 `toolsAdded` 重放后的并集。
- `toolsRemoved`：先加 `read`、后删 `read` ⇒ resume 后 `read` 不在激活集。
- 修饰符：`--tools +grep,-bash` ⇒ resume 后恢复结果再叠加该修饰符。
- 晚注册：系统消息声明未注册的 `mcp__foo` ⇒ pending 含该名；注册后 `activateRuntimeTools` 能激活。
- 无系统消息 ⇒ 激活集不变（不覆盖初始 defaultTools 装配）。
- `usesDefaultTools=false`（显式 `--tools read` 列表）⇒ 恢复臂整臂跳过。
- 内置工具未提及：`read` 在注册表与系统消息都没提及时**不**被 newTools 自动加回。

既有 `RestoreToolsOnNavigateTest` 三条保留（语义不变），`AgentSessionPendingToolsTest` 回归。

### 2.5 风险

- 恢复现在改变模型可见的工具声明（[模型可见]）。若旧会话的 toolsAdded 记录与用户当前 CLI 选择冲突，以会话记录为准，再叠 `+name/-name`，与 pi 一致。
- 读取原始分支而不是投影是正确性前提；若沿用 `lane.messages`，压缩前缀里的 system 消息会丢，恢复出错误的工具集。
- `baseToolNames` 若 fork 装配漏回填，扩展工具会被误当新工具自动激活——用 `wireSession` 单点回填并在测试里钉住。

---

## 3. W2（B272）：compaction 摘要语义

### 3.1 pi 事实（新锚点）

摘要选项不再带输出上限（`compaction/compaction.ts:595-608`）：

```ts
function createSummarizationOptions(
	model: Model<any>,
	apiKey: string | undefined,
	headers: Record<string, string> | undefined,
	env: Record<string, string> | undefined,
	signal: AbortSignal | undefined,
	thinkingLevel: ThinkingLevel | undefined,
	sessionId: string | undefined,
): SimpleStreamOptions {
	const options: SimpleStreamOptions = { signal, apiKey, headers, env, sessionId };
	if (model.reasoning && thinkingLevel && thinkingLevel !== "off") {
		options.reasoning = thinkingLevel;
	}
	return options;
}
```

历史摘要与 turn-prefix 均不再计算 `0.8×reserve`／`0.5×reserve`（`:696-740`、`:1062-1095`）；`reserveTokens` 变成未使用参数（`:700` 注释 `Unused. Kept for existing callers; summaries use the model's output limit (#9512).`）。

split-turn 的 turn-prefix 现在接 `/compact` 的自定义指令（`:1082-1085`）：

```ts
	let promptText = `# Conversation\n${conversationText}\n\n# Instructions\n${TURN_PREFIX_SUMMARIZATION_PROMPT}`;
	if (customInstructions) {
		promptText = `${promptText}\n\nAdditional focus: ${customInstructions}`;
	}
```

`serializeConversation` 不再输出 thinking（`compaction/utils.ts:108-150`；注释说明：回放的 thinking 常不属于模型当前上下文，带上会让摘要请求大于被摘要的上下文）。

branch summary 去掉 4096 上限并透传思考级别（`branch-summarization.ts:79,305,353-356`）：

```ts
	const requestOptions: SimpleStreamOptions = { apiKey, headers, env, signal };
	if (model.reasoning && thinkingLevel && thinkingLevel !== "off") {
		requestOptions.reasoning = thinkingLevel;
	}
```

摘要鉴权：`agent-session.ts:167-172,603-606` 与 `sdk.ts:440` 引入 `markRuntimeStreamFunction`——由 model runtime 取鉴权的 stream fn 不再让调用方预先解析凭证（否则 OAuth 登录会被当成显式 API key，`#11520`）。

### 3.2 Java 现状与复核

- `LlmSummaryGenerator`（`pi-java-agent-core/.../compaction/LlmSummaryGenerator.java`）仍算 `min(floor(0.8×reserve), 模型上限)`／`min(floor(0.5×reserve), 模型上限)`（`:199-203,325-330`），并给 branch 传 `min(4096, 模型上限)`（`BranchSummarization.java:172-175`）。
- `ConversationSerializer.java:38-65` 仍输出 `[Assistant thinking]`。
- `CompactionService.java:147-174` 只把 `customInstructions` 传给历史摘要，turn-prefix 调用不接。
- `BranchSummarization.Options`（`:94-99`）没有思考级别；`LlmSummaryGenerator.callBranch`（`:169-183`）没有 reasoning 选项。
- **摘要鉴权复核**：Java 的 `DefaultProviders.streamFnFor`（`:171-186`）在**每次请求内**经 `Credentials::resolveCredential` 现取鉴权；`LlmSummaryGenerator.request` 从不接收 apiKey/headers。已经就是 pi 新语义（stream fn 自解析），**无代码改动**，仅在设计留痕并加一条回归：摘要请求不得预置凭证到 `StreamOptions`。

### 3.3 改动

1. `ConversationSerializer`：删除 thinking 收集与 `[Assistant thinking]` 输出；`[Assistant]`／`[Assistant tool calls]`／`[Tool result]` 逐字保持。
2. `LlmSummaryGenerator`：
   - `summarize`／`summarizeTurnPrefix` 不再计算输出上限，请求传 `OptionalInt.empty()`（Java 的 `StreamOptions.maxTokens` 空值语义＝使用模型默认输出上限，`SimpleOptions.maxTokensOrDefault:124-131`）。
   - `summarizeTurnPrefix` 增加 `customInstructions` 参数；非空时在 prompt 尾部追加 `\n\nAdditional focus: …`（与历史摘要同文案）。
   - 保留 `reserveTokens` 形参（接口兼容，标注未使用），删除 `historyMaxTokens`／`turnPrefixMaxTokens`。
   - `request` 增加 `Optional<ThinkingLevel>` 形参；`callBranch` 增加 `OptionalInt maxTokens + ModelThinkingLevel` 重载，内部按 `reasoningSupported` 与 `off` 门转成 reasoning。旧 `callBranch(String,int,...)` 保留给 bug report 摘要（其上限是 B79 自有语义，不在本包改动）。
3. `BranchSummarization`：
   - `Options` 去掉 `maxOutputTokens`，增加 `ModelThinkingLevel thinkingLevel`。
   - 生成调用改为 `OptionalInt.empty()`＋模型支持且级别非 off 时透传 reasoning。
   - `DEFAULT_RESERVE_TOKENS` 仍参与 `tokenBudget = contextWindow - reserveTokens`（输入预算），不删。
4. `TreeCompaction` 调用点：传 `owner.harness().getThinkingLevel()`；不再传 `modelResolver.maxOutputTokens(model)`。
5. `SummaryGenerator.summarizeTurnPrefix` 接口加 `customInstructions` 参数；保留旧三参默认方法委派 null，`truncating()` 同步。

### 3.4 测试（RED-first）

- `ConversationSerializer`：assistant 消息含 thinking＋text＋toolCall ⇒ 输出不含 `[Assistant thinking]`，其余块顺序与文案不变。
- `LlmHistorySummaryRequestTest`：历史摘要 `maxTokens` 为**空**（不再 16000/4096）；prompt 断言不变。
- `LlmTurnPrefixRequestTest`：turn-prefix `maxTokens` 为空；`customInstructions` 非空时 prompt 尾部有 `Additional focus: …`；空则没有。
- `BranchSummarizationTest`：请求 `maxTokens` 为空；模型 reasoning 且级别 `high` ⇒ reasoning=high；级别 `off` ⇒ 无 reasoning；非 reasoning 模型 ⇒ 无 reasoning；prompt 文案逐字保持。
- `CompactionServiceTest`／`SplitTurnCompactionTest`：split-turn 的 turn-prefix 带上 `/compact` 指令。
- 鉴权回归：摘要请求的 `StreamOptions` 不含任何凭证字段（结构上无此字段，断言请求组装不引入）。

### 3.5 风险

- [模型可见] 去掉输出上限后，摘要文本可能变长，进而影响后续上下文的 token 估算与压缩触发频率；这是 pi 的刻意行为（`#9512`），逐字对齐。
- [模型可见] thinking 不再进入摘要请求/摘要结果；依赖 thinking 细节的摘要质量变化与 pi 同向。
- 摘要请求的 `maxTokens` 变为空 ⇒ Java provider 侧回落到模型目录 `maxOutputTokens`（`SimpleOptions.maxTokensOrDefault`），与 pi 的 `options?.maxTokens ?? model.maxTokens` 等价；未编目模型走既有 4096 兜底（D207 族），不改。
- `BranchSummarization.Options` 是 record，构造点（生产 1 处＋测试 1 处）必须同步；漏改会在编译期暴露，不靠运行期兜底。

---

## 4. W3（B273）：auto-retry 省略与工具结果成对

### 4.1 pi 事实（`agent-session.ts:1256-1284`，逐字）

```ts
	private _omitRecoveryAttempt(message: AssistantMessage): void {
		const toolCallIds = new Set(message.content.flatMap((block) => (block.type === "toolCall" ? [block.id] : [])));
		// A context edit replaces the projected copy, so match it by source entry as well.
		const entryId = this._findPersistedMessageEntryId(message);
		const messages = this.agent.state.messages;
		const index = messages.findIndex(
			(m) => m === message || (entryId !== undefined && this._entryIdsByMessage.get(m) === entryId),
		);
		const toolResults: AgentMessage[] = [];
		for (const m of index < 0 ? [] : messages.slice(index + 1)) {
			if (m.role === "assistant") break;
			if (m.role === "toolResult" && toolCallIds.has(m.toolCallId)) toolResults.push(m);
		}
		const targets = [message, ...toolResults];
		const targetIds = targets.map((target) => this._findPersistedMessageEntryId(target));
		const unresolvedProjectedTarget = targets.some(
			(target, index) => targetIds[index] === undefined && this.agent.state.messages.includes(target),
		);
		if (unresolvedProjectedTarget) {
			throw new Error("Cannot persist recovery omission because a projected message has no source entry");
		}
		for (const targetId of targetIds) {
			if (!targetId) continue;
			const editId = this.sessionManager.appendContextEdit(targetId, null);
			const entry = this.sessionManager.getEntry(editId);
			if (entry) this._emit({ type: "entry_appended", entry });
		}
		this._refreshFinalizedContext();
	}
```

调用点：溢出压缩前 `:3124`；auto-retry 前 `_prepareRetry:3868`。两者都传整条失败助手，由该方法自己找本轮工具结果。

### 4.2 Java 现状

- `RecoveryOmissions.persist`（`pi-java-agent-core/.../harness/RecoveryOmissions.java:37-55`）用 `toolResultEntryIds`（`:151-167`）扫描**整条 transcript** 找同 toolUseId 的结果；对溢出路可用，但口径不是 pi 的「从失败助手往后扫到下一助手」。
- `persistRetryOmission`（`:63-78`）**只省略助手**，不配工具结果。这会让下一个请求出现「工具调用没有结果」的非法配对（pi 注释 `#11227`）。
- 定位规则、投影兜底与固定错误文案已存在（`:100-144`），可复用。

### 4.3 改动

1. 两条路统一走一个 `omitAttempt(lane, failed)`：
   - 按 pi 在**工作副本**（`lane.messages`）中找失败助手的下标：先按对象身份；落空时用 `resolveEntryId` 得到源条目 id，再按投影遍历找到索引（pi 的 `_entryIdsByMessage` 等价物）。
   - 从下标后逐条扫描：遇 assistant 停；把 `toolUseId` 命中失败助手 toolCall 集合的 `ToolResultMessage` 收集为待省略目标。
   - 目标集合＝失败助手＋这些结果；逐个 `resolveEntryId`；任一目标在副本中存在却解析不到源条目 ⇒ 抛既有固定错误。
   - 逐个追加 `replacement:null` 的 `Entry.ContextEdit`，最后重建工作副本。
2. `persist`（溢出）与 `persistRetryOmission`（重试）共用上述逻辑；删除「全 transcript 扫 toolUseId」的旧辅助（保留可测的纯函数仅当测试需要）。
3. 事件发射保持现状（Java 的 append 事件链由 lane 写入承接）；不新增事件。

### 4.4 测试（RED-first）

- `RecoveryOmissionsResolutionTest` 扩展（新建 `retryOmissionOmitsThePairedToolResult` 等）：失败助手含一个 toolCall，工作副本中紧跟其 toolResult，再触发 retry 省略路径 ⇒ transcript 追加**两条** context_edit（助手＋结果都 `replacement:null`），下一次请求投影不含该结果。
- 溢出路回归：既有 `RecoveryOmissionsResolutionTest` 仍覆盖助手＋结果成对；新增断言改为同一算法。
- 恢复会话形态：失败助手来自 resumed transcript（对象非新 run 的实例）时仍能配对并写入合法 edit。
- 守卫：目标在副本中无源条目 ⇒ 抛 `Cannot persist recovery omission…`（文案逐字）。
- 负例：toolResult 在失败助手之前或在下一个 assistant 之后 ⇒ 不省略。

### 4.5 风险

- [模型可见] 下一轮请求不再携带「失败尝试的孤立工具结果」；这是 pi 刻意修复，避免 provider 的配对校验报错。
- transcript 原文保留（append-only），用户历史仍可见失败与结果；只有模型投影被切除。
- 若同一 toolUseId 在异常分支出现重复结果，pi 只取失败助手之后、下一助手之前的那一段；Java 必须同范围，不能再全表扫。

---

## 5. W4（B274）：会话持久化细节

### 5.1 pi 事实

**路径比较**（`utils/paths.ts:46-70`）：`pathsEqual`／`getPathComparisonKey`＝resolve 后 Windows 小写、POSIX 原样（W2 已复核 Java `WindowsPath` 的 `equals` 与之等价）。消费点：

- `sessionCwdMatches:744-745`（列表按 cwd 过滤）；
- `usesDefaultSessionDir:1148-1149`（交互模式展示）；
- `continueRecent:1803-1805`、`findById:1889-1891`、`list:1922` 的 `filterCwd`。

**append 失败回滚**（`_appendEntry:1191-1206`）：先改内存，再 `_persist`；失败时弹回 entry、删 byId、恢复 leafId 后重抛。

**fork 单 fd**（`:1863-1878`）：头＋全部条目通过一个 fd 写出，避免每条重新 open/close。

### 5.2 Java 现状

- **append**：`JsonlSessionStorage.appendEntry:204-220` 是「先 `appendMutation` 写文件，成功后才 `applyMutation` 改内存」。因此写失败时内存本就未变，**行为等价**，无需改代码（§5.3-a）。
- **fork**：`JsonlSessionStorage.fork:139-154` 先 `createImmediate` 写头，再对每条 mutation `appendFile`，最后 rename；每条一次 open/close，与 pi 旧实现同，新锚点要求单次写。
- **路径比较**：Java 的 JSONL 仓库不接收「会话目录覆盖」，目录由 cwd 派生，所以 pi 的五处 `pathsEqual` 在 Java 当前架构中没有对应比较点。真正缺失的是 §1.4 的**显式目录布局**与 cwd 过滤。

### 5.3 决定

**W4a（B274-a）：append 失败回滚 = 复核，不改代码。**
【场景】pi 新锚点要求「写入失败不得让内存条目成为后续条目的 parent」。【决定】Java 已是文件成功后才提交内存，语义等价；补一条故障注入回归锁。【风险/代价】[内部] 无行为变化；若未来改成先改内存，测试立即变红。

**W4b（B274-b）：fork 单次写。**
【场景】逐条 `appendFile` 在开启文件安全监控的 Windows 上开销大。【决定】把「头＋全部 mutation 行」拼成一个字符串，一次 `fs.writeFile(tmp)` 后 rename；失败仍删除 tmp。【风险/代价】[内部] 失败原子性不变（tmp＋rename）。

**W4c（B278，需批准）：显式会话目录布局对齐。**
【场景】§1.4 的布局分叉导致 pi 与 pi-java 指向同一 `--session-dir` 时互不可见。【决定】
1. 解析出 `ResolvedSessionDir`：显式 `args.sessionDir()`／`settings.sessionDir` ⇒ `flat=true`，路径做 `~` 展开（相对路径原样保留，比较时由 `SessionPaths.same` 归一）；缺省 ⇒ `flat=false`，根为 `~/.pi-java/agent/sessions`。
2. `JsonlSessionRepository` 增加 flat 形态：`sessionDirectory(cwd)` 在 flat 下直接用该目录；`sessionDirectories(cwd)` 返回 `[dir]`；`create`／`fork`／`import` 均平铺。
3. cwd 过滤：flat 且 `!pathsEqual(dir, 默认会话目录(cwd))` 时，`list`／`findById`／`latest` 只收 header `cwd` 与当前 cwd 路径相等的会话（`SessionPaths.same`，Windows 大小写不敏感、POSIX 敏感）。
4. 默认根模式保持现状（两参 `PersistentSessionRepositories.jsonl(root)` 语义不变），新增显式目录构造；`AgentSessionFactory` 四处解析统一走一个 helper。
5. `usesDefaultSessionDir` 当前无 Java 消费点（交互模式展示属 L7），不新增方法，留设计注记。

【风险/代价】[模型可见] 文件落点与「列会话」的可见结果变化；旧版 Java 的 `dir/--cwd--/` 历史文件不会被自动发现。约 25 处测试夹具需从「根」改为「显式目录」形态（§5.4）。若用户驳回，W4c 转 B278 挂账，本包只做 a／b。

### 5.4 测试

- **W4a**：注入抛 `IOException` 的 `JsonlSessionRepoFileSystem`；`appendEntry` 失败后 ⇒ `getEntry(id)` 为空、后续追加的 parentId 仍是失败前的叶。
- **W4b**：计数 fs 断言 fork 对目标临时文件只用一次 `writeFile`（0 次 `appendFile`），结果与旧实现逐字节相同；失败注入仍清理 tmp。
- **W4c**（agent-core）：flat 仓库 create ⇒ 文件在目录顶层；`list(cwd)` 与 `findById(cwd,id)` 只返回同 cwd 会话（大小写变体在 Windows 命中）；显式目录等于默认 per-cwd 目录 ⇒ 不过滤；`listAll` 扫 flat 目录。
- **W4c**（coding-agent）：`--session-dir dir` 创建后直接在该目录看到 `.jsonl`；`-c`／`-r` 仍能恢复；`settings.sessionDir` 同语义；`~` 展开。
- 回归：`LatestSessionScopeTest`（默认根模式）保持通过，证明两种形态没有串味。

### 5.5 风险

- W4c 触及 CLI 可见文件布局与约 25 个测试夹具；建议作为独立提交，便于回滚。
- 默认根与显式目录是两套布局，必须由工厂单点决策；若某个调用点绕过工厂自建 handle，会落到错误形态。测试覆盖 `handle` 构造的两条入口。
- SQLite 后端不参与 W4c（pi 无参照物，B59 出分母）；`--session-dir` 对 SQLite 的既有含义不变，设计注明。

---

## 6. 回归与验证

### 6.1 新增/修改测试汇总

| 模块 | 测试 | 覆盖 |
|---|---|---|
| agent-core | `ConversationSerializerTest`（新建） | 摘要序列化不含 thinking |
| agent-core | `LlmHistorySummaryRequestTest`／`LlmTurnPrefixRequestTest` | 输出上限为空；turn-prefix 接指令 |
| agent-core | `RecoveryOmissionsResolutionTest` 扩展＋新用例 | 两条省略路成对 |
| agent-core | `JsonlPersistenceDetailsTest`（新建） | 写失败不污染内存；fork 单次写 |
| agent-core | `SessionDirLayoutTest`（W4c，新建） | 平铺布局、cwd 过滤、大小写 |
| agent-core | `CompactionCustomInstructionsTest` 扩展 | split-turn 的 turn-prefix 带指令 |
| coding-agent | `RecoverToolsOnResumeTest`（新建） | 历史 system 消息并集、toolsRemoved、修饰符、pending |
| coding-agent | `BranchSummarizationTest` 扩展 | 无上限、thinkingLevel 三臂 |
| coding-agent | `SessionDirResolutionTest`（新建） | `~` 展开、显式目录判定 |
| coding-agent | `--session-dir` 夹具批量调整（W4c） | 显式目录形态 |

### 6.2 命令与门

1. 模块验证：`mvn test -pl pi-java-agent-core,pi-java-coding-agent -am`。
2. 包闭环：`mvn clean verify`；要求测试零失败、Checkstyle 零错误零警告、SpotBugs 零新增问题。
3. 验证日志落盘到仓外或不被 `clean` 删除的路径，关键结果回填本文档。
4. 模型可见文本（摘要 prompt、工具声明、上下文投影）逐字断言；不以 Java 自产 golden 自证。
5. 无 `System.out.println`、无新增 `@SuppressWarnings`、文件 ≤500 行。

---

## 7. 挂账与编号

| 编号 | 所属层 | 内容 | 证据 | 本包动作 |
|---|---|---|---|---|
| B271 | L4 | 恢复会话 active tools：全部 system 消息并集、toolsRemoved、晚注册扩展工具、`+/-` 修饰符、usesDefaultTools 门、resume 入口 | `agent-session.ts:538,1851-1882`；`transcript.ts:58-67` | **实施（W1）** |
| B272 | L4 | compaction：去固定输出上限、thinking 不进序列化、turn-prefix 接指令、branch summary thinkingLevel；摘要鉴权复核 | `compaction.ts:595-608,696-740,1062-1095`；`utils.ts:108-150`；`branch-summarization.ts:79,305,353-356`；`agent-session.ts:167-172,603-606` | **实施（W2）** |
| B273 | L4 | auto-retry 省略按 toolCallId 与工具结果成对 | `agent-session.ts:1256-1284,3124,3868` | **实施（W3）** |
| B274 | L4 | 持久化细节：append 回滚（复核）、fork 单写 | `session-manager.ts:1191-1206,1863-1878` | **W4a 复核／W4b 实施** |
| B278 | L4（新增） | 显式 `--session-dir`／`settings.sessionDir` 平铺布局＋cwd 过滤＋`~` 展开 | `session-manager.ts:589-594,744-745,1148-1149,1803-1805,1889-1891,1922` | **待批准（W4c）** |
| D218 | L4（拟登记） | lane 是 Java 内部扩展；pi 单 leaf 树的分支语义由 `moveLane/resetLeaf/branchWithSummary` 覆盖，线形 `lane` 键被 pi 忽略（D190） | `agent-session.ts:1589,1601,1610`；`SessionStorage` | #6 改判对齐 |

---

## 8. 实施结果（2026-11-05）

### 8.0 代码落地

| 工作项 | 主要改动点 |
|---|---|
| **W1（B271）** | `ModelToolControl` 新增 `baseToolNames` 回填、`usesDefaultTools()`、`defaultToolModifiers()`、`applyToolLoadout(List<String>)`（从 `setActiveToolsByName` 提取）与 `restoreToolsFromTranscript(List<Entry>, List<String>)`（原始分支全部 system 消息→`mentioned`→`newTools`＝注册表−mentioned−内置−非注册激活→`applyToolModifiers`→pending 取允许名后按实际激活删除）。调用点：`SessionPersistence.attachOpened`（resume，modifiers＝`defaultToolModifiers()`）、`TreeCompaction`（navigate，modifiers 为空）、`SessionDiscoveryFork.forkInMemory`（fork 后装配）；`SessionWiring` 在 `toolSelection` 装配后回填内置工具名。删除 `RunLifecycle.restoreToolsFromTranscript` 与 `AgentHarness` 转发，避免双语义。 |
| **W2（B272）** | `ConversationSerializer` 删除 thinking 收集与 `[Assistant thinking]` 输出；`LlmSummaryGenerator` 历史/turn-prefix 传 `OptionalInt.empty()`，turn-prefix 追加 `\n\nAdditional focus: …`，新增 `reasoningOption(ModelThinkingLevel)` 与 `request(..., Optional<ThinkingLevel>, String)`、`callBranch(String, OptionalInt, ModelThinkingLevel, String)`；`SummaryGenerator` 增加四参 `summarizeTurnPrefix` 默认方法（委派旧三参）；`CompactionService` 的 split-turn 调用传 `customInstructions`；`BranchSummarization.Options` 改为 `(model, contextWindow, reserveTokens, customInstructions, replaceInstructions, thinkingLevel)`，生成走空 `maxTokens`＋thinking 门；`TreeCompaction` 传 `harness().getThinkingLevel()`。摘要鉴权复核结论为 Java `streamFn` 每次请求内自解析凭证，**无代码改动**。 |
| **W3（B273）** | `RecoveryOmissions` 的 `persist` 与 `persistRetryOmission` 共用 `omitAttempt`：在工作副本 `lane.messages` 中按对象身份或源条目 id 定位失败助手，向后扫描至下一 assistant，收集同 `toolCallId` 的工具结果，逐条追加 `replacement:null` 的 `ContextEdit`；删除 transcript 全表扫的旧 `toolResultEntryIds`。 |
| **W4a（B274-a）** | 复核不改代码：`JsonlSessionStorage.appendEntry` 仍「写文件成功后才提交内存」。新增 `JsonlPersistenceDetailsTest` 故障注入回归锁。 |
| **W4b（B274-b）** | `JsonlSessionStorage.fork` 改为头部＋全部 mutation 行拼一个 `StringBuilder`、单次 `fs.writeFile(tmp)` 后 rename；删除 `createImmediate` 逐条写路径。 |
| **W4c（B278）** | 新增 `SessionPaths.same`（resolve+normalize+`Path.equals`，Windows 大小写不敏感）；`JsonlSessionRepository` 增加 flat 构造与 `sessionDirectory`/`sessionDirectories`/`filterCwd`/`cwdMatches` 语义；`RichSessionLister` 接 `BiPredicate` 过滤；`PersistentSessionRepositories` 增加 `jsonlSession(Path[, fs])` 与 `defaultSessionsRoot()`；`AgentSessionFactory` 用 `resolveSessionDir` 单点解析（含 `~` 展开）并在 `sqlite`/`flat`/`root` 三分支构造 handle。 |

### 8.1 验证

- 模块验证：`mvn -o test -pl pi-java-agent-core,pi-java-coding-agent -am` ⇒ agent-core 943 测、coding-agent 1826 测，零失败。
- 全仓门（2026-11-05）：`mvn -o clean verify` ⇒ 16 模块 **BUILD SUCCESS**，退出码 0，耗时 16:30 min；总测试 **6,227**（跳过 33）**零失败零错误**；Checkstyle 15 次执行均 **0 violations**；SpotBugs 13 次执行均 **BugInstance size is 0**。
- 验证日志：`.verify/l4-full-verify.log`（仓内目录，不被各模块 `clean` 删除；提交前移除，结果已回填本表）。
- 实施提交：`b801256b`（W1＋W2）、`845a4a7e`（W3）、`6845786a`（W4）。


### 8.2 账面与回填（已执行）

1. **map/06**（已回填）：头部写 `PI_PIN=56cae7823` 与本包 SHA；存储后端 20 行改新锚点行号与证据；#6 改判「对齐（形状不同）」并注 D218；汇总改为 20 行对齐、Σ44、**44.0/44＝100%**；新增「L3 转出行」指针（#77／#24，权重 0）与 B278 落点；评测域注明归 L6。
2. **docs/04**（已回填）：B271–B274 销号；B278 登记并销号；D218 登记；更新 L4 行。
3. **docs/06**（已回填）：L4 状态改「已闭环（docs/24 W1–W4＋B278）」，下一步 L5。
4. **docs/08** §9 进度表（已回填）：L4 行更新为已重测／已通过／已闭环与新账面。
5. 提交拆分：代码按 W1＋W2（恢复＋摘要）、W3（省略成对）、W4（持久化＋目录）三个可独立编译的 commit，文档关闭提交 `docs(l4): close docs/24 rebaseline`。

## 9. 用户裁决（2026-11-05）

1. **W4c（B278）纳入本包**：显式会话目录平铺布局＋cwd 过滤＋`~` 展开按 §5.3-c 实施。
2. **map/06 #6 改判对齐并登记 D218**：lane 视为 Java 内部扩展，pi 分支语义由 `moveLane/resetLeaf/branchWithSummary`＋`TreeCompaction` 覆盖。
3. W1–W3、W4a/b 按本稿实施。
