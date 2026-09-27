# 55 - C 批次：终局事件载荷（`done` / `error`）设计

**状态：已闭环（2026-09-27；R1–R9 全按建议实施，`3684a82`/`b8d7e48`/`882fcaa` ＋ docs，实施记录见 §12）**

> 本包解决 `docs/48` **设计门 5**：「终局事件是否扩展现有 sealed record，是否保留旧访问器；
> 完整 assistant message 如何避免重复构造和不一致」。
> ⚠️ 实施期推翻了本文件的两处结论（`§5 F4` 的后果判断、`§9.1 E8` 的预测）——
> 两处**已在原位标注并指向 §12.4**，别照旧结论行事。

**参考台账：** `docs/48 §2`（A-05）、`docs/41 §1.4`（`done` 权重 3 / `error` 权重 2）、`docs/32`、`docs/07 §Phase2a`
**参考设计：** `docs/31`（agent 宿主层）、`docs/49`–`docs/54`（A 批次各包）
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（本文件所有行号均指该锚点）

---

## 1. 范围

**做**：`AssistantMessageEvent` 两个终局变体（`done` / `error`）的**载荷契约**，以及把它送进
provider / agent / 宿主 / 线上的全部生产点与消费点 —— 即 `docs/48 §5` 的 C1、C2、C3 三行。

**不做**（各自单独裁决，见 §10 登记）：

- `reason` / `stopReason` 的**词表**（java 的 `"tool_use"` vs pi 的 `"toolUse"`，§10 B109）；
- `providerThinkingLevel`（pi 的消息字段与 proxy 线字段，java 两侧都没有）⇒ B110；
- `deferred` 终局（pi `done.reason` 含 `"deferred"`，java 无生产者）⇒ B111；
- 宿主失败通路的**合成消息**（`RunFailure`，B5 已闭环，本包只确认不破坏）；
- `StreamSimple` / `ContextEstimator` 那条**零生产者**的旧路（§10 B112，A-20 类）。

**验收判据**（沿用本仓既定口径）：**行为等价** —— 对同一个 provider 结果，java 的终局承载物与 pi 的
`done.message` / `error.error` **逐字段同值**（差异只许是本文件 §5/§10 明确登记的那些）。

---

## 2. 观测面与术语

同一个字段在本仓有**三个互不相同的观测面**（A-01 的教训，`docs/54 §12.3`），本包逐条区分：

| 面 | 读法 | 本包相关物 |
|---|---|---|
| ① SDK 对象 | `event.partial()` / `err.error()` | `StreamEvent` 的访问器 |
| ② 序列化 JSON | `JsonEventMapper.toWire/ toStreamEventWire` | `message_update.assistantMessageEvent` |
| ③ 录制字节 | RPC/JSON 模式下真实写到 stdout/WebSocket 的字节 | 同上 |

**术语**：

- **累加器**（pi 的 `output`）：一条流里**同一个可变对象**，从 `start` 起每帧都作为 `partial` 被推出去，
  终局时**就地改写**并作为终局载荷推出（`anthropic-messages.ts:522-539`、`:596`、`:815`）。
- **落定**（settlement）：把终局的 `stopReason` / `errorMessage`（必要时的 `usage`）写进**累加器本身**
  （pi `:823-824`），使「消息」而不是「某个旁路字段」成为终局判定的唯一载体。
- **终局载荷**：`StreamDone.partial` / `StreamError.partial`（java）≙ `done.message` / `error.error`（pi）。

---

## 3. pi 侧契约（逐条，来源＝锚点源码）

| # | 事实 | 出处 |
|---|---|---|
| P1 | 两个终局变体都携带**一条完整 `AssistantMessage`**：`{type:"done"; reason: "stop"\|"length"\|"toolUse"\|"deferred"; message}`、`{type:"error"; reason: "aborted"\|"error"; error}` | `ai/src/types.ts:652-668` |
| P2 | 终局是**累加器本身**，不是另建的快照：`anthropic-messages.ts` 全流每一帧都是 `partial: output`（含 `start`），成功路 `push({type:"done", reason: output.stopReason, message: output})` | `anthropic-messages.ts:596`、`:815-816` |
| P3 | 失败路**就地落定同一个对象**：先删块的流式草稿字段（`index` / `partialJson`），再 `output.stopReason = aborted? "aborted":"error"`、`output.errorMessage = error.message`，再 `push({type:"error", reason, error: output})` | `anthropic-messages.ts:817-826` |
| P4 | **内容不丢**：`error.error.content` 是流到故障点为止已累积的块；`faux` 的中止路 `createAbortedMessage(partial)`＝`{...partial, stopReason:"aborted", errorMessage:"Request was aborted", timestamp: now}` | `providers/faux.ts:321-330`、`:349-352` |
| P5 | **usage 恒非 null**：累加器初值就是零值 `Usage`（含 `cost` 全零），`stopReason` 初值 `"pending"` | `anthropic-messages.ts:522-539` |
| P6 | **不抛**：`AssistantMessageEventStream` 的 `isComplete = done\|error`、`extractResult = message\|error` ⇒ `result()` 对两种终局**都 resolve**；非流式 `complete()` 就是 `stream(...).result()` | `utils/event-stream.ts:92-103`、`compat.ts:269-276` |
| P7 | agent 循环**两种终局一视同仁**：`const finalMessage = await response.result()` ⇒ 写进 `context.messages`、发 `message_start`（若先前没发）/ `message_end`、**作为本轮返回值** | `agent/src/agent-loop.ts:399-412` |
| P8 | ⇒ **失败消息进转录**：宿主在 `message_end` 上 `appendMessage` ⇒ 错误/中止的助手消息**落盘** | `coding-agent/src/core/agent-session.ts:706-724` |
| P9 | 宿主只读**消息**：打印模式读尾条 assistant，`stopReason ∈ {error, aborted}` ⇒ stderr 打 `errorMessage`、退出码 1 | `modes/print-mode.ts:139-155` |
| P10 | **缺终局 ⇒ 错**，不是「静默成功」：proxy 对「干净 EOF 但没有 done/error」判 error（"Connection closed … before the response completed"）；五条车道各自对「没有 stop reason」抛错 | `agent/src/proxy.ts:236-247`；java 侧对应断言见 §4 J12 |
| P11 | 错误上下文**不进下一次请求**：`error`/`aborted`/`deferred` 的助手消息投影为空（java 已逐字移植，落在 `ContextEntries`） | `harness/session/context.ts` `isContextMessage`（`docs/31` 已核）；java `ContextEntries:50-51` |
| P12 | 宿主级失败（pass 抛出）另有**合成消息**：`content:[{type:"text",text:""}]`、`stopReason=aborted\|error`、`errorMessage`、`usage=EMPTY_USAGE`，走 `message_start→message_end→turn_end→agent_end` | `agent/src/agent.ts:526-542` |
| P13 | **宿主线（JSON/RPC）不发终局帧**：`message_update` 只在非终局增量上产生（循环的 `case` 分支），线上还**剥掉 `partial`**，其文档写明「`message_end` 提供最终权威消息」 | `modes/json-event.ts:33-39`、`:55-70`；`agent-loop.ts:380-398` |
| P14 | **proxy 协议**（另一份独立实现，同一契约）把终局载荷**磨平**成 `{type:"done"; reason; usage; providerThinkingLevel?}` / `{type:"error"; reason; errorMessage?; usage; providerThinkingLevel?}`（带宽考虑剥 `partial`），收到后**再落定回 partial** 并推完整事件 | `agent/src/proxy.ts:42-58`、`:383-400` |
| P15 | 帧（`AssistantMessageFrame`）类型**显式排除终局**：「Terminal settlement is intentionally excluded and must be persisted separately」 | `ai/src/utils/assistant-message-frame.ts:4-7`、`:144-158` |

### 3.1 执行证据（2026-09-27 实测，逐字）

手法：抄 pi 自己的 `compat.streamSimple` / `complete` ＋ `registerFauxProvider`（脚本 `/tmp/pic/full.mjs`），
6 条全部跑通、**零抛出**：

```
P1.types        = ["start","text_start","text_delta","text_end","done"]
P1.reason       = "stop"
P1.message      = {"role":"assistant","content":[{"type":"text","text":"hello world"}],
                   "api":"faux:…","provider":"faux","model":"faux-1",
                   "usage":{"input":2,"output":3,"cacheRead":0,"cacheWrite":0,"totalTokens":5,
                            "cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},
                   "stopReason":"stop","timestamp":1790449754298}
P1.startIsDone  = false      ← faux 特例，见下
P1.resultIsDone = true       ← result() 与 done.message 同一个对象
P2.reason       = "toolUse"  msg.stopReason= "toolUse"     ← 注意大小写
P3.types        = ["start","text_start","text_delta","text_end","error"]
P3.thrown       = no
P3.error        = {"role":"assistant","content":[{"type":"text","text":"boom"}],…,
                   "stopReason":"error","errorMessage":"kaboom",…}   ← 内容保留 ＋ 文本在消息上
P3.result       = "error" "kaboom"                                 ← result() resolve
P4.reason       = "aborted"
P4.stopReason   = "aborted" errorMessage= "Request was aborted"
P4.content      = [{"type":"text","text":"alpha bravo charlie delta echo f"}]  ← 流到中止点的文本
P4.resultStop   = "aborted" content= [同上]
P5.complete     = returned "error" "kaboom" [{"type":"text","text":"boom"}]    ← 不抛
P6.types        = ["error"]
P6.thrown       = no
P6.error.msg    = "Connection error."
P6.error.stop   = "error" content= []
P6.error.usage  = {…全零对象…}                                      ← 非 null
P6.error.ident  = {"api":"anthropic-messages","provider":"anthropic","model":"claude-sonnet-4-5"}
```

**实测校正一处、补记一条**：

1. **`P1.startIsDone = false`** —— ⚠️ **不要**从 faux 推出「pi 的 partial 与终局共用对象」。
   faux 是**特例**：它的中间帧推 `{...partial}` **副本**，终局推**排队的那个 spec 对象**
   （`faux.ts:361-430`）。**真车道**才共用同一个 `output`（`anthropic-messages.ts:596` ↔ `:815`，
   §3 P2 的出处）。⇒ 本设计**不依赖对象同一性**做判据（java 的 record 本来就不可变），
   只依赖**字段同值**。
2. **补记 P16**：`usage` 里的 `cost` 在 faux 是零对象、真实车在故障路也是零对象 ⇒
   「错误载荷的 usage 非 null」这一条**两条路都成立**（P6 实测）。

> 本节 15 条 + 上述 2 条已全部有执行证据；`%TEMP%/pic/p.mjs`、`p2.mjs`、`full.mjs` 为脚本原件。

---

## 4. Java 侧现状（逐条）

| # | 事实 | 出处 |
|---|---|---|
| J1 | 两个终局 record：`StreamDone(String reason, UsageInfo usage, AssistantMessage partial)`、`StreamError(String reason, Throwable error, AssistantMessage partial)` | `StreamEvent.java:222`、`:231` |
| J2 | **`partial` 组件就是 pi 的 `message`/`error` 承载物**（同形、同语义位），只是名字与来源不同 | `StreamEvent.java:50` |
| J3 | 车道侧唯一出口是 `StreamPartialBuilder.emitDone/emitError`；二者**都只写 `stopReason`**，`emitError` **不写 `errorMessage`** | `StreamPartialBuilder.java:378-387` |
| J4 | 出口缝 `AbstractChatApi.IdentitySubscriber` 挂身份（api/provider/model/timestamp）＋ `withTerminalUsage` 补计量（`StreamError` ⇒ 兜零） | `AbstractChatApi.java:170-181`、`:215-226` |
| J5 | **安全网把「缺终局」伪装成成功**：`onComplete` 推 `StreamDone("stop", null, identityBase)` —— 空内容、无 usage、reason 为 `"stop"` | `AbstractChatApi.java:108-116` |
| J6 | **非流式 `send()` 吞错**：遇 `StreamError` 只 `break`，随后返回**空内容、无 stopReason、无 errorMessage** 的 identityBase 消息（`blocks` 累加器从不被填充）；另把 plumbing 异常包成 `PiHttpException` | `AbstractChatApi.java:122-141` |
| J7 | 多条生产者在错误路传**空 partial**：`AssistantMessage.empty()`（`QueueStreamIterator:57/:86`、`SessionRunner:115/:181`、`FauxProvider:114`）或 identityBase（`AbstractChatApi:105`）⇒ **流到故障点为止的内容被丢掉** | 各处 |
| J8 | `QueueStreamIterator.close()` 推 `StreamDone("aborted", null, empty)`；`abort(cause)` 推 `StreamError("aborted", cause, empty)` | `QueueStreamIterator.java:62-67`、`:81-87` |
| J9 | agent 循环替生产者**补形状**：`withErrorShape` 把 `err.reason()` / `err.error().getMessage()` 回填到消息上（partial 自带值优先）；`markAborted` 把「没观测到终局」折成 `"aborted"` | `PiLoopRunner.java:354-370`、`:323-339` |
| J10 | 另外**五处**消费者改从 `Throwable` 取文本：`LlmSummaryGenerator:250-252`、`PrintMode:99-102`、`ChatScreen:135-142`、`AgentEventTranslator:209-215`、`PayloadRecordingStreamFn:194-201` | 各处 |
| J11 | 宿主侧 `SessionRunner` 把**任何** `StreamError` 记成 `"error"`，**忽略 `err.reason()`** ⇒ `aborted` 被塌成 `error`（与 J9 的 `withErrorShape` 保留 reason 自相矛盾） | `SessionRunner.java:70-72` |
| J12 | 缺终局的另一处判据是**对的**：`PiLoopRunner:276-280` 在既无终局事件、又无 abort 时抛 `IllegalStateException("stream produced no terminal message")` | `PiLoopRunner.java:276-280` |
| J13 | 宿主线**会**发终局帧，且把 `Throwable` **原样序列化**：`SessionRunner:82` 对**每一个**流事件发 `AgentSessionEvent.MessageUpdate`；`JsonEventMapper.assistantMessageEvent` 用 `valueToTree(event)`（mixin 只忽略 `partial`） | `SessionRunner.java:82`、`JsonEventMapper.java:59-71`、`:363-376` |
| J14 | 现有夹具**零覆盖**：`JsonEventMapperMessageUpdateTest` 8 条全部只测 `text_delta` / `toolcall_*` / `usage`，**没有一条**断言终局帧的线形状 | 该文件全文 |

**这套 Throwable 承载是 Phase 2a 的设计，不是移植偏差**：`docs/07-phase2a-agent-loop-design.md:45`
「错误编码进流不 throw：LLM 错误 → `StreamError` 事件 → `stopReason:"error"`」，
`:141` 给出的字面量就是 `record StreamError(String reason, Throwable error, AssistantMessage partial)`。
即：**「错误不进异常」这半条对齐了 pi，「错误载荷长什么样」这半条当年没锚 pi**。

---

## 5. 差距清单（每条：pi / java / 用户可见后果 / 观测面）

### F1（P0）错误载荷丢内容 —— **流到故障点为止的文本/思维/工具块被丢弃**

- **pi**：`error.error.content` ＝ 累积到故障点的块（P3/P4）。
- **java**：J7 的四条生产者传**空消息** ⇒ `PiLoopRunner.withErrorShape` 拿到空 partial，转录里落一条**空**的错误消息。
- **后果**：网络中断/限流/中止后，界面上「模型刚说的话」整段消失；pi 保留（`print-mode` 会把它打出来）。
- **面**：① `err.partial().content()`；② 转录 JSONL 的 `content`；③ 尾条 assistant。

### F2（P0）错误文本不在消息上 —— `errorMessage` 由**五个下游**各自从 `Throwable` 反推

- **pi**：`output.errorMessage` 在车道就地写（P3）；下游（宿主、打印模式、扩展）只读消息。
- **java**：J3 不写；J9 的 `withErrorShape` 补一处，J10 的五处各读 `Throwable`。
- **后果**：① 未过 `withErrorShape` 的路（`send()`、`LlmSummaryGenerator` 之外的宿主路、旧转录）文本为空；
  ② `SessionJson:115-116` 只在非 null 时写键 ⇒ 落盘缺 `errorMessage`；
  ③ 3d 的重试分类器依赖非空文本，靠补丁而非不变量成立。
- **面**：① `partial.errorMessage()`；② 转录键；③ 线上 `agent_settled` / `message_update`。

### F3（P0）**缺终局被报成成功**（J5）

- **pi**：缺终局 ⇒ error（P10）。
- **java**：`onComplete` 安全网推 `StreamDone("stop", null, identityBase)` ⇒ 一次**空回答的成功轮次**。
- **后果**：`Publisher` 丢掉车道终局帧（该注释自陈的竞态）时，会话记录一次「成功的空响应」而不是错误；
  重试环、压缩阈值、退出码全部按成功走。
- **面**：① 终局事件类型与 reason；② 转录 stopReason；③ 退出码。

### F4（P1）`aborted` 在宿主状态里被塌成 `error`（J11）

> ⚠️ **本条的后果判断在实施期被实测推翻**：`SessionRunner` 末尾的**读尾**会覆盖这个值，
> 而流错误必然产生一条 `aborted` 的尾条 ⇒ 那处写法的值今天**不可观察**（变异探针 M4 零红）。
> 改动仍按 R 侧实施（正确读法 ＋ 去掉同仓两套口径），但它是**一致性**修复不是行为修复。
> 详见 **§12.4-1** 与新登记 `docs/32` **B117**。

- **pi**：两者语义不同（打印模式两者都退 1，但消息与状态保留 `aborted`；P9/P12）。
- **java**：`SessionRunner` 忽略 `err.reason()`；而 `PiLoopRunner`/`RunFailure` 保留 ⇒ **同仓两套口径**。
- **后果**：`RunStatus.status()`、run summary、宿主报告里中止与失败不可分。
- **面**：`RunStatus` / run summary / RPC。

### F5（P1）`send()` 吞错（J6）

- **pi**：`complete()` 返回**落定后的消息**（P6），调用方读 `stopReason`/`errorMessage`。
- **java**：返回空消息（无 stopReason、无文本），错误静默消失。
- **后果**：非流式 SDK 面上错误不可见。**今天生产可达性低**（只有 `AiCli:173` 与 conformance 套件用 `send`）⇒ 定 P1。
- **面**：`send()` 返回值。

### F6（P0，宿主线）终局帧把 `Throwable` 原样落线，且 pi 线上根本没有这种帧（J13 + P13）

- **pi**：JSON/RPC 线只发非终局增量，并剥 `partial`（P13）；终局权威消息在 `message_end` / `agent_end`。
- **java**：每个流事件（含终局）都发 `message_update`，终局帧的 `assistantMessageEvent` 里
  `error` 键是一个 Jackson 序列化的 `Throwable`（`cause`/`stackTrace`/`message`/…）⇒ **java 栈帧上了对外协议**。
- **后果**：RPC/JSON 客户端收到 pi 不会发的帧，且载荷含 JVM 内部结构；客户端按 pi 契约解析会踩空。
- **面**：② 序列化 JSON、③ 录制字节。**零夹具覆盖（J14）。**

### F7（P2）`StreamDone.usage` 的**嵌套包裹**是 java 方言（J1）

pi 的 usage 只在消息上（P5）；proxy 线另发一个**扁平** `usage`（P14）。
java 的 `done.usage()` 是 `UsageInfo`（该类型自带 `partial` 组件）⇒ 同一件事在 java 有两个载体，
且 `withTerminalUsage` 是「事件 usage 优先、否则兜零」这一层多余逻辑的**唯一**存在理由。

### F8（P2）`overflow` 这个 stop reason 是 java 自造（无生产者）

`StreamSimple:62-70` 造 `stopReason:"overflow"`；该路**零生产调用者**（只有测试），
且它**不在** `NON_PROJECTED_STOP_REASONS` 里（P11）⇒ 若哪天接线，被截断的响应会被投影进下一次请求。
pi 无此取值。归 A-20 类清理（B112）。

---

## 6. 目标契约与落点

### 6.1 契约（写进 `StreamEvent` 的 javadoc，逐条对上 pi）

1. **终局载荷＝落定的累加器**：`done.partial` / `err.partial` 是**同一条消息**，其
   `stopReason` **必为落定值**（非 `"pending"`、非 null）、`usage` **非 null**；
   错误路另有 `errorMessage` 非空文本，且 `content` 保留已流出的块。
2. **终局不抛**：错在流里、在消息上，不在异常里（P6）；`reason` 只是 `stopReason` 的**冗余投影**，
   两者的值必须一致。
3. **缺终局 ⇒ 错误**（P10）。
4. **下游不得重建**：任何消费者读 `partial()` 的字段即可；**不许**再从 `Throwable` 反推文本
   （`Throwable` 只保留给日志与 Java 侧栈）。

### 6.2 落点（三处，各有唯一职责）

| 落点 | 职责 | 具体 |
|---|---|---|
| ① 车道侧单点 | 写 `stopReason` + `errorMessage`（pi `:823-824` 的对应物） | `StreamPartialBuilder.emitError` 增写 `errorMessage`（取 `Throwable` 文本，**已是 pi 的 `error.message` 语义**） |
| ② 出口缝 | 挂身份 ＋ 保证「终局的 partial 必已落定、usage 非 null」 | `AbstractChatApi`：`withTerminalUsage` 保留；**新增**落定补全（`stopReason`/`errorMessage` 缺则补），并对**所有**终局事件生效（含旁路 `ChatApi` 实现） |
| ③ 旁路生产者 | 直接调**同一**落定工厂 | `StreamEvent` 上新增静态工厂 `StreamError.settle(reason, cause, partial)` / `StreamDone.settle(reason, partial)`（**落地名即此**）；`QueueStreamIterator`、`FauxProvider`、`SessionRunner`、`send()`、`onComplete` 安全网全部改走它 |

> **「避免重复构造和不一致」的答案**：**一个工厂 + 三处落点**。工厂只做「在已有消息上补缺」
> （不新建消息、不重组内容），故无论谁先落定，结果同值；`pi` 侧对应物就是那句就地赋值。
> 不允许第四种构造方式 —— §9 的变异探针逐点验证。

### 6.3 具体改动（文件级）

**pi-java-ai**

1. `StreamEvent.StreamError`：新增静态 `settle(String reason, Throwable cause, AssistantMessage partial)`
   —— 返回「partial 已落定」的事件；`reason` 为空时取 `"error"`；`errorMessage` 缺则取
   `cause.getMessage()`（空则 `cause.toString()`，与 `RunFailure:90` 同一口径）。
2. `StreamEvent.StreamDone`：新增静态 `settle(String reason, AssistantMessage partial)`（补 stopReason）。
3. 两个 record 的 javadoc 改写：`partial` ≙ pi 的 `message`/`error`；**注明「落定」不变量**。
4. `StreamPartialBuilder.emitError`：写 `errorMessage`（**F1/F2 的根因修复**）。
5. `AbstractChatApi.onError`：`StreamError` 走工厂（文本来自 `t`）。
6. `AbstractChatApi.onComplete` 安全网：**改推 `StreamError("error", ISE("stream completed without a terminal event"), identityBase)`**
   （F3；liveness 目的不变 —— 仍保证 `hasNext()` 不永久阻塞）。
7. `AbstractChatApi.send`：遇终局后**按终局返回**（`done` 与 `error` 同法投影），删掉永不填充的 `blocks`；
   `PiHttpException` 只留给 plumbing 异常（§10 B113 登记残余口径）。
8. `QueueStreamIterator`：`close()` / `abort()` / 中断路走工厂（保留「空内容」这一点**不变**：
   这两条路本来就没有累加器，pi 的对等物是 `lazy.ts:56` 的合成错误消息）。
9. `FauxProvider`：三条终局走工厂（错误文本进消息）。

**pi-java-agent-core**

10. `PiLoopRunner`：**删 `withErrorShape`**（pi 无此步；P7 是「拿到什么就是什么」），
    终局两臂都变成 `fromPartial(event.partial())`；`markAborted` 保留（pi 的 provider 自己收尾，
    java 的同步拉取需要这条兜底 —— 既有论证见 `PiLoopRunner:301-321`，本包不动）。
11. `LlmSummaryGenerator.terminal`：文本改读 `partial.errorMessage()`，`Throwable` 退为兜底。

**pi-java-agent-core / coding-agent（宿主）**

12. `SessionRunner`：① 终局 `StreamError` 的 reason 改用 `err.reason()`（F4）；
    ② 两处兜底 `StreamError` 走工厂（文本进消息，空 partial 这一点保持）。
13. `JsonEventMapper.assistantMessageEvent`：终端变体改**显式投影**为 pi proxy 的扁平形状
    （`{type:"done", reason, usage}` / `{type:"error", reason, errorMessage?, usage}`，P14），
    **不再** `valueToTree` 整个 record（F6）。
    ⚠️ **实施时扩到两处**：`toStreamEventWire`（`--mode json` 的逐条输出）走**同一个**投影 ——
    设计稿原写「这是**唯一**改变对外线形状的一处」，但那条线上同样漏 `Throwable`
    （`stackTrace` 上 stdout），只修一处等于半修。两处共用 `terminalWireNode` 一个实现
    （合 R3 的「单构造点」）。见 §12。
    ⚠️ R5-(a) 的对外形状变更：见 `docs/32` 惯例的「需知悉」标注。
14. `PrintMode` / `ChatScreen` / `AgentEventTranslator` / `PayloadRecordingStreamFn`：
    文本优先取 `partial.errorMessage()`，`Throwable` 仅兜底（行为等价，去重复）。

**明确不动**：`PiLoopRunner.markAborted`、`RunFailure`、`ContextEntries` 的非投影集、
`StreamEvent.withPartial`（仍用于身份挂载）、TUI/web 读的都是 `partial.errorMessage()` 兼容字段。

---

## 7. 裁决点（设计门 5 的四问在前）

### R1 —— 是否扩展现有 sealed record？（设计门 5 问一）

**建议：不扩展**。`partial` 组件已经是 pi 的 `message`/`error` 承载物（J2），
行为差异全部来自「生产者没落定」，与 record 组件无关。
扩字段/改组件的代价是实打实的：`StreamEvent` 的 13 个变体被 **40+ 消费点**（SDK 访问器、TUI/web 的
record 模式 `case StreamError(var reason, var error, var partial)`、conformance 套件、5 个测试文件）
按 3 组件位置解构；改一处就全仓动，而**收益为零**（不改行为）。

### R2 —— 是否保留旧访问器？（设计门 5 问二）

**建议：保留，只升语义**。`reason()` / `partial()` / `error()` 三个访问器**签名不变**，
javadoc 改为：`partial()` ＝ 落定后的完整消息（pi `message`/`error`）；`error()` ＝ **仅日志用**的
Throwable（pi 无对应物，登记 B114）。若日后要删 `error()`，需先让 §6.3-14 四处读点全部改读消息
（本包已改），**留作下一个包的一次纯清理**，不混进本包。

### R3 —— 如何避免重复构造与不一致？（设计门 5 问三）

**建议：单工厂 + 三落点 + 禁下游重建**（§6.2）。判据：全仓 `err.error()` 的读取点从 **6 处降到 1 处**
（`error()` 自身 + 日志），且**只剩一种**终局构造方式。

### R4 —— 「缺终局」的处置（F3）

**建议：改推 `StreamError`**（对齐 P10）。安全性：该安全网今天只在 `Publisher` 丢帧时命中
（`QueueStreamIterator` 读到第一个终局即 `closed`，故重复入队的合成事件被丢弃）⇒ **行为变化面＝丢帧那一支**。
风险：若某条车道**故意**不发终局（今天没有；五条车道各有「无 stop reason ⇒ error」夹具），会由
「静默成功」变「报错」—— 这正是我们要的。

### R5 —— 宿主线的终局帧怎么处理（F6）

两个选项：

- **(a) 保留帧，改载荷形状**（**本包建议**）：终局帧继续发（Java 宿主有消费者），但载荷改成
  **pi proxy 协议的扁平形状**（P14）：`done → {type,reason,usage}`、`error → {type,reason,errorMessage?,usage}`，
  `Throwable` 不再落线。**用户可见变化＝错误帧不再含 JVM 栈**（纯改进），帧集合不变。
- **(b) 不发终局帧**（更贴 pi 的 RPC 形状，P13）：须同时给 web 加一条**从落定消息派生**错误面的路径
  （`AgentSettled` 的尾条 assistant：`stopReason ∈ {error,aborted}` ⇒ 顶层错误），并改其夹具。
  代价更大、风险更高（web 错误显示会短暂失去来源），收益是「客户端收到的帧集合与 pi 相同」。

**建议 (a) + 把 (b) 登记为后续项（B115）**，理由：本包是**载荷契约**包，(b) 是**帧集合**变更，
两者混在一个包里会让「先红证据」指向两个不同的观测面；且 (a) 已经把 F6 的实际缺陷（栈上协议）修掉。

### R6 —— `done.usage` 的嵌套包裹（F7）

**建议：本包不动，登记 B116**。它不产生行为差异（`withTerminalUsage` 兜底后消息上的 usage 恒非 null），
且 proxy 线本来就有扁平 `usage`（P14）⇒ 保留「事件上带一个 usage」这一点**是有 pi 先例的**；
要动就等某个包同时处理 proxy 线的 `providerThinkingLevel`（B110）。

### R7 —— `send()` 的错误语义（F5）

**建议：按 pi 的 `complete()` 返回落定消息**（不抛）。同时把「plumbing 异常 ⇒ `PiHttpException`」保留并登记 B113：
pi 的等价物是「流里的一条错误消息」，但 java 这条 catch 覆盖的是**本仓管道自身**的故障（车道内已全捕获），
属于方言面。⚠️ 若审核希望严格照 pi，就把这条 catch 也改成合成错误消息 —— 请裁决。

### R8 —— 与 3d 重试环的关系

`PostRunRetry` 的白名单分类器要求**非空 `errorMessage`**（`docs/31 §8.22`）。
本包把该不变量从「补丁补上」升级为「生产者保证」，**不改变其判据**；
回归必须包含 3d 的 5 个夹具（`AgentSessionRetryEventOrderTest` 等）与 L5 剧本。

### R9 —— `markAborted` 是否随 pi 删除？

**建议：不删**。pi 的 provider 自己在同一对象上收尾（P2/P3），java 的同步拉取会在
「信号已中止但 provider 没吐终局」这一支走到这里；该分支的论证在 `PiLoopRunner:301-321`，
本包只**不再**让它承担 `errorMessage`（那由落点①②负责），语义收窄但不删。

---

## 8. 分批与提交计划

| 步 | 提交 | 内容 | 先红 |
|---|---|---|---|
| C1a | `feat(ai): settle the terminal stream payloads at the producers` | §6.3 的 1–9（工厂 + 车道侧 + 旁路生产者 + 安全网 + `send`） | 新夹具 6 条（§9.1） |
| C1b | `refactor(agent-core): read the terminal message instead of the throwable` | §6.3 的 10–12（删 `withErrorShape`、摘要文本源、宿主 reason） | 改写 3 条既有夹具 + 1 条新夹具 |
| C2 | `feat(coding-agent): project the terminal frame into the proxy wire shape` | §6.3 的 13–14 | 新夹具 2 条（终局帧线形状） |
| C3 | `docs(ai): close the C-batch terminal payload package` | 本文件 §12、`docs/48` banner/表行、`docs/41`、`docs/32` | — |

分三个提交是为了让「载荷」（C1a/C1b）与「线形状」（C2）各自有独立的先红与变异证据（R5 的理由）。

---

## 9. 先红与变异探针计划

### 9.1 先红（**实测见 §12.2**）

> ⚠️ 下表是**设计期的预测**。实测结果与两处更正（E4 实为两条、**E5 实测绿**、E8 必须改写）
> 都记在 **§12.2**；本表保留原样以免丢失「预测错在哪」的证据。

| # | 夹具（新） | 断言 | 预测今日红 |
|---|---|---|---|
| E1 | `StreamEventSettlementTest.builderErrorCarriesTheText` | `emitError("error", IOException("kaboom")).partial().errorMessage()` ＝ `"kaboom"` | 红（null） |
| E2 | `…keepsWhatStreamedBeforeTheFailure` | 先 `emitTextDelta("partial text")` 再 `emitError` ⇒ `partial().content()` 含该文本、`stopReason="error"` | **绿**（builder 已保留）→ 作对照，防「修坏了」 |
| E3 | `StreamEventSettlementTest.missingTerminalIsAnError` | `streamBlocking` 的 `onComplete` 安全网推的是 `StreamError` 且文本非空 | 红（今天是 `StreamDone("stop")`） |
| E4 | `QueueStreamIteratorTest.abortSettlesTheReasonAndText` | abort 路 `reason="aborted"`＋文本非空 | 红（今天无文本） |
| E5 | `SessionFailurePathTest.abortedKeepsItsReason`（coding-agent） | 中止的 `StreamError` ⇒ `RunStatus.status()` 不塌成 `error` | 红 ← ⚠️ **实测绿**，见 §12.4-1 |
| E6 | `ChatApiTest.sendReturnsTheSettledError`（ai） | `send()` 遇错误 ⇒ 返回 `stopReason="error"` + 文本 | 红（今天空消息、null） |
| E7 | `JsonEventMapperTest.terminalErrorFrameHasNoStackTrace` | 终局帧的 `assistantMessageEvent` 无 `stackTrace` 键、有 `errorMessage`；`done` 帧有 `reason`+`usage` | 红 |
| E8 | `AgentHarnessTest.*`（改写 3 条） | 生产者落定后，`withErrorShape` 删除仍绿（**不是先红**，是回归） | 绿 ← ⚠️ **预测错**：裸 record 夹具必红，见 §12.4-2 |

### 9.2 变异探针（**实测红集见 §12.3**）

| 变异 | 目的 |
|---|---|
| M1 去掉 `emitError` 的 `errorMessage` 写入 | E1/E4/E6 |
| M2 去掉工厂的落定补全 | E3/E4/E6 |
| M3 安全网改回 `StreamDone("stop")` | E3 |
| M4 `SessionRunner` 改回忽略 `reason()` | E5 |
| M5 `send()` 改回 `break` | E6 |
| M6 终局帧改回 `valueToTree` | E7 |
| M7 `QueueStreamIterator.abort` 传空 reason | E4 |

> ⚠️ 每条变异**落地后必须 grep 复核**（CRLF 让带 `$` 的模式静默失效，本仓已 5 次，`docs/54 §12.8`）。

### 9.3 pi 侧探针（**已跑**，逐字输出见 §3.1）

脚本：`/tmp/pic/full.mjs`（＝ `p.mjs` 头 ＋ P1/P2 ＋ `p2.mjs` 的 P3–P6）。
**6 条预测全部命中**，唯一校正：faux 的 partial 与终局**不共用对象**（真车道才共用，见 §3.1-1）。

---

## 10. 新增登记（起草名单，实施后定号）

| 拟号 | 内容 |
|---|---|
| B109 | **`reason`/`stopReason` 词表**：java `"tool_use"` vs pi `"toolUse"`（`AnthropicMessagesApi:388`、`PiMessagesApi:287` 已注明是有意偏差；`ChatApiConformanceSuite:74` 同时接受两者）⇒ 影响对外 `stopReason` 与存盘兼容，须单独裁决 |
| B110 | `providerThinkingLevel`：pi 的消息字段（`types.ts:522`）与 proxy 线字段（P14），java 两侧都没有 |
| B111 | `deferred` 终局：pi `done.reason` 含 `"deferred"`，java 无任何生产车道（`DeferredHandle` 只出现在解码/夹具） |
| B112 | `StreamSimple` 的 `"overflow"` 停因路**零生产者**（`StreamSimple.java:62-70`、`ContextEstimator` 自陈「与 pi 无对应物」）⇒ 归 A-20 清理 |
| B113 | `send()` 的 plumbing 异常包成 `PiHttpException`（pi 的等价物是一条错误消息）⇒ R7 的残余口径 |
| B114 | `StreamError.error()` 这个 `Throwable` 组件在 pi 无对应物（保留供日志；只许 1 个读点） |
| B115 | 宿主线是否**整体**不发终局帧（R5 的 (b) 项；需给 web 加派生错误面） |
| B116 | `StreamDone.usage` 的 `UsageInfo` 嵌套包裹（R6；与 B110 同批处理更省） |

---

## 11. 未覆盖与风险

1. **pi 侧探针已跑**（§3.1，6 条零不符）；剩下的不确定性在 **java 侧**：本包的 8 条先红与 7 条变异
   是**预测**（§9.1/§9.2），实施前不得当成已核事实；实测红集与预测不符时**就地更正本文件**（本仓惯例）。
2. **`FauxChatApi` 绕过出口缝**（`docs/32 B48` 已登记）：落点②不覆盖它 ⇒ 靠落点①/@6.3-9 的生产者改动兜住；
   实施时须为「走 faux 的路径」单列一条夹具，否则那条路会静默留在旧形状。
   ✅ **已落**：`FauxProviderTest.errorModeShouldReturnStreamError` 补三条断言
   （`reason`／`partial.stopReason()`／`partial.errorMessage()`）。
3. **真正的中止语义**仍是 R9 的旧论证（`cutShort` / `abortedAtEntry` 两分法），本包不动它 ⇒
   若某条中止路径的 `errorMessage` 在 pi 是 SDK 的原文而我们给的是 `"Request was aborted"`，属**残留差异**，
   实施时逐条比对并登记。
   ✅ **实施比对**：`QueueStreamIterator.close()` 那条合成终局仍给 `"aborted"` ＋ 空内容
   （它**没有累加器** —— pi 的对等物是 `lazy.ts` 的合成错误消息，同样没有已流出的内容）
   ⇒ 与 pi 同形，无新登记。
4. **对外协议变更**（R5-(a)）：`message_update.assistantMessageEvent` 的终局形状会变（不再有 `stackTrace`）
   ⇒ 按 `docs/32` 的惯例，这类变更要在收口时标注「需知悉」。
5. **`errorMessage` 的取文本口径**：pi 是 `error instanceof Error ? error.message : JSON.stringify(error)`；
   java 取 `getMessage()`，空则 `toString()`（`RunFailure:90` 既有口径）。两语言 `Error` 同名不同物，
   这个偏差**已登记**（`RunFailure` javadoc），本包沿用不重开。

---

## 12. 实施记录（闭环时回填）

**裁决结果**：R1–R9 **全部按建议**（2026-09-27 用户「按建议实施」）。即：不扩 record；
保留三个访问器并升语义；单工厂 ＋ 三落点 ＋ 禁下游重建；缺终局改判 error；
宿主线**保留终局帧但换成 pi proxy 的扁平形状**（(a)，(b) 登记 B115）；
`done.usage` 嵌套包裹不动（B116）；`send()` 返回落定消息、`PiHttpException` 保留（B113）；
3d 重试环判据不变；`markAborted` 保留。

### 12.1 落地的生产代码

**单工厂**：`StreamEvent.StreamError.settle(reason, cause, partial)` 与
`StreamEvent.StreamDone.settle(reason, partial)`，共用两个私有助手
（`settleMessage` / `settleReason`）与一个判据 `StreamEvent.isSettled(stopReason)` ＋
常量 `StreamEvent.PENDING_STOP_REASON`（把此前散在两处的 `"pending"` 字面量收成一处）。
工厂规则：**消息上已落定 ⇒ 消息是权威**（pi 的 `reason` 只是 `output.stopReason` 的冗余投影）。

**三落点**：

| 落点 | 文件 | 改动 |
|---|---|---|
| ① 车道侧 | `StreamPartialBuilder.emitError` | 走 `settle`（此前只写 `stopReason`）—— F1/F2 的根因修复 |
| ② 出口缝 | `AbstractChatApi` | `onError` 走 `settle`；**安全网改推 `StreamError("error", ISE("stream completed without a terminal event"))`**（F3）；`send()` 两种终局都返回落定消息（F5），删掉永不填充的 `blocks` 累加器 |
| ③ 旁路生产者 | `QueueStreamIterator`（close/abort/中断三路）、`FauxProvider`（三条终局）、`SessionRunner`（两处兜底） | 全部改走同一工厂 |

**消费面**：`PiLoopRunner` **删 `withErrorShape`**（终局两臂变 `fromPartial(event.partial())`；
`markAborted` 保留，判据改用 `isSettled`）；`LlmSummaryGenerator.terminal` 的文本源改
`StreamError.textOf(event)`；`PrintMode` / `ChatScreen` / `AgentEventTranslator` /
`PayloadRecordingStreamFn` 四处同样改 `textOf`（消息优先、`Throwable` 兜底）
⇒ **全仓 `err.error()` 的读点从 6 处降到 1 处**（`textOf` 的兜底分支；R3 的判据成立）。

**线形状（C2）**：`JsonEventMapper` 新增 `terminalWireNode` 显式投影 ＋ `normalizedUsage`
（顶层 usage 与终局帧 usage 共用一个归一），`assistantMessageEvent` 与 `toStreamEventWire`
**两处共用**之。

### 12.2 先红（实测，回填 §9.1）

`mvn -pl pi-java-ai test -Dtest='StreamEventSettlementTest,ChatApiTerminalSettlementTest,QueueStreamIteratorTest'`
在**只加了两个工厂、未改任何消费面**时跑：**22 跑 6 红**，与预测逐条对上：

| # | 夹具 | 预测 | **实测** |
|---|---|---|---|
| E1 | `builderErrorCarriesTheText` | 红 | ✅ 红（`errorMessage` 为 null） |
| E2 | `builderErrorKeepsWhatStreamedBeforeTheFailure` | 绿（对照） | ✅ 绿 |
| E3 | `missingTerminalIsAnErrorNotASuccess` | 红 | ✅ 红（当时是 `StreamDone("stop")`） |
| E4 | `abortSettlesTheReasonAndText` ＋ `closeSettlesTheStopReasonOnTheSyntheticDone` | 红 | ✅ **两条**都红（设计稿只列了一条） |
| E5 | `SessionFailurePathTest.abortedStreamErrorKeepsItsReason` | 红 | ⚠️ **绿**（见 §12.4 —— 设计稿的 F4 判断错了一半） |
| E6 | `sendReturnsTheSettledError` | 红 | ✅ 红（当时返回空消息） |
| E7 | `JsonEventMapperMessageUpdateTest` 终局帧四条 | 红 | ✅ 红（4 条全红） |
| E8 | `AgentHarnessTest.*`（改写 3 条） | 绿（回归） | ✅ 绿（改走 `settle` 后） |
| ＋ | `streamErrorExitsTheSeamSettled`（设计稿未列，实施时补） | — | ✅ 红（出口缝的 F1 面） |

⚠️ 「先红」的准确口径：两个**工厂**是新增面（`settle` / `textOf`），编译前不存在 ⇒
它们在实现后当然是绿的 —— 先红测的是**四条消费面**（builder / 安全网 / 旁路 / `send` / 线格式）。
§9.1 的写法让人误以为工厂本身也能先红，实施时按上述口径测。

### 12.3 变异探针（实测红集，回填 §9.2）

| 变异 | 预测红集 | **实测** |
|---|---|---|
| M1 去掉 `emitError` 的落定 | E1/E4/E6 | **5 红**：`builderErrorCarriesTheText`、`builderErrorKeepsWhatStreamedBeforeTheFailure`、`builderErrorWithoutACauseStillSettlesTheReason`、`sendReturnsTheSettledError`、`streamErrorExitsTheSeamSettled`。⚠️ 预测里的 **E4 不在此列**（`QueueStreamIterator.abort` 走工厂、不经 builder） |
| M2 去掉工厂的落定补全 | E3/E4/E6 | **12 红**（`StreamError` 面全灭，`StreamDone` 的 4 条仍绿 ⇒ 归因干净） |
| M3 安全网改回 `StreamDone("stop")` | E3 | **1 红**：恰 `missingTerminalIsAnErrorNotASuccess` |
| M4 `SessionRunner` 改回忽略 `reason()` | E5 | ⚠️ **0 红** —— 语义等价，见 §12.4 |
| M5 `send()` 改回 `break` | E6 | **1 红**：恰 `sendReturnsTheSettledError` |
| M6 终局帧改回 `valueToTree` | E7 | **3 红**：三条 `terminal*Frame*`（`toStreamEventWire` 那条仍绿 ⇒ 两处投影的隔离成立） |
| M7 `QueueStreamIterator.abort` 传空 reason | E4 | **1 红**：恰 `abortSettlesTheReasonAndText` |

每次变异**落地后都 grep 复核**（CRLF 的第 6 次提醒）：本次按单一整行字面量替换，不带 `$` 锚点。

### 12.4 被实测推翻的设计结论（两处）

1. **F4 的后果判断不成立 ⇒ M4 零红。** 设计稿写「`aborted` 在宿主状态里被塌成 `error`」。
   实测：`SessionRunner:132-136` 的**读尾**（B5 第 2 步，pi `print-mode.ts:139-155` 的对应物）
   在尾条是 `error`/`aborted` 的助手消息时用 `tail.stopReason()` **覆盖**监听器设的值，
   而流错误必然产生这样一条尾条 ⇒ 监听器那一行的值今天**不可观察**。
   **处置**：改动**保留**（它是正确读法、去掉同仓两套口径，且读尾失效时它就成了唯一来源），
   但夹具的 javadoc 与代码注释都写明「本条钉端到端契约、不是该改动的判别器」，
   并在 `docs/32 B117` 登记（读尾是权威、监听器值被遮蔽这件事本身没有文档）。
2. **E8 不是「回归」而是「必须改写」。** 设计稿预测三条既有 `AgentHarnessTest` 夹具「删
   `withErrorShape` 后仍绿」。实测：它们手搓**裸** `StreamError`（partial 只带 `stopReason`、
   无 `errorMessage`），删掉补丁后 `stopReason` 会停在 `pending`/null ⇒ **必红**。
   同类还有三条：`AgentSessionRetryEventOrderTest` / `SessionRunnerRetryContextTest` /
   `RpcDispatcherTest.autoRetryRerunsAfterError`（都靠补丁把 `"overloaded"` 送进重试白名单）。
   ⇒ 五条夹具全部改走 `StreamError.settle`（**生产者**的入口）。
   这正是本仓已记过的形态：**夹具把人脑里的模型当契约** —— 手搓 record 之所以能过关，
   靠的是被删掉的那个补丁。

### 12.5 提交与回归

- `C1a` `3684a82` `feat(ai): settle the terminal stream payloads at the producers` — §6.3 的 1–9
- `C1b` `b8d7e48` `refactor(agent-core): read the terminal message instead of the throwable` — §6.3 的 10–12
- `C2` `882fcaa` `feat(coding-agent): project the terminal frame into the proxy wire shape` — §6.3 的 13–14
- `C2'` `caebeaf` `fix(tui): read the error bubble text from the settled message` — `ChatScreen` 的收口
  （C2 之后单列，因为它同时是行数顶格的修复，见下）
- `C3` `1c09569` `docs(ai): close the C-batch terminal payload package` — 本文件 ＋ `docs/48` ＋ `docs/41` ＋ `docs/32`

模块回归（实施后实测）：`ai` **995**、`agent-core` **520**、`coding-agent` **279**、
`tui` 209（1 skip）、`web` 48、`evals` 43（17 skip）。
⚠️ 跨模块跑 `-pl <mod>` 前必须先 `install` 上游模块：本次 `coding-agent` 一度因 `~/.m2` 的
**旧 agent-core**（缺 `promptGuidelines`/`isCompacting`/`options.reasoning()`）编译失败 —— 那是
陈旧构件，不是本包改动（`docs/32` 既有教训）。
全 reactor `mvn test`：**BUILD SUCCESS（14/14、exit 0）**，日志 `/tmp/reactor-test.log`。

⚠️ 一处存量顶格的连锁：`ChatScreen.java` **原本恰好 500 行**（本仓上限），本包的第一版改动把它顶到 503
—— 已在收口时把新增注释压回单行、恢复 500。**该文件再无余量**，下次改动必须拆。

### 12.6 需知悉的对外变更

`message_update.assistantMessageEvent` 的**终局形状变了**：`done` 帧从
`{type,reason,usage:<UsageInfo>}` 变成 `{type,reason,usage:<扁平 Usage>}`（不再有
`inputTokens`/`outputTokens`/嵌套 `partial`），`error` 帧从 `{type,reason,error:<Throwable>}`
变成 `{type,reason,errorMessage?,usage}`（**不再有 `stackTrace`/`cause`**）。
同一投影也作用于 `--mode json` 的逐条输出（`toStreamEventWire`）。
消费方按 pi 的 proxy 契约解析即可；键序不承诺（按 `docs/54 §12.8` 的教训：跨实现夹具按键取值）。
