# docs/62 — L7 W2：选择器整页翻页＋模型别名＋补全抢占视口键

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/59` §3 W2（覆盖 map/05 的 **C2、S5、S7、S20、K24**）、`docs/map/05-tui-web.md`、docs/57（L8 W5 UI 槽位）、docs/58（L8 W6 交互）
- 文档状态：**2026-11-20 用户审核通过并实施**（§8 实施记录）
- 编号：`docs/59`＝L7 总设计、`docs/60`＝W1b 内置渲染器、`docs/61`＝B161；本稿 **docs/62**。

## 0. 范围与现状

| map/05 行 | pi 面 | Java 现状 | 本包要补 |
|---|---|---|---|
| C2 | `tui/components/select-list.ts`：PgUp/PgDn 整页 + 居中窗口（页大小＝`maxVisible`） | `SelectList.java` 只有 Up/Down/Enter/Esc/过滤，**无翻页、无窗口** | `maxVisible`＋`pageUp/pageDown`＋`visibleRange()` |
| S5 | 模型选择器：翻页（`MAX_VISIBLE=10`）＋别名 `(name)` muted | `ModelSelectorScreen` 用 `SelectList<String>`，无翻页、无别名 | 随 C2 拿翻页；`(name)` 进标签 |
| S7 | scoped-models：翻页（`maxVisible=8`）＋别名 | 自绘列表，`MAX_VISIBLE=8`，键循环无翻页 | 加分页分支＋`(name)` |
| S20 | 用户消息选择器：翻页（`maxVisible=10`） | `UserMessageSelectorScreen` 自绘，无翻页 | 加分页分支 |
| K24 | 补全弹层打开时**抢占** PgUp/PgDn（`capturesViewportInput`），关闭时还给视口滚动 | `SlashCompleter` 只处理 Up/Down/Tab/Esc；`GlobalKeyRouter` 按 `isInputEmpty()` 硬编码分流 | 补全打开时截走 PgUp/PgDn；`GlobalKeyRouter` 门加补全条件 |

另含设置页（`SettingsListModel`＋`SettingsScreen`）、设置子菜单（`ThemeSubmenuScreen` 等 `SelectList` 用法）的接线；会话选择器与树选择器**已有**翻页，本包只补 clamp 语义的断言（§3.5）。

## 1. pi 侧事实（本锚点）

1. **翻页判定集中在一个纯函数**：`tui/src/keybindings.ts:327-339` `getSelectPageIndex(kb, data, selectedIndex, itemCount, pageSize)`——`pageUp ⇒ -1`、`pageDown ⇒ +1`，返回 `max(0, min(n-1, i + dir*pageSize))`。**clamp，不 wrap**（与 ↑/↓ 的 wrap 相反）。
2. **无位移不发事件**：`select-list.ts:161` `if (len === 0 || pageIndex === selectedIndex) return;`；否则 `selectedIndex = pageIndex; notifySelectionChange()`（`:162-163`）。
3. **页大小＝`maxVisible`**：`select-list.ts:46` 默认 5；`settings-list.ts` 同构（`:234/:241-243`）；模型 10（`model-selector.ts:38`）、scoped 8（`:119`）、oauth 8（`:56`）、用户消息 10（`:28`）、设置子菜单 `min(项数,10)`（`settings-submenu.ts:95`）。
4. **居中窗口**：`select-list.ts:181-188` `start = clamp(selected - floor(maxVisible/2), 0, len - maxVisible)`。
5. **树/会话/配置是自有实现**：树页大小 `max(5, floor(terminalHeight/2))`（`tree-selector.ts:1360`）、PgUp/PgDn 与光标左右共用分支（`:1016-1021`）；会话 `maxVisible=10` 自有 clamp（`session-selector.ts:611-617`）；config-selector 翻页还要**跳过分组标题行**（`:458-476`）。
6. **补全抢占**：`editor.ts:307-310` 动态 getter `capturesViewportInput = autocompleteState !== null`；按键门 `editor.ts:762-777`——菜单开着时 Up/Down/PgUp/PgDn 全喂内部 SelectList；`coding-agent/custom-editor.ts:94-102` 再前置一道（键被 app 绑定时菜单打开仍优先翻页）；菜单关闭时键落到 `tui-alt-screen.ts:762-774` 的 `scrollBy(±max(1, h-4))`，`PAGE_SCROLL_OVERLAP=4`（`:76`）。
7. **别名去重**（`modes/interactive/model-search.ts:19-34`）：`getDistinctModelName({id,name})`——name 按 `splitModelLabel` 分词（`\p{L}\p{N}`，小写）；`idTokens = new Set(idWords)`、`compactId = idWords.join("")`；`isCovered(w) = idTokens.has(w) || (w.length > 1 && compactId.includes(w))`；**每个词都被覆盖 ⇒ 返回 undefined（不显示）**，否则返回原 name。锚点用例：`claude-sonnet-4-5`／`Claude Sonnet 4.5` ⇒ 无；`eu.anthropic.claude-sonnet-4-5-20250929-v1:0`／`Claude Sonnet 4.5 (EU)` ⇒ 无；`mistral-small-2603`／`Mistral Small 4` ⇒ 显示。
8. **文案**：行 = `→ ✓<id>` + `(name)`（muted）+ ` [provider]`（muted）+ ` · default`（`model-selector.ts:326-334`）；scoped 同模板（`scoped-models-selector.ts:257-261`）。**翻页没有 keyHint 文案**（pi 自己也没有），不要新增。

## 2. Java 现状（关键事实）

- `SelectList.java:42-79` 的 `onKeyEvent`：Up/Down（clamp）、Enter、Backspace、Esc、可打印过滤；`:120/:125` 只有 `visibleItems()/selectedIndex()` 探针；`render()` `:153-164` 把整份可见项交给 `TamboUIAdapter.list(...)`，**没有窗口裁剪**。
- `SettingsListModel`：`move(int)` 环绕（`:31`）、`visibleItems()`（`:124`）、`visibleStart()`（`:130`）、`scrollPosition()`（`:135`）；键循环 `SettingsScreen.java:57-93`，**无翻页**。
- `ScopedModelsScreen.java:68-138` 自绘键循环（Up/Down 走 `KeybindingsManager.SELECT_UP/DOWN`），`moveSelection` 环绕，窗口在渲染段自算；**无翻页**。
- `UserMessageSelectorScreen.java:86-112` 自绘，无翻页；`MAX_VISIBLE=10` 只用于渲染。
- `SessionSelectorComponent.java:203-218` 已有 `event.isPageUp()/isPageDown()` → `SessionListModel.pageUp/pageDown`（±`MAX_VISIBLE=10`，`:175/:180`）；`TreeSelectorOverlay.java:123-126` 已有同类（±`maxVisibleLines`，`TreeListModel:256-266`）。**这两处已对齐**。
- 补全：`ChatInputPortal.java:33-47` 先给 `completer.onKeyEvent`（HANDLED 编辑器收不到键）；`SlashCompleter.java:107-129` 处理 Up/Down/Tab/Esc，`:132-149` 渲染只取前 `maxVisible`（选中项超出时高亮不可见）。宿主分流在 `GlobalKeyRouter.java:125-195`：PgUp/PgDn 走 `isAltScreenScroll(:170-177)` / `isInlinePageScroll(:180-183，条件=chatScreen.isInputEmpty())` → `scrollViewport`。
- 模型标签：`ModelSelectorScreen.java:30` 仅拼 `provider/modelName`（`SelectList<String>`）；`ScopedModelsScreen.java:276-281` 行＝`(→) ✓ modelName [provider]`，`displayName` 只在详情行。`ModelInfo` 无 alias 字段（`ai/catalog/ModelInfo.java:44-60`）；`ModelPatterns.isAlias`（`:37`）是解析用，**不是**显示别名。
- 键位：`KeybindingsManager.SELECT_PAGE_UP/DOWN`（`:83-84/:193-194`，默认 `pageup/pagedown`）已存在，但屏侧普遍直判 `KeyEvent.isPageUp()`；`TamboUIAdapter.java:242-257` 已把 `KeyCode.PAGE_UP` 映成 `pageup`。

## 3. 设计

### 3.1 `SelectList`：翻页 + 居中窗口（C2 本体）

```java
public SelectList(List<T> items, Function<T,String> label, int maxVisible)  // 新增重载；旧构造 ⇒ DEFAULT_MAX_VISIBLE = 5
```
- `static final int DEFAULT_MAX_VISIBLE = 5`（pi `select-list.ts:46`）。
- `boolean page(int direction)`：`pageIndex = clamp(selected + direction*maxVisible, 0, visible.size()-1)`；**`visible.isEmpty() || pageIndex == selected` ⇒ 返回 false**（pi `:161` 的"无位移不发事件"）；否则改选中并返回 true。
- 键分支：`onKeyEvent` 中 `event.isPageUp()` / `isPageDown()` → `page(∓1)`（与 Up/Down 同一优先级），**不 wrap**。
- `List<T> visibleRange()`：`start = clamp(selected - maxVisible/2, 0, max(0, visible.size()-maxVisible))`（pi `:181-188`）；`render()` 改用它，保证"选中项永远在窗口内"。
- 语义边界：窗口只影响渲染，`visibleItems()`/`selectedIndex()` 语义不变（现有测试面不破）。

### 3.2 各屏接线

| 屏 | 页大小 | 改动 |
|---|---|---|
| `ModelSelectorScreen` | 10（pi `:38`） | `new SelectList<>(items, label, 10)`；标签加 `(name)`（§3.3） |
| `LoginProviderScreen` | 8（oauth） | `new SelectList<>(..., 8)` |
| `ThemeSubmenuScreen` / `WarningSettingsScreen` / `ModelThinkingLevelScreen` | `min(项数,10)`（settings-submenu） | 构造处传 `Math.min(size, 10)` |
| `SettingsScreen`＋`SettingsListModel` | `maxVisible`（现 10） | `SettingsListModel.page(int dir)`（clamp，无位移返回 false）；`SettingsScreen` 键循环加 PgUp/PgDn 分支 |
| `ScopedModelsScreen` | `MAX_VISIBLE=8` | 键循环加 PgUp/PgDn → `moveSelection(±MAX_VISIBLE)` 的 clamp 版（**新增 `pageSelection`，不要复用 wrap 的 `moveSelection`**） |
| `UserMessageSelectorScreen` | `MAX_VISIBLE=10` | 同上：新增 clamp 分页方法（现 Up/Down 是 wrap，翻页必须 clamp） |
| `SessionSelectorComponent` / `TreeSelectorOverlay` | 已有 | 不改；只补 clamp 断言（§5） |
| `ConfigSelectorComponent` | 自有 | **复核项**：若 Java 有分组标题行且翻页会停在标题上，补"跳到最近可选项"；否则只补断言 |

### 3.3 模型别名 `(name)`

新增纯函数 `com.pijava.coding.agent.core.ModelLabels`：

```java
public static String distinctName(String id, String name);   // null ⇒ 不显示
static List<String> splitLabel(String value);                // 小写 + Character.isLetterOrDigit 切分
```

- 逐字对位 `getDistinctModelName`：`idWords`/`idTokens`/`compactId`、`isCovered = tokens.contains(w) || (w.length() > 1 && compactId.contains(w))`、全词覆盖 ⇒ `null`。
- **分词不用正则**：Java 侧用 `Character.isLetterOrDigit` 逐字符切（`\p{L}\p{N}` 的等价实现），保证 CJK/重音模型名与 pi 同判（`pi` 用 Unicode 属性类）。
- 消费点：
  - `ModelSelectorScreen`：标签 = `provider/modelName` + `distinctName(...) == null ? "" : " (" + name + ")"`；
  - `ScopedModelsScreen.java:276-281`：行文本末尾追加同名片段。
- **muted 降级**：`SelectList` 标签是 `String`，无分段样式 ⇒ `(name)` 与 `[provider]` 同色（D264）。

### 3.4 补全弹层抢占视口键（K24）

- `SlashCompleter`：`onKeyEvent` 增 `event.isPageUp()/isPageDown()` 分支——按 `maxVisible` clamp 移动 `selected` 并 **返回 HANDLED**；`render` 改成以选中项居中的窗口（`selected` 永在窗口内，修掉"选中项超出行数不可见"）。
- `GlobalKeyRouter`：新增 `completerTakesPageKeys(event) = chatScreen.completerActive() && isInlinePageScroll(event)`，并在**两条**视口滚分支上取反——
  1. alt-screen 分支（`:157`，全屏下 PgUp/PgDn 恒滚视口）：补全打开时按键归弹层；
  2. inline 分支（`:161`，条件 `isInputEmpty()`）：同样取反，防御"输入判空但弹层仍开"的时序。

  理由：pi 的 `capturesViewportInput`（`editor.ts:307-310`）在两种屏幕模式下都成立；只改 inline 会漏掉全屏模式（这是实施时对 §3.4 的修正）。
- 键位来源沿用现状（`KeyEvent.isPageUp()`）；不新增 `keyHint` 文案（pi 亦无，§1.8）。

### 3.5 已对齐项（不改语义）

会话选择器（±10）与树选择器（±maxVisibleLines）已是 clamp 语义，且页大小与 pi 同源；本包只补断言，防止后续重构把 clamp 写成 wrap。

## 4. 窄差登记（新增 D264–D266）

| 编号 | 内容 |
|---|---|
| **D264** | 列表行的分段配色无对位：pi 用 `theme.fg("muted", …)` 给 `(name)`／`[provider]` 单独上色，Java `SelectList` 标签是单个 `String`，整行同色。文案逐字，颜色不移植（D92 同族）。 |
| **D265** | `capturesViewportInput` 没有通用组件级契约：Java 只有「补全菜单」这一处显式门（`GlobalKeyRouter` 的补全条件），其它 overlay（扩展自定义组件等）的视口键语义沿用 D99/D256，不新增框架层拦截抽象。 |
| **D266** | 分页单位是「项」不是「可视行」：pi 自身如此（`user-message-selector` 每项 3 行但 `maxVisible=10`），Java 同形不修；页大小也不做终端高度换算（树/配置各自按高度算的那两处维持现状）。 |

## 5. 测试计划

1. `SelectListTest`：12 项/`maxVisible=5`，PgDn×3 ⇒ 5→10→11（钳位），再一次 PgUp ⇒ 6（pi `tui/test/select-list.test.ts:117-130` 同款）；空表与「无位移」不发选中变更回调；`visibleRange()` 居中（选中 11 时窗口含 11）。
2. `SettingsListModelTest` / `SettingsScreenTest`：`page(±1)` 钳位；PgDn 屏上落到第 11 项；顶部 PgUp 不动。
3. `ScopedModelsScreenTest`、`UserMessageSelectorScreenTest`、`ThemeSubmenuScreenTest`：PgDn 移动 `maxVisible` 且**不 wrap**（末尾再按不变）；与 Up/Down 的 wrap 形成对照断言。
4. `ModelSelectorScreenTest`（新建）：PgDn 步长 10；标签含 `(name)`；`ModelLabelsTest` 用 pi 三条锚点用例（含 CJK/重音各一条）。
5. `SlashCompleterTest`：菜单 8 项/`maxVisible=5`，PgDn ⇒ 选中第 6 项且窗口内可见；`GlobalKeyRouter` 同族（`PiTuiAppScrollTest`）：输入为空且补全打开时 PgDn **不**滚转录、关闭后恢复滚动（步长 `visibleRows-4`）。
6. `SessionSelectorComponentTest` / `TreeSelectorOverlayTest`：补「末尾 PgDn 不动、顶部 PgUp 不动」的 clamp 断言。
7. 验证门：`mvn -pl pi-java-tui -am test` 全绿；收尾全仓 `mvn clean verify -Dfrontend.skip=true`（SpotBugs 0 告警、Checkstyle 新文件 0 告警）。

## 6. 顺序与风险

顺序：`SelectList` 翻页＋窗口 → 五个 `SelectList` 屏接线 → 设置页/scoped/用户消息三处自绘列表 → `ModelLabels`＋两处标签 → 补全抢占 → 断言补齐。

风险：
1. `SelectList.render` 改窗口化会影响所有用它的屏的渲染断言（`SettingsFlowTest`、`ThemeSubmenuScreenTest` 等）；先把 `maxVisible` 默认设 5，再逐屏确认没有"项数 > 5 但断言全量行"的用例。
2. 三处自绘列表的 Up/Down 是 **wrap**，翻页必须 clamp——两套语义写在同一个 switch 里容易写错，测试要用"末尾再 PgDn 不动"钉住。
3. `GlobalKeyRouter` 是全局分流，改条件会影响所有视口滚动测试（`PiTuiAppScrollTest`、`PiTuiAppInputInteractionTest`）。
4. 别名分词的 Unicode 语义若用 `[^a-zA-Z0-9]` 会与 pi 判分叉，测试必须含 CJK 用例。

## 7. 回填

实施后：`docs/04` 追加 D264–D266；`docs/map/05` 的 C2 维持**存疑**（翻页补齐，但分组渲染/多选仍无对位）、S5/S7/S20/K24 补"整页翻页＋别名"证据；`docs/06` L7 行 W2 标注；`docs/05` 总表待 L7 闭环整表替换。

## 8. 实施记录（2026-11-20）

**代码**

| 文件 | 改动 |
|---|---|
| `tui/.../component/SelectList.java` | `DEFAULT_MAX_VISIBLE=5`、`maxVisible` 重载、`page(±1)`（clamp、零位移不报）、`visibleRange()`／`windowSelectedIndex()` 居中窗口，`render()` 改用窗口 |
| `tui/.../screen/ModelSelectorScreen.java` | 改持 `ModelRow`（id＋displayName），`maxVisible=10`，行＝`provider/model`＋去重别名；`label(...)` 提为包内函数供测试 |
| `tui/.../screen/ScopedModelsScreen.java` | PgUp/PgDn→`pageSelection`（步长 8、clamp）、`nameText()` 追加 `(name)`、`selectedIndex()` 探针 |
| `tui/.../screen/LoginProviderScreen.java` | `maxVisible=8` |
| `tui/.../screen/ThemeSubmenuScreen.java`、`ModelThinkingLevelScreen.java` | 页大小 `min(项数,10)` |
| `tui/.../screen/SettingsScreen.java`＋`settings/SettingsListModel.java` | `page(int)`（步长＝`maxVisible`、clamp）＋ PgUp/PgDn 分支；`selectedItemId()` 探针 |
| `tui/.../screen/UserMessageSelectorScreen.java` | PgUp/PgDn→`page`（步长 10、clamp，与 ↑/↓ 的环绕区分） |
| `tui/.../component/SlashCompleter.java` | PgUp/PgDn→`page`（步长＝`maxVisible`）＋ `windowStart()` 居中窗口（修掉"选中项跑出可视区") |
| `tui/.../app/GlobalKeyRouter.java` | `completerTakesPageKeys`：alt-screen 与 inline 两条滚分支都让位给弹层 |
| `coding-agent/.../core/ModelLabels.java`（新） | `distinctName`（词覆盖判定）＋ `splitLabel`（`Character.isLetterOrDigit` 逐码点） |

**测试**：`pi-java-tui` **665 测 0 失败 1 skip**（新增 12 项：`SelectListTest` 3、`SettingsListModelTest` 1、`SettingsScreenTest` 1、`ScopedModelsScreenTest` 1、`UserMessageSelectorScreenTest` 1、`SlashCompleterTest` 2、`ModelSelectorScreenTest` 2（新类）、`PiTuiAppScrollTest` 1）；`pi-java-coding-agent` 全绿（新增 `ModelLabelsTest` 4 项，含 CJK/重音用例）。

**偏离/注意**：
1. §3.4 原写只改 inline 分支；实施时发现全屏模式恒滚视口会把 PgUp/PgDn 抢走，故两分支同门（更贴 pi）。
2. 既有 653 项 TUI 测试在 `SelectList` 默认窗口化为 5 后**零改动通过**——没有"项数 > 5 且断言全量行"的用例。
3. `SettingsScreen`／`ScopedModelsScreen` 各加了一个只读探针（`selectedItemId()`／`selectedIndex()`），沿用 `UserMessageSelectorScreen.selectedIndex()` 的既有手法。
4. `ConfigSelectorComponent` 复核：Java 侧无分组标题行（`filteredItems()` 平铺），pi 的"翻页跳过非 item 行"无对应物，不登记窄差。
5. 收尾全仓 `mvn clean verify` 仍属 L7 闭环门，本包只跑到 `pi-java-tui -am test` 全绿。
