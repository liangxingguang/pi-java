# docs/65 — L7 W5：`[Skill warnings]` 区段＋`Model scope:` 入 header＋终端标题

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游：`docs/59` §3 W5（覆盖 map/05 **S4、S5、R23**）、docs/59 §2.1 的 S4/R23 判定、docs/62（W2 的 `(name)` 已落）
- 状态：**2026-11-20 用户授权「写文档后直接实施」**（§6 实施记录）

## 0. 范围

| 行 | pi 面 | Java 现状 | 本包 |
|---|---|---|---|
| S4 | 启动资源区 `[Skill warnings]`（`:2015-2023` ＋ `formatDiagnostics :1820-1871`） | 全仓无任何 `[...]` 区段；`LoadSkillsResult.diagnostics()` 在装配期被丢弃 | 诊断上抛＋区段渲染 |
| S5 | `Model scope:` 行（`:1507-1521`）：非全屏曾 `console.log`，改渲 header 末尾 | Java 完全不输出（只有 `ModelScoping` 的 stderr per-pattern 警告） | 欢迎卡之后的 dim 行 |
| R23 | 终端标题 `setTitle`（`terminal.ts:600-604`）＋控制字符过滤（`utils.ts:372-377`） | `ExtensionUI.setTitle` 默认空、TUI 臂无 override，无 OSC 0 写入 | `TerminalTitle`＋写入点 |

## 1. pi 事实

1. `[Skill warnings]` 只在 `skillsResult.diagnostics.length > 0` 时出现，标题 warning 色，后接 `Spacer(1)`（`:2015-2023`）。
2. `formatDiagnostics` 两种行形：
   - collision：`  "<name>" collision:` / `    ✓ <winnerPath> (source)` / `    ✗ <loserPath> (source) (skipped)`；
   - 普通：有 path ⇒ `  <path>` ＋ `    <message>`；无 path ⇒ `  <message>`；色按 `level` 切 error/warning。
3. 旧名 `[Skill conflicts]` **不存在**（pi 全仓零命中）——skill 段改名为 warnings，同族仍是 `[Prompt conflicts]`/`[Theme conflicts]`。
4. `Model scope:`：`Model scope: <id[:level], …>` ＋ 可选 ` (<Key> to cycle)`；scope 为空不显示；`quietStartup` 也不显示。
5. 标题：`<APP_TITLE> - <sessionName> - <cwdBasename>`（无会话名省略中段）；`\x1b]0;<title>\x07`；过滤 `[\u0000-\u001f\u007f-\u009f]+` ⇒ 单空格，**无长度上限**。
6. 标题更新时机：初始化尾部、`session_info_changed`、扩展 reset、win32 包版本检查后恢复。

## 2. 设计

### 2.1 技能诊断：上抛＋区段

- 新增 `com.pijava.coding.agent.core.SkillDiagnostics`：
  - `format(List<ResourceDiagnostic>)`：逐条展开（两种行形照抄上述）；
  - `block(...)`：`"[Skill warnings]\n" + join`；空 ⇒ `null`；
  - `copy(...)`：防御性拷贝。
- 装配面回填：`ExtensionLoading.applySessionStartResources` 注册技能后把 `result.diagnostics()` 格式化并写入会话（不再丢弃）。
- 存储：`AgentSession.skillWarnings()` 委托 `SessionDataViews`（与 `markdownTransformers` 同一持有者；`AgentSession` 保持一行委托，避免再撑破 500 行上限）。
- 渲染：`StartupNotices.showWelcomeOnce` 在欢迎卡之后、changelog 之前追加一段 dim 系统文本（Java 系统文本无分段色，D83 同族）。

### 2.2 `Model scope:` 行

- 新增 `com.pijava.coding.agent.core.ModelScopeNotice.text(List<ScopedModel>, String cycleKeyText)`：
  - 条目＝`modelName` ＋（显式思考档 ⇒ `:level`），`,` 连接；
  - 空 scope ⇒ `null`（不出行）。
- 渲染点：`StartupNotices.showWelcomeOnce`（`app.session.scopedModels()` 现读），键文本取
  `KeybindingHints.keyText(keys.strokeFor(MODEL_CYCLE))` 后按 pi 的 `capitalize` 把每段首字母大写
  （`ctrl+p` ⇒ `Ctrl+P`；Java 无 keyHint 分段色，D92 同族）。

### 2.3 终端标题

- 新增 `com.pijava.tui.util.TerminalTitle`：
  - `sanitize`（`[\u0000-\u001f\u007f-\u009f]+` ⇒ `" "`）；
  - `title(sessionName, cwd)`（`pi-java - <name> - <basename>`；空会话名省略中段）；
  - `escapeSequence`（`\u001b]0;…\u0007`）与 `write`（`System.out` 直写，同
    `TamboUIAdapter.leaveAlternateScreen` 的既有出口）；
  - `capitalizeKey`（`ctrl+p` ⇒ `Ctrl+P`）。
- `TuiExtensionUI.setTitle` 由空操作改为 `TerminalTitle.write(title)`（扩展 `ctx.ui.setTitle` 由此生效）。
- 内置更新点：`PiTuiApp.start`／`startInline`（欢迎卡之后）与 `switchSession` 末尾（pi 的
  `session_info_changed` 时机对位）。

## 3. 窄差

| 编号 | 内容 |
|---|---|
| **D273** | 技能 collision 的 winner/loser 行尾 ` (source)` 不移植：Java `ResourceDiagnostic.Collision` 只有 `winnerPath`/`loserPath`，没有来源包字段。其余文案逐字。 |
| D83（引用） | 启动区段无分段色（pi `theme.fg("warning"/"muted")`）——Java 走 dim 系统文本，标题文案逐字。 |
| D92（引用） | `Model scope:` 的键提示无 muted 分段；`capitalize` 语义按"每段首字母大写"实现。 |

## 4. 测试计划

1. `SkillDiagnosticsTest`：collision 三行；带路径/无路径两种普通诊断；`block` 空 ⇒ null、非空含标题。
2. `ModelScopeNoticeTest`：多模型＋思考档拼接；空 scope ⇒ null；`cycleKeyText` 可省。
3. `TerminalTitleTest`：控制字符折空格（C0/DEL/C1）、标题三段形、注入控制字符不破坏序列、OSC 0 形、`capitalizeKey`。
4. 既有回归：`WelcomeOverlayTest`（卡片文本不变）、`PiTuiAppInlineTest`（启动路径新增输出不炸）。
5. 验证门：`mvn -pl pi-java-tui -am test` 全绿。

## 5. 风险

- `TerminalTitle.write` 直写 `System.out`：测试里会产生转义序列（无害）；TUI 后端缓冲不影响 OSC（与既有 alt-screen 序列同手法）。
- 启动区段顺序：`欢迎卡 → [Skill warnings] → Model scope → changelog → 崩溃提示 → 诊断`。pi 的资源区在 header 之后、changelog 之前，本包按此序。
- `AgentSession` 已在上限边缘（500 行）：本包只加两行委托，状态放 `SessionDataViews`。

## 6. 实施记录（2026-11-20）

| 文件 | 改动 |
|---|---|
| `coding-agent/.../core/SkillDiagnostics.java`（新） | `format`／`block`／`copy`：collision 三行、普通诊断两行/一行 |
| `coding-agent/.../core/ModelScopeNotice.java`（新） | `text(models, cycleKeyText)`：`Model scope: a, b:high (Ctrl+P to cycle)` |
| `coding-agent/.../core/SessionDataViews.java` | 技能诊断字段＋访问器（`AgentSession` 只留两行委托） |
| `coding-agent/.../core/AgentSession.java` | `skillWarnings()`／`setSkillWarnings()` 委托 |
| `coding-agent/.../core/ExtensionLoading.java` | 注册技能后回填 `SkillDiagnostics.format(result.diagnostics())` |
| `tui/.../util/TerminalTitle.java`（新） | `sanitize`／`title`／`escapeSequence`／`write`／`capitalizeKey` |
| `tui/.../app/TuiExtensionUI.java` | `setTitle` 覆盖 → OSC 0 |
| `tui/.../app/StartupNotices.java` | 欢迎卡后追加 `[Skill warnings]` 与 `Model scope:`；`applyTerminalTitle()` |
| `tui/.../app/PiTuiApp.java` | `showWelcomeOnce(keys)`；`switchSession` 末尾重刷标题 |

**测试**：`pi-java-tui` **687 测 0 失败 1 skip**（新增 11 项：`SkillDiagnosticsTest` 3、`ModelScopeNoticeTest` 3、`TerminalTitleTest` 5）；`WelcomeOverlayTest`／`PiTuiAppInlineTest` 零改动通过。

**偏离**：`[Skill warnings]` 只接**扩展贡献技能**那条装配路（`ExtensionLoading`，有 `owner` 可用）；`--skills` 的 `SessionSetup.discoverSkills` 是纯静态、无会话句柄，诊断仍被丢弃——若需要覆盖，得改装配签名，留作 W5 的后续项（登记在 docs/04 的 D273 备注里）。
