# 25 - L5 coding-agent 新锚点重测与对齐设计（W1–W10）

> 【背景摘要】按 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md) §5 的 L5 范围，对 CLI 宿主/产品层（`map/04`）按新锚点 `56cae7823` 全量重测。区间内 `packages/coding-agent/src` 共 156 个文件变化（+3,417/−12,363），其中 9,950 行是 `experimental/` 整块删除（R1）。真正落在 L5 的差异集中在：run 启动与 `before_agent_start` 载荷、`reachesTools` 投影、`pi.exec` 输出上限、settings/分享/自更新/扩展 changelog、包管理器容错与临时包刷新、模型引用解析、tool_search 排序、HTML 导出与扩展渲染上下文、RPC 思考档过滤及少量 CLI/平台修复。
>
> 文档状态：**实施中（2026-11-05 批准；W1–W5、W9、W10 已落，余 W6/W7/W8）**。§14 第 2–5 项已由用户裁决。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。pi-java 代码基线：`b9af4f18`（main，2026-11-05；L4 docs/24 关闭后）。旧基线：pi `ea448f454`，pi-java `edc8749d`（map/04 旧读数）。

## 0. 范围、口径与术语

- **层范围**：`pi-java-coding-agent` 的 CLI 参数/子命令/slash/扩展 API/资源发现/包管理/会话呈现，以及 map/04 计分单元在 `pi-java-ai`、`pi-java-agent-core`、`pi-java-tui` 中的实现点。MCP 面归 map/07（L2 已闭环），评测归 L6，TUI 屏域归 L7，examples 归 L8。
- **计分口径**：权重＝用户可观察影响 × 频率（3/2/1/0）；完成系数 对齐 1.0／存疑 0.5／缺失 0。R1/R5 与转出行权重 0、不进分母；新图 Σ权重按行实算。
- **判定三档**：对齐／存疑／缺失。旧锚点已对齐的行必须重新取证；“上一批已对齐”不是新锚点证据。
- **术语**：
  - **运行启动窗**：从 `_startRun` 置 busy 到 `agent_start` 之间的窗口；此时 `before_agent_start` 处理器正在跑，但引擎尚未起跑。
  - **loadout（激活装配）**：声明给模型的工具名集合及其模型可见顺序。
  - **reachesTools（可达工具）**：工具声明“一次调用可能触达哪些其他工具”的函数；MCP 晚注册与 tool_search 靠它决定等待/搜索范围。
  - **custom trigger（自定义触发）**：`sendMessage(triggerTurn: true)` 在空闲时直接用一条 custom 消息起一轮 run。
  - **扩展 changelog**：包扩展自带的 `CHANGELOG.md` 在升级后于启动界面展示新版本条目。
  - **share destination（分享目的地）**：`/share` 选择 Radius artifact 还是 GitHub gist 的配置。
  - **自更新命令（selfUpdateCommand）**：用户配置的 `pi update` 自身更新 argv，支持 `{packageName}`／`{version}`／`{installSpec}` 占位符。
- **模型可见面**：工具声明与顺序、`before_agent_start` 载荷、`pi.exec` 结果（含 `truncated`）、模型解析错误/警告、tool_search 结果顺序、导出的 HTML 内容。内部代码路径标【内部，模型不可见】。

## 1. 新锚点重测结论

### 1.1 方法

1. `git diff --stat ea448f454..56cae7823 -- packages/coding-agent/src` 枚举全部变化文件，逐个按 docs/08 层归属分类。
2. `git log --oneline --no-merges ea448f454..56cae7823 -- packages/coding-agent/src` 的 101 个提交作为功能清单，避免把文件行数当功能缺口。
3. 逐行重判 map/04 的 A/B/C/D/E1–E7/G 全部计分单元；只有新锚点证据真正影响的行才翻动，其余行只做行号平移与复核。
4. 旧裁决（B259、E2 五项存疑、E5-31、E5-33、B58）与 docs/08 §5 L5 点名的十个重点单独复核。

### 1.2 变化清单与归属

区间内落在 coding-agent 的提交按归属分层（代表性提交）：

| 归属 | 代表性提交 / 文件 | 结论 |
|---|---|---|
| **L1（已闭环）** | `adc5fa9f8` client identity、`ee409a85f` OpenRouter 个性化目录、`cae8d69e5`/`7c22a93d0` usage cost、`e5fe9777b` 退役模型、`f049787d9`/`95364a54e`/`90d863f7c` 默认模型 | docs/12–docs/20 已覆盖，不在本包重做 |
| **L2（已闭环）** | `67996ab75` structuredContent、`f4cca2dc2` 连接不枚举资源、`908aa7b74`/`be15fe3c1` OAuth、`bdd9f0399` BOM、`88a564b3` codemode 解耦 | map/07 已闭环；本包只保留指针 |
| **L3（已闭环）** | `fecc19e4e` grep 孤立 CR、`9fe3f3716` 模板引号空参数、`1be312b46` 路径比较键（技能复核等价） | docs/23 已覆盖 |
| **L4（已闭环）** | `6f2f164ab` 恢复工具、`269afc66a` branch summary thinking、`15e7bdc14` 摘要去 thinking、`02726086d` 省略成对、`807a5c051` 写失败回滚、`8ddc44ec8` fork 单 fd | docs/24 已覆盖 |
| **R1** | `7472cda0d` 删除 experimental server/client/protocol/server + durable/vacation（−9,950 行） | 移出分母，不为旧参照物补功能 |
| **R5** | `f6ee9ce9c` codemode 输出描述、`885a564b3` codemode worker、`24382bedd` codemode/MCP 解耦、`3daa20280` codemode 取消；`7f225f25d` System One classifier 图像输入 | 不进入 L5 分母；classifier 按 docs/04 §5.1 维持 R5 |
| **L5（本包）** | `a447ba11f` custom 触发 `before_agent_start`；`b9e3eef6c` 激活顺序；`c466a3595`/`36be9c612`/`c60de42e9` exec；`cea257439` share destination；`b4b72a351` 扩展 changelog；`8bc94b19f`/`c2760d32c` 自更新命令；`6dfb54678` 安装失败不阻塞；`bec60c54b`/`cd6a8f7f3` 临时包刷新；`6a51a39d4` `~` 扩展；`d7f7e0dfe`/`984cf6643` 模型引用统一策略；`79006f061`/`e033f255f`/`cc44fa216`/`ae823bc0b` tool_search；`26293e74f`/`9359adf0c`/`1c720d285` HTML 导出；`64627838b` markdown 上下文；`9376c8d17`/`c1810bf70` KeybindingHints／blocked；`441f5767c` print 截断；`31ade0e0d` trust 优先级；`88ffcdc73` cwd 缺失；`e7a0df27c` 图片编码；`61060ddf8`/`28bea006b`/`27cc6bfc0` shell 平台修复 | 见 §2–§11 |
| **L7（转出/复核）** | `8c03fef4d` 会话搜索分层排序、`3bae85274`/`9d345b4e9` 翻页键、`606af8662` 模型名显示、`7391dbb0f` 模型 scope 头、`7853186d1`/`38847563f` 扩展对话框堆叠、`6454d07ec` 剪贴板/WSL、`b5fc6dc84` CSI 清理、`f14813e96` Skill warnings、`a6a7bac86` thinking 空标签、`910149912` read 行号、`014873d7d` 工具计时、`dbc7ff87c` 状态颜色 | map/04 对应行转 L7 或仅作证据刷新，本包不实现 |
| **N/A（平台/依赖）** | `5f960a0e2`/`5fb9a0b11`/`476a303c1`/`0d23d5345` npm CLI 行为、`c8ec752cf` fd/rg 下载架构、`8efd314fa` models.json 文档生成、`96abe9a5a` 主题 `$schema` URL、`e6fb2581d` lockfile EPERM | 登记 D 或复核说明 |

### 1.3 map/04 逐域重判（实施前）

判定变化行用“旧→新”标注；`转`＝转出行（权重 0）。

| 域 | 行数/Σ权重（新分母） | 实施前读数 | 变化与说明 | 实施后 |
|---|---|---|---|---|
| A CLI 参数 | 43 / 62 | 59.5/62＝**95.97%** | A-7 `--model`→存疑（模型引用新策略，W6）；A-17 `--models`→存疑（scope 歧义语义，W6）；其余 41 行对齐。args.ts 仅帮助文案与 `PI_SELF_UPDATE_COMMAND` 环境行变化 | 62/62 |
| B 子命令 | 8 / 15 | 13.0/15＝**86.67%** | B-4 `update`→存疑（`selfUpdateCommand`，W4）；B-7 `auth`→存疑（provider 限定的严格解析，W6）；其余对齐 | 15/15 |
| C slash | 25 / 38 | 36.5/38＝**96.05%** | C-6 `/export`→存疑（HTML 导出新语义，W8）；C-8 `/share`→存疑（destination，W4）；`/import` `/share` `/resume` 描述文案变化（W10）；其余对齐 | 38/38 |
| D 扩展事件 | 41 / 83 | 81.5/83＝**98.19%** | D-21 `before_agent_start`→存疑（user/custom 联合与 custom 触发，W1）；其余 40 行事件集不变 | 83/83 |
| E1 配置/清单 | 5 / 10 | 9.0/10＝**90.00%** | E1-5 pi manifest→存疑（新增 `changelogPath`，W4）；E1-3 auth lock 的 EPERM 重试登记 D223；其余对齐 | 10/10 |
| E2 settings 键 | 57 / 87 | 81.5/87＝**93.68%** | 新增 E2-56 `extensionChangelogVersions`(1)、E2-57 `sessionShareDestination`(1)、E2-58 `selfUpdateCommand`(1)＝缺失（W4）；旧 49 对齐＋5 存疑（E2-22/32/43/50/54）不变；E2-46 显式会话目录证据随 L4 W4c 刷新 | 84.5/87 |
| E3 会话管理 | 24 / 38 | 37.0/38＝**97.37%** | E3-3 会话选择器→**转 L7**（新锚点搜索分层重写，TUI 域）；E3-11 HTML 导出→存疑（系统消息/时间戳/CJK/深链，W8）；E3-20/21 证据刷新（L4 W4c）；其余 21 行对齐 | 38/38 |
| E4 资源发现 | 7 / 14 | 14/14＝**100%** | 无改判；路径比较键复核等价（同 L3 W2）；prompt 引号参数已由 L3 W1 覆盖 | 14/14 |
| E5 扩展 API | 35 / 53 | 49.0/53＝**92.45%** | E5-31 扩展 UI→**转 L7**；E5-33 examples→**转 L8**；E5-12 `sendMessage`→存疑（custom 触发，W1）；E5-17 `exec`→存疑（maxBuffer/truncated，W3）；E5-18 `getAllTools`→存疑（reachesTools 投影，W2）；E5-9 markdown transformer→存疑（messageId/timestamp，W8）；其余对齐；KeybindingHints 登记 D220 | 53/53 |
| E6 包管理 | 18 / 34 | 27.0/34＝**79.41%** | 新增 E6-17 安装失败不阻塞＋启动警告(2)、E6-18 临时包自动刷新(1)＝缺失（W5）；E6-5 临时刷新→存疑、E6-6 资源过滤(`~`)→存疑、E6-10 自更新→存疑、E6-15 manifest→存疑（W4/W5）；其余对齐；npm CLI 特有条目登记 D222 | 34/34 |
| E7 RPC | 7 / 13 | 12.0/13＝**92.31%** | 新增 E7-7 思考档 RPC 按模型能力过滤(1)＝缺失（B259，W9）；其余对齐 | 13/13 |
| G 新增子系统 | G-8/9/10＝5 | 4.0/5＝**80.00%** | G-8 tool_search→存疑（Unicode 分词/字段加权/精确名，W7）；G-9/G-10 对齐；G-1..G-5 仍为 map/07 指针不重复计分；G-6/G-7 R5 | 5/5 |
| **合计（不含 E8）** | **452** | **424.0/452＝93.81%** | 分母变化：转出 E3-3(2)＋E5-31(2)＋E5-33(3)＝−7；新增 E2×3＋E6×2＋E7×1＝＋7，净持平 452 | **449.5/452＝99.45%** |

> 说明：`docs/05` 的旧快照 485 含此前已撤销的 D′ 重复计分与 MCP 指针等历史口径，不作为本层分母；本表按 map/04 各行权重逐行实算，closure 时以更新后的 map/04 为准。
> E2 实施后仍有 5 行存疑（E2-22/32/43/50/54，均为已登记的平台/终端能力窄差），因此模块不会到 100%，这是预期状态而不是遗漏。

### 1.4 旧裁决与新锚点复核

| 裁决/开放项 | 新锚点事实 | 本包结论 |
|---|---|---|
| **B259** 思考档 RPC | `agent-session.ts:2726-2753`：`cycleThinkingLevel()` 先 `supportsThinking()`，否则 `undefined`；`getAvailableThinkingLevels()` 走 `getSupportedThinkingLevels(model)`；`rpc-mode.ts:502-512` 返回 `null`/过滤集 | 确认缺口，W9 实施后销号 |
| E2-22 `enableInstallTelemetry` | 消费点仍只有 pi.dev 上报与归因头；Java 不复刻 pi.dev 端点（D55） | 维持存疑 |
| E2-32 `terminal` | 能力覆盖仍依赖终端协议面；Java 无可移植缝 | 维持存疑（D56） |
| E2-43 `markdown` | mermaid 仍无渲染者；Java 维持 D57 | 维持存疑 |
| E2-50 `websocketConnectTimeoutMs` | 唯一消费者仍是 codex WS；Java 无 WS 传输 | 维持存疑（D58） |
| E2-54 `fullscreenCopyOnSelect` | 仍依赖 alt-screen 选区子系统 | 维持存疑（D59） |
| **E5-31** 扩展 UI | 新锚点新增 `blocked`、对话框堆叠、KeybindingHints；全部是 TUI 渲染/交互面 | **转 L7**（docs/04 已把 E5-31 归 L7）；API 侧的 KeybindingHints 另见 D220 |
| **E5-33** examples | 新锚点 examples 仍需 L8 重新清点 | **转 L8**，L5 不再重复计分 |
| **B58** OpenAI 默认 wire | 两侧均 `openai-responses` | 仍待用户单独拍板，不在本包 |
| `isToolSearchTool` 引用判等 | 新锚点删除该函数（`tool.ts` 不再导出） | W7 随 Java 实现删除对位函数及调用点 |
| `experimental/` 整块删除 | `7472cda0d` | R1：不复活 server/client/protocol/plugin 发布面 |
| classifier compat（B277 点名） | 消费者为 System One/codemode；`bin pi` 主流路径不可达 | R5 出分母，B277 该项按 R5 收口 |

### 1.5 转出与 R 裁决

- **E3-3 → L7**：选择器本体与搜索排序都在 `pi-java-tui`；新锚点 `session-selector-search.ts` 的分层评分（名称/正文/模糊三档）由 L7 重测实现。map/04 行保留证据与指针，权重 0。
- **E5-31 → L7**：扩展 UI 28 成员与 `blocked`/对话框堆叠按 docs/04 归属 L7。
- **E5-33 → L8**：examples 验收规格归 L8，L5 不保留第二计分入口。
- **R1**：`experimental/**` 删除不补；`client`/`server`/`protocol` 旧发布面不复活。
- **R5**：codemode、System One classifier、durable/chord 不进分母；tool_search、MCP 数据面等共享主流行为不随文件名整包排除。

## 2. W1（B275）：run 启动窗与 `before_agent_start` 联合载荷

### 2.1 pi 事实（新锚点）

事件联合（`core/extensions/types.ts:952-978`）：

```ts
interface BeforeAgentStartEventBase { type: "before_agent_start"; readonly systemPrompt: string;
  systemPromptOptions: NormalizedBuildSystemPromptOptions; }
export interface BeforeAgentStartUserEvent extends BeforeAgentStartEventBase {
  messageType: "user"; prompt: string; images?: ImageContent[]; }
export interface BeforeAgentStartCustomEvent extends BeforeAgentStartEventBase {
  messageType: "custom"; message: CustomMessage; }
export type BeforeAgentStartEvent = BeforeAgentStartUserEvent | BeforeAgentStartCustomEvent;
```

runner 触发（`extensions/runner.ts:169-172`、`:1427-1460`）：`emitBeforeAgentStart(trigger, options)` 用 `{...trigger, type, systemPrompt}` 构造事件；user 臂带 `prompt/images`，custom 臂带 `message`。

启动窗（`core/agent-session.ts:1894-1945`）：

```ts
private async _startRun(trigger: BeforeAgentStartTrigger, onStarted?: () => void): Promise<void> {
  this._isAgentRunActive = true;
  this._agentRunAbortRequested = false;
  let messages: AgentMessage[];
  try { messages = await this._prepareRun(trigger); }
  catch (error) { this._isAgentRunActive = false; this._flushPendingBashMessages();
    this._flushPendingCustomMessages(); this._resolveIdleWaitIfIdle(); throw error; }
  this._flushPendingBashMessages();
  this._flushPendingCustomMessages();
  onStarted?.();
  ...
}
```

准备（`:1947-2013`）：先压缩检查，再 `emitBeforeAgentStart(trigger, ...)`；custom 臂把 `trigger.message` 作为起始消息，user 臂在 hook 之后归一化图片并构造 user 消息；随后注入 `_pendingNextTurnMessages`、处理器返回的 custom 消息，系统补丁置首。

abort 在启动窗生效（`:1117-1122`）：`agent_start` 事件到达时若 `_agentRunAbortRequested` ⇒ `this.agent.abort()`。

`sendCustomMessage` 的 `triggerTurn` 臂（`:2398-2405`）：settled 窗内改为重新调用 `sendCustomMessage(message, options)`（而非直接起旧轮）；空闲时 `_startRun({messageType:"custom", message})`。

### 2.2 Java 现状

- `PromptPipeline.processPrompt`（`pi-java-coding-agent/.../core/PromptPipeline.java:98-251`）在路由门之后直接 flush custom、发 `beforeAgentStart(prompt, ...)`，再起虚拟线程 `SessionRunner.drive`；**没有启动窗**：`harness.isRunning()` 只有引擎起跑后才为真。
- `SessionEventFirer.beforeAgentStart`（`extension/SessionEventFirer.java:337-364`）签名只有 `prompt/currentSystemPrompt/sections`；`ExtensionEvent.BeforeAgentStartEvent`（`extension/event/ExtensionEvent.java:281-283`）无 `messageType/images/message`。
- custom 触发：`SessionMessagingLifecycle.sendCustomMessage`（`:184-234`）空闲臂直接 `driveCustomTurn(payload)`（`:303-310`），**完全不发 `before_agent_start`**；settled 延迟表存的也是 `driveCustomTurn`。
- abort 旗在 `SessionRunner.driveImpl`（`core/SessionRunner.java:111`）起手清零——会把启动窗内的 abort 请求抹掉；`agent_start` 分支（`:291`）只看不发 abort。
- 既有窄差 D11（`systemPromptOptions.selectedTools` 可变面不移植）、D19（result.message 落盘序）保持。

### 2.3 改动

1. `AgentSession` 增加 `AtomicBoolean agentRunActive`（包内读写）；`SessionDataViews.isStreaming`（`:64`）改为 `harness.isRunning(lane) || agentRunActive`，供路由/排队/`sendMessage` 的 streaming 门使用。
2. `PromptPipeline`：
   - 把 `processPrompt` 的“flush＋hook＋构造消息＋起线程”段抽成 `startRun(trigger, onStarted)`；进入即置 `agentRunActive=true`、清 `agentRunAbortRequested`。
   - 失败臂按 pi：复位 active、flush bash/custom、`resolveIdleWaitIfIdle`，再抛出。
   - hook 之后（成功臂）才 flush pending bash/custom，**先于**起始消息；`onStarted` 在 flush 后、起线程前调用（RPC 的 `"started"` 回执时点）。
   - `_pendingNextTurnMessages` 的 drain 放到 hook 之后、与起始消息同批（custom 触发同样消费）。
   - `agentRunActive` 在 `SessionRunner` 收尾（正常/异常/log）统一复位；`driveImpl` 不再清 abort 旗。
3. 新 `startRunCustom(trigger.message)`：custom 触发走同一 `startRun` 流程，起始消息为 custom 本体；处理器返回的 custom 消息排在其后；`driveContinuation` 仍用于“已落盘 custom 条目”的续跑语义，但启动窗与 hook 共用。
4. `SessionMessagingLifecycle`：
   - `triggerTurn` 空闲臂改调 `prompts().startRunCustom(...)`；settled 延迟表改存 `() -> sendCustomMessage(...)`（重新走五臂，pi `:2401`）。
   - streaming 判定改用 `SessionDataViews.isStreaming`（含启动窗）。
5. `ExtensionEvent.BeforeAgentStartEvent` 增加 `messageType`（枚举 USER/CUSTOM）、`images`、`message`（custom 草稿的可空载荷）；保留旧三参构造器以兼容既有扩展；`SessionEventFirer.beforeAgentStart` 增加 trigger 形参并构造 union。
6. `SessionRunner` 的 `AgentStart` 分支：`if (owner.agentRunAbortRequested()) owner.harness().abort(laneName)`，与 pi `:1122` 同点。

### 2.4 测试（RED-first）

- `RunStartWindowTest`：`before_agent_start` 处理器内调用 `sendMessage(triggerTurn:false)` ⇒ 消息进本 run 的 pending，而不是另起一轮；处理器内 `abort()` ⇒ `agent_start` 后引擎立即中止，`agent_settled{aborted:true}`。
- `CustomTriggerHookTest`：空闲 `sendMessage(triggerTurn:true)` ⇒ 扩展收到 `messageType:"custom"` 与本条 custom 消息；处理器返回的 message 排在起始 custom 之后；`input` 事件仍不经过（pi 同）。
- `RunStartFlushOrderTest`：启动窗内到达的 bash/custom 延迟消息排在起始 user 消息之前（pi `_prepareRun` 后 flush 的序）。
- `DeferredSettledReentryTest`：settled 窗内 `triggerTurn` 在窗后重新判 streaming；若此时已有新 run 在飞，应走队列臂而非再起一轮。
- 回归：既有 `PromptPipeline`、`SessionLifecycleEventsTest`、`Extension*` 全量。

### 2.5 风险

- 【模型可见】启动窗内 `setActiveTools()` 与 hook 的 `selectedTools` 竞争：维持 D11 现状（handler 的 sections/systemPrompt 生效，selectedTools 编辑不移植），但 `agentRunActive` 会让 `getActiveTools` 读到已更新的集。
- 【内部】active 旗跨线程可见（TUI/RPC 线程读）；用 AtomicBoolean，不复用引擎 `isRunning`，避免死锁与“引擎未起跑即非 busy”的旧语义。
- 【内部】`deferredSettledActions` 改为重入 `sendCustomMessage` 后必须避免递归 settled（settled 发射窗已关闭，不会重入）。
- 【实施期回归（2026-11-05，W4 期间发现并修复）】`isStreaming` 并入 `agentRunActive` 后，`agentRunActive` 的复位点在 `emitAgentSettled`（引擎 `waitForIdle` 返回之后）。TUI 树导航的「abort 后立即 navigateTree」此时仍读到 `isRunning()==true`，撞双门错文——`TreeNavigationFlowTest` ⑥a/⑥c 在 `b053aee4` 上确定性红。修法按 pi `abort()` 的 await 语义（`agent-session.ts:2519-2529`：`await waitForIdle()`，而 pi 的 `isIdle` 含 `!_isAgentRunActive`）：新增 `PromptPipeline.waitForRunIdle()`／`AgentSession.abortAndWaitForIdle()`，树导航改调后者。

## 3. W2（B276）：`reachesTools` 投影与激活顺序

### 3.1 pi 事实

- `getAllTools()`（`agent-session.ts:1530-1545`）逐定义投影，含 `...(definition.reachesTools ? { reachesTools: definition.reachesTools } : {})`；`ToolInfo`（`types.ts:2143-2152`）Pick 加入 `reachesTools`。
- `_applyToolLoadout`（`:1614-1621`）：

```ts
const requested = new Set(toolNames);
const kept = this.getActiveToolNames().filter((name) => requested.has(name));
const tools = [...new Set([...kept, ...requested])].flatMap(...)
```

  同名集合重设保持当前顺序，新名追加。

### 3.2 Java 现状

- `AgentTool.reachesTools()`（`pi-java-agent-core/.../tool/AgentTool.java:86-93`）与 `ToolNamespace.aliases`（`ToolNamespace.java:12-23`）已存在；MCP 与 tool_search 已消费。
- `ModelToolControl.getAllTools()`（`:333-343`）构造 `ExtensionToolInfo` 时**未带 `reachesTools`**；`ExtensionToolInfo`（`extension/ExtensionToolInfo.java:19-24`）无该组件。
- `applyToolLoadout`（`:217-229`）用 `LinkedHashSet(names)` 顺序；但宿主的 `activeTools` 是 `Set.copyOf`（`HarnessConfig.java:136`、`HarnessPromptAndToolAccessors.java:91-93`），`getActiveToolNames()` 再按**注册表序**投影（`ToolRegistry.activeOf:70-73`）。因此：
  - 用户可见的工具声明顺序恒为注册表序，重置同名集合**不会**改变顺序——B276 的稳定性症状在 Java 已不存在（复核等价，同 L3 W2 先例）；
  - 与 pi 的“activation 序”是形状差异，登记 **D219**，本包不改注册表序源。

### 3.3 改动

1. `ExtensionToolInfo` 增加可空组件 `Function<Map<String,Object>, ToolReach> reachesTools`（保留现有构造器重载）；`ModelToolControl.getAllTools` 投影 `t.reachesTools()`。
2. 不改 `applyToolLoadout` 的顺序算法；在 `ModelToolControlTest` 加一条“同名集合重设后 `getActiveToolNames()` 不变”的回归锁。
3. `ToolNamespace.aliases` 已存在且无主流消费者（消费者是 codemode，R5），只登记复核结论，不新增代码。

### 3.4 测试

- 扩展 `getAllTools()` 返回的 `reachesTools` 函数可被扩展侧调用，且与工具本体的函数是同一引用。
- tool_search 的 `reachesTools:()=>"all"` 经 `getAllTools` 可见。
- 同名集合乱序重设 ⇒ 声明顺序不变（注册表序）。

### 3.5 风险

- 【内部】`ExtensionToolInfo` 是公开 record；新增组件须保留旧构造器，并检查所有 `new ExtensionToolInfo(...)` 构造点（不允许只改构造点漏 with/rebuild 点——docs/04 B156 教训）。
- 【内部】序列化面（RPC `get_state` 等）不投影 `ExtensionToolInfo`；若未来新增投影，函数不可序列化，须显式丢弃。

## 4. W3（B277a）：`pi.exec` 输出上限与 UTF-8 分块

### 4.1 pi 事实

`core/exec.ts:10-15,19-40,53-129`：

```ts
const DEFAULT_EXEC_MAX_BUFFER = Math.min(256 * 1024 * 1024, bufferConstants.MAX_STRING_LENGTH);
export interface ExecOptions { timeout?: number; cwd?: string; maxBuffer?: number; }
export interface ExecResult { stdout: string; stderr: string; code: number; killed: boolean; truncated: boolean; }
```

- `maxBuffer` 必须是非负整数且 ≤ 运行时字符串上限，否则 `RangeError("maxBuffer must be an integer between 0 and ... bytes")`。
- stdout/stderr **合并计字节**；超限 ⇒ 标记 `truncated`、销毁两条管道、kill；结果 `code` 若非零则保留，若为 0 强制为 1。
- 用 `StringDecoder` 跨块解码；truncated 时**丢弃不完整的 UTF-8 尾**，不追加替换字符。

### 4.2 Java 现状

- `ExtensionExec.ExecOptions`（`extension/ExtensionExec.java:24`）只有 signal/timeout/cwd；`ExecResult`（`:31`）只有四件，无 `truncated`。
- 双泵线程 `readAllBytes()` 后一次性 `new String(bytes, UTF_8)`；无字节上限；truncated 时尾部会产生替换字符。

### 4.3 改动

1. `ExecOptions` 增加 `Long maxBuffer`（null ⇒ 默认 256 MiB）；构造时校验 `0 ≤ v ≤ 256 MiB`，非法抛 `IllegalArgumentException`（文案逐字 pi，把字节上限写成 268435456）。
2. `ExecResult` 增加 `boolean truncated`；所有构造点（含异常臂）更新。
3. 泵改为分块读取（建议 64 KiB），`AtomicLong outputBytes` 合并计数；超限时置 `truncated`、关两条流、走现有 `killProcess`，停止追加。
4. 终局：若 `truncated && code == 0` ⇒ `code = 1`；若 truncated，按“完整 UTF-8 序列”截断已收字节后再解码（实现 `decodeCompleteUtf8Prefix(byte[])`，把末尾不完整序列剥离）。
5. `killed` 语义不变（timeout/abort/truncation 都算 killed，pi 同）。

### 4.4 测试

- `ExecMaxBufferTest`：`maxBuffer=0` ⇒ 立即 truncated、code 非 0、stdout/stderr 为空；`maxBuffer` 小于输出 ⇒ truncated 且 code 1；stdout+stderr 合计计限。
- `ExecUtf8BoundaryTest`：进程输出跨块的多字节字符（可用 `bash -c 'printf "汉字" ; sleep 0.05; printf "汉字"'`）⇒ 结果中无替换字符；截断点落在多字节中间 ⇒ 不追加 `U+FFFD`。
- 校验臂：负数/非整数/超上限 ⇒ 抛。
- 既有 `ExtensionExecTest` 回归（killed/timeout/异常臂）。

### 4.5 风险

- 【模型可见】新增 `truncated` 与“截断不得读作成功”的退出码，扩展脚本可能据此改变分支；与 pi 一致。
- 【内部】泵线程与 kill 的竞态：关闭流后仍有在途 `read()`，需吞 IO 异常并按现有“泵静默收”策略处理。

## 5. W4（B277b）：settings／share／self-update／扩展 changelog


> **实施状态（2026-11-05）：已实施，待闭环回填。** 落地件：`PackagePaths`（`canonicalizePath`／`resolvePackageChangelogPath` 顶出，守 500 行闸门）、`SelfUpdate`（自更新命令面顶出）、`ExtensionChangelogs`（扩展 changelog 计算）。测试：`ExtensionChangelogsTest`(13)、`PackagePathsTest`(8)、`SelfUpdateTest`(4)、`SessionShareServiceTest`(+3)。coding-agent 全量 2,081 测零失败；tui 侧 `SettingsItemsTest` 补 `share-destination` 行。
>
> **与设计的两处偏差（均为实现事实，非裁决变更）：**
> 1. §5.3 第 2 项原写“若现列表无 sourceInfo，补字段”。实际补的是完整通路：`ResolvedAssembly.extensionMetadata()` → `DefaultExtensionContext.bindExtensionMetadata` → `ExtensionSourceInfos.forExtensionPath(…, PathMetadata)` → `ExtensionManager.sourceInfos()` → `AgentSession.extensionSourceInfos()`。缺这条通路时扩展的 `origin` 恒为 `top-level`，`ExtensionChangelogs` 的 package 门永不成立。
> 2. §5.1 第 4 项的路径包含判定改为 pi `isPathInside`（`package-manager.ts:690-695`）的逐字相对路径口径，而非 `startsWith` 前缀比较——后者会把包根 `pkg` 与兄弟目录 `pkg2` 判成包含关系。

### 5.1 三个新 settings 键与 manifest 字段

pi 证据：`settings-schema.ts:210-216,229-238,286-293`；`settings-defaults.ts:5`（`sessionShareDestination:"auto"`）；`settings-manager.ts:690-697,820-833`；`pi-manifest.ts:9,33-36`。

Java 现状：`Settings.java` 无这三个字段（`lastChangelogVersion:107` 只有旧键）；`SettingsAccessors` 无访问器；`PiManifest`（`pkg/PiManifest.java`）四资源键无 `changelogPath`；`SourceInfo`（`core/source/SourceInfo.java`）无 `changelogPath`；`PathMetadata`（`pkg/PathMetadata.java`）无 `changelogPath`。

改动：

1. `Settings` 增加：
   - `Map<String,String> extensionChangelogVersions`（仅全局域）；
   - `String sessionShareDestination`（`auto|gist|radius`，缺省 `auto`）；
   - `List<String> selfUpdateCommand`（仅全局域）。
   三者天然落入既有 unknown-key 丢失保护之外的已知字段；序列化字段顺序按 pi 键序插入（`extensionChangelogVersions` 紧跟 `lastChangelogVersion`，`sessionShareDestination` 紧跟 `transport`，`selfUpdateCommand` 紧跟 `npmCommand`）。
2. `SettingsAccessors`：
   - `getExtensionChangelogVersion(key)` / `setExtensionChangelogVersion(key, version)`（后者只写全局域并 flush）；
   - `getSessionShareDestination()`（三值校验，非法回落 `auto`）/ `set...`；
   - `getSelfUpdateCommand()`（防御性拷贝，仅全局域）。
3. `PiManifest` 读取 `pi.changelogPath`（仅 string）；`PathMetadata` 增加 `changelogPath`（可空）与 `withChangelogPath`；`SourceInfo` 增加 `changelogPath`（`@JsonInclude(NON_NULL)`，不进 RPC 时自动省略）。
4. `PackageManager` 在收集包元数据时解析 `resolvePackageChangelogPath(packageRoot, manifest)`（pi `package-manager.ts:697-713`）：
   - 缺省文件名 `CHANGELOG.md`；
   - `resolve(packageRoot, changelogPath)` 必须真实存在、是普通文件、且 canonical 路径在包根内（`isPathInside`），否则丢弃；
   - 结果写 `metadata.changelogPath`，并透传到 `SourceInfo`。
5. `PackageResourceCollector` 采用 `hasManifestResourceFields`（pi `:686-688`）：`pi` 对象只含 `changelogPath`/展示元数据时，视为无资源清单，走目录自动发现（修复 Java 当前“manifest 存在但全 null ⇒ 跳过目录发现”的分叉）。
6. 设置页：`pi-java-tui/.../screen/settings/SettingsItems.java` 在 Transport 之后插入 “Session share destination”（`auto|gist|radius`，描述逐字 pi `settings-selector.ts:512-521`）；`SettingsActionHandler` 增加 `share-destination` 分支。

### 5.2 `/share` 目的地

pi 证据（`modes/interactive/session-share.ts:66-82`）：

```ts
const destination = context.session.settingsManager.getSessionShareDestination();
if (destination !== "gist") {
  if (await tryShareViaRadius(jsonlFile, context)) return;
  if (destination === "radius") {
    context.showError("Radius authentication is not configured. Run '/login radius' first.");
    return;
  }
}
```

Java 现状：`SessionShareService.share`（`core/SessionShareService.java:71-82`）只要 radius provider 注册且 token 在场就上传，否则回落 gist；**无 destination 门**。

改动：服务构造接受 `Supplier<String> destination`；臂序：

- `gist` ⇒ 跳过 Radius，直接走 gh；
- `radius` ⇒ 允许 Radius 尝试；未认证（provider 缺席或 token 缺席）⇒ 错误文案逐字，**不回落**；
- `auto` ⇒ 现行为（有 token 走 Radius，否则 gist）；
- Radius 一旦实际尝试，其失败仍是终局（现有 `uploader.upload` 直接返回 outcome，保持）。

### 5.3 扩展 changelog 启动展示

pi 证据（`interactive-mode.ts:1384-1432,885-900`；`source-info.ts:57,77`；`settings-manager.ts:690-697`）：

- 只看 `origin === "package"`、有 `baseDir` 与 `changelogPath` 的扩展；按 `getSourcePackageKey({source,scope})` 去重；
- 读包 `package.json` 的 `name/version`；无 lastVersion ⇒ 只记版本不展示（全新安装）；
- `getNewEntries(parseChangelog(changelogPath), lastVersion)` 有内容 ⇒ 记版本并展示；每条 `## x.y.z` 标题前插包名；
- 展示：折叠开关开 ⇒ 每包一行 `Updated <name> to v<version>.`；否则 “Extension Updates” 头＋拼接 Markdown；与主 changelog 同时存在时用 Spacer 分隔。

Java 现状：`PiTuiApp` 有主 changelog 展示（`ChangelogSupport`、`SettingsAccessors` 的 `lastChangelogVersion`），无扩展 changelog。

改动：

1. 新增 `core/ExtensionChangelogs.java`（coding-agent）：输入扩展 `SourceInfo` 列表＋`SettingsAccessors`，输出 `List<Update{name,version,markdown}>`；复用 `ChangelogSupport.parse/getNewEntries` 与 `PromptTemplate` 无关。
2. `SourceInfo.changelogPath` 来自 W4 的包元数据；扩展加载链把 `SourceInfo` 带到 TUI 的 loaded resources 列表（若现列表无 sourceInfo，补字段）。
3. TUI 启动展示：`PiTuiApp` 在主 changelog 段之后按 pi 序追加；折叠文案逐字。
4. 设置版本键用 `JSON.stringify({source, scope})` 的 Java 等价：按固定键序拼 JSON（`{"source":...,"scope":...}`），保证跨进程稳定；新增 `SourcePackageKey.of(SourceInfo)` 并测试键稳定性。

### 5.4 `selfUpdateCommand` 与 `PI_SELF_UPDATE_COMMAND`

pi 证据：`config.ts:341-359`；`package-manager-cli.ts:1109-1119`、`:645`；`args.ts:466`。

Java 现状：`PackageCommand` 的 `update self` 恒走不可用臂（`subcommand/PackageCommand.java:239-249`），无配置命令、无环境变量；`Version.VERSION` 是 `0.1.0-SNAPSHOT`，无远程发布通道。

【场景】pi-java 用 Maven 分发，没有 pi 的 npm/managed 版本计划；“自身更新”只能是用户自己的命令。

【决定】拟按以下语义实现：

1. `getConfiguredSelfUpdateArgv(setting)`：`PI_SELF_UPDATE_COMMAND` 非空 ⇒ 按空白切分（不解析引号）；否则用全局 `selfUpdateCommand`。
2. `update self` / `update pi` 有配置命令 ⇒ 直接执行 argv（继承 stdout/stderr），占位符替换 `{packageName}`＝`pi-java`、`{version}`＝当前 `Version.VERSION`、`{installSpec}`＝`pi-java@<version>`；退出码透传。
3. 无配置 ⇒ 现有不可用文案，并追加 pi `:645` 的指引行（`To make \`pi-java update\` run your own update command, set PI_SELF_UPDATE_COMMAND or the selfUpdateCommand setting.`）。
4. `--help` 环境变量段加入 `PI_SELF_UPDATE_COMMAND` 行（品牌改为 `pi-java`）。

【风险/代价】`{version}` 在 pi 里是**目标版本**，在 pi-java 里只能是当前版本（无发布通道）——登记 **D221**；使用 `{version}` 的配置命令需自行处理“已是最新”场景。

### 5.5 测试

- `SettingsChangelogVersionsTest`：读写全局域、未知键保留、非法值不崩。
- `SessionShareDestinationTest`：三值臂（gist 跳过 Radius；radius 未认证文案逐字且不回落；auto 保持旧行为）。
- `ExtensionChangelogTest`：全新安装只记版本；升级展示逐包 Markdown；折叠文案；同包多 scope 去重；`changelogPath` 越界/非文件被拒。
- `PiManifestChangelogPathTest`：只含 `changelogPath` 的 `pi` 对象不阻断开目录自动发现。
- `SelfUpdateCommandTest`：env 优先于 setting；占位符替换；退出码透传；无配置文案与指引行。

## 6. W5（B277c／B279／B280）：包管理器容错、刷新与模式扩展

> **实施记录（2026-11-05，已落）**：`ResolvedPaths` 增加 `installFailures`（旧四参构造器保留）；`PackageInstaller`（新拆件）承载 `installMissing`／`installParsedSource`／temporary npm 刷新——装不上记 failure 并跳过，`onMissing=error` 仍抛 `Missing source:`；`PackageInstallWarnings`（core）产三档逐字警告，`ExtensionLoading` 汇入 `startupDiagnostics`、`ConfigCommand` 打 `Warning:` 行；`PiManifest`／`PathMetadata` 不动。`GitPackageStore.refreshTemporaryGitSource` 加 commit-ref 预检（7–64 位十六进制且 HEAD 命中才跳过网络），分支 ref 一律刷新。`ResourcePatterns.normalizePath` 补 `~`／`~/`／`~\` 展开（三形态入口同用）。`PathComparisonKeys`（pkg）为包身份、资源去重键与 trust 键提供平台大小写折叠，`ProjectTrustStore` 的最近祖先匹配与 `setMany` 删除改用 `pathsEqual`（pi `findTrustFileKeys`）。`PackageUpdater` 的 `shouldUpdateNpmSource` 返回三态并新增 `reportUnchangedNpmUpdates`（progress 型消息，CLI 消费）。**窄差**：`installFailures` 消息取根因 message（Java 把 `IOException` 包成 `UncheckedIOException`，否则文案多出类名前缀）。

### 6.1 安装失败不阻塞启动（B279）

pi 证据：`package-manager.ts:98-112,1371-1454`；`resource-loader.ts:115-133,562-566,732-736`。

- `resolve*` 返回 `installFailures: PackageInstallFailure[]`（source/scope/message/installedVersion/targetVersion）；
- 单个包安装失败 ⇒ 记 failure、跳过其资源、继续解析其余包；`onMissing` 显式 `error` 仍抛出（`Missing source: ...`）；
- 启动警告逐字：`Extension package "<source>": failed to install (<message>). <hint>`；hint＝`Run "pi remove <source>[ --local]" to remove it from settings.json.`（temporary ⇒ `Check the source and try again.`）。

Java 现状：`PackageManager.installParsedSource`（`:368-380`）把 `IOException` 转 `UncheckedIOException` 上抛 ⇒ 启动中止；`ResolvedPaths`（`pkg/ResolvedPaths.java`）无失败表。

改动：

1. `ResolvedPaths` 增加 `List<PackageInstallFailure> installFailures`（保留旧构造器）。**所有**构造点/重建点检查（docs/04 B156 教训）。
2. `resolvePackageSources` 的 npm/git 安装臂捕获安装异常：`onMissing` 显式 skip/error 语义不变；实际安装失败 ⇒ 记 failure 并 `continue`。
3. `SessionSetup` 把 `resolvedPaths.installFailures` 与 CLI `-e` 的 `installFailures` 合成启动诊断（warning），文案按品牌 `pi-java` 生成；命令行 `-e` 的 temporary hint 用“重试”臂。
4. 保持离线模式：离线时不尝试安装，也不记 failure（pi 同）。

### 6.2 临时包刷新（B280）

pi 证据：`package-manager.ts:1437-1449`、`:2137-2163`、`:1447`。

- temporary git 源：即使 `pinned`（带 ref）也要刷新 **moving branch**；只有 ref 是 7–64 位十六进制 commit 且 HEAD 已是它时才跳过网络；
- temporary npm 源：未固定版本且 registry 有更新 ⇒ 按 `name@latest`（无版本 spec 时）重新安装；失败保留缓存。

Java 现状：`PackageManager:337` 仅在 `temporary && !git.pinned()` 刷新 git；npm 臂无刷新。

改动：

1. git：`GitPackageStore.refreshTemporaryGitSource` 增加“commit-ref 已检出 ⇒ return”预检（`git rev-parse HEAD`）；其余 temporary（含 branch ref）一律 `fetch + checkout ref`。
2. npm：Java 走 HTTP registry，不执行 npm CLI。实现 `HttpNpmResolver.hasAvailableUpdate`（现成版本比较可复用）后，对未固定 temporary npm 源在解析期重新下载到同一布局；失败静默保留缓存。
3. `pi update --extensions` 的“announced but unchanged”警告：Java 在更新后比较实际安装版本与目标版本，不一致时发进度消息（逐字 pi `:1277-1298`，品牌替换）；npm 特有 `--no-audit/--no-fund/realpath` 行为不适用，登记 **D222**。

### 6.3 资源模式 `~` 扩展

pi 证据：`package-manager.ts:726,694`（`toPosixPath(normalizePath(pattern))`；`normalizePath` 展开 `~` 与 `~/`）。

Java 现状：`ResourcePatterns.matchesAnyPattern:79` 与 `normalizeExactPattern:101` 只做 `toPosixPath`，不展开 `~` ⇒ `!~/skills/pdf` 永不匹配。

改动：新增 `ResourcePatterns.expandHome(pattern)`（`~` 或 `~/` 起头 ⇒ `PiJavaPaths.home()` 拼余部，`~` 单独 ⇒ home）；两处入口统一走该函数，再 `toPosixPath`。

### 6.4 本机源身份与资源去重键

pi 证据：`utils/paths.ts:36-78`（`getPathComparisonKey`/`pathsEqual`/`isPathWithin`/`shortenHomePath`）；`package-manager.ts:1527-1546`、`resource-loader.ts`、`source-info.ts:getResourcePathKey`。

Java 现状：

- 包身份用字符串 `"local:" + resolvePath(...)`（`PackageManager.java:440` 一带），Windows 大小写不同会产生**两条身份**；
- 资源去重：`ResourcePatterns` 用 `canonicalizePath` 字符串，未见大小写折叠。

改动：新增 `PathComparisonKeys`（coding-agent util）：`key(path) = Platform.isWindows ? resolved.toLowerCase(Locale.ROOT) : resolved`；`equal`/`within` 同 pi。用于包身份、扩展预加载/失败表键、资源去重键。`shortenHomePath` 已由 TUI 域使用，L7 复核。

### 6.5 测试

**实施形状（2026-11-05）**：

- `PackageInstallFailureTest`（8 项）：坏包＋好包 ⇒ 启动继续、好包资源在、failure 三字段（source/scope/message＝根因文案）；`onMissing=skip` 不记 failure；`onMissing=error` 仍抛逐字 `Missing source:`；`-e` 源失败也进 `installFailures`；三档警告文案逐字（user 无 `--local`／project 带 `--local`／temporary「Check the source and try again.」）；`PackageInstallWarnings.collect` 按 source 后写覆盖。
- `TemporarySourceRefreshTest`（9 项）：分支 ref 照常 fetch；commit-ref 已检出 ⇒ 零 spawn；commit-ref 不符 ⇒ 刷新；capture 失败静默保缓存；temporary npm 未固定版本 ⇒ `name@latest`；带 range spec ⇒ 用原 spec；刷新安装失败静默保缓存；registry 查询失败跳过；版本已最新不重装。
- `PathComparisonKeysTest`（9 项）：绝对化＋normalize；Windows 大小写折叠；`isWithin` 自身/后代/前缀兄弟；合成路径直通；`resolve` 后 local 包身份折叠；`canonicalizePath` 失败回原串；`~`／`~/`／`.\~` 展开三形态。
- `PackageUpdateMessageTest`（3 项）：宣告 2.0.0 但装回 1.0.0 ⇒ progress 文案逐字；版本确实变化 ⇒ 不发；无 targetVersion（查询失败保守臂）⇒ 不发。
- `SourceResolverTest#localIdentityResolvesAgainstScopeBaseDir` 改用比较键断言（行为变更，非放宽）。

## 7. W6（B277d）：模型引用解析统一策略

### 7.1 pi 事实

`core/model-resolver.ts` 重构为单一策略：

- `resolveModelReference(request, models, usable)`（`:149-233`）：`thinkingSuffix` 三态（`none|strict|lenient`）、`fuzzy`；`provider/rest` 只是**提示**，全串也可能是别家的模型 id（如 `zai/glm-5`、`xiaomi/mimo-v2.6-pro`）；候选先收集后选 provider：
  - 同名候选仅一家 provider ⇒ resolved；
  - 命名 provider 可凭据使用 ⇒ 它赢；
  - 否则唯一可用的其它 provider 赢；都没有可用但存在命名候选 ⇒ 命名候选（供 `--api-key` 首配）；
  - 多 provider 且无法裁决 ⇒ `ambiguous`（携带 candidates 与 usableCount）；
- `resolveModelScopeFromModels`（`:332-405`）：歧义 ⇒ **加入所有候选** + warning `Pattern "<p>" matches several providers: <排序列>. Use provider/id to choose one.`；无匹配 ⇒ `No models match pattern "<p>"`；非法思考后缀 ⇒ lenient 递归 + warning；
- `resolveCliModel`（`:450-530`）：
  - `--provider` 未知 ⇒ `Unknown provider "<p>". Use --list-models ...`；
  - 歧义 ⇒ `Model "<m>" is ambiguous across providers: <provider/id 排序列>. <authHint> Use --provider or provider/model.`；authHint 三臂（inline key / 无可用 / 多个可用）；
  - 无 `target`（没有任何 provider 提示）⇒ `Model "<m>" not found. Use --list-models ...`，**不再**兜底到默认 provider 的自定义 id；
  - 有 target ⇒ 自定义 id 警告 `Model "<id>" not found for provider "<p>". Using custom model id.`；`--thinking` 显式时后缀留在 id 里；
- `findExactModelReferenceMatch`（`:179-186`）成为 `resolveModelReference(... none, false)` 的薄包装，`/model` 用它。

### 7.2 Java 现状

- `ModelPatterns`（`pi-java-ai/.../model/ModelPatterns.java`）仍是旧三段精确匹配＋子串模糊＋单条选择；无 provider 提示语义、无 ambiguous 结果。
- `ModelScopeResolver`（同包）：歧义落为 `no-match`；无 ambiguous 诊断。
- `DefaultModelResolver.resolve(pattern, defaultProvider)`（同包）：未知 provider 抛、裸未知 id 兜底到 default/google 并 warn；`SessionWiring`（`:104-140`）与 `ModelScoping` 消费。
- `ModelExactMatch.findExact`（coding-agent `core/ModelExactMatch.java`）是旧三段；`ModelCommands`（`:77,91`）消费。
- `AuthCommand`/`credential-print` 用 provider 限定的旧解析。

### 7.3 改动（建议拆 W6a/W6b 实施）

**W6a：解析核（ai 模块）**

1. 新增 `ModelReferenceResolver`（`pi-java-ai/.../model/`）：`Request(text, providerConstraint, ThinkingSuffix, fuzzy)`、`Result.resolved/ambiguous/notFound(target)`、`usable` 谓词；逐字移植 `resolveModelReference`/`selectReference`/`selectFuzzy`/`splitProviderPrefix`/`splitThinkingSuffix`。
2. `ModelPatterns` 保留 `parse` 兼容壳，内部改调新核；`findExactReference` 改为 `thinkingSuffix=none, fuzzy=false` 的薄包装。
3. `ModelScopeResolver.resolvePlain/Glob` 改用新核：ambiguous ⇒ 收集全部候选并产 `AMBIGUOUS` 诊断（新增 code），warning 文案逐字。
4. `ModelScopeDiagnostic` 增加 `AMBIGUOUS` 码与 message 构造。

**W6b：宿主接线（coding-agent）**

5. `DefaultModelResolver.resolve` 改为：
   - 需要 `usable` 谓词 ⇒ 新增重载 `resolve(pattern, provider, Predicate<String> usable)`；`SessionWiring` 用 `DefaultProviders.hasConfiguredAuth(provider)`（含 settings/api-key/env）传参；`cliProvider` 未知时返回错误而非抛（由调用方输出诊断并 exit 2）。
   - 保留 Java 独有的“裸 provider 名 / `provider/` ⇒ 默认模型”支路（pi 无；既有 D，且用户可见便利臂）。该支路排在 provider/rest 提示核之前，避免裸 `anthropic` 被子串命中。
   - 裸未知 id 且无 provider 提示 ⇒ 不再自定义兜底；改为错误（与 pi 一致）。`--provider` 未知 ⇒ 错误文案逐字。
   - 自定义 id 兜底只在 target 存在时发生（显式 `--provider` 或 `provider/` 前缀）。
6. `SessionWiring`/`ModelScoping`：`--models` 歧义 warning 走新诊断；模型选择失败尽量在启动诊断阶段汇总，而不是抛裸异常（pi `main.ts:476-490` 把 warning 进 diagnostics）。
7. `ModelExactMatch.findExact` 改调新核（none/false）；`ModelCommands` 直设臂沿用；`AuthCommand`/credential print 用 `provider` 限定的 `strict, fuzzy` 解析。
8. `inlineApiKey`：`Args.apiKey()` 非空 ⇒ CLI 解析的 `usable` 恒 false（pi `main.ts:480`）。
9. 删除 `ModelPatterns` 中已被新核取代的旧 `tryMatch`/`parse` 私有实现（保留必要薄壳），避免两套语义。

### 7.4 测试

`ModelReferenceResolverTest`（ai）：

- `provider/id` 提示：`openai/foo` 不存在，但别家 id 恰为 `openai/foo` ⇒ 无 provider 名候选时按裸 id resolved；命名 provider 可用 ⇒ 命名赢。
- 同名跨 provider + 单可用 ⇒ 可用者赢；双可用 ⇒ ambiguous；双不可用 ⇒ ambiguous。
- inline key ⇒ usable 全 false ⇒ 命名候选兜底（custom id 路径）。
- 思考后缀：`id` 含冒号且全串命中 ⇒ 不切；`none` 不切；strict 非法后缀留 id；lenient 非法后缀警告＋回退。
- scope：歧义 ⇒ 两个候选都入 scope + 一条 warning；无匹配 text；glob 优先精确。

`CliModelResolutionTest`（coding-agent）：未知 provider/歧义/not-found/custom id 四类文案逐字；`--thinking` 与后缀共存；`--api-key` 的 authHint。

`ModelCommandsTest`：`/model` 旧精确行为不变（含 scoped 先、刷新后）。

### 7.5 风险

- 【模型可见】裸未知 `--model foo` 从“默认 provider 下的自定义模型”变为“报错”。这是 pi 新锚点行为，必须与用户确认（见 §14）。
- 【内部】`usable` 谓词把凭证状态带进纯解析核；保持 ai 模块不依赖 coding-agent 的 `DefaultProviders`，只接受谓词。
- 【内部】`DefaultModelResolver` 的 Java 独有裸 provider 支路与 pi 核的“provider 提示”可能冲突；用顺序固定（Java 支路先）并加回归锁。

## 8. W7（B277e）：tool_search Unicode 分词与字段加权

### 8.1 pi 事实

`extensions/tool-search/tool.ts`：

- `tokenize`（`:42,77-96`）：NFC 规范化；camel/缩写边界；小写；非 `\p{L}`/`\p{M}`/`\p{N}` 替换为空格；ASCII 直接按空格切，非 ASCII 用 `Intl.Segmenter` word 粒度；停用词与英文单数化只作用于纯 ASCII 词。
- `createToolSearchDocument`（`:119-129`）：`text`＝描述＋schema 文本；`namespaceText` 单独存放命名空间名/描述/说明；名称不再是 text 的一部分。
- 排序（`:179-256`）：`NAME_WEIGHT=3`、`NAMESPACE_WEIGHT=0.5`；名称/命名空间/描述分字段；IDF 覆盖三字段；长度归一化只用描述；`covered`（命中的查询词数）先于分数；整名精确查询排第一；本地名（最后一个 `__` 段）全命中 ⇒ 加 `fullNameBonus`。
- `reachesTools: () => "all"`（`:322`）；删除 `isToolSearchTool`（`:168-171` 旧判定不再导出）。

### 8.2 Java 现状

- `ToolSearchTokenizer`（`extension/toolsearch/ToolSearchTokenizer.java`）：纯 ASCII 正则切分，无 NFC/Segmenter。
- `ToolSearchDocument`（`:18-29`）：namespace 拼进 text，无独立字段。
- `Bm25Ranker`（`:1-160`）：单字段 BM25、无字段权重/覆盖数/精确名/本地名奖励。
- `ToolSearchTool.isToolSearchTool`（`:91-94`）仍按 schema 引用判等；pi 已删除该导出。

### 8.3 改动

1. `ToolSearchTokenizer.tokenize`：NFC ⇒ 边界正则 ⇒ 小写 ⇒ `[^\p{L}\p{M}\p{N}]+` ⇒ 空格；纯 `[a-z0-9 ]` 直接 `split`，否则用 `BreakIterator.getWordInstance(Locale.ROOT)` 且只收 word-like 段；停用词/stem 只对 `[a-z0-9]+` 应用。登记 **D224**：JDK 根 locale 的 UAX#29 断词与浏览器 ICU 词典分词在连续汉字上可能产生不同词数（测试以词组/混合标识符为主，连续汉字整段比较）。
2. `ToolSearchDocument` 增加 `namespaceText`；`of(tool, namespace)` 不再把名称/命名空间拼进 text。
3. `Bm25Ranker` 重写为分字段版本（名称集、本地名集、text 计数、namespace 集）；排序键依次 `exact`、`covered`、`score`；同分稳定保持文档序。
4. `ToolSearchTool`：文档构造传入 namespace；删除 `isToolSearchTool` 及其调用点（若扩展替换臂依赖，改用注册表首见/名称判定，与 pi 新锚点一致）。
5. `reachesTools` 已在 Java（`:177-181`）；保持。

### 8.4 测试

- 分词：`mcp__app__thread_read` ⇒ `mcp app thread read`；`dev-radius` 与 `dev_radius` 同词；中文/日文无空格词条可被命中；ASCII 快路径与 Segmenter 路径同结果。
- 排序：仅描述命中 vs 名称命中 ⇒ 名称优先；命中 2 个查询词 vs 1 个 ⇒ 2 个优先；整名查询 ⇒ 第一；`app` 命中 `mcp__peekaboo__app` 获得本地名奖励；同 id 跨 namespace/Tie 保序。
- 回归：docs/38 既有 toolsearch 测试按新排序更新，禁止旧断言伪装通过。

### 8.5 风险

- 【模型可见】搜索结果顺序变化，模型看到的工具候选集顺序改变；需 golden/排序表钉住。
- 【内部】`Intl.Segmenter` 与 JDK 分词差异登记 D224；若后续引入 ICU4J 依赖须用户批准（docs/08 风险表）。

## 9. W8（B277f）：HTML 导出与 markdown 渲染上下文

### 9.1 pi 事实

- `export-html/index.ts:146-176`：新增 `vendor/marked-cjk-friendly.min.js`，模板占位 `{{MARKED_CJK_FRIENDLY_JS}}`。
- `template.js`：
  - 系统消息渲染（`:321-430,760`）：首条 system 消息渲染完整提示（content＋sections）；后续 system 消息渲染为 update（`Removed/Updated system prompt section "<name>"`，工具增删摘要）；折叠摘要仍按 branch 判定；
  - 深链改为迭代遍历（`sortChildren`/`mapNodes`/`markActive`），避免超长会话爆栈（`:1c720d285`）；
  - `formatReadLineRange(offset, limit)` 统一处理数字字符串/`null`（`:659`）。
- `markdown-transform` 上下文（`types.ts:1562-1568`）：`messageId?`、`timestamp?`（仅从持久化条目渲染时有值）。
- `KeybindingHints`（`types.ts:152`）传给 `ToolRenderContext`/`MessageRenderOptions`/`EntryRenderOptions`；`blocked` 加在 `showComponent` 选项（`:246`）。

### 9.2 Java 现状

- `HtmlExporter.renderMessage`（`export/HtmlExporter.java:182-201`）把 system 消息只渲染成 `meta("system")`，不输出正文/sections/更新；无系统条目时间戳；无深链迭代遍历（若按树递归，需核查）。
- `MarkdownTransformContext`（`extension/MarkdownTransformContext.java:12-14`）只有 messageType/isStreaming/availableWidth。
- 扩展渲染 API 是文本级（D92）：`ToolCallRenderer`、`ExtensionMessageRenderer`、`ExtensionEntryRenderer` 无 keybinding hints；`ExtensionUI` 无 `blocked`。

### 9.3 改动

1. `HtmlExporter`：
   - 增加系统消息渲染：首条 system ⇒ 完整 content＋sections（`systemMessageText`，sections 非空按行）；后续 system ⇒ update 文案（section null ⇒ `Removed ...`，否则 `Updated ...`），工具增删附 `tools +name/-name`；
   - 系统条目渲染时间戳（复用 entry `timestamp`，样式 `system-entry > .message-timestamp`）；
   - 条目树遍历改迭代（显式栈），深度 10k 回归；
   - `formatReadLineRange` 等价函数：数字字符串/`null` 不产生畸形区间（导出 HTML 的 read 工具行、树选择器同源）；
   - 内置资源加入 CJK-friendly marked 不可行（Java 用自有 `MarkdownToHtml`）：改为验证 `MarkdownToHtml` 对 CJK 强调（`**中文**`、`*中文*`、全角标点）渲染正确，并登记 **D225**（不使用 JS marked 方言）。
2. `MarkdownTransformContext` 增加可空 `messageId`/`timestamp` 组件（保留三参构造器）；TUI 渲染持久化条目时传入 entry id/ISO 时间，实时消息不传。
3. `KeybindingHints`：按文本级渲染 API 现状登记 **D220**——Java 扩展渲染器返回纯文本，宿主 TUI 自行展示键位提示；不在本包扩接口（若用户要求对齐，另开 API 变更包）。
4. `blocked`：属扩展 UI/TUI，随 E5-31 转 L7，本包不做。

### 9.4 测试

- `HtmlExportSystemMessagesTest`：首条 system 完整提示；后续 update 文案；sections 增删；工具增减摘要；时间戳存在。
- `HtmlExportDeepChainTest`：10,000 条线性会话导出不崩（栈深度）。
- `HtmlExportCjkTest`：`**中文**` 强调渲染为 `<strong>`；全角标点不被吞。
- `MarkdownTransformContextTest`：持久化条目渲染时 transformer 收到 entry id 与 timestamp；实时渲染为 null。

### 9.5 风险

- 【模型可见】导出 HTML 增加提示正文，文件体积上升；与 pi 一致。
- 【内部】D225 意味着导出 HTML 的 Markdown 方言与 pi 不完全同源（Java 自有渲染器），以行为等价测试为准。

## 10. W9（B259）：思考档 RPC 按模型能力过滤

### 10.1 pi 事实

`agent-session.ts:2726-2753`：

```ts
cycleThinkingLevel(options = {}): ThinkingLevel | undefined {
  if (!this.supportsThinking()) return undefined;
  const levels = this.getAvailableThinkingLevels();
  ...
}
getAvailableThinkingLevels(): ThinkingLevel[] {
  if (!this.model) return [...THINKING_LEVEL_OPTIONS];
  return getSupportedThinkingLevels(this.model);
}
```

`rpc-mode.ts:502-512`：`cycle_thinking_level` 无级别 ⇒ `success(id, ..., null)`；`get_available_thinking_levels` 返回过滤集。

### 10.2 Java 现状

- `RpcSessionPayloads.cycleThinking`（`:120-126`）恒在七级全集上轮转；
- `availableThinkingLevels()`（`:135-138`）恒返回七值全集；
- `ThinkingCommands.availableLevels`（`core/slash/builtin/ThinkingCommands.java`）已按目录过滤（可作为复用点）。

### 10.3 改动

1. `cycleThinking` 改为按 `ThinkingCommands.availableLevels(session)`：模型不支持思考（`ModelThinkingLevels.supported` ⇒ `["off"]` 且不支持）⇒ 返回 null；否则在可用集上轮转。
2. `availableThinkingLevels(session)` 改为同一可用集；RPC 返回过滤后的标签列表。
3. 若 model 为 null ⇒ 七值全集（pi 同）。

### 10.4 测试

- 非 reasoning 模型：`get_available_thinking_levels` ⇒ `["off"]`；`cycle_thinking_level` ⇒ `null`。
- reasoning 且 thinkingLevelMap 缺 xhigh/max ⇒ 过滤集不含两档；轮转跳过不可用档。
- 无模型 ⇒ 七值全集。
- `/thinking` 命令原有过滤回归不变。

## 11. W10：其余 CLI／平台修复

| # | 项 | pi 证据 | Java 现状/改动 | 编号 |
|---|---|---|---|---|
| 1 | print 模式长度截断 | `print-mode.ts:151-157`：末条 assistant `stopReason === "length"` ⇒ stderr `Response was truncated: the model reached its output token limit.`＋exit 1 | `PrintMode` 只流式打印；改为在 stream 结束后读末条 assistant 的 stop reason（经 `SessionResult.entries()` 末条），命中即输出＋非零 | **B281** |
| 2 | trust 优先级 | `project-trust.ts:52`：`!hasTrustRequiringProjectResources ⇒ trustStore.get(cwd) ?? true`；`trust-manager.ts` 路径键比较 | `ProjectTrustResolver.java:53-56` 无资源 ⇒ 恒 true；改为先读 store，再回落 true；`ProjectTrustStore` 的键比较用 W5 的 `PathComparisonKeys` | B277 |
| 3 | 删除的 cwd | `main.ts:591-599`：`process.cwd()` ENOENT ⇒ 红字错误 exit 1 | `Main` 启动前检查 `user.dir` 可达；不可达逐字输出 | B277 |
| 4 | 启动诊断顺序 | `main.ts:884-899`：`startupDiagnostics`（settings＋runtime）在 help/list-models 前构造并报告 | Java help/list-models 不建会话；若可低成本统一则补，否则登记 D（内部诊断顺序） | B277 |
| 5 | CLI 帮助文案 | `args.ts:294-345,463-466`：`mcp` 描述、session 六行、`--export <file> [output]`、`PI_SELF_UPDATE_COMMAND` | `HelpText.java` 逐字更新（品牌 `pi-java`，`update` 指引按 W4） | B277 |
| 6 | slash 描述文案 | `slash-commands.ts:26-41`：`/import` `/share` `/resume` 三行 | `CommandRegistry` 内置描述逐字更新 | B277 |
| 7 | 图片编码偏好 | `utils/image-resize-core.ts:134-147`：同质量下 PNG/JPEG 取较小者，低质量 JPEG 只作兜底 | `pi-java-agent-core/.../tool/image/ImageResizer.java:127-135` 现按 PNG→JPEG 顺序；改为先编码两件、取较小者、再质量阶梯 | **B282** |
| 8 | shell 平台兼容 | `utils/shell.ts`：Windows Store 别名用 `access(F_OK)` 不 `existsSync`；PowerShell 安装目录兜底；macOS 先 `SIGSTOP` 进程组再杀 | Java `DefaultShellExecutor`/`PowerShellShellExecutor` 的解析链；Windows 两项对齐，macOS `SIGSTOP` 在 `killProcessTree` 等价点补 best-effort | **B283**（macOS 部分登记 D226） |
| 9 | auth lock EPERM | `auth-storage.ts:19-31` | Java 用 `FileChannel` 锁，无 lockfile 目录重试面 | **D223** |
| 10 | 工具计时/read 行号/Skill warnings/CSI/剪贴板/翻页/模型名显示 | 见 §1.2 L7 行 | 全部归 L7 | — |

### 11.1 测试

- `PrintModeTruncationTest`：length 收尾 ⇒ stderr 逐字＋退出码 1；aborted/error 不误报。
- `TrustStorePrecedenceTest`：无信任敏感资源但 store 记 false ⇒ 不信任；store 记 true ⇒ 信任；无 store ⇒ 信任。
- `DeletedCwdTest`：模拟 cwd 不可达 ⇒ 错误文案＋exit 1（可用临时目录删除＋`user.dir` 夹具）。
- `HelpTextParityTest` / `SlashDescriptionParityTest`：逐字断言（品牌替换白名单）。
- `ImageEncodingPreferenceTest`：同等质量下 JPEG 更小 ⇒ 选 JPEG；均超限 ⇒ 降质量 JPEG。
- `ShellResolutionTest`（Windows）：Store 别名可执行；PATH 缺失但 `ProgramFiles` 有 pwsh ⇒ 命中。

## 12. 实施顺序与验证门

### 12.1 建议顺序

```text
W2（小）→ W3（小）→ W9（小）→ W10（混合）
→ W1（启动窗，跨线程风险最高）
→ W4（settings/分享/自更新/changelog，跨 record）
→ W5（包管理器容错）
→ W7（tool_search）
→ W8（导出/上下文）
→ W6a → W6b（模型解析，最大且模型可见行为变化）
```

理由：先做低风险、可独立验证的块；W1 与 W4/W5 都会动资源/终局路径，放在中层；W6 行为变化最大，放最后并有独立审核点；W6a 与 W6b 分开提交，避免 ai 与 coding-agent 同时红。

### 12.2 门槛（沿用 docs/08 §8）

1. 每个 W 先落 RED 测试（能复现新锚点差异），再写实现。
2. `mvn test -pl <module> -am`；跨模块（ai/agent-core/tui）必须带 `-am`。
3. 包闭环前 `mvn clean verify`：测试零失败、Checkstyle 零错误零警告、SpotBugs 零新增。
4. 模型可见文本逐字断言；必要时从 `56cae7823` 跑 TS 生成 golden，不用 Java 自产 golden 自证。
5. 文件 ≤500 行，拆分沿用批 29 模式；不得 `@SuppressWarnings` 掩盖新告警。
6. 验证日志不落 `target/`（`clean` 会删）。
7. 每包完成后回填 `map/04`、`docs/04`、`docs/06`；未验证不得翻“对齐”。

## 13. 风险与代价

- 【模型可见】**W6** 改变 `--model foo` 的兜底行为（裸未知 id 由“自定义模型”变“报错”）与歧义文案。必须按 pi 语义执行；若用户要保留 Java 旧兜底，则改登记 D 并降级 A-7 判定。
- 【模型可见】**W1** 启动窗内到达的用户消息改排队而非新 run；若启用 `agentRunActive` 后路由门未同步，可能出现“会话看似空闲却拒绝 prompt”的死锁。用 `isStreaming` 单点改造＋并发测试钉住。
- 【模型可见】**W5** 安装失败的包从“启动失败”变“启动继续＋警告”；这会让此前被失败掩盖的坏配置变成可见警告，需在 docs/04 记录行为变化。
- 【内部】**W4** 给 `PathMetadata`/`SourceInfo`/`ResolvedPaths` 加组件：record 加组件必须搜**重建点**（`withX`/`toResolvedPaths`/复制构造），不能只改构造点（docs/04 B156 教训）。
- 【内部】**W7** 的 Unicode 分词与浏览器 ICU 分词存在方言（D224），连续汉字检索可能不同；以行为测试与 D 登记控制，不引入新依赖。
- 【内部】**W8** 不引入 JS marked；CJK 强调由 Java 渲染器保证（D225），导出 HTML 的字节形态必然与 pi 不同，判定以渲染结果为准。
- 【范围】**W6/W7** 工作量大（预计各 300–600 行）；W6 拆 W6a/W6b 两次提交。若排期紧，W6a 单独闭环、W6b 随下一次。

## 14. 需要用户裁决

【场景】本稿把新锚点差异归为十个工作包；其中四项涉及行为变化或范围转移，需在批准时明确。

【决定（建议）】

1. **本稿整体批准**：W1–W10 作为 L5 实施包；W6 拆 W6a/W6b。（待最终批准）
2. **转出确认**：E3-3 → L7、E5-31 → L7、E5-33 → L8；map/04 保留证据与指针、权重 0。**已裁决：确认转出（2026-11-05）。**
3. **模型解析行为**：采用 pi 新锚点语义（裸未知 `--model` 报错；歧义报错/警告；scope 歧义加入所有候选）——即 W6 不保留 Java 旧兜底。**已裁决：按 pi 语义（2026-11-05）。**
4. **自更新语义**：`selfUpdateCommand` 在 pi-java 里按 §5.4 执行（`{version}`＝当前版本，D221）。**已裁决：执行配置命令（2026-11-05）。**
5. **KeybindingHints**：按 D220 不扩文本级扩展渲染 API；`blocked` 随 L7。**已裁决：D220＋随 L7（2026-11-05）。**
6. **新编号**：B279（安装失败不阻塞）、B280（临时包刷新）、B281（print 截断）、B282（图片编码）、B283（shell 平台）；D219（激活序来源）、D220（KeybindingHints）、D221（自更新占位符）、D222（npm CLI 特有行为不适用）、D223（auth EPERM）、D224（Unicode 分词方言）、D225（导出 Markdown 方言）、D226（macOS 进程组 STOP）。
7. **E2 五项存疑**维持（E2-22/32/43/50/54），不随本包强行翻对齐。

【风险/代价】[内部，模型不可见] 审核项集中在范围与行为变化；批准后按 §12 顺序实施，任何一项未通过验证都不得翻账。

## 15. 下一步

1. 用户审核本稿并裁决 §14 七项。
2. 批准后先落 W2/W3/W9 的 RED 测试与实现（小包先验），再按 §12.1 推进。
3. 每完成一个 W，回填 `map/04` 行证据与判定；全部 W 闭环后更新 `docs/04`（B275/B276/B277/B259 销号，新 B/D 登记）、`docs/06` 的 L5 状态与 `docs/08 §9` 进度表。
4. `docs/05` 总表与 `map/ANCHOR.md` 仍留到 L6–L8 全部闭环后统一换锚。
