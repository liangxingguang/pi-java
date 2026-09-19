# 37 - 包⑩：RPC 线的消息形状（B48）

> **文档约定**（用户 2026-09-19）：一个功能模块一份文档；`docs/32` 是唯一索引。
> 流程：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**；**用户审核 §8 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：命题**已逐条核过**（§2.3 的样本是**实测 dump**，不是读码推断）；
> §8 待审核。

---

## 1. 这一包解决什么

包⑨ 写夹具时撞出来的东西，一路追下去发现**远不止「少个 `role`」**：

> ### ⚠️ 头条：RPC 线在**带 timestamp 的真实消息**上**直接抛**
>
> `JsonEventMapper` 用的是**裸** `ObjectMapper` ＋ 两个 mixin，**没有注册 jsr310**。
> 而 `AbstractChatApi:73` 给每条消息挂 `Instant.now()`。⇒ `agent_end` 的
> `MAPPER.valueToTree(m)` 抛 `IllegalArgumentException: Java 8 date/time type
> java.time.Instant not supported`（**已用执行证实**，见 §2.3）。
>
> 按包⑦ 已核的隔离性（`SessionEventHub` 逐个 listener `catch (RuntimeException)`）
> ⇒ **丢该客户端的整帧 ＋ 一条 warning**。即：**生产上的 RPC 客户端收不到 `agent_end`**。
>
> **为什么没人发现**：`FauxProvider` 的 `FauxChatApi` **直接实现 `ChatApi`、不走
> `AbstractChatApi`** ⇒ 夹具里的消息 timestamp 恒 `null`、永不触发；而
> `RpcModeEndToEndTest:79` 只断言 `output.contains("\"type\":\"agent_end\"")` —— 也走 faux。
> **测试与生产在这一点上不同路。**

---

## 2. 命题表

### 2.1 pi 侧（行为定义）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | 线的形状就是 `JSON.stringify(message)` —— 消息对象**自带 `role` 字面量**、**自带 `timestamp`** | `ai/src/types.ts:417-470`（三个 interface 逐个声明 `role:` 与 `timestamp: number`） | **已核** |
| P2 | `UserMessage` = `{role:"user"; content: string \| (TextContent\|ImageContent)[]; timestamp}` —— ⚠️ `content` **允许是裸字符串** | `types.ts:417-421` | **已核** |
| P3 | `AssistantMessage` = `{role:"assistant"; content; api; provider; model; responseModel?; responseId?; providerThinkingLevel?; diagnostics?; usage; stopReason; deferred?; errorMessage?; rawStopReason?; endTurn?; timestamp}` | `types.ts:428-449` | **已核** |
| P4 | `ToolResultMessage` = `{role:"toolResult"; toolCallId; toolName; content; details?; usage?; addedToolNames?; isError; timestamp}` | `types.ts:452-468` | **已核** |
| P5 | 内容块的**判别字面量**：`{type:"text", text}`、`{type:"thinking", thinking, signature?, redacted?}`、`{type:"toolCall", id, name, arguments}`、`{type:"image", data, mimeType}` | `types.ts`（TextContent/ThinkingContent/ToolCall 各自声明） | **已核** |
| P6 | 消息经 `toJsonEvent` 时**原样透传**（非 `message_update` 一律 `return event`）⇒ 上述形状**逐字上线** | `modes/json-event.ts:48-51` | **已核** |

### 2.2 pi-java 侧（现状）

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P7 | `JsonEventMapper` 的 MAPPER = 裸 `ObjectMapper` ＋ `StreamEventMixin`/`MessageMixin`；**未注册任何 Module** | `JsonEventMapper.java:29-34` | **已核** |
| P8 | `AbstractChatApi:73` 在每次流起手取 `Instant.now()` 并挂到每个 partial 与终局消息上 ⇒ **生产消息带 `Instant`** | `AbstractChatApi.java:45-50`、`:72-73` | **已核** |
| P9 | ⚠️ **`toWire` 在带 `Instant` 的消息上抛** | **实测**：`TmpWireDumpTest` → `IllegalArgumentException … Instant not supported`（栈顶 `JsonEventMapper.java:70` 的 agent_end 支） | **已核** |
| P10 | `SessionJson`（**落盘**层）**有** `InstantEpochMsSerializer` ＋ 自写的 `MessageSerializer`/`ContentBlockSerializer` ⇒ 落盘与 RPC 线是**两条不同的投影** | `SessionJson.java:185-205` | **已核** |
| P11 | `WebWireJson` **手工构造**每个字段、且**刻意不带 timestamp**（注释原文「维持既有『wire 无消息 timestamp』的有意偏离」）⇒ web 线**不经过** Jackson 的 Instant ⇒ **不抛** | `WebWireJson.java:21-27`、`:58` | **已核** |
| P12 | `Message.role()` 是**接口方法**，三个 record 都实现它，但**无一把它作为 record 组件** ⇒ Jackson 不序列化 | `Message.java:16`、`:29`、`:174`、`:221`；**实测 dump 无 `role`** | **已核** |
| P13 | `ToolResultMessage` 的组件名是 **`toolUseId`**，pi 叫 **`toolCallId`** | `Message.java:200`；**实测 dump** | **已核** |
| P14 | `UserMessage`/`ToolResultMessage` **没有 timestamp 组件**（pi 两者都必填） | `Message.java:22`、`:200` | **已核** |
| P15 | `ContentBlock` 的 `@JsonTypeInfo` 判别值是 **`tool_use`**（pi 是 `toolCall`）、`ThinkingContent` 的字段名是 **`text`**（pi 是 `thinking`）、`signature`/`redacted` **恒写**（pi 可选、缺席即省略） | `ContentBlock.java:17-27`；**实测 dump** | **已核** |
| P16 | `addedToolNames` 在线上**恒写空数组**（pi 缺席即省略） | `Message.java:190-193`；**实测 dump** | **已核** |
| P17 | 前端/消费侧**不读**这些键的存在与否（web 线是另一条自建投影）⇒ 本包的可见影响面**只有 RPC 客户端** | `docs/34 §10`（web 线已核为自建） | **已核** |

### 2.3 实测样本（**证据，不是推断**）

用 `JsonEventMapper.toWire(agent_end)` 序列化 **一 user ＋ 一 assistant ＋ 一 toolResult**
（assistant 的 timestamp 置 null 以绕开 P9 的抛），实测输出：

```
{"content":[{"type":"text","text":"hi"}]}                              ← user
{"content":[{"type":"text","text":"hello"},
            {"type":"thinking","text":"why","signature":"","redacted":false},
            {"type":"tool_use","id":"c1","name":"bash","arguments":{"cmd":"ls"}}],
 "stopReason":"tool_use","api":"anthropic","provider":"anthropic","model":"m",
 "usage":{…},"rawStopReason":"tool_use"}                               ← assistant
{"toolUseId":"c1","toolName":"bash","content":[{"type":"text","text":"out"}],
 "details":{"k":"v"},"addedToolNames":[],"isError":false}              ← toolResult
```

**与 pi 的差**（逐条对上 P1–P5）：三处缺 `role`；两处缺 `timestamp`；`toolUseId`≠`toolCallId`；
`tool_use`≠`toolCall`；thinking 的 `text`≠`thinking`；`signature`/`redacted` 该省的在写；
`addedToolNames` 该省的在写。**外加 P9 的抛。**

---

## 3. 被推翻的命题

| # | 我原先说的 | 实测 | 后果 |
|---|---|---|---|
| **R1** | B48 是「少个 `role`、键名 `toolUseId` 要改」两条**小的** | **错得离谱**：追下去是 **9 处差异 ＋ 一处会抛**（P9）。而且**抛才是头条** —— 那条让 RPC 客户端**根本收不到 `agent_end`** | 范围完全改写 |
| **R2** | （§2.2 假设）「RPC 线的消息序列化是能跑通的，只是字段少」 | **错**：带 timestamp 就抛。此前所有 RPC 夹具都用 faux ⇒ timestamp 恒 null ⇒ **测试与生产不同路** | ⇒ 本包的**首要**工作是让那条路**不抛** |
| **R3** | 「web 线同病」 | **错**：web 线手工构造、刻意不带 timestamp（P11）⇒ 安全。**只有 RPC 线** | 范围收窄到一处 |

---

## 4. 审计（顺带发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **落盘层与 RPC 线是两条独立投影**，能力也不同：`SessionJson` 有自写 serializer（能把 Instant 写成本地毫秒、能改名），RPC 线**只有裸 Jackson ＋ mixin** | P7/P10 | ⇒ 修法可以**借** `SessionJson` 的手法（自写 serializer），也可以给它注册 Module —— **裁决点 A** |
| **B** | ⚠️ **测试与生产不同路**（P9 的成因）：`FauxProvider` 自带 `FauxChatApi`，**绕过** `AbstractChatApi` 的身份挂载 | `FauxProvider.java:145` 的 `FauxChatApi implements ChatApi` | ⇒ 夹具永远造不出「带身份/时间戳的消息」。**这是本包能藏这么久的结构性原因**，值得单独登记 |
| **C** | `RpcModeEndToEndTest:79` 只断言**类型字符串出现**，不断言 messages 的形状 | `RpcModeEndToEndTest.java:79` | ⇒ 换一条**能带上 timestamp** 的夹具才钉得住（§8.3） |
| **D** | pi 允许 `UserMessage.content` 是**裸字符串**；pi-java 恒为数组 | P2 | 写出去恒数组**不违反** pi 的读法（pi 两形态都收）⇒ **不做**（§6-4） |

---

## 5. 待核 / 裁决点

| # | 问题 | 结论 |
|---|---|---|
| **N1** | timestamp 怎么写 | **裁决点 A**：给 RPC 线注册一个 `Instant → epoch 毫秒` 的 serializer（**借 `SessionJson` 的既有实现**）vs 干脆不写 timestamp（像 web 线那样） |
| **N2** | `role` 怎么写 | **裁决点 B**：给三个 record 加 `role` 组件（改数据形状、churn 大）vs 在 `JsonEventMapper` 里手工 `put("role", …)`（像 `WebWireJson:28` 那样）vs 加 `@JsonTypeInfo`（会同时改落盘形状，**不动**） |
| **N3** | 内容块改名的爆炸半径 | `ContentBlock` 的 `@JsonTypeInfo` 是**全仓共用**的（落盘 `SessionJson` 另写了 serializer ⇒ 可能不受影响，**待核**）⇒ 若共用，改名会动落盘。**裁决点 C** |
| **N4** | `UserMessage.timestamp` / `ToolResultMessage.timestamp` | pi 必填。pi-java 的消息对象**没有**这两个字段（时间戳在 `Entry` 上）⇒ 要写就得从 `Entry` 取。**裁决点 D**：本包补 vs 登记不修 |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 给 `Message` 加 `@JsonTypeInfo` 来产出 `role` | 会**同时改变落盘形状**（`SessionJson` 虽自写 serializer，但风险面大）⇒ 取裁决 B 的另两条 |
| 2 | 把 `UserMessage.content` 改成允许裸字符串 | pi 两种形态都收（D）⇒ 写数组不违反读法 |
| 3 | 动 web 线（`WebWireJson`） | 它**已经是对的形状**（P11），且与 RPC 线是两条独立投影 |
| 4 | 让 `FauxProvider` 也挂身份/时间戳（§4-B） | 那是**测试基础设施**的改动，牵动既有全部 faux 夹具的行为 ⇒ **单独登记** |

---

## 8. 实施稿（步骤 ③ 的产物；**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决 | 后果 |
|---|---|---|
| **A** | **注册 `Instant → epoch 毫秒` 的 serializer**（复用 `SessionJson.InstantEpochMsSerializer` 的那套手法） | 与 pi 的 `timestamp: number`（Unix 毫秒）**同形**（P1）。⚠️ 备选「不写 timestamp」会与 pi 差一个必填键 |
| **B** | **在 `JsonEventMapper` 里手工 `put("role", …)`**（照 `WebWireJson:28` 的既有手法） | 只动一个模块、不动数据形状。⚠️ 三处（user/assistant/toolResult）各一行 |
| **C** | **本包只改 RPC 线**；内容块改名**先核清爆炸半径**再定 | 若 `ContentBlock` 的判别值只在 RPC 线用 ⇒ 改成 pi 的字面量；若与落盘共用 ⇒ 改成**线级投影**（另写，不动注解） |
| **D** | **本包补 `timestamp`**（从 `Entry` 取，见 §8.2） | 若取值链太长 ⇒ 退为「登记不修」，如实写明 |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-coding-agent` | **止血**：给 `JsonEventMapper` 注册 Instant serializer ⇒ 带 timestamp 的消息**不再抛** ＋ 夹具（钉 P9 会抛 → 不抛） |
| **2** | `pi-java-coding-agent` | `role` 补三处 ＋ `toolCallId` 改名 ＋ `addedToolNames`/`signature`/`redacted` 按 pi 的 `?` 省略 |
| **3** | `pi-java-coding-agent` | 内容块判别字面量（`tool_use`→`toolCall`、`thinking` 的 `text`→`thinking`）＋ `timestamp`（裁决 D） |
| **4** | `docs/37`＋`docs/32` | 实施记录 ＋ B48 结案 |

⚠️ **步 1 是止血**，单独一次提交 —— 即使后面几条谈不拢，这一条也必须先落。

### 8.2 精确改动（形状）

**(1) MAPPER**：

```java
private static final ObjectMapper MAPPER = new ObjectMapper()
    .addMixIn(StreamEvent.class, StreamEventMixin.class)
    .addMixIn(Message.class, MessageMixin.class)
    // 包⑩（docs/37）：pi 的 timestamp 是 Unix 毫秒数（types.ts 的 `timestamp: number`）。
    // 此前裸 ObjectMapper 没注册 jsr310 ⇒ 带 Instant 的消息**直接抛**，
    // 而 AbstractChatApi 给每条消息都挂了 Instant.now() ⇒ 生产上 RPC 客户端
    // 收不到 agent_end（SessionEventHub 逐个 listener catch 掉该帧）。
    .registerModule(new SimpleModule().addSerializer(Instant.class, …epochMilli…));
```

**(2) `role`**：`node.put("role", m instanceof Message.ToolResultMessage ? "toolResult" : m.role())`
—— **照 `WebWireJson:28` 的既有写法**。

**(3) 键名**：`toolUseId` → 线上写 `toolCallId`。

**(4) 省略语义**：`addedToolNames` 空 ⇒ 省；`signature` 空 / `redacted` false ⇒ 省。

**(5) `timestamp`（裁决 D）**：`Message` 本体没有该字段（P14）。⚠️ 实施时须先核
**RPC 线上消息的时间戳该从哪来**（`Entry` 有、消息没有）—— 若没有干净的取值链，
**退为登记**，不硬凑。

### 8.3 测试计划（**先红**）

| 模块 | 夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-coding-agent` | `RpcWireMessageShapeTest` | 6 | ① **带 timestamp 的消息不抛**（P9 的回归）② user 带 `role`/`timestamp` ③ assistant 带 `role`/`timestamp` ④ toolResult 带 `role`/`toolCallId`（**不是** `toolUseId`）⑤ 内容块判别值是 `toolCall`/`thinking` ⑥ 空 `addedToolNames`/空 signature/`redacted:false` 被省略 |
| 同上 | `RpcModeEndToEndTest` 扩 | 1 | ⑦ 端到端：一条**带 timestamp** 的 assistant 消息走到 RPC 线上仍然出现（钉 P9 的生产面） |

⚠️ ⑦ 需要一条**不经过 faux** 的路径 —— 见 §8.4 的「夹具缺口」。

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | 去掉 Instant serializer | ①⑦ 有牙 |
| P2 | 不写 `role` | ②③④ 有牙 |
| P3 | 写回 `toolUseId` | ④ 有牙 |
| P4 | `addedToolNames` 恒写 | ⑥ 有牙 |
| P5 | 判别值写回 `tool_use` | ⑤ 有牙 |

### 8.5 未覆盖（预登记）

- ⚠️ **夹具缺口（§4-B）**：`FauxProvider` 绕过 `AbstractChatApi` ⇒ **造不出带 timestamp 的
  消息**。⑦ 若做不到真生产路径，只能**手工构造带 `Instant` 的消息**喂给 mapper ——
  **那钉不住「生产会不会走到这里」**。如实写。
- **RPC 客户端侧**：没有真实的编辑器集成可测 ⇒ 判据沉默。
- **`ContentBlock` 改名的落盘影响**（裁决 C）：待核。

---

## 9. 下一步

用户审核 §8（含 §8.0 四个裁决点）之后才写代码；实施完成后追加**实施记录**。
⚠️ **其中步 1（止血）建议无论如何先落** —— 它是生产上的真故障。

---

## 10. 实施记录（2026-09-20，用户「通过 + 推 main」）

**§8.0 裁决已裁**：A ＝ **写 epoch 毫秒**；B ＝ **手工 put `role`**；C 的待核**有结果**
（见 §10.4-D1，是个硬约束）；D 退为登记（见 §10.5）。四步全部落地，新增 **6 条**断言，
五个探针**全部有牙**。

### 10.1 四步与改动面

| 步 | 改动 | 状态 |
|---|---|---|
| **1（止血）** | `JsonEventMapper` 注册 `Instant → epoch 毫秒` 的 serializer | ✅ **单独一次提交**（`5435666`） |
| **2–3** | 新的 `messageNode(Message)` 节点级投影：`role`、`toolCallId` 改名、内容块判别字面量、可选键按 pi 的 `?` 省略；三处调用点（`agent_end.messages`、`agent_settled` 的 `message`/`toolResults`）接上 | ✅ |
| **4** | 本文件 ＋ `docs/32` | ✅ |

### 10.2 实测红集（先红）

**包⑩ 整包：`RpcWireMessageShapeTest` 6 跑 6 红** —— 含**两条**
`IllegalArgumentException: Instant not supported`（止血的直接证据）。
⚠️ 为了让**步 1 单独可绿**，落地止血时先把尚未实现的 4 条断言移出夹具，
在步 2–3 里补回（如实记）。

### 10.3 变异探针（**红集实测，不预测**）

| # | 改坏什么 | 实测红集 | 条 |
|---|---|---|---|
| **P1** | 去掉 Instant serializer | `messageWithTimestampDoesNotThrow:50`、`timestampIsWrittenAsEpochMilliseconds:56`、`everyMessageCarriesItsRoleLiteral:74`（连带因抛而红） | **3** |
| **P2** | `role` 写死 | `everyMessageCarriesItsRoleLiteral:76`、`JsonEventMapperTest.agentSettledCarriesTheAssistantMessageWhenPresent:81` | **2** |
| **P3** | 写回 `toolUseId` | `toolResultUsesPiFieldNameToolCallId:91`、`JsonEventMapperTest…:84` | **2** |
| **P4** | 可选键恒写 | `absentOptionalKeysAreOmittedNotWrittenEmpty:135` | **1** |
| **P5** | 判别值写回 `tool_use` | `contentBlockDiscriminatorsMatchPi:115` | **1** |

⚠️ **P1 第一次没生效**：我用多行 perl 删注册块，**正则没匹配上**、探针等于没打，
红集读到 0。改用 Edit 工具才拿到真红集。**这是本项目第二次踩多行 sed/perl 的坑**
（首次在包⑤）—— 记在这里，别再犯。

### 10.4 与稿子的偏差（如实）

| # | 稿子写的 | 实际做的 | 理由 |
|---|---|---|---|
| **D1** | §8.0 裁决 C：「内容块改名先核爆炸半径再定」 | **核出了硬约束**：`SessionJson` 只注册了 `ContentBlock` 的 **serializer**、**没有 deserializer**（`SessionJson.java:194`）⇒ **读既有会话文件靠的正是注解里的判别名**（`tool_use` 等）。**改注解会让旧会话文件读不出来** | ⇒ 改用**节点级投影**（只在线这一层改名），这也是 `WebWireJson` 一直在手工构造块的原因 |
| **D2** | §8.1：步 2 与 步 3 分两次提交 | **合成一次** | 两者都在同一个新方法 `messageNode` 里，拆开任一步都不产生可独立验证的中间态 |
| **D3** | §8.2(5)：`timestamp`（裁决 D，「若无干净取值链则退为登记」） | **退为登记** | `Message` 本体没有 timestamp（时间戳在 `Entry` 上），而 `agent_end.messages` 是 `List<Message>` ⇒ **没有干净的取值链**。硬凑会把 Entry 的时间戳假装成消息的时间戳 |
| **D4** | §8.3 计划 7 条 | **6 条** | 没写「端到端带上 timestamp 走 RPC 线」那条 —— 见 §10.5 的夹具缺口 |

### 10.5 未覆盖（如数）

- ⚠️ **夹具缺口（A16）**：`FauxProvider` 绕过 `AbstractChatApi` ⇒ **造不出带 timestamp
  的消息**。本夹具**手工构造**带 `Instant` 的消息 ⇒ 它钉得住「mapper 会不会抛」，
  **钉不住「生产会不会走到这里」**。端到端那条因此没写。
- **`UserMessage`/`ToolResultMessage` 的 `timestamp`**（裁决 D）：**退为登记** —— pi 两者都
  必填，但 Java 的消息记录里没有这个字段（在 `Entry` 上）⇒ 线上仍缺这两个键。
- **`entry_appended.entry` 里的消息**：`Entry` 走 `MAPPER.valueToTree(a.entry())`，
  **不经过** 新的 `messageNode` ⇒ 那条路上的消息**仍是旧形状**。⇒ 登记。
- **`UserMessage.content` 的裸字符串形态**（§6-2）：不做。

### 10.6 全树验证 —— **BUILD SUCCESS（14 模块全绿）**

`mvn -o clean verify`：**14 个模块全部 SUCCESS**（TUI 03:44、Web 01:12、AI 38 s），
**spotbugs 全模块 `BugInstance size is 0`**，checkstyle 零违规。

模块计数（实测）：telemetry 31 · ai 448 · agent-core 487 · session-sqlite 35 ·
coding-agent **263**（＋6）· protocol 14 · server 2 · web 48 · TUI 209（1 skipped）。

> ⚠️ **本包的价值集中在步 1**：那一步修的是**生产上的真故障**（RPC 客户端收不到
> `agent_end`）。其余三步是形状对齐（对外可见性远低于步 1）。**两截的价值不对称**，
> 如实记。
