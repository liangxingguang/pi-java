# 21 - L7 复核与分段计划：TUI/web 按目标锚点对账

> 【背景摘要】L7 是七层中最大的剩余缺口（旧锚点正式 179.0/244＝73.4%；目标锚点投影 182/259≈70.3%）。按「由底往上、逐层对齐」裁决，L6 复核收口后的下一批就是 L7。本稿是对账稿：①核实 `docs/09` L7 段十行与 map/05 预审计 12 项是否成立；②登记预审计漏项；③复核 10 条「旧基线」行；④给出分段实施计划。**核对完成后更新地图与总账，分段实施随后按 W 段推进。**
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前 main（`942a0ce5`）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做对账、计划与分段设计索引。

## 1. 上游 diff 规模（核实基线）

`56cae7823..a00a5048d`：`packages/tui/src` **41 文件 +1388/−525**；`packages/coding-agent/src/modes/interactive` **28 文件 +818/−342**。全部 14 个 tui 区间提交均被 `docs/09` L7 表引用；无表外未引用的提交。

## 2. docs/09 L7 十行核实（全部成立）

| 行 | 结论 | a00a5048d 证据 | Java 侧现状 |
|---|---|---|---|
| 1. render() 只读＋Container 缓存 | 成立 | `tui.ts:130`（`readonly string[]`）、`:369-393 renderChildLines`、`:470-472`；`box.ts:100,112` | TamboUI 屏幕架构无该组件协议 ⇒ 见 §6 |
| 2. `setSuspendSignalHandler`（SIGTSTP） | 成立 | `tui.ts:577`、`:1099-1101`、`:1108`、start/stop `:1055-1056/:1139-1140`；外部 TSTP 同 Ctrl+Z（coding-agent 侧 e4f50d76d） | JVM 无公共 signal API（`SessionShutdownHook.java:7-9` 明示）⇒ 见 §6 |
| 3. 全屏 O(viewport) 帧缓存 | 成立 | `tui.ts:613,1163`；`layout.ts:398 renderLayoutFrame(reusableOutput)`、`:84 renderCached`；`tui-alt-screen.ts:1702-1706` | 无 renderCache/reusableOutput 对应物 ⇒ 见 §6 |
| 4. Markdown 顶层块缓存 | 成立 | `markdown.ts:300 RenderedBlock`、`:330` WeakRef、`:404-417` 逐块复用、`:445`、`:641-645` 高亮缓存、`:356-358 invalidate` | `MarkdownRenderer` 无缓存逻辑 ⇒ **可实现**（应用层） |
| 5. HTML 注释隐藏＋图片硬换行 | 成立 | `markdown.ts:201,203,212`、`:459`、`:870-873` | 无 `<!--` 处理 ⇒ **可实现** |
| 6. overlay/图片互斥＋Kitty 保留 | 成立 | `tui.ts:520,1567`；`terminal-image.ts:246,276,428`；`tui-alt-screen.ts:1769,1753-1757` | 不适用（无图片协议）——随 R14 批 |
| 7. 斜杠 Enter 上下文＋粘贴标记 | 成立 | `editor.ts:807-808,2223,1433` | 斜杠语义已等价（`GlobalKeyRouter.java:125`）；粘贴标记随 K26 |
| 8. 设置列表 query 门控 | 成立 | `settings-list.ts:253-256` | Java 无此问题（方向键不经搜索输入）⇒ **天然对齐** |
| 9. Alacritty 识别＋APC 不渗漏 | 成立 | `terminal-image.ts:134-135`；`utils.ts:1440` | 不适用/部分（无终端识别面）；**遗漏子机制**⇒ 随 K35/C20 |
| 10. ASCII 快路径＋setText 早退 | 成立 | `utils.ts:183-260`；`text.ts:27` | `TextLayout` 无 Segmenter ⇒ 见 §6 |

**行内遗漏子机制 4 项（并入对应行说明，不单列单元）**：①`tui.ts:1589` 输出归一化剥离全部 CURSOR_MARKER；②`tui.ts:1626` 光标定位改 replaceAll（多标记行为变化）；③`markdown.ts:356-358` invalidate 重置 `defaultStylePrefix`（行 4 主题切换正确性前提）；④`tui.ts:732` setFocusedComponent 强制 contentChanged（行 3 正确性前提）。

## 3. 预审计 12 项核实（全部成立；落点修正）

预审计 15 权重的 12 项全部成立。**落点修正**：C21／R30–R33／K41 的主体都在 `packages/tui`（框架层），modes/interactive 只是采纳面（`readonly string[]` 改签名、`MarkdownSlot` 复用、`setSuspendSignalHandler` 接线）。预审计未标注模块，后续分段设计按模块归属。

| 项 | 主体落点 | interactive 侧采纳面 |
|---|---|---|
| C21 render 只读/缓存(1) | tui.ts/layout.ts/box.ts | themed-text/bash-execution/visual-truncate/tool-execution/settings-submenu 改签名；assistant-message/radius-login 按引用判变后拷贝 zone 行 |
| K40 SIGTSTP(1) | tui.ts:577 | interactive-mode.ts 两处接线（主屏＋切屏恢复，#12272） |
| R30 O(viewport)(2) | tui.ts/layout.ts | —（overlay 遮挡 kitty 图片按 viewport 裁剪） |
| R31 Markdown 块缓存(2) | markdown.ts | assistant-message `MarkdownSlot`＋markdown-transform `isStreaming` 改 `()=>boolean` |
| R32 注释隐藏(1) | markdown.ts | — |
| R33 ASCII 快路径(1) | utils.ts | — |
| K41 slash Enter(1) | editor.ts | —（`isInSlashCommandNameContext`） |
| `custom().blocked` OSC 7501(1) | — | interactive-mode.ts `extension-overlay:N` 状态源＋`extensionOverlays` 注册表 |
| `pasteToEditor` 重绘(1) | — | interactive-mode.ts 补 `ui.requestRender()` |
| 工具预览主题前景(1) | — | theme.ts `default: fg("text")`＋renderers/write.ts（新增）＋CodeHighlighter 类 |
| 缓存预热合并提示(1) | — | interactive-mode.ts `cacheWarmingNotice` 合并＋累计＋invalidate |
| Mermaid 升级(2) | — | mermaid.ts grok→lovely-mermaid＋适用面收窄三类＋edgeLabel 色＋osc8 只链文本 |

## 4. 预审计漏项登记（11 项，权重分段设计时逐行定）

1. **provider-brand 品牌动画**（新组件 73 行）：Radius shimmer 抽出＋新增 OpenRouter glow＋`startBrandAnimation` 50ms 帧驱动＋oauth-selector ✦ 伙伴标记——用户可感知新行为。
2. **missing-cwd 三选**：恢复二选一→Continue/Fork/Cancel（`switchToForkedSession`＋import 同步＋fork 取消删副本）。
3. **reasoning drop 提示泛化**：OpenAI reasoning items 也提示并带原因。
4. **extension-selector 窗口化**：10 行居中滚动＋`(i/n)` 计数。
5. **diff 缩进剥离**：行内 diff 先剥公共缩进（影响 R10 观感，随 R10 段）。
6. **四消息组件 transformer 接线**：compaction/branch-summary/skill-invocation/custom-message 统一接 markdownTransformers/messageId/timestamp。
7. **bash 组件 agent_settled 迁入 chat＋默认展开态**。
8. **"to resume" 独立成行**（#12237）。
9. **changelog 版本不可读时写回**；**session-share/tool-execution 稳定性修复**（spawn error 防崩／spread 改循环）；**退出前 flushStdout**（CLI 启动 UI 面，docs/18 §5 归属待定项落定于 L7）。
10. `session-selector` 渐进加载保选中＋搜索置顶；`model-selector` 刷新在途保持偏好模型。
11. **query 门控同族修复**：config/thinking/scoped-models/settings-submenu（S16 同族，见 §5）。

## 5. 旧基线 10 行复核（9 翻正，1 降存疑）

| 行 | 结论 | 关键证据 |
|---|---|---|
| S6 模型搜索 | **对齐**（翻正） | `model-search.ts` 零变化；`model-selector.ts` 两点变化（刷新保偏好、query 门控）Java 均已等价或无消费点 |
| S16 config 选择器 | **存疑**（降级） | pi `config-selector.ts:504-508` query 门控；Java `ConfigSelectorComponent.java:114-116` 无条件 `filterWith`、`ConfigSelectorModel.java:338` 重过滤即 `selectFirstItem` ⇒ 方向键移动光标会重置选中。**补偿项**：照 SelectList 模式补「query 未变不重过滤」 |
| S19 信任选择器 | **对齐**（翻正） | trust 三文件 56..a00 零变化；Java 已对位（do-not-trust 持久化＋最近祖先＋Windows 折大小写） |
| S25 /session 统计 | **对齐**（翻正） | `handleSessionCommand`/`getSessionStats` 零 hunk |
| T3 自定义主题 | **对齐**（翻正） | `theme-schema.ts` 零变化；schema 仅 `$id` URL 变 |
| T4 热重载 | **对齐**（翻正） | `theme-controller.ts` 零变化 |
| T5 thinking 色映射 | **对齐**（翻正） | theme.ts 变化全在语法高亮面，无 thinking hunk |
| T7 导出主题色 | **对齐**（翻正） | dark/light.json 仅 `$schema` 指向变化，export 对象零变化 |
| T10 OKLab | **对齐**（翻正） | colors.ts/oklab.ts 零变化 |
| W14 --no-session | **对齐**（翻正） | noSession 语义无 hunk |

**旧锚点读数修正**：179.0 → **178.5/244＝73.2%**（S16 −0.5）；未加权 87/147。map/05 各行已随本稿改判。

## 6. 无法闭环预判（设计前置判定，按「提前写明」惯例）

| 上游能力 | 预判 | 处置 |
|---|---|---|
| C21 render 只读/缓存契约 | TamboUI 为外部库，组件协议不可改 | **窄差 D**（框架层不可达面）；应用层可做等价的「行数组复用」局部优化则做 |
| R30 O(viewport) 帧缓存 | 同上（layout.ts 内部） | **窄差 D**；如 TamboUI 已有等价机制则复核改判 |
| K40 SIGTSTP / K12 Ctrl+Z 挂起 | JVM 无公共 signal API（`SessionShutdownHook.java:7-9` 在案） | **窄差 D**（登记既有判定） |
| R33 ASCII/字素快路径 | `TextLayout` 无 `Intl.Segmenter` 等价性能诉求；功能正确性已有 | **窄差 D**（性能契约） |
| 行 10 `Text.setText` 早退 | 同上 | 并入 R33 窄差 |
| 行 9 终端识别 | Java 无终端识别面（K35 缺失） | 随 K35 段实现后复核 |

## 7. 分段实施计划（由观察影响排序；每段先设计稿、再实施、定向测试收口）

| 段 | 范围 | 新增/触达单元 | 预期读数增量 |
|---|---|---|---|
| **W1 状态面** | C5 loader/spinner(w3)、C6 working 指示器(w3)、C7/C8 loaders(w1×2)、C10 文案复核 | 补齐「每轮都走」的最大缺口 | +7~8 |
| **W2 工具/消息卡** | R10 diff 渲染(w3)＋缩进剥离、R12 工具执行卡(w2)、R17/R18/R19 三卡(w2/w1/w1)＋transformer 接线、R25(w1)、R26(w1)、K37+C14(w1×2)、bash 迁入 chat | 渲染特性域主缺口 | +10~12 |
| **W3 渲染缓存契约** | R31 块缓存(2)、R32 注释隐藏(1)、R33(1)＋invalidate 前缀；C21/R30 按 §6 出窄差 | 应用层可做面落地＋框架面出 D | +3~4 |
| **W4 补全与选择器** | S16 门控补偿(w1)、K41(1)、K25 @file(w2)、K26 粘贴标记(1)、K31 jump mode(1)、extension-selector 窗口化(1)、C2 多选复核 | 交互域 | +5~7 |
| **W5 平台/协议** | K35 终端探测(1)＋Alacritty、K34 原生剪贴板(1)、K39 OSC 7501(1)＋custom().blocked、K32 存疑收窄、K38 设置项(1) | 平台域 | +4~5 |
| **W6 全屏长尾** | S2a 搜索(1)＋K21、S2b 选择复制(1)＋K22、S2c flash(1)、C3 settings-list(2)、C4 input(1)、C13(2)、C15(1)、C20 假光标(1)、K18(2) | 组件/交互域 | +10~12 |
| **W7 屏幕与图** | S2d＋R14 图片协议(1×2)、R15 粘贴(1)＋K14、S18 向导(1)、S21 show-images(1)、K13(1)、R7 mermaid(1)、R8 LaTeX(1) | 低频大件 | +6~8 |
| **W8 新增小单元＋复核收尾** | §4 漏项 1/2/3/8/9/10、R22/R27 彩蛋(1+1)、K17 keybindings.json(1)、S27/S28/R28/W16 存疑复核、10 行旧基线扫尾 | 收口读数 | +5~8 |

预期收口读数（不含窄差出分母调整）：**178.5＋~50 ≈ 228/259+ ≈ 88%**；正式数字按每段落地逐行回算，窄差段（§6）按 D 登记不进分子。

## 8. 验收口径

- 每段：设计稿落盘（本稿 §7 索引）→ 实施 → `mvn test -pl <触达模块> -am` 全绿 → map/05 行改判＋docs/05 §1.2/`docs/09` 进度同步。
- 全层收口：`mvn clean verify -Dfrontend.skip=true` 全绿；#20（L6）随 W6 的 context footer 落地改判。
- 提交切分按 200–500 行/提交，`feat/fix(tui|coding-agent): ...`；改动留工作区，等明确指示再提交。
