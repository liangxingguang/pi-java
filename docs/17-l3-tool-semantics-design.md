# 17 - L3 收口设计：六个内置工具语义对齐

> 【背景摘要】L3（`pi-java-agent-core`）在新锚点 `a00a5048d` 下投影 **169.5/178 ≈ 95.2%**。缺口全部落在内置工具：`find`（w2）、`read`（w3）、`edit`（w3）、`grep`（w3）、`bash`（w3）、截断（w3）共 17 权重按「部分对齐」各扣一半（−8.5）。本稿逐项给出 pi 证据、Java 现状与落地方案；目标是把这 6 行改判对齐，L3 回到 **178/178＝100%**。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前 main。分层判定只写 `docs/map/03-agent-core.md`，本稿只做设计与验收。凡「模型可见」者逐字对齐；纯引擎实现（遍历、正则翻译、流式读取）标注为【内部】。

术语：
- **模型可见面**：大模型能读到的工具描述、schema 文案、工具输出与报错文本。
- **窄差**：两侧无法逐字一致、已登记在 `docs/map/03-agent-core.md` 的差异（如 D1–D8）。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | 6 个工具单元从「部分」改判「对齐」⇒ L3 **178/178＝100%** |
| 行为不变量 | 工具名、参数集、执行模式、截断预算（2000 行／50KB）、临时文件机制不变 |
| 完成判据 | ①`mvn test -pl pi-java-agent-core -am` 全绿；②`mvn test -pl pi-java-coding-agent -am` 全链全绿；③`map/03` 六行改判并回填 `docs/05`／`docs/09` |
| 不做项 | 不移植 fd / ripgrep 外壳与下载器（批 3 裁决①维持）；不新增远程操作接缝（Java 的 `FileSystem`／`ShellExecutor` 即接缝）；不搬 `pi.exec`（coding-agent 面，L5） |

## 2. pi 证据

### 2.1 `find`（`packages/coding-agent/src/core/tools/find.ts`）

schema 文案（`:27-30`）：

```ts
pattern: Type.String({
	description:
		"Glob pattern relative to the search directory, e.g. 'src/**/*.ts'. Without '/', e.g. '*.ts', it matches file names at any depth",
}),
```

结果路径相对化（`:14-25`）：

```ts
export function relativizeFindResultPath(
	resultPath: string,
	searchPath: string,
	pathModule: path.PlatformPath = path,
): string {
	const hadTrailingSeparator =
		resultPath.endsWith(pathModule.sep) || (pathModule.sep === "\\" && resultPath.endsWith("/"));
	const relativePath = pathModule.isAbsolute(resultPath) ? pathModule.relative(searchPath, resultPath) : resultPath;
	const posixPath = relativePath.split(pathModule.sep).join("/");
	return hadTrailingSeparator && !posixPath.endsWith("/") ? `${posixPath}/` : posixPath;
}
```

limit 下限与模式归一（`:114,209-252`）：

```ts
// fd treats --max-results 0 as unlimited, so a limit below 1 means 1.
const effectiveLimit = Math.max(1, Math.floor(limit ?? DEFAULT_LIMIT));
...
let effectivePattern = process.platform === "win32" ? pattern.replaceAll("\\", "/") : pattern;
// fd's full paths never contain "/./", so "./src/*.ts" would find nothing; drop the "./".
effectivePattern = effectivePattern.replace(/^(?:\.\/)+/, "");
const isPathPattern = effectivePattern.includes("/");
const ignoreCase = effectivePattern === effectivePattern.toLowerCase();
const isRelativePathPattern = isPathPattern && !path.isAbsolute(effectivePattern);
```

相对路径模式锚定搜索根（`:240-250` 注释）：

```ts
// A relative path pattern is anchored at the search path, like grep's glob (#12066):
// 'src/*.ts' finds 'src/a.ts' but not 'pkg/src/b.ts'; '**/src/*.ts' finds both.
```

Windows 上改用 minimatch（`:226-233`），并把尾位 `/**` 改写为 `/**/*`：

```ts
const windowsPathMatcher =
	process.platform === "win32" && isPathPattern
		? new Minimatch(effectivePattern.replace(/\/\*\*$/, "/**/*"), {
				dot: true, noext: true, nocase: ignoreCase,
			})
		: undefined;
```

结果 limit 的证明与告示（`:284-290,331-336`）：

```ts
if (resultCount === effectiveLimit) {
	resultLimitReached = true;
	...
notices.push(
	`${effectiveLimit} results limit reached. Use limit=${effectiveLimit * 2} for more, or refine pattern`,
);
```

### 2.2 `grep`（`.../grep.ts`）

schema 的 `glob` 文案（`:23-28`）：

```ts
glob: Type.Optional(
	Type.String({
		description:
			"Filter files by glob pattern relative to path, e.g. 'src/**/*.ts'. Without '/', e.g. '*.ts', it matches file names at any depth",
	}),
),
```

`readFile` 操作被废弃（`:61-63`）：

```ts
/**
 * @deprecated Ignored. Context lines come directly from ripgrep. Override the tool's execute() for remote search.
 */
readFile?: (absolutePath: string) => Promise<string> | string;
```

`./` 前缀剥离与锚定（`:150,167-172`）：

```ts
const effectiveLimit = Math.max(1, Math.floor(limit ?? DEFAULT_LIMIT));
...
// rg never matches a leading "./" ("./src/*.ts" finds nothing), so drop it; "!" negates.
if (glob) args.push("--glob", glob.replace(/^(!?)(?:\.\/)+/, "$1"));
// rg emits the surrounding lines as `context` events, so no matched file is ever read here (#9276).
if (contextValue > 0) args.push("--context", String(contextValue));
```

上下文与 limit 的状态机（`:244-267`）：

```ts
if (!limitHit) {
	pushLine(filePath, lineNumber, lineText, isMatch);
	if (!isMatch) return;
	matchCount++;
	if (matchCount >= effectiveLimit) {
		limitHit = true;
		trailingContextEnd = lineNumber + contextValue;
	}
	return;
}
const inWindow = filePath === lastFile && lineNumber <= trailingContextEnd;
// A match inside the last match's trailing context is shown as context.
if (inWindow) pushLine(filePath, lineNumber, lineText, false);
// Only another match proves that the limit cut results off.
if (isMatch) matchLimitReached = true;
if (matchLimitReached && !(inWindow && lineNumber < trailingContextEnd)) finish();
```

⇒ 可见语义：每个文件内的匹配与上下文窗口**按行去重**；limit 命中后只保留最后一条已接受匹配的尾部上下文；窗口内再出现的匹配行按上下文（`-`）展示；只有窗口外**确实还有匹配**才置 `matchLimitReached`。

### 2.3 `read`（`.../read.ts`）

新增可选流式操作（`:65,99-117`）：

```ts
createReadStream?: (absolutePath: string, options: { signal?: AbortSignal }) => AsyncIterable<Uint8Array>;
...
async function* readLocalFileChunks(path: string): AsyncIterable<Uint8Array> {
	const handle = await fsOpen(path, "r");
	try {
		const stats = await handle.stat();
		const end = stats.isFile() && stats.size > 0 ? stats.size - 1 : undefined;
		yield* handle.createReadStream({ end, autoClose: false });
	} finally {
		await handle.close();
	}
}
```

流式仅用于可整型范围，其余走整文件（`:122-125,301-305`）：

```ts
function isStreamableRange(offset: number | undefined, limit: number | undefined): boolean {
	return (
		(offset === undefined || Number.isInteger(offset)) &&
		(limit === undefined || (Number.isInteger(limit) && limit >= 0))
	);
}
...
ops.createReadStream && isStreamableRange(offset, limit)
	? await selectLinesFromStream(...)
	: selectLinesFromBuffer(...)
```

流式只保留要展示的行（`:142-170` 关键片段）：

```ts
async function selectLinesFromStream(chunks, startLine, limit, signal): Promise<SelectedLines> {
	const endLine = limit === undefined ? Number.POSITIVE_INFINITY : startLine + limit;
	const decoder = new TextDecoder("utf-8", { ignoreBOM: true });
	...
	for await (const chunk of chunks) {
		if (signal?.aborted) throw new Error("Operation aborted");
		select(decoder.decode(chunk, { stream: true }));
	}
```

`read` 描述新增整文件接力句（`:239`）：

```
... Use offset/limit for large files. When you need the full file, continue with offset until complete.
```

首行超限与两种截断告示（`:321,324-332`）：

```ts
outputText = `[Line ${startLineDisplay} is ${firstLineSize}, exceeds ${formatSize(DEFAULT_MAX_BYTES)} limit. Use bash: sed -n '${startLineDisplay}p' ${path} | head -c ${DEFAULT_MAX_BYTES}]`;
...
if (truncation.truncatedBy === "lines") {
	outputText += `\n\n[Showing lines ${startLineDisplay}-${endLineDisplay} of ${totalFileLines}. Use offset=${nextOffset} to continue.]`;
} else {
	outputText += `\n\n[Showing lines ${startLineDisplay}-${endLineDisplay} of ${totalFileLines} (${formatSize(DEFAULT_MAX_BYTES)} limit). Use offset=${nextOffset} to continue.]`;
}
```

### 2.4 `edit` / `edit-diff`（`.../edit.ts`、`.../edit-diff.ts`）

schema 与提示（`edit.ts:20-36,40-51,146-148`）：

```ts
oldText: Type.String({
	description:
		"Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.",
}),
newText: Type.String({ description: "Replacement text for this targeted edit." }),
...
description:
	"Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes.",
promptGuidelines: [...editToolSystemPromptContribution.guidelines],
```

`edits` 参数归一（`edit.ts:98-125`）：字符串 JSON、单对象、legacy `oldText`/`newText` 三形态都折成 `edits[]`。

写前算 diff、保留未改行 EOL（`edit.ts:188-201`）：

```ts
const { baseContent, newContent, replacements } = applyEditsToNormalizedContent(...);
// Diff before writing, so diff computation cannot fail after the write.
const editDiff = generateEditDiff(path, baseContent, newContent, replacements);
...
// Splice into the original text, so untouched lines keep their line endings. Without any CR
// the original is the normalized text, and the normalized result is already final.
const finalContent =
	bom + (content === normalizedContent ? newContent : applyReplacementsToOriginal(content, replacements));
```

`applyReplacementsToOriginal`（`edit-diff.ts:21-61`）把替换区间映射回原文本偏移，只重写被触及的行；新文本内部换行取**该区间所在行**的行尾（`CRLF`／`LF`／孤 CR）：

```ts
const ending = getLineEndingAt(originalContent, start);
push(ending === "\n" ? replacement.newText : replacement.newText.replaceAll("\n", ending));
```

### 2.5 `bash` / `powershell`

超时错误携带 shell 提前退出事实（`bash.ts:100-105,143-186,405-412`）：

```ts
class ShellTimeoutError extends Error {
	readonly shellExit: { exitCode: number; afterSeconds: number } | undefined;
	constructor(timeout, shellExit) {
		super(`timeout:${timeout}`);
		this.shellExit = shellExit;
	}
}
...
if (timedOut) {
	throw new ShellTimeoutError(timeout,
		exitedBeforeDeadlineAfterMs !== undefined
			? { exitCode: resolvedExitCode, afterSeconds: Math.round(exitedBeforeDeadlineAfterMs / 100) / 10 }
			: undefined);
}
...
let status = `Command timed out after ${timeoutSecs} seconds`;
if (err instanceof ShellTimeoutError && err.shellExit) {
	status += `. The shell exited with code ${err.shellExit.exitCode} after ${err.shellExit.afterSeconds} seconds, but background processes it started kept writing output until the timeout.`;
}
```

信号终止的退出码与无退出码（`:175-178,420-421`）：

```ts
const resolvedExitCode = exitCode ?? (signalCode ? 128 + (osConstants.signals[signalCode] ?? 0) : 1);
...
throw new Error(appendStatus(outputText, "Command terminated without an exit code"));
```

PowerShell 5.1 原生退出码修正（`powershell.ts:19-26,46-52`）：

```ts
const WINDOWS_POWERSHELL_EXIT_SUFFIX =
	'\n;\nif ($?) { exit 0 }\nif (($LASTEXITCODE -eq 0) -and ("$($Error[0].FullyQualifiedErrorId)" -like "NativeCommandError*")) { exit 0 }\nexit 1';
...
const suffix =
	win32.basename(shellConfig.shell).toLowerCase() === "powershell.exe" && !command.endsWith("`")
		? WINDOWS_POWERSHELL_EXIT_SUFFIX
		: "";
```

### 2.6 截断（`.../truncate.ts`）

`:268-298`：

```ts
export function truncateLine(line, maxChars = GREP_MAX_LINE_LENGTH) {
	if (line.length <= maxChars) return { text: line, wasTruncated: false };
	return { text: `${sliceHead(line, maxChars)}... [truncated]`, wasTruncated: true };
}
export function sliceHead(text: string, maxUnits: number): string {
	const end = Math.max(0, maxUnits);
	return isHighSurrogate(text.charCodeAt(end - 1)) && isLowSurrogate(text.charCodeAt(end))
		? text.slice(0, end - 1)
		: text.slice(0, end);
}
```

⇒ 按 UTF-16 切时若切点落在代理对中间，把整个字符留在切口之外（不产生半个 emoji）。

## 3. Java 现状与差异

| # | 单元 | Java 现状（锚点 main） | 与 pi 的可见差异 |
|---|---|---|---|
| 1 | `find` | `FindTool.java:66-80` 旧 schema 文案；`:112-123` 无 `max(1)`、不剥 `./`、不归一 `\`；`:118-122` 对所有路径模式补 `**/` 前缀 ⇒ 相对模式不锚定搜索根；NIO `PathMatcher`（`:123`）大小写随平台 | 模式语义、limit=0、schema 文案 |
| 2 | `read` | `ReadTool.java:95` 恒 `readBinary` 整文件；`:105-117` 用 `split`＋`subList`；`:123-128` 首行超限缺 `is SIZE`；`:129-138` 截断告示不分 `truncatedBy`，恒带 `(50KB limit)`；`:49-55` 描述缺最后一句 | 描述、两条告示、流式内存画像 |
| 3 | `edit` | `EditTool.java:53-64` 旧四条 guidelines；`:65-70` 描述缺末句；`:72-94` 旧 schema 文案；`:102-113` 不做输入归一；`:143-157` 先写后算 diff；`EditDiff.java:49-67` 整文件检测单一 EOL 后 `restoreLineEndings` ⇒ 未改行 EOL 被改写、孤立 CR 丢失 | 提示/schema、输入健壮性、EOL 保真 |
| 4 | `grep` | `GrepTool.java:79-81` 旧 glob 文案；`:163-175` 每条匹配独立展开窗口 ⇒ 上下文重复、匹配行在邻窗按 `-` 重复；`:176-179` 一到 limit 就置 `matchLimitReached` ⇒ 恰好 N 条也发 limit 告示；`:224-232` 不剥 `./`；`:363-370` `\r\n→\n` 后孤立 CR 留在行内 | schema 文案、上下文去重、limit 证明、`./` |
| 5 | `bash` | `ShellToolSupport.java:154-159` 超时只报 `Command timed out after N seconds`；`ShellResult` 无「shell 已提前退出」字段；`ShellProcessEngine.java:94-127` 超时即销毁进程、不记退出时刻；`PowerShellTool.java:62-64` 无 5.1 退出码后缀 | 超时详述、PowerShell 退出码 |
| 6 | 截断 | `TruncationUtils.java:28-33` `substring(0, maxChars)` 可切开代理对 | 长行截断可能产生半个 emoji |

## 4. 目标结构

### 4.1 `find`

1. 新增共享 glob 匹配器 `com.pijava.agent.tool.GlobPattern`（从 `GrepTool.globToRegex` 上移）：
   - `**/` ＝ 零或多段；裸 `**` ＝ 跨段任意；`*` ＝ 段内任意（含 dotfile）；`?` ＝ 段内单字；`[...]`（`!` 取反）；`{a,b}` 支选；`+(...)` 按字面。
   - `match(String posixPath, boolean ignoreCase)`。
2. `FindTool` 的模式预处理（[内部]）：
   - Windows 下 `pattern.replace('\\','/')`；
   - 反复剥离开头 `./`；
   - `isPathPattern = pattern.contains("/")`；
   - `ignoreCase = pattern.toLowerCase(ROOT).equals(pattern)`（smart case）；
   - 相对路径模式（含 `/` 且非绝对）对**根相对 posix 路径**匹配；绝对模式对**绝对 posix 路径**匹配；无 `/` 对 basename 匹配。
3. `effectiveLimit = max(1, floor(limit ?? 1000))`；达到 limit 终止遍历。
4. 输出仍为根相对 posix 路径；目录与文件都入选；`.git` 跳过、gitignore 过滤不动。
5. schema 文案换成 pi `:27-30` 逐字。

### 4.2 `grep`

1. 引擎仍为纯 Java（批 3 裁决①）；`FileSystem.readBinary`/整文件读保留，D3 继续登记。
2. glob：先做 `glob.replaceAll("^(!?)(?:\\./)+", "$1")`，再走共享 `GlobPattern`；含 `/` 锚定根相对路径，无 `/` 匹配 basename。
3. 上下文改为 rg 事件语义（按行去重）：
   - 每个文件先收集全部行与匹配行号；
   - 输出区间＝已接受匹配的 `[m-C, m+C]` 并集，同一行只输出一次；
   - 区间内若是已接受匹配 ⇒ `path:N: text`，否则 `path-N- text`；
   - 接受满 `effectiveLimit` 条后，只保留最后一条的尾部窗口；窗口内后续匹配按上下文输出；窗口外再出现匹配 ⇒ `matchLimitReached = true` 并停止；
   - 恰好 N 条且无下一条 ⇒ `matchLimitReached = false`（不发 limit 告示）。
4. 行文本剥行尾：按 `\n` 切分后去掉一个尾部 `\r`（等价 pi 的 `/\r?\n?$/`）；孤立 CR 留在行内。
5. schema glob 文案换成 pi `:23-28` 逐字。

### 4.3 `read`

1. `FileSystem` 新增默认方法 `InputStream openRead(String path)`：
   - 默认实现 `new ByteArrayInputStream(readBinary(path))`（自定义 FS 零改签）；
   - `DefaultFileSystem` 覆写 `Files.newInputStream(Path.of(path))`。
2. 新增包内 `StreamingLineSelector`（建议 ≤200 行）：
   - 仅当 `offset` 缺省或整型、`limit` 缺省或整型 ≥0 时启用（pi `isStreamableRange`）；
   - 增量 UTF-8 解码（`CharsetDecoder`），保留 BOM；
   - 按 `\n` 切行；选中区间 `[startLine, startLine+limit)`；
   - 只保留到截断上限所需的行为止，其余仅计数；
   - 每块检查 `signal.aborted` ⇒ `Operation aborted`。
3. 非流式范围继续走整文件路径（Java `int` 参数语义不变）。
4. 描述末句、首行超限告示（带 `is SIZE`）、截断告示按 `truncatedBy` 分支全部对齐 pi。

### 4.4 `edit`

1. `EditDiff.AppliedEdits` 增加 `List<TextReplacement>`（`matchIndex/matchLength/newText`）。
2. 新增 `applyReplacementsToOriginal(originalContent, replacements)`：
   - 只重写被触及的行块；
   - 新文本内部 `\n` 换成替换起点所在行的行尾（`\r\n`／`\n`／孤 `\r`）；
   - 拼接时防「CR＋LF」合并（pi `push()` 的补 LF 逻辑）。
3. `EditTool` 写前算 diff；`bom + (原文本 == 归一文本 ? 新文本 : splice 结果)`；保留 `.bak` 与 `FileMutationQueue`（[内部]既有 Java 安全措施，不动）。
4. 输入归一（`prepareEditArguments` 对位）：`edits` 为 JSON 字符串、单对象、legacy `oldText/newText` 三形态。
5. 描述、guidelines（两条）、schema 文案逐字对齐 pi。

### 4.5 `bash` / `powershell`

1. `ShellResult` 增加可空组件 `timeoutShellExit`（`record(int exitCode, double afterSeconds)`）：
   - `ShellProcessEngine` 记录 `process.onExit()` 时刻；超时且进程已退出 ⇒ 填该字段；
   - `ShellToolSupport` 在超时文案后追加 pi 的整句（exit code／after seconds）。
2. `PowerShellTool.wrapCommand` 按 pi：只对 `powershell.exe` 且命令不以反引号结尾时追加 `WINDOWS_POWERSHELL_EXIT_SUFFIX`；`pwsh.exe` 不加。
3. 「Command terminated without an exit code」在 Java 不可达（`ShellResult.exitCode` 恒 `int`），维持 D6。
4. 信号退出：Java 由 OS 提供 128+N 退出码；若实测不符再登记窄差，不在本批虚构。

### 4.6 截断

1. `TruncationUtils` 增加 `sliceHead(String, int)`（代理对安全）与 `sliceTail`（备用）。
2. `truncateLine` 改用 `sliceHead`；`truncateTail` 的字节回退已是 UTF-8 边界安全，不动。
3. MCP 的 `truncateMiddle` 不在本批（L2 已闭环）。

## 5. 裁决

> 【场景】`find` 的路径模式在 Windows 与 POSIX 上由 fd／minimatch 分别实现；Java 只有一套 NIO `PathMatcher`，且 `**` 不吃零段。
> 【决定】抽出共享 `GlobPattern`（regex 翻译），在所有平台统一用 fd 语义：含 `/` 的相对模式锚定搜索根；`**/` 零或多段；大小写按 smart case；Windows 先归一 `\` 与 `./`。
> 【风险/代价】与 minimatch 的极端扩展语法（如 `+(a|b)`、嵌套花括号）仍可能不完全一致；主流 glob 一致。[模型可见：匹配结果] 触发重裁条件：用户报告相对模式漏匹配。

> 【场景】`grep` 的上下文原先由 Java 逐匹配展开窗口，重叠区重复输出；pi 由 ripgrep 事件流按行只输出一次，并延迟判定 limit。
> 【决定】保留纯 Java 引擎，但按 rg 事件语义重写输出装配：窗口并集、按行去重、limit 后只保留尾部窗口、只有窗口外再出现匹配才置 `matchLimitReached`。
> 【风险/代价】实现为两遍扫描（先收集匹配行号再输出），大文件内存画像仍受 D3 窄差约束；输出行序、前缀与告示与 pi 一致。[模型可见]

> 【场景】`read` 的整文件读取在超大文件上有内存压力；pi 新锚点引入 `createReadStream` 只保留展示行。
> 【决定】在 `FileSystem` 上加默认 `openRead`（默认回落 `readBinary`，自定义 FS 零改签），`DefaultFileSystem` 用 NIO 流；只有整型 offset/limit 走流式，其余仍走整文件以保留 JS slice 语义。
> 【风险/代价】两条路径必须产出逐字节相同的选择结果，夹具做对拍；非整型范围的边角语义保持不变。[内部：内存画像]

> 【场景】`edit` 原先按首个 CRLF 判定整文件行尾并全局重写，未改行也会被改写；pi 只从原文本拼接被触及的行块。
> 【决定】`AppliedEdits` 暴露替换区间，新增 `applyReplacementsToOriginal` 做原文本拼接；新文本换行取替换起点所在行的行尾；diff 在写入前计算。
> 【风险/代价】模糊匹配路径下替换区间要映射到原文本行块，行数不一致时抛错（pi 同）。[模型可见：文件字节]

> 【场景】`bash` 超时前 shell 可能已退出，只是后台进程仍持有输出管道；pi 在超时文案里报告该 shell 的退出码与退出时间。
> 【决定】`ShellResult` 增加 `timeoutShellExit`；引擎在超时分支用 `process.onExit()` 记录的时刻判定，工具层追加 pi 整句。
> 【风险/代价】虚拟线程仍可能在管道关闭前等待；判定只影响文案，不影响销毁进程。 [模型可见]

> 【场景】PowerShell 5.1 把重定向的原生命令 stderr 当错误记录，导致 `git push 2>&1 | ...` 退出 1；pi 追加退出码修正后缀。
> 【决定】`PowerShellTool.wrapCommand` 对 `powershell.exe` 且命令不以反引号结尾时追加 pi 的后缀；`pwsh.exe` 保持原样。
> 【风险/代价】后缀可能改变用户脚本的「最后一条语句失败」语义，但这是 pi 对齐目标；反引号结尾仍按原命令执行。 [模型可见：退出码]

> 【场景】`truncateLine` 用 UTF-16 下标直接切，切点可能在代理对中间，产生半个 emoji。
> 【决定】引入 `sliceHead`，切点落在高低代理之间时回退一个单元；`truncateTail` 保持字节边界回退。
> 【风险/代价】长行长度由 `maxChars` 改为「最多 maxChars 个 UTF-16 单元，可能少 1」；grep 告示不变。 [模型可见：截断长度]

## 6. 夹具

| 层 | 夹具 |
|---|---|
| find | `FindToolTest`：`./src/*.ts` 只中根下 `src/`、不中 `pkg/src/`；`**/src/*.ts` 两者都中；`limit=0` ⇒ 1 条；Windows `\` 归一（模拟 pattern 文本）；smart case（全小写忽略大小写、含大写敏感）；schema 文案断言 |
| grep | `GrepToolTest`：两匹配窗口重叠只输出一次；limit 恰好 N 条无 limit 告示；N+1 条有告示且最后匹配的尾上下文保留；窗口内额外匹配按 `-`；`./` 剥离（`!` 保留）；行尾孤 CR 保留、CRLF 去掉 CR；schema 文案 |
| read | `ReadToolTest`：流式与整文件选择同一大文件结果逐字节相等；首行超限文案含 `is 50.0KB`；行数截断告示不带 `(50KB limit)`、字节截断带；描述末句；abort 中断流 |
| edit | `EditToolTest`／`EditDiffTest`：CRLF 文件只改一行 ⇒ 其余行仍 CRLF；LF 文件中新文本 `\n` 不变；孤立 CR 文件保留孤 CR；diff 在写入前生成（写失败不影响 diff 断言可用 mock FS）；`edits` 字符串/单对象/legacy 三形态归一；描述/guidelines/schema 文案 |
| bash | `BashToolTest`：模拟 shell 提前退出 + 后台进程拖住管道 ⇒ 超时文案含 `. The shell exited with code N after X seconds, ...`；无该事实时只有首句；`ShellResult` 新组件缺省 `null` |
| powershell | `PowerShellToolTest`：`powershell.exe` 且命令无反引号 ⇒ 命令含后缀；`pwsh.exe` 或反引号结尾 ⇒ 不含 |
| 截断 | `TruncationUtilsTest`：`truncateLine` 切点落在 emoji 代理对之间 ⇒ 不产生孤立代理；`maxChars` 边界 |

验收命令：

```bash
mvn test -pl pi-java-agent-core -am
mvn test -pl pi-java-coding-agent -am
```

## 7. 读数与台账

- `map/03` 工具表的 `find`／`read`／`edit`／`grep`／`bash`／截断行：`部分` → **对齐**，并补证据行（新锚点 `file:line`）。
- L3 记 **178/178＝100%**；`docs/05` §1.2 L3 行 169.5 → 178.0，合计 1,288.0 → **1,296.5/1,397＝92.8%**。
- `docs/09` §3「当前」列 L3 同步；§4 下一批改为 L4（session 归属复核）。
- 不新增 B／D 编号；D1–D8 原样保留（D5/D8 的 find 模式差异被本批缩窄，但方言部分仍登记）。

## 8. 提交切分

| 序 | 内容 | 模块 | 规模 |
|---|---|---|---|
| 1 | 本设计稿 | docs | S |
| 2 | `feat(agent-core): align find glob semantics`（共享 `GlobPattern`＋find＋夹具） | agent-core | M |
| 3 | `feat(agent-core): align grep context output`（grep＋夹具） | agent-core | M |
| 4 | `feat(agent-core): stream read and align notices`（FileSystem 流＋read＋夹具） | agent-core | M |
| 5 | `feat(agent-core): preserve edit line endings`（edit-diff＋edit＋夹具） | agent-core | M |
| 6 | `feat(agent-core): report bash timeout shell exit`＋PowerShell 后缀＋截断代理对 | agent-core | M |
| 7 | `docs: close L3`（读数回填） | docs | S |

提交 2–6 各自可独立编译；共享 `GlobPattern` 落在 2，3 复用。
