# 17 - CustomMessageEntry（custom_message）设计

## 1. 背景

pi-java 此前只对齐了 pi 的 `CustomEntry`（type:"custom"，纯持久化、不进 LLM 上下文，
即 `Entry.Custom`），缺少 pi 的 `CustomMessageEntry`（type:"custom_message"）能力——
**扩展可向会话注入参与 LLM 上下文的自定义消息**。压缩上下文对齐任务
（commit `6d9d859`）实测确认了这一缺口：`Entry.Custom.data` 只是等价于 pi `details`
的一般性元数据，没有 `content`/`display` 这一半；`Session.appendCustomEntry` 存在但
coding-agent 层零调用。

本任务补齐整条链路：条目类型 → 三路持久化 → LLM 上下文转换 → 扩展注入 API → TUI 渲染。

## 2. pi 侧对照（参考实现实测事实）

| 层 | pi（TypeScript） | pi-java（本次新增） |
|---|---|---|
| 条目类型 | `CustomMessageEntry`（`session-manager.ts:135-141`）：`customType: string`、`content: string \| (TextContent\|ImageContent)[]`、`display: boolean`、`details?: T` | `Entry.CustomMessage`（type `"custom_message"`），字段同名；content 用 `CustomMessageContent` sealed ADT |
| JSONL | `{"type":"custom_message","id","parentId","timestamp","customType","content","display",["details"]}`；content 为 string 时裸字符串，数组时块数组 | `SessionJson` 注册 `CustomMessageContentSerializer`：`Text`→裸字符串、`Blocks`→块数组（复用 `blockNode`，与 pi 逐字段一致）；解码走 `EntryJsonCodec` 手写 case |
| 写入 API | `appendCustomMessageEntry(customType, content, display, details?): string` | `SessionTree.appendCustomMessageEntry(...)`，`Session`/`LaneView` 实现，复用 `ProvisionedEntry + appendEntry` 模式 |
| 扩展 API | `pi.sendMessage(message, {triggerTurn?, deliverAs?})`——非 streaming 默认仅落盘 | `ExtensionContext.sendMessage(customType, content, display, details[, options])`——本轮仅落盘路径 |
| LLM 转换 | 两级：entry→运行时 `role:"custom"` 消息（`sessionEntryToContextMessages`）；`convertToLlm` 转 **`role:"user"`**，string 包成单个 text 块、数组原样透传，display/details 丢弃 | 一级：`ContextEntries.project` 直转 `Message.UserMessage(content.toBlocks())`，与 Compaction/BranchSummary 同模式（pi-java 无运行时消息中间态消费者） |
| UI | `display=false` 不渲染；true 渲染 `[customType]` 标签 + text 块拼接（图片忽略），支持扩展注册 renderer | `ChatMessage.from`：display=false → `null`（`ChatScreen.onEntry` 判空跳过）；true → `[customType] plainText` 的 CUSTOM 气泡；扩展 renderer 不做 |
| 查询 | 按 customType 过滤 | 三后端过滤路径均扩展：`JsonlCodec.ENTRY_TYPES`/mutation 校验、`SessionState.matchesEntryQuery`（Memory）、SQLite `customTypeOf`/`matchesEntryQuery`/branch cache |

## 3. 关键设计决策

### 3.1 content 建模

`string | (TextContent|ImageContent)[]` 的并集形状用 sealed interface 表达：

```java
public sealed interface CustomMessageContent {
    record Text(String text) implements CustomMessageContent {}
    record Blocks(List<ContentBlock> blocks) implements CustomMessageContent {}
    // toBlocks(): Text -> 单个 TextContent；Blocks -> 原样透传（对齐 pi convertToLlm）
    // plainText(): text 块拼接、非 text 忽略（对齐 pi 默认渲染）
}
```

`Entry` 所在 agent-core 已依赖 ai 模块（`Entry.Message.message()` 即
`com.pijava.ai.message.Message`），直接引用 `ContentBlock` 不破坏依赖方向。

### 3.2 序列化：为什么不用 `@JsonValue`

最初方案想在 record 组件上标 `@JsonValue` 让 Jackson 自动编码。实测失败：
`SessionJson` 为 `ContentBlock` 注册了显式 `ContentBlockSerializer`（因「Jackson 的
polymorphic type info 在泛型集合里不可靠」，见 `SessionJson` 类注释），`@JsonValue`
展开的 `List<ContentBlock>` 会走 polymorphic 路径触发
`serializeWithType`，抛 `Type id handling not implemented`。

最终方案与 `Message`/`ContentBlock` 完全同模式：在 `SessionJson` 注册
`CustomMessageContentSerializer` + 公开 `contentNode()` 辅助方法。编码显式、逐字节
可控；解码走手写 `EntryJsonCodec`（JSONL 与 SQLite payload 共用）。全仓无反序列化
Entry 的 Jackson 路径（已验证），注解只承担编码职责。

### 3.3 SQLite 零 migration

`entries.payload` 为去身份字段的 JSON blob（编码经 `SessionJson` mapper 自动覆盖新
类型），`branch_entries.custom_type` 列值改由 `SqliteCodecs.customTypeOf(entry)` 统一
提取（覆盖 `Custom`/`CustomMessage` 两变体）。schema 版本不变。

### 3.4 扩展注入：惰性 session 绑定

`AgentSession.assemble` 中扩展加载（原 `:244`）先于 `AgentSession` 构造，而 session
字段又由 `SessionPersistence` 更晚赋值。方案：把 `loadExtensions` 移到构造之后，
`DefaultExtensionContext.bindSession(Supplier<SessionTree>)` 传
`() -> owner.session()` 惰性求值——扩展实际调用 `sendMessage` 时 session 已 resolve；
`/resume`、会话切换重新 `attach` 后亦自动指向新 session。

**已知限制**：`forkCopy`/`forkFromEntry` 不经 `assemble`，其扩展回调仍写入原 owner 的
session（与 pi「一进程一会话」模型一致，RPC 切换会话场景由惰性求值覆盖）。

### 3.5 sendMessage 语义边界（本轮不做）

| 不做项 | 说明 |
|---|---|
| `triggerTurn` | options 已占位（record `SendMessageOptions(boolean triggerTurn)`），传 `true` 抛 `UnsupportedOperationException`；实现需回合驱动入口（`processPrompt` 空 prompt 副作用待验证） |
| `steer`/`followUp`/`nextTurn` | pi 的投递队列语义映射到 LaneHandle/QueueMode 未验证，独立任务 |
| 扩展消息 renderer 注册 | pi `registerMessageRenderer(customType, renderer)`；本轮用默认 `[customType]` 文本气泡 |
| 事件注入钩子 | pi `before_agent_start` 返回 message 的路径 |

## 4. 改动清单

| 模块 | 文件 | 改动 |
|---|---|---|
| agent-core | `entry/CustomMessageContent.java` | 新增 sealed ADT |
| agent-core | `entry/Entry.java` | `CustomMessage` subtype + `@JsonSubTypes` + `type()`/`committed()` |
| agent-core | `session/SessionJson.java` | `contentNode()` + serializer 注册 |
| agent-core | `session/jsonl/EntryJsonCodec.java` | `custom_message` 解码 + `decodeContent` |
| agent-core | `session/jsonl/JsonlCodec.java` | `ENTRY_TYPES` 白名单 + mutation customType 校验 |
| agent-core | `session/SessionTree.java` / `Session.java` / `LaneView.java` | `appendCustomMessageEntry`（main + lane） |
| agent-core | `session/SessionState.java` | Memory 后端 customType 过滤覆盖两变体 |
| agent-core | `session/ContextEntries.java` | `project` 直转 user 消息 |
| sqlite | `SqliteCodecs.customTypeOf` + `SqliteSessionStorage`（两处）+ `SqliteMutationReplay` | custom_type 提取/过滤 |
| coding-agent | `extension/ExtensionContext.java` / `DefaultExtensionContext.java` | `sendMessage` + `bindSession` |
| coding-agent | `core/AgentSession.java` | loadExtensions 移位 + session 供给器 |
| tui | `component/ChatMessage.java` / `screen/ChatScreen.java` | 渲染 + display 门控（null） |
| docs | `docs/17-custom-message-entry-design.md` | 本文档 |

## 5. 测试

- `EntryTest`：编码形状（Text 裸字符串 / Blocks 块数组 / display 始终写出）、codec
  round-trip（两形态）、`type()`/`committed()`。
- `ContextEntriesTest`：string→单 TextContent user 消息、块数组透传（含 image）、
  display=false 仍进上下文、details 不进；`Entry.Custom` 仍被丢弃。
- `ConformanceGroup2Test`（Memory/JSONL/SQLite 三后端）：`customMessage` fixture、
  按 type / 按 customType / 混合查询、游标与分页含 custom_message。
- `DefaultExtensionContextSendMessageTest`：绑定后落盘可查、未绑定抛
  `IllegalStateException`、`triggerTurn=true` 抛 `UnsupportedOperationException` 且不留条目。
- `MessageBubbleTest`：display 门控（false→null）、`[customType]` 标签、图片块忽略。

验证命令：`mvn clean verify`（全模块 + checkstyle）。
