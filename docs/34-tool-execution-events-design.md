# 34 - 包⑦：工具执行事件（B43）

> **文档约定**（用户 2026-09-19）：**一个功能模块一份文档**，不往 `docs/31` 追加；
> `docs/32` 是唯一索引。本文件按新流程写：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**，
> **用户审核 §8 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：命题**已逐条核过**（下表 `已核` 均附本轮读到的出处）；
> §8 是实施稿，**待审核**。

---

## 1. 这一包解决什么

**根因一处**：`tool_execution_start/update/end` 这三条**工具执行生命周期**事件在
pi-java 的会话层**没有出口** —— 而 pi 有，且 RPC 线逐字节透传。

| 面 | 现状 | 谁受影响 |
|---|---|---|
| **接线（新发现的真根因）** | `PiLoop.Event.ToolExecutionStart/Update/End` **存在且已对齐**（L5 逐帧验证过），但 `SessionRunner.passEvents` 只认 `MessageEnd`/`AgentStart`，**把这三条丢了**；`AgentSessionEvent` 也没有对应变体 | 所有消费面 |
| **RPC / JSON 标准输出** | pi 对非 `message_update` 事件**原样透传**（`json-event.ts:48-51` 的 `return event;`）⇒ 这三条**带着全部载荷上线**。pi-java 的 `JsonEventMapper` **没有它们的分支** ⇒ 落到 `default -> "unsupported_event"` | RPC 客户端（编辑器集成 / 脚本） |
| **web** | `AgentEventTranslator` 把 **`StreamEvent.ToolCallStart/Delta/End`**（＝「模型把这次调用**吐完**了」）翻成这三个名字 ⇒ **名字对、时刻不对**。工具跑 30 秒，前端这 30 秒里收不到任何东西 | web UI |

**范围**：`AgentSessionEvent` 加变体 ＋ 会话层转发 ＋ 两个消费面各接一次。
**`pi-java-agent-core` 生产代码零改动** —— `PiLoop.Event` 已与 pi 1:1（§2.2 P9–P13）。

---

## 2. 命题表

**判定口径**：`已核`＝本文件写下当轮**逐行读过**并附出处；`待核`＝尚未读、不许当事实用。

### 2.1 pi 侧（行为定义）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | 三条事件是 `AgentEvent` 联合的成员，**每个字段都必填、没有 `?`** | `packages/agent/src/types.ts:443-446`（逐字抄见下） | **已核** |
| P2 | 会话层联合**复用** agent 联合（`Exclude<AgentEvent, {type:"agent_end"}> \| {...}`）⇒ 这三条原样是 `AgentSessionEvent` 成员 | `core/agent-session.ts:143-145` | **已核** |
| P3 | **`tool_execution_start` 在「工具即将开始执行」时发**，不是模型流式期间 —— 它排在 `prepareToolCall`（校验）与 `beforeToolCall` 钩子**之前** | `agent-loop.ts:443-448`（顺序路径）/ `:498-503`（并行）；`prepareToolCall` 在 `:450`/`:505`；`streamAssistantResponse` 的 `message_end` 在 `:355`、返回在 `:212`，批次在 `:233` 才起 | **已核** |
| P4 | **`tool_execution_end` 在「工具跑完并定稿」时发** —— 排在 `executePreparedToolCall` 与 `finalizeExecutedToolCall` **之后** | `agent-loop.ts:459-470`（顺序）/ `:530-539`（并行）；`afterToolCall` 的文档原文「Called after a tool finishes executing, **before `tool_execution_end`**」在 `types.ts:281` | **已核** |
| P5 | 「模型把调用吐完」是**另一条**事件：`message_update` 里嵌套的 `assistantMessageEvent.type === "toolcall_end"`，且它**严格早于**任何 `tool_execution_start` | `ai/src/types.ts:556`（变体）、`agent-loop.ts:330-341`（转发） | **已核** |
| P6 | `tool_execution_end` **带完整结果**：`result`（`AgentToolResult`，文本在 `result.content[i].text`）＋ **另立的** `isError` 布尔 | `agent-loop.ts:774-782`、`types.ts:362-376` | **已核** |
| P7 | **RPC/JSON 线把这三条原样透传**（`return event;`），字段不重命名、不裁剪 | `modes/json-event.ts:48-51`；消费点 `modes/rpc/rpc-mode.ts:355-360`、`modes/print-mode.ts:108-112` | **已核** |
| P8 | **pi 仓库里没有 web UI**（workspaces 无前端包；`tool_execution*` 全仓零命中于 `protocol`/`server`/`client`）⇒ **web 面没有 pi 可直接对齐的对手** | 全仓 grep；`packages.json:5-14` | **已核** |

逐字核对（P1）：

```ts
| { type: "tool_execution_start"; toolCallId: string; toolName: string; args: any }
| { type: "tool_execution_update"; toolCallId: string; toolName: string; args: any; partialResult: any }
| { type: "tool_execution_end"; toolCallId: string; toolName: string; result: any; isError: boolean };
```

⚠️ 全必填 ⇒ **线上没有「缺哪个键」的自由度**（与包④ 那套可空键纪律不同档：那边 pi 的字段带 `?`，这边一个都没有）。

### 2.2 pi-java 侧（现状）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P9 | 三个事件**已存在**且与 pi 同形：`ToolExecutionStart(toolCallId, toolName, args)` / `ToolExecutionUpdate(…, args, partialResult)` / `ToolExecutionEnd(…, result, isError)` | `PiLoop.java:66-76` | **已核** |
| P10 | 发射点齐全：start 在顺序路径 `:97` 与并行路径的准备循环；end 在 `:105` 与并行 thunk；**update 在 `PiToolRunner:158`** | `PiLoopTools.java:97/105`、`PiToolRunner.java:158` | **已核** |
| P11 | 顺序路径**逐个成组**：start → prepare/execute → end → 结果消息 start/end | `PiLoopTools.java:96-113` | **已核** |
| P12 | 并行路径**不保证「所有 start 早于任何 end」**（准备相即失败的调用当场收尾，其 end 插在后续 start 之前）；end 是**完成序** | `PiLoopTools.java:122-146` 的 javadoc（逐条对 `agent-loop.ts` 行号） | **已核** |
| P13 | `PiLaneSink.emit` 是**唯一漏斗**且 `synchronized` 串行化 —— 工具 update 回调在各自工具线程直呼 | `PiLaneSink.java:308-326` | **已核** |
| P14 | **L5 差分已经逐帧覆盖这三条**（golden 含 `tool_execution_*`，`FrameNormalizer:55` 已归一化 `ToolExecutionUpdate`）⇒ 模型/事件层**已对齐**，本包不动它 | `conformance/pi-out/S2–S14.jsonl`（10 个剧本命中）、`FrameNormalizer.java:55` | **已核** |
| P15 | **`SessionRunner.passEvents` 收到 `PiLoop.Event` 但只处理 `MessageEnd`/`AgentStart`** ⇒ 三条工具执行事件**在会话层被丢弃** | `SessionRunner.java:203-215` | **已核** |
| P16 | `AgentSessionEvent` 的 17 个变体里**没有**工具执行 | `AgentSessionEvent.java:18-71` | **已核** |
| P17 | `AgentEventTranslator` 把 `StreamEvent.ToolCallStart/Delta/End` 翻成 `tool_execution_start/update/end`（**错的源**） | `AgentEventTranslator.java:84-95` | **已核** |
| P18 | `JsonEventMapper` **没有**这三条的分支 ⇒ `default -> "unsupported_event"` | `JsonEventMapper.java:148` | **已核** |
| P19 | 三个 `switch (AgentSessionEvent)` **全都有 `default`** ⇒ **加变体不会破坏任何现有 switch、不会引入编译失败** | `JsonEventMapper.java:148`、`AgentEventTranslator.java:56-58`、`ChatScreen.java:293` | **已核** |
| P20 | 消费端是三条订阅：`RpcDispatcher`、`WebDispatcher`、TUI | `RpcDispatcher.java:62/126/222/233`、`WebDispatcher.java:172` | **已核** |
| P21 | `ToolResult` 是普通 record：`(content, details, usage, terminate, addedToolNames)` | `ToolResult.java:16-23` | **已核** |

### 2.3 前端侧（`pi-java-web/src/main/frontend/`，**本仓**）

| # | 命题 | 出处 | 判定 |
|---|---|---|---|
| P22 | 前端在这三条上**只 `renderApp()`、完全不读载荷** | `client/main.ts:339-343` | **已核** |

---

## 3. 被推翻的命题

| # | 我原先以为 | 实测 | 后果 |
|---|---|---|---|
| **R1** | `tool_execution_*` 需要**新建**事件类型（比如在 agent-core 里造） | **错**：`PiLoop.Event` 三个变体**早已存在**、形状与 pi 逐字段相同、**且被 L5 逐帧验证过**（P9/P14） | ⇒ **`pi-java-agent-core` 生产代码零改动**。真根因是「会话层没有出口」，不是「事件不存在」 |
| **R2** | 这条缺口只在 web 面（`docs/33 §4-B` 就是这么记的） | **半错**：web 面确实有，但 **RPC 面同样缺**（P7/P18）—— pi 逐字节透传、pi-java 发 `unsupported_event` | ⇒ 本包与包⑥ 同构，**也是两面** |
| **R3** | 修 B43 要让 web 改吃 agent-core 的工具事件，是「另一包的重接线」 | **对，但比说的轻**：接线只差 `SessionRunner.passEvents` 三行转发 ＋ 一个变体；agent-core 内部（批次结构、并发、顺序）**一行都不用动** | ⇒ 不是重接线，是**加一个出口** |

---

## 4. 审计（顺带发现，本轮）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **pi 没有 web UI** —— 它唯一的 UI 是 TUI（`interactive-mode.ts:3324-3365`，且它**逐字段读载荷**：start 用 `toolCallId/toolName/args` 建组件、update `updateResult(partialResult)`、end `updateResult(result, isError)`） | P8 的 grep | ⇒ web 面的**载荷**没有 pi 的直接对手；**裁决点 A** |
| **B** | pi 的 `interactive-mode.ts` 展示了**载荷该怎么用**：这正是 web 前端日后要长成的样子 | `interactive-mode.ts:3324-3365` | 支持「web 帧带载荷」（裁决点 A） |
| **C** | `PiLoop.Event.ToolExecutionUpdate` 的 `args` 口径：pi 用 `prepared.toolCall.arguments`（**原始、未校验**），与 start 取的是**同一个**量 | `agent-loop.ts:698` | 已核，无需改动（pi-java 由 `PiToolRunner:158` 传入） |
| **D** | 截断路径（`stopReason === "length"`）会为**永不执行**的工具调用发 start＋end（`isError: true`） | `agent-loop.ts:385-401` | pi-java 的 `PiLoopTools:45` javadoc 已记该语义 ⇒ 无缺口 |
| **E** | pi 的 `update` **不保证跨工具不交错**（fire-and-forget 进数组），文档原文「may interleave across tools」 | `agent-loop.ts:692-704`、`docs/extensions.md:657` | pi-java 的 `PiLaneSink` 全局串行化（P13）⇒ 本仓顺序**更严**，属扩展不是缺口；如实登记 |

---

## 5. 待核 / 裁决点

| # | 问题 | 结论 |
|---|---|---|
| **N1** | web 帧要不要带载荷？pi 无 web（P8）⇒ 无对手；前端今天不读（P22）⇒ 带了没人用 | **裁决点 A**（见 §8.0） |
| **N2** | `result` 的序列化保真度 —— **已核，是真缺口** | pi 的 `AgentToolResult`（`types.ts:362-376`）是 `{content, details, usage?, addedToolNames?, terminate?}`，**后三个带 `?`** ⇒ 缺席即省略。pi-java 的 `ToolResult`（`ToolResult.java:16-23`）是 `(content, details, usage, terminate, addedToolNames)`，`terminate` 是**原始 boolean**、`addedToolNames` 恒 `[]`，且**全类零 Jackson 注解**、**全仓零序列化点**（grep 只命中 `HtmlExporter`/`WebWireJson` 里对 `content` 块的渲染，都不是 `ToolResult` 本体）⇒ 拿默认 Jackson 直接落线会得到 `"terminate":false` / `"addedToolNames":[]` / `"details":null` **三个 pi 会省略的键**。**裁决点 B** |
| **N3** | pi 的 `args`/`result`/`partialResult` 静态类型是 `any` ⇒ Java 用什么类型 | `PiLoop.Event` 已用 `Map<String,Object>` / `Object`（P9）⇒ **沿用**，不发明 |
| **N4** | 三条新变体是 3 个 record 还是 1 个 record + enum | **裁决点 C**：字段不同 ⇒ 按 CLAUDE.md 用 3 个 record |
| **N5** | TUI 要不要也吃这三条？ | **不做**（§6-4）：TUI 已按要求降级，且它今天在 `StreamEvent.ToolCallStart` 上只 `runToolCalls++`，改成真实工具执行事件会**改变语义**（计数时机不同）⇒ 另立 |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 改动 `pi-java-agent-core` 的任何生产代码 | `PiLoop.Event` 已对齐且 L5 验过（P9/P14/R1） |
| 2 | 修 pi 的 `update` 跨工具交错（§4-E） | pi-java 更严，且「更严」不是缺口 |
| 3 | 让 web 前端**消费**载荷（渲染工具运行中/结果） | 那是**前端功能**，本包只把线修对。前端改动另立（用户已定前端不在对齐包范围） |
| 4 | TUI 改吃这三条（N5） | TUI 降级中；且会改变 `runToolCalls` 的语义（计数时机） |
| 5 | 顺手补 `turn_end.toolResults`（B42） | 不同根因、不同面 |

---

## 8. 实施稿（步骤 ③ 的产物；**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决 | 后果 |
|---|---|---|
| **A** | **web 帧带载荷**（与 pi 的 RPC 透传同形）—— 理由：同一个变体喂两面，载荷是**投影的一部分**而非新增能力；且 §4-B 显示 pi 的 TUI 正是逐字段读它，web 前端要长成的就是那个样子 | 若改裁「只带 type」，web 侧需另一条投影路径 |
| **B** | **`result` 走一条显式投影**（而非拿默认 Jackson 直接落线）：`usage`/`addedToolNames`/`terminate` 照 pi 的 `?` 语义**缺席即省略**；`details` 为 null 时省略（pi 的 `details` 是必填 `T`，但工具实测会传 undefined，见 pi `bash.ts:296-298`） | 需要一个 `WebWireJson`/mapper 侧的小投影方法；**不改 `ToolResult` 本体**（那是 agent-core 的公共类型，加注解会改它的语义） |
| **C** | **3 个 record**（字段不同） | —— |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-coding-agent` | `AgentSessionEvent` 加 3 个变体 ＋ `SessionRunner.passEvents` 转发 ＋ `RpcDispatcher` 无改动（它是通用转发） |
| **2** | `pi-java-coding-agent` | `JsonEventMapper` 补三支（**照 pi 透传**，全字段必填） |
| **3** | `pi-java-web` | `AgentEventTranslator` 把三条的**源**从 `StreamEvent.ToolCall*` **换成**真实的工具执行事件；`JsonEventMapper`/web 的 `StreamEvent.ToolCall*` 分支**只留**包⑥ 加的 `message_update` |
| **4** | `docs/34`＋`docs/32` | 实施记录 ＋ B43/B28/B29 结案 |

⚠️ **步 3 是删改**：`StreamEvent.ToolCallStart/Delta/End` 不再产生 `tool_execution_*`。
包⑥ 刚在这三支上加的 `message_update` **保留**（那是 `toolcall_*` 增量的正确出口，P5）。

### 8.2 精确改动（形状，待 §8.0 裁决后定稿）

**(1) `AgentSessionEvent` 三个新变体**：

```java
/** pi {@code {type:"tool_execution_start"; toolCallId; toolName; args}}（全必填）。 */
record ToolExecutionStart(String toolCallId, String toolName, Map<String, Object> args)
    implements AgentSessionEvent {}
record ToolExecutionUpdate(String toolCallId, String toolName,
                           Map<String, Object> args, Object partialResult)
    implements AgentSessionEvent {}
record ToolExecutionEnd(String toolCallId, String toolName, Object result, boolean isError)
    implements AgentSessionEvent {}
```

**(2) `SessionRunner.passEvents` 三行转发**（`:203-215` 的 lambda 内）：

```java
if (event instanceof PiLoop.Event.ToolExecutionStart s) {
    owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionStart(
        s.toolCallId(), s.toolName(), s.args()));
}
// update / end 同形
```

**(3) `JsonEventMapper` 三支** —— 与 pi 同序（`type` 在前，其余按 pi 的字段顺序）：

```java
case AgentSessionEvent.ToolExecutionStart s -> {
    node.put("type", "tool_execution_start");
    node.put("toolCallId", s.toolCallId());
    node.put("toolName", s.toolName());
    node.set("args", MAPPER.valueToTree(s.args()));
}
// update：多 partialResult；end：result + isError
```

⚠️ 全字段必填（P1）⇒ **一个键都不许省**（与包④ 的可空键纪律相反，别照抄邻行）。

**(4) `AgentEventTranslator`**：

```java
// StreamEvent.ToolCallStart/Delta/End 支：删掉 tool_execution_*，只留 message_update
// 新增：
case AgentSessionEvent.ToolExecutionStart s -> out.add(toolExecution("tool_execution_start", s));
// update / end 同形
```

### 8.3 测试计划（**先红**）

| 模块 | 夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-coding-agent` | `AgentSessionToolExecutionEventTest` | 3 | `passEvents` 把 `PiLoop.Event.ToolExecution*` 转成对应的 `AgentSessionEvent`，字段逐个相等 |
| 同上 | `JsonEventMapperToolExecutionTest` | 4 | ① 三条都有分支（不再 `unsupported_event`）；② start 三键、update 四键、end 四键**一个不缺**；③ `args`/`partialResult`/`result` 是对象透传；④ `isError` 布尔照写 |
| `pi-java-web` | `AgentEventTranslatorToolExecutionTest` | 3 | ⑤ 真实工具执行事件 → `tool_execution_*`；⑥ **`StreamEvent.ToolCall*` 不再产生 `tool_execution_*`**（反向，钉住「换源」）；⑦ 包⑥ 的 `message_update` 仍在 |

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | `passEvents` 不转发 | ①②⑤ 有牙 |
| P2 | mapper 少写一个键（如 `toolName`） | ② 有牙 |
| P3 | web 保留旧源（两处都发） | ⑥ 有牙 |
| P4 | end 的 `isError` 恒 false | ④ 有牙 |
| P5 | 把 `args` 换成 `Map.of()` | ③ 有牙 |

### 8.5 未覆盖（预登记）

- **端到端**：真实工具（Bash 的累计快照 update）在 web 上的可见性未测 —— 需运行前端。
- **`result` 的保真度**：N2/裁决 B 落实后仍可能与 pi 的省略语义有差（待核）。
- **TUI**：不改（§6-4）。

---

## 9. 下一步

用户审核 §8（含 §8.0 三个裁决点）之后才写代码；实施完成后追加**实施记录**
（含实测红集、偏差、未覆盖如数）。
