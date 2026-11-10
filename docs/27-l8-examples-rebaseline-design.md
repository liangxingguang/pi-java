# 27 - L8 examples 新锚点重测与移植设计

> 【背景摘要】本文档在目标锚点 `56cae7823` 重新清点 `packages/coding-agent/examples`，给出新分母、逐件判定、宿主 API 缺口和 W1–W8 移植工作包。旧“16/102”只作历史进度，不再作为翻“对齐”的条件。
>
> **文档状态：设计待审。本稿只授权审核，不授权实施代码。**

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`（`PI_PIN`）
- pi-java 重测时 HEAD：`d0178c7b`（工作区干净）
- 上游依据：`docs/08` §5 L8、`docs/07` §4
- 用户已裁决（本稿前置）：
  1. 外部运行时示例出分母并登记窄差（2026-11-08）；
  2. 自定义 provider 示例**入分母**，只移植注册形状与流式事件，不复刻真实 OAuth 网络（2026-11-08）。

## 0. 清点口径

1. **计分单位 = examples 子树中的一个 TS 文件**（沿用 `docs/07` §4 的“105/102 件”口径；目录型示例按文件逐件计）。
2. 非 TS 资产（README、`package.json`、`package-lock.json`、`.gitignore`、`SKILL.md`、`dynamic.md`、`doom.wasm`／`doom.js`／`build.sh`／`doomgeneric_pi.c`）不单独计分，只作为所属单元的行为夹具。
3. 判定四类：`保留`（pi 侧未变且 Java 已移植）、`已修改`（pi 侧行为变化，需重新移植）、`已删除`（旧锚点有、新锚点无）、`出分母`（R5／用户裁决的外部运行时）。
4. 判定证据一律来自锚点源码：`git show 56cae7823:<path>`；本仓工作树 `d:\workplaceForai\pi` 的 HEAD 即锚点且干净，可直接 Read。
5. Java 侧判定以当前 `pi-java-examples` 与扩展宿主接口为准；“已移植”必须同时有 main 类与独立验收测试。

## 1. 新分母与总体结论

### 1.1 文件数变化（已核实）

```text
旧锚点 ea448f454：examples 142 文件，其中 TS 105
新锚点 56cae7823：examples 137 文件，其中 TS 102
删除 5 文件：plugins/pi-example-plugin/{README.md,package.json,src/contract.ts,src/session.ts,src/tui.ts}
```

删除的 `src/session.ts` 顶部 `import ... from "@earendil-works/chord"`，确认属 Chord plugin 组，按 R5 出分母。

### 1.2 102 件判定汇总

| 判定 | 件数 | 说明 |
|---|---|---|
| 保留（已移植且 pi 未变） | 16 | 既有 Java 类与测试继续有效，见 §2.1 |
| 已修改（需重新移植） | 9 | 见 §2.2 逐条差异 |
| 未移植（新端口） | 63 | 见 §2.5 分组 |
| 出分母（外部运行时，用户裁决） | 8 | sandbox 1＋gondolin 1＋doom-overlay 5＋with-deps 1，登 D232 |
| 出分母（R5） | 1 | `sdk/14-codemode-mcp.ts` |
| 出分母（范围面主动，用户裁决 2026-11-09） | 5 | overlay 2＋游戏 2＋平台 1，登 D237，见 §2.4b |
| **新锚点合计** | **102** | 16+9+63+8+1+5 |

> 旧锚点的 3 个 Chord plugin TS（`contract.ts`、`session.ts`、`tui.ts`）不在新锚点清单，只解释 105→102 的差额，不进入任何分母（见 §2.3）。
>
> **新分母 = 102 − 8 − 1 − 5 = 88 件。**
> 清点时对齐 **16/88 = 18.18%**；待实施 **72 件 = 9 件已修改 + 63 件未移植**（实施进度以 `docs/04`／`docs/06` 为准：W1＋W2 共 13 件已于 2026-11-09 完成，现 29/88＝32.95%、余 59 件 = 5 已修改＋54 未移植）。
> 计数校验：§2.5 的 W 分组为 3+6+7+8+13+4+5+17 = 63；§4 的 W 覆盖 63＋9 = 72。两项闭合。

## 2. 逐件判定

### 2.1 保留（16 件，pi 侧未变）

| pi 文件 | Java main | 验收测试 | 备注 |
|---|---|---|---|
| `hello.ts` | `HelloExtension` | `HelloExtensionTest` | 工具注册与 echo |
| `structured-output.ts` | `StructuredOutputExtension` | `StructuredOutputExtensionTest` | JSON Schema 工具参数 |
| `dynamic-tools.ts` | `DynamicToolsExtension` | `DynamicToolsExtensionTest` | 动态增删工具 |
| `permission-gate.ts` | `PermissionGateExtension` | `PermissionGateExtensionTest` | `tool_call` 拦截 |
| `protected-paths.ts` | `ProtectedPathsExtension` | `ProtectedPathsExtensionTest` | 路径保护 |
| `confirm-destructive.ts` | `ConfirmDestructiveExtension` | `ConfirmDestructiveExtensionTest` | 会话切换确认 |
| `dirty-repo-guard.ts` | `DirtyRepoGuardExtension` | `DirtyRepoGuardExtensionTest` | git 脏树保护 |
| `project-trust.ts` | `ProjectTrustExtension` | `ProjectTrustExtensionTest` | 信任决策 |
| `input-transform.ts` | `InputTransformExtension` | `InputTransformExtensionTest` | `input` 变换 |
| `input-transform-streaming.ts` | `InputTransformStreamingExtension` | `InputTransformStreamingExtensionTest` | 流式输入变换 |
| `inline-bash.ts` | `InlineBashExtension` | `InlineBashExtensionTest` | `user_bash` |
| `pirate.ts` | `PirateExtension` | `PirateExtensionTest` | 系统提示改写 |
| `claude-rules.ts` | `ClaudeRulesExtension` | `ClaudeRulesExtensionTest` | `before_agent_start` 注入 |
| `prompt-customizer.ts` | `PromptCustomizerExtension` | `PromptCustomizerExtensionTest` | 系统提示定制 |
| `event-bus.ts` | `EventBusExtension` | `EventBusExtensionTest` | 扩展间总线 |
| `provider-payload.ts` | `ProviderPayloadExtension` | `ProviderPayloadExtensionTest` | `before_provider_request` 改载荷 |

处置：**W0 复核**。不改代码；跑既有测试；若宿主接口演进导致编译或断言失效，按回归缺陷修复并记录。

### 2.2 已修改（9 件，pi 侧行为变化）

| pi 文件:行 | 新行为（锚点逐字取证） | Java 现状／对位 | 处置 |
|---|---|---|---|
| `extensions/custom-provider-anthropic/index.ts:45,457-531` | 引入 `ToolCallArgumentsStream`；按 Anthropic block index 维护 `openBlocks`，`input_json_delta` 只走一次增量；结束块 `Object.assign(block, open.arguments.finish())`，畸形参数写入 `argumentsError` | `com.pijava.ai.stream.ToolCallArgumentsStream`（append/finish，B264 已闭环） | W1 重移植 |
| `extensions/debug-provider.ts:27` | `registerEntryRenderer(ENTRY_TYPE, (entry, { expanded, keyHint }, theme) => …)`，新增 `keyHint` 上下文 | Java `ExtensionEntryRenderer` 无 `keyHint`；既有窄差 **D220**（KeybindingHints 归 L7） | W1 移植文本级，`keyHint` 维持 D220 |
| `extensions/minimal-mode.ts:28,69-71` | 引入 `formatReadLineRange`，read 路径显示 `:start-end` 区间 | `com.pijava.coding.agent.core.ReadLineRange.format(offset, limit)`（L5 W8 已实现） | W4 重移植，直接复用 |
| `extensions/plan-mode/index.ts:200-206` | `before_agent_start` 增加 `if (event.messageType !== "user") return`；custom 消息（后台任务通知）不再重复注入计划上下文 | Java `BeforeAgentStartEvent.messageType()`（L5 W1 已实现 user/custom 联合） | W2 重移植 |
| `extensions/preset.ts:255-256` | 选择列表传 `capturesViewportInput: selectList.capturesViewportInput`，让 PageUp/PageDown 翻列表 | Java `ExtensionComponent.capturesViewportInput()` 已存在 | W3 重移植 |
| `extensions/reload-runtime.ts:22-41` | 工具结果加 `terminate: true`；`agent_settled` 中 `sendUserMessage("/reload-runtime", { expandPromptTemplates: true })` 在 idle 后派发命令 | Java `ToolResult.terminate`、`SendUserMessageOptions.expandPromptTemplates` 已存在；`ctx.reload()` 缺失（B284） | W2 重移植 |
| `extensions/subagent/index.ts:25,91-94` | 同 `formatReadLineRange`，工具调用摘要显示行区间 | `ReadLineRange.format` | W4 重移植 |
| `extensions/todo.ts:279-285` | 工具结果渲染补 `default` 臂：文本结果按 `error` 色渲染 | 纯逻辑，无宿主缺口 | W3 重移植 |
| `extensions/tools.ts:120-121` | 同 `preset.ts`：设置列表转发 `capturesViewportInput` | `ExtensionComponent.capturesViewportInput()` | W3 重移植 |

非行为修改（不产生移植任务）：`extensions/custom-provider-gitlab-duo/package.json`、`dynamic-resources/dynamic.json`、`gondolin|sandbox|with-deps/package*.json`、`examples/README.md`。其中 `dynamic.json` 在 W8 作为夹具复核，`gitlab-duo` 的 package 变化不改变其 TS 行为。

### 2.3 已删除（R5，Chord plugin）

| 旧锚点文件 | 证据 |
|---|---|
| `plugins/pi-example-plugin/src/contract.ts` | 删除（新锚点无此路径） |
| `plugins/pi-example-plugin/src/session.ts` | 删除；文件头 `import ... from "@earendil-works/chord"` |
| `plugins/pi-example-plugin/src/tui.ts` | 删除 |
| `plugins/pi-example-plugin/README.md`、`package.json` | 删除（非计分资产） |

处置：按 `docs/08` 裁决 3 与 `docs/07` §1，Chord 组不做；不进入新分母，不为旧参照物补功能。

### 2.4 出分母（9 件）

| 文件 | 原因 | 编号 |
|---|---|---|
| `extensions/sandbox/index.ts` | 依赖 `@anthropic-ai/sandbox-runtime`（Node 原生沙箱），纯 Java 无对位运行时 | D232 |
| `extensions/gondolin/index.ts` | 依赖 gondolin micro-VM 包与外部镜像 | D232 |
| `extensions/doom-overlay/index.ts` | 依赖 DOOM WASM 构建产物与 WAD 资产，非行为可移植 | D232 |
| `extensions/doom-overlay/doom-component.ts` | 同上 | D232 |
| `extensions/doom-overlay/doom-engine.ts` | 同上 | D232 |
| `extensions/doom-overlay/doom-keys.ts` | 同上 | D232 |
| `extensions/doom-overlay/wad-finder.ts` | 同上 | D232 |
| `extensions/with-deps/index.ts` | 演示 jiti/JS 模块解析；Java 的 JAR/Maven 依赖不是同一行为面 | D232 |
| `sdk/14-codemode-mcp.ts` | codemode 面，R5 | R5 |

> 用户已裁决：这 8 个外部运行时文件出分母并登记窄差；不写降级假移植。R5 的 codemode 文件沿用既有规则。

### 2.4b 出分母（范围面主动，5 件，用户裁决 2026-11-09）

评审结论（2026-11-09）：这 5 件不产出可用断言证明的宿主行为，或平台无对位，移植成本远大于 oracle 价值。处置：出分母并登 **D237**（docs/04 §6），不写降级假移植。

| 文件 | 所属 W | 原因 |
|---|---|---|
| `extensions/overlay-test.ts` | W5 | D99 已裁决 overlay 定位/尺寸/堆叠不移植，验收只剩文本行，无法证明对齐 |
| `extensions/overlay-qa-tests.ts` | W5 | 同 D99；1,450 行 pi 源码只演示 overlay 全参，L7 未闭环时写下的断言必然返工 |
| `extensions/snake.ts` | W6 | 只消费 `appendEntry`＋`registerCommand`＋`ui.custom`，除游戏帧外不验证宿主语义 |
| `extensions/space-invaders.ts` | W6 | 同上 |
| `extensions/mac-system-theme.ts` | W7 | macOS 专属（`osascript` 应用外观），Windows/Linux 无对位产品行为 |

> W5 余下 13 件、W6 余下 4 件、W7 余下 5 件仍按文本级/序列级断言实施（D99/D220 约束不变）；`ExtensionUI` 的 footer/header/status/widget/editor 槽位接通仍由 W5 其余件覆盖。

### 2.5 未移植（63 件，按 W 分组；W1 的 3 件已于 2026-11-09 实施）

下表只列归属与 pi 侧宿主 API 消费面（由锚点源码全量抽取），逐条行为断言在对应 W 实施时逐字取证并落测试。

| W | 件数 | 文件 | 主要 pi API 消费 |
|---|---|---|---|
| W1 | 3 | `custom-provider-gitlab-duo/index.ts`、`custom-provider-gitlab-duo/test.ts`、`jev-router.ts` | `registerProvider`、`registerVirtualModel`、`modelRegistry.find/findOfType/classify` |
| W2 | 6 | `plan-mode/utils.ts`、`trigger-compact.ts`、`custom-compaction.ts`、`send-user-message.ts`、`system-prompt-header.ts`、`handoff.ts`（另 2 件 `plan-mode/index.ts`、`reload-runtime.ts` 属 §2.2 已修改；**W2 整体已于 2026-11-09 实施，docs/51**） | `ctx.compact/getContextUsage/isIdle/getSystemPrompt/newSession/modelRegistry.complete/getBranch` |
| W3 | 7 | `bookmark.ts`、`session-name.ts`、`qna.ts`、`summarize.ts`、`auto-commit-on-exit.ts`、`git-checkpoint.ts`、`tic-tac-toe.ts` | `sessionManager.getLabel/getLeafEntry/getBranch`、`modelRegistry.complete`、`exec` |
| W4 | 8 | `bash-spawn-hook.ts`、`built-in-tool-renderer.ts`、`tool-override.ts`、`truncated-tool.ts`、`subagent/agents.ts`、`ssh.ts`、`interactive-shell.ts`、`github-issue-autocomplete.ts` | `registerTool`（spawnHook）、tool renderer、`addAutocompleteProvider`、`exec`、`ui.custom` |
| W5 | 13 | `custom-footer.ts`、`custom-header.ts`、`border-status-editor.ts`、`status-line.ts`、`model-status.ts`、`hidden-thinking-label.ts`、`working-indicator.ts`、`working-message-test.ts`、`widget-placement.ts`、`message-renderer.ts`、`entry-renderer.ts`、`modal-editor.ts`、`rainbow-editor.ts` | `ui.setFooter/setHeader/setStatus/setWidget/setEditorComponent/setWorking*/theme`、`registerMessageRenderer/registerEntryRenderer` |
| W6 | 4 | `timed-confirm.ts`、`commands.ts`、`question.ts`、`questionnaire.ts` | `ui.custom/select/confirm`、`appendEntry`、MessageRenderer、定时器 |
| W7 | 5 | `notify.ts`、`titlebar-spinner.ts`、`file-trigger.ts`、`git-merge-and-resolve.ts`、`shutdown-command.ts` | OSC 通知/标题（`setTitle`）、`exec`、`ctx.shutdown` |
| W8 | 17 | `rpc-client.ts`、`rpc-extension-ui.ts`、`rpc-demo.ts`、`sdk/01`–`sdk/13`（13 件）、`dynamic-resources/index.ts` | RPC 客户端/服务、`createAgentSession` 等价入口、资源发现 |
| **合计** | **63** | — | — |

> 计数校验：3+6+7+8+13+4+5+17 = 63（未移植净数；W1 的 3 件已实施，§2.4b 的 5 件已出分母）。加上 §2.2 的 9 件已修改，§4 的 W 覆盖 72 件；新分母 88 = 16 保留 + 72 待实施。

## 3. 宿主 API 缺口

### 3.1 必须补的上下文成员（B284）

`ExtensionContext` 当前没有下列 pi 成员（共 11 项）。它们被 W2/W3/W5/W7 直接消费，必须随 L8 补上；实现遵循晚绑定与“无宿主即抛”的既有纪律。

| 缺口 | pi 对位 | 消费示例 | 设计要点 |
|---|---|---|---|
| `model()` | `ExtensionContext.model`（当前模型） | `border-status-editor`、`custom-footer`、`handoff`、`preset`、`qna`、`subagent`、`summarize` | 返回宿主会话当前 model 的只读快照（provider/id/显示名）；无会话抛 |
| `modelRegistry()` | `modelRegistry.find/findOfType/classify/complete/hasConfiguredAuth` | `custom-compaction`、`handoff`、`jev-router`、`preset`、`qna`、`summarize` | 新只读门面；`complete` 走宿主既有补全路径；`classify` 仅供虚拟模型路由；不得写全局默认 |
| `getContextUsage()` | `ctx.getContextUsage()` | `border-status-editor`、`trigger-compact` | 返回 tokens/contextWindow/percent；无会话抛 |
| `compact()` | `ctx.compact()` | `trigger-compact` | 触发宿主压缩；返回结果或失败原因 |
| `reload()` | `ctx.reload()` | `reload-runtime` | 命令上下文可用；工具内不可调用（示例用 `terminate`＋`agent_settled` 绕行，Java 同） |
| `shutdown()` | `ctx.shutdown()` | `shutdown-command` | 触发会话关闭并让 `session_shutdown` 观察者收尾 |
| `isIdle()` | `ctx.isIdle()` | `send-user-message` | 读宿主 run 状态 |
| `getSystemPrompt()` | `ctx.getSystemPrompt()` | `system-prompt-header` | 读当前生效系统提示（含 before_agent_start 合成结果） |
| `thinkingLevel()` | `ctx.thinkingLevel` | `subagent` | 与既有 `getThinkingLevel()` 同值，提供 pi 形成员名 |
| `isProjectTrusted()` | `ctx.isProjectTrusted` | `subagent` | 复用 `settings().isProjectTrusted()` |
| `newSession()` | `ctx.newSession` | `handoff` | 创建新会话并切换（与既有 session 替换路径同源） |

### 3.2 会话只读面补齐（B285）

`ExtensionSessionView` 当前只有 `getEntries()`；`docs/40` 已挂账余 14 成员。L8 至少需要：

`getBranch()`、`getEntry(id)`、`getLabel(id)`、`getLeafEntry()`、`getSessionFile()`。

其余成员按示例真实消费点补，不为凑接口一次性空实现。实现映射到既有 `SessionTree`／持久化视图；无持久面时沿用转录兜底。

### 3.3 Provider 注册与虚拟模型（W1 前置）

- Java `ProviderRegistry.register(provider)` 已有；示例移植只需把“注册形状”映射到 `Provider` 接口，并用脚本化 transport 产生流事件。
- `custom-provider-anthropic` 的 OAuth 面只移植**回调契约**（`onAuth`/`onPrompt`/凭据形状），不做真实网络；测试用可控 fake。
- `jev-router` 复用既有 `registerVirtualModel`；缺的是 `modelRegistry.classify`（并入 B284 的 `modelRegistry()`）。
- `custom-provider-gitlab-duo` 只移植“委托内置 wire”的选择逻辑与注册形状；不复制内置协议实现。

### 3.4 既有窄差沿用，不重号

| 缺口 | 既有编号 | 影响单元 |
|---|---|---|
| `EntryRenderOptions.keyHint` | D220 | `debug-provider` |
| overlay 定位/尺寸/堆叠全参 | D99 | `question`、`questionnaire`（`overlay-test`／`overlay-qa-tests`／游戏两件已按 D237 出分母） |
| 结构化 Theme 对象（Java 只有主题名） | docs/28 §4 主题面窄差 | `border-status-editor`、`status-line`、`model-status`、`preset`、`plan-mode`、`ssh`、`working-indicator` |
| `formatReadLineRange` 不是导出函数 | 无（Java 有 `ReadLineRange.format`） | `minimal-mode`、`subagent`：直接复用 Java 类 |

L8 不新开“万能 overlay/theme”接口；如后续 L7 补齐，再消除 D99/D220。

## 4. W1–W8 工作包

每个 W 是独立审核／实施单元；顺序按依赖排：

```text
W0（复核）→ W1（provider/stream）→ W2（生命周期/压缩）→ W3（会话树）
→ W4（工具执行）→ W7（平台/文件）→ W8（SDK/RPC）→ W5（UI 槽位）→ W6（交互/游戏）
```

W5/W6 放最后：它们依赖扩展 UI 面，且 overlay 定位受 D99 约束，验收以文本行/按键序列为准。

### W0 基线复核（16 件）

- 动作：跑 `pi-java-examples` 全部既有测试；确认 16 个 main 类仍编进 ServiceLoader；确认无真实 home/settings/session 写入。
- 验收：16 个测试全绿；`ServiceLoader` 发现数与既有清单一致。
- 失败处置：按回归缺陷修复，不改变判定。

### W1 provider、流式与虚拟模型（5 件）

- 单元：`custom-provider-anthropic/index.ts`（改）、`debug-provider.ts`（改）、`custom-provider-gitlab-duo/index.ts`、`custom-provider-gitlab-duo/test.ts`、`jev-router.ts`。
- 行为断言（实施时逐字补齐）：
  1. 注册形状：name/baseUrl/models/oauth 可被宿主解析；重复注册替换。
  2. 流事件：`text_*`、`thinking_*`、`toolcall_start/delta/end`、`done` 顺序；`input_json_delta` 只解析一次；畸形最终参数写入 `argumentsError`，不进 `ContentBlock` 原始字段。
  3. `provider_stream_event`/`message_end`/`turn_end` 供 `debug-provider` 落盘取证。
  4. 虚拟模型按阶段路由，物理模型选择可断言。
- 测试：`ScriptedProvider`（流脚本）＋ `FauxTransport`；不联网。
- 依赖：`com.pijava.ai.stream.ToolCallArgumentsStream`；D220 维持。

### W2 启动窗、提示注入、压缩与生命周期（8 件）

- 单元：`plan-mode/index.ts`（改）、`plan-mode/utils.ts`、`reload-runtime.ts`（改）、`trigger-compact.ts`、`custom-compaction.ts`、`send-user-message.ts`、`system-prompt-header.ts`、`handoff.ts`。
- 行为断言：
  1. `before_agent_start` 仅在 `messageType=user` 注入计划上下文；custom 触发跳过。
  2. `terminate: true` 结束 run；`agent_settled` 后 idle 派发 `/reload-runtime`，`expandPromptTemplates` 走命令而非模型输入。
  3. `compact()` 成功/失败路径；自定义压缩返回摘要，失败回退默认。
  4. `isIdle()` 两臂：空闲立即发送、运行中排队。
  5. `getSystemPrompt()` 反映注入后的系统提示；`newSession()` 切换后旧会话事件停止。
- 依赖：B284（`compact/getContextUsage/isIdle/reload/getSystemPrompt/newSession/modelRegistry`）、B285（`getBranch`）。

### W3 会话树、标签与持久化（10 件）

- 单元：`preset.ts`（改）、`todo.ts`（改）、`tools.ts`（改）、`bookmark.ts`、`session-name.ts`、`qna.ts`、`summarize.ts`、`auto-commit-on-exit.ts`、`git-checkpoint.ts`、`tic-tac-toe.ts`。
- 行为断言：
  1. `setLabel/getLabel` 可往返；目标不存在文案逐字。
  2. `getBranch()` 序与恢复会话 active tools 一致；`appendEntry` 不进 LLM 上下文。
  3. `todo` 的 default 渲染臂：文本错误按 `error` 色；空内容不渲染。
  4. `preset/tools` 列表 PageUp/PageDown 由 `capturesViewportInput` 捕获。
  5. `auto-commit-on-exit`/`git-checkpoint` 用假 git 脚本断言命令与工作树状态。
- 依赖：B285（`getBranch/getEntry/getLabel/getLeafEntry/getSessionFile`）、B284（`modelRegistry.complete`）。

### W4 工具语义与执行（10 件）

- 单元：`minimal-mode.ts`（改）、`subagent/index.ts`（改）、`subagent/agents.ts`、`bash-spawn-hook.ts`、`built-in-tool-renderer.ts`、`tool-override.ts`、`truncated-tool.ts`、`ssh.ts`、`interactive-shell.ts`、`github-issue-autocomplete.ts`。
- 行为断言：
  1. spawn hook 可改 `command/cwd/env`，覆盖工具按注册序生效。
  2. 工具渲染器接收增量参数；`ReadLineRange.format` 输出 `:start-end`。
  3. 截断结果文本与 `truncated` 标记；`tool-override` 同名覆盖后者赢。
  4. `subagent` 用假会话/假模型返回结构化结果。
  5. `ssh`/`interactive-shell` 用假可执行脚本，断言 argv/env/cwd。
- 测试：`FakeProcess` 夹具；不启动真实 ssh/终端。

### W5 UI 槽位与编辑器（13 件）

- 单元见 §2.5（`overlay-test`／`overlay-qa-tests` 已出分母，D237）。
- 行为断言：footer/header/status/widget 的键位与文本行；`setEditorComponent` 的渲染与按键；working 指示器文案/可见性；thinking label 设置与重置；message/entry renderer 的 expanded 两臂；modal/rainbow editor 的提交值。
- 限制：`question`／`questionnaire` 的 overlay 定位沿 D99；组件动画以固定步进渲染行断言；主题只断言颜色名/转义，不复制结构化 Theme。
- 测试：文本行快照＋中性 `KeyStroke` 序列。

### W6 交互与命令（4 件）

- 单元见 §2.5（`snake`／`space-invaders` 已出分母，D237）。
- 行为断言：`commands` 的三源命令列表与过滤；`question/questionnaire` 的取消值；`timed-confirm` 超时；`tic-tac-toe` 归 W3（它是唯一钉住 `executionMode: "sequential"` 串行化的示例）。
- 限制：D99 overlay；计时器只验逻辑时点，不验真实帧率。

### W7 平台与文件（5 件）

- 单元见 §2.5（`mac-system-theme` 已出分母，D237）。
- 行为断言：OSC 777 通知序列、标题设置/恢复；`file-trigger` 用临时目录轮询；`git-merge-and-resolve` 用假 git；`shutdown-command` 触发 `session_shutdown` 并收尾。

### W8 SDK、RPC 与资源（17 件）

- 单元见 §2.5。
- 行为断言：
  1. SDK 01–13 逐件对应 Java `AgentSession.create(...)` 等入口：minimal、custom model、custom prompt、skills、tools、extensions、context files、prompt templates、keys/oauth、settings、sessions、full control、session runtime。
  2. `rpc-client`/`rpc-extension-ui`：请求/响应九方法通道、事件顺序、取消。
  3. `dynamic-resources`：`dynamic.json`/`dynamic.md`/`SKILL.md` 资源发现与内容投影。
- 限制：`sdk/14` 出分母（R5）；SDK 示例不要求新增 facade，可用现有入口等价改写，形状差登记 D 编号。

## 5. 验收测试口径

1. 每件一个独立测试类（目录型示例可按入口类一测＋辅助类单测）；命名 `XxxExtensionTest`。
2. 外部系统一律假模型、假进程、假 git/ssh、临时目录；禁止真实 home/settings/session、禁止联网。
3. 模型可见文本（系统提示、工具声明、参数、结果、错误）逐字断言；内部形状差登记窄差。
4. 流式/工具/会话链必须有集成测试：`AgentSession`＋扩展＋脚本 provider 全链。
5. 测试不得写入 `~`；会话目录用 `@TempDir`；agent 目录用既有 `pi.java.agentDir` 隔离缝（D231）。
6. 每个 W 闭环跑：
   ```bash
   mvn test -pl pi-java-examples -am
   ```
   层闭环再跑 `mvn clean verify`（零失败、Checkstyle 零错误、SpotBugs 零新增）。
7. 文件 ≤500 行；不以 `@SuppressWarnings` 掩盖新告警。

## 6. 编号登记

| 编号 | 内容 | 处置 |
|---|---|---|
| D232 | examples 的 8 个外部运行时文件出分母（sandbox、gondolin、doom-overlay×5、with-deps）：纯 Java 无对位原生沙箱/micro-VM/WASM/JS 模块解析。不写降级假移植。 | 本层登记 |
| D237 | examples 的 5 件范围面主动出分母（overlay×2、游戏×2、mac-system-theme，见 §2.4b）：用户裁决 2026-11-09；分母 93→88。 | 本层登记（docs/04 §6） |
| B284 | `ExtensionContext` 余 11 成员（`model`、`modelRegistry`、`getContextUsage`、`compact`、`reload`、`shutdown`、`isIdle`、`getSystemPrompt`、`thinkingLevel`、`isProjectTrusted`、`newSession`） | W2/W3 前置，实施后销号 |
| B285 | `ExtensionSessionView` 余成员（`getBranch`、`getEntry`、`getLabel`、`getLeafEntry`、`getSessionFile` 等） | W3 前置，实施后销号 |
| D220 | `EntryRenderOptions.keyHint` 不移植（既有） | 沿用，W1 引用 |
| D99 | overlay 定位/尺寸/堆叠不移植（既有） | 沿用，W5/W6 引用 |

## 7. 实施顺序与验证门

1. 本稿审核通过后，先做 W0＋W1（不依赖新宿主成员）。
2. B284/B285 作为 W2/W3 的前置包单独审核；不在 W1 中夹带。
3. 每个 W 完成后回填 `docs/04`（销 B/D）、`docs/06`（L8 进度）、`docs/map` 对应行与 `docs/07` 能力清单。
4. L8 全层闭环条件：新分母 88 件全部有判定；余 59 件待实施全部有独立测试（W1＋W2 共 13 件已完成）；全仓 `mvn clean verify` 通过；`docs/07` §3 能力清单按新清单重写。
5. 未实施前不得把任何 W 标成“对齐”；`docs/05` 总表在 L8 闭环后才替换。

## 8. 风险与待审点

1. **宿主 API 扩张面大**：B284/B285 会触及 coding-agent 核心上下文，需单独审核签名与晚绑定语义，避免把示例需求写成万能接口。
2. **D99/D220 约束**：overlay/KeybindingHints 相关示例只能文本级验收；`overlay-test`／`overlay-qa-tests` 已按 D237 出分母，若后续 L7 补齐 overlay 全参，再单独立项回补（不自动恢复分母）。
3. **自定义 provider 不联网**：只钉注册形状与流事件；真实 OAuth/HTTP wire 仍由 L1 provider 面负责，避免重复实现。
4. **SDK 入口形状差**：Java 无 `createAgentSession` 同名 facade，SDK 示例以现有入口等价改写；如用户要求同名 facade，另立 B。
5. **测试量**：余 59 件、每件独立测试，实施必须分批过审；不得一次性大提交。
6. **旧账面隔离**：`16/102` 只作历史；本层新账面统一用 88 分母（D232＋D237），完成后替换 `docs/05`。
