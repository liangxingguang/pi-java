# coding-agent 层待对齐清单（快照）

> **性质**：这不是台账，是从权威明细 [`map/04-coding-agent.md`](map/04-coding-agent.md)（基线 pi @ `ea448f454`、v1.1.0-22）导出的**一次性快照**（2026-10-18，agent-core 批 5 闭环后）。后续以 map/04 与 [`04-open-items-register.md`](04-open-items-register.md) 为准；完成任何一项后应回改 map/04，而非本文件。
> **不占用设计稿包号**（下一包号 docs/27，见 docs/04 §7 规则 6）。

## 总览

模块现状：**加权 43.9%（217.5/496）**、未加权 31.7%（92/290）。待对齐 = **缺失 128 + 存疑 70** 个单元（MCP 面归 [`map/07-mcp.md`](map/07-mcp.md)，不在此展开）。

| 域 | 对齐/缺失/存疑 | 加权完成度 | Σ权重 |
|---|---|---|---:|
| A. CLI 参数（43） | 28 / 4 / 11 | 78.2% | 62 |
| B. 子命令（8） | 3 / 1 / 4 | 60.0% | 15 |
| C. Slash 命令（25） | 11 / 3 / 11 | 65.8% | 38 |
| **D. 扩展系统 41 事件** | **1 / 33 / 7** | **12.7%** | **83（模块最大域，16.7%）** |
| **D′. 扩展 UI 注入面（9）** | **0 / 8 / 1** | **6.3%** | 16 |
| E1. 配置文件/清单（5） | 2 / 1 / 2 | 60.0% | 10 |
| E2. settings 键（55） | 20 / 23 / 12 | 57.0% | 86 |
| E3. 会话管理（24） | 14 / 4 / 6 | 68.8% | 40 |
| E4. 资源发现（7） | 4 / 3 / 0 | 57.1% | 14 |
| **E5. 扩展 API（35）** | **5 / 26 / 4** | **24.1%** | 58 |
| **E6. 包管理器（16）** | **0 / 11 / 5** | **16.1%（唯一零对齐域）** | 31 |
| E7. RPC 面（6） | 2 / 0 / 4 | 66.7% | 12 |
| G. 主流新增子系统（10） | 3 / 5 / 2 | 38.9% | 18 |
| F. 整块缺失（6） | 0 / 6 / 0 | 0% | 13 |

---

## 一、最大单块缺口：扩展系统（D + D′ + E5，单独口径加权 16.2%）

D 域 Σ权重 83 是全模块最大域；若 D 域全对齐，模块加权 43.9% → **58.5%**（**单独拉低 14.6pp**）。

- **D：41 个扩展事件仅 1 个可订阅**（`resources_discover`）。33 缺失 + 7 个「引擎钩子有但未桥接」（`ExtensionContext` 不暴露 `hookSystem()`）。w3 事件全在此列：`context`、`context_with_system`、`before_provider_request`、`before_agent_start`、`message_update`、`input`、`tool_call`、`tool_result`。
- **D′：扩展 UI 注入 9 面全零**：widget / overlay / footer·header / 按键拦截 / 编辑器注入 / 主题读写 / 工具展开态；唯一有形的 RPC `ui()` 无生产者。
- **E5：扩展 API 35 项只对齐 5**（registerTool / registerCommand / registerProvider / skills / getSettings）。缺：`sendMessage` 的 triggerTurn、`exec`、`setModel`、`setActiveTools`、`registerFlag`/`getFlag`、`on()` 退订函数、渲染器注册族（message/markdown/entry/tool）、`events` 扩展间总线、热重载全量、virtual-model 注册族、MCP 注册 API（E5-23..25，归 map/07）。
- **E5-33 / F：扩展示例库 `examples/` 105 个 `.ts`（16,033 行，w3）零对应** —— 它同时是 pi 扩展 API 的行为规格。

## 二、E6 包管理器：唯一零对齐域（0/16）

- npm 源（版本/range/pinning，**w3**）、git 源、持久化到 `settings.packages`（**w3**）。
- 资源过滤、5 档资源优先级、忽略文件、`onProgress` 进度回调。
- `update self|pi`（自更新 + managed release 清理）、`update --extensions`、`update --models`（子命令；刷新机制本身已在核心自动跑）。
- temporary 作用域、`config` TUI（Tab 切作用域逐资源开关）。
- java 现状 = 「下 JAR 到目录」＋非交互 enable/disable。

## 三、G 域新子系统：java 全零（4,723 行）

| 子系统 | pi 侧 | 权重 |
|---|---|---:|
| codemode 沙箱运行时 + 工具（quickjs-wasi） | `packages/codemode` 2,597 + `extensions/codemode` 1,360 | 2+2 |
| `tool_search`（BM25 + 按需加载 deferred 工具） | `extensions/tool-search` 265 | 2 |
| 虚拟模型（registry + route + 状态条目） | `core/virtual-models.ts` 238 | 2 |
| 嵌套工具调用（`ctx.executeTool()`） | `core/nested-tool-calls.ts` 263 | 1 |

## 四、A. CLI 参数（4 缺失 + 11 存疑）

**缺失**：`--models`（Ctrl+P 循环列表）、`--extension/-e`（显式扩展路径）、`--use-theme`、扩展注册 CLI flag（无 `registerFlag` API）。

**存疑**（按重量排）：
- **`--resume` 无参抛 `Session not found: null`** —— pi 开交互式选择器（session-selector 族 1,294 行），java 走 `find(null)`。**这是最像 bug 的一条**。
- `--tools` / `--exclude-tools` 只吃逗号裸名；pi 收 `*` 通配、`+name`/`-name` modifier、`mcp__` 前缀 ⇒ **docs/26（B236 设计稿，搁置待审）**。
- `--session` / `--session-id` / `--fork`：java `find()` 走 `list(all())`，O(全量) 且跨项目；pi 是路径形快道 + 三级阶梯（**台账 B53**）。
- `--no-session` web 路径被忽略（**B54**）。
- `--export` 只收 1 个位置参数（pi 收 2 个）；`--theme` 只收 `.tcss`（pi 收 JSON 主题名/文件）；`--offline` 只覆盖目录刷新；`--` 选项终止无夹具。

## 五、B. 子命令（1 缺失 + 4 存疑）

- 缺失：`update` 整支（self-update / 扩展更新 / 目录刷新子命令）。
- 存疑：`install`/`remove` 只支持本地 JAR 与 http(s)（pi 三源）；`list` 无 user/project 分组与 installedPath；`config` 非交互（pi 是 TUI）。

## 六、C. Slash 命令（3 缺失 + 11 存疑）

**缺失**：`/thinking`、`/bug`（bug-report 375+ 行上报链）、**`/skill:<name>` 命令族（B244）**——`enableSkillCommands` 字段有但不产命令。

**存疑**：
- `/settings` / `/tree` / `/scoped-models`：只有 marker，无选择器 UI（pi 侧 995 / 1,435 / 401 行）。
- `/model` 不支持 `<provider/model>` 直设；`/fork` 无历史用户消息选择器；`/session` 无 stats；`/export` 无默认 HTML；`/changelog` 硬编码；`/share` 缺 Radius 网关主路径（只有 gist fallback）；`/login` 缺 OAuth 选择器 + Radius 直供 + 写入全局 mcp.json。
- **`/compact` 缺 abort-first（台账 A4）**：pi `compact()` 第一句 `await this.abort()`，java 入口无 isRunning 守卫、运行中压缩不中止在飞回合。
- `/reload` 只重载 settings；pi 还重载 extensions/skills/prompts/themes/context files 并激活新 defaultTools（**B246**）。

## 七、E2. settings 键（23 缺失 + 12 存疑）

**缺失**（w2 优先）：`defaultTools`（+/- modifier，随 B236）、`branchSummary`（**B1**）、`packages`、`cacheWarming`、`modelThinkingLevels`、`thinkingBudgets`、`warnings`、`npmCommand`、`httpIdleTimeoutMs`（**E2-48**，已在册）、`codemode`；另有遥测 4 键（enableInstallTelemetry/enableAnalytics/trackingId/deviceId）、fullscreen 4 键、`lastChangelogVersion`、`showCacheMissNotices`、`collapseChangelog`、`showHardwareCursor`、`websocketConnectTimeoutMs` 等 w1 长尾。

**存疑**（多为「字段有、零消费」）：`editorPaddingX`、`outputPad`（pi 已接进 ToolRenderContext）、`autocompleteMaxVisible`、`httpProxy`、`externalEditor`、`doubleEscapeAction`、`treeFilterMode`、`terminal`（7 键形齐，本模块零消费）、`images.blockImages`、`markdown.codeBlockIndent/mermaid`、`enableSkillCommands`（随 B244）、`theme`（值语义差：`.tcss` 路径 vs JSON 主题名）。

## 八、E3. 会话管理（4 缺失 + 6 存疑）

**缺失**：`-r` 交互式选择器（同 A 域）、分支摘要 branch summary（**B1**）、`session_info_changed` 零生产者（**B30**）、提示缓存预热 cache-warmer（453 行，含 `cacheWarming` 键）。

**存疑**：**B53**（`--session`/`--session-id`/`--fork` 缺 header-only 快道，见 A 域）；**B55**（会话列表无 `onProgress`/`AbortSignal`/增量发布）；**B54**（`--no-session` web 路径）；`/fork` 历史消息选择器（同 C 域）。

## 九、E1 / E4 / E7

- **E1**：pi manifest（`package.json` 的 `pi` 字段）缺失；`auth.json` 存疑（revision/文件锁/stale 检测未核）；`models.json` 存疑（**F6**：`ModelsJsonProvider` 钉死 baseUrl，`Args.baseUrl` 不消费）。
- **E4**：theme JSON 系统全缺（2,417 行：theme/theme-controller/system-theme/typebox schema/tokens + dark/light.json）；系统提示词文件发现（`discoverSystemPromptFile`）缺失；`/skill:` 注册（同 C 域 B244）。
- **E7**：`get_state` 三瑕疵——**B31**（model 是字符串非对象）/ **B32**（sessionName 恒有默认值）/ **B33**（messageCount 取 entryCount）；RPC 事件线格式（**B27/B28/B29**）；`prompt` 回执不产 `"handled"` disposition（E7-6）；扩展 UI 双向通道有形无生产者（E7-2）。

## 十、F / E8 整块缺失

| 子系统 | pi LOC | 权重 |
|---|---:|---:|
| 扩展示例库 `examples/` | 16,033 | 3 |
| JSON 主题系统 | 2,417 | 2 |
| npm/git 包源 + 优先级 + 忽略文件 | 2,760 | 3 |
| 自更新（self-update） | ~300 | 1 |
| TUI 扩展 UI 注入层 | ~700 | 2 |
| 交互式会话选择器（`-r`） | 1,294 | 2 |

E8（不计入模块合计）：crash-log（170 行）、WSL/zip 工具、system-prompt 分节构建（229 行）、jiti 加载器（不适用）。

---

## 推进抓手（已在册）

1. **docs/26（B236，搁置待审）** — coding-agent 批 1 设计稿：`--tools` glob/modifier、`mcp__` 策略、`defaultTools`、active 默认 7→4。审核通过即开工。
2. **B244** — `/skill:<name>` 命令族 + `enableSkillCommands` 消费。
3. **B245** — tree 扩展事件 + TUI 摘要流。
4. **B246** — `/reload` 工具选择臂（重载资源 + 激活新 defaultTools）。
5. **E1** — `AgentSession.java` 已 1,592 行，拆分宜搭某个 coding-agent 批。
6. **B221**（map/07）— MCP builtin 工厂接线卡在 D/E5 扩展面（六事件 / 激活门 / `ui.custom`）；扩展系统打通后的最大受益方。
7. **E2-48** — `httpIdleTimeoutMs` 默认值。

> 读数：w3 层已 50.0%（高于其余两层），剩余欠账高度集中——**D 域扩展事件面（16.7% 权重）+ E6 包管理器 + E5 扩展 API 长尾**吃掉模块近四成权重；`--resume` 选择器是唯一「现存即异常」的条目，宜优先。
