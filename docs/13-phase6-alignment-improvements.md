# Phase 6 对齐度提升 — 优化改进文档

> 依据 `docs/phase1-pi-code-mapping.md`（功能完成度口径）中 **11 个 <80% 条目**逐项制定改进方案。
> 目标：将每个条目的功能对齐度提升到 90% 以上（结构性取舍条目除外——重新定性为「设计决策」）。
> 更新日期：2026-08-22

---

## 0. 分类总览

11 个 <80% 条目按性质分三类：

| 类别 | 条目 | 处理 |
|------|------|------|
| **A. 真实缺口（代码可补）** | `ModelInfo` 70%、`MarkdownRenderer` 70%、`CompactionService` 75% | 低成本项本次实施 |
| | `EditTool` 70%、`OAuthFlow` 70%、`EditorComponent` 70% | 大项给方案 + 工作量，分期 |
| **B. 文档口径过时（非代码缺口）** | `SkillManager` 60%、`SessionListScreen` 70% | 修正对齐度 + 补注 |
| **C. 结构性取舍（设计决策，非缺口）** | provider 17/40、`InteractiveMode` 75%、TamboUI 组件族 70% | 重新定性为「设计决策」 |

---

## 1. A 类 — 真实缺口

### 1.1 `ModelInfo` 补字段（70% → 85%）— ✅ 本次实施

- **现状**：`pi-java-ai/.../catalog/ModelInfo.java` 有 `thinkingLevelMap`/capabilities/定价，缺 pi `Model` 的 `headers` 与 `samplingParams`。
- **差距**：pi `types.ts` `Model` 含 `headers?: ProviderHeaders`、`samplingParams?: Record<string, unknown>`、`compat`（per-protocol：`OpenAICompletionsCompat`/`AnthropicMessagesCompat`）。pi-java 的协议适配器无法按模型下发自定义 header / 采样参数。
- **方案**：`ModelInfo` 加 `Map<String,String> headers`、`Map<String,Object> samplingParams`（默认空 map，现有调用点兼容）；`compat` 为 per-protocol 复杂类型，单列大项分期（需建模 `OpenAICompletionsCompat`/`AnthropicMessagesCompat`）。
- **目标对齐度**：85%（字段建模对齐；adapter 消费 headers/samplingParams 下发到请求列为后续）。
- **工作量**：0.5d。

### 1.2 `MarkdownRenderer` 代码语法高亮（70% → 90%）— ✅ 本次实施

- **现状**：`pi-java-tui/.../component/MarkdownRenderer.java` 代码块仅灰色面板，无语法高亮。
- **差距**：pi `components/markdown.ts` 的代码块有高亮；pi-java 的 `SyntaxHighlighter.java`（P6-23，编辑器用）已存在但未接入 Markdown 渲染。
- **方案**：代码块非 mermaid 分支逐行调 `SyntaxHighlighter.highlight(line, Style.EMPTY)`，分段转 markup `[#RRGGBB]...[/]`（`MarkupParser` 支持 `[#hex]` 真彩 token 与 `[[`/`]]` 转义），`markupText` 渲染。
- **目标对齐度**：90%（关键字/字符串/数字/注释高亮对齐 pi）。
- **工作量**：0.5d。

### 1.3 `CompactionService` LLM 摘要接线（75% → 90%）— ✅ 本次实施

- **现状**：`SummaryGenerator` 接口已定义，但 `ActionExecutor.compactTranscript` 硬编码 `SummaryGenerator.truncating()` 占位，LLM 摘要未接线。
- **差距**：pi `compaction.ts` 用 `completeSimpleWithRetries` + `SUMMARIZATION_SYSTEM_PROMPT` + `serializeConversation` 生成结构化摘要（Goal/Constraints/Progress）。
- **方案**：新增 `LlmSummaryGenerator`（`pi-java-agent-core/.../compaction/`）实现 `SummaryGenerator`，用 harness `StreamFn` 调 LLM（system prompt 对齐 pi + 序列化对话 + 结构化格式指令），失败回退 `truncating()` 不崩；`HarnessConfig` 加 `summaryGenerator` 通道（默认 `truncating()`），`AgentSession.assemble` 注入 `LlmSummaryGenerator`。
- **目标对齐度**：90%（结构化摘要流程对齐 pi；`serializeConversation` 完整细节渐进）。
- **工作量**：1d。

### 1.4 `EditTool` file-mutation-queue（70% → 90%）— ⏸ 分期

- **现状**：`pi-java-agent-core/.../tool/builtin/EditTool.java` 单文件精确替换 + 简单 diff。
- **差距**：pi `harness/tools/edit-diff.ts`（500 行）+ `file-mutation-queue.ts`（56 行）——批量变更队列（原子 apply + 冲突检测），EditTool 走队列而非直接写。
- **方案**：移植 `file-mutation-queue`（变更条目入队 → 原子 apply → 冲突时拒绝）；`edit-diff.ts` 的完整 diff 语义（行号/上下文/多编辑）。
- **目标对齐度**：90%。
- **工作量**：~2d。**分期原因**：涉及 agent-core 工具执行管道的队列化改造，风险高。

### 1.5 `OAuthFlow` 逐 provider 接入（70% → 90%）— ⏸ 分期

- **现状**：`pi-java-ai/.../auth/OAuthFlow.java` 通用 PKCE authorization-code 流程。
- **差距**：pi `auth/oauth/` 11 个文件（anthropic/github-copilot/kimi-coding/openai-codex/openrouter/radius/xai + device-code/pkce 通用），逐 provider 授权端点/token 交换/scope 不同。
- **方案**：抽取 provider 配置表（授权端点/令牌端点/client-id 来源/scope），逐 provider 接入；device-code 流程为独立变体。
- **目标对齐度**：90%（9 个 provider flow + device-code）。
- **工作量**：~3d。**分期原因**：每个 provider 需实测端点与凭证格式。

### 1.6 `EditorComponent` undo/kill-ring（70% → 90%）— ⏸ 分期

- **现状**：`pi-java-tui/.../component/EditorComponent.java`（126 行）基础 insert/replace，无撤销栈。
- **差距**：pi `components/editor-component.ts` 含 undo/redo、kill-ring（多行 kill/yank）、游标历史。
- **方案**：编辑器状态加 undo/redo 栈（每个 edit 入栈）+ kill-ring（循环缓冲区）。
- **目标对齐度**：90%。
- **工作量**：~2d。**分期原因**：TamboUI `TextAreaState` 变更语义需细测。

---

## 2. B 类 — 文档口径过时（修正，非代码缺口）

### 2.1 `SkillManager`（60% → 90%）— 口径修正

- **现状**：agent-core `SkillManager`（59 行）仅注册/查询 + prompt 模板。
- **实际**：完整 Markdown frontmatter 解析 + SKILL.md 目录规则 + 校验已在 **coding-agent** `skill/MarkdownSkillLoader.java`（121 行）+ `FrontmatterParser.java`（97 行）+ `SkillDiscovery.java`（P6-6）补全——是**模块边界口径**问题，非能力缺失。
- **修正**：对齐度改 90%，补注「能力在 coding-agent skill/（P6-6）」。

### 2.2 `SessionListScreen`（70% → 90%）— 口径修正

- **现状**：tui `screen/SessionListScreen.java`（50 行）为 `/resume`/`/session` 选择器。
- **实际**：会话 diff 渲染（P6-26）已在 `ChatScreen`/相关组件实现，列表选择 + diff 视图完整。
- **修正**：对齐度改 90%，补注「diff 渲染在 ChatScreen（P6-26）」。
- **另注**：pi 的交互全栈（17K 行）由 tui 模块经 TamboUI 承载，见 C 类。

---

## 3. C 类 — 结构性取舍（重新定性为设计决策）

> 以下三条**不是缺口**，是此前审定的设计决策。在 `phase1-pi-code-mapping.md` 中从「⚠️部分/70%/75%」改为「**设计决策**」，不计入待提升项。

### 3.1 provider 清单（17/40）

- **决策**：聚焦中国大陆 17 家（16 chat + openrouter-images 1 image），非 pi 全量 39 chat + 1 image。Bedrock/Vertex/OpenRouter chat 等按需接入（`models.json` 自定义 provider 兜底）。
- **依据**：`11-phase6-ecosystem-design.md` §2.4（2026-08-19 用户指示收窄名单）。

### 3.2 `InteractiveMode` 交互全栈（75%）

- **决策**：pi `modes/interactive/`（47 文件 / 17K 行）为自研终端全栈；pi-java 交互 UI 在 **tui 模块**经 TamboUI 库封装（行为对齐，渲染层不逐文件复刻）。
- **依据**：`08-phase3-cli-tui-design.md` / `08b-phase3-tui-codex-alignment-design.md`（TamboUI 选型）。

### 3.3 TamboUI 组件族（70%）

- **决策**：`component/` 组件族由 TamboUI 等价组件承载（box/stack/spacer/text/loader 等），非逐文件复刻 pi 组件。
- **依据**：同上 TamboUI 选型；功能等价（已通过行为对齐验证）。

---

## 4. 实施后目标状态

| 条目 | 原对齐度 | 目标 | 状态 |
|------|---------|------|------|
| ModelInfo | 70% | 85% | ✅ 本次实施 |
| MarkdownRenderer | 70% | 90% | ✅ 本次实施 |
| CompactionService | 75% | 90% | ✅ 本次实施 |
| EditTool | 70% | 90% | ⏸ 分期（~2d） |
| OAuthFlow | 70% | 90% | ⏸ 分期（~3d） |
| EditorComponent | 70% | 90% | ⏸ 分期（~2d） |
| SkillManager | 60% | 90% | ✅ 口径修正 |
| SessionListScreen | 70% | 90% | ✅ 口径修正 |
| provider 17/40 | ⚠️ | — | ✅ 设计决策 |
| InteractiveMode | 75% | — | ✅ 设计决策 |
| TamboUI 组件族 | 70% | — | ✅ 设计决策 |

**结论**：低成本缺口（1.1–1.3）本次实施后，文档中 <80% 条目仅剩三个**已给方案+工作量的分期大项**（1.4–1.6）与三个**设计决策**（C 类），无未决缺口。
