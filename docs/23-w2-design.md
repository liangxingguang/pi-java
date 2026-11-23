# 23 - L7 W2 设计稿：工具/消息卡（diff 渲染、三消息卡、工具卡、bash 卡、点击展开）（目标锚点 `a00a5048d`）

> 【背景摘要】`docs/21` §7 把 W2 定为「渲染特性域主缺口」：**R10 diff 渲染(w3)＋缩进剥离（漏项#5）、R12 工具执行卡(w2)、R17/R18/R19 三卡(w2/w1/w1)＋transformer 接线（漏项#6）、R25(w1)、R26(w1)、K37+C14(w1×2)、bash 迁入 chat＋默认展开态（漏项#7）**，预期 +10~12。本稿是 spec＋实施计划：先设计、经审核、再实施。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（W1 已收口、未提交）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | R10/R17/R19/R26/K37/C14 从缺失/存疑翻「对齐」；R25 复核翻正（已有实现）；R12/R18 改判「对齐（窄差注）」/「对齐」；bash 卡为新对齐单元（漏项#7 落地） |
| 行为不变量 | 文本级架构不动（LogicalLine/markup 投影，无 Box/Container 移植）；`ChatMessage` 投影为纯函数＋渲染层可变展开态分离；渲染器责任链（`ToolRenderers`/`BuiltInRenderers`）优先级不变；扩展 `MarkdownTransformer` 接口面不新增成员 |
| 完成判据 | ①`mvn test -pl pi-java-tui -am` 全绿（JDK25）；②`map/05` W2 逐行改判＋`docs/05` §1.2／`docs/09`／`docs/04` §0 回填 |
| 不做项 | 流式部分结果通道（pi `tool_execution_update`→`updateResult(isPartial)`，D197 维持：Java 渲染是 entry 投影、无流式卡槽）；`MouseRegion` 组件移植（C14 以元素级等价判）；edit 读盘 preview（D263 维持）；图片块（R14 批） |

## 2. 上游证据（逐项，均对 `a00a5048d`）

### 2.1 R10 `modes/interactive/components/diff.ts`（166 行）＋ edit.ts 渲染器

- `parseDiffLine`：`^([+-\s])(\s*\d*)\s(.*)$` → `{prefix, lineNum, content}`；不匹配 ⇒ 整行 `toolDiffContext` 色（`@@`/`---` 头也落此臂）。
- `-` 连续块后收集连续 `+` 块；**恰好 1 删 1 加** ⇒ 词级 intra-line diff（`renderIntraLineDiff`）；否则先删后加原样。
- `renderIntraLineDiff`：**先剥公共缩进**（逐字符同位 whitespace 公共前缀，漏项#5），`Diff.diffWords` 词级 diff，变化段 `theme.inverse`，两侧行色 `toolDiffRemoved`/`toolDiffAdded`。
- tab → 3 空格（`replaceTabs`）。上下文行 ` N ...` 落 `toolDiffContext`。
- 调用点：edit 渲染器 `renderResult`（`details.diff`）与 `renderCall` 读盘 preview（D263 不移植）。diff 文本格式由 `edit-diff.ts formatDisplayDiff` 产出：`+NN content`/`-NN content`/` NN content`/` NN ...`（行号右对齐到 `lineNumWidth`）。
- Java 已有同格式生产者：`LineDiff.generateDiffString`（`pad(String, lineNumWidth)` 同形）→ `EditTool` 包 `DiffContent`。

### 2.2 R17/R18/R19 三卡＋漏项#6 `compaction/branch-summary/skill-invocation/custom-message.ts`

四组件同构（`Box`＋`customMessageBg`＋`MouseRegion` 点击切换 `expanded`）：

| 卡 | label | collapsed | expanded |
|---|---|---|---|
| CompactionSummary | `\x1b[1m[compaction]\x1b[22m`（`customMessageLabel` 色） | `Compacted from N tokens (`+`keyText("app.tools.expand")`+` to expand)` | Markdown(summary)，transform 前**先拼 `**Compacted from N tokens**\n\n` 头**（变换链只见 summary） |
| BranchSummary | `[branch]` | `Branch summary (… to expand)` | Markdown(summary)，头 `**Branch Summary**\n\n` |
| SkillInvocation | `[skill]`（collapsed 同行带 name） | `[skill] name (… to expand)` | `[skill]` label 行＋`**name**\n\n` 头＋Markdown(skillBlock.content)，型 `custom` |
| CustomMessage | `[customType]` | —（默认直接 Markdown，无折叠臂） | Markdown(text)，型 `custom`；扩展 renderer 在先（失败/无 ⇒ 回落） |

- 全部接 `createMarkdownTransform(type, () => false, transformers, messageId, timestamp)`（漏项#6）；type：compaction/branch ⇒ `"summary"`，skill/custom ⇒ `"custom"`。
- 构造时 `setExpanded(this.toolOutputExpanded)`（interactive-mode.ts 四处）——初值来自全局展开态。
- `parseSkillBlock`（`agent-session.ts:393-403`）：`^<skill name="([^"]+)" location="([^"]+)">\n([\s\S]*?)\n<\/skill>(?:\n\n([\s\S]+))?$`；`userMessage` trim 后单独渲染 User 气泡。

### 2.3 R12 `tool-execution.ts`

- 三态背景：`toolPendingBg`（partial）/`toolErrorBg`（result.isError）/`toolSuccessBg`（最终非错）。
- fallback 结果臂：`FALLBACK_PREVIEW_LINES=10`，超出 ⇒ `... (N more lines, ctrl+o to expand)`。
- `outputPad` 进 Box paddingX。流式部分结果经 `updateResult(…, isPartial)`（D197 维持不做）。

### 2.4 R25 `interactive-mode.ts` `maybeShowThinkingDropNotice`

- Java 已实现：`ReasoningDropNotice.java`（L5 W7，interactive-mode.ts:473-523 逐字：KINDS 双 provider、reasons 去重、`isRepeat` 抑制）＋`CacheNoticeRenderer.appendReasoningDropNotice:119-133`＋`SessionEventRouter:127`（MessageEnd 臂）＋`PiTuiApp:159` 上一条 assistant supplier。文案 `(details in session)` 尾部逐字。**复核成立 ⇒ 翻正，无代码**。

### 2.5 R26 `render-utils.ts` `formatToolCallWithArgs`

- collapsed：`title key=value key=value …` 截 `COLLAPSED_ARGS_CHARS=100`＋`...`；expanded：每 entry `  key: value` 行（字符串原样、复合值 JSON 两空格缩进、续行再缩 4）。
- 非 object args ⇒ `args=<JSON>`。null args ⇒ 仅标题。

### 2.6 K37/C14 `mouse-region.ts` ＋ 漏项#7 `bash-execution.ts`

- K37：三卡 `MouseRegion` 左键 click ⇒ `setExpanded(!expanded)`。
- bash 卡：`$ cmd`（`bashMode` 色 bold；`!!` ⇒ `dim`）、输出尾部 `PREVIEW_LINES=20`、loader（运行中）→ 状态行（`(cancelled)` warning／`(exit N)` error／`Output truncated. Full output: path` warning）；流式期挂 pending 区、`agent_settled` 迁 chat（interactive-mode.ts:3766-3770）。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| R10 | `DiffView` 按行首字符三色（+/-/@头），无行号解析、无词级 diff、无缩进剥离；色是硬编码 hex 非 theme token | `DiffView.java:36-63` |
| R17/R18 | `Entry.Compaction/BranchSummary` → `System` 一行（`Compacted context: …`/`Branch: …`，icon 🗼/⎇），summary 过 SUMMARY 变换但**无 id/ts** | `ChatMessage.java:132-137` |
| R19 | user 消息无 skill 解析 ⇒ 无卡 | `ChatMessage.java:214` |
| 漏项#6 | CustomMessage fallback 过 CUSTOM 变换、无 id/ts；compaction/branch 同 | `ChatMessage.java:163-165` |
| R12 | fallback 臂（`ToolCallCard`）无 bg、结果不折叠（500 字符截断）；渲染器链折叠已落（docs/60） | `MessageBubble.java:196-209/240-272`、`ToolCallCard.java` |
| R25 | 已实现已接线（见 §2.4） | `ReasoningDropNotice.java` |
| R26 | fallback args 渲染：`String.valueOf(arguments)`＋200 截断，无 key=value 形 | `MessageBubble.java:227-232` |
| K37/C14 | `ChatViewportElement.handleMouseEvent` 只有 scrollbar MOVE 臂；点击不达消息 | `ChatViewportElement.java:209-223` |
| bash | `Entry.BashExecution` → `User(bashExecutionText)` 纯文本（无卡）；`BashExecutionUpdate` 事件无消费面 | `ChatMessage.java:171-172`、`ContextEntries.java:340` |
| 展开态 | 全局 `MessageBubbleVisibility.toolsExpanded()`（Ctrl+O）已在渲染器链与 D197 消费 | `MessageBubble.java:187/200/245` |
| token | `TOOL_DIFF_ADDED/REMOVED/CONTEXT`、`TOOL_PENDING/SUCCESS/ERROR_BG`、`CUSTOM_MESSAGE_BG/LABEL/TEXT` 全在 | `ThemeToken.java:48-88` |
| bg/inverse | TamboUI `Style.bg()` 可用（`TuiThemeBridge.bg:55`）；无 inverse API | `TuiThemeBridge.java` |

## 4. 设计（Java 方案）

### 4.1 R10：重写 `DiffView`

对位 pi `diff.ts` 逐语义（文本级方言：markup 行代替 ANSI 串）：

```java
// 解析：^([+\- ])(\s*\d*)\s(.*)$ → record DiffLine(char prefix, String lineNum, String content)；null ⇒ 未匹配
// 主循环：'-' 连续块 → '+' 连续块；1:1 ⇒ 词级；否则先删后加；未匹配 ⇒ toolDiffContext(dim)
// 词级：先剥公共缩进（漏项#5）→ WordDiff.diffWords → 变化段反白近似：
//   Style.EMPTY.bg(行 diff 色).fg(Color 黑)（窄差 D：pi 是终端 inverse，TamboUI 无该 API）
// 行色：TuiThemeBridge.fg(TOOL_DIFF_ADDED/REMOVED/CONTEXT)（替硬编码 hex）
// tab → 3 空格；行号保留原对齐（`-NN ` 前缀原样拼）
```

- 新增私有 `WordDiff`：tokenize 近 npm `diffWords`（word run／whitespace run 交替），LCS 对齐，产 `{removedLine, addedLine}` 两行 markup（已转义）。
- `LogicalLine` 转换维持（preformatted、不换行）。API `lines(String)` 签名不变，调用点零改。

### 4.2 R17/R18/R19：三卡

`ChatMessage` 增三 record（投影层不可变，展开态在渲染层）：

```java
record CompactionCard(int tokensBefore, String summary, String messageId, String timestamp) implements ChatMessage {}
record BranchCard(String summary, String messageId, String timestamp) implements ChatMessage {}
record SkillCard(String name, String content, String userMessage, String messageId, String timestamp) implements ChatMessage {}
```

- `ChatMessage.project`：
  - `Entry.Compaction` ⇒ `CompactionCard(tokensBefore, summary, message.id(), timestamp)`（弃 `System` 一行＋icon）；
  - `Entry.BranchSummary` ⇒ `BranchCard(...)`；
  - user 消息臂先 `SkillBlocks.parse(text)`（新 util，regex 逐字移植 §2.2）⇒ 命中 ⇒ `SkillCard(name, content, userMessage, id, ts)`＋（userMessage 非空时）追加 `User(userMessage)` 气泡；未命中 ⇒ 原 `User` 臂；
  - CustomMessage fallback 的变换改 `applyForEntry(..., CUSTOM, id, ts)`（漏项#6）。
- `MessageBubble` 渲染（三卡同构私有方法）：
  - label 行：`[cyan bold][compaction][/][/]` 形（markup：CUSTOM_MESSAGE_LABEL 色＋bold）；collapsed 单行尾 ` [dim](ctrl+o to expand)[/]`（对位 `keyText("app.tools.expand")`，collapsed 文案逐字：`Compacted from N tokens (…)`/`Branch summary (…)`/`[skill] name (…)`）；
  - expanded：label 行＋空行＋Markdown 渲染（`MarkdownRenderer.toLogicalLines`），变换前拼头 `**Compacted from N tokens**\n\n`／`**Branch Summary**\n\n`／`**name**\n\n`（pi 语义：变换链只见正文），型 SUMMARY（compaction/branch）／CUSTOM（skill），`applyForEntry` 带 id/ts；
  - 背景窄差 D：pi `customMessageBg` 全卡底色 ⇒ 文本级不铺底（Java chat 无 bg 盒模型），label 色承担区分（登记窄差）。

### 4.3 K37＋C14：点击展开

- `ChatViewportElement`：
  - `flatten` 时记录每条消息的 `[startRow,endRow)`（`RenderRow` 增消息索引？——不：`flatten` 返回处新建 `List<int[]> messageRowRanges` 私有字段，与 `lastRowCount` 同帧更新）；
  - `handleMouseEvent` 增 CLICK 臂：非滚动条区 ⇒ 行号 → 区间命中卡类消息 ⇒ 回调（新增 `@FunctionalInterface CardToggler { void toggle(int messageIndex); }` setter，PiTuiApp 接线到 ChatScreen）；
  - C14 判定：MouseRegion 组件不移植，元素级行区间分发承担同可观察行为（窄差 N：区域=整卡行块）。
- 展开态存储：ChatScreen 新增 `Map<Integer, Boolean> cardExpanded`（key=chatPanel 消息索引；会话重渲染/清屏时随 ChatPanel 清空）；渲染取值 `cardExpanded.getOrDefault(idx, toolsExpanded())`。渲染签名问题：`MessageBubble.lines(ChatMessage)` 是静态纯函数 ⇒ 新增 `lines(ChatMessage, int index, IntPredicate cardExpanded)` 重载（ChatPanel 侧传递），默认重载维持旧测试面。

### 4.4 漏项#7：bash 卡

- `ChatMessage` 增 `record BashCard(String command, String output, Integer exitCode, boolean cancelled, boolean truncated, String fullOutputPath, boolean excludeFromContext) implements ChatMessage {}`；
- `Entry.BashExecution` 臂改产 BashCard（弃 User 纯文本；`ContextEntries.bashExecutionText` 保留给 compaction/branch 上下文用）；
- `MessageBubble` 渲染（对位 bash-execution.ts updateDisplay，文本级）：
  - 头行 `$ cmd`（bold，`excludeFromContext` ⇒ dim 否则 accent 色——pi bashMode 色）；
  - 输出：尾部 20 行预览（折叠）／全部（展开），muted；空 ⇒ `(no output)`；
  - 状态行：`(cancelled)` warning／`(exit N)` error／`Output truncated. Full output: path` warning，逐字；
  - 展开态同 §4.3（可点击）。
- 运行中流式臂（pending 区＋loader）＝窄差 D（BashExecutionUpdate 无消费面，落盘后卡已对位 pi agent_settled 后形态）。

### 4.5 R12：工具卡 bg＋fallback 折叠

- `ToolCallCard`/`MessageBubble.toolResult` fallback 臂：行 style 叠 bg（`TuiThemeBridge.bg(TOOL_PENDING_BG/TOOL_ERROR_BG/TOOL_SUCCESS_BG)`）——ToolCall running ⇒ pending；ToolResult isError ⇒ error，否则 success。bg 只铺文本格（非整宽盒）＝窄差 D 登记。
- fallback 结果折叠：非 expanded 且非错误 ⇒ 前 10 行＋`... (N more lines, ctrl+o to expand)`（对位 `FALLBACK_PREVIEW_LINES=10`）；文本超 500 字符截断保留。
- isPartial 流式通道维持 D197（§1 不做项）。

### 4.6 R26：args 渲染

- `ToolCallCard` 增 `FormatToolArgs.withArgs(String title, Map<String,Object> args, boolean expanded)`（或 MessageBubble 私有——落 `ToolCallCard` 静态方法，渲染器 fallback 与 MCP 面共享）：
  - collapsed：`title k=v k=v …` 100 字符截＋`...`；expanded：`  k: v` 行（复合值 JSON 两空格缩进）；
  - 接入：`MessageBubble.toolUseLines`/`toolResultLines` 未命中臂改走（替 200 截断）。
- `McpToolCallRenderer` 已有（map 行备注），不动。

### 4.7 R25

无代码；map/05 行改判「对齐」＋证据补 `ReasoningDropNotice.java`／`CacheNoticeRenderer.java:119-133`／`SessionEventRouter.java:127`。

## 5. 测试计划

| 域 | 新增/改动 |
|---|---|
| DiffView | 新 `DiffViewTest`：行号保留、1:1 词级（含缩进剥离：两行同前导缩进不进词 diff）、多行块先删后加、tab 替换、`@@` 头落 dim、空输入 |
| 三卡 | 新 `ChatMessageCardsTest`：投影（Compaction/BranchSummary → 卡＋id/ts；user `<skill>` → SkillCard＋userMessage 分离）、collapsed/expanded 文案逐字、transform 接线（SUMMARY/CUSTOM＋id/ts 到达链——fake transformer 断言 context） |
| K37/C14 | `ChatViewportElementTest` 扩：CLICK 命中卡区间 → toggler 回调、非卡行不回调、滚动条区不回调；ChatScreen per-card 态覆盖全局 |
| bash 卡 | `MessageBubbleRendererTest` 扩：头行色（`!!` dim）、尾部 20 行、三态状态行逐字 |
| R12/R26 | `ToolCallCard` args 形（collapsed 100 截断/expanded 多行）、fallback 折叠 10 行＋省略行、bg style 到达 LogicalLine |
| 回归 | 既有 `MessageBubbleTest`/`ChatMessageTest` 中 Compaction/Branch 一行断言改卡形；`ChatScreenSessionEventTest` 不动（事件面未变） |

定向：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am`（agent-core 投影改动被 -am 覆盖）。

## 6. 实施步骤

1. DiffView 重写＋WordDiff＋测试。
2. ChatMessage 三 record＋SkillBlocks＋project 改造（含漏项#6 id/ts）＋MessageBubble 卡渲染＋测试。
3. ChatViewportElement 点击分发＋ChatScreen cardExpanded＋PiTuiApp 接线＋测试。
4. BashCard＋渲染＋测试。
5. ToolCallCard/MessageBubble bg＋折叠＋FormatToolArgs＋测试。
6. 全量定向测试 → map/05 改判（R10/R17/R18/R19/R25/R26/K37/C14/R12＋bash 新单元）→ docs/05 §1.2、docs/09、docs/04 §0 回填。

## 7. 预期读数

R10 3 ＋ R17 2 ＋ R18 1 ＋ R19 1 ＋ R25 1 ＋ R26 1 ＋ K37 1 ＋ C14 1 ＋ R12 1（卡形面；流式维持 D197 不计）＋ bash 卡 1 ≈ **+12** ⇒ 186.5 → ~198.5/244 ≈ **81.3%**（正式数字按落地逐行回算）。

## 8. 收口结论（2026-11-23）

- 全部六步落地；`mvnw test -pl pi-java-tui -am` 全绿（含上游 coding-agent，含新增 `DiffViewTest`／`ChatMessageCardsTest`／`FormatToolArgsTest`／`ChatViewportElementTest` K37 三测与既有回归改判）。
- **读数**：`map/05` **196.0/244＝80.3%**（未加权 99/147；＋9.5）；投影回算 **199.5/259＝77.0%**。R10/R17/R18/R19/R25/R26/K37 对齐、C14 对齐（窄差 N）、R12/R13 存疑（卡形面 1/2）。
- **实施与设计的偏差**（均已回写 map/05 行注）：①词级反白用 TamboUI 原生 `[reversed]` 标签（javap 取证 MarkupParser 支持）——优于设计稿的 bg 近似，无窄差；②K37 点击走 `DragHandler.onDragEnd`（toolkit `routeMouseEvent` 对 draggable 元素把 PRESS 吞进 `startDrag`，`handleMouseEvent` 收不到）——行为等价、拖拽 release 亦触发，窄差 N；③R26 collapsed 单行替代旧「名称＋参数」两行（pi `formatToolCallWithArgs` 即单行），相关旧测试改判；④fallback 结果行不再 500 字符截断（pi Text 行终端裁剪等价）。
- 下一分段：**W3 渲染缓存契约**（R31 块缓存、R32 注释隐藏、R33＋invalidate 前缀；C21/R30 按 docs/21 §6 出窄差）。
