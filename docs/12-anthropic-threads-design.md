# 12 - Anthropic message threads 设计稿（L1 #113，最后一环）

> 【背景摘要】`docs/11` 的 D6。#113（w3，−3.0）是 L1 在预审计里的最大缺口；补齐后 L1 为
> **245.5/246＝99.8%**（余 cache 策略单元的 0.5 属模块归属，随 L5 迁移收口）。
> threads 改的是请求生命周期与重发语义，属 L 级，故单独立稿。
>
> 锚点：pi `a00a5048d`（2026-11-20）。证据全部在 `packages/ai/src/api/anthropic-messages.ts`
> （`git -C D:/workplaceForai/pi show a00a5048d:<path>`）。读数出口在 [`map/02`](map/02-ai.md)：
> 2026-11-22 按「代码落地 ＋ `mvn test -pl pi-java-ai` 全绿」收口（真机冒烟经用户裁决豁免），
> 本稿只记设计与实施口径、不改分数。

## 1. pi 的语义（逐条）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `thread` 是请求体字段：`{type:"create"}` 或 `{type:"continue", previous_message_id}` | `:259-262` |
| 2 | 默认开启条件：`compat?.supportsMessageThreads ?? baseUrl.includes("api.anthropic.com")` | `:249` |
| 3 | 另有开关 `options?.disableContinuation === true` ⇒ 完全不发 `thread`（**不是**发 `create`） | `:908-909` |
| 4 | 会话态：`{prefixCount, prefixHash, replyId}` 或 `"off"`；上限 10,000，丢最久未更新；`cleanupSessionResources(sessionId)` 释放（`null` 清空） | `:264-306` |
| 5 | `continue` 只发新增消息：`messages.slice(prefixCount)`，并**省略 `system`／`tools`**（服务端保存）；`thread: {type:"continue", previous_message_id: replyId}` | `:361-383` |
| 6 | 发 `continue` 前**先删状态**（单次可续；同时防止并发请求重复续） | `:373-375` |
| 7 | 前缀哈希 = `shortHash(JSON(设置) + JSON(消息))`；设置含 provider／baseUrl／model／system／tools／thinking／output_config／tool_choice／betas／fallbacks；消息归一化：**删除 `cache_control`**、字符串 content 视为 `[{type:"text",text}]` | `:321-350` |
| 8 | 成功后续记：`prefix = params.messages + 转换后的 reply`，`replyId = output.responseId`；**`responseModel` 有值（回退模型答题）不记**，`responseId` 缺失不记 | `:445-475` |
| 9 | 失败回退：见 §2 矩阵；诊断 `anthropic_thread_fallback` **先记后重发**，重发失败也留痕 | `:401-443` |

## 2. 失败回退矩阵（`sendThreadRequest`，`:401-443`）

| 条件 | 动作 | 会话态 |
|---|---|---|
| 中止信号已触发，或非 `APIError` | 原样抛出 | 不变 |
| `error_code ∈ {thread_not_found, thread_already_continued, thread_continue_in_flight, thread_fingerprint_mismatch}` | 记诊断（reason=code）→ **以 `create` 完整重发一次** | 由重发成功后的 `recordThreadReply` 覆盖 |
| `error_code === "thread_unsupported_request"`，或 `code` 缺失且 `status=400` 且 message 含 `thread` | 记诊断（reason=code／status）→ **去掉 `thread` 字段整请求重发** | 置 `"off"` |
| 其他（prompt too long、unknown model、strict 超限…） | 原样抛出（与线程无关，重发只会再失败） | 不变 |

## 3. Java 现状与缺口

| 面 | 现状 | 缺口 |
|---|---|---|
| 会话号 | `AnthropicStreamSupport.sessionIdOf(options)`（`extra["sessionId"]`），strict 记忆已用它 | 可直接复用 |
| 请求体 | `AnthropicRequestBuilder` 统一构造；`addAdditionalBodyProperty`（`:222-224`）已被 `fallbacks` 使用 | 需要「省略 system／tools」与「只放新增 messages」的构造形态 |
| beta 头 | `AnthropicBetaFeatures.assemble` ＋显式 header 覆写门（`:170-190`） | 需加 `message-threads-2026-08-12`（`:213`） |
| 诊断 | `AssistantMessageDiagnostic(type, timestamp, error?, details?)` ＋ `builder.appendDiagnostic` | 直接可用，`details` 放 `reason` |
| 哈希 | `AI/utils/ShortHash` 已有 | 需按 pi 的归一化口径（删 `cache_control`、字符串 content 归一）序列化 |
| 开关 | **不存在** `supportsMessageThreads`／`disableContinuation` | 两处都要新增 |

## 4. 设计

### 4.1 开关

- `supportsMessageThreads`：优先读 `ModelCompat`；`ModelCompat` 无该字段时按 `baseUrl.contains("api.anthropic.com")` 推导（与 pi 的 `??` 同序）。若 `ModelCompat` 加字段的构造面过大，先只做 baseUrl 推导并登记窄差。
- `disableContinuation`：从 `ApiOptions.extra()` 读 `"disableContinuation"`（布尔／`"true"`），**不改 `ApiOptions` 记录元数**（避免所有构造点连锁改动）；在 `docs/02` 的 extra 键表登记。

### 4.2 会话态

新类 `com.pijava.ai.protocol.AnthropicThreadState`：

- `record Entry(int prefixCount, String prefixHash, String replyId)`
- `Map<String, Object> STATE`（`Entry` 或 `OFF` 哨兵），上限 10,000，`LinkedHashMap` 重插保持最久优先
- `get`／`put`／`off`／`consume`（读并删，对应 `:373-375` 的单次语义）／`clear(String sessionId)`
- 与 `StrictToolLimitMemory` 同形（同包、同容量、同清理习惯），便于一起挂到会话清理点

### 4.3 构造与发送

```
prepare(model, sessionId, params):
  state = STATE.get(sessionId)
  if state == OFF: return undefined                  // 该端点不支持 ⇒ 不发 thread
  if state is Entry && params.messages.size > prefixCount
     && hashThreadPrefix(model, params, params.messages[0..prefixCount]) == state.prefixHash:
        STATE.consume(sessionId)                      // 单次可续
        return { body: params - system - tools
                        + messages[prefixCount..]
                        + thread:{continue, state.replyId}, sessionId }
  return { body: params + thread:{create}, sessionId }
```

`sendThreadRequest`：捕获 HTTP 错误后按 §2 矩阵分流；重发时 `create` 用**完整 params**、去 thread 用**原 params**；两次重发都只做一次。

### 4.4 记录

`recordThreadReply`：`replyId` 取本次响应的 `responseId`；`reply` 取 `TransformMessages` ＋ `convertMessages` 后**仅 assistant 消息**（`:462-472`）；`prefixCount = params.messages.size + reply.size`；`prefixHash` 用同一 `hashThreadPrefix` 对 `prefix` 计算。`responseModel != null` 或 `responseId == null` ⇒ 不记。

## 5. 与现有机制的顺序

1. **strict fallback 在外、threads 在内**：pi 的 strict 降级发生在拿到线程拒绝之后的同一请求里；Java 的 `AnthropicMessagesApi` 循环已实现 strict 降级，threads 的「准备 → 发送 → 回退」应包在**同一轮**内，重发时不再重新 `prepare`（状态已被 consume）。
2. **重试（`ProviderRetry`）**：threads 的回退是**请求级重发**，不消耗 `maxRetries`（pi 同理——它直接 `send` 第二次）。
3. **缓冲**：`continue` 省略 `system`／`tools` 后，`StreamPartialBuilder` 的输入变换必须仍按**完整**请求计算（哈希已覆盖，但 `openai_input_transformations` 之类只对 responses 车道，不受影响）。
4. **中止**：`signal.aborted` ⇒ 不重发、直接抛（`:415`），由 `StreamFailures.reasonFor` 记 `aborted`。

## 6. 测试计划（`RecordingHttpServer` 两轮观测）

| 用例 | 断言 |
|---|---|
| 首轮 create | body 有 `thread:{type:"create"}`；header 含 threads beta；诊断为空 |
| 次轮 continue | body 的 `messages` 只含新增；无 `system`／`tools`；`thread.previous_message_id` = 上轮 `responseId` |
| 前缀被改（改 system 或 tools） | 回落到 `create`，不出 `continue` |
| `thread_not_found` | 诊断 `anthropic_thread_fallback` reason=`thread_not_found`；重发为 `create`；请求计数 +1 |
| 400 无 code 且 message 含 `thread` | 重发**无** `thread`；会话置 `off`；第三轮不再带 `thread` |
| `disableContinuation=true` | 全程无 `thread` 键（也不是 `create`） |
| 回退模型答题（`responseModel` 非空） | 不记状态，下轮仍 `create` |
| 容量／清理 | 10,001 会话丢最旧；`clear(sessionId)` 生效 |

真实 provider 冒烟：`api.anthropic.com` 单轮 create + 一轮 continue，确认 `responseId` 被接受。

## 7. 提交切分

| 提交 | 内容 |
|---|---|
| S6 | `AnthropicThreadState` ＋哈希（`hashThreadPrefix` 归一化）＋单测 |
| S7 | `thread` 体构造 ＋ beta 头 ＋ create／continue 分流（不含回退）＋ 两轮观测测试 |
| S8 | 失败回退矩阵 ＋ 诊断 ＋ `off` 语义 ＋ 测试 |
| S9 | `disableContinuation` ＋ 真实 provider 冒烟（不进 CI） |

每提交 200–500 行；`mvn test -pl pi-java-ai` 绿为门槛。

## 8. 风险

1. **默认开启**是行为变化：所有 `api.anthropic.com` 用户从「整请求」变成「首轮 create、次轮 continue」。回退矩阵必须全绿，否则会出现「模型没看到 system」这类静默错误。
2. **哈希口径必须逐字对位**：漏掉 `cache_control` 归一会让每轮都判「前缀变了」→ 永远 `create`（性能退化为现状，不报错，难被发现）。测试要专门覆盖「缓存标记位置搬移」。
3. **单次可续**：状态先删后发；若重发失败，状态已丢 ⇒ 下一轮 `create`。这与 pi 一致，不能「失败后回滚状态」。
4. **`off` 是终局**：端点拒绝后整会话不再发 `thread`，避免每轮一次 400。

## 9. 待决项

- **D-1**：`supportsMessageThreads` 是否进 `ModelCompat`（真对位），还是先只按 baseUrl 推导（低成本，登记窄差）？
- **D-2**：`disableContinuation` 放 `ApiOptions.extra`（不破记录元数）还是给 `ApiOptions` 加组件（更显式但要改所有构造点）？
- **D-3**：默认值是否与 pi 完全一致（`api.anthropic.com` 一律开启）？若要稳妥，可先默认关闭、由 `compat` 显式开启，但那会在默认路径上与 pi 不一致。

三项都已按建议拍板：D-1 先按 baseUrl 推导并登记窄差；D-2 放 `ApiOptions.extra`；D-3 与 pi 一致默认开启，
但回退矩阵测试必须先绿。

## 10. 实施记录

| 阶段 | 状态 | 提交 |
|---|---|---|
| S6 状态 ＋ 哈希 | **完成（2026-11-22）** | `177acc36`：`AnthropicThreadState`（单次消费／`off`／10k／清理）＋ `AnthropicThreadHash`（删 `cache_control`、字符串 content 归一）＋ 7 例 |
| S7a builder 支持 | **完成（2026-11-22）** | `25e00d5b`：`ThreadRequest` 三态、continue 省略 `system`／`tools` 且只写尾部行、`messageCount` 按完整请求计；无车道调用点 ⇒ 行为未变 |
| S7b 车道接入 | **完成（2026-11-22）** | `3f0ac7a6`，见 §11.1（哈希取源采纳方案 A） |
| S8 回退矩阵 | **完成（2026-11-22）** | `ecf4c6f1`，见 §11.2 |
| S9 开关 ＋ 冒烟 | **完成（2026-11-22）**；真机冒烟经用户裁决豁免 | `93b4bfe4`，见 §11.3 |

### S7b 的阻塞点（实测，**已按方案 A 解除** —— 见 §11.1）

pi 的 `hashThreadPrefix` 哈希的是**已构造的请求对象**（`params` 与 `params.messages`）。Java 侧实测：
`ObjectMappers.jsonMapper().valueToTree(built.params())` 返回 **`{}`** —— 稳定 SDK 的 params 无法反序列化回
JSON（其自定义序列化只在写线上生效）。因此「哈希线上报文」这条路在当前 SDK 下不可行。

两条备选：

- **A（推荐）改哈希输入**：对 `TranscriptContext` 的消息 ＋ 固定设置做归一化签名。语义上等价
  （前缀变了才需要新线程），且**风险更低**：`cache_control` 与字符串 content 的归一化发生在转换期，
  转录层没有这两类形态，恰好绕开设计稿 §8 第 2 条点名的静默退化风险。代价是哈希口径与 pi 不同——
  但状态只在本进程内，不跨语言、不跨版本。
  **漏掉某个设置的代价**也被 S8 兜住：服务端回 `thread_fingerprint_mismatch` ⇒ 重发 `create`（pi 自己
  在 `:317-318` 也是这么权衡的）。
- **B 自建 JSON 序列化**：把 `MessageParam` 逐字段再序列化一份用于哈希。能贴 pi 的字面口径，但要维护
  第二套序列化，与 SDK 版本漂移即成错源。

若采纳 A，还需要同步改一处：状态里的 `prefixCount` 必须与哈希同单位。方案是把计数改到**转录消息**
口径（`transcript.messages().size() + 1`），再由 builder 在写行时按「转录消息 → 行」的映射切分；
不能一边按行计数、一边按转录哈希。

## 11. 后续

A 拍板后：S7b（车道接入 ＋ 两轮 `RecordingHttpServer` 观测）→ S8（回退矩阵）→ S9（开关与冒烟）。

### 11.1 S7b 的落地口径（2026-11-22，采纳方案 A）

**哈希取源**：`AnthropicRequestBuilder` 在 `applyConversationCacheBreakpoint` **之前**采集逐行指纹
（`AnthropicConversationWire.Row#key()`），连同设置签名（`settingsKey`：SDK `toString()` 的逐字段
投影 ＋ betas）存进 `BuiltParams`／`MessagesResult`；`AnthropicThreadRequests` 用
`AnthropicThreadHash.ofRows` 合成前缀哈希。`cache_control` 从未进过指纹 ⇒ 标记搬移不再误判。

**为什么指纹里没有 `cache_control` 也不违背 pi**：pi 的归一化本来就是「删掉 `cache_control`」；
本仓把它提前到「采集时机」这一层实现，等价且更省事。`system`／`tools` 那两处 pi 是**保留**标记的，
这里不计入 —— 代价只有一次服务端 `thread_fingerprint_mismatch`，由 S8 的新线程兜住。

**单位统一**：`prefixCount` 与哈希同为「行」口径（S7a 的 `messageCount` 本来就是完整请求的行数），
故无需设计稿 §10 里那条「转录口径 ↔ 行口径」的换算。

**记账**：`record()` 取「完整请求的 rowKeys ＋ 回复的逐行指纹」；回复先盖 identity 再
`transformMessages`（`transformMessages` 的 assistant 分支按「同 api／同 provider／同 model」分岔，
不盖章会算出与下一轮不同的指纹），`responseModel` 有值或 `responseId` 缺失则不记。

**登记的窄差（不阻塞 S8，但需要读者知道）**：

1. **compat 字段缺席**（D-1）：`supportsMessageThreads` 不进 `ModelCompat`；explicit 值走
   `ApiOptions.extra["messageThreads"]`（Boolean 或线格字符串），缺席时按端点推导 ——
   无 baseUrl 覆盖时认 provider 名 `anthropic`，有覆盖时要求命中 `api.anthropic.com`。
   ⚠️ 比 pi **更保守一层**（pi 只判 `model.baseUrl`）：中转站默认不开，正是 pi 注释点名的风险。
2. **两次构造**：`continue` 要删 `system`／`tools` 并切片行，而 SDK 参数不可变、字段不能删 ⇒
   先建「不发 thread」的 probe 取哈希原料，再按判定重建。两次都是纯本地构造（无 I/O）。
3. **payloadHook 在 prepare 之后**：pi 是「先 hook 再切片」，本仓无法切 SDK 参数，故 hook 收到的
   已是最终形态；hook 若替换载荷，行指纹仍是替换前的（与 pi 的「哈希 hook 后参数」有差）。
4. **strict 降级与 threads 的次序**：pi 的 strict 降级在 prepare 之后重来一轮（状态已 consume ⇒
   第二轮变 `create`），本仓同式；`ProviderRetry` 的重试不重新 prepare（与 pi 一样不消耗状态）。

**测试**（新增 14 例，`mvn test -pl pi-java-ai`：2237 全绿）：`AnthropicThreadFingerprintTest`
（断点前后指纹一致、设置签名稳定、系统变更只动设置半边、对话增长保持前缀）、
`AnthropicThreadRequestsTest`（开关推导、create／continue／off、单次可续、回退模型不记）、
`AnthropicThreadsWireTest`（首轮 create ＋ beta、次轮只发新增且省略 system／tools、
system 变更回落 create、白名单外不发 thread）。

### 11.2 S8 的落地口径（2026-11-22）

**分流矩阵**：`AnthropicThreadFallback.classify` 三段判，逐字对位 pi `:414-433` —— 四个
`NEW_THREAD_ERRORS` ⇒ `NEW_THREAD`；`thread_unsupported_request` 或「无 code ＋ 400 ＋ 文案含
`thread`」⇒ `DROP_THREAD`；其余 ⇒ `NONE`（原样抛）。`error_code` 从
`AnthropicServiceException.body().convert(Object.class)` 走 `error.details.error_code` 取，
**只认字符串**（pi 的 `typeof code === "string"`）；体不是 JSON 或形状不同一律视为无 code。

**执行**：`AnthropicRequestSender.sendWithThreadFallback`（抽出去守 500 行）—— 中止或本轮没发
thread 都不重发；诊断 `anthropic_thread_fallback`（`details.reason = error_code ?? status`）在
重发**之前**追加；`create` 重发用「完整请求 ＋ create」，去 thread 重发用「probe（完整请求）＋ hook」，
`off` 只在去 thread 那次**成功**之后置（pi `:440-442`）。`Sent.threaded=false` 时清掉
`sentThread` ⇒ 本轮不记账（pi `:445` 的 `if (thread)`）。

**顺序**：threads 重发在**同一轮**内、strict 降级在它外层（pi 同式）；重发仍走
`ProviderRetry`，但重发本身不消耗 `maxRetries`（它是请求级重发，不是重试链上的一次）。

**与 pi 的一处差异（已知且有意）**：pi 的 payload hook 只调一次、两次重发复用同一个已 hook 的
`params`；本仓的重发要**重建**参数（SDK 参数不可变），故重发路径会**再调一次** hook。

**测试**（新增 3 例，`mvn test -pl pi-java-ai`：2240 全绿）：`thread_not_found` 续线程被拒 ⇒
诊断 reason=`thread_not_found` ＋ 以 `create` 完整重发（请求计数 +1）；无 code 的 400 文案含
`thread` ⇒ 去字段重发 ＋ 置 `off` ＋ 第三轮不再带 `thread`；无关 400（`prompt is too long`）⇒
不重发、直接出 `StreamError`。

### 11.3 S9 的落地口径（2026-11-22，代码面）

`disableContinuation`（pi `:908-909`，D-2 定为 `ApiOptions.extra` 键，与 `messageThreads` 同形，
两种来源：`Boolean` 或线格字符串）由 `AnthropicStreamSupport.disableContinuationOf` 读成车道字段。
车道把它与另外两件事分开判：

```
threadsSupported   = 保留期 ≠ none && 端点/compat 允许   → 决定 threads beta（pi :1116）
threadSessionId    = threadsSupported && !disableContinuation ? sessionId : null  → 决定发不发 thread
```

⚠️ 开关**只管 `thread` 字段**：关掉之后仍照发 `message-threads` beta（pi 的两处判据就是分开的），
所以「带 beta 但不带 thread」是正常请求，不是降级路径。

**测试**（新增 1 例，`mvn test -pl pi-java-ai`：2241 全绿）：`disableContinuation=true` 两轮都
无 `thread` 键（也不是 `create`），`anthropic-beta` 仍含 threads beta。

**真机冒烟：经用户裁决豁免（2026-11-22）**。原计划是 `api.anthropic.com` 单轮 `create` ＋ 一轮
`continue` 确认服务端接受 `previous_message_id`；因本地无 Anthropic 凭据、也不该进 CI，用户明确
「不验证了，直接认为已经做完」⇒ 以「代码落地 ＋ 2241 测试全绿 ＋ 回退矩阵有真机可替代的
`RecordingHttpServer` 观测」收口，`#113` 在 `map/02` 记为对齐。
⚠️ 遗留事实（不是待办，只是读者要知道）：`continue` 对真实 `api.anthropic.com` 的接受度只有
线上才会被最终证实；万一服务端口径与 pi 锚点不同，本仓已有的兜底是 `NEW_THREAD_ERRORS` ⇒ 以
`create` 重发（`AnthropicThreadFallback`），不会把错误暴露给用户。

