# 33 - 包⑥：工具调用的身份与可见性（命题表）

> **文档约定变更（用户 2026-09-19）**：**一个功能模块一份文档**，不再往 `docs/31` 追加。
> `docs/31` 及其 §8.1–§8.38 是**历史**，不重写；自本文件起，每个包在自己的文件里记设计、
> 取证与实施记录。`docs/32` 仍是索引（每条登记仍要进台账）。
>
> **本文件当前状态**：**步骤 1＋2 的产物＝命题表**（尚未出实施稿）。按新流程
> 「设计 → 代码验证 → 再实施」：命题逐条核过之后才写分步计划；用户审核之后才写代码。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。

---

## 1. 这一包解决什么

三处同根：**工具调用在流式起点没有身份**。

| 面 | 缺口 | 谁受影响 |
|---|---|---|
| **核心** | `StreamPartialBuilder.emitToolCallStart()` 无参，插的是**空占位** `ToolUseContent("", "", {})`；pi 在同刻插的是**带 id/name 的块** | 所有下游（两个消费面都从这里取数） |
| **RPC / JSON 标准输出** | `message_update` **恒缺顶层 `usage`**；`toolcall_start` **缺 `id`/`toolName`** | RPC 客户端（编辑器集成/脚本） |
| **web** | 工具调用的三个事件上**不推 `message_update`** ⇒ 前端手上最后一条 `message_update` 是工具调用**之前**那条 ⇒ 工具卡要等 `agent_end` 整表替换才出现 | web UI（用户已明示：web 将替代 TUI） |

**范围**：核心一处修复 ＋ 两个消费面各接一次。**不含**：前端改动（`pi-webui` 是仓库外，
且它的渲染已经就绪 —— 见 P8）。

---

## 2. 命题表

**判定口径**：`已核`＝本文件写下当轮**逐行读过**并附出处；`待核`＝尚未读、不许当事实用。

### 2.1 pi 侧（行为定义）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | `message_update` 的线格式**恒带**顶层 `usage`，取值是**累积消息**的 usage | `json-event.ts:11-15`（类型）、`:56-60`（`usage: event.message.usage`） | **已核** |
| P2 | 该 usage **永不为 undefined** —— 流起点初始化为**全零对象**（含 `totalTokens: 0` 与零化 `cost`） | `anthropic-messages.ts:518-525`、`openai-completions.ts:325-332` | **已核** |
| P3 | `toolcall_start` 的线格式**补** `id` + `toolName`，值取自 `partial.content[contentIndex]`；该位置**不是** toolCall 时**抛错** | `json-event.ts:23-30`（`toJsonAssistantMessageEvent`） | **已核** |
| P4 | 该 partial 在**发出起点事件的那一刻**就带身份 —— 块**先建后 push** | `anthropic-messages.ts:648-660`（工具块带 `id`/`name` 入 `output.content` 后才 push `toolcall_start`）；thinking 块同形（`:630-647`，包① 已按此改了 `emitThinkingStart`） | **已核** |
| P5 | 五条车道**全部**「先建块后 push 起点事件」（不是 Anthropic 独有） | `openai-completions.ts:497-529`（`id: toolCall.id \|\| ""`，name 可能为空串）、`:534-536`（name 之后**就地补**，块是同一对象） | **已核** |
| P6 | completions 车道**允许**起点块只有 id、name 稍后补 —— 即「起点 `toolName: ""`」是 **pi 自己也有的形状** | 同上；pi-java `ToolCallAccumulator:9-22` 的注释独立描述了同一现象 | **已核** |
| P7 | pi 的 `Usage` 必填 `input/output/cacheRead/cacheWrite/totalTokens/cost{input,output,cacheRead,cacheWrite,total}`，可选 `cacheWrite1h?`/`reasoning?` | `types.ts:383-405` | **已核** |
| P8 | pi 的 `tool_execution_start` 载荷是 `{toolCallId, toolName, args}` | `packages/agent/src/agent-loop.ts:386-388` | **已核** |

### 2.2 pi-java 侧（现状）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P9 | `emitToolCallStart()` **无参**，先 `toolArgBuf`/`toolCallId`/`toolCallName` 清零，再插 `ToolUseContent("", "", Map.of())` | `StreamPartialBuilder:258-266` | **已核** |
| P10 | 但**六个调用点在起点都已知 id/name**（至少其一） | ① `AnthropicMessagesApi:187-193`（`pendingToolName[0]`/`pendingToolId[0]` 刚赋值）② `GoogleGenerativeAiApi:181-188`（`id`/`name` 已算出，id 为 `fc.id().orElse(name+"_"+now)`）③ `MistralConversationsApi:211-214`（`tcId`/`name` 非空判定后 `toolBuilder.start`）④ `PiMessagesApi:104`（取 `s.id()`；而 `PiMessagesEvent.ToolCallStart:46` **已有 `toolName` 字段，调用点没用**）⑤ `ResponsesStreamProcessor:196-199`（刚存入 `fc.callId()`/`fc.name()`）⑥ `ToolCallAccumulator:32-41`（**可能只知其一**，见 P6） | **已核** |
| P11 | `JsonEventMapper` 的 `message_update` **只写** `type` + `assistantMessageEvent`，**无 `usage`** | `JsonEventMapper:49-52` | **已核** |
| P12 | `toolcall_start` 的载荷目前**只有** `contentIndex`（`partial` 被 `StreamEventMixin` 剥掉），**无 id/toolName** | `JsonEventMapper:33-36`（mixin）、`StreamEvent:146`（`ToolCallStart(int contentIndex, AssistantMessage partial)`） | **已核** |
| P13 | pi-java 的 `AssistantMessage.usage` 组件类型是 `StreamEvent.UsageInfo`，**不是** `Usage` | `AssistantMessage:55` | **已核** |
| P14 | 归一化 `UsageInfo → Usage` **存在但是 private**：有全量分解用全量，否则合成（cache 0、cost 零）；`UsageInfo == null` ⇒ **返回 null** | `Message.AssistantMessage.usageOf:131-142` | **已核** |
| P15 | 归一化产出的是 `Usage`（**无 `partial` 字段**）⇒ 写进线格式没有自引用风险 | 同上 + `Usage.java:24-33` | **已核** |
| P16 | 三个 `tool_execution_*` 在 web 上**只推 `{type}`、无载荷** | `AgentEventTranslator:73-78` | **已核** |
| P17 | web 在 `ToolCallStart/Delta/End` 上**不推 `message_update`**（只 Text/ThinkingDelta 推） | `AgentEventTranslator:65-88` 的 switch | **已核** |
| P18 | 但 web 的 wire 已经有完整的 toolCall 投影：`assistantNode` 遍历 partial 的 content，`blockNode` 输出 `{type:"toolCall", id, name, arguments}` | `WebWireJson:92-100`、`:119-124` | **已核** |
| P19 | `FauxProvider.toolCall` 的起点事件用的也是**空身份块** | `FauxProvider:95-103` | **已核** |
| P20 | `RpcDispatcher.emitEvent` 调 `toWire` 时**只捕 `IOException`** | `RpcDispatcher:311-320` | **已核** |
| P21 | 其余 `ToolCallStart` 消费者**不读 partial**：`PiLoopRunner:357`（只是 `isUpdateEvent` 的成员判定）、`PrintMode:88`（忽略）、`ChatScreen`（只 `runToolCalls++`） | 各自出处 | **已核** |

### 2.3 前端侧（`pi-webui`，**仓库外，只读**）

| # | 命题 | 出处 | 判定 |
|---|---|---|---|
| P22 | `message_update` → `updateStreamingContainer(event.message, true)`，即前端渲染的是**累积消息** | `client/main.ts:272-276` | **已核** |
| P23 | 三个 `tool_execution_*` **只调 `renderApp()`，完全不读载荷** | `client/main.ts:302-305` | **已核** |
| P24 | `turn_end` 按 `toolCallId` 去重后 append `event.toolResults` | `client/main.ts:286-296` | **已核** |
| P25 | 渲染组件（npm `@mariozechner/pi-web-ui@0.66.1`）**会**渲染 `chunk.type === "toolCall"` 的内容块，且工具名取 `this.tool?.name \|\| this.toolCall.name`（**回落到消息块里的 name**） | `node_modules/@mariozechner/pi-web-ui/dist/components/Messages.js:74-88`、`:223` | **已核** |

---

## 3. 被推翻的命题（我给用户的推荐依据）

写下来，因为**它们是这一版流程变更的直接理由**。

| # | 我原先说的 | 实测 | 后果 |
|---|---|---|---|
| **R1** | 「B28/B29 是 **web** 流式界面直接吃的」 | **错**：web 走 `AgentEventTranslator`，**完全不经过** `JsonEventMapper`（P17/P18） | B28/B29 是 **RPC 面**，与 web 无关 |
| **R2** | 「web 前端在工具执行期间拿不到工具名 / 拿不到结果」 | **半错**：名与结果前端**有**取法（P25 从 message 块取 name；P24 从 `turn_end.toolResults` 取结果）——但它们**都到不了**，因为 pi-java 在 ToolCall* 上不推 `message_update`（P17）、且 `turn_end` 无 `toolResults`（见 §4-A） | 真缺口是**推送时机**，不是载荷字段 |
| **R3** | 「给 `tool_execution_start` 补 `toolCallId`/`toolName`」 | **推翻为投机**：前端根本不读这三个事件的载荷（P23）；且 `docs/15:148` 原文就写着「载荷对齐前端 `tool_execution_*` 的空处理」。按本仓 C1/C9 的口径（「加发射是投机代码」）⇒ **不做** | 从范围里删掉 |

> ⚠️ **流程教训（本文档是第一个按新流程写的）**：R1/R2/R3 三条都是**没读代码就断言**的产物。
> 新流程要求每条命题先落表、逐条核过，才允许进实施稿。

---

## 4. 审计：同一结构缺口还盖住了什么（本轮新发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **`turn_end` 不带 `toolResults`** —— pi-java 把 `AgentSettled` 翻成空 `{type:"turn_end"}`，而前端按 `toolCallId` 从 `turn_end.toolResults` 收工具结果 | `AgentEventTranslator:51-54` vs `client/main.ts:286-296` | **今天不可观测**（`agent_end.messages` 里含 toolResult 消息，整表替换兜住）⇒ **登记不修** |
| **B** | **`tool_execution_end` 的语义错位**：pi 是「**工具跑完**」（带 `result`/`isError`），pi-java 发的是「**模型把调用吐完**」（`StreamEvent.ToolCallEnd`） | `agent-loop.ts:767` 区（pi）vs `AgentEventTranslator:77-78` | 今天不可观测（前端不读载荷，P23）⇒ **登记不修**，随前端扩展重估 |
| **C** | `PiMessagesEvent.ToolCallStart` **已有 `toolName` 字段**，调用点只取 `id` | `PiMessagesEvent:46` vs `PiMessagesApi:104` | 本包**顺手传**（一个实参），否则该车道的起点身份仍然是空的 |
| **D** | `FauxProvider.toolCall` 的起点块也是空身份 —— 改核心而**不改桩**，会让 L5 桩与生产**不同形** | `FauxProvider:95-103` | 本包**必须同改**，否则「夹具会把人脑里的模型当作契约」 |
| **E** | `ChatScreen` 的 `runToolCalls++` 是**唯一**读 `ToolCallStart` 形状之外的用途，且不读内容 | `ChatScreen:117` | 无需改动（反向确认） |
| **F** | **两个桩在 `ToolCallStart` 上都不带块**：`ScriptedStreams` 的 `blank = scripted("stop", List.of())`（**空内容**，全部事件共用同一个快照） | `ScriptedStreams:54`、`:85` | ⚠️ **L5 的桩不能改** —— 改它＝改 L5 帧＝与 pi 侧共享的 golden 失效。故本包**只改 `FauxProvider`**，并接受「桩与 pi 的 partial 形状不同」这件事如实登记 |
| **G** | L5 **不由 `FauxProvider` 驱动**（L5 用 test 侧 `ScriptedStreams`） | `ConformanceRunner:138` | ⇒ 改 `FauxProvider` **不动 L5**（N4 由此解） |

---

## 5. 待核 —— 已核（本轮读码结果）

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| N1 | `toWire` 抛异常在 RPC 栈上的后果 | **隔离**：`SessionEventHub.emit` 逐个 listener `catch (RuntimeException e)` 并记 warning ⇒ 抛错 = **丢该客户端的这一帧 ＋ 一条日志**，连接与其它监听器不受影响 | `SessionEventHub:27-36`；`RpcDispatcher:311-320` 只捕 `IOException` ⇒ `RuntimeException` 上抛到 hub |
| N2 | `usage` 归一化的公开落点 | **裁决点**（不是事实问题）：① 给 `StreamEvent.UsageInfo` 加 `toUsage()`；② 在 `Message.AssistantMessage` 开公开静态。倾向 ① —— 它把「StreamEvent 变体 → 领域类型」的投影留在 `pi-java-ai` 里，与 `snapshot()`/`withUsage()` 同层 | `AssistantMessage:55`、`Message.java:131-142` |
| N3 | 「`UsageInfo == null` 时写什么」两处口径 | **pi 恒写零值对象**（P2）⇒ 线格式必须**恒写**；而 `usageOf` 现在对 null 返回 **null**（P14）⇒ **两处口径不一样，且焦区别在于**：`usageOf` 服务**终局消息**（pi 该处也是必填 `Usage`，所以它**同样**是缺口），mapper 服务**每一帧**。**裁决点**：本包只修 mapper（低风险），把「终局消息 `usage` 可空」**另登记**（它会动现有终局投影，风险不同档） | `Message.java:131-142`、`types.ts:439` |
| N4 | 改 `FauxProvider` 是否动 L5 | **不动**（§4-G）；但**不能**顺手改 `ScriptedStreams`（§4-F） | `ConformanceRunner:138`、`ScriptedStreams:54/85` |
| N5 | web 推 `message_update` 的帧量 | `ToolCallDelta` 与 `TextDelta` 同量级（都是每块一条），前端 `updateStreamingContainer` 的重绘频率因此与今天**文本流一致** ⇒ 不引入新的量级。⚠️ **未实测**：真实前端下的重绘成本没有测量手段（仓库外），若日后可见卡顿，那是**新的一包**，不是本包能默认决定的 | `AgentEventTranslator:65-88`、`client/main.ts:272-276` |

### 5.1 由 N1–N5 带出的新增登记

| # | 条目 | 处置 |
|---|---|---|
| **B40** | **web 在 `ToolCall*` 上不推 `message_update`** ⇒ 工具调用期间前端手上最后一条 `message_update` 是调用**之前**那条 ⇒ 工具卡要等 `agent_end` 整表替换才出现 | **本包修**（范围第 3 项） |
| **B41** | **终局 assistant 消息的 `usage` 可空**（pi 必填 `Usage`，零值也在）—— `usageOf` 对 null 返回 null，`MessageMixin` 的 `NON_NULL` 让它**整键消失** | **登记不修**（N3：风险档不同；且它影响 `agent_end.messages` 的每一条助手消息） |
| **B42** | `turn_end` 不带 `toolResults`（前端按它收工具结果，今天被 `agent_end` 兜住） | **登记不修**（§4-A） |
| **B43** | `tool_execution_end` 语义错位（模型吐完 ≠ 工具跑完） | **登记不修**（§4-B；随前端扩展重估） |
| **B44** | 两个桩（`FauxProvider` / `ScriptedStreams`）的 `ToolCallStart` partial 与 pi 不同形（后者**不能**改，见 §4-F） | **登记**：本包只改 `FauxProvider` |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 给 `tool_execution_start/update/end` 补载荷 | 前端不读（P23）⇒ 投机（见 R3） |
| 2 | 修 `tool_execution_end` 的语义错位（§4-B） | 今天不可观测；真修要 web 改吃 agent-core 的工具事件，是**另一包** |
| 3 | 补 `turn_end.toolResults`（§4-A） | 今天被 `agent_end` 兜住；无消费者可证 |
| 4 | 改前端（`pi-webui`） | 仓库外、只读；且 P25 表明渲染侧已就绪 |
| 5 | 动 `PiMessagesApi` 的**其它**缺口（B18 签名/redacted） | 那是它自己的规则，本包只传一个已有字段（§4-C） |

---

## 7. 下一步

1. ~~核掉 §5 的 N1–N5~~ ⇒ **已完成**（§5）；
2. 出**实施稿**：分步计划 ＋ 测试计划（先红）＋ 变异探针设计 ＋ 裁决点（N2／N3 ＋ 下面这条）；
3. 用户审核之后才写代码。

**进实施稿前已知的三个裁决点**（不预设结论）：

| # | 问题 | 选项 |
|---|---|---|
| A | `toolcall_start` 的**读法** | ① 照 pi：读 `partial.content[contentIndex]`，不是 toolCall 就**抛**（N1 说后果＝丢一帧＋日志）；② 退化：读不到就写空串并登记。⚠️ **两个桩都不满足 pi 的读法**（§4-F）⇒ 无论选哪个，「夹具必须走生产形状（`StreamPartialBuilder`）」是硬约束 |
| B | `usage` 归一化的落点（N2） | ① `StreamEvent.UsageInfo.toUsage()`；② `Message.AssistantMessage` 公开静态 |
| C | N3 的两处口径 | ① 只改 mapper，终局消息的 `usage` 另登记（**倾向**）；② 一并改 `usageOf` |
