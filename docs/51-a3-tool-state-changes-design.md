# 51 - 包 A3：工具增删状态线（生产者 ＋ 重放后半 ＋ 车道原生渲染）

**状态：✅ 已闭环（2026-09-26）—— A3a `bef15fa` · A3b `8a0f67c` · A3c `1700985`/`f6665ec`/`c59db5a` ＋ docs `61d0178`/`3a088b1`。
四条车道（Anthropic／Completions／Responses／Mistral）全部原生渲染并删守卫，R1–R7 全按建议落地。
⚠️ 实施中推翻过一处设计结论（F13：一度判「两条 SDK 表达不了」，实测两条都有原始 JSON 直通，见 §12.4.1）——
读实施记录时别漏了那条自我纠正。**
**基准：** pi-java `51aaddc`（包 B87/B88 文档收尾）；pi `3390bd936`（2026-09-20，已核 `git rev-parse HEAD` ＝ 该提交、`git status` 空）
**裁决记录：** R1 三步全做 · R2 删 `Entry.ActiveToolsChange` · R3 本包补 `NextTurnUpdate.messages` · R4 用归一后序列化比 · R5 不带 `constrainedSampling`/`eager_input_streaming`（留注释指向）· R6 `toolCount()` 实测后定 · R7 接受三步拆分并删守卫
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
| **F11** | ⚠️ **设计稿漏了 pi 的「前导声明」生产者**：pi 的 `createMutableAgentState`（`agent.ts:84-85`）在会话起点把「系统提示 ＋ 工具」折成一条**前导系统消息**塞进 `state.messages`（`timestamp: 0`），`declareToolChanges` 的对照基准正是它。Java 的 `Context` 把 `systemPrompt`/`tools` 放成独立字段、工作副本里什么都没有 ⇒ 照搬 `declareToolChanges` 会让**每个会话的第一轮都宣告「新增全部工具」**，而 pi 从不产生那条消息。⇒ 必须在起手补同一条声明（落点 `PiLaneEngine.startPass`，实施记录 §12.2）。⚠️ **2026-09-26 更正：该落点已随包 A4c 删除** —— pi 的**生产**路径（`sdk.ts:368-372` 传空提示与空工具集）从不折前导消息，前导系统消息只由 `_preparePromptAndToolLoadout` 的段补丁产生；论证见 §12.2 的更正块与 `docs/52 §12.3` | pi `agent.ts:75-99`；反证：`conformance/pi-out/S2.pi.jsonl` 帧 3 起就是 user 消息，没有声明帧 |
| **F12** | ⚠️ **10 个带工具的 conformance 剧本是陈旧的**：它们生成于 pi 还没有 `runAgentLoop:109` 起手宣告的版本（帧序里没有系统消息），且 pi 侧 runner 的 `Normalizer` 没有 `system` 分支 ⇒ **重新生成会以 `TypeError: tr.content.map is not a function` 崩掉**。⇒ 已补 runner 的 system 分支、重生成全部 15 份；两侧 echo 的 `n` 改成「provider 看到的」消息数（系统消息是提示状态，不是对话），`sys=` 分量删除（pi 侧 `streamFn` 收到的是 `normalizeContext({messages})`，该字段恒为 undefined）| `conformance/pi/run.test.ts:284-330`、§12.3 |
| **F13** | ⚠️ **两条 SDK 都能用原始 JSON 构造它们类型系统里没有的形状**（这是 Anthropic 的 `tool_addition`/`tool_removal` 与 Completions 的 Kimi 形状能落地的**唯一**依据）：反序列化时把认不出的 `type` 收进 {@code _unknown}/{@code additionalProperties}，序列化**原样写出** ⇒ 非 beta 的 `MessageParam`/`ChatCompletionMessageParam` 可以承载 beta 形状。证据是 `SdkJsonEscapeHatchTest` 的**逐字节往返**（Anthropic 的 `tool_addition`、Completions 的 `{role:"system",tools:[…]}`、以及「省略 content 时线上也不出现 content」）。⚠️ **本设计稿的初版据 `javap` 的工厂方法清单判定「不可实施」—— 那是错的**：工厂只为**已知**变体生成，未知变体走的是另一条路。教训写进 §12.6 | `SdkJsonEscapeHatchTest`、§12.4 的逐条实测 |

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

⚠️ **实施结论（2026-09-26）**：R1 裁决「三步全做」，**四步全落地** —— 但中间有一次
误判与纠正：本稿一度判定「Anthropic 与 Completions 要发的形状不在 SDK 类型族里、
不可实施」，据此把两条车道登记成待裁决（§3 F13 初版、`docs/32` B92/B93）。
**实测推翻了它**：两条 SDK 都留着「未知变体直通」（原始 JSON 反序列化 ⇒ 序列化原样写出），
两者随后落地（§12.4.1）。**这不是设计稿能预见的**：§4.4 的三条行是按 pi 的 TS 形状写的，
而 TS 侧靠结构类型容忍任意键 —— 但「Java 侧的类型化参数不容忍」推出「所以要迁 beta 族」
是**跳步**，正确的下一步是先实测未知变体的往返。

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
| **L-G** | ~~Anthropic 的 beta 类型族迁移~~ **已作废** | 本包一度登记它，理由是需要 `com.anthropic.models.beta.messages.*` 才能发 `tool_addition`/`tool_removal`。**实测推翻**：非 beta 的 `MessageParam` 经原始 JSON 直通就能承载这两个块（逐字节往返见 `SdkJsonEscapeHatchTest`）⇒ **不需要迁移**，已在 `c59db5a` 落地。⚠️ 但它记下的另半句仍然成立：**B89**（顶层 `system` 的字符串 vs 块数组）仍随 A-01 `cache_control` 裁决 |
| **L-H** | ~~Completions 的 Kimi 形状手搓 body~~ **已作废** | 同上：一度判定「类型化参数完全表达不了 ⇒ 只能手搓 JSON body」，实测 `ChatCompletionMessageParam` 的未知键直通就够。已在 `c59db5a` 落地 |
| **L-I** | Responses 的 `tool_search_output` 的 `defer_loading` | 已随本包落地并实测；登记仅为备忘：`FunctionTool.deferLoading` 是**非 beta** 就有的 |
| **L-J** | **原始 JSON 直通是未文档化的 SDK 行为** | 新登记：`SdkJsonEscapeHatchTest` 钉住的通路依赖 SDK「未知变体原样往返」，那不是公开承诺 ⇒ **升级 `anthropic-java` / `openai-java` 时必须重跑那个夹具**（它红了就是通路变了，得重新设计两条车道的线格） |

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

（A3c 闭环后补完；下述两节在各自提交时即写入。）

### 12.1 步骤 A3a —— 重放后半 ＋ ModelCompat 四字段（`bef15fa`，2026-09-26）

**落地**：`ToolStateChanges` / `TranscriptTools` 两个结果类型；`Transcripts` 补
`getDeclaredTools` / `declarationsEqual` / `getToolStateChanges` / `hasToolRedefinitions` /
`hasNonAdditiveToolChanges` / `resolveTranscriptTools`；`ModelCompat` 加四个 `Boolean`
（`supportsMidConvoToolAdditions` / `supportsMidConvoToolChanges` / `supportsAdditionalTools` /
`supportsToolSearch`），并保留 5/4/3 参便捷构造器 ⇒ 既有构造点零改签。夹具照 pi 的
`system-message-replay.test.ts:110-145` 那三条纯函数用例移植（包 A2 当时跳过它们，因为函数还不存在），
另补 `getDeclaredTools` 与 `resolveTranscriptTools` 的直测。

⚠️ **`declarationsEqual` 的一处有意偏差**（F7 / R4 的落地形态）：pi 的判据是
`JSON.stringify`（**嵌套键序敏感**），而 Java 的 `inputSchema`/`parameters` 经 `Map.copyOf`
之后插入序**已经丢了**（迭代序由哈希与每 JVM 的盐决定）⇒ 根本无「键序」可比。归一因此把嵌套 Map
也**按键排序**（`ORDER_MAP_ENTRIES_BY_KEYS`）换确定性。两者只在「仅键序不同」时结论分叉，
而那个输入在 Java 侧**已经构造不出来**。

**变异红集（实测，与设计稿 §6 的预测对账后改写）**：

| 探针 | 设计稿预测 | **实测** |
|---|---|---|
| M1 `hasNonAdditiveToolChanges` 去掉 `toolsRemoved` 支 | 1 红 | **2 红** |
| M2 `declarationsEqual` 恒 `true` | 2 红 | **5 红** |
| M3 `getToolStateChanges` 的 removals 改按 `current` 序 | 「没钉顺序就是零红」 | **3 红**（夹具钉了顺序，故有牙）|
| M4 `resolveTranscriptTools` 去掉 `!hasNonAdditiveToolChanges` | 1 红 | **1 红** |
| M5 `getDeclaredTools` 改成看 removals | 1 红 | **1 红** |

先红来源与设计稿一致：**编译失败**（类型不存在）＋ 端口夹具在缺陷态无法成立。

### 12.2 步骤 A3b —— 生产者（`8a0f67c`，2026-09-26）

**落地**：`ToolChangeDeclaration`（pi `agent-loop.ts:281-333` 的四条分支 ＋
`initialDeclaration`）；`PiLoop.run` 补 `runAgentLoop:109` 的**起手宣告**；
`PiLoopRunner.runLoop` 的内层宣告改成 pi 的**无条件**形状并接上 `preparedMessages`；
`NextTurnUpdate.messages`（R3）；`PiLaneEngine.startPass` 的**前导声明**（F11）；
`Transcripts.toToolDefinition`（声明 → 全形的反向投影）。

⚠️ **F11 是本包在实施中发现的设计缺口，必须记牢**：`declareToolChanges` 的对照基准是
「转录里声明的工具」，而那条声明在 pi 里由 `createMutableAgentState`（`agent.ts:84-85`）
在会话起点写死。Java 的 `Context` 把 `systemPrompt`/`tools` 放成**独立字段**、工作副本里什么都没有
⇒ 不补这一条，**每个会话的第一轮都会宣告「新增全部工具」**，一条 pi 从不产生的系统消息
（还会被 `PiLaneSession` 落盘）。补上之后第一轮两侧相等、无事发生，与 pi 一致。

> **⚠️ 2026-09-26 更正（包 A4c，`docs/52 §12.3`）—— F11 的结论只对了一半，本包已删除该落点。**
>
> 上面那段的推理用的是 `agentLoop` 那条路（`normalizeContext(context.systemPrompt, context.tools)`
> 会折前导消息），而 **pi 的生产路径（`Agent` 类）根本不折**：`sdk.ts:368-372` 构造 agent 时传
> `systemPrompt: ""` 与 `tools: []`，`createInitialSystemMessage` 因此返回 `undefined`；
> `createContextSnapshot()`（`agent.ts:450-456`）只交 `{messages, tools}`；请求期唯一的折叠点
> `agent-loop.ts:357` 调的是 `normalizeContext({ messages: llmMessages })` —— **只传 messages**。
> ⇒ **`Context.systemPrompt` 在 pi 生产上是死字段**，前导系统消息**只有一个来源**：
> `_preparePromptAndToolLoadout` 产出的段补丁（`content: ""`），其 `toolsAdded` 由
> `declareToolChanges` 合并进去。
>
> ⇒ 包 A4c 按 R4 **删除**了 `PiLaneEngine.ensureInitialDeclaration` 与
> `ToolChangeDeclaration.initialDeclaration`，改由段补丁承担同一职责。A3 当时的实测证据
> （conformance 金标里有声明帧）**仍然成立** —— 那些帧来自 `declareToolChanges` 自己新建那条
> 系统消息，与「种子」无关；F12 的语料再生也没有推翻它。反过来说，A3 的种子让 java 的会话文件
> **比 pi 多一条**（`content` 载提示文本而不是 `sections`），这正是 A4 要消掉的。
>
> **为什么补在工作副本而不是只补比较基准**（下一段）：这条论证在 A4c 之后**依然成立**，
> 只是承担者从「种子」换成了「段补丁」—— 补丁同样落在转录里、同样被
> `resolveTranscriptTools` 当 `requestTools` 的来源。

**为什么补在工作副本而不是只补比较基准**：`resolveTranscriptTools` 的 `requestTools` 在锚定支下
取的就是**前导消息声明的那些**（§2 P3）—— 声明不落在转录里，A3c 的「请求级工具表只增不减」
就没有来源。

**⚠️ 两处已知差异（今天都不可观察，登记待 A4/A7）**：
① 提示文本在声明里被**冻结** —— pi 的前导消息内容同样是起点那份，中途的提示变化走
**sections 差分**（A4）；java 的 sections 尚未落地 ⇒ 将来若系统提示能在会话中途变化，
它会到不了模型。⇒ **A4c 已闭合**：段补丁就是差分，且 `PiLaneEngine.prepareNextTurn`
把补丁交给循环。
② `rebuildLaneMessages`（压缩/恢复/重置）整体替换工作副本、丢掉这条声明，下一次起手按**当时**的
工具集重建 ⇒ 被跨重建的工具增删不会作为增量宣告。⇒ **A4c 起不再适用**：段补丁**每次起手重新算**
（`startPass` 的差分基准是工作副本），压缩重建之后照算。
两者的前提都是「工具集在一次会话里会变」，而今天 `setActiveTools` 没有调用者（F6）。

**先红（实测）**：`git stash push -- <PiLoop/PiLoopRunner/PiLaneEngine>` ＋ 新 corpus 复跑
⇒ **10 红**（S2/S3/S4/S5/S9–S14，即全部带工具的剧本），而 `ToolChangeDeclarationTest`
（直测移植函数）仍 11/11 绿 —— **红来自接线，不是来自端口**。这条「红集切分」本身就是
F11 的证据：pi 的帧序里有声明帧，旧实现没有。

**变异红集（实测）**：M6 互换差分两入参 **5 红** · M8 声明改插末尾 **3 红** ·
M9 短路回「只在有 pending 时」**3 红** · M10 锚点取首条而非末条系统消息 **1 红** ·
M11 把 pending 自己的工具字段当事实 **1 红**。

⚠️ **设计稿 §6 的 M7（去掉「空列表省略键」门）在 Java 上无牙**：Java 的表示里
「空列表」与「键缺席」是同一件事，省略发生在**落线层**（`SessionJson` 的空值省略门，
A1 的 M4 探针已钉住）。M7 移到那里去测，本包不重复。

### 12.3 顺带修好的 corpus 缺陷（同上提交）

`conformance/pi-out/*.pi.jsonl` 里 **10 个带工具的剧本是陈旧的**（F12）：生成于 pi 还没有
`runAgentLoop:109` 起手宣告的版本。证据链：把 `conformance/pi/run.test.ts` 装回 pi 检出
（`3390bd936`，`git status` 空）重跑，**5 个无工具的剧本逐字节复现**，10 个带工具的以
`TypeError: tr.content.map is not a function` 崩掉 —— 崩溃点正是 Normalizer 的「其他角色
落进 toolResult 分支」，也就是**声明消息**。

处置：① pi 侧 runner 的 `Normalizer.message` 补 `system` 分支（与 `FrameNormalizer` 的
system 支逐字对应）；② `toolsAdded` 在帧里按 pi 的**三键**归一（Java 的转录槽是七件套）；
③ `ScriptTool.inputSchema()` 改成 typebox 的 `Type.object({}, {additionalProperties:true})` 形状；
④ 两侧 echo 的 `n` 改成「provider 看到的」消息数、并删掉 `sys=` 分量
（pi 的 `streamFn` 收到的是 `normalizeContext({messages})`，`systemPrompt` 恒 undefined ⇒
那个分量在 pi 侧是常量，比它等于比夹具接线）。⑤ 重生成全部 15 份。

**跑法**（重生成 pi 侧真相，务必照做且事后清理 pi 检出）：

```
cp conformance/pi/run.test.ts          <pi>/packages/agent/test/conformance/run.test.ts
cp conformance/pi/vitest.conformance.config.ts <pi>/packages/agent/
cd <pi> && CONFORMANCE_SCRIPTS=<pi-java>/conformance/scripts \
  CONFORMANCE_OUT=<pi-java>/conformance/pi-out \
  ./node_modules/.bin/vitest --run --config packages/agent/vitest.conformance.config.ts \
  packages/agent/test/conformance/run.test.ts
rm -rf <pi>/packages/agent/test/conformance <pi>/packages/agent/vitest.conformance.config.ts
```

⚠️ 用 `./node_modules/.bin/vitest`（shell 包装脚本）；`node ./node_modules/.bin/vitest` 会以
`SyntaxError: missing ) after argument list` 失败。

### 12.4 步骤 A3c —— 四条车道全落（`1700985`／`c59db5a`，2026-09-26）

| 车道 | 落了什么 |
|---|---|
| **Anthropic** | 四条件门（两个 compat 标志 ＋ **初始工具非空** ＋ **无工具重定义**）、`mid-conversation-tool-changes-2026-07-01` beta 头、工具表三段式（初始活跃 ＋ `__pi_deferred_placeholder__` ＋ 后续声明全部 `defer_loading`；**被删的工具仍然声明**，靠 `tool_removal` 撤回）、更新块攒批（`pendingSystemMessages`，在**下一条 assistant 之前**或转录末尾刷出）、块序 `[text?, removals…, additions…]`、删守卫 |
| **Completions** | 门（两个标志皆 `=== true`）、Kimi 形状 `{role:"system", tools:[…]}` **先于**该条的文本消息（一条系统消息可产出**两条**线上消息）、`i > 0` 走 `renderSystemMessageUpdate`、`requestTools` 取代 `getCurrentTools`、删守卫 |
| **Responses** | 门（`supportsAdditionalTools \|\| supportsToolSearch`）、`requestTools`、`additional_tools` 项／合成的 `tool_search_call`+`tool_search_output` 对（`call_id` 确定性哈希）、删守卫 |
| **Mistral** | 中途系统消息落成第二条 `role:system` 消息、删守卫 |
| **agent-core / TUI** | R2 删 `Entry.ActiveToolsChange`；R6 `toolCount()` 改读生效子集（`ToolRegistry.activeOf`） |
| **Transcripts** | 删 `requireOnlyLeadingSystemMessage`（四条车道都不再需要它） |

⚠️ **两处必须记牢的判据差异**：① Anthropic 的四条件门只排除**重定义**
（`hasToolRedefinitions`）—— **删除完全没问题**，那正是 `tool_removal` 的用途；
Responses 的 `anchorsAdditions` 用 `hasNonAdditiveToolChanges`，**任何删除都会关掉锚定**。
两者的夹具因此用**不同**的上下文，抄错就差分不出来。② Mistral **没有**工具锚定机制
（其 compat 只有 `supportsMidConvoSystemMessages` 一个标志）。

另外**修掉一处此前不可观察的下标偏差**：pi 的 `if (!isLeadingSystemMessage) msgIndex++`
（`openai-responses-shared.ts:349`）把**中途系统消息也算一个下标** —— 回填 id 里的
`msg_pi_${msgIndex}` 与 tool_search 的种子都用它。折叠支下中途系统消息为零 ⇒ 这条自增
从未生效；A3 放开之后必须补上，否则 id 与 call_id 都与 pi 不同。

#### 12.4.1 ⚠️ 自我纠正：F13 的初版结论是错的

本设计稿（以及基于它的 `docs/32` B92/B93）一度判定 **Anthropic 与 Completions 的
原生渲染「不可实施」**，理由是「要发的形状不在钉住的 SDK 类型族里」。**那是错的。**

错在**取证方法**：我读的是 `javap` 列出的 `Companion.ofXxx` **工厂方法清单**，见
Anthropic 的 `ContentBlockParam` 没有 `ofToolAddition`、openai 的
`ChatCompletionMessageParam` 六个变体没有一个带 `tools`，就下了结论。但**工厂只为
已知变体生成** —— 未知变体走的是另一条路：`ContentBlockParam` 的构造器里有
`JsonValue` 分量、有 `_json()` 访问器；反序列化把认不出的 `type` 收进 `_unknown`，
序列化**原样写出**。实测（`SdkJsonEscapeHatchTest` 的逐字节往返）：

```
PROBE-ANTHROPIC wire={"content":[{"type":"tool_addition","tool":{"type":"tool_reference","name":"late_tool"}}],"role":"system"}
PROBE-OPENAI   wire={"role":"system","tools":[{"type":"function","function":{"name":"late_tool"}}]}
```

⇒ 两条车道都用**原始 JSON 直通**落地，**不需要**迁 beta 类型族、也不需要手搓整个 body。
报错的那一步是「以为 `javap` 的工厂清单就是 SDK 的表达能力边界」。

#### 12.4.2 先红与变异

- **先红**：`git stash push -- <AnthropicRequestBuilder / OpenAICompletionsMessageConverter /
  Transcripts>` ＋ 新夹具复跑 ⇒ **Anthropic 5 红 + Completions 3 红**，且红全部是
  「请求没有真的发出去」—— 旧代码的守卫在构建期抛，桩服务器零请求
  （**这正是 §6 预测的红形态**）。Responses 那一份更早做过：**4 红**，同形。
- **变异红集**（实测）：

| 探针 | 红 |
|---|---|
| A1 门忽略工具重定义 | 1 |
| A2 门忽略「初始工具非空」 | 1 |
| A3 不发 beta 头 | 1 |
| A4 `tool_removal`/`tool_addition` 块序对调 | 1 |
| A5 中途系统消息**不攒批**（就地发） | 1 |
| N1 关掉 Responses 的锚定门 | 3 |
| N2 锚定不看 `anchorsAdditions` | 1 |
| N3 搜索结果不打 `defer_loading` | 1 |
| N4 中途消息改用完整提示渲染 | 2 |
| N5 `call_id` 失去确定性 | 1 |
| C1 门忽略 `supportsMidConvoToolAdditions` | 1 |
| C2 Kimi 消息带上 `content` 键 | 2 |
| C3 请求级工具回退到完整当前集 | 1 |
| C4 中途文本用完整提示渲染 | 1 |

- ⚠️ **探针脚本的两轮「静默无效」**（本包第二次兑现这条教训）：
  ① A1 的正则漏了行尾的 `;` ⇒ 变异**没落到文件里**，跑出「零红」；改对后 1 红。
  ② A5 的变异**落到了**，但夹具**没钉位置**（转录 `[前导, user, 更新]` 里就地发与攒批
  发得到的**末条消息是同一条**）⇒ 补了 `holdsSystemUpdatesBackUntilTheNextAssistantMessage`
  （转录 `[前导, userA, 更新, userB]` ⇒ 线上必须是 `[userA, userB, system]`）才有牙。
  **凡探针报「零红」，先复核两件事：变异是否真的落到文件里（grep 复核）、夹具是否真的
  钉住了那个行为。**（CRLF 行尾会让 `perl -0pi -e 's/…\n…/'` 匹配不上 —— 本包两次踩到。）

#### 12.4.3 三处已知差异（都写在各自夹具的类注释里）

① **Responses 文本项的 role**：pi 的 `instructionRole` 是
`model.reasoning && supportsDeveloperRole !== false`（缺席 ⇒ **真**），java 恒发 `system`
（`supportsDeveloperRole` 未接进 `ModelCompat`）⇒ 夹具用 `reasoning:false` 的模型使两侧同形；
差异本身登记 §10 **L-E**（`docs/41 A-07`）。⚠️ Completions 的同一表达式用的是**真值**判断
（`model.reasoning && compat.supportsDeveloperRole`，缺席 ⇒ **假**）⇒ 那条车道两侧本来就同形，
**两者别混**。
② **Anthropic 顶层 `system` 是字符串而不是块数组**（`docs/32` **B89**，与 `cache_control`
一并归 A-01）：夹具因此断言字符串。
③ **没有 `cache_control`**（A-01）：§4.4 ⑥ 的「断点可落在 `tool_addition`/`tool_removal` 上」
无从落，夹具只断言 `defer_loading` 的有无。三处的 `sections` 删除语义
（`docs/49 §9 R3①`）同样表达不了，夹具只用 content ＋非空 section。

#### 12.4.4 验收 grep（§8）现状

| # | 现状 |
|---|---|
| §8-1／3／4／6 | ✅ 全过（函数只在 `Transcripts` 与调用它的车道；beta 头与占位符各恰一处）|
| §8-2 | ✅ **代码零命中**、函数已删；⚠️ 但有 **3 条注释**提到函数名（三处「守卫已删」的说明）—— 跑验收 grep 时按注释读 |
| §8-5 | ✅ `ActiveToolsChange` 在 `pi-java-agent-core/src/main` 只剩 `Entry` 类 javadoc 里的那条裁决说明 |

