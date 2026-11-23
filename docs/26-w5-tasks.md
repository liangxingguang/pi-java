# 26 - L7 W5 任务清单（实施顺序）

> 对应设计：[`26-w5-design.md`](26-w5-design.md)。每项完成即跑定向测试；全部完成后回填地图与总账。改动留工作区不 commit。

| # | 任务 | 主要文件（新增/修改） | 验证 |
|---|---|---|---|
| [x] T1 | **K35 终端能力探测**：新增 `TerminalCapabilities`（detect/get/resetForTest/tmux 探测）；`SettingsScreen` 的 `supportsImages` 改由能力门；`PiTuiLauncher` truecolor 覆盖回落能力值；Alacritty 三臂 | `pi-java-tui/.../util/TerminalCapabilities.java`（新）<br>`pi-java-tui/.../screen/SettingsScreen.java`<br>`pi-java-tui/.../app/PiTuiLauncher.java` | `TerminalCapabilitiesTest`：tmux/screen/herdr/rex/orca/kitty/ghostty/wezterm/warp/iterm/WT/Alacritty/vscode/zed/jediterm/win/unknown 逐臂断言；`PI_*` 覆盖；tmux 探测异常⇒false；缓存复位 |
| [x] T2 | **K34 原生剪贴板**：新增 `NativeClipboard`（Termux/pbcopy/PowerShell+clip/wl-copy/xclip/xsel/AWT 回退、2 s 超时、注入 `CommandRunner`）；`ClipboardSupport` 默认实现改委托；`/copy` 改用 | `pi-java-coding-agent/.../core/clipboard/NativeClipboard.java`（新）<br>`pi-java-tui/.../util/ClipboardSupport.java`<br>`pi-java-coding-agent/.../slash/builtin/MiscCommands.java` | `NativeClipboardTest`：候选链按平台/环境生成、首个成功即止、失败回退、全失败抛 IOException、Termux 优先；`ClipboardSupport` 既有测试保持绿 |
| [x] T3 | **K39 OSC 7501**：新增 `ProgramStatusProtocol`（format/isReply/enabled）＋`ProgramStatusReporter`（事件状态机/blocked 来源表/去重/clear）；接线 `PiTuiLauncher`、`ChatScreen`、`SessionEventRouter`；`TuiExtensionUI` 对话与 custom overlay 的 blocked；`ExtensionCustomOptions` 增 `blocked` | `pi-java-tui/.../util/ProgramStatusProtocol.java`（新）<br>`pi-java-tui/.../util/ProgramStatusReporter.java`（新）<br>`pi-java-tui/.../app/PiTuiLauncher.java`<br>`pi-java-tui/.../screen/ChatScreen.java`<br>`pi-java-tui/.../screen/SessionEventRouter.java`<br>`pi-java-tui/.../app/TuiExtensionUI.java`<br>`pi-java-coding-agent/.../extension/ExtensionCustomOptions.java`（+新 `ExtensionBlockedStatus`） | `ProgramStatusProtocolTest`：序列逐字（state/app/kind/msg base64、控制字符、2048 截断、app 非法省略、clear、enabled 三态）；`ProgramStatusReporterTest`：turn/消息/压缩/settled/blocked 栈/去重/clear 序列；`TuiExtensionUI` blocked 开合（来源表最终清空） |
| [x] T4 | **K32 收窄**：`EventParser` 增 `parseModifyOtherKeys`（`ESC[27;mod;code~`）、CSI-u 备用键组（`:shifted:base`）、`SHIFTED_SYMBOLS` 逻辑 Shift 归一 | `pi-java-tui/src/main/java/dev/tamboui/tui/event/EventParser.java` | `EventParserModifyOtherKeysTest`：`27;2;97~`⇒Shift+A（身份小写）、`27;5;43~`/`27;6;43~`⇒Ctrl/Shift+Ctrl+'+'、Enter/Tab/Esc/Backspace、非法参数⇒UNKNOWN；CSI-u `97:65;2u`⇒Shift+A、`61:43;5u`⇒Ctrl+'+'；既有事件解析回归 |
| [x] T5 | **K38 复核**：确认设置行/accessor/消费链；补一条端到端断言（数字臂改 `wheelLines`，`"auto"` 保持默认） | `pi-java-tui/.../util/ScrollConfigTest.java`（补断言） | `./mvnw test -pl pi-java-tui -am` |
| [x] T6 | **回填**：`map/05` K32/K34/K35/K38/K39 行＋§加权汇总＋复核修正；`docs/05` §1/§1.1/§1.2；`docs/09` L7 行与「下一分段」改 W6；`docs/04` §0 追加 W5 | `docs/map/05-tui-web.md`、`docs/05-module-alignment-map.md`、`docs/09-pi-latest-reanchor-plan.md`、`docs/04-open-items-register.md`、本目录 26 两稿 | 读数 **210.5/252＝83.5%**（未加权 112/153） |
| [x] T7 | **收口测试**：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am` 全绿 | — | 退出码 0；失败先读首错再修 |

## 依赖顺序

T1/T2 独立 → T3（依赖 `ChatScreen`/`TuiExtensionUI` 接线）→ T4 独立 → T5 复核 → T6 回填 → T7 收口。

## 备注

- Checkstyle 单文件 ≤500 行；新文件均按 ≤220 行设计。
- `ProgramStatusReporter` 只写 stdout，不进渲染帧；`clear()` 需在 `PiTuiLauncher` 的 `finally` 与异常恢复路径各可达一次（幂等）。
- `ExtensionCustomOptions` 加组件后，更新所有构造点（含测试）与 `DEFAULT`。
