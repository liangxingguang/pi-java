# 51 - 包 A3：工具增删状态线（生产者 ＋ 重放后半 ＋ 车道原生渲染）

**状态：设计待审核** —— 审核通过前不写任何实现代码（`docs/00 §3` 步骤 3、`docs/48 §4` 审核门）。
**基准：** pi-java `51aaddc`（包 B87/B88 文档收尾）；pi `3390bd936`（2026-09-20，已核 `git rev-parse HEAD` ＝ 该提交、`git status` 空）
**设计期实测：** ✅ pi 的 oracle 已跑通 —— `transcript-tool-changes.test.ts` **11/11 绿**（同族 `system-message-replay` ＋ `providers` 另 **35/35 绿**），逐字输出与补数据面的过程见 §7.2。Java 侧现状未跑（§7.3）。
**上游：** [`32-open-items-register.md`](32-open-items-register.md) **B67**（系统消息 ＋ 工具状态增量）/ **B68**（`declareToolChanges`）；[`48-ai-next-work-items-design.md`](48-ai-next-work-items-design.md) §2 `A-11`、§5 实施表第三行
**下游/相邻：** A4（`sections` 构建/替换/差分）、A7（`Model.compat` 扩展与目录探测）、B87④（TUI/web 认系统消息）、`docs/50 §10 L-A`（`constrainedSampling`/grammar 工具）、`docs/49 §10 L-I`（B90 `eager_input_streaming`）
**参考：** [`41-gap-inventory.md`](41-gap-inventory.md):77-79、[`49-ai-provider-transcript-migration-design.md`](49-ai-provider-transcript-migration-design.md) §9 R4 / §10 L-D / §12、[`50-b87-b88-readback-and-responses-tools-design.md`](50-b87-b88-readback-and-responses-tools-design.md) §12

---

## 1. 范围

### 1.1 这一包解决什么

A1 落了 `Message.SystemMessage` 的形状，A2 让六条车道改从 transcript 取系统提示与工具，B87①② 让系统消息**能回读**、`toolsAdded` **按其形状落线**。但到今天为止：

- **没有任何生产者** —— `Message.SystemMessage` 在生产里只有两条来源：`ContextNormalizer` 造出的前导消息（A2，工具固定），以及手写。会话中途**从不**产生工具变更；
- **重放后半没移植** —— `Transcripts` 只有「读」的一半（`getCurrentTools` 等六个），`getToolStateChanges` / `declarationsEqual` / `hasToolRedefinitions` / `hasNonAdditiveToolChanges` / `resolveTranscriptTools` / `getDeclaredTools` 全缺（`Transcripts.java:20-25` 的类 javadoc 明文写着它们是 A3 的）；
- **车道原生渲染没落** —— 四条车道各有一道 `requireOnlyLeadingSystemMessage` 守卫（`AnthropicRequestBuilder:64`、`MistralConversationsApi:318`、`OpenAICompletionsMessageConverter:102`、`ResponsesMessageConverter:142`），中途系统消息一律**响亮抛**；
- **`Entry.ActiveToolsChange` 是只读死形状**（§3 F10）。

本包把这条线**从生产到线格补齐**：模型侧声明工具增删（生产者），重放函数的另一半，以及各车道把「增删」按自己的协议发出去。

### 1.2 本包不做什么

| 不做 | 归谁 | 理由 |
|---|---|---|
| `sections` 的构建/替换/差分（`diffSystemPromptSections`） | **A4** | `docs/48 §5` 是**独立一行**；A3 只搬工具字段，`content`/`sections` 的渲染 helper 在 A2 已落地（`MessageTexts:54/:75`） |
| `constrainedSampling` / grammar 工具 | `docs/50 §10 L-A` | 它改的是 `toToolDeclaration` 的第 4 个键与工具转换的入参，与本包**碰同一个函数**（§9 R5） |
| `supportsEagerToolInputStreaming`（B90） | `docs/32` B90 | 同上：`convertTools` 的又一个形参 |
| 三个 compat 标志的**目录探测与 `models.json` 接线** | **A7** | 照 `docs/49 §9 R4` 的先例：本包只加字段与消费点 |
| TUI/web 系统消息渲染 | `docs/50 §10 L-D` | 宿主面 |
| `Entry.ActiveToolsChange` 的 TUI 渲染 | 随 §9 R2 的裁决 | pi 主线不发射它（§2 P14） |

### 1.3 与 A2 的关系

A2 落的是**消费**侧：车道从 transcript 读系统提示与工具。A3 落的是**生产**侧与**线格**侧。两者的接缝是 `Transcripts.getCurrentTools`（A2 已落，被 A3 的 `resolveTranscriptTools` 复用）与 `Message.SystemMessage`（A1 已落）。

---

## 2. 命题表（pi 侧，均已实读；引用自锚点 `3390bd936`）

### P1 —— `SystemMessage` 的工具字段

`packages/ai/src/types.ts:491-507`：

```ts
export interface SystemMessage {
	role: "system";
	content: string | TextContent[];
	sections?: Record<string, string | null>;
	toolsAdded?: Tool[];          // 完整定义，语义是 upsert
	toolsRemoved?: ToolReference[];
	timestamp: number;
}
```

`toolsAdded` 装的是**完整定义**（不是名字），`toolsRemoved` 只装 `{name}`（`types.ts:607-609`）。类注释 `:486-489` 把语义写死：「重放每条系统消息 ⇒ 得到当前的提示与工具」。

### P2 —— `Tool` 的形状

`types.ts:600-605`：`{name, description, parameters, constrainedSampling?}`。这**正是** B87② 收敛后的 `ToolDeclaration` 三键（`Transcripts.java:57-59`），差别只在 `constrainedSampling`（L-A）。

### P3 —— 重放后半（`packages/ai/src/utils/transcript.ts`）

| 函数 | 行 | 语义（逐字） |
|---|---|---|
| `getCurrentTools` | `:57-66` | 逐条系统消息，**先 `toolsRemoved` 后 `toolsAdded`**；用 `Map<name, Tool>` ⇒ 重声明**保留首次位置、换值**（A2 已落，`Transcripts.java:82-96`） |
| `getDeclaredTools` | `:169-177` | 所有出现过的 `toolsAdded`，**首次声明序**，重声明换值不改位置 |
| `hasToolRedefinitions` | `:179-194` | 同一名字被声明两次且 `declarationsEqual` 为假 ⇒ `true` |
| `hasNonAdditiveToolChanges` | `:196-208` | 任何 `toolsRemoved` **或**任何同名再次 `toolsAdded` ⇒ `true`（即「加」不足以重放这段历史） |
| `resolveTranscriptTools` | `:220-234` | `anchorsAdditions = supportsToolAdditions && !hasNonAdditiveToolChanges(messages)`；`requestTools = anchorsAdditions ? (getInitialSystemMessage(messages)?.toolsAdded ?? []) : getCurrentTools(messages)` |

⚠️ `resolveTranscriptTools` 的 `requestTools` 在 `anchorsAdditions` 为真时**只是前导消息声明的那一份**，后续增量**不在请求级 tools 字段里** —— 它们靠各车道的「就地锚定」发出去（P8/P9/P10）。

### P4 —— `getToolStateChanges`（`:149-167`）

```ts
toolsAdded   = current.filter(t => { const p = previousByName.get(t.name);
                  return p === undefined || !declarationsEqual(p, t); }).map(toToolDeclaration)
toolsRemoved = previous.filter(t => { const c = currentByName.get(t.name);
                  return c === undefined || !declarationsEqual(t, c); }).map(t => ({ name: t.name }))
```

两条纪律：**改变了定义 ＝ 先删后加**（同名工具同时出现在两侧）；两侧的**输出顺序**分别跟 `current` / `previous`。

### P5 —— `toToolDeclaration` 与 `declarationsEqual`（`:122-142`）

`toToolDeclaration` 剥成 `{name, description, parameters, constrainedSampling?}`，其中 `parameters` 走 `JSON.parse(JSON.stringify(...))` 往返。`declarationsEqual` 判据是

```ts
JSON.stringify(toToolDeclaration(left)) === JSON.stringify(toToolDeclaration(right))
```

pi 的注释（`:132-139`）说明了为什么用序列化比较：往返会**丢掉 typebox 的 symbol 键与 `undefined` 字段**，并且让两侧键序一致 ⇒ 精确且不引深比较依赖。

### P6 —— 生产者 `declareToolChanges`（`packages/agent/src/agent-loop.ts:281-333`）

调用点在 `:209`，**内层循环每轮无条件调用**：

```ts
for (const message of declareToolChanges(currentContext, [...preparedMessages, ...pendingMessages])) {
	await emit({ type: "message_start", message });
	await emit({ type: "message_end", message });
	currentContext.messages.push(message);
	newMessages.push(message);
}
pendingMessages = [];
```

函数体四条纪律：

1. **待合并的锚点是 pending 里最后一条 system 消息**（`:293-299`，从后往前找）；
2. **对照基准是「已提交转录 ＋ 把该消息的工具字段剥掉」**（`:300-308`）—— 即 pending 消息的旧工具字段是**意图**、不是事实，真值取 `getCurrentTools([...context.messages, ...baseline])` 与 `context.tools` 的差；
3. **有 pending 系统消息 ⇒ 把 `changes` 写回它**（`:311-315`）；`changes` 为空**且**它自己也没声明过工具 ⇒ 原对象原样返回（保留调用方的对象身份）；
4. **没有 pending ⇒ 只在有变化时新建一条**，内容为空、时间戳为 `Date.now()`，插在**第一条非 system 消息之前**（`:316-320`）。

`withToolChanges`（`:325-333`）：先解构丢掉 `toolsAdded`/`toolsRemoved`，再按 `length > 0` 条件写回 ⇒ **空列表省略键**。

### P7 —— 产物的持久化

产出的系统消息走 `message_start`/`message_end`，会话层在 `agent-session.ts:707-725` 把 `role` 为 `system`/`user`/`assistant`/`toolResult` 的消息一律 `appendMessage` ⇒ **落的是一条普通消息 entry**，不是专用 entry 类型。恢复时 `_restoreToolsFromTranscript`（`:1192-1204`）从 `getCurrentSystemMessage(...).toolsAdded` 反推活跃工具集。

### P8 —— Anthropic 的原生渲染

判据（`anthropic-messages.ts:1050-1055`）：

```ts
const initialTools = initialSystemMessage?.toolsAdded ?? [];
const nativeToolChanges = compat.supportsMidConvoSystemMessages
	&& compat.supportsMidConvoToolChanges
	&& initialTools.length > 0            // Anthropic 拒绝「全部 deferred」的工具表
	&& !hasToolRedefinitions(context.messages);   // 块只按名引用，表达不了重定义
```

四处后果：

1. **beta 头**：`nativeToolChanges` ⇒ 推 `mid-conversation-tool-changes-2026-07-01`（`:186`、`:1031`）；
2. **工具表**（`:1115-1137`）：初始工具**保持活跃**（缓存断点挂在最后一个上）＋ `__pi_deferred_placeholder__`（`defer_loading: true`）＋ 所有后续声明（`getDeclaredTools` 去掉初始名，`defer_loading: true`）。注释明说意图是「请求级列表**只增不减**，缓存前缀跨工具变更保持完整」；被删的工具**仍然声明**，靠 `tool_removal` 块撤回。占位符常量在 `:195-200`；
3. **消息侧**（`:1250-1270`）：中途系统消息**不就地发**，而是攒进 `pendingSystemMessages`，在**下一条 assistant 消息之前**（`:1309-1310`）或转录末尾（`:1397`）刷出。块序为 `[text?, ...tool_removal, ...tool_addition]`。注释（`:1236-1240`）解释原因：Anthropic 要求 `tool_result` 紧跟 `tool_use`，夹一条系统消息会被拒 ⇒ **转录里排在 user 消息前的更新，线上落到它之后**；
4. **缓存断点**（`:1405-1412`）：`cache_control` 可以落在最后一块是 `tool_addition`/`tool_removal` 的时候。

### P9 —— OpenAI Completions 的原生渲染

判据（`openai-completions.ts:807-810` 与 `:1221-1224`，两处同式）：

```ts
const transcriptTools = resolveTranscriptTools(context.messages,
	compat.supportsMidConvoSystemMessages === true && compat.supportsMidConvoToolAdditions === true);
```

渲染（`:1240-1252`）：`i > 0` 且 `anchorsAdditions` 时，本条的 `toolsAdded` 先落成一条 **Kimi 形状**的 `{role:"system", tools:[...]}` 消息，**再**落 `renderSystemMessageUpdate` 的文本消息 ⇒ 一条系统消息可能产出**两条**线上消息。`requestTools` 进请求级 `tools`（`:848-849`）。

### P10 —— OpenAI Responses 的原生渲染

判据（`openai-responses-shared.ts:178-181`）：`supportsAdditionalTools || supportsToolSearch`。

锚定（`:182-210`）—— `anchorsAdditions` 时：

- 若 `supportsAdditionalTools`：落一条 `{type:"additional_tools", role:"developer", tools:[...]}`；
- 否则若 `supportsToolSearch`：落 `tool_search_call` + `tool_search_output` **一对**，`execution:"client"`、`status:"completed"`，`call_id` 由种子与名字列表哈希确定性生成（`pi_tool_load_${shortHash(seed:names)}`）。

文本渲染（`:217-226`）：**前导**走 `getSystemMessageText`，其余走 `renderSystemMessageUpdate`；非前导且 `supportsAdditionalTools` 为真时**先**锚定再落文本。⚠️ `role` 取 `instructionRole = model.reasoning && compat.supportsDeveloperRole !== false ? "developer" : "system"`（`:212-213`）—— 这条与 A-07 的 developer role 缺口同一处。

### P11 —— Mistral

`mistral-conversations.ts:786-791`：`index === 0` 走 `getSystemMessageText`，其余走 `renderSystemMessageUpdate` 落的仍是 `role:"system"`。**没有**工具锚定（`MistralConversationsCompat` 只有 `supportsMidConvoSystemMessages` 一个标志，`types.ts:849-852`）。

### P12 —— Google

`resolveTranscript` 之外无原生分支 ⇒ 一律折叠（Java 侧 `GoogleGenerativeAiApi:111` 直接 `collapseSystemMessages`，与 pi 同）。

### P13 —— compat 标志的分布

| 车道 | 中途系统消息 | 工具增删 | 位置 |
|---|---|---|---|
| Completions | `supportsMidConvoSystemMessages` | `supportsMidConvoToolAdditions` | `types.ts:731` / `:733` |
| Responses | `supportsMidConvoSystemMessages` | `supportsAdditionalTools` / `supportsToolSearch`（**两个独立机制**） | `:758` / `:768` / `:770` |
| Anthropic | `supportsMidConvoSystemMessages` | `supportsMidConvoToolChanges`（注释明写 *Requires* 前者） | `:830` / `:832` |
| Mistral | `supportsMidConvoSystemMessages` | — | `:851` |

四个字段的缺省都是 `false`，注释统一写着「由生成的模型目录为有能力的模型开启」。

### P14 —— `active_tools_change` 不是主线的形状

`git grep active_tools_change` 在 `packages/agent/src` ＋ `packages/coding-agent/src`（**排除 `harness/`**）**零命中**；它只活在 `packages/agent/src/harness/session/jsonl/legacy-v3.ts`（v3 会话导入器）里。`docs/harness.md:1455` 明写这三个 legacy 节点「disappear from the tree」。⇒ 按裁决 R5（harness 独有物不在对齐范围），**pi 主线的工具状态线就是系统消息**。

---

## 3. 顺带发现（Java 侧，全部为今天可核实的状态）

| # | 发现 | 证据 |
|---|---|---|
| **F1** | **重放后半全缺**：`Transcripts` 只有读的一半 | `Transcripts.java:20-25` 类 javadoc 自己列了缺的六个函数名 |
| **F2** | **生产者不存在，且现有代码形状与 pi 不符**：`PiLoopRunner.runLoop` 只在 `!pending.isEmpty()` 时才注入消息，而 pi **每轮无条件**调 `declareToolChanges` | `PiLoopRunner.java:83-92` vs `agent-loop.ts:208-215` |
| **F3** | **投影要跨模块**：`Context.tools` 是 `List<AgentTool<?,?>>`（agent-core），`ToolDeclaration` 在 ai；**依赖方向 ai ← agent-core ⇒ `AgentTool → ToolDeclaration` 的投影只能落在 agent-core**。今天唯一的投影是 `ToolRegistry.definitionsOf`（出 7 组件的 `ToolDefinition`，`:114-120`） | `Context.java:44`、`ToolDeclaration.java`、`ToolRegistry.java:114-120` |
| **F4** | **四道守卫要按车道拆**：`requireOnlyLeadingSystemMessage` 在 Anthropic/Mistral/Completions/Responses 各一处；Google 不调（它自己 collapse） | 四处调用点见 §1.1 |
| **F5** | ⚠️ **原生渲染在生产上零可达**：`supportsMidConvoSystemMessages` 在 `pi-java-ai` 的**生产代码里只被读、无任何写入点**（无 `CompatDef`/`models.json` 字段，A7 未做）⇒ 每一条车道今天**恒走折叠支**。而折叠支下 `supportsToolAdditions` 恒假 ⇒ `anchorsAdditions` 恒假 ⇒ **`resolveTranscriptTools` 的 `requestTools` 恒等于 Java 今天已经在发的 `getCurrentTools(...)`** | `grep -rn supportsMidConvoSystemMessages pi-java-ai/src/main` 只命中 `Transcripts` ＋ `ModelCompat` |
| **F6** | ⚠️ **生产里没人改工具集**：`setActiveTools` 除自身与 web 的**读**以外零调用者 ⇒ 工具集在一次会话里恒定 | `grep -rn "setActiveTools\|getActiveTools" --include=*.java . \| grep -v /test/` ⇒ `AgentHarness:465/:470`、`WebDispatcher:206` |
| **F7** | **`declarationsEqual` 的判据精度不同**：pi 比 `JSON.stringify`（**键序敏感**）；Java 若用 record/`Map.equals`（`Map.copyOf` 后无序但内容比较）是**键序不敏感**的 ⇒ 两侧只有在「仅键序不同」时结论分叉 | `transcript.ts:140-142` |
| **F8** | `ModelCompat` 缺本包要的三个标志 | `ModelCompat.java:94-98` |
| **F9** | **`NextTurnUpdate` 缺 pi 的 `messages` 字段**（`types.ts:146-147`）—— 那是会话层把「分段差分系统消息」送进 `declareToolChanges` 的通道（`agent-session.ts:603-611`）。Java 只有 `context` | `PiLoop.java:198` vs `types.ts:143-152` |
| **F10** | `Entry.ActiveToolsChange` 是**只读死形状**：生产无生产者，pi 主线也不发射（P14） | `Entry.java:121-131`、`EntryJsonCodec:42`、`ContextEntries` 不投影它 |

---

## 4. 设计

### 4.1 ai 侧：`Transcripts` 补重放后半（F1）

逐字移植 P3/P4/P5 的六个函数 ＋ 两个结果类型：

```java
public record ToolStateChanges(List<ToolDeclaration> toolsAdded, List<ToolReference> toolsRemoved) {}
public record TranscriptTools(List<ToolDefinition> requestTools, boolean anchorsAdditions) {}

public static List<ToolDefinition> getDeclaredTools(List<Message> messages)
public static boolean hasToolRedefinitions(List<Message> messages)
public static boolean hasNonAdditiveToolChanges(List<Message> messages)
public static boolean declarationsEqual(ToolDefinition left, ToolDefinition right)
public static ToolStateChanges getToolStateChanges(List<ToolDefinition> previous, List<ToolDefinition> current)
public static TranscriptTools resolveTranscriptTools(List<Message> messages, boolean supportsToolAdditions)
```

三处要小心的语义：

- **`getDeclaredTools` 与 `getCurrentTools` 不同**：前者**不看 `toolsRemoved`**（「历史上声明过什么」），后者看。两个都要留，用途不同（P8 用前者算 deferred 列表，P10 用后者算出参）。
- **`declarationsEqual` 用「归一后的序列化」比**（F7）：把 `ToolDeclaration` 按固定键序写成 JSON 再比字符串，与 pi 同判据。这需要一个稳定的三键序列化 —— 复用 `SessionJson`/`PiMessagesApi` 已有的那套形状，不另造。
- **`getToolStateChanges` 的顺序**：`toolsAdded` 跟 `current` 序、`toolsRemoved` 跟 `previous` 序（P4）。用 `List` 不用 `Set`。

### 4.2 ai 侧：`ModelCompat` 加四个标志（F8、P13）

`supportsMidConvoToolAdditions`、`supportsAdditionalTools`、`supportsToolSearch`、`supportsMidConvoToolChanges` —— **四个**（Responses 是两个独立机制）。全部 `Boolean`、`null` ≙ pi 的 `undefined` ≙ 假，并补便捷构造器与 `NONE`。探测与目录接线归 A7（`docs/49 §9 R4` 先例）。

### 4.3 agent-core 侧：生产者（F2、F3、F9）

**(a) 投影**（agent-core）：`AgentTool → ToolDeclaration`，与 `ToolRegistry.definitionsOf` **共用同一个 `AgentTool` 读取点**，避免两条投影漂移（F3）。

**(b) `declareToolChanges`**：移植 `agent-loop.ts:291-321` ＋ `withToolChanges`（`:326-333`）。落点 `PiLoopRunner.runLoop` 的 83-92：

```java
// 现在（只在有 pending 时才注入）
if (!pending.isEmpty()) { for (var m : pending) { emit…; messages.add(m); newMessages.add(m); } pending.clear(); }
```

改成 pi 的无条件形状：

```java
var toProcess = declareToolChanges(context, concat(prepared(), pending));
for (var message : toProcess) { emit.emit(new Event.MessageStart(message));
    emit.emit(new Event.MessageEnd(message)); messages.add(message); newMessages.add(message); }
pending.clear();
```

**(c) `NextTurnUpdate.messages`（F9）**：pi 的 `preparedMessages` 通道必须补，否则 `declareToolChanges` 的「合并进 pending 系统消息」那一支在 Java 上**结构性不可达**（`:311-315`）。补法是给 `NextTurnUpdate` 加第 4 个组件 `List<Message> messages`（`null` ＝ 不改），与 pi 的 `messages?` 一一对应。

### 4.4 车道原生渲染（P8–P11）

| 车道 | 要落的东西 |
|---|---|
| Anthropic | ① `nativeToolChanges` 四条件门（`hasToolRedefinitions` 是其中之一）；② beta 头常量；③ 工具表三段式（初始 ＋ `__pi_deferred_placeholder__` ＋ deferred 的后续声明）；④ `pendingSystemMessages` 攒批 ＋ 在 assistant 前/末尾刷出；⑤ 块序 `[text?, removals, additions]`；⑥ 缓存断点允许落在 `tool_addition`/`tool_removal` 上；⑦ 删掉 `:64` 的守卫 |
| Completions | ① 门 `supportsMidConvoSystemMessages && supportsMidConvoToolAdditions`；② Kimi 形状的 `{role:"system", tools}` 先于文本；③ `i > 0` 的文本改走 `renderSystemMessageUpdate`；④ `requestTools` 取代 `getCurrentTools`；⑤ 删掉 `:102` 的守卫 |
| Responses | ① 门 `supportsAdditionalTools \|\| supportsToolSearch`；② `additional_tools` 项 **或** `tool_search_call`/`tool_search_output` 对（含确定性 `call_id` 的哈希）；③ 非前导文本走 `renderSystemMessageUpdate`；④ `requestTools`；⑤ 删掉 `:142` 的守卫（`apiName` 参数随之失去唯一用途，要一并清） |
| Mistral | ① `index > 0` 的文本改走 `renderSystemMessageUpdate`；② 删掉 `:318` 的守卫。**无工具锚定** |
| Google | 不动 |

⚠️ `renderSystemMessageUpdate` 在 A2 已落地（`MessageTexts:75`），本包只改**调用点**。

### 4.5 §9 之外要提防的两处

1. **`llm.request` 的 `toolCount()`**：`PiLaneSink` 记账读的是活跃工具表。工具集中途变化后该读 `getCurrentTools` 还是 `context.tools`，本包**必须实测**（登记在 §9 R6）。
2. **压缩/重建路径**：`PiLaneEngine.rebuiltContext`（`:367-370`）在压缩后整体换 `Context`；新 `Context` 的 `tools` 必须仍是当前活跃集，否则 `declareToolChanges` 会在压缩后**误报一批增删**。

---

## 5. 三步拆分

| 步 | 内容 | 先红来源 | 依赖 |
|---|---|---|---|
| **A3a** | `Transcripts` 重放后半 ＋ `ModelCompat` 四字段 | 编译失败（类型不存在）＋ pi oracle 的纯函数部分 | — |
| **A3b** | 生产者：投影 ＋ `declareToolChanges` ＋ `NextTurnUpdate.messages` | **`git stash push -- <实现文件>` ＋ 旧代码复跑**（折叠支下必须看到「转录里多出一条系统消息、且前导消息的 `toolsAdded` 变成当前工具集」） | A3a |
| **A3c** | 四条车道的原生渲染 ＋ 删守卫 | stash 实现 ＋ 旧代码复跑（现在会**响亮抛**，红是白送的） | A3b |

⚠️ 三步的**可达性不同**：A3a/A3b 走折叠支，**生产可达**；A3c 在 A7 落地前只有测试可达（F5）。这三步要不要全做，见 §9 R1。

---

## 6. 先红与变异矩阵

| 步 | 先红（实测目标） | 变异探针 | **预测**红集（实施时按实测改写） |
|---|---|---|---|
| A3a | 编译失败（`ToolStateChanges`/`TranscriptTools` 不存在）＋ Java 端口的纯函数夹具 | M1 `hasNonAdditiveToolChanges` 去掉 `toolsRemoved` 支 ⇒ 只该红「有删除」那条；M2 `declarationsEqual` 恒 `true` ⇒ 红重定义/改变义两条；M3 `getToolStateChanges` 的 `toolsRemoved` 序改成 `current` 序 ⇒ 若夹具没专门钉顺序就是**零红**（故夹具必须钉顺序）；M4 `resolveTranscriptTools` 去掉 `!hasNonAdditiveToolChanges`；M5 `getDeclaredTools` 改成走 `getCurrentTools`（即看 removals）⇒ 应红「Anthropic deferred 列表」那条 oracle | 各 1–2 红 |
| A3b | **`git stash push -- <实现文件>` ＋ 旧代码复跑**：折叠支下必须看到「转录里多出一条系统消息」＋「前导消息的 `toolsAdded` 变成当前工具集」 | M6 `getToolStateChanges` 的两个入参**互换** ⇒ 增删方向反；M7 去掉「空列表省略键」门；M8 「插在第一条非 system 之前」改成「插在末尾」；M9 短路回 `if (!pending.isEmpty())` ⇒ 应红「无 pending 但有变化」那条 | 各恰 1 红（M9 是关键探针） |
| A3c | stash 实现 ＋ 旧代码 ⇒ 四车道**响亮抛** `UnsupportedOperationException`（4 台桩服务器零请求） | M10–M13 逐车道关掉 `nativeToolChanges`/`anchorsAdditions` 门 ⇒ 各自恰红对应的 oracle 用例 | 各恰 1–2 红 |

⚠️ 上表的红集是**预测**（照 `docs/49 §12.2`／`docs/50 §12.2` 的先例）：设计稿的预测在 A2 与 B87/B88 两包都**被实测纠正过**（M3 实测 4 红 vs 预测 1、M5 零红、M10 有牙而预测说没有）。实施时逐条对账并就地改写本表，**不许照抄预测**。

---

## 7. 实测记录

### 7.1 pi 自己的 oracle：`packages/ai/test/transcript-tool-changes.test.ts`（**11 条，已全份实读**）

这是 A3 最强的证据 —— 它用 `onPayload` 抓真出站体，逐字钉住每条车道的线格。11 条的覆盖面：

| # | 用例 | 钉住的事实 |
|---|---|---|
| 1 | `sends Anthropic updates and tool changes in native system messages` | beta 头在场；`system` 数组只有前导一条（sections 已拼进去）；`tools` 恰三段 `[base_tool(带 cache_control), __pi_deferred_placeholder__(deferred), late_tool(deferred)]`；**第一个没有 `defer_loading`、后两个没有 `cache_control`**；最后一条消息是 `role:"system"` 且块序 `[text, tool_removal, tool_addition]`；文本含 `updated guidance` / 新 rules / `Removed system prompt section "docs"`；**另有**：只有前两条消息时 `tools` 恰 `[base_tool, placeholder]` |
| 2 | `…when native tool changes cannot express the history` | 两支回退：同名重定义、无初始工具（全 deferred 被拒）⇒ 都不发 beta；`tools` 退回 `getCurrentTools`（含 `description:"changed"`）；最后一条消息只有 `text` 块 |
| 3 | `folds Anthropic updates into the system prompt without native support` | 折叠支：`system` 一条拼好的文本、`tools` 只剩 `late_tool`、`messages` 只剩 `user` |
| 4 | `requires both Anthropic capabilities for native tool changes` | 只给 `supportsMidConvoToolChanges`、不给 `supportsMidConvoSystemMessages` ⇒ **退回折叠**（两个都要） |
| 5 | `anchors OpenAI additions at their developer message` | `tools` 只有 `base_tool`；`additional_tools` 项装 `late_tool`；`developer` 文本消息两条 `["base prompt", "updated guidance"]` |
| 6 | `maps system-message additions into synthetic tool search` | `tool_search_call` 在场；`tool_search_output` 装 `late_tool` |
| 7 | `folds OpenAI updates …` | 只给 `supportsAdditionalTools`、不给 mid-convo ⇒ 折叠，`input` 恰 `[developer, user]`，首条是拼好的文本 |
| 8 | `falls back to the complete current tool state when removals are unsupported` | 有 `toolsRemoved` ⇒ `anchorsAdditions` 假 ⇒ **无** `additional_tools`，但 `developer` 仍有 2 条 |
| 9 | `anchors Kimi additions in tool-bearing system messages` | `tools` 只有 `base_tool`；带工具的 system 消息装 `late_tool`；`role:"system"` 的 `content` 序列恰 `["base prompt", undefined, "updated guidance"]`（**中间那条没文本**） |
| 10 | `keeps Kimi K2 system text inline without dynamic tool messages` | 只给 mid-convo、不给 toolAdditions ⇒ `tools` 是 `[base_tool, late_tool]`，无 `tools` 消息，system 文本两条 |
| 11 | `folds OpenAI-compatible updates …` | 折叠支：`messages` 恰 `[system, user]` |

**跑它、把 11/11 绿记在 §7.2，是实施前的第一步**（§11-1）。

### 7.2 pi oracle 运行结果 —— **11/11 绿**（2026-09-26 实测）

```
$ cd D:/workplaceForai/pi && ./node_modules/.bin/vitest run packages/ai/test/transcript-tool-changes.test.ts
 RUN  v4.1.9 D:/workplaceForai/pi
 Test Files  1 passed (1)
      Tests  11 passed (11)
   Duration  4.76s (transform 995ms, setup 0ms, import 3.94s, tests 685ms, environment 0ms)

$ ./node_modules/.bin/vitest run packages/ai/test/system-message-replay.test.ts packages/ai/test/providers.test.ts
 Test Files  2 passed (2)
      Tests  35 passed (35)
   Duration  2.32s
```

⇒ **这 11 条可以直接当 Java 夹具的 oracle**（`docs/45 §10` 那条「夹具骨架优先镜像 pi 自己的测试」再兑现一次），下面 35 条是 A2 已用过的同族。

**补数据的过程也是实测**：第一次跑不起来，逐字失败输出是

```
Error: Cannot find module './data/amazon-bedrock.json' imported from
       D:/workplaceForai/pi/packages/ai/src/providers/amazon-bedrock.models.ts
```

**根因是已登记过的老问题**：`packages/ai/src/providers/data/` 由 `generate-models.ts` 从 models.dev 生成、被 gitignore（`docs41-active-plan.md` 那条「pi 的数据面可以整体不在仓库里」）。处置＝

```
$ cd D:/workplaceForai/pi/packages/ai && node scripts/generate-models.ts --strict   # exit=0
```

跑完 `packages/ai/src/providers/data/` 有了全部 JSON（openrouter 392 个模型、azure-openai-responses 41 个等），**`git status` 仍干净**（生成物被 gitignore 吞掉）。⇒ 后续任何人跑 pi 夹具前都要先跑这一步，已写进 §11-1。

### 7.3 Java 侧现状的实测记录

⚠️ **未跑**。实施时用 `RecordingHttpServer` 取四条车道的**真出站体**，确认「带非前导系统消息」时今天确实是 `UnsupportedOperationException`（而不是静默错发）。

---

## 8. 验收 grep（实施后必须全过）

1. `grep -rn "getToolStateChanges\|resolveTranscriptTools\|hasNonAdditiveToolChanges" pi-java-ai/src/main` ⇒ 只应在 `Transcripts.java`（定义）与调用它们的车道；
2. `grep -rn "requireOnlyLeadingSystemMessage" pi-java-ai/src/main` ⇒ **只剩 Google 那条路径不需要它**（Google 不调）⇒ 本包后应为 **零命中**（函数本身删掉）；
3. `grep -rn "mid-conversation-tool-changes" pi-java-ai/src/main` ⇒ 恰 `AnthropicRequestBuilder`（或它引用的常量类）一处；
4. `grep -rn "__pi_deferred_placeholder__" pi-java-ai/src/main` ⇒ 恰一处；
5. `grep -rn "ActiveToolsChange" pi-java-agent-core/src/main` ⇒ 随 §9 R2 的裁决：若裁决「删」，应只剩 `EntryJsonCodec`（v3 读）或零命中；
6. `grep -rn "declarationsEqual" pi-java-ai/src/main` ⇒ 只应在 `Transcripts`（定义）与 `getToolStateChanges`/`hasToolRedefinitions`；

---

## 9. 裁决点（请审核时给结论）

| # | 问题 | 我的建议 | 备选 |
|---|---|---|---|
| **R1** | **本包做几步？** A3a＋b（生产可达） vs A3a＋b＋c（含原生渲染，A7 前只有测试可达，F5） | **三步全做**：`docs/48 §5` 那一行的标题就是「工具增删状态线**与 `tool_addition/removal`**」，只做 a＋b 会让那行名不副实；且 pi 的 11 条 oracle 是现成的逐字验收面，比留到 A7 再回头补便宜 | 只做 a＋b（更小、更快、零不可达代码），c 并入 A7；或 a＋b 本包、c 另立 `docs/52` |
| **R2** | **`Entry.ActiveToolsChange` 怎么办**（F10、P14） | **删**（`Entry.java` 的子类型、`EntryJsonCodec` 的读支、TUI 的渲染支、相关夹具一起删）：pi 主线不发射它，R5 也把 harness 的 legacy 导入器划出范围 ⇒ 保留它只会继续误导「工具状态线是一条 entry」 | 保留为 v3 导入的**只读**形状并加 javadoc 注明「主线不发射」 |
| **R3** | **`NextTurnUpdate.messages`（F9）本包补还是归 A4** | **本包补**：不补，`declareToolChanges` 的「合并进 pending 系统消息」支在 Java 上结构性不可达（§4.3c），而那一支正是「工具变更搭载在分段差分消息上」这条**最常见**的生产路径 | 归 A4（代价：A3 的合并支写成死码，或干脆不写该支 ⇒ 与 pi 形状不符） |
| **R4** | **`declarationsEqual` 的判据精度**（F7） | **照 pi 用「归一后序列化」比**：判据一致优先，且它顺带绕开 `Map` 序的问题 | 用 record/`Map.equals`（更宽松：仅键序不同时判「相同」⇒ 可能多发一次 `tool_addition`） |
| **R5** | **`constrainedSampling` 与 `eager_input_streaming` 要不要顺手带上**（L-A、B90） | **不带上**，但**代码里留注释指向**它们：三者改的是同一批函数（`toToolDeclaration`／`convertTools`／`convertResponsesTools`），一次改一处比三次穿同一函数便宜；且它们各自要先有自己的设计裁决（L-A 关系到 `strict` 的三态、B90 关系到 beta 头） | 本包一起做（代价：三个包的裁决混在一个 diff 里） |
| **R6** | **`llm.request` 的 `toolCount()` 读哪一份**（§4.5-1） | **实测后定**：先按「读 `getCurrentTools(context.messages)`」实现并用夹具对账；若与 pi 的遥测不符再改 —— **不许凭直觉选** | 现在直接选一份（代价：§8.28 那条「口径＝pi-java 的 adapter 比 pi 多」的教训会重演） |
| **R7** | **是否接受 §5 的三步拆分与 A3c 的「删守卫」一并做** | 接受 | 守卫保留、原生渲染只写不接线（代价：`requireOnlyLeadingSystemMessage` 与原生分支并存 ⇒ 互相矛盾的死码） |

---

## 10. 遗留登记（本包预计产出，实施后落 `docs/32`）

| 号 | 内容 | 处理 |
|---|---|---|
| **L-A** | `constrainedSampling` 与 grammar 工具（`prefer`/`require`/`type:"custom"`） | 保持 `docs/50 §10 L-A` 的登记；本包不碰（§9 R5） |
| **L-B** | `supportsEagerToolInputStreaming`（B90） | 保持 `docs/32` B90 登记；本包不碰（§9 R5） |
| **L-C** | `sections` 的构建/替换/差分 | 归 A4 |
| **L-D** | Anthropic 的 `supportsMidConvoEffort` / `insertThinkingLevelMessages` / `MID_CONVERSATION_OUTPUT_CONFIG_BETA` 一族 | 新登记：与工具的 mid-convo **同族但独立**（`anthropic-messages.ts:1029`、`:1434`），本包不碰 |
| **L-E** | Responses 的 `instructionRole`（developer vs system）与 `supportsDeveloperRole` | 既有 `docs/41 A-07`；本包只照抄那个三元式，不改其判定 |
| **L-F** | TUI/web 的系统消息渲染（B87④） | 仍归宿主面；本包后系统消息**会有真实生产者** ⇒ 它从「静默误渲染」升级为**生产可见**，优先级应上调 |

---

## 11. 本次设计的取证方式与已知不足

1. ✅ **pi oracle 已跑通：11/11 绿**（§7.2，2026-09-26 实测），同族另两份 35/35 绿。⇒ 本设计的 pi 侧事实有**三重**支撑：逐字实读的源码（带 `file:line`）、pi 自己的 11 条线格 oracle、以及运行期实测。⚠️ 但要注意口径：**oracle 钉的是 pi 的行为，不是 Java 的行为** —— Java 侧的现状（§3 F1–F10）仍然全部来自源码 grep/读，实施时要用 `RecordingHttpServer` 与一次真实 `setActiveTools` 复核。
2. ⚠️ **跑 pi 夹具前必须先补数据面**：`cd D:/workplaceForai/pi/packages/ai && node scripts/generate-models.ts --strict`（`providers/data/` 被 gitignore，不在仓库里）。不补的话任何以 `streamSimple` 为入口的 pi 夹具都会以 `Cannot find module './data/amazon-bedrock.json'` 加载失败 —— **那不是夹具坏了**。
2. **Java 侧的现状（§3 F1–F10）全部来自源码 grep/读**，未跑任何夹具。F5/F6 是**可达性判断**，实施时要用 `RecordingHttpServer` 与一次真实 `setActiveTools` 复核。
3. **锚点已核**：设计期 `cd D:\workplaceForai\pi && git rev-parse HEAD` ＝ `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`、`git status --short` 空（工作树就在锚点上，[[pi-anchor-and-drift]] 那条警告本次未发生）。本文所有 `git show 3390bd936:<path>` 与直接读工作树等价。
4. **§6 没有预测红集**：见 §6 末的说明。
5. **未做的对照**：PiMessages 车道（它不是 pi 的车道，`docs/49 §12.5`）与 Google 车道本包不动，因此没有覆盖它们。

---

## 12. 实施记录

（待实施后填写，形状照 `docs/49 §12` / `docs/50 §12`。）
