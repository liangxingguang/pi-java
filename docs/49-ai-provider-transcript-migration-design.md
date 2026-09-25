# 49 - 包 A2：`normalizeContext` 与 provider 消费迁移（设计）

**状态：设计待审核** —— 审核通过前不写任何实现代码（`docs/00 §3` 步骤 3、`docs/48 §4` 审核门）。
**基准：** pi-java `862f59a`（包 A1 收尾）；pi `3390bd936`（2026-09-20；transcript 重构提交 `9e05370b2`，2026-09-16，见 §2 命题 P0）
**设计期实测：** pi 的两个 oracle 已在本机跑过 —— `system-message-replay.test.ts` **9/9 绿**；五个行为探针（§7.6）**5/5 绿**，逐字输出见 §7.6
**上游：** [`48-ai-next-work-items-design.md`](48-ai-next-work-items-design.md) §2 的 `A-03`、§5 实施表的 A2 行、§10.3 遗留 L1–L4
**下游/相邻：** A3（工具增删状态线）、A4（prompt sections）、A7（`Model.compat` 扩展）、A15（Anthropic OAuth 请求分支）、B87①（系统消息回读）
**参考：** [`41-gap-inventory.md`](41-gap-inventory.md):57-58、[`32-open-items-register.md`](32-open-items-register.md) B67/B87、[`47-ai-transform-messages-rest-design.md`](47-ai-transform-messages-rest-design.md) §7-R5

---

## 1. 范围

### 1.1 本包做什么

A1 已经落地了数据形状（`Message.SystemMessage` 第四变体、`ToolReference`、`TranscriptContext`、`ContextNormalizer`），但 `ContextNormalizer` 在生产代码里**零调用者**，五条 provider 车道仍各自读 `StreamRequest.systemPrompt()` / `.tools()`。本包把这条通道接通，并补上「车道从 transcript 取系统提示与工具」所需的重放函数：

1. **请求形状收口**：`StreamRequest` 的 `systemPrompt`/`messages`/`tools` 三个组件换成 `TranscriptContext transcript`（pi 的 `TranscriptContext` branded 类型的 Java 对应物），legacy 形参保留为兼容构造器（内部调 `ContextNormalizer.normalize`）。类型上让「车道读不到 legacy 字段」这件事**成立**，而不是靠约定。
2. **重放 helper 移植**：`utils/transcript.ts` 的 `getInitialSystemMessage` / `getCurrentSystemMessage` / `getCurrentSystemPrompt` / `withoutInitialSystemMessage` / `getCurrentTools` / `collapseSystemMessages` / `resolveTranscript`；`utils/text.ts` 的 `contentText` / `getSystemMessageText` / `renderSystemMessageUpdate`。
3. **六条车道消费迁移**：Anthropic / Completions / Google / Mistral / Responses / PiMessages —— 系统文本改从「transcript 的前导系统消息」取，工具改从 `getCurrentTools`（重放 `toolsAdded`/`toolsRemoved`）取。
4. **R5（`heldSystemMessages`）**：`OrphanToolResults` 的 system 分支 —— 系统消息对工具调用记账**透明**（`docs/47 §7-R5`，A1 时期「java 没有 SystemMessage」的理由已消失）。
5. **`ModelCompat.supportsMidConvoSystemMessages`**：`resolveTranscript` 的判据字段（pi `types.ts:731`），A2 只落字段与消费点，**探测/目录接线归 A7**。
6. **PiMessages 线形状**：Java 现在发 `{systemPrompt, messages, tools}`，pi 自 `9e05370b2` 起发 `{messages}`（见 §4.2 F2）—— 一个裁决点（§9 R2）。

### 1.2 本包不做什么

- **A3**：`toolsAdded`/`toolsRemoved` 的**生产者**（agent/harness 侧）、`getToolStateChanges` / `toToolDeclaration` / `declarationsEqual` / `hasToolRedefinitions` / `hasNonAdditiveToolChanges` / `resolveTranscriptTools`、Anthropic `tool_addition`/`tool_removal`、completions 的 Kimi `system.tools`、Responses 的 `additional_tools`/`tool_search_call`。
- **A4**：`sections` 的生产、替换与差分；`SystemPromptBuilder` → sections 的迁移；`sections` 表达「删除」的形状变更（见 §9 R3 与 L3）。
- **A7**：`Model.compat` 的探测默认值与目录 / `models.json` 接线（本包只加字段与消费）。
- **A12/A15**：prompt sections 的宿主侧生产者；Anthropic OAuth 的 `Claude Code` 身份前缀与本包的 system 通道相邻但不合并。
- **B87①**：`MessageJsonCodec.decode` 对 `role:"system"` 的**回读**。本包不碰持久化路径（见 §10 L-A）。
- **P20/P21 类**：`TransformMessages` 的 id 归一、图片降级等既有行为**零改动**。
- 不新增 / 不删除任何 Maven 依赖（`docs/48 §4` 审核门 8 无触发）。

---

## 2. 命题表（逐条已核，`file:line` 为准）

> 核验方式：读 pi 工作树源码 + pi-java 工作树源码。带 ⚠️ 的是**设计期已实测**；带 🔎 的是**实施前必须再实测**（§7.4）。

| # | 命题 | pi 侧证据 | pi-java 侧证据 | 结论 |
|---|---|---|---|---|
| **P0** | pi 把「系统提示 + 工具声明」从 request 顶层搬进**消息流**，且公开入口归一 | `packages/ai/src/utils/transcript.ts:30` `normalizeContext`；`types.ts:617-634` `TranscriptContext`（branded） | 无 `normalizeContext` 调用者；`ContextNormalizer.java:24` 零生产调用点 | 缺口成立，本包闭 |
| **P1** | pi 的**公开入口**归一：`compat.stream/streamSimple`、`models.stream/streamSimple` 各调一次 `normalizeContext`，再把 transcript 交给 provider | `compat.ts:257`/`:283`、`models.ts:684`/`:704` | 无对应入口；`StreamRequest` 就是宿主→车道的唯一载体（`coding-agent/core/DefaultProviders.java:126-133`） | Java 的 `StreamApi.stream(StreamRequest, ApiOptions)` 同时扮演 pi 的**公开入口**与 **`StreamFunction` 车道面**两角 ⇒ §5.1 形状裁决 |
| **P2** | pi 的 `TranscriptContext` 只含 `messages`（branded，只有 `normalizeContext` 能产出） | `types.ts:628-634` | `TranscriptContext.java:8` 是普通 record（公开规范构造器） | Java 无法复刻 brand；用「**类型上没有 legacy 字段**」替代（§5.1） |
| **P3** | 五条车道**读系统文本**的位置由「request 字段」变成「前导系统消息」 | anthropic `:1043` `getInitialSystemMessage` + `:1044` `getSystemMessageText`；google `:374`/`:390`；completions `:1249`；mistral `:787-788`；responses `openai-responses-shared.ts:218-222` | `AnthropicRequestBuilder.java:46`、`GoogleGenerativeAiApi.java:290`、`OpenAICompletionsMessageConverter.java:84`、`MistralConversationsApi.java:293`、`ResponsesMessageConverter.java:112`（五处全读 `request.systemPrompt()`） | 缺口成立 |
| **P4** | 五条车道**读工具**的位置由「request 字段」变成「transcript 重放」 | anthropic `:1139` `getCurrentTools`、`azure:273`、google `:375`、mistral `:520`、completions `:807`、responses-shared `:178` | `AnthropicRequestBuilder.java:82`、`GoogleGenerativeAiApi.java:305`、`OpenAICompletionsMessageConverter.java:135`、`MistralConversationsApi.java:276`、`ResponsesMessageConverter.java:68`（全读 `request.tools()`） | 缺口成立 |
| **P5** | 车道入口先 `resolveTranscript`（按 `compat.supportsMidConvoSystemMessages` 决定折叠与否），再构建请求 | anthropic `:517`、mistral `:130`、completions `:305`/`:1191`、responses `:119`/`shared:149`、azure `:77` | 无对应物；`ModelCompat.java:77-80` 只有 4 个组件，**没有** `supportsMidConvoSystemMessages` | 缺口成立（§9 R4） |
| **P6** | Google（与 vertex/bedrock）**无条件折叠**：不查 compat | `google-generative-ai.ts:65` `collapseSystemMessages`；`google-shared.ts:192-193` `withoutInitialSystemMessage(collapseSystemMessages(context).messages)`；bedrock `:132` | `GoogleMessageConverter.java:92` 对 SystemMessage **抛异常**；`GoogleGenerativeAiApi.java:109` 直接把 `request.messages()` 交给转换器 | 缺口成立 |
| **P7** | Anthropic 把**前导**系统消息从会话数组里**切掉**（顶上另有 `params.system`），其余消息进 `convertMessages` | `anthropic-messages.ts:1042-1044` `conversationMessages = initialSystemMessage ? transformedMessages.slice(1) : transformedMessages` | `AnthropicRequestBuilder.java:51-80` 的循环会把 `SystemMessage` 当 **assistant** 文本块发出（`:74-75` 的角色三元） | 缺口成立（**静默错发**，见 F5） |
| **P8** | 「哪条系统消息算前导」判据是**数组下标 0**，不是「第一条 role=system」 | completions `:1249` `i === 0 ? … : renderSystemMessageUpdate(msg)`；mistral `:788` `index === 0 ? …`；responses-shared `:218` `sourceIndex++ === 0 && msg.role === "system"` | 无对应物 | 契约成立，**夹具与实现都必须按下标**（下标的偏一格是这类移植最容易走样的地方） |
| **P9** | 系统消息**对工具调用记账透明**：落在 toolCall 与结果之间的系统消息被**扣住**，等（含合成的）结果发完再发 | `transform-messages.ts:163-166`、`:184-185`、`:216-219` | `OrphanToolResults.java:59-63` 的 `else → throw new IllegalStateException("unreachable message role")` —— 该注释写于 A1 之前（「Message 只有 3 个变体」），**A1 加了第四变体后这个分支可达** | 缺口成立（**F1**，且是本包唯一「今天就能响亮撞到」的一条） |
| **P10** | 第一遍 transform 本就把 system 与 user 同等放行 | `transform-messages.ts:79` `if (msg.role === "system" \|\| msg.role === "user") return msg;` | `TransformMessages.java:99-102` 的 `else { out.add(msg); }` 已同义 | **无需改动**（本包只登记，不写代码） |
| **P11** | `sections` 的渲染顺序 = **插入顺序**（`Object.entries` / `Object.values`） | `utils/text.ts:17`/`:30`；`transcript.ts:80-81` | `Message.java:57-63` 紧凑构造器用 `Map.copyOf` ⇒ **顺序未定义** | 缺口成立（**F3**） |
| **P12** | `sections` 的值可为 `null`，语义是**删除**该具名段 | `transcript.ts:81-83`、`text.ts:27-37` | `Message.SystemMessage.sections` 是 `Map<String,String>`，`Map.copyOf` 还在 null 值上 NPE | 缺口成立，**A2 不改形状**（§9 R3，登记 L3） |
| **P13** | PiMessages 车道的 POST body 是 `{model, context, options}`，`context` **就是** `TranscriptContext`（只有 `messages`） | `pi-messages.ts:374-390`；`9e05370b2` 把该车道签名从 `Context` 改成 `TranscriptContext`（该 commit 的 `pi-messages.ts` diff 只改类型导入与两处形参） | `PiMessagesApi.java:164-186` 发的是 `{systemPrompt?, messages, tools?}`（引的 pi 行号是**重构前**的） | 缺口成立（**F2**，§9 R2） |
| **P14** | 车道 javadoc 引用的 pi 行号有三处指向**重构前**的位置 | `openai-completions.ts:1214` 现为 id 归一闭包的收尾 `}`；`openai-responses-shared.ts:175` 现为 `};`；`google-generative-ai.ts:380` 现落在 `getInitialSystemMessage`/`getSystemMessageText` 之间 | `OpenAICompletionsMessageConverter.java:82`、`ResponsesMessageConverter.java:110`、`GoogleGenerativeAiApi.java:288` | 缺口成立（**F4**，随本包更正） |
| **P15** | `contentText` 的默认分隔符是 `"\n"`；`getSystemMessageText` 用 `"\n\n"` 连段并**丢掉空段** | `text.ts:6-21` | 无对应物（两条私有 `extractText` 是别的东西：`OpenAICompletionsMessageConverter.java:288`、`ResponsesMessageConverter.java:372` 各自拼接、**不按 pi 的 `contentText` 语义**，本包不动它们） | 缺口成立 |
| **P16** | 空 `systemPrompt` + 空 `tools` ⇒ `normalizeContext` 返回原消息列表（不造空系统消息） | `transcript.ts:12-22`（`createInitialSystemMessage` 两空返 undefined），pi fixture `system-message-replay.test.ts:102-103` | `ContextNormalizer.java:36` 同义（`!= null && !isEmpty() \|\| !tools.isEmpty()`） | **已对齐**（A1 落），本包不改 |
| **P17** | A1 的重复守卫（消息已以系统消息开头则不再前置）**不是** pi 的行为 | `transcript.ts:30-33` 无条件前置 | `ContextNormalizer.java:30-34` | **刻意偏差**，A1-4 已登记；本包不改（边界见 §10 L-B） |
| **P18** | `StreamRequest.systemPrompt()` / `.tools()` / `.messages()` 的**全部读取点**都在 ai 模块的 6 个协议文件里 | — | `AnthropicRequestBuilder.java:28/:46/:82`、`GoogleGenerativeAiApi.java:109/:290/:305/:307`、`MistralConversationsApi.java:276-277/:293/:305`、`OpenAICompletionsMessageConverter.java:84/:93/:135`、`ResponsesMessageConverter.java:68/:112/:120`、`PiMessagesApi.java:166-167/:170/:173-175`；另有 1 处测试 `LaneTransformMessagesWiringTest.java:142` | **形状改造不会外溢**（宿主/evals/agent-core 全部只用兼容构造器：`DefaultProviders.java:126-133`、`ChatApiConformanceSuite.java:145/:174-177`、`ProviderSmokeTest.java:47`、`CrossLayerLengthGateTest.java:169`） |
| **P19** | `AbstractChatApi` 是六条车道（含 `AzureOpenAIResponsesApi`、`PiMessagesApi`）与 faux 桩的**唯一入缝**，`streamInternal(StreamRequest, SubmissionPublisher)` 是唯一抽象钩子 | — | `AbstractChatApi.java:38/:70/:95/:122/:242`；子类 `AnthropicMessagesApi.java:38`、`GoogleGenerativeAiApi.java:57`、`MistralConversationsApi.java:52`、`OpenAICompletionsApi.java:56`、`OpenAIResponsesApi.java:21`、`AzureOpenAIResponsesApi.java:27`、`PiMessagesApi.java:30`、`FauxProvider.java:164` | Java 的**归一收口点**在类型构造函数上（§5.1），不在 `AbstractChatApi`（后者零改动） |

---

## 3. pi 侧逐车道事实（`stream` 形参一律 `TranscriptContext`）

| 车道 | 折叠/保留 | 系统文本来源 → 落线字段 | 工具来源 → 落线字段 | 前导系统消息与会话数组的关系 |
|---|---|---|---|---|
| **anthropic-messages** | `resolveTranscript(ctx, getAnthropicCompat(model).supportsMidConvoSystemMessages)` `:517`；`currentTools = getCurrentTools(normalizedContext.messages)` `:518` | `getInitialSystemMessage` `:1043` → `getSystemMessageText` `:1044` → `params.system`（OAuth 时先 push Claude Code 身份串，`:1104-1122`） | `getCurrentTools(context.messages)` `:1139`（native 分支用 `initialTools` + `getDeclaredTools` 差集，`:1116-1134`，gated on `compat.supportsMidConvoToolChanges`） | **切掉前导**：`transformedMessages.slice(1)` `:1044`；后续系统消息由 `convertMessages` 就地转成 `{type:"text"}` 块（`renderSystemMessageUpdate` `:1253`），natively 才走到 |
| **openai-completions** | `resolveTranscript(ctx, getCompat(model).supportsMidConvoSystemMessages)` `:305`/`:1191` | 转换循环内 `i === 0 ? getSystemMessageText(msg) : renderSystemMessageUpdate(msg)` `:1249` → `{role: instructionRole}` 消息 `:1252`（`instructionRole = model.reasoning && compat.supportsDeveloperRole ? "developer" : "system"` `:1225`） | `resolveTranscriptTools(normalizedContext.messages, …)` `:807`/`:1221`（A3 的 `anchorsAdditions` 决定 `toolsAdded` 是否就地发 Kimi `system.tools`，`:1240-1246`） | **不切**：系统消息在数组里**就地**转成指令消息 |
| **google-generative-ai** | **无条件** `collapseSystemMessages(context)` `:65` | `getInitialSystemMessage` `:374` → `getSystemMessageText` `:390` → `systemInstruction` `:393`（净化整串） | `getCurrentTools(context.messages)` `:375` → `functionDeclarations` | `google-shared.ts:193` `withoutInitialSystemMessage(collapseSystemMessages(context).messages)` ⇒ contents 里**没有**系统项 |
| **mistral-conversations** | `resolveTranscript(ctx, model.compat?.supportsMidConvoSystemMessages)` `:130` | `toChatMessages` 内 `index === 0 ? getSystemMessageText(msg) : renderSystemMessageUpdate(msg)` `:787-789` → 就地 `{role:"system", content}` 进 **messages 数组**（无顶层 `system` 键） | `getCurrentTools(context.messages)` `:520` → `payload.tools` | **不切**：就地转 `{role:"system"}` |
| **openai-responses**（含 azure） | `resolveTranscript(ctx, getCompat(model).supportsMidConvoSystemMessages)` `:119` / `azure:77`；`shared:149` | `convertResponsesMessages` 内 `sourceIndex++ === 0 && msg.role === "system"` `:218` → `getSystemMessageText` `:222` → `input_message{role: system}` | `resolveTranscriptTools(...)` `shared:178` | **不切**：就地转 input 项 |
| **pi-messages** | 无（车道本身不折叠；它把 transcript 原样 POST 出去） | 由**对端**读 `context.messages` 里的系统消息 | 同上 | 原样发送 |

> 读法：**只有 Anthropic 切前导系统消息**，其余四条原地转换；Google 额外把「整条 transcript」先折叠成「一个头 + 非系统消息」。P8 的「下标 0」在 completions / mistral / responses 三处是**逐字**判据。

---

## 4. Java 侧现状与差距

### 4.1 现状（一句话）

两条并行通道：**协议层**（`StreamRequest.systemPrompt()`/`.tools()` → 各自车道的字段，五条车道各自在请求构建处挂 `TransformMessages`，`PiMessagesApi` 不挂）与 **harness 层**（agent-core 的 `Context` → `DefaultProviders.streamFnFor` 在**唯一一处**折成 `StreamRequest`）。`Message.SystemMessage` 已在类型层存在，但**六条车道对它的待遇各不相同**：

| 车道 | 今天的 SystemMessage 待遇 | 位置 |
|---|---|---|
| Anthropic | **静默错发**：`:74-75` 的角色三元把它当 **assistant**，其文本作为普通块进入会话 | `AnthropicRequestBuilder.java:74-75` |
| Completions | **静默吞掉**：`if/else if` 链只认 3 类，无 default | `OpenAICompletionsMessageConverter.java:96-130` |
| Responses | **静默吞掉**：同上 | `ResponsesMessageConverter.java:123-134` |
| Google | **抛** `IllegalArgumentException` | `GoogleMessageConverter.java:92-93` |
| Mistral | **抛** `IllegalArgumentException` | `MistralConversationsApi.java:333-334` |
| PiMessages | 落到末尾兜底 **抛** `Unknown message type` | `PiMessagesApi.java:212` |
| **TransformMessages → OrphanToolResults** | **抛** `IllegalStateException("unreachable message role")` | `OrphanToolResults.java:59-63` |

即：**同一条消息在七处有七种运气**，而 pi 只有一种（进 transcript 语义）。这正是 A2 要消灭的东西。

### 4.2 本包新发现的缺口（不在 `docs/41`/`docs/32` 既有行里）

| # | 缺口 | 证据 | 后果分档 |
|---|---|---|---|
| **F1** | `OrphanToolResults` 的兜底分支在 A1 之后**可达** | `OrphanToolResults.java:59-63` 的注释与异常文案都写于 A1 之前（「Message 只有 3 个变体」）；`TransformMessages.java:99-102` 把 system 原样放行 ⇒ 任何**中途**系统消息进第二遍就抛 | **响亮失败**，且走公开 API 今天就能撞到（`TransformMessages.apply` 是 public） |
| **F2** | `PiMessagesApi` 的线形状停在 pi `9e05370b2` **之前** | pi `9e05370b2`（2026-09-16）把该车道形参 `Context` → `TranscriptContext`，body 里 `context` 随之只含 `messages`（`pi-messages.ts:374-390`）；Java `PiMessagesApi.java:164-186` 仍发 `{systemPrompt?, messages, tools?}` | **静默形状偏差**（对端若是 pi 当前实现，读不到系统消息） |
| **F3** | `sections` 的渲染顺序被 `Map.copyOf` 打乱 | pi 按插入顺序渲染（`text.ts:17`/`:30` 的 `Object.entries`/`Object.values`）；Java `Message.java:57-63` 用 `Map.copyOf`（顺序未定义） | 今天不可达（无 section 生产者）；A4 一落就会静默改变渲染文本 |
| **F4** | 三处 javadoc 的 pi 行号指向重构前的位置 | 见 P14 | 文档失真（维护者按行号取证会读到无关代码） |
| **F5** | Anthropic 把系统消息当 assistant 文本发出 | `AnthropicRequestBuilder.java:74-75` vs `anthropic-messages.ts:1250-1256` | **静默错发**（比抛异常更难发现） |
| **F6** | **Anthropic 工具声明缺 `eager_input_streaming`**（本次探针顺带发现，**不属 A2**） | pi 的 compat 默认是 `?? true`（`anthropic-messages.ts:209`），`convertTools` 据此给每个工具加 `eager_input_streaming: true`（`:1486`）；不支持时改走细粒度工具流式 beta（`:1450-1455`）。`grep -rn "eager\|Eager" pi-java-ai/src/main` **零命中** ⇒ Java 从不发该字段，也从不挂该 beta | **线格差异**，登记为 L-I（归 A-07/A-09 一类，另行裁决） |

---

## 5. 设计

### 5.1 请求形状：`StreamRequest` 的组件换成 `TranscriptContext`（裁决点 R1）

**取舍**：Java 的 `StreamApi.stream(StreamRequest, ApiOptions)` 一身两角 —— 它既是宿主调用的**公开入口**（≈ pi 的 `models.stream(model, context, options)`），又是车道实现的**唯一钩子**（≈ pi 的 `StreamFunction`，形参已是 `TranscriptContext`）。三种收口方式：

| 方案 | 做法 | 代价 | 类型级保证 |
|---|---|---|---|
| **A（推荐）** | `StreamRequest` 的 `systemPrompt`/`messages`/`tools` → `TranscriptContext transcript`；**保留** 8 参 / 7 参 / `ModelId` 兼容构造器，内部调 `ContextNormalizer.normalize` | 1 个 record 重写 + 6 站读取点（P18：全在 ai 模块内） | **有**：`systemPrompt()`/`tools()` 访问器**不存在**，车道结构上读不到 |
| B | 追加第 9 个组件 `transcript`，legacy 三组件保留 | 最小 | **无**：两处真相并存，车道仍可读 legacy |
| C | 新车道面类型 `TranscriptRequest`，`streamInternal` 换形参 | 6 个 lane 类 + 5 个转换器 + faux + 部分测试的签名连锁 | 有（同 A） |

选 **A** 的理由：与 pi 的两级形状（公开 `Context` → provider `TranscriptContext`）同构，且**兼容构造器让 P18 列出的外部构造点零改动**（宿主 `DefaultProviders.java:127` 1 处、evals 4 处 `ChatApiConformanceSuite.java:145`/`:175`、`ProviderSmokeTest.java:47`、`StreamEventOrderValidatorTest.java:44`、agent-core `CrossLayerLengthGateTest.java:169` 1 处；其余全为 `pi-java-ai` 模块内测试）。C 的额外收益（把 `maxTokens/temperature/reasoning` 与 model 一起搬进新类型）在本包没有消费者，属于投机骨架。

**落地形状**（完整签名）：

```java
package com.pijava.ai.api;

/**
 * A streaming chat request sent to an LLM provider.
 *
 * <p>{@code systemPrompt} / {@code tools} 是 pi 的 {@code Context}（兼容输入），
 * 它们在本类型构造时被 {@link ContextNormalizer} 折进 {@link TranscriptContext}
 * —— 与 pi 的 {@code normalizeContext} 同一时点（{@code ai/src/utils/transcript.ts:30}）。
 * 车道只能看见 {@code transcript()}：{@code systemPrompt()} / {@code tools()}
 * 访问器**不存在**，镜像 pi 的 branded {@code TranscriptContext}
 * （{@code types.ts:628-634}「a raw Context cannot reach provider code by accident」）。</p>
 */
public record StreamRequest(
    ModelInfo model,
    TranscriptContext transcript,
    int maxTokens,
    double temperature,
    Map<String, Object> extra,
    Optional<ThinkingLevel> reasoning
) {
    public StreamRequest {
        transcript = transcript == null ? new TranscriptContext(List.of()) : transcript;
        extra = Map.copyOf(extra);
        if (reasoning == null) {
            reasoning = Optional.empty();
        }
    }

    /** pi 的公开入口形参（{@code Context.systemPrompt/messages/tools}）—— 兼容构造器。 */
    public StreamRequest(ModelInfo model, String systemPrompt, List<Message> messages,
                         List<ToolDefinition> tools, int maxTokens, double temperature,
                         Map<String, Object> extra, Optional<ThinkingLevel> reasoning) {
        this(model, ContextNormalizer.normalize(systemPrompt, messages, tools),
             maxTokens, temperature, extra, reasoning);
    }

    /** 7 参形态（无 thinking 级别）—— 保留使既有构造点零改签。 */
    public StreamRequest(ModelInfo model, String systemPrompt, List<Message> messages,
                         List<ToolDefinition> tools, int maxTokens, double temperature,
                         Map<String, Object> extra) {
        this(model, systemPrompt, messages, tools, maxTokens, temperature, extra, Optional.empty());
    }

    /** {@link ModelId} 形态 —— 合成 {@link ModelInfo#minimal}。 */
    public StreamRequest(ModelId<?> model, String systemPrompt, List<Message> messages,
                         List<ToolDefinition> tools, int maxTokens, double temperature,
                         Map<String, Object> extra) {
        this(ModelInfo.minimal(model), systemPrompt, messages, tools,
             maxTokens, temperature, extra, Optional.empty());
    }

    /** The target model's id（既有便利方法，保留）。 */
    public ModelId<?> modelId() {
        return model == null ? null : model.id();
    }

    /** 会话消息（transcript 的别名，便于逐站替换 {@code request.messages()}）。 */
    public List<Message> messages() {
        return transcript.messages();
    }

    public static StreamRequest of(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
    }
}
```

> ⚠️ `messages()` 便利方法**保留**（返回 `transcript().messages()`）：它是纯别名、不制造第二真相，且让 6 站迁移的 diff 缩到最小。是否保留由 R1 的结论决定（我倾向保留）。

**为什么归一放在构造函数而不是 `AbstractChatApi`**：pi 的归一在**公开入口**，车道拿到的东西**在类型上**已归一 —— 归一若放在 `AbstractChatApi.stream()`，车道仍持有可读 legacy 字段的对象（方案 B 的弱点），且 `AbstractChatApi` 当前的三个入口（`stream`/`streamBlocking`/`send`）会各自需要一次归一调用。放构造函数后 `AbstractChatApi` **零改动**（P19）。

### 5.2 新增 helper：`com.pijava.ai.api.Transcripts`（镜像 `utils/transcript.ts`）

```java
package com.pijava.ai.api;

/** pi {@code utils/transcript.ts} 中「重放」那一半的移植（本包只落消费侧需要的六个）。 */
public final class Transcripts {

    private Transcripts() {}

    /** pi {@code :36-39} —— 前导系统消息；转录不以系统消息开头时返回 {@code null}。 */
    public static Message.SystemMessage getInitialSystemMessage(List<Message> messages);

    /** pi {@code :42-44} —— 供「提示在消息列表之外」的 API 丢弃前导系统消息。 */
    public static List<Message> withoutInitialSystemMessage(List<Message> messages);

    /** pi {@code :47-53} —— 按序重放每一条系统消息的 {@code toolsRemoved}/{@code toolsAdded}。 */
    public static List<ToolDefinition> getCurrentTools(List<Message> messages);

    /**
     * pi {@code :57-74} —— 把每条系统消息重放成一条前导消息：
     * content 按 {@code "\n\n"} 连、sections 按名覆盖、tools 走 {@link #getCurrentTools}、
     * timestamp 取**第一条**系统消息的。
     *
     * <p>⚠️ 与 pi 的一处**能力差异**（§9 R3）：pi 的 {@code sections} 值可为 {@code null}
     * 表示**删除**具名段（{@code transcript.ts:81-83}），Java 的 {@code Map<String,String>}
     * 表达不了 ⇒ 本方法对「非 null 值覆盖」逐条对齐，删除语义**缺**（登记 L3）。</p>
     */
    public static Message.SystemMessage getCurrentSystemMessage(List<Message> messages);

    /** pi {@code :77-80} —— 重放后的系统提示文本（无系统消息 ⇒ {@code ""}）。 */
    public static String getCurrentSystemPrompt(List<Message> messages);

    /** pi {@code :104-110} —— 头 + 全部非系统消息（幂等）。 */
    public static TranscriptContext collapseSystemMessages(TranscriptContext context);

    /**
     * pi {@code :113-120} —— 模型接受中途系统消息就原样保留，否则折叠。
     * 判据字段是 {@link com.pijava.ai.catalog.ModelCompat#supportsMidConvoSystemMessages()}。
     */
    public static TranscriptContext resolveTranscript(TranscriptContext context, ModelInfo model);
}
```

要点（逐条对齐 pi，全部要在夹具里钉住）：

1. `getCurrentTools` 用**保序** Map（`LinkedHashMap`：pi 的 `Map.set` 对已存在键保留首次插入位置，Java 的 `LinkedHashMap.put` 同义）；先 removals 后 additions（`transcript.ts:49-51`）。
2. `getCurrentSystemMessage` 的**四条空值纪律**：`timestamp` 取第一条（`timestamp ??= message.timestamp`）；`sections` 为空 ⇒ 出参给 `Map.of()`（pi 是 omit，见 §5.6 的序列化纪律）；`tools` 为空 ⇒ 出参 `List.of()`（pi 同样 omit）；`content` 逐条 `contentText` 后**非空才收**再以 `"\n\n"` 连。
3. 「一条系统消息都没有且没有工具」⇒ 返回 `null`（pi `:71` `if (timestamp === undefined && tools.length === 0) return undefined;`）。
4. `collapseSystemMessages` 对**没有系统消息**的转录返回**等值**结果（pi fixture `:66`），对已折叠的转录**幂等**（pi fixture `:59`）。

### 5.3 新增 helper：`com.pijava.ai.message.MessageTexts`（镜像 `utils/text.ts`）

```java
package com.pijava.ai.message;

/** pi {@code utils/text.ts} 的移植：内容取文本、系统消息渲染。 */
public final class MessageTexts {

    private MessageTexts() {}

    /** pi {@code :6-11} —— 文本块按 {@code separator} 连接（默认 {@code "\n"}）。 */
    public static String contentText(List<ContentBlock> content);

    public static String contentText(List<ContentBlock> content, String separator);

    /**
     * pi {@code :15-21} —— 完整提示：content 文本 + 各 section 值，
     * 空串滤掉后以 {@code "\n\n"} 连。
     */
    public static String getSystemMessageText(Message.SystemMessage message);

    /**
     * pi {@code :23-40} —— 中途系统消息的「按名框住」渲染
     * （{@code Updated system prompt section "x":\n\n…} / {@code Removed system prompt section "x".}）。
     * pi 自述这是**请求期文本**、版本间可改（{@code text.ts:25-27}）。
     */
    public static String renderSystemMessageUpdate(Message.SystemMessage message);
}
```

> `renderSystemMessageUpdate` 在 A2 的**车道里不可达**（只有 `supportsMidConvoSystemMessages` 为真时才走到，见 §5.4 的守卫），但它是 `text.ts` 的一条**纯函数 + pi 有 oracle**（`system-message-replay.test.ts:86-98`），因此本包移植它并由单测钉住 —— 这不是投机骨架，是镜像一个已被 pi 自己的测试钉死的纯函数；车道里的**调用点**仍按「不可达 ⇒ 显式守卫」处理。

### 5.4 六条车道的改造

统一模式（pi 的调用位置逐条对应）：

```java
// 车道入口（streamInternal 开头，pi 的 `resolveTranscript` 在 stream 顶部）
var transcript = Transcripts.resolveTranscript(request.transcript(), request.model());
// 之后所有构建函数收 TranscriptContext，不再收 StreamRequest 的 legacy 字段
```

| 车道 | 改动 | pi 对位 |
|---|---|---|
| **Anthropic** | ①`buildParams(StreamRequest, TranscriptContext)`；②`TransformMessages.apply(transcript.messages(), …)`；③`initial = getInitialSystemMessage(transcript.messages())`；④`params.system = getSystemMessageText(initial)`（非空才设）；⑤**会话数组 = `initial != null ? transformed.subList(1, size) : transformed`**；⑥`tools = getCurrentTools(transcript.messages())`；⑦**若仍有非前导系统消息**（`supportsMidConvoSystemMessages` 为真才会）⇒ 显式抛「A3/A7 未落」的守卫 | `:517-518`、`:1042-1044`、`:1104-1122`、`:1139` |
| **Completions** | ①系统文本从 transcript 取，仍**先** `addSystemMessage`（等价于 pi 的「下标 0 就地转换」，因为折叠后的头必在下标 0）；②`TransformMessages` 换 transcript；③tools 换 `getCurrentTools`；④`instructionRole`（developer/system）**本包不碰**（既有 A-07 缺口）；⑤非前导系统消息 ⇒ 守卫 | `:305`、`:1225`、`:1249-1252`、`:807` |
| **Google** | ①入口 `collapseSystemMessages`（**不看 compat**，P6）；②`systemInstruction` ← `getSystemMessageText(getInitialSystemMessage(…))`；③`contents` 走 `withoutInitialSystemMessage(collapsed.messages())`（**先折叠再去头**，`google-shared.ts:193`）；④tools ← `getCurrentTools`；⑤`GoogleMessageConverter` 的 SystemMessage 抛异常**保留**为防御性不变量（折叠后结构上到不了） | `:65`、`:374-375`、`:390-393`、`google-shared.ts:192-193` |
| **Mistral** | ①入口 `resolveTranscript`（按字段）；②`toMistralMessages` 迭代 transcript，`index == 0` 的系统消息用 `getSystemMessageText`、其余用 `renderSystemMessageUpdate`，**就地**压 `{role:"system"}`；③tools ← `getCurrentTools`；④保留 `SystemMessage` 的抛异常分支为不变量 | `:130`、`:787-789`、`:520` |
| **Responses** | ①入口 `resolveTranscript`；②`convertMessages` 迭代 transcript，`sourceIndex++ == 0 && role==system` ⇒ `getSystemMessageText`，否则 `renderSystemMessageUpdate`；③tools ← `getCurrentTools` | `:119`/`shared:149`、`shared:218-222`、`shared:178` |
| **PiMessages** | 见 §5.5（**线形状变更**，裁决点 R2） | `pi-messages.ts:374-390` |

### 5.5 `OrphanToolResults`：R5 的 `heldSystemMessages`（判据 P9）

pi 的语义（`transform-messages.ts:163-225`）逐条移植：

```java
// pi :163-166
var heldSystemMessages = new ArrayList<Message>();

// closePendingToolCalls（pi :167-186）：合成孤儿结果 → 清 pending → **无条件** flush held
// （注意：flush 在 `if (pendingToolCalls.length > 0)` **之外** —— 删掉这个「之外」是典型走样点）

// 循环内（pi :213-225）
} else if (msg instanceof Message.SystemMessage system) {
    if (!pendingToolCalls.isEmpty()) {
        heldSystemMessages.add(system);          // 扣住，等结果
    } else {
        result.add(system);                      // 无未答调用 ⇒ 就地放行
    }
}
```

**同时删掉** `:59-63` 的 `else → throw`（它的前提「Message 只有 3 个变体」在 A1 之后已假）。

### 5.6 序列化纪律（只登记，不改）

`getCurrentSystemMessage` 出参里 `sections`/`toolsAdded` 为空时 pi **省略键**、Java 的 record 恒有字段。落线由 `SessionJson` 的「空值省略门」负责（A1 的 M4 探针已钉住该门），本包不改；只在 helper 的 javadoc 写明「空 ⇒ `Map.of()`/`List.of()`，落线省略由 `SessionJson` 保证」。

---

## 6. 分步实施（每步可独立编译，粒度 200–500 行）

| 步 | 内容 | 产出 | 预计 diff |
|---|---|---|---|
| **A2a** | `Transcripts` + `MessageTexts` + `ModelCompat` 加 `Boolean supportsMidConvoSystemMessages` + `Message.SystemMessage` 的 `sections` 改保序 Map（F3）+ 单测（pi oracle 移植，§7.2） | 新类两个、record 两处小改、测试两个 | ~350 行 |
| **A2b** | `StreamRequest` 加 `transcript` 组件（**保留** legacy 三组件）＋ 六站读取点改走 transcript（行为开始对齐） | 1 record + 6 协议文件 | ~300 行 |
| **A2c** | 删除 `StreamRequest` 的 legacy 三组件与旧便捷构造器（类型级保证生效）；`AbstractChatApi` 零改动（P19） | 1 record + 少量测试修 | ~120 行 |
| **A2d** | `OrphanToolResults` 的 held 系统消息（F1/R5）＋ 删兜底 throw | 1 文件 + 夹具 | ~120 行 |
| **A2e** | PiMessages 线形状（R2 的结论）＋ javadoc 行号更正（F4）＋ `docs/03` 形状回写 ＋ `docs/41`/`docs/32` 行更新 | 文档 + 1 文件 | ~150 行 |

> A2b/A2c 的拆法是「先兼容并存、再删旧路」，让**每个提交都能编译、都能单独回退**；若 A2b 实测超过 500 行，按车道再拆（每车道一个提交，legacy 字段逐步脱钩）。

---

## 7. 测试策略

### 7.1 先红矩阵（**实施前必须先跑出红**，`docs/48 §6.1`）

| # | 输入 | 今天 | pi 的期望 | 红的形态 |
|---|---|---|---|---|
| **RED-1** | `TransformMessages.apply([user, assistant(toolUse tc1), system("mid"), toolResult(tc1)], …)` | `IllegalStateException: unreachable message role` | `[user, assistant, toolResult, system]`（＝§7.6 **PR-1** 实测） | 抛异常 ⇒ 红 |
| **RED-2** | `[assistant(toolUse tc1), system("mid"), user("next")]` | 同上 | `[assistant, 合成 toolResult(tc1), system, user]`（＝§7.6 **PR-2** 实测，含合成条逐字载荷） | 抛异常 ⇒ 红 |
| **RED-3** | `new StreamRequest(m, null, [system("be brief"), user("hi")], List.of(), …)` × 5 车道 | Anthropic：无 `system` 字段且多一条 assistant 文本；Completions/Responses：系统文本消失；Google/Mistral：抛 | 五条车道各自在**本车道的系统字段/指令消息**里出现 `"be brief"`，且会话数组/数组项里**没有**系统项（Anthropic 切头、Google 去头）。Anthropic 的期望值＝§7.6 **PR-3** 实测 | 5 条红 |
| **RED-4** | `new StreamRequest(m, null, [system(sections=…, toolsAdded=[t1]), user("hi")], List.of(), …)` | 五条车道线上**没有** `t1` | 五条车道线上**有** `t1`（来自 `getCurrentTools`）。Anthropic 的期望值＝§7.6 **PR-3** 实测（`tools` 恰 1 条） | 5 条红 |
| **RED-5** | `[user("a"), system("b"), user("c")]`（中途系统消息） | Google/Mistral 抛；其余静默丢 | 折叠：`systemInstruction`/`params.system` 等 == `"b"`，且 `b` 不再作为会话项出现。Anthropic 的期望值＝§7.6 **PR-4** 实测（`"head\n\nmid"` + 两条 user） | 至少 2 条响亮红 |
| **RED-6** | PiMessages body | `{systemPrompt, messages, tools}` | `{messages}`（系统消息在数组里，R2 先裁决） | 1 条红（形状断言） |
| **RED-7** | helper 单测（pi oracle 移植） | 类不存在 ⇒ **编译失败** | 全绿 | 结构性红（A1 的先例：`docs/48 §5` 首行记的就是「编译失败」这种红） |

> ⚠️ **RED-3/4/5 的红全部经公开 API**（`api.stream(...)` / `api.send(...)` 或车道的 `buildParams`），不需要宿主参与 —— 因为 `SystemMessage` 是**公开类型**，任何人（含 `AiCli`、evals）都能把它放进 `messages`。

### 7.2 oracle（把 pi 自己的夹具搬过来）

| 夹具 | 来源 | 覆盖 |
|---|---|---|
| `MessageTextsTest` | pi `test/system-message-replay.test.ts:86-98`（`getSystemMessageText` / `renderSystemMessageUpdate` 的**逐字**期望值，含 `Removed system prompt section "b".`） | P11/P12/P15 |
| `TranscriptsTest` | pi 同文件 `:44-84`（replay 四条：content+sections+tools 合一、collapse 保序与幂等、空转录、迟到全量 patch）+ `:100-108`（`normalizeContext` 空输入恒等 —— 这条归 `ContextNormalizerTest`） | P8/P16 |
| `OrphanToolResultsHeldSystemMessageTest` | pi `transform-messages.ts:163-225` 的手工推导（🔎 见 §7.4：pi 无同名单测，须实测产出 oracle） | P9 |

### 7.3 变异探针（每条都要给精确红集）

| # | 变异 | 期望红 |
|---|---|---|
| M1 | Anthropic 不切前导系统消息（去掉 `subList(1)`） | RED-3 的 Anthropic 条 |
| M2 | `getCurrentTools` 直接 `return List.of()` | RED-4 五条 |
| M3 | `resolveTranscript` 恒返回入参（不折叠） | RED-5 |
| M4 | `OrphanToolResults` 删 system 分支（还原 throw） | RED-1/RED-2 |
| M5 | `closePendingToolCalls` 的 held flush 移进 `if` 之内 | RED-2（合成结果与 system 顺序反） |
| M6 | `getSystemMessageText` 去掉空段过滤 | `MessageTextsTest` 的 `renderSystemMessageUpdate` 空段条 |
| M7 | `StreamRequest` 兼容构造器跳过 `ContextNormalizer.normalize` | RED-3/RED-4 全红（证明收口点在构造函数） |
| M8 | PiMessages 还原 legacy 三个键 | RED-6 |
| M9 | `getCurrentSystemMessage` 的 `timestamp` 取**最后**一条 | `TranscriptsTest` 的 timestamp 条（pi oracle 期望 10） |
| M10 | `getCurrentSystemMessage` 的 sections 遍历改成移除后新增的顺序 | `MessageTextsTest` 的渲染顺序条（**F3 的探针**：`Map.copyOf` 下不保证红 ⇒ 见 §7.4-3） |

### 7.4 实施前必须闭合的取证（🔎）

1. **pi 侧 oracle 实测** —— ✅ **设计期已闭合**（见 §7.6）：`vitest run packages/ai/test/system-message-replay.test.ts` **9/9 绿**（夹具是活 oracle）；自写的五个行为探针 **5/5 绿**，逐字输出已钉进 §7.6。⚠️ **仍未实测**的是 completions / google / mistral / responses 四条车道（探针只覆盖了 Anthropic 车道 + `transformMessages`），实施 A2b 时按同一手法各补一个探针（harness 抄 `anthropic-eager-tool-input-compat.test.ts:70-110` 的本地 HTTP 抓包）。
2. **`sections` 顺序的可观察性**（F3）：确认 pi 的 `SystemMessage` 经 JSON 往返后 `sections` 的键序仍是插入序（`JSON.parse` 保序）⇒ 证明「保序」不是 Java 单方面的洁癖。
3. **M10 的牙**：`Map.copyOf` 的顺序未定义 ⇒ 该探针**可能不红**。若确认不可靠，则 F3 的修复理由写成「pi 语义（插入序）」并把 M10 从「探针」降级为「本包选择的记录」——**不得**当成有牙证据。
4. **`ModelCompat` 第 5 组件的形态**：确认 `ModelCompat` 的 `equals`/`NONE`/便捷构造器（3 参、4 参）在加字段后的连锁面（`grep new ModelCompat`），避免像 A1 那样出现「假绿」的增量编译。

### 7.5 回归与门禁（`docs/48 §6`）

```bash
export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25'
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am clean test     # 增量假绿 ⇒ 一律 clean
/d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-agent-core -am test  # 消费者侧（DefaultProviders 链路）
/d/soft/apache-maven-3.9.9/bin/mvn checkstyle:check
```

- focused：新增两个测试类 + 五车道的系统/工具来源测试 + `OrphanToolResultsHeldSystemMessageTest`。
- 模块回归：`pi-java-ai`（当前 819）与 `pi-java-agent-core`（当前 484）**不得减少**；全 reactor `mvn clean verify` 串行跑一次（TUI 的临时 JSONL 环境失败按既有办法原样记录并单跑复核）。
- 静态门禁：checkstyle 0 violations、`git diff --check`、无新增 `System.out.println`、无无说明的 `@SuppressWarnings`、改动文件 ≤500 行。

### 7.6 设计期实测的 oracle（本机实跑，逐字）

运行环境：pi `3390bd936`，`./node_modules/.bin/vitest run`（vitest 4.1.9，node 于 `D:/soft/node/node`）。
⚠️ 实测前后均核过锚点：`git rev-parse HEAD` = `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`，`git status --porcelain` **空**（即工作树就在锚点上；`docs/32 §10.7` 的「pi 工作树可能不在锚点」这条未发生）。探针脚本是临时文件，跑完已删（`packages/ai/test/tmp-a2-probe*.test.ts` 已不存在）。
探针脚本（临时文件，跑完已删）抄 `packages/ai/test/anthropic-eager-tool-input-compat.test.ts:70-110` 的本地 HTTP 抓包 harness，用 `normalizeContext(context)` 直接喂 `streamAnthropic`，再从抓到的 POST body 断言。

| 探针 | 输入 | pi 实测输出（**逐字**） | 对应命题 |
|---|---|---|---|
| **PR-1** | `[user, assistant(toolCall tc1), system("mid"), toolResult(tc1)]` | `transformMessages` 角色序列 = `["user","assistant","toolResult","system"]` | P9（system 被扣住，结果先发） |
| **PR-2** | `[user, assistant(toolCall tc1), system("mid"), user("next")]` | 角色序列 = `["user","assistant","toolResult","system","user"]`；`out[2]` = `{"role":"toolResult","toolCallId":"tc1","toolName":"lookup","content":[{"type":"text","text":"No result provided"}],"isError":true,"timestamp":<now>}` | P9（合成结果在 held 之前；`timestamp` 是 pi 侧本地元数据，Java 无此字段＝既登记偏差 D3） |
| **PR-3** | `messages=[system("be brief", toolsAdded=[lookup]), user("hi")]`（**无** legacy 字段） | `system` = `[{"type":"text","text":"be brief"}]`；`tools` = 1 条（`{"name":"lookup",…,"eager_input_streaming":true,"input_schema":…}`）；`messages` = `[{"role":"user","content":"hi"}]` | P3/P4/P7 + **RED-3/RED-4** |
| **PR-4** | `messages=[system("head"), user("a"), system("mid"), user("b")]` | `system` = `[{"type":"text","text":"head\n\nmid"}]`；`messages` = `[{"role":"user","content":"a"},{"role":"user","content":"b"}]`；`tools` = `undefined` | P5/P6 + **RED-5**（折叠：中途系统消息的文本**并入头部**，且**不再**出现在会话数组里） |
| **PR-5** | `{systemPrompt:"legacy", messages:[user("hi")], tools:[lookup]}`（纯 legacy 输入） | `system` = `[{"type":"text","text":"legacy"}]`；`tools` = 1 条 | P16 + 回归基线（改造后这条必须**一字不变**） |

另：`packages/ai/test/system-message-replay.test.ts` 实跑 **9 passed**，其中 `:44-98` 是 `getCurrentSystemMessage` / `collapseSystemMessages` / `getSystemMessageText` / `renderSystemMessageUpdate` 的逐字期望值 —— **直接作为 `TranscriptsTest` / `MessageTextsTest` 的夹具来源**。

> ⚠️ 记一条方法论：PR-3 的期望值（`system` 字段 + `tools` 来自 `toolsAdded` + `messages` 被切头）**三项都是我读源码推的**，实测三项全中；但 `tools` 的那条里还带出了 `eager_input_streaming: true`（F6）——**是我读源码时没看见的**。这正是 `docs/32 §10.7` 要求「不准只读源码」的原因。

---

## 8. 验收标准（可量化）

1. 五条车道的系统文本与工具**只**来自 transcript：`grep -rn "request.systemPrompt()\|request.tools()" pi-java-ai/src/main` 返回**空**（`StreamRequest` 上不再有这两个访问器 ⇒ 编译期不可能）。
2. pi 的两个 oracle 夹具（§7.2）**逐字**通过；`OrphanToolResults` 的两种 held 场景与 pi 实测输出逐条相同。
3. 六条车道对 `Message.SystemMessage` 的待遇从「七种」收敛为「一种」：前导 ⇒ 本车道系统字段/指令项；中途 ⇒ 折叠进头（或按 compat 保留 + 守卫）；**任何路径都不再静默吞掉或当 assistant 发出**。
4. §7.3 的十条变异探针红集精确（M10 按 §7.4-3 的结论处置）。
5. `pi-java-ai` / `pi-java-agent-core` 回归不减少、checkstyle 0、全 reactor 绿（或按 §7.5 记录环境失败）。
6. `docs/03` 的 `StreamRequest` 形状（`:101-109`）已回写；`docs/41:57-58`、`docs/32` B87 的行内状态已更新。

---

## 9. 裁决点（请审核时给结论）

| # | 问题 | 我的建议 | 备选 |
|---|---|---|---|
| **R1** | `StreamRequest` 形状：组件换 `TranscriptContext`（A 方案）vs 追加第 9 组件（B）vs 新车道面类型（C） | **A**：与 pi 两级同构、外部构造点零改动、类型上消灭第二真相；保留 `messages()` 纯别名 | B 最小但留后门；C 收益在别包 |
| **R2** | PiMessages 线形状是否随本包对齐 pi 当前形状（`{messages}`） | **对齐**（F2 是 `9e05370b2` 之后才出现的漂移，且该车道在 pi 里就是 `TranscriptContext` 形参）；`options` 的其它键（`reasoning`/`cacheRetention`/`sessionId`/`toolChoice`）**不在本包** | 只登记不改（对端若是自建服务则风险低） |
| **R3** | `sections` 的两件事：①值 `null` = 删除（表达不了）②渲染顺序被 `Map.copyOf` 打乱 | ② 本包**修**（`LinkedHashMap` 保序，一行）；① **本包不改形状**，登记 L3 归 A4（今天零生产者，改形状会波及 A1 已落的 `SessionJson` 线格） | ①也本包改（形状变更波及 A1 落线，代价大） |
| **R4** | `ModelCompat.supportsMidConvoSystemMessages`：本包加字段（消费点落地）vs 五车道硬编码 `false` | **加字段**（boxed `Boolean`，`null` ≙ pi 的 `undefined` ≙ 折叠），探测/目录接线归 A7 | 硬编码 false：省 1 个字段，代价是 A7 时要改 5 处调用点 |
| **R5** | `OrphanToolResults` 的 held 系统消息放本包（A2d）还是随 A3 | **本包**：它是 F1（今天就能响亮撞到），且与工具增删的**生产者**无关 | 随 A3：本包留一个已知可达的 throw |
| **R6** | 是否接受 §6 的六步拆分（A2a–A2e）与 A2b/A2c 的「先并存、后删旧」顺序 | 接受 | 一个原子提交（diff > 500 行） |

---

## 10. 遗留登记（本包预计产出，实施后落 `docs/32`）

| 号 | 内容 | 处理 |
|---|---|---|
| **L-A** | **B87① 系统消息不能回读**（`MessageJsonCodec.decode:60` 对 `role:"system"` 抛错） | 本包**不碰**：A2 的输入来自内存，不经回读。仍随 A3 或独立小包（它是**响亮失败**，越早越好） |
| **L-B** | **A1-4 的重复守卫**（消息已以系统消息开头则不再前置 legacy 提示）与 pi 的无条件前置不一致 | 本包不改（A1 已登记为刻意偏差）；边界：pi 无「legacy 提示 + 已有系统消息」这对输入的语义裁决 ⇒ 保留现状 |
| **L-C** | **`sections` 值 `null` = 删除**表达不了（B87③） | 本包**不改形状**（R3），随 A4 |
| **L-D** | **中途系统消息的会话内渲染**（Anthropic 的 `tool_addition`/`text` 就地块、completions 的 `renderSystemMessageUpdate`、Responses 的 `additional_tools`/`tool_search_call`） | 本包只落**折叠**路径与 helper；原生路径按「`supportsMidConvoSystemMessages` 为真才可达」加**显式守卫**，实现归 A3/A7 |
| **L-E** | **PiMessages 的 `options` 键不全**（pi 发 `reasoning`/`cacheRetention`/`sessionId`/`toolChoice`，Java 只发 `temperature`/`maxTokens`） | 新登记；不属 A2（options 通道） |
| **L-F** | **`instructionRole`（developer/system）** | 既有 `docs/41 A-07` 缺口，本包不碰（completions 的 `addSystemMessage` 固定 system 角色） |
| **L-G** | **三处私有 `extractText`** 与 pi 的 `contentText` 语义不同（无分隔符拼接） | 本包**不动**（它们服务的是各自车道的 tool-result 文本，与系统提示渲染无关）；是否统一留待专门的文本 helper 包 |
| **L-H** | **三处 javadoc 的 pi 行号**（F4） | 本包顺带更正（A2e） |
| **L-I** | **Anthropic 工具声明缺 `eager_input_streaming`**（F6，本包探针顺带发现） | 新登记；**不属 A2**（是 compat/线格编码，不是 transcript 来源问题）。pi 默认 `?? true`（`anthropic-messages.ts:209`/`:1486`），不支持时改挂细粒度工具流式 beta（`:1450-1455`）；Java 两样都没有 |

---

## 11. 本次设计的取证方式与已知不足

- 所有 pi 侧行号取自 pi 工作树 `3390bd936`（2026-09-20），pi-java 侧行号取自 `862f59a`。
- **已实测**：pi 的 `system-message-replay.test.ts` 9/9 绿；五个行为探针 5/5 绿（逐字输出在 §7.6）。探针文件是临时文件，跑完已从 pi 工作树删除（`git status` 干净）。
- **仍未实测**（实施 A2b 时必须补）：completions / google / mistral / responses 四条车道的同类探针（§7.4-1）。
- 本文档不含任何实现代码，符合 `docs/00 §3` 步骤 3 与 `docs/32 §10.7` 的「设计先行、审核后才写码」。
