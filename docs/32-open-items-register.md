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
| **B** | **功能缺口**（pi 有、pi-java 无） | 40 | 我（另立包，多数需先出设计文档） |
| **C** | 已裁决**不改 / 不做**，带触发条件 | 11 | 不推进，除非触发条件成立 |
| **D** | 小账（遥测/注释级，一处一行） | 8 | 我，随时可做 |
| **E** | 结构债（>500 行文件等） | 8 | 我，与功能包搭车 |
| **F** | **待用户拍板** | 6 | **你** |
| **G** | 已结案（**别重开**） | 21 | —— |
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
> **2026-09-18（包② 开工侦察）**：6 条线格夹具**先红证毕**（`Tests run: 12, Failures: 6`，逐条 actual 见 §8.34.5）；
> 新登记 **B15**（扩展开启在生产上不可达 —— 「目录元数据 → 请求路径」通道整体缺失，`compat` 与
> `thinkingLevelMap` 是同一断链的两个受害者）⇒ B 类 9→10 行；证伪点 2 **结论反转**
> （pi 的 trim 不对称**不可观察** ⇒ 不照抄，见 §8.34.6-2）。
> 故 B 类的「条数」不增反减是**结案**的结果，不是漏记。
> **2026-09-18（包② 实施，§8.34.11）**：**B8/B10/B11/B13 全部实施** ⇒ 移入 G（+4 行）；
> D8 已修（删假注释）⇒ G。实施中**又新发现三条** ⇒ **B16**（`sanitizeSurrogates` 全仓无对应物，
> pi 侧 54 处调用跨**所有**车道）、**B17**（Anthropic 车道静默丢弃图片块，另三条车道都映射了 ⇒ 该车道独缺）、
> **B18**（`PiMessagesApi` 丢签名与 `redacted` —— 包② **刻意**没给它挂闸，因为它不是 pi 的车道，
> 见 §8.34.11-（1））⇒ B 类 10→**13** 行。**B15 减半**：投送链已由包② 修好，
> 只剩「`thinkingLevelMap` 生产上不可构造」那半边。

> **2026-09-18（生产事故取证，§8.35）**：用户报告一次运行「中断」（会话 `2026-09-10T15-13-13`，
> `glm-5.3-flash` via `api.teamorouter.cn`：最后一个请求 201 s 后返回 `out=1014`，
> 而 assistant 消息 **`content: []`** ⇒ 前端静默停住）。经**真实 `openai` 车道**复现
> （P2/P3 两条探针：可见内容 2 / 1 字符，relay 计费 127 / 102 输出 token ⇒ 纯推理回复形态）
> ⇒ 根因＝**响应侧 reasoning 字段被整段丢弃**，登记 **B19**；同一次取证**顺带核实**了
> **B20**（stop reason 映射**跨四车道**缺失 —— 审计发现连主车道 Anthropic 也从不读
> `stop_reason`，`length` 机制在该车道**整段死掉**）与 **B21**（请求侧无 `compat.thinkingFormat`；
> ⚠️ 该 relay 上 pi **也不发** ⇒ **与本次事故无关**，如实标注）。
> ⇒ B 类 13→**16** 行，并更正 §1 汇总里滞后的 10（那行自包② 起就没跟上）。
> ⚠️ **口径说明**：本类历来的「条数」是**递增记账**，我不去重构它的定义。
> **2026-09-19 复核计数**（`grep -c '^| B[0-9]* |'`）：B 类**共 26 行**（B1–B26），
> 其中行内含「已实施/已落地」的 **11** 条、**15** 条尚无落地记录 ——
> 无一条是「部分落地」（**B20 已于 2026-09-19 补完 ⑩、转全落**，见该行）。B2x 是实施中陆续发现的：
> B22/B23（§8.35.10，responses 车道 `id` / 文本块无 `textSignature`）、
> B24（§8.35.12，`choice.usage` 回退缺失）—— **都是写夹具时撞出来的**；
> **B25/B26（§8.35.14，`rawStopReason` / partial 的 `pending` 初值）—— 是 B20 设计定稿时
> 逐行复核 pi 收尾语义撞出来的**，两条都以「**撤回既有结论**」的形式落地（§8.35.2 的两处作废）。
> 三者合为「**响应侧字段覆盖与收尾语义**」包（设计见 `docs/31 §8.35`）；
> ⚠️ **前置依赖**：B19 一旦落地，该车道就开始产 thinking 块 ⇒ B10 的跨模型重放闸
> **只挂 Anthropic 一条车道**这件事立刻可观察 ⇒ **B10 的剩余范围是本包的前置，不是后续**。

> **2026-09-19（包③ ＝ B5，§8.36）**：B5 实施闭环（`16ca4d7` / `f16436b`，记录见 §8.36.8）
> ⇒ 移入 G（+1 行）。按上段「复核计数」的口径：B 类 26 行里**已实施 12 条、尚无落地记录 14 条**。
> ⚠️ 本包的设计稿（`38f991c`）被**自己的变异探针实测证伪两处**（P2 的红集、夹具 2b 的注入点），
> 已就地更正 §8.36.5/§8.36.6 —— 这正是「**注入点必须在写下它的那一刻就跑一次**」那条教训
> （§8.36.8 末）：设计稿能钉住「要钉的命题」，钉不住「用哪根钉子」。

> **2026-09-19（包④ 设计取证，§8.37）**：**B3 的措辞被取证更正**（pi 的 `isRetrying` 零消费者、
> `RpcSessionState` 里根本没有这个字段 ⇒ 登记时的猜测），RPC 的真正缺口改述为「`get_state` 四个状态字段写死」。
> 按 §8.23.2/§8.26 的教训把审计面从「重试那几条」扩到**整个 `JsonEventMapper`**（审计单位＝
> 「规则 × 该文件全部同类调用」，不是被报告的那一处）⇒ **新登记 B27–B30**：B27（省略语义同族三处可空键 ＋
> 一处不可达）、B28（`message_update` 缺顶层 `usage`，**每个流式帧**）、B29（`toolcall_start` 缺 `id`+`toolName`）、
> B30（`session_info_changed` 无生产者）。**B12 与 B27 同根**（都是 `JSON.stringify` 的省略语义）。
> ⇒ B 类 26→**30** 行。⚠️ 这四条**全部结构上不被 L5 覆盖**（L5 产物是 `PiLoop` 帧，不是 RPC 线格式）。
> 设计见 `docs/31 §8.37`（**待审**：三个裁决点 —— ① 本包面是否扩到 `agent-core`；② C 组是否并入；③ B30 另立）。

> **2026-09-19（包④ 实施，§8.37.9）**：**B27 ①②③ 已修**（`JsonEventMapper` 四处可空键按 pi 的 `?` 主动省略；
> ④ 仍登记不修，同 B30）；**B3-块1 四个状态字段的取值已改真**（`RpcSessionState` 加 `@JsonInclude(NON_NULL)`
> 是**契约**，不是装饰 —— 变异探针 P7 恰一条红）。⚠️ **B28/B29（C 组）在实施中先被证伪、未并入**：
> 稿子指定的取值来源**结构上是空的**（`partial().usage()` 是 pi-java 方言 `StreamEvent.UsageInfo`，
> 不是 pi 必填的 `Usage`；`toolcall_start` 时刻的 `ToolUseContent` 是**空占位**，真值握在适配器手里）
> ⇒ 修在 `pi-java-ai`（5 个适配器 ＋ `StreamPartialBuilder`），**是一个新包**。
> ⚠️ 过程披露：本轮用户只回「继续」，**三个裁决点无明确裁决**，执行时按推荐项走；
> 其中裁决 B「并入」**因证伪而未发生**。⇒ 裁决 A 的真实扩面比稿子更大（还要动 `pi-java-ai`）。
> 新登记 **B31/B32/B33**（`get_state` 另三处取值偏差，本包发现、未修）⇒ B 类 30→**33** 行。

> **2026-09-19（包⑤ 实施，§8.38.9）**：**包⑤ ＝ B3-块2（TUI 重试面）已闭环**（`42d49ec`）——
> TUI 长出一条会话事件订阅通道（`SessionEventChannel`）＋ 单指示器槽与清位守卫 ＋
> 重试五个事件与逐字照 pi 的倒计时文本；**B37 就地修** ⇒ 移入 G（+1 行）。
> B3 至此**两条块都落地**（块1 见包④、块2 见本包）⇒ **B3 整行可结案**，见 G 类。
> ⚠️ **依裁决 A「只做重试」，但实施时补了一处最小扩面**：`summarization_retry_attempt_start`
> 会置 compaction 指示器，不做 `CompactionEnd` 的清位就**永久转圈** ⇒ 补上「指示器生命周期那一半」
> （`CompactionStart` 置 / `CompactionEnd` 清），第 4 步的其余三件（聊天区重建、取消消息、Esc 换绑）
> **仍未做** ⇒ **新登记 B38/B39** ⇒ B 类 33→**35** 行。
> 另一处据实记：通道落点在 `PiTuiApp` 而非稿子写的 `PiTuiLauncher`（`/session` 切会话时要跟着重接，
> 那里本就有同一形状的快照订阅）。**唯一的未覆盖**：端到端 emit（要 provider），见 §8.38.9-(8)。

> **口径提醒（用户 2026-09-19）**：**TUI 不是重要模块**，web UI 会逐步替代它 ⇒
> 本台账里一切「只在 TUI 面可见」的条目（B38/B39 与 §9.2 那批）**优先级一律靠后**；
> 与 web 客户端直接相关的是 **B28/B29**（流式帧载荷）、**B30/B34/B35**（事件无生产者）、
> **B31/B32/B33**（`get_state` 取值）与 **B19 剩余**（`reasoning_details` 结构化形状 —— 即
> `AgentEventTranslator` 那条 web 推送链上游）。

> **2026-09-19（包⑥ 起，`docs/33`）**：**流程与文档约定双变更**（用户同日提出，已采纳 ⇒ 见
> §10 维护规则 6/7）：**一包一文档**（`docs/31` 冻结为历史）＋ **命题 → 逐条验证 → 才写实施稿**。
> `docs/33` 是第一份按新流程写的文件，其 §3 列出**三条被自己取证推翻的命题** —— 其中
> **R1「B28/B29 是 web 直接吃的」是错的**（web 走 `AgentEventTranslator`，**不经过** `JsonEventMapper`）；
> **R3「给 `tool_execution_*` 补载荷」被推翻为投机**（前端根本不读那三个事件的载荷，
> `client/main.ts:302-305`）。
> 包⑥ 因此重述为**三面同根**：核心（起点身份）＋ RPC 面（B28/B29）＋ **web 面（B40，新）**。
> ⇒ B 类 35→**40** 行（新登记 B40–B44）。

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
| A12 | **web 的 WebSocket「收不到 `ready` 帧」是**一个症状家族**，全 reactor 负载下偶发红**：`PiWebServerAuthTest.acceptsConnectionWithValidToken:97`、`PiWebServerAuthTest.rejectsConnectionWithWrongToken:74`、`PiWebServerIntegrationTest.settingsRoundtrip:82`（`Timed out waiting for ready; received so far: []`） | §8.33.9-G（`docs/31:4080`）；**早在 §8.23.7 已记过**（`docs/31:1625`「三次全 reactor 红、第四次绿」、`:2593` 同）；**包⑦（`docs/34 §10.7`）扩记为三条同症状** | **未定位根因**。隔离复跑 6 次红 1 绿 5（红那次 15.05 s，绿均 ~1.3 s）⇒ **非确定性**；且 `pi-java-web/` 在包① 的 `git diff` 里零改动 ⇒ **与包① 无关**（§8.23.7 在前、包① 在后，同一现象，非新缺陷）。**需先定位根因**，未定位前不修、不加超时（那是掩盖） |
| A13 | **`SessionResumeFoldTest.resumeSettlesACrashOpenedOperationSoANewRunCanOpen` 在全 reactor 跑时偶发红**（该用例耗时 **311 s**，断言「恰 2 条 `OperationStarted`」实际 3 条） | `docs/34 §10.6`（包⑦ 实测） | **机制有据**：该用例经 `AgentSession.createWeb` 建会话 ⇒ **真实 provider（网络）**，而断言隐含「首次调用必成功」；负载下真调用变慢 ⇒ post-run **重试环**开了**续跑 operation**（多出的记录是 `OperationStarted[…intent=Run[originalPrompt=[]]…]` ＝空 prompt 续跑，`seq=83` 远在后）；311 s 即退避墙钟。隔离复跑**绿**（30 s）。⚠️ **未证**：没用「回退本包再跑全树」证明非因果；**能证的是**该用例无工具调用 ⇒ 包⑦ 新增的发射在那条路径上一次都不触发。**待裁**：修法是把它改成注入 `FauxProvider`（测试面收口，非生产改动）。<br>✅ **已修（2026-09-20，用户裁决「修」）**：给 `AgentSession` 加一条包私有测试缝 `createWeb(args, providers, toolContext)`（既有重载里**没有**「持久化仓库 ＋ 注入 provider」这个组合），`SessionResumeFoldTest.resume()` 改为注入 `FauxProvider.text("ok")`。**实测确认诊断**：改前该夹具耗时 **30 s**、改后 **1.1–1.4 s** ⇒ 那 30 秒确实是**真网络调用**；连跑 5 次绿，coding-agent 252 全绿 |
| A14 | **夹具在集成层无牙**：`ShellOutputStreamingTest.multiByteCharactersSurviveArbitraryChunkBoundaries` 逐字节喂 `Utf8ChunkStream` **单元**，**不经过 `DefaultShellExecutor`** ⇒ 把 executor 里那行换成裸 `new String(buf,0,n)` 它照样绿 | `docs/35 §10.3`（包⑧ 变异探针 **P5 实测红集 = 0**） | **变异探针测出来的**，不是猜的 ⇒ **单元级有牙、集成级无牙**。补它需要一个**字节精确**的假 shell，让 8 KiB 的切点确定性地落在多字节字符中间（输出全由 3 字节字符组成时，8192 = 3×2730+2 ⇒ 每个读边界**必然**切断）；当前假 shell 是 `.cmd`/`sh` 脚本，做字节精确输出要处理 cmd 的编码与 CRLF ⇒ **登记，不假装测过** |
| A15 | **错误路的 `AgentSettled` 没有夹具** ⇒ 它的形状无守护 | `docs/36 §10.3`（包⑨ 变异探针 **P4 实测红集 = 0**） | 把 `SessionRunner:180` 的错误路改成发**非空** `toolResults`，全树无一条红。同理**探针测出来的**，不是猜的。可补：`SessionFailurePathTest` / `AgentSessionRetryEventOrderTest` 已经在驱动失败运行 ⇒ 在那里加一条断言即可。**登记** |
| A16 | ⚠️ **夹具与生产在「消息身份」这一点上不同路** —— `FauxProvider` 自带的 `FauxChatApi` **直接实现 `ChatApi`、绕过 `AbstractChatApi`**（身份/时间戳的挂载点） | `docs/37 §4-B`（包⑩ 取证） | ⇒ **夹具永远造不出带 `Instant`/身份四元的消息**。这正是 B48 那条「带 timestamp 就抛」的 RPC 故障**能藏这么久**的结构性原因：所有 RPC 夹具都用 faux ⇒ timestamp 恒 null ⇒ 永不触发。⚠️ 补它＝改测试基础设施（牵动既有全部 faux 夹具的行为）⇒ **登记，单独一包**。**这是「测试与生产不同路」这一类问题的第一次具名登记** |

> A4 / A5 取证之后会变成**行为改动**，需你拍板 ⇒ 见 F 类。

---

## 3. B 类 —— 功能缺口（pi 有、pi-java 无）

| # | 条目 | 出处 | 修法素描 |
|---|---|---|---|
| B1 | **branch summary 无实现**（pi `branch-summarization.ts:349-353`） | `docs/31:1550` | `source:"branchSummary"` 的重试路**形状**已保留，功能本体缺 ⇒ 另立包 |
| B2 | compaction **`details` 生产者**恒 null | `docs/31:1324` | **已实施**（§8.30，`4380796`）⇒ 见 G 类；同族四处（摘要 prompt/`previousSummary`/请求参数/split turn）另立，§8.30.7 |
| B3 | TUI/RPC 对 **auto_retry / summarization_retry 的渲染**（倒计时、`isRetrying`、isIdle 含重试） | `docs/31:1552` | 并入「命令/界面面」清点。⚠️ **2026-09-19 取证更正**：这行的措辞是**登记时的猜测** —— pi 的 `isRetrying` **没有任何消费者**（`agent-session.ts:2977` 只声明，src 全仓零读点），`isIdle` 只在扩展上下文里用；pi 的 `RpcSessionState` 里也**没有**这两个字段。RPC 的真正缺口是 `get_state` 的**四个状态字段在 pi-java 被写死**（`buildState():336-354` 给 `false`/`null`/`null`/`0`）＋ `auto_retry_end` 多一个键（= **B12**）。**包④ 已实施 B3-块1（`docs/31 §8.37.9`）**：四个字段全部改真（`isCompacting` 由压缩窗口计数、`sessionFile` 由 JSONL 元数据、`sessionId`、`pendingMessageCount` = steer＋followUp）；`RpcSessionState` 补 `@JsonInclude(NON_NULL)`（`sessionFile` 可空是**契约**）。顺带发现 `get_state` **另三处**偏差 ⇒ **B31/B32/B33**（`model` 字符串 vs 对象、`sessionName` 恒有值、`messageCount` 取转录条数）。**包⑤ 已实施 B3-块2**（TUI 订阅通道 ＋ 指示器槽 ＋ 五个重试事件，`42d49ec`，见 §8.38.9）⇒ **B3 两条块全落，整行结案**，见 G 类 |
| B4 | `addedToolNames` 的 **provider 层消费者** | `docs/31:1060` | **结案为不做**（机制归属原判是错的：`addedToolNames` 来自**扩展系统**，不是 MCP；pi 侧 `extensions/wrapper.ts:17-37` → `deferred-tools.ts:8-39`）⇒ 见 C 类 C11 |
| B5 | 宿主层：`SessionRunner` 两处 `catch (Exception)` **不接 `Error`** ⇒ `statusFuture`/`entriesFuture` 永不完成、不发 `AgentEnd`/`AgentSettled`、宿主**永久挂起** | `docs/31:2685`（§8.26.5-12 的下游） | pi 的 `handleRunFailure` 把异常**压成文本**、合成 assistant 消息、promise **resolve** —— 另一处更大的差距，并入本包。**设计**（`docs/31 §8.36`，2026-09-19）：第 1 步＝活性收口（两处 `catch (Throwable)` ＋ `finally` 幂等兜底，对齐引擎侧 `PiLaneEngine.drive` 的既有纪律）；第 2 步＝`handleRunFailure` 落引擎侧（**2026-09-19 已裁：做，落引擎侧** ⇒ 落点定在 `PiLaneEngine.drive` 的 `while` **之内**，判据是 pi 的失败消息会进重试判定 `agent-session.ts:1123`），连带宿主读尾 assistant 定 `stopReason`（`print-mode.ts:139-155`）。附带登记 §8.36.7-**14**（`RunLifecycle.begin` 之后抛出 ⇒ `activeRun` 泄漏，今天无生产路径）与 **-15**（宿主层失败路的 `agent_end` 形状不一致：路 C 空数组、路 A 干脆不发）。**已实施**（§8.36.8 实施记录，2026-09-19，`16ca4d7` / `f16436b`；五条变异探针实测，其中 P2 的实测红集与设计稿不符 ⇒ 已就地更正 §8.36.6）⇒ 见 G 类 |
| B6 | content_block_start 的**初始 thinking 文本被丢弃** | `docs/31:3590`、§8.31.4 | **已实施**（§8.33 包①）⇒ 见 G 类 |
| B7 | **`redacted_thinking` 未处理** | `docs/31:3591`、§8.31.4 | **已实施**（§8.33 包①；SDK 路由 `ContentBlock.kt:549-553` 已实证）⇒ 见 G 类 |
| B8 | **空签名重放策略不可配**（pi 的 `Model.compat.allowEmptySignature`，`types.ts:713-714`） | `docs/31:3594`、§8.31.4、**§8.34** | ⚠️ **更正：行为上只有两态**，不是三态 —— `undefined` 与 `false` **完全等价**（`anthropic-messages.ts:193` 的 `?? false`），只有 `true` 不同（已实测）。启用的模型也不是「Kimi 系」而是**三处**：Fireworks 全部 anthropic-messages 模型（`generate-models.ts:1427`，无 allowlist）、Kimi Coding 全部（`:2242`/`:2253`）、Xiaomi（`:1075`，但**休眠**）。pi-java `ModelInfo` 无 `compat` 且 `ModelsJsonSchema` 会**静默吞掉**用户写的 `compat` ⇒ 需 compat 字段 + models.json schema 扩展。**被 B10 前置**。归包②，设计见 §8.34。**已实施**（§8.34.11 包②：`ModelCompat` + `ModelInfo` 第 11 组件 + `ModelsJsonSchema.CompatDef` + 投送链）⇒ 见 G 类 |
| B9 | **初始 signature 进不了 `ThinkingStart.partial`**（实现时才发现的） | `docs/31:3596`、§8.31.4 | **已实施**（§8.33 包①，与 B6 同一处改动）⇒ 见 G 类 |
| B10 | **pi 的 `transform-messages.ts` 整段缺失**（跨模型重放闸：`isSameModel = provider && api && model.id`；跨模型丢 redacted、thinking 降级 text） | §8.32.2-P2（`docs/31:3723`） | 移植 `transform-messages.ts:95-116` 的 **thinking 五分支**（`:95-98` 判据 + `:101-116` 分支）；**设计见 §8.34**；**是 B8 的前置** —— 不加此闸，B8 会让跨模型重放**比今天更错**。归包②。**已实施但只挂了一条车道**（§8.34.11 包②：新增 `TransformMessages.java`）。⚠️ **实测：`pi-java-ai/src/main` 里只有 `AnthropicMessagesApi` 调它**，而 OpenAI-Completions / Google / Mistral / Azure-Responses / PiMessages **五条车道的请求构建器都存在、都没挂**。这是**刻意的范围裁剪**（本包的夹具全在 Anthropic），**不是**「其余车道不存在」—— 后果是那五条车道上跨模型重放规则**今天仍未生效**（§8.34.10-（5）已指出闸的价值主要在非 Anthropic 车道）⇒ **另立包**。**已实施（§8.35.11，4 个提交）**：五条车道**全部挂上**（`openai-completions` / `google-generative-ai` / `mistral-conversations` / `openai-responses` / `azure-openai-responses`），`PiMessagesApi` 按裁决（§8.34.11-（1），它不是 pi 的车道）**仍不挂**；接线夹具 `LaneTransformMessagesWiringTest` 6/6 绿（先红证毕 5 红 1 绿）；顺带修了写夹具时**撞出来的** B22。⇒ 见 G 类 |
| B11 | **空 text 块不丢**（重放时会把空 `TextContent` 原样发给 Anthropic） | §8.32.2-P3（`docs/31:3724`） | 照 pi `anthropic-messages.ts:1282` 的 `if (block.text.trim().length === 0) continue;` 补闸（pi-java 无闸处在 `AnthropicMessagesApi:284-286`）；**设计见 §8.34**。归包②。**已实施**（§8.34.11：`toBlockParams` 的 `if (tc.text().trim().isEmpty()) continue;`，两条车道共用一处）⇒ 见 G 类 |
| B12 | **`auto_retry_end` 成功路多写 `"finalError":null`** | §8.32.2-P7（`docs/31:3730`） | pi 侧 `undefined` 被 `JSON.stringify` 省略；pi-java `JsonEventMapper.java:98` 无条件 `put`。同文件 `:107-113` 已有「null ⇒ 省略」先例 ⇒ 照抄。归包④。⚠️ **意图其实早写在接口上**：`RetryObserver:31-34` 的 javadoc 原文就有「`finalError=null` ≙ pi 的 undefined **透传**」，只是没在序列化边界实现 ⇒ 后果落在**每次重试成功**这条路上。**设计见 `docs/31 §8.37`（待审）** |
| B13 | **重放路径产不出 `redacted_thinking` 线格** —— pi-java 全仓没有代码路径能发出该块 | §8.34.2-2（`docs/31:4201`） | `redacted()` 在 `pi-java-ai` 生产代码**零读点** ⇒ redacted 块落进 `AnthropicMessagesApi:323-327` 的有签名分支，被当作**带签名的 thinking 块**发出、签名位放的是**加密载荷**。pi 的对照是 `anthropic-messages.ts:1289-1295`。归包②。**已实施**（§8.34.11：`appendThinkingBlock` 的 redacted 分支 → `ContentBlockParam.ofRedactedThinking`，载荷进 `data`）⇒ 见 G 类 |
| B14 | **`transform-messages.ts` 的其余四条变换全部缺失**（一条聚合行） | §8.34.3（`docs/31:4242`） | ① 图片降级为占位文本（`transform-messages.ts:35-57`，按 `model.input` 判定）；② 跨模型剥离 toolCall 的 `thoughtSignature`（`:131-134`）；③ 跨模型归一 toolCall id（`:136-142`）；④ **孤儿 toolCall 合成 `toolResult`**（`:158-220`「No result provided」）＋ 跳过 `error`/`aborted` 助手消息（`:194-197`）。④ 是**真功能**、其余三条是清理 ⇒ 不塞进包②（会让 200 行变 800 行），另立 |
| B15 | **扩展开启（extended thinking）在生产上不可达** —— 目录的 `reasoning` 标记永远传不到请求 | §8.34.4-决策 5 | 链路逐段实测：`models.json` 的 `reasoning:true` 只落成 `ModelCapability.THINKING`（`ModelsJsonConfig:191-193`），`thinkingLevelMap` 硬写 `empty()`（`:208`）；`HarnessConfig.thinkingLevelMap` 默认 `empty()`（`:159`）且 `Builder.thinkingLevelMap` **零主源调用者**；`forLevel` 在空 map 上**恒返回 `ThinkingConfig.OFF`**（`ThinkingLevelMap:26-34`）⇒ `DefaultProviders:111-113` 门恒假 ⇒ `extra` 永无 `thinking.budgetTokens` ⇒ `AnthropicMessagesApi:269-276` 永不发 `thinking`。**`ThinkingLevelMap.of(` 亦只有测试调用者** ⇒ 非空 map 在生产上**不可构造**。根因＝「**目录元数据 → 请求路径**」这条通道**整体缺失**（B8 的 `compat` 是同一断链的**第二个**受害者）⇒ 两者应合为**一次**投送修复。**它改变「发什么请求」**（比包② 重）⇒ 须**自己一包**。⚠️ `StreamSimple:44` 虽手持 `ModelInfo`，但**生产上是死的**（3 个调用点全在 `StreamSimpleTest`）—— 别把它当接缝。**⚠️ 投送链那半边已由包② 修好（§8.34.11）**：`StreamRequest` 现带整个 `ModelInfo`、`DefaultProviders.streamBlocking` 已投真目录元数据 ⇒ **本行只剩「`thinkingLevelMap` 生产上不可构造」这半边**，难度显著下降 |
| B16 | **`sanitizeSurrogates` 全仓无对应物** —— 出站文本未做孤对代理清理 | §8.34.11 | pi 在自己的**每个** API 适配器里都用它包裹出站 text/thinking（全仓 **54 处**调用），pi-java 自己的模块**零处**（`.agents/` 里 vendored tamboui 的那几处是宽度计算，无关）。孤对代理字符会让请求体 JSON 非法 ⇒ provider 400。**跨车道**（不是 Anthropic 独有）⇒ 须先设计「在哪一层做一次」而不是逐适配器抄 |
| B17 | **Anthropic 车道静默丢弃图片块** | §8.34.11 | `AnthropicMessagesApi.toBlockParams` 只处理 Text/Thinking/ToolUse ⇒ `ImageContent`/`UrlImageContent`/`DiffContent` **无声消失**。pi 在 user 车道把它们映射成 `{type:"image",source:{type:"base64",...}}`（`anthropic-messages.ts:1250-1260`）。**不是「图片进不了 Message」**：Google / OpenAI-Responses / PiMessages 三条车道**都**映射了图片 ⇒ 是 Anthropic 车道独缺 |
| B18 | **`PiMessagesApi` 对任何 thinking 块丢签名与 `redacted`** | §8.34.11-（1） | `PiMessagesApi:225-226` 一律送 `{type:"thinking","thinking":text}`。**包② 刻意没给它挂闸** —— 它是 pi-java **自己的** wire 形状（pi 的 6 个请求构建器里无对应物），在一条 pi 没有的车道上按 pi 的闸改行为＝**发明**行为。故单独登记，让它自己的规则被独立设计 |
| B19 | **`openai-completions` 车道整段丢弃响应侧 reasoning（收），重放侧又把字段名写死成 `deepseek`（发）** —— **本次事故根因** | §8.35.1（`docs/31:4604`） | **收**：`OpenAICompletionsApi:96-105` 只读 `delta.content()`，`delta._additionalProperties()` **从未被查**（SDK 侧可读 —— `ChatCompletionChunk$Choice$Delta` 有该方法，已 `javap` 实证）；pi 依次试 `reasoning_content`/`reasoning`/`reasoning_text` 并**用命中的字段名当 `thinkingSignature`**（`openai-completions.ts:597-620`、`:615-618`）⇒ 重放时**自描述**。**发**：`:235` 只给 `"deepseek".equalsIgnoreCase(provider)` 发 `reasoning_content`，pi 则按**签名**回填（`:1310-1318`，**无 provider 门**）。事故形态：纯推理回复 ⇒ 可见内容为空 ⇒ 前端静默停住；带答案回复 ⇒ 计费与可见严重不符（复现 P2：**127 计费 / 2 可见**）。⚠️ 同族但**不在本行**：`reasoning_details`（OpenRouter/llama.cpp 的结构化形状，`:661-671`/`:342`/`:1283`）—— 本行只覆盖三个**纯文本**字段名。**已实施（§8.35.13，4 个提交）**：**收** `b916d29`（探三个线格字段、命中的名字写成 signature、收尾按建块序发）、**发** `478fe91`（签名自描述回放 + deepseek 家族空串回填 + 落线跳过规则 + `baseUrl` 请求期探测；旧守卫多出的 `|| !reasoning.isEmpty()` 正是「只带 thinking 的消息被发出去」的来源）、models.json 暴露 `7369ae6`；顺带把两个存量用例按真规则改写（`nonDeepseekThinkingIsNotRoundTripped` → `unsignedThinkingIsNotRoundTripped` —— 决定权在签名、不在 provider 名）。发侧 11/11（此前 8 红），三条「两侧同绿」对照各做变异探针恰一条红 ⇒ 见 G 类 |
| B20 | **stop reason 映射跨车道缺失 —— 四条车道里三条「不读」或「原样透传」** | §8.35.2（`docs/31:4657`） | 逐车道审计（全部实测）：① **`AnthropicMessagesApi:190-193`** 处理 `message_delta` 时**只取 `usage`**，`event.delta.stop_reason` **全文件零读取**（pi `anthropic-messages.ts:743-745`），而 `:94` 硬写 `toolCallSeen[0] ? "tool_use" : "end_turn"` ⇒ `max_tokens`/`refusal`/未知值**全部丢失**；② **`OpenAICompletionsApi:132`** 硬写 `toolCall.started() ? "tool_use" : "stop"`（pi `:571-577`）；③ **`GoogleGenerativeAiApi:149-156`** 把枚举 `toString().toLowerCase()` 原样透传（`MAX_TOKENS`→`max_tokens`、`SAFETY`→`safety`，pi `google-shared.ts:379-411` 分别是 `length`/`error`）；④ **`MistralConversationsApi:151-154`** 半映射（无 `error` 兜底、无 `model_length`、无 `errorMessage`，pi `mistral-conversations.ts:926-941`）。只有 `ResponsesStreamProcessor:190-197` ~~已对齐 pi~~（⚠️ **该判定已作废**，见 B25/B26 段落与 §8.35.14 第四节：复核出 α/β/γ/δ/ε **五处**差距）。**功能后果（主车道 Anthropic）**：`length` **永不可达** ⇒ `PiLoopRunner:108` 的「length 截断 ⇒ 本回合**全部**工具调用判失败」（pi `agent-loop.ts:206-208`）**永不生效**（截断的工具参数会被**执行**），且 `ContextOverflow:118`/`:138`、`CompactionExecutor:310`、`PiLaneSink:367`、`LlmSummaryGenerator:160` 五处 `length` 分支同时是死代码。⚠️ `"end_turn"` 是 pi-java **自有**取值（`AssistantMessage:32`/`StreamEvent:199` 都列它，而 `LaneState:261` 的词汇表**不列** ⇒ 自家也不一致），pi 该处是 `"stop"` ⇒ **转录载荷分歧**；改不改口径＝~~**裁决点 D2**（§8.35.8）~~ **已裁：改成 `"stop"`**。<br>**2026-09-19 设计定稿（§8.35.14，待审）**：D1=**直接照 pi 严格版**（`supportsFinishReason` 默认 true，缺 `finish_reason` ⇒ 抛；原「P0 只读探针」首提交**被否决**，改为提交 ④ 的**真实车道门**）、D2=改成 `"stop"`、D3=Google/Mistral **并入**但**分车道提交**、D4=B10 先行（已执行）。**同批撤回两处旧结论**：① §8.35.2 末行「Responses ✅ 已对齐」**作废**（α/β/γ/δ/ε 五处）；② 「`rawStopReason` 零消费者 ⇒ 不移植」的**结论作废**（读点在**生产者层** Google 车道 2 处，是 `"Provider stopped with: X"` 文案的唯一来源）⇒ 改列 **D5**（建议全量移植，另立 B25）。另新发现：pi 五条车道的 partial 初值 `stopReason:"pending"` ⇒ **每一条中间帧**载荷都不同（B26）。实施计划 ⑩ 个提交（① `supportsFinishReason` → ② Anthropic 映射收尾 → ③ `end_turn`→`stop` → ④ completions 严格收尾 → ⑤ Google → ⑥ Mistral → ⑦ Responses α–ε → ⑧ 跨层回归门 → ⑨⑩ 依 D5）<br>**2026-09-19 实施进度（§8.35.15 实施记录）**：**③–⑩ 全部落地**（`d2254a2`/`811aa31`/`7f437b4`/`9f4c4bb`/`775f3a6`/`1007246`/`4619b78`/`32784aa`）；⑩ 的两处待裁**已裁取建议项并落地** —— (a) 两个中间读点**照报 `"pending"`**（零折算），(b) web wire **补上** `rawStopReason`（`a8c0a62`）。⑩ 的**必需**配套＝`PiLoopRunner.markAborted` 的收尾判据换成字面量 `"pending"`（否则 A8 复活，论证见 `docs/31 §8.35.15 八-8.3`）。逐条实测（含 6/7/5/7 条红灯的 actual、两处「今天应当红」的预测更正、五次 SDK 探针读法、五处 `default` 互不相同清单、⑩ 的三条变异探针、未覆盖登记）见 `docs/31 §8.35.15`。**D1 真实车道闸已过**（teamorouter 真实请求拿到 `done(stop)` ⇒ 线格里确有 `finish_reason`）；**D2 的真实车道检查本环境无法执行**（relay 无 `/v1/messages` 路由，404 `route_not_found`，未绕过）。⚠️ 一处与设计稿的偏差：⑧ 的模块标签据实记 `test(agent-core)`（`PiLoopRunner` 在 agent-core，`ai` 依赖不到它） |
| B21 | **请求侧无 `compat.thinkingFormat`**（能力缺口，**非本次事故因素**） | §8.35.3（`docs/31:4707`） | pi 按 provider 发 **10 种** thinking 开关形状（`openai-completions.ts:866`/`:879`/`:887`/`:892`/`:897`/`:914`/`:924`/`:934`/`:939`/`:948`：zai / qwen / qwen-chat-template / chat-template / baseten / deepseek / openrouter / ant-ling / together / string-thinking —— 其中 `detectCompat:1644-1654` 只会**产出 6 种**，其余靠用户显式写 `model.compat`）—— 而 pi-java 的请求侧只有 `DefaultProviders:112-116` 的 `thinking.budgetTokens`。⚠️ **如实标注**：`api.teamorouter.cn` 不匹配 pi **任何**探测模式（`detectCompat:1581-1600` 逐条比对：z.ai / together / moonshot / openrouter / cloudflare / nvidia / ant-ling / deepseek 全不匹配）⇒ pi 在该 relay 上**也不发**任何 thinking 配置 ⇒ **与本次事故无关**；只在改用 zai/deepseek/qwen 等**原生** provider 时才可观察。**不并入本包**（它管「发什么请求」，与响应侧字段覆盖无关），须自己一包 |

| B22 | **`openai-responses` 车道回放助手文本消息即抛 —— 缺 `id`** | §8.35.10（`docs/31`） | `ResponsesMessageConverter:186-194` 构造 `ResponseOutputMessage` 时**不设 `id`**，而 SDK 标它必填 ⇒ `IllegalStateException: `+`id` is required, but was not set`（`Check.kt:12` ← `ResponseOutputMessage.kt:348`）。pi **有**回填（`openai-responses-shared.ts:237-242`）：先试 `parseTextSignature(textBlock.textSignature)?.id`，取不到则 `msg_pi_${msgIndex}` / `msg_pi_${msgIndex}_${textBlockIndex}`，>64 字符压成 `msg_${shortHash}`。**可达性**：今天 CLI **不可达**（`DefaultProviders:147` 恒传 `Map.of()`，而协议覆盖读 `extra["protocol"]`；`ModelsJsonProvider:61-69` 只认完两条车道；`AZURE_OPENAI_RESPONSES` 全树只有枚举本身 ⇒ Azure 车道无 provider 创建）⇒ 属**潜在**缺陷。**仍必修**：它是本包该车道夹具的前置（不修则夹具红在「请求没发出」，闸挂没挂**测不到**），且是纯移植缺口。**写夹具时发现**，不在原审计范围内 |
| B23 | **文本块无 `textSignature`（`TextContent(String text)` 只有 1 个组件）** | §8.35.10（`docs/31`） | pi 的文本块带回执签名 `encodeTextSignatureV1(item.id, item.phase)`（`openai-responses-shared.ts:701`，读侧 `:55`/`:228`）⇒ 回放时能取回**原** `msg_xxx` 与 `phase`。pi-java 无此组件 ⇒ 即便修了 B22，回填也只能是 `msg_pi_N` 合成值。属「文本块载荷」缺口（与 B19 的 thinking 载荷同族、**另一条**），须自己一包 |
| B24 | **`openai-completions` 车道不读 `choice.usage` 回退** —— 该形状的 relay 上计费恒为 0 | §8.35.12（`docs/31`） | pi 在 `chunk.usage` 缺席时再读 `choice.usage`（`openai-completions.ts:565-568`，注释点名 **Moonshot** 把 usage 放在 choice 里）；pi-java `OpenAICompletionsApi:124-127` 只看 `chunk.usage()`。**后果不止「少显示一个数」**：`~/.pi-java` 的用量统计、上下文阈值判定、`ContextUsageEstimator` 都吃 usage ⇒ 那条 relay 上压缩时机会**晚于 pi**。**写 B19 夹具读 pi 该函数时发现**，不在原审计范围；**本包不改**（与 reasoning/stopReason 不同层） |
| B25 | **助手消息缺 `rawStopReason`** —— 且它是 Google 车道错误文案的**唯一来源** | §8.35.14 第五节（`docs/31`） | `Message.java:55-59` 原裁定「对齐面零消费者 ⇒ 不移植」**被证伪**：pi 侧**读点 2 处**，都在**生产者层**（`google-generative-ai.ts:272-273`、`google-vertex.ts:289-290`）用它拼 `` `Provider stopped with: ${raw}` ``；**写点 10 处**（anthropic:744 / bedrock:292 / google:217 / google-vertex:234 / mistral:614 / completions:572 / responses-shared:588、:747），声明 `types.ts:443`。原文措辞「对齐面（`packages/agent/src`）无消费者」是对的，**结论错**——pi-java 也要实现生产者层。**全车道、每一条消息**都写它 ⇒ 与 D2 同等级（「每一份转录都差一个键」）。⇒ **裁决点 D5**：建议 (a) 全量移植（消息第 10 组件 + 五条车道写点 + `MessageJsonCodec` + 全投影 + 夹具；主源码 20 个构造点）。**已裁：(a) 全量移植** ⇒ **已落地**为 B20 提交 ⑨（`4619b78`；实测与 8 条变异探针见 `docs/31 §8.35.15 七`）。⚠️ 一处曾遗留**待裁**：`pi-java-web` 的 wire 是否投影该键 —— **2026-09-19 已裁：补上**，落地 `a8c0a62`（`WebWireJson.messageNode` 逐键写出该键，缺席规则同 `toolResult` 支：null ⇒ 键省略），含一条缺席哨兵（旧形状消息不得凭空长出该键）。**本行至此无遗留** |
| B26 | **partial 的 `stopReason` 初值应为 `"pending"`** —— 中间帧载荷全线不同 | §8.35.14 第六节（`docs/31`） | pi 五条车道的累加器都从 `stopReason: "pending"` 起、只在终局事件改写（`anthropic-messages.ts:526`/`openai-completions.ts:333`/`google-generative-ai.ts:75`/`mistral-conversations.ts:222`/`openai-responses.ts:139`；pi 侧实际共 **10** 处，另含 azure/bedrock/vertex/codex/pi-messages，pi-java 只实现上述 5 条车道）⇒ 流进行中**每一个** `message_update` 与 `message_start` 的载荷都带 `"pending"`；pi-java 的 `StreamPartialBuilder.stopReason` 初值是 `null`（键主动省略）。**S 系列差分测不到**：剧本走 `faux`，它从一开始就是 `"stop"`（`faux.ts:93`）⇒ **结构上覆盖不到**（§8.33「桩盖不住」同一形态）。落地：builder 初值改 `"pending"`（收尾检查随之成为字面量），⚠️ **外溢** `PiMessagesApi`（不是 pi 的车道，§8.34.11-（1））—— 可接受但要写明。⇒ 与 D5 同类，建议**一并做**。**已裁：一并做** ⇒ 落地为 B20 提交 ⑩。**2026-09-19 设计修订（`docs/31 §8.35.15 八`；写下时标「待裁决」，随后**已裁取建议项**）**：原稿「初值 + 外溢说明」**不够** —— ① 新发现 pi 的**存盘**类型显式排除 `"pending"`（`harness/session/types.ts:13` ＋ conformance `session-repo.ts:264` 拒绝 append pending），即「pending ＝ 流的中间态、落定前必须已被改写」；② `PiLoopRunner:237` 每个 update 都 `fromPartial(partial)` 重建终局消息 ⇒ 中间快照的 `"pending"` 会流进终局消息（真实车道形状，非桩）；③ ⇒ **必需**转换：`markAborted:299` 的 `message.stopReason() != null`（＝「provider 有没有给终局判定」）在 ⑩ 后**恒真**，会让「进场前已中止、provider 照样吐帧」那一支原样留下 `"pending"`，而 `ContextEntries.NON_PROJECTED_STOP_REASONS` 不含它 ⇒ **打断的响应被投影进后续上下文（A8 复活）**，必须改成 `!"pending".equals(...)`；④ 另有两个中间读点取值由 `null` 变 `"pending"`（`HarnessUtils.deriveNewestOwn`→`LaneRecord.stopReason`、`RunSpanFactory.closeRunSpan`→跨度属性），二者是 pi 无对应物的旁路审计面 ⇒ 判据沉默、**待裁**；⑤ 经逐条复核**不受影响**的收尾检查已列表给理由（安全网走 `AssistantMessage.empty()`，仍为 `null`）。外溢 `PiMessagesApi` 的口径照原样写进提交信息。<br>**2026-09-19 已落地（`32784aa`，B20 提交 ⑩）**：初值 `"pending"` ＋ `markAborted` 判据换字面量（**保留 `null` 分支**：非流式构造的消息与旧转录同属「没观测到终局」）＋ 新建 `PendingStopReasonSettlementTest`（1 条，走 `abortedAtEntry` ＋ 真实 builder 造帧 —— ⑩ 唯一能伤到的路径，`MidStreamAbortTest` 与 L5 `FauxProvider` 两条夹具**结构性覆盖不到**）。两处待裁**已裁取建议项**：④ 的两个中间读点**照报 `"pending"`**（零折算）；⑤ 的复核结论照旧。三条变异探针（`expected: "pending" but was: null` / `expected: "aborted" but was: "pending"` / `Expecting value to be true but was false`）见 `docs/31 §8.35.15 八-8.8`。**本行至此无遗留** |
| B27 | **`JsonEventMapper` 的「`null` ⇒ 省略」纪律没贯彻 —— 同族三处可空键被无条件写出** | §8.37.3-B 组（`docs/31`） | pi 的规则（`json-event.ts:48-51` 原样透传 ＋ `JSON.stringify` = **省略 `undefined`、保留 `null`**）在 pi-java 只贯彻了一半（`:107-113` 的 `reason` 做了，其余没做）。逐处：① `compaction_end.result`（`:82`；pi `agent-session.ts:2100`/`:2201`/`:2311`/`:2366` 显式 `undefined`）—— ✅ 可达，`CompactionExecutor:98/:210/:258` ＋ `PostRunCompactionCheck:168` 四路传 `null`；② `compaction_end.errorMessage`（`:85`）—— ✅ **成功路也传 `null`**（`CompactionExecutor:289`）⇒ **每次成功压缩**都多一键；③ `bash_execution_update.id`（`:120`；pi `:3027` `id: options?.id`）—— ✅ `bash` 命令的 `id` 可选，缺省即 `null`。第 ④ 处 `session_info_changed.name`（`:66`，pi `:159` 是 `string \| undefined`）**今天不可达**（无生产者，见 B30）⇒ 一并登记、不修。**已核为非缺陷**：`auto_retry_start.errorMessage`/`summarization_retry_scheduled.errorMessage` 两侧都有兜底（`PostRunRetry:76`、`LlmSummaryGenerator:175`）⇒ 恒非空。<br>**2026-09-19 已修 ①②③（`docs/31 §8.37.9`）**：三处都改成「`null` ⇒ 不写键」，每条配一条**反向**断言（有值时必须在 —— 否则「一律删键」也能让夹具变绿）。变异探针 P6 把三处改回无条件写 ⇒ **恰 3 条红**（一一对应）。④（`session_info_changed.name`）按裁决 C **不动**，同 B30。⚠️ 另更正一处设计稿：D 组「写不出红灯夹具」**部分错** —— 直接 `toWire` 一个 `name=null` 的事件**是**写得出的，站得住的只有「行为不可达」那条 |
| B28 | **`message_update` 线格式缺顶层 `usage`** —— **每一个流式帧**都少一个对象 | §8.37.3-C1（`docs/31`） | ✅ **包⑥ 已修（2026-09-19）**：`JsonEventMapper` 恒写顶层 `usage`，缺 `UsageInfo` 时兜 `Usage.of(0,0)`、**不省键**（pi 的 usage 永不为 undefined）。见 `docs/33 §10`。原描述：pi `json-event.ts:11-15` 把它写成 `JsonMessageUpdateEvent` 的必填分量、`:56-60` 装配 `usage: event.message.usage`；pi 的注释（`:41-45`）明说剥 `partial` 时**故意**保留 usage / toolCall id / toolName，理由是「它们**尺寸恒定**」。pi-java `JsonEventMapper:42-43` 只写 `type` + `assistantMessageEvent` ⇒ 客户端拿不到 token 用量。⚠️ **L5 结构上测不到**：产物是 `PiLoop` 帧（`conformance/{pi,java}-out/*.jsonl`），不是 RPC 线格式。⚠️ **取值口径**：pi 取 `event.message.usage`，而 pi-java 的 `MessageUpdate` 只带 `StreamEvent`（`AgentSessionEvent:21`）⇒ 只能走 `partial().usage()`，实施时须用夹具钉死。<br>**2026-09-19 实施中被证伪 ⇒ 未并入包④，拆成新包**：稿子说的取值口径 `partial().usage()` **不成立** —— 它是 pi-java 方言 `StreamEvent.UsageInfo`（可空，且自带自引用 `partial` 字段，仅靠 `StreamEventMixin` 剥除），**不是** pi 必填的 `com.pijava.ai.Usage`（`packages/ai/src/types.ts:383-404`）。原样序列化会发出 `{type:"usage", inputTokens, outputTokens, usage:{…}}` 这种**双份**形状。正确的值要走 `Message.AssistantMessage` 私有的 `usageOf(UsageInfo)` 归一化，**外加**一个「无 `UsageInfo` 时写不写键」的裁决（pi 恒写；pi-java 自己的 javadoc 说「键省略」）。⇒ 修在 `pi-java-ai`，**是一个新包**（须自己一包，见 B29 同因） |
| B29 | **`toolcall_start` 线格式缺 `id` + `toolName`** —— 客户端在工具**开始**时拿不到工具名 | §8.37.3-C2（`docs/31`） | pi `json-event.ts:23-30` 在重建帧时从 `event.partial.content[contentIndex]` 取这两个值补上，且 `type !== "toolCall"` 时**抛错**；pi-java `StreamEvent.ToolCallStart` 只有 `(contentIndex, partial)`（`StreamEvent:146`），而 `partial` 在线上被剥 ⇒ 信息整段丢失（id/name 要到 `ToolCallEnd` 才出现）。<br>**2026-09-19 实施中被证伪 ⇒ 未并入包④，拆成新包**：稿子说的取值来源「从 `partial.content().get(contentIndex)` 取」**结构上是空的** —— `StreamPartialBuilder.emitToolCallStart()` 插的是**空占位** `ToolUseContent("", "", Map.of())`；真值握在适配器手里（`AnthropicMessagesApi.java:191-193` 暂存 `pendingToolName`/`pendingToolId`，到 `emitToolCallDelta`/`emitToolCallEnd` 才落到块上）。**与 B28 同根：pi-java 的流式 partial 比 pi 的 `AssistantMessage` 更稀疏**（usage 可空；start 时刻的工具调用是占位）⇒ 两条一起修在 `pi-java-ai`（5 个适配器 ＋ `StreamPartialBuilder`）。<br>✅ **包⑥ 已修（2026-09-19）**：`StreamPartialBuilder.emitToolCallStart(id,name)` 起点即带身份 ＋ `JsonEventMapper` 起点补 `id`/`toolName`（不是 `ToolUseContent` 就抛）。见 `docs/33 §10` |
| B30 | **`session_info_changed` 在 pi-java 没有生产者** —— RPC `set_session_name` 改了名却不发事件 | §8.37.3-D 组（`docs/31`） | `JsonEventMapper:64-67` 有映射，但**全仓 grep 只命中映射器自身**。pi `agent-session.ts:3114-3119` 的 `setSessionName` 里发（并转发给扩展运行器）；pi-java `RpcDispatcher:168-171` 只调 `session.setSessionName(name)` 就回响应 ⇒ RPC 客户端**永远不知道名字变了**。⇒ **登记不修、另立**：它牵出 `set_session_name` 的事件面，与 B1（branch summary）同属「会话信息面」。**2026-09-19 已裁（裁决 C）：登记不修、另立**；包④**未动**它。⚠️ 设计稿里「D 组写不出红灯夹具」的说法**部分错**（见 B27 行末），只有「行为不可达」那条论证成立。**设计见 `docs/31 §8.37.8` 裁决点 C** |
| B31 | **`get_state.model` 是字符串，pi 是对象** | §8.37.9（`docs/31`） | pi 的 `model?: Model<any>`（`rpc-types.ts:97`）是**整个模型对象**；pi-java 发 `"provider/id"` **字符串**（`RpcDispatcher.buildState`）。当年是**刻意**选字符串（`RpcSessionState` 的 javadoc 明写），但判据是**行为** ⇒ 是缺口。**包④ 发现、未修**（不在 B3-块1 的四个字段内）。改它要连带裁决「发哪些字段」（pi 的 `Model` 有 id/name/provider/api/baseUrl/contextWindow/maxTokens/cost/compat/thinking…） |
| B32 | **`get_state.sessionName` 恒有值（默认 `"session"`），pi 是可选** | §8.37.9（`docs/31`） | pi `sessionName?: string`（`rpc-types.ts:105`）在**发生过 `session_info` 之前缺键**；pi-java 的 `AgentSession` 构造时 `name = args.name() ?? "session"`（`:389`）⇒ 永远有值 ⇒ 客户端分不出「用户起过名」与「默认名」。⚠️ 与 B30 同族（都属 `session_info` 面）。**包④ 发现、未修** |
| B33 | **`get_state.messageCount` 取的是转录条数，不是工作副本条数** | §8.37.9（`docs/31`） | pi 是 `session.messages.length`（`rpc-mode.ts:462`，**工作副本**）；pi-java 是 `session.entryCount()`（**transcript 条数**，含 `Entry.Compaction`/`ModelChange`/`ThinkingLevelChange` 等非消息条目）⇒ **压缩后两者必然分叉**，`/compact` 之后客户端看到的计数与 pi 不同。**包④ 发现、未修** |
| B34 | **`queue_update` 在 pi-java 没有生产者** —— 队列变化永不通知任何宿主 | §8.38.3-A（`docs/31`） | pi 在 `agent-session.ts:594` 发射（类型声明 `:153`），pi 的 TUI 在 `interactive-mode.ts:3197` 处理。pi-java 只有 `AgentSessionEvent.java:36` 的定义 ＋ `JsonEventMapper:67` 的映射，**全仓零 emit**（`grep -rn "emitSessionEvent\|eventHub.emit"` 命中的 9 个变体里没有它）。⇒ **登记不修、另立**（§8.38.7 裁决点 E：发射侧在 `agent-core`/`coding-agent`，不是 `tui` 的面）。**包⑤ 未动**（`docs/31 §8.38.9-(10)`） |
| B35 | **`thinking_level_changed` 同样没有生产者** | §8.38.3-B（`docs/31`） | pi 在 `agent-session.ts:1830` 发射、TUI 在 `:3215` 处理；pi-java 只有定义（`AgentSessionEvent.java:43`）＋ 映射（`JsonEventMapper:76`）。⇒ `/thinking` 改了级别，**任何宿主都收不到**（TUI 状态栏与 RPC 客户端都断在这条上）。**登记不修、另立**（同 B34）。**包⑤ 未动**（同上） |
| B36 | **pi-java 没有 `abortCompaction`** —— TUI 无法取消压缩 | §8.38.3-E（`docs/31`） | pi 的压缩窗口把 Esc 换成 `() => this.session.abortCompaction()`（`interactive-mode.ts:3391-3394`），重试窗口换成 `abortRetry()`（`:3453-3456`）；pi-java **有** `AgentSession.abortRetry()`（`:923`），但 `abortCompaction` **全仓零命中**（`grep -rn "abortCompaction" --include=*.java`）。根因与包④ 的 `isCompacting` 同：pi-java 的压缩借 `lane.abortSignal()`（`CompactionExecutor:256`）、**没有 per-operation 取消句柄**。**登记不修、另立** ⇒ 包⑤ 依裁决 A 未动（`docs/31 §8.38.9-(6)`） |
| B37 | **`ChatScreen.lastError` 常驻状态栏，pi 没有这个构造** | §8.38.3-F（`docs/31`） | pi-java `ChatScreen.statusBar():215-222` 的优先级是 `lastError != null` ⇒ 红字整行、盖过 snapshot；而 `lastError`（`:38`）**只写不清**（唯一赋值 `:123`，`resetRunTracking():140-142` 不碰，**全仓零复位点**）⇒ 一次流错误之后状态栏**永久红着**直到进程退出。pi 的错误**只**进聊天区（`showError:4273-4277`），pi 的 footer **没有错误态**（`components/footer.ts` 里只有 context 百分比会被染红，`:155`）。且 pi-java **同时**在聊天区也追加一条（`ChatScreen:124`）⇒ **双报**，多出来的那条永不消失。**§8.38.7 裁决点 C**。**2026-09-19 已修（§8.38.9，`42d49ec`）**：`lastError` 字段与状态栏那条红字分支删除，错误只进聊天区；`onSessionEvent` 的 `showError` 按 pi 拼 `Error: ` 前缀。⇒ 见 G 类 |
| B38 | **pi 在 `compaction_end` **重建整个聊天区**（＋取消/失败两条消息）** —— pi-java 的 TUI 对压缩一无所知 | §8.38.3-D（`docs/31`） | pi `interactive-mode.ts:3400-3449`：`chatContainer.clear()` → `renderSessionEntries(entries.slice(1))` → 追加压缩摘要消息（`createCompactionSummaryMessage`）＋ 有 `usage` 时加开销提示；`aborted` ⇒ manual `showError("Compaction cancelled")` / 否则 `showStatus("Auto-compaction cancelled")`；`errorMessage != null` ⇒ manual `showError` / 否则红字一行。**这不是「少显示一行」而是整表替换**。包⑤ **只做了指示器生命周期那一半**（`CompactionStart` 置 / `CompactionEnd` 清 —— 不做会留下永久转圈，`docs/31 §8.38.9-(6)`）；重建要有 pi-java 侧的「会话上下文条目」对应物（pi 走 `sessionManager.buildContextEntries()` ＋ `renderSessionEntries`），**今天不存在** ⇒ 另立 |
| B39 | **压缩窗口的 Esc 换绑**（B36 的宿主面） | §8.38.3-E（`docs/31`） | pi 在压缩窗口把 Esc 换成 `abortCompaction()`；pi-java 的 Esc 恒走 `PiTuiApp:407 case INTERRUPT -> mode.abort()`。⚠️ **重试窗口不需要换绑**：pi-java 的 `AgentSession.abort()` **第一步就是 `abortRetry()`**（`:602-605`，≙ pi `:1641-1643` 的四连），所以 `(esc to cancel)` 这句提示在重试窗口里是**真的**（§8.38.9-(4)）。缺的只有压缩那半边，且它被 B36 挡着（无 `abortCompaction` 可绑） |
| B40 | **web 在 `ToolCall*` 上不推 `message_update`** —— 工具调用期间前端拿不到工具卡 | `docs/33 §2.2 P17`（`AgentEventTranslator:65-88` 只对 Text/ThinkingDelta 推） | 前端渲染的是**累积消息**（`client/main.ts:272-276`），且渲染侧**已就绪**（`pi-web-ui/dist/components/Messages.js:74-88` 会渲染 `chunk.type === "toolCall"`，名字取 `this.tool?.name \|\| this.toolCall.name`）⇒ 缺的**只是推送**。⚠️ **不是**「前端拿不到工具名/结果」—— 那两条前端有取法（name 从 message 块、结果从 `turn_end.toolResults`）。✅ **包⑥ 已修**（`docs/33 §10`）—— 推送**缀在** `tool_execution_*` 之后（稿子写反了，见 §10.4-D1） |
| B41 | **终局助手消息的 `usage` 可空**（pi 必填 `Usage`，流起点即零值对象） | `docs/33 §2.1 P1/P2`、`§2.2 P14` | pi `AssistantMessage.usage: Usage` **必填**（`types.ts:439`），流起点初始化为全零（`anthropic-messages.ts:518-525`）；pi-java 的 `usageOf` 对 `null` 返回 `null`（`Message.java:131-142`）＋ `MessageMixin` 的 `NON_NULL` ⇒ **整键消失**。**登记不修**（`docs/33 §5 N3`：它与「mapper 每帧兜零」风险档不同，会动现有终局投影）。✅ **包⑨ 已修（2026-09-20，`docs/36 §10`）**：改在 `usageOf`（`info == null` ⇒ `Usage.of(0,0)`），两线 ＋ **落盘**一并对齐 pi。⚠️ 实施中夹具抓到一条**真实回归**：`LlmSummaryGenerator:270` 原以 `projected.usage() == null` 当**信号**（「流里没报用量 ⇒ 从收集值补」），兜零把这个信号消灭 ⇒ 摘要跨度的 token 计数静默归零；judgement 已挪到 `partial.usage() == null`。⚠️ pi-java 侧影响面比 `docs/33` 记的**宽**：除 RPC 的 `MessageMixin`，**web 线在 `WebWireJson:75` 另有一份同样的 null 判断** |
| B42 | **`turn_end` 不带 `toolResults`** | `docs/33 §4-A` | 前端 `client/main.ts:286-296` 按 `toolCallId` 从 `turn_end.toolResults` 收工具结果；pi-java 把 `AgentSettled` 翻成空 `{type:"turn_end"}`（`AgentEventTranslator:51-54`）。**今天不可观测**（`agent_end.messages` 含 toolResult 消息，整表替换兜住）⇒ **登记不修**。✅ **包⑨ 已修（2026-09-20，`docs/36 §10`）**：`AgentSettled` 加 `message`/`toolResults`（**pi 两个字段都必填** —— `agent/types.ts:438`，原记只提到 `toolResults`）＋ `SessionRunner`/两条线各补载荷。⚠️ **颗粒度偏差**：pi 的 `turn_end` 是**每回合**一条、`AgentSettled` 是**每次驱动**一条 ⇒ 照字面取「最后一回合」会让该字段恒空（末回合通常纯文本收尾），故按**本次驱动**装（另登记）。⚠️ **残余偏差**：错误路的 `message` 为 null ⇒ 省略键（pi 那条路给合成失败消息） |
| B43 | **`tool_execution_*` 三条「工具执行生命周期」事件在会话层没有出口**（原记「`tool_execution_end` 语义错位」） | `docs/34 §2`（包⑦ 取证） | **2026-09-20 重新定范围（包⑦ 侦察）** —— 真根因不是「名字对、时刻不对」一条，而是**两面**：① **接线**：`PiLoop.Event.ToolExecutionStart/Update/End` **早已存在**、形状与 pi 逐字段相同、**且被 L5 逐帧验证过**（`conformance/pi-out/S2–S14.jsonl` 命中、`FrameNormalizer:55` 已归一化），但 `SessionRunner.passEvents:203-215` 只认 `MessageEnd`/`AgentStart`，**把这三条丢了**；`AgentSessionEvent`（`:18-71`）也没有对应变体。② **RPC 面**：pi 对非 `message_update` **原样透传**（`json-event.ts:48-51` 的 `return event;`）⇒ 这三条带全部载荷上线；`JsonEventMapper:148` 没有它们的分支 ⇒ 落到 `unsupported_event`。③ **web 面**：`AgentEventTranslator:84-95` 把 `StreamEvent.ToolCallStart/Delta/End`（＝「模型把调用吐完」）翻成这三个名字 ⇒ **错的源**。⚠️ 被推翻：原记「要重接线」—— 实际 `pi-java-agent-core` **生产代码零改动**。⚠️ **pi 没有 web UI**（全仓 grep 零命中）⇒ web 面**无 pi 对手**，载荷取舍见 `docs/34 §8.0 裁决 A`。**包⑦ 做**（`docs/34`）。✅ **包⑦ 已修（2026-09-20）**：`AgentSessionEvent` 加 3 个变体 ＋ `SessionRunner.passEvents` 转发 ＋ `JsonEventMapper` 三支（照 pi 透传，**全字段必填**）＋ `AgentEventTranslator` **换源**（流式 `ToolCall*` 不再伪造 `tool_execution_*`）。新增 `toolPayload()` 显式投影（裁决 B）。见 `docs/34 §10` |
| B44 | **两个桩的 `ToolCallStart` partial 与 pi 不同形** | `docs/33 §4-F/§4-G` | `FauxProvider:95-103`（空身份块）与 `ScriptedStreams:54`/`:85`（`blank = scripted("stop", List.of())`，**空内容**，全部事件共用）。⚠️ **`ScriptedStreams` 不能改** —— 它驱动 L5，改它＝改 L5 帧＝与 pi 侧共享的 golden 失效（`ConformanceRunner:138`）⇒ 包⑥ **只改 `FauxProvider`**，L5 桩的形状差异如实保留 |
| B45 | ~~**`pi-java-tui` 的 `spotbugs:check` 有 4 条 finding，卡住全树 `clean verify`**（`runToolCalls` 是 `AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE`；`assistantStreamed`／`thinkingRendered` 是 `AT_STALE_THREAD_WRITE_OF_PRIMITIVE`）~~ ✅ **已修（2026-09-20，用户裁决「真修，3 处最小」）** —— `ChatScreen` 的 `assistantStreamed`/`thinkingRendered` 加 `volatile`，`runToolCalls` 换 `AtomicInteger`（`++` 是读-改-写，volatile 不解决原子性），`finishRun` 把两处取值收成一次读。零语义改动，**未给并发夹具**（TUI 已降级；这类时序断言按 §8.23.8 ⑥ 口径是「运气断言」）。修后全树 `clean verify` **BUILD SUCCESS**、11 处 spotbugs 全 0 | `docs/33 §10.7`（实测输出） | 归属：**非包⑥ 引入**（本包工作树在 `pi-java-tui/` 下 0 个改动文件；`assistantStreamed` 出自 `d1e8d3a`、`runToolCalls` 出自 `b378b20`，2026-08-16 及更早，写点在包⑤ 之前就有的 `onStreamEvent`）。⚠️ **「此前为何没被拦住」未查明**（`spotbugs:check` 确实绑默认 `verify`，根 `pom.xml:172-180`）—— 没去追那条时间线 |
| B48 | ⚠️ **RPC 线的消息形状与 pi 不同**（两条，包⑨ 写夹具时撞出，**既有**、非包⑨ 引入） | `docs/36 §10.6`（实测序列化结果） | ① **没有 `role` 判别值** —— `Message.role()` 是接口方法、不是 record 组件，Jackson 不序列化它；实测 `{"content":[…],"stopReason":"stop"}` **无 role**。web 线靠 `WebWireJson:28` 手工 `put("role",…)` 补上，**RPC 线没有这道工序**。② **工具结果的键名是 `toolUseId`**，而 pi 线上叫 `toolCallId`（`agent-loop.ts:784-798`；web 线同样靠 `WebWireJson:34` 手工改名）。<br>⚠️ **2026-09-20 重新定范围（包⑩ 取证）—— 远不止两条**：拿 `JsonEventMapper.toWire(agent_end)` 实测 dump 一份「一 user ＋ 一 assistant ＋ 一 toolResult」后逐字段比，量出 **9 处差异 ＋ 一处会抛**。<br>**头条是「会抛」**：`JsonEventMapper` 用**裸** `ObjectMapper` ＋ 两个 mixin、**未注册 jsr310**，而 `AbstractChatApi:73` 给每条消息挂 `Instant.now()` ⇒ `agent_end` 的 `valueToTree` 抛 `IllegalArgumentException: Instant not supported`（**已用执行证实**）⇒ 按包⑦ 已核的隔离性，**丢该客户端整帧** ⇒ **生产上的 RPC 客户端收不到 `agent_end`**。<br>**为什么没人发现**：`FauxProvider` 自带的 `FauxChatApi` **直接实现 `ChatApi`、绕过 `AbstractChatApi`** ⇒ 夹具里的消息 timestamp 恒 null、永不触发（**测试与生产不同路**，另登记 A16）。<br>其余 8 处：三处缺 `role`；两处缺 `timestamp`（pi 必填）；`toolUseId`≠`toolCallId`；内容块判别值 `tool_use`≠pi 的 `toolCall`、thinking 字段名 `text`≠pi 的 `thinking`；`addedToolNames`/`signature`/`redacted` 该省的在写。**包⑩ 做**（`docs/37`，§8.1 步 1 是止血） |
| B49 | **`turn_end` 的颗粒度**：pi 是**每回合**一条，pi-java 的 `AgentSettled` 是**每次驱动**一条 | `docs/36 §10.4-D2` | 包⑨ 按「本次驱动」装 `toolResults`（照字面取末回合会**恒空**）。要真对齐得让 pi-java 也发 per-turn 事件 —— 那是**事件时序**的改动，另一包。**登记不修** |
| B46 | ⚠️ **pi-java 的 `BashTool` 从不调 `onUpdate`** ⇒ `tool_execution_update` **在生产上永不发射**（只有测试桩发） | `docs/34 §4-F`（登记处）＋ **包⑧ 取证更正（`docs/35 §2`）** | **2026-09-20 重新定范围（包⑧ 侦察）**：原记「pi 侧 7 个工具**全都有**」是**错的** —— 那是拿 `grep -l onUpdate` 数的，命中的是**声明**不是调用。逐行核过后：pi 侧**只有 `bash.ts` 真的调**（`:265` 与 `:297` 两处），`read`/`write`/`edit`/`grep`/`find`/`ls` 六家一律声明为 `_onUpdate?`（**下划线＝声明但不用**，TypeScript 的「故意未使用」约定）且全文再无引用；`powershell` 复用 `createShellToolDefinition` 故同样有。⇒ 本条的**真实范围只有 bash 一家**（pi-java 侧的 `BashTool` ＋ `ShellExecutor`）。pi-java 侧同法核过：7 个工具都收 `ToolUpdateCallback` 但**没有一条**调它 ⇒ 缺口成立、范围收窄。**包⑧ 修**。✅ **包⑧ 已修（2026-09-20）**：新 `ShellOutputSink` ＋ `ShellOptions` 第五个可空组件 ＋ `DefaultShellExecutor` 在既有 8 KiB 读循环里报增量 ＋ 新 `Utf8ChunkStream`（跨块多字节按字符边界解码）＋ 新 `BashUpdateEmitter`（逐位对齐 pi 的 `updateDirty`/`lastUpdateAt`/`updateTimer`，节流 100 ms）＋ `BashTool` 接线。**真实 `BashTool` 现在会发 `tool_execution_update`**（端到端夹具 `BashToolUpdateTest` 钉住）。见 `docs/35 §10` || B47 | **`pi-java-tui` 的 `tool_execution_*` 语义**：`ChatScreen.runToolCalls` 由 `StreamEvent.ToolCallStart` 计数（「模型吐完调用」），而 pi 的 TUI 由**真实工具执行事件**驱动 | `docs/34 §6-4` | 包⑦ **刻意不改**：会改变 `runToolCalls` 的计数时机（TUI 已按要求降级）。**登记不修**，待 TUI 退役或重估 |

---

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
| D8 | `AnthropicMessagesApi.java:299-301` 的注释写「ThinkingContent is dropped: replaying thinking blocks requires the original signature…」—— 而**它下面 `:288` 正是在重放**（生产源码里的假陈述） | §8.34.2-7（`docs/31:4227`） | **已修**（§8.34.11，包②：随 `toBlockParams` 重写删除；注释现指向 B17 的真实缺口）⇒ 见 G 类 |

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
| **B8** 空签名重放策略不可配（`compat.allowEmptySignature`） | `docs/31:4119`、§8.34.11 | 2026-09-18，包②（`ModelCompat` + `ModelInfo` 第 11 组件 + `CompatDef` + 投送链；两态已实测） |
| **B10** `transform-messages.ts` 跨模型重放闸整段缺失 | `docs/31:4119`、§8.34.11 | 2026-09-18，包②（`TransformMessages.java`）。⚠️ **只挂了 Anthropic 一条车道** ⇒ 接线另立包 |
| **B10 接线**（五条车道挂闸，`PiMessagesApi` 按裁决不挂） | `docs/31`、§8.35.11 | 2026-09-18，`d2a9c44` / `175a383` 等 4 个提交（夹具 `LaneTransformMessagesWiringTest` 6/6，先红证毕 5 红 1 绿） |
| **B19** `openai-completions` 响应侧 reasoning 收/发两侧（事故根因） | `docs/31:4604`、§8.35.13 | 2026-09-19，`ffc43e2`（夹具先红 13 红 4 绿）/ `b916d29`（收）/ `478fe91`（发）/ `7369ae6`（models.json）。⚠️ `reasoning_details` 结构化形状**仍在行外** |
| **§8.35.2 两处结论作废**（B20 设计定稿时逐行复核 pi 收尾语义所证伪） | `docs/31`、§8.35.14 第四/五节 | 2026-09-19。① 表格末行「Responses ✅ 已对齐」→ **α/β/γ/δ/ε 五处差距**（default 不抛 / `incomplete` 无文案 / `done("error")` / `failed` 文案 / 出错后继续消费）；② 「`rawStopReason` 零消费者 ⇒ 不移植」的**结论**错（读点在**生产者层** Google 车道 2 处）⇒ 改列 D5、登记 B25。**两处原文均保留并就地加作废批注**（历史记录性质）。教训形态：**「谁读它」要在生产者层问一遍，不能只问对齐面** |
| **B11** 空 text 块不丢 | `docs/31:4119`、§8.34.11 | 2026-09-18，包②（`toBlockParams` 的 trim 判空） |
| **B13** 重放路径产不出 `redacted_thinking` 线格 | `docs/31:4119`、§8.34.11 | 2026-09-18，包②（`appendThinkingBlock` 的 redacted 分支） |
| **D8** `AnthropicMessagesApi:299-301` 的假注释（写「ThinkingContent is dropped」，而它其实被路由到 `appendThinkingBlock`） | §8.34、§8.34.11 | 2026-09-18，包②（随 `toBlockParams` 重写一并删除） |
| **B5** 宿主失败通路的活性收口（两处 `catch (Throwable)` ＋ `finally` 幂等兜底 ＋ 读尾 assistant 定终局）＋ `handleRunFailure` 落引擎侧 | `docs/31`、§8.36.5 / §8.36.6 / §8.36.8 | 2026-09-19，包③，`16ca4d7`（agent-core：`RunFailure` + 两相拆分）/ `f16436b`（coding-agent：宿主侧）。夹具各 3/3，五条变异探针实测（P1/P2/P3/P5 各恰一条红；P4 ⇒ C 组两条 + 宿主 A3）。⚠️ **实测证伪设计稿两处**：① P2 的红集是 `{B1}` 而非 `{A1,B1,B3}` —— `finally` 只在「外层 catch 体自身再抛」那条路上是出口；② 夹具 2b 的注入点必须是 `AgentEnd`（`MessageUpdate` 上抛的 `Error` 会被引擎吞掉）。**L5 的 14 个剧本全绿不是本包第 2 步的证据**（桩 Stream 造不出引擎内抛出） |
| **B37** `ChatScreen.lastError` 常驻状态栏（pi 没有这个构造，且是双报） | `docs/31`、§8.38.3-F / §8.38.9-(5) | 2026-09-19，包⑤，`42d49ec`（删字段与状态栏分支；错误只进聊天区） |
| **B3** TUI/RPC 对 auto_retry / summarization_retry 的渲染 —— **两条块全部落地** | `docs/31`、§8.37.9 / §8.38.9 | 块1（`get_state` 四状态字段）＝包④ `3cf1181`；块2（TUI 订阅通道 ＋ 指示器 ＋ 五个事件）＝包⑤ `42d49ec`。⚠️ 行内原措辞「`isRetrying` / isIdle 含重试」是**登记时的猜测**，取证已更正（pi 零消费者）—— **结案的是真正存在的缺口，不是那三个名字** |

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
6. **一包一文档（用户 2026-09-19）**：自 `docs/33` 起，**每个包在自己的文件里**记设计、取证与实施记录，
   **不再往 `docs/31` 追加**。`docs/31`（§8.1–§8.38）**冻结为历史**，不重写。本台账仍是**唯一索引** ——
   每条登记仍要在这里留行（出处写新文档的章节）。
7. **流程（用户 2026-09-19 提出，已采纳）**：包内三步可见 —— ① **命题表**（每条断言 `file:line` 待核，
   **不写分步计划**）；② **逐条代码验证**（打勾/推翻，**被推翻的当场列出**）；③ 基于核过的表写实施稿，
   **用户审核后才写代码**。理由：实施后报「与设计稿有偏差」的根因全部落在**没核的命题**上。
