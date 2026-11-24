# 28-W7 任务清单（实施顺序）

> 配套 `docs/28-w7-design.md`。每项完成即跑定向测试；全部完成跑 `./mvnw test -pl pi-java-tui -am`。

## 阶段 A：小件键位/粘贴（先做，风险低）

- [ ] A1 `KeybindingsManager`：新增 `COPY_MESSAGE="app.message.copy"`（ctrl+x）与 `PASTE_IMAGE="app.clipboard.pasteImage"`（ctrl+v/alt+v），默认表与描述对位 `keybindings.ts:63-78`；`KeybindingsManagerTest` 断言两键。
- [ ] A2 `ChatScreen.copyLastAssistantMessage(preferSelection, flash)`：选区优先（`fullscreenCopyOnSelect` 关时）→ 最后 assistant 文本 → `ClipboardSupport.copy` → `flash("Copied!")`；空/失败文案逐字。
- [ ] A3 `GlobalKeyRouter`：`case COPY_MESSAGE` 接线。
- [ ] A4 `util/ClipboardImage`：Windows PowerShell / Wayland wl-paste / X11 xclip；MIME 白名单与扩展名；5s/1s 超时；失败空。
- [ ] A5 `ChatScreen.pasteFromClipboard()`：图片⇒临时文件路径插编辑器；无图⇒文本；全无/异常⇒`Failed to paste from clipboard: …`。
- [ ] A6 `GlobalKeyRouter`：`case PASTE_IMAGE` 接线（扩展快捷键后、Enter 前）。
- [ ] A7 测试：`ClipboardImageTest`（mime 选择/扩展名/命令失败空）、ChatScreen 复制/粘贴单测（注入假剪贴板）。

## 阶段 B：S18 首次设置向导

- [ ] B1 `app/FirstRunSetup.shouldRun(env, settingsExists, official)`＋单测（四条件组合）。
- [ ] B2 `screen/FirstTimeSetupScreen`：两步（theme→analytics）、LOGO/文案逐字、`j/k`/↑↓/Enter/Esc、主题预览回调；单测键盘与结果。
- [ ] B3 `app/FirstTimeSetupRunner`：TrustPromptRunner 同型启动 TUI；提交写 `SettingsManager.load(null)` 的 theme/analytics＋flush；取消不写；异常跳过。
- [ ] B4 `PiTuiLauncher.runInteractive` 接线（console 检查后、`new ChatScreen()` 前）。

## 阶段 C：S21 判定（无代码）

- [ ] C1 `map/05` S21 改「对齐」＋窄差 D-W7-2；`docs/21` §6 登记。

## 阶段 D：R14/S2d 图片渲染

- [ ] D1 `util/TerminalImage`：encodeKitty/encodeITerm2/delete、`isImageLine`/`stripImageSequences`、PNG/JPEG/GIF/WebP 尺寸、`cellSize`（含 Kitty 失真优化）、`fallback`（OSC 8）。
- [ ] D2 `component/ImagePlacement`＋`ImageElement`（Widget＋RawOutputCapable，`CSI` 绝对定位）。
- [ ] D3 `LogicalLine`/`RenderRow` 载荷扩展（保留旧构造）＋`TextLayout.wrap` 图片行展开（`rows` 空行，首行载荷）。
- [ ] D4 `MessageBubble` 图片块接线（能力∧showImages；否则占位）＋`MessageBubbleVisibility` 注入 `showImages`/`imageWidthCells`。
- [ ] D5 `ChatViewportElement.renderContent` 渲染 image 区（`frame.renderWidget`）；滚动/搜索/选区共存。
- [ ] D6 `PiTuiLauncher` 注入设置源；全屏退出清理由 TamboUI 承担（复核 `cleanupRawOutput` 行为）。
- [ ] D7 测试：`TerminalImageTest`（分块/尺寸/单元格/降级）、`ImageRowsTest`（wrap 行数/首行载荷）。

## 阶段 E：R8 LaTeX

- [ ] E1 `util/latex/LatexSymbols`（符号/集合/映射）。
- [ ] E2 `util/latex/LatexRenderer`（词法→AST→布局）。
- [ ] E3 `util/latex/LatexInline`（`$…$`/`$$…$$` 扫描，`\$` 转义）。
- [ ] E4 `MarkdownRenderer`/`MarkdownBlocks` 接入（行内/块级；pending 不算）。
- [ ] E5 `LatexRendererTest`：移植上游代表断言（≥30）。

## 阶段 F：收口

- [x] F1 `./mvnw test -pl pi-java-tui` 全绿（898 通过 / 0 失败 / 1 跳过）；Checkstyle 0 error。
- [x] F2 回填 `map/05`（S2d/R14/R15/K14/S18/S21/K13/R7/R8＋加权汇总＋W7 复核条）。
- [x] F3 回填 `docs/05` §1/§1.1/§1.2、`docs/09` L7 段＋下一分段 W8、`docs/04` §0。
- [x] F4 `docs/28-w7-design.md` §7「实施结果」已追加。未 commit。

> 状态：A1–F4 全部完成（2026-11-24）。R7 按 D-W7-1 登记；R8 为核心子集（N-W7-4）。

## 失败/回退口径

- D 阶段若实机 raw 输出破坏缓冲区：`ImageElement` 保留纯编码/降级路径，`ChatViewportElement` 退回占位行，R14/S2d 记窄差（不得留半接线）。
- E 阶段若布局移植超期：先落符号/行内/分数/脚本/根号（覆盖高频），矩阵/`\left…\right` 记 N 窄差。
