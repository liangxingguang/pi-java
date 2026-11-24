# 27 - L7 W6 任务清单（spec 执行序）

> 配套设计：`docs/27-w6-design.md`。所有路径相对 `d:\workplaceForai\pi-java`；pi 取证锚点 `a00a5048d`。
> 完成判据：每项落地后 `$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿；最终 `map/05`＋`docs/05`/`09`/`04` 回填；改动留工作区不 commit。

- [x] T1 C4 单行输入底座（依赖：无；被 T2/T8 复用）

| 项 | 内容 |
|---|---|
| 新增 | `pi-java-tui/src/main/java/com/pijava/tui/component/SingleLineInput.java`（≤260 行） |
| 新增 | `pi-java-tui/src/main/java/com/pijava/tui/component/SingleLineInputElement.java`（≤90 行） |
| 修改 | `pi-java-tui/src/main/java/com/pijava/tui/util/TextLayout.java`：`truncateToWidth(String,int)`、`sliceByColumns(String,int,int)`（MarkupTokens 口径） |
| 行为 | prompt/placeholder；括号粘贴（去 `\r\n`、`\t`→4 空格）；kill ring（Ctrl+K/U/W、Alt+D、Ctrl+Y/Alt+Y）；undo（连续词字符合并）；Ctrl+A/E/B/F、Home/End、Left/Right、Backspace/Delete；横向滚动半宽规则；光标反白；鼠标按列定位 |
| 测试 | 新增 `SingleLineInputTest`：输入/退格/光标左右/粘贴清洗/行首尾/词删/kill-yank/undo/横向滚动起点/按列定位 |
| 验收 | `./mvnw test -pl pi-java-tui -am` 全绿 |

- [x] T2 S2a＋K21 转录搜索

| 项 | 内容 |
|---|---|
| 新增 | `component/TranscriptSearchIndex.java`（≤190 行）：语料/span/匹配/缓存/`matchKey`，ASCII 快路径 |
| 新增 | `component/TranscriptSearchController.java`（≤170 行）：开关/查询/next/prev/高亮/当前行/键盘分派 |
| 新增 | `component/TranscriptSearchBar.java`（≤120 行）：三行框线＋结果计数＋键位提示（`rawKeyHint`） |
| 修改 | `component/ChatViewportElement.java`：`Highlight` 记录、`setHighlights`、`rowsSnapshot()`、`ScrollState.scrollToRow`、渲染时 `LineHighlighter.apply` |
| 新增 | `util/LineHighlighter.java`（≤130 行）：按显示列切 span 上样式 |
| 修改 | `component/ChatPanel.java`：`rowsSnapshot()`/`setHighlights`/`scrollToRow` |
| 修改 | `screen/ChatScreenLayout.java`：搜索条插入 `render()`/`renderBottomArea()` 顶部；持有控制器 |
| 修改 | `screen/ChatScreen.java`：`transcriptSearch()` 委托（净 0 行，压缩等量注释） |
| 修改 | `coding-agent/.../core/KeybindingsManager.java`：`tui.altScreen.search/searchNext/searchPrevious/searchClose`（`ctrl+shift+f`、`enter`/`ctrl+g`、`shift+enter`/`ctrl+shift+g`、`escape`），overlay 表＋`actionIds()` |
| 修改 | `app/GlobalKeyRouter.java`：搜索激活时先吃键；未激活命中 search 键 ⇒ toggle；`ctrl+g` 仅搜索激活时让位 |
| 测试 | `TranscriptSearchIndexTest`（大小写/空白归一/ASCII 词块/跨 span 合并/缓存 changed/空查询）、`LineHighlighterTest`（区间切分/宽字符）、`TranscriptSearchControllerTest`（下一/上一环回、锚点选择、关闭清高亮） |
| 验收 | 搜索键开合、输入过滤、`n/m` 计数、当前匹配滚动可见、Esc 关闭；全绿 |

- [x] T3 S2b＋K22 鼠标选择 / copy-on-select

| 项 | 内容 |
|---|---|
| 新增 | `component/TranscriptSelection.java`（≤170 行）：anchor/focus、反向排序、`text(rows)`、高亮、清空 |
| 修改 | `component/ChatViewportElement.java`：拖拽通道接选区（含 Windows X10 重复 PRESS）、渲染合并高亮、`setSelectionSink`/`hasActiveSelection`/`copyActiveSelection`/`clearSelection`；`clear()` 清选区 |
| 修改 | `component/ChatPanel.java`：选区委托＋`setSelectionSink`；`clear()` 清选区 |
| 修改 | `screen/ChatScreenLayout.java`：注入 `copySelection`（`ClipboardSupport.copy`）＋成功/失败 flash |
| 修改 | `screen/ChatScreen.java`：`copyActiveSelection()` 委托（净 0 行） |
| 修改 | `app/GlobalKeyRouter.java` `CLEAR`：有选区先复制（不清输入） |
| 测试 | `TranscriptSelectionTest`（正向/反向/多行/尾随空格/列切分）、`ChatViewportSelectionTest`（press-drag-release → sink 收到文本；空选区不复制；复制异常走 flash） |
| 验收 | 拖选可见反白；释放自动复制；`ctrl+c` 复制不清输入；全绿 |

- [x] T4 S2c flash 提示

| 项 | 内容 |
|---|---|
| 新增 | `screen/FlashNotice.java`（≤70 行）：`show(msg)`/`show(msg,ms)`/`messageAt(now)`/`clear` |
| 修改 | `screen/ChatScreenLayout.java`：字段＋`flash(...)`＋`flashElement()`（反白行，`truncateToWidth`），追加在 fullscreen/inline 渲染末尾 |
| 测试 | `FlashNoticeTest`（默认 1000ms、5s 定制、过期 null、覆盖旧值、clear） |
| 验收 | 复制成功 `Copied!` 1s 消失；失败 5s；全绿 |

- [x] T5 C13 truncated-text

| 项 | 内容 |
|---|---|
| 新增 | `component/TruncatedText.java`（≤100 行）：首行、左右 padding、补满 width、上下 paddingY |
| 依赖 | T1 的 `TextLayout.truncateToWidth` |
| 测试 | `TruncatedTextTest`（超宽截断含 `…`、多行只取首行、paddingX/Y、恰好 width） |
| 验收 | 全绿；`map/05` C13 存疑→对齐 |

- [x] T6 C15 dynamic-border（编辑器思考等级边框）

| 项 | 内容 |
|---|---|
| 新增 | `component/DynamicBorder.java`（≤60 行）：`Supplier<Style>`，`─`×width |
| 新增 | `theme/ThinkingBorderPalette.java`（≤60 行）：`ModelThinkingLevel.label()` → `ThemeToken.THINKING_*` → `TuiThemeBridge.fg` |
| 修改 | `component/EditorComponent.java`：`setBorderStyleSource`/`setBorderVisible`；`render()` 加顶/底横线；`lineCount()`＋2 |
| 修改 | `screen/ChatInputPortal.java`：`setEditorBorderStyleSource` 透传 |
| 修改 | `screen/ChatScreen.java`：委托（净 0 行） |
| 修改 | `app/PiTuiLauncher.java`：`chatScreen.setEditorBorderStyleSource(() -> ThinkingBorderPalette.forLevel(session.harness().getThinkingLevel()))` |
| 测试 | `DynamicBorderTest`、`ThinkingBorderPaletteTest`（off/minimal/low/medium/high/xhigh/max 令牌映射）、`EditorComponentTest`（两条横线、lineCount＋2、边框色源切换） |
| 验收 | 全绿；思考等级变更下一帧边框变色（主题色现读） |

- [x] T7 C20 假光标

| 项 | 内容 |
|---|---|
| 修改 | `util/EditorElement.java`：`setShowHardwareCursorSource`；硬件开 ⇒ 不画假光标；关 ⇒ 光标单元格 `Style.reversed()`（行尾反白空格） |
| 修改 | `component/EditorComponent.java` / `screen/ChatInputPortal.java`：setter 透传 |
| 修改 | `app/PiTuiLauncher.java`：注入 `uiPrefs().getShowHardwareCursor()` |
| 测试 | `EditorElementCursorTest`（默认反白光标；硬件开无假光标；行尾/滚动越界；光标列随内容宽字符） |
| 验收 | 全绿；不再出现固定青色方块 |

- [x] T8 C3 设置列表宽度感知＋鼠标

| 项 | 内容 |
|---|---|
| 新增 | `screen/settings/SettingsListElement.java`（≤230 行）：搜索行、标签对齐（≤36）、值截断、居中窗口、`(i/n)`、描述折行、hint 逐字、滚轮/点击 |
| 修改 | `screen/settings/SettingsListModel.java`：`selectedIndex()`/`selectIndex(int)` |
| 修改 | `screen/settings/SettingsListView.java`：改渲染 `SettingsListElement`（面板标题不变） |
| 修改 | `screen/SettingsScreen.java`：点击激活回调接 `activateSelected()` |
| 测试 | `SettingsListElementTest`（对齐/值截断/描述折行/滚动指标/无匹配/hint 两态/滚轮与点击映射） |
| 验收 | 全绿；键盘行为不回归（`SettingsScreenTest` 现有用例） |

- [x] T9 K18 keyHint / rawKeyHint

| 项 | 内容 |
|---|---|
| 修改 | `component/KeybindingHints.java`：`formatKeyText(String,boolean)`、`keyHint(KeyStroke,String)`、`rawKeyHint(String,String)`（dim 键＋muted 描述） |
| 消费 | `TranscriptSearchBar` 底行用 `rawKeyHint`；`SettingsListElement` hint 可用 `rawKeyHint` |
| 测试 | 扩充 `KeybindingHintsTest`：`formatKeyText` 的 `/`、`+`、capitalize、darwin `option`；`keyHint`/`rawKeyHint` 文本内容与样式 |
| 验收 | 全绿；`map/05` K18 存疑→对齐 |

- [x] T10 回归、行数与回填

| 项 | 内容 |
|---|---|
| 行数 | `ChatScreen` ≤500；`ChatViewportElement` ≤500；新文件 ≤300（Checkstyle 500 硬限） |
| 回归 | 全屏滚动/滚动条/卡点击/流式草稿/补全/PgUp/PgDn/`ctrl+c` 空输入退出 全部不回归 |
| 测试 | `$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿 |
| 回填 | `docs/map/05-tui-web.md`：S2a/K21/S2b/K22/S2c/C3/C4/C13/C15/C20/K18 改判＋§加权汇总/复核修正追加 W6 条；`docs/05` §1/§1.1/§1.2；`docs/09` L7 行＋L7 段＋「下一分段」改 W7；`docs/04` §0 追加 W6 条目；`docs/27-w6-design.md` §10 实施结果 |
| 读数 | 按 11 行逐行回算，预期 222.5/252＝88.3%（未加权 123/153）；窄差降级则实算并注记 |
| 提交 | 不 commit（除非用户明确要求） |

## 执行顺序

T1 →（T2、T8 可并行）→ T3 → T4 → T5 → T6 → T7 → T9 → T10。每完成一项即跑定向测试；T10 前预留一次全量 `pi-java-tui -am`。
