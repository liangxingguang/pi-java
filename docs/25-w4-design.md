# 25 - L7 W4 设计稿：补全与选择器（S16 门控、K41、K25、K26、K31、extension-selector 窗口化、C2 复核）

> 【背景摘要】`docs/21` §7 把 W4 定为「补全与选择器」：**S16 门控补偿(w1)、K41 斜杠 Enter 上下文(1)、K25 `@文件`补全(w2)、K26 大粘贴标记(1)、K31 jump mode(1)、extension-selector 窗口化(1)、C2 多选复核**。本稿是 spec＋实施计划。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（W1–W3 已收口、未提交）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | S16 从「存疑」翻「对齐」；K41 新增行并落地；K25/K26/K31 从「缺失」翻「对齐」；extension-selector 补 10 行居中窗口＋`(i/n)`；C2 复核（多选并非 pi 能力）并补 ↑↓ wrap/滚动计数 |
| 行为不变量 | 文本级架构不动；slash 补全现有行为不回归；`EditorComponent` 公共面（`getText`/`setText`/`insertText`/`submit`）语义保持，仅追加扩展（粘贴展开/jump/光标前替换） |
| 完成判据 | ①`./mvnw test -pl pi-java-tui -am` 全绿（JDK25）；②`map/05` 新增 K41 行、K25/K26/K31/S16/C2 改判；③`docs/05`、`docs/09`、`docs/04` §0 同步；④改动留工作区不 commit |
| 不做项 | 完整 `fd` 外部进程（用 Java NIO 有界遍历替代，窄差 N）；终端 tmux CSI-u 粘贴解码（窄差 N）；粘贴标记在词删/行删中的原子性（窄差 N）；SelectList 描述列（Java 消费点无 description，窄差 N） |

## 2. 上游证据（均对 `a00a5048d`）

### 2.1 S16 query 门控（同族四处）

- `packages/coding-agent/src/modes/interactive/components/config-selector.ts:504-508`：转发键前比较 `previousQuery`，query 未变不 `filterItems`（方向键只移搜索光标，不重置选中）。
- 同族：`thinking-selector.ts:148-151`、`scoped-models-selector.ts:407-410`、`settings-submenu.ts:134-137`。

### 2.2 K41 斜杠命令 Enter 上下文

- `packages/tui/src/components/editor.ts:2218-2224`：`isInSlashCommandContext`＝trimStart 以 `/` 开头；`isInSlashCommandNameContext`＝前者且 trimStart 不含空格。
- `:804-830`：补全应用后 `submitsCommand = autocompletePrefix.startsWith("/") && isInSlashCommandNameContext(beforeCursor)`；仅此情形「fall through to submit」，文件/参数补全只应用不提交。

### 2.3 K25 `@文件`补全 `packages/tui/src/autocomplete.ts`

- 触发：`getSuggestions :311-330` 先 `extractAtPrefix`（`@"` 引号形优先），再走 slash 分支，最后 `extractPathPrefix`。
- 前缀提取：`PATH_DELIMITERS = [" ", "\t", '"', "'", "="]`（`:9`）、CJK 标点分隔（`utils.ts:57-64`：`autocompleteSeparatorRegex`＝空白或 CJK 标点）、`stripLeadingWrappers`（`:60-70`）、`findUnclosedQuoteStart`（`:72-86`）、`isTokenStart`（`:88-94`）、`parsePathPrefix`（`:116-127`）、`buildCompletionValue`（`:129-147`，需引号时 `@"…"`）。
- 建议：`getFileSuggestions :597-732`（目录列举、大小写不敏感前缀、目录优先按 label 排序）；`scoreEntry :734-754`（同名 100／前缀 80／文件名子串 50／全路径子串 30，目录 +10）；`getFuzzyFileSuggestions :769-859`（fd 递归、baseDir 一层、去重、取前 20、按 score→深度→长度→路径排序）；`walkDirectoryWithFd :170-244`（`--hidden --follow`，排除 `.git`，上限 100）。
- 应用：`applyCompletion :421-477`——`@` 前缀替换 token；目录不加尾空格、文件加一个空格；目录带尾 `"` 时光标回退一格。

### 2.4 K26 大粘贴折叠 `packages/tui/src/components/editor.ts`

- `:31-39` 标记正则 `\[paste #(\d+)( (\+\d+ lines|\d+ chars))?\]`＋单段判定（`length>=10`）；`:41-90` `segmentWithMarkers` 让标记成为原子段。
- `:1276-1345 handlePaste`：`normalizeText`（`\r\n|\r`→`\n`、tab→4 空格）→ 过滤 `<32` 非换行字符 → 文件路径前补空格（前字符为 `\w`）→ **>10 行或 >1000 字符**登记 `pastes`＋`pasteCounter++` 并插入 `[paste #N +L lines]` / `[paste #N C chars]`。
- `:1106-1120 expandPasteMarkers`；`submitValue :1040-1055` 提交前展开并清空注册表。
- `:1411-1433` 退格删除整个标记：删注册表项、`pasteCounter--`、高位 id 下移、文本内标记重编号。
- `:225-229 / :2133-2142` undo 快照携带 `pastes`＋`pasteCounter`。

### 2.5 K31 jump mode `packages/tui/src/components/editor.ts`

- `:359-360 jumpMode`；`:986-993` `tui.editor.jumpForward/jumpBackward` 进入等待字符态；`:704-722` 等待态：再次按触发热键取消、可打印字符执行跳转、控制字符取消并回落；`:2151-2174 jumpToChar`：多行、区分大小写、跳过当前光标位、找不到不动。
- 键位 `packages/tui/src/keybindings.ts:106-110`：`ctrl+]` 向前、`ctrl+alt+]` 向后。

### 2.6 extension-selector 窗口化

- `packages/coding-agent/src/modes/interactive/components/extension-selector.ts:76-99 updateList`：`maxVisible = 10`；`startIndex = clamp(selectedIndex - 5, 0, options.length - maxVisible)`；越界时追加 muted `  (i/n)`（Text paddingX=0）。

### 2.7 C2 选择列表 `packages/tui/src/components/select-list.ts`

- `:143-171`：↑↓ **wrap**（顶部上移到底、底部下移回顶），PgUp/PgDn 走 `getSelectPageIndex`（clamp）；Enter 确认、Esc/Ctrl+C 取消。
- `:79-113`：无匹配显示 `  No matching commands`；滚动时 `  (i/n)`；`:238-263` 描述列（宽度 > 40 时第二列）。
- **无多选/分组能力**——`map/05` C2 原注「多选仍未移植」系误记。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| S16 | 转发键后无条件重过滤，`filterItems` 末尾 `selectFirstItem()` | `ConfigSelectorComponent.java:114-117`、`ConfigSelectorModel.java:373-376`、`:338` |
| K41 | Enter 恒「先补全再提交」，无补全上下文判定 | `GlobalKeyRouter.java:125-133` |
| K25 | 仅 slash 补全，无 `@`/路径补全 | `SlashCompleter.java`、`ChatInputPortal.java:33-47` |
| K26 | `insertText` 直接插入，无折叠/注册表/展开 | `EditorComponent.java:425-433`、`:489-499` |
| K31 | 无 jump mode | `EditorComponent.java:98-185` |
| extension-selector | 全量绘制，无窗口/计数 | `ExtensionSelectorOverlay.java:175-183` |
| C2 | 上下选 clamp 不 wrap；无 `(i/n)`/无匹配文案 | `SelectList.java:74-81`、`:250-261` |

## 4. 设计（Java 方案）

### 4.1 S16：query 未变不重过滤

`ConfigSelectorComponent.onKeyEvent` 末段改为：

```java
String before = searchEditor.getText();
searchEditor.onKeyEvent(event);
String after = searchEditor.getText();
if (!after.equals(before)) {
    model.filterWith(after);
}
```

- 对齐 pi `previousQuery` 门控：←/→/Home/End 只移搜索光标，选中项不重置。
- 同族核查：`ThinkingLevelScreen`/`ScopedModelsScreen` 的搜索行、`SettingsScreen` 子菜单——若存在「转发键 → 无条件 filter/refresh」同形缺陷则同样加文本比较门控；不存在则在设计附录记录证据（不改）。

### 4.2 K41：Enter 的补全上下文

`ChatInputPortal` 新增：

```java
/** Enter 语义：@return 是否应继续提交（slash 命令名/无补全 ⇒ true；文件补全 ⇒ false）。 */
boolean enterWithCompletion() {
    if (completer.active()) { applyCompletion(); return true; }
    if (fileCompleter.active()) { applyFileCompletion(); return false; }
    return true;
}
```

`GlobalKeyRouter.java:125-128` 改为：

```java
if (TamboUIAdapter.isSendEnter(event)) {
    if (chatScreen.enterWithCompletion()) slashFlow.submit();
    return EventResult.HANDLED;
}
```

- `ChatScreen` 暴露 `boolean enterWithCompletion()` 转发 portal。
- Tab 分支改为无条件 `chatScreen.onKeyEvent(event)`：portal 内部依次尝试 slash 补全 → 文件补全 Apply → 无活动补全时触发**强制**文件补全（pi `shouldTriggerFileCompletion`）。

### 4.3 K25：`@文件`补全

**新增** `pi-java-tui/src/main/java/com/pijava/tui/component/file/FileCompleter.java`：

```java
public final class FileCompleter {
    public record Suggestion(String value, String label, String description) {}
    public enum KeyAction { IGNORED, HANDLED, COMPLETE }
    public FileCompleter(Path basePath, IntSupplier maxVisible);
    /** 依据「光标前同行文本」刷新；返回是否激活。 */
    public boolean update(String textBeforeCursor, boolean force);
    public boolean active();
    public List<Suggestion> matches();
    public String selectedValue();
    /** 被替换 token 的字素长度（调用方 replaceBeforeCursor 用）。 */
    public int replaceGraphemes();
    public KeyAction onKeyEvent(KeyEvent event); // ↑↓/PgUp/PgDn/Tab/Enter/Esc
    public Element render();
    public int lineCount();
    // 纯函数（测试面）
    static String extractAtPrefix(String text);
    static String extractPathPrefix(String text, boolean force);
    static String buildCompletionValue(String path, boolean dir, boolean at, boolean quoted);
    static int scoreEntry(String path, String query, boolean dir);
    static List<Suggestion> listDirectory(Path dir, String namePrefix);
}
```

逐项对位：

- 分隔符/边界：`isSeparator(cp)`＝`Character.isWhitespace` 或 CJK 标点（`isCjk && Character.getType ∈ PUNCTUATION_*`）或显式全角标点集；`PATH_DELIMITERS`＋wrapper `()[]{}\`<>`；`findLastDelimiter`/`stripLeadingWrappers`/`isTokenStart`/`findUnclosedQuoteStart` 照搬。
- `extractAtPrefix`：未闭合 `@"` 优先；否则末分隔符后的 token 去 wrapper 必须以 `@` 开头。
- `extractPathPrefix`：含 `/`、以 `.` 或 `~/` 开头触发；force 恒返回；空 token 仅在 `isTokenStart` 时返回空串。
- 建议：`@` 触发走「base 一层 + 有界递归」（NIO `Files.walkFileTree`，`FOLLOW_LINKS`、含隐藏、跳过任何名为 `.git` 的目录、访问上限 20000、结果上限 100），`scoreEntry` 打分后按 score→深度→长度→路径排序取前 20；含 `/` 的查询走 scoped 目录列举（`getFileSuggestions` 语义：大小写不敏感前缀、目录优先、label 排序）。
- 取值：`buildCompletionValue` 需引号时 `@"path"`；目录尾 `/`，应用时文件补一个尾空格。
- 触发优先级：slash 名称上下文（trimStart 以 `/` 且无空格）不激活文件补全；`@` 优先于普通路径。
- 渲染：复用 `SlashCompleter` 行形（`▸ ` 高亮＋`  ` 普通＋`… N more`），窗口 `maxVisible` 居中。

`EditorComponent` 追加只读/光标前编辑 API：

```java
public String lineBeforeCursor();            // 当前行 [0, cursor) 文本
public void replaceBeforeCursor(int graphemes, String text); // 退格 N 字素后插入（单次 undo）
```

`ChatInputPortal` 接线：持有 `FileCompleter`（basePath＝会话 cwd 注入），`onKeyEvent` 顺序 slash → file → editor；`completerActive()/completerLineCount()` 合并两者；`applyCompletion()` slash 优先，否则文件。

### 4.4 K26：大粘贴折叠

`EditorComponent` 内：

- 字段：`Map<Integer,String> pastes`、`int pasteCounter`、两个 `Pattern`（全局重编号用、行尾单标记判定用）。
- `insertText`：`normalizeText`（`\r\n|\r`→`\n`、`\t`→4 空格）→ 过滤码点 `<32` 且非 `\n` → `^[/~.]` 且光标前为 `\w` 时补前导空格 → `>10 行 || >1000 字符` 时登记并插入标记（行型 `+N lines` 优先、否则 `C chars`），否则直接插入。
- `Snapshot` 扩为 `(text,row,col,pastes 副本,pasteCounter)`；`pushSnapshot`/`undo` 同步携带。
- `backspace`/`forwardDelete`：光标紧邻完整标记时整段删除——删注册表、`pasteCounter--`、高位 id 下移、全文本标记重编号；用「绝对偏移→新文本→重定位光标」实现（`setText` 后 `moveCursorToStart`＋逐行/逐字素移动）。
- `submit`：提交值为 `expandPasteMarkers(text).trim()`；提交后清空注册表与计数。
- `setText`/`replaceText`/`clear`：清空注册表与计数（pi `setText` 同）。
- 新增 `public String getExpandedText()`。

### 4.5 K31：jump mode

`EditorComponent`：

- 字段 `JumpMode jumpMode`（`FORWARD/BACKWARD`）。
- `onKeyEvent` **最前**（历史导航之前）处理等待态：再次按触发热键取消；`KeyCode.CHAR` 且无 ctrl/alt 执行 `jumpToChar`；其他取消并回落。
- 触发：`stroke.ctrl() && "]"`→FORWARD；`stroke.ctrl() && stroke.alt() && "]"`→BACKWARD。
- `jumpToChar`：从当前行/当前行之后（前）逐行搜索，`indexOf`/`lastIndexOf`，命中行用 `EditorWordNav.charToGrapheme` 定位，找不到不动；`lastAction=null`。

### 4.6 extension-selector 窗口化

`ExtensionSelectorOverlay.layout()` 列表段：

```java
int maxVisible = 10;
int start = Math.max(0, Math.min(selected - maxVisible / 2, options.size() - maxVisible));
int end = Math.min(start + maxVisible, options.size());
for (int i = start; i < end; i++) { /* 原选中/未选中行形 */ }
if (start > 0 || end < options.size()) {
    rows.add(List.of(new Span("  (" + (selected + 1) + "/" + options.size() + ")", SemanticColors.muted())));
}
```

### 4.7 C2 复核与补齐

- `SelectList.onKeyEvent`：↑↓ 改为 **wrap**（空表直接消费不移动）；PgUp/PgDn 保持 clamp。
- `render()`：`visible.isEmpty()` ⇒ `No matching commands` 文案；窗口化时在列表下方追加 `  (i/n)`（`column(list, text)`）。
- 设计附录记录：多选/分组非 pi `select-list` 能力，原判误记；描述列登记窄差 N。

## 5. 窄差登记（不进分子）

| 项 | 窄差 | 理由 |
|---|---|---|
| K25 fd | N | 外部 `fd` 进程改为 NIO 有界遍历；遍历顺序/ gitignore 细粒度可能不同 |
| K25 plain-path 自动触发 | N | 仅 `@` 自动弹层＋Tab 强制；普通路径自然触发随用随补 |
| K26 原子段 | N | 词删/行删/前向删不保证整标记；退格整标记已对位 |
| K26 CSI-u 解码 | N | tmux `ESC[<cp>;5u` 粘贴解码不移植 |
| K31 键位可配置 | N | jump 键走内置 ctrl+] / ctrl+alt+]（K17 keybindings.json 另账） |
| C2 描述列 | N | Java 消费点无 description 字段 |

## 6. 测试计划

| 测试 | 覆盖 |
|---|---|
| `ConfigSelectorComponentTest`（新/扩） | ←/→ 不重过滤（选中保持）；输入字符重过滤且回首项；空格/Enter toggle 不受影响 |
| `EditorComponentPasteTest`（新） | ≤10 行且 ≤1000 字符直插；11 行→`[paste #1 +11 lines]`；1001 字符→`[paste #1 1001 chars]`；submit 展开；undo 还原文本＋注册表；退格整标记删除并重编号；setText 清空注册表；CRLF/tab 归一；路径前补空格 |
| `EditorComponentJumpTest`（新） | ctrl+] 等待→字符前进命中；ctrl+alt+] 后退；再次热键取消；未命中不动；跨行 |
| `FileCompleterTest`（新） | `@src/ma` 目录列举；模糊打分排序（同名>前缀>子串>路径，目录+10）；`@"…"` 引号形；目录尾 `/` 不补空格、文件补空格；CJK 标点分隔；slash 名称上下文不激活；Tab 强制 |
| `ExtensionSelectorOverlayTest`（扩） | 12 项选中第 11 项窗口起点＝2、计数 `(11/12)`；首屏无计数 |
| `SelectListTest`（扩） | ↑ 顶部 wrap 到底、↓ 底部 wrap 到顶；PgUp/PgDn clamp；空表无匹配文案；窗口计数 |

定向命令：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am`

## 7. 实施步骤（任务分解见 `docs/25-w4-tasks.md`）

1. S16 门控＋同族核查（config 必改，其余记录）。
2. `EditorComponent` 扩展：`lineBeforeCursor`/`replaceBeforeCursor`／K26 注册表与快照／K31 jump（同文件，按 T1 分组提交式改动）。
3. `FileCompleter` 新增＋单测。
4. `ChatInputPortal`/`ChatScreen`/`GlobalKeyRouter` 接线（K41＋K25＋Tab 强制）。
5. extension-selector 窗口化＋单测。
6. C2 wrap/计数/无匹配＋单测。
7. 全绿后回填 `map/05`（K41 新增、K25/K26/K31/S16/C2 改判）、`docs/05` §1/§1.1/§1.2、`docs/09` L7 行/段/§4、`docs/04` §0；本稿补「实施结果」节。

## 8. 预期读数

当前 `map/05` 199.0/251＝79.3%（未加权 101/152）。W4 预计 **+6~8 加权分**：
S16 +1、K41 +1（分母 +1）、K25 +2、K26 +1、K31 +1、extension-selector 窗口化 +1、C2 存疑→对齐 +0~1（按现行存疑折算口径回算）。落点按行逐条回算，不预设总分。

## 9. 实施结果（2026-11-23 收口）

| 项 | 结果 |
|---|---|
| S16 | `ConfigSelectorComponent.onKeyEvent` 改为文本变化才 `filterWith`；同族 `ThinkingLevelScreen`/`ScopedModelsScreen`/`SettingsScreen` 核查无同形缺陷（键处理不无条件重过滤），记录不改 |
| K41 | 新增 `ChatInputPortal.enterWithCompletion()`；`GlobalKeyRouter` Enter 按返回值决定 `slashFlow.submit()`；`ChatScreen.enterWithCompletion()` 转发 |
| K25 | 新增 `FileCompleter`（`Suggestion`/`KeyAction`/弹层/缓存）＋`FileCompletionPaths`（纯路径/前缀/打分/walk）；`EditorComponent.lineBeforeCursor()/replaceBeforeCursor()`；`ChatInputPortal` 持文件补全、键序 slash→file→editor、Tab 强制；`PiTuiApp` 注入会话 cwd |
| K26 | 新增 `EditorPaste`（归一化/折叠/注册表/展开/整标记删除/重编号）；`EditorComponent` 快照携 `pastes`＋`pasteCounter`，`submit` 展开并 trim，`setText/replaceText/clear` 清空 |
| K31 | 新增 `EditorJump`（等待态最前置、再按取消、控制字符回落、多行跳转）；`EditorComponent` 委托 |
| extension-selector | `ExtensionSelectorOverlay.layout()` 10 行居中窗口＋越界 `  (i/n)`（S22 此前已判对齐，属加固、不另计分） |
| C2 | `SelectList` ↑↓ wrap、空表 `No matching commands`、窗口 `(i/n)`；设计附录确认 pi 无多选/分组 |
| 文件拆分 | 为守住 500 行上限，`EditorComponent` 拆出 `EditorKillOps`（kill ring/词操作）；`PiTuiApp` 两行压缩；`ChatScreen` 委托块压缩 |
| 测试 | `./mvnw test -pl pi-java-tui -am` **784 测全绿**（0 失败/0 错误/1 跳过，110 个测试类）；新增 `EditorComponentPasteTest`(10)、`EditorComponentJumpTest`(5)、`FileCompleterTest`(12)，扩 `SelectListTest`(2)、`ExtensionSelectorOverlayTest`(2)、`ConfigSelectorScreenTest`(1)；`ModelThinkingLevelScreenTest` 的「Down 到底」改为一次 `UP`（wrap 语义） |
| 读数 | `map/05` **205.5/252＝81.5%**（未加权 107/153）；投影回算 209.0/260＝80.4%；`docs/05`/`docs/09`/`docs/04` 已同步（W5 为下一分段） |
| 窄差 N | K25 fd/普通路径自然触发；K26 词删/行删原子性与 tmux CSI-u；K31 键位不读 `keybindings.json`；C2 描述列。统一在 P12 编号 |
