# 18 - L4 收口设计：会话持久化归属复核

> 【背景摘要】`docs/09` 的 P0 预审计把 L4（session）判为「44/44＝100%，新锚点无 L4 行为变化」。按「由底往上、逐层对齐」的排序，L3 收口后的下一批就是 L4。本稿对 `56cae7823..a00a5048d` 的会话持久化面逐项复核，发现该结论**不成立**：`session-manager.ts` 有一处新锚点行为变化（#11956 abort 观测），另有两条早已存在、但从未登记的误差。目标是把这三处落掉，让 L4 维持 **44/44＝44.0＝100%**，并把 20 行的证据行号重新钉到 `a00a5048d`。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前 main（`36ea5e58`）。分层判定只写 `docs/map/06-session-evals-chord.md`，本稿只做设计、实施与验收。

术语：
- **归属复核**：不改判定口径，只核对「该层到底有哪些 pi 单元、它们的当前证据是什么、Java 是否仍对得上」。
- **abort 观测**：调用方传入 `AbortSignal` 后在列表/加载过程中是否会观察到中止；不是模型可见面。
- **候选（candidate）**：`listAll` 扫描阶段产出的 `{path, stats?}`；`stats` 可能缺席（stat 失败）。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | L4 的 20 个存储后端单元在新锚点下复核通过；三处误差落地后维持 **44/44＝100%** |
| 行为不变量 | JSONL v3 线形、头/条目字段、文件命名、惰性创建、fork/branch 语义全部不变；不改 SQLite 后端（R1＋R5 出分母） |
| 完成判据 | ①`mvn test -pl pi-java-agent-core -am` 全绿；②`mvn test -pl pi-java-coding-agent -am` 全链全绿；③`map/06` 20 行行号重钉＋`docs/05` §1.2／`docs/09` 进度行同步 |
| 不做项 | 不碰 `session-cwd.ts`／`session-picker.ts`／`docs/sessions.md`（归属 L5／L7，见 §5）；不重构 `RichSessionLister` 的并发/节流结构；不引入流式逐行读取（Java 侧 `readTextFile` 同步读已对齐语义） |

## 2. pi 证据（`a00a5048d`）

### 2.1 锚点间变更清单

`56cae7823..a00a5048d` 中与会话相关的改动只有四个文件（`git diff --numstat`）：

| 文件 | +/- | 归属 |
|---|---|---|
| `packages/coding-agent/src/core/session-manager.ts` | +41/−33 | **L4**（本批） |
| `packages/coding-agent/src/core/session-cwd.ts` | +15/−1 | L5（cwd 缺失流程，已在 `docs/09` 缺口表） |
| `packages/coding-agent/src/cli/session-picker.ts` | +9/−6 | L5／L7（退出前 flush，见 §5） |
| `packages/coding-agent/docs/sessions.md` | +7/−1 | 文档（随 cwd 流程） |

`session-manager.ts` 全文 2,028→2,036 行，只有两个 hunk：

1. `projectContextEntry` 从模块私有改为 `export`（`:520`）。**纯可见性，无行为变化**，与 map/06 #11 的判定无关。
2. `buildSessionInfo` 的 abort 处理重写（`:800-890`）。这是唯一的行为变化。

### 2.2 `buildSessionInfo` 新形状（逐字，`:800-890`）

头部注释与读取源（`:814-819`）：

```ts
		// No signal for the stream: leaving the loop early stops readline from listening for the stream's
		// errors, so a later abort would destroy it with an 'error' no listener handles (#11956). The loop
		// checks the signal instead, and the stream is destroyed when reading stops, closing its file.
		const stream = createReadStream(filePath, { encoding: "utf8" });
		try {
			const rl = createInterface({ input: stream, crlfDelay: Infinity });
```

循环内的逐行检查（`:821`）：

```ts
			for await (const line of rl) {
				signal?.throwIfAborted();
```

循环结束后、组装 `SessionInfo` 之前的**补检查**（`:856-861`）：

```ts
		} finally {
			stream.destroy();
		}
		// An abort while readline waited for the end of the file, after the last line.
		signal?.throwIfAborted();

		if (!header) return null;
```

catch 仍按「先转抛 abort、再返回 null」处理（`:885-888`）：

```ts
	} catch {
		signal?.throwIfAborted();
		return null;
	}
```

旧锚点（`56cae7823`）的差异只有两点：`createReadStream(filePath, { encoding: "utf8", signal })` 把 signal 交给 stream；循环后**没有**补检查。也就是说，旧实现的中止只能被「readline 流错误」或「逐行检查」观测到，EOF 等待窗口里的中止会漏掉。

### 2.3 `listSessionsFromDir` 的错误吞噬（`:949-982`）

```ts
async function listSessionsFromDir(
	dir: string,
	onProgress?: SessionListProgress,
	signal?: AbortSignal,
): Promise<SessionInfo[]> {
	signal?.throwIfAborted();
	if (!existsSync(dir)) return [];

	try {
		const dirEntries = await readdir(dir);
		...
	} catch {
		signal?.throwIfAborted();
		return [];
	}
}
```

`listAll` 同形（`:1967,2031-2034`）：入口先 `throwIfAborted`，整个主体包在 try 里，catch 后再 `throwIfAborted` 并返回 `[]`。

### 2.4 候选 stat 的双重尝试（`:806,1996-2000`）

```ts
		const stats = fileStats ?? (await stat(filePath));   // buildSessionInfo :806
...
				async (path): Promise<SessionFileCandidate> => {
					try {
						return { path, stats: await stat(path) };   // :1999
					} catch {
						return { path };                            // 无 stats 候选
					}
				},
```

`listSessionsFromDir`（项目内列表）根本不预 stat，直接让 `buildSessionInfo` 在 try 里 stat；`listAll` 预 stat 失败后仍会在 `buildSessionInfo` 再试一次。两次都失败 ⇒ catch ⇒ `null` ⇒ 该会话被排除。

## 3. Java 现状（`pi-java-agent-core`）

### 3.1 `RichSessionLister.buildRichInfo`（`:155-243`）

逐行检查已对齐（`:167-168`）：

```java
            for (String line : fs.readTextFile(path).split("\n")) {
                throwIfAborted(signal);
```

循环结束到 header 校验之间**没有补检查**（`:227-230`）：

```java
            }
            if (!headerSeen || id == null) {
                return null;
            }
```

catch 已对齐（`:237-242`）：

```java
        } catch (SessionListAbortedException e) {
            throw e;
        } catch (Exception e) {
            throwIfAborted(signal); // pi :869-872：catch 后先转抛 abort
            return null;
        }
```

### 3.2 `listRich`／`listAllRich`（`:60-91`／`:98-145`）

入口检查已对齐（`:62`／`:99`），但两个方法体**都没有** pi 的外层 try/catch：目录枚举（`fs.listDir`）、`mapWithConcurrency` 抛出的非 abort 异常会直接穿透到调用方。`DefaultJsonlFileSystem.listDir` 在 IO 失败时抛 `JsonlFileException`（`:100-110`）；pi 在这种情况下返回 `[]`。

### 3.3 `mtimeOf`（`:276-282`）

```java
    private long mtimeOf(Path path) {
        try {
            return fs.fileInfoMtimeMs(path);
        } catch (RuntimeException e) {
            return 0;
        }
    }
```

stat 失败被折成 `0` 并继续加载；pi 的对应行为是「候选无 stats → `buildSessionInfo` 再 stat → 再失败则 `null`」。因此 Java 会**保留**一个 pi 会**排除**的会话，且 `modified` 兜底成 0。

## 4. 三处误差与落地

### L4-R1 abort 补检查（新锚点 #11956）

【场景】单文件逐行读完后、结果组装前，另一线程触发 abort。
【决定】在 `buildRichInfo` 的 for 循环之后、`if (!headerSeen…)` 之前加一行 `throwIfAborted(signal);`，对位 pi `:860`。Java 没有 Node stream 的 EOF/readline 错误面，故不引入流所有权语义；补检查就是「EOF 等待窗口」在同步读实现下的等价位置。

```java
            for (String line : ... ) { ... }
            throwIfAborted(signal); // pi :860：readline 等到 EOF 之后的中止
            if (!headerSeen || id == null) {
```

【风险】该窗口在同步读实现下很窄，只有并发 abort 能命中；单线程调用无行为变化。风险仅余「多一次 `isAborted()` 读」，可忽略。

### L4-R2 列表错误吞噬

【场景】目录不可读、磁盘错误等非 abort 异常出现在列表过程中。
【决定】`listRich`／`listAllRich` 的主体包入 `try`，`catch (RuntimeException e)` 后先 `throwIfAborted(signal)`（abort 直接转抛），否则返回 `List.of()`，对位 pi `:978-981`／`:2031-2034`。单个目录发现失败已有内层 catch（`:113-115`，对位 pi `:1953-1955`），保持不变。

```java
    public List<RichSessionInfo> listRich(...) {
        throwIfAborted(signal);
        if (cwd == null) return List.of();
        try {
            ...  // 原主体
        } catch (RuntimeException e) {
            throwIfAborted(signal); // pi :979-981：catch 后先转抛 abort
            return List.of();
        }
    }
```

【风险】`catch (RuntimeException)` 会像 pi 一样把编程错误也折成空列表；这是对齐 pi 的代价，符合「和 pi 表现一样」的判据。若后续想保留诊断，可在 catch 里记日志，但不得改变返回值。

### L4-R3 候选 stat 失败语义

【场景】`listAll` 发现阶段 stat 失败，或项目内列表读取时 stat 失败。
【决定】`mtimeOf` 失败返回哨兵 `-1`（不再返回 `0`）；`buildRichInfo` 收到 `-1` 时在自身 try 内再 stat 一次，仍失败则走既有 catch ⇒ `null`。对位 pi `:806,1999-2000`。

```java
    private long mtimeOf(Path path) {
        try { return fs.fileInfoMtimeMs(path); }
        catch (RuntimeException e) { return -1; } // pi :1999-2000：候选可无 stats
    }
```

```java
    private RichSessionInfo buildRichInfo(Path path, long fileMtimeMs, AbortSignal signal) {
        try {
            if (fileMtimeMs < 0) {
                fileMtimeMs = fs.fileInfoMtimeMs(path); // pi :806：fileStats ?? stat
            }
            ...
```

排序无需改：`-1` 在「mtime desc」中自然排到最后，等价 pi 的 `-Infinity`；stat 成功后实际 mtime 回填，`created` 兜底也用它。

【风险】Windows 上极少数文件系统的 mtime 可能异常为负？实际 epoch ms 不会为负；哨兵约定写进注释与测试。若未来 `fileInfoMtimeMs` 改成返回 `OptionalLong`，只需改本方法。

## 5. 归属面外的变更（只登记，不实施）

| pi 变更 | 归属 | 说明 |
|---|---|---|
| `session-cwd.ts`：`formatMissingSessionCwdPrompt` 改为 `Session cwd does not exist: <cwd>`；新增 `MissingSessionCwdChoice`＋`getMissingSessionCwdOptions`（Continue/Fork/Cancel，`:40-57`） | **L5** | `docs/09` L5 缺口已列「cwd 缺失流程」；Java 当前无对应流程。本批不实现，作为 L5 设计输入。 |
| `session-picker.ts`：退出前 `flushStdout()`（#12133，`:26-30`） | **L5／L7 待定** | 属于 CLI 启动 UI 的终端复位刷新；`docs/09` 未列，建议 L5 复核时归到编码代理的 CLI 面，若判为纯 TUI 终端能力则转 L7。 |
| `docs/sessions.md`：补 Continue/Fork/Cancel 描述 | 文档 | 随 L5 cwd 流程。 |

## 6. 20 行证据重钉（判定不变，只改行号）

除 R1–R3 外，其余 17 行的能力与判定原样保留；下列行号全部按 `a00a5048d` 重钉（`buildSessionInfo` 之后整体 +8，`projectContextEntry` 之后 +1）。

| # | 能力 | 旧证据（`56cae7823`） | 新证据（`a00a5048d`） | 复核 |
|---|---|---|---|---|
| 1 | create 建会话 | `create:1765`／`newSession:1057`；header `:1062-1070` | `create:1773`／`newSession:1065`；header `:1071-1078`；首写 `_persist:1180` | 对齐 |
| 2 | list 列会话 | `list:1915`／`listAll:1936-1942`；cwd 过滤 `:1922,744-745` | `list:1923`／`listAll:1944-1950`；`:1930,745-746` | 对齐（R2） |
| 3 | 列表进度＋abort＋并发 | `SessionListProgress:884-889`；节流 `:893`；`listSessionsFromDir:941-971`；signal `:879,:911,:946,:971` | `892-897`；`901`；`949-979`；`887,919,954,979` | 对齐（R1／R2） |
| 4 | open | `open:1776` | `:1784` | 对齐 |
| 5 | fork | `forkFrom:1825`／`createBranchedSession:1642`；单 fd `:1868-1877` | `1833`／`1650`；`1876-1885` | 对齐 |
| 6 | 分支／leaf | `branch:1589`／`resetLeaf:1601`／`branchWithSummary:1610` | `1597`／`1609`／`1618` | 对齐（D218） |
| 7 | 追加 entry | `appendMessage:1214`、`appendThinkingLevelChange:1227`、`appendModelChange:1240`、`appendCustomEntry:1300`、`appendCustomMessageEntry:1349`；`_persist:1172`；回滚 `_appendEntry:1191-1206` | `1222`／`1235`／`1248`／`1308`／`1357`；`1180`；`1199-1214` | 对齐 |
| 8 | compaction 条目 | `appendCompaction:1271`；`CompactionEntry:91-104` | `1279`；不变 | 对齐 |
| 9 | 分支摘要条目 | `branchWithSummary:1610`／`BranchSummaryEntry:106-118` | `1618`；不变 | 对齐 |
| 10 | 上下文投影 | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `476`／`544`／`216` | 对齐 |
| 11 | `context_edit` | `ContextEditEntry:175-182`；`appendContextEdit:1370`；`projectContextEntry:519` | 不变；`1378`；`520` | 对齐 |
| 12 | 会话名 | `appendSessionInfo:1314`／`getSessionName:1328`；`SessionInfoEntry:142-147` | `1322`／`1336`；不变 | 对齐 |
| 13 | 标签 | `getLabel:1442`／`appendLabelChange:1451`；`LabelEntry:135-141` | `1450`／`1459`；不变 | 对齐 |
| 14 | usage 条目 | `UsageEntry:80-90`；`appendUsage:1254` | 不变；`1262` | 对齐 |
| 15 | JSONL 头线 | `SessionHeader:43-56`；写点 `:1062-1070`；`CURRENT_SESSION_VERSION:41`；找头 `:1086` | 不变；`1071-1078`；不变；`1093-1096` | 对齐 |
| 16 | JSONL 条目线 | `SessionEntryBase:57-63`；`SessionEntry:183-195` | 不变 | 对齐 |
| 17 | 旧版本读取 | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`；`_rewriteFile:1124-1133` | 前三不变；`1132-1141` | 对齐 |
| 18 | 文件名／目录 | `fileTimestamp:1079`；`getDefaultSessionDirPath:589-594` | `1087`；`590-595` | 对齐（B278） |
| 19 | 惰性创建 | `_hasConversation:1166`；`_persist:1172-1190` | `1174`；`1180-1190` | 对齐（D191） |
| 20 | 内存后端 | `inMemory:1814` | `1822` | 对齐 |

**行数/权重不变**：20 行，Σ=44，Σ(w×c)=44.0 ⇒ **44/44＝100%**。

> 说明：Java 代码注释里的 pi 行号（如 `RichSessionLister` 顶部的 `:799-882`、`:884-974`、`:1921-2012`）也随本批一起 +8 重钉，避免下次复核再被误导。

## 7. 测试计划（RED-first）

| 用例 | 夹具 | 先红状态 | 断言 |
|---|---|---|---|
| `abortAfterLastLineStillThrows`（R1） | `RichSessionListTest`：单会话文件；自定义 `StepSignal extends AbortSignal`，第 `2+L+1` 次 `isAborted()` 才返回 true（`L`＝`readTextFile` 内容按 `\n` 切分后的段数；2＝入口＋worker 头，L＝逐行，1＝补检查） | 无补检查时总调用数只有 `2+L`，永不中止 ⇒ 返回 1 条 | `SessionListAbortedException`；且 `signal.calls() == 2+L+1` |
| `listRichSwallowsDirectoryFailure`（R2） | 复用 `JsonlPersistenceDetailsTest.CountingFs` 风格：委托真实 fs，`listDir` 抛 `JsonlFileException` | 无外层 catch 时异常穿透 | 返回空列表 |
| `listAllRichSwallowsDirectoryFailure`（R2） | 同上，`sessionsRoot` 的 `listDir` 抛错 | 同上 | 返回空列表 |
| `statFailureRetriesThenExcludes`（R3） | 包装 fs：`fileInfoMtimeMs` 第 1 次抛错、第 2 次成功 ⇒ 会话保留且 `modifiedMs` 为真实 mtime；两次都抛 ⇒ 列表为空 | 无哨兵/重试时第 1 次失败被折成 0，会话仍保留 | 两次都失败 ⇒ `isEmpty()`；一次失败一次成功 ⇒ `hasSize(1)` 且 mtime 正确 |

**变异探针**：

1. 删掉 R1 的补检查 ⇒ `abortAfterLastLineStillThrows` 失败。
2. 删掉 R2 外层 catch ⇒ 两个 swallow 用例失败。
3. 把 R3 的哨兵 `-1` 改回 `0`（或删掉 buildRichInfo 重试）⇒ `statFailureRetriesThenExcludes` 失败。

三个探针必须分别单独跑；组合跑会互相遮蔽（R1 的补检查先于 R3 的重试）。

## 8. 验收与台账回填

| 步骤 | 内容 |
|---|---|
| 自验证 | `mvn test -pl pi-java-agent-core -am`（993 测＋新增 5 个用例）→ 全绿；`mvn test -pl pi-java-coding-agent -am` 全链全绿 |
| 地图 | `map/06`：文件头基准改 `a00a5048d`／java 证据改本批 HEAD；20 行行号按 §6 重钉；#3 增补 R1–R3 说明；`docs/05` §1.2 的 L4 行保持 44/44 但补「已复核（docs/18）」 |
| 计划视图 | `docs/09`「进度」段补 L4 完成；「现状速览」的下一步改为 L5 |
| 台账 | `docs/04` §0 增一条 L4 复核说明：R1–R3 直接落地、**未新开 B／D 号**；D218／B273／B274／B278 原样保留 |
| 提交 | ①`docs: design the L4 session recheck`（本稿）；②`fix(agent-core): align session list abort and stat semantics`（代码＋测试）；③`docs: close L4`（地图/台账回填） |

## 9. 实施记录（2026-11-22）

- 代码＋测试：`9c398610 fix(agent-core): align session list abort and stat semantics`（`RichSessionLister` ＋ 新夹具 `RichSessionListRobustnessTest`）。
- R1–R3 按 §4 落地；`RichSessionLister` 364 行（≤500），checkstyle `checkstyle:check` 退出 0。
- 测试：`RichSessionListRobustnessTest` 5 个用例由红转绿；`mvn test -pl pi-java-agent-core -am` → **998 测，0 失败，0 错误，1 跳过**；`mvn test -pl pi-java-coding-agent -am` → **1,998 测，0 失败**。
- 全量 `mvn clean verify -Dfrontend.skip=true`（2026-11-22，14:31）→ **BUILD SUCCESS，零 ERROR**（各模块合计 7,101 测）。首两轮被两条**既有** spotbugs 误报拦下（与 L4 改动无关）：`pi-java-ai` 的 `AnthropicStreamSupport.threadsOf` 三态 `Boolean`（`docs/12` 落地时未登记排除，补根 `spotbugs-exclude.xml` 条目）与 `pi-java-agent-core` 的 `PowerShellTool.wrapCommand`（`Path.getFileName()` 可空，补空值守卫）；两者随本批以 `967543bd fix(build): clear pre-existing spotbugs findings` 单独提交。
- 变异探针（分别单跑，均按预期转红后恢复）：
  1. 删 `throwIfAborted(signal)` 补检查 ⇒ `abortAfterLastLineStillThrows` 失败；
  2. catch 改回直接 `throw e` ⇒ 两个 swallow 用例报错；
  3. `mtimeOf` 哨兵改回 `0` ⇒ 两个 stat 用例失败。
- `StepSignal` 的调用数断言为 `2+L+1` 成立：R2 的外层 catch 对 `SessionListAbortedException` 单列重抛，没有额外查询 `isAborted()`。

## 10. 风险与遗留

- **R2 的 catch 宽度**：`RuntimeException` 会吞掉真正的 bug；这是对齐 pi 的必然结果。若日后要诊断，只能在 catch 内记日志，不能改返回。
- **map/03 的陈旧行号**：`map/03` 里 `session-manager.ts:1815`、`:943-965` 等证据来自更早锚点，L3 收口时未重钉。它不影响本批判定；是否顺带修正留待用户裁决（不纳入本批验收）。
- **Node stream 语义**：pi 的注释解释的是 Node `readline` 错误监听；Java 无对应面，本批只对「abort 可观测」这一行为结果负责，不移植流所有权机制。
</content>
