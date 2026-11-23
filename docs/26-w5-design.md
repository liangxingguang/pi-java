# 26 - L7 W5 设计稿：平台/协议（K35、K34、K39、K32 收窄、K38 复核）

> 【背景摘要】`docs/21` §7 把 W5 定为「平台/协议」：**K35 终端能力探测(1)＋Alacritty、K34 原生剪贴板(1)、K39 OSC 7501(1)＋`custom().blocked`、K32 存疑收窄、K38 设置项(1)**。本稿是 spec＋实施计划。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（W1–W4 已收口、未提交）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | K35/K34/K39 从「缺失」翻「对齐」；K32 从「存疑」翻「对齐（窄差 N）」；K38 复核后从「存疑」翻「对齐（窄差 D60）」 |
| 行为不变量 | 不改 TamboUI 组件协议；`EventParser` 既有 VT/CSI-u/粘贴语义不回归；`ClipboardSupport`/`TuiExtensionUI`/`SettingsScreen` 公共面只追加；OSC 写 stdout 只在启用的终端状态面发生，不进入渲染帧 |
| 完成判据 | ①`./mvnw test -pl pi-java-tui -am` 全绿（JDK 25）；②`map/05` K32/K34/K35/K38/K39 改判＋§加权汇总复算；③`docs/05` §1/§1.1/§1.2、`docs/09` L7 段、`docs/04` §0 同步；④改动留工作区不 commit |
| 不做项 | OSC 7501 查询/DA 哨兵握手（TamboUI 独占输入通道，见 §5 N1）；OSC 8 超链接输出（既有「只显文本」维持，N2）；Kitty 图片渲染（R14/W7）；`baseLayoutKey` 非拉丁布局匹配（N3）；pi 预编译 `.node` helper（改平台命令行，N4）；登录对话框 `auth` 状态（窄差 N5） |

## 2. 上游证据（均对 `a00a5048d`）

### 2.1 K35 终端能力探测

- `packages/tui/src/terminal-image.ts:140 detectCapabilities`：`PI_HYPERLINKS`/`PI_IMAGE_PROTOCOL`（`kitty|iterm2|none|0`）/`PI_TRUE_COLOR` 覆盖；`detectCapabilitiesFromEnvironment` 判定序：
  1. tmux（`TMUX` 或 `TERM` 以 `tmux` 开头）⇒ images null、hyperlinks 由 `tmux display-message -p '#{client_termfeatures}'` 含 `hyperlinks` 决定（`probeTmuxHyperlinks`，250 ms、异常 false）；
  2. `TERM` 以 `screen` 开头 ⇒ hyperlinks false；
  3. `TERM_PROGRAM=herdr` ⇒ kitty＋hyperlinks；`termProgram=rex` ⇒ kitty＋truecolor＋hyperlinks；
  4. `orca` 且 `ORCA_IMAGE_PROTOCOL=kitty`、`KITTY_WINDOW_ID`/`kitty`、`ghostty`/`GHOSTTY_RESOURCES_DIR`、`WEZTERM_PANE`/`wezterm`、`warpterminal`/`WARP_SESSION_ID`/`WARP_TERMINAL_SESSION_UUID` ⇒ kitty；
  5. `ITERM_SESSION_ID`/`iterm.app` ⇒ iterm2；`WT_SESSION` ⇒ images null＋truecolor＋hyperlinks；
  6. **Alacritty**：`termProgram=alacritty` 或 `TERM` 以 `alacritty` 开头或 `ALACRITTY_WINDOW_ID` ⇒ images null＋truecolor＋hyperlinks；`vscode`/`zed` 同臂；
  7. `TERMINAL_EMULATOR=jetbrains-jediterm` ⇒ hyperlinks false；win32 控制台 ⇒ truecolor、hyperlinks false；未知 ⇒ 仅 `COLORTERM=truecolor|24bit` 或 `TERM` 以 `-direct` 结尾的 truecolor 提示。
- `packages/tui/src/terminal-colors.ts`：OSC 10/11/4 颜色回复解析、OSC 997 明暗回复（`parseTerminalColorSchemeReport`），与能力探测同属终端握手面。
- APC 光标标记不渗漏：`utils.ts:1440`（`\x1b_` 前缀不随区间样式携带）——Java 无 APC 光标标记，TamboUI 自绘光标，见 §5 N6。

### 2.2 K34 原生剪贴板

- `packages/tui/src/native-platform.ts:1-63`：`getNativePlatformHelper` 仅 darwin/win32；`getNativeClipboard` 在 Linux 需 `DISPLAY` 且加载 `linux-x11`；接口 `getText/getImage/getFilePaths/setText`。
- `packages/tui/src/native-module-path.ts:31`：候选路径（包解析、模块相对、可执行文件相对）；本锚点补 Termux 路径与报错。
- Java 现状：`ClipboardSupport.java` 仅 `java.awt.Toolkit`（注释自认窄差 D44）；`MiscCommands.java:102-105` 的 `/copy` 另起一份裸 AWT。

### 2.3 K39 OSC 7501 程序状态

- `packages/tui/src/program-status.ts`（53 行）：`ProgramStatus{state: idle|working|blocked|done|error|clear, app?, kind?: permission|question|auth, message?}`；`PROGRAM_STATUS_QUERY="\x1b]7501;?\x1b\\"`；`isProgramStatusReply`；`formatProgramStatus`＝`\x1b]7501;state=…[:app=…][:kind=…][:msg=<base64>]\x1b\\`，app 须 `[A-Za-z0-9_.+-]{1,32}`，消息控制字符→空格、trim、UTF-8 截断 2048 字节。
- `packages/tui/src/terminal.ts:576 setProgramStatus`：仅 `programStatusSupported` 时写出；`:291-300` 握手——Kitty 查询后串 `PROGRAM_STATUS_QUERY` 再串 DA 查询，收到 7501 回复⇒支持；DA 先到⇒不支持；`PI_PROGRAM_STATUS=1/0` 跳过查询；`stop()` 时 `formatProgramStatus({state:"clear"})`。
- `packages/coding-agent/src/modes/interactive/program-status-reporter.ts`（111 行）：`agent_start`⇒runActive；`message_end`（assistant）⇒`stopReason=error` 则 `error(firstLine(errorMessage))`，否则 `done`；`compaction_start/end` 维护 compacting 与 error/aborted；`agent_settled`⇒resting=aborted?idle:runResult；`setBlocked(source, status|undefined)` 按来源栈，最近者为 blocked；`report()` 按状态键去重；working/done 带会话名。
- 接线：`interactive-mode.ts:551`（字段）、`:2729`（`extension-dialog` 的 blocked＝顶层对话框 `{kind:"question", message:title}`）、`:2966-3043`（custom overlay 用 `options?.blocked`，每 overlay 独立来源 `extension-overlay:N`）、登录 `auth`（`:…LoginDialog}`，本批不做）。

### 2.4 K32 键盘协议

- `packages/tui/src/keys.ts:731`：xterm modifyOtherKeys＝`CSI 27 ; modifiers ; keycode ~`，modifier 1-indexed（2=shift、3=alt、5=ctrl…，故减 1 得位掩码）。
- `:602 parseKittySequence`：CSI-u 组 `<codepoint>[:<shifted>[:<base>]] ; <mod> [: <event>] u`。
- `:792 matchesPrintableModifyOtherKeys`：物理匹配（shift 字母按小写身份归一）＋逻辑匹配（`SHIFTED_SYMBOL_KEYS="~!@#$%^&*()_+|{}:<>?"`，Shift+符号消费隐含 Shift）。
- `:1292 parseKey`：CSI-u 与 modifyOtherKeys 均先做逻辑 Shift 符号归一再格式化；`decodePrintableKey` 同样回落 modifyOtherKeys。
- Java 现状：`EventParser.java:224,309 parseCsiU` 只取 `parts[0]`（`97:65` 会 `NumberFormatException`→UNKNOWN），无 `27;…~`，无 Shift 符号归一。

### 2.5 K38 滚轮行数设置（复核）

- `packages/coding-agent/src/core/settings-manager.ts`：`getFullscreenWheelScrollLines`＝number 且 finite ⇒ clamp 1–100 floor，否则默认；setter 同 clamp，`"auto"` 原样。
- `packages/tui/src/wheel-scroll.ts:35 WheelScrollAccelerator`：`"auto"` 速率模型（5 ms 突发不加速、200 ms 手势窗、100 ms 参考间隔、上限 6 行）；数字臂固定行数。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| K35 | 无终端识别面；truecolor 仅 `COLORTERM`/`PI_TRUE_COLOR` | `ThemeColorMath.java:367-385`；`TamboUIAdapter.java:101-106` 只分 Windows/其他 |
| K34 | 仅 AWT；`/copy` 另起 AWT | `ClipboardSupport.java:38-45`；`MiscCommands.java:102-105` |
| K39 | 全树无 7501/程序状态输出 | greps 无命中；`TerminalTitle.java` 仅 OSC 0 |
| K32 | CSI-u 单码点；无 modifyOtherKeys；无 Shift 符号归一 | `EventParser.java:204-227,309-336` |
| K38 | **已实现**：设置行、accessor clamp、启动消费 | `SettingsItems.java:127-130`；`SettingsUiPrefsAccessors.java:163-180`；`PiTuiLauncher.java:63-67`；`ScrollConfig.java:113-120` |

## 4. 设计（Java 方案）

### 4.1 K35：终端能力探测

**新增** `pi-java-tui/src/main/java/com/pijava/tui/util/TerminalCapabilities.java`（≤200 行）：

```java
public record TerminalCapabilities(ImageProtocol images, boolean trueColor, boolean hyperlinks) {
    public enum ImageProtocol { KITTY, ITERM2 }
    public static TerminalCapabilities detect(Map<String,String> env, String osName);
    public static TerminalCapabilities get();          // 缓存；env=System.getenv, os=os.name
    public static void resetForTest();
    static boolean tmuxForwardsHyperlinks();           // 250ms 超时，异常⇒false
}
```

- 纯函数 `detect(env, osName)` 逐条对位 §2.1 判定序；`PI_*` 覆盖在探测后套用（`PI_TRUE_COLOR`/`PI_HYPERLINKS` 解析 `1|0`，`PI_IMAGE_PROTOCOL` 解析 `kitty|iterm2|none|0`）。
- truecolor 提示＝`COLORTERM ∈ {truecolor,24bit}` 或 `TERM` 以 `-direct` 结尾。
- 接线：
  - `SettingsScreen.java:56-59` 的 `supportsImages` 由常量 `false` 改为 `TerminalCapabilities.get().images() != null`（pi `settings-selector` 的终端图片门；图片渲染本身仍归 R14）。
  - `PiTuiLauncher.java:79-80`：`Themes.redetectColorMode(terminalTrueColor(session).orElseGet(() -> TerminalCapabilities.get().trueColor()))`，让 rex/Alacritty/Windows 控制台的 truecolor 判定与 pi 同源。
- Alacritty 子项：`ALACRITTY_WINDOW_ID`/`TERM` 前缀/`TERM_PROGRAM` 三臂纳入 `detect`；APC 光标标记 Java 无对应物（N6）。
- hyperlinks 字段仅暴露能力（N2：渲染仍只文本）。

### 4.2 K34：原生剪贴板命令行链

**新增** `pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/clipboard/NativeClipboard.java`（≤220 行）：

```java
public final class NativeClipboard {
    @FunctionalInterface public interface CommandRunner {
        void run(List<String> command, String stdin) throws IOException;   // 非 0 退出⇒IOException
    }
    public static void copy(String text) throws IOException;               // 链式，全部失败抛最后异常
    public static void overrideForTest(CommandRunner runner);              // null 复位
    static List<List<String>> commandCandidates(Map<String,String> env, String osName);
}
```

候选链（顺序即回退序）：
1. Termux（`TERMUX_VERSION` 存在或 `PREFIX` 含 `com.termux`）⇒ `termux-clipboard-set`（stdin）。
2. macOS ⇒ `pbcopy`（stdin）。
3. Windows ⇒ `powershell -NoProfile -NonInteractive -Command "$ProgressPreference='SilentlyContinue'; Set-Clipboard -Value ([Console]::In.ReadToEnd())"`（stdin），再 `clip`（stdin）。
4. Linux ⇒ `wl-copy`（stdin）、`xclip -selection clipboard`（stdin）、`xsel --clipboard --input`（stdin）。
5. 兜底 ⇒ `java.awt.Toolkit` 写剪贴板（沿用 `ClipboardSupport.awtCopy` 实现，移入本类私有方法）。

- `CommandRunner` 默认实现：`ProcessBuilder`、`redirectErrorStream(true)`、stdin UTF-8、`waitFor(2, SECONDS)`、超时 `destroyForcibly`、非 0/异常⇒继续下一候选；每个候选的 IO 异常收集，最终抛最后一个。
- `ClipboardSupport.java`：`awtCopy` 默认实现改为 `NativeClipboard.copy`（测试注入点 `overrideForTest` 保留）。
- `MiscCommands.java:101-111` 的 `/copy` 改调 `NativeClipboard.copy(lastAssistant)`，`IOException` ⇒ 原「Clipboard unavailable; last message:\n…」回退文案不变。
- Termux 路径/报错语义按可用 CLI 近似（N4）。

### 4.3 K39：OSC 7501 程序状态

**新增** `pi-java-tui/src/main/java/com/pijava/tui/util/ProgramStatusProtocol.java`（≤160 行）：

```java
public final class ProgramStatusProtocol {
    public enum State { IDLE, WORKING, BLOCKED, DONE, ERROR, CLEAR }
    public enum Kind { PERMISSION, QUESTION, AUTH }
    public record Status(State state, String app, Kind kind, String message) {}
    public static String format(Status status);     // §2.3 序列；app 正则；msg base64/2048
    public static boolean isReply(String sequence);
    public static boolean enabled(Map<String,String> env);  // "1"⇒true, "0"⇒false, 缺省⇒true
}
```

**新增** `pi-java-tui/src/main/java/com/pijava/tui/util/ProgramStatusReporter.java`（≤200 行）：

```java
public final class ProgramStatusReporter {
    public record BlockedStatus(ProgramStatusProtocol.Kind kind, String message) {}
    public ProgramStatusReporter(Consumer<String> sink, Supplier<String> sessionName);
    public void handleEvent(AgentSessionEvent event);          // §2.3 状态机
    public void setBlocked(String source, @Nullable BlockedStatus status); // 来源表，最近者优先
    public void reset();
    public void clear();                                       // 写 clear 后停用
    public void setEnabled(boolean enabled);
}
```

- 状态机严格对位 `program-status-reporter.ts`：`TurnStart`⇒runActive＋runResult=DONE；`MessageEnd`（assistant）⇒ERROR(`firstLine(errorMessage)`)/DONE；`CompactionStart/End`⇒compacting 与 error/aborted；`AgentEnd`⇒settled（aborted⇒IDLE）；`report()` 按字段键去重。
- `currentStatus`：最近 blocked ⇒ BLOCKED(kind,message)；compacting ⇒ WORKING("Compacting context")；runActive ⇒ WORKING；否则 resting；WORKING/DONE 追加会话名为 message。
- 输出：`sink.accept(ProgramStatusProtocol.format(status))`；默认 sink＝`System.out::print` 后 flush（与 `TerminalTitle` 同层级），仅 `enabled` 时写。
- **接线**：
  - `PiTuiLauncher`：在构造 `TuiExtensionUI` 前创建 reporter（`sessionName` 取 `session.sessionName()`/兜底 null；`enabled=ProgramStatusProtocol.enabled(System.getenv())`），传给 `TuiExtensionUI` 与 `ChatScreen`；`finally` 中 `reporter.clear()`。
  - `SessionEventRouter`：新增 `setProgramStatusReporter`；`onSessionEvent` 首行 `reporter.handleEvent(event)`（渲染线程，与 pi 同线程）。
  - `ChatScreen`：透传 setter（内部转 `SessionEventRouter`）。
  - `TuiExtensionUI`：`select/confirm/input/editor` 打开时 `setBlocked("extension-dialog", new BlockedStatus(QUESTION, title))`，`runDialog` 的 `finally` 重算顶层（`activeDialogs` 末项）后清除；`custom` 打开时 `setBlocked("extension-overlay:" + id, options.blocked())`，关闭时清除该来源。
  - `ExtensionCustomOptions` 增第三组件 `@Nullable ExtensionBlockedStatus blocked`（新 record `ExtensionBlockedStatus(Kind kind, String message)`，`DEFAULT` 填 null）；`TuiExtensionUI` 把 tui 侧 `BlockedStatus` 与 extension 侧类型互转。
- 支持判定：仅 `PI_PROGRAM_STATUS` 覆盖（缺省乐观 true）；无查询/DA 握手（N1）。

### 4.4 K32：modifyOtherKeys＋Shift 符号归一＋CSI-u 备用键组

`EventParser.java`：

- 常量 `static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+|{}:<>?";`。
- `parseCSIWithParams`：在 `terminator=='~'` 分支里、`"200"` 判定之后、`parseVT` 之前插入：

```java
if (params.startsWith("27;")) {
    return parseModifyOtherKeys(params, bindings);
}
```

- `parseModifyOtherKeys(String params, Bindings)`：`split(";")` 取 `mod=parseInt(parts[1])-1`、`code=parseInt(parts[2])`；`shift/alt/ctrl` 位取自 `mod`；若 `shift && code∈SHIFTED_SYMBOLS` ⇒ 逻辑归一（去 shift）；`13/9/27/127` ⇒ ENTER/TAB/ESCAPE/BACKSPACE，`32..126` ⇒ `ofChar`，其余 UNKNOWN。
- `parseCsiU`：先按 `;` 拆 `keyPart`/modPart，再 `keyPart.split(":", -1)` ⇒ `codepoint`、`shiftedKey`、`baseLayoutKey`；`shiftedKey` 有效且落在 `SHIFTED_SYMBOLS` 时以 `shiftedKey` 为字符并去 shift（与 `matchesPrintableModifyOtherKeys` 的逻辑匹配同义）。`baseLayoutKey` 解析但不消费（N3）。
- 保留既有 Ctrl+J/LF 语义与粘贴分支不动。

### 4.5 K38：复核（无代码改动）

- 证据链已在 §3：设置行 → accessor（1–100/"auto" clamp）→ `ScrollConfig.from(..., Object)` 数字臂固定 `wheelLines` → `ScrollInputNormalizer` 消费。
- 判定：**对齐（窄差 D60）**——`"auto"` 仍走 Codex 有界加速模型（非 pi 的 5/200/100 ms 速率模型），用户可配面已具备。

## 5. 窄差登记（不进分子）

| # | 项 | 窄差 | 理由 |
|---|---|---|---|
| N1 | OSC 7501 握手 | N | TamboUI 独占 stdin，无 7501 回复旁路；改为 `PI_PROGRAM_STATUS` 覆盖＋缺省乐观输出；不支持终端忽略未知 OSC |
| N2 | OSC 8 输出 | N | 能力已探测，`InlineMarkup` 仍只显文本（既有行为，OSC 8 渲染不在 W5） |
| N3 | `baseLayoutKey` | N | 非拉丁布局 alternate match 不消费（CSI-u 组已解析） |
| N4 | 原生 helper | N | pi 预编译 `.node` ⇒ 平台 CLI 链；Termux 路径/报错文案近似 |
| N5 | 登录 `auth` | N | `LoginDialog` 的 blocked 上报不做 |
| N6 | APC 光标标记 | N | Java 无 APC 标记（TamboUI 自绘光标），无可观察对照 |
| D60 | `"auto"` 加速模型 | D | 维持 Codex 有界加速（既有裁决⑥），不新造 pi 速率模型 |

## 6. 预期读数

| 行 | 改判 | 权重变化 |
|---|---|---|
| K34 | 缺失→对齐 | +1.0 |
| K35 | 缺失→对齐 | +1.0 |
| K39 | 缺失→对齐 | +1.0 |
| K32 | 存疑→对齐（窄差 N3） | +1.5 |
| K38 | 存疑→对齐（窄差 D60） | +0.5 |

**205.5＋5.0＝210.5/252＝83.5%**（未加权 107→112/153）。

## 7. 验收

- 每项：`./mvnw test -pl pi-java-tui -am`（含 coding-agent 改动时同命令覆盖）；新增单测见 `docs/26-w5-tasks.md`。
- 全绿后：`docs/map/05` 五行改判＋§加权汇总/复核修正追加；`docs/05` §1/§1.1/§1.2；`docs/09` L7 行与「下一分段」改 W6；`docs/04` §0 追加 W5 条目。
- 改动留工作区，不 commit。

## 8. 实施结果（2026-11-24）

- **K35**：新增 `TerminalCapabilities`（`detect/get/resetForTest/tmuxForwardsHyperlinks`，15 臂＋`PI_*` 覆盖）；`SettingsScreen` 图片门与 `PiTuiLauncher` truecolor 回落接线；`TerminalCapabilitiesTest` 11 例。
- **K34**：新增 `NativeClipboard`（候选链含空项＝AWT 兜底，`CommandRunner` 注入，2 s 超时）；`ClipboardSupport` 委托；`MiscCommands` `/copy` 同源；`NativeClipboardTest` 8 例。
- **K39**：新增 `ProgramStatusProtocol`/`ProgramStatusReporter`/`ExtensionProgramStatus`（阻塞来源簿）＋`ExtensionBlockedStatus`；`ExtensionCustomOptions` 第三组件；`PiTuiLauncher`（创建/退出 clear）、`SessionEventRouter`、`ChatScreen`、`TuiExtensionUI`（dialog/custom blocked）接线；协议 10 例＋状态机 7 例。
- **K32**：`EventParser` 增 `parseModifyOtherKeys`、CSI-u `:shifted:base` 组解析、`SHIFTED_SYMBOLS` 逻辑归一，并在 CSI 参数循环接受 `:`；`EventParserModifyOtherKeysTest` 8 例。
- **K38**：复核无代码改动；`ScrollConfigTest`/`SettingsItemsTest`/`SettingsActionHandlerTest`/`DeclaredSettingsAccessorsTest` 已覆盖设置面与 `"auto"` 模型。
- **偏差**：`ExtensionBlockedStatus` 用 `kind` 字符串（coding-agent 不能依赖 tui 的枚举），`TuiExtensionUI` 侧映射为 `ProgramStatusProtocol.Kind`；登录 `auth` 未接线（N5）。`TuiExtensionUI` 抽出 `OverlayHandleNarrowed`/`ExtensionProgramStatus` 以守 500 行限。
- **结果**：`./mvnw test -pl pi-java-tui -am` 全绿；`map/05` 读数 **210.5/252＝83.5%**（未加权 112/153；投影 214.0/260＝82.3%）；`docs/05`/`docs/09`/`docs/04` 已同步，下一分段 W6。
