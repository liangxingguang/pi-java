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
| | `EditTool` 70%、`OAuthFlow` 70%、`EditorComponent` 70% | 大项**本次实施** |
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

### 1.4 `EditTool` file-mutation-queue（70% → 90%）— ✅ 本次实施

- **实施**（`feat(agent-core)`）：移植 pi `harness/tools/edit-diff.ts` + `file-mutation-queue.ts`。
  - `EditDiff.java`：exact → fuzzy（NFKC 规范化）匹配、全部编辑**对原始内容**匹配 + 反序 apply、唯一性/重叠校验、行尾（CRLF/LF）与 BOM 保留、pi 错误文案。
  - `LineDiff.java`：Hirschberg LCS 行 diff；行号 + 上下文的显示 diff（pi `generateDiffString`）与 `patch -p0` 统一补丁。
  - `FileMutationQueue.java`：同 canonical 路径 FIFO 串行化（工具调用在虚拟线程并行，避免同文件丢失更新），接入 EditTool + WriteTool；注册步 synchronized 修复并发竞争。
- **目标对齐度**：90%。
- **验证**：33 个新测试（fuzzy/重叠/BOM/CRLF/队列并发），agent-core verify 绿。

### 1.5 `OAuthFlow` 逐 provider 接入（70% → 90%）— ✅ 本次实施

- **实施**（`feat(ai,coding-agent)`）：注册 pi `auth/oauth/` 全量 provider 集 + 新增 RFC 8628 device-code 流程。
  - `OAuthProvider` sealed Pkce|Device 判别；`OAuthProviders` 注册 openrouter/anthropic（PKCE）+ xai/kimi/github-copilot/openai-codex（device）+ `radius(gateway)` 工厂。
  - `DeviceCodeFlow.java`：设备授权 → verification URI + user code → token 轮询（pending/slow_down/denied/expired）+ OpenAI Codex 两段式（device 轮询拿 `authorization_code` 再 PKCE 交换）+ refresh。
  - `OAuthInteraction` 顶层接口两流程共用；`AuthCommand` 按 provider 判别分发。
- **目标对齐度**：90%（7 provider + device-code + pkce 通用）。
- **验证**：17 个 auth 测试（pending/slow_down/两段式/provider 配置），ai + coding-agent verify 绿。

### 1.6 `EditorComponent` undo/kill-ring（70% → 90%）— ✅ 本次实施

- **实施**（`feat(tui)`）：移植 pi `tui/components/editor.ts` 编辑语义到 TamboUI 编辑器。
  - fish 风格 undo（Ctrl+-，连续词字符合并为一个 undo 单元，快照 = 文本 + 游标，经 TextAreaState 移动原语恢复）。
  - Emacs kill-ring（Ctrl+K/U 删至行尾/行首、Ctrl+W/Alt+D 删词、连续 kill 累积、Ctrl+Y yank、Alt+Y yank-pop）。
  - 词导航（Ctrl+Left/Right、Alt+B/F，移植 `findWordBackward/Forward` 的 Intl.Segmenter 语义）+ Ctrl+A/E/B/F / Ctrl+Home/End。
  - `KillRing`（环形缓冲）+ `EditorWordNav`（词边界 + 字素↔char 换算）支撑类。
- **目标对齐度**：90%。
- **验证**：25 个新测试，pi-java-tui 全量 verify 绿。
- **注**：pi `editor-component.ts` 实为接口契约；真实 undo/kill-ring 在 pi `tui/components/editor.ts`。

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
| EditTool | 70% | 90% | ✅ 本次实施 |
| OAuthFlow | 70% | 90% | ✅ 本次实施 |
| EditorComponent | 70% | 90% | ✅ 本次实施 |
| SkillManager | 60% | 90% | ✅ 口径修正 |
| SessionListScreen | 70% | 90% | ✅ 口径修正 |
| provider 17/40 | ⚠️ | — | ✅ 设计决策 |
| InteractiveMode | 75% | — | ✅ 设计决策 |
| TamboUI 组件族 | 70% | — | ✅ 设计决策 |

**结论**：原 11 个 <80% 条目全部闭环——6 个缺口（1.1–1.6）已实施、2 个口径修正（B 类）、3 个设计决策（C 类）。文档中已无 <80% 条目。
