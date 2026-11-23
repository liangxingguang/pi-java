# 25 - L7 W4 任务清单（对应 `docs/25-w4-design.md`）

> 执行约定：每项完成即跑定向测试；全部完成后回填地图与总账，改动留工作区不 commit。
> **状态：全部完成（2026-11-23 收口，784 测全绿）。**

## T1 S16 query 门控
- [x] `ConfigSelectorComponent.onKeyEvent`：仅当 `searchEditor.getText()` 变化才 `model.filterWith(...)`
- [x] 同族核查 `ThinkingLevelScreen`/`ScopedModelsScreen`/`SettingsScreen` 子菜单；无同形缺陷（键处理不无条件重过滤），设计稿 §9 记录
- [x] 测试：←/→ 选中保持；字符输入重过滤；空格/Enter toggle 不回归

## T2 EditorComponent 扩展（K26＋K31＋补全底座）
- [x] 新增 `lineBeforeCursor()`、`replaceBeforeCursor(int graphemes, String text)`
- [x] K26：`pastes`/`pasteCounter`、`normalizeText`、`insertText` 折叠阈值（>10 行 / >1000 字符）、标记正则
- [x] K26：`Snapshot` 携 `pastes`＋`pasteCounter`，`pushSnapshot`/`undo` 同步
- [x] K26：`submit` 展开标记并清空注册表；`setText`/`replaceText`/`clear` 清空；`getExpandedText()`
- [x] K26：退格/前向删整标记（删注册表、高位下移、重编号、光标重定位）
- [x] K31：`jumpMode` 等待态（最前置）、`ctrl+]`/`ctrl+alt+]`、`jumpToChar` 多行搜索
- [x] 测试：`EditorComponentPasteTest`、`EditorComponentJumpTest`

## T3 FileCompleter（K25）
- [x] 新增 `FileCompleter`＋`FileCompletionPaths`（前缀提取/打分/目录列举/有界递归/引号形/渲染）
- [x] 测试：`FileCompleterTest`（列列举、打分排序、引号、目录尾斜杠、CJK 分隔、Tab 强制）

## T4 接线（K41＋K25＋Tab）
- [x] `ChatInputPortal`：持有 `FileCompleter`，键序 slash→file→editor，合并 `active/lineCount/render`
- [x] `ChatInputPortal.enterWithCompletion()`＋`applyFileCompletion()`
- [x] `ChatScreen.enterWithCompletion()` 转发；`GlobalKeyRouter` Enter 用它决定是否 `slashFlow.submit()`
- [x] `GlobalKeyRouter` Tab 分支无条件转 `chatScreen.onKeyEvent(event)`
- [x] 测试：Enter 在文件补全时不提交、slash 名称时提交；Tab 强制文件补全

## T5 extension-selector 窗口化
- [x] `ExtensionSelectorOverlay.layout()`：10 行居中窗口＋越界 `  (i/n)`
- [x] 测试：12 项选中第 11 项窗口起点与计数

## T6 C2 复核
- [x] `SelectList` ↑↓ wrap；PgUp/PgDn 保持 clamp
- [x] `render()` 无匹配文案＋窗口 `(i/n)`
- [x] 设计稿 §9 记「多选/分组非 pi 能力；描述列窄差 N」
- [x] 测试：wrap/clamp/空表/计数

## T7 收口
- [x] `$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿（784 测）
- [x] `map/05`：新增 K41 行；K25/K26/K31 缺失→对齐；S16 存疑→对齐；C2 改判＋注记；§加权汇总回算 **205.5/252＝81.5%**
- [x] `docs/05` §1/§1.1/§1.2；`docs/09` L7 行/段/§4；`docs/04` §0
- [x] `docs/25-w4-design.md` 追加「实施结果」
