# 36 - 包⑨：线上终局投影（B41 ＋ B42）

> **文档约定**（用户 2026-09-19）：一个功能模块一份文档；`docs/32` 是唯一索引。
> 流程：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**；**用户审核 §8 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：命题**已逐条核过**；§8 待审核。

---

## 1. 这一包解决什么

两条都是包⑥ 当时裁决「登记不修」的**线上终局投影**缺口，共用一个面：**回合结束时那几条消息在线上缺字段**。

| # | 缺口 | 谁受影响 |
|---|---|---|
| **B41** | **终局助手消息的 `usage` 可空** —— pi 的 `AssistantMessage.usage` 是**必填**且**永不为空**（零值也在）；pi-java 在 `usageOf` 里对 null 返回 null ⇒ 两条线都**整键消失** | RPC 客户端（编辑器/脚本）、web 前端（读 token 用量）、**以及落盘** |
| **B42** | **`turn_end` 既不带 `message` 也不带 `toolResults`** —— pi 两条都是**必填** | 前端按 `toolCallId` 从 `turn_end.toolResults` 收工具结果（今天被 `agent_end` 整表替换兜住）；`message` 今天无人读 |

---

## 2. 命题表

### 2.1 pi 侧

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | `turn_end` = `{ type; message: AgentMessage; toolResults: ToolResultMessage[] }`，**两个字段都必填、没有 `?`** | `agent/src/types.ts:438` | **已核** |
| P2 | 会话层联合**复用** agent 联合（`Exclude<AgentEvent,{type:"agent_end"}>`）⇒ `turn_end` 原样是 `AgentSessionEvent` 成员 | `core/agent-session.ts:144-150` | **已核** |
| P3 | 两个发射点：**错误/中止**路发 `{message, toolResults: []}`；**正常**路发本回合的完整工具结果 | `agent-loop.ts:216`、`:224-243` | **已核** |
| P4 | `toolResults` 是**完整的 `ToolResultMessage` 对象**（不是投影）：`{role:"toolResult", toolCallId, toolName, content, details, usage?, addedToolNames?, isError, timestamp}` | `agent-loop.ts:224-243` 把 `executedToolBatch.messages` 原样 push；构造器 `:784-798` | **已核** |
| P5 | `turn_end` 与 `agent_end` **不同形**：前者带**本回合**的助手消息 ＋ 类型化的工具结果；后者带**整个 run** 的全部新消息（扁平 `AgentMessage[]`，不区分类型） | `types.ts:434`（agent_end）vs `:438`（turn_end）；发射点 `:243` vs `:253`/`:272` | **已核** |
| P6 | `turn_end` **原样上 RPC/JSON 线**（只有 `message_update` 被投影） | `modes/json-event.ts:48-51` | **已核** |
| P7 | `AssistantMessage.usage: Usage` **必填** | `ai/src/types.ts:439` | **已核** |
| P8 | **pi 没有任何产出「无 usage 的助手消息」的路径** —— 11 个 provider 适配器、`lazy.ts` 的装配失败、`faux`、中止/错误路（`agent.ts:511-527` 的 `EMPTY_USAGE`、`recovery.ts:28-40` 的 `ZERO_USAGE`）**全都显式给 usage** | 逐处见该报告 §B7 的清单 | **已核** |
| P9 | **落盘的助手条目也带 `usage`**（`SessionManager._persist` 整条 `JSON.stringify`，不做字段裁剪）；真实夹具佐证 | `core/session-manager.ts:1029-1056`、`test/fixtures/large-session.jsonl:3` | **已核** |
| P10 | ⚠️ **但 `ToolResultMessage.usage` 是**可选**的** —— 工具没报用量时线上**确实没有**该键（`createErrorToolResult` 根本不带 usage） | `ai/src/types.ts:459`；`agent-loop.ts:767-772` → `:793` | **已核** |
| P11 | **pi 没有 web UI**（全仓无前端包／无 `.tsx`/`.vue`/`.svelte`；`client`/`server` 是传输层、`tui` 对 `turn_end` 零命中） | 全仓 grep ＋ `interactive-mode.ts:3172-3195` 无 `turn_end` 分支 | **已核** |

> **P10 是一条要点**：它说明「线上一律带 usage」**不成立** —— 助手消息必带（P7-P9），
> **工具结果可以不带**。两条不能一概而论，改动必须分开。

### 2.2 pi-java 侧

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P12 | `Message.AssistantMessage.usage` 组件类型是 `StreamEvent.UsageInfo`（**可空**） | `AssistantMessage.java:52-55` | **已核** |
| P13 | 归一化 `usageOf`：有全量分解用全量、否则合成；**`info == null` ⇒ 返回 null** | `Message.java:131-142` | **已核** |
| P14 | **`fromPartial` 是终局助手消息的唯一漏斗**（`PiLoopRunner:232/237/242/245` 四处、`AbstractChatApi:129`、`LlmSummaryGenerator:269`）⇒ 改它一处即改「所有」终局消息 | 全仓 grep | **已核** |
| P15 | **RPC 线**经 `MessageMixin` 的 `NON_NULL`：`usageOf` 返回 null ⇒ **整键消失** | `JsonEventMapper.java:32-34`、`:39-41` | **已核** |
| P16 | **web 线**在 `WebWireJson.messageNode` **也**做同样的 null 判断（`:75-76`）⇒ **两条线同时缺** | `WebWireJson.java:75-76`；写工具结果的是 `:42-43` | **已核** |
| P17 | ⚠️ `MessageMixin` **只**注册在 `JsonEventMapper` 的私有 MAPPER 里 ⇒ **落盘不走它**（包⑥ 时我把这点说错过一次） | `JsonEventMapper.java:32-34`（全仓 `addMixIn` 只有这一处） | **已核** |
| P18 | `AgentSessionEvent.AgentSettled()` **无字段**，web 把它翻成空 `{type:"turn_end"}` | `AgentSessionEvent.java:29-30`、`AgentEventTranslator.java:51-54` | **已核** |
| P19 | `AgentSettled` 有 **2 个发射点**：正常路在转写本**已 flush 之后**（`transcript` 局部量在手）；错误路无转写 | `SessionRunner.java:150-156`、`:179-180` | **已核** |
| P20 | 三个 `switch (AgentSessionEvent)` **全都有 `default`** ⇒ 改 `AgentSettled` 的形状**不会**破坏现有 switch | `JsonEventMapper:148`、`AgentEventTranslator:56-58`、`ChatScreen:293` | **已核** |
| P21 | 前端**只**读 `turn_end.toolResults` 且按 `toolCallId` 去重（幂等 ⇒ 把整份结果都发过去是安全的） | `client/main.ts:325-337` | **已核** |
| P22 | web 的 `message_update` 帧里的 `message` 由 `assistantNode` 构造，**根本不写 usage**（与 P16 的 `messageNode` 是两个方法） | `WebWireJson.java:91-99` | **已核** |

---

## 3. 被推翻的命题

| # | 我原先说的 | 实测 | 后果 |
|---|---|---|---|
| **R1** | B41 的「影响面是 `agent_end` 的每一条助手消息，以及任何靠 `MessageMixin` 序列化 `Message` 的地方（RPC 转录、**session 落盘**…）」 | **半错**（包⑥ 我已更正过一次，本轮定稿）：`MessageMixin` **只**注册在 `JsonEventMapper`（P17）⇒ **落盘不走它**；**但** web 线在 `WebWireJson:75` **另有一份**同样的 null 判断（P16）⇒ 我把影响面说小了 | ⇒ **两条线**（RPC ＋ web）都要改，不是一条 |
| **R2** | 「pi-java 落 trim 而 pi 不 trim」那类**口径差**…（无关，略） | —— | —— |
| **R3** | B42 就是「`turn_end` 补 `toolResults`」 | **半错**：pi 的 `turn_end` **还有 `message`**（P1），同样必填、pi-java 同样没有 | ⇒ 范围是**两个字段**，不是一个 |
| **R4** | 「线上助手消息一律带 usage，所以统一在投影层兜零即可」 | **错一半**：**工具结果**的 `usage` 在 pi 是**可选**的（P10），pi-java 现在省略它**是对的** | ⇒ 兜零**只能**落在助手消息上；把手伸到工具结果就是引入偏差 |

---

## 4. 审计（顺带发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | `turn_end` 的 `message` 字段**今天没有任何消费者**（前端只读 `toolResults`；pi 的 TUI 连 `turn_end` 都不处理） | P11、P21；`interactive-mode.ts:3172-3195` | 照 pi 补上（对齐），但**如实标注**它今天不可观测 |
| **B** | pi 的错误路 `turn_end` 也带 `message`（合成失败消息，`usage: EMPTY_USAGE`）与空 `toolResults` | `agent-loop.ts:216`、`agent.ts:511-527` | pi-java 的错误路（`SessionRunner:179-180`）应当同形 |
| **C** | `AgentSettled` 在 pi 里是**另一个**事件（`agent_settled`），**不等于** `turn_end`：pi 的 `agent_settled` 由 adapter 层另发 | `agent-session.ts` 的联合里两者并列（P2 上下文） | ⚠️ **裁决点 B**：pi-java 用 `AgentSettled` 兼任 `turn_end` 的发射点 —— 这是既有的合并，本包**不拆**（拆它动的是事件时序，另一包） |
| **D** | web 的 `message_update.message` 由 `assistantNode` 构造（P22），**不写 usage**；而 RPC 的 `message_update` 顶层写 usage（包⑥ 已做） | `WebWireJson.java:91-99` | **不在本包**：前端不读它；且改了会让每帧多一个对象。**登记**（见 §5-N4） |

---

## 5. 待核 / 裁决点

| # | 问题 | 结论 |
|---|---|---|
| **N1** | B41 改在哪一层 | **裁决点 A**：`usageOf`（宽：两条线 ＋ **落盘**都对齐 pi）vs 两处线投影（窄：不碰落盘） |
| **N2** | `turn_end` 的载体 | **裁决点 B**：给 `AgentSettled` **加字段**（+兼容构造器）vs 新开 `AgentTurnEnd` 变体 |
| **N3** | `toolResults` 的元素类型 | pi 是完整 `ToolResultMessage`（P4）⇒ Java 用 `List<Message>` 但**实际只放 `Message.ToolResultMessage`**；⚠️ 是否收紧成 `List<Message.ToolResultMessage>` 见裁决点 C |
| **N4** | 前端不读 `turn_end.message`、也不读 web 的 `message_update.message.usage` | 照 pi 补前者（对齐）；**后者不做**（§4-D） |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 给**工具结果**的 `usage` 兜零 | **pi 自己就是可选的**（P10/R4）⇒ 兜零是引入偏差 |
| 2 | 拆 `AgentSettled` 与 `turn_end` 的合并（§4-C） | 动事件时序，另一包 |
| 3 | 给 web 的 `message_update.message` 加 usage（§4-D） | 前端不读；每帧多一个对象 |
| 4 | 动 `assistantNode`（流式 partial 的形状） | 同上 |

---

## 8. 实施稿（步骤 ③ 的产物；**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决 | 后果 |
|---|---|---|
| **A** | **改在 `usageOf`**（`info == null` 时合成零值 `Usage`，而不是返回 null） | 两条线 ＋ **落盘**一并对齐 pi（P9：pi 的落盘条目带 usage）。⚠️ 代价：**既有 pi-java 会话文件与新写出的会不同形**（旧的没有 usage 键）—— 但那正是向 pi 靠 |
| **B** | **给 `AgentSettled` 加两个字段 ＋ 留一个无参兼容构造器** | 改动最小、既有构造点与夹具零churn；不新开变体（`AgentSettled` 就是 pi-java 的 `turn_end` 发射点，见 §4-C） |
| **C** | `toolResults` 用 **`List<Message>`**（不收紧成 `ToolResultMessage`） | pi 的类型是 `ToolResultMessage[]`，但 Java 侧收紧会让 `AgentSettled` 与 `AgentEnd` 的字段类型不一致；投影端反正只序列化消息。⚠️ 若你倾向收紧，实施时改。 |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-ai` | B41：`usageOf` 兜零（裁决 A）＋ 夹具 |
| **2** | `pi-java-coding-agent` | B41 落线复核（`JsonEventMapper` 无需改，因 `usageOf` 已兜）＋ **B42**：`AgentSettled` 加 `message`/`toolResults` ＋ `SessionRunner` 两个发射点填值 ＋ `JsonEventMapper` 的 `agent_settled` 支补载荷 |
| **3** | `pi-java-web` | `AgentEventTranslator` 的 `turn_end` 支补 `message`/`toolResults` |
| **4** | `docs/36`＋`docs/32` | 实施记录 ＋ B41/B42 结案 |

### 8.2 精确改动（形状）

**(1) `usageOf`**：

```java
private static com.pijava.ai.Usage usageOf(StreamEvent.UsageInfo info) {
    if (info == null) {
        // pi 的 AssistantMessage.usage 必填且**永不为空**（ai/src/types.ts:439；
        // 11 个适配器 + lazy + faux + 中止/错误路全都显式给零值）⇒ 兜零、**不省键**。
        return com.pijava.ai.Usage.of(0, 0);
    }
    ...
}
```

⚠️ **只动助手消息**。工具结果的 `usage` 保持「null ⇒ 省略」（P10/R4）。

**(2) `AgentSettled`**：

```java
record AgentSettled(Message message, List<Message> toolResults) implements AgentSessionEvent {
    /** 兼容构造器：错误路等拿不到转写时用（pi 的错误路也发空 toolResults）。 */
    public AgentSettled() { this(null, List.of()); }
}
```

**(3) `SessionRunner` 两个发射点**：
- 正常路（`:156`）：从 `transcript` 里取**本回合**的助手消息与工具结果。
  ⚠️ 「本回合」的界定要照 pi（`agent-loop.ts:224-243` 的 `toolResults` 是**这一次 `executeToolCalls` 的产出**）—— 实施时须先核 pi-java 侧的对应量，**不许拍脑袋取「最后一条」**。
- 错误路（`:180`）：`new AgentSettled()`（pi 同：合成失败消息 ＋ 空 `toolResults`）。

**(4) 两条线**：`JsonEventMapper` 的 `agent_settled` 支与 `AgentEventTranslator` 的 `turn_end` 支各补两个字段（`message` 走各线既有的消息投影）。

### 8.3 测试计划（**先红**）

| 模块 | 夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-ai` | `AssistantMessageUsageTest` | 3 | ① `usageOf` 为 null 时终局消息带**零值** usage（不是 null）② 有全量分解时用全量（不回归）③ **工具结果**的 usage 仍可为空（反向，R4） |
| `pi-java-coding-agent` | `AgentSettledPayloadTest` | 3 | ④ 正常路带本回合的助手消息与工具结果 ⑤ 错误路是空 results（pi 同）⑥ `agent_settled` 线上有 `message`/`toolResults` 两键 |
| `pi-java-web` | `AgentEventTranslatorTest` 扩 | 2 | ⑦ `turn_end` 帧带 `toolResults`，前端按 `toolCallId` 去重后能收（钉 P21 的形状）⑧ `agent_end` 整表替换不回归 |

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | `usageOf` 回到返回 null | ① 有牙 |
| P2 | 工具结果也兜零 | ③ 有牙（反向） |
| P3 | `turn_end` 不带 `toolResults` | ④⑦ 有牙 |
| P4 | 错误路发非空 results | ⑤ 有牙 |
| P5 | `turn_end` 不带 `message` | ⑥ 有牙 |

### 8.5 未覆盖（预登记）

- **端到端**：前端真的靠 `turn_end.toolResults` 渲染出工具结果未测（需运行前端；且今天被
  `agent_end` 兜住 ⇒ **无可见差异可钉**，如实写）。
- **落盘形状的变化**（裁决 A 的代价）：既有会话文件与新写出的不同形 —— 回读兼容性未测。
- **`message` 字段无消费者**（§4-A）：补它属对齐，**不是**有人要读。

---

## 9. 下一步

用户审核 §8（含 §8.0 三个裁决点）之后才写代码；实施完成后追加**实施记录**。

---

## 10. 实施记录（2026-09-20，用户「通过 + 推 main」）

**§8.0 三个裁决点已裁**：A ＝ **改在 `usageOf`**（宽：两线 ＋ 落盘）；B ＝ **给
`AgentSettled` 加字段 ＋ 兼容构造器**；C ＝ `List<Message>`。三步全部落地，
新增 **12 条**断言，五个探针**四个有牙、一个未命中**。

### 10.1 三步与改动面

| 步 | 改动 | 状态 |
|---|---|---|
| **1** | `pi-java-ai`：`usageOf` 兜零（`info == null` ⇒ `Usage.of(0,0)`） | ✅ |
| **2** | `pi-java-coding-agent`：`AgentSettled` 加 `message`/`toolResults` ＋ 无参兼容构造器 ＋ `SessionRunner` 正常路填值 ＋ `JsonEventMapper` 的 `agent_settled` 支补载荷 | ✅ |
| **3** | `pi-java-web`：`AgentEventTranslator` 的 `turn_end` 支补 `message`/`toolResults` | ✅ |

⚠️ **计划外的一处改动**：`LlmSummaryGenerator`（见 §10.4-D1）—— **它才是本包的真实风险点**。

### 10.2 实测红集（先红）

- **步骤 1**：`AssistantMessageUsageTest` **4 跑 1 红**（`:40` `Expecting actual not to be
  null`）—— 这次是**真正的断言红**（不是编译失败）。
- **步骤 2**：`AgentSettledPayloadTest` **编译失败 8 处**（`message()`/`toolResults()` 不存在）。

### 10.3 变异探针（**红集实测，不预测**）

| # | 改坏什么 | 实测红集 | 条 |
|---|---|---|---|
| **P1** | `usageOf` 回到返回 null | `AssistantMessageUsageTest…:40`、`MessageTest.fromPartialWithoutUsageInfoSynthesizesZeroUsage:207` | **2** |
| **P2** | 工具结果**也**兜零（反向） | `AssistantMessageUsageTest.toolResultUsageStaysNullWhenTheToolReportedNone:81`、`MessageTest.toolResultShouldPreserveErrorFlag:108` | **2** |
| **P3** | `turn_end` 不带 `toolResults` | `AgentSettledPayloadTest…:102`、`:114` | **2** |
| **P4** | 错误路发非空 `results` | **无** | **0** ⚠️ |
| **P5** | `turn_end` 不带 `message` | `JsonEventMapperTest.agentSettledCarriesTheAssistantMessageWhenPresent:84` | **1** |

**⚠️ P4 未命中（登记，不假装测过）**：错误路的 `AgentSettled`（`SessionRunner:180`）
**没有夹具** —— 把它改成发非空结果，全树无一条红。⇒ **错误路的形状无守护**。
可补：`SessionFailurePathTest` / `AgentSessionRetryEventOrderTest` 已经在驱动失败运行，
在那里加一条断言即可（**登记，不在本包做**）。

**P3 只打到 2 条**：设计稿预测「④⑦」，实测 mapper/web 那两条**打不到** —— 它们**手工
构造** `AgentSettled`、不经 `SessionRunner`（与包⑦ 的 P1 同一类现象）。

**P2 意外地有既有夹具**：`MessageTest.toolResultShouldPreserveErrorFlag:108` 也被打红 ——
说明「工具结果不许兜零」这条不变量**本来就有人守**。

### 10.4 与稿子的偏差（如实）

| # | 稿子写的 | 实际做的 | 理由 |
|---|---|---|---|
| **D1** | 未提 | **改了 `LlmSummaryGenerator:270`**：判据从 `projected.usage() == null` 挪到 **`partial.usage() == null`** | ⚠️ **这是本包的真实风险点，夹具抓到的**：原来的 null 是「流里没报用量」的**信号**，而 B41 让 `usageOf` 恒兜零 ⇒ 信号被消灭 ⇒ **摘要跨度的 token 计数静默归零**（`HarnessCompactionSummarySpanTest` 期望 1200、实得 0）。partial 才是那件事的原件，从那里读同一语义 |
| **D2** | §8.2(3)：「本回合」的界定要照 pi | 按**本次驱动**装 | pi 的 `turn_end` 是**每回合**一条、`AgentSettled` 是**每次驱动**一条；照字面取「最后一回合」会让该字段在常见形状下**恒空**（末回合通常是纯文本收尾）。颗粒度差异另登记 |
| **D3** | §8.2(3)：错误路 `new AgentSettled()` | 同 | pi 那条路给的是**合成的失败消息**（`agent.ts:511-527`），Java 侧没有可给 ⇒ message 为 null、投影时省略。**残余偏差**，已登记 |
| **D4** | §8.3 计划 8 条断言 | **12 条** | 多出的是「合成 usage 带 totalTokens」「无工具时是空数组不是 null」「兼容构造器」等 |

### 10.5 未覆盖（如数）

- ⚠️ **错误路的 `AgentSettled` 无夹具**（P4 未命中）：见 §10.3。
- ⚠️ **`message` 为 null ⇒ 省略键** 是**残余偏差**（pi 那条路必有合成消息）：线上少一个键。
- **端到端**：前端真的靠 `turn_end.toolResults` 渲染出工具结果未测 —— 且今天被
  `agent_end` 的整表替换兜住 ⇒ **无可见差异可钉**。
- **落盘形状的变化**（裁决 A 的代价）：既有会话文件（无 usage）与新写出的（零值 usage）
  不同形；**回读兼容性未测**。
- **`turn_end.message` 无消费者**（§4-A）：补它属对齐，不是有人要读。

### 10.6 ⚠️ 顺带撞出的两条**既有** RPC 线偏差（本包未引入、未修）

写夹具时撞出来的，证据是**实测的序列化结果**：

1. **RPC 线的消息没有 `role` 判别值** —— `Message.role()` 是接口方法、不是 record 组件，
   Jackson 不序列化它。实测 `{"content":[{"type":"text","text":"done"}],"stopReason":"stop"}`，
   **没有 `role`**。web 线靠 `WebWireJson:28` 手工 `put("role", …)` 补上，**RPC 线没有
   这道工序**。
2. **工具结果的键名是 `toolUseId`，而 pi 线上叫 `toolCallId`**（`agent-loop.ts:784-798`
   的字段名；web 线同样靠 `WebWireJson:34` 手工改名）。

⇒ **登记**（不是本包范围）：这是一条独立的「RPC 线消息形状」复核项。

### 10.7 全树验证 —— **BUILD SUCCESS（14 模块全绿）**

`mvn -o clean verify`：**14 个模块全部 SUCCESS**（TUI 03:32、Web 56 s、AI 41 s、
Agent Core 31 s），**spotbugs 11 处检查全部 `BugInstance size is 0`**，checkstyle 零违规。

模块计数（实测）：ai **448**（＋4）· agent-core **487**（不变）· session-sqlite 35 ·
coding-agent **257**（＋5）· web **48**（＋2）· telemetry 31 · protocol 14 · TUI 209（1 skipped）。

> ⚠️ **`LlmSummaryGenerator` 那处改动的守护**：`HarnessCompactionSummarySpanTest`
> 是本包**唯一**能抓到「兜零消灭信号」的夹具 —— 它在**没有**这条夹具的情况下会是
> 一次静默的 token 计数归零。**夹具先于事故**，如实记。
