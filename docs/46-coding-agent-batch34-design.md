# docs/46 — 批 34 设计稿：TUI 交互主路（thinking 三键／工具输出展开／提示历史／thinking 选择器／视口滚动键重裁）

## §0 背景与范围裁决

【背景摘要】批 33 闭环后按 `docs/06` §3 序 27 进入 TUI 队尾批组。该批组原注（4–6 批）列了屏域 S2a–d/S6–S8/S15–S19/S21/S24＋R7/R8＋长尾 B38/B39/B46/B47/B66/B78/B145/B238。本稿是**首批**，只收「已有现成基座、改动收敛在 tui 模块」的一条主路：三个死键＋提示历史＋选择器补齐＋滚动键重裁。TUI 账面 `map/05` 当前 **137.5/244 ≈ 56.4%**，是本批唯一翻动的域表。

本稿术语：

- **死键（dead key）**：`KeybindingsManager` 已声明默认键、`resolve` 能命中，但 `GlobalKeyRouter.handleAction` 落 `default` 分支不消费（台账 B66）。
- **全局展开态**：pi `toolOutputExpanded`（`interactive-mode.ts:4561`），一个布尔驱动所有可展开组件。
- **提示历史**：编辑器 ↑/↓ 回填此前提交过的提示词（pi `editor.ts:347-353`）。
- **视口滚动键**：pi alt-screen 的 `pageUp/pageDown`（恒消费）、`top/bottom`（`ctrl+home`/`ctrl+end`）、`previousPrompt/nextPrompt`（`ctrl+shift+↑|ctrl+↑` / `ctrl+shift+↓|ctrl+↓`）。

### 已定范围裁决（2026-10-31 用户拍板）

用户在 AskUserQuestion 中选定 **「TUI 交互主路批（推荐）」**：K7/K8/K9 接线（B66 清零）＋K27 提示历史＋S8 thinking 选择器＋K20 滚动键重裁＋B238-① 销账。证据已足、全部有现成基座，故不再拆细批。

1. **B66 清零**：THINKING_CYCLE/TOOLS_EXPAND/THINKING_TOGGLE 三键接线。MODEL_CYCLE（B137）/EXTERNAL_EDITOR（批 12）/DEQUEUE（批 10）已于前批接线，本批一并纠 `map/05` 与台账的滞后判定。
2. **K8 含 D97**：扩展选择器内 `ctrl+o` 转发 `toggleToolOutputExpansion`（pi `extension-selector.ts:98-100`），不再「不消费」。
3. **K20 按 pi 本锚点语义重裁**：全屏下 `pageUp/pageDown` 恒滚视口（步长 `max(1, visibleRows-4)`）、`ctrl+home/ctrl+end` 顶/底、裸 `home/end` 与裸 ↑↓ 归还编辑器（↑↓ 触发 K27 历史）。B238-① 销账。
4. **S8 补完整组件**：DEFAULT 标记＋`ctrl+s` 存默认（新增 `app.thinking.save` 绑定）＋级别描述＋按描述模糊过滤；D84 销号。
5. **K27 拆独立类**：`EditorComponent` 447 行已逼近 FileLength 500 闸门，历史三元（entries/index/draft）与导航落 `EditorHistory`（新类）。
6. **RPC 平价缺口不在本批实施**：取证时发现 `cycle_thinking_level` 对不支持 thinking 的模型仍返级别（pi 返 null），登记 B259（§7.3 注）。

### 范围内

| 单元 | 权重 | 本批交付 |
|---|---:|---|
| K7 `Shift+Tab` 循环 thinking | 2 | 缺失 → 对齐（§3） |
| K9 `Ctrl+T` 显隐 thinking | 2 | 缺失 → 对齐（§4） |
| K8 `Ctrl+O` 展开/折叠工具输出 | 2 | 缺失 → 对齐（§5，含 D97） |
| K27 提示历史 ↑/↓（draft 还原、100 上限） | 3 | 缺失 → 对齐（§6） |
| S8 thinking level 选择器 | 2 | 缺失 → 对齐（§7，含 D84） |
| K20 键盘滚动键 | 2 | 存疑 → 对齐（§8，含 B238-①） |
| K6／K10 | 2／1 | 账面翻账（已接线，§9） |

### 范围外

- R12 工具执行卡（可折叠＋流式＋部分结果＋diff＋图像）整块仍缺；本批只接「全局展开态」到 `ToolCallRenderer` 的 `expanded` 形参（D197）。
- `previousPrompt`/`nextPrompt`（`ctrl+↑/↓` 跳到上/下一个提示）与 `halfPageUp/Down`、`lineUp/lineDown`（pi 默认空绑定）不接（D200）。
- 编辑器边框色随 thinking 等级（C15）、footer 刷新（`footer.invalidate()`）仍缺；键动作只落状态文案。
- 扩展 UI 的 `getToolsExpanded`/`setToolsExpanded` 全家族只补 selector 臂（pi `interactive-mode.ts:2683`）；widget/setWidget 等不扩。
- B259（RPC cycle 语义）只立项不改码。

---

## §1 现状与账面

### 本批 8 行逐行实算（map/05 当前口径 → 本批判定）

| # | 权重 | 能力 | java 现状（批 34 前） | 旧判定 | 本批判定 | 依据 |
|---|---:|---|---|---|---|---|
| K6 | 2 | `Ctrl+P` 模型循环 | `GlobalKeyRouter.java:245-248` `MODEL_CYCLE → cycleModelForward()` | 缺失 | **对齐** | 账面滞后：B137（ai 批 3b）已接，`map/05:130` 未跟 |
| K7 | 2 | `Shift+Tab` 循环 thinking | `KeybindingsManager.THINKING_CYCLE` 声明；`GlobalKeyRouter.java:263` default 死码 | 缺失 | **对齐** | 本批 §3 |
| K8 | 2 | `Ctrl+O` 展开/折叠工具输出 | 同上（`:25` 声明 → 死码）；`MessageBubble.java:179,195` 硬编码 `false` | 缺失 | **对齐** | 本批 §5（R12 仍缺，D197） |
| K9 | 2 | `Ctrl+T` 显隐 thinking | 同上（`:26` 声明 → 死码） | 缺失 | **对齐** | 本批 §4 |
| K10 | 1 | `Ctrl+G` 外部编辑器 | `GlobalKeyRouter.java:250` `EXTERNAL_EDITOR → openExternalEditor()` | 缺失 | **对齐** | 账面滞后：批 12 T3 已接，`map/05:134` 未跟 |
| K20 | 2 | 键盘滚动 | `GlobalKeyRouter.java:161-182`：裸 `HOME/END` 直接滚视口；PgUp/PgDn 仅空编辑器；无 `ctrl+home/end` | 存疑 | **对齐** | 本批 §8（D195/D200） |
| K27 | 3 | 提示历史 ↑/↓ | `EditorComponent.java:171-172` `moveCursorUp/Down`；无历史字段 | 缺失 | **对齐** | 本批 §6（D199） |
| S8 | 2 | thinking level 选择器 | `ThinkingLevelScreen.java`（71 行，批 15 简版）：无 DEFAULT／无 `ctrl+s`／无描述／无独立搜索行 | 缺失 | **对齐** | 本批 §7（D196，销 D84） |

**汇总（本批后）**：`map/05` 148 单元；对齐 **48**（+6）／缺失 **76**（−6）／存疑 **23**（−1）⇒ 未加权 **48/148 ＝ 32.4%**（旧 42/148 ＝ 28.4%）。加权 Σ244：旧 137.5 → **150.5 ≈ 61.7%**（+13.0）。

### 台账立项

| 编号 | 面 | 条目 | 处置 |
|---|---|---|---|
| B66 | 用户可见 | 3 个已声明键位未接线 | **本批销号**（三键全部接线） |
| B238① | 用户可见 | K20 视口滚动键改 Ctrl+Home/Ctrl+End、裸 Home/End 归还编辑器 | **本批销号**（②③④ 留 B238 行） |
| D84 | — | persist 第二键/DEFAULT 标记随 S8 批收编 | **本批销号** |
| D97 | — | 扩展选择器内 `ctrl+o` 不消费 | **本批销号**（随 K8） |
| **B259** | 协议面 | **`cycle_thinking_level` 对不支持 thinking 的模型仍返级别**（pi `agent-session.ts` `cycleThinkingLevel()` 不支持时返 undefined ⇒ RPC payload null；java `RpcSessionPayloads.cycleThinking:120-126` 不判能力、`RpcDispatcher` 恒返级别；`get_available_thinking_levels` 也返全 7 而不按模型过滤） | **本批新登，不在本批实施**（取证发现，范围外） |

### 账面滞后项（只纠文档，`map/05`）

`map/05` 另有 5 处判定/证据已过期，本批在 §11 回填：K6/K10 判定、B66 台账纠错行「剩 4」（实为 3）、K20 窄差表「↑↓/PgUp/PgDn 两侧一致」（实为 `lineUp/lineDown` 空绑定、`ctrl+↑/↓` 是 prompt 跳转）、K27 证据行号（`:145-146` → `:171-172`）、S8 证据（「只在 SettingsScreen 循环 6 值」→ 批 15 已有 `ThinkingLevelScreen`）。

---

## §2 总体结构与任务拆分

### 模块与文件

| 模块 | 文件 | 改动 |
|---|---|---|
| `pi-java-coding-agent` | `core/KeybindingsManager.java` | 新增 `THINKING_SAVE = "app.thinking.save"`（overlay 表，`ctrl+s`） |
| `pi-java-tui` | `app/GlobalKeyRouter.java` | K7/K9/K20 键动作；`isChatNavigation`/`scrollByKey` 重裁；default 分支清空 |
| | `app/PiTuiApp.java` | 新增 `toolOutputExpanded` 字段＋`toggleToolsExpanded()`（幂等短路＋文案）＋`MessageBubbleVisibility.installToolsExpanded(...)` 注入 |
| | `component/MessageBubbleVisibility.java` | 新增 `installToolsExpanded(BooleanSupplier)`＋`static boolean toolsExpanded()`（类注释改为「投影现读设置」） |
| | `component/MessageBubble.java` | `toolUseLines`/`toolResultLines` 的 `expanded` 实参改现读 |
| | `component/EditorComponent.java` | 新增 `history` 字段与 ↑/↓ 三臂；`submit()` 前 `history.add`；删 `ctrl+home/ctrl+end` 行首行尾臂 |
| | `component/EditorHistory.java`（新） | 历史三元＋`add`/`navigate`（trim、去连续重复、100 上限、draft） |
| | `screen/ThinkingLevelScreen.java` | 完整组件：描述列／DEFAULT 标记／`ctrl+s` 存默认／自绘布局／搜索行 |
| | `component/SelectList.java` | 新增 `visibleItems()`／`selectedIndex()`（自绘列表读点） |
| | `screen/ExtensionSelectorOverlay.java` | 注入 `Runnable onToggleToolsExpanded`；`ctrl+o` 转发并消费（D97） |
| | `app/TuiExtensionUiHost.java` | 新增 `bindToolsToggle(Runnable)`＋`toggleToolsExpanded()` |
| | `app/TuiExtensionUI.java` | selector 构造多传一个 `host::toggleToolsExpanded` |
| `pi-java-tui`（测试） | 见 §9 | — |

模块分工不变；`pi-java-ai` 只读 `ModelCapability`／`ModelThinkingLevel`，无改动。

### 任务拆分

| 任务 | 内容 | 依赖 |
|---|---|---|
| **T1** | 键位与状态基座：`THINKING_SAVE` 绑定＋`MessageBubbleVisibility` 展开态缝＋`PiTuiApp.toolOutputExpanded` | 无 |
| **T2** | K7＋K9：`GlobalKeyRouter` 两个动作臂＋状态文案 | T1（K7 复用 `ThinkingCommands.availableLevels`） |
| **T3** | K8：投影传参＋`GlobalKeyRouter` 臂＋D97 扩展 selector 转发 | T1 |
| **T4** | K27：`EditorHistory` 新类＋`EditorComponent` ↑/↓ 三臂＋`submit` 记录 | 无（可与 T1 并行） |
| **T5** | S8：`SelectList` 读点＋`ThinkingLevelScreen` 完整组件＋D84 销账 | T1（`THINKING_SAVE`） |
| **T6** | K20：`GlobalKeyRouter` 视口键重裁＋PgUp/PgDn 步长＋`EditorComponent` 删 `ctrl+home/end` | 无 |
| **T7** | 测试（§9）＋全量 `mvn clean verify`＋FileLength 复核＋文档回填（§11） | T2–T6 |

实施顺序建议：T1 → T4 → T2 → T3 → T5 → T6 → T7。T4 先落是让 T6 能安全删 `EditorComponent` 的分支（↑↓ 归属在同一条分派链上）。

### 文件长度闸门

所有目标文件本批后均 <500 行（FileLength `severity=error`）：

- `GlobalKeyRouter` 300 → 约 375；`PiTuiApp` 442 → 约 465；`EditorComponent` 447 → 约 470；
- 新 `EditorHistory` 约 95；`ThinkingLevelScreen` 71 → 约 155；`MessageBubbleVisibility` 20 → 约 36；
- `SelectList` 145 → 约 157；`ExtensionSelectorOverlay` 约 180 → 约 190；`TuiExtensionUiHost` 约 135 → 约 145；
- `KeybindingsManager` 252 → 约 258；`MessageBubble` 307 → 约 309。

---

## §3 K7 `Shift+Tab` 循环 thinking level

### pi 证据

```
interactive-mode.ts:3102  this.defaultEditor.onAction("app.thinking.cycle", () => this.cycleThinkingLevel());

:4527  private cycleThinkingLevel(): void {
:4528      const newLevel = this.session.cycleThinkingLevel();
:4529      if (newLevel === undefined) {
:4530          this.showStatus("Current model does not support thinking");
:4531      } else {
:4532          this.footer.invalidate();
:4533          this.updateEditorBorderColor();
:4534          this.showStatus(`Thinking level: ${newLevel}`);
:4535      }
:4536  }

agent-session.ts  cycleThinkingLevel(options): ThinkingLevel | undefined {
    if (!this.supportsThinking()) return undefined;
    const levels = this.getAvailableThinkingLevels();
    const currentIndex = levels.indexOf(this.thinkingLevel);
    const next = levels[(currentIndex + 1) % levels.length];
    this.setThinkingLevel(next, options);
    return next;
}
```

`supportsThinking()` ＝ `!!model?.reasoning`。

### Java 现状与差距

- `THINKING_CYCLE`（`app.thinking.cycle`，`shift+tab`）已在 `KeybindingsManager:24` 声明；`GlobalKeyRouter.java:263` 的 `default -> { /* thinking.* / tools.expand → Phase 6 */ }` 不消费。
- `Tab` 分支（`GlobalKeyRouter.java:146`）只吃 `!event.hasShift()` 的 `\t`，所以 Shift+Tab 会落 `keys.resolve` ⇒ 命中 `THINKING_CYCLE`。
- 可用集与当前级已有公开读点：`ThinkingCommands.availableLevels(session)`（`ThinkingCommands.java:57-65`）、`session.harness().getThinkingLevel()`（`ThinkingLevelScreen.java:40` 同源）。
- `AgentSession` 492 行，**不可再加方法**（FileLength 闸门）；cycle 逻辑落 `GlobalKeyRouter` 私有方法。

### 设计

```java
case KeybindingsManager.THINKING_CYCLE -> cycleThinkingLevel();

private void cycleThinkingLevel() {
    var model = app.session.harness().getModel();
    boolean supports = model != null && app.session.modelCatalog().find(model)
        .map(info -> info.capabilities().contains(ModelCapability.THINKING))
        .orElse(true);                       // 目录外模型：能力未知 ⇒ 支持（D200）
    if (!supports) {
        chatScreen.appendSystemText("Current model does not support thinking");
        return;
    }
    var levels = ThinkingCommands.availableLevels(app.session);
    var current = app.session.harness().getThinkingLevel();
    int index = levels.indexOf(current);      // 不在集合 ⇒ -1 ⇒ 取首项（pi 同）
    var next = levels.get((index + 1) % levels.size());
    app.session.setThinkingLevel(next);
    chatScreen.appendSystemText("Thinking level: " + next.label());
}
```

- `supports` 口径与 `availableLevels` 自洽：目录未编目 ⇒ 可用集是全 7（`ThinkingCommands.java:62-64`），故此处也放行（pi 的 `model.reasoning` 恒有定义，不会出现该分支）。
- 状态文案逐字：`Current model does not support thinking`、`Thinking level: <level>`（后者与 `/thinking <level>` 的状态行同形，`ThinkingCommands.java:45`）。
- pi 的 `footer.invalidate()`／`updateEditorBorderColor()` 在 java 无对应件（C15 边框色整块缺失、java 无 footer 区）⇒ 不接线，登记在 §10 行为变化注记。

---

## §4 K9 `Ctrl+T` 显隐 thinking 块

### pi 证据

```
interactive-mode.ts:3110  this.defaultEditor.onAction("app.thinking.toggle", () => this.toggleThinkingBlockVisibility());

:4589  private toggleThinkingBlockVisibility(): void {
:4590      this.hideThinkingBlock = !this.hideThinkingBlock;
:4591      this.settingsManager.setHideThinkingBlock(this.hideThinkingBlock);
:4592      this.updateThinkingBlockVisibility();       // 遍历 assistant 组件 setHideThinkingBlock
:4593      this.showStatus(`Thinking blocks: ${this.hideThinkingBlock ? "hidden" : "visible"}`);
:4594  }
```

### Java 现状与差距

- `THINKING_TOGGLE`（`ctrl+t`）已在 `KeybindingsManager:26` 声明 → 死码。
- 显隐的**渲染消费面已就绪**：`MessageBubbleVisibility.hiddenThinking()`（`MessageBubbleVisibility.java:18`）在 `MessageBubble.java:130` 过滤 thinking 块；`PiTuiApp.java:133-134` 已把设置项现读注入（`getHideThinkingBlock()`）。
- `ChatScreen` 每帧从 `ChatMessage` 重投影（`ChatViewportElement.java:270` 调 `MessageBubble.lines`）⇒ 写设置后下一帧自动生效，无需 pi 的 `updateThinkingBlockVisibility` 遍历。

### 设计

```java
case KeybindingsManager.THINKING_TOGGLE -> toggleThinkingVisibility();

private void toggleThinkingVisibility() {
    var accessors = app.session.services().settings().accessors();
    boolean hidden = !Boolean.TRUE.equals(accessors.getHideThinkingBlock());
    accessors.setHideThinkingBlock(hidden);
    chatScreen.appendSystemText("Thinking blocks: " + (hidden ? "hidden" : "visible"));
}
```

- 语义差：pi 用组件内 `hideThinkingBlock` 字段＋遍历刷新；java 直接反转持久设置项（`SettingsAccessors.setHideThinkingBlock` 写 global ＋ `markModified`），渲染现读。会话切换后新会话读同一设置 ⇒ 行为一致。
- `Boolean.TRUE.equals(...)` 兜底 null（缺省可见）。

---

## §5 K8 `Ctrl+O` 展开/折叠工具输出（含 D97）

### pi 证据

```
interactive-mode.ts:3109  this.defaultEditor.onAction("app.tools.expand", () => this.toggleToolOutputExpansion());
:4557  private toggleToolOutputExpansion(): void { this.setToolsExpanded(!this.toolOutputExpanded); }

:4561  private setToolsExpanded(expanded: boolean): void {
:4562      if (expanded === this.toolOutputExpanded) return;      // 幂等短路
:4563      this.toolOutputExpanded = expanded;
:4564      const activeHeader = this.customHeader ?? this.builtInHeader;
:4565      if (isExpandable(activeHeader)) activeHeader.setExpanded(expanded);
:4566      for (const container of [this.loadedResourcesContainer, this.chatContainer]) {
:4567          for (const child of container.children) {
:4568              if (isExpandable(child)) child.setExpanded(expanded);
:4569          }
:4570      }
:4571      this.showStatus(`Tool output: ${expanded ? "expanded" : "collapsed"}`);
:4572  }

// 扩展选择器（extension-selector.ts:15,28,42,98-100）
onToggleToolsExpanded?: () => void;
if (kb.matches(keyData, "app.tools.expand")) { this.onToggleToolsExpanded?.(); return; }
// 接线（interactive-mode.ts:2721）
{ tui: this.ui, timeout: opts?.timeout, onToggleToolsExpanded: () => this.toggleToolOutputExpansion() }
```

### Java 现状与差距

- `TOOLS_EXPAND`（`ctrl+o`）已在 `KeybindingsManager:25` 声明 → 死码。
- `ToolCallRenderer.renderCall/renderResult` 的第三个/第四个形参就是 `expanded`；唯一实现 `McpToolCallRenderer` 已按 `expanded` 展开（`OUTPUT_PREVIEW_LINES` 截断＋`"... (N more lines)"`）。`MessageBubble.java:179,195` 硬编码 `false`（第 171 行注释「java 无 expanded 交互缝 ⇒ 恒折叠臂（D92 含此项）」）。
- 无全局展开态字段；扩展选择器 `ExtensionSelectorOverlay.java:93` 明写 `ctrl+o` 不消费（D97）。

### 设计

#### 5.1 全局展开态

`PiTuiApp` 新增：

```java
/** pi toolOutputExpanded（interactive-mode.ts:4561）——Ctrl+O 驱动全局投影。 */
private volatile boolean toolOutputExpanded;

/** pi toggleToolOutputExpansion（:4557）。 */
void toggleToolsExpanded() { setToolsExpanded(!toolOutputExpanded); }

/** pi setToolsExpanded（:4561-4572）：幂等短路＋状态行。 */
void setToolsExpanded(boolean expanded) {
    if (expanded == toolOutputExpanded) {
        return;
    }
    toolOutputExpanded = expanded;
    chatScreen.appendSystemText("Tool output: " + (expanded ? "expanded" : "collapsed"));
}
```

构造期（`PiTuiApp.java:133` 旁）注入现读缝：

```java
com.pijava.tui.component.MessageBubbleVisibility.installToolsExpanded(
    () -> toolOutputExpanded);
```

`MessageBubbleVisibility` 增加：

```java
private static volatile BooleanSupplier toolsExpanded = () -> false;

public static void installToolsExpanded(BooleanSupplier supplier) {
    toolsExpanded = supplier == null ? () -> false : supplier;
}

static boolean toolsExpanded() { return toolsExpanded.getAsBoolean(); }
```

类注释改为「assistant 消息投影的现读设置（thinking 显隐＋工具输出展开）」。

#### 5.2 投影传参

`MessageBubble.toolUseLines` / `toolResultLines` 的 `false` 改 `MessageBubbleVisibility.toolsExpanded()`：

```java
var rendered = renderer.renderCall(..., MessageBubbleVisibility.toolsExpanded());
var rendered = renderer.renderResult(..., MessageBubbleVisibility.toolsExpanded());
```

`ChatViewportElement.java:270` 每帧重投影 ⇒ 切换后下一帧全量生效（所有历史工具卡同时展开/折叠，与 pi 遍历已挂组件一致）。内联模式 `ScrollbackTranscript.java:99` 只在消息追加时投影，已打印的历史不回溯（pi 主屏同样无法重渲已打印行）。

#### 5.3 D97：扩展选择器转发

`ExtensionSelectorOverlay` 构造新增末位 `Runnable onToggleToolsExpanded`（可 null）：

```java
if (keys.matches(KeybindingsManager.TOOLS_EXPAND, stroke)) {
    if (onToggleToolsExpanded != null) {
        onToggleToolsExpanded.run();
    }
    return true;                     // pi extension-selector.ts:100 消费
}
```

`TuiExtensionUiHost` 新增：

```java
private volatile Runnable toolsToggle = () -> { };

public void bindToolsToggle(Runnable toggle) {
    this.toolsToggle = toggle == null ? () -> { } : toggle;
}

public void toggleToolsExpanded() { toolsToggle.run(); }
```

`PiTuiApp` 在 `bindApp(...)` 处追加 `extensionHostWiring.extensionUiHost().bindToolsToggle(this::toggleToolsExpanded)`；`TuiExtensionUI.java:99` 构造 selector 时传 `host::toggleToolsExpanded`。

> ctrl+o 在扩展选择器内是渲染线程的 `onKeyEvent`，直接改 flag 即可；不必经 dispatcher（下一帧自然重排）。

---

## §6 K27 提示历史 ↑/↓

### pi 证据

```
editor.ts:347  private history: string[] = [];
:349  private historyIndex: number = -1;   // -1 未浏览，0 最近
:350  private historyDraft: EditorState | null = null;

:428  addToHistory(text: string): void {
:429      const trimmed = text.trim();
:430      if (!trimmed) return;
:431      if (this.history.length > 0 && this.history[0] === trimmed) return;   // 连续重复
:432      this.history.unshift(trimmed);
:433      if (this.history.length > 100) this.history.pop();
:434  }

:456  navigateHistory(direction: 1 | -1): void {
:457      this.lastAction = null;
:458      if (this.history.length === 0) return;
:459      const newIndex = this.historyIndex - direction;       // Up(-1) 增、Down(1) 减
:460      if (newIndex < -1 || newIndex >= this.history.length) return;
:461      if (this.historyIndex === -1 && newIndex >= 0) {
:462          this.pushUndoSnapshot();
:463          this.historyDraft = structuredClone(this.state);
:464      }
:465      this.historyIndex = newIndex;
:466      if (this.historyIndex === -1) {
:467          const draft = this.historyDraft; this.historyDraft = null;
:468          if (draft) { this.state = draft; ... } else { this.setTextInternal(""); }
:469      } else {
:470          this.setTextInternal(this.history[this.historyIndex] || "", direction === -1 ? "start" : "end");
:471      }
:472  }

:928  if (kb.matches(data, "tui.editor.cursorUp")) {
:929      if (this.isOnFirstVisualLine() &&
:930          (this.isEditorEmpty() || this.historyIndex > -1 || this.state.cursorCol === 0)) {
:931          this.navigateHistory(-1);
:932      } else if (this.isOnFirstVisualLine()) { this.moveToLineStart(); }
:933      else { this.moveCursor(-1, 0); }
:934  }
:941  if (this.historyIndex > -1 && this.isOnLastVisualLine()) { this.navigateHistory(1); }
:943  else if (this.isOnLastVisualLine()) { this.moveToLineEnd(); }
```

`setTextInternal(text, placement)`：`placement:"start"` ⇒ 光标 `(0,0)`；`"end"` ⇒ 末行行尾；滚动偏移清零。`exitHistoryBrowsing()` 在编辑/提交等 15 处调用。

### Java 现状与差距

- `EditorComponent.java:171-172`：`UP -> state.moveCursorUp()`、`DOWN -> state.moveCursorDown()`；无历史字段、`submit()`（`:478-486`）只调 handler 后清空。
- `TextAreaState` 无光标 setter ⇒ 恢复位置沿用现有 `moveCursorToStart`＋逐行 `moveCursorDown` 手法（`undo()` `:343-351` 已如此）。
- `EditorComponent` 447 行，直接加历史逻辑会逼近 500 ⇒ 拆 `EditorHistory`。

### 设计

#### 6.1 `EditorHistory`（新类，约 95 行，包 `com.pijava.tui.component`）

```java
final class EditorHistory {
    private static final int MAX = 100;
    private final List<String> entries = new ArrayList<>();     // index 0 = 最近
    private int index = -1;                                     // -1 = 未浏览
    private Snapshot draft;                                     // 首次进入时的草稿

    void add(String text) { ... pi :428-434 逐字 ... }
    int index() { return index; }
    boolean browsing() { return index >= 0; }

    /** Up(-1)/Down(1)；返回 null ⇒ 无变化，否则给出落点（text＋placement）。 */
    Entry navigate(int direction) { ... pi :456-472 ... }
    void reset() { index = -1; draft = null; }                  // exitHistoryBrowsing 对位
    record Entry(String text, boolean cursorAtStart, boolean restoreDraft) { }
}
```

- `draft` 存「文本＋光标行列」（复用 `EditorComponent.Snapshot` 语义，独立 record 存于 `EditorHistory` 内，避免跨类可见性）。
- `navigate` 返回 `null` ⇒ 调用方保持现状（`EditorComponent` 转 `moveCursorUp/Down`）。

#### 6.2 `EditorComponent` 接线（约 +25 行）

```java
private final EditorHistory history = new EditorHistory();

// onKeyEvent 裸键分支：
case KeyCode.UP -> {
    if (onFirstLine() && (isEmptyEditor() || history.browsing() || state.cursorCol() == 0)) {
        if (!historyUp()) state.moveCursorUp();
    } else if (onFirstLine()) {
        state.moveCursorToLineStart();          // pi :932 顶行跳行首
    } else {
        state.moveCursorUp();
    }
}
case KeyCode.DOWN -> {
    if (history.browsing() && onLastLine()) {
        if (!historyDown()) state.moveCursorDown();
    } else if (onLastLine()) {
        state.moveCursorToLineEnd();             // pi :943 底行跳行尾
    } else {
        state.moveCursorDown();
    }
}

private boolean historyUp() {
    var entry = history.navigate(-1);
    if (entry == null) return false;
    if (entry.restoreDraft()) { restoreDraft(); } else { setTextInternal(entry.text(), true); }
    return true;
}
```

`setTextInternal(text, atStart)`：`state.setText(text)` → `moveCursorToStart()`；`atStart == false` 则走到末行行尾（`moveCursorToLineEnd()` 需要先下移到最后一行——用 `for` 逐行 `moveCursorDown`）。不触碰 `undoStack`（导航不产生 undo 单元；进入历史时已 `pushSnapshot()`）。

`submit()` 改：

```java
public void submit() {
    var text = state.text();
    if (!text.isBlank()) {
        history.add(text);              // pi addToHistory（进历史后再交 handler）
        submitHandler.accept(text);
        state.clear();
        undoStack.clear();
        lastAction = null;
    }
}
```

#### 6.3 历史浏览退出

`EditorComponent` 的以下操作在开头调 `history.reset()`（对齐 pi `exitHistoryBrowsing` 的 15 个调用面，js 逐点对应到 java 的等效入口）：

- `typeChar`／`backspace`／`forwardDelete`／`insertNewline`／`insertText`／`replaceText`／`setText`／`clear`／`submit`
- 各 kill/yank 与词移动（`deleteToLineStart`／`deleteToLineEnd`／`deleteWordBackward`／`deleteWordForward`／`yank`／`yankPop`／`moveWordLeft`／`moveWordRight`）

实现上统一在 `onKeyEvent` 的「非 UP/DOWN 的键」入口先 `history.reset()`（一处即可覆盖全部编辑键，且语义等价：一旦离开历史浏览，再按 ↑ 是重新开始浏览并重新记 draft）。

- `isOnFirstVisualLine`／`isOnLastVisualLine` 的 java 等价：`state.cursorRow() == 0` / `== state.lineCount()-1`（TamboUI 无软换行视觉行；java 的 wrap 在渲染层，编辑器按逻辑行走——登记 D199）。

---

## §7 S8 thinking level 选择器（含 D84）

### pi 证据

`components/thinking-selector.ts`（154 行）构造序与逐字：

```
LEVEL_DESCRIPTIONS = { off:"No reasoning", minimal:"Very brief reasoning (~1k tokens)",
  low:"Light reasoning (~2k tokens)", medium:"Moderate reasoning (~8k tokens)",
  high:"Deep reasoning (~16k tokens)", xhigh:"Extra-high reasoning (~32k tokens)", max:"Maximum reasoning" }

label       = `${level === currentLevel ? "✓ " : "  "}${level}`
description = level === defaultThinkingLevel ? `${LEVEL_DESCRIPTIONS[level]} · default` : LEVEL_DESCRIPTIONS[level]

子件序：DynamicBorder → Spacer(1) → Text("Thinking Level") → Spacer(1)
        → Text(`${keyDisplayText("app.thinking.cycle")} cycles thinking levels in-session`) → Spacer(1)
        → Input(searchInput) → Spacer(1) → SelectList → Spacer(1)
        → Text(dim, `  ${confirm} to select · ${save} to set as default · ${cancel} to cancel`)
        → DynamicBorder

handleInput：先 app.thinking.save ⇒ onSelectAsDefault(选中项)；再四导航键转发 SelectList；
            其余入 searchInput → applyFilter(fuzzyFilter(allItems, q, `${value} ${description}`))
```

`showThinkingSelector()`（interactive-mode.ts:5173-5196）：`currentLevel = session.thinkingLevel ?? DEFAULT_THINKING_LEVEL`；`defaultThinkingLevel = settingsManager.getDefaultThinkingLevel() ?? DEFAULT_THINKING_LEVEL`；`onSelectAsDefault ⇒ selectLevel(level, true)`。

`selectThinkingLevel(level, persist)`（:5153-5162）：`session.setThinkingLevel(level, {persist})`＋`showStatus(persist ? "Default thinking level: {level}" : "Thinking level: {level}")`。

`DEFAULT_THINKING_LEVEL = "medium"`（`core/defaults.ts`）。

### Java 现状与差距

`ThinkingLevelScreen`（71 行，批 15）只有「标题＋`SelectList.render()`」；构造点 `SlashFlowRouter.java:196-199`。差距：缺描述列、DEFAULT 标记、`ctrl+s` 存默认、pi 标题文案（"Select thinking level" vs "Thinking Level"）、独立搜索行、底行提示、cycle hint 行。

`SelectList` 已有 `filter` 状态机（可打印字符累积→模糊过滤；Esc 先清 filter；`:42-79`），但渲染只画 label（`SelectList.java:143-154`），且不暴露 visible 集合。

### 设计

#### 7.1 `SelectList` 读点（+12 行）

```java
/** 可见项（过滤后；自绘列表读点）。 */
public List<T> visibleItems() { return List.copyOf(visible); }

/** 当前选中下标（钳过界；空表为 0）。 */
public int selectedIndex() { return Math.min(selected, Math.max(0, visible.size() - 1)); }
```

#### 7.2 `ThinkingLevelScreen` 重写（约 155 行）

```java
public ThinkingLevelScreen(AgentSession session, KeybindingsManager keys, Consumer<String> statusSink)
```

- `available = ThinkingCommands.availableLevels(session)`；`current = session.harness().getThinkingLevel()`；
- `defaultLabel =` 设置值（`accessors().getDefaultThinkingLevel()`）缺省 `"medium"`（`DEFAULT_THINKING_LEVEL`）；
- `list = new SelectList<>(available, ModelThinkingLevel::label)`；`list.selectItem(current::equals)`；
- `filter` 标签：**用 `label + " " + description` 参与匹配**。`SelectList` 的过滤键是 `label`（构造器 `Function<T,String>`），故传入组合函数：`lvl -> lvl.label() + " " + description(lvl)`；渲染时仍只显示 `label`（描述列自绘）——等价 pi `fuzzyFilter(..., `${value} ${description}`)`。

`onKeyEvent`：

```java
var stroke = TamboUIAdapter.toStroke(event);
if (keys.matches(KeybindingsManager.THINKING_SAVE, stroke)) {
    list.selected().ifPresent(level -> {
        saveAsDefault = true;
        selectedForSave = level;
        done = true;
    });
    return true;                          // pi :141-146 先于导航判定
}
return list.onKeyEvent(event);
```

`apply`：

```java
if (list.cancelled()) return;             // Esc 只关
var level = saveAsDefault ? selectedForSave : list.selected().orElse(null);
if (level == null) return;
if (saveAsDefault) {
    this.session.setThinkingLevel(level);                                  // pi persist=true 同时设当前级
    this.session.services().settings().accessors().setDefaultThinkingLevel(level.label());
    statusSink.accept("Default thinking level: " + level.label());
} else {
    this.session.setThinkingLevel(level);
    statusSink.accept("Thinking level: " + level.label());
}
```

`render()` 自绘布局（宽＝终端列数）：

```
────────────（border，dim）
（空行）
 Thinking Level                        ← pi Text("Thinking Level")，accent 加粗
（空行）
 <cycle> cycles thinking levels in-session   ← keyDisplayText(THINKING_CYCLE)
（空行）
 > <filter>                             ← 搜索行（空 filter ⇒ 空行；非空 ⇒ "> "+query）
（空行）
 ✓ off      No reasoning · default      ← label 左列（宽 = maxLabel+2）、description 右列（dim）
   minimal  Very brief reasoning (~1k tokens)
   ...
（空行）
   <confirm> to select · <save> to set as default · <cancel> to cancel   ← dim
────────────
```

- 选中项 `✓ ` 前缀＋accent、未选两空格（pi label 逐字）；
- description 为 DEFAULT 时追加 ` · default`；
- 底行键名走 `KeybindingHints.keyText/displayText`（`ExtensionSelectorOverlay` 同手法）；
- 布局模型（`List<List<Span>>`）与 `ExtensionSelectorOverlay.layout()` 同构，测试可断言逐行文本。

#### 7.3 构造点与绑定

- `KeybindingsManager` 新增常量与 overlay 绑定：

```java
public static final String THINKING_SAVE = "app.thinking.save";
overlayBindings.put(THINKING_SAVE, List.of(KeyStroke.of("s", true, false, false)));
```

放 `overlayBindings` 而非 `bindings`：主屏 `resolve()` 不命中（Ctrl+S 不进 `handleAction`），selector 内 `matches` 两表都查 ⇒ 命中。

- `SlashFlowRouter.java:196-199` 改 `new ThinkingLevelScreen(app.session, app.keys, chatScreen::appendSystemText)`。

> B259 注：`/thinking` 与 selector 的可用集来自 `ThinkingCommands.availableLevels`（按模型过滤）；RPC 的 `get_available_thinking_levels`/`cycle_thinking_level` 仍用 `ThinkingLevel.ordered()` 全 7 —— 两条路径口径不一，已登 B259，不在本批改。

---

## §8 K20 键盘滚动键重裁（含 B238-①）

### pi 证据

```
tui/keybindings.ts:96-101   tui.editor.cursorLineStart = "home"（+ctrl+a）；cursorLineEnd = "end"（+ctrl+e）
tui/keybindings.ts:160-182  tui.altScreen.pageUp = pageUp、pageDown = pageDown；
                            halfPageUp/Down = []、lineUp/lineDown = []；
                            previousPrompt = ["ctrl+shift+up","ctrl+up"]、nextPrompt = [...down]
tui/keybindings.ts:208-209  tui.altScreen.top = "ctrl+home"、bottom = "ctrl+end"

tui-alt-screen.ts:750-792   分派序（overlay 先让）：
  pageUp/Down   → scrollBy(∓ max(1, viewportHeight - PAGE_SCROLL_OVERLAP))；PAGE_SCROLL_OVERLAP = 4（:76）
  halfPageUp/Dn → scrollBy(∓ max(1, floor(h/2)))
  lineUp/Down   → scrollBy(∓1)
  previousPrompt/nextPrompt → scrollToPrompt(∓1)
  top/bottom    → scrollToTop()/scrollToBottom()
```

`tui.editor.historyPrevious/historyNext` 默认 `[]` ⇒ 裸 ↑/↓ 是编辑器历史（§6），不是 alt-screen 键。

### Java 现状与差距

- `GlobalKeyRouter.java:153-156`：空编辑器＋`isChatNavigation`（`UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END`，无修饰）⇒ 滚视口。
- `:172-182`：PgUp/PgDn 步长 `visibleRows()`（pi 是 `visibleRows-4`）；`HOME → scrollToTop()`、`END → scrollToBottom()`（裸键）。
- `EditorComponent.java:144-153`：`ctrl+home/ctrl+end` 被绑为行首/行尾（pi 本锚点已删，改 `ctrl+a`/`ctrl+e`）。

### 设计

#### 8.1 分派（全屏对齐 pi；内联保留 java 便利）

```java
// 落在 keys.resolve 与 Tab 之后、chatScreen.onKeyEvent 之前
if (app.runner != null && isAltScreenScroll(event)) {    // 全屏：pi alt-screen 语义
    scrollViewport(event);
    return EventResult.HANDLED;
}
if (chatScreen.isInputEmpty() && isInlinePageScroll(event)) {   // 内联：java 独有（D195）
    scrollViewport(event);
    return EventResult.HANDLED;
}
chatScreen.onKeyEvent(event);
```

- `isAltScreenScroll`：`PAGE_UP/PAGE_DOWN` 且无 Ctrl/Alt/Shift；或 `HOME/END` 且仅 Ctrl。**恒消费**（pi alt-screen 在编辑器之前）。
- `isInlinePageScroll`：仅 `PAGE_UP/PAGE_DOWN` 无修饰（内联无 alt 屏；裸 Home/End 归还编辑器）。
- 裸 `UP/DOWN/HOME/END` 从 `isChatNavigation` 中移除（删除该类整体）⇒ 落编辑器。

#### 8.2 步长与顶底

```java
private void scrollViewport(KeyEvent event) {
    int page = Math.max(1, chatScreen.visibleRows() - 4);    // pi PAGE_SCROLL_OVERLAP
    switch (event.code()) {
        case KeyCode.PAGE_UP   -> chatScreen.scrollByRows(-page);
        case KeyCode.PAGE_DOWN -> chatScreen.scrollByRows(page);
        case KeyCode.HOME      -> chatScreen.scrollToTop();      // 仅 ctrl+home 走到这里
        case KeyCode.END       -> chatScreen.scrollToBottom();   // 仅 ctrl+end
        default -> { }
    }
}
```

#### 8.3 `EditorComponent` 删 ctrl+home/end 臂

`EditorComponent.java:144-153` 改为只保留 `ctrl+a` / `ctrl+e`：

```java
if (stroke.ctrl() && "a".equals(stroke.key())) { state.moveCursorToLineStart(); return; }
if (stroke.ctrl() && "e".equals(stroke.key())) { state.moveCursorToLineEnd(); return; }
```

#### 8.4 行为变化

- 全屏：PgUp/PgDn 即使编辑器非空也滚视口（pi 同）；多行编辑器内不再由 `EditorComponent.PAGE_UP -> state.scrollUp(1)`（`:175-176`）接管。
- 裸 Home/End：到编辑器行首/行尾（B238-①）；视口顶/底换 Ctrl+Home/Ctrl+End。
- 裸 ↑↓：编辑器历史（K27）；内联模式下也不再滚视口（java 旧行为移除，D195 只保留 PgUp/PgDn）。
- `previousPrompt`/`nextPrompt`（Ctrl+↑/↓ 滚到上/下一个提示）与 `halfPage*`、`lineUp/lineDown` 不接（D200）。

---

## §9 测试设计

### 9.1 键位与动作

文件：新 `ThinkingActionsTest`（同包 `com.pijava.tui.app`，仿 `PiTuiAppDequeueTest`）。

1. 模型无 `THINKING` 能力：Shift+Tab ⇒ 系统行 `Current model does not support thinking`，`thinkingLevel` 不变。
2. reasoning 模型：`off→minimal→…` 按可用集轮转；状态行 `Thinking level: <label>`；末项回卷首项。
3. 当前级不在可用集（目录变更后）：取可用集首项。
4. Ctrl+T：设置项 `hideThinkingBlock` 置反；`MessageBubbleVisibility.hiddenThinking()` 随之为真/假；文案 `Thinking blocks: hidden|visible`。
5. Ctrl+O：`PiTuiApp.toolOutputExpanded` 翻转；状态行 `Tool output: expanded|collapsed`；再次同态调用短路（无重复行）。

### 9.2 投影传参（K8）

文件：`MessageBubbleExpandedTest`（扩展 `MessageBubbleVisibilityTest`）。

1. `installToolsExpanded(() -> true)` ⇒ `McpToolCallRenderer` 的展开臂（行数多于折叠臂，含 `"... (N more lines)"` 消失）；`false` ⇒ 折叠臂。
2. `installToolsExpanded(null)` ⇒ 回落 `false`。
3. `ToolCallCard` 回落路径不受影响（无 renderer 时仍单行）。

### 9.3 提示历史（K27）

文件：新 `EditorHistoryTest`＋`EditorComponentHistoryTest`。

1. `add`：trim、空串不入、连续重复不叠、第 101 条挤掉最旧。
2. 空编辑器 ↑ 连续上行到最旧后停住；↓ 回到最近后再 ↓ ⇒ 恢复 draft（原文本＋原光标）。
3. 多行编辑器：光标在首行且 `cursorCol==0` 才上行；`cursorCol>0` 时 ↑ 是行内上移。
4. 首次进入历史 `pushSnapshot` ⇒ Ctrl+- 可撤销回原文本。
5. 编辑键（键入/退格/删除/换行/粘贴）后 `history.reset()` ⇒ 再按 ↑ 从头开始。
6. `submit` 记录历史；空白提交不记录。
7. 上行后 `setTextInternal(start)`：光标在首行行首；draft 恢复后光标回到原行列。

### 9.4 S8 选择器

文件：扩展 `ThinkingLevelScreenTest`。

1. 标题 `Thinking Level`；cycle hint 行含 `cycles thinking levels in-session`。
2. DEFAULT：设置值 `medium` ⇒ `medium` 行 description 以 ` · default` 收尾；设置值 null ⇒ 仍标 `medium`（`DEFAULT_THINKING_LEVEL`）。
3. 选中项 label `✓ off`、未选 `  minimal`。
4. Ctrl+S ⇒ `setDefaultThinkingLevel(<label>)`＋`session.setThinkingLevel`＋状态行 `Default thinking level: <label>`；不写第二键时 Enter ⇒ 只设会话级＋`Thinking level: <label>`。
5. 搜索：输入 `reason` ⇒ 只剩带 `reasoning` 描述的行（description 参与匹配）；Esc 先清 filter、再取消。
6. 取消（Esc）⇒ 不设级、不写设置。

### 9.5 扩展选择器 ctrl+o（D97）

文件：扩展 `ExtensionSelectorOverlayTest`。

1. 注入 `onToggleToolsExpanded` 计数 runnable ⇒ ctrl+o 恰调 1 次、`onKeyEvent` 返回 true、选中项不变、不提交。
2. 注入 null ⇒ ctrl+o 消费但不抛错。

### 9.6 滚动键（K20）

文件：扩展 `PiTuiAppScrollTest` / `EditorComponentTest`。

1. 全屏（`runner != null`）：PgUp 步长 `visibleRows-4`（≥1）；PgDn 对称；编辑器非空也消费。
2. 全屏：Ctrl+Home ⇒ `scrollToTop`；Ctrl+End ⇒ `scrollToBottom`；裸 Home ⇒ 编辑器 `moveCursorToLineStart`（不滚视口）。
3. 内联（`runner == null`）：PgUp 空编辑器滚视口；裸 Home/End 归编辑器；裸 ↑↓ 归编辑器（历史）。
4. `EditorComponent`：Ctrl+Home 不再置光标行首（该键已被上层视口消费）；Ctrl+A 仍行首。

### 9.7 回归与闸门

- 存量 `ModelCycleActionTest` / `PiTuiAppInputTest` / `EditorComponentUndoKillRingTest` 保持绿。
- 新增测试文件每个 <500 行；`EditorComponent` 新增用例进既有文件而非新建大文件。

---

## §10 窄差与行为变化

### 窄差

| 编号 | 内容 | 类别 |
|---|---|---|
| **D195** | 内联（regular）模式保留 java 独有视口滚：空编辑器裸 PgUp/PgDn 滚转录。pi 主屏无此构造（main screen 无 alt-screen 分派）；全屏面已逐 pi | 内部·边缘 |
| **D196** | S8 搜索行形态：pi 用独立 `Input` 组件（含光标/提交转发），java 复用 `SelectList` 的 type-to-filter 并自绘一行 `> query`（无独立组件、无光标）。匹配面逐 pi（`label + " " + description`） | 用户可见·形态 |
| **D197** | 工具输出展开粒度：pi 逐组件 `isExpandable` 遍历 header/loadedResources/chatContainer（bash/diff/图像/部分结果都在内），java 全局 flag 只驱动 `ToolCallRenderer.expanded` 形参（MCP 渲染器与工具卡 args 尾截断），R12 工具执行卡整体仍缺 | 用户可见·覆盖 |
| **D198** | 键动作反馈落点：pi `showStatus` 瞬态状态行；java `appendSystemText` 转录系统行（K7/K8/K9/S8 同） | 用户可见·形态 |
| **D199** | 历史语义的编辑器形态差：pi 以「视觉行」判首/末行（软换行）；java 以逻辑行（`cursorRow`）判断；`addToHistory` 只挂 `EditorComponent.submit`（pi 7 处调用面含扩展编辑器/slash 恢复）。可见面：窄宽折行处 ↑ 的历史触发条件略早/略晚 | 内部·边缘 |
| **D200** | 键盘长尾不接：`previousPrompt`/`nextPrompt`（`ctrl+↑/↓`/`ctrl+shift+↑/↓` 滚到上/下一个提示）、`halfPageUp/Down`、`lineUp/lineDown`（pi 默认空绑定）、`scrollToPrompt` 无对应件；K7 对目录外模型按「能力未知 ⇒ 支持」 | 内部·边缘 |

### 行为变化注记（changelog 级）

1. **Shift+Tab** 从无反应变为循环思考等级；不支持的模型显示提示行。
2. **Ctrl+T** 从无反应变为显隐 thinking 块（立即影响当前转录与后续渲染）。
3. **Ctrl+O** 从无反应变为全局展开/折叠工具输出（MCP/fallback 工具的 args 预览随之变化）；扩展选择器内也生效。
4. **↑/↓** 在编辑器首/末行成为提示历史导航（原为纯光标移动的一个子路径）；空编辑器按 ↑ 回填最近一次提交。
5. **PgUp/PgDn** 步长减少 4 行（保留上下文重叠）；全屏下编辑器非空时也滚转录。
6. **Home/End** 归还编辑器行首/行尾；视口顶/底改为 **Ctrl+Home/Ctrl+End**。
7. **Ctrl+S** 成为 thinking 选择器内的「存为默认」（只在选择器内生效，主屏无动作）。
8. 四种键动作的反馈是**转录系统行**而非状态栏瞬态（D198）。

---

## §11 预计闭环账面

### map/05

| 项 | 批 34 前 | 批 34 后 |
|---|---|---|
| 键绑定/交互（K1–K39，39 单元） | 10 对齐／22 缺失／6 存疑（26.3%） | 15 对齐／18 缺失／5 存疑 |
| 屏幕/面板（S 族，35） | 12 对齐／20 缺失／3 存疑（34.3%） | 13 对齐／19 缺失／3 存疑 |
| **加权 Σ244** | **137.5 ＝ 56.4%** | **150.5 ≈ 61.7%**（+13.0） |
| **未加权 148** | **42 ＝ 28.4%** | **48 ＝ 32.4%**（对齐 48／缺失 76／存疑 23） |

**全仓**：1,216.0 → **1,229.0/1,383 ≈ 88.8%**（+13.0）。
`docs/06` 模块账面 Σ485 不动（TUI 域表在 map/05，不在 coding-agent 模块表）；`docs/06` §1 的 map/05 行改为 150.5 ≈ 61.7%、全仓行改为 1,229.0 ≈ 88.8%。

> **实施后修正（2026-10-31）**：上表 +13.0 漏计 **K6 的 +2.0**（K6 在本批从「缺失」翻「对齐」，w2）。逐行实算为 **+15.0** ⇒ map/05 137.5→**152.5/244 ≈ 62.5%**，全仓 1,216.0→**1,231.0/1,383 ≈ 89.0%**。文档（map/05、docs/05、docs/06）按实算落账。

### 台账

- **B66 销号**（三键接线）；**B238① 销号**（B238 行保留 ②③④）。
- **D84／D97 销号**（`docs/04` §4 裁决条与批 17 闭环条改注）。
- **B259 新登**（§3.1 协议面；不在本批实施）。

---

## §12 验收与风险

### 验收命令

```powershell
$env:JAVA_HOME='D:\soft\jdk\jdk-25'
D:\soft\apache-maven-3.9.9\bin\mvn.cmd -o clean verify
```

验收口径：

- 退出码 0；测试 0 失败 0 错误（记录总数与跳过数）；
- checkstyle 0 错误 0 警告（15 模块）；spotbugs 13 模块零钉；
- FileLength：`GlobalKeyRouter`／`PiTuiApp`／`EditorComponent`／`EditorHistory`／`ThinkingLevelScreen`／`SelectList`／`ExtensionSelectorOverlay`／`MessageBubbleVisibility` 全部 <500 行。

### 风险与排障钉

1. **分派序**：`isAltScreenScroll` 必须在 `keys.resolve` 之后判断——否则 Ctrl+Home 会在未来加入 app 键表时被抢；也必须早于 `chatScreen.onKeyEvent`，否则编辑器先吃到。
2. **裸 ↑↓ 的双臂**：全屏与内联都不能再让 `isChatNavigation` 拦 ↑↓，否则 K27 永远不可达。测试断言「空编辑器 ↑ 触发历史」是硬闸门。
3. **`EditorComponent` 的 `history.reset()` 位置**：放在 `onKeyEvent` 的「非 ↑/↓ 键」入口会连 `HOME/END` 也 reset（pi 也如此）；但不要放在 UP/DOWN 分支内，否则草稿在浏览中途被丢。
4. **`setTextInternal` 的光标落点**：`TextAreaState` 没有光标 setter，用 `moveCursorToStart`＋`moveCursorRight ×col`；末行行尾要先 `moveCursorDown` 到末行再 `moveCursorToLineEnd`，否则多行历史回填时光标错误。
5. **`SelectList` 过滤键变更**：`ThinkingLevelScreen` 传入组合标签（`label + " " + description`）后，`render()` 若仍走 `SelectList.render()` 会把整串画出来——本批改为自绘，必须保留 `visibleItems()`/`selectedIndex()` 读点；其它 selector 不受影响。
6. **`THINKING_SAVE` 放 overlay 表**：误放全局 `bindings` 会让主屏 Ctrl+S 被 `handleAction` 吞掉（虽然无害但语义错）。
7. **`MessageBubbleVisibility` 静态态**：`installToolsExpanded` 只由 `PiTuiApp` 构造注入一次；测试并行跑时要显式复位（`installToolsExpanded(() -> false)`），否则跨用例串态（`MessageBubbleVisibilityTest` 已有此手法）。
8. **扩展 selector 的 ctrl+o**：`ExtensionSelectorOverlay.onKeyEvent` 现有末尾无条件 `return true`，新增臂必须放在导航判定**之前**（pi `:98` 在四键判定前），否则 `ctrl+o` 会被 `isFilterInput` 之外的路径忽略。
9. **`PiTuiApp` 442 → 465**：注意 FileLength 500 闸门；`toggleToolsExpanded` 的 javadoc 压成单行（批 33 曾因多行 javadoc 撞 504 行）。
10. **全屏判据**：`app.runner != null` 是既有的 alt 屏判据（`GlobalKeyRouter.openExternalEditor:276` 同源）；内联壳下 `visibleRows()` 可能为 0，`max(1, ...)` 必须保留。

---

## §13 闭环记录（实施后回填）

- 实施日期：待回填。
- 最终 `mvn clean verify`：待回填（模块数／测试数／失败／错误／跳过）。
- checkstyle／spotbugs：待回填。
- FileLength：待回填（上列 8 个文件实际行数）。
- 窄差最终编号与措辞：D195–D200（实施后按实际撞到的再收敛）。
- 排障钉（实施期实际撞到的）：待回填。
- 账面终值：map/05 150.5/244 ≈ 61.7%；全仓 1,229.0/1,383 ≈ 88.8%（实施后复核）。
