# 22 agent-core 批 3 设计 —— 内置工具参数面（bash/grep/find/ls）＋ 三条裁决

> 基线：pi `ea448f454`（2026-10-11）；pi-java main（批 2 闭环后 `631db32f`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：[`21-agent-core-batch2-design.md`](21-agent-core-batch2-design.md)（事件面＋prepareRequest＋四裁决，已闭环）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围（用户 2026-10-17 选定） | map/03 工具表四条存疑：**bash**（w3）、**grep**（w3）、**find**（w2，java 现名 `glob`）、**ls**（w2）；powershell（w1 缺失）裁决留账（§5.2） |
| 基线账面 | 148.5/183 ≈ **81.1%**（未加权 53/92 = 57.6%；工具表 15.0/21 = 71.4%） |
| 目标账面 | **153.5/183 ≈ 83.9%**（+5.0：bash/grep 各 +1.5，find/ls 各 +1.0）；未加权 57/92 ≈ 62.0%；工具表 20.0/21 ≈ **95.2%**；**权重 3 层含工具 27/27 = 100%** |
| 重件 | grep/find 引擎面（gitignore 下沉 agent-core＋输出形重建＋find 改名） |
| 中件 | bash 超时面＋文案逐字；ls 参数集换血（`recursive`→`limit`） |
| 裁决 | ① rg/fd 外壳与 tools-manager 下载子系统不移植（§5.1）；② powershell 留账 B241 随 B236 批（§5.2）；③ PI_\* 会话环境变量面登记 B242（§5.3） |
| 不碰 | 引擎/事件面/JSONL 线格式；`BashUpdateEmitter` 流式节奏；`truncateMiddle`（MCP 独立预算） |

四条存疑的共同根因：java 四工具是「形似」实现——参数集、默认值、输出形、告示文案全部自成一套，
而 pi 的模型可见契约（schema 描述、默认 limit、截断告示、`Full output:` 行文）是逐字可钉的。
本批把四工具的**模型可见面**全部逐字化；执行引擎留在纯 Java（裁决①），方言差进窄差表（§5.4）。

---

## 1. bash：超时面 ＋ 文案逐字（w3 存疑 → 对齐）

### 1.1 pi 参照（逐字）

**上限与校验**（`core/tools/bash.ts:22,27-37`）：

```ts
const MAX_TIMEOUT_MS = 2_147_483_647;
const MAX_TIMEOUT_SECONDS = MAX_TIMEOUT_MS / 1000;

function resolveTimeoutMs(timeout: number | undefined): number | undefined {
	if (timeout === undefined) return undefined;
	if (!Number.isFinite(timeout) || timeout <= 0) {
		throw new Error("Invalid timeout: must be a finite number of seconds");
	}
	const timeoutMs = timeout * 1000;
	if (timeoutMs > MAX_TIMEOUT_MS) {
		throw new Error(`Invalid timeout: maximum is ${MAX_TIMEOUT_SECONDS} seconds`);
	}
	return timeoutMs;
}
```

**schema**（`bash.ts:40-43`）：

```ts
const bashSchema = Type.Object({
	command: Type.String({ description: "Shell command to execute" }),
	timeout: Type.Optional(Type.Number({ description: "Timeout in seconds (optional, no default timeout)" })),
});
```

**description**（`bash.ts:261-263`，`config.shellName` 对 bash 恒为 `"bash"`）：

```ts
description: `Execute a ${config.shellName} command in the current working directory. Returns stdout and stderr. Output is truncated to last ${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.`,
```

⇒ 逐字：`Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last 2000 lines or 50KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.`
（pi 在 Windows 跑 Git Bash 但**不告诉模型**——java 的 `shellNote()` Windows 提示是超集，删。）

**超时错误文本**（`bash.ts:347-351`）：`Command timed out after ${timeoutSecs} seconds`——
`timeoutSecs` 是**用户原始秒值**（`err.message.split(":")[1]`，可小数 `"0.5"`、整值 `"30"` 不带 `.0`），
前缀是经 `formatOutput` 处理的全量部分输出（`appendStatus(text, status)`：`text ? text+"\n\n"+status : status`）。

**截断 notice 三分支**（`bash.ts:317-330`）：

```ts
if (truncation.lastLinePartial) {
	text += `\n\n[Showing last ${formatSize(truncation.outputBytes)} of line ${endLine} (line is ${lastLineSize}). Full output: ${snapshot.fullOutputPath}]`;
} else if (truncation.truncatedBy === "lines") {
	text += `\n\n[Showing lines ${startLine}-${endLine} of ${truncation.totalLines}. Full output: ${snapshot.fullOutputPath}]`;
} else {
	text += `\n\n[Showing lines ${startLine}-${endLine} of ${truncation.totalLines} (${formatSize(DEFAULT_MAX_BYTES)} limit). Full output: ${snapshot.fullOutputPath}]`;
}
```

注意：`truncatedBy === "lines"` 分支**不带** `(50.0KB limit)` 说明；`lastLineSize` 是**最后一行**的字节数
（`output.getLastLineBytes()`），不是全量输出字节数。

**非零退出是 resolve 不是 reject**（`bash.ts:355-364`）：`isError: true` 的结果，
content = `appendStatus(outputText, \`Command exited with code ${exitCode}\`)`，details 保留。

**临时文件**（`utils/output-files.ts:17-20`）：`$TMPDIR/pi-bash-<16 hex>.log`，0600，`wx`；
前缀来自 `tempFilePrefix: "pi-bash"`（`bash.ts:435`）。

**promptSnippet/guidelines**（`bash.ts:45-48`）：snippet `"Execute bash commands (ls, grep, find, etc.)"`；
guidelines 是 PI_\* 那条 ⇒ 随 B242（§5.3），本批不加。

### 1.2 差距清单（java 现状 → pi）

| # | java 现状（`BashTool.java`） | pi | 处置 |
|---|---|---|---|
| 1 | 默认 120 s（`:36-37,110-111`） | **无默认**（undefined⇒无超时） | 删默认，`OptionalLong.empty()` |
| 2 | 上限 600 s（`:38-39,112-115`），错文 `Timeout exceeds maximum of 600 seconds` | 上限 2_147_483_647 **ms**，错文 `Invalid timeout: maximum is 2147483.647 seconds` | 逐字 |
| 3 | `timeout<=0` 静默落默认（`:110`） | `<=0`/非有限 ⇒ throw `Invalid timeout: must be a finite number of seconds` | 逐字 |
| 4 | `Optional<Long>` 整秒（`:43,96-98`）——`0.5` 被 `longValue()` 截成 0 | `Type.Number` 小数秒（×1000 ms） | `Optional<Double>`＋`ShellOptions` 改毫秒（§1.3） |
| 5 | 描述自带 `(default 120, max 600; …)`＋`shellNote()` Windows 段（`:57-64,223-232`）；command 描述 `Bash command to execute` | 逐字见 §1.1；command 描述 `Shell command to execute` | 逐字；`shellNote()` 删除 |
| 6 | 超时报文 `…\n\nPartial output before timeout:\n<2000 字截断>`（`:140-147`） | 全量部分输出走 `formatOutput`（truncateTail＋temp 文件＋notice）再 `appendStatus` | 按 pi 结构重写（§1.3） |
| 7 | notice 恒带 `(50.0KB limit)`＋`Full output saved to: …`（`:170-176`） | 三分支；`Full output: …` | 逐字三分支 |
| 8 | `lastLinePartial` 分支的 line size 用**全量**字节（`:168`） | 最后一行字节 | 修 |
| 9 | 非零退出 `throw RuntimeException`（`:181-184`） | resolve `isError:true`（content 带 outputText＋状态行，details 保留） | 改返 `ToolResult`（`isError=true`） |
| 10 | temp 文件 `bash-output-*.txt`（`:240`） | `pi-bash-<hex>.log` | `Files.createTempFile("pi-bash-", ".log")` |
| 11 | 无 `promptSnippet` 覆写 | `"Execute bash commands (ls, grep, find, etc.)"` | 覆写 |

### 1.3 java 待写代码

**`BashTool` 常量与校验**（pi `bash.ts:22,27-37` 对位）：

```java
/** pi {@code bash.ts:22}：{@code 2_147_483_647} ms（≙ 2147483.647 s）。 */
private static final long MAX_TIMEOUT_MS = 2_147_483_647L;

/** pi {@code resolveTimeoutMs}（bash.ts:27-37）：{@code null} ≙ undefined ⇒ 返回值 {@code -1} 表无超时。 */
private static long resolveTimeoutMs(Double timeout) {
    if (timeout == null) return -1;
    if (!Double.isFinite(timeout) || timeout <= 0) {
        throw new IllegalArgumentException("Invalid timeout: must be a finite number of seconds");
    }
    double timeoutMs = timeout * 1000;
    if (timeoutMs > MAX_TIMEOUT_MS) {
        throw new IllegalArgumentException(
            "Invalid timeout: maximum is " + MAX_TIMEOUT_MS / 1000.0 + " seconds");
    }
    return (long) timeoutMs;
}

/** JS {@code String(number)}：整值去小数点（30.0→"30"），其余原样（0.5→"0.5"）。pi bash.ts:350 的回显值。 */
private static String jsNumber(double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e15
        ? Long.toString((long) v) : Double.toString(v);
}
```

`MAX_TIMEOUT_MS / 1000.0` 的 `Double.toString` 恰为 `"2147483.647"` ✓（夹具钉死）。

**输入记录与 prepareArguments**：

```java
public record BashInput(String command, Optional<Double> timeoutSeconds) {}
// prepareArguments：Number n → Optional.of(n.doubleValue())
```

**execute 起手**（无默认超时；毫秒进 `ShellOptions`）：

```java
long timeoutMs = resolveTimeoutMs(params.timeoutSeconds().orElse(null));
…
var options = new ShellOptions(context.cwd(), context.env(), true,
    timeoutMs < 0 ? OptionalLong.empty() : OptionalLong.of(timeoutMs), signal, emitter);
```

**`ShellOptions`/`DefaultShellExecutor` 改毫秒**（两个生产调用点＋测试）：

```java
// ShellOptions.java:11：OptionalLong timeoutSeconds → OptionalLong timeoutMillis（javadoc 同步）
// DefaultShellExecutor.java:109-110：
if (options.timeoutMillis().isPresent()) {
    exitCode = future.get(options.timeoutMillis().getAsLong(), TimeUnit.MILLISECONDS);
} else {
    exitCode = future.get();
}
// AgentSession.java:1161-1163（RPC bash）：OptionalLong.empty() 不变，仅组件改名
```

**输出成形抽 `formatOutput`**（pi `bash.ts:306-333` 结构：成功/超时/非零退出三路共用）：

```java
/** pi {@code formatOutput}（bash.ts:316-333）：truncateTail＋截断时落 temp 文件＋三分支 notice。 */
private static FormatOut formatOutput(String fullOutput, String emptyText) throws IOException {
    var truncation = TruncationUtils.truncateTail(fullOutput);
    String text = truncation.content().isEmpty() ? emptyText : truncation.content();
    BashDetails details = null;
    if (truncation.truncated()) {
        String tempPath = saveTempFile(fullOutput);
        details = new BashDetails(truncation, tempPath);
        int startLine = truncation.totalLines() - truncation.outputLines() + 1;
        int endLine = truncation.totalLines();
        if (truncation.lastLinePartial()) {
            text += "\n\n[Showing last " + TruncationUtils.formatSize(truncation.outputBytes())
                + " of line " + endLine + " (line is " + lastLineSize(fullOutput) + ")."
                + " Full output: " + tempPath + "]";
        } else if ("lines".equals(truncation.truncatedBy())) {
            text += "\n\n[Showing lines " + startLine + "-" + endLine
                + " of " + truncation.totalLines() + ". Full output: " + tempPath + "]";
        } else {
            text += "\n\n[Showing lines " + startLine + "-" + endLine
                + " of " + truncation.totalLines()
                + " (" + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                + " limit). Full output: " + tempPath + "]";
        }
    }
    return new FormatOut(text, details);
}
private record FormatOut(String text, BashDetails details) {}

/** pi {@code appendStatus}（bash.ts:335）。 */
private static String appendStatus(String text, String status) {
    return (text.isEmpty() ? "" : text + "\n\n") + status;
}

/** pi {@code output.getLastLineBytes()}：全量输出最后一行的 UTF-8 字节数。 */
private static String lastLineSize(String fullOutput) {
    int idx = fullOutput.lastIndexOf('\n');
    String last = idx < 0 ? fullOutput : fullOutput.substring(idx + 1);
    return TruncationUtils.formatSize(last.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
}
```

**三路收尾**（pi `bash.ts:337-366`）：

```java
if (shellResult.timedOut()) {
    var snap = formatOutput(shellResult.output(), "");
    throw new RuntimeException(appendStatus(snap.text(),
        "Command timed out after " + jsNumber(params.timeoutSeconds().orElseThrow()) + " seconds"));
}
var snap = formatOutput(shellResult.output(), "(no output)");
if (shellResult.exitCode() != 0) {
    // pi resolve(isError:true)，不 reject（bash.ts:355-364）
    return new ToolResult<>(
        List.of(new ContentBlock.TextContent(
            appendStatus(snap.text(), "Command exited with code " + shellResult.exitCode()))),
        snap.details(), null, false, List.of(), true, null);
}
return new ToolResult<>(List.of(new ContentBlock.TextContent(snap.text())),
    snap.details(), null, false, List.of());
```

**temp 文件**：`Files.createTempFile("pi-bash-", ".log")`（POSIX 默认 0600 与 pi 同；随机段形状差 `<16hex>` vs JDK 长数不钉）。

**description/schema/snippet**：按 §1.1 逐字（`DEFAULT_MAX_LINES`／`DEFAULT_MAX_BYTES / 1024` 现算）；
删 `shellNote()` 与 `(default 120, max 600…)` 尾巴。

**测试迁移**：`BashToolTest` 的 120/600 断言（`:79,98,102-114`）→ 新真值表：缺省⇒`OptionalLong.empty`、
`0.5`⇒500ms、`0`/负⇒错文、`2147483.648`⇒max 错文、`2147483.647`⇒通过、超时回显 `30`（不带 `.0`）；
notice 三分支钉逐字；非零退出断言 `isError=true` 且 content 合形。

---

## 2. grep：补四参数 ＋ gitignore ＋ 输出形（w3 存疑 → 对齐）

### 2.1 pi 参照（逐字）

**schema 七参**（`core/tools/grep.ts:22-32`）：

```ts
const grepSchema = Type.Object({
	pattern: Type.String({ description: "Search pattern (regex or literal string)" }),
	path: Type.Optional(Type.String({ description: "Directory or file to search (default: current directory)" })),
	glob: Type.Optional(Type.String({ description: "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'" })),
	ignoreCase: Type.Optional(Type.Boolean({ description: "Case-insensitive search (default: false)" })),
	literal: Type.Optional(
		Type.Boolean({ description: "Treat pattern as literal string instead of regex (default: false)" })),
	context: Type.Optional(
		Type.Number({ description: "Number of lines to show before and after each match (default: 0)" })),
	limit: Type.Optional(Type.Number({ description: "Maximum number of matches to return (default: 100)" })),
});
```

**description**（`grep.ts:84-85`）：
`Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore. Output is truncated to 100 matches or 50KB (whichever is hit first). Long lines are truncated to 500 chars.`
（`DEFAULT_LIMIT=100`（`:42`）、`DEFAULT_MAX_BYTES/1024`、`GREP_MAX_LINE_LENGTH=500`（`truncate.ts:13`）现算。）

**核心流程**（`grep.ts:86-318`）：`ensureTool("rg")` → `rg --json --line-number --color=never --hidden`
（＋`--ignore-case`/`--fixed-strings`/`--glob g`）`-- pattern searchPath`；流式收 match 事件，
`matchCount >= effectiveLimit`（`Math.max(1, limit ?? 100)`，`:122`）即杀子进程并置 `matchLimitReached`；
path 是文件时输出行用 `basename`，目录时相对 searchPath 且 `\`→`/`（`formatPath :123-131`）；
匹配行 `path:N: text`、上下文行 `path-N- text`，上下文窗口 `[max(1,N-C), min(len,N+C)]`（`:184-186`），
每行过 `truncateLine`（500 字＋`... [truncated]`，`truncate.ts:288-294`）；
`matchCount === 0` ⇒ 逐字 `No matches found`（`:243`）；
终局 `truncateHead(rawOutput, { maxLines: Number.MAX_SAFE_INTEGER })`（纯字节 50KB，`:261`）；
notices 序（`:264-278`）：

```ts
if (matchLimitReached)  notices.push(`${effectiveLimit} matches limit reached. Use limit=${effectiveLimit * 2} for more, or refine pattern`);
if (truncation.truncated) notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
if (linesTruncated)     notices.push(`Some lines truncated to ${GREP_MAX_LINE_LENGTH} chars. Use read tool to see full lines`);
if (notices.length > 0) output += `\n\n[${notices.join(". ")}]`;
```

details：`{ truncation?, matchLimitReached?, linesTruncated? }`（`:44-48`）。
path 不存在 ⇒ `Path not found: <searchPath>`（`:119`）。

### 2.2 引擎裁决（详 §5.1）

pi 外壳是 rg（找不到还会联网下载）。本批**不移植 rg/fd 外壳与 tools-manager**，
纯 Java 引擎对齐**模型可见契约**（参数/默认/输出形/告示逐字）；方言与边界差进 §5.4 窄差表。

### 2.3 java 待写代码

**输入/详情记录**：

```java
public record GrepInput(String pattern, Optional<String> path, Optional<String> glob,
        Optional<Boolean> ignoreCase, Optional<Boolean> literal,
        Optional<Integer> context, Optional<Integer> limit) {}

/** pi {@code GrepToolDetails}（grep.ts:44-48）。 */
public record GrepDetails(TruncationUtils.TruncationResult truncation,
        Integer matchLimitReached, Boolean linesTruncated) {}
```

**`TruncationUtils` 补两件**（pi `truncate.ts:13,288-294`）：

```java
/** pi {@code GREP_MAX_LINE_LENGTH}（truncate.ts:13）。 */
public static final int GREP_MAX_LINE_LENGTH = 500;

/** pi {@code truncateLine}：超长截到 {@code maxChars} ＋ {@code "... [truncated]"}。 */
public static LineTruncation truncateLine(String line, int maxChars) { … }
public record LineTruncation(String text, boolean wasTruncated) {}
```

**execute 主体**（对位 `grep.ts:86-318`，纯 Java 引擎）：

```java
String searchPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
Path root = Path.of(searchPath);
boolean isDirectory;
if (Files.isDirectory(root)) { isDirectory = true; }
else if (Files.isRegularFile(root)) { isDirectory = false; }
else { throw new IllegalArgumentException("Path not found: " + searchPath); }

int contextValue = params.context().filter(c -> c > 0).orElse(0);
int effectiveLimit = Math.max(1, params.limit().orElse(DEFAULT_LIMIT)); // pi grep.ts:122

int flags = 0;
if (params.ignoreCase().orElse(false)) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
if (params.literal().orElse(false)) flags |= Pattern.LITERAL;
Pattern pattern = Pattern.compile(params.pattern(), flags);
```

`CASE_INSENSITIVE|UNICODE_CASE` 对位 rg `-i` 的 Unicode 感知；`LITERAL` 对位 `--fixed-strings`。

**候选收集**（gitignore＋`.git` 硬跳过＋glob 过滤；rg `--hidden` ⇒ dotfiles 照搜）：

```java
var ignore = new ToolIgnoreFilter(root);   // §2.4 下沉件
var candidates = new ArrayList<Path>();
Files.walkFileTree(root, new SimpleFileVisitor<>() {
    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
        if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
            return FileVisitResult.SKIP_SUBTREE;      // rg/fd 的 ignore crate 默认不下降 .git
        }
        ignore.push(dir);
        return ignore.isIgnored(ignoreRel(dir), true)
            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
    }
    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
        if (!ignore.isIgnored(ignoreRel(file), false) && globMatches(file)) candidates.add(file);
        return FileVisitResult.CONTINUE;
    }
    @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) {
        ignore.pop(); return FileVisitResult.CONTINUE;
    }
});
```

`globMatches` 的 glob 参数语义按 **rg `--glob`**（gitignore 式）：无 `/` ⇒ 匹配 basename；
有 `/` ⇒ 相对 root 的 posix 路径锚定匹配（`PathMatcher "glob:"+g`）。**注意与 find 的 fd 语义不同**
（fd 要 `--full-path`＋`**/` 前缀），两工具各写各的，不抽公共（§3.2 对照）。

**匹配与成形**（单文件路径时 `isDirectory=false` ⇒ 只搜该文件、输出名取 basename）：

```java
var outputLines = new ArrayList<String>();
var fileCache = new HashMap<Path, List<String>>();   // pi fileCache（grep.ts:133-146）
int matchCount = 0; boolean matchLimitReached = false, linesTruncated = false;
outer:
for (Path file : candidates) {
    if (isBinarySniff(file)) continue;               // §5.4 D3：NUL 嗅探近似 rg 默认跳二进制
    List<String> lines = fileLines(file, fileCache); // \r\n/\r → \n 归一（pi :136-138）
    for (int i = 0; i < lines.size(); i++) {
        if (!pattern.matcher(lines.get(i)).find()) continue;
        matchCount++;
        appendBlock(outputLines, file, i, contextValue, lines, …); // path:N:/path-N- 两形＋truncateLine
        if (matchCount >= effectiveLimit) { matchLimitReached = true; break outer; }
    }
}
if (matchCount == 0) return ToolResult.success("No matches found");  // 逐字（pi :243）
```

**终局截断＋notices**（pi `:260-278` 逐字序）：

```java
var truncation = TruncationUtils.truncateHead(String.join("\n", outputLines),
    Integer.MAX_VALUE, TruncationUtils.DEFAULT_MAX_BYTES);   // ≙ maxLines: Number.MAX_SAFE_INTEGER
String output = truncation.content();
var notices = new ArrayList<String>();
Integer matchLimitDetail = null; Boolean linesTruncatedDetail = null;
if (matchLimitReached) {
    notices.add(effectiveLimit + " matches limit reached. Use limit=" + effectiveLimit * 2
        + " for more, or refine pattern");
    matchLimitDetail = effectiveLimit;
}
if (truncation.truncated()) {
    notices.add(TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES) + " limit reached");
}
if (linesTruncated) {
    notices.add("Some lines truncated to " + TruncationUtils.GREP_MAX_LINE_LENGTH
        + " chars. Use read tool to see full lines");
    linesTruncatedDetail = Boolean.TRUE;
}
if (!notices.isEmpty()) output += "\n\n[" + String.join(". ", notices) + "]";
var details = (matchLimitDetail == null && !truncation.truncated() && linesTruncatedDetail == null)
    ? null : new GrepDetails(truncation.truncated() ? truncation : null,
                             matchLimitDetail, linesTruncatedDetail);
return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
    details, null, false, List.of());
```

**description/schema/snippet**：按 §2.1 逐字（常量现算）；`promptSnippet` 覆写
`"Search file contents for patterns (respects .gitignore)"`（pi `grep.ts:34-37`）。

### 2.4 下沉件：`IgnoreFilter` → agent-core

gitignore 解析**不自研子集**（既定口径，见 `IgnoreFilter` 类注）——JGit `IgnoreNode` 全语义。
现 `IgnoreFilter` 在 coding-agent 的 skill 包且 package-private，agent-core 够不着：

1. 移到 agent-core `com.pijava.agent.tool.ignore.ToolIgnoreFilter`（public；`push/pop/isIgnored/toPosix` 原样）；
2. agent-core pom 加 `org.eclipse.jgit:org.eclipse.jgit`（版本 parent dependencyManagement 已管）；
3. coding-agent `SkillDiscovery` 改 import 复用同一份（`.gitignore/.ignore/.fdignore` 三件套不变 ⇒ 技能扫描行为零漂移）；
4. 窄差 D2：grep 侧 rg 实际只读 `.gitignore`＋`.ignore`（不读 `.fdignore`），java 三件套对 grep 多读一件；嵌套 git 仓库边界（pi `find.ts:171-181` 的 `--no-require-git` 取舍）java 无感 ⇒ 同登 D2。

---

## 3. find（原 glob）：改名 ＋ limit ＋ gitignore ＋ 目录入选（w2 存疑 → 对齐）

### 3.1 pi 参照（逐字）

**schema**（`core/tools/find.ts:29-36`）：

```ts
const findSchema = Type.Object({
	pattern: Type.String({
		description: "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'" }),
	path: Type.Optional(Type.String({ description: "Directory to search in (default: current directory)" })),
	limit: Type.Optional(Type.Number({ description: "Maximum number of results (default: 1000)" })),
});
```

**description**（`find.ts:91-93`）：
`Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore. Output is truncated to 1000 results or 50KB (whichever is hit first).`
**snippet**（`:38-41`）：`Find files by glob pattern (respects .gitignore)`。`DEFAULT_LIMIT = 1000`（`:48`）。

**fd 调用与 pattern 适配**（`find.ts:165-199`）：

```ts
const args: string[] = ["--glob", "--color=never", "--hidden"];
// 仓库外补 --no-require-git（:171-181）；args.push("--max-results", String(effectiveLimit));
let effectivePattern = pattern;
if (pattern.includes("/")) {
	args.push("--full-path");
	if (!pattern.startsWith("/") && !pattern.startsWith("**/") && pattern !== "**") {
		effectivePattern = `**/${pattern}`;
	}
	if (process.platform === "win32")
		effectivePattern = effectivePattern.replaceAll("/", String.raw`[/\\]`);
}
args.push("--", effectivePattern, searchPath);
```

fd `--glob` 默认匹配 **basename**；`--full-path` 匹配绝对候选路径（故 `**/` 前缀）。
**fd 默认文件与目录都出**（无 `--type`）；输出相对化（`relativizeFindResultPath :15-27`：
相对 searchPath、`\`→`/`、fd 若带尾分隔符则保 `/`——实际 fd 不吐尾分隔符）。
`resultLimitReached = relativized.length >= effectiveLimit`（`:282`）；notice
`1000 results limit reached. Use limit=2000 for more, or refine pattern`（fd 分支带后缀，`:284-287`）；
字节 50KB notice 同 grep；空 ⇒ 逐字 `No files found matching pattern`（`:269`）。
details `{ truncation?, resultLimitReached? }`（`:51-54`）。

### 3.2 java 待写代码

**改名**：`GlobTool` → `FindTool`（文件/类/record 全改；`GlobToolTest` → `FindToolTest` 重写）。
name/label `"find"`。改名连带：

| 消费点 | 现状 | 处置 |
|---|---|---|
| `ToolSetFactory:33,45` | `GlobTool.create()` | `FindTool.create()`；**注册序对齐 pi `createAllTools`**（`index.ts:202-212`：read, bash, edit, write, grep, find, ls）——java 现序 bash,read,write,edit,grep,ls,glob 一并修 |
| `SystemPrompts:191` | 已按 `"find"` 判（pi 名） | 零改动（此前就是超前对齐） |
| `--tools` allowlist（`SessionSetup:34`） | 裸名过滤 | 零改动（`--tools find` 自此可用） |
| 历史会话 replay | 旧 `glob` 工具名进 transcript | 留痕（§6）：tool_use 块按名回放，provider 侧历史名不在当前工具表可接受 |

**pattern 语义映射**（fd `--glob`）：

```java
String p = params.pattern();
PathMatcher matcher;
boolean fullPath = p.contains("/");
if (!fullPath) {
    matcher = FileSystems.getDefault().getPathMatcher("glob:" + p);      // 对 basename
} else {
    String eff = (p.startsWith("/") || p.startsWith("**/") || p.equals("**")) ? p : "**/" + p;
    matcher = FileSystems.getDefault().getPathMatcher("glob:" + eff);    // 对相对 posix 路径
}
```

`p.startsWith("/")` 的绝对 globs 对绝对候选路径匹配——Windows 分隔符差登 §5.4 D5，不逐字 `[/\\]`。

**walk**（同 §2.3 的 ignore＋`.git` 跳过；**目录也入选**——`preVisitDirectory` 里对非忽略目录同样过
matcher 并收集，命中即收、达 `effectiveLimit` 即 `SKIP_SUBTREE` 提前终止）：

```java
int effectiveLimit = params.limit().orElse(DEFAULT_LIMIT);   // pi 无 max(1,…)，负值/0 ⇒ 空结果＋limit 告示，照抄
// 收集到 results.size() >= effectiveLimit 即停；resultLimitReached = results.size() >= effectiveLimit
```

**输出/截断/notices/details**：

```java
// 每条：root.relativize(p) → \→/
String raw = String.join("\n", relativized);
var truncation = TruncationUtils.truncateHead(raw, Integer.MAX_VALUE, TruncationUtils.DEFAULT_MAX_BYTES);
// notices：effectiveLimit + " results limit reached. Use limit=" + effectiveLimit * 2
//          + " for more, or refine pattern" ；formatSize(DEFAULT_MAX_BYTES) + " limit reached"
// 空：return ToolResult.success("No files found matching pattern");
// details：FindDetails(truncation, resultLimitReached)
public record FindDetails(TruncationUtils.TruncationResult truncation, Integer resultLimitReached) {}
```

**path 不存在**：pi 的 fd 分支报 fd 自身 stderr（不可逐字）、custom-ops 分支报
`Path not found: <searchPath>`（`find.ts:114`）⇒ java 采后者（D5 登记）。

**description/schema/snippet**：按 §3.1 逐字（`1000`/`50` 常量现算）。

---

## 4. ls：`recursive`→`limit` 参数集换血（w2 存疑 → 对齐）

### 4.1 pi 参照（逐字）

**schema**（`core/tools/ls.ts:12-15`）：

```ts
const lsSchema = Type.Object({
	path: Type.Optional(Type.String({ description: "Directory to list (default: current directory)" })),
	limit: Type.Optional(Type.Number({ description: "Maximum number of entries to return (default: 500)" })),
});
```

**description**（`ls.ts:64-66`）：
`List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. Includes dotfiles. Output is truncated to 500 entries or 50KB (whichever is hit first).`
**snippet**（`:17-20`）：`List directory contents`。`DEFAULT_LIMIT = 500`（`:25`）。

**行为**（`ls.ts:67-172`）：`Path not found: X`（`:96`）／`Not a directory: X`（`:102`）／
`Cannot read directory: <msg>`（`:112`）；条目按 `a.toLowerCase().localeCompare(b.toLowerCase())`
排序（`:119`）；逐条 stat，目录加 `/`，stat 失败**跳过**（`:131-136`）；满 `effectiveLimit` 置
`entryLimitReached` 即停（`:124-127`）；空 ⇒ `(empty directory)`（`:140`）；
字节 50KB truncateHead；notices
`500 entries limit reached. Use limit=1000 for more`（`:153-156`）／`50.0KB limit reached`；
details `{ truncation?, entryLimitReached? }`（`:28-31`）。

### 4.2 java 待写代码

```java
public record LsInput(Optional<String> path, Optional<Integer> limit) {}
public record LsDetails(TruncationUtils.TruncationResult truncation, Integer entryLimitReached) {}
private static final int DEFAULT_LIMIT = 500;
```

**execute**（对位 `ls.ts:86-172`；删 `recursive`、`[DIR]/[FILE]` 前缀、size 列——pi 输出只有名字）：

```java
String dirPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
int effectiveLimit = params.limit().orElse(DEFAULT_LIMIT);
Path dir = Path.of(dirPath);
if (!Files.exists(dir)) throw new IllegalArgumentException("Path not found: " + dirPath);
if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + dirPath);
List<String> entries;
try (var stream = Files.list(dir)) { entries = stream.map(p -> p.getFileName().toString()).toList(); }
catch (IOException e) { throw new IllegalArgumentException("Cannot read directory: " + e.getMessage()); }
// pi :119：toLowerCase 后 localeCompare；java 取确定性码点序（§5.4 D4）
entries = new ArrayList<>(entries);
entries.sort(Comparator.comparing(s -> s.toLowerCase(java.util.Locale.ROOT)));

var results = new ArrayList<String>();
boolean entryLimitReached = false;
for (String entry : entries) {
    if (results.size() >= effectiveLimit) { entryLimitReached = true; break; }
    try {
        if (Files.isDirectory(dir.resolve(entry))) entry += "/";
    } catch (RuntimeException e) { continue; }   // pi :134-136：stat 失败跳过
    results.add(entry);
}
if (results.isEmpty()) return ToolResult.success("(empty directory)");
// truncateHead(纯字节) + notices（" entries limit reached. Use limit=" + effectiveLimit*2 + " for more"）
```

`Files.list` 含 dotfiles（pi 含，`:65` 描述自证）✓；`Files.isDirectory` 对失效符号链接返回 false
而不是抛 ⇒ 「stat 失败跳过」在 java 几乎不可达，留形对齐。

**description/schema/snippet**：按 §4.1 逐字（`500`/`50` 常量现算）。

**测试**：`LsToolTest` 重写——排序（大小写混排）、`/` 后缀、dotfiles、limit 告示、三错误文本、
`(empty directory)`、字节截断告示。

---

## 5. 裁决与登记

### 5.1 裁决①：rg/fd 外壳与 tools-manager 下载子系统不移植

pi 的 grep/find 外壳是 rg/fd 子进程，缺二进制时 `ensureTool` 还会**联网下载**（`utils/tools-manager.ts:1-80`，
GitHub release 资产、10s/120s 双超时、`PI_OFFLINE` 开关）。判：**不移植**——

- 模型可见契约（参数、默认、输出形、告示、gitignore 行为）纯 Java 全能对齐（§2/§3）；
- 引入「下载外部二进制」子系统是新的供应链面（下载/解压/缓存/离线开关），与项目既有先例一致
  （批 9 用 JDK ImageIO 替代 photon；批 3b minimatch 自研子集替代 npm 依赖）；
- 代价是方言窄差（D1–D3），已逐条登记、带触发条件。

**触发条件**（重裁）：出现 rg/fd 特有行为成为硬需求的证据（如超大仓库性能、Rust regex 语法兼容
被模型/用户实际踩中），或 pi 把匹配语义改成非纯文本约定。

### 5.2 裁决②：powershell 工具（w1 缺失）留账 **B241**，随 B236 批

pi 侧形状已取全（`powershell.ts:1-67`＝bash 的 `createShellToolDefinition` 变体：`name/label "powershell"`、
`prompt "PS>"`、`tempFilePrefix "pi-powershell"`、UTF8 前缀
`try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n`、`shell.ts:126-133`
仅 Windows＋`pwsh.exe`→`powershell.exe` 解析）。**但 pi 默认不激活它**
（`DEFAULT_TOOL_NAMES = ["read","bash","edit","write"]`，`settings-manager.ts:83`；
`createCodingTools` 四件，`tools/index.ts:176-182`），激活走 `defaultTools` 修饰符/`--tools` 模式面——
那正是 **B236** 的面。java 现在默认激活全 7 件（`SessionSetup:45`），单把 powershell 做进
`ToolSetFactory` 会立刻变成「默认激活」，比 pi 更偏；做而不注册则零可达。
⇒ 实现＋激活策略（含 `DEFAULT_TOOL_NAMES` 四件默认集差）**整体随 B236 批**落地，本批登记 B241 挂 B236 族。

### 5.3 裁决③：bash 的 PI_\* 会话环境变量面登记 **B242**

pi 的 bash 默认向子进程暴露 `PI_SESSION_ID/PI_SESSION_FILE/PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL`
（`resolveSpawnContext`，`bash.ts:185-213`；`exposeSessionEnvironment` 默认 true），
promptGuidelines 因此带 `You can inspect PI_* environment variables…`（`bash.ts:47`）。
java 全仓零命中（本次 grep 证实），`ToolContext` 拿不到会话身份（在 coding-agent 层）。
跨层接线（ToolContext 增会话身份供应器或 ToolOptions 注入）超出本批 ⇒ 登记 B242；
在此之前 **guideline 不加**（加了是假话），snippet 照加（无 PI_\* 内容）。
同族留痕不登记：`BashOperations`/`spawnHook` 可插拔（扩展 API 面）、
bash `outputSchema`/`structuredContent`（消费者 codemode 是 B161，与批 9「read structuredContent」同真空臂）。

### 5.4 窄差登记（随 map/03 备注回填）

| # | 窄差 | 触发重裁条件 |
|---|---|---|
| D1 | grep 正则方言：Java `Pattern` vs Rust regex——lookaround/反向引用 java 多出面；字面/字符类/量词主流一致 | 模型实际产出 Rust 方言失配 pattern 的案例 |
| D2 | gitignore 解析三件全读（grep 侧 rg 不读 `.fdignore`）；嵌套 git 仓库边界不停（pi `--no-require-git` 取舍 `find.ts:171-181`） | 嵌套仓库场景的可见误报 |
| D3 | 二进制文件：NUL 嗅探（首 8 KiB 含 0x00 即跳过）近似 rg 默认；rg 的 binary 判定更细 | 二进制误中/漏中案例 |
| D4 | ls 排序：`toLowerCase(ROOT)` 码点序 vs pi `localeCompare`（Node locale 序）；ASCII 全等 | 非 ASCII 文件名排序差异被踩 |
| D5 | find 绝对 glob（`/` 开头）的 Windows 分隔符不逐字 `[/\\]`；path 不存在错文采 custom-ops 形 | Windows 上绝对 pattern 实测差异 |
| D6 | bash `Command terminated without an exit code`（`bash.ts:354`）java 不可达（`ShellResult.exitCode` 恒 int） | —（结构性） |
| D7 | fd/rg 遍历序 vs `Files.walkFileTree` 序——两侧都不保证顺序，不逐字 | —（不登记台账，此表留痕） |

---

## 6. 风险与留痕汇总

| 点 | 处置 |
|---|---|
| `glob`→`find` 改名：历史会话 transcript 里的旧工具名回放 | 留痕：tool_use 按名回放、provider 不要求历史名在当前工具表；CLI `--tools glob` 失效即对齐（pi 本无 `glob`） |
| `ls` 删 `recursive`、输出形从 `[DIR]/[FILE]+size` 变裸名列表 | 模型可见破坏式变更，0.1.0-SNAPSHOT 阶段直接改；测试随批重写 |
| bash 非零退出从 throw 改 `isError:true` | 事件面 `tool_execution_end{isError}` 语义不变（throw 路也是 isError）；变的是 details 保留＋content 带输出——正是 pi 形状 |
| `ShellOptions` 组件改名（`timeoutSeconds`→`timeoutMillis`） | 两生产调用点＋测试全在仓内（grep 已核：BashTool、AgentSession:1161、DefaultShellExecutor） |
| agent-core 新增 JGit 依赖 | 版本 parent 已管；技能扫描复用同一份 `ToolIgnoreFilter`，行为零漂移（三件套文件名不变） |
| 描述/snippet/notice 全部常量现算 | 夹具钉逐字终值（`50KB`/`50.0KB` 两形并存是 pi 原样：描述 `DEFAULT_MAX_BYTES/1024`、告示 `formatSize`） |
| `AgentSession` 988 行 E 项 | 本批只动 `:1161` 一个组件名，净增 0 行 |

---

## 7. commit 切分与验证

1. `docs(agent-core): batch-3 design — builtin tool parameter faces + three rulings`（本稿）
2. `feat(agent-core): bash timeout face — no default, 2147483.647s cap, ms precision, verbatim notices`＋`BashToolTest` 迁移
3. `feat(agent-core): move IgnoreFilter to agent-core as ToolIgnoreFilter (jgit dep)`＋coding-agent import 切换（同模块可编译）
4. `feat(agent-core): grep four params + limit/byte truncation notices + gitignore + relative paths`＋`GrepToolTest` 重写
5. `feat(agent-core): rename glob→find + limit + gitignore + dirs included + verbatim contract`＋`ToolSetFactory` 序对齐＋`GlobToolTest`→`FindToolTest` 重写
6. `feat(agent-core): ls limit replacing recursive + sorted bare names + verbatim notices`＋`LsToolTest` 重写
7. `docs(agent-core): batch-3 closure — weighted 81.1->83.9%`（map/03、docs/07、docs/04 B241/B242 回填）

验证：每步 `mvn test -pl pi-java-agent-core -am -Dsurefire.failIfNoSpecifiedTests=false`
（步 3/5 跨模块带 coding-agent：`-pl pi-java-agent-core,pi-java-coding-agent -am`）；
收口前全量 `mvn clean verify` 零错误。JDK25/Maven 显式路径按惯例。

## 8. 目标账面（闭环时回填）

| 表 | 变动 |
|---|---|
| map/03 工具表 bash/grep/find/ls | 存疑 → 对齐（各附本批 commit；powershell 留缺失挂 B241） |
| map/03 工具表汇总 | 15.0/21 = 71.4% → 20.0/21 ≈ **95.2%** |
| map/03 模块合计 | 148.5 → **153.5/183 ≈ 83.9%**；未加权 53 → 57/92 ≈ 62.0%；权重 3 层含工具 **27/27 = 100%** |
| docs/07 §1 | 删 bash/grep/find/ls 四行（#8 powershell 行改挂 B241） |
| docs/07 §2.2 | 「内置工具」行删（四条存疑清零，窄差 D1–D5 入 map/03 备注） |
| docs/04 §3.3 | 新增 **B241**（powershell 实现＋默认激活集，挂 B236 族）、**B242**（PI_\* 会话环境变量面） |
