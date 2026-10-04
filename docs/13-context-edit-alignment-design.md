# 包 13：`context_edit` 条目对齐设计

> **状态：✅ 已闭环（2026-10-04）—— 读侧、投影、写侧全落，全 reactor 14/14。**
> 承接 `docs/12`（JSONL 线格式对齐）的 **D3**：pi 的 `context_edit` 是本轮锚点新增的第 11 个条目型，
> 本仓此前读到即报 `unknown entry type context_edit`。实施记录见 §12。
> **B60/B61 不销号**：类型面全齐，唯一剩余硬判据是真 pi 端到端互读（从未执行）。

## 1. 范围与裁决建议

pi 的 `context_edit` 是**追加式的「改历史」**：不改写旧行，而是追加一条指向旧条目的编辑条目，
在**模型上下文投影**时把目标条目的内容替换掉、或整条剔除（日志里的原文永远留着）。

本包建议的落点：

| # | 事项 | 建议 |
|---|---|---|
| 1 | 线格式：读 pi 写的 `context_edit` 行、写侧产出 pi 同形行 | **做** |
| 2 | 投影语义：`ContextEntries` 应用 omit/replace（含 latest-wins、string→array 归一） | **做**（只解码不应用＝静默错上下文，比报错更糟） |
| 3 | 生产者①溢出恢复省略：把现有「只摘工作副本尾」换成持久 edit 行，目标扩到助手＋其工具结果 | **做**（修一个重载即复活的真分歧，§3.2） |
| 4 | 生产者②扩展边界草稿（`SessionBoundaryDraft`） | **不做** —— 本仓无扩展运行器，与 CacheWarmer 同为「上游设施缺席」类 |
| 5 | TUI：默认视图隐藏、搜索/行渲染标记 | **做**（编译与行为双重要求） |
| 6 | SQLite | **零结构改动**：payload 不透明透传，edit 无 usage 不进 stats |
| 7 | 投影式 token 估算的 edit 失效算法（pi `compaction.ts:245-254`、`:836-855`） | **不逐条移植**（§4.5 裁决 R4） |

## 2. pi 事实（锚点 `200387122`，证据一律 `git show`）

### 2.1 条目形状（`packages/coding-agent/src/core/session-manager.ts:168-180`）

```ts
/** Content that an append-only context edit may replace without changing message metadata. */
export type ContextEditableContent =
	| UserMessage["content"]
	| AssistantMessage["content"]
	| ToolResultMessage["content"]
	| CustomMessage["content"];

/** Append-only change to one earlier entry's contribution to model context. */
export interface ContextEditEntry extends SessionEntryBase {
	type: "context_edit";
	targetId: string;
	/** Null omits the target from model context. A value replaces only its content. */
	replacement: { content: ContextEditableContent } | null;
}
```

线格式基线仍是 `SessionEntryBase`（`:57-63`）：`id/parentId/timestamp`，无 `seq`。
**`replacement` 是必填键、值可为 null**（类型里没有 `?`）—— 写侧必须显式落 `null`。

### 2.2 写入口校验与归一（`session-manager.ts:1360-1396`）

```ts
appendContextEdit(targetId: string, replacement: ContextEditEntry["replacement"]): string {
	if (
		replacement !== null &&
		(typeof replacement !== "object" ||
			!("content" in replacement) ||
			(typeof replacement.content !== "string" && !Array.isArray(replacement.content)))
	) {
		throw new Error("Context edit replacement must be null or contain string/array content");
	}
	const target = this.byId.get(targetId);
	if (!target) throw new Error(`Entry ${targetId} not found`);
	if (!this.getBranch().some((entry) => entry.id === targetId)) {
		throw new Error(`Entry ${targetId} is not on the active branch`);
	}
	const editable =
		target.type === "custom_message" ||
		(target.type === "message" &&
			(target.message.role === "user" ||
				target.message.role === "assistant" ||
				target.message.role === "toolResult"));
	if (!editable) throw new Error(`Entry ${targetId} does not contribute editable model content`);
	const targetRole = target.type === "message" ? target.message.role : "custom";
	const normalizedReplacement =
		replacement !== null &&
		(targetRole === "assistant" || targetRole === "toolResult") &&
		typeof replacement.content === "string"
			? { content: [{ type: "text" as const, text: replacement.content }] }
			: replacement;
	// ……id/parentId: leafId/timestamp 后 _appendEntry
}
```

要点：① 目标必须存在且在当前分支；② 可编辑目标仅 `custom_message` 与 role 为
`user/assistant/toolResult` 的消息；③ assistant/toolResult 的**裸字符串替换在写入点归一成
`[{type:"text"}]`**（它们的 content 不接受字符串）。

### 2.3 投影应用（`session-manager.ts:519-569`）

```ts
function projectContextEntry(entry: SessionEntry, edit: ContextEditEntry | undefined): AgentMessage[] {
	const messages = sessionEntryToContextMessages(entry);
	if (!edit) return messages;
	const replacement = edit.replacement;
	if (replacement === null) return [];

	return messages.map((message) => {
		if (
			message.role !== "user" &&
			message.role !== "assistant" &&
			message.role !== "toolResult" &&
			message.role !== "custom"
		) {
			return message;
		}
		const content =
			(message.role === "assistant" || message.role === "toolResult") && typeof replacement.content === "string"
				? [{ type: "text" as const, text: replacement.content }]
				: replacement.content;
		return { ...message, content } as AgentMessage;
	});
}

// buildSessionProjection 内（:551-554）：
const edits = new Map<string, ContextEditEntry>();
for (const entry of contextEntries) {
	if (entry.type === "context_edit") edits.set(entry.targetId, entry);
}
```

要点：① edit 集合**从压缩裁剪后的 contextEntries 取**（压缩前的旧 edit 随被摘条目一起消失）；
② 同目标多次编辑，`Map.set` ⇒ **后者胜**；③ replacement null ⇒ 投影出 `[]`（剔除）；
④ 非 null ⇒ **只换 content，消息元数据（usage/timestamp/api…）保留**；
⑤ 投影时再次做 string→array 归一 —— 这是给**导入的、未经 appendContextEdit 校验的**行兜底
（测试 “normalizes imported string replacements while projecting”）；
⑥ 目标不在投影里的孤儿 edit：map 里有但无源条目应用 ⇒ **静默忽略**。

### 2.4 两个生产者

| 生产者 | 位置 | 形状 |
|---|---|---|
| 扩展会话边界草稿 | `agent-session.ts:934-936`（`_applyBoundaryDrafts`） | 扩展 API 提交 `ContextEditEntryDraft`（`extensions/types.ts:949-953`） |
| 溢出恢复省略 | `agent-session.ts:1210-1225`（`_omitRecoveryAttempt`） | **只发 `replacement:null`**；目标＝失败助手＋该轮工具结果；随后 `_refreshFinalizedContext()` |

`_omitRecoveryAttempt` 逐字：

```ts
private _omitRecoveryAttempt(message: AssistantMessage, toolResults: AgentMessage[] = []): void {
	const targets = [message, ...toolResults];
	const targetIds = targets.map((target) => this._findPersistedMessageEntryId(target));
	const unresolvedProjectedTarget = targets.some(
		(target, index) => targetIds[index] === undefined && this.agent.state.messages.includes(target),
	);
	if (unresolvedProjectedTarget) {
		throw new Error("Cannot persist recovery omission because a projected message has no source entry");
	}
	for (const targetId of targetIds) {
		if (!targetId) continue;
		const editId = this.sessionManager.appendContextEdit(targetId, null);
		const entry = this.sessionManager.getEntry(editId);
		if (entry) this._emit({ type: "entry_appended", entry });
	}
	this._refreshFinalizedContext();
}
```

它在 post-run 溢出恢复路径（`agent-session.ts:2998-3002`）**先于** `_runAutoCompaction` 执行；
edit 条目进分支后，切点规划看到的就是「助手已消失」的投影（测试
“advances past input for an omitted assistant recovery suffix”：`firstKeptEntryId` 落在被省略
的助手条目上）。

### 2.5 TUI（`tree-selector.ts`）

- `:360-362`：`context_edit` 与 `label/custom/model_change/thinking_level_change/session_info`
  同列为 **settings entry，默认视图隐藏**；
- `:611-613` 搜索文本：`context edit omit|replace <targetId>`；
- `:844-846` 行渲染：`[context omit: <target>]` / `[context replace: <target>]`（dim 色）。

## 3. pi-java 现状与实测分歧

### 3.1 读侧

`JsonlCodec.ENTRY_TYPES`（`:21-23`）无 `context_edit`；`Entry` 联合 8 型无此变体。
pi 写的行进入派发器：无 `kind` 碰撞（本行不带 `kind`）⇒ 过 legacy 判定后在 ENTRY_TYPES 检查处
报 **`unknown entry type context_edit`**；`JsonlSessionStorage.load` 对 schema 错误致命 ⇒ 整个会话不可读。

### 3.2 写侧：溢出恢复只摘副本、不留痕（真分歧）

`PostRunCompactionCheck.java:172-179` 的 R2：

```java
// R2：置闩、把失败的助手消息从**副本**摘掉（日志留着，:2214-2223），
// 再 compact-and-retry。
lane.overflowRecoveryAttempted = true;
var messages = lane.messages;
if (!messages.isEmpty()
        && messages.get(messages.size() - 1) instanceof Message.AssistantMessage) {
    messages.remove(messages.size() - 1);
}
```

与 pi 对比，三处偏差：

1. **省略没有持久化** ⇒ 该会话下次 resume 时 `HarnessUtils.rebuildLaneMessages` 从日志重建，
   失败助手**回到模型上下文**（pi 的 edit 行让它永久剔除）；
2. **只摘助手、不摘它的工具结果**（pi 目标＝`[message, ...toolResults]`）；
3. 摘的是**尾条目**：若尾不是该助手则什么都不做 —— pi 按对象身份定位、不靠尾部位置。

### 3.3 既有投影与压缩设施（可直接承接）

- `ContextEntries`（`session/ContextEntries.java`）：`pathToLeaf → contextEntries → project`
  正是 pi `buildContextEntries + sessionEntryToContextMessages` 的对应；
- `LaneState.appendEntry(EntryFactory)`（D6 落地的锁点）：新 edit 追加走它，取序号/叶在车道监视器内；
- JSONL 写侧 `PiV3Wire.encodeEntryLine`（`:138-153`）：通用 Jackson `valueToTree(entry)` ＋
  去 `seq` ＋ `lane` 扩展键 ＋ ISO 时间戳 ⇒ **新变体零特例编码代码**；
- SQLite：entry payload 原样 JSON 透传，stats 仅按 `instanceof Entry.Usage` 分支 ⇒ **零改动**。

## 4. Java 设计

### 4.1 新变体（`entry/Entry.java`）

`@JsonSubTypes` 增加：

```java
@JsonSubTypes.Type(value = Entry.ContextEdit.class, name = "context_edit")
```

`type()` 增 `case ContextEdit c -> "context_edit";`；`committed()` 增：

```java
case ContextEdit e -> new ContextEdit(e.id(), seq, parentId, timestamp,
    e.targetId(), e.replacement());
```

新 record（同文件）：

```java
/**
 * 追加式上下文编辑（pi {@code ContextEditEntry}，session-manager.ts:175-180）。
 * replacement 为 null ⇒ 目标从模型上下文剔除；非 null ⇒ 只替换其 content。
 */
record ContextEdit(
    String id,
    long seq,
    String parentId,
    Instant timestamp,
    String targetId,
    Replacement replacement
) implements Entry {

    /** pi 的 {@code { content: string | ContentBlock[] }}。 */
    record Replacement(CustomMessageContent content) {}
}
```

**复用 `CustomMessageContent`**：pi 的 replacement content 与 custom_message content 同形
（裸串或块数组），其 `toBlocks()` 已提供 string→单 text 块归一，不新造重复类型。
javadoc 记录 pi 逐字定义，并注明：**`replacement` 键必填、null 也要落键**（§4.4）。

### 4.2 JSON 解码（`EntryJsonCodec.java`）

```java
case "context_edit" -> new Entry.ContextEdit(id, seq, parentId, timestamp,
    JsonlCodec.requireString(node, "targetId"),
    decodeReplacement(node.get("replacement")));
```

```java
/** pi replacement：键必填；null ⇒ 剔除；对象 ⇒ {content: string|块数组}。 */
private static Entry.ContextEdit.Replacement decodeReplacement(JsonNode node) {
    if (node == null || node.isNull()) {
        return null;
    }
    if (!node.isObject() || !node.has("content")) {
        throw JsonlCodec.DecodeError.schema("has invalid context edit replacement");
    }
    JsonNode content = node.get("content");
    if (content.isTextual()) {
        return new Entry.ContextEdit.Replacement(
            new CustomMessageContent.Text(content.textValue()));
    }
    if (content.isArray()) {
        List<ContentBlock> blocks = new ArrayList<>(content.size());
        for (JsonNode item : content) {
            blocks.add(MessageJsonCodec.decodeBlock(item));
        }
        return new Entry.ContextEdit.Replacement(
            new CustomMessageContent.Blocks(blocks));
    }
    throw JsonlCodec.DecodeError.schema("has invalid context edit content");
}
```

注意：`node == null`（键缺失）与 `node.isNull()`（显式 null）在此**合并**为 null（剔除）。
pi 对缺键会在投影时崩、对显式 null 剔除；我们在读入点统一按剔除处理 —— 比 pi 略宽，登记于此。

派发器：`JsonlCodec.ENTRY_TYPES` 增加 `"context_edit"`。

### 4.3 投影应用（`ContextEntries.java`）

```java
public static List<Message> toMessages(List<Entry> leafPath) {
    List<Entry> context = contextEntries(leafPath);
    // pi buildSessionProjection :551-554：edit 从**压缩裁剪后**的条目收集，后者胜。
    var edits = new java.util.HashMap<String, Entry.ContextEdit>();
    for (var e : context) {
        if (e instanceof Entry.ContextEdit edit) {
            edits.put(edit.targetId(), edit);
        }
    }
    List<Message> messages = new ArrayList<>();
    for (var e : context) {
        Message projected = project(e);
        if (projected != null) {
            // 编辑只作用于 user/assistant/tool 消息与 custom_message（appendContextEdit 白名单）；
            // 孤儿 edit（无目标）自然不被取用。
            Message effective = edits.get(e.id()) == null
                ? projected : applyEdit(projected, edits.get(e.id()));
            if (effective != null) {
                messages.add(effective);
            }
        }
    }
    return messages;
}
```

```java
/** pi projectContextEntry：replacement null ⇒ 剔除；非 null ⇒ 只换 content。 */
private static Message applyEdit(Message message, Entry.ContextEdit edit) {
    if (edit.replacement() == null) {
        return null;
    }
    List<ContentBlock> blocks = edit.replacement().content().toBlocks();
    if (message instanceof Message.UserMessage user) {
        return new Message.UserMessage(blocks, user.timestamp());
    }
    if (message instanceof Message.AssistantMessage assistant) {
        return new Message.AssistantMessage(blocks, assistant.stopReason(), assistant.deferred(),
            assistant.api(), assistant.provider(), assistant.model(), assistant.usage(),
            assistant.timestamp(), assistant.errorMessage(), assistant.rawStopReason());
    }
    if (message instanceof Message.ToolResultMessage tool) {
        return new Message.ToolResultMessage(tool.toolUseId(), tool.toolName(), blocks,
            tool.details(), tool.usage(), tool.addedToolNames(), tool.isError(), tool.timestamp());
    }
    return message;
}
```

说明：① custom_message 目标在 `project()` 已投为 `UserMessage` ⇒ 走第一支（pi 的 custom
角色对裸串原样保留，Java 的 UserMessage 恒块列表，投影结果等价）；
② assistant/tool 的裸串经 `CustomMessageContent.toBlocks()` 归一成 text 块（§2.3⑤ 的导入兜底）；
③ **保留 usage 等全部元数据** ⇒ 压缩的 G4 陈旧界与后续用量锚点行为不变。

### 4.4 写侧：显式 null 键（`PiV3Wire.java`）

Entry 级 `@JsonInclude(NON_NULL)` 会把 `replacement:null` 整键吞掉，而 pi 视该键必填。
`encodeEntryLine` 在通用路径后补一行（与 `parentId` 同性质）：

```java
if (m.entry() instanceof Entry.ContextEdit && !target.has("replacement")) {
    target.putNull("replacement");
}
```

### 4.5 生产者改造：R2 溢出恢复省略（`PostRunCompactionCheck.java`）

替换 `:172-179` 的摘尾：

```java
// R2：置闩，把失败助手＋其工具结果以**持久 edit 行**剔除（pi :2214-2223 的
// _omitRecoveryAttempt），再 compact-and-retry。日志原文保留。
lane.overflowRecoveryAttempted = true;
RecoveryOmissions.persist(lane, message);
return compactions.runAutoCompaction(laneName, lane, "overflow", willRetry).shouldContinue();
```

新类 `harness/RecoveryOmissions.java`（≤80 行）：

```java
final class RecoveryOmissions {

    private RecoveryOmissions() {}

    /** pi _omitRecoveryAttempt：助手＋其工具结果各追加一条 replacement:null edit，随后重建副本。 */
    static void persist(LaneState lane, Message.AssistantMessage failed) {
        var toolUseIds = new java.util.HashSet<String>();
        for (var block : failed.content()) {
            if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolUseIds.add(toolUse.id());
            }
        }
        // 按对象身份在日志中定位（不靠尾部位置）；工具结果经 toolUseId 关联。
        String assistantId = null;
        var toolResultIds = new ArrayList<String>();
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            if (lane.transcript.get(i) instanceof Entry.Message m
                    && m.message() instanceof Message.ToolResultMessage tool
                    && toolUseIds.contains(tool.toolUseId())) {
                toolResultIds.add(m.id());
            }
            if (assistantId == null && lane.transcript.get(i) instanceof Entry.Message m
                    && m.message() == failed) {
                assistantId = m.id();
            }
        }
        if (assistantId != null) {
            appendOmission(lane, assistantId);
        }
        for (var id : toolResultIds) {
            appendOmission(lane, id);
        }
        // pi _refreshFinalizedContext：压缩前让 contextTokens/切点规划读到剔除后的上下文。
        HarnessUtils.rebuildLaneMessages(lane);
    }

    private static void appendOmission(LaneState lane, String targetId) {
        lane.appendEntry((seq, parentId) -> new Entry.ContextEdit(
            UUID.randomUUID().toString(), seq, parentId, Instant.now(),
            targetId, null));
    }
}
```

线程模型：post-run 检查在运行循环内、宿主线程调用（`PiLaneEngine:212`），追加走车道监视器
（D6 锁点）；随后压缩在同一线程整体替换日志，无新增写者。**不用 `appendDeferredEntry`**：
这些条目紧接着由压缩整体替换承接、随本次 run 的写链落盘，不单独标 deferred。

**R4（不移植投影估算失效算法）**：pi 还有一套 edit 失效旧 usage 的估算
（`compaction.ts:245-254` 的 `latestInvalidatingEntryIndex`、`:836-855` 的恢复后缀判据），
因为扩展可以在**不压缩**的情况下产 edit。本仓 edit 的唯一生产者**紧贴一次自动压缩**：
压缩重建副本后，G4 陈旧界（`:150-153`）挡住压缩前 usage、用量锚点时间校验（T1）接住
错误/零值响应 ⇒ 行为面等价。残余差异：`CompactionService.findCutPoint` 按**原始**字符累加
（pi 按投影后字符），当失败助手文本为空（`estimateTokens` 折算 0）时切点可能落在它前面而非
它上面 —— 模型上下文结果相同（edit 照样剔除），仅摘要前缀内容略有不同。登记新 B 项。

### 4.6 TUI（`ChatMessage.java`）

```java
case Entry.ContextEdit edit -> null;   // 默认视图隐藏（pi tree-selector :360-362）
```

本仓 TUI 无 pi 那棵可搜索会话树，故只做隐藏；未来会话树落地时补 `[context omit: …]` 标记渲染。

## 5. 测试与证据计划

RED 先行，每步亲眼看红。

### 5.1 新增测试

`pi-java-agent-core/src/test/.../session/jsonl/PiV3ContextEditEntryTest.java`：

1. `loadsAPiAuthoredOmissionRow` —— pi 形 omit 行：按字段名断言 `type/targetId/replacement==null/parentId`；
2. `loadsAPiAuthoredReplacementRow` —— 含 assistant 裸串内容的行（导入形）；
3. `writesOmissionAsANativePiRow` —— 驱动真实 repository：读文件断言
   `type=="context_edit"`、**`replacement` 键在且为 null**、无 `seq`、无 `customType`、
   ISO 时间戳、`lane` 扩展键；reload 后逐字段相等。

`ContextEntriesTest`（同文件追加用例）：

4. `omitsTheTargetFromProjectedMessages` —— user/assistant/tool 三目标 omit；
5. `replacesOnlyContentAndKeepsMetadata` —— 断言 usage/timestamp 原样、content 已换；
6. `letsTheLatestEditWin` —— 同目标 replace→omit→replace；
7. `normalizesImportedStringContentForAssistantAndTool` —— 投影出 text 块数组；
8. `ignoresAnEditWhoseTargetIsNotProjected` —— 孤儿 edit；
9. `appliesAPostCompactionEditToARetainedEntry` —— 压缩后 edit 命中保留条目。

### 5.2 红灯预期（按实施序）

| 步 | 红 |
|---|---|
| 加变体＋codec 前先写 1/2 | 编译红（类型不存在）——随后行加载现报 `unknown entry type context_edit`（行为红） |
| 投影 4-9：变体已加、投影逻辑未加 | omit 用例红：目标仍在消息列表；replace 用例红：content 未换 |
| 写侧 3：null 键强制未加 | 红：`replacement` 键缺席 |

### 5.3 变异探针（写在实现旁、红灯不充分处）

- **M1**：`ENTRY_TYPES` 去掉 `context_edit` ⇒ 恰 1/2 红 `unknown entry type context_edit`；
- **M2**：`applyEdit` 首行 `replacement==null → null` 改成返回原消息 ⇒ 用例 4 红；
- **M3**：null 键强制语句删掉 ⇒ 恰写侧用例 3 红（钉死必填键）；
- 每次变异后 grep 复核落地（CRLF/锚点失效前科）。

### 5.4 既有用例调整

- `PostRunCompactionCheckTest` R2 断言（`:216` 附近）：由「置闩＋尾删除」改为
  断言 edit 条目（目标 id＝失败助手＋工具结果、`replacement==null`）、副本已重建；
- 全 reactor `mvn -o clean verify`（带 `-am`），模块汇总行取数；TUI 随变体落地同步编译。

## 6. 实施步骤与提交

1. `feat(agent-core): read pi context_edit entries` —— 变体＋`EntryJsonCodec`＋ENTRY_TYPES
   ＋TUI `ChatMessage` case（编译要求）＋测试 1/2（先红）；
2. `feat(agent-core): apply context edits when projecting session messages` ——
   `ContextEntries` 改造＋测试 4-9（先红）＋M1/M2；
3. `feat(agent-core): persist overflow recovery omissions as context edits` ——
   `RecoveryOmissions`＋R2 改造＋null 键强制＋测试 3/M3＋`PostRunCompactionCheckTest` 调整；
4. 闭环回填：本文 banner 与 §12、`docs/12` D3 状态、`docs/05`（B61 销号＋新登记项）。

每步保证 reactor 绿；`git add <显式路径>`，commit 末尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## 7. 明确不做

- 扩展会话边界草稿（`SessionBoundaryDraft` / `ContextEditEntryDraft`）—— 扩展运行器不存在，
  与 CacheWarmer、export-html 同为上游设施缺席；
- pi 的投影式 token 估算 edit 失效全套（§4.5 R4）—— 唯一生产者紧贴压缩，等价路径已覆盖；
- 旧行迁移：本仓从未写过 `context_edit`，无 legacy 形状；
- SQLite schema/migration：无改动。

## 8. 验收

1. pi 锚点写的 omit/replace 行（含 assistant 裸串导入形）本仓可读、投影结果与 pi 规则字面一致；
2. 本仓写的 omit 行经 §5.3 M3 钉死 `replacement:null` 在键；
3. 溢出恢复后：失败助手与其工具结果在**重新 resume** 后仍不进模型上下文（§3.2 分歧闭合）；
4. 全 reactor `mvn -o clean verify` 14/14（含 checkstyle/spotbugs）；
5. `docs/05`：B61 全项闭合；如实登记残余项。

仍未做、不随本包闭合：真 pi 端到端互读（B60/B61 销号的硬判据仍需一次真跑）、B167、B168。

## 9. pi ↔ Java 键对照（`context_edit` 行）

| pi 键 | Java 分量 | 备注 |
|---|---|---|
| `type` | `@JsonSubTypes name` | `"context_edit"` |
| `id` | `id` | |
| `parentId` | `parentId` | 必填，根为 null |
| `timestamp` | `timestamp` | ISO-8601 |
| `targetId` | `targetId` | 目标条目 id |
| `replacement` | `replacement`（null）/ `Replacement.content` | **必填键，可显式 null** |
| `replacement.content`（串） | `CustomMessageContent.Text` | assistant/tool 投影时归一 |
| `replacement.content`（数组） | `CustomMessageContent.Blocks` | ContentBlock 多态 |
| —— | `lane`（扩展键） | pi 裸 `JSON.parse` 容忍未知键 |

<!-- §10–11 预留：实施期裁决与证据回填 -->

## 12. 闭环记录（2026-10-04）

### 12.1 实施期裁决与被实测纠正的认知

| # | 事项 | 结论 |
|---|---|---|
| 1 | error 助手为何也要 edit？ | **它本就被 stopReason 投影过滤**（`ContextEntries.project` 的 NON_PROJECTED_STOP_REASONS）；edit 真正额外剔除的是**工具结果**（无 stopReason 过滤、照常投影，可能携带巨型输出）。助手 edit 是双保险 |
| 2 | 嵌套 record 的可见性 | `ContextEdit.Replacement` 初版漏 `public` ⇒ 包外测试不可见（嵌套 record 默认包私）。已修 |
| 3 | **引用身份 ≠ record 值相等** | 初版 `lane.messages.contains(failed)` 对分量相同的两条 error 助手返回 true（record equals），误抛「no source entry」。pi 的 JS `includes` 是对象身份 ⇒ 改为 `inWorkingCopy` 引用扫描。教训：把 JS 身份语义翻成 Java 时，record 的值相等是必须主动绕开的坑 |
| 4 | `CustomMessageContent` 是顶层型 | 不是 `Entry.CustomMessageContent`；测试按 `Entry.` 限定编译红，改 import |
| 5 | 旧 R2 测试的夹具形状 | 旧测试给 `checkAfterRun` 传的是**全新实例**（既不在 transcript 也不在工作副本）⇒ 按 pi 语义应静默跳过。保留为负向用例；生产形状（同一实例）另立两条用例钉 edit |

### 12.2 证据

| 面 | 先红 | 变异探针 |
|---|---|---|
| 读侧 | 2 条用例**先红**，恰报 `has unknown entry type context_edit`（整会话不可读） | **M1**（ENTRY_TYPES 去掉 `context_edit`）⇒ **恰 2 红**，消息正是 `unknown entry type context_edit` |
| 投影 | 6 条新用例中 **5 红**（孤儿 edit 一条天然已绿：无目标可作用） | **M2**（omit 分支 `return null` → `return message`）⇒ **恰 1 红**，只红 `omitsTheTargetFromProjectedMessages` |
| 写侧 | `writesOmissionAsANativePiRow` 写在实现旁、无红灯可看 | **M3**（删掉 null 键强制）⇒ **恰 1 红**，只红写侧那条，钉死 `replacement` 显式 null 必须在键 |
| R2 | `PostRunCompactionCheckTest` 旧断言（摘尾）改为 3 条用例：同实例 edit 全链路、edit 单独验证、陌生实例静默跳过 | 全部按预期；每次变异 grep/perl 复核落地 |

测试总数：`ContextEntriesTest` 23（＋6）、`PiV3ContextEditEntryTest` 3（新）、
`PostRunCompactionCheckTest` 15（＋2，旧 R2 用例改写）。

全 reactor `mvn -o clean verify` **BUILD SUCCESS 14/14**（含 checkstyle/spotbugs）：
telemetry / ai 47 s / agent-core 24 s / sqlite / coding-agent / TUI 3:18 / web 1:09 / 其余。

### 12.3 提交

```
c7d7e12 feat(agent-core): read pi context_edit entries
3facd18 feat(agent-core): apply context edits when projecting session messages
9283efa feat(agent-core): persist overflow recovery omissions as context edits
```

### 12.4 docs/05 变更

- **B60/B61 不销号**：补 D3 落地注记 —— pi 11 个条目型类型面全部可读，唯一剩余是真 pi 互读；
- 新增 **B169**（切点按原始字符不按投影，空 content 角可能差一条目边界、上下文结果相同）、
  **B170**（定位仅身份扫描、无投影下标兜底，接线变更会硬失败不静默）；
- §1 台账新增 2026-10-04 包 13 收口说明（表内行 +2）；CRLF 826/826 完好。

### 12.5 仍未做（沿用，不随本包闭合）

- **真 pi 端到端互读一次都没跑过** —— B60/B61 销号的唯一硬判据；
- B167（判别值两份拷贝）、B168（runId 记录查询不命中 usage）、B169、B170 均登记未修。
