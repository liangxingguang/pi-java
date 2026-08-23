# Phase 7 设计文档 — pi-java Web UI（代码调试 + 功能使用）

> 依据：用户需求「给 pi-java 配一个 web UI，可以通过 web UI 进行代码调试以及功能使用」。
> 目标：一个 `pi-java --mode web` 启动的本地 Web 工作区，在浏览器里驱动 pi-java agent——
> 流式对话、模型/思考等级管理、会话管理、**文件浏览 + git diff（代码调试）**、终端、skills/MCP、fork 分支树、导出。
> 更新日期：2026-08-23

---

## 1. 现状与调研结论

### 1.1 pi-java 已有的可复用面（决定性事实）

pi-java 已有一个 **headless RPC 层**，本质是「stdio 上的 JSON 协议」，是 web UI 的直接基座：

- `coding-agent/rpc/RpcDispatcher.java`（601 行）—— 32 个命令分发：`prompt`/`steer`/`follow_up`/`abort`/`get_state`/`new_session`/`set_model`/`set_thinking_level`/`compact`/`bash`/`fork`/`clone`/`get_tree`/`get_entries`/`export_html`… 全齐。
- `coding-agent/mode/JsonEventMapper.java` —— `AgentSessionEvent → JSON` 线格式。
- `agent-core/agent/session/SessionJson.java` —— `SessionJson.mapper()`：`Entry`/`LaneRecord`/`Message` 已 `@JsonTypeInfo` 多态，**byte-for-byte 兼容 pi JSONL v4**。
- `AgentSession`（`create(Args)` 引导 + 全部控制面）、`PersistentSessionRepositories.RepositoryHandle`（会话 list/create/open/fork）、`agent-tool/FileSystem`（文件访问）、`org.eclipse.jgit`（已在 BOM）。
- **缺的只是**：网络传输层（WS）+ 前端。

### 1.2 前端复用起点（决定性事实）

`Zetaphor/pi-webui`（已 clone 到 `D:\workplaceForai\pi-webui`）是唯一有**真协议边界**的 pi web UI：

- 前端 `client/` 只有 3 个文件（`main.ts`/`app.css`/`index.html`，Vite+Lit），对 pi **运行时零耦合**——只 import 类型（`AgentMessage`/`ToolResultMessage`）+ `@mariozechner/pi-web-ui` 渲染组件。
- 协议 `shared/protocol.ts` 56 行：**21 条 WebSocket JSON 消息**。
- 服务端 `server/index.ts` 394 行：内嵌 pi SDK 的薄壳——**本方案以整段替换该文件**为 pi-java WS 网关，前端与协议作骨架原样复用 + 扩展。

### 1.3 决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 传输 | **WebSocket** `/api/ws`（JSON） | 复用 pi-webui 前端 `connectWs()` 不动；终端/流式需全双工 |
| WS 服务端 | `org.java-websocket:Java-WebSocket` | 纯 Java 单 jar；项目 BOM 已有依赖管理 |
| 新模块 | **`pi-java-web`**（第 14 个） | 与 `protocol`/`server`（CBOR Unix-socket）解耦 |
| 协议 | pi-webui `shared/protocol.ts` 为基线 + 扩展 | 前端复用；「完整功能」为协议超集 |
| CLI 入口 | `--mode web` + **`WebEntryPoint` SPI**（对齐 `TuiEntryPoint` 模式） | coding-agent 不反向依赖 pi-java-web |

---

## 2. 架构

```
┌─ 浏览器 ─────────────────────────────────────────────┐
│  pi-webui client/ (main.ts/app.css/index.html)      │
│  WebSocket /api/ws  ← 21+ 条 JSON 消息               │
└───────────────┬─────────────────────────────────────┘
                │ ws://localhost:8787/api/ws
┌───────────────▼─────────────────────────────────────┐
│  pi-java-web 模块  (com.pijava.web)                  │
│  PiWebServer (Java-WebSocket, 静态托管 / → dist)     │
│  WebProtocol  (基线 21 消息 + 扩展, Jackson 多态)     │
│  WebDispatcher (消息 → AgentSession, 每连接单活跃会话) │
│  AgentEventTranslator (StreamEvent/AgentSessionEvent │
│                        → pi-webui agentEvent)        │
│  SessionFacade / FileBrowserService / GitService     │
└───────────────┬─────────────────────────────────────┘
                │ 复用
┌───────────────▼─────────────────────────────────────┐
│  pi-java-coding-agent                                │
│  AgentSession.create(Args) · RepositoryHandle        │
│  RpcDispatcher(helper) · JsonEventMapper · SessionJson│
│  FileSystem · JGit · ProviderCatalog · ThinkingLevels │
└──────────────────────────────────────────────────────┘
```

**模块依赖**：`pi-java-web` → `pi-java-coding-agent`（→ `agent-core`/`ai`/`session-backend-sqlite` 按需）。与 `protocol`/`server`/`client` 无依赖关系。

**SPI 接线**：`coding-agent` 新增 `spi/WebEntryPoint`（`int runWeb(Args args)`）；`Main` 在 `--mode web` 时 `ServiceLoader.load(WebEntryPoint.class)`（TUI 同款模式）。`pi-java-web` 实现之。

---

## 3. WS 协议（基线 21 条 + 扩展）

> 基线消息字面遵循 `D:\workplaceForai\pi-webui\shared\protocol.ts`。所有消息 JSON 对象，`type` 判别。

### 3.1 客户端 → 服务端（基线 11）

| type | 字段 | 对应 pi-java 调用 |
|------|------|-------------------|
| `prompt` | `text` | `session.processPrompt(text, PromptConfig.defaults())` |
| `steer` | `text` | `session.steer(text)` |
| `followUp` | `text` | `session.followUp(text)` |
| `abort` | — | `session.abort()` |
| `getModels` | — | `ProviderCatalog.allModels()` → `ModelInfo[]` |
| `setModel` | `provider, modelId` | `session.harness().setModel(resolveModel(...))` |
| `setThinkingLevel` | `level` | `session.harness().setThinkingLevel(ThinkingLevels.parse(level))` |
| `getState` | — | 组装 `SerializedAgentState` |
| `newSession` | — | `RepositoryHandle.create(cwd)` 重建 `AgentSession` |
| `getSessions` | — | `RepositoryHandle.list(cwd)` → `SessionListItem[]` |
| `loadSession` | `sessionPath` | `RepositoryHandle.open(metadata)` 重建 `AgentSession` |

### 3.2 服务端 → 客户端（基线 8）

| type | 字段 | 来源 |
|------|------|------|
| `ready` | — | 连接建立 |
| `stateSync` | `state` | 状态组装（见 §5） |
| `agentEvent` | `event` | 事件翻译层（见 §4） |
| `models` | `models, current?, thinkingLevel?` | `getModels` 响应 |
| `modelChanged` | `model, thinkingLevel` | `setModel`/`setThinkingLevel` 广播 |
| `error` | `message` | 异常/StreamError |
| `sessions` | `sessions, currentSessionId` | `getSessions` 响应 |
| `sessionChanged` | `sessionId` | `newSession`/`loadSession` 广播 |

### 3.3 扩展消息（Stage B/C，协议超集）

| type | 方向 | 用途 | 阶段 |
|------|------|------|------|
| `listDir` / `readFile` | C→S | 文件浏览器 | B |
| `gitStatus` / `gitDiff` / `gitHistory` | C→S | git 调试 | B |
| `bash` / `abortBash` | C→S | 终端 | C |
| `listSkills` / `listMcpServers` | C→S | skills/MCP 管理 | C |
| `getTree` / `fork` / `clone` / `getForkMessages` | C→S | fork 分支树 | C |
| `exportHtml` | C→S | 导出 | C |
| `setSessionName` / `getSessionStats` | C→S | 会话控制 | C |
| 对应 `dirListing`/`fileContent`/`gitStatusResult`/`gitDiffResult`/`gitHistoryResult`/`bashOutput`/`skills`/`mcpServers`/`tree`/`exportPath`/`sessionNameChanged`/`sessionStats` | S→C | 响应 | B/C |

**每连接单活跃会话**：`newSession`/`loadSession` 换当前会话（对齐 pi-webui 前端 `currentSessionId` 模型）；多标签 = 多 WS 连接各自持有活跃会话。事件按连接广播（`AgentSession.subscribe` 每连接一个 listener）。

---

## 4. 事件翻译层（最大风险点，单独成类）

> pi-java 事件词汇 ≠ pi-webui 前端期望词汇。翻译发生在服务端，前端不改。

### 4.1 两端词汇

| pi-java 侧 | pi-webui 前端期望 |
|-----------|-------------------|
| `StreamEvent.Start` | `agent_start` |
| `StreamEvent.TextDelta/ThinkingDelta/ToolCallDelta`（`partial()` 携带累积 `AssistantMessage`） | `message_update`（`{message: <partial>}`）|
| `StreamEvent.StreamDone(reason, usage, partial)` | `message_end`（`{message: <partial>}`）+ `agent_end` |
| `StreamEvent.StreamError` | `error` |
| `AgentSessionEvent.AgentEnd(messages, willRetry)` | `agent_end`（`{messages, willRetry}`）|
| `AgentSessionEvent.AgentSettled` | `turn_end` |
| `AgentSessionEvent.EntryAppended(entry)` | （必要时补 `message_end`/tool result）|
| `AgentSessionEvent.BashExecutionUpdate(id, delta)` | `bashOutput`（Stage C）|

### 4.2 映射规则（`AgentEventTranslator.translate(...) → List<ServerMessage>`）

1. **run 开始**（首个 `StreamEvent`）：`{type:"agent_start"}`。
2. **增量**：每个 Text/Thinking/ToolCall delta → `{type:"message_update", message: <partial 序列化>}`。`partial()` 是累积消息，用 `SessionJson.mapper()` 序列化（`role`+`content[]`）。
3. **消息结束**：`StreamDone(reason="completed")` → 先 `{type:"message_end", message: <partial>}`，再 `{type:"agent_end", messages: <transcript messages>}`（从 `harness().snapshot(lane).transcript()` 取）。
4. **中止/错误**：`StreamDone(reason="aborted")` → `{type:"agent_end"}`；`StreamError` → `{type:"error", message}`。
5. **回合结束**：`AgentSettled` → `{type:"turn_end"}`。
6. **工具调用**：`ToolCallStart/Delta/End` → `{type:"tool_execution_start/update/end"}`（载荷对齐前端 `tool_execution_*` 的空处理 + 可后续扩展渲染）。

### 4.3 未决字段（Stage A 实机钉死）

- 前端 `message_end` 按 `message.timestamp === event.message.timestamp` 去重。pi-java `Message` **无 timestamp 字段** → 翻译层须为 `message` JSON **补 `timestamp`**（epoch ms，取 entry timestamp 或 run 时间戳），否则 `existing===-1` 恒成立导致重复 append。具体取哪个时间源在 Stage A 用真实前端验证。
- 前端 `stateSync.messages`/`agent_end.messages` 期望 pi `AgentMessage[]`；pi-java `SessionJson.messageNode` 已声明 pi 兼容，但 `MessageList` 组件实机渲染需验证，失败则前端消息渲染自绘（预留，前端本要扩展）。

---

## 5. 状态组装（`SerializedAgentState`）

对齐 `shared/protocol.ts` 形状，从 `AgentSession` 组装：

```json
{
  "messages": [<transcript 中 role+content 消息>],
  "model": {"provider","id","name"},
  "thinkingLevel": "off|minimal|...|xhigh",
  "systemPrompt": <harness.getSystemPrompt()>,
  "isStreaming": <run 中>,
  "streamingMessage": <当前 partial, 流式中>,
  "errorMessage": <末次错误>,
  "tools": [<harness.getActiveTools()>],
  "sessionId": <session.sessionId>,
  "sessionName": <session.sessionName>
}
```

- **会话列表**：`RepositoryHandle.list(cwd)` → `SessionMetadata`（`JsonlSessionMetadata`/`SqliteSessionMetadata`）→ `SessionListItem`。**修正 `SessionInfo` stub**（现 `name="session"`/`entryCount=0`）：name 从 `Session.getName()`，messageCount 从 `getStats()`，firstMessage 从最老 message entry 派生。
- `SessionMetadata` 含 `java.nio.file.Path` 不直接序列化 → 网关映射 DTO 时转字符串/省略。

---

## 6. 复用清单（不重写）

| 能力 | 复用点 |
|------|--------|
| 会话运行时 | `AgentSession.create(Args)` / `processPrompt` / `steer` / `followUp` / `abort` / `harness().setModel/setThinkingLevel` / `compact` / `forkFromEntry` / `executeBash` / `exportJsonl` |
| 事件→JSON | `SessionJson.mapper()`（Entry/LaneRecord/Message 已多态）+ 翻译层（§4）|
| 命令逻辑 | `RpcDispatcher` 的 `resolveModel`/`buildTree`/`exportHtml`/`availableModels`/`thinkingWire`——`pi-java-web` 侧建 `RpcHelpers` 复刻（复制小段，不动 RpcDispatcher 公共契约；如需复用再抽公共）|
| 会话 CRUD | `PersistentSessionRepositories.RepositoryHandle` |
| 文件访问 | `com.pijava.agent.tool.FileSystem`（受 `TrustManager` 约束）|
| git | `org.eclipse.jgit`（BOM 已有；补 status/diff/history 命令，参考 `IgnoreFilter` 用法）|
| 模型/思考 | `ProviderCatalog.allModels()` / `ThinkingLevel.ordered()` / `ThinkingLevels.parse` |
| 终端 | `executeBash` + `BashExecutionUpdate`（Stage C 确认阻塞/异步语义）|
| 导出 | RPC 已有 `export_html` 逻辑 |

---

## 7. Stage 拆分（每阶段独立编译 + commit）

### Stage A — 骨架 + 最小对话闭环 ✅（`16a5c46`）
- `pi-java-web` 模块（pom + 根 pom/BOM 注册）；`WebEntryPoint` SPI + `Main --mode web` + `--port`。
- `PiWebServer`（Java-WebSocket `/api/ws` + 静态托管 `resources/web/`）。
- `WebProtocol`（基线 21 消息 Jackson 多态）、`WebDispatcher`（§3.1 消息 → `AgentSession`）、`AgentEventTranslator`（§4）。
- `SessionFacade`（状态组装 + 真实会话列表）。实际并入 `WebDispatcher`（`stateSync`/`sessions`），无独立类。
- 前端：pi-webui `client/` Vite build → `resources/web/`，**零改动**跑通对话闭环。
- **验证**：浏览器 `localhost:8787` 完成 新建/列表/切换会话 + 流式对话 + 切换模型/思考等级。

### Stage B — 代码调试（文件浏览器 + git diff） ✅（`10954c0`）
- 协议扩展（§3.3 B 行）；`FileBrowserService`（`FileSystem` + 信任边界）；`GitService`（JGit status/diff/history）。
- 前端：文件树面板 + 代码查看器 + git diff 视图（自绘，或复用 `pi-web-ui` 若含 diff 组件）。
- **验证**：受信目录列/读；含未提交改动仓库返回正确 diff。

### Stage C — 完整功能（终端 + skills + fork 树 + 导出） ✅（`deef37c`）
- 终端：`bash`/`abortBash` + `bashResult`（一次性结果消息，非流式 `bashOutput`——实现取舍）。
- skills：`harness().skillManager()` + `listSkills`/`skills`（MCP 面未纳入）。
- fork 树：`forkFromEntry`/`forkCopy` + `getTree`/`fork`/`clone` → 前端分支树。
- 导出：`exportHtml`（`HtmlExporter`）。

### Stage D — 打磨 + 鉴权 + 文档 ✅（2026-08-23 完成）
- 网关 token 鉴权（`~/.pi-java/web/gateway-token` + `PI_JAVA_WEB_TOKEN`，对齐 pivot-ui 思路）。`GatewayToken` 首启自动生成落盘；`PiWebServer.onOpen` 校验 `?token=`（常数时间比较），失败 `close 4401`；`/api/config` 返回 `requiresAuth`。
- 会话重命名（`setSessionName` → `sessionNameChanged`）/ 会话统计（`getSessionStats` → `sessionStats`）；错误提示走既有 `error` 消息 + 前端横幅；多 tab 每连接独立会话（既有设计）。
- 前端：`getWsUrl()` 读 localStorage token 并追加 `?token=`，`requiresAuth` 且无 token 时 prompt；sidebar 增加重命名按钮。
- `phase1-pi-code-mapping.md` 增补 web 条目（§13）。
- **验证**：`mvn clean verify` 零错误零警告、checkstyle/spotbugs 通过、无 `System.out.println`。
- **Commit**：`feat(web): gateway token auth + polish`。

---

## 8. 验证（端到端）

1. Stage A：`mvn test -pl pi-java-web -am`；`java -jar pi-java-dist`（或 `--mode web --port 8787`），浏览器完成一次流式对话。
2. Stage B：文件树列/读受信目录；`gitStatus`/`gitDiff` 对含未提交改动仓库正确。
3. Stage C：终端 `ls`/`git status` 实时回显；fork 树可视化；导出 HTML 可打开。
4. 翻译层单测：`AgentEventTranslatorTest` 断言 `TextDelta→message_update`、`StreamDone→message_end+agent_end`、`StreamError→error`、`AgentSettled→turn_end`。
5. 全量 `mvn clean verify` 零错误零警告。

---

## 9. 风险

| 风险 | 缓解 |
|------|------|
| **事件词汇不一致**（前端期望 pi 原生 `AgentSessionEvent`，pi-java 词汇不同）| §4 翻译层统一；语义对不齐时微调前端 `handleAgentEvent`（前端本要扩展）|
| `@mariozechner/pi-web-ui` 渲染组件对 `AgentMessage` 形状敏感 | Stage A 实机验证；失败则前端消息渲染自绘（预留）|
| Node/Vite 构建步骤 | `npm run build` 产物 `dist/` 提交进 `resources/web/`，`mvn` 不依赖 Node；可选 frontend-maven-plugin 自动化 |
| 文件/git 访问成为任意路径读取面 | 必须过 `TrustManager` 信任边界（与 TUI 一致）|
| 多会话并发写 | 每连接单活跃会话 + 既有 `SessionRepository` writer-lease/序列化语义 |

---

## 10. 关键文件

**新增**
- `pi-java-web/pom.xml`；根 `pom.xml`/`pi-java-bom/pom.xml` 注册
- `pi-java-web/src/main/java/com/pijava/web/`：`PiWebServer`、`WebProtocol`、`WebDispatcher`、`AgentEventTranslator`、`SessionFacade`、`RpcHelpers`、`FileBrowserService`、`GitService`、`WebEntryPointImpl`
- `pi-java-web/src/main/resources/web/`（前端产物）+ `src/web/`（前端源，可选）
- `docs/15-phase7-webui-design.md`

**修改**
- `pi-java-coding-agent/.../spi/WebEntryPoint.java`（新增 SPI）
- `pi-java-coding-agent/.../Main.java`（`--mode web` 分支）
- `pi-java-coding-agent/.../cli/Args.java` + `ArgsParser.java`（`--port`）
- `docs/phase1-pi-code-mapping.md`（web UI 条目）
