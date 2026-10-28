# docs/39 — examples/ 可执行验收规格：全量盘点与分批方案

## §0 背景摘要

批 27（docs/38）闭环后，docs/06 §3.3 还剩三个未拍板变量。本文档拍板**变量①：examples/ 16,033 行可执行验收规格**。

2026-10-28 用户拍板两件事：

1. 变量①选「**全做**」（放弃「代表子集」与「不做」两个选项）。
2. 落地形态选「**Java 移植＋验收测试**」（放弃「仅验收测试夹具」与「混合」）。

同场问答裁决：**pi-java 不做 TS 扩展运行时**（裁决②，理由见 §1）。

本文档是盘点稿与分批方案，不是批次设计稿。用户审核本文档后，每批另写设计稿（docs/40 起），流程与批 1–27 相同。

**术语**（首次出现附释义）：

- **examples/**：pi 仓库 `packages/coding-agent/examples/` 目录，105 个 `.ts` 文件、16,033 行。它既是示例库，也是 pi 扩展 API 的**行为规格**——每个示例钉死一组可断言的扩展行为。
- **锚点**：pi 仓库的对齐基准提交，当前为 `ea448f454`（2026-10-11）。本文档全部行数与代码取证均取自该提交。已核实 pi 工作树就在锚点，无漂移。
- **虚拟模块**：pi 运行时把 `@earendil-works/pi-coding-agent`、`pi-tui`、`pi-ai`、`typebox` 等 import 拦截到宿主进程内模块实例的机制（`core/extensions/virtual-modules.ts`）。示例里的每个 import 都是对一张 API 面的引用。
- **w3 / F 类**：map/04 的权重 3 层（每轮对话都走的默认路径）与「整块缺失」分类。examples 同时占两行：E5-33（权重 3）与 F 域 #22（权重 3）。
- ** Operations 注入面**：pi 内置工具工厂 `createBashTool(cwd, {operations})` 的依赖注入点，允许扩展替换文件/进程执行后端。pi-java 目前**没有**这个面。

---

## §1 拍板记录（裁决）

### 裁决①：变量① 拍板「全做」

【场景】docs/06 §3.3 变量① 给三个选项：全做 ≈ +2 批、代表子集 ≈ +1 批、不做 −2 批（F 域行出分母）。
【决定】全做。105 件全部移植或经裁决登记例外（§6 分组表逐件给处置）。
【风险/代价】「+2 批」是盘点前的估计。**盘点后重估为 8±2 批**（§7）。原因：105 件里有 9 件 L 级（>400 行或含子系统逻辑），且揭出一张 pi-java 尚未落地的 API 面（Operations 注入，§4-⑨）。[内部，模型不可见]

### 裁决②：不做 TS 扩展运行时

【场景】pi 的示例是 TypeScript 文件，由 jiti 在运行时变换后加载，与宿主同进程同堆，import 经虚拟模块拿到宿主的活对象。用户问：pi-java 的扩展能否支持 TS？
【决定】不支持。维持纯 Java 扩展（ServiceLoader + `loadJar`）。
【风险/代价】三条排除理由：① 纯 JVM 路线只有 GraalJS polyglot，等于再造一个 E5 域规模的 JS 桥（E5 域约 10 批的体量），TamboUI 组件模型还要协议化；② GraalJS 进 native image 会进一步放大体积（native 已判不现实：exe 149MB）；③ 第二条行为面意味着第二轮对齐工作，违背「判据＝行为与 pi 相同」。若未来要兼容用户现成的 TS 扩展，唯一可议路线是 GraalJS＋预编译＋虚拟模块桥，需单独立 epic。[内部，模型不可见]

### 裁决③：落地形态——新 Maven 模块 `pi-java-examples`

【场景】105 件移植件放在哪：进产品模块会污染发行；只进测试目录则不可加载运行，违背「可执行验收规格」的定位。
【决定】新增第 12 个模块 `pi-java-examples`（依赖 `pi-java-coding-agent` 与 `pi-java-tui`）。示例扩展是 main 源码集的 `PiExtension` 实现，验收测试在同模块 test 源码集。CLI 发行不携带该模块；测试经 `ExtensionManager` 直接实例化或 `loadJar` 加载。
【风险/代价】模块数 11→12；`-e` 参数在 pi 侧接受 `.ts` 路径/目录，java 侧接受 JAR——登记窄差（路径形态差异，语义一致：指定路径加载扩展）。[内部，模型不可见]

---

## §2 盘点口径与方法

- 对象：`packages/coding-agent/examples/` 下全部 **105 个 `.ts`**（锚点 `ea448f454`），合计 **16,033 行**（map/04 口径复核一致）。
- 非 `.ts` 附属件（不计入 105，但随件处置）：3 个 README、5 个带 `package.json` 的目录（custom-provider-anthropic / custom-provider-gitlab-duo / gondolin / sandbox / with-deps）、subagent 的 4 个 agent 定义 `.md` 与 3 个 prompt 模板、doom-overlay 的 WASM 引擎（`doom.wasm` + 构建产物）、dynamic-resources 的 3 个资源文件。
- 形态分布：**单文件扩展 70 件**；**目录型扩展 9 个**（16 件 .ts）；**SDK 演示 14 件**（非扩展，`createAgentSession()` 程序化用法）；**RPC 客户端 2 件**；**Chord 插件 1 个**（3 件 .ts，experimental 第三代机制）。
- 方法：四路并行逐件通读原文（无抽样），逐件记录 import 面、事件订阅、注册点、验收行为点、体量（S<100 / M 100–400 / L>400 或含子系统）、对 pi-java 的依赖风险。

---

## §3 验收规格总则（每件的落地标准）

每件示例的闭环标准四条：

1. **移植本体**：`pi-java-examples` 模块下的 Java 扩展类，行为以 pi 锚点 `file:line` 为准；模型可见文案逐字。
2. **验收测试**：同模块测试，断言 §6 表「核心验收点」列；**不用活 LLM**（走既有假模型夹具）；TUI 件走 TamboUI 测试壳；CLI 行为件（subagent）走进程级 e2e。
3. **可加载**：经 ServiceLoader 或 `loadJar` 能被 `ExtensionManager` 装配，事件/命令/工具真实注册。
4. **台账回填**：批次闭环时按件翻 map/04 E5-33 明细（E5-33 行翻对齐以「105 件处置完毕」为条件，F#22 同）；例外件（§5）以裁决登记，不算缺失。

---

## §4 API 面压力并表（105 件对 pi-java 的需求汇总）

| # | 需求面 | 来源示例 | pi-java 现状 |
|---|---|---|---|
| ① | 事件返回值协议：`tool_call{block,reason}`、`context{messages}`、`before_agent_start{systemPrompt}`、`user_bash{operations/result}`、`session_before_*{cancel}`、`session_before_compact{compaction}`、`input` 三态 | 守卫/改写类 ~20 件 | ✅ 已落（D 域满分，批 4/5/23） |
| ② | 注册面：`registerTool/Command/Provider/Flag/Shortcut/MessageRenderer/EntryRenderer/VirtualModel`、`pi.events` | 几乎全量 | ✅ 已落（批 16/17/26/27） |
| ③ | 会话持久化：`appendEntry(customType)`＋`sessionManager.getEntries/getBranch`＋`session_tree` 回放 | plan-mode/preset/tools/todo/游戏存档 | ✅ 已落 |
| ④ | UI 注入面：`setHeader/Footer/Widget/Status/Title/WorkingIndicator/HiddenThinkingLabel/EditorComponent`、`ctx.ui.custom`、overlay 系统 | UI 类 ~20 件 | ⚠️ 注入层已落（批 17），**TUI 消费臂挂账 E5-31**——本变量顺带收 |
| ⑤ | 编辑器扩展：`CustomEditor` 可子类化（paddingX/borderColor/getText 覆写） | modal/rainbow/border-status-editor | ❓ 待核（批 28 前取证） |
| ⑥ | 工具渲染覆盖：`create*ToolDefinition`（保执行换渲染）、`renderShell`、`executionMode:"sequential"`、terminate | minimal-mode/built-in-tool-renderer/tool-override/structured-output/tic-tac-toe | ❓ 部分待核 |
| ⑦ | 旁路 LLM 调用：`modelRegistry.complete/find/hasConfiguredAuth/classify` | summarize/qna/handoff/custom-compaction/jev-router | ✅ 大体已落（classify 随批 27 待核） |
| ⑧ | provider 协议：`registerProvider`＋pi-ai `streamSimple`/compat 工厂/OAuth 三段式 | custom-provider×2、debug-provider、provider-payload | ✅ 已落（ai 模块 94.5%） |
| ⑨ | **Operations 注入面**：`createBashTool(cwd,{operations})` 等 7 个工具工厂的文件/进程后端替换 | ssh/sandbox/gondolin/bash-spawn-hook | ❌ **缺失——pi-java 无 `*Operations` 类型**。本变量揭出的最大整块新工作 |
| ⑩ | TamboUI 组件对齐：`Editor/SelectList/SettingsList/Input/Text/Markdown/Container`、`visibleWidth/truncateToWidth/wrapTextWithAnsi`、`Key/matchesKey`、Kitty 键盘释放事件、假光标 `CURSOR_MARKER` | TUI 类 ~25 件 | ⚠️ 部分（EventParser 已解 Kitty CSI-u；组件逐件核） |
| ⑪ | 压缩钩：`session_before_compact` 的 `preparation` 结构与 `{compaction}` 返回 | custom-compaction/trigger-compact | ✅ 已落（批 5 引擎钩） |
| ⑫ | RPC 扩展 UI 桥：`extension_ui_request/response` 八方法＋`RpcClient` 封装 | rpc-demo/rpc-extension-ui/rpc-client | ✅ 桥已落（批 17）；`RpcClient` SDK 类待核 |
| ⑬ | SDK 程序化面：`createAgentSession`/ResourceLoader 接口全表/运行时热替换 | sdk 14 件 | ✅ 大体已落（12-full-control 是接口清单参照） |

结论：105 件里 **约 85 件的全部依赖面已落地**，可直接移植；④⑤⑥⑩ 需逐件核或随批补齐；**⑨ 是唯一需要新建的子系统级地基**（建议独立成批，§7 批 30）。

---

## §5 外部依赖处置（裁决候选，随批拍板）

| 示例 | 外部依赖 | 处置建议 |
|---|---|---|
| gondolin/ | `@earendil-works/gondolin`（QEMU micro-VM，Node≥23.6） | **不移植 VM 本体**。把它降级为 Operations SPI 的参考契约：Java 侧落 SPI＋一件「本地回环」演示件。QEMU 路由登记窄差 |
| sandbox/ | `@anthropic-ai/sandbox-runtime`（macOS sandbox-exec / Linux bubblewrap） | 同上：落配置合并＋`user_bash`/工具替换两条钩，沙箱执行后端换 Java 可实现的降级版（或直接放行＋警告），OS 沙箱登记窄差 |
| doom-overlay/ | `doom.wasm`（WASM 引擎） | **只移植渲染壳＋WAD 定位＋键位映射**；引擎换假帧源。引 Chicory（Java WASM 运行时）是备选，随批拍板 |
| custom-provider-anthropic | `@anthropic-ai/sdk` | 无问题：pi-java 的 ai 模块自有 Anthropic 流式实现可复用 |
| with-deps/ | npm 包 `ms`（演示扩展自带依赖） | Java 对位＝扩展 JAR 自带依赖（fat jar / 模块 classpath）。演示「扩展包带独立依赖可加载」即可 |
| mac-system-theme / notify | macOS osascript / OSC 777·99 / Windows toast | 平台分发逻辑保留，macOS 臂在 Windows 验证环境只能走「非 mac 不动作」臂；登记窄差 |

---

## §6 全量盘点总表（105 件）

分组即分批的依据。「体量」：S<100 行 / M 100–400 / L>400 或含子系统。「批次」见 §7。

### 组 1：事件守卫与输入改写（16 件，全部 headless 可测）→ 批 28

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| hello | 26 | S | 最小自定义工具：typebox 参数、content/details 返回 |
| permission-gate | 34 | S | tool_call 拦危险 bash（rm -rf/sudo）；无 UI 默认 block |
| protected-paths | 30 | S | tool_call 拦 .env/.git/node_modules 写；block+reason |
| confirm-destructive | 59 | S | session_before_switch/fork 返回 {cancel}；无 UI 放行 |
| dirty-repo-guard | 56 | S | 脏工作区拦截会话切换；非 git 仓库放行 |
| project-trust | 64 | M | project_trust 返回 trusted 三态；首个非 undecided 胜出 |
| input-transform | 43 | S | input 三态 continue/transform/handled；extension 源防递归 |
| input-transform-streaming | 39 | S | streamingBehavior==="steer" 跳过昂贵处理 |
| inline-bash | 94 | S | `!{cmd}` 内联展开；30s 超时；失败替换 [error:] |
| pirate | 47 | S | before_agent_start 条件追加 systemPrompt；关闭返回 undefined |
| claude-rules | 86 | S | session_start 扫 .claude/rules；无规则不改 prompt |
| prompt-customizer | 49 | S | systemPromptOptions.sections.tool_guidance 条件增删 |
| event-bus | 43 | S | pi.events 扩展间总线；/emit 广播与接收 |
| provider-payload | 18 | S | before_provider_request/after_provider_response 落日志 |
| structured-output | 65 | S | terminate:true 工具结果直接结束回合 |
| dynamic-tools | 74 | S | session_start 与运行时动态注册工具；同名去重 |

### 组 2：命令与会话动作（12 件）→ 批 29

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| commands | 72 | S | getCommands 枚举＋source 过滤＋参数补全 |
| session-name | 27 | S | setSessionName/getSessionName |
| bookmark | 50 | S | setLabel 打标供 /tree 导航；逆序移除 |
| shutdown-command | 63 | S | ctx.shutdown 延迟到空闲；工具结果先回 LLM 再退出 |
| send-user-message | 97 | S | 空闲直触发；流式必须 deliverAs；isIdle |
| file-trigger | 41 | S | 文件 watch 注入 triggerTurn；读后清空 |
| auto-commit-on-exit | 49 | S | session_shutdown 自动生成提交；非 git 返回 |
| trigger-compact | 50 | M | getContextUsage 跨阈值触发一次；手动带指令 |
| git-checkpoint | 53 | M | turn_start stash 检查点；fork 恢复；entryId 追踪 |
| git-merge-and-resolve | 115 | M | agent_end 检查 merge；冲突标记解析为 followUp |
| reload-runtime | 37 | M | ctx.reload；工具经 followUp 排队命令触发 |
| tools | 146 | M | /tools SettingsList 开关；分支隔离持久化恢复 |

### 组 3：UI 注入与渲染（19 件，TUI 消费臂随收 E5-31）→ 批 31

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| status-line | 32 | S | setStatus 同 key 覆盖；turn 计数 |
| model-status | 31 | S | model_select 三来源；restore 源不弹通知 |
| system-prompt-header | 17 | S | getSystemPrompt 长度入状态条 |
| hidden-thinking-label | 53 | S | setHiddenThinkingLabel；undefined 恢复默认 |
| working-message-test | 25 | S | setWorkingMessage 跨 loader 重建持久 |
| working-indicator | 123 | M | 五模式 frames/intervalMs；非法参数 Usage error |
| custom-header | 73 | S | setHeader 替换；undefined 恢复内置 |
| custom-footer | 64 | M | footerData git 分支通道；dispose 退订 |
| widget-placement | 9 | S | setWidget 上/下 placement |
| titlebar-spinner | 58 | S | setTitle 终端标题动画；settled 停止 |
| notify | 57 | S | agent_settled 发通知（非 agent_end）；平台分发 |
| mac-system-theme | 47 | S | 外观轮询 setTheme；非 macOS 不动作（窄差） |
| message-renderer | 59 | S | registerMessageRenderer＋sendMessage display |
| entry-renderer | 41 | S | appendEntry＋registerEntryRenderer；LLM 不可见 |
| debug-provider | 90 | M | provider_stream_event 捕获＋entry 持久化＋渲染 |
| modal-editor | 85 | S | CustomEditor 子类：Esc 模态切换，normal 吞字符 |
| rainbow-editor | 88 | S | CustomEditor：关键词触发扫光动画 |
| border-status-editor | 150 | S | setEditorComponent 整体替换＋边框状态行 |
| overlay-test | 153 | M | overlay 假光标/Focusable/CJK·emoji 宽度对齐 |

### 组 4：工具渲染覆盖与交互工具（8 件，依赖 §4-⑥ 核面）→ 批 32

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| minimal-mode | 354 | M | 7 内置工具渲染整体覆盖；collapsed 计数形 |
| built-in-tool-renderer | 225 | M | 保执行换渲染；isPartial/expanded 两臂 |
| tool-override | 144 | M | 覆盖内置 read 加日志/门禁 |
| truncated-tool | 195 | M | truncateHead 50KB/2000 行；截断落临时文件 |
| bash-spawn-hook | 30 | M | createBashTool spawnHook 改命令/env |
| todo | 297 | M | 状态从分支 toolResult.details 重放；/todos 组件 |
| question | 286 | M | ui.custom 选项组件；sequential 执行模式 |
| questionnaire | 448 | L | 多问题 tab 导航；allowOther 伪选项；非 TUI 报错 |

### 组 5：旁路调用与会话重组（7 件，依赖 §4-⑦）→ 批 33

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| summarize | 199 | M | modelRegistry.complete 旁路摘要；三 warning 臂 |
| qna | 118 | M | BorderedLoader 可中止；问题提取注入编辑器草稿 |
| handoff | 190 | M | compaction 重组分支；newSession parentSession 链 |
| custom-compaction | 117 | M | session_before_compact preparation 解构；空摘要回退默认 |
| preset | 436 | L | presets.json 双层；快照恢复；--preset flag 优先 |
| plan-mode/（2 件） | 558 | L | 工具快照/恢复；bash 白名单拦截；步骤提取三选一 |
| tic-tac-toe | 1008 | L | sequential 落点正确；非法 play isError 不回滚；分支重建 |

### 组 6：Operations 注入面地基＋三件（依赖 §4-⑨ 新建）→ 批 30（先于组 4/5 之外独立成批）

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| **Operations SPI 地基**（非示例，新建） | — | — | 7 内置工具的 Read/Write/Edit/Bash/Ls/Find/Grep Operations 接口＋默认实现＋user_bash {operations} 返回臂 |
| ssh | 220 | M | 4 工具 operations 路由远端；系统提示 cwd 行改写 |
| sandbox/ | 321 | L | 配置双层合并；bash 与用户 ! 同钩；初始化失败降级（OS 沙箱窄差） |
| gondolin/ | 531 | L | **只落 SPI 契约演示**（本地回环件）；QEMU 路由登记窄差 |

### 组 7：provider 与虚拟模型（5 件）→ 批 34

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| custom-provider-anthropic/ | 617 | L | OAuth PKCE 伪装 Claude Code；消息转换；stop_reason 映射 |
| custom-provider-gitlab-duo/（2 件） | 487 | L | compat 工厂委托；direct_access token 缓存 25 分钟 |
| jev-router | 113 | M | registerVirtualModel reason 三臂；上轮 edit 切 Luna（批 27 面的首个真用户） |
| debug-provider | — | — | （已列组 3，此处不重复计） |
| provider-payload | — | — | （已列组 1，此处不重复计） |

### 组 8：TUI 重度与游戏（6 件，依赖 §4-⑩ TamboUI 对齐＋E5-31 overlay）→ 批 35

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| snake | 343 | M | tick/requestRender 循环；存档恢复；方向键双映射 |
| overlay-qa-tests | 1450 | L | 9 anchor 位；overlayOptions 全参数；堆叠/动画/焦点 |
| space-invaders | 560 | L | Kitty 释放事件平滑移动；50ms tick；存档 |
| doom-overlay/（5 件） | 538 | L | 渲染壳＋WAD 定位＋键位映射；引擎假帧源（裁决） |
| interactive-shell | 196 | M | user_bash 短路内建；TUI stop/start 生命周期 |
| github-issue-autocomplete | 185 | M | addAutocompleteProvider 装饰链；#1234 前缀/模糊匹配 |

### 组 9：SDK 演示 14 件＋RPC 3 件（验收形态＝程序化 API 行为测试）→ 批 36

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| sdk/01–05 | 253 | S×5 | 最小会话/模型选择/prompt 替换/技能过滤/工具白名单 |
| sdk/06–10 | 334 | M | 扩展工厂装载/上下文文件/模板/认证路径/settings 覆盖 |
| sdk/11–13 | 193 | M | 会话四种持久化/ResourceLoader 全接口清单/运行时热替换 |
| sdk/14-codemode-mcp | 61 | M | codemode＋tool_search＋MCP 工厂装配（依赖 B161 codemode 落地，挂账衔接） |
| rpc-client | 35 | S | RpcClient start/promptAndWait/onEvent/stop |
| rpc-demo | 118 | M | RPC 模式 UI 方法八件；tool_call 阻断协议 |
| rpc-extension-ui | 661 | L | extension_ui_request/response 全方法对（客户端可用假 WS/假进程驱动） |

### 组 10：进程级与插件（2 项，特殊形态）→ 批 37 或裁决例外

| 示例 | 行数 | 体量 | 核心验收点 |
|---|---:|---|---|
| subagent/（2 件＋7 附属 .md） | 1195 | L | spawn 自身 CLI（--mode json -p --no-session 等 pi-java 已有全数 flag）；JSON 行协议解析；single/parallel/chain 三模式；0600 临时文件（Windows 窄差） |
| plugins/pi-example-plugin（3 件） | 68 | — | **建议不做**：Chord facet 是 pi 第三代实验机制，pi-java 无 chord 模块、机制本身未复刻。登记 C 类「机制不做」裁决候选，105 件分母 −3 |

### 组外注记

- `custom-provider-gitlab-duo/test.ts`（82 行）是独立 node 测试脚本，验收并入组 7 同件。
- `todo.ts` 已在组 4（盘点确认存在，297 行）。

---

## §7 分批方案（批 28 起，重估 8±2 批）

| 批 | 主题 | 件数 | 行数 | 前置 |
|---|---|---:|---:|---|
| 28 | 事件守卫与输入改写（组 1） | 16 | ~880 | 无（全 headless，事件面已满分） |
| 29 | 命令与会话动作（组 2） | 12 | ~760 | 批 28 |
| 30 | **Operations SPI 地基**＋ssh/sandbox/gondolin（组 6） | 3＋地基 | ~1,070 | 无依赖但建议早做——组 4 的 bash-spawn-hook 也要它 |
| 31 | UI 注入与渲染（组 3） | 19 | ~1,300 | E5-31 TUI 消费臂随批收 |
| 32 | 工具渲染覆盖与交互工具（组 4） | 8 | ~1,650 | 批 30（bash-spawn-hook） |
| 33 | 旁路调用与会话重组（组 5） | 7 | ~2,300 | 无硬前置 |
| 34 | provider 与虚拟模型（组 7） | 4 | ~1,200 | 无 |
| 35 | TUI 重度与游戏（组 8） | 6 | ~3,250 | 批 31（overlay/组件面） |
| 36 | SDK 14＋RPC 3（组 9） | 17 | ~1,500 | sdk/14 挂 B161（codemode）——B161 未落则该件留尾 |
| 37 | subagent 进程级 e2e（组 10） | 1 | ~1,200 | pi-java CLI e2e 夹具 |

**合计处置 105−3（plugin 裁决不做候选）＝102 件**，~16.0k 行 pi 源码 ⇒ 预计 Java 侧 12–16k 行移植＋8–10k 行测试。

**与 docs/06 §3.3 原估计的偏差**：原估「全做 ≈ +2 批」是盘点前数字。盘点后 9 件 L 级＋Operations 地基＋E5-31 随收，重估 **8–10 批**。docs/06 §3.3 变量① 在本文档审核通过后回填为「已拍板全做，重估 8–10 批（docs/39）」。

**账面影响**：E5-33（权重 3）与 F#22（权重 3）两行以「102 件处置完毕」翻对齐，模块去重口径 +6.0（446.0→452.0/485 ≈ 93.2%）；期间各批若顺带翻 E5-31 等挂账行另计。

**裁剪阀**：若某批实际体量超线，按组内 S→M→L 顺序往后排，组序不变。

**2026-10-28 修订（批 28 闭环后拍板）：批 29–37 合并至 6 批（批 29–34）**。合并轴＝依赖/风险同域；新批号映射：

| 新批 | 内容 | 原批 | 件数 | 行数 |
|---|---|---|---:|---:|
| 29 | 命令与会话动作（组 2）＋provider 与虚拟模型（组 7） | 29＋34 | 16 | ~1,960 |
| 30 | Operations SPI 地基＋ssh/sandbox/gondolin（组 6） | 30 | 3＋地基 | ~1,070 |
| 31 | UI 注入与渲染（组 3）＋工具渲染覆盖与交互工具（组 4） | 31＋32 | 27 | ~2,950 |
| 32 | 旁路调用与会话重组（组 5） | 33 | 7 | ~2,300 |
| 33 | TUI 重度与游戏（组 8） | 35 | 6 | ~3,250 |
| 34 | SDK 14＋RPC 3（组 9）＋subagent（组 10） | 36＋37 | 18 | ~2,700 |

不合批 30/32/33（各自单批已够重）；组内内容与各组处置裁决不变；裁剪阀、E5-33/F#22 翻行条件（102 件处置完毕）不变。批 29 设计稿＝docs/41。

**2026-10-29 再修订（用户裁决：排期降为最尾）**：全做裁决（裁决①）不变，但 examples 六批**排在所有其他收口批之后**——理由：① pi-java 以 web 为主要交互方式；② examples 是扩展 API 的验收规格而非用户功能流程，有无不影响实际功能。原批 29–34 编号让位新主线批（docs/06 §3 序 23–27），本方案六批成为 docs/06 §3 序 28，启动时按当时包号重排；上表组 2–10 的内容、合并映射、裁剪阀、翻行条件全部不变。

---

## §8 本文档审核通过后的动作

1. docs/06 §3.3 变量① 标「已拍板（2026-10-28，docs/39）」＋重估批数；§1 总览「总批次估计」更新为 27＋8~10＋余两变量。
2. docs/04 登记：变量① 拍板记录＋裁决②（TS 不支持）＋裁决③（pi-java-examples 模块）＋组 10 plugin 不做候选（待拍板）。
3. map/04：E5-33 行注记「全做拍板，处置口径 102 件，docs/39」；F#22 同。
4. 下一包号 docs/40＝批 28 设计稿（组 1，16 件逐件带 pi file:line 与逐字代码）。
