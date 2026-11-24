# 27 - L7 W6 设计稿：全屏长尾（搜索/选择/flash、设置/输入/截断/边框/假光标/键位提示）

> 【背景摘要】`docs/21` §7 把 W6 定为「全屏长尾」：**S2a 转录搜索(1)＋K21、S2b 鼠标选择/copy-on-select(1)＋K22、S2c flash 提示(1)、C3 settings-list(2)、C4 input(1)、C13 truncated-text(2)、C15 dynamic-border(1)、C20 假光标(1)、K18 键位提示(2)**。本稿是 spec＋实施计划；任务拆分见 `docs/27-w6-tasks.md`。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（HEAD `7fa01504`，W1–W5 已提交）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | S2a/S2b/S2c、C4、C15、C20 从「缺失」翻「对齐」；C13、K18 从「存疑」翻「对齐」；C3 从「缺失」翻「对齐」；K21/K22 随搜索/选择落地翻「对齐」 |
| 行为不变量 | 不改 TamboUI 组件协议；`ChatScreen` 保持 ≤500 行（Checkstyle 硬限）；全屏滚动/卡点击/流式草稿/补全弹层行为不回归；剪贴板走 W5 `NativeClipboard` 链；主题令牌色现读 |
| 完成判据 | ①`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿；②`map/05` 11 行改判＋§加权汇总复算；③`docs/05` §1/§1.1/§1.2、`docs/09` L7 段、`docs/04` §0 同步；④改动留工作区不 commit |
| 不做项 | #20（L6 context footer）——`SessionSnapshot` 无上下文窗口字段，改 record 契约不属 W6（见 §9）；图片协议（R14/W7）；bash-mode 编辑器边框（Java 无 bash 输入模式态，见 §5 D2）；鼠标双击选词/拖拽边缘自动滚动/OSC 8 链接（见 §5 N3） |

## 2. 上游证据（均对 `a00a5048d`）

### 2.1 S2a/K21 转录搜索

- `packages/tui/src/alt-screen-search.ts`（327 行）：
  - `buildSearchCorpus(lines)`：`stripTerminalSequences` 后按行建「词块＋空格分隔」语料；ASCII 快路径整段索引（`linearColumns=true`），非 ASCII 按字素分段（列宽 `visibleWidth`）。`spans` 记 `textStart/textEnd/row/startCol/endCol`。
  - `normalizeQuery`＝`replace(/\s+/gu," ").trim()`；`findSearchCorpusMatches` 用转义后的 `giu` 正则，跨 span 合并同段（`previous.row===span.row && startCol<=previous.endCol`）。
  - `AltScreenSearchIndex.search(lines, query)`：行内容未变且 query 未变 ⇒ 复用缓存，返回 `changed=false`。
  - `getAltScreenSearchMatchKey`＝`first.row:first.startCol:last.row:last.endCol`。
  - `AltScreenSearchComponent`：`Input`（prompt `" "`，placeholder `Find in transcript`）；结果文本 `No matches` / `${index+1}/${count}`；底行 `↑ prev · ↓ next`；三行框线；`setResult(index,count)`。
- `packages/tui/src/tui-alt-screen.ts`：
  - `toggleSearch`（`ctrl+shift+f`，keybindings.ts `tui.altScreen.search`）；`searchNext`＝`enter`/`ctrl+g`；`searchPrevious`＝`shift+enter`/`ctrl+shift+g`；`searchClose`＝`escape`。
  - `refreshSearch`：query 变化后选择「锚点行之后的首个匹配」；`next/previous` 环回；`retain` 不改变选中；滚动使当前匹配可见。
  - 高亮：非当前匹配 `\x1b[4m…\x1b[24m`（下划线），当前匹配 `\x1b[1;7m…\x1b[22;27m`（加粗反白）。

### 2.2 S2b/K22 鼠标选择 / copy-on-select

- `tui-alt-screen.ts:305 hasActiveSelection`、`:317 resetTextSelection`（转录替换时清选区）、`:1464 copySelectionToClipboard`。
- 选择点 `SelectionPoint{row,col,boundary}`；拖拽 `selectionAnchor/selectionFocus`；双击按词（iTerm2 词字符集）/三击按行；释放时 `copyOnSelect`（默认 true）⇒ 注入的 `copySelection`（成功 `Copied!`，失败具体错误文案＋`COPY_ERROR_FLASH_DURATION_MS=5000`）。
- `tui.input.copy`＝`ctrl+c`（有选区时复制而不清输入）。
- `tui-alt-screen.ts` 边缘自动滚动定时器、OSC 8 点击打开、右键粘贴：同文件。

### 2.3 S2c flash

- `packages/tui/src/components/alt-screen-flash.ts`（51 行）：`flash(message, durationMs=1000)` 入栈、`setTimeout` 到期移除并 `requestRender`；`render(width)`＝`\x1b[7m` + `truncateToWidth(" "+message+" ", width)` + `\x1b[27m`；`dispose()` 清定时器。
- `tui-alt-screen.ts:665 flash()` 委托容器；复制成功/失败均走 flash。

### 2.4 C3 settings-list

- `packages/tui/src/components/settings-list.ts`：
  - 搜索行（`Input`）＋空行；`No settings available` / `No matching settings`＋hint。
  - 可见窗口居中：`start = clamp(selected - maxVisible/2, 0, len-maxVisible)`；滚动指标 `  (i/n)`。
  - 标签对齐：`maxLabelWidth = min(36, max(visibleWidth(label)))`；分隔两空格；值按 `width - usedWidth - 2` 截断。
  - 选中项描述：空行＋`wrapTextWithAnsi(desc, width-4)`，每行前缀两空格。
  - hint：`  Type to search · Enter/Space to change · Esc to cancel`（无搜索时去掉 `Type to search · `）。
  - 鼠标：滚轮移动选中；左键 press 选中、click 激活；hover 不改选中。
  - 子菜单 `done(selectedValue?, {navigateTo?})`：关闭后跳到目标项并自动打开其子菜单。

### 2.5 C4 input

- `packages/tui/src/components/input.ts`（501 行）：单行值＋光标；prompt/placeholder/placeholderStyle；括号粘贴缓冲（去 `\r\n`、`\t`→4 空格）；kill ring（Ctrl+K/U/W/Alt+D、Ctrl+Y/Alt+Y）；undo（词字符合并）；光标移动（字符/词/行首尾，Ctrl+A/E/B/F）；`handleMouse` 按可见列定位；横向滚动（半宽规则，`renderedStartColumn`）；假光标（`renderFakeCursor`）。

### 2.6 C13 truncated-text

- `packages/tui/src/components/truncated-text.ts`（65 行）：取首个 `\n` 前文本；`truncateToWidth(text, width - 2*paddingX)`；左右 padding；补空格到**恰好 width**；上下各 `paddingY` 行空白（也补满 width）。本锚点无 `visual-truncate.ts`（已删除），C13 只按 truncated-text 计。

### 2.7 C15 dynamic-border

- `packages/coding-agent/src/modes/interactive/components/dynamic-border.ts`：`DynamicBorder(color = theme.fg("border"))`，`render(width)`＝`color("─".repeat(max(1,width)))`。
- `interactive-mode.ts updateEditorBorderColor`：bash 模式 ⇒ `theme.getBashModeBorderColor()`，否则 `theme.getThinkingBorderColor(session.thinkingLevel ?? "off")`；`editor.ts` 顶部/底部各一条横线用 `borderColor` 上色（滚动时带 `↑/↓ n more` 标签）。
- Java 主题已具备 `Theme.thinkingBorderColor(level)` 与 `ThemeToken.THINKING_* / BASH_MODE`（`Theme.java:232-248`）。

### 2.8 C20 假光标

- `tui.ts`：`CURSOR_MARKER="\x1b_pi:c\x07"`、`FAKE_CURSOR_START/END`；`renderFakeCursor(text)` 包裹；`resolveFakeCursors(lines)`：硬件光标开 ⇒ 去掉紧跟 `CURSOR_MARKER` 的假光标；假光标标记 → `\x1b[7m` / `\x1b[27m`（反白）；无聚焦标记的假光标保留。
- 默认 `showHardwareCursor=false`（假光标）；开关切换后重新渲染。

### 2.9 K18 键位提示

- `packages/coding-agent/src/modes/interactive/components/keybinding-hints.ts`：`formatKeyText`（`/` 分段、`+` 连接、darwin `alt`→`option`、可选首字母大写）；`keyText`/`keyDisplayText`；`keyHint(kb, desc)`＝`theme.fg("dim", keyText) + theme.fg("muted", " "+desc)`；`rawKeyHint(key, desc)` 同形。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| S2a/K21 | 全树无搜索；无 `ctrl+shift+f` 动作 | `grep` 无命中；`KeybindingsManager` 无 `tui.altScreen.*` |
| S2b/K22 | 无选区；拖拽只做滚动条/卡点击；`ctrl+c` 只清输入 | `ChatViewportElement.java:144-182`；`GlobalKeyRouter.java:257` |
| S2c | 无 flash；错误走 `appendSystemText` | `ChatScreenLayout.java` 无 flash 槽 |
| C3 | 有 model/view（搜索/窗口/子菜单），但视图是裸列：无标签对齐、无值截断、无描述折行、无鼠标 | `SettingsListView.java:14-38`；`SettingsListModel.java` |
| C4 | 所有输入走多行 `TextAreaState`；无单行 Input | `ChatInputPortal.java:23`；`EditorComponent.java` |
| C13 | `TextLayout` 有量宽/wrap/硬截断，无 truncated-text 组件；无 `truncateToWidth` 公共口 | `TextLayout.java:398-428` |
| C15 | 编辑器无上下横线；`.EditorComponent` 只有 CSS border-color；thinking 主题色已备 | `EditorComponent.java:55-77`；`ThemeApplicator.java:74` |
| C20 | 固定青色方块光标常显，与 `showHardwareCursor` 无关 | `EditorElement.java:33-34,90-117` |
| K18 | 只有 `keyText`/`displayText`，无 `keyHint`/`rawKeyHint` | `KeybindingHints.java:29-67` |

## 4. 设计（Java 方案）

### 4.1 公共底座

**`pi-java-tui/.../component/SingleLineInput.java`（C4，≤260 行）**

```java
public final class SingleLineInput {
    public SingleLineInput(String prompt, String placeholder);
    public String value();
    public int cursor();
    public void setValue(String value);
    public void handleKey(KeyEvent event, KeybindingsManager keys); // 无输入法时也接受 CHAR
    public void handleText(String data);                             // 括号粘贴/批量字符
    public void setCursorByColumn(int col);                          // 鼠标点击列
    public int renderedStartColumn();
    public void clear();
}
```

- 语义逐条对位 §2.5：单行（换行/`\r` 丢弃）、字素/字符边界用 `String` 码点、宽度用 `TextLayout.displayWidth`、横向滚动半宽规则、kill ring 复用 `component/KillRing`（不可见则内联小环）、undo 栈（连续词字符合并）、Ctrl+A/E/B/F/K/U/W/Y、Alt+D/Y/B/F、Delete/Backspace/Home/End/Left/Right。
- 粘贴：`\r\n`/`\r`/`\n` 删除，`\t`→4 空格。

**`pi-java-tui/.../component/SingleLineInputElement.java`（≤90 行）**

- `StyledElement`，渲染 `prompt + visibleText + padding`；光标处反白（`Style.reversed()`），横向滚动按 `renderedStartColumn`；`preferredSize` 宽=内容宽、高 1。鼠标点击列 → `setCursorByColumn`。

**`pi-java-tui/.../util/TextLayout.java`（追加，不动现有方法）**

```java
public static String truncateToWidth(String markup, int width);      // 超宽截断＋"…"；不超宽原样
public static String sliceByColumns(String markup, int startCol, int endCol); // 选区/高亮切列
```

- 用 `MarkupTokens` token 流实现（标签零宽、按码点列宽、转义解码），保证与 `wrap` 同口径；超宽时保留一个单元格给省略号。

**`pi-java-tui/.../util/LineHighlighter.java`（≤130 行）**

```java
public record Range(int startCol, int endCol, Style style) {}
public static Line apply(RenderRow row, List<Range> ranges);
```

- `MarkupParser.parse` 后按显示列切分 span，区间内 `patch` 高亮样式；宽字符区间落在字符中间时收敛到字符边界（与 pi 的 span 合并同义）。

### 4.2 S2a/K21 转录搜索

**新增 `pi-java-tui/.../component/TranscriptSearchIndex.java`（≤190 行）**

```java
public record Segment(int row, int startCol, int endCol) {}
public record Match(List<Segment> segments) {}
public final class TranscriptSearchIndex {
    public Result search(List<RenderRow> rows, String query);
    public record Result(List<Match> matches, boolean changed) {}
    public static String matchKey(Match match);
}
```

- `plainText(RenderRow)`：`MarkupParser.parse(row.text())` 后拼 span 内容（可见文本），再按 §2.1 规则建语料/span；ASCII 快路径按空格跑词块，非 ASCII 逐码点（列宽 `TextLayout.displayWidthMarkup`），`startCol/endCol` 为显示列。
- 缓存键＝行内容列表＋归一化 query；未变返回 `changed=false`。

**新增 `pi-java-tui/.../component/TranscriptSearchController.java`（≤170 行）**

```java
public final class TranscriptSearchController {
    public boolean active();
    public void toggle();
    public void close();
    public void queryChanged(String query);
    public void next();
    public void previous();
    public void handleKey(KeyEvent event, KeybindingsManager keys);
    public Element bar();
    public List<ChatViewportElement.Highlight> highlights();
    public int currentRow();
}
```

- 持有 `SingleLineInput`（prompt `" "`，placeholder `Find in transcript`）、`TranscriptSearchIndex`、`matches`、`selectedIndex`、`anchorRow`、`rowsSupplier`。
- `queryChanged`：重算匹配；选中「锚点行之后首个匹配」，无则第 0 个；`next/previous` 环回；空 query ⇒ 无匹配、选中 -1。
- `bar()` 返回 `TranscriptSearchBar`：三行框线（`┌─┐│ │└─┘`），第二行 `│ <input>        n/m │`，第三行 `└─ <↑ prev · ↓ next · Esc close> ─┘`；用 `KeybindingHints.rawKeyHint` 渲染 dim/muted。
- 高亮：非当前 `underline`，当前 `reversed+bold`。

**新增 `pi-java-tui/.../component/TranscriptSearchBar.java`（≤120 行）**

- `StyledElement`，宽度感知渲染上述三行（窄宽时按钮降级为 `↑`/`↓`，同 §2.1 的 `controlsWidth` 规则）。

**修改 `ChatViewportElement`（+≤50 行）**

- `record Highlight(int row, int startCol, int endCol, boolean current)`；`setHighlights(List<Highlight>)`。
- `renderContent`：`toLine(row)` 改为 `toLine(row, highlightsFor(i))` ⇒ `LineHighlighter.apply`。
- 暴露 `rowsSnapshot()`（本帧 `rows` 的只读副本）与 `scrollToRow(int)`（`ScrollState.scrollToRow`，居中并置 `userScrolledAway=true`）。

**修改 `ChatPanel`（+≤20 行）**：`rowsSnapshot()`、`setHighlights(...)`、`scrollToRow(int)`。

**修改 `ChatScreenLayout`（+≤25 行）**：持有 `TranscriptSearchController`；`searchBar()` 在 `render()`/`renderBottomArea()` 顶部（`headerRows` 之前）插入。
**修改 `ChatScreen`（净 0 行）**：新增 `transcriptSearch()` 等 2–3 个委托；同时压缩类注释与非必要 javadoc 等量，保持 ≤500。

**修改 `KeybindingsManager`**：新增四个 overlay 动作
`tui.altScreen.search`＝`ctrl+shift+f`；`searchNext`＝`enter`/`ctrl+g`；`searchPrevious`＝`shift+enter`/`ctrl+shift+g`；`searchClose`＝`escape`；加入 `actionIds()`。

**修改 `GlobalKeyRouter`**：`onKeyEvent` 在 `keys.resolve` 之前：
1. 搜索激活 ⇒ `search.handleKey(event, keys)` 先吃（toggle/close/next/prev/文本/退格），返回 HANDLED；
2. 未激活且命中 `tui.altScreen.search` ⇒ `chatScreen.transcriptSearch().toggle()`。
（`ctrl+g` 的全局外部编辑器绑定只在搜索激活时让位，退出搜索即恢复。）

### 4.3 S2b/K22 鼠标选择 + copy-on-select

**新增 `pi-java-tui/.../component/TranscriptSelection.java`（≤170 行）**

```java
public final class TranscriptSelection {
    public record Cell(int row, int col) {}
    public void press(Cell cell);
    public void drag(Cell cell);
    public boolean finish();                 // true＝有非空选区
    public boolean active();
    public String text(List<RenderRow> rows); // 有序行 → 逐行 sliceByColumns，行间 "\n"
    public List<ChatViewportElement.Highlight> highlights();
    public void clear();
}
```

- 选择以「内容区行号（含滚动偏移）」记；列 clamp 到 `[0, contentWidth-1]`；反向拖拽自动排序；行尾去尾随空格。

**修改 `ChatViewportElement`（并入 §4.2 的 ≤50 行预算）**

- 拖拽通道：`onDragStart` 首次落在内容区 ⇒ `selection.press(cell)`；已按下 ⇒ `selection.drag(cell)`（Windows X10 重复 PRESS 臂）；`onDrag` ⇒ `drag(cell)`；`onDragEnd` ⇒ 非滚动条且 `finish()` 为真 ⇒ `selectionSink.accept(text)`；无位移的按下仍走既有卡点击。
- `setSelectionSink(Consumer<String>)`；`hasActiveSelection()`；`copyActiveSelection()`；`clearSelection()`；渲染时把选区高亮与搜索高亮合并（选区 `reversed`，搜索下划线/当前反白；当前匹配优先）。
- `clear()`/整表重建 ⇒ `clearSelection()`。

**修改 `ChatPanel`（+≤10 行）**：选区委托与 `setSelectionSink`。
**修改 `ChatScreenLayout`（并入 §4.2 预算）**：构造时注入 `chatPanel.setSelectionSink(this::copySelection)`；`copySelection(text)` ⇒ `ClipboardSupport.copy` 成功 `flash("Copied!")`，`IOException` ⇒ `flash("Copy failed: "+msg, 5000)`。
**修改 `ChatScreen`（净 0 行）**：`copyActiveSelection()` 委托。
**修改 `GlobalKeyRouter` `CLEAR` 分支**：`if (chatScreen.copyActiveSelection()) break;`（有选区复制不清输入；无选区保持清输入）。

### 4.4 S2c flash

**新增 `pi-java-tui/.../screen/FlashNotice.java`（≤70 行）**

```java
public final class FlashNotice {
    public void show(String message);            // 1000 ms
    public void show(String message, long ms);
    public String messageAt(long nowNanos);      // 过期返回 null
    public void clear();
}
```

- 到期靠渲染 tick 现读（`GlobalKeyRouter` 每个 `TickEvent` 都触发重绘），不引入定时器；单条消息（新 flash 覆盖旧值）。

**修改 `ChatScreenLayout`（并入预算）**：字段 `FlashNotice`；`flash(...)`；`flashElement()`（`│` 无；纯反白行：`" " + truncate(message, width-2) + " "`，`Style.reversed()`），追加在 `render()` 与 `renderBottomArea()` 的**最后**（pi 的底部合成语义）。

### 4.5 C3 设置列表

**新增 `pi-java-tui/.../screen/settings/SettingsListElement.java`（≤230 行）**

- `StyledElement`，按 `width` 渲染：搜索行（`SingleLineInput`，prompt `"> "`，含反白光标）＋空行；空表/无匹配文案＋hint；可见窗口行：前缀 `"→ "`/`"  "`、标签 pad 到 `min(36, maxLabelWidth)`、两空格、值 `TextLayout.truncateToWidth(value, width-usedWidth-2)`；滚动指标 `(i/n)`；选中描述按 `width-4` 折行、前缀两空格、dim；hint 行逐字。
- 颜色：选中行 `cyan()`（沿用现观感），其余默认；hint/描述 dim；未知 id 不影响。
- `handleMouseEvent`：滚轮 ⇒ `model.move(±1)`；左键 press ⇒ `model.selectIndex(row)`；click ⇒ `screen.activate` 回调（构造注入 `Runnable/IntConsumer`）。

**修改 `SettingsListModel`**：`int selectedIndex()`、`void selectIndex(int)`（clamp）。
**修改 `SettingsListView`**：改为 `new SettingsListElement(model, ...)` 包在 `panel("Settings")`。
**修改 `SettingsScreen`**：把 `SettingsListElement` 的点击激活回调接到 `activateSelected()`；键盘路径不变（搜索/上下/PgUp/PgDn/Enter/Space/Esc）。
**窄差 N6**：子菜单 `navigateTo` 不接（现有子菜单不返回该选项）；鼠标 hover 高亮不做。

### 4.6 C4（见 §4.1）与 C13

**新增 `pi-java-tui/.../component/TruncatedText.java`（≤100 行）**

- `StyledElement`，`TruncatedText(String text, int paddingX, int paddingY)`；渲染严格照 §2.6：空行/首行截断/左右 pad/补满 width/上下 pad。

### 4.7 C15 dynamic-border

**新增 `pi-java-tui/.../component/DynamicBorder.java`（≤60 行）**

- `StyledElement`，`Supplier<Style>`；`renderContent` 写 `─`×width（最少 1）。

**新增 `pi-java-tui/.../theme/ThinkingBorderPalette.java`（≤60 行）**

```java
public static Style forLevel(ModelThinkingLevel level); // label(): off/minimal/low/medium/high/xhigh/max
```

- 映射 `ThemeToken.THINKING_OFF/MINIMAL/LOW/MEDIUM/HIGH/XHIGH/MAX`，经 `TuiThemeBridge.fg`。

**修改 `EditorComponent`（+≤25 行）**

- 字段 `Supplier<Style> borderStyle = SemanticColors::border`、`boolean borderVisible = true`；`setBorderStyleSource(...)`、`setBorderVisible(boolean)`。
- `render()`：`borderVisible` ⇒ `Column(topBorder, row, bottomBorder)`，两条 `DynamicBorder(borderStyle)`；否则现形。
- `lineCount()`：`Math.max(1, state.lineCount()) + (borderVisible ? 2 : 0)`。

**修改 `ChatInputPortal` / `ChatScreen`**：`setEditorBorderStyleSource(Supplier<Style>)` 透传（ChatScreen 净 0 行，见 §6）。
**修改 `PiTuiLauncher`**：`chatScreen.setEditorBorderStyleSource(() -> ThinkingBorderPalette.forLevel(session.harness().getThinkingLevel()))`（`ModelThinkingLevel.label()` 现读；主题切换下一帧生效）。
**窄差 D2**：bash-mode 边框不接（Java 无 bash 输入模式态）。

### 4.8 C20 假光标

**修改 `EditorElement`（+≤20 行）**

- 字段 `BooleanSupplier showHardwareCursor = () -> false`；`setShowHardwareCursorSource(...)`。
- `renderCursor`：硬件光标开 ⇒ 不画（终端光标由 runner 的 `hideCursor(false)` 负责）；关 ⇒ 光标单元格 `patchStyle(Style.reversed())`（反白，替代固定 `CURSOR` 青色底）。
- 光标在行尾/空行 ⇒ 反白一个空格；不可见（滚动/越界）⇒ 不画。
- `EditorComponent` 透传 setter；`ChatInputPortal` 透传；`PiTuiLauncher` 注入 `uiPrefs().getShowHardwareCursor()`。
- **窄差 N5**：硬件光标位置标记（APC `CURSOR_MARKER`）不移植——TamboUI 无该通道；开硬件光标时只保证「不叠假光标」。

### 4.9 K18 键位提示

**修改 `KeybindingHints`（+≤40 行）**

```java
public static String formatKeyText(String key, boolean capitalize);
public static Element keyHint(KeyStroke stroke, String description);
public static Element rawKeyHint(String key, String description);
```

- `formatKeyText`：`/` 分段、`+` 连接；darwin 且段为 `alt` ⇒ `option`；`capitalize` 每段首字母大写。
- `keyHint`/`rawKeyHint`：`Span(dim(keyText))` + `Span(" ")` + `Span(muted(desc))` 的 rich text 元素（pi 的 ANSI 串在 Java 是 `Style`＋`Span`，见 N7）。
- 消费点：`TranscriptSearchBar` 底行 `rawKeyHint("↑"/"↓"/"Esc", ...)`；`SettingsListElement` hint 行可用 `rawKeyHint`（保持逐字文案）。

## 5. 窄差登记（不进分子；D 为已裁决不可达面）

| # | 项 | 窄差 | 理由 |
|---|---|---|---|
| N1 | 搜索条位置/鼠标按钮 | N | 顶部三行条（TamboUI Column 无浮动 overlay 合成），无「上一/下一」按钮的 hover/点击；键盘等价 |
| N2 | 语料字素粒度 | N | 非 ASCII 按码点而非 `Intl.Segmenter` 字素簇；列宽同 `visibleWidth` 规则，组合字素可能差一列 |
| N3 | 选择长尾 | N | 无双击选词/三击选行、边缘自动滚动定时器、OSC 8 点击打开、右键粘贴；copy-on-select 与 `ctrl+c` 等价面已落 |
| N4 | flash 计时/堆叠 | N | tick 驱动到期（无 `setTimeout`），单条覆盖而非栈；视觉（反白行）与时长（1000/5000 ms）同 |
| N5 | 硬件光标定位 | N | 无 APC `CURSOR_MARKER`；开硬件光标仅抑制假光标，位置由 TamboUI/终端决定 |
| N6 | C3 子菜单 navigateTo / hover | N | 现有子菜单不产生 `navigateTo`；hover 不改变选中（pi 同）但无高亮 |
| N7 | K18 返回类型 | N | 返回 TamboUI `Element`（`Style`/`Span`），非 pi 的 ANSI 串；文本形状逐字 |
| D2 | bash-mode 边框 | D | Java 无 bash 输入模式态（pi `isBashMode` 无对应物） |

## 6. 行数与结构约束

- Checkstyle：**500 行/文件**（超限即 error）。
- `ChatScreen` 现 **500 行**：新增委托（`transcriptSearch`/`copyActiveSelection`/`setEditorBorderStyleSource`）约 12–18 行，同步压缩类注释与冗余 javadoc 等量；改后必须 ≤500。
- `ChatViewportElement` 现 416 行：新增拖拽选择/高亮/滚动约 +50，≤470；选择逻辑本体放 `TranscriptSelection`。
- `ChatPanel` 现 110 行、`ChatScreenLayout` 现 213 行、`GlobalKeyRouter` 现 360 行、`EditorComponent` 现 390 行：均在限额内。
- 新文件单文件 ≤300 行；需要时按「状态类＋元素类」拆分。

## 7. 预期读数

| 行 | 前 | 后 | 权重变化 |
|---|---|---|---|
| S2a | 缺失 | 对齐 | +1.0 |
| K21 | 缺失 | 对齐 | +1.0 |
| S2b | 缺失 | 对齐 | +1.0 |
| K22 | 缺失 | 对齐 | +1.0 |
| S2c | 缺失 | 对齐 | +1.0 |
| C3 | 缺失 | 对齐 | +2.0 |
| C4 | 缺失 | 对齐 | +1.0 |
| C13 | 存疑(0.5) | 对齐 | +1.0 |
| C15 | 缺失 | 对齐 | +1.0 |
| C20 | 缺失 | 对齐 | +1.0 |
| K18 | 存疑(0.5) | 对齐 | +1.0 |

**210.5＋12.0＝222.5/252＝88.3%**（未加权 112＋11＝123/153）。若任一行因窄差降级，按实际逐行回算并在地图注明。

## 8. 验收

- 每项落地即跑：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am`。
- 新增/更新单测见 `docs/27-w6-tasks.md` §2。
- 全绿后：`docs/map/05` 11 行改判＋§加权汇总/复核修正追加 W6 条；`docs/05` §1/§1.1/§1.2；`docs/09` L7 行与「下一分段」改 W7；`docs/04` §0 追加 W6 条目；本稿 §10 追加实施结果。
- 改动留工作区，不 commit。

## 9. 遗留（明确不做）

- **#20（L6 context footer）**：`SessionSnapshot` 无上下文窗口字段（`name/model/phase/totalTokens/turnCount/...`），改 record 契约会连锁 protocol/tests，超 W6 边界；#20 维持存疑，登记 W8 收口（docs/21 §8 的收口条件顺延，地图复核注记）。
- 全屏图片协议/LaTeX/Mermaid：W7。
- `docs/21` §4 漏项 1/2/3/8/9/10：W8。

## 10. 实施结果（2026-11-24）

| 项 | 落地 | 偏差 |
|---|---|---|
| S2a/K21 | `TranscriptSearchIndex`（语料/span/缓存）、`TranscriptSearchController`（开关/查询/环回/锚点）、`TranscriptSearchBar`（三行框线＋`n/m`＋提示）、`LineHighlighter`（按列切 span）、`GlobalKeyRouter` 搜索先吃键＋`KeybindingsManager.ALT_SEARCH*` 四动作 | 搜索条在聊天区顶部；无浮动 overlay（N1）；非 ASCII 按码点（N2） |
| S2b/K22 | `ViewportInteraction`＋`TranscriptSelection`（反白／多行切列／行尾去空格）、拖拽通道（含 X10 重复 PRESS）、`ChatPanel.setSelectionSink` → `ClipboardSupport.copy`＋flash、`ctrl+c` 有选区复制不清输入 | 无双击选词/边缘自动滚动（N3） |
| S2c | `FlashNotice`（1000/5000 ms）＋`ChatScreenLayout.flashElement` 反白单行（fullscreen/inline 末尾） | tick 到期、单条覆盖（N4） |
| C3 | `SettingsListElement`：搜索行＋对齐（≤36）＋值截断＋描述折行＋`(i/n)`＋滚轮/点击；`SettingsListModel.selectedIndex/selectIndex/visibleStartIndex` | 子菜单 `navigateTo` 未接（N6） |
| C4 | `SingleLineInput`＋`SingleLineInputElement`（单行/粘贴清洗/kill ring/undo/词移动/横向滚动/按列定位） | 搜索条消费；设置搜索仍走 model（pi 亦为 Input，但 Java 设置屏键路径不变） |
| C13 | `TruncatedText`＋`TextColumns`（`truncateToWidth/plainText/sliceByColumns/charIndexAtColumn`）；`TextLayout` 保留薄委托 | `visual-truncate` 在本锚点上游已删，按 truncated-text 计 |
| C15 | `DynamicBorder`＋`ThinkingBorderPalette`（`THINKING_*`）＋`EditorComponent` 上下横线（`lineCount()+2`）；`PiTuiLauncher` 注入现读源 | bash-mode 边框不接（D2） |
| C20 | `EditorElement` 反白光标＋`showHardwareCursor` 抑制；`EditorComponent/ChatInputPortal/Launcher` 透传 | 无 APC 定位（N5） |
| K18 | `KeybindingHints.formatKeyText/keyHint/rawKeyHint`（dim 键＋muted 描述） | 返回 `Element`（N7） |

- 测试：`SingleLineInputTest`、`TranscriptSearchIndexTest`、`TranscriptSearchControllerTest`、`LineHighlighterTest`、`TranscriptSelectionTest`、`ChatViewportSelectionTest`、`FlashNoticeTest`、`TruncatedTextTest`、`DynamicBorderTest`、`ThinkingBorderPaletteTest`、`EditorElementCursorTest`、`SettingsListElementTest`、`KeybindingHintsTest` 扩充；`EditorComponentTest`／`PiTuiAppInputTest`／`FakeBackend` 随边框与反白光标更新；`KeybindingsManagerTest` 动作数 47→51。
- 验证：`./mvnw test -pl pi-java-tui` 全绿（JDK 25）；`pi-java-coding-agent` 的 `KeybindingsManagerTest` 全绿。
- 读数：**222.5/252＝88.3%**（未加权 123/153），与 §7 预期一致。
- 行数：`ChatScreen` ≤500（压缩等量注释）；`ChatViewportElement` 498；`TextLayout` 拆出 `TextColumns` 后 ≤500；`SingleLineInput` ~350。
