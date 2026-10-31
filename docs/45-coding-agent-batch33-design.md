# docs/45 — 批 33 设计稿：session 余量（JSONL 惰性创建／pi v1/v2 迁移／compaction `systemMessage`／条目信封扩展键）

## §0 背景与范围裁决

【背景摘要】批 32 闭环后，按 `docs/06` §3 序 26 收口 session 余量。`docs/map/06-session-evals-chord.md` 存储后端 20 行当前 **32.0/44 ＝ 72.7%**（对齐 11／缺失 3／存疑 6），是 map/06 唯一剩余的非评测缺口；本范围整体 74.0/87 ≈ 85.1%。序 26 的原注只写了「条目信封扩展键（#16，w3 存疑，P0 队列唯一剩条）＋会话文件惰性创建等」，本稿经取证把「等」展开为三条实现项＋四行取证翻账。

本稿术语：

- **条目信封（entry envelope）**：pi 每条会话行的基字段 `{type,id,parentId,timestamp}`（`session-manager.ts:57-63`，无 `seq`）。
- **惰性创建（lazy creation）**：会话对象建立时不落文件；出现第一条 user/assistant 消息时才一次性写出 header ＋ 全部内存条目。
- **版本迁移**：读取 pi v1／v2 旧文件时，在内存与磁盘上把它改写成当前 v3 线。
- **systemMessage 边界**：compaction 条目上携带的「压缩点当时的完整系统提示与工具状态」（`session-manager.ts:100-104`）。

### 已定范围裁决（2026-10-31 用户拍板，见 §11）

1. **#16 翻「对齐」并登记窄差 D190**。`lane` 是 Java 多 lane 模型的承重字段；pi `parseSessionEntries` 只做 `JSON.parse`（`session-manager.ts:355-370`），未知键照读，双向互读已经成立。P0 队列的 session 唯一项随之清零。
2. **#17 实现 pi v1／v2 迁移**（不继续维持「刻意非目标」）。读取时迁移并重写文件，与 pi `_loadEntries:1085-1101` 一致。
3. **#8 在本批实现 `systemMessage`**（含 pi `buildContextEntries:506` 的保留段 system 消息过滤），不把列集分叉留到后续批。
4. **#3／#9／#14／#20 只做取证翻账，不改代码**：四项都是 map/06 账面滞后（B55 已在 agent-core 批 4 关闭、`fromHook` 已存在、用户可见统计已逐 pi、内存后端是误读）。

### 范围内

| 单元 | 本批交付 |
|---|---|
| #19（w2，缺失） | `JsonlSessionStorage` 惰性创建＋首次对话一次性落盘＋fork／load 语义对齐 |
| #17（w2，缺失） | `LegacyPiVersionMigrator`：v1→v2→v3 节点级迁移、重写、v3 行内 `role:"custom"` 读取 |
| #8（w3，存疑） | `Entry.Compaction.systemMessage` 全链：形状／编解码／投影／生产者／保留段过滤 |
| #16（w3，存疑） | 信封扩展键收口：`lane`＋`retainedTail` 登记为 pi 容忍键；`retainedTail` 读取改可选 |
| #3／#9／#14／#20 | 取证翻账（文档） |

### 范围外

- lane 模型本身（pi 单 leaf 树）不重做；#6 维持存疑（S1）。
- `version > 3` 的未来格式不做前向兼容（维持显式报错，D193）。
- 无头文件的 entries 采用（pi `_loadEntries:1095-1098`）不做；Java `load` 继续要求首行 header。
- SQLite 后端不接入 `systemMessage`（其载荷走同一 `SessionWireShape`，天然随 `Entry` 记录序列化；无额外工作）。

---

## §1 现状与账面

### 20 行逐行实算（map/06 当前口径 → 本批判定）

| # | 权重 | 能力 | java 现状（批 33 前） | 旧判定 | 本批判定 | 依据 |
|---|---:|---|---|---|---|---|
| 1 | 2 | create 建会话 | `SessionRepository.create:18`／`JsonlSessionRepository.create:47-65` | 对齐 | 对齐 | create 变惰性仍建会话（D191） |
| 2 | 2 | list 列会话 | `JsonlSessionRepository.list:72-95` | 对齐 | 对齐 | — |
| 3 | 1 | list 进度回调＋增量发布＋中止＋并发 | `RichSessionLister` 已实现（10/64 路并发＋`onProgress`） | 缺失 | **对齐** | B55 已在 agent-core 批 4 关闭；本批仅纠账面 |
| 4 | 2 | open 打开会话 | `JsonlSessionRepository.open:67-70` | 对齐 | 对齐 | 未落盘会话走程序化 API 有窄差 D191 |
| 5 | 2 | fork 分支会话 | `SessionRepository.fork:33`／`JsonlSessionStorage.fork:105-121` | 对齐 | 对齐 | fork 立即物化（D191） |
| 6 | 2 | 分支创建／移动 | `createLane`／`moveLane`（lane 模型） | 存疑 | **存疑（维持）** | pi 单 leaf 树 vs Java lane（S1） |
| 7 | 3 | 追加 entry | `appendEntry`＋9 变体 | 对齐 | 对齐 | — |
| 8 | 3 | compaction 条目 | `Entry.Compaction:191-208`：多 `retainedTail`、缺 `systemMessage` | 存疑 | **对齐** | 本批 §4 |
| 9 | 3 | 分支摘要条目 | `Entry.BranchSummary:217-227` 已有 `fromHook` | 存疑 | **对齐** | 账面滞后（S3 已不成立） |
| 10 | 3 | compaction-aware 投影 | `ContextEntries.projectEntries:171-200` | 对齐 | 对齐 | §4 增加 systemMessage 臂 |
| 11 | 3 | `context_edit` | `Entry.ContextEdit`＋投影／编解码 | 对齐 | 对齐 | — |
| 12 | 2 | 会话名 | `session_info` 原生线 | 对齐 | 对齐 | — |
| 13 | 1 | entry 标签 | `label` 原生线 | 对齐 | 对齐 | — |
| 14 | 2 | usage 条目＋汇总 | 一等 `Entry.Usage`；`coding-agent SessionStats`＋`UsageCostBreakdown` | 存疑 | **对齐** | 用户可见统计已逐 pi（S4 账面滞后） |
| 15 | 3 | JSONL 头部线格式 | `PiV3Wire.encodeHeader:75-94`／`parseHeader:97-121` | 对齐 | 对齐 | §5 放宽为 1/2/3 |
| 16 | 3 | JSONL 条目信封 | `PiV3Wire.encodeEntryLine:139-158`：去 seq、ISO、`lane` 扩展键 | 存疑 | **对齐** | `lane`＋`retainedTail` 为 pi 忽略键（D190） |
| 17 | 2 | pi v1→v2→v3 迁移 | 当前对非 v3 报错（`PiV3Wire:103-109`） | 缺失 | **对齐** | 本批 §5（D192–D194） |
| 18 | 2 | JSONL 文件名／目录名 | `sessionFileName`／`sessionDirectoryName` 同形 | 对齐 | 对齐 | — |
| 19 | 2 | 会话文件惰性创建 | `JsonlSessionStorage.create:43-48` 建即写头 | 缺失 | **对齐** | 本批 §3（D191） |
| 20 | 1 | 内存后端 | `MemorySessionStorage`＋coding-agent 注册表 | 存疑 | **对齐** | pi 单类与 Java「存储＋注册表」是分层不是缺失（S6 账面滞后） |

**汇总（本批后）**：Σ 权重 **44**；对齐 **19 行**（Σw 42.0）＋存疑 **1 行**（#6，w2 → 1.0）＋缺失 **0 行** ⇒ **43.0/44 ＝ 97.7%**（未加权 19/20 ＝ 95.0%）。

### 台账立项

- 本批不新立项：三项实现都有承载能力面，无独立工单需要；窄差 D190–D194 登记在 `docs/04` 的批 33 闭环条。
- `docs/04` 无 B55 行（已在批 4 销号），本批只纠 `map/06` 账面。

---

## §2 总体结构与任务拆分

### 模块与文件

| 模块 | 文件 | 改动 |
|---|---|---|
| `pi-java-agent-core` | `entry/Entry.java` | `Compaction` 末位增 `systemMessage`；旧 11 参构造器保留；`committed` 携带 |
| | `session/jsonl/EntryJsonCodec.java` | 读 `systemMessage`；`retainedTail` 改可选；`message.role:"custom"` → `Entry.CustomMessage` |
| | `session/jsonl/PiV3Wire.java` | 头解析接受 v1/v2/3；>3 仍报错 |
| | `session/jsonl/LegacyPiVersionMigrator.java`（新） | v1→v2→v3 节点迁移＋原子重写 |
| | `session/jsonl/JsonlSessionStorage.java` | 惰性创建／首次对话 flush／fork 物化／load 前迁移 |
| | `session/ContextEntries.java` | compaction 产 `[systemMessage, summary]`；edit 对每条消息；保留段过滤 system |
| | `harness/CompactionTranscriptBuilder.java` | 两臂计算 `systemMessage`＋时间戳覆盖 |
| `pi-java-agent-core`（测试） | `JsonlSessionStorageTest`、新 `PiLegacyMigrationTest`、`CompactionSystemMessageTest` 等 | 见 §7 |

模块分工不变；`pi-java-ai` 的 `Transcripts.getCurrentSystemMessage`（`:141-170`）是唯一系统提示重放源。

### 任务拆分

| 任务 | 内容 | 依赖 |
|---|---|---|
| **T1** | 取证翻账：#3／#9／#14／#20 的判定与证据写入 `map/06`＋`docs/05`（无代码） | 无 |
| **T2** | #19 惰性创建：`JsonlSessionStorage` 内存缓冲＋首次对话 flush＋fork 物化＋metadata 占位 | 无 |
| **T3** | #17 迁移：`PiV3Wire.parseHeader` 放宽＋`LegacyPiVersionMigrator`＋`load` 接线＋`role:"custom"` 读取 | T2（load 结构稳定后接入） |
| **T4** | #8 `systemMessage`：Entry／编解码／投影／生产者／保留段过滤；`retainedTail` 可选 | 无（可与 T2/T3 并行） |
| **T5** | 测试：迁移、惰性创建、systemMessage 投影与落盘、版本闸门 | T2–T4 |
| **T6** | 全量回归（`mvn clean verify`）＋FileLength 复核＋文档回填（§10） | T5 |

实施顺序建议：T4 → T2 → T3 → T5 → T1（文档）→ T6。T4 先落是因为 T3 的迁移测试要读带 compaction 的 v1 夹具，而 `retainedTail` 可选化是那条路的前置。

### 文件长度闸门

所有目标文件本批后均 <500 行（FileLength 已是 `severity=error`）：

- `JsonlSessionStorage` 356 → 约 420；新迁移器约 150；`ContextEntries` 303 → 约 340；
- `CompactionTranscriptBuilder` 133 → 约 165；`Entry.java` 约 430 → 约 445；`EntryJsonCodec` 约 198 → 约 230。

---

## §3 #19 会话文件惰性创建

### pi 证据

```
:1163  // closing pi without chatting leaves no file behind. Starting at the user message (not the
:1164  // first assistant reply) keeps the prompt on disk if the first turn never completes (#10000).
:1166  private _hasConversation(): boolean {
:1167      return this.fileEntries.some(
:1168          (e) => e.type === "message" && (e.message.role === "user" || e.message.role === "assistant"),
:1169      );
:1172  private _persist(): void {
:1173      if (!this.persist || !this.sessionFile) return;
:1175      if (!this.flushed) {
:1176          if (!this._hasConversation()) return;
:1177          const fd = openSync(this.sessionFile, "wx");
:1178          try {
:1179              for (const e of this.fileEntries) { ... }
```

fork 臂（`createBranchedSession:1712-1724`）：`_hasConversation() ? _rewriteFile() + flushed=true : flushed=false`。

### Java 现状与差距

`JsonlSessionStorage.create:44-48` 在建立对象时即 `fs.writeFile(path, header)`，因此：

- 新建会话立刻在 `listSessions` 可见，pi 不可见（直到首条 user/assistant 消息）；
- 只发系统提示／只改模型但没对话的会话会留下空壳文件，pi 不落盘。

### 设计

`JsonlSessionStorage` 增加三个字段：

```java
private final JsonlV4Header header;      // create 时传进来的头，flush 用
private boolean flushed;                  // 文件是否已存在
private final StringBuilder pending = new StringBuilder(); // 未落盘的已编码行（含尾换行）
```

**create（公开）改为惰性**：

```java
public static JsonlSessionStorage create(fs, path, header) {
    var storage = new JsonlSessionStorage(fs, header, metadata(header, path, header.createdAtMs()));
    storage.flushed = false;   // 不写盘
    return storage;
}
```

`metadata.modifiedAtMs` 在无文件时用 `header.createdAtMs()` 占位（无文件不可观察；只影响程序化 `getMetadata()` 的读数）。

**appendMutation 改为先缓冲后落盘**：

```java
private void appendMutation(SessionMutation mutation) {
    String line = JsonlCodec.encodeMutation(mutation);
    if (!flushed) { pending.append(line); return; }
    fs.appendFile(metadata.path(), line);
}
```

**首次对话触发一次性落盘**（在 `appendEntry` 的 `applyMutation` 之后）：

```java
private void flushIfConversationStarted() {
    if (flushed || !hasConversation()) return;
    String temp = metadata.path() + ".tmp";
    fs.writeFile(Path.of(temp), JsonlCodec.encodeHeader(header) + pending);
    fs.renameFile(Path.of(temp), metadata.path());
    pending.setLength(0);
    flushed = true;
}
```

`hasConversation()`：扫描 `state.getLog(LogOptions.none())`，命中 `LogItem.EntryItem` 且 `Entry.Message.message().role()` 为 `"user"`／`"assistant"`。`BashExecution` 是独立 `Entry` 变体，不命中；system 消息也不命中 —— 与 pi `_hasConversation` 的三条件逐字一致。

`flushIfConversationStarted` 只在 `appendEntry` 后调用；其它 mutation（record／lane／fact）继续进缓冲，随首次 flush 一起按序写出（pi `_persist` 写的是整份 `fileEntries`，同样包含这些条目）。写入顺序是 create 之后的提交序，与内存状态一致。

**load**：读文件成功后 `flushed = true`（文件已存在）。

**fork**：`fork` 需要一个可 `load` 的实体文件，故走内部 `createImmediate`（写头＋`flushed=true`）：

```java
private static JsonlSessionStorage createImmediate(fs, path, header) {
    fs.writeFile(path, JsonlCodec.encodeHeader(header));
    var storage = ...;
    storage.flushed = true;
    return storage;
}
```

`fork:105-121` 的 `create(fs, tempPath, header)` 改调 `createImmediate`；其余 temp＋rename＋load 不变。

**崩溃语义**：首条对话前进程退出 ⇒ 无文件（与 pi 同）；flush 用 temp＋rename，文件要么不存在、要么是完整头＋前缀。

**程序化面窄差**：未落盘会话没有实体文件 ⇒
- `SessionRepository.open(path/metadata)` 读取会失败（pi 对不存在的显式路径按新建会话处理，`_loadEntries` 的 else 臂）；
- Java `fork` 立即物化，pi 分支在无对话时不落盘。

两条都只在程序化 API 可达，UI／CLI 路径不可观察 ⇒ D191。

---

## §4 #8 compaction `systemMessage`

### pi 证据

```
:100  /** True if generated by an extension, undefined/false if pi-generated (backward compatible) */
:101  fromHook?: boolean;
:102  /** Complete prompt and tool state at this compaction boundary. */
:103  systemMessage?: SystemMessage;
:104  }

:461  if (entry.type === "compaction") {
:462      const summary = createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp);
:463      return entry.systemMessage ? [entry.systemMessage, summary] : [summary];
:464  }

:506  if (foundFirstKept && !(entry.type === "message" && entry.message.role === "system")) {
:507      contextEntries.push(entry);
:508  }

:525  return messages.map((message) => { ... });      // edit 对投影出的每条消息生效

:1270  const systemMessage = getCurrentSystemMessage(this.buildSessionProjection().messages);
:1283  ...(systemMessage ? { systemMessage: { ...systemMessage, timestamp: new Date(timestamp).getTime() } } : {}),
```

### Java 现状

- `Entry.Compaction:191-208` 没有 `systemMessage`；投影只出摘要（`ContextEntries.project:257-259`）；
- `contextEntries:120-128` 在保留段不过滤 system 消息（pi `:506`）；
- `projectEntries:191-196` 的 edit 只作用于 `getFirst()`；
- `EntryJsonCodec:48-55` 的 `retainedTail` 用 `MessageJsonCodec.decodeList`，该法对 `null` 抛错 —— pi 的 compaction 行没有这个键，**当前无法读入任何 pi compaction**（本批随 #16 修）。

### 设计

#### 4.1 形状

`Entry.Compaction` 末位加第 12 个组件，并保留旧 11 参构造器（全部存量构造点零改签）：

```java
record Compaction(
    String id, long seq, String parentId, Instant timestamp,
    String summary, String firstKeptEntryId, List<Message> retainedTail,
    int tokensBefore, Map<String, Object> details, Usage usage, Boolean fromHook,
    Message.SystemMessage systemMessage
) implements Entry {
    public Compaction(String id, long seq, String parentId, Instant timestamp, String summary,
                      String firstKeptEntryId, List<Message> retainedTail, int tokensBefore,
                      Map<String, Object> details, Usage usage, Boolean fromHook) {
        this(id, seq, parentId, timestamp, summary, firstKeptEntryId, retainedTail,
            tokensBefore, details, usage, fromHook, null);
    }
    public Compaction { retainedTail = List.copyOf(retainedTail); }
}
```

`committed:101-103` 末位传 `e.systemMessage()`。

#### 4.2 编解码

- 写：record 组件名即线名；`SessionJson.messageNode:164-199` 已有 SystemMessage 的完整形状（timestamp 为 epoch ms、sections 保留 null 删除态、toolsAdded 走 `Transcripts.toToolDeclaration`）。`SessionWireShape.remap` 原样透传。
- 读（`EntryJsonCodec:48-55`）：

```java
decodeSystemMessage(node.get("systemMessage")),          // 缺席/null ⇒ null
decodeRetainedTail(node.get("retainedTail"));            // 缺席/null ⇒ List.of()
```

`decodeSystemMessage` 调 `MessageJsonCodec.decode` 后校验确实是 `Message.SystemMessage`，否则 schema 错。

`retainedTail` 是 Java 内部边界桥字段（压缩时保留段消息的副本），pi 不识别；它在盘上存在时 pi 忽略（D190），Java 读自己的旧盘仍可用。

#### 4.3 投影

`ContextEntries.projectEntries:180-197` 改为「一条源条目 → 消息列表」：

```java
List<Message> messages = e instanceof Entry.Compaction && index > 0
    ? List.of()
    : projectMessages(e);
Entry.ContextEdit edit = edits.get(e.id());
if (edit != null) messages = applyEdit(messages, edit);
```

- `projectMessages(Entry.Compaction c)`：`systemMessage != null ? [systemMessage, summary] : [summary]`（pi `:461-464`）；其余条目仍单条。
- `applyEdit` 改为列表级：`replacement == null` ⇒ 空列表；否则对**每条**消息套用现有单条逻辑（`:217-240`），system 消息不在 pi 白名单（user／assistant／toolResult／custom）⇒ 原样保留（pi `:525-539`）。
- `contextEntries:120-128` 的保留段循环加过滤：

```java
if (foundFirstKept && !isSystemMessageEntry(e)) { context.add(e); }
```

`isSystemMessageEntry`：`e instanceof Entry.Message m && m.message() instanceof Message.SystemMessage`。压缩点之后的条目照常全收（pi `:510`）。

#### 4.4 生产者

`CompactionTranscriptBuilder` 两臂（`compactTranscript:56-89`、`suppliedTranscript:98-108`）在构造 `Entry.Compaction` 前取当前投影的系统提示：

```java
Instant now = Instant.now();
var systemMessage = currentSystemMessage(lane.transcript, now);
```

```java
private static Message.SystemMessage currentSystemMessage(List<Entry> transcript, Instant timestamp) {
    var current = Transcripts.getCurrentSystemMessage(ContextEntries.toMessages(transcript));
    return current == null ? null
        : new Message.SystemMessage(current.content(), timestamp,
            current.sections(), current.toolsAdded(), current.toolsRemoved());
}
```

- `Transcripts.getCurrentSystemMessage`（`Transcripts:141-170`）已经逐字实现 pi `getCurrentSystemMessage`：content 按 `"\n\n"` 拼接、sections 按名覆盖（null 删除）、toolsAdded 来自 `getCurrentTools`、无系统消息且无工具 ⇒ null。
- 时间戳覆盖：pi 在 `:1283` 把 `systemMessage.timestamp` 改成 compaction 发生时刻；Java 用与 entry 相同的 `now`，保持「entry 时间戳 ＝ systemMessage 时间戳」。
- 两臂都计算：pi 的 `appendCompaction:1260-1288` 不分 `fromHook`，扩展供应压缩同样带 `systemMessage`。

#### 4.5 行为变化

压缩后的模型上下文从 `[摘要]` 变成 `[系统消息, 摘要]`（系统消息为空时仍是 `[摘要]`）。这是与 pi 一致的行为修正；既有会话重新加载后，老 compaction 条目无 `systemMessage` ⇒ 投影不变。

---

## §5 #17 pi v1／v2 迁移

### pi 证据

```
:287  function migrateV1ToV2(entries: FileEntry[]): void {
:288      const ids = new Set<string>();
:289      let prevId: string | null = null;
:291      for (const entry of entries) {
:292          if (entry.type === "session") { entry.version = 2; continue; }
:297          entry.id = generateId(ids);
:298          entry.parentId = prevId;
:299          prevId = entry.id;
:302          if (entry.type === "compaction") {
:304              if (typeof comp.firstKeptEntryIndex === "number") {
:305                  const targetEntry = entries[comp.firstKeptEntryIndex];
:306                  if (targetEntry && targetEntry.type !== "session") {
:307                      comp.firstKeptEntryId = targetEntry.id;
:308                  }
:309                  delete comp.firstKeptEntryIndex;
:310              }
:311          }
:312      }
:313  }

:316  function migrateV2ToV3(entries: FileEntry[]): void {
:318      if (entry.type === "session") { entry.version = 3; continue; }
:324      if (entry.type === "message") {
:326          if (msgEntry.message && msgEntry.message.role === "hookMessage") {
:327              msgEntry.message.role = "custom";
:328          }
:329      }
:330  }

:341  if (version >= CURRENT_SESSION_VERSION) return false;
:343  if (version < 2) migrateV1ToV2(entries);
:344  if (version < 3) migrateV2ToV3(entries);

:355  parseSessionEntries: JSON.parse 每行，畸形行 catch 跳过
:1092  if (migrateToCurrentVersion(this.fileEntries)) this._rewriteFile();
```

v1 夹具（`test/fixtures/before-compaction.jsonl:1`）：头 **无 `version`**，额外带 `provider/modelId/thinkingLevel/branchedFrom`；条目无 `id/parentId`；compaction 行带 `firstKeptEntryIndex:293`／`tokensBefore:175004`，无 `firstKeptEntryId`。

`generateId`（`:277-284`）：`randomUUID().slice(0, 8)`，最多 100 次碰撞重试，最后回退完整 UUID。

### Java 设计

#### 5.1 头解析放宽

`PiV3Wire.parseHeader:103-109` 改为：

- 缺失 `version` ⇒ 1；`version ∈ {1,2,3}` ⇒ 可解析（`JsonlV4Header.version` 记原值）；
- `version > 3` ⇒ 保持显式 `schema` 错（D193，pi 会按当前语法继续读未来版本）；
- `version` 非数字 ⇒ schema 错。

实际上 `load` 会在严格解析前先迁移，所以放宽主要用于「迁移器判断版本」与直接调用 `parseHeader` 的测试。

#### 5.2 `LegacyPiVersionMigrator`（新类，约 150 行）

节点级迁移，保留头里的未知键（pi 同样保留）：

```java
final class LegacyPiVersionMigrator {
    static boolean needsMigration(JsonNode header);       // type:"session" 且 version<3
    static void migrate(JsonlSessionRepoFileSystem fs, Path path, String content);
}
```

`migrate` 步骤：

1. **逐行解析**：`SessionJson.mapper().readTree(line)`，畸形行直接跳过（pi `:361-366` 同）。首行必须是 `type:"session"` 头。
2. **v1 → v2**（`version < 2`）：
   - 头 `version = 2`；
   - 对每个非 header 条目：`id = generateId(ids)`（8 位 UUID 截断＋碰撞重试）、`parentId = prevId`、`prevId = id`；
   - 第二遍把 `compaction.firstKeptEntryIndex` 换成 `firstKeptEntryId`：索引语义是**整份条目数组**（头在 0 位，与 pi `entries[comp.firstKeptEntryIndex]` 一致），目标存在且 `type != "session"` 才换；无论成败都删 `firstKeptEntryIndex`。
   - 与 pi 的一遍循环相比，Java 用「先分配全部 id、再解析索引」两遍；对合法文件结果一致，对索引指向后续条目的文件更稳。
3. **v2 → v3**（`version < 3`）：
   - 头 `version = 3`；
   - `type:"message"` 且 `message.role == "hookMessage"` ⇒ 就地改 `message.role = "custom"`（与 pi 逐字一致；不发明 `customType`）。
4. **原子重写**：temp＋rename，内容＝迁移后的头行＋全部条目行（保留头额外键，行序不变）。

`load` 接线（`JsonlSessionStorage.load:51-65`）：

```java
String content = fs.readTextFile(path);
String[] lines = split(content);
if (lines[0] 非空 && LegacyPiVersionMigrator.needsMigration(parseObject(lines[0]))) {
    LegacyPiVersionMigrator.migrate(fs, path, content);
    content = fs.readTextFile(path);          // 重读迁移结果
    lines = split(content);
}
// 之后走现有严格解析（parseHeader 要求 v3、parseMutation 逐行）
```

迁移后的文件已是 v3，`parseHeader` 的 v3 分支正常读；`metadata` 的 mtime 在重写后取。

#### 5.3 v3 文件里的 `role:"custom"`

pi 迁移只改 role，条目仍是 `type:"message"`。Java 的 `Message` sealed 类型没有 `custom` 变体，需要在 `EntryJsonCodec` 的 `TYPE_MESSAGE` 分支特判：

```java
if (messageNode != null && "custom".equals(messageNode.path("role").asText())) {
    yield new Entry.CustomMessage(id, seq, parentId, timestamp,
        "hookMessage",                                 // 旧 hook 消息没有 customType
        decodeContent(messageNode.get("content")),     // 裸串/块数组
        true,                                          // 旧 hook 消息是用户可见的
        JsonlCodec.optionalObject(messageNode, "details"));
}
```

这样既能读 Java 自己迁移后的 v2 文件，也能读**已被 pi 迁移过**的 v3 文件。模型可见面等价：pi 的 `convertToLlm` 把 `role:"custom"` 转成 user 消息（`agent/src/types.ts:209-211` 的示例契约），Java 的 `Entry.CustomMessage` 投影同样产 `UserMessage`。

若该条目后来被 Java 重写（fork／迁移），线形会变成原生 `custom_message`；pi 可读 → D194。

---

## §6 取证翻账（无代码四项）

| # | 旧判定 | 新判定 | 证据 |
|---|---|---|---|
| 3 | 缺失 | **对齐** | `RichSessionLister` 已实现 10/64 路并发扫描＋`onProgress` 增量发布＋取消；B55 在 agent-core 批 4 已销号，`map/06:81` 未跟着翻 |
| 9 | 存疑（java 缺 `fromHook`） | **对齐** | `Entry.BranchSummary:217-227` 已有 `fromHook`；`EntryJsonCodec:56-61` 读写均存在（`optionalFromHook:160-166`） |
| 14 | 存疑（汇总桶不同） | **对齐** | 用户可见面在 `coding-agent`：`SessionStats.UsageTokens(input,output,cacheRead,cacheWrite,total)`（`:27-79`）＋`UsageCostBreakdown`（provider/model 分组、cost 降序）；agent-core `SessionStats` 只服务内部存储一致性测试，不是 pi 可观察面 |
| 20 | 存疑（两构造） | **对齐** | `MemorySessionStorage` 是单类；coding-agent `InMemorySessionRepository` 是**进程内注册表**（不是第二种存储实现），与 pi `SessionManager.inMemory` 的工厂方法同层 |

四项只改 `map/06` 与 `docs/05` 的文字与汇总，不改源码；因此列在 T1，不占 T2–T5。

---

## §7 测试设计

### 7.1 惰性创建（#19）

文件：`JsonlSessionStorageTest`（扩展）＋新 `JsonlLazyCreationTest`。

1. `create` 后 `fs.exists(path) == false`；`getMetadata().modifiedAtMs == header.createdAtMs()`。
2. `create` → `appendEntry(系统消息)`（无 user/assistant）→ 仍无文件；`load` 不可（D191 语义）。
3. `create` → `appendEntry(用户消息)` → 文件出现，内容＝header＋两条（系统＋用户）按序；再 append assistant ⇒ 直接 `appendFile`，不重写头。
4. `create` → `createLane`／`setName` → 无文件；随后 user 消息 ⇒ 这些行也一起写出且顺序正确。
5. `load` 已有文件 ⇒ `flushed=true`，append 立即落盘（回归现有用例）。
6. `fork` 无对话源 ⇒ 目标文件仍存在且可 `load`（D191）。

### 7.2 v1／v2 迁移（#17）

文件：新 `PiLegacyMigrationTest`。

1. 构造 v1 内容（头无 version，带 `provider/modelId/thinkingLevel/branchedFrom`；两条消息无 id/parentId；一条 compaction 带 `firstKeptEntryIndex`）。
   - 迁移后：头 `version:3` 且保留四个额外键；条目有 8 位 id、parentId 链；compaction 有 `firstKeptEntryId`、无 `firstKeptEntryIndex`；
   - 再 `load` 能读出消息与 compaction（`retainedTail` 缺席 ⇒ 空表）。
2. v2 内容（头 `version:2`，一条 `message.role:"hookMessage"`）：
   - 迁移后头 `version:3`、role `"custom"`；
   - Java 读出 `Entry.CustomMessage(customType="hookMessage", display=true)`；投影为用户消息。
3. 已迁移 v3 文件（`role:"custom"`）直接 `load` 也成功（不需再次迁移）。
4. 畸形行：v1 文件中间一行 `{` ⇒ 迁移时丢弃，重写后文件不再含该行（pi `parseSessionEntries` 同）。
5. `version:4` ⇒ `JsonlSessionError`（D193）；`version:99` 同。
6. v1 compaction 的 `firstKeptEntryIndex` 指向 header（0）⇒ 不产出 `firstKeptEntryId`，但仍删除旧键（pi `:306`）。

### 7.3 `systemMessage`（#8）

文件：新 `CompactionSystemMessageTest`＋`ContextEntriesTest` 扩展。

1. 编解码 round-trip：`Entry.Compaction` 带 SystemMessage（content/sections 含 null 删除态/toolsAdded：timestamp）⇒ 写线再读回逐字段相等；旧文件无键 ⇒ `null`。
2. `retainedTail` 缺席 ⇒ `List.of()`（pi compaction 行可读）。
3. 投影：带 systemMessage 的 compaction ⇒ `[SystemMessage, summary]`；不带 ⇒ `[summary]`。
4. `context_edit` 作用于 compaction：replacement 非 null ⇒ 摘要消息替换、systemMessage **原样保留**；replacement null ⇒ 两条都剔除。
5. `contextEntries`：保留段里夹一条 system 消息 ⇒ 被过滤；压缩点之后的 system 消息保留（pi `:506` vs `:510`）。
6. 生产者：`compactTranscript`／`suppliedTranscript` 产出的 `Entry.Compaction.systemMessage` 取自压缩前投影；其 timestamp ＝ entry timestamp；无系统消息且无工具 ⇒ null。

### 7.4 回归与闸门

- 存量 `CompactionService`／`CompactionExecutor`／harness 测试：11 参构造器保留 ⇒ 零改签；断言若比较整条 `Compaction` 需补第 12 位。
- `PiV3WireFormatTest`／`PiNativeSpelling*Test`／`MessageJsonCodecSystemReadbackTest` 保持绿。
- 新增测试文件每个 <500 行。

---

## §8 窄差与行为变化

### 窄差

| 编号 | 内容 | 类别 |
|---|---|---|
| **D190** | JSONL 条目信封的 Java 扩展键：`lane`（承重）＋`retainedTail`（边界桥）；pi `JSON.parse` 忽略未知键，双向可读。lane/record 合成行仍借 pi `custom`／`customType` 承载 | 内部·线形 |
| **D191** | 惰性创建的程序化面：未落盘会话无实体文件 ⇒ Java `open(metadata)` 读失败、`fork` 立即物化；pi 对不存在路径新建会话、分支无对话不落盘。仅程序化 API 可达 | 内部·边缘 |
| **D192** | v1→v2 补 id 的随机源：Java 8 位 `UUID` 截断＋碰撞重试（pi `randomUUID().slice(0,8)`）；落盘后稳定，外部不可观察 | 内部 |
| **D193** | `version > 3` 的头显式报错（pi 按当前语法继续读未来版本）；保护严格解析不被未知形状带偏 | 边缘 |
| **D194** | v2 `hookMessage` 在 Java 侧读作 `Entry.CustomMessage`（`customType:"hookMessage"`、`display=true`）；pi 就地改 role 为 `custom`。模型可见面等价（两臂都投影 user 消息）；若 Java 重写则落为原生 `custom_message` | 内部·线形 |

### 行为变化注记（changelog 级）

1. **新建会话不再立即产生文件**：空会话（只设模型／只写系统提示／只改标签）在磁盘上不可见；首条 user/assistant 消息出现时才一次性落盘。
2. **fork 目标在无对话时仍立即有文件**（D191，程序化面）。
3. **可读 pi v1／v2 会话**：读取成功后会原子重写为 v3（头保留额外键；畸形行丢弃）。
4. **可读 pi v3 里 `role:"custom"` 的旧 hook 消息**，显示为 custom message。
5. **压缩后的上下文多一条系统消息**（`[systemMessage, summary]`），与 pi 一致。
6. **`retainedTail` 解码变宽松**：缺席不再报错，pi 的 compaction 行可读。

---

## §9 预计闭环账面

### map/06

| 项 | 批 33 前 | 批 33 后 |
|---|---|---|
| 存储后端（Σ44） | 32.0 ＝ 72.7% | **43.0 ＝ 97.7%**（对齐 19／存疑 1〔#6〕／缺失 0；未加权 19/20） |
| 评测（Σ43） | 42.0 ＝ 97.7% | 42.0 ＝ 97.7%（不动） |
| **本范围（Σ87）** | **74.0 ＝ 85.1%** | **85.0 ＝ 97.7%**（未加权 38/40 ＝ 95.0%） |

**全仓**：1,205.0 → **1,216.0/1,383 ≈ 87.9%**（+11.0）。
`docs/06` 模块账面 Σ485 **不动**（本批全在 map/06 域表，不在 coding-agent 模块表）。

### P0 队列

`docs/05` §3 的「session：JSONL 条目信封形状（#16，w3 存疑）」随 #16 翻对齐**清零**。此后 P0 只剩 coding-agent 的 examples 验收规格（排期最尾）与 tui 的渲染长尾。

### 存疑／缺失清单

- 存疑：仅剩 S1（#6 lane vs pi 单 leaf 树）。
- 缺失：存储后端清零；评测面早已清零（批 32）。

---

## §10 文档回填清单

| 文件 | 回填点 |
|---|---|
| `docs/map/06-session-evals-chord.md` | 20 行表：**8 行改判**（#3 缺失→对齐、#8 存疑→对齐、#9 存疑→对齐、#14 存疑→对齐、#16 存疑→对齐、#17 缺失→对齐、#19 缺失→对齐、#20 存疑→对齐）；`#8` 证据列补 `systemMessage`；`#16` 证据列补 `retainedTail`；行数／权重／汇总（`:100-103`、`:271-278`）；差距表 `:296-305`（重算为 3 条清零）；存疑清单 S1–S7 收窄为 S1；缺失清单 A 清零；翻转清单追加批 33 段 |
| `docs/map/03-agent-core.md` | `#73` 注记：信封扩展键（lane／retainedTail）与惰性创建不改变 v3 落线判定；`PiV3Wire` 头解析放宽到 v1/v2 |
| `docs/04-open-items-register.md` | 批 33 闭环条（§5，批 32 条之后）：场景／决定 T1–T6／风险代价／窄差 D190–D194／账面／排障钉；§7 维护规则第 6 条把「下一包号」改为 docs/46 |
| `docs/05-module-alignment-map.md` | §2.6 存储后端 72.7%→97.7%、本范围 85.1%→97.7%（残余缺口措辞改「仅 #6 存疑」）；§3 P0 队列删 session 条 |
| `docs/06-coding-agent-batch-plan.md` | §1 行 27/28/29/31/41/42（已闭环 32→33、待审批 0、下一包 docs/46、map/06 85.0/87、全仓 1,216.0/1,383）；§2 批次记录加「批 33」；§3 序 26 行标 **批 33 已闭环**；行 107 之后插入批 33 行 |
| `docs/45-coding-agent-batch33-design.md` | §12 闭环记录（实施后回填测试计数／排障钉／最终账面） |

---

## §11 验收与风险

### 验收命令

```powershell
$env:JAVA_HOME='D:\soft\jdk\jdk-25'
D:\soft\apache-maven-3.9.9\bin\mvn.cmd -o clean verify
```

验收口径：

- 退出码 0；
- 测试 0 失败 0 错误（记录总数与跳过数）；
- checkstyle 0 错误 0 警告（15 模块）；
- spotbugs 13 模块零钉；
- FileLength：`JsonlSessionStorage`／`LegacyPiVersionMigrator`／`ContextEntries`／`CompactionTranscriptBuilder`／`Entry`／`EntryJsonCodec` 全部 <500 行。

### 风险与排障钉

1. **严格解析顺序**：迁移必须在 `parseHeader`／`parseMutation` 之前完成；迁移后要**重读文件**，不能复用旧 `content`。若顺序反了，v1/v2 仍报 `unsupported session version`。
2. **惰性 flush 的调用点**：只有 `appendEntry` 后检查对话；漏掉会让首条用户消息不落盘，或让 record/lane 行丢出缓冲。测试覆盖「先写标签/记录、后写用户消息」的顺序。
3. **fork 物化**：`createImmediate` 只给 fork 用；若误把 `create` 改回写盘，#19 静默失效。测试 `create` 后 `exists==false` 是硬闸门。
4. **`retainedTail` 可选化**：`MessageJsonCodec.decodeList` 的 null 抛错是既有行为，只在 `EntryJsonCodec` 的 Compaction 分支加兜底；不要放宽 `decodeList` 本身（其它调用点依赖严格）。
5. **record 加组件**：`Entry.Compaction` 从 11→12 组件后，全仓搜「逐臂 `new Compaction`」的重建点（批 30 教训）——已知 `Entry.committed` 与 `CompactionTranscriptBuilder` 两处，测试夹具若做整条比较需补第 12 位。
6. **systemMessage 时间戳**：entry 与 systemMessage 必须用同一个 `Instant`；`Transcripts.getCurrentSystemMessage` 返回的是第一条系统消息的时间戳，不能直接沿用。
7. **`role:"custom"` 的 `content` 可能缺失**：旧 hook 消息可能有 `content:null`；`decodeContent` 前按空内容兜底（空块表），不要抛 schema 错（pi 的 message 分支对 null content 也是宽容的）。

---

## §12 闭环记录（实施后回填）

- 实施日期：2026-10-31。
- 最终 `mvn clean verify`（16 模块，11 分 12 秒，退出码 0）：测试 **5,720**／失败 **0**／错误 **0**／跳过 **29**（Agent Core 1、TUI 1、Evals 27；均为既有条件跳过）。分模块：Telemetry 53／AI 1,753／MCP 260／Agent Core 913／SQLite 37／Coding Agent 1,765／TUI 599／Protocol 14／Client 0／Server 2／Web 59／Evals 196／Examples 69／Dist 0。
- checkstyle：15 模块全部 `0 Checkstyle violations`（0 错 0 警）。
- spotbugs：13 个出包模块全部 `BugInstance size is 0`（Dist 无源，不跑）。
- FileLength（六个目标文件，均 <500）：`JsonlSessionStorage` 474／`LegacyPiVersionMigrator` 181／`ContextEntries` 358／`CompactionTranscriptBuilder` 167／`Entry` 366／`EntryJsonCodec` 242。
- 窄差最终编号与措辞：D190–D194（D190 条目信封 `lane` 与 compaction `retainedTail` 为 pi 忽略的扩展键；D191 未落盘会话无实体文件 ⇒ 程序化 `open` 失败、`fork` 立即物化；D192 迁移保留头额外键且空行/畸形行丢弃；D193 `version > 3` 显式报错不做前向兼容；D194 `role:"custom"` 且 `content` 缺席按空块表兜底）。
- 排障钉（实施期实际撞到的）：
  1. **FileLength 499 上限**：`AgentSession.session()` 转公开时加的 7 行 javadoc 让文件到 504 行，checkstyle FileLength（>500，`severity=error`）拦下；压成单行 javadoc 后 499 行。
  2. **惰性创建波及存量夹具**：8 个 agent-core 测试（`AgentSessionCacheWarmingTest`／`InitialNameWritebackTest`／`RecordBashResultTest`／`SessionListProgressOverloadTest`／`SessionPersistenceResolutionTest`／`SessionResumeFoldTest`／`SessionTeardownTest`／`ExportCommandTest`）与 `RpcNewSessionWireTest`、web `WebSessionsProgressTest` 都依赖「建会话即有文件／条目」，均需先写一条 user 消息（或先 `processPrompt`）再断言；`SessionResumeFoldTest` 的「记录为空」断言改为「1 条」。
  3. **`SessionPersistence` 三处 create 后不能再 `handle.open(metadata)`**：`resolvePersistentWeb`／`resolveSessionId`／`createAttached` 改走新文件内存附着；否则全量首轮 coding-agent 大面积 `Session not found`。
  4. **协作中止不是「恰好 1 帧」**：pi 10 路 worker 只在循环头 `throwIfAborted`，在飞 worker 会读完手头文件并发布；`SessionListProgressOverloadTest.abortedSignalStopsListing` 断言放宽为 `≤10`。
  5. **保留段起点选择**：`firstKeptEntryId` 命中旧 system 消息时那条仍会被保留（pi 语义），`ContextEntriesTest.systemMessagesAfterCompactionAreKept` 夹具起点改 `"c"`。
  6. **`EntryQuery.all()` 是 `NEWEST_FIRST`**：`PiLegacyMigrationTest` 不能按数组下标取 compaction 条目，改 `filter(Entry.Compaction.class::isInstance).findFirst()` 与 `get(1)`／`getFirst()`。
  7. **旧 API 名**：`ContextEntriesTest` 里 `afterSystem.getMessage()` 编译错，实为 `Message.SystemMessage.message()`。
  8. **PowerShell 传 `-D`**：`-Dtest=a,b,c` 与 `-Dsurefire.failIfNoSpecifiedTests=false` 必须整体加双引号，否则逗号/点号被当参数分隔符。
- 账面终值：map/06 85.0/87 ＝ 97.7%；全仓 1,216.0/1,383 ≈ 87.9%。
