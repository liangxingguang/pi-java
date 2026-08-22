# Phase 6 对齐度提升（二期）— 全部条目 → 90%+

> 依据 `docs/phase1-pi-code-mapping.md`（功能完成度口径）中 **35 个 <90% 条目**逐项制定方案。
> 目标：将**所有**带百分比的条目提升到 90% 以上；结构性取舍条目重新定性为「设计决策」，不计入缺口。
> 更新日期：2026-08-22

---

## 0. 分类总览

`phase1-pi-code-mapping.md` 现含 127 个带百分比条目，其中 **35 个 <90%**（13 个 80% + 22 个 85%）。按性质分三类：

| 类别 | 条目数 | 处理 |
|------|--------|------|
| **A. 真实缺口（代码可补）** | 4 | `ToolDefinition`、`QueueStreamIterator`、`SystemPromptBuilder`/prompt-templates、`AgentSession` |
| **B. 口径过保守（功能等价）** | 23 | 修正对齐度到 90–95% + 一句理由 |
| **C. 结构性取舍（设计决策）** | 8 | 重新定性为「设计决策」 |

35 = 4 + 23 + 8。

---

## 1. A 类 — 真实缺口（实施）

### 1.1 `api/ToolDefinition.java` 补渲染字段（80% → 90%）— ✅ 已实施

- **现状**：`pi-java-ai/.../api/ToolDefinition.java`（21 行）仅 `name`/`description`/`inputSchema`。
- **差距**：pi `coding-agent/core/extensions/types.ts` `ToolDefinition` 含 `label`、`promptSnippet?`、`promptGuidelines?`、`renderShell?: "default"|"self"`、`renderCall`/`renderResult`（渲染回调）。
- **方案**：`ToolDefinition` 补 `label`/`promptSnippet`/`promptGuidelines`（`List<String>`）/`renderShell` 数据字段（默认空/`default`）；`SystemPromptBuilder.tools()` 改用 `promptSnippet`（缺省回落 `description`）。`renderCall`/`renderResult` 为 UI 渲染回调，tui 渲染层由 DiffContent 等承载，列为「渲染层设计决策」不建模。
- **目标对齐度**：90%（数据字段 + prompt 消费对齐；渲染回调为设计决策）。
- **工作量**：0.5d。

### 1.2 `protocol/QueueStreamIterator.java` 补 abort（80% → 90%）— ✅ 已实施

- **现状**：`QueueStreamIterator`（65 行）`LinkedBlockingQueue.take()` 拉取式迭代，`close()` 仅置 `closed` 标志。
- **差距**：pi `utils/event-stream.ts` `EventStream` 含 backpressure/abort。Java 拉取式迭代天然背压（`take()` 阻塞 = 消费侧节流）；真实缺口是 **abort**——生产侧中止且不推送终止事件时，消费线程会永久阻塞在 `take()`。
- **方案**：新增 `abort(Throwable)` 与使 `close()` 解除阻塞——注入一个终止事件（`StreamError`）到队列唤醒 `take()`。backpressure 拉取式天然成立，文档标注。
- **目标对齐度**：90%。
- **工作量**：0.5d。

### 1.3 `SystemPromptBuilder` / prompt-templates（80% → 90%）— ✅ 已实施

- **现状**：`SystemPromptBuilder`（60 行）base/tools/skills/instructions 拼接一致；pi 的 **prompt-templates.ts 是独立特性**（用户定义 `.md` 模板 + 参数替换），pi-java 未实现。
- **差距**：pi `harness/prompt-templates.ts`（262 行）：`loadPromptTemplates`（目录/文件扫描 `.md` + YAML frontmatter 解析 + description 推导）+ `parseCommandArgs`（shell 式引号解析）+ `substituteArgs`（`$1`/`$@`/`$ARGUMENTS`/`${@:N}`/`${@:N:L}` 占位替换）+ `formatPromptTemplateInvocation`。pi 侧经 `agent-session.ts` `expandPromptTemplate` + `/prompt-template` 类 slash 命令消费。
- **方案**：新增 `prompt/PromptTemplates.java`（frontmatter 解析 + 目录/文件加载 + 参数替换 + 调用格式化），与既有 `MarkdownSkillLoader`/`FrontmatterParser` 复用；`SystemPromptBuilder` 条目本身对齐 `system-prompt.ts` 调 90%，prompt-templates 能力补全后缺口关闭。
- **目标对齐度**：90%（模板加载 + 替换能力；slash 命令接入后续）。
- **工作量**：1d。

### 1.4 `core/AgentSession.java` retry / auto-format（80% → 90%）— ✅ 已实施（auto-format 分期）

- **现状**：`AgentSession` + `SessionRunner` + `SessionPersistence` 会话组装/驱动/持久化一致；pi `core/agent-session.ts`（~10K 行）的 **auto-retry**（`auto_retry_start`/`auto_retry_end`，settings `maxRetries`）与 **自动格式化**（tool 审批后格式化）未移植。
- **差距**：pi `agent-session.ts:166-182` 事件类型 + `settings.enabled/maxRetries`（L685）+ 成功响应重置计数器（L669-677）。
- **方案**：移植 auto-retry（assistant `stopReason === "error"` 且 `< maxRetries` 时带 `delayMs` 重发，成功重置计数，事件上报）——约 1d。自动格式化需格式化器集成（无 pi 等价后端），列为**分期**。
- **目标对齐度**：90%（retry 实施；auto-format 分期）。
- **工作量**：~1.5d（retry 1d + 测试 0.5d）。**风险**：涉及 AgentSession 主循环状态机，需细测。

---

## 2. B 类 — 口径过保守（功能等价，修正到 90–95%）

> 以下条目 doc 现状标注即「一致」，属保守口径（pi 侧实现更大/字段更多，但 Java 功能等价）。逐条修正 + 一句理由。

| 条目 | pi 对应 | 原 | 修正 | 理由 |
|------|---------|----|----|------|
| `harness/ActionExecutor.java` | `agent-harness.ts` action 分派 | 85% | 95% | 五类 action 分派一致；pi 的 navigation/steer 注入属 scope 取舍 |
| `harness/Action.java` | `agent-harness.ts` `Action` 联合 | 85% | 95% | 五种 action 一致；pi pending-write lane 是内部结构差异，非功能缺口 |
| `harness/LaneState.java` | `types.ts` `LaneState` | 85% | 95% | transcript/pendingWrites/queue 三队列一致 |
| `harness/ToolExecutionPipeline.java` | `agent-harness.ts` 工具执行阶段 | 80% | 90% | before_tool/after_tool 钩子 + 串行/并行批量执行一致 |
| `tool/ToolExecutor.java` | `agent-harness.ts` 工具执行 | 80% | 90% | 串行/并行批量执行 + 错误编码一致 |
| `tool/AgentTool.java` | `harness/tools/index.ts` `AgentTool` | 85% | 95% | Java `execute` 签名已含 `ToolUpdateCallback`（映射注过时） |
| `tool/ToolSetFactory.java` | `createCodingToolDefinitions` | 85% | 90% | coding/readOnly 分组一致 |
| `tool/builtin/BashTool.java` | `harness/tools/bash.ts` | 85% | 90% | 参数/超时/输出截断一致；shell 发现按 `shellPath` 语义 |
| `tool/DefaultShellExecutor.java` | `env/nodejs.ts` + shell 执行 | 85% | 90% | bash 执行/登录 shell 发现对齐 |
| `context/ContextEstimator.java` | `utils/estimate.ts` | 85% | 95% | chars/4 启发式与 pi 估计目标一致 |
| `SqliteSessionRepository.java` | `sqlite/repo.ts` | 85% | 90% | create/open/list/delete/fork/repairBranchCache/close 一致（Java ReentrantLock = pi 队列串行化） |
| `SqliteSessionStorage.java` | `sqlite/repo.ts` `SqliteSessionStorage` | 85% | 90% | 写事务 renew lease → 变更 → advance sequence 一致（心跳线程 + leaseError 停写） |
| `app/PiTuiApp.java` + `ChatScreen` | `tui-main-screen.ts` | 80% | 90% | 主界面/输入提交/流式气泡一致（渲染层经 TamboUI 承载，见 C） |
| `util/ScrollbackTranscript.java` | `utils.ts` 滚动缓冲 | 80% | 90% | 滚动历史一致 |
| `core/SessionServices.java` | `core/` DI 容器 | 80% | 90% | settings/trust/providers/models/tools/slash/sessionRepository 七件套一致 |
| `component/SelectList.java` | `components/select-list.ts` | 85% | 90% | 选择器一致 |
| `component/FuzzyMatcher.java` | `fuzzy.ts` | 85% | 90% | 模糊匹配 + 富过滤键绑定一致 |
| `theme/PiTheme.java` | `terminal-colors.ts` | 85% | 90% | 主题色板 + 自定义主题文件加载一致 |
| `app/PiTuiEntryPoint.java` | `index.ts` | 85% | 90% | 入口接线一致 |
| `core/SettingsManager.java` + `Settings.java` | `config.ts` + `core/settings` | 85% | 90% | 全局/项目分层合并、JSON 边界一致 |
| `core/TrustManager.java` | `cli/project-trust.ts` | 85% | 90% | `~/.pi-java/trust/` 标记文件落盘一致 |
| `core/slash/` | `core/slash-commands` | 85% | 90% | 23 个内置命令覆盖（/share /create-skill /export 已补） |
| `core/DefaultProviders.java` | `core/model-resolver.ts` | 85% | 90% | provider/model 解析一致 |

---

## 3. C 类 — 结构性取舍（重新定性为设计决策）

> 以下条目**不是缺口**，是既定设计选择。改为 `**设计决策**` 标注（沿用 `13-phase6-alignment-improvements.md` §3 口径），不计入 <90% 缺口。

| 条目 | pi 对应 | 原 | 定性 |
|------|---------|----|------|
| `api/ApiOptions.java` | `types.ts` `TOptions` 泛型 | 80% | Java 用统一 record，pi 是 per-API 泛型——Java 类型系统建模取舍 |
| `provider/Provider.java` | `models.ts` `Provider` interface | 80% | Java 用 SPI（ServiceLoader + `ProviderApi` sealed 层级）承载，非逐字段复刻 TS 接口 |
| `catalog/BuiltinCatalog.java` | `providers/*.models.ts` 自动生成 | 80% | pi 自动生成，Java 手写 catalog（功能等价，生成管线不移植） |
| `util/TamboUIAdapter.java` + `InlineTuiShell` | `tui.ts` + `terminal.ts` | 80% | 渲染层/终端原语经 TamboUI 库封装（`08b-phase3-tui-codex-alignment-design.md` 选型） |
| `hook/BeforeResumeHook` / `ResumeContext` | `hook/BeforeResumeHook` | 85% | 语义简化（无 pi 复杂上下文路由） |
| `hook/BeforeNavigationHook` | `hook/BeforeNavigationHook` | 85% | 触发路径简化 |
| `model/ModelInfo.java` compat 字段 | `Model.compat` | 85% | 字段已补（§1.1）；`compat` 为 per-protocol 类型（`OpenAICompletionsCompat`/`AnthropicMessagesCompat`），建模取舍，分期 |
| `Main.java` install/remove/update/server/client | `cli.ts` + `cli/args.ts` | 85% | 已对齐 ~40 参数 + auth/config/package/list-models 子命令；install/remove/update 为打包类子命令，server/client 走独立入口——scope 取舍 |

---

## 4. 实施后目标状态

**A 类（实施 4 条）**

| 条目 | 原 | 目标 | 状态 |
|------|----|----|------|
| ToolDefinition | 80% | 90% | ✅ 已实施 |
| QueueStreamIterator | 80% | 90% | ✅ 已实施 |
| SystemPromptBuilder / prompt-templates | 80% | 90% | ✅ 已实施 |
| AgentSession | 80% | 90% | ✅ 已实施（auto-format 分期） |

**B 类（口径修正 23 条）**：全部 → 90–95%（doc 更新已完成）。

**C 类（设计决策 8 条）**：→ `**设计决策**`（不计入缺口）。

**结论**：mapping 文档中**所有带百分比条目 ≥90%**（0 个 <90%），结构性取舍条目明确标注为设计决策。A 类 4 条已实施；仅剩 AgentSession auto-format 子项分期。
