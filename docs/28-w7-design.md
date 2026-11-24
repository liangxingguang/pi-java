# 28 - L7 W7 设计稿：屏幕与图（图片协议/粘贴/向导/show-images/复制键/Mermaid/LaTeX）

> 【背景摘要】`docs/21` §7 把 W7 定为「屏幕与图」：**S2d＋R14 图片协议(1×2)、R15 粘贴(1)＋K14、S18 首次设置向导(1)、S21 show-images(1)、K13(1)、R7 Mermaid(1)、R8 LaTeX(1)**。本稿是 spec＋实施计划；任务拆分见 `docs/28-w7-tasks.md`。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（W1–W6 未提交改动之上）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。
>
> 【流程】用户 2026-11-24 明确：设计稿落盘后直接实施，不等审核；改动留工作区不 commit。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | R14/S2d（图片渲染）、R15/K14（剪贴板图片粘贴）、S18（首次设置向导）、K13（Ctrl+X 复制消息）落地；S21 按现状改判；R7 登记不可达窄差；R8 移植 LaTeX 核心 |
| 行为不变量 | 不改 TamboUI 组件协议；`ChatScreen`/新增类均 ≤500 行（Checkstyle）；无图片终端/关闭 show-images ⇒ 逐字回落 `[image: …]` 占位；剪贴板失败不崩；主题/设置项现读 |
| 完成判据 | ①`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿；②`map/05` S2d/R14/R15/K14/S18/S21/K13/R7/R8 九行改判＋§加权汇总复算；③`docs/05` §1/§1.1/§1.2、`docs/09` L7 段、`docs/04` §0 同步；④不 commit |
| 不做项 | R7 Mermaid 本体（无 Java 等价库，见 §5 D 登记）；交互式终端实机验收（无 PTY 的自动化面不可达，见 §5 N 登记） |

## 2. 上游证据（均对 `a00a5048d`）

### 2.1 R14/S2d 图片协议

- `packages/tui/src/terminal-image.ts`（763 行）：
  - 能力：`detectCapabilities`（tmux/screen/Herdr/Rex/Orca/Kitty/Ghostty/WezTerm/Warp/iTerm2/WT/Alacritty/VSCode/Zed/JetBrains/Windows），`PI_IMAGE_PROTOCOL`/`PI_TRUE_COLOR`/`PI_HYPERLINKS` 覆盖；`getCellDimensions` 默认 9×18。
  - 编码：`encodeKitty`（`a=T,f=100,q=2`，`C=1` 禁光标移动，4096 分块 `m=1/m=0`）、`encodeITerm2`（`inline=1;size=…;width=…;height=auto`）、`deleteKittyImage/deleteAllKittyImages`。
  - 尺寸：PNG/JPEG/GIF/WebP 头解析；`calculateImageCellSize`（比例保持＋Kitty 失真优化 `chooseLessDistortedCellCount`）。
  - 行语义：`isImageLine`、`getImageLineExtent`、`stripImageSequences`、Kitty 元数据注册（`registerKittyImageMetadata`）与裁剪。
  - 降级：`imageFallback(mime, dims, filename)`＝`[Image: <path> [mime] WxH]`，支持 OSC 8 `file://` 链接（hyperlinks 能力）。
- `packages/tui/src/components/image.ts`：`Image` 组件按宽度缓存行；Kitty⇒首行序列＋`rows-1` 空行；iTerm2⇒`rows-1` 空行＋光标上移＋序列。
- `tui-alt-screen.ts:358`：退出全屏时 `setCapabilities({images:null})`。

### 2.2 R15/K14 剪贴板粘贴

- `core/keybindings.ts:75-78`：`app.clipboard.pasteImage` 默认 `ctrl+v` / `alt+v`。
- `custom-editor.ts:105-107`：命中该绑定 ⇒ `onPasteImage()`。
- `interactive-mode.ts:3300-3330` 注册；`:3344-3382 handleClipboardPaste`：
  1) 先读剪贴板文件路径（macOS Finder），控制字符 ⇒ 报错；按 bash 模式加引号，否则换行连接；
  2) 再读图片：`readClipboardImage()`（Wayland `wl-paste`、X11 `xclip`、WSL PowerShell、原生剪贴板），写 `os.tmpdir()/pi-clipboard-<uuid>.<ext>`，**把文件路径插入编辑器**；
  3) 最后文本兜底 `readClipboardText()`；
  4) 任一步异常 ⇒ `showError("Failed to paste from clipboard: …")`。
- `utils/clipboard-image.ts`：MIME 白名单 png/jpeg/webp/gif；`extensionForImageMimeType`。

### 2.3 K13 Ctrl+X 复制消息

- `keybindings.ts:63`：`app.message.copy` 默认 `ctrl+x`，描述「Copy selection or last assistant message」。
- `interactive-mode.ts:3303-3306`：`handleCopyCommand({flashConfirmation:true, preferSelection:true})`。
- `:6883-6913`：`preferSelection && !copyOnSelect && hasActiveSelection` ⇒ 复制选区；否则 `session.getLastAssistantText()`，无 ⇒ `showError("No agent messages to copy yet.")`；成功 ⇒ 全屏 `flash("Copied!")`，否则 `showStatus("Copied last agent message to clipboard")`。

### 2.4 S18 首次设置向导

- `cli/startup-ui.ts:132-148 shouldRunFirstTimeSetup`：官方发行版＋`PI_EXPERIMENTAL=1`＋无 `PI_AGENT_DIR`＋`settings.json` 不存在。
- `startup-ui.ts:186-231 showFirstTimeSetup`：启动 TUI；主题预览（system/dark/light，默认 system）；提交 ⇒ `setTheme`＋`setEnableAnalytics`＋`flush`；取消 ⇒ 不写。
- `components/first-time-setup.ts`：两步（theme→analytics）；LOGO 四行；底部 `↑↓ navigate · Enter continue/finish · Esc skip setup`；`j/k` 同 ↓↑。

### 2.5 S21 show-images

- `components/show-images-selector.ts`：Yes/No 两行 SelectList＋上下 DynamicBorder；**仅导出给扩展**（`index.ts:468`）。
- 内置消费面是 `settings-selector.ts:771-779/890`：`supportsImages` 时插入 `show-images` 布尔循环项——Java 已逐字对位（`SettingsItems.java:31-37`＋`SettingsItemsTest`）。

### 2.6 R7 Mermaid

- `components/mermaid.ts`：依赖 `lovely-mermaid`（JS 包）`render()` 出 Unicode 图；`grok-mermaid` 已升级；适用面限 user/assistant/summary；streaming 门控；edgeLabel 色；OSC 8 只链文本。

### 2.7 R8 LaTeX

- `packages/tui/src/latex.ts`（1506 行）：`renderLatex(source,{display})`；符号表、`\frac/\sqrt/^/_`、矩阵、重音、间距、`\not` 否定、黑板体、上下标 Unicode 映射、布局（多行分数/根号/矩阵居中）。
- `markdown.ts:291,621-622,796-797`：块级 `$$…$$`（`display:true`）与行内 `$…$`；`pending` 期间回落原文。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| 图片协议 | 能力探测已在（K35），无编码/尺寸/渲染；消息图片只显 `[image: mime]` | `TerminalCapabilities.java`；`MessageBubble.java:200-205,353-356` |
| 剪贴板 | 只有文本复制 | `ClipboardSupport.java`；`NativeClipboard` |
| Ctrl+X | 无键位；`/copy` 命令存在 | `KeybindingsManager` 无 `app.message.copy`；`MiscCommands.java:95-96` |
| 首次向导 | 无 | 全仓无 first-time 命中 |
| show-images | 设置项已按 `supportsImages` 插入 | `SettingsItems.java:31-37` |
| Mermaid | 围栏按普通代码块 | `MarkdownBlocks/MarkdownRenderer` |
| LaTeX | 无 | 全仓无 latex |
| 原始输出通道 | TamboUI 0.4.0 支持 `RawOutputCapable`＋`Frame.renderWidget` 直写＋`Terminal` 管 raw 区域/`KITTY_DELETE_ALL` | javap 取证：`dev.tamboui.widget.RawOutputCapable`；`Frame.renderWidget` 反射分支；`Terminal.cleanupRawOutput` |

## 4. 设计（Java 方案）

### 4.1 公共底座

**`pi-java-tui/.../util/TerminalImage.java`（R14，≤480 行）**

```java
public final class TerminalImage {
    public record Dimensions(int widthPx, int heightPx) {}
    public record CellSize(int columns, int rows) {}
    public static String encodeKitty(String base64, int columns, int rows, int imageId, boolean moveCursor);
    public static String encodeITerm2(String base64, CellSize size, String name);
    public static String deleteKittyImage(int imageId);
    public static boolean isImageLine(String line);
    public static String stripImageSequences(String line);
    public static Dimensions dimensions(String base64, String mime);
    public static CellSize cellSize(Dimensions dims, int maxWidthCells, int maxHeightCells,
                                    int cellW, int cellH, boolean optimize);
    public static String fallback(String mime, Dimensions dims, String filename);
    public static int allocateImageId();
}
```

- 能力门：`TerminalCapabilities.get().images()`；`PI_IMAGE_PROTOCOL` 已由 K35 覆盖。
- 编码/尺寸/降级逐条对位 §2.1；OSC 8 仅在 `hyperlinks()` 时链接 `file://`。

**`pi-java-tui/.../component/ImagePlacement.java`**

```java
public record ImagePlacement(String base64, String mimeType, String filename,
                             int columns, int rows, int imageId) {}
```

**`pi-java-tui/.../component/ImageElement.java`（≤140 行）**

- `implements dev.tamboui.widget.Widget, dev.tamboui.widget.RawOutputCapable`。
- `Widget.render(rect, buffer)`：以空 Cell 填满区域（占位，不写文本）。
- `RawOutputCapable.render(rect, buffer, out)`：`CSI (rect.y+1);(rect.x+1) H` + Kitty/iTerm2 序列；协议空 ⇒ 不写。
- 序列只发一次到 raw 流；TamboUI `Terminal.cleanupRawOutput` 负责旧区域清理（无需自管）。

**`LogicalLine` / `RenderRow` 载荷扩展**

- `LogicalLine` 加第 6 分量 `ImagePlacement image`，保留 5 参便捷构造（现有调用点零改动）。
- `RenderRow` 同上（第 3 分量 `ImagePlacement image`，5→3 参便捷构造）。
- `TextLayout.wrap`：`image != null` ⇒ 产出 `rows` 个空 `RenderRow`，仅首行携带载荷；`displayWidth` 视为 0 宽（不参与换行）。

**`ChatViewportElement`**

- `flatten` 已按行收集 `RenderRow`；`renderContent` 在文本 `setLine` 循环后，对落在视口内的首个 image 行：
  `frame.renderWidget(new ImageElement(payload), new Rect(area.x()+indent, area.y()+row-offset, columns, rows))`。
- 与搜索/选区/滚动共存：image 行文本为空，高亮跳过。

**`MessageBubble`**

- `renderBlocks` 的 `ImageContent`/`UrlImageContent`：当 `imagesAvailable()`（能力非空 ∧ `showImages`）⇒ 产 image 行；否则现状 dim 占位。
- 通过 `MessageBubbleVisibility` 注入 `Supplier<Boolean> showImages` 与 `Supplier<Integer> imageWidthCells`（`PiTuiLauncher` 接 `settings.uiPrefs()`），默认 false/60。

### 4.2 R15/K14 粘贴

**`pi-java-tui/.../util/ClipboardImage.java`（≤220 行）**

- `record Image(byte[] bytes, String mimeType)`；`static Optional<Image> read()`。
- Windows：PowerShell `Add-Type System.Windows.Forms/Drawing` + `GetImage().Save(tmp,'Png')` → 读回（5s 超时）。
- Wayland：`wl-paste --list-types` → 选 png/jpeg/webp/gif → `wl-paste --type T`。
- X11：`xclip -selection clipboard -t TARGETS -o` → 选型 → `xclip … -o`。
- 任一失败/空 ⇒ `Optional.empty()`；线程中断复位。

**`ChatScreen.pasteFromClipboard()`**

- 图片：写 `java.io.tmpdir()/pi-java-clipboard-<uuid>.<ext>`，`insertText(path)`（插入点前补空格规则沿用编辑器插入）。
- 无图片：读文本（AWT Toolkit `stringFlavor`，headless/失败 ⇒ `Optional.empty()`），`insertText(text)`。
- 都无 ⇒ `showError("Failed to paste from clipboard: clipboard has no image or text")`；异常 ⇒ 同前缀 + 原因。

**键位**：`KeybindingsManager.PASTE_IMAGE = "app.clipboard.pasteImage"`，默认 `ctrl+v`/`alt+v`（对位 `keybindings.ts:75-78`）；`GlobalKeyRouter` 在扩展快捷键之后、Enter 之前处理（对位 custom-editor 顺序）。

### 4.3 K13 复制键

- `KeybindingsManager.COPY_MESSAGE = "app.message.copy"`，默认 `ctrl+x`。
- `ChatScreen.copyLastAssistantMessage(boolean preferSelection, boolean flash)`：
  - `preferSelection && !uiPrefs().getFullscreenCopyOnSelect() && viewport.hasActiveSelection()` ⇒ `viewport.copyActiveSelection()`；
  - 否则从 `messages` 反向取最后 `ChatMessage.Assistant` 的 text 块拼接；空 ⇒ `showError("No agent messages to copy yet.")`；
  - `ClipboardSupport.copy(text)` 成功 ⇒ `flash("Copied!")`（W6 已有 FlashNotice）；失败 ⇒ `showError(message)`。
- `GlobalKeyRouter` 加 `case COPY_MESSAGE`。

### 4.4 S18 首次设置向导

**`pi-java-tui/.../app/FirstRunSetup.java`（≤120 行）**

```java
static boolean shouldRun(Map<String,String> env, boolean settingsJsonExists, boolean official);
```

- 官方发行版判定：`APP_NAME == "pi-java"` 且配置目录 `.pi-java`（Java 无 fork 重命名面 ⇒ true）。
- 条件：`PI_EXPERIMENTAL=1` ∧ 无 `PI_AGENT_DIR` ∧ `settings.json` 不存在。

**`pi-java-tui/.../screen/FirstTimeSetupScreen.java`（≤200 行）**

- 对位 §2.4：两步状态机；`themeIndex`/`analyticsIndex`；`update()` 重建文本；`j/k`、↑↓、Enter、Esc；主题预览回调。

**`pi-java-tui/.../app/FirstTimeSetupRunner.java`（≤140 行）**

- `TrustPromptRunner` 同型：裸 `ToolkitRunner` 全屏；Resize 回填宽度；提交后 `SettingsManager.load(null)` 写 theme/analytics 并 flush；异常 ⇒ 跳过向导（不阻塞启动）。

**接线**：`PiTuiLauncher.runInteractive` 在 console 检查后、`new ChatScreen()` 前执行；向导写盘后 `AgentSession.create` 自然读到。

### 4.5 S21

无代码。`map/05` 改判「对齐」；窄差 D：独立导出组件（扩展面）不移植，内置消费面＝设置布尔循环项。

### 4.6 R8 LaTeX（分文件，≤500 行/文件）

- `pi-java-tui/.../util/latex/LatexSymbols.java`：`SYMBOLS`、`NEGATED_SYMBOLS`、`BLACKBOARD`、`SUPERSCRIPTS`、`SUBSCRIPTS`、`ACCENTS`、命令集合。
- `pi-java-tui/.../util/latex/LatexRenderer.java`：`renderLatex(String source, boolean display)`——词法（`\cmd`/`{}`/`^`/`_`/`&`/`\\`）→ AST（分数/根号/脚本/矩阵/重音/操作符）→ 布局渲染（多行分数、根号、矩阵居中、`\left…\right` 定界符）。
- `pi-java-tui/.../util/latex/LatexInline.java`：扫描 `$…$`/`$$…$$`（转义 `\$` 不触发），块级整行识别。
- 接入：`MarkdownRenderer` 文本 span 先过 `LatexInline.renderInline`；`MarkdownBlocks` 识别 `$$…$$` 块输出居中多行。
- 测试：移植 `latex.test.ts` 代表断言（符号、分数、上下标、根号、矩阵、`\not`、间距、定界符）。

## 5. 窄差/不可达登记（按 docs/21 §6 惯例）

| 编号 | 内容 | 处置 |
|---|---|---|
| D-W7-1 | R7 Mermaid：pi 依赖 `lovely-mermaid` JS 包（布局/主题/警告）；Java 无等价库，手写无法保证逐字输出 | **不进分子**；围栏继续按代码块渲染 |
| N-W7-1 | 图片实机渲染依赖真实终端（Kitty/iTerm2）；自动化测试只覆盖编码/尺寸/降级/行装配 | 测试面说明，不改判定 |
| N-W7-2 | 剪贴板图片来源仅 Windows/Linux 命令链；macOS Finder 文件路径读取不移植（无 Java 原生面） | 文本/图片兜底保留 |
| N-W7-3 | 首次向导主题预览只重着色启动壳，不重查终端 system 主题色（Java 无启动期 OSC 查询面） | 行为等价可观察面：选择生效、取消不写 |
| D-W7-2 | S21 独立 `ShowImagesSelectorComponent` 不移植（仅扩展导出；无内置消费者） | 设置布尔项已对位 |

## 6. 验收与回填

- 测试：`./mvnw test -pl pi-java-tui -am`（新增 `TerminalImageTest`、`ClipboardImageTest`、`FirstRunSetupTest`、`FirstTimeSetupScreenTest`、`LatexRendererTest`、`ImageRowsTest`）；`KeybindingsManagerTest` 补两键。
- 回填：`map/05` 九行＋§加权汇总＋复核修正 W7 条；`docs/05` §1/§1.1/§1.2；`docs/09` L7 行/段/「下一分段」改 W8；`docs/04` §0 追加 W7。
- 改动留工作区。

## 7. 实施结果（2026-11-24）

| 项 | 结果 | 证据 |
|---|---|---|
| K13 | **落地** | `KeybindingsManager.MESSAGE_COPY` 进全局表；`GlobalKeyRouter.copyLastAssistantMessage`（选区优先/copy-on-select 门控/最后助手消息/逐字文案/`flash("Copied!")`）；`ChatScreen.flash` 委托 |
| K14/R15 | **落地** | `KeybindingsManager.PASTE_IMAGE`（ctrl+v/alt+v）；`ClipboardImage`（Windows/Wayland/X11，白名单/mime 选择/超时）；`ClipboardPasteFlow`（临时 png→路径、文本兜底、`Failed to paste from clipboard: …`）；`GlobalKeyRouter` 臂 |
| S18 | **落地** | `FirstRunSetup`（四条件纯函数＋生产判定）、`FirstTimeSetupScreen`（两步/文案/`j/k`/Esc）、`FirstTimeSetupRunner`（TrustPromptRunner 同型，写 theme/analytics）；`PiTuiLauncher.runInteractive` 接线 |
| S21 | **改判对齐** | Java 设置布尔项已对位；D-W7-2 登记 |
| R14/S2d | **落地** | `util/TerminalImage`（Kitty 分块/iTerm2/删除/图片行/PNG-JPEG-GIF-WebP 尺寸/单元格/OSC 8 降级）、`ImagePlacement`、`ImageElement`（Widget＋RawOutputCapable）、`ImageRenderSupport`（视口裁剪）、`LogicalLine`/`RenderRow` 载荷（旧构造保留）、`TextLayout.wrap` 图片行展开、`MessageBubble` 双门控、`PiTuiApp` 注入 `showImages/imageWidthCells` |
| R8 | **落地（核心子集）** | `util/latex/LatexSymbols`（符号/上下标/重音/集合）、`LatexRenderer`（解析/分数/根号/脚本/矩阵/文本包装）、`LatexLayout`（基线布局）、`LatexInline`（`$…$`/`$$…$$`）；`MarkdownRenderer` 段落/标题/列表/引用/表格接入 |
| R7 | **不可达登记** | D-W7-1；围栏维持代码块 |
| 测试 | **全绿** | `./mvnw test -pl pi-java-tui`：**898 通过 / 0 失败 / 1 跳过**；新增/覆盖 `TerminalImageTest`(10)、`LatexRendererTest`(12)、`ImageRowsTest`(3)、`ClipboardPasteFlowTest`(4)、`ClipboardImageTest`(2)、`FirstRunSetupTest`(2)、`FirstTimeSetupScreenTest`(4) |
| 读数 | **回填** | `map/05` **230.5/252＝91.5%**（未加权 131/153）；投影 **234.0/260＝90.0%** |
| 遗留 | 登记 | N-W7-1/2/3/4、D-W7-1/2；`TerminalImage.sequence` 依赖真实终端；`LatexRenderer` 未知命令整式回落原文（pi 同） |
