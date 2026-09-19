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

**范围**：核心一处修复 ＋ 两个消费面各接一次。**不含**：前端改动（前端源码在
**本仓** `pi-java-web/src/main/frontend/`，其渲染侧**已经就绪** —— 见 P25 ⇒ 本包只需要把
推送补上，不必动前端）。

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

### 2.3 前端侧（`pi-java-web/src/main/frontend/`，**本仓**）

> ⚠️ **出处更正（R4）**：本节初稿把前端源码指到**仓库外**的 `/d/workplaceForai/pi-webui`。
> 权威位置是**本仓** `pi-java-web/src/main/frontend/`。两份的 `client/main.ts` 各自独立，
> 但**组件来自同一个 npm 包**（`@mariozechner/pi-web-ui@0.66.1`，两处 `node_modules` 里
> `dist/components/Messages.js` **逐字节相同**）⇒ 四条命题**结论不变**，只有出处要改。

| # | 命题 | 出处 | 判定 |
|---|---|---|---|
| P22 | `message_update` → `updateStreamingContainer(event.message, true)`，即前端渲染的是**累积消息** | `client/main.ts:279-282` | **已核** |
| P23 | 三个 `tool_execution_*` **只调 `renderApp()`，完全不读载荷** | `client/main.ts:316-320` | **已核** |
| P24 | `turn_end` 按 `toolCallId` 去重后 append `event.toolResults` | `client/main.ts:302-310` | **已核** |
| P25 | 渲染组件**会**渲染 `chunk.type === "toolCall"` 的内容块，且工具名取 `this.tool?.name \|\| this.toolCall.name`（**回落到消息块里的 name**） | `frontend/node_modules/@mariozechner/pi-web-ui/dist/components/Messages.js:74`、`:88`、`:223` | **已核** |
| P26 | 流式容器另收一个 `toolResultsById`（由 `messages` 里的 toolResult 消息建图），即**结果从消息列表来、不从事件载荷来** | `client/main.ts:611`、`:776` | **已核** |

---

## 3. 被推翻的命题（我给用户的推荐依据）

写下来，因为**它们是这一版流程变更的直接理由**。

| # | 我原先说的 | 实测 | 后果 |
|---|---|---|---|
| **R1** | 「B28/B29 是 **web** 流式界面直接吃的」 | **错**：web 走 `AgentEventTranslator`，**完全不经过** `JsonEventMapper`（P17/P18） | B28/B29 是 **RPC 面**，与 web 无关 |
| **R2** | 「web 前端在工具执行期间拿不到工具名 / 拿不到结果」 | **半错**：名与结果前端**有**取法（P25 从 message 块取 name；P24 从 `turn_end.toolResults` 取结果）——但它们**都到不了**，因为 pi-java 在 ToolCall* 上不推 `message_update`（P17）、且 `turn_end` 无 `toolResults`（见 §4-A） | 真缺口是**推送时机**，不是载荷字段 |
| **R3** | 「给 `tool_execution_start` 补 `toolCallId`/`toolName`」 | **推翻为投机**：前端根本不读这三个事件的载荷（P23）；且 `docs/15:148` 原文就写着「载荷对齐前端 `tool_execution_*` 的空处理」。按本仓 C1/C9 的口径（「加发射是投机代码」）⇒ **不做** | 从范围里删掉 |
| **R4** | 「前端源码在**仓库外** `/d/workplaceForai/pi-webui`」 | **错**：权威位置是**本仓** `pi-java-web/src/main/frontend/`（我按 `maxdepth 3` 找、它在该深度之下）。⚠️ 结论未变 —— 两条 `main.ts` 的行为与 npm 组件版本一致（§2.3） | 只改出处，不改判定 |

> ⚠️ **流程教训（本文档是第一个按新流程写的）**：R1/R2/R3 三条都是**没读代码就断言**的产物。
> 新流程要求每条命题先落表、逐条核过，才允许进实施稿。

---

## 4. 审计：同一结构缺口还盖住了什么（本轮新发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **`turn_end` 不带 `toolResults`** —— pi-java 把 `AgentSettled` 翻成空 `{type:"turn_end"}`，而前端按 `toolCallId` 从 `turn_end.toolResults` 收工具结果 | `AgentEventTranslator:51-54` vs `client/main.ts:302-310` | **今天不可观测**（`agent_end.messages` 里含 toolResult 消息，整表替换兜住）⇒ **登记不修** |
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
| N5 | web 推 `message_update` 的帧量 | `ToolCallDelta` 与 `TextDelta` 同量级（都是每块一条），前端 `updateStreamingContainer` 的重绘频率因此与今天**文本流一致** ⇒ 不引入新的量级。⚠️ **未实测**：真实前端下的重绘成本没有测量手段，若日后可见卡顿，那是**新的一包**，不是本包能默认决定的 | `AgentEventTranslator:65-88`、`client/main.ts:279-282` |

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
| 4 | 改前端（`pi-java-web/src/main/frontend/`） | 渲染侧**已经就绪**（P25 会渲染 `toolCall` 块、名字回落到块里的 `name`）⇒ 本包只需补推送。改前端不在本包范围 |
| 5 | 动 `PiMessagesApi` 的**其它**缺口（B18 签名/redacted） | 那是它自己的规则，本包只传一个已有字段（§4-C） |

---

## 8. 实施稿（步骤 3 的产物；**待用户审核后才写代码**）

### 8.0 裁决点 —— 已裁（2026-09-19，用户「三个裁决点按照推荐方案来实施」）

| # | 裁决 | 后果 |
|---|---|---|
| **A** | **照 pi 抛**：读 `partial.content[contentIndex]`，不是 `ToolUseContent` 就抛 | N1 已核：`SessionEventHub` 逐个 listener 隔离 ⇒ 抛错 = 丢该客户端一帧 ＋ 一条 warning，**不丢连接** |
| **B** | 归一化落在 **`StreamEvent.UsageInfo.toUsage()`** | 「StreamEvent 变体 → 领域类型」的投影留在 `pi-java-ai`，与 `snapshot()`/`withUsage()` 同层 |
| **C** | **只改 mapper**（每帧兜零）；终局消息 `usage` 可空**另登记 B41** | 不动现有终局投影 |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-ai` | 核心：`emitToolCallStart(id, name)` ＋ 六个调用点 ＋ `FauxProvider` 桩同改 ＋ `UsageInfo.toUsage()` |
| **2** | `pi-java-coding-agent` | RPC 线：`JsonEventMapper` 的 `message_update` 恒写 `usage` ＋ `toolcall_start` 补 `id`/`toolName`（照抛） |
| **3** | `pi-java-web` | web 线：`AgentEventTranslator` 在 `ToolCallStart/Delta/End` 上**增加** `message_update` |
| **4** | `docs/33` | 实施记录（含实测红集、未覆盖、与稿子的偏差） |

### 8.2 精确改动

**(1) `StreamPartialBuilder.emitToolCallStart(String id, String name)`** —— 签名**改死**（不留无参重载：六个调用点全都拿得到值，留重载＝留一条「空身份」的路）。

```java
public StreamEvent.ToolCallStart emitToolCallStart(String id, String name) {
    toolArgBuf.setLength(0);
    toolCallId = id == null ? "" : id;
    toolCallName = name == null ? "" : name;
    toolBlockIndex = blocks.size();
    blocks.add(new ContentBlock.ToolUseContent(toolCallId, toolCallName, Map.of()));
    int idx = nextContentIndex++;
    return new StreamEvent.ToolCallStart(idx, snapshot());
}
```
块**先入 `blocks` 再取快照** —— 与 pi 同序（P4/P5：`anthropic-messages.ts:648-660`、`openai-completions.ts:503-521`）。
`Map.of()` 即 pi 起点的空 `arguments`。⚠️ 这同时**修好了一条隐性偏差**：`emitToolCallDelta` 从
`toolCallId`/`toolCallName` 重建块（`:339-342`），而 `toolCallName` 此前**只有 `emitToolCallEnd` 才写**
⇒ 整个参数流期间块上的 name 恒为空串。

**(2) 六个调用点**（P10 已核各自都持有值）：

| 文件:行 | 改法 |
|---|---|
| `AnthropicMessagesApi:193` | `emitToolCallStart(pendingToolId[0], pendingToolName[0])` |
| `GoogleGenerativeAiApi:188` | `emitToolCallStart(id, name)`（局部量已算出） |
| `MistralConversationsApi:214` | `emitToolCallStart(tcId, name)` |
| `PiMessagesApi:104` | `emitToolCallStart(s.id(), s.toolName())` ← **§4-C**：`PiMessagesEvent.ToolCallStart:46` 早有该字段 |
| `ResponsesStreamProcessor:199` | `emitToolCallStart(fc.callId(), fc.name())` |
| `ToolCallAccumulator:40` | `emitToolCallStart(id, name)`（字段，**可能为空串** —— P6：pi 同形） |

**(3) `FauxProvider:99`** —— 桩的起点块改成 `ToolUseContent(callId, toolName, Map.of())`（§4-D）。
⚠️ **`ScriptedStreams` 不动**（§4-F：改它＝改 L5 帧）。

**(4) `StreamEvent.UsageInfo.toUsage()`** —— 新增公开实例方法：

```java
/** 归一为领域类型（裁决 B）；无全量分解时按 input/output 合成（cache 0、cost 零）。 */
public Usage toUsage() {
    return usage != null ? usage : Usage.of(inputTokens, outputTokens);
}
```
`Usage.of` 已给出 `totalTokens = input + output` 与零 `cost` ⇒ 与 pi 的初值对象**同形**
（`cacheWrite1h`/`reasoning` 缺席，被 `@JsonInclude(NON_NULL)` 省略，pi 同）。

**(5) `JsonEventMapper` 的 `MessageUpdate` 支**：

```java
case AgentSessionEvent.MessageUpdate u -> {
    node.put("type", "message_update");
    // pi 恒写（json-event.ts:58）：流起点 usage 就是零值对象（anthropic-messages.ts:518-525）
    // ⇒ 缺 UsageInfo 时兜零，**不省键**（这与 B41 的终局口径不同，见裁决 C）。
    var partial = u.streamEvent().partial();          // ⚠️ UsageInfo 的 partial 可为 null
    var info = partial == null ? null : partial.usage();
    node.set("usage", MAPPER.valueToTree(info == null ? Usage.of(0, 0) : info.toUsage()));
    node.set("assistantMessageEvent", assistantMessageEvent(u.streamEvent()));
}
```
新增私有方法（剥 partial 由既有 mixin 完成，此处只补身份）：

```java
private static ObjectNode assistantMessageEvent(StreamEvent event) {
    var delta = (ObjectNode) MAPPER.valueToTree(event);
    if (event instanceof StreamEvent.ToolCallStart start) {
        var content = start.partial().content();
        int index = start.contentIndex();
        if (index < 0 || index >= content.size()
                || !(content.get(index) instanceof ContentBlock.ToolUseContent toolCall)) {
            throw new IllegalStateException(
                "toolcall_start content at index " + index + " is not a tool call");
        }
        delta.put("id", toolCall.id());
        delta.put("toolName", toolCall.name());
    }
    return delta;
}
```
键序与 pi 同（`type, usage, assistantMessageEvent`；身份键缀在增量字段后）。

**(6) `AgentEventTranslator` 的三支** —— **增加**而非替换（三个 `tool_execution_*` 保留：既有测试
`AgentEventTranslatorTest:53-59` 断言它们、`docs/15:148` 有意为之、且补 message_update 正是前端真正会渲染的那条）：

```java
case StreamEvent.ToolCallStart s -> {
    out.add(messageUpdate(s.partial()));
    out.add(new WebServerMessage.AgentEvent(typeNode("tool_execution_start")));
}
case StreamEvent.ToolCallDelta d -> { out.add(messageUpdate(d.partial())); out.add(...update...); }
case StreamEvent.ToolCallEnd e   -> { out.add(messageUpdate(e.partial()));   out.add(...end...); }
```
`messageUpdate(partial)` 走既有 `WebWireJson.assistantNode`（P18：toolCall 块的投影**已经存在**）。

### 8.3 测试计划（**先红**）

| 模块 | 新夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-ai` | `StreamPartialBuilderToolIdentityTest` | 3 | 起点事件的 `partial.content[idx]` **就是** `ToolUseContent(id, name, {})`；未知 id/name 传空 ⇒ 块上是空串（pi 同形）；随后 `emitToolCallDelta` **不丢** name（回归上面那条隐性偏差） |
| `pi-java-coding-agent` | `JsonEventMapperMessageUpdateTest` | 4 | ① `usage` **恒在**，取值 = partial 的 usage 归一化；② 无 usage ⇒ **零值对象**（四个计数 + `totalTokens:0` + 零 `cost`）；③ `toolcall_start` 带 `id`/`toolName`（**夹具走 `StreamPartialBuilder` 造帧** —— 两个桩都不满足 pi 的读法，§4-F）；④ 块不是 toolCall ⇒ **抛** |
| `pi-java-web` | `AgentEventTranslatorTest` 扩 | 2 | ⑤ `ToolCallStart/Delta/End` 各**多**产生一条 `message_update`，且其中 `message.content[]` 含 `{type:"toolCall"}`；⑥ 三个 `tool_execution_*` **仍在**（不回归） |

**先红**：夹具按目标形状写，实施前先跑一次并**记录红集**（本仓纪律：写下注入点的那一刻就跑）。
**既有测试**：`JsonEventMapperTest:15-27`（断言 partial 被剥）与 `AgentEventTranslatorTest:53-59`
**应当保持绿** —— 前者是反向断言，后者被第 6 条刻意保留。`StreamPartialBuilderTest` 会因签名变更
**编译失败** ⇒ 机械更新调用点（不算行为改动）。

### 8.4 变异探针设计（**红集不预测，跑完实测记录** —— 包⑤ 起的口径）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | mapper 的 `usage` 去掉（回到今天） | ①② 有牙 |
| P2 | `toolcall_start` 不补 `id`/`toolName` | ③ 有牙 |
| P3 | 抛换成写空串 | ④ 有牙 |
| P4 | `emitToolCallStart` 不 seed 块（回到空占位） | ①③ 有牙 |
| P5 | web 不推 `message_update`（回到今天） | ⑤ 有牙 |

### 8.5 不做（汇总，理由见 §6）

补 `tool_execution_*` 载荷 · 修 `tool_execution_end` 语义 · 补 `turn_end.toolResults` ·
改前端 · 改 `ScriptedStreams` · 动终局消息的 `usage`（B41） · 动 `PiMessagesApi` 的其它缺口。

### 8.6 未覆盖（预登记，实施后如数）

- **端到端**：真实 provider 的 `toolcall_start` 帧未测（要 provider）；夹具走 builder 造帧。
- **真实前端**：`updateStreamingContainer` 的重绘成本未测（N5）—— 判据沉默，不假装测过。
- **分块到达**（首块只有 id、name 稍后）：pi-java 的 `ToolCallAccumulator` 与 pi 同形，
  但**没有**夹具造这个序列（今天没有可见差异可钉）。

---

## 9. 下一步

用户审核 §8 之后才写代码；实施完成后在 `docs/33` 追加**实施记录**（含实测红集、偏差、未覆盖如数）。

---

## 10. 实施记录（2026-09-19/20，用户「包6审核通过，开始实施」）

**结论：§8 实施稿四步全部落地；五个变异探针全部有牙；全树 `clean verify` 绿。**
新增 17 条断言（稿子计划 9 条），既有测试零回归。

### 10.1 四步与改动面

| 步 | 模块 | 改动 | 状态 |
|---|---|---|---|
| 1 | `pi-java-ai` | `emitToolCallStart(String,String)` 签名改死 ＋ 六个调用点 ＋ `FauxProvider` 桩 ＋ `UsageInfo.toUsage()` | ✅ |
| 2 | `pi-java-coding-agent` | `JsonEventMapper`：`message_update` 恒写 `usage` ＋ 新私有 `assistantMessageEvent(StreamEvent)` 给起点补身份（照抛） | ✅ |
| 3 | `pi-java-web` | `AgentEventTranslator`：三个工具增量各**增加**一条 `message_update` | ✅ |
| 4 | 本文件 | 本节 | ✅ |

改动面（`git diff --stat`）：主源码 12 文件、+124/−17；新夹具 3 文件。
**`ScriptedStreams` 未动**（§4-F：改它＝改 L5 帧）；既有 `AgentEventTranslatorTest`
（11 条）与 `JsonEventMapperTest`（12 条）**零改动、全绿**。

### 10.2 实测红集（先红）

**步骤 1 —— 红是「编译失败」，不是断言红**（如实记）：
`StreamPartialBuilderToolIdentityTest.java:28,44,58,79`，四条全是
`method emitToolCallStart ... cannot be applied to given types`（`required: no arguments`）。
签名不存在时夹具**无法编译**，故这一步拿不到断言级红 —— 这是本包唯一一处
「先红」打了折的地方。

**步骤 2 —— `JsonEventMapperMessageUpdateTest`：9 跑 7 红**
- ERROR（NPE）：`usageInfoEventWithoutPartialStillGetsZeroUsage`、
  `toolcallStartCarriesIdAndToolNameFromThePartialBlock`、
  `usageNormalizationPrefersTheFullBreakdownOverTheCounts`
- FAILURE：`messageUpdateAlwaysCarriesTopLevelUsage`（`Expecting actual not to be null`）、
  `messageUpdateWithoutUsageWritesZeroValuedObjectNotMissingKey`（同）、
  `toolcallStartThrowsWhenTheBlockIsNotAToolCall`（`Expecting code to raise a throwable`）、
  `toolcallStartWireKeysAreOrderedTypeUsageAssistantMessageEvent`
- 恒绿 2 条：`nonToolCallStreamEventsKeepTheirOwnFields`、
  `toolcallDeltaIsUntouchedByTheIdentityRule`（反向确认，本来就该绿）

**步骤 3 —— `AgentEventTranslatorToolCallVisibilityTest`：4 跑 3 红**
`:56` `:75` `:91`（`containsExactly` 序列不符，今天只有一条 `tool_execution_*`）；
反向断言 `textDeltaStillPushesExactlyOneMessageUpdate` 恒绿。

### 10.3 变异探针（**红集实测，不预测**）

| # | 改坏什么 | 实测红集 | 条 |
|---|---|---|---|
| **P1** | mapper 去掉顶层 `usage` | `messageUpdateAlwaysCarriesTopLevelUsage:49`、`messageUpdateWithoutUsageWritesZeroValuedObjectNotMissingKey:66`、`toolcallStartWireKeysAreOrderedTypeUsageAssistantMessageEvent:140`、`usageInfoEventWithoutPartialStillGetsZeroUsage:165`(NPE)、`usageNormalizationPrefersTheFullBreakdownOverTheCounts:153`(NPE) | **5** |
| **P2** | 起点不补 `toolName`（写空串） | `toolcallStartCarriesIdAndToolNameFromThePartialBlock:86` | **1** |
| **P3** | 抛换成写空串 | `toolcallStartThrowsWhenTheBlockIsNotAToolCall:97` | **1** |
| **P4** | 核心不 seed 块（回空占位） | ai：`toolCallStartIndexPointsAtTheSeededBlock:86`、`toolCallStartSeedsIdentityBlockBeforeSnapshot:33`；coding：`toolcallStartCarriesIdAndToolNameFromThePartialBlock:85` | **3**（跨模块） |
| **P5** | web 不推 `message_update` | `toolCallStartAlsoPushes…:56`、`toolCallDeltaAlsoPushes…:75`、`toolCallEndAlsoPushes…:91` | **3** |

**P1 与 P4 都比稿子预测的更大**（稿子 P1 猜「①②」＝2 条，实测 5 条；P4 猜「①③」＝2 条，
实测跨模块 3 条）。这正是「红集实测、不预测」这条口径的价值 —— 预测的两个都偏小。

### 10.4 与稿子的偏差（如实）

| # | 稿子写的 | 实际做的 | 理由 |
|---|---|---|---|
| **D1** | §8.2(6)：`message_update` 加在 `tool_execution_*` **之前** | **之后** | 两条理由同向：① 既有夹具 `AgentEventTranslatorTest:53-59` 断言 `get(0)` 的类型，后置可零回归（§8.3 要求它保持绿）；② 前端的 `message_update` 是**命令式** `updateStreamingContainer`（`client/main.ts:302-305`），而 `tool_execution_*` 会 `renderApp()` 重渲染 —— 命令式那次放最后才不会被随后的重渲染覆盖。已写进代码注释 |
| **D2** | §8.3：步骤 3「`AgentEventTranslatorTest` **扩**」 | 另起 `AgentEventTranslatorToolCallVisibilityTest` | 让红集无歧义，且不动既有绿文件（该文件 11 条零改动） |
| **D3** | §8.3 计划 9 条断言 | **17 条**（ai 4 ＋ mapping 9 ＋ web 4） | 每条都配反向断言；多出的是「键序」「非工具增量不被污染」「`UsageInfo` 无 partial 不 NPE」「文本路径不翻倍」 |
| **D4** | 步骤 1「先红」 | 只能拿到**编译失败** | 签名变更使夹具无法编译；如实记为打折处 |

### 10.5 未覆盖（如数）

- **端到端**：真实 provider 的 `toolcall_start` 帧未测（要 provider）；夹具走
  `StreamPartialBuilder` 造帧 —— 这正是为了避开两个桩的非 pi 形状（§4-F）。
- **真实前端**：`updateStreamingContainer` 的重绘成本未测（N5）—— 判据沉默，不假装测过。
- **分块到达**（首块只有 id、name 稍后）：pi-java 的 `ToolCallAccumulator` 与 pi 同形，
  但**没有**夹具造这个序列（今天没有可见差异可钉）。
- **D1 的第 ② 条理由是推断，不是实测**：`renderApp()` 是否会覆盖命令式设的消息，
  我没有前端运行手段去证；第 ① 条（既有夹具）是**编译期/运行期可证**的硬理由。
  如实标注：D1 的选择由硬理由独立成立，②只是同向的加分项。
- **两处归一化并存**：`Message.AssistantMessage.usageOf`（终局，null⇒null）与
  `UsageInfo.toUsage()`（每帧，调用方兜零）现在各有一份合成逻辑。合并会改变终局投影
  ⇒ 按裁决 C 不动，差额登记 B41。

### 10.6 顺带观察（未修，登记）

- **`PiWebServerAuthTest.acceptsConnectionWithValidToken:97` 首跑全量 web 套件时红过一次**
  （15 s 内未收到 `ready` 帧）。随后：单跑 1 次绿、全量 2 次绿 ⇒ 判为**非必现**。
  **我没有证明它与本包无因果**，只证明了不可复现；与本次改动无直接关联（该测试不碰
  `AgentEventTranslator`），不擅自归因、不在本包修。
- **`JsonEventMapper` 现在对自洽性有硬要求**：`toolcall_start` 的 `partial.content[index]`
  必须是 `ToolUseContent`，否则抛（照 pi）。生产侧**全部六个调用点都经
  `StreamPartialBuilder`**，块先入后取快照 ⇒ 结构上恒满足。但**测试里手搓
  `ToolCallStart` 的桩**（`ScriptedStreams:85`、`EngineFailureSettlementTest:94`、
  `PiLoopTest:92`、`AgentSessionToolIntegrationTest:54`、`SessionFailurePathTest:101`）
  形状不合 pi 的读法 —— 它们今天不流经 mapper（L5 走帧级差分、不经 RPC 线），
  全树绿即证。**若日后有新的 RPC 夹具手搓起点事件而不建块，会当场抛** —— 这是
  pi 的语义（照抛），不是缺陷。

### 10.7 全树验证 —— 先红后绿：卡在存量 spotbugs，用户裁决「真修」后 **全树通**

**第一次 `mvn -o clean verify`（15:10）**：reactor 走到 `pi-java-tui` 时
`spotbugs:check` 报 **4 bugs**，其后 7 个模块（protocol/client/server/web/evals/dist）
被 **SKIPPED** ⇒ 全树未通。

```
Medium: Operation on the "runToolCalls" shared variable is not atomic
        [ChatScreen] At ChatScreen.java:[124] AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE
Medium: Shared primitive "assistantStreamed" ... [ChatScreen.java:92, :109]
        AT_STALE_THREAD_WRITE_OF_PRIMITIVE
Medium: Shared primitive "thinkingRendered" ... [ChatScreen.java:93, :122] 同上
Medium: Shared primitive "runToolCalls" ... [ChatScreen.java:151] 同上
```

**归属（可证）**：本包工作树在 `pi-java-tui/` 下 **0 个改动文件**
（`git status --porcelain -- pi-java-tui/ | wc -l` = 0）⇒ 这 4 条是 **HEAD 上的存量**，
与包⑥ 无因果。字段来源也是实证的：`assistantStreamed` 出自 `d1e8d3a`、
`runToolCalls` 出自 `b378b20`（2026-08-16 及更早），被点名的写点全在
`onStreamEvent` —— 那是**包⑤ 之前**就存在的老路径（包⑤ 加的是 `onSessionEvent`）。

**诚实交代**：**我不知道此前为何没被拦住**。`spotbugs:check` 确实绑在默认 `verify`
（根 `pom.xml:172-180`），且 `b378b20` 已是一个多月前 ⇒ 这期间任何一次
`clean verify` 都应当同样失败。我没去追这条时间线，只把「它不是本包引入的」
证到 `git` 级。

**用户裁决（2026-09-20）：真修（3 处，最小）** ⇒ 落地：

| 字段 | 改法 | 为什么这个改法 |
|---|---|---|
| `assistantStreamed` | `volatile boolean` | 单 boolean 读写无竞态，只缺可见性 |
| `thinkingRendered` | `volatile boolean` | 同上 |
| `runToolCalls` | `AtomicInteger`（`incrementAndGet`/`set`/`get`） | **不能只加 volatile** —— `++` 是读-改-写，volatile 不保证原子性（spotbugs 原文的 `AT_NONATOMIC_…`）。`finishRun` 顺带把两处取值收成**一次读**（标签里 "N calls" 与单复数判定必须一致） |

零语义改动。**未给并发夹具** —— TUI 已按要求降级，且本仓口径认定这类时序断言是
「运气断言」（§8.23.8 ⑥）。tui 模块验证：**`BugInstance size is 0`**、209 测试全绿。

**修后全树 `mvn -o clean verify`：BUILD SUCCESS** —— 14 个模块全部 SUCCESS
（TUI 03:56、Web 01:22、Coding Agent 54.7s），**11 处 `spotbugs:check` 全部
`BugInstance size is 0`**，checkstyle 零违规。

**包⑥ 自己范围的验证（修前绕开 tui 门禁单独跑）**：
`mvn -o clean verify -pl pi-java-protocol,pi-java-client,pi-java-server,pi-java-web,
pi-java-evals,pi-java-coding-agent -am`（**spotbugs 不跳**；只有 `pi-java-dist`
依赖 tui，见 `pi-java-dist/pom.xml:28`）⇒ 同样 BUILD SUCCESS，说明包⑥ 的改动
在 tui 门禁之外**独立成立**。

模块计数（实测）：telemetry 31 · **ai 444**（＋4）· **agent-core 473** ·
session-sqlite 35 · **coding-agent 242**（＋9）· **web 41**（＋4）· tui 209；
L5 差分 `ConformanceTest` **14/14** 绿（`ScriptedStreams` 未动，golden 完好）。
