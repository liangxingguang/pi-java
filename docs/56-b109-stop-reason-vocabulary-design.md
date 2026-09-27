# 56 - B109：归一化停因词表对齐 pi（`"tool_use"` → `"toolUse"`）设计

**状态：待审（2026-09-27；设计稿，**未实施**）**

> 本包裁决 `docs/32` **B109**：「`stopReason` 的词表偏差 —— java 的 `"tool_use"` vs pi 的 `"toolUse"`」。
> B109 原文的保留理由是「影响对外 `stopReason` 与既存转录的兼容（改词表 = 一次数据迁移），须单独裁决」。
> 用户 2026-09-27 裁决：**对齐 pi，改为 `"toolUse"`**。本文件把那次裁决拆成可审的改动面与 R1–R7。
>
> ⚠️ **本包最容易出错的地方是「同名字面量」**：本仓有三个不同的东西都写作 `"tool_use"`，
> 只有一个是本包的目标。先把 §2 读完再动任何一行。
>
> ⚠️ 设计期实测**推翻了一条预案**：初稿把 L5 差分当作最有价值的红灯源，实测它对本案
> **结构上盲**（三条独立理由，§8.1）⇒ 红灯改由 evals 的**真实车道**提供（§8.2 第 2 条，R5）。
> 该盲区本身已登记 **B120**。

**参考台账：** `docs/32`（B109 本行、B48 内容块判别值、B20 停因映射跨车道、B117）、`docs/41 §1.4`、`docs/48 §5`
**参考设计：** `docs/55`（C 批次终局载荷 —— 本包改动的最主要**出口**都由它建立）、`docs/37`（RPC 线形状）
**pi 锚点：** `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（本文件所有 pi 行号均指该锚点）

---

## 1. 范围

**做**：把 pi-java **归一化后**的停因字面量由 `"tool_use"` 改成 pi 的 `"toolUse"`。改动面是
「谁写这个词」（7 条生产者车道）、「谁读这个词」（两条持久化读路径）、「谁把它翻译一下」
（3 座只为这次分歧而存在的桥函数）、以及夹具与对外线格式的连带面。

**不做**（各自单独裁决，见 §10 登记）：

- **内容块判别字面量** `"tool_use"` —— 本仓内部仍是 `"tool_use"`、pi 是 `"toolCall"`。这**不是** B109，
  是 **B48**，且 `docs/37 §10` 已记录硬约束（`SessionJson` 只注册了 serializer、没有 deserializer
  ⇒ **读既有会话文件靠的正是注解里的判别名** ⇒ 注解不能改，RPC 线走节点级投影）。本包**一个字都不碰**。
- **Anthropic 线格原值** `stop_reason: "tool_use"` 与 `rawStopReason` —— provider 的原值，
  本包**不动**（§7 是本包的对照组）。
- pi `response.ts:309` 的「报了 toolUse 却没有任何工具块」守卫 —— java 全仓缺失，但那是**驱动循环的
  行为**，属另一子系统 ⇒ 登记 **B118**，本包不修。
- 停因闭集的**校验点**（今天 7 个生产者写自由字符串、拼错无人察觉）⇒ 登记 **B119**。
- pi 的 `"deferred"` 终局（B111）、`providerThinkingLevel`（B110）。

**验收判据**（沿用本仓既定口径）：**行为等价** —— 对同一个 provider 结果，java 落定的
`AssistantMessage.stopReason()` 与 pi 的 `output.stopReason` **逐字同值**；对外（终局帧 / RPC 转录 /
web wire / 遥测属性）投影出的字面量与 pi 对应面逐字同值。

---

## 2. 观测面与术语 —— 三个 `"tool_use"`

本仓的 `"tool_use"` 是**三件不同的事**共用一个写法。混掉任意两个都会造成静默缺陷：

| # | 是什么 | 出现处 | 本包 |
|---|---|---|---|
| ① | **归一化停因**（java 词表） | 7 条生产车道落进 `AssistantMessage.stopReason()` | **改**（→ `"toolUse"`） |
| ② | **内容块判别字面量**（java 内部块类型） | `ContentBlock` 的 `@JsonSubTypes`、`SessionJson:200`、`MessageJsonCodec:275`、`JsonEventMapper:273`、`HtmlExporter:214` | **不动**（B48） |
| ③ | **provider 线格原值 / Anthropic SDK 类型名** | `AnthropicMessagesApi:407` 的 `case` 标号、`rawStopReason`、`tool_result` 紧跟 `tool_use` 的协议约束 | **不动**（§7 对照组） |

`AnthropicMessagesApi:407` 一行里三者同现，是分辨它们的标本：

```java
case "tool_use" -> new MappedStopReason("tool_use", null);
//   ↑③ 线格原值（不动）        ↑① 归一化词表（本包改成 "toolUse"）
```

**观测面**（A-01 的教训，`docs/41`：同一字段在 SDK 对象／序列化 JSON／录制字节三层有三个访问器，
取证面必须全覆盖）：本包的观测面是 **SDK 访问器**（`stopReason()`）、**序列化 JSON**
（终局帧 / RPC / web wire）、**录制字节**（`target/traces/*.jsonl` 与既有会话文件）。

---

## 3. pi 侧取证

**类型**（`packages/ai/src/types.ts:419`）：

```ts
export type StopReason = "pending" | "stop" | "length" | "toolUse" | "error" | "aborted" | "deferred";
```

**pi 全仓 `"toolUse"` 作归一化取值的发射点**（`git grep -n toolUse 3390bd936 -- packages/ai/src`）：
`anthropic-messages.ts:1504`、`bedrock-converse-stream.ts:1167`、`google-generative-ai.ts:227`、
`google-vertex.ts:235`、`mistral-conversations.ts:940`、`openai-completions.ts:690`/`:1565`、
`openai-responses-shared.ts:594`。**八处，全部 camelCase，无例外。**

**pi 的翻译点**（`anthropic-messages.ts:1494-1518`）把线格原值映射到归一化值：

```ts
case "tool_use":
    return { stopReason: "toolUse" };   // :1503-1504
```

**pi 的消息通道**同样只发 camelCase（`packages/ai/src/api/pi-messages.ts:73`：
`reason: Extract<PiMessagesStopReason, "stop" | "length" | "toolUse">`）。

**pi 的驱动循环只在两处读这个字面量**：`packages/agent/src/harness/runtime/drive/response.ts:309`
（`else if (response.stopReason === "toolUse")` ⇒ provider error 守卫，见 B118）与
`session-repo.ts:62`/`:276`（夹具）。

**结论**：pi 侧 `"tool_use"` **只**作为 ③（线格原值 / SDK 类型名）出现，
**从不作为归一化后的取值**。java 的 ① 是本仓自造的偏差。

---

## 4. java 侧现状

1. **7 条生产车道各自落 `"tool_use"`**（§5.1），互不引用，没有单一来源。
2. **两座桥函数把它在边界上翻译**（§5.2）：`PiMessagesApi.mapDoneReason` 在**入站**
   （pi 发 `"toolUse"` → java 收 `"tool_use"`）、`ScriptedStreams.internalStopReason` 在**夹具侧**。
   `FrameNormalizer.stopReasonOf` 是第三座，方向相反（java → pi，供 L5 比对）。
3. **两条持久化读路径无校验**：`MessageJsonCodec:47` 用 `JsonlCodec.optionalString(node,"stopReason")`
   原样收下；`LaneRecord` 的 `stopReason` 是审计字段，同样原样往返。
4. **对外面**：终局帧（C 批次新建的 `JsonEventMapper.terminalWireNode`）、RPC `Message` 投影、
   web wire（`WebWireJson`）、遥测 span 属性（`PiLaneSink:224`）、HTML 导出、TUI 错误气泡。

### 4.1 存量数据是**真实的**，不是假想的

`~/.pi-java/agent/sessions/` 下有 **3353** 个会话 jsonl。抽样确认（`2026-09-20T15-35-16-336Z_*.jsonl`）：
**助手消息条目**与 **`step_attempt` 观测记录**里都在写 `"stopReason":"tool_use"`；全仓
`"stopReason":"toolUse"` **今日零命中**。⇒ 改词表是一次**真实迁移**，R2/R3 不是学术问题。

### 4.2 派发点审计：**零处**按 ① 分支

这是本包最重要的安全性结论。全仓主源码里按停因字面量分支的地方只有：

| 位置 | 比对的字面量 | 受本包影响 |
|---|---|---|
| `HarnessUtils.isErrorStopReason` | `"error"` / `"aborted"` | 否 |
| `PiLaneSink:389` / `:396` | `"error"` / `"length"` | 否 |
| `MistralConversationsApi:259` | `"tool_use"` —— **本车道自己的映射内部**，两侧同改 | 是（同批改） |
| `PiLoopRunner`（length 门、`markAborted` 的 `isSettled`） | `"length"` / `"pending"` | 否 |
| `ContextEntries.NON_PROJECTED_STOP_REASONS` | `{deferred, error, aborted}` | 否 |
| drive 循环的「有没有工具块」判定 | **看内容块、不看停因** | 否 |

⇒ ① 这个字符串**今天不参与任何行为决策**，只被**投影出去**。所以本包的**行为面风险为零**，
风险全在**兼容面**（旧文件）与**投影面**（对外字面量）。

---

## 5. 改动面清单

### 5.1 生产者（`pi-java-ai/src/main`，代码 11 处）

| 文件:行 | 现有 | 改成 |
|---|---|---|
| `AnthropicMessagesApi:407` | `new MappedStopReason("tool_use", null)` | `("toolUse", null)`（`case` 标号**不动**） |
| `GoogleGenerativeAiApi:237` | `stopReason = "tool_use"` | `"toolUse"` |
| `MistralConversationsApi:168` | `new MappedStopReason("tool_use", null)` | `"toolUse"` |
| `MistralConversationsApi:259` | `if ("tool_use".equals(stop.reason))` | `"toolUse"` |
| `OpenAICompletionsApi:258` | `toolCall.started() ? "tool_use" : "stop"` | `"toolUse"` |
| `OpenAICompletionsApi:314` | `new MappedStopReason("tool_use", null)` | `"toolUse"` |
| `ResponsesStreamProcessor:266` | `stop.reason = "tool_use"` | `"toolUse"` |
| `PiMessagesApi:130`/`:288-289` | `mapDoneReason(...)` 翻译 | **删函数**，内联 `done.reason()`（§5.2） |
| `FauxProvider:94` | `.withStopReason("tool_use")` | `"toolUse"` |
| `FauxProvider:103` | `StreamDone.settle("tool_use", finalMsg)` | `"toolUse"` |

javadoc 6 处：`AnthropicMessagesApi:386-390`（那对「刻意偏差」的第 1 条**整条作废**，因为偏差没有了）、
`OpenAICompletionsApi:29`/`:305`、`MistralConversationsApi:44`、`AssistantMessage:32`、
`Message:120`、`StreamEvent:293`。注释 3 处：`LaneState:300`（词汇表）、`HarnessUtils:139`、
`AnthropicMessagesApi:262`。⚠️ `PiLaneSink:110`/`:382`、`PiLoopRunner:317` 说的是**「tool_use 块」＝②**，**不改**。

### 5.2 三座桥函数 —— 删除（决策 R4）

| 桥 | 现作用 | 对齐后 |
|---|---|---|
| `PiMessagesApi.mapDoneReason` | pi 的 `"toolUse"` → java 的 `"tool_use"` | 恒等 ⇒ 删函数 |
| `ScriptedStreams.internalStopReason` | 剧本的 `"toolUse"` → java 的 `"tool_use"` | 恒等 ⇒ 删函数 |
| `FrameNormalizer.stopReasonOf` | java 的 `"tool_use"` → pi 的 `"toolUse"` | 恒等 ⇒ 删函数（调用点 `:65`、`:166`） |

它们**只为这次分歧而存在**，是「桥」不是「移植对象」——与 C 批次删 `withErrorShape` 同形。
留着＝把已经为零的翻译固化成看不懂的代码。附带收益：`ScriptedStreams:116-124` 现在
**done 用翻译值、error 用原值**的不对称（`StreamError(response.stopReason(), …)` 直接用剧本原值）
会随函数一起消失。

### 5.3 对外投影 —— 自动跟随，且其中两处是**净收益**

- `JsonEventMapper.terminalWireNode` 写 `done.reason()`/`err.reason()` ⇒ 改成 `"toolUse"` 后，
  宿主线终局帧**首次**与 pi proxy 的声明一致（`agent/src/proxy.ts:49` 正是
  `Extract<StopReason, "stop" | "length" | "toolUse">`）。**这是 C 批次的一处残余，本包顺手关掉。**
- RPC `Message` / web wire / 遥测 span 属性同理，全部变成 pi 的字面量。

### 5.4 夹具（约 55 处 / 30 文件）

`ai`（`GoogleGenerativeAiApiTest`、`MistralConversationsApiTest`、`OpenAICompletionsApiTest`、
`PiMessagesApiTest`、`OpenAIResponsesApiTest`、`StreamEventSettlementTest`）、
`agent-core`（`PiLoopTest`、`LaneMessagesTest`、`AgentLoopL1Test`、`ToolBatch*Test`、
`HarnessTelemetrySpansTest`、`HarnessToolExecutionSpansTest`、`EngineFailureSettlementTest`、
`ContextUsageEstimatorTest`、`CrossLayerLengthGateTest`、`ContextEntriesTest`、
`RecordObservabilityCodecTest`、`FrameNormalizer`、`ScriptedStreams` 等）、
`coding-agent`（`RpcWireMessageShapeTest`、`SessionFailurePathTest`、`AgentSettledPayloadTest`、
`AgentSessionTool*Test`）、`telemetry`（`JsonlFileTelemetryTest`）、
`evals`（`ConformanceFixtures` ＋ `ChatApiConformanceSuite:74`）。

⚠️ `ChatApiConformanceSuite:74` 现在**两个字面量都放过**
（`!"tool_use".equals(…) && !"toolUse".equals(…)`）⇒ 对本包**零判别力**。收紧为只认 `"toolUse"`
（决策 R5），它才成为本包红灯的来源。

⚠️ 机械替换前必读 §2：`grep '"tool_use"'` 的命中里，②③ 类的**一个都不能碰**。

---

## 6. 决策点

| # | 决策点 | 建议 | 理由 |
|---|---|---|---|
| **R1** | 词表**载体**：字面量 / 共享常量 / enum | **保持字面量** | 与既有的 `"stop"`/`"length"`/`"error"`/`"aborted"` 一致；`PENDING_STOP_REASON` 之所以是常量，是因为它要一处**共享谓词**，不是因为它是词汇表成员。enum 化是一次独立重构（`AssistantMessage.stopReason()` 是 `String`、`rawStopReason` 是自由字符串），另立 |
| **R2** | **旧转录**（助手消息）读侧是否归一 | **归一**（`MessageJsonCodec.decode` 里 `"tool_use"` → `"toolUse"`） | 有 **3353** 个真实旧会话（§4.1）。不归一则外来字面量**从旧会话漏到对外面**（遥测属性、终局帧、RPC 转录、HTML）。代价一行 ＋ 一条夹具。这是**刻意的迁移垫片**，须在 javadoc 标明它**没有 pi 对应物**（pi 从未有过该字面量） |
| **R3** | **车道记录**（审计路径）的旧值 | **一并归一** | 与 R2 同源；让同仓出现两套口径正是 B20 已经判过的缺陷（「自家也不一致」）。反方意见：审计数据是历史事实、不该在读时改写 —— 若采纳反方，则改为「保持原样 ＋ 登记」，但须同时接受旧会话的观测记录与新会话不一致 |
| **R4** | 三座桥函数 | **删** | §5.2 |
| **R5** | conformance suite 的「两者都认」 | **收紧为只认 `"toolUse"`** | 否则本包最该有牙的地方没牙 |
| **R6** | pi `response.ts:309` 的守卫缺失 | **本包不修，登记 B118** | 是驱动循环的**行为**，属另一子系统，且要单独量化（今天会不会真触发）。塞进本包会让「词表包」变成「行为包」 |
| **R7** | 停因闭集是否加校验点 | **本包不加，登记 B119** | 是**新增约束**、非对齐项；且要先裁定「未知值抛还是退化」（pi 在 `mapStopReason` 的 default **抛**） |

---

## 7. 不变量与对照组

**唯一的不变量：③ 类字面量一个字不动。** 它同时是本包的**对照组**——
五条既有断言必须**从头到尾保持绿**，它们证明本包只动了映射侧：

| 断言 | 值 |
|---|---|
| `OpenAICompletionsApiTest:132` | `rawStopReason == "tool_calls"` |
| `AnthropicMessagesApiTest:102` | `rawStopReason == "max_tokens"` |
| `GoogleGenerativeAiApiTest:88` | `rawStopReason == "MAX_TOKENS"` |
| `MistralConversationsApiTest:86` | `rawStopReason == "model_length"` |
| `OpenAIResponsesApiTest:148`/`:166` | `incomplete.max_output_tokens` / `failed` |

同理由，`AnthropicMessagesApi:407` 的 `case "tool_use"` 标号与 `MessageJsonCodec:275`、
`SessionJson:200`、`JsonEventMapper:273`、`HtmlExporter:214` 的 ② 类**必须逐字不动**。

---

## 8. 先红计划与变异探针

⚠️ **本节有一条已实测推翻的预案，先读它再读计划。** 设计初稿把「L5 差分」列为最有价值的红灯源，
**实测不成立**：L5 对本案**结构上盲**，见 §8.1。红灯只能来自模块内的适配器夹具与 evals 真实车道。

**§8.1 L5 为什么抓不到本案（实测）**

三条独立理由，任一成立即够：

1. **剧本里的停因不由生产者产出** —— L5 的停因走 `ScriptedStreams.eventsFor` **合成**
   （`:116` 用剧本的 `stopReason` 造 `StreamDone`）。§5.1 的七条真生产者**根本不在 L5 的路径上**
   （`ConformanceRunner:88` 直连 `PiLoop`，provider 是 `driver::stream` 这个桩）。
2. **出口又被归一化掉** —— `ConformanceRunner:57`/`:89` 对**每一帧**过 `FrameNormalizer.frame`，
   停因在 `:65`/`:166` 被 `stopReasonOf` 翻译回 pi 的词表。
3. **两座桥是往返一致的** —— `ScriptedStreams.internalStopReason`（`toolUse`→`tool_use`，发射）
   与 `FrameNormalizer.stopReasonOf`（`tool_use`→`toolUse`，比对）互为逆。
   ⇒ 改生产者、删任一桥、删两桥、**任意顺序都绿**，红灯结构上取不到。

**实测佐证**：`conformance/pi-out/` 与 `conformance/java-out/` 里 `"stopReason":"toolUse"` 各 **33** 处
—— 两侧逐字相同，正是上述归一化的产物，而不是「两侧真的都发 `toolUse`」。

⇒ **L5 既不能当本案的红灯源，也不能当验收证据**（§11）。这条盲区自身登记为 **B120**。

**§8.2 先红（写在实施之前）**

1. **新词表断言**：一条「`StreamDone.reason()` / `partial().stopReason()` 必须是 `"toolUse"`」的
   用例 ⇒ 旧实现下红。
2. **★ R5 的收紧（本包唯一有牙的真实车道门）**：`ChatApiConformanceSuite:74` 现在两个字面量都放过。
   **先**收紧为只认 `"toolUse"`（此时 `FauxProvider:103` 仍发 `"tool_use"`）⇒ **红**；
   改完生产者 ⇒ 绿。**注意顺序**：收紧必须在改 `FauxProvider` **之前**落地，否则这条红灯取不到。
   这条路是真的 —— evals 跑的是真 `FauxProvider` 经真 `ChatApi`（`MODEL = faux/eval`）。

**§8.3 变异探针**

- M1：`AnthropicMessagesApi:407` 改回 `"tool_use"` ⇒ 预期 `ai` 模块若干红。
- M2：`FauxProvider:103` 改回 ⇒ 预期 evals conformance 红（R5 有没有牙的直接测量）。
- M3：`ResponsesStreamProcessor:266` 改回 ⇒ 测该车道覆盖。
- **M4（测 L5 在删桥后是否长出牙）**：删桥之后，把 `ScriptedStreams` 的出口改回发 `"tool_use"`
  ⇒ 预期 **L5 红**。这是「删桥」这个动作唯一的可测收益 —— 删桥**不会**让 L5 抓得到生产者回归，
  只让它对**夹具侧**回归有牙，别过度声称。

⚠️ **事先声明**：M1–M3 在 `agent-core` 的引擎夹具上应**零红** —— §4.2 已证引擎不按 ① 分支。
这属于 C 批次教训里的**「变异体语义等价」那一类零红**，不是「夹具没牙」。
按 C 批次的规矩：**先声明、再测量**，零红时如实记录成因，不当成通过。

---

## 9. 提交计划

1. `docs(ai): B109 归一化停因词表的设计` —— 本文件（先行落地再动代码）。
2. `feat(ai): 归一化停因词表对齐 pi（tool_use → toolUse）` —— §5.1 七条生产者 ＋ javadoc。
3. `refactor(ai): 删除 pi 消息边界的停因翻译桥` —— `mapDoneReason`。
4. `refactor(agent-core): 停因词表对齐的夹具与注释` —— §5.4 ＋ 删 `internalStopReason`/`stopReasonOf`。
5. `test(coding-agent|telemetry|evals): 停因词表对齐的连带夹具` ＋ R5 收紧。
6. （若 R2/R3 采纳）`fix(agent-core): 旧转录读侧归一停因词表`。
7. `docs(ai): B109 闭环` —— `docs/32` B109 回填 ＋ `docs/41`/`docs/48` 相应行（**含文首 banner**，B91）。

---

## 10. 登记

- **B118**：pi `response.ts:309` 的守卫（「Provider reported tool use without any tool calls」）
  在 java 全仓**零命中**。pi 把这种自相矛盾判为 provider error，java 会落进 checkpoint 分支
  当正常收尾。**复核本包时顺带发现，与词表无关**（改前改后都是零）。
- **B119**：停因闭集**没有校验点** —— 7 个生产者写自由字符串，拼错（`"tooluse"`）无人察觉，
  `"stop"`/`"length"` 同理。有了单一词表后可在一处声明闭集、由 conformance 守卫。
- **B120**：**L5 差分把停因字段归一掉了**（§8.1 实测）—— `FrameNormalizer.stopReasonOf` 让
  `pi-out` 与 `java-out` 的这个字段**逐字相同**（各 33 处），于是**任何 L5 剧本都抓不到停因词表的
  回归**。这是差分层的一个结构性盲区，不只影响本案：凡是「两侧用不同词表、出口被归一」的字段
  都有同样问题。修法＝删桥（本包 R4 顺带做到夹具侧），但**生产者侧仍需模块内适配器夹具兜**
  —— 别指望 L5。
- **B121**：`AssistantMessage.stopReason()` 的类型仍是 `String`（pi 是联合类型）。`enum` 化会
  与 `rawStopReason`（自由字符串）和持久化形状冲突 ⇒ 需单独裁决（R1 的延伸）。

---

## 11. 未覆盖 / 今天不可观察

- **真实 provider 车道验证**：与 B20 的 D2 同样受限（relay 无 `/v1/messages` 路由）——
  本包不新增该限制，但**也没有解除**它。
- **旧会话的端到端读回**：§4.1 的 3353 个文件是**样本取证**，不是夹具。R2/R3 落地时夹具从
  真实样本的字节构造，**测试不得依赖 `~/.pi-java` 的存在**。
- **L5 剧本的覆盖**：已实测 —— `pi-out`/`java-out` 各 **33** 处 `toolUse`，覆盖是有的；
  但如 §8.1 所述，**覆盖不等于能判别**：这条路对本案结构上盲。别再把它当验收证据（B120）。
- **`/resume` 一条旧会话后**的观测面（遥测属性 / 终局帧）今日无夹具 —— 若 R2/R3 均不采纳，
  这里就是外来字面量的实际泄漏路径。
