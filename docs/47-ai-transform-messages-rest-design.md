# 47 - 包B14：`transformMessages` 剩余三条变换

> **状态**：已批准（2026-09-23，§8 七点全按推荐执行）。实施中。
> **上游**：`docs/41-gap-inventory.md §1.1` 台账 **B14**（一条聚合行，带逐条出处）。
> **前身**：`docs/31 §8.34`（包② 落「闸」骨架，只做 thinking 五分支）＋ `docs/44`（包H2 追加图片降级）。
> **锚点**：pi `3390bd936`。**取证一律 `git show 3390bd936:<path>`，不读工作树**。
> **参照文件**：`packages/ai/src/api/transform-messages.ts`（235 行，本包唯一蓝图）。

---

## 1. 这一包解决什么

`TransformMessages`（`pi-java-ai`）今天只落了两条变换：thinking 五分支（包②，`docs/31 §8.34`）
与图片降级（包H2，`docs/44`）。`docs/31:4245` 登记 B14 时，`transform-messages.ts` 还剩
**四条**；图片降级随 H2 落地后 **B14 收窄为三条**（`docs/41:54`）：

| # | pi 出处 | 变换 | 用户可观察后果（若不补） |
|---|---|---|---|
| ② | `:131-134` | 跨模型**剥离** toolCall 的 `thoughtSignature` | 见 §3-D1：**在 java 上结构性不可达** |
| ③ | `:136-142` | 跨模型**归一** toolCall id | 跨模型重放时 id 带 `\|`／超 64 字符 ⇒ **Anthropic provider 400** |
| ④ | `:158-232` | **孤儿 toolCall 合成 `toolResult`** ＋ 跳过 `error`/`aborted` 助手消息 | 转录以「有 `tool_use` 无 `tool_result`」结尾 ⇒ **Anthropic/Google provider 400** |

③ 与 ④ 都有**硬故障后果**（provider 400，整轮对话不可用），且 ④ 是 pi 自己在
`CHANGELOG.md:1051` 点名的修复（「transcripts that end with unresolved assistant tool calls
during **direct low-level history replay**」）。本包的目标就是关掉这两条。

### 1.1 头条：② 在 java 上**不可达**，且理由不是「没接线」

pi 的 `ToolCall.thoughtSignature`（`types.ts:391`）在 java 的
`ContentBlock.ToolUseContent`（`ContentBlock.java:93`）上**没有对应字段**：

```java
record ToolUseContent(String id, String name, Map<String, Object> arguments) implements ContentBlock
```

且全树 `grep thoughtSignature` 在 `pi-java-ai` / `agent-core` / `coding-agent` 的 `src/main`
里**只命中两条注释**（`TransformMessages.java:23` 与 `:29`，都是「B14 不做」的说明）
—— **零生产生产者、零生产消费者**。

这不是 H5 那种「形状被发明」的反向（H5：java 有 pi 没有的形状）。这里是
**pi 有、java 完全没有的一条管道**，而 ② 只是那条管道的**下游消费者**：

| 环节 | pi 出处 | java 现状 |
|---|---|---|
| 产出（流式解析） | `google-generative-ai.ts:207`、`google-vertex.ts:215`、`openai-completions.ts:1306` | 无 |
| 消费（落线回放） | `google-shared.ts:266-273`（`functionCall` part 的 `thoughtSignature`） | 无（`GoogleMessageConverter:112-124` 不写该键） |
| **剥离（跨模型）** | **`transform-messages.ts:131-134`** | **无** |
| 部分帧透传 | `assistant-message-frame.ts:72/:278/:471-473` | 无（H5 的 partial 投影只覆盖 thinking 五分支） |

⇒ 只补 ② 等于**写一段永远不执行的删除逻辑**——正是 `docs/31:4254` 明文禁止的
「不留投机骨架」。**§3-D1 建议把 ② 拆出去另立一包**（含 Google thought-signature 往返
的产出＋消费＋剥离），本包只做 ③＋④。

### 1.2 连带发现：java 的 Responses 车道**不建复合 id**

pi 在流式解析时把 Responses 的 `call_id` 与 `item.id` 拼成 **`{call_id}|{item_id}`**
（`openai-responses-shared.ts:488` 与 `:509`），回放时再拆开
（`:290`、`:332`）。java 的 `ResponsesStreamProcessor:210/:212/:227` **只用
`fc.callId()`**，`item.id` 直接丢弃。

后果：**java 自己产出的 id 永不含 `|`** ⇒ ③ 里所有 `id.includes("|")` 的分支在
java 上不可达（**登记，见 §7-R3**）；同时 pi→java 的会话迁移会丢 `fc_*` item id
（**另一条独立缺口，登记为 §7-R4，不在本包**）。

---

## 2. 命题表

### 2.1 pi 侧（行为，逐条给出处）

**第一遍（`imageAwareMessages.map`，`:77-156`）**

- **P1** `:79-81` `system` 与 `user` 消息**原样放行**（`return msg`）。
- **P2** `:84-90` `toolResult`：若 `toolCallIdMap` 里有映射且**不等于**原值 ⇒
  换 `toolCallId`；否则原样。**依赖助手消息先于其结果出现**（同一 `map` 顺序）。
- **P3** `:95-98` `isSameModel` ＝ `assistantMsg.provider === model.provider &&
  assistantMsg.api === model.api && assistantMsg.model === model.id`。**`===` 语义**
  （老会话的身份三元组是 `undefined` ⇒ 判**异**）。
- **P4** `:131-134` `!isSameModel && toolCall.thoughtSignature` ⇒ 复制后
  `delete thoughtSignature`。**同模型保留**。
- **P5** `:136-142` `!isSameModel && normalizeToolCallId` ⇒ 调
  `normalizeToolCallId(toolCall.id, model, assistantMsg)`；**返回值与原值不同**时
  才 (a) 记入 `toolCallIdMap`、(b) 换 `id`。**归一器可返回原值 ⇒ 不记映射**。
- **P6** `:147` 其余块类型**原样返回**。

**第二遍（`for` 循环 ＋ `closePendingToolCalls`，`:158-232`）**

- **P7** `:167-186` `closePendingToolCalls`：对每个 `pendingToolCalls` 中
  **未被 `existingToolResultIds` 覆盖**的调用，压入一条合成结果：
  `{role:"toolResult", toolCallId: tc.id, toolName: tc.name,
    content:[{type:"text", text:"No result provided"}], isError: true, timestamp: Date.now()}`。
  随后**清空** `pendingToolCalls` 与 `existingToolResultIds`，再**把 `heldSystemMessages`
  全部压入并清空**（顺序：先合成结果，后持有的系统消息）。
- **P8** `:191-193` 遇到 `assistant`：**先** `closePendingToolCalls()`（关上一轮的）。
- **P9** `:201-203` `stopReason === "error" || === "aborted"` ⇒ `continue`
  —— **整条消息不进 `result`，且不更新 `pendingToolCalls`**。
- **P10** `:206-210` 该助手消息的 `toolCall` 块**非空**时 ⇒
  `pendingToolCalls = toolCalls`、`existingToolResultIds = new Set()`（**重置**）。
  **空则不触碰**（沿用上一轮的 pending —— 但上一轮已在 P8 关掉了，故实际恒为空）。
- **P11** `:212` 助手消息压入 `result`。
- **P12** `:213-215` `toolResult`：把 `msg.toolCallId` 加入 `existingToolResultIds`，压入。
- **P13** `:216-221` `system`：**若 `pendingToolCalls` 非空 ⇒ 暂存到
  `heldSystemMessages`**（不压入）；否则直接压入。**系统消息对工具调用记账透明**。
- **P14** `:222-225` `user`：`closePendingToolCalls()` 后压入。
- **P15** `:226-228` 其它角色（实际只有 `toolResult`/`system`/`user`/`assistant`）压入。
- **P16** `:232` 循环结束后**再调一次** `closePendingToolCalls()` —— 转录以未答调用结尾时
  在这里合成。

**归一器（五条车道，逐条逐字）**

- **P17** Anthropic（`anthropic-messages.ts:1208-1210`）：
  `id.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 64)`。**无长度前置判断，恒 slice**。
- **P18** Bedrock（`bedrock-converse-stream.ts:907-910`）：
  `sanitized = id.replace(/[^a-zA-Z0-9_-]/g,"_")`；`length > 64 ? slice(0,64) : sanitized`。
  **与 P17 出参等价**（slice 在短串上是恒等）。⚠️ **java 无 Bedrock 车道**（§2.2-P22）。
- **P19** Google（`google-shared.ts:195-198`）：
  `if (!requiresToolCallId(model.id)) return id;`
  否则 `id.replace(/[^a-zA-Z0-9_-]/g,"_").slice(0,64)`。
  `requiresToolCallId`（`:165-172`）＝ `claude-*` 前缀 ∨ `gpt-oss-*` 前缀 ∨
  `gemini(-live)?-(\d+)` 且主版本 ≥ 3。**其余模型归一是恒等变换**。
- **P20** Completions（`openai-completions.ts:1194-1218`）：
  - `|` 在场：`callId = id[:idx]` 净化（**允许 `_-`**）、`itemId = id[idx+1:]` 净化；
    `combined = itemId 非空 ? callId + "_" + itemId : callId`；
    `combined.length <= 40` ⇒ 返回 `combined`；
    否则 `hash = shortHash(id).slice(0,8)`，
    `prefix = callId.slice(0, max(1, 40 - hash.length - 1))` ⇒ 返回 `prefix + "_" + hash`。
  - 无 `|` 且 `model.provider === "openai"`：`id.length > 40 ? id.slice(0,40) : id`。
  - 其余：**原样**。
- **P21** Responses（`openai-responses-shared.ts:163-175`）：
  - `!allowedToolCallProviders.has(model.provider)` ⇒ `normalizeIdPart(id)`。
    `allowedToolCallProviders = OPENAI_TOOL_CALL_PROVIDERS = {"openai","openai-codex","opencode"}`
    （`openai-responses.ts:31`）。
  - `!id.includes("|")` ⇒ `normalizeIdPart(id)`。
  - 否则拆 `[callId, itemId]`：`normalizedCallId = normalizeIdPart(callId)`；
    `isForeignToolCall = source.provider !== model.provider || source.api !== model.api`；
    `normalizedItemId = isForeignToolCall ? "fc_" + shortHash(itemId) 截 64 : normalizeIdPart(itemId)`；
    若 `!normalizedItemId.startsWith("fc_")` ⇒ `normalizeIdPart("fc_" + normalizedItemId)`；
    返回 `normalizedCallId + "|" + normalizedItemId`。
  - `normalizeIdPart`（`:152-157`）：`part.replace(/[^a-zA-Z0-9_-]/g,"_")` ⇒ 截 64 ⇒
    **`replace(/_+$/,"")`（剥尾部下划线）**。⚠️ 这条尾巴是 Responses 独有的。
- **P22** Mistral（`mistral-conversations.ts:232-252`）：
  **有状态**归一器，`idMap`/`reverseMap` 在**一次 `convertMessages` 调用内**存活。
  `deriveMistralToolCallId(id, attempt)`：`normalized = id.replace(/[^a-zA-Z0-9]/g,"")`
  （⚠️ **不允许 `_`/`-`**，与其他四条不同）；`attempt === 0 && normalized.length === 9`
  ⇒ 返回 `normalized`；否则 `seed = attempt===0 ? (normalized || id) : seedBase + ":" + attempt`，
  `shortHash(seed).replace(/[^a-zA-Z0-9]/g,"").slice(0, 9)`。
  冲突（`reverseMap` 里已有别人）⇒ `attempt++` 重试。`MISTRAL_TOOL_CALL_ID_LENGTH = 9`。
- **P23** `shortHash`（`utils/hash.ts:2-13`）：FNV-ish 双通道 32 位乘法哈希，
  返回 `(h2>>>0).toString(36) + (h1>>>0).toString(36)`。

### 2.2 pi-java 侧（现状）

- **P24** `TransformMessages.apply(List<Message>, ModelId<?>, String apiName, ModelInfo)`
  （`TransformMessages.java:59`）—— **4 参，无归一器形参**。第一遍只做 thinking 五分支
  （`gateAssistant`/`gateBlock`，`:192-246`），`toolResult` 分支明写「id 归一是 B14 ⇒ 此处恒原样」
  （`:69-71`）。**第二遍完全不存在**。
- **P25** 五条车道调 `apply`（＋Azure 经 `ResponsesMessageConverter`，共 **6 个调用点**）：
  `AnthropicMessagesApi:409`、`GoogleGenerativeAiApi:108`、`MistralConversationsApi:305`、
  `OpenAICompletionsMessageConverter:93`、`ResponsesMessageConverter:120`。
  ⚠️ `PiMessagesApi` **刻意不挂**（它不是 pi 的车道，`docs/31 §8.34.4`）。
- **P26** `ContentBlock.ToolUseContent`（`:93`）**无 `thoughtSignature`**；
  `ContentBlock.TextContent` **无 `textSignature`**。Google 车道落线
  （`GoogleMessageConverter:99-130`）不写这两个键；thinking 块在 Google 车道上
  **整块丢弃**（`blockParts:298-299`）。
- **P27** `Message.ToolResultMessage`（`Message.java:200-204`）**无 `timestamp` 字段**
  （pi `types.ts:539-551` 是**必填** `timestamp: number`）。`UserMessage` 同样无。
  `AssistantMessage` 有 `timestamp`。
- **P28** `Message` 的 sealed 变体只有 `UserMessage` / `AssistantMessage` /
  `ToolResultMessage`（`Message.java:13-22`）—— **没有 `SystemMessage`**
  （`docs/41 §1.1` 单列一条，权重 3）。
- **P29** 停止原因是 **`String`**（可空）：`"error"`（`RunFailure:82`、四车道 `stop.reason`）
  与 `"aborted"`（`PiLoopRunner:265`、`LlmSummaryGenerator:168`）都是**生产可达**的取值。
  `agent-core` 已有 `HarnessUtils.isErrorStopReason`（`HarnessUtils:126`），但
  **`ai` 不能依赖 `agent-core`**（依赖方向 `ai ← agent ← coding-agent`）。
- **P30** ⚠️ **会话级已有同形过滤**：`ContextEntries.NON_PROJECTED_STOP_REASONS =
  {deferred, error, aborted}`（`ContextEntries.java:49-50`），在 `project`（`:167`）
  里把这类助手消息**整条投影为 `null`** ⇒ **agent-core 路径上 P9 被遮蔽**。
- **P31** ⚠️ **P30 的 javadoc 与锚点不符**：`ContextEntries.java:10-18` 声称
  「pi's current code (`session/context.ts:72`) filters `deferred` only」。
  实测锚点 `3390bd936` 的 `packages/agent/src/harness/session/context.ts:24-29`
  是 `isContextMessage` = **过滤 `error` ∧ `aborted` ∧ `deferred` 三者**
  （`git grep isContextMessage` 全树只此一处）。**该注释需要更正**（§5-步0）。
- **P32** java 无 `shortHash` 对应物（`pi-java-ai/src/main/java/com/pijava/ai/utils/`
  只有 `ContextOverflow` / `RetryBackoff` / `RetryableError` / `SanitizeUnicode`）。
- **P33** java 的 `ResponsesStreamProcessor` 只用 `fc.callId()`（`:210/:212/:227`），
  **不建 `{call_id}|{item_id}`**（对比 pi `openai-responses-shared.ts:488/:509`）
  ⇒ P20/P21 的所有 `|` 分支在 java 上不可达。
- **P34** java 已有 `GoogleMessageConverter.requiresToolCallId(String)`（`:237`，
  包内可见）—— P19 的门可直接复用。

---

## 3. 设计决策

### D1 —— ② `thoughtSignature` 剥离：**不做，另立包**（推荐，见 §8 ①）

理由已在 §1.1 论证：字段不存在、零生产者、零消费者，只补剥离＝死代码。**不推荐**
「顺带把 Google thought-signature 往返也做了」——那是产出（流式解析）＋消费（落线）
＋部分帧透传三条管道，规模与 H5 同级，应自己一份设计（建议代号 **B14b**，见 §7-R1）。

### D2 —— ③ id 归一：**做，形状照 pi 的「函数式形参」**（推荐，见 §8 ②）

`TransformMessages.apply` 加第 5 个形参 `ToolCallIdNormalizer`（`@FunctionalInterface`），
并把 pi 的**可选性**用 4 参重载表达（pi 的形参是 `normalizeToolCallId?`，测试就调 4 参版）。

```java
package com.pijava.ai.api;

/** pi {@code transform-messages.ts:67} 的归一器形参（三参，java 用具体类型收窄）。 */
@FunctionalInterface
public interface ToolCallIdNormalizer {
    /**
     * @param id     原始 toolCall id
     * @param target 目标模型（pi 的 {@code model}）
     * @param source 发出该 toolCall 的助手消息（pi 的 {@code source: AssistantMessage}，
     *               只有 Responses 车道用得到 —— P21 的 {@code isForeignToolCall}）
     */
    String normalize(String id, ModelId<?> target, Message.AssistantMessage source);
}
```

五个实现放**各自车道的包内类**（镜像 pi 把归一器写在车道文件里的归属）：
`AnthropicToolCallIds` / `GoogleToolCallIds` / `CompletionsToolCallIds` /
`ResponsesToolCallIds` / `MistralToolCallIds`，统一落在
`com.pijava.ai.protocol`。每个都是 `static ToolCallIdNormalizer create()`（Mistral 有状态）
或 `static String normalize(...)`（其余无状态）。

⚠️ **`|` 分支照抄保留**（P33 说它在 java 不可达）——**但保留不等于死代码**：
`apply` 是**公开 API**，调用方可以传入任意 `Message` 列表（含从 pi 会话文件读来的
复合 id）。**不删分支、加注释说明来源**（与 `docs/44 §10` 的「车道侧能力门」同一处理）。

### D3 —— ④ 孤儿合成：**做**；合成结果的 `timestamp` **不设、登记**（推荐，见 §8 ③）

第二遍逐字照抄 P7–P16。**唯一偏差**：pi 的合成结果带 `timestamp: Date.now()`
（`:177`），java 的 `ToolResultMessage`（P27）**结构上没有该字段**。

- **不推荐**为此加字段：`timestamp` 是**本地元数据，永不上线**（六条车道的
  `convertToolResult` 都不读它）⇒ 偏差**在 provider 侧不可观察**；
  而加字段会牵动 `UserMessage`（同样缺 `timestamp`）与全部构造点，
  那是**另一条独立的形状缺口**，应自己一份设计（§7-R2）。
- 登记为「已知且不可观察的偏差」。

### D4 —— 跳过 `error`/`aborted`（P9）：**做，照抄**（推荐，见 §8 ④）

- **不因 P30 遮蔽而省略**：`apply` 是 `pi-java-ai` 的公开入口，低层重放路径
  （`SimpleApi` / `AiCli`，`CLAUDE.md` 列的 SDK 入口点）**不经过 `ContextEntries`**。
- **不做「中央收口」**：不在 `apply` 里替 `ContextEntries` 去重，也不删
  `ContextEntries` 的过滤 —— pi 两层都有，java 照两层（`docs/43 §9` 的同一裁决）。
- 停止原因是 `String`（P29），判断写成 `"error".equals(sr) || "aborted".equals(sr)`；
  **不引 `HarnessUtils`**（依赖方向不允许）。

### D5 —— 系统消息保持位（P13）：**不实现，登记**（推荐，见 §8 ⑤）

`heldSystemMessages` 整个机制的存在理由是「系统消息对工具调用记账透明」。
java **没有 `SystemMessage`**（P28）⇒ `Message` 的 sealed 穷举里根本走不到那个分支。
按「不留投机骨架」，**不写**；在第二遍的 `switch` 里用
`default -> result.add(msg)` 覆盖（java 的 sealed 穷举会强制处理全部三个变体，
`system` 分支无处可写）。**登记为「随 `SystemMessage` 包一起补」**（§7-R5）。

### D6 —— 归一器门控与 `requiresToolCallId`：**复用现成物**（推荐，见 §8 ⑥）

- Google 的 P19 门直接调 `GoogleMessageConverter.requiresToolCallId`（P34），
  **不新造第二个谓词**（避免两份真相）。
- Responses 的 `OPENAI_TOOL_CALL_PROVIDERS` 是**常量集合**，落
  `ResponsesToolCallIds` 的 `private static final Set<String>`。
- Completions 的 `model.provider === "openai"` 是**字面量比较**，照抄。
- ⚠️ **java 的 `ModelId.provider()` 取值必须与 pi 的 `ProviderId` 逐字对齐**——
  实施前取证（§10-1）。

### D7 —— 文件切分：**第二遍拆出去**（推荐，见 §8 ⑦）

`TransformMessages.java` 现 **247 行**。本包要加：第一遍的 `toolResult` 分支（P2）、
`toolCall` 的 P4/P5 分支、以及整个第二遍（P7–P16，约 60–80 行 Java）。
预估落点 **380–420 行**——**在 500 以内但很挤**，且第二遍与第一遍**职责不同**
（第一遍逐条改写、第二遍重建序列并**改变长度**）。

⇒ 推荐拆出 `OrphanToolResults`（`com.pijava.ai.api`，包内可见）：
`static List<Message> apply(List<Message> transformed)`，只做 P7–P16。
`TransformMessages.apply` 变成「第一遍 → `OrphanToolResults.apply`」两行。
**这也让 ④ 的夹具可以独立于 ③ 写**（红/绿互不掩盖）。

### D8 —— 不做（登记，不在本包）

见 §7。

---

## 4. 关键签名（完整 Java，供审核）

### 4.1 `com.pijava.ai.api.ToolCallIdNormalizer`（新增）

```java
package com.pijava.ai.api;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * pi {@code transform-messages.ts:67} 的 {@code normalizeToolCallId?} 形参。
 *
 * <p>三参形状逐字照 pi：{@code (id, model, source) => string}。只有 Responses
 * 车道用得到 {@code source}（{@code openai-responses-shared.ts:168} 的
 * {@code isForeignToolCall}）。</p>
 *
 * <p>⚠️ <b>返回原值是合法结果</b>：pi {@code :138} 只在
 * {@code normalizedId !== toolCall.id} 时才记映射、才换 id（命题 P5）。</p>
 */
@FunctionalInterface
public interface ToolCallIdNormalizer {

    String normalize(String id, ModelId<?> target, Message.AssistantMessage source);
}
```

### 4.2 `com.pijava.ai.utils.ShortHash`（新增）

```java
package com.pijava.ai.utils;

/**
 * pi {@code utils/hash.ts:2-13} 的逐字移植。
 *
 * <p>⚠️ 三处 Java 陷阱（照抄时最容易走样的地方）：</p>
 * <ol>
 *   <li>{@code Math.imul(a,b)} ≡ Java {@code int} 乘法（两者都是 32 位回绕）</li>
 *   <li>{@code (x >>> 0).toString(36)} ≡ {@link Integer#toUnsignedString(int, int)}</li>
 *   <li>常量 {@code 2654435761} / {@code 2246822507} / {@code 3266489909} 与
 *       {@code 1597334677} 都 &gt; {@code Integer.MAX_VALUE} ⇒
 *       **十进制字面量在 Java 里编译不过**，须写 {@code (int) 2654435761L}
 *       （{@code 0xdeadbeef} / {@code 0x41c6ce57} 是十六进制，可直接用）</li>
 * </ol>
 *
 * <p>出参必须与 pi 逐字节相同：Completions（P20）与 Responses（P21）把它拼进
 * 线上 id，**差一个字符就是另一个 id**。</p>
 */
public final class ShortHash {

    private ShortHash() {}

    /** pi {@code shortHash(str)} —— 返回 base36 双通道拼接。 */
    public static String of(String str) {
        // ... 逐行照抄 hash.ts:3-12
    }
}
```

### 4.3 `TransformMessages`（改）

```java
/** 4 参重载：镜像 pi 形参的可选性（测试与「无归一器」调用方用）。 */
public static List<Message> apply(List<Message> messages, ModelId<?> target,
                                  String apiName, ModelInfo targetModel) {
    return apply(messages, target, apiName, targetModel, null);
}

/**
 * @param normalize pi {@code :67} 的归一器；{@code null} ＝ 不做 id 归一
 *                  （pi 的 {@code normalizeToolCallId?} 缺席）
 */
public static List<Message> apply(List<Message> messages, ModelId<?> target,
                                  String apiName, ModelInfo targetModel,
                                  ToolCallIdNormalizer normalize) {
    // 第一遍：图片降级 → 逐条闸（thinking 五分支 + toolResult id 换名 + toolCall 剥/换）
    // 第二遍：OrphanToolResults.apply(...)
}
```

### 4.4 `OrphanToolResults`（新增，包内可见）

```java
package com.pijava.ai.api;

/** pi {@code transform-messages.ts:158-232} 的第二遍。 */
final class OrphanToolResults {

    private static final String NO_RESULT_TEXT = "No result provided";

    private OrphanToolResults() {}

    static List<Message> apply(List<Message> transformed) { /* P7–P16 */ }
}
```

### 4.5 五个归一器（各自车道，签名统一）

```java
// com.pijava.ai.protocol.AnthropicToolCallIds（P17）
public final class AnthropicToolCallIds {
    private AnthropicToolCallIds() {}
    /** pi {@code anthropic-messages.ts:1208-1210}。 */
    public static ToolCallIdNormalizer create() {
        return (id, target, source) -> id.replaceAll("[^a-zA-Z0-9_-]", "_")
            .substring(0, Math.min(64, id.replaceAll("[^a-zA-Z0-9_-]", "_").length()));
    }
}

// GoogleToolCallIds（P19，门用 GoogleMessageConverter.requiresToolCallId）
// CompletionsToolCallIds（P20，含 shortHash 冲突分支）
// ResponsesToolCallIds（P21，含 normalizeIdPart 的剥尾下划线）
// MistralToolCallIds（P22，有状态：idMap + reverseMap，create() 每次请求新建）
```

---

## 5. 实施步骤（每步：先红 → 实现 → 变异探针 → 回归 → 一个提交）

| 步 | 内容 | 先红形态 |
|---|---|---|
| **0** | 修 `ContextEntries` javadoc 的 P31 错述（纯文档，**无生产改动**） | 无（文档步，如实标注） |
| **1** | `ShortHash` ＋ 其夹具 | 桩：先返回 `""` ⇒ 夹具红 |
| **2** | `ToolCallIdNormalizer` 接口 ＋ 五车道归一器（**逐个夹具**） | 桩：先返回入参 ⇒ 5 组红 |
| **3** | `TransformMessages` 加第 5 形参 ＋ 第一遍的 P2/P4/P5 | 编译红 → 桩红 |
| **4** | `OrphanToolResults`（P7–P16）＋ 夹具 | 桩：先返回入参 ⇒ 孤儿用例红 |
| **5** | 六车道接线（6 个调用点） | 端到端红（真请求体里 id 未归一／转录尾缺 `tool_result`） |
| **6** | pi 的 oracle 夹具照搬（`transform-messages-copilot-openai-to-anthropic.test.ts` 四例） | 与步 3/4 同批；**跨实现 oracle** |
| **7** | 回归 ＋ `docs/41` / `docs/31` 回填 ＋ 收尾台账 | — |

**⚠️ 每步验证一律带 `-am`**（`docs/46 §10-7`：不带 `-am` 会解析本地仓库旧构件，
连变异探针 runner 都会静默无输出）。

---

## 6. 验收

- 全 reactor `mvn clean verify` **BUILD SUCCESS**，14/14 模块，checkstyle 0 违规，无 `System.out`。
- `pi-java-ai` 测试数 **740 → ≥ 770**（预估 +30：ShortHash ~6、五归一器 ~15、
  第一遍 ~4、第二遍 ~10，去重后）。
- L5 ConformanceTest **15/15**（本包不动 agent-core 行为 ⇒ 预期零变化；若变红须解释）。
- 夹具数（含 oracle 4 例）**全部有牙**：每条先答「它在什么情况下会红」。

---

## 7. 遗留登记（本包预计产出）

| 号 | 内容 | 处理 |
|---|---|---|
| **R1** | **Google thought-signature 往返**（产出 `google-generative-ai.ts:207`／消费 `google-shared.ts:266-273`／剥离 `transform-messages.ts:131-134`／部分帧 `assistant-message-frame.ts:72/:278/:471-473`）—— 建议代号 **B14b**，须自己一份设计 | 另立包 |
| **R2** | `ToolResultMessage` / `UserMessage` 缺 `timestamp`（pi `types.ts:513/:550` 必填） | 另立形状包 |
| **R3** | P20/P21 的 `\|` 分支在 java 不可达（P33：Responses 车道不建复合 id） | 照抄保留 ＋ 注释 |
| **R4** | java 的 Responses 车道**丢 `item.id`**（pi `openai-responses-shared.ts:488/:509` 拼、`:290/:332` 拆）⇒ pi→java 会话迁移丢 `fc_*` | 另立包 |
| **R5** | `heldSystemMessages`（P13）随 `SystemMessage`（`docs/41 §1.1` 权重 3）一起补 | 挂靠 |
| **R6** | `ContentBlock.TextContent` 无 `textSignature`（pi `google-shared.ts:235/:243`） | 并入 R1 |
| **R7** | P31 的 `ContextEntries` javadoc 错述 | **本包步 0 修** |

---

## 8. 待裁决（七点，请审核时给结论）

### ① ② `thoughtSignature` 剥离：**不做、另立包 B14b**（推荐） vs 本包连 Google 往返一起做

推荐理由见 §1.1（字段不存在 ⇒ 只补剥离是死代码，`docs/31:4254` 明文禁止投机骨架）。
若选「一起做」，本包规模翻倍（产出＋消费＋部分帧三条管道），应改代号并重写本文。

### ② ③ id 归一：**做，函数式形参**（推荐） vs 只在 Anthropic 车道内做

推荐：位置照 pi（闸在适配器**之外**，`docs/31 §8.34.4` 决策 1 已定调）；
五条车道各一份归一器。不推荐「只做 Anthropic」——Google 的 `requiresToolCallId`
门（P19）与 Completions 的 40 字符截断（P20）各有**不同的线上约束**。

### ③ 合成结果的 `timestamp`：**不设、登记不可观察偏差**（推荐） vs 加字段

推荐理由见 D3。

### ④ `error`/`aborted` 跳过：**做，照抄**（推荐） vs 因 P30 遮蔽而省略

推荐理由见 D4。

### ⑤ `heldSystemMessages`：**不实现、登记**（推荐） vs 提前搭骨架

推荐理由见 D5。

### ⑥ 归一器的门控：**复用 `GoogleMessageConverter.requiresToolCallId`**（推荐） vs 新造谓词

推荐理由见 D6。⚠️ 若选「新造」，两份真相会在 Google 版本门（≥3）上漂移。

### ⑦ 文件切分：**第二遍拆 `OrphanToolResults`**（推荐） vs 全留在 `TransformMessages`

推荐理由见 D7（职责不同 ＋ 夹具可分离 ＋ 500 行余量）。

---

## 9. 不在本包范围

- ② `thoughtSignature`（§8 ①，⇒ B14b）。
- `SystemMessage` / `normalizeContext` / `TranscriptContext`（`docs/41 §1.1`，各有权重）。
- Responses 的复合 id（R4）与 `namespace` 字段（`types.ts:393`）。
- `lax-message-content`（pi `test/lax-message-content.test.ts`）：pi `:73` 的
  「`content == null ⇒ []`」在 java 上**结构性不需要**（`Message` 的紧凑构造器已
  `List.copyOf`，`TransformMessages.java:61-62` 已论证）——**不补**。
- `assistant-message-frame.ts` 的 `thoughtSignature` 部分帧（并入 R1）。

---

## 10. 待补取证（实施前必须闭合）

1. ~~**`ModelId.provider()` 的取值面**~~ —— **✅ 设计期已实测闭合**（2026-09-23）：
   `ModelId.provider()` 是裸 `String`（`ModelId.java:11`），取值由目录决定。
   内置目录（`BuiltinCatalog:152-157` ＋ `imageModel:26` ＋ `embeddingModel:35`）
   只产出 **`anthropic` / `openai` / `google` / `deepseek` / `mistral` / `unknown` /
   `openrouter-images`** 七个。
   ⇒ **P20 的 `provider === "openai"` 在 java 可达**；
   **P21 的 `allowedToolCallProviders = {openai, openai-codex, opencode}` 只有
   `openai` 一个能从内置目录命中**，`openai-codex` / `opencode` / `github-copilot`
   需经 `models.json` 自定义 provider（`OAuthProviders:42/:48` 已认得后两者的**凭证**，
   但目录侧无内置条目）。
   ⚠️ **夹具不得依赖内置目录里没有的 provider** —— 要测 P21 的
   `allowedToolCallProviders` 分支，夹具必须显式构造
   `ModelId.of("openai-codex", …)`（而不是从目录查），否则该分支**没牙**。
2. **④ 的低层可达性**：`SimpleApi` / `AiCli` 是否存在一条真会传入
   「有 `tool_use` 无 `tool_result`」转录的调用路径（pi 的 `CHANGELOG.md:1051`
   点名的是 "direct low-level history replay"）。**取证方式**：构造该转录跑
   `AiCli`，看当前是否 provider 400。**若证伪（不可达）**，④ 改为「照抄保留、
   夹具直调 `apply`」并如实登记 —— **不因此不做**（`apply` 是公开 API）。
3. **`pi-java-ai` 是否有 `Message.SystemMessage` 的隐式来源**（如 Jackson 反序列化
   未知 `role:"system"` 时落到哪个变体）—— 决定 D5 的 `default` 分支是否真不可达。
4. **`shortHash` 的 Java 移植出参比对**：用 pi 的 TS 实现跑一组输入（含空串、
   非 ASCII、长串），把结果钉进夹具 —— **不能只靠「照着写」**（P23 的
   `toString(36)` 与 `>>>` 边界最易走样）。
