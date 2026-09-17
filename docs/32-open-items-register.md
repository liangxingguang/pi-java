# 32 - 未结项台账（open items register）

> **这份文件解决一个问题**：本分支「还有什么没做完 / 没对齐」此前散在 `docs/31` 的十九个小节，
> 以及 `docs/24` / `docs/08b` / `docs/09b` / `docs/27` / `docs/phase1-pi-code-mapping` 等
> 二十余份文档里 —— 没有一处能一眼看全，也没有一处说得清「谁挡着」。本台账把它们合并成一张表。
>
> **基准**：本台账写于 `4af78f9`（2026-09-17），分支 `agent-core-pi-loop` —— 当时领先本地 `main` **98** / 落后 0。
> 分支位置与测试计数会随提交漂移，**引用时以 `git rev-list --left-right --count main...HEAD` 为准**，不要抄这里的数字。
> 当时全 reactor `mvn -o clean verify` 绿；telemetry 31 / ai 336 / agent-core **450**。
>
> **本台账不复制原节论证** —— 每条只给「是什么 / 出处 / 谁挡着 / 修法素描」。
> 要读论证、证据与反向实验，按出处回原节。

---

## 0. 怎么读

### 0.1 两档可信度

| 档 | 范围 | 本次做法 |
|---|---|---|
| **已复核** | `docs/31` 的二十一处登记表（§8.2 / §8.3 / §8.6 / §8.7 / §8.9 / §8.15 / §8.18 / §8.19 / §8.21.5 / §8.22.5 / §8.23.5 / §8.24.5 / §8.25.5 / §8.26.5 / §8.27.4 / §8.28.5 / §8.28.7 / §8.29.6 / §8.30.8 / §8.31.4） | 逐节读原文核对，带**逐字标记**与行号 |
| **待复核** | `docs/31` **之外**的文档与主源码 javadoc —— **69 条**（H 类） | 机械扫描，**未逐条复核**，只保证出处准确 |

### 0.2 一个必须先说的更正

上一轮我口头报的是「登记在册、尚未结案的共 **20** 项」——**那是凭记忆数的**。
机械扫描在 `docs/31` 一处就找到 **40 个未处理 / 25 个已结案**，比我报的多了一倍：
漏掉的是 §8.2 / §8.3 / §8.6 / §8.7 / §8.9 / §8.18 / §8.19 / §8.24.5 / §8.27.4 这几处。
**这正是要落成台账的理由** —— 而且本次也已复核：上轮那些数字不可引用，以本文件为准。

### 0.3 「不做」也是结论，与「欠账」分列

判据是**行为**（分支所有功能都和 pi 表现一样），不是文档、不是 API 形状、不是文件清单。
所以下面 C 类（已裁决不改）与 G 类（已结案）**不是欠账**，是被裁决关闭的条目；
把它们和 A/B 两类混在一张表里，正是「还剩多少没对齐」这个问题一直答不准的原因。

---

## 1. 汇总

| 类 | 含义 | 条数 | 谁能推进 |
|---|---|---:|---|
| **A** | 要**证据**才能定案（多数要先读 pi 源码） | 12 | 我（读 pi 源码 / 清点） |
| **B** | **功能缺口**（pi 有、pi-java 无） | 9 | 我（另立包，多数需先出设计文档） |
| **C** | 已裁决**不改 / 不做**，带触发条件 | 11 | 不推进，除非触发条件成立 |
| **D** | 小账（遥测/注释级，一处一行） | 8 | 我，随时可做 |
| **E** | 结构债（>500 行文件等） | 8 | 我，与功能包搭车 |
| **F** | **待用户拍板** | 6 | **你** |
| **G** | 已结案（**别重开**） | 20 | —— |
| **H** | `docs/31` 之外，机械扫描**待复核** | 69（有重复，见 §9） | 我（逐条复核后才能定档） |

> B/C/F 三类的本次增量（B6–B9 / C8–C10 / F6）全部来自 §8.31 的登记表，实现时又新发现两条（B9/C10）。
> **2026-09-18（包①，§8.33）后的变动**：B6/B7/B9 与 P1/P4/P6 实施 ⇒ 移入 G（+5 行）；B4 **结案为不做** ⇒
> 移入 C（C11）；§8.32.2 的 P2/P3/P7 立为 **B10/B11/B12**（跨模型重放闸 / 空 text 块 / `finalError` 键），
> P8 立为 **E8**（L5 夹具对 thinking 块不对称）；P5（行号错位）已就地更正 ⇒ 入 G。
> 又新登记 **A12**（`PiWebServerAuthTest` 负载敏感 flake，**§8.23.7 早已记过**，非新缺陷）⇒ A 类 11→12 行。
> **2026-09-18（包② 设计，§8.34）**：包② 取证推翻了我方**四处错引**（`appendThinkingBlock` 是 `:311-328`
> 不是 `:295-312`、text 块闸位在 `:284-286` 不是 `:268-270`、`isSameModel` 在 `transform-messages.ts:95-98`
> 不是 `:89`、compat 声明在 `types.ts:713-714` 不是 `:193`），并更正 **B8 的「三态」为两态**；
> 新登记 **B13**（重放产不出 `redacted_thinking` 线格 —— `redacted()` 在生产代码**零读点**）、
> **B14**（`transform-messages.ts` 其余四条变换）、**D8**（`AnthropicMessagesApi:299-301` 的假注释）
> ⇒ B 类 7→9 行、D 类 7→8 行。
> 故 B 类的「条数」不增反减是**结案**的结果，不是漏记。

**收敛路径**：A 类与 F 类是真正的闸门 —— A 挡在「读 pi / 清点」上，F 挡在「你的决定」上；
B 类是产品缺口、本来就不属于「对齐」；C/D/E 三类随时可做，没有一条阻塞合并。
**没有任何一项挡着合并到 `main`。**

---

## 2. A 类 —— 要证据才能定案

| # | 条目 | 出处 | 挡在什么上 |
|---|---|---|---|
| A1 | `Entry` 八种类型 ↔ `ContextEntries.toMessages` 的转换**逐项核对** | `docs/31:467` | 未核。「⇒ 仍待做」字样仍在 |
| A2 | 下游消费者不依赖 `transcript()` —— **未清点** | `docs/31:471` | TUI / web / RPC / SQLite 四个读取点未清点，`messages` 取代它是否等价未验证 |
| A3 | `AgentLoopTurnUpdate.thinkingLevel` **差分结构上覆盖不到**（S9） | `docs/31:584` | 该通道不出现在任何帧上；要覆盖必须**先把形状折进帧**，否则「加了剧本」是错觉 |
| A4 | 运行中手动 `/compact` **有没有门** —— 先取证 pi | `docs/31:2503` | pi 侧未取证；**这是行为改动，不是归属改动** |
| A5 | 并发 prompt **有没有门** —— 同样先取证 pi | `docs/31:2502` | 同上；且要的是一条**会话级**串行保证 |
| A6 | 宿主消费者的**并发契约**未声明（TUI / RPC / web） | `docs/31:1779` | 收敛到 `PiLaneSink.emit` 的锁后仍互斥，但「总是哪个线程」不再唯一，无显式声明 |
| A7 | `retry.provider.*`（timeoutMs/maxRetries/maxRetryDelayMs）↔ ai HTTP `RetryPolicy` 的映射对照 | `docs/31:1548` | 独立清点项，未做 |
| A8 | `agent_end` **载荷全量对照**（pi = 本 pass `newMessages`；pi-java = `accumulatedMessages`） | `docs/31:1554` | 3d 只改了频率，载荷未对照 |
| A9 | **per-model 压缩设置**（pi `getCompactionSettings(model)`，settings-manager.ts:891-901） | `docs/31:1322` | pi-java 的 settings supplier **无模型参数** |
| A10 | `approvalHandler` 与钩子体在**工具线程**执行 ⇒ 交互类钩子（权限询问）需自证线程安全 | `docs/31:1784` | 清点项（pi 同形状） |
| A11 | **内置工具线程安全审计**（`coding-agent` 侧 read/write/edit/bash/glob…） | `docs/31:1782` | 本轮**只审计不改**；审计本身尚未开始。pi 的契约是「工具必须可并发执行」 |
| A12 | `PiWebServerAuthTest.acceptsConnectionWithValidToken:97` 在全 reactor 跑时**偶发红**（15 s 内没等到 `ready` 帧） | §8.33.9-G（`docs/31:4080`）；**早在 §8.23.7 已记过**（`docs/31:1625`「三次全 reactor 红、第四次绿」、`:2593` 同） | **未定位根因**。隔离复跑 6 次红 1 绿 5（红那次 15.05 s，绿均 ~1.3 s）⇒ **非确定性**；且 `pi-java-web/` 在包① 的 `git diff` 里零改动 ⇒ **与包① 无关**（§8.23.7 在前、包① 在后，同一现象，非新缺陷）。**需先定位根因**，未定位前不修、不加超时（那是掩盖） |

> A4 / A5 取证之后会变成**行为改动**，需你拍板 ⇒ 见 F 类。

---

## 3. B 类 —— 功能缺口（pi 有、pi-java 无）

| # | 条目 | 出处 | 修法素描 |
|---|---|---|---|
| B1 | **branch summary 无实现**（pi `branch-summarization.ts:349-353`） | `docs/31:1550` | `source:"branchSummary"` 的重试路**形状**已保留，功能本体缺 ⇒ 另立包 |
| B2 | compaction **`details` 生产者**恒 null | `docs/31:1324` | **已实施**（§8.30，`4380796`）⇒ 见 G 类；同族四处（摘要 prompt/`previousSummary`/请求参数/split turn）另立，§8.30.7 |
| B3 | TUI/RPC 对 **auto_retry / summarization_retry 的渲染**（倒计时、`isRetrying`、isIdle 含重试） | `docs/31:1552` | 并入「命令/界面面」清点 |
| B4 | `addedToolNames` 的 **provider 层消费者** | `docs/31:1060` | **结案为不做**（机制归属原判是错的：`addedToolNames` 来自**扩展系统**，不是 MCP；pi 侧 `extensions/wrapper.ts:17-37` → `deferred-tools.ts:8-39`）⇒ 见 C 类 C11 |
| B5 | 宿主层：`SessionRunner` 两处 `catch (Exception)` **不接 `Error`** ⇒ `statusFuture`/`entriesFuture` 永不完成、不发 `AgentEnd`/`AgentSettled`、宿主**永久挂起** | `docs/31:2685`（§8.26.5-12 的下游） | pi 的 `handleRunFailure` 把异常**压成文本**、合成 assistant 消息、promise **resolve** —— 另一处更大的差距，另立包 |
| B6 | content_block_start 的**初始 thinking 文本被丢弃** | `docs/31:3590`、§8.31.4 | **已实施**（§8.33 包①）⇒ 见 G 类 |
| B7 | **`redacted_thinking` 未处理** | `docs/31:3591`、§8.31.4 | **已实施**（§8.33 包①；SDK 路由 `ContentBlock.kt:549-553` 已实证）⇒ 见 G 类 |
| B8 | **空签名重放策略不可配**（pi 的 `Model.compat.allowEmptySignature`，`types.ts:713-714`） | `docs/31:3594`、§8.31.4、**§8.34** | ⚠️ **更正：行为上只有两态**，不是三态 —— `undefined` 与 `false` **完全等价**（`anthropic-messages.ts:193` 的 `?? false`），只有 `true` 不同（已实测）。启用的模型也不是「Kimi 系」而是**三处**：Fireworks 全部 anthropic-messages 模型（`generate-models.ts:1427`，无 allowlist）、Kimi Coding 全部（`:2242`/`:2253`）、Xiaomi（`:1075`，但**休眠**）。pi-java `ModelInfo` 无 `compat` 且 `ModelsJsonSchema` 会**静默吞掉**用户写的 `compat` ⇒ 需 compat 字段 + models.json schema 扩展。**被 B10 前置**。归包②，设计见 §8.34 |
| B9 | **初始 signature 进不了 `ThinkingStart.partial`**（实现时才发现的） | `docs/31:3596`、§8.31.4 | **已实施**（§8.33 包①，与 B6 同一处改动）⇒ 见 G 类 |
| B10 | **pi 的 `transform-messages.ts` 整段缺失**（跨模型重放闸：`isSameModel = provider && api && model.id`；跨模型丢 redacted、thinking 降级 text） | §8.32.2-P2（`docs/31:3723`） | 移植 `transform-messages.ts:95-116` 的 **thinking 五分支**（`:95-98` 判据 + `:101-116` 分支）；**设计见 §8.34**；**是 B8 的前置** —— 不加此闸，B8 会让跨模型重放**比今天更错**。归包② |
| B11 | **空 text 块不丢**（重放时会把空 `TextContent` 原样发给 Anthropic） | §8.32.2-P3（`docs/31:3724`） | 照 pi `anthropic-messages.ts:1282` 的 `if (block.text.trim().length === 0) continue;` 补闸（pi-java 无闸处在 `AnthropicMessagesApi:284-286`）；**设计见 §8.34**。归包② |
| B12 | **`auto_retry_end` 成功路多写 `"finalError":null`** | §8.32.2-P7（`docs/31:3730`） | pi 侧 `undefined` 被 `JSON.stringify` 省略；pi-java `JsonEventMapper.java:94-99` 无条件 `put`。同文件 `:110-113` 已有「null ⇒ 省略」先例 ⇒ 照抄。归包④ |
| B13 | **重放路径产不出 `redacted_thinking` 线格** —— pi-java 全仓没有代码路径能发出该块 | §8.34.2-2（`docs/31:4201`） | `redacted()` 在 `pi-java-ai` 生产代码**零读点** ⇒ redacted 块落进 `AnthropicMessagesApi:323-327` 的有签名分支，被当作**带签名的 thinking 块**发出、签名位放的是**加密载荷**。pi 的对照是 `anthropic-messages.ts:1289-1295`。归包② |
| B14 | **`transform-messages.ts` 的其余四条变换全部缺失**（一条聚合行） | §8.34.3（`docs/31:4242`） | ① 图片降级为占位文本（`transform-messages.ts:35-57`，按 `model.input` 判定）；② 跨模型剥离 toolCall 的 `thoughtSignature`（`:131-134`）；③ 跨模型归一 toolCall id（`:136-142`）；④ **孤儿 toolCall 合成 `toolResult`**（`:158-220`「No result provided」）＋ 跳过 `error`/`aborted` 助手消息（`:194-197`）。④ 是**真功能**、其余三条是清理 ⇒ 不塞进包②（会让 200 行变 800 行），另立 |

---

## 4. C 类 —— 已裁决「不改 / 不做」（带触发条件）

| # | 条目 | 出处 | 裁决理由（一句话） | 触发条件 |
|---|---|---|---|---|
| C1 | `ActiveToolsChange` **不加发射** | `docs/31:686` | `setActiveTools` 生产路径**零调用者**，加发射是投机代码 | 出现真实调用者 |
| C2 | 批内 join 是**源序**，异常选择与 pi 的 `Promise.all` 不同 | `docs/31:2685` | 可达性窄（同批 ≥2 条抛 `Error`）；按完成序重抛会引入 pi-java 从未有过的语义 | —— （宿主那半边见 B5） |
| C3 | `transcript`/`messages`/`partial`/`runId` 等字段的**可见性** | `docs/31:2746` | 写者**唯一**（引擎线程），不产生结构破坏；最坏是「少最后一条」 | —— |
| C4 | `RunLifecycle.reset:228` 把 `lane.runSpan` 置 null 却**不 `close()`** | `docs/31:2981` | **今天就不可达**（两条独立证明，§8.28.5） | 出现不带 `isRunning` 门的 reset 变体，或 `restoreRecords` 被运行中调用 ⇒ 修法一行 |
| C5 | 开/关**顺序不对称**：`PiLaneSink.endRequest:197-198` 是 `close()` 再 `popCurrent()`，`JsonlFileTelemetry.startSpan:131-132` 相反 | `docs/31:2983` | 今天无影响（`popCurrent` 的守卫是 `peek()==span`，与是否已结算无关） | —— |
| C6 | §8.24.5 四条未覆盖：② 完成序保证本身 · ③ 消费者阻塞语义 · `acceptingUpdates` 闩的迟到 update · 串行路径下的 update 时机 | `docs/31:2156-2159` | 前两条**不可达**（`PiLoop.Sink` 契约是同步 `void` ⇒ 排除异步消费者）；后两条无剧本可证 | —— |
| C7 | 是否另立 **`TelemetryAdapterConformance` 套件** | `docs/31:3043` | **倾向不做** —— pi 那 9 条套件的注册表里只有一个测试用 adapter，无第二个 adapter 时套抽象基类不划算 | 出现 OTel 之外的真 adapter |
| C8 | signature 会**多发一条** `ThinkingDelta` 事件（pi 只改块、不 push） | `docs/31:3592`、§8.31.4 | 改的是 `StreamEvent` 通道形状，且**无剧本可证**（同 §8.24 口径：不钉没剧本的形状） | L5 剧本覆盖 `SignatureDelta` 的形状 |
| C9 | **per-model `api` 表达不出**（单 provider 多 API，pi 的 fireworks/opencode） | `docs/31:3593`、§8.31.4 | `models.json` 的 `api` 在 **provider 级**；P1 做到 provider 级派发即覆盖今日全部已注册 provider，加字段是投机代码（同 C1 口径） | 出现单 provider 多 API 的真实需求 |
| C10 | `emitThinkingSignature` **先于** `emitThinkingStart` ⇒ `IndexOutOfBoundsException`（实现时才发现的） | `docs/31:3597`、§8.31.4 | 生产不可达；且与同族的 `emitThinkingDelta`（有惰性建块分支）**不对称** ⇒ 加分支属投机代码 | 出现「先 `signature_delta` 后 `content_block_start`」的真事件序列 |
| C11 | `addedToolNames` 的 **provider 层消费者**（原 B4） | `docs/31:3749`（§8.32.4） | **结案为不做**，且**机制归属原判是错的**：`addedToolNames` 来自 pi 的**扩展系统**（`extensions/wrapper.ts:17-37` → `deferred-tools.ts:8-39`），不是 MCP；消费侧的闸是 `openai-completions.ts:838-840` 的 `compat.deferredToolsMode === "kimi"` | ① 扩展系统落地且真产出 `addedToolNames`；② 出现 `deferredToolsMode` 为真的 provider；③ 有用户报告 deferred tools 不生效 |

---

## 5. D 类 —— 小账（一处一行）

| # | 条目 | 出处 |
|---|---|---|
| D1 | `JsonlFileTelemetry.with(...)` 返回**新实例**（新文件/新锁/新栈）；A1 后新实例各有独立 `ThreadLocal` | `docs/31:2500` |
| D2 | `JsonlSpan.startSpan:338-349` **不碰当前栈**，而接口 `startSpan` 的 javadoc 一个字没提「绑定为当前跨度」⇒ **未文档化的局部行为**（三实现里只有一个有） | `docs/31:2505` |
| D3 | 接口 `openSpan` 的 **default 实现返回已结束的 span**（`TelemetryContext:32-39`）⇒ 装饰器**必须显式转发** | `docs/31:2506` |
| D4 | 摘要重试的**每次尝试**没有各自的跨度（内层环共享一条；主循环那边每次 attempt 一条）—— 够用，但不对称 | `docs/31:3232` |
| D5 | **截断兜底生成器**下那条「无事件、无 token」的跨度形状**无测试** | `docs/31:3234` |
| D6 | `SummaryGenerator.java:12` javadoc「until Phase 6 wires the real summarization flow」**已过期**（生产装的是 `LlmSummaryGenerator`） | 主源码 |
| D7 | `client` / `protocol` / `server` 三个 `package-info.java` 写「Phase 6 will implement…」—— 三个模块都已实现 | 主源码 |
| D8 | `AnthropicMessagesApi.java:299-301` 的注释写「ThinkingContent is dropped: replaying thinking blocks requires the original signature…」—— 而**它下面 `:288` 正是在重放**（生产源码里的假陈述） | §8.34.2-7（`docs/31:4227`） |

---

## 6. E 类 —— 结构债

| # | 条目 | 出处 / 实测 |
|---|---|---|
| E1 | `AgentSession.java` **988** 行 | `pi-java-coding-agent/.../core/AgentSession.java` |
| E2 | `RpcDispatcher.java` **601** 行 | `pi-java-coding-agent/.../rpc/RpcDispatcher.java` |
| E3 | `PiLaneSink.java` **510** 行 | `pi-java-agent-core/.../harness/PiLaneSink.java` |
| E4 | `AgentHarness.java` **502** 行 | `pi-java-agent-core/.../harness/AgentHarness.java` |
| E5 | `EventParser.java` **515** 行 —— **已登记例外**（TamboUI same-package 覆写） | `pi-java-tui/.../dev/tamboui/tui/event/EventParser.java` |
| E6 | 拆分超限文件（`SqliteSessionStorage`、`AgentHarness`） | `docs/09b:117` |
| E7 | `appendEntry` / `appendRecord` 在 JSONL/Memory 两处重复 —— **判断项，保留**（差异小且各自持有锁语义） | `docs/09b:140` |
| E8 | **L5 两侧 scripted stream 对 thinking 块不对称**：pi 侧 `ScriptedStream` 的推事件循环**只**处理 `text`/`toolCall`（thinking 块零 `thinking_*` 事件），Java 侧 `ScriptedStreams.eventsFor` 的 `case "thinking"` 推 `ThinkingStart`+`ThinkingEnd`（⇒ 每块多两条 `message_update` 帧） | §8.32.2-**P8**（`docs/31:3731`）；`conformance/pi/run.test.ts` vs `ScriptedStreams.java:73-79` |

> 仓库规则是「文件 ≤ 500 行」；E1–E4 是唯一破口的四处（E5 有例外登记）。
> 拆分点已在 §8.23.5 / `docs/28` 讨论过，没有一条与功能耦合 ⇒ 可随时做。

---

## 7. F 类 —— 待你拍板

| # | 条目 | 出处 | 需要你定的 |
|---|---|---|---|
| F1 | **`QueueMode.All` 的宿主层行为变更** | `docs/31:947` | 改不改（从 §8.15 挂到今天，三次登记都指向这里） |
| F2 | §8.25.5-3 **`recordEvent` 的环境态语义**：没有宿主跨度时，事件行怎么办 | `docs/31:2499`、`:3034` | **仍未结案** —— 不能照搬 `span.addEvent`（pi 的是随 span 落地的小属性，pi-java 的是整份负载的独立行）；第 8 条已修，第 3 条问的是兜底规则，面不同 |
| F3 | **`_emitSessionCompactFailed`**（扩展层事件） | `docs/31:1326` | 发不发 —— 扩展层在 `docs/27 §4` 排除面内，observer 目前只保证会话事件 `compaction_end.errorMessage` |
| F4 | A4 / A5 两道门（运行中 `/compact`、并发 prompt） | `docs/31:2502-2503` | 取证后确认是**行为改动**，不是我单方面能定的 |
| F5 | **合并到 `main` 的时机** | —— | 我建议**现在**：见 §1「收敛路径」，无任何一项挡着合并 |
| F6 | `ModelsJsonProvider` 钉死 baseUrl ⇒ **CLI `--base-url` 对它失效**，且注释与实现**不符** | `docs/31:3595`、§8.31.4 | 二选一：**改注释**（认下「models.json 的 baseUrl 恒赢」）还是**让 CLI 赢**（改实现，`args.baseUrl()` 提到 pinned 之前） |

---

## 8. G 类 —— 已结案（**别重开**）

| 条目 | 结案处 | 日期 / commit |
|---|---|---|
| §8.25.5-1 跨度词汇对齐 pi typed schema（**并含方案 C「删环境态」**） | `docs/31:2497`、§8.28.7-1 | 2026-09-17，结案为**不做** |
| §8.25.5-2 adapter 契约 9 条 —— 只修真实差异的那条（父已结算后开子跨度降级 noop） | `docs/31:2498`、§8.28.4 | `35c4758` / `c3eef82` |
| §8.25.5-5 `docs/18 §7.3` 描述的 worker push/pop 已无实现 | `docs/31:2501` | 2026-09-16（A3） |
| §8.25.5-8 摘要请求没有自己的跨度 | `docs/31:2504`、§8.29 | `798cccd` / `5dfa640` |
| §8.26.5-11 顺序路径 `batchSize` 语义错 | `docs/31:2684`、§8.28.6 | `7f0cfb9` |
| §8.26.5-13 `lane.records` 被工具线程写入（数据竞争） | `docs/31:2686`、§8.27 | `0e76e5a` / `c87359c` |
| §8.23.5-1 `currentStack` 是全局 `ArrayDeque` | `docs/31:1770`、§8.25 | 2026-09-16（`ThreadLocal`） |
| §8.23.5-2 `tool_execution_update` 发射时机 | `docs/31:1774-1778`、§8.24 | 2026-09-16（结论：无差距） |
| §8.28.5-① 父已结算后开的子跨度仍落盘 | `docs/31:2980` | `35c4758` |
| §8.28.5-③ `JsonlSpan.markAborted()` 无调用者 | `docs/31:2982` | `7add447`（删第三状态） |
| §8.3-6 `prepareNextTurn` 的 `context` 通道缺失 | `docs/31:474-490` | 2026-09-13 |
| §8.21.5 重试判据白/黑名单反转 | `docs/31:1316-1321`、§8.22 | 2026-09-15（`f27b1bd`） |
| **B2** compaction `details` 生产者恒 null（含摘要尾部 `<read-files>`/`<modified-files>`） | `docs/31:1324`、§8.30.8 | 2026-09-17，`4380796` |
| **§8.31 P1+P2** 生产事故根因：① 适配器不跟 `model.provider()` 走（StreamFn 闭包会话 provider）② thinking `signature` 用严格必填访问器打死整轮 run | `docs/31:3457`、§8.31.8 | 2026-09-17，`fb4866d`（P2）/ `922ef4c`（P1）；**未做端到端复现**，见 §8.31.8 末 |
| **B6 / B9** 初始 thinking 文本与初始 signature 进不了 `content_block_start` 的第一个 partial（`ThinkingStart.partial`） | `docs/31:3787`、§8.33.9-A | 2026-09-18，包① |
| **B7** `redacted_thinking` 被当 text 块（载荷丢失、且回落成 `TextStart`） | `docs/31:3787`、§8.33.9-A | 2026-09-18，包①（SDK 路由 `ContentBlock.kt:549-553` 实证） |
| **P1 / P6** thinking 块的 `signature`/`redacted` 既不落盘也不回读；落盘键名 `text` 与 pi 的 `thinking` 不一致 | `docs/31:3787`、§8.33.9-B | 2026-09-18，包①（旧键 `text` 仍兜底读） |
| **P4** pi-messages 车道的 `thinking_end` 从不装配 signature/redacted | `docs/31:3787`、§8.33.9-A | 2026-09-18，包① |
| **P5** §8.31.4 的 pi 行号整体错位 1 行 | §8.32.2-P5；已就地更正（R1 `:632`、R2 `:638-647`、R7 `:630-635`/`:636`/`:637`） | 2026-09-18，包① |
| §8.15「**遗留**：B 项（延迟任务真并发 = pi 的 `Promise.all`）仍开放」 | §8.23 | 2026-09-16 —— **原文那行是过期陈述，已被 §8.23 取代**（本地已回填标记） |

> 最后一条特别提一下：`docs/31:945`（以及 `:1059` / `:1117` 两处重复）写着 B 项「仍开放 / 待用户」，
> 而 §8.23 已于 2026-09-16 实施闭环 —— 这正是你问的「信息不知道在哪里」的典型样本。
> 本次已在原行就地回填指向（**同长度改写，不动行号**，以免本台账的行号引用全部失效）。

---

## 9. H 类 —— `docs/31` 之外（机械扫描，**未逐条复核**）

**先说清两件事**：

1. 这 69 条来自对 `docs/31` 之外二十余份文档与主源码 javadoc 的机械扫描。
   扫描**会把「陈述句」「历史记录」「已废弃的设计」一并标成「未做」** —— 例如
   `docs/01 §6 存储选型`、`docs/10 v1.10 放弃原生分发` 都被标成了 OPEN。
   **本台账未逐条复核**，只保证出处准确。
2. 其中很大一部分是 **Phase 6 时期的长期有意 descope**（Bedrock/Vertex、auto-update、Bun 运行时…），
   与当前 agent-core 对齐工作**不同轨**。它们确实是「没对齐」，但不是「待办」。

### 9.1 扩展系统（`docs/24`）—— 一整块未落地的功能

| 条目 | 出处 |
|---|---|
| 运行时事件订阅 **30 个 → 0 个**（pi `types.ts:1203-1244`） | `docs/24:64` |
| 热重载 `/reload`（pi `agent-session.ts:2610-2635`）**无** | `docs/24:71` |
| P0：`ExtensionContext` 暴露 `hookSystem()` | `docs/24:86` |
| P0：扩展订阅 `AgentSessionEvent` | `docs/24:87` |
| P1：`PiExtension.dispose()` 生命周期 | `docs/24:88` |
| 扩展侧 `onEvent` 未落地 | `docs/24:454` |
| `sendMessage` 的 `triggerTurn` 当前抛 `UnsupportedOperationException` | `docs/24:464`、`ExtensionContext.java:53/:60`、`DefaultExtensionContext.java:88` |
| 热加载整体后置到 P2（先做 C + D） | `docs/24:409` |
| TUI 扩展 UI 组件（`custom<T>()` / `setFooter`）列 P2 | `docs/24:462` |
| 四个候选方案待评审（§4.5 决策矩阵） | `docs/24:14` |
| ADR 目标档位「A（逻辑重载）—— 待确认」/ 结论「**待填**」 | `docs/24:424`、`:425` |

### 9.2 TUI（`docs/08b`、`docs/15`）

| 条目 | 出处 |
|---|---|
| inline 模式超长草稿 off-screen 更新与终端滚动交互的已知 artifact | `docs/08b:474` |
| `app.message.followUp`（Alt+Enter 排队）与 Alt+Enter=换行 **键位占用** | `docs/08b:506` |
| `StreamPartialBuilder` / `ToolCallAccumulator` 仍为**单工具调用模型**，需多槽位 | `docs/08b:525`、`ToolCallAccumulator.java:21` |
| STDIN 传输下命令内的交互式 stdin（如 `read`）与命令输入**共享管道** | `docs/08b:555` |
| Codex 启动 logo ASCII 动画（frames 帧驱动）**未移植** | `docs/08b:579` |
| 模型 arguments 截断到连 `command` 字段都不完整时，命令**仍无法恢复** | `docs/08b:607` |
| Anthropic 路径的**思考内容仍按 `TextContent` 处理** | `docs/08b:628` |
| webui 未决字段待 Stage A 实机钉死 | `docs/15:150` |
| web 端 `queue_update` / `compaction_*` / `auto_retry_*` **暂不推前端** | `AgentEventTranslator.java:57` |
| `keybindings.json` 用户覆盖 → Phase 6 | `KeybindingsManager.java:14` |

### 9.3 持久化 / 存储（`docs/09`、`docs/09b`、`docs/27`、`docs/30`）

| 条目 | 出处 | 备注 |
|---|---|---|
| `pendingWrites` **欠写不收敛**（一旦欠账则永远欠着），修复**重新进入待办** | `docs/27:149`、`docs/28:336`、`docs/30:171` | 三处同指一事 |
| **日志不变量不再有生产端守卫**（随折叠链删除） | `docs/30:174` | |
| 会话恢复的**定位语义** 待核 | `docs/27:100` | |
| `/import` 与 `/export`（JSONL）是**占位符，未实现** | `docs/09b:93` | 扫描标 CLOSED，**文本说未实现——应为 OPEN** |
| `/new` 文案陈旧 | `docs/09b:94` | |
| Compaction v2「部分落地」（丢弃 usage） | `docs/09b:95` | |
| 根 entry 的 `parentId` 被**省略**而非输出 `"parentId":null` | `docs/09b:103` | |
| JSONL 扫描式搜索后端（按需） | `docs/09:1633`、`phase1-pi-code-mapping:220` | |
| 多进程读并发压测 | `phase1-pi-code-mapping:397` | |
| `DeferredHandle` **无生产者** | `docs/superpowers/plans/2026-09-12-…md:16` | |
| ⚠️ **对外协议变更**：`agent_end` wire 多出 `stopReason` | 同上 `:399` | **需知悉** |

### 9.4 Provider / AI 层

| 条目 | 出处 |
|---|---|
| 六个协议适配器未实现；Bedrock Converse / Google Vertex 等（按需 / 渐进） | `docs/11:84`、`phase1-pi-code-mapping:49` |
| Bedrock / Azure / Vertex 整体移出本阶段，留作后续独立议题 | `docs/11:103`、`docs/04:307` |
| adapter 消费 `headers` / `samplingParams` 下发到请求，列为后续 | `docs/13:29` |
| `serializeConversation` 完整细节渐进 | `docs/13:45` |
| `tools` 的 `strict` 模式支持情况**待确认** | `docs/06:380` |
| prompt-templates 的 slash 命令接入后续（模板本体已实现） | `docs/14:46` |
| `PricingInfo` 只分 input/output（pi 四种：+cacheRead/cacheWrite） | `phase1-pi-code-mapping:79` |
| OAuth flow 覆盖 9 个中的子集 | `phase1-pi-code-mapping:93` |
| `AiCli` 缺 OAuth login | `phase1-pi-code-mapping:109` |
| `TelemetrySpan` 的 `addLink`/`event` 未实现 | `phase1-pi-code-mapping:130` |
| telemetry memory 后端 + conformance 套件未实现 | `phase1-pi-code-mapping:134`、`:395` |
| 图片工具 / 批量文件变更队列未实现 | `phase1-pi-code-mapping:195` |
| harness 富记录字段逐步填充（`resultEntryId` / `effectiveArgs` / `usage.cause`）、branch-summarization、image 工具 | `phase1-pi-code-mapping:396` |
| `pi utils/` 的 33 文件工具集未移植（`bun/` 不适用） | `phase1-pi-code-mapping:309` |
| prompt-templates / auto-format / resource-loader / process-manager / auto-update / image 工具 | `phase1-pi-code-mapping:287` |
| 剩余供应商适配器与 constrained sampling | `phase1-pi-code-mapping:394` |
| coding-agent：自动更新 / TUI 图片预览 / interactive 模式未逐文件移植 | `phase1-pi-code-mapping:399` |
| `session` / `process` 细粒度控制（pi 命令更多） | `phase1-pi-code-mapping:400` |
| evals 完整测试矩阵 | `phase1-pi-code-mapping:401` |

> ⚠️ **映射文档内部有矛盾**，复核时要先解掉：
> `phase1-pi-code-mapping:384` 写「② 外围能力（auto-update / **prompt-templates** / constrained-sampling 等）
> **明确不实现**」，而 `:233` 又写「prompt-templates 已实现（`PromptTemplates`，`docs/14 §1.3`）」，
> `:287` 又把它列在「缺口」里。**同一份文档三处说法不同。**

### 9.5 机械扫描的误报样本（**别去追**）

| 被标 OPEN 的条目 | 出处 | 实际 |
|---|---|---|
| `SQLite 为主存储（FTS5 搜索、写租约）` | `docs/01:127` | 是**设计决策陈述**，不是任务 |
| `GraalVM Native Image — 单一二进制` | `docs/01:129` | **已废弃**（`docs/10` v1.10 放弃原生分发、`docs/04:313`） |
| `pi-ai` 原生产物与 native 冒烟留 TODO（P5-5） | `docs/10:568` | 同上，随原生分发放弃而作废 |
| Native Image 相关约束「全部作废」，改为 fat jar | `docs/11:2024` | 自述即 CLOSED |
| 六个待确认项已于 2026-08-19 全部查证定案 | `docs/11:2034` | 自述即 CLOSED |
| 与 `01` 措辞偏离，特此记录 | `docs/09:85` | 是**记录**，不是待办 |
| `docs/12` 状态：草稿（待审核） | `docs/12:3` | 是头部状态行 |
| `docs/23c` 的 §0.1 / §0 ②档 / §3 剧本 | `docs/23c:17-22` | 顶部**作废横幅**，自述「❌ 作废 / ❌ 反了 / ⚠️ 需重定」 |
| `no_multi_lane` / `P2c-1` / `P2c-2` 多车道模型 | `docs/04:157-158` | **已作废**（带删除线） |
| `ToolDefinition` 类型缺失 / `renderResult` 推迟 Phase 3 / `/export`、`/share` 占位 | `docs/07b:1158`、`:249`、`docs/08:1335` | 历史记录，已完成 |

---

## 10. 维护规则

1. **新增登记**先进 `docs/31` 的对应 §8.x 小节（要有证据与反向实验），**再**在本台账加一行。
   本台账是**索引**，不是第一现场。
2. 每条必须有**出处**（`file:line`）。结案时在 G 类留行、注明日期与 commit，**不要删行** ——
   删掉的条目会以「这是不是还没做？」的形式重新出现。
3. **就地改写、不增删行**：回填指向时保持行数与其它条目不变，否则本文件里的行号引用会集体失效。
4. 分类变动（如 A→C）时**改行不改号**，在「裁决理由」列写明。
5. H 类逐条复核后，按结论分流到 A–G 或 §9.5；**复核一条就搬一条**，不要批量搬。
