# 57 - L8 W5 UI 槽位与编辑器示例移植设计（13 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W5 的详细设计：把 pi 锚点 `56cae7823`
> 的 13 个扩展 UI 槽位示例逐件移植到 `pi-java-examples`，给出宿主 API 消费面、逐件
> 实现要点、验收断言与窄差登记。W5 的宿主前置已全数闭环（`setFooter`/`setHeader`/
> `setStatus`/`setWidget`/`setWorkingMessage`/`setWorkingVisible`/`setWorkingIndicator`/
> `setHiddenThinkingLabel`/`setEditorComponent`/`registerMessageRenderer`/
> `registerEntryRenderer`），本包 **零新增宿主成员**。用户已裁决「设计文档落地之后直接实施」。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.5 W5 分组（13 件）、§4 W5 断言、§5 文本级口径
- 前置闭环：`docs/28`（批 17/20/21 扩展 UI 面）、`docs/50`（B284 `getModel`/`getContextUsage`）
- 用户既有裁决：文本级逐字断言；D99（overlay 定位/尺寸/堆叠）与 D220（`keyHint`）约束不变
- **文档状态：2026-11-19 设计并实施完成。**

## 0. 取证口径与关键结论

1. 13 件逐字取自锚点 `packages/coding-agent/examples/extensions/`；**D99/D220 影响面为零**——
   无一件调用 `ctx.ui.custom(...)`／`OverlayOptions`／`OverlayHandle`，也无一件读
   `options.keyHint`（`overlay-test`／`overlay-qa-tests` 已按 D237 出分母）。
2. **零新增宿主成员**。所需面全部已存在：
   `ExtensionUI` 的 `setStatus:67`／`setWidget:70`／`setFooter:74`／`setHeader:77`／
   `setTitle:80`／`setWorkingMessage:85`／`setWorkingVisible:88`／`setWorkingIndicator:91`／
   `setHiddenThinkingLabel:94`／`setEditorComponent:114`；`ExtensionContext` 的
   `getModel`／`getContextUsage`／`getThinkingLevel`／`cwd`／`registerMessageRenderer`／
   `registerEntryRenderer`／`sendMessage`／`appendEntry`／`exec`；`ExtensionEventSpec` 的
   `SESSION_START`／`TURN_START`／`TURN_END`／`MODEL_SELECT`／`AGENT_START`／
   `AGENT_SETTLED`／`SESSION_SHUTDOWN`。
3. 三条着色通道，按槽位分派（这是本包唯一的系统性窄差）：
   - **组件工厂内**（footer/header/editor/widget 组件）：`env.colorize(slot, text)` 是真实通道，
     TUI 侧产 TamboUI markup（`ExtensionInjectionLayer.customFooterRows` 直接当 markup 解析），逐字对齐；
   - **状态段（`setStatus`）**：TUI 侧把文本 `escapeMarkup` 后再包 `[dim]`（`ExtensionInjectionLayer:204-211`），
     任何 markup/ANSI 都会被转义成字面量 ⇒ handler 侧没有着色通道，`ThemeText.fg` 恒等返回；
   - **消息/条目渲染器**：`ExtensionMessageRenderer`／`ExtensionEntryRenderer` 签名里没有 `theme`，
     是 D92 的文本级方言，注释已载明。
4. 两处定时器沿 **D246** 先例：不建真 `setInterval`，用可注入 `TickDriver` 手动推帧
   （rainbow-editor 60ms、border-status-editor 80ms）。
5. 14 件（13 示例 + 共享基座）全部为 `main` 源码；每件一个独立 `*Test`。

## 1. 单元与对位

| # | pi 文件 | Java 类型 | 注册形状 | 主要宿主消费 |
|---|---|---|---|---|
| 1 | `status-line.ts` | `StatusLineExtension` | 3 events | `setStatus` |
| 2 | `model-status.ts` | `ModelStatusExtension` | 1 event：`model_select` | `notify`、`setStatus` |
| 3 | `hidden-thinking-label.ts` | `HiddenThinkingLabelExtension` | 1 event + 1 command | `setHiddenThinkingLabel`、`notify` |
| 4 | `widget-placement.ts` | `WidgetPlacementExtension` | 1 event | `hasUi`、`setWidget`×2 |
| 5 | `working-message-test.ts` | `WorkingMessageTestExtension` | 1 event | `setWorkingMessage`、`setWorkingIndicator` |
| 6 | `working-indicator.ts` | `WorkingIndicatorExtension` | 1 event + 1 command | `setWorkingIndicator`、`setStatus`、`notify` |
| 7 | `custom-footer.ts` | `CustomFooterExtension` | 1 command | `setFooter`、`sessionManager.getBranch`、`getModel`、`FooterDataView` |
| 8 | `custom-header.ts` | `CustomHeaderExtension` | 1 event + 1 command | `setHeader`、`mode` |
| 9 | `message-renderer.ts` | `MessageRendererExtension` | 1 renderer + 1 command | `registerMessageRenderer`、`sendMessage` |
| 10 | `entry-renderer.ts` | `EntryRendererExtension` | 1 renderer + 1 command | `registerEntryRenderer`、`appendEntry` |
| 11 | `modal-editor.ts` | `ModalEditorExtension` | 1 event | `setEditorComponent` |
| 12 | `rainbow-editor.ts` | `RainbowEditorExtension` | 1 event | `setEditorComponent` |
| 13 | `border-status-editor.ts` | `BorderStatusEditorExtension` | 4 events | `setWorkingVisible`、`setFooter`、`setEditorComponent`、`exec`、`getContextUsage`、`getModel`、`getThinkingLevel`、`cwd` |

## 2. 逐件设计

### 2.1 `status-line.ts` → `StatusLineExtension`

`session_start` 写 `"Ready"`；`turn_start` 计数 +1 写 `"● Turn N..."`；`turn_end` 写
`"✓ Turn N complete"`；键恒 `status-demo`（pi L15）。三处 `theme.fg` 经 `ThemeText.fg` 降级纯文本。

### 2.2 `model-status.ts` → `ModelStatusExtension`

`model_select`：`source != "restore"` 才 `notify("Model: <provider>/<id>", INFO)`；
恒写状态段 `"🤖 <id>"`（`model.modelId()`，不是 provider）。`previousLabel` 的 `undefined ⇒ "none"`。
pi 末尾 `console.log` 只进调试输出，java 不产 stdout 残留（CLAUDE.md）。

### 2.3 `hidden-thinking-label.ts` → `HiddenThinkingLabelExtension`

`session_start` 施加 `"Pondering..."`；`/thinking-label <text>` 设标签，无参数复位
（`setHiddenThinkingLabel(null)` ＋ 通知 `"Hidden thinking label reset to: Pondering..."`）。
两处通知 pi 未传类型 ⇒ java 传 `null`（≙ undefined ⇒ info）。

### 2.4 `widget-placement.ts` → `WidgetPlacementExtension`

`session_start` 且 `hasUi` 时写两槽：`widget-above` 省略 placement（宿主默认 aboveEditor）、
`widget-below` 显式 `"belowEditor"`。无 UI 会话不写。

### 2.5 `working-message-test.ts` → `WorkingMessageTestExtension`

`session_start` 写 `CUSTOM_MESSAGE`（`\x1b[38;2;155;86;63mWorking... (custom)\x1b[39m`）与
单帧 `WorkingIndicatorOptions(frames=[同色 ●], intervalMs=null)`。裸 24bit ANSI 逐字保留
（宿主对自定义帧「逐字渲染，颜色须扩展自带」）。

### 2.6 `working-indicator.ts` → `WorkingIndicatorExtension`

模式闭集落 `enum Mode`（DOT/NONE/PULSE/SPINNER/DEFAULT，`wire()` 为命令字面量）。
`indicator(mode)` 五分支：dot 单帧、none 空帧表（整体隐藏）、pulse 四帧 120ms、
spinner 十帧 80ms（逐帧轮转六色 `PASTEL_RAINBOW[i % 6]`）、default 返回 `null`（≙ undefined）。
`/working-indicator` 四臂：无参数报当前、非法报用法（ERROR）、合法施加、`reset` ⇒ DEFAULT。
状态段 `"Indicator: <描述>"` 经 `ThemeText.fg` 降级。

### 2.7 `custom-footer.ts` → `CustomFooterExtension`

`/footer` 开关；开启时装 `ExtensionFooterFactory`。组件 `render(width)`：
分支上 assistant 消息用量求和（`sumUsage`），左段 `↑input ↓output $cost`、右段
`<modelId> (<gitBranch>)`，两条 `theme.fg("dim")` 走 `env.colorize`；空格补齐到 `max(1, width-w左-w右)`
后按 `truncateToWidth` 截断。`formatTokens` 复刻 JS 模板（`n<1000` 去小数点、否则 `toFixed(1)+"k"`）。
关闭臂 `setFooter(null)` ＋ `"Default footer restored"`。

### 2.8 `custom-header.ts` → `CustomHeaderExtension`

仅 `mode=="tui"` 时挂 header。吉祥物行集 `piMascot(fg)` 逐字：8 行
（`""`、`     █▌  █▌`（瞳孔 dim）、`  `+accent 14 块、四条腿 `     ██    ██`（accent）、`""`）；
副标题 `muted("   shitty coding agent") + dim(" v" + Version.VERSION)`。
`/builtin-header` ⇒ `setHeader(null)` ＋ `"Built-in header restored"`。

### 2.9 `message-renderer.ts` → `MessageRendererExtension`

`registerMessageRenderer("status-update", …)`：`details.level`（`error`/`warn`/其余）
决定前缀 `[LEVEL]`；`expanded` 且 `timestamp` 非 0 时追加 `"\n  at HH:mm:ss"`。
`/status [warn|error] <message>`：级别前缀 + 余下文本（空 ⇒ `"Status update"`），
`sendMessage(type, content, display=true, details={level,timestamp})`。
时区取系统默认、格式固定（pi 的 `toLocaleTimeString` 依区域，java 取等价无区域口径）。

### 2.10 `entry-renderer.ts` → `EntryRendererExtension`

`registerEntryRenderer("status-card", …)`：`" [status] <message>"`，`data == null` ⇒ `"No data"`，
`data` 在但无 `message` ⇒ 模板里的 `undefined` 逐字；`expanded` 追加
`" yyyy-MM-dd HH:mm:ss"`。`/status-card [message]` ⇒ `appendEntry`（空参数 ⇒ `"Status card"`）。

### 2.11 `modal-editor.ts` → `ModalEditorExtension`

`NORMAL_KEYS` 把 pi 的转义序列映射成中性键：`h/j/k/l` ⇒ `left/down/up/right`、
`0` ⇒ `ctrl+a`、`$` ⇒ `ctrl+e`、`x` ⇒ `delete`（`i`/`a` 是模式切换，不入表）。
`handleInput` 逐字：Escape 在 INSERT 转 NORMAL、在 NORMAL 下传；INSERT 全下传；NORMAL 里
`i` 转 INSERT、`a` 转 INSERT 并先右移、映射键转基类、其余可打印字符吞掉、控制序列下传。
`render` 在末行（底边框）贴 `" NORMAL "`/`" INSERT "`（宽度够才贴）。

### 2.12 `rainbow-editor.ts` → `RainbowEditorExtension`

七色基色、`brighten(base, factor)` 向白插值取整、三位闪光（中心 0.7／邻位 0.35）、
20 帧循环（前 10 帧扫、后 10 帧停）。`handleInput` 后按 `hasUltrathink` 起停动画；
`render` 用大小写不敏感替换把每处 `ultrathink` 换成当前帧的彩虹串。帧推进经注入 `TickDriver`。

### 2.13 `border-status-editor.ts` → `BorderStatusEditorExtension`

纯函数四件：`fitBorder(left,right,width,border[,fill])`（两段削平 + 最小间隔 3 + 间隙填充）、
`formatCwd(cwd,home)`（`~` 缩短）、`formatContext(ctx)`（`ctx <pct>%/<k>k`／`"ctx ?"`）、
`formatThinking(level)`。`session_start`：`setWorkingVisible(false)` ＋ 空 footer ＋
`git branch --show-current`（`exec`，失败即无分支）＋ 自定义编辑器。`agent_start`/`agent_settled`
起停 80ms spinner；`session_shutdown` 停表清重绘柄。编辑器 `render` 把首行换 `fitBorder(topLeft, "")`、
末行换 `fitBorder(bottomLeft, bottomRight)`。

## 3. 共享件（main 源码，非示例）

| 类型 | 作用 |
|---|---|
| `ExampleEditor` | pi `CustomEditor` 的文本级最小对位：文本缓冲/光标/删除/换行、边框 `render`、`borderColor` 钩子、`requestRender` 注入 |
| `ExampleText` | pi `visibleWidth`/`truncateToWidth` 对位（markup 感知，标签零宽） |
| `ThemeText` | handler 层 `theme.fg` 降级（恒等），窄差声明唯一落点 |

## 4. 验收测试

每件一个 `*Test`（13 个类、63 项），全部走 `W3Context`＋`ScriptedUi` 夹具，无真实终端：

- 帧表/色表/键位映射/文案常量逐字断言（`SPINNER_FRAMES`、`PASTEL_RAINBOW`、`COLORS`、
  `NORMAL_KEYS`、`CUSTOM_MESSAGE`、`DEFAULT_LABEL` 等）。
- 事件/命令两臂：`fire(spec, event)` 与 `command(name, args)` 驱动，断言槽位录表、通知文案与类型。
- 组件：`footers()/headers()/editorFactories()` 录下的工厂用测试 `ExtensionUiHostEnv`
  （`colorize = (slot,text) -> "[slot]text[/]"`，`requestRender` 计数）实例化后断言 `render(width)` 行集、
  可见宽与截断、展开两臂。
- 渲染器：`messageRenderers`/`entryRenderers` 录表直接调用，断言行集与 `expanded` 两臂。
- 定时器：注入 `TickDriver` 手动推帧，断言帧号、重绘次数与起停。

## 5. 窄差与登记

本包**不新开 D 编号**，全部沿用既有登记：

| 面 | 既有登记 | 落点 |
|---|---|---|
| 结构化 Theme 对象（java 只有主题名）；状态段无着色通道 | `docs/27` §3.4「主题面窄差」（同 D235 族） | status-line、model-status、working-indicator、custom-footer/header（组件内仍用 `env.colorize`） |
| 渲染器无 theme/Box，产文本行 | D92（`ExtensionMessageRenderer`/`ExtensionEntryRenderer` 注释已载明） | message-renderer、entry-renderer |
| `FooterDataView` 无 `onBranchChange` | D100 | custom-footer（无退订柄，重绘靠下一次渲染） |
| 无注入式定时器 | D246 | rainbow-editor、border-status-editor |
| `CustomEditor` 富组件（软换行/滚动/补全/历史/内边距） | 本包文本级基座 `ExampleEditor` 注释声明 | modal/rainbow/border-status-editor |
| overlay 定位/尺寸/堆叠 | D99（本包零消费） | — |
| `keyHint` | D220（本包零消费） | — |

## 6. 实施顺序与验证

按依赖排：轻量槽位 6 件 → footer/header/renderer 4 件 → 编辑器 3 件（先 `ExampleEditor`）→
夹具补齐 → 注册表 → 全量测试。验证：`mvn -pl pi-java-examples -am test` 模块 **358 测 0 失败**；
Checkstyle 对新文件零告警。

## 7. 实施记录（2026-11-19）

- 13 个 `main` 类＋3 个共享件落地；`META-INF/services` 从 53 项增至 **66 项**；
  `RegistryIntegrityTest` 同步（66 类/66 名）。
- 夹具扩展（仅 test 源码）：`ScriptedUi` 增 `hiddenThinkingLabels`/`workingMessages`/
  `workingIndicators`/`workingVisibles`/`footers`/`headers`/`editorFactories`/`widgetPlacements`
  录表与对应 override；`W3Context` 增 `contextUsage`、`messageRenderers`、`entryRenderers`、
  `appendedData`、`sentCustom`，`thinkingLevel` 默认 `off()`（宿主恒有值）。
- 13 个测试类共 **63 项**全绿；模块全量 **358 测 0 失败**。
- 附带修复（用户 2026-11-19 裁决「修复」）：W4/W8 遗留的两条 SpotBugs 告警已销——
  `ToolOverrideExtension.appendLog` 的 `file.getParent()` 为 null（`pi.java.agentDir` 空串时
  相对路径无父目录）改为先判空再 `createDirectories`，与 pi 的 `appendFile` 自动建父目录等价；
  `RpcDemoExtension` 的 `session_start` 处理里对已定型事件的 `instanceof` 判断
  （`event instanceof SessionStartEvent s ? s.reason() : null`）是死分支，改为直接
  `event.reason()`（同时删掉随之失效的 `ExtensionEvent` import）。修复后
  `mvn -pl pi-java-examples -am install` **SpotBugs 全模块 0 告警、BUILD SUCCESS**，
  模块仍 358 测 0 失败。
