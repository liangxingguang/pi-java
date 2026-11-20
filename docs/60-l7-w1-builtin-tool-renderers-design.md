# docs/60 — L7 W1b：内置工具渲染器（`core/tools/renderers`）移植设计

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/59` §3 W1（"内置 read 渲染接入 `ReadLineRange`"）、`docs/map/05` R11、docs/25（L5 工具面）、docs/54（examples 渲染器）
- 文档状态：**2026-11-19 用户审核通过并直接实施**（接缝＋8 个渲染器＋单测全绿，见 §8）
- 编号说明：L7 设计稿占 **docs/59**；B161 codemode 设计稿让号到 **docs/61**（用户裁决 2026-11-19）；本稿为 **docs/60**。

## 0. 口径

1. 判定单位：`docs/map/05` 的 **R11（工具渲染）**行。本包只做**默认渲染器**面，不动工具执行、不动参数 schema。
2. 分母：pi `core/tools/renderers/index.ts:32-43` 的 8 个工具名 = **7 个渲染器族**（`bash`/`powershell` 共用 `createShellRenderers(prompt)`）。
3. 与 docs/54 的关系：docs/54 移植的是 **examples 扩展**（`minimal-mode`/`built-in-tool-renderer`，用户主动加载才生效）；本包补的是 **CLI 默认渲染器**（不加载任何扩展即生效）。两者注册面相同，扩展仍优先（§2.3）。
4. 现状判定：Java 8 个工具全部落通用 `ToolCallCard`（`read  running` + 参数行），call 行没有行区间 / 命令 / 折叠计数，R11 因此停在"缺失"。

## 1. 为什么现在能补（Java 已有的接缝）

| 需要的能力 | Java 已有对位 |
|---|---|
| 行区间文案 | `com.pijava.coding.agent.core.ReadLineRange.format(Object,Object)`（L5 W8；数字字符串容错，`:38-56`） |
| 字节数文案 | `com.pijava.agent.tool.TruncationUtils.formatSize(long)`、`DEFAULT_MAX_BYTES`/`DEFAULT_MAX_LINES` |
| 工具层截断告示 | `ReadTool:119-146`、`ShellToolSupport:225-251`、`LsTool:135-152`、`GrepTool:186-210`、`FindTool:189-206`——文案逐字，已写进结果文本 |
| 渲染器责任链 | `ToolRendererRegistry`（追加序、首个非 null 胜，`:30-41`）、`ToolRendererResolver`、`ToolCallRenderer`（纯文本行，D92） |
| 展开态 | `MessageBubbleVisibility.toolsExpanded()`（Ctrl+O，K8/D197） |
| 耗时行 | `ToolDurations.timerText` + `ChatMessage.ToolResult.durationMillis`（本包前置，已实施） |

结论：**渲染器不需要 `details`**（D241 的缺口对本包不构成阻断），因为 Java 把 pi 工具层的截断/限额告示已经写进正文；`details` 只在 pi 的 *渲染器侧* 额外生成 `[Truncated: …]` 行（§4 D262）。

## 2. 接缝设计

### 2.1 落点

新增 `pi-java-coding-agent` 的 `com.pijava.coding.agent.core.render.BuiltInRenderers`：

```java
public final class BuiltInRenderers {
    public static ToolCallRenderer resolve(String toolName) { /* 8 名 → 渲染器；其余 null */ }
}
```

放在 coding-agent：渲染器只依赖 core 的 `ReadLineRange`/`TruncationUtils`，不依赖 tui（分层纪律与 `ToolCallRenderer` 一致）。

### 2.2 注入点：链尾，不是链首

`ToolRenderers.resolve`（tui，`ToolRenderers.java:29-32`）改为：

```java
return current == null ? null : current.resolve(toolName, () -> BuiltInRenderers.resolve(toolName));
```

- `ToolRendererRegistry.resolve(name, base)` 已有"链尾落 base"语义（`ToolRendererRegistry.java:30-41`），内置渲染器正好坐 `base`。
- **不能**在 registry 构造时 register：那是链首，会抢掉扩展——pi 的 `tool_override` 是替换工具定义（含 renderers），扩展必须优先。
- 无注册表（纯投影/测试）时 `ToolRenderers.resolve` 返回 null ⇒ 仍走默认卡，行为不变。

### 2.3 逐方法"无意见"（对齐 pi 的可选 renderer）

pi 的 `ToolRenderers` 两个方法都可选（`renderers/index.ts:51-63` 逐方法合并）。Java 接口现在是两个必选方法，本包改为**默认方法返回 `null` ≙ 无意见**：

```java
default List<String> renderCall(...) { return null; }
default List<String> renderResult(...) { return null; }
```

既有实现（examples 的 10 件）都显式实现了两个方法，零改动。`null` 与空表语义分开：`null`＝无意见（走默认），`List.of()`＝渲染器认为结果为空（pi 的空组件）。

### 2.4 结果面接入（本包的关键改动）

现状：`MessageBubble.lines` 的 `ChatMessage.ToolResult` 臂**不查渲染器**（`MessageBubble.java:44`），只有 assistant 块内嵌的 `ContentBlock.ToolResultContent` 臂查（`:155-156`）。因此：

1. `ChatMessage.ToolResult` 增加 `String toolName`（来源：`Message.ToolResultMessage.toolName()`，内嵌形取 `ContentBlock.ToolResultContent.toolName()`）。
2. `MessageBubble.toolResult` 先查渲染器：`renderResult(toolName, joinText(content), isError, expanded)`
   - `null` ⇒ 现有默认渲染（逐块：Text / `DiffView` / 图片占位）＋ 耗时行；
   - 非 null ⇒ `styledFlat(rendered, TOOL_INDENT, dim|red, dim|red)` ＋ 耗时行。
3. 耗时行保持在结果气泡末行（pi 的计时行在工具行尾，位置等价）。

这样 `edit` 的 `DiffContent` 仍在默认路径（§3.7），其余 6 族的折叠计数才真正生效。

### 2.5 已裁决的降级

- 颜色 / 语法高亮 / `Text`/`Container`/`Spacer`/`Box` / `VisualLinePreview`：沿用 D92（文本级方言），折叠按**逻辑行**（`\n`）计数，不按视觉行。
- `keyHint`：硬编码常量 `" (ctrl+o to expand)"`（D220：不解析绑定，pi 走 `keyHint`）。
- 流式增量（write 的 50 行高亮缓存、edit 的异步 preview）：不移植，渲染器是**纯函数**，逐帧全量重算。

## 3. 逐件设计

通用约定：所有渲染器返回**已转义**的纯文本行（`TextLayout.escapeMarkup`，D92/裁决⑦）；`expanded` 只影响折叠行数；空结果返回 `List.of()`；`null` 表示无意见。

| # | 工具 | call 行 | result 折叠 |
|---|---|---|---|
| 1 | `read` | `read <path><:range>` | 折叠且非错误 ⇒ 空；否则 ≤10 行 |
| 2 | `bash` | `$ <command>[ (timeout Ns)]` | 尾部 5 行 + `N earlier lines` |
| 3 | `powershell` | `PS> <command>[ (timeout Ns)]` | 同 bash |
| 4 | `write` | `write <path>` + 内容前 10 行 | 成功 ⇒ 空；错误 ⇒ 文本 |
| 5 | `grep` | `grep /<pat>/ in <path>[ (<glob>)][ limit N]` | ≤15 行 |
| 6 | `find` | `find <pat> in <path>[ (limit N)]` | ≤20 行 |
| 7 | `ls` | `ls <path>[ (limit N)]` | ≤20 行 |
| 8 | `edit` | `edit <path>`（仅 call） | 无意见 ⇒ 默认 `DiffView` |

### 3.1 `read`（pi `renderers/read.ts:28-148`）

- call：`read ` + `shortenPath(path)` + `ReadLineRange.format(args.offset, args.limit)`；键取 `file_path` 优先、回退 `path`。
- 紧凑分类（`read docs <label>` / `[skill]`）**不移植**：判定依赖 pi 包根（`config.getReadmePath()`）与 `SKILL.md` 探测，属 pi 产品布局 ⇒ D260。
- result：`!expanded && !isError ⇒ 空`（pi `:118-120`）；否则先裁尾部空行，再取前 10 行，超出追加 `"\n... (R more lines, ctrl+o to expand)"`（pi `:127-132`）。
- 不做截断告示：工具层已给（`ReadTool:123-146`，三种分支逐字）。

### 3.2 `bash` / `powershell`（pi `renderers/bash.ts:18-93`）

- call：单行 `{prompt} {command}`，`prompt` 为 `$` / `PS>`；`command` 非字符串 ⇒ `[invalid arg]`；空串 ⇒ `...`；`timeout` 存在 ⇒ 追加 `" (timeout Ns)"`（N 用 JS `String(number)` 形态，`ShellToolSupport.jsNumber:220-223` 可复用）。
- result：按 `\n` 切行；`expanded` ⇒ 全量；否则**尾部 5 行** + `"\n... (H earlier lines, ctrl+o to expand)"`。
- **不做** pi 的 footer 剥离（`bash.ts:42-47`）：Java 的正文已含 `[Showing lines a-b of n. Full output: …]`（`ShellToolSupport:236-248`），剥离后再重建属同一文案的另一种生产点，剥离反而引入差异。此项与 D262 同源。
- 无 `isError` 分支（pi 同）；耗时行由 §2.4 的统一出口渲染，渲染器不重复。

### 3.3 `write`（pi `renderers/write.ts:29-145`）

- call：`write <path>`；`content` 非字符串 ⇒ 追加 `"\n\n[invalid content arg - expected string]"`；空串 ⇒ 只有 header；否则 `"\n\n"` + 前 10 行，超出 `"\n... (R more lines, T total, ctrl+o to expand)"`（pi `:117-122`）。
- result：`!isError ⇒ List.of()`；`isError` ⇒ `"\n" + text`（pi `:132-142`）。
- 不移植增量高亮缓存（`WRITE_PARTIAL_FULL_HIGHLIGHT_LINES=50`，`write.ts:29-88`）。

### 3.4 `grep`（pi `renderers/grep.ts:23-66`）

- call：`grep /` + pattern + `/ in ` + path（空 ⇒ `.`）+ ` (<glob>)` + ` limit N`（**无括号**，pi `:32-33`）。
- result：trim 后按行切；`maxLines = expanded ? 全量 : 15`；超出 `"\n... (R more lines, ctrl+o to expand)"`；空 ⇒ 空表。无 `isError` 分支。

### 3.5 `find`（pi `renderers/find.ts:20-61`）

- call：`find ` + pattern + ` in ` + path（空 ⇒ `.`）+ ` (limit N)`（**带括号**）。
- result：`maxLines = expanded ? 全量 : 20`，同一省略文案。无 `isError` 分支。

### 3.6 `ls`（pi `renderers/ls.ts:19-54`）

- call：`ls ` + path（空 ⇒ `.`）+ ` (limit N)`。
- result：`maxLines = expanded ? 全量 : 20`，同一省略文案。无 `isError` 分支。

### 3.7 `edit`（pi `renderers/edit.ts:32-247`）——只接 call 行

- call：单行 `edit <path>`（pi `:83-86`）。
- result：**返回 `null`（无意见）**，走默认路径。理由：
  1. Java 的 edit 结果带 `ContentBlock.DiffContent`（`EditTool:163-165`），默认气泡经 `DiffView` 上色（P6-26），比 pi 的文本 diff 预览更接近 pi 的彩色 `renderDiff`；若渲染器返回纯文本会**丢掉 diff**。
  2. pi 的 call 行 preview 靠 `computeEditsDiff` 读盘 + 异步 `invalidate`（`edit.ts:177-195`、`edit-diff.ts:514-543`），Java 渲染器是纯函数、无 fs 访问 ⇒ 不移植（D263）。
- 因此本件对齐面＝"标题形状"，diff 面沿用既有实现。

### 3.8 路径缩写

pi：`renderToolPath` → `shortenPath`（home 前缀 ⇒ `~`）、空串 ⇒ `...`、非字符串 ⇒ `[invalid arg]`。
Java 目前**无** path 缩写的公共函数（`DefaultShellExecutor:194-197` 只在内部用 `user.home` 替换）⇒ 本包新增纯函数 `com.pijava.coding.agent.core.render.DisplayPath.shorten(String)`（`user.home` 前缀 ⇒ `~`，反斜杠折 `/`），供 5 个渲染器共用并单测。

## 4. 窄差登记（新增 D260–D263，编号接 docs/59 的 D259）

| 编号 | 内容 |
|---|---|
| **D260** | read 的紧凑分类行（`read docs <label>` / `[skill] <label>`，pi `renderers/read.ts:46-107`）不移植：判定依赖 pi 包根（`getReadmePath()`）与 `SKILL.md` 探测，Java 无 pi 的产品布局。call 行恒为 `read <path><:range>`。 |
| **D261** | 折叠按**逻辑行**计数：pi bash 用 `VisualLinePreview{maxVisualLines:5, keep:"end"}`（宽度感知视觉行，`bash.ts:58-68`），Java 无宽度感知（逻辑行在渲染期才折行），故"尾部 5 行"取逻辑行。D92 同族。 |
| **D262** | 渲染器侧的 `[Truncated: …]` / `[Full output: …]` 警告行（由 `details.truncation`/`fullOutputPath` 生成）不移植，也不新增 details 通道（D241 扩展）：这些文案已由工具层写进正文（`ReadTool:123-146`、`ShellToolSupport:236-248`、`GrepTool:194-207`、`FindTool:195-205`、`LsTool:141-149`），双重产出只会重复。 |
| **D263** | edit 的 call 行读盘 preview（`computeEditsDiff` + `argsComplete` 异步 `invalidate`，pi `edit.ts:177-195`）不移植：Java 渲染器为纯函数、无 fs 访问；diff 仍由结果 `DiffContent` 经 `DiffView` 上色显示。 |

沿用：D92（文本级方言：无 theme/keyHint/富组件）、D220（键位提示由宿主展示 ⇒ 硬编码 `ctrl+o`）、D241（`ToolCallRenderer` 无 `details`/`isPartial`）。

## 5. 测试与验证门

1. **纯函数单测**（coding-agent，`BuiltInRenderersTest`）：8 个工具名的 call 行逐字；`read` 的行区间（含字符串数字、非法值 ⇒ 无区间）；`bash`/`powershell` 前缀与 timeout 后缀；`grep` 的 ` limit N` 与 ` (limit N)` 差异；三档折叠常量（5/10/15/20）；省略文案逐字；`edit` 的 `renderResult == null`；未知名 ⇒ `null`；`DisplayPath.shorten`（home 前缀/反斜杠/空值）。
2. **TUI 投影测**（`MessageBubbleTest` 等）：结果气泡走渲染器（折叠行 + 省略行）与耗时行共存；`renderResult == null` 时保留 `DiffContent` 的 `DiffView` 行；无注册表时回落 `ToolCallCard`（既有断言改造：`toolCardInAssistantBlocksGetsNoBulletPrefix` 里的 `bash` 现由内置渲染器接管，断言改 `$ echo hi`）。
3. **扩展优先回归**：注册一个覆盖 `bash` 的 resolver，断言其行胜出（链尾语义）。
4. **验证门**：`mvn -pl pi-java-coding-agent,pi-java-tui -am test` 全绿；收尾跑全仓 `mvn clean verify -Dfrontend.skip=true`（SpotBugs 0 告警、Checkstyle 新文件 0 告警）。

## 6. 顺序与风险

顺序：接缝（§2.1–2.4：默认方法 + `ChatMessage.ToolResult.toolName` + 消息气泡查表）→ `DisplayPath` + read → shell 两件 → write → grep/find/ls → edit call 行 → TUI 断言改造。

风险：
- §2.4 动的是渲染主路径（`MessageBubble`），影响面比前几包大：先落"查询 + `null` 回落"的骨架并保持默认行为，再逐件注册，每件都能独立回退。
- `ChatMessage.ToolResult` 加 `toolName` 会改 sealed 记录的组件数（主代码 2 处模式匹配 + 5 处测试构造），与上一包加 `durationMillis` 同型，已演练过。
- 折叠行数改变现有快照式断言（`ChatViewportElementTest` 的 500 字符截断断言针对默认路径，不受影响）。

## 7. 回填

实施闭环后：`docs/04` 追加 D260–D263 行、`docs/05` 总表与 `docs/map/05` R11 重裁（预计"缺失 ⇒ 存疑/对齐"，待 W1 全包完成后按形状等价重判）、`docs/06` L7 行同步、`docs/59` §3 W1 行标注"内置渲染器见 docs/60"。

## 8. 实施记录（2026-11-19）

**代码**（按 §2 接缝，未偏离）：

| 文件 | 改动 |
|---|---|
| `coding-agent/.../core/render/BuiltInRenderers.java`（新） | 8 个工具名的渲染器：read／shell×2／write／grep／find／ls／edit（仅 call 行） |
| `coding-agent/.../core/render/DisplayPath.java`（新） | `shorten`（home ⇒ `~`、`\` 折 `/`、Windows 折大小写）＋ `renderToolPath` 的 `[invalid arg]`/占位语义 |
| `coding-agent/.../core/render/RenderText.java`（新） | markup 转义（与 `TextLayout.escapeMarkup` 同形，跨模块不共享）、切行、尾部空行裁剪、JS 数字形态 |
| `coding-agent/.../extension/ToolCallRenderer.java` | 两方法改 `default` 返回 `null`（≙ pi 的可选方法） |
| `tui/.../component/ChatMessage.java` | `ToolResult` 增 `toolName`（追加为第 4 分量，兼容 2/3 参构造）；投影带上 `durationMs`/`isError`/`toolName` |
| `tui/.../component/ToolRenderers.java` | 链尾落 `BuiltInRenderers.resolve`（无注册表仍 `null`） |
| `tui/.../component/MessageBubble.java` | 调/结果两面对 `null` 回落默认；结果气泡按 `toolName` 查渲染器；耗时行进统一出口 `addTiming` |
| `tui/.../util/ScrollbackTranscript.java` | 模式匹配参数个数同步 |

**测试**：`pi-java-coding-agent` **1979 测 0 失败**（新增 `BuiltInRenderersTest` 18 项、`DisplayPathTest` 3 项）；`pi-java-tui` **653 测 0 失败 1 skip**（新增 3 项：`builtInRenderersFillTheChainTail`／`extensionResolversWinOverBuiltIns`／`toolResultBubbleConsultsRenderers`，改造 `unregisteredToolFallsBackToToolCallCard` 为未知名工具）。

**偏离/注意**：
1. pi 的占位文案 `[invalid arg]` 在 Java 侧按 markup 规则转义成 `[[invalid arg]]`（屏上仍是 `[invalid arg]`）；测试断言取 markup 形态。
2. `args == null` 视为 pi 的 `undefined`（渲染器不 NPE），有专门用例。
3. 内置渲染器只在**有会话注册表**时生效（`ToolRenderers.install` 之后），纯投影测试与无会话路径行为不变。
4. 全量 `mvn clean verify` 属 L7 闭环门，本包只跑到 `pi-java-tui -am test` 全绿；`AgentSessionSendCustomMessageTest.promptDuringSettledEmissionDefersUntilAfterSettled` 在并行窗口超时 1 次、单跑 1.6s 通过（既有 flake，与本包无关）。
